#!/usr/bin/env bash
#
# mockserver-netty integration tests, sharded across parallel Buildkite steps.
# Runs inside the mockserver/mockserver:maven container (repo mounted at /build).
#
#   buildkite_netty_it.sh prebuild
#       Builds mockserver-netty and its upstream modules once, without tests, and packs
#       what the shards need into mockserver/target/netty-it/ (uploaded as artifacts):
#         m2.tar.gz            the upstream SNAPSHOT artifacts this build installed
#         netty-target.tar     mockserver-netty's compiled classes, test classes and fat jars
#
#   buildkite_netty_it.sh shard <proxy-http|mock|rest>
#       Unpacks those archives and runs only failsafe for profile netty-it-shard-<shard>,
#       with the same leak gate (clean/print/check-netty-leaks) and CI arguments as the
#       reactor build, without recompiling. Which tests each shard runs is defined in
#       mockserver-netty/pom.xml.
#
# Both modes install into, and resolve first from, an empty local repository under
# target/netty-it/repo, chained to the usual one (maven.repo.local.tail) for everything
# else, so the archive holds exactly what this build installed. MAVEN_REPO_LOCAL overrides
# the usual one (default ~/.m2/repository, which CI mounts from the restored cache). Maven
# also reads extra arguments from MAVEN_ARGS.

set -euo pipefail

MODE="${1:-}"
cd "$(dirname "$0")/../mockserver"

M2="${MAVEN_REPO_LOCAL:-${HOME}/.m2/repository}"
OUT_DIR="target/netty-it"
HEAD_REPO="$PWD/$OUT_DIR/repo"
REPOS=(-Dmaven.repo.local="$HEAD_REPO" -Dmaven.repo.local.tail="$M2")
VERSION="$(sed -n '/<parent>/,/<\/parent>/s/.*<version>\(.*\)<\/version>.*/\1/p' mockserver-netty/pom.xml)"
if [ -z "$VERSION" ]; then
    echo "+++ :bangbang: could not read the project version from mockserver-netty/pom.xml" >&2
    exit 1
fi

log() {
    echo "[$(date -u +"%Y-%m-%d %H:%M:%S UTC")] $*"
}

prebuild() {
    export MAVEN_OPTS="${MAVEN_OPTS:-} -Xms2048m -Xmx6144m"
    rm -rf "$HEAD_REPO"
    mkdir -p "$HEAD_REPO" "$M2"
    # Same profiles as the reactor build: build-ui is file-activated, so the dashboard the
    # dashboard tests serve is in target/classes.
    ./mvnw -B --no-transfer-progress "${REPOS[@]}" -T 1C install -pl mockserver-netty -am \
        -DskipTests -DskipITs -Dinvoker.skip=true

    # The shards take mockserver-netty itself from target/, so its installed jars stay behind.
    (cd "$HEAD_REPO" && find org/mock-server -type f -path "*/${VERSION}/*" \
        ! -path 'org/mock-server/mockserver-netty/*' ! -name '*-sources.jar' ! -name '*-javadoc.jar') \
        > "$OUT_DIR/m2-files.txt"
    if ! grep -q "^org/mock-server/mockserver-core/${VERSION}/mockserver-core-${VERSION}.jar$" "$OUT_DIR/m2-files.txt"; then
        echo "+++ :bangbang: the prebuild installed no mockserver-core ${VERSION} jar into ${HEAD_REPO}" >&2
        exit 1
    fi
    tar -czf "$OUT_DIR/m2.tar.gz" -C "$HEAD_REPO" -T "$OUT_DIR/m2-files.txt"

    # Some integration tests boot the assembled fat jars (default and -http3) from target/.
    # Not gzipped: the jars are already compressed.
    local fat_jars=(mockserver-netty/target/mockserver-netty-"${VERSION}"-jar-with-dependencies*.jar)
    if [ ! -f "mockserver-netty/target/mockserver-netty-${VERSION}-jar-with-dependencies.jar" ]; then
        echo "+++ :bangbang: mockserver-netty's jar-with-dependencies was not built" >&2
        exit 1
    fi
    tar -cf "$OUT_DIR/netty-target.tar" \
        mockserver-netty/target/classes mockserver-netty/target/test-classes "${fat_jars[@]}"
    log "packed $(wc -l < "$OUT_DIR/m2-files.txt") SNAPSHOT files: $(du -h "$OUT_DIR/m2.tar.gz" | cut -f1) + $(du -h "$OUT_DIR/netty-target.tar" | cut -f1)"
}

shard() {
    local name="$1"
    case "$name" in
        proxy-http|mock|rest) ;;
        *) echo "usage: buildkite_netty_it.sh shard <proxy-http|mock|rest>" >&2; exit 2 ;;
    esac
    for archive in m2.tar.gz netty-target.tar; do
        [ -f "$OUT_DIR/$archive" ] || { echo "+++ :bangbang: $OUT_DIR/$archive missing: download it from the prebuild step first" >&2; exit 1; }
    done
    rm -rf "$HEAD_REPO"
    mkdir -p "$HEAD_REPO" "$M2"
    tar -xzf "$OUT_DIR/m2.tar.gz" -C "$HEAD_REPO"
    tar -xf "$OUT_DIR/netty-target.tar"

    export MAVEN_OPTS="${MAVEN_OPTS:-} -Xmx2048m"
    # Lifecycle order of the integration-test bindings, invoked directly so nothing is
    # recompiled. -nsu: the upstream SNAPSHOTs must be the ones just unpacked into HEAD_REPO.
    local rc=0
    ./mvnw -B --no-transfer-progress "${REPOS[@]}" -nsu -pl mockserver-netty -P "netty-it-shard-${name}" \
        clean:clean@delete-stale-failsafe-summary \
        jacoco:prepare-agent-integration \
        antrun:run@clean-netty-leaks \
        failsafe:integration-test \
        antrun:run@print-netty-leaks \
        failsafe:verify \
        antrun:run@check-netty-leaks \
        -Dmockserver.testOutput=quiet -DredirectTestOutputToFile=true -Dmockserver.testLogLevel=INFO \
        "-Dmockserver.testArgLine=-Dmockserver.maxLogEntries=10000 -Dmockserver.maxExpectations=5000" \
        || rc=$?

    # Per-shard name so the three shards' coverage data can be downloaded side by side and merged.
    if [ -f mockserver-netty/target/jacoco-it.exec ]; then
        cp mockserver-netty/target/jacoco-it.exec "mockserver-netty/target/jacoco-it-${name}.exec"
    fi
    return "$rc"
}

case "$MODE" in
    prebuild) prebuild ;;
    shard) shard "${2:-}" ;;
    *) echo "usage: buildkite_netty_it.sh prebuild | shard <proxy-http|mock|rest>" >&2; exit 2 ;;
esac
