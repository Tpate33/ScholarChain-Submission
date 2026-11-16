# Phase 5 human decisions after RV-P5-08 / RV-P5-09 (transcribed by the orchestrator)

Received 2026-11-12. This is a verbatim transcription, not a human-gate record.

```text
1. Proceed toward the reviewed October DBLP download. Defer the three
   new acquisition nits. Show me the exact dataset files to stage for
   the script-provenance checkpoint and the preflight commands. I'll
   review and commit those files myself, then run the download locally.
   Keep the entity-resolution work out of that checkpoint.

2. Under Option A, a shared DBLP key or Scopus EID must not automatically
   accept a pair whose publication stages differ or are uncertain, even
   when there is no shared preprint identifier. Preserve authoritative
   conflict handling and cover both record-key paths with regressions.

3. Fix arXiv v1/v2 through m7. Compare the normalized base submission ID
   consistently for agreement and disagreement, while preserving the
   original IDs in provenance. Agreement still requires the same-stage
   check. Test both different revisions of one submission and distinct
   submissions.

Batch the policy repairs for the next full check and focused review.
Keep the gold-label amendment pending separate ratification.
```

## Routing

| Item | Work order / output |
|---|---|
| 1. Download checkpoint file list and preflight commands | `download-checkpoint-01.md` (orchestrator, from `git status` / `git ls-files` / `git diff --stat`); the human reviews, commits and runs the download; RV-P5-09 NIT-A, NIT-C, NIT-E deferred |
| 2. Record-key (dblp-key, scopus-eid) acceptance gated on an established same stage for work pairs, with no shared preprint identifier required; T1 conflicts preserved; regressions for both keys | ER-P5-12 (entity-resolution-engineer) |
| 3. m7: arXiv base-ID comparison for agreement and disagreement; originals preserved in provenance; agreement still stage-gated; tests for revisions of one submission and for distinct submissions | ER-P5-12 (same order; one policy version) |
| Version assertions in tests/** | test-engineer after ER-P5-12 |
| Verification | one human-run foreground `sbt check`, then a focused code-reviewer review |
| FE-P5-01 (gold-label path amendment) | pending separate ratification; not touched |
