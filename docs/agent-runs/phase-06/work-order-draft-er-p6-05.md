# Phase 06 draft work order ER-P6-05 (grouping), for human approval

- Requested by: docs/agent-runs/phase-06/prompt-de-p6-01.md ("Return ... the next grouping work order for approval")
- Author: entity-resolution-engineer, read-only draft, 2026-11-15
- Status: PROPOSAL. Nothing here is approved until the human records it in `human-gate.md`.
- Orchestrator note: the draft's "delete the empty leftover file `decisions/DecisionRunMain.scala`" item is stale. The human deleted that file on 2026-11-15 (see repair-01.md).

## Agent report, verbatim

## ER-P6-05-DRAFT: grouping work order for your approval (read-only; nothing created, edited or run)

**Summary.** The draft is in section 1. The B3 vetoes are spelled out in section 2, and the homonym check is in section 3. Four findings change the decision package:

1. **Vetoes should compare member pairs, not merged identifier sets.** The package's union-of-identifiers form of T1 and BD is not monotone: a link it vetoes can be merged later anyway. The core validator then refuses the run, because it requires a vetoed decision's endpoints to stay in different groups (`AssignmentValidation.scala:88-91`). A pair-level rule is monotone and fixes this (O1).
2. **No veto ever undoes a pair that er-policy.v8 accepted directly, under the recommended definitions.** It can only veto a merge that would join other members who disagree. One exception: the package's work BD override (publication DOI only) can veto a directly accepted preprint pair, so I propose an exact mirror of pairwise step 6a (O2).
3. **The pairwise homonym comparison is broader than the correct name context.** It ignores the base name and the source. HN is defined in the correct context, and er-policy.v8 is not changed (section 3).
4. **ST can never fire under er-policy.v8.** I recommend keeping it as a backstop (O5).

There is also one circular definition in C1, C2 and D1 (fixed by O6), and one possible core gap: CONFLICT and REJECTED pairs are not checked to stay apart (section 4, M2).

---

### 1. Work order ER-P6-05 (draft)

**Owner:** entity-resolution-engineer.

**Scope:** `modules/entity-resolution/**` only.
- New pure package `scholarchain.entityresolution.grouping`. It must not reference `review`, `evaluation` or gold. ER-P6-02 reported that `LeakageStructureSuite` enforces this (agent-report.md:287); I did not re-verify the suite.
- Resource `src/main/resources/scholarchain/entityresolution/grouping/er-grouping.v1.json`.
- Entry point `review/GroupingRunMain.scala`. It sits in `review` because it reads the curated run with `CuratedRunReader`, which lives in `review`.

**Inputs:**
- One published decision run. It is read only through the ER-P6-04 verified reader (`_parts.json` checked before use).
- The curated run it decided: the decision manifest's `inputRunId` must equal the curated run ID (`DecisionExport.scala:117-128`).
- External config through `GroupingRunSettings.from` (`ScholarChainConfig.scala:258-305`).
- A grouping run manifest (core RunManifest):
  - stage `er.grouping`;
  - the decision run's input snapshots;
  - `configDigest`;
  - exactly these parameters: `assignedAt` (UTC instant; no clock is read), `decisionRunId`, `inputRunId`, `groupingPolicyVersion`, `matchPolicyVersion`, `identityVersion`.

The run refuses when any of these holds:
- the resource version differs from the config's `groupingPolicyVersion`;
- the config's `entityResolution.policyVersion` (`ScholarChainConfig.scala:297-298`) differs from any decision's `provenance.policyVersion`, which `checkAgainstDecisions` also requires (`AssignmentValidation.scala:287-289`);
- the decision run is not listed as supported in the resource (`er-policy.v8` only).

