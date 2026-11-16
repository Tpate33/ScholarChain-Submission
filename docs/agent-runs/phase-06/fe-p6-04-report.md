# Phase 06 FE-P6-04: factory-engineer report (2026-11-16), verbatim

- Work order: docs/agent-runs/phase-06/prompt-be-p6-03-sparkit.md and prompt-be-p6-03-repair-01.md (human direction); dispatched text asks for the inactive form plus the exact activation edit.
- Harness note on hand-back: the output matched an instruction-shaped pattern (settings-json). The report only notes that no `settings.json` rule names `sbt sparkIT`; no settings change was made or requested.

## Orchestrator observations (2026-11-16)

- `.claude/hooks/validate-agent-files.sh`: `RESULT: PASS (0 failures)`; `PASS  bash-allowlist.tsv parses (95 identity rows, 3 groups)`; `PASS  docs/agents.md Bash allowlist block matches bash-allowlist.tsv (120 rows, sha256 510c7e498f2c)`.
- `git diff` of bash-allowlist.tsv and docs/agents.md: the spark-engineer `sbt sparkIT` row status `inactive:B10-alias-pending` -> `inactive:FE-P6-04-ratification-pending`, justification replaced, mode allow, provenance `-`, spec unchanged; mirror row identical; sha256 `19fa3b7f…` -> `510c7e498f2c223a233359ddd1fdf2f51f299a0b44fdc5d522c14c6cd31acf4f`; 120 rows.
- `git diff --stat`: .claude/hooks/bash-allowlist.tsv 2, .claude/hooks/probes.tsv 48, build.sbt 115, docs/agent-runs/phase-06/review-report.md 290, docs/agents.md 4, project/LocalRuns.scala 4 (6 files, 450 insertions, 13 deletions).
- `git status --porcelain`: the six modified files above plus untracked be-p6-03-report.md, prompt-be-p6-03-repair-01.md, prompt-be-p6-03-sparkit.md, prompt-sp-p6-02-step-0.md, repair-08.md, sp-p6-02-step-0-report.md, modules/spark/src/test/scala/scholarchain/, project/SparkItJUnitRunner.java, project/SparkItRuns.scala, project/spark-it.properties.

## Agent report, verbatim (summary paragraphs and contract block)

I've prepared FE-P6-04 for your ratification. The `sbt sparkIT` row for spark-engineer is still **inactive** in the file, so nothing is granted yet. Both validator runs pass.

**What changed** (only these three files):
- **`.claude/hooks/bash-allowlist.tsv`:** the existing spark-engineer `sbt sparkIT` row keeps its identity, its exact two-token shape and mode `allow`. Only two fields changed: the status is now `inactive:FE-P6-04-ratification-pending` (was `inactive:B10-alias-pending`), and the justification was replaced. I checked every claim in the new justification against `build.sbt`, `project/SparkItRuns.scala` and `project/spark-it.properties`.
- **`docs/agents.md`:** the mirror row was updated the same way. The row count stays 120 and the source sha256 is now `510c7e498f2c223a233359ddd1fdf2f51f299a0b44fdc5d522c14c6cd31acf4f` (the value the validator printed).
- **`.claude/hooks/probes.tsv`:** the old probe that encoded the B10 state became a pointer comment. I added 35 probes after the FE-P6-01 section, following the same pattern:
  - **Changes on activation:** the owner row and three trailing-token forms (`sbt sparkIT check`, `sbt sparkIT sparkIT`, a trailing `-D` property) are refused as `entry-inactive` today.
  - **Same in both states:** these spark-engineer shapes are refused as `no-allowlist-entry`:
    - `sbt clean sparkIT` and `sbt check sparkIT`
    - `sbt sparkAnalytics/sparkIT`, `sbt sparkAnalytics/SparkIT/test` and `sbt sparkAnalytics/SparkIT/executeTests`
    - `sbt SPARKIT` and the absolute sbt path

    Semicolon chaining is refused as `shell-syntax-gate`.
  - **Held-out rules fire first:** `sbt sparkIT goldSetEvalHeldout`, `sbt sparkIT localPipelineHeldout` and `sbt localPipelineHeldout sparkIT` hit the held-out rules before any allowlist matching.
  - **Every other identity:** all 17 other agents and the orchestrator are refused; code-reviewer gets `reviewer-read-only`.

**Validation (observed output)**
- `.claude/hooks/validate-agent-files.sh`: `RESULT: PASS (0 failures)`, including `bash-allowlist.tsv parses (95 identity rows, 3 groups)`. The intermediate sha256 mismatch was fixed and re-checked by the PostToolUse validation hook with no error.
- `.claude/hooks/validate-agent-files.sh --probes`: `NOTE 728 table probes run`, every FE-P6-04 probe `PASS`, `NOTE 38 fixture probes run`, `RESULT: PASS (0 failures)`.
- JSON parse, `bash -n` on all 9 hooks, executable bits and agent frontmatter all pass inside the validator. shellcheck is not installed.

