#!/usr/bin/env bash
# Fixture tests for the NUMA placement in lib/perf-cpu-topology.sh and the two arms that use it
# (scripts/rw-multi-k6-sweep.sh and lib/perf-percore.sh's hardware matrix), driven against fake
# sysfs trees through PERF_SYSFS_ROOT. Docker is a stub that fails, so nothing is started.
# Run: .buildkite/scripts/test/perf-cpu-topology-test.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
# shellcheck source=../steps/lib/perf-cpu-topology.sh
. "$REPO_ROOT/.buildkite/scripts/steps/lib/perf-cpu-topology.sh"
HARNESS="${PERF_TOPO_HARNESS:-$REPO_ROOT/mockserver-performance-test/scripts/rw-multi-k6-sweep.sh}"
PERCORE="${PERF_TOPO_PERCORE:-$REPO_ROOT/.buildkite/scripts/steps/lib/perf-percore.sh}"
T="$(mktemp -d "${TMPDIR:-/tmp}/perf-topo-test.XXXXXX")"
trap 'rm -rf "$T"' EXIT
FAILS=0
ok()   { echo "  ok   $1"; }
bad()  { echo "  FAIL $1" >&2; FAILS=$((FAILS + 1)); }
check() { # name expected actual
  if [ "$2" = "$3" ]; then ok "$1"; else bad "$1: expected '$2', got '$3'"; fi
}
unset BUILDKITE PERF_SYSFS_CPU_ROOT PERF_SYSFS_NODE_ROOT PERF_K6_NUMA_NODE

# make_cpu <root> <cpu> <package> <core_id> <thread_siblings_list>
make_cpu() {
  local d="$1/devices/system/cpu/cpu$2/topology"
  mkdir -p "$d"
  echo "$3" > "$d/physical_package_id"; echo "$4" > "$d/core_id"; echo "$5" > "$d/thread_siblings_list"
}
make_node() { mkdir -p "$1/devices/system/node/node$2"; echo "$3" > "$1/devices/system/node/node$2/cpulist"; }

# c5.12xlarge: one socket, 24 cores, 48 threads, siblings N and N+24, one node.
C5="$T/c5"
for c in $(seq 0 47); do b=$(( c % 24 )); make_cpu "$C5" "$c" 0 "$b" "$b,$(( b + 24 ))"; done
make_node "$C5" 0 "0-47"
# c6i.32xlarge: two sockets x 32 cores, 128 threads. The kernel enumerates every socket's first
# threads before any sibling, as AWS documents for the two-socket c5.24xlarge (node0 0-23,48-71;
# node1 24-47,72-95), so node0 is 0-31,64-95, node1 32-63,96-127 and siblings are N and N+64.
# core_id restarts per package, so the package must disambiguate physical cores.
C6I="$T/c6i"
for c in $(seq 0 127); do
  b=$(( c % 64 )); make_cpu "$C6I" "$c" $(( b / 32 )) $(( b % 32 )) "$b,$(( b + 64 ))"
done
make_node "$C6I" 0 "0-31,64-95"; make_node "$C6I" 1 "32-63,96-127"
# Another two-node numbering (siblings adjacent, nodes contiguous): the layout must follow the
# node map, not assume c6i's.
ALT="$T/alt"
for c in $(seq 0 63); do b=$(( c - c % 2 )); make_cpu "$ALT" "$c" $(( c / 32 )) $(( (c % 32) / 2 )) "$b-$(( b + 1 ))"; done
make_node "$ALT" 0 "0-31"; make_node "$ALT" 1 "32-63"
# Node 0 owns the HIGH cpus: a layout that took "the lowest cpus" instead of node 0's would show.
SWAP="$T/swap"
for c in $(seq 0 15); do make_cpu "$SWAP" "$c" $(( c < 8 ? 1 : 0 )) "$c" "$c"; done
make_node "$SWAP" 0 "8-15"; make_node "$SWAP" 1 "0-7"
# Three nodes: a k6 straddling nodes 1 and 2 shares no node with the SUT, so only the per-k6
# single-node check stops it.
TRI="$T/tri"
for c in $(seq 0 11); do make_cpu "$TRI" "$c" $(( c / 4 )) "$c" "$c"; done
make_node "$TRI" 0 "0-3"; make_node "$TRI" 1 "4-7"; make_node "$TRI" 2 "8-11"
# The c6i booted with numa=off: two sockets, one node. The node map cannot show the split.
NUMAOFF="$T/numaoff"
for c in $(seq 0 127); do
  b=$(( c % 64 )); make_cpu "$NUMAOFF" "$c" $(( b / 32 )) $(( b % 32 )) "$b,$(( b + 64 ))"
