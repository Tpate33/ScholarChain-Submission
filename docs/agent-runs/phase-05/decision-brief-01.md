# Phase 5 consolidated decision brief 01 (orchestrator)

Assembled 2026-11-05 from the ER-P5-03 and ER-P5-04 reports (`agent-report.md`), at the human's
request in `decisions-04.md`. **Nothing here is implemented or decided.** Every item awaits the
human's response.

Terminology:
- 0.99, 0.95 and 0.90 are **uncalibrated policy constants**, not measured confidence.
- "Plausible" is the er-policy.v2 plausibility test (`Plausibility.scala`), which uses only
  non-identifier evidence (names, initials, titles, author sequence).

## Part 1. Work-labeling target

Researcher pairs, under every option: one question, "Do A and B refer to the same person?"
Label choices are always **match / non-match / unsure**. "Unsure" is excluded from precision
and recall and reported separately as coverage loss. It is never replaced by another pair.

### Option A: version-level
- **Question (work pairs):** "Do A and B describe the same version of a publication: the same
  text at the same publication stage (preprint, proceedings paper, journal article, thesis,
  report)?"
- **The policy's `work` entity means:** one registered version. This is the current
  er-policy.v2 meaning.
- **Metrics:** precision, recall, F1, confusion matrix and coverage, per entity kind and per
  match path (exact-id, deterministic-bibliographic, probabilistic, llm-adjudicated), against
  version labels. Also a per-criterion breakdown, unresolved and conflict rates, and threshold
  sensitivity.
- **README fit:** DOI and arXiv stay authoritative; README:1221 and 1242 are fully met.
- **Known tension:** OpenAlex can merge versions (in 7 of 8 preprint-vs-VoR units, both DOIs
  returned one OpenAlex work), so an OpenAlex W agreement can be a version-level false positive.

### Option B: work-level (lifecycle)
- **Question (work pairs):** "Are A and B versions of the same paper: its preprint(s), accepted
  manuscript, version of record, or corrected/updated versions?"
- **The policy's `work` entity means:** one paper across its lifecycle.
- **Metrics:** as A, against work labels, plus an explicit version-split false-negative count.
- **README fit / tension:** incoherent with the current policy.
  - A DOI disagreement gives CONFLICT, so true preprint↔VoR matches can never be ACCEPTED.
  - It needs a version-of relation in core and Spark (P-7, out of Phase 5 scope), or a rule
    making DOI non-authoritative for preprint↔VoR pairs. That rule would weaken README:1221.

### Option C: two questions (recommended)
- **Q1:** Option A's question. **Q2:** Option B's question. Both are asked for each work pair.
- **Validator rule:** Q1 = match requires Q2 = match. Q2 = non-match requires Q1 = non-match.
  "Unsure" may be given to either question independently.
- **The policy's `work` entity means:** version. The policy is scored against Q1. Q2 is
  recorded for Phase 6.
- **Metrics:**
  - all of A's metrics against Q1 (primary);
  - diagnostics only: the Q1×Q2 table, the system outcomes on pairs with Q2 = match and
    Q1 = non-match, and precision/recall against Q2.
- **README fit:** as A, fully met.
- **Cost:** one extra answer on work rows only.

### Edge cases (M = match, N = non-match)

| Case | A | B | C (Q1/Q2) |
|---|---|---|---|
| Preprint vs version of record | N | M | N/M |
| Extended journal version of a conference paper | N | N | N/N |
| Corrected version (same DOI updated, or arXiv vN) | M | M | M/M |
| Erratum or corrigendum notice vs the article | N | N | N/N |
| Retraction notice vs the retracted paper | N | N | N/N |
| Retracted paper (flagged) vs another record of the same paper | M | M | M/M |
| Same-title editorial, different year | N | N | N/N |
| Thesis vs paper | N | N | N/N |
| One text with two DOIs (for example proceedings plus a SIG newsletter) | M | M | M/M |

### Recommendation: C
- It keeps the version-level policy and the README conflict rules intact, and needs no
  held-out reselection.
