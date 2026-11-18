# Phase 06 BE-P6-09 repair 01: build-engineer, CR-P6-27 m2, m3, n3 (2026-11-17)

- Routed under prompt-pause-handoff.md. Edited only build.sbt, project/SparkSubmitLocal.scala, project/LocalRuns.scala. No sparkSubmitLocal, no sbt clean, no AWS.
- STATUS: COMPLETE.

## Changes

- m2: `beforeCompile` returns the observed HEAD; `sparkSubmitLocal(…, headBeforeCompile)` calls `headMoved(headBeforeCompile, pre.head).foreach(refuse)` after the post-compile preChecks; `headMoved` refuses a difference or a non-40-hex value; `--code-version`, inventory and result.txt use `pre.head`.
- m3: comparison JVM `LocalRuns.runForked` gets `StrippedPrefixes, StrippedNames` (JAVA_TOOL_OPTIONS, _JAVA_OPTIONS, JDK_JAVA_OPTIONS, JAVA_OPTS, SBT_OPTS, CLASSPATH). `runForked` gains opt-in `stripNames: Set[String] = Set.empty`; new pure `strippedFromFork`, `forkedEnvironment`; erDev, sparkIT, local pipeline and route probe unchanged.
- n3: `Files.createLink` FileAlreadyExistsException: a regular `dest` with the pinned sha512 is used and only the own partial deleted (invocation still stops, NOT a pass); symlink, non-regular or other sha512 refuses and keeps both; `dest` never deleted or overwritten.
- Self-tests: m3 environment equality and each of 6 names stripped, default stripping unchanged; m2 unchanged, moved and malformed HEAD; n3 four cache cases (match, other sha512, symlink, directory).

## Commands and results

- `sbt Test/compile`: `[success] Total time: 1 s`.
- `sbt check` (background after 180 s; exit 0; `[success] Total time: 314 s (0:05:14.0), completed Nov 17, 2026, 11:23:07 PM`):
  ```
  [info] Passed: Total 621, Failed 0, Errors 0, Passed 621
  [info] Passed: Total 464, Failed 0, Errors 0, Passed 464
  [info] Passed: Total 85, Failed 0, Errors 0, Passed 85
  [info] Passed: Total 255, Failed 0, Errors 0, Passed 255
  [info] Passed: Total 145, Failed 0, Errors 0, Passed 145
  [info] verifySparkSubmitLocalGuards: PASS: 99 cases gave the expected verdict (temporary directories only; no network, no fork)
  [info] markTestRun: wrote /Users/taufeeqpatel/Desktop/ScholarChain/.claude/.last-test-run (git_head=bd9c0a5a195a86a6b8df9a39de3eabc50db124f7, worktree=dirty, suites=112, passed=1570, skipped=0)
  ```
  Other self-tests: verifyHeldoutGuards PASS (23 + 17), verifyErDevRunGuards PASS 128, verifySparkItGuards PASS 204, verifySparkItRunner PASS 8, verifyHeldoutOutputRoute PASS (0 of 6982 files). Ledger OK (24); scalafmt checks passed.
- Guard refusals (nothing executed): one quoted `grep`, one `git status --short -- .claude build.sbt project`.

## Orchestrator verification

Next orchestrator Bash call refused by `audit-bash-writes.sh --pre` as UNATTRIBUTED, changed path `.claude/.last-test-run` (GENERATED) only; re-run once. Marker read: `written_by=sbt markTestRun`, `written_at_utc=2026-11-18T05:23:07.120912Z`, `git_head=bd9c0a5…`, `git_worktree=dirty`, `suites=112`, `tests_passed=1570`, `tests_failed=0`, `tests_errored=0`, `tests_skipped=0`, `production_sources=112`; matches. `verify-tests-ran.sh --check`: OK.

## Risks (as reported)

A commit made and reverted to the same HEAD during compile is not detected (the post-compile raw-byte scope check covers the bytes); n3 cannot stop another writer changing `dest` after verification; self-tests do not fork the comparison JVM or run spark-submit; `sbt sparkSubmitLocal` still unobserved.