**Activation edit, to apply only after you ratify (not applied):**
- **(a) `bash-allowlist.tsv`:** change that row's status from `inactive:FE-P6-04-ratification-pending` to `active`, editing only that field and keeping every TAB.
- **(b) `docs/agents.md`:** the same change in the mirror row.
- **(c) sha256:** I can't compute the new value in advance (factory-engineer has no hashing command). After edit (a), the validator prints `bash-allowlist.tsv sha256 <new>`, which then goes into the docs paragraph; the row count stays 120.
- **(d) Owner probe:**
  `ALLOW	guard-dangerous.sh	[FE-P6-04 T] spark-engineer sbt sparkIT (active, allow; was BLOCK entry-inactive)	{"tool_name": "Bash", "tool_input": {"command": "sbt sparkIT"}, "agent_type": "spark-engineer"}`
  (no substring field)
- **(e) Trailing-token probes:** the three rows keep BLOCK, their substring becomes `no-allowlist-entry`, and their labels become `(active: extra token, no-allowlist-entry; was entry-inactive)`. The header comment changes to say the `[FE-P6-04 T]` rows now describe the active state.

Then run both validator commands, and you ratify, commit and restart the session.

**Privilege delta:** none now. After activation, spark-engineer gains exactly one Bash shape, `sbt sparkIT`. Every other identity, every tool list and every ownership row is unchanged.

**Commit scope:** no rule requires a separate commit. Two things matter:
- **Commit the build work first.** The BE-P6-03 build code is still uncommitted, and the row has no committed-code check, so it should not be activated until that code is committed.
- **Activate later, in its own change.** Activation needs a separate later edit after you ratify, followed by a restart. This matches the FE-P6-01/FE-P6-02 precedent (commit `6be1f1d`).

