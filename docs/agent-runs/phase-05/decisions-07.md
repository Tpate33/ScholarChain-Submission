# Phase 5 human decision on RV-P5-05 finding 1 (transcribed by the orchestrator)

Received 2026-11-11 in response to RV-P5-05 (review-report.md). This is a verbatim transcription, not a human-gate record.

```text
Choose Option 1. When DOI-based acceptance relies solely on shared
preprint DOIs, require evidence that both records are preprints. If
their stages differ or are uncertain, prevent automatic acceptance
through that DOI or a fallback path. Preserve authoritative conflict
handling and the existing rules for shared publication DOIs.

Add regression coverage for a published record that lists both its own
DOI and the preprint DOI, and for two preprint records that share a DOI.
Then run one foreground sbt check and request focused re-review.
```

## Status after this instruction

| Item | Decision / routing |
|---|---|
| RV-P5-05 finding 1 (MAJOR) | Option 1: a DOI agreement whose shared DOIs are all preprint DOIs decides only when both records are established preprints. Cross-stage or uncertain → no automatic acceptance via that DOI or any fallback (inferred, score, LLM). Authoritative (T1) conflict handling and the rules for shared publication DOIs unchanged. Routed to entity-resolution-engineer (ER-P5-09). |
| Regression coverage | (a) published record listing its own DOI and the preprint DOI vs the preprint; (b) two preprint records sharing a preprint DOI |
| Verification | one foreground `sbt check`, then focused code-reviewer re-review (RV-P5-06) |
| RV-P5-05 findings 2-5, earlier open items, gates | not covered by this instruction; remain open |
| Unchanged | no commits, pushes, deployments, AWS or paid model calls; hooks, settings and test marker untouched; download and FE-P5-01 ratification pending |
