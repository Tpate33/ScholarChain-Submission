#!/usr/bin/env bash
# shellcheck disable=SC2154 # C_* are assigned by parse_kv through printf -v (lib/common.sh)
# =====================================================================================================
# scripts/run-bounded-spark.sh -- one bounded run of the ScholarChain Spark job on the transient EMR
# cluster of stack emr-run (lifecycle L2). Owner: infra-engineer. Work order IE-P6-02.
#
# NOT APPROVED FOR EXECUTION AGAINST AWS until the human has ratified the environment, applied the
# signed base and emr-run plans, and reviewed a --dry-run of the committed run config.
#
# USAGE
#   scripts/run-bounded-spark.sh --dry-run <environment> <run-config>
#   scripts/run-bounded-spark.sh --stage   <environment> <run-config>     (token required)
#   scripts/run-bounded-spark.sh --execute <environment> <run-config>     (token required)
#
#   <run-config>  committed file infra/terraform/environments/<environment>/runs/<name>.conf
#   Account, Region and the operator profile come from infra/terraform/environments/<env>/env.conf.
#   Bucket and cluster names are derived: scholarchain-<env>-{data,logs}-<account>-<region>,
#   scholarchain-<env>-spark-run. Run from the repository root on a clean tree. No interactive prompt.
#
# MODES
#   --dry-run  validates every input and bound, hashes the local artifacts that are present, prints the
#              cost estimate, the planned AWS calls of both other modes and their two confirmation
#              tokens. Makes NO AWS call and writes nothing.
#   --stage    (SCHOLARCHAIN_CONFIRM_SPARK_RUN = the stage token) uploads create-once, with a SHA-256
#              integrity checksum, every artifact, the two config files, the input tree and its
#              manifest. An object that already exists must carry the same ChecksumSHA256 (skipped) or
#              the run refuses. Never deletes or overwrites.
#   --execute  (SCHOLARCHAIN_CONFIRM_SPARK_RUN = the execute token) verifies the cluster (state, release,
#              Hadoop only, step concurrency 1, instance count and types, no scaling, idle timeout,
#              bootstrap action path and digests, S3A marker and YARN AM-attempt configuration, tags),
#              the staged objects, writes the run record create-once, submits ONE step, checks the step
#              as EMR recorded it (ActionOnFailure, timeout prefix), polls with a deadline, cancels only
#              its own step on deadline or interrupt (INT, TERM, HUP), verifies the published run and
#              the event log, and writes the run summary create-once. It never creates, resizes or
#              terminates a cluster itself.
#
# COST BOUND ON THE CLUSTER SIDE (CR-P6-27 M2; holds when this script, the Terminal, the network or
# the credentials go away): the step is `/usr/bin/timeout -k <kill> <step timeout>` around the wrapper
# (spark-submit inside it has its own timeout), with ActionOnFailure TERMINATE_CLUSTER, so a hung or
# failed step ends the cluster at most step_timeout + kill after it starts; a successful step leaves the
# cluster idle, and the emr-run idle auto-termination ends it after max_idle_timeout_seconds. Before
# the step, the same idle auto-termination bounds the wait for --execute.
#   window = provisioning + idle (before the step) + step + kill + idle (after) + termination
#
# HARD CAPS (a run config may only tighten them; changing them is a reviewed change to this file)
readonly HC_MAX_INSTANCES=2
readonly HC_ALLOWED_INSTANCE_TYPES="m5.xlarge m6i.xlarge"
readonly HC_RELEASE_LABEL=emr-7.14.0
readonly HC_MAX_IDLE_TIMEOUT_SECONDS=1800
readonly HC_MAX_STEP_TIMEOUT_MINUTES=30 # equals MAX_STEP_TIMEOUT_SECONDS / 60 of the wrapper
readonly HC_MIN_STEP_TIMEOUT_MINUTES=7  # equals MIN_STEP_TIMEOUT_SECONDS / 60 of the wrapper
readonly HC_KILL_AFTER_SECONDS=60       # SIGKILL this long after the step timeout's SIGTERM
readonly HC_STEP_GRACE_SECONDS=120      # operator deadline after the cluster-side bound (state lag)
readonly HC_PROVISIONING_MINUTES=20     # allowance in the cost window (terraform apply waits for it)
readonly HC_CANCEL_WAIT_MINUTES=10
readonly HC_TERMINATION_MINUTES=5
readonly HC_MIN_POLL_SECONDS=15
readonly HC_MAX_POLL_SECONDS=120
readonly HC_MAX_COST_USD=2
# Verified us-east-1 On-Demand rates (emr-docs-research-03.md): EC2 0.192 + EMR 0.048 per instance-hour;
# gp3 0.08 per GB-month; EBS per instance 15 GiB root + 32 GiB data.
readonly HC_MIN_INSTANCE_HOUR_PRICE_USD=0.24
readonly EBS_GIB_PER_INSTANCE=47
readonly GP3_USD_PER_GB_MONTH=0.08
# Interface endpoints of stack emr-run (KMS, CR-P6-27 6b). Rate UNVERIFIED in this session (reference
# 0.01 USD per endpoint-AZ-hour, bounded here at twice that); one AZ.
readonly HC_INTERFACE_ENDPOINTS=1
readonly HC_INTERFACE_ENDPOINT_USD_PER_HOUR=0.02
readonly HC_MAX_INPUT_OBJECTS=2000
readonly HC_MAX_INPUT_BYTES=268435456
readonly HC_MAX_OUTPUT_PREFIXES=1000
#
# MARKERS  [run-bounded-spark] STEP / CHECK ok / NOTE / BOUND / PLAN-AWS-CALL / AWS-CALL (stderr) /
#          CONFIRM / CONFIRMATION-TOKEN <mode> / SUMMARY-BEGIN / SUMMARY-END / RESULT / FAIL (stderr)
# EXIT     0 ok, 2 usage, 3 refused, 4 AWS precondition, 5 step failed/cancelled/timed out, 6 verify
# =====================================================================================================
set -euo pipefail
readonly SCRIPT_NAME=run-bounded-spark
# shellcheck source=scripts/lib/common.sh disable=SC1091
. "$(dirname "$0")/lib/common.sh"
readonly CONFIRM_ENV_VAR=SCHOLARCHAIN_CONFIRM_SPARK_RUN
readonly CONFIG_SCHEMA=scholarchain.bounded-spark-config.v1
readonly SUMMARY_SCHEMA=scholarchain.bounded-spark-summary.v1
readonly INSTALL_SCRIPT=scripts/emr/install-spark-dist.sh
readonly SUBMIT_SCRIPT=scripts/emr/submit-collaboration-job.sh
readonly WRAPPER=/opt/scholarchain/bin/submit-collaboration-job.sh
readonly JOB_JAR_NAME=scholarchain-spark-jobs-0.1.0.jar
readonly CONFIG_KEYS="config_version environment attempt emr_release_label instance_type max_instances \
max_idle_timeout_seconds step_timeout_minutes poll_interval_seconds instance_hour_price_usd max_cost_usd \
code_version job_jar_local_path job_jar_sha256 spark_dist_local_path spark_dist_sha512 spark_dist_sha256 \
scala_library_local_path scala_library_sha256 hadoop_aws_local_path hadoop_aws_sha1 hadoop_aws_sha256 \
sdk_bundle_local_path sdk_bundle_sha256 analysis_config_path benchmark_pairs_path input_name \
input_archive_home_relative input_tree_sha256 curated_run decision_run grouping_run partitions \
checkpoint_interval max_tree_entries max_tree_depth max_driver_validation_rows \
max_driver_validation_bytes max_metadata_bytes"

usage() { die "$EX_USAGE" "usage: scripts/run-bounded-spark.sh --dry-run|--stage|--execute <environment> <run-config>"; }
(($# == 3)) || usage
case $1 in --dry-run) MODE=dry-run ;; --stage) MODE=stage ;; --execute) MODE=execute ;; *) usage ;; esac
ARG_ENV=$2
ARG_CONFIG=$3
is_env_name "$ARG_ENV" || die "$EX_USAGE" "environment '$ARG_ENV' is not a short lowercase name"
need_tools git jq awk shasum xxd base64 find sort comm
step "start mode=$MODE env=$ARG_ENV at=$(now_utc)"

