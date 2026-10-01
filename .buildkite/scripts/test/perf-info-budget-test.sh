#!/usr/bin/env bash
# shellcheck disable=SC2034  # the fixture variables are read by eval'd function bodies and call sites
# Docker-free checks that the INFO-log-level SUT runs at the shipped event-log budget while every
# other SUT keeps the harness budget, and that perf-test-compare.sh keys the info_* baseline on it.
# The call sites, env guard and compare program are lifted from the real scripts, not copied.
# Run: .buildkite/scripts/test/perf-info-budget-test.sh
#   (PERF_RUN_SCRIPT / PERF_COVERAGE_LIB / PERF_COMPARE_SCRIPT=<path> to test another copy)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
F="${PERF_RUN_SCRIPT:-$REPO_ROOT/.buildkite/scripts/steps/perf-test-run.sh}"
COV="${PERF_COVERAGE_LIB:-$REPO_ROOT/.buildkite/scripts/steps/lib/perf-path-coverage.sh}"
CMP="${PERF_COMPARE_SCRIPT:-$REPO_ROOT/.buildkite/scripts/steps/perf-test-compare.sh}"
BUDGETS="$REPO_ROOT/mockserver-performance-test/perf-budgets.json"
FAILS=0
ok()   { echo "  ok   $1"; }
bad()  { echo "  FAIL $1" >&2; FAILS=$((FAILS + 1)); }
check() { # name expected actual
  if [ "$2" = "$3" ]; then ok "$1"; else bad "$1: expected '$2', got '$3'"; fi
}
WORK="$(mktemp -d "${TMPDIR:-/tmp}/perf-info-budget-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

extract() { # a top-level function's text, from `name() {` to its closing `}`
  awk -v n="$1" '$0 ~ "^"n"\\(\\) *\\{" {p=1; print; if ($0 ~ /}$/) exit; next} p {print} p && /^}/ {exit}' "$F"
}
block() { # file first_line_regex -> lines from the first match through the next line that is exactly `fi`
  awk -v r="$2" '!p && $0 ~ r {p=1} p {print} p && /^fi$/ {exit}' "$1"
}
call_site() { # file regex -> the first non-comment match from the regex on, cut at its `\` or `||`
  CS_RE="$2" awk '$0 !~ /^[[:space:]]*#/ && match($0, ENVIRON["CS_RE"]) {print substr($0, RSTART); exit}' "$1" \
    | sed -E 's/[[:space:]]+(\\|\|\|.*)$//'
}

echo "--- 1. which SUTs are handed MOCKSERVER_MAX_EVENT_LOG_SIZE_IN_BYTES"
for fn in start_mockserver cpuset_arg; do
  body="$(extract "$fn")"
  if [ -z "$body" ]; then bad "function $fn not found in $F"; continue; fi
  eval "$body"
done
ENV_BLOCK="$(block "$F" '^PERF_MAX_EVENT_LOG_BYTES_SET=')"
[ -n "$ENV_BLOCK" ] || bad "PERF_MAX_EVENT_LOG_BYTES/PERF_INFO_MAX_EVENT_LOG_BYTES block not found"
SITE_upstream="$(call_site "$F" 'start_mockserver "\$UPSTREAM"')"
SITE_main="$(call_site "$F" 'start_mockserver "\$SERVER"')"
SITE_info="$(call_site "$F" 'start_mockserver "\$INFO_SERVER"')"
SITE_coverage="$(call_site "$COV" 'start_mockserver "\$name" "\$SERVER_CPUS"')"
SITE_covdl="$(call_site "$COV" 'start_mockserver "\$COV_DL_UPSTREAM"')"
for s in upstream main info coverage covdl; do v="SITE_$s"; [ -n "${!v}" ] || bad "call site '$s' not found"; done
[ "$FAILS" -eq 0 ] || { echo "FAILED: $FAILS check(s)" >&2; exit 1; }

