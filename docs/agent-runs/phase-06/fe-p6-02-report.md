# Phase 06 FE-P6-02: factory-engineer report (2026-11-16), verbatim

- Work order (verbatim): docs/agent-runs/phase-06/prompt-fe-p6-02-activate.md
- Active form (mode allow) of the FE-P6-01 row, prepared for human ratification; not ratified, not committed.
- Harness note on hand-back: the output matched an instruction-shaped pattern (settings-json). The report only mentions that no `settings.json` rule names the command; no settings change was made or requested.
- The agent's "COMPLETE DIFF" block is not repeated here; the authoritative bytes are `git diff -- .claude/hooks/bash-allowlist.tsv .claude/hooks/probes.tsv docs/agents.md` in the working tree at ratification. The agent noted one wrong context line in its pasted copy (the docs/agents.md line after the new mirror row reads `@GIT` at HEAD); the orchestrator's own `git diff` confirms the HEAD context line is `| \`spark-engineer\` | active | allow | - | \`@GIT\` | matrix row |`.

## Orchestrator observations after the report (2026-11-16)

- `.claude/hooks/validate-agent-files.sh`: `RESULT: PASS (0 failures)`; `PASS  bash-allowlist.tsv parses (95 identity rows, 3 groups)`; `PASS  docs/agents.md Bash allowlist block matches bash-allowlist.tsv (120 rows, sha256 19fa3b7fd04a)`.
- `git diff --stat`: bash-allowlist.tsv 1 +; probes.tsv 50; docs/agent-runs/phase-06/review-report.md 89 +; docs/agents.md 3; 4 files changed, 141 insertions(+), 2 deletions(-).
- `git diff` of bash-allowlist.tsv and docs/agents.md: row 101 status `active`, mode `allow`; mirror row identical; sha256 `19fa3b7fd04a8d332fe4a3b47a8c830ba0c3925ea9293fc0d30b00327a6ac9f9`; 120 rows.
- `git status --porcelain`: the four modified files above plus untracked fe-p6-01-report.md, prompt-fe-p6-01-allowlist.md, prompt-fe-p6-02-activate.md (and this file once written).

## Agent report, verbatim (diff block omitted as noted above)

STATUS: COMPLETE
OWNER: factory-engineer
WORK ORDER: FE-P6-02 (Phase 6). Prepare the ACTIVE form (mode allow) of the reviewed FE-P6-01 allowlist row: `sbt erDecisionGroupingDev`, for entity-resolution-engineer only. This applies activation plan (a)-(e) from docs/agent-runs/phase-06/fe-p6-01-report.md. I prepared it for human ratification. I did not stage, commit or ratify anything.

FILES CREATED: none

FILES MODIFIED (all relative to the FE-P6-01 working tree; base HEAD 7b72681f827b3cf19851aa7aef3c4d7603c5d15a):
- /Users/taufeeqpatel/Desktop/ScholarChain/.claude/hooks/bash-allowlist.tsv
  - (a) Row 101, field 2: `inactive:FE-P6-01-ratification-pending` -> `active`.
  - Mode `allow`, identity, spec, provenance `-` and justification are unchanged.
- /Users/taufeeqpatel/Desktop/ScholarChain/docs/agents.md
  - (b) Mirror row, the same field.
  - (c) Source sha256 `6d1a92c6...` -> `19fa3b7fd04a8d332fe4a3b47a8c830ba0c3925ea9293fc0d30b00327a6ac9f9`. I copied this value from the validator's message. The row count stays 120.
- /Users/taufeeqpatel/Desktop/ScholarChain/.claude/hooks/probes.tsv
  - (d) Owner probe changed from BLOCK entry-inactive to ALLOW with an empty 5th field. New label: "(active, allow; was BLOCK entry-inactive)".
  - (e) The three trailing-token [FE-P6-01 T] probes stay BLOCK. Their substring changed from `entry-inactive` to `no-allowlist-entry`. New labels: "(active: extra token, no-allowlist-entry; was entry-inactive)".
  - The transition comment block was rewritten to describe the active state. It went from 7 to 10 lines. The section header now says "(state-dependent; active state)".
  - Line 683 (the accepted trailing-TAB difference) was not touched. No probes were added.

