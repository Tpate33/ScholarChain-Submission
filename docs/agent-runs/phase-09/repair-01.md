# Phase 09 repair 01 (orchestrator, 2026-11-21)

Source: CR-P9-01 REQUEST_CHANGES (review-report.md).

## Human decisions (recorded by the orchestrator; the human's own record belongs in human-gate.md)
- Marker reviewed: 181 suites, 2199 passed, 1 skipped, git_head=a5cbdab, expected Phase 9 paths. Audit recovery: first `git status --porcelain` refused as UNATTRIBUTED (.claude/.last-test-run), baseline reset; re-run succeeded with only Phase 9 paths.
- One bounded repair covering M1-M4 and m1-m6.
- m5: persist structured QueryPlan operations, metrics, window, filters, ranking rule; resolved researcher IDs where available; no raw question text or names in the audit; unresolved arguments explicitly recorded.
- Deferred, limitations documented, not claimed fixed: m7 (unresolved same-form mentions not recorded in entity links), n1 (schema bounds duplicated as literals; chars vs bytes), n2 (entityLinks opt-in default None), n3 (injected titles shown verbatim, quoted and labelled), n4 (labelled PARTIAL/CONFLICTING claims keep original text).
- Then test-engineer completes its changes and runs one final sbt check; fresh review CR-P9-02. Builds sequential.

## RE-P9-04 result (rag-engineer)
- STATUS: COMPLETE. BLOCKED-ON: none. m7, n1-n4 untouched.
- FILES CREATED: prompts/runtime/answer-synthesis.v2.txt; prompts/schemas/answer-synthesis-output.v2.schema.json, audit-record.v2.schema.json; modules/rag/src/test/scala/scholarchain/rag/agents/RepairP9Suite.scala.
- FILES MODIFIED: rag agents/{PathFinder, EvidenceBuilder, BundleFacts, ClaimVerification, AnswerSynthesis, ResearcherResolver, Audit, Workflow}.scala; benchmark/Benchmark.scala; tests PathFinderSuite.scala, ResearcherResolverSuite.scala.
- COMMANDS: sbt rag/test x4 (final "Passed: Total 122, Failed 0, Errors 0, Passed 122"). No sbt check (human instruction).
- Per finding: M1 PathOutcome.FoundNotProvenShortest + PathTraversalEvidence.searchTruncated (rag-path-traversal.v2); shortest-path facts only for untruncated searches. M2 closed wording scholarchain.rag.claim-wording.v1; unlicensed words make a claim PARTIALLY_SUPPORTED (verifier claim-verification.v2). M3 answer missing-evidence = workflow gaps only; model lines counted (droppedMissingEvidence) with a fixed notice. M4 TooManyCandidates returns maxCandidates candidates + truncated marker. m1 synthesizeAccounted: real attempts, versions, tokens, model kind. m2 word-boundary number regex. m3 bundleId only when written. m4 audit row is the commit point; session failure after it reported, answer returned. m5 audit-record.v2 with structured plan (resolved IDs, unresolved/not-run entries, filter key + SHA-256, no names, no question text). m6 CheckResult.applicable, FamilySummary.checksNotApplicable.
- Benchmark rag-fixture-world.v1: graph 11/11 cases, 87/87 checks, 43 not applicable; semantic 2/2 cases, 3/3 checks, 23 not applicable.
- Versions: prompt answer-synthesis.v2; output schema v2; audit-record.v2 (stored row still storage query-audit.v1); planner v1 unchanged.
- Risks: tests/** must follow public type changes; strict closed wording may label legitimate prose; filter hashes guessable; uncommitted v1 prompt/schema files remain unreferenced (deletion not authorized; human decides before commit).

## Routing
- RE-P9-04 (rag-engineer): M1, M2, M3, M4, m1, m2, m3, m4, m5, m6 (runner) with focused regression tests; focused `sbt rag/test` only.
- TE-P9-02 (test-engineer): m6 report wording, any tests/** updates the repair requires, one final `sbt check`.
- CR-P9-02 (code-reviewer): fresh review.
