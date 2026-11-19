#!/usr/bin/env bash
# shellcheck disable=SC2015 # `test && test || die ...` is a guard: die always exits, so it never falls through
# =====================================================================================================
# scripts/emr/submit-collaboration-job.sh -- EMR step wrapper (primary node, via command-runner.jar).
# Owner: infra-engineer. Installed by install-spark-dist.sh into /opt/scholarchain/bin/.
#
# USAGE   submit-collaboration-job.sh --name value ... (every option below exactly once, no defaults)
#   --data-bucket --logs-bucket --region --submission-id
#   --job-jar-key --job-jar-sha256 --config-key --config-sha256 --pairs-key --pairs-sha256
#   --input-root --curated-run --decision-run --grouping-run --output-root --code-version --attempt
#   --partitions --checkpoint-interval --max-tree-entries --max-tree-depth
#   --max-driver-validation-rows --max-driver-validation-bytes --max-metadata-bytes
#   --step-timeout-seconds --kill-after-seconds
#         submit-collaboration-job.sh --self-check <sample fs.defaultFS> <canonical stub JVM root>
#         submit-collaboration-job.sh --check-java <canonical JVM root> [<java -version bound 1..10 s>]
#
# The EMR step runs this wrapper under `/usr/bin/timeout -k <kill> <step timeout>` (cluster-side wall
# bound of the whole step, CR-P6-27 M2); the step's ActionOnFailure is TERMINATE_CLUSTER, so a failed
# or timed-out step ends the cluster. Inside, spark-submit runs under its own
# `timeout --foreground -k <kill> <remaining - reserve>`, so the event-log copy still fits the bound.
#
# Locations passed to the job (CR-P6-27 M1) are checked against the job's StorePath rules before any
# download: --input-root and --output-root s3a://<data bucket>/<segments>; the checkpoint root
# hdfs://<authority>/scholarchain/checkpoints/<submission id>, the authority read on the node from
# `hdfs getconf -confKey fs.defaultFS` and validated (scheme hdfs, non-empty host, optional port, no
# path beyond "/", character whitelist); --config and --benchmark-pairs absolute local paths. Segments:
# [A-Za-z0-9._=-] only, never empty, "." or ".." (so no comma, glob character, space or percent).
#
# --self-check runs exactly the argument construction and checks with sample option values and the
# given fs.defaultFS, runs select_java17 on the given stub JVM root and passes its JAVA_HOME_DIR to
# build_submit_argv as the real run does, prints the spark-submit argv (ARGV lines) and exits 0, or
# refuses with exit 3 (location) or 4 (no Java 17). It makes no AWS, HDFS or network call and writes
# nothing. scripts/emr/check-submit-args.sh drives it.
#
# Java 17 (IE-P6-12; run attempt 2 exited 4 in 6 s): the step environment from command-runner has
# JAVA_HOME=/etc/alternatives/jre and /usr/bin/java first on PATH, both Corretto 8 on the EMR 7.14.0
# primary, while EMR itself runs on /usr/lib/jvm/jre-17. The wrapper therefore ignores the inherited
# JAVA_HOME and PATH and selects Java 17 deterministically (select_java17): the first of the fixed
# candidates <JVM root>/jre-17/bin/java, <JVM root>/java-17-amazon-corretto.x86_64/bin/java (JVM root
# /usr/lib/jvm) that exists, resolves (readlink -f) to <JVM root>/.../bin/java, is an executable
# regular file and whose `java -version` (bounded, 10 s; a timeout is that candidate's failure)
# reports `version "17.`. Its home (the resolved path minus
# /bin/java) is passed as JAVA_HOME to spark-submit (client mode, primary) and pinned for the YARN
# ApplicationMaster and executors (spark.yarn.appMasterEnv.JAVA_HOME, spark.executorEnv.JAVA_HOME), so
# no process relies on the node's default Java; the same path must exist on the core node (same EMR
# release image). No candidate passes: refuse with exit 4, never fall back to another Java.
# --check-java runs ONLY select_java17 against the given canonical JVM root (stub JVMs in a temporary
# directory), with an optional lower java -version bound (1..10 s, default 10), prints RESULT JAVA-SELECT-OK java_home=<dir> and exits 0, or refuses with exit 4. No AWS,
# HDFS or network call; writes nothing. scripts/emr/check-submit-args.sh drives it.
#
# HDFS (CR-P6-27 6d): /scholarchain is created by the HDFS superuser (`sudo -n -u hdfs`), owned by the
# step user, mode 0750; spark-events/, checkpoints/ and staging/ (spark.yarn.stagingDir) below it.
# Exit status: spark-submit's status (124 = timed out); 1 if spark-submit succeeded but the event-log
# copy failed; 2 usage; 3 refused; 4 node or HDFS precondition.
# After a successful spark-submit and event-log copy, the wrapper removes only this submission's
# checkpoint directory (hdfs dfs -rm -r -skipTrash, bounded); a failure there is logged, not fatal.
# MARKERS  [submit-collaboration-job] STEP / CHECK ok / NOTE / ARGV / RESULT spark_exit=<n> /
#          RESULT SELF-CHECK-OK / RESULT JAVA-SELECT-OK / FAIL exit=<n>
# =====================================================================================================
set -euo pipefail
readonly SCRIPT_NAME=submit-collaboration-job
readonly ROOT=/opt/scholarchain
readonly SPARK_HOME_DIR=$ROOT/spark-3.5.5-bin-hadoop3-scala2.13
readonly RUNTIME_CP=$ROOT/runtime-jars/scala-library-2.13.16.jar:$ROOT/runtime-jars/hadoop-aws-3.3.4.jar:$ROOT/runtime-jars/aws-java-sdk-bundle-1.12.262.jar
readonly HDFS_BASE=/scholarchain
readonly MAX_STEP_TIMEOUT_SECONDS=1800 # equals HC_MAX_STEP_TIMEOUT_MINUTES of run-bounded-spark.sh
readonly MIN_KILL_AFTER_SECONDS=10
readonly MAX_KILL_AFTER_SECONDS=120
readonly POST_RESERVE_SECONDS=240 # event-log copy and own-checkpoint removal after spark-submit
readonly MIN_SPARK_SECONDS=120
# Downloads, java check and HDFS setup before spark-submit (CR-P6-29 n4): without it a step at the
# minimum always refused once preparation took 1 s or more.
readonly PREPARATION_ALLOWANCE_SECONDS=60
# Equals HC_MIN_STEP_TIMEOUT_MINUTES * 60 of run-bounded-spark.sh (CR-P6-28 n3, CR-P6-29 n4): 420 s.
readonly MIN_STEP_TIMEOUT_SECONDS=$((POST_RESERVE_SECONDS + MIN_SPARK_SECONDS + PREPARATION_ALLOWANCE_SECONDS))
readonly CHECKPOINT_RM_MAX_SECONDS=60 # bound of the own-checkpoint removal (repair 02 item 5)
readonly CHECKPOINT_RM_MARGIN_SECONDS=15
readonly OPTS="data-bucket logs-bucket region submission-id job-jar-key job-jar-sha256 config-key \
config-sha256 pairs-key pairs-sha256 input-root curated-run decision-run grouping-run output-root \
code-version attempt partitions checkpoint-interval max-tree-entries max-tree-depth \
max-driver-validation-rows max-driver-validation-bytes max-metadata-bytes step-timeout-seconds \
kill-after-seconds"

