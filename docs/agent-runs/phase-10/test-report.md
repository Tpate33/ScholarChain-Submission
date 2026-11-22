# Phase 10 test report (orchestrator assembly)

Records state only; approves nothing.

## CURRENT (FINAL): SE-P10-06 repair 05 `sbt check` (service-engineer)
Observed by the orchestrator in the run's output file (shell b91m6puid, started in foreground, moved to background after 180 s): `[exited with code 0]`, `[success] Total time: 410 s (0:06:50.0), completed Nov 22, 2026, 10:05:35 AM`.
- core 621/0; entityResolution 464/0; hadoop 85/0; rag 132/0; root 0 ("No tests to run for root / Test / test"); services "Passed: Total 99, Failed 0, Errors 0, Passed 99"; sparkAnalytics 263/0; storage 437/0; tests "Passed: Total 231, Failed 0, Errors 0, Passed 230, Skipped 1".
- markTestRun: `wrote .claude/.last-test-run (git_head=3d9da3e6d8ef22e1d6f4bcfee1417350af25d758, worktree=dirty, suites=194, passed=2331, skipped=1)`; services production sources=13, test sources=13.
- Marker read: written_at_utc=2026-11-22T16:05:35.223980Z, suites=194, passed=2331, failed=0, errored=0, skipped=1, production_sources=193. Matches. Agent states no file changed after the run started. No code-review round after this run (human decision); human marker review pending.

## Superseded: SE-P10-05 repair 04 `sbt check` (service-engineer)
Observed by the orchestrator in the run's output file (background shell bv1udyol6): `[exited with code 0]`, `[success] Total time: 421 s (0:07:01.0), completed Nov 22, 2026, 9:43:18 AM`.
- core 621/0; entityResolution 464/0; hadoop 85/0; rag 132/0; root 0; services "Passed: Total 97, Failed 0, Errors 0, Passed 97"; sparkAnalytics 263/0; storage 437/0; tests "Passed: Total 231, Failed 0, Errors 0, Passed 230, Skipped 1".
- markTestRun: `wrote .claude/.last-test-run (git_head=3d9da3e6d8ef22e1d6f4bcfee1417350af25d758, worktree=dirty, suites=193, passed=2329, skipped=1)`; services production sources=13, test sources=12.
- Marker read: written_at_utc=2026-11-22T15:43:18.817155Z, suites=193, passed=2329, failed=0, errored=0, skipped=1, production_sources=193. Matches. Agent states no file changed after the run started. Agent returned BLOCKED-ON human only for the R1-b marker attribution (backgrounded run); human marker review pending.

## Superseded: SE-P10-04 repair 03 `sbt check` (service-engineer)
Observed by the orchestrator in the run's output file (background shell bkr6qqk2d): `[exited with code 0]`, `[success] Total time: 405 s (0:06:45.0), completed Nov 22, 2026, 9:19:46 AM`.
- core 621/0; entityResolution 464/0; hadoop 85/0; rag 132/0; root 0; services "Passed: Total 92, Failed 0, Errors 0, Passed 92"; sparkAnalytics 263/0; storage 437/0; tests "Passed: Total 231, Failed 0, Errors 0, Passed 230, Skipped 1".
- markTestRun: `wrote .claude/.last-test-run (git_head=3d9da3e6d8ef22e1d6f4bcfee1417350af25d758, worktree=dirty, suites=192, passed=2324, skipped=1)`; services production sources=13, test sources=11.
- Marker read: written_at_utc=2026-11-22T15:19:46.292496Z, suites=192, passed=2324, failed=0, errored=0, skipped=1, production_sources=193. Matches. Agent states no file changed after the run started. Backgrounded; human marker review pending.

## Superseded: SE-P10-03 repair 02 + entry points `sbt check` (service-engineer)
Observed by the orchestrator in the run's output file (background shell bhfl2ttwd): `[exited with code 0]`, `[success] Total time: 403 s (0:06:43.0), completed Nov 22, 2026, 12:55:30 AM`.
- core 621/0; entityResolution 464/0; hadoop 85/0; rag 132/0; root 0; services "Passed: Total 81, Failed 0, Errors 0, Passed 81"; sparkAnalytics 263/0; storage 437/0; tests "Passed: Total 231, Failed 0, Errors 0, Passed 230, Skipped 1".
- markTestRun: `wrote .claude/.last-test-run (git_head=3d9da3e6d8ef22e1d6f4bcfee1417350af25d758, worktree=dirty, suites=191, passed=2313, skipped=1)`; services production sources=13, test sources=10.
- Marker read: written_at_utc=2026-11-22T06:55:30.450676Z, suites=191, passed=2313, failed=0, errored=0, skipped=1, production_sources=193. Matches. Agent states no file changed after the run started (00:48:09 local). Backgrounded; Stop hook R1-b refusal; human marker review pending.

