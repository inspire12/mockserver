#!/usr/bin/env bash
# Fixture tests for the rw-multi-k6 same-requests cross-check (lib/perf-rw-cross-check.jq,
# performance programme item 31), no Docker needed. The base rung is a real rig rung (item 52):
# by_time dropped about one push interval of steady requests, so its p99 is far from the
# published one while by_tag, over the same requests, agrees.
# Run: .buildkite/scripts/test/perf-rw-cross-check-test.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
CROSS_JQ="$REPO_ROOT/.buildkite/scripts/steps/lib/perf-rw-cross-check.jq"
FAILS=0
ok()   { echo "  ok   $1"; }
bad()  { echo "  FAIL $1" >&2; FAILS=$((FAILS + 1)); }
check() { # name expected actual
  if [ "$2" = "$3" ]; then ok "$1"; else bad "$1: expected '$2', got '$3'"; fi
}

# One-rung input; $1 is a jq update applied to it (the degrade), $2 the mode.
input() { # update [mode]
  jq -nc --arg mode "${2:-cross}" '
    {mode:$mode,
     pub:{points:[{offered_rps:28800, achieved_rps:28740.3, sample_count:431104, measured_sample_count:345612,
                   p50_ms:0.081, p90_ms:0.114, p95_ms:0.134, p99_ms:0.359, p999_ms:1.642}]},
     bytime:{points:[{sample_count:431104, measured_sample_count:317026, p50_ms:0.081, p95_ms:0.132, p99_ms:0.252,
                      settle_cut_ok:true, accounting_ok:true, per_process:[{pool:2048}]}]},
     bytag:{points:[{measured_sample_count:345612, p50_ms:0.081, p95_ms:0.134, p99_ms:0.358}]},
     main:null, band:null, xcost:[],
     tol:{p50:0.10, p95:0.10, p99:0.15, abs_ms:0.005, push_s:1, window_tol:0.05,
          cross_run:{achieved_ratio:0.02, p50:0.20, p95:0.35, p99:0.60}}}' | jq -c "$1"
}
cross() { input "$1" | jq -c -f "$CROSS_JQ"; }
eqv() { jq -r '.equivalent' <<<"$1"; }
rung() { jq -r ".same_requests.rungs[0].$2" <<<"$1"; }

echo "--- 1. a rig rung whose time cut dropped ~1 s of requests: the window explains the p99 gap"
R="$(cross '.')"
check "by_time p99 outside the tight tolerance" "false" "$(rung "$R" by_time.p99.tight_ok)"
check "by_time p99 inside the window band" "true" "$(rung "$R" by_time.p99.ok)"
check "band lower bound is published p90 (level 0.908)" "0.114" "$(rung "$R" by_time.p99.band.lo_ms)"
check "window: 28586 excluded, within 60480" "28586 60480 true" "$(rung "$R" 'window | "\(.excluded) \(.max_excluded) \(.ok)"')"
check "by_tag agrees on the same requests" "true" "$(rung "$R" by_tag.ok)"
check "equivalent" "true" "$(eqv "$R")"
check "no failure listed" "0" "$(jq -r '.same_requests.failed | length' <<<"$R")"

echo "--- 2. band levels (mode levels) and by_tag at those levels"
L="$(input '.' levels | jq -c -f "$CROSS_JQ")"
check "6 levels (p50, p95, p99 lo/hi)" "6" "$(jq -r '.[0].levels | length' <<<"$L")"
check "p99 levels 0.9081..0.9908" "0.9081 0.9908" "$(jq -r '.[0].levels[4:6] | map(. * 10000 | round / 10000) | join(" ")' <<<"$L")"
BAND="$(jq -c '{points:[{quantiles:[ .[0].levels as $l | [$l[0],0.078],[$l[1],0.085],[$l[2],0.112],[$l[3],0.135],[$l[4],0.113],[$l[5],0.371] ]}]}' <<<"$L")"
R="$(cross ".band = $BAND")"
check "exact band: p99 upper bound tightens to by_tag Q(0.9908)" "0.371" "$(rung "$R" by_time.p99.band.hi_ms)"
check "exact band: rig rung still equivalent" "true" "$(eqv "$R")"
R="$(cross ".band = $BAND | .bytime.points[0].p99_ms = 1.851")"
check "exact band: by_time p99 from the whole rung (1.851) fails" "false" "$(eqv "$R")"
R="$(cross ".band = $BAND | .bytime.points[0].p99_ms = 0.05")"
check "exact band: by_time p99 below Q(0.908) fails" "false" "$(eqv "$R")"
R="$(cross ".band = $BAND | .band.points[0].quantiles[5][1] = null")"
check "exact band with a missing level: band not applied, rig rung fails" "false" "$(eqv "$R")"
check "  ... source recorded" "by_tag" "$(rung "$(cross ".band = $BAND")" by_time.p99.band.source)"
R="$(cross '.live = true')"
check "live run with no band file: no band, the tight check decides (rig rung fails)" "false" "$(eqv "$R")"
check "  ... band not applied" "null" "$(rung "$R" by_time.p99.band)"
check "live run with the band file: rig rung equivalent" "true" "$(eqv "$(cross ".live = true | .band = $BAND")")"
check "offline (no live flag), no band file: published-only band" "published_only" "$(rung "$(cross '.')" by_time.p99.band.source)"

