#!/usr/bin/env bash
# Fixture tests for the multi-k6 arm's tail attribution files (performance programme item 44):
# lib/perf-tail-instrument.sh (gctrace CSV, per-second series, host kernel sampler shape, fail-soft and
# lifecycle), the harness wiring (stub docker, nothing started) and scripts/rw-tail-attribution.py on
# synthetic bundles. No Docker. Run: .buildkite/scripts/test/perf-tail-instrument-test.sh
# PERF_TAIL_LIB / PERF_TAIL_HARNESS / PERF_TAIL_HELPER=<path> test another copy (a degrade check); a
# harness copy still sources its libs from this tree, so put a degraded lib in place via PERF_TAIL_LIB.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
# shellcheck source=lib/perf-test-env.sh
. "$REPO_ROOT/.buildkite/scripts/test/lib/perf-test-env.sh"
perf_test_scrub_env PERF_TAIL_LIB PERF_TAIL_HARNESS PERF_TAIL_HELPER
LIB="${PERF_TAIL_LIB:-$REPO_ROOT/.buildkite/scripts/steps/lib/perf-tail-instrument.sh}"
HARNESS="${PERF_TAIL_HARNESS:-$REPO_ROOT/mockserver-performance-test/scripts/rw-multi-k6-sweep.sh}"
HELPER="${PERF_TAIL_HELPER:-$REPO_ROOT/mockserver-performance-test/scripts/rw-tail-attribution.py}"
# shellcheck source=../steps/lib/perf-tail-instrument.sh
. "$LIB"
T="$(mktemp -d "${TMPDIR:-/tmp}/perf-tail-test.XXXXXX")"
trap 'rm -rf "$T"' EXIT
FAILS=0
ok()   { echo "  ok   $1"; }
bad()  { echo "  FAIL $1" >&2; FAILS=$((FAILS + 1)); }
check() { # name expected actual
  if [ "$2" = "$3" ]; then ok "$1"; else bad "$1: expected '$2', got '$3'"; fi
}
has() { grep -qE "$1" <<<"$2" && echo yes || echo no; } # regex text
alive() { # pid -> success while it runs; an unreaped zombie counts as gone
  kill -0 "$1" 2>/dev/null || return 1
  case "$(ps -o stat= -p "$1" 2>/dev/null)" in Z*) return 1 ;; esac
}
gone_within() { # pid tenths -> yes once the process is gone
  local n=0
  while alive "$1" && [ "$n" -lt "$2" ]; do sleep 0.1; n=$((n + 1)); done
  alive "$1" && echo no || echo yes
}

echo "--- 1. gctrace CSV: one row per real k6 1.7.1 gctrace line"
cat > "$T/p0.log" <<'LOG'
running (0m17.3s), 0000/2049 VUs, 25 complete and 0 interrupted iterations
gc 1 @0.039s 0%: 0.13+1.7+0.011 ms clock, 2.1+0/4.4/0.63+0.17 ms cpu, 15->15->5 MB, 16 MB goal, 0 MB stacks, 0 MB globals, 16 P
time="2026-10-01T10:00:00Z" level=warning msg="Insufficient VUs"
gc 20 @241.757s 0%: 0.15+91+0.12 ms clock, 2.4+54/363/629+1.9 ms cpu, 3675->3700->746 MB, 3745 MB goal, 4 MB stacks, 0 MB globals, 16 P
gc 31 @351.428s 0%: 0.068+139+0.078 ms clock, 1.1+427/554/161+1.2 ms cpu, 4520->4600->936 MB, 4745 MB goal, 4 MB stacks, 0 MB globals, 16 P
LOG
GC_ROWS="$(k6_gctrace_csv main-p0 1000.5 "$T/p0.log")"
check "three cycles, nothing else" "3" "$(grep -c . <<<"$GC_ROWS")"
check "row: start = container start + @s; clock and cpu phases; heap and goal" \
  "main-p0,20,1242.257,0.15,91,0.12,54,363,629,3675,3700,746,3745" "$(sed -n 2p <<<"$GC_ROWS")"
check "every row has the header's column count" "13" "$(awk -F, '{print NF}' <<<"$GC_ROWS" | sort -u | tr '\n' ' ' | tr -d ' ')"
check "header names the same 13 columns" "13" "$(awk -F, '{print NF}' <<<"$K6_GC_CSV_HEADER")"
check "unknown container start -> no rows (never a guessed time)" "" "$(k6_gctrace_csv main-p0 null "$T/p0.log")"
check "missing log -> no rows, exit 0" "|0" "$(k6_gctrace_csv main-p0 1000 "$T/nope.log")|$?"

