#!/usr/bin/env bash

set -e

export MAVEN_OPTS="$MAVEN_OPTS -Xms2048m -Xmx8192m"
export JAVA_OPTS="$JAVA_OPTS -Xms2048m -Xmx8192m"
export JAVA_HOME=`/usr/libexec/java_home -v 17`

cd mockserver

echo
java -version
echo
./mvnw -version
echo

# to run from specific test use argument in quotes "ExpectationFileWatcherIntegrationTest" or "ExpectationFileWatcherIntegrationTest#shouldDetectModifiedInitialiserJsonOnAdd"
# every module runs with the same -Dtest / -Dit.test, so a module where they match nothing must not fail the build
mkdir -p ../.tmp
marker=$(mktemp ../.tmp/local_single_test.XXXXXX)
trap 'rm -f "$marker"' EXIT
./mvnw -T 1C -Dtest="none" -Dit.test="$1" clean install \
  -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false -Dmockserver.failIfNoIntegrationTests=false

# require a Failsafe report from this run; -newer because `clean` misses dirs outside the reactor
if [ -z "$(find . -path '*/target/failsafe-reports/TEST-*.xml' -newer "$marker" -print -quit)" ]; then
  echo "No integration test matched '$1' in any module" >&2
  exit 1
fi
