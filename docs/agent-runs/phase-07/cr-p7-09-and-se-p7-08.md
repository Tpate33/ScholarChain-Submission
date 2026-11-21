# CR-P7-09 (review of SE-P7-07) and SE-P7-08 (WP5 increment 3) — orchestrator-condensed records

Full reports are in the session transcript.

## CR-P7-09 (code-reviewer): VERDICT REQUEST_CHANGES
- Closures verified: CR-P7-08 M1 (negative counts, addExact, in-loop bound), m1 (chunk extents), m2 (SummaryUpstream private, built only from verified readers; empty inputs refused), m3 (hostile + differential Snappy tests), n1, n3; n2 partial. Upstream readers: grouping verified like an analysis run; curated CRC magic, bytes-per-checksum > 0, exact checksum count in Long; inflation bounded while inflating; per-stage `_SUCCESS`/`_counters.json`; run-ID and lineage binding. Gate mapping matches human M2 decision, exhaustive, pinned, no vacuous route. DynamoDB keys unambiguous, ranks deterministic, no TTL, size-checked, putIfAbsent only, provenance on every row; MC-1..MC-8 bounded.
- **M1 (major):** analysis-results keys (TableKeys.scala:187-212) carry no run ID; LoadLedger writes in order and stops at the first error (LoadLedger.scala:184-201); a second, different run partly writes and then Conflicts, leaving two runs mixed and the ledger failed permanently. TableSpecs.scala:281-284 claim not implemented. Human decision on multi-run semantics needed.
- m1: no negative tests for new refusal paths (tampered upstream; CRC problems; stage files; part names; run-ID mismatch; empty inputs).
- m2: old ResearcherMetricRecord codec shares the analysis-results key space and rank GSI.
- m3: curated reader CRC-checks one read and decodes another; metadata decoded with lenient UTF-8.
- n1: "test classpath only" comment for snappy-java probably wrong (parquet-hadoop Optional at storage Compile/Runtime); read path does not use it. n2: duplicated role map.
- Unverified: **U1 (likely major for cloud):** EMR output on S3/HDFS has no `.crc` sidecars, so the curated reader would refuse every real cloud curated run (fail closed, unusable) — needs an S3 curated-zone integrity design before deployment (tracked outstanding). U2 parquet-mr value-count amplification; U3 snappy-java/aircompressor/zstd-jni probably on storage Compile/Runtime classpath (build-engineer); U4 GZIPInputStream trailing bytes; U5 DynamoDB item size accounting.
- Routing: all findings sent to the running SE-P7-08 (addendum); M1 fail-closed part implemented; multi-run semantics → human.

## SE-P7-08 (storage-engineer): WP5 increment 3 + CR-P7-09 addendum
- STATUS: BLOCKED-ON human (procedural marker review). `sbt check` backgrounded (task bm7t1k78l); agent read: core 621, entityResolution 464, hadoop 85, sparkAnalytics 259, storage 316, tests 145, all Failed 0 Errors 0; markTestRun suites=148, passed=1890, skipped=0; `[success] Total time: 352 s (0:05:52.0), completed Nov 20, 2026, 4:56:51 PM`; `[exited with code 0]`.
- Commands also: `sbt storage/Test/compile` (×7, four failed then fixed), `sbt storage/test` (×4; final 316/316), `sbt storage/scalafmt storage/Test/scalafmt`, one guard-refused `sbt storage/testQuick`.

### Real local loads through the production entry point `OpeningLoad.run` (outside package `load`; nothing marked by hand)
- paths-run-chain PASSES: benchmark list read by the production reader (bounded, no links, strict UTF-8, one LF line, canonical); digest via core `BenchmarkPairList.digest` = 2e1ff1824ba18c90a8b154e9932c51c64565779f62eeb2a519df22ea20bc8be9 = manifest `benchmarkPairsDigest`; new check `benchmark-pairs` (digest, provenance runId = grouping run, pairs identified); core `checkPaths`, `checkRunChain` no rejection. Fixture sha256 pinned c37e3db9…bae10. Tampered copy refused (digest 4a571ddb…01bb), zero writes in every store.