**Outputs**, under `<assignmentRunRoot>/run=<hex>/`, published with the same rules as DecisionExport (staging, create-once, all or nothing):
- `assignments/researcher/part-00000.jsonl`: one ResearcherAssignment per mention and per profile.
- `assignments/work/part-00000.jsonl`: one WorkAssignment per work, with graphEligibility.
- `authorships/part-00000.jsonl`: AuthorshipRecordV2, one per identified mention on a representative. `matchConfidence` is the lowest supporting confidence; `year` comes from the work.
- `teams/part-00000.jsonl`: PublicationTeam for each counted work that has mentions. Provenance: derived, `sourceRecordId` = workId, the grouping run ID, `policyVersion` = er-grouping.v1.
- `_run-manifest.json` (LF-terminated), then `_parts.json`, then `_SUCCESS` last (`PartManifest.scala:57-66`).
- No veto log file, because no core schema exists for one. Veto evidence lives in `vetoedDecisionIds` and reason codes.

**Algorithm (B1, B2, C1-C3, D1, E1, E2):**
- Researchers and works are grouped separately. Only ACCEPTED decisions are links, merged one at a time in B2 order. A link whose ends are already in one group is kept as supporting (redundant).
- Every veto rule in section 2 is evaluated on every link, and every rule that fires is recorded.
- **Endpoint self-check (fail closed):** for each ACCEPTED decision, no veto may fire on its two endpoints alone. If one does, the curated inputs differ from the ones decided, and the run refuses with `grouping.invariant.endpoint-veto`.
- Before publishing, every core validator must return no errors: checkResearchers, checkWorks, checkRun, checkAgainstDecisions, checkAuthorships, checkAuthorshipConfidence and checkTeams. Otherwise nothing is written.

**Resource `er-grouping.v1.json`** holds:
- `schemaVersion: er-grouping-file.v1`, `groupingPolicyVersion: er-grouping.v1`, and `supportedMatchPolicies: ["er-policy.v8"]`;
- `linkOutcome: ACCEPTED` and the merge-order keys;
- the veto rules, each with id, kinds, schemes, definition, reason code, the pairwise rule it mirrors and whether it is stricter;
- the anchor order, collision scope, status rules, representative order and graph-eligibility rule.

It has no scores, weights or thresholds. The loader refuses a resource whose set of rule IDs differs from the implemented set.

**Entry point:** `GroupingRunMain <grouping run manifest .json> <decision run dir> <curated run dir> <assignment output root>`. It prints counts, the settings and config evidence. Exit 0 means published; exit 2 means refused, and nothing is written.

**Tests.** Each test also asserts that plain transitive closure would give a different result, so it fails without the rule. All fixtures are synthetic.
1. The resource loads and its rule-ID set matches. Refusals for a grouping-version mismatch, a match-policy mismatch and an unsupported decision policy.
2. One test per veto and scheme, using the section 2 examples:
   - N1 with REJECTED and with CONFLICT;
   - T1 for orcid, dblp-pid, doi and arxiv (v1 against v2 of one submission does not veto; different base IDs do);
   - researcher BD, with and without a pair-level ORCID override;
   - work BD, with the publication-DOI override and the pure-preprint arXiv override;
   - SW;
   - the HN cases of section 3, including the comma form and a 3-digit token;
   - ST with a synthetic decision set (see O5).

   Each checks the decision ID in `vetoedDecisionIds` on both endpoints and the reason codes.
3. The endpoint self-check: it passes on the committed-sample decisions, and a curated input changed after deciding refuses the run.
4. Monotonicity: a later attempt to merge the endpoints of a vetoed link is also vetoed, and the endpoints end in different groups.
5. Determinism. Identical groups and IDs under:
   - shuffled decision order and shuffled line order;
   - a different decision run ID, so every decision ID changes;
   - a different `assignedAt`, which changes only `assignedAt`.
6. Anchors (C1, each step) and collisions (C2), including a collision with an unresolved singleton (O6). The run asserts that researcher IDs are unique.
7. Statuses (D1): resolved; singleton with REJECTED decisions only; singleton anchored on its own ORCID or pid (`t1-anchored`) with a pending decision; unresolved (package example 4); profiles.
8. Works (E1 and E2):
   - representative order and the byte-order tiebreak;
   - single works and a group with two DBLP works;
   - package example 5;
   - an enrichment-only group.