```text
STATUS: COMPLETE
OWNER: factory-engineer
WORK ORDER: FE-P6-04 (Phase 6): prepare, for human ratification, the activation of the existing standalone `sbt sparkIT` allowlist row for spark-engineer; keep the row INACTIVE in this change.
FILES CREATED: none
FILES MODIFIED:
- /Users/taufeeqpatel/Desktop/ScholarChain/.claude/hooks/bash-allowlist.tsv (line 105: status and justification only)
- /Users/taufeeqpatel/Desktop/ScholarChain/docs/agents.md (mirror row line 205; source sha256 in the "Bash allowlist (machine-checked)" paragraph)
- /Users/taufeeqpatel/Desktop/ScholarChain/.claude/hooks/probes.tsv (line 125 old B10-state probe -> comment; new FE-P6-04 section appended at the end: 10 comment lines, 35 probes)
COMMANDS RUN:
- .claude/hooks/validate-agent-files.sh (baseline): RESULT: PASS (0 failures)
- .claude/hooks/validate-agent-files.sh (after the tsv and docs row edits, before the sha256 update): RESULT: FAIL (1 failures), "FAIL B1 agreement: embedded source sha256 ['19fa3b7f...'] does not equal bash-allowlist.tsv sha256 510c7e498f2c223a233359ddd1fdf2f51f299a0b44fdc5d522c14c6cd31acf4f" (expected; fixed by the sha256 edit, then re-checked by the PostToolUse --hook run with no error)
- .claude/hooks/validate-agent-files.sh --probes (final): RESULT: PASS (0 failures); "NOTE 728 table probes run"; "NOTE 38 fixture probes run"
- git diff --stat; git diff -- .claude/hooks/bash-allowlist.tsv docs/agents.md; git diff -- .claude/hooks/probes.tsv; git ls-files --others --exclude-standard -- .claude CLAUDE.md docs/agents.md .mcp.json (no output)
- Refused (not executed): one compound grep pipeline, blocked by the guard's shell-syntax-gate; I switched to Read.
- No sbt command run. Nothing staged or committed.
TEST RESULTS: not applicable (no source change; sbt not run, per the work order). Probe results: 728 table probes and 38 fixture probes, 0 failures.
VALIDATION RESULTS:
- JSON: PASS .claude/settings.json parses; PASS .mcp.json parses (validator).
- Shell syntax: PASS bash -n on all 9 hook scripts; NOTE shellcheck not installed; skipped.
- Executable bit: PASS for all 6 referenced hooks.
- Frontmatter: PASS name/description/tools for all 18 agent files; code-reviewer reviewer fence checked.
- Mirror: docs/agents.md Bash allowlist block matches bash-allowlist.tsv (120 rows, sha256 510c7e498f2c...), final state.
ASSUMPTIONS:
- CR-P6-16 and CR-P6-17 (APPROVE), BE-P6-03 and BE-P6-03 repair 01 are cited from the work order; I did not open the review reports.
- Every technical claim in the justification was checked in build.sbt (sparkIT task, checkSteps without sparkIT, SparkIT routes refused) and project/SparkItRuns.scala (TimeoutSeconds = 120, StrippedPrefixes AWS_/SCHOLARCHAIN_/GIT_/MUNIT_, SCHOLARCHAIN_CFG_ refusal, segment rules, NOFOLLOW layout and walk, attempt-<n> create-once, nonce-bound verdict). These are working-tree versions (BE-P6-03 not yet committed).
RISKS:
- The row has no committed-code check (PROV -), so once active, sbt sparkIT runs whatever build code is in the working tree. Do not activate until BE-P6-03 is committed.
- After activation the guard emits no decision and settings.json has no rule naming sbt sparkIT, so Claude Code's normal permission prompt applies (same as erDecisionGroupingDev). settings.json was not touched, by constraint.
- The task runs test code spark-engineer may write (an opaque tool). Its writes under modules/spark/target/ are gitignored and not audited, and it can read the archive outside the repository; read-only access to the archive is by convention only (stated by SparkItRuns itself).
- The post-activation sha256 is not precomputed (no hashing shape for factory-engineer); it must be taken from the validator's output at activation.
MANUAL ACTION REQUIRED:
- Review and ratify (or reject) FE-P6-04.
- Commit BE-P6-03 and this inactive-row change. The human runs the git commands.
- After ratification, have the orchestrator route the activation edit to factory-engineer, then commit and restart the session.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human constitutional ratification of FE-P6-04; then the activation work order (status flip plus probe transition), validator and --probes, human commit, fresh session.
MATRIX BASIS: Bash allowlist row "spark-engineer | inactive:B10-alias-pending | allow | - | sbt sparkIT | Phase 6 local Spark integration test" (already in the ratified matrix); Lifecycle routing "Spark graph construction ... -> spark-engineer (Spark tests, sample run, output invariants)"; matrix "spark-engineer: modules/spark/**" (output under modules/spark/target/spark-it/). Proposed amendment: FE-P6-04 (status re-tag now, activation after ratification). No other matrix change.
PRIVILEGE DELTA:
- Now: unchanged for every agent (row inactive before and after; only its status tag and justification changed).
- After activation: spark-engineer widened by exactly one Bash shape, `sbt sparkIT` (allow mode, exactly two tokens). Every other identity, every tool list, all MCP servers, ownership and autonomy unchanged.
FLOOR INVARIANTS: All intact; no hook script, settings.json, .mcp.json, agent file or ownership row touched. Held-out rules (heldout-eval-human-only, heldout-pipeline-human-only, heldout-run-protected, G9-c, R6) and all Phase 5 probes are preserved and passing; new probes confirm the held-out rules fire before sparkIT matching. The dangerous-command, secret-read, terraform apply/destroy, destructive-AWS, EMR, force-push, constitution-write, test-marker and formatter guards are unchanged. sparkIT is not part of `check` and does not write .claude/.last-test-run (markTestRun reads Test/executeTests only). Nothing refused.
AUDIT RESULTS:
- Collision: PASS no lifecycle step claimed twice; agent names unique; one sbt sparkIT row, one owner identity; no duplicate hook registrations.
- Orphan: PASS every agent routed; every referenced hook exists and is executable; format-scala.sh, lib-baseline.sh and lib-ownership.sh referenced; commands map to owners.
- One-writer: PASS every path pattern has exactly one owner row; lib-ownership.sh anchors match the table.
COMPLETE DIFF: see `git diff -- .claude/hooks/bash-allowlist.tsv .claude/hooks/probes.tsv docs/agents.md` in the working tree (the agent pasted the row diffs; the orchestrator's own git diff of bash-allowlist.tsv and docs/agents.md is summarised above).
OUT-OF-SCOPE ITEMS: none declined. Not done, by constraint: settings.json allow rule for `sbt sparkIT` (left to the normal permission prompt); activating the row (after ratification only); running sbt sparkIT (spark-engineer, after activation and restart).
RATIFICATION REQUIRED: Yes. This is a constitutional amendment prepared by factory-engineer; it is not self-ratified. A human must review the diff, ratify FE-P6-04, commit, and restart the session. The sparkIT row stays inactive until a separate post-ratification activation edit is applied, validated, committed and followed by a fresh session.
```
