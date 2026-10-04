# Drift figures for the weekly soak, from the per-window series soak.js emits.
# Input: the k6 soak result ({soak: {warmup_s, match: {windows: [...]}, ...}}).
# Args:  $ref_n, $late_n (numbers) — how many windows form the reference and the late group.
# Output: {match: <drift>, verify: <drift>, retrieve: <drift>}.
#
# Windows starting before warmup_s are excluded. Of the rest, the first $ref_n are the reference and
# the last $late_n are late. `ratio` is late median / reference median. `ratio_worst` is the slowest
# late window over the QUIETEST window before the late group, so a reference still inflated by
# warm-up cannot lower it. `reference_over_quietest` says how settled the reference was (1 = settled).
# Run on fixtures by .buildkite/scripts/test/perf-soak-test.sh.

def median: sort | length as $n
  | if $n == 0 then null elif $n % 2 == 1 then .[($n - 1) / 2] else (.[$n / 2 - 1] + .[$n / 2]) / 2 end;
def r4: if . == null then null else (. * 10000 | round) / 10000 end;
def ratio($n; $d): if $n == null or $d == null or $d <= 0 then null else ($n / $d | r4) end;

def figures($ref; $late; $body; f):
  ($ref | map(f)) as $r | ($late | map(f)) as $l | ($body | map(f)) as $b
  | if (($r + $l + $b) | any(. == null)) then null
    else ($r | median) as $rm | ($b | min) as $q
      | { reference_ms: ($rm | r4), quietest_ms: $q, late_ms: $l,
          ratio: ratio($l | median; $rm), ratio_worst: ratio($l | max; $q),
          reference_over_quietest: ratio($rm; $q) }
    end;

def drift($warmup_s):
  (.windows // []) as $all
  | ($all | map(select($warmup_s != null and .start_s >= $warmup_s))) as $settled
  | { window_count: ($all | length), warmup_s: $warmup_s,
      warmup_windows: ($all | map(select($warmup_s != null and .start_s < $warmup_s) | .index)) } as $head
  | if $warmup_s == null then
      $head + {computed: false, reason: "the k6 result does not state warmup_s"}
    elif ($all | length) == 0 then
      $head + {computed: false, reason: "the k6 result carries no per-window series"}
    elif ($settled | length) < $ref_n + $late_n then
      $head + {computed: false,
               reason: "\($settled | length) window(s) start at or after the \($warmup_s) s warm-up; \($ref_n) reference + \($late_n) late are needed (shorten K6_SOAK_WARMUP or K6_SOAK_WINDOW for a short run)"}
    else
      $settled[:$ref_n] as $ref | $settled[-$late_n:] as $late | $settled[:-$late_n] as $body
      | figures($ref; $late; $body; .p99_ms) as $p99 | figures($ref; $late; $body; .p50_ms) as $p50
      | $head + {reference_windows: ($ref | map(.index)), late_windows: ($late | map(.index))}
        + (if $p99 == null or $p50 == null then
             {computed: false,
              reason: "window(s) \($settled | map(select(.p99_ms == null or .p50_ms == null) | .index) | map(tostring) | join(", ")) after the warm-up carried no samples"}
           else {computed: true, p99: $p99, p50: $p50} end)
    end;

.soak as $s
| $s.warmup_s as $w
| { match: ($s.match // {} | drift($w)), verify: ($s.verify // {} | drift($w)), retrieve: ($s.retrieve // {} | drift($w)) }
