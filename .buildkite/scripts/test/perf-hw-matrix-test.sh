#!/usr/bin/env bash
# Fixture tests for the hardware matrix (performance programme item 27), no Docker needed:
#   1. lib/perf-hw-matrix-rw.jq: each lower-bound reason from a synthetic rw-multi-k6 result;
#   2. lib/perf-website-figures.jq: the hw_matrix display fields (scaling, peak, reason text);
#   3. perf-website-publish.sh: a lower-bound headline plus a new matrix emits an hw_matrix-only
#      patch, and without a new matrix still holds (exit 1, nothing written).
# Run: .buildkite/scripts/test/perf-hw-matrix-test.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
LIB="$REPO_ROOT/.buildkite/scripts/steps/lib"
FIGURES_JQ="$LIB/perf-website-figures.jq"
POINT_JQ="$LIB/perf-hw-matrix-rw.jq"
PUBLISH="$REPO_ROOT/.buildkite/scripts/steps/perf-website-publish.sh"
T="$(mktemp -d "${TMPDIR:-/tmp}/perf-hwm-test.XXXXXX")"
trap 'rm -rf "$T"' EXIT
FAILS=0
ok()   { echo "  ok   $1"; }
bad()  { echo "  FAIL $1" >&2; FAILS=$((FAILS + 1)); }
check() { # name expected actual
  if [ "$2" = "$3" ]; then ok "$1"; else bad "$1: expected '$2', got '$3'"; fi
}

