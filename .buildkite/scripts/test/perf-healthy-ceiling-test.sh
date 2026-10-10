#!/usr/bin/env bash
# Fixture tests for the healthy ceiling rule (docs/code/performance-measurement.md, "The healthy
# ceiling rule"): lib/perf-website-figures.jq, and the two copies of the rule that cannot call it
# (multi-process-sweep.sh's fallback, render_perf_charts.py). No Docker needed.
# Run: .buildkite/scripts/test/perf-healthy-ceiling-test.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
# shellcheck source=lib/perf-test-env.sh
. "$REPO_ROOT/.buildkite/scripts/test/lib/perf-test-env.sh"
perf_test_scrub_env PERF_HC_FIGURES_JQ PERF_HC_MULTI_SH PERF_HC_CHARTS_PY
FIGURES_JQ="${PERF_HC_FIGURES_JQ:-$REPO_ROOT/.buildkite/scripts/steps/lib/perf-website-figures.jq}"
MULTI_SH="${PERF_HC_MULTI_SH:-$REPO_ROOT/mockserver-performance-test/scripts/multi-process-sweep.sh}"
CHARTS_PY="${PERF_HC_CHARTS_PY:-$REPO_ROOT/jekyll-www.mock-server.com/images/perf-charts/render_perf_charts.py}"
FAILS=0
ok()   { echo "  ok   $1"; }
bad()  { echo "  FAIL $1" >&2; FAILS=$((FAILS + 1)); }
check() { # name expected actual
  if [ "$2" = "$3" ]; then ok "$1"; else bad "$1: expected '$2', got '$3'"; fi
}

# run <points> [ladder|null] [p99_max_ms] -> the figures transform of a synthetic run
run() {
  local extra=()
  [ -z "${3:-}" ] || extra=(--argjson p99_max_ms "$3")
  jq -nc --argjson p "$1" --argjson l "${2:-null}" \
    '{schema_version:2, timestamp_utc:"2026-10-01T00:00:00Z", config:{}, agent:{}, sweep:{points:$p}}
     + (if $l == null then {} else {saturation:{ladder:$l}} end)' \
  | jq -c --arg now x --argjson lat_mult 3 --argjson keep 0.95 --arg fix_date 2026-09-16 ${extra[@]+"${extra[@]}"} -f "$FIGURES_JQ"
}
hc() { jq -r '.headline.healthy_ceiling_rps // "null"' <<<"$1"; }
# pt <offered> [p99] [achieved] [p50] [errors] -> one sweep point
pt() { jq -nc --argjson o "$1" --argjson p99 "${2:-1}" --argjson a "${3:-$1}" --argjson p50 "${4:-0.1}" --argjson e "${5:-0}" \
  '{offered_rps:$o, achieved_rps:$a, p50_ms:$p50, p95_ms:(if $p99 == null then null else $p99 / 2 end), p99_ms:$p99, error_rate:$e}'; }
pts() { local IFS=,; echo "[$*]"; }
# rung <offered> <rig_valid> [client_limited] [error_rate] -> one saturation.ladder rung
rung() { jq -nc --argjson o "$1" --argjson v "$2" --argjson c "${3:-false}" --argjson e "${4:-0}" \
  '{offered_rps:$o, rig_valid:$v, client_limited:$c, error_rate:$e}'; }

echo "--- 1. a failed rung stops the climb (build 605's p99-bounded ladder)"
P605="$(pts "$(pt 96000 4.13)" "$(pt 104000 0.71)" "$(pt 112000 1.01)" "$(pt 120000 2.16)" "$(pt 128000 2.20)" \
  "$(pt 136000 35.59)" "$(pt 144000 8.77)" "$(pt 152000 9.65)" "$(pt 160000 53.92)")"
