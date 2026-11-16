# Phase 06 decision package 01: H1-H8 (ER-P6-03-DP)

- Work order (verbatim): docs/agent-runs/phase-06/prompt-er-p6-03-decision-package.md
- Author: entity-resolution-engineer, read-only proposal, 2026-11-15
- Status: PROPOSAL, awaiting human decision. Nothing in this file is approved until the human records it in `human-gate.md`.

## Agent report, verbatim

## ER-P6-03-DP: decision package for H1-H8 and the first implementation work order (read-only proposal)

I recommend one package. Ten items need your approval; the rest are defaults that take effect when you accept the package.

The main points:
- **Grouping is a new rule layer, `er-grouping.v1`.** It sits on top of the unchanged er-policy.v8 decisions.
- **Conflicts are handled by vetoing a merge.** Accepted links are merged one at a time in a fixed order, and a merge is skipped if it would join a pair that must stay apart.
- **IDs depend only on who is in the group.** They never use run IDs, decision IDs, input order or the clock.
- **Unresolved mentions get no researcher ID.** They still count in team size but add no coauthor edge.
- **One paper is counted once.** Copies of a paper are counted once through a representative record, which supplies the team.
- **Decision runs get a per-file integrity manifest.** It is a new core contract, and every reader verifies it.
- **First work order: DE-P6-01 (domain-engineer, core contracts).**

I created and changed nothing and ran no sbt command.

---

### 0. What I read and what I did not

- **Read:**
  - the work order;
  - `docs/agent-runs/phase-06/agent-report.md` (SP-P6-01, ER-P6-01, ER-P6-02), `review-report.md` (CR-P6-01, CR-P6-02) and `repair-01.md`;
  - Phase 5 `decisions-03`, `-05`, `-15` and `-16`, and `amendment-fe-p5-03.md` (lines 1-60 and 300-649);
  - `docs/agents.md:321-329`;
  - core and entity-resolution sources, cited below.
- **Not read:** anything under `gold/**`, any labels, anything under `target/**`, or any held-out output.
- **`human-gate.md`:** I read only lines 1-131, which stop before the metrics in sections 5-6. Line 94 states one held-out count. I do not repeat it.
- **Search:** no search tool was available (no Grep tool, and `git grep` is not on my allowlist). Every citation comes from reading the file.

---

### 1. Decision package

Each item is marked **APPROVAL** (requires your approval) or **DEFAULT** (recommended default, accepted with the package).

#### A. Grouping layer and D3(c) classification (H1, H8)

**A1 (APPROVAL): approve `er-grouping.v1` as its own layer.**
- It reads one published decision run plus the curated run it decided.
- It writes researcher and work assignments.
- Its rules live in a versioned ER resource, `er-grouping.v1.json`, alongside `er-policy.v8.json`.
- It adds no score, feature or threshold. It never edits a decision or a source record.
- Alternatives:
  - transitive closure inside Spark: rejected, because a chain of accepted links would merge records that disagree (role law 2);
  - no grouping at all: rejected, because every Spark job keys on canonical IDs (SP-P6-01).
- Deciding reason: keeping grouping a separate, versioned, deterministic layer keeps the pairwise evidence exactly as Phase 5 accepted it.

**A2 (APPROVAL): classify grouping under D3(c).**

What D3(c) requires. The committed rule (`docs/agents.md:327`) reads: "After any held-out result, a change to policy, features or thresholds needs disclosure in `human-gate.md` and a fresh held-out set before new final claims (D3(c))." It comes from `amendment-fe-p5-03.md:585`, and you chose "(c) disclosure and fresh held-out set rule in human-gate.md" in `decisions-16.md:8,17`. It has two parts, and both apply only when policy, features or thresholds change: a disclosure in `human-gate.md`, and a fresh held-out set before any new final claim.

Does grouping trigger it?
- **By its text, no.** er-policy.v8, er-blocking.v2, er-features.v2 and the thresholds are unchanged. Every decision is still the output of `Resolver.decide` plus `toMatchDecision` (CR-P6-01 check 1).
- **In effect, it changes who counts as the same entity downstream, in two ways:**
  - it puts two records in one group without a direct decision (example 1);
  - it can leave an ACCEPTED pair in different groups (example 2).
- **Recommended classification:** "D3(c) not triggered for pairwise claims. Grouping is a new layer that no evaluation has covered, and it is disclosed anyway." That means no fresh held-out set is needed for the existing pairwise claims, and no claim about grouping quality is made at all.
- Any later change to er-policy, features or thresholds triggers D3(c) in full.

Minimum disclosure for Phase 6 `human-gate.md` (draft for you to edit):

