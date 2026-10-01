#!/usr/bin/env bash
# Checks for perf-test-allocprofile.sh's ceiling-window annotation: the size cap, the per-role hot
# methods, monitor and GC summaries over a JSON fixture, and every degrade path (no recording, no
# Docker, `jfr` unable to run, a corrupt recording, a timed-out call) ending in a one-line note,
# never a failure. Docker-free: `docker` and `timeout` are stubbed. The functions are lifted from
# the real script, not copied.
# Run: .buildkite/scripts/test/perf-allocprofile-annotation-test.sh
#   PERF_ALLOCPROFILE_TEST_REAL=true   also run the corrupt-recording check through the real `jfr`
#   PERF_ALLOCPROFILE_TEST_BUNDLE=<allocprofile-perf-jvm-diagnostics.tgz>   and render a real bundle
#   PERF_ALLOCPROFILE_TEST_BUNDLE_PERIODS=<n>   and expect n sampling periods in it (build 537: 7)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
F="${PERF_ALLOCPROFILE_SCRIPT:-$REPO_ROOT/.buildkite/scripts/steps/perf-test-allocprofile.sh}"
FAILS=0
ok()   { echo "  ok   $1"; }
bad()  { echo "  FAIL $1" >&2; FAILS=$((FAILS + 1)); }
check() { # name expected actual
  if [ "$2" = "$3" ]; then ok "$1"; else bad "$1: expected '$2', got '$3'"; fi
}
has() { # name needle haystack
  if grep -qF -- "$2" <<<"$3"; then ok "$1"; else bad "$1: '$2' not in output"; printf '%s\n' "$3" | head -20 >&2; fi
}
lacks() { # name needle haystack
  if grep -qF -- "$2" <<<"$3"; then bad "$1: '$2' in output"; printf '%s\n' "$3" | head -20 >&2; else ok "$1"; fi
}
WORK="$(mktemp -d "${TMPDIR:-/tmp}/perf-allocprofile-annotation-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

extract() {
  awk -v n="$1" '$0 ~ "^"n"\\(\\) *\\{" {p=1; print; if ($0 ~ /}$/) exit; next} p {print} p && /^}/ {exit}' "$F"
}
for fn in jfr_tool cap_annotation thread_cpu_period thread_cpu_table role_hot_methods monitor_threshold monitor_enter_summary \
          gc_cycle_summary jfr_view_block ceiling_profile load_window_figures heap_inspection_placement \
          largest_repo_dir assemble_finished_chunks emit_allocation_annotation annotate; do
  body="$(extract "$fn")"
  if [ -z "$body" ]; then bad "function $fn not found in $F"; continue; fi
  eval "$body"
done
# The step's knobs, as the script defaults them.
eval "$(grep -E '^[A-Z_]+="\$\{PERF_ALLOCPROFILE_[A-Z_]+:-[^}]*\}"$' "$F")"
[ "$FAILS" -eq 0 ] || { echo "FAILED: $FAILS check(s)" >&2; exit 1; }
JDK_IMAGE="${PERF_JFR_JDK_IMAGE:-$(bash -c '. "$1"; echo "$PERF_JFR_JDK_IMAGE"' _ "$REPO_ROOT/.buildkite/scripts/steps/lib/perf-jfr-image.sh")}"
TIMEOUT_ARGS="$WORK/timeout-args"; : > "$TIMEOUT_ARGS"
# macOS has no timeout(1), and the stubs never hang; this records each call so -k can be asserted.
timeout() { local a="$*"; printf '%s\n' "${a//$'\n'/ }" >> "$TIMEOUT_ARGS"; [ "$1" != -k ] || shift 2; shift; "$@"; }

echo "--- 1. cap_annotation keeps a body under the byte limit"
small=$'line one\n```\ncode\n```'
check "a small body passes through unchanged" "$small" "$(printf '%s\n' "$small" | cap_annotation 1000000)"
big="$( { echo '### head'; echo '```'; for i in $(seq 1 400); do echo "row $i é padded to make the body longer than the cap"; done; echo '```'; } | cap_annotation 4000)"
bytes="$(printf '%s\n' "$big" | LC_ALL=C wc -c | tr -d ' ')"
if [ "$bytes" -le 4000 ]; then ok "a large body is cut to at most the cap ($bytes bytes)"; else bad "capped body is $bytes bytes, over 4000"; fi
check "the cut closes the open code fence" "0" "$(( $(grep -c '^```' <<<"$big") % 2 ))"
has "the cut says where the rest is" "Annotation truncated at" "$big"
# Under a UTF-8 locale gawk's length() counts characters, which would let this body through at ~2x the cap.
if [ "$(LC_ALL=C.UTF-8 awk 'BEGIN { print length("é") }' 2>/dev/null)" = 2 ]; then
  echo "  skip the cap counts bytes, not characters (this awk counts bytes in any locale, so it cannot discriminate)"