L605="$(pts "$(rung 96000 true)" "$(rung 104000 true)" "$(rung 112000 true)" "$(rung 120000 true)" "$(rung 128000 true)" \
  "$(rung 136000 true)" "$(rung 144000 true)" "$(rung 152000 true)" "$(rung 160000 true)")"
F="$(run "$P605" "$L605" 10)"
check "the ceiling is the healthy rung below the first failure, not the healthy 152k above it" "128000" "$(hc "$F")"
check "the ceiling's own p99 is reported" "2.2" "$(jq -r .headline.healthy_ceiling_p99_ms <<<"$F")"
check "a measured failure above the ceiling is an overload, not a lower bound" "false false" \
  "$(jq -r '"\(.headline.no_measured_overload) \(.headline.lower_bound)"' <<<"$F")"
check "every rung above the ceiling is degraded, the healthy ones past the stop included" "136000,144000,152000,160000" \
  "$(jq -r '[.throughput_ladder[] | select(.degraded) | .offered_rps] | join(",")' <<<"$F")"
check "unbounded (p50-only), the same ladder has no failure: the top rung" "160000" "$(hc "$(run "$P605" "$L605")")"

echo "--- 2. rig-invalid rungs neither count nor stop the climb"
PX="$(pts "$(pt 1000)" "$(pt 2000)" "$(pt 3000)" "$(pt 4000)" "$(pt 5000 1 4000)" "$(pt 6000)" "$(pt 7000 1 5000)" "$(pt 8000)")"
LX="$(pts "$(rung 1000 true)" "$(rung 2000 true)" "$(rung 3000 true)" "$(rung 4000 true)" "$(rung 5000 false true)" \
  "$(rung 6000 true)" "$(rung 7000 true)" "$(rung 8000 true)")"
FX="$(run "$PX" "$LX")"
check "an excluded short rung is skipped, a measured short rung stops" "6000" "$(hc "$FX")"
check "the excluded rung is not published" "1000,2000,3000,4000,6000,7000,8000" \
  "$(jq -r '[.throughput_ladder[].offered_rps] | join(",")' <<<"$FX")"
check "the same short rung with no rig information (no saturation.ladder) stops the climb" "4000" "$(hc "$(run "$PX")")"
check "an error at any measured rung stops it" "2000" \
  "$(hc "$(run "$(pts "$(pt 1000)" "$(pt 2000)" "$(pt 3000 1 3000 0.1 0.001)" "$(pt 4000)" "$(pt 5000)")")")"
check "p50 past 3x the flat region stops it" "4000" \
  "$(hc "$(run "$(pts "$(pt 1000)" "$(pt 2000)" "$(pt 3000)" "$(pt 4000)" "$(pt 5000 1 5000 0.5)" "$(pt 6000)")")")"
check "under the p99 bound, a rung with no p99 stops it" "2000" \
  "$(hc "$(run "$(pts "$(pt 1000)" "$(pt 2000)" "$(pt 3000 null)" "$(pt 4000)")" null 10)")"
# An excluded rung that returned errors with client headroom is the server's failure, so it stops the climb.
PE="$(pts "$(pt 1000)" "$(pt 2000)" "$(pt 3000 1 3000 0.1 0.05)" "$(pt 4000)" "$(pt 5000)" "$(pt 6000)")"
LE="$(pts "$(rung 1000 true)" "$(rung 2000 true)" "$(rung 3000 false false 0.05)" "$(rung 4000 true)" "$(rung 5000 true)" "$(rung 6000 true)")"
LEC="$(pts "$(rung 1000 true)" "$(rung 2000 true)" "$(rung 3000 false true 0.05)" "$(rung 4000 true)" "$(rung 5000 true)" "$(rung 6000 true)")"
check "an excluded rung with 5% errors below healthy rungs stops the climb" "2000" "$(hc "$(run "$PE" "$LE")")"
check "the same rung client-limited stays transparent" "6000" "$(hc "$(run "$PE" "$LEC")")"
check "a failing lowest rung leaves no ceiling" "null" \
  "$(hc "$(run "$(pts "$(pt 1000 1 900)" "$(pt 2000)" "$(pt 3000)")")")"