echo "--- 3. a wrong query, histogram path or cut must still fail"
R="$(cross '.bytag.points[0].p99_ms = 0.718')"
check "by_tag p99 doubled" "false" "$(eqv "$R")"
check "by_tag p99 doubled names by_tag" "true" "$(jq -r '.same_requests.failed[0] | test("by_tag")' <<<"$R")"
R="$(cross '.bytag.points[0].measured_sample_count = 317026')"
check "by_tag over different requests (count)" "false" "$(eqv "$R")"
R="$(cross '.bytime.points[0].p50_ms = 0.162')"
check "by_time p50 doubled" "false" "$(eqv "$R")"
R="$(cross ".band = $BAND | .bytime.points[0].p95_ms = 0.268")"
check "by_time p95 doubled" "false" "$(eqv "$R")"
R="$(cross '.bytime.points[0].measured_sample_count = 431104')"
check "by_time cut not subtracted (settle requests kept)" "false" "$(eqv "$R")"
check "  ... rejected by the window" "false" "$(rung "$R" window.ok)"
R="$(cross '.bytime.points[0].measured_sample_count = (345612 - 86400)')"
check "by_time cut 3 pushes late" "false" "$(eqv "$R")"
R="$(cross '.bytime.points[0].settle_cut_ok = false')"
check "by_time cut outside its bound" "false" "$(eqv "$R")"
R="$(cross '.bytime.points[0].per_process[0].pool = null | .bytime.points[0].measured_sample_count = 345700')"
check "requests added with no pool to explain them" "false" "$(eqv "$R")"
R="$(cross '.bytime.points[0].p99_ms = null')"
check "by_time p99 missing" "false" "$(eqv "$R")"
R="$(cross '.bytime.points[0].sample_count = 431000')"
check "total counts differ" "false" "$(eqv "$R")"
R="$(cross '.bytime = null')"
check "by_time merge missing" "false" "$(eqv "$R")"
R="$(cross '.bytime.points[0].accounting_ok = false')"
check "by_time accounting failed" "false" "$(eqv "$R")"

echo "--- 4. window edges"
R="$(cross '.bytime.points[0] += {measured_sample_count:(345612 - 60480)}')"
check "d at the window maximum (2 pushes x rate x 1.05 = 60480) is allowed" "true" "$(rung "$R" window.ok)"
R="$(cross '.bytime.points[0] += {measured_sample_count:(345612 - 60481)}')"
check "d one over the window maximum fails" "false" "$(eqv "$R")"
R="$(cross '.bytime.points[0] += {measured_sample_count:(345612 + 1000), p99_ms:0.357}')"
check "d<0 (1000 settle requests completing after the cut, pool 2048): window ok" "true" "$(rung "$R" window.ok)"
check "d<0: band levels straddle q (q-(1-q)e/n, q+qe/n)" "0.98997 0.99286" "$(rung "$R" 'by_time.p99.band.levels | map(. * 100000 | round / 100000) | join(" ")')"
check "d<0 with agreeing quantiles: equivalent" "true" "$(eqv "$R")"
R="$(cross '.bytime.points[0] += {measured_sample_count:(345612 + 2049)}')"
check "d<0 beyond the in-flight pool fails" "false" "$(eqv "$R")"

echo "--- 5. a cut at the boundary needs no band"
R="$(cross '.bytime.points[0] += {measured_sample_count:345612, p99_ms:0.357}')"
check "d=0, tight agreement" "true" "$(eqv "$R")"
check "d=0, p99 tight_ok" "true" "$(rung "$R" by_time.p99.tight_ok)"
R="$(cross '.bytime.points[0] += {measured_sample_count:345612, p99_ms:0.252}')"
check "d=0, same p99 gap fails (no window to explain it)" "false" "$(eqv "$R")"

if [ "$FAILS" -gt 0 ]; then echo ":x: $FAILS cross-check fixture check(s) failed" >&2; exit 1; fi
echo "all cross-check fixture checks passed"
