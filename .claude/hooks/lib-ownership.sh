#!/usr/bin/env bash
# lib-ownership.sh -- shared library, SOURCED (not executed) by
#   .claude/hooks/enforce-ownership.sh
#   .claude/hooks/verify-tests-ran.sh
#   .claude/hooks/validate-agent-files.sh
#
# Single machine-readable mirror of the "Path ownership" table in docs/agents.md.
# validate-agent-files.sh cross-checks this map against docs/agents.md; the two
# MUST change together, and only factory-engineer may change either.
#
# Compatible with macOS /bin/bash 3.2 (no associative arrays, no ${x,,}).

# Owners that are not agents:
#   ORCHESTRATOR -- main Claude Code thread; may assemble evidence in docs/agent-runs/
#   HUMAN        -- human architect only; no Claude tool call may write it
#   GENERATED    -- produced by build tooling (sbt markTestRun), never hand-written
#   UNASSIGNED   -- not covered by the ratified matrix; escalate to the human

# scholarchain_owner_of <repo-relative-path>  -> prints owner on stdout
# Matching is case-insensitive: macOS (APFS/HFS+) resolves docs/AGENTS.md to
# docs/agents.md, so a case variant must never map to a different owner.
scholarchain_owner_of() {
  local p
  p="$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')"
  case "$p" in
    # --- constitutional (factory-engineer) -----------------------------------
    claude.md|docs/agents.md|.mcp.json|.claude/settings.json) echo "factory-engineer" ;;
    .claude/agents/*|.claude/hooks/*|.claude/commands/*)       echo "factory-engineer" ;;
    .claude/.last-test-run)                                     echo "GENERATED" ;;
    .claude/settings.local.json)                                echo "HUMAN" ;;
    .claude/*)                                                  echo "UNASSIGNED" ;;
    # --- human-owned evidence --------------------------------------------------
    docs/agent-runs/readme.md)                                  echo "HUMAN" ;;
    docs/agent-runs/*/human-gate.md)                            echo "HUMAN" ;;
    docs/agent-runs/*/commit.txt)                               echo "HUMAN" ;;
    # no sbt build definition in phase evidence either (Phase 2 review F1)
    docs/agent-runs/*.sbt)                                      echo "UNASSIGNED" ;;
    docs/agent-runs/*)                                          echo "ORCHESTRATOR" ;;
    license)                                                    echo "HUMAN" ;;
    # --- build ----------------------------------------------------------------
    build.sbt|.scalafmt.conf|.gitignore)                        echo "build-engineer" ;;
    compose.yaml|compose.yml|docker-compose.yaml|docker-compose.yml) echo "build-engineer" ;;
    project/*)                                                  echo "build-engineer" ;;
    # sbt loads *.sbt in every subproject base as build policy (Phase 2 review F1):
    # only build.sbt and project/** above are writable; every other *.sbt fails closed.
    *.sbt)                                                      echo "UNASSIGNED" ;;
    # --- human-owned gold set (amendment G9-a, FE-P5-03): per-split membership manifests, freeze records
    # (*.jsonl.sha256), evaluation configs, blind views, review queues, labels. After *.sbt so
    # gold/**/*.sbt stays UNASSIGNED (F1); before every agent territory.
    gold/*)                                                     echo "HUMAN" ;;
    # --- application modules --------------------------------------------------
    modules/core/*)                                             echo "domain-engineer" ;;
    modules/hadoop/*)                                           echo "hadoop-engineer" ;;
    modules/entity-resolution/*)                                echo "entity-resolution-engineer" ;;
    modules/spark/*)                                            echo "spark-engineer" ;;
    modules/storage/*)                                          echo "storage-engineer" ;;
    modules/rag/*|prompts/*)                                    echo "rag-engineer" ;;
    modules/services/*)                                         echo "service-engineer" ;;
    # --- data, tests, infra, experiments ---------------------------------------
    datasets/*)                                                 echo "dataset-engineer" ;;
    tests/*)                                                    echo "test-engineer" ;;
    infra/terraform/*|scripts/*|.github/workflows/*)            echo "infra-engineer" ;;
    experiments/*|load-tests/*)                                 echo "experiment-engineer" ;;
    # --- documentation --------------------------------------------------------
    docs/incidents/*)                                           echo "incident-responder" ;;
    readme.md|reports/final-report.md|docs/*)                   echo "documentation-engineer" ;;
    # --- everything else -------------------------------------------------------
    *)                                                          echo "UNASSIGNED" ;;
  esac
}

# Phase 2 skeleton exemption (spec, Phase 2: "empty module source roots" and
# "empty required top-level directories"). build-engineer may create empty
# .gitkeep placeholders inside another agent's territory, never inside
# constitutional, human, generated, or orchestrator paths.
scholarchain_skeleton_exempt() {
  local identity="$1" p="$2" owner="$3"
  [ "$identity" = "build-engineer" ] || return 1
  case "$p" in */.gitkeep|.gitkeep) ;; *) return 1 ;; esac
  case "$owner" in factory-engineer|HUMAN|GENERATED|ORCHESTRATOR) return 1 ;; esac
  return 0
}

