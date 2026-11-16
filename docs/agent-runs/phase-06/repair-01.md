# Phase 06 repair 01: ER-P6-02 repair 01

- Work order (verbatim): docs/agent-runs/phase-06/prompt-er-p6-02-repair-01.md
- Findings routed: CR-P6-01 m1, m2 (fix); n1, n4 (fix if small); n2, n3 (document as limitations); n5 (human cleanup)
- Owner: entity-resolution-engineer
- n5: on 2026-11-15 the human deleted the empty file `modules/entity-resolution/src/main/scala/scholarchain/entityresolution/decisions/DecisionRunMain.scala`. The orchestrator confirmed it before delegating. `git ls-files --others --exclude-standard -- modules/entity-resolution` listed only DecisionExport.scala, review/DecisionRunMain.scala and DecisionExportSuite.scala. The preceding UNATTRIBUTED audit refusal named only the deleted path.

## Agent report (entity-resolution-engineer, 2026-11-15), verbatim

ER-P6-02 repair 01 is finished. All seven review items are dealt with: m1, m2, n1 and n4 are fixed and tested, and n2 and n3 are written up as limitations in the scaladoc. `sbt check` passed on its single final run: 934 tests passed, 0 failed, 0 skipped, in 77 suites. That is the 928 from before plus 6 new tests.

Matching policy, features, thresholds, `Resolver`, evidence and `DecisionRunMain.scala` are unchanged. The deleted `decisions/DecisionRunMain.scala` was not recreated.

