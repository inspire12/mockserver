#!/usr/bin/env bash
# shellcheck disable=SC2034  # the call-site variables are read by the eval'd jq invocation
# Fixture tests for the gating JMH time_per_op score (lib/perf-microbench-reshape.jq), no Docker or
# JMH run needed. The iterations are the ones scheduled builds 620-653 printed for
# MatchingBenchmark JSON_BODY_100_detailed, and the baseline / threshold are the ones build 653's
# compare used. In 653 one fork's first measured iteration ran slow and JMH's mean crossed the +5%
# threshold; the score must not, and the same iterations shifted 5-6% must trip it exactly where the
# mean does. The call site is lifted from perf-test-microbench.sh, not copied.
# Run: .buildkite/scripts/test/perf-microbench-score-test.sh   (PERF_MICROBENCH_SCRIPT=<path> for another copy)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
# shellcheck source=lib/perf-test-env.sh
. "$REPO_ROOT/.buildkite/scripts/test/lib/perf-test-env.sh"
perf_test_scrub_env PERF_MICROBENCH_SCRIPT
F="${PERF_MICROBENCH_SCRIPT:-$REPO_ROOT/.buildkite/scripts/steps/perf-test-microbench.sh}"
FAILS=0
ok()   { echo "  ok   $1"; }
bad()  { echo "  FAIL $1" >&2; FAILS=$((FAILS + 1)); }
check() { # name expected actual
  if [ "$2" = "$3" ]; then ok "$1"; else bad "$1: expected '$2', got '$3'"; fi
}
WORK="$(mktemp -d "${TMPDIR:-/tmp}/perf-microbench-score-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

# Build 653's compare row for JSON_BODY_100_detailed.time_per_op: baseline 1259.941, threshold
# 1322.938 (= baseline x 1.05, the microbench.*.time_per_op min_pct). EXACT / REGEX as recorded too.
TH_JSON=1322.938; TH_EXACT=942.034; TH_REGEX=962.592

# JMH 1.37 -rf json row shape (primaryMetric.rawData is one array of measured iterations per fork).
row() { # matcherType detailed rawData-json jmh-score
  jq -nc --arg m "$1" --arg d "$2" --argjson raw "$3" --argjson score "$4" '
    {benchmark:"org.mockserver.benchmark.MatchingBenchmark.firstMatchingExpectation_noMatch", mode:"avgt",
     params:{detailedMatchFailures:$d, expectationCount:"100", logLevel:"INFO", matcherType:$m},
     primaryMetric:{score:$score, scoreUnit:"us/op", rawData:$raw},
     secondaryMetrics:{"gc.alloc.rate.norm":{score:2567612.7546157576, scoreUnit:"B/op"}}}'
}
B653_JSON='[[1294.402,1258.3,1265.962],[1629.295,1262.833,1263.983]]'
B653_EXACT='[[908.813,938.141,964.39],[880.389,872.297,891.577]]'
B653_REGEX='[[897.356,907.477,894.793],[907.028,895.346,892.86]]'
# Scheduled builds' JSON_BODY_100_detailed iterations, "<build> <rawData> <JMH score>".
HISTORY='620 [[1315.526,1320.325,1309.099],[1240.303,1256.211,1251.321]] 1282.1309829810855
636 [[1244.624,1252.538,1247.035],[1343.155,1351.826,1342.947]] 1297.020668242064
644 [[1249.36,1256.716,1251.658],[1282.314,1271.98,1261.157]] 1262.1976121700625
649 [[1246.023,1246.594,1244.596],[1244.985,1263.203,1255.462]] 1250.143946309737
650 [[1270.347,1277.118,1274.6],[1233.744,1237.702,1231.256]] 1254.12792690326
651 [[1259.874,1264.364,1263.041],[1249.268,1242.32,1238.616]] 1252.9136823144556
652 [[1239.367,1286.29,1233.97],[1268.56,1251.336,1245.131]] 1254.108999016891
653 [[1294.402,1258.3,1265.962],[1629.295,1262.833,1263.983]] 1329.1290453787594'

# The reshape exactly as perf-test-microbench.sh runs it: its jq invocation, lifted from the script.
CALL="$(awk '/^jq --arg args "\$JMH_ARGS"/ {p=1} p {print} p && /> "\$OUT_JSON"$/ {exit}' "$F")"
# shellcheck disable=SC2016  # the pattern matches the script's literal text
case "$CALL" in
  *'-f "$SCRIPT_DIR/lib/perf-microbench-reshape.jq"'*'"$REPO_ROOT/$RESULT_RAW" > "$OUT_JSON"') ok "call site found and uses lib/perf-microbench-reshape.jq" ;;
  *) bad "the perf-microbench.json jq call in $F is missing or no longer uses lib/perf-microbench-reshape.jq"; CALL="false" ;;