echo "--- 2. per-second series: counters differenced, gauge read at the end of the second, rungs and GC marks aligned"
# start 1000, step 4 s, gap 2 s: rung 0 = [1000, 1004), gap, rung 1 = [1006, 1010).
cat > "$T/ranges.json" <<'JSON'
{"failed":["dropped"],
 "reqs":[{"metric":{"proc":"main-p0"},"values":[[1000,"0"],[1001,"10"],[1002,"30"],[1003,"60"],[1004,"60"],[1005,"60"],[1006,"60"],[1007,"100"],[1008,"140"]]},
         {"metric":{"proc":"main-p1"},"values":[[1000,"0"],[1001,"20"],[1002,"5"],[1003,"15"]]}],
 "over_5ms":[{"metric":{"proc":"main-p0"},"values":[[1001,"0"],[1002,"0.5"],[1003,"5.5"],[1004,"NaN"],[1005,"5.5"],[1006,"5.4999999"]]}],
 "iterations":[{"metric":{"proc":"main-p0"},"values":[[1000,"0"],[1001,"10"],[1003,"60"]]}],
 "dropped":null,
 "vus":[{"metric":{"proc":"main-p0"},"values":[[1002,"3"],[1003,"7"]]}]}
JSON
printf '%s\n%s\n%s\n' "$K6_GC_CSV_HEADER" "main-p0,1,1002.2,0,500,0,0,0,0,1,1,1,2" "main-p0,2,1003.8,0,500,0,0,0,0,1,1,1,2" > "$T/gc.csv"
TS="$(k6_timeseries_csv "$T/ranges.json" "$T/gc.csv" 1000 4 2 "100,200")"
check "header" "$K6_TS_CSV_HEADER" "$(head -1 <<<"$TS")"
row() { awk -F, -v t="$1" -v p="$2" '$1 == t && $2 == p' <<<"$TS"; }
check "ts 1002 = [1002, 1003): reqs 60-30, over-5 ms 5.5-0.5, vus at 1003, one GC start, 500 ms of mark" \
  "1002,main-p0,100,2,30,5.0,,,7,1,500.0" "$(row 1002 main-p0)"
check "a mark across a second boundary is split between the two seconds" "200.0|300.0" \
  "$(row 1003 main-p0 | cut -d, -f11)|$(row 1004 main-p0 | cut -d, -f11)"
check "the gap between rungs has no rung label" ",|0" "$(row 1004 main-p0 | cut -d, -f3,4)|$(row 1004 main-p0 | cut -d, -f5)"
check "rung 1 starts at 1006: ts 1007 is 1 s into the 200 rung" "200,1,40" "$(row 1007 main-p0 | cut -d, -f3-5)"
check "a NaN sample is a missing end point (null), never 0" "" "$(row 1003 main-p0 | cut -d, -f6)"
check "a counter that went backwards is null" "" "$(row 1001 main-p1 | cut -d, -f5)"
check "a fall under 0.5 is rounding in the interpolated over-5 ms count: 0" "0.0" "$(row 1005 main-p0 | cut -d, -f6)"
check "a hole after a series' first sample is null, on both sides of it" "|" "$(row 1001 main-p0 | cut -d, -f7)|$(row 1002 main-p0 | cut -d, -f7)"
check "a succeeded query with no series for a process reads 0 (dropped_iterations before a drop)" "0" "$(row 1001 main-p1 | cut -d, -f7)"
check "a failed query leaves its column null on every row" "" "$(cut -d, -f8 <<<"$TS" | sed 1d | sort -u)"
check "every row has the header's column count" "11" "$(sed 1d <<<"$TS" | awk -F, '{print NF}' | sort -u)"
check "rows sorted by process, then time" "$(sed 1d <<<"$TS" | sort -t, -k2,2 -k1,1n)" "$(sed 1d <<<"$TS")"
echo '{"failed":["reqs","over_5ms","iterations","dropped","vus"],"reqs":null,"over_5ms":null,"iterations":null,"dropped":null,"vus":null}' > "$T/none.json"
check "every query failed: the header alone" "$K6_TS_CSV_HEADER" "$(k6_timeseries_csv "$T/none.json" "$T/gc.csv" 1000 4 2 100)"

