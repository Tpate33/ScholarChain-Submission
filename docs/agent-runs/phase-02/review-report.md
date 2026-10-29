# Phase 2 code-reviewer reports (verbatim)

| Round | Verdict |
|---|---|
| Review 1 (initial build) | REQUEST_CHANGES (F1, F2 major; F3-F6 minor; F7 nits) |
| Review 2 (after repair-01) | **APPROVE** (N1, N2 nits; F2, F6 accepted deferrals pending human decision) |

## Review 1

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 2 review gate. Adversarial read-only review of the uncommitted Phase 2 build by build-engineer (untracked relative to HEAD d0e0784): build.sbt, project/build.properties, project/plugins.sbt, project/versions.properties, project/Ledger.scala, project/TestMarker.scala, .scalafmt.conf, and the .gitkeep skeleton. Also assess the Spark 3.5.5 _2.13 Provided vs. EMR 7.x Scala 2.12 risk.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none. I have no Bash. I used only Read, Grep, and Glob on build.sbt, project/*, .scalafmt.conf, .gitignore, README.md (Phase 2 section, lines 986-1049), .claude/agents/build-engineer.md, .claude/hooks/lib-ownership.sh, .claude/hooks/guard-dangerous.sh, and a glob of **/.gitkeep.
TEST RESULTS: none observed. I could not run sbt, so I have not verified build-engineer's claims that `sbt Test/compile` and `sbt check` pass.
VALIDATION RESULTS:
- Checked against the files and found conforming:
  - Projects: core, entityResolution, hadoop, sparkAnalytics, storage, rag, services, tests, plus an aggregating root.
  - The dependsOn edges match CLAUDE.md exactly (build.sbt:101, 107, 118, 129, 135, 141). The graph is acyclic. Nothing depends on services or tests. tests uses only `% Test` edges (build.sbt:168-176).
  - Scala 3.3.6 comes from the ledger for ThisBuild.
  - uPickle is the only JSON library (core) and MUnit the only test framework, at Test scope (build.sbt:56).
  - Hadoop and Spark are Provided (112, 123). logback is Runtime (146).
  - scalafmt uses `runner.dialect = scala3`, and its version mirror is checked by verifyVersionLedger.
  - Aliases fmt, check, packageJobs, and dockerLocal exist (214-220). The check chain is ledger, sbt-format check, format check, Test/compile, markTestRun.
  - Every version is read from project/versions.properties. Ledger.scala rejects latest, SNAPSHOT, ranges, `+`, and wildcards.
  - Docker: the image tag is pinned, the user is non-root (uid/gid 10001), and memory is set as percentages (Initial 50, Max 75).
  - No secrets, endpoints, or account IDs are present.
  - All 23 .gitkeep files sit in module, test-engineer, dataset, rag, infra, experiment, or incident territory. None is in a factory-engineer, HUMAN, GENERATED, or ORCHESTRATOR path.
  - .gitignore covers target/ for each project, .bsp, .metals, .terraform, tfstate, .env*, and the marker.
- TestMarker.scala review:
  - A compile error makes Test/executeTests fail, so markTestRun fails and no marker is written.
  - Any project whose `overall` is not Passed throws before the write (TestMarker.scala:28-32). So do failed, errored, or canceled counts above 0 (42-45), and production sources with zero passing tests (46-49).
  - The alias chain stops at the first failing command.
  - markTestRun is defined only on root. It collects testOutcome from every project, including tests and root (build.sbt:205-209).
  - The write is atomic (temp file plus ATOMIC_MOVE).
  - guard-dangerous.sh SBT_TASKS (line 66) does not include markTestRun, so agents can trigger the marker write only through `sbt check` or `sbt clean check`.
  - The weaknesses I found are below (F1, F3, F4).
