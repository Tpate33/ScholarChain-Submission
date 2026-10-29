#!/usr/bin/env bash
# format-scala.sh -- PostToolUse hook for Write|Edit|MultiEdit.
#
# Formats the written file with scalafmt when it is a repository Scala file
# (.scala, .sc, .sbt), using the repository .scalafmt.conf owned by
# build-engineer (spec: "Format Scala after edits"; "exits harmlessly when no
# Scala file changed").
#   - Not a Scala file, file absent, or outside the repo -> exit 0 silently
#                                                          (no Scala file changed).
#   - scalafmt not installed        -> exit 2, actionable: install command. The
#                                      write happened but is UNFORMATTED; the
#                                      agent must not report completion.
#   - .scalafmt.conf missing        -> exit 2, actionable: owner is build-engineer.
#   - scalafmt fails                -> exit 2 so the agent sees the error and fixes
#                                      the source (the write itself already happened).
# No fallback formatter is used: none has been verified to produce output
# identical to scalafmt with the repository configuration.
# This hook never creates, changes, or bypasses formatter configuration
# (it passes --config explicitly and never uses --config-str).
set -u

HOOK_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib-ownership.sh
. "$HOOK_DIR/lib-ownership.sh"

payload="$(cat)"
file_path="$(scholarchain_json_get "$payload" '.tool_input.file_path')" || exit 0
[ -n "$file_path" ] || exit 0

case "$file_path" in
  *.scala|*.sc|*.sbt) ;;
  *) exit 0 ;;
esac

[ -f "$file_path" ] || exit 0

root="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
rel="$(scholarchain_relpath "$root" "$file_path")"
case "$rel" in OUTSIDE:*) exit 0 ;; esac

missing_tool=0; missing_conf=0
command -v scalafmt >/dev/null 2>&1 || missing_tool=1
[ -f "$root/.scalafmt.conf" ] || missing_conf=1

if [ "$missing_tool" = 1 ] || [ "$missing_conf" = 1 ]; then
  {
    echo "BLOCKED-FORMAT by .claude/hooks/format-scala.sh: $rel was written but NOT formatted."
    if [ "$missing_tool" = 1 ]; then
      echo "Cause: the scalafmt CLI is not on PATH."
      echo "Fix:   the human installs it (toolchain prerequisite), e.g."
      echo "         cs install scalafmt                      # Coursier, https://get-coursier.io"
      echo "         brew install coursier/formulas/scalafmt  # Homebrew alternative"
      echo "       then verifies with: scalafmt --version"
    fi
    if [ "$missing_conf" = 1 ]; then
      echo "Cause: $root/.scalafmt.conf does not exist."
      echo "Fix:   .scalafmt.conf is owned by build-engineer (docs/agents.md). If you are not"
      echo "       build-engineer, do not create it: return STATUS: BLOCKED-ON with"
      echo "       BLOCKED-ON OWNER: build-engineer. build-engineer creates it (Phase 2) first."
    fi
    echo "Then re-save $rel so this hook formats it. Do not report completion with unformatted Scala."
  } >&2
  exit 2
fi

if ! out="$(cd "$root" && scalafmt --non-interactive --config "$root/.scalafmt.conf" "$file_path" 2>&1)"; then
  {
    echo "BLOCKED-FORMAT: scalafmt could not format $rel (usually a syntax error)."
    echo "$out" | tail -n 20
    echo "Do: fix the syntax in the file you own and write it again. Do not edit .scalafmt.conf (owned by build-engineer)."
  } >&2
  exit 2
fi
exit 0