echo "--- 3. host counters: one interval's rows from two /proc states"
mkproc() { # dir user sys soft netrx dropped squeezed retrans listendrops k6retrans softnet_cols idle(cpu1-3)
  local d="$1"; mkdir -p "$d/net" "$d/self/net" "$d/4242/net"
  {
    echo "cpu  1 2 3 4 5 6 7 8 9 10"
    echo "cpu0 $2 0 $3 1000 0 0 $4 0 0 0"
    echo "cpu1 10 0 10 ${12} 0 0 0 0 0 0"
    echo "cpu2 50 0 0 ${12} 0 0 0 0 0 0"
    echo "cpu3 0 0 0 ${12} 0 0 0 0 0 0"
  } > "$d/stat"
  printf '%s\n' "                    CPU0       CPU1       CPU2       CPU3" "          HI:          0          0          0          0" \
    "      NET_TX:          5          0          0          0" "      NET_RX:        $5          0         10          4" > "$d/softirqs"
  if [ "${11}" = 13 ]; then
    # Rows out of cpu order: the cpu id in column 13 must win over the row index.
    printf '%08x %08x %08x 00000000 00000000 00000000 00000000 00000000 00000000 00000000 00000000 00000000 %08x\n' \
      32 0 0 1 16 "$6" "$7" 0 48 0 0 2 64 0 0 3 > "$d/net/softnet_stat"
  else
    printf '%08x %08x %08x 00000000 00000000 00000000 00000000 00000000 00000000 00000000 00000000\n' 16 "$6" "$7" 32 0 0 48 0 0 64 0 0 > "$d/net/softnet_stat"
  fi
  # <root>/net is the sampler's own namespace on Linux (a link to self/net).
  mkdir -p "$d/net"; for n in self 4242; do
    local r="$8" ld="$9"; [ "$n" = 4242 ] && { r="${10}"; ld=0; }
    printf 'Tcp: RtoAlgorithm RetransSegs InErrs\nTcp: 1 %s 0\n' "$r" > "$d/$n/net/snmp"
    printf 'TcpExt: SyncookiesSent ListenOverflows ListenDrops TCPBacklogDrop\nTcpExt: 0 0 %s 7\n' "$ld" > "$d/$n/net/netstat"
  done
  cp "$d/self/net/snmp" "$d/self/net/netstat" "$d/net/"
}
mkproc "$T/a" 100 10 5 100 0 1 10 0 1 13 1000
mkproc "$T/b" 150 30 15 350 2 4 13 5 2 13 1100
NS="host=self k6_0=4242 k6_1="
_hks_snapshot "$T/a" "$NS" | sed 's/^time .*/time 2000.000/' > "$T/s1"
_hks_snapshot "$T/b" "$NS" | sed 's/^time .*/time 2001.250/' > "$T/s2"
: > "$T/cpu.csv"; : > "$T/tcp.csv"
_hks_rows "$T/s1" "$T/s2" "sut=0-1 k6_0=2" "$T/cpu.csv" "$T/tcp.csv"
crow() { awk -F, -v c="$1" '$3 == c' "$T/cpu.csv"; }
check "cpu0 (sut): % of its jiffies, NET_RX/NET_TX and softnet deltas (13-column softnet, cpu id in col 13)" \
  "2000,1.250,0,sut,62.5,25.0,12.5,0.0,0.0,250,0,0,2,3" "$(crow 0)"
check "cpu2 (k6_0): idle, its NET_RX unchanged" "2000,1.250,2,k6_0,0.0,0.0,0.0,0.0,100.0,0,0,0,0,0" "$(crow 2)"
check "an unlisted cpu is summed into 'other'" "2000,1.250,other,other,0.0,0.0,0.0,0.0,100.0,0,0,0,0,0" "$(crow other)"
check "every cpu row has the header's column count" "14|14" "$(awk -F, '{print NF}' "$T/cpu.csv" | sort -u)|$(awk -F, '{print NF}' <<<"$HKS_CPU_CSV_HEADER")"
TCP_RETRANS_COL="$(tr ',' '\n' <<<"$HKS_TCP_CSV_HEADER" | grep -n '^RetransSegs$' | cut -d: -f1)"
TCP_LDROP_COL="$(tr ',' '\n' <<<"$HKS_TCP_CSV_HEADER" | grep -n '^ListenDrops$' | cut -d: -f1)"
tcol() { awk -F, -v n="$1" -v c="$2" '$3 == n { print $c }' "$T/tcp.csv"; }
check "host namespace: RetransSegs and ListenDrops deltas" "3|5" "$(tcol host "$TCP_RETRANS_COL")|$(tcol host "$TCP_LDROP_COL")"
check "a container namespace is read from <root>/<pid>/net" "1" "$(tcol k6_0 "$TCP_RETRANS_COL")"
check "a counter the kernel does not report is null, not 0" "" "$(tcol host "$(tr ',' '\n' <<<"$HKS_TCP_CSV_HEADER" | grep -n '^TCPTimeouts$' | cut -d: -f1)")"
check "a namespace without a pid has no row (never the sampler's own /proc/net)" "" "$(tcol k6_1 3)"
mkproc "$T/a11" 100 10 5 100 0 1 10 0 1 11 1000
mkproc "$T/b11" 150 30 15 350 2 4 13 5 2 11 1100
_hks_snapshot "$T/a11" "" | sed 's/^time .*/time 2000/' > "$T/s1"; _hks_snapshot "$T/b11" "" | sed 's/^time .*/time 2001/' > "$T/s2"
: > "$T/cpu.csv"; : > "$T/tcp.csv"
_hks_rows "$T/s1" "$T/s2" "sut=0-1 k6_0=2" "$T/cpu.csv" "$T/tcp.csv"
check "11-column softnet (older kernels): the row index is the cpu" "2,3" "$(crow 0 | cut -d, -f13,14)"

