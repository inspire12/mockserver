#!/usr/bin/env bash
#
# Fail the build unless the shaded jar's direct-memory limit behaves as documented: MockServer's default
# applies when nothing is set, and a user's -Dio.netty.maxDirectMemory or -XX:MaxDirectMemorySize wins.
# Shade relocates Netty and rewrites "io.netty..." string literals, so relocated Netty reads
# shaded_package.io.netty.maxDirectMemory; only a run of the built jar shows which name takes effect.
#
set -euo pipefail

JAR="${1:?usage: assert-shaded-direct-memory-property.sh <shaded-jar> <java>}"
JAVA="${2:?usage: assert-shaded-direct-memory-property.sh <shaded-jar> <java>}"
[ -f "$JAR" ] || { echo "ERROR: shaded jar not found: $JAR" >&2; exit 1; }

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT
cat > "$WORK_DIR/DirectMemoryProbe.java" <<'JAVA'
public class DirectMemoryProbe {
    public static void main(String[] arguments) throws Exception {
        Class.forName("org.mockserver.cli.Main");
        Class<?> platformDependent = Class.forName("shaded_package.io.netty.util.internal.PlatformDependent");
        System.out.println("limit=" + platformDependent.getMethod("maxDirectMemory").invoke(null));
    }
}
JAVA

FAILED=0
check() {
  local expected="$1" description="$2"; shift 2
  local output actual
  output="$(env -u JAVA_TOOL_OPTIONS -u JDK_JAVA_OPTIONS -u _JAVA_OPTIONS \
    "$JAVA" -XX:+UseG1GC -Xmx512m "$@" -cp "$JAR" "$WORK_DIR/DirectMemoryProbe.java" 2>&1 || true)"
  actual="$(sed -n 's/^limit=//p' <<<"$output" | tail -n 1)"
  if [ "$actual" != "$expected" ]; then
    echo "ERROR: $(basename "$JAR") $description: expected limit=$expected, got '${actual:-no limit line}'. Probe output:" >&2
    printf '%s\n' "$output" >&2
    FAILED=1
  fi
}

check 134217728 "default (a quarter of a 512 MiB heap)"
check 33554432 "-Dio.netty.maxDirectMemory=33554432" -Dio.netty.maxDirectMemory=33554432
check 104857600 "-XX:MaxDirectMemorySize=100m" -XX:MaxDirectMemorySize=100m

if [ "$FAILED" -ne 0 ]; then
  echo "  See org.mockserver.socket.NettyDirectMemoryLimit." >&2
  exit 1
fi
echo "OK: $(basename "$JAR") applies the default direct-memory limit and honours both user overrides."
