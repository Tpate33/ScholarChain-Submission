# HD-P5-05 final report (hadoop-engineer; verbatim)

Archived by the orchestrator, 2026-11-14. Work order: fix CR-P5-05 HD-1 (decisions-20).

---

I fixed HD-1. `checkManifest` now refuses, with exit 2 and before any job runs, a run manifest that lists any source more than once in `inputSnapshots`, whatever the snapshot values. `sbt check` passed in the foreground twice: 922 tests, 0 failed. The dev and held-out run IDs are unchanged.

**The fix** (`modules/hadoop/src/test/scala/scholarchain/hadoop/local/ErSplitPipeline.scala`)
- After decoding, `checkManifest` groups `inputSnapshots` by source. If any source repeats, it returns `Left("manifest <file> lists source(s) <tags> more than once in inputSnapshots (each source must appear exactly once: <tag>=<snap>,...)")` before any other check.
- The other checks are unchanged and moved into a new private `checkUniqueManifest`, which only ever sees a source-unique list.
- The later lookups therefore all see one entry per source: the summary's `expectedSnap .toMap` and `RunLayout.of`'s `.find` (Pipeline.scala:124), the latter reached through `Pipeline.run` and `summarize`. I did not edit Pipeline.scala or any other production code.

**Core decoding behaves as the finding says.** It accepts the four cases that differ in snapshot and already rejects the exact duplicate pair. From the run:
```
[er-split-duplicate-source] case=openalex-bulk-then-api coreDecode=accepted
[er-split-duplicate-source] case=openalex-api-then-bulk coreDecode=accepted
[er-split-duplicate-source] case=dblp-two-snapshots coreDecode=accepted
[er-split-duplicate-source] case=dblp-two-snapshots-reversed coreDecode=accepted
[er-split-duplicate-source] case=openalex-exact-duplicate coreDecode=rejected:inputSnapshots: contains duplicate values
```

**New test** in `ErSplitPipelineSuite`: "duplicate-source guard (HD-1): a source listed twice is refused (exit 2), any snapshots"
- It covers openalex bulk then API, openalex API then bulk, dblp with two snapshots in both orders, and the exact duplicate.
- Each case calls `checkManifest` directly first, so a regression fails there and never starts a pipeline run. It then calls `ErSplitPipeline.run` and expects exit 2, a refusal line, and no output directory created.
- For the four cases core accepts, the test also requires the "more than once in inputSnapshots" message.
- Control: both committed manifests have one entry per source, and `checkManifest` returns their own `RunId.of`. As before, only the held-out manifest and the ledgers are read, never held-out samples or output.
- Without the change, bulk-then-API and dblp `dblp-2026-09-01`-then-token pass the old last-entry guard. API-then-bulk and token-then-wrong were refused, but with a different message, so all four cases fail without the fix.