echo "--- 4. fail soft: unreadable sources are null with a reason, and nothing fails"
mkdir -p "$T/empty" "$T/out1"
RC=0; PERF_PROC_ROOT="$T/empty" host_kernel_sampler_start "$T/out1" "sut=0-5" "host=self sut=" 10 0.2 || RC=$?
S1="$(cat "$T/out1/host-kernel-status.json")"
check "start returns 0 and starts no process" "0|" "$RC|$HOST_KERNEL_SAMPLER_PID"
check "status: not running, with a reason" "false|true" "$(jq -r .running <<<"$S1")|$(jq -r '.reason | test("no kernel counter source")' <<<"$S1")"
check "every source: available false, reason names the path" "5|5" \
  "$(jq '[.sources[] | select(.available == false)] | length' <<<"$S1")|$(jq '[.sources[] | select(.reason | test("not readable|no process id"))] | length' <<<"$S1")"
check "the CSVs hold their headers only" "$HKS_CPU_CSV_HEADER|$HKS_TCP_CSV_HEADER" "$(cat "$T/out1/host-kernel-cpu.csv")|$(cat "$T/out1/host-kernel-tcp.csv")"
host_kernel_sampler_stop
check "stop records not_started, 0 samples" "not_started|0|false" "$(jq -r '"\(.stop_reason)|\(.samples)|\(.truncated)"' "$T/out1/host-kernel-status.json")"
mkdir -p "$T/statonly" "$T/out2"; cp "$T/a/stat" "$T/statonly/stat"
PERF_PROC_ROOT="$T/statonly" host_kernel_sampler_start "$T/out2" "sut=0-1" "host=self" 1000 0.2
S2="$(cat "$T/out2/host-kernel-status.json")"
check "one readable source: running; the others null with a reason" "true|true|false|false|false" \
  "$(jq -r '[.running, .sources.proc_stat.available, .sources.softirqs.available, .sources.softnet_stat.available, .sources.tcp_host.available] | map(tostring) | join("|")' <<<"$S2")"
sleep 0.7; host_kernel_sampler_stop
check "  ... its rows exist, with empty softirq and softnet cells" "row||" \
  "$(awk -F, '$3 == "0" { print "row|" $10 "|" $12; exit }' "$T/out2/host-kernel-cpu.csv")"
check "a stop with no sampler running is a no-op" "0" "$(host_kernel_sampler_stop; echo $?)"

echo "--- 5. lifecycle: stopped, bounded, and gone on every exit path"
mkdir -p "$T/l1"
PERF_PROC_ROOT="$T/a" host_kernel_sampler_start "$T/l1" "sut=0-1" "host=self" 1000 0.2
P1="$HOST_KERNEL_SAMPLER_PID"
for _ in $(seq 1 50); do [ "$(cat "$T/l1/.hks-count" 2>/dev/null || echo 0)" -ge 2 ] && break; sleep 0.1; done
# The CPU % needs a whole second between start and stop (their epoch seconds); 2 samples can take less.
L1_START="$(jq -r .started_epoch_s "$T/l1/host-kernel-status.json")"
for _ in $(seq 1 30); do [ "$(date +%s)" -gt "$L1_START" ] && break; sleep 0.1; done
host_kernel_sampler_stop
check "stop: process gone at once" "yes" "$(gone_within "$P1" 0)"
check "stop: reason stopped, at least 2 samples, not truncated" "stopped|yes|false" \
  "$(jq -r '"\(.stop_reason)|\(if .samples >= 2 then "yes" else "no" end)|\(.truncated)"' "$T/l1/host-kernel-status.json")"
check "stop: the sampler's own CPU is recorded (seconds, and % of one cpu)" "number|number" \
  "$(jq -r '"\(.sampler_cpu_s | type)|\(.sampler_cpu_pct_of_one_cpu | type)"' "$T/l1/host-kernel-status.json")"
check "stop: no half-written row" "14" "$(awk -F, '{print NF}' "$T/l1/host-kernel-cpu.csv" | sort -u)"
check "stop: scratch files removed" "" "$(for f in "$T/l1"/.hks*; do [ -e "$f" ] && echo "$f"; done)"
mkdir -p "$T/l2"
PERF_PROC_ROOT="$T/a" host_kernel_sampler_start "$T/l2" "sut=0-1" "host=self" 2 0.2
P2="$HOST_KERNEL_SAMPLER_PID"
check "max_samples: the sampler ends by itself" "yes" "$(gone_within "$P2" 30)"
host_kernel_sampler_stop
check "max_samples: 2 samples, truncated" "max_samples|2|true" "$(jq -r '"\(.stop_reason)|\(.samples)|\(.truncated)"' "$T/l2/host-kernel-status.json")"
mkdir -p "$T/l3"
PERF_PROC_ROOT="$T/a" host_kernel_sampler_start "$T/l3" "sut=0-1" "host=self" 1000 0.2 1000
P3="$HOST_KERNEL_SAMPLER_PID"
check "max_bytes: the sampler ends by itself" "yes" "$(gone_within "$P3" 50)"
host_kernel_sampler_stop
check "max_bytes: truncated, CSVs bounded near the cap" "max_bytes|true|yes" \
  "$(jq -r '"\(.stop_reason)|\(.truncated)"' "$T/l3/host-kernel-status.json")|$([ "$(cat "$T/l3"/host-kernel-*.csv | wc -c)" -lt 2000 ] && echo yes || echo no)"