diag_jvm_opts() { echo "-Dstub.diag=$1"; }
compose_java_tool_options() { printf '%s' "$2"; }
docker() { if [ "$1" = run ]; then shift; printf '%s\n' "$@" > "$WORK/run.args"; fi; }
args_of() { # site [VAR=value ...] -> the docker run arguments of that call site, one per line
  local site="SITE_$1"; shift
  (
    unset PERF_MAX_EVENT_LOG_BYTES PERF_INFO_MAX_EVENT_LOG_BYTES
    for kv in "$@"; do export "${kv?}"; done
    eval "$ENV_BLOCK"
    UPSTREAM=up SERVER=sut INFO_SERVER=info name=cov COV_DL_UPSTREAM=covdl fdir="$WORK" UPSTREAM_CPUS="" SERVER_CPUS="" UPSTREAM_PORT=1080 SUT_PORT=1080
    INFO_SERVER_ALIAS=mockserver-info SERVER_ALIAS=mockserver alias=cov SERVER_MEMORY=2g mem=2g FILE_BODY_MOUNT="" mount="" level=ERROR
    NETWORK=n PERF_NETWORK_MODE=bridge DIAG_DIR="$WORK/diag" MOCKSERVER_IMAGE=img SUT_IMAGE_JAVA_TOOL_OPTIONS="" START_EXTRA_ENV=()
    : > "$WORK/run.args"
    eval "${!site}"
    cat "$WORK/run.args"
  )
}
budget_of() { # site [VAR=value ...] -> the budget the container is handed, or "absent"
  local a v; a="$(args_of "$@")"
  [ -n "$a" ] || { echo "<no docker run>"; return; }
  v="$(awk -F= '$1 == "MOCKSERVER_MAX_EVENT_LOG_SIZE_IN_BYTES" {sub("^[^=]*=", ""); v = $0; found = 1; exit}
    END {print (!found ? "absent" : (v == "" ? "empty" : v))}' <<<"$a")"
  echo "$v"
}
for s in upstream main coverage covdl; do
  check "$s SUT keeps the harness 256 MiB" "268435456" "$(budget_of "$s")"
  check "$s SUT follows PERF_MAX_EVENT_LOG_BYTES" "536870912" "$(budget_of "$s" PERF_MAX_EVENT_LOG_BYTES=536870912)"
  check "$s SUT ignores PERF_INFO_MAX_EVENT_LOG_BYTES" "268435456" "$(budget_of "$s" PERF_INFO_MAX_EVENT_LOG_BYTES=104857600)"
done
check "INFO SUT gets no budget by default (shipped default)" "absent" "$(budget_of info)"
check "INFO SUT ignores PERF_MAX_EVENT_LOG_BYTES" "absent" "$(budget_of info PERF_MAX_EVENT_LOG_BYTES=536870912)"
check "INFO SUT takes PERF_INFO_MAX_EVENT_LOG_BYTES" "104857600" "$(budget_of info PERF_INFO_MAX_EVENT_LOG_BYTES=104857600)"
check "INFO SUT publishes a host port for its metrics" "127.0.0.1::1080" \
  "$(args_of info | awk 'prev == "-p" {print; exit} {prev = $0}')"

echo "--- 2. the INFO SUT's resolved budget is read from its own gauges"
body="$(extract gauge_value)"; [ -n "$body" ] && eval "$body" || bad "function gauge_value not found in $F"
METRICS='# HELP mock_server_event_log_max_retained_bytes x
mock_server_event_log_max_retained_bytes_total 1.0
mock_server_event_log_max_retained_bytes 7.8854144E7
mock_server_event_log_max_retained_entries 115456.0
jvm_memory_max_bytes{area="nonheap"} -1.0
jvm_memory_max_bytes{area="heap"} 9.66787072E8'
check "byte budget (scientific notation, not the _total series)" "78854144" "$(gauge_value mock_server_event_log_max_retained_bytes <<<"$METRICS")"
check "entry bound" "115456" "$(gauge_value mock_server_event_log_max_retained_entries <<<"$METRICS")"
check "heap ceiling (labelled series)" "966787072" "$(gauge_value 'jvm_memory_max_bytes{area="heap"}' <<<"$METRICS")"
check "absent gauge is empty" "" "$(gauge_value mock_server_event_log_max_retained_bytes <<<"")"

