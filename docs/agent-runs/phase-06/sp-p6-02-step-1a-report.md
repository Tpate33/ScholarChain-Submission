# Phase 06 SP-P6-02 step 1A: spark-engineer report (2026-11-16), verbatim

- Work order: docs/agent-runs/phase-06/prompt-sp-p6-02-step-1.md (orchestrator dispatch text summarised there; human text verbatim).
- HEAD at work: 5e176eb7b5641ea79b9b610933184bd85052be49
- Human decisions on this report: docs/agent-runs/phase-06/prompt-sp-p6-02-step-1a-acceptance.md (marker accepted; leftover export/CoauthorEdgeRows.scala removed by the human; benchmark pairs, windows, sc-graph.v1 and the unpinned all-zero codeVersion accepted for this dev attempt only).

## Agent report, verbatim

STATUS: COMPLETE
OWNER: spark-engineer
WORK ORDER: SP-P6-02 step 1, part A (Phase 6): verified archive input, graph foundation (graph input, CoauthorEdgeV2 per window, benchmark pair list file and loader, staged Parquet publication with part-manifest v2), and the first archive-backed sparkIT suite. Code and routine tests only. `sbt sparkIT` was NOT run.

The code is written, the 41 routine Spark tests pass, and `sbt check` passed (exit 0). The archive-backed suite has not been compiled in its own SparkIT configuration yet; its body has (see RISKS 1).

FILES CREATED (all under /Users/taufeeqpatel/Desktop/ScholarChain/modules/spark/):

Main code:
- src/main/scala/scholarchain/spark/input/RunTree.scala: CheckStep (the ordered checks), Refusal, ReaderLimits, the no-follow tree walk, streamed file observation, strict UTF-8 reads.
- src/main/scala/scholarchain/spark/input/VerifiedArchiveReader.scala: ArchiveRuns (the configured run paths), VerifiedRun, VerifiedInput, and the reader.
- src/main/scala/scholarchain/spark/graph/CoauthorEdgeJob.scala: EdgeContext, WindowInputSummary, and the distributed counted-team and edge jobs. The Scaladoc documents partitioning, the 3 shuffles, persistence and what reaches the driver.
- src/main/scala/scholarchain/spark/output/CoauthorEdgeRows.scala: the Parquet row layout of coauthor-edge.v2, plus RecordSchemas (label to StructType, shape comparison that ignores nullability).
- src/main/scala/scholarchain/spark/output/RunPublisher.scala: staged, verified, atomically renamed publication, and verifyPublished.
- src/main/scala/scholarchain/spark/analysis/BenchmarkPairs.scala: loader and validation of the benchmark pair list.
- src/main/scala/scholarchain/spark/analysis/AnalysisRun.scala: AnalysisJobConfig, the analysis run manifest, lineage, and runEdges.
- src/main/scala/scholarchain/spark/export/CoauthorEdgeRows.scala: a comment-only leftover; the human should delete it (see MANUAL ACTION).

Committed dev inputs:
- src/test/resources/scholarchain/spark/dev-run-01/benchmark-pairs-dev.v1.json (scholarchain.benchmark-pairs.v1, canonical JSON plus LF, provenance.runId run:84dd896fda43977b934008f10955191e78c2b93b80ec57c00f55dbb137dbd7d2).
- src/test/resources/scholarchain/spark/dev-run-01/analysis-dev.conf (config.v2; windows 1970-2026 and 2020-2024; graph.policyVersion sc-graph.v1; parquet export).