9. Authorships and teams: matchConfidence is the minimum supporting confidence; teams exist only for counted works; unresolved mentions are listed in the team; teamSize counts every mention.
10. Corrupted outputs are refused by each core validator (the fail-closed path).
11. Publication: byte-identical rerun, no overwrite, nothing left after a failure, `_parts.json` verified, a truncated copy refused.
12. The leakage structure suite is extended, not weakened, and the grouping sources read no clock and no randomness.
13. Committed-sample probe with descriptive diagnostics only, labelled "unevaluated": group-size distribution, vetoes by reason, unresolved rate, anchor collisions, ST count (expected 0) and SW-C count (O8).

**Gate:**
- `sbt check` with exact counts;
- code-reviewer;
- your match-policy review;
- descriptive dev diagnostics labelled "unevaluated". A dev-split run needs a build-engineer alias and a factory-engineer allowlist row; until those exist, the committed-sample probe is used.

There is no precision or recall: grouping has no labels.

**Constraints:**
- No change to er-policy.v8, er-blocking.v2, er-features.v2, the thresholds, Resolver, DecisionExport decisions or Phase 5 evidence.
- No reads of `gold/**`, labels or held-out paths. No sbt goldSetEval tasks.
- uPickle only, no new dependency, and no values hardcoded (all from config or the resource).

**What it does not do:**
- no Spark, Hadoop, core or AWS changes;
- no LLM calls;
- no P-7 lifecycle linking;
- no group-size cap;
- no cluster-level gold set or quality claim;
- no review queue for vetoes or collisions;
- no Hadoop part manifests;
- no ResearcherMetrics v2 or C6;
- no change to DecisionExport beyond ER-P6-04.

---

### 2. B3 group-wide vetoes

**Notation.**
- A link is an ACCEPTED decision between x0 in group G1 and y0 in group G2.
- Each rule (except N1) checks cross pairs, meaning every x in G1 and y in G2, using each member's own data: identifiers from `EndpointIdentifiers.index` (`EndpointIdentifiers.scala:32-43`), and names and source from `MatchFeatures.prepare` (`MatchFeatures.scala:249-262, 281-286, 29-32`). These are the same inputs the pairwise decision used.
- I recommend this pair-level form over the package's union form (O1). Every pair-level rule has the form "some cross pair has property P", so a vetoed pair stays vetoed as groups grow. The union form is not monotone: unions that disagree now can share a value later. A link it vetoed could then be merged, which breaks `AssignmentValidation.scala:88-91`.

| Rule | Kinds | Compared | Fires when | Override | Reason code | Mirrors (file:line) | Stricter than pairwise? |
|---|---|---|---|---|---|---|---|
| N1 | both | decisions between cross pairs | a REJECTED or CONFLICT decision exists for some cross pair | none | `grouping.veto.negative-decision` | the decision itself | No |
| T1 | researcher: orcid, dblp-pid; work: doi, arxiv | each cross pair's values for the scheme | `compareCanonical(s, ids(x), ids(y)) == Conflict`: both assert values, and the comparable sets (arXiv by base ID) are disjoint (`ExactIdentifiers.scala:90-110, 165-175`) | none | `grouping.veto.t1-disagreement.<scheme>` | step 2, `er-policy.v8.json:11`; `Resolver.scala:511-515, 702-729` (CONFLICT or REJECTED, never ACCEPTED; "nothing overrides it") | No. Same test per pair, just applied to non-endpoint pairs |
| BD | researcher: openalex-author, s2-author; work: dblp-key, scopus-eid | each cross pair | some BD scheme conflicts for (x, y) and (x, y) has no accepting T1 agreement of its own | Researcher: x and y agree on orcid or dblp-pid. Work (recommended, O2): x and y share a DOI without a preprint prefix, or share an arXiv base ID or preprint DOI while `PublicationStage.evidence` is `Preprint` for both (`Corroboration.scala:58-68, 168-174`) | `grouping.veto.blocking-disagreement.<scheme>` | steps 3, 4, 6a, 7 and 8: `er-policy.v8.json:12-13, 15, 18-19`; `Resolver.scala:545-561, 730-751, 766-778, 810-828` | Pair-level: no, it is exactly the 6a mirror per pair. Package variants are stricter (see below) |
| SW | researcher | cross pairs of mentions | x.workId == y.workId | none | `grouping.veto.same-work` | `context.same_work` (`MatchFeatures.scala:444`); `Resolver.scala:593, 752-765, 866-875`; core `AssignmentValidation.scala:136-138`; `Collaboration.scala:44` | No (identical test) |
| HN | researcher | cross pairs, DBLP-sourced members only | equal normalized base names and different homonym numbers (section 3) | none | `grouping.veto.homonym-number` | `name.homonym_suffix_*` (`MatchFeatures.scala:398, 440-441`); step 5 and step 7 (`Resolver.scala:594-595, 752-765, 876-885`; `er-policy.v8.json:14`) | Narrower than pairwise in name context; wider only because it reaches non-endpoints |
| ST | work | cross pairs | one member's `PublicationStage.evidence` is `Preprint` and the other's is `Published` (`Corroboration.scala:99-107, 168-174`; `er-policy.v8.json:60`) | none | `grouping.veto.publication-stage` | publicationStage | No-op under v8 (see below) |

