#!/usr/bin/env bash
# =====================================================================================================
# scripts/emr/check-submit-args.sh -- local self-check of the EMR step wrapper's argument construction
# (CR-P6-27 M1) and Java 17 selection (IE-P6-12). Owner: infra-engineer. No AWS, HDFS or network
# call; writes only stub JVMs in one temporary directory under TMPDIR, removed on exit.
#
# USAGE   scripts/emr/check-submit-args.sh
#
# Runs `submit-collaboration-job.sh --self-check <fs.defaultFS> <stub JVM root>` in the attempt-2
# environment (JAVA_HOME and the first PATH java a stub reporting 1.8.0) for accepted and refused sample
# fs.defaultFS values, checks the exit status of each, and for accepted values checks that the
# --checkpoint-dir argument is the full hdfs://<authority>/scholarchain/checkpoints/<id> URI (never
# hdfs:///), that spark-submit runs under timeout, and that JAVA_HOME=, spark.yarn.appMasterEnv.JAVA_HOME=
# and spark.executorEnv.JAVA_HOME= equal the home that the wrapper's own select_java17 chose on the stub
# root (root S: <S>/java-17-amazon-corretto.x86_64 via the jre-17 link; root S2: <S2>/jre-17), so the
# selected home -> argv link is tested (CR-P6-49 m2); a root without Java 17 -> exit 4 and no argv.
# Prints the argv of the first accepted case.
# Then runs `submit-collaboration-job.sh --check-java <stub JVM root> [<bound>]` in the same environment
# against stub JVM roots: (a) jre-17 link to a Java 17 stub beside Corretto 8 -> selected, and the old
# selection (readlink -f of the PATH java) shown to yield 1.8 -> exit 4; (a2) second candidate; (a3) bad
# first candidate skipped; (b) no Java 17 -> exit 4; (c) candidates report 1.8 or 21 -> exit 4; (d)
# jre-17 a symlink outside the JVM root -> exit 4; (e) java -version bound 1 s with a stub that sleeps
# 30 s: (e1) hanging first candidate skipped, second selected, < 10 s; (e2) only candidate hangs -> exit
# 4 with "timed out after 1s", < 10 s; (e3) bound 0 -> exit 2.
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