> Phase 6 adds er-grouping.v1, a rule layer that groups the pairwise decisions of er-policy.v8 / er-blocking.v2 / er-features.v2 into canonical researcher and work IDs. It changes no policy, feature or threshold. My D3(c) classification: [not triggered / triggered], because [...]. Grouping can merge records that have no direct decision and can split an ACCEPTED pair. The Phase 5 held-out evaluation (heldout-v1, accepted 2026-11-15) measured pairwise decisions only. It does not measure canonical-ID or cluster quality or any downstream graph metric, and no such claim is made in Phase 6. er-grouping.v1 was designed without held-out labels or evaluation output and was not tuned on any labels. Any later change to policy, features or thresholds triggers D3(c) in full.

What the accepted Phase 5 evidence supports (scope only, no numbers):
- **Supported:** pairwise MatchDecision outcomes under er-policy.v8 / er-blocking.v2 / er-features.v2 (`human-gate.md:131`).
  - The members are sampled candidate pairs from one held-out pipeline run (`human-gate.md:96`, generatingRunId).
  - Results are reported by entity kind and match path (`human-gate.md:98`).
  - "Unsure" labels are excluded from binary precision and recall (`decisions-05.md:39-41`).
- **Not supported:**
  - canonical-ID, cluster or group quality;
  - transitive merges and veto effects;
  - recall of the blocking step (pairs never generated as candidates);
  - live LLM adjudication (OA-1: no model connected; band pairs stay UNRESOLVED);
  - calibrated confidence (`er-policy.v8.json:6-7`: all confidences are uncalibrated constants);
  - full-scale behaviour;
  - any count, path, component, community or bridge metric.
- The metric values are in `human-gate.md` sections 5-6. I did not read them and I do not cite them.

**A3 (APPROVAL; H8, a scope question): make no claim about canonical-ID quality in Phase 6.**
- Report only descriptive dev and committed-sample statistics, labelled "unevaluated":
  - group-size distribution;
  - veto counts by reason;
  - unresolved rate;
  - anchor collisions.
- A quality claim needs a new cluster-level gold set with human-written labels in a later phase, designed blind like heldout-v1.
- Alternative: build that cluster-level gold set inside Phase 6. Deciding reason: it widens Phase 6, and nothing downstream needs it before the bounded run.

#### B. Deterministic conflict handling (H2)

**B1 (APPROVAL): merge one link at a time and skip vetoed merges (H2-a).**
- Every member of the entity kind starts as a group of one. Researcher members are mention and source-author IDs (`Candidates.scala:41-44`); work members are work IDs.
- Only ACCEPTED decisions are links.
- Links are processed in the order of B2. For each link:
  - if both ends are already in one group, the link is kept as redundant;
  - otherwise the cannot-link rules of B3 are checked on the two groups. If none fires, the groups are merged. If one fires, the link is recorded as vetoed, with a reason code per rule.
- A union-find structure may be used internally. Its internal representative never reaches the output: anchors and representatives are chosen afterwards from the final membership (C1, E1).
- Alternatives:
  - H2-b: turn every group that contains any violation entirely into singletons or unresolved members. Safer, but it throws away strong exact-ID groups because of one weak link.
  - Plain closure: rejected (law 2).
- Deciding reason: H2-a keeps the strongest evidence first and can never put a cannot-link pair into one group.

**B2 (APPROVAL): the order in which links are merged.** Links of one entity kind are sorted by this total order:
1. Match path rank, from `MatchDecisionPath` (`Enums.scala:58-71`): exact-id 0, deterministic-bibliographic 1, probabilistic 2, llm-adjudicated 3.
2. `confidence`, descending (`MatchDecision.scala:311-312`, 351). The contract keeps it finite and within [0,1], so there is no NaN.
3. `sourceId`, ascending by UTF-8 bytes.
4. `targetId`, ascending by UTF-8 bytes.

Why this order is total and depends on nothing outside the data:
- Pairs are stored with `sourceId < targetId` (`MatchDecision.scala:295-296`).
- A pair appears at most once per kind, because the exporter refuses duplicate decision IDs, and a decision ID hashes the kind, the pair, the policy and the run (`StableIds.scala:486-511`; CR-P6-01 check 1).
- So keys 3 and 4 never tie, and the order is total.
- None of the keys is a run ID, decision ID, candidate ID, decidedAt, file or line order, or the clock.

Why the result is deterministic:
- Each step's input is the group membership left by the earlier links in this fixed order. By induction, the final partition depends only on the set of decisions and the curated identifier and name data.
- Decision IDs appear only as references in the output lists, which are sorted by bytes.

