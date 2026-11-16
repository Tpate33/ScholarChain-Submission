# Phase 06 repair 07: DE-P6-02 repair 01 (CR-P6-13 findings 1-4)

- Work order (verbatim): docs/agent-runs/phase-06/prompt-de-p6-02-repair-01.md
- Findings routed: CR-P6-13 findings 1 (major), 2, 3, 4 (minor). Carried forward: findings 5, 6 (nits).
- Owner: domain-engineer

## Agent report (domain-engineer, 2026-11-16), verbatim

STATUS: COMPLETE
OWNER: domain-engineer
WORK ORDER: DE-P6-02 repair 01 (Phase 6). Fixes CR-P6-13 findings 1-4 in the uncommitted DE-P6-02 work in modules/core/** (HEAD 6be1f1d). Findings 5-6 are carried forward untouched.

All four findings are fixed and `sbt check` passes: 1251 tests passed, 0 failed, 89 suites. Core went from 532 to 557 tests, all 25 new ones for this repair. Two rules differ slightly from the wording of the work order; both are explained under ASSUMPTIONS (the team rule for counted works without authors, and tying the benchmark list to one grouping run).

**What changed, by finding**
- **Finding 1 (config digest):** the analysis settings now carry their own `configDigest`, and settings can only be built from a configuration. `checkRunChain` refuses an analysis manifest, or any record lineage, whose digest differs from that value. The fixture now uses the real digest of an in-memory synthetic config instead of `"9"*64`.
- **Finding 2 (inputs tied to their runs):** teams, works and researcher assignments must carry the grouping run ID. Assignments and decisions must belong to the grouping manifest's `decisionRunId`. A missing or duplicate team for a counted work is refused.
- **Finding 3 (load ID):** the store load ID now hashes each item's path and role. Different role maps over the same parts give different IDs.
- **Finding 4 (which paths are stored):** a new benchmark-pair list contract says which pairs get paths. For each listed pair and each configured window, the stored paths must be exactly the first K shortest paths in a fixed order (rule below).
- **Unchanged digests:** `digestWithoutGrouping`, `GroupingRunSettings.configDigest` and the frozen base digest are untouched. The ConfigLoaderSuite frozen-digest tests and the ER `DevRunInputsSuite` (decision 61655dc1…, grouping 48484593…) pass.

**Path-order rule (exact)**
- Paths are compared on their node sequence (`nodeIds`, source first), element by element, by unsigned UTF-8 bytes (`Canonical.compareUtf8`).
- The first differing element decides; a proper prefix sorts first. This is `EvidencePath.compareNodes` / `EvidencePath.nodeOrder`.
- For each listed pair and each window in `graph.windows`, the stored paths are the first K shortest paths in that order, with K = `analysis.maxPathsPerPair`, at most `analysis.maxPathHops` hops, and `ordinal` = 0..n-1 in that order. None are stored when the pair is not connected within the bound, and no path may be stored for an unlisted pair.
- The executable definition is `AnalysisValidation.selectShortestPaths`. It runs a bounded breadth-first search from the target, then a depth-first walk from the source that tries neighbours in ascending UTF-8 order and stops after K. It never lists every shortest path.
- Exhaustive enumeration exists only in a test helper (dev scale, bounded by maxHops). It matches `selectShortestPaths` on 1708 cases: the two tie fixtures and a 3x3 grid with 6 tied corner paths.
- Tie fixtures, written out literally with ORCID IDs n1 < … < n6:
  - Diamond n1-{n2,n3,n4}-n6: K=1 gives [n1,n2,n6]; K=2 adds [n1,n3,n6]; K=3 adds [n1,n4,n6].
  - Two levels: K=2 gives [n1,n2,n4,n6] and [n1,n2,n5,n6], both through n2 before any path through n3.
  - In the main fixture graph: K=1 stores A-B-C, never A-E-C.

**Benchmark-pair contract**
- Schema `scholarchain.benchmark-pairs.v1` (`BenchmarkPairList`): `schemaVersion`, `listVersion` (a token), `pairs` (non-empty, each in canonical order, strictly ascending), `provenance`.
- Provenance is `scholarchain-derived`, with `sourceRecordId` = `listVersion` and no model or prompt. Its `runId` must be the analysis run's grouping run (checked in `checkRunChain`).
- Every listed researcher must be an identified researcher of the grouping run (checked in `checkPaths`).
- The analysis manifest gets a new required parameter `benchmarkPairsDigest` = SHA-256 of the list's compact JSON. A different list therefore gives a different run ID.
- Frozen example: `{"schemaVersion":"scholarchain.benchmark-pairs.v1","listVersion":"benchmark-pairs-synthetic.v1","pairs":[{"researcherA":"researcher:orcid:0000-0000-0000-0001","researcherB":"researcher:sc:v1:6408…9ff0"},{"researcherA":"researcher:sc:v1:6408…9ff0","researcherB":"researcher:sc:v1:e688…9251"}],"provenance":{"sourceDataset":"scholarchain-derived","sourceRecordId":"benchmark-pairs-synthetic.v1","snapshot":"dblp-2026-10-03","runId":"run:4eb49e70…0d41","sourceSchemaVersion":"scholarchain.researcher-assignment.v1","policyVersion":null,"modelVersion":null,"promptVersion":null}}`

**New store-load ID formula**
`load:` + SHA-256(netstrings("scholarchain.store-load.v2", targetStore tag, sourceRunId, partManifestSha256, item count, then for each item in ascending UTF-8 order of path: path, targetRole)).
- I bumped the tag from v1 to v2 even though v1 was never committed. The manifest schema stays `store-load-manifest.v1` because its wire shape did not change.
- Empty, duplicate-path or non-token-role items are refused.
- Goldens: `load:fa5344b2abfbaf061e5a5dc5afeb05332cf01d311a45e2812ff5a62fcfdaeb9a`, plus the PartManifestV2Suite formula test.

**Other stable IDs:** component, community, path and summary-input formulas are unchanged. The canonical pair rule is unchanged: first < second by unsigned UTF-8 bytes, equal endpoints refused; benchmark pairs follow it too.

**Changed regions (repair diff only, line ranges after the change)**
- `modules/core/src/main/scala/scholarchain/core/config/ScholarChainConfig.scala:141-161` (doc, private constructor, `configDigest` field), `:191-212` (`configDigest` delegates; private `digestOf`, same digest input as before), `:214-281` (`from` fills the digest).
- `modules/core/src/main/scala/scholarchain/core/AnalysisValidation.scala`:
  - `:117-241`: `upstreamRuns`, `foreignRun`, new `checkGraphInput(input, groupingManifest)`, `graphInputProblems`.
  - `:502-539`: `checkIdentity(…, groupingManifest)`, plus private `identityProblems`.
  - `:822-952`: `checkPaths(…, benchmarkPairs)` with the selection checks.
  - `:954-1013`: `selectShortestPaths`.
  - `:1166-1173`: `AnalysisManifestKeys.BenchmarkPairsDigest`.
  - `:1175-~1303`: `checkRunChain(…, benchmarkPairs)`; the expected lineage uses `settings.configDigest`, and the digest and benchmark refusals come first in its `all(...)`.
- `modules/core/src/main/scala/scholarchain/core/Analysis.scala:1068-1079` (EvidencePath doc: selection rule and ordinal), `:1104-1120` (`compareNodes`, `nodeOrder`), `:1229-1328` (`BenchmarkPair`, `BenchmarkPairList`, digest, contract).
- `modules/core/src/main/scala/scholarchain/core/StableIds.scala:74` (tag v2), `:899-~944` (`StoreLoadId`).
- `modules/core/src/main/scala/scholarchain/core/StoreLoad.scala`: `loadId` doc, and both `StoreLoadId.of` calls now pass (path, role).
- `modules/core/src/main/scala/scholarchain/core/Contract.scala`: `BenchmarkPairList` constant, registry entry, version-history line.
- Tests:
  - `AnalysisFixtures.scala` (top section): config, `settingsOf`, IDs, `benchmarkPairs`, real digest, local `groupingProv`/`stamped`/`countedWork`, restamped works and researchers, `decisionRunId` = the DE-P6-01 decision run, `path(bounds=…)`.
  - `AnalysisValidationSuite.scala`: existing tests moved to the new signatures; new tests are named "finding 1/2/4: …".
  - `EvidencePathSelectionSuite.scala`: new.
  - `AnalysisWireSuite.scala`: pins (ADigest, BPDigest, DRun, GRun, ARun, LOAD), golden `L` digest, BridgeGolden measure, two "repair 01" tests, BenchmarkPairList golden.
  - `PartManifestV2Suite.scala` (C7 formula test), `SchemaVersionSuite.scala` (40 schemas, new contract), `WireContractSuite.scala` (registry).

**Configuration precedence** is unchanged: `SCHOLARCHAIN_CFG_*` environment variables, then later config files over earlier ones; no built-in defaults. No new config key was added.

FILES CREATED:
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/test/scala/scholarchain/core/EvidencePathSelectionSuite.scala

FILES MODIFIED:
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/main/scala/scholarchain/core/config/ScholarChainConfig.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/main/scala/scholarchain/core/AnalysisValidation.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/main/scala/scholarchain/core/Analysis.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/main/scala/scholarchain/core/StableIds.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/main/scala/scholarchain/core/StoreLoad.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/main/scala/scholarchain/core/Contract.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/test/scala/scholarchain/core/AnalysisFixtures.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/test/scala/scholarchain/core/AnalysisValidationSuite.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/test/scala/scholarchain/core/AnalysisWireSuite.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/test/scala/scholarchain/core/PartManifestV2Suite.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/test/scala/scholarchain/core/SchemaVersionSuite.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/test/scala/scholarchain/core/WireContractSuite.scala

COMMANDS RUN:
- Refused by the guard, not executed: two grep/git-grep calls (character gate).
- `git ls-files` (three times), `git diff --unified=0` and `--stat` on core, `git status --porcelain`: all exit 0.
- `sbt core/compile`: success.
- `sbt core/Test/compile`: success.
- `sbt core/test`, five times during development:
  - runs 1-2 failed on placeholder pins and new IDs, as expected;
  - run 3 failed (exit 1) on a cause I could not see in the cut-off error output; reading the suites turned up the stale v1 load-ID formula test in PartManifestV2Suite, which I then updated;
  - run 4 failed again (exit 1, cut off);
  - run 5 passed: Total 557, Failed 0.
- `sbt check`: once, as the final command after the last edit (the last edit was a robustness `getOrElse` in `selectShortestPaths`). Success.

TEST RESULTS (`sbt check`, observed in the saved log, tool-results/bdzct2m60.txt):
- core 557, entityResolution 464, hadoop 85, tests 145; rag, root, services, sparkAnalytics, storage 0.
- Total 1251 passed, 0 failed, 0 errors, 0 skipped, 89 suites. `[success] Total time: 133 s`.
- markTestRun wrote .claude/.last-test-run (git_head=6be1f1db6eb22864ac20b051040412d8ddb4e0e1, worktree=dirty, suites=89, passed=1251, skipped=0).

VALIDATION RESULTS:
- Inside `sbt check`: "Build sources OK", "Version ledger OK: 14 pinned entries", scalafmt check passed.
- The frozen base digest and the ER dev-run digests are unchanged (their suites pass).
- 13 goldens are frozen in AnalysisWireSuite, now including the benchmark list; all pins match their formulas.
- `git status` shows changes only under modules/core/** plus the orchestrator's evidence files in docs/agent-runs/phase-06/.

ASSUMPTIONS:
- **Team rule (decision needed):** "exactly one team per counted work" is implemented as one team per counted work that has at least one mention in the grouping run. This is the accepted `AssignmentValidation.checkTeams` rule. `Grouping.teamRecords` writes no team for a counted work without authors, and `PublicationTeam` requires `teamSize >= 1`, so a team for such a work cannot exist. A literal rule would refuse any grouping run that contains a counted work with no authors. Missing and duplicate teams are refused and tested.
- **One list per grouping run (decision needed):** the benchmark list's `provenance.runId` must be the analysis run's grouping run, so a new grouping run needs a re-issued list.
- The analysis fixture reuses the DE-P6-01 decisions and their decision run (`run:8dc188c7…`), because decision IDs hash the run ID. Restamping the decisions would break their IDs.
- Finding 6 item (bridge, community, coreness): no contract field was needed. `bridge.algorithm`, `community.algorithm` and `community.*` parameters are already recorded on every record, and coreness by k-core peeling has a unique result. The fixture config now uses `cross-community-degree` and `label-propagation` (`maxIterations`).
- Goldens and pins changed without a schema bump, because none of the DE-P6-02 contracts has been committed. The only version change is the load-ID tag (v2).
- I read the saved sbt output under ~/.claude/projects/…/tool-results/ to get the counts. I read nothing under target/, gold/ or any held-out path.

RISKS:
- Spark must reproduce `selectShortestPaths` exactly: same set and order, with ties broken by UTF-8 node order. It does not need to enumerate every shortest path.
- The analysis validator is still in-memory and dev-scale. Finding 5 (models rebuilt per check) is unchanged.
- `bridge.*` sampling parameters are still recorded with the cross-community-degree measure, where they are meaningless. They are base-scope keys, part of the later digest-scope fix.
- The ER dev manifests still need re-pinning before `sbt erDecisionGroupingDev` (BE-P6-02 code-scope check), as before.

MANUAL ACTION REQUIRED: Orchestrator: route a focused code-reviewer re-review of the regions above. Human: accept or redirect the two assumptions marked "decision needed" (the team rule and one list per grouping run); author the real benchmark pair list (its location and owner are not decided); carry findings 5-6 forward.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: focused code-reviewer re-review of the repair diff, then human acceptance.
