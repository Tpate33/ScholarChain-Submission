# Phase 2 agent reports (verbatim)

Routing: orchestrator -> build-engineer (initial build) -> code-reviewer (REQUEST_CHANGES) -> build-engineer (repair-01) -> code-reviewer (APPROVE). Reviews are in `review-report.md`; the repair loop is summarised in `repair-01.md`.

## 1. build-engineer, initial Phase 2 build

```text
STATUS: COMPLETE
OWNER: build-engineer
WORK ORDER: Phase 2: ScholarChain sbt multi-project build and repository skeleton (from scratch)

The build is in place. `sbt Test/compile`, `sbt check` and `sbt packageJobs` all ran and passed. The test count is honestly zero: `sbt check` passed with 0 tests in every project, and `markTestRun` still updated the marker. This is allowed by design: `markTestRun` refuses only when production sources exist but no test passed, and today there are 0 production sources. No test exists yet, so no BLOCKED-ON is raised. The first real test belongs to domain-engineer (a core contract test).

FILES CREATED:
- /Users/taufeeqpatel/Desktop/ScholarChain/build.sbt
- /Users/taufeeqpatel/Desktop/ScholarChain/project/build.properties
- /Users/taufeeqpatel/Desktop/ScholarChain/project/plugins.sbt
- /Users/taufeeqpatel/Desktop/ScholarChain/project/versions.properties (the version ledger)
- /Users/taufeeqpatel/Desktop/ScholarChain/project/Ledger.scala (reads the ledger; rejects floating values; checks the two mirror files)
- /Users/taufeeqpatel/Desktop/ScholarChain/project/TestMarker.scala (`markTestRun` logic)
- /Users/taufeeqpatel/Desktop/ScholarChain/.scalafmt.conf
- Empty `.gitkeep` placeholders (Phase 2 skeleton exemption):
  - modules/{core,entity-resolution,hadoop,spark,storage,rag,services}/src/{main,test}/scala/.gitkeep (14 files)
  - tests/src/test/scala/.gitkeep
  - datasets/.gitkeep, prompts/runtime/.gitkeep, prompts/schemas/.gitkeep, infra/terraform/.gitkeep, scripts/.gitkeep, experiments/.gitkeep, load-tests/.gitkeep, docs/incidents/.gitkeep
  - No placeholder was put in a factory, HUMAN, GENERATED or ORCHESTRATOR path.
- No compose file was created. Nothing local needs a service container yet: storage tests are contract and port tests with no AWS. Adding one now would be speculative.

FILES MODIFIED:
- /Users/taufeeqpatel/Desktop/ScholarChain/.gitignore: no change. After the builds, `git ls-files --others --exclude-standard` shows no build output. All 9 project bases, `project/target/` and `.bsp/` are already covered, including `/tests/target/`.
- /Users/taufeeqpatel/Desktop/ScholarChain/.claude/.last-test-run was written by the `markTestRun` task inside `sbt check`, not by hand.

COMMANDS RUN:
1. `git ls-files`: OK.
2. A grep command was refused by guard-dangerous.sh (character gate). Nothing ran; I used Read instead.
3. `sbt sbtVersion`: FAILED. Compile error in project/TestMarker.scala:25: "value showResults is not a member of object sbt.Tests". I switched to `TestResultLogger.Default` plus an explicit `overall != TestResult.Passed` check. This run did resolve sbt 1.11.7 and all three plugins.
4. `sbt sbtVersion`: success. All 9 projects report 1.11.7.
5. `sbt fmt`: success. "Reformatted 2" and "Reformatted 1" Scala sources (build.sbt, project/Ledger.scala, project/TestMarker.scala).
6. `sbt Test/compile`: success. "[success] Total time: 26 s"; every project resolved its dependencies.
7. `sbt check`: success. Output: "Version ledger OK: 13 pinned entries", then "scalafmt: Checking 2 Scala sources" twice, each step [success], then per project "Passed: Total 0, Failed 0, Errors 0, Passed 0" / "No tests to run for <p> / Test / test" for core, entityResolution, hadoop, rag, root, services, sparkAnalytics, storage and tests. Last lines: "markTestRun: wrote .../.claude/.last-test-run (git_head=d0e0784781d68217abd0fc1ece2516918bb6fae2, worktree=dirty, suites=0, passed=0, skipped=0)" and [success].
8. `sbt packageJobs`: success. Built modules/hadoop/target/scala-3.3.6/scholarchain-hadoop-jobs-0.1.0.jar and modules/spark/target/scala-3.3.6/scholarchain-spark-jobs-0.1.0.jar.
9. `git status --porcelain` and `git ls-files --others --exclude-standard`: only the intended new files appear.
- `sbt dockerLocal` was not run: Docker is not assumed, and the work order says not to.

TEST RESULTS: 0 suites, 0 tests, 0 failed, 0 errors in all 9 projects. The marker contains: scholarchain-test-marker-v1, written_by=sbt markTestRun, git_head=d0e0784…, git_worktree=dirty, suites=0, tests_passed=0, tests_failed=0, tests_errored=0, tests_skipped=0, production_sources=0.

VALIDATION RESULTS:

Version ledger. All 13 entries are in /Users/taufeeqpatel/Desktop/ScholarChain/project/versions.properties. "Resolved" means downloaded during the commands above; "known" means from my knowledge, not checked.

| Entry | Version | Scope / where used | Evidence |
|---|---|---|---|
| sbt | 1.11.7 | build tool; mirrored in project/build.properties | resolved |
| scala | 3.3.6 | all projects | resolved; artifacts built under scala-3.3.6 |
| scalafmt | 3.9.4 | formatter; mirrored in .scalafmt.conf | resolved; it ran in fmt and check |
| plugin.sbt-scalafmt | 2.5.5 | plugin | resolved |
| plugin.sbt-assembly | 2.3.1 | plugin | resolved; built both jars |
| plugin.sbt-native-packager | 1.11.1 | plugin, enabled on services only | resolved |
| upickle | 4.1.0 | core, Compile | resolved |
| munit | 1.1.1 | all projects, Test | resolved |
| slf4j (slf4j-api) | 2.0.17 | services, Compile | resolved |
| logback (logback-classic) | 1.5.18 | services, Runtime | resolved |
| hadoop (hadoop-client-api, hadoop-client-runtime) | 3.4.1 | hadoop: Provided; tests: Test | resolved |
| spark (spark-sql_2.13 via `CrossVersion.for3Use2_13`) | 3.5.5 | sparkAnalytics: Provided; tests: Test | resolved |
| docker.java-runtime-image | eclipse-temurin:17.0.17_10-jre-noble | services Docker base image | not resolved; tag pinned, no digest |

- Why sbt 1.11.x and not 2.x: all three plugins and the ownership rows such as `core/scalafmt` are mature on sbt 1. Plugin availability on sbt 2 is not established. The 2.0.12 launcher started sbt 1.11.7 without trouble.
- Why Scala 3.3.6: it is on the 3.3 LTS line, which keeps a stable binary target and the longest support window.
- Versions in two tool-mandated places: sbt and scalafmt require their versions in project/build.properties and .scalafmt.conf. These copies are checked against the ledger by `verifyVersionLedger`, which runs first in `check`.
- Gap G5: the ledger is one dedicated file that holds only version values, so it is ready to hand to dependency-updater. That agent must also update the two copies above when bumping sbt or scalafmt.

Module graph as built. It matches CLAUDE.md exactly:
- core -> nothing
- entityResolution -> core
- hadoop -> core, entityResolution
- sparkAnalytics -> core
- storage -> core
- rag -> core, storage
- services -> core, rag, storage
- tests -> every module, `% Test` only
- root aggregates all of them and depends on none.

Nothing depends on services or tests. The sbt project ids and base directories are exactly those in the work order; tests is based at tests/.

Aliases:
- `fmt` = scalafmtSbt; scalafmtAll
- `check` = verifyVersionLedger; scalafmtSbtCheck; scalafmtCheckAll; Test/compile; markTestRun
- `packageJobs` = hadoop/assembly; sparkAnalytics/assembly
- `dockerLocal` = services/Docker/publishLocal

How `markTestRun` works:
- It calls `executeTests` in every project itself, so check does not run the tests twice.
- It writes the marker only if every project's result is Passed and there are no failed, errored or canceled tests.
- It also refuses when production sources exist but no test passed.
- The marker is written atomically (temp file, then rename) and records git HEAD, working-tree state and counts.

AWS-free tests: every test JVM is forked with AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY and AWS_SESSION_TOKEN set to empty, AWS_EC2_METADATA_DISABLED=true, and AWS_CONFIG_FILE and AWS_SHARED_CREDENTIALS_FILE pointing at files that don't exist under target/. Contract tests are ordinary Test-scope tests. Anything that needs AWS must go in a later opt-in alias, never in Test.

Environment-variable input: the build reads no environment variables. It only sets the AWS-disabling variables above in forked test JVMs. `markTestRun` runs `git rev-parse --verify HEAD` and `git --no-optional-locks status --porcelain`, which are read-only. The sbt launcher's own JAVA_OPTS and SBT_OPTS are outside the build. There are no secrets, endpoints, buckets or account IDs.

Packaging:
- packageJobs makes fat jars with sbt-assembly that leave out Provided Hadoop and Spark but include the Scala 3 and 2.13 standard libraries and upickle. The merge strategy discards module-info.class. The jars have no main class; use `hadoop jar … <Class>` or `spark-submit --class`.
- dockerLocal uses native-packager: a pinned Temurin 17 JRE base image, non-root user scholarchain with uid and gid 10001, and `-XX:InitialRAMPercentage=50.0 -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError`. It never tags `latest` and sets no registry.

Compiler options: `-deprecation -feature -unchecked -release:17 -Werror` for Scala and `--release 17` for javac.

ASSUMPTIONS:
- Target is Amazon EMR 7.10.0, which I believe ships Hadoop 3.4.1, Spark 3.5.5 and Java 17. This is from knowledge only, not checked against AWS release notes.
- The tests project is needed so that test-engineer's tests under tests/** compile.
- Allowing zero tests while there are no production sources is acceptable for Phase 2.
- The `-release:17` and `-Werror` options are accepted by Scala 3.3.6. This comes from knowledge: no Scala 3 source has been compiled yet, so scalac has never run on them.
- I read "Checking 2 Scala sources" twice as covering all four build files (build.sbt, plugins.sbt, Ledger.scala, TestMarker.scala). I did not confirm per file whether project/plugins.sbt is included in the format check.

RISKS:
1. HIGH, Spark Scala version on EMR: EMR's Spark runtime is built for Scala 2.12 (from knowledge). Spark code compiled with Scala 3 against the _2.13 artifacts needs a Scala 2.13 Spark runtime. On stock EMR Spark the job will probably fail with binary errors. Options:
   - a Scala 2.13 Spark distribution or custom image on EMR;
   - Spark 4.x where EMR offers it;
   - restricting the Spark code to Java and DataFrame APIs and bundling or shading the Scala 2.13 library, which is fragile.
   This needs a spark-engineer and human decision before Phase 6.
2. Scala 3 has no TypeTag, so Spark's typed Dataset encoders do not work. Use DataFrame and Row APIs or explicit encoders.
3. Hadoop is a Java API, so its risk is low. The hadoop fat jar bundles Scala 3 runtime libraries.
4. The build cannot enforce that hadoop uses only "pure normalization and feature functions" from entityResolution. That rule is left to review.
5. Spark 3.5 transitively pulls hadoop-client-api 3.3.4. In tests it is evicted to 3.4.1, which is fine at compile time. Version skew is possible in cross-module tests.
6. `-Werror` may block module owners on warnings. Relaxing it needs a build-engineer work order.
7. services has no main class yet, so a dockerLocal image would have no start script until service-engineer adds one. The base image digest is not pinned.
8. `markTestRun` passes with zero tests while no sources exist, so the marker currently proves nothing about coverage.
9. Allowlist row `sbt tests/Test/scalafmt` is still inactive (G7). The project is named `tests` as that row requires. The `@projects` directive and the SBTTEST slot do not include `tests`, so `sbt tests/test` is not allowed for anyone; `sbt test` and `sbt check` cover it. The B10 aliases (localPipeline, goldSetEval, sparkIT, storageIT, benchmarkQueries) were not created; they were out of scope for this work order.
10. The .gitignore header refers to gitignore-rationale.md, which is not in the repository.
11. scripts/.gitkeep stays untracked until committed. Script provenance rows require every file in scripts/ to be committed, so the human should commit it with this phase.
12. No dependencies exist yet for AWS SDK v2, the HTTP server or the LLM client. Each module owner will need a build-engineer work order to add them.

MANUAL ACTION REQUIRED:
1. Install the scalafmt CLI so the format hook (format-scala.sh) works: `cs install scalafmt` or `brew install coursier/formulas/scalafmt`, then `scalafmt --version`. It reads `version = "3.9.4"` from .scalafmt.conf. Until it is installed, every Scala or sbt write and every Bash call that changes Scala (including `sbt fmt`) gets BLOCKED-FORMAT (exit 2) from post-write.sh or audit-bash-writes.sh. I saw this on every build-file write and after `sbt fmt`. The files are formatted, because `sbt check`'s format check passed, but the hook cannot see that.
2. Optionally pin the base image digest: run `docker buildx imagetools inspect eclipse-temurin:17.0.17_10-jre-noble` on a machine with Docker, then record the digest through dependency-updater or build-engineer.
3. Decide the EMR Spark Scala-runtime question (risk 1).
4. Review and commit the phase. Commits are human-only.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none

NEXT GATE:
- code-reviewer reviews the Phase 2 diff, especially the TestMarker.scala logic and the build.sbt dependency graph.
- The human reviews and commits.
- domain-engineer adds the first core contract test, so that `sbt check` and `markTestRun` reflect real tests (it should then show suites >= 1).
- factory-engineer considers amending the allowlist `@projects` to include `tests` and activating the G7 and B10 rows.
```