Tests:
- src/test/scala/scholarchain/spark/testkit/LocalSpark.scala, RunFixtures.scala, DevArchiveGraphFoundationChecks.scala (abstract: the integration suite's body).
- src/test/scala/scholarchain/spark/input/VerifiedArchiveReaderSuite.scala (23 tests).
- src/test/scala/scholarchain/spark/graph/CoauthorEdgeJobSuite.scala (7).
- src/test/scala/scholarchain/spark/output/RunPublisherSuite.scala (5).
- src/test/scala/scholarchain/spark/analysis/DevInputsSuite.scala (4).
- src/it/scala/scholarchain/spark/DevArchiveGraphFoundationSuite.scala: the only concrete SparkIT class; it extends DevArchiveGraphFoundationChecks.

FILES MODIFIED:
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/spark/src/test/scala/scholarchain/spark/SparkParquetCompatibilitySuite.scala
  - CR-P6-15 N2: the Scaladoc now says "Loopback listeners only; no outbound network."
  - Optional N1 item: the synthetic part directory is renamed from metrics/ to synthetic/.

EXACT CHECK ORDER IMPLEMENTED (VerifiedArchiveReader.read): each stage runs only after every run has passed the previous one, and no file content is read before stage 1 passes for all three runs.

1. Layout of the curated, decision and grouping runs, from names and file types only:
   - (1) run-name: the directory is `run=<64 lowercase hex>`. If not, nothing else is checked for that run.
   - Then the directory must be a real directory, not a link (reported under the tree step).
   - (2) unverified-marker: no `_UNVERIFIED`.
   - (3) staging-entry: no `.staging-*` or `.unverified-*` entry beside the run or anywhere inside it.
   - (4) success-marker: a regular `_SUCCESS`.
   - (5) tree: a no-follow walk, bounded by maxTreeEntries and maxTreeDepth, finds only regular files and directories (links and special files are refused).
2. part-manifest:
   - Every file is observed (bytes, LF count, sha256), and `_parts.json` is decoded with PartManifests.decode.
   - Decision run: PartManifests.verify, and at least one match-decision.v2 part must be listed.
   - Grouping run: AnalysisValidation.checkGroupingRunParts.
   - Curated run: verified only if `_parts.json` exists. The archived Hadoop curated run has none.
3. run-manifest: `_run-manifest.json` decodes, `RunId.of` equals the directory's run ID, and PartManifestV2.checkRunManifest passes.
4. run-chain:
   - The grouping manifest's decisionRunId is the decision run, and its inputRunId is the curated run.
   - The decision manifest's inputRunId is the curated run.
   - The input snapshot sets are equal (grouping and decision, decision and curated).
5. driver-bound, then decode:
   - The declared rows of the teams, work-assignment, researcher-assignment and match-decision parts must be at most maxDriverValidationRows.
   - Then every row is decoded with Json.decode.
6. graph-input (AnalysisValidation.checkGraphInput), then identity (AnalysisValidation.checkIdentity with no metrics yet, which checks that every assignment and decision is bound to the right run).

Every refusal has its own feature test that fails without its check. Three order tests show that a later check never runs while an earlier one refuses.

Graph and edges:
- Teams come only from the grouping run's PublicationTeam parts, joined with the work assignments whose graphEligibility is counted.
- Edges use identified researcherIds only; unresolved mentions are counted separately per window. Pairs are canonical, self-pairs are impossible, and each pair is aggregated once per window with per-year counts and every supporting work.
- Each edge is built by CoauthorEdgeV2.build with lineage and provenance. Provenance is scholarchain-derived, sourceRecordId = edgeId, the DBLP snapshot of the grouping run, the analysis run ID, policy sc-graph.v1, and no model.

Output:
- Only under outputRoot: `.staging-<hex>` (create-once) and the scratch directory `.spark-<hex>` (deleted afterwards). Parts are moved in as `edges/part-NNNNN.parquet`.
- N1: each part's StructType is checked against its record schema label twice, on the frame before writing and on each part after writing.
- `_parts.json` is part-manifest v2: parquet parts with rows from the footer, bytes and sha256. `_run-manifest.json` is one LF-terminated row. `_SUCCESS` is written last.
- The staged directory is verified, then atomically renamed to `run=<hex>`, then re-verified from its bytes (verifyPublished).
- A run directory is never overwritten, and on any refusal the staging and scratch directories this call created are removed.

EXPECTED ARCHIVE-BACKED ASSERTIONS for the reviewer (DevArchiveGraphFoundationChecks; DEV, unevaluated). The per-window numbers are my own hand count from the 41 archived teams:

- Reader:
  - Run IDs are grouping run:84dd896f…d7d2, decision run:736cb170…b76a and curated run:2881724b…8baa.
  - The curated run has no part manifest.
  - The grouping part manifest declares 292 researcher-assignment, 90 work-assignment, 143 authorship and 41 team rows.
  - 374 decisions decode.
- Graph input: 41 counted works, 41 counted teams, 0 unresolved team mentions, and every team has a year.

Per-window counts the suite asserts:

| Window | Counted works | Identified researchers | Unresolved mentions | Edges | Sum of jointPublicationCount |
|---|---|---|---|---|---|
| 1970-2026 | 41 | 112 | 0 | 163 | 182 |
| 2020-2024 | 24 | 64 | 0 | 96 | 107 |

- Edge totals: 259 edges in total, 30 of them with weight 2 (19 + 11), and the maximum weight is 2.
- One independent cross-check: the hadoop dev-run test in this same `sbt check` printed "teams=41 coauthorPairs=182", which matches the 182 joint publications in the 1970-2026 window.
- AnalysisValidation.checkEdges is empty, and checkRunChain (edges only, with the committed pair list) is empty.
- The written run is a child of outputDir named `run=<hex>`, verifyPublished passes, it is part-manifest v2, the coauthor-edge rows sum to 259, and every parquet part's row count comes from the footer.
- The benchmark list is benchmark-pairs-dev.v1 with 5 pairs, provenance.runId is the dev grouping run, and the manifest's benchmarkPairsDigest equals the list digest.
- There are no ignore, assume or flaky markers. A missing `scholarchain.sparkit.*` property fails the suite.

COMMANDS RUN:
1. `git ls-files -- modules/spark modules/core project docs/agent-runs/phase-06`: OK.
2. A `git grep` was refused by the guard (shell-syntax-gate); nothing ran.
3. `git show --stat HEAD` showed 5e176eb.
4. `git diff --stat e8c6ecc HEAD -- build.sbt project` printed nothing, so the build is unchanged since e8c6ecc.
5. `sbt sparkAnalytics/compile`: success, run twice.
6. `sbt sparkAnalytics/Test/compile`: failed once on my own fixture error (a local value shadowed a method), then succeeded.
7. `sbt sparkAnalytics/testOnly …` and `sbt sparkAnalytics/SparkIT/compile` were both refused by the guard (no-allowlist-entry); nothing ran.
8. `sbt sparkAnalytics/test`: 25 passed. After adding suites it failed to compile once on my own error (a private Spark API), then passed 41, Failed 0.
9. `git status --porcelain`, `git status --short`, `git ls-files --others --exclude-standard -- modules/spark`.
10. `sbt check`: run once, last, from a foreground call with a 600000 ms timeout. The tool moved it to the background after 180 s (I did not choose this). Exit 0, "[success] Total time: 364 s (0:06:04.0)".

TEST RESULTS (from that `sbt check`, as markTestRun reported them):

| Project | Production sources | Test sources | Passed |
|---|---|---|---|
| core | 25 | 28 | 557 |
| entityResolution | 40 | 41 | 464 |
| hadoop | 18 | 13 | 85 |
| sparkAnalytics | 8 | 8 | 41 |
| tests | 0 | 18 | 145 |
| rag, root, services, storage | 0 | 0 | 0 each |

Every project reported Failed 0, Errors 0. The final line, verbatim: "markTestRun: wrote /Users/taufeeqpatel/Desktop/ScholarChain/.claude/.last-test-run (git_head=5e176eb7b5641ea79b9b610933184bd85052be49, worktree=dirty, suites=94, passed=1292, skipped=0)".

The sparkAnalytics 41 are: SparkParquetCompatibilitySuite 3, VerifiedArchiveReaderSuite 23, CoauthorEdgeJobSuite 7, RunPublisherSuite 5, DevInputsSuite 4.

VALIDATION RESULTS:
- `sbt check` succeeded. That includes its format steps (sparkAnalytics/SparkIT/scalafmtCheck among them) and the build guard self-tests. I did not pull their individual lines from the 228k-line log, only the overall [success].
- Fixture edges equal the hand-computed edges and AnalysisValidation.checkEdges's recomputation exactly. Counter-tests (a dropped edge, a changed supporting work) are detected, and checkRunChain is empty.
- Values printed by the tests:
  - dev analysis configDigest c2f9c94135074c8bf2b282d875b965cc0420ee02fe1e06bc313552af0743e6ce
  - benchmarkPairsDigest 2e1ff1824ba18c90a8b154e9932c51c64565779f62eeb2a519df22ea20bc8be9
- Stage DAG, per edge run:
  - textFile and decode for teams and works; the decoded rows are re-counted against the manifest.
  - Shuffle 1: join with the counted work IDs.
  - Shuffle 2: aggregateByKey on (window, a, b).
  - Shuffle 3: sortBy for a stable output order.
  - The edge results are persisted MEMORY_AND_DISK.
  - Per-window reduceByKey summaries are the only data collected to the driver.
  - The Parquet write goes to scratch.
- Partitions are 1 in the tests (local[1]). No Spark metrics were collected.
- Integrity violations in the fixtures are 0 except the deliberate refusals.
- Nothing archive-backed has been observed; that waits for the sparkIT run.

ASSUMPTIONS:
- I chose the five benchmark pairs myself; the human decided only that the list is bound to the dev grouping run. They are: a pair with no connection (one has no coauthor), a direct weight-2 pair, two 2-hop pairs, and one sc-ID weight-2 pair present only in 1970-2026.
- I chose the dev windows (1970-2026 and 2020-2024), graph.policyVersion sc-graph.v1, label-propagation/cross-community-degree labels, and the path bounds 4/3/3. They live in the committed config. The ER and AWS keys are dummies because the loader requires every key.
- SparkIT extends Test, so test classes and test resources are on its classpath (build-engineer stated this). The suite relies on it.
- The decision run manifest's curated-input parameter is named `inputRunId`, as observed in the archive. It is an ER contract and is not a core constant.
- Analysis stage token "spark.collaboration-analysis".

RISKS:
1. The concrete SparkIT file has never been compiled in its own SparkIT configuration; `sbt sparkAnalytics/SparkIT/compile` is not an allowed command for me. Its entire body is in the Test tree and compiles in `sbt check`. The thin IT subclass is untested until `sbt sparkIT`.
2. The per-window counts (112/163/182 and 64/96/107) are my own hand count. A miscount would fail the first run honestly; it is not a code defect.
3. codeVersion is unpinned. The sparkIT fork receives no commit, so the IT records "0"*40 plus the manifest parameter codeVersionStatus=unpinned-sparkit-dev. A build-engineer item should pass git_head as a fixed property.
4. The Hadoop curated run publishes no part manifest, so its bytes are not verified; only its run manifest is.
5. The core validators run on the driver. The reader refuses (fail-closed) when the input exceeds the configured driver bound, so sample or EMR scale needs distributed validators (CR-P6-13 nit 5, domain-engineer).
6. The archive is read-only by convention. A file changed between verification and the Spark read is detected only through the row re-count.
7. Because the tool moved `sbt check` to the background, the R1-b Stop refusal on the test marker (seen in BE-P6-03) may occur. The marker was written by that check, at 18:00:54 with exit 0.
8. Spark logged a non-loopback executor host name, but the RPC and block manager bound to 127.0.0.1 (as in step 0).

MANUAL ACTION REQUIRED:
- Human: delete /Users/taufeeqpatel/Desktop/ScholarChain/modules/spark/src/main/scala/scholarchain/spark/export/CoauthorEdgeRows.scala. It is comment-only: `export` is a Scala 3 keyword, the code moved to output/, and I have no delete command. Then review and commit.
- If a Stop refusal for the marker appears, review .claude/.last-test-run against the check output quoted above.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none

NEXT GATE: code-reviewer reviews modules/spark/** (especially DevArchiveGraphFoundationChecks and the expected counts), the human deletes the leftover file, and build.sbt and project/ are confirmed unchanged against e8c6ecc. Then spark-engineer runs `sbt sparkIT` exactly once and records result.txt, junit-result.txt and the out/run=<hex> evidence. Optional build-engineer follow-up: pass git_head to the sparkIT fork as a fixed property.
