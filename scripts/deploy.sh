#!/usr/bin/env bash
# =====================================================================================================
# scripts/deploy.sh -- apply exactly one human-approved saved Terraform plan.
# Owner: infra-engineer (author). Operator: the human only, in a Terminal, with reviewed commands
# (decision D1, chosen by the human; CR-P6-31 n1). The script refuses inside Claude Code and without a
# TTY, so the deploy-engineer allowlist row cannot run it.
#
# USAGE   scripts/deploy.sh <environment> <commit sha40> <plan sha256>
#
# Gates, in order: repository root; clean tree; HEAD equals <commit sha40>; env.conf committed and
# complete, and the human-local env.local.conf (account ID, admin_principal_arn, API ingress CIDRs;
# gitignored, never committed) present with no dummy value; exactly one stack of <environment> (bootstrap, base, emr-run, runtime) holds a tfplan whose sha256
# equals <plan sha256>; its .terraform/scholarchain-plan.meta names the same environment, stack, HEAD,
# checksum, account and Region (written by scripts/plan.sh); the plan contains no delete or replace, except a replace or
# delete of aws_emr_cluster in emr-run (a terminated cluster is re-created); the deploy profile
# (aws_deploy_profile, the human's IAM user via `aws login`) is in the configured account and is
# exactly admin_principal_arn; for emr-run only, the account's applied EC2 quota L-1216C47A (Running
# On-Demand Standard instances, vCPUs) is at least EMR_RUN_REQUIRED_VCPUS. Then
# `terraform apply -input=false tfplan` (a saved plan: terraform itself refuses a stale plan), prints
# the outputs, and removes the consumed plan and its meta file. Destroy plans go through
# scripts/cleanup.sh (human only), never through this script.
# Refuses first of all when CLAUDECODE is set or stdin/stdout is not a TTY (human Terminal only).
#
# MARKERS  [deploy] STEP / CHECK ok / PLAN-CHANGE / PLAN-COUNTS / RESULT APPLIED; FAIL on stderr.
# EXIT     0 applied, 2 usage, 3 refused by a gate, 4 AWS identity mismatch, 5 apply failed
# =====================================================================================================
set -euo pipefail
readonly SCRIPT_NAME=deploy
# shellcheck source=scripts/lib/common.sh disable=SC1091
. "$(dirname "$0")/lib/common.sh"
# emr-run cluster shape: 1 primary + 1 core m5.xlarge (4 vCPU each) = 8 vCPU; equals HC_MAX_INSTANCES=2
# of scripts/run-bounded-spark.sh times 4 (m6i.xlarge, the other allowed type, also has 4 vCPU).
# Changing the shape is a reviewed change to this value, emr-run/main.tf and run-bounded-spark.sh.
readonly EMR_RUN_REQUIRED_VCPUS=8
readonly EC2_STANDARD_VCPU_QUOTA_CODE=L-1216C47A

# Human Terminal only, before any other action (CR-P6-30 m3; mirrors cleanup.sh): opaque code run by an
# agent must not reach `terraform apply` non-interactively with the human's `aws login` session.
[[ -z ${CLAUDECODE:-} ]] || die "$EX_REFUSED" "deploy is human-operated: refusing inside a Claude Code session"
[[ -t 0 && -t 1 ]] || die "$EX_REFUSED" "deploy is human-operated: run it in a terminal (stdin and stdout must be a TTY)"

