# Phase 06 work orders DE-P6-01 repair 02 and ER-P6-04 (verbatim)

Received by the orchestrator on 2026-11-15, after CR-P6-04 (APPROVE with m1, n1-n3). The acceptances and decisions in this text are the human's own words. The human records them in `human-gate.md`.

```text
Accept the reviewed DE-P6-01 repair as the Phase 6 contract baseline. Run these two work orders in order, one owner at a time.

1. Have domain-engineer fix CR-P6-04 m1. Reject invalid or oversized paths before building the directory-overlap index, preserve overlap detection, and describe its actual complexity. Fix the stale digest comment and the missing work ID validation if they stay small. Add focused regression coverage and have code-reviewer review only the new diff.

2. Once the core fix passes its checks and review, delegate ER-P6-04 to entity-resolution-engineer. Write the integrity manifest before the completion marker and verify the exact required files, counts, and checksums before returning a published run. Include the F4 follow-up so grouping-only settings do not change the decision run ID. Cover truncation and digest stability in the tests. Report any compatibility impact on existing local exports and the supported regeneration path.

Keep run-folder locations out of the grouping digest. The future grouping manifest must record its verified input run IDs, including the decision and curated runs. Accept the stricter singleton validation without a schema bump for this unreleased format. Keep unresolved records in identifier collision checks, but leave the remaining grouping rules pending approval. Carry forward the requirement to validate expected author lists against every curated work.

Run each owner's required checks once per completed change. Preserve Phase 5 acceptance, policy, and evidence. Use dev or committed samples only and keep held-out labels and evaluation output off limits. Return the test results and review verdicts, then stop before ER-P6-05.
```

## Carried forward to ER-P6-05 (not yet approved for implementation)

- The grouping run manifest records its verified input run IDs, including the decision run and the curated run.
- Expected author lists are validated against every curated work, not only representatives (CR-P6-04 n3).
- Run-folder locations stay out of the grouping digest.
- Unresolved records count in identifier collision checks (draft O6, this item only).
- The remaining grouping rules (O1-O5, O7-O11) are pending approval.
