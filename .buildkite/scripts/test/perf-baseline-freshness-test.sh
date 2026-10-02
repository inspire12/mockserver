#!/usr/bin/env bash
# Network-free checks for perf-baseline-freshness.sh: every verdict class, against a fake
# Buildkite API that honours per_page/page/created_from/created_to and emits Link headers
# the way the real one does (default per_page 30, newest first).
# Run: .buildkite/scripts/test/perf-baseline-freshness-test.sh
#      (PERF_FRESHNESS_SCRIPT=<path> to test another copy)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
# shellcheck source=lib/perf-test-env.sh
. "$REPO_ROOT/.buildkite/scripts/test/lib/perf-test-env.sh"
perf_test_scrub_env PERF_FRESHNESS_SCRIPT
F="${PERF_FRESHNESS_SCRIPT:-$REPO_ROOT/.buildkite/scripts/steps/perf-baseline-freshness.sh}"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/perf-freshness-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
# Failures go to a file, not a variable: each case's `run` sits at the end of a pipe,
# so it runs in a subshell whose variables never reach the final tally.
: > "$WORK/fails"
ok()  { echo "  ok   $1"; }
bad() { echo "  FAIL $1" >&2; echo "$1" >> "$WORK/fails"; }
BIN="$WORK/bin"
mkdir -p "$BIN"
TOKEN="fake-token-$$"
API_PREFIX="https://api.buildkite.com/v2/organizations/mockserver/pipelines/mockserver-performance-test/builds"

# A no-op buildkite-agent so a CI run of this test posts no annotations of its own.
printf '#!/usr/bin/env bash\ncat >/dev/null\n' > "$BIN/buildkite-agent"
cat > "$BIN/aws" <<'EOF'
#!/usr/bin/env bash
[ "${FAKE_NO_TOKEN:-}" = 1 ] && { echo "ExpiredToken: the SSO session has expired" >&2; exit 255; }
printf '%s\n' "$FAKE_TOKEN"
EOF
cat > "$BIN/curl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$FAKE_LOG"
cfg="" url="" hdr="" out="" params=()
while [ $# -gt 0 ]; do
  case "$1" in
    --config) cfg="$2"; shift 2 ;;
    --get) url="$2"; shift 2 ;;
    --data-urlencode) params+=("$2"); shift 2 ;;
    -D) hdr="$2"; shift 2 ;;
    -o) out="$2"; shift 2 ;;
    --max-time|--connect-timeout) shift 2 ;;
    *) shift ;;
  esac
done
calls=$(wc -l < "$FAKE_LOG" | tr -d ' ')
if [ "${FAKE_TRANSPORT_ON_CALL:-0}" = "$calls" ]; then echo "curl: (7) Failed to connect" >&2; exit 7; fi
emit() { if [ -n "$out" ]; then printf '%s' "$1" > "$out"; else printf '%s' "$1"; fi; }
[ -n "$hdr" ] && : > "$hdr"
if ! grep -qF "Authorization: Bearer $FAKE_TOKEN" "$cfg"; then emit '{"message":"Authentication required. Please supply a valid API Access Token"}'; exit 0; fi
case "${FAKE_MODE:-}" in
  denied) emit '{"message":"Authentication required. Please supply a valid API Access Token"}'; exit 0 ;;
  garbage) emit '<html>502 Bad Gateway</html>'; exit 0 ;;
esac
base="${url%%\?*}"
if [ "$url" != "$base" ]; then IFS='&' read -r -a q <<<"${url#*\?}"; params+=("${q[@]}"); fi
per_page=30 page=1 from="" to="" branch=""
for p in "${params[@]}"; do
  v="${p#*=}"; v="${v//%3A/:}"
  case "${p%%=*}" in
    per_page) per_page="$v" ;; page) page="$v" ;; branch) branch="$v" ;;
    created_from) from="$v" ;; created_to) to="$v" ;;
  esac
