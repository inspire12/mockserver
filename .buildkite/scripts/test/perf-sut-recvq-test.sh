#!/usr/bin/env bash
# Fixture tests for the SUT receive-queue sampler (performance programme item 44):
# lib/perf-sut-recvq.sh against a fake /proc and stub nsenter/ss, and its wiring into
# scripts/rw-multi-k6-sweep.sh. No Docker needed.
# Run: .buildkite/scripts/test/perf-sut-recvq-test.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
LIB="${PERF_RQ_LIB:-$REPO_ROOT/.buildkite/scripts/steps/lib/perf-sut-recvq.sh}"
HARNESS="${PERF_RQ_HARNESS:-$REPO_ROOT/mockserver-performance-test/scripts/rw-multi-k6-sweep.sh}"
# shellcheck source=../steps/lib/perf-sut-recvq.sh
. "$LIB"
T="$(mktemp -d "${TMPDIR:-/tmp}/perf-recvq-test.XXXXXX")"
ORPHAN=""
trap '{ [ -z "$ORPHAN" ] || kill -9 "$ORPHAN"; } 2>/dev/null || true; rm -rf "$T"' EXIT
FAILS=0
ok()   { echo "  ok   $1"; }
bad()  { echo "  FAIL $1" >&2; FAILS=$((FAILS + 1)); }
check() { # name expected actual
  if [ "$2" = "$3" ]; then ok "$1"; else bad "$1: expected '$2', got '$3'"; fi
}
st() { jq -r "$1" "$T/w/main-sut-recvq-status.json" 2>/dev/null || echo "<no status>"; }
# poll <seconds> <command...>: true once the command succeeds, false after the bound (never a fixed sleep).
poll() { local end=$(( $(date +%s) + $1 )); shift; until "$@"; do [ "$(date +%s)" -lt "$end" ] || return 1; sleep 0.1; done; }
rows_at_least() { [ $(( $(wc -l < "$1") - 1 )) -ge "$2" ]; }

# A fake /proc for pid 4242: a listener with 3 queued connections, three established sockets with
# 0, 500 (0x1F4) and 16 (tcp6) unread bytes, and a TIME_WAIT socket that must not count.
P="$T/proc"; mkdir -p "$P/4242/net"
cat > "$P/4242/net/tcp" <<'TCP'
  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
   0: 00000000:0438 00000000:0000 0A 00000000:00000003 00:00000000 00000000     0        0 10 1 0000000000000000 100 0 0 10 0
   1: 0300120A:0438 0500120A:A8C2 01 00000000:00000000 00:00000000 00000000     0        0 11 1 0000000000000000 20 4 30 10 -1
   2: 0300120A:0438 0500120A:A8C3 01 00000010:000001F4 00:00000000 00000000     0        0 12 1 0000000000000000 20 4 30 10 -1
   3: 0300120A:0438 0500120A:A8C4 06 00000000:00FFFFFF 00:00000000 00000000     0        0 0 3 0000000000000000
TCP
cat > "$P/4242/net/tcp6" <<'TCP'
  sl  local_address                         remote_address                        st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
   0: 0000000000000000FFFF00000300120A:0438 0000000000000000FFFF00000600120A:B000 01 00000000:00000010 00:00000000 00000000     0        0 13 1 0000000000000000 20 4 30 10 -1
TCP
mkdir -p "$T/bin" "$T/ssbin"
printf '#!/usr/bin/env bash\necho "nsenter: reassociate to namespace ns/net failed: Operation not permitted" >&2\nexit 1\n' > "$T/bin/nsenter"
printf '#!/usr/bin/env bash\nexit 0\n' > "$T/bin/ss"
printf '#!/usr/bin/env bash\nshift 3; "$@"\n' > "$T/ssbin/nsenter"
cat > "$T/ssbin/ss" <<'SS'
#!/usr/bin/env bash
echo "LISTEN 2      4096   0.0.0.0:1080      0.0.0.0:*"
echo "ESTAB  0      0      172.18.0.3:1080   172.18.0.5:43010"
echo "ESTAB  7000   0      172.18.0.3:1080   172.18.0.5:43011"
echo "TIME-WAIT 0   0      172.18.0.3:1080   172.18.0.5:43012"
SS
chmod +x "$T/bin/"* "$T/ssbin/"*

