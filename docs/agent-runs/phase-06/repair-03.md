# Phase 06 repair 03: DE-P6-01 repair 02

- Work order (verbatim): docs/agent-runs/phase-06/prompt-de-p6-01-repair-02-and-er-p6-04.md, item 1
- Findings routed: CR-P6-04 m1, n1, n2
- Owner: domain-engineer

## Agent report (domain-engineer, 2026-11-15), verbatim

All three items are fixed inside modules/core. The final `sbt check` passed with 1058 tests passed, 0 failed, 81 suites. That is the 1055 from repair 01 plus 3 new tests.

**m1 (PartManifest).** Only paths with no `pathProblem` now go into the directory-overlap index. Every invalid path, including one longer than MaxPathLength, is skipped there and is still refused by `fileChecks` with its usual problem message. Every directory prefix of a valid path is itself valid, so no overlap between valid paths is missed. The index-building code is the same as before, so it still names the first listed deeper path, and the existing F5 tests pass unchanged.

I did not make it linear. A sorted prefix search would name the smallest deeper path in byte order instead of the first listed one. The two differ only for an unsorted manifest, which is refused anyway, but the work order allowed no behaviour change. The scaladoc now states the real cost: O(F · L · d) time and memory, with L ≤ MaxPathLength = 1024 and d ≤ L/2, bounded by F · L · L / 2 characters, and it says plainly that this is not linear.

**n1 (Provenance).** The `configDigest` scaladoc now says every stage except grouping records `ScholarChainConfig.digestWithoutGrouping`, which equals `digest` when no grouping key is set. A grouping run records `GroupingRunSettings.configDigest`. Scaladoc only.

**n2 (checkCoverage).** A null `ExpectedAuthors.workId` now returns `MissingField("expected[i].workId")` for each such entry. This check runs before any sorting, keying or duplicate check, so nothing compares the null. A malformed non-null `workId` already gave a typed `StableId.check` error, and a test now covers it.

**Changed regions, for the reviewer:**
- `/Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/main/scala/scholarchain/core/PartManifest.scala:45-50`: class scaladoc with the corrected cost.
- `PartManifest.scala:165-175`: `validPaths = paths.filter(pathProblem(_).isEmpty)`, and the index is built over `validPaths` only.
- `PartManifest.scala:193-203`: cost comment corrected; the lookup runs over `validPaths`.
- `/Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/main/scala/scholarchain/core/Provenance.scala:149-153`: `@param configDigest` names the per-stage digest functions.
- `/Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/main/scala/scholarchain/core/AssignmentValidation.scala:624-626`: scaladoc listing the typed errors for null and malformed input.
- `AssignmentValidation.scala:637-641`: new branch that refuses a null `workId` with an indexed `MissingField`.
- `/Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/test/scala/scholarchain/core/PartManifestSuite.scala:222-252`: new test. A path of 100,000 '/' characters and a path of `"a/" * 50000 + "x.jsonl"` are each refused with "longer than 1024 characters" and no directory error. With both present, only the valid `works` overlap is reported. Before the fix the index would have copied about 5e9 and 2.5e9 prefix characters for these paths (worked out, not run). The test took 0.004s.
- `PartManifestSuite.scala:254-271`: new test. A valid path of exactly 1024 characters still takes part in the overlap check, and the expected file-versus-directory error is named exactly.
- `/Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/test/scala/scholarchain/core/AssignmentValidationSuite.scala:785-809`: new test. Null `workId` first among several entries, two nulls among several, a single null, and a malformed id among several all give typed errors. Without the fix the cases with several entries would throw when the null is compared.

Not run: I did not run the new tests against the old code to watch them fail. The "fails without the fix" claims for m1 and n2 come from reading the old code.

```text
STATUS: COMPLETE
OWNER: domain-engineer
WORK ORDER: DE-P6-01 repair 02 (Phase 6), item 1 of docs/agent-runs/phase-06/prompt-de-p6-01-repair-02-and-er-p6-04.md: CR-P6-04 m1, n1, n2
FILES CREATED: none
FILES MODIFIED: /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/main/scala/scholarchain/core/PartManifest.scala; /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/main/scala/scholarchain/core/Provenance.scala; /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/main/scala/scholarchain/core/AssignmentValidation.scala; /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/test/scala/scholarchain/core/PartManifestSuite.scala; /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/test/scala/scholarchain/core/AssignmentValidationSuite.scala
COMMANDS RUN: `ls docs/agent-runs/phase-06/` (refused by guard, no-allowlist-entry; nothing executed); `sbt core/test` (Passed: Total 423, Failed 0, Errors 0); `sbt check` once, as the final command after the last edit (success, 138 s)
TEST RESULTS: sbt check: build sources OK; version ledger OK; scalafmt check OK; core 423 passed; entityResolution 405 passed; hadoop 85 passed; tests 145 passed; rag, root, services, sparkAnalytics, storage 0 (no tests); all Failed 0, Errors 0. markTestRun wrote .claude/.last-test-run (git_head=426df590f3f043c96021b739dd7f39436dd30956, worktree=dirty, suites=81, passed=1058, skipped=0). "[success] Total time: 138 s"
VALIDATION RESULTS: Existing F5 overlap tests (PartManifestSuite:190-220) pass unchanged; the frozen PartManifest wire examples (AssignmentWireSuite) pass; no format, ID or schema change; no new dependency; changes only under modules/core/**
ASSUMPTIONS: Keeping "first listed deeper path" exactly (also for unsorted, already-refused manifests) takes priority over true linearity, so I kept the fold over valid paths and documented its real cost instead of using a sorted prefix search. For a null workId, returning only the per-entry MissingField errors (no further coverage checks on that input) is acceptable, matching the existing early return for a null entry.
RISKS: Overlap indexing is still O(F · L · d), up to about 512K characters per valid path at the 1024 limit; it is bounded but not linear, and a manifest with very many maximally deep valid paths is costly in proportion. pathProblem now runs twice per path (O(L) each). checkCoverage still assumes non-null WorkAssignment.workId values, as its scaladoc precondition says (records must pass checkWorks first); this is out of scope for n2.
MANUAL ACTION REQUIRED: Orchestrator: route the new diff (regions above) to code-reviewer per the human work order. The phase-06 directory listing was refused by the guard, so I opened the cited evidence files directly by path.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer review of this diff only; then human acceptance; then ER-P6-04 (entity-resolution-engineer). I did not start ER-P6-04.
```
