# Phase 06 BE-P6-07: build-engineer report, curated layout rules 5 and 6 "iff" (CR-P6-21 m1, n2 build side) (2026-11-17)

- Work order: routed under prompt-sp-p6-03-consolidated.md; human gate "Carry CR-P6-21 m1, n1, and n2 forward before any step reads curated data parts".
- STATUS returned: BLOCKED-ON human, for the audit and Stop-hook event below only. Change done; sbt check exit 0.

## Orchestrator observations

- `git status --porcelain`: the only new change is ` M project/SparkItRuns.scala`. No build.sbt, allowlist, pin, bound or other guard change.
- **Audit event (third occurrence), now also a Stop-hook refusal.** The full `sbt check` was moved to the background at 180 s; markTestRun wrote the marker at `2026-11-17T19:31:03.694219Z` after the call ended. build-engineer's next Bash call was refused (UNATTRIBUTED `.claude/.last-test-run`), and `verify-tests-ran.sh` (SubagentStop) refused its completion: "The test marker .claude/.last-test-run changed after the last audited Bash call of this session, without an audited 'sbt check' … Possible test-marker forgery". Marker content as quoted by the agent: `written_by=sbt markTestRun`, `git_head=c2d9a82…`, `git_worktree=dirty`, `suites=101`, `tests_passed=1457`, `tests_failed=0`, `tests_errored=0`, `tests_skipped=0`; consistent with the quoted sbt check output. The orchestrator's following `git status --porcelain` ran normally (baseline already reset). Raised to the human; work stopped at this point.

## Agent report (key fields)

Rule 5 (SparkItRuns.scala:869-880): round-r (r > 0) refused unless round-(r-1)/spill holds a non-empty part; for r + 1 < 4, spill parts in round-r without round-(r+1) refused (round-3 may hold spill parts). Producer: Pipeline.scala runBlocking :516-521, nonEmptyDirs :417-423, MaxBlockingRounds 4 = BlockingKeys.Levels.

Rule 6 (SparkItRuns.scala:889-903): join/joined parts without pairs/ refused; pairs/pairs parts without final/ refused (previously only the converse). Producer: Pipeline.scala runCandidates :646-666. Rule-mapping doc comment (:617-622) now says "if and only if".

New self-test cases: refused: joined parts without pairs/; pairs/pairs parts without final/; round-0/spill parts without round-1; round-2/spill parts without round-3; round-1 without round-0/spill parts; `.<X>.crc` directory (n2). Allowed: join and pairs only (pairs/pairs empty); four rounds with spill in round-3. The former allowed case "candidates join only" (itself a truncated layout) is now a refused case.

Archive conformance (Read only, inferred, not by running the preflight): pinned curated run has blocking/round-0 only, no round-0/spill (counters level-0 only); candidates/join/joined, pairs/pairs and final present, CANDIDATE_PAIRS=374, EMITTED=374. Satisfies the tightened rules.

TEST RESULTS (sbt check, quoted from the background output):

```
[info] verifySparkItGuards: PASS: 204 cases gave the expected verdict
[info] verifySparkItRunner: PASS: 8 cases gave the expected verdict with the real runner on the SparkIT classpath (JUnit /Users/taufeeqpatel/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar); scratch target/sparkit-runner-check/ cleaned
[info] Passed: Total 621, Failed 0, Errors 0, Passed 621
[info] Passed: Total 464, Failed 0, Errors 0, Passed 464
[info] Passed: Total 85, Failed 0, Errors 0, Passed 85
[info] Passed: Total 142, Failed 0, Errors 0, Passed 142
[info] Passed: Total 145, Failed 0, Errors 0, Passed 145
[info] markTestRun: wrote /Users/taufeeqpatel/Desktop/ScholarChain/.claude/.last-test-run (git_head=c2d9a8216b945f2dd57eb587099f0b938ed1d670, worktree=dirty, suites=101, passed=1457, skipped=0)
[success] Total time: 165 s (0:02:45.0), completed Nov 17, 2026, 1:31:03 PM
```

(rag, root, services, storage: 0 tests.) Also verifyHeldoutGuards PASS (23 launch + 17 clean-tree cases), verifyErDevRunGuards PASS (128 cases). verifySparkItGuards went from 197 to 204 cases.

RISKS (as reported): until spark-engineer makes the same change in HadoopCuratedRun.scala:317-353 the preflight is stricter than the reader (fails closed); RoundName index above Int range throws (fails closed); n1 and the n2 reader branches remain spark-engineer's.
