# Phase 5 prerequisite (Unicode format characters): test report

> **PREREQUISITE WORK ONLY. THIS IS NOT COMPLETION OF PHASE 5.**

## Tested state: NOT a clean commit (read this first)

`docs/agent-runs/README.md` requires the test report to record `git rev-parse HEAD` and an **empty**
`git status --porcelain` captured immediately before the test run. **That requirement is not met here.** All
`sbt check` runs below were performed by agents on an uncommitted working tree. This report is therefore not
commit evidence and does not satisfy `/deploy` gate 5. A clean-tree `sbt check` on the accepted commit is still
required (human).

Recorded by the orchestrator after the final test run (2026-11-04), with no source changes since that run
(`verify-tests-ran.sh --check` reported OK):

```text
$ git rev-parse HEAD
d16e8c9717f806f6f4018b56beb456382c5ed23a

$ git status --porcelain
 M modules/entity-resolution/src/main/scala/scholarchain/entityresolution/BlockingKeys.scala
 M modules/entity-resolution/src/main/scala/scholarchain/entityresolution/MatchFeatures.scala
 M modules/entity-resolution/src/main/scala/scholarchain/entityresolution/Names.scala
 M modules/entity-resolution/src/main/scala/scholarchain/entityresolution/TextFolding.scala
 M modules/entity-resolution/src/test/scala/scholarchain/entityresolution/BlockingKeysSuite.scala
 M modules/entity-resolution/src/test/scala/scholarchain/entityresolution/MatchFeaturesSuite.scala
 M modules/entity-resolution/src/test/scala/scholarchain/entityresolution/TextFoldingSuite.scala
 M modules/hadoop/docs/job-contracts.md
 M modules/hadoop/src/test/scala/scholarchain/hadoop/ShuffleAndParamsSuite.scala
 M modules/hadoop/src/test/scala/scholarchain/hadoop/pipeline/LocalPipelineSuite.scala
 M tests/src/test/scala/scholarchain/tests/adversarial/UnicodeSuite.scala
?? modules/entity-resolution/src/test/scala/scholarchain/entityresolution/FormatCharactersSuite.scala
?? tests/src/test/scala/scholarchain/tests/adversarial/FormatCharacterPipelineSuite.scala

$ git diff --stat
 .../entityresolution/BlockingKeys.scala            |  9 ++-
 .../entityresolution/MatchFeatures.scala           | 10 ++-
 .../scholarchain/entityresolution/Names.scala      | 17 ++++--
 .../entityresolution/TextFolding.scala             | 71 +++++++++++++++++++---
 .../entityresolution/BlockingKeysSuite.scala       |  4 +-
 .../entityresolution/MatchFeaturesSuite.scala      | 22 ++++++-
 .../entityresolution/TextFoldingSuite.scala        |  3 +-
 modules/hadoop/docs/job-contracts.md               |  6 +-
 .../hadoop/ShuffleAndParamsSuite.scala             |  4 +-
 .../hadoop/pipeline/LocalPipelineSuite.scala       |  5 +-
 .../tests/adversarial/UnicodeSuite.scala           | 42 ++++++++++++-
 11 files changed, 163 insertions(+), 30 deletions(-)

$ .claude/hooks/verify-tests-ran.sh --check
verify-tests-ran --check: OK (no uncommitted source change is newer than .claude/.last-test-run)
```

The two untracked files are new test suites (not in `--stat`). The diff above is the full set of changes for this
prerequisite: 11 modified files and 2 new files, under `modules/entity-resolution/**`, `modules/hadoop/**`
(tests and docs only), and `tests/**`.

## Runs, in order (all agent-run; the orchestrator cannot run sbt)