**Can a rule veto a link that er-policy.v8 accepted directly between x0 and y0? No, for every rule above in its recommended form.** It can only veto because of a non-endpoint pair. Per rule:
- **N1:** a pair has exactly one decision per kind, because the exporter refuses duplicate decision IDs (`review-report.md` CR-P6-01 check 1). The link's own pair is ACCEPTED.
- **T1:** a pair with a T1 disagreement is always CONFLICT or REJECTED (`Resolver.scala:702-729`).
- **BD:** with a BD, only step 6a can accept, and 6a needs `t1a` nonEmpty, which is exactly the override:
  - step 3 catches any T2 agreement (`Resolver.scala:730`);
  - 6b needs `deciding ⊆ t2a` without BD;
  - 6c is UNRESOLVED;
  - steps 7 and 8 turn ACCEPTED into UNRESOLVED (`Resolver.scala:810-828`).
- **SW:** `sameWork` gives CONFLICT (step 5) or REJECTED (`Resolver.scala:752, 866`).
- **HN:** HN on (x0, y0) implies the pairwise `homonymDiffers` on the same parsed names, which gives CONFLICT or REJECTED.
- **ST:** a pure preprint is never ACCEPTED against a non-preprint:
  - the stage is cross-stage or uncertain (`Corroboration.scala:180-186`);
  - its DOIs are all preprint DOIs, so the DOI and arXiv agreements are blocked (`Resolver.scala:545-554`);
  - record keys are blocked (`Resolver.scala:585-586`);
  - the cluster stage clause fails (`Corroboration.scala:151-152`);
  - steps 7 and 8 are stage-capped.

  Pure preprints therefore form closed ACCEPTED components, and **ST can never fire under er-policy.v8**.

Package variants that can veto a direct ACCEPTED link:
- **Work BD with only a publication-DOI override:** yes. Two DBLP CoRR records of one arXiv preprint have different dblp-keys and the same arXiv ID, and both are pure preprints. Pairwise 6a accepts them by overriding the dblp-key BD; the package override vetoes them, and the paper is counted twice in the graph.
- **Union T1 and BD:** not on the endpoints alone, but they are not monotone (above).

**Synthetic worked examples.** All IDs are invented. "Order" is B2.
- **N1.**
  - Links: m1–m2 ACCEPTED exact-id 0.99 (shared ORCID O1); m2–m3 ACCEPTED probabilistic 0.90.
  - m1–m3 is REJECTED (probabilistic, score at or below reject).
  - After {m1, m2} merge, m2–m3 is vetoed: cross pair (m1, m3) has a REJECTED decision.
  - Closure would have merged m1 and m3.
- **T1 (orcid).**
  - m1 has ORCID O1, m2 has no identifiers, m3 has ORCID O2.
  - m1–m2 is ACCEPTED deterministic 0.95; m2–m3 is ACCEPTED probabilistic 0.88; m1–m3 was never a candidate (block cap).
  - m2–m3 is vetoed: `t1-disagreement.orcid`.