echo "--- 1. one sample, from /proc/<pid>/net/tcp{,6} and from ss"
check "proc: estab, nonzero, sum, max, accept queue" "3,2,516,500,3" "$(_recvq_proc "$P" 4242)"
check "proc: no such pid fails" "fail" "$(_recvq_proc "$P" 999 || echo fail)"
check "proc: a malformed rx_queue is skipped, not read as 0" "1,0,0,0,0" \
  "$(mkdir -p "$P/77/net"; printf '  sl\n   0: A:1 B:2 01 0:0000zz00\n   1: A:1 B:3 01 0:0\n' > "$P/77/net/tcp"; _recvq_proc "$P" 77)"
check "ss: the same columns from ss -tanH in the namespace" "2,1,7000,7000,2" "$(PATH="$T/ssbin:$PATH" _recvq_ss 4242)"
check "ss: nsenter refused (not root) fails" "fail" "$(PATH="$T/bin:$PATH" _recvq_ss 4242 || echo fail)"

echo "--- 2. the sampler: source choice, rows, stop, status"
mkdir -p "$T/w"
PATH="$T/bin:$PATH" PERF_PROC_ROOT="$P" sut_recvq_sampler_start "$T/w" 4242 100 0.2
check "nsenter refused: falls back to /proc, running" "true proc null" "$(st '"\(.running) \(.source) \(.reason)"')"
check "rows are written at the interval (3 within 30 s)" "yes" "$(poll 30 rows_at_least "$T/w/main-sut-recvq.csv" 3 && echo yes || echo no)"
sut_recvq_sampler_stop
check "every row is one proc sample" "proc,3,2,516,500,3" "$(tail -n 1 "$T/w/main-sut-recvq.csv" | cut -d, -f2-7)"
check "read_ms is a duration where the clock has sub-seconds, else empty" "yes" \
  "$(tail -n 1 "$T/w/main-sut-recvq.csv" | awk -F, -v rt="${EPOCHREALTIME:-}" '{ print ((rt == "" && $8 == "") || (rt != "" && $8 ~ /^[0-9]+\.[0-9]$/)) ? "yes" : "no: " $8 }')"
check "status: stopped, its samples counted" "stopped true" "$(st '"\(.stop_reason) \(.samples >= 3)"')"
check "stop is idempotent and leaves no scratch files" "0 stopped" \
  "$(sut_recvq_sampler_stop; find "$T/w" -name '.recvq-*' | wc -l | tr -d ' ') $(st .stop_reason)"
PATH="$T/ssbin:$PATH" PERF_PROC_ROOT="$P" sut_recvq_sampler_start "$T/w" 4242 2 0.1
poll 30 test -s "$T/w/.recvq-end" || true; sut_recvq_sampler_stop
check "nsenter + ss usable: ss is the source, bounded by max_samples" "ss max_samples 2" "$(st '"\(.source) \(.stop_reason) \(.samples)"')"
PATH="$T/bin:$PATH" PERF_PROC_ROOT="$P" sut_recvq_sampler_start "$T/w" 4242 100 0.1 150
poll 30 test -s "$T/w/.recvq-end" || true; sut_recvq_sampler_stop
check "bounded by max_bytes: stops once the CSV reaches it" "max_bytes yes" \
  "$(st .stop_reason) $([ "$(wc -c < "$T/w/main-sut-recvq.csv")" -lt 300 ] && echo yes || echo no)"