echo "--- 3. PERF_INFO_MAX_EVENT_LOG_BYTES guard and the tuned profile"
guard_rc() { # value -> exit code of the env block
  local rc=0
  env -u PERF_MAX_EVENT_LOG_BYTES -u PERF_INFO_MAX_EVENT_LOG_BYTES ${1:+PERF_INFO_MAX_EVENT_LOG_BYTES="$1"} \
    bash -c "set -euo pipefail; $ENV_BLOCK" >/dev/null 2>&1 || rc=$?
  echo "$rc"
}
check "unset passes" "0" "$(guard_rc "")"
check "a positive integer passes" "0" "$(guard_rc 104857600)"
for v in 0 -1 abc 1e8 "100 MiB"; do check "'$v' refused" "1" "$(guard_rc "$v")"; done
PROFILE_BLOCK="$(block "$F" '^CONFIG_PROFILE="default"$')"
[ -n "$PROFILE_BLOCK" ] || bad "CONFIG_PROFILE trigger block not found"
profile_of() { # [VAR=value] -> CONFIG_PROFILE
  ( unset PERF_SO_BACKLOG PERF_SERVER_JAVA_OPTS PERF_SERVER_MEMORY PERF_MAX_EVENT_LOG_BYTES_SET PERF_INFO_MAX_EVENT_LOG_BYTES
    PERF_LARGE_HEAP_PROFILE=false; [ $# -eq 0 ] || export "${1?}"; eval "$PROFILE_BLOCK"; echo "$CONFIG_PROFILE" )
}
check "no lever is the default profile" "default" "$(profile_of)"
check "PERF_INFO_MAX_EVENT_LOG_BYTES marks the run tuned" "tuned" "$(profile_of PERF_INFO_MAX_EVENT_LOG_BYTES=104857600)"

echo "--- 4. perf-test-compare.sh keys info_* on the INFO event-log budget method"
COMPARE="$(awk '/^COMPARE='"'"'$/ {on = 1; next} on && /^'"'"'$/ {exit} on' "$CMP")"
[ -n "$COMPARE" ] || bad "COMPARE program not found in $CMP"
run() { # method p95 -> a run whose INFO arm measured match_http under that budget method
  jq -nc --arg m "$1" --argjson p "$2" '{agent: {instance_type: "c5.12xlarge"},
    info_log_level_arm: ({measured: true, behaviours: {match_http: {p95_ms: $p, p99_ms: ($p * 3), error_rate: 0}}}
      + (if $m == "" then {} else {config: {event_log_budget: {method: $m}}} end))}'
}
compare() { # head_json baseline_json -> compare result
  jq -n --argjson head "$1" --argjson baseline "$2" --slurpfile b "$BUDGETS" --argjson minbaseline 5 \
    '($b[0].budgets) as $budgets | $head | '"$COMPARE"
}
LEGACY="$(for p in 30 31 29 30 32; do run "" "$p"; done | jq -sc .)"
NEWBASE="$(for p in 5 6 5 6 5; do run shipped-default "$p"; done | jq -sc .)"
unmeasured() { jq -c '.info_log_level_arm.measured = false' <<<"$1"; }
R="$(compare "$(run shipped-default 5.3)" "$LEGACY")"
check "against forced-256MiB history: info p95 has no baseline" "no-baseline" \
  "$(jq -r '.rows[] | select(.name == "info_match_http.p95_ms") | .status' <<<"$R")"
check "a run without the field reads as fixed-268435456" "5" "$(jq -r '.baseline_info_other' <<<"$R")"
check "head method reported" "shipped-default" "$(jq -r '.head_info_budget_method' <<<"$R")"
R="$(compare "$(run shipped-default 5.3)" "$NEWBASE")"
check "against shipped-default history: info p95 is compared" "5" \
  "$(jq -r '.rows[] | select(.name == "info_match_http.p95_ms") | .baseline' <<<"$R")"
check "no other-method runs" "0" "$(jq -r '.baseline_info_other' <<<"$R")"
R="$(compare "$(run fixed-268435456 30)" "$LEGACY")"
check "an explicit 256 MiB matches the old runs" "30" \
  "$(jq -r '.rows[] | select(.name == "info_match_http.p95_ms") | .baseline' <<<"$R")"
R="$(compare "$(run shipped-default 40)" "$NEWBASE")"
check "a real regression is still flagged" "true" \
  "$(jq -r '.rows[] | select(.name == "info_match_http.p95_ms") | .regression' <<<"$R")"
# error_rate is not hardware-keyed, so it exercises the all-instance INFO baseline and its warm-up.
R="$(compare "$(run shipped-default 5.3)" "$LEGACY")"
check "error_rate against forced-256MiB history has no baseline" "no-baseline" \
  "$(jq -r '.rows[] | select(.name == "info_match_http.error_rate") | .status' <<<"$R")"
for n in 1 2 3 4; do
  R="$(compare "$(run shipped-default 5.3)" "$(jq -c ".[:$n]" <<<"$NEWBASE")")"
  check "error_rate with $n shipped-default run(s) stays new" "no-baseline" \
    "$(jq -r '.rows[] | select(.name == "info_match_http.error_rate") | .status' <<<"$R")"
done
R="$(compare "$(run shipped-default 5.3)" "$NEWBASE")"
check "error_rate with 5 shipped-default runs is compared" "0" \
  "$(jq -r '.rows[] | select(.name == "info_match_http.error_rate") | .baseline' <<<"$R")"

echo "--- 5. the INFO-arm reset annotation"
NOTE_BLOCK="$(awk '/^INFO_COMPARABLE=/ {p=1} p {print} p && /^fi$/ {exit}' "$CMP")"
[ -n "$NOTE_BLOCK" ] || bad "INFO_NOTE block not found in $CMP"
note_of() { # compare_result -> INFO_NOTE
  ( RESULT_CMP="$1"; MIN_BASELINE=5; eval "$NOTE_BLOCK"; printf '%s' "$INFO_NOTE" )
}
MIXED="$(jq -c --argjson n "$NEWBASE" --argjson u "[$(unmeasured "$(run "" 30)"),$(unmeasured "$(run shipped-default 5)"),$(unmeasured "$(run shipped-default 5)")]" \
  '. + $n + $u' <<<"$LEGACY")"
R="$(compare "$(run shipped-default 5.3)" "$MIXED")"
check "unmeasured runs are not counted as comparable" "5" "$(jq -r '.baseline_info_comparable' <<<"$R")"
check "unmeasured runs are not counted as other-method" "5" "$(jq -r '.baseline_info_other' <<<"$R")"
R="$(compare "$(unmeasured "$(run shipped-default 5.3)")" "$LEGACY")"
check "an unmeasured head reports itself" "false" "$(jq -r '.head_info_measured' <<<"$R")"
check "no note for an unmeasured head" "" "$(note_of "$R")"
N="$(note_of "$(compare "$(run shipped-default 5.3)" "$LEGACY")")"
check "reset note shown against old-method history" "true" "$(grep -q 'INFO-arm baseline reset' <<<"$N" && echo true || echo false)"
check "reset note says the metrics stay new below MIN_BASELINE" "true" "$(grep -q 'stay `:new: new`' <<<"$N" && echo true || echo false)"
N="$(note_of "$(compare "$(run shipped-default 5.3)" "$(jq -c '. + $n' --argjson n "$NEWBASE" <<<"$LEGACY")")")"
check "past MIN_BASELINE the note says they compare" "true" "$(grep -q 'enough to compare' <<<"$N" && ! grep -q 'stay `:new:' <<<"$N" && echo true || echo false)"
check "no note when every run shares the method" "" "$(note_of "$(compare "$(run shipped-default 5.3)" "$NEWBASE")")"
check "the annotation body renders INFO_NOTE" "true" \
  "$(grep -qF '${INFO_NOTE}' <<<"$(grep -F '${PROVENANCE}' "$CMP")" && echo true || echo false)"

echo "--- 6. event_log_budget derivation"
for fn in info_els_method event_log_counters event_log_peaks event_log_budget_json event_log_sampler start_info_els_sampler stop_info_els_sampler; do
  body="$(extract "$fn")"; [ -n "$body" ] && eval "$body" || bad "function $fn not found in $F"
done
check "no requested budget is shipped-default" "shipped-default" "$(info_els_method "")"
check "a requested budget is fixed-<bytes>" "fixed-104857600" "$(info_els_method 104857600)"
check "counters read by their exposed names" "2|1500" \
  "$(printf 'mock_server_dropped_log_events_total 2.0\nmock_server_dropped_log_events_created 1.7E9\nmock_server_evicted_log_entries_total 1500.0\n' | event_log_counters)"
check "absent counters are empty" "|" "$(event_log_counters <<<"")"
printf '100 5\n300 2\n200 9\n' > "$WORK/peaks"; : > "$WORK/nopeaks"
check "peaks are per column" "300 9" "$(event_log_peaks "$WORK/peaks")"
check "no samples, no peaks" "" "$(event_log_peaks "$WORK/nopeaks")"
els() { # peak_bytes peak_entries dropped evicted -> "bound_reached binding" at an 80000000 B / 100000 entry bound
  event_log_budget_json shipped-default "" container-env 80000000 100000 966787072 78817280 "$1" "$2" "$3" "$4" 0.90 \
    | jq -r '"\(.bound_reached) \(.binding)"'
}
check "nothing known: undecided" "null null" "$(els "" "" "" "")"
check "evictions only: reached, bound unknown" "true null" "$(els "" "" "" 5)"
check "no evictions, no samples: not reached, bound unknown" "false null" "$(els "" "" 0 0)"
check "samples below the ratio, evictions unknown: undecided" "null null" "$(els 20000000 9000 "" "")"
check "below the ratio, no evictions: neither" "false neither" "$(els 20000000 9000 0 0)"
check "byte bound reached" "true bytes" "$(els 78000000 9000 0 0)"
check "count bound reached" "true count" "$(els 20000000 99000 0 0)"
check "both reached: the one nearer its limit" "true count" "$(els 76000000 99000 0 3)"
check "both reached, bytes nearer" "true bytes" "$(els 79500000 92000 0 3)"
check "just under the ratio is not reached" "false neither" "$(els 71999999 89999 0 0)"
check "the count ratio itself is reached" "true count" "$(els 20000000 90000 0 0)"
check "the ratio itself is reached" "true bytes" "$(els 72000000 9000 0 0)"
R="$(event_log_budget_json fixed-104857600 104857600 declared "" "" "" "" "" "" "" "" 0.90)"
check "fields carried through" "fixed-104857600 104857600 unavailable" "$(jq -r '"\(.method) \(.requested_bytes) \(.resolved_source)"' <<<"$R")"

echo "--- 7. the INFO arm starts, reads and stops the sampler around its load"
METRICS_STUB='mock_server_event_log_max_retained_bytes 8.0E7
mock_server_event_log_max_retained_entries 100000.0
mock_server_event_log_retained_bytes 4096.0
mock_server_event_log_retained_entries 7.0
mock_server_event_log_in_flight_bytes 1.0
mock_server_dropped_log_events_total 2.0
mock_server_evicted_log_entries_total 1500.0
jvm_memory_max_bytes{area="heap"} 9.66787072E8'
# Stubbed as executables on PATH, not functions: bash 3.2 ends a backgrounded function after its
# first call into a function-stubbed sleep.
printf '%s\n' "$METRICS_STUB" > "$WORK/metrics"; mkdir -p "$WORK/bin"
# The first scrape fails as curl does on a timeout (28); the sampler must keep going.
cat > "$WORK/bin/curl" <<EOS
#!/bin/sh
n=\$(cat "$WORK/curl-calls" 2>/dev/null || echo 0); n=\$((n + 1)); echo "\$n" > "$WORK/curl-calls"
[ "\$n" -gt 1 ] || exit 28
case "\$*" in *" http://127.0.0.1:9/mockserver/metrics") cat "$WORK/metrics" ;; *) exit 7 ;; esac
EOS
printf '#!/bin/sh\nexec /bin/sleep 0.05\n' > "$WORK/bin/sleep"
chmod +x "$WORK/bin/curl" "$WORK/bin/sleep"; PATH="$WORK/bin:$PATH"
wait_samples() { local i; for i in $(seq 1 60); do [ "$(wc -l < "$1")" -ge "$2" ] && return 0; sleep 0.05; done; return 1; }
bounded_stop() { # stop_info_els_sampler, but a hang is reported and broken after 10 s
  local pid="$INFO_ELS_SAMPLER_PID" dog
  ( /bin/sleep 10 & sp=$!; trap 'kill "$sp" 2>/dev/null; exit 0' TERM; wait "$sp"
    echo hung > "$WORK/stop-hung"; [ -z "$pid" ] || kill "$pid" 2>/dev/null ) >/dev/null 2>&1 &
  dog=$!
  stop_info_els_sampler
  kill "$dog" 2>/dev/null || true; wait "$dog" 2>/dev/null || true
  [ ! -e "$WORK/stop-hung" ] || bad "stop_info_els_sampler did not return within 10 s"
}
INFO_ELS_SAMPLER_PID=""; SAMP="$WORK/samples"; : > "$SAMP"
start_info_els_sampler "127.0.0.1:9" "$SAMP"
wait_samples "$SAMP" 2 || bad "sampler wrote fewer than 2 samples after a failed first scrape"
check "the first scrape failed" "true" "$([ "$(cat "$WORK/curl-calls")" -ge 3 ] && echo true || echo false)"
check "sampler reads the retained gauges" "4096 7" "$(head -1 "$SAMP")"
PID="$INFO_ELS_SAMPLER_PID"; bounded_stop
check "stop clears the pid" "" "$INFO_ELS_SAMPLER_PID"
check "stop kills the sampler" "dead" "$(kill -0 "$PID" 2>/dev/null && echo alive || echo dead)"
/bin/sleep 0.3; n1="$(wc -l < "$SAMP")"; /bin/sleep 0.4
check "the samples file stops growing" "$n1" "$(wc -l < "$SAMP")"
stop_info_els_sampler; check "stop with no sampler is a no-op" "" "$INFO_ELS_SAMPLER_PID"

