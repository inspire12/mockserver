# perf-rw-cross-check.jq: the rw-multi-k6 cross-check (performance programme item 31).
# Input {mode, live, pub, bytime, bytag, main, band, xcost, tol}, built by rw-multi-k6-sweep.sh
# (live: true) from its work files; a fixture or an offline re-derivation feeds the same object.
#   mode "levels": per rung, the published-window quantile levels that bound each by_time quantile
#   mode "cross":  the cross_check object (same_requests gates rw_cross_check_same_requests)
#
# by_time cannot hold exactly the published requests: its cut is the first push at or after the
# settle boundary, so it drops up to one push interval of steady requests (d of the published n).
# Removing d of n requests moves the q-quantile only within the published quantiles at levels
# [q(1-f), q+(1-q)f], f = d/n, whatever the latencies, so by_time is held to that band.
def num: type == "number";
.tol as $tol | $tol.abs_ms as $abs | (.band // {}) as $bandin | (.band != null) as $hasband
| (.live == true) as $live
| def within($a; $b; $t): ($a|num) and ($b|num) and ((($a - $b)|fabs) <= ([($t * ([$a, $b]|max)), $abs]|max));
  def ratio($a; $o): if ($a|num) and ($o|num) and $o > 0 then $a / $o else null end;
  def r3: if . == null then null else (. * 1000 | round) / 1000 end;
  def cmp($x; $y; $t5; $t9; $t99): {
      p50:{published:$x.p50_ms, remote_write:$y.p50_ms, ok:within($x.p50_ms; $y.p50_ms; $t5)},
      p95:{published:$x.p95_ms, remote_write:$y.p95_ms, ok:within($x.p95_ms; $y.p95_ms; $t9)},
      p99:{published:$x.p99_ms, remote_write:$y.p99_ms, ok:within($x.p99_ms; $y.p99_ms; $t99)}};
  # Requests the time cut moved: excluded (d > 0) up to 2 push intervals at the offered rate, or
  # added (d < 0, settle requests completing after the cut) up to the in-flight pool.
  def window($x; $t):
    ($x.measured_sample_count) as $n | ($t.measured_sample_count) as $m
    | (if ($n|num) and ($m|num) then $n - $m else null end) as $d
    | ([ ($t.per_process // [])[] | .pool | select(num) ] | add // 0) as $pool
    | ((($x.offered_rps // 0) * 2 * $tol.push_s * (1 + $tol.window_tol)) | floor) as $max
    | {excluded:$d, of:$n,
       fraction:(if $d != null and ($n|num) and $n > 0 then (($d / $n) * 100000 | round) / 100000 else null end),
       max_excluded:$max, max_added:$pool, settle_cut_ok:$t.settle_cut_ok,
       ok:($d != null and $n > 0 and $d <= $max and $d >= -$pool and $t.settle_cut_ok == true)};
  def levels($q; $d; $n):
    if $d >= 0 then [$q - $q * $d / $n, $q + (1 - $q) * $d / $n]
    else [$q - (1 - $q) * (-$d) / $n, $q + $q * (-$d) / $n] end;
  def quantiles: [["p50", 0.5, $tol.p50], ["p95", 0.95, $tol.p95], ["p99", 0.99, $tol.p99]];
  # Known quantiles of the published window: its own summary, plus by_tag (the same requests)
  # at the band levels. Any of them is a sound bound. Offline, a run that predates the by_tag
  # levels gets the wider published-only band; a live run must have all six, or no band.
  def bandpts($k): ((($bandin.points // [])[$k] // {}).quantiles // []) | map(select((.[0]|num) and (.[1]|num)));
  def refpts($x; $k):
    ([[0.5, $x.p50_ms], [0.9, $x.p90_ms], [0.95, $x.p95_ms], [0.99, $x.p99_ms], [0.999, $x.p999_ms]]
     | map(select((.[0]|num) and (.[1]|num)))) + bandpts($k);
  def lo_at($pts; $l): [ $pts[] | select(.[0] <= $l + 1e-9) ] | if length == 0 then 0 else (max_by(.[0]) | .[1]) end;
  def hi_at($pts; $l): [ $pts[] | select(.[0] >= $l - 1e-9) ] | if length == 0 then null else (min_by(.[0]) | .[1]) end;
  def tcmp($x; $t; $k; $w):
    refpts($x; $k) as $pts
    | reduce quantiles[] as [$name, $q, $tq] ({};
        ($x[$name + "_ms"]) as $a | ($t[$name + "_ms"]) as $b
        | (if $w.ok and (if $hasband or $live then (bandpts($k) | length) == 6 else true end)
           then levels($q; $w.excluded; $w.of) else null end) as $lv
        | (if $lv == null then null
           else {levels:$lv, lo_ms:lo_at($pts; $lv[0]), hi_ms:hi_at($pts; $lv[1]),
                 source:(if $hasband then "by_tag" else "published_only" end)} end) as $band
        | within($a; $b; $tq) as $tight
        | .[$name] = {published:$a, remote_write:$b, tight_ok:$tight, band:$band,
                      ok:($tight or ($band != null and ($b|num) and ($band.hi_ms|num)
                                     and $b >= $band.lo_ms * (1 - $tq) - $abs
                                     and $b <= $band.hi_ms * (1 + $tq) + $abs))});
  (.pub.points // []) as $P
  | (.bytime.points // []) as $T
  | (.bytag.points // []) as $G
  | if .mode == "levels" then
      [ range(0; $P|length) as $k | window($P[$k]; ($T[$k] // {})) as $w
        | {index:$k, levels:(if $w.ok then [ quantiles[] as [$n, $q, $tq] | levels($q; $w.excluded; $w.of)[] ] else [] end)} ]
    else
      [ range(0; $P|length) as $k
        | ($P[$k]) as $x | ($T[$k] // {}) as $t | ($G[$k] // {}) as $g
        | window($x; $t) as $w
        | {offered_rps:$x.offered_rps,
           counts:{published:$x.sample_count, prometheus:$t.sample_count,
                   ok:(($x.sample_count|num) and $x.sample_count == $t.sample_count)},
           measured_counts:{published:$x.measured_sample_count, by_tag:$g.measured_sample_count, by_time:$t.measured_sample_count},
           window:$w,
           by_time:tcmp($x; $t; $k; $w),
           by_tag:cmp($x; $g; $tol.p50; $tol.p95; $tol.p99)}
        | .by_time += {ok:(.by_time.p50.ok and .by_time.p95.ok and .by_time.p99.ok)}
        | .by_tag += {same_count:(($x.measured_sample_count|num) and $x.measured_sample_count == $g.measured_sample_count)}
        | .by_tag += {ok:(.by_tag.same_count and .by_tag.p50.ok and .by_tag.p95.ok and .by_tag.p99.ok)}
        | . + {ok:(.counts.ok and .by_tag.ok and .window.ok and .by_time.ok)} ] as $same
    | [ (.main.points // [])[] as $m
        | ([ $P[] | select(.offered_rps == $m.nominal_agg_offered_rps) ] | first) as $x
        | if $x == null then
            {offered_rps:$m.nominal_agg_offered_rps, main_offered_rps:$m.offered_rps, status:"no counterpart", ok:null}
          else
            ratio($x.achieved_rps; $x.offered_rps) as $rs | ratio($m.achieved_rps; $m.offered_rps) as $rm
            | {offered_rps:$x.offered_rps, main_offered_rps:$m.offered_rps,
               status:(if $rs == null or $rm == null then "incomplete" else "compared" end),
               achieved_ratio:{single:($rs|r3), multi:($rm|r3),
                               ok:($rs != null and $rm != null and (($rs - $rm)|fabs) <= $tol.cross_run.achieved_ratio)},
               latency:cmp($x; $m; $tol.cross_run.p50; $tol.cross_run.p95; $tol.cross_run.p99)}
            | . + {ok:(.achieved_ratio.ok and .latency.p50.ok and .latency.p95.ok and .latency.p99.ok)}
          end ] as $run
    | ([ $run[] | select(.status != "no counterpart") ]) as $cmp
    | {attempted:true,
       note:"same_requests: one k6 in the published summary mode ALSO remote-writing. by_tag (the Prometheus merge of exactly its steady requests) must match its summary within the histogram tolerances; by_time (the time cut under test) may move up to 2 push intervals of requests (window) and must lie within the published quantiles that bound a window of that many fewer requests, widened by the same tolerances. cross_run: the N-process rungs vs that single-process run at the same aggregate offered rate (separate runs, so run-to-run noise); rungs above the cross-check cap have no counterpart.",
       tolerances:{same_requests:{p50:$tol.p50, p95:$tol.p95, p99:$tol.p99, abs_ms:$abs,
                                  window_band:"by_time q-quantile within published quantiles at [q(1-f), q+(1-q)f], f = excluded/measured"},
                   cross_run:($tol.cross_run + {abs_ms:$abs})},
       same_requests:{rungs:$same, equivalent:(($same|length) > 0 and all($same[]; .ok)),
                      accounting_ok:($T as $bp | ($bp|length) > 0 and all($bp[]; .accounting_ok)),
                      failed:[ $same[] | select(.ok|not) | . as $r
                               | "\($r.offered_rps): " + ([ (if $r.counts.ok then empty else "counts \($r.counts.published) vs \($r.counts.prometheus)" end),
                                    (if $r.by_tag.ok then empty else "by_tag (counts \($r.measured_counts.published) vs \($r.measured_counts.by_tag); " + ([ quantiles[] as [$n, $q, $tq] | $r.by_tag[$n] | select(.ok|not) | "\($n) \(.published) vs \(.remote_write)" ] | join(", ")) + ")" end),
                                    (if $r.window.ok then empty else "window (excluded \($r.window.excluded) of \($r.window.of), allowed -\($r.window.max_added)..\($r.window.max_excluded), settle_cut_ok \($r.window.settle_cut_ok))" end),
                                    (if $r.by_time.ok then empty else "by_time " + ([ quantiles[] as [$n, $q, $tq] | $r.by_time[$n] | select(.ok|not) | "\($n) \(.remote_write) " + (if .band == null then "with no band" else "outside \(.band.lo_ms)..\(.band.hi_ms)" end) + " (published \(.published))" ] | join(", ")) end) ] | join("; ")) ]},
       cross_run:{rungs:$run, agrees:(($cmp|length) > 0 and all($cmp[]; .ok)),
                  compared:([ $run[] | select(.status == "compared") ] | length),
                  no_counterpart:([ $run[] | select(.status == "no counterpart") ] | length)},
       single_process_cpu_us_per_request:((.xcost // [])[0].cpu_us_per_request // null)}
    | . + {equivalent:(.same_requests.equivalent and .same_requests.accounting_ok)}
    end