else
  dense="$(for _ in $(seq 1 20); do printf 'é%.0s' $(seq 1 300); echo; done | LC_ALL=C.UTF-8 cap_annotation 1000 2>/dev/null)"
  bytes="$(printf '%s\n' "$dense" | LC_ALL=C wc -c | tr -d ' ')"
  if [ "$bytes" -le 1000 ]; then ok "the cap counts bytes, not characters ($bytes bytes of 2-byte text)"; else bad "multibyte body is $bytes bytes, over 1000"; fi
fi

echo "--- 2. per-role hot methods, monitor and GC summaries over a JSON fixture"
frame() { printf '{"method":{"type":{"name":"%s"},"name":"%s"}}' "$1" "$2"; }
{
  echo '{"recording":{"events":['
  for i in 1 2 3 4 5 6; do
    printf '{"type":"jdk.CPUTimeSample","values":{"failed":false,"eventThread":{"javaName":"io-worker%s"},"stackTrace":{"frames":[%s]}}},\n' \
      "$(( i % 2 + 1 ))" "$(frame io/netty/Socket sendAddress)"
  done
  printf '{"type":"jdk.CPUTimeSample","values":{"failed":false,"eventThread":{"javaName":"io-worker1"},"stackTrace":{"frames":[%s]}}},\n' "$(frame org/mockserver/HttpState handle)"
  printf '{"type":"jdk.CPUTimeSample","values":{"failed":false,"eventThread":{"javaName":"EventLog0"},"stackTrace":{"frames":[%s]}}},\n' "$(frame org/mockserver/LogEntry clone)"
  printf '{"type":"jdk.CPUTimeSample","values":{"failed":true,"eventThread":{"javaName":"EventLog0"},"stackTrace":{"frames":[%s]}}},\n' "$(frame x/Failed sample)"
  printf '{"type":"jdk.ExecutionSample","values":{"sampledThread":{"javaName":"other0"},"stackTrace":{"frames":[%s]}}},\n' "$(frame a/B c)"
  echo '{"type":"jdk.JavaMonitorEnter","values":{"duration":"PT0.0025S","monitorClass":{"name":"java/lang/Object"},"eventThread":{"javaName":"io-worker2"}}},'
  echo '{"type":"jdk.JavaMonitorEnter","values":{"duration":"PT0.0012S","monitorClass":{"name":"java/lang/Object"},"eventThread":{"javaName":"io-worker1"}}},'
  echo '{"type":"jdk.GarbageCollection","values":{"name":"ZGC Minor","cause":"Allocation Rate","duration":"PT0.4S","sumOfPauses":"PT0.00004S","longestPause":"PT0.000016S"}},'
  echo '{"type":"jdk.GarbageCollection","values":{"name":"ZGC Minor","cause":"Allocation Rate","duration":"PT0.2S","sumOfPauses":"PT0.00003S","longestPause":"PT0.00002S"}},'
  echo '{"type":"jdk.GarbageCollection","values":{"name":"ZGC Major","cause":"Proactive","duration":"PT1M2.5S","sumOfPauses":"PT0.0001S","longestPause":"PT0.00005S"}}'
  echo ']}}'
} > "$WORK/events.json"
roles="$(role_hot_methods "$WORK/events.json")"
has "CPU-time samples are preferred, failed ones dropped" "from 8 CPU-time samples" "$roles"
has "threads group by name without their number" "| **io-worker** (87.5% of all samples) | | | 7 |" "$roles"
has "a method's share is of its role" '| | `io.netty.Socket.sendAddress` | 85.7 | 6 |' "$roles"
check "the busiest role comes first" "io-worker" "$(grep -m1 -o '\*\*[^*]*\*\*' <<<"$roles" | tr -d '*')"
mon="$(monitor_enter_summary "$WORK/events.json" "1 ms")"
has "monitor count is reported beside its threshold" "recorded threshold, **1 ms**): **2**" "$mon"
has "the longest monitor wait is in ms" "longest 2.5 ms" "$mon"
check "no events reads zero, not unavailable" "- \`jdk.JavaMonitorEnter\` events (a thread blocked on a monitor for at least the recorded threshold, **1 ms**): **0**" \
  "$(echo '{"recording":{"events":[]}}' > "$WORK/none.json"; monitor_enter_summary "$WORK/none.json" "1 ms")"
has "an unknown threshold is said, not hidden" "cannot be told from a disabled event" "$(monitor_enter_summary "$WORK/events.json" "")"
has "a disabled event is said" "was **disabled**" "$(monitor_enter_summary "$WORK/events.json" "disabled")"
gc="$(gc_cycle_summary "$WORK/events.json")"
has "GC cycles are summed per collector" "| ZGC Minor | 2 | 600 | 400 | 0.07 | 0.02 | Allocation Rate ×2 |" "$gc"
has "a cycle over a minute parses (PT1M2.5S)" "| ZGC Major | 1 | 62500 | 62500 |" "$gc"
check "unreadable events give one line, not a failure" "(per-role hot methods unavailable: the sample events could not be read)" \
  "$(echo 'not json' > "$WORK/bad.json"; role_hot_methods "$WORK/bad.json")"
