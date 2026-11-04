#!/usr/bin/env bash
# =====================================================================================================
# scripts/run-bounded-pipeline.sh -- bounded EMR smoke run of the ScholarChain Hadoop pipeline
#
# Owner: infra-engineer (scripts/**). Authored in Phase 4, work order 5 (README Phase 4, "A bounded
# EMR smoke-run script owned by the infrastructure workflow"; README Phases 12-13, script 6).
#
# -----------------------------------------------------------------------------------------------------
# NOT APPROVED FOR EXECUTION AGAINST AWS.
# Nobody may run this script against AWS until the human architect has ratified ALL of:
#   1. the environment list, and the Terraform environment infra/terraform/environments/<env>/ that
#      provides the resources listed under "Infrastructure this script assumes" (Phase 12);
#   2. the operator of this script (gap G6 in docs/agents.md; proposed: deploy-engineer);
#   3. the hard caps and the cost bound in the HARD CAPS block below;
#   4. the Bash allowlist row for this script (currently inactive:B2+G6 for deploy-engineer).
# Until (1) exists, the script refuses every environment in every mode except --preflight-scan.
# -----------------------------------------------------------------------------------------------------
#
# USAGE
#   scripts/run-bounded-pipeline.sh <environment> <dataset-manifest> <run-config>
#   scripts/run-bounded-pipeline.sh --dry-run <environment> <dataset-manifest> <run-config>
#   scripts/run-bounded-pipeline.sh --preflight-scan <environment> <dataset-manifest> <run-config>
#
#   <environment>       ratified environment name; must equal `environment` in the run config and
#                       have a committed directory infra/terraform/environments/<environment>/
#   <dataset-manifest>  committed dataset manifest under datasets/ (datasets/dataset-manifest.yaml)
#   <run-config>        committed run configuration file (format below); every bound is in it
#
#   The three-argument form (the shape of the allowlist row) is a DRY RUN unless the environment
#   variable SCHOLARCHAIN_CONFIRM_BOUNDED_RUN holds the confirmation token printed by a dry run of
#   exactly the same inputs. A token that does not match refuses (exit 3); it never falls back to a
#   dry run. --dry-run forces a dry run even when the token is set. --preflight-scan runs only the
#   local DBLP input scan (no git cleanliness gate, no AWS).
#
#   Run from the repository root. Run cleanly: no interactive prompt is ever shown.
#
# MODES
#   dry run          validates every input and bound, scans the local DBLP input, prints every bound,
#                    the cost estimate, the exact planned AWS calls, and the confirmation token.
#                    Makes NO AWS call and writes nothing.
#   execute          (token set and matching) re-validates, runs read-only AWS pre-flight checks,
#                    writes exactly two write-once objects (staged run manifest, run summary),
#                    submits one EMR step to an existing Terraform-managed cluster, waits with a
#                    bounded timeout, verifies the outputs, prints the run summary.
#   --preflight-scan local scan of the DBLP input subset only (markup constructs, record counts,
#                    record sizes, team sizes, pair expansion); exit 0 when every gate passes.
#
# WHAT THIS SCRIPT NEVER DOES
#   - creates, terminates, resizes, or changes the scaling of any EMR cluster (the cluster belongs to
#     Terraform; the only EMR mutations are add-steps of one step and, on timeout or interrupt,
#     cancel-steps of that same step ID);
#   - deletes or overwrites any S3 object (both of its writes use put-object --if-none-match "*");
#   - writes to the raw zone or to the curated zone (the driver writes the curated run prefix
#     <curated-root>/hadoop/snapshot=<dblp snapshot>/run=<run ID>/, and the driver never overwrites
#     or deletes; an incomplete stage requires a new manifest with attempt + 1);
#   - chooses the DBLP venue subset (scholarchain.dblp.venues is taken verbatim from the committed
#     run manifest and recorded; the functional-run venue list is an open human decision);
#   - assumes the DBLP input is free of comments, CDATA sections, or processing instructions (it
#     counts them locally on every run and applies the explicit dblp_markup_policy);
#   - prints, reads, or stores any secret (it uses no secret at all);
#   - uses eval or sources its configuration.
#
# RUN CONFIGURATION FORMAT (scholarchain.bounded-pipeline-config.v1)
#   One `key=value` per line; `#` comment lines and blank lines are ignored. Values contain only
#   A-Z a-z 0-9 . _ : / = , @ + - (no spaces, quotes, or shell syntax). Every key is required, no key
#   has a default, unknown or repeated keys are refused, and any value containing REPLACE-ME is
#   refused. The file must be committed and unmodified (clean tree).
#
#   config_version=scholarchain.bounded-pipeline-config.v1
#   environment=<env>                         must equal the <environment> argument
#   run_profile=smoke                         only `smoke` is supported (functional runs need new,
#                                             ratified caps and the human venue decision)
#   pipeline=hadoop                           only the Hadoop stage group is supported (Spark: Phase 6)
#   aws_profile=<named profile>               operator profile (NOT the read-only profile; it needs
#                                             add-steps, cancel-steps, put-object, see IAM below)
#   aws_region=<region>                       e.g. a value such as us-east-1
#   aws_account_id=<12 digits>                must equal sts get-caller-identity Account
#   emr_cluster_id=<j-...>                    existing Terraform-managed cluster of <env>
#   emr_release_label=<emr-X.Y.Z>             must equal the cluster's ReleaseLabel
#   emr_allowed_instance_types=<t1,t2,...>    every cluster instance type must be in this list,
#                                             which must be a subset of HC_ALLOWED_INSTANCE_TYPES
#   emr_max_instances=<int>                   bound on primary + core + task instances, including the
#                                             managed-scaling maximum (<= HC_MAX_INSTANCES)
#   emr_max_idle_timeout_seconds=<int>        the cluster's auto-termination idle timeout must exist
#                                             and be <= this (<= HC_MAX_IDLE_TIMEOUT_SECONDS)
#   step_timeout_minutes=<int>                wall-clock bound; the step is cancelled at the deadline
#                                             (<= HC_MAX_STEP_TIMEOUT_MINUTES)
#   poll_interval_seconds=<int>               HC_MIN_POLL_SECONDS..HC_MAX_POLL_SECONDS
#   instance_hour_price_usd=<decimal>         human-supplied upper bound of EC2 + EMR price per
#                                             instance-hour for the allowed types (from the AWS price
#                                             list; the script calls no pricing API)
#   max_cost_usd=<decimal>                    must be >= the computed estimate (<= HC_MAX_COST_USD)
#   raw_bucket=<bucket>                       substituted into the manifest's {raw_bucket}
#   curated_root_uri=s3://<bucket>[/<prefix>] passed to the driver as --curated-root
#   pipeline_runs_uri=s3://<bucket>[/<prefix>] write-once submission records go under
#                                             <pipeline_runs_uri>/submission=<run manifest sha256>/
#   jar_uri=s3://<bucket>/<...>/<jar_git_sha>/<name>.jar   immutable job jar (path contains the SHA)
#   jar_git_sha=<40 hex>                      commit the jar was built from; ancestor of HEAD with no
#                                             change since in the jar's source paths; must equal the
#                                             run manifest's codeVersion
#   jar_sha256=<64 hex>                       verified against a download of jar_uri
#   jar_max_bytes=<int>                       <= HC_MAX_JAR_BYTES
#   run_manifest_path=<repo path>.json        committed scholarchain.run-manifest.v1, stage
#                                             hadoop.pipeline
#   run_manifest_sha256=<64 hex>              sha256 of the committed manifest bytes
#   dblp_snapshot_id=<id>                     must equal datasets.dblp.snapshot.id of the manifest
#   dblp_input_uri=s3://...                   the bounded DBLP subset object; must lie under the
#                                             dataset manifest's dblp raw_prefix
#   dblp_input_local_path=<path>              byte-identical local copy (e.g. under datasets/downloads/)
#                                             used for the local scan; .xml or .xml.gz
#   dblp_input_sha256=<64 hex>                sha256 of the local copy AND of the S3 object
#   max_input_bytes=<int>                     bound on file bytes and on uncompressed bytes
#                                             (<= HC_MAX_INPUT_BYTES)
#   max_input_records=<int>                   bound on DBLP publication records (<= HC_MAX_INPUT_RECORDS:
#                                             the smoke subset = 4 sample records + 10,000 records)
#   stages=normalize,exact-ids,teams,blocking,candidates   subset in this order; normalize required;
#                                             candidates requires blocking
#   dblp_markup_policy=refuse-if-present|accept-reported   what to do when the local scan finds
#                                             comments, CDATA, PIs (besides the XML declaration), or
#                                             entity declarations in the DBLP input
#   team_max_authors=<int>                    bound on the largest observed team (<= HC_MAX_TEAM_AUTHORS)
#   team_max_pairs=<int>                      bound on total pair expansion sum n(n-1)/2
#                                             (<= HC_MAX_TEAM_PAIRS)
#   teams_oversize_override=refuse|allow      when `teams` is a stage and the scan exceeds
#                                             team_max_authors or team_max_pairs: refuse, or allow and
#                                             record it (hard caps still apply; giant-team cost note)
#
# CONFIRMATION
#   A dry run prints `CONFIRMATION-TOKEN brp-<32 hex>`: the SHA-256 (truncated) of the environment,
#   account, Region, profile, cluster ID, HEAD commit, the sha256 of the config, the run manifest,
#   the dataset manifest, the jar, and the input, and every enforced bound. The human reviews the
#   dry-run output, then sets SCHOLARCHAIN_CONFIRM_BOUNDED_RUN=<token> in their own terminal. Any
#   change to any of those values (including a new commit) invalidates the token.
#
# OUTPUT MARKERS (greppable; all on stdout except FAIL, which goes to stderr)
#   [run-bounded-pipeline] STEP <name>
#   [run-bounded-pipeline] CHECK ok <name>: <detail>
#   [run-bounded-pipeline] BOUND <name>=<value> cap=<cap> source=<where defined>
#   [run-bounded-pipeline] SCAN <name>=<value>
#   [run-bounded-pipeline] PLAN-AWS-CALL <nn> <read|write>: aws <argv>
#   [run-bounded-pipeline] AWS-CALL <read|write>: aws <argv>        (stderr, so captured JSON stays clean)
#   [run-bounded-pipeline] CONFIRM <name>=<value>
#   [run-bounded-pipeline] CONFIRMATION-TOKEN brp-<hex>
#   [run-bounded-pipeline] SUMMARY-BEGIN / SUMMARY-END   (run summary JSON in between)
#   [run-bounded-pipeline] RESULT <DRY-RUN-OK|PREFLIGHT-OK|COMPLETED>
#   [run-bounded-pipeline] FAIL exit=<code> <reason>
#
# EXIT CODES
#   0 success   2 usage or invalid input   3 refused by a safety gate or bound
#   4 AWS pre-flight condition not met     5 EMR step failed, was cancelled, or timed out
#   6 post-run output verification failed
#
# RUN SUMMARY (scholarchain.bounded-pipeline-summary.v1, execute mode only)
#   JSON printed between SUMMARY-BEGIN and SUMMARY-END and written once to
#   <pipeline_runs_uri>/submission=<run manifest sha256>/run-summary.json. Fields: schemaVersion,
#   status, environment, awsAccountId, awsRegion, gitCommit, startedAt, endedAt, confirmationToken,
#   config{path,sha256}, datasetManifest{path,sha256}, runManifest{path,sha256,stagedUri,attempt,
#   codeVersion,configDigest,dblpVenues,parameters}, jar{uri,gitSha,sha256,bytes},
#   input{uri,localPath,sha256,fileBytes,scan{...}}, bounds{...}, hardCaps{...},
#   emr{clusterId,releaseLabel,logUri,observedInstances,observedTypes,managedScalingMax,
#   idleTimeoutSeconds,stepId,stepState,stepFailure}, outputs{curatedRunUri,runId,stages[{stage,
#   successUri,countersUri,countersSha256,counters}]}.
#
# PLANNED AWS CALLS (execute mode; a dry run prints them with the resolved values)
#   read : sts get-caller-identity
#   read : emr describe-cluster --cluster-id <id>
#   read : emr list-instance-groups | emr list-instance-fleets --cluster-id <id>
#   read : emr get-managed-scaling-policy --cluster-id <id>
#   read : emr get-auto-termination-policy --cluster-id <id>
#   read : emr list-steps --cluster-id <id> --step-states PENDING CANCEL_PENDING RUNNING
#   read : s3api head-object + get-object of jar_uri (size cap, sha256)
#   read : s3api head-object + get-object of dblp_input_uri (size cap, sha256)
#   read : s3api head-object of the two submission keys (must not exist)
#   read : s3api list-objects-v2 of <curated-root>/hadoop/snapshot=<id>/ (run prefixes before)
#   write: s3api put-object --if-none-match "*" of the staged run manifest
#   write: emr add-steps (one CUSTOM_JAR step, ActionOnFailure=CONTINUE)
#   read : emr describe-step (poll until a final state or the deadline)
#   write: emr cancel-steps --step-ids <own step> (only at the deadline or on interrupt)
#   read : s3api list-objects-v2 (run prefixes after), head-object / get-object of the new run's
#          _run-manifest.json, <stage>/_SUCCESS, <stage>/_counters.json
#   write: s3api put-object --if-none-match "*" of the run summary
#
# INFRASTRUCTURE THIS SCRIPT ASSUMES (to be provided by Terraform in Phase 12, not by this script)
#   - infra/terraform/environments/<env>/ committed; required tags course, project, environment,
#     owner, expiration on the cluster (expiration as YYYY-MM-DD, not in the past)
#   - one EMR cluster with Hadoop, an auto-termination policy, managed scaling in Instances or
#     InstanceFleetUnits (weight 1) or no scaling, no instance-group AutoScalingPolicy, and a LogUri
#   - S3 raw, curated, artifact, and pipeline-runs locations, encrypted, public access blocked; the
#     artifact location immutable (versioning plus deny-overwrite policy or Object Lock)
#   - an operator IAM role for aws_profile limited to: sts:GetCallerIdentity; on that cluster
#     elasticmapreduce:DescribeCluster, ListInstanceGroups, ListInstanceFleets,
#     GetManagedScalingPolicy, GetAutoTerminationPolicy, ListSteps, DescribeStep, AddJobFlowSteps,
#     CancelSteps; s3:GetObject on the jar and raw input keys; s3:ListBucket on the curated snapshot
#     prefix; s3:GetObject on the curated run prefixes; s3:PutObject on <pipeline_runs_uri>/* only.
#     No TerminateJobFlows, ModifyInstanceGroups, PutManagedScalingPolicy, or s3:DeleteObject.
#   - the EMR cluster's EC2 instance role may read the jar and raw input and write the curated root.
#
# PREREQUISITES: bash 3.2+, git, jq, awk, gzip, shasum or sha256sum, aws CLI v2 with put-object
# --if-none-match support. Execute mode also needs network access to AWS.
# =====================================================================================================

