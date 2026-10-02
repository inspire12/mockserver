#!/usr/bin/env bash
# Fixture tests for the multi-k6 arm's default k6 runtime (performance programme item 31):
# lib/perf-k6-runtime.sh, and the harness resolving it (PERF_RW_TEST_RESOLVE_ONLY) against a stub
# `docker` on PATH, so no Docker is needed.
# Run: .buildkite/scripts/test/perf-k6-runtime-test.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
# shellcheck source=../steps/lib/perf-k6-runtime.sh
. "$REPO_ROOT/.buildkite/scripts/steps/lib/perf-k6-runtime.sh"
HARNESS="${PERF_K6RT_HARNESS:-$REPO_ROOT/mockserver-performance-test/scripts/rw-multi-k6-sweep.sh}"
T="$(mktemp -d "${TMPDIR:-/tmp}/perf-k6rt-test.XXXXXX")"
trap 'rm -rf "$T"' EXIT
FAILS=0
ok()   { echo "  ok   $1"; }
bad()  { echo "  FAIL $1" >&2; FAILS=$((FAILS + 1)); }
check() { # name expected actual
  if [ "$2" = "$3" ]; then ok "$1"; else bad "$1: expected '$2', got '$3'"; fi
}
GIB=1073741824
C5_MEM=$(( 96 * GIB ))
LAPTOP_MEM=8214323200 # an 8 GiB Docker Desktop VM's MemTotal

echo "--- 1. gracefulStop default: the gap in whole seconds, at least 1 s"
check "5 s gap" "5s" "$(k6_graceful_stop_default 5)"
check "4 s gap (per-core ladder)" "4s" "$(k6_graceful_stop_default 4)"
check "sub-second gap -> 1 s floor" "1s" "$(k6_graceful_stop_default 0)"
check "empty gap fails" "fail" "$(k6_graceful_stop_default "" || echo fail)"
check "non-numeric gap fails" "fail" "$(k6_graceful_stop_default 5s || echo fail)"

echo "--- 2. GOMEMLIMIT default: half the Docker host's memory over N, whole MiB"
check "c5.12xlarge (96 GiB), N=4 -> 12 GiB" "12288MiB" "$(k6_gomemlimit_default "$C5_MEM" 4)"
check "c5.12xlarge, N=8 -> 6 GiB" "6144MiB" "$(k6_gomemlimit_default "$C5_MEM" 8)"
check "8 GiB Docker Desktop VM, N=3" "1305MiB" "$(k6_gomemlimit_default "$LAPTOP_MEM" 3)"
LIMIT_MIB="$(k6_gomemlimit_default "$LAPTOP_MEM" 3)"; LIMIT_MIB="${LIMIT_MIB%MiB}"
check "the k6 total never exceeds half the host" "yes" \
  "$([ $(( LIMIT_MIB * 3 * 1048576 )) -le $(( LAPTOP_MEM / 2 )) ] && echo yes || echo no)"
check "unreadable memory fails" "fail" "$(k6_gomemlimit_default "" 4 || echo fail)"
check "zero memory fails" "fail" "$(k6_gomemlimit_default 0 4 || echo fail)"
check "non-numeric memory fails" "fail" "$(k6_gomemlimit_default "<no value>" 4 || echo fail)"
check "N=0 fails" "fail" "$(k6_gomemlimit_default "$C5_MEM" 0 || echo fail)"
check "under 1 MiB per process fails" "fail" "$(k6_gomemlimit_default 1000000 4 || echo fail)"

