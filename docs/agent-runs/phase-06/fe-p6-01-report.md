# Phase 06 FE-P6-01: factory-engineer report (2026-11-16), verbatim

- Work order (verbatim): docs/agent-runs/phase-06/prompt-fe-p6-01-allowlist.md
- Constitutional change prepared for human ratification; not ratified, not committed; the new row is INACTIVE.
- Harness note on hand-back: the subagent output matched an instruction-shaped pattern (settings-json). The orchestrator treats the report's mention of a possible `settings.json` allow rule as an out-of-scope suggestion for the human, not as an instruction; no settings change was made or requested.

## Orchestrator observations after the report (2026-11-16)

`git status --porcelain`:
```
 M .claude/hooks/bash-allowlist.tsv
 M .claude/hooks/probes.tsv
 M docs/agents.md
?? docs/agent-runs/phase-06/prompt-fe-p6-01-allowlist.md
```
`git diff --stat`: bash-allowlist.tsv 1 +; probes.tsv 47 (46 +, 1 -); docs/agents.md 3 (2 +, 1 -); 3 files changed, 49 insertions(+), 2 deletions(-).
`.claude/hooks/validate-agent-files.sh` (orchestrator run): `RESULT: PASS (0 failures)`, including `PASS  bash-allowlist.tsv parses (95 identity rows, 3 groups)` and `PASS  docs/agents.md Bash allowlist block matches bash-allowlist.tsv (120 rows, sha256 6d1a92c6694f)`.

## Agent report, verbatim

STATUS: COMPLETE
OWNER: factory-engineer
WORK ORDER: FE-P6-01 (Phase 6). Prepare, for human ratification, an inactive Bash allowlist row that lets only `entity-resolution-engineer` run the dev-only command `sbt erDecisionGroupingDev`, plus refusal probes and the docs/agents.md mirror update. The activation edit is presented separately below.

Summary: I added the row in INACTIVE state, added 35 probes and updated the docs mirror. Both validator runs pass. One side effect: the Edit tool dropped a trailing TAB from one existing Phase 5 probe line, and I could not put it back with Edit. That probe's verdict is unchanged, but the line is not byte-identical to before. Details and a fix are under RISKS and MANUAL ACTION REQUIRED.

FILES CREATED: none
FILES MODIFIED:
- /Users/taufeeqpatel/Desktop/ScholarChain/.claude/hooks/bash-allowlist.tsv (one new row, at line 101)
- /Users/taufeeqpatel/Desktop/ScholarChain/.claude/hooks/probes.tsv (35 new probes and 7 comment lines at the end; one Phase 5 line changed by accident, see RISKS)
- /Users/taufeeqpatel/Desktop/ScholarChain/docs/agents.md (mirror row, row count 119 -> 120, source sha256)

COMMANDS RUN:
- `git rev-parse HEAD` -> 7b72681f827b3cf19851aa7aef3c4d7603c5d15a (matches commit Y)
- `git status --porcelain` -> `?? docs/agent-runs/phase-06/prompt-fe-p6-01-allowlist.md` (as expected)
- `git ls-files -- .claude/hooks`, `git ls-files -- project` (inspection)
- `.claude/hooks/validate-agent-files.sh` -> RESULT: PASS (0 failures)
- `.claude/hooks/validate-agent-files.sh --probes` -> RESULT: PASS (0 failures); "NOTE 696 table probes run" (661 before plus 35 new); "NOTE 38 fixture probes run"
- `python3 -I -m json.tool .claude/settings.json` -> parsed (exit 0)
- `python3 -I -m json.tool .mcp.json` -> parsed (exit 0)
- `bash -n .claude/hooks/guard-dangerous.sh` -> exit 0, no output
- `test -x .claude/hooks/guard-dangerous.sh` -> exit 0
- `git diff --stat`, `git diff`
- Not run: any sbt command, `sbt erDecisionGroupingDev`, staging or commits.

TEST RESULTS: not applicable. No source changed and no sbt command was run, as the work order requires.

