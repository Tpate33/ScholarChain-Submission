# Phase 06 agent reports

## SP-P6-01: spark-engineer (2026-11-15), verbatim

## Summary

SP-P6-01 is blocked, and I wrote no code. Job 1 needs "accepted researcher and work entity links", and nothing upstream produces them. Neither the data product nor its core contract exists, and how to turn pairwise decisions into a researcher ID is entity-resolution policy that Spark cannot invent. Every other job (edges, P_r..W_r, components, communities, bridges, k-core, paths, temporal changes, summaries, exports) keys on canonical researcher and work IDs, so none of it can be built correctly. Several downstream records also have no core contract.

The work order and the escalation rules (docs/agents.md) say to stop with no partial change, so I did not build a local substitute.

```text
STATUS: BLOCKED-ON
OWNER: spark-engineer
WORK ORDER: SP-P6-01 (Phase 6). Verbatim text: /Users/taufeeqpatel/Desktop/ScholarChain/docs/agent-runs/phase-06/prompt.md
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN:
- git ls-files --others --exclude-standard -- docs/agent-runs/phase-06: ok (prompt.md)
- git ls-files -- modules/core modules/spark build.sbt project: ok (modules/spark has only two .gitkeep files)
- git grep (one call): refused by the guard (no-allowlist-entry); nothing ran
- git ls-files -- datasets and Phase 4/5 report paths: ok; git ls-files -- ER and Hadoop main sources and Phase 4/5 reports: ok
- git ls-files --others -- modules/hadoop/target/local-pipeline/er-candidates/dev: ok (dev split only; I read nothing under heldout)
- git status --porcelain: ok ("?? docs/agent-runs/phase-06/" only; tree unchanged by me)
- sbt check: NOT RUN (I changed no file, so there was nothing to verify)
TEST RESULTS: none run. No Spark test exists or was added.
VALIDATION RESULTS:
- Local Spark integration test: NOT RUN. There is no implementation, and the `sbt sparkIT` row is inactive (B10-alias-pending, build-engineer).
- Bounded EMR Spark run: NOT RUN (BLOCKED-ON infra-engineer / deploy-engineer / human; see below).
- Stage DAGs, partition counts, shuffle metrics, graph, component and community counts, metric distributions, path tests, temporal windows, integrity violations, output paths: NOT OBSERVED.
- What I checked by reading source:
  - The Hadoop pipeline stages are normalize, exact-ids, teams, blocking and candidates (Pipeline.scala). The dev run's curated output (run=2881724b...) has works, mentions, profiles, venues, institutions, topics, identity links, teams, pairs, blocks and MatchCandidates. It has no MatchDecision output and no mention-to-researcher assignment.
  - TeamJob emits PublicationTeam with researcherIds empty and every mention unresolved, and pair candidates over mention IDs.
  - Entity resolution (Resolver.toMatchDecision) gives pairwise decisions only, inside evaluation tooling. No job writes accepted, rejected, unresolved or conflict outputs to the curated zone.
  - core ResearcherId.scholarChain says the anchor is "chosen by the entity-resolution policy".
ASSUMPTIONS:
- Spark may not import entityResolution (CLAUDE.md: sparkAnalytics -> core only). So identity clustering policy must reach Spark as data under a core contract, not as code.
- Hadoop's RunLayout is not a core contract, so Spark must not copy its path segments; input locations must come from configuration.
- GraphX and GraphFrames are not needed. Components, label propagation, k-core peeling and bounded BFS can be written with the DataFrame API and checkpointing, so they are not a build blocker.
RISKS:
- Clustering pairwise ACCEPTED decisions transitively inside Spark without a policy would force-merge identities (role law 7, README Phase 5 "why") and corrupt every metric.
- One paper appears as separate DBLP, Crossref and OpenAlex works (Phase 5 report: Bigtable/Spanner copies). Without accepted work links and a canonical-work rule, P_r, T_r and jointPublicationCount would be counted up to three times.
- The Spark runtime target is still open (review F2): the build uses spark-sql_2.13 3.5.5, while EMR 7.x Spark is understood to be built for Scala 2.12. This blocks the EMR run, not local tests.
MANUAL ACTION REQUIRED:
- Human: decide F2 (the Spark/Scala runtime target) before any EMR run.
- Human: ratify an environment and a cloud-run operator (G6) before any bounded EMR run.
BLOCKED-ON OWNER: domain-engineer (primary: core contracts) and entity-resolution-engineer (identity clustering policy and the decision/assignment data product); then build-engineer, infra-engineer, deploy-engineer and human, each for the item named in the evidence below.
BLOCKED-ON EVIDENCE: see the numbered list after this block.
NEXT GATE: After the domain-engineer contracts (items 1-12) and the entity-resolution-engineer outputs (item 13) are delivered and committed, rerun SP-P6-01 in a fresh context to implement and test with sbt check. Then the human decides F2 and ratifies an environment and operator before any EMR run.
```

## BLOCKED-ON EVIDENCE

**domain-engineer (`modules/core`)** needs these contracts:

1. **Researcher assignment.** A record such as `ResearcherAssignment` (`scholarchain.researcher-assignment.v1`):
   - fields: memberId (`mention:` or `source-author:`), researcherId, identityVersion, anchorNamespace, anchorValue, the sorted ACCEPTED matchDecisionIds supporting it, policyVersion, provenance;
   - rules: exactly one researcherId per member, and the ORCID-form rule;
   - plus an explicit unresolved-mention record or status with a reason (no-accepted-decision, conflict, unresolved, rejected-only). Without it, AuthorshipRecord.researcherId and matchDecisionId cannot be filled, and unresolved mentions cannot be kept explicitly.
2. **Work assignment.** Member workId -> canonical workId, the representative rule, supporting decision IDs, policyVersion and provenance. Today WorkId has only per-source namespaces and no canonical-work form.
3. **Component.** A component record and ID formula, with window, size, algorithm, iterations and a converged flag. Run-level counts (largest-component fraction, singleton count) need a home too.
4. **Community.** Community membership, size and cross-community edge records, plus a community ID formula, with algorithm, maxIterations, the iterations run, convergence, tie-break rule and window. ResearcherMetrics holds only communityId.
5. **Bridge measures.** One field or record per measure: crossCommunityDegree, participationCoefficient, the distinct neighbouring-community count, and approximate betweenness with sampleSize, seed and approximation strategy. ResearcherMetrics has one bridgeScore and bridgeMeasure, and no field for the count of directly connected communities.
6. **Path evidence.** A record for selected pairs (window, source, target, hop count, node and edge IDs, maxHops, a found or not-found-within-bound status), or EvidenceItem v2 with a window. EvidenceItem has no window, which breaks role law 5.
7. **Path-length estimate.** A per-component and per-window record: sample size, seed, mean path length, effective-diameter percentile and its value.
8. **Temporal change.** A record comparing two windows (researcher or component/community, the deltas), plus inclusion rules and a comparability flag with a limitation statement.
9. **Semantic summary / TextChunk.** ChunkId and DocumentType exist, but there is no record carrying the text, entityId, window, the source metric, edge and work IDs it was computed from, and provenance.
10. **Load manifests.** Records for neptune, dynamodb, s3-vectors and s3: target kind, logical table or prefix as a config key, record schema, files with sha256 and row counts, runId. This must be agreed with storage-engineer.
11. **Window inclusion rule.** A rule for undated works, which CoauthorEdge years and TimeWindow cannot express. It must be explicit in edges and metrics, as a v2 or a run-level field.
12. **Spark configuration.** A `SparkConfig` section in ScholarChainConfig and ConfigLoader:
    - curated Hadoop run prefix and ER assignment prefix;
    - output prefix and checkpoint directory;
    - shuffle partitions;
    - maxIterations for components and k-core;
    - a pointer to the selected or benchmark path pairs, and the path maxHops;
    - path-length sample size and seed;
    - summary bounds.

    It also needs either a core curated-layout contract or one config key per Hadoop named output, so Spark does not copy RunLayout.

**entity-resolution-engineer:**

13. The identity clustering policy: how pairwise ACCEPTED decisions combine, whether a CONFLICT or REJECTED pair vetoes a merge, the anchor and identityVersion choice, ORCID-form eligibility, and the canonical-work representative. It also needs the production job (with hadoop-engineer if it runs as MapReduce) that writes decisions and items 1-2 as curated data (README Phase 5: "Produce accepted, rejected, unresolved, and conflict outputs").

**build-engineer:** the `sparkIT` alias (B10), and the F2 runtime coordinates once the human decides. No new library is requested.

**infra-engineer / deploy-engineer / human: what the EMR run needs**

- Job entrypoint: one main class per stage under package `scholarchain.spark`, taking only a run-manifest URI. Not written yet.
- Packaged jar: `scholarchain-spark-jobs-0.1.0.jar` from the existing `sbt packageJobs` (sparkAnalytics/assembly, Spark Provided). It must be compatible with the F2 decision.
- Run manifest: core RunManifest with stage `spark.<job>`, codeVersion, configDigest, the inputSnapshots of the consumed Hadoop run, the input run IDs as parameters, and attempt.
- Config keys, not literal values: input Hadoop curated-run prefix, ER assignment prefix, output prefix, checkpoint prefix.
- Expected sizing for the committed-sample scale: 1 primary plus 2 core nodes of a general-purpose instance class, with shuffle partitions taken from config. This is an estimate, not observed. infra-engineer owns the Terraform; cost and approval are the human's.

---

## ER-P6-01: entity-resolution-engineer (2026-11-15), verbatim

## ER-P6-01: proposal for handing entity-resolution output to Spark (read-only, nothing changed)

The Spark blocker is real. Phase 5 computes pairwise decisions but never saves them as data, and nothing assigns canonical researcher or work IDs. Saving the decisions needs no new contract and changes no decision. Grouping records under canonical IDs is a new rule layer, and it does change which records count as the same entity compared with the pairwise decisions. So the human has to approve it before anyone builds it.

### 1. What Phase 5 produces (verified by reading source)

- **Decision logic.**
  - `Resolver.decide` is pure and deterministic. It turns a `MatchCandidate` plus the identifiers of its two endpoints into a `ResolutionOutcome` (modules/entity-resolution/src/main/scala/scholarchain/entityresolution/policy/Resolver.scala:307-309, 420-451).
  - `Resolver.toMatchDecision` maps that outcome to the core `MatchDecision` (`scholarchain.match-decision.v2`) (Resolver.scala:981-1034).
  - The accepted settings are policy er-policy.v8, blocking er-blocking.v2 and features er-features.v2 (docs/agent-runs/phase-05/human-gate.md:131).
- **Core decision contract.**
  - `MatchDecision` v2 is strictly pairwise: it "says nothing about any third record" (modules/core/src/main/scala/scholarchain/core/MatchDecision.scala:293-298).
  - A CONFLICT must carry identifier conflicts, and an ACCEPTED must carry none (MatchDecision.scala:503-511).
  - The decision ID hashes the run ID (modules/core/src/main/scala/scholarchain/core/StableIds.scala:486-511), so decision IDs change on every decision run.
- **Nothing writes decisions as curated data.**
  - Hadoop runs five stages: normalize, exact-ids, teams, blocking, candidates. There is no decision stage (modules/hadoop/src/main/scala/scholarchain/hadoop/pipeline/Pipeline.scala:45-51, 99-111).
  - `GoldSetEvalMain` says "Writes nothing" (modules/entity-resolution/src/main/scala/scholarchain/entityresolution/evaluation/GoldSetEvalMain.scala:78).
  - `ReviewQueueMain queue` writes review-queue rows only for gold members (modules/entity-resolution/src/main/scala/scholarchain/entityresolution/review/ReviewQueueMain.scala:387-401).
  - `ReviewQueueMain diagnostics` decides every candidate in memory (ReviewQueueMain.scala:67-80) but writes only the sampled rows (ReviewQueueMain.scala:402-421).
  - Those rows hold an internal `SystemDecision`, not a core `MatchDecision` (modules/entity-resolution/src/main/scala/scholarchain/entityresolution/review/ReviewQueue.scala:44-59).
  - UNVERIFIED: whether any main-code file calls `toMatchDecision`. `git grep` is not on my allowlist; none of the main files I read calls it.
- **LLM adjudication.** No live model is connected (OA-1, human-gate.md:95). Score-band cases stay UNRESOLVED.
- **Identity contracts.**
  - `ResearcherId` has two forms: an ORCID form, or `researcher:sc:<identityVersion>:hash(anchorNamespace, anchorValue)`, where the anchor is "chosen by the entity-resolution policy" (StableIds.scala:235-268). `identityVersion` comes from `GraphConfig` (modules/core/src/main/scala/scholarchain/core/config/ScholarChainConfig.scala:41-53).
  - `WorkId` has only per-source namespaces (dblp, doi, openalex, orcid-work) and no canonical form (StableIds.scala:204-233).
  - Mention IDs are a hash of work ID and position (StableIds.scala:270-282).
  - Researcher endpoints are mention or source-author IDs (modules/core/src/main/scala/scholarchain/core/Candidates.scala:41-44).
  - `AuthorshipRecord.matchDecisionId` is required: "the ACCEPTED match decision that resolved the mention" (modules/core/src/main/scala/scholarchain/core/Identity.scala:72-77, 116). A singleton researcher cannot fill it.
  - `PublicationTeam` already has `unresolvedMentionIds` (modules/core/src/main/scala/scholarchain/core/Collaboration.scala:8-27).
  - There is no assignment schema in `SchemaVersions` (modules/core/src/main/scala/scholarchain/core/Contract.scala:30-54).
- **Work target is version-level.** Same publication at the same stage; preprint versus published is a non-match; lifecycle aggregation was deferred to Phase 6 as P-7 (docs/agent-runs/phase-05/decisions-05.md:9-13, 53).
- **Dev scale.** The dev run has 90 works, 286 mentions, 6 profiles, 296 researcher pairs and 78 work pairs (docs/agent-runs/phase-05/dev-run-02.md:35-41).
- **Same layout coupling Spark raised.** The ER reader hardcodes the Hadoop folder names (`CuratedRunReader.scala:24-30, 60-72`).
- **Team pairs come from every source's copy.** The teams job reads works from all sources (Pipeline.scala:490-503). Copies of one paper therefore produce duplicate coauthor pairs until they are mapped to one canonical work.