sut_recvq_sampler_start "$T/w" "" 10
check "no SUT pid (external target): not started, says why" "false null true" "$(st '"\(.running) \(.source) \(.reason | test("no SUT process id"))"')"
check "  ... and the CSV is just the header" "1" "$(wc -l < "$T/w/main-sut-recvq.csv" | tr -d ' ')"
sut_recvq_sampler_stop
check "  ... stop on a sampler that never ran records it" "not_started 0" "$(st '"\(.stop_reason) \(.samples)"')"
PATH="$T/bin:$PATH" PERF_PROC_ROOT="$T/nowhere" sut_recvq_sampler_start "$T/w" 4242 10
check "nothing readable: not started, says why" "false true" "$(st '"\(.running) \(.reason | test("neither nsenter"))"')"
sut_recvq_sampler_stop
mkdir -p "$T/ro"; chmod 0555 "$T/ro"
check "an unwritable work dir never fails the caller" "0" "$(PERF_PROC_ROOT="$T/nowhere" sut_recvq_sampler_start "$T/ro/missing" 4242 10 >/dev/null 2>&1; echo $?)"
# The harness dies without stopping it (SIGKILL): the sampler notices its parent is gone and exits.
mkdir -p "$T/pg"
( PATH="$T/bin:$PATH" PERF_PROC_ROOT="$P" bash -c '. "$1"; sut_recvq_sampler_start "$2" 4242 100000 0.1
  echo "$SUT_RECVQ_PID" > "$2/sampler.pid"; kill -9 $$' _ "$LIB" "$T/pg" || true ) 2>/dev/null
ORPHAN="$(cat "$T/pg/sampler.pid" 2>/dev/null || true)"
check "parent killed: the sampler records parent_gone within 30 s" "parent_gone" \
  "$(poll 30 test -s "$T/pg/.recvq-end" && cat "$T/pg/.recvq-end" || echo "still running")"
check "  ... and exits" "gone" "$(poll 30 bash -c "! kill -0 '$ORPHAN' 2>/dev/null" && echo gone || echo alive)"

echo "--- 3. per-rung summary over each steady window"
# start 1000, step 10, gap 5, settle 3: rung 0 steady [1003,1010), rung 1 [1018,1025).
cat > "$T/rq.csv" <<'CSV'
ts,source,estab,recvq_nonzero,recvq_sum_bytes,recvq_max_bytes,listen_recvq,read_ms
1001.0,proc,10,5,99999,99999,9
1003.0,proc,10,0,0,0,0
1005.5,proc,10,2,300,200,1
1009.9,proc,10,1,100,100,0
1012.0,proc,10,9,88888,88888,8
1018.0,proc,12,4,1000,700,2
1024.0,proc,12,0,0,0,0
1031.0,proc,12,9,77777,77777,7
CSV
R="$(sut_recvq_rungs "$T/rq.csv" 1000 10 5 3 "8000,16000,24000")"
check "settle and gap samples are excluded; a rung with no sample is absent" \
  '[{"offered_rps":8000,"samples":3,"recvq_nonzero_mean":1.00,"est_wait_to_read_ms":0.1250,"recvq_sum_bytes_mean":133.3,"recvq_sum_bytes_max":300,"recvq_max_bytes_max":200,"nonzero_sample_frac":0.667,"listen_recvq_max":1},{"offered_rps":16000,"samples":2,"recvq_nonzero_mean":2.00,"est_wait_to_read_ms":0.1250,"recvq_sum_bytes_mean":500.0,"recvq_sum_bytes_max":1000,"recvq_max_bytes_max":700,"nonzero_sample_frac":0.500,"listen_recvq_max":2}]' "$R"
check "the summary is valid JSON" "2" "$(jq length <<<"$R")"
check "no CSV: an empty array" "[]" "$(sut_recvq_rungs "$T/none.csv" 1000 10 5 3 "8000")"

echo "--- 4. wiring in rw-multi-k6-sweep.sh"
MAIN="$(awk '/^if \[ "\$SUT_RECVQ" = true \]; then$/ {on = 1} on {print} /^run_phase main / {seen = 1} seen && /^fi$/ {exit}' "$HARNESS")"
has() { grep -qE -- "$1" <<<"$2" && echo yes || echo no; }
check "the sampler starts before the main ladder" "yes" "$(has '^  sut_recvq_sampler_start "\$WORK"' "$(sed -n '1,/^run_phase main /p' <<<"$MAIN")")"
check "it is stopped after the main ladder and summarised per rung" "yes yes" \
  "$(has '^  sut_recvq_sampler_stop$' "$(sed -n '/^run_phase main /,$p' <<<"$MAIN")") $(has 'sut_recvq_rungs ' "$(sed -n '/^run_phase main /,$p' <<<"$MAIN")")"
check "its pid is recorded, so an abort's cleanup kills it" "yes" "$(has 'record_pid "\$SUT_RECVQ_PID"' "$MAIN")"
mkdir -p "$T/dbin"; printf '#!/usr/bin/env bash\nexit 1\n' > "$T/dbin/docker"; chmod +x "$T/dbin/docker"
RC=0; env PATH="$T/dbin:$PATH" PERF_RW_TEST_RESOLVE_ONLY=true PERF_RW_K6_GOMEMLIMIT=off PERF_RW_K6_CPUSETS="1;2" PERF_RW_PROCS=2 \
  PERF_RW_SUT_RECVQ=yes PERF_RW_REPO_ROOT="$REPO_ROOT" bash "$HARNESS" >/dev/null 2>"$T/h.err" || RC=$?
check "PERF_RW_SUT_RECVQ=yes is rejected at startup with exit 2, naming the setting" "2 yes" \
  "$RC $(grep -q 'PERF_RW_SUT_RECVQ must be true or false' "$T/h.err" && echo yes || echo no)"

if [ "$FAILS" -gt 0 ]; then echo ":x: $FAILS receive-queue check(s) failed" >&2; exit 1; fi
echo "--- all receive-queue fixture checks passed"