VALIDATION RESULTS:
- JSON: both files parse (validator "PASS .claude/settings.json parses", "PASS .mcp.json parses", plus the separate json.tool calls).
- Shell syntax: validator `bash -n` PASS for all 9 hook scripts. I changed no hook script. Message: "NOTE shellcheck not installed; skipped (bash -n still enforced)".
- Executable bits: validator "PASS referenced hook exists and is executable" for all 6 registered hooks; format-scala.sh is invoked by a registered hook; `test -x guard-dangerous.sh` exit 0.
- Frontmatter: all 18 agent files PASS for name, description and explicit tools. No agent file changed.
- Allowlist: "PASS bash-allowlist.tsv parses (95 identity rows, 3 groups)"; "PASS docs/agents.md Bash allowlist block matches bash-allowlist.tsv (120 rows, sha256 6d1a92c6694f)".
- Validator output while editing: after the TSV edit, the PostToolUse validator failed with "B1 agreement: Bash allowlist row 63 differs ... (docs has 119 rows, tsv 120)". After the mirror row, it failed with "embedded source sha256 [...eb708cd4...] does not equal bash-allowlist.tsv sha256 6d1a92c6694f12411486947654839d310746f7c1a7f2bfe7332824fa7468de44". Both were fixed by the docs edits; I took the new sha256 from that message because I have no allowlisted shasum. The final full runs pass.
- New probe results, all PASS, inactive state:
  - [FE-P6-01 T], 4 rows: `entity-resolution-engineer` `sbt erDecisionGroupingDev`; the same with trailing `check`; the task name twice; and trailing `-Dscholarchain.x=y`. All four give BLOCK `entry-inactive`.
  - `entity-resolution-engineer`, BLOCK `no-allowlist-entry`:
    - `sbt clean erDecisionGroupingDev`
    - `sbt check erDecisionGroupingDev`
    - `sbt entityResolution/erDecisionGroupingDev`
    - `sbt ERDECISIONGROUPINGDEV`
    - `sbt erDecisionGrouping`
    - `/usr/local/bin/sbt erDecisionGroupingDev`
    - direct `sbt entityResolution/runMain ...DecisionRunMain`
    - `sbt erDecisionGroupingHeldout`
  - `sbt erDecisionGroupingDev; sbt check` gives BLOCK `shell-syntax-gate`.
  - Held-out rules fire first:
    - `sbt erDecisionGroupingDev goldSetEvalHeldout` (ER engineer and `hadoop-engineer`) -> `heldout-eval-human-only`
    - `sbt erDecisionGroupingDev localPipelineHeldout` -> `heldout-pipeline-human-only`
    - `sbt localPipelineHeldout erDecisionGroupingDev` -> `heldout-pipeline-human-only`
  - Every other identity running `sbt erDecisionGroupingDev`, ORCHESTRATOR included, gives BLOCK `no-allowlist-entry`: `test-engineer`, `service-engineer`, `hadoop-engineer`, `factory-engineer`, `build-engineer`, `domain-engineer`, `dataset-engineer`, `spark-engineer`, `storage-engineer`, `rag-engineer`, `infra-engineer`, `deploy-engineer`, `experiment-engineer`, `documentation-engineer`, `incident-responder`, `dependency-updater`. `code-reviewer` gives BLOCK `reviewer-read-only`.

ASSUMPTIONS:
- The task's behaviour, from reading /Users/taufeeqpatel/Desktop/ScholarChain/build.sbt (lines 398-412) and /Users/taufeeqpatel/Desktop/ScholarChain/project/LocalRuns.scala (lines 1000-1816), matches the justification:
  - It is a root dynamic task. It runs `erDevBeforeCompile` before compiling and `erDevPreflight` after.
  - Refusals: `configOverrideProblem` (any SCHOLARCHAIN_CFG_*); `devPathProblems` (the spellings heldout, held-out, held_out and "held out" in any case, plus the prefixes gold/, target/streams/, target/local-runs/heldout/ and the held-out attempts root); `erDevCodeScopeCheck` (scope build.sbt project modules/core modules/entity-resolution; index flags, status, raw-byte compare with HEAD, .gitignore committed; codeVersion equal in both manifests and HEAD or an ancestor; `git diff C HEAD` limited to the two manifests).
  - It needs exactly one complete localPipelineDev attempt. It is create-once on both output roots and never overwrites the overlay.
  - It forks DecisionRunMain, then GroupingRunMain, 45 s each.
  - Outputs: target/local-runs/dev/er-decisions, er-assignments and er-run-roots.conf; JVM scratch under target/local-runs/dev.
- No other path admits the command today:
  - `@SBT-ALL` has only exact `sbt check`, `sbt clean check`, `sbt compile`, `sbt Test/compile` and `sbt test`.
  - `<SBT:entityResolution>` only accepts `entityResolution/{compile,Test/compile,test,scalafmt,Test/scalafmt,scalafmtCheck}` (guard line 165).
  - `<SBTTEST>` only accepts `<project>/test`.
  - settings.json has no allow, ask or deny rule naming erDecisionGroupingDev.
  - The guard never emits allow.