COMMANDS RUN:
- `git diff --stat`, `git diff -- <three files>`, `git status --porcelain`
- `.claude/hooks/validate-agent-files.sh` -> RESULT: PASS (0 failures)
- `.claude/hooks/validate-agent-files.sh --probes` -> RESULT: PASS (0 failures)
- `python3 -I -m json.tool .claude/settings.json` -> parsed
- `python3 -I -m json.tool .mcp.json` -> parsed
- `test -x .claude/hooks/guard-dangerous.sh` -> exit 0
- Refused by the guard, so nothing ran: a `grep` and a `wc -l` on the saved probe output. These are not my shapes, so I read that file with Read instead.
- Not run: any sbt command, `sbt erDecisionGroupingDev`, staging or commits.

TEST RESULTS: not applicable. No source changed and no sbt command was run, as the work order requires.

VALIDATION RESULTS:
- Plain validator: RESULT: PASS (0 failures). Exact lines:
  - "PASS  bash-allowlist.tsv parses (95 identity rows, 3 groups)"
  - "PASS  docs/agents.md Bash allowlist block matches bash-allowlist.tsv (120 rows, sha256 19fa3b7fd04a)"
  - "PASS  every path pattern has exactly one owner row"
  - "PASS  no duplicate hook registrations"
  - "PASS  no lifecycle step is claimed twice"
  - "PASS  agent names unique"
- --probes run: RESULT: PASS (0 failures), with "NOTE  696 table probes run" and "NOTE  38 fixture probes run". These are the first observed active-state verdicts:
  - "PASS  probe [FE-P6-01 T] entity-resolution-engineer sbt erDecisionGroupingDev (active, allow; was BLOCK entry-inactive) -> ALLOW"
  - "PASS  probe [FE-P6-01 T] entity-resolution-engineer sbt erDecisionGroupingDev check (active: extra token, no-allowlist-entry; was entry-inactive) -> BLOCK"
  - "PASS  probe [FE-P6-01 T] entity-resolution-engineer sbt erDecisionGroupingDev twice (active: extra token, no-allowlist-entry; was entry-inactive) -> BLOCK"
  - "PASS  probe [FE-P6-01 T] entity-resolution-engineer trailing -D property (active: extra token, no-allowlist-entry; was entry-inactive) -> BLOCK"
  - All other [FE-P6-01] probes still PASS: the 17 other identities, ORCHESTRATOR included, the held-out rules first, the shell gate, and the other refused shapes.
  - "PASS  probe D10(ii) service-engineer sbt clean check, held-out output present -> BLOCK"
- JSON: both files parse.
- Shell syntax: `bash -n` PASS for all 9 hook scripts. "NOTE  shellcheck not installed; skipped (bash -n still enforced)".
- Executable bits: "referenced hook exists and is executable" PASS for all 6 registered hooks.
- Frontmatter: name, description and explicit tools PASS for all 18 agent files.
- One intermediate failure, already fixed:
  - My first Edit of row 101 ended its new_string with a TAB. The tool stripped it, which fused field 5 and field 6 ("sbt erDecisionGroupingDevPhase 6 ..."). The PostToolUse validator reported "FAIL  allowlist bash-allowlist.tsv: line 101: malformed row".
  - The guard then failed closed for every Bash call ("BLOCKED [allowlist-unreadable]: ... line 101: malformed row, so nothing can be allowed").
  - I restored the TAB with a second Edit. The validator then reported only the expected sha256 mismatch: "embedded source sha256 ['6d1a92c6...'] does not equal bash-allowlist.tsv sha256 19fa3b7fd04a8d332fe4a3b47a8c830ba0c3925ea9293fc0d30b00327a6ac9f9". Fix (c) resolved it.
  - The final diff below shows row 101 with correct TAB separation, and the validator parses it.

