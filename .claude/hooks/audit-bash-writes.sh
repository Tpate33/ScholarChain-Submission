#!/usr/bin/env bash
# audit-bash-writes.sh -- PreToolUse (--pre) and PostToolUse (--post) hook for Bash.
#
# Detective backstop for the opaque-tool exceptions of the Bash allowlist (sbt and the test code it
# runs, approved scripts): the guard decides which programs run, but not what they write. This hook
# records a CONTENT HASH (sha256 of the bytes plus the executable bits; symlinks hash their target
# string) of every git-visible path -- tracked plus untracked, non-ignored -- of the git control
# files that change what git shows or runs (.git/config, .git/info/exclude, .git/info/attributes,
# .git/hooks/*), and of the test marker .claude/.last-test-run even when it is gitignored. Size and
# mtime are ignored, so same-size rewrites with a restored mtime are seen. Exception: the test
# marker's entry ALSO records its mtime (ns) and size, because the test gate compares mtimes; a
# touch that leaves its bytes unchanged is therefore a reported change. Baseline storage, locking,
# and hashing live in lib-baseline.sh (shared with post-write.sh).
#
#   --pre   record the snapshot, then (under the session lock) compare it with the SESSION BASELINE:
#           the state at the end of the previous Bash call, updated by post-write.sh for every
#           authorized Write/Edit/MultiEdit/NotebookEdit since then. Any difference is a change that
#           happened BETWEEN calls without an audited tool (e.g. a background or delayed process
#           started by sbt test code, or a human IDE edit): it is reported as UNATTRIBUTED and this
#           call is refused (exit 2). The baseline is then reset to the current state, so after the
#           human reviews the report the agent may simply re-run the command.
#           Baseline absent and no session-started marker: new session, baseline created, exit 0.
#           Baseline absent AFTER the session-started marker exists: fails closed (exit 2).
#   --post  compare with this call's own pre snapshot. Every changed path whose owner (docs/agents.md)
#           is not the acting identity is reported, exit 2. The test marker .claude/.last-test-run
#           (GENERATED) may change (content, mode, size, or mtime) only when the command is exactly
#           `sbt check` or `sbt clean check`; any other change to it is reported. Within `sbt check`
#           itself the audit cannot tell markTestRun's update from a touch by test code (residual;
#           see docs/agents.md). Changed Scala files (.scala .sc .sbt) the identity owns
#           are passed to format-scala.sh, unchanged (exit 2 if scalafmt or .scalafmt.conf is missing),
#           and their formatted content is re-hashed into the baseline. The baseline is set to the post
#           state (lock, temp file + rename).
#           FAILS CLOSED (exit 2): missing or corrupt pre snapshot, corrupt baseline, lock not acquired
#           within 5 s (parallel Bash calls are unsupported), python3 or git missing, not a git work tree,
#           or a snapshot that could not be completed (over 1 GiB of git-visible content).
# Identity: agent_type absent -> ORCHESTRATOR; present but empty, null, "null", non-string, or not a
# ratified agent -> UNATTRIBUTED (owns nothing).
# DETECTION ONLY, NOT PREVENTION: the change has already happened when it is reported. Not covered:
# ignored paths (target/, downloads), anything outside the repository, and tampering with this hook's
# own state files in TMPDIR by a process running as the same OS user (no OS sandbox; B11 is off).
set -u

HOOK_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib-ownership.sh
. "$HOOK_DIR/lib-ownership.sh"

mode="${1:-}"
payload="$(cat)"
closed() {
  {
    echo "AUDIT FAILED CLOSED (.claude/hooks/audit-bash-writes.sh): $1"
    echo "The Bash command already ran, but what it changed could not be verified."
    echo "Do: stop. Return STATUS: BLOCKED-ON with BLOCKED-ON OWNER: human and this message as"
    echo "BLOCKED-ON EVIDENCE. The human inspects 'git status' and 'git diff' before work continues."
  } >&2
  exit 2
}
pre_closed() {
  {
    echo "AUDIT FAILED CLOSED (.claude/hooks/audit-bash-writes.sh): $1"
    echo "This Bash call was refused. Do: stop. Return STATUS: BLOCKED-ON with BLOCKED-ON OWNER: human;"
    echo "the human reviews git status / git diff before work continues."
  } >&2
  exit 2
}
case "$mode" in --pre|--post) ;; *) echo "usage: audit-bash-writes.sh --pre|--post (hook payload on stdin)" >&2; exit 2 ;; esac
pre_or_closed() { [ "$mode" = "--pre" ] && exit 0; closed "$1"; }

