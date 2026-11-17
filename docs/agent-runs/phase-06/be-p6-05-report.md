# Phase 06 BE-P6-05: build-engineer report, SparkIT classpath repair (2026-11-17)

- Work order: prompt-sparkit-classpath-repair.md (human, verbatim). Follows sparkit-run-02.md (compile failure, MissingType SparkSession).
- STATUS: COMPLETE. `sbt sparkIT` not run.

## Orchestrator observations

- `git diff --stat`: `build.sbt | 30`, `project/SparkItRuns.scala | 102`, `2 files changed, 123 insertions(+), 9 deletions(-)`. No other tracked change.
- `git ls-files --others --exclude-standard`: prompt-sparkit-classpath-repair.md, prompt-sparkit-run-02.md, sparkit-run-02.md (orchestrator evidence).
- `.claude/hooks/verify-tests-ran.sh --check`: `OK (no uncommitted source change is newer than .claude/.last-test-run)`.
- No audit interruption on the next Bash call.

## Agent report (key fields, command output verbatim)

DIAGNOSIS (from source; sbt inspection tasks are not on build-engineer's allowlist): `SparkIT = config("sparkit").extend(Test)` (build.sbt:125) with `inConfig(SparkIT)(Defaults.testSettings)` (:321); Spark is `sparkSql.value % Provided` (:316). sbt resolves a configuration's managed classpath through `classpathConfiguration`, from `internalConfigurationMap`, which maps only Compile/Test/Runtime to their `*Internal` configs (TestInternal = Test + Optional + Provided); a custom config maps to itself, and the chain sparkit -> test -> runtime -> compile never includes `provided`. The old comment at build.sbt:110 claiming Provided jars were on the classpath was wrong.

CHANGE:
- build.sbt:332 `SparkIT / classpathConfiguration := Configurations.TestInternal`, feeding `SparkIT/dependencyClasspath` (compiler) and `sparkAnalytics / SparkIT / fullClasspath` (forked runner, build.sbt ~:497). No new dependency or version; Spark and Scala versions unchanged.
- `check` gains step `SparkItRuns.CompileCheckStep` = `sparkAnalytics/SparkIT/compile`, after `Test/compile`: compile only, no archive read, no fork, no test run, no attempt directory. SparkIT comments corrected.
- project/SparkItRuns.scala: `CompileCheckStep`, `ClasspathConfigName`, `classpathWiringProblems`, `compileGateProblems`; 5 new self-test cases (classpath setting allowed; counter-test without it refused; check runs compile step and never sparkIT or a run-capable SparkIT route allowed; without compile step refused; with `sparkAnalytics/SparkIT/test` and `sparkIT` added refused). All 170 existing cases kept.
- Unchanged (as reported): preflight, route refusals, pinned curated run, segment and held-out refusals, env stripping, 120 s bound, pass rule.
- Job jar: argued from source only (sbt-assembly packages `Runtime / fullClasspath`, RuntimeInternal, no Provided; change scoped to `SparkIT /`). `packageJobs` not run; jar contents not listed.

COMMANDS RUN: `git ls-files -- build.sbt project`; `sbt check` first attempt failed at project load (`not found: value TestInternal`, exit 1, no task ran, no marker); `git ls-files -- modules/spark/src/it` (one file); `sbt check` (pass, the one full run); `git diff --stat -- build.sbt project`; `git status --porcelain`. Two Bash calls refused by the guard (nothing executed).

TEST RESULTS (verbatim, passing `sbt check`):

```
[success] Total time: 1 s, completed Nov 17, 2026, 9:05:35 AM
[info] compiling 1 Scala source to /Users/taufeeqpatel/Desktop/ScholarChain/modules/spark/target/scala-3.3.6/sparkit-classes ...
[info] done compiling
[success] Total time: 1 s, completed Nov 17, 2026, 9:05:36 AM
[info] verifySparkItGuards: PASS: 175 cases gave the expected verdict
[info] markTestRun: wrote /Users/taufeeqpatel/Desktop/ScholarChain/.claude/.last-test-run (git_head=7244398258894dbe400ae3811cd1c0ca9d9d6322, worktree=dirty, suites=94, passed=1360, skipped=0)
[success] Total time: 142 s (0:02:22.0), completed Nov 17, 2026, 9:08:08 AM
```

- verifyHeldoutGuards PASS (23 launch + 17 clean-tree), verifyErDevRunGuards PASS (128), verifyHeldoutOutputRoute PASS.
- Passed: core 557, entityResolution 464, hadoop 85, sparkAnalytics 109, tests 145; rag, root, services, storage 0; Failed 0, Errors 0.

RISKS (as reported): runner classpath not exercised until the next approved `sbt sparkIT`; src/it now compiled under `-Werror` in check (any warning fails check for every agent); job-jar exclusion of Provided shown by source reading only; sparkit-classes now exist after every check; check about 1 s longer.

NEXT GATE: code-reviewer review of build.sbt and project/SparkItRuns.scala; human approval and commit; then one human-approved standalone `sbt sparkIT` (run 03, spark-engineer).
