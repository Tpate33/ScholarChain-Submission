#!/usr/bin/env bash
# shellcheck disable=SC2015 # `test && test || die ...` is a guard: die always exits, so it never falls through
# =====================================================================================================
# scripts/emr/install-spark-dist.sh -- EMR bootstrap action (every node, before applications install).
# Owner: infra-engineer. Staged content-addressed at
#   s3://<data bucket>/artifacts/emr-scripts/<sha256 of this file>/install-spark-dist.sh
# and named by infra/terraform/environments/<env>/emr-run (bootstrap_action, positional arguments).
#
# USAGE   install-spark-dist.sh <data-bucket> <region> <spark-sha512> <scala-library-sha256>
#                               <hadoop-aws-sha256> <hadoop-aws-sha1> <sdk-bundle-sha256> <submit-sha256>
#         install-spark-dist.sh --check-archive <spark tgz>
#
# Bootstrap mode (8 positional arguments, as EMR calls it): downloads the pinned Apache Spark 3.5.5
# (Scala 2.13) distribution, the three runtime jars and the submit wrapper from their content-addressed
# keys, verifies every digest BEFORE extracting or installing anything, runs check_archive_listing
# (below), and installs create-once into /opt/scholarchain:
#   spark-3.5.5-bin-hadoop3-scala2.13/   runtime-jars/   spark-conf/spark-defaults.conf
#   bin/submit-collaboration-job.sh      .installed (digests recorded)
# Any mismatch exits non-zero, so EMR fails the cluster (fail closed, no retry).
#
# --check-archive mode: runs ONLY check_archive_listing (the same code) on a local archive, with no
# digest check, download, sudo or install; needs only tar, grep, awk, head, tr and mktemp. Exit 0 pass,
# 3 refused, 2 usage. Exercised by scripts/emr/check-archive-listing.sh (IE-P6-09 repair 01).
#
# check_archive_listing refuses (exit 3) unless every entry
#   - is a regular file or a directory (`tar -tv` type column `-` or `d`; links `l`/`h` and special
#     entries refused);
#   - lies under the single top-level directory spark-3.5.5-bin-hadoop3-scala2.13/ (so no absolute
#     path and no second top-level entry) with path segments of [A-Za-z0-9._+=@-] only, or is one of
#     the ODD_NAMES below, as an exact full-path string, listed once and as a regular file;
#   - has no `..` segment.
# ODD_NAMES: the pinned 3.5.5 distribution ships exactly two upstream files named `__init__,py` (a
# COMMA, 0x2C, before `py`), which failed EMR attempt 3 (IE-P6-09 repair 01). Only these two full
# paths are admitted; a comma anywhere else is still refused. An ODD_NAMES entry absent from the
# archive is not a failure (NOTE line). Each failed check prints its offending entries (at most 20,
# bytes outside printable ASCII shown as `?`) as OFFENDING lines on stderr before the FAIL line.
# MARKERS  [install-spark-dist] STEP / CHECK ok / NOTE / OFFENDING / RESULT / FAIL exit=<n>
# =====================================================================================================
set -euo pipefail
readonly SCRIPT_NAME=install-spark-dist
readonly ROOT=/opt/scholarchain
readonly SPARK_NAME=spark-3.5.5-bin-hadoop3-scala2.13
readonly SPARK_FILE=$SPARK_NAME.tgz
readonly SCALA_JAR=scala-library-2.13.16.jar
readonly HADOOP_AWS_JAR=hadoop-aws-3.3.4.jar
readonly SDK_JAR=aws-java-sdk-bundle-1.12.262.jar
readonly SUBMIT=submit-collaboration-job.sh

