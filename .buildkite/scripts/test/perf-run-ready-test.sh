#!/usr/bin/env bash
# Docker-free checks for perf-test-run.sh's start-up path: the JVM diagnostics tiers, the env guards
# that run before any container starts, and wait_ready against a stub `docker` for every container
# state it must tell apart. The functions are lifted from the real script, not copied.
# Run: .buildkite/scripts/test/perf-run-ready-test.sh   (PERF_RUN_SCRIPT=<path> to test another copy)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
F="${PERF_RUN_SCRIPT:-$REPO_ROOT/.buildkite/scripts/steps/perf-test-run.sh}"
FAILS=0
ok()   { echo "  ok   $1"; }
bad()  { echo "  FAIL $1" >&2; FAILS=$((FAILS + 1)); }
check() { # name expected actual
  if [ "$2" = "$3" ]; then ok "$1"; else bad "$1: expected '$2', got '$3'"; fi
}
WORK="$(mktemp -d "${TMPDIR:-/tmp}/perf-run-ready-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

# A top-level function's text, from `name() {` to its closing `}` (one-liners included).
extract() {
  awk -v n="$1" '$0 ~ "^"n"\\(\\) *\\{" {p=1; print; if ($0 ~ /}$/) exit; next} p {print} p && /^}/ {exit}' "$F"
}
for fn in tier1_jvm_opts gc_log_jvm_opts tier2_jvm_opts diag_jvm_opts print_container_postmortem wait_ready; do
  body="$(extract "$fn")"
  if [ -z "$body" ]; then bad "function $fn not found in $F"; continue; fi
  eval "$body"
done
[ "$FAILS" -eq 0 ] || { echo "FAILED: $FAILS check(s)" >&2; exit 1; }

echo "--- 1. diag_jvm_opts per tier (standard and deep must not drift: they set the baseline's JVM)"
T1='-XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/diag/sut/heapdump.hprof'
GC='-Xlog:gc*,gc+heap=info:file=/diag/sut/gc-%p.log:time,uptime,level,tags:filecount=5,filesize=20m'
T2='-XX:NativeMemoryTracking=summary -XX:+UnlockDiagnosticVMOptions -XX:+PrintNMTStatistics -XX:StartFlightRecording=name=perfdiag,settings=profile,maxsize=256m,jdk.JavaMonitorEnter#threshold=1ms,jdk.CPUTimeSample#enabled=true -XX:FlightRecorderOptions=repository=/diag/sut/jfr-repo,stackdepth=128'
check "standard = tier 1 only" "$T1" "$(PERF_JVM_DIAGNOSTICS=standard diag_jvm_opts sut)"
check "gc = tier 1 + GC file log" "$T1 $GC" "$(PERF_JVM_DIAGNOSTICS=gc diag_jvm_opts sut)"
check "deep = tier 1 + tier 2" "$T1 $GC $T2" "$(PERF_JVM_DIAGNOSTICS=deep diag_jvm_opts sut)"
check "gc log follows the subdir" "-Xlog:gc*,gc+heap=info:file=/diag/info/gc-%p.log:time,uptime,level,tags:filecount=5,filesize=20m" \
  "$(gc_log_jvm_opts info)"

echo "--- 2. env guards: tier allow-list and the /diag/ refusal, before any temp dir or container"
GUARDS="$(awk '/^# JVM diagnostics tier/ {p=1} p {print} p && /^fi$/ {exit}' "$F")"
if [ -z "$GUARDS" ]; then
  bad "guard block (from '# JVM diagnostics tier' to its 'fi') not found"