ASSUMPTIONS:
- The behaviors of sbt, the Scala 3 stdlib, and EMR cited in F1 and F2 come from my own knowledge and are labeled as such. Repository files cannot confirm them.
- I assumed .gitignore was unchanged since Phase 1 (it is tracked and does not appear in git status).
RISKS:
- rag -> storage gives rag concrete AWS clients on its classpath once storage contains them. The build cannot enforce CLAUDE.md's "ports/interfaces only" rule without a storage-api split, which would be a constitutional graph change.
- The same applies to hadoop -> entityResolution ("pure functions only").
MANUAL ACTION REQUIRED: The human architect must decide the Spark runtime target (F2) before Phase 6. The human must also ratify any ownership-map amendment for subproject *.sbt files (F1).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Route F1 to factory-engineer (ownership row) and build-engineer (detection inside check). Route F2 to the human architect for decision, then to build-engineer, with infra-engineer for the EMR runtime target. Route the minor findings to build-engineer. Then re-review, and observe `sbt check` output with exact counts.
VERDICT: REQUEST_CHANGES
FINDINGS:

[build-engineer / factory-engineer]

F1 (major). Module owners can rewrite the definition of done through subproject `*.sbt` files.
- Where: build.sbt:68-72 and 205-209. .claude/hooks/lib-ownership.sh:43 (`modules/core/*) echo "domain-engineer"`), and the same pattern for the other modules and tests/.
- Verified from files: the ownership map assigns `modules/core/build.sbt` (and `modules/<m>/*.sbt`, `tests/*.sbt`) to the module owner or test-engineer. No row reserves `*.sbt` for build-engineer.
- Knowledge-based: sbt loads `*.sbt` files in every subproject base directory as settings for that project.
- Failure input: domain-engineer writes `modules/core/build.sbt` containing `Test / executeTests := Tests.Output(TestResult.Passed, Map.empty, Nil)` or `Test / testOptions += Tests.Filter(_ => false)`.
- Result: core reports Passed with zero events. The marker is still written whenever any other project passes one test, because the `production > 0 && passed == 0` check (TestMarker.scala:46) is global, not per project.
- Wider impact: the same route can add Global or ThisBuild settings, such as redefining `addCommandAlias("check", ...)` or the scope of dependencies. Build policy owned by build-engineer becomes writable by six other agents. It defeats markTestRun and the ownership boundary, and enforce-ownership.sh allows it.
- Owners: factory-engineer (ownership row: `modules/**/*.sbt` and `tests/**/*.sbt` -> build-engineer or UNASSIGNED; human ratification) and build-engineer (make `check` fail when any `*.sbt` exists outside the repo root and project/).

F2 (major, decision owner: human architect; implementation: build-engineer; EMR release target: infra-engineer). The Provided Spark artifact does not match the stated EMR runtime.
- Where: build.sbt:31-32 and 123. project/versions.properties:25-27 ("EMR-provided runtimes (Provided scope; Amazon EMR 7.10.0 line)").
- Verified from files:
  - sparkAnalytics compiles against `spark-sql_2.13` 3.5.5, marked Provided, and the ledger says EMR 7.10.0 supplies it.
  - jobAssembly (build.sbt:76-87) does not exclude Scala, so the spark-jobs fat jar will carry scala3-library_3 and the Scala 2.13 scala-library.
  - The repo contains no documented decision on runtime Scala binary compatibility. README.md:1012 requires avoiding "unsupported Scala binary assumptions".
- Knowledge-based, not verifiable from the repo: Amazon EMR on EC2 7.x ships Spark 3.5 built for Scala 2.12, with scala-library 2.12 on the driver and executor classpath. Scala 3 can only consume 2.13 artifacts (its stdlib is the 2.13 library), so a 2.12 runtime cannot be compiled against from Scala 3.
- Failure input: `spark-submit` of the Phase 6 spark-jobs jar on an EMR 7.10 cluster.
- Expected impact: NoSuchMethodError or NoClassDefFoundError at link time. Examples: 2.12-vs-2.13 `Seq` signatures in Dataset and functions methods, and `scala.collection.immutable.ArraySeq` used by Scala 3 varargs, which does not exist in 2.12. Java/DataFrame-style calls do not avoid this, because the Scala 3 code itself links against the 2.13 stdlib while Spark's 2.12 scala-library loads first.
- Why it matters now: Provided scope asserts that "the runtime supplies this exact artifact", and on the knowledge above that assertion is false. Phase 2 local tests will pass while the EMR target is broken.
- Options for the human: an EMR on EKS or EMR Serverless custom image with a Scala 2.13 Spark distribution; a Spark 2.13 install on EMR on EC2 through Terraform-defined bootstrap; or a spec-level change. Phase 2 compile is not broken, but the gap should be decided and recorded (in the ledger comment and the report) before Phase 6.

