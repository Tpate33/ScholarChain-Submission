# Phase 06 DE-P6-02: domain-engineer report (2026-11-16), verbatim

- Work order (verbatim): docs/agent-runs/phase-06/prompt-archive-and-de-p6-02.md (human direction) and the dispatched text summarised in work-order-draft-de-p6-02.md, with the human's additions (explicit unknown identity confidence; Parquet integrity by bytes and checksum, rows from Parquet metadata or the writer; no JSONL newline validation on binary files).

## Orchestrator observations after the report (2026-11-16)

`git status --porcelain`: 9 modified tracked files, all under modules/core/** (Contract.scala, PartManifest.scala, Provenance.scala, StableIds.scala, config/ConfigLoader.scala, config/ScholarChainConfig.scala, SchemaVersionSuite.scala, WireContractSuite.scala, config/ConfigLoaderSuite.scala); 9 new untracked files under modules/core/** (Analysis.scala, AnalysisValidation.scala, PartManifestV2.scala, StoreLoad.scala, AnalysisContractSuite.scala, AnalysisFixtures.scala, AnalysisValidationSuite.scala, AnalysisWireSuite.scala, PartManifestV2Suite.scala); and orchestrator evidence under docs/agent-runs/phase-06/. `git diff --stat` (tracked files): 9 files changed, 890 insertions(+), 67 deletions(-). Nothing outside modules/core/** and docs/agent-runs/phase-06/.

## Agent report, verbatim

STATUS: COMPLETE
OWNER: domain-engineer
WORK ORDER: DE-P6-02 (Phase 6). Contracts C1-C7 for the Spark collaboration-analysis stage, with validation, config additions and contract tests, all inside `modules/core/**`.

The new contracts are in place and `sbt check` passes: 1226 tests passed, 0 failed, across 88 suites. One thing you need to act on: because core changed, `sbt erDecisionGroupingDev` will now refuse until the entity-resolution dev manifests are re-pinned to a new implementation commit. The captured dev run is still valid under its own code version (8c1bee0).

**Contracts and schema versions** (all synthetic-fixture tested; none read `target/`, an archive, a run directory or `gold/`)

| Contract | Schema version | What it holds |
|---|---|---|
| C1 metrics | `scholarchain.researcher-metrics.v2` | P, C, T, A, M, W per researcher and window, unresolved counts in separate fields, identity confidence, lineage |
| C2 edge | `scholarchain.coauthor-edge.v2` | identified endpoints, per-year weights, every supporting counted work |
| C3 | `component-membership.v1`, `community-membership.v1`, `bridge-measure.v1`, `coreness.v1`, `evidence-path.v1` | graph structure; the path record also serves as the C6 evidence path |
| C4 | `analysis-window.v1` | one record per configured window |
| C6 | `semantic-summary-input.v1` | facts for a summary, each citing stored records |
| Integrity | `part-manifest.v2` | per-file format: `jsonl`, `parquet`, `csv` |
| C7 | `store-load-manifest.v1` | names parts of a part manifest for Neptune, DynamoDB, S3 or S3 Vectors |

- Value objects (versioned by the record that carries them): lineage, identity confidence (C5), decision confidence, path bounds, path hop, year count.
- Every record carries lineage: grouping, decision and input run IDs, code version, config digest, grouping and match policy, identity version, and `groupingEvaluation = "unevaluated"`. That last field has no other value, so no record can imply a quality claim.
- Config digest schema: `scholarchain.analysis-config-digest.v1`.

**Compatibility notes**
- Metrics v1, edge v1 and part-manifest v1 are not retired. Their contracts, codecs and frozen examples are unchanged and they still decode. The v1 path-set checks and verifier were moved into shared helpers; messages and order are unchanged and all existing v1 tests pass. Analysis writers write v2 only.
- `PartManifests.decode` and `PartManifests.verify` read either part-manifest version and apply that version's rules. `PartManifestV2.fromV1` lifts a v1 manifest, and a test shows it verifies exactly like v1.
- Accepted Phase 6 contracts (ResearcherAssignment v1, WorkAssignment v1, AuthorshipRecordV2, PublicationTeam v1, MatchDecision v2, PartManifest v1) are unchanged. Phase 5 policy, features, thresholds and evidence are untouched.

**Integrity rules (decision 6)**
- `jsonl` parts keep the line-count and final-newline checks; row-count source must be `lf-count`.
- `parquet` parts are checked by byte count and SHA-256 only. Rows come from `parquet-footer` or `writer`, are never counted from the bytes, and no newline rule applies. Only format check: 12-byte minimum (a Parquet property).
- `csv` parts: byte count and SHA-256, rows reported by the writer.
- Core still has no Parquet dependency.
- Both requested tests exist: a binary part with no final newline passes when bytes and SHA-256 match, and a JSONL part still fails on a missing final newline.

**Sample serialized records**
- Identity confidence, measured: `{"status":"resolved","anchorNamespace":"orcid","memberCount":2,"collisionCodes":["grouping.anchor-collision.orcid"],"lowestDecisionConfidence":{"kind":"measured","value":0.97,"unknownReason":null}}`
- Unknown confidence: `{"kind":"unknown","value":null,"unknownReason":"no-supporting-decision"}`. A missing `kind`, a value on an unknown, or a null confidence is refused.
- Edge v2: `{"schemaVersion":"scholarchain.coauthor-edge.v2","edgeId":"coauthor:3d2e5b…f2a5","researcherA":"researcher:sc:v1:6408a5…9ff0","researcherB":"researcher:sc:v1:e68814…9251","window":{"startYear":2020,"endYear":2024},"jointPublicationCount":2,"firstCollaborationYear":2021,"lastCollaborationYear":2023,"publicationsByYear":[{"year":2021,"count":1},{"year":2023,"count":1}],"supportingWorkIds":["work:dblp:journals/example/Synthetic33","work:dblp:journals/example/Synthetic36"],"lineage":{…},"provenance":{…}}`
- Twelve full goldens are frozen in `AnalysisWireSuite.scala`.

**Stable ID formulas** (SHA-256 over netstring-encoded fields; timestamp-independent)
- Edge: v1 formula unchanged (canonical pair, window, graph policy), so v1 and v2 records of one pair share the ID.
- `component:` = tag `scholarchain.component.v1`, start year, end year, graph policy, anchor (smallest member researcher ID by UTF-8 bytes), so the ID never depends on an algorithm's labels.
- `community:` = tag `scholarchain.community.v1`, start, end, policy, algorithm, parameter count, sorted key/value pairs, anchor.
- `path:` = tag `scholarchain.evidence-path.v1`, start, end, policy, node count, node IDs in order.
- `suminput:` = tag `scholarchain.summary-input.v1`, document type, subject ID, start, end, policy.
- `load:` = tag `scholarchain.store-load.v1`, target store, source run ID, `_parts.json` SHA-256, item count, sorted item paths.

**Pair canonicalization:** first endpoint strictly less than the second by unsigned UTF-8 byte order; equal endpoints refused. Edge v2 and path endpoints are canonical by construction (`CoauthorEdgeV2.build`) and re-checked on validation.

**Validation (`AnalysisValidation`)**
- Exact recomputation from the grouping run's counted teams: edges, metrics, components, bridge degrees and participation, coreness, shortest-path distances, window counts.
- Graph input: a team of an enrichment-only, member-of-counted or unknown work is refused, never counted.
- No mixing: C and W identified-only; W must equal T minus the unresolved part.
- Identity: each metrics record's identity must equal the one derived from grouping members and decision confidences.
- Run chain: analysis manifest must name the grouping run, the grouping manifest's `decisionRunId` and `inputRunId`, and the same snapshots; every record's lineage and provenance must match.
- Before consumption: a grouping run's parts are verified per format and must include all four record schemas.
- Not re-derived: community partition choice and bridge score (shape and configuration only).

**Config contract and digest scope**
- Precedence: `SCHOLARCHAIN_CFG_*` env vars, then later config files over earlier ones; no built-in defaults.
- Five new optional keys: `community.randomSeed`, `analysis.maxPathHops`, `analysis.maxPathsPerPair`, `analysis.maxEvidenceWorksPerHop`, `analysis.exportFormat` (`parquet` or `jsonl`). Windows still come from `graph.windows`.
- Existing configs load unchanged and keep their exact digest (frozen base digest test passes).
- Analysis keys never change decision-run or grouping-run digests: `digestWithoutGrouping` now also strips the analysis keys (no existing value changes; no earlier config could set them).
- Analysis run digest = hash of base digest plus the five analysis values. `AnalysisRunSettings.from` refuses a config missing any of them.

FILES CREATED:
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/main/scala/scholarchain/core/Analysis.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/main/scala/scholarchain/core/AnalysisValidation.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/main/scala/scholarchain/core/PartManifestV2.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/main/scala/scholarchain/core/StoreLoad.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/test/scala/scholarchain/core/AnalysisFixtures.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/test/scala/scholarchain/core/AnalysisContractSuite.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/test/scala/scholarchain/core/AnalysisValidationSuite.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/test/scala/scholarchain/core/AnalysisWireSuite.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/test/scala/scholarchain/core/PartManifestV2Suite.scala

FILES MODIFIED (changed regions for the reviewer; new files are entirely new):
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/main/scala/scholarchain/core/Contract.scala: 34-52 (version history), 83-93 (constants), new entries in `SchemaVersions.all`.
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/main/scala/scholarchain/core/PartManifest.scala: 119-163 (`pathSetChecks` extracted), ~207-213 (v1 contract calls it), 321-430 (`verify` delegates to shared `compare`; new `verifyV2`; `Listed`; row and newline checks gated by `lineDelimited`).
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/main/scala/scholarchain/core/Provenance.scala: 151-155 (doc of `RunManifest.configDigest`).
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/main/scala/scholarchain/core/StableIds.scala: 33-47 (five new ID kinds), 70-74 (tags), 765-931 (new ID objects).
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/main/scala/scholarchain/core/config/ConfigLoader.scala: `ValueKind` (two optional kinds), key-table doc, key at 133, keys at 145-149, env parsing 461-468, file readers 544-559, build 643-644, 661-666, 670-676.
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/main/scala/scholarchain/core/config/ScholarChainConfig.scala: imports 3-7, defaulted `analysis` field 24-25, 65-260 (`CommunityConfig.randomSeed`, `AnalysisConfig`, `AnalysisRunSettings`), object tail (`AnalysisScopeKeys`, `withoutAnalysis`, `digestWithoutGrouping`).
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/test/scala/scholarchain/core/SchemaVersionSuite.scala: counts 28 to 39, new contracts listed, one v1/v2 separation test.
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/test/scala/scholarchain/core/WireContractSuite.scala: frozen-example registry extended by the 11 new types.
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/test/scala/scholarchain/core/config/ConfigLoaderSuite.scala: seven tests for the analysis keys, appended.

COMMANDS RUN:
- `git ls-files -- modules/core` (exit 0); `git ls-files -- modules/core/src/test/resources … project` (exit 0)
- `sbt core/compile` (success)
- `sbt core/test`, five times during development: first four failed as expected on placeholder ID literals and fixture mistakes; the fifth passed 532/532.
- `git status --porcelain` (exit 0)
- `sbt Test/compile` (success, all modules)
- `sbt check`, once, as the final sbt command after the last edit (success)
- `git diff --stat`, `git diff --unified=0 -- modules/core/src/main` (exit 0)
- Refused by the guard and not executed: `git show --stat HEAD -- modules/core`, two `git grep` calls, `sbt core/testOnly …`, one over-long `git diff`.

TEST RESULTS (`sbt check`, observed): core passed 532 (25 production, 27 test sources); entityResolution passed 464; hadoop passed 85; tests passed 145; rag, root, services, sparkAnalytics, storage 0 tests. Total 1226 passed, 0 failed, 0 errors, 88 suites. `[success] Total time: 138 s`. `markTestRun` wrote `.claude/.last-test-run` (git_head=6be1f1db6eb22864ac20b051040412d8ddb4e0e1, worktree=dirty).

VALIDATION RESULTS:
- Format check, build guard and version ledger passed inside `sbt check`.
- Frozen pre-Phase-6 config digest unchanged.
- All 12 new goldens and pinned IDs match their formulas.
- `git status` shows changes only under `modules/core/**`, plus the orchestrator's untracked evidence under `docs/agent-runs/phase-06/`.

ASSUMPTIONS:
- Edge weight = joint publication count, so W = T minus unresolved part.
- A metrics record exists only for an identified researcher with at least one counted work in the window (P >= 1).
- A counted work without a year belongs to no window and is counted in `countedWorksWithoutYear`.
- Anchor-collision reason-code prefix `grouping.anchor-collision.` is taken from the dev-run diagnostics; entity-resolution should confirm.
- Added a `csv` part format because the Neptune bulk loader reads CSV; Neptune loads restricted to CSV.
- Singleton confidence is unknown with reason `no-supporting-decision`; resolved measured value = lowest supporting-decision confidence (uncalibrated, not a quality estimate).

RISKS:
- `sbt erDecisionGroupingDev` now refuses until dev manifests are re-pinned (BE-P6-02 code-scope check).
- Spark must reproduce A = T/P as an IEEE double division and the participation coefficient exactly as `1.0 - S/(d*d)` from integers, or validation refuses its output.
- Validator is in-memory, for dev and sample runs, not full scale.
- Workflow note: during development a temporary test-runner dump inside `AnalysisWireSuite` wrote pin values and failure messages to my scratchpad outside the repository; it was removed before `sbt check` and nothing remains in the tree.
- upickle 4 behavior on a missing `Option` key is not pinned; the C5 test is written so either behavior is refused.

MANUAL ACTION REQUIRED: Re-pin the entity-resolution dev manifests to a new code commit (route to entity-resolution-engineer) before running `sbt erDecisionGroupingDev` again. Request one focused code review of this diff.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none

NEXT GATE: Focused code review (code-reviewer), then human acceptance.

Open questions for the first Spark work order (SP-P6-01):
1. Consume the captured dev run bytes, or re-run `sbt erDecisionGroupingDev` after a re-pin?
2. Which researcher pairs get stored shortest-path records? The contract only bounds hops, paths per pair and evidence works per hop.
3. Who writes Neptune CSV files: the Spark export or the storage loader (storage-engineer)?
4. Which algorithms implement the bridge score and communities on the pinned Spark (GraphFrames has no sampled betweenness)? Is a community seed needed?
5. `graph.windows`, `bridge.*`, `community.algorithm`/`community.maxIterations` are in the base digest (pre-existing), so changing them changes upstream run IDs. Move to analysis scope? Needs a decision and digest migration.
6. Is a fractional edge weighting ever needed? It would require edge v3 and metrics v3.
7. Spark must read the decision run's confidences to derive identity confidence. Add a confidence projection to the grouping run output instead?
8. Spark runtime target (Scala 2.12 vs 2.13, review F2) still pending.