else
  guard_rc() { # PERF_JVM_DIAGNOSTICS PERF_SERVER_JAVA_OPTS -> exit code of the guard block
    local rc=0
    env -u PERF_JVM_DIAGNOSTICS -u PERF_SERVER_JAVA_OPTS ${1:+PERF_JVM_DIAGNOSTICS="$1"} ${2:+PERF_SERVER_JAVA_OPTS="$2"} \
      bash -c "set -euo pipefail; $GUARDS" >/dev/null 2>&1 || rc=$?
    echo "$rc"
  }
  check "unset tier defaults to standard and passes" "0" "$(guard_rc "" "")"
  for t in standard gc deep; do check "tier $t accepted" "0" "$(guard_rc "$t" "")"; done
  for t in bogus Deep "gc " full; do check "tier '$t' refused" "1" "$(guard_rc "$t" "")"; done
  check "a heap/GC option in PERF_SERVER_JAVA_OPTS passes" "0" "$(guard_rc "" "-XX:+UseZGC -Xmx2g")"
  check "the stdout GC-log form passes" "0" "$(guard_rc "" "-Xlog:async -Xlog:gc*:stdout:time,uptime,level,tags")"
  check "a /diag/ file target is refused (build 542)" "1" "$(guard_rc "" "-Xlog:gc*:file=/diag/sut/gc-%p.log")"
fi
GUARD_LINE="$(grep -n '^# JVM diagnostics tier' "$F" | head -1 | cut -d: -f1)"
OUT_LINE="$(grep -n '^OUT_DIR="$(mktemp' "$F" | head -1 | cut -d: -f1)"
check "guards run before the OUT_DIR mktemp" "true" \
  "$([ -n "$GUARD_LINE" ] && [ -n "$OUT_LINE" ] && [ "$GUARD_LINE" -lt "$OUT_LINE" ] && echo true || echo false)"

echo "--- 3. wait_ready against a stub docker"
# STUB_STATES: one "<status> <health>" per poll, the last repeating; "missing" mimics docker 29,
# which prints an empty line to stdout and exits 1 for an unknown container.
POLLS="$WORK/polls"
docker() {
  case "$1" in
    inspect)
      if [[ "$*" == *"{{json .State}}"* ]]; then printf '{"Status":"stub","ExitCode":1}\n'; return 0; fi
      local n; n=$(( $(cat "$POLLS") + 1 )); echo "$n" > "$POLLS"
      local s; s="$(sed -n "${n}p" <<<"$STUB_STATES")"; [ -n "$s" ] || s="$(tail -1 <<<"$STUB_STATES")"
      if [ "$s" = missing ]; then echo ""; return 1; fi
      echo "$s" ;;
    logs) echo "stub-jvm-log-line" ;;
  esac
}
sleep() { :; }
ready() { # name  expected_rc  expected_polls  stderr_must_contain  states...
  local name="$1" want_rc="$2" want_polls="$3" want_msg="$4" rc=0; shift 4
  STUB_STATES="$(printf '%s\n' "$@")"; echo 0 > "$POLLS"
  wait_ready "c" 2>"$WORK/err" || rc=$?
  check "$name: rc" "$want_rc" "$rc"
  check "$name: polls" "$want_polls" "$(cat "$POLLS")"
  if [ -n "$want_msg" ]; then
    if grep -qF -- "$want_msg" "$WORK/err"; then ok "$name: says '$want_msg'"; else bad "$name: stderr lacks '$want_msg': $(head -3 "$WORK/err")"; fi
  fi
}
ready "healthy"                       0 1  ""                            "running healthy"
ready "no healthcheck"                0 1  ""                            "running nohealth"
ready "starting then healthy"         0 3  ""                            "running starting" "running starting" "running healthy"
ready "missing (docker 29 empty line)" 1 1 "exited early"                "missing"
ready "exited (kept, build 542)"      1 1  "is exited before"            "exited unhealthy"
ready "exited, prints its log"        1 1  "stub-jvm-log-line"           "exited unhealthy"
ready "dead"                          1 1  "is dead before"              "dead nohealth"
ready "removing (--rm dying)"         1 1  "is removing before"          "removing unhealthy"
ready "created (run -d never started)" 1 1 "is created before"           "created nohealth"
ready "dies after starting"           1 2  "is exited before"            "running starting" "exited unhealthy"
ready "unhealthy, not running"        1 1  "unhealthy and not running"   "paused unhealthy"
ready "running unhealthy keeps polling" 1 60 "did not become ready"      "running unhealthy"
ready "never ready: post-mortem"      1 60 "stub-jvm-log-line"           "running starting"

if [ "$FAILS" -gt 0 ]; then echo "FAILED: $FAILS check(s)" >&2; exit 1; fi
echo "all perf-run start-up checks passed"