# A driver with the harness's trap shape: EXIT runs a declare -F-guarded stop; TERM exits through it.
cat > "$T/driver.sh" <<'DRV'
set -euo pipefail
. "$1"
trap '{ declare -F host_kernel_sampler_stop >/dev/null && host_kernel_sampler_stop; } 2>/dev/null || true' EXIT
trap 'exit 143' TERM
PERF_PROC_ROOT="$2" host_kernel_sampler_start "$3" "sut=0-1" "host=self" 1000 0.2
echo "$HOST_KERNEL_SAMPLER_PID" > "$3/pid"
case "$4" in fail) sleep 0.5; false ;; wait) sleep 30 & wait $! ;; esac
DRV
for how in fail term kill; do
  mkdir -p "$T/d-$how"
  mode="wait"; [ "$how" = fail ] && mode="fail"
  bash "$T/driver.sh" "$LIB" "$T/a" "$T/d-$how" "$mode" & DPID=$!
  for _ in $(seq 1 50); do [ -s "$T/d-$how/pid" ] && break; sleep 0.1; done
  SPID="$(cat "$T/d-$how/pid")"
  case "$how" in term) sleep 0.5; kill -TERM "$DPID" ;; kill) sleep 0.5; kill -KILL "$DPID" ;; esac
  wait "$DPID" 2>/dev/null || true
  check "harness exit by $how: the sampler is gone" "yes" "$(gone_within "$SPID" 30)"
done
check "  ... after a failure it was stopped by the EXIT trap" "stopped" "$(jq -r .stop_reason "$T/d-fail/host-kernel-status.json")"
check "  ... after SIGTERM it was stopped by the EXIT trap" "stopped" "$(jq -r .stop_reason "$T/d-term/host-kernel-status.json")"
check "  ... after SIGKILL (no trap runs) it saw its parent gone" "parent_gone" "$(cat "$T/d-kill/.hks-end" 2>/dev/null)"

echo "--- 6. harness wiring (stub docker, nothing started)"
H="$(cat "$HARNESS")"
check "the harness sources the lib with the shared guard" "yes" "$(has '^for lib in .*perf-tail-instrument\.sh; do$' "$H")"
CLEANUP="$(awk '/^cleanup\(\) \{/,/^}/' "$HARNESS")"
check "cleanup stops the sampler, declare -F guarded, before it copies the work files" "yes" \
  "$(awk '/declare -F host_kernel_sampler_stop/ { s = NR } /PERF_RW_DEBUG_DIR/ && !c { c = NR } END { print (s && c && s < c) ? "yes" : "no" }' <<<"$CLEANUP")"
RUNPHASE="$(awk '/^run_phase\(\) \{/,/^}/' "$HARNESS")"
check "run_phase starts it for the main ladder only" "yes" \
  "$(awk '/if \[ "\$phase" = main \] && \[ "\$TAIL_INSTRUMENT" = true \]; then/ { g = NR } /start_tail_sampler / && g && NR <= g + 1 { print "yes"; exit }' <<<"$RUNPHASE")"
check "run_phase stops it once the k6 processes have exited" "yes" \
  "$(awk '/docker wait "\$k"/ { w = NR } /host_kernel_sampler_stop/ { s = NR } END { print (w && s > w) ? "yes" : "no" }' <<<"$RUNPHASE")"
check "the sampler's pid is also in the cleanup registry" "yes" "$(has 'record_pid "\$HOST_KERNEL_SAMPLER_PID"' "$H")"
check "the docker stats loop ends with its parent (a SIGKILLed harness runs no cleanup)" "yes" \
  "$(has '^  \( while kill -0 "\$\$" 2>/dev/null; do$' "$RUNPHASE")"
