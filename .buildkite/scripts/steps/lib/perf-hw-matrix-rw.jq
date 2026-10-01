# perf-hw-matrix-rw.jq — one hardware-matrix point (item 27) from a scripts/rw-multi-k6-sweep.sh
# result. Input: the rw result (sweep + derive_saturation's .saturation). Output: the point
# fields lib/perf-percore.sh's memory/survival block and perf-website-figures.jq read.
#
# Args (--argjson): $cores; $hc50 / $hc99 = perf-website-figures.jq's .headline over this result
# without / with the p99 bound (null when none); $p99_max_ms; $maxlog; $body.
#
# Rig validity and every lower-bound reason come from .saturation (derive_saturation), never a
# local rule. The published ceiling is the p50 rule ($hc50); the p99-bounded one is recorded only.

def frac($a; $b): if ($a | type) != "number" or ($b | type) != "number" or $b <= 0 then null
                  else (($a / $b) * 1000 | round) / 1000 end;
def num: if type == "number" then . else null end;

(.saturation // {}) as $sat
| ((.sweep.points // []) | map(select(type == "object" and .offered_rps != null)) | sort_by(.offered_rps)) as $pts
| (($sat.ladder // []) | sort_by(.offered_rps)) as $lad
| ($hc50.healthy_ceiling_rps // null | num) as $hc
| (($sat.server_pin_pct // 0) | if . > 0 then . else $cores * 100 end) as $sutpin
| ($sat.client_cpu_ceiling_pct | num) as $k6ceil
| ([ $pts[].offered_rps ] | max) as $top
| [ $pts[] | . as $p | ([ $lad[] | select(.offered_rps == $p.offered_rps) ] | first) as $l
    | { offered_rps, achieved_rps, sample_count, measured_sample_count, settle_excluded,
        p50_ms, p95_ms, p99_ms, p999_ms, error_rate: (.error_rate // 0), dropped_iterations: (.dropped_iterations // 0),
        vus_active_max, vus_active_p95,
        k6_cpu_pct: ($l.k6_cpu_pct // null), k6_cpu_pct_max: ($l.k6_cpu_pct_max // null),
        sut_cpu_pct: ($l.server_cpu_pct // null), sut_cpu_frac_of_pin: frac($l.server_cpu_pct; $sutpin),
        rig_valid: ($l.rig_valid == true), client_limited: $l.client_limited,
        dropped_fraction: ($l.dropped_fraction // null), vu_occupancy: ($l.vu_occupancy // null),
        exclude_reason: ($l.exclude_reason // (if $l == null then "no derive_saturation verdict for this rung" else null end)),
        retention_residence_s: (if (.achieved_rps // 0) > 0 then (($maxlog / .achieved_rps) * 1000 | round) / 1000 else null end) } ] as $rungs
| ([ $rungs[] | select(.rig_valid) ] | max_by(.achieved_rps)) as $peakrung
| ([ $rungs[] | select(.offered_rps == $hc) ] | first) as $hcrung
# The first rung above the ceiling decides whether overload was measured: rig-valid there is a
# server limit; client-limited there (derive_saturation) means the rig stopped the ladder.
| ([ $lad[] | select($hc != null and .offered_rps > $hc) ] | first) as $next
| ($next != null and $next.client_limited == true) as $next_client
| ($next_client and ($next.k6_cpu_pct | num) != null and $k6ceil != null and $next.k6_cpu_pct > $k6ceil) as $next_k6sat
| ($next_client and ($next.server_cpu_pct | num) != null and $next.server_cpu_pct < 0.85 * $sutpin) as $next_srv_idle
| ([ $rungs[] | .sut_cpu_frac_of_pin | num ] | max
   | if . == null then null elif . >= 0.85 then "server" else "load_path_or_virtualization" end) as $limited_by
| {
    cores: $cores,
    ceiling_rps: $hc,
    healthy_ceiling_rps: $hc,
    healthy_ceiling_p50_ms: ($hc50.healthy_ceiling_p50_ms // null),
    healthy_ceiling_p95_ms: ($hc50.healthy_ceiling_p95_ms // null),
    healthy_ceiling_p99_ms: ($hcrung.p99_ms // null),
    healthy_ceiling_rule: "achieved>=0.95x offered, zero errors, p50<=3x flat-region p50",
    # Recorded, not published: the same rule plus p99 <= $p99_max_ms (programme item 44 decides any switch).
    healthy_ceiling_p99_bounded: {rps: ($hc99.healthy_ceiling_rps // null), p99_max_ms: $p99_max_ms,
                                  p99_ms: ($hc99.healthy_ceiling_p99_ms // null)},
    healthy_ceiling_rig_valid: (if $hcrung == null then null else $hcrung.rig_valid end),
    healthy_ceiling_client_cpu_pct: ($hcrung.k6_cpu_pct // null),
    healthy_ceiling_dropped_iterations: ($hcrung.dropped_iterations // null),
    rig_valid_peak_achieved_rps: ($sat.rig_valid_peak_achieved_rps // null),
    peak_offered_rps: ($peakrung.offered_rps // null),
    rps_per_core: (if $hc == null then null else (($hc / $cores) * 100 | round) / 100 end),
    peak_per_core: (if ($sat.rig_valid_peak_achieved_rps | num) == null then null
                    else (($sat.rig_valid_peak_achieved_rps / $cores) * 100 | round) / 100 end),
    sut_pin_pct: $sutpin,
    sut_cpu_peak_pct: ([ $rungs[] | .sut_cpu_pct | num ] | max),
    sut_cpu_at_peak_pct: ($peakrung.sut_cpu_pct // null),
    sut_cpu_frac_of_pin_at_peak: ($peakrung.sut_cpu_frac_of_pin // null),
    peak_limited_by: $limited_by,
    client_pin_pct: ($sat.client_pin_pct // null),
    saturation: {client_limited_from_rps: ($sat.client_limited_from_rps // null),
                 server_headroom_test: ($sat.server_headroom_test // null),
                 client_cpu_ceiling_pct: $k6ceil, server_pin_pct: ($sat.server_pin_pct // null),
                 rig_valid_rungs: ($sat.rig_valid_rungs // null)},
    retention: {
      assumed_max_log_entries: $maxlog,
      body_bytes: $body,
      retained_bytes_estimate: ($maxlog * $body),
      note: "count-bounded ring: retained bytes ~ maxLogEntries*body (≈constant vs rate); residence = maxLogEntries/achieved_rps lengthens as rps falls (see .ladder[].retention_residence_s)"
    },
    measurement: {
      client: "multik6",
      valid: (.valid == true),
      invalid_reasons: (.invalid_reasons // []),
      procs: (.method.procs // null),
      cross_check_equivalent: .cross_check.equivalent,
      # null, not false, when no rung had a single-process counterpart to compare.
    cross_run_agrees: (if (.cross_check.cross_run.compared // 0) == 0 then null else .cross_check.cross_run.agrees end),
      k6_cpu_us_per_request_mean: (.cpu.k6_cpu_us_per_request_mean // null),
      k6_runtime: (.config.k6_runtime // null),
      observed_max_start_skew_ms: (.method.observed_max_start_skew_ms // null)
    },
    ladder: $rungs,
    excluded: ($sat.excluded // []),
    # Read by perf-percore.sh in place of its own rule (a dead SUT has no ceiling to bound).
    # A ceiling is only a measured server limit when the SUT reached 85% of its pin on some rung
    # and the rung above it was measured validly; anything else is a lower bound.
    lower_bound_candidates: (if $hc == null then [] else [
        (if $next_k6sat then "client_cpu_limited" else empty end),
        (if $next_client and ($next_k6sat | not) and $next_srv_idle then "server_cpu_not_saturated" else empty end),
        # client-limited by derive_saturation, yet neither split applies (e.g. no server sample)
        (if $next_client and ($next_k6sat | not) and ($next_srv_idle | not) then "client_cpu_limited" else empty end),
        (if $limited_by == "load_path_or_virtualization" then "server_cpu_not_saturated" else empty end),
        # rig-invalid above the ceiling for a reason that is not the client (errors, idle-pool drops)
        (if $next != null and ($next.rig_valid != true) and ($next_client | not) then "overload_not_measured" else empty end),
        (if ($sat.server_headroom_test // "off") != "active" then "cpu_unverified" else empty end),
        (if $hc == $top then "ladder_top_reached" else empty end),
        (if ($sat.client_limited_from_rps | num) != null and $hc >= $sat.client_limited_from_rps
         then "client_limit_at_or_below_ceiling" else empty end)
      ] | unique end)
  }