esac
reshape() { # jmh-result-file -> perf-microbench.json on stdout; non-zero when the reshape refuses
  local SCRIPT_DIR REPO_ROOT RESULT_RAW OUT_JSON JMH_ARGS="-f 2 -wi 2 -i 3" JMH_ARGS_EXTRA="x" JMH_ARGS_SCALING="y"
  SCRIPT_DIR="$(dirname "$F")"; REPO_ROOT="$WORK"; RESULT_RAW="$(basename "$1")"; OUT_JSON="$WORK/out.json"
  eval "$CALL" 2>"$WORK/err.txt" && cat "$OUT_JSON"
}
score() { # rawData-json jmh-score -> the JSON_BODY_100_detailed time_per_op
  row JSON_BODY true "$1" "$2" | jq -s . > "$WORK/one.json"
  reshape "$WORK/one.json" | jq -r '.microbench.JSON_BODY_100_detailed.time_per_op'
}
over() { awk -v v="$1" -v t="$2" 'BEGIN { print (v > t) ? "trips" : "ok" }'; }
times() { jq -c --argjson k "$2" 'map(map(. * $k))' <<<"$1"; }

echo "--- 1. build 653: one slow iteration no longer crosses the threshold"
{ row EXACT true "$B653_EXACT" 909.2677623926212; row REGEX true "$B653_REGEX" 899.1432923632824
  row JSON_BODY true "$B653_JSON" 1329.1290453787594; row JSON_BODY false "$B653_JSON" 1329.1290453787594; } | jq -s . > "$WORK/b653.json"
OUT="$(reshape "$WORK/b653.json")" || { bad "reshape refused build 653's result: $(cat "$WORK/err.txt")"; OUT='{}'; }
J="$(jq -r '.microbench.JSON_BODY_100_detailed.time_per_op' <<<"$OUT")"
check "JSON_BODY score is the mean of the middle four of six iterations" "1271.795" "$(jq -r '. * 1000 | round / 1000' <<<"$J")"
check "JSON_BODY score is under 653's threshold $TH_JSON" "ok" "$(over "$J" "$TH_JSON")"
check "JMH's own mean is kept, and it is what tripped 653" "1329.129 trips" \
  "$(jq -r '.microbench.JSON_BODY_100_detailed.time_per_op_jmh_mean | (. * 1000 | round / 1000)' <<<"$OUT") $(over "$(jq -r '.microbench.JSON_BODY_100_detailed.time_per_op_jmh_mean' <<<"$OUT")" "$TH_JSON")"
check "EXACT under its threshold" "ok" "$(over "$(jq -r '.microbench.EXACT_100_detailed.time_per_op' <<<"$OUT")" "$TH_EXACT")"
check "REGEX under its threshold" "ok" "$(over "$(jq -r '.microbench.REGEX_100_detailed.time_per_op' <<<"$OUT")" "$TH_REGEX")"
check "keys unchanged (a false arm has no suffix)" "EXACT_100_detailed JSON_BODY_100 JSON_BODY_100_detailed REGEX_100_detailed" \
  "$(jq -r '.microbench | keys | join(" ")' <<<"$OUT")"
check "time_unit and alloc_bytes_per_op pass through" "us/op 2567612.7546157576" \
  "$(jq -r '.microbench.JSON_BODY_100_detailed | "\(.time_unit) \(.alloc_bytes_per_op)"' <<<"$OUT")"
check "config.jmh fingerprint is the three arg strings only" '{"args":"-f 2 -wi 2 -i 3","args_extra":"x","args_scaling":"y"}' \
  "$(jq -c '.config.jmh' <<<"$OUT")"

echo "--- 2. a real 5-6% slowdown trips the score wherever it trips JMH's mean (recorded runs, 653's threshold)"
while read -r build raw jmh; do
  for k in 1.00 1.05 1.06; do
    s="$(score "$(times "$raw" "$k")" "$(awk -v a="$jmh" -v k="$k" 'BEGIN { print a * k }')")"
    old="$(over "$(awk -v a="$jmh" -v k="$k" 'BEGIN { printf "%.6f", a * k }')" "$TH_JSON")"
    new="$(over "$s" "$TH_JSON")"
    if [ "$build" = 653 ] && [ "$k" = 1.00 ]; then
      check "build 653 as recorded: mean trips, score does not" "trips ok" "$old $new"
    else
      check "build $build x$k: score agrees with the mean ($old)" "$old" "$new"
    fi
    [ "$k" = 1.06 ] && check "build $build x1.06 trips" "trips" "$new"
  done
done <<<"$HISTORY"

echo "--- 3. the reshape refuses rather than guesses"
for bad_raw in 'null' '[]' '[[1250.0],[1251.0]]' '[[1250.0,"x",1252.0],[1251.0,1252.0,1253.0]]'; do
  row JSON_BODY true "$bad_raw" 1250.0 | jq -s . > "$WORK/bad.json"
  if reshape "$WORK/bad.json" >/dev/null; then bad "rawData $bad_raw was scored instead of refused"
  elif grep -q "needs at least 3" "$WORK/err.txt"; then ok "rawData $bad_raw refused with a reason"
  else bad "rawData $bad_raw refused without the expected reason: $(cat "$WORK/err.txt")"; fi
done
check "three iterations: the middle one" "1251" "$(score '[[1250.0,1251.0,1400.0]]' 1300.33)"

if [ "$FAILS" -gt 0 ]; then echo ":x: $FAILS microbench score check(s) failed" >&2; exit 1; fi
echo "all microbench score checks passed"
