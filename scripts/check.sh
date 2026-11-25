#!/usr/bin/env bash
# =====================================================================================================
# scripts/check.sh -- full non-cloud validation of the repository. Owner: infra-engineer (IE-P13-01).
#
# USAGE   sbt check                   (FIRST, as its own command: writes the test marker)
#         scripts/check.sh            (THEN; no arguments; run from the repository root)
#
# ORDER   sbt is NOT run here (IE-P13-03, CR-P13-01 M1): the shell-write audit accepts a change to
#         .claude/.last-test-run only when the Bash command is exactly `sbt check` or `sbt clean check`,
#         so running sbt inside this script would be reported as marker forgery. Run `sbt check` first
#         as its own command, then this script, which only READS the marker (test-marker gate).
#
# Runs every gate below, in order, even after an earlier gate fails, then prints a result table and
# exits non-zero if any gate failed. Makes NO AWS call, no network call and no Terraform
# init/plan/apply.
#   constitution   .claude/hooks/validate-agent-files.sh (when present and executable)
#   json           python3 -m json.tool on .claude/settings.json and .mcp.json (output discarded)
#   hook-syntax    bash -n on every .claude/hooks/*.sh
#   test-marker    read-only check of .claude/.last-test-run: a regular file written by
#                  `sbt markTestRun` (scholarchain-test-marker-v1, written_by=sbt markTestRun),
#                  tests_failed=0, tests_errored=0, git_head equal to the current HEAD, the working
#                  tree clean NOW (git status --porcelain, tracked and untracked, ignored excluded),
#                  and no tracked file under modules/, project/, build.sbt, tests/ newer than the
#                  marker (CR-P13-02 m-A). The marker is never written, touched or created here.
#   tf-fmt         terraform -chdir=infra/terraform fmt -check -recursive
#   tf-validate    terraform validate of each stack ALREADY initialized (has .terraform/); a stack that
#                  is not initialized is reported SKIP (init needs the registry and is not done here)
#   script-syntax  bash -n on every scripts/**/*.sh
#   lint           ShellCheck over every scripts/**/*.sh (scripts/.shellcheckrc applies)
#   emr-args       scripts/emr/check-submit-args.sh (local argument-construction self-check)
#   runtime        python3 -B scripts/runtime/check-offline.py (canned fixtures, no AWS SDK)
# A missing tool is a FAIL of its gate, never a SKIP. The script itself writes nothing; the gate
# programs it calls behave as they do when run alone (python3 -B writes no bytecode).
#
# MARKERS  [check] STEP / GATE <name> PASS|FAIL|SKIP <detail> / RESULT CHECK-OK|CHECK-FAILED;
#          [check] FAIL exit=<n> on stderr.
# EXIT     0 every gate passed or skipped, 2 usage, 6 at least one gate failed
# =====================================================================================================
set -euo pipefail
readonly SCRIPT_NAME=check
# shellcheck source=scripts/lib/common.sh disable=SC1091
. "$(dirname "$0")/lib/common.sh"

