#!/usr/bin/env bash
# =====================================================================================================
# scripts/emr/check-archive-listing.sh -- local self-check of the archive-listing validator of
# scripts/emr/install-spark-dist.sh (IE-P6-09 repair 01, repair 02). Owner: infra-engineer. No AWS or
# network call, no sudo, no install, no extraction. Writes only a temporary directory (mktemp -d under
# TMPDIR), removed on exit with find -depth -delete (no link is followed). The pinned archive is only read.
#
# USAGE   scripts/emr/check-archive-listing.sh [<pinned spark tgz>]
#         default target/spark-dist/spark-3.5.5-bin-hadoop3-scala2.13.tgz; run from the repository root.
#         macOS only: the fixtures are built with bsdtar (-P, -s, -n, --format newc, --no-mac-metadata,
#         --no-xattrs, COPYFILE_DISABLE=1 so no AppleDouble `._` entry is added); a bsdtar that refuses
#         --no-mac-metadata or --no-xattrs stops the run with exit 2. The validator itself runs under
#         both bsdtar and GNU tar.
#
# Runs `install-spark-dist.sh --check-archive` (the same check_archive_listing code the EMR bootstrap
# runs) on 18 cases. Each case asserts the exit code AND the expected validator output: the specific
# FAIL message of the check meant to fire (checks 1-4 of check_archive_listing) and the OFFENDING
# content, so a case refused for a different reason is a CASE FAIL.
#   ok (exit 0, RESULT ARCHIVE-LISTING-OK and both "CHECK ok exception entry is one regular file"):
#    1 pinned          the complete pinned archive
#    2 pos-mini        small archive, both exception names as regular files; its listing must be exactly
#                      the 14 expected entries (no AppleDouble or other extra entry)
#   refused (exit 3):
#    3 dotdot          check 4 `entries with a .. path segment`, OFFENDING <top>/x/../evil.txt
#    4 absolute        check 2 `entries outside <top>/ or ...`, OFFENDING /<top>/README.md
#    5 outside         check 2, OFFENDING outside.txt
#    6 second-top      check 2, OFFENDING other/x.txt
#    7 symlink         check 1 `entries that are not regular files or directories`, OFFENDING l... link
#    8 hardlink        check 1, OFFENDING h... link to ...
#    9 comma-x         check 2, OFFENDING <top>/x/__init__,py
#   10 comma-near      check 2, OFFENDING <top>/examples/src/main/python/__init__,py
#   11 space           check 2, OFFENDING <top>/x/a b.txt
#   12 newline         check 2, OFFENDING <top>/x/a... (fixture listing must show a\nb.txt or a?b.txt)
#   13 control         check 2, OFFENDING <top>/x/a... (fixture listing must show a\033b.txt or a?b.txt)
#   14 odd-dir         check 2, the exception name as an empty directory listed WITH a trailing slash
#   15 odd-symlink     check 1, the exception name as a symlink
#   16 odd-hardlink    check 1, the exception name itself is the second (`h`) entry
#   17 odd-dup         check 3 `duplicate listings of an exception entry (2)`: the exception listed
#                      twice as a regular file
#   18 odd-dir-noslash check 3 `exception entries that are not exactly one regular file (types 'd')`:
#                      the exception name as a directory WITHOUT a trailing slash. The libarchive tar
#                      writers (ustar, pax, gnutar) append `/` to every directory name, so this fixture
#                      is a gzipped cpio newc archive built with bsdtar --format newc -n; bsdtar lists
#                      it through the same `tar -tzf`/`tar -tzvf` calls. GNU tar (EMR) cannot read cpio,
#                      so this case exercises check 3's logic under bsdtar only.
# Before each fixture run it checks the fixture's own (sanitized) listing for the intended entry, so a
# fixture that bsdtar built differently fails as a fixture error instead of passing for another reason.
# MARKERS  [check-archive-listing] STEP / CASE ok / CASE FAIL / RESULT PASS|FAIL
# EXIT     0 all cases as expected, 1 otherwise, 2 usage or missing prerequisite
# =====================================================================================================
set -euo pipefail
readonly SCRIPT_NAME=check-archive-listing
say() { printf '[%s] %s\n' "$SCRIPT_NAME" "$*"; }
die() {
  printf '[%s] FAIL %s\n' "$SCRIPT_NAME" "$*" >&2
  exit 2
}
show() { LC_ALL=C tr -c '\n -~' '?' | sed "s/^/[$SCRIPT_NAME]   | /"; } # sanitized indented output