mkdir -p "$T/bin"
printf '#!/usr/bin/env bash\nif [ "$1" = info ] && [ -n "${STUB_MEM:-}" ]; then echo "$STUB_MEM"; exit 0; fi\nexit 1\n' > "$T/bin/docker"
chmod +x "$T/bin/docker"
run_h() { # env... -> stdout in $R, exit code in $RC, stderr in $T/err
  # No BUILDKITE: in CI the NUMA guard refuses the unreadable fake sysfs, as perf-cpu-topology-test.sh notes.
  RC=0
  env -u BUILDKITE PATH="$T/bin:$PATH" PERF_RW_K6_CPUSETS="1;2" PERF_RW_PROCS=2 PERF_RW_REPO_ROOT="$REPO_ROOT" \
    PERF_SYSFS_ROOT="$T/nosysfs" PERF_TEST_HOST_CORES=48 "$@" bash "$HARNESS" >"$T/out" 2>"$T/err" || RC=$?
  R="$(cat "$T/out")"
}
run_h PERF_RW_TEST_RESOLVE_ONLY=true STUB_MEM=103079215104 PERF_RW_K6_GOGC=off
check "k6 GC A/B: GOGC=off keeps the derived GOMEMLIMIT as its trigger" "0|off|24576MiB|env|derived" \
  "$RC|$(jq -r '"\(.gogc)|\(.gomemlimit)|\(.source.gogc)|\(.source.gomemlimit)"' <<<"$R" 2>/dev/null)"
run_h PERF_RW_TEST_RESOLVE_ONLY=true PERF_RW_K6_GOGC=off PERF_RW_K6_GOMEMLIMIT=off
check "GOGC=off with GOMEMLIMIT=off is refused (Go would never collect)" "2|yes" "$RC|$(has 'never collects' "$(cat "$T/err")")"
for bad_env in PERF_RW_TAIL_INSTRUMENT=yes PERF_RW_HOST_SAMPLER_INTERVAL_S=0 PERF_RW_HOST_SAMPLER_INTERVAL_S=1s PERF_RW_HOST_SAMPLER_MAX_BYTES=10; do
  run_h PERF_RW_TEST_RESOLVE_ONLY=true PERF_RW_K6_GOMEMLIMIT=off "$bad_env"
  check "rejected at startup: $bad_env" "2" "$RC"
done
run_h PERF_RW_TEST_PLACEMENT_ONLY=true STUB_MEM=103079215104
check "default run: .ab all null, not a trial" "false|5" "$(jq -r '"\(.ab.trial)|\([.ab[] | select(. == null)] | length)"' <<<"$R")"
run_h PERF_RW_TEST_PLACEMENT_ONLY=true STUB_MEM=103079215104 PERF_RW_K6_GOGC=800
check "k6 GC A/B (GOGC=800) is recorded as a trial" "true|800" "$(jq -r '"\(.ab.trial)|\(.ab.k6_gogc)"' <<<"$R")"
check "  ... and logged as not a counting run" "yes" "$(has 'A/B trial, not a counting run' "$(cat "$T/err")")"
run_h PERF_RW_TEST_PLACEMENT_ONLY=true STUB_MEM=103079215104 PERF_RW_K6_VU_CEILING=2048
check "VU-ceiling A/B (PERF_RW_K6_VU_CEILING) is recorded as a trial" "true|2048" "$(jq -r '"\(.ab.trial)|\(.ab.k6_vu_ceiling)"' <<<"$R")"
run_h PERF_RW_TEST_PLACEMENT_ONLY=true STUB_MEM=103079215104 PERF_K6_NUMA_NODE=same
check "same-socket A/B is recorded as a trial" "true|same" "$(jq -r '"\(.ab.trial)|\(.ab.k6_numa_node)"' <<<"$R")"

echo "--- 6b. tail files: an oversized or empty Prometheus answer still gives the result a JSON value"
# The harness's own functions run against a stub curl. 8 x 5,000 points is ~1.2 MB: past Linux's 128 KiB
# per-argument limit and macOS's 1 MiB ARG_MAX, so passing a matrix as an argument fails on both hosts.
for f in k6_prom_ranges build_tail_files write_tail_files tail_instrument_json; do
  src="$(awk "/^$f\\(\\) \\{/,/^}/" "$HARNESS")"
  if [ -n "$src" ]; then eval "$src"; else bad "the harness defines $f()"; fi
done
curl() { case "$*" in *k6_vus*) [ -z "${STUB_VUS_FAIL:-}" ] || return 22 ;; esac; cat "$STUB_BODY"; }
prom_url() { echo http://stub; }
tail_case() { # procs points: a fresh WORK holding the files build_tail_files reads
  WORK="$T/tf-$1-$2"; rm -rf "$WORK"; mkdir -p "$WORK"
  # shellcheck disable=SC2034  # read by the harness functions eval'd above
  N="$1" QUIET_S=5 STEP_S=60 GAP_S=0 K6_GCTRACE=true STUB_BODY="$WORK/body.json"
  jq -nc --argjson p "$2" '{start_at_s:1700000005, ladder_end_s:(1700000000 + $p - 20), container_started_epoch_s:[], agg_rates:[1000,2000]}' \
    > "$WORK/main-meta.json"
  for ((i = 0; i < $1; i++)); do : > "$WORK/main-p$i.log"; done
  echo '{"running":false,"reason":"stub"}' > "$WORK/host-kernel-status.json"
  jq -nc --argjson n "$1" --argjson p "$2" '{status:"success", data:{resultType:"matrix", result:[range($n) as $i
    | {metric:{proc:"main-p\($i)"}, values:[range($p) as $k | [(1700000000 + $k), "\($k * 37.123457)"]]}]}}' > "$STUB_BODY"
}
assembled() { jq -nc --argjson t "$(tail_instrument_json)" '{valid:true, tail_instrumentation:$t}' 2>/dev/null || echo "assembly failed"; }
tail_case 8 5000
check "  (fixture: the stub matrix is over 1 MiB)" "yes" "$([ "$(wc -c < "$STUB_BODY")" -gt 1048576 ] && echo yes || echo no)"
write_tail_files 2>"$T/tf-err"
check "a 1.2 MB matrix: every series is read, no query failed, no warning" "39992|[]|0" \
  "$(jq -r '"\(.k6_timeseries.rows)|\(.k6_timeseries.failed_queries | tojson)"' "$WORK/tail-instrument.json" 2>/dev/null)|$(grep -c WARNING "$T/tf-err" || true)"