check "unreadable GC events give one line" "(GC cycle summary unavailable: the events could not be read)" "$(gc_cycle_summary "$WORK/bad.json")"
: > "$WORK/empty.json"   # what a failed `jfr print` leaves behind
check "empty events: per-role note" "(per-role hot methods unavailable: the sample events could not be read)" "$(role_hot_methods "$WORK/empty.json")"
check "empty events: monitor note" "- \`jdk.JavaMonitorEnter\`: unavailable (the events could not be read)" "$(monitor_enter_summary "$WORK/empty.json" "1 ms")"
check "empty events: GC note" "(GC cycle summary unavailable: the events could not be read)" "$(gc_cycle_summary "$WORK/empty.json")"

echo "--- 2b. per-thread CPU: the busiest-period flag on both sides of HOT_THREAD_PCT ($HOT_THREAD_PCT)"
cpu_events() { # worker load in the first period (fraction of all CPUs) -> a jfr print --json fixture, 6 CPUs
  printf '{"recording":{"events":[{"type":"jdk.ContainerConfiguration","values":{"effectiveCpuCount":6}},{"type":"jdk.CPUInformation","values":{"hwThreads":12}},'
  printf '{"type":"jdk.ThreadCPULoad","values":{"startTime":"2026-09-30T21:34:00.1Z","eventThread":{"javaName":"worker1"},"user":%s,"system":0}},' "$1"
  printf '{"type":"jdk.ThreadCPULoad","values":{"startTime":"2026-09-30T21:34:00.1Z","eventThread":{"javaName":"logger0"},"user":0.02,"system":0}},'
  printf '{"type":"jdk.ThreadCPULoad","values":{"startTime":"2026-09-30T21:34:10.1Z","eventThread":{"javaName":"worker1"},"user":0.01,"system":0}},'
  printf '{"type":"jdk.ThreadCPULoad","values":{"startTime":"2026-09-30T21:34:10.1Z","eventThread":{"javaName":"logger0"},"user":0.02,"system":0}}]}}\n'
}
docker() { cpu_events "$CPU_LOAD"; }
hot="$(CPU_LOAD=0.16 thread_cpu_table "$WORK" x.jfr)"      # 0.16 x 6 CPUs = 96% of one core in one period
has "a thread above the flag in one period is flagged" ":warning: **worker1** reached **96% of one core**" "$hot"
has "the window average sits beside the peak" "| worker1 | 51 | 96 | n/a | 2 |" "$hot"
cool="$(CPU_LOAD=0.12 thread_cpu_table "$WORK" x.jfr)"     # 72% of one core
has "a thread below the flag is not flagged" "Busiest sampling period: worker1 at 72% of one core" "$cool"
if grep -q ':warning:' <<<"$cool"; then bad "a thread below the flag was flagged"; else ok "no warning below the flag"; fi
edge="$(CPU_LOAD=0.1493 thread_cpu_table "$WORK" x.jfr)"   # 89.6% of one core: shown as 90, so flagged as 90
has "the flag decides on the rounded figure it prints" ":warning: **worker1** reached **90% of one core**" "$edge"
has "without a rung schedule the line says a zero flag proves little" "is not proof that no thread was near a full core" "$cool"
docker() { return 1; }
check "a failed jfr print gives the per-thread note" "(per-thread CPU unavailable: jfr print failed)" "$(thread_cpu_table "$WORK" x.jfr)"
docker() { :; }
check "an empty jfr print gives the per-thread note, not nothing" "(per-thread CPU unavailable: jfr print failed)" "$(thread_cpu_table "$WORK" x.jfr)"

echo "--- 2c. per-thread CPU scaled to the rung schedule: a 15 s rung + 5 s gap is two 10 s periods"
T0=1790800000
mkdir -p "$WORK/phase"
jq -n --argjson t0 "$T0" '[range(0; 4) | {offered_rps: 1000, start_epoch_ms: (($t0 + . * 20) * 1000), end_epoch_ms: (($t0 + . * 20 + 15) * 1000)}]' \
  > "$WORK/phase/sweep-rungs.json"