## Superseded: SE-P10-02 repair 01 `sbt check` (service-engineer)
Observed by the orchestrator in the run's output file (background shell b8ciu4pgn): `[exited with code 0]`, `[success] Total time: 420 s (0:07:00.0), completed Nov 22, 2026, 12:31:33 AM`.
- core 621/0; entityResolution 464/0; hadoop 85/0; rag 132/0; root 0; services "Passed: Total 61, Failed 0, Errors 0, Passed 61"; sparkAnalytics 263/0; storage 437/0; tests "Passed: Total 231, Failed 0, Errors 0, Passed 230, Skipped 1".
- markTestRun: `wrote .claude/.last-test-run (git_head=3d9da3e6d8ef22e1d6f4bcfee1417350af25d758, worktree=dirty, suites=189, passed=2293, skipped=1)`; services production sources=12, test sources=8.
- Marker read: written_at_utc=2026-11-22T06:31:33.109969Z, suites=189, passed=2293, failed=0, errored=0, skipped=1, production_sources=192. Matches. Written after the agent's audited call returned (backgrounded). Orchestrator reset call: first `git status --porcelain` refused UNATTRIBUTED (.claude/.last-test-run), rerun succeeded with Phase 10 paths only.
- Human (2026-11-22): marker and git status checked; 189 suites, 2293 passed, 0 failed, 0 errored, 1 skipped, 2026-11-22T06:31:33.109969Z, HEAD 3d9da3e; changed paths match the reported Phase 10 work. Instruction: no rerun solely for the audit pause.

## Superseded: SE-P10-01 `sbt check` run 2 (service-engineer, after the QueryApiSuite race fix)
Observed by the orchestrator in the run's output file (harness background task br3fr3nb7): `[exited with code 0]`, `[success] Total time: 409 s (0:06:49.0), completed Nov 22, 2026, 12:08:02 AM`.
- core 621/0; entityResolution 464/0; hadoop 85/0; rag 132/0; root 0 ("No tests to run for root / Test / test"); services "Passed: Total 46, Failed 0, Errors 0, Passed 46"; sparkAnalytics 263/0; storage 437/0; tests "Passed: Total 231, Failed 0, Errors 0, Passed 230, Skipped 1".
- markTestRun: `wrote .claude/.last-test-run (git_head=3d9da3e6d8ef22e1d6f4bcfee1417350af25d758, worktree=dirty, suites=188, passed=2278, skipped=1)`; services production sources=12, test sources=7.
- Marker read: written_at_utc=2026-11-22T06:08:02.667059Z, suites=188, passed=2278, failed=0, errored=0, skipped=1, production_sources=192.
- The marker was written after service-engineer's audited Bash call returned (backgrounded run). The orchestrator's Stop hook refused completion (R1-b) and its next Bash call was refused as UNATTRIBUTED (.claude/.last-test-run, owner GENERATED); baseline reset. Human marker review required.
- Human (2026-11-22): marker and git status checked; 188 suites, 2278 passed, 0 failed, 0 errored, 1 skipped, written 2026-11-22T06:08:02.667059Z against HEAD 3d9da3e. Instruction: do not rerun the unchanged build solely because of the audit pause.

## Superseded: SE-P10-01 `sbt check` run 1
Exit 1, 422 s. services Total 46, Failed 1, Passed 45: "idempotent async request replays the same job; an in-flight sync key is 409 in-progress" (QueryApiSuite.scala:271), a test race, fixed with an `entered` latch. markTestRun `sbt.TestsFailedException: Tests unsuccessful`; marker not refreshed.

## Superseded: BE-P10-01 `sbt check` (build-engineer)
Exit 0, 446 s; 183 suites, 2232 passed, 0 failed, 1 skipped; marker 2026-11-22T05:30:24Z (reviewed per human instruction to continue).

No test-engineer run in Phase 10 so far (adversarial/cross-module testing is Phase 11).