done
sel="$(jq -c --arg b "$branch" --arg from "$from" --arg to "$to" '
  def ep: (sub("\\.[0-9]+Z$"; "Z") | fromdateiso8601?) // 1e12;
  [ .[] | select(($b == "" or .branch == $b)
                 and ($from == "" or (.created_at | ep) >= ($from | ep))
                 and ($to == "" or (.created_at | ep) < ($to | ep))) ]
  | sort_by(.created_at | ep) | reverse' "$FAKE_BUILDS")"
total=$(jq 'length' <<<"$sel")
emit "$(jq -c --argjson p "$page" --argjson n "$per_page" '.[($p - 1) * $n : $p * $n]' <<<"$sel")"
if [ -n "$hdr" ] && [ $(( page * per_page )) -lt "$total" ]; then
  q="branch=${branch}${from:+&created_from=${from//:/%3A}}${to:+&created_to=${to//:/%3A}}&page=$(( page + 1 ))&per_page=${per_page}"
  next_base="${FAKE_NEXT_BASE:-$base}"
  last=$(( (total + per_page - 1) / per_page ))
  if [ "${FAKE_EMPTY_NEXT:-}" = 1 ]; then
    printf 'HTTP/2 200\r\nlink: <>; rel="next", <%s?page=%s>; rel="last"\r\n\r\n' "$base" "$last" > "$hdr"
  else
    printf 'HTTP/2 200\r\nlink: <%s?%s>; rel="next", <%s?page=%s>; rel="last"\r\n\r\n' "$next_base" "$q" "$base" "$last" > "$hdr"
  fi
