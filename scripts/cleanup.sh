#!/usr/bin/env bash
# =====================================================================================================
# scripts/cleanup.sh -- HUMAN-ONLY teardown of one environment, in two phases. Owner: infra-engineer.
# Agents never run this script (guard rule cleanup-human-only); it also refuses inside Claude Code and
# without a terminal. No interactive prompt: every destructive action needs a confirmation token in
# SCHOLARCHAIN_CONFIRM_CLEANUP, printed by the listing action before it.
#
# USAGE
#   scripts/cleanup.sh <env> inventory [after-base]             read-only listing + empty-buckets tokens
#                                                               (after-base: reads with the deploy profile,
#                                                               the read-only role is gone with base)
#   scripts/cleanup.sh <env> plan-destroy <emr-run|base|bootstrap>
#   scripts/cleanup.sh <env> apply-destroy <emr-run|base|bootstrap> <plan sha256>      (token)
#   scripts/cleanup.sh <env> empty-buckets <base|bootstrap>                            (token)
#
# ORDER
#   Phase A (after the run): plan-destroy emr-run, apply-destroy emr-run (terminates the cluster if it
#            is still up; stops all compute cost).
#   Archive evidence outside AWS and outside the repository with the ARCHIVE-COMMAND lines of
#   `inventory`, then set SCHOLARCHAIN_EVIDENCE_ARCHIVE_DIR to that directory. empty-buckets base
#   checks, by name and size against S3, every object under submissions/, each run's event log and
#   step logs, and the run=/load= listing of output/spark/ (layout above require_evidence_dir).
#   Phase B: inventory, empty-buckets base (data and logs buckets, every version), plan-destroy base,
#            apply-destroy base (KMS key scheduled for deletion, 7-day window, no charge meanwhile);
#            optionally inventory, empty-buckets bootstrap, plan-destroy bootstrap, apply-destroy bootstrap.
#   Gates: base needs emr-run empty; bootstrap needs base and emr-run empty; empty-buckets needs the
#   evidence directory and the stack(s) above it destroyed first (emr-run for base; base for bootstrap).
#
# Profiles from env.conf: listing and plans with aws_readonly_profile; deletes and apply with
# aws_deploy_profile (the human's IAM user via `aws login`; the bucket policies exempt only
# admin_principal_arn from the delete denies). Destroy plans are written to <stack>/destroy.tfplan (gitignored).
# MARKERS  [cleanup] STEP / CHECK ok / INVENTORY / PLAN-CHANGE / PLAN-COUNTS / PLAN-SHA256 /
#          CONFIRMATION-TOKEN / DELETED / RESULT; FAIL on stderr.
# EXIT     0 ok, 2 usage, 3 refused, 4 AWS error, 5 terraform failed
# =====================================================================================================
set -euo pipefail
readonly SCRIPT_NAME=cleanup
# shellcheck source=scripts/lib/common.sh disable=SC1091
. "$(dirname "$0")/lib/common.sh"
readonly HC_MAX_DELETE_BATCHES=100 # 1000 versions per batch

