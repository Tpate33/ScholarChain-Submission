# Phase 06 BE-P6-06: build-engineer report, SparkIT runner repair (2026-11-17)

- Work order: prompt-sparkit-runner-repair.md (human, verbatim). Follows sparkit-run-03.md (runner `NoSuchMethodException: org.junit.runner.Result.getIgnoredCount()`).
- STATUS: COMPLETE. `sbt sparkIT`, `SparkIT/test`, `sbt clean` and `sbt clean check` not run.

## Orchestrator observations

- `git diff --stat`: `build.sbt | 22`, `project/SparkItJUnitRunner.java | 228`, `project/SparkItRuns.scala | 939`, `project/versions.properties | 5`; `4 files changed, 1078 insertions(+), 116 deletions(-)`.
- Untracked: prompt-sparkit-run-03.md, prompt-sparkit-runner-repair.md, sparkit-run-03.md (orchestrator evidence).
- `.claude/hooks/verify-tests-ran.sh --check`: OK.
- Run 03 preserved: Read of `modules/spark/target/spark-it/attempt-1/junit-result.txt` still shows the v1 file with `error	scholarchain.spark.DevArchiveGraphFoundationSuite	java.lang.NoSuchMethodException` and nonce `e583fc6b…3aef`.

## Agent report (key fields, output verbatim)

CHANGES:
1. Lookup fixed to `getIgnoreCount`. All reflective names are resolved once, before any test class loads; a missing one is a setup failure (exit 2, no result file, member named). Names: JUnitCore no-arg constructor and `run(Class[])`; Result `getFailures`, `getRunCount`, `getFailureCount`, `getIgnoreCount`, `getAssumptionFailureCount`; Failure `getTestHeader`, `getException`, `getMessage`. Checked against the human's javap note and the real fixture run (javap not on build-engineer's allowlist).
2. JUnit provenance: the runner records the code source and Implementation-Version of JUnitCore and Result. Before forking, `junitOnClasspath` requires exactly one junit:junit module at the ledger revision, that jar the only entry with `org/junit/runner/{JUnitCore,Result}.class`, and at most one entry with `org/hamcrest/Matcher.class`; unreadable jar refuses. The verdict refuses unless both recorded paths are that jar. `sparkIT` runs the same check before creating an attempt directory. No second JUnit or hamcrest found; no dependency change.
3. Result format `junit-result.v2`: junit lines; `failure` lines (class, test, exception class, message) written before counts; `error` lines naming the stage. Verdict lists failures first. Refuses on any failure line, failure-count mismatch, failure for an unlisted class, any error line, run count 0, any ignored test or assumption failure, missing/unknown/malformed junit line, class set different from expected, any v1 file.
4. New `verifySparkItRunner` in `sbt check` (after verifySparkItGuards, before markTestRun): 5 fixture JUnit classes (pass, fail, ignore, assume, exit) as Java source strings in project/SparkItRuns.scala, compiled with the sbt JVM's javac against the SparkIT classpath into a fresh `target/sparkit-runner-check/check-*/`, deleted after. Same `SparkItRuns.runRunner` as `sparkIT` (same meta-build runner class with fresh nonce, `LocalRuns.runForked`, same strip list, same `verdict`); installed class bytes compared to the meta-build class (sha256 824078eb…38b0); classpath `sparkAnalytics/SparkIT/fullClasspath` plus the fixture directory. No archive read; no write under modules/spark/target/spark-it/.
5. verifySparkItGuards: all 175 cases kept, 3 adjusted to v2 (error stage, malformed-line number 6 instead of 4, helpers pass the JUnit expectation), 22 added: 197.

FILES MODIFIED: build.sbt (verifySparkItRunner key and setting, check step, `sparkIT` receives Attributed classpath and the ledger `junit` value); project/SparkItJUnitRunner.java; project/SparkItRuns.scala; project/versions.properties (new key `junit=4.13.2`, an asserted transitive revision; resolution unchanged).

COMMANDS RUN: `ls project` and `git grep …` (refused by the guard); `git ls-files -- project build.sbt`; `sbt Test/compile` (success); `sbt fmt` (success, own files only); `git status --porcelain` (twice); `sbt check` (once, last); `git diff --stat -- build.sbt project`; `git ls-files --others --exclude-standard -- build.sbt project` (none).