- **T1 (doi).**
  - w1 has DOI 10.1/a, w2 is a DBLP record with no DOI, w3 has DOI 10.1/b.
  - Both links are ACCEPTED on the title-year-author rule; w1–w3 was never a candidate.
  - The second link is vetoed (`.doi`).
  - Not vetoed: w1 with arXiv 2101.00001v1 against w3 with 2101.00001v2 (same base ID).
- **BD (researcher).**
  - m1 is OpenAlex A1, m2 is DBLP with no identifiers, m3 is OpenAlex A2. m1–m2 is ACCEPTED at 0.93 and m2–m3 at 0.90.
  - Vetoed: (m1, m3) disagree on openalex-author, with no ORCID or pid agreement.
  - If m1 and m3 both carried ORCID O1: no veto (override).
  - If only m2 and m3 carried O1: the pair-level rule vetoes, while the package's union override would merge. This is the O3 difference.
- **BD (work).**
  - w1 `work:dblp:conf/x/A20` (key conf/x/A20, no DOI); w2 `work:doi:10.1/c`; w3 `work:dblp:journals/y/A21` (DOI 10.1/c).
  - Order: w2–w3 ACCEPTED exact-id 0.99 (DOI), then w1–w2 ACCEPTED deterministic 0.95 (same-stage-venue).
  - w1–w2 is vetoed: (w1, w3) disagree on dblp-key, and w1 has no DOI to override with. The conference and journal versions stay as two counted works.
- **SW.**
  - m1 is DBLP pos 0 on `work:dblp:conf/x/A20`, m2 is Crossref pos 0 on `work:doi:10.1/abc`, m3 is DBLP pos 1 on `work:dblp:conf/x/A20`.
  - m1–m2 is ACCEPTED deterministic (same-paper-same-position); m2–m3 is ACCEPTED probabilistic.
  - m2–m3 is vetoed (`same-work`).
- **HN.**
  - m1 is DBLP "Wei Wang 0003", m2 is OpenAlex "Wei Wang", m3 is DBLP "Wei Wang 0001".
  - m1–m2 is ACCEPTED at 0.91 and m2–m3 at 0.89; m1–m3 was never a candidate.
  - m2–m3 is vetoed (`homonym-number`).
- **ST** (only possible with a decision set that er-policy.v8 cannot produce).
  - w1 is a pure preprint, w2 is mixed (DOI P plus arXiv X), w3 is published (DOI P).
  - w2–w3 is ACCEPTED (DOI P); w1–w2 is ACCEPTED, which is hypothetical.
  - w1–w2 is vetoed (`publication-stage`).

---

### 3. DBLP homonym suffixes: verified behaviour and the HN definition

**What er-policy.v8 does (verified):**
- **String parsed:** `PersonName.parse(rawName)` for mentions (`MatchFeatures.scala:251`). Profiles use `BlockingKeys.profileName`: the structured name, whose suffix is always None, otherwise the display name (`BlockingKeys.scala:116-120`; `Names.scala:147-151`).
- **Parsing:**
  - names are folded with NFKD, digits kept (`TextFolding.scala:116-138`);
  - words keep hyphens (`Names.scala:88-92`);
  - the last word is taken as the suffix when there are at least 2 words and it is exactly 4 ASCII digits (`Names.scala:85-86, 99-100`);
  - after that, a generational suffix is stripped (`Names.scala:101`);
  - in comma form, the suffix is taken from the given part (`Names.scala:120-128`).
- **Comparison:** `suffixBoth` is true when both names have a suffix; then `agree` means the suffixes are equal (`MatchFeatures.scala:398, 440-441`). `homonymDiffers = both && !agree` (`Resolver.scala:594-595`).
  - **Not limited to the same base name.** Only the suffixes are compared. In practice, candidate pairs share the family block key and first initial (level-0 block, `BlockingKeys.scala:23-25`), but given names can differ.
  - **Not limited to DBLP.** Parsing ignores the source, so any source's name ending in a 4-digit word gets a "suffix".
  - **Absence:** suffix against no suffix never fires, which is correct.