- The verdicts after activation come from reading guard-dangerous.sh (bash_check, lines 505-545). They have not been observed; the first --probes run after activation is their first observation.
- I used provenance `-`, matching the existing sbt rows: sbt is not a repository script.

RISKS:
- Phase 5 probe line changed by accident. On the line `ALLOW guard-dangerous.sh [D21] control: ORCHESTRATOR git log naming a localPipelineHeldout path (rule is sbt-only) ...`, the Edit tool dropped the trailing TAB, i.e. the empty 5th column. The validator reads this field as empty either way, so the verdict and substring check are unchanged and the probe passes. But the line is no longer byte-identical to the Phase 5 line. Edit normalises trailing whitespace, so I could not restore it, and no Bash write shape exists. Fix under MANUAL ACTION REQUIRED.
- Mode allow without a settings.json allow rule. After activation the guard returns no decision, and settings.json has no `Bash(sbt erDecisionGroupingDev)` allow rule. Claude Code's normal permission flow then decides: in default mode the human is asked on each call, unlike `goldSetEvalDev`, which has a settings allow rule. Running without a prompt would need a separate settings.json amendment, which I did not prepare (out of this work order's scope).
- The ask alternative. With MODE `ask`, the guard itself returns permissionDecision ask with the reason "allowlist row 101 (sbt erDecisionGroupingDev): <justification>". A human prompt is then forced on every call, even if a settings allow rule is added later and in any permission mode. That costs one approval per run.
- The reviewer (CR-P6-09 check 6) found allow acceptable: it gives no capability beyond what `sbt check` already runs, and the writes are bounded. The human chooses.
- Write auditing. sbt writes the task's outputs under gitignored target/ and modules/*/target, which audit-bash-writes.sh does not see. This is the same accepted residual as goldSetEvalDev and localPipelineDev.
- Time budget. Compile plus 45 s plus 45 s plus sbt start-up must fit in the roughly 180 s agent tool limit. The 45 s values come from BE-P6-01, not from me.
- Deferred hardening. `sbt erDecisionGroupingHeldout` and similar names are refused only by default deny (no-allowlist-entry), not by a held-out rule. The optional deny on any sbt token containing "heldout" was deferred by the human and not added; a probe records the current behaviour.
- Each new task name (e.g. a future erDecisionGroupingHeldout) would need its own allowlist amendment. Default deny holds until then.

MANUAL ACTION REQUIRED:
1. Decide on the accidental TAB change. Either accept it (the verdict is unchanged), or restore the byte-exact Phase 5 line in your own Terminal before committing, for example:
   `perl -0pi -e 's/(docs\/localPipelineHeldout\.md"\}\})\n(# FE-P6-01)/$1\t\n$2/' .claude/hooks/probes.tsv`
   then run `.claude/hooks/validate-agent-files.sh --probes`. Afterwards, `git diff` should show no change on that line.
2. Choose the mode: allow (as prepared) or ask.
3. Ratify or reject the change, commit it yourself, and record the hash.
4. For activation: give a later work order for the activation edit, then start a fresh session.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: The human reviews this diff, chooses the mode and ratifies. In a later work order, factory-engineer makes the activation edit below. The human reviews and commits it and starts a fresh Claude Code session. The first `--probes` run then observes the activated verdicts, and only after that does entity-resolution-engineer run `sbt erDecisionGroupingDev`.

Activation edit (not made; for a later work order after ratification). The row count stays 120. The required changes are:
- (a) /Users/taufeeqpatel/Desktop/ScholarChain/.claude/hooks/bash-allowlist.tsv line 101, field 2 (the one functional change): `inactive:FE-P6-01-ratification-pending` -> `active`.
- (b) /Users/taufeeqpatel/Desktop/ScholarChain/docs/agents.md mirror row: the same field. The validator requires the mirror to match.
- (c) docs/agents.md, the "source sha256" value: replace 6d1a92c6... with the new sha256 of the TSV, which the validator prints.
- (d) probes.tsv, owner probe: `BLOCK ... [FE-P6-01 T] entity-resolution-engineer sbt erDecisionGroupingDev ... entry-inactive` -> `ALLOW ...` with an empty 5th field. Its label can become "(active, allow)".
- (e) probes.tsv, the other three [FE-P6-01 T] rows (`... check`, the task name twice, `-Dscholarchain.x=y`): substring `entry-inactive` -> `no-allowlist-entry`. They stay BLOCK.

