# Phase 06 repair 02: DE-P6-01 repair 01

- Work order (verbatim): docs/agent-runs/phase-06/prompt-de-p6-01-repair-01.md
- Findings routed: CR-P6-03 F1 (also ER-P6-05 draft M2), F2 (M5), F3, F4, F5; draft O9 (M3); optional nits N1, N3, N6
- Owner: domain-engineer

## Agent report (domain-engineer, 2026-11-15), verbatim

I've made all seven repair items inside `modules/core/**` and nothing else. `sbt check`, run once after my last edit, passed: 1055 tests, 0 failed, 81 suites.

**Main points for the reviewer**
- **F4 needs a one-line change outside core**, owned by entity-resolution-engineer. Until it lands, adding the grouping keys to a config still changes the decision run's ID. The change is at `DecisionRunMain.scala:108`: use `ScholarChainConfig.digestWithoutGrouping(cfg)` instead of `ScholarChainConfig.digest(cfg)`. `DecisionExportSuite` (configured digest, around line 731) should switch too and gain a regression that sets the grouping keys. Configs without grouping keys give the same digest under both functions, so existing decision manifests stay valid.
- **The new validators only work if ER calls them.** ER-P6-05 must call `checkCoverage` (with `ExpectedAuthors.of` for every representative work) and `checkSingletonAnchors` (with every ORCID iD and DBLP pid from the curated run) before publishing. It should record `GroupingRunSettings.configDigest(cfg)` as the grouping run's digest.
- **Some F2 checks are the producer's job.** Core cannot see the curated data, so ER must make sure:
  - the identifier input is complete and faithful;
  - values are in the same canonical form as the anchor value;
  - no collision exists outside the supplied set;
  - the singleton status is correct, i.e. no ACCEPTED link was dropped.
- **Two choices of mine need confirming:**
  - **Grouping digest scope.** The grouping digest leaves out the two run roots, so moving a root keeps the grouping run's ID. The base config already counts bucket names as identity, so this differs from precedent. Including the roots is a one-line change if you prefer it.
  - **Unresolved members count for collisions.** A value asserted by an unresolved member counts as a collision. That matches draft O6, which is not approved.
- **The record rule is stricter without a schema bump.** A singleton with a pending, vetoed or conflict decision must now be anchored on `orcid` or `dblp-pid`. No frozen example changes and the schema was never committed, so I didn't bump `researcher-assignment.v1`.

**Changed regions** (the new files are untracked; ranges are after formatting, test ranges marked ~ are approximate)

`modules/core/src/main/scala/scholarchain/core/AssignmentValidation.scala`
- 3: import adds `at`.
- 5-17: new `ExpectedAuthors` input (F3), with `of(WorkRecord)`.
- 19-46: new `MemberIdentifier` input (F2), with `fromMention` and `fromLink`.
- 48-69: object scaladoc: N1 null preconditions and the O9/M6 graph-scope statement.
- 88-91: `duplicates` uses the group size instead of a recount (F5).
- 106-112, 150-157: REJECTED and CONFLICT decisions may not join one group; pending stays unconstrained (F1, for both researchers and works).
- 333-341, ~364: per-record decision index replaces the `contains` scan, result unchanged (F5).
- 431-437: `checkAuthorships` scaladoc on O9 scope.
- 533-545: `checkTeams` scaladoc on O9 and M6.
- 588-593: `unresolvedMentionIds` compared in author order (N6).
- 608-686: new pure `checkCoverage` (F3).
- 688-772: new pure `checkSingletonAnchors`, with the list of checks core cannot perform (F2).

`modules/core/src/main/scala/scholarchain/core/Assignments.scala`
- ~15-17: singleton scaladoc describes `t1-anchored`.
- 288-305: `T1AnchorNamespaces`, `requiresT1Anchor`, `blockingDecisions`.
- 432, 459-467: record-level F2 rule in `statusFields`.

`modules/core/src/main/scala/scholarchain/core/config/ScholarChainConfig.scala`
- ~85-90: scaladoc names the grouping scope.
- ~268-298: `GroupingRunSettings.DigestSchema` and `configDigest`, the grouping run's own digest (F4).
- ~342-379: `digest` scaladoc, `GroupingScopeKeys`, `withoutGrouping`, `digestWithoutGrouping` (F4). `digest` itself is unchanged, so the frozen base digest is unchanged.

`modules/core/src/main/scala/scholarchain/core/PartManifest.scala`
- ~45-48: validation cost documented.
- ~162-168 and ~182-185: file-versus-directory check now uses a prefix index (linear, same message, same path named) (F5).

Tests
- `AssignmentValidationSuite.scala` 254-287: the tautological N3 test is replaced by an O9 test (enrichment-only representative keeps authorships, has no team).
- `AssignmentValidationSuite.scala` 488-820: F1 for researchers and works with both REJECTED and CONFLICT; F2 accept and refuse cases; F3 complete, missing-final-author and mid-gap cases plus input-shape cases; F5 unchanged behaviour; N6.
- `AssignmentContractSuite.scala` ~11-12 and ~106-140: record-level F2.
- `ConfigLoaderSuite.scala` ~827-895: F4 decision-stage digest unchanged when grouping keys are added or moved, base frozen digest unchanged, grouping digest input frozen.
- `PartManifestSuite.scala` ~190-220: F5 messages unchanged.

Each regression fails without its fix, except the F5, PartManifest and O9 tests, which pin behaviour that did not change. F2, F3 and F4 also fail to compile without the new functions.

