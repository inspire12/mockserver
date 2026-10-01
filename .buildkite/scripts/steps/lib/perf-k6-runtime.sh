#!/usr/bin/env bash
# Default k6 runtime for scripts/rw-multi-k6-sweep.sh (performance programme item 31): each rung's
# gracefulStop and the Go GC limit of every measured k6 process. Defines functions only.
# Rationale: docs/code/performance-measurement.md, "k6 heap and GC".
# Tests: .buildkite/scripts/test/perf-k6-runtime-test.sh

# Share of the Docker host's memory the k6 processes may use between them, in percent. The rest is
# headroom for the SUT, Prometheus, the upstream, Docker and the OS (page cache included).
K6_GOMEMLIMIT_HOST_PCT=50

# k6_graceful_stop_default <gap_seconds>: the gap in whole seconds, so never above it, and at least
# 1 s (sweep.js's floor). At or below the gap, adjacent rungs' VU reservations do not overlap.
k6_graceful_stop_default() {
  local g="${1:-}"
  [[ "$g" =~ ^[0-9]+$ ]] || return 1
  [ "$g" -ge 1 ] || g=1
  echo "${g}s"
}

# k6_gomemlimit_default <docker_mem_bytes> <procs>: K6_GOMEMLIMIT_HOST_PCT of the Docker host's
# memory split evenly over the k6 processes that run at once, in whole MiB (rounded down).
# Fails (no output) on a memory size or process count that is not a positive whole number.
k6_gomemlimit_default() {
  local mem="${1:-}" n="${2:-}" mib
  [[ "$mem" =~ ^[1-9][0-9]{0,15}$ ]] && [[ "$n" =~ ^[1-9][0-9]{0,3}$ ]] || return 1
  mib=$(( mem * K6_GOMEMLIMIT_HOST_PCT / 100 / n / 1048576 ))
  [ "$mib" -ge 1 ] || return 1
  echo "${mib}MiB"
}

# k6_docker_mem_bytes: the memory of the host the k6 containers run on, as Docker sees it. Not the
# local machine's: under Docker Desktop that is a smaller VM, which is the limit that matters.
k6_docker_mem_bytes() {
  docker info --format '{{.MemTotal}}' 2>/dev/null | tr -d '[:space:]'
}