**B3 (APPROVAL): the cannot-link rules.** Each rule is checked on the union of the two groups' members.

Rule sources: identifier sets come from `EndpointIdentifiers.index` (`EndpointIdentifiers.scala:32-43`), and comparisons use `ExactIdentifiers.compareCanonical` and `disagreement` (`ExactIdentifiers.scala:103-110`, 165-175; arXiv IDs are compared by base submission ID).

| Rule | Kinds | Fires when | Pairwise rule it mirrors |
|---|---|---|---|
| N1 | both | any REJECTED or CONFLICT decision between a member of one group and a member of the other | the decision itself |
| T1 | researcher: orcid, dblp-pid; work: doi, arxiv | `compareCanonical(union1, union2) == Conflict` | step 2 (`er-policy.v8.json:11`) |
| BD | researcher: openalex-author, s2-author; work: dblp-key, scopus-eid | the two unions disagree on the scheme, and no T1 agreement between the unions overrides it | step 6a override (`er-policy.v8.json:15`) |
| SW | researcher | the union would hold two mentions of the same workId | step 5, and `PublicationTeam` distinct researcherIds (`Collaboration.scala:44`) |
| HN | researcher | the union would hold two different DBLP homonym numbers (`PersonName.parse(name).homonymSuffix`, `Names.scala:16-22`, 97-103) | step 5 |
| ST | work | the union would hold a pure-preprint member and a pure-published member | publicationStage (`er-policy.v8.json:60`) |

Notes on BD:
- For researchers, any T1 agreement overrides.
- For works, only a shared publication DOI (one without a preprint prefix) overrides. This is conservative: at group level the publication stage is not computed, so an arXiv or preprint-DOI agreement does not override.

Three rules are deliberately stricter than the pairwise policy, and I want this approved explicitly:
- BD ignores the "if plausible" qualifier, because plausibility is a pairwise feature that does not exist for record pairs that were never candidates.
- The work BD override is limited to a publication DOI.
- ST is new. It stops a mixed record from chaining a preprint to its published version while P-7 is deferred.

UNVERIFIED: whether a per-side publication-stage function can be reused from `Resolver` or `Corroboration`. If not, ER-P6-05 builds one from the er-policy.v8 stage definition, with tests.

**B4 (DEFAULT): vetoed links are recorded.**
- The decision ID is added to `vetoedDecisionIds` on both endpoint members.
- Reason codes are `grouping.veto.<rule>[.<scheme>]`.
- Every vetoed link is flagged for human review, following the evidence hierarchy of law 1. The review mechanism is follow-up work.

#### C. Researcher ID selection (H4)

**C1 (DEFAULT): anchor order.** Applies to every group that receives an ID (status `resolved` or `singleton`). An identifier value is *collided* if it is asserted by members of more than one group that receives an ID.
1. Exactly one distinct ORCID value X, not collided: `ResearcherId.orcid(X)`, namespace `orcid`.
2. Otherwise, exactly one distinct dblp-pid value P, not collided: `ResearcherId.scholarChain(identityVersion, "dblp-pid", P)`.
3. Otherwise, exactly one distinct openalex-author value A, not collided: the same form with namespace `openalex-author`.
4. Otherwise, the member ID that is smallest by UTF-8 bytes: the same form with namespace `member`.

Form references: `StableIds.scala:235-268`.

**C2 (APPROVAL): anchor collisions.**
- A collided value is never used as an anchor. Each group involved falls through to the next anchor step and is flagged `anchor-collision.<scheme>`.
- Nothing is merged.
- Alternative: treat a shared identifier as an implicit link. Rejected, because it would bypass every pairwise check, including same-work (SW) and conflict (CONFLICT) decisions.

**C3 (DEFAULT): `identityVersion`.**
- Use `v1`, set in `GraphConfig.identityVersion` (`ScholarChainConfig.scala:41-53`). The configured value is UNVERIFIED.
- Any change to the anchor rules requires bumping it.

Determinism and stability:
- An ID is a function only of the group's members, their identifiers and identityVersion.
- After C1-C2, every ID is unique by construction:
  - collided values are excluded;
  - each member belongs to exactly one group;
  - the ORCID form and the `sc` form, and the different namespaces, never coincide.
  - The run still asserts uniqueness and fails closed if it breaks.
- ORCID-form IDs carry no version (`StableIds.scala:244-251`), so they survive reruns, snapshots, policy versions and identityVersion bumps.
- dblp-pid anchors survive everything except an identityVersion bump.
- OpenAlex anchors change if OpenAlex reassigns A IDs (`er-policy.v8.json:86`).
- Member anchors change when the group's smallest member changes, or when a mention ID changes because a source reorders its authors (`StableIds.scala:270-282`).