phase_events() { # first sample offset (s): a worker pegged at 100% of one core inside every rung, idle in the gaps
  jq -n --argjson t0 "$T0" --argjson off "$1" --slurpfile r "$WORK/phase/sweep-rungs.json" '
    def inrung($t): [$r[0][] | ([$t, .end_epoch_ms / 1000] | min) - ([$t - 10, .start_epoch_ms / 1000] | max) | select(. > 0)] | add // 0;
    def iso: . as $x | ($x | floor | todate | sub("Z$"; "")) + "." + ((($x - ($x | floor)) * 1000 | round) + 1000 | tostring | .[1:]) + "Z";
    {recording: {events: ([{type: "jdk.ContainerConfiguration", values: {effectiveCpuCount: 6}}]
      + [range(0; 7) | ($t0 + $off + . * 10) as $t
         | {type: "jdk.ThreadCPULoad", values: {startTime: ($t | iso), eventThread: {javaName: "worker1"}, user: (inrung($t) / 10 / 6), system: 0}},
           {type: "jdk.ThreadCPULoad", values: {startTime: ($t | iso), eventThread: {javaName: "C2 CompilerThread0"}, user: (0.99 / 6), system: 0}}])}}'
}
docker() { phase_events "$PHASE"; }
bad_phase="$(PHASE=17.5 thread_cpu_table "$WORK/phase" x.jfr)"   # every period straddles a rung edge: 7.5 s in a rung
has "bad phase: the raw peak reads 75%" "| worker1 | " "$bad_phase"
has "bad phase: the raw peak column is 75" "| 75 | 100 |" "$bad_phase"
has "bad phase: the in-rung peak is flagged" ":warning: **worker1** reached **100% of one core** while a rung ran" "$bad_phase"
good_phase="$(PHASE=10 thread_cpu_table "$WORK/phase" x.jfr)"    # alternate periods sit wholly inside a rung
has "good phase: the in-rung peak is flagged too" ":warning: **worker1** reached **100% of one core** while a rung ran" "$good_phase"
if grep -q 'CompilerThread0\*\* reached' <<<"$bad_phase$good_phase"; then bad "a JVM-internal thread was flagged"; else ok "JVM-internal threads are not flagged"; fi
idle_phase="$(PHASE=17.5 HOT_THREAD_PCT=101 thread_cpu_table "$WORK/phase" x.jfr)"
has "below the flag, the scaled line says so" "Busiest in-rung period: worker1 at 100% of one core" "$idle_phase"