ARM_BLOCK="$(awk '/^  if wait_ready "\$INFO_SERVER"; then$/ {p=1} p {print} p && /^  fi$/ {exit}' "$F")"
[ -n "$ARM_BLOCK" ] || bad "INFO arm load block not found in $F"
wait_ready() { return 0; }
docker() { if [ "$1 $2 $3" = "port info 1080/tcp" ]; then echo "127.0.0.1:9"; fi; }
LOAD_LOG="$WORK/load"; : > "$LOAD_LOG"
load_step() { # records whether the sampler was alive while a load phase ran
  wait_samples "$INFO_ELS_SAMPLES" 1 || true
  if [ -n "$INFO_ELS_SAMPLER_PID" ] && kill -0 "$INFO_ELS_SAMPLER_PID" 2>/dev/null; then echo "$1 sampled" >> "$LOAD_LOG"; else echo "$1 unsampled" >> "$LOAD_LOG"; fi
}
run_regression() { load_step "regression-$1"; }
run_sweep() { # the load also moves the counters, so the arm must read them after it
  load_step sweep
  sed -e 's/^mock_server_dropped_log_events_total .*/mock_server_dropped_log_events_total 5.0/' \
      -e 's/^mock_server_evicted_log_entries_total .*/mock_server_evicted_log_entries_total 2500.0/' \
      "$WORK/metrics" > "$WORK/metrics.new" && mv "$WORK/metrics.new" "$WORK/metrics"
}
arm_run() {
  INFO_SERVER=info INFO_SERVER_ALIAS=mockserver-info OUT_DIR="$WORK" INFO_SWEEP_K6=k6 INFO_MEASURED=false
  PERF_INFO_MAX_EVENT_LOG_BYTES=""; INFO_ELS_SAMPLES="$WORK/arm-samples"; : > "$INFO_ELS_SAMPLES"
  INFO_ELS_SAMPLER_PID=""
  eval "$ARM_BLOCK" >/dev/null 2>&1
  printf '%s|%s|%s|%s|%s\n' "${INFO_ELS_SAMPLER_PID:-none}" "$INFO_ELS_MAX_BYTES" "$INFO_HEAP_MAX_BYTES" "$INFO_ELS_DROPPED" "$INFO_ELS_EVICTED"
}
bounded() { # seconds outfile command... -> runs it in the background; a hang is killed and recorded as "hung"
  local secs="$1" out="$2" pid i; shift 2
  "$@" > "$out" 2>/dev/null < /dev/null &
  pid=$!
  for i in $(seq 1 $((secs * 20))); do kill -0 "$pid" 2>/dev/null || break; /bin/sleep 0.05; done
  if kill -0 "$pid" 2>/dev/null; then
    pkill -P "$pid" 2>/dev/null || true; kill "$pid" 2>/dev/null || true; echo hung > "$out"; return 0
  fi
  wait "$pid" || true
}
bounded 20 "$WORK/arm-out" arm_run
LEFT="$(cut -d'|' -f1 "$WORK/arm-out")"; [ "$LEFT" = none ] || kill "$LEFT" 2>/dev/null || true
check "every load phase ran with the sampler alive" "regression-http sampled
regression-https_h2 sampled
sweep sampled" "$(cat "$LOAD_LOG")"
check "sampler stopped, budget read, counters read after the load" "none|80000000|966787072|5|2500" "$(cat "$WORK/arm-out")"
CLEANUP_BODY="$(extract cleanup)"; [ -n "$CLEANUP_BODY" ] || bad "cleanup() not found in $F"
run_cleanup() { # with_sampler(yes|no) exit_code -> "<rc>|<teardown reached>|<sampler state>"
  local script="$WORK/cleanup-$1.sh" out rc=0
  { echo 'set -euo pipefail'
    printf '%s\n' "$CLEANUP_BODY"
    if [ "$1" = yes ]; then
      for fn in event_log_sampler start_info_els_sampler stop_info_els_sampler; do extract "$fn"; done
    fi
    cat <<'EOS'
capture_sut_diagnostics() { :; }; docker() { :; }; cov_cleanup() { echo TEARDOWN; }
SAMPLER_PID="" SWEEP_SAMPLER_PID="" DIAG_SAMPLER_PID="" LIVE_HISTO_PID="" SUT_LOG_PID="" INFO_LOG_PID=""
SERVER=s UPSTREAM=u SWEEP_K6=k STREAM_K6=k STREAM_SUT=s INFO_SERVER=i INFO_SWEEP_K6=k CLU_CTRL=c CLU_A=a CLU_B=b RUN_ID=r NETWORK=n
INFO_ELS_SAMPLER_PID=""
if declare -F start_info_els_sampler >/dev/null; then start_info_els_sampler 127.0.0.1:9 "$CLEANUP_SAMPLES"; echo "PID $INFO_ELS_SAMPLER_PID"; fi
trap cleanup EXIT
EOS
    echo "exit $2"
  } > "$script"
  # To a file, not $(...): a sampler cleanup failed to stop would hold the substitution open forever.
  CLEANUP_SAMPLES="$WORK/cleanup-samples" bash "$script" > "$WORK/cleanup.out" 2>&1 < /dev/null &
  local cpid=$! i
  for i in $(seq 1 300); do kill -0 "$cpid" 2>/dev/null || break; /bin/sleep 0.05; done
  if kill -0 "$cpid" 2>/dev/null; then
    pkill -P "$cpid" 2>/dev/null || true; kill "$cpid" 2>/dev/null || true; echo "hung|cleanup did not return within 15 s|"; return
  fi
  wait "$cpid" || rc=$?
  out="$(cat "$WORK/cleanup.out")"
  local pid; pid="$(awk '$1 == "PID" {print $2}' <<<"$out")"
  local state=none; [ -z "$pid" ] || { kill -0 "$pid" 2>/dev/null && { state=alive; kill "$pid" 2>/dev/null || true; } || state=stopped; }
  echo "$rc|$(grep -c '^TEARDOWN$' <<<"$out" || true)|$state"
}
check "cleanup without the sampler helper (an early failure) still tears down and keeps the exit code" "3|1|none" "$(run_cleanup no 3)"
check "cleanup stops a running INFO sampler" "0|1|stopped" "$(run_cleanup yes 0)"