TEST RESULTS (sbt check, verbatim):

```
[info] verifySparkItRunner: SparkIT classpath (sparkAnalytics/SparkIT/fullClasspath, the classpath sparkIT forks with; 166 entries): JUnit: exactly one provider of org.junit.runner.{JUnitCore,Result}: junit:junit:4.13.2 (/Users/taufeeqpatel/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar); hamcrest: org.hamcrest:hamcrest-core:1.3 (/Users/taufeeqpatel/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar); 166 classpath entries scanned; ledger junit=4.13.2
[info] [sparkit-runner] JUnit: org.junit.runner.JUnitCore loaded from /Users/taufeeqpatel/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar (Implementation-Version 4.13.2); org.junit.runner.Result loaded from /Users/taufeeqpatel/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar (Implementation-Version 4.13.2)
[info] verifySparkItRunner: case 'pass' (PassFixture): exit 0; verdict PASS (2 tests)
[info] verifySparkItRunner: case 'fail' (FailFixture): exit 1; verdict FAILED (exit 1): JUnit FAILURE in scholarchain.sparkitcheck.FailFixture: fails(scholarchain.sparkitcheck.FailFixture): java.lang.AssertionError: sparkit-runner-check fixture failure expected:<1> but was:<2>; the runner JVM ended with exit 1; scholarchain.sparkitcheck.FailFixture had 1 failure(s); NOT a pass
[info] verifySparkItRunner: case 'ignore' (IgnoreFixture): exit 1; verdict FAILED (exit 1): the runner JVM ended with exit 1; scholarchain.sparkitcheck.IgnoreFixture had 1 ignored test(s); NOT a pass
[info] verifySparkItRunner: case 'assume' (AssumeFixture): exit 1; verdict FAILED (exit 1): the runner JVM ended with exit 1; scholarchain.sparkitcheck.AssumeFixture had 1 assumption failure(s) (skipped); NOT a pass
[info] verifySparkItRunner: case 'exit' (ExitFixture): exit 0; verdict FAILED (exit 0): the runner wrote no junit-result.txt (a test may have called System.exit, the JVM may have been stopped, or the runner refused before running); NOT a pass
[info] verifySparkItRunner: case 'mixed' (PassFixture, FailFixture): exit 1; verdict FAILED (exit 1): JUnit FAILURE in scholarchain.sparkitcheck.FailFixture: ...; scholarchain.sparkitcheck.FailFixture had 1 failure(s); NOT a pass
[info] verifySparkItRunner: case 'pass result against another expected JUnit jar is refused': as expected
[info] verifySparkItRunner: case 'pass result with its junit Result line removed (required datum missing) is refused': as expected
[info] verifySparkItRunner: PASS: 8 cases gave the expected verdict with the real runner on the SparkIT classpath (JUnit /Users/taufeeqpatel/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar); scratch target/sparkit-runner-check/ cleaned
[info] verifySparkItGuards: PASS: 197 cases gave the expected verdict
[info] markTestRun: wrote /Users/taufeeqpatel/Desktop/ScholarChain/.claude/.last-test-run (git_head=982eecf2f0fb66033a0e3d2f769161b20c4dcd3a, worktree=dirty, suites=94, passed=1360, skipped=0)
[success] Total time: 139 s (0:02:19.0), completed Nov 17, 2026, 9:59:55 AM
```

- SparkIT compile step: up to date, no "compiling" line; `[success] Total time: 0 s, completed Nov 17, 2026, 9:57:25 AM` (output line 18, by step order).
- Passed: core 557, entityResolution 464, hadoop 85, sparkAnalytics 109, tests 145; rag, root, services, storage 0; Failed 0, Errors 0. Version ledger OK (15 entries); verifyHeldoutOutputRoute, verifyHeldoutGuards (23 + 17), verifyErDevRunGuards PASS.

RISKS (as reported): a munit bump that moves junit:junit fails check and sparkIT until the runner is re-checked and the ledger `junit` updated (intended coupling; dependency-updater should know); the assumption case relies on JUnit 4.13 counting assumption failures (observed on 4.13.2); full check output 27.5 MB, mostly pre-existing Hadoop logging; fixtures prove the runner on plain JUnit, not on munit suites or Spark.

NEXT GATE: focused code review; human commit and approval; then one sparkIT run 04 by spark-engineer.
