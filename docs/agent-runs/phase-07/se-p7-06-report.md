# SE-P7-06 (storage-engineer): WP5 (rerun) — orchestrator-condensed record

The full report is in the session transcript.

## First attempt (stopped) and audit incident
- The first SE-P7-06 run stopped after its first `git status --porcelain`: audit-bash-writes.sh `--post` flagged `docs/agent-runs/phase-07/next-human-action.md (owner: ORCHESTRATOR)`. Cause: the orchestrator's own concurrent Write to that file (an audited Write in its territory) landed during the agent's call. No storage file was changed. The human reviewed and kept the change ("Keep it, rerun SE-P7-06") and directed: no orchestrator file writes while command-running agents are active.

## Rerun
- STATUS: BLOCKED-ON human (procedural marker review). `sbt check` backgrounded after 180 s (task b09squ4yb); saved output ends: markTestRun wrote suites=140, passed=1810, skipped=0; `[success] Total time: 341 s (0:05:41.0), completed Nov 20, 2026, 3:44:08 PM`; `[exited with code 0]`. Per project: core 621, entityResolution 464, hadoop 85, sparkAnalytics 259, storage 236, tests 145; rag, root, services 0.
- Marker as read by the orchestrator: written_at_utc=2026-11-20T21:44:08.018013Z, suites 140, tests_passed 1810, failed/errored/skipped 0, production_sources 153.
- Guard-refused (nothing ran): one `git grep` (quote), one `git ls-files -- *.conf` (glob), one `git diff --no-index`.

## Human marker review (2026-11-20, screenshot 3:48 PM CST)
- "Marker reviewed for the run completed November 20 at 3:44:08 PM CST (21:44:08Z). Terminal confirms [success] and exit code 0: 140 suites, 1810 passed, storage 236, zero failures/errors/skips. Git status shows the expected paths. Disk space remains 13 GiB."
- Screenshot: core 621, entityResolution 464, hadoop 85, sparkAnalytics 259, storage 236, tests 145; markTestRun storage production sources 41, test sources 30; `[success] Total time: 341 s (0:05:41.0), completed Nov 20, 2026, 3:44:08 PM`; `[exited with code 0]`.
- Human instructions: keep command-running agents paused until the fixture copy output is in; after the copy, recovery procedure, then WP5 review (explicitly the custom Snappy decoder and the changed gate mapping; passing tests are not security approval) and storage-engineer's DynamoDB table/key design from actual access patterns.
- STATUS (final): WP5 increment COMPLETE as reported; `sbt check` observed passing by the human. Review CR-P7-08 pending.

## Results
- **Neptune opens for real through the production gate** (`LoadVerifier.verify(..., Some(VerificationContext))`, `LoadGate.proceed`), every check run by the verifier, none marked by hand: 459 vertices + 799 edges (1258 writes); rerun writes nothing. Zero rejections on the real fixture; no Spark output defect found.

| Store | Performed and passed | Still open |
|---|---|---|
| neptune-bulk-load | row-contracts, graph-ids, graph-id-parity, evidence-trace | none (gate opens) |
| dynamodb | parquet-rows, row-contracts, evidence-trace | analysis-recompute (new) |
| s3 | parquet-rows, row-contracts, graph-id-parity, evidence-trace | summary-evidence, analysis-recompute (new) |
| s3-vectors | parquet-rows, row-contracts | summary-evidence |

1. Parquet reader `parquet/ParquetRows.scala`; SNAPPY only, decoded by a **new pure-Scala decoder `parquet/SnappyRaw.scala`** (no snappy-java, aircompressor or Hadoop codec on this read path); other codecs → `ParquetReadError.UnsupportedCodec` (UNCOMPRESSED and GZIP test parts refused). Bounds `ParquetReadBounds` from configuration. `load/ParquetRecords.scala` decodes 16 record schemas, exact column names, nulls refused in required fields. Hadoop Configuration creation by parquet-mr not observed.
2. parquet-rows: footer = decoded = declared rows for all 29 parts.
3. row-contracts: core record contracts, schema version, configured window, provenance runId and graph policy, lineage = run chain; core `checkWindowStructures`, `checkCommunities`, `CanonicalGraphValidation.checkStructure`, unique summary input IDs. Settings bound by `configDigest` equality with the run manifest (`run-settings` rejection). Validators needing the grouping `GraphInput` (temporal, path-length, metrics vs edges, checkEdges/Metrics/Components/Bridges/Coreness/Windows/Paths/RunChain) moved to new deferred check `analysis-recompute` (dynamodb, s3) — mapping change for review. No core change.
4. graph-id-parity (`GraphParity`): CSV ↔ graph-nodes/graph-edges records equal; policy carried.
5. evidence-trace (`EvidenceTrace`): COAUTHORED_WITH ↔ CoauthorEdgeV2 (pair, window, count, years), supporting works AUTHORED from both endpoints, each coauthor edge cited once, evidence-path hops resolve; broken pointer refused.
6. summary-evidence: `SummaryEvidenceCheck.bound` binds upstream inputs; wired through `VerificationContext.upstream`; RunSource readers for grouping/curated runs not done (no fixtures yet).
7. Writers: shared ledger protocol `load/LoadLedger.scala` (publication putIfAbsent before data, Conflict by `partManifestSha256`, RunLedger state machine); GraphLoad moved onto it. `load/ObjectLoad.scala` (s3): create-once objects under `S3Layout.runObject` keys, ArtifactRegistry row per object (sha256, byteCount, runId, loadId, sourcePath, recordSchemaVersion); tested in-package as gate mechanics. **DynamoDB KeyValue loader not done**: only 6 of 11 dynamodb roles map to ResearcherMetricCache; analysis-windows, window-structures, window-comparisons, path-length-estimates, temporal-changes have no table/key design in TableSpecs.
8. Production gate: neptune end to end; dynamodb refused (analysis-recompute).
9. T3 memory: estimate only; step 12 holds all decoded records of the run (29 parts).

- Test-settings: the committed dev analysis config rebuilt in test code, bound by digest equality (`e37c32b1…`).
- FILES CREATED (modules/storage/src/): main parquet/SnappyRaw, parquet/ParquetRows, load/ParquetRecords, load/ParquetChecks, load/LoadLedger, load/ObjectLoad; tests RunSettingsFixture, RecordChecksSuite, EvidenceTraceSuite, parquet/ParquetRowsSuite, parquet/SnappyRawSuite, load/ObjectLoadSuite. MODIFIED: load/LoadVerification, load/GraphLoad, load/SummaryEvidence, parquet/ParquetFooters; tests LoadGateSuite, load/GraphLoadSuite. No fixture, build, hook, marker or core file touched.
- RISKS: mapping change (analysis-recompute); neptune opens without `CanonicalGraphValidation.checkTables` (needs curated inputs); SnappyRaw is new decoding code over untrusted bytes; Configuration creation unobserved; ParquetRowsSuite uses parquet-mr's writer (Configuration in tests); AWS limits unverified.
- Follow-ups: human fixture copy; DynamoDB table design for 5 roles; domain-engineer StorageConfig keys later.
