#!/usr/bin/env bash
#
# Fail the build unless JNA is packaged the way each server jar needs it:
#  - the mockserver-netty-no-dependencies library jar relocates it (shaded_package/com/sun/jna/), so it
#    cannot clash with the JNA that Testcontainers or docker-java put on an embedding app's classpath;
#  - the mockserver-netty-docker jar the images ship carries it UNRELOCATED (com/sun/jna/) with the
#    linux jnidispatch natives, because libjnidispatch binds its JNI entry points to the unrelocated
#    class names, and differs from the library jar in nothing else.
#
# usage: assert-jna-relocation.sh <library-jar> [<docker-jar>]
set -euo pipefail

LIB="${1:?usage: assert-jna-relocation.sh <library-jar> [<docker-jar>]}"
DOCKER_JAR="${2:-}"
RELOCATED="shaded_package/com/sun/jna"
RELOCATED_DOT="shaded_package.com.sun.jna"
# JNA's resource prefixes for the two arches the images are built for.
LINUX_ARCHES="linux-x86-64 linux-aarch64"

FAILED=0
err() { echo "ERROR: $*" >&2; FAILED=1; }
# "<crc32>\t<name>" for every file entry, read from the zip directory (no extraction).
crc_list() {
  unzip -v "$1" | awk '$7 ~ /^[0-9a-f]+$/ && length($7) == 8 && $NF !~ /\/$/ {
    n = $0; for (i = 1; i <= 7; i++) sub(/^ *[^ ]+/, "", n); sub(/^ +/, "", n); print $7 "\t" n }'
}

[ -f "$LIB" ] || { echo "ERROR: library jar not found: $LIB" >&2; exit 1; }
LIB_ENTRIES="$(unzip -Z1 "$LIB")"
[ -n "$LIB_ENTRIES" ] || { echo "ERROR: $LIB lists no entries" >&2; exit 1; }

grep -qxF "$RELOCATED/Native.class" <<<"$LIB_ENTRIES" \
  || err "$(basename "$LIB") has no $RELOCATED/Native.class - the library jar must keep JNA relocated"
if grep -q '^com/sun/jna/' <<<"$LIB_ENTRIES"; then
  err "$(basename "$LIB") carries unrelocated com/sun/jna/ entries, which clash with an embedding app's own JNA"
fi
for arch in $LINUX_ARCHES; do
  grep -qxF "$RELOCATED/$arch/libjnidispatch.so" <<<"$LIB_ENTRIES" \
    || err "$(basename "$LIB") is missing $RELOCATED/$arch/libjnidispatch.so"
done

if [ -n "$DOCKER_JAR" ]; then
  [ -f "$DOCKER_JAR" ] || { echo "ERROR: docker jar not found: $DOCKER_JAR" >&2; exit 1; }
  DOCKER_ENTRIES="$(unzip -Z1 "$DOCKER_JAR")"
  D="$(basename "$DOCKER_JAR")"

  grep -qxF "com/sun/jna/Native.class" <<<"$DOCKER_ENTRIES" \
    || err "$D has no com/sun/jna/Native.class - JNA must be unrelocated in the image jar"
  for arch in $LINUX_ARCHES; do
    grep -qxF "com/sun/jna/$arch/libjnidispatch.so" <<<"$DOCKER_ENTRIES" \
      || err "$D is missing com/sun/jna/$arch/libjnidispatch.so"
  done
  if grep -q "^$RELOCATED/" <<<"$DOCKER_ENTRIES"; then
    err "$D still carries relocated $RELOCATED/ entries"
  fi

  # Same files as the library jar once the JNA prefix is moved back, so nothing was dropped or added.
  EXPECTED="$(grep -v '/$' <<<"$LIB_ENTRIES" | sed "s#^$RELOCATED/#com/sun/jna/#" | LC_ALL=C sort)"
  ACTUAL="$(grep -v '/$' <<<"$DOCKER_ENTRIES" | LC_ALL=C sort)"
  if [ "$EXPECTED" != "$ACTUAL" ]; then
    err "$D's entries differ from the library jar's beyond the JNA prefix:"
    diff <(echo "$EXPECTED") <(echo "$ACTUAL") | head -20 >&2 || true
  fi

  # Byte-for-byte (by CRC, streamed, no extraction): an entry may differ only if the library's copy
  # referenced relocated JNA, and no entry of the image jar may still reference it.
  STILL="$(unzip -p "$DOCKER_JAR" | LC_ALL=C grep -caF -e "$RELOCATED" -e "$RELOCATED_DOT" || true)"
  if [ "${STILL:-0}" != "0" ]; then
    err "$D still references relocated JNA ($STILL matches of $RELOCATED / $RELOCATED_DOT) - a class the relocation missed fails at runtime"
  fi
  # Every image-jar entry must pair with a library entry, or an unpaired one would go uncompared; and
  # every file entry must have been parsed, or a changed `unzip -v` format would compare nothing.
  LIB_CRCS="$(crc_list "$LIB")"
  DOCKER_CRCS="$(crc_list "$DOCKER_JAR")"
  if [ "$(grep -c . <<<"$LIB_CRCS")" -ne "$(grep -c . <<<"$EXPECTED")" ] \
     || [ "$(grep -c . <<<"$DOCKER_CRCS")" -ne "$(grep -c . <<<"$ACTUAL")" ]; then
    err "could not read a CRC for every entry from unzip -v, so the jars were not compared"
  fi
  CHANGED="$(awk -F '\t' -v rel="$RELOCATED/" '
      NR == FNR { n = $2; if (index(n, rel) == 1) n = "com/sun/jna/" substr(n, length(rel) + 1); crc[n] = $1; next }
      !($2 in crc) { print "UNPAIRED " $2; next }
      crc[$2] != $1 { print $2 }' <(echo "$LIB_CRCS") <(echo "$DOCKER_CRCS"))"
  if grep -q '^UNPAIRED ' <<<"$CHANGED"; then
    err "$D has entries with no library counterpart: $(grep '^UNPAIRED ' <<<"$CHANGED" | head -5 | tr '\n' ' ')"
    CHANGED="$(grep -v '^UNPAIRED ' <<<"$CHANGED" || true)"
  fi
  UNEXPLAINED=""
  while IFS= read -r f; do
    [ -n "$f" ] || continue
    src="$f"
    case "$f" in com/sun/jna/*) src="shaded_package/$f" ;; esac
    refs="$(unzip -p "$LIB" "$src" | LC_ALL=C grep -caF -e "$RELOCATED" -e "$RELOCATED_DOT" || true)"
    [ "${refs:-0}" != "0" ] || UNEXPLAINED="$UNEXPLAINED $f"
  done <<<"$CHANGED"
  if [ -n "$UNEXPLAINED" ]; then
    err "$D changed entries that never referenced JNA:"
    printf '  %s\n' $UNEXPLAINED | head -20 >&2
  fi
fi

if [ "$FAILED" -ne 0 ]; then
  echo "" >&2
  echo "  See mockserver-netty-docker/pom.xml and the com.sun relocation in mockserver/pom.xml." >&2
  exit 1
fi

if [ -n "$DOCKER_JAR" ]; then
  echo "$(grep -c . <<<"$CHANGED" || true) entries differ from the library jar, each only in its JNA references"
  echo "OK: $(basename "$LIB") relocates JNA; $(basename "$DOCKER_JAR") carries it unrelocated (linux x86-64, aarch64) and differs in nothing else."
else
  echo "OK: $(basename "$LIB") relocates JNA (linux x86-64, aarch64 natives under $RELOCATED/)."
fi
