# Phase 06 sparkIT run 05: spark-engineer report (2026-11-17)

- Work order: prompt-sparkit-run-05.md (human, verbatim; includes the note on the first delegation, which stopped before any sbt command). Approval: human-gate.md entry "Spark researcher metrics and run 05 approval (2026-11-17)".
- Reviewed implementation commit: `c2d9a8216b945f2dd57eb587099f0b938ed1d670` (HEAD at run time; tracked tree clean; the only untracked path was prompt-sparkit-run-05.md, before and after). The run manifest records the accepted all-zero codeVersion (`codeVersionStatus=unpinned-sparkit-dev`), so this commit SHA is the binding link between output and code.
- STATUS: PASS. One `sbt sparkIT`, exit 0. No other sbt command, no `sbt clean`, no file edit, archive untouched.

## Run output (sbt, verbatim lines reported by spark-engineer)

```
[info] [sparkit-runner] class scholarchain.spark.DevArchiveGraphFoundationSuite: run=9 failures=0 ignored=0 assumptionFailures=0
[info] sparkIT: PASS: 9 tests in 1 class(es), every expected class ran, no failure, ignored test or assumption failure (exit 0); evidence modules/spark/target/spark-it/attempt-3/junit-result.txt and result.txt; copy them into the phase evidence (sbt clean can remove attempt folders)
[success] Total time: 9 s, completed Nov 17, 2026, 12:15:40 PM
```

```
SP-P6-02 sparkIT: analysisRunId=run:68538385b2711a89267842d793dfc3c8bcf247c82d7331a8dc3249d138d1bccc dir=/Users/taufeeqpatel/Desktop/ScholarChain/modules/spark/target/spark-it/attempt-3/out/run=68538385b2711a89267842d793dfc3c8bcf247c82d7331a8dc3249d138d1bccc edges=259 metrics=176 configDigest=c2f9c94135074c8bf2b282d875b965cc0420ee02fe1e06bc313552af0743e6ce benchmarkPairsDigest=2e1ff1824ba18c90a8b154e9932c51c64565779f62eeb2a519df22ea20bc8be9
SP-P6-02 sparkIT window: WindowInputSummary(TimeWindow(1970,2026),41,112,0,163,182)
SP-P6-02 sparkIT window: WindowInputSummary(TimeWindow(2020,2024),24,64,0,96,107)
SP-P6-02 sparkIT metrics window: MetricsWindowSummary(TimeWindow(1970,2026),112,135,364,0,0,326,364,4)
SP-P6-02 sparkIT metrics window: MetricsWindowSummary(TimeWindow(2020,2024),64,78,214,0,0,192,214,4)
SP-P6-02 sparkIT reproducibility scholarchain.coauthor-edge.v2: rows=259/259 sameSchema=true logicalEqual=true sameBytes=false (bytes: diagnosis only)
SP-P6-02 sparkIT reproducibility scholarchain.researcher-metrics.v2: rows=176/176 sameSchema=true logicalEqual=true sameBytes=false (bytes: diagnosis only)
```

Identity distribution (printed, computed only, checked by checkIdentity): `(1970,2026,resolved,measured,109)`, `(1970,2026,singleton,unknown,1)`, `(1970,2026,singleton-t1-anchored,unknown,2)`, `(2020,2024,resolved,measured,61)`, `(2020,2024,singleton,unknown,1)`, `(2020,2024,singleton-t1-anchored,unknown,2)`. Sums 112 and 64 equal the window record counts.

## Evidence copied from attempt-3 (orchestrator Read)

`modules/spark/target/spark-it/attempt-3/junit-result.txt` (tab-separated):

```
format=scholarchain.sparkit.junit-result.v2
nonce=34f99551ea37b98ebecf141e1e4c393b45dad443b1e2709c28c558331d34d4b8
junit	JUnitCore	/Users/taufeeqpatel/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar	4.13.2
junit	Result	/Users/taufeeqpatel/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar	4.13.2
class	scholarchain.spark.DevArchiveGraphFoundationSuite	run=9	failures=0	ignored=0	assumptionFailures=0
classes=1
complete=34f99551ea37b98ebecf141e1e4c393b45dad443b1e2709c28c558331d34d4b8
```

`attempt-3/result.txt`:

```
status=pass
tests=9
exit=0
expectedClasses=1
expectedJUnit=/Users/taufeeqpatel/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar (junit:junit:4.13.2)
evidence=junit-result.txt
junit.JUnitCore=/Users/taufeeqpatel/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar implementationVersion=4.13.2
junit.Result=/Users/taufeeqpatel/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar implementationVersion=4.13.2
class=scholarchain.spark.DevArchiveGraphFoundationSuite run=9 failures=0 ignored=0 assumptionFailures=0
```

`attempt-3/out/run=68538385…d1bccc/_parts.json`:

```
{"schemaVersion":"scholarchain.part-manifest.v2","runId":"run:68538385b2711a89267842d793dfc3c8bcf247c82d7331a8dc3249d138d1bccc","files":[{"path":"_run-manifest.json","recordSchemaVersion":"scholarchain.run-manifest.v1","format":"jsonl","rowCount":1,"rowCountSource":"lf-count","byteCount":875,"sha256":"d3287aa530f5fca7e61ce01321981b9766497d33bbc3cb5d9deaef432c0c98ad"},{"path":"edges/part-00000.parquet","recordSchemaVersion":"scholarchain.coauthor-edge.v2","format":"parquet","rowCount":259,"rowCountSource":"parquet-footer","byteCount":53966,"sha256":"a8e980c1a5813387cce23aa970049f5971956d73109caa0ef765873a1023038c"},{"path":"metrics/part-00000.parquet","recordSchemaVersion":"scholarchain.researcher-metrics.v2","format":"parquet","rowCount":176,"rowCountSource":"parquet-footer","byteCount":24437,"sha256":"f40d7e6a6a3ed1a57f7fc37830bc5eb465ab9f660b70d945d0d6bca6a7cd8d87"}]}
```

`_run-manifest.json`:

```
{"schemaVersion":"scholarchain.run-manifest.v1","stage":"spark.collaboration-analysis","codeVersion":"0000000000000000000000000000000000000000","configDigest":"c2f9c94135074c8bf2b282d875b965cc0420ee02fe1e06bc313552af0743e6ce","inputSnapshots":[{"source":"dblp","snapshot":"dblp-2026-10-03"},{"source":"openalex","snapshot":"openalex-api-2026-11-05"},{"source":"crossref","snapshot":"crossref-api-2026-11-05"}],"parameters":{"benchmarkPairsDigest":"2e1ff1824ba18c90a8b154e9932c51c64565779f62eeb2a519df22ea20bc8be9","codeVersionStatus":"unpinned-sparkit-dev","decisionRunId":"run:736cb1706210a34f0958e658c061054c4e6b27b80847528db8619da84495b76a","groupingRunId":"run:84dd896fda43977b934008f10955191e78c2b93b80ec57c00f55dbb137dbd7d2","inputRunId":"run:2881724b5c86b561a235836d51946fbd884beed36a14f929b27c278cb5de8baa","outputs":"coauthor-edges,researcher-metrics"},"attempt":1}
```

## Expected versus observed

| Item | Expected | Observed |
|---|---|---|
| JUnit | 9 tests, 1 class, 0/0/0, junit-4.13.2.jar | match (nonce lines equal) |
| Edges 1970-2026 | 41/112/0/163/182 | match (printed) |
| Edges 2020-2024 | 24/64/0/96/107 | match (printed) |
| Total edges | 259 | match (printed; `_parts.json` footer count) |
| Metrics 1970-2026 (records/P/T/U/Wu/C/W/max M) | 112/135/364/0/0/326/364/4 | match (printed) |
| Metrics 2020-2024 | 64/78/214/0/0/192/214/4 | match (printed) |
| Metrics rows | 176 | match (printed; `_parts.json` footer count) |
| Four named researchers | values in sp-p6-02-step-1b-report.md | asserted by passing tests only; not printed |
| Pinned input, decision, grouping runs | the three pins | match (manifest parameters) |
| `outputs` parameter | coauthor-edges,researcher-metrics | match |
| `_SUCCESS` / `_UNVERIFIED` | present / absent | present and empty (spark-engineer Read) / absent (spark-engineer and orchestrator Read) |

Not a defect: the work order asked for a manifest "runId" field. `_run-manifest.json` (run-manifest.v1) has no runId field, as in run 04; the runId is derived from it and recorded in `_parts.json`, in the directory name and in the printed analysisRunId, which all agree. The wording of the order was wrong, not the output.

## Reproducibility scope

- Within one forked JVM, two builds of the same run on the archived inputs gave the same schema and the same complete row multiset with duplicate counts, for edges (259) and metrics (176). Different bytes within the JVM (`sameBytes=false`); cause not diagnosed, and bytes are diagnostic only.
- The schema comparison is as limited as CR-P6-24 m1 says (Parquet read-back makes every field nullable).
- Across JVMs: not tested. The run 03/04 byte difference stays undiagnosed. Run 05 is not comparable row for row with run 04 (different runId through the `outputs` parameter; `provenance.runId` differs in every row). The edges Parquet byteCount is again 53966.

## Integrity limits

- sha256 values not recomputed by any agent (no hash command allowed); covered by the suite's `verifyPublished`.
- Directory not listed; files known from Read only.

## Earlier attempts preserved

spark-engineer read attempt-1/result.txt (status=fail, NoSuchMethodException, exit=1) and attempt-2/result.txt (status=pass, tests=6, exit=0) before and after the run: unchanged. The run created attempt-3. `git status --porcelain` after the run: `?? docs/agent-runs/phase-06/prompt-sparkit-run-05.md` only.