#### D. Unresolved mentions and singletons (H3)

**D1 (APPROVAL): member status.** Every researcher member gets exactly one status:
- **resolved:** the member's group has two or more members. Any UNRESOLVED, CONFLICT and vetoed decisions touching it are listed, but the member stays in its group.
- **singleton:** a group of one, and either:
  - no UNRESOLVED, CONFLICT or vetoed decision touches it (REJECTED-only is fine), or
  - it asserts its own ORCID or dblp-pid that is not collided (reason `grouping.singleton.t1-anchored`).
  It gets its own ID.
- **unresolved:** everything else. No researcher ID.
- Alternative H3-b: give unresolved members a provisional ID. Rejected, because it asserts a distinct person that the evidence does not support. An unresolved mention can still be the same person as an existing researcher, and splitting them would inflate researcher counts.
- The `t1-anchored` exception is new compared with ER-P6-01. A curated person identifier is strong enough to stand alone (example 2).

**D2 (DEFAULT, for spark-engineer to confirm against the README metric definitions, which I did not verify):** how each status reaches downstream records.
- `PublicationTeam.teamSize` counts every mention on the representative work, unresolved included (`Collaboration.scala:13-16`, 52-58).
- Unresolved mentions go to `unresolvedMentionIds`. They add no coauthor edge and get no AuthorshipRecord.
- Measures of distinct identities (C_r, coauthor edges and W_r weights, components, communities, bridges, paths) use resolved and singleton researchers only.
- Measures of team positions (M_r, A_r) use teamSize.
- T_r is an open choice for spark-engineer:
  - counted by position, it equals sum(teamSize - 1) and includes unresolved mentions;
  - counted by edge, it excludes them.
  Either way, publish a per-researcher count of unresolved coauthor occurrences, so the undercount is visible.
- P_r counts canonical works on which the researcher has a mention on the representative copy.
- Singletons get AuthorshipRecord v2 with no supporting decision (C3 below).

#### E. Work duplicates counted once (H5, H6)

**E1 (APPROVAL): the representative and the team.**
- Representative order: `work:dblp:`, then `work:doi:` (Crossref), then `work:openalex:`, then `work:orcid-work:`; ties go to the smallest workId by UTF-8 bytes.
- The canonical work ID is the representative's own workId, so no new WorkId namespace is needed (`StableIds.scala:204-233`).
- Every work gets a canonical work ID, its own if it is not grouped. Works are never dropped. UNRESOLVED work pairs stay separate and are flagged, which over-counts rather than over-merges.
- The team of a canonical work comes from the representative's mentions only. Mentions on the other copies still take part in researcher grouping, for example by contributing an ORCID, but add no team member or edge.
- Alternatives:
  - union of teams across copies: rejected, it double-counts;
  - the copy with the largest team: rejected, it is unstable across snapshots.

**E2 (APPROVAL, a scope question): only canonical works with a DBLP member enter the coauthorship graph.**
- Groups without a DBLP member stay as enrichment records.
- This removes double counting when a Crossref or OpenAlex copy's link to DBLP stays UNRESOLVED, and it matches DBLP's role as the seed source (CLAUDE.md data flow).
- Cost: works found only outside DBLP are excluded from the graph.
- Alternative: count all canonical works and disclose the possible duplicates.

**E3 (APPROVAL, a scope question; H6): keep P-7 lifecycle aggregation out of Phase 6** (`decisions-05.md:9-13`, 53).
- A preprint and its published version remain two works. `jointPublicationCount` then counts publication versions, which must be disclosed.
- Alternative: an `er-lifecycle` layer in Phase 6. It would need new rules, human labels and review.

#### F. Scale path (H7)

- **F1 (DEFAULT):** Phase 6 local and bounded runs use the single-JVM ER batch (DecisionRunMain plus a GroupingRunMain). Measure heap and time on the bounded input.
- **F2 (DEFAULT now; APPROVAL when chosen):** the full-scale architecture is decided after that measurement.
  - A MapReduce or Spark ER job would need a constitutional amendment to the module graph. CLAUDE.md allows hadoop to import only pure normalization and feature functions from entityResolution, and sparkAnalytics to import only core.
  - Decisions are pure per candidate and could be sharded. Grouping needs connected components.

#### G. Integrity of copied runs (your new requirement)

