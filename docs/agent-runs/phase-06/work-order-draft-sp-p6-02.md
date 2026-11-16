# Phase 06 work order draft SP-P6-02 (spark-engineer): first Spark implementation (revised after CR-P6-14)

Status: DRAFT for human approval. Not dispatched. Revised by the orchestrator on 2026-11-16 after CR-P6-14 (APPROVE of DE-P6-02 repair 01) and the human's Spark decisions in prompt-de-p6-02-repair-01.md. Supersedes the earlier draft in this file.

## Step 0: smallest build compatibility check (local Spark on Scala 3 with Spark `_2.13` artifacts)

What the build already provides (build.sbt at HEAD 6be1f1d, read by the orchestrator):
- `sparkAnalytics` (modules/spark) is Scala 3 (`ThisBuild / scalaVersion` from the ledger), depends on `core` only, and declares `spark-sql` from the ledger with `.cross(CrossVersion.for3Use2_13)`, scope `Provided` (on the test classpath in sbt).
- Tests fork (`Test / fork := true`) with the Java 17 `--add-opens` list and AWS isolation; the project is aggregated by `root`, so its tests run inside `sbt check` and are counted by `markTestRun`.
- No GraphFrames dependency exists. None is needed: label propagation, cross-community degree and k-core peeling are implementable on Spark SQL/RDD alone.
- `sbt sparkIT` stays unneeded for now (its allowlist row stays inactive).

Proposed check (spark-engineer, modules/spark/src/test only; no build change): one munit suite that, in a forked test JVM,
1. starts `SparkSession` with `master local[1]`, UI disabled, shuffle partitions 1, warehouse and temp dirs under the JVM temp dir;
2. builds a DataFrame from a few core records serialised with uPickle as JSON strings (Encoders.STRING), reads them back, and decodes them into core types (proves Scala 3 core types cross the Spark `_2.13` boundary without Scala 2 encoder derivation);
3. writes one tiny Parquet file, reads it back, takes the row count from the Parquet footer or the writer, and verifies the file with `PartManifestV2` by bytes and sha256 (proves the integrity contract on binary output);
4. stops the session.
Pass criterion: the suite runs and passes inside `sbt check` with exact counts reported. If compilation or classpath resolution fails (for example a scala-library or Jackson conflict), spark-engineer stops and reports BLOCKED-ON build-engineer with the exact error; build-engineer then owns the fix. EMR runtime compatibility (EMR's Spark and Scala build, Java version, `Provided` jars) is a separate later gate.

## Step 1: first Spark implementation (after step 0 passes)

Owner: spark-engineer. Scope: modules/spark/** only. No AWS, no EMR, no store loaders (Neptune CSV belongs to storage-engineer), no runtime API.

Input: the archived, verified dev run `/Users/taufeeqpatel/ScholarChain-archive/phase-06/dev-run-01/` (grouping run `run:84dd896f…d7d2`, decision run `run:736cb170…b76a`, curated input `run:2881724b…8baa`). The input root comes from configuration, never hardcoded. Do not re-run or re-pin entity resolution.

Order of checks before any computation (CR-P6-14 nit 3): verify every part against `_parts.json` (`PartManifests.verify`, `checkGroupingRunParts`); refuse non `run=<64 hex>` names, `_UNVERIFIED`, missing `_SUCCESS`; check the grouping manifest's decisionRunId and inputRunId chain; then `checkGraphInput` and `checkIdentity`.

Jobs, producing DE-P6-02 v2 records with lineage and provenance:
1. Graph input from the grouping run's teams only (not Hadoop TeamJob), counted works only.
2. Coauthor edges v2 per configured window.
3. ResearcherMetrics v2 (P, C, T, A, M, W; unresolved occurrences in separate fields; identity confidence with explicit unknown, from the archived decision run).
4. Components; communities by documented label propagation (parameters recorded); bridge measure = cross-community degree; coreness by k-core peeling. Sampled Brandes deferred.
5. Stored shortest paths only for the explicit, versioned benchmark-pair list (`scholarchain.benchmark-pairs.v1`), first K per pair and window in unsigned-UTF-8 node-sequence order, matching `AnalysisValidation.selectShortestPaths` exactly.
6. Temporal window records; semantic-summary input records.
7. Export in `analysis.exportFormat` with a part-manifest v2 (Parquet by bytes and sha256, rows from footer or writer) and `_run-manifest.json` carrying the analysis config digest and `benchmarkPairsDigest`; staging, verification, then atomic rename.

Tests: a bounded fixture integration test inside `sbt check` (synthetic fixtures), plus a local run over the archived dev bytes whose output passes `AnalysisValidation`. Report stage summaries, partitions, record counts, run ID, output path and timings; label everything dev and unevaluated.

Constraints: Phase 5 accepted; prior graph-scope and metric decisions binding; never read held-out labels or evaluation output; no Grep under gold/ or target/; no `sbt clean`; sbt check once; one focused review.

## Open before step 1

- Benchmark pair list for the archived run: location and owner. A committed file under `modules/spark/**` (spark-engineer) needs no matrix amendment; any path outside an owned tree needs a factory-engineer amendment.
- How the local run over the archived bytes is executed: inside a test (reads a path outside the repository from configuration) or a new fixed-shape root task by build-engineer with its own allowlist row.