(($# == 3)) || die "$EX_USAGE" "usage: scripts/deploy.sh <environment> <commit sha40> <plan sha256>"
ARG_ENV=$1
ARG_SHA=$2
ARG_PLAN=$3
is_env_name "$ARG_ENV" || die "$EX_USAGE" "environment '$ARG_ENV' is not a short lowercase name"
need_hex "commit" "$ARG_SHA" 40
need_hex "plan checksum" "$ARG_PLAN" 64
need_tools git terraform jq awk shasum aws

step "start env=$ARG_ENV commit=$ARG_SHA plan=$ARG_PLAN at=$(now_utc)"
require_repo_root
require_clean_tree
[[ $HEAD_SHA == "$ARG_SHA" ]] || die "$EX_REFUSED" "HEAD $HEAD_SHA differs from the approved commit $ARG_SHA"
load_env_conf "$ARG_ENV"

step "locate the approved plan"
STACK=""
for s in bootstrap base emr-run runtime; do
  d=$(stack_dir "$ARG_ENV" "$s")
  if [[ -f $d/tfplan && ! -L $d/tfplan && $(sha256_file "$d/tfplan") == "$ARG_PLAN" ]]; then
    [[ -z $STACK ]] || die "$EX_REFUSED" "more than one stack holds a plan with this checksum"
    STACK=$s
  fi
done
[[ -n $STACK ]] || die "$EX_REFUSED" "no stack of $ARG_ENV holds a tfplan with sha256 $ARG_PLAN (run scripts/plan.sh)"
DIR=$(stack_dir "$ARG_ENV" "$STACK")
META="$DIR/.terraform/scholarchain-plan.meta"
[[ -f $META ]] || die "$EX_REFUSED" "$META is missing (the plan was not made by scripts/plan.sh)"
for kv in "environment=$ARG_ENV" "stack=$STACK" "head=$HEAD_SHA" "plan_sha256=$ARG_PLAN" \
  "account=$ENV_aws_account_id" "region=$ENV_aws_region"; do
  grep -qxF -- "$kv" "$META" || die "$EX_REFUSED" "$META does not record $kv (plan made at another commit or for another target)"
done
ok "plan $DIR/tfplan matches checksum and meta"

tf_env "$ENV_aws_deploy_profile"
step "plan review"
plan_summary "$DIR" tfplan
if ((PLAN_DESTROY + PLAN_REPLACE > 0)); then
  [[ $STACK == emr-run && $PLAN_DELETED_TYPES == "aws_emr_cluster " ]] ||
    die "$EX_REFUSED" "plan deletes or replaces [$PLAN_DELETED_TYPES] in $STACK; destroy only through scripts/cleanup.sh"
  note "plan replaces the transient EMR cluster only"
fi

if [[ $STACK == runtime ]]; then require_runtime_approval; fi

step "aws identity"
ID_JSON=$(aws sts get-caller-identity --profile "$ENV_aws_deploy_profile" --region "$ENV_aws_region" \
  --output json) || die "$EX_AWS" "sts get-caller-identity failed for $ENV_aws_deploy_profile (aws login --profile scholarchain-login?)"
ACCOUNT=$(printf '%s' "$ID_JSON" | jq -r .Account)
[[ $ACCOUNT == "$ENV_aws_account_id" ]] || die "$EX_AWS" "profile $ENV_aws_deploy_profile is in account $ACCOUNT, expected $ENV_aws_account_id"
[[ $(printf '%s' "$ID_JSON" | jq -r .Arn) == "$ENV_admin_principal_arn" ]] ||
  die "$EX_AWS" "profile $ENV_aws_deploy_profile is not admin_principal_arn $ENV_admin_principal_arn"
ok "account $ACCOUNT, profile $ENV_aws_deploy_profile"

if [[ $STACK == emr-run ]]; then
  # Read-only Service Quotas call. A quota below the cluster's vCPUs makes EMR fail provisioning
  # (instances cannot launch) after the KMS endpoint already bills, so refuse before apply. Other
  # running On-Demand Standard instances in the Region also consume this quota; they are not counted
  # here (the account is expected to run none).
  step "EC2 vCPU quota $EC2_STANDARD_VCPU_QUOTA_CODE (need >= $EMR_RUN_REQUIRED_VCPUS)"
  Q_JSON=$(aws service-quotas get-service-quota --service-code ec2 --quota-code "$EC2_STANDARD_VCPU_QUOTA_CODE" \
    --profile "$ENV_aws_deploy_profile" --region "$ENV_aws_region" --output json) ||
    die "$EX_AWS" "service-quotas get-service-quota $EC2_STANDARD_VCPU_QUOTA_CODE failed; check the quota in the Service Quotas console"
  QUOTA=$(printf '%s' "$Q_JSON" | jq -r '.Quota.Value // empty')
  [[ $QUOTA =~ ^[0-9]+(\.[0-9]+)?$ ]] || die "$EX_AWS" "quota $EC2_STANDARD_VCPU_QUOTA_CODE has no numeric value ('$QUOTA')"
  dec_le "$EMR_RUN_REQUIRED_VCPUS" "$QUOTA" ||
    die "$EX_REFUSED" "quota $EC2_STANDARD_VCPU_QUOTA_CODE is $QUOTA vCPU in $ENV_aws_region, below the $EMR_RUN_REQUIRED_VCPUS the emr-run cluster needs; wait for the increase request to be approved"
  ok "quota $EC2_STANDARD_VCPU_QUOTA_CODE = $QUOTA vCPU >= $EMR_RUN_REQUIRED_VCPUS"
fi

if [[ $STACK == bootstrap ]]; then
  STATE_FILE="$HOME/$ENV_bootstrap_state_home_relative"
  mkdir -p "$(dirname "$STATE_FILE")"
  note "bootstrap state file (outside the repository): $STATE_FILE"
fi

step "terraform apply of the saved plan ($STACK)"
if ! terraform -chdir="$DIR" apply -input=false -no-color tfplan; then
  die "$EX_RUN" "terraform apply failed in $DIR; inspect the output above, then plan again"
fi
rm -f "$DIR/tfplan" "$META"
terraform -chdir="$DIR" output -no-color || true
say "RESULT APPLIED env=$ARG_ENV stack=$STACK commit=$HEAD_SHA plan=$ARG_PLAN at=$(now_utc)"
