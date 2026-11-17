# Phase 06 sparkIT run 03: spark-engineer report (2026-11-17)

- Work order: prompt-sparkit-run-03.md (human, verbatim). Approval: human-gate.md entry "SparkIT classpath repair and run 03 approval (2026-11-17)", commit 982eecf2f0fb66033a0e3d2f769161b20c4dcd3a.
- STATUS: FAILED (BLOCKED-ON build-engineer). One `sbt sparkIT`, exit 1, `NOT a pass`. Not repaired, not rerun. The approved run is consumed.

## Agent report (key fields, output verbatim)

Pre-run: `git rev-parse HEAD` -> `982eecf2f0fb66033a0e3d2f769161b20c4dcd3a`; `git status --porcelain` -> `?? docs/agent-runs/phase-06/prompt-sparkit-run-03.md` (the expected work-order record); `git diff --stat HEAD -- build.sbt project modules/spark` -> empty. Archive preflight passed; SparkIT compiled; the suite ran its Spark work.

Failure:

```
[info] [sparkit-runner] class scholarchain.spark.DevArchiveGraphFoundationSuite: could not be run: java.lang.NoSuchMethodException: org.junit.runner.Result.getIgnoredCount()
[info] [sparkit-runner] result file written: 1 class(es); NOT clean
[error] sparkIT: FAILED (exit 1): the runner JVM ended with exit 1; scholarchain.spark.DevArchiveGraphFoundationSuite could not be run (java.lang.NoSuchMethodException); NOT a pass; attempt modules/spark/target/spark-it/attempt-1/ kept; sparkIT deleted nothing
[error] Total time: 5 s, completed Nov 17, 2026, 9:41:47 AM
```

Agent's reading of `project/SparkItJUnitRunner.java` (build-engineer): `JUnitCore.run` at :132 returned; `count(result, "getIgnoredCount")` at :135 (reflective `result.getClass().getMethod`) threw; catch at :155-161 recorded `error`; failures (:137) were never read. Why the method is not found is not diagnosed (JUnit version on the 167-entry classpath not inspected).

`attempt-1/junit-result.txt`:

```
format=scholarchain.sparkit.junit-result.v1
nonce=e583fc6b1cb04a93176d9646f030fffc6d2ba1cad6fed726882382eb72673aef
error	scholarchain.spark.DevArchiveGraphFoundationSuite	java.lang.NoSuchMethodException
classes=1
complete=e583fc6b1cb04a93176d9646f030fffc6d2ba1cad6fed726882382eb72673aef
```

`attempt-1/result.txt`:

```
status=fail
reason=FAILED (exit 1): the runner JVM ended with exit 1; scholarchain.spark.DevArchiveGraphFoundationSuite could not be run (java.lang.NoSuchMethodException); NOT a pass
exit=1
expectedClasses=1
evidence=junit-result.txt
class=scholarchain.spark.DevArchiveGraphFoundationSuite error=java.lang.NoSuchMethodException
```

JUnit summary: none (run, failure, ignored counts and test names not observed).

## Output written by the failed attempt (not verified; not a pass)

Printed by the suite before the runner error (field order assumed works, researchers, unresolved, edges, joint-publication sum, from the work order, not from source):

| Window | Printed | Expected |
|---|---|---|
| 1970-2026 | `WindowInputSummary(TimeWindow(1970,2026),41,112,0,163,182)` | 41, 112, 0, 163, 182 |
| 2020-2024 | `WindowInputSummary(TimeWindow(2020,2024),24,64,0,96,107)` | 24, 64, 0, 96, 107 |

- `edges=259` printed; `analysisRunId=run:3a8f5a72…4ab4`, `configDigest=c2f9c941…e6ce`, `benchmarkPairsDigest=2e1ff182…bea9`. Weight-2 count (expected 30): not observed.
- Published `attempt-1/out/run=3a8f5a7238ead652fa518665dfd2237ccae809e0f604bb5f30c650fc81864ab4/` (Read only): `_parts.json` (`scholarchain.part-manifest.v2`) lists `_run-manifest.json` (rowCount 1, 856 bytes, sha256 `85d24cd8963b4c4bfa4ac9eac5acf46a834aaf49e3ec40923e1115c65ae5f0f7`) and `edges/part-00000.parquet` (`scholarchain.coauthor-edge.v2`, rowCount 259 parquet-footer, 53966 bytes, sha256 `fd28869d0f66b803e206b33c877cf8fce16543e51ff0a9894a28681a946d002a`); `_SUCCESS` present and empty; `_UNVERIFIED` absent; `_run-manifest.json` codeVersion all-zero, `codeVersionStatus=unpinned-sparkit-dev`, `attempt:1`, input runs equal the pins, snapshots dblp-2026-10-03, openalex-api-2026-11-05, crossref-api-2026-11-05.
- Not observed: on-disk sizes and hashes (no listing or hash command allowed), other files under out/, the suite's assertion and re-read verification outcomes.

After the run `git status --porcelain` unchanged. BLOCKED-ON OWNER: build-engineer (runner result reading). Decider for run 04: human.

## Orchestrator note

Correction (2026-11-17): the orchestrator's earlier note here was wrong. It said `getIgnoredCount()` is public JUnit 4.x API and guessed at a different JUnit on the classpath. The human's local `javap` on JUnit 4.13.2 shows the method is `Result.getIgnoreCount()`; the runner looks up a name that does not exist. Routed to build-engineer in prompt-sparkit-runner-repair.md. Run 03 and its `attempt-1/` artifacts are preserved as failed evidence.