- **Confusion risks:**
  - a trailing year or other 4-digit number ("John Smith 2019") is read as a homonym number;
  - fullwidth digits fold to ASCII;
  - 3-digit or 5-digit tokens, non-ASCII digits and `Smith-0001` are not suffixes;
  - "John Smith 0001 Jr." gives no suffix and leaves `0001` as the family name. Whether DBLP writes that form is UNVERIFIED.
- UNVERIFIED: that Hadoop keeps the DBLP number in `rawName`. The comment at `BlockingKeys.scala:24-25` implies it does.

**Discrepancy (reported; er-policy.v8 is not changed).** The pairwise rule compares homonym numbers across different base names and across non-DBLP names. Example: an OpenAlex "Wei Wang 2019" with the same ORCID as a DBLP "Wei Wang 0003" gives CONFLICT (step 5). Without an identifier, it gives REJECTED (step 7).
- It errs toward splitting, never merging.
- Fixing it needs a new er-policy version, and under D3(c) that requires disclosure and a fresh held-out set.
- In grouping, such pairs stay apart through N1 anyway.

**HN definition (recommended):**
- **Members considered:** only members whose source is DBLP (`ResearcherEndpoint.source == Dblp`, `MatchFeatures.scala:29-32`), using the same parsed name as pairwise.
- **Base key:** the folded `givenParts` plus `familyWords` of that parsed name. The suffix is already removed. There is no vowel-digraph collapse.
- **Fires when:** a DBLP member in G1 and a DBLP member in G2 have equal base keys and both suffixes are present and different.
- **Never fires on absence.** Because HN implies the pairwise `homonymDiffers`, it cannot veto a directly accepted pair.

Examples:

| Case | HN veto? | Pairwise result |
|---|---|---|
| DBLP "Wei Wang 0003" vs DBLP "Wei Wang 0001" | yes | — |
| DBLP "Wei Wang 0003" vs DBLP "Wen Wang 0001" | no (different base names) | rejects |
| DBLP "Wei Wang 0003" vs DBLP "Wei Wang" | no (suffix against none) | — |
| OpenAlex "Wei Wang 2019" vs DBLP "Wei Wang 0003" | no (OpenAlex is not DBLP-sourced) | rejects or conflicts |
| DBLP "Wang, Wei 0003" vs DBLP "Wei Wang 0001" | yes (same base) | — |
| "Wei Wang 003" | no suffix; the number stays in the name | — |

Profiles with several DBLP name variants: only the one parsed name is used, as in pairwise. Variants are not compared.

---

### 4. B3 against the delivered core validators and GraphEligibility

| ID | Field or rule | Finding | Owner |
|---|---|---|---|
| M1 | `vetoedDecisionIds`, `AssignmentValidation.scala:88-91` | Incompatible with the union-form T1 and BD (not monotone). Compatible with the pair-level form. | entity-resolution-engineer (choose pair-level); no core change |
| M2 | `checkAgainstDecisions`, `AssignmentValidation.scala:250-354` | Does not check that the endpoints of a CONFLICT or REJECTED decision end in different groups, so a silent merge of a CONFLICT pair (role law 2) would pass core. ER-P6-05 self-checks it. A core backstop is suggested. | domain-engineer |
| M3 | `checkAuthorships`, `AssignmentValidation.scala:405-414, 418-428` | Requires authorships on every representative, including enrichment-only ones, while teams exist only for counted works (`:477, 488`). This is coherent if Spark filters by graphEligibility, but needs confirming against "one author list per counted work". ER will emit exactly what core requires. | domain-engineer / human |
| M4 | `checkTeams`, `AssignmentValidation.scala:506` | `unresolvedMentionIds` is compared as a set, so its order is unspecified. ER will emit author order. Nit. | domain-engineer |
| M5 | `Assignments.scala:409-460` | Core does not check that a singleton with pending, vetoed or conflict decisions has an orcid or dblp-pid anchor (`t1-anchored`). This is deliberate (DE assumption: no closed set of anchor namespaces in core), so ER self-checks it. | entity-resolution-engineer |
| M6 | PublicationTeam | Hadoop TeamJob also writes PublicationTeam, with empty researcherIds (agent-report.md:32). Spark must read teams only from the assignment run. | spark-engineer |