**G1 (DEFAULT): new core contract C5, `scholarchain.part-manifest.v1` (domain-engineer).**
- Written as `_parts.json`. It lists every file in the run directory except `_parts.json` and `_SUCCESS`, `_run-manifest.json` included.
- Per file: relative path, record schema version, row count, byte count and sha256.
- Row count means LF-terminated lines; every non-empty file must end with LF.
- Core also provides a pure verifier over observed (path, bytes, rows, sha256) tuples. Core does no I/O.
- It recording both a row count and a checksum satisfies your "row count or checksum" requirement with margin. It closes CR-P6-02 nit 2 (`review-report.md:157-160`).
- Why core: Spark cannot import ER (CLAUDE.md), so both must share a core contract.
- It stays out of RunManifest, because RunManifest is an input of the run ID and is written before any output exists (`StableIds.scala:569-602`).

**G2 (DEFAULT): publication order and verification.**
- Order: parts, then `_run-manifest.json`, then `_parts.json`, then `_SUCCESS` last.
- Readers (ER `readPublished`, Spark readers, and the copy script after copying) verify an exact file set, row counts, byte counts and sha256 before use.
- On S3 there is no atomic rename, so this verification is what makes a `_SUCCESS`-first or cut-short copy detectable.

**G3 (DEFAULT): owners.**

| Item | Owner |
|---|---|
| Emitting and verifying the manifest in ER (an ER-P6-02 follow-up) | entity-resolution-engineer |
| Verifying before Spark consumes a run | spark-engineer |
| Copy scripts with verification after the copy | infra-engineer |
| Running copies | the G6 operator (proposed: deploy-engineer) |

---

### 2. Worked examples (all synthetic; the IDs are invented)

**Example 1: transitive merge.**
- Members:
  - m1: DBLP mention on `work:dblp:conf/x/A20`, dblp-pid `p/1`;
  - m2: OpenAlex mention on `work:openalex:W7`, openalex-author A9;
  - m3: profile `source-author:…`, ORCID O1 and openalex-author A9.
- Decisions:
  - d23 (m2-m3): ACCEPTED, exact-id, confidence 0.90;
  - d12 (m1-m2): ACCEPTED, probabilistic, confidence 0.91;
  - m1-m3: never a candidate.
- Order: d23 (rank 0), then d12 (rank 2).
- d23: no cannot-link rule fires, so {m2, m3} merge.
- d12: compare {m1} with {m2, m3}:
  - no negative decision (N1);
  - ORCID is absent on m1; dblp-pid is absent on the other side (T1);
  - openalex is absent on m1 (BD);
  - the works differ (SW).
  So all three merge.
- ID: exactly one ORCID, O1, not collided, so `researcher:orcid:O1`. All three members are `resolved`.
- m1 and m3 are now one researcher, which Phase 5 never decided.

**Example 2: a vetoed ACCEPTED link.**
- Members: m1 (dblp-pid `p/1`), m2 (no identifiers), m3 (dblp-pid `p/2`).
- Decisions:
  - d12: ACCEPTED, deterministic-bibliographic, 0.95;
  - d23: ACCEPTED, probabilistic, 0.88;
  - d13: CONFLICT (dblp-pid disagreement, step 2).
- d12 is processed first: {m1, m2} merge.
- d23: compare {m1, m2} with {m3}. N1 fires (d13), and T1 fires on dblp-pid ({p/1} against {p/2}). The link is vetoed with reasons `grouping.veto.negative-decision` and `grouping.veto.t1-disagreement.dblp-pid`.
- Result:
  - {m1, m2} get `sc(v1, dblp-pid, p/1)`;
  - m3 is a singleton anchored on its own pid (`t1-anchored`), `sc(v1, dblp-pid, p/2)`, with d23 in `vetoedDecisionIds`;
  - m2 is flagged for review.
- The ACCEPTED pair m2-m3 ends up in different groups.

**Example 3: anchor collision.**
- Groups:
  - G1 = {m1 (ORCID O1, dblp-pid `p/1`), m2};
  - G2 = {m5}: a Crossref mention with ORCID O1. The m1-m5 candidate was dropped by the block cap.
- O1 is collided, so neither group uses it.
- G1 falls to its dblp-pid: `sc(v1, dblp-pid, p/1)`.
- G2 has no dblp-pid and no OpenAlex ID: `sc(v1, member, mention:…m5)`.
- Both are flagged `anchor-collision.orcid`. Nothing is merged, and no ORCID-form ID is issued for O1.
- Same outcome when the link was vetoed by SW: for example, two mentions on one work both carrying O1 because of a source error.