echo "--- 2d. per-thread CPU: the 0.5 cut-off, raw fallbacks, figures over 100%, and the sampling period"
tl_fixture() { # rungs_json < "offset_s|thread|rung or flat|% of one core[|drain_s]" lines -> a jfr print --json fixture, 6 CPUs
  # "rung": that load while a rung ran and idle in the gaps, over a 10 s period; "flat": that load throughout.
  # drain_s: a full core for that long after each rung ends (a backlog drained between rungs).
  jq -R -n --argjson t0 "$T0" --slurpfile r "$1" '
    def inrung($t): [$r[0][] | ([$t, .end_epoch_ms / 1000] | min) - ([$t - 10, .start_epoch_ms / 1000] | max) | select(. > 0)] | add // 0;
    def drain($t; $d): [$r[0][] | ([$t, .end_epoch_ms / 1000 + $d] | min) - ([$t - 10, .end_epoch_ms / 1000] | max) | select(. > 0)] | add // 0;
    def iso: . as $x | ($x | floor | todate | sub("Z$"; "")) + "." + ((($x - ($x | floor)) * 1000 | round) + 1000 | tostring | .[1:]) + "Z";
    {recording: {events: ([{type: "jdk.ContainerConfiguration", values: {effectiveCpuCount: 6}}]
      + [inputs | select(length > 0) | split("|") | ($t0 + (.[0] | tonumber)) as $t
         | {type: "jdk.ThreadCPULoad", values: {startTime: ($t | iso), eventThread: {javaName: .[1]},
            user: ([(.[3] | tonumber) / 100 * (if .[2] == "rung" then inrung($t) / 10 else 1 end) + drain($t; (.[4] // "0" | tonumber)) / 10, 1]
                   | min / 6), system: 0}}])}}'
}
batches() { # first_offset count spec... -> each spec at first_offset + 10 k, k = 0 .. count - 1
  local off="$1" n="$2" k s; shift 2
  for k in $(seq 0 $((n - 1))); do for s in "$@"; do echo "$(awk -v o="$off" -v k="$k" 'BEGIN { print o + 10 * k }')|$s"; done; done
}
FIX="$WORK/fixture.json"
docker() { # `jfr print` gives $FIX; `jfr view active-settings` gives $SETTINGS (ceiling.jfr) or $LOAD_SETTINGS (load.jfr)
  case " $* " in
    *" active-settings "*) if [ "${*: -1}" = /w/sut/load.jfr ]; then printf '%s\n' "${LOAD_SETTINGS:-}"; else printf '%s\n' "${SETTINGS:-}"; fi ;;
    *) cat "$FIX" ;;
  esac
}
settings_view() { # Thread CPU Load period -> a `jfr view active-settings` table
  printf '%-55s %-22s %-24s %-26s %-25s\n' 'Event Type' Enabled Threshold 'Stack Trace' Period \
    'Java Monitor Blocked' true '1 ms' true '' 'Thread CPU Load' true '' '' "$1"
}
cpu_settings="$(settings_view '10 s')"
check "period: 10 s" "10" "$(SETTINGS="$cpu_settings" thread_cpu_period "$WORK/phase" x.jfr)"
check "period: 20 ms" "0.02" "$(SETTINGS="$(settings_view '20 ms')" thread_cpu_period "$WORK/phase" x.jfr)"
check "period: 10s, unit unspaced" "10" "$(SETTINGS="$(settings_view '10s')" thread_cpu_period "$WORK/phase" x.jfr)"
check "period: everyChunk is not a period" "" "$(SETTINGS="$(settings_view 'everyChunk')" thread_cpu_period "$WORK/phase" x.jfr)"
check "period: no settings events" "" "$(thread_cpu_period "$WORK/phase" x.jfr)"
# Periods end at offsets 2 + 10 k: the first is 2 s inside rung 0 (share 0.2), the rest 1 or 0.5.
{ echo "2|worker1|flat|30"; batches 12 7 "worker1|rung|100"; batches 2 8 "logger0|flat|1"; } | tl_fixture "$WORK/phase/sweep-rungs.json" > "$FIX"
r="$(thread_cpu_table "$WORK/phase" x.jfr)"   # without the cut-off the edge sample scales to 30 / 0.2 = 150%
has "cut-off: an edge sample under half inside a rung is not scaled" "| worker1 | 72.5 | 100 | 100 | 8 |" "$r"
has "cut-off: the in-rung flag reads the kept samples" ":warning: **worker1** reached **100% of one core** while a rung ran" "$r"
{ echo "2|short-1|flat|95"; batches 2 8 "worker1|rung|60" "logger0|flat|1"; } | tl_fixture "$WORK/phase/sweep-rungs.json" > "$FIX"
r="$(thread_cpu_table "$WORK/phase" x.jfr)"
has "ranking: a raw peak is not ranked against in-rung ones, but named with its raw peak" \
  "Busiest in-rung period: worker1 at 60% of one core (its busiest period, scaled to the part inside a rung; flagged at 90%). Not judged on a scaled figure: short-1 (no sample at least half inside a rung; raw peak 95%). This is not proof that no thread was near a full core." "$r"
lacks "ranking: the raw 95% is not worded as in-rung" ":warning:" "$r"
lacks "ranking: no all-clear while a thread is unscaled" "no application thread was near a full core" "$r"
# Mixed: the event-log consumer pegged in each rung and draining 1 s after it reads 113% scaled, so it is
# only bounded (80-100%), while a worker scales to 85%: the line must not give the all-clear.
batches 17.5 7 "MockServer-EventLog0|rung|100|1" "worker1|rung|85" "logger0|flat|1" | tl_fixture "$WORK/phase/sweep-rungs.json" > "$FIX"
r="$(thread_cpu_table "$WORK/phase" x.jfr)"
has "mixed: the bounded thread shows its range" "| MockServer-EventLog0 | 80.7 | 85 | 80–100 | 7 |" "$r"
has "mixed: the bounded thread is named with its range and raw peak" \
  "Busiest in-rung period: worker1 at 85% of one core (its busiest period, scaled to the part inside a rung; flagged at 90%). Not judged on a scaled figure: MockServer-EventLog0 (worked between rungs too, so its busiest in-rung period was 80–100% of one core; raw peak 85%). This is not proof that no thread was near a full core." "$r"
lacks "mixed: no all-clear" "no application thread was near a full core" "$r"
batches 17.5 7 "MockServer-EventLog0|rung|100|1.75" "worker1|rung|85" "logger0|flat|1" | tl_fixture "$WORK/phase/sweep-rungs.json" > "$FIX"
has "mixed: a lower bound at the threshold flags" ":warning: **MockServer-EventLog0** reached at least **90% of one core** while a rung ran" \
  "$(thread_cpu_table "$WORK/phase" x.jfr)"
mkdir -p "$WORK/short"
jq -n --argjson t0 "$T0" '[range(0; 4) | {offered_rps: 1000, start_epoch_ms: (($t0 + . * 20) * 1000), end_epoch_ms: (($t0 + . * 20 + 4) * 1000)}]' \
  > "$WORK/short/sweep-rungs.json"
batches 5 8 "worker1|rung|100" "logger0|flat|1" | tl_fixture "$WORK/short/sweep-rungs.json" > "$FIX"
r="$(thread_cpu_table "$WORK/short" x.jfr)"   # 4 s rungs: no period is half inside one
has "4 s rungs: no in-rung figure" "| worker1 | 20 | 40 | n/a | 8 |" "$r"
has "4 s rungs: the raw fallback says why and keeps its caveat" \
  "Busiest sampling period: worker1 at 40% of one core (flagged at 90%). No application thread had a sample at least half inside a rung, so periods are not scaled: one that straddles a rung edge reads as little as ~75%" "$r"
# Bad phase (every period 7.5 s in a rung), plus three lone thread-exit samples 5 s off the cadence.
{ batches 17.5 7 "worker1|rung|100" "logger0|flat|1"; batches 22.5 3 "exit-1|flat|5" | awk -F'|' '{ $1 = $1 + 10 * (NR - 1); print }' OFS='|'; } \
  | tl_fixture "$WORK/phase/sweep-rungs.json" > "$FIX"
r="$(thread_cpu_table "$WORK/phase" x.jfr)"   # counted as batches, they halve the median gap: 150%
has "exit samples: not counted as sampling periods" "7 sampling periods" "$r"
has "exit samples: the period estimate is not skewed" ":warning: **worker1** reached **100% of one core** while a rung ran" "$r"
# Two threads exiting together are a batch of two, which the estimate cannot drop; the setting gives the period.
{ batches 17.5 7 "worker1|rung|100" "logger0|flat|1"; batches 22.5 3 "exit-1|flat|5" "exit-2|flat|5" | awk -F'|' '{ $1 = $1 + 10 * int((NR - 1) / 2); print }' OFS='|'; } \
  | tl_fixture "$WORK/phase/sweep-rungs.json" > "$FIX"
r="$(thread_cpu_table "$WORK/phase" x.jfr)"
lacks "two-thread exits: the fixture skews an estimated period" "100% of one core** while a rung ran" "$r"
r="$(SETTINGS="$cpu_settings" thread_cpu_table "$WORK/phase" x.jfr)"
has "the period comes from the recording's settings" ":warning: **worker1** reached **100% of one core** while a rung ran" "$r"
has "with a known period, off-cadence batches are not counted as periods" "7 sampling periods" "$r"
for bad_period in '20 ms' '0 s'; do
  has "a recorded period of $bad_period is not used" "The sampling period could not be told, so periods are not scaled" \
    "$(SETTINGS="$(settings_view "$bad_period")" thread_cpu_table "$WORK/phase" x.jfr)"
done
mkdir -p "$WORK/loadset/sut"; cp "$WORK/phase/sweep-rungs.json" "$WORK/loadset/"; printf 'jfr' > "$WORK/loadset/sut/load.jfr"
r="$(LOAD_SETTINGS="$cpu_settings" thread_cpu_table "$WORK/loadset" x.jfr)"
has "an older ceiling.jfr without settings reads the period from load.jfr" ":warning: **worker1** reached **100% of one core** while a rung ran" "$r"
batches 17.5 7 "background-1|flat|80" "worker1|rung|50" "logger0|flat|1" | tl_fixture "$WORK/phase/sweep-rungs.json" > "$FIX"
r="$(thread_cpu_table "$WORK/phase" x.jfr)"   # 80 / 0.75 = 107% in-rung: it worked in the gaps too
has "over 100%: the cell shows the bound, not the scaled figure" "| background-1 | 80 | 80 | 73–100 | 7 |" "$r"
has "over 100%: the table says what the range means" "A range in Peak in-rung: scaled to its in-rung part, that period passed a full core" "$r"
has "over 100%: the line names the thread with its bound" "background-1 (worked between rungs too, so its busiest in-rung period was 73–100% of one core; raw peak 80%)" "$r"
lacks "over 100%: it does not drive the flag on its own" ":warning:" "$r"
batches 17.5 7 "bg-1|flat|84" "bg-2|flat|83" "bg-3|flat|82" "bg-4|flat|81" "worker1|rung|50" "logger0|flat|1" \
  | tl_fixture "$WORK/phase/sweep-rungs.json" > "$FIX"
r="$(thread_cpu_table "$WORK/phase" x.jfr)"   # four bounded threads (75-79% lower bounds), none flagged
has "bounded threads: the line names three, then counts the rest" "bg-3 (worked between rungs too" "$r"
has "bounded threads: the rest are counted" "; and 1 more. This is not proof that no thread was near a full core." "$r"
lacks "bounded threads: the fourth is not named" "bg-4 (" "$r"
lacks "bounded threads: no flag" ":warning:" "$r"
batches 17.5 7 "spin-1|flat|100" "logger0|flat|1" | tl_fixture "$WORK/phase/sweep-rungs.json" > "$FIX"
r="$(thread_cpu_table "$WORK/phase" x.jfr)"
has "over 100% with a pegged thread: flagged, on its lower bound" \
  ":warning: **spin-1** reached at least **100% of one core** while a rung ran" "$r"
has "a lower bound of 100 shows as at least 100" "| spin-1 | 100 | 100 | ≥100 | 7 |" "$r"
lacks "no range shown, so no range note" "A range in Peak in-rung" "$r"
batches 17.5 7 "worker1|flat|77" "logger0|flat|1" | tl_fixture "$WORK/phase/sweep-rungs.json" > "$FIX"
r="$(thread_cpu_table "$WORK/phase" x.jfr)"   # 77 / 0.75 = 103%: rung-edge slop, shown as a full core
has "slop just over 100%: shown at 100" "| worker1 | 77 | 77 | 100 | 7 |" "$r"
has "slop just over 100%: flagged in-rung" ":warning: **worker1** reached **100% of one core** while a rung ran" "$r"
batches 17.5 7 "C2 CompilerThread-pool-1|rung|95" "Attach Listener|rung|100" "C1 CompilerThread3|rung|100" "logger0|flat|1" \
  | tl_fixture "$WORK/phase/sweep-rungs.json" > "$FIX"
r="$(thread_cpu_table "$WORK/phase" x.jfr)"
has "a worker named like a compiler thread is still flagged" ":warning: **C2 CompilerThread-pool-1** reached **95% of one core**" "$r"

echo "--- 2e. per-thread CPU with a wrong-schema rung schedule: the unscaled table, not a failure"
docker() { cpu_events "${CPU_LOAD:-0.16}"; }
mkdir -p "$WORK/schema"; cp "$WORK/phase/sweep-rungs.json" "$WORK/schema/good.json"
for case in string-epoch missing-end two-documents empty not-json; do
  case "$case" in
    string-epoch) jq '.[0].start_epoch_ms |= tostring' "$WORK/schema/good.json" ;;
    missing-end) jq 'del(.[1].end_epoch_ms)' "$WORK/schema/good.json" ;;
    two-documents) cat "$WORK/schema/good.json" "$WORK/schema/good.json" ;;
    empty) echo '[]' ;;
    not-json) echo '[{"start_epoch_ms":' ;;
  esac > "$WORK/schema/sweep-rungs.json"
  r="$(thread_cpu_table "$WORK/schema" x.jfr)"
  has "$case: the per-thread table survives" "| worker1 | 51 | 96 | n/a | 2 |" "$r"
  has "$case: the line says the schedule is unusable" "The rung schedule in the bundle (sweep-rungs.json) is empty or malformed, so periods are not scaled" \
    "$(CPU_LOAD=0.12 thread_cpu_table "$WORK/schema" x.jfr)"