done
make_node "$NUMAOFF" 0 "0-127"
# Sub-NUMA clustering: two sockets of two nodes each. Node 1 is the SUT's socket, node 2 the other.
SNC="$T/snc"
for c in $(seq 0 15); do make_cpu "$SNC" "$c" $(( c / 8 )) "$c" "$c"; done
make_node "$SNC" 0 "0-3"; make_node "$SNC" 1 "4-7"; make_node "$SNC" 2 "8-11"; make_node "$SNC" 3 "12-15"
# One socket split into two nodes: no other socket to put k6 on.
SNC1="$T/snc1"
for c in $(seq 0 7); do make_cpu "$SNC1" "$c" 0 "$c" "$c"; done
make_node "$SNC1" 0 "0-3"; make_node "$SNC1" 1 "4-7"
# A kernel without NUMA: cpu topology but no node directory.
NONUMA="$T/nonuma"
for c in $(seq 0 7); do b=$(( c % 4 )); make_cpu "$NONUMA" "$c" 0 "$b" "$b,$(( b + 4 ))"; done

echo "--- 1. node map from sysfs"
check "compress_cpulist" "7-10,31-34" "$(compress_cpulist "34 7 8 31 9 10 32 33")"
check "compress_cpulist single + pair" "7,71" "$(compress_cpulist "71 7")"
check "c5: one node" "0" "$(PERF_SYSFS_ROOT=$C5 numa_nodes)"
check "c6i: two nodes" "0 1" "$(PERF_SYSFS_ROOT=$C6I numa_nodes)"
check "c6i: node count" "2" "$(PERF_SYSFS_ROOT=$C6I numa_node_count)"
check "c6i: 0-5 on node 0" "0" "$(PERF_SYSFS_ROOT=$C6I cpuset_numa_nodes 0-5)"
check "c6i: siblings 64-69 on node 0" "0" "$(PERF_SYSFS_ROOT=$C6I cpuset_numa_nodes 64-69)"
check "c6i: 32-39,96-103 on node 1" "1" "$(PERF_SYSFS_ROOT=$C6I cpuset_numa_nodes 32-39,96-103)"
check "c6i: 30-33 straddles" "0 1" "$(PERF_SYSFS_ROOT=$C6I cpuset_numa_nodes 30-33)"
check "c6i: an absent cpu is rc 2" "2" "$(PERF_SYSFS_ROOT=$C6I cpuset_numa_nodes 200 >/dev/null; echo $?)"
check "unreadable map is rc 1" "1" "$(PERF_SYSFS_ROOT=$T/none cpuset_numa_nodes 0 >/dev/null; echo $?)"
check "no-NUMA kernel: one node 0" "0" "$(PERF_SYSFS_ROOT=$NONUMA numa_nodes)"
check "no-NUMA kernel: 0-7 on node 0" "0" "$(PERF_SYSFS_ROOT=$NONUMA cpuset_numa_nodes 0-7)"
check "c6i: node 1 physical core 0 + sibling" "32 96" "$(PERF_SYSFS_ROOT=$C6I numa_node_phys_cores 1 | head -1)"
check "c6i: node 0 has 32 physical cores" "32" "$(PERF_SYSFS_ROOT=$C6I numa_node_phys_cores 0 | wc -l | tr -d ' ')"
check "mems flag on node 1" "--cpuset-mems=1" "$(PERF_SYSFS_ROOT=$C6I numa_mems_flag 32-35)"
check "mems flag on no-NUMA kernel" "--cpuset-mems=0" "$(PERF_SYSFS_ROOT=$NONUMA numa_mems_flag 0-3)"
check "no mems flag for a straddle" "" "$(PERF_SYSFS_ROOT=$C6I numa_mems_flag 30-33)"
check "no mems flag when unreadable" "" "$(PERF_SYSFS_ROOT=$T/none numa_mems_flag 0-3)"
check "physical disjointness reads PERF_SYSFS_ROOT (c6i 0 and 64 are one core)" "1" \
  "$(PERF_SYSFS_ROOT=$C6I cpusets_physically_disjoint a 0 b 64 >/dev/null 2>&1; echo $?)"
check "physical disjointness: c6i package disambiguates core_id 0 (cpus 0 and 32)" "0" \
  "$(PERF_SYSFS_ROOT=$C6I cpusets_physically_disjoint a 0 b 32 >/dev/null 2>&1; echo $?)"

