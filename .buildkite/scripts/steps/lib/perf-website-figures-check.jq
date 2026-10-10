# perf-website-figures-check.jq — was this page data's multi-k6 headline written by the publish step?
# Input: a _data/perf_figures.json. Output: an array of problems; [] means it may be committed.
#
# A file with no .headline_rule carries only the single-k6 figures and has nothing to check here.
# One with a .headline_rule must carry the stamp perf-website-publish.sh writes when its multi-k6
# source refreshes the headline inside a build (.source.published_from), and that stamp must agree
# with the rest of the file. A dry-run candidate, a local run and a hand-assembled file all fail.
# perf-website-publish.sh refuses to build on, or to write, a file this rejects, and
# .buildkite/scripts/test/perf-xl-publish-test.sh runs it on the committed file in every lint.
# It checks the stamp and the file against itself; it cannot show the S3 object was unedited.
#
# It is total: a value of the wrong type is a problem, never a jq error. Callers must still treat
# a jq failure, or anything but exactly one array of strings, as a refusal (an unreadable file).

def need($ok; $detail): if $ok then empty else $detail end;
def isobj: type == "object";
def str: if type == "string" then . else "" end;
try (
if (isobj | not) then [ "the page data is \(type), not an object" ]
elif .headline_rule == null then
  # Single-k6 figures only. Anything of the multi-k6 arm's left at the top level would be shown
  # under the single-k6 wording.
  (.source | if isobj then . else {} end) as $s
  | [ need(.single_k6 == null; "no headline_rule, yet a single_k6 block: the multi-k6 headline is missing"),
      need($s.published_from == null and $s.client != "multi_k6";
           "no headline_rule, yet source is the multi-k6 arm's (client \($s.client | tojson)): the figures would be shown as single-k6 ones") ]
else
  [ need(.source | isobj; "source is \(.source | type), not an object"),
    need(.headline | isobj; "headline is \(.headline | type), not an object"),
    need(.headline_rule | isobj; "headline_rule is \(.headline_rule | type), not an object"),
    need((.throughput_ladder | type) == "array" and all(.throughput_ladder[]; isobj);
         "throughput_ladder is not an array of objects"),
    need((.single_k6 | isobj) and (.single_k6.source | isobj) and (.single_k6.headline | isobj)
         and (.single_k6.throughput_ladder | type) == "array" and .single_k6.source.client != "multi_k6";
         "single_k6 is not the single-k6 run's {source, headline, throughput_ladder}") ] as $shape
  | if ($shape | length) > 0 then $shape else
      .source as $s | $s.published_from as $pf | ($pf | isobj) as $stamped | .headline as $h | .headline_rule as $r
      | ([ .throughput_ladder[] | select(.offered_rps == $h.healthy_ceiling_rps) ] | first) as $row
      | ((($s.run_timestamp_utc | str) | gsub(":"; "-")) + "__" + ($s.commit | str) + ".json") as $object
      | [
          need($stamped; "source.published_from is \($pf | type), not the publish step's stamp: the multi-k6 headline was not written by the publish step"),
          need(($stamped | not) or $pf.dry_run == false; "source.published_from.dry_run is \($pf.dry_run? | tojson): a dry-run candidate is not a published refresh"),
          need(($stamped | not) or ($pf.publish_build_number != null and $pf.publish_build_number == $s.build_number);
               "source.published_from.publish_build_number \($pf.publish_build_number? | tojson) is not the run's build \($s.build_number | tojson): the refresh did not come from that build's publish step"),
          need(($stamped | not) or (($pf.key | type) == "string" and ($pf.key | test("^runs-perf-xl/[^/]+/[^/]+$")) and ($pf.key | endswith("/" + $object)));
               "source.published_from.key \($pf.key? | tojson) is not runs-perf-xl/<branch>/\($object)"),
          need(($stamped | not) or $pf.series_unmet == []; "source.published_from.series_unmet is \($pf.series_unmet? | tojson), not []"),
          need(($h.healthy_ceiling_rps | type) == "number" and ($h.p99_max_ms | type) == "number" and $h.p99_max_ms == $r.p99_max_ms;
               "headline ceiling \($h.healthy_ceiling_rps | tojson) or bound \($h.p99_max_ms | tojson) does not match headline_rule.p99_max_ms \($r.p99_max_ms | tojson)"),
          need(($h.healthy_ceiling_p99_ms | type) == "number" and ($h.p99_max_ms | type) == "number" and $h.healthy_ceiling_p99_ms <= $h.p99_max_ms;
               "headline p99 \($h.healthy_ceiling_p99_ms | tojson) is missing or over the bound \($h.p99_max_ms | tojson)"),
          need($row != null and $row.degraded == false and $row.p99_gc_masked_ms == $h.healthy_ceiling_p99_ms;
               "the ladder has no healthy row at the ceiling \($h.healthy_ceiling_rps | tojson) with the headline's p99 \($h.healthy_ceiling_p99_ms | tojson)"),
          need([ .throughput_ladder[] | (.offered_rps > $h.healthy_ceiling_rps) == (.degraded == true) ] | all;
               "a ladder row is marked past the ceiling on the wrong side of it")
        ]
    end
end
) catch [ "the page data could not be checked: \(.)" ]
