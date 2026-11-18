#!/usr/bin/env bash
# =====================================================================================================
# scripts/plan.sh -- non-destructive Terraform plan of one stack. NEVER applies.
# Owner: infra-engineer. Work order IE-P6-02 (lifecycle L2).
#
# USAGE   scripts/plan.sh <environment> <bootstrap|base|emr-run>
#
# Runs from the repository root on a clean tree, with the READ-ONLY profile of env.conf
# (aws_readonly_profile); the one first-plan exception for bootstrap and base is described at the
# profile selection below (human Terminal only, refused once the read-only role exists). Steps: terraform init (backend from env.conf, provider lock file read-only),
# validate, plan -input=false -lock=false -out=tfplan, then prints the add/change/destroy/replace counts,
# stateful, security and cost-relevant changes, the plan sha256 and the deploy command. -lock=false:
# the read-only profile cannot write the S3 lock object; a plan made stale by a concurrent apply is
# refused by terraform at apply time. Writes only <stack>/tfplan and <stack>/.terraform/ (gitignored),
# including .terraform/scholarchain-plan.meta, which scripts/deploy.sh checks.
#
# MARKERS  [plan] STEP / CHECK ok / PLAN-CHANGE / PLAN-STATEFUL / PLAN-SECURITY / PLAN-COST /
#          PLAN-COUNTS / PLAN-SHA256 / RESULT PLAN-OK; [plan] FAIL exit=<n> on stderr.
# EXIT     0 ok, 2 usage, 3 refused (dirty tree, placeholder, init/validate/plan failure)
# =====================================================================================================
set -euo pipefail
readonly SCRIPT_NAME=plan
# shellcheck source=scripts/lib/common.sh disable=SC1091
. "$(dirname "$0")/lib/common.sh"

(($# == 2)) || die "$EX_USAGE" "usage: scripts/plan.sh <environment> <bootstrap|base|emr-run>"
ARG_ENV=$1
ARG_STACK=$2
is_env_name "$ARG_ENV" || die "$EX_USAGE" "environment '$ARG_ENV' is not a short lowercase name"
need_tools git terraform jq awk shasum

step "start env=$ARG_ENV stack=$ARG_STACK at=$(now_utc)"
require_repo_root
require_clean_tree
load_env_conf "$ARG_ENV"
DIR=$(stack_dir "$ARG_ENV" "$ARG_STACK")
[[ -d $DIR ]] || die "$EX_USAGE" "no stack directory $DIR"
[[ -n $(git ls-files -- "$DIR") ]] || die "$EX_REFUSED" "$DIR is not committed"

# Profile selection. Default, and always for emr-run: the read-only profile (role
# scholarchain-<env>-readonly, created by base). First-plan exception (chicken and egg): before base
# exists, bootstrap and base can only be planned with the deploy profile (the human's `aws login`
# user). That exception needs ALL of: SCHOLARCHAIN_PLAN_FIRST_WITH_DEPLOY_PROFILE=<stack> set by the
# human for this stack; not inside a Claude Code session; stdin and stdout a terminal; and the deploy
# profile's `iam get-role` reporting the read-only role absent (NoSuchEntity). Once base has created
# the role, the exception refuses, so the read-only default cannot be bypassed afterwards.
PLAN_PROFILE=$ENV_aws_readonly_profile
PLAN_PROFILE_KIND="readonly"
if [[ -n ${SCHOLARCHAIN_PLAN_FIRST_WITH_DEPLOY_PROFILE:-} ]]; then
  [[ $SCHOLARCHAIN_PLAN_FIRST_WITH_DEPLOY_PROFILE == "$ARG_STACK" ]] ||
    die "$EX_REFUSED" "SCHOLARCHAIN_PLAN_FIRST_WITH_DEPLOY_PROFILE must name this stack ($ARG_STACK)"
  [[ $ARG_STACK == bootstrap || $ARG_STACK == base ]] || die "$EX_REFUSED" "the deploy-profile exception covers bootstrap and base only"
  [[ -z ${CLAUDECODE:-} ]] || die "$EX_REFUSED" "the deploy-profile exception is human-only: refusing inside a Claude Code session"
  [[ -t 0 && -t 1 ]] || die "$EX_REFUSED" "the deploy-profile exception needs a terminal (stdin and stdout a TTY)"
  need_tools aws
  RO_ROLE="scholarchain-$ARG_ENV-readonly"
  if GR_ERR=$(aws iam get-role --role-name "$RO_ROLE" --profile "$ENV_aws_deploy_profile" --region "$ENV_aws_region" \
    --output json 2>&1 >/dev/null); then
    die "$EX_REFUSED" "role $RO_ROLE exists: plan with the read-only profile $ENV_aws_readonly_profile (unset SCHOLARCHAIN_PLAN_FIRST_WITH_DEPLOY_PROFILE)"
  fi
  [[ $GR_ERR == *NoSuchEntity* ]] || die "$EX_REFUSED" "could not establish that $RO_ROLE is absent: $GR_ERR"
  PLAN_PROFILE=$ENV_aws_deploy_profile
  PLAN_PROFILE_KIND=deploy-first-plan
  note "first plan of $ARG_STACK with the deploy profile $PLAN_PROFILE: role $RO_ROLE does not exist yet"
fi
tf_env "$PLAN_PROFILE"
ok "profile $PLAN_PROFILE ($PLAN_PROFILE_KIND), Region $ENV_aws_region"

step "terraform init"
tf_init "$ARG_STACK" "$DIR"
step "terraform validate"
terraform -chdir="$DIR" validate -no-color || die "$EX_REFUSED" "terraform validate failed in $DIR"
step "terraform plan (no apply)"
rm -f "$DIR/tfplan" "$DIR/.terraform/scholarchain-plan.meta"
terraform -chdir="$DIR" plan -input=false -lock=false -no-color -out=tfplan ||
  die "$EX_REFUSED" "terraform plan failed in $DIR"

step "plan summary"
plan_summary "$DIR" tfplan
PLAN_SHA256=$(sha256_file "$DIR/tfplan")
{
  printf 'environment=%s\nstack=%s\nhead=%s\nplan_sha256=%s\n' "$ARG_ENV" "$ARG_STACK" "$HEAD_SHA" "$PLAN_SHA256"
  printf 'account=%s\nregion=%s\ncreated_at=%s\nplan_profile=%s\n' "$ENV_aws_account_id" "$ENV_aws_region" "$(now_utc)" "$PLAN_PROFILE_KIND"
  printf 'add=%s\nchange=%s\ndestroy=%s\nreplace=%s\n' "$PLAN_ADD" "$PLAN_CHANGE" "$PLAN_DESTROY" "$PLAN_REPLACE"
} >"$DIR/.terraform/scholarchain-plan.meta"
say "PLAN-SHA256 $PLAN_SHA256 $DIR/tfplan"
say "to apply after human approval of this checksum: scripts/deploy.sh $ARG_ENV $HEAD_SHA $PLAN_SHA256"
say "RESULT PLAN-OK"