check "  ... and the result assembles with it" "true|39992" "$(assembled | jq -r '"\(.valid)|\(.tail_instrumentation.k6_timeseries.rows)"')"
tail_case 2 50
STUB_VUS_FAIL=1 write_tail_files 2>/dev/null
check "a failed query is null and named; the others are read" '["vus"]|98' \
  "$(jq -r '"\(.k6_timeseries.failed_queries | tojson)|\(.k6_timeseries.rows)"' "$WORK/tail-instrument.json")"
tail_case 2 50
k6_prom_ranges() { printf '\n' > "$3"; }
write_tail_files 2>/dev/null
check "a range-query file holding only a newline: all queries failed, with a reason" '["all"]|0|no Prometheus series for main-p*' \
  "$(jq -r '"\(.k6_timeseries.failed_queries | tojson)|\(.k6_timeseries.rows)|\(.k6_timeseries.reason)"' "$WORK/tail-instrument.json" 2>/dev/null)"
build_tail_files() { : > "$WORK/tail-instrument.json"; }
write_tail_files 2>"$T/tf-err"
check "build_tail_files leaving an empty file: a warning, and the result carries the reason" "yes|yes" \
  "$(has 'tail-instrument.json is empty or not JSON' "$(cat "$T/tf-err")")|$(assembled | jq -e '.tail_instrumentation.error | test("empty or not JSON")' >/dev/null 2>&1 && echo yes || echo no)"
for bad_file in "" "not json" '{"a":1}{"b":2}' '[1]'; do
  printf '%s' "$bad_file" > "$WORK/tail-instrument.json"
  check "tail-instrument.json holding '$bad_file': the result assembles with null" "null" "$(assembled | jq -c .tail_instrumentation)"
done
rm -f "$WORK/tail-instrument.json"
check "no tail-instrument.json: the result assembles with null" "null" "$(assembled | jq -c .tail_instrumentation)"
unset -f curl prom_url k6_prom_ranges build_tail_files write_tail_files tail_instrument_json

echo "--- 7. rw-tail-attribution.py on synthetic bundles"
if ! command -v python3 >/dev/null 2>&1; then
  if [ "${BUILDKITE:-}" = "true" ]; then bad "python3 absent: the analysis helper is untested"; else echo "  SKIP python3 absent"; fi