echo "--- 2. the guards"
rc() { "$@" >/dev/null 2>&1 && echo 0 || echo 1; }
check "single node: c6i SUT 0-5" "0" "$(PERF_SYSFS_ROOT=$C6I rc assert_cpuset_single_node server 0-5)"
check "single node: c6i straddle 30-33 FAILS" "1" "$(PERF_SYSFS_ROOT=$C6I rc assert_cpuset_single_node server 30-33)"
check "single node: c6i 0-5,96 FAILS" "1" "$(PERF_SYSFS_ROOT=$C6I rc assert_cpuset_single_node server 0-5,96)"
check "single node: absent cpu FAILS" "1" "$(PERF_SYSFS_ROOT=$C6I rc assert_cpuset_single_node server 0,200)"
check "single node: reversed range FAILS" "1" "$(PERF_SYSFS_ROOT=$C6I rc assert_cpuset_single_node server 9-3)"
check "single node: unpinned on 2 nodes FAILS" "1" "$(PERF_SYSFS_ROOT=$C6I rc assert_cpuset_single_node server "")"
check "single node: unpinned on 1 node ok" "0" "$(PERF_SYSFS_ROOT=$C5 rc assert_cpuset_single_node server "")"
check "single node: c5 0-5 ok" "0" "$(PERF_SYSFS_ROOT=$C5 rc assert_cpuset_single_node server 0-5)"
check "single node: unreadable off-CI warns, passes" "0" "$(PERF_SYSFS_ROOT=$T/none rc assert_cpuset_single_node server 0-5)"
check "single node: unreadable in CI FAILS" "1" "$(BUILDKITE=true PERF_SYSFS_ROOT=$T/none rc assert_cpuset_single_node server 0-5)"
check "disjoint: c6i 0-5 vs node 1" "0" "$(PERF_SYSFS_ROOT=$C6I rc assert_nodes_disjoint server 0-5 k6 32-63)"
check "disjoint: c6i 0-5 vs 8-11 FAILS" "1" "$(PERF_SYSFS_ROOT=$C6I rc assert_nodes_disjoint server 0-5 k6 8-11)"
check "disjoint: c6i 0-5 vs a straddle FAILS" "1" "$(PERF_SYSFS_ROOT=$C6I rc assert_nodes_disjoint server 0-5 k6 30-40)"
check "disjoint: c5 0-5 vs 7-22 FAILS (one node)" "1" "$(PERF_SYSFS_ROOT=$C5 rc assert_nodes_disjoint server 0-5 k6 7-22)"
check "disjoint: wrong arity FAILS" "1" "$(PERF_SYSFS_ROOT=$C6I rc assert_nodes_disjoint server 0-5 k6)"
check "disjoint: unreadable in CI FAILS" "1" "$(BUILDKITE=true PERF_SYSFS_ROOT=$T/none rc assert_nodes_disjoint a 0 b 1)"
check "check: c6i other, k6 on node 1" "0" "$(PERF_SYSFS_ROOT=$C6I rc numa_placement_check other 0-5 32-39,96-103 40-47,104-111)"
check "check: c6i other, one k6 on node 0 FAILS" "1" "$(PERF_SYSFS_ROOT=$C6I rc numa_placement_check other 0-5 32-39 8-11)"
check "check: c6i other, a k6 straddling FAILS" "1" "$(PERF_SYSFS_ROOT=$C6I rc numa_placement_check other 0-5 28-35)"
check "check: 3 nodes, other, a k6 straddling nodes 1 and 2 FAILS" "1" "$(PERF_SYSFS_ROOT=$TRI rc numa_placement_check other 0-1 6-9)"
check "check: 3 nodes, other, k6 on nodes 1 and 2 separately" "0" "$(PERF_SYSFS_ROOT=$TRI rc numa_placement_check other 0-1 4-7 8-11)"
check "check: c6i same, k6 on node 0" "0" "$(PERF_SYSFS_ROOT=$C6I rc numa_placement_check same 0-5 8-13,72-77)"
check "check: c6i same, k6 on node 1 FAILS" "1" "$(PERF_SYSFS_ROOT=$C6I rc numa_placement_check same 0-5 32-39)"
check "check: c5 other, k6 on the only node" "0" "$(PERF_SYSFS_ROOT=$C5 rc numa_placement_check other 0-5 7-10,31-34)"
check "check: unreadable off-CI warns once, passes" "0|1" \
  "$(PERF_SYSFS_ROOT=$T/none rc numa_placement_check other 0-5 7-10)|$(PERF_SYSFS_ROOT=$T/none numa_placement_check other 0-5 7-10 2>&1 | grep -c WARNING)"
check "check: unreadable in CI FAILS" "1" "$(BUILDKITE=true PERF_SYSFS_ROOT=$T/none rc numa_placement_check other 0-5 7-10)"
check "numa=off c6i: 2 sockets, 1 node" "2|1" "$(PERF_SYSFS_ROOT=$NUMAOFF numa_package_count)|$(PERF_SYSFS_ROOT=$NUMAOFF numa_node_count)"
check "check: numa=off (more sockets than nodes) in CI FAILS" "1" \
  "$(BUILDKITE=true PERF_SYSFS_ROOT=$NUMAOFF rc numa_placement_check other 0-5 7-10,31-34)"
check "check: numa=off off-CI warns, passes" "0|1" \
  "$(PERF_SYSFS_ROOT=$NUMAOFF rc numa_placement_check other 0-5 7-10)|$(PERF_SYSFS_ROOT=$NUMAOFF numa_placement_check other 0-5 7-10 2>&1 | grep -c 'sockets but 1 NUMA node')"