# One sample path per row of the docs/agents.md ownership table (same sample
# rule as validate-agent-files.sh: '**' -> 'x/y.scala', '*' -> 'x').
# validate-agent-files.sh fails if this list and the table disagree.
# Used to decide whether a recursive shell operation on a directory could
# reach an artifact with a different owner.
SCHOLARCHAIN_ANCHORS='CLAUDE.md
docs/agents.md
.mcp.json
.claude/settings.json
.claude/agents/x/y.scala
.claude/hooks/x/y.scala
.claude/commands/x/y.scala
.claude/.last-test-run
.claude/settings.local.json
.claude/x/y.scala
docs/agent-runs/README.md
docs/agent-runs/x/human-gate.md
docs/agent-runs/x/commit.txt
docs/agent-runs/x/y.scala/x.sbt
docs/agent-runs/x/y.scala
LICENSE
build.sbt
project/x/y.scala
.scalafmt.conf
.gitignore
compose.yaml
compose.yml
docker-compose.yaml
docker-compose.yml
x/y.scala/x.sbt
gold/x/y.scala
modules/core/x/y.scala
modules/hadoop/x/y.scala
modules/entity-resolution/x/y.scala
modules/spark/x/y.scala
modules/storage/x/y.scala
modules/rag/x/y.scala
prompts/x/y.scala
modules/services/x/y.scala
datasets/x/y.scala
tests/x/y.scala
infra/terraform/x/y.scala
scripts/x/y.scala
.github/workflows/x/y.scala
experiments/x/y.scala
load-tests/x/y.scala
docs/incidents/x/y.scala
README.md
reports/final-report.md
docs/x/y.scala
x'