### 2. Saving the decisions as data (no decision changes)

- **What:** an ER batch entry point, `DecisionRunMain` in `modules/entity-resolution`.
  - It reads a completed curated run with the existing `CuratedRunReader`.
  - It applies the effective policy exactly as `ReviewQueueMain.policyOf` does (ReviewQueueMain.scala:253-267).
  - It calls the same `Resolver.decide` and `Resolver.toMatchDecision` for every candidate.
  - It writes one `MatchDecision` v2 per candidate as JSONL under `decisions/{researcher,work}/`, plus a core `RunManifest` (stage `er.decisions`, the input run's snapshots, policy, blocking and feature versions, config digest, attempt) and `_SUCCESS`.
  - Outputs are create-once.
- **Proposed rules:**
  - Fail closed: if any candidate is refused, the run writes nothing.
  - `decidedAt` comes from a manifest parameter, so a rerun of the same manifest is byte-identical.
  - No LLM call; band cases stay UNRESOLVED with the rule `probabilistic.llm-band`.
- **Why nothing changes:** it is the same pure function, policy, features and thresholds. The only new value is the decision run ID inside the decision IDs.
- **Contract:** none new. `MatchDecision` v2 and `RunManifest` already exist.
- **Scale:** at full scale this runs as one JVM holding every work and profile in memory. UNVERIFIED whether that fits; there is a scale decision below.

### 3. Grouping under canonical IDs (a new, separately versioned rule set, proposed as `er-grouping.v1`)

**Shared mechanism.**
- Every record is a node: every author mention and every source author profile, and every work.
- Only ACCEPTED decisions are links. REJECTED, UNRESOLVED and CONFLICT decisions never link records.
- "Cannot-link" pairs are pairs that must never end up in one group:
  - any pair with a REJECTED or CONFLICT decision;
  - any two members whose top-tier identifiers disagree (both assert one, no shared value), using the existing `ExactIdentifiers.disagreement` and the policy's own scheme rules (Resolver.scala:314-322). For researchers that is ORCID and DBLP person ID; for works, DOI and arXiv base ID.
  - any two members with a lower-tier blocking disagreement that no top-tier agreement overrides, matching step 6a of the policy (Resolver.scala:334-345);
  - two mentions on the same work, or differing DBLP homonym numbers.
- Links are merged one at a time in a fixed order: match path strength (exact-id, then deterministic, then probabilistic, then LLM), then higher confidence, then the canonical pair bytes. Decision IDs are not used for ordering because they change per run.
- A merge that would put a cannot-link pair into one group is skipped. The skipped decision is recorded on the affected members and flagged for human review.
- Conservative fallback (H2-b): leave every group that contains any violation entirely as singletons.

**(a) Accepted links that imply a conflicting merge.** Handled by the skip rule above. Two different ORCIDs or DBLP person IDs can therefore never share a group.

There is also an anchor collision case. Two separate groups that both assert the same ORCID or DBLP person ID, with no link between them, would otherwise receive the same ID and merge silently downstream. Under this proposal neither group uses that identifier as its anchor, both are flagged `anchor-collision`, and nothing is merged.

**(b) An author mention that stays unresolved.** Every mention and profile gets exactly one assignment with a status:
- `resolved`: in a group of two or more joined by kept ACCEPTED links.
- `singleton`: no ACCEPTED link and no UNRESOLVED, CONFLICT or skipped decision. It gets its own ID.
- `unresolved`: the rest. Recommended: it gets no researcher ID and goes to `PublicationTeam.unresolvedMentionIds`. It still counts in team size but adds no coauthor edge.
- Alternative: give it a provisional singleton ID marked unresolved.

**(c) Several sources describing the same work.**
- Groups are version-level, as Phase 5 decided.
- The canonical work ID is one of the members' existing IDs, so no new `WorkId` namespace is needed. Proposed order: DBLP first (the required seed source), then `work:doi:` (Crossref), then OpenAlex, then ORCID works; ties go to the smallest ID by bytes.
- Every work keeps a canonical ID (itself if not grouped). Its status marks pending UNRESOLVED decisions.
- Spark needs one more rule: the team of a canonical work comes from the representative record's mentions only. Otherwise the DBLP, Crossref and OpenAlex copies are counted up to three times.
- Preprint and published version stay separate until the human decides P-7.

**(d) ID stability across reruns and policy versions.**
- Researcher ID:
  - ORCID form when the group has exactly one ORCID and no collision;
  - otherwise the `sc` form anchored on the single DBLP person ID, then the single OpenAlex author ID, then the smallest member ID.
- ORCID and DBLP-ID anchors stay the same across reruns, snapshots and policy versions as long as the group keeps that identifier.
- The smallest-member anchor is deterministic for the same inputs. It changes when the group gains a smaller member or loses its anchor member. It also depends on mention IDs, which change if a source reorders its authors.
- Changing the anchor rules requires bumping `identityVersion`.
- IDs are never derived from decision IDs.

### 4. Does grouping change who counts as the same entity? Yes, in two ways

Synthetic illustration, labelled synthetic. I did not extract a dev example: the dev output is compressed and I have no grep.

- **Merging without a direct decision.** m1 (DBLP, pid 11/1) and m2 (OpenAlex A1) are ACCEPTED. m2 and m4 are ACCEPTED. m1 and m4 were never a candidate. Grouping puts m1 and m4 together, which Phase 5 never decided.
- **Overriding an ACCEPTED pair.** m1 (pid 11/1) and m2 are ACCEPTED. m2 and m3 (pid 22/2) are ACCEPTED. m1 and m3 disagree on DBLP person ID, so that pair is REJECTED or CONFLICT (Resolver.scala:702-729). Plain transitive closure would merge m1 and m3, contradicting a Phase 5 decision. The skip rule keeps the m1–m2 group and skips m2–m3, so m3 becomes `unresolved` and an ACCEPTED pair ends up in two different groups.
- **Works.** `work:dblp:conf/x/A20`, `work:doi:10.1/abc` and `work:openalex:W1` are linked by DOI agreement, so the canonical ID is `work:dblp:conf/x/A20`. An arXiv preprint copy (`work:openalex:W2`) is UNRESOLVED as cross-stage and stays a separate work.

**What this means under D3(c).** My proposal does not change the match policy, features, thresholds or any pairwise decision. The accepted Phase 5 held-out metrics remain valid for pairwise decisions only. Grouping is a new rule layer that no gold set has evaluated, so:
- (i) the human must approve the rules and record that approval in Phase 6 `human-gate.md`;
- (ii) Phase 5 metrics must not be presented as quality metrics for canonical IDs;
- (iii) any claim about canonical-ID quality needs a new evaluation with human-written labels.

Grouping rules must not be tuned on dev or held-out labels (law 5). Whether the human treats this as a D3(c) change is the human's call; I am not deciding it.

### 5. Minimum core contracts (domain-engineer) and owners

- **C1 `ResearcherAssignment`** (`scholarchain.researcher-assignment.v1`).
  - Fields: `memberId` (mention or source-author), `workId` (for mentions), `status` (resolved, singleton or unresolved), `researcherId` (optional), `identityVersion`, `anchorNamespace`, `anchorValue`, `groupSize`, `supportingDecisionIds`, `vetoedDecisionIds`, `pendingDecisionIds`, `reasonCodes`, `groupingPolicyVersion`, `matchPolicyVersion`, `decisionRunId`, `provenance` (derived).
  - Invariants: one record per member per run; `researcherId` present exactly when status is not unresolved; an ORCID-form ID matches its anchor and some member asserts that ORCID; an `sc`-form ID equals `ResearcherId.scholarChain(...)`; `resolved` needs a group of two or more and supporting decisions; `singleton` has all lists empty.
  - Dataset-level invariants: one anchor per researcher ID; no two mentions of one work share a researcher ID.
- **C2 `WorkAssignment`** (`scholarchain.work-assignment.v1`).
  - Fields: `workId`, `canonicalWorkId`, `isRepresentative`, `status`, `representativeRule`, `groupSize`, the three decision-ID lists, `reasonCodes`, the two policy versions, `decisionRunId`, `provenance`.
  - Invariants: exactly one representative per group; the representative maps to itself.
- **C3 `AuthorshipRecord` v2.** `matchDecisionId` becomes optional, plus an assignment status, so singletons are allowed.
- **C4 Config.** Keys for the grouping policy version and the decision and assignment output locations (overlaps Spark item 12). Optionally a core contract for the curated-run layout.

| Artifact | Owner |
|---|---|
| C1–C4 | domain-engineer |
| `DecisionRunMain`, grouping function and their tests | entity-resolution-engineer |
| sbt alias for a dev decision or grouping run | build-engineer |
| Allowlist row for that alias | factory-engineer, with human ratification |
| A MapReduce version, if chosen | hadoop-engineer; also needs a constitutional amendment, because CLAUDE.md lets hadoop import only normalization and feature functions from entityResolution, and `Resolver` is policy |
| Consuming the outputs | spark-engineer |

### 6. Smallest next work order

**ER-P6-02** (entity-resolution-engineer): decision persistence only. It needs no new contract and no grouping decision.
- **Scope:** `DecisionRunMain` plus its tests in `modules/entity-resolution`.
- **Inputs:** a completed curated run directory and the external config.
- **Outputs:** `MatchDecision` v2 JSONL, a `RunManifest` and `_SUCCESS`.
- **Tests:**
  - every line decodes through `Json.decode`;
  - each decision equals `toMatchDecision(decide(...))`;
  - outcome, path, rules, reasons, conflicts and score equal `ReviewQueue.row`'s `SystemDecision` for the same candidate;
  - one decision per candidate;
  - two runs with the same manifest give byte-identical output;
  - refuses a policy or config mismatch;
  - refuses partial output when a candidate is refused;
  - never overwrites output;
  - no LLM call.
  - Fixtures are synthetic, with no gold files.
- **Gate:** `sbt check`, then code-reviewer.

After that: **DE-P6-01** (C1–C4), once the human has made the decisions below. Then **ER-P6-03**: grouping as `er-grouping.v1`, with tests for each case in section 4, anchor collisions and stability. Then build-engineer adds the dev alias. Then SP-P6-01 is rerun.

### 7. Decisions for the human

- **H1.** Approve `er-grouping.v1` as a new layer that adds transitive merges and can skip ACCEPTED links, and record the D3(c) classification and disclosure in Phase 6 `human-gate.md`.
- **H2.** Conflict handling: (a) skip-at-merge in strength order (recommended) or (b) leave any conflicted group entirely as singletons. Also confirm the cannot-link set, including blocking disagreements and same-work mentions.
- **H3.** Unresolved mentions: (a) no ID, listed in `unresolvedMentionIds` (recommended), or (b) a provisional singleton ID.
- **H4.** Anchor order for researcher IDs (ORCID, DBLP person ID, OpenAlex author ID, smallest member) and the anchor-collision rule; which `identityVersion` to use.
- **H5.** Canonical work order (DBLP, DOI, OpenAlex, ORCID work) and the team-from-representative rule for Spark.
- **H6.** P-7 lifecycle aggregation (preprint versus published version): keep it deferred, or put it in Phase 6 scope.
- **H7.** Scale path for decisions and grouping at full data: an ER single-JVM batch, or MapReduce (needs the amendment above).
- **H8.** Whether canonical-ID quality must be evaluated with new cluster-level human labels before any claim is made about it.
- **H9.** `decidedAt` from a manifest parameter and fail-closed refusal in ER-P6-02.

```text
STATUS: PROPOSAL
OWNER: entity-resolution-engineer
WORK ORDER: ER-P6-01 (Phase 6), read-only assessment and proposal; verbatim at /Users/taufeeqpatel/Desktop/ScholarChain/docs/agent-runs/phase-06/prompt-er-p6-01.md; responds to SP-P6-01 (/Users/taufeeqpatel/Desktop/ScholarChain/docs/agent-runs/phase-06/agent-report.md) items 1, 2, 11, 13
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN:
- git ls-files -- modules/entity-resolution modules/core docs/agent-runs/phase-05: ok
- git ls-files -- modules/hadoop/src/main modules/spark datasets/schemas: ok
- git grep -n ... -- Bibliographic.scala: REFUSED by guard (shell-syntax-gate, quote character); nothing executed
- No sbt command of any kind (by instruction)
TEST RESULTS: none run, by instruction
VALIDATION RESULTS: Source and evidence read with Read only. Not read: anything under gold/**, any labels, any held-out output, any target/ path. human-gate.md read only up to line 131, stopping before its held-out metrics section. Not verified: whether any main code calls toMatchDecision (no grep); real dev examples of the section 4 cases (dev output is under target/ and compressed); full-scale memory fit.
ASSUMPTIONS: The Phase 5 work target stays version-level (decisions-05). The Phase 5 pairwise policy er-policy.v8 / er-blocking.v2 / er-features.v2 is unchanged. The grouping layer reuses the policy's own identifier dispositions and adds no thresholds. GraphConfig.identityVersion is the identity version to use. Spark keeps consuming data only, under core contracts.
RISKS: Grouping changes entity membership relative to pairwise decisions (section 4) and has no gold evaluation. A smallest-member anchor can change when a group's membership changes. Without the representative-team rule, coauthor counts are inflated by copies of one paper from several sources. The single-JVM decision run may not scale. CuratedRunReader hardcodes the Hadoop layout. Decision IDs change per run by design.
MANUAL ACTION REQUIRED: Human decides H1-H9 (section 7) and records the H1 and D3(c) disclosure in Phase 6 human-gate.md.
BLOCKED-ON OWNER: human (H1-H9) before DE-P6-01 and ER-P6-03; none for ER-P6-02 (persistence), which needs no new contract or policy decision
BLOCKED-ON EVIDENCE: Identity.scala:72-77,116 (matchDecisionId required); StableIds.scala:204-268 (no canonical work form; anchor left to ER policy); MatchDecision.scala:293-298 (decisions are pairwise only); decisions-05.md:9-13,53 (lifecycle aggregation deferred to Phase 6); Pipeline.scala:45-51 and GoldSetEvalMain.scala:78 (no decision output exists)
NEXT GATE: Orchestrator presents H1-H9 to the human. ER-P6-02 (persistence) may be routed now, gate sbt check plus code-reviewer. Then DE-P6-01 (contract tests, sbt check), ER-P6-03 (sbt check, code review, human match-policy review), build-engineer alias, and the SP-P6-01 rerun.
```

---

## ER-P6-02: entity-resolution-engineer (2026-11-15), verbatim

## ER-P6-02: the decision exporter is built and `sbt check` passes

**Summary.** The repository had no writer for the core MatchDecision v2 format, so I added a small exporter in `modules/entity-resolution`. It decides every candidate of a completed curated run with the existing `Resolver.decide` and `Resolver.toMatchDecision`. It writes exactly one decision per candidate and publishes the whole dataset in one step, or nothing. Policy, features, thresholds, Resolver logic and the Phase 5 tests and evidence are unchanged. `sbt check` passed: 928 tests, 0 failed. A dev-split run was not done, because no allowlisted command covers it.

**Decision for you: one empty file to delete.** `modules/entity-resolution/src/main/scala/scholarchain/entityresolution/decisions/DecisionRunMain.scala` is an empty, untracked leftover. I first put the entry point there, but the Phase 5 `LeakageStructureSuite` forbids any package except `review` and `evaluation` from naming the review package. I moved the entry point into `review` rather than weaken that test. I can't delete files (no `rm` on my allowlist), so I emptied it. Please delete it in a terminal, or leave it out of the commit. It compiles as an empty unit.

**1. Existing writer check (asked about in ER-P6-01).** No Grep tool was available in this session and `git grep` is not allowlisted, so I listed files with `git ls-files` and read the candidates.
- spark, storage, rag and services contain only `.gitkeep`.
- `GoldSetEvaluation`, `GoldSetEvalMain`, the adjudication sources, `EntityResolution` and the review sources never write a MatchDecision.
- The Hadoop `CandidateJob` does not import `MatchDecision`.
- Residual: I read imports and bodies of the likely files, not a full-text search of every main file.

**2. What was built**
- **`decisions/DecisionExport.scala`** holds the decision logic and does not reference `review`.
  - `decideAll` decides every candidate, sorted by candidate ID, with `EndpointIdentifiers`, `Resolver.decide` and `Resolver.toMatchDecision`, using the policy it is given. It makes no LLM call.
  - It refuses the whole run if anything fails: a manifest mismatch, a candidate from another run, a duplicate candidate ID, any candidate that cannot be decided or mapped, or a duplicate decision ID.
  - Also in this file: `publish`, `publishedRuns` and `readPublished`.
- **`review/DecisionRunMain.scala`** is the entry point: `DecisionRunMain <decision run manifest .json> <curated run dir> <output root>`.
  - Configuration comes from `SCHOLARCHAIN_CONFIG_FILES` and is applied exactly as the existing tooling does, through `ReviewQueueMain.effectivePolicy`.
  - It reads the curated run with `CuratedRunReader` plus the run's `_run-manifest.json`.
  - It prints counts, settings (including model=none) and config evidence. Exit 0 means published; exit 2 means refused and nothing published.

**3. Run provenance.** The decision run manifest is a core RunManifest with:
- stage `er.decisions`;
- the same input snapshots as the curated run;
- `configDigest` equal to the resolved configuration's digest;
- exactly these parameters:
  - `decidedAt` (UTC instant; H9);
  - `inputRunId`, which must equal the curated run's ID and every candidate's `provenance.runId`;
  - `policyVersion`, `blockingVersion` and `featureVersion`, which must equal the effective policy's.

The decision run ID is computed from this manifest, so `attempt` and `codeVersion` are recorded in it. `codeVersion` is not checked against git.

**4. Output and what a failed attempt leaves**
- Published layout under the output root, `run=<hex>/`:
  - `_run-manifest.json`
  - `decisions/researcher/part-00000.jsonl`
  - `decisions/work/part-00000.jsonl`
  - `_SUCCESS`
- Everything is written into `.staging-run=<hex>-<random>/`, with `_SUCCESS` last, then renamed to `run=<hex>` in one atomic step.
- An existing `run=<hex>` is never overwritten. The publish refuses before writing and checks again just before the rename.
- On failure the staging folder is deleted. Only a killed process (or a failed delete) can leave a `.staging-*` folder.
- Readers ignore leftovers: they accept only a folder named exactly `run=<64 hex>` that contains `_SUCCESS`. The leading `.` is also hidden from Hadoop and Spark listings.

**Test results (observed)**
- `sbt entityResolution/test`: 398 passed, 0 failed with the first 5 new tests; 399 passed, 0 failed after adding the sample probe.
- `sbt check`: all checks and per-project tests passed in 134 s:

| Project | Passed | Failed |
|---|---|---|
| core | 299 | 0 |
| entityResolution | 399 | 0 |
| hadoop | 85 | 0 |
| tests | 145 | 0 |
| rag, services, sparkAnalytics, storage, root | 0 (no tests) | 0 |

  The test marker reads suites=77, passed=928, skipped=0, worktree=dirty, git_head=426df59.

**Dev diagnostics (committed-sample probe, not gold)**
- Input: the Hadoop local-pipeline test output from committed samples (`run=9c5c86b3…`), published as decision run `run=ce17b72e…`.
- 119 decisions for 119 candidates: researcher 109, work 10.
  - researcher: ACCEPTED/deterministic-bibliographic 86, ACCEPTED/exact-id 9, ACCEPTED/probabilistic 12, UNRESOLVED/probabilistic 2
  - work: ACCEPTED/exact-id 10
- These match the existing review-queue probe's strata for the same run.
- No confusion matrix, false-positive or false-negative examples, or threshold sensitivity were produced: no labels were used and `goldSetEvalDev` was not run (by instruction).

**Risks**
- Publication is atomic only on a local POSIX file system. S3 has no atomic rename; there the rule falls back to `_SUCCESS`-last, which this code does not implement.
- A rename onto an empty `run=<hex>` folder created in the instant between the check and the rename would replace that empty folder; no data would be lost.
- The run is a single JVM holding every work and profile in memory; fit at full scale is not verified (H7, deferred).
- The exporter, like `CuratedRunReader`, hardcodes the Hadoop folder names.
- Decision IDs change with every decision run, by design.

```text
STATUS: COMPLETE
OWNER: entity-resolution-engineer
WORK ORDER: ER-P6-02 (Phase 6), H9 approved, H1-H8 deferred; verbatim at /Users/taufeeqpatel/Desktop/ScholarChain/docs/agent-runs/phase-06/prompt-er-p6-02.md
FILES CREATED:
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/decisions/DecisionExport.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/review/DecisionRunMain.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/decisions/DecisionExportSuite.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/decisions/DecisionRunMain.scala (EMPTY leftover, untracked; to be deleted by the human)
FILES MODIFIED: none (no existing file changed)
COMMANDS RUN:
- git ls-files -- modules/entity-resolution modules/core: ok
- git ls-files -- docs/agent-runs/phase-06 modules/hadoop/src/main build.sbt project: ok
- git ls-files -- modules/spark modules/storage modules/rag modules/services tests: ok
- sbt entityResolution/Test/compile: success
- sbt entityResolution/test: Passed: Total 398, Failed 0
- sbt check: success, 134 s, 928 passed (core 299, entityResolution 399, hadoop 85, tests 145)
- sbt entityResolution/test (re-run to capture probe output, no source change): Passed: Total 399, Failed 0
- git status --porcelain: only docs/agent-runs/phase-06/ and the new entity-resolution files are untracked
TEST RESULTS: sbt check passed: 928 passed, 0 failed, 0 skipped, 77 suites. 6 new tests in DecisionExportSuite, all passed: (a) decision parity; (b) reproducibility; (c) failure without partial publication; (c) no overwrite; committed-sample probe; mismatch refusals (16 cases). The 5 non-probe tests fail without the change because the code they test does not exist (not compilable); I did not run a revert to observe that.
VALIDATION RESULTS: committed-sample probe (dev diagnostic, no labels): 119 decisions for 119 candidates (researcher 109, work 10), counts as listed above, matching the existing review-queue probe. Dev-split exporter run: NOT RUN, no allowlisted command covers it. goldSetEvalDev: not run (by instruction). No gold file, label or held-out path was read; no Grep was run under gold/ or target/.
ASSUMPTIONS: Policy er-policy.v8 / er-blocking.v2 / er-features.v2 unchanged; thresholds and band come from configuration through ReviewQueueMain.effectivePolicy. Hadoop candidates carry the Hadoop run ID as provenance.runId (CandidateJob.provenance). The curated run holds _run-manifest.json (Hadoop RunLayout). The output root is a local POSIX file system. codeVersion is recorded, not verified.
RISKS: no atomic rename on S3; rename onto an empty existing run dir in a race; single-JVM scale unverified (H7); Hadoop folder names hardcoded; decision IDs change per decision run; the writer check was done by reading files, not a full-text search.
MANUAL ACTION REQUIRED: Human: delete the empty file modules/entity-resolution/src/main/scala/scholarchain/entityresolution/decisions/DecisionRunMain.scala, or leave it out of the commit, then re-run sbt check. Orchestrator: route the change to code-reviewer.
BLOCKED-ON OWNER: build-engineer (an sbt alias for a bounded dev-split decision run) and factory-engineer (an allowlist row for it, human ratification). Optional, not needed for COMPLETE.
BLOCKED-ON EVIDENCE: no active allowlist row for entity-resolution-engineer can run DecisionRunMain on the dev split; existing rows are only sbt check/compile/test, own-module tasks and sbt goldSetEvalDev. A dev run also needs a dev-run decision manifest and config (decidedAt, inputRunId, configDigest), which the alias would provide.
NEXT GATE: code-reviewer review of the three new files, then the human deletes the empty file and re-runs sbt check, then human acceptance. Afterwards, optionally, build-engineer adds the dev decision-run alias and factory-engineer the allowlist row. Canonical grouping (H1-H8, DE-P6-01, ER-P6-03) stays deferred.
```

---

## DE-P6-01: domain-engineer (2026-11-15), verbatim

## DE-P6-01: grouping and integrity contracts C1-C5 in modules/core

C1-C5 are implemented with tests, inside `modules/core/**` only. `sbt check` was run once, as the last command after the last edit, and passed: **1037 tests in 81 suites, 0 failed, 0 skipped**.
- Per project: core 402, entityResolution 405, hadoop 85, tests 145; rag, root, services, sparkAnalytics and storage have no tests (0).
- Core went from 300 tests (my first run, which added only the frozen-digest test; the other 299 already existed) to 402.
- No existing frozen example, ID formula, `MatchDecision` v2 or `RunManifest` was changed. In the two existing wire/schema test files the only changes are the added record names and counts (see FILES MODIFIED).

### 1. What I found in the existing core files (clarification 7)
- **`ResearcherId.orcid(raw)`** normalises its input, so a URI-form ORCID gives the same ID. C1 therefore also requires `anchorValue` to be the bare, valid ORCID, so each researcher ID has exactly one canonical anchor.
- **`ResearcherId.scholarChain(v, ns, value)`** needs `ns` to be a token and `value` to be printable ASCII. A `member` anchor whose value is a `mention:...` ID fits, so no new formula is needed.
- **`WorkId`** has only per-source namespaces. The canonical work ID is therefore the representative's own work ID; no new namespace.
- **`MentionId.of(workId, position)`** is used to tie each mention member to its work and position.
- **`AuthorshipRecord` v1** requires `matchDecisionId` (`Identity.scala:88,116`), so a singleton cannot be expressed in v1.
  - Compatible choice: v1 keeps its class, contract, codec, `SchemaVersions.AuthorshipRecord` value and frozen example, and still decodes.
  - v2 is a separate record type, `AuthorshipRecordV2`. It has to be separate because `SchemaVersionSuite` requires each contract to accept exactly its current version, and `WireContractSuite` requires one frozen example per record type.
  - `AuthorshipRecords.decode` reads either version by `schemaVersion`.
  - Whether any v1 record was ever written is UNVERIFIED: I had no search tool, and none of the files I read writes one.
- **Config digest.** `ScholarChainConfig.digest` is the SHA-256 of the config's compact JSON, so any new field would change every existing digest.
  - I first added a test freezing the exact JSON and digest of the existing base config, and confirmed it passes on the unchanged code (300 passed).
  - The new fields are `Option` with a Scala default of `None`. uPickle leaves default values out of the JSON, so configs that don't set them keep the same digest. That test still passes after the change.
- **Frozen examples:** none changed; the diff only extends the record-name set.

### 2. Contracts
- **Schema versions added:** `scholarchain.researcher-assignment.v1`, `scholarchain.work-assignment.v1`, `scholarchain.authorship-record.v2` and `scholarchain.part-manifest.v1`. `SchemaVersions.all` grows from 24 to 28; `authorship-record.v1` is not retired.

**C1 ResearcherAssignment** (one record per mention or source author profile):
- Fields:
  - the member, plus workId and position for mentions only;
  - status `resolved|singleton|unresolved`;
  - an optional researcherId and anchor, identityVersion and groupSize;
  - five sorted, disjoint decision-ID lists: supporting, vetoed, pending, conflict, rejected;
  - sorted reason codes;
  - groupingPolicyVersion and matchPolicyVersion, which must differ;
  - decisionRunId, assignedAt (UTC) and provenance.
- Provenance rules: derived; sourceRecordId = memberId; policyVersion = grouping policy; no model or prompt; its run must not be the decision run.
- **Singleton:** has a researcher ID and needs no decision (clarification 2).
- **Unresolved:** has no researcher ID or anchor, and must keep at least one pending, vetoed or conflict decision. A record that has one is refused.

**C2 WorkAssignment:**
- `isRepresentative` is true exactly when `workId == canonicalWorkId`.
- `single` means groupSize 1, the work is its own canonical work, and there is no supporting decision.
- `grouped` means groupSize of 2 or more and at least one supporting decision.
- **Graph eligibility (clarification 4):** I added a field `graphEligibility` with three values:
  - `counted`: only on a representative that is a DBLP work;
  - `member-of-counted`: only on a non-representative whose canonical work is DBLP;
  - `enrichment-only`: never on a DBLP work or on a member of a DBLP canonical work.
- Why three values and not a boolean: a non-representative copy of a counted work is then distinct from an enrichment-only work, and consumers select counted works with one equality test instead of re-deriving it.
- Together with the representative rule, every label for a DBLP work in a non-DBLP group is refused (tested). So a group with a DBLP member must have a counted DBLP representative.

**C3 AuthorshipRecordV2:**
- `supportingDecisionIds`: non-empty for resolved, empty for singleton.
- `matchConfidence`: required for resolved and absent for singleton. I defined it as the lowest confidence among the supporting decisions, which `checkAuthorshipConfidence` verifies.
- Unresolved is refused. The record carries both policy versions and the decision run.

**Dataset validators** (`AssignmentValidation`, pure functions):
- `checkResearchers`:
  - one record per member, and one run (versions, decision run, identity version, grouping run);
  - per researcher ID: one anchor, groupSize equal to the member count, no two mentions of one work;
  - every decision ID appears on exactly two records in the same role;
  - a supporting decision joins records of one group; a vetoed decision never does;
  - every group is connected by its supporting decisions.
- `checkWorks`:
  - one record per work, and one run;
  - exactly one representative per canonical work, which is the record of that work itself;
  - groupSize consistent, graph eligibility consistent, and the same decision-reference rules.
- `checkRun`: researcher and work assignments come from one run, and every mention's work has a work assignment.
- `checkAgainstDecisions`: every decision appears on both of its endpoints, in the list of its outcome; every listed ID resolves; the decisions belong to the assignments' decision run and match policy.
- **`checkAuthorships` (clarification 3):** exactly one record per identified mention on a representative work, none on copies, each equal to its assignment.
- **`checkTeams` (clarification 3):** one `PublicationTeam` per counted work that has mentions, and none for any other work.
  - researcherIds are the representative's identified mentions, in author order;
  - `unresolvedMentionIds` are its unresolved mentions;
  - teamSize counts every mention on the representative.

**C4 config:** three optional keys are added to `scholarchain.config.v2`:
- `entityResolution.groupingPolicyVersion`: a token, and must differ from `entityResolution.policyVersion`.
- `entityResolution.decisionRunRoot` and `entityResolution.assignmentRunRoot`: each `s3://bucket[/prefix]` or an absolute local path, with no `..`, `.`, empty segment, trailing slash or whitespace. Neither root may equal or contain the other.
- Existing configs load unchanged. Values are validated when set.
- A feature that needs the keys refuses a config without them through `GroupingRunSettings.from` (which returns `MissingKey` with the environment variable name) or `EntityResolutionConfig.requireKey`.
- Precedence is unchanged: `SCHOLARCHAIN_CFG_*` environment variables, then later files, then earlier files; no built-in defaults; an empty environment value unsets an optional key.

**C5 PartManifest** (`_parts.json`):
- Contents: `runId` plus files sorted by path, each with path, record schema, rowCount, byteCount and sha256.
- Manifest rules:
  - paths are relative and `/`-separated, with no `..`, `.`, backslash or absolute path;
  - no two paths differ only in letter case, and no file is also a directory;
  - the two marker files (`_parts.json`, `_SUCCESS`) are never listed;
  - `_run-manifest.json` must be listed, as exactly one row;
  - an empty file has zero rows and the hash of zero bytes.
- `ObservedFile.of` and `ObservedFile.Accumulator` compute bytes, LF count, sha256 and final-LF from bytes the caller supplies. Core does no I/O.
- `PartManifestVerifier.verify(manifest, expectedRunId, observed)` refuses:
  - the wrong run;
  - a missing or extra file, a duplicate observation, or a missing marker file;
  - a byte, row or sha256 mismatch;
  - a non-empty file without a final LF.
- Tested refusals (clarification 6):
  - a file cut at a line boundary: it still ends in LF, so it is refused on fewer rows, fewer bytes and a different sha256;
  - a file cut mid-line, including the missing final LF;
  - a same-length corruption (sha256 alone);
  - a missing file and an extra file.
- An exact copy is accepted.

**Sample frozen record** (unresolved mention; its provenance is shown as `...` here and is literal in the test):
```
{"schemaVersion":"scholarchain.researcher-assignment.v1","memberId":"mention:191bdccd750f5fb2dacbfd3137030f10a141fac2763d35652f24df6cd3bdc162","workId":"work:dblp:conf/example/Synthetic22","position":0,"status":"unresolved","researcherId":null,"identityVersion":"v1","anchorNamespace":null,"anchorValue":null,"groupSize":1,"supportingDecisionIds":[],"vetoedDecisionIds":[],"pendingDecisionIds":["match:b0cc69cba29523b0206adfd97ba4b792de31769aca639a6d80f48c5d3a3b5c95"],"conflictDecisionIds":[],"rejectedDecisionIds":["match:eda80af67f84aa52563783e88c8e23f20d927d2966e9648e0f826344de3250f6"],"reasonCodes":["grouping.unresolved.pending-decision"],"groupingPolicyVersion":"er-grouping.v1","matchPolicyVersion":"er-policy-1.0.0","decisionRunId":"run:8dc188c7670e600b1e1f8bf679a11809bd4ebadbd4a9ed27d27e318a59a88614","assignedAt":"2026-11-15T12:00:00Z","provenance":{...}}
```
- **Stable ID formulas:** none new or changed. Researcher IDs come from the existing `ResearcherId.orcid` / `ResearcherId.scholarChain`; the canonical work ID is the representative's `WorkId`; decision and run IDs use the existing formulas.
- **Pair canonicalization:** unchanged (`CanonicalPair`, UTF-8 byte order). Assignments store no pairs; decision references rely on `MatchDecision` v2's canonical `sourceId < targetId`.

### 3. Clarification 5 (metric semantics): conflicts found, that part stopped
- **`ResearcherMetrics` v1** (`Collaboration.scala:301-320`) conflicts: it has no field for reporting unresolved coauthor occurrences separately, and its documentation does not say whether T_r includes them. It needs a v2 in DE-P6-02; I did not change it.
- **`PublicationTeam` v1** does not conflict: teamSize counts unresolved mentions and researcherIds are identified researchers. Its doc phrase "resolved researchers" now also covers singletons. Unchanged.
- **`CoauthorEdge` v1** is compatible (researcher IDs only).
- **C6** (`identityConfidence`) remains deferred.

```text
STATUS: COMPLETE
OWNER: domain-engineer
WORK ORDER: DE-P6-01 (Phase 6): contracts C1-C5 in modules/core; verbatim human text /Users/taufeeqpatel/Desktop/ScholarChain/docs/agent-runs/phase-06/prompt-de-p6-01.md; base spec decision-package-01.md section 5 plus G1/G2
FILES CREATED:
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/main/scala/scholarchain/core/Assignments.scala:1-689 (AssignmentStatus, WorkGroupStatus, GraphEligibility and DecisionRole enums; shared AssignmentRules; ResearcherAssignment and WorkAssignment records and contracts)
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/main/scala/scholarchain/core/AssignmentValidation.scala:1-523 (pure dataset validators: researchers, works, run, decisions, authorships, authorship confidence, teams)
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/main/scala/scholarchain/core/AuthorshipV2.scala:1-173 (AuthorshipRecordV2 and its contract; AuthorshipRecords.decode reader for v1 and v2)
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/main/scala/scholarchain/core/PartManifest.scala:1-353 (PartFile, PartManifest and contract, ObservedFile with Accumulator, PartManifestVerifier)
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/test/scala/scholarchain/core/AssignmentFixtures.scala:1-374 (one synthetic valid grouping run)
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/test/scala/scholarchain/core/AssignmentContractSuite.scala:1-421 (record invariants, round trips, unknown keys, v1 still decodes)
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/test/scala/scholarchain/core/AssignmentValidationSuite.scala:1-461 (each dataset rule, plus the valid set)
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/test/scala/scholarchain/core/AssignmentWireSuite.scala:1-149 (frozen examples: 4 researcher, 3 work, 2 authorship v2, 1 part manifest; pinned literal IDs)
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/test/scala/scholarchain/core/PartManifestSuite.scala:1-259 (C5 acceptance and refusals, including both truncation cases)
FILES MODIFIED:
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/main/scala/scholarchain/core/Contract.scala:29-33 (version history note), 60-63 (four new constants), 97-101 (new entries in SchemaVersions.all)
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/main/scala/scholarchain/core/config/ScholarChainConfig.scala:84-98 (documentation), 106-109 (three optional fields with None default), 112-143 (RunLocation), 146-167 (key constants and requireKey), 213-303 (problems() rules for the optional keys; GroupingRunSettings)
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/main/scala/scholarchain/core/config/ConfigLoader.scala:66-69 (OptionalToken and OptionalLocation kinds; `optional`), 81-85 (documentation), 132-134 (three keys), 449 (environment unset for optional kinds), 484 (missing-key rule for optional kinds), 508-523 (optToken and optLocation), 615-618 (build), 626-627 (comment)
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/test/scala/scholarchain/core/SchemaVersionSuite.scala:11-21 (28 versions; new constants; v1 kept), 60-64 (new contracts)
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/test/scala/scholarchain/core/WireContractSuite.scala:268-273 (four new names in the frozen-type set; no golden changed)
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/test/scala/scholarchain/core/config/ConfigLoaderSuite.scala:744-972 (frozen digest input of existing configs; C4 tests)
COMMANDS RUN:
- git status --porcelain (twice): ok
- git ls-files -- modules/core; git ls-files --others --exclude-standard -- docs/agent-runs/phase-06; git ls-files -- modules/entity-resolution/src/main modules/hadoop/src/main build.sbt project: ok
- git diff --stat --no-index ...: refused by guard (no-allowlist-entry); nothing executed
- sbt core/test: Passed 300, Failed 0 (frozen-digest test added, run against the unchanged config code)
- sbt core/compile: success
- sbt core/test: failed (expected; placeholder goldens); output truncated
- sbt core/test: failed (expected; placeholders pending); a temporary test wrote the pinned IDs to /private/tmp/claude-501/-Users-taufeeqpatel-Desktop-ScholarChain/1d4ee1c4-462f-4438-9d00-5d7f5bbf5c79/scratchpad/pinned.txt; that test was then removed
- sbt core/testOnly scholarchain.core.AssignmentWireSuite: refused by guard (no-allowlist-entry); nothing executed
- sbt core/test: Passed 402, Failed 0, Errors 0
- sbt check (once, final): success; markTestRun wrote .claude/.last-test-run (git_head=426df590f3f043c96021b739dd7f39436dd30956, worktree=dirty, suites=81, passed=1037, skipped=0)
- git diff --unified=0 and git diff --stat (after sbt check, read-only, to list line ranges)
TEST RESULTS: sbt check: core 402, entityResolution 405, hadoop 85, tests 145, other projects 0; total 1037 passed, 0 failed, 0 errors, 0 skipped, 81 suites. All new tests use synthetic fixtures. Each new test fails without the change: the new suites do not compile without the new types, and the C4 tests fail because the old loader refuses the new keys as unknown. Exception: the frozen-digest test passes both before and after; it is a regression guard.
VALIDATION RESULTS:
- scalafmt check passed inside sbt check, and the build guard and version ledger were OK.
- No change to MatchDecision v2, RunManifest, any ID formula or any existing frozen example (diff verified).
- No new dependency (uPickle only).
- Nothing under gold/**, target/** or any held-out path was read or searched (I had no Grep tool); no held-out task and no sbt clean.
ASSUMPTIONS:
- Changes beyond the package, for review:
  - a fifth decision list, rejectedDecisionIds, so that every decision touching a member is preserved and the five lists partition them;
  - the graphEligibility field with three values;
  - AuthorshipRecordV2 also carries matchPolicyVersion and decisionRunId;
  - matchConfidence is defined as the minimum supporting confidence;
  - teams only for counted works; authorship records only for identified mentions on representative works;
  - key names decisionRunRoot and assignmentRunRoot, as full locations rather than prefixes in the curated bucket.
- No closed set of anchor namespaces in core: the anchor order is ER policy.
- A singleton may carry pending, vetoed or conflict decisions (the t1-anchored case).
- PartManifest carries no Provenance, following the RunManifest precedent (a control record); its file names are contract constants.
- Optional config keys are added within config.v2, not a new version.
RISKS:
- C5 requires every non-empty file, including `_run-manifest.json`, to end in LF, and the run manifest to be one row. ER-P6-04 must check DecisionExport's current format.
- Compressed parts (Hadoop curated runs) do not fit the LF rule; Hadoop part manifests are deferred.
- Some dataset rules (one anchor per ID, the group-eligibility checks) cannot fail when every record is valid; they are backstops and are tested through the record rules.
- The file/directory path check is quadratic in the number of files.
- Whether any AuthorshipRecord v1 was ever written is UNVERIFIED.
- Disclosure: a temporary test wrote one file outside the repository, in my session scratchpad.
MANUAL ACTION REQUIRED: Human: review the assumptions above (rejectedDecisionIds, graphEligibility, the authorship v2 additions, the key names) as part of acceptance. The human decides the commit.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none. One part was stopped on purpose (clarification 5): ResearcherMetrics v1 (Collaboration.scala:301-320) has no field for unresolved coauthor occurrences; this belongs in DE-P6-02 (domain-engineer), not this order.
NEXT GATE: code-reviewer reviews DE-P6-01 (the new files are untracked; see the file list above), then human acceptance. Then ER-P6-04 (integrity: emit and verify _parts.json with an LF-terminated run manifest) and the ER-P6-05 grouping work order for approval, drafted by its owner; the B3 group-wide vetoes and the homonym-suffix name context are policy for entity-resolution-engineer, and core only validates the result. DE-P6-02 follows (ResearcherMetrics v2 with unresolved occurrences reported separately, C6, Spark contracts).
```

---

## ER-P6-04: entity-resolution-engineer (2026-11-15), verbatim

## ER-P6-04: `_parts.json` emitted and verified, F4 digest scope fixed; `sbt check` passed (1064 passed, 0 failed, 81 suites)

All three items are done inside `modules/entity-resolution/**`. The decision export now writes the integrity manifest in the G2 order. Both readers refuse any run that does not match its bytes on disk. Grouping-only settings no longer change the decision run ID. Policy, features, thresholds, `Resolver` and decision outcomes are unchanged, and no core change was needed.

**What changed in behaviour**
- **Write order:** inside staging, publication now writes the researcher part, the work part, `_run-manifest.json`, `_parts.json`, then `_SUCCESS`. After that the existing atomic rename and no-overwrite rules apply.
  - `_parts.json` is one LF-terminated core `PartManifest`. It lists exactly the run manifest (run-manifest.v1) and both parts (match-decision.v2).
  - Its counts and SHA-256 come from the core `ObservedFile` over bytes read back from staging, not from memory.
- **Verified reads:** `readPublished` and `publishedRuns` keep the existing layout and stage checks and add four:
  - `_parts.json` must be a regular file;
  - every entry is walked without following links, and anything that is neither a regular file nor one of the three expected directories is refused;
  - `_parts.json` must decode and list exactly those three files;
  - `PartManifestVerifier.verify` runs against the run ID the directory names: exact file set, row, byte and sha256 counts, final LF, both markers present.
- **Decoding:** the run manifest and decision rows are decoded only from the verified bytes, with strict UTF-8. The old `lines()` helper is gone, so an empty row is refused instead of dropped, and a missing final LF is refused. A missing file still never reads as zero rows.
- **New rule beyond the work order:** extra directories and non-regular entries (symlinks included) are refused, which keeps the layout exact.
- **F4:** `DecisionRunMain` now passes `ScholarChainConfig.digestWithoutGrouping(cfg)`.
- **Known gap:** `publishedRuns` checks integrity but, as before, never decodes rows. A copy with a self-consistent `_parts.json` and an empty row is therefore listed, and then refused by `readPublished`. The test asserts this explicitly.

**Compatibility report**
1. **Existing exports without `_parts.json` are refused.** `readPublished` returns "incomplete or unverifiable: no regular _parts.json" and `publishedRuns` does not list them (test at suite 1049-1057). I deleted and moved nothing. I could not list existing exports, because Grep under `target/` is off limits. No agent could have run `DecisionRunMain` on real data, since it has no allowlist row. The test exports under `modules/entity-resolution/target/test-work` are rebuilt on every test run.
2. **Regenerating with the same manifest and config keeps everything.**
   - The run ID is the same: for a config without grouping keys `digestWithoutGrouping == digest` (asserted at suite 1086), and no run-ID input changed.
   - The decision and run-manifest bytes are the same, because the encoding and decision path are unchanged. Only `_parts.json` is new.
   - **Catch:** `publish` refuses an existing `run=<hex>`, even an unverifiable one. Regeneration must target a new output root, or the human moves the old directory aside first.
   - A manifest recorded under the old full `digest(cfg)` of a config that *did* set grouping keys is now refused. None should exist; see RISKS.
3. **Regeneration path:** `scholarchain.entityresolution.review.DecisionRunMain <decision run manifest .json> <curated run dir> <new output root>`, with the config supplied through `SCHOLARCHAIN_CONFIG_FILES`. No allowlisted dev-run command exists yet. It needs a build-engineer alias plus a factory-engineer allowlist row, so until then only the human can run it, in a terminal.

**CR-P6-02 nits**
- **Nit 3 is partly closed, as an unavoidable side effect.** "Decode only after verification" requires reading each file once, opened with `NOFOLLOW_LINKS`, and decoding those same bytes. So the swap-after-check gap between check and read is gone. A swap of an intermediate directory during the walk is still possible.
- **Nits 1 and 4 are carried forward.** A renamed run directory is now also refused by the verifier's run-ID check, but it still has no dedicated test. The undecodable-manifest case in the m1 test now fails at sha256 verification before the decode step, so its substring assertion proves the decode reason even less.

**Changed regions (all files untracked; ranges after formatting)**

`modules/entity-resolution/src/main/scala/scholarchain/entityresolution/decisions/DecisionExport.scala`
- 3-37: imports (`ByteArrayOutputStream`, strict charset, `BasicFileAttributes`, core `ObservedFile`, `PartFile as ListedFile`, `PartManifest`, `PartManifestVerifier`, `SchemaVersions`).
- 88-126: object scaladoc: layout with `_parts.json`, G2 order, verified reads, refusal of runs from before ER-P6-04.
- 154-165: `ManifestFile` and `SuccessFile` now taken from the core constants; new `PartsFile` and `partPath`.
- 356-392: `listedFiles`, `runDirectories`, and `readNoFollow` (streams bytes into the core Accumulator, opened with NOFOLLOW).
- 394-449: `publish` in G2 order, building `_parts.json` from the bytes read back.
- 461-490: `layoutProblem` also requires a regular `_parts.json`.
- 492-547: `VerifiedRun`, strict `utf8`, `relative`, and `observe` (no-follow walk; refuses non-regular entries and unexpected directories).
- 549-623: `verifiedRun` (decode and listed-set check, `PartManifestVerifier`, then run-manifest decode, stage, run ID, `checkRunManifest`).
- 625-636: `publishedRuns` filters on `verifiedRun`.
- 638-653: `rows`: strict rows from verified bytes; an empty row is refused.
- 655-689: `readPublished` decodes only verified bytes.

`modules/entity-resolution/src/main/scala/scholarchain/entityresolution/review/DecisionRunMain.scala`
- ~28-31: scaladoc for step 3 (decision digest scope, `_parts.json`).
- ~107-114: F4: `digestWithoutGrouping(cfg)`, with a comment.

`modules/entity-resolution/src/test/scala/scholarchain/entityresolution/decisions/DecisionExportSuite.scala`
- 166-172: the suite digest is `digestWithoutGrouping` (was line ~168).
- 339-348: the reproducibility key set includes `_parts.json`.
- 402-403: the I/O-failure test expects the G2 order (only the researcher part written before the failure).
- 690-691: the wrong-stage test adds a consistent `_parts.json`, so the stage is still the only fault.
- 734: m2 configured digest uses `digestWithoutGrouping` (was ~730).
- 786-830: helpers: `listedFiles`, `remanifest` (built independently from the core contract), `copyRun`, `rewrite`.
- 832-871: test: the write order is G2, and `_parts.json` content matches the bytes on disk.
- 873-886: test: an exact copy, including a zero-row part, is accepted.
- ~888-1012: test with 14 refusal cases:
  - cut at a line boundary; cut mid-line; same-length corruption;
  - a missing part; an extra file in a part directory; an extra file at the top; an extra directory; an extra symlink;
  - `_parts.json` missing; `_parts.json` undecodable; wrong runId;
  - a consistent manifest but no final LF; a consistent manifest but an empty row; a consistent manifest listing a fourth file.
- 1014-1047: test: four `_SUCCESS`-first partial copies are refused.
- 1049-1057: test: a run from before ER-P6-04 (no `_parts.json`) is refused.
- 1059-1113: test: F4 digest stability.
  - Adding or changing grouping keys (version, `decisionRunRoot`, `assignmentRunRoot`, local and s3) leaves the decision digest, run ID and every published byte unchanged.
  - A config without grouping keys gives digest == the old digest.
  - A manifest that recorded the full digest of a grouped config is refused.

Every new test fails without its change. The F4 test fails without the `DecisionRunMain` change, because the grouped config would refuse on configDigest. The integrity tests fail without the verifier, and the legacy test fails without the `_parts.json` requirement.

```text
STATUS: COMPLETE
OWNER: entity-resolution-engineer
WORK ORDER: ER-P6-04 (Phase 6): integrity manifest (_parts.json) in DecisionExport and DecisionRunMain per decision package G1-G3 and the CR-P6-03 routing note, plus F4 (digestWithoutGrouping). Verbatim human text: /Users/taufeeqpatel/Desktop/ScholarChain/docs/agent-runs/phase-06/prompt-de-p6-01-repair-02-and-er-p6-04.md item 2
FILES CREATED: none (all three files were already untracked from ER-P6-02)
FILES MODIFIED:
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/decisions/DecisionExport.scala (untracked)
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/review/DecisionRunMain.scala (untracked)
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/decisions/DecisionExportSuite.scala (untracked)
COMMANDS RUN:
- git status --short: ok
- git ls-files --others --exclude-standard -- docs/agent-runs/phase-06 modules/entity-resolution modules/core: ok
- git ls-files --others -- review-report.md repair-02.md: ok
- git diff (core ScholarChainConfig.scala, Provenance.scala; --stat): ok, read only
- git ls-files -- modules/entity-resolution/src/test/scala and modules/core/src/main/scala/scholarchain/core: ok
- sbt entityResolution/Test/compile: success
- sbt entityResolution/test (1st): 1 failure, in my new test (an empty-row copy is listed by publishedRuns by design); test expectation corrected
- sbt entityResolution/testOnly ...: refused by guard (no-allowlist-entry), nothing executed
- tail of the saved output: refused by guard, nothing executed; read with the Read tool instead
- sbt entityResolution/test (2nd): Passed: Total 411, Failed 0, Errors 0
- sbt check (once, final command after the last edit): [success] Total time: 135 s
TEST RESULTS: sbt check: build sources OK, version ledger OK, scalafmt check OK, compile OK. Passed per project: core 423, entityResolution 411, hadoop 85, tests 145; rag, root, services, sparkAnalytics and storage 0. markTestRun wrote .claude/.last-test-run: suites=81, tests_passed=1064, tests_failed=0, tests_errored=0, tests_skipped=0, git_head=426df590f3f043c96021b739dd7f39436dd30956, worktree=dirty, written 2026-11-16T04:31:00Z. entityResolution went from 405 to 411 (6 new tests; existing tests adjusted in place).
VALIDATION RESULTS:
- Policy er-policy.v8, blocking er-blocking.v2, features er-features.v2, thresholds 0.85/0.2 with band 0.4-0.7, and Resolver are unchanged. Prompt er-adjudication-prompt.v2 is unused; model none.
- Decision outcomes and decision bytes are unchanged: the parity, reproducibility, no-overwrite, m2 threshold and committed-sample probe tests pass.
- No dev diagnostics were run (goldSetEvalDev was not named and was excluded). So there is no confusion matrix, unresolved rate, threshold sensitivity or FP/FN listing for this change; none applies to an I/O integrity change.
- Nothing read under gold/**, held-out paths, target/streams or target/local-runs. No Grep at all. The only target/ files read were my own module's JUnit report (modules/entity-resolution/target/test-reports/TEST-...DecisionExportSuite.xml) and the saved sbt output.
- No *Heldout task, no sbt clean, no core change.
ASSUMPTIONS:
- _parts.json itself is written as one compact LF-terminated line; C5 does not constrain it, and it is not compared by the verifier.
- Refusing extra directories and non-regular entries is within "exact file set" (fail closed).
- publishedRuns verifies integrity but does not decode rows, the same split as before.
- No decision manifest was ever recorded under the full digest of a config that sets grouping keys. Those keys only became loadable in DE-P6-01, and no agent can run DecisionRunMain.
RISKS:
- Any such manifest would now be refused on configDigest; regenerate it with a manifest that records digestWithoutGrouping.
- Regenerating into an output root that already holds a run=<hex> from before ER-P6-04 is refused by the no-overwrite rule; use a new root.
- Each read hashes every file in full (publishedRuns too), which costs I/O on large runs. Parts are held in memory only by readPublished, as before.
- Residual TOCTOU: an intermediate directory swapped during the walk.
- Spark-side verification (spark-engineer) and copy scripts (infra-engineer) are still to come per G3. Without them, S3 copies remain unverified outside ER.
MANUAL ACTION REQUIRED:
- Human: review and commit the untracked files.
- Human: decide whether existing local decision exports, if any, are regenerated into a new output root. I deleted and moved nothing.
- Orchestrator: route the build-engineer alias and factory-engineer allowlist row for a dev decision run; route this diff to code-reviewer.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer review of the regions above, then human acceptance. ER-P6-05 (grouping) is not started and waits for approval of the carried-forward rules.
```

---

## ER-P6-05: entity-resolution-engineer (2026-11-15), verbatim

## ER-P6-05: grouping layer er-grouping.v1 is implemented; `sbt check` passed

**Outcome**
- **Implemented:** the grouping layer er-grouping.v1, its all-or-nothing publisher and verified reader, the entry point `GroupingRunMain`, and the CR-P6-06 m1 read-back regression for both publishers.
- **`sbt check`:** ran once, as the final command after the last edit. Success: 1097 passed, 0 failed, 0 skipped, 83 suites. Entity-resolution passed 444, core 423, hadoop 85, tests 145.
- **No change to:** er-policy.v8, er-blocking.v2, er-features.v2, the thresholds, the Resolver, `DecisionExport.scala`, `core`, `hadoop`, Spark or AWS. No model is called.

**Choices I made beyond the draft, for the reviewer to confirm**
- **Run roots are enforced.** `GroupingRunMain` requires the decision run to be a direct child of the configured `entityResolution.decisionRunRoot`, and the output root to equal `entityResolution.assignmentRunRoot`. An `s3://` root is refused, because this entry point publishes on a local file system only.
- **Extra fail-closed checks.** The run is also refused when:
  - the decisions do not decide exactly the curated run's candidate IDs;
  - a work ID has a namespace that is not in the representative order;
  - the resource's T1 or BD scheme lists differ from er-policy.v8's T1 and blocking schemes.
- **Two readers instead of one.** `GroupingExport.readPublished` does exactly the decision export's verification. `readValidated` adds the core dataset validators.
- **Diagnostics wording.** The probe diagnostics avoid the word "label". The extended leakage test bans it in grouping code, so the header line says "not evaluated: no precision, recall or quality claim".

### Probe diagnostics, verbatim (committed-sample Hadoop output; every line unevaluated)
```text
[er-grouping-probe] input run=9c5c86b3ea1a74fe1c6275acab9210dc1ae42dc354a88661af759daf4b4b2498 decisions run=ce17b72e657962f651ab6b7dbd8d57fb958775a7f156c4e0a37fa627e04a9764 published run=9fa908646bc02d9a6eb610c760e25cd526ea7cbc9108c71afa2f176a72cfaf5a
[er-grouping-probe] output /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/target/test-work/grouping-sample-probe/assignments/run=9fa908646bc02d9a6eb610c760e25cd526ea7cbc9108c71afa2f176a72cfaf5a
[er-grouping-probe] unevaluated records researcher=103 work=13 authorship=65 team=4
[er-grouping-probe] unevaluated links merged=74 redundant=43 vetoed=0
[er-grouping-probe] unevaluated grouping run=run:9fa908646bc02d9a6eb610c760e25cd526ea7cbc9108c71afa2f176a72cfaf5a policy=er-grouping.v1 matchPolicy=er-policy.v8 decisionRun=run:ce17b72e657962f651ab6b7dbd8d57fb958775a7f156c4e0a37fa627e04a9764 identityVersion=v1 (descriptive counts only; not evaluated: no precision, recall or quality claim)
[er-grouping-probe] unevaluated researcher members=103 mentions=99 profiles=4 researcherIds=36
[er-grouping-probe] unevaluated researcher groupSizes=1:5,2:1,3:24,4:6
[er-grouping-probe] unevaluated researcher status resolved=98 singleton=5 singletonT1Anchored=0 unresolved=0
[er-grouping-probe] unevaluated researcher unresolved=0 of 103 rate=0.0000
[er-grouping-probe] unevaluated researcher anchorNamespaces=member:10,openalex-author:17,orcid:9
[er-grouping-probe] unevaluated researcher anchorCollisions identifiedGroups=grouping.anchor-collision.openalex-author:2,grouping.anchor-collision.orcid:2 unresolvedMembers=none collidedValues=orcid:1,dblp-pid:0,openalex-author:1
[er-grouping-probe] unevaluated links researcher total=107 merged=67 redundant=40 vetoed=0
[er-grouping-probe] unevaluated vetoes researcher links=0 byRule=none byReason=none
[er-grouping-probe] unevaluated work members=13 canonicalWorks=6 groupSizes=1:2,2:1,3:3
[er-grouping-probe] unevaluated work graphEligibility counted=4 member-of-counted=5 enrichment-only=4
[er-grouping-probe] unevaluated links work total=10 merged=7 redundant=3 vetoed=0
[er-grouping-probe] unevaluated vetoes work links=0 byRule=none byReason=none
[er-grouping-probe] unevaluated teams=4 teamMentions=39 unresolvedTeamMentions=0 works=13
[er-grouping-probe] unevaluated ST=0 (expected 0 under er-policy.v8) SW-C=0 (O8 diagnostic, not a veto)
```
The endpoint self-check passed on these committed-sample decisions; the run would have been refused otherwise. On this sample, one ORCID iD and one OpenAlex author ID are each asserted by two final groups that no decision links, so those groups use a fallback anchor. No veto fired.

There is no confusion matrix, no false positive or false negative listing and no threshold sensitivity. Grouping has no labels and no thresholds.

### Grouping output available for Spark
- **What exists now:** the run directory `/Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/target/test-work/grouping-sample-probe/assignments/run=9fa908646bc02d9a6eb610c760e25cd526ea7cbc9108c71afa2f176a72cfaf5a/`, holding:
  - `assignments/researcher/part-00000.jsonl`: ResearcherAssignment v1, 103 rows;
  - `assignments/work/part-00000.jsonl`: WorkAssignment v1, 13 rows;
  - `authorships/part-00000.jsonl`: AuthorshipRecordV2, 65 rows;
  - `teams/part-00000.jsonl`: PublicationTeam v1, 4 rows;
  - `_run-manifest.json`, `_parts.json` and `_SUCCESS`.

  The decision run it grouped is at `.../grouping-sample-probe/decisions/run=ce17b72e.../`.
- **Integrity status:**
  - before publishing, every core validator and producer check passed;
  - after publishing, the probe reread the run with `readValidated`, so `_parts.json` was verified and the dataset validators passed.
- **Not durable:** this output is regenerated on every test run (`workDir` deletes it first) and sits under gitignored `target/`. It is not a published dataset.
- **What Spark must use:**
  - the teams of this assignment run (`teams/`, with researcher IDs), never the Hadoop TeamJob teams;
  - counted works only for the graph (`graphEligibility == counted`; teams exist only for those);
  - `researcherIds` for C_r, edges and W_r, and `teamSize` plus `unresolvedMentionIds` for T_r, A_r and M_r;
  - a `_parts.json` check with the core verifier before reading (spark-engineer).

  Authorship records also exist for enrichment-only representatives (O9), so they must be filtered.
- **A durable dev run would need:**
  - a build-engineer fixed-shape task that runs decisions and then grouping on the `localPipelineDev` output;
  - a factory-engineer allowlist row, ratified by the human (today no agent can `runMain` either entry point);
  - DE-P6-02 for the Spark contracts.

### CR-P6-06 n1-n4 and CR-P6-02 nits 1 and 4
`DecisionExport.scala` is unchanged, so all six stay open for the decision export. For the new grouping publisher:
- **n1:** a failure is injected at each of the 7 writes, and the tests show nothing is left behind.
- **n2:** the `.DS_Store` refusal is documented in the `RunDirectory` scaladoc.
- **n3 and n4:** the same limitations apply (marker files read into memory without a size bound; every listing hashes every file). They are documented.
- **CR-P6-02 nit 1:** a renamed-copy test was added. It is caught by the verifier's run-ID check first, so the later directory-name check is still not reached on its own.
- **CR-P6-02 nit 4:** untouched.

```text
STATUS: COMPLETE
OWNER: entity-resolution-engineer
WORK ORDER: ER-P6-05 (Phase 6). Grouping layer er-grouping.v1, GroupingRunMain, verified assignment-run reader, CR-P6-06 m1 regression for both publishers. Inputs: docs/agent-runs/phase-06/prompt-er-p6-05.md, work-order-draft-er-p6-05.md (sections 1-5, O1-O11, O9 as decided), decision-package-01.md B-E.
FILES CREATED:
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/resources/scholarchain/entityresolution/grouping/er-grouping.v1.json (1-111, whole file)
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/grouping/GroupingPolicy.scala (1-264): resource model, loader, validate, checkAgainst (T1/BD schemes equal er-policy.v8's)
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/grouping/Grouping.scala (1-1337): manifest and decision checks, members, vetoes N1/T1/BD/SW/HN/ST, endpoint self-check, B2 merge, anchors C1/C2, D1 statuses, works E1/E2, authorships, teams, validate (every core validator), producerChecks, verify, diagnostics
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/grouping/RunDirectory.scala (1-385): staging, parts, _run-manifest.json, _parts.json from bytes read back, _SUCCESS, atomic rename without overwrite; verified reads
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/grouping/GroupingExport.scala (1-171): layout, publish, publishedRuns, readPublished, readValidated
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/review/GroupingRunMain.scala (1-200)
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/grouping/GroupingFixtures.scala (1-369)
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/grouping/GroupingSuite.scala (1-811): draft items 1-10 plus O11 and the name-parity test
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/grouping/GroupingExportSuite.scala (1-583): item 11, grouping m1 regression, entry point, item 13 probe
FILES MODIFIED:
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/decisions/DecisionExportSuite.scala (untracked; added 1114-1159: CR-P6-06 m1 decision-export regression; nothing else changed)
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/LeakageStructureSuite.scala (tracked; added 169-220: grouping and decisions packages in scan, grouping names no review/evaluation/gold/label, no clock or randomness in grouping and decisions, resource text check; no existing check changed)
COMMANDS RUN:
- git status --short (x3), git ls-files (untracked and tracked entity-resolution, phase-06, core main, hadoop docs, build files), git diff --stat, git show --stat HEAD: ok
- git grep with quotes: refused by guard (shell-syntax-gate), nothing ran; sbt entityResolution/testOnly <suite>: refused by guard (no-allowlist-entry), nothing ran
- sbt entityResolution/compile x4: success
- sbt entityResolution/test x5: run 1 test-compile error (fixed); runs 2-4 failed (GroupingSuite 2 assertions; LeakageStructureSuite: "labels" in a Grouping.scala string; GroupingExportSuite 4 tests whose fixture had an ACCEPTED pair with disagreeing ORCIDs, correctly refused by the endpoint self-check), all fixed in tests or wording; run 5 Passed: Total 444, Failed 0
- sbt check (once, final command after the last edit): success
- afterwards only Read and git status --short (no edits)
TEST RESULTS: sbt check success; markTestRun wrote .claude/.last-test-run (git_head=426df590f3f043c96021b739dd7f39436dd30956, worktree=dirty, suites=83, passed=1097, skipped=0). Per project: core 423, entityResolution 444, hadoop 85, tests 145; rag, root, services, sparkAnalytics, storage 0.
VALIDATION RESULTS:
- Committed-sample probe ran inside sbt check (0 skipped). Diagnostics are above, verbatim, all unevaluated. There is no confusion matrix, false-positive/false-negative listing or threshold sensitivity: grouping has no labels or thresholds.
- Versions: grouping er-grouping.v1; match er-policy.v8 / er-blocking.v2 / er-features.v2 unchanged; thresholds unchanged; model none; prompt er-adjudication-prompt.v2 (unused); identityVersion v1 (from config).
- Features: none added; vetoes reuse the er-features.v2 identifier and name functions (a test proves name parity with MatchFeatures.prepare).
- The grouping manifest records decisionRunId and inputRunId (both verified), assignedAt, groupingPolicyVersion, matchPolicyVersion and identityVersion; configDigest = GroupingRunSettings.configDigest(cfg), without run roots.
- Before publication the run calls checkResearchers, checkWorks, checkRun, checkAgainstDecisions, checkAuthorships, checkAuthorshipConfidence, checkTeams, checkCoverage (ExpectedAuthors.of for every curated work) and checkSingletonAnchors (every ORCID iD and DBLP pid, unresolved members included), plus the producer checks.
- Nothing under gold/**, held-out paths or target/streams was read; no Grep was used; no goldSetEval or *Heldout task and no sbt clean was run.
ASSUMPTIONS:
- The identifier input is the er-features.v2 functions over curated works and profiles (what the decisions used), cross-checked to contain every MemberIdentifier.fromMention value and every profile assertion. Hadoop exact-ids/links (IdentityLink) are not read; per job-contracts they derive from the same fields.
- The local-root enforcement and the candidate-set check in GroupingRunMain are my additions beyond the draft.
- Decisions are unique per pair (refused otherwise). Grouping run ID and published bytes are deterministic for one manifest; codeVersion is asserted by the caller (as for the decision export).
RISKS:
- Grouping is unevaluated and changes entity membership relative to the pairwise decisions. Pair-level BD can split a person whom OpenAlex split into several A IDs.
- Collisions with other runs or splits cannot be checked. Member-anchored and OpenAlex-anchored IDs are less stable.
- Veto cost is O(|G1|·|G2|) per link over members asserting a scheme; single-JVM scale is unmeasured (H7).
- The probe depends on Hadoop test output existing (skipped otherwise; same concurrency caveat as the existing decision probe).
- AuthorshipRecordV2 exists for enrichment-only representatives, so Spark must filter on counted works. The Hadoop TeamJob also writes PublicationTeam (M6). CR-P6-06 n1-n4 and CR-P6-02 nits 1 and 4 stay open for DecisionExport.
MANUAL ACTION REQUIRED:
- Human: match-policy review of er-grouping.v1 (resource and Grouping.scala); confirm local-root enforcement and the extra refusals; record the D3(c) disclosure at the phase gate (no policy, feature or threshold change); review and commit the untracked files.
- Orchestrator: one focused code-reviewer review of the files above.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer focused review of ER-P6-05, then the human's match-policy review and acceptance. A durable dev run needs a build-engineer alias plus a factory-engineer allowlist row the human ratifies. Stop before Spark (DE-P6-02, SP-P6-01) and AWS.
```

---

## BE-P6-01: build-engineer (2026-11-15), verbatim

## BE-P6-01: `sbt erDecisionGroupingDev` is added and tested, but it can't run until entity-resolution-engineer commits three input files

The build side is finished. The final `sbt check` passed: 1101 tests, 0 failed, 83 suites. I did not run the new task: it has no allowlist row, and its committed inputs don't exist yet. The task refuses until entity-resolution-engineer commits them (section 2). One integrity step, a read-back of the grouping run after it is published, needs a small change in `GroupingRunMain` (2.4).

### 1. The task
- **Name:** `erDecisionGroupingDev` (a root task with no arguments, in `/Users/taufeeqpatel/Desktop/ScholarChain/build.sbt` and `/Users/taufeeqpatel/Desktop/ScholarChain/project/LocalRuns.scala`).
  - "er" because it is entity resolution and runs both stages; "Dev" to match `localPipelineDev` and `goldSetEvalDev`.
  - No held-out twin, no bare name, and the name contains neither held-out task name, so the existing guard rules can't misfire on it.
- **What it forks**, in order, in the repository root, on the `entityResolution/Compile/fullClasspath`:
  1. `scholarchain.entityresolution.review.DecisionRunMain` with `<repo>/modules/entity-resolution/local-runs/er-decisions.dev.run-manifest.json`, `<curated dev run>`, `<repo>/target/local-runs/dev/er-decisions`.
  2. `scholarchain.entityresolution.review.GroupingRunMain` with `<repo>/modules/entity-resolution/local-runs/er-grouping.dev.run-manifest.json`, `<repo>/target/local-runs/dev/er-decisions/run=<hex>` (the run that stage 1 published), `<curated dev run>`, `<repo>/target/local-runs/dev/er-assignments`.
- **Curated dev run:** the task finds it, never builds it.
  - It requires exactly one complete attempt under `modules/hadoop/target/local-pipeline/er-candidates/dev/`.
  - Inside it, exactly one `curated/hadoop/snapshot=*/run=<64 hex>/`, with no symbolic links and a regular `_run-manifest.json`. Today that is the `run:2881724b…` attempt.
  - It lists only the dev attempts directory and reads no file contents.
  - If the run is missing, it refuses and says to run `sbt localPipelineDev` first. I chose not to have it run `localPipelineDev` itself: that would add 90 s to the bound, cross into hadoop-engineer's row, and inherit that task's own refusal when a complete attempt already exists.
- **Environment:**
  - Inherited `AWS_*`, `SCHOLARCHAIN_*` and `GIT_*` variables are removed; the AWS isolation values are added; no stdin.
  - Only `SCHOLARCHAIN_CONFIG_FILES` is set: `<repo>/modules/entity-resolution/local-runs/er-dev.conf,<repo>/target/local-runs/dev/er-run-roots.conf`.
  - It refuses while any `SCHOLARCHAIN_CFG_*` is set in sbt's environment.
  - JVM scratch goes under `target/local-runs/dev/er-dev-jvm-*`. Child output goes to the sbt task log, which is fine for dev.
- **Why a generated overlay file:** `GroupingRunMain` needs both run roots as absolute local paths, so they can't be committed in a way that works on any checkout.
  - The task writes `target/local-runs/dev/er-run-roots.conf` on each run. It holds only `configSchemaVersion` and the two roots.
  - Neither root enters a run ID: decision runs use `digestWithoutGrouping` and grouping runs use `GroupingRunSettings.configDigest`, according to the ER-P6-04 and ER-P6-05 reports. I did not verify that myself.
  - The task refuses a checkout path containing a space, comma, quote or backslash.
- **Outputs:**
  - Decision runs: `target/local-runs/dev/er-decisions/run=<hex>/`
  - Grouping runs: `target/local-runs/dev/er-assignments/run=<hex>/`
  - The overlay file above.
  - All of these sit under the root `target/`, so they are outside the held-out clean-tree scope, the held-out output fences (`target/streams`, `target/local-runs/heldout`) and the protected held-out attempt directory.
- **Time bound:** 45 s per stage (90 s total, the same as `localPipelineDev`). These are estimates at dev scale, not measurements.
- **Refusals:**
  - any `SCHOLARCHAIN_CFG_*` set;
  - any fixed or discovered path that names the held-out split (`heldout`, `held-out`, `held_out` or `held out`, any case), lies under `gold/`, `target/streams/`, `target/local-runs/heldout/` or the held-out attempt directory, or is not a plain relative path;
  - an input file not committed or not identical to HEAD;
  - no complete dev attempt, or more than one; more than one snapshot or curated run; a symbolic link anywhere on the way; a missing `_run-manifest.json`;
  - a `run=…` entry already in either output root (create-once).
- **Failures:** the task fails when an entry point exits 2 (refused), exits with any other code, or hits the time bound, or when exactly one new run is not found after exit 0.
- **Re-runs:** the task refuses if either root already holds a run, and the human must move both roots outside the repository first. A decision run already published when grouping fails stays in place. Leftover `.staging-*` entries are reported, never deleted. Nothing is ever deleted.
- **Integrity (requirement 3a):**
  - The decision run is read back by `GroupingRunMain` through the verified reader (`DecisionExport.readPublished`).
  - The grouping run is validated before it is published, but no entry point reads it back afterwards (see 2.4).
  - The task prints both run IDs and paths and the overlay's sha256; the entry points print the sha256 of both config files.
- **Build-level test:** a new check `verifyErDevRunGuards` is now part of `check`, with 59 cases.
  - Constants: the task name, both main classes, every fixed path allowed, outputs outside the clean-tree scope, and the two roots disjoint.
  - 18 synthetic held-out, `gold`, `target/streams` and malformed paths, all refused.
  - The exact argument lists and config file list; the overlay content and its refusals.
  - On a temporary directory: curated-run selection (none, partial, a complete held-out attempt never chosen, a missing manifest, two complete attempts, two runs, two snapshots, a symbolic link, controls) and the create-once listing.
- **Shared code change:** the existing fork helper (`runForked`) got an optional list of variable prefixes to remove. Its default is the old `AWS_`, `SCHOLARCHAIN_`, so the Phase 5 tasks behave the same.

### 2. Inputs that entity-resolution-engineer must commit (not written by me)
These paths are under `modules/entity-resolution/**`, which entity-resolution-engineer owns.

1. **`modules/entity-resolution/local-runs/er-dev.conf`** (HOCON, `scholarchain.config.v2`). It must not set the two run roots, because the overlay supplies them.
   - **Proposed ER values (the accepted Phase 5 settings):**
     - `entityResolution.policyVersion = "er-policy.v8"`
     - `acceptThreshold = 0.85`, `rejectThreshold = 0.2`, `llmBandLower = 0.4`, `llmBandUpper = 0.7`
     - `promptVersion = "er-adjudication-prompt.v2"`, `groupingPolicyVersion = "er-grouping.v1"`
     - `graph.identityVersion = "v1"`
   - All other required keys get dummy, non-secret values in the style of `ConfigLoaderSuite.base`.
   - The owner should confirm the ER values against the dev config they use. I did not read `gold/**`.
2. **`modules/entity-resolution/local-runs/er-decisions.dev.run-manifest.json`**:
   ```json
   {"schemaVersion":"scholarchain.run-manifest.v1","stage":"er.decisions","codeVersion":"<fixed SHA>","configDigest":"<digestWithoutGrouping(er-dev.conf + overlay)>","inputSnapshots":[{"source":"dblp","snapshot":"dblp-2026-10-03"},{"source":"openalex","snapshot":"openalex-api-2026-11-05"},{"source":"crossref","snapshot":"crossref-api-2026-11-05"}],"parameters":{"blockingVersion":"er-blocking.v2","decidedAt":"<fixed UTC literal, e.g. 2026-11-15T00:00:00Z>","featureVersion":"er-features.v2","inputRunId":"run:2881724b5c86b561a235836d51946fbd884beed36a14f929b27c278cb5de8baa","policyVersion":"er-policy.v8"},"attempt":1}
   ```
3. **`modules/entity-resolution/local-runs/er-grouping.dev.run-manifest.json`**:
   - stage `er.grouping`, a fixed `codeVersion`, `configDigest` = `GroupingRunSettings.configDigest`, the input snapshots that `Grouping` requires;
   - parameters: `assignedAt` (a fixed literal), `decisionRunId` = the run ID of file 2, `groupingPolicyVersion` `er-grouping.v1`, `identityVersion` `v1`, `inputRunId` `run:2881724b…`, `matchPolicyVersion` `er-policy.v8`;
   - `attempt` 1.
4. **Two ER code items:**
   - A feature test proving the three files agree: both digests, the decision run ID chain, `inputRunId` equal to the run ID of `modules/hadoop/local-runs/er-candidates.dev.run-manifest.json`, and digests unchanged when the roots change. Note that `DecisionRunMain` decodes its manifest without stripping the final newline, while `GroupingRunMain` strips it.
   - For requirement 3a: `GroupingRunMain` should read the published run back with `GroupingExport.readValidated` and print a verified line, and optionally `DecisionRunMain` should do the same with `readPublished`.

### 3. Proposed allowlist row and guard changes (factory-engineer, after human ratification)
- **Row:** identity `entity-resolution-engineer`; status `inactive:BE-P6-01-ratification-pending` (active once ratified); mode `allow` (like `goldSetEvalDev`; `ask` is the human's alternative); provenance `-`; command shape `sbt erDecisionGroupingDev`.
  - Justification text: "Phase 6 bounded dev decision and grouping run (BE-P6-01): root task forking DecisionRunMain then GroupingRunMain on the complete localPipelineDev dev attempt with committed manifests and config; 45+45 s bounds; writes only target/local-runs/dev/er-decisions/, er-assignments/, er-run-roots.conf and JVM scratch under target/local-runs/dev/."
  - The allowlist row count and the sha in `docs/agents.md` must be updated.
- **Probes:**
  - entity-resolution-engineer `sbt erDecisionGroupingDev`: no deny.
  - The same command for the orchestrator and the hadoop- and spark-engineers: `no-allowlist-entry`.
  - `sbt erDecisionGroupingHeldout`: `no-allowlist-entry`.
  - `sbt entityResolution/runMain …` stays refused.
- **Optional hardening:** deny any `sbt` token containing `heldout` (any case). No active row uses one.
- **Held-out output fences:** none are touched. Agents can still Read the dev outputs and the task log (`target/streams/_global/erDecisionGroupingDev/`), but cannot Grep them, because `target/local-runs` and `target/streams` are already Grep-fenced.
- **`sbt clean` protection:** the held-out protection rule is unaffected. However, `sbt clean` / `sbt clean check` deletes these dev runs together with their Hadoop dev input. Adding a similar protection for dev is a human decision; I don't recommend it, because dev runs can be regenerated.

### 4. Risks
- The run path past the guards has never been observed: the inputs are missing and the task is not allowlisted. The 45 s bounds are estimates.
- The build mirrors the ErSplit and Hadoop folder layout (`attempt-<n>/curated/hadoop/snapshot=/run=`). If that layout changes, the task refuses.
- The overlay hardcodes `scholarchain.config.v2`. If the schema changes, the config loader refuses.
- The claim that the roots enter no run ID is taken from the ER reports and is not verified by the build.
- `codeVersion` is asserted in the manifests, never verified.
- Overlay contents, and so its sha256, differ per checkout path.
- JVM scratch directories pile up under `target/local-runs/dev/`.
- The self-test needs symbolic-link support in the system temp directory.
- A directory could change between the build's listing and the entry points reading it.

```text
STATUS: BLOCKED-ON
OWNER: build-engineer
WORK ORDER: BE-P6-01 (Phase 6): bounded dev-only decision and grouping task; human text docs/agent-runs/phase-06/prompt-er-p6-05-repair-01-and-dev-run.md paragraph 3
FILES CREATED: none
FILES MODIFIED:
- /Users/taufeeqpatel/Desktop/ScholarChain/project/LocalRuns.scala (DefaultStrippedPrefixes; optional stripPrefixes parameter of runForked, default unchanged; new section "Dev decision and grouping run (BE-P6-01)" with erDecisionGroupingDev, erDevGuardSelfTest, erDevFixture)
- /Users/taufeeqpatel/Desktop/ScholarChain/build.sbt (keys erDecisionGroupingDev and verifyErDevRunGuards, root settings, verifyErDevRunGuards added to the check alias, environment-variable comments)
COMMANDS RUN:
- ls (one call): refused by guard (no-allowlist-entry); nothing ran
- git ls-files --others --exclude-standard -- docs/agent-runs/phase-06 project: ok
- git ls-files -- project docs/agent-runs/phase-05 modules/entity-resolution/src/main/resources: ok
- git ls-files -- modules/hadoop/local-runs modules/entity-resolution/local-runs ...test/resources: ok (only the two Hadoop run manifests)
- git ls-files -- hadoop local and ER source paths: ok
- sbt compile: success (meta-build compiled, 1 s)
- sbt fmt: success; git status --short afterwards showed only build.sbt and project/LocalRuns.scala changed beyond the starting state
- git diff --stat -- build.sbt project: 2 files, +634 -7
- sbt check (once, final, after the last edit): [success] Total time: 131 s
TEST RESULTS: sbt check: build sources OK; version ledger OK (14 entries); scalafmt check OK; Test/compile OK; verifyHeldoutOutputRoute PASS; verifyHeldoutGuards PASS (23 launch + 17 clean-tree cases); verifyErDevRunGuards PASS (59 cases). Passed: core 423, entityResolution 448, hadoop 85, tests 145; rag, root, services, sparkAnalytics, storage 0. markTestRun: suites=83, passed=1101, failed=0, errored=0, skipped=0, git_head=426df590f3f043c96021b739dd7f39436dd30956, worktree=dirty.
VALIDATION RESULTS: erDecisionGroupingDev NOT RUN (not allowlisted; committed inputs absent). No gold/**, held-out path, target/streams/**/goldSetEvalHeldout or target/local-runs/heldout read; no Grep used; no *Heldout task and no sbt clean; no .claude/** edit.
ASSUMPTIONS: The run roots are excluded from both run-ID digests (ER-P6-04 and ER-P6-05 reports). The curated dev run ID run:2881724b… stays fixed while the committed Hadoop dev manifest is unchanged. entity-resolution-engineer is the identity that runs the task.
RISKS: see section 4 (unobserved run path, estimated bounds, mirrored layout, hardcoded overlay schema, sbt clean deletes the dev runs, asserted codeVersion, per-checkout overlay hash, no grouping read-back until the ER change).
MANUAL ACTION REQUIRED: Human: ratify the allowlist row (section 3) before factory-engineer activates it. Decide whether to add sbt clean protection for the dev runs (not recommended).
BLOCKED-ON OWNER: entity-resolution-engineer (three committed inputs, consistency test, grouping read-back); then factory-engineer (allowlist row, probes, docs/agents.md mirror) with human ratification
BLOCKED-ON EVIDENCE: The task refuses until modules/entity-resolution/local-runs/er-dev.conf, er-decisions.dev.run-manifest.json and er-grouping.dev.run-manifest.json are committed and unmodified (the committed-file check, as in localPipelineDev). Those paths are under modules/entity-resolution/** (entity-resolution-engineer). GroupingRunMain.runWithConfig publishes without reading the run back (GroupingRunMain.scala lines 191-193). No allowlist row names the task.
NEXT GATE: entity-resolution-engineer commits the inputs, the consistency test and the read-back (sbt check, code review). factory-engineer prepares the row; the human ratifies and restarts the session. entity-resolution-engineer then runs sbt erDecisionGroupingDev once and reports the run IDs.
```

Orchestrator note: agents cannot commit; "commits the inputs" above means writes them. The human commits them (docs/agents.md, Human gates: "Commits and pushes are human-only").

---

## ER-P6-06: entity-resolution-engineer (2026-11-16), verbatim

## ER-P6-06: the three dev-run inputs, the consistency test and both read-backs are in place, and `sbt check` passed

I wrote the three input files that `sbt erDecisionGroupingDev` needs and a feature test proving they agree. Both entry points now read their published run back. The final `sbt check` passed with 1109 tests, 0 failed, 0 skipped; `verifyErDevRunGuards` passed all 59 cases. I did not run `erDecisionGroupingDev` and committed nothing.

Only two things still block BE-P6-01: the human commit and the allowlist row.

### Computed values (taken from the test and entry-point output, not worked out by hand)
- **inputRunId:** `RunId.of` of the committed Hadoop dev manifest is `run:2881724b5c86b561a235836d51946fbd884beed36a14f929b27c278cb5de8baa`, which confirms BE's value. The test pins it.
- **Decision configDigest:** `digestWithoutGrouping` of er-dev.conf plus a roots overlay is `61655dc122d9317924cb58b67fa7fbd176349e8ea13401c17af2f865f9bbe085`. It is the same for two different roots overlays and for er-dev.conf alone.
- **Decision run ID:** `run:76ec32b67bb0be98a040c0bad751bcd987ef0fbd7085dca74612d877b6b47e7b`.
- **Grouping configDigest:** `GroupingRunSettings.configDigest` is `484845939b9f036bb5ff460a8b0a856cb1b731b5a2a080a1737168faa0026298`, the same for both overlays.
- **How I got them:** I committed placeholder zeros first and ran the suite. Its whole-file assertion printed the correct text. In the end-to-end run, GroupingRunMain refused with exactly two reasons: the configDigest and the decisionRunId. That shows every other field was already accepted by the entry point.

### New files, verbatim

`/Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/local-runs/er-dev.conf`
```
# Dev decision and grouping run configuration (ER-P6-06; owner entity-resolution-engineer).
# Read by sbt erDecisionGroupingDev through SCHOLARCHAIN_CONFIG_FILES, followed by the
# build-generated overlay, which supplies the two run locations of the checkout. Those two keys
# are deliberately absent here; neither enters a run ID. DEV ONLY.
# The entityResolution values are the accepted Phase 5 settings (er-policy.v8). Every other value
# is a dummy, non-secret placeholder: no real account, bucket, endpoint or model; no model is
# called. Changing any value changes both run-manifest configDigests (DevRunInputsSuite).
configSchemaVersion = "scholarchain.config.v2"
environment = dev-local
aws { region = "us-east-1", accountId = "000000000000" }
storage {
  rawBucket = "dummy-raw-bucket"
  curatedBucket = "dummy-curated-bucket"
  contextBucket = "dummy-context-bucket"
  vectorBucket = "dummy-vector-bucket"
  vectorIndex = dummy-index
  dynamoTablePrefix = dummy
  neptuneEndpoint = "dummy-neptune.invalid"
  openSearchEndpoint = null
}
models {
  llmModelId = "dummy-llm-model.not-called"
  embeddingModelId = "dummy-embedding-model"
  embeddingDimension = 8
}
graph {
  windows = [{ startYear = 2000, endYear = 2020 }]
  policyVersion = dummy-graph-policy
  identityVersion = v1
}
bridge { algorithm = dummy, sampleSize = 10, randomSeed = 7, maxIterations = 5 }
community { algorithm = dummy, maxIterations = 5 }
entityResolution {
  policyVersion = "er-policy.v8"
  acceptThreshold = 0.85
  rejectThreshold = 0.2
  llmBandLower = 0.4
  llmBandUpper = 0.7
  promptVersion = "er-adjudication-prompt.v2"
  groupingPolicyVersion = "er-grouping.v1"
}
runtime { maxPathHops = 3, retrievalTopK = 5, defaultWindow = { startYear = 2000, endYear = 2020 } }
```

`/Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/local-runs/er-decisions.dev.run-manifest.json` (one compact line plus LF)
```
{"schemaVersion":"scholarchain.run-manifest.v1","stage":"er.decisions","codeVersion":"426df590f3f043c96021b739dd7f39436dd30956","configDigest":"61655dc122d9317924cb58b67fa7fbd176349e8ea13401c17af2f865f9bbe085","inputSnapshots":[{"source":"dblp","snapshot":"dblp-2026-10-03"},{"source":"openalex","snapshot":"openalex-api-2026-11-05"},{"source":"crossref","snapshot":"crossref-api-2026-11-05"}],"parameters":{"blockingVersion":"er-blocking.v2","decidedAt":"2026-11-15T00:00:00Z","featureVersion":"er-features.v2","inputRunId":"run:2881724b5c86b561a235836d51946fbd884beed36a14f929b27c278cb5de8baa","policyVersion":"er-policy.v8"},"attempt":1}
```

`/Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/local-runs/er-grouping.dev.run-manifest.json` (one compact line plus LF)
```
{"schemaVersion":"scholarchain.run-manifest.v1","stage":"er.grouping","codeVersion":"426df590f3f043c96021b739dd7f39436dd30956","configDigest":"484845939b9f036bb5ff460a8b0a856cb1b731b5a2a080a1737168faa0026298","inputSnapshots":[{"source":"dblp","snapshot":"dblp-2026-10-03"},{"source":"openalex","snapshot":"openalex-api-2026-11-05"},{"source":"crossref","snapshot":"crossref-api-2026-11-05"}],"parameters":{"assignedAt":"2026-11-15T12:00:00Z","decisionRunId":"run:76ec32b67bb0be98a040c0bad751bcd987ef0fbd7085dca74612d877b6b47e7b","groupingPolicyVersion":"er-grouping.v1","identityVersion":"v1","inputRunId":"run:2881724b5c86b561a235836d51946fbd884beed36a14f929b27c278cb5de8baa","matchPolicyVersion":"er-policy.v8"},"attempt":1}
```

`/Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/review/DevRunInputsSuite.scala` (about 545 lines, 8 tests, all synthetic):
1. **er-dev.conf contents:** it contains no `RunRoot`, `RUN_ROOT`, `runroot`, `${` or `include`. It loads, and the loaded config has both roots `None` and the exact ER values. Those values also equal the er-policy.v8 policy file (and the grouping resource for `er-grouping.v1`). Without an overlay, `GroupingRunSettings.from` refuses because both roots are missing.
2. **Roots overlays:** with either of two roots overlays, only the two roots differ, and neither digest changes.
3. **Hadoop dev manifest:** `RunId.of` of it equals the pinned run ID, and its split is `dev`.
4. **Decision manifest:**
   - it is read with `DecisionRunMain.readManifest`, the entry point's own decoder, which keeps the final LF;
   - the whole file must equal the compact JSON rebuilt from the computed digest, snapshots and inputRunId, plus LF;
   - the parameter key set equals `DecisionExport.Params.All`;
   - `DecisionExport.manifestProblems` returns nothing under each overlay.
5. **Grouping manifest:**
   - it is read with `GroupingRunMain.readManifest`, which strips the LF;
   - the whole file must equal the rebuilt compact JSON;
   - decisionRunId equals `RunId.of` of the decision manifest, and inputRunId equals `RunId.of` of the Hadoop manifest;
   - the parameter key set equals `Grouping.Params.All`;
   - the versions equal the settings and the resource, assignedAt is not before decidedAt, and both manifests carry the same codeVersion.
6. **End to end:** the committed files drive both entry points through `ConfigEvidence.load`, with er-dev.conf plus a test overlay. The curated run is synthetic, except its `_run-manifest.json`, which is a byte copy of the committed Hadoop dev manifest. Both runs exit 0, print their verified lines and the er-dev.conf sha256, and read back equal to the committed manifests.
7. **Decision read-back failures (exit 2):**
   - bytes changed after `_parts.json` is written → "does not match its _parts.json";
   - a repeated row → "differs from the published batch".
8. **Grouping read-back failures (exit 2):**
   - bytes changed after `_parts.json` is written;
   - a repeated work assignment → "is not a valid grouping run", from the core dataset validators.

### Changed regions, for the reviewer
**`.../review/DecisionRunMain.scala`**
- 32-38: doc step 4 and the exit codes.
- 57-58: `readManifest` is now `private[review]`.
- 96-124: `readBackFailure` and `readBack`, which uses `readPublished` and requires the manifest and decisions to equal the batch.
- 126-135: a three-argument `runWithConfig` that delegates to a new overload taking a `writer`.
- 162-165: publish with `writer`, then `verified <- readBack(...)`, and the verified line in the output.

**`.../review/GroupingRunMain.scala`**
- 45-52: doc step 6 and the exit codes.
- 72-73: `readManifest` is now `private[review]`.
- 151-177: `readBack`, which uses `readValidated` and requires the run ID, manifest and all four record sets to equal the batch.
- 179-188: the `runWithConfig` overload.
- 233-236: publish with `writer`, then `readBack`, and the verified line.

Behaviour without a fault is unchanged apart from the extra verified line. No policy, feature, threshold, Resolver, outcome or grouping-rule change.

### Remaining blockers
Only the human commit of the untracked files and the factory-engineer allowlist row with human ratification. Two non-blocking build-engineer follow-ups:
- **Misleading exit-2 message:** `LocalRuns.erDecisionGroupingDev` says "nothing published" on any exit 2. After a read-back failure the run is published but unverified, and the entry point's own error line says so. The task's wording, and its create-once check on a re-run, should allow for this.
- **Summary wording:** the task's final summary line can now also cite DecisionRunMain's own read-back.

```text
STATUS: COMPLETE
OWNER: entity-resolution-engineer
WORK ORDER: ER-P6-06 (Phase 6): inputs that BE-P6-01 is BLOCKED-ON (er-dev.conf, two dev run manifests, consistency feature test, integrity read-back in GroupingRunMain and DecisionRunMain)
FILES CREATED:
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/local-runs/er-dev.conf
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/local-runs/er-decisions.dev.run-manifest.json
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/local-runs/er-grouping.dev.run-manifest.json
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/review/DevRunInputsSuite.scala
FILES MODIFIED:
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/review/DecisionRunMain.scala (untracked; 32-38, 57-58, 96-135, 162-165)
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/review/GroupingRunMain.scala (untracked; 45-52, 72-73, 151-188, 233-236)
COMMANDS RUN:
- git status --short; git ls-files (phase-06 evidence, ER and core sources, hadoop local-runs, gold/entity-resolution/dev-v1 -> empty); git log --name-only -- gold/entity-resolution/dev-v1 (empty); git diff --stat and --unified=0 for project/LocalRuns.scala, build.sbt and core Provenance.scala: ok
- sbt entityResolution/test x3: run 1 failed by design (placeholder digests; the computed values were printed); run 2 failed by design (grouping placeholders; the entry point named the two remaining mismatches); run 3 Passed: Total 456, Failed 0, Errors 0
- sbt entityResolution/testOnly scholarchain.entityresolution.review.DevRunInputsSuite: refused by guard (no-allowlist-entry); nothing ran
- sbt check (once, final command after the last edit): [success] Total time: 132 s
TEST RESULTS: sbt check success. Build sources OK; version ledger OK (14 entries); scalafmt check OK; verifyHeldoutOutputRoute PASS; verifyHeldoutGuards PASS (23 launch + 17 clean-tree cases); verifyErDevRunGuards PASS (59 cases). Passed per project: core 423, entityResolution 456, hadoop 85, tests 145; rag, root, services, sparkAnalytics, storage 0 (no tests). markTestRun: suites=84, passed=1109, skipped=0, git_head=426df590f3f043c96021b739dd7f39436dd30956, worktree=dirty.
VALIDATION RESULTS:
- All three files were checked against core functions and both entry points (8 DevRunInputsSuite tests); placeholder values were refused, as the failing runs showed.
- Versions: policy er-policy.v8, blocking er-blocking.v2, features er-features.v2, thresholds 0.85/0.2, band 0.4-0.7, prompt er-adjudication-prompt.v2 (no model called), grouping er-grouping.v1, identityVersion v1. All unchanged.
- No gold-set evaluation was run, so there is no confusion matrix, unresolved rate, threshold sensitivity, or false-positive or false-negative listing; none is claimed. Grouping stays unevaluated.
- Nothing under gold/**/heldout*, labels, target/streams, target/local-runs/heldout or the held-out attempt directory was read; no Grep was used; no *Heldout task, no goldSetEvalDev, no sbt clean, no erDecisionGroupingDev.
ASSUMPTIONS:
- codeVersion = 426df590f3f043c96021b739dd7f39436dd30956, the HEAD these changes are based on. It is asserted and not verified, and it is not the commit that will contain this code (that SHA cannot be known in advance). Changing it changes both run IDs; the test enforces that both manifests carry the same value and that the run-ID chain holds.
- decidedAt 2026-11-15T00:00:00Z and assignedAt 2026-11-15T12:00:00Z are fixed literals.
- gold/entity-resolution/dev-v1/er-config.conf does not exist and was never committed (git ls-files and git log are empty). So the ER values were confirmed against the accepted er-policy.v8.json resource and the BE proposal, not against a human dev config. The test asserts equality with the policy file.
- The overlay in the test mirrors the build's generated format (schema version plus the two roots). The test cannot import project/** code.
RISKS:
- A published-but-unverified run (read-back failure) exits 2 and stays on disk. The build task's exit-2 message says "nothing published" (build-engineer follow-up), and its create-once rule then requires the human to move the roots.
- Any edit to er-dev.conf, including the dummy values, changes both digests and the decision run ID. The suite fails until both manifests are regenerated, which is intended.
- The end-to-end test uses synthetic curated data. The real dev curated run has not been processed by these manifests until erDecisionGroupingDev runs.
- The CR-P6-08 decideAll provenance finding is carried forward and was not touched.
MANUAL ACTION REQUIRED:
- Human: review and commit the four new files and the two modified entry points; erDecisionGroupingDev refuses until they are committed and unmodified.
- Human: ratify the BE-P6-01 allowlist row (factory-engineer prepares it).
- Orchestrator: route a focused code-reviewer review of the files above, and optionally a build-engineer follow-up on the exit-2 wording.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer review of ER-P6-06; human commit; factory-engineer allowlist row with human ratification and a session restart; then entity-resolution-engineer runs sbt erDecisionGroupingDev once and reports the run IDs (expected decision run run:76ec32b6...e7b on the run:2881724b... dev curated run).
```
