# Reshapes the MatchingBenchmark JMH result (-rf json) into perf-microbench.json, the GATING
# microbench.* rows, plus the .config.jmh fingerprint from $args / $argsExtra / $argsScaling.
# time_per_op is a trimmed mean of every fork's measured iterations (drop the highest and lowest
# one), so one iteration from a fork still settling cannot trip the gate, while a real slowdown,
# which moves every iteration, moves it as much as JMH's mean (kept as time_per_op_jmh_mean, not
# compared). Missing rawData or fewer than 3 iterations is an error, never a fallback.
# Run on fixtures by .buildkite/scripts/test/perf-microbench-score-test.sh.

def trimmed_mean:
  sort | .[1:-1] | add / length;

def time_score:
  [ (.primaryMetric.rawData // [])[][] ] as $v
  | if ($v | length) < 3 or ($v | any(type != "number")) then
      error("JMH row \(.benchmark) \(.params | tojson) has \($v | length) numeric measured iteration(s) in primaryMetric.rawData; the gating time_per_op needs at least 3")
    else $v | trimmed_mean end;

[ .[] | {
    key: (.params.matcherType + "_" + .params.expectationCount
          + (if .params.detailedMatchFailures == "true" then "_detailed" else "" end)),
    value: {
      time_per_op: time_score,
      time_per_op_jmh_mean: .primaryMetric.score,
      time_unit: .primaryMetric.scoreUnit,
      alloc_bytes_per_op: (.secondaryMetrics["gc.alloc.rate.norm"].score // null)
    }
  } ] | from_entries
| { microbench: ., config: { jmh: { args: $args, args_extra: $argsExtra, args_scaling: $argsScaling } } }