# ---- stub JVMs in one temporary directory, removed on exit (IE-P6-12) -----------------------------
JT=""
# shellcheck disable=SC2329 # invoked by the EXIT trap below
cleanup_java() {
  [[ -n $JT && -d $JT && ${JT##*/} == scholarchain-check-java.* ]] || return 0
  find "$JT" -depth ! -type d -exec rm -f {} + 2>/dev/null || true
  find "$JT" -depth -type d -exec rmdir {} + 2>/dev/null || true
}
trap cleanup_java EXIT
jt_raw=$(mktemp -d "${TMPDIR:-/tmp}/scholarchain-check-java.XXXXXXXX")
JT=$jt_raw
# canonical, so readlink -f results compare as strings; on failure JT keeps jt_raw for the cleanup (CR-P6-49 n1)
jt_canon=$(cd "$jt_raw" && pwd -P) || {
  printf '[%s] FAIL cannot canonicalize %s\n' "$SCRIPT_NAME" "$jt_raw" >&2
  exit 1
}
JT=$jt_canon
case_ok() { printf '[%s] CASE ok %s\n' "$SCRIPT_NAME" "$*"; }
case_fail() {
  printf '[%s] CASE FAIL %s\n' "$SCRIPT_NAME" "$*" >&2
  FAILURES=$((FAILURES + 1))
}
stub_java() { # stub_java <java home> <version>: <java home>/bin/java prints `openjdk version "<v>"` on stderr
  mkdir -p "$1/bin"
  printf '%s\n' '#!/bin/sh' "echo 'openjdk version \"$2\" 2024-10-15 LTS' >&2" >"$1/bin/java"
  chmod 0755 "$1/bin/java"
}
stub_java_hang() { # stub_java_hang <java home>: <java home>/bin/java never answers (exec, so the kill ends it)
  mkdir -p "$1/bin"
  printf '%s\n' '#!/bin/sh' 'exec sleep 30' >"$1/bin/java"
  chmod 0755 "$1/bin/java"
}
# The inherited step environment of attempt 2: JAVA_HOME and the first PATH java are Corretto 8.
INHERITED=$JT/inherited/java-1.8.0-amazon-corretto.x86_64/jre
stub_java "$INHERITED" 1.8.0_432
# Self-check JVM roots (CR-P6-49 m2): S as the attempt-2 node (jre-17 -> Corretto 17, Corretto 8 beside);
# S2 with jre-17 a plain directory, so the expected home differs; S0 without Java 17.
S=$JT/s
stub_java "$S/java-1.8.0-amazon-corretto.x86_64/jre" 1.8.0_432
stub_java "$S/java-17-amazon-corretto.x86_64" 17.0.13
ln -s java-17-amazon-corretto.x86_64 "$S/jre-17"
S2=$JT/s2
stub_java "$S2/jre-17" 17.0.13
S0=$JT/s0
stub_java "$S0/java-1.8.0-amazon-corretto.x86_64/jre" 1.8.0_432
NOT17='FAIL exit=4 java on the primary node is not 17 ('
self_check() { # self_check <fs.defaultFS> <JVM root>: runs the wrapper's --self-check in the attempt-2 environment
  env JAVA_HOME="$INHERITED" PATH="$INHERITED/bin:$PATH" bash "$WRAPPER" --self-check "$1" "$2" 2>&1
}
accept_case() { # accept_case <fs.defaultFS> <JVM root> <expected selected java home>
  local fs=$1 home=$3 out ck auth want first jh pins
  if out=$(self_check "$fs" "$2"); then
    ck=$(printf '%s\n' "$out" | awk '/ ARGV [0-9]+ --checkpoint-dir$/ { getline; print $4 }')
    auth=${fs#hdfs://}
    auth=${auth%/}
    want="hdfs://$auth/scholarchain/checkpoints/a1-0123456789abcdef"
    first=$(printf '%s\n' "$out" | awk '/ ARGV 000 / { print $4 }')
    jh=$(printf '%s\n' "$out" | awk '/ ARGV [0-9]+ JAVA_HOME=/ { print $4 }')
    pins=$(printf '%s\n' "$out" | awk -v w="$home" '$2 == "ARGV" && ($4 == "spark.executorEnv.JAVA_HOME=" w || $4 == "spark.yarn.appMasterEnv.JAVA_HOME=" w) { n++ } END { print n + 0 }')
    if [[ $ck == "$want" && $first == /usr/bin/timeout && $jh == "JAVA_HOME=$home" && $pins == 2 ]]; then
      case_ok "accepted $fs checkpoint=$ck java_home=$home (selected by select_java17 on $2)"
      if [[ $PRINTED == no ]]; then
        printf '%s\n' "$out"
        PRINTED=yes
      fi
    else
      case_fail "$fs on $2: checkpoint=$ck (want $want), argv[0]=$first, ${jh:-no JAVA_HOME} (want JAVA_HOME=$home), AM/executor JAVA_HOME pins=$pins (want 2)"
    fi
  else
    case_fail "$fs on $2 refused but must be accepted:"
    printf '%s\n' "$out" >&2
  fi
}

for fs in $ACCEPT; do
  accept_case "$fs" "$S" "$S/java-17-amazon-corretto.x86_64"
done
accept_case hdfs://ip-10-42-0-10.ec2.internal:8020 "$S2" "$S2/jre-17"

for fs in $REFUSE; do
  rc=0
  out=$(self_check "$fs" "$S") || rc=$?
  if ((rc == 3)); then
    case_ok "refused $fs: $(printf '%s' "$out" | tail -n 1)"
  else
    case_fail "$fs: exit $rc, expected 3"
  fi
done
# A valid fs.defaultFS on a root without Java 17: the self-check's selection really runs, exit 4.
rc=0
out=$(self_check hdfs://ip-10-42-0-10.ec2.internal:8020 "$S0") || rc=$?
if ((rc == 4)) && [[ $out == *"$NOT17"* && $out != *" ARGV "* ]]; then
  case_ok "self-check without Java 17: exit 4, no argv: $(printf '%s' "$out" | tail -n 1)"
else
  case_fail "self-check without Java 17: exit $rc (want 4, no ARGV lines):"
  printf '%s\n' "$out" >&2
fi

# ---- Java 17 selection (IE-P6-12) ------------------------------------------------------------------
java_case() { # java_case <label> <JVM root> <expected exit> <expected text> [<java -version bound>]
  local rc=0 out
  out=$(env JAVA_HOME="$INHERITED" PATH="$INHERITED/bin:$PATH" bash "$WRAPPER" --check-java "$2" ${5:+"$5"} 2>&1) || rc=$?
  if ((rc == $3)) && [[ $out == *"$4"* ]]; then
    case_ok "java $1: exit $rc: $(printf '%s' "$out" | tail -n 1)"
  else
    case_fail "java $1: exit $rc (want $3), output must contain '$4':"
    printf '%s\n' "$out" >&2
  fi
}

# (a) the attempt-2 node: jre-17 -> java-17-amazon-corretto.x86_64 (17), Corretto 8 beside it.
A=$JT/a
stub_java "$A/java-1.8.0-amazon-corretto.x86_64/jre" 1.8.0_432
stub_java "$A/java-17-amazon-corretto.x86_64" 17.0.13
ln -s java-17-amazon-corretto.x86_64 "$A/jre-17"
# The old selection (readlink -f of the PATH java, as before IE-P6-12) on the same environment:
old_bin=$(
  PATH="$INHERITED/bin:$PATH"
  readlink -f "$(command -v java)"
)
old_ver=$("$old_bin" -version 2>&1) || old_ver="java -version failed"
if [[ $old_bin == "$INHERITED/bin/java" && $old_ver == *'version "1.8.'* ]]; then
  case_ok "java (a) old selection: PATH java resolves to $old_bin, '$old_ver' -> not 17, the old wrapper refused with exit 4 (attempt 2)"
else
  case_fail "java (a) old selection: expected $INHERITED/bin/java reporting 1.8, got '$old_bin' '$old_ver'"
fi
java_case "(a) new selection ignores the inherited Java 8" "$A" 0 "java_home=$A/java-17-amazon-corretto.x86_64"
java_case "(a) first candidate jre-17 chosen" "$A" 0 "candidate $A/jre-17/bin/java"
# (a2) only the Corretto 17 directory, no jre-17 link: the second candidate.
A2=$JT/a2
stub_java "$A2/java-17-amazon-corretto.x86_64" 17.0.13
java_case "(a2) second candidate" "$A2" 0 "java_home=$A2/java-17-amazon-corretto.x86_64"
# (a3) jre-17 reports 21, Corretto 17 directory reports 17: the bad first candidate is skipped.
A3=$JT/a3
stub_java "$A3/jre-17" 21.0.5
stub_java "$A3/java-17-amazon-corretto.x86_64" 17.0.13
java_case "(a3) bad first candidate skipped" "$A3" 0 "java_home=$A3/java-17-amazon-corretto.x86_64"
# (b) no Java 17 at any candidate (only Corretto 8 under the root).
B=$JT/b
stub_java "$B/java-1.8.0-amazon-corretto.x86_64/jre" 1.8.0_432
java_case "(b) no candidate" "$B" 4 "$NOT17"
# (c) candidates exist but report 1.8 or 21.
C1=$JT/c1
stub_java "$C1/jre-17" 1.8.0_432
java_case "(c1) candidate reports 1.8" "$C1" 4 "reports 'openjdk version \"1.8.0_432\""
C2=$JT/c2
stub_java "$C2/jre-17" 21.0.5
stub_java "$C2/java-17-amazon-corretto.x86_64" 21.0.5
java_case "(c2) candidates report 21" "$C2" 4 "reports 'openjdk version \"21.0.5\""
# (d) jre-17 is a symlink to a Java 17 outside the JVM root.
D=$JT/d
mkdir -p "$D"
stub_java "$JT/outside/jdk-17" 17.0.13
ln -s "$JT/outside/jdk-17" "$D/jre-17"
java_case "(d) symlink outside the JVM root" "$D" 4 "resolves to $JT/outside/jdk-17/bin/java, outside $D"
java_case "(d) refusal text" "$D" 4 "$NOT17"
# (e) java -version bound (CR-P6-49 m1). Stubs `exec sleep 30`; bound lowered to 1 s for speed (the
# node uses 10 s, the same code path). Each case must end well before the stub would (< 10 s).
E1=$JT/e1
stub_java_hang "$E1/jre-17"
stub_java "$E1/java-17-amazon-corretto.x86_64" 17.0.13
t0=$SECONDS
java_case "(e1) hanging first candidate skipped" "$E1" 0 "java_home=$E1/java-17-amazon-corretto.x86_64" 1
el=$((SECONDS - t0))
if ((el < 10)); then case_ok "java (e1) bounded: ${el}s"; else case_fail "java (e1) took ${el}s (want < 10)"; fi
E2=$JT/e2
stub_java_hang "$E2/jre-17"
t0=$SECONDS
java_case "(e2) only candidate hangs" "$E2" 4 "$E2/jre-17/bin/java: java -version timed out after 1s" 1
el=$((SECONDS - t0))
if ((el < 10)); then case_ok "java (e2) bounded: ${el}s"; else case_fail "java (e2) took ${el}s (want < 10)"; fi
java_case "(e2) refusal text" "$E2" 4 "$NOT17" 1
java_case "(e3) bound outside 1..10 refused" "$A" 2 "bound '0' is not an integer in 1..10" 0

if ((FAILURES == 0)); then
  printf '[%s] RESULT PASS\n' "$SCRIPT_NAME"
  exit 0
fi
printf '[%s] RESULT FAIL failures=%s\n' "$SCRIPT_NAME" "$FAILURES" >&2
exit 1
