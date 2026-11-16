# Phase 06 repair 05: CR-P6-09 repairs (ER-P6-07, BE-P6-02)

- Work order (verbatim): docs/agent-runs/phase-06/prompt-cr-p6-09-repairs.md
- Findings routed: CR-P6-09 M2 and CR-P6-08 minor 1 (entity-resolution-engineer, ER-P6-07); CR-P6-09 M1, M3 and the uncommitted-code suggestion (build-engineer, BE-P6-02)
- Not in this repair: CR-P6-09 MAJ-1 (needs the human's first commit), N1, N2, the allowlist row and the hook hardening (deferred by the human)

## ER-P6-07 agent report (entity-resolution-engineer, 2026-11-16), verbatim

I fixed both findings. `sbt check` passed as the final command: 1117 tests, 0 failed, 0 skipped, across 84 suites. The decision and grouping entry points now exit 3 when verification fails. The failed run is kept for diagnosis and no reader accepts it. Exit 2 still means nothing was published.

```text
STATUS: COMPLETE
OWNER: entity-resolution-engineer
WORK ORDER: ER-P6-07 (Phase 6). Fix CR-P6-09 M2 (a run that fails verification looks like a refusal and stays readable) and the CR-P6-08 minor 1 decideAll provenance gap. Scope: modules/entity-resolution/** only.

FILES CREATED: none

FILES MODIFIED (all untracked Phase 6 files except where noted):
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/decisions/DecisionExport.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/grouping/RunDirectory.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/grouping/GroupingExport.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/grouping/Grouping.scala (provenanceProblems only)
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/review/DecisionRunMain.scala (rewritten; adds object RunExit)
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/review/GroupingRunMain.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/decisions/DecisionExportSuite.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/grouping/GroupingExportSuite.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/review/DevRunInputsSuite.scala
Not touched: modules/entity-resolution/local-runs/*, Samples.scala, GroupingFixtures.scala, LeakageStructureSuite.scala, every policy, feature, threshold and Resolver file, and grouping rules O1-O11.

CHANGED REGIONS (line ranges after the change; ranges marked ~ are within about 5 lines):
- DecisionExport.scala:69-108 adds Preserved and PublishResult (Published(dir, attempt) | Refused(reason) | Unverified(preserved, reason)). ~145-178 updates the object scaladoc (staged verification, what a failed attempt leaves). 220-230 adds UnverifiedPrefix ".unverified-", UnverifiedMarker "_UNVERIFIED" and unverifiedName. 315-328 adds curatedRecords. ~330-345 updates the decideAll scaladoc. ~352-356 changes the decideAll foreign check (candidates ++ curatedRecords(works, profiles)). ~500-565 adds Mover, AtomicMove, withdraw, unverifiedText and compareWithBatch. ~567-660 adds publishRun (staged verification before the rename) and keeps publish as an Either wrapper. ~676-707 changes layoutProblem (name parameter, marker refusal). ~776-787 adds a name parameter to verifiedRun. publishedRuns now passes nameOf(p). ~875-895 splits readPublished into nameOf and readAs.
- RunDirectory.scala: imports. ~61-85 updates the object scaladoc. ~150-215 changes publish (adds a check parameter, returns PublishResult, withdraws on a failed check). ~239-265 changes layoutProblem (name parameter, marker refusal). ~283-298 adds nameOf and a name parameter to verified. publishedRuns now passes nameOf(p).
- GroupingExport.scala: imports. ~78-140 adds publishRun (staged check is validatedAs plus compareWithBatch), the publish wrapper and compareWithBatch. ~160-170 adds readPublished to publishedAs. ~190-197 adds readValidated to validatedAs.
- Grouping.scala:~366-380: provenanceProblems now uses DecisionExport.curatedRecords. Output is unchanged.
- DecisionRunMain.scala: whole file. RunExit is at about 20-110, the entry-point scaladoc at about 112-145, and runWithConfig with the err and afterRename overload at about 225-290.
- GroupingRunMain.scala:~22-65 updates the scaladoc and main. ~160-215 changes readBack (now returns the raw reason) and adds the 6-argument runWithConfig. ~255-285 adds the publication and post-publication stage through RunExit.
- DecisionExportSuite.scala: ~19-21 imports. ~123-127 fixture works and profiles are now of the curated run. ~1117-1175 CR-P6-06 m1 test adapted. ~1176-end new tests: mixed-run decideAll, the entry point exits 2, staged failure, readers refuse, withdraw fallback.
- GroupingExportSuite.scala: ~19 imports. ~192-235 m1 test adapted. ~480-590 rewriteCurated and the reworked CR-P6-07 test. ~630-690 new grouping marker and staged-failure test.
- DevRunInputsSuite.scala: ~490-end. The two old exit-2 read-back tests are replaced by four exit-code tests.

EXIT-CODE CONTRACT (RunExit, the same for DecisionRunMain and GroupingRunMain; for build-engineer M1):
- 0 = published and verified twice: the staged directory before the rename, and run=<hex> after it.
  - stdout: summary, the "verified ..." line, settings and evidence lines. Nothing on stderr.
  - New entry under the output root: run=<hex> only.
- 2 = refused, nothing published.
  - stdout: one "error: ..." line. Nothing on stderr.
  - The attempt leaves no new entry under the output root, with two exceptions. The output root directory itself may be created. A ".staging-run=<hex>-<random>" remains only if the process died, or if deleting the staging directory after a write or I/O failure failed; readers ignore it.
  - A run=<hex> that already existed (the no-overwrite refusal) is untouched.
- 3 = verification failed; artifacts preserved; no reader accepts them. Nothing is deleted.
  - stdout: one "error: ..." line with the reason and the preserved path.
  - stderr: exactly one line, "unverified: verification failed; artifacts preserved under <abs path>; not accepted by readers".
  - Directory names that can appear:
    - Normal case: ".unverified-run=<hex>-<random>". It holds the full staged file set plus "_UNVERIFIED", a one-line reason. <random> is the staging suffix.
    - Fallback if that rename failed after a staged failure: ".staging-run=<hex>-<random>", marker attempted.
    - Fallback if that rename failed after a post-publication failure: "run=<hex>" holding "_UNVERIFIED". Every reader refuses it.
    - Only if both the rename and the marker write fail after publication is run=<hex> left unmarked. The stderr line then reads "unverified: verification failed; artifacts left under <path>, which could be neither renamed nor marked _UNVERIFIED; a reader may accept it: move it aside by hand before any reader runs".
  - For a staged failure, run=<hex> is never created.
- Readers (DecisionExport.readPublished and publishedRuns; GroupingExport.readPublished, readValidated and publishedRuns; RunDirectory.verified) refuse any name other than "run=<64 hex>" and any directory holding "_UNVERIFIED". GroupingRunMain reads its decision input through DecisionExport.readPublished, so it refuses withdrawn or marked decision runs with exit 2.

COMMANDS RUN:
- git ls-files --others --exclude-standard -- modules/entity-resolution
- git ls-files -- modules/entity-resolution/src/main/scala/scholarchain/entityresolution
- git ls-files -- modules/entity-resolution/src/test
- git diff --stat
- a grep -n command, refused by the guard (shell-syntax gate); nothing ran
- sbt entityResolution/Test/compile (success)
- sbt entityResolution/test (Passed: Total 464, Failed 0, Errors 0)
- sbt check (final command, success)

TEST RESULTS (sbt check, observed): core 423 passed; entityResolution 464 passed; hadoop 85 passed; tests 145 passed; rag, root, services, sparkAnalytics and storage had no tests (0). markTestRun: "suites=84, passed=1117, skipped=0", git_head=426df590f3f043c96021b739dd7f39436dd30956, worktree=dirty. Total time 149 s. LeakageStructureSuite passed. The DevRunInputsSuite manifest tests passed, so the configDigest and run IDs in local-runs are unchanged.

New feature tests (each fails without its fix):
- decideAll refuses works, mentions and profiles of another run, each case separately and only that kind reported.
- The decision entry point exits 2 for each of those cases with nothing written.
- A staged verification failure (repeated row, or bytes changed after _parts.json) creates no run=<hex>, leaves only .unverified-run=<hex>-<attempt> with every staged file plus the marker, and is refused by the readers.
- Post-publication tampering, injected through the afterRename hook, exits 3 and the run is withdrawn. Tested for the decision and grouping entry points; the grouping cases also cover staged failures and the dataset validators.
- Decision and grouping readers refuse the marker and the .unverified- name.
- withdraw with a failing rename keeps run=<hex> marked and refused, and deletes nothing.
- A refusal exits 2 with nothing on stderr and nothing published, and an existing run is never overwritten.
- GroupingRunMain refuses a marked or preserved decision run with exit 2.

Adjusted tests:
- GroupingExportSuite CR-P6-07 test: the decision export now refuses mixed-run curated data itself, which is asserted. The grouping-side refusal is still tested, by deciding from a clean curated run and then replacing works, mentions or profiles with another run's.
- Both CR-P6-06 m1 tests: publish now preserves instead of publishing. They inspect _parts.json in the preserved directory.
- DecisionExportSuite fixture: works and profiles now carry the curated run ID.

VALIDATION RESULTS: no gold-set evaluation was run; the work order did not name one. No dev diagnostics were produced, and no grouping quality claim is made. Policy, features, thresholds and decision outcomes are unchanged (the parity, threshold and grouping suites pass unchanged).

ASSUMPTIONS:
- The preserved path goes to the err sink: stderr in main. The test overloads without an err parameter send it to out. The "error:" line stays on stdout, as before, so existing consumers keep working.
- The marker is written first, then the rename, so a run=<hex> is refused from the moment the marker exists.
- An I/O failure while staging still exits 2: the staging directory is deleted and nothing was verified.
- "_UNVERIFIED" is also an unlisted file, so the core PartManifestVerifier would refuse it too.

RISKS:
- If both the marker write and the rename fail after publication, run=<hex> stays readable. This is reported on stderr with exit 3 but is not prevented.
- RunExit.afterPublication always renames with AtomicMove. Only DecisionExport.withdraw takes an injectable Mover, so the rename-failure fallback is tested at the withdraw level, not through main.
- The afterRename hook is a test injection point on the public 6-argument runWithConfig. main passes a no-op.
- Within each export, directory verification is still shared code; nothing changed there.

MANUAL ACTION REQUIRED: code-reviewer review of this diff. build-engineer can key M1 on exit code 3 and on the stderr line starting "unverified: ". Pinning the manifests (MAJ-1) waits on the human commit as planned.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer re-review (CR-P6-09 M2, CR-P6-08 minor 1), then build-engineer M1 (task message keyed on exit 3), then the human commit and MAJ-1 manifest pinning.
```

## BE-P6-02 agent report (build-engineer, 2026-11-16), verbatim

```text
STATUS: COMPLETE
OWNER: build-engineer
WORK ORDER: BE-P6-02 (Phase 6). Fix CR-P6-09 M1 and M3 and add the review suggestion's code-scope check to the dev-only task `erDecisionGroupingDev`. Optional N1 is also done. Scope: build.sbt and project/** only.

FILES CREATED: none

FILES MODIFIED:
- /Users/taufeeqpatel/Desktop/ScholarChain/project/LocalRuns.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/build.sbt

Changed regions (line ranges after the change):
- project/LocalRuns.scala:295-335. `readTreeState` gains the parameters `paths` and `context`, and `allowIgnored` becomes `String => Boolean`. The defaults are `CleanTreeScope` and the old D3(b) wording, so the Phase 5 refusal strings are byte-identical. A `Set[String]` still passes, so `heldoutTreeCheck` and the `guardSelfTest` report are unchanged. The verifyHeldoutGuards output still shows 23 launch cases and 17 clean-tree cases, all PASS.
- project/LocalRuns.scala:~982-1004. The dev-section doc comment now describes BE-P6-02.
- project/LocalRuns.scala:1112-1301 (M1). New: `RootEntry`/`EntryKind`, `listRoot`, `describeEntry`, `createOnceProblem`, the `StageState` family, `classifyStage` (pure), and `stageReport`. `existingRuns` and `stagingLeftovers` are removed.
- project/LocalRuns.scala:1385-1595. Code-scope check: `ErDevCodeScope`, `ErDevGeneratedDirs`, `ErDevManifests`, `erDevIgnoredAllowed`, `codeVersionOf`, `erDevCodeScopeCheck`.
- project/LocalRuns.scala:1597-1817 (M3). The task is split into functions: `configOverrideProblem`, `erDevSourceChecks`, `writeOverlay` (N1), `ErDevPreflight`/`erDevPreflight`, `ErDevFork`/`erDevStages` (with an injectable fork), the thinner `erDecisionGroupingDev`, and the new `erDevBeforeCompile`. The old `published` helper is removed.
- project/LocalRuns.scala:1820-2635. Self-test: `erDevGuardSelfTest(buildBase, log)` gets new pure cases and a read-only report of the real tree. The 4 old listing cases were rewritten onto `listRoot`/`createOnceProblem`. New: `fixtureManifest`, `erDevGitFixture` (temporary git repository) and `erDevStageFixture` (synthetic fork).
- build.sbt:19-24. The environment-variable comment now says the verifyErDevRunGuards fixture also sets GIT_CONFIG_GLOBAL, GIT_CONFIG_NOSYSTEM and GIT_CEILING_DIRECTORIES, on a temporary repository only.
- build.sbt:~72-78. New description for the verifyErDevRunGuards key.
- build.sbt:395-416. `erDecisionGroupingDev` is now a `Def.taskDyn`. The outer body runs `erDevBeforeCompile` (source and code-scope checks) before the inner task references `entityResolution/Compile/fullClasspath`. The inner task runs the full preflight again after compiling, before any fork. `verifyErDevRunGuards` now receives the build base. The `check` alias is unchanged.

What was done:
1. M1, publication state per stage. After each child exits, `erDevStages` lists the output root and classifies the result from the exit code and the listing together; neither is trusted alone.
   - Exit 0 with exactly one new unmarked `run=<64 hex>` directory: published-and-verified.
   - Exit 2 with no new entry except unmarked `.staging-*` directories: refused-nothing-published. Any leftovers are named; readers ignore them.
   - Exit 3 with exactly one new `.unverified-*`, `.staging-*` or `run=` directory: unverified-preserved. The report names the path, says whether a `run=` entry exists, and whether it carries `_UNVERIFIED`. An unmarked `run=` gets "a reader may accept it; move it aside by hand".
   - Anything else is "state not established" and the task fails, reporting exactly what was seen. That covers a listing that contradicts the code, a timeout, an exit code outside 0/2/3, a symbolic-link entry, and a pre-existing entry that changed or vanished. When a `run=` entry is present it is reported as "published but unverified".
   - Grouping starts only after the decision stage is published-and-verified. Otherwise the message ends "GroupingRunMain was not started".
   - Create-once decision: any entry at all under either output root blocks a re-run, and each entry is named and classified. That includes `run=` with or without `_UNVERIFIED`, `.unverified-*`, `.staging-*` and anything else. `.staging-*` blocks too because ER-P6-07's exit-3 fallback can leave unverified artifacts under that name. Nothing is deleted.
2. Code-scope check (`erDevCodeScopeCheck`). Scope: `build.sbt project modules/core modules/entity-resolution`. It uses the D3(b) git hygiene (GIT_* stripped, stdin closed, 30 s bound, `--no-optional-locks`, fsmonitor and untracked cache off, fail closed). It refuses on any of:
   - an index flag;
   - any modified, staged, deleted or untracked non-ignored path;
   - any ignored path other than `project/target/`, `project/project/target/`, `modules/core/target/`, `modules/entity-resolution/target/`, or a `.DS_Store` file;
   - a root `.gitignore` that differs from HEAD;
   - a symbolic link or gitlink at HEAD in the scope;
   - raw bytes (`git hash-object --no-filters`) that differ from the HEAD blob;
   - two manifests that disagree on codeVersion;
   - a codeVersion that is not a 40-hex commit in the repository, or is not HEAD or an ancestor of it;
   - `git diff --name-only C HEAD` in the scope listing anything besides the two manifests.
   It reads nothing under gold/ or any held-out path.
3. M3. Each refusal is now a function, and verifyErDevRunGuards exercises each one on temporary fixtures, never the real tree. If any single check is removed, a case fails because its expected refusal substring is specific to that check.
4. N1. An identical overlay is left alone ("unchanged"). An overlay with different bytes refuses, naming both sha256 values. The overlay is never overwritten.

COMMANDS RUN:
1. `sbt Test/compile`: success; the meta-build compiled 1 source.
2. `sbt Test/compile`: success, after fixes.
3. `git diff --stat -- build.sbt project/LocalRuns.scala`: 2 files, 1733 insertions, 18 deletions, against HEAD. This includes the uncommitted BE-P6-01 work.
4. `sbt check`: final sbt command, run once after the last edit; success in 133 s. The output was 27.5 MB, mostly existing Hadoop DEBUG logs from tests. Saved at /Users/taufeeqpatel/.claude/projects/-Users-taufeeqpatel-Desktop-ScholarChain/1d4ee1c4-462f-4438-9d00-5d7f5bbf5c79/tool-results/b7gomwnd5.txt
No git command after it changed anything.

TEST RESULTS (from the sbt check output):
- Passed: Total 423 (core), 464 (entityResolution), 85 (hadoop), 145 (tests); 0 for rag, root, services, sparkAnalytics and storage. Failed 0, Errors 0.
- markTestRun wrote .claude/.last-test-run: git_head=426df590f3f043c96021b739dd7f39436dd30956, worktree=dirty, suites=84, passed=1117, skipped=0.
- verifyErDevRunGuards: "PASS: 128 cases gave the expected verdict" (was 59). The 69 new cases are 16 pure, 21 code-scope fixture, 13 preflight fixture and 19 stage cases.
- verifyHeldoutGuards: PASS, 23 launch and 17 clean-tree cases.
- verifyHeldoutOutputRoute: PASS, marker found in 0 of 5571 files; counter-test PASS.
- Format check, verifyBuildSources and verifyVersionLedger (14 entries): OK.

VALIDATION RESULTS:
(a) Current tree, observed read-only in the sbt check output, not enforced:
- Full source checks: "REFUSE: verifyErDevRunGuards: refused: modules/entity-resolution/local-runs/er-dev.conf is not committed at HEAD; the human commits it before the run". The committed-input check runs first; the three inputs are untracked.
- Code-scope check alone: "REFUSE: ... git status --porcelain --untracked-files=all -- build.sbt project modules/core modules/entity-resolution lists 35 path(s); the dev run compiles this scope, so it must be committed ...; first:  M build.sbt;  M modules/core/src/main/scala/scholarchain/core/Contract.scala; ..."
- Note: today the manifests' codeVersion 426df590 equals HEAD, so the commit-range part would pass on its own. The current state refuses because the code is uncommitted.

(b) Planned X-then-Y sequence (simulated in the fixture, not observed on the real repository):
- At HEAD=X, if the manifests still pin 426df590: refuses with "426df590…..HEAD changes N path(s) in build.sbt project modules/core modules/entity-resolution other than the two dev run manifests ...". This matches the fixture case "HEAD = X, C = W".
- At HEAD=Y, a manifest-only commit pinning X in both manifests: ALLOW. Fixture lines: "X..HEAD changes modules/entity-resolution/local-runs/er-decisions.dev.run-manifest.json, ...er-grouping.dev.run-manifest.json in this scope". This holds only if the scope is otherwise clean.
- A later commit outside the scope (for example docs/agent-runs) still passes (fixture case Z).
- If Y also changes er-dev.conf or any other file in scope, the task refuses.

ASSUMPTIONS:
- Ignored `.DS_Store` files are accepted in the code scope; every other ignored path refuses. Without this, Finder files the agent cannot delete would block the dev run. The held-out D3(b) rule is unchanged.
- codeVersion is read with a regex that requires exactly one `"codeVersion":"<40 hex>"` field per manifest. The meta-build has no JSON library.
- `Def.taskDyn` evaluates the inner task's `fullClasspath` only after the outer body has run. That is standard sbt semantics, but it was not observed: `erDecisionGroupingDev` was never run.

RISKS:
- Neither `erDecisionGroupingDev` nor `erDevBeforeCompile` has ever run on the real repository. Only the functions they call are exercised, on fixtures.
- Compile and check race is narrowed, not closed. The meta-build (project/) is compiled at sbt load, before any check, though a dirty project/ still refuses. A change made between the second check and the fork, or during the run, is not seen.
- The new raw-byte comparison applies to this task's scope only. The Phase 5 held-out BE-6 limitation is unchanged.
- Cosmetic: stage failure messages say "nothing was deleted" twice (once in the state line, once in the re-run advice). It was noticed after the single permitted sbt check, so it was left.
- With `.gitattributes` eol conversion in the future, the raw-byte check would refuse legitimately converted files. It fails closed.

MANUAL ACTION REQUIRED:
- Human: commit X (the code), then a manifest-only commit Y that pins X as codeVersion in both dev manifests. After that the code-scope check should pass at HEAD=Y with a clean scope.
- `sbt erDecisionGroupingDev` stays inactive. No allowlist row was added; activating it needs MAJ-1 resolved and the human's ratification of the exact allowlist change.
- Hook hardening is deferred, as directed.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer review of the changed regions above, then human acceptance.
```