**Example 4: an unresolved mention.**
- m7: OpenAlex mention "J. Chen" on `work:openalex:W8` at position 3, with no identifiers.
- Its only decision, d78 with m8 ("Jie Chen", DBLP, in group G3), is UNRESOLVED on the probabilistic path, inside the LLM band, with no model connected.
- m7 is a group of one, touched by an UNRESOLVED decision, with no T1 identifier of its own. Status: `unresolved`, no researcher ID, `pendingDecisionIds=[d78]`.
- If W8 is the representative of its canonical work:
  - `PublicationTeam(W8).teamSize` counts m7, and m7 is listed in `unresolvedMentionIds`;
  - no coauthor edge and no AuthorshipRecord for m7;
  - its coauthors' C_r excludes m7.
- If W8 is a non-representative copy, m7 affects no team.

**Example 5: one paper in DBLP, Crossref and OpenAlex, counted once.**
- Records:
  - w1 = `work:dblp:conf/x/A20` (DOI 10.1/abc);
  - w2 = `work:doi:10.1/abc`;
  - w3 = `work:openalex:W1` (DOI 10.1/abc).
- All three pairs are ACCEPTED on the exact-id path through the DOI.
- No cannot-link rule fires: only w1 has a dblp-key, and all three are at the published stage.
- The representative is w1 (DBLP first), so `canonicalWorkId = work:dblp:conf/x/A20` for all three.
- The team comes from w1's three DBLP mentions only. A coauthor pair gains `jointPublicationCount` +1, not +3.
- An arXiv-only OpenAlex preprint, w4 = `work:openalex:W2`, is UNRESOLVED against w3 and stays separate.
  - ST would also veto it, since w4 is a pure preprint and w2 is published.
  - Under E2 it is not a graph work at all.

---

### 3. Work by tier

**(i) Local development (dev split and committed samples)**
- Contracts C1-C5 (DE-P6-01).
- ER-P6-04: `_parts.json` emitted and verified in DecisionExport, plus CR-P6-02 nits 1, 3 and 4. Nit 2 is closed by G1.
- ER-P6-05: `er-grouping.v1`, GroupingRunMain and tests.
- build-engineer: dev aliases for the decision run and the grouping run. factory-engineer: allowlist rows, which you ratify.
- Spark rerun on committed samples. `sparkIT` is inactive under B10.
- Approvals needed: A1-A3, B1-B3, C2, D1, E1-E3.

**(ii) Bounded EMR run**
- S3 publication gap (no atomic rename): handled by G2 together with C5 verification.
- Integrity before Spark consumes a copied run: verified by spark-engineer's readers, and by infra-engineer's copy scripts after each copy.
- F2, the Spark/Scala target (spark-sql_2.13 3.5.5 against EMR Scala 2.12): you decide, then build-engineer implements.
- G6, the operator: proposed deploy-engineer; you ratify.
- Also needed:
  - environment ratification (the ENV-unratified rows);
  - Terraform (infra-engineer);
  - your cost approval.
- Recommendation: run ER locally on the bounded curated output, then copy the verified decision and assignment runs to S3. This avoids packaging ER as an EMR step.
- I am not solving infrastructure here.

**(iii) Full-scale follow-up**
- H7 architecture, which may need a constitutional amendment.
- A cluster-level human gold set (A3).
- P-7 lifecycle aggregation (E3).
- A live LLM model (OA-1).
- Blocking recall.
- ID continuity across identityVersion bumps (a crosswalk).
- Part manifests for Hadoop curated runs (hadoop-engineer).
- A core layout contract to replace the hardcoded Hadoop folder names (`CuratedRunReader`).
- A human review queue for vetoed links and collisions.

---

### 4. Minimum shared contracts, work-order order and gates

| # | Contract | Owner |
|---|---|---|
| C1 | `ResearcherAssignment` v1 | domain-engineer |
| C2 | `WorkAssignment` v1 | domain-engineer |
| C3 | `AuthorshipRecord` v2 | domain-engineer |
| C4 | config keys: `entityResolution.groupingPolicyVersion`, decision-run and assignment-run locations | domain-engineer |
| C5 | `PartManifest` v1 plus a pure verifier | domain-engineer |
| C6 (deferred to the Spark contracts work order) | meaning of `CanonicalResearcher.identityConfidence` (`Identity.scala:14-15`, 27): it is required, but grouping produces no calibrated value | domain-engineer |

Work orders, in order:

| Order | Work order | Owner | Gate |
|---|---|---|---|
| 1 | DE-P6-01 | domain-engineer | contract tests, `sbt check`, code-reviewer, your acceptance |
| 2 | ER-P6-04 (integrity) | entity-resolution-engineer | `sbt check`, code-reviewer |
| 3 | ER-P6-05 (grouping) | entity-resolution-engineer | `sbt check`, code-reviewer, your match-policy review, dev diagnostics labelled dev (descriptive, no labels) |
| 4 | BE and FE aliases and allowlist rows | build-engineer, factory-engineer | `sbt check`, validator, your ratification |
| 5 | DE-P6-02 (Spark items 3-12, and C6) | domain-engineer | contract tests, `sbt check`, code-reviewer |
| 6 | SP-P6-01 rerun | spark-engineer | Spark tests, `sbt check` |
| 7 | F2, G6, Terraform | you, build-engineer, infra-engineer, deploy-engineer | per `docs/agents.md` |

