# Phase 06 FE-P6-05: factory-engineer report (2026-11-16), verbatim

- Work order: docs/agent-runs/phase-06/prompt-fe-p6-05-activate-sparkit.md.
- Harness note on hand-back: the output matched an instruction-shaped pattern (settings-json). The report only states that settings.json is unchanged and that no rule in it names `sbt sparkIT`; no settings change was made or requested.

## Orchestrator observations (2026-11-16)

- `git rev-parse` HEAD at session start: `e8c6ecc400382307698e4308dc7f6bc3000890cd`; tree clean.
- `git diff --stat` after the agent: .claude/hooks/bash-allowlist.tsv 2, .claude/hooks/probes.tsv 40, docs/agents.md 4 (3 files, 29 insertions, 17 deletions).
- `git status --porcelain`: the three files above modified; untracked prompt-fe-p6-05-activate-sparkit.md (and this file).
- `.claude/hooks/validate-agent-files.sh` (orchestrator run): `RESULT: PASS (0 failures)`; `PASS  bash-allowlist.tsv parses (95 identity rows, 3 groups)`; `PASS  docs/agents.md Bash allowlist block matches bash-allowlist.tsv (120 rows, sha256 71034e28f6b0)`.
- `--probes` was not re-run by the orchestrator (no orchestrator allowlist row); the agent's reported result is below.
- Not verified by the orchestrator: the justification cites "row reviewed CR-P6-18 APPROVE". CR-P6-18 is not mentioned in fe-p6-04-report.md, and the orchestrator could not search review-report.md (no grep row; file exceeds the Read limit). The human should confirm that CR-P6-18 exists and is an APPROVE.

## Agent report, verbatim (contract block)