command -v python3 >/dev/null 2>&1 || pre_or_closed "python3 is not installed."
command -v git >/dev/null 2>&1 || pre_or_closed "git is not installed."
root="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
cd "$root" 2>/dev/null || pre_or_closed "cannot enter the project directory $root."
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || pre_or_closed "$root is not a git work tree."

# shellcheck source=lib-baseline.sh
. "$HOOK_DIR/lib-baseline.sh"
baseline_init "$(scholarchain_json_get "$payload" '.session_id')" || pre_or_closed "cannot create the private state directory under ${TMPDIR:-/tmp}."

# line 1: per-call key; line 2: command (one line)
meta="$(printf '%s' "$payload" | python3 -I -c '
import hashlib, json, sys
try:
    d = json.load(sys.stdin)
    assert isinstance(d, dict)
except Exception:
    d = {}
k = "|".join(str(d.get(x, "")) for x in ("session_id", "agent_id", "tool_use_id"))
cmd = (d.get("tool_input") or {}).get("command", "") if isinstance(d.get("tool_input"), dict) else ""
k += "|" + str(cmd)
print(hashlib.sha256(k.encode()).hexdigest()[:32])
print(str(cmd).replace("\n", " "))
')"
key="$(printf '%s\n' "$meta" | sed -n 1p)"
command_run="$(printf '%s\n' "$meta" | sed -n 2p)"
identity="$(scholarchain_identity_checked "$payload")" || identity="UNATTRIBUTED"
state="$state_dir/$key.v2.json"

# diffsnap FILE-A FILE-B: print every path whose recorded state differs; exit 5 if either is corrupt.
diffsnap() {
  python3 -I -c '
import json, sys
try:
    a, b = (json.load(open(f)) for f in sys.argv[1:3])
    assert all(x.get("v") == 2 and isinstance(x.get("files"), dict) for x in (a, b))
except Exception:
    sys.exit(5)
a, b = a["files"], b["files"]
for p in sorted(set(a) | set(b)):
    if a.get(p) != b.get(p):
        print(p)
' "$1" "$2"
}
# owners_of: annotate each path on stdin with its owner (UNATTRIBUTED changes have no rightful writer)
owners_of() { while IFS= read -r p; do [ -n "$p" ] && echo "  - $p (owner: $(scholarchain_owner_of "$p"))"; done; }

if [ "$mode" = "--pre" ]; then
  # remove per-call snapshot files older than a day (never the session baseline or marker)
  for old in "$state_dir"/*.v2.json; do
    case "$old" in */session-*) continue ;; esac
    [ -f "$old" ] && [ -n "$(find "$old" -mtime +1 2>/dev/null)" ] && rm -f "$old"
  done
  baseline_snapshot > "$state.tmp" 2>/dev/null && mv "$state.tmp" "$state" || { rm -f "$state.tmp" "$state"; exit 0; }
  baseline_lock || pre_closed "$(baseline_lock_msg)"
  if [ ! -f "$base" ]; then
    baseline_replace "$state" || { baseline_unlock; pre_closed "the session baseline $base could not be written."; }
    if [ -f "$started" ]; then
      baseline_unlock
      echo "== $(date -u +%Y-%m-%dT%H:%M:%SZ) BASELINE MISSING after session start; re-created from the current state" >> "$incidents" 2>/dev/null
      pre_closed "the session baseline $base was missing although this session had already started ($started exists). It was deleted or tampered with, so changes made since the previous Bash call cannot be checked. It was re-created from the current state."
    fi
    : > "$started"
    baseline_unlock
    # session start: log tracked files differing from HEAD (informational; filters apply) for the human
    { echo "== $(date -u +%Y-%m-%dT%H:%M:%SZ) session start ($root): tracked files differing from HEAD:"
      git -c core.fsmonitor=false diff --name-only --no-ext-diff --no-textconv HEAD 2>/dev/null | sed 's/^/  - /'
    } >> "$incidents" 2>/dev/null
    exit 0
  fi
  inter="$(diffsnap "$base" "$state")"; drc=$?
  baseline_replace "$state"; brc=$?
  baseline_unlock
  if [ $drc -ne 0 ] || [ $brc -ne 0 ]; then
    pre_closed "the session baseline $base is corrupt or could not be replaced; it was reset where possible."
  fi
  [ -n "$inter" ] || exit 0
  list="$(printf '%s\n' "$inter" | owners_of)"
  { echo "== $(date -u +%Y-%m-%dT%H:%M:%SZ) INTER-CALL CHANGE before a Bash call by '$identity' (UNATTRIBUTED):"; echo "$list"; } >> "$incidents" 2>/dev/null
  {
    echo "INTER-CALL CHANGE detected by .claude/hooks/audit-bash-writes.sh (UNATTRIBUTED)"
    echo "These git-visible paths changed AFTER the previous Bash call of this session ended and BEFORE this"
    echo "call, without an audited Write/Edit (for example a background or delayed process started by test"
    echo "code, or an edit made outside Claude Code). No identity is credited with the change:"
    echo "$list"
    echo "This Bash call was refused. The change was NOT reverted. Logged in: $incidents"
    echo "Do: stop. Return STATUS: BLOCKED-ON with BLOCKED-ON OWNER: human and the list above as"
    echo "BLOCKED-ON EVIDENCE. The human reviews git status / git diff and decides what to keep. The"
    echo "baseline has been reset to the current state, so after that review the same command may be re-run."
  } >&2
  exit 2
