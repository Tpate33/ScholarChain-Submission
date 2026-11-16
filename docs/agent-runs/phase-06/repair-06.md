# Phase 06 repair 06: ER-P6-08, pin the dev run manifests (CR-P6-09 MAJ-1)

- Work order (verbatim): docs/agent-runs/phase-06/prompt-er-p6-08-manifest-pin.md
- Finding routed: CR-P6-09 MAJ-1 (entity-resolution-engineer)
- Implementation commit X: 8c1bee042e7dca3119f5116df2150e0bee204598
- Carried forward by the human: CR-P6-10 minor 1 and minor 2 (build-engineer)

## Orchestrator check after the repair (2026-11-16)

`git status --porcelain`:
```
 M modules/entity-resolution/local-runs/er-decisions.dev.run-manifest.json
 M modules/entity-resolution/local-runs/er-grouping.dev.run-manifest.json
?? docs/agent-runs/phase-06/prompt-er-p6-08-manifest-pin.md
```
`git diff` of the two manifests: decision manifest changes only `codeVersion` (426df590… to 8c1bee04…); grouping manifest changes only `codeVersion` (same) and `parameters.decisionRunId` (run:76ec32b6…7e7b to run:736cb170…b76a). Both configDigests, inputRunId, timestamps, versions, inputSnapshots and attempt are byte-identical.

## Agent report (entity-resolution-engineer, 2026-11-16), verbatim

