# Phase 06 BE-P6-03: build-engineer report (2026-11-16), verbatim

- Work order (verbatim): docs/agent-runs/phase-06/prompt-be-p6-03-sparkit.md

## Orchestrator observations after the report (2026-11-16)

- My first Bash call afterwards was refused by `audit-bash-writes.sh` as an UNATTRIBUTED inter-call change to `.claude/.last-test-run` (owner GENERATED). This matches the agent's account: its `sbt check` ran as a background task, so the marker update by `markTestRun` landed after the audited call ended. The refusal reset the baseline.
- `git status --porcelain`: ` M build.sbt`, ` M docs/agent-runs/phase-06/review-report.md`, ` M project/LocalRuns.scala`, `?? docs/agent-runs/phase-06/prompt-be-p6-03-sparkit.md`, `?? docs/agent-runs/phase-06/prompt-sp-p6-02-step-0.md`, `?? docs/agent-runs/phase-06/sp-p6-02-step-0-report.md`, `?? modules/spark/src/test/scala/scholarchain/`, `?? project/SparkItRuns.scala`, `?? project/spark-it.properties`.
- `git diff --stat`: build.sbt 104 (+95/-9 approx.), review-report.md +60, project/LocalRuns.scala 4; 3 files changed, 159 insertions(+), 9 deletions(-).
- `.claude/hooks/verify-tests-ran.sh --check`: `verify-tests-ran --check: OK (no uncommitted source change is newer than .claude/.last-test-run)`.
- The `sbt check` output itself was not observed by the orchestrator; its result rests on the agent's report.

## Agent report, verbatim

STATUS: BLOCKED-ON
OWNER: build-engineer
WORK ORDER: BE-P6-03 (Phase 6). Define a bounded, opt-in `sparkIT` root task for local Spark integration tests over the verified dev archive.

The implementation is finished and the final `sbt check` passed (exit 0, observed output below). I return BLOCKED-ON human only because the Stop hook told me to. I ran `sbt check` in the background because it takes more than 180 s (212 s). The shell-write audit records a command when it is launched, so when `markTestRun` updated `.claude/.last-test-run` at the end of that run, `verify-tests-ran.sh` (R1-b) saw a marker change "after the last audited Bash call ... without an audited 'sbt check'". The change came from that `sbt check` and nothing else; no other process ran. The hook's required action is to stop and return BLOCKED-ON human.

FILES CREATED:
- /Users/taufeeqpatel/Desktop/ScholarChain/project/SparkItRuns.scala (the sparkIT task logic and its self-test)
- /Users/taufeeqpatel/Desktop/ScholarChain/project/spark-it.properties (the committed sparkIT configuration)

FILES MODIFIED:
- /Users/taufeeqpatel/Desktop/ScholarChain/build.sbt. Line numbers are approximate; I did not re-read the file after editing.
  - Environment-variable header, about lines 9-30: sparkIT is added to the SCHOLARCHAIN_CFG_* and GIT_* notes, and the `user.home` system property is mentioned.
  - New keys `sparkIT` and `verifySparkItGuards`, plus the `SparkIT` configuration (`config("sparkit").extend(Test)`), about lines 95-116.
  - sparkAnalytics project, about lines 300-335: `.configs(SparkIT)`, `inConfig(SparkIT)(Defaults.testSettings)`, `SparkIT / sourceDirectory := modules/spark/src/it`. `SparkIT / test`, `testOnly`, `testQuick`, `run` and `runMain` now refuse and name `sbt sparkIT`. The existing Spark and Scala setup is unchanged: spark-sql `_2.13` via for3Use2_13, Provided, Test javaOptions java17Opens.
  - Root settings, after verifyErDevRunGuards, about lines 445-485: `sparkIT := Def.taskDyn {...}`, which runs the checks before compiling, then compiles, then forks; and `verifySparkItGuards := SparkItRuns.guardSelfTest(...)`.
  - Alias region at the end of the file: the steps of `check` are now `lazy val checkSteps = Seq(...)`, with `addCommandAlias("check", checkSteps.mkString("; ", " ; ", ""))`. The order is unchanged, with `verifySparkItGuards` added before `markTestRun`.
