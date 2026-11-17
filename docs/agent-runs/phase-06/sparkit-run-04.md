# Phase 06 sparkIT run 04: spark-engineer report (2026-11-17)

- Work order: prompt-sparkit-run-04.md (human, verbatim). Approval: human-gate.md entry "SparkIT runner repair and run 04 approval (2026-11-17)".
- Reviewed implementation commit: `f834c603b0db1965914cf779b1f6bdffa7a426ab` (HEAD at run time; build.sbt, project and modules/spark clean against it). The run manifest itself records the accepted all-zero codeVersion (`codeVersionStatus=unpinned-sparkit-dev`), so this commit SHA is the binding link between output and code.
- STATUS: PASS. One `sbt sparkIT`, exit 0: `[success] Total time: 5 s, completed Nov 17, 2026, 10:17:40 AM`. No other sbt command.

## Run output (sbt, verbatim lines reported by spark-engineer)

```
[sparkit-runner] class scholarchain.spark.DevArchiveGraphFoundationSuite: run=6 failures=0 ignored=0 assumptionFailures=0
sparkIT: PASS: 6 tests in 1 class(es), every expected class ran, no failure, ignored test or assumption failure (exit 0); evidence modules/spark/target/spark-it/attempt-2/junit-result.txt and result.txt; copy them into the phase evidence (sbt clean can remove attempt folders)
[success] Total time: 5 s, completed Nov 17, 2026, 10:17:40 AM
```

Preflight: archive root `/Users/taufeeqpatel/ScholarChain-archive/phase-06/dev-run-01` holding exactly the pinned curated run=2881724b…, decision run=736cb170…, grouping run=84dd896f…, none with `_UNVERIFIED`. JUnit provenance: exactly one provider, junit:junit:4.13.2.

## Evidence copied from attempt-2 (orchestrator Read)

`modules/spark/target/spark-it/attempt-2/junit-result.txt` (tab-separated):

```
format=scholarchain.sparkit.junit-result.v2
nonce=029032f609f8512ab9825ae3854c65759f9d5929e19ecf1589375d2b6442238e
junit	JUnitCore	/Users/taufeeqpatel/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar	4.13.2
junit	Result	/Users/taufeeqpatel/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar	4.13.2
class	scholarchain.spark.DevArchiveGraphFoundationSuite	run=6	failures=0	ignored=0	assumptionFailures=0
classes=1
complete=029032f609f8512ab9825ae3854c65759f9d5929e19ecf1589375d2b6442238e
```

`modules/spark/target/spark-it/attempt-2/result.txt`:

```
status=pass
tests=6
exit=0
expectedClasses=1
expectedJUnit=/Users/taufeeqpatel/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar (junit:junit:4.13.2)
evidence=junit-result.txt
junit.JUnitCore=/Users/taufeeqpatel/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar implementationVersion=4.13.2
junit.Result=/Users/taufeeqpatel/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar implementationVersion=4.13.2
class=scholarchain.spark.DevArchiveGraphFoundationSuite run=6 failures=0 ignored=0 assumptionFailures=0
```

`attempt-2/out/run=3a8f5a7238ead652fa518665dfd2237ccae809e0f604bb5f30c650fc81864ab4/_parts.json`:

```
{"schemaVersion":"scholarchain.part-manifest.v2","runId":"run:3a8f5a7238ead652fa518665dfd2237ccae809e0f604bb5f30c650fc81864ab4","files":[{"path":"_run-manifest.json","recordSchemaVersion":"scholarchain.run-manifest.v1","format":"jsonl","rowCount":1,"rowCountSource":"lf-count","byteCount":856,"sha256":"85d24cd8963b4c4bfa4ac9eac5acf46a834aaf49e3ec40923e1115c65ae5f0f7"},{"path":"edges/part-00000.parquet","recordSchemaVersion":"scholarchain.coauthor-edge.v2","format":"parquet","rowCount":259,"rowCountSource":"parquet-footer","byteCount":53966,"sha256":"46860cabfbd20742dd6445ca0072184ddfb32b1656b23198eb3310c0e481d807"}]}
```

## Tests (per-class counts only in v2; names from DevArchiveGraphFoundationChecks.scala:130, 143, 151, 165, 191, 218; individual outcomes inferred from run=6 failures=0)

