# Phase 5 human decision on the RV-P5-06 record-key decision point (transcribed by the orchestrator)

Received 2026-11-11 in response to RV-P5-06 (review-report.md). This is a verbatim transcription, not a human-gate record.

```text
Choose "Block it." When the preprint DOI rule leaves a pair unresolved
because publication stages differ or are uncertain, a shared DBLP key
or Scopus EID must not override that result. Keep this scoped to that
case and preserve authoritative conflict handling.

Record the decision with a reason code that explains why the pair
stayed unresolved. Add regressions for both record-key paths.

Before scheduling another full check, group the remaining findings by
owner and identify which affect the required gold-set evaluation or
dataset acquisition. Include the threshold-sensitivity issue, where
stage-blocked pairs are counted as eligible for score-based acceptance.
```

## Status after this instruction

| Item | Decision / routing |
|---|---|
| RV-P5-06 decision point | Block it: when the preprint-DOI rule leaves a pair UNRESOLVED (stage cross-stage or uncertain), a shared dblp-key or scopus-eid must not override it. Scope limited to that case; T1 conflict handling unchanged. A reason code records why the pair stayed unresolved. Regressions for the dblp-key and the scopus-eid paths. Routed to entity-resolution-engineer (ER-P5-10), without a full `sbt check`. |
| Remaining findings | grouped by owner, with gold-set-evaluation and dataset-acquisition impact, in `open-findings-01.md`, before any further full check |
| Unchanged | no commits, pushes, deployments, AWS or paid model calls; hooks, settings and test marker untouched; download and FE-P5-01 ratification pending |