- /Users/taufeeqpatel/Desktop/ScholarChain/project/LocalRuns.scala: one change near line 725. `private def runForked` became `def runForked`, and a doc line says sparkIT reuses it. Its behaviour is unchanged.

COMMANDS RUN:
- `git rev-parse HEAD`: e59d7539d344eece8120c47c0ac6704773fff518
- `git status --porcelain`: matched the work order, before and after my edits
- `git ls-files -- build.sbt project`, `git ls-files -- project modules/spark`, `git ls-files --others --exclude-standard -- modules/spark`
- `sbt Test/compile`: failed three times on my own errors (two meta-build type-inference errors in SparkItRuns.scala, then `scalafmtConfigSettings` takes an argument list). Fixed; the fourth run passed with `[success] Total time: 6 s`.
- `sbt test`: exit 0. This is the proof that the unscoped `test` key still resolves to `Test / test`. If it had resolved to the refusing `SparkIT / test`, the command would have failed.
- `sbt check`: run once, after my last edit, in the background. Exit 0, `[success] Total time: 212 s (0:03:32.0)`.

TEST RESULTS (from that `sbt check`):
- core: Passed 557, Failed 0
- entityResolution: 464, Failed 0
- hadoop: 85, Failed 0
- sparkAnalytics: 3, Failed 0. This is the step-0 `scholarchain.spark.SparkParquetCompatibilitySuite`, with all 3 tests passing, so it still runs in routine check.
- tests: 145, Failed 0
- rag, root, services, storage: 0 each
- markTestRun: "wrote .claude/.last-test-run (git_head=e59d7539d344eece8120c47c0ac6704773fff518, worktree=dirty, suites=90, passed=1254, skipped=0)"

VALIDATION RESULTS:
- Build sources OK. Version ledger OK (14 pinned entries; no literal versions in 2 build files). scalafmtSbtCheck and scalafmtCheckAll passed.
- verifyHeldoutOutputRoute: PASS (marker found in 0 of 5861 files; the counter-test PASS).
- verifyHeldoutGuards: PASS with 23 synthetic launch cases and 17 temporary-repository clean-tree cases. This is the Phase 5 output, and my changes did not touch any held-out task.
- verifyErDevRunGuards: PASS with 128 cases. Its read-only real-repository report says REFUSE because the tree is dirty; that report is informational, not enforced.
- New verifySparkItGuards: PASS with 91 cases. Read-only report: "<user.home>/ScholarChain-archive/phase-06/dev-run-01 is present (a directory); contents not read, layout not checked".
- `sbt sparkIT` was NOT run, as the work order requires (no active allowlist row).

DESIGN (one item per required point):
1. Opt-in separation. `SparkIT` is a separate sbt configuration with its own source directory, /Users/taufeeqpatel/Desktop/ScholarChain/modules/spark/src/it/scala. It extends Test, so test classes, munit, JUnit 4.13.2 and the Provided Spark jars are on its classpath.
   - `check` compiles only `Test/compile`, and markTestRun reads only `Test / executeTests`.
   - `sbt test` resolves to Test; this was proven by exit 0.
   - `SparkIT / test`, `testOnly`, `testQuick`, `run` and `runMain` refuse. This closes unbounded side routes such as `sparkAnalytics/SparkIT/test`.
   - Only `sparkIT` compiles and runs the configuration. The checks run before `SparkIT / fullClasspath` is built.
   - Routine check does not read the archive. The self-test reads only the committed config and makes one `isDirectory` stat on the root for its report.
   - Deviation: SparkIT sources are not covered by `scalafmtCheckAll`. `ScalafmtPlugin.scalafmtConfigSettings` in 2.5.5 takes an argument list I could not verify, so I left it out. The post-write hook format-scala.sh still formats those files when they are written.
