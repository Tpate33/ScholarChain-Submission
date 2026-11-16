# Phase 06 repair 04: ER-P6-05 repair 01

- Work order (verbatim): docs/agent-runs/phase-06/prompt-er-p6-05-repair-01-and-dev-run.md, paragraph 2
- Findings routed: CR-P6-07 minor finding 1 (curated provenance), nit 2 (feature/blocking versions), nit 3 (group-size diagnostic wording)
- Owner: entity-resolution-engineer

## Agent report (entity-resolution-engineer, 2026-11-15), verbatim

ER-P6-05 repair 01 is done. I fixed the minor finding and both nits from CR-P6-07, and the final `sbt check` passed: 1101 tests passed, 0 failed, 0 skipped, 83 suites (1097 before, plus 4 new tests).

**1. Minor finding: curated data not tied to the recorded curated run (fixed).**
- `GroupingInputs.candidateIds: Vector[String]` is now `candidates: Vector[CuratedCandidate]`. That new case class holds the candidate ID and its provenance and is built with `CuratedCandidate.of(MatchCandidate)`.
- Before anything is published, `Grouping.manifestProblems` now refuses the run if any curated candidate, work, mention or profile has a `provenance.runId` other than the curated run ID. That ID is already checked to equal the decision manifest's `inputRunId`.
- I reused the decision exporter's check instead of copying it. `DecisionExport.scala:251-254` became a shared function, `DecisionExport.foreignProvenance`, plus `candidateLabel`. `decideAll` calls it with the same candidates and produces exactly the same message as before, so its behaviour is unchanged.
- The decisions package imports nothing new and has no review references. `LeakageStructureSuite` was not edited and passes.
- On a mismatch, `group` returns `Left` and `GroupingRunMain` exits with code 2. The regression test confirms the assignment root stays empty.
- Mentions are checked too, which goes slightly beyond the finding. The real Hadoop sample output passes this check: the probe ran and published.

**2. Nit: feature and blocking versions (done).** The decision manifest's `featureVersion` must equal both the effective policy's `featureVersion` and `MatchFeatures.Version`. Its `blockingVersion` must equal both the policy's `blockingVersion` and `BlockingKeys.Version`. Any mismatch refuses the run.

**3. Nit: group-size diagnostic (done).** The line now reads `unevaluated researcher groupSizes=<histogram> (identified groups only; unresolved members excluded: <n>)`. It keeps the `unevaluated` label.

Phase 5 policy, features, thresholds, Resolver logic, decision outcomes and evidence are unchanged, and so are grouping rules O1-O11. No core change was needed.

**Changed regions, for the reviewer (file:line-range after the change)**

Main code:
- `modules/entity-resolution/src/main/scala/scholarchain/entityresolution/decisions/DecisionExport.scala`
  - 33: imports `Provenance`.
  - 228-246: new `candidateLabel` and the shared `foreignProvenance` check.
  - 271-276: `decideAll` calls the shared check; same message as before.
- `modules/entity-resolution/src/main/scala/scholarchain/entityresolution/grouping/Grouping.scala`
  - 18: imports `MatchCandidate`.
  - 85-108: `GroupingInputs` scaladoc, and the `candidates` field replaces `candidateIds`.
  - 110-120: new `CuratedCandidate` and `CuratedCandidate.of`.
  - 242-250: `manifestProblems` scaladoc.
  - 310-325: feature and blocking version checks (nit 2).
  - 343-344: calls `provenanceProblems` (finding 1).
  - 348-364: new `decidedVersion`.
  - 366-384: new `provenanceProblems`, which delegates to `DecisionExport.foreignProvenance`.
  - 524-531: the coverage check in `decisionProblems` now uses `in.candidates`.
  - 1388-1391: diagnostic wording (nit 3).
- `modules/entity-resolution/src/main/scala/scholarchain/entityresolution/review/GroupingRunMain.scala`
  - 13: imports `CuratedCandidate`.
  - 41-44: scaladoc.
  - 186: passes `run.candidates.map(CuratedCandidate.of)`.

Tests:
- `modules/entity-resolution/src/test/scala/scholarchain/entityresolution/grouping/GroupingFixtures.scala`
  - 60-77: `decisionManifest` takes a `set` override.
  - 79-101: new `curatedProvenance`, `ofRun` and `profileOfRun`.
  - 141-193: the work builders stamp the curated run ID.
  - about 217: `openAlexProfile` stamps the curated run ID.
  - 318-335: `inputs` takes `decisionSet` and builds `candidates`.