echo "--- 3. the harness resolves and records the defaults (stub docker, nothing started)"
mkdir -p "$T/bin"
cat > "$T/bin/docker" <<'STUB'
#!/usr/bin/env bash
echo "docker $*" >> "$STUB_DOCKER_LOG"
if [ "$1" = info ] && [ -n "${STUB_MEM:-}" ]; then echo "$STUB_MEM"; exit 0; fi
exit 1
STUB
chmod +x "$T/bin/docker"
resolve() { # env assignments... -> the harness's k6_runtime JSON in $R, its exit code in $RC
  RC=0
  env PATH="$T/bin:$PATH" STUB_DOCKER_LOG="$T/docker.log" PERF_RW_TEST_RESOLVE_ONLY=true \
    PERF_RW_K6_CPUSETS="1;2;3;4" PERF_RW_PROCS=4 PERF_RW_REPO_ROOT="$REPO_ROOT" "$@" \
    bash "$HARNESS" >"$T/out.json" 2>"$T/stderr.log" || RC=$?
  R="$(cat "$T/out.json")"
}
: > "$T/docker.log"
resolve STUB_MEM="$C5_MEM"
check "defaults: exit 0" "0" "$RC"
check "defaults: gogc 400, 12 GiB, gracefulStop = the 5 s gap" '"400" "12288MiB" "5s"' \
  "$(jq -r '"\"\(.gogc)\" \"\(.gomemlimit)\" \"\(.graceful_stop)\""' <<<"$R")"
check "defaults: sources" "default derived default (the gap)" \
  "$(jq -r '[.source.gogc, .source.gomemlimit, .source.graceful_stop] | join(" ")' <<<"$R")"
check "defaults: GOMEMLIMIT basis recorded" "$C5_MEM 4 50" \
  "$(jq -r '"\(.gomemlimit_basis.docker_mem_total_bytes) \(.gomemlimit_basis.procs) \(.gomemlimit_basis.host_pct)"' <<<"$R")"
resolve STUB_MEM="$C5_MEM" PERF_RW_GAP=4s
check "a 4 s gap gives a 4 s gracefulStop" "4s" "$(jq -r .graceful_stop <<<"$R")"
resolve STUB_MEM="$C5_MEM" PERF_RW_K6_GOGC= PERF_RW_K6_GOMEMLIMIT= PERF_RW_K6_GRACEFUL_STOP=
check "an empty variable (a caller forwarding an unset one) gets the default" '400 12288MiB 5s' \
  "$(jq -r '"\(.gogc) \(.gomemlimit) \(.graceful_stop)"' <<<"$R")"
: > "$T/docker.log"
resolve STUB_MEM= PERF_RW_K6_GOGC=100 PERF_RW_K6_GOMEMLIMIT=off PERF_RW_K6_GRACEFUL_STOP=30s
check "k6 defaults are settable back: exit 0" "0" "$RC"
check "k6 defaults are settable back: 100 off 30s" "100 off 30s" "$(jq -r '"\(.gogc) \(.gomemlimit) \(.graceful_stop)"' <<<"$R")"
check "k6 defaults: sources env, no basis" "env env env null" \
  "$(jq -r '"\(.source.gogc) \(.source.gomemlimit) \(.source.graceful_stop) \(.gomemlimit_basis)"' <<<"$R")"
check "an explicit GOMEMLIMIT never asks Docker" "" "$(cat "$T/docker.log")"
resolve STUB_MEM= PERF_RW_K6_GOMEMLIMIT=8GiB
check "an explicit GOMEMLIMIT is applied as given" "8GiB" "$(jq -r .gomemlimit <<<"$R")"

echo "--- 4. fail closed when the default cannot be derived or a value is invalid"
resolve STUB_MEM=
check "Docker memory unreadable: non-zero exit" "1" "$RC"
check "Docker memory unreadable: names the setting to use" "yes" \
  "$(grep -q 'set PERF_RW_K6_GOMEMLIMIT' "$T/stderr.log" && echo yes || echo no)"
env PATH="$T/bin:$PATH" STUB_DOCKER_LOG="$T/docker.log" PERF_RW_K6_CPUSETS="1;2;3;4" PERF_RW_PROCS=4 \
  PERF_RW_REPO_ROOT="$REPO_ROOT" bash "$HARNESS" "$T/fallback.json" 2>/dev/null || true
