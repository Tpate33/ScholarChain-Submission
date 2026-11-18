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
#
# Downloads the pinned Apache Spark 3.5.5 (Scala 2.13) distribution, the three runtime jars and the
# submit wrapper from their content-addressed keys, verifies every digest BEFORE extracting or
# installing anything, checks the archive listing (regular files and directories only, all under the
# single top-level directory, no absolute or `..` path), and installs create-once into /opt/scholarchain:
#   spark-3.5.5-bin-hadoop3-scala2.13/   runtime-jars/   spark-conf/spark-defaults.conf
#   bin/submit-collaboration-job.sh      .installed (digests recorded)
# Any mismatch exits non-zero, so EMR fails the cluster (fail closed, no retry).
# MARKERS  [install-spark-dist] STEP / CHECK ok / FAIL exit=<n>
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
cleanup() { rm -f "$WORK"/* 2>/dev/null || true; rmdir "$WORK" 2>/dev/null || true; }
trap cleanup EXIT

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
tar -tzvf "$WORK/$SPARK_FILE" >"$WORK/listing.txt" || die 3 "cannot list $SPARK_FILE"
tar -tzf "$WORK/$SPARK_FILE" >"$WORK/names.txt" || die 3 "cannot list $SPARK_FILE"
BAD_TYPES=$(cut -c1 "$WORK/listing.txt" | grep -vc '^[-d]$' || true)
[[ $BAD_TYPES == 0 ]] || die 3 "$SPARK_FILE has $BAD_TYPES entries that are not regular files or directories"
BAD_NAMES=$(grep -vcE "^$SPARK_NAME/([A-Za-z0-9._+=@-]+/?)*$" "$WORK/names.txt" || true)
[[ $BAD_NAMES == 0 ]] || die 3 "$SPARK_FILE has $BAD_NAMES entries outside $SPARK_NAME/ or with unexpected characters"
grep -qE '(^|/)\.\.(/|$)' "$WORK/names.txt" && die 3 "$SPARK_FILE has a .. path segment"
say "CHECK ok archive listing: $(wc -l <"$WORK/names.txt" | tr -d ' ') entries, files and directories only"

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