check "check: SNC, k6 on another node of the SUT's socket FAILS" "1" "$(BUILDKITE=true PERF_SYSFS_ROOT=$SNC rc numa_placement_check other 0-1 4-5)"
check "check: SNC, k6 on the other socket" "0" "$(BUILDKITE=true PERF_SYSFS_ROOT=$SNC rc numa_placement_check other 0-1 8-9 10-11)"
check "check: c6i in CI (sockets = nodes)" "0" "$(BUILDKITE=true PERF_SYSFS_ROOT=$C6I rc numa_placement_check other 0-5 32-39)"
check "check: c5 in CI (one socket, one node)" "0" "$(BUILDKITE=true PERF_SYSFS_ROOT=$C5 rc numa_placement_check other 0-5 7-10,31-34)"
check "check: bad mode FAILS" "1" "$(PERF_SYSFS_ROOT=$C6I rc numa_placement_check sideways 0-5 32-39)"
check "check: no k6 cpuset FAILS" "1" "$(PERF_SYSFS_ROOT=$C6I rc numa_placement_check other 0-5)"

echo "--- 3. the two-socket layout"
L="$(PERF_SYSFS_ROOT=$C6I numa_split_layout 6 4 other)"
check "c6i: SUT on node 0 cores 0-5, siblings idle" "0-5" "$(layout_value server "$L")"
check "c6i: upstream on the next core" "6" "$(layout_value upstream "$L")"
check "c6i: Prometheus on the next core + sibling" "7,71" "$(layout_value prometheus "$L")"
check "c6i: 4 x 8 k6 cores on node 1, both threads" \
  "32-39,96-103;40-47,104-111;48-55,112-119;56-63,120-127" "$(layout_value k6 "$L")"
check "c6i: nodes" "0 1" "$(layout_value sut_node "$L") $(layout_value k6_node "$L")"
check "c6i: N=8 -> 8 x 4" "32-35,96-99;36-39,100-103;40-43,104-107;44-47,108-111;48-51,112-115;52-55,116-119;56-59,120-123;60-63,124-127" \
  "$(layout_value k6 "$(PERF_SYSFS_ROOT=$C6I numa_split_layout 6 8 other)")"
check "c6i: 4 x 6 (A/B parity with same)" "32-37,96-101;38-43,102-107;44-49,108-113;50-55,114-119" \
  "$(layout_value k6 "$(PERF_SYSFS_ROOT=$C6I numa_split_layout 6 4 other 6)")"
check "c6i same: k6 on node 0's 24 free cores" "8-13,72-77;14-19,78-83;20-25,84-89;26-31,90-95" \
  "$(layout_value k6 "$(PERF_SYSFS_ROOT=$C6I numa_split_layout 6 4 same)")"
LA="$(PERF_SYSFS_ROOT=$ALT numa_split_layout 6 4 other)"
check "alt numbering: SUT one thread per core" "0,2,4,6,8,10" "$(layout_value server "$LA")"
check "alt numbering: Prometheus core + sibling" "14-15" "$(layout_value prometheus "$LA")"
check "alt numbering: k6 on node 1" "32-39;40-47;48-55;56-63" "$(layout_value k6 "$LA")"
LS="$(PERF_SYSFS_ROOT=$SWAP numa_split_layout 6 4 other)"
check "node 0 on the high cpus: the SUT, upstream and Prometheus follow node 0" "8-13|14|15" \
  "$(layout_value server "$LS")|$(layout_value upstream "$LS")|$(layout_value prometheus "$LS")"
check "node 0 on the high cpus: k6 on node 1" "0-1;2-3;4-5;6-7" "$(layout_value k6 "$LS")"
LN="$(PERF_SYSFS_ROOT=$SNC numa_split_layout 2 2 other)"
check "SNC: k6 on node 2 (the other socket), not node 1" "2|8-9;10-11" "$(layout_value k6_node "$LN")|$(layout_value k6 "$LN")"
check "SNC on one socket: no node for k6, rc 2" "2" "$(PERF_SYSFS_ROOT=$SNC1 numa_split_layout 1 1 other >/dev/null 2>&1; echo $?)"
check "one node: bad numbers still rc 1, not a number error" "1|" \
  "$(PERF_SYSFS_ROOT=$C5 numa_split_layout 6 0 other >/dev/null 2>"$T/err"; echo $?)|$(cat "$T/err")"
