#!/usr/bin/env bash
# Every perf fixture test is hermetic: it sources lib/perf-test-env.sh and scrubs the perf knobs a
# build sets before it reads anything, so an A/B build's environment cannot move a default it
# asserts. Static checks on every perf-*-test.sh, the helper itself, and four fixture tests run under
# two A/B builds' environments.
# Run: .buildkite/scripts/test/perf-test-env-test.sh   (PERF_TEST_ENV_LIB=<path> to test another copy)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
# shellcheck source=lib/perf-test-env.sh
. "$REPO_ROOT/.buildkite/scripts/test/lib/perf-test-env.sh"
perf_test_scrub_env PERF_TEST_ENV_LIB
LIB="${PERF_TEST_ENV_LIB:-$REPO_ROOT/.buildkite/scripts/test/lib/perf-test-env.sh}"
FAILS=0
ok()   { echo "  ok   $1"; }
bad()  { echo "  FAIL $1" >&2; FAILS=$((FAILS + 1)); }
check() { # name expected actual
  if [ "$2" = "$3" ]; then ok "$1"; else bad "$1: expected '$2', got '$3'"; fi
}

echo "--- 1. every perf-*-test.sh scrubs the environment right after REPO_ROOT"
for t in "$REPO_ROOT"/.buildkite/scripts/test/perf-*-test.sh; do
  got="$(awk '/^REPO_ROOT=/ { r = NR } r && NR == r + 2 && index($0, ". \"$REPO_ROOT/.buildkite/scripts/test/lib/perf-test-env.sh\"") { s = 1 }
              r && NR == r + 3 && /^perf_test_scrub_env( |$)/ && s { print "yes"; exit }' "$t")"
  check "$(basename "$t")" "yes" "${got:-no}"
done

echo "--- 2. the helper drops the knobs and keeps the named seams and BUILDKITE"
SCRUBBED="$(env PERF_RW_K6_VU_CEILING=2048 PERF_RW_K6_GOGC=off PERF_XL=true PERF_JVM_DIAGNOSTICS=gc PERF_INFO_ARM=false \
  PERF_COVERAGE=false K6_SWEEP_RATES=1 GOGC=off PUBLISH_MOVE_PCT=1 MOCKSERVER_IMAGE=x BUILDKITE_SOURCE=api \
  BUILDKITE=true PERF_MY_SEAM=keep PATH="$PATH" bash -c '. "$1"; perf_test_scrub_env PERF_MY_SEAM; env' _ "$LIB")"
check "no knob survives" "" "$(grep -E '^(PERF_RW_|PERF_XL|PERF_JVM|PERF_INFO|PERF_COVERAGE|K6_|GOGC|PUBLISH_|MOCKSERVER_|BUILDKITE_SOURCE)' <<<"$SCRUBBED" || true)"
check "the named seam and BUILDKITE survive" "BUILDKITE=true|PERF_MY_SEAM=keep" "$(grep -E '^(BUILDKITE|PERF_MY_SEAM)=' <<<"$SCRUBBED" | sort | paste -sd'|' -)"

echo "--- 3. fixture tests pass in CI (BUILDKITE=true) under the A/B builds' environments"
# Each A/B knob as a perf build sets it. Run in parallel to fit the lint step's timeout.
knob_of() { case "$1" in 605) echo PERF_RW_K6_GOGC=off ;; 606) echo PERF_RW_K6_VU_CEILING=2048 ;; esac; }
T="$(mktemp -d "${TMPDIR:-/tmp}/perf-test-env.XXXXXX")"
trap 'rm -rf "$T"' EXIT
pids=""
for build in 605 606; do
  for t in perf-cpu-topology-test.sh perf-tail-instrument-test.sh perf-k6-runtime-test.sh perf-xl-dispatch-test.sh; do
    ( rc=0
      env BUILDKITE=true PERF_XL=true PERF_JVM_DIAGNOSTICS=gc PERF_INFO_ARM=false PERF_COVERAGE=false BUILDKITE_SOURCE=api \
        BUILDKITE_MESSAGE="[perf-run] A/B" "$(knob_of "$build")" bash "$REPO_ROOT/.buildkite/scripts/test/$t" >"$T/$build-$t.log" 2>&1 || rc=$?
      echo "$rc" > "$T/$build-$t.rc" ) & pids="$pids $!"
  done
done
for p in $pids; do wait "$p" || true; done
for build in 605 606; do
  for t in perf-cpu-topology-test.sh perf-tail-instrument-test.sh perf-k6-runtime-test.sh perf-xl-dispatch-test.sh; do
    check "$t under build $build's env" "0" "$(cat "$T/$build-$t.rc" 2>/dev/null || echo missing)"
    [ "$(cat "$T/$build-$t.rc" 2>/dev/null)" = 0 ] || grep -m3 '^  FAIL' "$T/$build-$t.log" >&2 || true
  done
done

echo
if [ "$FAILS" -gt 0 ]; then echo ":x: $FAILS check(s) failed" >&2; exit 1; fi
echo "all perf test-environment checks passed"
