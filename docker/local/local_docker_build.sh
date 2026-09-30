#!/usr/bin/env bash

# Resolve the repository root and the current MockServer version so this script
# is portable across machines (no hardcoded home directory or version).
REPO_ROOT="$(git -C "$(cd "$(dirname "$0")" && pwd)" rev-parse --show-toplevel)"
VERSION="$(cd "${REPO_ROOT}/mockserver" && ./mvnw -q -o help:evaluate -Dexpression=project.version -DforceStdout 2>/dev/null)"
M2_REPO="${HOME}/.m2/repository"

# The published images ship the mockserver-netty-docker jar (installed by `./mvnw install`), not the
# mockserver-netty-no-dependencies library jar, whose relocated JNA jarprep refuses.
cp "${M2_REPO}/org/mock-server/mockserver-netty-docker/${VERSION}/mockserver-netty-docker-${VERSION}.jar" ./mockserver-netty-jar-with-dependencies.jar
CA_STATE="$("${REPO_ROOT}/docker/ensure-ca-bundle.sh" .)"
docker build --no-cache -t mockserver/mockserver:local-snapshot .
if [[ "${CA_STATE}" == "created" ]]; then
  rm -f ./ca-bundle.pem
fi
