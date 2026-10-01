#!/usr/bin/env bash
set -euo pipefail

# Lint the k6 performance harness:
#   1. syntax-check the shell run scripts (bash on the agent), and
#   2. validate every k6 entry script in a pinned grafana/k6 container —
#      `k6 inspect` parses the JS, resolves the lib/ imports, and validates the
#      options/scenarios/thresholds without running any load.
#
# Reproduce locally: run this script from the repo root (needs docker).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
PERF_DIR="$REPO_ROOT/mockserver-performance-test"

echo "--- syntax-checking run scripts"
for s in scripts/runMockServer.sh scripts/runK6.sh scripts/runAll.sh; do
  echo "bash -n $s"
  bash -n "$PERF_DIR/$s"
done

echo "--- shellcheck on the perf shell libs (catches mangled embedded jq)"
# WHY THIS IS HERE AND WHY `bash -n` IS NOT ENOUGH.
#
# These scripts embed jq programs as single-quoted bash strings. A possessive in
# a comment INSIDE such a program -- k6's, pool's, the sweep's -- closes the
# string early. If a second one appears later it re-opens it, so the file's
# quoting stays BALANCED and `bash -n` reports it valid. jq then receives
# mangled text and the step dies at that line mid-run.
#
# Build 347 is the worked example: perf-percore.sh carried k6's and pool's on
# adjacent comment lines inside the result-assembly jq program. bash -n passed,
# an authoritative review passed, and the per-core ladder still died with
#   syntax error near unexpected token `$sweep[0].vus_diagnostics'
# AFTER the ladder had run -- so item 18's curve was lost while every check was
# green.
#
# SC1073/SC1072 DO catch it -- verified against that exact broken file, and
# clean on the fixed one -- so the guard is a mature parser rather than a bespoke
# one. (A first attempt here was a hand-written quote-state tracker; it could not
# model command substitution inside double quotes and produced both false
# positives and a false negative, which is a worse guard than none.) -S error
# keeps this to parse-level breakage and will not fail the build on style.
if command -v shellcheck >/dev/null 2>&1; then
  SC_FILES=""
  for f in "$REPO_ROOT"/.buildkite/scripts/steps/lib/perf-*.sh "$REPO_ROOT"/.buildkite/scripts/steps/perf-*.sh \
           "$PERF_DIR"/scripts/multi-process-sweep.sh "$PERF_DIR"/scripts/rw-multi-k6-sweep.sh; do
    [ -f "$f" ] && SC_FILES="$SC_FILES $f"
  done
  # shellcheck disable=SC2086
  shellcheck -S error $SC_FILES
  echo "  shellcheck -S error: clean across the perf shell libs"
else
  echo "  shellcheck absent on this agent — skipping (bash -n above still runs)" >&2
fi

echo "--- hardware-matrix fixture checks (item 27: lower-bound reasons, page fields, publish hold)"
bash "$REPO_ROOT/.buildkite/scripts/test/perf-hw-matrix-test.sh"

echo "--- rw-multi-k6 same-requests cross-check fixture checks (item 31: lib/perf-rw-cross-check.jq)"
bash "$REPO_ROOT/.buildkite/scripts/test/perf-rw-cross-check-test.sh"

echo "--- rw-multi-k6 interrupted-iteration fixture checks (item 31: lib/perf-k6-interrupted.sh)"
bash "$REPO_ROOT/.buildkite/scripts/test/perf-k6-interrupted-test.sh"

echo "--- rw-multi-k6 default k6 runtime fixture checks (item 31: lib/perf-k6-runtime.sh)"
bash "$REPO_ROOT/.buildkite/scripts/test/perf-k6-runtime-test.sh"

echo "--- perf-test-run start-up checks (diagnostics tiers, env guards, wait_ready states)"
bash "$REPO_ROOT/.buildkite/scripts/test/perf-run-ready-test.sh"

echo "--- perf-test-run upstream alias checks (containers reached by alias, DNS-label guard, seed evidence)"
bash "$REPO_ROOT/.buildkite/scripts/test/perf-upstream-alias-test.sh"

echo "--- INFO-arm event-log budget checks (item 40: shipped default on the INFO SUT, info_* baseline key)"
bash "$REPO_ROOT/.buildkite/scripts/test/perf-info-budget-test.sh"

echo "--- perf-xl dispatch checks (guard YAML with and without PERF_XL, PERF_RUN_ARM switch)"
bash "$REPO_ROOT/.buildkite/scripts/test/perf-xl-dispatch-test.sh"

echo "--- compare queue-history checks (a perf-xl run never enters or displaces the perf baseline window)"
bash "$REPO_ROOT/.buildkite/scripts/test/perf-compare-queue-history-test.sh"

echo "--- allocation-profile annotation checks (item 28: ceiling views, size cap, degrade notes)"
bash "$REPO_ROOT/.buildkite/scripts/test/perf-allocprofile-annotation-test.sh"

echo "--- byte-compiling the SSE fidelity reader (item 12)"
# The reader is pure-stdlib python3 run on the perf agent by perf-test-run.sh; a
# syntax error would only surface mid-run, so compile it here. Skip (do not fail)
# if python3 is absent on the lint agent — the run step guards on it too.
if command -v python3 >/dev/null 2>&1; then
  python3 -m py_compile "$PERF_DIR/k6/tools/sse-fidelity-reader.py"
  echo "python3 -m py_compile k6/tools/sse-fidelity-reader.py OK"
else
  echo "python3 absent — skipping reader byte-compile (run step guards on it)"
fi

echo "--- validating k6 scripts (k6 inspect)"
# The remote-write inspect runs under that arm's default runtime: gracefulStop 5s (the default gap),
# GOGC 400 and a GOMEMLIMIT in the MiB form the harness derives (Go aborts on a malformed one).
# The single-quoted -c body is expanded by the container's sh, not the host —
# $f must NOT expand here, so SC2016 is intentional.
# shellcheck disable=SC2016
exec "$SCRIPT_DIR/../run-in-docker.sh" \
  -i grafana/k6:1.7.1@sha256:4fd3a694926b064d3491d9b02b01cde886583c4931f1223816e3d9a7bdfa7e0f \
  --entrypoint sh \
  -w /build/mockserver-performance-test \
  -- -c 'set -e; for f in k6/smoke.js k6/load.js k6/stress.js k6/soak.js k6/regression.js k6/growth.js k6/sweep.js k6/forward.js k6/proxy.js k6/streaming.js k6/clustered_crossing.js k6/coverage.js; do echo "k6 inspect $f"; k6 inspect "$f" > /dev/null; done; echo "k6 inspect k6/sweep.js (remote-write multi-k6 mode)"; GOGC=400 GOMEMLIMIT=12288MiB k6 inspect -e K6_SWEEP_WINDOW_MODE=wallclock -e K6_SWEEP_LEAN_SUMMARY=true -e K6_SWEEP_VU_DIAGNOSTICS=false -e K6_SWEEP_QUIET=5s -e K6_SWEEP_MANAGE_SUT=false -e K6_SWEEP_GRACEFUL_STOP=5s -e K6_SWEEP_START_AT_MS=$(( $(date +%s) * 1000 + 60000 )) k6/sweep.js > /dev/null'
