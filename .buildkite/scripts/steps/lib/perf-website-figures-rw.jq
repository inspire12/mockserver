# perf-website-figures-rw.jq — the published multi-k6 headline (performance-programme item 44).
# Input: a perf-xl arm-only result (perf-test-run.sh PERF_RUN_ARM=rw_multik6) that is a member of
# the published series (lib/perf-rw-multik6-series.jq returned []). Args: $now (published_utc), and
# $key, $publish_build, $dry_run, $series_unmet for the .source.published_from stamp, which
# lib/perf-website-figures-check.jq later requires of any committed multi-k6 headline.
# Output: {source, headline, headline_rule, throughput_ladder, problems}.
#
# It restates what the run itself concluded and recomputes no ceiling: .headline is the run's
# .serving_rw_multik6.headline, and the ladder holds only rungs the run judged rig-valid. Each
# rung carries both p99s: p99_ms is the whole rung (the same meaning as the single-k6 ladder) and
# p99_gc_masked_ms is the one the p99 bound read. .problems lists every way the result disagrees
# with itself; perf-test-compare.sh refuses to persist, and perf-website-publish.sh to publish,
# unless it is empty.

def round2: if . == null then null else (. * 100 | round) / 100 end;
def round3: if . == null then null else (. * 1000 | round) / 1000 end;
def commafy: (. // 0 | floor | tostring) | gsub("(?<=\\d)(?=(\\d{3})+$)"; ",");
def need($ok; $detail): if $ok then empty else $detail end;
# CPUs in a cpuset list such as "32-39,96-103"; null when it is not one.
def cpucount: if type != "string" or (test("^[0-9]+(-[0-9]+)?(,[0-9]+(-[0-9]+)?)*$") | not) then null
  else (split(",") | map(split("-") | map(tonumber) | if length == 2 then .[1] - .[0] + 1 else 1 end) | add) end;

(.serving_rw_multik6 // {}) as $rw
| .config as $c
| .agent as $a
| ($rw.headline // null) as $h
| ($rw.headline_rule // {}) as $rule
| ($rw.gc_masked // {}) as $gm
| (($rw.saturation.ladder // []) | map(select(.rig_valid == true) | .offered_rps)) as $rv
| ([ ($rw.sweep.points // [])[]
     | select(type == "object" and .offered_rps != null and .achieved_rps != null)
     | select(.offered_rps as $o | ($rv | index($o)) != null) ]
   | sort_by(.offered_rps)) as $pts
| def masked($o): ([ ($gm.rungs // [])[] | select(.offered_rps == $o) ] | first) // null;
  ($h.healthy_ceiling_rps // null) as $hc
| (if $hc == null then null else ([ $pts[] | select(.offered_rps == $hc) ] | first) end) as $hcpt
| (if $hc == null then null else masked($hc) end) as $hcm
# The highest rate up to which every counted rung kept its whole-rung p95 under 1 ms.
| (reduce $pts[] as $p ({ok: true, top: null};
     if .ok and ($p.p95_ms != null) and ($p.p95_ms < 1) then .top = $p.offered_rps else .ok = false end) | .top) as $sub_ms
| ([ $pts[] | select($hc != null and .offered_rps > $hc) | .offered_rps ] | first) as $next
| ($rule.condition_3.first_failure // null) as $ff
# The page calls the first failure "the next rate tested" and quotes its masked p99. The run picks
# it over every rung, counted or not, so it is published only when it is the next counted rung and
# has a masked figure; otherwise the page says nothing about it. The run is published either way.
| (if ($ff | type) == "object" and $next != null and $ff.offered_rps == $next and ($ff.p99_ms | type) == "number"
   then $ff else null end) as $ff
# CPUs each measuring k6 process was pinned to, stated only when observed and the same for all.
| ([ ($rw.placement.observed // [])[] | select((.role // "") | startswith("k6_main_")) | .cpuset_cpus | cpucount ] | unique) as $k6cpus
| {
    source: {
      published_utc: $now,
      run_timestamp_utc: .timestamp_utc,
      build_number: (.build_number // null),
      build_url: (.build_url // null),
      commit: ((.commit // "") | .[0:10]),
      harness_commit: ((.harness_commit // "") | .[0:10]),
      mockserver_version: ($c.mockserver_version // null),
      instance_type: ($a.instance_type // null),
      queue: ($a.queue // null),
      server_cpus: ($a.server_cpus // null),
      server_physical_cores: ($a.server_physical_cores // null),
      gc: ($c.gc // null),
      heap_max_mib: (if ($c.heap_max_bytes // null) == null then null else ($c.heap_max_bytes / 1048576 | floor) end),
      jdk: ($c.jdk // null),
      log_level: ($c.log_level // null),
      disable_system_out: ($c.disable_system_out // null),
      jvm_diagnostics: ($c.jvm_diagnostics // null),
      image_digest: ($c.image_digest // null),
      config_recorded: (($c // null) != null),
      sweep_latency_settle_s: ($rw.sweep.latency_window.settle_s // null),
      client: "multi_k6",
      k6_processes: ($rw.method.procs // null),
      k6_cpus_per_process: (if ($k6cpus | length) == 1 then $k6cpus[0] else null end),
      k6_gogc: ($rw.config.k6_runtime.gogc // null),
      placement: ($rw.placement.layout // null),
      baseline_ineligible_reasons: (.baseline_ineligible_reasons // null),
      published_from: {
        key: ($ARGS.named.key // null),
        publish_build_number: ($ARGS.named.publish_build // "" | if . == "" then null else . end),
        dry_run: (($ARGS.named.dry_run // "true") != "false"),
        series_unmet: ($ARGS.named.series_unmet // null)
      },
      note: "Measured on a dedicated CI host with MockServer pinned to its own physical cores and the load generators on the other CPU socket. Logging is reduced below the shipped INFO default for measurement (see log_level)."
    },
    headline: (if $h == null then null else {
      healthy_ceiling_rps: $h.healthy_ceiling_rps,
      healthy_ceiling_rps_display: ($h.healthy_ceiling_rps | commafy),
      healthy_ceiling_achieved_rps: ($h.healthy_ceiling_achieved_rps | round2),
      healthy_ceiling_p50_ms: ($h.healthy_ceiling_p50_ms | round3),
      healthy_ceiling_p95_ms: ($h.healthy_ceiling_p95_ms | round3),
      healthy_ceiling_p99_ms: ($h.healthy_ceiling_p99_ms | round3),
      healthy_ceiling_p99_whole_rung_ms: ($hcpt.p99_ms | round3),
      p99_max_ms: ($h.p99_max_ms // null),
      peak_achieved_rps: ($h.peak_achieved_rps | round2),
      peak_achieved_rps_display: (if $h.peak_achieved_rps == null then null else ($h.peak_achieved_rps | commafy) end),
      peak_offered_rps: ($h.peak_offered_rps // null),
      peak_offered_rps_display: (if $h.peak_offered_rps == null then null else ($h.peak_offered_rps | commafy) end),
      peak_p50_ms: ($h.peak_p50_ms | round3),
      peak_p95_ms: ($h.peak_p95_ms | round3),
      server_cores: ($h.server_cores // null),
      no_measured_overload: ($h.no_measured_overload == true),
      lower_bound: ($h.lower_bound == true),
      lower_bound_reason: ($h.lower_bound_reason // null),
      sub_ms_p95_through_rps: $sub_ms,
      sub_ms_p95_through_rps_display: (if $sub_ms == null then null else ($sub_ms | commafy) end),
      ladder_step_rps: (if $next == null or $hc == null then null else $next - $hc end),
      ladder_step_rps_display: (if $next == null or $hc == null then null else ($next - $hc | commafy) end)
    } end),
    headline_rule: {
      name: ($rule.name // null),
      p99_max_ms: ($rule.p99_max_ms // null),
      min_quiet_s: ($gm.min_quiet_s // null),
      unmasked_ceiling_rps: ($rule.unmasked_ceiling_rps // null),
      unmasked_ceiling_rps_display: (if ($rule.unmasked_ceiling_rps // null) == null then null else ($rule.unmasked_ceiling_rps | commafy) end),
      first_failure: (if $ff == null then null else {
        offered_rps: $ff.offered_rps,
        offered_display: ($ff.offered_rps | commafy),
        p99_gc_masked_ms: ($ff.p99_ms | round3),
        p99_ms: ($ff.unmasked_p99_ms | round3),
        quiet_s: ($ff.quiet_s // null) } end),
      first_failure_observed: ($rule.condition_3.ok == true)
    },
    throughput_ladder: [ $pts[] | masked(.offered_rps) as $m | {
        offered_rps: .offered_rps,
        offered_display: (.offered_rps | commafy),
        achieved_rps: (.achieved_rps | round2),
        achieved_display: (.achieved_rps | commafy),
        p50_ms: (.p50_ms | round3),
        p95_ms: (.p95_ms | round3),
        p99_ms: (.p99_ms | round3),
        p99_gc_masked_ms: ($m.p99_ms | round3),
        quiet_s: ($m.seconds.quiet // null),
        measured_s: ($m.seconds.measured // null),
        error_rate: (.error_rate // 0),
        error_pct: ((.error_rate // 0) * 100 | round),
        degraded: (($hc != null) and (.offered_rps > $hc))
      } ],
    problems: [
      need($h != null and ($hc | type) == "number" and $hc > 0; "the run states no headline ceiling"),
      need(($rule.name // null) == "gc_masked_p99"; "headline_rule.name is \($rule.name | tojson), not \"gc_masked_p99\""),
      need($gm.available == true and $gm.report_only == false;
           "gc_masked.available is \($gm.available | tojson) and report_only \($gm.report_only | tojson): the masked figure did not set the headline"),
      need($hc == ($gm.healthy_ceiling.rps // null);
           "headline ceiling \($hc | tojson) is not the GC-masked ceiling \($gm.healthy_ceiling.rps | tojson)"),
      need($hcpt != null; "the ceiling rung \($hc | tojson) is not a rung the run counted as rig-valid"),
      need(($h.p99_max_ms // null) != null and $h.p99_max_ms == ($rule.p99_max_ms // null);
           "headline.p99_max_ms \($h.p99_max_ms | tojson) is not headline_rule.p99_max_ms \($rule.p99_max_ms | tojson)"),
      need(($hcm.p99_ms // null) != null and ($h.p99_max_ms // null) != null and $hcm.p99_ms <= $h.p99_max_ms;
           "the ceiling rung masked p99 \($hcm.p99_ms | tojson) is missing or over the bound \($h.p99_max_ms | tojson)"),
      need(($hcm.p99_ms // null) == ($h.healthy_ceiling_p99_ms // null);
           "headline p99 \($h.healthy_ceiling_p99_ms | tojson) is not the ceiling rung masked p99 \($hcm.p99_ms | tojson)"),
      need(($hcpt.achieved_rps | round2) == ($h.healthy_ceiling_achieved_rps | round2);
           "headline achieved rate \($h.healthy_ceiling_achieved_rps | tojson) is not the ceiling rung \($hcpt.achieved_rps | tojson)"),
      need(($h.peak_achieved_rps // null) == null or ([ $pts[] | .achieved_rps | round2 ] | index($h.peak_achieved_rps | round2)) != null;
           "headline peak \($h.peak_achieved_rps | tojson) is not the achieved rate of a rig-valid rung")
    ]
  }