1. the verified reader accepts the archived dev chain with the pinned run IDs
2. graph input: 41 counted works, 41 teams, no unresolved team mention
3. per-window counts of the dev graph (hand-derived)
4. the produced edges pass AnalysisValidation (checkEdges and checkRunChain)
5. the written run lies under outputDir and re-verifies against its part manifest
6. the benchmark pair list names identified researchers of the dev grouping run

## Window counts

Field order from CoauthorEdgeJob.scala:37-44 (filled at :224-231): window, countedWorks, identifiedResearchers, unresolvedMentionOccurrences, edges, jointPublications. Printed:

```
WindowInputSummary(TimeWindow(1970,2026),41,112,0,163,182)
WindowInputSummary(TimeWindow(2020,2024),24,64,0,96,107)
```

| Window | Works | Researchers | Unresolved | Edges | Joint-publication sum | Expected |
|---|---|---|---|---|---|---|
| 1970-2026 | 41 | 112 | 0 | 163 | 182 | match (printed; asserted in test 3) |
| 2020-2024 | 24 | 64 | 0 | 96 | 107 | match (printed; asserted in test 3) |

- Total edges 259: printed (`edges=259`), `_parts.json` rowCount 259 (parquet footer), asserted in tests 3 and 5.
- Weight-2 edges 30 and max weight 2: asserted only by passing test 3 (`== 19 + 11`); not printed.

## Output integrity

- `_SUCCESS` present and empty; `_UNVERIFIED` absent (Read: "File does not exist").
- Test 5 re-verified the published run with `RunPublisher.verifyPublished` (v2 part manifest, 259 rows, footer row counts, checksums); test 4 ran checkEdges/checkRunChain with no violations; both passed (from failures=0).
- `_run-manifest.json`: runId `run:3a8f5a72…4ab4`, stage `spark.collaboration-analysis`, codeVersion all-zero (`unpinned-sparkit-dev`), configDigest `c2f9c94135074c8bf2b282d875b965cc0420ee02fe1e06bc313552af0743e6ce`, benchmarkPairsDigest `2e1ff1824ba18c90a8b154e9932c51c64565779f62eeb2a519df22ea20bc8be9`, inputRunId/decisionRunId/groupingRunId equal the pins, snapshots dblp-2026-10-03, openalex-api-2026-11-05, crossref-api-2026-11-05.
- sha256 values not recomputed by any agent (no hash command allowed); covered by the suite's verifyPublished.
- Directory not listed (no listing tool); files known from Read only.

## attempt-1 (run 03) preserved

spark-engineer read attempt-1/junit-result.txt and result.txt before and after: unchanged (v1, `NoSuchMethodException`, `status=fail`); nothing written there.

## Orchestrator note: Parquet bytes differ between attempts

Run 03 (attempt-1, unverified) and run 04 (attempt-2) have the same runId `run:3a8f5a72…4ab4`, the same Parquet size (53966 bytes) and row count (259), and the same `_run-manifest.json` sha256 (`85d24cd8…f0f7`), but different Parquet sha256 (`fd28869d…d002a` vs `46860cab…d807`). The analysis runId therefore does not bind the output bytes, and the Parquet file is not byte-reproducible across runs on the same inputs (cause not diagnosed: row order within the file, or writer metadata). Raised to the human; owner spark-engineer.

## Human decision and reproducibility status (2026-11-17)

- The human accepted sparkIT run 04 at f834c60 as passing evidence for SP-P6-02 step 1A (prompt-sp-p6-02-step-1b.md). Run 03 stays recorded as failed (sparkit-run-03.md). This report, the JUnit result and the integrity manifest above are preserved here and go into the next reviewed Spark commit.
- Recorded: runs 03 and 04 produced different Parquet bytes for `edges/part-00000.parquet`; **row-level equivalence has not been established.**
- Comparison route: none available. No permitted read-only route decodes the saved Parquet files: the Read tool cannot decode Parquet, no Parquet reader or hash command is on any allowlist row, and no existing sbt task compares two published runs. Writing one would be new code, not an existing route, and sparkIT was not rerun for this.
- Carried forward as a logical reproducibility test (schema plus complete row multiset with duplicate counts) in the next Spark work order, SP-P6-02 step 1B. Until it passes, no later step may assume that the run-04 edge output is reproducible row for row.