```text
STATUS: COMPLETE
OWNER: hadoop-engineer
WORK ORDER: HD-P5-05 (Phase 5; decisions-20.md; finding HD-1 in review-report-cr-p5-05.md; prior work report-hd-p5-04.md). Fix: checkManifest refuses (exit 2, before any job) a run manifest that lists any source more than once; later lookups work on a source-unique list.
FILES CREATED: none
FILES MODIFIED:
  /Users/taufeeqpatel/Desktop/ScholarChain/modules/hadoop/src/test/scala/scholarchain/hadoop/local/ErSplitPipeline.scala (duplicate-source guard in checkManifest; existing checks moved unchanged into private checkUniqueManifest; doc comment)
  /Users/taufeeqpatel/Desktop/ScholarChain/modules/hadoop/src/test/scala/scholarchain/hadoop/local/ErSplitPipelineSuite.scala (one new test: 5 duplicate cases plus control on both committed manifests)
  /Users/taufeeqpatel/Desktop/ScholarChain/modules/hadoop/docs/job-contracts.md (guard list and test-fixture list in "Local per-split pipeline")
  (The local/ test files are untracked since HD-P5-02, so git shows them as untracked, not modified.)
  Not modified: Pipeline.scala and all other production code, the committed run manifests, codeVersion, modules/core/**, amendment-fe-p5-06.md.
COMMANDS RUN:
  git status --short
  git ls-files --others --exclude-standard -- modules/hadoop
  git diff --stat -- modules/hadoop/docs/job-contracts.md
  git diff --unified=0 -- modules/hadoop/docs/job-contracts.md
  sbt check (foreground, after the code change; success, 131 s)
  sbt check (foreground, again after the doc edit; success, 130 s; the results below are from this final run)
  Not run: sbt localPipelineHeldout, localPipelineDev, goldSetEvalHeldout, goldSetEvalDev, or anything that reads or writes held-out output.
TEST RESULTS (final sbt check):
  "Passed: Total 299, Failed 0, Errors 0, Passed 299" (core)
  "Passed: Total 393, Failed 0, Errors 0, Passed 393" (entityResolution)
  "Passed: Total 85, Failed 0, Errors 0, Passed 85" (hadoop; was 84)
  "Passed: Total 145, Failed 0, Errors 0, Passed 145" (tests)
  rag, root, services, sparkAnalytics, storage: Total 0 ("No tests to run")
  "[info] markTestRun: wrote /Users/taufeeqpatel/Desktop/ScholarChain/.claude/.last-test-run (git_head=36ffcf95e585c7def6aa06835a784820936e1b60, worktree=dirty, suites=76, passed=922, skipped=0)"
  "[success] Total time: 130 s (0:02:10.0), completed Nov 14, 2026, 6:03:17 PM"
VALIDATION RESULTS (verbatim from the final sbt check):
  [info] verifyHeldoutOutputRoute: scanned 11 sbt target directories (modules/core/target, modules/entity-resolution/target, modules/hadoop/target, modules/rag/target, modules/services/target, modules/spark/target, modules/storage/target, project/project/target, project/target, target, tests/target): 4402 files, 102440102 bytes; 1 symbolic links not followed; 1 excluded data/scratch directories not read (modules/hadoop/target/local-pipeline, target/local-runs/heldout)
  [info] verifyHeldoutOutputRoute: counter-test PASS: control marker (sbt task log route) found in target/streams/_global/heldoutRouteProbe/_global/streams/out, target/global-logging/sbt-global-log2431912915888521223.log
  [info] verifyHeldoutOutputRoute: PASS: held-out-route marker sha256=393b9a8b3f81890f45edac6207c1a8762027d531535346306bfc774a0168fdc2 found in 0 of 4402 sbt-persisted files; it was printed by the child straight to the terminal
  [info] verifyHeldoutGuards: PASS: 23 synthetic launch cases and 17 temporary-repository clean-tree cases gave the expected verdict
  Run IDs, from the suite's lines (in modules/hadoop/target/test-work/er-split-report.txt), identical to report-hd-p5-04.md:
  [er-split-manifest] split=dev runId=run:9a6d688f364032e542b77cede7c3fef370603064bbbfacdbf0f7be48625b0b62 expected.inputDigest=97897d97c73fcdbb2e749e1d665247a97f58632d840766c00ff52e450f04ad7d expected.configDigest=179e02020754eb95b9abc855d55d8209c3e8baa2b46d4696a0b9a857a204ca40
  [er-split-manifest] split=heldout runId=run:63574417f7d6c31e23cebd6ef45ff23468f4dd1e7be50e5b5c89209be673ccef expected.inputDigest=bf26d518de202a48cbafc631f547bf69d3dc5bc2f86f3825f2fb7cd533b94731 expected.configDigest=34da975e0cbcf03181ff95e13c3db34128a2c5be3c163ac5bee599b43d0e786b
  In-suite DEV run: status=complete, the same runId, counts unchanged (374 distinct pairs, maxTeamSize=5, maxPairExpansion=10, largest block 4).
ASSUMPTIONS:
  - "Before any job runs" is met because checkManifest runs in prepareAndRun before collectInputs, Attempts.next and any stage; the tests confirm that no output directory is created.
  - The duplicate check is the first check in checkManifest. A manifest with several faults reports only the duplicate-source fault until it is fixed.
RISKS:
  - The guard is in the local split entry point only. The production Pipeline/RunLayout.of path, which picks the first entry, can still receive a manifest with a repeated source from other callers. Core validation is the place to close that generally (see suggestion below).
  - The sbt check log is about 27.4 MB, mostly Hadoop DEBUG logging. I read the summary lines from the start and the end of the log.
MANUAL ACTION REQUIRED:
  - Human: review and commit the modules/hadoop/** changes.
  - Suggestion for domain-engineer, not done here (modules/core/** is out of scope): make RunManifest validation require unique sources in inputSnapshots, not only distinct (source, snapshot) pairs.
  - No UNATTRIBUTED audit report appeared during this run.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Code review of this diff (HD-1 closure). Then the steps already planned: commit, codeVersion update, and the human-approved held-out run.
PROPOSED AMENDMENT-TEXT CHANGES (for separate human review): none. The fix adds a refusal and does not change the snapshot-token rule of FE-P5-06.
```