```text
STATUS: COMPLETE
OWNER: factory-engineer
WORK ORDER: FE-P6-05 (Phase 6): prepare the ACTIVE allowlist entry for spark-engineer to run exactly standalone `sbt sparkIT`, for human ratification; no self-ratification, no sbt, no commit.
FILES CREATED: none
FILES MODIFIED:
- /Users/taufeeqpatel/Desktop/ScholarChain/.claude/hooks/bash-allowlist.tsv (line 105: STATUS inactive:FE-P6-04-ratification-pending -> active; justification rewritten; IDENTITY, MODE allow, PROV -, SPEC `sbt sparkIT` unchanged)
- /Users/taufeeqpatel/Desktop/ScholarChain/docs/agents.md (mirror row line 205; source sha256 in the "Bash allowlist (machine-checked)" paragraph, line 132)
- /Users/taufeeqpatel/Desktop/ScholarChain/.claude/hooks/probes.tsv (FE-P6-04 header comment now describes the active state; the [FE-P6-04 T] owner probe BLOCK entry-inactive -> ALLOW; three trailing-token probes entry-inactive -> no-allowlist-entry; 10 new [FE-P6-05] probes; every other probe unchanged)
COMMANDS RUN:
- git log --oneline --max-count=8; git show --stat e8c6ecc; git status --porcelain; git diff --stat HEAD -- build.sbt project .claude docs/agents.md CLAUDE.md .mcp.json (no output); git rev-parse HEAD (e8c6ecc400382307698e4308dc7f6bc3000890cd); git diff --stat e59d753 e8c6ecc -- build.sbt project; git log --oneline -- build.sbt project/SparkItRuns.scala project/SparkItJUnitRunner.java project/spark-it.properties project/LocalRuns.scala (latest: e8c6ecc)
- Refused by the guard, nothing executed: `git grep -n awsIsolationEnv -- build.sbt project` and `git show --stat --no-ext-diff e8c6ecc -- <path>` (no-allowlist-entry). I used Read instead.
- PostToolUse validator runs during editing: after the tsv edit, FAIL B1 "row 67 differs" (expected, mirror not yet updated); after the mirror edit, FAIL B1 "embedded source sha256 ['510c7e49...'] does not equal bash-allowlist.tsv sha256 71034e28f6b00faecfc09be9ab13e3204b207b80b78c034a7cc760fcf040d707" (expected); after the sha256 edit, no error.
- .claude/hooks/validate-agent-files.sh: RESULT: PASS (0 failures)
- .claude/hooks/validate-agent-files.sh --probes: "NOTE 738 table probes run", "NOTE 38 fixture probes run", "RESULT: PASS (0 failures)"; all FE-P6-04, FE-P6-05, D15c R6, D21, D10(ii) and FE-P6-01 probes PASS
- python3 -I -m json.tool .claude/settings.json: parsed (unchanged file)
- python3 -I -m json.tool .mcp.json: parsed (unchanged file)
- test -x .claude/hooks/guard-dangerous.sh: exit 0
- git diff --stat; git diff; git ls-files --others --exclude-standard -- .claude CLAUDE.md docs/agents.md .mcp.json (no output); git status --porcelain
- Not run: any sbt command (including sbt sparkIT), git add, git commit.
TEST RESULTS: not applicable (no source change; sbt not run, per the work order). Probe results: 738 table probes and 38 fixture probes, 0 failures.
VALIDATION RESULTS:
- JSON: PASS .claude/settings.json parses; PASS .mcp.json parses (validator and json.tool).
- Shell syntax: PASS bash -n on all 9 hook scripts (validator); no hook script was changed. NOTE shellcheck not installed; skipped.
- Executable bit: PASS for all 6 referenced hooks (validator); test -x guard-dangerous.sh exit 0.
- Frontmatter: PASS name/description/tools for all 18 agent files; code-reviewer reviewer fence checked.
- Mirror: PASS "bash-allowlist.tsv parses (95 identity rows, 3 groups)"; PASS "docs/agents.md Bash allowlist block matches bash-allowlist.tsv (120 rows, sha256 71034e28f6b0)".
ASSUMPTIONS:
- The bytes approved in CR-P6-17 and CR-P6-18 are the bytes committed in e8c6ecc. Evidence is in Task 1 above; no reviewer recorded a sha256.
- After activation the guard emits no decision for the bare command and settings.json has no rule naming `sbt sparkIT`, so Claude Code's normal permission prompt decides. This is the FE-P6-01 precedent. settings.json is unchanged and needs no change.
- The child-output log path (root project task log under target/streams/) is inferred from `streams.value.log` in a root task. It has not been observed.
RISKS:
- The row has PROV `-` and SparkItRuns has no HEAD check, so `sbt sparkIT` runs whatever build code is in the working tree. build.sbt and project/ must be clean when the first run happens (CR-P6-18).
- Code in the fork (spark-engineer's src/it tests, Spark, Hadoop) is opaque. It can write outside the attempt, for example Hadoop's default hadoop.tmp.dir (unverified), and its writes to gitignored target/ paths are not audited. Code review of src/it suites before the first run remains the control.
- No src/it suite exists yet, so sparkIT will refuse ("no integration test is defined ... NOT a pass") until spark-engineer adds one.
- `sbt clean` (and `sbt clean check`) removes attempt folders. Evidence must be copied into the Phase 6 records after a real run. No protection rule was added (your earlier direction).
- The archive is read-only by convention only (stated by SparkItRuns).
MANUAL ACTION REQUIRED:
- Human: review the diff below, ratify FE-P6-05, record the human-gate entry, stage exactly the three files listed, commit, then restart Claude Code before anyone runs sbt sparkIT.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human constitutional ratification of FE-P6-05; human commit of the three files alone; a fresh session; then spark-engineer adds the first src/it suite (code review), runs `sbt sparkIT` once, and the orchestrator archives attempt-<n>/junit-result.txt and result.txt.
PRIVILEGE DELTA:
- spark-engineer: widened by exactly one Bash shape, `sbt sparkIT` (allow mode, exactly two tokens, no arguments). Any extra or leading token, combined task, project-scoped form or case variant stays refused (probes).
- Every other identity (16 other agents, code-reviewer, ORCHESTRATOR): unchanged.
- Tool lists, MCP grants, ownership rows and autonomy: unchanged for all agents.
FLOOR INVARIANTS: All intact. No hook script, settings.json, .mcp.json, agent file, CLAUDE.md or ownership row was touched. Held-out protections pass unchanged: heldout-eval-human-only, heldout-pipeline-human-only, heldout-run-protected (D10(ii) fixture probes both states), R6 Read/Grep fences (D15c R6 probes), G9-c, and the settings.json held-out denies. New probes confirm that held-out rules fire before sparkIT matching, including the lower-case `goldsetevalheldout`.
AUDIT RESULTS: Collision PASS; Orphan PASS; One-writer PASS.
COMPLETE DIFF: `git diff -- .claude/hooks/bash-allowlist.tsv .claude/hooks/probes.tsv docs/agents.md` in the working tree (pasted verbatim in the agent's hand-back).
RATIFICATION REQUIRED: Yes. This is a constitutional amendment prepared by factory-engineer, not self-ratified. A human must review the diff, ratify FE-P6-05, commit the three files, and restart the session. It has no effect before then, and sbt sparkIT must not be run before the restart.
```
