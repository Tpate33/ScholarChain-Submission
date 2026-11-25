#!/usr/bin/env bash
# =====================================================================================================
# scripts/run-experiment.sh -- manifest-driven, bounded experiment runner (Phase 15 interface).
# Owner: infra-engineer (IE-P13-01). Operator: experiment-engineer, after human approval of the manifest.
#
# SKELETON. Validation, bounds, the planned-action listing and the two-step confirmation are
# implemented; NO experiment workload is implemented yet: with a valid confirmation token the script
# refuses at the workload step (TODO per kind below). It makes no AWS call in any mode today.
#
# USAGE   scripts/run-experiment.sh <experiments/.../manifest.json> <environment>
#
#   Without SCHOLARCHAIN_CONFIRM_EXPERIMENT: DRY RUN. Validates the committed, unmodified manifest and
#   every bound, prints the planned bounded actions, the cost bound and the confirmation token. Writes
#   nothing, calls nothing.
#   With SCHOLARCHAIN_CONFIRM_EXPERIMENT=<token printed by the dry run> (set by the human in their own
#   terminal after reviewing the dry run): execute mode. The token binds the script, environment,
#   account, Region, HEAD, manifest sha256 and the bounds, so any change needs a new dry run.
#   KNOWN (CR-P13-01 n3, accepted): the token is deterministic, not single-use, so it can be replayed
#   for the same inputs. Harmless while no workload is implemented (execute mode always refuses);
#   revisit (nonce or create-once run record) when the first workload is implemented.
#
# MANIFEST (JSON, PROPOSED contract scholarchain.experiment-manifest.v1; owner of manifests:
# experiment-engineer):
#   schemaVersion       "scholarchain.experiment-manifest.v1"
#   experimentId        [a-z0-9][a-z0-9-]{0,62}
#   environment         equals <environment>
#   region              equals aws_region of env.conf
#   kind                emr-baseline | emr-burst | embedding-backlog | query-load | retrieval-modes |
#                       failure-recovery   (spec Phase 15 experiments A-F)
#   codeVersion         40-hex commit, an ancestor of HEAD
#   datasetSnapshot     [A-Za-z0-9._=/-]{1,128}
#   maxCostUsd          decimal string, <= HC_MAX_COST_USD
#   maxDurationMinutes  integer 1..HC_MAX_DURATION_MINUTES
#   resourceLimits      { maxEmrInstances 0..HC_MAX_EMR_INSTANCES, maxEcsTasks 0..HC_MAX_ECS_TASKS,
#                         maxConcurrency 1..HC_MAX_CONCURRENCY, maxRequests 0..HC_MAX_REQUESTS }
#   metricsPrefix       [A-Za-z0-9._=/-]{1,256}, where raw results will be written (immutable)
#
# HARD CAPS (a manifest may only tighten them; changing them is a reviewed change to this file)
readonly HC_MAX_COST_USD=5
readonly HC_MAX_DURATION_MINUTES=120
readonly HC_MAX_EMR_INSTANCES=2
readonly HC_MAX_ECS_TASKS=6
readonly HC_MAX_CONCURRENCY=100
readonly HC_MAX_REQUESTS=50000
readonly KINDS="emr-baseline emr-burst embedding-backlog query-load retrieval-modes failure-recovery"
#
# MARKERS  [run-experiment] STEP / CHECK ok / BOUND / PLAN-ACTION / CONFIRM / CONFIRMATION-TOKEN /
#          RESULT DRY-RUN-OK; FAIL on stderr.
# EXIT     0 dry run ok, 2 usage, 3 refused (manifest, bounds, token, workload not implemented)
# =====================================================================================================
set -euo pipefail
readonly SCRIPT_NAME=run-experiment
# shellcheck source=scripts/lib/common.sh disable=SC1091
. "$(dirname "$0")/lib/common.sh"
readonly CONFIRM_ENV_VAR=SCHOLARCHAIN_CONFIRM_EXPERIMENT
readonly MANIFEST_SCHEMA=scholarchain.experiment-manifest.v1