# scholarchain_tree_probes <repo-relative-dir> -> sample paths a recursive
# operation on that directory could touch (one per line).
scholarchain_tree_probes() {
  local rel="${1%/}" a b
  [ "$rel" = "." ] && rel=""
  if [ -z "$rel" ]; then printf '%s\n' "__x__"; printf '%s\n' "$SCHOLARCHAIN_ANCHORS"; return 0; fi
  printf '%s\n' "$rel/__x__"
  printf '%s\n' "$SCHOLARCHAIN_ANCHORS" | while IFS= read -r a; do
    [ -n "$a" ] || continue
    b="${a##*/}"
    case "$(printf '%s' "$a" | tr '[:upper:]' '[:lower:]')" in
      "$(printf '%s' "$rel" | tr '[:upper:]' '[:lower:]')"/*) printf '%s\n' "$a" ;;
    esac
    printf '%s\n' "$rel/$b" "$rel/x/$b"
  done
}

# scholarchain_owner_ok <identity> <rel> <owner> -> 0 when identity may write rel
scholarchain_owner_ok() {
  [ "$3" = "$1" ] && return 0
  scholarchain_skeleton_exempt "$1" "$2" "$3"
}

# scholarchain_is_temp_path <absolute-realpath> -> 0 for session temp/scratch paths
scholarchain_is_temp_path() {
  local abs="$1" tmp="${TMPDIR:-/tmp}"
  tmp="${tmp%/}"
  case "$abs" in
    "$tmp"/*|/tmp/*|/private/tmp/*|/private/var/folders/*|/var/folders/*) return 0 ;;
  esac
  return 1
}

# Resolve the acting identity from a hook payload's agent_type / agent_id.
#   agent_type present            -> that agent (subagent, or main thread under --agent)
#   agent_type absent             -> ORCHESTRATOR (plain main thread)
scholarchain_identity() {
  local agent_type="$1"
  if [ -n "$agent_type" ] && [ "$agent_type" != "null" ]; then
    echo "$agent_type"
  else
    echo "ORCHESTRATOR"
  fi
}

# The 18 ratified agents (same list as guard-dangerous.sh and validate-agent-files.sh).
SCHOLARCHAIN_AGENTS="factory-engineer build-engineer domain-engineer dataset-engineer hadoop-engineer entity-resolution-engineer spark-engineer storage-engineer rag-engineer service-engineer test-engineer code-reviewer infra-engineer deploy-engineer experiment-engineer documentation-engineer incident-responder dependency-updater"

# scholarchain_identity_checked <payload-json>
#   agent_type ABSENT                        -> prints ORCHESTRATOR, returns 0
#   a ratified agent name                    -> prints it, returns 0
#   present but empty, null, "null", a non-string, or not ratified, or an unparseable payload, or no
#   python3                                  -> prints the reason, returns 1 (callers deny / fail closed)
# Same rule as guard-dangerous.sh (review finding NEW-6).
scholarchain_identity_checked() {
  command -v python3 >/dev/null 2>&1 || { echo "python3 is required to check the acting identity"; return 1; }
  printf '%s' "$1" | python3 -I -c '
import json, sys
agents = set(sys.argv[1].split())
try:
    d = json.load(sys.stdin)
except Exception:
    print("the hook payload is not JSON"); sys.exit(1)
if not isinstance(d, dict):
    print("the hook payload is not a JSON object"); sys.exit(1)
if "agent_type" not in d:
    print("ORCHESTRATOR"); sys.exit(0)
a = d["agent_type"]
if not isinstance(a, str) or a in ("", "null"):
    print("agent_type=%s is not a valid agent name" % json.dumps(a)); sys.exit(1)
if a not in agents:
    print("agent_type %r is not one of the 18 ratified agents" % a); sys.exit(1)
print(a)
' "$SCHOLARCHAIN_AGENTS"
}

# JSON field extraction: prefers jq, falls back to python3. Prints empty string
# for missing fields. Returns 3 when no parser is available (callers fail closed).
scholarchain_json_get() {
  local json="$1" filter="$2"
  if command -v jq >/dev/null 2>&1; then
    printf '%s' "$json" | jq -r "$filter // empty" 2>/dev/null
    return 0
  elif command -v python3 >/dev/null 2>&1; then
    printf '%s' "$json" | python3 -I -c '
import json, sys
path = sys.argv[1].lstrip(".").split(".")
try:
    v = json.load(sys.stdin)
    for k in path:
        if not k:
            continue
        v = v.get(k) if isinstance(v, dict) else None
    if v is None:
        print("")
    elif isinstance(v, (dict, list)):
        print(json.dumps(v))
    else:
        print(v)
except Exception:
    print("")
' "$filter"
    return 0
  fi
  return 3
}

# Normalize an absolute or relative path to a repo-relative path.
# Prints the relative path, or "OUTSIDE:<abs>" when it is outside the repo.
scholarchain_relpath() {
  local root="$1" path="$2"
  if command -v python3 >/dev/null 2>&1; then
    python3 -I -c '
import os, sys
root = os.path.realpath(sys.argv[1])
p = sys.argv[2]
if not os.path.isabs(p):
    p = os.path.join(root, p)
p = os.path.realpath(p)
if p == root or p.startswith(root + os.sep):
    print(os.path.relpath(p, root))
else:
    print("OUTSIDE:" + p)
' "$root" "$path"
  else
    case "$path" in
      *..*) echo "OUTSIDE:$path" ;;
      "$root"/*) echo "${path#"$root"/}" ;;
      /*) echo "OUTSIDE:$path" ;;
      *) echo "${path#./}" ;;
    esac
  fi
}