say() { printf '[%s] %s\n' "$SCRIPT_NAME" "$*"; }
die() {
  printf '[%s] FAIL exit=%s %s\n' "$SCRIPT_NAME" "$1" "$2" >&2
  exit "$1"
}

# ---- location rules (mirror StorePath.parse + JobArguments.location + StorePath.globProblem) --------
is_segments() { # relative /-separated segments, whitelist only, no empty, "." or ".." segment
  [[ $1 =~ ^[A-Za-z0-9._=-]+(/[A-Za-z0-9._=-]+)*$ ]] && [[ /$1/ != */../* && /$1/ != */./* ]]
}
is_bucket() { [[ $1 =~ ^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$ && $1 != *..* ]]; }
is_hostname() { # DNS-style host: labels of [A-Za-z0-9-], no leading or trailing hyphen, <= 253 chars
  ((${#1} >= 1 && ${#1} <= 253)) &&
    [[ $1 =~ ^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$ ]]
}
need_s3a_in_bucket() { # need_s3a_in_bucket <flag> <uri> <bucket>
  local rest b key
  [[ $2 == s3a://* ]] || die 3 "$1 '$2' is not an s3a:// URI"
  rest=${2#s3a://}
  [[ $rest == */* ]] || die 3 "$1 '$2' has no path below the bucket"
  b=${rest%%/*}
  key=${rest#*/}
  is_bucket "$b" || die 3 "$1 '$2': bucket '$b' is not a valid bucket name"
  [[ $b == "$3" ]] || die 3 "$1 '$2' is not in the data bucket $3"
  is_segments "$key" || die 3 "$1 '$2': path '$key' has an empty, '.' or '..' segment or a character outside [A-Za-z0-9._=-]"
}
need_local_abs() { # need_local_abs <flag> <path>
  [[ $2 == /* ]] && is_segments "${2#/}" || die 3 "$1 '$2' is not an absolute, normalized local path of [A-Za-z0-9._=-] segments"
}
HDFS_AUTHORITY=""
validate_default_fs() { # validate_default_fs <fs.defaultFS value>: sets HDFS_AUTHORITY
  local v=$1 rest host port
  [[ $v == hdfs://* ]] || die 3 "fs.defaultFS '$v' is not an hdfs:// URI"
  rest=${v#hdfs://}
  rest=${rest%/}
  [[ -n $rest ]] || die 3 "fs.defaultFS '$v' has no authority (name node host)"
  [[ $rest != */* ]] || die 3 "fs.defaultFS '$v' has a path beyond '/'"
  if [[ $rest == *:* ]]; then
    host=${rest%%:*}
    port=${rest#*:}
    [[ $port =~ ^[1-9][0-9]{0,4}$ ]] && ((port <= 65535)) || die 3 "fs.defaultFS '$v' has an invalid port '$port'"
  else
    host=$rest
  fi
  is_hostname "$host" || die 3 "fs.defaultFS '$v' has an empty or invalid host '$host' (letters, digits, '-', '.')"
  HDFS_AUTHORITY=$rest
}

# ---- Java 17 selection (IE-P6-12) ---------------------------------------------------------------------
readonly JVM_ROOT=/usr/lib/jvm
readonly JAVA17_CANDIDATES="jre-17 java-17-amazon-corretto.x86_64" # names under the JVM root, in order
JAVA_BIN=""
JAVA_HOME_DIR=""
# Bound of each candidate's `java -version` (CR-P6-49 m1). 10 s on the node; --check-java may lower it
# (1..10) so the self-check's hanging stub ends fast. Two candidates: at most ~21 s of the 60 s
# preparation allowance. The bound is a polling watchdog in bash itself (kill -0 every 0.1 s, then
# kill -KILL), not /usr/bin/timeout, so the same code runs on the node and in the macOS self-check
# (GNU timeout is absent there). Residual: a candidate that forks a descendant which keeps stdout open
# would hold the capture until that descendant exits; a JVM's -version does not, and the step timeout
# still bounds the whole step.
JAVA_VERSION_TIMEOUT_SECONDS=10
java_version_bounded() { # java_version_bounded <bin>: prints `<bin> -version` output; 124 if not done in the bound
  local pid n=0 limit=$((JAVA_VERSION_TIMEOUT_SECONDS * 10)) rc=0
  "$1" -version </dev/null 2>&1 &
  pid=$!
  while kill -0 "$pid" 2>/dev/null; do
    if ((n >= limit)); then
      kill -KILL "$pid" 2>/dev/null || true
      wait "$pid" 2>/dev/null || true
      return 124
    fi
    sleep 0.1
    n=$((n + 1))
  done
  wait "$pid" || rc=$?
  return "$rc"
}
select_java17() { # select_java17 <canonical JVM root>: sets JAVA_BIN and JAVA_HOME_DIR, or die 4
  local root=$1 c cand bin ver first rc tried=""
  for c in $JAVA17_CANDIDATES; do
    cand=$root/$c/bin/java
    if [[ ! -e $cand ]]; then
      tried="$tried $cand: absent;"
      continue
    fi
    if ! bin=$(readlink -f "$cand") || [[ -z $bin ]]; then
      tried="$tried $cand: does not resolve;"
      continue
    fi
    if [[ $bin != "$root"/*/bin/java ]]; then
      tried="$tried $cand: resolves to $bin, outside $root;"
      continue
    fi
    if [[ ! -f $bin || ! -x $bin ]]; then
      tried="$tried $cand: $bin is not an executable file;"
      continue
    fi
    rc=0
    ver=$(java_version_bounded "$bin") || rc=$?
    if ((rc == 124)); then
      tried="$tried $cand: java -version timed out after ${JAVA_VERSION_TIMEOUT_SECONDS}s ($bin);"
      continue
    fi
    if ((rc != 0)); then
      tried="$tried $cand: java -version failed with exit $rc ($bin);"
      continue
    fi
    if [[ $ver != *'version "17.'* ]]; then
      first=${ver%%$'\n'*}
      tried="$tried $cand: $bin reports '$first';"
      continue
    fi
    JAVA_BIN=$bin
    JAVA_HOME_DIR=${bin%/bin/java}
    say "CHECK ok java 17 $JAVA_BIN (candidate $cand; inherited JAVA_HOME and PATH java not used)"
    return 0
  done
  die 4 "java on the primary node is not 17 (no Java 17 candidate under $root:$tried)"
}
need_jvm_root() { # need_jvm_root <mode> <path>
  [[ $2 == /* && $2 != */ && -d $2 ]] || die 2 "$1: '$2' is not an absolute directory path without a trailing /"
}
if [[ ${1:-} == --check-java ]]; then
  (($# == 2 || $# == 3)) || die 2 "usage: submit-collaboration-job.sh --check-java <canonical JVM root> [<java -version bound 1..10 s>]"
  need_jvm_root --check-java "$2"
  if (($# == 3)); then
    [[ $3 =~ ^([1-9]|10)$ ]] || die 2 "--check-java: bound '$3' is not an integer in 1..10"
    JAVA_VERSION_TIMEOUT_SECONDS=$3
  fi
  select_java17 "$2"
  say "RESULT JAVA-SELECT-OK java_bin=$JAVA_BIN java_home=$JAVA_HOME_DIR"
  exit 0
fi

# ---- arguments ----------------------------------------------------------------------------------------
SELF_CHECK=no
if [[ ${1:-} == --self-check ]]; then
  (($# == 3)) || die 2 "usage: submit-collaboration-job.sh --self-check <sample fs.defaultFS> <canonical stub JVM root>"
  need_jvm_root --self-check "$3"
  SELF_CHECK=yes
  SAMPLE_DEFAULT_FS=$2
  SAMPLE_JVM_ROOT=$3
  # Sample values only (no real account): the shapes run-bounded-spark.sh builds.
  O_data_bucket=scholarchain-dev-data-000000000000-us-east-1
  O_logs_bucket=scholarchain-dev-logs-000000000000-us-east-1
  O_region=us-east-1
  O_submission_id=a1-0123456789abcdef
  O_job_jar_key=artifacts/job/0123456789abcdef0123456789abcdef01234567/scholarchain-spark-jobs-0.1.0.jar
  O_job_jar_sha256=$(printf '%064d' 0)
  O_config_key=config/$(printf '%064d' 0)/analysis-dev.conf
  O_config_sha256=$(printf '%064d' 0)
  O_pairs_key=config/$(printf '%064d' 0)/benchmark-pairs-dev.v1.json
  O_pairs_sha256=$(printf '%064d' 0)
  O_input_root=s3a://$O_data_bucket/input/dev-run-01
  O_curated_run=curated/hadoop/snapshot=dblp-2026-10-03/run=2881724b5c86b561a235836d51946fbd884beed36a14f929b27c278cb5de8baa
  O_decision_run=er-decisions/run=736cb1706210a34f0958e658c061054c4e6b27b80847528db8619da84495b76a
  O_grouping_run=er-assignments/run=84dd896fda43977b934008f10955191e78c2b93b80ec57c00f55dbb137dbd7d2
  O_output_root=s3a://$O_data_bucket/output/spark
  O_code_version=0123456789abcdef0123456789abcdef01234567
  O_attempt=1
  O_partitions=8
  O_checkpoint_interval=2
  O_max_tree_entries=20000
  O_max_tree_depth=4
  O_max_driver_validation_rows=2000
  O_max_driver_validation_bytes=67108864
  O_max_metadata_bytes=1048576
  O_step_timeout_seconds=1800
  O_kill_after_seconds=60
else
  SEEN=" "
  while (($# > 0)); do
    [[ $1 == --* && $# -ge 2 ]] || die 2 "expected --option value pairs"
    name=${1#--}
    value=$2
    shift 2
    case " $OPTS " in *" $name "*) ;; *) die 2 "unknown option --$name" ;; esac
    [[ $SEEN != *" $name "* ]] || die 2 "option --$name repeated"
    [[ $value =~ ^[A-Za-z0-9._:/=+@-]+$ ]] || die 2 "--$name has characters outside the allowed set"
    SEEN="$SEEN$name "
    printf -v "O_${name//-/_}" '%s' "$value"
  done
  for name in $OPTS; do [[ $SEEN == *" $name "* ]] || die 2 "missing --$name"; done
fi

# ---- checks shared by both modes ---------------------------------------------------------------------
START_EPOCH=$(date +%s)
[[ $O_code_version =~ ^[0-9a-f]{40}$ ]] || die 2 "--code-version must be 40 hex"
[[ $O_submission_id =~ ^[a-z0-9-]{8,64}$ ]] || die 2 "--submission-id is malformed"
for v in "$O_job_jar_sha256" "$O_config_sha256" "$O_pairs_sha256"; do
  [[ $v =~ ^[0-9a-f]{64}$ ]] || die 2 "sha256 arguments must be 64 hex"
done
is_bucket "$O_data_bucket" || die 2 "--data-bucket is not a bucket name"
is_bucket "$O_logs_bucket" || die 2 "--logs-bucket is not a bucket name"
[[ $O_region =~ ^[a-z]{2}(-[a-z]+)+-[0-9]$ ]] || die 2 "--region is not a Region code"
for k in "$O_job_jar_key" "$O_config_key" "$O_pairs_key"; do
  is_segments "$k" || die 3 "object key '$k' has unexpected characters or segments"
done
need_s3a_in_bucket --input-root "$O_input_root" "$O_data_bucket"
need_s3a_in_bucket --output-root "$O_output_root" "$O_data_bucket"
[[ $O_output_root/ != "$O_input_root"/* && $O_input_root/ != "$O_output_root"/* ]] || die 3 "--input-root and --output-root overlap"
for v in "$O_curated_run" "$O_decision_run" "$O_grouping_run"; do
  is_segments "$v" || die 3 "run path '$v' is not a relative path of [A-Za-z0-9._=-] segments"
done
for v in "$O_attempt" "$O_partitions" "$O_checkpoint_interval" "$O_max_tree_entries" "$O_max_tree_depth" \
  "$O_max_driver_validation_rows" "$O_max_driver_validation_bytes" "$O_max_metadata_bytes"; do
  [[ $v =~ ^[1-9][0-9]{0,9}$ ]] || die 2 "numeric option '$v' is not a positive integer"
done
[[ $O_step_timeout_seconds =~ ^[1-9][0-9]{0,4}$ ]] &&
  ((O_step_timeout_seconds >= MIN_STEP_TIMEOUT_SECONDS && O_step_timeout_seconds <= MAX_STEP_TIMEOUT_SECONDS)) ||
  die 3 "--step-timeout-seconds must be in [$MIN_STEP_TIMEOUT_SECONDS, $MAX_STEP_TIMEOUT_SECONDS]"
[[ $O_kill_after_seconds =~ ^[1-9][0-9]{0,3}$ ]] &&
  ((O_kill_after_seconds >= MIN_KILL_AFTER_SECONDS && O_kill_after_seconds <= MAX_KILL_AFTER_SECONDS)) ||
  die 3 "--kill-after-seconds must be in [$MIN_KILL_AFTER_SECONDS, $MAX_KILL_AFTER_SECONDS]"

if [[ $SELF_CHECK == yes ]]; then
  DEFAULT_FS=$SAMPLE_DEFAULT_FS
  WORK=/tmp/scholarchain-submit.selfcheck
else
  [[ -f $ROOT/.installed ]] || die 4 "$ROOT/.installed missing: the bootstrap action did not complete"
  grep -q '"isMaster": *true' /mnt/var/lib/info/instance.json 2>/dev/null || die 4 "not the primary node"
  DEFAULT_FS=$(hdfs getconf -confKey fs.defaultFS 2>/dev/null) || die 4 "hdfs getconf -confKey fs.defaultFS failed"
  WORK=$(mktemp -d /tmp/scholarchain-submit.XXXXXXXX)
fi
validate_default_fs "$DEFAULT_FS"
need_local_abs work-directory "$WORK"
readonly HDFS_ROOT="hdfs://$HDFS_AUTHORITY$HDFS_BASE"
readonly EVENTS_URI="$HDFS_ROOT/spark-events"
readonly STAGING_URI="$HDFS_ROOT/staging"
readonly CHECKPOINT_URI="$HDFS_ROOT/checkpoints/$O_submission_id"
need_local_abs --config "$WORK/analysis.conf"
need_local_abs --benchmark-pairs "$WORK/benchmark-pairs.json"
say "CHECK ok locations: input $O_input_root, output $O_output_root, checkpoint $CHECKPOINT_URI"

OPENS="-Djdk.reflect.useDirectMethodHandle=false"
for p in java.lang java.lang.invoke java.lang.reflect java.io java.net java.nio java.util \
  java.util.concurrent java.util.concurrent.atomic sun.nio.ch sun.nio.cs sun.security.action sun.util.calendar; do
  OPENS="$OPENS --add-opens=java.base/$p=ALL-UNNAMED"
done

# build_submit_argv <spark timeout seconds> <java home>: sets SUBMIT_ARGV (timeout + env + spark-submit)
build_submit_argv() {
  SUBMIT_ARGV=(
    /usr/bin/timeout --foreground -k "$O_kill_after_seconds" "$1"
    env SPARK_HOME="$SPARK_HOME_DIR" SPARK_CONF_DIR="$ROOT/spark-conf" HADOOP_CONF_DIR=/etc/hadoop/conf
    JAVA_HOME="$2" "$SPARK_HOME_DIR/bin/spark-submit"
    --master yarn --deploy-mode client --name scholarchain-collaboration-analysis
    --class scholarchain.spark.job.CollaborationJob --driver-memory 1g
    --num-executors 2 --executor-cores 1 --executor-memory 2g
    --driver-class-path "$RUNTIME_CP" --driver-java-options "$OPENS"
    --conf "spark.executor.extraClassPath=$RUNTIME_CP" --conf "spark.executor.extraJavaOptions=$OPENS"
    --conf "spark.executorEnv.JAVA_HOME=$2" --conf "spark.yarn.appMasterEnv.JAVA_HOME=$2"
    --conf spark.dynamicAllocation.enabled=false
    --conf spark.eventLog.enabled=true --conf "spark.eventLog.dir=$EVENTS_URI"
    --conf "spark.yarn.stagingDir=$STAGING_URI"
    --conf spark.yarn.maxAppAttempts=1 --conf spark.hadoop.fs.s3a.directory.marker.retention=keep
    "$WORK/job.jar"
    --input-root "$O_input_root" --curated-run "$O_curated_run" --decision-run "$O_decision_run"
    --grouping-run "$O_grouping_run" --output-root "$O_output_root"
    --checkpoint-dir "$CHECKPOINT_URI"
    --config "$WORK/analysis.conf" --benchmark-pairs "$WORK/benchmark-pairs.json"
    --code-version "$O_code_version" --attempt "$O_attempt" --partitions "$O_partitions"
    --checkpoint-interval "$O_checkpoint_interval" --max-tree-entries "$O_max_tree_entries"
    --max-tree-depth "$O_max_tree_depth" --max-driver-validation-rows "$O_max_driver_validation_rows"
    --max-driver-validation-bytes "$O_max_driver_validation_bytes" --max-metadata-bytes "$O_max_metadata_bytes"
  )
}
spark_timeout_now() { # remaining step budget minus the post-spark reserve, refused below the minimum
  local left
  left=$((O_step_timeout_seconds - ($(date +%s) - START_EPOCH) - POST_RESERVE_SECONDS))
  ((left >= MIN_SPARK_SECONDS)) || die 3 "only ${left}s of the ${O_step_timeout_seconds}s step budget left for spark-submit (< $MIN_SPARK_SECONDS)"
  printf '%s' "$left"
}

if [[ $SELF_CHECK == yes ]]; then
  # Same two calls as the real run below (select_java17, then build_submit_argv with its JAVA_HOME_DIR),
  # on the stub root, so the selected home -> JAVA_HOME= / AM / executor pins link is tested (CR-P6-49 m2).
  select_java17 "$SAMPLE_JVM_ROOT"
  SPARK_TIMEOUT=$(spark_timeout_now)
  build_submit_argv "$SPARK_TIMEOUT" "$JAVA_HOME_DIR"
  i=0
  for a in "${SUBMIT_ARGV[@]}"; do
    printf '[%s] ARGV %03d %s\n' "$SCRIPT_NAME" "$i" "$a"
    i=$((i + 1))
  done
  say "RESULT SELF-CHECK-OK fs.defaultFS=$DEFAULT_FS authority=$HDFS_AUTHORITY argc=$i"
  exit 0
fi

# ==== real run on the primary node ==================================================================
# shellcheck disable=SC2329 # invoked by the EXIT trap below
cleanup_work() { rm -f "$WORK"/* 2>/dev/null || true; rmdir "$WORK" 2>/dev/null || true; }
trap cleanup_work EXIT

fetch_verified() { # fetch_verified <key> <sha256> <local name>
  aws s3 cp --only-show-errors --region "$O_region" "s3://$O_data_bucket/$1" "$WORK/$3" || die 4 "download of $1 failed"
  [[ $(sha256sum "$WORK/$3" | awk '{ print $1 }') == "$2" ]] || die 3 "$1 does not match sha256 $2"
  say "CHECK ok $3 sha256 matches"
}
say "STEP fetch job inputs"
fetch_verified "$O_job_jar_key" "$O_job_jar_sha256" job.jar
fetch_verified "$O_config_key" "$O_config_sha256" analysis.conf
fetch_verified "$O_pairs_key" "$O_pairs_sha256" benchmark-pairs.json

select_java17 "$JVM_ROOT" # never the inherited JAVA_HOME or PATH java (IE-P6-12)

say "STEP hdfs directories under $HDFS_ROOT"
STEP_USER=$(id -un)
if ! hdfs dfs -test -d "$HDFS_ROOT"; then
  sudo -n -u hdfs hdfs dfs -mkdir -p "$HDFS_ROOT" || die 4 "the HDFS superuser could not create $HDFS_ROOT"
  sudo -n -u hdfs hdfs dfs -chown "$STEP_USER" "$HDFS_ROOT" || die 4 "could not give $HDFS_ROOT to $STEP_USER"
  sudo -n -u hdfs hdfs dfs -chmod 0750 "$HDFS_ROOT" || die 4 "could not set the mode of $HDFS_ROOT"
fi
[[ $(hdfs dfs -stat '%u' "$HDFS_ROOT") == "$STEP_USER" ]] || die 4 "$HDFS_ROOT is not owned by the step user $STEP_USER"
hdfs dfs -mkdir -p "$EVENTS_URI" "$STAGING_URI" "$HDFS_ROOT/checkpoints" || die 4 "cannot create the directories under $HDFS_ROOT"
if hdfs dfs -test -e "$CHECKPOINT_URI"; then die 3 "$CHECKPOINT_URI already exists (one submission id, one run)"; fi
BEFORE=$(hdfs dfs -ls -C "$EVENTS_URI" | LC_ALL=C sort) || die 4 "cannot list $EVENTS_URI"
say "CHECK ok hdfs directories owned by $STEP_USER"

say "STEP spark-submit"
SPARK_TIMEOUT=$(spark_timeout_now)
build_submit_argv "$SPARK_TIMEOUT" "$JAVA_HOME_DIR"
say "BOUND spark_timeout_seconds=$SPARK_TIMEOUT kill_after_seconds=$O_kill_after_seconds step_timeout_seconds=$O_step_timeout_seconds"
set +e
"${SUBMIT_ARGV[@]}"
SPARK_RC=$?
set -e
if ((SPARK_RC == 124)); then say "RESULT spark_exit=124 (timed out after ${SPARK_TIMEOUT}s)"; else say "RESULT spark_exit=$SPARK_RC"; fi

say "STEP copy event log"
AFTER=$(hdfs dfs -ls -C "$EVENTS_URI" | LC_ALL=C sort) || die 4 "cannot list $EVENTS_URI"
NEW=$(LC_ALL=C comm -13 <(printf '%s\n' "$BEFORE") <(printf '%s\n' "$AFTER") | grep . || true)
[[ $(printf '%s' "$NEW" | grep -c . || true) == 1 ]] || die 1 "expected exactly one new event log, found: ${NEW:-none}"
EVENT_NAME=${NEW##*/}
[[ $EVENT_NAME =~ ^[A-Za-z0-9._-]+$ ]] || die 1 "unexpected event log name $EVENT_NAME"
hdfs dfs -get "$NEW" "$WORK/$EVENT_NAME" || die 1 "cannot read $NEW"
KEY="spark-events/$O_submission_id/$EVENT_NAME"
aws s3api put-object --region "$O_region" --bucket "$O_logs_bucket" --key "$KEY" \
  --body "$WORK/$EVENT_NAME" --if-none-match '*' >/dev/null || die 1 "create-once copy to s3://$O_logs_bucket/$KEY failed"
say "CHECK ok event log s3://$O_logs_bucket/$KEY ($(wc -c <"$WORK/$EVENT_NAME" | tr -d ' ') bytes)"

# ---- own checkpoint removal (repair 02 item 5) ---------------------------------------------------
# After a successful job only: remove THIS submission's checkpoint directory, and nothing else, so
# HDFS utilization cannot keep the cluster from idling out. The path is rebuilt from the validated
# authority and submission id and compared before use. Any failure is logged, never fatal: the step
# result stays spark-submit's, and the step timeout plus cleanup Phase A still bound cost. The removal
# is bounded by the remaining step budget so it cannot turn a successful step into a timed-out one.
remove_own_checkpoint() {
  local expected left rm_t
  expected="hdfs://$HDFS_AUTHORITY$HDFS_BASE/checkpoints/$O_submission_id"
  if [[ $CHECKPOINT_URI != "$expected" || ! $O_submission_id =~ ^[a-z0-9-]{8,64}$ ]]; then
    say "NOTE checkpoint-cleanup skipped: $CHECKPOINT_URI is not this submission's checkpoint path"
    return 0
  fi
  # Each hdfs call ends at most <t> + 5 s (timeout -k 5) after it starts.
  left=$((O_step_timeout_seconds - ($(date +%s) - START_EPOCH) - CHECKPOINT_RM_MARGIN_SECONDS))
  if ((left < 10 + 5 + 10 + 5)); then
    say "NOTE checkpoint-cleanup skipped: only ${left}s of the step budget left; $CHECKPOINT_URI stays (idle detection may be delayed; step timeout and Phase A still bound cost)"
    return 0
  fi
  if ! /usr/bin/timeout -k 5 10 hdfs dfs -test -d "$CHECKPOINT_URI"; then
    say "NOTE checkpoint-cleanup: $CHECKPOINT_URI is absent or could not be tested; nothing removed"
    return 0
  fi
  left=$((O_step_timeout_seconds - ($(date +%s) - START_EPOCH) - CHECKPOINT_RM_MARGIN_SECONDS - 5))
  rm_t=$((left < CHECKPOINT_RM_MAX_SECONDS ? left : CHECKPOINT_RM_MAX_SECONDS))
  if ((rm_t < 10)); then
    say "NOTE checkpoint-cleanup skipped: only ${left}s of the step budget left after the test; $CHECKPOINT_URI stays"
    return 0
  fi
  if /usr/bin/timeout -k 5 "$rm_t" hdfs dfs -rm -r -skipTrash "$CHECKPOINT_URI" >/dev/null; then
    say "CHECK ok checkpoint-cleanup removed $CHECKPOINT_URI"
  else
    printf '[%s] NOTE checkpoint-cleanup FAILED for %s (logged, not fatal; step timeout and Phase A still bound cost)\n' "$SCRIPT_NAME" "$CHECKPOINT_URI" >&2
  fi
}
if ((SPARK_RC == 0)); then
  say "STEP remove own checkpoint $CHECKPOINT_URI"
  remove_own_checkpoint || true
fi
exit "$SPARK_RC"
