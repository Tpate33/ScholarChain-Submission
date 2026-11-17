# Phase 06 sparkIT run 03: work order (verbatim)

Received by the orchestrator on 2026-11-17 after the human committed 982eecf. The text is the human's own words.

```text
BE-P6-05 and the run 03 approval are committed. Read HEAD and confirm
the build and Spark suite match the commit. Have spark-engineer run
standalone sbt sparkIT once. Record the commit SHA, JUnit result,
observed edge counts, and Parquet integrity evidence. If it fails,
report the exact failure and stop. Hold the next Spark step and AWS.
```

## Orchestrator pre-run observations (2026-11-17)

- Audit interruption: the first `git rev-parse HEAD` was refused UNATTRIBUTED for `docs/agent-runs/phase-06/human-gate.md` (owner HUMAN), the human's gate entry committed in 982eecf (tree clean). Baseline reset; the same command was re-run.
- `git rev-parse HEAD`: `982eecf2f0fb66033a0e3d2f769161b20c4dcd3a` ("Phase 6: fix Spark integration test classpath").
- `git status --porcelain` and `git ls-files --others --exclude-standard`: empty before this file was written.
- `git diff --stat 7244398 HEAD`: build.sbt (30), project/SparkItRuns.scala (102), the five evidence files and human-gate.md (+11); 8 files, 286 insertions, 9 deletions: the CR-P6-22 commit scope.
- `git diff --stat HEAD -- build.sbt project modules/spark`: empty.
- human-gate.md entry "SparkIT classpath repair and run 03 approval (2026-11-17)" approves one standalone `sbt sparkIT` run by spark-engineer with the reviewed build in this commit, superseding the 7244398 retry approval; allowlist scope unchanged.
