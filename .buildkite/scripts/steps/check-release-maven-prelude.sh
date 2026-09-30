#!/usr/bin/env bash
#
# Fails when a release script runs mvn in a container without first installing
# unzip via ${maven_packaging_prelude} (scripts/release/_lib.sh). The packaging
# assertions bound to package/verify shell out to unzip, which the pinned
# maven:*-eclipse-temurin image lacks, and the deploys that hit them are skipped
# in dry-run, so only the real release would fail. See docs/operations/release-process.md.
#
# Detection limits: it cannot see mvn inside a heredoc payload (bash -s <<EOF),
# a command held in a variable (sh -c "$CMD"), or a wrapper that forwards "$@"
# to in_docker. Write mvn literally in the in_docker call, or use in_maven.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$REPO_ROOT"

LIB="scripts/release/_lib.sh"
FIXTURE=".buildkite/scripts/steps/check-release-maven-prelude.fixture"
errors=0

# Prints "<verdict>\t<line>\t<kind>" per container invocation block: kind "fn" is
# the container call inside in_maven(), "mvn" any other block that runs mvn/mvnw.
# BAD = no live ${maven_packaging_prelude} ahead of mvn. `live` blanks single-quoted
# and backslash-escaped text (which never expands) but keeps positions, so it
# lines up with `text`.
prelude_hits() {
  awk '
    BEGIN { inblk = 0; infn = 0 }
    function scan(line,   i, n, c, skip, pair) {
      skip = (sq && line ~ /^[ \t]*#/); code = ""; live = ""; bare = ""; n = length(line)
      for (i = 1; i <= n; i++) {
        c = substr(line, i, 1)
        if (sq) { if (c == "\047") sq = 0; else if (!skip) { code = code c; live = live " " }; continue }
        if (c == "\\") { pair = substr(line, i, 2); code = code pair; gsub(/./, " ", pair); live = live pair; i++; continue }
        if (dq) { if (c == "\"") dq = 0; else { code = code c; live = live c }; continue }
        if (c == "\047") { sq = 1; continue }
        if (c == "\"") { dq = 1; continue }
        if (c == "#" && (i == 1 || substr(line, i - 1, 1) ~ /[ \t]/)) break
        code = code c; live = live c; bare = bare c
      }
    }
    function evaluate(   kind, p, m) {
      p = match(livetext, /\$\{?maven_packaging_prelude\}?/) ? RSTART : 0
      m = match(text, /(^|[^A-Za-z0-9_.-])(mvn|mvnw)([ \t]|$)/) ? RSTART : 0
      if (blkfn) kind = "fn"; else if (m) kind = "mvn"; else return
      print ((!p || (m && p > m)) ? "BAD" : "OK") "\t" start "\t" kind
    }
    {
      if (!inblk) {
        if ($0 ~ /^in_maven\(\)[ \t]*\{/) infn = 1
        else if (infn && $0 ~ /^\}/) infn = 0
        if ($0 ~ /^[ \t]*in_docker\(\)/) next
        sq = 0; dq = 0; scan($0)
        if (bare !~ /(^|[^A-Za-z0-9_])(in_docker|run-in-docker\.sh|docker([ \t]+container)?[ \t]+run)([ \t]|$)/ &&
            code !~ /(^[ \t]*|\$\([ \t]*)([^ \t]*\/)?(in_docker|run-in-docker\.sh)([ \t]|$)/) next
        inblk = 1; start = NR; text = code; livetext = live; blkfn = infn
      } else {
        scan($0); text = text " " code; livetext = livetext " " live
      }
      if (sq || dq || $0 ~ /\\$/) next
      evaluate(); inblk = 0
    }
    END { if (inblk) evaluate() }
  ' "$1"
}

echo "--- :package: release: every containerized mvn run installs unzip first"

# 1. Self-test the detector against the fixture before trusting it.
if [ ! -f "$FIXTURE" ]; then
  echo "+++ :bangbang: detector fixture ${FIXTURE} is missing; failing closed" >&2
  errors=$(( errors + 1 ))
else
  want="$(awk '/^#@flag$/ { print NR + 1 }' "$FIXTURE")"
  npass="$(awk '/^#@pass$/ { n++ } END { print n + 0 }' "$FIXTURE")"
  hits="$(prelude_hits "$FIXTURE")"
  got="$(awk -F'\t' '$1 == "BAD" { print $2 }' <<<"$hits")"
  ok="$(awk -F'\t' '$1 == "OK" { n++ } END { print n + 0 }' <<<"$hits")"
  if [ -z "$want" ] || [ "$npass" -eq 0 ]; then
    echo "+++ :bangbang: fixture has no #@flag or no #@pass case, so the self-test proves nothing; failing closed" >&2
    errors=$(( errors + 1 ))
  elif [ "$got" != "$want" ] || [ "$ok" -ne "$npass" ]; then
    echo "+++ :bangbang: detector self-test FAILED: want flagged lines [$(tr '\n' ' ' <<<"$want")] got [$(tr '\n' ' ' <<<"$got")], want ${npass} passing blocks got ${ok}" >&2
    errors=$(( errors + 1 ))
  else
    echo "    :white_check_mark: detector self-test: $(wc -l <<<"$want" | tr -d ' ') must-flag and ${npass} must-pass cases correct"
  fi
fi

# 2. The prelude must behave, run the way the release runs it (bash -e, followed by
#    more commands) against a stub apt-get on an otherwise empty PATH: install unzip
#    when it is missing, skip apt when it is present, and stop the payload before
#    anything after it runs when apt update or apt install fails.
prelude_body="$(awk '/^maven_packaging_prelude=\047/ { on = 1; next } on && /^\047/ { exit } on' "$LIB")"
mkdir -p .tmp
stub="$(mktemp -d "$REPO_ROOT/.tmp/prelude-check.XXXXXX")"
trap 'rm -rf "$stub"' EXIT
# run_prelude <update rc> <install rc> <unzip present: yes|no>; prints "<rc> <reached|stopped>"
run_prelude() {
  local out rc=0
  rm -f "$stub/apt-get" "$stub/unzip" "$stub/apt-calls"
  printf '#!/bin/sh\necho "$*" >> "%s/apt-calls"\ncase " $* " in *" update "*) exit %s ;; esac\nexit %s\n' \
    "$stub" "$1" "$2" > "$stub/apt-get"
  [ "$3" = yes ] && printf '#!/bin/sh\nexit 0\n' > "$stub/unzip"
  chmod +x "$stub"/*
  out="$(env -i PATH="$stub" "$BASH" -ec "$prelude_body"$'\n''echo REACHED' 2>/dev/null)" || rc=$?
  if [ "$out" = REACHED ]; then echo "$rc reached"; else echo "$rc stopped"; fi
}
prelude_fail() {
  echo "+++ :bangbang: ${LIB}: maven_packaging_prelude $1" >&2
  errors=$(( errors + 1 ))
}
if [ -z "$prelude_body" ]; then
  prelude_fail "is missing or empty"
else
  before=$errors
  case "$(run_prelude 100 0 no)" in 0*|*reached) prelude_fail "lets the payload continue when apt-get update fails" ;; esac
  case "$(run_prelude 0 100 no)" in 0*|*reached) prelude_fail "lets the payload continue when apt-get install fails" ;; esac
  if [ "$(run_prelude 0 0 no)" != "0 reached" ]; then
    prelude_fail "stops the payload even when apt-get succeeds"
  elif ! grep -Eq '(^| )install( .*)? unzip( |$)' "$stub/apt-calls" 2>/dev/null; then
    prelude_fail "does not run 'apt-get install … unzip' when unzip is missing"
  fi
  if [ "$(run_prelude 100 100 yes)" != "0 reached" ] || [ -f "$stub/apt-calls" ]; then
    prelude_fail "calls apt-get even though unzip is already present"
  fi
  [ "$errors" -eq "$before" ] && echo "    :white_check_mark: prelude installs unzip, skips apt when unzip exists, and stops the payload when apt update or install fails"
fi

# 3. Sweep every release script.
fn_blocks=0
mvn_blocks=0
while IFS= read -r script; do
  while IFS=$'\t' read -r verdict lineno kind; do
    [ -n "$verdict" ] || continue
    if [ "$kind" = "fn" ] && [ "$script" = "$LIB" ]; then
      fn_blocks=$(( fn_blocks + 1 ))
    else
      mvn_blocks=$(( mvn_blocks + 1 ))
    fi
    if [ "$verdict" = "BAD" ]; then
      what="container runs mvn"; [ "$kind" = "fn" ] && what="in_maven's container call runs"
      echo "+++ :bangbang: ${script}:${lineno}: ${what} without \${maven_packaging_prelude} ahead of mvn; the packaging assertions need unzip, which the Maven image lacks. Use in_maven, or start the bash -ec payload with \"\${maven_packaging_prelude}\"." >&2
      errors=$(( errors + 1 ))
    fi
  done < <(prelude_hits "$script")
done < <({ find scripts/release -type f -name '*.sh'; find .buildkite/scripts -maxdepth 1 -type f -name 'release-*.sh'; } | sort)

if [ "$fn_blocks" -ne 1 ]; then
  echo "+++ :bangbang: ${LIB}: expected exactly one container call inside in_maven(), found ${fn_blocks}; the guard cannot verify in_maven, failing closed" >&2
  errors=$(( errors + 1 ))
fi
if [ "$mvn_blocks" -eq 0 ]; then
  echo "+++ :bangbang: found no raw containerized mvn run in scripts/release; the sweep matched nothing, failing closed" >&2
  errors=$(( errors + 1 ))
fi
echo "    in_maven() checked; ${mvn_blocks} raw containerized mvn run(s) checked"

if [ "$errors" -gt 0 ]; then
  echo "+++ :x: ${errors} release Maven prelude violation(s)" >&2
  exit 1
fi
echo "    :white_check_mark: all containerized mvn runs install unzip first"
