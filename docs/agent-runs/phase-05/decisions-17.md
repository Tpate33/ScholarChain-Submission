# Phase 5 decisions 17 (human confirmation of D15(c) details; orchestrator transcription)

Recorded by the orchestrator, 2026-11-14. Not a human-gate record and not a ratification. The combined amendment stays in draft; application waits for ratification; any held-out run waits for separate approval.

## Human text (verbatim)

```text
Confirm D15(c): I will run the held-out evaluation myself in a separate Terminal. Send the evaluation output directly to that Terminal and include R6 with the matching Claude Code settings entries.

Keep the held-out pipeline under `ask`. In the build work order, include a check using synthetic output to verify that held-out evaluation results do not appear in agent-readable sbt logs.

Show me the final combined FE-P5-03 + FE-P5-04 diff for ratification. Keep it in draft. Wait for my ratification before applying it, and wait for separate approval before any held-out run.
```

## Effect on the amendment (amendment-fe-p5-03.md as revised by amendment-fe-p5-04.md)

| Item | Decision |
|---|---|
| D15 | (c) confirmed: the human runs held-out evaluation in a separate Terminal (FE-P5-04 consequences 1-6) |
| Output routing | held-out evaluation child output goes directly to the terminal, not through the sbt log (build-engineer, non-constitutional) |
| R6 | adopted, with the optional S2 extension: `Read(./target/streams/**/goldSetEvalHeldout/**)` and `Read(./target/local-runs/heldout/**)` |
| Held-out pipeline | `sbt localPipelineHeldout` stays hadoop-engineer, mode ask (S3 ask) |
| Expected counts | 120 allowlist rows, 95 identity rows, 657 table probes (R6 included, R4 excluded) |

## Held build work-order item (dispatch only after ratification, per amendment section 8)

To be added to the BE-P5-02 apply work order: a check that uses synthetic evaluation output (no real labels or gold data) to verify that held-out evaluation results do not appear in agent-readable sbt logs (for example `target/streams/**/goldSetEvalHeldout/**` and any other sbt-persisted task log), while the output still reaches the terminal.

## Routing

- FE-P5-05 (factory-engineer): final combined FE-P5-03 + FE-P5-04 diff, draft only.
