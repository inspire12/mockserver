#!/usr/bin/env bash
# Run mockserver-netty's Docker-gated binary-proxying suite (a real PostgreSQL
# server behind MockServer) with a Docker socket, then fail closed if it skipped.
# The main build has no socket, so there the suite skips. Why this is its own
# step, and the Ryuk and -m 7g settings: see java-async-broker-test.sh.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

MODULE="mockserver-netty"
SUITE="PostgresThroughMockServerIntegrationTest"

source "$SCRIPT_DIR/../lib/test-images.sh"
source "$SCRIPT_DIR/../lib/ecr-pull-through-login.sh"

"$SCRIPT_DIR/../lib/pre-pull-images.sh" "$(test_image postgres)"

IMAGE_REGISTRY_ENV=()
if [[ -n "${MOCKSERVER_TEST_IMAGE_REGISTRY:-}" ]]; then
  IMAGE_REGISTRY_ENV=(-e "MOCKSERVER_TEST_IMAGE_REGISTRY=${MOCKSERVER_TEST_IMAGE_REGISTRY}")
fi

exec "$SCRIPT_DIR/../run-in-docker.sh" \
  -i mockserver/mockserver:maven \
  -w /build/mockserver \
  -m 7g \
  --cache maven \
  --docker-socket \
  -e TESTCONTAINERS_RYUK_CONTAINER_PRIVILEGED=false \
  "${IMAGE_REGISTRY_ENV[@]+"${IMAGE_REGISTRY_ENV[@]}"}" \
  -- bash -ec "
    ./mvnw -pl ${MODULE} -am install \
      -DskipTests -DskipITs -Djacoco.skip=true -Dmaven.javadoc.skip=true \
      -Dmaven.gitcommitid.skip=true -P '!build-ui' \
      --batch-mode --no-transfer-progress

    # Only this Failsafe class, no unit tests; check-netty-leaks still runs at verify.
    ./mvnw -pl ${MODULE} verify \
      -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=${SUITE} \
      -Djacoco.skip=true -Dmaven.javadoc.skip=true \
      -Dmaven.gitcommitid.skip=true -P '!build-ui' -DredirectTestOutputToFile=true \
      --batch-mode --no-transfer-progress

    /build/.buildkite/scripts/steps/assert-suite-ran.sh \
      'mockserver-netty/target/failsafe-reports/TEST-*PostgresThroughMockServerIntegrationTest.xml'
  "
