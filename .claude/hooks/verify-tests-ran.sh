#!/usr/bin/env bash
# verify-tests-ran.sh -- Stop and SubagentStop hook.
#
# Refuses completion while source changes are newer than the repository test
# marker .claude/.last-test-run (written only by the sbt markTestRun task, which
# 'sbt check' runs after tests pass).
#
# Scope: uncommitted (modified, added, untracked, renamed, deleted) source files
# -- *.scala *.sc *.sbt *.java, project/**, .scalafmt.conf, prompts/** --
# restricted to the acting identity's owned territory (docs/agents.md). The
# orchestrator (main thread) is checked against the whole repository. Identities
# that own no source (e.g. code-reviewer, deploy-engineer, factory-engineer) are
# therefore never blocked by another agent's stale changes.
#
# Stop/SubagentStop also refuse when the test marker changed after the last audited Bash call of
# the session (R1-b; compared with the audit baseline in lib-baseline.sh; skipped when no baseline).
#
# This hook blocks even when stop_hook_active is true: tests are a hard gate.
# The human can always interrupt the session.
# Exit 0 = allow stop; exit 2 = refuse stop with instructions on stderr.
set -u

HOOK_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib-ownership.sh
. "$HOOK_DIR/lib-ownership.sh"

# --check: read-only mode for Bash use. Reads no stdin, checks the whole repository (orchestrator
# scope), prints OK or the stale list, exit 0 = fresh, 2 = stale. It sees ONLY uncommitted changes
# (git status), so on a clean tree it always reports OK: it is NOT evidence that a commit was tested
# and is not a /deploy gate (review finding R2). Binding the marker to a commit is a Phase 2
# build-engineer item (markTestRun).
check_mode=0
if [ "${1:-}" = "--check" ]; then
  check_mode=1; identity="ORCHESTRATOR"
else
  payload="$(cat)"
  agent_type="$(scholarchain_json_get "$payload" '.agent_type' 2>/dev/null)"
  identity="$(scholarchain_identity "$agent_type")"
fi

root="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
cd "$root" 2>/dev/null || exit 0
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || exit 0

marker=".claude/.last-test-run"

# R1-b: Stop/SubagentStop only. If this session has an audit baseline, the test marker must still equal
# its baseline entry (content, mode, size, mtime). The baseline is updated only by audited Bash calls
# (an audited `sbt check` moves it) and by post-write.sh, so a mismatch means the marker changed after
# the last audited call -- e.g. a delayed background touch by test code. Refuses only on a definite
# mismatch; no baseline (no Bash call yet), python3 missing, or an unreadable/corrupt baseline: this
# check is skipped (the next Bash call's audit fails closed on a corrupt baseline).
if [ "$check_mode" = 0 ] && command -v python3 >/dev/null 2>&1 && [ -f "$HOOK_DIR/lib-baseline.sh" ]; then
  # shellcheck source=lib-baseline.sh
  . "$HOOK_DIR/lib-baseline.sh"
  if baseline_init "$(scholarchain_json_get "$payload" '.session_id' 2>/dev/null)" 2>/dev/null && [ -f "$base" ]; then
    baseline_marker_check 2>/dev/null; mrc=$?
    if [ "$mrc" -eq 1 ]; then
      {
        echo "BLOCKED by .claude/hooks/verify-tests-ran.sh: completion refused for '$identity'."
        echo "The test marker $marker changed after the last audited Bash call of this session, without"
        echo "an audited 'sbt check' (for example a delayed background process started by test code)."
        echo "Possible test-marker forgery: the marker cannot be trusted as evidence that tests passed."
        echo "Do: stop. Return STATUS: BLOCKED-ON with BLOCKED-ON OWNER: human and this message as"
        echo "BLOCKED-ON EVIDENCE. Do not touch the marker. The human reviews it; the next audited Bash call"
        echo "reports the change as UNATTRIBUTED and resets the baseline, and a fresh 'sbt check' re-records the marker."
      } >&2
      exit 2
    fi
  fi
fi

is_source() {
  case "$1" in
    */target/*|target/*|.bsp/*|.metals/*|.bloop/*) return 1 ;;
    *.scala|*.sc|*.sbt|*.java|project/*|.scalafmt.conf|prompts/*) return 0 ;;
  esac
  return 1
}

in_scope() {
  [ "$identity" = "ORCHESTRATOR" ] && return 0
  [ "$(scholarchain_owner_of "$1")" = "$identity" ]
}

stale=""
count=0
note_stale() {
  count=$((count + 1))
  [ "$count" -le 20 ] && stale="$stale
  - $1"
}

while IFS= read -r -d '' entry; do
  xy="${entry:0:2}"
  path="${entry:3}"
  case "$xy" in
    R*|C*) IFS= read -r -d '' _orig || true ;;
  esac
  is_source "$path" || continue
  in_scope "$path" || continue
  if [ ! -f "$marker" ]; then
    note_stale "$path (no test marker exists)"
  elif [ -e "$path" ]; then
    [ "$path" -nt "$marker" ] && note_stale "$path"
  else
    dir="$(dirname "$path")"
    if [ ! -d "$dir" ] || [ "$dir" -nt "$marker" ]; then
      note_stale "$path (deleted)"
    fi
  fi
done < <(git -c core.fsmonitor=false status --porcelain=v1 -z --untracked-files=all 2>/dev/null)

if [ "$count" -eq 0 ]; then
  [ "$check_mode" = 1 ] && echo "verify-tests-ran --check: OK (no uncommitted source change is newer than $marker)"
  exit 0
fi

{
  echo "BLOCKED by .claude/hooks/verify-tests-ran.sh: completion refused for '$identity'."
  echo "$count source change(s) are newer than the test marker $marker:$stale"
  [ "$count" -gt 20 ] && echo "  ... and $((count - 20)) more"
  echo "Do: run 'sbt check' from the repository root. It runs format check, compile,"
  echo "tests, AWS-free contract tests, and markTestRun (which refreshes the marker"
  echo "only when tests pass). Then report the exact pass/fail counts you observed."
  echo "If tests fail and you cannot fix them inside your owned artifacts, report"
  echo "STATUS: FAILED or BLOCKED-ON with the failing output; do not touch the marker."
  if [ ! -f build.sbt ]; then
    echo "Note: build.sbt does not exist yet (pre-Phase 2). Source files must not be"
    echo "created before build-engineer delivers the build; report BLOCKED-ON build-engineer."
  fi
} >&2
exit 2
