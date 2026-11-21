# Phase 8 session handoff (orchestrator, 2026-11-21)

The human is ending this session and starting Phase 8 afresh with the professor's Phase 8 prompt. This file records state only; it approves nothing.

## Phase 7 (committed)
- a62f85b "Phase 7: local storage closeout; live AWS outstanding" (implementation).
- ef2d7df "Record Phase 7 local closeout commit" (human-gate.md and commit.txt; commit.txt points to a62f85b).
- Phase 7 live AWS work stays outstanding (see docs/agent-runs/phase-07/phase-08-handoff.md and requirements-checklist.md).

## Uncommitted Phase 8 changes (storage-engineer, work order SE-P8-01, rerun)
Files in modules/storage only (git status at handoff time):
- Modified: fake/SyntheticTestEmbedding.scala, load/LoadLedger.scala, load/OpeningLoad.scala, load/VectorLoad.scala, model/ItemCodecs.scala, model/RejectedRecord.scala, model/RunLedgerStates.scala, model/S3Layout.scala, model/TableKeys.scala, model/Vectors.scala, port/Sources.scala; tests OpeningFixture.scala, OpeningLoadSuite.scala, VectorMetadataSuite.scala, VectorRegistrySuite.scala, load/VectorExploreSuite.scala.
- New: model/EmbeddingWork.scala, test load/EmbeddingWorkSuite.scala.
- Purpose (work order; the agent's report is summarised below): embedding model version in vector keys; skip unchanged text by content hash and model version; manifest-driven bounded embedding work queue (no SQS); per-item failures and retry counts in RunLedger; Embedder port (model version, dimension); Phase 8 report counts and an injected clock. No real or meaningful embedder was to be added.
- The orchestrator has not read or reviewed these diffs. No code review has run.

## Verification results (storage-engineer's final report, received after this file was first written; STATUS: BLOCKED-ON test-engineer)
- The background build was `sbt test` (all projects), not `sbt check`. It **exited 1**. `sbt check` was **not run**.
- `sbt storage/test` (final): "Passed: Total 410, Failed 0, Errors 0, Passed 410" (395 + 15 new: 14 EmbeddingWorkSuite, 1 updated VectorRegistrySuite key test).
- tests/** failures, read by the agent from JUnit XML under tests/target/test-reports/ (the 282 MB output was not read in full; overall `sbt test` totals and other modules' reports were not read):
  - PartIntegrityAdversarialSuite: 9 tests, 1 failure, line 155: expected 249 objects written, got 250 (the new embedding work manifest); line 156 (registry 249) will also become 250.
  - SummaryTextInjectionAdversarialSuite: 4 tests, 1 failure, line 170 "no vector topic-institution-summary-v1/suminput:fd04…": line 165 builds the old Phase 7 key `<index>/<inputId>`.
  - VectorAdversarial 7/0, LoadIdempotency 5/0, GraphIdConsistency 6/0, GraphInjection 2/0 failures.
- Both failures follow from the work order (new key format; new manifest object). Fix owner: test-engineer (tests/**): use `VectorKeys.of(index, SyntheticTestEmbedding.ModelVersion, inputId)` at :165; expect 250/250 at :155-156. Then one `sbt check`, then code review.
- Test marker unchanged (Phase 7: 2026-11-21T09:07:45Z, 162 suites, 2006 passed). The human has not reviewed any new marker.

## SE-P8-01 design as reported (unreviewed)
- Vector key `<index>/<modelVersion>/<inputId>`; modelVersion canonical `[a-z0-9][a-z0-9._-]{0,127}` (refused otherwise; also closes the case-sensitive synthetic check, CR-P7-20 n4). New model → new keys (tested: v1 + v2 = 498 vectors, v1 unchanged).
- Skip rule: same key with equal metadata incl. textSha256 and intact floats → Unchanged (no embed); different textSha256/model under a key → Conflict; digest mismatch → Corrupt.
- EmbeddingWorkManifest v1, content-addressed create-once under `<semantic-context>/loads/<loadHex>/embedding-work/model=<mv>/manifest-<sha256>.json` with a registry row; bounded by `maxItemsPerCall`/`batchSize`; resumable (`documents-staged` until done).
- RunLedger: s3-vectors row per model (`load#<loadId>#model#<mv>`) with EmbeddingTotals (embedded, putRetries, embedRetries, remaining, passes); rejections scoped by model; RejectedRecord carries attempts and final error.
- Risks reported: modelVersion non-filterable (mixed embedding spaces if two models share an index; rag/infra decision); different dimension needs new indexes (Terraform); S3 Vectors limits unverified (aws-docs MCP failed: uvx not found); docs/storage/README.md must be updated (documentation-engineer).

## Pending human decisions (blocking dependent work)
1. Production embedding model and access (paid API calls not authorized; e.g. a hosted model).
2. Free deterministic local stand-in embedder, labelled non-production, for retrieval smoke tests with known neighbours (recommended; not approved).
3. Corpus: 249 local units vs the ≥10,000 vectors required; no instructor approval for a smaller corpus.
4. Queue: manifest-driven locally (recommended, implemented in SE-P8-01, unverified); SQS remains an AWS item.
5. Optional: fix tests-module DEBUG logging so `sbt check` returns to minutes.

## What the fresh session must check before continuing
1. Compare the professor's Phase 8 prompt with README.md Phase 8 (L1436-1482) and docs/agent-runs/phase-08/prompt.md; follow the professor's prompt where they differ and do not adjust requirements to the implementation.
2. Decide whether to keep the uncommitted SE-P8-01 changes: review the diff (`git diff -- modules/storage`), check that they match the professor's prompt (key versioning, skip rule, queue, RunLedger failures/retries, metadata fields, five index names), and run `sbt check` once to get real results.
3. Confirm the storage/rag split (rendering templates, chunking, model versioning, smoke tests and semantic-context format belong to rag-engineer).
4. Get the human decisions above before embedder- or AWS-dependent work.
5. Keep outstanding AWS work explicit: S3 Vectors adapter, bounded AWS ingestion, throughput/latency/cost reporting, IAM, Terraform indexes.
