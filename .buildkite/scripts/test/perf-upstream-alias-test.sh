#!/usr/bin/env bash
# Docker-free checks that perf-test-run.sh reaches the upstream by its network alias, never by its
# container name (64 characters once the agent PID has 7 digits, which Docker's DNS cannot resolve),
# that the DNS-length guard is wired to the assembled hostname, and that a failed seed reports curl's
# exit code. The code under test is lifted from the real script, not copied.
# Run: .buildkite/scripts/test/perf-upstream-alias-test.sh   (PERF_RUN_SCRIPT=<path> to test another copy)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
F="${PERF_RUN_SCRIPT:-$REPO_ROOT/.buildkite/scripts/steps/perf-test-run.sh}"
LIB_DIR="$(dirname "$F")/lib"
FAILS=0
ok()   { echo "  ok   $1"; }
bad()  { echo "  FAIL $1" >&2; FAILS=$((FAILS + 1)); }
check() { # name expected actual
  if [ "$2" = "$3" ]; then ok "$1"; else bad "$1: expected '$2', got '$3'"; fi
}
WORK="$(mktemp -d "${TMPDIR:-/tmp}/perf-upstream-alias-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

extract() { # a top-level function's text, from `name() {` to its closing `}`
  awk -v n="$1" '$0 ~ "^"n"\\(\\) *\\{" {p=1; print; next} p {print} p && /^}/ {exit}' "$F"
}
GUARD_FN="$(extract require_dns_hostname)"
SEED_FN="$(extract seed_upstream)"
START_FN="$(extract start_mockserver)"
ALIAS_LINE="$(grep -E '^UPSTREAM_ALIAS=' "$F" | head -1)"
HOSTPORT_BLOCK="$(awk '/^SUT_PORT=1080$/ {p=1} p {print} p && /^require_dns_hostname "\$UPSTREAM_HOSTPORT"/ {exit}' "$F")"
SEED_CHECK="$(awk '/^UPSTREAM_SEED_RESULT="\$\(seed_upstream\)"$/ {p=1} p {print} p && /^  exit 1$/ {exit}' "$F")"
for v in GUARD_FN SEED_FN START_FN ALIAS_LINE HOSTPORT_BLOCK SEED_CHECK; do
  [ -n "${!v}" ] || bad "$v not found in $F"
done
# An awk range with no end match runs to EOF; executing that would run the rest of the script.
grep -qE '^require_dns_hostname "\$UPSTREAM_HOSTPORT"' <<<"$(tail -1 <<<"$HOSTPORT_BLOCK")" \
  || bad "the UPSTREAM_HOSTPORT block does not end at its require_dns_hostname guard"
[ "$(tail -1 <<<"$SEED_CHECK")" = "  exit 1" ] || bad "the seed check block does not end at its exit 1"
[ "$FAILS" -eq 0 ] || { echo "FAILED: $FAILS check(s)" >&2; exit 1; }

NAME63="$(printf 'a%.0s' $(seq 63))"
NAME64="${NAME63}a"
# The real failing name: build id (36) + a 7-digit PID.
CI_UPSTREAM="mockserver-upstream-0199a7c2-1b2e-4c3d-9e8f-0123456789ab-1234567"

echo "--- 1. require_dns_hostname: 63 characters resolve, 64 do not"
guard_rc() { local rc=0; bash -c "$GUARD_FN"$'\n''require_dns_hostname "$1"' _ "$1" >/dev/null 2>&1 || rc=$?; echo "$rc"; }
check "the CI upstream name is 64 characters" "64" "${#CI_UPSTREAM}"
check "63-character host passes" "0" "$(guard_rc "$NAME63")"
check "63-character host:port passes" "0" "$(guard_rc "$NAME63:1080")"
check "64-character host trips the guard" "1" "$(guard_rc "$NAME64")"
check "64-character host:port trips the guard" "1" "$(guard_rc "$NAME64:1080")"
check "the CI upstream name:1080 trips the guard" "1" "$(guard_rc "$CI_UPSTREAM:1080")"