## 2. build-engineer, repair-01

```text
STATUS: COMPLETE
OWNER: build-engineer
WORK ORDER: Phase 2 repair-01. Bounded repair of code-reviewer findings F1, F3, F4, F5 and F7 on the uncommitted Phase 2 build (HEAD d0e0784). For F2, record only (ledger comment).

FILES CREATED:
- /Users/taufeeqpatel/Desktop/ScholarChain/project/BuildGuard.scala

FILES MODIFIED:
- /Users/taufeeqpatel/Desktop/ScholarChain/build.sbt
- /Users/taufeeqpatel/Desktop/ScholarChain/project/Ledger.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/project/TestMarker.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/project/plugins.sbt
- /Users/taufeeqpatel/Desktop/ScholarChain/project/versions.properties (comments only; values unchanged at the end, see the F5 demo)
- /Users/taufeeqpatel/Desktop/ScholarChain/.gitignore (header line 2 only)

FINDINGS:
- F1 (major): FIXED, with residual risk stated below.
  - New guard `BuildGuard.foreignSbtFiles` / `enforce` in project/BuildGuard.scala (about lines 34-85).
    - It walks the repository without following symlinks.
    - It skips `.git`, `target`, `.bsp`, `.bloop`, `.metals`, `.idea`, `.terraform`, `node_modules`, `/project` and `/datasets/downloads`.
    - It reports every `*.sbt` file (extension matched case-insensitively) except `/build.sbt`. It also reports `*.sbt` children of symlinked directories.
  - Load-time enforcement is a strict top-level `val buildSourceGuard: Unit = BuildGuard.enforceAtLoad()` at build.sbt:11.
  - Settings-time enforcement is the new `verifyBuildSources` task, now the first step of the `check` alias.
    - It also checks that sbt's `ThisBuild / baseDirectory` equals the launch directory that the load-time scan used.
    - This catches `.sbt` files added while an sbt session is already running.
  - Why the load-time guard holds: the strict val runs when sbt initializes the root build.sbt object during `Load.discoverProjects`, which happens before any subproject `.sbt` is evaluated. It is a JVM exception, not a setting, so a subproject `.sbt` cannot override it.
  - Observed demo (temporary and reverted): I removed `"project"` from `prunedRelative` so that the build's own `project/plugins.sbt` counted as foreign, then ran `sbt compile`. Load aborted with exit 1:
      Caused by: java.lang.RuntimeException: Build policy violation: *.sbt files outside /build.sbt and /project/ are not allowed (...). Remove: project/plugins.sbt. ...
        at BuildGuard$.enforceAtLoad(BuildGuard.scala:85)
        at $aa227605941b19b46c3b$.<init>(build.sbt:11)
        ... sbt.internal.Load$.discoverProjects(Load.scala:1150)
    The revert was confirmed by the later passing `sbt check`. I did not create any `modules/**/*.sbt` file.
- F3 (minor): FIXED.
  - Outcome now includes `testSources` (`(Test / sources).value.size`, wired in build.sbt `testOutcome`).
  - `TestMarker.untestedProjects` (TestMarker.scala:31-40) refuses any project whose production sources > 0 OR test sources > 0 while it has passed == 0. It is enforced at TestMarker.scala:64-68.
  - I chose to include the test-sources case. It refuses all-`.ignore` suites, all-skipped or pending suites, and test trees with helper files but no runnable test. The owner must add at least one passing test.
  - Per-project counts are logged (TestMarker.scala:69-73). The marker format is unchanged (v1).
  - The failure path was not exercised: it needs Scala sources in `modules/**` or `tests/**`, which are outside my territory. The logic is above.
- F4 (minor): FIXED (best-effort; comment reworded at build.sbt:77-83).
  - These are now blanked: AWS_PROFILE, AWS_DEFAULT_PROFILE, AWS_ROLE_ARN, AWS_ROLE_SESSION_NAME, AWS_WEB_IDENTITY_TOKEN_FILE, AWS_CONTAINER_CREDENTIALS_FULL_URI, AWS_CONTAINER_CREDENTIALS_RELATIVE_URI, AWS_CONTAINER_AUTHORIZATION_TOKEN, AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE and AWS_ENDPOINT_URL. Every `AWS_*` name present in sbt's own environment is also set to "" (build.sbt:85-107). That covers any inherited AWS_ENDPOINT_URL_<SERVICE>.
  - sbt cannot unset inherited variables, only override them, so they are set to "".
  - The network is not blocked.
  - The build header (build.sbt:5-6) now documents this as the only environment variable use: names only, values unused.
- F5 (minor): FIXED.
  - project/plugins.sbt now resolves the ledger from the meta-build `baseDirectory` (the repository's `project/`).
  - It applies the same rejection rules (latest, snapshot, exact `release`, the forbidden characters `+[](),*{}<>$?` and whitespace, empty) inside a setting. It declares plugins through the same expansion `addSbtPlugin` uses (`Defaults.sbtPluginExtra` with `pluginCrossBuild / sbtBinaryVersion` and `update / scalaBinaryVersion`).
  - Observed demo (temporary and reverted): with `plugin.sbt-assembly=RELEASE`, `sbt compile` failed during "loading settings for project scholarchain-build from plugins.sbt", before "loading project definition", so before plugin resolution:
      [error] Version ledger .../project/versions.properties has a floating or invalid value: plugin.sbt-assembly=RELEASE
    The value was restored to 2.3.1.
- F7 (nits): FIXED.
  - `Ledger.floating` rejects exact `RELEASE` (any case) and adds `$` and `?` (Ledger.scala:18-24). It does not reject versions that merely contain "release": fixed versions such as `5.3.0.RELEASE` are legitimate.
  - `Ledger.load(buildBase)` resolves from `ThisBuild / baseDirectory` through the new `versionLedger` setting (build.sbt:26). All dependency vals are now `Def.setting`s that read it (build.sbt:36-52). No call reads the JVM working directory any more.
  - New `Ledger.literalVersionProblems` (end of Ledger.scala) is run by `verifyVersionLedger` over build.sbt and project/*.sbt.
    - It finds `% "name" % "x"` / `%% "name" % "x"` patterns, including ones split across lines, after removing `//` comments that are outside strings.
    - Not caught: versions held in a val (`val v = "1.0"; ... % v`), `ModuleID(...)` constructors, and literals in project/*.scala.
    - Block comments are not stripped, so such a comment can only cause a false failure, not a false pass.
  - `.gitignore` header now reads "see the section comments below" (line 2).
  - Marker temp file: I moved it to `target/test-marker/` (TestMarker.scala:98-103) instead of adding an ignore line. A crash leaves no stray file in `.claude/`. `ATOMIC_MOVE` fails closed, with no marker written, if `target/` is on a different filesystem. The rename succeeded in this run.
- F2 (record only): DONE. The ledger comment (versions.properties, "Cluster runtimes" block) no longer says EMR supplies `spark-sql_2.13`. It reads "Runtime target pending human decision (review F2) before Phase 6". build.sbt:48 cross-references it. The Spark dependency is unchanged.

COMMANDS RUN:
- `git status --porcelain`, `git ls-files --others`, `git ls-files --others --exclude-standard`
- `ls -la ...`: refused by guard-dangerous.sh (no-allowlist-entry). Nothing executed.
- `sbt fmt` three times:
  - Run 1 failed: nested-comment compile error in BuildGuard.scala. Fixed.
  - Run 2 failed: string escape error in Ledger.scala. Fixed.
  - Run 3 succeeded: "Reformatted 2 Scala sources", `[success]`.
- `sbt compile` (F1 demo, guard temporarily narrowed): exit 1, the expected load abort shown above.
- `sbt compile` (F5 demo, ledger value temporarily RELEASE): exit 1, the expected meta-build rejection shown above.
- `sbt Test/compile`: `[success] Total time: 1 s`
- `sbt check`: success, output below.
- `sbt packageJobs`: both assembly jars "up to date", `[success]` twice.

TEST RESULTS: `sbt check`, exact output lines:
  [info] Build sources OK: only /build.sbt and /project/ define the build
  [info] Version ledger OK: 13 pinned entries in /Users/taufeeqpatel/Desktop/ScholarChain/project/versions.properties; no literal versions in 2 build file(s)
  [info] scalafmt: Checking 3 Scala sources ...   [info] scalafmt: Checking 2 Scala sources ...
  [info] Passed: Total 0, Failed 0, Errors 0, Passed 0   (x9: core, entityResolution, hadoop, rag, root, services, sparkAnalytics, storage, tests; each "No tests to run")
  [info] markTestRun: <project>: production sources=0, test sources=0, passed=0   (x9)
  [info] markTestRun: wrote /Users/taufeeqpatel/Desktop/ScholarChain/.claude/.last-test-run (git_head=d0e0784781d68217abd0fc1ece2516918bb6fae2, worktree=dirty, suites=0, passed=0, skipped=0)
  [success] Total time: 0 s, completed Oct 29, 2026, 12:25:18 AM
In total: 9 projects, 0 suites, 0 tests. Every step reported `[success]`.

VALIDATION RESULTS:
- Load-time guard path (F1) and meta-build floating rejection (F5) were both observed failing as designed, then reverted.
- scalafmtSbtCheck and scalafmtCheckAll pass.
- packageJobs is unchanged: jars up to date, so the dependency graph and scopes are unchanged by the `Def.setting` refactor.
- Version ledger: 13 entries, values unchanged. Module graph: unchanged.
- Environment-variable surface: only the names of inherited `AWS_*` variables.

ASSUMPTIONS:
- sbt uses the launch directory as the build root, so the load-time scan uses `user.dir`. `verifyBuildSources` asserts this equals `ThisBuild / baseDirectory`.
- Root build.sbt is initialized before subproject `.sbt` files are evaluated. The demo stack trace (`Load.discoverProjects`) is consistent with this, but I did not observe it with a real subproject file.
- `Defaults.sbtPluginExtra` reproduces `addSbtPlugin` in sbt 1.11.7. Plugins resolved and all plugin tasks ran, which is consistent with this.

RISKS:
- F1 residual:
  - A subproject `.sbt` file is still arbitrary Scala. The guard only ensures the load aborts before such a file is evaluated.
  - Other ways to influence the build remain outside this guard:
    - `.sbtopts`, `.jvmopts` or `.sbtrc` at the repository root (UNASSIGNED paths, read by the launcher before the build loads; not scanned).
    - `~/.sbt` global settings and plugins.
    - `SBT_OPTS` / `JAVA_OPTS`.
    - Test code run by `sbt check`, which can write the marker (R1 residual).
  - A process could also create a `.sbt` file between the load-time scan and evaluation, a TOCTOU window of milliseconds.
  - Adding the root launcher files to the scan would be a small follow-up if wanted.
- The ownership-map row change for subproject `*.sbt` paths remains with factory-engineer.
- F3: The failure path has not been exercised by a real test until modules have sources.
- F4: This is best-effort only. The network is not blocked, and non-`AWS_` configuration such as Java system properties in test code is not covered.
- F5: The rejection rules are duplicated in plugins.sbt and Ledger.scala, because `project/*.scala` is not visible to `plugins.sbt`. Both have keep-in-sync comments.
- F2: The Spark artifact (spark-sql_2.13) versus EMR's Scala 2.12 build is undecided. A human must decide before Phase 6.
- The PostToolUse format hook refused every Scala/sbt write: "the scalafmt CLI is not on PATH". The files were formatted with `sbt fmt` instead and verified by `scalafmtSbtCheck` in `sbt check`. Until the CLI is installed, each such write may also leave the audit baseline unrecorded.

MANUAL ACTION REQUIRED:
1. The human installs the scalafmt CLI (e.g. `cs install scalafmt`) so that `format-scala.sh` stops refusing writes.
2. The human decides F2 (the Spark runtime target) before Phase 6.
3. factory-engineer handles the ownership-map row for subproject `*.sbt` paths (F1, constitutional part).
4. Optionally, the human decides whether the guard should also refuse root `.sbtopts`, `.jvmopts` and `.sbtrc`.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer re-review of the repair-01 diff (build.sbt, project/BuildGuard.scala, project/Ledger.scala, project/TestMarker.scala, project/plugins.sbt, project/versions.properties, .gitignore), then human acceptance.
```