(($# == 0)) || die "$EX_USAGE" "usage: scripts/check.sh (no arguments)"
need_tools git
step "start at=$(now_utc)"
require_repo_root

RESULTS=""
FAILED=0
gate() { # gate <name> <PASS|FAIL|SKIP> <detail>
  say "GATE $1 $2 $3"
  RESULTS="$RESULTS$1|$2|$3"$'\n'
  if [[ $2 == FAIL ]]; then FAILED=$((FAILED + 1)); fi
}
have() { command -v "$1" >/dev/null 2>&1; }
# run_gate <name> <command...>: runs the command with its output visible, records PASS or FAIL.
run_gate() {
  local name=$1 rc=0
  shift
  step "$name: $*"
  "$@" || rc=$?
  if ((rc == 0)); then gate "$name" PASS "exit=0"; else gate "$name" FAIL "exit=$rc ($*)"; fi
}

# Collect tracked shell scripts (Bash 3.2: no mapfile).
SCRIPTS=()
while IFS= read -r f; do
  [[ -n $f ]] && SCRIPTS+=("$f")
done < <(git ls-files -- 'scripts/*.sh' 'scripts/**/*.sh' | LC_ALL=C sort -u)
HOOKS=()
while IFS= read -r f; do
  [[ -n $f ]] && HOOKS+=("$f")
done < <(git ls-files -- '.claude/hooks/*.sh' | LC_ALL=C sort -u)

# ---- constitution ----------------------------------------------------------------------------------
if [[ -x .claude/hooks/validate-agent-files.sh ]]; then
  run_gate constitution .claude/hooks/validate-agent-files.sh
else
  gate constitution FAIL ".claude/hooks/validate-agent-files.sh is missing or not executable"
fi

# ---- JSON configuration ----------------------------------------------------------------------------
if have python3; then
  for j in .claude/settings.json .mcp.json; do
    if [[ -f $j ]]; then
      step "json: $j"
      if python3 -I -m json.tool "$j" >/dev/null; then gate "json:$j" PASS "parses"; else gate "json:$j" FAIL "does not parse"; fi
    else
      gate "json:$j" SKIP "absent"
    fi
  done
else
  gate json FAIL "python3 is not installed"
fi

# ---- hook syntax -----------------------------------------------------------------------------------
if ((${#HOOKS[@]} == 0)); then
  gate hook-syntax FAIL "no tracked .claude/hooks/*.sh"
else
  HS_BAD=0
  for f in "${HOOKS[@]}"; do bash -n "$f" || HS_BAD=$((HS_BAD + 1)); done
  if ((HS_BAD == 0)); then gate hook-syntax PASS "${#HOOKS[@]} files"; else gate hook-syntax FAIL "$HS_BAD of ${#HOOKS[@]} files"; fi
fi

# ---- test marker (read-only; `sbt check` is run separately, BEFORE this script) --------------------
# marker_value <key>: prints the value of the single <key>=... line of the marker; fails unless the key
# appears exactly once.
MARKER=.claude/.last-test-run
marker_value() {
  awk -F= -v k="$1" '$1 == k { n++; v = substr($0, length(k) + 2) } END { if (n != 1) exit 1; print v }' "$MARKER"
}
step "test-marker: read $MARKER (never written here)"
MARKER_HINT="run \`sbt check\` first as its own command, then scripts/check.sh"
if [[ -L $MARKER || ! -f $MARKER ]]; then
  gate test-marker FAIL "$MARKER is missing or not a regular file; $MARKER_HINT"
else
  M_FIRST=$(head -n 1 "$MARKER" || true)
  M_BY=$(marker_value written_by || true)
  M_FAILED=$(marker_value tests_failed || true)
  M_ERRORED=$(marker_value tests_errored || true)
  M_HEAD=$(marker_value git_head || true)
  M_TREE=$(marker_value git_worktree || true)
  M_PASSED=$(marker_value tests_passed || true)
  if [[ $M_FIRST != scholarchain-test-marker-v1 || $M_BY != "sbt markTestRun" ]]; then
    gate test-marker FAIL "$MARKER was not written by sbt markTestRun (header or written_by mismatch); $MARKER_HINT"
  elif [[ $M_FAILED != 0 || $M_ERRORED != 0 ]]; then
    gate test-marker FAIL "$MARKER records tests_failed=${M_FAILED:-?} tests_errored=${M_ERRORED:-?}; $MARKER_HINT"
  elif [[ $M_HEAD != "$HEAD_SHA" ]]; then
    gate test-marker FAIL "$MARKER git_head=${M_HEAD:-?} differs from HEAD $HEAD_SHA; $MARKER_HINT"
  else
    # CR-P13-02 m-A: the marker binds HEAD only. A tree that is dirty NOW (tracked or untracked, ignored
    # paths excluded), or tracked source newer than the marker, means the tests may not cover it.
    TREE_HINT="tree changed since last sbt check; run \`sbt check\` then scripts/check.sh"
    M_DIRTY=$(git status --porcelain --untracked-files=all) || M_DIRTY="git status failed"
    # NUL-separated listing (any file name), and a failing git ls-files fails the gate (CR-P13-03 nit):
    # the pipeline runs under pipefail inside the substitution, and the loop never exits early, so git
    # never sees SIGPIPE.
    M_LS_RC=0
    M_NEWER=$(git ls-files -z -- modules project build.sbt tests | {
      first=""
      while IFS= read -r -d '' f; do
        if [[ -z $first && -n $f && -f $f && ! -L $f && $f -nt $MARKER ]]; then first=$f; fi
      done
      printf '%s' "$first"
    }) || M_LS_RC=$?
    if ((M_LS_RC != 0)); then
      gate test-marker FAIL "git ls-files over modules project build.sbt tests failed (exit $M_LS_RC); cannot compare source times with $MARKER"
    elif [[ -n $M_DIRTY ]]; then
      printf '%s\n' "$M_DIRTY" | head -n 20 >&2 || true
      gate test-marker FAIL "working tree is not clean now (git status --porcelain above; worktree=${M_TREE:-?} when written); $TREE_HINT"
    elif [[ -n $M_NEWER ]]; then
      gate test-marker FAIL "tracked source $M_NEWER is newer than $MARKER; $TREE_HINT"
    else
      if [[ $M_TREE != clean ]]; then
        note "$MARKER was written on a ${M_TREE:-unknown} working tree; the tree is clean now and no tracked source is newer"
      fi
      gate test-marker PASS "git_head=HEAD tests_passed=${M_PASSED:-?} tests_failed=0 tests_errored=0 worktree_now=clean"
    fi
  fi
fi

# ---- Terraform -------------------------------------------------------------------------------------
if have terraform; then
  run_gate tf-fmt terraform -chdir=infra/terraform fmt -check -recursive
  STACKS=()
  [[ -d infra/terraform/bootstrap ]] && STACKS+=(infra/terraform/bootstrap)
  for d in infra/terraform/environments/*/base infra/terraform/environments/*/emr-run infra/terraform/environments/*/runtime; do
    [[ -d $d ]] && STACKS+=("$d")
  done
  for d in "${STACKS[@]}"; do
    if [[ -d $d/.terraform && ! -L $d/.terraform ]]; then
      run_gate "tf-validate:$d" terraform -chdir="$d" validate -no-color
    else
      gate "tf-validate:$d" SKIP "not initialized (run: terraform -chdir=$d init -input=false -backend=false)"
    fi
  done
else
  gate tf-fmt FAIL "terraform is not installed"
fi

# ---- scripts ---------------------------------------------------------------------------------------
if ((${#SCRIPTS[@]} == 0)); then
  gate script-syntax FAIL "no tracked scripts/**/*.sh"
else
  SS_BAD=0
  for f in "${SCRIPTS[@]}"; do bash -n "$f" || SS_BAD=$((SS_BAD + 1)); done
  if ((SS_BAD == 0)); then gate script-syntax PASS "${#SCRIPTS[@]} files"; else gate script-syntax FAIL "$SS_BAD of ${#SCRIPTS[@]} files"; fi
  if have shellcheck; then
    run_gate lint shellcheck "${SCRIPTS[@]}"
  else
    gate lint FAIL "shellcheck is not installed"
  fi
fi
if [[ -x scripts/emr/check-submit-args.sh ]]; then
  run_gate emr-args scripts/emr/check-submit-args.sh
else
  gate emr-args FAIL "scripts/emr/check-submit-args.sh is missing or not executable"
fi

# ---- runtime offline checks ------------------------------------------------------------------------
if have python3; then
  # -B: no __pycache__ under scripts/runtime (the checks import the runtime modules by path).
  run_gate runtime python3 -B scripts/runtime/check-offline.py
else
  gate runtime FAIL "python3 is not installed"
fi

# ---- summary ---------------------------------------------------------------------------------------
step "summary"
printf '%s' "$RESULTS" | awk -F'|' -v s="$SCRIPT_NAME" 'NF >= 2 { printf "[%s] TABLE %-48s %-4s %s\n", s, $1, $2, $3 }'
if ((FAILED > 0)); then
  say "RESULT CHECK-FAILED failed_gates=$FAILED"
  die "$EX_VERIFY" "$FAILED gate(s) failed (GATE ... FAIL lines above)"
fi
say "RESULT CHECK-OK"
