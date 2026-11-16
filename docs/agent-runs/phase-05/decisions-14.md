# Phase 5 human decisions after DS-P5-10 (transcribed by the orchestrator)

Received 2026-11-13. This is a verbatim transcription, not a human-gate record.

```text
I committed the verified samples and manifest as 36ffcf9.

Have hadoop-engineer and entity-resolution-engineer prepare the exact
local commands for separate dev and held-out pipeline runs. Show each
run's inputs, output directory, and how it reports the number of
distinct candidate pairs. Keep the two splits separate.

Have build-engineer and factory-engineer prepare the localPipeline and
goldSetEval alias changes and the corresponding FE-P5-01 amendment
for my review. Hold activation and execution until I review the
commands and ratify the amendment.
```

## Routing (preparation only; nothing is written, activated or run)

| Item | Work order | Output |
|---|---|---|
| Hadoop per-split local pipeline commands (dev, held-out), inputs, output dirs, distinct-pair count reporting | HD-P5-01 (hadoop-engineer), report only | agent report |
| Review-queue / membership / blind-view commands per split from those outputs, and how pair counts are reported | ER-P5-14 (entity-resolution-engineer), report only | agent report |
| `localPipeline` and `goldSetEval` sbt alias changes | BE-P5-01 (build-engineer), proposed diff in the report, no file written | saved by the orchestrator as a draft |
| FE-P5-01 amendment revision (allowlist rows for both aliases; `gold/**` ownership) | FE-P5-02 (factory-engineer), proposed diff in the report, no file written | saved by the orchestrator as a draft |
| Activation (alias in build, allowlist rows, ownership rows) and execution of any run | held until human review and ratification, then a fresh session | — |