echo "--- 3. the lower bound reads only the rungs above the ceiling"
PL="$(pts "$(pt 1000)" "$(pt 2000)" "$(pt 3000)" "$(pt 4000)" "$(pt 5000 1 4000)")"
FL="$(run "$PL" "$(pts "$(rung 1000 true)" "$(rung 2000 true)" "$(rung 3000 true)" "$(rung 4000 true)" "$(rung 5000 false true)")")"
check "every rung healthy, the next one up client-limited: a lower bound at the top" "4000 true true" \
  "$(jq -r '"\(.headline.healthy_ceiling_rps) \(.headline.no_measured_overload) \(.headline.lower_bound)"' <<<"$FL")"
FM="$(run "$PL" "$(pts "$(rung 1000 true)" "$(rung 2000 true)" "$(rung 3000 true)" "$(rung 4000 true)" "$(rung 5000 true)")")"
check "the same top rung measured and short: an overload, not a lower bound" "4000 false false" \
  "$(jq -r '"\(.headline.healthy_ceiling_rps) \(.headline.no_measured_overload) \(.headline.lower_bound)"' <<<"$FM")"
FEA="$(run "$PL" "$(pts "$(rung 1000 true)" "$(rung 2000 true)" "$(rung 3000 true)" "$(rung 4000 true)" "$(rung 5000 false false 0.05)")")"
check "an excluded error rung above the ceiling: not a lower bound, and no peak (no rig-valid overload)" "4000 true false null" \
  "$(jq -r '"\(.headline.healthy_ceiling_rps) \(.headline.no_measured_overload) \(.headline.lower_bound) \(.headline.peak_achieved_rps)"' <<<"$FEA")"

echo "--- 4. the copies of the rule agree with the filter"
# Each copy sees what its caller passes: the rig-valid points plus the ladder (the filter filters itself).
rv() { jq -c --argjson l "${2:-null}" 'if $l == null then . else [ .[] | .offered_rps as $o
  | select([$l[] | select(.rig_valid == true) | .offered_rps] | index($o)) ] end' <<<"$1"; }