```text
STATUS: COMPLETE
OWNER: entity-resolution-engineer
WORK ORDER: ER-P6-08 (Phase 6). Fix CR-P6-09 MAJ-1: pin the two dev run manifests to the implementation commit 8c1bee042e7dca3119f5116df2150e0bee204598. `sbt check` passes (1117 tests, 0 failed) and only the two manifests changed.

FILES CREATED: none

FILES MODIFIED:
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/local-runs/er-decisions.dev.run-manifest.json (codeVersion only)
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/local-runs/er-grouping.dev.run-manifest.json (codeVersion and parameters.decisionRunId only)

COMMANDS RUN:
1. `git rev-parse HEAD`: printed 8c1bee042e7dca3119f5116df2150e0bee204598, which matches X.
2. `git status --porcelain` (before editing): showed only `?? docs/agent-runs/phase-06/prompt-er-p6-08-manifest-pin.md`, which your clarification says is expected.
3. Refused by the guard (no-allowlist-entry), nothing executed:
   - `cat` of the two manifests. I used Read instead.
   - `sbt entityResolution/testOnly scholarchain.entityresolution.review.DevRunInputsSuite`.
4. `sbt entityResolution/test`, run after editing both codeVersions to read the new decision run ID from the suite. It exited 1 as expected.
   - The grouping-manifest test failed at DevRunInputsSuite.scala:241.
   - The end-to-end test failed at :454 with this message: "parameter decisionRunId is run:76ec32b6..., but the verified decision run's ID is run:736cb1706210a34f0958e658c061054c4e6b27b80847528db8619da84495b76a".
   - The decision-manifest test passed with the new codeVersion.
   - The expected grouping text the suite printed (Json.write output) matched the file in everything except decisionRunId.
5. `sbt check`, the final command after the last edit. It ended with `[success] Total time: 134 s`.
6. `git status --porcelain` and `git diff --stat`, run after sbt check (results below).

TEST RESULTS (from the observed `sbt check` output):
| Project | Passed | Failed / Errors |
|---|---|---|
| core | 423 | 0 |
| entityResolution | 464 | 0 |
| hadoop | 85 | 0 |
| tests | 145 | 0 |
| rag, root, services, sparkAnalytics, storage | 0 (no tests) | 0 |

- markTestRun line: "wrote .claude/.last-test-run (git_head=8c1bee042e7dca3119f5116df2150e0bee204598, worktree=dirty, suites=84, passed=1117, skipped=0)".
- DevRunInputsSuite: the check output was 226847 lines and I have no grep tool here, so I did not pull out the suite's own per-test lines. It is part of entityResolution's 464 passed with 0 failed. In step 4 (before the decisionRunId edit) its first four tests passed and the grouping and end-to-end tests failed only because of the decisionRunId mismatch, which is what the last edit fixed.

VALIDATION RESULTS:
Old and new values:
| Value | Old | New |
|---|---|---|
| Decision codeVersion | 426df590f3f043c96021b739dd7f39436dd30956 | 8c1bee042e7dca3119f5116df2150e0bee204598 |
| Decision run ID (RunId.of) | run:76ec32b67bb0be98a040c0bad751bcd987ef0fbd7085dca74612d877b6b47e7b | run:736cb1706210a34f0958e658c061054c4e6b27b80847528db8619da84495b76a |
| Grouping codeVersion | 426df590f3f043c96021b739dd7f39436dd30956 | 8c1bee042e7dca3119f5116df2150e0bee204598 |
| Grouping decisionRunId | run:76ec32b67bb0be98a040c0bad751bcd987ef0fbd7085dca74612d877b6b47e7b | run:736cb1706210a34f0958e658c061054c4e6b27b80847528db8619da84495b76a |
| Grouping run ID | not observed | not observed |

- The old decision run ID was the old grouping manifest's decisionRunId, and the suite at X enforced that equality. The new one is the suite's own RunId.of output from step 4.
- Grouping run ID: neither manifest stores its own run ID. It is derived by RunId.of whenever the file is read, so changing codeVersion and decisionRunId recomputes it. The suite asserts it in the end-to-end and ER-P6-07 tests, which pass, but never prints it on success. I did not add temporary code to print it, per item 4.
- Both configDigests are unchanged: decision 61655dc122d9317924cb58b67fa7fbd176349e8ea13401c17af2f865f9bbe085, grouping 484845939b9f036bb5ff460a8b0a856cb1b731b5a2a080a1737168faa0026298.
- inputRunId (run:2881724b...8baa), decidedAt, assignedAt, the policy and feature versions, inputSnapshots and attempt are unchanged. Both files keep the compact Json.write form with one final LF.
- `git status --porcelain` after sbt check:
  ```
   M modules/entity-resolution/local-runs/er-decisions.dev.run-manifest.json
   M modules/entity-resolution/local-runs/er-grouping.dev.run-manifest.json
  ?? docs/agent-runs/phase-06/prompt-er-p6-08-manifest-pin.md
  ```
- `git diff --stat`: the two manifests, "2 files changed, 2 insertions(+), 2 deletions(-)".
- Under build.sbt, project, modules/core and modules/entity-resolution, the only changes are the two manifests.
- Scope for commit Y: exactly these two files. The untracked prompt file is yours as evidence and outside the code scope; whether it goes into Y is the human's call.
- Nothing about policy, features, thresholds, Resolver, outcomes, grouping rules, er-dev.conf, source, tests or Phase 5 evidence changed. No goldSetEvalDev run was needed. No held-out path was read and no gold/ or target/ search was made.

ASSUMPTIONS:
- The manifests have no run-ID field. "Recompute that manifest's run ID" therefore means the derived RunId.of value changes with codeVersion; the decision value was taken from the suite's output.
- The untracked prompt file is not a dirty tree, per your clarification.

RISKS:
- The new grouping run ID was not printed, so it is verified only by the passing suite. If the evidence needs the literal value, it has to be computed by something allowed to print it, for example a printing test in a later work order.
- The pin holds only if Y changes nothing in scope other than these two manifests. Any other in-scope change would make the dev task's code-scope check refuse.

MANUAL ACTION REQUIRED: The human reviews the diff, then stages and commits exactly the two manifests as commit Y and records the hash. The dev command `sbt erDecisionGroupingDev` stays inactive pending ratification; I did not run it.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer re-check of CR-P6-09 MAJ-1 on the Y diff, then the human commits Y.
```