How the probes handle the transition:
- The guard reports `entry-inactive` only when no active row matches and an inactive row's two leading literal tokens (`sbt erDecisionGroupingDev`) equal the command's first two tokens. So only the 4 [FE-P6-01 T] rows depend on the row's state.
- After activation, the exact one-row spec matches only the bare two-token command, giving ALLOW. Commands with extra trailing tokens fall through to `no-allowlist-entry`, because the dormant check only considers inactive rows.
- Every other new probe gives the same verdict and rule in both states: other identities have no such row; the other shapes do not share both leading tokens; the held-out and gate rules fire before allowlist matching. So the committed inactive state passes now, and the activated state passes once (a) to (e) are applied together.
- If you prefer a smaller activation, the three trailing-token probes could instead carry an empty substring (BLOCK only) and never change. That makes them weaker. Not done; your call.

MATRIX BASIS:
- Ratified entries relied on: the entity-resolution-engineer matrix row (owns modules/entity-resolution/**; gate: gold-set evaluation and match-policy review); its Agent catalog row (Bash; global floor); and its lifecycle row (dev gold-set diagnostics).
- The Bash allowlist is the Bash column of the authority matrix. This new row is the proposed matrix amendment (FE-P6-01). It is not ratified, and it is INACTIVE so it has no effect before ratification and restart.
- Authority: the human direction quoted in the work order, and CR-P6-09 check 6 (the exact shape, for entity-resolution-engineer only, mode allow acceptable, plus the listed probes), with MAJ-1 closed by CR-P6-11 APPROVE at commit 7b72681.
- The deferred "heldout" token hardening is not proposed.

PRIVILEGE DELTA:
- Now (committed, inactive): no identity gains anything. For entity-resolution-engineer only, the refusal message for `sbt erDecisionGroupingDev` and the 3 trailing-token forms changes from `no-allowlist-entry` to `entry-inactive`. Every other identity: unchanged.
- After activation: entity-resolution-engineer gains exactly one Bash shape, the two tokens `sbt erDecisionGroupingDev`, with no arguments or extra tokens (widened). Every other identity, including ORCHESTRATOR, is unchanged and gets no-allowlist-entry, or reviewer-read-only for code-reviewer.
- Unchanged for everyone: tool lists, artifact ownership and autonomy. No settings.json, MCP or hook-script change.

FLOOR INVARIANTS: all intact. No hook script, settings.json or .mcp.json changed. Held-out rules still fire before allowlist matching, shown by 4 new probes. The other floor rules are unchanged: dangerous-command guards, test-stop hooks, the stale-marker refusal, formatter, Scala auto-format, secret-read denies, terraform apply/destroy deny, destructive AWS and EMR guards, force-push deny and constitution protection. Error messages stay actionable: entry-inactive names the status and row and says "Report BLOCKED-ON human".

AUDIT RESULTS:
- Collision: PASS. One new spec, unique within entity-resolution-engineer and named by no other identity. No command or agent name added. No hook registration changed; the validator reports "no duplicate hook registrations".
- Orphan: PASS. The new row is mirrored in docs/agents.md (validator B1 PASS) and referenced by 35 probes. The validator reports every registered hook present and executable and no orphan hook.
- One-writer: PASS. No ownership row changed. The validator reports "every path pattern has exactly one owner row". The task's outputs under target/ are written by sbt as an opaque tool, as for goldSetEvalDev; target/** is not writable by Write/Edit.

COMPLETE DIFF: as reported by `git diff` in the working tree at the time of this file (bash-allowlist.tsv +1 row at line 101; probes.tsv: the [D21] control line loses its trailing TAB, then 7 comment lines and 35 probe rows appended; docs/agents.md: row count 119 -> 120, source sha256 eb708cd4... -> 6d1a92c6694f12411486947654839d310746f7c1a7f2bfe7332824fa7468de44, and the mirror row). The agent's verbatim diff is reproduced in the orchestrator's message to the human; the authoritative copy is `git diff -- .claude/hooks/bash-allowlist.tsv .claude/hooks/probes.tsv docs/agents.md` before the human commit.

OUT-OF-SCOPE ITEMS:
- A settings.json allow rule `Bash(sbt erDecisionGroupingDev)`, which would let it run without a prompt like goldSetEvalDev. Not prepared, because the work order limits changes to the three files. It would need its own amendment from factory-engineer if you want it.
- The optional deny on any sbt token containing "heldout" (deferred by you). Not added.
- Running `sbt erDecisionGroupingDev`: entity-resolution-engineer, only after activation and restart.

RATIFICATION REQUIRED: Yes. This is a constitutional amendment (FE-P6-01) that a human must review and ratify. It takes no effect before ratification, and the row stays INACTIVE until a later activation work order is applied, ratified and followed by a fresh Claude Code session. factory-engineer has not staged, committed or ratified anything.
