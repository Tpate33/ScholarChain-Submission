# Phase 5 prerequisite (Unicode format characters): repair 01

> **PREREQUISITE WORK ONLY. THIS IS NOT COMPLETION OF PHASE 5.**

## Trigger

`entity-resolution-engineer` (ER-P5-00) returned `STATUS: BLOCKED-ON`, `BLOCKED-ON OWNER: hadoop-engineer`.
Its standalone `sbt check` exited 1 (2026-11-04 09:48:24): 394 tests, 392 passed, 2 failed, 0 errors.

## Findings routed

| # | File:line | Failure (verbatim) | Owner |
|---|---|---|---|
| 1 | `modules/hadoop/src/test/scala/scholarchain/hadoop/pipeline/LocalPipelineSuite.scala:288` | `assert(allBlocks.forall(_.blockingVersion == "er-blocking.v1"))` -> `munit.FailException ... assertion failed` | `hadoop-engineer` |
| 2 | `modules/hadoop/src/test/scala/scholarchain/hadoop/ShuffleAndParamsSuite.scala:166` | `assertEquals(src.version, "er-blocking.v1")` -> `munit.ComparisonFailException`, obtained `er-blocking.v2` | `hadoop-engineer` |
| 3 | `modules/hadoop/docs/job-contracts.md:92, :95, :303` | stale `er-blocking.v1` / `er-features.v1` prose (no test failure) | `hadoop-engineer` |

Cause: the version bump `er-blocking.v1` -> `er-blocking.v2` (required by the provenance invariant because
blocking-key outputs change) broke two test literals that hard-coded the old value. No production defect.

The human prompt named only `entity-resolution-engineer` and `test-engineer`. The orchestrator routed this
dependency to `hadoop-engineer` because the files are in its territory (`docs/agents.md`, path ownership:
`modules/hadoop/**`), following the BLOCKED-ON routing rule. Work order: HD-P5-00a
(`prereq-unicode-work-orders.md`).

## Change summary (from hadoop-engineer's report)

- `LocalPipelineSuite.scala:288`: compares with `EntityResolution.blockingKeysVersion` (not weakened: still
  fails if any block carries a different version than the production key source).
- `ShuffleAndParamsSuite.scala:166`: compares with `EntityResolution.blockingKeysVersion` plus one literal pin
  `"er-blocking.v2"`.
- `job-contracts.md:92, 95, 303`: v1 -> v2; level count 4 confirmed against `BlockingKeys.Levels`.
- Intentional mismatch probes `er-blocking.v0` / `er-features.v0` unchanged (now `LocalPipelineSuite.scala:649, 654`).
- No production Hadoop code changed.

## Rerun evidence

`hadoop-engineer`, `sbt check`, foreground, exit success, completed 2026-11-04 09:52:13:

```
[info] Passed: Total 252, Failed 0, Errors 0, Passed 252
[info] Passed: Total 75, Failed 0, Errors 0, Passed 75
[info] Passed: Total 67, Failed 0, Errors 0, Passed 67
[info] Passed: Total 32, Failed 0, Errors 0, Passed 32
[info] markTestRun: wrote /Users/taufeeqpatel/Desktop/ScholarChain/.claude/.last-test-run (git_head=d16e8c9717f806f6f4018b56beb456382c5ed23a, worktree=dirty, suites=33, passed=426, skipped=0)
```

426 passed, 0 failed. Full report: `prereq-unicode-agent-report.md`, section 2. The code-reviewer later grepped
the repository (excluding `docs/agent-runs/**`) and found `er-blocking.v1` / `er-features.v1` only in history
comments (`prereq-unicode-review-report.md`).

## Session note

While the test marker was stale (between ER-P5-00 and HD-P5-00a), the Stop hook `verify-tests-ran.sh`
refused orchestrator completion repeatedly. The orchestrator has no `sbt` allowlist row and did not touch the
marker; the refusals cleared when `hadoop-engineer`'s `sbt check` passed and `markTestRun` wrote the marker.