These checks are consistent with B3 and need no change:
- D1 against the core status rules (`Assignments.scala:417-458`);
- SW against `:136-138`;
- E1 (DBLP first) against GraphEligibility (`Assignments.scala:618-651`; `AssignmentValidation.scala:185-193`): a group with a DBLP member always has a DBLP representative;
- the grouping run ID differs from the decision run ID (`Assignments.scala:184-189`), because the stages differ.

---

### 5. Determinism
- **B2:** links are ordered by path rank, then confidence descending (`java.lang.Double.compare`; confidence lies in [0,1]), then sourceId bytes, then targetId bytes. The pair is unique per kind, so the order is total. It uses no run ID, decision ID, candidate ID, decidedAt, file order or clock.
- **Merges:** each merge depends only on the state left by earlier links. Pair-level vetoes are monotone, and union-find roots are never output.
- **C1:** anchors are chosen from the final membership only.
- **C2 circularity (fix in O6):** "collided" was defined over groups that receive an ID, but whether a singleton receives one (`t1-anchored`) depends on collision. Define collided over all final groups.
- **D1:** status is a function of the final groups, the decisions and C1.
- **E1:** prefix order dblp, doi, openalex, orcid-work, then the smallest workId by bytes. An unknown namespace refuses the run.
- **Clock:** `assignedAt` and `decidedAt` come only from manifests. Decision IDs appear only as references, sorted by bytes.

---

### 6. Open decisions (your approval needed before ER-P6-05 starts)

| ID | Decision | Recommendation |
|---|---|---|
| O1 | T1, BD, HN, ST at pair level instead of union level | Pair level (monotone, required by M1) |
| O2 | Work BD override | Exact 6a mirror (publication DOI, or arXiv or preprint DOI when both are pure preprints). The package's DOI-only form can veto direct accepted links |
| O3 | Researcher BD override | Pair-level ORCID or pid agreement only. The package's union override is not monotone |
| O4 | HN context and base-key normalization | As in section 3 |
| O5 | ST | Keep as a backstop; report its count, expected 0 |
| O6 | Collision scope | All final groups |
| O7 | N1 scope | REJECTED and CONFLICT only; UNRESOLVED never vetoes |
| O8 | SW-C: two mentions on copies of one canonical work at different positions | Not a veto in v1; count it in diagnostics. It does not affect teams, which come from representatives only |
| O9 | M3 authorship scope | domain-engineer / your call |
| O10 | Reason-code vocabulary | `grouping.veto.*`, `grouping.anchor.<ns>`, `grouping.anchor-collision.<scheme>`, `grouping.singleton[.t1-anchored]`, `grouping.unresolved.*`, `grouping.representative.<rule>` |
| O11 | Rule evaluation | Evaluate every rule and record every reason, not only the first that fires |

**Prerequisites:**
- DE-P6-01 accepted after code-reviewer review (still pending), with its field names frozen; M2 decided.
- ER-P6-04 done and accepted: `_parts.json`, an LF-terminated run manifest and a verified reader.
- ER-P6-02 accepted and committed, after you delete the empty leftover file `decisions/DecisionRunMain.scala`.
- The D3(c) disclosure recorded in Phase 6 `human-gate.md`.
- Optional, for a dev-split run: a build-engineer alias plus a factory-engineer allowlist row for the dev decision and grouping runs.

