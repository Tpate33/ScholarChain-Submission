#!/usr/bin/env bash
# validate-agent-files.sh -- agent-constitution validator.
#
# Usage:
#   .claude/hooks/validate-agent-files.sh          full validation, human-readable
#   .claude/hooks/validate-agent-files.sh --hook   PostToolUse mode: reads the hook
#        payload on stdin and validates only when a constitutional file was written
#   .claude/hooks/validate-agent-files.sh --probes  full validation, then the committed probe
#        table (.claude/hooks/probes.tsv) and disposable-fixture probes (audit, format, provenance, stop,
#        D10(ii) held-out run protection)
#
# Checks (spec: "validate-agent-files.sh validates frontmatter, required tool
# fences, unique names, executable hooks, and JSON syntax"), plus the factory
# audits: one-writer ownership mirror, routing collisions/orphans, hook orphans,
# duplicate hook registrations, and command routing.
# Exit 0 = all checks pass; exit 1 (script) / 2 (hook) = failures listed.
set -u

HOOK_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib-ownership.sh
. "$HOOK_DIR/lib-ownership.sh"
ROOT="${CLAUDE_PROJECT_DIR:-$(cd "$HOOK_DIR/../.." && pwd)}"

REQUIRED_AGENTS="factory-engineer build-engineer domain-engineer dataset-engineer hadoop-engineer entity-resolution-engineer spark-engineer storage-engineer rag-engineer service-engineer test-engineer code-reviewer infra-engineer deploy-engineer experiment-engineer documentation-engineer incident-responder dependency-updater"
REQUIRED_COMMANDS="deploy rollback incident run-pipeline experiment"
READ_ONLY_AGENTS="code-reviewer"
FORBIDDEN_REVIEWER_TOOLS="Write Edit MultiEdit NotebookEdit Bash"
NO_WRITE_AGENTS="deploy-engineer"

if [ "${1:-}" = "--hook" ]; then
  payload="$(cat)"
  fp="$(scholarchain_json_get "$payload" '.tool_input.file_path')"
  [ -n "$fp" ] || exit 0
  rel="$(scholarchain_relpath "$ROOT" "$fp")"
  [ "$(scholarchain_owner_of "$rel")" = "factory-engineer" ] || exit 0
  if ! out="$("$0" 2>&1)"; then
    { echo "validate-agent-files.sh: constitutional validation FAILED after writing $rel"; echo "$out" | grep -E 'FAIL|ERROR' | head -n 40; echo "Do: fix the listed problems before reporting."; } >&2
    exit 2
  fi
  exit 0
fi

cd "$ROOT" || { echo "ERROR: cannot cd to $ROOT"; exit 1; }
fails=0
pass() { echo "PASS  $*"; }
fail() { echo "FAIL  $*"; fails=$((fails + 1)); }
section() { echo; echo "== $* =="; }

json_ok() {
  if command -v jq >/dev/null 2>&1; then jq empty "$1" >/dev/null 2>&1
  else python3 -I -m json.tool "$1" >/dev/null 2>&1; fi
}

# --------------------------------------------------------------- JSON ------
section "JSON syntax"
for f in .claude/settings.json .mcp.json; do
  if [ ! -f "$f" ]; then fail "$f missing"
  elif json_ok "$f"; then pass "$f parses"
  else fail "$f does not parse as JSON"; fi
done