fi

[ -f "$state" ] || closed "no pre-command snapshot exists for this call (the --pre hook did not run or failed)."
baseline_snapshot > "$state.post" 2>/dev/null || { rm -f "$state" "$state.post"; closed "the post-command snapshot could not be completed (git failure, or over 1 GiB of git-visible content)."; }
changed="$(diffsnap "$state" "$state.post")" || { rm -f "$state" "$state.post"; closed "the pre-command snapshot is corrupt or in an unknown format."; }
baseline_lock || { rm -f "$state" "$state.post"; closed "$(baseline_lock_msg)"; }
baseline_replace "$state.post" || { baseline_unlock; rm -f "$state" "$state.post"; closed "the session baseline could not be replaced."; }
[ -f "$started" ] || : > "$started"
baseline_unlock
rm -f "$state" "$state.post"
[ -n "$changed" ] || exit 0

bad=""; scala=""
while IFS= read -r p; do
  [ -n "$p" ] || continue
  owner="$(scholarchain_owner_of "$p")"
  if [ "$owner" = "GENERATED" ]; then
    # the test marker may change only through `sbt check` / `sbt clean check` (markTestRun after tests)
    case "$command_run" in "sbt check"|"sbt clean check") continue ;; esac
    bad="$bad
  - $p (owner: GENERATED; changed by '$command_run', which is not 'sbt check' or 'sbt clean check': possible test-marker forgery)"
    continue
  fi
  if scholarchain_owner_ok "$identity" "$p" "$owner"; then
    case "$p" in *.scala|*.sc|*.sbt) [ -f "$p" ] && [ ! -L "$p" ] && scala="$scala
$p" ;; esac
    continue
  fi
  bad="$bad
  - $p (owner: $owner)"
done <<EOF
$changed
EOF

rc=0
if [ -n "$bad" ]; then
  {
    echo "OWNERSHIP VIOLATION detected by .claude/hooks/audit-bash-writes.sh"
    echo "The Bash command just run changed files outside the territory of '$identity':$bad"
    echo "Detection is by content hash (size and modification time are ignored, except for the test marker, whose mtime is also recorded)."
    echo "The change already happened and was NOT reverted automatically."
    echo "Do: stop. Do not try to repair or hide it. Return STATUS: BLOCKED-ON with"
    echo "BLOCKED-ON OWNER: human and the list above as BLOCKED-ON EVIDENCE. The human"
    echo "reviews the diff (git diff / git status) and decides whether to revert it."
  } >&2
  rc=2
fi
# Bash-originated Scala changes reach the formatter (same hook, same behaviour as after Write/Edit);
# the formatted content is then re-hashed into the baseline so it is not reported at the next call.
fmt=()
while IFS= read -r p; do
  [ -n "$p" ] || continue
  fp="$(python3 -I -c 'import json, sys; print(json.dumps({"tool_name": "Bash", "tool_input": {"file_path": sys.argv[1]}}))' "$root/$p")"
  if ! msg="$(printf '%s' "$fp" | CLAUDE_PROJECT_DIR="$root" "$HOOK_DIR/format-scala.sh" 2>&1 >/dev/null)"; then
    { echo "Scala changed by a Bash command: $p"; echo "$msg"; } >&2
    rc=2
  fi
  fmt+=("$p")
done <<EOF
$scala
EOF
if [ "${#fmt[@]}" -gt 0 ]; then
  # R4: release the lock only if this process acquired it, and exactly once.
  if baseline_lock; then
    baseline_update "${fmt[@]}" || { echo "AUDIT: formatted Scala could not be recorded in the session baseline; the next Bash call will report it." >&2; rc=2; }
    baseline_unlock
  else
    echo "AUDIT: formatted Scala could not be recorded in the session baseline ($(baseline_lock_msg)); the next Bash call will report it." >&2; rc=2
  fi
fi
exit "$rc"
