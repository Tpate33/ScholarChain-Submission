#!/usr/bin/env bash
# post-write.sh -- PostToolUse hook for Write|Edit|MultiEdit|NotebookEdit (review finding NEW-1).
#
# Runs two steps IN THIS ORDER, so the recorded hash is of the formatted file:
#   1. format-scala.sh with the same payload (behaviour unchanged; its exit 2 is propagated).
#   2. Records the content hash of exactly the written path in the session baseline (lib-baseline.sh),
#      so the next Bash audit does not report an authorized Write/Edit as an inter-call change.
# Step 2 records ONLY the single path named by the payload (tool_input.file_path, or notebook_path),
# and only when: the identity is valid (absent agent_type = ORCHESTRATOR); the path is inside the
# repository; the acting identity owns it (same map and skeleton exemption as enforce-ownership.sh);
# the session baseline exists. It never touches any other path, so an out-of-band change to ANOTHER
# path stays visible to the next audit.
# Exit 2 (nothing recorded, reason on stderr): python3 missing, invalid identity, out-of-territory
# path, missing baseline after session start, lock timeout, or corrupt baseline.
# Exit with format-scala.sh's status (0 unless formatting failed), nothing recorded, silently: no
# file_path/notebook_path in the payload; the project directory cannot be entered or is not a git
# work tree; the path is outside the repository; no baseline and no session-started marker (no Bash
# call yet this session; the first Bash call creates the baseline from the current state). If an
# unrecorded write did change a git-visible repository path (the cd/work-tree case), the next Bash
# call's --pre reports it as an inter-call change (UNATTRIBUTED). Paths outside the repository are
# never audited.
# RESIDUAL (R5, documented, not fixed): the whole written file is re-hashed, so an out-of-band change
# to THE SAME file (e.g. a background process started by earlier test code appending to it) made
# before this Write/Edit is absorbed into the baseline and not reported. Scope: only paths the acting
# identity owns, so no cross-owner change is ever blessed.
set -u
HOOK_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib-ownership.sh
. "$HOOK_DIR/lib-ownership.sh"
# shellcheck source=lib-baseline.sh
. "$HOOK_DIR/lib-baseline.sh"

payload="$(cat)"
rc=0
if ! fmt_err="$(printf '%s' "$payload" | "$HOOK_DIR/format-scala.sh" 2>&1 >/dev/null)"; then
  printf '%s\n' "$fmt_err" >&2
  rc=2
fi
notrec() {
  echo "post-write.sh: the write was NOT recorded in the Bash audit baseline: $1" >&2
  echo "The next Bash call of this session will report it as an inter-call change (UNATTRIBUTED)." >&2
  exit 2
}

command -v python3 >/dev/null 2>&1 || notrec "python3 is not installed."
fp="$(scholarchain_json_get "$payload" '.tool_input.file_path')"
[ -n "$fp" ] || fp="$(scholarchain_json_get "$payload" '.tool_input.notebook_path')"
[ -n "$fp" ] || exit "$rc"
root="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
cd "$root" 2>/dev/null || exit "$rc"
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || exit "$rc"

identity="$(scholarchain_identity_checked "$payload")" || notrec "invalid acting identity ($identity)."
rel="$(scholarchain_relpath "$root" "$fp")"
case "$rel" in OUTSIDE:*) exit "$rc" ;; esac          # temp/scratch files are not audited
owner="$(scholarchain_owner_of "$rel")"
scholarchain_owner_ok "$identity" "$rel" "$owner" \
  || notrec "'$rel' (owner: $owner) is outside the territory of '$identity'; enforce-ownership.sh should have refused this write."

baseline_init "$(scholarchain_json_get "$payload" '.session_id')" || notrec "cannot create the private state directory."
if [ ! -f "$base" ]; then
  [ -f "$started" ] && notrec "the session baseline is missing although the session started."
  exit "$rc"                                          # no Bash call yet this session
fi
baseline_lock || notrec "$(baseline_lock_msg)"
baseline_update "$rel" || { baseline_unlock; notrec "the session baseline is corrupt or could not be replaced."; }
baseline_unlock
exit "$rc"