check "Docker memory unreadable: the written result is invalid and names it" "false true" \
  "$(jq -r '"\(.valid) \(.validity.checks[0].detail | test("GOMEMLIMIT"))"' "$T/fallback.json" 2>/dev/null || echo unreadable)"
check "Docker memory unreadable: the result still records the resolved knobs" "400 null 5s" \
  "$(jq -r '"\(.config.k6_runtime.gogc) \(.config.k6_runtime.gomemlimit) \(.config.k6_runtime.graceful_stop)"' "$T/fallback.json" 2>/dev/null || echo unreadable)"
rm -f "$T/resolve-out.json"; RC=0
env PATH="$T/bin:$PATH" STUB_DOCKER_LOG="$T/docker.log" STUB_MEM="$C5_MEM" PERF_RW_TEST_RESOLVE_ONLY=true \
  PERF_RW_K6_CPUSETS="1;2;3;4" PERF_RW_PROCS=4 PERF_RW_REPO_ROOT="$REPO_ROOT" \
  bash "$HARNESS" "$T/resolve-out.json" >/dev/null 2>&1 || RC=$?
check "the resolve-only hook with an output file (a real run) is rejected with exit 2" "2 absent" \
  "$RC $([ -e "$T/resolve-out.json" ] && echo written || echo absent)"
for bad_env in PERF_RW_K6_GOGC=lots PERF_RW_K6_GOMEMLIMIT=12G PERF_RW_K6_GRACEFUL_STOP=500ms PERF_RW_TEST_RESOLVE_ONLY=yes; do
  resolve STUB_MEM="$C5_MEM" "$bad_env"
  check "rejected at startup with exit 2: $bad_env" "2" "$RC"
done

echo "--- 5. wiring: every measured k6 process gets the resolved values, and no caller overrides them"
block() { awk -v a="$1" -v b="$2" 'index($0, a) == 1 {on = 1} on {print} on && index($0, b) {exit}' "$HARNESS"; }
RUN="$(block 'run_phase() {' '/k6/sweep.js >/dev/null')"
has() { grep -qE -- "$1" <<<"$2" && echo yes || echo no; }
check "the k6 run passes K6_GO_ENV" "yes" "$(has '\$\{K6_GO_ENV\[@\]\+"\$\{K6_GO_ENV\[@\]\}"\}' "$RUN")"
check "the k6 run passes the resolved gracefulStop" "yes" "$(has '"K6_SWEEP_GRACEFUL_STOP=\$K6_GRACEFUL_STOP"' "$RUN")"
check "K6_GO_ENV always carries both Go knobs" "yes" \
  "$(has '^K6_GO_ENV=\(-e "GOGC=\$K6_GOGC" -e "GOMEMLIMIT=\$K6_GOMEMLIMIT"\)$' "$(cat "$HARNESS")")"
OVERRIDES="$(grep -nE 'PERF_RW_K6_(GOGC|GOMEMLIMIT|GRACEFUL_STOP)=' \
  "$REPO_ROOT/.buildkite/scripts/steps/perf-test-run.sh" "$REPO_ROOT/.buildkite/scripts/steps/lib/perf-percore.sh" \
  "$REPO_ROOT"/.buildkite/*.yml 2>/dev/null || true)"
check "perf-test-run.sh, perf-percore.sh and the pipelines inherit the defaults" "" "$OVERRIDES"

echo "--- 6. perf-test-compare.sh: the hardware matrix baseline keys on the k6 runtime"
COMPARE_SH="${PERF_K6RT_COMPARE:-$REPO_ROOT/.buildkite/scripts/steps/perf-test-compare.sh}"
COMPARE="$(awk '/^COMPARE='"'"'$/ {on = 1; next} on && /^'"'"'$/ {exit} on' "$COMPARE_SH")"
check "the whole compare program compiles and runs on an empty run" "0" \
  "$(jq -n --argjson minbaseline 5 '([]) as $baseline | ({}) as $budgets | ({}) | '"$COMPARE" >/dev/null 2>&1; echo $?)"