- It gives Phase 6 the measurement it needs to decide on version aggregation, without
  committing now.
- Fallback if labeling time is tight: A. B is not recommended before P-7.

## Part 2. Identifier precedence (recommended: the "narrow" reading)

"Block" means **block inferred acceptance only**. Inferred acceptance is ACCEPTED on the
deterministic-bibliographic, probabilistic or llm-adjudicated path. An identifier acceptance
(exact-id path) is overridden only by an agreement of a strictly higher tier.

### 2.1 Per-scheme agreement and disagreement

| Scheme | Layer / tier | Agreement means → outcome | Disagreement means → outcome | Who can override the disagreement |
|---|---|---|---|---|
| orcid | researcher, T1 | same person → ACCEPTED (0.99) | different people → CONFLICT if plausible or any ID agrees; otherwise REJECTED (0.95) | nothing |
| dblp-pid | researcher, T1 | DBLP says same person → ACCEPTED (0.99) | DBLP says different people → as orcid | nothing |
| doi | work/version, T1 | same version → ACCEPTED (0.99) | different versions or a wrong DOI → as orcid | nothing |
| arxiv | work/version, T1 (vN folded) | same preprint line → ACCEPTED (0.99) | different submissions → as orcid | nothing |
| openalex-author (A) | researcher, T2 (algorithmic) | same cluster → ACCEPTED (0.90) | possibly a split cluster, not proof. Plausible pair: blocks inferred acceptance (UNRESOLVED, no LLM). Implausible pair: REJECTED (0.95). Never CONFLICT on its own. | T1 researcher agreement (orcid, dblp-pid) → ACCEPTED (0.99) with an override reason code. A conflicting T2 agreement → CONFLICT. |
| s2-author | researcher, T2 (inert) | as A | as A | as A |
| dblp-key (P-2) | source record, T2 (curated) | same DBLP record → ACCEPTED (0.99) | two DBLP records (often preprint + venue, sometimes a duplicate). Blocks inferred acceptance; never CONFLICT or REJECTED on its own. | T1 work agreement (doi, arxiv) → ACCEPTED (0.99) with an override reason code. A conflicting T2 agreement → CONFLICT. |
| scopus-eid (P-2) | source record, T2 (curated) | same Scopus record → ACCEPTED (0.99) | as dblp-key | as dblp-key |
| openalex-work (W) (P-1) | source record, T2 (algorithmic) | same OpenAlex record → ACCEPTED (0.95) | evidence only: a reason code; never blocks, decides or enters `conflicts` | n/a |
| mag (P-1) | source record, T2 | same MAG record → ACCEPTED (0.95) | evidence only | n/a |
| s2-corpus, s2-paper (P-1) | source record, T2 (inert) | same S2 record → ACCEPTED (0.95) | evidence only | n/a |
| repository-url | artifact, T3 | evidence only (shared code artifact, not a shared work); never decides | evidence only | n/a |

**P-6 (optional, separate):** set the `id.other.conflict` score weight from -1.5 to 0. Every
same-source pair disagrees on its own record key by construction, so the current weight
penalizes all of them. This is a design correction, not tuning.

### 2.2 Order of evaluation (every pair)

| Step | Condition | Outcome | Can a later step change it? |
|---|---|---|---|
| 0 | invalid candidate, version mismatch, inconsistent endpoint IDs | refused | n/a |
| 1 | classify every scheme as agree / disagree / absent | bookkeeping; evidence-only schemes add reason codes | never decides |
| 2 | T1 disagreement | CONFLICT if plausible or any agreement; otherwise REJECTED | no |
| 3 | no T1 agreement or disagreement; a blocking disagreement plus a same-tier T2 agreement | CONFLICT (contradicting identifiers) | no |
| 4 | no T1 agreement; an algorithmic-author disagreement on an implausible pair | REJECTED | no |
| 5 | researcher; any accepting agreement plus same-work or different DBLP homonym numbers | CONFLICT | no |
| 6a | T1 agreement | ACCEPTED (0.99); overrides blocking disagreements (override reason code) | no |
| 6b | T2 agreement, no blocking disagreement | ACCEPTED (0.90 / 0.99 / 0.95 by scheme) | no |
| 7 | deterministic bibliographic rules | reject rules give REJECTED; accept rules give ACCEPTED (0.95), or UNRESOLVED if a blocking disagreement exists | no (a blocked accept goes to human review) |
| 8 | score p (uncalibrated) | p ≥ accept gives ACCEPTED, or UNRESOLVED if blocked; p ≤ reject gives REJECTED; otherwise UNRESOLVED, band-eligible only if p is in the band and `conflicts` is empty | only via step 9 |
| 9 | LLM band (interface only, OA-1; no paid calls) | accept, reject or abstain (UNRESOLVED); never CONFLICT | no |
| 10 | every UNRESOLVED / CONFLICT | human review queue | n/a |

