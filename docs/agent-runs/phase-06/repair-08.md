# Phase 06 repair 08: BE-P6-03 repair 01 (sparkIT, CR-P6-16)

- Work order (verbatim): docs/agent-runs/phase-06/prompt-be-p6-03-repair-01.md
- Findings routed: CR-P6-16 F1 (evidence), F2-F5, F6 (wording), pass-rule hardening, MUNIT_* stripping. Carried forward: the two nits.
- Owner: build-engineer

## Orchestrator observations after the report (2026-11-16)

- The orchestrator's next Bash call was NOT refused by the audit (no UNATTRIBUTED marker change), consistent with the agent's report that `sbt check` ran in the foreground inside an audited call.
- `git status --porcelain`: ` M build.sbt`, ` M docs/agent-runs/phase-06/review-report.md`, ` M project/LocalRuns.scala`, `?? docs/agent-runs/phase-06/be-p6-03-report.md`, `?? docs/agent-runs/phase-06/prompt-be-p6-03-repair-01.md`, `?? docs/agent-runs/phase-06/prompt-be-p6-03-sparkit.md`, `?? docs/agent-runs/phase-06/prompt-sp-p6-02-step-0.md`, `?? docs/agent-runs/phase-06/sp-p6-02-step-0-report.md`, `?? modules/spark/src/test/scala/scholarchain/`, `?? project/SparkItJUnitRunner.java`, `?? project/SparkItRuns.scala`, `?? project/spark-it.properties`.
- `.claude/hooks/verify-tests-ran.sh --check`: `verify-tests-ran --check: OK (no uncommitted source change is newer than .claude/.last-test-run)`.

## Agent report (build-engineer, 2026-11-16), verbatim