[build-engineer]

F3 (minor). The zero-test allowance is global, so modules with code and no tests still get a marker.
- Where: TestMarker.scala:40 and 46.
- Failure input: storage has 10 production sources and zero tests, and core has 1 passing test.
- Result: production=11, passed=1, and the marker is written. The per-project `productionSources` field is collected but never checked per project.
- Second case: a tests-only project where every test is `.ignore`, with zero production sources, also writes a marker even though no test ran.
- Impact: "only after tests pass" holds vacuously, and the marker can certify untested modules.
- Fix: check per project, e.g. "production sources > 0 implies passed > 0 in that project". The production-source count itself looks complete: it counts Compile/sources, including managed sources and sbt's default sourcesInBase files, so compiled sources in unusual directories are still counted. Sources the build never compiles are not production code.

F4 (minor). The AWS-free test environment is weaker than the comment claims.
- Where: build.sbt:57-67.
- Failure input: the developer shell exports AWS_PROFILE, AWS_ROLE_ARN with AWS_WEB_IDENTITY_TOKEN_FILE, AWS_CONTAINER_CREDENTIALS_FULL_URI or AWS_CONTAINER_CREDENTIALS_RELATIVE_URI, or AWS_ENDPOINT_URL*. These are inherited by the forked test JVM and not cleared.
- Impact: the web-identity and container credential providers stay reachable. Network access is not blocked either, so test code can still make anonymous or static-credential AWS calls. The comment "cannot reach AWS" overstates the control. Clear those variables too, and reword the comment as best-effort.

F5 (minor). The plugin loader does not enforce pinning.
- Where: project/plugins.sbt:4-14.
- Detail: the inline loader does not reject floating values. Ledger.scala's validation runs only when build.sbt loads, after the meta-build has already resolved and loaded the plugins.
- Failure input: `plugin.sbt-assembly=latest.integration`.
- Result: the unpinned plugin code is downloaded and loaded before the build fails. It does fail closed eventually.

F6 (minor). The Docker base image is pinned by mutable tag, not digest.
- Where: project/versions.properties:30 (`eclipse-temurin:17.0.17_10-jre-noble`).
- Detail: the tag can be re-pushed upstream, so builds are not reproducible. The file itself says "digest pending".

F7 (nit). Several small gaps.
- Ledger.scala:16-21 does not reject Maven `RELEASE` or `release`.
- No check prevents a literal version string from being added to build.sbt.
- Ledger.scala:14 resolves the ledger relative to the JVM working directory instead of the build base.
- .gitignore:2 references `gitignore-rationale.md`, which does not exist (pre-existing file, outside Phase 2).
- A killed markTestRun can leave `.claude/.last-test-run.*.tmp`, which is not gitignored, in UNASSIGNED territory.

