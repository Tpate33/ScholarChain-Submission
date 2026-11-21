# SE-P7-04 (storage-engineer): m1, m2, Mirror compile test, nits, codec assessment — orchestrator-condensed record (interim)

The full report is in the session transcript.

- STATUS (interim): BLOCKED-ON human (procedural). `sbt storage/test` passed: "Passed: Total 137, Failed 0, Errors 0, Passed 137", completed 2:01:29 PM (first run failed 1/137 on a guessed Parquet writer string; the exact string is now asserted). `sbt check` was moved to the background after 180 s (task bze8hcw2l); its output stops at 14:05:18 in the Spark suites with no summary. No Bash call was made after the move.
- Marker as read by the orchestrator after the hand-back: unchanged at written_at_utc=2026-11-20T18:04:44.645538Z, so markTestRun had not run at that time.
- FILES CREATED (modules/storage/src/): main/.../parquet/ParquetFooters.scala (footer metadata from in-memory bytes); test LoadGateSuite (8), ParquetFooterSuite (5), load/LoadPackageControlSuite (4).
- FILES MODIFIED: load/LoadVerification.scala, local/LocalRunSource.scala, port/Sources.scala (Scaladoc), tests LoadVerificationSuite, Fixtures (RecordingRunSource), CsvRecordsSuite, LocalRunSourceSuite. No fixture, build file, hook or marker touched.

## Human marker review (2026-11-20, human Terminal)
- Marker written_at_utc=2026-11-20T20:07:44.528545Z: suites 129, tests_passed 1711, failed/errored/skipped 0, production_sources 141.
- Human statement: "Marker reviewed. The saved Terminal output confirms sbt check succeeded with exit code 0, completed November 20 at 2:07:44 PM CST: 129 suites, 1711 passed, storage 137, zero failures/errors/skips. Correct the earlier 130-suite estimate. Disk space remains 13 GiB available."
- Screenshot per-project lines: core 621, entityResolution 464, hadoop 85, sparkAnalytics 259, storage 137, tests 145 (rag, root, services 0); markTestRun storage production sources 29, test sources 18; `[success] Total time: 339 s (0:05:39.0), completed Nov 20, 2026, 2:07:44 PM`; `[exited with code 0]`.
- Orchestrator correction: its earlier 130-suite expectation was wrong; three suites were added (129).
- STATUS (final): implementation COMPLETE; `sbt check` observed passing by the human. Code-reviewer review (CR-P7-05) is next.

## m1 (gate)
- `accepted` removed; structural verdict renamed `structurallyValid`. `enum DeferredCheck` (parquet-rows, row-contracts, graph-ids, evidence-trace) with fail-closed `appliesTo(store)`:
  - neptune-bulk-load: row-contracts, graph-ids, evidence-trace
  - dynamodb: parquet-rows, row-contracts, evidence-trace
  - s3: parquet-rows, row-contracts, graph-ids, evidence-trace
  - s3-vectors: parquet-rows, row-contracts
- `LoadGate.proceed(report, target)` calls the writer only with no rejections, a load manifest for the target (else `target-load`), and every applicable check in `passedChecks` (else `deferred-checks`). Writers take a `LoadPermit` (plain class, `private[load]` constructor) that reads only that store's items.
- `passedChecks` is empty today, so no store can proceed (intended). Tests: exact mapping; real fixture refused per store with zero writes; pass branch exercised only in-package.

## m2 (binding) — option (b), re-checking handle
- `VerifiedRun` holds no bytes and a private reference to the verified `RunSource`. `readVerified(path)` re-reads and returns bytes only if size and SHA-256 equal the `_parts.json` entry; else `Corrupt` ("changed since verification"); unlisted paths refused. No method takes a source.
- Tests: flipped byte, swapped valid Parquet, truncated, deleted part each refused at read with zero writes; unchanged part uploads once; reads only through the bound source; `readVerified(b, path)` does not type-check.

## Constructor surfaces
- `VerifiedRun` and `LoadPermit` are plain classes. Outside package `load`, `typeCheckErrors` reports errors for `new`, creator application, `Mirror.ProductOf` summon and `fromProduct`, and `copy`. In-package control suite compiles the constructors.

## Nits
- n1 fixed (manifest read once), n2 fixed (maxDepth enforced on `read`; Scaladoc states maxFiles applies to listings only and `read` does not require a prior listing), n4 fixed (missing `_SUCCESS`: one listing, zero reads), n5 tests added (trailing blank line refused; BOM kept as content; 4 MiB field; 100,000 embedded LFs). n3, n6 unchanged.

## Codec assessment (footer metadata only; nothing decompressed)
- All 29 fixture Parquet parts: every column chunk SNAPPY; union {SNAPPY}. No ZSTD, LZ4, LZ4_RAW, LZO, GZIP, BROTLI, UNCOMPRESSED. Footer row counts equal declared rowCount. Writer "parquet-mr version 1.13.1 (build db4183109d5b734ec5930d870cdae161e408ddba)". The test asserts the exact set.
- Decoding these fixtures reaches `org.apache.parquet.hadoop.codec.SnappyCodec` and snappy-java (`org.xerial.snappy.Snappy`). zstd-jni and aircompressor are on the classpath but not reached by these files.
- Spark writer: `modules/spark/src/main/scala/scholarchain/spark/output/RunPublisher.scala:437` and `:765` write Parquet with no compression option; `CollaborationJob.scala:74` sets no Spark conf. The codec is therefore Spark's default or a `--conf`; that the default is snappy is the agent's recollection, unverified (aws-docs MCP down); the footers confirm SNAPPY for these fixtures.
- No claim that any library is safe.

## Risks
- `sbt check` not observed to pass. Gate pass branch reachable only from the in-package test until a deferred check exists. Check-then-open race remains (hash is of the returned bytes). Parquet code is Optional scope, absent from the services runtime classpath. AWS limits unverified. CR-P7-03 R1 concurrency and public mutable `WriteCounts` carried to the write increment.
- Assumption to review: the store-to-check mapping (e.g. s3 gated on graph-ids).
