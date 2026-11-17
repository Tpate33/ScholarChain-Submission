# Phase 06 full sbt check and sparkIT run 07: spark-engineer report (2026-11-17)

- Work order: prompt-sparkit-run-07.md. Code: uncommitted tree on HEAD `c2d9a8216b945f2dd57eb587099f0b938ed1d670`; `git diff --stat`: 28 files changed, 3442 insertions(+), 324 deletions(-) (11 core, 16 spark, project/SparkItRuns.scala), plus untracked new sources and evidence (orchestrator `git status --porcelain` after the run lists 28 modified and 44 untracked paths). Run manifest codeVersion is the placeholder; this tree is the link to code.
- STATUS: both PASS. No `sbt clean`, no file edit, archive read only. Attempts 1-4 unchanged.

## sbt check (verbatim, as reported)

```
[info] Passed: Total 621, Failed 0, Errors 0, Passed 621
[info] Passed: Total 464, Failed 0, Errors 0, Passed 464
[info] Passed: Total 85, Failed 0, Errors 0, Passed 85
[info] Passed: Total 202, Failed 0, Errors 0, Passed 202
[info] Passed: Total 145, Failed 0, Errors 0, Passed 145
[info] compiling 1 Scala source to /Users/taufeeqpatel/Desktop/ScholarChain/modules/spark/target/scala-3.3.6/sparkit-classes ...
[info] done compiling
[info] verifyHeldoutGuards: PASS: 23 synthetic launch cases and 17 temporary-repository clean-tree cases gave the expected verdict
[info] verifyErDevRunGuards: PASS: 128 cases gave the expected verdict
[info] verifySparkItGuards: PASS: 204 cases gave the expected verdict
[info] verifySparkItRunner: PASS: 8 cases gave the expected verdict with the real runner on the SparkIT classpath (JUnit /Users/taufeeqpatel/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar); scratch target/sparkit-runner-check/ cleaned
[info] markTestRun: wrote /Users/taufeeqpatel/Desktop/ScholarChain/.claude/.last-test-run (git_head=c2d9a8216b945f2dd57eb587099f0b938ed1d670, worktree=dirty, suites=106, passed=1517, skipped=0)
[success] Total time: 239 s (0:03:59.0), completed Nov 17, 2026, 3:42:25 PM
```

(rag, root, services, storage: 0 tests.) Exit 0.

### Hook event (reported with exact paths, per prompt-sp-p6-03-audit-review.md)

The call was moved to the background; the agent's next Bash call (`git status --porcelain`) was refused by `audit-bash-writes.sh --pre` as an UNATTRIBUTED inter-call change. Changed path: `.claude/.last-test-run` (owner GENERATED) only. Refusal text as quoted by the agent: "INTER-CALL CHANGE detected by .claude/hooks/audit-bash-writes.sh (UNATTRIBUTED) … - .claude/.last-test-run (owner: GENERATED) This Bash call was refused. The change was NOT reverted. Logged in: /var/folders/zt/pbpscx0s72b8jch7_wr7gc9m0000gn/T/scholarchain-bash-audit-501/session-276129da30842b93828e5f236f57fd1b.incidents.log". The same command was re-run once and succeeded. While sbt ran, SubagentStop refused completion three times ("32 source change(s) are newer than the test marker"), as expected before markTestRun.

Marker (orchestrator Read after the run): `written_by=sbt markTestRun`, `written_at_utc=2026-11-17T21:42:24.992867Z`, `git_head=c2d9a82…`, `git_worktree=dirty`, `suites=106`, `tests_passed=1517`, `tests_failed=0`, `tests_errored=0`, `tests_skipped=0`, `production_sources=107`. Matches the markTestRun line. Previous marker: 19:31:03Z, 101 suites, 1457 passed.

## sparkIT run 07

```
[info] [sparkit-runner] class scholarchain.spark.DevArchiveGraphFoundationSuite: run=14 failures=0 ignored=0 assumptionFailures=0
[info] sparkIT: PASS: 14 tests in 1 class(es), every expected class ran, no failure, ignored test or assumption failure (exit 0); evidence modules/spark/target/spark-it/attempt-5/junit-result.txt and result.txt; copy them into the phase evidence (sbt clean can remove attempt folders)
[success] Total time: 32 s, completed Nov 17, 2026, 3:43:37 PM
```