check "c5 (one node): rc 1, the caller keeps its layout" "1" "$(PERF_SYSFS_ROOT=$C5 numa_split_layout 6 4 other >/dev/null 2>&1; echo $?)"
check "unreadable: rc 1" "1" "$(PERF_SYSFS_ROOT=$T/none numa_split_layout 6 4 other >/dev/null 2>&1; echo $?)"
check "does not fit: 4 x 9 on node 1 is rc 2" "2" "$(PERF_SYSFS_ROOT=$C6I numa_split_layout 6 4 other 9 >/dev/null 2>&1; echo $?)"
check "does not fit: SUT 31 cores is rc 2" "2" "$(PERF_SYSFS_ROOT=$C6I numa_split_layout 31 4 other >/dev/null 2>&1; echo $?)"
check "bad procs is rc 2" "2" "$(PERF_SYSFS_ROOT=$C6I numa_split_layout 6 0 other >/dev/null 2>&1; echo $?)"
check "every c6i layout cpuset is physically disjoint" "0" \
  "$(PERF_SYSFS_ROOT=$C6I rc cpusets_physically_disjoint server 0-5 upstream 6 prometheus 7,71 \
       k0 32-39,96-103 k1 40-47,104-111 k2 48-55,112-119 k3 56-63,120-127)"

echo "--- 4. rw-multi-k6-sweep.sh resolves its placement (PERF_RW_TEST_PLACEMENT_ONLY, stub docker)"
mkdir -p "$T/bin"
printf '#!/usr/bin/env bash\necho "docker $*" >> "$STUB_DOCKER_LOG"\nexit 1\n' > "$T/bin/docker"
chmod +x "$T/bin/docker"
place() { # sysfs host_cores env... -> JSON in $R, exit code in $RC
  local root="$1" hc="$2"; shift 2
  RC=0
  env PATH="$T/bin:$PATH" STUB_DOCKER_LOG="$T/docker.log" PERF_SYSFS_ROOT="$root" PERF_TEST_HOST_CORES="$hc" \
    PERF_RW_TEST_PLACEMENT_ONLY=true PERF_RW_K6_GOMEMLIMIT=off PERF_RW_REPO_ROOT="$REPO_ROOT" "$@" \
    bash "$HARNESS" >"$T/out.json" 2>"$T/stderr.log" || RC=$?
  R="$(cat "$T/out.json")"
}
q() { jq -r "$1" <<<"$R" 2>/dev/null || echo "<no json>"; }
: > "$T/docker.log"
# Today's default strings, byte for byte, on every one-node or unreadable host.
C5_K6="7-10,31-34;11-14,35-38;15-18,39-42;19-22,43-46"
place "$C5" 48
check "c5: exit 0" "0" "$RC"
check "c5: server/prometheus/k6 are today's strings" "0-5|23,47|$C5_K6" "$(q '"\(.server)|\(.prometheus)|\(.k6 | join(";"))"')"
check "c5: layout single_node, nodes 0/0" "single_node|1|0|0|true" "$(q '.placement | "\(.layout)|\(.numa_nodes)|\(.sut_node)|\(.k6_node)|\(.baseline_eligible)"')"
check "c5: every container's memory on node 0" "--cpuset-mems=0|--cpuset-mems=0|--cpuset-mems=0|4" \
  "$(q '"\(.mems.server)|\(.mems.prometheus)|\(.mems.xcheck)|\(.mems.k6 | map(select(. == "--cpuset-mems=0")) | length)"')"
place "$T/none" 48
check "unreadable, 48 cpus: today's strings" "0-5|23,47|$C5_K6|topology_unknown" "$(q '"\(.server)|\(.prometheus)|\(.k6 | join(";"))|\(.placement.layout)"')"
check "unreadable: no --cpuset-mems, numa_nodes null" "||null" "$(q '"\(.mems.server)|\(.mems.k6 | join(""))|\(.placement.numa_nodes)"')"
place "$T/none" 12
check "unreadable, 12 cpus (Docker Desktop): today's proportional layout" "0-3|4|5-6;7-8;9-10" "$(q '"\(.server)|\(.prometheus)|\(.k6 | join(";"))"')"
place "$T/none" 12 PERF_RW_PROCS=2
check "unreadable, 12 cpus, N=2: today's layout" "5-7;8-10" "$(q '.k6 | join(";")')"
place "$C6I" 128
check "c6i: exit 0" "0" "$RC"
check "c6i: SUT, Prometheus, k6 from the node map" "0-5|7,71|32-39,96-103;40-47,104-111;48-55,112-119;56-63,120-127" \
  "$(q '"\(.server)|\(.prometheus)|\(.k6 | join(";"))"')"
check "c6i: upstream default on the next core" "6" "$(q '.upstream_default')"
check "c6i: placement record" "numa_split|2|0|1|true" "$(q '.placement | "\(.layout)|\(.numa_nodes)|\(.sut_node)|\(.k6_node)|\(.baseline_eligible)"')"
check "c6i: memory per container's node" "--cpuset-mems=0|--cpuset-mems=0|--cpuset-mems=1|--cpuset-mems=1" \
  "$(q '"\(.mems.server)|\(.mems.prometheus)|\(.mems.xcheck)|\(.mems.k6 | unique | join(","))"')"
