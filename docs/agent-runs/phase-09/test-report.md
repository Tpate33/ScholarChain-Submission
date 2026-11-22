# Phase 09 test report (orchestrator assembly, 2026-11-21)

## CURRENT: final full check after repair 02 (TE-P9-04, test-engineer; exit 0, 415 s; supersedes all earlier runs)
- core 621/0; entityResolution 464/0; hadoop 85/0; rag "Passed: Total 132, Failed 0, Errors 0, Passed 132"; root 0; services 0; sparkAnalytics 263/0; storage 437/0; tests "Passed: Total 231, Failed 0, Errors 0, Passed 230, Skipped 1".
- markTestRun: `wrote .claude/.last-test-run (git_head=a5cbdab53e91296906be8bbfac284cbeb19a4cd2, worktree=dirty, suites=183, passed=2232, skipped=1)`, completed Nov 21, 2026, 9:05:24 PM.
- Backgrounded by the harness after 180 s; marker written after the audited call returned; Stop hook refused (R1-b). Human marker review required.
- No file changed by TE-P9-04 (four tests/** rag files read: no bidi/format/no-break/zero-width literals).
- Asserted: graph 8/8 cases, 55/55 applicable checks, 35 n/a; semantic (NON-PRODUCTION plumbing only) 1/1, 2/2, 16 n/a; applicability derived independently; pinned 37 compared claims (model text withheld); source counts 148 forms, 9 ambiguous, 157 links, 139 unique names all resolve; outcomes answered 6, abstained 2, refused 1. F1 oracle sweep, F1 adversarial (negation, injected title, Unicode), F2 and F3 corpus tests passed.
- TE-P9-03's earlier final check (exit 1 at verifyRagSourceImports, no tests run) is superseded.

## Superseded: final full check after repair 01 (TE-P9-02, test-engineer; exit 0, 420 s; supersedes TE-P9-01 below)
- core 621/0; entityResolution 464/0; hadoop 85/0; rag "Passed: Total 122, Failed 0, Errors 0, Passed 122"; root 0; services 0; sparkAnalytics 263/0; storage 437/0; tests "Passed: Total 226, Failed 0, Errors 0, Passed 225, Skipped 1".
- markTestRun: `wrote .claude/.last-test-run (git_head=a5cbdab53e91296906be8bbfac284cbeb19a4cd2, worktree=dirty, suites=182, passed=2217, skipped=1)`, completed Nov 21, 2026, 8:13:14 PM.
- The harness moved the run to the background after 180 s; the marker was written after the audited Bash call returned, so the Stop hook refused completion (R1-b). Human marker review required.
- Earlier iteration in TE-P9-02: `sbt test` exit 0, same per-project totals.
- Corpus benchmark (asserted): graph 8/8 cases, 55/55 applicable checks, 35 not applicable; semantic (NON-PRODUCTION plumbing only) 1/1 case, 2/2 applicable checks, 16 not applicable.
- New/updated regressions in tests/**: check applicability derived independently of the runner; M1 (maxResults=2: searchTruncated=true, never VERIFIED shortest, hops >= BFS distance, at least one VERIFIED path-found-hops); M2 reviewer example PARTIALLY_SUPPORTED; M3 model missingEvidence withheld and counted; M4 over-bound name truncated=true with bounded candidates; m1 failed-synthesis audit accounting; m3 bundleId None for non-answers; m5 stored plan with resolved IDs, explicit unresolved entries, filter SHA-256, no names, question text or filter text.
- Failures: none. No gate assertion weakened (agent's statement).

## Superseded: TE-P9-01

## Final full check (the one Phase 9 `sbt check`, run by test-engineer; exit 0, 400 s)
- core: Passed: Total 621, Failed 0, Errors 0, Passed 621
- entityResolution: Passed: Total 464, Failed 0, Errors 0, Passed 464
- hadoop: Passed: Total 85, Failed 0, Errors 0, Passed 85
- rag: Passed: Total 109, Failed 0, Errors 0, Passed 109
- root: Total 0; services: Total 0
- sparkAnalytics: Passed: Total 263, Failed 0, Errors 0, Passed 263
- storage: Passed: Total 437, Failed 0, Errors 0, Passed 437
- tests: Passed: Total 221, Failed 0, Errors 0, Passed 220, Skipped 1 (pre-existing skip)
- markTestRun: `wrote .claude/.last-test-run (git_head=a5cbdab53e91296906be8bbfac284cbeb19a4cd2, worktree=dirty, suites=181, passed=2199, skipped=1)`
- Note: the harness moved this run to the background after 180 s; the agent waited and made no Bash call after it. The marker may be reported as an UNATTRIBUTED change at the next audited Bash call (human review).

Earlier iteration: `sbt test` exit 1, tests "Failed: Total 221, Failed 2" (both wrong expectations in the agent's own new tests, fixed). `sbt tests/test` and `sbt tests/Test/compile` refused by the guard (gap G7: `tests` not in @projects).

## New tests (tests/src/test/scala/scholarchain/tests/rag/)
- SurfaceFormDriftSuite (5): equal version tags; 62 adversarial inputs byte-identical in rag and storage; v1 rules; 20000-string seeded fuzz zero drift; all 157 corpus links (148 forms) reachable by rag's hash.
- CollaborationWorkflowCorpusBenchmarkSuite (11), benchmark set tests/src/test/resources/benchmarks/collaboration-corpus-run1.v1.json (9 cases; aliases filled from source records only):
  - Deterministic graph retrieval: FamilySummary("graph", 8 cases, 8 passed, 90 checks, 90 passed) asserted.
  - Semantic retrieval, NON-PRODUCTION PLUMBING ONLY (stand-in embedder): FamilySummary("semantic", 1, 1, 18, 18) asserted.
  - Direct coauthors, metrics, shortest path (source BFS hop count), not connected, C_r ranking checked against source data. All 9 ambiguous names abstain with all candidates (>= 18 variant questions); all 139 unique names resolve exactly. Outcomes asserted: answered 6, abstained 2, refused 1; every question audited.
- CollaborationWorkflowGateAdversarialSuite (11): G1 database-code injection (11 payloads), G2 ambiguity, G3 evidence IDs resolve and verified assertions match cited non-semantic facts, G4 fabricated numbers/IDs/hops via a scripted fake model (CONFLICTING/UNSUPPORTED/PARTIALLY_SUPPORTED handled), G5 prompt injection in retrieved titles (plan, claims, verdicts unchanged), G6 retry bound, G7 no chain-of-thought keys anywhere, G8 audit for answered/refused/unknown/ambiguous.

Failing tests: none.

## Observed risks (no test fails on them)
1. rag-engineer: TooManyCandidates abstention carries an empty candidate list (README stage 2: "Return multiple candidates").
2. rag-engineer: model free-text missingEvidence lines without numbers or IDs reach the answer text.
3. rag-engineer: labelled PARTIALLY_SUPPORTED/CONFLICTING claims keep original fabricated text; injected titles appear verbatim in "Descriptive context only:" lines.
4. rag-engineer: on synthesis failure the audit records synthesisAttempts=0.
5. build-engineer: tests-project DEBUG logging ~7.7M lines per run (deferred by the human).
6. factory-engineer: G7, no tests-only allowlist shape for test-engineer.