**Changed regions for code-reviewer** (both files are untracked, so `git diff` won't show them; line numbers are after the change):

`modules/entity-resolution/src/main/scala/scholarchain/entityresolution/decisions/DecisionExport.scala`
- 97-112: object scaladoc. States what counts as a published run (a real directory, never a symlink, with `_SUCCESS`, `_run-manifest.json` and both part files as regular files; a part file with zero rows counts as complete). Adds "Known limitations" for n2 and n3.
- 250-252: `decideAll` now ends with `partition(m, runId, decisions)` instead of two silent filters (n1).
- 258-283: new `private[decisions] def partition`. Refuses the batch unless researcher count + work count == total, and names any decision of another kind (n1).
- 373-381: new helpers `Kinds`, `isDir` and `isFile` (both `LinkOption.NOFOLLOW_LINKS`), and `partOf`.
- 383-409: new `layoutProblem`. A structural check that follows no symlinks: the `run=<64 hex>` name, the directory itself, regular `_SUCCESS`, regular manifest, the three `decisions/` directories, and both part files as regular files (m1, n4).
- 411-440: new `publishedManifest`. Runs `layoutProblem`, then decodes the manifest, requires `stage == er.decisions`, and checks that the run ID names the directory (m1).
- 442-460: `publishedRuns` now lists only entries that pass `publishedManifest`. It used to check only `isDirectory` (following symlinks) plus `_SUCCESS` (m1, n4).
- 462-463: `lines` no longer turns a missing file into zero rows.
- 465-500: `readPublished` now goes through `publishedManifest`, then decodes each part file as before. Its scaladoc is updated (m1).

`modules/entity-resolution/src/test/scala/scholarchain/entityresolution/decisions/DecisionExportSuite.scala`
- 604-615: section header and the `publishedRun` and `part` helpers.
- 617-666: m1, seven refusal cases. Each one first reads an intact run, then breaks it and checks that `readPublished` refuses with the expected reason and `publishedRuns` lists nothing. Cases: missing `_SUCCESS`, missing manifest, undecodable manifest, missing researcher part, missing work part, work part replaced by a directory, work part replaced by a symlink to a regular file.
- 668-676: m1, a zero-row work part is listed and read (researcher decisions only).
- 678-691: m1, a hand-built complete run with stage `er.other`, placed in the directory its own run ID names, is refused and not listed.
- 693-703: n4, a symlink `run=<hex>` pointing at a complete run is neither listed nor read; the real run is still listed.
- 705-720: n1, `partition` accepts the real batch and refuses it once a Venue copy of a decision is added.
- 722-780: m2, described below.

**How the m2 test works.** The config differs from the defaults only in accept = 0.95 (default 0.85), with a matching digest. The test first checks that the configured digest and accept threshold differ from the defaults. On dev fixture data, one probabilistic researcher pair is ACCEPTED at p ≈ 0.94 under the defaults and becomes UNRESOLVED under 0.95. The test asserts that this flip exists and is exactly ACCEPTED -> UNRESOLVED. It then runs `DecisionRunMain` with that config and asserts four things:
- the settings line reads `accept=0.95 reject=0.2 llmBand=[0.4,0.7]`;
- every published decision equals the decision under the configured policy;
- the flipped candidate's published outcome is the configured one;
- it is not the default one.

If the code path fell back to `MatchPolicy.loadDefault()`, the digest check would still pass but the decisions would match the defaults, so the test would fail.

**Which tests fail without their fix** (worked out by reading the code; I did not revert and re-run):
- These fail without the fix: missing researcher or work part, part as a directory, part as a symlink (the old reader read these as zero rows); undecodable manifest (the old `publishedRuns` listed it); wrong stage (the old reader returned `Right`); the n4 symlink (the old listing followed it); m2.
- Two refusals already existed before this repair. A missing `_SUCCESS` was already refused and is kept only as a regression check. A missing manifest was already refused through a caught exception; the test now fails only on the new, explicit reason.
- The zero-row test passes with or without the fix. It is there so the new checks can't become too strict.
- The n1 test does not compile without `partition`. The path it covers can't be reached today, because `MatchPolicy.kind` refuses other kinds.

**Risks**
- `publishedRuns` now decodes each candidate run's manifest, which costs a bit more per listing. It still does not read decision lines; consumers must call `readPublished`.
- The output root itself is still opened through symlinks. Only `run=<hex>` and everything inside it are checked without following links.
- The flipped candidate depends on the current fixture score (p ≈ 0.94). If features or policy change, the test's own guard fails loudly rather than passing without testing anything.
- The CR-P6-01 unverified concerns are unchanged and untested: how Hadoop/Spark globs treat `.staging-` names, a possible flake in the sample probe, the exception type of an atomic move, and the missing split guard.

```text
STATUS: COMPLETE
OWNER: entity-resolution-engineer
WORK ORDER: ER-P6-02 repair 01 (Phase 6): fix CR-P6-01 m1 and m2, plus n1 and n4 (small, same code); n2 and n3 documented as limitations; focused regression tests; one final sbt check.
FILES CREATED: none (both files below were already untracked from ER-P6-02)
FILES MODIFIED:
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/decisions/DecisionExport.scala (97-112, 250-252, 258-283, 373-381, 383-409, 411-440, 442-460, 462-463, 465-500)
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/decisions/DecisionExportSuite.scala (604-780)
COMMANDS RUN:
- sbt entityResolution/test (1st): compiled; DecisionExportSuite 11 of 12 passed. m2 failed on its own guard because the first thresholds (accept 0.5, reject 0.05, band 0.1-0.45) flipped no candidate; full counts were cut off in the shell output.
- sbt entityResolution/test (2nd): the same failure; read the reason from modules/entity-resolution/target/test-reports/TEST-scholarchain.entityresolution.decisions.DecisionExportSuite.xml (default outcomes and p per candidate). Changed the threshold to accept = 0.95.
- sbt entityResolution/test (3rd): Passed: Total 405, Failed 0, Errors 0, Passed 405.
- sbt check (once, final, after the last edit): [success], Total time 144 s.
- Refused by the guard, nothing executed: one git grep with quotes; sbt entityResolution/testOnly <suite>; git ls-files -s <path>.
TEST RESULTS: sbt check: core 299 passed; entityResolution 405 passed (399 before + 6 new); hadoop 85 passed; tests 145 passed; rag, root, services, sparkAnalytics and storage have no tests (0). Each project reported Failed 0, Errors 0. markTestRun: "wrote .claude/.last-test-run (git_head=426df590f3f043c96021b739dd7f39436dd30956, worktree=dirty, suites=77, passed=934, skipped=0)". The format check passed inside sbt check.
VALIDATION RESULTS: No goldSetEvalDev or held-out task run (not named by the work order). No gold/** path, held-out label or held-out output read. The only target/ file read was the entity-resolution test-report XML of DecisionExportSuite. No Grep anywhere. Fixtures are synthetic (Samples records, invented manifests) under modules/entity-resolution/target/test-work. Policy version er-policy.v8, blocking er-blocking.v2, features er-features.v2, default thresholds 0.85/0.2 with band 0.4-0.7: all unchanged. No adjudicator, model or prompt involved.
ASSUMPTIONS: publishedRuns decides what a consumer may use, so it also refuses an undecodable or wrong-stage manifest (not only a bad layout). Decision lines are still validated only by readPublished. n1 is tested through private[decisions] partition, because the other-kind path cannot be reached through Resolver.
RISKS: publishedRuns now decodes each manifest (a little more I/O per listing). The output root is still opened through symlinks; only run=<hex> and its contents are checked without following them. The m2 fixture flip depends on the current score (p ≈ 0.94); the test's guard fails loudly if features or policy change. n2 (no directory force after the rename) and n3 (codeVersion never verified) stay open, written up in the scaladoc, pending human acceptance. The CR-P6-01 unverified concerns are unchanged.
MANUAL ACTION REQUIRED: Human accepts or rejects n2 and n3 as documented limitations. Human reviews and commits; the files are untracked.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer reviews only the repair regions listed above; then the human accepts.
```