# rw result from rung rows [offered, achieved, p50, k6_cpu, server_cpu, rig_valid, client_limited,
# error_rate (optional)] and a saturation override. k6 ceiling 500%, server pin 100% (C=1) unless overridden.
rw_fixture() { # rows_json saturation_override_json
  jq -n --argjson rows "$1" --argjson sat "$2" '
    {schema_version:2, timestamp_utc:"2026-09-30T00:00:00Z", config:{}, agent:{server_cpus:1}, valid:true,
     sweep:{proto:"http", points:[ $rows[] | {offered_rps:.[0], achieved_rps:.[1], p50_ms:.[2], p95_ms:(.[2]*2),
                                              p99_ms:(.[2]*3), error_rate:(.[7] // 0), dropped_iterations:0, sample_count:(.[1]*12)} ]},
     saturation:({client_cpu_ceiling_pct:500, server_pin_pct:100, server_headroom_test:"active",
                  client_limited_from_rps:([ $rows[] | select(.[6] == true) | .[0] ] | min),
                  rig_valid_peak_achieved_rps:([ $rows[] | select(.[5] == true) | .[1] ] | max),
                  ladder:[ $rows[] | {offered_rps:.[0], achieved_rps:.[1], k6_cpu_pct:.[3], server_cpu_pct:.[4],
                                      rig_valid:.[5], client_limited:.[6]} ]} + $sat)}'
}
# The same two steps perf-percore.sh runs: the published p50 rule, then the point mapping.
point_of() { # rw_json -> point
  local hc50
  hc50="$(jq -c --arg now x --argjson lat_mult 3 --argjson keep 0.95 --arg fix_date 2026-09-16 -f "$FIGURES_JQ" <<<"$1" | jq -c '.headline')"
  jq -c --argjson cores 1 --argjson hc50 "$hc50" --argjson hc99 null --argjson p99_max_ms 10 \
    --argjson maxlog 1000 --argjson body 6 -f "$POINT_JQ" <<<"$1"
}
reasons() { jq -r '.lower_bound_candidates | join(",")' <<<"$1"; }

echo "--- 1. lower-bound reasons from derive_saturation (lib/perf-hw-matrix-rw.jq)"
# A k6-saturated rung above the ceiling: the load generator stopped the ladder.
P="$(point_of "$(rw_fixture '[[8000,8000,0.2,200,70,true,false],[8600,8600,0.2,220,75,true,false],[9300,9300,0.2,300,80,true,false],[10000,10000,0.2,300,85,true,false],[10800,9000,0.3,600,80,false,true]]' '{}')")"
check "k6-saturated next rung -> client_cpu_limited" "client_cpu_limited" "$(reasons "$P")"
check "ceiling is the last rig-valid rung" "10000" "$(jq -r '.healthy_ceiling_rps' <<<"$P")"
# Short with server CPU under 85% of its pin, k6 under its ceiling.
P="$(point_of "$(rw_fixture '[[8000,8000,0.2,200,70,true,false],[8600,8600,0.2,220,75,true,false],[9300,9300,0.2,300,80,true,false],[10000,10000,0.2,300,80,true,false],[10800,9000,0.3,400,60,false,true]]' '{}')")"
check "client-short, server idle -> server_cpu_not_saturated" "server_cpu_not_saturated" "$(reasons "$P")"
# A rig-valid overload rung above the ceiling: a real server limit, no reason.
P="$(point_of "$(rw_fixture '[[8000,8000,0.2,200,70,true,false],[8600,8600,0.2,220,75,true,false],[9300,9300,0.2,300,80,true,false],[10000,10000,0.2,300,95,true,false],[10800,9500,2.0,320,100,true,false]]' '{}')")"
check "rig-valid overload above -> measured (no reason)" "" "$(reasons "$P")"
check "rig-valid overload above -> ceiling 10000" "10000" "$(jq -r '.healthy_ceiling_rps' <<<"$P")"
# The server headroom test did not run on every rung.
P="$(point_of "$(rw_fixture '[[8000,8000,0.2,200,70,true,false],[8600,8600,0.2,220,75,true,false],[9300,9300,0.2,300,80,true,false],[10000,10000,0.2,300,95,true,false],[10800,9500,2.0,320,100,true,false]]' '{"server_headroom_test":"partial"}')")"
check "server_headroom_test partial -> cpu_unverified" "cpu_unverified" "$(reasons "$P")"
# Healthy all the way up.
P="$(point_of "$(rw_fixture '[[8000,8000,0.2,200,70,true,false],[8600,8600,0.2,220,75,true,false],[9300,9300,0.2,300,80,true,false],[10000,10000,0.2,300,95,true,false]]' '{}')")"
check "healthy at the top rung -> ladder_top_reached" "ladder_top_reached" "$(reasons "$P")"
# derive_saturation found a client limit from a rung at or below the ceiling.
P="$(point_of "$(rw_fixture '[[8000,8000,0.2,200,70,true,false],[8600,8600,0.2,220,75,true,false],[9300,9300,0.2,300,80,true,false],[10000,10000,0.2,300,95,true,false],[10800,9500,2.0,320,100,true,false]]' '{"client_limited_from_rps":9300}')")"
check "client limit at or below the ceiling -> client_limit_at_or_below_ceiling" "client_limit_at_or_below_ceiling" "$(reasons "$P")"
check "rig-valid peak comes from saturation" "10000" "$(jq -r '.rig_valid_peak_achieved_rps' <<<"$P")"
# A: rig-invalid above the ceiling but not client-limited (idle-pool drops), SUT never above 60%.
P="$(point_of "$(rw_fixture '[[8000,8000,0.2,200,40,true,false],[8600,8600,0.2,220,45,true,false],[9300,9300,0.2,300,50,true,false],[10000,10000,0.2,300,55,true,false],[10800,9000,0.3,320,60,false,false]]' '{}')")"
check "A: rig-invalid, not client-limited, SUT <=60% -> overload_not_measured + server_cpu_not_saturated" \
  "overload_not_measured,server_cpu_not_saturated" "$(reasons "$P")"
# B: rig-valid overload above (p50 10x), but the SUT only reached 55% of its pin.
P="$(point_of "$(rw_fixture '[[8000,8000,0.2,200,40,true,false],[8600,8600,0.2,220,45,true,false],[9300,9300,0.2,300,50,true,false],[10000,10000,0.2,300,55,true,false],[10800,10500,2.0,320,55,true,false]]' '{}')")"
check "B: rig-valid overload but SUT at 55% -> server_cpu_not_saturated" "server_cpu_not_saturated" "$(reasons "$P")"
check "B: peak_limited_by" "load_path_or_virtualization" "$(jq -r '.peak_limited_by' <<<"$P")"
# C: an erroring rung above the ceiling (rig-invalid, not client-limited), SUT at 50%.
P="$(point_of "$(rw_fixture '[[8000,8000,0.2,200,40,true,false],[8600,8600,0.2,220,45,true,false],[9300,9300,0.2,300,50,true,false],[10000,10000,0.2,300,50,true,false],[10800,10800,0.2,320,50,false,false,0.05]]' '{}')")"
check "C: error rung above, SUT at 50% -> overload_not_measured + server_cpu_not_saturated" \
  "overload_not_measured,server_cpu_not_saturated" "$(reasons "$P")"
# D: the same error rung with the SUT at 90%: the overload rule alone.
P="$(point_of "$(rw_fixture '[[8000,8000,0.2,200,70,true,false],[8600,8600,0.2,220,75,true,false],[9300,9300,0.2,300,80,true,false],[10000,10000,0.2,300,90,true,false],[10800,10800,0.2,320,90,false,false,0.05]]' '{}')")"
check "D: error rung above, SUT at 90% -> overload_not_measured" "overload_not_measured" "$(reasons "$P")"
# cross_run.agrees is only a verdict when some rung was compared.
RW="$(rw_fixture '[[8000,8000,0.2,200,90,true,false],[8600,8600,0.2,220,95,true,false]]' '{}')"
check "cross_run_agrees null when nothing was compared" "null" \
  "$(point_of "$(jq -c '. + {cross_check:{equivalent:true, cross_run:{agrees:false, compared:0}}}' <<<"$RW")" | jq -r '.measurement.cross_run_agrees')"
check "cross_run_agrees kept when rungs were compared" "false" \
  "$(point_of "$(jq -c '. + {cross_check:{equivalent:true, cross_run:{agrees:false, compared:2}}}' <<<"$RW")" | jq -r '.measurement.cross_run_agrees')"

echo "--- 2. published hw_matrix fields (lib/perf-website-figures.jq)"
RUN="$(jq -n '{schema_version:2, timestamp_utc:"2026-09-30T00:00:00Z", config:{}, agent:{},
  sweep:{points:[{offered_rps:1000, achieved_rps:1000, p50_ms:0.2}]},
  serving_hw_matrix:{other_containers_paused:true, sweep:{rates:"", latency_settle_s:3}, points:[
    {key:"1c-512m", cores:1, memory_limit:"512m", memory_limit_bytes:536870912, control:false, status:"measured",
     healthy_ceiling_rps:12000, rps_per_core:12000, rig_valid_peak_achieved_rps:12800.4, lower_bound:false, lower_bound_reasons:[]},
    {key:"3c-1536m", cores:3, memory_limit:"1536m", memory_limit_bytes:1610612736, control:false, status:"oom_killed",
     healthy_ceiling_rps:null, rps_per_core:null, died_before_sweep:true, lower_bound:false, lower_bound_reasons:[],
     rig_valid_peak_achieved_rps:21000, measurement:{valid:false}},
    {key:"2c-1g", cores:2, memory_limit:"1g", memory_limit_bytes:1073741824, control:false, status:"measured",
     healthy_ceiling_rps:22000, rps_per_core:11000, rig_valid_peak_achieved_rps:23000, lower_bound:false, lower_bound_reasons:[]},
    {key:"6c-2g", cores:6, memory_limit:"2g", memory_limit_bytes:2147483648, control:false, status:"measured",
     healthy_ceiling_rps:60000, rps_per_core:10000, rig_valid_peak_achieved_rps:61000, lower_bound:true,
     lower_bound_reasons:["client_limit_at_or_below_ceiling"]}]}}')"
F="$(jq -c --arg now x --argjson lat_mult 3 --argjson keep 0.95 --arg fix_date 2026-09-16 -f "$FIGURES_JQ" <<<"$RUN")"
check "scaling_vs_1core at 2 cores" "0.92" "$(jq -r '.hw_matrix.points[1].scaling_vs_1core' <<<"$F")"
check "scaling_vs_1core at 1 core" "1" "$(jq -r '.hw_matrix.points[0].scaling_vs_1core' <<<"$F")"
check "scaling_vs_1core_display keeps two decimals" "1.00 0.92" "$(jq -r '[.hw_matrix.points[0,1].scaling_vs_1core_display] | join(" ")' <<<"$F")"
check "peak_display" "12,800" "$(jq -r '.hw_matrix.points[0].peak_display' <<<"$F")"
check "rps_per_core_display" "11,000" "$(jq -r '.hw_matrix.points[1].rps_per_core_display' <<<"$F")"
check "client_limit_at_or_below_ceiling note" "may have been limited by the load generator — lower bound" "$(jq -r '.hw_matrix.points[3].note' <<<"$F")"
check "no peak published for a point that did not measure (oom_killed, invalid)" "null null" \
  "$(jq -r '.hw_matrix.points[2] | "\(.peak_achieved_rps) \(.peak_display)"' <<<"$F")"
check "server_cpu_not_saturated note does not blame the load generator alone" \
  "MockServer's CPU was not saturated, so the limit may be elsewhere (load generator or load path) — lower bound" \
  "$(jq -c '.serving_hw_matrix.points[3].lower_bound_reasons = ["server_cpu_not_saturated"]' <<<"$RUN" \
     | jq -r --arg now x --argjson lat_mult 3 --argjson keep 0.95 --arg fix_date 2026-09-16 -f "$FIGURES_JQ" | jq -r '.hw_matrix.points[3].note')"
check "1536m displays as 1.5 GB" "1.5 GB" "$(jq -r '.hw_matrix.points[2].memory_display' <<<"$F")"
check "died before the sweep reads as warm-up" "ran out of memory (container killed) before or during warm-up" "$(jq -r '.hw_matrix.points[2].note' <<<"$F")"

echo "--- 3. publish: a lower-bound headline holds the headline only (perf-website-publish.sh)"
# A throwaway repository holding the committed figures, and a fake aws serving one run.
R="$T/repo"; mkdir -p "$R/jekyll-www.mock-server.com/_data" "$R/jekyll-www.mock-server.com/images/perf-charts/data"
cp "$REPO_ROOT/jekyll-www.mock-server.com/_data/perf_figures.json" "$R/jekyll-www.mock-server.com/_data/"
cp "$REPO_ROOT/jekyll-www.mock-server.com/images/perf-charts/render_perf_charts.py" "$R/jekyll-www.mock-server.com/images/perf-charts/"
echo '{"points":[]}' > "$R/jekyll-www.mock-server.com/images/perf-charts/data/perf-sweep.json"
git -C "$R" init -q && git -C "$R" add -A && git -C "$R" -c user.name=t -c user.email=t@t commit -qm base
# Candidate run: its headline is a client-limited lower bound (next rung client_limited) at
# 44,000 against the committed 60,000; it also carries a new matrix.
jq -n --argjson hw "$(jq -c '.serving_hw_matrix' <<<"$RUN")" '
  {schema_version:2, timestamp_utc:"2026-09-30T00:00:00Z", validity:{valid:true}, config:{mockserver_version:"x"}, agent:{},
   sweep:{points:[{offered_rps:32000, achieved_rps:32000, p50_ms:0.2}, {offered_rps:40000, achieved_rps:40000, p50_ms:0.2},
                  {offered_rps:44000, achieved_rps:44000, p50_ms:0.2}, {offered_rps:48000, achieved_rps:44500, p50_ms:0.3}]},
   saturation:{ladder:[{offered_rps:32000, rig_valid:true}, {offered_rps:40000, rig_valid:true},
                       {offered_rps:44000, rig_valid:true}, {offered_rps:48000, rig_valid:false, client_limited:true}]},
   serving_hw_matrix:$hw}' > "$T/run-with-matrix.json"
jq 'del(.serving_hw_matrix)' "$T/run-with-matrix.json" > "$T/run-no-matrix.json"
cat > "$T/aws" <<EOF
#!/usr/bin/env bash
case "\$2" in
  ls) echo "2026-09-30 00:00:00 100 runs/master/2026-09-30T00:00:00Z__abc.json" ;;
  cp) cp "\$PERF_TEST_RUN" "\$4" ;;
esac
EOF
chmod +x "$T/aws"
# In CI a real buildkite-agent would annotate this build and upload the test's patch: stub it.
mkdir -p "$T/bin" && printf '#!/bin/sh\nexit 0\n' > "$T/bin/buildkite-agent" && chmod +x "$T/bin/buildkite-agent"
publish() { # run_json [env...]
  local run="$1"; shift
  env PATH="$T/bin:$PATH" PERF_TEST_RUN="$run" PERF_PUBLISH_AWS_BIN="$T/aws" PERF_PUBLISH_REPO_ROOT="$R" "$@" \
    bash "$PUBLISH" > "$T/publish.log" 2>&1
}
COMMITTED="$R/jekyll-www.mock-server.com/_data/perf_figures.json"

rc=0; publish "$T/run-with-matrix.json" PERF_PUBLISH_DRY_RUN=true PERF_PUBLISH_OUT="$T/dry.json" || rc=$?
check "dry-run with a new matrix exits 0" "0" "$rc"
check "dry-run candidate differs from committed in hw_matrix only" "hw_matrix" \
  "$(jq -rn --slurpfile a "$COMMITTED" --slurpfile b "$T/dry.json" '[($a[0] + $b[0] | keys[]) as $k | select($a[0][$k] != $b[0][$k]) | $k] | join(",")')"
check "dry-run candidate keeps the committed headline" "$(jq -c .headline "$COMMITTED")" "$(jq -c .headline "$T/dry.json")"

rc=0; publish "$T/run-with-matrix.json" || rc=$?
check "held-headline publish exits 0" "0" "$rc"
CHANGED="$(git -C "$R" diff --name-only HEAD~1 HEAD 2>/dev/null | { grep -v 'perf_hw_matrix.png$' || true; } | tr '\n' ' ')"
check "patch touches the figures and the hw chart data only" \
  "jekyll-www.mock-server.com/_data/perf_figures.json jekyll-www.mock-server.com/images/perf-charts/data/perf-hw-matrix.json " "$CHANGED"
check "committed figures change in hw_matrix only" "hw_matrix" \
  "$(git -C "$R" show HEAD~1:jekyll-www.mock-server.com/_data/perf_figures.json > "$T/before.json"; jq -rn --slurpfile a "$T/before.json" --slurpfile b "$COMMITTED" '[($a[0] + $b[0] | keys[]) as $k | select($a[0][$k] != $b[0][$k]) | $k] | join(",")')"
SUBJECT="$(git -C "$R" log -1 --format=%s)"
check "patch commit message says the headline was held" "yes" "$([ "${SUBJECT%headline held)}" != "$SUBJECT" ] && echo yes || echo no)"