FALLBACK="$(awk 'index($0, "headline=\"$(jq -c --argjson keep \"$KEEP\" --argjson l \"$lad\" '"'"'") {on = 1; next} on && index($0, "'"'"' \"$pts_file\")\"") {exit} on' "$MULTI_SH")"
[ -n "$FALLBACK" ] || bad "multi-process-sweep.sh: the fallback jq program was not found"
PYFN="$(cat <<'PY'
import ast, json, sys
tree = ast.parse(open(sys.argv[1]).read())
fn = [n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name in ("_healthy_ceiling", "_error_stops")]
ns = {}
exec(compile(ast.Module(body=fn, type_ignores=[]), sys.argv[1], "exec"), ns)
pts, lad = json.loads(sys.argv[2]), json.loads(sys.argv[3])
c = ns["_healthy_ceiling"](pts, stops=ns["_error_stops"]({"saturation": {"ladder": lad or []}}))
print("null" if c is None else "%s/%s" % (c["offered_rps"], c.get("tag", "-")))
PY
)"
command -v python3 >/dev/null 2>&1 || bad "python3 absent: render_perf_charts.py's rule cannot be checked"
# Edge inputs: a null error_rate is 0; a duplicate offered rate where one copy fails stops at that rate,
# and of two healthy copies the later one is the ceiling (jq's max_by keeps the last tie).
PN="$(pts "$(pt 1000)" "$(pt 2000)" "$(pt 3000 1 3000 0.1 null)" "$(pt 4000)")"
PD="$(pts "$(pt 1000)" "$(pt 2000)" "$(pt 3000)" "$(pt 3000 1 2000)" "$(pt 4000)")"
PT="$(jq -c '.[3].tag = "first" | .[4].tag = "second"' <<<"$(pts "$(pt 1000)" "$(pt 2000)" "$(pt 3000)" "$(pt 4000)" "$(pt 4000 1 4000 0.12)" "$(pt 5000 1 1000)")")"
NC=0
# shellcheck disable=SC2034  # read through ${!p} below
PNP="$(pts "$(pt 1000)" "$(pt 2000)" "$(pt 3000)" "$(pt 4000)" "$(pt 5000 1 5000 null)" "$(pt 6000)")"
for c in "PX|LX" "PE|LE" "PE|LEC" "PE|" "PN|" "PD|" "PT|" "PNP|" "P605|" "P605|L605" \
         "$(pts "$(pt 1000)" "$(pt 2000)" "$(pt 3000)" "$(pt 4000)" "$(pt 5000 1 5000 0.5)" "$(pt 6000)")|"; do
  NC=$((NC + 1))
  p="${c%%|*}"; l="${c#*|}"
  case "$p" in \[*) ;; *) p="${!p}" ;; esac
  if [ -n "$l" ]; then l="${!l}"; else l=null; fi
  want="$(run "$p" "$l" | jq -r '.headline.healthy_ceiling_rps // "null"')"
  in="$(rv "$p" "$l")"
  if [ -n "$FALLBACK" ]; then
    check "case $NC: multi-process fallback = filter ($want)" "$want" \
      "$(jq -r --argjson keep 0.95 --argjson l "$l" "$FALLBACK" <<<"$in" | jq -r '.healthy_ceiling_rps // "null"')"
  fi
  if command -v python3 >/dev/null 2>&1; then
    got="$(python3 -c "$PYFN" "$CHARTS_PY" "$in" "$l" 2>&1)" || got="python failed: $got"
    check "case $NC: render_perf_charts.py = filter ($want)" "$want" "${got%%/*}"
  fi
done
AGGCALL="$(grep -E '^  read -r AGG_HC ' "$MULTI_SH" || true)"
check "multi-process: the aggregate ceiling gets the aggregate ladder" "yes" \
  "$(grep -qF 'healthy_ceiling_of "$AGG_PTS" "$WORK/agg-ladder-N${N}.json"' <<<"$AGGCALL" && echo yes || echo no)"
check "  ... built from client_sound, any_client_at_pin and error_rate_max" "yes" \
  "$(grep -qF '{offered_rps:.agg_offered_rps, rig_valid:.client_sound, client_limited:.any_client_at_pin,' "$MULTI_SH" \
     && grep -qF 'error_rate:.error_rate_max} ]'"'"' <<<"$AGG" > "$WORK/agg-ladder-N${N}.json"' "$MULTI_SH" && echo yes || echo no)"
check "render_perf_charts.py: the knee chart's ceiling gets the run's error stops" "yes yes" \
  "$(grep -qF 'hc = _healthy_ceiling(pts, stops=_error_stops(result))' "$CHARTS_PY" && echo yes || echo no) $(grep -qF 'chart_knee(sweep, args.out, result)' "$CHARTS_PY" && echo yes || echo no)"
check "the edge cases: null error_rate counts as 0" "4000" "$(hc "$(run "$PN")")"
check "the edge cases: a failing duplicate stops at its rate" "2000" "$(hc "$(run "$PD")")"
check "the edge cases: of two healthy duplicates the filter takes the later" "0.12" "$(run "$PT" | jq -r .headline.healthy_ceiling_p50_ms)"
check "  ... and so does render_perf_charts.py" "4000/second" "$(python3 -c "$PYFN" "$CHARTS_PY" "$PT" null 2>&1)"

if [ "$FAILS" -gt 0 ]; then echo ":x: $FAILS healthy ceiling check(s) failed" >&2; exit 1; fi
echo "--- all healthy ceiling fixture checks passed"
