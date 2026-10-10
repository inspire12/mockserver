# perf-rw-multik6-series.jq — is this perf result a member of the published multi-k6 series?
# Input: a perf-xl arm-only result (perf-test-run.sh PERF_RUN_ARM=rw_multik6).
# Output: an array of the criteria it fails, as "name: detail" strings; [] means it is a member.
#
# The published headline (performance-programme item 44) is the multi-k6 arm's GC-masked
# p99-bounded ceiling on perf-xl. perf-test-compare.sh persists a result to runs-perf-xl/ only
# when this returns [], and perf-website-publish.sh refuses to publish one unless it does, so
# a trial, an unmasked-rule run or a run on other hardware never reaches the history or the page.
# The criteria are the item-44 series rules (docs/code/performance-measurement.md, "The headline
# rule (item 44)"). A changed default (k6 GOGC, ladder, bound) starts a new series: change it here,
# and with it the rungs perf-website-publish.sh expects (RW_EXPECTED_CEILING_RPS and
# RW_ADJACENT_CEILING_RPS), which describe the runs that qualified the current series.

def need($name; $ok; $detail): if $ok then empty else "\($name): \($detail)" end;
(.serving_rw_multik6 // {}) as $rw
| [
    need("run_arm"; .run_arm == "rw_multik6"; "run_arm is \(.run_arm | tojson), not \"rw_multik6\""),
    need("queue"; (.agent.queue // null) == "perf-xl"; "agent.queue is \(.agent.queue | tojson), not \"perf-xl\""),
    need("valid"; (.validity.valid == true) and ($rw.valid == true);
         "validity.valid \(.validity.valid | tojson), serving_rw_multik6.valid \($rw.valid | tojson)"),
    # Every reason the run is not a daily baseline run, other than being arm-only, is a trial. The
    # one exception is the SUT's GC file log (PERF_JVM_DIAGNOSTICS=gc): the five runs that
    # qualified the series (630-634) carried it, so that tier is a member; deep is not.
    need("eligibility";
         ((.baseline_ineligible_reasons // null) | if type == "array" then sort else . end) as $r
         | $r == ["arm_only"] or ($r == ["arm_only", "jvm_diagnostics"] and .config.jvm_diagnostics == "gc");
         "baseline_ineligible_reasons is \(.baseline_ineligible_reasons | tojson) at jvm_diagnostics \(.config.jvm_diagnostics | tojson), not [\"arm_only\"] (plus \"jvm_diagnostics\" at the gc tier)"),
    need("image_fresh"; .config.image_stale == false; "config.image_stale is \(.config.image_stale | tojson)"),
    need("event_log_budget"; (.config.event_log_budget.method // null) == "shipped-default";
         "config.event_log_budget.method is \(.config.event_log_budget.method | tojson), not \"shipped-default\""),
    need("trial"; $rw.method.ab.trial == false; "serving_rw_multik6.method.ab.trial is \($rw.method.ab.trial | tojson)"),
    need("rule"; ($rw.headline_rule.name // null) == "gc_masked_p99";
         "headline_rule.name is \($rw.headline_rule.name | tojson), not \"gc_masked_p99\""),
    need("bound"; ($rw.headline_rule.p99_max_ms // null) == 10; "headline_rule.p99_max_ms is \($rw.headline_rule.p99_max_ms | tojson), not 10"),
    need("min_quiet"; ($rw.gc_masked.min_quiet_s // null) == 3; "gc_masked.min_quiet_s is \($rw.gc_masked.min_quiet_s | tojson), not 3"),
    need("ladder"; ($rw.method.ladder // null) == {profile: "multi_socket", source: "default"};
         "method.ladder is \($rw.method.ladder | tojson), not the default multi_socket ladder"),
    need("placement"; ($rw.placement.baseline_eligible // null) == true;
         "placement.baseline_eligible is \($rw.placement.baseline_eligible | tojson)"),
    need("vu_ceiling"; ($rw.config.k6_runtime.source.vu_ceiling // null) == "derived";
         "k6_runtime.source.vu_ceiling is \($rw.config.k6_runtime.source.vu_ceiling | tojson), not \"derived\""),
    need("k6_gogc"; (($rw.config.k6_runtime.gogc // null) == "1600") and (($rw.config.k6_runtime.source.gogc // null) == "default");
         "k6_runtime.gogc \($rw.config.k6_runtime.gogc | tojson) from \($rw.config.k6_runtime.source.gogc | tojson), not the default \"1600\""),
    need("headline"; (($rw.headline.healthy_ceiling_rps // null) | type) == "number" and $rw.headline.healthy_ceiling_rps > 0;
         "serving_rw_multik6.headline.healthy_ceiling_rps is \($rw.headline.healthy_ceiling_rps | tojson)")
  ]
