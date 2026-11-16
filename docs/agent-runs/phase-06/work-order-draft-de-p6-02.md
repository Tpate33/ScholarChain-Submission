# Phase 06 work order draft DE-P6-02 (domain-engineer): Spark-facing core contracts

Status: DRAFT for human approval. Not dispatched. Prepared by the orchestrator on 2026-11-16, after dev run 01 (docs/agent-runs/phase-06/dev-run-01.md).

## Why now

- The pinned dev run is captured: decision run `run:736cb1706210a34f0958e658c061054c4e6b27b80847528db8619da84495b76a`, grouping run `run:84dd896fda43977b934008f10955191e78c2b93b80ec57c00f55dbb137dbd7d2`, code commit 8c1bee0, HEAD 6be1f1d.
- Any change under modules/core/** after this point makes `sbt erDecisionGroupingDev` refuse until the dev manifests are re-pinned to a new implementation commit (BE-P6-02 code-scope check). The captured run keeps its own code version; a re-run needs a new X/Y pinning pair.
- SP-P6-01 (Spark) is blocked on the core contracts below. It stays out of scope here.

## Work order (proposed text for domain-engineer)

Owner: domain-engineer. Scope: modules/core/** only. Add the versioned core contracts the Spark collaboration-analysis pipeline will produce and consume. Contracts, validation and contract tests only; no Spark code, no storage adapter, no AWS.

Inputs the contracts must fit (already in core, accepted): ResearcherAssignment v1, WorkAssignment v1 (graphEligibility counted / member-of-counted / enrichment-only), AuthorshipRecordV2, PublicationTeam v1, MatchDecision v2, PartManifest v1 with PartManifestVerifier, RunManifest v1, Provenance and StableIds. Read the existing Collaboration.scala, Graph.scala, Canonical.scala and Runtime.scala first and extend or version them rather than duplicating.

Decisions already made by the human (binding):
1. Spark builds the graph from the grouping run's canonical teams, not Hadoop TeamJob teams, and only from works with graphEligibility `counted` (O9: canonical authorship is kept for all representative works; teams and graph metrics only for DBLP-backed, graph-eligible works).
2. Metric semantics: T_r, A_r and M_r include unresolved coauthor occurrences, reported in separate fields; C_r, edges and W_r use identified coauthors only. P-7 (preprint linking) stays deferred.
3. Grouping quality is unevaluated; no contract may carry or imply a quality claim.
4. Every output carries provenance: source run IDs (grouping run, decision run, curated input run), code version, config digest, policy versions and schema version.

Contracts to add or version (confirm names against existing core; propose and justify any deviation):
- C1. ResearcherMetrics v2: P_r, C_r, T_r, A_r, M_r, W_r per researcher and per time window, with the unresolved-occurrence counts as separate fields, window bounds, and provenance.
- C2. Coauthor edge contract (versioned from the existing CoauthorEdge if present): identified endpoints only, temporal weights per window, supporting work IDs (counted works only), provenance.
- C3. Graph structure outputs: connected component membership, community membership with algorithm and parameters recorded, bridge measures, k-core/coreness, shortest-path support records (bounded, with evidence work IDs).
- C4. Temporal window analysis records and the window configuration contract (windows come from configuration, never hardcoded).
- C5. Identity confidence (decision package item C6): how per-researcher identity confidence from the grouping run (status, anchors, collisions) is carried into metrics and evidence, without a quality claim.
- C6. Semantic-summary input records and evidence-path records whose IDs resolve to stored evidence.
- C7. Load manifests for downstream stores (Neptune bulk load, DynamoDB, S3, S3 Vectors) as data contracts only, reusing PartManifest v1 for integrity.
- Validation in AssignmentValidation style: identified-only edge endpoints, counted-only works, window consistency, run-ID chain (grouping run's decisionRunId and inputRunId), and part-manifest verification before consumption.
- Config contract additions (graph windows, community algorithm parameters, bounds) with the digest scope stated, following the groupingPolicyVersion precedent.

Tests: contract round-trip, schema-version, validation and ID-stability tests with synthetic fixtures. Fixture shapes may mirror the dev run's record types, but tests must not read target/ or any run directory.

Constraints:
- Phase 5 remains accepted; do not change matching policy, features, thresholds or evidence. Do not change ER-P6 contracts already accepted (ResearcherAssignment, WorkAssignment, AuthorshipRecordV2, PublicationTeam, PartManifest) except by a new version with compatibility notes.
- Never read gold/**/heldout*, held-out labels, target/streams/**/goldSetEvalHeldout, target/local-runs/heldout, or modules/hadoop/target/local-pipeline/er-candidates/heldout/. No Grep under gold/ or target/. No *Heldout task. No `sbt clean` (it would delete the captured dev run and its Hadoop input).
- Run sbt check once as the final command and report exact counts. Request one focused code review.
- Do not start Spark or AWS work.

Return: the contracts, compatibility notes, test results, and open questions for the Spark work order (SP-P6-01 rerun).

## Points for the human before dispatch

1. Archive or accept the ephemeral dev run: the outputs under target/local-runs/dev/ and the Hadoop dev input under modules/hadoop/target/ are deleted by any `sbt clean` (allowed to every agent via `@SBT-ALL`). The work order forbids `sbt clean`, but other work orders do not.
2. Scope: C1-C7 in one work order or split (for example C1-C2 and validation first, C3-C7 next).
3. Whether the Spark rerun should consume the captured dev run bytes or re-run `sbt erDecisionGroupingDev` after a re-pin.