| Store | Checks performed | Result | Counts |
|---|---|---|---|
| neptune-bulk-load | row-contracts, graph-ids, graph-id-parity, evidence-trace, analysis-recompute, canonical-tables | loaded, written 1258 | 459 vertices, 799 edges |
| dynamodb | parquet-rows, row-contracts, evidence-trace, analysis-recompute, paths-run-chain | loaded, written 1302 | analysis-windows 2, bridge-measures 176, coauthor-edges 259, community-memberships 176, component-memberships 176, coreness 176, evidence-paths 9, path-length-estimates 37, researcher-metrics 176, temporal-changes 112, window-comparisons 1, window-structures 2 |
| s3 | parquet-rows, row-contracts, graph-id-parity, evidence-trace, summary-evidence, analysis-recompute, canonical-tables, paths-run-chain | loaded, written 29 | 29 objects + 29 ArtifactRegistry rows |
| s3-vectors | parquet-rows, row-contracts, summary-evidence | verified, no writer (WP6) | 4 parts, 249 rows |
- RunLedger: 4 rows (1 publication + 3 load). Conflicts 0.
- Rerun same ledger: all three skipped-already-loaded, written 0. Fresh-ledger rerun: written 0, already present 1258 / 1302 / 29.
- Interrupted dynamodb (501st write throttled): first run dynamodb failed with 500 stored, neptune and s3 loaded; rerun dynamodb written 802 + present 500 = 1302; others skipped.
- One flipped byte in a run copy: refused by part-integrity in every store, zero write calls. Two full runs into fresh stores give equal reports.
- Entry point for WP7: `OpeningLoad.run(OpeningLoadSources, OpeningLoadConfig, OpeningLoadTargets): OpeningLoadResult(report, runtime: RuntimeStores)`; deterministic report per store. Local invocation: `sbt storage/test` via OpeningLoadSuite (no runMain added).

### CR-P7-09 addendum
- M1 fail-closed part DONE: `LoadLedger.run` takes a probe; after the row moves to writing and before the first data write, every element is probed (KV GetItem + recordSha256; Object HEAD sha256/bytes + registry row; Graph `storedVertex`/`storedEdge`); any difference → row failed, Conflict, zero data writes. Tests for each loader with another run's data: zero writes, ledger failed, other run intact. TableSpecs comment corrected. Run-scoped keys NOT implemented (human decision pending); proposed design: `run#<hex>#…` prefix in pk and GSI partitions + RunLedger active-run pointer switched with putIfVersion after `loaded`; reads do one GetItem for the active run first.
- m1 DONE: UpstreamRefusalsSuite (16 tests, temp copies). m2 DONE: old codec/record/keys removed. m3 DONE: read-once caching source; strict UTF-8 metadata; **deviation:** one optional final LF accepted on the curated run manifest (the real Hadoop manifest has none). n1 DONE. n2 NOT DONE.
- CR-P7-07 n6 not implemented; proposed separate staging ledger row for the bulk-load CSV copy.
- FILES CREATED: main load/BenchmarkPairs, load/OpeningLoad; tests OpeningFixture, OpeningLoadSuite, BenchmarkPairsSuite, UpstreamRefusalsSuite. MODIFIED: main load/LoadVerification, ParquetChecks, UpstreamRuns, LoadLedger, GraphLoad, KeyValueLoad, ObjectLoad; model/ItemCodecs, Records, TableKeys, TableSpecs; port/GraphStore; fake/InMemoryGraphStore, Recording; tests TestData, ConditionalWritesSuite, SerializationSuite, TableSpecsSuite, load/ObjectLoadSuite, KeyValueLoadSuite, GraphLoadSuite, parquet/ParquetHostileSuite; formatting-only changes from scalafmt possible elsewhere in storage. No fixture modified.
- Risks: probe and write not atomic (a concurrent writer is still caught by create-once, but the load could stop part-written); one read per element; curated CRC32 only; optional-LF deviation; stale suite docstrings; `GraphWriter` gained two abstract methods (adapter must implement).
