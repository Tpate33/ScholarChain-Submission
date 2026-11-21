# CR-P7-10 (review of SE-P7-08) and SE-P7-09 (WP6 vectors) — orchestrator-condensed records

Full reports are in the session transcript.

## CR-P7-10 (code-reviewer): VERDICT APPROVE (no critical or major)
- Verified from code: pre-write probe placement (LoadLedger.scala:189-212) and full-element comparison (KV recordSha256 over the whole body; object sha256+bytes+registry; graph full equality incl. provenance); resume counts own writes as present; OpeningLoad is the production path (all evidence types `private[load]`); deterministic report; read-only runtime facades; counts consistent (1258 / 1302 / 29 / 249). Benchmark reader sound. CR-P7-09 m2, m3, n1 closed; optional final LF on the curated manifest acceptable (identity from decoded record). `storedVertex/storedEdge` implementable with parameterized openCypher reads.
- m1: one-run-per-store was procedural only — a second run with a different window set shares no dynamodb key, so it would load and mix; comments overstated the guarantee.
- m2: failed-outcome report said written=0 although 500 items were created; ledger re-added resumed AlreadyPresent.
- m3: two refusal tests not pinned to their check.
- Nits n1-n4 (graph writes not counted; registry-rows restated; no-op replace; lenient UTF-8 on grouping `_parts.json`).
- Risks for the deferred Neptune adapter: R1 property/provenance round-trip fidelity (else every probe is a false Conflict); R2 bulk-loaded elements lack provenance; R3 one probe read per element; R4 = CR-P7-09 U1; R5 runtime returned regardless of report status.
- Routing: m1-m3, R5, n1-n4 sent to the running SE-P7-09.

## SE-P7-09 (storage-engineer): WP6 vector loading + CR-P7-10 addendum
- STATUS: BLOCKED-ON human (procedural marker review). `sbt check` backgrounded (task bgb3mblu9); agent read: core 621, entityResolution 464, hadoop 85, sparkAnalytics 259, storage 339, tests 145, all Failed 0 Errors 0; markTestRun suites=149, passed=1913, skipped=0; `[success] Total time: 354 s (0:05:54.0), completed Nov 20, 2026, 5:31:44 PM`; `[exited with code 0]`.

### Real-fixture load through `OpeningLoad.run` (production gates, nothing marked by hand)
| Store | Written | Counts |
|---|---|---|
| neptune-bulk-load | 1258 | 459 vertices, 799 edges |
| dynamodb | 1302 | per-role counts asserted (2/176/259/176/176/176/9/37/176/112/1/2) |
| s3 | 29 | 29 objects, 29 registry rows (measured) |
| s3-vectors | 249 | researcher-profile-v1 176, collaboration-community-v1 51, paper-abstract-v1 8, collaboration-path-v1 9, topic-institution-summary-v1 5; 0 rejected; 249 text objects + 249 registry rows |
- Totals: 278 objects, 278 ArtifactRegistry rows, RunLedger 9 rows (4 store-owner, 1 publication, 4 load).
- Same-ledger rerun: all skipped, zero writes. Fresh-ledger rerun: written 0, present 1258/1302/29/249.
- Interrupted vector load (batch throttled through 4 attempts; backoff 50/100/200 ms): first call failed with 100 written; rerun 149 written + 100 present = 249; text objects written once.
- Interrupted dynamodb (m2): first call reports written=500; resume 802 + 500 present; ledger ends written 1302, present 0.
- Tampered summary input: refused by part-integrity (or parquet-rows when re-sealed) in every store, zero writes, no runtime view.
- Second run (m1): planted store-owner rows for another run → all four stores Conflict ("one analysis run per local store instance"), zero data writes. Simulated: no second analysis-run fixture exists.

### What was built
- `load/VectorLoad.scala`: inside LoadGate with an s3-vectors permit; shared LoadLedger with a batched probe; five-index routing by document type (V6); key `<index>/<suminput ID>` (V2; duplicate key with different text hash = Conflict); deterministic text rendering of verified facts stored create-once under `semantic-context/loads/<loadHex>/text/<chunkHex>.txt` with sha256 + registry row; metadata only `textS3Key`, `textSha256` and compact fields, limits from configuration (V3); filters researcher, year window, source, topic, community, document type tested on loaded data, institution on synthetic metadata only (V4); batching capped by configuration, bounded exponential backoff with jitter via injected Clock/Sleeper/Jitter, RejectedRecord rows + one rejects object, `maxRejected` bound (V5); provenance (runId, loadId, sourcePath, schema version, model ID) on every vector.
- `fake/SyntheticTestEmbedding.scala`: model ID `synthetic-test-only.v1` on every vector; `EmbeddingPolicy.RealModelsOnly` (production) refuses it; `SyntheticTestOnly` refuses real models. Dimension/distance/non-filterable keys checked against configuration.
- CR-P7-10: m1 DONE (StoreOwnership RunLedger row `store#<tag>`/`owner`, putIfAbsent before any data write, all four stores; comments corrected); m2 DONE; R5 DONE (`OpeningLoadResult.runtime: Option[RuntimeStores]`, None unless every store loaded or skipped — API change for Phases 9-11); m3, n1, n3, n4 DONE; n2 DONE (registry rows measured). CR-P7-09 n2 (role map from core LoadRoles) not done.
- FILES CREATED: main load/VectorLoad, fake/SyntheticTestEmbedding; test load/VectorExploreSuite.scala (holds class VectorLoadSuite; file name mismatch left because agents cannot rename). MODIFIED: main load/LoadLedger, OpeningLoad, GraphLoad, KeyValueLoad, ObjectLoad, UpstreamRuns; model/Vectors, RunLedgerStates, TableKeys, TableSpecs, ItemCodecs; tests OpeningFixture, OpeningLoadSuite, TestData, VectorMetadataSuite, VectorRegistrySuite, UpstreamRefusalsSuite, UpstreamChecksSuite. No fixture modified.

### Decisions requested by the agent
1. End-to-end per-value metadata cap 1024 bytes (at 256, 2 paper-abstract rows are rejected for long author-ID lists; both cases tested). This is ScholarChain's own guard, not an AWS limit; the 2048-byte filterable budget unchanged; all limits UNVERIFIED.
2. Model ID on the vector only, not on the text object (text is model-independent for Phase 8 re-embedding).
3. collaboration-path-v1 `researcherIds` optional (path summaries cite no researcher).
- Housekeeping: rename the test file VectorExploreSuite.scala → VectorLoadSuite.scala (human or a later work order).

### Risks / outstanding
- Fresh ledger in front of populated stores relies on the per-key probe; non-atomic probe/write window; single-part rejects object (a rerun rejecting a different set would Conflict); all embeddings computed before writes; synthetic vectors carry no meaning; second-run test simulated.
- Outstanding: real embeddings (Phase 8); W1-W6, `sbt storageIT` (row inactive); offline SDK adapters; AWS limits verified with date; multi-run coexistence (deferred); real second-run fixture; S3 curated-zone integrity (CR-P7-09 U1); CR-P7-07 n6.