STATUS: COMPLETE
OWNER: build-engineer
WORK ORDER: BE-P6-03 repair 01 (Phase 6). This repairs the uncommitted sparkIT work on HEAD e59d753 for CR-P6-16 (F2 to F6, the pass-rule hardening, MUNIT_* stripping, and the two carried nits). F1 is met by one foreground `sbt check`, run last: exit 0, 139 s. Scope was build.sbt and project/** only.

FILES CREATED:
- /Users/taufeeqpatel/Desktop/ScholarChain/project/SparkItJUnitRunner.java. This is the runner main. It is plain Java with no Scala library and no compile-time JUnit dependency (JUnit 4 is reached by reflection), and it has no nested or anonymous class. sbt compiles it as part of the meta-build (observed: "compiling 1 Scala source and 1 Java source"). sparkIT copies its single class file into `attempt-<n>/runner/`. Nothing goes into modules/**, so no BLOCKED-ON was needed.

FILES MODIFIED:
- /Users/taufeeqpatel/Desktop/ScholarChain/project/SparkItRuns.scala. This file is still untracked. Changed regions, by current line numbers:
  - 24-68: header doc (F5 walk, F6 wording, runner, MUNIT_*, pass rule)
  - 91-139: runner constants; `StrippedPrefixes` now adds `MUNIT_`; `strippedFromChild`; `MaxRunDepth=4`, `MaxRunEntries=20000`; `RefusedRoutes`, `RunCapableKeys`, `FormatCheckStep`
  - 203-207: result-file regexes (the JUnit summary regexes are removed)
  - 429 and 435-489: `runTreeProblems`, the F5 NOFOLLOW walk, called from `layoutProblems`
  - about 520-535: `createAttempt` F6 wording
  - 574-about 755: nonce, `runnerClassBytes`, `installRunner`, `runnerArgs`, `readResult`, `parseResult`, the new `verdict`. `SummaryCapture` is removed.
  - about 840-900: the sparkIT fork through the runner, the verdict, per-class `result.txt`, `orFail`
  - 902-about 1005: `refuseSbtRoute` (now returns Nothing), `refusedInput`, `routeWiringProblems`, `formatGateProblems`
  - 1028 to the end: `guardSelfTest`, with a new signature and new cases
- /Users/taufeeqpatel/Desktop/ScholarChain/build.sbt:
  - 5-29: environment-variable header (MUNIT_*)
  - 103-106: description of `verifySparkItGuards`
  - 109-116: SparkIT comment
  - 309-334: sparkAnalytics. `inConfig(SparkIT)(ScalafmtPlugin.scalafmtConfigSettings(SparkIT))`, plus one refusal setting per line for every refused route
  - about 494-503: `verifySparkItGuards` arguments (structure, sparkAnalytics ProjectRef, javaHome)
  - about 532-545: `checkSteps` gains `SparkItRuns.FormatCheckStep` before `Test/compile`
- /Users/taufeeqpatel/Desktop/ScholarChain/project/LocalRuns.scala: not changed in this repair. The earlier BE-P6-03 visibility change of `runForked` remains.
- /Users/taufeeqpatel/Desktop/ScholarChain/project/spark-it.properties: not changed.

COMMANDS RUN (all in the foreground):
1. `git diff --stat` and `git diff -- docs/agent-runs/phase-06/review-report.md` (reading inputs); `git ls-files -- project`.
2. `sbt Test/compile`. This was a deliberate probe and failed as intended. The compiler reported "type mismatch; found: Unit required: sbt.Configuration" for `ScalafmtPlugin.scalafmtConfigSettings(())`, which confirms the 2.5.5 signature `scalafmtConfigSettings(configuration: Configuration)` from the plugin's compiled API. It also reported "not found: value testFull", so `testFull` is not a key in sbt 1.11.7.
3. `sbt Test/compile` with a temporary aid (`root / Test / compile` depending on `verifySparkItGuards`). The self-test passed: "PASS: 129 cases".
4. `sbt Test/compile` with the line `SparkIT / executeTests := ...` deleted on purpose, to prove a real wiring removal is caught. It failed as expected: "got sparkAnalytics/SparkIT/executeTests is defined at (sbt.Defaults.testTasks) Defaults.scala, not by a refusal in build.sbt". The line was restored.
5. `sbt Test/compile` with the scalafmt per-config settings removed on purpose. It failed as expected: "sparkAnalytics/SparkIT/scalafmtCheck is not defined in SparkIT's scope". This also confirms the reviewer's F2 premise. The settings were restored and the temporary aid removed.
6. `git diff -- build.sbt` (review).
7. `sbt check`: run once, last, foreground, 600000 ms timeout. The output (27.5 MB) was saved by the tool to /Users/taufeeqpatel/.claude/projects/-Users-taufeeqpatel-Desktop-ScholarChain/1d4ee1c4-462f-4438-9d00-5d7f5bbf5c79/tool-results/bqckmfs8b.txt. I read the relevant lines from that file.

Not run: `sbt sparkIT`, `sbt clean`, `sbt clean check`, `sbt fmt`, and any held-out task.

TEST RESULTS (F1, from that `sbt check`):
- Final lines, verbatim:
  - `[info] markTestRun: wrote /Users/taufeeqpatel/Desktop/ScholarChain/.claude/.last-test-run (git_head=e59d7539d344eece8120c47c0ac6704773fff518, worktree=dirty, suites=90, passed=1254, skipped=0)`
  - `[success] Total time: 139 s (0:02:19.0), completed Nov 16, 2026, 3:44:00 PM`
- Per project, passed with 0 failed: core 557, entityResolution 464, hadoop 85, sparkAnalytics 3, tests 145, rag 0, root 0, services 0, storage 0.

VALIDATION RESULTS (same run):
- Build sources OK. Version ledger OK (14 pinned entries; no literal versions in 2 build files).
- scalafmtSbtCheck, scalafmtCheckAll and the new `sparkAnalytics/SparkIT/scalafmtCheck` step all `[success]`. The SparkIT step checks 0 files today, because no file exists under src/it yet. It does not compile anything and does not touch the archive.
- verifyHeldoutOutputRoute PASS (marker in 0 of 5865 files; counter-test PASS).
- verifyHeldoutGuards: "PASS: 23 synthetic launch cases and 17 temporary-repository clean-tree cases". Unchanged.
- verifyErDevRunGuards: "PASS: 128 cases". Unchanged.
- verifySparkItGuards: "PASS: 129 cases gave the expected verdict" (it was 91). Its read-only report still says only that the archive root is "present (a directory); contents not read, layout not checked". Routine check stays independent of the archive.

Each required item:
- **F2:** the plugin's per-configuration settings are applied in SparkIT, and `check` runs the explicit `sparkAnalytics/SparkIT/scalafmtCheck` step before `Test/compile`. The self-test checks four things in the loaded build: the task is defined in SparkIT's own scope; it depends on SparkIT `unmanagedSources`; SparkIT `unmanagedSourceDirectories` contains modules/spark/src/it/scala; and the step is in checkSteps before Test/compile. There is a counter-test, plus the observed mutation in command 5.
- **F3, refused SparkIT routes:** test, testOnly, testQuick, executeTests, run, runMain, bgRun, bgRunMain, fgRun, fgRunMain, console, consoleQuick, consoleProject. `testFull` does not exist in sbt 1.11.7. It stays in `RunCapableKeys`, so a later sbt that defines it in SparkIT without a refusal fails the self-test.
  - Corrected claim: these 13 SparkIT keys refuse. Other routes are not refused, such as SparkIT compile and `Test`-scoped tasks. They only compile and run no suites.
- **F4, new cases:**
  - MaxConfigBytes (a 70470-byte configuration)
  - project/ as a symbolic link, with the configuration file itself regular
  - output root as a regular file
  - exit 1 without a result file
  - route wiring checked from `Project.extract(state).structure.settings`: the effective setting's source position must be on a build.sbt line that calls the refusal, plus the run-capable-key check. Each has a counter-test, plus the observed mutation in command 4.
- **F5, synthetic fixtures:**
  - control
  - symbolically linked part file
  - symbolically linked subdirectory
  - a dangling link whose target string names a held-out path inside the fixture only (refused by its own path; target never created or shown)
  - a special file, a Unix domain socket (created and refused in this run)
  - depth bound (depth 5 > 4)
  - entry-count bound (3), with a control at 100
- **F6:** every "never deleted" claim is reworded: sparkIT never reuses or deletes an attempt, but `sbt clean` can remove spark-it attempt folders, so integration evidence must be copied into the Phase 6 evidence after a real run. No protection rule was added.
- **Child environment:** `MUNIT_` is added to the strip prefixes. The runner also refuses (exit 4) if any MUNIT_* reaches it. Two cases cover this: a predicate case, and a real fork with MUNIT_FLAKY_OK that gets exit 4 with the nonce left unread.
- **Runner forks without JUnit:** one fork shows exit 2, the nonce consumed, no result file, and a verdict of NOT a pass.

THE EXACT PASS RULE (`SparkItRuns.verdict`): PASS (total test count) only if all of these hold:
1. The forked JVM exited within 120 s with exit 0.
2. The runner consumed (deleted) its one-time nonce file. The nonce is 32 SecureRandom bytes, written create-new, and read and deleted before any test class is loaded.
3. `attempt-<n>/junit-result.txt` is a regular, symlink-free file of at most 1 MiB, written create-new by the runner after the last class.
4. The file parses strictly: `format=scholarchain.sparkit.junit-result.v1`, `nonce=<this run's nonce>`, one `class` or `error` line per class, `classes=<that count>`, `complete=<this run's nonce>`, and a final LF.
5. The set of classes in the file equals the expected set exactly: the sorted SparkIT definedTestNames, n > 0, none missing, extra or repeated.
6. There is no error line.
7. Every class has run > 0, failures = 0, ignored = 0 and assumptionFailures = 0.

Everything else is reported as "FAILED (...): <every problem>; NOT a pass". That includes a test calling System.exit (no result file), a printed fake summary (stdout is never parsed), a forged file without the nonce, and a truncated file. Each such case has a self-test.

ASSUMPTIONS:
- The munit 1.1.1 MUnitRunner reports `assume` failures and flaky-under-MUNIT_FLAKY_OK as JUnit assumption failures, and `.ignore` as ignored. Both are counted and refused. This could not be observed, because no src/it suite exists and sparkIT was not run.
- `Result.getAssumptionFailureCount` exists in JUnit 4.13.2. If it were absent, the class gets an error line and fails closed.
- sbt setting source positions map to build.sbt lines, which the counter-tests and the mutation in command 4 confirm.

RISKS:
- The runner has not yet run against real JUnit. The first real `sbt sparkIT` is its first observation.
- Test code runs in the same JVM as the runner. A deliberately adversarial test could still interfere, for example by instrumentation. Code review of src/it suites remains the control.
- Test classes that sbt's munit fingerprint does not detect are not expected and not run. This is unchanged.
- Hadoop's `hadoop.tmp.dir` default outside the attempt is unverified. This is unchanged.
- The wiring checks use the internal API `sbt.internal.BuildStructure`. An sbt upgrade would fail loudly, not silently.
- The socket special-file case depends on the OS. It runs here; elsewhere it is logged as not run.
- The SparkIT format step checks 0 files until the first suite lands.
- Process note: during iteration I used a temporary `root / Test / compile` dependency on `verifySparkItGuards` and two deliberate mutations. All were reverted before the final check; the build.sbt diff shows none of them.
- Carried forward, not fixed:
  - nit 1: `used.max + 1` in `nextAttempt` overflows at `attempt-2147483647`
  - nit 2: `LocalRuns.namesHeldout` does not match `held.out`, which SegmentChars permits. This is pre-existing and shared.
- Phase 5 tasks, held-out protections, erDecisionGroupingDev, the Spark/Scala setup and the module graph are unchanged.

MANUAL ACTION REQUIRED:
- Human: review the diff and commit. The marker `.claude/.last-test-run` was written by the foreground, audited `sbt check` above.
- Orchestrator: route the focused code-reviewer re-check.
- factory-engineer, after review and human ratification: prepare the `sbt sparkIT` activation (spark-engineer row, currently `inactive:B10-alias-pending`).
- After the first real run: copy `attempt-<n>/junit-result.txt` and `result.txt` into the Phase 6 evidence.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: a focused code-reviewer review of the files above. If approved, factory-engineer prepares the standalone `sbt sparkIT` activation for human ratification and restart. Then spark-engineer adds the first suite under modules/spark/src/it/scala, and the first real run's evidence goes into the Phase 6 report.