---

### 5. First implementation work order (draft for your review)

**ID:** DE-P6-01. **Owner:** domain-engineer.

**Why this one first:**
- Every consumer needs these shapes: ER grouping, ER integrity and Spark.
- Spark cannot import ER.
- C5 needs none of the H decisions.
- Building grouping first would mean inventing an output format and redoing it later.

**Scope:** `modules/core/**` only.

1. **C1 `ResearcherAssignment` (`scholarchain.researcher-assignment.v1`).**
   - Fields:
     - `memberId`; `workId` and `position` (both optional; required for mentions, and `MentionId.of` must reproduce the memberId);
     - `status` (`resolved`, `singleton` or `unresolved`; frozen tags);
     - `researcherId` (optional); `identityVersion`; `anchorNamespace` and `anchorValue` (both optional); `groupSize`;
     - `supportingDecisionIds`, `vetoedDecisionIds`, `pendingDecisionIds`, `conflictDecisionIds` (each sorted by bytes, distinct, and disjoint from the others);
     - `reasonCodes` (non-empty tokens);
     - `groupingPolicyVersion`, `matchPolicyVersion`, `decisionRunId`, `assignedAt` (UTC instant; not an input of any ID), `provenance`.
   - Record invariants:
     - `researcherId` and the anchor fields are present exactly when status is not `unresolved`;
     - an ORCID-form ID equals `ResearcherId.orcid(anchorValue)` with namespace `orcid`;
     - an `sc`-form ID equals `ResearcherId.scholarChain(...)`;
     - `resolved` requires groupSize ≥ 2 and supporting IDs;
     - `singleton` and `unresolved` require groupSize 1 and no supporting IDs;
     - `unresolved` requires at least one pending, vetoed or conflict ID.
   - Dataset invariants, as a pure validator:
     - one record per memberId;
     - one anchor per researcherId;
     - groupSize equals the number of members sharing the researcherId;
     - no two mentions of one work share a researcherId.
2. **C2 `WorkAssignment` (v1).**
   - Fields: `workId`, `canonicalWorkId`, `isRepresentative`, `status` (`grouped` or `single`), `representativeRule` (token), `groupSize`, the four decision-ID lists, `reasonCodes`, both policy versions, `decisionRunId`, `assignedAt`, `provenance`.
   - Invariants: the representative maps to itself; `single` means groupSize 1 and `canonicalWorkId == workId`.
   - Dataset invariants: exactly one representative per canonical work, and groupSize is consistent.
3. **C3 `AuthorshipRecord` v2.**
   - `matchDecisionId` is replaced by `supportingDecisionIds`, which is empty for singletons. `matchConfidence` becomes optional. Adds `assignmentStatus` (`resolved` or `singleton`) and `groupingPolicyVersion`.
   - Whether v1 is retired or kept is for domain-engineer to verify; it is UNVERIFIED whether any v1 record was ever written to curated output.
4. **C4: config keys,** with refusals on missing or invalid values.
5. **C5: `PartManifest` (`scholarchain.part-manifest.v1`) plus a pure verifier**, as in G1.
6. **`SchemaVersions` entries and frozen wire examples** for each new record.

**Inputs:** this package (the approved items), ER-P6-01 §5, SP-P6-01 items 1-2, CR-P6-02 nit 2, and the existing core sources.

**Outputs:** core sources and tests. No data.

**Tests (each fails without the change):**
- A JSON round trip for each new record, with unknown keys rejected. These do not compile without the change.
- One refusal case for each invariant above.
- Each dataset-validator violation, plus one valid synthetic set.
- AuthorshipRecord v2 accepts a singleton without a decision (v1 refuses it, `Identity.scala:116`).
- PartManifest refuses:
  - unsorted or duplicate paths;
  - `..`, absolute or backslash paths;
  - negative counts;
  - a bad sha256.
- The verifier refuses:
  - a missing file or an extra file;
  - a row, byte or sha256 mismatch;
  - a non-empty file without a final LF.
  It accepts an exact match.
- The config loader refuses a missing `groupingPolicyVersion` or a missing location key.
- All fixtures are synthetic.

**Gate:** `sbt check` with exact counts, then code-reviewer, then your acceptance.