# ---- repository, environment, run config -----------------------------------------------------------
require_repo_root
require_clean_tree
load_env_conf "$ARG_ENV"
is_repo_path "$ARG_CONFIG" || die "$EX_USAGE" "run config path is not a plain repository path"
[[ $ARG_CONFIG == infra/terraform/environments/$ARG_ENV/runs/*.conf ]] ||
  die "$EX_USAGE" "run config must be infra/terraform/environments/$ARG_ENV/runs/<name>.conf"
require_tracked "run config" "$ARG_CONFIG"
parse_kv "$ARG_CONFIG" "$CONFIG_KEYS" C_ "=~"
[[ $C_config_version == "$CONFIG_SCHEMA" ]] || die "$EX_USAGE" "config_version must be $CONFIG_SCHEMA"
[[ $C_environment == "$ARG_ENV" ]] || die "$EX_REFUSED" "config environment '$C_environment' differs from '$ARG_ENV'"
need_int_range attempt "$C_attempt" 1 99
[[ $C_emr_release_label == "$HC_RELEASE_LABEL" ]] || die "$EX_REFUSED" "emr_release_label must be $HC_RELEASE_LABEL"
in_list "$C_instance_type" "$HC_ALLOWED_INSTANCE_TYPES" || die "$EX_REFUSED" "instance_type must be one of: $HC_ALLOWED_INSTANCE_TYPES"
need_int_range max_instances "$C_max_instances" 2 "$HC_MAX_INSTANCES"
need_int_range max_idle_timeout_seconds "$C_max_idle_timeout_seconds" 60 "$HC_MAX_IDLE_TIMEOUT_SECONDS"
# Minimum 7 minutes = the wrapper's MIN_STEP_TIMEOUT_SECONDS (420 = POST_RESERVE_SECONDS 240 +
# MIN_SPARK_SECONDS 120 + PREPARATION_ALLOWANCE_SECONDS 60), so a config the cluster would refuse is
# refused locally (CR-P6-28 n3, CR-P6-29 n4: 360 s left no time for the downloads and HDFS setup).
need_int_range step_timeout_minutes "$C_step_timeout_minutes" "$HC_MIN_STEP_TIMEOUT_MINUTES" "$HC_MAX_STEP_TIMEOUT_MINUTES"
need_int_range poll_interval_seconds "$C_poll_interval_seconds" "$HC_MIN_POLL_SECONDS" "$HC_MAX_POLL_SECONDS"
is_decimal "$C_instance_hour_price_usd" || die "$EX_USAGE" "instance_hour_price_usd is not a decimal"
dec_le "$HC_MIN_INSTANCE_HOUR_PRICE_USD" "$C_instance_hour_price_usd" ||
  die "$EX_REFUSED" "instance_hour_price_usd must be >= the verified $HC_MIN_INSTANCE_HOUR_PRICE_USD"
is_decimal "$C_max_cost_usd" || die "$EX_USAGE" "max_cost_usd is not a decimal"
dec_le "$C_max_cost_usd" "$HC_MAX_COST_USD" || die "$EX_REFUSED" "max_cost_usd exceeds the hard cap $HC_MAX_COST_USD"
need_hex code_version "$C_code_version" 40
[[ $C_code_version != 0000000000000000000000000000000000000000 ]] || die "$EX_USAGE" "code_version must be a real commit"
git cat-file -e "$C_code_version^{commit}" 2>/dev/null || die "$EX_REFUSED" "code_version $C_code_version is not a commit in this repository"
git merge-base --is-ancestor "$C_code_version" HEAD || die "$EX_REFUSED" "code_version is not an ancestor of HEAD"
# Code-scope binding (CR-P6-37 m1, IE-P6-04 repair 01), every mode: the jar was built and gated at
# code_version, so the build and the code it runs (and the analysis config and benchmark pairs read from
# the run HEAD under modules/spark) must be unchanged between code_version and HEAD. Tree-to-tree
# comparison of committed blobs (the tree is clean, checked above); exit 1 = differs, other = git error.
readonly CODE_SCOPE="build.sbt project modules/core modules/spark"
CS_RC=0
# shellcheck disable=SC2086 # CODE_SCOPE is a fixed list of plain paths, split on purpose
git diff --quiet --no-ext-diff --no-textconv "$C_code_version" HEAD -- $CODE_SCOPE || CS_RC=$?
if ((CS_RC == 1)); then
  # shellcheck disable=SC2086
  git diff --name-only --no-ext-diff "$C_code_version" HEAD -- $CODE_SCOPE | head -n 50 >&2 || true
  die "$EX_REFUSED" "code scope ($CODE_SCOPE) differs between code_version $C_code_version and HEAD $HEAD_SHA (files above); the job jar no longer matches the run tree: rerun sbt sparkSubmitLocal at the new commit and update code_version, the jar and its pins in the run config"
elif ((CS_RC != 0)); then
  die "$EX_REFUSED" "git diff of the code scope between code_version $C_code_version and HEAD failed (exit $CS_RC)"
fi
ok "code scope ($CODE_SCOPE) unchanged between code_version $C_code_version and HEAD"
for k in job_jar_sha256 spark_dist_sha256 scala_library_sha256 hadoop_aws_sha256 sdk_bundle_sha256 input_tree_sha256; do
  v="C_$k"
  need_hex "$k" "${!v}" 64
done
need_hex spark_dist_sha512 "$C_spark_dist_sha512" 128
need_hex hadoop_aws_sha1 "$C_hadoop_aws_sha1" 40
[[ $C_input_name =~ ^[a-z0-9][a-z0-9.-]{0,62}$ ]] || die "$EX_USAGE" "input_name is malformed"
for k in curated_run decision_run grouping_run input_archive_home_relative; do
  v="C_$k"
  is_repo_path "${!v}" || die "$EX_USAGE" "$k is not a plain relative path"
done
# Home-relative paths (CR-P6-30 n4): no segment may start with '.' (.aws, .ssh, .., and so on), so
# --dry-run cannot hash, count or list files in hidden directories under $HOME. Checked here, before
# any local file is hashed or listed.
no_dot_segment() { [[ /$1 != */.* ]]; }
need_local_path_value() { # need_local_path_value <key> <value>: repository-relative or ~/home-relative
  local rel=$2
  if [[ $2 == \~/* ]]; then # a literal "~/" prefix (no tilde expansion intended)
    rel=${2#\~/}
    no_dot_segment "$rel" || die "$EX_REFUSED" "$1 '$2': a home-relative path may not have a segment starting with '.'"
  fi
  is_repo_path "$rel" || die "$EX_USAGE" "$1 '$2' is not a plain repository-relative or ~/ home-relative path"
}
no_dot_segment "$C_input_archive_home_relative" ||
  die "$EX_REFUSED" "input_archive_home_relative '$C_input_archive_home_relative' may not have a segment starting with '.'"
for k in job_jar_local_path spark_dist_local_path scala_library_local_path hadoop_aws_local_path sdk_bundle_local_path; do
  v="C_$k"
  need_local_path_value "$k" "${!v}"
done
for k in partitions checkpoint_interval max_tree_entries max_tree_depth max_driver_validation_rows \
  max_driver_validation_bytes max_metadata_bytes; do
  v="C_$k"
  need_int_range "$k" "${!v}" 1 1073741824
done
require_tracked "analysis config" "$C_analysis_config_path"
require_tracked "benchmark pairs" "$C_benchmark_pairs_path"
require_tracked "install script" "$INSTALL_SCRIPT"
require_tracked "submit script" "$SUBMIT_SCRIPT"
ok "run config $ARG_CONFIG"

local_path() { # local_path <value>: repository-relative or ~/home-relative
  local rel=$1 base=""
  if [[ $1 == \~/* ]]; then # a literal "~/" prefix (no tilde expansion intended)
    rel=${1#\~/}
    base="$HOME/"
    no_dot_segment "$rel" || die "$EX_REFUSED" "home-relative path $1 has a segment starting with '.'"
  fi
  is_repo_path "$rel" || die "$EX_USAGE" "bad local path $1"
  printf '%s%s' "$base" "$rel"
}

# ---- derived names ---------------------------------------------------------------------------------
ACCOUNT=$ENV_aws_account_id
REGION=$ENV_aws_region
PROFILE=$ENV_aws_operator_profile
DATA_BUCKET="scholarchain-$ARG_ENV-data-$ACCOUNT-$REGION"
LOGS_BUCKET="scholarchain-$ARG_ENV-logs-$ACCOUNT-$REGION"
CLUSTER_NAME="scholarchain-$ARG_ENV-spark-run"
INSTALL_SHA256=$(sha256_file "$INSTALL_SCRIPT")
SUBMIT_SHA256=$(sha256_file "$SUBMIT_SCRIPT")
ANALYSIS_SHA256=$(sha256_file "$C_analysis_config_path")
PAIRS_SHA256=$(sha256_file "$C_benchmark_pairs_path")
CONFIG_SHA256=$(sha256_file "$ARG_CONFIG")
K_SPARK="artifacts/spark-dist/$C_spark_dist_sha512/spark-3.5.5-bin-hadoop3-scala2.13.tgz"
K_SCALA="artifacts/runtime-jars/$C_scala_library_sha256/scala-library-2.13.16.jar"
K_HAWS="artifacts/runtime-jars/$C_hadoop_aws_sha256/hadoop-aws-3.3.4.jar"
K_SDK="artifacts/runtime-jars/$C_sdk_bundle_sha256/aws-java-sdk-bundle-1.12.262.jar"
K_INSTALL="artifacts/emr-scripts/$INSTALL_SHA256/install-spark-dist.sh"
K_SUBMIT="artifacts/emr-scripts/$SUBMIT_SHA256/submit-collaboration-job.sh"
K_JAR="artifacts/job/$C_code_version/$JOB_JAR_NAME"
K_ANALYSIS="config/$ANALYSIS_SHA256/${C_analysis_config_path##*/}"
K_PAIRS="config/$PAIRS_SHA256/${C_benchmark_pairs_path##*/}"
K_TREE="config/input-trees/$C_input_tree_sha256/input-tree.tsv"
K_INPUT="input/$C_input_name"
K_OUTPUT="output/spark"
ARCHIVE_ROOT="$HOME/$C_input_archive_home_relative"
# Every location handed to the job must pass the job's StorePath rules (CR-P6-27 M1): s3a://<bucket>/
# <segments>, segments of [A-Za-z0-9._=-] only (is_repo_path), never empty, "." or "..".
for b in "$DATA_BUCKET" "$LOGS_BUCKET"; do
  [[ $b =~ ^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$ && $b != *..* ]] || die "$EX_USAGE" "derived bucket name '$b' is not a valid bucket name"
done
for k in "$K_INPUT" "$K_OUTPUT" "$C_curated_run" "$C_decision_run" "$C_grouping_run"; do
  is_repo_path "$k" || die "$EX_USAGE" "job location part '$k' has characters or segments the job refuses"
done

# ---- cost bound (cluster side, see header) ---------------------------------------------------------
STEP_TIMEOUT_SECONDS=$((C_step_timeout_minutes * 60))
IDLE_MIN=$(((C_max_idle_timeout_seconds + 59) / 60))
STEP_BOUND_MIN=$(((STEP_TIMEOUT_SECONDS + HC_KILL_AFTER_SECONDS + 59) / 60))
WINDOW_MIN=$((HC_PROVISIONING_MINUTES + IDLE_MIN + STEP_BOUND_MIN + IDLE_MIN + HC_TERMINATION_MINUTES))
ESTIMATE=$(awk -v n="$C_max_instances" -v p="$C_instance_hour_price_usd" -v g="$EBS_GIB_PER_INSTANCE" \
  -v r="$GP3_USD_PER_GB_MONTH" -v m="$WINDOW_MIN" -v e="$HC_INTERFACE_ENDPOINTS" -v ep="$HC_INTERFACE_ENDPOINT_USD_PER_HOUR" \
  'BEGIN { printf "%.4f", (n * (p + g * r / 730) + e * ep) * m / 60 }')
dec_le "$ESTIMATE" "$C_max_cost_usd" || die "$EX_REFUSED" "worst-case estimate $ESTIMATE USD exceeds max_cost_usd=$C_max_cost_usd"
bound() { say "BOUND $1=$2 cap=$3 source=$4"; }
bound instances "$C_max_instances" "$HC_MAX_INSTANCES" "config/HC_MAX_INSTANCES"
bound instance_type "$C_instance_type" "$HC_ALLOWED_INSTANCE_TYPES" "config/HC_ALLOWED_INSTANCE_TYPES"
bound idle_timeout_seconds "$C_max_idle_timeout_seconds" "$HC_MAX_IDLE_TIMEOUT_SECONDS" "config/emr-run idle_timeout_seconds"
bound step_timeout_minutes "$C_step_timeout_minutes" "$HC_MAX_STEP_TIMEOUT_MINUTES" "config; enforced on the cluster by /usr/bin/timeout -k $HC_KILL_AFTER_SECONDS $STEP_TIMEOUT_SECONDS"
bound step_action_on_failure TERMINATE_CLUSTER - "a failed or timed-out step ends the cluster"
bound cost_window_minutes "$WINDOW_MIN" "-" "provisioning $HC_PROVISIONING_MINUTES + idle $IDLE_MIN + step $STEP_BOUND_MIN (timeout + kill) + idle $IDLE_MIN + termination $HC_TERMINATION_MINUTES"
bound worst_case_cost_usd "$ESTIMATE" "$C_max_cost_usd" "(instances x (price + EBS) + interface endpoints x rate) x window; S3 requests and storage excluded (cents); the KMS endpoint keeps billing until cleanup Phase A"
bound max_cost_usd "$C_max_cost_usd" "$HC_MAX_COST_USD" "config/HC_MAX_COST_USD"
bound steps 1 1 "one step per submission id; step concurrency 1"
# Both allowed instance types have 4 vCPU. The account quota L-1216C47A (Running On-Demand Standard
# instances, vCPUs) must cover the cluster before emr-run is applied; scripts/deploy.sh refuses the
# emr-run apply below 8 (IE-P6-03). This script makes no quota call.
bound vcpus "$((C_max_instances * 4))" "$((HC_MAX_INSTANCES * 4))" "instances x 4 vCPU; EC2 quota L-1216C47A in $REGION must be >= this before emr-run is applied (checked by scripts/deploy.sh)"

# ---- local artifacts (required for --stage; reported for --dry-run) --------------------------------
LOCAL_MISSING=0
check_local() { # check_local <label> <path value> <algo> <expected> [<algo> <expected>]
  local label=$1 p
  p=$(local_path "$2")
  if [[ ! -f $p || -L $p ]]; then
    [[ $MODE == stage ]] && die "$EX_REFUSED" "$label: $p is not a regular file"
    note "$label: $p not present locally (needed for --stage only)"
    LOCAL_MISSING=$((LOCAL_MISSING + 1))
    return 0
  fi
  shift 2
  while (($# >= 2)); do
    [[ $(digest_file "$1" "$p") == "$2" ]] || die "$EX_REFUSED" "$label: sha$1 of $p differs from the config"
    shift 2
  done
  ok "$label $p digests match"
}
build_tree() { # build_tree <root> <out file>: relative path, bytes, sha256 per file, sorted
  local root=$1 out=$2 rel n=0 total=0 b
  [[ -d $root && ! -L $root ]] || return 1
  [[ -z $(find "$root" ! -type f ! -type d -print | head -n 1) ]] || die "$EX_REFUSED" "$root holds a symlink or special file"
  : >"$out"
  while IFS= read -r rel; do
    is_repo_path "$rel" || die "$EX_REFUSED" "input file name '$rel' has unexpected characters"
    b=$(file_bytes "$root/$rel")
    n=$((n + 1))
    total=$((total + b))
    ((n <= HC_MAX_INPUT_OBJECTS && total <= HC_MAX_INPUT_BYTES)) || die "$EX_REFUSED" "input tree exceeds $HC_MAX_INPUT_OBJECTS objects or $HC_MAX_INPUT_BYTES bytes"
    printf '%s\t%s\t%s\n' "$rel" "$b" "$(sha256_file "$root/$rel")" >>"$out"
  done < <(cd "$root" && find . -type f ! -name .DS_Store -print | sed 's|^\./||' | LC_ALL=C sort)
  ((n > 0)) || die "$EX_REFUSED" "input tree $root is empty"
  ok "input tree $root: $n files, $total bytes"
}

TMP=$(mktemp -d "${TMPDIR:-/tmp}/scholarchain-brs.XXXXXXXX")
LOCK_DIR="${TMPDIR:-/tmp}/scholarchain-bounded-spark.lock"
HAVE_LOCK=no
# shellcheck disable=SC2329 # invoked by the EXIT trap below
cleanup() {
  find "$TMP" -type f -exec rm -f {} + 2>/dev/null || true
  rmdir "$TMP" 2>/dev/null || true
  if [[ $HAVE_LOCK == yes ]]; then rmdir "$LOCK_DIR" 2>/dev/null || true; fi
}
trap cleanup EXIT

if [[ $MODE != execute ]]; then
  step "local artifacts"
  check_local "job jar" "$C_job_jar_local_path" 256 "$C_job_jar_sha256"
  check_local "spark distribution" "$C_spark_dist_local_path" 512 "$C_spark_dist_sha512" 256 "$C_spark_dist_sha256"
  check_local "scala-library" "$C_scala_library_local_path" 256 "$C_scala_library_sha256"
  check_local "hadoop-aws" "$C_hadoop_aws_local_path" 1 "$C_hadoop_aws_sha1" 256 "$C_hadoop_aws_sha256"
  check_local "aws-java-sdk-bundle" "$C_sdk_bundle_local_path" 256 "$C_sdk_bundle_sha256"
  if build_tree "$ARCHIVE_ROOT" "$TMP/input-tree.tsv"; then
    # 64 zeros is the documented sentinel: --dry-run prints the computed value and refuses.
    [[ $(sha256_file "$TMP/input-tree.tsv") == "$C_input_tree_sha256" ]] ||
      die "$EX_REFUSED" "computed input tree manifest sha256 $(sha256_file "$TMP/input-tree.tsv") differs from input_tree_sha256=$C_input_tree_sha256 (review the tree, then commit the computed value)"
    ok "input tree manifest matches input_tree_sha256"
  else
    [[ $MODE == stage ]] && die "$EX_REFUSED" "input archive $ARCHIVE_ROOT is not a directory"
    note "input archive $ARCHIVE_ROOT not present locally (needed for --stage only)"
  fi
fi

# ---- tokens and planned calls ----------------------------------------------------------------------
MATERIAL="$SCRIPT_NAME|env=$ARG_ENV|account=$ACCOUNT|region=$REGION|profile=$PROFILE|cluster=$CLUSTER_NAME"
MATERIAL="$MATERIAL|head=$HEAD_SHA|config=$CONFIG_SHA256|install=$INSTALL_SHA256|submit=$SUBMIT_SHA256"
MATERIAL="$MATERIAL|jar=$C_job_jar_sha256|spark=$C_spark_dist_sha512|tree=$C_input_tree_sha256|analysis=$ANALYSIS_SHA256"
MATERIAL="$MATERIAL|pairs=$PAIRS_SHA256|attempt=$C_attempt|instances=$C_max_instances|timeout=$C_step_timeout_minutes|kill=$HC_KILL_AFTER_SECONDS|cost=$C_max_cost_usd|estimate=$ESTIMATE"
MATERIAL_HASH=$(sha256_text "$MATERIAL")
SUBMISSION_ID="a$C_attempt-${MATERIAL_HASH:0:16}"
token_for() { printf 'brs-%s' "$(sha256_text "$MATERIAL|mode=$1" | cut -c1-32)"; }
AWS_BASE=(aws --profile "$PROFILE" --region "$REGION" --output json --cli-connect-timeout 10 --cli-read-timeout 300)

readonly STEP_TIMEOUT_PREFIX_JSON="[\"/usr/bin/timeout\",\"-k\",\"$HC_KILL_AFTER_SECONDS\",\"$STEP_TIMEOUT_SECONDS\"]"
step_args_json() { # the wrapper invocation under the cluster-side timeout, one EMR step
  jq -cn --argjson pre "$STEP_TIMEOUT_PREFIX_JSON" --arg st "$STEP_TIMEOUT_SECONDS" --arg ka "$HC_KILL_AFTER_SECONDS" \
    --arg w "$WRAPPER" --arg db "$DATA_BUCKET" --arg lb "$LOGS_BUCKET" --arg rg "$REGION" --arg sub "$SUBMISSION_ID" \
    --arg jk "$K_JAR" --arg js "$C_job_jar_sha256" --arg ck "$K_ANALYSIS" --arg cs "$ANALYSIS_SHA256" \
    --arg pk "$K_PAIRS" --arg ps "$PAIRS_SHA256" --arg in "s3a://$DATA_BUCKET/$K_INPUT" \
    --arg cr "$C_curated_run" --arg dr "$C_decision_run" --arg gr "$C_grouping_run" --arg out "s3a://$DATA_BUCKET/$K_OUTPUT" \
    --arg cv "$C_code_version" --arg at "$C_attempt" --arg pa "$C_partitions" --arg ci "$C_checkpoint_interval" \
    --arg te "$C_max_tree_entries" --arg td "$C_max_tree_depth" --arg vr "$C_max_driver_validation_rows" \
    --arg vb "$C_max_driver_validation_bytes" --arg mb "$C_max_metadata_bytes" \
    '$pre + [$w, "--data-bucket", $db, "--logs-bucket", $lb, "--region", $rg, "--submission-id", $sub,
      "--job-jar-key", $jk, "--job-jar-sha256", $js, "--config-key", $ck, "--config-sha256", $cs,
      "--pairs-key", $pk, "--pairs-sha256", $ps, "--input-root", $in, "--curated-run", $cr,
      "--decision-run", $dr, "--grouping-run", $gr, "--output-root", $out, "--code-version", $cv,
      "--attempt", $at, "--partitions", $pa, "--checkpoint-interval", $ci, "--max-tree-entries", $te,
      "--max-tree-depth", $td, "--max-driver-validation-rows", $vr, "--max-driver-validation-bytes", $vb,
      "--max-metadata-bytes", $mb, "--step-timeout-seconds", $st, "--kill-after-seconds", $ka]'
}
STEP_ARGS_JSON=$(step_args_json)
STEPS_JSON=$(jq -cn --arg n "scholarchain-$ARG_ENV-spark-$SUBMISSION_ID" --argjson a "$STEP_ARGS_JSON" \
  '[{Name: $n, ActionOnFailure: "TERMINATE_CLUSTER", HadoopJarStep: {Jar: "command-runner.jar", Args: $a}}]')
EXPECTED_BOOTSTRAP_ARGS=$(jq -cn --arg b "$DATA_BUCKET" --arg r "$REGION" --arg s5 "$C_spark_dist_sha512" \
  --arg sc "$C_scala_library_sha256" --arg ha "$C_hadoop_aws_sha256" --arg h1 "$C_hadoop_aws_sha1" \
  --arg sd "$C_sdk_bundle_sha256" --arg su "$SUBMIT_SHA256" '[$b, $r, $s5, $sc, $ha, $h1, $sd, $su]')
STAGE_LIST="$K_SPARK $K_SCALA $K_HAWS $K_SDK $K_INSTALL $K_SUBMIT $K_JAR $K_ANALYSIS $K_PAIRS $K_TREE"

print_plan() {
  local n=0 k
  p() {
    n=$((n + 1))
    printf '[%s] PLAN-AWS-CALL %s %02d %s: %s\n' "$SCRIPT_NAME" "$1" "$n" "$2" "${AWS_BASE[*]} $3"
  }
  p stage read "sts get-caller-identity"
  for k in $STAGE_LIST; do
    p stage read "s3api head-object --bucket $DATA_BUCKET --key $k --checksum-mode ENABLED"
    p stage write "s3api put-object --bucket $DATA_BUCKET --key $k --body <local> --checksum-sha256 <b64> --if-none-match *   (only if absent)"
  done
  p stage read "s3api head-object --bucket $DATA_BUCKET --key $K_INPUT/<each file> --checksum-mode ENABLED   (<= $HC_MAX_INPUT_OBJECTS files)"
  p stage write "s3api put-object --bucket $DATA_BUCKET --key $K_INPUT/<each file> --checksum-sha256 <b64> --if-none-match *   (only if absent)"
  n=0
  p execute read "sts get-caller-identity   (Arn must be assumed-role/scholarchain-$ARG_ENV-operator)"
  p execute read "emr list-clusters --active   (exactly one named $CLUSTER_NAME, state WAITING)"
  p execute read "emr describe-cluster --cluster-id <id>"
  p execute read "emr list-instance-groups --cluster-id <id>"
  p execute read "emr get-managed-scaling-policy --cluster-id <id>"
  p execute read "emr get-auto-termination-policy --cluster-id <id>"
  p execute read "emr list-bootstrap-actions --cluster-id <id>   (path s3://$DATA_BUCKET/$K_INSTALL, args $EXPECTED_BOOTSTRAP_ARGS)"
  p execute read "emr list-steps --cluster-id <id> --step-states PENDING CANCEL_PENDING RUNNING"
  p execute read "s3api head-object --checksum-mode ENABLED x10: $STAGE_LIST"
  p execute read "s3api get-object --bucket $DATA_BUCKET --key $K_TREE <tmp>"
  p execute read "s3api list-objects-v2 --bucket $DATA_BUCKET --prefix $K_INPUT/   (key set and sizes equal the tree manifest)"
  p execute read "s3api list-objects-v2 --bucket $DATA_BUCKET --prefix $K_OUTPUT/ --delimiter /   (run prefixes before)"
  p execute read "s3api head-object --bucket $DATA_BUCKET --key submissions/$SUBMISSION_ID/run-record.json   (must be 404)"
  p execute write "s3api put-object --bucket $DATA_BUCKET --key submissions/$SUBMISSION_ID/run-record.json --if-none-match *"
  p execute write "emr add-steps --cluster-id <id> --steps file://<tmp>/steps.json   steps.json=$STEPS_JSON"
  p execute read "emr describe-step --cluster-id <id> --step-id <step>   (first: ActionOnFailure TERMINATE_CLUSTER and Args prefix $STEP_TIMEOUT_PREFIX_JSON)"
  p execute read "emr describe-step --cluster-id <id> --step-id <step>   (every ${C_poll_interval_seconds}s, operator deadline $((STEP_TIMEOUT_SECONDS + HC_KILL_AFTER_SECONDS + HC_STEP_GRACE_SECONDS))s)"
  p execute write "emr cancel-steps --cluster-id <id> --step-ids <step> --step-cancellation-option SEND_INTERRUPT   (deadline or interrupt only)"
  p execute read "s3api list-objects-v2 --bucket $DATA_BUCKET --prefix $K_OUTPUT/ --delimiter /   (exactly one new run=<hex>/)"
  p execute read "s3api head-object --bucket $DATA_BUCKET --key $K_OUTPUT/run=<hex>/{_SUCCESS,_parts.json,_run-manifest.json}"
  p execute read "s3api list-objects-v2 --bucket $LOGS_BUCKET --prefix spark-events/$SUBMISSION_ID/   (exactly one event log)"
  p execute write "s3api put-object --bucket $DATA_BUCKET --key submissions/$SUBMISSION_ID/run-summary.json --if-none-match *"
}
confirm_block() {
  say "CONFIRM environment=$ARG_ENV account=$ACCOUNT region=$REGION profile=$PROFILE"
  say "CONFIRM cluster_name=$CLUSTER_NAME release=$C_emr_release_label instance_type=$C_instance_type max_instances=$C_max_instances"
  say "CONFIRM git_head=$HEAD_SHA run_config=$ARG_CONFIG sha256=$CONFIG_SHA256"
  say "CONFIRM code_version=$C_code_version job_jar_sha256=$C_job_jar_sha256 attempt=$C_attempt"
  say "CONFIRM install_script_sha256=$INSTALL_SHA256 submit_script_sha256=$SUBMIT_SHA256"
  say "CONFIRM input=s3://$DATA_BUCKET/$K_INPUT tree_sha256=$C_input_tree_sha256 output=s3://$DATA_BUCKET/$K_OUTPUT"
  say "CONFIRM submission_id=$SUBMISSION_ID worst_case_cost_usd=$ESTIMATE max_cost_usd=$C_max_cost_usd"
}

if [[ $MODE == dry-run ]]; then
  step "planned AWS calls (none executed)"
  print_plan
  confirm_block
  ((LOCAL_MISSING == 0)) || note "$LOCAL_MISSING local artifact(s) missing: --stage would refuse"
  say "CONFIRMATION-TOKEN stage $(token_for stage)"
  say "CONFIRMATION-TOKEN execute $(token_for execute)"
  say "to run a mode: review every CONFIRM and BOUND line, then set $CONFIRM_ENV_VAR=<that mode's token> in your own terminal"
  say "RESULT DRY-RUN-OK"
  exit 0
fi

# ==== AWS modes =====================================================================================
[[ ${SCHOLARCHAIN_CONFIRM_SPARK_RUN:-} == "$(token_for "$MODE")" ]] ||
  die "$EX_REFUSED" "$CONFIRM_ENV_VAR does not hold the $MODE token for these inputs; run --dry-run and review it"
ok "confirmation token matches ($MODE)"
confirm_block
need_tools aws
unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN AWS_SECURITY_TOKEN AWS_PROFILE
mkdir "$LOCK_DIR" 2>/dev/null || die "$EX_REFUSED" "another bounded run holds $LOCK_DIR (remove it only after confirming no run is active)"
HAVE_LOCK=yes
STARTED_AT=$(now_utc)

READ_VERBS="sts:get-caller-identity emr:list-clusters emr:describe-cluster emr:list-instance-groups \
emr:get-managed-scaling-policy emr:get-auto-termination-policy emr:list-bootstrap-actions emr:list-steps \
emr:describe-step s3api:head-object s3api:get-object s3api:list-objects-v2"
WRITE_VERBS="s3api:put-object emr:add-steps emr:cancel-steps"
aws_read() {
  in_list "$1:$2" "$READ_VERBS" || die "$EX_USAGE" "internal: $1 $2 is not an allowed read call"
  say "AWS-CALL read: ${AWS_BASE[*]} $*" >&2
  "${AWS_BASE[@]}" "$@"
}
aws_write() {
  in_list "$1:$2" "$WRITE_VERBS" || die "$EX_USAGE" "internal: $1 $2 is not an allowed write call"
  if [[ $MODE == stage && $1:$2 != s3api:put-object ]]; then die "$EX_USAGE" "internal: --stage writes objects only"; fi
  say "AWS-CALL write: ${AWS_BASE[*]} $*" >&2
  "${AWS_BASE[@]}" "$@"
}
HEAD_JSON=""
s3_head() { # s3_head <bucket> <key>: 0 present (HEAD_JSON set), 1 absent, exit 4 on any other error
  local err
  say "AWS-CALL read: ${AWS_BASE[*]} s3api head-object --bucket $1 --key $2 --checksum-mode ENABLED" >&2
  if HEAD_JSON=$("${AWS_BASE[@]}" s3api head-object --bucket "$1" --key "$2" --checksum-mode ENABLED 2>"$TMP/err"); then
    return 0
  fi
  err=$(cat "$TMP/err")
  [[ $err == *"Not Found"* || $err == *"404"* ]] || die "$EX_AWS" "head-object s3://$1/$2 failed: $err"
  return 1
}
head_checksum() { printf '%s' "$HEAD_JSON" | jq -r '.ChecksumSHA256 // ""'; }

step "aws identity"
ID_JSON=$(aws_read sts get-caller-identity) || die "$EX_AWS" "sts get-caller-identity failed (run aws login --profile scholarchain-login first?)"
[[ $(printf '%s' "$ID_JSON" | jq -r .Account) == "$ACCOUNT" ]] || die "$EX_AWS" "profile $PROFILE is not in account $ACCOUNT"
[[ $(printf '%s' "$ID_JSON" | jq -r .Arn) == "arn:aws:sts::$ACCOUNT:assumed-role/scholarchain-$ARG_ENV-operator/"* ]] ||
  die "$EX_AWS" "profile $PROFILE is not the operator role scholarchain-$ARG_ENV-operator"
ok "operator role in account $ACCOUNT"

# ==== --stage =======================================================================================
if [[ $MODE == stage ]]; then
  stage_object() { # stage_object <local file> <key> <sha256 hex>
    local b64
    b64=$(hex_to_b64 "$3")
    if s3_head "$DATA_BUCKET" "$2"; then
      [[ $(head_checksum) == "$b64" ]] || die "$EX_REFUSED" "s3://$DATA_BUCKET/$2 exists with a different or no SHA-256 checksum"
      ok "already staged $2"
      return 0
    fi
    aws_write s3api put-object --bucket "$DATA_BUCKET" --key "$2" --body "$1" --checksum-sha256 "$b64" \
      --if-none-match "*" >/dev/null || die "$EX_AWS" "put-object $2 failed (nothing is overwritten)"
    ok "staged $2"
  }
  step "stage artifacts"
  stage_object "$(local_path "$C_spark_dist_local_path")" "$K_SPARK" "$C_spark_dist_sha256"
  stage_object "$(local_path "$C_scala_library_local_path")" "$K_SCALA" "$C_scala_library_sha256"
  stage_object "$(local_path "$C_hadoop_aws_local_path")" "$K_HAWS" "$C_hadoop_aws_sha256"
  stage_object "$(local_path "$C_sdk_bundle_local_path")" "$K_SDK" "$C_sdk_bundle_sha256"
  stage_object "$INSTALL_SCRIPT" "$K_INSTALL" "$INSTALL_SHA256"
  stage_object "$SUBMIT_SCRIPT" "$K_SUBMIT" "$SUBMIT_SHA256"
  stage_object "$(local_path "$C_job_jar_local_path")" "$K_JAR" "$C_job_jar_sha256"
  stage_object "$C_analysis_config_path" "$K_ANALYSIS" "$ANALYSIS_SHA256"
  stage_object "$C_benchmark_pairs_path" "$K_PAIRS" "$PAIRS_SHA256"
  step "stage input tree"
  while IFS=$'\t' read -r rel _ sum; do
    stage_object "$ARCHIVE_ROOT/$rel" "$K_INPUT/$rel" "$sum"
  done <"$TMP/input-tree.tsv"
  stage_object "$TMP/input-tree.tsv" "$K_TREE" "$C_input_tree_sha256"
  say "RESULT STAGED submission_id=$SUBMISSION_ID at=$(now_utc)"
  exit 0
fi

# ==== --execute =====================================================================================
step "cluster"
CLUSTERS=$(aws_read emr list-clusters --active) || die "$EX_AWS" "list-clusters failed"
MATCH=$(printf '%s' "$CLUSTERS" | jq -c --arg n "$CLUSTER_NAME" '[.Clusters[] | select(.Name == $n)]')
[[ $(printf '%s' "$MATCH" | jq length) == 1 ]] || die "$EX_AWS" "expected exactly one active cluster named $CLUSTER_NAME (apply emr-run first)"
CLUSTER_ID=$(printf '%s' "$MATCH" | jq -r '.[0].Id')
[[ $CLUSTER_ID =~ ^j-[A-Z0-9]{8,20}$ ]] || die "$EX_AWS" "unexpected cluster id $CLUSTER_ID"
CJ=$(aws_read emr describe-cluster --cluster-id "$CLUSTER_ID") || die "$EX_AWS" "describe-cluster failed"
cj() { printf '%s' "$CJ" | jq -r "$@"; }
[[ $(cj .Cluster.Status.State) == WAITING ]] || die "$EX_AWS" "cluster state is $(cj .Cluster.Status.State), need WAITING"
[[ $(cj .Cluster.ReleaseLabel) == "$C_emr_release_label" ]] || die "$EX_AWS" "release $(cj .Cluster.ReleaseLabel) differs"
[[ $(cj '[.Cluster.Applications[].Name] | sort | join(",")') == Hadoop ]] || die "$EX_AWS" "applications must be Hadoop only"
[[ $(cj .Cluster.StepConcurrencyLevel) == 1 ]] || die "$EX_AWS" "step concurrency must be 1"
[[ $(cj '.Cluster.Ec2InstanceAttributes.Ec2KeyName // ""') == "" ]] || die "$EX_AWS" "cluster has an SSH key"
[[ $(cj '.Cluster.LogUri // ""') == *"://$LOGS_BUCKET/emr/" ]] || die "$EX_AWS" "LogUri is not the logs bucket emr/ prefix"
[[ $(cj '[.Cluster.Configurations[]? | select(.Classification == "core-site") | .Properties["fs.s3a.directory.marker.retention"]] | first // ""') == keep ]] ||
  die "$EX_AWS" "core-site fs.s3a.directory.marker.retention is not keep"
[[ $(cj '[.Cluster.Configurations[]? | select(.Classification == "yarn-site") | .Properties["yarn.resourcemanager.am.max-attempts"]] | first // ""') == 1 ]] ||
  die "$EX_AWS" "yarn-site yarn.resourcemanager.am.max-attempts is not 1"
for t in course project environment owner expiration; do
  # shellcheck disable=SC2016 # $k is a jq variable, bound by --arg
  [[ -n $(cj --arg k "$t" '[.Cluster.Tags[]? | select(.Key == $k) | .Value] | first // ""') ]] || die "$EX_AWS" "cluster lacks tag $t"
done
[[ $(cj '[.Cluster.Tags[]? | select(.Key == "environment") | .Value] | first') == "$ARG_ENV" ]] || die "$EX_AWS" "environment tag differs"
IG=$(aws_read emr list-instance-groups --cluster-id "$CLUSTER_ID") || die "$EX_AWS" "list-instance-groups failed"
INSTANCES=$(printf '%s' "$IG" | jq '[.InstanceGroups[] | ([.RequestedInstanceCount, .RunningInstanceCount] | max)] | add')
((INSTANCES <= C_max_instances)) || die "$EX_AWS" "cluster has $INSTANCES instances > $C_max_instances"
[[ $(printf '%s' "$IG" | jq -r '[.InstanceGroups[].InstanceType] | unique | join(" ")') == "$C_instance_type" ]] || die "$EX_AWS" "instance types differ from $C_instance_type"
[[ $(printf '%s' "$IG" | jq '[.InstanceGroups[] | select(.AutoScalingPolicy != null)] | length') == 0 ]] || die "$EX_AWS" "an instance group has an AutoScalingPolicy"
MS=$(aws_read emr get-managed-scaling-policy --cluster-id "$CLUSTER_ID") || die "$EX_AWS" "get-managed-scaling-policy failed"
[[ -z $(printf '%s' "$MS" | jq -r '.ManagedScalingPolicy // empty') ]] || die "$EX_AWS" "cluster has managed scaling"
AT=$(aws_read emr get-auto-termination-policy --cluster-id "$CLUSTER_ID") || die "$EX_AWS" "get-auto-termination-policy failed"
IDLE=$(printf '%s' "$AT" | jq -r '.AutoTerminationPolicy.IdleTimeout // empty')
is_uint "${IDLE:-x}" || die "$EX_AWS" "cluster has no auto-termination policy"
((IDLE <= C_max_idle_timeout_seconds)) || die "$EX_AWS" "idle timeout ${IDLE}s > $C_max_idle_timeout_seconds"
BA=$(aws_read emr list-bootstrap-actions --cluster-id "$CLUSTER_ID") || die "$EX_AWS" "list-bootstrap-actions failed"
[[ $(printf '%s' "$BA" | jq '.BootstrapActions | length') == 1 ]] || die "$EX_AWS" "expected exactly one bootstrap action"
[[ $(printf '%s' "$BA" | jq -r '.BootstrapActions[0].ScriptPath') == "s3://$DATA_BUCKET/$K_INSTALL" ]] || die "$EX_AWS" "bootstrap script path differs from the committed install script"
[[ $(printf '%s' "$BA" | jq -c '.BootstrapActions[0].Args') == "$EXPECTED_BOOTSTRAP_ARGS" ]] || die "$EX_AWS" "bootstrap arguments differ from the run config pins"
ACTIVE=$(aws_read emr list-steps --cluster-id "$CLUSTER_ID" --step-states PENDING CANCEL_PENDING RUNNING | jq '.Steps | length') || die "$EX_AWS" "list-steps failed"
[[ $ACTIVE == 0 ]] || die "$EX_AWS" "cluster has $ACTIVE active steps"
ok "cluster $CLUSTER_ID WAITING, $INSTANCES x $C_instance_type, idle ${IDLE}s, pinned bootstrap, no active step"

step "staged objects"
EXPECT="$K_SPARK=$C_spark_dist_sha256 $K_SCALA=$C_scala_library_sha256 $K_HAWS=$C_hadoop_aws_sha256 $K_SDK=$C_sdk_bundle_sha256"
EXPECT="$EXPECT $K_INSTALL=$INSTALL_SHA256 $K_SUBMIT=$SUBMIT_SHA256 $K_JAR=$C_job_jar_sha256 $K_ANALYSIS=$ANALYSIS_SHA256"
EXPECT="$EXPECT $K_PAIRS=$PAIRS_SHA256 $K_TREE=$C_input_tree_sha256"
for kv in $EXPECT; do
  s3_head "$DATA_BUCKET" "${kv%%=*}" || die "$EX_AWS" "${kv%%=*} is not staged (run --stage)"
  [[ $(head_checksum) == "$(hex_to_b64 "${kv##*=}")" ]] || die "$EX_AWS" "${kv%%=*} checksum differs from the config"
done
aws_read s3api get-object --bucket "$DATA_BUCKET" --key "$K_TREE" "$TMP/tree.tsv" >/dev/null || die "$EX_AWS" "get-object $K_TREE failed"
[[ $(sha256_file "$TMP/tree.tsv") == "$C_input_tree_sha256" ]] || die "$EX_AWS" "downloaded tree manifest differs"
aws_read s3api list-objects-v2 --bucket "$DATA_BUCKET" --prefix "$K_INPUT/" --max-items "$HC_MAX_INPUT_OBJECTS" \
  >"$TMP/listing.json" || die "$EX_AWS" "list of $K_INPUT/ failed"
[[ $(jq -r '.NextToken // ""' "$TMP/listing.json") == "" ]] || die "$EX_AWS" "more than $HC_MAX_INPUT_OBJECTS input objects"
jq -r --arg p "$K_INPUT/" '.Contents[]? | [(.Key | ltrimstr($p)), (.Size | tostring)] | @tsv' "$TMP/listing.json" | LC_ALL=C sort >"$TMP/got.tsv"
cut -f1,2 "$TMP/tree.tsv" | LC_ALL=C sort >"$TMP/want.tsv"
cmp -s "$TMP/got.tsv" "$TMP/want.tsv" || die "$EX_AWS" "objects under $K_INPUT/ differ from the tree manifest (keys or sizes)"
ok "10 artifacts and $(grep -c . "$TMP/want.tsv") input objects match"

list_runs() {
  aws_read s3api list-objects-v2 --bucket "$DATA_BUCKET" --prefix "$K_OUTPUT/" --delimiter / \
    --max-items "$HC_MAX_OUTPUT_PREFIXES" >"$TMP/out.json" || return 1
  [[ $(jq -r '.NextToken // ""' "$TMP/out.json") == "" ]] || return 1
  jq -r '.CommonPrefixes[]?.Prefix' "$TMP/out.json" | LC_ALL=C sort
}
RUNS_BEFORE=$(list_runs) || die "$EX_AWS" "could not list $K_OUTPUT/"
RECORD_KEY="submissions/$SUBMISSION_ID/run-record.json"
SUMMARY_KEY="submissions/$SUBMISSION_ID/run-summary.json"
if s3_head "$DATA_BUCKET" "$RECORD_KEY"; then die "$EX_REFUSED" "$RECORD_KEY exists: already submitted (retry needs attempt + 1)"; fi

STEP_ID=""
STEP_STATE=""
STEP_FAILURE=null
NEW_RUN=""
EVENT_KEY=""
FINISHED=no
build_summary() { # build_summary <status> <out file>
  jq -n --arg schema "$SUMMARY_SCHEMA" --arg status "$1" --arg env "$ARG_ENV" --arg account "$ACCOUNT" \
    --arg region "$REGION" --arg head "$HEAD_SHA" --arg started "$STARTED_AT" --arg ended "$(now_utc)" \
    --arg sub "$SUBMISSION_ID" --arg cfg "$ARG_CONFIG" --arg cfgSha "$CONFIG_SHA256" --arg cluster "$CLUSTER_ID" \
    --arg step "$STEP_ID" --arg state "$STEP_STATE" --argjson failure "$STEP_FAILURE" --arg run "$NEW_RUN" \
    --arg event "$EVENT_KEY" --arg estimate "$ESTIMATE" --arg maxCost "$C_max_cost_usd" --argjson steps "$STEPS_JSON" \
    '{schemaVersion: $schema, status: $status, environment: $env, awsAccountId: $account, awsRegion: $region,
      gitCommit: $head, startedAt: $started, endedAt: $ended, submissionId: $sub,
      config: {path: $cfg, sha256: $cfgSha}, emr: {clusterId: $cluster, stepId: $step, stepState: $state,
      stepFailure: $failure, steps: $steps}, outputs: {runPrefix: $run, eventLogKey: $event},
      cost: {worstCaseUsd: $estimate, maxCostUsd: $maxCost}}' >"$2"
}
write_summary() {
  build_summary "$1" "$TMP/summary.json" || return 1
  say "SUMMARY-BEGIN"
  cat "$TMP/summary.json"
  say "SUMMARY-END"
  aws_write s3api put-object --bucket "$DATA_BUCKET" --key "$SUMMARY_KEY" --body "$TMP/summary.json" \
    --if-none-match "*" >/dev/null || printf '[%s] FAIL could not write %s; the summary above is the evidence\n' "$SCRIPT_NAME" "$SUMMARY_KEY" >&2
}
finish() { # finish <status> <exit code> <message>
  FINISHED=yes
  write_summary "$1" || true
  if (($2 == 0)); then
    say "RESULT $1"
    exit 0
  fi
  die "$2" "$3"
}

cancel_own_step() {
  if [[ -z $STEP_ID ]]; then
    note "no step id known ($1): nothing to cancel; a step submitted in flight is still bounded on the cluster side"
    return 0
  fi
  say "STEP cancel own step $STEP_ID ($1)"
  aws_write emr cancel-steps --cluster-id "$CLUSTER_ID" --step-ids "$STEP_ID" --step-cancellation-option SEND_INTERRUPT >/dev/null ||
    printf '[%s] FAIL cancel-steps %s failed; the cluster-side bound still holds: the step ends by /usr/bin/timeout within %ss of its start (ActionOnFailure TERMINATE_CLUSTER), then idle auto-termination ends a cluster left idle\n' \
      "$SCRIPT_NAME" "$STEP_ID" "$((STEP_TIMEOUT_SECONDS + HC_KILL_AFTER_SECONDS))" >&2
}
# shellcheck disable=SC2329 # invoked by the INT/TERM/HUP trap below
on_interrupt() {
  trap - INT TERM HUP
  if [[ $FINISHED == no ]]; then
    cancel_own_step interrupted
    STEP_STATE=INTERRUPTED-BY-OPERATOR
    finish INTERRUPTED "$EX_RUN" "interrupted; cancellation of $STEP_ID requested"
  fi
  exit "$EX_RUN"
}
trap on_interrupt INT TERM HUP

step "run record and submission"
build_summary SUBMITTING "$TMP/record.json" || die "$EX_USAGE" "could not build the run record"
aws_write s3api put-object --bucket "$DATA_BUCKET" --key "$RECORD_KEY" --body "$TMP/record.json" --if-none-match "*" >/dev/null ||
  die "$EX_REFUSED" "could not write $RECORD_KEY (exists or no permission); nothing was submitted"
printf '%s\n' "$STEPS_JSON" >"$TMP/steps.json"
STEP_ID=$(aws_write emr add-steps --cluster-id "$CLUSTER_ID" --steps "file://$TMP/steps.json" | jq -r '.StepIds[0] // ""') || true
[[ $STEP_ID =~ ^s-[A-Z0-9]+$ ]] || {
  STEP_ID=""
  finish SUBMIT-FAILED "$EX_AWS" "add-steps returned no step id; run record stays at $RECORD_KEY"
}
ok "step $STEP_ID submitted to $CLUSTER_ID"

# The step as EMR recorded it must carry the cluster-side bound; otherwise cancel it at once.
# EMR may echo the legacy name TERMINATE_JOB_FLOW for TERMINATE_CLUSTER (equivalent values,
# repair 02 item 2); any other value, or none, cancels.
SJ0=$(aws_read emr describe-step --cluster-id "$CLUSTER_ID" --step-id "$STEP_ID") || SJ0='{}'
RECORDED_AOF=$(printf '%s' "$SJ0" | jq -r '.Step.ActionOnFailure // ""') || RECORDED_AOF=""
case $RECORDED_AOF in
  TERMINATE_CLUSTER | TERMINATE_JOB_FLOW) AOF_OK=yes ;;
  *) AOF_OK=no ;;
esac
if [[ $AOF_OK != yes ||
  $(printf '%s' "$SJ0" | jq -c '.Step.Config.Args[0:4] // []') != "$STEP_TIMEOUT_PREFIX_JSON" ]]; then
  cancel_own_step "recorded step has ActionOnFailure '${RECORDED_AOF:-none}' (need TERMINATE_CLUSTER or TERMINATE_JOB_FLOW) or lacks the timeout prefix"
  STEP_STATE=REFUSED-UNBOUNDED
  finish STEP-UNBOUNDED "$EX_VERIFY" "step $STEP_ID as recorded by EMR is not bounded as submitted; cancellation requested"
fi
ok "step $STEP_ID recorded with ActionOnFailure $RECORDED_AOF and $STEP_TIMEOUT_PREFIX_JSON"

OPERATOR_DEADLINE_SECONDS=$((STEP_TIMEOUT_SECONDS + HC_KILL_AFTER_SECONDS + HC_STEP_GRACE_SECONDS))
step "wait for $STEP_ID (cluster-side bound $((STEP_TIMEOUT_SECONDS + HC_KILL_AFTER_SECONDS))s, operator deadline ${OPERATOR_DEADLINE_SECONDS}s)"
DEADLINE=$(($(date +%s) + OPERATOR_DEADLINE_SECONDS))
while :; do
  SJ=$(aws_read emr describe-step --cluster-id "$CLUSTER_ID" --step-id "$STEP_ID") || SJ='{}'
  STEP_STATE=$(printf '%s' "$SJ" | jq -r '.Step.Status.State // "UNKNOWN"') || STEP_STATE=UNKNOWN
  say "STEP poll step=$STEP_ID state=$STEP_STATE at=$(now_utc)"
  case $STEP_STATE in
    COMPLETED) break ;;
    FAILED | CANCELLED | INTERRUPTED)
      STEP_FAILURE=$(printf '%s' "$SJ" | jq -c '.Step.Status.FailureDetails // null') || STEP_FAILURE=null
      finish "STEP-$STEP_STATE" "$EX_RUN" "step $STEP_ID ended $STEP_STATE; logs under s3://$LOGS_BUCKET/emr/$CLUSTER_ID/steps/$STEP_ID/"
      ;;
  esac
  if (($(date +%s) >= DEADLINE)); then
    cancel_own_step deadline
    CANCEL_DEADLINE=$(($(date +%s) + HC_CANCEL_WAIT_MINUTES * 60))
    while (($(date +%s) < CANCEL_DEADLINE)); do
      sleep "$HC_MIN_POLL_SECONDS"
      STEP_STATE=$(aws_read emr describe-step --cluster-id "$CLUSTER_ID" --step-id "$STEP_ID" | jq -r '.Step.Status.State // "UNKNOWN"') || STEP_STATE=UNKNOWN
      in_list "$STEP_STATE" "CANCELLED INTERRUPTED FAILED COMPLETED" && break
    done
    finish TIMED-OUT "$EX_RUN" "step $STEP_ID exceeded the operator deadline ${OPERATOR_DEADLINE_SECONDS}s; final observed state $STEP_STATE"
  fi
  sleep "$C_poll_interval_seconds"
done

step "verify outputs"
RUNS_AFTER=$(list_runs) || finish VERIFY-FAILED "$EX_VERIFY" "could not list $K_OUTPUT/ after the step"
NEW=$(LC_ALL=C comm -13 <(printf '%s\n' "$RUNS_BEFORE") <(printf '%s\n' "$RUNS_AFTER") | grep . || true)
NEW_RUNS=$(printf '%s\n' "$NEW" | grep -E "^$K_OUTPUT/run=[0-9a-f]+/$" || true)
[[ $(printf '%s' "$NEW_RUNS" | grep -c . || true) == 1 ]] || finish VERIFY-FAILED "$EX_VERIFY" "expected exactly one new run= prefix, got: ${NEW:-none}"
NEW_RUN=$NEW_RUNS
[[ -z $(printf '%s\n' "$RUNS_AFTER" | grep -F "$K_OUTPUT/.spark-" || true) ]] || finish VERIFY-FAILED "$EX_VERIFY" "a .spark- scratch prefix remains under $K_OUTPUT/"
for f in _SUCCESS _parts.json _run-manifest.json; do
  (s3_head "$DATA_BUCKET" "$NEW_RUN$f") || finish VERIFY-FAILED "$EX_VERIFY" "$NEW_RUN$f is missing"
done
say "NOTE new output prefixes: $(printf '%s' "$NEW" | tr '\n' ' ')"
aws_read s3api list-objects-v2 --bucket "$LOGS_BUCKET" --prefix "spark-events/$SUBMISSION_ID/" --max-items 10 >"$TMP/ev.json" ||
  finish VERIFY-FAILED "$EX_VERIFY" "could not list the event log prefix"
[[ $(jq '[.Contents[]?] | length' "$TMP/ev.json") == 1 ]] || finish VERIFY-FAILED "$EX_VERIFY" "expected exactly one event log under spark-events/$SUBMISSION_ID/"
EVENT_KEY=$(jq -r '.Contents[0].Key' "$TMP/ev.json")
ok "published $NEW_RUN with _SUCCESS, _parts.json, _run-manifest.json; event log $EVENT_KEY"
finish COMPLETED 0 ""