say() { printf '[%s] %s\n' "$SCRIPT_NAME" "$*"; }
die() {
  printf '[%s] FAIL exit=%s %s\n' "$SCRIPT_NAME" "$1" "$2" >&2
  exit "$1"
}
hex() { [[ $1 =~ ^[0-9a-f]+$ ]] && ((${#1} == $2)) || die 2 "$3 must be $2 lowercase hex"; }

# Exact full entry names admitted beside the regex (see header). Fixed list; no pattern.
ODD_NAMES=(
  "$SPARK_NAME/examples/src/main/python/ml/__init__,py"
  "$SPARK_NAME/examples/src/main/python/sql/streaming/__init__,py"
)
SPARK_NAME_RE=${SPARK_NAME//./\\.} # dots literal in the ERE
readonly ODD_NAMES SPARK_NAME_RE

filter() { grep "$@" || (($? == 1)); } # grep where "no line selected" is not an error; 2 still fails
offending() {                           # offending <file> <what>: up to 20 lines to stderr, then die 3
  local n
  n=$(grep -c '' "$1") || n=0
  head -n 20 "$1" | LC_ALL=C tr -c '\n -~' '?' |
    awk -v s="$SCRIPT_NAME" '{ printf "[%s] OFFENDING %s\n", s, $0 }' >&2 || true
  die 3 "$ARCHIVE_LABEL has $n $2 (first 20 shown above)"
}

check_archive_listing() { # check_archive_listing <tgz>; work files in $WORK
  local tgz=$1 name hits lines
  LC_ALL=C tar -tzvf "$tgz" >"$WORK/listing.txt" || die 3 "cannot list $ARCHIVE_LABEL"
  LC_ALL=C tar -tzf "$tgz" >"$WORK/names.txt" || die 3 "cannot list $ARCHIVE_LABEL"
  [[ -s $WORK/names.txt ]] || die 3 "$ARCHIVE_LABEL lists no entry"
  printf '%s\n' "${ODD_NAMES[@]}" >"$WORK/odd.txt"
  # 1. type column: regular files and directories only.
  LC_ALL=C filter -v '^[-d]' "$WORK/listing.txt" >"$WORK/bad.txt" || die 3 "type check failed to run"
  [[ ! -s $WORK/bad.txt ]] || offending "$WORK/bad.txt" "entries that are not regular files or directories"
  # 2. names: under the single top-level directory, plain segments, or an exact ODD_NAMES entry.
  LC_ALL=C filter -vE "^$SPARK_NAME_RE/([A-Za-z0-9._+=@-]+/?)*\$" "$WORK/names.txt" >"$WORK/odd-or-bad.txt" ||
    die 3 "name check failed to run"
  LC_ALL=C filter -vxF -f "$WORK/odd.txt" "$WORK/odd-or-bad.txt" >"$WORK/bad.txt" || die 3 "name check failed to run"
  [[ ! -s $WORK/bad.txt ]] || offending "$WORK/bad.txt" "entries outside $SPARK_NAME/ or with unexpected characters"
  # 3. each ODD_NAMES entry present: listed exactly once, and its one verbose line is a regular file
  #    (a directory or link of that name, with or without a trailing slash, is refused).
  for name in "${ODD_NAMES[@]}"; do
    hits=$(LC_ALL=C filter -cxF -- "$name" "$WORK/names.txt") || die 3 "exception check failed to run"
    if ((hits == 0)); then
      say "NOTE exception entry absent: $name"
      continue
    fi
    printf '%s\n' "$name" >"$WORK/bad.txt"
    ((hits == 1)) || offending "$WORK/bad.txt" "duplicate listings of an exception entry ($hits)"
    lines=$(LC_ALL=C awk -v n=" $name" 'length($0) >= length(n) && substr($0, length($0) - length(n) + 1) == n { print substr($0, 1, 1) }' \
      "$WORK/listing.txt" | tr -d '\n') || die 3 "exception check failed to run"
    [[ $lines == - ]] || offending "$WORK/bad.txt" "exception entries that are not exactly one regular file (types '$lines')"
    say "CHECK ok exception entry is one regular file: $name"
  done
  # 4. no `..` segment (the regex admits `.` characters, so this is separate).
  LC_ALL=C filter -E '(^|/)\.\.(/|$)' "$WORK/names.txt" >"$WORK/bad.txt" || die 3 "dot-dot check failed to run"
  [[ ! -s $WORK/bad.txt ]] || offending "$WORK/bad.txt" "entries with a .. path segment"
  say "CHECK ok archive listing: $(grep -c '' "$WORK/names.txt") entries, files and directories only"
}

WORK=""
cleanup() {
  [[ -n $WORK ]] || return 0
  rm -f "$WORK"/* 2>/dev/null || true
  rmdir "$WORK" 2>/dev/null || true
}
trap cleanup EXIT

if [[ ${1:-} == --check-archive ]]; then
  (($# == 2)) || die 2 "usage: install-spark-dist.sh --check-archive <spark tgz>"
  [[ -f $2 && ! -L $2 ]] || die 2 "--check-archive: $2 is not a regular file"
  command -v tar >/dev/null 2>&1 || die 2 "required tool tar missing"
  ARCHIVE_LABEL=$2
  WORK=$(mktemp -d)
  say "STEP archive listing (check-archive) $2"
  check_archive_listing "$2"
  say "RESULT ARCHIVE-LISTING-OK"
  exit 0
fi

(($# == 8)) || die 2 "expected 8 arguments, got $#"
BUCKET=$1 REGION=$2 SPARK_SHA512=$3 SCALA_SHA256=$4 HAWS_SHA256=$5 HAWS_SHA1=$6 SDK_SHA256=$7 SUBMIT_SHA256=$8
[[ $BUCKET =~ ^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$ ]] || die 2 "bad bucket name"
[[ $REGION =~ ^[a-z]{2}(-[a-z]+)+-[0-9]$ ]] || die 2 "bad Region"
hex "$SPARK_SHA512" 128 spark-sha512
hex "$SCALA_SHA256" 64 scala-library-sha256
hex "$HAWS_SHA256" 64 hadoop-aws-sha256
hex "$HAWS_SHA1" 40 hadoop-aws-sha1
hex "$SDK_SHA256" 64 sdk-bundle-sha256
hex "$SUBMIT_SHA256" 64 submit-sha256
for t in aws tar sha512sum sha256sum sha1sum sudo; do
  command -v "$t" >/dev/null 2>&1 || die 2 "required tool $t missing"
done
# The EMR step runs the wrapper under /usr/bin/timeout (cluster-side step bound, CR-P6-27 M2); a node
# without GNU timeout fails here, at bootstrap, before any step can run unbounded.
TIMEOUT_VERSION=$(/usr/bin/timeout --version 2>/dev/null) || die 2 "/usr/bin/timeout missing"
[[ $TIMEOUT_VERSION == *"GNU coreutils"* ]] || die 2 "/usr/bin/timeout is not GNU coreutils timeout"
[[ ! -e $ROOT ]] || die 3 "$ROOT already exists (create-once)"

WORK=$(mktemp -d)
ARCHIVE_LABEL=$SPARK_FILE

fetch() { # fetch <key> <local name>
  say "STEP fetch s3://$BUCKET/$1"
  aws s3 cp --only-show-errors --region "$REGION" "s3://$BUCKET/$1" "$WORK/$2" || die 4 "download of s3://$BUCKET/$1 failed"
}
check() { # check <tool> <file> <expected>
  local got
  got=$("$1" "$WORK/$2" | awk '{ print $1 }')
  [[ $got == "$3" ]] || die 3 "$2: $1 $got differs from the pinned $3"
  say "CHECK ok $2 $1 matches"
}

fetch "artifacts/spark-dist/$SPARK_SHA512/$SPARK_FILE" "$SPARK_FILE"
fetch "artifacts/runtime-jars/$SCALA_SHA256/$SCALA_JAR" "$SCALA_JAR"
fetch "artifacts/runtime-jars/$HAWS_SHA256/$HADOOP_AWS_JAR" "$HADOOP_AWS_JAR"
fetch "artifacts/runtime-jars/$SDK_SHA256/$SDK_JAR" "$SDK_JAR"
fetch "artifacts/emr-scripts/$SUBMIT_SHA256/$SUBMIT" "$SUBMIT"
check sha512sum "$SPARK_FILE" "$SPARK_SHA512"
check sha256sum "$SCALA_JAR" "$SCALA_SHA256"
check sha256sum "$HADOOP_AWS_JAR" "$HAWS_SHA256"
check sha1sum "$HADOOP_AWS_JAR" "$HAWS_SHA1"
check sha256sum "$SDK_JAR" "$SDK_SHA256"
check sha256sum "$SUBMIT" "$SUBMIT_SHA256"

say "STEP archive listing"
check_archive_listing "$WORK/$SPARK_FILE"

say "STEP install into $ROOT"
sudo install -d -o root -g root -m 0755 "$ROOT" "$ROOT/runtime-jars" "$ROOT/spark-conf" "$ROOT/bin"
sudo tar -xzf "$WORK/$SPARK_FILE" -C "$ROOT" --no-same-owner
for j in "$SCALA_JAR" "$HADOOP_AWS_JAR" "$SDK_JAR"; do
  sudo install -o root -g root -m 0644 "$WORK/$j" "$ROOT/runtime-jars/$j"
done
sudo install -o root -g root -m 0755 "$WORK/$SUBMIT" "$ROOT/bin/$SUBMIT"

# Spark defaults for the wrapper (SPARK_CONF_DIR). S3A settings are explicit so that no EMR-specific
# core-site value (for example an EMR credentials provider class) is used by the Apache S3A client.
printf '%s\n' \
  "spark.master yarn" \
  "spark.submit.deployMode client" \
  "spark.yarn.maxAppAttempts 1" \
  "spark.dynamicAllocation.enabled false" \
  "spark.eventLog.enabled true" \
  "spark.hadoop.fs.s3a.impl org.apache.hadoop.fs.s3a.S3AFileSystem" \
  "spark.hadoop.fs.s3a.aws.credentials.provider org.apache.hadoop.fs.s3a.auth.IAMInstanceCredentialsProvider" \
  "spark.hadoop.fs.s3a.endpoint s3.$REGION.amazonaws.com" \
  "spark.hadoop.fs.s3a.endpoint.region $REGION" \
  "spark.hadoop.fs.s3a.directory.marker.retention keep" |
  sudo tee "$ROOT/spark-conf/spark-defaults.conf" >/dev/null
printf 'spark_sha512=%s\nscala_library_sha256=%s\nhadoop_aws_sha256=%s\nsdk_bundle_sha256=%s\nsubmit_sha256=%s\n' \
  "$SPARK_SHA512" "$SCALA_SHA256" "$HAWS_SHA256" "$SDK_SHA256" "$SUBMIT_SHA256" | sudo tee "$ROOT/.installed" >/dev/null
say "CHECK ok installed $SPARK_NAME, 3 runtime jars and $SUBMIT into $ROOT"