**Constraints:**
- `modules/core/**` only, uPickle, and no new dependency.
- No I/O in the verifier.
- No hardcoded paths or values.
- No change to `MatchDecision` v2, `RunManifest`, any ID formula or existing frozen examples.
- No reads under `gold/**`.

**What it explicitly does not do:**
- no grouping algorithm, anchor selection or representative selection (core only validates them);
- no ER, Hadoop or Spark code;
- no Spark items 3-12 and no C6;
- no Hadoop part manifests;
- no config values.

**Approvals needed before it starts:** A1, B1 (the vetoed and conflict lists), D1 (the status set and the `t1-anchored` rule) and E1 (the representative fields). C1 and C3 are defaults.

**Fallback:** if those approvals are pending, DE-P6-01 can start with C5 alone, because the integrity requirement is already yours.

---

```text
STATUS: PROPOSAL
OWNER: entity-resolution-engineer
WORK ORDER: ER-P6-03-DP (Phase 6), read-only decision package for H1-H8 plus the first implementation work order; verbatim human text at /Users/taufeeqpatel/Desktop/ScholarChain/docs/agent-runs/phase-06/prompt-er-p6-03-decision-package.md
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN:
- git status --porcelain: ok (only the expected untracked phase-06 and entity-resolution files)
- git ls-files --others --exclude-standard -- docs/agent-runs/phase-06 modules/entity-resolution: ok
- git ls-files -- docs/agent-runs/phase-05: ok
- git log --oneline --max-count=8: ok (HEAD 426df59)
- git ls-files -- modules/entity-resolution/src/main modules/core/src/main: ok
- Grep tool: not available in this session (tool error); nothing ran
- No sbt command (by instruction)
TEST RESULTS: none, by instruction
VALIDATION RESULTS: Every citation was checked by Read in this run except Pipeline.scala:490-503 and CuratedRunReader, which are cited from ER-P6-01. No gold/**, label, target/** or held-out output was read or searched. human-gate.md was read only for lines 1-131; line 94 contains one held-out count, which is not repeated. No dev diagnostics were run. The only rate quoted is ER-P6-02's committed-sample probe (2 of 109 researcher decisions UNRESOLVED), with no labels. No confusion matrix, threshold sensitivity, false positives or false negatives exist for grouping, because it is unimplemented and unevaluated. Model: none; prompt version er-adjudication-prompt.v2 (unused, no live model); policy er-policy.v8, er-blocking.v2, er-features.v2, unchanged.
ASSUMPTIONS: Phase 5 work target stays version-level. GraphConfig.identityVersion = v1 (configured value UNVERIFIED). Each pair is unique per kind in a decision run, because duplicate decision IDs are refused (CR-P6-01). Spark consumes data only. EMR Spark target and operator are still open (F2, G6).
RISKS: Grouping changes membership compared with the pairwise decisions and is unevaluated. B3 is stricter than the pairwise policy, so it may split true matches. Probabilistic chains can make large groups (report sizes; no cap is proposed). Under E1, a researcher group that holds only non-representative mentions gets no P_r. Version-level counting inflates joint counts while P-7 is deferred. Member-anchored and OpenAlex-anchored IDs are less stable. Single-JVM fit at full scale is unverified. UNVERIFIED: a reusable per-side publication-stage function; whether AuthorshipRecord v1 was ever written; the README metric formulas for T_r and A_r; Hadoop and Spark glob handling of .staging- names.
MANUAL ACTION REQUIRED: Human: decide the APPROVAL items A1-A3, B1-B3, C2, D1, E1-E3; write the D3(c) disclosure in Phase 6 human-gate.md; approve or edit DE-P6-01, or its C5-only fallback. Later: F2, G6, environment ratification.
BLOCKED-ON OWNER: human (the approval items above) before DE-P6-01 starts in full; none for the C5-only fallback
BLOCKED-ON EVIDENCE: Identity.scala:88,116 (matchDecisionId required); Collaboration.scala:44,52-58; StableIds.scala:204-268,486-511,569-602; MatchDecision.scala:293-298; docs/agents.md:327 and decisions-16.md:8,17 (D3(c)); decisions-05.md:9-13,53 (P-7); review-report.md:157-160 (CR-P6-02 nit 2); CLAUDE.md module graph (hadoop and spark import limits)
NEXT GATE: Orchestrator presents this package to the human. After approval: DE-P6-01 (contract tests, sbt check, code-reviewer, human acceptance), then ER-P6-04, ER-P6-05 (adding human match-policy review), BE and FE aliases, DE-P6-02, SP-P6-01 rerun, then F2 and G6 before any EMR run.
```