place "$C6I" 128 PERF_RW_PROCS=2
check "c6i: N=2 -> 2 x 16" "32-47,96-111;48-63,112-127" "$(q '.k6 | join(";")')"
place "$C6I" 128 PERF_K6_NUMA_NODE=same
check "c6i same: k6 on node 0, marked non-baseline" "8-13,72-77;14-19,78-83;20-25,84-89;26-31,90-95|numa_same_node|0|false" \
  "$(q '"\(.k6 | join(";"))|\(.placement.layout)|\(.placement.k6_node)|\(.placement.baseline_eligible)"')"
place "$C6I" 128 PERF_K6_NUMA_NODE=same PERF_RW_K6_CORES_PER_PROC=6
check "c6i other at the same-socket arm's 6 cores per process (A/B parity)" "0" "$RC"
place "$C6I" 128 PERF_RW_K6_CPUSETS="8-11;12-15"
check "c6i: explicit k6 on the SUT's node FAILS" "1" "$RC"
check "  ... naming the NUMA guard" "yes" "$(grep -q 'NUMA placement guard' "$T/stderr.log" && echo yes || echo no)"
place "$C6I" 128 PERF_RW_SERVER_CPUS="0-4,60" PERF_RW_PROCS=2 PERF_RW_K6_CORES_PER_PROC=4 # disjoint cores, two nodes
check "c6i: a SUT straddling nodes FAILS" "1" "$RC"
check "  ... naming the straddle" "yes" "$(grep -q 'straddles NUMA nodes' "$T/stderr.log" && echo yes || echo no)"
place "$C6I" 128 PERF_RW_PROM_CPUS="7,60" PERF_RW_PROCS=2 PERF_RW_K6_CORES_PER_PROC=4 # disjoint cores, two nodes
check "c6i: Prometheus straddling nodes FAILS" "1" "$RC"
check "  ... naming Prometheus" "yes" "$(grep -q 'prometheus cpuset .7,60. straddles' "$T/stderr.log" && echo yes || echo no)"
place "$C6I" 128 PERF_RW_XCHECK_CPUS="0-3"
check "c6i: the cross-check k6 on the SUT's node FAILS" "1" "$RC"
place "$C6I" 128 PERF_RW_SERVER_CPUS="0-5" PERF_RW_K6_CPUSETS="32-35;36-39" PERF_RW_PROCS=2
check "c6i: explicit, valid -> layout explicit" "0|explicit|1" "$(echo "$RC")|$(q '.placement.layout')|$(q '.placement.k6_node')"
place "$C6I" 128 PERF_RW_K6_CORES_PER_PROC=9
check "c6i: a layout that does not fit FAILS" "1" "$RC"
place "$C6I" 128 PERF_K6_NUMA_NODE=sideways
check "bad PERF_K6_NUMA_NODE FAILS" "2" "$RC"
place "$NUMAOFF" 128 BUILDKITE=true
check "numa=off c6i in CI: the multi-k6 arm FAILS" "1" "$RC"
check "  ... naming the socket/node mismatch" "yes" "$(grep -q '2 sockets but 1 NUMA node' "$T/stderr.log" && echo yes || echo no)"
place "$SNC" 16 PERF_RW_SERVER_CPUS=0-1 PERF_RW_PROM_CPUS=3 PERF_RW_K6_CPUSETS="4-5;6-7" PERF_RW_PROCS=2
check "SNC: explicit k6 on the SUT's socket FAILS" "1" "$RC"
place "$SNC" 16 PERF_RW_SERVER_CPUS=0-1 PERF_RW_PROM_CPUS=3 PERF_RW_K6_CPUSETS="8-9;10-11" PERF_RW_PROCS=2
check "SNC: explicit k6 on the other socket" "0|2" "$RC|$(q '.placement.k6_node')"
place "$C5" 48 PERF_RW_K6_CPUSETS="5-8;9-12"
check "the physical-core guard still runs (k6 on the SUT's core 5)" "1" "$RC"
check "nothing was started (stub docker never called)" "0" "$(wc -l < "$T/docker.log" | tr -d ' ')"