done
unset -f docker

echo "--- 3. ceiling_profile degrade paths: a one-line note and return 1, never a failure"
bundle() { # dir: a ceiling-window.json naming sut/ceiling.jfr, and a non-empty recording file
  mkdir -p "$1/sut"
  echo '{"jfr":"sut/ceiling.jfr","begin_epoch_ms":0,"end_epoch_ms":75000,"first_rung_rps":40000,"last_rung_rps":64000,"saturation_rps":40000,"begin_iso":"a","end_iso":"b"}' > "$1/ceiling-window.json"
  printf 'not a recording' > "$1/sut/ceiling.jfr"
}
profile() { # work_dir -> "<rc>|<output>"
  local rc=0 out; out="$(ceiling_profile "$1" 2>&1)" || rc=$?
  printf '%s|%s' "$rc" "$out"
}
mkdir -p "$WORK/none"; echo '{"jfr":null,"error":"dump failed"}' > "$WORK/none/ceiling-window.json"
r="$(profile "$WORK/none")"; check "no recording: rc 1" "1" "${r%%|*}"; has "no recording: the reason" "No ceiling recording in the bundle (dump failed)." "$r"

bundle "$WORK/nodocker"; mkdir -p "$WORK/bin"; ln -sf "$(command -v jq)" "$WORK/bin/jq"
r="$(PATH="$WORK/bin" profile "$WORK/nodocker")"; check "no Docker: rc 1" "1" "${r%%|*}"; has "no Docker: a note" "Docker is unavailable on this agent" "$r"