- `modules/entity-resolution/src/test/scala/scholarchain/entityresolution/grouping/GroupingSuite.scala`
  - 5: imports `DecisionExport`.
  - 133-138: the existing coverage case now uses `CuratedCandidate`.
  - 161-219: new mixed-runs test. Works with their mentions, only one work's mentions, profiles, candidates and a null candidate provenance are each refused; the base inputs group successfully.
  - 221-248: new feature/blocking version mismatch test, covering the decision run's values and an effective-policy mismatch.
  - 250-261: new test that pins the exact diagnostic wording (`2:1`, `unresolved members excluded: 1`).
- `modules/entity-resolution/src/test/scala/scholarchain/entityresolution/grouping/GroupingExportSuite.scala`
  - 325-343: `writeCurated` takes the run ID for works and profiles and stamps it.
  - 468-477: `synthetic` passes those run IDs through.
  - 479-501: new entry-point test. The curated manifest names run R while the works (and, separately, the profiles) carry another run ID; the decision export still publishes, grouping exits 2 with the reason, and nothing is published.

Each new test fails without its fix. They use the new API (`CuratedCandidate`, `ofRun`, `decisionSet`, `foreignProvenance`), so they do not compile without it. Without the checks, the mixed-run inputs would group and publish.

```text
STATUS: COMPLETE
OWNER: entity-resolution-engineer
WORK ORDER: ER-P6-05 repair 01 (Phase 6): CR-P6-07 minor finding 1 (curated provenance), nit 2 (feature/blocking versions), nit 3 (group-size diagnostic wording)
FILES CREATED: none
FILES MODIFIED: /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/decisions/DecisionExport.scala; /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/grouping/Grouping.scala; /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/review/GroupingRunMain.scala; /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/grouping/GroupingFixtures.scala; /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/grouping/GroupingSuite.scala; /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/grouping/GroupingExportSuite.scala
COMMANDS RUN: git ls-files --others --exclude-standard (twice, path listing); git ls-files (tracked module files); sbt entityResolution/test (while iterating: Passed: Total 448, Failed 0, Errors 0); sbt check (once, final command after the last edit). Two other shapes were refused by the guard and nothing ran: ls, and a quoted git grep.
TEST RESULTS: sbt check [success], Total time 132 s. Per project: core 423, entityResolution 448, hadoop 85, tests 145, rag/root/services/sparkAnalytics/storage 0 (no tests). Every project Failed 0, Errors 0. markTestRun: suites=83, passed=1101, skipped=0 (git_head=426df590f3f043c96021b739dd7f39436dd30956, worktree=dirty). New tests passed: GroupingSuite "CR-P6-07 finding 1 ... (mixed runs)", "CR-P6-07 nit 2 ...", "CR-P6-07 nit 3 ..."; GroupingExportSuite "CR-P6-07 finding 1 (entry point) ...". All existing tests passed, including LeakageStructureSuite and the committed-sample probe.
VALIDATION RESULTS: The committed-sample probe ran (not skipped) on the Hadoop dev test output (input run 9c5c86b3..., decision run ce17b72e..., grouping run 9fa90864...) and published. So the real curated works, mentions, profiles and candidates carry the curated manifest's run ID. Its diagnostics are DEV and unevaluated: "unevaluated researcher groupSizes=1:5,2:1,3:24,4:6 (identified groups only; unresolved members excluded: 0)". The other counts match CR-P6-07 item 7: researcher=103, work=13, authorship=65, team=4, links merged=74, redundant=43, vetoed=0, unresolved 0 of 103, ST=0. goldSetEvalDev was not run (not named in the work order). No held-out evaluation was run or read.
ASSUMPTIONS: The required run ID is the curated manifest's RunId, which manifestProblems already requires to equal the decision manifest's inputRunId (otherwise refused), so the two are the same value. Hadoop stamps mentions with the same run ID as their works; the probe passes under this. Passing a CuratedCandidate (ID plus provenance) instead of the full MatchCandidate satisfies "the candidates, or the data needed for the check". Synthetic fixtures were restamped with the curated run ID, test data only.
RISKS: DecisionExport.decideAll still checks only candidate provenance, not works or profiles. Its behaviour was kept unchanged on purpose, so a decision run can still be published from a mixed curated run; grouping now refuses such a run. Extending decideAll is a possible follow-up for a separate work order. On full-DBLP data a mixed run gives one reason per record; the entry point prints the count and at most 20 reasons. The probe still skips silently through assume() when the Hadoop test output is missing (CR-P6-07 unverified concern, unchanged).
MANUAL ACTION REQUIRED: Orchestrator: request code-reviewer review of this repair diff only (regions listed above). Human: review and accept; commit the untracked files.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer review of the ER-P6-05 repair 01 diff; then human acceptance
```