UNVERIFIED CONCERNS:
- In a forked test JVM, test code that calls System.exit(0) mid-run may leave later suites unreported while executeTests still returns Passed. I could not confirm sbt ForkTests behavior without running it. The compensating control is review of test code.
- MUnit `.only` filtering: other tests are excluded rather than counted as skipped, so one `.only` test could make the marker certify a mostly unrun suite.
- Forked Spark tests run with the project base directory as working directory and may create `spark-warehouse/`, `metastore_db/`, or `derby.log` under modules/spark or tests/. Those paths are not gitignored, so audit-bash-writes.sh would report git-visible changes.
- I have not verified that the pinned versions exist at those exact coordinates (sbt 1.11.7, sbt-scalafmt 2.5.5, Temurin 17.0.17_10). Observed `sbt check` output is the evidence needed.
- The guard's `@projects` list (guard-dangerous.sh:139) omits `tests`, so test-engineer cannot run `sbt tests/test`. This is a factory-engineer item under gap G7, outside this diff.
```

## Review 2 (after repair-01)

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 2 re-review after repair-01. Strictly read-only. Check each of F1-F7 as FIXED, PARTIAL, or NOT FIXED, look for regressions, and return APPROVE or REQUEST_CHANGES.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none. I have no Bash tool. I inspected files with Read and Glob only: /Users/taufeeqpatel/Desktop/ScholarChain/build.sbt, project/BuildGuard.scala, project/TestMarker.scala, project/Ledger.scala, project/plugins.sbt, project/versions.properties, project/build.properties, .scalafmt.conf, .gitignore, plus repository-wide globs for *.sbt (any case), .sbtopts, .jvmopts, .sbtrc and modules/*/project/**.
TEST RESULTS: Not observed. I cannot run sbt. The `sbt check` output build-engineer reported (Build sources OK; Version ledger OK, 13 entries; scalafmt checks; 9 projects, Total 0; marker written) is consistent with the files, but I did not see it myself.
VALIDATION RESULTS:
- F1 (major): FIXED, judged from the code. build.sbt:11 is a strict top-level `val buildSourceGuard: Unit = BuildGuard.enforceAtLoad()`.
  - BuildGuard.scala:24-27: pruning of `project` is anchored at the root only (`prunedRelative` compares the root-relative path, so "project" matches only /project). A nested `modules/x/project/*.sbt` is still scanned and flagged.
  - Pruning by name (`target`, `.git`, `.bsp`, `.bloop`, `.metals`, `.idea`, `.terraform`, `node_modules`) only skips directories that cannot be sbt project bases, because the bases are fixed in build.sbt:132-236.
  - Case: the name test lowercases (`isSbtName`, line 32), so `x.SBT` is flagged. A root `Build.sbt` is also flagged, which errs on the strict side.
  - Symlinks: a symlinked `*.sbt` file is flagged by name. A symlinked directory's direct `*.sbt` children are flagged (lines 55-62). Unreadable paths abort the load (lines 66-67), so the check fails closed.
  - Subproject `project/*.scala` is not a build source in sbt 1.x. Only the root meta-build loads `project/*.scala`, and build-engineer owns it.
  - `verifyBuildSources` runs first in the `check` alias (build.sbt:279) and cross-checks the build base against the launch directory (build.sbt:245-249).
  - Glob confirms the only `*.sbt` files are /build.sbt and /project/plugins.sbt, and that no .sbtopts, .jvmopts or .sbtrc exists.
  - The load-order claim (root build.sbt initializes before subproject .sbt files are evaluated) fits how sbt loads builds, but I cannot run sbt to observe it (see Unverified concerns).
- F3 (minor): FIXED. TestMarker.scala:36-40 refuses any project with production sources > 0 or test sources > 0 and passed == 0. It is enforced at lines 64-68, after the overall-pass check (46-50) and the failed/errored/canceled check (60-63). The marker is written only after all three checks pass.
- F4 (minor): FIXED. build.sbt:84-107 blanks 13 named variables and every inherited AWS_*. The later `++ Map(...)` correctly overrides any inherited AWS_CONFIG_FILE, AWS_SHARED_CREDENTIALS_FILE or AWS_EC2_METADATA_DISABLED. The comment now says best-effort and states that the network is not blocked. Values are never read, only names.
- F5 (minor): FIXED. project/plugins.sbt:8-34 checks the ledger value while the meta-build settings load, before `update` resolves plugins. The expansion matches the sbt 1.x addSbtPlugin form: `Defaults.sbtPluginExtra(m, (pluginCrossBuild / sbtBinaryVersion).value, (update / scalaBinaryVersion).value)`. In the meta-build, `baseDirectory` is /project, so the path resolves correctly. The rejection rules are identical to Ledger.floating (Ledger.scala:19-25): same forbidden-character string `"+[](),*{}<>$? \t"`, same empty/latest/snapshot/release tests on the trimmed value. Both files carry a "keep identical" comment.
- F7 (nits): mostly FIXED.
  - RELEASE in any case, `$` and `?` are now rejected (Ledger.scala:19-24).
  - The ledger is loaded from `ThisBuild / baseDirectory` (build.sbt:26), and the dependencies are now `Def.setting` (36-52).
  - The .gitignore header no longer mentions gitignore-rationale.md.
  - The marker temp file is now under /target/test-marker (TestMarker.scala:101-103), which /target/ in .gitignore covers.
  - The literal-version check is partial (see finding N1).
- F2 (major, deferred to the human): handled correctly. versions.properties:28-30 records it as pending a human decision before Phase 6, build.sbt:48 cross-references it, and the dependency is unchanged.
- F6 (minor, deferred): unchanged. The image is still a pinned tag with the digest pending (versions.properties:34-35), which is the accepted deferral.
- Regression check: none found.
  - Module graph unchanged and matches CLAUDE.md: core has no internal deps; entityResolution depends on core; hadoop on core and entityResolution; sparkAnalytics, storage on core; rag on core and storage; services on core, rag and storage; tests uses test scope only and nothing depends on it.
  - Hadoop and Spark are still Provided, munit is Test only, and the `tests` project uses Test scope.
  - Aliases are intact, and `check` runs verifyBuildSources, verifyVersionLedger, scalafmtSbtCheck, scalafmtCheckAll, Test/compile, then markTestRun.
  - The ledger still has 13 entries and matches project/build.properties (1.11.7) and .scalafmt.conf (3.9.4).
  - No secrets, and nothing beyond AWS_* variable names is read from the environment.
ASSUMPTIONS: sbt 1.11.7 initializes the root build.sbt's generated object, including strict vals, while discovering root projects, and only then evaluates each subproject's *.sbt files. sbt 1.x ignores `project/` directories inside subprojects.
RISKS: The residuals build-engineer stated remain: a root .sbtopts, .jvmopts or .sbtrc; ~/.sbt global plugins and settings; SBT_OPTS; the time gap between the scan and the load; and opaque test code writing anywhere the OS user can (B11 is off). The literal-version scan is pattern-based and can be evaded (N1).
MANUAL ACTION REQUIRED: The human decides F2 (the Spark/Scala runtime target versus EMR 7.x Scala 2.12) before Phase 6, and F6 (pinning the image by digest) before any image is published. When convenient, a human can confirm F1 empirically: create a throwaway `modules/core/x.sbt`, check that `sbt check` aborts at load, then delete the file.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: The human reviews the Phase 2 diff and this verdict, records F2 and F6 as accepted deferrals, commits, and records the hash.
VERDICT: APPROVE
FINDINGS:
build-engineer:
- N1 (nit): /Users/taufeeqpatel/Desktop/ScholarChain/project/Ledger.scala:81 and build.sbt:257. The literal-version check only catches a string literal written directly in the version slot (`% "name" % "1.2.3"`). It misses:
  - an indirect literal, such as `val v = "1.2.3"; "org" % "name" % v`;
  - a literal inside a `/* */` block comment boundary trick, since only `//` comments are stripped (lines 84-95);
  - literals in project/*.scala, which is not scanned.
  Impact: a hardcoded version written that way would still pass `sbt check`. Exposure is low because only build-engineer can write these files.
- N2 (nit): /Users/taufeeqpatel/Desktop/ScholarChain/.gitignore:1-2. "owner: build-engineer" appears on both header lines. Cosmetic only.
UNVERIFIED CONCERNS:
- I did not observe the F1 load-order claim (strict val runs before any subproject .sbt evaluates, aborting the load), because I cannot run sbt. The code and sbt's documented loading model support it.
- If sbt is started with a module directory as the working directory (for example, cwd=modules/core), sbt treats that directory as a separate build. The root build.sbt and its guard would not load, so a hostile `modules/core/*.sbt` could run there. Agent Bash calls run from the repository root and `sbt check` takes no directory argument, so this looks unreachable through the allowlist, but I have not verified it.
- On `reload` (ReloadOnSourceChanges), I assume the build.sbt object is initialized again in a fresh classloader, so the guard runs again. The verifyBuildSources step in `check` covers this case anyway.
```
