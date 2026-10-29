#!/usr/bin/env bash
# enforce-ownership.sh -- PreToolUse hook for Write|Edit|MultiEdit|NotebookEdit.
#
# Enforces the one-writer rule from docs/agents.md: the acting identity
# (agent_type from the hook payload, or ORCHESTRATOR for the plain main thread)
# may write only paths it owns. Constitutional files are writable only by
# factory-engineer. Unowned paths fail closed.
#
# Exit 0 = allow; exit 2 = block (stderr is shown to the agent).
set -u

HOOK_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib-ownership.sh
. "$HOOK_DIR/lib-ownership.sh"

block() {
  {
    echo "BLOCKED by .claude/hooks/enforce-ownership.sh"
    echo "$1"
    echo "What to do: do not attempt a workaround. Return STATUS: BLOCKED-ON with"
    echo "BLOCKED-ON OWNER set to the owner named above and the change you need as"
    echo "BLOCKED-ON EVIDENCE. The orchestrator routes it (see docs/agents.md)."
  } >&2
  exit 2
}

payload="$(cat)"
tool_name="$(scholarchain_json_get "$payload" '.tool_name')" || block "No JSON parser (jq or python3) available; failing closed."
agent_type="$(scholarchain_json_get "$payload" '.agent_type')"
file_path="$(scholarchain_json_get "$payload" '.tool_input.file_path')"
[ -n "$file_path" ] || file_path="$(scholarchain_json_get "$payload" '.tool_input.notebook_path')"

[ -n "$file_path" ] || block "Tool '$tool_name' did not supply a file path; cannot verify ownership, failing closed."

# Identity (review finding NEW-6, same rule as guard-dangerous.sh): agent_type absent -> ORCHESTRATOR;
# present but empty, null, "null", a non-string, or not a ratified agent -> deny.
identity="$(scholarchain_identity_checked "$payload")" || block "Invalid acting identity: $identity.
A Write/Edit cannot be attributed to a ratified agent, so it is refused (fail closed). Run the work
through a ratified agent from docs/agents.md (Agent catalog), or from the orchestrator."
root="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
rel="$(scholarchain_relpath "$root" "$file_path")"

case "$rel" in
  OUTSIDE:*)
    abs="${rel#OUTSIDE:}"
    tmp="${TMPDIR:-/tmp}"; tmp="${tmp%/}"
    case "$abs" in
      "$tmp"/*|/tmp/*|/private/tmp/*|/private/var/folders/*) exit 0 ;;
    esac
    block "'$identity' may not write outside the repository: $abs
Only the session temp/scratch directory is writable outside the repo. User-level
settings (~/.claude), credentials (~/.aws), and shell profiles are never writable."
    ;;
esac

owner="$(scholarchain_owner_of "$rel")"

if [ "$owner" = "$identity" ]; then
  exit 0
fi
if scholarchain_skeleton_exempt "$identity" "$rel" "$owner"; then
  exit 0
fi

case "$owner" in
  factory-engineer)
    block "'$rel' is a constitutional file. Only factory-engineer may change it, and
the change takes effect only after human ratification and a session restart.
Acting identity: $identity. Owner: factory-engineer." ;;
  HUMAN)
    block "'$rel' is human-owned (human gate decisions, LICENSE, personal settings).
No agent or orchestrator tool call may write it. Acting identity: $identity. Owner: human architect." ;;
  GENERATED)
    block "'$rel' is the test marker written only by the sbt markTestRun task after
tests pass. Writing it by hand would forge test evidence. Run 'sbt check' instead.
Acting identity: $identity." ;;
  ORCHESTRATOR)
    block "'$rel' is phase evidence under docs/agent-runs/. Only the orchestrator
(main thread) may assemble it; subagents return their report as their final
message instead. Acting identity: $identity. Owner: orchestrator (human-owned evidence)." ;;
  UNASSIGNED)
    block "'$rel' has no owner in the ratified authority matrix (docs/agents.md).
Do not invent ownership. Acting identity: $identity. Owner: UNASSIGNED --
escalate to the human architect for a matrix amendment via factory-engineer." ;;
  *)
    if [ "$identity" = "ORCHESTRATOR" ]; then
      block "The orchestrator must not perform specialist work inline.
'$rel' is owned by $owner. Delegate with: Use the $owner agent to ..."
    fi
    block "'$identity' does not own '$rel'. Owner: $owner." ;;
esac
