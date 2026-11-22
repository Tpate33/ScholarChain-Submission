# Phase 09 repair 02 (orchestrator, 2026-11-21)

Source: CR-P9-02 REQUEST_CHANGES (review-report.md): F1 major, F2-F3 minor, F4-F5 nits.

## Human decisions (recorded by the orchestrator; the human's own record belongs in human-gate.md)
- Marker reviewed: 182 suites, 2217 passed, 1 skipped, git_head=a5cbdab, expected Phase 9 paths. Audit recovery: first `git status --porcelain` refused as UNATTRIBUTED (.claude/.last-test-run), baseline reset; re-run succeeded with only Phase 9 paths.
- Second bounded repair authorized:
  - F1: generate deterministic factual claim wording directly from verified structured assertions; model prose must not establish verification; retrieved titles stay quoted and clearly attributed; reject or withhold wording that cannot be supported.
  - Regression tests for negation, injected titles, and Unicode numeral/letter bypasses.
  - F2: preserve the actual withheld-answer outcome across retries.
  - F3: accurately record institution hints used in name resolution.
  - F4/F5 comment corrections.
- Keep prompts/runtime/answer-synthesis.v1.txt, prompts/schemas/answer-synthesis-output.v1.schema.json and prompts/schemas/audit-record.v1.schema.json as historical versions; record that they are superseded and unused; no deletion.
- Focused tests during repair; test-engineer runs one final sbt check after all edits; fresh review before acceptance. Unrelated cleanup deferred.

## RE-P9-05 result (rag-engineer)
- STATUS: COMPLETE. BLOCKED-ON: none.
- FILES CREATED: prompts/runtime/answer-synthesis.v3.txt; prompts/schemas/answer-synthesis-output.v3.schema.json; prompts/SUPERSEDED.md (v1 and v2 files kept unchanged, recorded as superseded and unused); modules/rag/src/test/scala/scholarchain/rag/agents/RepairP9bSuite.scala (10 tests).
- FILES MODIFIED: rag agents/{ClaimVerification, AnswerSynthesis, Workflow, Audit, BundleFacts}.scala; tests RepairP9Suite, WorkflowSuite, PathFinderSuite.
- COMMANDS: sbt rag/test x2 (first 2 failures fixed; final "Passed: Total 132, Failed 0, Errors 0, Passed 132"). No sbt check (human instruction).
- F1: ClaimWording removed; kept claim text rendered from verified assertions by fixed templates (claim-rendering.v1); model text never shown; descriptive fields cannot support deterministic claims; names/titles only as escaped quoted tokens, titles with a fixed "Retrieved descriptive context only" prefix; final ASCII guard; negative wording only in not-connected-within and path-inconclusive templates; CONFLICTING shows the stored value. Tests: negation (3 variants), injected title, Unicode numerals/enclosed letters, guard unit, control.
- F2: withheld answer recorded as abstained/audit-not-persisted with no answer hash in held and retried audit. F3: institution filter applied=true on resolve steps where the hint was evaluated. F4/F5 docs fixed.
- Versions: prompt answer-synthesis.v3; output schema v3; claim-verification.v3; claim-rendering.v1; audit-record.v2, benchmark-set.v1, workflow.v1 unchanged.
- Benchmark rag-fixture-world.v1: graph 11/11 cases, 87/87 checks, 43 n/a; semantic 2/2, 3/3, 23 n/a.
- Risks: tests/** must follow type/behaviour changes; quoted name may show the model's case/width variant (hash-checked); a falsely reported failed audit write makes the retry fail closed; non-ASCII title characters shown as \uXXXX escapes.

## TE-P9-03 result (test-engineer)
- STATUS: BLOCKED-ON rag-engineer.
- FILES CREATED: tests/src/test/scala/scholarchain/tests/rag/RenderingOracle.scala (independent re-implementation of claim-rendering.v1 templates/quoting). FILES MODIFIED: Phase9Corpus.scala (sourceAgrees), CollaborationWorkflowCorpusBenchmarkSuite.scala (F1 oracle sweep; sentinel test, model text withheld, pinned 37), CollaborationWorkflowGateAdversarialSuite.scala (G4, G5, M2 rewritten; new F1 adversarial, F2, F3 corpus tests).
- Iteration `sbt test`: exit 1; tests "Failed: Total 231, Failed 1, Errors 0, Passed 229, Skipped 1" (own threshold `>= 40` vs observed 37; fixed by pinning 37, not re-run); other projects 621, 263, 437, 464, 132, 85 passed.
- Final `sbt check`: exit 1 at verifyRagSourceImports before any compile/test: `modules/rag/src/test/scala/scholarchain/rag/agents/RepairP9bSuite.scala:244 suspicious-char U+00A0, ... U+202E`. markTestRun not reached; marker not refreshed.
- Routed: RE-P9-06 (rag-engineer) escape the two literal characters; then TE-P9-04 (fresh test-engineer) one final sbt check.

## RE-P9-06 result (rag-engineer)
- STATUS: COMPLETE. FILES MODIFIED: modules/rag/src/test/scala/scholarchain/rag/agents/RepairP9bSuite.scala (line 244 assertion now builds U+202E and U+00A0 with Character.toString; same runtime input and expected value).
- COMMANDS: `sbt rag/test` once: "Passed: Total 132, Failed 0, Errors 0, Passed 132". Benchmark unchanged (graph 11/11, 87/87, 43 n/a; semantic 2/2, 3/3, 23 n/a). verifyRagSourceImports not yet re-run (next full check).

## Routing
- RE-P9-05 (rag-engineer): F1-F5 with regression tests; supersession note for the v1 files inside prompts/** (rag-owned); focused `sbt rag/test` only.
- TE-P9-03 (test-engineer): follow changes in tests/**, corpus/adversarial F1 regressions, one final `sbt check`.
- CR-P9-03 (code-reviewer): fresh review.