DEFS="$(awk 'index($0, "def latk6rt(") == 1 {on = 1} index($0, "def latpresent(") == 1 {exit} on' "$COMPARE_SH")"
fp() { # family block_json -> latfp of a run holding that block
  jq -nr --arg f "$1" --argjson b "$2" "$DEFS"' {($f): $b} | latfp($f) | tostring'
}
hw() { # k6_runtime_json [client] -> a hardware-matrix block whose points carry it
  jq -nc --argjson rt "$1" --arg c "${2:-multik6}" \
    '{sweep: {latency_settle_s: 3}, client: $c, points: [{measurement: null}, {measurement: {k6_runtime: $rt}}]}'
}
K6DEF="3|multik6|gogc=100,graceful_stop=30s,gomemlimit=off"
NEWDEF="3|multik6|gogc=400,graceful_stop=5s,gomemlimit=derived"
check "no runtime (runs before the default) is k6 defaults" "$K6DEF" "$(fp serving_hw_matrix "$(hw null)")"
check "k6 defaults set back explicitly match older runs" "$K6DEF" \
  "$(fp serving_hw_matrix "$(hw '{"gogc":"100","gomemlimit":"off","graceful_stop":"30s","source":{"gogc":"env","gomemlimit":"env","graceful_stop":"env"}}')")"
DERIVED='{"gogc":"400","gomemlimit":"12288MiB","graceful_stop":"5s","source":{"gogc":"default","gomemlimit":"derived","graceful_stop":"default (the gap)"}}'
check "the new default" "$NEWDEF" "$(fp serving_hw_matrix "$(hw "$DERIVED")")"
check "a different derived MiB (host memory) does not reset the baseline" "$NEWDEF" \
  "$(fp serving_hw_matrix "$(hw "$(jq -c '.gomemlimit = "11770MiB"' <<<"$DERIVED")")")"
check "an A/B-era explicit limit (no .source) is its own signature" "3|multik6|gogc=400,graceful_stop=5s,gomemlimit=env" \
  "$(fp serving_hw_matrix "$(hw '{"gogc":"400","gomemlimit":"12GiB","graceful_stop":"5s"}')")"
check "a single-k6 matrix carries k6 defaults" "3|single|gogc=100,graceful_stop=30s,gomemlimit=off" \
  "$(fp serving_hw_matrix '{"sweep":{"latency_settle_s":3},"points":[{"measurement":{"client":"single"}}]}')"
check "a block without a settle stays null" "null" "$(fp serving_hw_matrix '{"client":"multik6","points":[]}')"
check "other families keep the settle alone" "3" "$(fp serving_percore '{"sweep":{"latency_settle_s":3}}')"
check "a pinned 2,048 VU ceiling keeps the existing key" "$NEWDEF" \
  "$(fp serving_hw_matrix "$(hw "$(jq -c '.vu_ceiling = 2048 | .source.vu_ceiling = "env"' <<<"$DERIVED")")")"
check "any other VU ceiling is its own signature" "$NEWDEF,vu_ceiling=3072" \
  "$(fp serving_hw_matrix "$(hw "$(jq -c '.vu_ceiling = 3072 | .source.vu_ceiling = "derived"' <<<"$DERIVED")")")"
PERCORE_RUN="$(awk 'index($0, "run_point_multik6() {") == 1 {on = 1} on {print} on && /^}/ {exit}' \
  "$REPO_ROOT/.buildkite/scripts/steps/lib/perf-percore.sh")"
check "every hardware-matrix point pins the VU ceiling at 2,048 (comparable across hosts)" "yes" \
  "$(grep -qE '(^|[[:space:]])PERF_RW_K6_VU_CEILING=2048([[:space:]]|$)' <<<"$PERCORE_RUN" && echo yes || echo no)"

if [ "$FAILS" -gt 0 ]; then echo ":x: $FAILS k6 runtime check(s) failed" >&2; exit 1; fi
echo "--- all k6 runtime fixture checks passed"
