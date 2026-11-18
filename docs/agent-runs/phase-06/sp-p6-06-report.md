# Phase 06 SP-P6-06: spark-engineer report, published-run comparison entry point (2026-11-17)

- Work order: routes BE-P6-09 BLOCKED-ON spark-engineer (comparator interface in be-p6-09-report.md). Base HEAD bd9c0a5 plus uncommitted SP-P6-05 and BE-P6-09; build.sbt and project/** not touched.
- STATUS: COMPLETE. OWNER: spark-engineer. BLOCKED-ON: none. No main-code change (job jar and run identity unchanged; sparkIT not needed).

## Files

- Created (test tree): testkit/PublishedRunComparisonMain.scala, testkit/PublishedRunComparisonSuite.scala.
- Modified (test tree): testkit/LogicalReproducibility.scala (shared `ParquetDatasets` 16 and `CsvDatasets` 12; comparison logic unchanged), testkit/LocalSpark.scala (optional `local*`-only master, default local[1]), job/CollaborationJobSuite.scala (uses the shared lists; unused import removed).

## Interface as implemented

`--left-output-root`, `--right-output-root`, `--nonce` (64 lower hex), `--result` (absolute, must not exist), each exactly once; exit 2 before any Spark session on argument refusal. Each root must be a real directory equal to its real path holding exactly `run=<hex>` and `load=<same hex>`. Verification with `VerifiedArchiveReader.layout`, `RunPublisher.verifyPublished`, `LoadManifests.verify`; row multisets via `LogicalReproducibility.compare` and `compareCsv`. Result written create-once (CREATE_NEW, NOFOLLOW), v1 format, `complete=<nonce>` last; exit 0 whenever written (facts only), exit 1 when comparison impossible (nothing written). Store tag `neptune` = core `neptune-bulk-load`; counts are declared Parquet rows of coauthor-edge.v2 and researcher-metrics.v2. `main` runs local[2], UI off, loopback, temp scratch deleted afterwards; no network, AWS or s3a.

## Commands and results

- `sbt sparkAnalytics/Test/compile`: success.
- `sbt sparkAnalytics/test` first: `Failed: Total 255, Failed 1` (the agent's own test wrongly asserted every dataset non-empty; six fixture CSV datasets have 0 rows; assertion fixed). Second: `[info] Passed: Total 255, Failed 0, Errors 0, Passed 255`.
- `sbt check` (once, backgrounded, exit 0):
  ```
  [info] Passed: Total 621, Failed 0, Errors 0, Passed 621
  [info] Passed: Total 464, Failed 0, Errors 0, Passed 464
  [info] Passed: Total 85, Failed 0, Errors 0, Passed 85
  [info] Passed: Total 255, Failed 0, Errors 0, Passed 255
  [info] Passed: Total 145, Failed 0, Errors 0, Passed 145
  [info] markTestRun: wrote /Users/taufeeqpatel/Desktop/ScholarChain/.claude/.last-test-run (git_head=bd9c0a5a195a86a6b8df9a39de3eabc50db124f7, worktree=dirty, suites=112, passed=1570, skipped=0)
  [success] Total time: 372 s (0:06:12.0), completed Nov 17, 2026, 9:50:33 PM
  ```
- Guard refusals (nothing executed): `git grep` x3, `git status --short -- <path>` x3.
- Six PublishedRunComparisonSuite tests: dataset and store lists; two publications of one run (exit 0, exact lines, parsed by a replica of `parseComparison`: 2 runs, 4 counts, 8 loads, 28 datasets equal; second call onto same result exit 2, file unchanged); attempt 2 gives `different`; argument refusals exit 2 with no session; missing root, two runs, missing load, extra `.spark-*` exit 1; tampered Parquet byte, `_UNVERIFIED`, missing `_SUCCESS` exit 1.

## Orchestrator verification

Next orchestrator Bash call refused by `audit-bash-writes.sh --pre` as UNATTRIBUTED, changed path `.claude/.last-test-run` (GENERATED) only; re-run once. Marker read: `written_by=sbt markTestRun`, `written_at_utc=2026-11-18T03:50:33.835538Z`, `git_head=bd9c0a5…`, `git_worktree=dirty`, `suites=112`, `tests_passed=1570`, `tests_failed=0`, `tests_errored=0`, `tests_skipped=0`, `production_sources=112`; matches. `verify-tests-ran.sh --check`: OK.

## Risks (as reported)

`main` (local[2] and `sys.exit`) not exercised in-process, first by `sbt sparkSubmitLocal`; build parser replicated, not imported; stray entries in out/ fail closed; output roots must have no symlink on their path; sbt check output ~228k lines (pre-existing DEBUG logs, unrouted).

NEXT GATE: infra-engineer, factory-engineer row draft, combined code review, human ratification and commit, then `sbt sparkSubmitLocal` twice.
