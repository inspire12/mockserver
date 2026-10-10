# shellcheck shell=bash
# SUT JAVA_TOOL_OPTIONS helpers, shared by perf-test-run.sh and lib/perf-percore.sh.

# The shipped image sets its own JAVA_TOOL_OPTIONS (e.g. -XX:+UseZGC); passing -e
# JAVA_TOOL_OPTIONS REPLACES it, silently dropping the default GC. Read it once per image.
image_java_tool_options() { # image_ref -> its built-in JAVA_TOOL_OPTIONS default (empty if none)
  docker image inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$1" 2>/dev/null \
    | awk -F= '$1=="JAVA_TOOL_OPTIONS"{sub("^[^=]*=",""); print; exit}' || true
}
# Prepend the image default to a container's own opts. If those opts select a GC, drop the
# image default's GC selectors first — two -XX:+Use*GC flags abort the JVM ("Multiple GCs").
compose_java_tool_options() { # image_default  container_opts
  local base="$1" extra="$2"
  if grep -Eq -- '-XX:\+Use[A-Za-z0-9]*GC' <<<"$extra"; then
    base="$(printf '%s' "$base" | sed -E 's/-XX:\+Use[A-Za-z0-9]*GC//g; s/-XX:\+ZGenerational//g; s/  */ /g; s/^ //; s/ $//')"
  fi
  printf '%s' "${base}${base:+${extra:+ }}${extra}"
}
