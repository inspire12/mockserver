#!/usr/bin/env bash
# Interrupted k6 iterations, read from k6's progress output (needs `k6 run` WITHOUT --quiet).
# An iteration still running when its scenario's gracefulStop expires is cut off: its request
# reached the SUT, but k6 emits no metric for it (not http_reqs, not http_req_failed, not
# dropped_iterations), so no count can reconcile it. The only place k6 reports it is the progress
# line "running (<elapsed>), <a>/<b> VUs, <N> complete and <M> interrupted iterations".
# Tests: .buildkite/scripts/test/perf-k6-interrupted-test.sh

# k6_interrupted_json <proc> <log>: {proc, complete, interrupted, first_interrupted_at_s} from
# the LAST progress line, which is the end-of-run one for a process that exited (the harness gates
# that separately). Counts are null when the log has none (a --quiet run), which
# k6_interrupted_check treats as a failure, never as zero.
k6_interrupted_json() {
  { tr -d '\r' < "$2" || true; } 2>/dev/null | awk -v proc="$1" '
    function secs(s,   t, n, c, i) { # "06.0s", "1m05.0s", "1h02m03.0s"
      t = 0; n = ""
      for (i = 1; i <= length(s); i++) {
        c = substr(s, i, 1)
        if (c ~ /[0-9.]/) n = n c
        else { if (c == "h") t += n * 3600; else if (c == "m") t += n * 60; else if (c == "s") t += n; n = "" }
      }
      return t
    }
    $1 == "running" && $4 == "VUs," && $6 == "complete" && $7 == "and" && $9 == "interrupted" \
      && $5 ~ /^[0-9]+$/ && $8 ~ /^[0-9]+$/ {
      complete = $5; interrupted = $8; seen = 1
      if (interrupted + 0 > 0 && first == "") { e = $2; gsub(/[(),]/, "", e); first = secs(e) }
    }
    END {
      if (!seen) { printf "{\"proc\":\"%s\",\"complete\":null,\"interrupted\":null,\"first_interrupted_at_s\":null}\n", proc; exit }
      printf "{\"proc\":\"%s\",\"complete\":%d,\"interrupted\":%d,\"first_interrupted_at_s\":%s}\n",
        proc, complete, interrupted, (first == "" ? "null" : first)
    }'
}

# k6_interrupted_check <entries_json_array>: the rw_no_interrupted_iterations validity check.
# Fails closed: an empty list, or any process without a readable count, fails it.
k6_interrupted_check() {
  jq -c '
    ([ .[] | select(.interrupted != 0) ]) as $bad
    | {name: "rw_no_interrupted_iterations",
       ok: (length > 0 and ($bad | length) == 0),
       detail: (if length == 0 then "no k6 process log to read the interrupted count from"
                elif ($bad | length) == 0 then "ok"
                else "k6 interrupted iterations at a scenario gracefulStop; their requests reached the SUT but are in no count: "
                     + ([ $bad[] | if .interrupted == null
                                   then "\(.proc) count unreadable (no k6 progress line in its log)"
                                   else "\(.proc) \(.interrupted) interrupted (first at \(.first_interrupted_at_s) s)" end ]
                        | join(", ")) end)}' <<<"$1"
}
