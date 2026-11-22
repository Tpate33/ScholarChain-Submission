# Phase 09 prompt (verbatim, as given to the orchestrator on 2026-11-21)

```text
Complete the minimum functional implementation required by this
Phase 9 prompt. Defer optional enhancements, cosmetic cleanup,
and unrelated refactoring. Do not silently omit requirements:
record anything blocked or deferred explicitly.

Phase 8 local scope is accepted and committed:
- Implementation: 5c97a9a
- Closeout record: a5cbdab
- Working tree clean.
- Final verification: 2080 passed, 1 skipped, 0 failed.

Read the Phase 8 acceptance, reports, and outstanding requirements
in docs/agent-runs/phase-08/.

Keep builds sequential. Include required testing, review,
human acceptance, and human commit steps.

No paid calls, AWS operations, automatic commits, deletions,
or hook changes without my explicit authorization.
```

The Phase 9 work order itself is README.md "Phase 9: implement the runtime collaboration-analysis agents" (L1484-1574 at a5cbdab).

## Orchestrator plan (records state; approves nothing)
- RE-P9-01 (rag-engineer): stages 1-5 (QueryPlanner, ResearcherResolver, CollaborationMetrics, PathFinder, CommunityAnalyst).
- RE-P9-02 (rag-engineer): stages 6-10 (SemanticContext, EvidenceBuilder, AnswerSynthesis, ClaimVerification, Audit), required controls, benchmark-query suite.
- test-engineer: adversarial and prompt-injection tests under tests/**; final `sbt check`.
- code-reviewer: read-only review, APPROVE required.
- Human: acceptance (human-gate.md) and commit (commit.txt).

Defaults forced by the human's constraints (recorded as deferred, not waived):
- No paid model calls: synthesis and any model-backed stage run behind a rag-owned model port; only a deterministic offline implementation, labelled non-production, exists locally. Real LLM outstanding.
- No AWS: graph, metric, vector and audit access through existing storage ports and in-memory fakes. Live Neptune, DynamoDB and S3 Vectors outstanding.
- No hook changes: the `sbt benchmarkQueries` allowlist row stays inactive (B10-alias-pending); the benchmark-query suite runs as tests inside `sbt check`. A dedicated alias is deferred.
- Builds sequential: one sbt command at a time, no background builds.
