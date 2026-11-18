# shellcheck shell=bash
# shellcheck disable=SC2154,SC2034 # ENV_* are assigned by parse_kv (printf -v); EX_* and PLAN_* are read by callers
# scripts/lib/common.sh -- helpers shared by plan.sh, deploy.sh, cleanup.sh, run-bounded-spark.sh.
# Owner: infra-engineer. Sourced (never executed); the caller sets SCRIPT_NAME and `set -euo pipefail`.
# Bash 3.2 compatible (no associative arrays, no mapfile). Never evaluates configuration.

readonly EX_USAGE=2
readonly EX_REFUSED=3
readonly EX_AWS=4
readonly EX_RUN=5
readonly EX_VERIFY=6

say() { printf '[%s] %s\n' "$SCRIPT_NAME" "$*"; }
step() { say "STEP $*"; }
ok() { say "CHECK ok $*"; }
note() { say "NOTE $*"; }
die() {
  local code=$1
  shift
  printf '[%s] FAIL exit=%s %s\n' "$SCRIPT_NAME" "$code" "$*" >&2
  exit "$code"
}
now_utc() { date -u +%Y-%m-%dT%H:%M:%SZ; }

in_list() { # in_list <word> <space-separated list>
  local w=$1 x
  for x in $2; do
    if [[ $x == "$w" ]]; then return 0; fi
  done
  return 1
}
is_uint() { [[ $1 =~ ^(0|[1-9][0-9]{0,14})$ ]]; }
need_int_range() { # need_int_range <name> <value> <min> <max>
  is_uint "$2" || die "$EX_USAGE" "$1='$2' is not a non-negative integer"
  if (($2 < $3 || $2 > $4)); then die "$EX_REFUSED" "$1=$2 is outside [$3, $4]"; fi
}
is_decimal() { [[ $1 =~ ^(0|[1-9][0-9]{0,6})(\.[0-9]{1,4})?$ ]]; }
dec_le() { awk -v a="$1" -v b="$2" 'BEGIN { exit !(a + 0 <= b + 0) }'; }
is_hex() { [[ $1 =~ ^[0-9a-f]+$ ]] && ((${#1} == $2)); }
need_hex() { is_hex "$2" "$3" || die "$EX_USAGE" "$1 must be $3 lowercase hex characters"; }
is_env_name() { [[ $1 =~ ^[a-z][a-z0-9-]{1,15}$ ]]; }
is_repo_path() { [[ $1 =~ ^[A-Za-z0-9._=-]+(/[A-Za-z0-9._=-]+)*$ ]] && [[ /$1/ != */../* && /$1/ != */./* ]]; }

digest_file() { # digest_file <256|512|1> <file>
  if [[ $1 == 256 ]] && command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$2" | awk '{ print $1 }'
  elif [[ $1 == 512 ]] && command -v sha512sum >/dev/null 2>&1; then
    sha512sum "$2" | awk '{ print $1 }'
  elif [[ $1 == 1 ]] && command -v sha1sum >/dev/null 2>&1; then
    sha1sum "$2" | awk '{ print $1 }'
  else
    shasum -a "$1" "$2" | awk '{ print $1 }'
  fi
}
sha256_file() { digest_file 256 "$1"; }
sha256_text() {
  if command -v sha256sum >/dev/null 2>&1; then
    printf '%s' "$1" | sha256sum | awk '{ print $1 }'
  else
    printf '%s' "$1" | shasum -a 256 | awk '{ print $1 }'
  fi
}
hex_to_b64() { printf '%s' "$1" | xxd -r -p | base64; } # S3 ChecksumSHA256 form
file_bytes() { wc -c <"$1" | awk '{ print $1 }'; }

need_tools() {
  local t
  for t in "$@"; do
    command -v "$t" >/dev/null 2>&1 || die "$EX_USAGE" "required tool '$t' is not installed"
  done
}

# Repository gates. Sets REPO_ROOT and HEAD_SHA.
require_repo_root() {
  REPO_ROOT=$(git rev-parse --show-toplevel 2>/dev/null) || die "$EX_USAGE" "not inside a git work tree"
  [[ $(pwd -P) == "$(cd "$REPO_ROOT" && pwd -P)" ]] || die "$EX_USAGE" "run from the repository root ($REPO_ROOT)"
  HEAD_SHA=$(git rev-parse --verify HEAD) || die "$EX_USAGE" "no HEAD commit"
  ok "head $HEAD_SHA"
}
require_clean_tree() {
  local dirty
  dirty=$(git status --porcelain --untracked-files=all)
  if [[ -n $dirty ]]; then
    printf '%s\n' "$dirty" | head -n 50 >&2 || true
    die "$EX_REFUSED" "working tree is not clean (git status --porcelain above); commit or remove the changes"
  fi
  ok "clean tree"
}
require_tracked() { # require_tracked <label> <path>
  [[ -f $2 && ! -L $2 ]] || die "$EX_USAGE" "$1 '$2' is not a regular file"
  git ls-files --error-unmatch -- "$2" >/dev/null 2>&1 || die "$EX_REFUSED" "$1 '$2' is not committed"
  if ! git diff --quiet HEAD -- "$2"; then die "$EX_REFUSED" "$1 '$2' differs from HEAD"; fi
}

# parse_kv <file> <space-separated keys> <variable prefix> <extra value characters>
# key=value lines; '#' comments and blank lines ignored; every key exactly once; no defaults;
# REPLACE-ME refused. Values become shell variables <prefix><key> (printf -v, never eval).
parse_kv() {
  local file=$1 keys=$2 prefix=$3 extra=$4 seen=" " n=0 line key value
  while IFS= read -r line || [[ -n $line ]]; do
    n=$((n + 1))
    [[ -z $line || $line == \#* ]] && continue
    [[ $line =~ ^([a-z0-9_]+)=(.*)$ ]] || die "$EX_USAGE" "$file:$n is not key=value"
    key=${BASH_REMATCH[1]}
    value=${BASH_REMATCH[2]}
    [[ $value =~ ^[A-Za-z0-9._/${extra}-]*$ ]] || die "$EX_USAGE" "$file:$n '$key' has characters outside the allowed set"
    in_list "$key" "$keys" || die "$EX_USAGE" "$file:$n unknown key '$key'"
    [[ $seen == *" $key "* ]] && die "$EX_USAGE" "$file:$n repeats key '$key'"
    [[ -n $value ]] || die "$EX_USAGE" "$file:$n '$key' is empty (no defaults)"
    [[ $value == *REPLACE-ME* ]] && die "$EX_REFUSED" "$file:$n '$key' is still a REPLACE-ME placeholder"
    seen="$seen$key "
    printf -v "$prefix$key" '%s' "$value"
  done <"$file"
  for key in $keys; do
    [[ $seen == *" $key "* ]] || die "$EX_USAGE" "$file lacks required key '$key' (no defaults)"
  done
}

# Environment configuration (infra/terraform/environments/<env>/env.conf). Sets ENV_* variables.
readonly ENV_KEYS="config_version environment aws_account_id aws_region availability_zone_id \
aws_readonly_profile aws_deploy_profile aws_operator_profile admin_principal_arn \
bootstrap_state_home_relative course project owner expiration"
load_env_conf() { # load_env_conf <env> [allow-expired]   (allow-expired: cleanup.sh only)
  local f="infra/terraform/environments/$1/env.conf" allow_expired=${2:-}
  [[ -z $allow_expired || $allow_expired == allow-expired ]] || die "$EX_USAGE" "load_env_conf: unknown option '$allow_expired'"
  require_tracked "environment config" "$f"
  parse_kv "$f" "$ENV_KEYS" ENV_ ":"
  [[ $ENV_config_version == scholarchain.environment-config.v2 ]] || die "$EX_USAGE" "$f: config_version must be scholarchain.environment-config.v2"
  [[ $ENV_environment == "$1" ]] || die "$EX_REFUSED" "$f: environment '$ENV_environment' differs from '$1'"
  [[ $ENV_aws_account_id =~ ^[0-9]{12}$ ]] || die "$EX_USAGE" "$f: aws_account_id is not 12 digits"
  [[ $ENV_admin_principal_arn =~ ^arn:aws:iam::[0-9]{12}:user/[A-Za-z0-9._-]{1,64}$ ]] ||
    die "$EX_USAGE" "$f: admin_principal_arn is not an IAM user ARN arn:aws:iam::<account>:user/<name>"
  [[ $ENV_admin_principal_arn == "arn:aws:iam::$ENV_aws_account_id:user/"* ]] || die "$EX_REFUSED" "$f: admin_principal_arn is not in account $ENV_aws_account_id"
  local k v
  for k in aws_readonly_profile aws_deploy_profile aws_operator_profile; do
    v="ENV_$k"
    [[ ${!v} =~ ^[A-Za-z0-9._-]+$ ]] || die "$EX_USAGE" "$f: $k is not a plain profile name"
  done
  # The read-only profile is pinned (CR-P6-30 m4): agent plan.sh runs and the guard's <RO> profile use
  # it, so it may never name the deploy (admin) or operator profile.
  [[ $ENV_aws_readonly_profile == scholarchain-readonly ]] || die "$EX_REFUSED" "$f: aws_readonly_profile must be exactly scholarchain-readonly"
  [[ $ENV_aws_readonly_profile != "$ENV_aws_deploy_profile" ]] || die "$EX_REFUSED" "$f: the read-only profile must differ from the deploy profile"
  [[ $ENV_aws_readonly_profile != "$ENV_aws_operator_profile" ]] || die "$EX_REFUSED" "$f: the read-only profile must differ from the operator profile"
  [[ $ENV_aws_region =~ ^[a-z]{2}(-[a-z]+)+-[0-9]$ ]] || die "$EX_USAGE" "$f: aws_region is not a Region code"
  [[ $ENV_expiration =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || die "$EX_USAGE" "$f: expiration is not YYYY-MM-DD"
  # An expired environment may not be planned, deployed or run, but must stay removable (IE-P6-03).
  if [[ $ENV_expiration < $(date -u +%Y-%m-%d) ]]; then
    [[ $allow_expired == allow-expired ]] || die "$EX_REFUSED" "$f: expiration $ENV_expiration is in the past"
    note "$f: expiration $ENV_expiration is in the past; allowed for teardown only"
  fi
  is_repo_path "$ENV_bootstrap_state_home_relative" || die "$EX_USAGE" "$f: bootstrap_state_home_relative is not a plain relative path"
  STATE_BUCKET="scholarchain-tfstate-$ENV_aws_account_id-$ENV_aws_region"
  ok "environment config $f (account $ENV_aws_account_id, Region $ENV_aws_region)"
}

# stack_dir <env> <bootstrap|base|emr-run>: prints the stack directory.
stack_dir() {
  case $2 in
    bootstrap) printf '%s' "infra/terraform/bootstrap" ;;
    base | emr-run) printf '%s' "infra/terraform/environments/$1/$2" ;;
    *) die "$EX_USAGE" "stack must be bootstrap, base or emr-run" ;;
  esac
}

# Terraform environment for one AWS profile: no static credentials, no prompts, TF_VAR_* from env.conf.
tf_env() { # tf_env <aws profile>
  unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN AWS_SECURITY_TOKEN
  export AWS_PROFILE=$1 AWS_REGION=$ENV_aws_region TF_IN_AUTOMATION=1 TF_INPUT=0
  export TF_VAR_aws_account_id=$ENV_aws_account_id TF_VAR_aws_region=$ENV_aws_region
  export TF_VAR_availability_zone_id=$ENV_availability_zone_id TF_VAR_environment=$ENV_environment
  export TF_VAR_admin_principal_arn=$ENV_admin_principal_arn TF_VAR_state_bucket=$STATE_BUCKET
  export TF_VAR_course=$ENV_course TF_VAR_project=$ENV_project TF_VAR_owner=$ENV_owner TF_VAR_expiration=$ENV_expiration
}

# tf_init <stack> <stack dir>: init with the derived backend configuration, lock file read-only.
tf_init() {
  local backend
  if [[ $1 == bootstrap ]]; then
    backend=(-backend-config="path=$HOME/$ENV_bootstrap_state_home_relative")
  else
    backend=(-backend-config="bucket=$STATE_BUCKET" -backend-config="region=$ENV_aws_region")
  fi
  require_tracked "provider lock file" "$2/.terraform.lock.hcl"
  terraform -chdir="$2" init -input=false -reconfigure -lockfile=readonly -no-color "${backend[@]}" >&2 ||
    die "$EX_REFUSED" "terraform init failed in $2"
  ok "terraform init $2"
}

# plan_summary <stack dir> <plan file>: prints counts and notable changes; sets PLAN_ADD PLAN_CHANGE
# PLAN_DESTROY PLAN_REPLACE and PLAN_DELETED_TYPES. Reads `terraform show -json` into jq only; no value
# is printed (addresses, types and actions only).
plan_summary() {
  local json rows
  json=$(terraform -chdir="$1" show -json "$2") || die "$EX_REFUSED" "terraform show failed for $1/$2"
  rows=$(printf '%s' "$json" | jq -r '.resource_changes[]? | select(.change.actions != ["no-op"] and .change.actions != ["read"])
    | [(.change.actions | join("+")), .type, .address] | @tsv') || die "$EX_REFUSED" "could not parse the plan"
  PLAN_ADD=$(printf '%s\n' "$rows" | awk -F'\t' '$1 == "create"' | grep -c . || true)
  PLAN_CHANGE=$(printf '%s\n' "$rows" | awk -F'\t' '$1 == "update"' | grep -c . || true)
  PLAN_DESTROY=$(printf '%s\n' "$rows" | awk -F'\t' '$1 == "delete"' | grep -c . || true)
  PLAN_REPLACE=$(printf '%s\n' "$rows" | awk -F'\t' '$1 == "delete+create" || $1 == "create+delete"' | grep -c . || true)
  PLAN_DELETED_TYPES=$(printf '%s\n' "$rows" | awk -F'\t' '$1 ~ /delete/ { print $2 }' | LC_ALL=C sort -u | tr '\n' ' ')
  printf '%s\n' "$rows" | awk -F'\t' 'NF == 3 { printf "[%s] PLAN-CHANGE %s %s %s\n", s, $1, $2, $3 }' s="$SCRIPT_NAME"
  printf '%s\n' "$rows" | awk -F'\t' 'NF == 3 && $1 ~ /delete/ && ($2 ~ /^aws_s3_bucket$|^aws_kms_key$|^aws_s3_bucket_versioning$/) { printf "[%s] PLAN-STATEFUL %s %s\n", s, $1, $3 }' s="$SCRIPT_NAME"
  printf '%s\n' "$rows" | awk -F'\t' 'NF == 3 && $2 ~ /^aws_iam_|^aws_kms_|_policy$|^aws_security_group$|^aws_vpc_security_group_|^aws_default_security_group$/ { printf "[%s] PLAN-SECURITY %s %s\n", s, $1, $3 }' s="$SCRIPT_NAME"
  printf '%s\n' "$rows" | awk -F'\t' 'NF == 3 && $2 ~ /^aws_emr_cluster$|^aws_nat_gateway$|^aws_eip$|^aws_instance$|^aws_vpc_endpoint$/ { printf "[%s] PLAN-COST %s %s\n", s, $1, $3 }' s="$SCRIPT_NAME"
  say "PLAN-COUNTS add=$PLAN_ADD change=$PLAN_CHANGE destroy=$PLAN_DESTROY replace=$PLAN_REPLACE"
}