set -euo pipefail

readonly SCRIPT_NAME="run-bounded-pipeline"
readonly CONFIG_SCHEMA="scholarchain.bounded-pipeline-config.v1"
readonly SUMMARY_SCHEMA="scholarchain.bounded-pipeline-summary.v1"
readonly CONFIRM_ENV_VAR="SCHOLARCHAIN_CONFIRM_BOUNDED_RUN"
readonly DRIVER_MAIN_CLASS="scholarchain.hadoop.pipeline.PipelineDriver"

# ----------------------------------------------------------------------------------------------------
# HARD CAPS. Proposed by infra-engineer; they require human ratification before any execution.
# A run config may only tighten them. Changing them is a reviewed change to this committed file.
# ----------------------------------------------------------------------------------------------------
readonly HC_MAX_INSTANCES=5                       # primary + core + task, including scaling maximum
readonly HC_ALLOWED_INSTANCE_TYPES="m5.xlarge m6i.xlarge m7i.xlarge"
readonly HC_MAX_IDLE_TIMEOUT_SECONDS=3600         # cluster auto-termination idle timeout
readonly HC_MAX_STEP_TIMEOUT_MINUTES=90           # wall-clock deadline of the single step
readonly HC_MIN_POLL_SECONDS=30
readonly HC_MAX_POLL_SECONDS=300
readonly HC_MAX_COST_USD="10"                     # bound on the step-window compute estimate
readonly HC_MAX_INPUT_BYTES=67108864              # 64 MiB (file and uncompressed)
readonly HC_MAX_INPUT_RECORDS=10004               # smoke subset: 4 sample + 10,000 records
readonly HC_MAX_JAR_BYTES=536870912               # 512 MiB
readonly HC_MAX_RECORD_BYTES=1048576              # scholarchain.dblp/orcid.maxRecordBytes upper cap
readonly HC_MIN_RECORD_BYTES=1024                 # driver minimum
readonly HC_MAX_REDUCERS=8                        # per scholarchain.reducers.* value
readonly HC_MAX_BLOCK_SIZE=200                    # scholarchain.blocking.maxBlockSize
readonly HC_MAX_EXCERPT_CHARS=4096                # scholarchain.reject.maxExcerptChars
readonly HC_MAX_TEAM_AUTHORS=3000                 # largest team in the input
readonly HC_MAX_TEAM_PAIRS=5000000                # sum of n(n-1)/2 over publication records
readonly HC_MAX_CANDIDATE_PAIRS=50000000          # (mentions + works) * (maxBlockSize - 1) / 2
readonly HC_MAX_COUNTERS_BYTES=1048576            # per downloaded _counters.json
readonly HC_MAX_RUN_PREFIXES=1000                 # existing run= prefixes listed per snapshot

readonly EX_USAGE=2
readonly EX_REFUSED=3
readonly EX_AWS=4
readonly EX_RUN=5
readonly EX_VERIFY=6

readonly CONFIG_KEYS="config_version environment run_profile pipeline aws_profile aws_region \
aws_account_id emr_cluster_id emr_release_label emr_allowed_instance_types emr_max_instances \
emr_max_idle_timeout_seconds step_timeout_minutes poll_interval_seconds instance_hour_price_usd \
max_cost_usd raw_bucket curated_root_uri pipeline_runs_uri jar_uri jar_git_sha jar_sha256 \
jar_max_bytes run_manifest_path run_manifest_sha256 dblp_snapshot_id dblp_input_uri \
dblp_input_local_path dblp_input_sha256 max_input_bytes max_input_records stages \
dblp_markup_policy team_max_authors team_max_pairs teams_oversize_override"

# Manifest parameters (modules/hadoop/src/main/scala/scholarchain/hadoop/JobParams.scala, ManifestKeys).
readonly MANIFEST_PARAM_KEYS="scholarchain.blocking.keySource scholarchain.blocking.keySourceVersion \
scholarchain.blocking.maxBlockSize scholarchain.blocking.oversizePolicy \
scholarchain.candidates.featureVersion scholarchain.codec.intermediate scholarchain.codec.output \
scholarchain.dblp.encoding scholarchain.dblp.maxRecordBytes scholarchain.dblp.recordTypes \
scholarchain.dblp.venues scholarchain.dblp.yearRange scholarchain.orcid.maxRecordBytes \
scholarchain.reducers.blocking scholarchain.reducers.candidates scholarchain.reducers.exactIds \
scholarchain.reducers.normalize scholarchain.reducers.teams scholarchain.reject.maxExcerptChars \
scholarchain.team.maxSize scholarchain.team.sources"

readonly ALL_STAGES="normalize exact-ids teams blocking candidates"
readonly DBLP_PUBLICATION_TYPES="article inproceedings proceedings book incollection phdthesis mastersthesis"
# Paths whose content goes into the job jar; no change since jar_git_sha is allowed in them.
readonly JAR_SOURCE_PATHS="modules/core modules/entity-resolution modules/hadoop build.sbt project"

# ----------------------------------------------------------------------------------------------------
# output helpers
# ----------------------------------------------------------------------------------------------------
say() { printf '[%s] %s\n' "$SCRIPT_NAME" "$*"; }
step() { say "STEP $*"; }
ok() { say "CHECK ok $*"; }
die() {
  local code=$1
  shift
  printf '[%s] FAIL exit=%s %s\n' "$SCRIPT_NAME" "$code" "$*" >&2
  exit "$code"
}
usage() {
  cat >&2 <<'EOF'
usage: scripts/run-bounded-pipeline.sh [--dry-run|--preflight-scan] <environment> <dataset-manifest> <run-config>
  without --dry-run, the run executes only when SCHOLARCHAIN_CONFIRM_BOUNDED_RUN holds the token
  printed by a dry run of the same inputs; otherwise it is a dry run. See the script header.
EOF
  exit "$EX_USAGE"
}
now_utc() { date -u +%Y-%m-%dT%H:%M:%SZ; }

