# Phase 06 work orders: post-restart probes (FE-P6-03) and the pinned dev run (ER-P6-09) (verbatim)

Received by the orchestrator on 2026-11-16, after the human ratified and committed FE-P6-01/FE-P6-02. The decisions in this text are the human's own words. The human records them in `human-gate.md`.

```text
The Phase 6 allowlist change is ratified and committed. Read HEAD.
Have factory-engineer run the required post-restart probes. If they pass,
delegate the exact standalone `sbt erDecisionGroupingDev` command once to
entity-resolution-engineer.

Report the observed result, decision and grouping run IDs, verified output
locations, and evidence for the Spark handoff. If a prerequisite fails,
report it before retrying. Use dev inputs only and keep held-out labels and
evaluation output off limits. Start no core changes before the pinned run
is captured. Return the result and the next DE-P6-02 work order.
```

## Orchestrator check of HEAD (2026-11-16)

- `git rev-parse HEAD`: `6be1f1db6eb22864ac20b051040412d8ddb4e0e1` ("Phase 6: ratify bounded dev entity-resolution command"; parent 7b72681 = Y).
- `git show --name-only`: .claude/hooks/bash-allowlist.tsv, .claude/hooks/probes.tsv, docs/agents.md, and five phase-06 evidence files. Nothing under build.sbt, project, modules/core or modules/entity-resolution, so the Y pin (codeVersion 8c1bee0) still satisfies the code-scope check.
- `git status --porcelain`: empty before this file was written.
- Session restart: the human states the change is ratified and committed; the orchestrator cannot observe the restart itself.

## FE-P6-03 result (factory-engineer, 2026-11-16), summary of the verbatim report

- STATUS: COMPLETE. Overall verdict: PASS. Files created/modified: none. No sbt command run.
- `git rev-parse HEAD` = 6be1f1db6eb22864ac20b051040412d8ddb4e0e1; `git status --porcelain` = only `?? docs/agent-runs/phase-06/prompt-er-p6-09-dev-run.md`; `git diff --stat HEAD` = empty.
- `validate-agent-files.sh`: `PASS  bash-allowlist.tsv parses (95 identity rows, 3 groups)`; `PASS  docs/agents.md Bash allowlist block matches bash-allowlist.tsv (120 rows, sha256 19fa3b7fd04a)`; `RESULT: PASS (0 failures)`.
- `validate-agent-files.sh --probes`: `NOTE  696 table probes run`; `NOTE  38 fixture probes run`; `RESULT: PASS (0 failures)`.
  - `PASS  probe [FE-P6-01 T] entity-resolution-engineer sbt erDecisionGroupingDev (active, allow; was BLOCK entry-inactive) -> ALLOW`
  - The three trailing-token [FE-P6-01 T] probes -> BLOCK; the nine refused-shape [FE-P6-01] probes -> BLOCK; the four held-out-first probes -> BLOCK; all 18 other identities including ORCHESTRATOR -> BLOCK.
  - `PASS  probe D10(ii) service-engineer sbt clean check, held-out output absent -> ALLOW`; `PASS  probe D10(ii) service-engineer sbt clean check, held-out output present -> BLOCK`.
- Reported risks: shellcheck not installed (bash -n only); the mirror match is shown as a 12-character sha256 prefix.
