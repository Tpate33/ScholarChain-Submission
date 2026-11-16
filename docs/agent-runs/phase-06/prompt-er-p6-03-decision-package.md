# Phase 06 work order: H1-H8 decision package (verbatim)

Received by the orchestrator on 2026-11-15, after CR-P6-02 returned APPROVE. The human acceptance statement in this text is the human's own words. The human records it in `human-gate.md`; the orchestrator does not write that file.

```text
I accept ER-P6-02 for local use within Phase 6, with n2 and n3 documented as limitations. Carry forward the four non-blocking findings. Require a verified row count or checksum before Spark consumes copied decision runs. Phase 6 remains open. Keep the existing report and sbt check evidence without rerunning tests or review.

Next, use the existing ER-P6-01 assessment to draft one recommended decision package for H1–H8. Group related choices and flag the ones that actually require my approval. Include deterministic conflict handling and ID selection, the treatment of unresolved mentions and singleton researchers, and how work duplicates are counted once. State what D3(c) requires and what quality claims Phase 5 evidence supports. Separate what is needed for local development and the bounded EMR run from full-scale follow-up work.

Keep Phase 5 accepted. Use only dev or committed sample inputs. Do not access held-out labels or evaluation output. Return the decision package and the first implementation work order for my review before starting grouping or changing any shared contracts.
```

## Carried forward (from the human's acceptance)

- CR-P6-02 nits 1-4 (entity-resolution-engineer), open and non-blocking.
- New requirement: a verified row count or checksum for each decision part file before Spark consumes a copied decision run (relates to CR-P6-02 nit 2).
- n2 and n3 are accepted as documented limitations for local use within Phase 6.
