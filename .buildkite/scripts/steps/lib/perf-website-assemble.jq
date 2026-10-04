# perf-website-assemble.jq — fit one source's refreshed figures into the committed page data.
# Input: the candidate from that source's transform. Args: $mode (single_k6 | rw_multik6) and
# --slurpfile old with the committed _data/perf_figures.json (no file = nothing committed yet).
# With $view == "scope" it instead returns what is committed for that source, in the candidate's
# shape, or null when the committed file holds no figures from it (input is then ignored).
#
# Two layouts. Legacy: the single-k6 figures at the top level, no .headline_rule. Multi-k6 (item
# 44): the top level is the multi-k6 headline, its .headline_rule and its ladder, and the
# single-k6 run sits under .single_k6 as {source, headline, throughput_ladder}. In both,
# behaviours and hw_matrix come from the single-k6 pipeline and stay at the top level. Each
# source rewrites only its own part, so a daily refresh cannot overwrite the multi-k6 headline.

def own: {source, headline, throughput_ladder};
def shared: {behaviours, hw_matrix, behaviours_status, withheld_internal};

($old[0] // null) as $o
| ($o != null and ($o.headline_rule // null) != null) as $rw_layout
| if ($ARGS.named.view // "") == "scope" then
    if $o == null then null
    elif $mode == "rw_multik6" then (if $rw_layout then ($o | own + {headline_rule}) else null end)
    elif $rw_layout then (if ($o.single_k6 // null) == null then null else ($o.single_k6 | own) + ($o | shared) end)
    else $o end
  elif $mode == "rw_multik6" then
    {source, headline, headline_rule, throughput_ladder,
     single_k6: (if $o == null then null elif $rw_layout then ($o.single_k6 // null) else ($o | own) end)}
    + (($o // {}) | shared)
  elif $rw_layout then
    ($o | {source, headline, headline_rule, throughput_ladder}) + {single_k6: own} + shared
  else . end
