# Phase 11 human acceptance — local scope

Date: [2026-11-22]
Decision:Accept] the Phase 11 adversarial testing, review and repairs, verified LOCALLY only.
Accepted implementation commit: [161acf4e1abd841aa87955f1997e380cb0698ed9]

## What was reviewed
Work orders (prompt.md); agent reports (agent-report.md: RG-P11-R1, TE-P11-01, SE-P11-01 checkpoint); test report (test-report.md: TE-P11-02); review reports (review-report.md: CR-P11-01 tests APPROVE and gate REQUEST_CHANGES; CR-P11-02 APPROVE); repair records (repair-01.md, rag-engineer; repair-02.md, service-engineer); outstanding.md.
[This does not claim a personal line-by-line source review."]
Manual implementation changes during acceptance: [none / describe].

## Final verification (local, no AWS)
- Final `sbt check` (repair 02): exit 0; 207 suites; 2388 passed; 0 failed; 0 errored; 1 skipped (the existing SemanticRetrievalCorpusSmokeSuite skip).
- Per project: core 621, entityResolution 464, hadoop 85, rag 141, root 0, services 108, sparkAnalytics 263, storage 437, tests 269 (1 skipped).
- Marker: written 2026-11-22T18:31:41.019564Z, git_head=a49317a, worktree=dirty. The run was backgrounded by the harness, and its markTestRun write was attributed by the audit to a concurrent read-only git call. I reviewed the marker together with `git status`; the changed paths match the Phase 11 tests, repairs and records. The unchanged tree was not re-run solely for the audit pause.
- No live AWS call, paid model call, deployment, resource change, commit by an agent or push was made in this phase. No bounded AWS integration test was run: no approved AWS profile is active, and the `storageIT` row is inactive.

## Phase 11 gate (README.md:1740-1746)
- No critical or major verified finding remains: CR-P11-02 APPROVE. CR-P11-01-1 (major, Phase 9 R1) and CR-P11-01-2 to -6, plus U1, are closed.
- Every production behaviour changed in this phase has a relevant test (CR-P11-02 §5). Each of the 21 required categories has at least one test (test-report.md §4).
- All tests pass under their declared local profile. Live-AWS (P) checks are carried into integration (below), not passed.
- No reviewer wrote production code.
- Repair records identify owners and rerun evidence (repair-01.md, repair-02.md).

## Decisions recorded in this phase
- Phase 11 took priority over the production-integration request. SE-P11-01 (DynamoDB adapter and durable stores) stopped at a checkpoint with no code written; its design and routing items are kept in agent-report.md for reissue.
- 39 adversarial tests were added under tests/** without production changes. The 10 tests that exposed defects were left failing until the owners repaired the code; no test was weakened.
- [Accept / overrule] Fabricated evidence IDs appear in client text only as a count ("cites N evidence ID(s) not in the evidence bundle"); the raw IDs stay internal.
- [Accept / overrule] Audit-record schema v3 (new field `verifierVersion`, claim verifier `scholarchain.rag.claim-verification.v4`); v2 is listed in prompts/SUPERSEDED.md.
- [Accept / overrule] The idempotency digest hashes an ASCII-escaped rendering of the request. This closes the U1 collision (a lone surrogate and `?` hashed alike) and changes digest values for non-ASCII questions.
- Old-audit-schema compatibility (CR-P11-02-1: explain rejects superseded v2 rows as corrupt, ReadServices.scala:460) is a mandatory prerequisite before durable QuerySession storage is integrated. Owner: service-engineer, with rag-engineer.

## Local acceptance versus integration readiness
Accepted here: adversarial coverage and the two repairs, verified against the local implementation with in-memory fakes and stubbed ports.
NOT accepted here, and still required before deployment (see outstanding.md, "(P) items carried into integration"):
- Neptune, DynamoDB and S3 Vectors production adapters, with timeout, conditional-write and throttling behaviour tested against stubbed SDK clients, then bounded live tests under the approved profile;
- durable ownership, job and idempotency stores (SE-P11-01, reissue), and old-audit-schema compatibility before them;
- core `services.*` and `storage.dynamodb.*` configuration keys and deployed values;
- the production ServiceProvider, the Secrets Manager key source, and launchers or images per service;
- Terraform, ECS, ALB, IAM and deployed timeouts and logging, through a reviewed plan;
- a real answer model and a real embedder: paid-call authorization, plus the Phase 9 residuals (omission or cherry-picking of true facts; R2, combining marks in quoted titles);
- EMR-scale data runs.

## Remaining risk (see outstanding.md)
- Open, not blocking: CR-P11-02-1 (P, prerequisite above); nits CR-P11-02-2 and -3; unverified CR-P11-01 U2 (query-parameter names echoed in 400 responses) and U3 (no retry backoff); CR-P11-02 U-a; test nits T-1 to T-3; the category 10 runtime gap (profile present but topic or institution absent).
- The Phase 10 accepted residuals and the CR-REL-01 publication findings F1-F11 are unchanged.
- Process: backgrounded `sbt check` runs and concurrent calls caused two audit pauses this phase. The durable fix belongs to build-engineer and factory-engineer and needs my authorization for hook changes.

This acceptance does not authorize deployment, paid calls, AWS operations, publication, deletions or hook changes.