else
  B="$T/bundle/serving-rw-multik6-work"; mkdir -p "$B"
  # Two rungs: 64k at [1000, 1010) and 128k at [1014, 1024).
  jq -n '{phase:"main", n:2, agg_rates:[64000,128000], per_process_rates:[32000,64000], start_at_s:1000,
          ladder_end_s:1024, step_s:10, gap_s:4, container_started_epoch_s:[990,990]}' > "$B/main-meta.json"
  { echo "$K6_TS_CSV_HEADER"
    for p in 0 1; do for t in $(seq 999 1025); do
      v=10; o=0
      [ "$p" = 1 ] && [ "$t" = 1019 ] && { v=400; o=300; }
      [ "$p" = 0 ] && [ "$t" = 1021 ] && { v=200; o=50; }
      [ "$p" = 0 ] && [ "$t" = 1014 ] && { v=300; o=90; } # a rung-onset second: never a spike
      echo "$t,main-p$p,,,1000,$o,1000,,$v,0,0.0"
    done; done; } > "$B/main-k6-timeseries.csv"
  printf '%s\n%s\n%s\n' "$K6_GC_CSV_HEADER" "main-p1,7,1019.2,0.1,300,0.1,0,0,0,1,1,1,2" "main-p0,5,1005.0,0.1,300,0.1,0,0,0,1,1,1,2" > "$B/main-k6-gc.csv"
  { echo "$HKS_CPU_CSV_HEADER"
    for t in $(seq 999 1025); do for c in 0 1; do
      soft=1.0; sq=0; [ "$t" = 1019 ] && [ "$c" = 0 ] && { soft=30.0; sq=7; }
      echo "$t,1.000,$c,sut,10.0,5.0,$soft,0.0,84.0,100,50,100,0,$sq"
    done; echo "$t,1.000,2,k6_0,40.0,9.0,2.0,0.0,49.0,10,10,10,0,0"; done; } > "$B/host-kernel-cpu.csv"
  { echo "$HKS_TCP_CSV_HEADER"
    for t in $(seq 999 1025); do r=0; [ "$t" = 1019 ] && r=4; echo "$t,1.000,sut,$r,0,0,0,0,0,0,0,0,0,0,0,0,0,0"; done; } > "$B/host-kernel-tcp.csv"
  OUT="$(python3 "$HELPER" "$B" 2>&1)" || bad "helper exited non-zero on a complete bundle: $OUT"
  L1="$(grep 'main-p1 @+5.0s' <<<"$OUT" || true)"
  check "the planted p1 spike: both criteria, its own GC mark, the SUT's softirq at that second" "yes|yes|yes|yes|yes" \
    "$(has '\[vus\+over5\]' "$L1")|$(has 'OWN-GC [0-9]+ ms' "$L1")|$(has 'sut soft 15.5% sys 5% NET_RX 200' "$L1")|$(has 'squeeze 7' "$L1")|$(has 'sut retrans 4' "$L1")"
  L0="$(grep 'main-p0 @+7.0s' <<<"$OUT" || true)"
  check "the planted p0 spike without a GC: no own GC" "yes|no" "$(has 'no own GC' "$L0")|$(has 'OWN-GC' "$L0")"
  check "a rung's first second is skipped (onset transient)" "no" "$(has '@\+0\.0s' "$OUT")"
  check "rung headers: only rungs with a spike" "--- rung 128000" "$(grep '^--- rung' <<<"$OUT")"
  check "summary: 2 spikes, 1 aligned with its own GC" "yes|yes" \
    "$(has '^spikes 2 ' "$OUT")|$(has 'own-GC aligned 1/2' "$OUT")"
  check "summary: SUT softirq in spike seconds vs the rest" "yes" "$(has 'SUT cpus: spike seconds soft 8\.2%' "$OUT")"
  check "--min-rate drops lower rungs" "yes" "$(has '^spikes 0 over rungs >= 200000' "$(python3 "$HELPER" "$B" --min-rate 200000)")"
  tar czf "$T/bundle.tgz" -C "$T/bundle" serving-rw-multik6-work
  check "a .tgz bundle gives the same spikes" "$(grep -c '@+' <<<"$OUT")" "$(python3 "$HELPER" "$T/bundle.tgz" | grep -c '@+')"
  rm "$B/host-kernel-cpu.csv" "$B/host-kernel-tcp.csv"
  jq -n '{running:false, reason:"no kernel counter source is readable on this host", sources:{}}' > "$B/host-kernel-status.json"
  OUT2="$(python3 "$HELPER" "$B" 2>&1)" || bad "helper exited non-zero without host counters: $OUT2"
  check "no host counters: says why, still lists the spikes" "yes|2" \
    "$(has 'host kernel counters unavailable: no kernel counter source' "$OUT2")|$(grep -c '@+' <<<"$OUT2")"
  # An older bundle: no series CSV, no GC CSV, no step_s; progress and gctrace lines in the logs.
  O="$T/old"; mkdir -p "$O"
  jq -n '{phase:"main", n:1, agg_rates:[1000], per_process_rates:[1000], start_at_s:2000, ladder_end_s:2010, container_started_epoch_s:[1990]}' > "$O/main-meta.json"
  jq -n '{sweep:{latency_window:{settle_s:3, measured_s:7}}}' > "$O/result.json"
  { echo "running (0m10.0s), 0000/100 VUs, 0 complete and 0 interrupted iterations"
    for s in $(seq 11 19); do v=2; [ "$s" = 15 ] && v=90; echo "running (0m${s}.0s), 00$v/100 VUs, $(( (s - 10) * 1000 )) complete and 0 interrupted iterations"; done
    echo "gc 3 @14.9s 0%: 0.1+200+0.1 ms clock, 1+1/1/1+1 ms cpu, 10->10->5 MB, 20 MB goal, 0 MB stacks, 0 MB globals, 16 P"; } > "$O/main-p0.log"
  OUT3="$(python3 "$HELPER" "$O" 2>&1)" || bad "helper exited non-zero on an older bundle: $OUT3"
  check "older bundle: progress-line fallback finds the VU spike inside its own GC" "yes|yes" \
    "$(has 'series from progress lines' "$OUT3")|$(has 'main-p0 @\+5\.0s \[vus\] vus 90 .*OWN-GC' "$OUT3")"
fi

echo
if [ "$FAILS" -gt 0 ]; then echo ":x: $FAILS check(s) failed" >&2; exit 1; fi
echo "all perf-tail-instrument checks passed"