`attempt-5/junit-result.txt`:

```
format=scholarchain.sparkit.junit-result.v2
nonce=c00ce92a999aa9d75c13d23019aef7d4d5ce72d345f675c55ded7355a50bc49e
junit	JUnitCore	/Users/taufeeqpatel/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar	4.13.2
junit	Result	/Users/taufeeqpatel/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar	4.13.2
class	scholarchain.spark.DevArchiveGraphFoundationSuite	run=14	failures=0	ignored=0	assumptionFailures=0
classes=1
complete=c00ce92a999aa9d75c13d23019aef7d4d5ce72d345f675c55ded7355a50bc49e
```

`attempt-5/result.txt` (orchestrator Read): `status=pass`, `tests=14`, `exit=0`, `expectedClasses=1`, expected and loaded JUnit 4.13.2, `class=scholarchain.spark.DevArchiveGraphFoundationSuite run=14 failures=0 ignored=0 assumptionFailures=0`.

### Printed summaries (verbatim, selected; full set in the agent report)

```
SP-P6-02 sparkIT: analysisRunId=run:dffad781b52d554739174502776797a62a4aa12ac7fdb5dc2cd9a7d32c04212c ... edges=259 metrics=176 configDigest=e37c32b115c82cbedc56e2407b8a322b52505eabbd800dfb5dade372b47800df benchmarkPairsDigest=2e1ff1824ba18c90a8b154e9932c51c64565779f62eeb2a519df22ea20bc8be9
SP-P6-02 sparkIT window: WindowInputSummary(TimeWindow(1970,2026),41,112,0,163,182)
SP-P6-02 sparkIT window: WindowInputSummary(TimeWindow(2020,2024),24,64,0,96,107)
SP-P6-02 sparkIT metrics window: MetricsWindowSummary(TimeWindow(1970,2026),112,135,364,0,0,326,364,4)
SP-P6-02 sparkIT metrics window: MetricsWindowSummary(TimeWindow(2020,2024),64,78,214,0,0,192,214,4)
SP-P6-03 sparkIT structure window (computed only, checked by validator): window=1970-2026 researchers=112 edges=163 components=31 largestComponent=8 largestComponentFraction=0.07142857142857142 singletonComponents=1 communities=32 largestCommunity=8 singletonCommunities=1 crossCommunityEdges=6 maxCoreness=4 corenessHistogram=0:1,1:13,2:30,3:32,4:36 labelPropagation=3/true/0
SP-P6-03 sparkIT structure window (computed only, checked by validator): window=2020-2024 researchers=64 edges=96 components=18 largestComponent=8 largestComponentFraction=0.125 singletonComponents=1 communities=19 largestCommunity=5 singletonCommunities=1 crossCommunityEdges=6 maxCoreness=4 corenessHistogram=0:1,1:8,2:15,3:12,4:28 labelPropagation=2/true/0
SP-P6-03 sparkIT canonical (computed only, checked by validator): nodes=Vector((Researcher,117), (AuthorMention,143), (Work,45), (Venue,64), (Institution,60), (Topic,30)) edges=Vector((AUTHORED,143), (RESOLVES_TO,143), (COAUTHORED_WITH,259), (PUBLISHED_IN,70), (AFFILIATED_WITH,143), (HAS_TOPIC,41)) unmatched=UnmatchedReferences(0,0,0) ...
SP-P6-03 sparkIT paths (computed only, checked by validator): stored=9 searches=10 present=8 connected=6 supersteps=2 walkSteps=2 checkpoints=2 maxLevel=17 maxPartialPaths=9 hops=1:3,2:6
SP-P6-03 sparkIT path-length (computed only, checked by validator): estimates=37 components=37 sources=154 supersteps=2 checkpoints=1 maxLevel=498
SP-P6-03 sparkIT temporal (hand-derived counts above): limitations=grouping-unevaluated,overlapping-windows,unequal-window-lengths changes=in-both:64,only-from:48
SP-P6-03 sparkIT summaries (computed only, checked by validator): community-summary=51 path-summary=9 researcher-profile=176 topic-summary=5 work-summary=8
SP-P6-03 sparkIT load manifest (computed only, checked by validator): store=neptune-bulk-load items=12 rows=1258 roles=12 loadId=load:c1f8718aa2b92f5657ad14cff36455a407291e28cb2c8061c1c3123875840c59
SP-P6-03 sparkIT load manifest (computed only, checked by validator): store=dynamodb items=12 rows=1302 roles=12 loadId=load:08a978851f4dfb0775c73f0fe72d8d57b6a1513778489551b130d86c1cdc722b
SP-P6-03 sparkIT load manifest (computed only, checked by validator): store=s3 items=16 rows=2809 roles=16 loadId=load:fc9f11c48f3ed1378299c657d69fab55831ab726a6cf5b363bf1179ca31021c9
SP-P6-03 sparkIT load manifest (computed only, checked by validator): store=s3-vectors items=2 rows=249 roles=2 loadId=load:478bef7ef6c8f8ab5d487f3b3e68303b312c53d94b911a90119069e1bbefd41f
SP-P6-03 sparkIT load manifests dir=.../attempt-5/out/load=dffad781b52d554739174502776797a62a4aa12ac7fdb5dc2cd9a7d32c04212c partManifestSha256=056e429a27eed6aff0809733f073e87c5f6690c664a49a3065e256b703412d0a
```