ASSUMPTIONS:
- An ALLOW probe line with no 5th-field TAB is treated the same as an empty 5th field. Line 683 relies on this, CR-P6-12 check 4 confirms it, and the owner probe passes.
- The justification text did not need to cite FE-P6-02, and the validator does not require it. My optional suggestion: append "; activated FE-P6-02" when you next amend the row. I did not change it.

RISKS:
- There is no settings.json `Bash(sbt erDecisionGroupingDev)` allow rule, unlike `sbt goldSetEvalDev`. After activation the guard gives no decision, so in default permission mode the human is prompted on every call. This is unchanged from FE-P6-01, as CR-P6-12 check 5 noted.
- Carried-forward nits from CR-P6-12, deliberately not fixed:
  - The fe-p6-01-report.md comment-line count says 7; the true count was 10.
  - The row 101 justification "writes only target/local-runs/dev/" leaves out the compile outputs and target/streams/_global/erDecisionGroupingDev/.
  - probes.tsv:683 is missing its trailing TAB.
  - The optional probes `sbt -Dx=y erDecisionGroupingDev` and `SBT erDecisionGroupingDev` are still missing.
- Inherited residuals:
  - The task's gitignored target/ writes are not audited.
  - Its 45 s + 45 s run plus compile time must fit the agent tool limit (not observed).
  - `sbt erDecisionGroupingHeldout`-style names are refused only by default deny. The "heldout" token hardening was deferred.
- The fe-p6-01-report.md evidence describes the inactive state. It stays accurate as a historical record, and this report supersedes it for the active state.

MANUAL ACTION REQUIRED:
1. Review and ratify (or reject) the combined diff below.
2. Commit it yourself in a terminal. Recommended scope:
   - Constitutional files (required): .claude/hooks/bash-allowlist.tsv, .claude/hooks/probes.tsv, docs/agents.md.
   - Orchestrator evidence (your choice whether it goes in the same commit): docs/agent-runs/phase-06/review-report.md (modified), plus the untracked docs/agent-runs/phase-06/fe-p6-01-report.md, prompt-fe-p6-01-allowlist.md, prompt-fe-p6-02-activate.md, and this FE-P6-02 report once the orchestrator archives it.
   - Do not include any other path.
3. Record the hash, then start a fresh Claude Code session before anyone uses the new permission.
4. In that session, run `.claude/hooks/validate-agent-files.sh --probes` again before entity-resolution-engineer runs `sbt erDecisionGroupingDev`.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human ratification of the combined FE-P6-01 + FE-P6-02 diff, then a human commit, then a fresh session and a `--probes` replay. Only after that may the orchestrator route `sbt erDecisionGroupingDev` to entity-resolution-engineer.

SEPARATE-COMMIT QUESTION: no rule found. What I checked:
- CLAUDE.md: "One project phase uses one fresh orchestrator session and normally one commit."
- docs/agents.md, Authority boundaries 9: the same sentence. Authority boundaries 8: "Constitutional files require human ratification and a fresh session before they take effect."
- docs/agents.md, Human gates "Every phase": "... accept or reject, disclose manual edits, commit, and record the hash."
- docs/agents.md, Escalation rules (Constitutional change): requires a complete diff, justification, privilege delta, audits and validation output, but no commit split. The Known-gaps note says amendments are not effective "until the human ratifies it and restarts the session".
- docs/agent-runs/README.md: commit.txt cannot hold its own commit's hash, and reports are pasted verbatim (corrections go in repair-NN.md). Nothing about committing an inactive form first.
- My role file: "You never commit ..."; "No constitutional change is effective before human ratification and a session restart." Nothing about a split.
- The only source of "commit the inactive form, then activate in a later work order" is my own FE-P6-01 plan (NEXT GATE and MANUAL ACTION item 4 in fe-p6-01-report.md). That is a proposal, not a repository rule.
- One commit is consistent with "normally one commit". The inactive state's verdicts were observed in FE-P6-01 (CR-P6-12 APPROVE), and the active state's verdicts were observed here.