2. Configured archive path. The config file is /Users/taufeeqpatel/Desktop/ScholarChain/project/spark-it.properties.
   - Resolution rule: `archiveRoot = Paths.get(<sbt JVM System.getProperty("user.home")>).resolve(archive.root.home-relative).normalize`. The committed value is `ScholarChain-archive/phase-06/dev-run-01`, so no user name is committed.
   - The file pins the three run paths, relative to the archive root: keys `run.curated`, `run.decisions` and `run.assignments`, with the work order's run IDs. The curated snapshot is `snapshot=dblp-2026-10-03`.
   - Parsing rules: every key exactly once and no unknown key. Each segment may contain only A-Z a-z 0-9 . _ = + @ : -, with no empty, `.` or `..` segment. Each run value must end in `run=<64 lower-case hex>` under its required parent: `modules/hadoop/target/local-pipeline/er-candidates/dev/attempt-<n>/curated/hadoop/snapshot=<s>`, `target/local-runs/dev/er-decisions` or `target/local-runs/dev/er-assignments`.
   - Segment rules, applied case-insensitively to the config values and to the full absolute root: no held-out spelling (LocalRuns.namesHeldout), no `gold` segment, no `target`,`streams` pair, and no `target`,`local-runs`,held-out triple.
   - The root must exist and be a real directory whose real path equals itself (no symbolic link, no differently cased path). It must not be inside the repository, must not contain the repository, and must not be inside any sbt target directory (all projects plus project/target and project/project/target).
   - Exact layout: every directory along the three paths holds exactly the expected children, each a real directory; regular `.DS_Store` files are ignored. Each run has a regular `_SUCCESS` and no `_UNVERIFIED`. The decision and grouping runs also need a regular `_parts.json`. No content is read and no checksum is verified.
   - The task never reads `gold/**` or any held-out path.
3. Configuration for the tests. Only fixed JVM system properties are used:
   - `scholarchain.sparkit.archiveRoot`, `.curatedRun`, `.decisionRun`, `.groupingRun` (absolute archive paths)
   - `.attemptDir`, `.outputDir` (`<attempt>/out`)
   - `.runConfig` (`<attempt>/run-config.properties`, a generated record with the same keys, written create-new)
   Inherited AWS_*, SCHOLARCHAIN_* and GIT_* are removed by LocalRuns.runForked with these strip prefixes, and then awsIsolationEnv is added. The task refuses if any SCHOLARCHAIN_CFG_* name is set.