echo "--- 5. perf-percore.sh hardware matrix and per-core placement (PERF_PERCORE_TEST_PLACEMENT_ONLY)"
printf '#!/usr/bin/env bash\necho "docker $*" >> "$STUB_DOCKER_LOG"\nexit 0\n' > "$T/bin/docker"
percore() { # sysfs host_cores mode env... -> JSON in $R, exit code in $RC
  local root="$1" hc="$2" mode="$3"; shift 3
  RC=0
  env PATH="$T/bin:$PATH" STUB_DOCKER_LOG="$T/docker.log" PERF_SYSFS_ROOT="$root" PERF_TEST_HOST_CORES="$hc" \
    PERF_PERCORE_TEST_PLACEMENT_ONLY=true PERF_PERCORE_MODE="$mode" PERF_PERCORE_REPO_ROOT="$REPO_ROOT" "$@" \
    bash "$PERCORE" >"$T/out.json" 2>"$T/stderr.log" || RC=$?
  R="$(cat "$T/out.json")"
}
percore "$C5" 48 hw_matrix
check "c5 matrix: exit 0" "0" "$RC"
check "c5 matrix: today's client layout" "$C5_K6|23,47|single_node" "$(q '"\(.k6_cpusets | join(";"))|\(.prometheus_cpus)|\(.layout)"')"
check "c5 matrix: SUT on cores 0..C-1" "0|0,1|0,1|0,1,2|0,1,2,3|0,1,2,3,4,5" "$(q '[.points[].server_cpus] | join("|")')"
check "c5 matrix: every point placed on node 0" "6|0|0" "$(q '"\(.points | length)|\([.points[].placement.sut_node] | unique | join(","))|\([.points[].placement.k6_node] | unique | join(","))"')"
percore "$C6I" 128 hw_matrix
check "c6i matrix: exit 0" "0" "$RC"
check "c6i matrix: the multi-k6 arm's client layout" "32-39,96-103;40-47,104-111;48-55,112-119;56-63,120-127|7,71|numa_split" \
  "$(q '"\(.k6_cpusets | join(";"))|\(.prometheus_cpus)|\(.layout)"')"
check "c6i matrix: SUT on node 0 cores" "0|0,1|0,1|0,1,2|0,1,2,3|0,1,2,3,4,5" "$(q '[.points[].server_cpus] | join("|")')"
check "c6i matrix: points record SUT node 0, k6 node 1" "0|1|--cpuset-mems=0|--cpuset-mems=1" \
  "$(q '"\([.points[].placement.sut_node] | unique | join(","))|\([.points[].placement.k6_node] | unique | join(","))|\([.points[].mems.server] | unique | join(","))|\([.points[].mems.k6] | unique | join(","))"')"
percore "$C6I" 128 hw_matrix PERF_K6_NUMA_NODE=same
check "c6i matrix same: k6 on node 0, non-baseline" "8-13,72-77;14-19,78-83;20-25,84-89;26-31,90-95|false" \
  "$(q '"\(.k6_cpusets | join(";"))|\([.points[].placement.baseline_eligible] | unique | join(","))"')"
percore "$C6I" 128 hw_matrix PERF_HW_MATRIX_K6_CPUSETS="8-11;12-15" PERF_HW_MATRIX_PROM_CPUS="7"
check "c6i matrix: k6 on the SUT's node -> every point a NUMA failure" "0|6|failure" \
  "$(q '"\(.points | length)|\(.skipped | length)|\([.skipped[].type] | unique | join(","))"')"
percore "$C6I" 128 hw_matrix PERF_HW_MATRIX_PROM_CPUS="7,60" PERF_HW_MATRIX_PROCS=2 PERF_HW_MATRIX_K6_CORES_PER_PROC=4
check "c6i matrix: Prometheus straddling nodes -> every point a NUMA failure" "0|6|failure" \
  "$(q '"\(.points | length)|\(.skipped | length)|\([.skipped[].type] | unique | join(","))"')"
percore "$C6I" 128 percore PERF_PERCORE_CORES=1,4
check "c6i per-core: single k6 on node 1, one thread per core minus the reserve" "0|0,1,2,3|1|31" \
  "$(q '"\(.points[0].server_cpus)|\(.points[1].server_cpus)|\([.points[].placement.k6_node] | unique | join(","))|\(.points[0].k6_cpus | split(",") | length)"')"
check "c6i per-core: k6 cpus all on node 1" "1" "$(PERF_SYSFS_ROOT=$C6I cpuset_numa_nodes "$(q '.points[0].k6_cpus')")"
percore "$SWAP" 16 percore PERF_PERCORE_CORES=1,4
check "node 0 on the high cpus, per-core: SUT on node 0, k6 on node 1" "8|8,9,10,11|0,1,2,3,4,5,6" \
  "$(q '"\(.points[0].server_cpus)|\(.points[1].server_cpus)|\(.points[0].k6_cpus)"')"
percore "$SNC" 16 percore PERF_PERCORE_CORES=1
check "SNC per-core: k6 on node 2 (the other socket), not node 1" "0|8,9,10|2" \
  "$(q '"\(.points[0].server_cpus)|\(.points[0].k6_cpus)|\(.points[0].placement.k6_node)"')"
percore "$C5" 48 percore PERF_PERCORE_CORES=1,4
check "c5 per-core: unchanged (SUT 0, k6 1..22)" "0|1-22" \
  "$(q '.points[0].server_cpus')|$(compress_cpulist "$(q '.points[0].k6_cpus' | tr ',' ' ')")"

