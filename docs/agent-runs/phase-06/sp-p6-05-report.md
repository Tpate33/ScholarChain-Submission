# Phase 06 SP-P6-05: spark-engineer report, CR-P6-26 fixes and start-up checks (2026-11-17)

- Work order: CR-P6-26 n1-n6, u1, u2 and a YARN single-attempt start-up check (routed under prompt-emr-preparation.md). HEAD bd9c0a5; uncommitted, modules/spark/** only.
- STATUS: COMPLETE. OWNER: spark-engineer. BLOCKED-ON: none.

## Changes (key fields)

- n1 `StorePath.parse` refuses two or more trailing slashes; segments split with `split("/", -1)`.
- n2 new `StorePath.mayOverlap`, used by JobArguments and `AnalysisRun.structureProblems`: same scheme; `hdfs` authority ignored; other schemes compare host case-insensitively without port; prefix in either direction overlaps (may falsely refuse two HDFS clusters; never misses an overlap).
- n3 `StorePath.globProblem` refuses `, * ? [ ] { }` and backslash in every location flag; `CoauthorEdgeJob.countedTeams` refuses such part locations before `textFile`.
- n4 load-manifest refusal message corrected (`loadManifestsNotPublished`).
- n5 CollaborationJob Scaladoc: the job always uses protocol B (including `file:`). Corrected wording for sp-p6-04-report.md:10 (recorded here; committed evidence not rewritten): "Atomicity: whole-run atomic (one rename, protocol A) only for the java.nio store used by AnalysisRun.runCollaboration and sparkIT; the CollaborationJob entry point always uses protocol B through HadoopRunStore, including `file:` locations via RawLocalFileSystem (check-then-create, link-following open), so on every scheme a job run is atomic per file or object only and run completion is `_SUCCESS`."
- n6 test: pre-existing `.spark-<hex>/` refuses publication (s3sim and `file:`); no code change.
- u1 `StartupChecks.markerRetentionProblems`: for every `s3a:` location the effective `fs.s3a.bucket.<bucket>.directory.marker.retention`, else `fs.s3a.directory.marker.retention`, must be exactly `keep` (trimmed); checked before any data I/O; exit 1.
- YARN: `StartupChecks.attemptProblems`: master `yarn*` requires `spark.yarn.maxAppAttempts` exactly `1`; exit 1.
- u2 port-only authority (`s3a://:80/x`) refused. u3 unchanged (fails closed).
- Files created: main job/StartupChecks.scala; test job/StartupChecksSuite.scala. Modified main: store/StorePath.scala, job/JobArguments.scala, job/CollaborationJob.scala, analysis/AnalysisRun.scala, graph/CoauthorEdgeJob.scala; test: store/StorePathSuite.scala, job/CollaborationJobSuite.scala, output/CommitPublisherSuite.scala, graph/CoauthorEdgeJobSuite.scala.

## Required EMR Spark settings (for build-engineer and infra-engineer)

- `--conf spark.yarn.maxAppAttempts=1`
- `--conf spark.hadoop.fs.s3a.directory.marker.retention=keep` (or core-site); any per-bucket override must also be `keep`.

## Commands and results

- `sbt sparkAnalytics/test`: `[info] Passed: Total 249, Failed 0, Errors 0, Passed 249`.
- `sbt check` (once; moved to background after 180 s; exit 0, `[success] Total time: 286 s`):
  ```
  [info] Passed: Total 621, Failed 0, Errors 0, Passed 621
  [info] Passed: Total 464, Failed 0, Errors 0, Passed 464
  [info] Passed: Total 85, Failed 0, Errors 0, Passed 85
  [info] Passed: Total 249, Failed 0, Errors 0, Passed 249
  [info] Passed: Total 145, Failed 0, Errors 0, Passed 145
  [info] markTestRun: wrote /Users/taufeeqpatel/Desktop/ScholarChain/.claude/.last-test-run (git_head=bd9c0a5a195a86a6b8df9a39de3eabc50db124f7, worktree=dirty, suites=111, passed=1564, skipped=0)
  ```
- sparkIT not run: run identity, outputs and publication order unchanged (refusal paths and messages only); sparkIT run 08 remains the evidence. End-to-end job tests (file:, s3sim) still give the baseline run ID and logically equal datasets.
- Other: two `git ls-files` and one `git diff --stat`.

## Orchestrator verification

- Next orchestrator Bash call refused by `audit-bash-writes.sh --pre` as UNATTRIBUTED, changed path `.claude/.last-test-run` (GENERATED) only (the backgrounded sbt check); re-run once. Marker read: `written_by=sbt markTestRun`, `written_at_utc=2026-11-18T03:05:28.837682Z`, `git_head=bd9c0a5…`, `git_worktree=dirty`, `suites=111`, `tests_passed=1564`, `tests_failed=0`, `tests_errored=0`, `tests_skipped=0`, `production_sources=112`; matches the markTestRun line. `verify-tests-ran.sh --check`: OK.
- Earlier, during the run, one orchestrator Bash call was refused as UNATTRIBUTED for `modules/spark/src/main/scala/scholarchain/spark/analysis/AnalysisRun.scala` (spark-engineer territory, covered by this order); recorded in prompt-aws-account-signin.md.

## Risks (as reported)

HDFS overlap rule may falsely refuse; marker check is configuration-only (S3A runtime behaviour and the IAM deny first observable on EMR); operator retries must use attempt+1; `keep` required even for read-only buckets (fail closed); `--code-version` operator-asserted. Assumption: per-bucket override semantics and that `spark.hadoop.*` plus cluster core-site reach `hadoopConfiguration` (Hadoop knowledge, unverified on EMR). Test JVM reports Hadoop 3.3.4 while the ledger pins 3.4.1.

NEXT GATE: code-reviewer focused review (combined with the packaging and infrastructure diffs), then human commit.