# ----------------------------------------------------------------------------------------------------
# validation helpers (bash 3.2 compatible; no associative arrays)
# ----------------------------------------------------------------------------------------------------
in_list() { # in_list <word> <space-separated list>
  local w=$1 x
  for x in $2; do
    if [[ $x == "$w" ]]; then return 0; fi
  done
  return 1
}
is_uint() { [[ $1 =~ ^(0|[1-9][0-9]{0,14})$ ]]; }
need_int_range() { # need_int_range <name> <value> <min> <max>
  local name=$1 v=$2 min=$3 max=$4
  is_uint "$v" || die "$EX_USAGE" "$name='$v' is not a non-negative integer"
  if ((v < min || v > max)); then
    die "$EX_REFUSED" "$name=$v is outside [$min, $max]"
  fi
}
is_decimal() { [[ $1 =~ ^(0|[1-9][0-9]{0,6})(\.[0-9]{1,4})?$ ]]; }
dec_le() { awk -v a="$1" -v b="$2" 'BEGIN { exit !(a + 0 <= b + 0) }'; }
is_hex() { [[ $1 =~ ^[0-9a-f]+$ ]] && ((${#1} == $2)); }
need_hex() { is_hex "$2" "$3" || die "$EX_USAGE" "$1 must be $3 lowercase hex characters"; }
is_s3_uri() { [[ $1 =~ ^s3://[a-z0-9][a-z0-9.-]{1,61}[a-z0-9](/[A-Za-z0-9._=,+@-]+)*$ ]]; }
s3_bucket() {
  local u=${1#s3://}
  printf '%s' "${u%%/*}"
}
s3_key() {
  local u=${1#s3://}
  if [[ $u == */* ]]; then printf '%s' "${u#*/}"; fi
}
is_repo_path() { [[ $1 =~ ^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*$ ]] && [[ /$1/ != */../* ]] && [[ /$1/ != */./* ]]; }

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{ print $1 }'
  else
    shasum -a 256 "$1" | awk '{ print $1 }'
  fi
}
sha256_text() {
  if command -v sha256sum >/dev/null 2>&1; then
    printf '%s' "$1" | sha256sum | awk '{ print $1 }'
  else
    printf '%s' "$1" | shasum -a 256 | awk '{ print $1 }'
  fi
}
file_bytes() { wc -c <"$1" | awk '{ print $1 }'; }

# ----------------------------------------------------------------------------------------------------
# arguments
# ----------------------------------------------------------------------------------------------------
MODE=""
case "${1:-}" in
  --dry-run) MODE=dry-run; shift ;;
  --preflight-scan) MODE=preflight; shift ;;
  -*) usage ;;
esac
(($# == 3)) || usage
ARG_ENV=$1
ARG_DATASET_MANIFEST=$2
ARG_CONFIG=$3

if [[ -z $MODE ]]; then
  if [[ -n ${SCHOLARCHAIN_CONFIRM_BOUNDED_RUN:-} ]]; then MODE=execute; else MODE=dry-run; fi
fi
step "start mode=$MODE at=$(now_utc)"

[[ $ARG_ENV =~ ^[a-z][a-z0-9-]{1,30}$ ]] || die "$EX_USAGE" "environment '$ARG_ENV' is not a lowercase name"
is_repo_path "$ARG_DATASET_MANIFEST" || die "$EX_USAGE" "dataset manifest path is not a plain repository path"
[[ $ARG_DATASET_MANIFEST == datasets/* ]] || die "$EX_USAGE" "dataset manifest must be under datasets/"
is_repo_path "$ARG_CONFIG" || die "$EX_USAGE" "run config path is not a plain repository path"

for tool in git jq awk gzip wc date; do
  command -v "$tool" >/dev/null 2>&1 || die "$EX_USAGE" "required tool '$tool' is not installed"
done
if ! command -v sha256sum >/dev/null 2>&1 && ! command -v shasum >/dev/null 2>&1; then
  die "$EX_USAGE" "neither sha256sum nor shasum is installed"
fi

# ----------------------------------------------------------------------------------------------------
# repository state
# ----------------------------------------------------------------------------------------------------
step "repository"
REPO_ROOT=$(git rev-parse --show-toplevel 2>/dev/null) || die "$EX_USAGE" "not inside a git work tree"
[[ $(pwd -P) == "$(cd "$REPO_ROOT" && pwd -P)" ]] || die "$EX_USAGE" "run from the repository root ($REPO_ROOT)"
HEAD_SHA=$(git rev-parse --verify HEAD) || die "$EX_USAGE" "no HEAD commit"
ok "head: $HEAD_SHA"

require_tracked() { # require_tracked <label> <path>
  [[ -f $2 && ! -L $2 ]] || die "$EX_USAGE" "$1 '$2' is not a regular file"
  git ls-files --error-unmatch -- "$2" >/dev/null 2>&1 || die "$EX_REFUSED" "$1 '$2' is not committed"
  if ! git diff --quiet HEAD -- "$2"; then die "$EX_REFUSED" "$1 '$2' differs from HEAD"; fi
}
require_tracked "dataset manifest" "$ARG_DATASET_MANIFEST"
require_tracked "run config" "$ARG_CONFIG"

if [[ $MODE != preflight ]]; then
  DIRTY=$(git status --porcelain --untracked-files=all)
  if [[ -n $DIRTY ]]; then
    printf '%s\n' "$DIRTY" | head -n 50 >&2 || true
    die "$EX_REFUSED" "working tree is not clean (git status --porcelain above); commit or remove the changes"
  fi
  ok "clean tree"
  ENV_DIR="infra/terraform/environments/$ARG_ENV"
  if [[ ! -d $ENV_DIR ]] || [[ -z $(git ls-files -- "$ENV_DIR") ]]; then
    die "$EX_REFUSED" "environment '$ARG_ENV' is not ratified: no committed $ENV_DIR/ (Phase 12)"
  fi
  ok "environment directory: $ENV_DIR"
fi

# ----------------------------------------------------------------------------------------------------
# run configuration (parsed, never sourced)
# ----------------------------------------------------------------------------------------------------
step "run config $ARG_CONFIG"
SEEN_KEYS=" "
LINE_NO=0
while IFS= read -r line || [[ -n $line ]]; do
  LINE_NO=$((LINE_NO + 1))
  [[ -z $line || $line == \#* ]] && continue
  if [[ ! $line =~ ^([a-z0-9_]+)=([A-Za-z0-9._:/=,@+-]*)$ ]]; then
    die "$EX_USAGE" "$ARG_CONFIG:$LINE_NO is not key=value with allowed characters"
  fi
  key=${BASH_REMATCH[1]}
  value=${BASH_REMATCH[2]}
  in_list "$key" "$CONFIG_KEYS" || die "$EX_USAGE" "$ARG_CONFIG:$LINE_NO unknown key '$key'"
  [[ $SEEN_KEYS == *" $key "* ]] && die "$EX_USAGE" "$ARG_CONFIG:$LINE_NO repeats key '$key'"
  [[ -n $value ]] || die "$EX_USAGE" "$ARG_CONFIG:$LINE_NO '$key' is empty (no defaults)"
  [[ $value == *REPLACE-ME* ]] && die "$EX_USAGE" "$ARG_CONFIG:$LINE_NO '$key' is still a placeholder"
  SEEN_KEYS="$SEEN_KEYS$key "
  printf -v "CFG_$key" '%s' "$value"
done <"$ARG_CONFIG"
for key in $CONFIG_KEYS; do
  [[ $SEEN_KEYS == *" $key "* ]] || die "$EX_USAGE" "run config lacks required key '$key' (no defaults)"
done
cfg() {
  local n="CFG_$1"
  printf '%s' "${!n}"
}

[[ $(cfg config_version) == "$CONFIG_SCHEMA" ]] || die "$EX_USAGE" "config_version must be $CONFIG_SCHEMA"
ENVIRONMENT=$(cfg environment)
[[ $ENVIRONMENT == "$ARG_ENV" ]] || die "$EX_REFUSED" "config environment '$ENVIRONMENT' differs from argument '$ARG_ENV' (single environment)"
[[ $(cfg run_profile) == smoke ]] || die "$EX_REFUSED" "run_profile must be 'smoke'; functional runs need ratified caps and the human venue decision"
[[ $(cfg pipeline) == hadoop ]] || die "$EX_REFUSED" "pipeline must be 'hadoop' (Spark stages are not supported by this version)"

AWS_PROFILE_NAME=$(cfg aws_profile)
[[ $AWS_PROFILE_NAME =~ ^[A-Za-z0-9._-]{1,64}$ ]] || die "$EX_USAGE" "aws_profile is not a profile name"
AWS_REGION_NAME=$(cfg aws_region)
[[ $AWS_REGION_NAME =~ ^[a-z]{2}(-[a-z]+)+-[0-9]$ ]] || die "$EX_USAGE" "aws_region is not a Region code"
AWS_ACCOUNT=$(cfg aws_account_id)
[[ $AWS_ACCOUNT =~ ^[0-9]{12}$ ]] || die "$EX_USAGE" "aws_account_id is not 12 digits"
CLUSTER_ID=$(cfg emr_cluster_id)
[[ $CLUSTER_ID =~ ^j-[A-Z0-9]{8,20}$ ]] || die "$EX_USAGE" "emr_cluster_id is not an EMR cluster ID"
RELEASE_LABEL=$(cfg emr_release_label)
[[ $RELEASE_LABEL =~ ^emr-[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "$EX_USAGE" "emr_release_label is not emr-X.Y.Z"

ALLOWED_TYPES=$(cfg emr_allowed_instance_types | tr ',' ' ')
for t in $ALLOWED_TYPES; do
  [[ $t =~ ^[a-z][a-z0-9-]*\.[a-z0-9]+$ ]] || die "$EX_USAGE" "instance type '$t' is malformed"
  in_list "$t" "$HC_ALLOWED_INSTANCE_TYPES" || die "$EX_REFUSED" "instance type '$t' is not in the hard-cap list ($HC_ALLOWED_INSTANCE_TYPES)"
done
MAX_INSTANCES=$(cfg emr_max_instances)
need_int_range emr_max_instances "$MAX_INSTANCES" 1 "$HC_MAX_INSTANCES"
MAX_IDLE=$(cfg emr_max_idle_timeout_seconds)
need_int_range emr_max_idle_timeout_seconds "$MAX_IDLE" 60 "$HC_MAX_IDLE_TIMEOUT_SECONDS"
STEP_TIMEOUT_MIN=$(cfg step_timeout_minutes)
need_int_range step_timeout_minutes "$STEP_TIMEOUT_MIN" 1 "$HC_MAX_STEP_TIMEOUT_MINUTES"
POLL_SECONDS=$(cfg poll_interval_seconds)
need_int_range poll_interval_seconds "$POLL_SECONDS" "$HC_MIN_POLL_SECONDS" "$HC_MAX_POLL_SECONDS"
PRICE=$(cfg instance_hour_price_usd)
is_decimal "$PRICE" || die "$EX_USAGE" "instance_hour_price_usd is not a decimal"
dec_le 0.0001 "$PRICE" || die "$EX_USAGE" "instance_hour_price_usd must be positive"
MAX_COST=$(cfg max_cost_usd)
is_decimal "$MAX_COST" || die "$EX_USAGE" "max_cost_usd is not a decimal"
dec_le "$MAX_COST" "$HC_MAX_COST_USD" || die "$EX_REFUSED" "max_cost_usd=$MAX_COST exceeds the hard cap $HC_MAX_COST_USD"

RAW_BUCKET=$(cfg raw_bucket)
[[ $RAW_BUCKET =~ ^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$ ]] || die "$EX_USAGE" "raw_bucket is not a bucket name"
CURATED_ROOT=$(cfg curated_root_uri)
is_s3_uri "$CURATED_ROOT" || die "$EX_USAGE" "curated_root_uri is not s3://bucket[/prefix] without a trailing slash"
RUNS_URI=$(cfg pipeline_runs_uri)
is_s3_uri "$RUNS_URI" || die "$EX_USAGE" "pipeline_runs_uri is not s3://bucket[/prefix] without a trailing slash"
case "$RUNS_URI/" in "$CURATED_ROOT"/*) die "$EX_REFUSED" "pipeline_runs_uri must not lie inside curated_root_uri" ;; esac
case "$CURATED_ROOT/" in "$RUNS_URI"/*) die "$EX_REFUSED" "curated_root_uri must not lie inside pipeline_runs_uri" ;; esac

JAR_URI=$(cfg jar_uri)
JAR_GIT_SHA=$(cfg jar_git_sha)
need_hex jar_git_sha "$JAR_GIT_SHA" 40
JAR_SHA256=$(cfg jar_sha256)
need_hex jar_sha256 "$JAR_SHA256" 64
is_s3_uri "$JAR_URI" || die "$EX_USAGE" "jar_uri is not an s3 URI"
[[ $JAR_URI == */"$JAR_GIT_SHA"/*.jar ]] || die "$EX_REFUSED" "jar_uri must contain /<jar_git_sha>/ and end in .jar (immutable, SHA-versioned artifact)"
JAR_MAX_BYTES=$(cfg jar_max_bytes)
need_int_range jar_max_bytes "$JAR_MAX_BYTES" 1 "$HC_MAX_JAR_BYTES"

RUN_MANIFEST=$(cfg run_manifest_path)
is_repo_path "$RUN_MANIFEST" || die "$EX_USAGE" "run_manifest_path is not a plain repository path"
[[ $RUN_MANIFEST == *.json ]] || die "$EX_USAGE" "run_manifest_path must end in .json"
require_tracked "run manifest" "$RUN_MANIFEST"
RUN_MANIFEST_SHA256=$(cfg run_manifest_sha256)
need_hex run_manifest_sha256 "$RUN_MANIFEST_SHA256" 64
[[ $(sha256_file "$RUN_MANIFEST") == "$RUN_MANIFEST_SHA256" ]] || die "$EX_REFUSED" "run_manifest_sha256 does not match $RUN_MANIFEST"

DBLP_SNAPSHOT=$(cfg dblp_snapshot_id)
[[ $DBLP_SNAPSHOT =~ ^[A-Za-z0-9._-]+$ ]] || die "$EX_USAGE" "dblp_snapshot_id is not a token"
DBLP_URI=$(cfg dblp_input_uri)
is_s3_uri "$DBLP_URI" || die "$EX_USAGE" "dblp_input_uri is not an s3 URI"
DBLP_LOCAL=$(cfg dblp_input_local_path)
if [[ ! $DBLP_LOCAL =~ ^/?[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*$ ]] || [[ /$DBLP_LOCAL/ == */../* ]]; then
  die "$EX_USAGE" "dblp_input_local_path is not a plain path"
fi
[[ $DBLP_LOCAL == *.xml || $DBLP_LOCAL == *.xml.gz ]] || die "$EX_USAGE" "dblp_input_local_path must end in .xml or .xml.gz"
[[ ${DBLP_URI##*/} == "${DBLP_LOCAL##*/}" ]] || die "$EX_REFUSED" "dblp_input_uri and dblp_input_local_path must have the same file name"
DBLP_SHA256=$(cfg dblp_input_sha256)
need_hex dblp_input_sha256 "$DBLP_SHA256" 64
MAX_INPUT_BYTES=$(cfg max_input_bytes)
need_int_range max_input_bytes "$MAX_INPUT_BYTES" 1 "$HC_MAX_INPUT_BYTES"
MAX_INPUT_RECORDS=$(cfg max_input_records)
need_int_range max_input_records "$MAX_INPUT_RECORDS" 1 "$HC_MAX_INPUT_RECORDS"

STAGES=$(cfg stages)
[[ $STAGES =~ ^[a-z-]+(,[a-z-]+)*$ ]] || die "$EX_USAGE" "stages is not a comma-separated list"
prev_index=-1
for s in ${STAGES//,/ }; do
  idx=0
  found=-1
  for a in $ALL_STAGES; do
    if [[ $a == "$s" ]]; then found=$idx; fi
    idx=$((idx + 1))
  done
  ((found >= 0)) || die "$EX_USAGE" "unknown stage '$s' (allowed: $ALL_STAGES)"
  ((found > prev_index)) || die "$EX_USAGE" "stages must be distinct and in the order: $ALL_STAGES"
  prev_index=$found
done
STAGE_WORDS=" ${STAGES//,/ } "
[[ $STAGE_WORDS == *" normalize "* ]] || die "$EX_REFUSED" "stages must include normalize (a new run has no earlier normalize)"
if [[ $STAGE_WORDS == *" candidates "* && $STAGE_WORDS != *" blocking "* ]]; then
  die "$EX_REFUSED" "stage candidates requires blocking in the same run"
fi
RUNS_TEAMS=no
[[ $STAGE_WORDS == *" teams "* ]] && RUNS_TEAMS=yes

MARKUP_POLICY=$(cfg dblp_markup_policy)
in_list "$MARKUP_POLICY" "refuse-if-present accept-reported" || die "$EX_USAGE" "dblp_markup_policy must be refuse-if-present or accept-reported"
TEAM_MAX_AUTHORS=$(cfg team_max_authors)
need_int_range team_max_authors "$TEAM_MAX_AUTHORS" 2 "$HC_MAX_TEAM_AUTHORS"
TEAM_MAX_PAIRS=$(cfg team_max_pairs)
need_int_range team_max_pairs "$TEAM_MAX_PAIRS" 1 "$HC_MAX_TEAM_PAIRS"
TEAMS_OVERRIDE=$(cfg teams_oversize_override)
in_list "$TEAMS_OVERRIDE" "refuse allow" || die "$EX_USAGE" "teams_oversize_override must be refuse or allow"
ok "run config: every key present and well formed"

# ----------------------------------------------------------------------------------------------------
# dataset manifest (strict-subset YAML; only the dblp entries are read)
# ----------------------------------------------------------------------------------------------------
step "dataset manifest $ARG_DATASET_MANIFEST"
DM_OUT=$(awk '
  /^datasets:[ ]*$/ { inds = 1; next }
  inds && /^[^ ]/ { inds = 0 }
  inds && /^  [a-z]/ { cur = $1; sub(/:$/, "", cur); sec = ""; next }
  inds && cur == "dblp" && /^    [a-z_]+:/ {
    k = $1; sub(/:$/, "", k); sec = k
    if (k == "raw_prefix" || k == "schema_version") { v = $0; sub(/^[^:]*:[ ]*/, "", v); print k "=" v }
    next
  }
  inds && cur == "dblp" && sec == "snapshot" && /^      (id|date):/ {
    k = $1; sub(/:$/, "", k); v = $0; sub(/^[^:]*:[ ]*/, "", v); print "snapshot_" k "=" v
  }
' "$ARG_DATASET_MANIFEST")
DM_RAW_PREFIX=""
DM_SNAPSHOT_ID=""
DM_SNAPSHOT_DATE=""
DM_SCHEMA=""
while IFS='=' read -r k v; do
  v=${v#\"}
  v=${v%\"}
  case $k in
    raw_prefix) DM_RAW_PREFIX=$v ;;
    schema_version) DM_SCHEMA=$v ;;
    snapshot_id) DM_SNAPSHOT_ID=$v ;;
    snapshot_date) DM_SNAPSHOT_DATE=$v ;;
  esac
done <<<"$DM_OUT"
[[ -n $DM_RAW_PREFIX && -n $DM_SNAPSHOT_ID && -n $DM_SNAPSHOT_DATE && -n $DM_SCHEMA ]] ||
  die "$EX_USAGE" "dataset manifest lacks datasets.dblp raw_prefix, schema_version, or snapshot id/date"
[[ $DM_SNAPSHOT_ID == "$DBLP_SNAPSHOT" ]] || die "$EX_REFUSED" "dblp_snapshot_id '$DBLP_SNAPSHOT' differs from the manifest's '$DM_SNAPSHOT_ID'"
[[ $DM_RAW_PREFIX == "s3://{raw_bucket}/raw/dblp/snapshot_date=$DM_SNAPSHOT_DATE/" ]] ||
  die "$EX_REFUSED" "dblp raw_prefix '$DM_RAW_PREFIX' is not s3://{raw_bucket}/raw/dblp/snapshot_date=$DM_SNAPSHOT_DATE/"
RAW_PREFIX="s3://$RAW_BUCKET/raw/dblp/snapshot_date=$DM_SNAPSHOT_DATE/"
[[ $DBLP_URI == "$RAW_PREFIX"?* ]] || die "$EX_REFUSED" "dblp_input_uri must be an object under $RAW_PREFIX"
DATASET_MANIFEST_SHA256=$(sha256_file "$ARG_DATASET_MANIFEST")
ok "dblp snapshot $DM_SNAPSHOT_ID, raw prefix $RAW_PREFIX, schema $DM_SCHEMA"

# ----------------------------------------------------------------------------------------------------
# run manifest (scholarchain.run-manifest.v1, stage hadoop.pipeline)
# ----------------------------------------------------------------------------------------------------
step "run manifest $RUN_MANIFEST"
jq -e . "$RUN_MANIFEST" >/dev/null 2>&1 || die "$EX_USAGE" "$RUN_MANIFEST is not valid JSON"
mq() { jq -r "$@" "$RUN_MANIFEST"; }
[[ $(mq '.schemaVersion') == scholarchain.run-manifest.v1 ]] || die "$EX_REFUSED" "run manifest schemaVersion is not scholarchain.run-manifest.v1"
[[ $(mq '.stage') == hadoop.pipeline ]] || die "$EX_REFUSED" "run manifest stage is not hadoop.pipeline"
MANIFEST_CODE=$(mq '.codeVersion')
[[ $MANIFEST_CODE == "$JAR_GIT_SHA" ]] || die "$EX_REFUSED" "run manifest codeVersion '$MANIFEST_CODE' differs from jar_git_sha"
MANIFEST_ATTEMPT=$(mq '.attempt | if type == "number" then tostring else "" end')
need_int_range "run manifest attempt" "$MANIFEST_ATTEMPT" 1 1000
MANIFEST_CONFIG_DIGEST=$(mq '.configDigest')
is_hex "$MANIFEST_CONFIG_DIGEST" 64 || die "$EX_REFUSED" "run manifest configDigest is not 64 hex"
SNAP_MATCHES=$(jq -r --arg s "$DBLP_SNAPSHOT" '[.inputSnapshots[]? | select(.source == "dblp" and .snapshot == $s)] | length' "$RUN_MANIFEST")
[[ $SNAP_MATCHES == 1 ]] || die "$EX_REFUSED" "run manifest inputSnapshots must contain exactly one {source: dblp, snapshot: $DBLP_SNAPSHOT}"
MANIFEST_KEYS=$(jq -r '.parameters | if type == "object" then keys[] else error("parameters is not an object") end' "$RUN_MANIFEST" | tr '\n' ' ') ||
  die "$EX_REFUSED" "run manifest parameters is not an object"
# shellcheck disable=SC2086 # both lists are space-separated words without glob characters
EXPECTED_KEYS=$(printf '%s\n' $MANIFEST_PARAM_KEYS | LC_ALL=C sort | tr '\n' ' ')
# shellcheck disable=SC2086
GOT_KEYS=$(printf '%s\n' $MANIFEST_KEYS | LC_ALL=C sort | tr '\n' ' ')
[[ $GOT_KEYS == "$EXPECTED_KEYS" ]] || die "$EX_REFUSED" "run manifest parameters must be exactly: $EXPECTED_KEYS (got: $GOT_KEYS)"
param() {
  local v
  v=$(jq -r --arg k "$1" '.parameters[$k] | if type == "string" then . else "" end' "$RUN_MANIFEST")
  [[ -n $v ]] || die "$EX_REFUSED" "run manifest parameter $1 is not a non-empty string"
  printf '%s' "$v"
}
# Every value is read into a variable first: a failing $(param ...) then stops the script (set -e)
# instead of yielding an empty string to a later check.
for k in $MANIFEST_PARAM_KEYS; do
  PV=$(param "$k")
  printf -v "PARAM_${k//./_}" '%s' "$PV"
done
P_MAX_RECORD_BYTES=$PARAM_scholarchain_dblp_maxRecordBytes
need_int_range scholarchain.dblp.maxRecordBytes "$P_MAX_RECORD_BYTES" "$HC_MIN_RECORD_BYTES" "$HC_MAX_RECORD_BYTES"
need_int_range scholarchain.orcid.maxRecordBytes "$PARAM_scholarchain_orcid_maxRecordBytes" "$HC_MIN_RECORD_BYTES" "$HC_MAX_RECORD_BYTES"
need_int_range scholarchain.reject.maxExcerptChars "$PARAM_scholarchain_reject_maxExcerptChars" 0 "$HC_MAX_EXCERPT_CHARS"
P_TEAM_MAX_SIZE=$PARAM_scholarchain_team_maxSize
need_int_range scholarchain.team.maxSize "$P_TEAM_MAX_SIZE" 2 "$HC_MAX_TEAM_AUTHORS"
P_MAX_BLOCK=$PARAM_scholarchain_blocking_maxBlockSize
need_int_range scholarchain.blocking.maxBlockSize "$P_MAX_BLOCK" 2 "$HC_MAX_BLOCK_SIZE"
in_list "$PARAM_scholarchain_blocking_oversizePolicy" "drop fail" || die "$EX_REFUSED" "scholarchain.blocking.oversizePolicy must be drop or fail"
for r in normalize exactIds teams blocking candidates; do
  n="PARAM_scholarchain_reducers_$r"
  need_int_range "scholarchain.reducers.$r" "${!n}" 1 "$HC_MAX_REDUCERS"
done
in_list "$PARAM_scholarchain_dblp_encoding" "UTF-8 ISO-8859-1 US-ASCII" || die "$EX_REFUSED" "scholarchain.dblp.encoding must be UTF-8, ISO-8859-1, or US-ASCII"
[[ $PARAM_scholarchain_dblp_recordTypes =~ ^[a-z]+(,[a-z]+)*$ ]] || die "$EX_REFUSED" "scholarchain.dblp.recordTypes is not a comma-separated list"
for t in ${PARAM_scholarchain_dblp_recordTypes//,/ }; do
  in_list "$t" "$DBLP_PUBLICATION_TYPES" || die "$EX_REFUSED" "scholarchain.dblp.recordTypes names '$t', not a DBLP publication type"
done
for c in "$PARAM_scholarchain_codec_intermediate" "$PARAM_scholarchain_codec_output"; do
  [[ $c =~ ^org\.apache\.hadoop\.io\.compress\.[A-Za-z0-9]+Codec$ ]] || die "$EX_REFUSED" "codec '$c' is not an org.apache.hadoop.io.compress codec class"
done
for v in "$PARAM_scholarchain_blocking_keySourceVersion" "$PARAM_scholarchain_candidates_featureVersion"; do
  [[ $v =~ ^[A-Za-z0-9._-]+$ ]] || die "$EX_REFUSED" "version '$v' is not a token"
done
P_VENUES=$PARAM_scholarchain_dblp_venues
P_YEARS=$PARAM_scholarchain_dblp_yearRange
ok "run manifest: schema, stage, codeVersion, attempt=$MANIFEST_ATTEMPT, snapshot, 21 parameters within caps"
say "CONFIRM dblp_venues=$P_VENUES (taken verbatim from the committed manifest; not chosen by this script)"
say "CONFIRM dblp_year_range=$P_YEARS"

# ----------------------------------------------------------------------------------------------------
# jar provenance (git side only; the S3 object is verified in execute mode)
# ----------------------------------------------------------------------------------------------------
if [[ $MODE != preflight ]]; then
  step "jar provenance"
  git cat-file -e "$JAR_GIT_SHA^{commit}" 2>/dev/null || die "$EX_REFUSED" "jar_git_sha $JAR_GIT_SHA is not a commit in this repository"
  git merge-base --is-ancestor "$JAR_GIT_SHA" HEAD || die "$EX_REFUSED" "jar_git_sha $JAR_GIT_SHA is not an ancestor of HEAD"
  # shellcheck disable=SC2086 # JAR_SOURCE_PATHS is a fixed word list
  if ! git diff --quiet "$JAR_GIT_SHA" HEAD -- $JAR_SOURCE_PATHS; then
    die "$EX_REFUSED" "jar source paths ($JAR_SOURCE_PATHS) changed since $JAR_GIT_SHA; package a new jar"
  fi
  ok "jar commit $JAR_GIT_SHA is an ancestor of HEAD with unchanged jar sources"
fi

# ----------------------------------------------------------------------------------------------------
# local DBLP input scan (no AWS): markup constructs, record counts and sizes, team sizes
# ----------------------------------------------------------------------------------------------------
step "local scan $DBLP_LOCAL"
[[ -f $DBLP_LOCAL && ! -L $DBLP_LOCAL ]] || die "$EX_USAGE" "dblp_input_local_path '$DBLP_LOCAL' is not a regular file"
if git ls-files --error-unmatch -- "$DBLP_LOCAL" >/dev/null 2>&1; then
  die "$EX_REFUSED" "dblp_input_local_path must not be a committed file (dataset bytes stay out of git)"
fi
LOCAL_FILE_BYTES=$(file_bytes "$DBLP_LOCAL")
((LOCAL_FILE_BYTES <= MAX_INPUT_BYTES)) || die "$EX_REFUSED" "input file has $LOCAL_FILE_BYTES bytes > max_input_bytes=$MAX_INPUT_BYTES"
LOCAL_SHA256=$(sha256_file "$DBLP_LOCAL")
[[ $LOCAL_SHA256 == "$DBLP_SHA256" ]] || die "$EX_REFUSED" "local input sha256 $LOCAL_SHA256 differs from dblp_input_sha256"
ok "local input $LOCAL_FILE_BYTES bytes, sha256 matches"

scan_dblp() {
  local -a reader
  if [[ $1 == *.gz ]]; then reader=(gzip -cd -- "$1"); else reader=(cat -- "$1"); fi
  # head -c bounds the uncompressed stream at max + 1 byte (a gzip bomb cannot exceed it).
  "${reader[@]}" | head -c "$((MAX_INPUT_BYTES + 1))" | LC_ALL=C awk -v W="$2" -v PUB="$DBLP_PUBLICATION_TYPES" '
    function cnt(s, t,    n, i) { n = 0; while ((i = index(s, t)) > 0) { n++; s = substr(s, i + length(t)) } return n }
    BEGIN {
      np = split(PUB, a, " "); for (i = 1; i <= np; i++) pub[a[i]] = 1
      nonpub["www"] = 1; nonpub["person"] = 1; nonpub["data"] = 1
      off = 0; inrec = 0; npub = 0; nnon = 0; over = 0; maxlen = 0; maxteam = 0; pairs = 0
      authors_total = 0; interrupted = 0; comments = 0; cdata = 0; pis = 0; doctype = 0; entdecl = 0; xmldecl = 0
    }
    {
      line = $0
      if (NR == 1 && substr(line, 1, 5) == "<?xml") xmldecl = 1
      comments += cnt(line, "<!--"); cdata += cnt(line, "<![CDATA["); pis += cnt(line, "<?")
      doctype += cnt(line, "<!DOCTYPE"); entdecl += cnt(line, "<!ENTITY")
      rest = line; pos = 0
      while (match(rest, "</?[a-z]+([ \t>/]|$)")) {
        tag = substr(rest, RSTART, RLENGTH)
        isend = (substr(tag, 2, 1) == "/")
        name = tag; sub("^</?", "", name); sub("[ \t>/]$", "", name)
        at = off + pos + RSTART - 1
        isrec = ((name in pub) || (name in nonpub))
        if (inrec && !isend && isrec) { interrupted++; inrec = 0 }
        if (!inrec) {
          if (!isend && isrec) {
            inrec = 1; rtype = name; rstart = at; authors = 0
            if (name in pub) npub++; else nnon++
          }
        } else if (!isend && name == "author") {
          authors++
        } else if (isend && name == rtype) {
          rlen = at + length(rtype) + 3 - rstart
          if (rlen > maxlen) maxlen = rlen
          if (rlen > W) over++
          if (rtype in pub) {
            authors_total += authors
            if (authors > maxteam) maxteam = authors
            pairs += authors * (authors - 1) / 2
          }
          inrec = 0
        }
        adv = RSTART + RLENGTH - 1; pos += adv; rest = substr(rest, adv + 1)
      }
      off += length(line) + 1
    }
    END {
      printf "uncompressed_bytes=%.0f\n", off
      printf "records_publication=%d\nrecords_non_publication=%d\n", npub, nnon
      printf "record_bytes_max=%.0f\nrecords_over_max_record_bytes=%d\n", maxlen, over
      printf "records_interrupted=%d\nunterminated_at_eof=%d\n", interrupted, inrec
      printf "team_max_authors=%d\nteam_total_pairs=%.0f\nauthor_mentions=%.0f\n", maxteam, pairs, authors_total
      printf "xml_declaration=%d\ncomments=%d\ncdata_sections=%d\n", xmldecl, comments, cdata
      printf "processing_instructions_excluding_xml_declaration=%d\n", pis - xmldecl
      printf "doctype_declarations=%d\nentity_declarations=%d\n", doctype, entdecl
    }
  '
}
SCAN_OUT=$(scan_dblp "$DBLP_LOCAL" "$P_MAX_RECORD_BYTES") ||
  die "$EX_REFUSED" "local scan of $DBLP_LOCAL failed (gzip or read error, or the uncompressed input exceeds max_input_bytes)"
SCAN_KEYS="uncompressed_bytes records_publication records_non_publication record_bytes_max \
records_over_max_record_bytes records_interrupted unterminated_at_eof team_max_authors team_total_pairs \
author_mentions xml_declaration comments cdata_sections processing_instructions_excluding_xml_declaration \
doctype_declarations entity_declarations"
while IFS='=' read -r k v; do
  in_list "$k" "$SCAN_KEYS" || die "$EX_USAGE" "unexpected scan output '$k'"
  is_uint "$v" || die "$EX_USAGE" "scan value $k='$v' is not a count"
  printf -v "SCAN_$k" '%s' "$v"
  say "SCAN $k=$v"
done <<<"$SCAN_OUT"
for k in $SCAN_KEYS; do
  n="SCAN_$k"
  [[ -n ${!n:-} ]] || die "$EX_USAGE" "scan did not report $k"
done
say "SCAN note=heuristic line scanner; counts are exact only when no markup construct is present"

((SCAN_uncompressed_bytes <= MAX_INPUT_BYTES)) || die "$EX_REFUSED" "uncompressed input exceeds max_input_bytes=$MAX_INPUT_BYTES"
((SCAN_records_publication <= MAX_INPUT_RECORDS)) || die "$EX_REFUSED" "input has $SCAN_records_publication publication records > max_input_records=$MAX_INPUT_RECORDS"
((SCAN_records_publication >= 1)) || die "$EX_REFUSED" "input has no DBLP publication record"
MARKUP_TOTAL=$((SCAN_comments + SCAN_cdata_sections + SCAN_processing_instructions_excluding_xml_declaration + SCAN_entity_declarations))
if ((MARKUP_TOTAL > 0)); then
  if [[ $MARKUP_POLICY == refuse-if-present ]]; then
    die "$EX_REFUSED" "input contains $MARKUP_TOTAL comment/CDATA/PI/entity-declaration constructs and dblp_markup_policy=refuse-if-present"
  fi
  say "CONFIRM dblp_markup_constructs=$MARKUP_TOTAL accepted by dblp_markup_policy=accept-reported (reader residual limitation applies; job-contracts.md)"
else
  ok "no comment, CDATA, processing instruction, or entity declaration in the input"
fi
TEAMS_OVERRIDE_USED=no
if [[ $RUNS_TEAMS == yes ]]; then
  ((SCAN_team_max_authors <= HC_MAX_TEAM_AUTHORS)) || die "$EX_REFUSED" "largest team $SCAN_team_max_authors > hard cap $HC_MAX_TEAM_AUTHORS"
  ((SCAN_team_total_pairs <= HC_MAX_TEAM_PAIRS)) || die "$EX_REFUSED" "pair expansion $SCAN_team_total_pairs > hard cap $HC_MAX_TEAM_PAIRS"
  if ((SCAN_team_max_authors > TEAM_MAX_AUTHORS || SCAN_team_total_pairs > TEAM_MAX_PAIRS)); then
    if [[ $TEAMS_OVERRIDE != allow ]]; then
      die "$EX_REFUSED" "teams stage refused: largest team $SCAN_team_max_authors (bound $TEAM_MAX_AUTHORS), pairs $SCAN_team_total_pairs (bound $TEAM_MAX_PAIRS); set teams_oversize_override=allow only with human approval"
    fi
    TEAMS_OVERRIDE_USED=yes
    say "CONFIRM teams_oversize_override=allow used: largest team $SCAN_team_max_authors, pairs $SCAN_team_total_pairs"
  else
    ok "teams gate: largest team $SCAN_team_max_authors <= $TEAM_MAX_AUTHORS, pairs $SCAN_team_total_pairs <= $TEAM_MAX_PAIRS"
  fi
fi
CANDIDATE_PAIR_BOUND=$(((SCAN_author_mentions + SCAN_records_publication) * (P_MAX_BLOCK - 1) / 2))
if [[ $STAGE_WORDS == *" candidates "* ]]; then
  ((CANDIDATE_PAIR_BOUND <= HC_MAX_CANDIDATE_PAIRS)) || die "$EX_REFUSED" "candidate pair bound $CANDIDATE_PAIR_BOUND > hard cap $HC_MAX_CANDIDATE_PAIRS"
fi

# ----------------------------------------------------------------------------------------------------
# bounds and cost estimate
# ----------------------------------------------------------------------------------------------------
step "bounds"
COST_ESTIMATE=$(awk -v n="$MAX_INSTANCES" -v p="$PRICE" -v m="$STEP_TIMEOUT_MIN" 'BEGIN { printf "%.2f", n * p * m / 60 }')
dec_le "$COST_ESTIMATE" "$MAX_COST" || die "$EX_REFUSED" "estimated step-window cost $COST_ESTIMATE USD exceeds max_cost_usd=$MAX_COST"
SPLIT_OVERREAD=$((P_MAX_RECORD_BYTES + 16))
bound() { say "BOUND $1=$2 cap=$3 source=$4"; }
bound emr_max_instances "$MAX_INSTANCES" "$HC_MAX_INSTANCES" "config/HC_MAX_INSTANCES"
bound emr_allowed_instance_types "$(cfg emr_allowed_instance_types)" "${HC_ALLOWED_INSTANCE_TYPES// /,}" "config/HC_ALLOWED_INSTANCE_TYPES"
bound emr_max_idle_timeout_seconds "$MAX_IDLE" "$HC_MAX_IDLE_TIMEOUT_SECONDS" "config/HC_MAX_IDLE_TIMEOUT_SECONDS"
bound step_timeout_minutes "$STEP_TIMEOUT_MIN" "$HC_MAX_STEP_TIMEOUT_MINUTES" "config/HC_MAX_STEP_TIMEOUT_MINUTES"
bound max_cost_usd "$MAX_COST" "$HC_MAX_COST_USD" "config/HC_MAX_COST_USD"
bound estimated_step_window_cost_usd "$COST_ESTIMATE" "$MAX_COST" "emr_max_instances*instance_hour_price_usd*step_timeout_minutes/60"
bound max_input_bytes "$MAX_INPUT_BYTES" "$HC_MAX_INPUT_BYTES" "config/HC_MAX_INPUT_BYTES"
bound max_input_records "$MAX_INPUT_RECORDS" "$HC_MAX_INPUT_RECORDS" "config/HC_MAX_INPUT_RECORDS"
bound jar_max_bytes "$JAR_MAX_BYTES" "$HC_MAX_JAR_BYTES" "config/HC_MAX_JAR_BYTES"
bound dblp_max_record_bytes "$P_MAX_RECORD_BYTES" "$HC_MAX_RECORD_BYTES" "run-manifest/HC_MAX_RECORD_BYTES"
bound dblp_split_overread_bytes "$SPLIT_OVERREAD" "-" "maxRecordBytes+16 per split (job-contracts.md, DBLP reader D1)"
bound team_report_threshold "$P_TEAM_MAX_SIZE" "$HC_MAX_TEAM_AUTHORS" "run-manifest scholarchain.team.maxSize (reporting only; pairs are never suppressed)"
bound team_max_authors "$TEAM_MAX_AUTHORS" "$HC_MAX_TEAM_AUTHORS" "config/HC_MAX_TEAM_AUTHORS"
bound team_max_pairs "$TEAM_MAX_PAIRS" "$HC_MAX_TEAM_PAIRS" "config/HC_MAX_TEAM_PAIRS"
bound blocking_max_block_size "$P_MAX_BLOCK" "$HC_MAX_BLOCK_SIZE" "run-manifest/HC_MAX_BLOCK_SIZE"
bound candidate_pair_bound "$CANDIDATE_PAIR_BOUND" "$HC_MAX_CANDIDATE_PAIRS" "(mentions+works)*(maxBlockSize-1)/2"
bound reducers_each "<=${HC_MAX_REDUCERS}" "$HC_MAX_REDUCERS" "run-manifest/HC_MAX_REDUCERS"
bound runs "1" "1" "write-once submission key per run manifest sha256; no pending or running step on the cluster"
say "BOUND note=the cost estimate covers the step window only; the Terraform-managed cluster's idle time, S3, and transfer are outside it"

if [[ $MODE == preflight ]]; then
  say "RESULT PREFLIGHT-OK"
  exit 0
fi

# ----------------------------------------------------------------------------------------------------
# derived names, confirmation token, planned AWS calls
# ----------------------------------------------------------------------------------------------------
CONFIG_SHA256=$(sha256_file "$ARG_CONFIG")
SUBMISSION_URI="$RUNS_URI/submission=$RUN_MANIFEST_SHA256"
STAGED_MANIFEST_URI="$SUBMISSION_URI/run-manifest.json"
SUMMARY_URI="$SUBMISSION_URI/run-summary.json"
SNAPSHOT_PREFIX_KEY="$(s3_key "$CURATED_ROOT")"
[[ -n $SNAPSHOT_PREFIX_KEY ]] && SNAPSHOT_PREFIX_KEY="$SNAPSHOT_PREFIX_KEY/"
SNAPSHOT_PREFIX_KEY="${SNAPSHOT_PREFIX_KEY}hadoop/snapshot=$DBLP_SNAPSHOT/"
CURATED_BUCKET=$(s3_bucket "$CURATED_ROOT")
STEP_NAME="scholarchain-bounded-$ENVIRONMENT-${RUN_MANIFEST_SHA256:0:12}"

TOKEN_MATERIAL="$SCRIPT_NAME|env=$ENVIRONMENT|account=$AWS_ACCOUNT|region=$AWS_REGION_NAME|profile=$AWS_PROFILE_NAME"
TOKEN_MATERIAL="$TOKEN_MATERIAL|cluster=$CLUSTER_ID|release=$RELEASE_LABEL|head=$HEAD_SHA|config=$CONFIG_SHA256"
TOKEN_MATERIAL="$TOKEN_MATERIAL|manifest=$RUN_MANIFEST_SHA256|datasets=$DATASET_MANIFEST_SHA256|jar=$JAR_SHA256|input=$DBLP_SHA256"
TOKEN_MATERIAL="$TOKEN_MATERIAL|instances=$MAX_INSTANCES|types=$(cfg emr_allowed_instance_types)|timeout=$STEP_TIMEOUT_MIN"
TOKEN_MATERIAL="$TOKEN_MATERIAL|cost=$MAX_COST|estimate=$COST_ESTIMATE|records=$MAX_INPUT_RECORDS|bytes=$MAX_INPUT_BYTES"
TOKEN_MATERIAL="$TOKEN_MATERIAL|stages=$STAGES|markup=$MARKUP_POLICY|teams=$TEAMS_OVERRIDE_USED"
TOKEN_HASH=$(sha256_text "$TOKEN_MATERIAL")
TOKEN="brp-${TOKEN_HASH:0:32}"

AWS_BASE=(aws --profile "$AWS_PROFILE_NAME" --region "$AWS_REGION_NAME" --output json
  --cli-connect-timeout 10 --cli-read-timeout 120)

STEP_ARGS_JSON=$(jq -cn --arg m "$STAGED_MANIFEST_URI" --arg root "$CURATED_ROOT" --arg st "$STAGES" --arg d "$DBLP_URI" \
  '["--manifest", $m, "--curated-root", $root, "--stages", $st, "--dblp", $d]')
STEPS_JSON=$(jq -cn --arg name "$STEP_NAME" --arg jar "$JAR_URI" --arg main "$DRIVER_MAIN_CLASS" --argjson args "$STEP_ARGS_JSON" \
  '[{Name: $name, ActionOnFailure: "CONTINUE", HadoopJarStep: {Jar: $jar, MainClass: $main, Args: $args}}]')

print_plan() {
  local n=0
  p() {
    n=$((n + 1))
    printf '[%s] PLAN-AWS-CALL %02d %s: %s\n' "$SCRIPT_NAME" "$n" "$1" "${AWS_BASE[*]} $2"
  }
  p read "sts get-caller-identity"
  p read "emr describe-cluster --cluster-id $CLUSTER_ID"
  p read "emr list-instance-groups --cluster-id $CLUSTER_ID   (InstanceCollectionType INSTANCE_GROUP)"
  p read "emr list-instance-fleets --cluster-id $CLUSTER_ID   (InstanceCollectionType INSTANCE_FLEET)"
  p read "emr get-managed-scaling-policy --cluster-id $CLUSTER_ID"
  p read "emr get-auto-termination-policy --cluster-id $CLUSTER_ID"
  p read "emr list-steps --cluster-id $CLUSTER_ID --step-states PENDING CANCEL_PENDING RUNNING"
  p read "s3api head-object --bucket $(s3_bucket "$JAR_URI") --key $(s3_key "$JAR_URI")"
  p read "s3api get-object --bucket $(s3_bucket "$JAR_URI") --key $(s3_key "$JAR_URI") <tmp>/job.jar"
  p read "s3api head-object --bucket $(s3_bucket "$DBLP_URI") --key $(s3_key "$DBLP_URI")"
  p read "s3api get-object --bucket $(s3_bucket "$DBLP_URI") --key $(s3_key "$DBLP_URI") <tmp>/input"
  p read "s3api head-object --bucket $(s3_bucket "$STAGED_MANIFEST_URI") --key $(s3_key "$STAGED_MANIFEST_URI")   (must be 404)"
  p read "s3api head-object --bucket $(s3_bucket "$SUMMARY_URI") --key $(s3_key "$SUMMARY_URI")   (must be 404)"
  p read "s3api list-objects-v2 --bucket $CURATED_BUCKET --prefix $SNAPSHOT_PREFIX_KEY --delimiter / --max-items $HC_MAX_RUN_PREFIXES"
  p write "s3api put-object --bucket $(s3_bucket "$STAGED_MANIFEST_URI") --key $(s3_key "$STAGED_MANIFEST_URI") --body $RUN_MANIFEST --if-none-match *"
  p write "emr add-steps --cluster-id $CLUSTER_ID --steps file://<tmp>/steps.json   steps.json=$STEPS_JSON"
  p read "emr describe-step --cluster-id $CLUSTER_ID --step-id <step-id>   (every ${POLL_SECONDS}s, deadline ${STEP_TIMEOUT_MIN} min)"
  p write "emr cancel-steps --cluster-id $CLUSTER_ID --step-ids <step-id> --step-cancellation-option SEND_INTERRUPT   (only at the deadline or on interrupt)"
  p read "s3api list-objects-v2 --bucket $CURATED_BUCKET --prefix $SNAPSHOT_PREFIX_KEY --delimiter / --max-items $HC_MAX_RUN_PREFIXES"
  p read "s3api head-object --bucket $CURATED_BUCKET --key ${SNAPSHOT_PREFIX_KEY}run=<new run ID>/_run-manifest.json"
  p read "s3api head-object / get-object --bucket $CURATED_BUCKET --key ${SNAPSHOT_PREFIX_KEY}run=<new run ID>/<stage>/{_SUCCESS,_counters.json}   (stages: $STAGES)"
  p write "s3api put-object --bucket $(s3_bucket "$SUMMARY_URI") --key $(s3_key "$SUMMARY_URI") --body <tmp>/run-summary.json --if-none-match *"
}

confirm_block() {
  say "CONFIRM environment=$ENVIRONMENT"
  say "CONFIRM aws_account_id=$AWS_ACCOUNT"
  say "CONFIRM aws_region=$AWS_REGION_NAME"
  say "CONFIRM aws_profile=$AWS_PROFILE_NAME"
  say "CONFIRM emr_cluster_id=$CLUSTER_ID release=$RELEASE_LABEL"
  say "CONFIRM git_head=$HEAD_SHA"
  say "CONFIRM run_config=$ARG_CONFIG sha256=$CONFIG_SHA256"
  say "CONFIRM run_manifest=$RUN_MANIFEST sha256=$RUN_MANIFEST_SHA256 attempt=$MANIFEST_ATTEMPT"
  say "CONFIRM dataset_manifest=$ARG_DATASET_MANIFEST sha256=$DATASET_MANIFEST_SHA256"
  say "CONFIRM jar=$JAR_URI git_sha=$JAR_GIT_SHA sha256=$JAR_SHA256"
  say "CONFIRM dblp_input=$DBLP_URI sha256=$DBLP_SHA256 publication_records=$SCAN_records_publication"
  say "CONFIRM stages=$STAGES"
  say "CONFIRM max_instances=$MAX_INSTANCES step_timeout_minutes=$STEP_TIMEOUT_MIN estimated_cost_usd=$COST_ESTIMATE max_cost_usd=$MAX_COST"
  say "CONFIRM submission=$SUBMISSION_URI"
}

if [[ $MODE == dry-run ]]; then
  step "planned AWS calls (none executed)"
  print_plan
  confirm_block
  say "CONFIRMATION-TOKEN $TOKEN"
  say "to execute: review every CONFIRM and BOUND line, then set $CONFIRM_ENV_VAR=$TOKEN in your own terminal"
  say "RESULT DRY-RUN-OK"
  exit 0
fi

# ====================================================================================================
# EXECUTE MODE
# ====================================================================================================
[[ $SCHOLARCHAIN_CONFIRM_BOUNDED_RUN == "$TOKEN" ]] ||
  die "$EX_REFUSED" "$CONFIRM_ENV_VAR does not match the token for these inputs; run a dry run and review it"
ok "confirmation token matches"
confirm_block
command -v aws >/dev/null 2>&1 || die "$EX_USAGE" "aws CLI is not installed"
STARTED_AT=$(now_utc)

LOCK_DIR="${TMPDIR:-/tmp}/scholarchain-bounded-pipeline.lock"
mkdir "$LOCK_DIR" 2>/dev/null || die "$EX_REFUSED" "another bounded run holds $LOCK_DIR (remove it only after confirming no run is active)"
WORK_DIR=$(mktemp -d "${TMPDIR:-/tmp}/scholarchain-brp.XXXXXXXX")
STEP_ID=""
STEP_STATE=""
FINISHED=no
cleanup() {
  if [[ -n ${WORK_DIR:-} && -d $WORK_DIR ]]; then
    find "$WORK_DIR" -type f -exec rm -f {} + 2>/dev/null || true
    rmdir "$WORK_DIR" 2>/dev/null || true
  fi
  rmdir "$LOCK_DIR" 2>/dev/null || true
}
trap cleanup EXIT

READ_VERBS="sts:get-caller-identity emr:describe-cluster emr:list-instance-groups emr:list-instance-fleets \
emr:get-managed-scaling-policy emr:get-auto-termination-policy emr:list-steps emr:describe-step \
s3api:head-object s3api:get-object s3api:list-objects-v2"
WRITE_VERBS="s3api:put-object emr:add-steps emr:cancel-steps"
EMPTY_JSON='{}'
# Every AWS call goes through one of these two wrappers; anything outside the verb lists is refused.
# The AWS-CALL marker goes to stderr so that captured JSON on stdout stays clean.
aws_read() {
  in_list "$1:$2" "$READ_VERBS" || die "$EX_USAGE" "internal: $1 $2 is not an allowed read call"
  say "AWS-CALL read: ${AWS_BASE[*]} $*" >&2
  "${AWS_BASE[@]}" "$@"
}
aws_write() {
  in_list "$1:$2" "$WRITE_VERBS" || die "$EX_USAGE" "internal: $1 $2 is not an allowed write call"
  say "AWS-CALL write: ${AWS_BASE[*]} $*" >&2
  "${AWS_BASE[@]}" "$@"
}
s3_absent() { # s3_absent <uri>: 0 when absent, 1 when present, exit 4 on any other error
  local err
  say "AWS-CALL read: ${AWS_BASE[*]} s3api head-object --bucket $(s3_bucket "$1") --key $(s3_key "$1")" >&2
  if err=$("${AWS_BASE[@]}" s3api head-object --bucket "$(s3_bucket "$1")" --key "$(s3_key "$1")" 2>&1 >/dev/null); then
    return 1
  fi
  [[ $err == *"Not Found"* || $err == *"404"* ]] || die "$EX_AWS" "head-object $1 failed: $err"
  return 0
}
s3_fetch_bounded() { # s3_fetch_bounded <uri> <max bytes> <dest>; prints the object size
  local size
  size=$(aws_read s3api head-object --bucket "$(s3_bucket "$1")" --key "$(s3_key "$1")" | jq -r '.ContentLength')
  is_uint "$size" || die "$EX_AWS" "head-object $1 returned no ContentLength"
  ((size <= $2)) || die "$EX_REFUSED" "$1 has $size bytes > cap $2"
  aws_read s3api get-object --bucket "$(s3_bucket "$1")" --key "$(s3_key "$1")" "$3" >/dev/null ||
    die "$EX_AWS" "get-object $1 failed"
  printf '%s' "$size"
}

# ---- identity --------------------------------------------------------------------------------------
step "aws identity"
OBSERVED_ACCOUNT=$(aws_read sts get-caller-identity | jq -r '.Account') || die "$EX_AWS" "sts get-caller-identity failed"
[[ $OBSERVED_ACCOUNT == "$AWS_ACCOUNT" ]] || die "$EX_AWS" "profile $AWS_PROFILE_NAME is in account $OBSERVED_ACCOUNT, expected $AWS_ACCOUNT"
ok "account $OBSERVED_ACCOUNT"

# ---- cluster ---------------------------------------------------------------------------------------
step "emr cluster $CLUSTER_ID"
CLUSTER_JSON=$(aws_read emr describe-cluster --cluster-id "$CLUSTER_ID") || die "$EX_AWS" "describe-cluster failed"
cj() { printf '%s' "$CLUSTER_JSON" | jq -r "$@"; }
CL_STATE=$(cj '.Cluster.Status.State')
in_list "$CL_STATE" "WAITING RUNNING" || die "$EX_AWS" "cluster state is $CL_STATE (need WAITING or RUNNING)"
[[ $(cj '.Cluster.ReleaseLabel') == "$RELEASE_LABEL" ]] || die "$EX_AWS" "cluster ReleaseLabel $(cj '.Cluster.ReleaseLabel') differs from $RELEASE_LABEL"
[[ $(cj 'any(.Cluster.Applications[]?; .Name == "Hadoop")') == true ]] || die "$EX_AWS" "cluster has no Hadoop application"
tag() { cj --arg k "$1" '[.Cluster.Tags[]? | select(.Key == $k) | .Value] | first // ""'; }
for k in course project environment owner expiration; do
  [[ -n $(tag "$k") ]] || die "$EX_AWS" "cluster lacks required tag '$k'"
done
[[ $(tag environment) == "$ENVIRONMENT" ]] || die "$EX_AWS" "cluster tag environment=$(tag environment), expected $ENVIRONMENT"
EXPIRATION=$(tag expiration)
[[ $EXPIRATION =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2} ]] || die "$EX_AWS" "cluster tag expiration '$EXPIRATION' is not YYYY-MM-DD"
TODAY=$(date -u +%Y-%m-%d)
[[ ! ${EXPIRATION:0:10} < $TODAY ]] || die "$EX_AWS" "cluster expiration tag ${EXPIRATION:0:10} is in the past"
LOG_URI=$(cj '.Cluster.LogUri // ""')
[[ -n $LOG_URI ]] || die "$EX_AWS" "cluster has no LogUri (step logs are required evidence)"
COLLECTION=$(cj '.Cluster.InstanceCollectionType')

OBSERVED_TYPES=""
PRIMARY_COUNT=0
WORKER_COUNT=0
if [[ $COLLECTION == INSTANCE_GROUP ]]; then
  IG_JSON=$(aws_read emr list-instance-groups --cluster-id "$CLUSTER_ID") || die "$EX_AWS" "list-instance-groups failed"
  [[ $(printf '%s' "$IG_JSON" | jq '[.InstanceGroups[] | select(.AutoScalingPolicy != null)] | length') == 0 ]] ||
    die "$EX_AWS" "an instance group has an AutoScalingPolicy; only managed scaling with a bounded maximum is accepted"
  OBSERVED_TYPES=$(printf '%s' "$IG_JSON" | jq -r '[.InstanceGroups[].InstanceType] | unique | join(" ")')
  PRIMARY_COUNT=$(printf '%s' "$IG_JSON" | jq '[.InstanceGroups[] | select(.InstanceGroupType == "MASTER") | ([.RequestedInstanceCount, .RunningInstanceCount] | max)] | add // 0')
  WORKER_COUNT=$(printf '%s' "$IG_JSON" | jq '[.InstanceGroups[] | select(.InstanceGroupType != "MASTER") | ([.RequestedInstanceCount, .RunningInstanceCount] | max)] | add // 0')
elif [[ $COLLECTION == INSTANCE_FLEET ]]; then
  IF_JSON=$(aws_read emr list-instance-fleets --cluster-id "$CLUSTER_ID") || die "$EX_AWS" "list-instance-fleets failed"
  [[ $(printf '%s' "$IF_JSON" | jq '[.InstanceFleets[].InstanceTypeSpecifications[] | select((.WeightedCapacity // 1) != 1)] | length') == 0 ]] ||
    die "$EX_AWS" "instance fleet weighted capacity other than 1 cannot be bounded by instance count"
  OBSERVED_TYPES=$(printf '%s' "$IF_JSON" | jq -r '[.InstanceFleets[].InstanceTypeSpecifications[].InstanceType] | unique | join(" ")')
  PRIMARY_COUNT=$(printf '%s' "$IF_JSON" | jq '[.InstanceFleets[] | select(.InstanceFleetType == "MASTER") | ((.TargetOnDemandCapacity // 0) + (.TargetSpotCapacity // 0))] | add // 0')
  WORKER_COUNT=$(printf '%s' "$IF_JSON" | jq '[.InstanceFleets[] | select(.InstanceFleetType != "MASTER") | ((.TargetOnDemandCapacity // 0) + (.TargetSpotCapacity // 0))] | add // 0')
else
  die "$EX_AWS" "unknown InstanceCollectionType '$COLLECTION'"
fi
for t in $OBSERVED_TYPES; do
  in_list "$t" "$ALLOWED_TYPES" || die "$EX_AWS" "cluster uses instance type $t, not in emr_allowed_instance_types"
done
MS_JSON=$(aws_read emr get-managed-scaling-policy --cluster-id "$CLUSTER_ID") || die "$EX_AWS" "get-managed-scaling-policy failed"
MS_MAX=$(printf '%s' "$MS_JSON" | jq -r '.ManagedScalingPolicy.ComputeLimits.MaximumCapacityUnits // empty')
if [[ -n $MS_MAX ]]; then
  MS_UNIT=$(printf '%s' "$MS_JSON" | jq -r '.ManagedScalingPolicy.ComputeLimits.UnitType')
  in_list "$MS_UNIT" "Instances InstanceFleetUnits" || die "$EX_AWS" "managed scaling UnitType $MS_UNIT cannot be bounded by instance count"
  is_uint "$MS_MAX" || die "$EX_AWS" "managed scaling maximum '$MS_MAX' is not an integer"
  ((MS_MAX > WORKER_COUNT)) && WORKER_COUNT=$MS_MAX
else
  MS_MAX=none
fi
INSTANCE_BOUND=$((PRIMARY_COUNT + WORKER_COUNT))
((INSTANCE_BOUND <= MAX_INSTANCES)) || die "$EX_AWS" "cluster can reach $INSTANCE_BOUND instances > emr_max_instances=$MAX_INSTANCES"
AT_JSON=$(aws_read emr get-auto-termination-policy --cluster-id "$CLUSTER_ID") || die "$EX_AWS" "get-auto-termination-policy failed"
IDLE_TIMEOUT=$(printf '%s' "$AT_JSON" | jq -r '.AutoTerminationPolicy.IdleTimeout // empty')
is_uint "${IDLE_TIMEOUT:-x}" || die "$EX_AWS" "cluster has no auto-termination policy"
((IDLE_TIMEOUT <= MAX_IDLE)) || die "$EX_AWS" "cluster idle timeout ${IDLE_TIMEOUT}s > emr_max_idle_timeout_seconds=$MAX_IDLE"
ACTIVE_STEPS=$(aws_read emr list-steps --cluster-id "$CLUSTER_ID" --step-states PENDING CANCEL_PENDING RUNNING | jq '.Steps | length') ||
  die "$EX_AWS" "list-steps failed"
[[ $ACTIVE_STEPS == 0 ]] || die "$EX_AWS" "cluster has $ACTIVE_STEPS pending or running steps (single run only)"
ok "cluster $CL_STATE, types [$OBSERVED_TYPES], instance bound $INSTANCE_BOUND, managed max $MS_MAX, idle timeout ${IDLE_TIMEOUT}s, no active step"

# ---- artifacts and input ---------------------------------------------------------------------------
step "jar and input checksums"
JAR_BYTES=$(s3_fetch_bounded "$JAR_URI" "$JAR_MAX_BYTES" "$WORK_DIR/job.jar")
[[ $(sha256_file "$WORK_DIR/job.jar") == "$JAR_SHA256" ]] || die "$EX_REFUSED" "jar at $JAR_URI does not match jar_sha256"
rm -f "$WORK_DIR/job.jar"
ok "jar $JAR_BYTES bytes, sha256 matches"
INPUT_BYTES=$(s3_fetch_bounded "$DBLP_URI" "$MAX_INPUT_BYTES" "$WORK_DIR/input")
[[ $INPUT_BYTES == "$LOCAL_FILE_BYTES" ]] || die "$EX_REFUSED" "S3 input has $INPUT_BYTES bytes, local copy $LOCAL_FILE_BYTES"
[[ $(sha256_file "$WORK_DIR/input") == "$DBLP_SHA256" ]] || die "$EX_REFUSED" "S3 input does not match dblp_input_sha256 (the local scan would not describe it)"
rm -f "$WORK_DIR/input"
ok "S3 input is byte-identical to the scanned local copy"

# ---- write-once submission keys and existing runs --------------------------------------------------
step "submission keys"
s3_absent "$STAGED_MANIFEST_URI" || die "$EX_REFUSED" "$STAGED_MANIFEST_URI exists: this manifest was already submitted (rerun needs attempt + 1)"
s3_absent "$SUMMARY_URI" || die "$EX_REFUSED" "$SUMMARY_URI exists: this manifest was already submitted"
list_runs() {
  local out
  out=$(aws_read s3api list-objects-v2 --bucket "$CURATED_BUCKET" --prefix "$SNAPSHOT_PREFIX_KEY" --delimiter / \
    --max-items "$HC_MAX_RUN_PREFIXES") || die "$EX_AWS" "list-objects-v2 of the snapshot prefix failed"
  [[ -n $out ]] || out=$EMPTY_JSON
  [[ $(printf '%s' "$out" | jq -r '.NextToken // ""') == "" ]] || die "$EX_REFUSED" "more than $HC_MAX_RUN_PREFIXES run prefixes under the snapshot"
  printf '%s' "$out" | jq -r '.CommonPrefixes[]?.Prefix' | LC_ALL=C sort
}
RUNS_BEFORE=$(list_runs)
ok "$(printf '%s' "$RUNS_BEFORE" | grep -c . || true) existing run prefixes recorded"

# ---- summary writer (used on every exit path after this point) -------------------------------------
NEW_RUN_URI=""
NEW_RUN_ID=""
STAGES_JSON="[]"
STEP_FAILURE="null"
write_summary() {
  local status=$1 ended
  ended=$(now_utc)
  jq -n \
    --arg schema "$SUMMARY_SCHEMA" --arg status "$status" --arg env "$ENVIRONMENT" \
    --arg account "$AWS_ACCOUNT" --arg region "$AWS_REGION_NAME" --arg head "$HEAD_SHA" \
    --arg started "$STARTED_AT" --arg ended "$ended" --arg token "$TOKEN" \
    --arg cfgPath "$ARG_CONFIG" --arg cfgSha "$CONFIG_SHA256" \
    --arg dmPath "$ARG_DATASET_MANIFEST" --arg dmSha "$DATASET_MANIFEST_SHA256" \
    --arg rmPath "$RUN_MANIFEST" --arg rmSha "$RUN_MANIFEST_SHA256" --arg rmUri "$STAGED_MANIFEST_URI" \
    --argjson attempt "$MANIFEST_ATTEMPT" --arg code "$MANIFEST_CODE" --arg cdig "$MANIFEST_CONFIG_DIGEST" \
    --arg venues "$P_VENUES" --slurpfile rm "$RUN_MANIFEST" \
    --arg jarUri "$JAR_URI" --arg jarSha "$JAR_GIT_SHA" --arg jarSum "$JAR_SHA256" --argjson jarBytes "${JAR_BYTES:-0}" \
    --arg inUri "$DBLP_URI" --arg inLocal "$DBLP_LOCAL" --arg inSum "$DBLP_SHA256" --argjson inBytes "$LOCAL_FILE_BYTES" \
    --argjson sUb "$SCAN_uncompressed_bytes" --argjson sPub "$SCAN_records_publication" \
    --argjson sNon "$SCAN_records_non_publication" --argjson sMaxRec "$SCAN_record_bytes_max" \
    --argjson sOver "$SCAN_records_over_max_record_bytes" --argjson sInt "$SCAN_records_interrupted" \
    --argjson sEof "$SCAN_unterminated_at_eof" --argjson sTeam "$SCAN_team_max_authors" \
    --argjson sPairs "$SCAN_team_total_pairs" --argjson sMent "$SCAN_author_mentions" \
    --argjson sCom "$SCAN_comments" --argjson sCd "$SCAN_cdata_sections" \
    --argjson sPi "$SCAN_processing_instructions_excluding_xml_declaration" \
    --argjson sDt "$SCAN_doctype_declarations" --argjson sEnt "$SCAN_entity_declarations" \
    --argjson maxInst "$MAX_INSTANCES" --arg types "$(cfg emr_allowed_instance_types)" \
    --argjson timeout "$STEP_TIMEOUT_MIN" --argjson idleCap "$MAX_IDLE" --arg maxCost "$MAX_COST" \
    --arg estimate "$COST_ESTIMATE" --arg price "$PRICE" --argjson maxRec "$MAX_INPUT_RECORDS" \
    --argjson maxBytes "$MAX_INPUT_BYTES" --argjson wBytes "$P_MAX_RECORD_BYTES" --argjson overread "$SPLIT_OVERREAD" \
    --argjson teamAuth "$TEAM_MAX_AUTHORS" --argjson teamPairs "$TEAM_MAX_PAIRS" --arg teamsOverride "$TEAMS_OVERRIDE_USED" \
    --arg markup "$MARKUP_POLICY" --argjson candBound "$CANDIDATE_PAIR_BOUND" --arg stages "$STAGES" \
    --argjson hcInst "$HC_MAX_INSTANCES" --arg hcTypes "$HC_ALLOWED_INSTANCE_TYPES" --argjson hcIdle "$HC_MAX_IDLE_TIMEOUT_SECONDS" \
    --argjson hcTimeout "$HC_MAX_STEP_TIMEOUT_MINUTES" --arg hcCost "$HC_MAX_COST_USD" --argjson hcBytes "$HC_MAX_INPUT_BYTES" \
    --argjson hcRec "$HC_MAX_INPUT_RECORDS" --argjson hcTeam "$HC_MAX_TEAM_AUTHORS" --argjson hcPairs "$HC_MAX_TEAM_PAIRS" \
    --arg cluster "$CLUSTER_ID" --arg release "$RELEASE_LABEL" --arg logUri "$LOG_URI" \
    --argjson obsInst "$INSTANCE_BOUND" --arg obsTypes "$OBSERVED_TYPES" --arg msMax "$MS_MAX" \
    --argjson idle "$IDLE_TIMEOUT" --arg stepId "$STEP_ID" --arg stepState "$STEP_STATE" --argjson stepFailure "$STEP_FAILURE" \
    --arg runUri "$NEW_RUN_URI" --arg runId "$NEW_RUN_ID" --argjson stageOut "$STAGES_JSON" \
    '{
      schemaVersion: $schema, status: $status, environment: $env, awsAccountId: $account, awsRegion: $region,
      gitCommit: $head, startedAt: $started, endedAt: $ended, confirmationToken: $token,
      config: {path: $cfgPath, sha256: $cfgSha},
      datasetManifest: {path: $dmPath, sha256: $dmSha},
      runManifest: {path: $rmPath, sha256: $rmSha, stagedUri: $rmUri, attempt: $attempt, codeVersion: $code,
        configDigest: $cdig, dblpVenues: $venues, parameters: $rm[0].parameters},
      jar: {uri: $jarUri, gitSha: $jarSha, sha256: $jarSum, bytes: $jarBytes},
      input: {uri: $inUri, localPath: $inLocal, sha256: $inSum, fileBytes: $inBytes,
        scan: {uncompressedBytes: $sUb, publicationRecords: $sPub, nonPublicationRecords: $sNon,
          maxRecordBytesObserved: $sMaxRec, recordsOverMaxRecordBytes: $sOver, recordsInterrupted: $sInt,
          unterminatedAtEof: $sEof, maxTeamSize: $sTeam, totalPairExpansion: $sPairs, authorMentions: $sMent,
          comments: $sCom, cdataSections: $sCd, processingInstructions: $sPi, doctypeDeclarations: $sDt,
          entityDeclarations: $sEnt}},
      bounds: {maxInstances: $maxInst, allowedInstanceTypes: $types, stepTimeoutMinutes: $timeout,
        maxIdleTimeoutSeconds: $idleCap, maxCostUsd: $maxCost, estimatedStepWindowCostUsd: $estimate,
        instanceHourPriceUsd: $price, maxInputRecords: $maxRec, maxInputBytes: $maxBytes,
        dblpMaxRecordBytes: $wBytes, dblpSplitOverreadBytes: $overread, teamMaxAuthors: $teamAuth,
        teamMaxPairs: $teamPairs, teamsOversizeOverrideUsed: $teamsOverride, dblpMarkupPolicy: $markup,
        candidatePairBound: $candBound, stages: $stages},
      hardCaps: {maxInstances: $hcInst, instanceTypes: $hcTypes, idleTimeoutSeconds: $hcIdle,
        stepTimeoutMinutes: $hcTimeout, costUsd: $hcCost, inputBytes: $hcBytes, inputRecords: $hcRec,
        teamAuthors: $hcTeam, teamPairs: $hcPairs},
      emr: {clusterId: $cluster, releaseLabel: $release, logUri: $logUri, observedInstanceBound: $obsInst,
        observedTypes: $obsTypes, managedScalingMax: $msMax, idleTimeoutSeconds: $idle, stepId: $stepId,
        stepState: $stepState, stepFailure: $stepFailure},
      outputs: {curatedRunUri: $runUri, runId: $runId, stages: $stageOut}
    }' >"$WORK_DIR/run-summary.json" || {
    printf '[%s] FAIL could not build the run summary\n' "$SCRIPT_NAME" >&2
    return 1
  }
  say "SUMMARY-BEGIN"
  cat "$WORK_DIR/run-summary.json"
  say "SUMMARY-END"
  aws_write s3api put-object --bucket "$(s3_bucket "$SUMMARY_URI")" --key "$(s3_key "$SUMMARY_URI")" \
    --body "$WORK_DIR/run-summary.json" --if-none-match "*" >/dev/null ||
    printf '[%s] FAIL could not write %s; the summary above is the evidence\n' "$SCRIPT_NAME" "$SUMMARY_URI" >&2
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

# ---- stage the manifest and submit the step --------------------------------------------------------
step "stage run manifest"
aws_write s3api put-object --bucket "$(s3_bucket "$STAGED_MANIFEST_URI")" --key "$(s3_key "$STAGED_MANIFEST_URI")" \
  --body "$RUN_MANIFEST" --if-none-match "*" >/dev/null ||
  die "$EX_REFUSED" "could not write $STAGED_MANIFEST_URI (already exists or no permission); nothing was submitted"
ok "staged $STAGED_MANIFEST_URI"

step "submit EMR step"
printf '%s\n' "$STEPS_JSON" >"$WORK_DIR/steps.json"
STEP_ID=$(aws_write emr add-steps --cluster-id "$CLUSTER_ID" --steps "file://$WORK_DIR/steps.json" | jq -r '.StepIds[0] // ""') || true
[[ $STEP_ID =~ ^s-[A-Z0-9]+$ ]] || finish SUBMIT-FAILED "$EX_AWS" "add-steps returned no step ID; staged manifest stays at $STAGED_MANIFEST_URI (rerun needs attempt + 1)"
ok "step $STEP_ID submitted"

cancel_own_step() {
  say "STEP cancel own step $STEP_ID ($1)"
  aws_write emr cancel-steps --cluster-id "$CLUSTER_ID" --step-ids "$STEP_ID" \
    --step-cancellation-option SEND_INTERRUPT >/dev/null || printf '[%s] FAIL cancel-steps %s failed; cancel it manually\n' "$SCRIPT_NAME" "$STEP_ID" >&2
}
on_interrupt() {
  trap - INT TERM
  if [[ $FINISHED == no && -n $STEP_ID ]]; then
    cancel_own_step "interrupted"
    STEP_STATE=INTERRUPTED-BY-OPERATOR
    finish INTERRUPTED "$EX_RUN" "interrupted; step $STEP_ID cancellation requested"
  fi
  exit "$EX_RUN"
}
trap on_interrupt INT TERM

# ---- bounded wait ----------------------------------------------------------------------------------
step "wait for step $STEP_ID (deadline ${STEP_TIMEOUT_MIN} min)"
DEADLINE=$(($(date +%s) + STEP_TIMEOUT_MIN * 60))
while :; do
  STEP_JSON=$(aws_read emr describe-step --cluster-id "$CLUSTER_ID" --step-id "$STEP_ID") || STEP_JSON=""
  [[ -n $STEP_JSON ]] || STEP_JSON=$EMPTY_JSON
  STEP_STATE=$(printf '%s' "$STEP_JSON" | jq -r '.Step.Status.State // "UNKNOWN"') || STEP_STATE=UNKNOWN
  say "STEP poll step=$STEP_ID state=$STEP_STATE at=$(now_utc)"
  case $STEP_STATE in
    COMPLETED) break ;;
    FAILED | CANCELLED | INTERRUPTED)
      STEP_FAILURE=$(printf '%s' "$STEP_JSON" | jq -c '.Step.Status.FailureDetails // null') || STEP_FAILURE=null
      finish "STEP-$STEP_STATE" "$EX_RUN" "step $STEP_ID ended $STEP_STATE; logs under $LOG_URI$CLUSTER_ID/steps/$STEP_ID/"
      ;;
  esac
  if (($(date +%s) >= DEADLINE)); then
    cancel_own_step "deadline"
    CANCEL_DEADLINE=$(($(date +%s) + 600))
    while (($(date +%s) < CANCEL_DEADLINE)); do
      sleep "$HC_MIN_POLL_SECONDS"
      STEP_STATE=$(aws_read emr describe-step --cluster-id "$CLUSTER_ID" --step-id "$STEP_ID" | jq -r '.Step.Status.State // "UNKNOWN"') || STEP_STATE=UNKNOWN
      in_list "$STEP_STATE" "CANCELLED INTERRUPTED FAILED COMPLETED" && break
    done
    finish TIMED-OUT "$EX_RUN" "step $STEP_ID exceeded ${STEP_TIMEOUT_MIN} min; final observed state $STEP_STATE"
  fi
  sleep "$POLL_SECONDS"
done
ok "step $STEP_ID COMPLETED"

# ---- output verification ---------------------------------------------------------------------------
step "verify outputs"
# After submission every failure must go through finish (summary first), so helpers that may call die
# run in subshells here and their status is mapped explicitly.
require_present() { # require_present <uri> <message>
  local rc=0
  (s3_absent "$1") || rc=$?
  case $rc in
    1) return 0 ;;
    0) finish VERIFY-FAILED "$EX_VERIFY" "$2" ;;
    *) finish VERIFY-FAILED "$EX_VERIFY" "head-object $1 failed" ;;
  esac
}
RUNS_AFTER=$(list_runs) || finish VERIFY-FAILED "$EX_VERIFY" "could not list run prefixes after the step"
NEW_PREFIXES=$(LC_ALL=C comm -13 <(printf '%s\n' "$RUNS_BEFORE") <(printf '%s\n' "$RUNS_AFTER") | grep . || true)
NEW_COUNT=$(printf '%s' "$NEW_PREFIXES" | grep -c . || true)
[[ $NEW_COUNT == 1 ]] || finish VERIFY-FAILED "$EX_VERIFY" "expected exactly one new run prefix under s3://$CURATED_BUCKET/$SNAPSHOT_PREFIX_KEY, found $NEW_COUNT"
[[ $NEW_PREFIXES =~ run=([0-9a-f]{64})/$ ]] || finish VERIFY-FAILED "$EX_VERIFY" "new prefix '$NEW_PREFIXES' is not run=<64 hex>/"
NEW_RUN_ID=${BASH_REMATCH[1]}
NEW_RUN_URI="s3://$CURATED_BUCKET/$NEW_PREFIXES"
require_present "${NEW_RUN_URI}_run-manifest.json" "${NEW_RUN_URI}_run-manifest.json is missing"
for s in ${STAGES//,/ }; do
  require_present "${NEW_RUN_URI}$s/_SUCCESS" "stage $s has no _SUCCESS"
  COUNTERS_URI="${NEW_RUN_URI}$s/_counters.json"
  require_present "$COUNTERS_URI" "stage $s has _SUCCESS but no _counters.json"
  (s3_fetch_bounded "$COUNTERS_URI" "$HC_MAX_COUNTERS_BYTES" "$WORK_DIR/counters-$s.json" >/dev/null) ||
    finish VERIFY-FAILED "$EX_VERIFY" "could not fetch $COUNTERS_URI within $HC_MAX_COUNTERS_BYTES bytes"
  CSUM=$(sha256_file "$WORK_DIR/counters-$s.json")
  CJSON=$(jq -c . "$WORK_DIR/counters-$s.json" 2>/dev/null) || CJSON='"unparseable"'
  STAGES_JSON=$(printf '%s' "$STAGES_JSON" | jq -c --arg st "$s" --arg su "${NEW_RUN_URI}$s/_SUCCESS" --arg cu "$COUNTERS_URI" \
    --arg cs "$CSUM" --argjson c "$CJSON" '. + [{stage: $st, successUri: $su, countersUri: $cu, countersSha256: $cs, counters: $c}]')
  ok "stage $s: _SUCCESS and _counters.json present"
done
finish COMPLETED 0 ""
