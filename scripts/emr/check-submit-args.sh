#!/usr/bin/env bash
# =====================================================================================================
# scripts/emr/check-submit-args.sh -- local self-check of the EMR step wrapper's argument construction
# (CR-P6-27 M1). Owner: infra-engineer. No AWS, HDFS or network call; writes nothing.
#
# USAGE   scripts/emr/check-submit-args.sh
#
# Runs `submit-collaboration-job.sh --self-check <fs.defaultFS>` for accepted and refused sample
# fs.defaultFS values, checks the exit status of each, and for accepted values checks that the
# --checkpoint-dir argument is the full hdfs://<authority>/scholarchain/checkpoints/<id> URI (never
# hdfs:///) and that spark-submit runs under timeout. Prints the argv of the first accepted case.
# MARKERS  [check-submit-args] CASE ok / CASE FAIL / RESULT PASS|FAIL
# EXIT     0 all cases as expected, 1 otherwise
# =====================================================================================================
set -euo pipefail
set -f # the sample values contain glob characters; never expand them
readonly SCRIPT_NAME=check-submit-args
WRAPPER="$(cd "$(dirname "$0")" && pwd -P)/submit-collaboration-job.sh"
readonly WRAPPER
[[ -f $WRAPPER ]] || {
  printf '[%s] FAIL wrapper %s missing\n' "$SCRIPT_NAME" "$WRAPPER" >&2
  exit 1
}

ACCEPT="hdfs://ip-10-42-0-10.ec2.internal:8020 hdfs://ip-10-42-0-10.ec2.internal:8020/ hdfs://namenode hdfs://10.42.0.10:8020"
REFUSE="hdfs:/// hdfs:// hdfs://:8020 file:/// s3a://bucket hdfs://host:8020/path hdfs://host:8020// \
hdfs://host:0 hdfs://host:65536 hdfs://host:80:90 hdfs://-host hdfs://host- hdfs://ho_st \
hdfs://host,other hdfs://user@host hdfs://ho*st hdfs://[::1]:8020 HDFS://host"
FAILURES=0
PRINTED=no

for fs in $ACCEPT; do
  if out=$(bash "$WRAPPER" --self-check "$fs" 2>&1); then
    ck=$(printf '%s\n' "$out" | awk '/ ARGV [0-9]+ --checkpoint-dir$/ { getline; print $4 }')
    auth=${fs#hdfs://}
    auth=${auth%/}
    want="hdfs://$auth/scholarchain/checkpoints/a1-0123456789abcdef"
    first=$(printf '%s\n' "$out" | awk '/ ARGV 000 / { print $4 }')
    if [[ $ck == "$want" && $first == /usr/bin/timeout ]]; then
      printf '[%s] CASE ok accepted %s checkpoint=%s\n' "$SCRIPT_NAME" "$fs" "$ck"
      if [[ $PRINTED == no ]]; then
        printf '%s\n' "$out"
        PRINTED=yes
      fi
    else
      printf '[%s] CASE FAIL %s: checkpoint=%s (want %s), argv[0]=%s\n' "$SCRIPT_NAME" "$fs" "$ck" "$want" "$first" >&2
      FAILURES=$((FAILURES + 1))
    fi
  else
    printf '[%s] CASE FAIL %s refused but must be accepted:\n%s\n' "$SCRIPT_NAME" "$fs" "$out" >&2
    FAILURES=$((FAILURES + 1))
  fi
done

for fs in $REFUSE; do
  rc=0
  out=$(bash "$WRAPPER" --self-check "$fs" 2>&1) || rc=$?
  if ((rc == 3)); then
    printf '[%s] CASE ok refused %s: %s\n' "$SCRIPT_NAME" "$fs" "$(printf '%s' "$out" | tail -n 1)"
  else
    printf '[%s] CASE FAIL %s: exit %s, expected 3\n' "$SCRIPT_NAME" "$fs" "$rc" >&2
    FAILURES=$((FAILURES + 1))
  fi
done

if ((FAILURES == 0)); then
  printf '[%s] RESULT PASS\n' "$SCRIPT_NAME"
  exit 0
fi
printf '[%s] RESULT FAIL failures=%s\n' "$SCRIPT_NAME" "$FAILURES" >&2
exit 1