docker() { return 125; }   # the image cannot be pulled or run
bundle "$WORK/notool"; r="$(profile "$WORK/notool")"; check "jfr cannot run: rc 1" "1" "${r%%|*}"; has "jfr cannot run: a note" "tool could not run" "$r"

docker() { local a; for a in "$@"; do shift; [ "$a" = jfr ] && break; done
  case "$1" in version) echo "25";; summary) echo "jfr summary: invalid file" >&2; return 1;; *) return 1;; esac; }
bundle "$WORK/corrupt"; r="$(profile "$WORK/corrupt")"; check "corrupt recording: rc 1" "1" "${r%%|*}"; has "corrupt recording: a note" "could not read \`sut/ceiling.jfr\` (corrupt or truncated" "$r"

docker() { local a; for a in "$@"; do shift; [ "$a" = jfr ] && break; done
  case "$1" in version) echo "25";; summary) printf ' Duration: 75 s\n jdk.ExecutionSample 10\n';; esac; }
bundle "$WORK/small"; r="$(profile "$WORK/small")"; check "below the floor: rc 1" "1" "${r%%|*}"; has "below the floor: INVALID" "Ceiling recording INVALID" "$r"

calls="$WORK/calls"; : > "$calls"
docker() { local a; for a in "$@"; do shift; [ "$a" = jfr ] && break; done; echo "$1" >> "$calls"
  case "$1" in version) echo "25";; summary) printf ' Duration: 75 s\n jdk.ExecutionSample 9000\n jdk.CPUTimeSample 9000\n';; *) return 124;; esac; }
