# shellcheck shell=bash
# shellcheck disable=SC2154,SC2034 # ENV_* and ENVL_* are assigned by parse_kv (printf -v); EX_* and PLAN_* are read by callers
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

# parse_kv <file> <space-separated keys> <variable prefix> <extra value characters> [ignored keys]
# key=value lines; '#' comments and blank lines ignored; every key exactly once; no defaults;
# REPLACE-ME refused. Values become shell variables <prefix><key> (printf -v, never eval).
# A line whose key is in [ignored keys] is skipped unread: neither required, checked nor assigned.
parse_kv() {
  local file=$1 keys=$2 prefix=$3 extra=$4 ignored=${5:-} seen=" " n=0 line key value
  while IFS= read -r line || [[ -n $line ]]; do
    n=$((n + 1))
    [[ -z $line || $line == \#* ]] && continue
    [[ $line =~ ^([a-z0-9_]+)=(.*)$ ]] || die "$EX_USAGE" "$file:$n is not key=value"
    key=${BASH_REMATCH[1]}
    if [[ -n $ignored ]] && in_list "$key" "$ignored"; then continue; fi
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

# Environment configuration, two files (human decision 2026-11-24, IE-P13-02). Sets ENV_* variables.
#   infra/terraform/environments/<env>/env.conf        committed, unmodified; non-identifying values only
#   infra/terraform/environments/<env>/env.local.conf  human-local, NEVER committed (gitignored by
#       /infra/terraform/environments/*/env.local.conf): account ID, the admin IAM user ARN, the API
#       ingress CIDRs and the optional API certificate ARN. Template: env.local.conf.example (dummy
#       REPLACE-ME values). Parsed like env.conf (never sourced). The CHECK lines mask the account ID;
#       refusal messages and derived bucket names (and Terraform's own plan output) still show it.
readonly ENV_KEYS="config_version environment aws_region availability_zone_id \
aws_readonly_profile aws_deploy_profile aws_operator_profile \
bootstrap_state_home_relative course project owner expiration"
readonly ENV_LOCAL_KEYS="config_version environment aws_account_id admin_principal_arn \
api_ingress_cidrs api_certificate_arn"
# Values that only a template or documentation would hold (refused as "still dummy").
readonly ENV_LOCAL_DUMMY_ACCOUNTS="000000000000 111111111111 123456789012 999999999999"

# is_narrow_cidr <cidr>: canonical IPv4 CIDR, prefix /24../32, not TEST-NET (RFC 5737) or 0.0.0.0.
is_narrow_cidr() {
  local c=$1 ip len o1 o2 o3 o4 n mask
  [[ $c =~ ^(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])(\.(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])){3}/(2[4-9]|3[0-2])$ ]] || return 1
  ip=${c%/*}
  len=${c#*/}
  IFS=. read -r o1 o2 o3 o4 <<<"$ip"
  n=$(((o1 << 24) | (o2 << 16) | (o3 << 8) | o4))
  mask=$(((0xFFFFFFFF << (32 - len)) & 0xFFFFFFFF))
  (((n & mask) == n)) || return 1 # host bits set: not canonical
  ((o1 != 0)) || return 1
  [[ $ip != 192.0.2.* && $ip != 198.51.100.* && $ip != 203.0.113.* ]] || return 1
  return 0
}

# Keys of env.local.conf that teardown (allow-expired) neither needs nor validates (CR-P13-02 m-B).
readonly ENV_LOCAL_API_KEYS="api_ingress_cidrs api_certificate_arn"
# Teardown-only values for the runtime stack's two required API variables. They pass the stack's
# variable validation and only let a DESTROY plan evaluate; a destroy plan creates no listener or
# ingress rule, so they never reach AWS. Never used outside allow-expired mode.
readonly TEARDOWN_API_INGRESS_CIDRS=127.0.0.1/32
readonly TEARDOWN_API_CERTIFICATE_ARN=none
readonly TEARDOWN_DIGEST=0000000000000000000000000000000000000000000000000000000000000000
# Runtime-stack variables that carry real AWS identifiers (account ID, resource IDs) or a personal
# address. Never committed: the human exports TF_VAR_<name> in the Terminal that runs plan.sh
# (IE-P13-05, human decision 2026-11-24); require_runtime_inputs checks them without printing them.
readonly RUNTIME_HUMAN_TF_VARS="vpc_id private_subnet_id private_route_table_id s3_prefix_list_id \
kms_key_arn runtime_image worker_image collector_image notification_email emr_cluster_id"
# Variables runtime/terraform.tfvars may never assign: a tfvars value overrides TF_VAR_* (Terraform
# precedence), so assigning one would silently replace the env.conf, env.local.conf or human value.
readonly RUNTIME_TFVARS_FORBIDDEN="aws_account_id environment course owner expiration availability_zone_id \
api_ingress_cidrs api_certificate_arn data_bucket logs_bucket $RUNTIME_HUMAN_TF_VARS"
# ENV_LOCAL_MODE: full (every key validated), teardown (account ID and admin ARN only) or none
# (env.local.conf not read; no account-derived value exists). Set by load_env_conf.
ENV_LOCAL_MODE=""

load_env_local_conf() { # load_env_local_conf <env> <full|teardown>: after env.conf; sets ENV_* from env.local.conf
  local f="infra/terraform/environments/$1/env.local.conf" mode=$2 c
  [[ $mode == full || $mode == teardown ]] || die "$EX_USAGE" "load_env_local_conf: unknown mode '$mode'"
  [[ -e $f || -L $f ]] || die "$EX_REFUSED" "$f is missing: copy $f.example to $f in your own terminal and fill in the real values (it is gitignored and never committed)"
  [[ -f $f && ! -L $f ]] || die "$EX_REFUSED" "$f is not a regular file"
  ! git ls-files --error-unmatch -- "$f" >/dev/null 2>&1 || die "$EX_REFUSED" "$f is tracked by git: it must never be committed (git rm --cached it and rewrite the commit)"
  git check-ignore -q -- "$f" || die "$EX_REFUSED" "$f is not gitignored (expected rule /infra/terraform/environments/*/env.local.conf in .gitignore)"
  # Separate prefix first, so a key of the local file can never overwrite one of env.conf.
  if [[ $mode == teardown ]]; then
    # Teardown must stay reachable (IE-P6-03): the API keys may be absent, REPLACE-ME or stale.
    parse_kv "$f" "config_version environment aws_account_id admin_principal_arn" ENVL_ ":," "$ENV_LOCAL_API_KEYS"
  else
    parse_kv "$f" "$ENV_LOCAL_KEYS" ENVL_ ":,"
  fi
  [[ $ENVL_config_version == scholarchain.environment-local-config.v1 ]] || die "$EX_USAGE" "$f: config_version must be scholarchain.environment-local-config.v1"
  [[ $ENVL_environment == "$1" ]] || die "$EX_REFUSED" "$f: environment '$ENVL_environment' differs from '$1'"
  [[ $ENVL_aws_account_id =~ ^[0-9]{12}$ ]] || die "$EX_USAGE" "$f: aws_account_id is not 12 digits"
  ! in_list "$ENVL_aws_account_id" "$ENV_LOCAL_DUMMY_ACCOUNTS" || die "$EX_REFUSED" "$f: aws_account_id is still a dummy value; fill in the real account"
  [[ $ENVL_admin_principal_arn =~ ^arn:aws:iam::[0-9]{12}:user/[A-Za-z0-9._-]{1,64}$ ]] ||
    die "$EX_USAGE" "$f: admin_principal_arn is not an IAM user ARN arn:aws:iam::<account>:user/<name>"
  [[ $ENVL_admin_principal_arn == "arn:aws:iam::$ENVL_aws_account_id:user/"* ]] || die "$EX_REFUSED" "$f: admin_principal_arn is not in account $ENVL_aws_account_id"
  [[ $ENVL_admin_principal_arn != *:user/example* && $ENVL_admin_principal_arn != *:user/dummy* ]] ||
    die "$EX_REFUSED" "$f: admin_principal_arn is still a dummy value"
  ENV_aws_account_id=$ENVL_aws_account_id
  ENV_admin_principal_arn=$ENVL_admin_principal_arn
  if [[ $mode == teardown ]]; then
    ok "local environment config $f, teardown mode: account ID and admin ARN only; API ingress and certificate not read (values not printed)"
    return 0
  fi
  [[ $ENVL_api_ingress_cidrs =~ ^[0-9./]+(,[0-9./]+){0,3}$ ]] || die "$EX_USAGE" "$f: api_ingress_cidrs must be 1 to 4 comma-separated IPv4 CIDRs"
  for c in ${ENVL_api_ingress_cidrs//,/ }; do
    is_narrow_cidr "$c" || die "$EX_REFUSED" "$f: api_ingress_cidrs entry '$c' is not a canonical IPv4 CIDR /24../32 outside TEST-NET (never 0.0.0.0/0)"
  done
  [[ $ENVL_api_certificate_arn == none || $ENVL_api_certificate_arn =~ ^arn:aws:acm:[a-z]{2}(-[a-z]+)+-[0-9]:[0-9]{12}:certificate/[0-9a-f-]{36}$ ]] ||
    die "$EX_USAGE" "$f: api_certificate_arn must be 'none' or an ACM certificate ARN"
  [[ $ENVL_api_certificate_arn == none || $ENVL_api_certificate_arn == *":$ENVL_aws_account_id:certificate/"* ]] ||
    die "$EX_REFUSED" "$f: api_certificate_arn is not in account $ENVL_aws_account_id"
  ENV_api_ingress_cidrs=$ENVL_api_ingress_cidrs
  ENV_api_certificate_arn=$ENVL_api_certificate_arn
  ok "local environment config $f (values not printed)"
}

# load_env_conf <env> [allow-expired|no-local]
#   (none)         plan.sh, deploy.sh and the run scripts: env.local.conf fully validated.
#   allow-expired  teardown and evidence (cleanup.sh, archive-evidence.sh): an expired environment is
#                  accepted, and env.local.conf supplies only aws_account_id and admin_principal_arn
#                  (API ingress and certificate keys are not read, so a stale value never blocks
#                  teardown; CR-P13-02 m-B). tf_env then exports teardown-only API values.
#   no-local       callers that need no account-derived value (smoke-test.sh): env.local.conf is not
#                  read; STATE_BUCKET is not set and tf_env refuses.
load_env_conf() {
  local f="infra/terraform/environments/$1/env.conf" opt=${2:-} allow_expired=""
  case $opt in
    "") ENV_LOCAL_MODE=full ;;
    allow-expired) ENV_LOCAL_MODE=teardown allow_expired=allow-expired ;;
    no-local) ENV_LOCAL_MODE=none ;;
    *) die "$EX_USAGE" "load_env_conf: unknown option '$opt'" ;;
  esac
  require_tracked "environment config" "$f"
  parse_kv "$f" "$ENV_KEYS" ENV_ ":"
  [[ $ENV_config_version == scholarchain.environment-config.v3 ]] || die "$EX_USAGE" "$f: config_version must be scholarchain.environment-config.v3 (account ID and admin ARN live in env.local.conf)"
  [[ $ENV_environment == "$1" ]] || die "$EX_REFUSED" "$f: environment '$ENV_environment' differs from '$1'"
  if [[ $ENV_LOCAL_MODE != none ]]; then load_env_local_conf "$1" "$ENV_LOCAL_MODE"; fi
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
  if [[ $ENV_LOCAL_MODE == none ]]; then
    ok "environment config $f (Region $ENV_aws_region; env.local.conf not read)"
    return 0
  fi
  STATE_BUCKET="scholarchain-tfstate-$ENV_aws_account_id-$ENV_aws_region"
  # Masked here; derived names (state, data and logs bucket names) still carry the full account ID.
  ok "environment config $f (account ********${ENV_aws_account_id:8:4}, Region $ENV_aws_region)"
}

