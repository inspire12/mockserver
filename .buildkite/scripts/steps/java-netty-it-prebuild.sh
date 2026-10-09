#!/usr/bin/env bash
# Builds mockserver-netty and its upstream modules once, without tests, for the parallel
# ":maven: netty IT" shard steps, which download the archives this uploads instead of
# recompiling. See scripts/buildkite_netty_it.sh.
# -m 12g as java-build.sh: packaging mockserver-netty runs the build-ui profile (npm ci + vite)
# alongside a 6g Maven heap.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "$SCRIPT_DIR/../run-in-docker.sh" \
  -i mockserver/mockserver:maven \
  -m 12g \
  --cache maven \
  -- /build/scripts/buildkite_netty_it.sh prebuild
