# SE-P7-05 (storage-engineer): WP4 entry conditions A1-A6 and Neptune graph loading B1-B7 — orchestrator-condensed record

The full report is in the session transcript.

- STATUS: BLOCKED-ON human (procedural marker review). `sbt check` (task bnkec93wn) was backgrounded after 180 s; the agent read its saved output: `[success] Total time: 333 s (0:05:33.0), completed Nov 20, 2026, 3:01:05 PM`, exit code 0. Per project: core 621, entityResolution 464, hadoop 85, sparkAnalytics 259, storage 188, tests 145; rag, root, services 0. markTestRun: suites=135, passed=1762, skipped=0.
- Marker as read by the orchestrator: written_at_utc=2026-11-20T21:01:05.891777Z, git_head 0c975573…, worktree dirty, suites 135, tests_passed 1762, failed/errored/skipped 0, production_sources 147.
- Commands: `sbt storage/compile`, `sbt storage/test` iterations (one run 185/188 with three test-side mistakes, fixed; final 188/188), one guard-refused `sbt storage/testQuick`, one `sbt check`. **Disclosed: the first call was the compound form `cd /Users/taufeeqpatel/Desktop/ScholarChain && sbt storage/compile`, which the agent reports the guard allowed.** The documented character gate excludes `&`; the orchestrator has not verified how this passed (possible harness handling of a leading `cd`, or a guard gap). Routed to the human as a factory-engineer question; no hook change made.
- FILES CREATED (modules/storage/src/): main load/NeptuneRows.scala, load/GraphLoad.scala, load/NeptuneBulkLoad.scala, load/SummaryEvidence.scala, model/GraphWrites.scala, fake/InMemoryGraphStore.scala; tests SourceHardeningSuite, RecordingCountersSuite, NeptuneRowsSuite, NeptuneBulkLoadSuite, load/GraphLoadSuite, load/VerifiedReadSuite.
- FILES MODIFIED: main load/LoadVerification, load/CsvRecords, local/LocalRunSource, port/Sources, port/GraphStore, parquet/ParquetFooters, fake/Recording; tests Fixtures, LoadGateSuite, LoadVerificationSuite, ParquetFooterSuite, RuntimeStoresTypeSuite, load/LoadPackageControlSuite. No fixture, build, hook, marker or core file touched.

## Human marker review (2026-11-20, human Terminal, screenshot 3:07 PM CST)
- "Marker reviewed for the run completed November 20 at 3:01:05 PM CST (21:01:05Z). Terminal confirms [success] and exit code 0: 135 suites, 1762 passed, storage 188, zero failures/errors/skips. Git status shows the expected paths. Disk space remains 13 GiB available."
- Screenshot: per-project core 621, entityResolution 464, hadoop 85, sparkAnalytics 259, storage 188, tests 145; markTestRun storage production sources 35, test sources 24; `[success] Total time: 333 s (0:05:33.0), completed Nov 20, 2026, 3:01:05 PM`; `[exited with code 0]`.
- STATUS (final): implementation COMPLETE; `sbt check` observed passing by the human. Code review CR-P7-07 next. Per the human: the in-package end-to-end neptune test that marks pending checks is NOT proof of a complete load.

## Entry conditions
- A1: deferred check `summary-evidence` (s3, s3-vectors) via core `AnalysisValidation.checkSummaryInputs` and `StructureValidation.checkEntitySummaries`; not yet performed.
- A2: `LoadPermit.run` private; permit exposes runId, partManifestSha256, target, load, items, readItem; `VerifiedRun.readVerified` now `private[load]` (closes the `VerificationReport.verified` bypass); readItem refuses other stores' items and `_run-manifest.json`, `_parts.json`, `_SUCCESS`.
- A3: `RunSource.readAtMost(path, maxBytes)`, verified reads bounded by manifest byte count; non-regular files refused before open; fileKey and type equal before and after open; segments never followed through links (SecureDirectoryStream when available; this JVM, Temurin 17 macOS: path checks). Residual: a final segment swapped to a FIFO between check and open could still block (no non-blocking open or fstat in Java).
- A4: recording counters private/read-only; immutable snapshots; type-check tests.
- A5: PAR1 at both ends; footer length ≥1, ≤ size−12, ≤ configured `ParquetFooterBounds.maxFooterBytes`; StackOverflowError → Corrupt; OOM not caught; codec factory refuses every codec. Whether parquet-mr builds a Hadoop Configuration (and reads core-site.xml) is unobserved.
- A6: F4 tag `not-verified`; F5 assertions tightened.