# --------------------------------------------------------------- hooks -----
section "Hook scripts"
for h in .claude/hooks/*.sh; do
  [ -e "$h" ] || continue
  if bash -n "$h" 2>/dev/null; then pass "bash -n $h"; else fail "bash -n $h: $(bash -n "$h" 2>&1 | head -1)"; fi
  if command -v shellcheck >/dev/null 2>&1; then
    if shellcheck -x -S warning "$h" >/dev/null 2>&1; then pass "shellcheck $h"; else fail "shellcheck $h"; fi
  fi
done
command -v shellcheck >/dev/null 2>&1 || echo "NOTE  shellcheck not installed; skipped (bash -n still enforced)"

refs=""
if command -v jq >/dev/null 2>&1 && json_ok .claude/settings.json; then
  refs="$(jq -r '.hooks // {} | to_entries[] | .value[] | .hooks[]? | .command' .claude/settings.json | grep -oE '\.claude/hooks/[A-Za-z0-9_.-]+\.sh' | sort -u)"
  dups="$(jq -r '.hooks // {} | to_entries[] | .key as $e | .value[] | (.matcher // "*") as $m | .hooks[]? | "\($e) | \($m) | \(.command)"' .claude/settings.json | sort | uniq -d)"
  if [ -z "$dups" ]; then pass "no duplicate hook registrations"; else fail "duplicate hook registrations: $dups"; fi
else
  fail "cannot read hook registrations (jq missing or settings invalid)"
fi
for r in $refs; do
  if [ ! -f "$r" ]; then fail "settings references missing hook $r"
  elif [ ! -x "$r" ]; then fail "hook not executable: $r (chmod +x)"
  else pass "referenced hook exists and is executable: $r"; fi
done
# PENDING-DELETION-ON-RATIFICATION: lib-bash-analysis.sh (the old shell parser) is no longer sourced
# by guard-dangerous.sh after the allowlist redesign. Human decision: keep it on disk until the
# redesign is ratified, then the human deletes it and this exception is removed. The exception names
# exactly this one file; it still FAILS if any registered hook sources it again.
PENDING_DELETION=".claude/hooks/lib-bash-analysis.sh"
for h in .claude/hooks/*.sh; do
  [ -e "$h" ] || continue
  if printf '%s\n' "$refs" | grep -qx "$h"; then continue; fi
  base="$(basename "$h")"
  if [ "$h" = "$PENDING_DELETION" ]; then
    if [ -n "$refs" ] && grep -lE "^[^#]*(\.|source) .*$base" $refs >/dev/null 2>&1; then fail "$h is pending deletion but is sourced by a registered hook"
    else echo "NOTE  $h: pending deletion on ratification (not sourced; narrow orphan exception)"; fi
    continue
  fi
  case "$base" in
    lib-*.sh)
      if [ -n "$refs" ] && grep -l "$base" $refs >/dev/null 2>&1; then pass "library $h is sourced by a registered hook"
      else fail "orphan library $h (not sourced by any registered hook)"; fi ;;
    *)
      # A hook invoked by a registered hook with the exact form "$HOOK_DIR/<name>" is not an orphan
      # (format-scala.sh runs inside post-write.sh and audit-bash-writes.sh); it must be executable.
      if [ -n "$refs" ] && grep -lF "\"\$HOOK_DIR/$base\"" $refs >/dev/null 2>&1; then
        if [ -x "$h" ]; then pass "hook $h is invoked by a registered hook"; else fail "hook not executable: $h (chmod +x)"; fi
      else fail "orphan hook $h (on disk but not registered in .claude/settings.json)"; fi ;;
  esac
done

# --------------------------------------------------------------- agents ----
section "Agent frontmatter and tool fences"
fm() { awk -v k="$2" 'NR==1&&$0!="---"{exit} NR>1&&$0=="---"{exit} NR>1{ if (index($0, k ":")==1) { sub("^" k ":[ ]*", ""); print; exit } }' "$1"; }
names=""
for a in .claude/agents/*.md; do
  [ -e "$a" ] || continue
  stem="$(basename "$a" .md)"
  if [ "$(head -n 1 "$a")" != "---" ]; then fail "$a: no frontmatter"; continue; fi
  n="$(fm "$a" name)"; d="$(fm "$a" description)"; t="$(fm "$a" tools)"
  [ -n "$n" ] && pass "$a: name=$n" || fail "$a: missing name"
  [ "$n" = "$stem" ] || fail "$a: name '$n' does not match filename '$stem'"
  [ -n "$d" ] && pass "$a: description present" || fail "$a: missing description"
  if [ -n "$t" ]; then pass "$a: tools=$t"; else fail "$a: missing explicit tools field"; fi
  case "$t" in *'*'*) fail "$a: wildcard in tools field" ;; esac
  printf '%s' "$d" | grep -q "Use when" || fail "$a: description lacks 'Use when' routing trigger"
  printf '%s' "$d" | grep -q "Do not use" || fail "$a: description lacks 'Do not use' exclusion"
  grep -q '^STATUS: COMPLETE | BLOCKED-ON | FAILED' "$a" || fail "$a: missing report contract STATUS line"
  grep -q '^BLOCKED-ON OWNER:' "$a" || fail "$a: missing report contract BLOCKED-ON OWNER field"
  names="$names $n"
  for ro in $READ_ONLY_AGENTS; do
    if [ "$n" = "$ro" ]; then
      for ft in $FORBIDDEN_REVIEWER_TOOLS; do
        if printf ', %s,' "$t" | grep -qE "[ ,]${ft}[ ,]"; then fail "$a: read-only agent has forbidden tool $ft"; fi
      done
      pass "$a: reviewer fence checked (no $FORBIDDEN_REVIEWER_TOOLS)"
    fi
  done
  for nw in $NO_WRITE_AGENTS; do
    if [ "$n" = "$nw" ] && printf ', %s,' "$t" | grep -qE '[ ,](Write|Edit|MultiEdit|NotebookEdit)[ ,]'; then
      fail "$a: execution-only agent has a write tool"
    fi
  done
done

section "Agent set"
dupnames="$(printf '%s\n' $names | sort | uniq -d)"
[ -z "$dupnames" ] && pass "agent names unique" || fail "duplicate agent names: $dupnames"
for r in $REQUIRED_AGENTS; do
  printf '%s\n' $names | grep -qx "$r" && pass "required agent present: $r" || fail "required agent missing: $r"
done
for n in $names; do
  printf '%s\n' $REQUIRED_AGENTS | grep -qx "$n" || fail "agent '$n' is not in the ratified matrix"
  grep -q "\`$n\`" docs/agents.md 2>/dev/null || fail "agent '$n' not referenced in docs/agents.md"
done

# ----------------------------------------------------- ownership mirror ----
section "One-writer audit (docs/agents.md vs lib-ownership.sh)"
table() { awk -v b="<!-- $1:begin -->" -v e="<!-- $1:end -->" '$0==b{f=1;next} $0==e{f=0} f&&/^\|/' docs/agents.md | awk 'NR>2'; }
own_rows="$(table ownership-table)"
[ -n "$own_rows" ] || fail "ownership table markers not found in docs/agents.md"
patterns=""
while IFS='|' read -r _ pcol ocol _rest; do
  pat="$(printf '%s' "$pcol" | sed -E 's/^ *`?//; s/`? *$//')"
  own="$(printf '%s' "$ocol" | sed -E 's/^ *`?//; s/`? *$//')"
  [ -z "$pat" ] && continue
  patterns="$patterns
$pat"
  sample="$(printf '%s' "$pat" | sed -E 's#\*\*#x/y.scala#g; s#\*#x#g')"
  got="$(scholarchain_owner_of "$sample")"
  if [ "$got" = "$own" ]; then pass "$pat -> $own"
  else fail "$pat: docs/agents.md says '$own' but enforcement map says '$got'"; fi
  case "$own" in HUMAN|ORCHESTRATOR|GENERATED|UNASSIGNED) ;;
    *) printf '%s\n' $REQUIRED_AGENTS | grep -qx "$own" || fail "$pat owned by unknown agent '$own'" ;;
  esac
done <<EOF
$own_rows
EOF
dpat="$(printf '%s\n' "$patterns" | sed '/^$/d' | sort | uniq -d)"
[ -z "$dpat" ] && pass "every path pattern has exactly one owner row" || fail "path patterns with multiple owner rows: $dpat"
samples="$(printf '%s\n' "$patterns" | sed '/^$/d' | sed -E 's#\*\*#x/y.scala#g; s#\*#x#g' | sort)"
anchors="$(printf '%s\n' "$SCHOLARCHAIN_ANCHORS" | sed '/^$/d' | sort)"
if [ "$samples" = "$anchors" ]; then pass "lib-ownership.sh anchor list matches the ownership table (tree checks cover every row)"
else fail "SCHOLARCHAIN_ANCHORS in lib-ownership.sh differs from the docs/agents.md ownership table: $(diff <(printf '%s\n' "$samples") <(printf '%s\n' "$anchors") | grep -E '^[<>]' | tr '\n' ' ')"; fi
for v in CLAUDE.md Claude.md docs/AGENTS.md .CLAUDE/hooks/x.sh .Mcp.json; do
  [ "$(scholarchain_owner_of "$v")" = "factory-engineer" ] && pass "case variant $v -> factory-engineer" || fail "case variant $v escapes constitutional ownership (got $(scholarchain_owner_of "$v"))"
done

section "Guard prerequisites"
if command -v python3 >/dev/null 2>&1; then pass "python3 present (guard-dangerous.sh decision core)"
else fail "python3 missing: guard-dangerous.sh blocks every checked call until it is installed"; fi
# Embedded decision core: exactly one opener line ending in <<'PYEOF' (optionally followed by
# " || true"), exactly one terminator line "PYEOF", terminator after opener, non-empty body that
# parses as Python. Any other heredoc line naming PYEOF also fails (no unrecognized second opener).
gcore="$(python3 -I -c '
import ast, re, sys
lines = open(sys.argv[1], encoding="utf-8").read().split("\n")
strict = [i for i, l in enumerate(lines) if re.search(r"<<\x27PYEOF\x27( \|\| true)?$", l)]
loose = [i for i, l in enumerate(lines) if "<<" in l and "PYEOF" in l]
terms = [i for i, l in enumerate(lines) if l == "PYEOF"]
if len(strict) != 1 or len(loose) != 1:
    sys.exit("found %d anchored and %d total heredoc openers naming PYEOF (need exactly 1)" % (len(strict), len(loose)))
if len(terms) != 1 or terms[0] <= strict[0]:
    sys.exit("found %d terminator lines PYEOF (need exactly 1, after the opener)" % len(terms))
body = "\n".join(lines[strict[0] + 1:terms[0]])
if not body.strip():
    sys.exit("embedded body is empty")
try:
    ast.parse(body)
except SyntaxError as e:
    sys.exit("embedded body does not parse as Python: line %s: %s" % (e.lineno, e.msg))
print("%d lines" % len(body.split("\n")))
' .claude/hooks/guard-dangerous.sh 2>&1)"; grc=$?
if [ "$grc" -eq 0 ]; then pass "guard decision core: one quoted PYEOF heredoc, $gcore, parses as Python"
else fail "guard-dangerous.sh embedded decision core: $gcore"; fi
if grep -qE '^[^#]*(\. |source ).*lib-bash-analysis' .claude/hooks/guard-dangerous.sh; then fail "guard-dangerous.sh must not source lib-bash-analysis.sh"
else pass "guard-dangerous.sh does not source the old shell parser"; fi
for d in bash-allowlist.tsv probes.tsv; do
  if [ ! -f ".claude/hooks/$d" ]; then fail "data file .claude/hooks/$d missing"
  elif grep -q "$d" .claude/hooks/guard-dangerous.sh .claude/hooks/validate-agent-files.sh; then pass "data file .claude/hooks/$d present and referenced"
  else fail "orphan data file .claude/hooks/$d"; fi
done
while IFS= read -r l; do case "$l" in PASS*) pass "${l#PASS  }" ;; FAIL*) fail "${l#FAIL  }" ;; esac; done \
  < <(.claude/hooks/guard-dangerous.sh --validate-allowlist 2>&1 || true)
# B1 agreement: docs/agents.md must carry the Bash allowlist table between the bash-table markers,
# row for row identical to bash-allowlist.tsv (same order; same rendering as amendment B1), and the
# embedded "source sha256" must equal the tsv's actual sha256. Missing block -> FAIL.
b1="$(python3 -I -c '
import hashlib, re, sys
doc = open(sys.argv[1], encoding="utf-8").read().split("\n")
raw = open(sys.argv[2], "rb").read()
B, E = "<!-- bash-table:begin -->", "<!-- bash-table:end -->"
if doc.count(B) != 1 or doc.count(E) != 1 or doc.index(B) > doc.index(E):
    sys.exit("Bash allowlist block missing or malformed in docs/agents.md (need exactly one %s ... %s)" % (B, E))
block = doc[doc.index(B) + 1:doc.index(E)]
if block[:2] != ["| Identity | Status | Mode | Provenance | Command shape | Justification |", "|---|---|---|---|---|---|"]:
    sys.exit("Bash allowlist block header differs")
got = block[2:]
want = []
for l in raw.decode("ascii").split("\n"):
    c = l.split("\t")
    if not l or l.startswith("#") or c[0] in ("@projects", "@env", "@env-nonprod"):
        continue
    want.append("| `%s` | %s | %s | %s | `%s` | %s |" % tuple(c[:6]))
if got != want:
    for i in range(max(len(got), len(want))):
        g = got[i] if i < len(got) else "<none>"
        w = want[i] if i < len(want) else "<none>"
        if g != w:
            sys.exit("Bash allowlist row %d differs from bash-allowlist.tsv (docs has %d rows, tsv %d): docs=%r tsv=%r" % (i + 1, len(got), len(want), g[:120], w[:120]))
h = hashlib.sha256(raw).hexdigest()
found = re.findall(r"source sha256 `([0-9a-f]{64})`", "\n".join(doc))
if found != [h]:
    sys.exit("embedded source sha256 %s does not equal bash-allowlist.tsv sha256 %s" % (found or "missing", h))
print("%d rows, sha256 %s" % (len(want), h[:12]))
' docs/agents.md .claude/hooks/bash-allowlist.tsv 2>&1)"; brc=$?
if [ "$brc" -eq 0 ]; then pass "docs/agents.md Bash allowlist block matches bash-allowlist.tsv ($b1)"
else fail "B1 agreement: $b1"; fi

# ----------------------------------------------------- routing audit -------
section "Routing audit (collisions and orphans)"
route_rows="$(table routing-table)"
[ -n "$route_rows" ] || fail "routing table markers not found in docs/agents.md"
steps=""; routed=""
while IFS='|' read -r _ scol acol _rest; do
  step="$(printf '%s' "$scol" | sed -E 's/^ +//; s/ +$//')"
  [ -z "$step" ] && continue
  steps="$steps
$step"
  agents_in_cell="$(printf '%s' "$acol" | grep -oE '`[a-z-]+`' | tr -d '`')"
  cnt="$(printf '%s\n' "$agents_in_cell" | sed '/^$/d' | wc -l | tr -d ' ')"
  if [ "$cnt" != "1" ]; then fail "routing step '$step' has $cnt owning agents (must be exactly 1)"; continue; fi
  routed="$routed $agents_in_cell"
  case "$agents_in_cell" in orchestrator|human) ;;
    *) printf '%s\n' $names | grep -qx "$agents_in_cell" || fail "routing step '$step' routes to unknown agent '$agents_in_cell'" ;;
  esac
done <<EOF
$route_rows
EOF
dsteps="$(printf '%s\n' "$steps" | sed '/^$/d' | sort | uniq -d)"
[ -z "$dsteps" ] && pass "no lifecycle step is claimed twice" || fail "routing collisions (duplicate steps): $dsteps"
for n in $names; do
  printf '%s\n' $routed | grep -qx "$n" && pass "agent routed: $n" || fail "routing orphan: agent '$n' owns no lifecycle step"
done

# ------------------------------------------------------------ commands -----
section "Commands"
for c in $REQUIRED_COMMANDS; do
  f=".claude/commands/$c.md"
  if [ ! -f "$f" ]; then fail "missing command $f"; continue; fi
  ra="$(grep -m1 -E '^Responsible agent: ' "$f" | grep -oE '`[a-z-]+`' | head -1 | tr -d '`')"
  if [ -n "$ra" ] && printf '%s\n' $names | grep -qx "$ra"; then pass "$f -> $ra"; else fail "$f: no valid 'Responsible agent:' line"; fi
  grep -q '^## Gates' "$f" && pass "$f has Gates section" || fail "$f: missing '## Gates' section"
done

# ------------------------------------------------------------- probes ------
# --probes: pipe the committed probe table (.claude/hooks/probes.tsv) into the hooks, then exercise
# audit/format/provenance/stop behaviour in a disposable git fixture under $TMPDIR (removed after).
# Payloads are inert: hooks only read them; nothing in a payload is executed. Fixture "keys" contain FAKE.
if [ "${1:-}" = "--probes" ]; then
  section "Probe table (.claude/hooks/probes.tsv)"
  F="$(mktemp -d "${TMPDIR:-/tmp}/scholarchain-probes.XXXXXX")" || { fail "cannot create probe fixture"; F=""; }
  mkdir -p "$F/fix/home/.ssh" "$F/fix/proj/docs" "$F/repo"
  printf 'FAKE\n' > "$F/fix/home/.ssh/id_rsa"; printf 'harmless\n' > "$F/fix/proj/docs/notes.md"
  ln -s ../home/.ssh/id_rsa "$F/fix/proj/innocent.txt"; ln -s ../home/.ssh "$F/fix/proj/configdir"
  probe() { # label expected(BLOCK|ALLOW|ASK|exit code) got want-substring output
    local ok=1; pn=$((pn + 1)); [ "$2" = "$3" ] || ok=0
    if [ -n "$4" ] && ! printf '%s' "$5" | grep -qF -- "$4"; then ok=0; fi
    if [ "$ok" = 1 ]; then pass "probe $1 -> $3"; else fail "probe $1: expected $2, got $3 ($(printf '%s' "$5" | head -n 2 | tr '\n' ' ' | cut -c1-160))"; fi
  }
  verdict() { # hook-dir hook root payload -> sets got and msg
    local o; o="$(printf '%s' "$4" | CLAUDE_PROJECT_DIR="$3" "$1/$2" 2>&1)"; local ec=$?
    msg="$o"; if [ $ec -eq 2 ]; then got=BLOCK
    elif [ $ec -eq 0 ] && printf '%s' "$o" | grep -q '"permissionDecision": "ask"'; then got=ASK
    elif [ $ec -eq 0 ]; then got=ALLOW; else got="EXIT$ec"; fi
  }
  TAB="$(printf '\t')"; n=0; pn=0
  while IFS="$TAB" read -r exp hook label json want; do
    case "$exp" in ''|\#*) continue ;; esac
    case "$hook" in *[!a-z.-]*|'') fail "probes.tsv: bad hook column '$hook'"; continue ;; esac
    json="${json//@ROOT@/$ROOT}"; json="${json//@FIX@/$F/fix}"
    verdict "$HOOK_DIR" "$hook" "$ROOT" "$json"; n=$((n + 1))
    probe "$label" "$exp" "$got" "${want:-}" "$msg"
  done < .claude/hooks/probes.tsv
  echo "NOTE  $n table probes run"

  section "Fixture probes (C4 hash audit, C5 formatter, provenance, test marker, D10(ii) held-out run protection, FE-P6-06 scripts and plan files)"
  pn=0
  G="$F/repo"; mkdir -p "$G/.claude/hooks" "$G/modules/services" "$G/tests/x" "$G/scripts" "$G/datasets/scripts"
  cp .claude/hooks/*.sh .claude/hooks/*.tsv "$G/.claude/hooks/"
  printf '#!/bin/sh\nexit 0\n' > "$G/scripts/check.sh"; printf '#!/bin/sh\nexit 0\n' > "$G/datasets/scripts/sample.sh"
  printf '#!/bin/sh\n# shared helper\n' > "$G/scripts/lib.sh"
  chmod +x "$G/scripts/check.sh" "$G/datasets/scripts/sample.sh" "$G/scripts/lib.sh"
  printf 'version = 3.8.3\nrunner.dialect = scala3\n' > "$G/.scalafmt.conf"
  ( cd "$G" && git init -q . && git add scripts datasets .scalafmt.conf && git -c user.email=p@x -c user.name=p commit -q -m fixture ) || fail "fixture git init"
  printf 'alpha' > "$G/modules/services/Prod.scala"; printf 'a' > "$G/tests/x/A.txt"; touch -t 202601010000 "$G/modules/services/Prod.scala"
  GH="$G/.claude/hooks"; spath="/usr/bin:/bin:$(dirname "$(command -v python3)")"
  audit() { # label agent expected-exit want side-effect [command]
    local pl="{\"session_id\":\"s\",\"tool_use_id\":\"$1\",\"agent_type\":\"$2\",\"tool_name\":\"Bash\",\"tool_input\":{\"command\":\"${6:-sbt test}\"}}"
    printf '%s' "$pl" | CLAUDE_PROJECT_DIR="$G" TMPDIR="$F" PATH="$spath" "$GH/audit-bash-writes.sh" --pre
    ( cd "$G" && eval "$5" )
    local o; o="$(printf '%s' "$pl" | CLAUDE_PROJECT_DIR="$G" TMPDIR="$F" PATH="$spath" "$GH/audit-bash-writes.sh" --post 2>&1)"
    probe "$1" "$3" "$?" "$4" "$o"
  }
  if PATH="$spath" command -v scalafmt >/dev/null 2>&1; then echo "NOTE  scalafmt present on the probe PATH; C5 missing-tool probe skipped"
  else audit "C5 Bash-changed Scala, scalafmt missing" test-engineer 2 "cs install scalafmt" 'printf "object T" > tests/x/T.scala'; fi
  audit "C4 same-size rewrite of production Scala, mtime restored" test-engineer 2 "modules/services/Prod.scala (owner: service-engineer)" \
    'touch -r modules/services/Prod.scala .ref; printf omega > modules/services/Prod.scala; touch -r .ref modules/services/Prod.scala; rm .ref'
  audit "C4 negative control: test-engineer changes tests/**" test-engineer 0 "" 'printf b > tests/x/A.txt'
  audit "C5 control: no Scala change" test-engineer 0 "" 'true'
  audit "C4 .git/config sentinel" test-engineer 2 ".git/config" 'git config core.fsmonitor false'
  audit "MED-2 test marker touched under sbt core/test" domain-engineer 2 "possible test-marker forgery" 'mkdir -p .claude && touch .claude/.last-test-run' "sbt core/test"
  audit "MED-2 test marker touched under sbt check (allowed)" service-engineer 0 "" 'touch .claude/.last-test-run' "sbt check"
  # R1: the marker now EXISTS; these touches change only its mtime (the test gate compares mtimes)
  audit "R1 existing marker, mtime-only touch under sbt core/test" domain-engineer 2 "possible test-marker forgery" 'touch -t 202701010000 .claude/.last-test-run' "sbt core/test"
  audit "R1 existing marker, mtime-only touch under sbt check (allowed)" service-engineer 0 "" 'touch -t 202702010000 .claude/.last-test-run' "sbt check"
  printf '/.claude/.last-test-run\n' >> "$G/.git/info/exclude"
  o="$(printf '{"session_id":"s","tool_use_id":"r1x","agent_type":"test-engineer","tool_name":"Bash","tool_input":{"command":"sbt test"}}' | CLAUDE_PROJECT_DIR="$G" TMPDIR="$F" PATH="$spath" "$GH/audit-bash-writes.sh" --pre 2>&1)"
  probe "R1 setup: marker gitignored (.git/info/exclude change reported)" 2 "$?" ".git/info/exclude" "$o"
  audit "R1 gitignored marker, mtime-only touch under sbt core/test" domain-engineer 2 "possible test-marker forgery" 'touch -t 202703010000 .claude/.last-test-run' "sbt core/test"
  # R1-b: Stop compares the marker with the session baseline
  stopq() { o="$(printf '{"session_id":"s","hook_event_name":"Stop","agent_type":"%s"}' "$2" | CLAUDE_PROJECT_DIR="$G" TMPDIR="$F" PATH="$spath" "$GH/verify-tests-ran.sh" 2>&1)"; probe "$1" "$3" "$?" "$4" "$o"; }
  audit "R1-b normal flow: audited sbt check touches the marker" service-engineer 0 "" 'touch -t 202704010000 .claude/.last-test-run' "sbt check"
  stopq "R1-b normal flow: Stop after an audited sbt check is allowed" service-engineer 0 ""
  audit "R1-b call starting a delayed background marker touch" domain-engineer 0 "" '( sleep 2; touch -t 202705010000 .claude/.last-test-run ) >/dev/null 2>&1 &' "sbt core/test"
  sleep 3
  stopq "R1-b delayed marker touch then Stop: refused" domain-engineer 2 "changed after the last audited Bash call"
  o="$(printf '{"session_id":"s","tool_use_id":"r1b","agent_type":"domain-engineer","tool_name":"Bash","tool_input":{"command":"sbt test"}}' | CLAUDE_PROJECT_DIR="$G" TMPDIR="$F" PATH="$spath" "$GH/audit-bash-writes.sh" --pre 2>&1)"
  probe "R1-b next Bash call reports the marker change" 2 "$?" ".claude/.last-test-run" "$o"
  stopq "R1-b Stop after the reset is allowed" domain-engineer 0 ""
  audit "HIGH-1 call before an inter-call change" test-engineer 0 "" 'true'
  printf 'gamma' > "$G/modules/services/Prod.scala"
  o="$(printf '{"session_id":"s","tool_use_id":"ic","agent_type":"test-engineer","tool_name":"Bash","tool_input":{"command":"sbt test"}}' | CLAUDE_PROJECT_DIR="$G" TMPDIR="$F" PATH="$spath" "$GH/audit-bash-writes.sh" --pre 2>&1)"
  probe "HIGH-1 inter-call change reported UNATTRIBUTED at the next --pre" 2 "$?" "INTER-CALL CHANGE" "$o"
  audit "HIGH-1 following call after the report is clean" test-engineer 0 "" 'true'
  printf 'n' > "$G/tests/x/N.txt"
  o="$(printf '{"session_id":"s","agent_type":"test-engineer","tool_name":"Write","tool_input":{"file_path":"%s"}}' "$G/tests/x/N.txt" | CLAUDE_PROJECT_DIR="$G" TMPDIR="$F" PATH="$spath" "$GH/post-write.sh" 2>&1)"
  probe "NEW-1 own Write recorded by post-write.sh" 0 "$?" "" "$o"
  o="$(printf '{"session_id":"s","tool_use_id":"nw","agent_type":"test-engineer","tool_name":"Bash","tool_input":{"command":"sbt test"}}' | CLAUDE_PROJECT_DIR="$G" TMPDIR="$F" PATH="$spath" "$GH/audit-bash-writes.sh" --pre 2>&1)"
  probe "NEW-1 next Bash --pre after an authorized Write is clean" 0 "$?" "" "$o"
  o="$(printf '{"session_id":"s","tool_use_id":"nopre","tool_name":"Bash","tool_input":{"command":"x"}}' | CLAUDE_PROJECT_DIR="$G" TMPDIR="$F" "$GH/audit-bash-writes.sh" --post 2>&1)"
  probe "audit fails closed without a pre snapshot" 2 "$?" "FAILED CLOSED" "$o"
  pv() { verdict "$GH" guard-dangerous.sh "$G" "{\"tool_name\":\"Bash\",\"agent_type\":\"$2\",\"tool_input\":{\"command\":\"$3\"}}"; probe "$1" "$4" "$got" "${5:-}" "$msg"; }
  pv "provenance: committed scripts/check.sh" infra-engineer "scripts/check.sh" ALLOW
  pv "provenance: committed dataset script" dataset-engineer "datasets/scripts/sample.sh --sample" ALLOW
  printf '# tampered\n' >> "$G/scripts/lib.sh"
  pv "MED-4 committed script with a modified sibling" infra-engineer "scripts/check.sh" BLOCK "scripts/lib.sh is modified relative to HEAD"
  ( cd "$G" && git checkout -q -- scripts/lib.sh ); printf '#!/bin/sh\n' > "$G/scripts/extra.sh"
  pv "MED-4 committed script with an untracked sibling" infra-engineer "scripts/check.sh" BLOCK "scripts/extra.sh is not tracked at HEAD"
  rm -f "$G/scripts/extra.sh"; cp -p "$G/scripts/check.sh" "$F/check.orig"
  mkdir -p "$G/.git/info"; printf 'scripts/*.sh filter=strip\n' >> "$G/.git/info/attributes"
  ( cd "$G" && git config filter.strip.clean "sed -e /^#evil/d" ); printf '#evil\n' >> "$G/scripts/check.sh"
  pv "LOW-3 clean filter cannot mask a modified script" infra-engineer "scripts/check.sh" BLOCK "scripts/check.sh is modified relative to HEAD"
  cp -p "$F/check.orig" "$G/scripts/check.sh"; ( cd "$G" && git config --unset filter.strip.clean ); : > "$G/.git/info/attributes"
  pv "provenance: clean again after restore" infra-engineer "scripts/check.sh" ALLOW
  printf '# edit\n' >> "$G/scripts/check.sh"; printf '#!/bin/sh\n' > "$G/datasets/scripts/new.sh"
  pv "provenance: modified scripts/check.sh" infra-engineer "scripts/check.sh" BLOCK "modified relative to HEAD"
  pv "provenance: modified script, other identity" documentation-engineer "scripts/check.sh" BLOCK "script-provenance"
  pv "provenance: uncommitted dataset script -> ask" dataset-engineer "datasets/scripts/new.sh --dry-run" ASK
  ( cd "$G" && git checkout -q -- scripts/check.sh && mv scripts scripts.real && ln -s scripts.real scripts )
  pv "provenance: script reached through a symlinked dir" infra-engineer "scripts/check.sh" BLOCK "symlink"
  touch -t 202601010000 "$G/.claude/.last-test-run"   # marker (created by the MED-2 probes) older than the Scala sources
  o="$(cd "$G" && CLAUDE_PROJECT_DIR="$G" "$GH/verify-tests-ran.sh" --check </dev/null 2>&1)"
  probe "verify-tests-ran --check with stale Scala" 2 "$?" "newer than the test marker" "$o"
  o="$(printf '{"hook_event_name":"Stop"}' | CLAUDE_PROJECT_DIR="$G" "$GH/verify-tests-ran.sh" 2>&1)"
  probe "Phase 1 probe 5: Stop with unverified Scala" 2 "$?" "sbt check" "$o"
  ( cd "$G" && rm -f modules/services/Prod.scala tests/x/T.scala && mkdir -p .claude && touch .claude/.last-test-run )
  o="$(CLAUDE_PROJECT_DIR="$G" "$GH/verify-tests-ran.sh" --check </dev/null 2>&1)"
  probe "verify-tests-ran --check fresh" 0 "$?" "OK" "$o"
  # D10(ii) held-out run protection (FE-P5-06, decisions-18 (c); replaces table probe [entry] service-engineer sbt clean check).
  # pv sets CLAUDE_PROJECT_DIR to the fixture root $G, which is the ROOT the guard resolves the directory against, so the
  # real repository's target/ is never read or created. A regular .gitignore lets sbt pass gitignore-missing. Setup failure = FAIL.
  HO="modules/hadoop/target/local-pipeline/er-candidates/heldout"
  case "$G" in "${TMPDIR:-/tmp}"/scholarchain-probes.*/repo) fxok=1 ;; *) fxok=0 ;; esac
  if [ "$fxok" = 1 ] && rm -f "$G/.gitignore" && printf '/target/\n' > "$G/.gitignore" && [ -f "$G/.gitignore" ] && [ ! -L "$G/.gitignore" ] && [ ! -e "$G/$HO" ]; then
    pv "D10(ii) service-engineer sbt clean check, held-out output absent" service-engineer "sbt clean check" ALLOW
  else pn=$((pn + 1)); fxok=0; fail "probe D10(ii) held-out output absent: fixture setup failed (fixture root '$G' not the mktemp fixture, .gitignore not created, or $HO already present)"; fi
  if [ "$fxok" = 1 ] && mkdir -p "$G/$HO" && [ -d "$G/$HO" ] && [ ! -L "$G/$HO" ] && [ "$(cd "$G/$HO" && pwd -P)" = "$(cd "$G" && pwd -P)/$HO" ]; then
    pv "D10(ii) service-engineer sbt clean check, held-out output present" service-engineer "sbt clean check" BLOCK "heldout-run-protected"
  else pn=$((pn + 1)); fail "probe D10(ii) held-out output present: fixture setup failed (cannot create a real directory $G/$HO)"; fi
  # FE-P6-06 (CR-P6-30 m2, n2): rows that execute a repository script, checked against committed stubs in the fixture, never the
  # real scripts/ tree. The stubs are never executed: the guard only checks provenance and returns a decision. Restore the real
  # scripts directory first (the provenance probes above replaced it by a symlink). Setup failure = one FAIL per skipped probe.
  RC="infra/terraform/environments/dev/runs/spark-dev-run-01.conf"
  if [ "$fxok" = 1 ] && ( cd "$G" && rm scripts && mv scripts.real scripts && mkdir -p scripts/emr infra/terraform/environments/dev/runs \
       && printf '#!/bin/sh\nexit 0\n' > scripts/plan.sh && printf '#!/bin/sh\nexit 0\n' > scripts/run-bounded-spark.sh \
       && printf '#!/bin/sh\nexit 0\n' > scripts/emr/check-submit-args.sh && chmod +x scripts/plan.sh scripts/run-bounded-spark.sh scripts/emr/check-submit-args.sh \
       && printf 'run_id = fixture\n' > "$RC" && git add scripts "$RC" && git -c user.email=p@x -c user.name=p commit -q -m fe-p6-06 ) \
       && [ ! -L "$G/scripts" ] && [ -x "$G/scripts/plan.sh" ]; then
    pv "FE-P6-06 plan.sh dev bootstrap (committed) -> ask" infra-engineer "scripts/plan.sh dev bootstrap" ASK "scripts/plan.sh <ENVNP> <STACK>"
    pv "FE-P6-06 plan.sh dev base (committed) -> ask" infra-engineer "scripts/plan.sh dev base" ASK "scripts/plan.sh <ENVNP> <STACK>"
    pv "FE-P6-06 plan.sh dev emr-run (committed) -> ask" infra-engineer "scripts/plan.sh dev emr-run" ASK "scripts/plan.sh <ENVNP> <STACK>"
    pv "FE-P6-06 run-bounded-spark.sh --dry-run, committed config" infra-engineer "scripts/run-bounded-spark.sh --dry-run dev $RC" ALLOW
    pv "FE-P6-06 check-submit-args.sh (committed, +x)" infra-engineer "scripts/emr/check-submit-args.sh" ALLOW
    printf 'max_core_nodes = 99\n' >> "$G/$RC"
    pv "FE-P6-06 --dry-run with a modified run config" infra-engineer "scripts/run-bounded-spark.sh --dry-run dev $RC" BLOCK "script-provenance"
    ( cd "$G" && git checkout -q -- "$RC" ); printf '# tampered\n' >> "$G/scripts/plan.sh"
    pv "FE-P6-06 plan.sh modified relative to HEAD" infra-engineer "scripts/plan.sh dev base" BLOCK "scripts/plan.sh is modified relative to HEAD"
    ( cd "$G" && git checkout -q -- scripts/plan.sh ); chmod -x "$G/scripts/emr/check-submit-args.sh"
    pv "FE-P6-06 check-submit-args.sh executable bit removed" infra-engineer "scripts/emr/check-submit-args.sh" BLOCK "executable bit differs from HEAD"
    chmod +x "$G/scripts/emr/check-submit-args.sh"
    pv "FE-P6-06 plan.sh clean again after restore" infra-engineer "scripts/plan.sh dev emr-run" ASK
  else pn=$((pn + 9)); fail "probe FE-P6-06 script provenance (9 probes): fixture setup failed (scripts restore, stubs or fixture commit)"; fi
  # n2 symlinked TFPLAN (path-escape check) and m1 (a regular plan file stays reachable by the Bash rows, never by Read)
  if mkdir -p "$G/infra/terraform/bootstrap" "$G/infra/terraform/environments/dev/base" && ln -s "$F/fix/home/.ssh/id_rsa" "$G/infra/terraform/bootstrap/tfplan" \
       && printf 'FAKE PLAN\n' > "$G/infra/terraform/environments/dev/base/tfplan" && ln -s infra/terraform/environments/dev/base/tfplan "$G/plan-notes.txt"; then
    pv "FE-P6-06 n2 shasum of a symlinked TFPLAN leaving the repository" deploy-engineer "shasum -a 256 infra/terraform/bootstrap/tfplan" BLOCK "path-escape-or-secret"
    pv "FE-P6-06 m1 shasum of a regular plan file (Bash row unaffected by the Read fence)" deploy-engineer "shasum -a 256 infra/terraform/environments/dev/base/tfplan" ALLOW
    pv "FE-P6-06 m1 terraform show of a regular plan file" deploy-engineer "terraform -chdir=infra/terraform/environments/dev/base show -no-color tfplan" ALLOW
    verdict "$GH" guard-dangerous.sh "$G" "{\"tool_name\":\"Read\",\"agent_type\":\"deploy-engineer\",\"tool_input\":{\"file_path\":\"$G/plan-notes.txt\"}}"
    probe "FE-P6-06 m1 Read of an innocent name that resolves to a plan file" BLOCK "$got" "Terraform plan file" "$msg"
  else pn=$((pn + 4)); fail "probe FE-P6-06 TFPLAN symlink and plan-file fence (4 probes): fixture setup failed"; fi
  echo "NOTE  $pn fixture probes run"
  case "$F" in "${TMPDIR:-/tmp}"/scholarchain-probes.*) rm -r "$F"; [ ! -e "$F" ] || fail "probe fixture $F was not removed (the human removes it)" ;; esac
fi

echo
if [ "$fails" -eq 0 ]; then echo "RESULT: PASS (0 failures)"; exit 0; fi
echo "RESULT: FAIL ($fails failures)"; exit 1
