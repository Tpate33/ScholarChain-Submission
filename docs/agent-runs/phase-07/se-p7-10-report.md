# SE-P7-10 (storage-engineer): repair of CR-P7-11 findings + DU-P7-02 storage items — orchestrator-condensed record

The full report is in the session transcript.

- STATUS: BLOCKED-ON human (procedural marker review). `sbt check` backgrounded (task bn5bxno84); agent read lines 228681-228705: core 621, entityResolution 464, hadoop 85, sparkAnalytics 259, storage 361, tests 145, all Failed 0 Errors 0; markTestRun storage production 47 / test 43, suites=152, passed=1935, skipped=0; `[success] Total time: 353 s (0:05:53.0), completed Nov 20, 2026, 6:55:36 PM`; `[exited with code 0]`.
- Guard-refused (nothing ran): `ls -la …`, `git show --stat HEAD -- …`, `sbt storage/testOnly …`, `sbt storage/testQuick`.
- FILES CREATED: tests RuntimeVectorPolicySuite, CodecReferenceScanSuite, parquet/ParquetCodecIsolationSuite. MODIFIED main: load/LoadLedger, VectorLoad (restructured), OpeningLoad, GraphLoad, KeyValueLoad, ObjectLoad; model/RejectedRecord (schema v2), TableKeys, ItemCodecs, Vectors; port/VectorStore, RuntimeStores. Tests: OpeningFixture, OpeningLoadSuite, load/VectorExploreSuite (class VectorLoadSuite), TestData, TableSpecsSuite, SerializationSuite, VectorRegistrySuite, VectorMetadataSuite, RuntimeStoresTypeSuite. No fixture, build, hook or marker touched.

## Items
1. M1 DONE: `WrittenStores` per loader (graph → neptune; KV → dynamodb; object → s3 + dynamodb; vector → s3-vectors + s3 + dynamodb); `LoadLedger.claimOwners` reads every owner row first (any foreign owner → zero writes, ledger included), then claims absent rows with putIfAbsent; OpeningLoad pre-reads all four owner rows. Tests: each single store foreign-owned → all stores Failed, zero writes, zero embedder calls; the exact CR-P7-11 sequence (A's vector load refused under RealModelsOnly; B with planted owner rows refused everywhere; A then completes: 249 vectors, 9 ledger rows). Comment OpeningLoad.scala:198-219 corrected (fresh ledger in front of populated stores relies on per-key probes; steps not atomic).
2. M2 DONE: `ContextText.render` requires identity; one `identity: <core JSON>` line plus readable lines; metadata `identityStatus`, `anchorCollision` required on researcher-profile-v1. Real fixture: 176 texts carry the identity exactly; (status, collision) counts (resolved,false) 168, (resolved,true) 2, (singleton,true) 2, (singleton-t1-anchored,false) 4; filter by identityStatus works.
3. m1 DONE: rejection evidence keyed per write generation (row sk `reject#<loadId>#<generation>#<recordKey>`, object `rejects/part-<generation>.jsonl`, RejectedRecord v2 with `generation`); a row left `writing` is failed first; current evidence = highest generation; CR-P7-11 inputs (a), (b), (c) tested.
4. m2 DONE: `RuntimeStores.apply` requires an explicit `vectorPolicy` (no default; compile test); `VectorReader.modelChecked` refuses vectors the policy does not accept (RealModelsOnly refuses synthetic or missing modelVersion).
5. m3 DONE: Failed vector outcome reports text-written 249, registry-written 249.
6. m4 DONE: embedding inside the ledger-guarded writer, per batch, only for vectors not already stored; embedder calls: first load 9/249; reruns, ownership refusal, index refusal 0; resume embeds 149.
7. m5 DONE: filterable field declared non-filterable refused.
8. Nits: jitter clamped (DONE); fallback-key rejections counted per index (DONE); CR-P7-09 n2 NOT DONE.
9. DU-P7-02: (a) codec scan as a test — storage main 1 hit: UpstreamRuns.scala:329 string constant `"org.apache.hadoop.io.compress.GzipCodec"` compared with the curated manifest's codec parameter (no class imported or loaded); 16 test hits (scan patterns; ParquetFooterSuite:66-78; ParquetCodecIsolationSuite:31-34); no commons-pool hit. (b) ParquetCodecIsolationSuite: isolated loader without snappy-java-1.1.10.4, aircompressor-0.21, zstd-jni-1.5.0-1 (ClassNotFoundException confirmed for four entry classes) runs production `ParquetRows.read` over all 29 parts: rows equal, codec set SNAPPY; valid after the build change.

## Real-fixture load (OpeningLoad.run)
- neptune 1258 (459 + 799); dynamodb 1302; s3 29 objects + 29 registry rows; s3-vectors 249 (176/51/8/9/5), 0 rejected, 249 embedded. Object store 278, ArtifactRegistry 278, RunLedger 9 (4 owner, 1 publication, 4 load).
- Same-ledger rerun: all skipped, 0 writes, 0 embedded. Fresh-ledger rerun: 0 written, all present, 0 embedded. Interrupted dynamodb 500 → 802 + 500; interrupted vectors 100 → 149 + 100. Tampered input refused everywhere, zero writes.

## Risks reported
1. **ParquetFooterSuite.scala:58-80 asserts zstd-jni and aircompressor classes are PRESENT on the storage Test classpath; it will fail once build-engineer excludes them.** Needs a storage-engineer change before or with the WP3 build change.
2. Context-document format changed (identity lines) but version string still `scholarchain.context-document.v1`.
3. No TableSpecs access pattern for per-generation reject rows (RL-4 prefix still serves them).
4. Ledger `rejected` count sums all generations (can exceed the current set).
5. **The probe now compares a stored vector by metadata and dimension, not float values: a vector with the same metadata but different floats counts as present (not detected).** Rationale: resume without a deterministic embedder; textSha256 and modelVersion are in metadata.
6. Texts held in memory during the load; embeddings per batch.
7. A concurrent second loader finding `writing` marks it failed (concurrent loads unsupported locally).
8. AWS limits unverified; multi-run coexistence deferred.