## Graph loading
- B1 row contracts (neptune only): header equals core NeptuneCsv; Int/String only; labels, StableId, profiles, mention-ID formula, canonical pair, evidence kind, COAUTHORED_WITH window/years/count/pointer (N3), runId. `row-contracts` performed for neptune.
- B2 graph IDs: duplicates refused (across files), endpoints exist with allowed labels, IDs verbatim. `graph-ids` performed for neptune. CSV-vs-Parquet parity split into new check `graph-id-parity` (neptune, s3), open. s3 no longer carries `graph-ids` (mapping change for review).
- B3 evidence trace: not implemented; `evidence-trace` open; production gate still refuses neptune.
- B4 GraphLoad: only through the gate; 12 allowlisted templates; publication row putIfAbsent before data, Conflict decided by `partManifestSha256`; RunLedger state machine via putIfVersion; create-once, Conflict never overwrites; provenance runId, loadId, sourcePath, schema version. Tests: rerun skipped with zero graph calls; fresh-ledger rerun 1258 AlreadyPresent; Conflict leaves element unchanged and ledger failed; interruption at 500 resumes 758 Written + 500 AlreadyPresent; two interleaved loaders write each element once; run-2 sha Conflict with zero writes.
- B5: RuntimeStores exposes only a GraphReader facade; writer unreachable.
- B6: `NeptuneBulkLoad.plan` pure; 12 requests, edges depend on vertices; settings validated, no defaults; golden test; nothing sent.
- B7: fixture loaded ID sets equal CSV ID sets (459 vertices, 799 edges, 1258 written); six edge and six vertex labels; listed corruptions refused; injection string stays a value. End-to-end neptune load proven only in package `load` with the open checks marked by the test; production gate refuses.

## Deferred checks per store
- neptune-bulk-load: performed row-contracts, graph-ids; open graph-id-parity, evidence-trace.
- dynamodb: open parquet-rows, row-contracts, evidence-trace.
- s3: open parquet-rows, row-contracts, graph-id-parity, evidence-trace, summary-evidence.
- s3-vectors: open parquet-rows, row-contracts, summary-evidence.

## WP5 must supply
1. Parquet row reader for graph-nodes/graph-edges (GraphNodeV2, GraphEdgeV2) → `graph-id-parity` (ID formulas with the graph policy version from provenance).
2. Decoded CoauthorEdgeV2 from `edges/*.parquet` → `evidence-trace`.
3. summary-inputs, entity-summary-inputs, analysis outputs, plus GraphInput, WorkAssignment, WorkRecord from the grouping and curated runs (`groupingRunId`, `inputRunId`), which are NOT in the analysis run directory or the fixtures → `summary-evidence`. (Orchestrator note: this may need additional fixtures from the archive — a human decision.)
4. parquet-rows and row-contracts for the three Parquet stores, plus the core cross-record checks routed by CR-P7-05.

## Risks
- AWS limits, openCypher templates and bulk-loader fields unverified (aws-docs MCP unavailable).
- FIFO race at the final segment; crafted Thrift footer within the bound can exhaust memory; Configuration creation unobserved.
- `NeptuneLoaderSettings` not bound to core config keys (core StorageConfig has `neptuneEndpoint` only): domain-engineer work order needed.
- Compiler message during test compile: "exception caught when loading module class ActiveStandbyElector$ ... failure to resolve inner class" (hadoop-client-api), non-fatal.
- T5 remains a review item.