(($# <= 1)) || die "usage: scripts/emr/check-archive-listing.sh [<pinned spark tgz>]"
HERE=$(cd "$(dirname "$0")" && pwd -P)
readonly VALIDATOR="$HERE/install-spark-dist.sh"
readonly PINNED=${1:-target/spark-dist/spark-3.5.5-bin-hadoop3-scala2.13.tgz}
readonly TOP=spark-3.5.5-bin-hadoop3-scala2.13
readonly PY=$TOP/examples/src/main/python
readonly ML=$PY/ml
readonly SS=$PY/sql/streaming
readonly EX_ML=$ML/__init__,py
readonly EX_SS=$SS/__init__,py
# Exact validator message parts (scripts/emr/install-spark-dist.sh, check_archive_listing and offending).
readonly O='[install-spark-dist] OFFENDING '
readonly R_OK='[install-spark-dist] RESULT ARCHIVE-LISTING-OK'
readonly R_EX_ML="[install-spark-dist] CHECK ok exception entry is one regular file: $EX_ML"
readonly R_EX_SS="[install-spark-dist] CHECK ok exception entry is one regular file: $EX_SS"
readonly R_TYPE='entries that are not regular files or directories (first 20 shown above)'
readonly R_NAME="entries outside $TOP/ or with unexpected characters (first 20 shown above)"
readonly R_DUP='duplicate listings of an exception entry (2) (first 20 shown above)'
readonly R_EXTYPE="exception entries that are not exactly one regular file (types 'd') (first 20 shown above)"
readonly R_DOTDOT='entries with a .. path segment (first 20 shown above)'
readonly R_FAIL3='[install-spark-dist] FAIL exit=3 '
[[ -f $VALIDATOR ]] || die "validator $VALIDATOR missing"
[[ -f $PINNED && ! -L $PINNED ]] || die "pinned archive $PINNED is not a regular file (run sbt sparkSubmitLocal first)"
TAR_VERSION=$(tar --version 2>&1) || die "tar --version failed"
[[ $TAR_VERSION == *bsdtar* ]] || die "fixtures need bsdtar (macOS tar); found: ${TAR_VERSION%%$'\n'*}"

T=$(mktemp -d "${TMPDIR:-/tmp}/scholarchain-cal.XXXXXXXX")
# shellcheck disable=SC2329 # invoked by the EXIT trap
cleanup() { if [[ -n $T && -d $T ]]; then find "$T" -depth -delete 2>/dev/null || true; fi; }
trap cleanup EXIT
FAILURES=0

# Every fixture create: no AppleDouble files (COPYFILE_DISABLE), no Mac metadata, no extended attributes.
ctar() { COPYFILE_DISABLE=1 LC_ALL=C tar "$@"; }
mkdir "$T/probe"
printf 'p\n' >"$T/probe/f"
ctar -cf "$T/probe.tar" --no-mac-metadata --no-xattrs -C "$T/probe" f >/dev/null 2>&1 ||
  die "this bsdtar refuses --no-mac-metadata or --no-xattrs; fixtures could carry Mac metadata"

fail_case() { # fail_case <case> <reason>
  say "CASE FAIL $1: $2" >&2
  FAILURES=$((FAILURES + 1))
}
listing() { LC_ALL=C tar -tzvf "$2" 2>&1 | LC_ALL=C tr -c '\n -~' '?' >"$T/$1.listing" || true; }
fixture_error() { # fixture_error <case> <what>
  fail_case "$1" "fixture error, $2; fixture listing (sanitized):"
  show <"$T/$1.listing" >&2
}
fixture_has() { # fixture_has <case> <tgz> <fixed>...: some sanitized listing line contains one of them
  local c=$1 tgz=$2 s
  shift 2
  listing "$c" "$tgz"
  for s in "$@"; do
    if grep -qF -- "$s" "$T/$c.listing"; then return 0; fi
  done
  fixture_error "$c" "its listing shows none of: $*"
  return 1
}
fixture_typed() { # fixture_typed <case> <tgz> <type char> <line suffix> <count>
  local c=$1 n
  listing "$c" "$2"
  n=$(LC_ALL=C awk -v t="$3" -v s="$4" \
    'substr($0, 1, 1) == t && length($0) >= length(s) && substr($0, length($0) - length(s) + 1) == s { k++ } END { print k + 0 }' \
    "$T/$c.listing")
  if ((n == $5)); then return 0; fi
  fixture_error "$c" "its listing has $n (expected $5) '$3' lines ending with '$4'"
  return 1
}
fixture_exact() { # fixture_exact <case> <tgz> <name>...: tar -tzf names (trailing / removed) == names
  local c=$1 tgz=$2
  shift 2
  listing "$c" "$tgz"
  LC_ALL=C tar -tzf "$tgz" 2>&1 | LC_ALL=C tr -c '\n -~' '?' | sed 's,/$,,' | LC_ALL=C sort >"$T/$c.names" || true
  printf '%s\n' "$@" | LC_ALL=C sort >"$T/$c.expected"
  if cmp -s "$T/$c.expected" "$T/$c.names"; then return 0; fi
  fail_case "$c" "fixture error, its entries differ from the expected $# (< expected, > listed):"
  diff "$T/$c.expected" "$T/$c.names" | show >&2 || true
  return 1
}

run_case() { # run_case <case> <expected exit> <archive> <fixed string the validator output must hold>...
  local c=$1 want=$2 tgz=$3 rc=0 out s bad=0
  shift 3
  out=$(bash "$VALIDATOR" --check-archive "$tgz" 2>&1) || rc=$?
  if ((rc != want)); then
    fail_case "$c" "exit $rc, expected $want; validator output:"
    printf '%s\n' "$out" | show >&2
    return 0
  fi
  for s in "$@"; do
    if ! grep -qF -- "$s" <<<"$out"; then
      fail_case "$c" "exit $rc as expected, but the output lacks '$s' (refused or passed for another reason)"
      bad=1
    fi
  done
  if ((bad)); then
    printf '%s\n' "$out" | show >&2
    return 0
  fi
  say "CASE ok $c exit=$rc reason asserted"
  printf '%s\n' "$out" | grep -E 'OFFENDING|FAIL|NOTE|exception entry|RESULT' | show || true
}
refused() { # refused <case> <reason> <offending fixed string>...: exit 3, FAIL line with that reason
  local c=$1 r=$2
  shift 2
  run_case "$c" 3 "$T/$c.tgz" "$R_FAIL3" "$r" "$@"
}

fresh() { # fresh <case>: $T/<case>/src/<TOP> with ordinary files, including the ml and streaming dirs
  local s="$T/$1/src"
  mkdir -p "$s/$TOP/x" "$s/$ML" "$s/$SS"
  printf 'readme\n' >"$s/$TOP/README.md"
  printf 'ok\n' >"$s/$TOP/x/ok.txt"
  printf 'x = 1\n' >"$s/$ML/als_example.py"
}
src() { printf '%s' "$T/$1/src"; }
mk() { # mk <case> <tar options and paths...>: $T/<case>.tgz built inside $T/<case>/src
  local c=$1
  shift
  (cd "$T/$c/src" && ctar -czf "$T/$c.tgz" --no-mac-metadata --no-xattrs "$@")
}

say "STEP 1 pinned archive $PINNED"
run_case pinned 0 "$PINNED" "$R_OK" "$R_EX_ML" "$R_EX_SS"

say "STEP 2 exception names as regular files (small archive, exact entry list)"
fresh pos-mini
printf '#\n' >"$(src pos-mini)/$EX_ML"
printf '#\n' >"$(src pos-mini)/$EX_SS"
mk pos-mini "$TOP"
if fixture_exact pos-mini "$T/pos-mini.tgz" "$TOP" "$TOP/README.md" "$TOP/x" "$TOP/x/ok.txt" \
  "$TOP/examples" "$TOP/examples/src" "$TOP/examples/src/main" "$PY" "$ML" "$ML/als_example.py" "$EX_ML" \
  "$PY/sql" "$SS" "$EX_SS" &&
  fixture_typed pos-mini "$T/pos-mini.tgz" - " $EX_ML" 1 &&
  fixture_typed pos-mini "$T/pos-mini.tgz" - " $EX_SS" 1; then
  run_case pos-mini 0 "$T/pos-mini.tgz" "$R_OK" "$R_EX_ML" "$R_EX_SS"
fi

say "STEP 3 unsafe entries (each must exit 3 for its own reason)"
fresh dotdot
mkdir -p "$(src dotdot)/$TOP/x/DOTDOT"
printf 'evil\n' >"$(src dotdot)/$TOP/x/DOTDOT/evil.txt"
mk dotdot -P -s ',/DOTDOT/,/../,' "$TOP"
if fixture_has dotdot "$T/dotdot.tgz" "$TOP/x/../evil.txt"; then
  refused dotdot "$R_DOTDOT" "$O$TOP/x/../evil.txt"
fi

fresh absolute
mk absolute -P -s ',^spark,/spark,' "$TOP"
if fixture_has absolute "$T/absolute.tgz" " /$TOP/README.md"; then
  refused absolute "$R_NAME" "$O/$TOP/README.md"
fi

fresh outside
printf 'outside\n' >"$(src outside)/outside.txt"
mk outside "$TOP" outside.txt
if fixture_has outside "$T/outside.tgz" " outside.txt"; then
  refused outside "$R_NAME" "${O}outside.txt"
fi

fresh second-top
mkdir -p "$(src second-top)/other"
printf 'other\n' >"$(src second-top)/other/x.txt"
mk second-top "$TOP" other
if fixture_has second-top "$T/second-top.tgz" " other/x.txt"; then
  refused second-top "$R_NAME" "${O}other/x.txt"
fi

fresh symlink
ln -s ../README.md "$(src symlink)/$TOP/x/link"
mk symlink "$TOP"
if fixture_typed symlink "$T/symlink.tgz" l " $TOP/x/link -> ../README.md" 1; then
  refused symlink "$R_TYPE" "${O}l" " $TOP/x/link -> ../README.md"
fi

fresh hardlink
ln "$(src hardlink)/$TOP/x/ok.txt" "$(src hardlink)/$TOP/x/hard"
mk hardlink "$TOP"
if fixture_has hardlink "$T/hardlink.tgz" " link to "; then
  refused hardlink "$R_TYPE" "${O}h" " link to "
fi

fresh comma-x
printf '#\n' >"$(src comma-x)/$TOP/x/__init__,py"
mk comma-x "$TOP"
if fixture_has comma-x "$T/comma-x.tgz" " $TOP/x/__init__,py"; then
  refused comma-x "$R_NAME" "$O$TOP/x/__init__,py"
fi

fresh comma-near
printf '#\n' >"$(src comma-near)/$PY/__init__,py"
mk comma-near "$TOP"
if fixture_has comma-near "$T/comma-near.tgz" " $PY/__init__,py"; then
  refused comma-near "$R_NAME" "$O$PY/__init__,py"
fi

fresh space
printf 'sp\n' >"$(src space)/$TOP/x/a b.txt"
mk space "$TOP"
if fixture_has space "$T/space.tgz" " $TOP/x/a b.txt"; then
  refused space "$R_NAME" "$O$TOP/x/a b.txt"
fi

# bsdtar and GNU tar list a non-printable name byte escaped (\n, \033); a raw byte would show as `?` in
# the sanitized listing. A fixture built without the byte (`ab.txt`) matches neither form.
fresh newline
printf 'nl\n' >"$(src newline)/$TOP/x/a"$'\n'"b.txt"
mk newline "$TOP"
if fixture_has newline "$T/newline.tgz" " $TOP/x/a\\nb.txt" " $TOP/x/a?b.txt"; then
  refused newline "$R_NAME" "$O$TOP/x/a"
fi

fresh control
printf 'esc\n' >"$(src control)/$TOP/x/a"$'\033'"b.txt"
mk control "$TOP"
if fixture_has control "$T/control.tgz" " $TOP/x/a\\033b.txt" " $TOP/x/a?b.txt"; then
  refused control "$R_NAME" "$O$TOP/x/a"
fi

fresh odd-dir
mkdir -p "$(src odd-dir)/$EX_ML"
mk odd-dir "$TOP"
if fixture_typed odd-dir "$T/odd-dir.tgz" d " $EX_ML/" 1; then
  refused odd-dir "$R_NAME" "$O$EX_ML/"
fi

fresh odd-symlink
ln -s als_example.py "$(src odd-symlink)/$EX_ML"
mk odd-symlink "$TOP"
if fixture_typed odd-symlink "$T/odd-symlink.tgz" l " $EX_ML -> als_example.py" 1; then
  refused odd-symlink "$R_TYPE" "${O}l" " $EX_ML -> als_example.py"
fi

# Explicit order without recursion: als_example.py is stored first (regular), the exception second (h).
fresh odd-hardlink
ln "$(src odd-hardlink)/$ML/als_example.py" "$(src odd-hardlink)/$EX_ML"
mk odd-hardlink -n "$TOP/README.md" "$ML/als_example.py" "$EX_ML"
if fixture_typed odd-hardlink "$T/odd-hardlink.tgz" h " $EX_ML link to $ML/als_example.py" 1 &&
  fixture_typed odd-hardlink "$T/odd-hardlink.tgz" - " $ML/als_example.py" 1; then
  refused odd-hardlink "$R_TYPE" "${O}h" " $EX_ML link to $ML/als_example.py"
fi

# The exception (one link, so no hard-link entry) archived by the tree walk and again by name: two
# regular-file entries of the same exact name; checks 1 and 2 pass, check 3 refuses the duplicate.
fresh odd-dup
printf '#\n' >"$(src odd-dup)/$EX_ML"
mk odd-dup "$TOP" "$EX_ML"
if fixture_typed odd-dup "$T/odd-dup.tgz" - " $EX_ML" 2; then
  refused odd-dup "$R_DUP" "$O$EX_ML"
fi

# cpio newc (see header): a directory entry named exactly the exception, with no trailing slash, and no
# top-directory entry (a bare `<top>` name would fail check 2 first); checks 1 and 2 pass.
fresh odd-dir-noslash
mkdir -p "$(src odd-dir-noslash)/$EX_ML"
mk odd-dir-noslash --format newc -n "$TOP/README.md" "$EX_ML"
if fixture_exact odd-dir-noslash "$T/odd-dir-noslash.tgz" "$TOP/README.md" "$EX_ML" &&
  fixture_typed odd-dir-noslash "$T/odd-dir-noslash.tgz" d " $EX_ML" 1; then
  refused odd-dir-noslash "$R_EXTYPE" "$O$EX_ML"
fi

if ((FAILURES == 0)); then
  say "RESULT PASS cases=18"
  exit 0
fi
say "RESULT FAIL failures=$FAILURES" >&2
exit 1