```text
STATUS: COMPLETE
OWNER: domain-engineer
WORK ORDER: DE-P6-01 repair 01 (Phase 6). F1/M2, F2, F3, F4, F5, O9 and the optional nits N1, N3, N6. Binding text: /Users/taufeeqpatel/Desktop/ScholarChain/docs/agent-runs/phase-06/prompt-de-p6-01-repair-01.md
FILES CREATED: none (all edits are in files DE-P6-01 created or modified)
FILES MODIFIED:
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/main/scala/scholarchain/core/AssignmentValidation.scala (untracked)
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/main/scala/scholarchain/core/Assignments.scala (untracked)
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/main/scala/scholarchain/core/PartManifest.scala (untracked)
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/main/scala/scholarchain/core/config/ScholarChainConfig.scala (tracked, already modified)
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/test/scala/scholarchain/core/AssignmentValidationSuite.scala (untracked)
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/test/scala/scholarchain/core/AssignmentContractSuite.scala (untracked)
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/test/scala/scholarchain/core/PartManifestSuite.scala (untracked)
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/test/scala/scholarchain/core/config/ConfigLoaderSuite.scala (tracked, already modified)
COMMANDS RUN:
- git status --short: ok
- git ls-files --others --exclude-standard -- docs/agent-runs/phase-06: ok
- git ls-files --others --exclude-standard --modified -- modules/core: ok
- git ls-files -- modules/core: ok
- git ls-files --others -- .../PartManifestSuite.scala: ok
- three Bash calls refused by the guard, nothing executed: grep with quotes, git grep with quotes (shell-syntax-gate), and git status --short -- modules (no-allowlist-entry)
- sbt core/compile: success
- sbt core/test: Passed: Total 420, Failed 0, Errors 0
- sbt check (once, final command after the last edit): success. Version ledger OK, scalafmt check OK, compile OK. Passed per project: core 420, entityResolution 405, hadoop 85, tests 145; rag, root, services, sparkAnalytics and storage 0. markTestRun wrote .claude/.last-test-run (git_head=426df590f3f043c96021b739dd7f39436dd30956, worktree=dirty, suites=81, passed=1055, skipped=0). Total time 132 s.
TEST RESULTS: sbt check 1055 passed, 0 failed, 0 skipped, 81 suites (DE-P6-01 had 1037; 18 new tests, and N3 was replaced in place). Core alone 420 passed, 0 failed.
VALIDATION RESULTS:
- Frozen wire examples, MatchDecision v2, RunManifest and ID formulas unchanged; no schema version bump.
- ScholarChainConfig.digest unchanged; the frozen base digest test passes.
- No new dependency. All edits are under modules/core/**.
- Nothing read under gold/**, target/streams, target/local-runs or any held-out path, apart from my own sbt output file. Entity-resolution files were read only, never edited.
- Stable ID formulas: unchanged.
- Pair canonicalization: unchanged.
- Config precedence: unchanged (later files override earlier files, the environment overrides files).
- Schema versions: unchanged.
- New core API (not a wire format): ExpectedAuthors, MemberIdentifier, checkCoverage, checkSingletonAnchors, T1AnchorNamespaces, requiresT1Anchor, GroupingScopeKeys, withoutGrouping, digestWithoutGrouping, GroupingRunSettings.configDigest.
- Grouping digest: SHA-256 of {"schema":"scholarchain.grouping-config-digest.v1","baseConfigDigest":<digestWithoutGrouping>,"groupingPolicyVersion":<version>}, frozen by test.
ASSUMPTIONS:
- The record-level F2 rule needs no schema bump, because researcher-assignment.v1 was never committed and no frozen example changes.
- Anchor values and identifier values are compared exactly as given; both are assumed canonical.
- A value asserted by an unresolved member counts as a collision (fail closed; matches draft O6, which is not approved).
- The two run roots are locations, not run identity, so they are left out of the grouping digest. This differs from how buckets are treated in the base config.
- An expected author list is required for every representative work, including enrichment-only works with no authors; lists for non-representative works are checked when supplied.
- O9: the current code already matched the requested scope, so only scaladoc and a pinning test were added.
RISKS:
- Until entity-resolution-engineer changes DecisionRunMain.scala:108, adding grouping keys to a config still changes the decision run's configDigest.
- I could not search other modules (git grep with quoted patterns and plain grep were refused) to see whether anything else calls ScholarChainConfig.digest. Any such caller is unaffected while configs set no grouping key.
- checkCoverage and checkSingletonAnchors protect nothing unless ER-P6-05 calls them with complete inputs.
- The cross-dataset checks still assume record-valid inputs (N1 documented, not hardened).
MANUAL ACTION REQUIRED:
- Human: confirm the two choices above (run roots left out of the grouping digest; unresolved members counted for collisions) and accept the stricter record rule without a schema bump.
- Orchestrator: route the DecisionRunMain.scala:108 and DecisionExportSuite change to entity-resolution-engineer; send the repair regions above to code-reviewer.
BLOCKED-ON OWNER: none (the F4 change outside core belongs to entity-resolution-engineer as follow-up work; it does not block this core repair)
BLOCKED-ON EVIDENCE: modules/entity-resolution/src/main/scala/scholarchain/entityresolution/review/DecisionRunMain.scala:108 still passes ScholarChainConfig.digest(cfg) to DecisionExport.decideAll
NEXT GATE: code-reviewer review of the repair regions, then human acceptance; then entity-resolution-engineer switches DecisionRunMain to digestWithoutGrouping. ER-P6-04 and ER-P6-05 stay on hold until review.
```