### 2.3 Evidence preservation (no core expansion needed)
- **Blocking disagreements:** written to `conflicts` on UNRESOLVED, REJECTED and CONFLICT
  outcomes. That also keeps the pair out of the LLM band.
- **On ACCEPTED:** core forbids `conflicts`. The disagreement is kept as a reason code
  (`<disposition>.<scheme>.overridden-by.<scheme>`) and a rule ID
  (`exact-id.override.<scheme>.by.<scheme>`). The values are recomputed from the endpoint
  records of the same run.
- **No core change.** Existing fields carry the scheme. The optional inline field is not
  proposed, per decisions-03.

### 2.4 Stricter alternative
Keep dblp-key and scopus-eid as T1-like `conflict-if-plausible`, as in er-policy.v2 today.
Then two DBLP records of one paper that share a DOI become CONFLICT, not ACCEPTED, and a DBLP
duplicate is never accepted automatically. That means more human review, but it never accepts
while record keys differ.

### 2.5 Worked examples (SYNTHETIC identifiers; Option C / version-level policy)

| Case | Recommended outcome | Stricter alternative |
|---|---|---|
| same ORCID, plausible names | ACCEPTED | same |
| different ORCIDs, plausible names | CONFLICT | same |
| different ORCIDs, unrelated names | REJECTED | same |
| different OpenAlex A, plausible, no ORCID | UNRESOLVED (no LLM) | same |
| different OpenAlex A, plausible, same ORCID | ACCEPTED (override code) | same |
| preprint vs proceedings: different dblp-keys and DOIs | CONFLICT on [dblp-key, doi] | same |
| two dblp-keys, same DOI | ACCEPTED via DOI (override code) | CONFLICT |
| two dblp-keys, no DOI, identical title/year/authors | UNRESOLVED (review) | CONFLICT |
| two OpenAlex W, same DOI | ACCEPTED via DOI | ACCEPTED via DOI |
| conference vs journal: different DOIs and W | CONFLICT on [doi] | CONFLICT on [doi] |
| different DOIs, unrelated titles | REJECTED | same |

## Part 3. Decisions requested from the human

1. Labeling target: A, B or **C (recommended)**.
2. Precedence: **narrow reading (recommended)** or the stricter alternative.
3. Per-scheme dispositions:
   - P-1 (OpenAlex W, MAG, S2 records as evidence-only);
   - P-2 (dblp-key and Scopus as block-inferred-acceptance);
   - the OpenAlex/S2 author treatment;
   - P-6 (zero the `id.other.conflict` weight).
4. P-3 (er-policy.v3; retire v2 but keep its file for the refusal test) and P-4 (wording) are
   agreed in principle. They are implemented only together with the choices above.
5. P-7 (version aggregation): deferred to Phase 6, as recorded.

## Already decided (decisions-04, recorded for completeness)
- **Blind view masks:** DBLP homonym suffixes and DBLP person IDs, including inside URLs.
- **Kept:** publication, affiliation and coauthor evidence. Original records and provenance are
  stored separately. "Unsure" is allowed. DBLP's identity assignment is never the gold label.
- **Capacity:** 240 held-out pairs, seeded policy-independent sample. Membership is frozen
  before labeling. No replacement of uncertain cases. At least 200 pairs must receive human
  review.