TAIL_BLOCK="$(awk '/^  if docker inspect "\$INFO_SERVER" >\/dev\/null 2>&1; then$/ {p=1} p {print} p && /^  jq -e \. .*INFO_ELS_JSON=/ {exit}' "$F")"
[ -n "$TAIL_BLOCK" ] || bad "INFO budget assembly block not found in $F"
assemble() { # handed_env inspect_ok declared -> event_log_budget json
  ( docker() { [ "$1" = inspect ] && [ "$INSPECT_OK" = yes ]; }
    container_env() { [ "$1" = MOCKSERVER_MAX_EVENT_LOG_SIZE_IN_BYTES ] && printf '%s' "$HANDED"; }
    HANDED="$1" INSPECT_OK="$2" PERF_INFO_MAX_EVENT_LOG_BYTES="$3" INFO_SERVER=info PERF_EVENT_LOG_APPROACH_RATIO=0.90
    INFO_HEAP_MAX_BYTES=966787072 INFO_ELS_MAX_BYTES=80000000 INFO_ELS_MAX_ENTRIES=100000 INFO_ELS_DROPPED=0 INFO_ELS_EVICTED=0
    INFO_ELS_SAMPLES="$WORK/peaks"
    [ "${BREAK_JSON:-}" != yes ] || event_log_budget_json() { echo "not json"; }
    eval "$TAIL_BLOCK"
    if [ "${BREAK_JSON:-}" = yes ]; then jq -c . <<<"$INFO_ELS_JSON"; else
      jq -c '{method, requested_bytes, requested_source, expected_default_bytes, peak_retained_bytes}' <<<"$INFO_ELS_JSON"; fi )
}
check "no budget handed: shipped-default" \
  '{"method":"shipped-default","requested_bytes":null,"requested_source":"container-env","expected_default_bytes":78817280,"peak_retained_bytes":300}' \
  "$(assemble "" yes "")"
check "a handed budget is its own method" '{"method":"fixed-104857600","requested_bytes":104857600,"requested_source":"container-env","expected_default_bytes":78817280,"peak_retained_bytes":300}' \
  "$(assemble 104857600 yes 104857600)"
check "uninspectable container falls back to the declared value" '{"method":"fixed-104857600","requested_bytes":104857600,"requested_source":"declared","expected_default_bytes":78817280,"peak_retained_bytes":300}' \
  "$(assemble "" no 104857600)"
check "a failed budget record still carries the method, so the run is not keyed as legacy" '{"method":"fixed-104857600"}' \
  "$(BREAK_JSON=yes assemble 104857600 yes 104857600)"

if [ "$FAILS" -gt 0 ]; then echo "FAILED: $FAILS check(s)" >&2; exit 1; fi
echo "all INFO event-log budget checks passed"
