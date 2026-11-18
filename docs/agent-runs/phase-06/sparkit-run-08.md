# Phase 06 full sbt check and sparkIT run 08 after SP-P6-04: spark-engineer report (2026-11-17)

- Code: HEAD `facb73dcdfd7fc0fbe790a788249cf7a007a94d7` plus the uncommitted SP-P6-04 tree (`git diff --stat`: 17 files changed, 1152 insertions(+), 289 deletions(-); untracked store/, job/ sources and tests, CommitPublisherSuite, HadoopStoreReaderSuite, test resources job/; plus orchestrator evidence files). No file edited by the agent; no `sbt clean`; archive read only.
- STATUS: both PASS. Run ID identical to run 07: the store refactor did not change any identity input.

## sbt check (background, exit 0)

```
[info] Passed: Total 621, Failed 0, Errors 0, Passed 621
[info] Passed: Total 464, Failed 0, Errors 0, Passed 464
[info] Passed: Total 85, Failed 0, Errors 0, Passed 85
[info] Passed: Total 232, Failed 0, Errors 0, Passed 232
[info] Passed: Total 145, Failed 0, Errors 0, Passed 145
[info] verifyHeldoutOutputRoute: PASS: held-out-route marker sha256=5df36712669f805ec2ba86832e80f9314eae81294ba254f9a4d7755dbf17be07 found in 0 of 6801 sbt-persisted files; it was printed by the child straight to the terminal
[info] verifyHeldoutGuards: PASS: 23 synthetic launch cases and 17 temporary-repository clean-tree cases gave the expected verdict
[info] verifySparkItGuards: PASS: 204 cases gave the expected verdict
[info] verifySparkItRunner: PASS: 8 cases gave the expected verdict with the real runner on the SparkIT classpath (JUnit /Users/taufeeqpatel/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar); scratch target/sparkit-runner-check/ cleaned
[info] compiling 1 Scala source to /Users/taufeeqpatel/Desktop/ScholarChain/modules/spark/target/scala-3.3.6/sparkit-classes ...
[info] done compiling
[info] markTestRun: wrote /Users/taufeeqpatel/Desktop/ScholarChain/.claude/.last-test-run (git_head=facb73dcdfd7fc0fbe790a788249cf7a007a94d7, worktree=dirty, suites=110, passed=1547, skipped=0)
[success] Total time: 287 s (0:04:47.0), completed Nov 17, 2026, 6:37:12 PM
```

(rag, root, services, storage: 0 tests; verifyErDevRunGuards PASS 128.)

Hook events (reported with exact paths per prompt-sp-p6-03-audit-review.md): SubagentStop reported "The test marker .claude/.last-test-run changed after the last audited Bash call of this session, without an audited 'sbt check' … Possible test-marker forgery"; the next Bash call (`sbt sparkIT`) was refused by `audit-bash-writes.sh --pre` as UNATTRIBUTED, changed path `.claude/.last-test-run` (owner GENERATED) only; re-run once. Marker read by the agent: `written_by=sbt markTestRun`, `written_at_utc=2026-11-18T00:37:12.894556Z`, `git_head=facb73d…`, `git_worktree=dirty`, `suites=110`, `tests_passed=1547`, `tests_failed=0`, `tests_errored=0`, `tests_skipped=0`, `production_sources=111`; matches the markTestRun line.

## sparkIT run 08 (attempt-6, exit 0)

```
[info] [sparkit-runner] class scholarchain.spark.DevArchiveGraphFoundationSuite: run=14 failures=0 ignored=0 assumptionFailures=0
[info] sparkIT: PASS: 14 tests in 1 class(es), every expected class ran, no failure, ignored test or assumption failure (exit 0); evidence modules/spark/target/spark-it/attempt-6/junit-result.txt and result.txt; copy them into the phase evidence (sbt clean can remove attempt folders)
[success] Total time: 32 s, completed Nov 17, 2026, 6:38:34 PM
```

`attempt-6/junit-result.txt`:

```
format=scholarchain.sparkit.junit-result.v2
nonce=d80662fa93773cf521d9f46b9d8e20f32ae0bc75924dad7a84f528419174a2fd
junit	JUnitCore	/Users/taufeeqpatel/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar	4.13.2
junit	Result	/Users/taufeeqpatel/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar	4.13.2
class	scholarchain.spark.DevArchiveGraphFoundationSuite	run=14	failures=0	ignored=0	assumptionFailures=0
classes=1
complete=d80662fa93773cf521d9f46b9d8e20f32ae0bc75924dad7a84f528419174a2fd
```

`attempt-6/result.txt`: `status=pass`, `tests=14`, `exit=0`, `expectedClasses=1`, expected and loaded JUnit 4.13.2, `class=scholarchain.spark.DevArchiveGraphFoundationSuite run=14 failures=0 ignored=0 assumptionFailures=0`.

All printed counts equal run 07 (sparkit-run-07.md): run `run:dffad781b52d554739174502776797a62a4aa12ac7fdb5dc2cd9a7d32c04212c`, configDigest `e37c32b1…00df`; edges 259, metrics 176; windows 112/163, 64/96; structure, bridge and canonical values identical; 9 stored paths, 37 exact path-length estimates; temporal limitations and changes 64/48; summaries 51/9/176/5/8; 4 load manifests (neptune 12 items 1258 rows, dynamodb 12/1302, s3 16/2809, s3-vectors 2/249; partManifestSha256 `77c48ada822215917b296a488f79d5e60795ab3ce92cd0aafcebc51f894d83af`); 28 of 28 datasets logicalEqual. `_parts.json`: 29 files, same row counts as run 07. `_run-manifest.json` identical in content to run 07 (17 outputs, codeVersion placeholder `unpinned-sparkit-dev`).

Observation: the load-manifest IDs and partManifestSha256 differ from run 07 (load:6f5cb013… vs load:c1f8718a…, 77c48ada… vs 056e429a…) while the run ID is the same; expected, because they hash `_parts.json`, which contains per-file sha256 values of non-byte-reproducible Parquet/CSV parts (accepted limit).

Attempts 1-5: result.txt content unchanged (Read; no byte comparison available).
