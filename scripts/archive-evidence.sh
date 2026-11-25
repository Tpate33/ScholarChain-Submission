#!/usr/bin/env bash
# =====================================================================================================
# scripts/archive-evidence.sh -- collect local deployment and run evidence for one commit into a
# create-once directory OUTSIDE the repository. Owner: infra-engineer (IE-P13-01).
#
# USAGE   scripts/archive-evidence.sh <environment> <commit sha40>
#
# Destination: ${SCHOLARCHAIN_EVIDENCE_ROOT:-$HOME/ScholarChain-evidence}/<environment>/<sha40>/
#              <UTC timestamp>-<pid>/   created with mkdir (never reused, never overwritten), must be
#              outside the repository. Before anything is created, the nearest existing ancestor of
#              the destination is resolved with `pwd -P` and the result is refused if it lies inside
#              the repository, compared case-insensitively (APFS is case-insensitive by default); the
#              check is repeated after creation. Nothing is ever deleted, here or in the repository.
# Tools: git, shasum, awk, find, sed, tr, cp, chmod, date, head are required before anything is
#              created (exit 2 if one is missing). aws and jq are checked before creation too; if
#              either is missing (and SCHOLARCHAIN_ARCHIVE_SKIP_AWS is not 1) the run degrades to
#              local evidence only, records AWS-FAIL tools and exits 4.
# Collected (each only when present; absence is recorded in collection.tsv, not hidden):
#   git/          HEAD, branch, status, `git log -1 --stat` of the commit, blob hashes of
#                 docs/agent-runs/** and experiments/** at the commit (names and hashes only)
#   config/       infra/terraform/environments/<env>/env.conf and runs/*.conf at the commit
#                 (`git show <sha>:<path>`, non-secret by contract)
#   plans/        <stack>/.terraform/scholarchain-plan.meta of each stack (written by scripts/plan.sh:
#                 checksum, counts, account, Region; the saved tfplan itself is NEVER read)
#   package/      target/package/<sha40>/attempt-*/package-manifest.txt (scripts/package.sh)
#   tests/        .claude/.last-test-run (the sbt markTestRun marker)
#   aws/          read-only describe/list output through the READ-ONLY profile of env.conf, unless
#                 SCHOLARCHAIN_ARCHIVE_SKIP_AWS=1: sts get-caller-identity (must be the
#                 scholarchain-<env>-readonly role), ecs describe-services, ecr describe-images,
#                 cloudwatch describe-alarms, emr list-clusters --active. Every call has connect and
#                 read timeouts; no call writes, and no call reads a secret value.
# Then SHA256SUMS over every collected file and the directory is made read-only (chmod -R a-w).
# Failure after the timestamped directory exists (any command failure, internal refusal, or HUP, INT
# or TERM): an EXIT trap writes INCOMPLETE (exit status, phase, time, reason), SHA256SUMS over the
# files that exist, makes the directory read-only, prints RESULT ARCHIVED-INCOMPLETE and exits 4
# (3 if the failure was a refusal). No unsealed partial directory is left behind.
# Never reads Terraform state or plan files, gold/**, or target/local-runs/heldout (refused by path).
#
# MARKERS  [archive-evidence] STEP / CHECK ok / COLLECT <kind> <path> / MISSING <kind> <path> /
#          AWS-CALL read: ... (stderr) / AWS-FAIL <name> / EVIDENCE <dir> SHA256SUMS sha256=... /
#          RESULT ARCHIVED|ARCHIVED-WITH-FAILURES|ARCHIVED-INCOMPLETE; FAIL on stderr.
# EXIT     0 archived; 2 usage or a required local tool missing (nothing created); 3 refused (before
#          creation nothing is created; after creation the partial archive is sealed with INCOMPLETE);
#          4 an AWS read failed, aws/jq missing, the identity is not the read-only role, or the run
#          failed after creation (the archive is always written, checksummed and read-only)
# =====================================================================================================
set -euo pipefail
readonly SCRIPT_NAME=archive-evidence
# shellcheck source=scripts/lib/common.sh disable=SC1091
. "$(dirname "$0")/lib/common.sh"

