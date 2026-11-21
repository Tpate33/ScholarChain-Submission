# Phase 08 agent reports (orchestrator assembly, 2026-11-21)

Condensed from each agent's final report in this session. The full verbatim reports are in the session transcript; the report-contract fields that matter for acceptance are reproduced here. The orchestrator did no specialist work.

| Work order | Agent | STATUS | Files | Tests run by the agent |
|---|---|---|---|---|
| SE-P8-01 (previous session) | storage-engineer | BLOCKED-ON test-engineer | 16 modified, 2 new in modules/storage | `sbt storage/test` 410 passed |
| TE-P8-01 | test-engineer | COMPLETE | tests/.../storage/PartIntegrityAdversarialSuite.scala, SummaryTextInjectionAdversarialSuite.scala | `sbt check` 2021 passed (overlapped with `sbt test`; superseded) |
| TE-P8-02 | test-engineer | COMPLETE | none | isolated `sbt check` 2021 passed |
| SE-P8-02 (first run) | storage-engineer | BLOCKED-ON human | none (stopped on an UNATTRIBUTED marker report) | none |
| SE-P8-02 rerun 1 | storage-engineer | COMPLETE | VectorLoad, LoadLedger, RunLedgerStates, ItemCodecs, RejectedRecord, EmbeddingWork, VectorStore, RuntimeStores, Sources; new EmbeddingFailureSuite | `sbt check` 2027 passed |
| SE-P8-03 | storage-engineer | BLOCKED-ON test-engineer | VectorStore, RuntimeStores, RejectedRecord, VectorLoad, LoadLedger (docs); storage tests | `sbt storage/test` 419 passed; `sbt check` exit 1 (one tests/** test) |
| TE-P8-03 | test-engineer | COMPLETE (marker review) | SummaryTextInjectionAdversarialSuite.scala | `sbt check` 2030 passed |
| RE-P8-01 | rag-engineer | BLOCKED-ON (corpus test; routed to test-engineer) | modules/rag main: EmbeddingModels, NonProductionHashedTokenEmbedder, RenderTemplates, ChunkingPolicy, SemanticContext; 4 test suites; prompts/schemas/semantic-context.v1.schema.json | `sbt rag/test` 28; `sbt check` 2058 passed |
| RE-P8-02 | rag-engineer | COMPLETE | RenderTemplates, SemanticContext, RenderTemplatesSuite, SemanticRetrievalSuite | `sbt rag/test` 35 passed |
| TE-P8-04 | test-engineer | BLOCKED-ON (final check only) | new tests/.../rag/SemanticRetrievalCorpusSmokeSuite.scala | `sbt test` 2080 passed, 1 skipped |
| TE-P8-05 | test-engineer | COMPLETE | none | final `sbt check` exit 0: 169 suites, 2080 passed, 1 skipped |

## Delivered against README Phase 8
Storage-engineer items:
- Bounded embedding work queue: manifest-driven batch design. A content-addressed, create-once work manifest in ordinary S3 with a registry row; bounded by maxItemsPerCall and batchSize; resumable. No SQS (an AWS item).
- Vector write batches through the S3 Vectors API: through the storage VectorStore port and an in-memory fake only. The real S3 Vectors adapter is OUTSTANDING.
- Authoritative text in ordinary S3, with textS3Key and textSha256 in vector metadata.
- Compact metadata: every required field is present (vector key, subject/researcher/community IDs, window, topic and institution IDs, document type, source dataset, model version, text hash, run ID).
- Skip unchanged text by content hash and model version. Different text under a key is a Conflict; changed floats are Corrupt.
- Failures and retry counts in RunLedger: per-model rows with EmbeddingTotals, per-item rejections with attempts and final error, a failure field on failed rows, and an incomplete marker on partial sets.
- Vector key `<index>/<modelVersion>/<inputId>`. Queries must name their model (`RuntimeStores.vectorsFor`).

Rag-engineer items:
- Deterministic rendering templates (`scholarchain.rag.render-templates.v1`), validated by a strict rag-owned parser of storage's `context-document.v2` layout, with a test-scope drift check.
- Chunking policy `scholarchain.rag.chunking.v1` (unused locally: no abstracts in the local data).
- Embedding model versioning (canonical labels; RealModelsOnly refuses synthetic labels).
- Stand-in embedder `synthetic-nonprod-hashed-token-bag.v1`: NON-PRODUCTION (human-approved for development smoke tests only).
- Compact semantic-context format v1 (schema in prompts/schemas): budgets, deterministic order, fail-closed checks, and an allowlist so that only work title, work name and topic name are shown verbatim. No deterministic fact is carried.
- Retrieval smoke tests: SemanticRetrievalCorpusSmokeSuite over the 249 local items. Non-production plumbing only (see test-report.md).

## OUTSTANDING (not done; no claim made)
- Production embedding model and access (paid; not authorized).
- Bounded AWS ingestion of at least 10,000 vectors. Locally there are 249 units, and there is NO instructor approval for a smaller corpus.
- Real S3 Vectors adapter, SQS (if used), Terraform indexes and IAM (infra-engineer), including the non-filterable metadata declarations; modelVersion is non-filterable, so model scoping is client-side.
- AWS throughput, rejected records at scale, query latency samples, cost and object counts.
- Phase 7 live AWS items (see docs/agent-runs/phase-07/).
- Deferred non-blocking review items (review-report.md): storage docs and recording-error nit; rag m1, n1 and the control-character check; corpus-suite m1-m4.
- Optional: tests-project DEBUG logging (about 6.6 M lines per check) and disk usage (Hadoop reported 95%); owner build-engineer.
- docs/storage/README.md update (documentation-engineer).