(($# == 2)) || die "$EX_USAGE" "usage: scripts/run-experiment.sh <experiments/.../manifest.json> <environment>"
ARG_MANIFEST=$1
ARG_ENV=$2
is_env_name "$ARG_ENV" || die "$EX_USAGE" "environment '$ARG_ENV' is not a short lowercase name"
is_repo_path "$ARG_MANIFEST" || die "$EX_USAGE" "manifest path is not a plain repository path"
[[ $ARG_MANIFEST == experiments/*.json ]] || die "$EX_USAGE" "manifest must be a .json file under experiments/"
need_tools git jq awk shasum
MODE=dry-run
[[ -n ${SCHOLARCHAIN_CONFIRM_EXPERIMENT:-} ]] && MODE=execute
step "start mode=$MODE env=$ARG_ENV manifest=$ARG_MANIFEST at=$(now_utc)"

require_repo_root
require_clean_tree
load_env_conf "$ARG_ENV"
require_tracked "experiment manifest" "$ARG_MANIFEST"
jq -e 'type == "object"' "$ARG_MANIFEST" >/dev/null 2>&1 || die "$EX_USAGE" "$ARG_MANIFEST is not a JSON object"
EXPECTED_KEYS='["codeVersion","datasetSnapshot","environment","experimentId","kind","maxCostUsd","maxDurationMinutes","metricsPrefix","region","resourceLimits","schemaVersion"]'
LIMIT_KEYS='["maxConcurrency","maxEcsTasks","maxEmrInstances","maxRequests"]'
jq -e --argjson k "$EXPECTED_KEYS" --argjson l "$LIMIT_KEYS" \
  '(keys == $k) and (.resourceLimits | type == "object" and keys == $l)' "$ARG_MANIFEST" >/dev/null ||
  die "$EX_USAGE" "$ARG_MANIFEST must have exactly the keys $EXPECTED_KEYS and resourceLimits $LIMIT_KEYS"
m() { jq -r "$1 | if type == \"string\" or type == \"number\" then tostring else error(\"bad type\") end" "$ARG_MANIFEST"; }
M_schema=$(m .schemaVersion) || die "$EX_USAGE" "schemaVersion has the wrong type"
M_id=$(m .experimentId) || die "$EX_USAGE" "experimentId has the wrong type"
M_env=$(m .environment) || die "$EX_USAGE" "environment has the wrong type"
M_region=$(m .region) || die "$EX_USAGE" "region has the wrong type"
M_kind=$(m .kind) || die "$EX_USAGE" "kind has the wrong type"
M_code=$(m .codeVersion) || die "$EX_USAGE" "codeVersion has the wrong type"
M_snapshot=$(m .datasetSnapshot) || die "$EX_USAGE" "datasetSnapshot has the wrong type"
M_cost=$(jq -r '.maxCostUsd | if type == "string" then . else error("bad") end' "$ARG_MANIFEST") ||
  die "$EX_USAGE" "maxCostUsd must be a decimal string"
M_minutes=$(m .maxDurationMinutes) || die "$EX_USAGE" "maxDurationMinutes has the wrong type"
M_prefix=$(m .metricsPrefix) || die "$EX_USAGE" "metricsPrefix has the wrong type"
M_emr=$(m .resourceLimits.maxEmrInstances) || die "$EX_USAGE" "resourceLimits.maxEmrInstances has the wrong type"
M_ecs=$(m .resourceLimits.maxEcsTasks) || die "$EX_USAGE" "resourceLimits.maxEcsTasks has the wrong type"
M_conc=$(m .resourceLimits.maxConcurrency) || die "$EX_USAGE" "resourceLimits.maxConcurrency has the wrong type"
M_req=$(m .resourceLimits.maxRequests) || die "$EX_USAGE" "resourceLimits.maxRequests has the wrong type"

[[ $M_schema == "$MANIFEST_SCHEMA" ]] || die "$EX_USAGE" "schemaVersion must be $MANIFEST_SCHEMA"
[[ $M_id =~ ^[a-z0-9][a-z0-9-]{0,62}$ ]] || die "$EX_USAGE" "experimentId is malformed"
[[ $M_env == "$ARG_ENV" ]] || die "$EX_REFUSED" "manifest environment '$M_env' differs from '$ARG_ENV'"
[[ $M_region == "$ENV_aws_region" ]] || die "$EX_REFUSED" "manifest region '$M_region' differs from env.conf $ENV_aws_region"
in_list "$M_kind" "$KINDS" || die "$EX_USAGE" "kind must be one of: $KINDS"
need_hex codeVersion "$M_code" 40
git cat-file -e "$M_code^{commit}" 2>/dev/null || die "$EX_REFUSED" "codeVersion $M_code is not a commit in this repository"
git merge-base --is-ancestor "$M_code" HEAD || die "$EX_REFUSED" "codeVersion is not an ancestor of HEAD"
[[ $M_snapshot =~ ^[A-Za-z0-9._=/-]{1,128}$ ]] || die "$EX_USAGE" "datasetSnapshot is malformed"
[[ $M_prefix =~ ^[A-Za-z0-9._=/-]{1,256}$ && /$M_prefix/ != */../* ]] || die "$EX_USAGE" "metricsPrefix is malformed"
is_decimal "$M_cost" || die "$EX_USAGE" "maxCostUsd is not a decimal"
dec_le "$M_cost" "$HC_MAX_COST_USD" || die "$EX_REFUSED" "maxCostUsd $M_cost exceeds the hard cap $HC_MAX_COST_USD"
need_int_range maxDurationMinutes "$M_minutes" 1 "$HC_MAX_DURATION_MINUTES"
need_int_range maxEmrInstances "$M_emr" 0 "$HC_MAX_EMR_INSTANCES"
need_int_range maxEcsTasks "$M_ecs" 0 "$HC_MAX_ECS_TASKS"
need_int_range maxConcurrency "$M_conc" 1 "$HC_MAX_CONCURRENCY"
need_int_range maxRequests "$M_req" 0 "$HC_MAX_REQUESTS"
MANIFEST_SHA256=$(sha256_file "$ARG_MANIFEST")
ok "manifest $ARG_MANIFEST sha256=$MANIFEST_SHA256"

bound() { say "BOUND $1=$2 cap=$3"; }
bound max_cost_usd "$M_cost" "$HC_MAX_COST_USD"
bound max_duration_minutes "$M_minutes" "$HC_MAX_DURATION_MINUTES"
bound max_emr_instances "$M_emr" "$HC_MAX_EMR_INSTANCES"
bound max_ecs_tasks "$M_ecs" "$HC_MAX_ECS_TASKS"
bound max_concurrency "$M_conc" "$HC_MAX_CONCURRENCY"
bound max_requests "$M_req" "$HC_MAX_REQUESTS"

# ---- planned actions (descriptive; nothing below is executed by this skeleton) ---------------------
plan_actions() {
  local n=0
  a() {
    n=$((n + 1))
    printf '[%s] PLAN-ACTION %02d %s\n' "$SCRIPT_NAME" "$n" "$*"
  }
  a "record run header: experimentId=$M_id commit=$HEAD_SHA codeVersion=$M_code manifest_sha256=$MANIFEST_SHA256 region=$ENV_aws_region start=<UTC>"
  case $M_kind in
    emr-baseline | emr-burst)
      a "submit the bounded Hadoop/Spark run through the approved run path (<= $M_emr EMR instances, <= $M_minutes min)"
      a "collect EMR step, task, shuffle and capacity metrics over time" ;;
    embedding-backlog)
      a "enqueue a controlled chunk count (<= $M_req messages); observe SQS depth and worker tasks (<= $M_ecs)"
      a "collect queue depth, desired/running tasks, retries and drain time" ;;
    query-load)
      a "drive the query API at concurrency levels up to $M_conc (<= $M_req requests in total, <= $M_minutes min)"
      a "collect p50/p95/p99 latency, throughput, error rate, ECS task count, CPU and memory" ;;
    retrieval-modes)
      a "run the benchmark queries in graph-only, vectors-only and fused modes (<= $M_req requests)"
      a "collect per-mode retrieval metrics" ;;
    failure-recovery)
      a "trigger one safe, pre-declared failure; observe retry, dead-letter, idempotency and recovery"
      a "collect execution state, retries and dead-letter counts" ;;
  esac
  a "write raw results create-once under $M_prefix (never overwritten); record end=<UTC> and run IDs"
}
step "planned bounded actions"
plan_actions

MATERIAL="$SCRIPT_NAME|env=$ARG_ENV|account=$ENV_aws_account_id|region=$ENV_aws_region|head=$HEAD_SHA"
MATERIAL="$MATERIAL|manifest=$ARG_MANIFEST|sha256=$MANIFEST_SHA256|kind=$M_kind|cost=$M_cost|minutes=$M_minutes"
MATERIAL="$MATERIAL|emr=$M_emr|ecs=$M_ecs|concurrency=$M_conc|requests=$M_req"
TOKEN="rex-$(sha256_text "$MATERIAL" | cut -c1-32)"
confirm_block() {
  say "CONFIRM environment=$ARG_ENV account=$ENV_aws_account_id region=$ENV_aws_region"
  say "CONFIRM experiment=$M_id kind=$M_kind manifest=$ARG_MANIFEST sha256=$MANIFEST_SHA256"
  say "CONFIRM git_head=$HEAD_SHA code_version=$M_code dataset_snapshot=$M_snapshot metrics_prefix=$M_prefix"
  say "CONFIRM max_cost_usd=$M_cost max_duration_minutes=$M_minutes"
}

if [[ $MODE == dry-run ]]; then
  confirm_block
  say "CONFIRMATION-TOKEN execute $TOKEN"
  say "to execute: review every CONFIRM, BOUND and PLAN-ACTION line, then set $CONFIRM_ENV_VAR=<token> in your own terminal"
  say "RESULT DRY-RUN-OK"
  exit 0
fi

[[ $SCHOLARCHAIN_CONFIRM_EXPERIMENT == "$TOKEN" ]] ||
  die "$EX_REFUSED" "$CONFIRM_ENV_VAR does not hold the token for these inputs; run the dry run (unset $CONFIRM_ENV_VAR) and review it"
ok "confirmation token matches"
confirm_block

# ---- workloads (not implemented) -------------------------------------------------------------------
# TODO(IE-P13 follow-up, per kind): each workload must use only approved, committed scripts and
# bounded configuration; record start/end UTC, run IDs and metric locations; write raw results
# create-once; and enforce maxDurationMinutes with a cluster- or service-side bound, not only a local
# timer. Until then execution refuses here, before any AWS call.
case $M_kind in
  emr-baseline | emr-burst) die "$EX_REFUSED" "workload $M_kind is not implemented (needs the approved bounded Hadoop/ER/Spark chain; see offline-status.md blocker 3)" ;;
  embedding-backlog) die "$EX_REFUSED" "workload $M_kind is not implemented (needs the embedding queue and workers deployed)" ;;
  query-load) die "$EX_REFUSED" "workload $M_kind is not implemented (needs a reachable service endpoint and a load driver under load-tests/)" ;;
  retrieval-modes) die "$EX_REFUSED" "workload $M_kind is not implemented (needs the benchmark-query suite)" ;;
  failure-recovery) die "$EX_REFUSED" "workload $M_kind is not implemented (needs a reviewed, pre-declared safe failure)" ;;
esac
die "$EX_REFUSED" "internal: unknown kind $M_kind"