git -C "$R" checkout -q - && git -C "$R" branch -q -D "$(git -C "$R" branch --format='%(refname:short)' | grep perf/ )"
rc=0; publish "$T/run-no-matrix.json" || rc=$?
check "lower-bound headline without a new matrix still holds (exit 1)" "1" "$rc"
check "the hold writes nothing" "" "$(git -C "$R" status --porcelain)"

echo "--- 4. knee diagnostics (lib/perf-hw-matrix-jvm.sh: scrape, cgroup and per-rung summary)"
# shellcheck source=../steps/lib/perf-hw-matrix-jvm.sh
. "$LIB/perf-hw-matrix-jvm.sh"
check "metric columns from a scrape" "2.5,40,3,1200" "$(printf '%s\n' '# HELP x' 'jvm_gc_collection_seconds_sum 2.5' \
  'jvm_gc_collection_count 40.0' 'mock_server_dropped_log_events_total 3.0' 'mock_server_evicted_log_entries_total 1200.0' | jvm_metric_cols)"
check "metric columns blank when absent (never zero)" ",,," "$(echo 'other_metric 1' | jvm_metric_cols)"
mkdir -p "$T/cg"
printf 'low 0\nhigh 7\nmax 2\noom 0\noom_kill 0\noom_group_kill 0\n' > "$T/cg/memory.events"
printf 'some avg10=1.00 avg60=0.50 avg300=0.10 total=1500000\nfull avg10=0.00 avg60=0.00 avg300=0.00 total=20000\n' > "$T/cg/cpu.pressure"
printf 'some avg10=0.00 avg60=0.00 avg300=0.00 total=300\nfull avg10=0.00 avg60=0.00 avg300=0.00 total=100\n' > "$T/cg/memory.pressure"
check "cgroup columns" "7,2,0,0,1500000,20000,300,100" "$(cgroup_cols "$T/cg")"
check "cgroup columns blank when unreadable" ",,,,,,," "$(cgroup_cols "")"
CGD="$(sut_cgroup_dir "no-such-container-id")" # an assignment: a non-zero return would stop this set -e script
check "no cgroup dir for an unknown container" "" "$CGD"
# Two rungs of 10 s (settle 2) from t0=1000 and t0=1020; samples every 2 s, rung 2 GC-bound.
{ echo "ts,$JVM_COLS"
  echo "1001,0.0,0,0,0,1,0,0,0,0,0,," # in rung 1's settle window: excluded
  for t in 1002 1004 1006 1008 1010; do echo "$t,0.$(( t - 1002 )),$(( t - 1000 )),0,$(( (t - 1000) * 100 )),1,0,0,0,$(( (t - 1000) * 100000 )),0,,"; done
  for t in 1022 1024 1026 1028 1030; do echo "$t,$(( 1 + (t - 1022) / 2 )),$(( t - 1000 )),5,$(( 3000 + (t - 1020) * 900 )),9,0,0,0,$(( 2000000 + (t - 1020) * 900000 )),0,,"; done
} > "$T/jvm.csv"
J="$(jvm_rungs_json "$T/jvm.csv" '[{"offered_rps":1000,"start_epoch_ms":1000000},{"offered_rps":2000,"start_epoch_ms":1020000}]' 10 2)"
check "rung 1: GC seconds per second" "0.1" "$(jq -r '.[0].gc_seconds_per_s' <<<"$J")"
check "rung 2: GC seconds per second" "0.5" "$(jq -r '.[1].gc_seconds_per_s' <<<"$J")"
check "rung 2: evictions per second, CPU pressure fraction" "900 0.9" "$(jq -r '.[1] | "\(.evicted_log_entries_per_s) \(.cpu_pressure_some_frac)"' <<<"$J")"
check "rung 2: drops and memory.events high are deltas within the window" "0 0" "$(jq -r '.[1] | "\(.dropped_log_events) \(.memory_events.high)"' <<<"$J")"
check "unreadable memory PSI stays null" "null" "$(jq -r '.[0].memory_pressure_some_frac' <<<"$J")"
check "five samples per steady window" "5 5" "$(jq -r '[.[].samples] | join(" ")' <<<"$J")"
check "no samples -> null" "null" "$(jvm_rungs_json "" '[]' 10 2)"
sed 's/^1002,0\.0,/1002,bad,/' "$T/jvm.csv" > "$T/jvm-bad.csv"
J="$(jvm_rungs_json "$T/jvm-bad.csv" '[{"offered_rps":1000,"start_epoch_ms":1000000}]' 10 2)"
check "a non-numeric cell is null, not a column shift (GC count still read)" "8" "$(jq -r '.[0].gc_count' <<<"$J")"

if [ "$FAILS" -ne 0 ]; then echo ":x: $FAILS check(s) failed" >&2; exit 1; fi
echo "--- all hardware-matrix fixture checks passed"