bundle "$WORK/hang"; r="$(profile "$WORK/hang")"
check "a timed-out view still returns 0 (the floor passed)" "0" "${r%%|*}"
has "a timed-out view is named" "timed out (${JFR_VIEW_DEADLINE_S} s) or was killed" "$r"
has "a timed-out per-thread print gives its note" "(per-thread CPU unavailable: jfr print failed)" "$r"
check "after one timeout no further jfr call runs" "version summary print" "$(tr '\n' ' ' < "$calls" | sed 's/ $//')"
docker() { return 137; }   # killed by timeout -k
rm -f "$WORK/.jfr-timed-out"; jfr_tool "$WORK" summary x.jfr || true
if [ -e "$WORK/.jfr-timed-out" ]; then ok "a call killed after the grace period also sets the marker"; else bad "rc 137 did not set the marker"; fi
bundle "$WORK/earlier"; : > "$WORK/earlier/.jfr-timed-out"
r="$(profile "$WORK/earlier")"; check "an earlier timeout: rc 1" "1" "${r%%|*}"
has "an earlier timeout is named, not 'could not run'" "An earlier \`jfr\` call timed out" "$r"
docker() { return 0; }
mkdir -p "$WORK/asm/repo"; assemble_finished_chunks "$WORK/asm" "$WORK/asm/repo"
unset -f docker
check "every timeout call carries -k (jfr_tool and assemble_finished_chunks)" "0" "$(grep -cv '^-k [0-9]' "$TIMEOUT_ARGS" || true)"
has "jfr_tool goes through timeout" " jfr summary " "$(cat "$TIMEOUT_ARGS")"
has "assemble_finished_chunks goes through timeout" " sh -c " "$(cat "$TIMEOUT_ARGS")"

if [ "${PERF_ALLOCPROFILE_TEST_REAL:-false}" = "true" ] || [ -n "${PERF_ALLOCPROFILE_TEST_BUNDLE:-}" ]; then
  echo "--- 4. the real \`jfr\` ($JDK_IMAGE)"
  bundle "$WORK/real-corrupt"; head -c 65536 /dev/urandom > "$WORK/real-corrupt/sut/ceiling.jfr"
  r="$(profile "$WORK/real-corrupt")"; check "real jfr, corrupt recording: rc 1" "1" "${r%%|*}"
  has "real jfr, corrupt recording: a note" "could not read \`sut/ceiling.jfr\`" "$r"
  if [ -n "${PERF_ALLOCPROFILE_TEST_BUNDLE:-}" ]; then
    mkdir -p "$WORK/repo"; cp "$PERF_ALLOCPROFILE_TEST_BUNDLE" "$WORK/repo/allocprofile-perf-jvm-diagnostics.tgz"
    out="$(REPO_ROOT="$WORK/repo" ARTIFACT_PREFIX=allocprofile- ANNOTATE_CONTEXT=test emit_allocation_annotation)"
    printf '%s\n' "$out" > "${PERF_ALLOCPROFILE_TEST_OUT:-$WORK/annotation.md}"
    [ -z "${PERF_ALLOCPROFILE_TEST_BUNDLE_PERIODS:-}" ] || has "real bundle: the sampling periods" "${PERF_ALLOCPROFILE_TEST_BUNDLE_PERIODS} sampling periods" "$out"
    for section in "#### per-thread CPU" "| Peak period |" "#### hot methods by thread role" "#### monitor contention" \
                   "#### GC cycles" "#### gc-pauses" "#### allocation-by-site" "#### cpu-time-hot-methods"; do
      has "real bundle: $section" "$section" "$out"
    done
    bytes="$(printf '%s' "$out" | LC_ALL=C wc -c | tr -d ' ')"
    if [ "$bytes" -le "$ANNOTATION_MAX_BYTES" ]; then ok "real bundle: $bytes bytes, under the cap"; else bad "real bundle: $bytes bytes"; fi
  fi
fi

if [ "$FAILS" -ne 0 ]; then echo "FAILED: $FAILS check(s)" >&2; exit 1; fi
echo "perf-allocprofile-annotation-test: all checks passed"
