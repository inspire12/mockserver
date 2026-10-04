#!/usr/bin/env bash
# Sourced first by every .buildkite/scripts/test/perf-*-test.sh. A perf build's environment reaches
# its lint step, so an A/B knob set on the build (PERF_RW_K6_VU_CEILING=2048, PERF_RW_K6_GOGC=off)
# would change the defaults the fixtures assert. perf_test_scrub_env <name...> unsets every knob
# the perf scripts read, except the names given (the test's own seams). BUILDKITE stays: tests read
# it to fail closed in CI. Checked by .buildkite/scripts/test/perf-test-env-test.sh.
perf_test_scrub_env() {
  local keep=" $* " v
  for v in $(compgen -e); do
    case "$v" in
      PERF_*|K6_*|PUBLISH_*|MOCKSERVER_*|JMH_*|H2_BENCH_*|H2_MEM_*|SWEEP_*) ;;
      GOGC|GOMEMLIMIT|GODEBUG|MAVEN_IMAGE|MAVEN_MEMORY|LOOKBACK_HOURS|ENVOY_IMAGE|LAPTOP_JAR) ;;
      BUILDKITE_MESSAGE|BUILDKITE_SOURCE) ;;
      *) continue ;;
    esac
    case "$keep" in *" $v "*) continue ;; esac
    unset "$v"
  done
}