# stack_dir <env> <bootstrap|base|emr-run>: prints the stack directory.
stack_dir() {
  case $2 in
    bootstrap) printf '%s' "infra/terraform/bootstrap" ;;
    base | emr-run | runtime) printf '%s' "infra/terraform/environments/$1/$2" ;;
    *) die "$EX_USAGE" "stack must be bootstrap, base, emr-run or runtime" ;;
  esac
}

# Terraform environment for one AWS profile: no static credentials, no prompts, TF_VAR_* from env.conf
# and env.local.conf. Terraform ignores TF_VAR_* for variables a stack does not declare (only the
# runtime stack declares api_ingress_cidrs and api_certificate_arn).
tf_env() { # tf_env <aws profile>
  [[ $ENV_LOCAL_MODE == full || $ENV_LOCAL_MODE == teardown ]] ||
    die "$EX_USAGE" "internal: tf_env needs load_env_conf with env.local.conf (mode '$ENV_LOCAL_MODE')"
  unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN AWS_SECURITY_TOKEN
  export AWS_PROFILE=$1 AWS_REGION=$ENV_aws_region TF_IN_AUTOMATION=1 TF_INPUT=0
  export TF_VAR_aws_account_id=$ENV_aws_account_id TF_VAR_aws_region=$ENV_aws_region
  export TF_VAR_availability_zone_id=$ENV_availability_zone_id TF_VAR_environment=$ENV_environment
  export TF_VAR_admin_principal_arn=$ENV_admin_principal_arn TF_VAR_state_bucket=$STATE_BUCKET
  export TF_VAR_course=$ENV_course TF_VAR_project=$ENV_project TF_VAR_owner=$ENV_owner TF_VAR_expiration=$ENV_expiration
  # Base's bucket names, derived exactly as base/storage.tf does (only the runtime stack declares them).
  export TF_VAR_data_bucket="$ENV_project-$ENV_environment-data-$ENV_aws_account_id-$ENV_aws_region"
  export TF_VAR_logs_bucket="$ENV_project-$ENV_environment-logs-$ENV_aws_account_id-$ENV_aws_region"
  if [[ $ENV_LOCAL_MODE == teardown ]]; then
    # The human's API values are not read in teardown; the runtime stack's required variables get
    # the inert teardown values (destroy plans only; see TEARDOWN_API_*).
    export TF_VAR_api_ingress_cidrs=$TEARDOWN_API_INGRESS_CIDRS TF_VAR_api_certificate_arn=$TEARDOWN_API_CERTIFICATE_ARN
    # Likewise the runtime identifiers the human exports for a create plan (RUNTIME_HUMAN_TF_VARS): a
    # destroy plan only needs values that pass the stack's variable validation (IE-P13-05).
    local td_img="$ENV_aws_account_id.dkr.ecr.$ENV_aws_region.amazonaws.com/teardown-only@sha256:$TEARDOWN_DIGEST"
    export TF_VAR_vpc_id=vpc-00000000000000000 TF_VAR_private_subnet_id=subnet-00000000000000000
    export TF_VAR_private_route_table_id=rtb-00000000000000000 TF_VAR_s3_prefix_list_id=pl-00000000
    export TF_VAR_kms_key_arn="arn:aws:kms:$ENV_aws_region:$ENV_aws_account_id:key/00000000-0000-0000-0000-000000000000"
    export TF_VAR_runtime_image=$td_img TF_VAR_worker_image=$td_img TF_VAR_collector_image=$td_img
    export TF_VAR_notification_email=teardown-only@example.invalid TF_VAR_emr_cluster_id=""
    # stderr: callers such as cleanup.sh stack_resources capture tf_env's stdout.
    note "teardown mode: TF_VAR_api_ingress_cidrs, TF_VAR_api_certificate_arn and the runtime identifiers ($RUNTIME_HUMAN_TF_VARS) are teardown-only values (destroy plans only)" >&2
  else
    export TF_VAR_api_ingress_cidrs=$ENV_api_ingress_cidrs TF_VAR_api_certificate_arn=$ENV_api_certificate_arn
  fi
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

# require_runtime_inputs <stack dir>: plan.sh gate for the runtime stack (IE-P13-05). Refuses unless
#   - <dir>/terraform.tfvars is committed and unmodified, holds no REPLACE-ME on a non-comment line, and
#     assigns none of RUNTIME_TFVARS_FORBIDDEN;
#   - no other auto-loaded variables file exists (terraform.tfvars.json, *.auto.tfvars[.json]);
#   - every TF_VAR_<name> of RUNTIME_HUMAN_TF_VARS is set by the human, free of REPLACE-ME, and of the
#     expected shape (account-bearing values in the env.local.conf account and Region).
# Prints names and counts only, never a value.
require_runtime_inputs() {
  local dir=$1 f="$1/terraform.tfvars" alt n bad k v re line s hex
  require_tracked "runtime variables file" "$f"
  for alt in "$dir"/terraform.tfvars.json "$dir"/*.auto.tfvars "$dir"/*.auto.tfvars.json; do
    [[ ! -e $alt && ! -L $alt ]] || die "$EX_REFUSED" "$alt would be auto-loaded next to $f; the runtime stack uses terraform.tfvars only"
  done
  n=$(awk '/^[[:space:]]*(#|\/\/)/ { next } /REPLACE-ME/ { c++ } END { print c + 0 }' "$f") ||
    die "$EX_REFUSED" "could not read $f"
  ((n == 0)) || die "$EX_REFUSED" "$f still holds $n REPLACE-ME placeholder line(s); fill them in a reviewed commit (runtime README, Filling the variables file)"
  bad=$(awk -v keys="$RUNTIME_TFVARS_FORBIDDEN" '
    BEGIN { m = split(keys, k, " "); for (i = 1; i <= m; i++) forbidden[k[i]] = 1 }
    /^[[:space:]]*(#|\/\/)/ { next }
    { line = $0; sub(/^[[:space:]]*/, "", line)
      if (match(line, /^"?[A-Za-z0-9_]+"?[[:space:]]*=/)) {
        name = substr(line, 1, RLENGTH); sub(/[[:space:]]*=$/, "", name); gsub(/"/, "", name)
        if (name in forbidden) printf "%s ", name } }' "$f") || die "$EX_REFUSED" "could not read $f"
  [[ -z $bad ]] || die "$EX_REFUSED" "$f assigns [${bad% }]: those come from env.conf, env.local.conf or the human's TF_VAR_* (a tfvars value would override them)"
  # CR-P13-04 m1: the committed file stays account-free. Refuse any non-comment line holding "arn:",
  # the env.local.conf account ID, or a 12-digit number once every 64-hex sha256 value is removed
  # (a byte count of 12 or more digits is refused too: fail closed). Line numbers only, never a value.
  [[ ${ENV_aws_account_id:-} =~ ^[0-9]{12}$ ]] || die "$EX_REFUSED" "account ID from env.local.conf is not loaded; cannot check $f"
  bad="" n=0
  while IFS= read -r line || [[ -n $line ]]; do
    n=$((n + 1))
    [[ $line =~ ^[[:space:]]*(#|//) ]] && continue
    s=$line
    while [[ $s =~ [0-9a-fA-F]{64} ]]; do
      hex=${BASH_REMATCH[0]}
      s=${s//"$hex"/ }
    done
    if [[ $line == *arn:* || $line == *"$ENV_aws_account_id"* || $s =~ [0-9]{12} ]]; then
      bad+="$n "
    fi
  done <"$f" || die "$EX_REFUSED" "could not read $f"
  [[ -z $bad ]] || die "$EX_REFUSED" "$f line(s) [${bad% }] hold an ARN, the account ID or a 12-digit number: write the data bucket as @DATA_BUCKET@ and keep account-bearing values in TF_VAR_* (runtime README, Filling the variables file; values not printed)"
  ok "runtime variables file $f: no placeholder, no overriding assignment, no ARN or account ID"
  for k in $RUNTIME_HUMAN_TF_VARS; do
    v="TF_VAR_$k"
    [[ -n ${!v+set} ]] || die "$EX_REFUSED" "$v is not exported: set it in your Terminal before plan.sh (runtime README, Filling the variables file; never commit it)"
    [[ ${!v} != *REPLACE-ME* ]] || die "$EX_REFUSED" "$v is still a REPLACE-ME placeholder"
  done
  [[ $TF_VAR_vpc_id =~ ^vpc-[0-9a-f]{8,17}$ ]] || die "$EX_USAGE" "TF_VAR_vpc_id is not a VPC ID (vpc-...; value not printed)"
  [[ $TF_VAR_private_subnet_id =~ ^subnet-[0-9a-f]{8,17}$ ]] || die "$EX_USAGE" "TF_VAR_private_subnet_id is not a subnet ID (subnet-...; value not printed)"
  [[ $TF_VAR_private_route_table_id =~ ^rtb-[0-9a-f]{8,17}$ ]] || die "$EX_USAGE" "TF_VAR_private_route_table_id is not a route table ID (rtb-...; value not printed)"
  [[ $TF_VAR_s3_prefix_list_id =~ ^pl-[0-9a-f]{8,17}$ ]] || die "$EX_USAGE" "TF_VAR_s3_prefix_list_id is not a prefix list ID (pl-...; value not printed)"
  re="^arn:aws:kms:$ENV_aws_region:([0-9]{12}):key/[0-9a-f-]{36}$"
  [[ $TF_VAR_kms_key_arn =~ $re && ${BASH_REMATCH[1]} == "$ENV_aws_account_id" ]] ||
    die "$EX_USAGE" "TF_VAR_kms_key_arn is not a KMS key ARN in the env.local.conf account and $ENV_aws_region (value not printed)"
  re="^([0-9]{12})\.dkr\.ecr\.$ENV_aws_region\.amazonaws\.com/[a-z0-9/-]+@sha256:[0-9a-f]{64}$"
  for k in runtime_image worker_image collector_image; do
    v="TF_VAR_$k"
    [[ ${!v} =~ $re && ${BASH_REMATCH[1]} == "$ENV_aws_account_id" ]] ||
      die "$EX_USAGE" "$v is not an ECR image digest reference in the env.local.conf account and $ENV_aws_region (value not printed)"
    [[ ${!v} != *"@sha256:$TEARDOWN_DIGEST" ]] || die "$EX_REFUSED" "$v holds the teardown-only digest"
  done
  [[ $TF_VAR_notification_email =~ ^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$ && $TF_VAR_notification_email != *@example.* ]] ||
    die "$EX_USAGE" "TF_VAR_notification_email is not a deliverable e-mail address (value not printed)"
  if [[ -z $TF_VAR_emr_cluster_id ]]; then
    note "TF_VAR_emr_cluster_id is empty: the deadline monitor's EMR branch is disabled in this plan"
  else
    [[ $TF_VAR_emr_cluster_id =~ ^j-[A-Z0-9]{8,20}$ ]] || die "$EX_USAGE" "TF_VAR_emr_cluster_id is neither empty nor an EMR cluster ID (j-...; value not printed)"
  fi
  ok "runtime identifiers exported: $RUNTIME_HUMAN_TF_VARS (values not printed)"
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
  printf '%s\n' "$rows" | awk -F'\t' 'NF == 3 && $2 ~ /^aws_emr_cluster$|^aws_nat_gateway$|^aws_eip$|^aws_instance$|^aws_vpc_endpoint$|^aws_lb$|^aws_internet_gateway$/ { printf "[%s] PLAN-COST %s %s\n", s, $1, $3 }' s="$SCRIPT_NAME"
  say "PLAN-COUNTS add=$PLAN_ADD change=$PLAN_CHANGE destroy=$PLAN_DESTROY replace=$PLAN_REPLACE"
}

# Runtime admission stays separate from Terraform plan approval. The JSON has no secret values.
require_runtime_approval() {
  local approval_file=infra/terraform/environments/dev/runtime/approved-run.json sum
  [[ $ARG_ENV == dev ]] || die "$EX_REFUSED" "bounded runtime is dev only"
  need_tools python3
  require_tracked "runtime approval package" "$approval_file"
  sum=$(python3 scripts/runtime/approval.py "$approval_file") || die "$EX_REFUSED" "runtime approval package invalid"
  [[ ${SCHOLARCHAIN_RUNTIME_APPROVAL:-} == "runtime-$HEAD_SHA-$ARG_PLAN-$sum" ]] ||
    die "$EX_REFUSED" "human runtime approval must bind HEAD, saved plan and current approval-package checksum"
}