```text
STATUS: PROPOSAL
OWNER: entity-resolution-engineer
WORK ORDER: ER-P6-05-DRAFT (Phase 6), read-only draft of grouping work order ER-P6-05; binding inputs /Users/taufeeqpatel/Desktop/ScholarChain/docs/agent-runs/phase-06/prompt-de-p6-01.md and /Users/taufeeqpatel/Desktop/ScholarChain/docs/agent-runs/phase-06/decision-package-01.md
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN:
- git ls-files --others --exclude-standard -- docs/agent-runs/phase-06 modules/entity-resolution modules/core: ok
- git ls-files -- modules/entity-resolution/src/main: ok
- git status --porcelain: ok (unchanged from the session start; I made no changes)
- No sbt command (by instruction)
TEST RESULTS: none, by instruction
VALIDATION RESULTS: Every file:line claim comes from a Read in this run: Assignments.scala, AssignmentValidation.scala, AuthorshipV2.scala, PartManifest.scala (1-120), ScholarChainConfig.scala (30-309), Collaboration.scala (1-80), Resolver.scala (280-1035), er-policy.v8.json, Names.scala, TextFolding.scala, MatchFeatures.scala (1-459), BlockingKeys.scala (1-120), ExactIdentifiers.scala, EndpointIdentifiers.scala, Corroboration.scala, DecisionExport.scala (1-140), and review-report.md (1-40). Two claims come from earlier reports and were not re-verified: pair uniqueness (CR-P6-01 check 1) and the LeakageStructureSuite constraint (ER-P6-02). Nothing under gold/**, target/** or any held-out path was read; no Grep was used. No dev diagnostics were run: grouping is unimplemented and unevaluated, so there is no confusion matrix, threshold sensitivity or false-positive/false-negative listing. Policy er-policy.v8 / er-blocking.v2 / er-features.v2 unchanged; model none; prompt er-adjudication-prompt.v2 (unused).
ASSUMPTIONS: DE-P6-01 field names are as read today (still under review). Hadoop keeps DBLP homonym numbers in rawName (UNVERIFIED). DBLP-source profiles may exist (the dblp-pid profile namespace, MatchFeatures.scala:202); whether curated runs contain any is UNVERIFIED. The config's entityResolution.policyVersion equals the decisions' policyVersion (UNVERIFIED; the run refuses if not). graph.identityVersion = v1 (configured value UNVERIFIED).
RISKS: Grouping is unevaluated and changes entity membership compared with the pairwise decisions. Pair-level BD may split people that OpenAlex split into several A IDs. Example: three DBLP members m0, m1, m2, where m0 carries A1 and m2 carries A2 and both are linked to m1; the second link is vetoed even though the person is probably one. The pairwise homonym parsing reads 4-digit years in non-DBLP names as homonym numbers (Phase 5 accepted; a fix requires D3(c)). ST is a no-op under v8. Single-JVM scale is unverified (H7). There are two PublicationTeam producers (M6). M2 leaves core without a backstop against merging CONFLICT pairs until domain-engineer adds one.
MANUAL ACTION REQUIRED: Human: decide O1-O11; approve or edit ER-P6-05; record the D3(c) disclosure; delete the empty leftover file modules/entity-resolution/src/main/scala/scholarchain/entityresolution/decisions/DecisionRunMain.scala. Orchestrator: route M2-M4 to domain-engineer and M6 to spark-engineer.
BLOCKED-ON OWNER: human (O1-O11 and approval); domain-engineer (DE-P6-01 review and acceptance, M2-M4); entity-resolution-engineer (ER-P6-04 first)
BLOCKED-ON EVIDENCE: AssignmentValidation.scala:88-91 (vetoed endpoints must stay apart; the union form breaks this); AssignmentValidation.scala:250-354 (no check that CONFLICT/REJECTED pairs stay apart); AssignmentValidation.scala:418-428 vs 477-488 (authorship vs team scope); MatchFeatures.scala:398,440-441 and Names.scala:85-86,99-100 (homonym context); Resolver.scala:545-561,702-828,866-885 (proof that no recommended veto can undo a directly accepted pair); Corroboration.scala:168-174 (per-side stage function exists)
NEXT GATE: You approve ER-P6-05 with O1-O11; DE-P6-01 passes code review and is accepted; ER-P6-04 is done. Then ER-P6-05 runs, gated by sbt check, code-reviewer, your match-policy review and descriptive committed-sample diagnostics labelled "unevaluated".
```