echo "--- 5b. hardware-matrix re-assembly restores the perf-xl placement from matrix-inputs.json"
reassemble_xl() { # mode(other|same) -> the re-assembled block in $R
  local W="$T/xl-$1" layout=numa_split
  [ "$1" = same ] && layout=numa_same_node
  mkdir -p "$W"
  jq -n --arg mode "$1" --arg layout "$layout" '{source:"live", env:{PERF_HW_MATRIX:"1:512m,2:1g"},
    resolved:{host_cores:128, host_physical_cores:64, topology_known:true, k6_physical_cores:32,
              image:"mockserver/mockserver:test", image_java_tool_options:"", java_tool_options:"", rig_paused:true,
              hw_k6_cpusets:"32-39,96-103;40-47,104-111;48-55,112-119;56-63,120-127", hw_prom_cpus:"7,71",
              layout:$layout, k6_numa_node:$mode}}' > "$W/matrix-inputs.json"
  printf '%s\n' '{"cores":1,"key":"1c-512m","memory_limit":"512m","reason":"x","type":"failure"}' \
    '{"cores":2,"key":"2c-1g","memory_limit":"1g","reason":"x","type":"failure"}' > "$W/skipped.ndjson"
  RC=0
  # Re-assembled on a host with no topology: every placement fact must come from the inputs.
  env PATH="$T/bin:$PATH" STUB_DOCKER_LOG="$T/docker.log" PERF_SYSFS_ROOT="$T/none" PERF_PERCORE_MODE=hw_matrix \
    PERF_HW_MATRIX_REASSEMBLE_DIR="$W" PERF_PERCORE_REPO_ROOT="$REPO_ROOT" bash "$PERCORE" "$T/xl.json" 2>"$T/stderr.log" || RC=$?
  R="$(cat "$T/xl.json" 2>/dev/null)"
}
reassemble_xl other
check "re-assembly: exit 0" "0" "$RC"
check "re-assembly: perf-xl client placement restored" "32-39,96-103;40-47,104-111;48-55,112-119;56-63,120-127|7,71|numa_split|other|true" \
  "$(q '"\(.client_placement.k6_cpusets | join(";"))|\(.client_placement.prometheus_cpus)|\(.client_placement.layout)|\(.client_placement.k6_numa_node)|\(.baseline_eligible)"')"
reassemble_xl same
check "re-assembly of a same-socket matrix: not baseline-eligible" "numa_same_node|same|false" \
  "$(q '"\(.client_placement.layout)|\(.client_placement.k6_numa_node)|\(.baseline_eligible)"')"

echo "--- 6. every pinned container also pins its memory to that cpuset's node"
# <file> <cpuset expr> <mems var> <the mems var's definition>: one docker run per row, and no
# --cpuset-cpus line outside the table (a new container without its mems flag fails here).
RW="$HARNESS"
MEMS_TABLE="$RW|\$PROM_CPUS|PROM_MEMS|PROM_MEMS=\"\$(numa_mems_flag \"\$PROM_CPUS\")\"
$RW|\$XCHECK_CPUS|XCHECK_MEMS|XCHECK_MEMS=\"\$(numa_mems_flag \"\$XCHECK_CPUS\")\"
$RW|\$SERVER_CPUS|SUT_MEMS|SUT_MEMS=\"\$(numa_mems_flag \"\$SERVER_CPUS\")\"
$RW|\${set_arr[\$i]}|mems|mems=\"\$(numa_mems_flag \"\${set_arr[\$i]}\")\"
$PERCORE|\$1|mems|local mems; mems=\"\$(numa_mems_flag \"\$1\")\"
$PERCORE|\$SCPU|SUT_MEMS|SUT_MEMS=\"\$(numa_mems_flag \"\$SCPU\")\"
$PERCORE|\$KCPU|K6_MEMS|K6_MEMS=\"\$(numa_mems_flag \"\${KCPU//;/,}\")\""
while IFS='|' read -r f cpus var def; do
  n="$(grep -cF -- "--cpuset-cpus=\"$cpus\" \${$var:+\"\$$var\"}" "$f" || true)"
  [ "$n" -ge 1 ] && ok "$(basename "$f"): --cpuset-cpus=\"$cpus\" carries \$$var ($n run(s))" \
    || bad "$(basename "$f"): no docker run pins --cpuset-cpus=\"$cpus\" with \${$var:+\"\$$var\"}"
  grep -qF -- "$def" "$f" && ok "  ... and \$$var is the node of that cpuset" || bad "$(basename "$f"): \$$var is not defined as: $def"
done <<<"$MEMS_TABLE"
for f in "$RW" "$PERCORE"; do
  stray="$(grep -n -- '--cpuset-cpus=' "$f" | grep -v -- '_MEMS:+"\|{mems:+"' || true)"
  check "$(basename "$f"): no --cpuset-cpus without a mems flag" "" "$stray"
done

echo
if [ "$FAILS" -gt 0 ]; then echo ":x: $FAILS check(s) failed" >&2; exit 1; fi
echo "all perf-cpu-topology checks passed"