| # | Run by | Command | Exit | Completed | Result |
|---|---|---|---|---|---|
| 1 | entity-resolution-engineer | `sbt entityResolution/test` | 0 | (not reported) | 75 passed, 0 failed |
| 2 | entity-resolution-engineer | `sbt check` (overlapping runs; discarded by the agent) | 1 | - | output truncated |
| 3 | entity-resolution-engineer | `sbt check` (standalone, authoritative) | 1 | 2026-11-04 09:48:24 | 394 total, 392 passed, **2 failed**, 0 errors |
| 4 | hadoop-engineer | `sbt check` | 0 | 2026-11-04 09:52:13 | 426 passed, 0 failed |
| 5 | test-engineer | `sbt check` | 0 | 2026-11-04 10:00:53 | **443 passed, 0 failed** (final) |

Run 3 failures (fixed in `prereq-unicode-repair-01.md`):
- `scholarchain.hadoop.pipeline.LocalPipelineSuite` (`LocalPipelineSuite.scala:288`, literal `er-blocking.v1`)
- `scholarchain.hadoop.ShuffleAndParamsSuite` (`ShuffleAndParamsSuite.scala:166`, literal `er-blocking.v1`)

## Final run (run 5), verbatim summary lines as reported by test-engineer

```
[info] Passed: Total 252, Failed 0, Errors 0, Passed 252
[info] Passed: Total 75, Failed 0, Errors 0, Passed 75
[info] Passed: Total 67, Failed 0, Errors 0, Passed 67
[info] Passed: Total 0, Failed 0, Errors 0, Passed 0   (rag, root, services, sparkAnalytics, storage: "No tests to run")
[info] Passed: Total 49, Failed 0, Errors 0, Passed 49
[info] markTestRun: core: production sources=16, test sources=16, passed=252
[info] markTestRun: entityResolution: production sources=5, test sources=6, passed=75
[info] markTestRun: hadoop: production sources=18, test sources=8, passed=67
[info] markTestRun: rag/root/services/sparkAnalytics/storage: passed=0
[info] markTestRun: tests: production sources=0, test sources=8, passed=49
[info] markTestRun: wrote /Users/taufeeqpatel/Desktop/ScholarChain/.claude/.last-test-run (git_head=d16e8c9717f806f6f4018b56beb456382c5ed23a, worktree=dirty, suites=34, passed=443, skipped=0)
[success] Total time: 115 s (0:01:55.0), completed Nov 4, 2026, 10:00:53 AM
```

| Project | Passed | Failed | Errors | Ignored/skipped |
|---|---|---|---|---|
| core | 252 | 0 | 0 | 0 |
| entityResolution | 75 | 0 | 0 | 0 |
| hadoop | 67 | 0 | 0 | 0 |
| tests | 49 | 0 | 0 | 0 |
| rag, root, services, sparkAnalytics, storage | 0 | 0 | 0 | 0 |
| **Total** | **443** | **0** | **0** | **0** |

Baseline comparison: Phase 4 human gate recorded 407 (core 252, entityResolution 56, hadoop 67, tests 32).
Change: entityResolution +19, tests +17, total +36.

Note on the entityResolution count. ER-P5-00 reports `FormatCharactersSuite` with 18 tests plus 1 new
`MatchFeaturesSuite` test = 19 new tests, matching 56 -> 75. The `markTestRun` line shows "test sources=6"
for entityResolution: the five test files tracked at HEAD (four suites plus the `Samples.scala` fixture) plus the
new `FormatCharactersSuite.scala`. Not independently verified by the orchestrator.

## Observation status

The orchestrator did not observe any `sbt` output directly; the `sbt check` lines above are copied from agent
reports. The orchestrator did observe the marker check (`verify-tests-ran.sh --check` OK) and the git state above.
The final run's full output was ~27 MB and is not archived here (mostly Hadoop DEBUG/INFO logging).

## Regression sensitivity

No new test was executed against the pre-fix code. Regression sensitivity (tests marked [R] in the
test-engineer report) was established by hand-tracing the v1 behaviour, by the authoring agents and
independently by `code-reviewer`. See `prereq-unicode-review-report.md`.
