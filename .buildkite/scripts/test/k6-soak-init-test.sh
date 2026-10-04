#!/bin/sh
# Which K6_SOAK_* durations soak.js accepts and which it refuses at init, through `k6 inspect` (no
# load, no server). POSIX sh: perf-test-lint.sh runs it inside the k6 container, so it cannot source
# lib/perf-test-env.sh; the build's environment is kept out with --include-system-env-vars=false.
# Locally: K6_BIN=/path/to/k6 .buildkite/scripts/test/k6-soak-init-test.sh
set -eu

K6="${K6_BIN:-k6}"
SOAK_JS="${PERF_SOAK_JS:-$(cd "$(dirname "$0")/../../../mockserver-performance-test/k6" && pwd)/soak.js}"
command -v "$K6" >/dev/null 2>&1 || { echo "ERROR: no k6 binary ('$K6'): set K6_BIN" >&2; exit 2; }
FAILS=0
bad() { echo "  FAIL $1" >&2; FAILS=$((FAILS + 1)); }

# accepts <name> <match-arm windows> [-e VAR=value...]
accepts() {
  name="$1"; windows="$2"; shift 2
  if ! out="$("$K6" inspect --include-system-env-vars=false "$@" "$SOAK_JS" 2>&1)"; then
    bad "$name: refused: $(printf '%s\n' "$out" | grep -o 'msg="[^"]*' | head -1)"; return 0
  fi
  got="$(printf '%s\n' "$out" | grep -c '"http_req_duration{op:match,win:w' || true)"
  if [ "$got" = "$windows" ]; then echo "  ok   $name"; else bad "$name: expected $windows windows, got $got"; fi
}
# refuses <name> <message> [-e VAR=value...]
refuses() {
  name="$1"; message="$2"; shift 2
  if out="$("$K6" inspect --include-system-env-vars=false "$@" "$SOAK_JS" 2>&1)"; then bad "$name: accepted"; return 0; fi
  case "$out" in
    *"$message"*) echo "  ok   $name" ;;
    *) bad "$name: refused without '$message'" ;;
  esac
}

echo "--- soak.js: durations it reads"
accepts "the defaults (30m in 5m windows)" 6
accepts "the weekly run (2h, 5m windows, 15m warm-up, 6 windows for drift)" 24 \
  -e K6_SOAK_DURATION=2h -e K6_SOAK_WINDOW=5m -e K6_SOAK_WARMUP=15m -e K6_SOAK_DRIFT_WINDOWS=6
accepts "hours and minutes together: 1h30m is 90 minutes" 3 -e K6_SOAK_DURATION=1h30m -e K6_SOAK_WINDOW=30m
accepts "days and hours together: 1d12h is 36 hours" 36 -e K6_SOAK_DURATION=1d12h -e K6_SOAK_WINDOW=1h
accepts "a fraction and milliseconds: 1.5m in 30000ms windows" 3 -e K6_SOAK_DURATION=1.5m -e K6_SOAK_WINDOW=30000ms -e K6_SOAK_WARMUP=0s
accepts "exactly the windows drift needs after the warm-up" 9 -e K6_SOAK_DURATION=45m -e K6_SOAK_DRIFT_WINDOWS=6

echo "--- soak.js: what it refuses"
refuses "a unit k6 does not have" "K6_SOAK_DURATION=15min is not a duration" -e K6_SOAK_DURATION=15min
refuses "a warm-up that does not parse is not read as no warm-up" "K6_SOAK_WARMUP=15min is not a duration" -e K6_SOAK_WARMUP=15min
refuses "a window with a trailing bare number" "K6_SOAK_WINDOW=1h30 is not a duration" -e K6_SOAK_WINDOW=1h30
refuses "a bare number (k6 would read milliseconds)" "K6_SOAK_WINDOW=300 is not a duration" -e K6_SOAK_WINDOW=300
refuses "one window too few after the warm-up" "has 5 window(s) of K6_SOAK_WINDOW=5m starting at or after K6_SOAK_WARMUP=15m; K6_SOAK_DRIFT_WINDOWS=6 are needed" \
  -e K6_SOAK_DURATION=40m -e K6_SOAK_DRIFT_WINDOWS=6
refuses "a window the warm-up ends inside is not settled" "has 5 window(s) of K6_SOAK_WINDOW=5m starting at or after K6_SOAK_WARMUP=16m" \
  -e K6_SOAK_DURATION=45m -e K6_SOAK_WARMUP=16m -e K6_SOAK_DRIFT_WINDOWS=6
refuses "a warm-up as long as the run" "has 0 window(s)" -e K6_SOAK_DURATION=10m -e K6_SOAK_WARMUP=10m -e K6_SOAK_DRIFT_WINDOWS=1
refuses "more windows than the cap" "it must give 1 to 96" -e K6_SOAK_DURATION=2h -e K6_SOAK_WINDOW=1m

echo
if [ "$FAILS" -gt 0 ]; then echo ":x: $FAILS soak.js init check(s) failed" >&2; exit 1; fi
echo "all soak.js init checks passed"
