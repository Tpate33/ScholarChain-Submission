# Phase 06 sparkIT run 04: work order (verbatim)

Received by the orchestrator on 2026-11-17 after the human committed f834c60. The text is the human's own words.

```text
BE-P6-06 and the run 04 approval are committed. Read HEAD and confirm
the build and Spark suite match the commit. Have spark-engineer run
standalone sbt sparkIT once. Keep attempt-1 intact. Record the commit
SHA, JUnit verdict and counts, observed edge counts, and Parquet
integrity evidence. If it fails, report the exact failure and stop.
Hold the next Spark step and AWS work.
```

## Orchestrator pre-run observations (2026-11-17)

- Audit interruption: the first `git rev-parse HEAD` was refused UNATTRIBUTED for `docs/agent-runs/phase-06/human-gate.md` (owner HUMAN), the human's gate entry committed in f834c60 (tree clean). Baseline reset; the same command was re-run.
- `git rev-parse HEAD`: `f834c603b0db1965914cf779b1f6bdffa7a426ab` ("Phase 6: fix Spark integration runner verdict reporting").
- `git status --porcelain` and `git ls-files --others --exclude-standard`: empty before this file was written.
- `git diff --stat 982eecf HEAD`: the 4 build files (build.sbt 22, SparkItJUnitRunner.java 228, SparkItRuns.scala 939, versions.properties 5), the 5 evidence files and human-gate.md (+12); 10 files, 1274 insertions, 116 deletions: the CR-P6-23 commit scope.
- `git diff --stat HEAD -- build.sbt project modules/spark`: empty.
- human-gate.md entry "SparkIT runner repair and run 04 approval (2026-11-17)" approves one standalone `sbt sparkIT` run by spark-engineer with the reviewed build in this commit, superseding the run 03 approval at 982eecf; F1, F2 deferred; JUnit ledger entry accepted as a validation value; G5 open.
- Run 03 preserved: `modules/spark/target/spark-it/attempt-1/result.txt` still reads `status=fail` with `java.lang.NoSuchMethodException`.