(($# == 2)) || die "$EX_USAGE" "usage: scripts/archive-evidence.sh <environment> <commit sha40>"
ARG_ENV=$1
ARG_SHA=$2
is_env_name "$ARG_ENV" || die "$EX_USAGE" "environment '$ARG_ENV' is not a short lowercase name"
need_hex commit "$ARG_SHA" 40
need_tools git shasum awk find sed tr cp chmod date head
step "start env=$ARG_ENV commit=$ARG_SHA at=$(now_utc)"
require_repo_root
git cat-file -e "$ARG_SHA^{commit}" 2>/dev/null || die "$EX_REFUSED" "$ARG_SHA is not a commit in this repository"
load_env_conf "$ARG_ENV" allow-expired

# ---- forbidden sources -----------------------------------------------------------------------------
forbidden() { # forbidden <path>: 0 if the path must never be read
  local p="/${1#./}"
  case $p in
    */tfplan | */*.tfplan | */tfplan.* | *.tfstate | *.tfstate.* | */terraform.tfstate*) return 0 ;;
    /gold/* | /gold | */target/local-runs/heldout*) return 0 ;;
    */.env* | */secrets/* | */.aws/* | */.ssh/*) return 0 ;;
  esac
  return 1
}

# ---- AWS tools (checked before anything is created; missing tools degrade to local-only, exit 4) ----
AWS_FAILS=0
AWS_MODE=live
if [[ ${SCHOLARCHAIN_ARCHIVE_SKIP_AWS:-} == 1 ]]; then
  AWS_MODE=skip
else
  for t in aws jq; do
    if ! command -v "$t" >/dev/null 2>&1; then
      say "AWS-FAIL tools: required tool '$t' is not installed; archiving local evidence only (exit 4)"
      AWS_MODE=tools-missing
    fi
  done
fi

# ---- destination (resolved and refused BEFORE anything is created) ---------------------------------
ROOT_DIR=${SCHOLARCHAIN_EVIDENCE_ROOT:-$HOME/ScholarChain-evidence}
[[ $ROOT_DIR == /* ]] || die "$EX_USAGE" "SCHOLARCHAIN_EVIDENCE_ROOT must be an absolute path"
[[ $ROOT_DIR =~ ^[A-Za-z0-9._/-]+$ && /$ROOT_DIR/ != */../* && /$ROOT_DIR/ != */./* ]] ||
  die "$EX_USAGE" "SCHOLARCHAIN_EVIDENCE_ROOT has unexpected characters or . / .. segments"
lower() { printf '%s' "$1" | tr '[:upper:]' '[:lower:]'; }
# inside_repo <physical path>: 0 if the path is the repository or below it, compared case-insensitively
# (APFS and HFS+ are case-insensitive by default, so /Users/x/Repo and /users/x/repo are one place).
REPO_REAL=$(cd "$REPO_ROOT" && pwd -P) || die "$EX_REFUSED" "cannot resolve the repository root"
inside_repo() {
  local p r
  p=$(lower "${1%/}/")
  r=$(lower "$REPO_REAL/")
  [[ $p == "$r"* ]]
}
TARGET="$ROOT_DIR/$ARG_ENV/$ARG_SHA"
# Nearest existing ancestor, physically resolved (symlinks followed); the missing tail is appended.
EXISTING=$TARGET
TAIL=""
while [[ ! -d $EXISTING ]]; do
  [[ ! -e $EXISTING && ! -L $EXISTING ]] || die "$EX_REFUSED" "$EXISTING exists and is not a directory"
  TAIL="/${EXISTING##*/}$TAIL"
  EXISTING=${EXISTING%/*}
  [[ -n $EXISTING ]] || EXISTING=/
done
EXISTING_REAL=$(cd "$EXISTING" && pwd -P) || die "$EX_REFUSED" "cannot resolve $EXISTING"
RESOLVED="${EXISTING_REAL%/}$TAIL"
inside_repo "$RESOLVED" && die "$EX_REFUSED" "the evidence directory $RESOLVED is inside the repository $REPO_REAL (nothing created)"
ok "evidence parent $RESOLVED is outside the repository (case-insensitive check, before creation)"
mkdir -p "$TARGET"
PARENT=$(cd "$TARGET" && pwd -P)
# Re-check after creation (a concurrent symlink swap would show here).
inside_repo "$PARENT" && die "$EX_REFUSED" "the evidence directory $PARENT is inside the repository"
DEST="$PARENT/$(date -u +%Y%m%dT%H%M%SZ)-$$"
mkdir "$DEST" || die "$EX_REFUSED" "$DEST already exists (create-once)"

# ---- from here on, any exit seals the archive ------------------------------------------------------
# On a failure the trap writes INCOMPLETE (exit status, phase, time), SHA256SUMS over what exists, makes
# the directory read-only, prints RESULT ARCHIVED-INCOMPLETE and exits 4 (3 if the failure was a
# refusal). A successful run seals itself below and the trap passes its status through.
SEALED=0
PHASE="create layout"
seal() { # seal: SHA256SUMS over every file except itself, then read-only
  (
    cd "$DEST"
    find . -type f ! -name SHA256SUMS -print | sed 's|^\./||' | LC_ALL=C sort | while IFS= read -r f; do
      printf '%s  %s\n' "$(sha256_file "$f")" "$f"
    done
  ) >"$DEST/SHA256SUMS"
  SUMS_SHA=$(sha256_file "$DEST/SHA256SUMS")
  chmod -R a-w "$DEST"
  say "EVIDENCE $DEST SHA256SUMS sha256=$SUMS_SHA files=$(awk 'END { print NR }' "$DEST/SHA256SUMS")"
}
# shellcheck disable=SC2329 # invoked by the EXIT trap below
on_exit() {
  local rc=$? code
  trap - EXIT
  if ((SEALED == 1)); then exit "$rc"; fi
  set +e
  chmod -R u+w "$DEST" 2>/dev/null # a seal interrupted after chmod: reopen, then reseal below
  code=$EX_AWS
  ((rc == EX_REFUSED)) && code=$EX_REFUSED
  printf 'status=incomplete\nexit_status=%s\nphase=%s\nat=%s\nreason=the archive run failed in phase "%s" with exit status %s; files present are listed in SHA256SUMS\n' \
    "$rc" "$PHASE" "$(now_utc)" "$PHASE" "$rc" >"$DEST/INCOMPLETE"
  seal
  say "RESULT ARCHIVED-INCOMPLETE phase=$PHASE exit_status=$rc dir=$DEST"
  printf '[%s] FAIL exit=%s archive incomplete (phase "%s", exit status %s); partial evidence sealed in %s\n' \
    "$SCRIPT_NAME" "$code" "$PHASE" "$rc" "$DEST" >&2
  exit "$code"
}
trap on_exit EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
phase() { PHASE=$1; step "$1"; }

mkdir "$DEST/git" "$DEST/config" "$DEST/plans" "$DEST/package" "$DEST/tests" "$DEST/aws"
INDEX="$DEST/collection.tsv"
printf 'kind\tstatus\tsource\n' >"$INDEX"
ok "evidence directory $DEST (create-once)"

collect() { # collect <kind> <source path> <dest relative path>
  if forbidden "$2"; then die "$EX_REFUSED" "internal: refusing to read forbidden path $2"; fi
  if [[ -f $2 && ! -L $2 ]]; then
    cp -p "$2" "$DEST/$3"
    say "COLLECT $1 $2"
    printf '%s\tcollected\t%s\n' "$1" "$2" >>"$INDEX"
  else
    say "MISSING $1 $2"
    printf '%s\tmissing\t%s\n' "$1" "$2" >>"$INDEX"
  fi
}
record() { # record <kind> <status> <source>
  printf '%s\t%s\t%s\n' "$1" "$2" "$3" >>"$INDEX"
}

# ---- git -------------------------------------------------------------------------------------------
phase "git"
{
  printf 'archived_commit=%s\nhead=%s\nbranch=%s\narchived_at=%s\nenvironment=%s\n' "$ARG_SHA" "$HEAD_SHA" \
    "$(git branch --show-current)" "$(now_utc)" "$ARG_ENV"
  printf 'head_equals_commit=%s\n' "$([[ $HEAD_SHA == "$ARG_SHA" ]] && printf yes || printf no)"
} >"$DEST/git/revision.txt"
git status --porcelain --untracked-files=all >"$DEST/git/status.txt"
git log -1 --stat --no-ext-diff "$ARG_SHA" >"$DEST/git/commit.txt"
git ls-tree -r "$ARG_SHA" -- docs/agent-runs experiments >"$DEST/git/evidence-blobs.txt"
record git collected "git metadata at $ARG_SHA"
[[ -s $DEST/git/status.txt ]] && note "working tree is not clean (recorded in git/status.txt)"

# ---- configuration at the commit -------------------------------------------------------------------
phase "configuration at $ARG_SHA"
ENV_DIR="infra/terraform/environments/$ARG_ENV"
while IFS= read -r p; do
  [[ -n $p ]] || continue
  forbidden "$p" && die "$EX_REFUSED" "internal: refusing to read forbidden path $p"
  case $p in
    "$ENV_DIR/env.conf" | "$ENV_DIR"/runs/*.conf)
      git show "$ARG_SHA:$p" >"$DEST/config/$(printf '%s' "${p#"$ENV_DIR"/}" | tr '/' '_')"
      say "COLLECT config $p@$ARG_SHA"
      record config collected "$p@$ARG_SHA"
      ;;
  esac
done < <(git ls-tree -r --name-only "$ARG_SHA" -- "$ENV_DIR")

# ---- plan metadata (never the plan) ----------------------------------------------------------------
phase "plan metadata"
for s in bootstrap base emr-run runtime; do
  d=$(stack_dir "$ARG_ENV" "$s")
  collect plan-meta "$d/.terraform/scholarchain-plan.meta" "plans/$s.scholarchain-plan.meta"
done

# ---- package manifests -----------------------------------------------------------------------------
phase "package manifests"
PKG_FOUND=0
for f in target/package/"$ARG_SHA"/attempt-*/package-manifest.txt; do
  [[ -f $f ]] || continue
  a=${f%/package-manifest.txt}
  collect package "$f" "package/${a##*/}.package-manifest.txt"
  PKG_FOUND=$((PKG_FOUND + 1))
done
((PKG_FOUND > 0)) || { say "MISSING package target/package/$ARG_SHA/attempt-*/package-manifest.txt"; record package missing "target/package/$ARG_SHA"; }

# ---- test marker -----------------------------------------------------------------------------------
phase "test marker"
collect tests .claude/.last-test-run tests/last-test-run.txt

# ---- read-only AWS ---------------------------------------------------------------------------------
if [[ $AWS_MODE == skip ]]; then
  note "SCHOLARCHAIN_ARCHIVE_SKIP_AWS=1: no AWS call made"
  record aws skipped "SCHOLARCHAIN_ARCHIVE_SKIP_AWS=1"
elif [[ $AWS_MODE == tools-missing ]]; then
  say "AWS-FAIL tools: aws or jq is not installed; no AWS call made (local evidence only)"
  record aws failed "aws or jq not installed"
  AWS_FAILS=$((AWS_FAILS + 1))
else
  phase "read-only AWS (profile $ENV_aws_readonly_profile)"
  unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN AWS_SECURITY_TOKEN AWS_PROFILE
  AWS_BASE=(aws --profile "$ENV_aws_readonly_profile" --region "$ENV_aws_region" --output json
    --cli-connect-timeout 10 --cli-read-timeout 60)
  READ_VERBS="sts:get-caller-identity ecs:describe-services ecr:describe-images cloudwatch:describe-alarms emr:list-clusters"
  aws_ro() { # aws_ro <name> <service> <verb> [args...]: output to aws/<name>.json, failure recorded
    local name=$1
    shift
    in_list "$1:$2" "$READ_VERBS" || die "$EX_USAGE" "internal: $1 $2 is not an allowed read call"
    say "AWS-CALL read: ${AWS_BASE[*]} $*" >&2
    if "${AWS_BASE[@]}" "$@" >"$DEST/aws/$name.json" 2>"$DEST/aws/$name.err"; then
      record aws collected "$name"
      return 0
    fi
    head -n 5 "$DEST/aws/$name.err" >&2 || true
    say "AWS-FAIL $name (error kept in aws/$name.err)"
    record aws failed "$name"
    AWS_FAILS=$((AWS_FAILS + 1))
    return 1
  }
  if aws_ro identity sts get-caller-identity; then
    ID_ARN=$(jq -r '.Arn // ""' "$DEST/aws/identity.json")
    ID_ACCOUNT=$(jq -r '.Account // ""' "$DEST/aws/identity.json")
    if [[ $ID_ACCOUNT != "$ENV_aws_account_id" || $ID_ARN != "arn:aws:sts::$ENV_aws_account_id:assumed-role/scholarchain-$ARG_ENV-readonly/"* ]]; then
      say "AWS-FAIL identity is not the read-only role scholarchain-$ARG_ENV-readonly in $ENV_aws_account_id; no further AWS call"
      AWS_FAILS=$((AWS_FAILS + 1))
    else
      ok "read-only role in account $ENV_aws_account_id"
      RT="scholarchain-$ARG_ENV-bounded" # local.name of the runtime stack
      aws_ro ecs-services ecs describe-services --cluster "$RT" --services "$RT-query" "$RT-evidence" "$RT-graph" || true
      aws_ro ecr-runtime-images ecr describe-images --repository-name "$RT/runtime" || true
      aws_ro ecr-worker-images ecr describe-images --repository-name "$RT/worker" || true
      aws_ro alarms cloudwatch describe-alarms --alarm-name-prefix "scholarchain-$ARG_ENV" || true
      aws_ro emr-active-clusters emr list-clusters --active || true
    fi
  fi
fi

# ---- checksums and seal ----------------------------------------------------------------------------
phase "checksums"
seal
SEALED=1
if ((AWS_FAILS > 0)); then
  say "RESULT ARCHIVED-WITH-FAILURES aws_failures=$AWS_FAILS dir=$DEST"
  die "$EX_AWS" "$AWS_FAILS AWS read(s) failed or the identity check failed (AWS-FAIL lines above); the local evidence is archived"
fi
say "RESULT ARCHIVED dir=$DEST"