(($# >= 2)) || die "$EX_USAGE" "usage: see the header of scripts/cleanup.sh"
ARG_ENV=$1
ACTION=$2
is_env_name "$ARG_ENV" || die "$EX_USAGE" "environment '$ARG_ENV' is not a short lowercase name"
[[ -z ${CLAUDECODE:-} ]] || die "$EX_REFUSED" "cleanup is human-only: refusing inside a Claude Code session"
[[ -t 0 && -t 1 ]] || die "$EX_REFUSED" "cleanup is human-only: run it in a terminal (stdin and stdout must be a TTY)"
need_tools git terraform jq awk shasum aws

step "start env=$ARG_ENV action=$ACTION at=$(now_utc)"
require_repo_root
require_clean_tree
# allow-expired: teardown must remain possible after the expiration tag date (IE-P6-03).
load_env_conf "$ARG_ENV" allow-expired
DATA_BUCKET="scholarchain-$ARG_ENV-data-$ENV_aws_account_id-$ENV_aws_region"
LOGS_BUCKET="scholarchain-$ARG_ENV-logs-$ENV_aws_account_id-$ENV_aws_region"
token_for() { printf 'clr-%s' "$(sha256_text "cleanup|$ARG_ENV|$ENV_aws_account_id|$HEAD_SHA|$*" | cut -c1-32)"; }
need_token() {
  [[ ${SCHOLARCHAIN_CONFIRM_CLEANUP:-} == "$(token_for "$@")" ]] ||
    die "$EX_REFUSED" "SCHOLARCHAIN_CONFIRM_CLEANUP does not hold the token for '$*'; run the listing action first"
  ok "confirmation token matches ($*)"
}
# Read profile: the read-only role (R5) while base exists. Bootstrap teardown runs after base, and
# with it R5, is destroyed, so its reads use the deploy profile (this script is human-only):
# plan-destroy/apply-destroy/empty-buckets bootstrap, and `inventory after-base`.
READ_PROFILE=$ENV_aws_readonly_profile
if [[ ${3:-} == bootstrap || ($ACTION == inventory && ${3:-} == after-base) ]]; then
  READ_PROFILE=$ENV_aws_deploy_profile
fi
note "read profile $READ_PROFILE"
aws_ro() { aws --profile "$READ_PROFILE" --region "$ENV_aws_region" --output json "$@"; }
aws_rw() {
  say "AWS-CALL write: aws --profile $ENV_aws_deploy_profile $*" >&2
  aws --profile "$ENV_aws_deploy_profile" --region "$ENV_aws_region" --output json "$@"
}
stack_resources() { # stack_resources <stack>: number of resources in the stack's state
  local d
  d=$(stack_dir "$ARG_ENV" "$1")
  tf_env "$READ_PROFILE"
  tf_init "$1" "$d" >/dev/null
  terraform -chdir="$d" state list -no-color | grep -c . || true
}
version_count() { # version_count <bucket>: object versions + delete markers (first 1000), or "absent"
  local out
  out=$(aws_ro s3api list-object-versions --bucket "$1" --max-keys 1000 --no-paginate 2>/dev/null) || {
    printf 'absent'
    return 0
  }
  printf '%s' "$out" | jq '[(.Versions // [])[], (.DeleteMarkers // [])[]] | length'
}
require_destroyed() { # require_destroyed <stack>...
  local s n
  for s in "$@"; do
    n=$(stack_resources "$s")
    [[ $n == 0 ]] || die "$EX_REFUSED" "stack $s still has '$n' resources in state; destroy it first"
  done
}
# ---- evidence archive (CR-P6-27 m1) -------------------------------------------------------------------
# Layout under SCHOLARCHAIN_EVIDENCE_ARCHIVE_DIR (outside the repository), filled by the human with the
# ARCHIVE-COMMAND lines that `inventory` prints:
#   s3/<data bucket>/submissions/<id>/{run-record,run-summary}.json   every object under submissions/
#   s3/<logs bucket>/spark-events/<id>/<event log>                    every object under spark-events/<id>/
#   s3/<logs bucket>/emr/<cluster>/steps/<step>/<log>                 every object of each run's step
#   listings/output-spark-prefixes.txt                                the run=/load= prefixes under
#                                                                     output/spark/, sorted, one per line
#   notes/<id>.txt   optional, human-written: why submission <id> has no summary, event log or step logs
#                    in S3. It waives only an empty listing; every object that exists is still checked.
# Every object listed in S3 must be present under the same relative name with the same, non-zero size.
EVIDENCE_DIR=""
require_evidence_dir() {
  local d=${SCHOLARCHAIN_EVIDENCE_ARCHIVE_DIR:-} real repo
  [[ -n $d && -d $d && ! -L $d ]] || die "$EX_REFUSED" "set SCHOLARCHAIN_EVIDENCE_ARCHIVE_DIR to the evidence archive directory"
  real=$(cd "$d" && pwd -P)
  repo=$(pwd -P)
  # Only the repository itself and its subdirectories are refused; a sibling such as <repo>-archive is fine.
  [[ $real != "$repo" && $real != "$repo/"* ]] || die "$EX_REFUSED" "the evidence archive must be outside the repository ($real)"
  EVIDENCE_DIR=$real
}
list_keys() { # list_keys <bucket> <prefix> <out file>: "key<TAB>size" lines; refuses more than 1000 objects
  local out
  out=$(aws_ro s3api list-objects-v2 --bucket "$1" --prefix "$2" --max-items 1000) || die "$EX_AWS" "list of s3://$1/$2 failed"
  [[ $(printf '%s' "$out" | jq -r '.NextToken // ""') == "" ]] || die "$EX_REFUSED" "more than 1000 objects under s3://$1/$2; check the archive by hand"
  printf '%s' "$out" | jq -r '.Contents[]? | [.Key, (.Size | tostring)] | @tsv' >"$3"
}
require_mirrored() { # require_mirrored <bucket> <prefix>: sets MIRRORED to the number of objects checked
  local key size f list="$EVIDENCE_TMP/keys.tsv"
  MIRRORED=0
  list_keys "$1" "$2" "$list"
  while IFS=$'\t' read -r key size; do
    [[ -n $key ]] || continue
    is_repo_path "$key" || die "$EX_REFUSED" "s3://$1/$key has characters outside the archive name rules; archive and check it by hand"
    f="$EVIDENCE_DIR/s3/$1/$key"
    [[ -f $f && ! -L $f ]] || die "$EX_REFUSED" "evidence missing: $f (s3://$1/$key, $size bytes)"
    [[ $(file_bytes "$f") == "$size" ]] || die "$EX_REFUSED" "evidence $f has $(file_bytes "$f") bytes, s3://$1/$key has $size"
    ((size > 0)) || die "$EX_REFUSED" "s3://$1/$key is empty; record why in the archive notes and check it by hand"
    MIRRORED=$((MIRRORED + 1))
  done <"$list"
}
has_note() { [[ -f $EVIDENCE_DIR/notes/$1.txt && ! -L $EVIDENCE_DIR/notes/$1.txt && -s $EVIDENCE_DIR/notes/$1.txt ]]; }
require_run_evidence() { # every submission's record, summary, event log, step logs, plus the output listing
  local ids id summary record src cluster stp want
  require_evidence_dir
  EVIDENCE_TMP=$(mktemp -d "${TMPDIR:-/tmp}/scholarchain-evidence.XXXXXXXX")
  require_mirrored "$DATA_BUCKET" submissions/
  ids=$(cut -f1 "$EVIDENCE_TMP/keys.tsv" | awk -F/ 'NF >= 3 { print $2 }' | LC_ALL=C sort -u)
  # CR-P6-28 m1-r (repair 02 item 3): every object that exists in S3 for a submission must be mirrored,
  # whatever notes/<id>.txt says. A note waives only an EMPTY listing (no summary, no event log in S3,
  # no step logs in S3, or no cluster/step id to locate them), never an object that exists but is not
  # archived.
  for id in $ids; do
    [[ $id =~ ^a[0-9]{1,2}-[0-9a-f]{16}$ ]] || die "$EX_REFUSED" "unexpected submission id '$id' under submissions/"
    summary="$EVIDENCE_DIR/s3/$DATA_BUCKET/submissions/$id/run-summary.json"
    record="$EVIDENCE_DIR/s3/$DATA_BUCKET/submissions/$id/run-record.json"
    # Event log: always listed and mirrored (no early exit for a missing summary).
    require_mirrored "$LOGS_BUCKET" "spark-events/$id/"
    ((MIRRORED > 0)) || has_note "$id" || die "$EX_REFUSED" "submission $id: no event log in S3; write notes/$id.txt"
    ((MIRRORED > 0)) || note "submission $id: empty spark-events/$id/ listing, waived by notes/$id.txt"
    # Locate the cluster and step: the summary when archived, else the run record (cluster only;
    # the record is written before add-steps, so it names no step).
    src=""
    stp=""
    if [[ -f $summary && ! -L $summary ]]; then
      [[ $(jq -r .submissionId "$summary") == "$id" ]] || die "$EX_REFUSED" "$summary does not name submission $id"
      src=$summary
      stp=$(jq -r '.emr.stepId // ""' "$summary")
    else
      has_note "$id" || die "$EX_REFUSED" "submission $id has no run-summary.json in S3; write notes/$id.txt"
      note "submission $id: no run summary, waived by notes/$id.txt (event log and step logs still checked)"
      if [[ -f $record && ! -L $record ]]; then
        [[ $(jq -r .submissionId "$record") == "$id" ]] || die "$EX_REFUSED" "$record does not name submission $id"
        src=$record
      fi
    fi
    cluster=""
    [[ -z $src ]] || cluster=$(jq -r '.emr.clusterId // ""' "$src")
    if [[ $cluster =~ ^j-[A-Z0-9]{8,20}$ && $stp =~ ^s-[A-Z0-9]+$ ]]; then
      require_mirrored "$LOGS_BUCKET" "emr/$cluster/steps/$stp/"
      ((MIRRORED > 0)) || has_note "$id" || die "$EX_REFUSED" "submission $id: no step logs under emr/$cluster/steps/$stp/ in S3; write notes/$id.txt"
    elif [[ $cluster =~ ^j-[A-Z0-9]{8,20}$ ]]; then
      # Step id unknown: every step log of the cluster is this submission's candidate evidence.
      require_mirrored "$LOGS_BUCKET" "emr/$cluster/steps/"
      ((MIRRORED > 0)) || has_note "$id" || die "$EX_REFUSED" "submission $id: step id unknown and no step logs under emr/$cluster/steps/ in S3; write notes/$id.txt"
    else
      has_note "$id" || die "$EX_REFUSED" "submission $id: no cluster id to locate step logs; write notes/$id.txt"
      note "submission $id: step logs cannot be located, waived by notes/$id.txt"
    fi
    ok "evidence of submission $id (every object listed in S3 is archived)"
  done
  [[ -n $ids ]] || note "no submission under s3://$DATA_BUCKET/submissions/: no run evidence to check"
  want="$EVIDENCE_DIR/listings/output-spark-prefixes.txt"
  [[ -f $want && ! -L $want ]] || die "$EX_REFUSED" "evidence missing: $want (the run=/load= listing of output/spark/)"
  aws_ro s3api list-objects-v2 --bucket "$DATA_BUCKET" --prefix output/spark/ --delimiter / --max-items 1000 >"$EVIDENCE_TMP/out.json" ||
    die "$EX_AWS" "list of s3://$DATA_BUCKET/output/spark/ failed"
  [[ $(jq -r '.NextToken // ""' "$EVIDENCE_TMP/out.json") == "" ]] || die "$EX_REFUSED" "more than 1000 prefixes under output/spark/"
  jq -r '.CommonPrefixes[]?.Prefix' "$EVIDENCE_TMP/out.json" | grep -E '^output/spark/(run|load)=' | LC_ALL=C sort >"$EVIDENCE_TMP/prefixes.txt" || true
  if [[ -s $EVIDENCE_TMP/prefixes.txt ]]; then
    cmp -s "$EVIDENCE_TMP/prefixes.txt" "$want" || die "$EX_REFUSED" "$want differs from the current run=/load= prefixes under output/spark/"
  else
    [[ ! -s $want ]] || die "$EX_REFUSED" "$want lists prefixes, but output/spark/ has no run= or load= prefix"
  fi
  rm -f "$EVIDENCE_TMP"/*
  rmdir "$EVIDENCE_TMP"
  ok "evidence archive $EVIDENCE_DIR holds every submission's objects and the output listing"
}
print_archive_commands() {
  # shellcheck disable=SC2016 # the variable name is printed for the human's own shell, not expanded here
  local a='"$SCHOLARCHAIN_EVIDENCE_ARCHIVE_DIR"' ro="aws --profile $READ_PROFILE --region $ENV_aws_region"
  say "NOTE ARCHIVE-COMMAND $ro s3 cp --recursive s3://$DATA_BUCKET/submissions/ $a/s3/$DATA_BUCKET/submissions/"
  say "NOTE ARCHIVE-COMMAND $ro s3 cp --recursive s3://$LOGS_BUCKET/spark-events/ $a/s3/$LOGS_BUCKET/spark-events/"
  say "NOTE ARCHIVE-COMMAND $ro s3 cp --recursive s3://$LOGS_BUCKET/emr/<cluster id>/steps/ $a/s3/$LOGS_BUCKET/emr/<cluster id>/steps/   (each cluster named by a run summary)"
  say "NOTE ARCHIVE-COMMAND mkdir -p $a/listings && $ro s3api list-objects-v2 --bucket $DATA_BUCKET --prefix output/spark/ --delimiter / --query 'CommonPrefixes[].Prefix' --output text | tr '\\t' '\\n' | grep -E '^output/spark/(run|load)=' | LC_ALL=C sort > $a/listings/output-spark-prefixes.txt"
}

case $ACTION in
  inventory)
    (($# == 2)) || [[ $# == 3 && $3 == after-base ]] || die "$EX_USAGE" "inventory takes no argument, or after-base once base is destroyed"
    step "inventory (read-only calls, profile $READ_PROFILE)"
    INV=""
    for s in emr-run base bootstrap; do INV="$INV stack:$s=$(stack_resources "$s")"; done
    for b in "$DATA_BUCKET" "$LOGS_BUCKET" "$STATE_BUCKET"; do INV="$INV bucket:$b=$(version_count "$b")"; done
    ACTIVE=$(aws_ro emr list-clusters --active | jq -r --arg n "scholarchain-$ARG_ENV-spark-run" '[.Clusters[] | select(.Name == $n) | .Id] | join(",")')
    INV="$INV active_clusters=${ACTIVE:-none}"
    for item in $INV; do say "INVENTORY $item"; done
    print_archive_commands
    say "CONFIRMATION-TOKEN empty-buckets base $(token_for empty-buckets base "$INV")"
    say "CONFIRMATION-TOKEN empty-buckets bootstrap $(token_for empty-buckets bootstrap "$INV")"
    note "an empty-buckets token is valid only while this inventory is unchanged (it is re-listed and compared)"
    say "RESULT INVENTORY-OK"
    ;;
  plan-destroy)
    (($# == 3)) || die "$EX_USAGE" "plan-destroy <emr-run|base|bootstrap>"
    STACK=$3
    DIR=$(stack_dir "$ARG_ENV" "$STACK")
    [[ $STACK != base ]] || require_destroyed emr-run
    [[ $STACK != bootstrap ]] || require_destroyed emr-run base
    tf_env "$READ_PROFILE"
    tf_init "$STACK" "$DIR"
    rm -f "$DIR/destroy.tfplan"
    terraform -chdir="$DIR" plan -destroy -input=false -lock=false -no-color -out=destroy.tfplan ||
      die "$EX_RUN" "destroy plan failed in $DIR"
    plan_summary "$DIR" destroy.tfplan
    SUM=$(sha256_file "$DIR/destroy.tfplan")
    say "PLAN-SHA256 $SUM $DIR/destroy.tfplan"
    say "CONFIRMATION-TOKEN apply-destroy $STACK $(token_for apply-destroy "$STACK" "$SUM")"
    say "RESULT DESTROY-PLAN-OK"
    ;;
  apply-destroy)
    (($# == 4)) || die "$EX_USAGE" "apply-destroy <emr-run|base|bootstrap> <plan sha256>"
    STACK=$3
    need_hex "plan checksum" "$4" 64
    DIR=$(stack_dir "$ARG_ENV" "$STACK")
    [[ -f $DIR/destroy.tfplan && $(sha256_file "$DIR/destroy.tfplan") == "$4" ]] || die "$EX_REFUSED" "$DIR/destroy.tfplan does not have sha256 $4"
    need_token apply-destroy "$STACK" "$4"
    [[ $STACK != base ]] || require_destroyed emr-run
    [[ $STACK != bootstrap ]] || require_destroyed emr-run base
    if [[ $STACK == base ]]; then
      for b in "$DATA_BUCKET" "$LOGS_BUCKET"; do
        [[ $(version_count "$b") == 0 ]] || die "$EX_REFUSED" "bucket $b is not empty; run empty-buckets base first"
      done
    fi
    tf_env "$ENV_aws_deploy_profile"
    terraform -chdir="$DIR" apply -input=false -no-color destroy.tfplan || die "$EX_RUN" "destroy apply failed in $DIR"
    rm -f "$DIR/destroy.tfplan"
    say "RESULT DESTROYED stack=$STACK at=$(now_utc)"
    ;;
  empty-buckets)
    (($# == 3)) || die "$EX_USAGE" "empty-buckets <base|bootstrap>"
    TARGET=$3
    case $TARGET in
      base) BUCKETS="$DATA_BUCKET $LOGS_BUCKET" ;;
      bootstrap) BUCKETS=$STATE_BUCKET ;;
      *) die "$EX_USAGE" "empty-buckets target must be base or bootstrap" ;;
    esac
    if [[ $TARGET == base ]]; then
      require_destroyed emr-run
      require_run_evidence
    else
      require_destroyed emr-run base
      require_evidence_dir
      [[ -f $EVIDENCE_DIR/listings/output-spark-prefixes.txt ]] ||
        die "$EX_REFUSED" "$EVIDENCE_DIR does not hold the base evidence (listings/output-spark-prefixes.txt); empty-buckets base checks it first"
      ok "evidence archive $EVIDENCE_DIR (base evidence checked when base was emptied)"
    fi
    INV=""
    for s in emr-run base bootstrap; do INV="$INV stack:$s=$(stack_resources "$s")"; done
    for b in "$DATA_BUCKET" "$LOGS_BUCKET" "$STATE_BUCKET"; do INV="$INV bucket:$b=$(version_count "$b")"; done
    ACTIVE=$(aws_ro emr list-clusters --active | jq -r --arg n "scholarchain-$ARG_ENV-spark-run" '[.Clusters[] | select(.Name == $n) | .Id] | join(",")')
    INV="$INV active_clusters=${ACTIVE:-none}"
    need_token empty-buckets "$TARGET" "$INV"
    BATCH_DIR=$(mktemp -d "${TMPDIR:-/tmp}/scholarchain-cleanup.XXXXXXXX")
    BATCH_FILE="$BATCH_DIR/batch.json"
    for b in $BUCKETS; do
      step "delete every object version and delete marker in $b"
      batch=0
      while :; do
        LIST=$(aws_rw s3api list-object-versions --bucket "$b" --max-keys 1000 --no-paginate) || die "$EX_AWS" "list-object-versions $b failed"
        printf '%s' "$LIST" | jq -c '{Objects: [(.Versions // [])[], (.DeleteMarkers // [])[] | {Key, VersionId}], Quiet: true}' >"$BATCH_FILE"
        n=$(jq '.Objects | length' "$BATCH_FILE")
        if ((n == 0)); then break; fi
        batch=$((batch + 1))
        ((batch <= HC_MAX_DELETE_BATCHES)) || die "$EX_REFUSED" "more than $HC_MAX_DELETE_BATCHES batches in $b; stop and review"
        aws_rw s3api delete-objects --bucket "$b" --delete "file://$BATCH_FILE" >/dev/null ||
          die "$EX_AWS" "delete-objects in $b failed"
        say "DELETED bucket=$b batch=$batch versions=$n"
      done
      ok "bucket $b is empty"
    done
    rm -f "$BATCH_FILE"
    rmdir "$BATCH_DIR"
    say "RESULT BUCKETS-EMPTIED target=$TARGET at=$(now_utc)"
    ;;
  *) die "$EX_USAGE" "unknown action '$ACTION' (inventory, plan-destroy, apply-destroy, empty-buckets)" ;;
esac