MATRIX BASIS:
- The ratified entity-resolution-engineer rows:
  - Matrix: owns modules/entity-resolution/**; gate is gold-set evaluation and match-policy review.
  - Agent catalog: Bash under the global floor.
  - Lifecycle: dev gold-set diagnostics.
- The Bash allowlist is the Bash column of the matrix. Row 101 is proposed amendment FE-P6-01, reviewed in CR-P6-09 check 6 and CR-P6-12 (APPROVE).
- Your direction quoted in FE-P6-02 picks mode allow, entity-resolution-engineer only. This activation is part of that same amendment and is not yet ratified.
- No other matrix entry is proposed.

PRIVILEGE DELTA (against HEAD 7b72681):
- entity-resolution-engineer: WIDENED by exactly one Bash shape, the two tokens `sbt erDecisionGroupingDev`, mode allow (the guard gives no decision). Extra tokens, project scoping, case variants and prefixes are still refused. Tools, ownership and autonomy are unchanged.
- Every other identity, ORCHESTRATOR included: unchanged. They get no-allowlist-entry, or reviewer-read-only for code-reviewer.
- settings.json, .mcp.json, agent files, hook scripts and ownership rows: unchanged.
- Nothing takes effect before ratification and a session restart.

FLOOR INVARIANTS: all intact. No hook script, settings.json, .mcp.json, agent file or ownership row changed.
- The held-out rules (`heldout-eval-human-only`, `heldout-pipeline-human-only`) still fire before allowlist matching. Observed probes: `sbt erDecisionGroupingDev goldSetEvalHeldout` / `localPipelineHeldout` -> BLOCK.
- The held-out run protection (D10(ii)) fixture probe passes. The settings.json held-out denies (Read of gold held-out labels, target/streams/**/goldSetEvalHeldout/**, target/local-runs/heldout/**, and `sbt *goldSetEvalHeldout*` / `*localPipelineHeldout*`) are as at HEAD.
- Also unchanged: the dangerous-command guards, test-stop and stale-marker hooks, formatter and Scala auto-format, secret-read denies, terraform apply/destroy deny, destructive AWS/EMR guards, force-push deny and constitution protection.
- Error messages stay actionable. During the transient malformed state the guard failed closed with an explicit message.

AUDIT RESULTS:
- Collision: PASS. The spec is unique and only entity-resolution-engineer has it. No agent, command or hook registration was added ("no duplicate hook registrations", "agent names unique", "no lifecycle step is claimed twice").
- Orphan: PASS.
  - Row 101 is mirrored in docs/agents.md (B1 PASS, 120 rows, sha256 19fa3b7fd04a).
  - It is referenced by the 35 [FE-P6-01] probes.
  - Every registered hook exists and is executable, and every hook on disk is referenced or sourced.
- One-writer: PASS ("every path pattern has exactly one owner row"). No ownership row changed.

OUT-OF-SCOPE ITEMS:
- A settings.json `Bash(sbt erDecisionGroupingDev)` allow rule to run without a prompt. Not prepared; it needs its own factory-engineer amendment if you want it.
- The optional probes and the CR-P6-12 nits, carried forward as you directed.
- The deferred "heldout" sbt-token hardening.
- Running `sbt erDecisionGroupingDev`: entity-resolution-engineer, only after ratification, commit and a fresh session.

RATIFICATION REQUIRED: Yes. This is a constitutional amendment (FE-P6-01, active form prepared under FE-P6-02). A human must review and ratify it, commit it, and restart the Claude Code session before it takes effect. The new permission must not be used before then. factory-engineer has not staged, committed or ratified anything.