fi
EOF
chmod +x "$BIN"/*

NOW="$(date -u +%s)"
# b(number, source, state, hours_ago[, message]) -> one build object
b() {
  jq -cn --argjson n "$1" --arg s "$2" --arg st "$3" --argjson t $(( NOW - $4 * 3600 - 60 )) --arg m "${5:-}" \
    '{number: $n, source: $s, state: $st, branch: "master", created_at: ($t | todate | sub("Z$"; ".123Z")),
      message: (if $m == "" then (if $s == "schedule" then "Scheduled: daily performance regression" else "[perf-run] manual" end) else $m end),
      web_url: "https://buildkite.com/mockserver/mockserver-performance-test/builds/\($n)"}'
}
# manual(count, start_number, hours_ago_first, step_minutes) -> manual api builds, one every step
manual() {
  jq -cn --argjson c "$1" --argjson s "$2" --argjson h "$3" --argjson st "$4" --argjson now "$NOW" \
    '[ range(0; $c) as $i | {number: ($s + $i), source: "api", state: "passed", branch: "master",
       created_at: (($now - $h * 3600 - $i * $st * 60) | todate | sub("Z$"; ".456Z")),
       message: "[perf-run] experiment \($i)"} ] | .[]'
}

# run(name, expected_rc, expected_substring, env...; builds on stdin) -> runs the real script
run() {
  local name="$1" want_rc="$2" want="$3" rc=0 out
  shift 3
  jq -s '.' > "$WORK/builds.json"
  : > "$WORK/curl.log"
  out="$(env -u PERF_PRODUCER_MAX_AGE_HOURS -u PERF_FRESHNESS_LOOKBACK_HOURS -u PERF_FRESHNESS_MAX_PAGES \
    PATH="$BIN:$PATH" FAKE_BUILDS="$WORK/builds.json" FAKE_LOG="$WORK/curl.log" FAKE_TOKEN="$TOKEN" \
    PERF_FRESHNESS_AWS_BIN="$BIN/aws" PERF_FRESHNESS_CURL_BIN="$BIN/curl" TMPDIR="$WORK" "$@" \
    bash "$F" 2>&1)" || rc=$?
  printf '%s\n' "$out" > "$WORK/last.out"
  if [ "$rc" -ne "$want_rc" ]; then
    bad "$name: exit $rc, expected $want_rc"; printf '%s\n' "$out" | tail -8 | sed 's/^/       | /' >&2
  elif ! grep -qF -- "$want" <<<"$out"; then
    bad "$name: output lacks '$want'"; printf '%s\n' "$out" | tail -8 | sed 's/^/       | /' >&2
  else
    ok "$name"
  fi
}
calls() { wc -l < "$WORK/curl.log" | tr -d ' '; }

echo "--- 1. PASS paths — including manual builds that outnumber any fixed page"
{ b 541 schedule passed 2; manual 5 600 1 5; } | run "fresh passing scheduled build" 0 "OK: producer live (newest scheduled #541"
for want in "--get $API_PREFIX" "per_page=100" "created_from=" "branch=master"; do
  grep -qF -- "$want" "$WORK/curl.log" && ok "  ... query carries '$want'" || bad "query lacks '$want'"
done
{ b 541 schedule passed 12; manual 35 600 0 15; b 517 schedule passed 36; } \
  | run "35 manual builds newer than a passing scheduled build" 0 "OK: producer live (newest scheduled #541"
{ b 541 schedule passed 12; manual 250 600 0 2; b 517 schedule passed 36; } \
  | run "250 manual builds: pages through the window" 0 "last completed #541 passed"
[ "$(calls)" -eq 3 ] && ok "  ... read exactly 3 pages of 100" || bad "250 manual builds: expected 3 API calls, got $(calls)"
{ b 541 schedule running 1; b 517 schedule passed 25; manual 10 600 0 5; } \
  | run "in-flight newest, previous passed" 0 "last completed #517 passed"
if grep -qF "$TOKEN" "$WORK/curl.log"; then bad "token appeared in curl argv"; else ok "token never in curl argv"; fi

echo "--- 2. STALLED / NO_SCHEDULE — told apart, with accurate ages"
{ b 500 schedule passed 40; manual 20 600 0 30; } | run "newest scheduled inside the lookback but past the window" 1 "STALLED (no scheduled build within 30h)"
grep -qF "is **40h old**" "$WORK/last.out" && ok "  ... reports its real age" || bad "STALLED in-window: age not 40h"
{ b 400 schedule passed 100; manual 60 600 0 60; } | run "newest scheduled older than the lookback" 1 "**100h old**"
{ manual 30 600 0 60; manual 30 700 60 60; } | run "no scheduled build anywhere in history" 1 "NO_SCHEDULE (producer has no scheduled builds)"
{ manual 20 600 0 60; manual 150 700 60 10; b 300 schedule passed 200; } \
  | run "none in the lookback, page cap reached in older history" 1 "STALLED (no scheduled build within 54h)" PERF_FRESHNESS_MAX_PAGES=1
if grep -qF NO_SCHEDULE "$WORK/last.out"; then bad "capped older scan claimed NO_SCHEDULE"; else ok "  ... and does not claim NO_SCHEDULE"; fi
{ b 541 schedule running 1; manual 10 600 0 60; } | run "scheduled builds exist but none ever finished" 1 "producer wedged"
{ b 541 schedule running 1; b 470 schedule passed 70; manual 40 600 0 30; } \
  | run "in-flight newest, last finished older than the lookback" 1 "STALLED (no scheduled build has completed in 54h — producer wedged)"
{ b 590 schedule scheduled 12; b 589 schedule scheduled 36; b 588 schedule passed 60; manual 30 600 0 60; } \
  | run "two queued dailies and an older pass" 1 "STALLED (no scheduled build has completed in 54h — producer wedged)"
if grep -q 'created_to=' "$WORK/curl.log"; then bad "wedged: reached back past the lookback for a finished build"; else ok "  ... without reaching back past the lookback"; fi

echo "--- 3. NOT_PASSED"
{ b 541 schedule failed 3; b 517 schedule passed 27; } | run "most recent scheduled build failed" 1 "NOT_PASSED (last completed scheduled run was 'failed')"
{ b 541 schedule running 1; b 517 schedule canceled 25; manual 40 600 0 5; } | run "in-flight newest, previous canceled" 1 "was 'canceled'"

echo "--- 4. [perf-soak] is not the daily producer"
{ b 545 schedule passed 2 "[perf-soak] Scheduled: weekly soak"; b 541 schedule failed 8; } \
  | run "a passing soak does not mask a failed daily" 1 "NOT_PASSED"
{ b 545 schedule passed 2 "[perf-soak] Scheduled: weekly soak"; b 500 schedule passed 40; } \
  | run "a fresh soak does not mask a stalled daily" 1 "STALLED (no scheduled build within 30h)"
{ b 545 schedule passed 2 "[perf-soak] Scheduled: weekly soak"; b 520 schedule passed 100 "[perf-soak] Scheduled: weekly soak"; } \
  | run "only soak builds ever: no daily schedule" 1 "NO_SCHEDULE"

echo "--- 5. API and config errors fail closed"
b 541 schedule passed 2 | run "no token" 1 "DENIED (no Buildkite API token)" FAKE_NO_TOKEN=1
b 541 schedule passed 2 | run "token rejected" 1 "DENIED (Buildkite API rejected the token)" FAKE_MODE=denied
b 541 schedule passed 2 | run "unusable body" 1 "TRANSPORT (unusable Buildkite API response)" FAKE_MODE=garbage
b 541 schedule passed 2 | run "unreachable API" 1 "TRANSPORT (Buildkite API unreachable)" FAKE_TRANSPORT_ON_CALL=1
{ manual 150 600 0 2; b 541 schedule passed 12; } | run "transport failure on page 2" 1 "TRANSPORT (Buildkite API unreachable)" FAKE_TRANSPORT_ON_CALL=2
{ manual 150 600 0 2; b 541 schedule passed 12; } \
  | run "next link to another host is not followed" 1 "TRANSPORT (unexpected pagination link)" FAKE_NEXT_BASE="https://evil.example/builds"
[ "$(calls)" -eq 1 ] && ok "  ... and the token was sent nowhere else" || bad "foreign next link: $(calls) calls"
for nb in "https://api.buildkite.com.evil/v2/organizations/mockserver/pipelines/mockserver-performance-test/builds" "http://api.buildkite.com/v2/organizations/mockserver/pipelines/mockserver-performance-test/builds"; do
  { manual 150 600 0 2; b 541 schedule passed 12; } \
    | run "next link $nb is not followed" 1 "TRANSPORT (unexpected pagination link)" FAKE_NEXT_BASE="$nb"
  [ "$(calls)" -eq 1 ] && ok "  ... one call only" || bad "next link $nb: $(calls) calls"
done
{ manual 150 600 0 2; b 541 schedule passed 12; } \
  | run "an empty next link is not read as the last page" 1 "TRANSPORT (unexpected pagination link)" FAKE_EMPTY_NEXT=1
b 541 schedule passed 2 | jq -c '.created_at = "not-a-timestamp"' \
  | run "unparseable created_at" 1 "TRANSPORT (unparseable build timestamp)"
{ manual 150 600 0 2; b 541 schedule passed 12; } | run "page cap inside the window" 1 "TRUNCATED (page cap reached inside the lookback window)" PERF_FRESHNESS_MAX_PAGES=1
{ b 541 schedule running 1; manual 150 600 0 2; b 517 schedule passed 26; } \
  | run "page cap before a finished build in the window" 1 "TRUNCATED" PERF_FRESHNESS_MAX_PAGES=1
b 541 schedule passed 2 | run "lookback not beyond the window" 1 "CONFIG (lookback not longer" PERF_FRESHNESS_LOOKBACK_HOURS=30
b 541 schedule passed 2 | run "malformed page cap" 1 "CONFIG (MAX_PAGES is not a positive integer)" PERF_FRESHNESS_MAX_PAGES=0
for bad_age in abc 1.5; do
  b 541 schedule passed 2 | run "liveness window '$bad_age'" 1 "CONFIG (PRODUCER_MAX_AGE_HOURS is not a positive integer)" PERF_PRODUCER_MAX_AGE_HOURS="$bad_age"
done
b 541 schedule passed 2 | run "malformed lookback" 1 "CONFIG (LOOKBACK_HOURS is not a positive integer)" PERF_FRESHNESS_LOOKBACK_HOURS=2d

FAILS=$(wc -l < "$WORK/fails" | tr -d ' ')
[ "$FAILS" -eq 0 ] || { echo "FAILED: $FAILS check(s)" >&2; exit 1; }
echo "OK: all perf-baseline-freshness checks passed"
