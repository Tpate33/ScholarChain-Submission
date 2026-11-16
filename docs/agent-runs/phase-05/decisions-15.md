# Phase 5 human decisions after the decisions-14 review package (transcribed by the orchestrator)

Received 2026-11-13. This is a verbatim transcription, not a human-gate record.

```text
Proceed with the Crossref reader fix, with a regression test using the
committed sample format. Prepare the separate dev and held-out pipeline
entry points and manifests. Use distinct run IDs and output directories,
and report distinct candidate pairs and oversized-block drops by split.

Reconcile the build and factory drafts into one proposal:
1. Use explicit goldSetEvalDev and goldSetEvalHeldout commands. Require
   approval for the held-out pipeline and held-out evaluation. Enforce
   the expected split at runtime.
2. Use a human-owned config and a human-recorded manifest SHA-256.
   Pick one hash-file format and show how evaluation verifies it.
3. Use a bounded local pipeline with fixed inputs and output paths.
   Justify the timeout with dev measurements. Report partial runs
   clearly and use a fresh output directory for retries.
4. Fork runMain from the repository root. Route test log reduction
   separately if it would delay the pipeline work.

Derive the OpenAlex and Crossref snapshot tokens from committed
provenance and show the exact values. Justify the block cap of 4 on dev
and report what it drops. Fix the cap before the held-out run.

Show me which of the 15 core changes the pipeline requires and why
they must be committed first. Bring back one consolidated amendment
with the exact command rows, paths, and remaining D2–D11 and S1/S2
choices. Hold activation, commits, and held-out execution for review.
```

## Routing (orchestrator plan)

| Item | Work order | Writes |
|---|---|---|
| Which of the 15 uncommitted `modules/core` changes the pipeline needs, and why they must be committed first | DE-P5-03 (domain-engineer), report only | none |
| Crossref reader fix + regression test on the committed sample format; `ErSplitPipelineMain` (dev, held-out); per-split manifests with distinct run IDs and output dirs; snapshot tokens from committed provenance; distinct-pair and oversized-block-drop reporting by split; dev measurement command | HD-P5-02 (hadoop-engineer) | `modules/hadoop/**` only |
| Block cap of 4: justification from dev measurements, what it drops; cap fixed before held-out | ER-P5-15 (entity-resolution-engineer), after the dev measurement | report; `modules/entity-resolution/**` for `--expect-split` and the hash-file check |
| `--expect-split` runtime enforcement and the single hash-file format verified by evaluation | ER-P5-15 | `modules/entity-resolution/**` |
| Reconciled build proposal: `goldSetEvalDev`/`goldSetEvalHeldout`, bounded `localPipelineDev`/`localPipelineHeldout`, fresh output per retry, partial-run reporting, timeout from dev measurements, forked `runMain` from root | BE-P5-02 (build-engineer), proposed diff only | none (activation held) |
| Test log reduction | BE-P5-03 (build-engineer), separate, after the pipeline work | deferred |
| One consolidated amendment: exact rows, paths, D2–D11, S1/S2 | FE-P5-03 (factory-engineer), proposed diff only | none (ratification held) |
| Activation, commits, held-out execution | held for human review | — |