echo "--- 2. UPSTREAM_HOSTPORT is built from the alias, and the guard checks the assembled value"
hostport() { # mode upstream_alias_override -> "rc hostport"
  local rc=0 out
  out="$(PERF_NETWORK_MODE="$1" UPSTREAM="$CI_UPSTREAM" SERVER_ALIAS=mockserver NETWORK=n bash -c "
    set -euo pipefail
    $GUARD_FN
    $ALIAS_LINE
    ${2:+UPSTREAM_ALIAS='$2'}
    $HOSTPORT_BLOCK
    printf '%s' \"\$UPSTREAM_HOSTPORT\"" 2>/dev/null)" || rc=$?
  echo "$rc ${out:-}"
}
check "bridge: the alias, not the 64-character container name" "0 mockserver-upstream:1080" "$(hostport bridge "")"
check "host: the host port" "0 127.0.0.1:1081" "$(hostport host "")"
check "bridge: a 64-character hostname exits before any container starts" "1" "$(hostport bridge "$NAME64" | cut -d' ' -f1)"

echo "--- 3. seed_upstream: alias URL, and -w carries curl's exit code and error"
SEED_ARGS="$(UPSTREAM_HOSTPORT=mockserver-upstream:1080 bash -c "
  NET_CLIENT=(--network n)
  docker() { printf '%s\n' \"\$@\"; }
  $SEED_FN
  seed_upstream")"
check "seed URL uses the alias" "1" "$(grep -cxF 'http://mockserver-upstream:1080/mockserver/expectation' <<<"$SEED_ARGS" || true)"
W_FMT="$(grep -A1 -xF -- '-w' <<<"$SEED_ARGS" | tail -1)"
check "seed -w format" "%{http_code} %{exitcode} %{errormsg}" "$W_FMT"

echo "--- 4. a failed seed prints curl's exit code and error; a 2xx passes"
seed_check() { # seed_upstream output -> "rc" and stderr in $WORK/seed.err
  local rc=0
  SEED_OUT="$1" bash -c "
    set -euo pipefail
    seed_upstream() { printf '%s' \"\$SEED_OUT\"; }
    sleep() { :; }
    print_container_postmortem() { :; }
    UPSTREAM=u
    $SEED_CHECK
    fi" >/dev/null 2>"$WORK/seed.err" || rc=$?
  echo "$rc"
}
check "201 with an empty errormsg passes" "0" "$(seed_check '201 0 ')"
check "DNS failure aborts the run" "1" "$(seed_check '000 6 Could not resolve host: x')"
check "the abort line carries the exit code and error" "1" \
  "$(grep -c "^ERROR: upstream seeding failed.*000 6 Could not resolve host: x" "$WORK/seed.err" || true)"
check "the retry warning carries the exit code and error" "1" \
  "$(grep -c "^WARNING: upstream seed failed.*000 6 Could not resolve host: x" "$WORK/seed.err" || true)"
check "a 2xx-looking number in the errormsg still aborts" "1" "$(seed_check '000 28 Operation timed out after 2001 milliseconds')"

echo "--- 5. start_mockserver refuses an over-long alias before docker run; the upstream uses the alias"
start_rc() { # mode alias -> "rc docker_calls network_alias"
  local rc=0
  PERF_NETWORK_MODE="$1" ALIAS="$2" bash -c "
    set -euo pipefail
    NETWORK=n MOCKSERVER_IMAGE=img PERF_MAX_EVENT_LOG_BYTES=1 SUT_IMAGE_JAVA_TOOL_OPTIONS=
    docker() { echo call >> '$WORK/docker.calls'; printf '%s\n' \"\$@\" > '$WORK/docker.args'; }
    cpuset_arg() { :; }; numa_mems_flag() { :; }; diag_jvm_opts() { :; }; compose_java_tool_options() { :; }
    $GUARD_FN
    $START_FN
    start_mockserver name 1 \"\$ALIAS\"" >/dev/null 2>&1 || rc=$?
  local calls=0; [ ! -f "$WORK/docker.calls" ] || calls="$(wc -l < "$WORK/docker.calls" | tr -d ' ')"
  local net_alias; net_alias="$(grep -A1 -xF -- '--network-alias' "$WORK/docker.args" 2>/dev/null | tail -1 || true)"
  rm -f "$WORK/docker.calls" "$WORK/docker.args"
  echo "$rc $calls ${net_alias:-}"
}
check "bridge: the upstream alias starts the container" "0 1 mockserver-upstream" "$(start_rc bridge mockserver-upstream)"
check "bridge: a 64-character alias is refused, docker never runs" "1 0" "$(start_rc bridge "$NAME64" | sed 's/ *$//')"
check "host: the alias is unused, so it is not checked" "0 1" "$(start_rc host "$NAME64" | sed 's/ *$//')"
check "the upstream is started under UPSTREAM_ALIAS" "1" \
  "$(grep -cE '^start_mockserver "\$UPSTREAM" "\$UPSTREAM_CPUS" "\$UPSTREAM_ALIAS" ' "$F" || true)"

echo "--- 6. PERF_WORKLOAD=forward reaches the upstream through UPSTREAM_HOSTPORT"
WL_BLOCK="$(awk '/^if \[ "\$PERF_WORKLOAD" = "forward" \] && \[ "\$PERF_NETWORK_MODE" != host \]/ {p=1} p {print} p && /^fi$/ {exit}' "$F")"
[ -n "$WL_BLOCK" ] || bad "PERF_WORKLOAD=forward block not found"
check "upstream reset URL" "1" "$(grep -cF '"http://${UPSTREAM_HOSTPORT}/mockserver/reset"' <<<"$WL_BLOCK" || true)"
check "upstream slow-seed URL" "1" "$(grep -cF '"http://${UPSTREAM_HOSTPORT}/mockserver/expectation"' <<<"$WL_BLOCK" || true)"

echo "--- 7. sweep: no container name is used as a hostname, in the script or its libs"
# Every variable assigned a "...${RUN_ID}" value names a container (or network); none may appear as a
# URL host, a host:port, or a *_HOST value. Clients use the --network-alias instead.
SOURCES=("$F" "$LIB_DIR"/*.sh)
NAMES="$(grep -hoE '^ *(local )?[A-Z][A-Z0-9_]*="[^"]*\$\{RUN_ID\}"' "${SOURCES[@]}" \
  | sed -E 's/^ *(local )?([A-Z0-9_]+)=.*/\2/' | sort -u)"
check "container-name variables found (UPSTREAM among them)" "1" "$(grep -cx UPSTREAM <<<"$NAMES" || true)"
for n in $NAMES; do
  hits="$(grep -nE "://\\\$\\{?$n\\}?([:/\"]|\$)|\\\$\\{?$n\\}?:[0-9]|_HOSTS?=[^ ]*\\\$\\{?$n\\b" "${SOURCES[@]}" \
    | grep -vE '^[^:]+:[0-9]+: *#' || true)"
  [ -z "$hits" ] || bad "container name \$$n used as a hostname: $hits"
done
FWD_HOSTS="$(grep -hoE 'FORWARD_UPSTREAM_HOST=[^" ]+' "${SOURCES[@]}")"
check "FORWARD_UPSTREAM_HOST values present" "true" "$([ -n "$FWD_HOSTS" ] && echo true || echo false)"
check "every FORWARD_UPSTREAM_HOST uses the alias or UPSTREAM_HOSTPORT" "" \
  "$(grep -vE '=\$(\{UPSTREAM_ALIAS\}:1080|UPSTREAM_HOSTPORT)$' <<<"$FWD_HOSTS" || true)"
ok "sweep complete over $(wc -w <<<"$NAMES" | tr -d ' ') container-name variables"

[ "$FAILS" -eq 0 ] || { echo "FAILED: $FAILS check(s)" >&2; exit 1; }
echo "perf-upstream-alias-test: all checks passed"