37 path-length estimate lines, all `exact=true` (sources = component size at sample size 8): the largest components (size 8) have average distance 1.3214285714285714 and max 2.

Reproducibility (two builds in one forked JVM): 28 of 28 `logicalEqual=true` (16 Parquet with `sameSchema=true`, 12 CSV with `sameHeader=true`); `sameBytes=true` only for window-structure, analysis-window and window-comparison (diagnosis only).

## Integrity evidence

- `_parts.json` (run `run:dffad781…212c`): 29 files (`_run-manifest.json` 1287 bytes sha256 `4704f2c7…e293`, 16 Parquet, 12 CSV); row counts: edges 259, metrics 176, components/communities/bridges/coreness 176 each, graph-nodes 459, graph-edges 799, window-structures 2, analysis-windows 2, evidence-paths 9, path-length-estimates 37, window-comparisons 1, temporal-changes 112, summary-inputs 236, entity-summary-inputs 13; CSV as in run 06. Full text in the agent report and in attempt-5.
- `_run-manifest.json`: stage spark.collaboration-analysis; codeVersion placeholder, `codeVersionStatus=unpinned-sparkit-dev`; configDigest `e37c32b1…00df`; the three pinned input runs; `community.converged.*=true`, iterations 3 and 2; `outputs` 17 names (16 plus path-length-estimates); attempt 1.
- Load manifests: `out/load=dffad781…212c/` with neptune-bulk-load.json (12 CSV roles, 1258 rows), dynamodb.json (12 Parquet roles, 1302 rows), s3.json (16 items, 2809 rows), s3-vectors.json (2 items, 249 rows); each names partManifestSha256 `056e429a…2d0a`; every item's byteCount and sha256 equals its `_parts.json` entry (agent Read).
- `_SUCCESS` present, `_UNVERIFIED` absent. sha256 values not recomputed by any agent.

## Expected versus observed

| Expected | Observed |
|---|---|
| 14 IT tests, within 120 s | match, 32 s |
| new run ID; configDigest e37c32b1…00df | match |
| edges 259, metrics 176, windows 112/163 and 64/96 | match |
| nodes 117/45/143/64/60/30; AUTHORED/RESOLVES_TO/COAUTHORED_WITH 143/143/259 | match |
| comparison researchers 64/48/0 | 64 and 48 printed; third value asserted by passing test only |
| comparison edges 96/67/0, overlap 5 years | asserted by passing test only (not printed) |
| limitations | match |
| 112 temporal changes, none gained | 112 printed; "none gained" asserted by passing test only |
| 176 researcher profiles; 4 load manifests | match |
| logical reproducibility 16 Parquet + 12 CSV | match |
| analysis windows 41/0/112/163/0, 24/0/64/96/0 | asserted by passing test only (2 rows written) |
| attempt-5; attempts 1-4 untouched; no clean | match |

## Risks reported

- `sbt check` printed about 227,700 lines, almost all Hadoop DEBUG/INFO logging from forked MapReduce test JVMs (tests and hadoop projects, not spark), which makes review of the output hard (owner of the logging configuration: hadoop-engineer or test-engineer; not routed in this milestone).
- sameBytes=false for 25 of 28 datasets within one JVM; cause not diagnosed (documented limit, not blocking per the human).