4. Output layout. Everything goes under /Users/taufeeqpatel/Desktop/ScholarChain/modules/spark/target/spark-it/attempt-<n>/. Here n is one more than every existing `attempt-<n>` name, the directory is made with `Files.createDirectory` (create-once), and no attempt is ever reused or deleted. Each attempt contains:
   - `run-config.properties`
   - `tmp/` (java.io.tmpdir, so Spark's local dir lands here)
   - `out/`
   - `hs_err_pid%p.log`
   - `result.txt` (status, tests, exit)
   The forked JVM's working directory is the attempt directory, so spark-warehouse and derby files land there too. The task refuses when any component of `modules/spark/target/spark-it` is a symbolic link, when a symbolic link sits inside it, or when `sparkAnalytics/target` is not `<base>/modules/spark/target`. The archive is read-only by convention only; the build cannot enforce that.
5. Bound. The forked JVM has a 120 s wall-clock bound (`SparkItRuns.TimeoutSeconds`). On timeout it gets destroy, then a 10 s grace, then destroyForcibly (LocalRuns.runForked). Stdin is closed, and the JVM gets java17Opens as in Test. Why 120 s: dev scale is 374 candidates, 90 works and 286 mentions, and a local SparkSession takes about 5-10 s to start. The step-0 suite took about 5 s of test time. Adding sbt start-up and the SparkIT compile keeps one call well under the 600 s agent tool maximum. Note that the whole `sparkIT` call will likely exceed 180 s, so it should run with a longer tool timeout or in the background.
6. Fixed shape. `sbt sparkIT` takes no arguments. It is not in `check`; the self-test asserts `checkSteps` contains `verifySparkItGuards` and not `sparkIT`. It writes no marker. The self-test also asserts sparkAnalytics depends on `core` only, so there is no hadoop or entityResolution dependency.
7. Zero tests. The task fails (non-zero) with "no integration test is defined in modules/spark/src/it/scala ...; nothing ran, no attempt directory was created, and this is NOT a pass". This check runs after compiling and before any attempt directory is created. Pass requires JUnitCore exit 0 AND the summary `OK (<n> tests)` with n > 0. `OK (0 tests)`, a missing summary, exit 1 (with the "Tests run/Failures" counts), any other exit, and a timeout all fail. I chose non-zero because a zero-test exit 0 could be read as a passing gate. The runner is `org.junit.runner.JUnitCore <SparkIT definedTestNames, sorted>`; munit suites are JUnit 4 runners via the inherited @RunWith. Output goes through the sbt task log, captured only for the summary lines.
8. Self-test `verifySparkItGuards`, 91 cases, all on constants, synthetic text and fixtures in the system temp directory. Every refusal case asserts a substring of its own message, so removing that check makes its case fail. It covers:
   - missing root, root a file, root a symbolic link, symbolically linked ancestor, root inside the repository, root containing the repository, root inside a target directory outside the repository
   - missing or extra run, extra top-level entry, extra snapshot, renamed run, symbolically linked intermediate directory or run, run as a regular file
   - `_UNVERIFIED` present (two cases), missing or directory `_SUCCESS`, missing `_parts.json` (two cases)
   - held-out, gold, target/streams and target/local-runs/heldout segments, in both root and run values
   - config shape, SCHOLARCHAIN_CFG_*, Spark target or output elsewhere or normalizing outside, output-root and target symbolic links, create-once, attempt numbering
   - verdicts, zero tests, JVM options, and preflight end to end
   It never reads the real archive.

Exact command shape for factory-engineer to activate: `sbt sparkIT` (identity spark-engineer; the existing row is currently `inactive:B10-alias-pending`).

ASSUMPTIONS:
- The archived curated run contains `_SUCCESS`, as the work order states. I did not read the archive. If it lacks `_SUCCESS`, sparkIT refuses and names the missing file.
- spark-engineer creates modules/spark/src/it/scala/ with the first integration suite. No placeholder is needed, because sbt accepts a missing source directory. Until then sparkIT reports zero tests and fails.
- An unscoped `test` resolves to Test; this was confirmed by `sbt test` exit 0.

RISKS:
- SparkIT sources are not in scalafmtCheckAll (see point 1); formatting depends on the post-write hook.
- The archive is read-only by convention only; test code could write there.
- The 120 s bound has not been measured against real integration suites yet. Adjust it by work order once spark-engineer reports timings.
- Logging of LocalRuns.runForked is shared, but the change made it public with no behaviour change. verifyHeldoutGuards (23+17) and verifyErDevRunGuards (128) still pass with unchanged counts.
- R1-b workflow risk: when a run of `sbt check` takes more than 180 s, it must run in the background, and that triggers the marker-change Stop refusal. factory-engineer may want to address this.

MANUAL ACTION REQUIRED:
- The human reviews `.claude/.last-test-run`. It was written by the background `sbt check` run b9nbfgocr at 15:08:40, exit 0, with the evidence above. The next audited Bash call resets the baseline. A fresh `sbt check` run in the foreground re-records the marker if wanted.
- factory-engineer activates the spark-engineer row `sbt sparkIT`.
- The human commits.

BLOCKED-ON OWNER: human
BLOCKED-ON EVIDENCE: Stop hook output, verbatim: "BLOCKED by .claude/hooks/verify-tests-ran.sh: completion refused for 'build-engineer'. The test marker .claude/.last-test-run changed after the last audited Bash call of this session, without an audited 'sbt check' (for example a delayed background process started by test code). Possible test-marker forgery: the marker cannot be trusted as evidence that tests passed. Do: stop. Return STATUS: BLOCKED-ON with BLOCKED-ON OWNER: human ..." Cause: the only writer was my background `sbt check` (task b9nbfgocr). Its final lines were "markTestRun: wrote /Users/taufeeqpatel/Desktop/ScholarChain/.claude/.last-test-run (git_head=e59d7539..., worktree=dirty, suites=90, passed=1254, skipped=0)" and "[success] Total time: 212 s ... [exited with code 0]".
NEXT GATE: human review of the marker, then code-reviewer review of build.sbt, project/SparkItRuns.scala, project/spark-it.properties and the LocalRuns.scala visibility change; then factory-engineer activates `sbt sparkIT`; then spark-engineer writes the first suite under modules/spark/src/it/scala.
