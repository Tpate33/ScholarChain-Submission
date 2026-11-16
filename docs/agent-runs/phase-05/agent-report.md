# Phase 5 (main) agent reports

## ER-P5-01: entity-resolution-engineer (final report, verbatim)

Orchestrator observations after hand-back (2026-11-04): `git status --porcelain` showed only new
paths under `modules/entity-resolution/**` plus `docs/agent-runs/phase-05/prompt.md`; no modified
tracked file. `.claude/hooks/verify-tests-ran.sh --check`: OK. The orchestrator did not observe the
`sbt` output; test counts below are agent-reported.

---

## Summary

The entity-resolution subsystem is built and `sbt check` passes: 526 passed, 0 failed, 46 suites. **No gold-set evaluation was run, and no precision, recall, F1 or confusion-matrix value exists:** there are no human labels. The committed samples also cannot supply the 200 reviewable pairs. They yield 119 pairs (81 short of the minimum, 121 short of the 240 target), and several required review strata have no pairs at all. Four further things are blocked on other owners:
- the core contract cannot express a pairwise CONFLICT or the match path (domain-engineer);
- the label-file path has no owner (human, via factory-engineer);
- there is no `goldSetEval` alias (build-engineer);
- the `sbt goldSetEval` allowlist row is inactive (factory-engineer).

```text
STATUS: BLOCKED-ON
OWNER: entity-resolution-engineer
WORK ORDER: ER-P5-01 (Phase 5 main, first work order): entity-resolution subsystem, review-queue generator, gold-set evaluation tooling
```

### FILES CREATED
All under /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/.

Main resources (src/main/resources/scholarchain/entityresolution/):
- policy/er-policy.v1.json
- adjudication/er-adjudication-prompt.v1.txt
- adjudication/er-adjudication-response.v1.schema.json

Main sources (src/main/scala/scholarchain/entityresolution/):
- TitleMarkup.scala
- policy/MatchPolicy.scala, policy/Scoring.scala, policy/Resolver.scala
- adjudication/StrictJson.scala, adjudication/AdjudicationProtocol.scala, adjudication/Adjudication.scala
- identifiers/ExactIdentifiers.scala
- review/ReviewQueue.scala, review/CuratedRunReader.scala, review/ReviewQueueMain.scala
- evaluation/GoldLabels.scala, evaluation/GoldSetEvaluation.scala, evaluation/GoldSetEvalMain.scala

Tests (src/test/scala/scholarchain/entityresolution/):
- LeakageStructureSuite.scala, TitleMarkupSuite.scala
- policy/PolicyFixtures.scala, policy/MatchPolicySuite.scala, policy/ResolverSuite.scala
- adjudication/AdjudicationSuite.scala (also contains StrictJsonSuite)
- identifiers/ExactIdentifiersSuite.scala
- review/ReviewQueueSuite.scala, review/CuratedRunReaderSuite.scala, review/SampleQueueProbeSuite.scala
- evaluation/GoldSetEvaluationSuite.scala (also contains GoldLabelsSuite)

Generated, gitignored, not for commit:
- target/review-queue/er-review-queue.samples.unlabeled.jsonl (119 unlabeled rows)
- target/test-work/**

### FILES MODIFIED
none. The v2 behaviour of TextFolding, Names, BlockingKeys and MatchFeatures is untouched; `git status` shows only new paths.

### COMMANDS RUN
- `git status --short` and `git status --porcelain`: ok.
- `git ls-files -- modules/entity-resolution modules/core datasets/samples`: ok.
- `git ls-files -- modules/hadoop tests project`: ok.
- `git ls-files -- .claude`: ok.
- `git ls-files --others -- modules/hadoop/target/test-work/local-pipeline/curated-a`: ok.
- `git ls-files --others -- modules/entity-resolution/target/test-reports`: ok.
- Refused by the guard (no-allowlist-entry), nothing ran:
  - two `git grep` calls;
  - one `git ls-files --others` on two run-directory paths (reason not stated; the other `git ls-files --others` calls ran).
- `sbt entityResolution/compile`: success.
- `sbt entityResolution/test`, run 1: 155 total, 2 failed. Both were test-expectation errors, fixed:
  - a different-DOI work pair correctly gave CONFLICT;
  - the prompt template legitimately contains its own delimiter text.
- `sbt entityResolution/test`, run 2: 155 passed. Run 3, after the reader fix: 158 passed.
- `sbt check` (twice; the second is final).

### TEST RESULTS
Final `sbt check` (completed Nov 4, 2026, 10:40:22 PM), verbatim tail:
```
[info] Passed: Total 252, Failed 0, Errors 0, Passed 252
[info] Passed: Total 158, Failed 0, Errors 0, Passed 158
[info] Passed: Total 67, Failed 0, Errors 0, Passed 67
[info] Passed: Total 49, Failed 0, Errors 0, Passed 49
[info] markTestRun: wrote /Users/taufeeqpatel/Desktop/ScholarChain/.claude/.last-test-run (git_head=1bc5382c00200b76d03f6a88ffdbbac53220760e, worktree=dirty, suites=46, passed=526, skipped=0)
[success] Total time: 126 s (0:02:06.0)
```
- The earlier `sbt check` passed 523 (entityResolution 155).
- entityResolution went from 75 to 158 tests (83 new).
- I read only the tail of the 27 MB output.

### VALIDATION RESULTS

**Gold-set evaluation: NOT RUN.** No human labels exist, so no precision, recall, F1, confusion matrix, false positives or false negatives can be reported. All metric tests use invented fixtures (`SyntheticEval`, marked SYNTHETIC). They are not gold data.

**Review-queue probe** (`SampleQueueProbeSuite`, observed during `sbt entityResolution/test`). It read the Hadoop local-pipeline outputs over all committed samples, run=9c5c86b3...:

- 119 candidates (researcher 109, work 10); 119 resolved, 0 refused, 0 untraceable.

Strata:

| Stratum | Available |
|---|---|
| researcher/deterministic-bibliographic/ACCEPTED/ge-accept | 86 |
| researcher/exact-id/ACCEPTED/ge-accept | 4 |
| researcher/exact-id/ACCEPTED/llm-band | 5 |
| researcher/probabilistic/ACCEPTED/ge-accept | 12 |
| researcher/probabilistic/UNRESOLVED/llm-band | 2 |
| work/exact-id/ACCEPTED/ge-accept | 10 |

Required cells:

| Cell | Researcher | Work |
|---|---|---|
| ACCEPTED | 107 | 10 |
| REJECTED | 0 | 0 |
| UNRESOLVED | 2 | 0 |
| CONFLICT | 0 | 0 |
| LLM band | 2 | 0 |

- **Shortfall:** 119 distinct pairs against the 200 minimum (81 short) and the 240 target (121 short). REJECTED, CONFLICT and the work LLM band have no pairs. I invented no pairs.
- **Unresolved rate on the samples** (system output, not a gold metric): 2 of 119 (1.7%), both researcher pairs in the LLM band; work 0 of 10.
- **The 12 probabilistic accepts** are Hsieh, Fikes, Dean and Ghemawat on Bigtable against the three Spanner copies (DBLP, Crossref, OpenAlex). Each pair shares 3 coauthors; scores observed for three rows were 0.924, 0.924 and 0.896. Correctness is unverified until a human labels them.
- **Threshold sensitivity:** tooling only. Only 14 of the 119 sample pairs are decided by score, so no sensitivity result is reported.

### Policy, features, thresholds, versions

- **Policy version:** `er-policy.v1`, `calibrated: false`. It is bound to `er-blocking.v2` and `er-features.v2` and refuses candidates of any other version.
- **Evidence order:**
  1. Disagreeing authoritative identifiers give CONFLICT: researcher ORCID or DBLP pid, work DOI or arXiv.
  2. An exact identifier agreement gives ACCEPTED: researcher ORCID or DBLP pid; work DOI, arXiv, dblp-key, openalex-work, MAG or Scopus EID. An agreement contradicted by a same-work or homonym rule gives CONFLICT.
  3. Deterministic rules:
     - REJECTED for two positions on the same work, or for different DBLP homonym numbers;
     - ACCEPTED for the same DOI at the same position, with the same team size, a matching family key and compatible initials;
     - ACCEPTED for works with the same folded title, same year and same author sequence.
  4. Otherwise a logistic score `sigmoid(bias + sum of weight x capped value)` decides against the thresholds.
  5. LLM adjudication only for UNRESOLVED pairs whose score is inside the band.

**Feature definitions.** All are existing er-features.v2 functions, unchanged, plus one policy-level feature. "unw" means not weighted.

| Feature | Definition | Use in er-policy.v1 |
|---|---|---|
| name.family_exact | folded family keys equal | w 0.25 |
| name.family_key_agree | blocking family keys equal (vowel digraphs collapsed) | w 0.75; deterministic same-paper rule |
| name.family_sim | Levenshtein similarity of family keys | unw |
| name.given_both | both names have given parts | unw |
| name.first_initial_agree | first initials equal | w 0.25 |
| name.initials_compatible | one initials string is a prefix of the other | w 0.5; same-paper rule |
| name.first_given_agree | written-out first given names equal | w 1.0 |
| name.given_sim / name.token_jaccard | given-name similarity / name-token Jaccard | unw |
| name.best_variant_sim | best similarity over name variants | w 0.25 |
| name.homonym_suffix_both / _agree | both carry a DBLP homonym number / numbers equal | agree w 0.5; both without agree gives deterministic REJECT, or CONFLICT if an exact ID agrees |
| id.orcid.*, id.dblp-pid.* | both / agree / conflict | conflict gives CONFLICT; agreement gives exact ACCEPT |
| id.openalex-author.* | both / agree / conflict | agree w +2.0, conflict w -2.5 (not treated as authoritative) |
| context.same_work | two mentions on the same work | deterministic REJECT, or CONFLICT if an exact ID agrees |
| context.claimed_work | an ORCID profile claims the other side's work | w 3.0 |
| context.same_source / context.work_both | same dataset / both are mentions | unw |
| coauthor.shared / coauthor.jaccard / coauthor.both | shared coauthor keys / their Jaccard / both have coauthors | w 0.75 (cap 4) / w 3.0 / unw |
| affiliation.id_jaccard / text_jaccard | institution-ID Jaccard / raw affiliation-token Jaccard | w 1.5 / w 0.5 (id_shared and *_both unw) |
| venue.id_agree / venue.name_jaccard | same venue ID / venue-name Jaccard | w 0.25 / w 0.25 |
| work.title_jaccard, work.doi_agree, work.position_agree, work.team_size_agree | context of the two mentions' works | w 1.5, 1.5, 0.25, 0.25; the last three also feed the same-paper rule |
| year.abs_diff | absolute year difference | w -0.05 (cap 30) |
| policy.source_reliability (policy feature) | min(rel A, rel B) - 0.8, with dblp .95, orcid .90, crossref .85, openalex .75 | w 1.0 |
| (researcher bias) | | -3.0 |
| id.doi.*, id.arxiv.* (work) | both / agree / conflict | conflict gives CONFLICT; agreement gives exact ACCEPT |
| id.other.agree / id.other.conflict | counts over dblp-key, openalex-work, MAG, Scopus EID | agree via exact path / w -1.5 (cap 2) |
| title.exact, title.sim, title.token_jaccard | folded title equal / Levenshtein / token Jaccard | w 2.0 each; title.exact also feeds the deterministic rule |
| year.abs_diff (work) | absolute year difference | w -0.5 (cap 10); 0 required by the deterministic rule |
| venue.id_agree, venue.name_jaccard (work) | as for researchers | w 0.5 each |
| authors.family_jaccard, first_family_agree, sequence_lcs_ratio | author family-key overlap / same first author / LCS ratio | w 2.0, 0.75, 1.0 |
| authors.count_diff, authors.orcid_shared | author-count difference / shared ORCIDs | w -0.2 (cap 10), 0.5 (cap 3) |
| authors.sequence_exact | equal author family sequence | deterministic rule |
| (work bias) | | -4.0 |

- Citation-neighbourhood evidence is not available: no citation dataset is selected.

**Thresholds** (hand-set, **uncalibrated until gold labels exist**):
- accept 0.85, reject 0.20, LLM band [0.40, 0.70]; exact-ID confidence 0.99; deterministic confidence 0.95.
- Rationale:
  - a perfect name match with nothing else scores about 0.54, inside the band, so a name never accepts on its own;
  - 3 shared coauthors push a pair to about 0.9;
  - the band sits strictly inside (reject, accept), so scores between the band and either threshold stay UNRESOLVED for human review.
- The core config `entityResolution.acceptThreshold` and `rejectThreshold` override the thresholds. Its `policyVersion` must match, and the band must still fit.

**Versions:**
- prompt `er-adjudication-prompt.v1`;
- response schema `er-adjudication-response.v1` (strict: accept/reject/abstain, evidence limited to the candidate's own identifiers, closed reason-code list, no free text);
- request `er-adjudication-request.v1`.
- Model ID configuration key: `models.llmModelId` (env `SCHOLARCHAIN_CFG_MODELS__LLM_MODEL_ID`). It is never written in code or the policy file.
- LLM cache key: (input hash, prompt version, model version, policy version).

**Proposed label path and format:**
- Path: `gold/entity-resolution/er-gold-labels.v1.jsonl`, needing a new matrix row `gold/**` owned by HUMAN.
- Format, one JSON object per line: `{"schemaVersion":"er-gold-label.v1","rowId":"rq:<hex64>","candidateId":"mcand:<hex64>","entityKind":"researcher|work","sourceIdA":"...","sourceIdB":"...","label":"match|non-match|unsure","labeler":"<token>","labeledAt":"<ISO instant Z>","notes":null}`.
- `GoldLabels.ingest` refuses the whole file if any line is unlabeled, uses an unknown label, is a duplicate row or pair, has an unknown key, a bad timestamp or no labeler, or does not trace to a queue row.
- Proposed unlabeled queue path (my territory, not created): `modules/entity-resolution/review-queue/er-review-queue.v1.unlabeled.jsonl`, written by `ReviewQueueMain`.

**goldSetEval alias** (build-engineer adds; I did not edit build.sbt):
```
addCommandAlias("goldSetEval", "entityResolution/runMain scholarchain.entityresolution.evaluation.GoldSetEvalMain modules/entity-resolution/review-queue/er-review-queue.v1.unlabeled.jsonl gold/entity-resolution/er-gold-labels.v1.jsonl 10")
```
- It also needs:
  - `entityResolution / run / fork := true`, because the entry point calls `sys.exit`;
  - `entityResolution / run / baseDirectory := (ThisBuild / baseDirectory).value`, so the relative paths resolve from the repository root.
- Optional analogous alias `reviewQueue` for `scholarchain.entityresolution.review.ReviewQueueMain <curated run dir> <out.jsonl> 240 10 er-p5`.

### ASSUMPTIONS
- `confidence` means the probability that the stated outcome is correct:
  - exact-ID 0.99, deterministic 0.95;
  - score-path ACCEPTED = p, REJECTED = 1 - p, UNRESOLVED = 0;
  - an LLM accept or reject keeps the pre-LLM p (or 1 - p for a reject).
- Mapping onto core `MatchDecision`:
  - source = sourceIdA, target = sourceIdB;
  - `provenance.sourceRecordId` = the candidate ID;
  - `sourceSchemaVersion` = `scholarchain.match-candidate.v1`;
  - the match path is carried as the rule-ID prefix (`MatchPath.ofRules`).
- One accept/reject pair serves both entity kinds, as the core config shape implies.
- The model version is the configured model ID string.
- The 200-pair minimum counts judged labels, excluding "unsure".

### RISKS
- **Uncalibrated.** Weights and thresholds are hand-set.
- **CONFLICT covers obviously distinct pairs too.** Following law 2 literally, any disagreeing ORCID, DBLP pid, DOI or arXiv ID gives CONFLICT even when nothing else agrees (two different CACM papers with different DOIs do). This adds human review load and needs a match-policy decision.
- **Policy choice:** OpenAlex author IDs are treated as non-authoritative.
- **Cross-module coupling.** The probe reads `modules/hadoop/target/test-work` as data. During a parallel `sbt check` it can skip or read an older run; it asserts invariants only. test-engineer could own it instead.
- **Anchoring.** Queue rows show the system decision; a blind labeling view is recommended.
- **Sample metrics.** Stratified-sample metrics are not population estimates (no reweighting).
- **LLM path is inert.** No concrete adjudicator client exists.
- **Prompt location.** The prompt and schema live in my module, because they are match-policy and cache-key inputs. The human may prefer `prompts/**` (rag-engineer); I did not write there.
- **Unwired code:**
  - TitleMarkup is not used by the v2 features; wiring it needs `er-features.v3` and `er-blocking.v3` coordinated with hadoop-engineer;
  - the Semantic Scholar and repository-URL rules have no core scheme and no source dataset, so they cannot affect decisions.
- **Inherited Unicode limitations still apply.** NIT-1, Cf-joining and the JDK Cf tables are unchanged. NIT-2 means a zero-width-space before "0001" loses the homonym number, so the homonym rule cannot fire and the pair falls to the score. The policy reads only ER features, never `normalizedName`.
- **Bespoke parser.** StrictJson is hand-written and covered by tests only.
- **Dirty-tree marker.** The test marker was written on a dirty tree.

### MANUAL ACTION REQUIRED
Human:
- ratify the label path and the `gold/**` HUMAN row (via factory-engineer);
- decide the CONFLICT-without-agreement policy;
- decide OpenAlex author-ID authority;
- decide the prompt location;
- define a blind labeling procedure;
- label the queue once the shortfall is fixed.

build-engineer: add the alias above. factory-engineer: activate `sbt goldSetEval` (B10).

### BLOCKED-ON OWNER
domain-engineer; dataset-engineer; human (via factory-engineer); build-engineer.

### BLOCKED-ON EVIDENCE
**domain-engineer: `MatchDecision` cannot express:**
- (a) `matchPath`;
- (b) `candidateId`;
- (c) `blockingVersion` and `featureVersion`;
- (d) `score`, separate from confidence;
- (e) pairwise CONFLICT, since `conflictingTargetIds` means "other targets". Needed: `conflicts: Vector[{scheme or rule, valuesA, valuesB}]`, or ratified pairwise semantics;
- (f) an adjudication reference (inputHash, decision, evidence, reasonCodes).

Also needed: `MatchCandidate.exactIdConflicts`, which hadoop-engineer would populate under a feature version bump; config keys `entityResolution.llmBandLower`, `llmBandUpper` and `promptVersion`; and optionally Semantic Scholar and repository-URL schemes. `Resolver.toMatchDecision` refuses CONFLICT rather than invent an encoding.

**dataset-engineer:** the committed samples yield 119 pairs (needed ≥200), with 0 REJECTED, 0 CONFLICT and 0 work band pairs. More diverse samples are needed: homonyms, same-name different people, DOI disagreements, preprint versus version of record.

**human:** the gold-label path has no owner (G9).

**build-engineer:** the `goldSetEval` alias is missing.

### NEXT GATE
1. code-reviewer review, including the no-leakage check. `LeakageStructureSuite` enforces it structurally: matching code never references review or evaluation; only evaluation names gold-label types.
2. The blocked owners resolve their items.
3. Generate the queue and the human labels it.
4. Run `sbt goldSetEval` and the match-policy review.

---

## DE-P5-01: domain-engineer (final report, verbatim)

## DE-P5-01 summary

**Status: BLOCKED-ON entity-resolution-engineer.** The core contract work is finished and `sbt core/test` passes (292 passed, 0 failed), but `sbt check` fails. The compile breaks at one entity-resolution call site, which is the expected result of the `match-decision.v2` field change. I edited only `modules/core/**`.

**Downstream impact.** I added a temporary shim in core, used it to compile and run every module, then removed it. With the shim in place:
- every module compiled;
- every hadoop and `tests/**` suite passed, including the accepted Unicode suites (FormatCharactersSuite, UnicodeSuite, FormatCharacterPipelineSuite);
- the only failures were the two entity-resolution `toMatchDecision` tests, plus four entity-resolution test sites that construct the config with the old 3-argument shape.

The adding of the new `ExactIdScheme` cases broke no exhaustive match anywhere under `-Werror`. hadoop-engineer has no new obligations.

```text
STATUS: BLOCKED-ON
OWNER: domain-engineer
WORK ORDER: DE-P5-01 (Phase 5 main): MatchDecision v2, entity-resolution config keys, Semantic Scholar and repository-URL schemes, decision on MatchCandidate.exactIdConflicts
```

### FILES CREATED
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/main/scala/scholarchain/core/MatchDecision.scala (MatchDecision v2, IdentifierConflict, AdjudicationReference; moved out of Identity.scala)
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/test/scala/scholarchain/core/MatchDecisionV2Suite.scala

### FILES MODIFIED
All under /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/.

Main sources (main/scala/scholarchain/core/):
- Contract.scala
- Enums.scala
- Identifiers.scala
- Identity.scala (MatchDecision removed from it)
- SourceIdentity.scala (ExactId errors redact repository-URL credentials)
- config/ConfigLoader.scala
- config/ScholarChainConfig.scala

Tests (test/scala/scholarchain/core/):
- EnumCodecSuite.scala
- Fixtures.scala
- IdentifiersSuite.scala
- SchemaVersionSuite.scala
- StableIdSuite.scala
- ValidationSuite.scala
- WireContractSuite.scala
- config/ConfigLoaderSuite.scala

### COMMANDS RUN
- `git ls-files` (several), `git ls-files --others` (test reports and entity-resolution tree), `git status --porcelain`: all ok.
- Refused by the guard, nothing ran: one `git grep`, and one oversized command.
- `sbt core/compile`: success.
- `sbt Test/compile`, first run: failed (core test fixtures and Resolver.scala).
- `sbt core/test`, five runs: placeholder-golden and StableId failures, then fixed; final 292 passed.
- With the temporary discovery shim:
  - `sbt Test/compile`: success;
  - `sbt test`: failures only in ResolverSuite and AdjudicationSuite, from test reports;
  - `sbt Test/compile` with the config shim removed: 4 errors, listed below.
- Shim fully removed, then:
  - `sbt check`: FAILED;
  - `sbt core/test`: 292 passed.

### TEST RESULTS
Final `sbt core/test`, verbatim tail:
```
[info] Passed: Total 292, Failed 0, Errors 0, Passed 292
[success] Total time: 1 s, completed Nov 4, 2026, 11:13:45 PM
```
- Core went from 252 tests to 292.

Final `sbt check`, verbatim:
- Earlier steps passed:
  - `Build sources OK`;
  - `Version ledger OK: 14 pinned entries`;
  - scalafmt checks `[success]`.
- Then the compile failed. Verbatim tail:
```
[error] -- [E007] Type Mismatch Error: /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/policy/Resolver.scala:364:14
[error] 364 |            o.entityKind,
[error]     |          Found:    (o.entityKind : scholarchain.core.MatchEntityKind)
[error]     |          Required: String
... (same call at 365, 367, 368, 371, 372, 374)
[error] 7 errors found
[error] (entityResolution / Compile / compileIncremental) Compilation failed
[error] Total time: 6 s, completed Nov 4, 2026, 11:13:35 PM
```
- markTestRun did not run, so the test marker was not updated. The Stop/SubagentStop hook may therefore refuse completion until entity-resolution is fixed.

### VALIDATION RESULTS

**Schema versions**
- MatchDecision: `scholarchain.match-decision.v1` becomes `scholarchain.match-decision.v2`. v1 is retired (`SchemaVersions.retired`) and rejected on decode, under the existing policy: no v1 decision was ever accepted into a curated output.
- Config: `scholarchain.config.v1` becomes `scholarchain.config.v2`. v1 is in `ConfigLoader.RetiredSchemaVersions` and is rejected.
- MatchCandidate stays `scholarchain.match-candidate.v1`.
- No other schema changed, and no other frozen example changed. AuthorshipRecord's golden is preserved by pinning its decision ID as a literal.

**MatchDecision v2 fields, in wire order, with validation rules**
1. `schemaVersion`: exactly v2.
2. `decisionId`: `MatchDecisionId.of(entityKind, sourceId, targetId, provenance.policyVersion, provenance.runId)`. The formula and hash tag `scholarchain.match-decision.v1` are unchanged.
3. `candidateId`: a valid `mcand:` ID, and equal to `provenance.sourceRecordId`.
4. `entityKind`: `researcher` or `work` only.
5. `sourceId` (side A) and `targetId` (side B):
   - endpoint kinds as in MatchCandidate (mention or source-author for researcher; work for work);
   - canonical order `sourceId < targetId` by UTF-8 bytes; a self-pair is rejected.
6. `matchPath`: closed enum `MatchDecisionPath`: `exact-id`, `deterministic-bibliographic`, `probabilistic`, `llm-adjudicated`.
7. `decision`: `MatchOutcome`: ACCEPTED, REJECTED, UNRESOLVED, CONFLICT.
8. `confidence`: in [0,1].
9. `score`: `Option[Double]`, finite and in [0,1]. Required on `probabilistic` and `llm-adjudicated`; optional on the other two paths. Independent of confidence.
10. `blockingVersion`, `featureVersion`: tokens; blank is rejected.
11. `features`: token keys, finite values.
12. `rulesFired`, `reasonCodes`: non-empty, tokens, distinct.
13. `conflicts`: `Vector[IdentifierConflict]`, sorted by (scheme tag, kind tag, rule) without duplicates.
    - CONFLICT requires at least one conflict.
    - ACCEPTED forbids any conflict, so an identifier conflict always prevents automatic acceptance (human decision 1).
    - `agreement-contradicted` entries are allowed only on CONFLICT.
    - CONFLICT on `llm-adjudicated` is rejected.
14. `adjudication`: `Option[AdjudicationReference]`, present exactly on `llm-adjudicated`; refused on every other path.
15. `decidedAt`: ISO-8601 instant in UTC.
16. `provenance`:
    - `sourceDataset = scholarchain-derived`;
    - `sourceSchemaVersion` starts with `scholarchain.match-candidate.v`;
    - `policyVersion` is a required token;
    - `modelVersion` and `promptVersion` are set only when an adjudication is present, and must equal its versions.

**IdentifierConflict fields:** `kind`, `scheme`, `valuesA`, `valuesB`, `contradictingRule`.
- `kind = identifier-disagreement`: both value sets are non-empty, canonical for the scheme, sorted, and disjoint; `contradictingRule` is null.
- `kind = agreement-contradicted`: `valuesA == valuesB` (the agreeing values), and `contradictingRule` is a required token.
- In both kinds the scheme must identify the decision's entity kind.
- Helpers:
  - `IdentifierConflict.disagreement(scheme, idsA, idsB)` uses the same rule as the `id.<scheme>.conflict` feature;
  - `contradictedAgreement(...)`;
  - `swapSides`;
  - `canonical`.

**AdjudicationReference fields**
- `inputHash`: 64 lowercase hex.
- `promptVersion`: token.
- `modelVersion`: printable ASCII.
- `policyVersion`: token, equal to `provenance.policyVersion`.
- `decision`: `AdjudicationVerdict`, one of `accept`, `reject`, `abstain`. These must match ACCEPTED, REJECTED, UNRESOLVED respectively.
- `evidenceIds`: sorted and distinct, non-empty for accept and reject. Each must be one of:
  - the candidate ID, or either endpoint ID;
  - `feature:<a key of features>`;
  - `exact-id:<scheme of this entity kind>:<canonical value>`.
- `reasonCodes`: sorted, distinct tokens.

**Builder.** `MatchDecision.build(...)` orders the pair canonically, exchanging the conflict sides with it. It also sorts conflicts and the adjudication lists, computes the ID, and validates.

**Frozen goldens added:**
- the exact-id ACCEPTED decision;
- a CONFLICT decision with a DOI disagreement;
- an LLM-adjudicated REJECTED decision;
- a work MatchCandidate.

**`conflictingTargetIds`: removed.** It meant "other targets in conflict", which is a cluster-level property, not a property of one pair. Renaming it would have implied the old meaning. It is replaced by the pairwise `conflicts` field, which has a different type and meaning. A v2 JSON that carries this key is rejected (tested).

**Config keys** (none has a default):
- `entityResolution.llmBandLower`, env `SCHOLARCHAIN_CFG_ENTITY_RESOLUTION__LLM_BAND_LOWER`;
- `entityResolution.llmBandUpper`, env `..._LLM_BAND_UPPER`;
- `entityResolution.promptVersion` (token), env `..._PROMPT_VERSION`.

Rules: every number finite and in [0,1], and `reject < bandLower < bandUpper < accept`, strictly. `EntityResolutionConfig.problems(c)` exposes the same rules for configurations built in code.

**New exact identifier schemes and canonical forms**
- `s2-corpus` (work):
  - canonical form: decimal digits, no leading zero, at most 18 digits;
  - accepted inputs: bare digits, or `CorpusId:<n>` in any case.
- `s2-paper` (work):
  - canonical form: 40 lowercase hex characters;
  - accepted inputs: bare in any case, or `https://(www.)semanticscholar.org/paper/[slug/]<id>`.
- `s2-author` (researcher):
  - canonical form: digits;
  - accepted inputs: bare, or a `/author/[slug/]<id>` URL.
  - Kept distinct from `s2-corpus`.
- `repository-url` (work):
  - canonical form: `https://<host>/<seg>/<seg>[/...]`;
  - rewriting:
    - the input scheme may be https, http, ssh, git+https, git+http, git+ssh, or scp-like `user@host:path`;
    - the host is lowercased and one `www.` removed;
    - user information is dropped and redacted in errors;
    - the default port and the query and fragment are dropped;
    - trailing slashes and `.git` are removed;
  - refused:
    - non-default ports;
    - empty, `.` or `..` segments;
    - characters outside `[A-Za-z0-9._~-]`;
    - fewer than two segments;
    - hosts that are not dotted DNS names;
  - path case is preserved, except on `github.com`, which must be exactly `<owner>/<repo>` and is lowercased;
  - deep links are never shortened.
- Scaladoc records what each scheme identifies, that a valid format is not proof of existence, and that a repository agreement is evidence of a shared artifact, not proof of the same work.
- **No observed form exists** for any of these four schemes: `datasets/schemas/identifiers.md` lists none, and no selected dataset carries them. The formats follow public Semantic Scholar and git URL conventions, and the code says so.

**MatchCandidate.exactIdConflicts: not added. MatchCandidate is unchanged.**
- The candidate already exposes `id.<scheme>.both/agree/conflict` flags for every authoritative scheme.
- The disagreeing values can be rebuilt deterministically from the endpoint records in the same curated run. entity-resolution's own `MatchFeatures.researcherExactIds` and `workExactIds` (the functions that produced the flags) plus core `IdentifierConflict.disagreement` do this.
- Agreeing values (for `agreement-contradicted`) are already on the candidate as `exactIdAgreements`.
- Result: no hadoop work and no candidate version bump.

### ASSUMPTIONS
- `policyVersion` is carried only in `provenance.policyVersion`, as in v1, with no duplicate top-level field.
- A refused or failed LLM call is not an adjudication.
- One decision per (pair, policy, run): the pre-LLM and post-LLM decisions of one pair in one run share a decision ID.
- No committed HOCON config file exists outside test strings. I could not search for `*.conf` files: the guard forbids globs and `git grep`.

### RISKS
- **entity-resolution's repository-URL normalizer differs from core and can falsely merge.** `ExactIdentifiers.scala` keeps only the first two path segments on gitlab.com. So `gitlab.com/group/sub/repoA` and `gitlab.com/group/sub/repoB` both become `https://gitlab.com/group/sub`. entity-resolution should adopt core's normalizer.
- **AuthorshipRecord semantics.** `AuthorshipRecord.matchDecisionId` assumes a mention-to-researcher decision. v2 decisions are pairwise candidate decisions, so how Phase 6 (spark) cites decisions for researcher clusters is unresolved.
- **Resolution cost.** Building disagreement values requires joining candidates with endpoint records at resolution time. If that join is too costly at EMR scale, the fallback is a MatchCandidate v2 with an `exactIdConflicts` field. That would oblige hadoop-engineer to populate it in CandidateJob under a candidate and feature version bump, with new goldens.
- **Unverified identifier forms.** The Semantic Scholar and repository-URL formats are unobserved and their bounds (for example 18 digits) are representational, not observed.
- **Leftover files.** A temporary file I created, `modules/core/target/golden-dump.txt`, is in a gitignored build directory; I cannot delete it with the allowed commands. A stale `TmpDumpSuite.xml` report predates my run.

### MANUAL ACTION REQUIRED
- Human: none for core.
- Optional: delete `/Users/taufeeqpatel/Desktop/ScholarChain/modules/core/target/golden-dump.txt`.

### BLOCKED-ON OWNER
entity-resolution-engineer (ER-P5-02)

### BLOCKED-ON EVIDENCE
Broken call sites:

1. **`modules/entity-resolution/src/main/scala/scholarchain/entityresolution/policy/Resolver.scala:361-375`** (positional `MatchDecision(...)` construction; compile errors at 364, 365, 367, 368, 371, 372, 374).
   - Replace it with `MatchDecision.build(...)`:
     - `candidateId = o.candidateId`;
     - `matchPath = MatchDecisionPath.fromTag(o.path.tag)`;
     - `score = Some(o.score.probability)` (required on the probabilistic and LLM paths);
     - `blockingVersion` and `featureVersion` from the outcome.
   - Also remove the CONFLICT refusal at 336-343, and fill `conflicts`:
     - `IdentifierConflict.disagreement` from the endpoints' identifier sets;
     - `contradictedAgreement(scheme, agreeing values, Rules.ExactVsSameWork or Rules.ExactVsHomonym)`.
   - An ACCEPTED decision must carry no conflicts.
2. **`modules/entity-resolution/src/main/scala/scholarchain/entityresolution/adjudication/Adjudication.scala:129-137`**. Refused responses currently set the `LlmAdjudicated` path with no verdict. Under v2 they must be emitted as UNRESOLVED on the probabilistic path, with no adjudication reference and no model or prompt in provenance. A valid trace maps to `AdjudicationReference`.
3. **Config constructor calls** (now 6 arguments, no defaults):
   - `.../src/test/scala/scholarchain/entityresolution/policy/MatchPolicySuite.scala:89`, `:95`, `:100`;
   - `.../policy/ResolverSuite.scala:161`.
   - `MatchPolicy.withConfig` (`MatchPolicy.scala:270-283`) should also apply the band and check `promptVersion`; it can call `EntityResolutionConfig.problems`.
4. **Tests that change with the contract:**
   - `ResolverSuite.scala:191-208`: the "refuses CONFLICT" test must become a CONFLICT round-trip;
   - `AdjudicationSuite.scala:89`.
5. **Optional:** use the core `s2-*` and `repository-url` schemes in place of `identifiers/ExactIdentifiers.scala`'s own `IdentifierKind`; the `s2-corpus` and `s2-paper` tags already match.

No hadoop-engineer or test-engineer obligations: all their suites passed against the new core.

### NEXT GATE
1. entity-resolution-engineer completes ER-P5-02, then `sbt check` passes and the marker updates.
2. code-reviewer reviews this core diff, including the conflict and LLM invariants.
3. test-engineer runs the adversarial pass.

---

## ER-P5-02: entity-resolution-engineer (final report, verbatim)

Orchestrator notes (2026-11-05):
- The first ER-P5-02 run was terminated by an API usage limit while a background `sbt check` was
  running. The orchestrator resumed the same agent.
- Its background `sbt check` then wrote `.claude/.last-test-run` between audited Bash calls. The
  Stop hook (`verify-tests-ran.sh`) refused orchestrator completion ("possible test-marker
  forgery"), and `audit-bash-writes.sh --pre` refused the agent's foreground re-run (UNATTRIBUTED).
- The 604-test result below therefore comes from an unaudited background run. It is agent-reported
  and is NOT accepted as evidence until a fresh audited foreground `sbt check` passes after human
  review of the marker change.
- The orchestrator made no Bash call between the refusal and this archive.

---

ER-P5-02 handback (entity-resolution-engineer). STATUS: BLOCKED-ON human. The work order is implemented and tested; only the confirming re-run of `sbt check` was refused by the audit hook.

WHY IT STOPPED
- My earlier BACKGROUND `sbt check` (started 23:38 local, exit 0) wrote `.claude/.last-test-run` via markTestRun after its Bash call had returned.
- On the next Bash call, `audit-bash-writes.sh --pre` saw that marker change and refused the call (UNATTRIBUTED). It refused the coordinator-requested re-run before anything ran.
- The hook says stop, so I did not retry. It reset its baseline, so after the human checks `git status` / `git diff`, `sbt check` can be re-run as-is.
- Lesson for the hooks: agents should never run `sbt check` in the background; the marker write lands between audited calls.

OBSERVED RESULT OF THE BACKGROUND RUN (last 25 lines of its output file, read with Read, not Bash; verbatim)
```
[info] Passed: Total 49, Failed 0, Errors 0, Passed 49
[info] markTestRun: core: production sources=17, test sources=17, passed=292
[info] markTestRun: entityResolution: production sources=22, test sources=19, passed=196
[info] markTestRun: hadoop: production sources=18, test sources=8, passed=67
[info] markTestRun: tests: production sources=0, test sources=8, passed=49
[info] markTestRun: wrote /Users/taufeeqpatel/Desktop/ScholarChain/.claude/.last-test-run (git_head=1bc5382c00200b76d03f6a88ffdbbac53220760e, worktree=dirty, suites=49, passed=604, skipped=0)
[success] Total time: 126 s (0:02:06.0), completed Nov 4, 2026, 11:40:17 PM
[exited with code 0]
```
- Earlier lines of that run showed `Build sources OK`, `Version ledger OK: 14 pinned entries`, and the scalafmt checks passing.
- The marker now reads `suites=49 tests_passed=604 tests_failed=0`. No source changed after that run started.
- Separately, foreground `sbt entityResolution/test`: `Passed: Total 196, Failed 0, Errors 0, Passed 196`.
- Not verified: whether this run's Hadoop and entity-resolution probe suites overlapped (known parallel-run risk). The test results show no problem.

STATUS: BLOCKED-ON
OWNER: entity-resolution-engineer
WORK ORDER: ER-P5-02 (Phase 5 main): contract integration with match-decision.v2 / config.v2; human decisions 1-4; OA-1 plan

FILES CREATED (all under /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/)
- main/resources/scholarchain/entityresolution/policy/er-policy.v2.json
- main/scala/scholarchain/entityresolution/policy/Plausibility.scala
- main/scala/scholarchain/entityresolution/policy/EndpointIdentifiers.scala
- main/scala/scholarchain/entityresolution/review/BlindView.scala
- test/scala/scholarchain/entityresolution/policy/ConflictPolicySuite.scala
- test/scala/scholarchain/entityresolution/review/BlindViewSuite.scala

FILES MODIFIED (ER-P5-01 files, still untracked; under the same src/ root)
- Main:
  - policy/MatchPolicy.scala
  - policy/Resolver.scala
  - adjudication/Adjudication.scala
  - identifiers/ExactIdentifiers.scala (own IdentifierKind removed; now uses core ExactIdScheme and Identifiers.normalizeExact)
  - review/ReviewQueue.scala
  - review/ReviewQueueMain.scala
- Tests:
  - policy/PolicyFixtures.scala
  - policy/ResolverSuite.scala
  - policy/MatchPolicySuite.scala
  - adjudication/AdjudicationSuite.scala
  - identifiers/ExactIdentifiersSuite.scala
  - review/ReviewQueueSuite.scala
  - review/CuratedRunReaderSuite.scala
  - review/SampleQueueProbeSuite.scala
- Unchanged: er-policy.v1.json stays as a retired resource (a test proves it is refused by name). The adjudication prompt and response schema are unchanged at v1 (content not changed, so no version bump).
- No tracked file and nothing outside modules/entity-resolution/** was changed. docs/agent-runs/** was not touched.

COMMANDS RUN
- git status --porcelain (x2), git ls-files (tracked and --others), git diff --stat, git show --stat HEAD: all OK.
- sbt entityResolution/compile: success.
- sbt entityResolution/test: 196 passed, 0 failed.
- sbt check (background): exit 0, 604 passed (tail above).
- sbt check (re-run): REFUSED by audit-bash-writes.sh --pre (UNATTRIBUTED change to the test marker). Nothing ran.

TEST RESULTS
- core 292, entityResolution 196 (was 158), hadoop 67, tests 49. Total 604 passed, 0 failed, 0 errors, 0 skipped, 49 suites.
- ConflictPolicySuite (24 tests) covers:
  - plausible pair + disagreeing authoritative ID;
  - the same with another ID agreeing;
  - implausible pair + disagreeing ID;
  - implausible pair + disagreeing ID with another ID agreeing;
  - edges of the plausibility test: family similarity 0.85 vs 0.8499 and 1.0 without key agreement; title similarity 0.85 vs 0.8499; author LCS 0.75 vs 0.7499; title Jaccard 0.5 vs 0.4999; missing given names; homonym numbers; two authors on one paper;
  - both entity kinds; OpenAlex A vs W; Semantic Scholar paper vs author; repository URL; arXiv versions; every scheme has exactly one rule.
- BlindViewSuite (8 tests), including a leakage checker and a test proving the checker fires when a hidden field or value is injected.
- Contract tests:
  - a CONFLICT decision round-trips through JSON (replaces the old "refuses CONFLICT" test);
  - a refused LLM response produces no adjudication reference and no model or prompt provenance;
  - a valid response maps to AdjudicationReference.
- Config: band and prompt version applied from EntityResolutionConfig and validated through EntityResolutionConfig.problems.
- Regression: the gitlab subgroup test fails under the old normalizer (old behaviour: both URLs became https://gitlab.com/group/sub).

VALIDATION RESULTS
- Gold-set evaluation was NOT run: no human labels exist, so there is no precision, recall, F1, confusion matrix, false positive or false negative to report. All metric tests use invented fixtures.
- Policy version: er-policy.v2 (file schema er-policy-file.v2, calibrated=false), bound to er-blocking.v2 and er-features.v2.

Plausibility definitions (non-identifier features only; documented with a rationale in the policy file and in the Plausibility.scala Scaladoc). The test leans toward "plausible", because a plausible pair goes to a manual CONFLICT decision while an implausible one is rejected automatically.
- Researcher: plausible only if all four hold:
  1. name.family_key_agree=1, or 0.85 <= name.family_sim < 1;
  2. name.initials_compatible=1, or name.given_both=0 (allowed by missingGivenNamesCompatible=true);
  3. context.same_work=0;
  4. not (both DBLP homonym numbers present and different).
- Work: plausible if either clause holds:
  - T: title.exact=1, or 0.85 <= title.sim < 1;
  - TA: authors.both=1, authors.sequence_lcs_ratio >= 0.75 and title.token_jaccard >= 0.5.
  - The "< 1" guards exclude two empty keys or empty titles. Years are not used (a preprint and its version of record can be years apart). An author team alone is not plausible.

Conflict outcomes (decision 1)
- Authoritative disagreement: CONFLICT if the pair is plausible OR any identifier agrees. Never ACCEPTED, by any path.
- Not plausible and no identifier agrees: REJECTED, rule exact-id.reject.disagreement-implausible.<scheme>, confidence 0.95. The disagreement stays recorded in `conflicts`.
- Why REJECTED: the disagreement is a second, independent sign of two entities; leaving these pairs to the score would mostly add UNRESOLVED noise.
- Why "any identifier agrees" gives CONFLICT even on an implausible pair: the identifiers contradict each other.

Per-scheme identifier rules (decision 2). Scope / reliability / agreement / disagreement. "Disagreement on an implausible pair" is REJECTED for every scheme except repository-url.
- orcid: one person / person-asserted, check digit / ACCEPT 0.99 / conflict-if-plausible.
- dblp-pid: one DBLP person page / curated, homonyms split by hand / ACCEPT 0.99 / conflict-if-plausible.
- openalex-author (A): one OpenAlex author cluster / algorithmic; mostly splits one person, sometimes merges people / ACCEPT 0.90 / block-acceptance-if-plausible: never CONFLICT on its own; a plausible pair is capped at UNRESOLVED and never sent to the LLM; an agreeing ORCID or DBLP pid overrides it (recorded as a reason code).
- s2-author: one Semantic Scholar author record / algorithmic; inert today / ACCEPT 0.90 / block-acceptance-if-plausible.
- doi: one registered object (preprint and version of record are distinct) / registered / ACCEPT 0.99 / conflict-if-plausible.
- arxiv: one submission, all versions / assigned by arXiv; vN compared as one value / ACCEPT 0.99 / conflict-if-plausible.
- dblp-key: one DBLP record / curated / ACCEPT 0.99 / conflict-if-plausible.
- openalex-work (W): one OpenAlex work record / algorithmic merge of source copies; known duplicates / ACCEPT 0.95 / conflict-if-plausible.
- mag: one MAG record / legacy, algorithmic / ACCEPT 0.95 / conflict-if-plausible.
- scopus-eid: one Scopus record / publisher-curated / ACCEPT 0.99 / conflict-if-plausible.
- s2-corpus and s2-paper: one Semantic Scholar paper record; never compared with each other / inert today / ACCEPT 0.95 / conflict-if-plausible.
- repository-url: one repository / a shared artifact is not the same work / evidence only (reason code) / evidence only (never CONFLICT).

FLAGGED FOR HUMAN AND REVIEWER CHECK AGAINST THE README (weaker than ORCID and DBLP pid)
- OpenAlex author (A) disagreements do not produce CONFLICT. Rationale: automated author disambiguation mostly fails by splitting one person, so a split A ID is not evidence of two people. Two A IDs still block automatic acceptance of a plausible pair.
- OpenAlex A agreements decide ACCEPTED with confidence 0.90, below ORCID and DBLP pid.
- The exact rules required for OpenAlex A and W exist and are tested.

Thresholds and band
- accept 0.85, reject 0.20, LLM band [0.40, 0.70]; deterministic confidence 0.95; conflict confidence 0.99.
- All weights are unchanged from v1; a test pins these values.
- New: per-scheme agreement confidences and the implausible-disagreement confidence (0.95). They come from decisions 1 and 2, not from data inspection or labels.
- withConfig now applies llmBandLower, llmBandUpper and promptVersion (a mismatch refuses the run) and validates through EntityResolutionConfig.problems.

Blind view (decision 4), schema er-blind-label-view.v1
- Visible fields:
  - row: rowId, entityKind, question, candidateId, sourceIdA, sourceIdB, labelOptions [match, non-match, unsure];
  - endpoint: endpointId, endpointKind, sourceDataset, sourceRecordId, snapshot, nameAsWritten, structuredName, nameVariants, authorPosition, affiliations, institutionIds, identifiers, claimedWorkIds;
  - work: workId, title, year, venue, authorCount, authors (coauthor list, up to 50), identifiers.
- Hidden fields: score, decision/outcome, confidence, path/matchPath, rulesFired, features, reasonCodes, conflicts, plausible, stratum, band, llmBandEligible, modelVersion, promptVersion, system, exactIdAgreements.
- Row order: SHA-256 of (er-blind-view-order.v1, seed, rowId), so it does not reveal the stratum.
- Row ID: hash of the candidate ID and policy version, so it does not encode the decision (tested: the ID stays the same when the decision changes).
- Labels join the internal queue (er-review-queue-row.v2) by row ID only at evaluation time.
- ReviewQueueMain now writes both artifacts: `<run> <internal.jsonl> <blind.jsonl> [target] [min] [seed]`. It refuses to overwrite either file and refuses equal paths.

Stratum counts from the probe (run 9c5c86b3, sbt entityResolution/test)
- 119 resolved, 0 refused, 0 untraceable. The new endpoint-identifier consistency check holds on all real Hadoop candidates.
- Strata identical to v1:
  - researcher/deterministic-bibliographic/ACCEPTED/ge-accept 86
  - researcher/exact-id/ACCEPTED/ge-accept 4
  - researcher/exact-id/ACCEPTED/llm-band 5
  - researcher/probabilistic/ACCEPTED/ge-accept 12
  - researcher/probabilistic/UNRESOLVED/llm-band 2
  - work/exact-id/ACCEPTED/ge-accept 10
- REJECTED, CONFLICT and the work LLM band still have no pairs: the samples contain no identifier disagreements and no OpenAlex-vs-OpenAlex pairs.
- Shortfall: 81 below the 200 minimum, 121 below the 240 target. No final queue was generated.

OA-1 PLAN (LLM adjudication stays interface-only)
- Client host: a concrete Adjudicator (Bedrock) belongs in a runtime module, not entity-resolution. rag-engineer would own it behind a port in modules/rag. Any AWS client, IAM role, model access and Secrets Manager setup goes through infra-engineer and Terraform; build-engineer adds the SDK under a version-ledger entry. Entity resolution keeps only the trait, the protocol, the validator and the cache.
- Sending band pairs with recorded inputs:
  1. A batch job selects outcomes where Adjudication.eligible holds (UNRESOLVED, probabilistic path, in the band, no conflicts).
  2. It builds AdjudicationProtocol.request and writes the canonical request JSON and its inputHash to S3 before any call.
  3. It calls the port with the configured models.llmModelId.
- Caching and replay:
  - A persistent AdjudicationCache (storage-engineer adapter, e.g. DynamoDB or S3) keyed by (inputHash, prompt version, model version, policy version).
  - It stores only validated responses. Refusals are logged but never cached and never become a decision; refused pairs may be retried.
  - Replay uses a cache-only adjudicator that fails on a cache miss, so re-evaluation is deterministic and makes no calls.
- Metrics:
  - Valid accept, reject or abstain responses give llm-adjudicated decisions with an AdjudicationReference. GoldSetEvaluation already segments by path.
  - Abstentions count as UNRESOLVED in the llm-adjudicated row (lower coverage, visible in the unresolved rate).
  - Refusals remain probabilistic UNRESOLVED with rule probabilistic.llm-response-refused, so they are countable.
  - Gold labels are joined by row ID only at evaluation time.
- Approval and cost: before any paid call, the human approves an experiment manifest (experiment-engineer, /experiment) setting model ID, prompt version, maximum pairs (the band count), maximum tokens per call, a total cost ceiling and the Region. No call without it.

ASSUMPTIONS
- The authoritative/algorithmic split, the plausibility bounds and the confidences come from decisions 1 and 2, not from data.
- Endpoint identifiers are rebuilt from the same curated run's records, as DE-P5-01 proposed. A candidate whose features those identifiers do not reproduce is refused.
- candidateId, sourceIdA and sourceIdB in the blind view are opaque hashes and record IDs, not system output.
- An algorithmic agreement plus an algorithmic disagreement gives CONFLICT (contradictory identifiers). Inert today.

RISKS
- The policy is uncalibrated.
- Making dblp-key, openalex-work, DOI and the other record keys authoritative turns plausible same-source duplicates and preprint-vs-version-of-record pairs into CONFLICTs, adding manual workload at full scale.
- OpenAlex A treatment is weaker than ORCID and DBLP pid; needs human and reviewer sign-off against the README.
- arXiv version folding is applied only in the policy; the candidate's id.arxiv.conflict flag can still be 1 for versions of the same ID.
- The stratified selection itself shows which strata were over-sampled; the internal queue must never be given to labelers.
- The retired er-policy.v1.json is still in the module, kept so a test can prove it is refused.
- The probe still reads Hadoop's target/ directory, which can overlap with the Hadoop suite during parallel runs.

MANUAL ACTION REQUIRED
- Human:
  - review git status / git diff for the marker change (cause: my background sbt check's markTestRun, exit 0, marker suites=49 passed=604);
  - then re-run `sbt check` in a fresh agent call (or have me do it);
  - decide on the OpenAlex A treatment and the CONFLICT workload trade-off;
  - optionally delete er-policy.v1.json.
- Still pending from ER-P5-01: the gold/** label path (factory-engineer), and the goldSetEval alias and allowlist row (build-engineer and factory-engineer). The proposed reviewQueue alias now takes the blind-view path as a third argument.

BLOCKED-ON OWNER: human

BLOCKED-ON EVIDENCE: audit-bash-writes.sh --pre: "INTER-CALL CHANGE detected ... (UNATTRIBUTED) ... .claude/.last-test-run (owner: GENERATED) ... This Bash call was refused." The change is consistent with my background `sbt check` writing the marker at written_at_utc=2026-11-05T05:40:17Z. Incident log: /var/folders/zt/pbpscx0s72b8jch7_wr7gc9m0000gn/T/scholarchain-bash-audit-501/session-31ac882037fa1279f7fc3022ee76cd91.incidents.log

NEXT GATE
1. Human reviews the marker change; `sbt check` is re-run in the foreground.
2. dataset-engineer DS-P5-01 provides more diverse samples.
3. code-reviewer RV-P5-01 (including the blind-view leakage check and the OpenAlex A rationale), then test-engineer TE-P5-01.
4. Generate the real queue and blind view; the human labels them blindly; then `sbt goldSetEval`.

---

## DS-P5-01: dataset-engineer (final report, verbatim)

Orchestrator observations after hand-back (2026-11-05):
- `git status --porcelain`: new or modified paths only under `datasets/**`, plus the earlier known
  Phase 5 paths; nothing else.
- `.claude/hooks/verify-tests-ran.sh --check`: OK.
- `sbt check` was not run by the agent. No test count exists for this work order.
- The `--sample` fetch was moved to the background by the harness after 180 s. Its later writes
  were reported UNATTRIBUTED by `audit-bash-writes.sh --pre`, which refused the agent's next call
  (see recovery-02.md).

---

## Summary

The work is real and source-traceable, but it is not finished: the heldout target is not met yet, and the run is blocked on the human.

- **What exists.** 70 selection units across 11 criteria are fixed, with a deterministic dev/heldout split written before any fetch. 171 byte-exact API responses are stored (623,126 bytes): 132 OpenAlex works, 20 OpenAlex authors, 19 Crossref works. Each has its exact URL, UTC time, HTTP status, sha256 and licence.
- **What is missing.** All 120 dblp records failed. dblp.org and the dblp.uni-trier.de mirror answered the script with HTTP 202 (bot challenge). Without them, heldout yields an estimated ~130 pairs at best, below the 200 minimum. I added an offline fallback that copies exact byte ranges from the pinned dblp release. It needs your 1.03 GB human-only download and has not been run.
- **Why I stopped.** The `--sample` fetch ran longer than 180 s, and the harness moved it to the background; I did not request that. Its later writes were then flagged by `audit-bash-writes.sh --pre`, which refused my next Bash call and said to stop. So `--verify`, `validate-manifest.sh --verify` and `sbt check` were **not run**.
- **No pairs observed.** The Phase 4 pipeline does not read these new directories, so no candidate pairs or ER stratum counts exist yet.

```text
STATUS: BLOCKED-ON
OWNER: dataset-engineer
WORK ORDER: DS-P5-01 (Phase 5 main) – real, licensed, source-traceable records for >=200 heldout ER review pairs
```

**FILES CREATED** (all under /Users/taufeeqpatel/Desktop/ScholarChain/)
- datasets/er-candidates/selection.tsv – the fixed record list: unit, criterion, source, kind, identifier, and the dblp selection facts (expected DOI and author string).
- datasets/er-candidates/split-assignment.tsv – written by the script before any request.
- datasets/scripts/fetch-er-candidates.sh – no executable bit. It is invoked through fetch-samples.sh.
- datasets/schemas/er-candidates.md – exact byte shape of each file kind, for hadoop-engineer.
- datasets/samples/er-candidates/PROVENANCE.tsv – per-record provenance: 171 rows.
- datasets/samples/er-candidates/FETCH-FAILURES.tsv – 121 rows: 120 dblp HTTP 202, plus Crossref 10.23919/cinc49843.2019.9005722 ("expected exactly one Crossref item").
- 171 record files under datasets/samples/er-candidates/{dev,heldout}/{openalex-works,openalex-authors,crossref-works}/

**FILES MODIFIED**
- datasets/dataset-manifest.yaml – new top-level `er_candidates` section: criteria, split rule, licences, privacy, limitations, unverified items.
- datasets/scripts/dsutil.py
  - New `er-fetch` and `er-verify` subcommands.
  - `validate` now runs the ER checks and excludes the er-candidates subtree from SHA256SUMS and the 300 KiB limit.
  - After the run started I added the dblp release-excerpt fallback and the stop-after-HTTP-202 logic. The ledger was produced by the earlier in-memory version of this file.
- datasets/scripts/fetch-samples.sh – new optional second argument `datasets/er-candidates`, which runs fetch-er-candidates.sh through bash.

**COMMANDS RUN**
- `git status --short`: ok.
- `git ls-files -- datasets`: ok.
- `git ls-files -- modules/hadoop modules/entity-resolution tests project`: ok.
- `git ls-files -- modules/entity-resolution/src/test/scala/scholarchain/entityresolution/review`: ok, no output.
- `git diff --stat HEAD -- project datasets`: ok, no output.
- `git ls-files -s -- datasets/scripts`: refused by the guard (no allowlist entry); nothing ran.
- `datasets/scripts/fetch-samples.sh --dry-run datasets/er-candidates`: ran after human approval. It parsed 70 units with no errors and printed every planned request.
- `datasets/scripts/fetch-samples.sh --sample datasets/er-candidates`: the harness moved it to the background after 180 s. It exited with code 1, and its final line was `fetched 171, already present 0, not fetched 121, bytes in ledger 623126`.
- `datasets/scripts/fetch-samples.sh --verify datasets/er-candidates`: refused by `audit-bash-writes.sh --pre` with "INTER-CALL CHANGE ... (UNATTRIBUTED)", listing the ledger, the failures file and the new record files. The hook said "Do: stop". I made no further Bash calls.
- Source checks were done with WebFetch, read-only: dblp SPARQL (about 14 queries; three returned 429), the Crossref API, the OpenAlex API, and the licence and terms pages.

**TEST RESULTS:** `sbt check` was not run (refused by the audit stop rule). Test counts: none. ER probe stratum counts: none.

**VALIDATION RESULTS**
- `validate-manifest.sh --verify` and `er-verify` were not run.
- The fetch-time content checks did pass for every stored file:
  - each OpenAlex body has a `W` or `A` ID and no abstract;
  - each Crossref body has exactly one item whose DOI equals the request, and no abstract.
- Licences were re-verified on 2026-11-05:
  - dblp: CC0 1.0, secondary ODC-BY 1.0, covering the XML, JSON and RDF exports (https://dblp.org/xml/README.txt).
  - OpenAlex: CC0, "Our data is CC0" (https://openalex.org/about).
  - Crossref: "almost none of the metadata is subject to copyright, and you may use it for any purpose"; abstracts may be copyrighted and were not requested (https://www.crossref.org/documentation/retrieve-metadata/rest-api/).
  - No ORCID record was fetched. A byte-exact personal-details response can carry a biography, which the Phase 3 minimisation rule forbids.
- OpenAlex keyless use is allowed (help.openalex.org, updated 2026-11-03). Its per-request cost and keyless budget are recorded as UNVERIFIED.

**Selection criteria.** Each was chosen by a documented query; no policy was consulted. Rules are in the manifest under `er_candidates.criteria`.

| Criterion | Units (heldout/dev) | Stored | Not fetched |
|---|---|---|---|
| homonym-rare (names with exactly 2 dblp persons, SHA-ordered) | 7 (5/2) | 14 OA works | 14 dblp |
| homonym-east-asian (Wei Wang, Lei Zhang, Yang Liu, Jing Li) | 4 (3/1) | 8 OA | 8 dblp |
| homonym-western (T. Müller, M. Müller, D. Brown*, D. Smith*, P. Schneider*; *includes a dblp "(disambiguation)" record) | 5 (3/2) | 10 OA | 10 dblp |
| name-variant (dblp persons with 2 name forms) | 9 (6/3) | 18 OA | 18 dblp |
| orcid-vs-none (signature with ORCID vs without) | 8 (5/3) | 16 OA | 16 dblp |
| preprint-vs-vor (CoRR vs non-CoRR, identical title) | 8 (5/3) | 16 OA; in 7 of 8 units both DOIs return one byte-identical OpenAlex work | 16 dblp |
| retraction (Crossref update-type:retraction) | 3 (2/1) | 6 OA + 6 Crossref | 0 |
| same-title (Editorial / Guest Editorial / Introduction / Preface, 2020) | 4 (3/1) | 8 OA | 8 dblp |
| multi-doi (dblp record with 2 DOIs) | 6 (4/2) | 12 OA (2 units merged by OpenAlex) + 5 Crossref | 6 dblp, 1 Crossref |
| conf-vs-journal (identical title) | 8 (5/3) | 16 OA (all distinct works) | 16 dblp |
| ordinary (2022, 8 general CS journals) | 8 (5/3) | 8 OA + 8 Crossref + 20 OA authors | 8 dblp |

OpenAlex author split/merge is not a targeted criterion; it is visible incidentally. Records stored per split: heldout 114 (87 OA works, 13 Crossref, 14 OA authors); dev 57 (45, 6, 6).

**Dev/heldout rule:** key = sha256("scholarchain-er-split.v1|" + unit_id); within each criterion, the first ceil(3n/5) by key are heldout. Result: 46 heldout units and 24 dev units. The rule reads unit IDs only, and the file was written before the first request.

**Candidate-pair estimates.** None of these are observed; all assume er-blocking.v2, the local test value maxBlockSize=4, one pipeline run per split, and no name collisions.

| Scenario | Heldout | Dev |
|---|---|---|
| Records as stored, with a Crossref envelope reader | ~130 | ~77 |
| Records as stored, OpenAlex only | ~70 | ~45 |
| After the dblp release excerpts are added | ~600 (optimistic) | ~300 |

So ≥200 heldout is reachable only with the dblp records.

**ASSUMPTIONS**
- Identifiers were read through a summarising web-fetch tool. Every stored body was checked against its identifier, so a transcription error shows up as a failure row, never as a wrong record.
- A plain selection filter is not policy tuning. Unit sizes were kept small for blocking capacity only.

**RISKS**
- **Harness auto-background.** Any Bash call over 180 s is moved to the background, which triggers the audit's UNATTRIBUTED refusal. Long fetches should run in a human terminal.
- **Format mismatches downstream.** Crossref files keep the API envelope. DBLP release excerpts carry the release encoding and DOCTYPE.
- **dblp release cutoff.** Some records date from 2025-2026 and may be missing from dblp-2026-10-03 (for example journals/tjs/CamaraCB26, journals/comcom/RioMSJC26, journals/theoretics/PintoH26).
- **Selection reproducibility.** Selection used the live dblp SPARQL data and Crossref's relevance ordering, not the pinned snapshots.
- **Untested code.** The dblp extraction path and er-verify have never run.

**MANUAL ACTION REQUIRED (human)**
1. Review `git status` / `git diff` for the audit-reported paths (all are mine under datasets/**). The baseline is already reset.
2. Decide whether to download dblp: `SCHOLARCHAIN_CONFIRM_FULL_DOWNLOAD=dblp-2026-10-03 datasets/scripts/fetch-dblp.sh --full` (about 1.03 GB).
3. Then, in a terminal:
   - `datasets/scripts/fetch-samples.sh --sample datasets/er-candidates` (resumes and extracts the dblp records);
   - `datasets/scripts/fetch-samples.sh --verify datasets/er-candidates`;
   - `datasets/scripts/validate-manifest.sh --verify`;
   - `sbt check` in the foreground.
4. When committing, `chmod +x datasets/scripts/fetch-er-candidates.sh`.

**BLOCKED-ON OWNER:** human; hadoop-engineer; entity-resolution-engineer.

**BLOCKED-ON EVIDENCE**
- **human:** `audit-bash-writes.sh --pre` refusal ("This Bash call was refused ... Do: stop"); incident log /var/folders/zt/pbpscx0s72b8jch7_wr7gc9m0000gn/T/scholarchain-bash-audit-501/session-31ac882037fa1279f7fc3022ee76cd91.incidents.log. The dblp download is human-only under the manifest.
- **hadoop-engineer:** add bounded local pipeline runs per split over datasets/samples/er-candidates/<split>/{dblp,openalex-works,openalex-authors,crossref-works}/, separate from LocalPipelineSuite's fixed counts. Add a Crossref reader for `message.items[0]`. Handle per-file DBLP encoding and DOCTYPE. Choose maxBlockSize for the gold run. Expect DUPLICATE counts from byte-identical OpenAlex files.
- **entity-resolution-engineer:** SampleQueueProbeSuite reads only local-pipeline/curated-a. It needs a probe or ReviewQueueMain over the heldout run, and must keep policy development on dev only.

**NEXT GATE:** human review of the audit change and the dblp download decision, then the re-run plus verify, validate-manifest and `sbt check`, then hadoop-engineer wiring with observed per-split pair counts, then a code-reviewer review of datasets/**.

Full per-record provenance is in /Users/taufeeqpatel/Desktop/ScholarChain/datasets/samples/er-candidates/PROVENANCE.tsv, and the failures are in /Users/taufeeqpatel/Desktop/ScholarChain/datasets/samples/er-candidates/FETCH-FAILURES.tsv.

---

## ER-P5-03: entity-resolution-engineer (final report, verbatim; read-only work order)

Orchestrator observation after hand-back (2026-11-05): `git status --porcelain` was unchanged,
apart from the orchestrator's own `recovery-02.md`. No files were created or modified by this
work order.

---

ER-P5-03: identifier-policy decision table (read-only)

I created and modified no files and did not run sbt. This work order changes no behaviour, so it carries no test obligation.

Wording rule used throughout: 0.99, 0.95 and 0.90 are each a **policy confidence constant (uncalibrated)**. The probabilistic-path value p is also an uncalibrated logistic output of hand-set weights (`er-policy.v2.json:6` `"calibrated": false`). None of these is measured confidence. One Scaladoc line overclaims and should be reworded: `Resolver.scala:61-64` calls `confidence` an "estimated probability that the stated outcome is correct".

## 1. Identity layers

| Scheme | Layer | Justification |
|---|---|---|
| orcid | Researcher | Registered by one person and asserted for that person. |
| dblp-pid | Researcher (ambiguous) | A DBLP person page, where curators split homonyms by hand. Two mentions of one person share a pid, so a disagreement is DBLP's assertion of two people. Ambiguity: it is also the record key of a DBLP person profile. |
| openalex-author (A) | Researcher (ambiguous) | An algorithmic author cluster. Ambiguity: two OpenAlex author-*profile* endpoints always have different A IDs by construction, so for profile pairs the disagreement is only a record-key difference. |
| s2-author | Researcher (ambiguous) | Algorithmic author record, same caveat as A. Inert: no selected dataset carries it. |
| doi | Work/version | Registers one deposited object. A preprint DOI (for example an arXiv DOI) and the version-of-record DOI are different versions of one work. |
| arxiv | Work/version | One arXiv submission across all its vN revisions (`ExactIdentifiers.scala:89-97` folds vN). It identifies the preprint version line, not the version of record. |
| **Preprint vs version of record** | n/a | **Different versions of the same work.** Today the `work` entity is at version level (`er-policy.v2.json:102`). Whether Phase 6 counts should collapse versions is an open question (P-7). |
| repository-url | Work/version (ambiguous) | Core kind is `work`, but it identifies a code artifact, not a work. One repository can accompany many works. |
| dblp-key | Source record | One DBLP publication record. Two distinct DBLP records always differ by construction. |
| openalex-work (W) | Source record | One OpenAlex record. OpenAlex is known to leave duplicate W IDs. |
| mag | Source record | One legacy MAG record (via OpenAlex `ids.mag`). |
| scopus-eid | Source record | One Scopus record (via ORCID external IDs). |
| s2-corpus, s2-paper | Source record | One Semantic Scholar record in two encodings, never compared with each other. Inert. |

**Key point:** two records from the same source always carry different keys of that source, so a source-record disagreement restates "these are two records", which is the premise of every candidate. It is not evidence of two identities.

## 2. Per-scheme rules (current er-policy.v2 vs proposed er-policy.v3)

Code locations for "today":
- AD (authoritative) branch: `Resolver.scala:340-358`. It gives CONFLICT when the pair is plausible or any identifier agrees, otherwise REJECTED (`rejectImplausible`, `:325-338`).
- GD (algorithmic) branch: `:359-400`.
- Agreement leading to ACCEPTED: `:416-425`.
- Evidence-only handling: `:255`, `:262-264`, `:278-283`.
- Plausibility test: `Plausibility.scala:59-87`.

| Scheme | Agreement means | Disagreement means | Today (er-policy.v2) | Proposed (v3) |
|---|---|---|---|---|
| orcid | same person | two registered iDs: different people, or a rare duplicate iD or wrong attachment | agree: ACCEPTED, constant 0.99 (json:38-45). Disagree: CONFLICT if plausible, else REJECTED with constant 0.95 (json:10) | unchanged |
| dblp-pid | DBLP says same person | DBLP says different people | as orcid (json:46-53) | unchanged |
| openalex-author | same cluster | possibly a split cluster, so not proof | agree: ACCEPTED, constant 0.90. Disagree on a plausible pair: an automatic ACCEPTED is capped at UNRESOLVED, no LLM, never CONFLICT. Implausible pair: REJECTED (json:54-61; Resolver:359-400) | unchanged |
| s2-author | as A | as A | as A (json:62-69) | unchanged |
| doi | same version | different versions, or a wrong DOI | agree: ACCEPTED, constant 0.99. Disagree: CONFLICT if plausible, else REJECTED (json:100-107) | unchanged. It is a version identifier, not a record key, so README 1221 conflict detection applies. |
| arxiv | same preprint line | different submissions | as doi (json:108-115) | unchanged |
| repository-url | shared artifact | nothing | evidence-only both ways, never decides (json:164-171) | unchanged |
| dblp-key | same DBLP record, so same item | two DBLP records: often preprint plus venue version, sometimes a duplicate | agree: ACCEPTED, constant 0.99. Disagree: CONFLICT if plausible, else REJECTED (json:116-123) | **Block automatic acceptance (new disposition `block-acceptance`).** Never CONFLICT and never REJECTED on its own. A deterministic or score ACCEPTED becomes UNRESOLVED and goes to review, with no LLM call. Any accepting identifier agreement (for example DOI) still accepts. Recorded as a reason code, not in `conflicts`. |
| scopus-eid | same Scopus record | two Scopus records (duplicates exist) | as dblp-key (json:140-147) | same as dblp-key |
| openalex-work | same OpenAlex record | two OpenAlex records (known duplicates) | agree: ACCEPTED, constant 0.95. Disagree: CONFLICT if plausible, else REJECTED (json:124-131) | **Evidence only on disagreement.** Agreement still ACCEPTED, constant 0.95. |
| mag | same MAG record | two MAG records | as W (json:132-139) | evidence only on disagreement |
| s2-corpus, s2-paper | same S2 record | two S2 records | as W (json:148-163) | evidence only on disagreement |

Core already supports this without change. `MatchDecision.scala:503-511` says CONFLICT needs at least one conflict and ACCEPTED must have none. Record-key disagreements would be kept as reason codes and rules only (as repository-url is today), never in `conflicts`.

## 3. Worked examples (all identifiers and names are SYNTHETIC placeholders, not gold data)

| # | Pair | Today (v2) | Proposed (v3) |
|---|---|---|---|
| 1 | "Jordan Q. Synthwell" vs "J. Q. Synthwell", both ORCID-S1 | ACCEPTED, exact-id, `exact-id.agree.orcid`, constant 0.99 | same |
| 2 | same names, ORCID-S1 vs ORCID-S2 | CONFLICT, exact-id; conflicts=[orcid] | same |
| 3 | "J. Synthwell" vs "M. Quorvane", ORCID-S1 vs ORCID-S2 (implausible) | REJECTED, `exact-id.reject.disagreement-implausible.orcid`, constant 0.95 | same |
| 4a | plausible names with strong coauthor overlap, A-S1 vs A-S2, no ORCID | the score would accept, so UNRESOLVED (`probabilistic.accept-blocked-by-id-disagreement`); no LLM; conflicts=[openalex-author] | same |
| 4b | as 4a, plus ORCID-S1 on both sides | ACCEPTED via orcid, constant 0.99; reason `algorithmic-id-disagreement-overridden-by-authoritative-agreement.openalex-author`; conflicts empty | same |
| 5a | DBLP preprint (DK-S1, arXiv DOI-S1) vs DBLP proceedings (DK-S2, DOI-S2), same title | CONFLICT; conflicts=[dblp-key, doi] | CONFLICT; conflicts=[doi]; dblp-key kept as a reason code |
| 5b | as 5a, but the preprint has an arXiv ID only and no DOI | CONFLICT (dblp-key alone, plausible) | no CONFLICT. Would-be ACCEPTED becomes UNRESOLVED for review; otherwise the score outcome stands. |
| 6 | conference vs journal version, DOI-S3 vs DOI-S4, W-S3 vs W-S4, same title | CONFLICT; conflicts=[doi, openalex-work] | CONFLICT; conflicts=[doi] |
| 7a | two DBLP records of one paper (DK-S5 vs DK-S6), both DOI-S5 | CONFLICT (dblp-key disagreement plus DOI agreement = identifier contradiction) | ACCEPTED via doi, constant 0.99; dblp-key disagreement kept as a reason code |
| 7b | as 7a, no DOIs, identical title, year and author sequence | CONFLICT | UNRESOLVED (deterministic accept blocked), goes to review |
| 8a | W-S7 vs W-S8, both DOI-S7 | CONFLICT (contradiction) | ACCEPTED via doi, constant 0.99; reason `evidence-only-id-disagreement.openalex-work` |
| 8b | W-S7 vs W-S8, no DOI, same title | CONFLICT | decided by the deterministic rule (ACCEPTED, constant 0.95, if title, year and authors are exact) or by the score |
| 9 | DOI-S9 vs DOI-S10, unrelated titles | REJECTED, `exact-id.reject.disagreement-implausible.doi`, constant 0.95 | same |
| 10 | same REPO-S1, different titles | the identifier never decides; reason `evidence-only-id-agreement.repository-url`; outcome comes from the score (not computed) | same; inert on current data |

## 4. README check (README.md:1216: "Exact identifier rules for DOI, arXiv ID, ORCID, OpenAlex ID, Semantic Scholar ID, and repository URL.")

| README identifier | Explicit exact rule kept in v3? |
|---|---|
| DOI | Yes, unchanged. Agreement accepts; disagreement gives CONFLICT or REJECTED. |
| arXiv ID | Yes, unchanged. |
| ORCID | Yes, unchanged. |
| OpenAlex ID | Yes, for both A and W. A is unchanged. W agreement still decides ACCEPTED; only W disagreement becomes evidence-only. |
| Semantic Scholar ID | Yes. s2-author is unchanged. s2-corpus and s2-paper agreement still accepts; disagreement becomes evidence-only. |
| repository URL | Yes, an explicit evidence-only rule (unchanged). Flag for the human: this rule never decides. |

Related gate lines:
- README:1221: "Conflict detection when authoritative identifiers disagree." Still met for orcid, dblp-pid, doi and arxiv.
- README:1242: "Exact ID conflicts do not silently merge." Examples 7a and 8a become ACCEPTED while two record keys differ. They are not silent: the difference is recorded in `rulesFired` and `reasonCodes`. They are also not identity conflicts under the proposal. The reviewer must agree with this reading.

## 5. Changes needed if approved (approve or reject each)

| ID | Change | Files |
|---|---|---|
| P-1 | `onDisagreement: evidence-only` for openalex-work, mag, s2-corpus, s2-paper. No Scala change: `Resolver.scala:255` already handles this value. | new `.../policy/er-policy.v3.json` |
| P-2 | New disposition `block-acceptance` for dblp-key and scopus-eid. It does not trigger the plausibility test or REJECTED or CONFLICT. It caps a deterministic, probabilistic or LLM ACCEPTED at UNRESOLVED (`<path>.accept-blocked-by-record-key-disagreement`) and makes the pair ineligible for the LLM. An accepting identifier agreement overrides it (with a reason code). It never appears in `conflicts`. | `MatchPolicy.scala` (`IdentifierRule` values and helpers, `DisagreementValues`), `Resolver.scala` (new branch and `Rules`/`Reason` entries, evidence-order Scaladoc). `Adjudication.scala` eligibility to be confirmed (not read). |
| P-3 | Bump `policyVersion` to `er-policy.v3`; point `DefaultResource` at v3. Decision needed: keep v2 loadable or retire it. The file schema can stay `er-policy-file.v2` (same shape). | `MatchPolicy.scala:251`, json |
| P-4 | Wording: "policy confidence constant (uncalibrated)" in the Scaladoc and the policy text. Optional core doc tweak at `MatchDecision.scala:312` (domain-engineer). | `Resolver.scala:61-64`, `MatchPolicy.scala:93-94, 208-213` |
| P-5 | No change to core, Hadoop, features (`er-features.v2`) or blocking (`er-blocking.v2`). | none |
| P-6 (optional, separate) | Set the `id.other.conflict` weight from -1.5 to 0. It penalises every same-source pair because the disagreement is automatic. It cannot be split by scheme without `er-features.v3` (hadoop-engineer). | json:186 |
| P-7 (open question, no code) | Do preprint and version of record collapse into one work for Phase 6 counts? That would need a version-of relation in core (domain-engineer) and spark-engineer. Out of scope for v3. | none |

Tests that would change:
- `ConflictPolicySuite.scala:304-313` (a plausible W disagreement is CONFLICT now; would no longer be).
- `ConflictPolicySuite.scala:315-322` (asserts W is `conflict-if-plausible`).
- `ConflictPolicySuite.scala:335-353` (an s2-corpus disagreement is CONFLICT now).
- `MatchPolicySuite` (default resource and version, the pinned rule values, the new disposition accepted by `validate`).
- Fixtures that name `er-policy.v2` (`PolicyFixtures`, and the config in `MatchPolicySuite` and `ResolverSuite`). I could not search the tree with the allowed tools, so the full list is confirmed at implementation.

Tests that would be added:
- examples 5a, 5b, 6, 7a, 7b, 8a and 8b;
- "a record-key disagreement never yields CONFLICT, never yields REJECTED on its own, and never appears in `conflicts`";
- "a dblp-key block is overridden by a DOI agreement";
- a v3 round-trip through `toMatchDecision`.

The `ConflictPolicySuite.scala:397-410` invariant (an ACCEPTED decision never carries conflicts) still holds.

```text
STATUS: COMPLETE
OWNER: entity-resolution-engineer
WORK ORDER: ER-P5-03 (Phase 5 main): read-only identifier-policy decision table for the human
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: git ls-files --others --exclude-standard -- modules/entity-resolution modules/core (ok); git diff --stat -- modules/core/src/main/scala/scholarchain/core/Identifiers.scala (ok); git ls-files -- modules/entity-resolution/src/main (ok); one grep refused by guard-dangerous.sh (shell-syntax-gate), nothing executed. Files were read with Read.
TEST RESULTS: not applicable: read-only. No sbt run, no behaviour change.
VALIDATION RESULTS: no gold-set evaluation (no human labels exist). Outcomes in section 3 were traced by reading Resolver.scala and er-policy.v2.json, not by execution. Where an outcome depends on the score (4a, 5b, 7b, 8b, 10), the result is stated conditionally because the score was not computed.
ASSUMPTIONS: The DBLP preprint record carries an arXiv DOI (10.48550 prefix) in example 5a, and 5b covers the case where it does not. Two endpoints from the same source always differ on that source's own record key (WorkRecord.dedicatedIds; not re-read in this order). Adjudication eligibility follows llmBandEligible (Adjudication.scala not read).
RISKS: (1) P-1 and P-2 allow ACCEPTED decisions while record keys differ; README:1242 compliance depends on reading record keys as non-identity evidence, which the reviewer must confirm. (2) Under P-1 the disagreeing record-key values are not stored in the decision; they can be recovered from the endpoint records only. (3) Example 7b and similar pairs raise the UNRESOLVED and review load. The size of that increase is unknown: current samples contain 0 such pairs and DS-P5-01 data is incomplete. (4) Review-queue row IDs hash the policy version, so a queue built under v2 is invalid for v3. Build the queue only after this decision. (5) P-7 (work vs version) remains open and affects Phase 6 counts. (6) The policy remains uncalibrated.
MANUAL ACTION REQUIRED: Human: approve or reject P-1, P-2, P-3 (including keep or retire v2), P-4 and the optional P-6; decide P-7 or defer it; confirm the README reading in section 4.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Human decision on P-1 to P-7, then an implementation work order for er-policy.v3 (entity-resolution-engineer) with sbt check, then code-reviewer, then queue generation and blind labeling.
```

---

## ER-P5-04: entity-resolution-engineer (final report, verbatim; read-only work order, no Bash)

ER-P5-04: read-only decision specification (precedence, work-labeling target, queue IDs)

I created and modified no files. I made no Bash calls, ran no sbt and used only Read; Grep and Glob were not in my tool set. Nothing changes behaviour, so there is no test obligation. 0.99, 0.95 and 0.90 below are each a **policy confidence constant (uncalibrated)**.

Short version:
- **7a contradiction:** I resolve it by narrowing what the block means: a record-key or algorithmic-author disagreement blocks only "inferred" acceptance (rules, score, LLM), never acceptance by a DOI, arXiv, ORCID or DBLP pid agreement. 7a stays ACCEPTED, and every overridden disagreement leaves a reason code.
- **Labeling target:** I recommend two questions per work pair (same version, and same underlying work). You decide.
- **Queue IDs:** today the queue picks rows by policy outcome and keys labels to the policy version, so a policy change would reselect examples and orphan labels. I propose a fixed held-out member list keyed by the endpoint pair.

# Deliverable 1: one precedence, one meaning of "block"

## 1.1 Decision (I chose the "narrow" reading)
"Block acceptance" means **block inferred acceptance only**.
- "Inferred acceptance" is an ACCEPTED on the deterministic-bibliographic, probabilistic or llm-adjudicated path.
- An identifier acceptance (exact-id path) is overridden only by an agreement of a **strictly higher tier**.

Two disposition names make this precise (it needs er-policy.v3; P-3 is agreed in principle):

| Disposition (v3 name) | Schemes (proposed, not yet approved) | Meaning |
|---|---|---|
| `conflict-if-plausible` (unchanged) | orcid, dblp-pid (researcher, tier T1); doi, arxiv (work/version, T1) | Nothing overrides it. CONFLICT if the pair is plausible or any identifier agrees; otherwise REJECTED. |
| `block-inferred-acceptance-if-plausible` (renamed from `block-acceptance-if-plausible`; same behaviour) | openalex-author, s2-author (T2) | Plausible pair: blocks inferred acceptance. Implausible pair: REJECTED. A T1 agreement overrides it; a T2 agreement contradicts it (CONFLICT). |
| `block-inferred-acceptance` (P-2, renamed) | dblp-key, scopus-eid (T2, curated record keys) | Blocks inferred acceptance. It never triggers the plausibility test and never REJECTs on its own. A T1 agreement overrides it; a T2 agreement contradicts it (CONFLICT). |
| `evidence-only` (P-1) | openalex-work, mag, s2-corpus, s2-paper (T2, algorithmic record keys); repository-url (T3) | Reason code only. It never blocks, never decides and never enters `conflicts`. |

Why I chose this:
- A record key names a catalog record, not an identity. Two DBLP records always differ in key by construction.
- When their version identifier (DOI) agrees, the identity evidence at the policy's level (version) agrees.
- Law 1 puts exact identifiers above bibliographic and probabilistic evidence, so a lower-tier record-level signal must not veto a higher-tier identifier.

The alternative, "7a must not accept", is also consistent. It means keeping `dblp-key: conflict-if-plausible` (today's v2). Then 7a is CONFLICT and a DBLP duplicate can never be accepted automatically. You can still choose that instead.

**Changed from ER-P5-03:** a `block-*` disagreement is now written to `conflicts` (kind `identifier-disagreement`) whenever the outcome is UNRESOLVED, REJECTED or CONFLICT. Core allows this (MatchDecision.scala:327-330, 503-522). It is left out only on ACCEPTED, where core forbids it (:506-511).
- This keeps the values whenever core allows.
- It makes the pair ineligible for the LLM through the existing check (Adjudication.scala:57-60, `o.conflicts.isEmpty`).
- It matches what openalex-author does today (Resolver.scala:380-399).

## 1.2 Ordered precedence (applied to every pair)
Abbreviations:
- **T1D / T1A**: T1 disagreement / agreement.
- **BD**: a disagreement under either `block-*` disposition.
- **T2A**: an agreement of a T2 scheme whose rule accepts on agreement.
- **P**: plausibility (Plausibility.scala), computed only when a T1 or BD disagreement exists, except that dblp-key and scopus-eid disagreements never trigger it.
- **SW / HD**: two positions on one work / different DBLP homonym numbers.

| Step | Input condition | Outcome (path, confidence) | Can later steps change it? |
|---|---|---|---|
| 0 | Invalid candidate, version mismatch, or endpoint identifiers inconsistent | Refused; no decision | n/a |
| 1 | Classify every scheme as agree / disagree / absent. repository-url and other `evidence-only` schemes add reason codes | none (bookkeeping) | It never decides |
| 2 | T1D present (orcid, dblp-pid / doi, arxiv) | P or any agreement of any tier: **CONFLICT** (exact-id, 0.99). Otherwise **REJECTED** (exact-id, 0.95). `conflicts` = T1D + BD (+ contradicted agreements) | **No** (final; no LLM) |
| 3 | No T1D, no T1A, BD present and a T2A present (same-tier contradiction) | **CONFLICT** (exact-id, 0.99), rule `exact-id.conflict.identifier-contradiction`; `conflicts` = BD + agreement-contradicted | **No** |
| 4 | No T1A; an algorithmic-author BD (`...-if-plausible`) and not P | **REJECTED** (exact-id, 0.95), `exact-id.reject.disagreement-implausible.<scheme>`; `conflicts` = BD | **No** |
| 5 | Researcher; any accepting agreement (T1A or T2A) and (SW or HD) | **CONFLICT** (exact-id, 0.99), `exact-id.conflict.same-work` / `.homonym-number` | **No** |
| 6a | T1A present (steps 2-5 did not fire) | **ACCEPTED** (exact-id, scheme constant 0.99). Overrides every BD (override codes, §1.4); `conflicts` empty | **No** |
| 6b | T2A present, no BD (steps 2-5 did not fire) | **ACCEPTED** (exact-id: 0.90 for A and s2-author; 0.99 for dblp-key and scopus-eid; 0.95 for W, mag, s2). Evidence-only disagreements are reason codes | **No** |
| 7 | No accepting agreement; deterministic bibliographic rules: SW REJECT, HD REJECT, same-paper-same-position ACCEPT, title-year-authors-exact ACCEPT | REJECT: **REJECTED** (deterministic, 0.95). ACCEPT without BD: **ACCEPTED** (0.95). ACCEPT with BD: **UNRESOLVED** (confidence 0), rule `deterministic-bibliographic.accept-blocked-by-id-disagreement` | **No** (a blocked accept goes to human review, not the LLM) |
| 8 | Score p (logistic, uncalibrated) | p ≥ accept: **ACCEPTED** (p), or **UNRESOLVED** with `probabilistic.accept-blocked-by-id-disagreement` if BD. p ≤ reject: **REJECTED** (1-p), BD kept in `conflicts`. Otherwise **UNRESOLVED**; band-eligible only if p is in the band and `conflicts` is empty | Only an eligible band pair can change, at step 9 |
| 9 | LLM band: UNRESOLVED, probabilistic, eligible, `conflicts` empty | accept: **ACCEPTED**; reject: **REJECTED**; abstain: **UNRESOLVED** (llm-adjudicated). A refusal stays probabilistic UNRESOLVED | **No** (an LLM verdict never yields CONFLICT; core :523-528) |
| 10 | Every UNRESOLVED / CONFLICT | Human review queue. It never rewrites the decision record | n/a |

## 1.3 Override matrix
"n/a": the evidence belongs to the other entity kind.

| Disagreement | T1 researcher ID agreement (orcid/dblp-pid) | DOI/arXiv agreement | Another source-record-key agreement (T2A) | Deterministic bibliographic rule | Score ≥ accept | LLM accept |
|---|---|---|---|---|---|---|
| dblp-key / scopus-eid (`block-inferred-acceptance`) | n/a | **overrides**: ACCEPTED (exact-id, 0.99) | **does not override**: CONFLICT (step 3). Inert today: no source carries both a dblp-key and a W | **does not override**: UNRESOLVED (accept blocked; REJECT rules still REJECT) | **does not override**: UNRESOLVED | **does not override**: unreachable (pair is ineligible), so UNRESOLVED |
| openalex-work / mag / s2-* (`evidence-only`) | n/a | nothing to override: ACCEPTED | nothing to override: the agreement ACCEPTs (6b) | nothing to override: ACCEPTED 0.95 | nothing to override: ACCEPTED p | nothing to override: eligible, ACCEPTED |
| openalex-author / s2-author, plausible pair | **overrides**: ACCEPTED (exact-id, 0.99) | n/a (a shared DOI of the mentions' works is the column-4 rule) | **does not override**: CONFLICT (step 3; s2-author vs A, inert) | **does not override**: UNRESOLVED | **does not override**: UNRESOLVED | **does not override**: unreachable, so UNRESOLVED |
| openalex-author / s2-author, implausible pair | **overrides**: ACCEPTED (step 4 requires no T1A; today's behaviour) | n/a | **does not override**: CONFLICT (step 3 precedes step 4) | **does not override**: REJECTED (step 4) | **does not override**: REJECTED | **does not override**: REJECTED |
| T1 (orcid, dblp-pid, doi, arxiv), for reference | **does not override**: CONFLICT | **does not override**: CONFLICT | **does not override**: CONFLICT | **does not override**: CONFLICT or REJECTED | same | same |

## 1.4 Reason codes and rule IDs that preserve the disagreement
| Situation | Reason code | Rule ID (prefixed by path) | In `conflicts`? |
|---|---|---|---|
| Disagreement recorded (any outcome) | `record-key-disagreement.<scheme>` (new; dblp-key, scopus-eid); `algorithmic-id-disagreement.<scheme>` (existing); `evidence-only-id-disagreement.<scheme>` (existing) | existing `exact-id.algorithmic-disagreement.<scheme>`; new `exact-id.record-key-disagreement.<scheme>`; existing `exact-id.evidence.disagree.<scheme>` | `block-*` dispositions: yes on UNRESOLVED, REJECTED and CONFLICT; **no on ACCEPTED**. evidence-only: never |
| Overridden by T1A | `<disposition>.<scheme>.overridden-by.<scheme>`, e.g. `block-inferred-acceptance.dblp-key.overridden-by.doi`, `block-inferred-acceptance-if-plausible.openalex-author.overridden-by.orcid` (replaces `algorithmic-id-disagreement-overridden-by-authoritative-agreement.<scheme>`) | `exact-id.override.<scheme>.by.<scheme>` | No (core forbids conflicts on ACCEPTED) |
| Inferred accept blocked | `<disposition>.<scheme>.blocks.<path>` plus existing `acceptance-blocked-by-id-disagreement` | existing `<path>.accept-blocked-by-id-disagreement` | Yes |
| Band pair excluded from the LLM | existing `llm-band-excluded-by-id-disagreement` | existing (the band rule is removed) | Yes |
| Same-tier contradiction | existing `identifier-agreement-contradicted-by-disagreement` | existing `exact-id.conflict.identifier-contradiction` | Yes (both kinds) |

**How the values stay recoverable on ACCEPTED** (core has no field for them):
- (a) The reason code and rule ID name the scheme.
- (b) The decision keeps candidateId (= provenance.sourceRecordId), sourceId/targetId and the run. The values are recomputed deterministically from the endpoint records of that run with ExactIdentifiers / EndpointIdentifiers, the reconstruction DE-P5-01 adopted. Assumption, not verified here: for DBLP work records the dblp key is the record's sourceRecordId.
- (c) Features keep `id.openalex-author.conflict = 1` for researchers. For works only the count `id.other.conflict` is kept, not per scheme.

**Optional** (domain-engineer, your decision): a match-decision v3 field such as `overriddenDisagreements: Vector[IdentifierConflict]` allowed on ACCEPTED would store the values inline. It is not required for consistency.

## 1.5 Examples under this wording (synthetic placeholders; work target = version-level, today's)
| # | Outcome | Path, confidence | Key codes | `conflicts` |
|---|---|---|---|---|
| 4a | UNRESOLVED; no LLM | probabilistic, 0 | `algorithmic-id-disagreement.openalex-author`, `block-inferred-acceptance-if-plausible.openalex-author.blocks.probabilistic` | [openalex-author] |
| 4b | ACCEPTED | exact-id, 0.99 constant | `exact-id-agreement.orcid`, `block-inferred-acceptance-if-plausible.openalex-author.overridden-by.orcid` | [] (A values via join) |
| 5a | CONFLICT (step 2, DOI) | exact-id, 0.99 | `authoritative-id-disagreement.doi`, `record-key-disagreement.dblp-key` | [dblp-key, doi] (**changed** from ER-P5-03's [doi]) |
| 5b | Exact title/year/authors or p ≥ accept: UNRESOLVED (blocked). p ≤ reject: REJECTED. In between: UNRESOLVED; never LLM | deterministic or probabilistic | `record-key-disagreement.dblp-key`, `block-inferred-acceptance.dblp-key.blocks.<path>` | [dblp-key] |
| 6 | CONFLICT (DOI) | exact-id, 0.99 | `authoritative-id-disagreement.doi`, `evidence-only-id-disagreement.openalex-work` | [doi] |
| 7a | ACCEPTED (step 6a) | exact-id, 0.99 | `exact-id-agreement.doi`, `block-inferred-acceptance.dblp-key.overridden-by.doi`; rule `exact-id.override.dblp-key.by.doi` | [] (keys via join) |
| 7b | UNRESOLVED (step 7 blocked) | deterministic-bibliographic, 0 | `block-inferred-acceptance.dblp-key.blocks.deterministic-bibliographic` | [dblp-key] |
| 8a | ACCEPTED via doi | exact-id, 0.99 | `exact-id-agreement.doi`, `evidence-only-id-disagreement.openalex-work` | [] |
| 8b | Exact title/year/authors: ACCEPTED (deterministic, 0.95). Otherwise the score decides; LLM-eligible if in the band | as stated | `evidence-only-id-disagreement.openalex-work` | [] |

# Deliverable 2: work-labeling target options (you decide)

| | (A) Version-level | (B) Work-level (lifecycle) | (C) Two questions |
|---|---|---|---|
| Question text (work rows) | "Do A and B describe the same version of a publication: the same text at the same publication stage (preprint, proceedings paper, journal article, thesis, report)?" | "Are A and B versions of the same paper: its preprint(s), accepted manuscript, version of record, or corrected/updated versions?" | Q1 = A's text; Q2 = B's text. Rule: Q1 match implies Q2 match (validator enforced) |
| Researcher rows | unchanged ("same person") | unchanged | unchanged, one question |
| `work` entity means | a version (= json:102 today) | one paper across its lifecycle | policy = version (Q1); Q2 is recorded for Phase 6 |

**Edge cases** (match = M, non-match = N):

| Case | A | B | C (Q1/Q2) |
|---|---|---|---|
| preprint vs version of record | N | M | N/M |
| extended journal version of a conference paper | N | N (a new publication with added contribution; the fixed rule avoids judging "how extended") | N/N |
| corrected version (same DOI updated, or arXiv vN) | M | M | M/M |
| erratum / corrigendum notice vs the article | N | N | N/N |
| retraction notice vs the retracted paper | N | N | N/N |
| retracted paper (flagged) vs the same paper record | M | M | M/M |
| same-title editorial, different year | N | N | N/N |
| thesis vs paper | N | N | N/N |
| one text with two DOIs (multi-doi, e.g. proceedings plus SIG newsletter) | M (same text, same stage) | M | M/M |

**Policy contradictions and README impact**

| | A | B | C |
|---|---|---|---|
| Current rules that contradict | (1) openalex-work agreement ACCEPTs at 0.95, but OpenAlex merges versions. DS-P5-01 reports that in 7 of 8 preprint-vs-vor units both DOIs return one byte-identical OpenAlex work, so a W agreement can be a version-level false positive. (2) multi-doi M pairs come out as CONFLICT (DOI disagreement): an expected abstention that lowers coverage | DOI/arXiv disagreement gives CONFLICT, so true preprint↔VoR matches can never be ACCEPTED. `block-inferred-acceptance` on dblp-key blocks exactly the CoRR-vs-venue matches B wants. The title-year-authors rule needs the same year. json:102's scope text contradicts B. Coherent only with a version-of relation (P-7: domain-engineer core plus spark-engineer), or with a configured preprint-DOI-prefix list that makes DOI non-authoritative for those pairs | As A for the policy |
| README:1221 / 1242 | fully met; DOI/arXiv stay authoritative | met only if DOI disagreement stays CONFLICT (recall loss by design); weakened if DOI is made non-authoritative for preprint↔VoR | fully met |

**Effect on DS-P5-01 criteria.** Membership is unaffected; no held-out reselection under any option.

| Criterion | A | B | C |
|---|---|---|---|
| preprint-vs-vor | hard negatives | positives | negative under Q1, positive under Q2 |
| conf-vs-journal | negatives | negatives | negatives |
| multi-doi | positives (system outcome CONFLICT) | positives | positives |
| retraction, same-title | negatives | negatives | negatives |

- Only class balance shifts.
- Pressure: under B, positives depend on DBLP CoRR records, and all 120 dblp fetches failed. You could be tempted to add preprint units. Any addition must be a policy-blind, additive selection version (dataset-engineer), never a removal.
- I did not read the stored OpenAlex bodies, so which DOI the merged records carry is unverified.

**Schema changes**

| | A | B | C |
|---|---|---|---|
| blind view | `er-blind-label-view.v2`: new question text, plus `labelingTarget: "work-version"` and `labelGuideVersion: "er-label-guide.v1"`. Add `criterion`, `unitId` and `split` to HiddenFields: unit names such as "preprint-vs-vor" hint at the label | same, with target `work-lineage` | `questions: [{questionId: same-entity / same-version / same-work, text, labelOptions}]` |
| gold label | `er-gold-label.v2`: `labelingTarget` must equal the view's; key `memberId` (Deliverable 3) | same | `label` (Q1) plus `workLabel` (Q2: required for work, null for researcher); Q1=match requires Q2=match, and Q2=non-match requires Q1=non-match |

**Metrics**
- A: precision, recall, F1, confusion matrix and coverage per kind and per path against version labels, plus per-criterion breakdown, unresolved/CONFLICT rate and threshold sensitivity.
- B: the same against work labels, plus an explicit "version-split" false-negative count.
- C: A's metrics against Q1 (primary). Diagnostics only:
  - a Q1×Q2 2×2 table;
  - the system outcome distribution on pairs with Q2=M and Q1=N;
  - precision and recall against Q2.
- "unsure" labels are excluded from precision and recall and counted separately.

**Recommendation (you decide): C.**
- It keeps the current version-level policy and README:1221/1242 unchanged, and needs no reselection.
- It gives Phase 6 the measurement it needs to decide on aggregation without committing now.
- Cost: a second answer on work rows only.
- If labeling capacity is tight, A is the next choice. B is not coherent without P-7.

# Deliverable 3: queue IDs under a policy-version change

**Why today's design breaks your rule**
1. `ReviewQueue.build` groups rows by `stratumOf` = kind/path/**outcome**/**score band** (ReviewQueue.scala:236-237, 318-343). A policy change moves pairs between strata and changes the quotas, so different pairs are selected.
2. `rowId = hash(schema, candidateId, policyVersion)` (:239-240). `GoldLabels.parseQueue` re-checks this hash (GoldLabels.scala:65) and `ingest` joins on rowId, so a version bump would orphan human labels.
3. The blind-view order key uses rowId (BlindView.scala:218-219), so the presentation order changes with the policy version. This is harmless, but should change too.

The within-stratum order (seed + candidateId) is already policy-free. No held-out queue or labels exist yet, so nothing is invalidated today.

**Proposed design**
| Element | Rule |
|---|---|
| Member key | `memberId = "gm:" + hash("er-gold-member.v1", entityKind, canonical endpoint pair)`. The endpoint IDs are stable record IDs, not candidate, blocking, feature or policy values. I did not verify whether candidateId hashes the blocking or feature version, so I avoid it |
| Membership | Built once from the held-out per-split pipeline run. **Census:** every held-out candidate pair (estimate ~130 without dblp, ~600 with). Only if the total exceeds the labeling capacity N that you set: a seeded sample ordered by `sha256("er-gold-sample.v1", seed, memberId)`, with proportional allocation over policy-free strata only (entity kind × DS-P5-01 criterion). Never by outcome, path, score or band |
| Manifest | `er-gold-membership.v1.jsonl` in my module: memberId, kind, endpoints with dataset, record ID and snapshot, unitIds, criterion, split, generating run and blocking version, selection rule, seed. The generator refuses to overwrite; growth only as a superset in a new manifest version |
| Per-policy rows | `rowId = hash("er-review-queue-row.v3", memberId, policyVersion)`, regenerated per policy version with the same members. Labels join on **memberId**, so they survive policy changes |
| Missing candidate | A member that a later blocking version does not produce is reported as `not-generated` (a false negative if labeled match), never dropped |
| Outcome-stratified builder | Kept only for **dev** diagnostics; it refuses held-out input. Outcome strata and the required cells become post-hoc reporting. An empty cell is reported as such and is not filled by reselection |

**Code changes for approval**

| File (under modules/entity-resolution/src/) | Change |
|---|---|
| main/.../review/GoldMembership.scala (new) | memberId, census or seeded selection, manifest read/write/verify |
| main/.../review/ReviewQueue.scala | rows carry memberId; rowId from memberId; row schema v3; outcome-stratified build limited to dev |
| main/.../review/ReviewQueueMain.scala | `membership` mode (create once) and `queue` mode (resolve members) |
| main/.../review/CuratedRunReader.scala | endpoint → selection unit, criterion and split, read as data from datasets/er-candidates and PROVENANCE.tsv |
| main/.../review/BlindView.scala | memberId, order by memberId, Deliverable 2 question, schema v2, extra hidden fields |
| main/.../evaluation/GoldLabels.scala | `er-gold-label.v2` keyed by memberId; labelingTarget; C's workLabel |
| main/.../evaluation/GoldSetEvaluation.scala, GoldSetEvalMain.scala | join on memberId; `not-generated` reporting; per-criterion breakdown |
| test/...: new GoldMembershipSuite; ReviewQueueSuite, BlindViewSuite, GoldSetEvaluationSuite, SampleQueueProbeSuite, LeakageStructureSuite | membership identical under two policies, and under permuted outcomes; membership code never references policy/Resolver |

The Deliverable 1 implementation needs separately:
- er-policy.v3.json;
- MatchPolicy.scala (dispositions);
- Resolver.scala (steps and codes);
- ConflictPolicySuite and MatchPolicySuite.

Adjudication.scala needs no change: its existing `conflicts.isEmpty` check already excludes blocked pairs.

Other owners:
- hadoop-engineer: per-split held-out run (already raised by DS-P5-01);
- build-engineer: alias arguments;
- human: the gold/** label path (G9).

```text
STATUS: COMPLETE
OWNER: entity-resolution-engineer
WORK ORDER: ER-P5-04 (Phase 5 main): read-only decision specification (precedence, work-labeling target, queue IDs)
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (no Bash; files read with Read only)
TEST RESULTS: not applicable (read-only; no behaviour change, so no test obligation; no sbt run)
VALIDATION RESULTS: no gold-set evaluation (no human labels exist). Outcomes were traced by reading Resolver.scala, MatchPolicy.scala, Plausibility.scala, Adjudication.scala, er-policy.v2.json and core MatchDecision.scala:293-540, not by execution. Score-dependent outcomes (4a, 5b, 7b, 8b) are stated conditionally.
ASSUMPTIONS: The dblp-key of a DBLP work equals its sourceRecordId. Endpoint record IDs are stable across blocking, feature and policy versions. Whether candidateId hashes the blocking or feature version was not checked. Which DOI the merged OpenAlex preprint/VoR records carry was not checked (record bodies not read). Under all options researcher labeling is unchanged.
RISKS: (1) Accepting via a DOI despite differing record keys (7a) risks a false positive when a source copied a wrong DOI; gold metrics on the exact-id path will show it. (2) Overridden values are not stored inline on ACCEPTED (recovery by join) unless core adds a field. (3) Under version-level labeling, an openalex-work agreement can accept two versions merged by OpenAlex. (4) A census may still fall below 200 pairs without the dblp records. (5) Unit and criterion names leak label hints if they are not hidden in the blind view. (6) The policy stays uncalibrated.
MANUAL ACTION REQUIRED: Human decides: (a) the narrow "block inferred acceptance" reading vs "7a must not accept"; (b) per-scheme dispositions (P-1, P-2); (c) the labeling target A, B or C (C recommended); (d) census vs seeded sample and capacity N; (e) the optional core field for overridden disagreements (domain-engineer); (f) where the membership manifest lives and the gold/** path (G9).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Human decisions above, then an implementation work order for er-policy.v3 and gold membership (entity-resolution-engineer) with sbt check, then code-reviewer, then the held-out per-split run (hadoop-engineer), membership generation, and blind labeling.
```

---

## DS-P5-02: dataset-engineer (final report, verbatim; read-only work order, no Bash)

Orchestrator note: this report discloses that the DS-P5-01 fetch (and `fetch-dblp.sh --sample`)
requested dblp per-record XML URLs that dblp's robots.txt disallows (`Disallow: /rec/xml`,
`Disallow: /*.xml`, `Crawl-delay: 4`). All 120 such requests received HTTP 202 challenges, and no
dblp record content was stored. Recorded as a process finding for the human.

---

## DS-P5-02: review packet for the proposed dblp download (read-only)

The 1.03 GB download is not the only way to get the pairs. Two smaller options work, with costs:
- **(b) dblp SPARQL:** provides the 120 dblp records as RDF facts, not as dblp XML.
- **(g) Human browser saves:** the human saves the 120 record pages by hand.

Neither gives byte ranges of the pinned release. The download remains the only option that does.

**New finding: dblp's robots.txt disallows the per-record XML URLs.** On both dblp.org and dblp.uni-trier.de it says `Disallow: /*.xml` and `Disallow: /rec/xml`, with `Crawl-delay: 4`. So retrying alternative (a) more slowly is not acceptable under dblp's stated policy. The DS-P5-01 run (and `fetch-dblp.sh --sample`) requested exactly those paths; I did not check robots.txt then.

**New finding: a November release exists.** DROPS now lists `dblp-2026-11-01`: 1.03 GB, MD5 `18ad5db59592406ec74289975c4b44cb`. Its existence matters for the cutoff risk (section 7).

I made no Bash calls, wrote no files and downloaded nothing. I read the files and checked sources with WebFetch only. Side effect: the WebFetch tool cached two response bodies outside the repository, under `/Users/taufeeqpatel/.claude/projects/-Users-taufeeqpatel-Desktop-ScholarChain/107a6780-f081-4d82-af1b-11c6ff34610e/tool-results/`: the 57-byte `.md5` file and the 12.8 KB DTD.

### 1. Script, commands, commit state

**Download** (from `fetch-dblp.sh` `--full`; the manifest `full_command` is identical). Run from the repository root in a Terminal:
```
SCHOLARCHAIN_CONFIRM_FULL_DOWNLOAD=dblp-2026-10-03 datasets/scripts/fetch-dblp.sh --full
```
- The script refuses with exit 3 unless the variable equals `datasets.dblp.snapshot.id`.
- Inside, it runs: `curl --fail --location --proto '=https' --proto-redir '=https' --max-filesize 2000000000 -A "$DS_UA" -C - -o "$dest.part" "$URL"`.
- It then checks the MD5 against the pinned value and runs `gzip -t`. On any failure it deletes the `.part` file.
- On success it renames `.part` to the final file and writes a `.sha256` sidecar.
- Curl has no time limit; only the 2 GB size cap bounds it.
- `--full` does not fetch the DTD or the `.md5` file.

**Follow-on commands, in order:**

| # | Command | Effect |
|---|---|---|
| 1 | `datasets/scripts/fetch-dblp.sh --verify` | offline: validates the dblp manifest entry, the MD5, the sidecar, and runs `gzip -t` (a second full decompression) |
| 2 | `datasets/scripts/fetch-samples.sh --dry-run datasets/er-candidates` | optional plan, no network |
| 3 | `datasets/scripts/fetch-samples.sh --sample datasets/er-candidates` | extracts the dblp excerpts; one network retry of the failed Crossref DOI |
| 4 | `datasets/scripts/fetch-samples.sh --verify datasets/er-candidates` | offline er-verify |
| 5 | `datasets/scripts/validate-manifest.sh --verify` | manifest and samples |
| 6 | `sbt check` (foreground) | definition of done |

Do **not** run `fetch-dblp.sh --sample`: it requests robots-disallowed `dblp.org/rec/<key>.xml` URLs.

**Commit state.** I could not check HEAD without Bash. This comes from the `git status` snapshot at the start of my session:

| File | State |
|---|---|
| `datasets/scripts/fetch-dblp.sh` | committed, not listed as modified |
| `datasets/scripts/lib-datasets.sh` | committed, not listed as modified |
| `datasets/scripts/dsutil.py` | **modified, uncommitted**; `--full` uses its `get` subcommand to read the manifest |
| `datasets/scripts/fetch-samples.sh` | **modified, uncommitted** |
| `datasets/scripts/fetch-er-candidates.sh` | **untracked**, no executable bit; run through `bash` |
| `datasets/dataset-manifest.yaml` | **modified, uncommitted**; the dblp values `--full` reads match the release page (section 2) |

- The dblp release-excerpt fallback in `dsutil.py` (`er_dblp_release`, `er_dblp_extract` and the branch at lines 1098-1134) was added after the DS-P5-01 fetch had started. **It has never run.**
- Because `datasets/scripts/**` is not byte-identical to HEAD, script provenance would refuse or ask for any agent run. The human must run these commands, or commit after review.

### 2. Source URLs (WebFetch, 2026-11-05)

| Item | URL | What the page shows |
|---|---|---|
| Release file | https://drops.dagstuhl.de/storage/artifacts/dblp/xml/2026/dblp-2026-10-03.xml.gz | release page: "dblp-2026-10-03.xml.gz" (Filesize: "1.03 GB") |
| MD5 file | https://drops.dagstuhl.de/storage/artifacts/dblp/xml/2026/dblp-2026-10-03.xml.gz.md5 | `cbd329100ea1bbb2873fa2f6d414ea00  dblp-2026-10-03.xml.gz` (57 bytes). Matches the manifest pin |
| Release page | https://drops.dagstuhl.de/entities/artifact/10.4230/dblp.xml.2026-10-03 | "dblp computer science bibliography – Monthly Snapshot XML Release of October 2026"; Publication Date "2026-10-03"; License "Creative Commons Zero v1.0 Universal"; DOI 10.4230/dblp.xml.2026-10-03 |
| DTD | https://dblp.org/xml/dblp.dtd | listing: "dblp.dtd 2023-06-28 16:19 13K". Declares the Latin-1 entities inline (`<!ENTITY uuml "&#252;" >`); no external entity file |

**Why the location is official:**
- https://dblp.org/xml/release/ answers `301 Moved Permanently` to https://drops.dagstuhl.de/entities/collection/10.4230/dblp.xml.
- That collection page is titled "dblp computer science bibliography – Monthly Snapshot XML Release", names "Schloss Dagstuhl – Leibniz Center for Informatics" as creator, and is CC0.
- DROPS robots.txt does not disallow `/storage/` and sets no Crawl-delay.

### 3. Sizes

| File | Compressed | Extracted | Source |
|---|---|---|---|
| dblp-2026-10-03.xml.gz | "1.03 GB" (display value; whether this means 10^9 bytes or GiB is UNVERIFIED) | **UNVERIFIED, not published.** My estimate is roughly 4-6 GB, assuming a typical 4-6x gzip ratio for text XML. That is not a measurement, and it is never written to disk | DROPS release page; manifest `size.expected` |
| rolling dblp.xml.gz (not used) | "1.0G", modified 2026-11-05 01:50 | n/a | dblp.org/xml listing |
| .md5 | 57 bytes | n/a | observed via WebFetch |
| dblp.dtd | "13K" (12.8 KB observed via WebFetch) | n/a | listing; not needed by the fallback |

### 4. Output paths and disk

| Path | Git status | Kept? |
|---|---|---|
| `datasets/downloads/dblp/full/dblp-2026-10-03.xml.gz.part` | ignored (`/datasets/downloads/` in `.gitignore`) | only during the transfer; renamed on success, deleted on MD5 or gzip failure |
| `datasets/downloads/dblp/full/dblp-2026-10-03.xml.gz` (+ `.sha256`) | ignored | kept |
| `datasets/samples/er-candidates/{heldout,dev}/dblp/<key with / replaced by __>.xml` | **git-visible**, ready to commit | kept |
| `datasets/samples/er-candidates/PROVENANCE.tsv` (rows appended), `FETCH-FAILURES.tsv` (rewritten) | git-visible | kept |

- **Disk:** about 1.03 GB peak; the rename does not copy. I suggest at least 2 GB free as margin.
- **Streaming:** the XML is streamed with `gzip.open`. No extracted XML is ever written; only the excerpts are.

### 5. What the fallback extracts

**Records:** 120 dblp records, counted from `selection.tsv` and `split-assignment.tsv`.

| Criterion | heldout records | dev records |
|---|---|---|
| homonym-rare | 10 | 4 |
| homonym-east-asian | 6 | 2 |
| homonym-western | 6 | 4 |
| name-variant | 12 | 6 |
| orcid-vs-none | 10 | 6 |
| preprint-vs-vor | 10 | 6 |
| same-title | 6 | 2 |
| multi-doi | 4 | 2 |
| conf-vs-journal | 10 | 6 |
| ordinary | 5 | 3 |
| retraction | 0 | 0 |
| **Total** | **79** | **41** |

- **Bytes added (estimate):** 120 files of about 0.5-1.6 KB each, so roughly 60-200 KB. This is based on the record sizes in `dblp-sample.xml`. The current total is 623,126 bytes; the er-verify limit is 8 MiB.
- **Pairs (DS-P5-01 estimates, none observed):** about 600 heldout and 300 dev with dblp, against about 130 heldout without it.

**How each file is built:** the release's header lines (through the `<dblp>` line), then the record's lines, then the release's last line (`</dblp>`). All three are unmodified bytes of the decompressed stream.
- The file is a concatenation of three byte ranges, not one contiguous range.
- Before writing, `er_check_content` checks: well-formed XML; exactly one record with the key; the `ee` element carries the expected DOI; an author element has the expected author text.

**What the ledger row records:**
- `request_url` = `release:dblp-2026-10-03.xml.gz sha256=<sha256 of the .gz> record-offset=<uncompressed offset> record-length=<n>`;
- `http_status` = `-`;
- `bytes` and `sha256` of the stored file;
- the licence;
- the note "exact byte ranges of the pinned release".

**Gaps in that record:**
- The header range is implicitly offset 0, but its length and the footer offset are not recorded.
- er-verify is offline and never re-checks the offsets against the release.

### 6. Alternatives

| Alt | Verdict | Evidence and reasoning |
|---|---|---|
| (a) per-record XML/JSON | **Not acceptable** | robots.txt on dblp.org and dblp.uni-trier.de: `Crawl-delay: 4`, `Disallow: /rec/xml`, `Disallow: /*.xml`, `Disallow: /*.json`. dblp.org pages are behind Anubis ("Access Denied: error code 9e4edb5b6b850c41", "Anubis version v1.27.0"); HTTP 202 matches a challenge. I could not read the dblp FAQ or terms pages (also Anubis). Retrying slower would still violate robots.txt. |
| (b) SPARQL | **Viable, at a fidelity cost** | robots.txt says `Crawl-delay: 10`, `Allow: /sparql`; the page states "all dblp metadata released as open data under CC0 1.0 license" and "synchronized daily". About 120 keys in a few batched queries is small. Costs: the result is RDF projected into CSV/TSV (JSON/Turtle via content negotiation is UNVERIFIED), not dblp XML. That needs a new reader (hadoop-engineer), takes a different parser path from production, uses live data rather than the pinned release, and three 429s were seen in DS-P5-01. |
| (c) mirror | **Not viable** | dblp.uni-trier.de returned 202 and has the same robots rules. dblp.dagstuhl.de is behind Anubis (observed). DROPS is the official archive, but it holds only full dumps. Third-party copies would weaken provenance. |
| (d) OpenAlex + Crossref only | **Not viable for ≥200** | DS-P5-01 estimates about 70 heldout pairs (OpenAlex only) to about 130 (with a Crossref envelope reader). The dblp side supplies the cross-source mention pairs. In 7 of 8 preprint units, OpenAlex returned one byte-identical work for both DOIs, so those collapse. The homonym criteria are defined by dblp homonym numbers, so dblp-pid, homonym-rule and dblp-key strata would stay empty. DBLP is the mandatory seed. |
| (e) more OpenAlex/Crossref units | **Allowed only under conditions; reaches the count, not the dblp strata** | Only new criteria are possible. Adding units to an existing criterion re-ranks it and could flip held-out groups. `er_fetch` also refuses when `split-assignment.tsv` differs from the recomputed text, so code must change first. Each criterion must be pre-declared from source facts only, with no look at policy outcomes (ER-P5-03 examples have been seen). Estimate: about 2.8 heldout pairs per unit, so 40-80 more heldout units (about 65-130 units, 0.5-1 MB). Human decision. |
| (f) ranged download | **Not useful** | A DEFLATE stream cannot be decoded from an arbitrary offset without an index built by a full pass. A prefix range yields only the first records in document order, and the 120 records are scattered. Whether the file is multi-member gzip, and whether DROPS honours Range, are both UNVERIFIED. |
| (g) human browser saves | **Viable, manual** | A human browsing is not a crawler. 120 pages; whether "Save As" keeps raw bytes is UNVERIFIED; no import or ledger tool exists yet; live data. |

### 7. Risks

| Risk | Detail |
|---|---|
| Time (estimates) | Download about 3 min at 50 Mbit/s, about 14 min at 10 Mbit/s. MD5 + `gzip -t` + sha256 about 1-2 min. Python streaming extraction about 5-20 min. Each `--sample` run re-hashes the 1 GB file. |
| 180 s harness limit | Agents must not run these commands: `--full` is human-only, and auto-backgrounding triggers the UNATTRIBUTED audit refusal. Run them in a Terminal. |
| 500 s run budget | The ER run's 500 s budget starts before extraction. If extraction takes longer, the Crossref retry is skipped. Exit 1 while any failure remains. |
| Release cutoff | Possibly absent from 2026-10-03: `journals/tjs/CamaraCB26`, `journals/comcom/RioMSJC26`, `journals/theoretics/PintoH26`, `conf/citc/ChatterjeeLV26` (heldout) and `journals/bspc/ErenT26` (dev). Absent keys become "key not in the pinned release" with no network fallback. Re-pinning to 2026-11-01 (same size) would narrow the gap, but needs manifest and code changes and a human decision. |
| DOCTYPE and encoding | The release encoding is UNVERIFIED. The checker maps named entities to numeric references and does not load the DTD; Hadoop must handle the DOCTYPE and encoding of each file. |
| Untested fallback | The layout assumptions (record start tag at the start of a line; `<dblp>` and `</dblp>` on their own lines) are UNVERIFIED. If they fail, an uncaught ValueError ends the run before any write. If the release or its sidecar is missing or mismatched, the code silently requests robots-disallowed dblp.org URLs (about 2 requests). The code needs review before it runs. |
| Range and resume | If DROPS ignores Range, resuming a `.part` file fails and the human deletes it by hand. |

```text
STATUS: COMPLETE
OWNER: dataset-engineer
WORK ORDER: DS-P5-02 (Phase 5 main) – read-only review packet for the proposed dblp download
FILES CREATED: none (the WebFetch tool cached 2 response bodies outside the repository under /Users/taufeeqpatel/.claude/projects/-Users-taufeeqpatel-Desktop-ScholarChain/107a6780-f081-4d82-af1b-11c6ff34610e/tool-results/)
FILES MODIFIED: none
COMMANDS RUN: none (no Bash). WebFetch:
  - https://drops.dagstuhl.de/entities/artifact/10.4230/dblp.xml.2026-10-03
  - https://drops.dagstuhl.de/storage/artifacts/dblp/xml/2026/dblp-2026-10-03.xml.gz.md5
  - https://dblp.org/xml/README.txt
  - https://dblp.org/faq/Am+I+allowed+to+crawl+the+dblp+website.html (Anubis)
  - https://dblp.org/xml/
  - https://dblp.org/faq/How+to+use+the+dblp+search+API.html (Anubis)
  - https://dblp.org/robots.txt (twice)
  - https://dblp.dagstuhl.de/faq/Am+I+allowed+to+crawl+the+dblp+website.html (Anubis)
  - https://dblp.org/xml/release/ (301)
  - https://drops.dagstuhl.de/entities/collection/10.4230/dblp.xml
  - https://drops.dagstuhl.de/entities/artifact/10.4230/dblp.xml.2026-11-01
  - https://dblp.org/xml/dblp.dtd
  - https://sparql.dblp.org/
  - https://dblp.uni-trier.de/robots.txt
  - https://sparql.dblp.org/robots.txt
  - https://drops.dagstuhl.de/robots.txt
TEST RESULTS: not applicable (read-only; sbt not run)
VALIDATION RESULTS: The pinned URL, MD5 cbd329100ea1bbb2873fa2f6d414ea00, size "1.03 GB", CC0 licence and DOI were re-confirmed on DROPS on 2026-11-05. validate-manifest, er-verify and sbt check were not run.
ASSUMPTIONS: Commit state comes from the session-start git status snapshot. All times, the extracted size, excerpt bytes and pair counts are estimates. The record layout of the release is unverified.
RISKS: Section 7. Also: the DS-P5-01 fetch and fetch-dblp.sh --sample requested robots-disallowed dblp paths; the uncommitted, untested fallback code would run on the human's machine.
MANUAL ACTION REQUIRED: Human:
  - choose the download (optionally pinning 2026-11-01), SPARQL (b), browser saves (g), or new OpenAlex/Crossref criteria (e);
  - have code-reviewer review the dsutil.py fallback before any run;
  - run the commands only in a Terminal.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Human decision on the dblp acquisition route. Then a dataset-engineer work order: remove the robots-disallowed network paths, add a no-network flag, record the header and footer ranges, and update the manifest. Then code-reviewer, then the human run and the verification commands.
```

---

## DS-P5-03: dataset-engineer (final report, verbatim)

Orchestrator observations after hand-back (2026-11-05):
- The agent was interrupted once by an API usage limit and resumed from saved state.
- `git status --porcelain`: changes under `datasets/**` (now also `scripts/fetch-dblp.sh`
  modified), plus the previously known Phase 5 paths; nothing else.
- `.claude/hooks/verify-tests-ran.sh --check`: OK.
- The sbt and dataset-script outputs below are agent-reported.

---

## DS-P5-03 summary

dblp extraction is now offline-only, the excerpt byte ranges are recorded, and er-verify can re-check them against the release. A fixture run through an allowlisted command passed: 36 checks, 0 failures, covering every failure mode the work order lists.

- **Full download: not run** (not authorized).
- **dblp records extracted from the real release: none.** It has not been downloaded.
- **`sbt check` (foreground, 122 s):** passed, 604 tests, 0 failed.
- **Resume check:** I re-read `dsutil.py` first; it still held the DS-P5-01 fallback, so no partial edit had landed. `git status --porcelain` ran without an audit refusal.

```text
STATUS: COMPLETE
OWNER: dataset-engineer
WORK ORDER: DS-P5-03 (Phase 5 main): offline-only dblp extraction, byte-range ledger, --with-release verify, fixture verification, manifest update
```

### FILES CREATED
- /Users/taufeeqpatel/Desktop/ScholarChain/datasets/er-candidates/fixture/fixture-release.xml
  - 3323 bytes, sha256 b2198d3e70046176a78ebc18b48f5ad5ff3b7e2cd3264ef182255afa8a5a033d.
  - Contains the 4 records of datasets/samples/dblp/dblp-sample.xml, copied unchanged, wrapped in a release-style header and footer. It is not a dblp release and is never extracted into samples.

### FILES MODIFIED
All under /Users/taufeeqpatel/Desktop/ScholarChain/datasets/.

**scripts/dsutil.py**
- Removed:
  - the silent network fallback;
  - the dblp.org and dblp.uni-trier.de per-record URLs (dblp items now have `urls=[]`);
  - dblp pacing.
- `run()` (the only caller of `er_curl`) raises if it is handed a dblp item.
- New functions:
  - `er_dblp_pin`: release values from the manifest;
  - `er_dblp_open_release`: one pass for MD5 and SHA-256, checked against the pin and the sidecar;
  - `er_dblp_scan`: layout-checked streaming scan;
  - `er_dblp_excerpts`, `er_write_excerpt`, `er_dblp_step`;
  - `er_read_ranges`, `er_check_ranges_offline`, `er_check_ranges_release`.
- Subcommands: `er-verify --with-release` and `er-fixture`.
- `validate` now requires `ranges_file`, `robots_compliance`, `byte_range_ledger` and `missing_record_policy`.

**scripts/fetch-er-candidates.sh** (untracked): new modes `--verify --with-release` and `--fixture`; header documents the network rules.

**scripts/fetch-samples.sh**: new dispatch for `--verify datasets/er-candidates <pinned release path>` and `--verify datasets/er-candidates/fixture`.

**scripts/fetch-dblp.sh**: `--sample` no longer requests `dblp.org/rec/<key>.xml`. It keeps only dblp.dtd and the DROPS .md5. `--full` is unchanged.

**dataset-manifest.yaml**
- er_candidates:
  - new `ranges_file`, `retrieval.dblp_offline_only`, `retrieval.network_hosts`;
  - new `verify_with_release_command` and `fixture_command`;
  - new `robots_compliance`, `byte_range_ledger`, `missing_record_policy`, `failure_reasons`, `fixture`;
  - `dblp_fallback` removed;
  - `bounds`, `validation_commands` and `unverified` updated.
- dblp: one limitation line added (per-record XML is never requested).

**schemas/er-candidates.md**: new section "dblp release excerpts" with the construction rule, the gate, the refusals, the ledger columns, verification and the fixture. The "dblp/ (network)" row is removed.

Not changed: selection.tsv, split-assignment.tsv, PROVENANCE.tsv, FETCH-FAILURES.tsv, all stored records, docs/agent-runs/**.

### COMMANDS RUN
All in the foreground; none was moved to the background.
- `git status --porcelain`, twice: ok, no audit refusal.
- `git ls-files -- datasets`: ok.
- `datasets/scripts/fetch-samples.sh --verify datasets/er-candidates/fixture`, three runs:
  - run 1: 35/36. The one failure was my own test expectation (I expected 4 offline checks; the code correctly makes 8, two per row). I fixed the expectation.
  - run 2: 36/0.
  - run 3, final code: 36/0.
- `datasets/scripts/fetch-samples.sh --verify datasets/er-candidates`: `er-verify: 1011 checks, 0 failures`.
- `datasets/scripts/fetch-samples.sh --verify datasets/er-candidates datasets/downloads/dblp/full/dblp-2026-10-03.xml.gz`: exit 1, as expected with no release. The summary line was truncated in the tool output.
- `datasets/scripts/fetch-samples.sh --dry-run datasets/er-candidates`: every dblp row shows `release:dblp-2026-10-03.xml.gz (offline extraction ...; no network)`.
- `datasets/scripts/fetch-dblp.sh --dry-run`: ok.
- `datasets/scripts/validate-manifest.sh --verify`: `manifest validation: 1346 checks, 0 failures`.
- `sbt check` (foreground).
- WebFetch, read-only: https://dblp.org/robots.txt and https://drops.dagstuhl.de/robots.txt.

### TEST RESULTS (`sbt check`, verbatim tail)
```
[info] Passed: Total 292, Failed 0, Errors 0, Passed 292
[info] Passed: Total 196, Failed 0, Errors 0, Passed 196
[info] Passed: Total 67, Failed 0, Errors 0, Passed 67
[info] Passed: Total 49, Failed 0, Errors 0, Passed 49
[info] markTestRun: wrote /Users/taufeeqpatel/Desktop/ScholarChain/.claude/.last-test-run (git_head=1bc5382c00200b76d03f6a88ffdbbac53220760e, worktree=dirty, suites=49, passed=604, skipped=0)
[success] Total time: 122 s (0:02:02.0), completed Nov 5, 2026, 7:20:18 PM
```
- Earlier in the same output: `Build sources OK`, `Version ledger OK: 14 pinned entries`, and scalafmt `[success]`.
- `sbt check` does not run the dataset scripts; the dataset gates above were run separately.

### VALIDATION RESULTS
**Fixture** (final run, `er-fixture: 36 checks, 0 failures (temporary directory removed; repository not written)`).

Good case, verbatim:
- `PASS fixture good: 4 records extracted`
- `PASS fixture good: absent key journals/tjs/CamaraCB26 fails with key-not-in-pinned-release, no substitute`
  - That key is a real selection.tsv key that is absent from the fixture.
- Offsets, each matched by an independent search:

| Record | header | record | footer | excerpt sha256 |
|---|---|---|---|---|
| journals/tocs/CorbettDEFFFGGHHHKKLLMMNQRRSSTWW13 | 0+739 | 739+1132 | 3315+8 | f4f560c3... |
| journals/cacm/PopperMP21 (named entity, Unicode) | 0+739 | 1871+500 | 3315+8 | 84d19db2... |
| journals/cacm/Varajao21 | 0+739 | 2371+331 | 3315+8 | 71ead7d6... |
| conf/osdi/ChangDGHWBCFG06 (no DOI) | 0+739 | 2702+613 | 3315+8 | acafe034... |

- `PASS fixture good: offline range check passes (8 checks)`
- `PASS fixture good: --with-release re-check passes (9 checks)`
- `PASS fixture good: --with-release refuses a different release (MD5 not the pin)`
- `PASS fixture tampered offset: offline check fails for the shifted row only`
- `PASS fixture tampered offset: --with-release fails for the shifted row only`
- `PASS fixture: no network call was attempted (er_curl and subprocess.run trapped)`
- The nested `FAIL er:` lines in that output come from the deliberate negative sub-cases (a different release, a shifted offset). They are expected, and the fixture asserts them.

Fail-closed cases. Each one passed as `fails closed with <token>, 5 of 5 items refused, nothing written`, with this reason:

| Case | Reason, verbatim |
|---|---|
| release missing | `release-missing: .../fixture-release.xml.gz does not exist. Nothing was written and no network request was made. ...` |
| sidecar missing | `release-sidecar-missing: ...` |
| MD5 mismatch | `release-checksum-mismatch: MD5 of ... is 83ad6bf8..., the manifest pin is 00000000...` |
| sidecar mismatch | `release-checksum-mismatch: SHA-256 of ... is db369102..., its sidecar says '0000...'` |
| record tag not at line start | `release-layout-error: the record tag of key journals/cacm/Varajao21 is not at the start of a line (line at offset 2371)` |
| missing `</dblp>` | `release-layout-error: the last non-blank line is not "</dblp>"` |
| missing `<dblp>` | `release-layout-error: no "<dblp>" line` |
| duplicate key | `release-layout-error: key journals/cacm/Varajao21 occurs twice (offsets 2371 and 2702)` |
| missing end tag | `release-layout-error: record journals/cacm/PopperMP21 (offset 1871) has no end tag before the next record` |
| text after end tag | `release-layout-error: text follows </article> on the end line of record journals/cacm/Varajao21` |
| truncated gzip | `release-layout-error: cannot decompress the release (Compressed file ended before the end-of-stream marker was reached)` |

**Real ER set**
- `er-verify: 1011 checks, 0 failures`. This is the first audited verification of the 171 records DS-P5-01 stored in the background (recovery-02).
  - dev: 45 OpenAlex works, 6 OpenAlex authors, 6 Crossref.
  - heldout: 87 OpenAlex works, 14 OpenAlex authors, 13 Crossref.
  - 0 OpenAlex author IDs or ORCID iDs appear in both dev and heldout.
- `--with-release` without the release: `FAIL er: --with-release: release-missing: /Users/taufeeqpatel/Desktop/ScholarChain/datasets/downloads/dblp/full/dblp-2026-10-03.xml.gz does not exist. Nothing was written and no network request was made.`, exit 1.

### Download, extraction and verification commands as they now stand
Run by the human, from the repository root, in a Terminal. The download is NOT authorized yet.

1. `SCHOLARCHAIN_CONFIRM_FULL_DOWNLOAD=dblp-2026-10-03 datasets/scripts/fetch-dblp.sh --full`
   - Unchanged. It is a single curl from `https://drops.dagstuhl.de/storage/artifacts/dblp/xml/2026/dblp-2026-10-03.xml.gz`, https only, 2 GB cap, resumable with `-C -`.
2. `datasets/scripts/fetch-dblp.sh --verify`
3. `datasets/scripts/fetch-samples.sh --verify datasets/er-candidates/fixture`
4. `datasets/scripts/fetch-samples.sh --sample datasets/er-candidates`
   - Extracts the dblp records offline.
   - Also makes one network retry of the single failed Crossref DOI to api.crossref.org (an existing failure row).
5. `datasets/scripts/fetch-samples.sh --verify datasets/er-candidates`
6. `datasets/scripts/fetch-samples.sh --verify datasets/er-candidates datasets/downloads/dblp/full/dblp-2026-10-03.xml.gz`
7. `datasets/scripts/validate-manifest.sh --verify`
8. `sbt check` (foreground)

### Output paths
- Download area (git-ignored):
  - `datasets/downloads/dblp/full/dblp-2026-10-03.xml.gz.part` while downloading; deleted on an MD5 or gzip failure;
  - `datasets/downloads/dblp/full/dblp-2026-10-03.xml.gz` and its `.sha256` sidecar.
- Git-visible, written by step 4:
  - `datasets/samples/er-candidates/{dev,heldout}/dblp/<key with / replaced by __>.xml`;
  - rows appended to `datasets/samples/er-candidates/PROVENANCE.tsv` and to the new `datasets/samples/er-candidates/DBLP-RELEASE-RANGES.tsv`;
  - `FETCH-FAILURES.tsv` rewritten.
- The fixture writes only to a temporary directory, which it removes.

### Checksum checks and when they run
| When | Check |
|---|---|
| `--full` | MD5 equal to the manifest pin cbd329100ea1bbb2873fa2f6d414ea00, then `gzip -t`, then the sha256 sidecar is written |
| `fetch-dblp.sh --verify` | sidecar, MD5 and `gzip -t` |
| Every `--sample` that has pending dblp items, before any extraction | `er_dblp_open_release`: one pass computing MD5 (must equal the pin) and SHA-256 (must equal the sidecar); the manifest snapshot id must be dblp-2026-10-03 |
| After each excerpt is written | the file's sha256 is re-hashed and compared with the excerpt bytes |
| `--verify` (offline) | ledger and range sha256 against the file |
| `--verify` with the release path | the release gate again, the recorded `.gz` sha256, and the bytes at every offset |

### Failure behavior
- **Whole dblp step refused** (no dblp file written, no network request):
  - `release-missing`;
  - `release-sidecar-missing`;
  - `release-checksum-mismatch`;
  - `release-pin-mismatch`;
  - `release-layout-error`.
- **One record refused:**
  - `key-not-in-pinned-release`;
  - `record-exceeds-cap` (200,000 bytes);
  - `excerpt-check-failed` (well-formedness, expected DOI, expected author).
- All refusals become FETCH-FAILURES.tsv rows, and the run exits 1.
- OpenAlex and Crossref fetching continues independently, as before.
- A missing record is never substituted, and the pin is never changed to improve coverage.

### Remaining network calls and hosts
- **ER `--sample`:** `api.openalex.org` (`/works/doi:...?select=`, `/authors/<A-ID>?select=`) and `api.crossref.org` (`/works?filter=doi:...`). Nothing else.
- **`fetch-dblp.sh --sample`:** `dblp.org/xml/dblp.dtd` (no Disallow rule matches it) and `drops.dagstuhl.de/.../dblp-2026-10-03.xml.gz.md5`.
- **`fetch-dblp.sh --full`:** `drops.dagstuhl.de/storage/...xml.gz`. DROPS robots.txt disallows only `/api/*`, `/metadata/*/*` and `/entities/*/metadata/*`.
- **Other `fetch-samples.sh --sample` children (unchanged):**
  - `openalex.s3.amazonaws.com` and `api.openalex.org`;
  - `api.crossref.org`;
  - `pub.orcid.org/v3.0` and `api.figshare.com`.
- **No network at all:** `--dry-run`, `--verify`, `--verify` with the release path, and the fixture.
- **No code path requests** `dblp.org/rec/...`, `dblp.uni-trier.de`, or any `*.xml` / `*.json` URL on dblp.org.

### ASSUMPTIONS
- The fixture layout mirrors what the real release is assumed to look like. The real layout is UNVERIFIED (recorded under `unverified`). If it differs, the extractor refuses the whole release (`release-layout-error`); it does not extract wrongly.
- dblp.org robots.txt was read through WebFetch on 2026-11-05 (verbatim content returned): `Disallow: /rec/xml`, `/*.xml`, `/*.json`; `Crawl-delay: 4`. `/xml/dblp.dtd` matches no Disallow rule.
- The fixture-pinned MD5 comes from the gzip built in the temporary directory, so it tests the pin mechanism, not a published checksum.

### RISKS
- **The scripts are still uncommitted**, so script provenance in the guard does not bind them yet.
- **The selection.tsv comment at line 10 is stale.** It still describes `dblp/rec` as `https://dblp.org/rec/<key>.xml`. I left the file untouched because of the membership rule. The comment does not affect parsing; the human may approve a comment-only edit.
- **Five keys are probably absent from dblp-2026-10-03** (UNVERIFIED): CamaraCB26, RioMSJC26, PintoH26, ChatterjeeLV26, ErenT26. Those units keep fewer records.
- **Extraction speed on the 1.03 GB release is unmeasured.** It needs two passes: one for the checksums and one for the scan. The `--sample` 500 s run budget starts before extraction, so the Crossref retry may be skipped.
- **The fixture does not exercise `release-pin-mismatch` or `record-exceeds-cap`.**
- **FETCH-FAILURES.tsv still holds the DS-P5-01 rows** (HTTP 202 dblp URLs) until the next `--sample` rewrites it.

### MANUAL ACTION REQUIRED
Human:
1. Review `git diff -- datasets` and the untracked `datasets/**` paths.
2. Have code-reviewer (RV-P5-02) review the fetch and extraction changes.
3. Only after an explicit decision, run the commands above in a Terminal.
4. Set `chmod +x datasets/scripts/fetch-er-candidates.sh` when committing.

### BLOCKED-ON OWNER
none

### BLOCKED-ON EVIDENCE
none

### NEXT GATE
1. ER-P5-05 (entity-resolution-engineer).
2. RV-P5-02 (code-reviewer): review of the DS-P5-01 and DS-P5-03 fetch and extraction changes, confirming the command, output paths, checksum checks and failure behavior.
3. The human presents the reviewed command and decides on the download.
4. Human run of steps 1-8, then hadoop-engineer per-split runs.

---

## ER-P5-05: entity-resolution-engineer (final report, verbatim)

Orchestrator observations after hand-back (2026-11-05):
- While the agent was writing, the Stop hook refused orchestrator completion repeatedly because
  in-progress ER sources were newer than the marker. Expected; no marker was touched.
- After hand-back, `.claude/hooks/verify-tests-ran.sh --check` was OK, and `git status
  --porcelain` showed no paths outside the known Phase 5 set.
- During the run, the orchestrator relayed RV-P5-02 finding 5 to the agent as an added requirement.
- The sbt counts below are agent-reported.

---

## ER-P5-05: fixed gold membership manifest and policy-independent pair IDs

The work order is implemented with tests, and a foreground `sbt check` passed: 627 tests, 0 failed, 50 suites. entityResolution went from 196 to 219 tests. No core change was needed, and nothing outside `modules/entity-resolution/**` was written.

The real held-out manifest was **not** generated, because the held-out per-split pipeline run does not exist yet. All tests use SYNTHETIC fixtures. There are no labels, so no gold-set metrics exist.

The RV-P5-02 finding 5 requirement is done. One thing still needs your decision: the dblp records themselves state some of the same facts (see the RV-P5-02 section).

### candidateId dependency
- **Finding:** `candidateId` cannot key gold membership.
- **Evidence:** core `MatchCandidateId.of` (`modules/core/src/main/scala/scholarchain/core/StableIds.scala:685-713`) is `"mcand:" + SHA-256(netstrings("scholarchain.match-candidate.v1", entityKind, first, second, blockingVersion, featureVersion, runId))`.
- **Consequence:** it changes with the blocking version, the feature version and every new run. `memberId` therefore never uses it.
- **Endpoint IDs used instead:**
  - work IDs: `work:<ns>:<source id>`;
  - mention IDs: hash of (workId, position);
  - `source-author:` IDs.

  These depend only on source records, not on any blocking, feature or policy version.

### memberId formula
`memberId = "gm:" + SHA-256(netstrings("er-gold-member.v1", entityKind, first, second))`
- (first, second) is the core `CanonicalPair` (UTF-8 byte order) of the two endpoint IDs.
- `entityKind` must be `researcher` or `work`.

### Schema versions
- `er-gold-membership.v1` (new): the manifest, JSONL.
- `er-review-queue-row.v2` becomes `er-review-queue-row.v3`. The row adds `memberId`, and `rowId = "rq:" + SHA-256(netstrings("er-review-queue-row.v3", memberId, policyVersion))`.
- `er-gold-label.v1` becomes `er-gold-label.v2`: keyed by `memberId`, with `rowId` and `candidateId` removed. v1 is retired and refused with a "retired" message; no v1 label was ever written.
- `er-blind-label-view.v1` becomes `er-blind-label-view.v2`:
  - `rowId` and `candidateId` are replaced by `memberId`;
  - order tag `er-blind-view-order.v2`;
  - rows ordered by SHA-256(order tag, seed, memberId).
- Selection rule tags: `er-gold-census.v1` and `er-gold-sample.v1`. The second is also the sample hash tag.
- Unchanged: er-blocking.v2, er-features.v2, er-policy.v2, adjudication v1, the core contracts, and the accepted Unicode limitations. The probe strata are identical to before: 119 resolved, 86/4/5/12/2/10.

### Manifest fields (one JSON line per member)
- `schemaVersion`, `memberId`, `entityKind`
- `endpointA`, `endpointB`: each `{endpointId, endpointKind, sourceDataset, sourceRecordId, snapshot}`, in canonical order
- `unitIds`: sorted, distinct
- `criterion`: distinct criteria joined with `+`
- `split`, `generatingRunId`, `blockingVersion`, `selectionRule`
- `seed`, `capacity` (N), `populationSize`

**Selection:**
- **Default is census:** every pair in the per-split run.
- **Optional capacity N below the population:** a seeded sample ordered by SHA-256("er-gold-sample.v1", seed, memberId). Allocation is largest-remainder proportional over the policy-free groups (entityKind × criterion).
- A seed is required exactly when N is given.

**Refusals (the whole build is refused and every problem is listed):**
- an existing file (never overwritten);
- more than one run or blocking version;
- an endpoint with no source record;
- an endpoint whose record maps to no selection unit;
- any endpoint in a unit of another split, so dev endpoints are refused for the held-out manifest.

**How endpoints map to units:** a mention maps through its work, a work or profile through itself. The mapping is read as data from `datasets/er-candidates/split-assignment.tsv` and `datasets/samples/er-candidates/PROVENANCE.tsv`, cross-checked against each other (unit, criterion, split). Record IDs are canonicalized:
- dblp `rec` gives `work:dblp`;
- openalex `work-by-doi` gives `work:openalex`;
- openalex `author` gives `source-author:openalex`;
- crossref `work-by-doi` gives `work:doi`.

### Queue, labels, evaluation
- **Queue mode:** `RunQueue.resolveMembers` looks each member up in the run by `memberId`, never by `candidateId`, so a run of another blocking version still finds it. It reports every member as one of:
  - a row;
  - `not-generated` (never dropped);
  - `refused`;
  - `untraceable`.
- **Labels:** `GoldLabels.ingest` validates labels against the manifest (memberId recomputed, kind and endpoints matched, duplicates refused).
- **Evaluation:** `GoldSetEvaluation` joins on `memberId`:
  - a labeled member with no row is counted as NOT-GENERATED (extra confusion row, `not-generated` segment, included in end-to-end recall);
  - the queue may not mix versions;
  - rows outside the manifest are refused.
- **Dev-only builder:** the outcome-stratified builder is now `ReviewQueue.buildDevDiagnostics(..., units)`. The units argument is mandatory, it refuses any endpoint in a held-out unit, and the unrestricted `build` is private.
- **ReviewQueueMain modes:**
  - `membership <split-assignment.tsv> <PROVENANCE.tsv> <run dir> <heldout|dev> <manifest> <blind> [capacity seed]`: create-once; writes the manifest and the policy-free blind view; loads no policy.
  - `queue <manifest> <run dir> <internal queue>`: rows under the default policy, with every member accounted for.
  - `diagnostics ...`: dev only.
- **GoldSetEvalMain** now takes `<manifest> <queue> <labels> [maxErrors]`.

### Extension point left for A/B/C (not implemented)
- **BlindView:** the object Scaladoc marks where the work-row question text, `labelingTarget` and a second question would go, under a new view schema. The single question text per entity kind is unchanged.
- **GoldLabel:** the Scaladoc reserves `er-gold-label.v3` for `labelingTarget` and option C's second label. Unknown keys are refused, so a v2 file can never silently carry them (tested).

### RV-P5-02 finding 5 (selection.tsv facts kept out of the blind view)
**Done:**
- The blind view is built only from (memberId, entityKind, sourceIdA, sourceIdB) plus the source records' own evidence. No unit ID, criterion, split, selection rule, `expect_*` value, or pid-bearing unit-ID fragment is copied into it. The manifest keeps unit IDs and criteria internally.
- `HiddenFields` now includes `criterion`, `unitId`, `unitIds`, `split`, `selectionRule`, `seed`, `capacity`, `populationSize`, `generatingRunId`, `rowId`, `candidateId` and `policyVersion`.
- **BlindViewSuite tests:**
  - views built from SYNTHETIC units shaped like `nv-208-1029-...` and `ov-16-493-1` contain none of the unit IDs, criteria, split values or pid fragments (`208/1029`, `208-1029`, `16/493-1`);
  - the views are checked against every real `selection.tsv` unit ID, criterion, `expect_doi`, `expect_author` and derived pid fragment (substring match);
  - the checker is proven to fire on each category.

**Decision needed (not changed, because it is a labeling-procedure question):** the dblp records themselves state the same facts, and the blind view shows them as source evidence under decision 4:
- names as written can carry the dblp homonym number ("Tyler Moore 0001", which equals `expect_author`);
- mentions can carry `dblp-pid:` identifiers;
- works show their DOIs.

On the real held-out data, those values will therefore appear through the records, not through the selection files. The fixture test cannot detect this, because the fixtures are not the DS-P5-01 records. Masking dblp homonym numbers and dblp-pid identifiers in the blind view would need your decision and a new view version.

```text
STATUS: COMPLETE
OWNER: entity-resolution-engineer
WORK ORDER: ER-P5-05 (Phase 5 main): fixed gold membership manifest and policy-independent pair IDs, plus RV-P5-02 finding 5 (selection.tsv facts kept out of the blind view)
FILES CREATED:
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/review/GoldMembership.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/review/SelectionUnits.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/review/EndpointRef.scala (moved out of ReviewQueue.scala unchanged, so membership code never touches a file that imports policy)
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/review/GoldMembershipSuite.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/review/SyntheticUnits.scala
FILES MODIFIED (all untracked ER files under /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/):
- main: review/ReviewQueue.scala, review/ReviewQueueMain.scala, review/BlindView.scala, evaluation/GoldLabels.scala, evaluation/GoldSetEvaluation.scala, evaluation/GoldSetEvalMain.scala
- test: LeakageStructureSuite.scala, review/ReviewQueueSuite.scala, review/BlindViewSuite.scala, review/CuratedRunReaderSuite.scala, review/SampleQueueProbeSuite.scala, evaluation/GoldSetEvaluationSuite.scala
- no core, hadoop, datasets, or docs/agent-runs file was touched
COMMANDS RUN:
- git status --porcelain: ok
- git ls-files (several path sets, tracked and --others): ok
- refused by the guard, nothing ran:
  - `git grep` with quoted arguments (shell-syntax-gate);
  - `grep` (shell-syntax-gate);
  - `git status --porcelain -- modules/entity-resolution .claude` (no-allowlist-entry);
- sbt entityResolution/compile: success
- sbt entityResolution/test: Passed: Total 219, Failed 0, Errors 0, Passed 219
- sbt check (foreground, once): success
TEST RESULTS: sbt check, verbatim tail (output 27 MB, read with Read):
  [info] Passed: Total 49, Failed 0, Errors 0, Passed 49
  [info] markTestRun: core: production sources=17, test sources=17, passed=292
  [info] markTestRun: entityResolution: production sources=25, test sources=21, passed=219
  [info] markTestRun: hadoop: production sources=18, test sources=8, passed=67
  [info] markTestRun: tests: production sources=0, test sources=8, passed=49
  [info] markTestRun: wrote /Users/taufeeqpatel/Desktop/ScholarChain/.claude/.last-test-run (git_head=1bc5382c00200b76d03f6a88ffdbbac53220760e, worktree=dirty, suites=50, passed=627, skipped=0)
  [success] Total time: 120 s (0:02:00.0), completed Nov 5, 2026, 7:43:35 PM
  Earlier in the same output: "Build sources OK", "Version ledger OK: 14 pinned entries", scalafmt checks [success].
  Required tests (each fails without the change):
  - membership identical under two policies and under permuted features;
  - LeakageStructureSuite: membership files never reference policy, Resolver, score, outcome, band, features or other decision words; the only candidate fields read are kind, pair, blocking version and run; the checker is proven to fire;
  - row IDs change with the policy version while member IDs do not;
  - labels keyed by memberId join after a policy-version change;
  - a missing member is reported as not-generated;
  - dev endpoints are refused for the held-out manifest;
  - the seeded sample is deterministic, proportional (6/2/4 of 12 over 30/10/20) and outcome-blind;
  - the blind view hides criterion, unitId and split, plus the RV-P5-02 selection.tsv checks;
  - the outcome-stratified builder refuses held-out input.
VALIDATION RESULTS:
- Gold-set evaluation was NOT run: no human labels exist, so there is no precision, recall, F1, confusion matrix, false positive or false negative. All metric tests use SYNTHETIC fixtures.
- Policy er-policy.v2 is unchanged (uncalibrated; accept 0.85, reject 0.20, band [0.40, 0.70]; prompt and adjudication v1).
- Unresolved rate on the samples is unchanged: 2/119 (system output, not a gold metric).
- No threshold-sensitivity result exists (no labels).
- The real DS-P5-01 files parse as data: 70 units (46 heldout, 24 dev), every PROVENANCE record canonicalizable.
- The real held-out manifest was not generated. How and when: after (1) the human dblp download and extraction, and (2) a hadoop-engineer per-split bounded run over datasets/samples/er-candidates/heldout/ (Crossref envelope reader included), run once:
  `ReviewQueueMain membership datasets/er-candidates/split-assignment.tsv datasets/samples/er-candidates/PROVENANCE.tsv <heldout run dir> heldout <manifest path> <blind view path>`
  with census by default, or `[N seed]` if the human sets a capacity. Then `queue` mode per policy version.
ASSUMPTIONS:
- Endpoint record IDs are stable across blocking, feature and policy versions (verified for the ID formulas in core StableIds.scala).
- A record shared by two units maps to both; a multi-criterion member's criterion is the sorted `+` join.
- A per-split run contains only DS-P5-01 records; any unmapped endpoint refuses the build (fail closed).
- Records outside the DS-P5-01 selection (the Phase 4 samples) count as not held out for the dev-diagnostic guard.
- In evaluation, a labeled member without a row (not generated, refused or untraceable) counts as NOT-GENERATED. The queue-mode summary distinguishes the three; GoldSetEvalMain cannot.
- The blind view is written once in membership mode (policy-free), not per policy version.
RISKS:
- The dblp records state the same identity facts that selection.tsv asserts (homonym numbers in names, dblp-pid identifiers, DOIs), and the blind view shows them as source evidence. Masking them needs a human decision and a new view version.
- A census may stay below 200 held-out pairs without the dblp records (DS-P5-01 estimate ~130).
- Membership is fixed to one generating run. Pairs that a later blocking version newly generates are not members; growth needs a new manifest version (a superset), not implemented.
- `buildDevDiagnostics` is only as strict as the units passed in. ReviewQueueMain always loads the real files; library callers must too.
- The manifest and label paths still have no ratified owner (G9, `gold/**`), and the `goldSetEval` alias (build-engineer) now needs the extra manifest argument.
MANUAL ACTION REQUIRED:
- Human:
  - decide whether the blind view should mask dblp homonym numbers and dblp-pid identifiers;
  - ratify the manifest and gold-label paths (G9);
  - set capacity N if labeling capacity is limited (census otherwise);
  - A/B/C and er-policy.v3 remain pending.
- build-engineer: update the proposed alias to `GoldSetEvalMain <manifest> <queue> <labels> 10`, plus `reviewQueue` aliases for the membership and queue modes.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer review of this diff (membership leakage structure, blind-view selection-fact checks); RV-P5-02 continues; then human dblp download decision, hadoop-engineer held-out per-split run, one-time membership generation, blind labeling, `queue` mode per policy version, and `sbt goldSetEval`.
```

---

## FE-P5-01: factory-engineer (draft amendment)

Archived verbatim, separately, in `amendment-fe-p5-01.md` (draft only; not ratified or applied).

---

## DS-P5-04: dataset-engineer (final report, verbatim)

Orchestrator observations after hand-back (2026-11-06):
- The agent was interrupted once by an API usage limit and resumed.
- `git status --porcelain`: changes only under `datasets/**` (now also
  `scripts/lib-datasets.sh` modified), plus the known Phase 5 paths.
- `.claude/hooks/verify-tests-ran.sh --check`: OK.
- The command outputs below are agent-reported.

---

## DS-P5-04: RV-P5-02 findings 1, 2, 4 and 7 repaired

All four findings are fixed. Each one has a fixture check that would fail without the fix. All required commands passed in the foreground:

| Command | Result |
|---|---|
| fixture (`fetch-samples.sh --verify datasets/er-candidates/fixture`) | 61 checks, 0 failures (was 36) |
| real ER set (`fetch-samples.sh --verify datasets/er-candidates`) | 1012 checks, 0 failures |
| `validate-manifest.sh --verify` | 1347 checks, 0 failures |
| `fetch-dblp.sh --dry-run` | ok |
| `sbt check` (once, 119 s) | 627 passed, 0 failed |

- **Full download: not run.** No network call was made.
- **Resume check:** no edit had landed before the usage-limit cutoff. `git status --porcelain` ran with no audit refusal (at the start and at the end).
- **Other RV-P5-02 items are unchanged:** findings 3, 5, 6, 8 and nits 9-12 stay open. selection.tsv, split-assignment.tsv, PROVENANCE.tsv, FETCH-FAILURES.tsv and the stored records are untouched.

```text
STATUS: COMPLETE
OWNER: dataset-engineer
WORK ORDER: DS-P5-04 (Phase 5 main): repair RV-P5-02 findings 1, 2, 4, 7 in the download and extraction scripts
```

### FILES CREATED
none

### FILES MODIFIED
All under /Users/taufeeqpatel/Desktop/ScholarChain/datasets/.

- **scripts/lib-datasets.sh**
  - New `ds_full_download URL DEST PIN_MD5`: the bounded, resumable transfer plus MD5 / `gzip -t` / sidecar checks, with explicit return codes (it does not rely on errexit).
  - New `ds_full_recovery`: prints the recovery step for each curl failure.
  - Bound constants: `DS_FULL_CONNECT_TIMEOUT_S=30`, `DS_FULL_STALL_BYTES_PER_S=1024`, `DS_FULL_STALL_TIME_S=120`, `DS_FULL_MAX_BYTES=2000000000`.
- **scripts/fetch-dblp.sh**
  - `--full` now calls `ds_full_download` and has no curl call of its own.
  - The header documents the bounds and the recovery behaviour.
  - `--dry-run` prints the bounds.
- **scripts/dsutil.py**
  - Atomic excerpt write:
    - new `ErExcerptWriteError`, `er_write_part`, `er_tail_complete`, `er_repair_torn_tail`, `er_ranges_put`, `er_ledger_commit`;
    - `er_write_excerpt` is rewritten.
  - `er_dblp_step` turns a write failure into a per-record failure.
  - The offline range check is factored into `er_dblp_row_recorded`, `er_dblp_state_problems` and `er_dblp_row_problems`. Behaviour is unchanged, except that it now also reports "the excerpt file is missing".
  - `er_fetch`:
    - repairs a torn last line before reading;
    - reads the ranges file;
    - counts a dblp record as present only when it is consistent;
    - re-extracts uncommitted leftovers and reports inconsistent committed rows.
  - `er_verify` checks that PROVENANCE.tsv and DBLP-RELEASE-RANGES.tsv end with a complete row.
  - New fixture parts: `_er_fixture_layouts`, `_er_fixture_orchestration`, `_er_fixture_full_download`, plus helpers. Imports added: `contextlib`, `io`.
- **dataset-manifest.yaml**
  - dblp:
    - new `retrieval.full_transfer_bounds` and `retrieval.full_failure_behavior`;
    - the `format.encoding` unverified reason is extended;
    - new unverified item: exact compressed byte size.
  - er_candidates:
    - new `byte_range_ledger.write_order`;
    - two new failure reasons;
    - `fixture.covers` extended and `verified_on` set to 2026-11-06;
    - the layout unverified reason is extended.
- **schemas/er-candidates.md**: new refusals, a "Write order" section, and an updated fixture coverage list.

`fetch-samples.sh` and `fetch-er-candidates.sh` were not edited in this work order.

### COMMANDS RUN
All foreground.
- `git status --porcelain` (start and end): ok, no audit refusal.
- `git status --porcelain -- datasets`: refused by the guard (no-allowlist-entry). Nothing ran; I used the plain form instead.
- `grep` and `wc -l`: refused by the guard (shell-syntax-gate, no-allowlist-entry). Nothing ran; I read the files with Read.
- `datasets/scripts/fetch-samples.sh --verify datasets/er-candidates/fixture`: 61 checks, 0 failures.
- `datasets/scripts/fetch-samples.sh --verify datasets/er-candidates`: `er-verify: 1012 checks, 0 failures`.
- `datasets/scripts/validate-manifest.sh --verify`: `manifest validation: 1347 checks, 0 failures`.
- `datasets/scripts/fetch-dblp.sh --dry-run`: ok.
- `sbt check`: success.
- WebFetch of `https://dblp.org/xml/README.txt` (to find the release encoding): failed with a tool usage limit; nothing was read.

### TEST RESULTS (`sbt check`, verbatim tail)
```
[info] Passed: Total 67, Failed 0, Errors 0, Passed 67
...
[info] Passed: Total 49, Failed 0, Errors 0, Passed 49
[info] markTestRun: core: production sources=17, test sources=17, passed=292
[info] markTestRun: entityResolution: production sources=25, test sources=21, passed=219
[info] markTestRun: hadoop: production sources=18, test sources=8, passed=67
[info] markTestRun: tests: production sources=0, test sources=8, passed=49
[info] markTestRun: wrote /Users/taufeeqpatel/Desktop/ScholarChain/.claude/.last-test-run (git_head=1bc5382c00200b76d03f6a88ffdbbac53220760e, worktree=dirty, suites=50, passed=627, skipped=0)
[success] Total time: 119 s (0:01:59.0), completed Nov 6, 2026, 9:31:09 AM
```
- Earlier in the same output: `Build sources OK`, `Version ledger OK: 14 pinned entries`, and the scalafmt checks passed.
- `sbt check` does not run the dataset scripts. The dataset gates below are separate.

### VALIDATION RESULTS
Fixture final line: `er-fixture: 61 checks, 0 failures (temporary directory removed; repository not written)`. All 36 earlier checks still pass. The new PASS lines, verbatim, by finding:

**Finding 1: time bound.** PASS. It would fail without the fix, because `ds_full_download` did not exist.
- `PASS fixture full download: curl gets --connect-timeout 30, --speed-limit 1024, --speed-time 120, -C - and no --max-time`
- `PASS fixture full download stall: exit 28, .part kept with its bytes for resume, RECOVERY names the path`
- `PASS fixture: fetch-dblp.sh --full uses ds_full_download (the tested function) and no other curl call`

**Finding 2: stuck resume.** PASS.
- `PASS fixture full download HTTP 416: non-zero exit, .part kept (not deleted silently), RECOVERY names the path and the manual rm step`
- `PASS fixture full download cannot resume (curl 33): non-zero exit, .part kept, RECOVERY names the manual step`
- `PASS fixture full download completed with the wrong MD5: exit 1, .part deleted and the message says so and why`
- `PASS fixture full download success: MD5 pin and gzip -t pass, file renamed, sha256 sidecar written`
- `PASS fixture full download: a .part that already matches the pin is not requested again`

**Finding 4: atomic excerpt and ledger.** PASS. The old code would fail these checks: it counted a PROVENANCE row without a ranges row as present, re-appended a duplicate ranges row, and raised after the rename.
- `PASS fixture crash before the PROVENANCE commit: file and ranges row exist without a PROVENANCE row, and er-verify detects it (2 failures)`
- `PASS fixture crash recovery: the next run does not count the uncommitted record as present, re-extracts it (one ranges row, one PROVENANCE row), and er-verify passes`
- `PASS fixture torn PROVENANCE row: er-verify detects it (2 failures); the next run drops the torn line, re-extracts .../conf__osdi__ChangDGHWBCFG06.xml, and er-verify passes`
- `PASS fixture PROVENANCE row without a ranges row: not counted as present, reported in FETCH-FAILURES as excerpt-state-inconsistent with the repair step, exit 1, er-verify fails`
- `PASS fixture PROVENANCE row without a ranges row: after the documented manual repair the next run re-extracts it and er-verify passes`
- `PASS fixture post-write sha check fails: excerpt-write-failed in FETCH-FAILURES, exit 1, no file, no .part, no PROVENANCE or ranges row; the other 3 are committed and er-verify passes`
- `PASS fixture post-write sha check: the next run extracts the record`

**Finding 7: fixture gaps.** PASS.
- `PASS fixture large-team-over-cap: a 205132-byte record (5126 authors) fails with record-exceeds-cap, that record only, no file and no ranges row; the other 3 are extracted`
- `PASS fixture large-team-under-cap: a 197132-byte record (4926 authors) is extracted whole, offsets match an independent search`
- `PASS fixture realistic iso-8859-1 | utf-8 | us-ascii: declaration <?xml ... encoding="..."?>, mdate/publtype/cdate attributes, unwanted records (incl. a prefix key) between wanted ones: 4 records extracted, offsets match an independent search, non-ASCII author matched` (three PASS lines, one per declaration)
- `PASS fixture realistic utf-8-declared-latin-1-bytes: bytes that contradict the declaration fail excerpt-check-failed for that record only, nothing written for it`
- `PASS fixture er_fetch first run: exit 1, one FETCH-FAILURES row (journals/tjs/CamaraCB26, key-not-in-pinned-release), 4 excerpts each with a PROVENANCE and a ranges row, er-verify passes (39 checks)`
- `PASS fixture er_fetch re-run: the 4 committed excerpts are skipped as already present (ledger and ranges unchanged), exit 1 for the absent key only`
- `PASS fixture pin-snapshot: release-pin-mismatch, 5 of 5 FETCH-FAILURES rows, exit 1, no dblp file or row written`
- `PASS fixture pin-md5: release-pin-mismatch, 5 of 5 FETCH-FAILURES rows, exit 1, no dblp file or row written`
- `PASS fixture: no network call was attempted (er_curl and subprocess.run trapped)`

**Real data:**
- er-verify gained 1 check (the complete-row check on PROVENANCE.tsv) and reports 0 failures.
- Manifest validation: 1347 checks, 0 failures.

### The download command as it now stands (NOT authorized; the human runs it in a Terminal, from the repository root)
`SCHOLARCHAIN_CONFIRM_FULL_DOWNLOAD=dblp-2026-10-03 datasets/scripts/fetch-dblp.sh --full`

It makes a single curl call to `https://drops.dagstuhl.de/storage/artifacts/dblp/xml/2026/dblp-2026-10-03.xml.gz` with these options:
- `--fail --location --proto '=https' --proto-redir '=https'`
- `--max-filesize 2000000000`
- `--connect-timeout 30 --speed-limit 1024 --speed-time 120`
- `--write-out '%{http_code}' -C - -o <dest>.part`

The follow-up sequence is unchanged from DS-P5-03:
1. `fetch-dblp.sh --verify`
2. the fixture
3. `fetch-samples.sh --sample datasets/er-candidates`
4. `--verify` (offline)
5. `--verify` with the release path
6. `validate-manifest.sh --verify`
7. `sbt check`

### Output paths
- **Download area (git-ignored):**
  - `datasets/downloads/dblp/full/dblp-2026-10-03.xml.gz.part` during the transfer;
  - `datasets/downloads/dblp/full/dblp-2026-10-03.xml.gz` and its `.sha256` sidecar.
- **ER `--sample` (git-visible):**
  - `datasets/samples/er-candidates/{dev,heldout}/dblp/<key with / replaced by __>.xml`, written via a transient `<file>.part`;
  - `DBLP-RELEASE-RANGES.tsv`, rewritten atomically via a transient `.tmp`;
  - `PROVENANCE.tsv`, appended to;
  - `FETCH-FAILURES.tsv`, rewritten.
- **Fixture:** a temporary directory only, which it removes. It contains throw-away repository roots and a curl stub.

### Checksum checks and when each runs
| When | Check |
|---|---|
| `--full`, before requesting | an existing `.part` that already matches the MD5 pin is not requested again |
| `--full`, after curl exits 0 | MD5 = pin cbd329100ea1bbb2873fa2f6d414ea00, then `gzip -t`, then the sha256 sidecar is written |
| `fetch-dblp.sh --verify` | sidecar, MD5, `gzip -t` |
| each `--sample` with dblp items, before any extraction | the manifest pin must be valid; one pass checks MD5 = pin and SHA-256 = sidecar |
| each `--sample`, for an already-stored dblp record | PROVENANCE row, ranges row and file sha256, lengths, ranges and pin all agree; otherwise it is not counted as present |
| each excerpt write | the sha256 of `<file>.part` must equal the excerpt sha256 before the rename |
| `--verify` (offline) | complete last rows, the ranges/ledger/file agreement, files without ledger rows (including `.part` and `.tmp`) |
| `--verify` with the release path | the release gate again, the recorded `.gz` sha256, and the bytes at every recorded offset |

### Stall and timeout bounds
- Connect timeout: 30 s.
- Stall: a transfer below 1024 bytes/s for 120 consecutive seconds. Either case makes curl exit 28.
- Size cap: 2,000,000,000 bytes.
- **There is no total time limit**, so a slow but healthy transfer is never cut off.
- The values are recorded in `datasets.dblp.retrieval.full_transfer_bounds`.

### Failure behaviour by mode
**`--full`.** Every curl failure exits non-zero, keeps the `.part`, and prints a RECOVERY line with the `.part` path and its byte count:

| Failure | Recovery printed |
|---|---|
| exit 28 (stall or no connection) | re-run the same command to resume |
| HTTP 416 | the `.part` is complete but corrupt; `rm '<path>'`, then re-run (the file is not deleted automatically, because the exact release size is unpublished) |
| exit 33 (server will not resume) | `rm '<path>'`, then re-run |
| exit 63 (over the cap) | stop and report |
| any other exit | re-run to resume; if it repeats, `rm '<path>'` |

There is one automatic deletion: when curl reports the transfer complete but the MD5 or `gzip -t` check fails, the `.part` is deleted and the message says so and why. The confirmation gate (exit 3) is unchanged.

**`fetch-dblp.sh --verify`:** a missing or mismatched sidecar, an MD5 mismatch, or a `gzip -t` failure exits non-zero.

**ER `--sample`, whole dblp step refused.** Every dblp item gets a FETCH-FAILURES row, nothing is written, and no network request is made, for:
- `release-pin-mismatch`, which now also applies to records that were already stored;
- `release-missing`;
- `release-sidecar-missing`;
- `release-checksum-mismatch`;
- `release-layout-error`.

**ER `--sample`, one record refused.** These get a FETCH-FAILURES row and exit 1:
- `key-not-in-pinned-release`
- `record-exceeds-cap`
- `excerpt-check-failed`
- `excerpt-write-failed` (no file, `.part` or row is left)
- `excerpt-state-inconsistent` (not counted as present; the message gives the manual repair)

**ER `--sample`, recovery messages:**
- `RECOVER`: an uncommitted leftover (file, `.part` or ranges row) is re-extracted.
- `REPAIRED`: a torn last line is dropped and printed verbatim.

OpenAlex and Crossref fetching is unchanged.

### ASSUMPTIONS
- **The PROVENANCE append is the commit point.** A dblp record with no PROVENANCE row is uncommitted, and re-extraction gives the same bytes from the pinned release.
- **Dropping a torn last line is not data loss.** It is an incomplete ledger fragment (printed verbatim). The record it described is re-extracted (dblp) or re-fetched (OpenAlex/Crossref).
- **Encoding of the real release is unknown.** ISO-8859-1, UTF-8 and US-ASCII are tested as plausible declarations.
- **Fixture attribute values are synthetic.** The `mdate`, `publtype` and `cdate` values only follow the record syntax.
- **The curl stub is a valid stand-in.** It mimics curl exit codes 28, 22 (with 416), 33 and 0. Real DROPS Range and 416 behaviour is still unverified.

### RISKS
- **Real DROPS behaviour is untested.** The bounds and recovery are tested against a stub only.
- **Exit 28 is ambiguous.** curl uses it for both a stall and a connect timeout, so one message covers both.
- **The stall threshold may need tuning.** A 1024 B/s floor over 120 s could trip on a very poor link; the `.part` is kept and resumable in that case.
- **One deliberate behaviour change.** A broken dblp pin now also fails records that were already stored, matching what `--verify` already did.
- **The fixture runs one subprocess.** It is bash, with a curl stub first on PATH and a `.invalid` URL; `/bin/bash` and the system md5/shasum/gzip are used.
- **The scripts are still uncommitted.** Script-provenance pinning is not in effect yet.

### MANUAL ACTION REQUIRED
Human:
1. Review `git diff -- datasets` (lib-datasets.sh, fetch-dblp.sh, dsutil.py, dataset-manifest.yaml) and the untracked `datasets/**` paths.
2. Route RV-P5-03, the focused re-review of the download scripts.
3. Decide the download only after that review. It is not authorized now.

The remaining RV-P5-02 items (3, 5, 6, 8 and nits 9-12) stay open.

### BLOCKED-ON OWNER
none

### BLOCKED-ON EVIDENCE
none

### NEXT GATE
1. RV-P5-03: code-reviewer focused re-review of the download and extraction scripts.
2. The human decides on the download.
3. The human runs the commands above.

---

## ER-P5-06: entity-resolution-engineer (final report, verbatim)

Orchestrator observations after hand-back (2026-11-06):
- `.claude/hooks/verify-tests-ran.sh --check`: OK.
- `git status --porcelain`: no paths outside the known Phase 5 set; nothing under `gold/`.
- The sbt counts below are agent-reported.
- Two behaviour points are flagged for the human:
  - (a) The 200-reviewed minimum now counts "unsure" as reviewed (it replaces ER-P5-01's assumption that only judged labels count).
  - (b) A `--provisional` flag prints metrics below the 200 minimum, each line prefixed PROVISIONAL.

---

ER-P5-06 is complete. Both parts are in place and `sbt check` passed (644 tests, 0 failures).

The real-data check found nothing to mask. The 171 DS-P5-01 OpenAlex and Crossref files contain no pid, person link or four-digit homonym suffix even before masking, so it only shows that real records go through the masked view intact. The synthetic DBLP-shaped fixtures are what actually test the masking. The dblp records do not exist yet (the full download is not authorized), so no dblp record was read.

## Part 1: masking in the blind view (new schema `er-blind-label-view.v3`, masking rules `er-dblp-display-mask.v1`)
Masking is display-only. Source records, the membership manifest (which keeps every source record ID, including pids) and provenance are unchanged. The internal evidence index stays unmasked; only the blind row is masked.

**Masking patterns** (`DblpMasking.scala`). Every masked value becomes one constant marker, `[masked: DBLP person identifier]`, whatever the value:
1. **Homonym number** (person-name fields only: name as written, structured name, name variants, coauthor names): whitespace plus exactly four ASCII digits at the end of the name or before `,` `;` `/`. "Quentin Synthwell 0001" becomes "Quentin Synthwell", and "Synthwell 0002, Quentin" becomes "Synthwell, Quentin". Titles and other text keep their digits.
2. **Pid scheme names** (every string): `dblp-pid:<pid>`, `dblp_pid = <pid>`, `dblp pid <pid>`, and a bare `dblp-pid` / `dblp_pid` / `dblppid` word.
3. **Person-link tokens** (every string). A token is masked when it contains `dblp` plus one of `/pid/`, `/pers/`, `homepages/`, `?pid=`, `?dblp…=`, `pid%3a`, `dblp-pid`. That covers `dblp.org/pid/…`, `dblp.uni-trier.de/pid/…`, `dblp.org/pers/…`, `dblp.org/rec/homepages/…`, `dblp-key:homepages/…` and search URLs carrying a pid. A token is also masked when it is a relative person path: `pid/…`, `/pid/…`, `pers/…`, `homepages/…`, `rec/homepages/…`, `work:dblp:homepages/…`.
   - Publication links are kept (`dblp.org/rec/journals/…`, `db/journals/…`).
4. **Pid values**: every pid found anywhere in the pair, plus a DBLP profile's record ID, is masked wherever it occurs as a whole value (for example `dblp:<pid>` in a profile's identifier list).

**Endpoint IDs.** An endpoint ID that names a DBLP person (`source-author:dblp-pid:…`) is shown as `masked-endpoint:<sha256(er-blind-label-ref.v1, memberId, endpointId)>`, and its record ID is masked. Label ingestion (`GoldLabels.scala`) now accepts either the real endpoint IDs or the references shown in the view, recomputed from the manifest. The label schema stays `er-gold-label.v2`.

**Markers and equality.** I used one constant marker plus a per-row note, not a per-name marker. A per-name marker would leak DBLP's assignment through presence or absence: "X 0001" against "X" would look different from "X 0001" against "X 0001". A tested case: "0001 vs 0002" and "0001 vs 0001" display identically.
- The row notes are constant: a homonym-number note and a person-identifier note.
- Every row also carries "If the evidence shown is insufficient, choose unsure." The label options stay match / non-match / unsure.

**Kept identifiers.** No identifier we keep (DOIs, ORCIDs, OpenAlex author and institution IDs, affiliations) embeds a dblp pid in the current contracts. The token rule would mask any that did, for example a URL with a `?dblp_id=` parameter.

**Visible fields.**
- Row: `schemaVersion`, `memberId`, `entityKind`, `question`, `note` (new), `maskingNotes` (new), `sourceIdA`, `sourceIdB`, `endpointA`, `endpointB`, `labelOptions`.
- Endpoint: `endpointId`, `endpointKind`, `sourceDataset`, `sourceRecordId`, `snapshot`, `nameAsWritten`, `structuredName`, `nameVariants`, `authorPosition`, `affiliations`, `institutionIds`, `identifiers`, `claimedWorkIds`, `work`.
- Work: `workId`, `title`, `year`, `venue`, `authorCount`, `authors`, `identifiers`, `eeLinks` (new), `urls` (new).

**Hidden fields** (unchanged list): `rowId`, `candidateId`, `policyVersion`, `criterion`, `unitId`, `unitIds`, `split`, `selectionRule`, `seed`, `capacity`, `populationSize`, `generatingRunId`, `score`, `decision`, `outcome`, `confidence`, `path`, `matchPath`, `rulesFired`, `features`, `reasonCodes`, `conflicts`, `plausible`, `stratum`, `band`, `llmBandEligible`, `modelVersion`, `promptVersion`, `system`, `exactIdAgreements`. The masked content (homonym number, dblp pid, person links, a DBLP profile's endpoint and record ID) is never a key.

**Real-data masking check counts** (printed by the test as `[er-mask-real]`):
| Item | Count |
|---|---|
| openalex-works files | 132 |
| openalex-authors files | 20 |
| crossref-works files | 19 |
| dblp files | 0 |
| works / profiles / skipped | 151 / 20 / 0 |
| endpoints (all shown) | 570 |
| rows built | 568 |
| raw string leaves | 12239 |
| leaves with a pid pattern before masking | 0 |
| leaves changed by the text rules | 0 |
| person names | 555 |
| names with a 4-digit suffix | 0 |
| rows with masking notes | 0 |
| survivors after masking | 0 |

## Part 2: capacity 240, freeze, coverage
- **Capacity and seed:** the default capacity is 240 (`GoldMembership.DefaultCapacity`), using the existing seeded proportional sample over (entity kind × criterion). The generator command is now `membership <split-assignment.tsv> <PROVENANCE.tsv> <run dir> <heldout|dev> <manifest> <blind view> <seed> [capacity]`. The seed is required and is recorded on every member.
- **Small population:** below 240, the whole population is taken as a census and the generator prints the shortfall ("nothing padded").
- **Generator output:**
  - design: seed, capacity, population, sampled, rule, shortfall;
  - researcher/work counts for the population and the sample;
  - population and sample counts per stratum;
  - `manifest sha256=<hex>`.
- **Freeze:** the manifest is still create-once and is never overwritten. `GoldSetEvalMain --manifest-sha256=<hex>` refuses (exit 2) any manifest file whose bytes hash differently. The output says whether the freeze matched or was not checked.
- **Coverage report** (always printed, also as JSON): members sampled, population, capacity, seed, rule, counts by kind, human-reviewed (also by kind), labeled match / non-match / unsure, unlabeled, not-generated (and how many of those were reviewed), and every exclusion with its reasons. The three reasons are: unlabeled; unsure (reviewed, excluded from precision and recall); not-generated.
- **The 200 minimum:** `MinimumReviewedPairs = 200`, and "unsure" counts as reviewed.
  - Below 200, final gold metrics are withheld: exit 3, coverage and the refusal are printed.
  - `--provisional` prints the metrics with every line prefixed `PROVISIONAL`.
  - At 200 or more, the metrics are final (exit 0).
- **No replacement:** unsure members are never replaced or resampled. Membership has no label input, and a test shows the manifest bytes are unchanged after evaluation.
- No real manifest was generated and nothing was written under `gold/`.

STATUS: COMPLETE
OWNER: entity-resolution-engineer
WORK ORDER: ER-P5-06 (Phase 5 main): blind-view masking and labeling capacity 240
FILES CREATED:
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/review/DblpMasking.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/review/DblpMaskingSuite.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/review/RealSampleMaskingSuite.scala
FILES MODIFIED (all under /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/):
- main/scala/scholarchain/entityresolution/review/BlindView.scala
- main/scala/scholarchain/entityresolution/review/GoldMembership.scala
- main/scala/scholarchain/entityresolution/review/ReviewQueueMain.scala
- main/scala/scholarchain/entityresolution/evaluation/GoldLabels.scala
- main/scala/scholarchain/entityresolution/evaluation/GoldSetEvaluation.scala
- main/scala/scholarchain/entityresolution/evaluation/GoldSetEvalMain.scala
- test/scala/scholarchain/entityresolution/review/BlindViewSuite.scala
- test/scala/scholarchain/entityresolution/review/GoldMembershipSuite.scala
- test/scala/scholarchain/entityresolution/review/CuratedRunReaderSuite.scala
- test/scala/scholarchain/entityresolution/evaluation/GoldSetEvaluationSuite.scala
COMMANDS RUN:
- `git ls-files --others --exclude-standard -- <paths>` (3 times)
- `sbt entityResolution/test`, 4 times. Runs 1 to 3 failed and were fixed: a test compile error, the CLI seed argument in CuratedRunReaderSuite, masking rule order, and the injected `"note"` test key. Run 4 output was truncated in the middle; the per-suite XML reports I read showed no failures.
- `sbt check` once, in the foreground: success.
- Refused by the guard, nothing executed: `git grep ...` (twice), `sbt entityResolution/testQuick`, `sbt entityResolution/testOnly <suite>`.
TEST RESULTS: `sbt check` "[success] Total time: 115 s". Per project: core Passed 292, Failed 0; entityResolution Passed 236, Failed 0; hadoop Passed 67, Failed 0; tests Passed 49, Failed 0; rag, root, services, sparkAnalytics and storage had no tests. markTestRun wrote .claude/.last-test-run (git_head=1bc5382c, worktree=dirty, suites=52, passed=644, skipped=0).
VALIDATION RESULTS:
- Synthetic DBLP-shaped fixtures (invented names and pids, the documented example ORCID 0000-0002-1825-0097): no `dblp-pid`, pid fragment, person link or homonym number survives anywhere in the view, and the same check fails on the unmasked evidence.
- DOI, ORCID, affiliation, coauthors, title, venue and links are still present.
- Masking is deterministic, independent of input order, and idempotent.
- Real-data counts are in the table above; nothing needed masking.
- No gold-set evaluation was run: no human labels exist.
ASSUMPTIONS:
- The homonym rule applies to person-name fields of every source, so DBLP numbers echoed into other sources are removed too.
- Adding `eeLinks`/`urls` to the view counts as "keep publication evidence".
- The membership seed is now a required positional argument of the generator.
- The 200 minimum counts reviewed members including unsure; this replaces the earlier rule, which counted only non-unsure labels.
- Exit code 3 means "evaluated, final metrics withheld".
RISKS:
- The per-row homonym note reveals that DBLP considers some name in the pair ambiguous, but not which person.
- `masked-endpoint:` references and member IDs could in principle be brute-forced over the pid space; labelers are not adversaries.
- Masking pid values as whole values could over-mask an identical unrelated value inside the same pair.
- A real name ending in whitespace plus four digits would be shortened in the display.
- DS-P5-01 units cannot include dblp person records (`SelectionUnits` supports dblp `rec` only), so masked endpoint references appear today only in dev diagnostics.
- The real-data check exercised no masking (0 patterns present), and dblp is untested on real data.
MANUAL ACTION REQUIRED: none now. Later, once the held-out per-split run exists and `gold/**` is ratified: run membership with a seed, record the printed manifest sha256 in a human gate before labeling, and pass it as `--manifest-sha256` at evaluation.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer review of the masking patterns, the label-reference acceptance in GoldLabels, and the capacity/coverage changes (held per decisions-04 until the policy work is integrated). After that, human A/B/C and precedence decisions, FE-P5-01 ratification, the held-out per-split run, membership generation and the human freeze record.

---

## ER-P5-07: entity-resolution-engineer (final report, verbatim)

Orchestrator observations after hand-back (2026-11-06):
- `.claude/hooks/verify-tests-ran.sh --check`: OK.
- `git status --porcelain`: no paths outside the known Phase 5 set; nothing under `gold/` or
  `.claude/`.
- The sbt counts below are agent-reported.

---

ER-P5-07: er-policy.v3 is implemented and is the default policy. The final foreground `sbt check` passed: 680 tests, 0 failed. No gold-set evaluation was run because no human labels exist, so there are no precision, recall, F1 or confusion-matrix values. No core change was needed, and er-blocking.v2 and er-features.v2 are unchanged.

Two policy gaps against Option A remain open for the human (Risks 1 and 2): the deterministic title-year-authors rule and the score can still accept a preprint against its published version, and an arXiv agreement can too.

## Policy version
- `er-policy.v3`, file schema `er-policy-file.v3`, `calibrated: false`, bound to er-blocking.v2 and er-features.v2.
- er-policy.v2 is retired from active use. `er-policy-file.v2` and `er-policy.v1`/`er-policy.v2` are refused by name. The v2 file is kept as history, and a test proves it is refused, the same way v1 is.

## Per-scheme disposition table (as implemented and pinned by a test)
| Scheme | Tier | onAgreement (constant) | onDisagreement | Overridden by |
|---|---|---|---|---|
| orcid | T1 | accept (0.99) | conflict-if-plausible | nothing |
| dblp-pid | T1 | accept (0.99) | conflict-if-plausible | nothing |
| openalex-author | T2 | accept (0.90) | block-inferred-acceptance-if-plausible | T1 researcher agreement (reason code); a T2 agreement gives CONFLICT |
| s2-author | T2 | accept (0.90) | block-inferred-acceptance-if-plausible | same as openalex-author |
| doi | T1 | accept (0.99) | conflict-if-plausible | nothing |
| arxiv | T1 | accept (0.99) | conflict-if-plausible | nothing |
| dblp-key (P-2) | T2 | accept (0.99) | block-inferred-acceptance | doi/arxiv agreement (reason code); a T2 agreement gives CONFLICT |
| scopus-eid (P-2) | T2 | accept (0.99) | block-inferred-acceptance | same as dblp-key |
| openalex-work (P-1) | T2 cluster | accept-if-corroborated (0.95) | evidence-only | n/a |
| mag (P-1) | T2 cluster | accept-if-corroborated (0.95) | evidence-only | n/a |
| s2-corpus, s2-paper (P-1) | T2 cluster | accept-if-corroborated (0.95) | evidence-only | n/a |
| repository-url | T3 | evidence-only (0) | evidence-only | n/a |

**Order of evaluation** (`Resolver` Scaladoc and the `evaluationOrder` field of the policy file), as in decision-brief-01 §2.2:
- Steps 0, 1 and 2 are unchanged.
- Step 3: a blocking disagreement plus a same-tier agreement gives CONFLICT.
- Step 4: an algorithmic author disagreement on an implausible pair gives REJECTED.
- Step 5: a researcher agreement with a same-work or homonym rule gives CONFLICT.
- Step 6a: a T1 agreement gives ACCEPTED and overrides every blocking disagreement. Each override records reason code `<disposition>.<scheme>.overridden-by.<scheme>` and rule `exact-id.override.<scheme>.by.<scheme>`.
- Step 6b: a T2 agreement gives ACCEPTED. For a cluster scheme this needs corroboration; otherwise the pair is UNRESOLVED on the exact-id path, with rule `exact-id.unresolved.cluster-agreement-uncorroborated` and reason `cluster-id-agreement-uncorroborated.<scheme>`. It is never sent to the LLM and never scored.
- Steps 7 and 8: with a blocking disagreement, an inferred ACCEPTED becomes UNRESOLVED, with reason `<disposition>.<scheme>.blocks.<path>`.
- Blocking disagreements go into `conflicts` on UNRESOLVED, REJECTED and CONFLICT outcomes, never on ACCEPTED.
- Every blocking disagreement adds `record-key-disagreement.<s>` (rule `exact-id.record-key-disagreement.<s>`) or `algorithmic-id-disagreement.<s>` to every outcome.
- Record-key disagreements never trigger the plausibility test.

**P-6:** the `id.other.conflict` weight is 0.0 (its cap stays at 2).

**P-4:** the wording is now "policy confidence constant (uncalibrated)":
- in `confidenceDefinition`, which validation requires to contain that phrase;
- in the Scaladoc of `MatchPolicy`, `IdentifierRule` and `Resolver`;
- at Resolver.scala:61-64, the old "estimated probability" text is replaced.

## Corroboration definition (`workCorroboration`, `Corroboration.work`)
It applies only to cluster agreements. All four clauses must hold, and the inputs are only er-features.v2 features and the endpoint identifiers rebuilt by the er-features.v2 functions:
1. **venue:** `venue.id_agree = 1`, or `venue.name_both = 1` and `venue.name_jaccard >= 0.8`.
2. **year:** `year.both = 1` and `year.abs_diff <= 0`.
3. **title:** `title.exact = 1`, or `0.9 <= title.sim < 1`.
4. **stage:** both sides carry a preprint marker, or neither does. A marker is an arxiv ID or a DOI starting with `10.48550/`. A marker on exactly one side means preprint against published version, which is never accepted. Two preprints are allowed.

- A missing input fails its clause. Each failed clause is recorded as `corroboration-clause-failed.<clause>`.
- Rationale (in the policy file and the Scaladoc): OpenAlex, MAG and S2 merge source copies and are known to merge a preprint with its published version (7 of 8 sampled DS-P5-01 units). The policy entity is one publication at one stage, so the test is deliberately strict.
- **Per-scheme decision:** corroboration is required for openalex-work, mag, s2-corpus and s2-paper. It is not required for doi (per-version), arxiv (one preprint line; revisions are allowed), or dblp-key and scopus-eid (per-record).
- **Researcher cluster IDs** (openalex-author, s2-author) are unchanged. The stage issue does not apply to a person, and their merge failure is not addressed by stage evidence.
- All README exact-identifier rules still exist and are tested.

## Thresholds and band (unchanged, uncalibrated)
accept 0.85, reject 0.20, LLM band [0.40, 0.70]; conflict 0.99, implausible-disagreement 0.95, deterministic 0.95; agreement constants as in the table. Prompt `er-adjudication-prompt.v1`, response `er-adjudication-response.v1`; the model ID comes from `models.llmModelId` (never stored).

## Label guide text (`er-label-guide.v1`, resource src/main/resources/scholarchain/entityresolution/review/er-label-guide.v1.txt), verbatim
```
er-label-guide.v1
ScholarChain entity-resolution labeling guide

Labeling target: Option A (human decision 2026-11-06). Work pairs: labelingTarget publication-stage. Researcher pairs: labelingTarget person.
This guide is fixed before labeling. A change is a new guide version, never an edit of this one.

How to answer
- Answer every pair with exactly one of: match, non-match, unsure.
- Judge only the source evidence the blind view shows for the two endpoints.
- If the evidence shown is insufficient, choose unsure. Unsure is a valid answer; it is never replaced by another pair.
- Masked DBLP person identifiers and homonym numbers are hidden on purpose. Do not try to recover them.

Researcher pairs (labelingTarget: person)
QUESTION researcher: Do endpoint A and endpoint B refer to the same person? Answer match, non-match, or unsure.

Work pairs (labelingTarget: publication-stage)
QUESTION work: Do endpoint A and endpoint B describe the same publication entity at the same publication stage (revisions within that entity count as the same)? Answer match, non-match, or unsure.

Definition of a work match
- A work pair is a match when both records describe the same publication entity at the same publication stage.
- Publication stages: preprint; conference or workshop paper; journal article; book or book chapter; thesis or dissertation; technical report.
- Revisions within that entity at that stage are still a match: a corrected or updated version, arXiv versions v1, v2 and later of one preprint, and two records of one publication taken from different sources or catalogues.
- Records of one publication may differ in wording, metadata, formatting, author-name spelling, or identifiers. That alone does not make a non-match.
- A preprint and the published article or published conference paper of the same research are a non-match, even when the title and the authors agree.

Edge cases (M = match, N = non-match)
1. Preprint vs published version (version of record): N.
2. Extended journal version of a conference paper: N.
3. Corrected version (the same DOI updated, or arXiv vN of one preprint): M.
4. Erratum or corrigendum notice vs the article it corrects: N.
5. Retraction notice vs the retracted paper: N.
6. Retracted paper (flagged as retracted) vs another record of the same paper: M.
7. Same-title editorial, preface or introduction in a different year: N.
8. Thesis vs paper: N.
9. One publication registered under two DOIs (for example a proceedings paper also deposited as a newsletter reprint): M.
```

## Schema versions
- Changed:
  - policy file `er-policy-file.v3`;
  - blind view `er-blind-label-view.v4` (new `labelingTarget`, `labelGuideVersion`; v3 masking unchanged);
  - gold label `er-gold-label.v3` (required `labelingTarget` and `labelGuideVersion`; v1 and v2 refused as retired).
- Unchanged: review-queue row `er-review-queue-row.v3`, membership `er-gold-membership.v1`, order tag `er-blind-view-order.v2`, masking `er-dblp-display-mask.v1`.
- Membership and sampling are unchanged and still policy-independent.
- Evaluation report changes:
  - `labeledPairs` is replaced by `reviewedPairs`, `definitiveGoldLabels` and `unsurePairs`;
  - Counts `labeled` is replaced by `reviewed` and `definitive`;
  - Coverage gains `definitiveLabels` and `definitiveByKind`.

## Updated probe stratum counts (sbt entityResolution/test, run 9c5c86b3)
Unchanged from v2: 119 resolved, 0 refused, 0 untraceable.
- researcher/deterministic-bibliographic/ACCEPTED/ge-accept 86
- researcher/exact-id/ACCEPTED/ge-accept 4
- researcher/exact-id/ACCEPTED/llm-band 5
- researcher/probabilistic/ACCEPTED/ge-accept 12
- researcher/probabilistic/UNRESOLVED/llm-band 2
- work/exact-id/ACCEPTED/ge-accept 10

REJECTED, CONFLICT and the work LLM band are still empty. Unresolved rate on the samples is 2/119; this is system output, not a gold metric.

```text
STATUS: COMPLETE
OWNER: entity-resolution-engineer
WORK ORDER: ER-P5-07 (Phase 5 main): integrate the human's policy decisions (decisions-05) as er-policy.v3
FILES CREATED:
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/resources/scholarchain/entityresolution/policy/er-policy.v3.json
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/resources/scholarchain/entityresolution/review/er-label-guide.v1.txt
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/policy/Corroboration.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/review/LabelGuide.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/policy/PolicyV3Suite.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/review/LabelGuideSuite.scala
FILES MODIFIED (all under /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/; all were already untracked ER files):
- main: policy/MatchPolicy.scala, policy/Resolver.scala, review/BlindView.scala, review/ReviewQueueMain.scala, evaluation/GoldLabels.scala, evaluation/GoldSetEvaluation.scala, evaluation/GoldSetEvalMain.scala
- test: policy/ConflictPolicySuite.scala, policy/MatchPolicySuite.scala, policy/ResolverSuite.scala, adjudication/AdjudicationSuite.scala, evaluation/GoldSetEvaluationSuite.scala (contains GoldLabelsSuite), review/BlindViewSuite.scala, review/CuratedRunReaderSuite.scala, review/DblpMaskingSuite.scala, review/ReviewQueueSuite.scala, review/GoldMembershipSuite.scala
- er-policy.v1.json and er-policy.v2.json were not edited (kept as retired history). No core, hadoop, datasets, gold/**, docs/agent-runs/** or .claude file was touched.
COMMANDS RUN (all foreground; none moved to the background; no audit refusal):
- git ls-files --others --exclude-standard -- modules/entity-resolution modules/core: ok
- git ls-files -- modules/entity-resolution: ok
- git ls-files -- modules/hadoop/src/main modules/core/src/main: ok
- git ls-files --others --exclude-standard -- modules/core/src/main: ok
- git diff --stat -- modules/core: ok
- git ls-files -- tests: ok
- git ls-files --others -- modules/entity-resolution/target/test-reports (twice, one with a single file): ok
- git ls-files --others -- modules/entity-resolution/target/review-queue: ok
- sbt entityResolution/compile: success
- sbt entityResolution/test, run 1: test compile failed (DblpMaskingSuite used the old GoldLabel shape); fixed.
- sbt entityResolution/test, run 2: failures in LeakageStructureSuite (a validation message contained the word "evaluation" in policy code), AdjudicationSuite (2), ReviewQueueSuite, GoldMembershipSuite and CuratedRunReaderSuite (2). All were stale "er-policy.v2"/"view.v3" expectations or that message; fixed.
- sbt entityResolution/test, run 3: one remaining stale v2 rowId expectation in ReviewQueueSuite; fixed.
- sbt entityResolution/test, run 4: Passed: Total 272, Failed 0, Errors 0, Passed 272
- sbt check (once, foreground, 117 s): success
TEST RESULTS: sbt check, verbatim tail:
  [info] Passed: Total 292, Failed 0, Errors 0, Passed 292
  [info] Passed: Total 272, Failed 0, Errors 0, Passed 272
  [info] Passed: Total 67, Failed 0, Errors 0, Passed 67
  [info] Passed: Total 49, Failed 0, Errors 0, Passed 49
  [info] markTestRun: entityResolution: production sources=28, test sources=25, passed=272
  [info] markTestRun: wrote /Users/taufeeqpatel/Desktop/ScholarChain/.claude/.last-test-run (git_head=1bc5382c00200b76d03f6a88ffdbbac53220760e, worktree=dirty, suites=54, passed=680, skipped=0)
  [success] Total time: 117 s (0:01:57.0), completed Nov 6, 2026, 2:49:48 PM
  Earlier in the same output: "Build sources OK", "Version ledger OK: 14 pinned entries", scalafmt checks [success].
  entityResolution went from 236 to 272 tests (PolicyV3Suite 27, LabelGuideSuite 6, plus new GoldSetEvaluationSuite and GoldLabelsSuite tests).
  Required tests, each failing without its change:
  - examples 4a, 4b, 5a, 5b, 6, 7a, 7b, 8a and 8b;
  - record-key property over 5 feature profiles x {dblp-key, scopus-eid}: never CONFLICT, never REJECTED alone, never in conflicts on ACCEPTED; it relies on the P-6 zero weight;
  - a dblp-key block overridden by DOI, and a scopus-eid block overridden by arXiv, each with its reason code and rule;
  - W, MAG, s2-corpus and s2-paper: alone gives UNRESOLVED, corroborated gives ACCEPTED, preprint vs VoR is never ACCEPTED; clause bounds tested at their edges;
  - v2 is refused; the id.other.conflict weight is 0;
  - the question text equals the guide's text, with no "byte-identical" or "same text" wording;
  - labelingTarget round-trips through both the label and the blind row;
  - --provisional is refused (exit 2) on held-out input;
  - reviewed and definitive counts are printed and distinguished on every line that shows the reviewed count.
  The accepted Unicode limitation suites (FormatCharactersSuite, tests/** UnicodeSuite and FormatCharacterPipelineSuite) and all masking suites pass. RealSampleMaskingSuite counts are unchanged: 0 survivors.
VALIDATION RESULTS:
- Gold-set evaluation: NOT run, because no human labels exist. There is no precision, recall, F1, confusion matrix, false positive, false negative or threshold-sensitivity result. All metric tests use SYNTHETIC fixtures.
- Probe strata are listed above and are unchanged from v2. All 10 sample work pairs are still exact-id ACCEPTED; none was decided by a cluster ID alone.
- Every SYNTHETIC v3 outcome maps to a valid core match-decision.v2.
ASSUMPTIONS:
- The researcher labeling target is named "person". The human named only the work target; the researcher question text is unchanged.
- "Same year" means year.abs_diff <= 0. Venue similarity uses venue.name_jaccard >= 0.8, and titles need title.sim >= 0.9 when they are not exactly equal. These bounds are design choices, not tuned on data.
- An uncorroborated cluster agreement still counts as an identifier agreement for contradiction rules (steps 2 and 3), but it never decides acceptance.
- `--provisional` was removed outright. No dev-only evaluation path was added, because none existed and none was required.
- The label schema requires labelGuideVersion as well as labelingTarget.
RISKS:
1. Option A gap in inferred acceptance (needs a human decision). Under unchanged steps 7 and 8, the deterministic title-year-authors rule and a high score can accept a preprint against its published version when no DOI disagreement exists. Example: an older DBLP CoRR record without a DOI against a published copy from the same year. The stage clause is applied only to cluster IDs, as ordered. Applying it to inferred acceptance would be a further policy change.
2. arXiv agreement is not corroborated (T1, per the instruction). A published record that lists its preprint's arXiv ID would be accepted against the preprint. This is recorded in the arxiv agreementRationale.
3. Corroboration is strict (same year, same venue). Correct cross-source pairs with an online-first vs issue year, or with missing venue IDs, become UNRESOLVED. That raises the manual-decision load; the size is unknown until held-out data exists.
4. openalex-author and s2-author agreements still accept at 0.90 without corroboration (merge risk, unchanged).
5. On ACCEPTED, overridden disagreement values are not stored inline. They are recoverable from the reason code, the rule and the endpoint join.
6. The report JSON keys changed (`reviewedPairs` and related) for any downstream reader.
7. The policy remains uncalibrated.
8. The test marker was written on a dirty tree.
MANUAL ACTION REQUIRED:
- Human: decide whether the stage clause should also cap deterministic and probabilistic acceptance (Risk 1), and whether arXiv agreements need a stage check (Risk 2).
- Human: confirm the researcher target name "person" and the corroboration bounds.
- Still pending from earlier work orders: FE-P5-01 ratification (gold/** path), the goldSetEval alias, the full dblp download, and the held-out per-split run.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: TE-P5-01 (test-engineer adversarial pass), then RV-P5-04 (code-reviewer, comprehensive: v3 precedence, corroboration, README:1221/1242 reading, label guide, evaluation output). Then the human decisions on Risks 1-2, FE-P5-01 ratification, the held-out run, membership generation and blind labeling.
```

---

## DE-P5-02: domain-engineer (final report, verbatim)

Orchestrator note (2026-11-06): agent-reported `sbt core/test` only. `sbt check` was not run, per
the work order (D1, D4 and D5 were still open). The TE-P5-01 tests were not run; the agent's
pass expectation is by reading.

---

D2 and D3 are fixed in core, and `sbt core/test` passed in the foreground: 299 passed, 0 failed. I did not run `sbt check`, as the work order said, and I did not run the two TE-P5-01 cases in `tests/**`. Why they should now pass comes from reading the test code, below.

**D2 / m10: credential echo.** I chose the safer of the two options: a repository-URL value is never echoed, in whole or in part. `Identifiers.errorDisplay(RepositoryUrl, raw)` now always returns a fixed placeholder, `Identifiers.WithheldRepositoryUrl = "<repository-url withheld>"`. A null value is still shown as `null`, and other schemes are echoed unchanged. Every `MalformedId` for a repository URL goes through `errorDisplay`. That covers `ExactId.of`, `ExactId.check`, `ExactId.checkSet`, `IdentityLink`, `MatchCandidate`, `IdentifierConflict.sideCheck` and `AdjudicationReference`; the last only ever emits a fixed reason text.
- I rejected pattern-based redaction because it is the kind of fix that failed in D2: credentials can sit after leading whitespace, in a password containing `/`, in scp-like `user:pass@host:path`, in user information with no scheme, or in query parameters.
- I also rejected "scheme and host only". With a `/` in the password, `https://deploy:12345/x@host` parses as host `deploy` with port `12345`, so a numeric password would show.
- The rule is documented in the scaladoc of `errorDisplay` and `normalizeRepositoryUrl`.

**D3: query-named repositories.** I chose refusal. The URL parser now captures the query.
- A non-empty query is refused with "a query string may name the repository on this host; refused rather than risking a merge", except on a new set `Identifiers.QueryIgnoringRepositoryHosts = Set("github.com")`.
- On github.com the repository is already exactly owner/repo, so the query can be dropped safely.
- An empty `?` is dropped everywhere, and so is a fragment, since a fragment is never sent to the server.
- The canonical output form is unchanged.

**Why the two TE-P5-01 cases should now pass (from reading the test code):**
- **Credential-echo case.** All six raw values fail `ExactId.of`: a non-default port, a single segment, a percent sign in a segment, whitespace (two values), or a host of `deploy` that is not a DNS name (the `/` password). `ExactId.check` refuses each one as not canonical. All those errors now carry only the placeholder. The test's `ExactIdentifiers.normalize` reasons are core's fixed reason strings, none of which contain the raw value. For the leading-space value that function trims first, so the URL is accepted and its canonical form has no credentials. For leading tab and space, ER's `raw.trim` leaves `https://deploy:...@gitlab.example.org/group/repo`, which is accepted, so the test's `.left.foreach` does nothing.
- **Query-only case.** Both `https://git.example.org/gitweb/index.cgi?p=alpha.git` and `?p=beta.git` now return `Left`, which satisfies `alpha.isLeft || beta.isLeft || alpha != beta`.

**Contract version:** no schema bump. No wire format, frozen JSON example or canonical form changed, and stored canonical values still validate. Error messages are documented as "diagnostic only; not part of any wire format". What did change is that normalization now refuses some inputs it used to accept (queries on hosts other than github.com), and error text shows the placeholder. No selected dataset carries repository URLs, and the scheme is T3, evidence only, so no decisions change today.

**Feature tests:** I added seven to `IdentifiersSuite` and changed one existing assertion from `"<redacted>@github.com"` to the placeholder. By reasoning, each new test fails on the old code: the leading-whitespace, `/`-password, scp-like, no-scheme and query cases all bypassed the old `^scheme://` pattern, and the two D3 tests need the query refusal. I did not run them against the old code, and they would not compile there anyway because they reference the two new constants.

```text
STATUS: COMPLETE
OWNER: domain-engineer
WORK ORDER: DE-P5-02 (Phase 5 main): repair D2 and D3 in the core identifier contracts
FILES CREATED: none
FILES MODIFIED:
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/main/scala/scholarchain/core/Identifiers.scala (QueryIgnoringRepositoryHosts; query captured and refused off query-ignoring hosts; WithheldRepositoryUrl; errorDisplay never echoes repository-URL values; old UserInfo pattern removed; scaladoc updated)
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/test/scala/scholarchain/core/IdentifiersSuite.scala (1 assertion changed to the placeholder; 7 new tests)
COMMANDS RUN:
- git status --short: ok
- git ls-files --others --exclude-standard -- modules/core: ok
- git ls-files -- modules/core: ok
- git ls-files --others --exclude-standard -- modules/entity-resolution/src/main/scala/scholarchain/entityresolution/identifiers: ok
- sbt core/test (foreground, not moved to the background, no audit refusal): success
- git diff --stat on the two edited files: ok
TEST RESULTS: sbt core/test, verbatim: "[info] Passed: Total 299, Failed 0, Errors 0, Passed 299" and "[success] Total time: 4 s, completed Nov 6, 2026, 10:59:18 PM". All 25 IdentifiersSuite tests passed, including the 7 new ones. sbt check was not run (per the work order), and tests/** suites were not run.
VALIDATION RESULTS: the formatter hook rewrote both edited files after editing, with no errors. No contract or wire example changed, so no schema-version bump.
ASSUMPTIONS:
- Only github.com is safe to treat as query-ignoring; every other host may name the repository in its query.
- An empty query and a fragment can never identify a repository.
- Validation error text is diagnostic, not a frozen wire format.
RISKS:
- Repository-URL errors no longer show the offending value, which makes debugging harder; the field path and reason are still given.
- Inputs with queries on hosts other than github.com that used to normalize are now refused (no current dataset has any).
- errorDisplay echoes values of other schemes unchanged, so a credential-bearing URL wrongly given under another scheme (for example DOI) would still be echoed. Out of scope; not observed.
- Fail-without-fix was established by reasoning, not by running the new tests against the old code.
MANUAL ACTION REQUIRED: none for this repair. The human reviews the diff as usual.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: entity-resolution-engineer fixes D1, D4 and D5 (ER-P5-08) and runs the single foreground sbt check, which must show both TE-P5-01 cases passing. Then code-reviewer re-checks D2 and D3.
```

---

## ER-P5-08 (entity-resolution-engineer) — hand-back 2026-11-11, verbatim

Context (orchestrator note, not part of the report): the first run of ER-P5-08 stopped at 2026-11-11T18:01Z on an API usage limit (HTTP 429) without a report. It was resumed by SendMessage after the reset; the report below is from the resumed run.

ER-P5-08 is not finished. Almost all the code changes are written, but `sbt check` has not run, so nothing is compiled or tested. I stopped because the audit hook refused a Bash call and told me to stop; per your instruction I made no further calls.

**The refusal, verbatim (`audit-bash-writes.sh --pre`, on my `git status --porcelain` after resuming):**
```
INTER-CALL CHANGE detected by .claude/hooks/audit-bash-writes.sh (UNATTRIBUTED)
These git-visible paths changed AFTER the previous Bash call of this session ended and BEFORE this
call, without an audited Write/Edit (for example a background or delayed process started by test
code, or an edit made outside Claude Code). No identity is credited with the change:
  - modules/entity-resolution/src/test/scala/scholarchain/entityresolution/evaluation/GoldSetEvaluationSuite.scala (owner: entity-resolution-engineer)
This Bash call was refused. The change was NOT reverted. Logged in: /var/folders/zt/pbpscx0s72b8jch7_wr7gc9m0000gn/T/scholarchain-bash-audit-501/session-31ac882037fa1279f7fc3022ee76cd91.incidents.log
Do: stop. Return STATUS: BLOCKED-ON with BLOCKED-ON OWNER: human ...
```
The flagged file is my own test file. I edited it with Edit just before the usage-limit interruption, and the formatter hook then rewrote it. My best guess is that the interruption meant `post-write.sh` never recorded that write in the baseline, but I have not verified this. No sbt or other test process ran in this work order.

**State on resume:** before writing anything I checked `git status --porcelain` and the module file list. No `er-policy.v4.json` or `er-adjudication-prompt.v2.txt` existed, so none of my earlier edits had landed. Everything below was written in this session.

### What is implemented (written, not compiled or tested)

**Versions**
- Policy: `er-policy.v4`, file schema `er-policy-file.v4`.
  - `er-policy.v3` and `er-policy-file.v3` are retired. The v3 file is kept as history and refused, like v1 and v2.
  - New required block `publicationStage {definition, rationale}`. Validation requires the definition to name all five verdicts.
- Prompt: `er-adjudication-prompt.v2` is active.
  - `er-adjudication-prompt.v1` is retired: a template declaring it is refused when parsed, and so is a policy naming it.
  - The v1 file is kept, under `AdjudicationProtocol.RetiredV1PromptResource`.
  - The response schema stays `er-adjudication-response.v1`.
- Blind view: `er-blind-label-view.v5`. Masking rules are `er-dblp-display-mask.v2` plus the new `er-openalex-display-mask.v1`.
- Label guide: `er-label-guide.v1`, unchanged. Label schema: `er-gold-label.v3`, unchanged.

**Stage check (`PublicationStage` in `Corroboration.scala`)**
- Inputs: the endpoint identifiers rebuilt by the er-features.v2 functions, plus the er-features.v2 venue features. It reuses `workCorroboration.preprintDoiPrefixes` (`10.48550/`) and `venueNameJaccardMin` (0.8).
- Each side is classified as:
  - `preprint`: an arXiv ID or a preprint DOI, and no published DOI;
  - `published`: a non-preprint DOI and no preprint marker;
  - `mixed`: both;
  - `unknown`: neither.
- Verdicts that establish the same stage:
  - `same-stage-preprint`: both sides `preprint`;
  - `same-stage-published`: both sides `published`;
  - `same-stage-venue`: no preprint marker on either side, and the venue clause holds.
- Verdicts that do not:
  - `cross-stage`: exactly one side carries a preprint marker;
  - `uncertain`: every other case.
- Where the verdict is used:
  - **Deterministic rule and score (steps 7 and 8):** if the stage is not established, an ACCEPTED becomes UNRESOLVED on the same path.
    - Rule: `<path>.accept-blocked-by-publication-stage`.
    - Reasons: `publication-stage-cross-stage` or `publication-stage-uncertain`, plus `acceptance-blocked-by-publication-stage`.
    - When a blocking identifier disagreement also applies, its reason codes are recorded as well.
  - **LLM band:** a work pair whose stage is not established is not band-eligible (reason `llm-band-excluded-by-publication-stage`). `Adjudication.eligible` also refuses any work outcome without an established stage, including forged copies.
  - **arXiv agreement:** it decides only for `same-stage-preprint`.
    - Otherwise it does not decide (rule `exact-id.unresolved.arxiv-agreement-cross-stage`, reason `arxiv-agreement-cross-stage`). The pair is UNRESOLVED on the exact-id path, never inferred, and never sent to the LLM.
    - It still counts as an agreement for the T1-disagreement step, so a DOI disagreement plus a shared arXiv ID stays CONFLICT.
    - A DOI agreement still accepts. The README exact-identifier rule for arXiv remains, explicit.
  - **Cluster corroboration:** its stage clause is now "both sides pure preprints, or no marker on either side". This tightens one case: a `mixed` side now fails the clause.

**D1, D4, D5**
- **D1:** `GoldLabels.ingest` accepts a label only if it is exactly `match`, `non-match` or `unsure`. Nothing is trimmed or case-folded, refusal stays all-or-nothing, and the refused value is shown with whitespace and control characters escaped. `GoldSetEvaluation.evaluate` also refuses any non-canonical label built in code, so reviewed, definitive and unsure counts and every cell come from the same values.
- **D4:** the homonym-number pattern is now `(?U)[\s\p{Z}\p{Cf}]+[0-9]{4}(?=[\s\p{Z}\p{Cf}]*(?:[,;/]|$))`. This is display only: the NIT-2 parsing limitation is unchanged, and a test pins that.
- **D5:** new `DisplayDecoding`, a total UTF-8 percent-decoder used for detection only (ASCII hex, at most 8 layers, `+` left alone). The DBLP person-token and spaced-pid rules check every decoded layer and mask the whole original token. Pid values found in decoded layers are masked elsewhere in the pair.

**m9 masking patterns (`OpenAlexMasking`, one constant marker `[masked: OpenAlex cluster identifier]`)**
- A token is masked when the token or any decoded layer contains `openalex` and an A or W ID as a whole value (`(?i)[AW][0-9]{4,15}`). This covers `openalex:A…`, `openalex-author:A…`, `openalex-work:W…`, `work:openalex:W…`, `source-author:openalex:A…`, `openalex.org/…`, `api.openalex.org/…`, filter URLs and encoded links.
- Every A or W value found that way, and the record ID of an OpenAlex endpoint, is then masked wherever it occurs as a whole value.
- OpenAlex work and profile endpoint IDs are shown as `masked-endpoint:` references, which label ingestion accepts after recomputing them from the manifest.
- A new row note names the removal.
- Kept visible: DOIs, ORCIDs, institution IDs (including OpenAlex `I…`) and MAG IDs.

**m4 and m5**
- **m4:** a held-out evaluation that meets the 200 minimum without `--manifest-sha256` prints coverage and a refusal, then exits 2. Below the minimum the behaviour is unchanged (exit 3, metrics withheld).
- **m5:**
  - The split is read from the manifest. An empty manifest, mixed splits, or a split other than heldout or dev is refused.
  - `split` is a field in both the report JSON and the coverage JSON.
  - Output starts with a split header line, and every other text line ends with ` split=<s>`.
  - On a dev manifest every line is prefixed `DEV`, and the status is `dev-diagnostic-not-final`.

### What is not done
- `PolicyV4Suite` (stage-target feature tests for the deterministic, score and arXiv paths) was not written. The LLM-path stage tests (in AdjudicationSuite) are written, and so are the D1, D4, D5, m4, m5 and m9 tests.
- The single foreground `sbt check` was not run, so there are no counts. Nothing is compiled.
- The obsolete characterization tests are not confirmed. From reading the tests, I expect these tests/** tests to fail because of the human's policy and version change (unobserved):
  - `ClusterCorroborationAdversarialSuite`, "OPEN POLICY GAP 1 …": asserts ACCEPTED on the deterministic and probabilistic paths for preprint against published. Under v4 this is UNRESOLVED.
  - `ClusterCorroborationAdversarialSuite`, "OPEN POLICY GAP 2 …": asserts ACCEPTED at 0.99 for a shared arXiv ID between a preprint and a published record. Under v4 this is UNRESOLVED with `arxiv-agreement-cross-stage`.
  - `MatchContractCrossModuleSuite`, "every Resolver outcome kind maps to …": asserts `policyVersion == er-policy.v3`. This is a version pin.
  - `MatchContractCrossModuleSuite`, "config band ordering …": `tight` uses config `er-policy.v3` with prompt v1 and expects Right. It is now Left because of the version mismatch.
  - `MatchContractCrossModuleSuite`, "config loader: a valid v2 file feeds withConfig …": the HOCON names v3 and prompt v1.
  - `MatchContractCrossModuleSuite`, "policy versioning: v3 is the default …": a version pin.
  - `AdjudicationAdversarialSuite`, "an exact-id evidence reference must match …": its repository-URL band pair has no stage markers and no venue, so its stage is uncertain and it is no longer band-eligible.

  The m7 characterization should be unaffected, because two versions of one arXiv ID are still not an agreement. The five D1–D5 regression tests are expected to pass through the fixes; none was edited or weakened.

### Report contract
```text
STATUS: BLOCKED-ON
OWNER: entity-resolution-engineer
WORK ORDER: ER-P5-08 (Phase 5 main): focused repairs (D1, D4, D5), Option A stage target (er-policy.v4, er-adjudication-prompt.v2), m9, m4, m5
FILES CREATED:
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/resources/scholarchain/entityresolution/policy/er-policy.v4.json
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/resources/scholarchain/entityresolution/adjudication/er-adjudication-prompt.v2.txt
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/review/DisplayDecoding.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/review/OpenAlexMasking.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/review/OpenAlexMaskingSuite.scala
FILES MODIFIED (all under /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/):
- main: policy/Corroboration.scala, policy/MatchPolicy.scala, policy/Resolver.scala, adjudication/Adjudication.scala, adjudication/AdjudicationProtocol.scala, evaluation/GoldLabels.scala, evaluation/GoldSetEvaluation.scala, evaluation/GoldSetEvalMain.scala, review/DblpMasking.scala, review/BlindView.scala
- test: policy/MatchPolicySuite.scala, policy/ResolverSuite.scala, policy/PolicyV3Suite.scala, adjudication/AdjudicationSuite.scala, evaluation/GoldSetEvaluationSuite.scala, review/BlindViewSuite.scala, review/DblpMaskingSuite.scala, review/RealSampleMaskingSuite.scala, review/ReviewQueueSuite.scala, review/GoldMembershipSuite.scala, review/CuratedRunReaderSuite.scala, review/LabelGuideSuite.scala
- not touched: er-policy.v1/v2/v3.json, er-adjudication-prompt.v1.txt, tests/**, core, gold/**, docs/agent-runs/**, hooks, settings, test marker
COMMANDS RUN:
- git status --porcelain (start): ok
- git ls-files --others --exclude-standard -- modules/entity-resolution tests/src/test/scala/scholarchain/tests/er: ok
- git ls-files -- modules/entity-resolution: ok
- git ls-files --others --exclude-standard --modified -- modules/core/src/main: ok
- two git ls-files calls with glob arguments: refused by guard-dangerous.sh (shell-syntax-gate); nothing ran
- git status --porcelain (after resume): REFUSED by audit-bash-writes.sh --pre (UNATTRIBUTED), quoted above
- sbt check: NOT run
TEST RESULTS: none. sbt check was not run; no counts exist for this work order.
VALIDATION RESULTS: no gold-set evaluation (no human labels exist). Code is written but not compiled or tested.
ASSUMPTIONS:
- An unknown-stage side with venue agreement establishes the same stage (same-stage-venue); with no venue agreement the stage is uncertain.
- A side with both an arXiv ID and a published DOI ("mixed") is of uncertain stage, including for cluster corroboration (a tightening from v3).
- m4 applies only when final held-out metrics would be printed; below the minimum, exit 3 stays as before.
- No repository config file pins er-policy.v3 or prompt v1 (I could not check; the glob search was refused).
- The arXiv rule's scope text keeps the v3 wording "versions vN are compared as one value" (m7 is out of scope and unchanged).
RISKS:
- Possible compile or test failures in the uncompiled code.
- Under v4 almost every inferred work acceptance becomes UNRESOLVED unless the venue agrees or both sides are preprints, so the manual-decision load rises.
- OpenAlex masking can over-mask a coincidental A/W-shaped value inside an OpenAlex-bearing token.
- Masked references may make labelers rely more on titles and names.
MANUAL ACTION REQUIRED:
- Human: review the incident log, git status and git diff for GoldSetEvaluationSuite.scala (my own edit; the formatter hook rewrote it after the Edit). The baseline is already reset.
- Then rerun me, or run one foreground `sbt check` in a Terminal.
BLOCKED-ON OWNER: human
BLOCKED-ON EVIDENCE: audit-bash-writes.sh --pre UNATTRIBUTED refusal for modules/entity-resolution/src/test/scala/scholarchain/entityresolution/evaluation/GoldSetEvaluationSuite.scala; incident log /var/folders/zt/pbpscx0s72b8jch7_wr7gc9m0000gn/T/scholarchain-bash-audit-501/session-31ac882037fa1279f7fc3022ee76cd91.incidents.log
NEXT GATE:
1. Human review of the flagged change.
2. Resume ER-P5-08: add PolicyV4Suite (stage-target tests for the deterministic, score and arXiv paths), then one foreground sbt check with verbatim counts and a confirmed list of obsolete tests/** tests.
3. Route the obsolete characterization and version-pin tests to test-engineer.
4. code-reviewer re-check.
```

---

## ER-P5-08 (entity-resolution-engineer) — final hand-back after human approval (recovery-03), verbatim

`sbt check` ran once in the foreground and exited 1. All 302 entity-resolution tests pass, and all five D1–D5 regression tests pass unchanged. Seven tests in tests/** fail. Each one was written to the pre-decision behaviour: either the old Option A gaps or a hard-coded `er-policy.v3` / prompt v1. None is a new defect.

Because `sbt check` failed, the test marker was not advanced. The Stop hook (`verify-tests-ran.sh`) therefore refused my completion, listing 26 entity-resolution source files newer than the marker. I did not touch the marker, and I can't make the run pass inside my own files, so I'm returning BLOCKED-ON test-engineer.

The console output was cut off, so I can't quote the per-project totals verbatim. The counts below come from the per-suite JUnit reports. I made no second run, because there were no compile errors.

### Results
| Project | Suites | Passed | Failed |
|---|---|---|---|
| entityResolution | 26 | 302 | 0 |
| tests/er (TE-P5-01 suites) | 7 | 64 | 7 |
| core, hadoop, tests/adversarial | not read | not observed | not observed |

New suites: PolicyV4Suite 11/11 and OpenAlexMaskingSuite 5/5.

The five regression tests:
- **D1:** MembershipEvaluationAdversarialSuite passed 13/13, including "a whitespace-padded label is either refused at ingest or counted as that label (RV-P5-04 M1)".
- **D4 and D5:** BlindViewLeakageAdversarialSuite passed 8/8, including "a homonym number behind non-ASCII spacing is still masked" and "a percent-encoded dblp person URL embedded in another link is masked".
- **D2 and D3** (fixed by DE-P5-02): both MatchContractCrossModuleSuite tests passed.

ExactIdPrecedenceAdversarialSuite (10/10) and UnicodeNormalizationRegressionSuite (12/12) also passed.

### The 7 failing tests/** tests (route to test-engineer)
1. **ClusterCorroborationAdversarialSuite**, "OPEN POLICY GAP 1 (characterization, awaiting human decision): a preprint without a cluster ID is ACCEPTED…". Line 220 expects ACCEPTED; the result is UNRESOLVED on the deterministic path. The human decided a preprint against its published version stays UNRESOLVED, so this gap is now closed.
2. **ClusterCorroborationAdversarialSuite**, "OPEN POLICY GAP 2 (characterization…): a published record listing its preprint's arXiv ID is ACCEPTED…". Line 246 expects (ACCEPTED, exact-id, 0.99); the result is (UNRESOLVED, exact-id, 0.0). The human decided a shared arXiv ID alone must not merge a preprint with a published version.
3. **MatchContractCrossModuleSuite**, "every Resolver outcome kind maps to a valid match-decision.v2 that round-trips byte-stably". Line 106 pins `er-policy.v3`; the result is `er-policy.v4`.
4. **MatchContractCrossModuleSuite**, "config band ordering reject < bandLower < bandUpper < accept is strict; withConfig refuses breaks". Line 225 expects Right, but the test config names `er-policy.v3`, so it is refused: "configured entityResolution.policyVersion 'er-policy.v3' differs from the policy 'er-policy.v4'".
5. **MatchContractCrossModuleSuite**, "config loader: a valid v2 file feeds withConfig; env overrides that break the band are refused; v1 is refused". Line 257 fails because the test's config text names `er-policy.v3` and prompt v1.
6. **MatchContractCrossModuleSuite**, "policy versioning: v3 is the default; v1 and v2 are refused by file schema and by name". Line 283 pins v3 as the default.
7. **AdjudicationAdversarialSuite**, "an exact-id evidence reference must match the candidate's agreement byte for byte". Line 264 expects its pair to be eligible for the LLM. That pair has no stage markers and no venue, so its stage is uncertain, and the human decided uncertain-stage pairs are not sent to the LLM. The fix is a fixture with an established stage (for example adding `venue.id_agree = 1`); the byte-for-byte check itself still holds.

The m7 characterization test and the ER-P5-07 Risk 4 characterization test both still pass, as expected.

### What was done in this resume
- **PolicyV4Suite** covers:
  - cross-stage and uncertain-stage pairs going to UNRESOLVED on the deterministic and score paths, each with its own reason code;
  - an established stage (two preprints, two published DOIs, or the same venue) still accepting;
  - the arXiv agreement deciding only when both sides are pure preprints;
  - a shared arXiv ID between a preprint and a published record never ACCEPTED alone, including when a record-key disagreement is present;
  - a DOI disagreement plus a shared arXiv ID staying CONFLICT, while a DOI agreement still accepts;
  - researcher pairs unaffected;
  - v3 and prompt v1 refused;
  - every outcome producing a valid core decision.
- **Invisible test characters:** in GoldSetEvaluationSuite (D1 values) and DblpMaskingSuite, U+00AD and U+200B are now built from code points instead of literal invisible characters.

### Versions and definitions
- **Versions:**
  - policy `er-policy.v4` (file schema `er-policy-file.v4`); v3 retired and refused;
  - prompt `er-adjudication-prompt.v2`; v1 retired and refused;
  - blind view `er-blind-label-view.v5`, with masking rules `er-dblp-display-mask.v2` and `er-openalex-display-mask.v1`;
  - label guide `er-label-guide.v1` and gold-label schema `er-gold-label.v3`, both unchanged.
- **Stage check:** each work endpoint is classed from its own identifiers as preprint, published, mixed, or unknown.
  - Same stage is established by: two preprints, two published records, or no preprint marker on either side plus a matching venue.
  - It is not established when exactly one side carries a preprint marker (cross-stage) or in any other case (uncertain).
  - It gates the deterministic rule, the score, LLM eligibility, and arXiv agreements; the cluster-ID corroboration uses the same notion.
- **D1:** labels must be exactly `match`, `non-match` or `unsure` at ingest. Evaluation also refuses any non-canonical label.
- **D4:** homonym numbers are masked after any Unicode separator (`\p{Z}`) or format character (`\p{Cf}`). The NIT-2 parser limitation is unchanged.
- **D5:** every percent-decoded layer of a token is checked (nested encodings too), and the whole original token is masked if any layer names a DBLP person.
- **Masking patterns added (m9):** tokens that contain `openalex` plus an A or W ID, including percent-encoded ones, become one constant marker. Every A/W value found that way is then masked wherever it occurs as a whole value. OpenAlex work and profile endpoint IDs are shown as `masked-endpoint:` references. DOIs, ORCID iDs, institution IDs and MAG IDs stay visible.
- **m4:** held-out final metrics are refused (exit 2) without `--manifest-sha256`.
- **m5:** the split, read from the manifest, appears on every output line and in both JSON reports. A dev manifest is marked DEV on every line and is never reported as final.

```text
STATUS: BLOCKED-ON
OWNER: entity-resolution-engineer
WORK ORDER: ER-P5-08 (Phase 5 main): D1, D4, D5 repairs; Option A stage target (er-policy.v4, er-adjudication-prompt.v2); m9; m4; m5
FILES CREATED:
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/resources/scholarchain/entityresolution/policy/er-policy.v4.json
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/resources/scholarchain/entityresolution/adjudication/er-adjudication-prompt.v2.txt
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/review/DisplayDecoding.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/review/OpenAlexMasking.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/review/OpenAlexMaskingSuite.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/policy/PolicyV4Suite.scala
FILES MODIFIED (all under /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/):
- main: policy/Corroboration.scala, policy/MatchPolicy.scala, policy/Resolver.scala, adjudication/Adjudication.scala, adjudication/AdjudicationProtocol.scala, evaluation/GoldLabels.scala, evaluation/GoldSetEvaluation.scala, evaluation/GoldSetEvalMain.scala, review/DblpMasking.scala, review/BlindView.scala
- test: policy/MatchPolicySuite.scala, policy/ResolverSuite.scala, policy/PolicyV3Suite.scala, adjudication/AdjudicationSuite.scala, evaluation/GoldSetEvaluationSuite.scala, review/BlindViewSuite.scala, review/DblpMaskingSuite.scala, review/RealSampleMaskingSuite.scala, review/ReviewQueueSuite.scala, review/GoldMembershipSuite.scala, review/CuratedRunReaderSuite.scala, review/LabelGuideSuite.scala
- not touched: er-policy.v1/v2/v3.json, er-adjudication-prompt.v1.txt, tests/**, core, gold/**, docs/agent-runs/**, hooks, settings, test marker
COMMANDS RUN:
- git status --porcelain (resume 1): REFUSED by audit-bash-writes.sh --pre (UNATTRIBUTED, GoldSetEvaluationSuite.scala); reported; the human approved keeping the edit
- git status --porcelain (resume 2): ok, no refusal
- sbt check (once, foreground, not moved to the background): exit 1; console output truncated
- git ls-files --others -- modules/entity-resolution/target/test-reports tests/target/test-reports: ok (JUnit XML files read with Read)
TEST RESULTS (JUnit XML; console totals were truncated):
- entityResolution: 26 suites, 302 passed, 0 failed, 0 skipped (PolicyV4Suite 11, OpenAlexMaskingSuite 5, PolicyV3Suite 27, ConflictPolicySuite 23, MatchFeaturesSuite 21, GoldSetEvaluationSuite 20, FormatCharactersSuite 18, BlockingKeysSuite 17, MatchPolicySuite 17, AdjudicationSuite 17, ResolverSuite 15, GoldMembershipSuite 15, BlindViewSuite 14, DblpMaskingSuite 11, NamesSuite 10, ExactIdentifiersSuite 10, ReviewQueueSuite 9, TextFoldingSuite 9, GoldLabelsSuite 8, LeakageStructureSuite 7, LabelGuideSuite 6, TitleMarkupSuite 5, CuratedRunReaderSuite 4, RealSampleMaskingSuite 1, SampleQueueProbeSuite 1, StrictJsonSuite 1)
- tests/er: 71 tests, 64 passed, 7 failed (ClusterCorroboration 9/2 failed, MatchContractCrossModule 10/4 failed, AdjudicationAdversarial 9/1 failed, BlindViewLeakage 8/0, MembershipEvaluation 13/0, ExactIdPrecedence 10/0, UnicodeNormalizationRegression 12/0)
- core, hadoop, tests/adversarial: not observed
- D1–D5 regression tests: all pass, none edited
VALIDATION RESULTS: no gold-set evaluation (no human labels exist); all metric tests use synthetic fixtures. RealSampleMaskingSuite passed on the 171 real records: no DBLP pattern and no OpenAlex A/W ID survives masking.
ASSUMPTIONS:
- A side with no stage markers plus a matching venue establishes the same stage; with no matching venue the stage is uncertain.
- A side carrying both an arXiv ID and a published DOI ("mixed") is of uncertain stage, also for cluster-ID corroboration (a tightening from v3).
- m4 applies only where final held-out metrics would be printed; below the minimum, exit 3 is unchanged.
- MAG IDs stay visible in the blind view (the instruction named only A and W IDs).
- The arXiv scope text keeps the v3 wording, since m7 is out of scope.
RISKS:
- The test marker is stale until a passing sbt check, so the Stop hook keeps refusing completion.
- Under v4 most inferred work acceptances become UNRESOLVED unless the venue matches or both sides are preprints, which raises the manual-decision load.
- OpenAlex masking can over-mask a coincidental A/W-shaped value inside a token that also contains "openalex".
- The policy remains uncalibrated.
MANUAL ACTION REQUIRED: route the 7 listed tests/** tests to test-engineer; then a single foreground sbt check (one agent, or the human in a Terminal), which should then pass and refresh the marker.
BLOCKED-ON OWNER: test-engineer
BLOCKED-ON EVIDENCE: the JUnit failures listed above (ClusterCorroborationAdversarialSuite:220, :246; MatchContractCrossModuleSuite:106, :225, :257, :283; AdjudicationAdversarialSuite:264). Stop hook verify-tests-ran.sh refused completion: 26 entity-resolution sources newer than .claude/.last-test-run, because sbt check failed on those tests/** tests.
NEXT GATE: test-engineer updates the 7 tests, then a foreground sbt check (expected 0 failures; markTestRun refreshes the marker), then the code-reviewer re-check of D1–D5, Option A, m4, m5 and m9.
```

---

## ER-P5-09 (entity-resolution-engineer) — hand-back 2026-11-11, verbatim

Orchestrator note (not part of the report): routed per decisions-07.md. Orchestrator observation after hand-back: the background task output `b3j0rixf1.output` ends at line 1090 ("parser: multiline title with inline markup collapses whitespace; ISO-8859-1 input decodes"); no completion line. The harness reports the agent "waiting on its own background work". See recovery-04.md.

ER-P5-09: the fix and the er-policy.v5 bump are written, and the new tests pass in my module run. But the one `sbt check` never finished: it moved to the background after 180 s and its output has been stuck at line 1091 (hadoop DblpMappingSuite) through four reads. So `sbt check` has not passed, the test marker was not refreshed, and the Stop hook keeps refusing completion. A human needs to stop that job and rerun `sbt check` once.

STATUS: BLOCKED-ON
OWNER: entity-resolution-engineer
WORK ORDER: ER-P5-09 (Phase 5). RV-P5-05 finding 1 (MAJOR), human decision decisions-07 Option 1. A DOI agreement whose shared DOIs are all preprint DOIs decides only when both records are pure preprints. Otherwise the pair is UNRESOLVED on the exact-id path, and no inferred, score or LLM path may accept it.

FILES CREATED:
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/resources/scholarchain/entityresolution/policy/er-policy.v5.json
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/policy/PolicyV5Suite.scala

FILES MODIFIED:
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/policy/Resolver.scala
  - New `doiPreprintOnly` / `doiBlocked` logic, a `blockedSchemes` filter on `accepting`, and the private `isPreprintDoi` (prefixes are read from `p.workCorroboration.preprintDoiPrefixes`).
  - New rule `Rules.PreprintDoiCrossStage` = `exact-id.unresolved.preprint-doi-agreement-cross-stage` and reason `Rules.Reason.PreprintDoiCrossStage` = `preprint-doi-agreement-cross-stage`, recorded together with `publication-stage-<verdict>`.
  - Scaladoc updated for v5.
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/policy/MatchPolicy.scala
  - `FileSchemaVersion` is now er-policy-file.v5 and `DefaultResource` points to er-policy.v5.json.
  - er-policy-file.v4 and er-policy.v4 are added to the retired sets; `RetiredV4Resource` is new.
- Version pins changed from v4 to v5 only, no assertion weakened, all under /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/:
  - policy/MatchPolicySuite.scala (lines 16, 138, 179)
  - policy/PolicyV3Suite.scala (76-78)
  - policy/PolicyV4Suite.scala (54-55)
  - policy/ResolverSuite.scala (139, 213)
  - adjudication/AdjudicationSuite.scala (121, 278)
  - review/ReviewQueueSuite.scala (67, 77)
  - review/CuratedRunReaderSuite.scala (163, 168)
  - review/GoldMembershipSuite.scala (154)
- er-policy.v4.json is unchanged: it is kept as history and is now refused.

COMMANDS RUN:
1. Read-only git commands: `git ls-files` (several), `git diff --stat`, `git status --porcelain`. One shell `grep` was refused by the guard (shell-syntax-gate); nothing ran.
2. `sbt entityResolution/test` (run 1). Exit 1. Failures were v4 pins: ResolverSuite, AdjudicationSuite (2), ReviewQueueSuite, CuratedRunReaderSuite, GoldMembershipSuite. All 10 PolicyV5Suite tests passed.
3. `sbt entityResolution/test` (run 2). Exit 1. Two remaining v4 pins: ResolverSuite:213 and ReviewQueueSuite:77. I fixed both afterwards but did not rerun the module.
4. `sbt check` (the single run). Moved to the background after 180 s as task b3j0rixf1. Output: /private/tmp/claude-501/-Users-taufeeqpatel-Desktop-ScholarChain/107a6780-f081-4d82-af1b-11c6ff34610e/tasks/b3j0rixf1.output, stuck at line 1091 (hadoop DblpMappingSuite, all passing up to that point). No completion was observed. The tests/target JUnit reports still have the old 16:39 timestamp, so the tests project never ran in this invocation.

TEST RESULTS:
- `sbt check`: not completed. No counts exist; no pass is claimed.
- entityResolution run 2, read from the JUnit XML headers in modules/entity-resolution/target/test-reports, plus console lines for TextFoldingSuite (9) and FormatCharactersSuite (18): 312 tests, 2 failures. Both failures are the version pins fixed afterwards (not rerun).
- PolicyV5Suite 10/10 passed in both runs:
  - (a) the published record listing its own DOI and the preprint DOI against the preprint record is UNRESOLVED on exact-id with the new rule and reasons, in both orientations. This holds across 7 feature profiles: empty, inside the LLM band (checked), strong, strong plus same venue, exact bibliographic, exact plus venue, and full corroboration. No deterministic or probabilistic rule fires, it is not band-eligible, and `Adjudication.eligible` is false. The stage is uncertain.
  - (a) Variants: a preprint carrying only the preprint DOI; a mixed record that also lists the arXiv ID (both agreements blocked); a dblp-key block (not overridden).
  - (b) Two preprints sharing a preprint DOI are ACCEPTED on exact-id at 0.99, and a shared preprint DOI between two preprints still overrides a dblp-key block.
  - (c) Shared publication DOI, and shared publication plus preprint DOI, are ACCEPTED, including the mixed/mixed and mixed/published cases.
  - (d) An arXiv T1 disagreement next to a shared preprint DOI (pure and mixed sides) is CONFLICT, with the DOI recorded as a ContradictedAgreement. A disjoint-DOI case is CONFLICT.
  - The prefixes come from config: with empty prefixes the pair accepts, and with prefix 10.9999/ it blocks.
  - Version tests: v5 is the default, v4 is refused by file schema, by name and through `withConfig`.
- Every PolicyV5Suite test except the version and policy-text tests would fail without the fix, which accepted these pairs at 0.99.

VALIDATION RESULTS:
- Policy version: er-policy.v5 (file schema er-policy-file.v5); v4 retired like v3.
- Prompt version: unchanged at er-adjudication-prompt.v2. The prompt text did not change, and the new path is exact-id, which never reaches the LLM.
- Features er-features.v2 and blocking er-blocking.v2 are unchanged. Thresholds are unchanged: accept 0.85, reject 0.2, band [0.4, 0.7].
- The inaccurate DOI rationale ("is already publication-stage evidence") is corrected in the v5 text, along with the publicationStage definition and evaluationOrder 2, 6a and 6c.
- No gold labels exist, so no gold-set evaluation was run (the goldSetEval alias is inactive). There is no confusion matrix or unresolved rate to report.
- Expected tests/** failures, predicted from reading MatchContractCrossModuleSuite.scala but not observed. Route to test-engineer; these are expected version pins, not defects:
  1. "every Resolver outcome kind maps to a valid match-decision.v2..." (line 142 pins policyVersion er-policy.v4).
  2. "config band ordering ... withConfig refuses breaks" (positive control at line 257 uses activePolicy = er-policy.v4, line 224; line 258 expects er-policy.v5 to be refused).
  3. "config loader: a valid v2 file feeds withConfig..." (line 313, v4 configuration).
  4. "policy versioning: v4 and prompt v2 are the default..." (lines 350 and 355 pin DefaultResource er-policy.v4.json; line 374 replaces "er-policy-file.v4").
- I did not read the other tests/er suites (ClusterCorroboration, ExactIdPrecedence, AdjudicationAdversarial, ErFixtures, Membership). They may hold further v4 pins, or tests that expect a shared preprint DOI on a mixed record to be ACCEPTED. Those would be expected behaviour changes under decisions-07.

ASSUMPTIONS:
- An independent identifier agreement may still decide when the preprint DOI is blocked, for example a shared dblp-key (same DBLP record) or scopus-eid. This mirrors the v4 arXiv rule. "Fallback path" is read as the inferred, score and LLM paths.
- Point (d) "a T1 DOI disagreement next to a shared preprint DOI" cannot occur, because DOI comparison is set intersection: a shared DOI means the DOI scheme agrees. I tested it with an arXiv T1 disagreement next to a shared preprint DOI, plus a separate disjoint-DOI CONFLICT control.
- `sbt entityResolution/test` (own-module task) is not the forbidden second `sbt check`.

RISKS:
- Characterization case: two mixed records with different publication DOIs (doi1 and doi2) and one shared preprint DOI were ACCEPTED under v4 and are now UNRESOLVED, not CONFLICT. A per-stage DOI comparison would be a further policy decision.
- RV-P5-05 finding 2 is left open. It is not trivial: relating an arXiv ID to its 10.48550 DOI changes agreement semantics and breaks `EndpointIdentifiers.inconsistencies` against Hadoop-built candidates.
- The final module rerun after the last two pin fixes was not observed.

MANUAL ACTION REQUIRED:
- Human: stop background task b3j0rixf1 (stalled `sbt check`), then run one foreground `sbt check` from the repo root, or authorize the orchestrator or me to run it again. The 180 s auto-background limit plus the large Hadoop log volume (build-engineer item) is the likely cause.
- After that run: route the tests/** version pins listed above to test-engineer, then focused re-review (RV-P5-06).

BLOCKED-ON OWNER: human
BLOCKED-ON EVIDENCE: `sbt check` (task b3j0rixf1) moved to the background at 180 s. /private/tmp/claude-501/-Users-taufeeqpatel-Desktop-ScholarChain/107a6780-f081-4d82-af1b-11c6ff34610e/tasks/b3j0rixf1.output stayed at 1091 lines, ending at hadoop DblpMappingSuite "parser: multiline title with inline markup collapses whitespace; ISO-8859-1 input decodes", across four reads with no completion notice. The tests/target/test-reports JUnit files are timestamped 2026-11-11T16:39, before this run. The Stop hook (verify-tests-ran.sh) refuses completion because 11 changed sources are newer than .claude/.last-test-run. The work order forbids a concurrent or second sbt run, and I have no tool to stop the job.
NEXT GATE: one completed foreground `sbt check` with per-project counts. Then test-engineer updates the tests/** v4 to v5 pins, and code-reviewer does a focused re-review of finding 1 (RV-P5-06).

---

## ER-P5-10 (entity-resolution-engineer) — hand-back 2026-11-11, verbatim

Orchestrator note (not part of the report): routed per decisions-08.md; the work order excluded a full `sbt check` and allowed at most two `sbt entityResolution/test` runs.

ER-P5-10 is done. Under the new policy er-policy.v6, a shared dblp-key or scopus-eid no longer overrides the preprint-DOI block. The RV-P5-06 input (A = {k, 10.48550/arxiv.X} vs B = {k, 10.1145/y, 10.48550/arxiv.X}) now stays UNRESOLVED on the exact-id path with confidence 0.0. Before this change it was ACCEPTED at 0.99. One `sbt entityResolution/test` run passed: Total 324, Failed 0. `sbt check` was not run, as the order said. Seven tests/** tests will now fail on version pins and need test-engineer.

**What changed in the Resolver**
- **Scope.** The block applies only when the preprint-DOI block applies. That means every shared DOI has a preprint prefix and the stage is not same-stage-preprint. In that case dblp-key and scopus-eid agreements are removed from the deciding set (step 6b), and the pair ends at step 6c: UNRESOLVED, exact-id, 0.0.
- **New codes.**
  - Reason code `record-key-agreement-blocked-by-preprint-doi-stage`.
  - Rule `exact-id.unresolved.record-key-agreement-blocked-by-preprint-doi-stage.<scheme>`, one per blocked scheme.
  - Both are recorded together with the existing `preprint-doi-agreement-cross-stage` and `publication-stage-uncertain` reasons.
- **Nothing else changes.** Record keys behave as before when no preprint DOI is shared, when a publication DOI is shared, and at same-stage-preprint.
- **Conflicts are preserved.** The held-back record-key agreement still counts for step 3, so a dblp-key agreement next to a scopus-eid disagreement is still CONFLICT. Step 2 (T1 disagreement leads to CONFLICT) is unchanged. ACCEPTED never carries conflicts.
- **Nit 1 (this rule only).** The preprint-DOI rule and reasons are now recorded only on outcomes the block actually decided (steps 3, 4 and 6c). They no longer appear on ACCEPTED outcomes or on step-2 CONFLICT outcomes. Outcomes themselves do not change.
- **arXiv rule not changed.** It is merged in the same list, but `PolicyV4Suite:217` pins `arxiv-agreement-cross-stage` on an ACCEPTED outcome. Changing it would alter pinned provenance without any human decision. It still appears on ACCEPTED outcomes, for example {k, arx} vs {k, arx, doi}.

**Decision for the human (not implemented):** should a dblp-key or scopus-eid agreement also be blocked when the trigger is the arXiv cross-stage block (a shared arXiv ID, not in DOI form, between a preprint and a published record), with no preprint DOI shared? decisions-08 names only the preprint-DOI rule, so I left this unchanged. Such pairs are still ACCEPTED at 0.99 through the record key. A characterization test pins that current behaviour.

**The 7 tests/** tests expected to fail (route to test-engineer).** These are predicted from reading the files; I did not run them.
- MatchContractCrossModuleSuite:
  1. "every Resolver outcome kind maps to a valid match-decision.v2 that round-trips byte-stably": line 150 pins `Some("er-policy.v5")`.
  2. "config band ordering ... withConfig refuses breaks": `activePolicy = "er-policy.v5"` at line 243, so the positive control at line 276 fails. Line 278 also expects er-policy.v6 to be refused, and it is now the active version.
  3. "config loader: a valid v2 file feeds withConfig...": line 334 (v5 configuration).
  4. "policy versioning: v5 and prompt v2 are the default...": lines 375, 380, 382, 392, 395, 409 (v5 default, resource and file schema).
- ClusterCorroborationAdversarialSuite:
  5. "preprint DOI (decisions-07 a)...": `assertPreprintDoiBlocked` pins er-policy.v5 at lines 431 and 448.
  6. "preprint DOI (decisions-07 c): shared publication DOIs still decide...": line 603.
  7. "preprint DOI (decisions-07 c): case and resolver-URL forms...": through `assertPreprintDoiBlocked`, line 431.
- No tests/** test asserts the preprint-DOI rule on an ACCEPTED outcome or on a step-2 CONFLICT. I read ClusterCorroboration, MatchContract, ExactIdPrecedence, AdjudicationAdversarial, MembershipEvaluation and ErFixtures.

```text
STATUS: COMPLETE
OWNER: entity-resolution-engineer
WORK ORDER: ER-P5-10 (Phase 5). decisions-08 "Block it": when the preprint-DOI rule leaves a work pair UNRESOLVED (stage cross-stage or uncertain), a shared dblp-key or scopus-eid must not override it; scoped to that case; T1 conflict handling preserved; dedicated reason code; regressions for both record-key paths; RV-P5-06 nit 1 for this rule; er-policy.v6.
FILES CREATED:
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/resources/scholarchain/entityresolution/policy/er-policy.v6.json
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/policy/PolicyV6Suite.scala (12 tests)
FILES MODIFIED:
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/policy/Resolver.scala (record-key block; Rules.recordKeyBlockedByPreprintDoi, Rules.Reason.RecordKeyBlockedByPreprintDoi; preprint-DOI rule and reasons moved from every outcome to steps 3, 4 and 6c; scaladoc)
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/policy/MatchPolicy.scala (FileSchemaVersion er-policy-file.v6; DefaultResource er-policy.v6.json; v5 added to both retired sets; RetiredV5Resource)
- Version pins v5 -> v6 only, no assertion weakened, under /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/: policy/MatchPolicySuite.scala, policy/PolicyV3Suite.scala, policy/PolicyV4Suite.scala, policy/PolicyV5Suite.scala (also its version test now checks v6 default and v4 refused), policy/ResolverSuite.scala, adjudication/AdjudicationSuite.scala, review/ReviewQueueSuite.scala, review/CuratedRunReaderSuite.scala, review/GoldMembershipSuite.scala
- er-policy.v5.json is unchanged and kept as history (now refused). Not touched: tests/**, core, hooks, settings, docs/agent-runs, test marker, prompts.
COMMANDS RUN:
- git ls-files (untracked and tracked listings of docs/agent-runs/phase-05, modules/entity-resolution, modules/core, tests): ok
- one ls command: refused by guard-dangerous.sh (no-allowlist-entry); nothing ran; I used git ls-files instead
- sbt entityResolution/test (run 1 of the 2 allowed): exit 0, 11 s
- git status --porcelain: ok, no audit refusal
- sbt check: NOT run (per work order)
TEST RESULTS:
- sbt entityResolution/test console, verbatim: "[info] Passed: Total 324, Failed 0, Errors 0, Passed 324" / "[success] Total time: 11 s". That is the previous 312 plus 12 new PolicyV6Suite tests, all passing.
- PolicyV6Suite tests that fail without the fix (under v5 these pairs were ACCEPTED at 0.99, or the preprint-DOI rule appeared on ACCEPTED or step-2 CONFLICT outcomes):
  - dblp-key and scopus-eid paths, both orientations, 6 feature profiles (empty, in-band, strong with the score checked >= 0.85, strong plus venue, exact title-year-authors, exact plus venue), with and without the arXiv ID on both sides. The outcome is UNRESOLVED, exact-id, 0.0, carrying the new rule and reason plus the preprint-DOI and publication-stage-uncertain reasons. No deterministic or probabilistic rule fires, llmBandEligible is false, Adjudication.eligible is false, and the core decision validates.
  - two mixed records sharing a preprint DOI and a record key: blocked.
  - both record keys shared at once: both blocked and both recorded.
  - step-2 CONFLICT no longer lists the preprint-DOI rule.
  - a sweep of 11x11 identifier combinations by 2 profiles: no ACCEPTED outcome lists the preprint-DOI or record-key "unresolved" rule, and ACCEPTED never has conflicts.
- Controls, passing and unchanged: a record key still ACCEPTS at 0.99 when no preprint DOI is shared (including a preprint DOI on one side only), next to a shared publication DOI, and at same-stage-preprint. An arXiv T1 disagreement and a DOI disagreement next to a shared record key are CONFLICT. A scopus-eid disagreement next to a held-back dblp-key agreement is still CONFLICT (step 3).
- tests/**: not run. The 7 tests listed above are expected to fail on version pins (predicted from reading the files).
VALIDATION RESULTS:
- Policy er-policy.v6 (file schema er-policy-file.v6). v1 to v5 are retired and refused by file schema, by name and through withConfig.
- Prompt er-adjudication-prompt.v2 is unchanged, because blocked pairs are on the exact-id path and never reach the LLM. Model ID still comes only from models.llmModelId.
- Features er-features.v2 and blocking er-blocking.v2 are unchanged. Thresholds are unchanged: accept 0.85, reject 0.2, LLM band [0.4, 0.7]. The policy is uncalibrated.
- v6 policy text updated: evaluationOrder steps 3, 6a, 6b and 6c; the publicationStage definition and rationale (citing decisions-08); the doi, dblp-key and scopus-eid agreementRationale.
- Gold-set evaluation: not run. No human gold labels exist and the goldSetEval alias is inactive. So there is no confusion matrix, unresolved rate, threshold sensitivity, or false-positive/false-negative examples to report.
ASSUMPTIONS:
- "Record keys" means the work schemes whose disagreement is block-inferred-acceptance: exactly dblp-key and scopus-eid in v6.
- The block holds back the record key's acceptance only. The agreement still counts for step 3 CONFLICT and step 2, which keeps conflict handling.
- "Outcomes it actually blocked" means steps 3, 4 and 6c, the steps whose result depends on the accepting set. The preprint-DOI rule is still recorded on a step-3 CONFLICT where the block turned an override-accept into CONFLICT.
- Not covered by decisions-08: the arXiv cross-stage trigger with a shared record key, so it is unchanged.
- BlindViewLeakageAdversarialSuite and UnicodeNormalizationRegressionSuite were not read. I believe they contain no policy-version pins, because earlier version bumps never broke them, but I have not confirmed it.
RISKS:
- More pairs become UNRESOLVED (manual decisions) where a DBLP or Scopus record is shared alongside a preprint DOI. How often this happens in real data is unknown.
- The arXiv-trigger asymmetry stays open: a shared record key still merges a preprint with its published record when the only shared preprint identifier is a bare arXiv ID.
- The arXiv rule ID still appears on ACCEPTED outcomes (RV-P5-06 nit 1 is left open for the arXiv rule).
- RV-P5-06 nit 2 (non-canonical preprint prefixes silently disable the block) and the decisions-08 threshold-sensitivity issue are not addressed.
- The test marker is stale until a passing `sbt check`, so the Stop hook may refuse completion.
MANUAL ACTION REQUIRED:
- Route the 7 listed tests/** tests to test-engineer (v5 -> v6 pins; activePolicy "er-policy.v6"; the refused-version lists should gain v5 and drop v6).
- Human: decide whether the arXiv cross-stage trigger should also block record-key agreements.
- Human: one foreground `sbt check` after the test-engineer update.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: test-engineer updates the 7 tests/** pins; then a human foreground `sbt check`; then a focused code-reviewer re-review of the decisions-08 change and nit 1; then the human decision on the arXiv-trigger question.
```

---

## ER-P5-11 (entity-resolution-engineer) — hand-back 2026-11-11, verbatim

Orchestrator note (not part of the report): routed per decisions-09.md items 1 and 2; no full `sbt check` allowed; at most three `sbt entityResolution/test` runs.

ER-P5-11 is done and settles the policy at er-policy.v7, which Part A and Part B both use. One `sbt entityResolution/test` run (of the three allowed) passed: "[info] Passed: Total 351, Failed 0, Errors 0, Passed 351". That is 324 before, plus 27 new tests. `sbt check` was not run, as ordered. No gold-set evaluation was run because no human labels exist.

**Part A: arXiv trigger (decisions-09 item 1)**
- A shared arXiv ID that does not decide (stage is not same-stage-preprint) now also holds back a shared dblp-key or scopus-eid, but only when no other T1 agreement decides. The pair stays UNRESOLVED on exact-id at 0.0, is not LLM-eligible and never reaches the score.
- New codes, recorded only on that step-6c outcome (never on ACCEPTED or CONFLICT):
  - reason `record-key-agreement-blocked-by-arxiv-stage`
  - rule `exact-id.unresolved.record-key-agreement-blocked-by-arxiv-stage.<scheme>`
- When the preprint-DOI trigger also applies, both rule families are recorded. v6 preprint-DOI behaviour is unchanged.
- Unchanged: step 2 (T1 disagreement gives CONFLICT) and step 3 (a held-back key still contradicts a blocking disagreement). ACCEPTED never carries conflicts.
- The ER-P5-10 characterization test is replaced by a regression in `PolicyV6Suite`. `PolicyV7Suite` (13 tests) covers both keys, both orientations and six profiles (including score ≥ 0.85 and the exact title-year-authors rule), with controls: no shared preprint ID, shared publication DOI, same-stage-preprint.
- v6 is retired like v5: file kept, refused by schema, by name and through `withConfig`. Policy text is updated (`evaluationOrder` 6b/6c, `publicationStage`, the arxiv, dblp-key and scopus-eid rationales).

**Part B: evaluation batch (decisions-09 item 2)**
- **m1, threshold sensitivity.** I re-ran each candidate through the Resolver rather than re-implementing gates. `Resolver.decide` now runs the same resolution twice more, with thresholds at −∞ and +∞, and records a `ScoreGate` (accept reachable, reject reachable, and the capping rule IDs). Since the score does not depend on thresholds, that exactly predicts the Resolver at any threshold pair.
  - A row counts as TP/FP only when acceptance is reachable. Capped rows are reported as `blockedAccept` (split by rule), `blockedReject` and `gateUnknown`.
  - A test checks every grid point against an independent `Resolver.decide` at those thresholds.
- **m6, settings and config.**
  - `GoldSetEvalMain` and the `queue`/`diagnostics` modes of `ReviewQueueMain` now load config (`SCHOLARCHAIN_CONFIG_FILES`) and apply `withConfig`. There is no fallback, and `membership` still needs no config.
  - Rows record the thresholds they were decided under. The evaluation refuses any mismatch in versions, thresholds/band, model or prompt.
  - Text output and report JSON print one `settings` line with accept, reject, band, policy/blocking/features, prompt, and model. Model is the configured `models.llmModelId` only when a row carries an adjudication, otherwise "none (no adjudicator ran)".
- **N3.** False-negative examples now cover REJECTED, UNRESOLVED, CONFLICT and not-generated, drawn from each category in turn. At least 10 are listed when available (`--max-errors` below 10 is refused), counts by outcome are reported, and the output says when fewer than 10 exist.
- **N4.** The manifest write goes to a forced temp file, then a hard link (falling back to a no-replace move). A failed `ReviewQueueMain` run removes the files it created.
- **#4.** `evaluate` refuses duplicate member IDs. `coverage()` now returns `Either` and refuses non-canonical labels.
- **#5.** The manifest is read once (`readFileWithSha256`) and the hash is of the parsed bytes.
- **Nit 2.** Each prefix must match `^10\.[0-9]{4,9}/$`.

**Changes beyond the listed items (needed by m1/m6/N3):**
- Review-queue row schema bumped to `er-review-queue-row.v4` (adds `thresholds` and `scoreGate`; v3 rows are refused).
- `ErrorExample.rowId`, `candidateId` and `score` are now `Option`, so a not-generated example invents nothing.
- New report fields on `EvaluationReport` and `SensitivityPoint`.
- `BlindView.HiddenFields` gains `thresholds` and `scoreGate`; the blind view itself is unchanged.
- Unchanged: er-features.v2, er-blocking.v2, thresholds (0.85 / 0.2 / band [0.4, 0.7]), prompt v2 text. No core change was needed.

Two of my own module tests asserted the old defects and were updated: `GoldSetEvaluationSuite`'s error-analysis test (it expected FN=1 REJECTED-only; now REJECTED+UNRESOLVED) and the `PolicyV6Suite` characterization test. Every other change to existing tests is a version pin, a config-loader argument, or an added assertion. D1–D5 and the PolicyV3–V6 behavioural tests are intact.

**tests/\*\* tests that will fail, for test-engineer** (predicted from reading the files, not run):
1. `MatchContractCrossModuleSuite` "every Resolver outcome kind maps to a valid match-decision.v2…": pins `Some("er-policy.v5")` at line 150.
2. `MatchContractCrossModuleSuite` "config band ordering … withConfig refuses breaks": `activePolicy = "er-policy.v5"` (line 243) breaks the positive control at line 276. Its refused list (line 278) should drop v6 as "unknown" and treat v5 and v6 as retired.
3. `MatchContractCrossModuleSuite` "config loader: a valid v2 file feeds withConfig…": line 334, v5 config.
4. `MatchContractCrossModuleSuite` "policy versioning: v5 and prompt v2 are the default…": lines 375–414 pin the v5 default, resource and `er-policy-file.v5`.
5. `ClusterCorroborationAdversarialSuite` "preprint DOI (decisions-07 a)…": `assertPreprintDoiBlocked` pins v5 at lines 431 and 448.
6. `ClusterCorroborationAdversarialSuite` "preprint DOI (decisions-07 c): shared publication DOIs still decide…": line 603.
7. `ClusterCorroborationAdversarialSuite` "preprint DOI (decisions-07 c): case and resolver-URL forms…": through `assertPreprintDoiBlocked`.
8. `MembershipEvaluationAdversarialSuite` "entry point: freeze mismatch and --provisional are refused…": it calls `GoldSetEvalMain.run(args, out)` without config, so runs end "configuration refused". Its rows also use policy "er-policy.v3" and carry no thresholds.
9. `MembershipEvaluationAdversarialSuite` "entry point: 199 reviewed withholds the metrics; 200 reviewed…": same cause (expects exit 3 and 0, gets 2).

For 1–7, test-engineer should pin v7 and add v6 to the refused lists. For 8–9, the tests need `GoldSetEvalMain.run(args, out, loader)` with a synthetic config, plus rows at the v7 policy version whose recorded thresholds match it (`DecisionThresholds`). The other tests/er suites I read assert behaviour that does not change. `UnicodeNormalizationRegressionSuite` was not read.

```text
STATUS: COMPLETE
OWNER: entity-resolution-engineer
WORK ORDER: ER-P5-11 (Phase 5): decisions-09 item 1 (the arXiv cross-stage block also holds back dblp-key/scopus-eid; er-policy.v7) and item 2 (evaluation batch: RV-P5-04 m1, m6, N3, N4; RV-P5-05 #4, #5; RV-P5-06 nit 2)
FILES CREATED:
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/resources/scholarchain/entityresolution/policy/er-policy.v7.json
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/review/CreateOnceFile.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/policy/PolicyV7Suite.scala (13 tests)
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/evaluation/EvaluationBatchSuite.scala (9 tests)
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/review/CreateOnceFileSuite.scala (4 tests)
FILES MODIFIED (all under /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/):
- main/scala/scholarchain/entityresolution/policy/Resolver.scala (arXiv record-key block, Rules.recordKeyBlockedByArxivStage, Reason.RecordKeyBlockedByArxivStage, ScoreGate, ResolutionOutcome.scoreGate, Scaladoc)
- main/scala/scholarchain/entityresolution/policy/MatchPolicy.scala (er-policy-file.v7, DefaultResource v7, v6 retired, RetiredV6Resource, PreprintDoiPrefixPattern validation)
- main/scala/scholarchain/entityresolution/review/ReviewQueue.scala (row schema er-review-queue-row.v4, DecisionThresholds, SystemDecision.thresholds and scoreGate)
- main/scala/scholarchain/entityresolution/review/ReviewQueueMain.scala (config via withConfig for queue/diagnostics, settings line, all-or-nothing writes)
- main/scala/scholarchain/entityresolution/review/GoldMembership.scala (readFileWithSha256; writeNew via CreateOnceFile)
- main/scala/scholarchain/entityresolution/review/BlindView.scala (HiddenFields: thresholds, scoreGate)
- main/scala/scholarchain/entityresolution/evaluation/GoldSetEvaluation.scala (gated sensitivity, EffectiveSettings, settings checks, N3 examples, labelProblems, coverage returns Either, MinErrorExamples)
- main/scala/scholarchain/entityresolution/evaluation/GoldSetEvalMain.scala (config applied, settings line, single-read freeze, example and sensitivity lines, --max-errors >= 10)
- test/scala/scholarchain/entityresolution/policy/PolicyFixtures.scala (synthetic config helpers)
- test/scala/scholarchain/entityresolution/policy/PolicyV6Suite.scala (characterization replaced by regression; v7 pins)
- version pins v6 -> v7 only: test/.../policy/{MatchPolicySuite,PolicyV3Suite,PolicyV4Suite,PolicyV5Suite,ResolverSuite}.scala, test/.../adjudication/AdjudicationSuite.scala, test/.../review/{ReviewQueueSuite,GoldMembershipSuite}.scala
- test/scala/scholarchain/entityresolution/review/ReviewQueueSuite.scala (also asserts recorded thresholds and gate)
- test/scala/scholarchain/entityresolution/review/CuratedRunReaderSuite.scala (config loader args; new m6 queue/diagnostics test)
- test/scala/scholarchain/entityresolution/evaluation/GoldSetEvaluationSuite.scala (config loader args; FN expectation updated for N3; candidateId is now Option)
- er-policy.v6.json unchanged (kept as history, now refused). Not touched: tests/**, core, hooks, settings, docs/agent-runs, test marker, prompts.
COMMANDS RUN:
- git status --short; git ls-files (untracked and tracked ER/tests listings); git status --porcelain (end; same path set as at start; no audit refusal)
- two Bash calls refused by guard-dangerous.sh (shell-syntax-gate, a quote character in a grep pipeline); nothing executed; not an audit refusal
- sbt entityResolution/compile: success (15 s)
- sbt entityResolution/Test/compile: exit 1, one type error (GoldSetEvaluationSuite:211, Option candidateId); fixed
- sbt entityResolution/test (run 1 of 3): success, 12 s, foreground
- sbt check: NOT run (work order)
TEST RESULTS: sbt entityResolution/test console, verbatim: "[info] Passed: Total 351, Failed 0, Errors 0, Passed 351" / "[success] Total time: 12 s". PolicyV7Suite 13/13, EvaluationBatchSuite 9/9, CreateOnceFileSuite 4/4, new CuratedRunReaderSuite m6 test passing; LeakageStructureSuite passing. That each new test fails without its fix is from code reasoning, not observed (no pre-fix run). tests/**: not run; 9 predicted failures listed above.
VALIDATION RESULTS:
- Policy er-policy.v7 (file er-policy-file.v7), calibrated=false. Features er-features.v2, blocking er-blocking.v2, thresholds accept 0.85 / reject 0.2 / LLM band [0.4, 0.7] (policy file; config now overrides), prompt er-adjudication-prompt.v2, response schema er-adjudication-response.v1. Model ID only from models.llmModelId; no adjudicator ran anywhere in this work.
- Gold-set evaluation NOT run: no human labels. So there is no confusion matrix, precision, recall, F1, unresolved rate, gold threshold sensitivity, or FP/FN examples. All metric tests use SYNTHETIC fixtures.
- Sample probe strata unchanged (SampleQueueProbeSuite): 119 resolved, 0 refused, 0 untraceable. System output, not a gold metric.
ASSUMPTIONS:
- "When the arXiv cross-stage block applies" means only when the arXiv block leaves the pair unresolved: no other T1 agreement decides. A shared publication DOI still decides, per the work-order control.
- Bumping the row schema to v4 is needed to carry thresholds and score gates honestly from queue generation to evaluation.
- The text settings line plus report JSON satisfy "text and JSON". When metrics are withheld, the coverage JSON has no settings block, but the settings line is printed.
- An empty preprintDoiPrefixes list stays valid (an existing PolicyV5Suite test requires it).
RISKS:
- Operators must now supply SCHOLARCHAIN_CONFIG_FILES for GoldSetEvalMain and ReviewQueueMain queue/diagnostics; there is no fallback to policy-file thresholds.
- A record key still ACCEPTS a cross-stage pair when no preprint identifier is shared, e.g. {key, arXiv} vs {key, DOI}. This is pinned as a control per the order and is not covered by any decision.
- Resolution now runs three times per candidate (score-gate probes); cost is linear and small.
- The no-hard-link fallback (no-replace move) has a tiny check-then-rename window. The normal hard-link path is atomic and fails if the file exists.
- An empty preprint-prefix list still silently disables the stage block (allowed by an existing test).
- Still open: RV-P5-04 m7, m8, N1, N2; RV-P5-05 #2, #3; arXiv rule on ACCEPTED outcomes.
MANUAL ACTION REQUIRED:
- Route the 9 tests/** tests above to test-engineer (v7 pins; v6 added to refused lists; entry-point tests need a config loader and v7 rows that record matching thresholds).
- After that, the human runs one foreground `sbt check`.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: test-engineer updates the 9 tests/** tests; human foreground sbt check; code-reviewer focused re-review of decisions-09 item 1 and the evaluation batch (m1 score gate, m6 config wiring, row schema v4).
```

---

## DS-P5-05 (dataset-engineer) — hand-back 2026-11-12, verbatim

Orchestrator note (not part of the report): routed per decisions-09.md item 3; ran in parallel with TE-P5-04 (which made no Bash calls). Interrupted once by an API usage limit and resumed by SendMessage. The download was not authorized and was not run.

All repairs in DS-P5-05 are done and both offline checks pass with 0 failures: the fixture with 81 checks and manifest validation with 1350 checks. I did not run `--full`, did not download anything, ran no `sbt`, and did not edit selection.tsv.

**Repairs**

| Item | What changed | Covered by |
|---|---|---|
| RV-P5-02 #3 (8 MiB budget) | Each dblp excerpt is counted against the budget before it is written. One that would go over fails with `byte-budget-reached` and nothing is written for it. | Fixture: one excerpt fills the budget exactly and the other 3 are refused; the same through `er_fetch` with a full ledger |
| #5 (labeler visibility) | New "Labeler visibility" section in the schema notes and new manifest key `er_candidates.labeler_visibility`. They list the columns that must be withheld: `criterion`, `expect_author`, `expect_doi` and `unit_id` in selection.tsv; `unit_id` and `criterion` in split-assignment.tsv, PROVENANCE.tsv and FETCH-FAILURES.tsv; and the unit grouping in file paths. They also record that the ER blind view already excludes criterion and unit IDs (as reported in RV-P5-05), and that entity-resolution-engineer must confirm full coverage. Manifest validation now requires the key. | Manifest validation |
| #6 (cross-split check) | Now a failing check. It includes the ORCID iDs that dblp excerpts assert, and normalises ID forms (URL vs bare, lower-case `x`). Author names are never used. Before the change I measured 0 shared IDs in the committed sample, and after it the check passed with 508 distinct IDs, so it does not break the sample and no human decision was needed. | Fixture: a straddling ORCID fails, plus a passing control case |
| #8 (`--with-release`) | `--verify --with-release` now fails whenever the release was not actually read and checked. | Fixture: fails before any extraction, passes after |
| Nit 9 | Not edited (needs your agreement). Proposed replacement for selection.tsv line 10: `# kind: dblp/rec = offline excerpt of the pinned release dblp-2026-10-03 by record key (never requested from dblp.org); openalex/work-by-doi and crossref/work-by-doi =` | – |
| Nit 10 | A dblp key containing `__` is refused. Two planned records that would share a file name (compared case-insensitively) are also refused. | Fixture |
| Nit 11 | The release is hashed and scanned in a single pass, and nothing is written until both checksums match. A checksum mismatch is reported even when the layout is also bad. The same applies to `--with-release`. | Existing fixture cases still pass |
| Nit 12 | The sha256 sidecar is written atomically. A final file left without a sidecar by a crash is re-checked (MD5 and `gzip -t`) and given its sidecar without a new download. If it fails the checks it is not trusted and is replaced only by a new download that passes. | Fixture |
| N1 (torn row) | A last line is dropped only if it has fewer columns than a full row. A line with all its columns but no final newline stops `--sample` with exit 2 and leaves the file unchanged. | Fixture |
| N2 (absolute path) | Failure messages use repository-relative paths only. | Fixture |
| N3 (`rm` command) | The printed `rm` path is shell-escaped. | Fixture: a path with a quote, spaces and `;` parses back exactly |
| N4 (MD5 tool) | The MD5 helper fails if its output is not a valid hash. The script checks for an MD5 tool before downloading, and if the tool fails after the transfer the `.part` is kept, not deleted. | Fixture with a failing tool |
| N5 (log line) | The final log line reports the bytes actually transferred. | Fixture |
| N6 (`--full` wrapper) | `fetch-dblp.sh --full` itself is now executed in a throw-away copy of the repository with a curl stub (see "Fixture coverage of the command"). | Fixture |

One change was not in the work order. If curl reports HTTP 416 but exits 0, the script now treats it as the 416 failure: exit 22, `.part` kept, manual step printed. This addresses the reviewer's unverified concern that real curl may exit 0 on a refused resume, and it is covered by the fixture.

**Download command for review (do not run)**
```
SCHOLARCHAIN_CONFIRM_FULL_DOWNLOAD=dblp-2026-10-03 datasets/scripts/fetch-dblp.sh --full
```
Run from the repository root.

- **Before any network access:** argument parsing, a check for python3, curl and awk, and five manifest reads. Without the variable, or with any other value, it exits 3. It then checks for gzip and an MD5 tool.
- **Existing files:** if the final file already matches its sidecar, it exits 0 with no network. If the final file has no sidecar, it re-checks MD5 and `gzip -t`; on success it writes the sidecar and returns 0. If a `.part` already matches the pin, curl is skipped.
- **Fetch:** exactly one https request to `https://drops.dagstuhl.de/storage/artifacts/dblp/xml/2026/dblp-2026-10-03.xml.gz`. Redirects are https-only but may go to any host.
- **curl options:** `--fail --location --proto =https --proto-redir =https --max-filesize 2000000000 --connect-timeout 30 --speed-limit 1024 --speed-time 120 --write-out %{http_code} -C - -o <part>`.
- **Writes:** only `datasets/downloads/dblp/full/dblp-2026-10-03.xml.gz.part`, then `dblp-2026-10-03.xml.gz` and `.xml.gz.sha256`. These are git-ignored. During the sidecar write, a temporary `.sha256.tmp` also exists.
- **Bounds:** 30 s to connect; a transfer below 1024 bytes/s for 120 s counts as a stall (curl exit 28); 2,000,000,000-byte cap; no total time limit. Expected size is about 1.03 GB.
- **Checksum gates, in order:**
  1. MD5 of `.part` equals the pin `cbd329100ea1bbb2873fa2f6d414ea00`.
  2. `gzip -t` passes.
  3. Only then the file is renamed and the sha256 sidecar is written atomically.
  4. Later uses (`--verify`, ER extraction and `--with-release`) re-check the MD5 and the sidecar.
- **Failure behaviour:**

| Failure | Result |
|---|---|
| Stall or connect timeout (curl 28) | `.part` kept, "re-run to resume", exit 28 |
| HTTP 416, whether curl exits 22 or 0 | `.part` kept, escaped `rm` step printed, non-zero exit |
| Server will not resume (curl 33) | `.part` kept, escaped `rm` step printed, exit 33 |
| Over the size cap (curl 63) | `.part` kept, "stop and report", exit 63 |
| Any other curl error | `.part` kept, exit with curl's code |
| Transfer complete but MD5 or `gzip -t` fails | `.part` deleted, message says why, exit 1 |
| MD5 tool fails after the transfer | `.part` kept, exit 1 |
| Ctrl-C | `.part` kept |
| Success | Logs size, sha256 and bytes fetched; exit 0 |

- **Fixture coverage of the command:** the fixture runs this exact command on copies of the scripts, with a curl stub and a `.invalid` URL, and confirms:
  - exit 3 without the variable and with a wrong value;
  - curl's exit 28 is passed through;
  - a wrong MD5 exits 1;
  - success exits 0 and logs the bytes fetched;
  - a re-run fetches 0 bytes.

  Real DROPS Range support and the real curl behaviour on 416 are still unverified.

STATUS: COMPLETE
OWNER: dataset-engineer
WORK ORDER: DS-P5-05 (Phase 5): acquisition repairs RV-P5-02 #3, #5 (schema side), #6, #8, nits 9-12; RV-P5-03 N1-N6; local fixture verification; reviewed download command for a separate human decision
FILES CREATED: none
FILES MODIFIED: /Users/taufeeqpatel/Desktop/ScholarChain/datasets/scripts/dsutil.py; /Users/taufeeqpatel/Desktop/ScholarChain/datasets/scripts/lib-datasets.sh; /Users/taufeeqpatel/Desktop/ScholarChain/datasets/scripts/fetch-dblp.sh; /Users/taufeeqpatel/Desktop/ScholarChain/datasets/dataset-manifest.yaml; /Users/taufeeqpatel/Desktop/ScholarChain/datasets/schemas/er-candidates.md (untracked). Not touched: selection.tsv, split-assignment.tsv, the stored records, modules/**, tests/**, docs/**, hooks, settings, the test marker.
COMMANDS RUN: (1) `git status --porcelain`; (2) `datasets/scripts/fetch-samples.sh --verify datasets/er-candidates` (before the changes, code unmodified); (3) `datasets/scripts/fetch-samples.sh --verify datasets/er-candidates/fixture`; (4) `datasets/scripts/validate-manifest.sh --verify`; (5) `git status --porcelain`. No sbt, no `--sample`, no `--full`, no network.
TEST RESULTS: run (2), before changes: "er-verify: 1012 checks, 0 failures", with "OpenAlex author IDs or ORCID iDs present in both dev and heldout (incidental coauthors): 0". Run (3): "er-fixture: 81 checks, 0 failures (temporary directory removed; repository not written)". Its FAIL lines are expected failures inside the negative test cases; each one's top-level check passed. The output also included "PASS fixture: no network call was attempted (er_curl and subprocess.run trapped)".
VALIDATION RESULTS: run (4): "manifest validation: 1350 checks, 0 failures", including "PASS er: no OpenAlex author ID or ORCID iD (OpenAlex records and dblp excerpts) occurs in both dev and heldout (508 distinct IDs checked)". Run (5): every path changed in this order is under datasets/**; the other modified and untracked paths belong to other work orders.
ASSUMPTIONS: The `--with-release` release gate (full MD5 and sidecar read) counts as "reading the release", even when no excerpts are recorded yet. The blind-view statements are as reported by RV-P5-05; I did not read BlindView.scala. dblp `pid` attributes are not used for the cross-split check, because their form in the dump is unverified.
RISKS: The real release layout, encoding, key coverage and DROPS Range support are still unknown. The cross-split check now fails verify if a future dblp excerpt asserts an ORCID that also appears in the other split, which would need a human selection decision. The wrapper fixture runs copies of the scripts in a temporary directory. The single-pass reader relies on Python's gzip module reading only through `read`/`readinto` (it passed on this machine's Python). The 416 handling for curl exit 0 is an addition beyond the listed items.
MANUAL ACTION REQUIRED: Agree or not to the nit-9 comment text. Decide separately on the download command, after code-reviewer has reviewed it. Run one foreground `sbt check` when the orchestrator says no test process is active. Commit the files.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer focused re-review of the DS-P5-05 diff and the download command above; entity-resolution-engineer confirms the blind view hides every column in `er_candidates.labeler_visibility`; human decides on the download.

---

## DS-P5-06 (dataset-engineer) — hand-back 2026-11-12, verbatim

Orchestrator note (not part of the report): routed per decisions-10.md items 2 and 3; download held (item 1). Ran in parallel with RV-P5-08 (read-only, no Bash).

Both minor findings are fixed and nit 9 is applied. Optional items NIT-3, NIT-4, NIT-5 (a and b) and the dataset side of NIT-7 are done too. The fixture passes with 98 checks and 0 failures (81 before). Manifest validation passes with 1350 checks and 0 failures. With Crossref ORCIDs added, the committed sample still has no identifier shared between dev and heldout, so item 2 did not need a human decision. Nothing was downloaded, and I ran no sbt, no `--full` and no network `--sample`.

One limit on the evidence: I did not run the new fixture cases against the old code. That each one fails without its fix comes from reading the old code path, not from a run.

### 1. MINOR-1: symlinked download paths are refused (`lib-datasets.sh`)
- **New check `ds_full_refuse_links`.** It refuses with exit 2 when any of these is a symbolic link: `datasets/downloads` itself, every path component below it down to the release file, the release file, its `.part`, `.sha256` and `.sha256.tmp`. It uses `-L`, which never follows the link, and it refuses a destination outside the download area.
- **When it runs.** `ds_full_download` calls it first, before any network access. It runs again after `mkdir`, before curl, and after curl, before the `.part` is read, renamed or deleted.
- **Download area.** `DS_DOWNLOADS="$DS_DIR/downloads"` is set inside the library and never read from the environment.
- **Message.** It names the link and says nothing was written or deleted through it.
- **Sidecar write.** `ds_write_sidecar` also refuses a linked `.sha256.tmp` or `.sha256`, and creates the temporary file with noclobber (`set -C`, exclusive create).
- **New fixture cases.** Each runs with a curl stub that would write the release body if called. Six cases call the transfer function directly: a symlinked `datasets/downloads`, a symlinked directory component, a `.part` linked to an existing file, a linked `.sha256.tmp`, a linked `.sha256`, and a linked final file. One more case runs the real `fetch-dblp.sh --full` with `datasets/downloads` as a symlink. All pass: exit 2, curl not called, the victim file unchanged, the outside directory unchanged, the link kept.
- **Fixture change.** `call()` now sets `DS_DOWNLOADS` to its temporary directory after sourcing the library.

### 2. MINOR-2: Crossref ORCIDs in the cross-split check (`dsutil.py`)
- **New function `er_crossref_entity_keys`.** It collects `message.items[].author[].ORCID`, normalised the same way as the dblp `orcid` attribute (both now use a shared helper, `er_asserted_orcid_key`). It reads identifiers only, never names or affiliations.
- **New fixture cases.** In the failing case, a Crossref ORCID (`http://orcid.org/` form) in heldout unit fx-cr-1 equals an OpenAlex authorship ORCID (`https://orcid.org/` form) in dev unit fx-cr-3. It fails with 1 failure and the output line `orcid:0000-0002-1825-0097 dev=fx-cr-3 heldout=fx-cr-1`. The passing control has the same author name in both splits with different ORCIDs and reports 3 distinct IDs checked.
- **Committed sample.** 0 shared IDs, and still 508 distinct IDs checked, the same as before. So every Crossref ORCID in the sample was already collected from an OpenAlex record of the same split. No identifier straddles dev and heldout, and nothing was hidden or re-split.
- **Docs.** `er-candidates.md` now lists exactly what is collected and what is not (institution IDs, Crossref affiliation and funder IDs, dblp `pid` attributes, names). The manifest's `cross_split_check` and `privacy` entries are updated to match.

### 3. Nit 9 (`selection.tsv`)
- Line 10 is now exactly the approved text.
- Line 7 now says "checked against the extracted dblp record" instead of "fetched".
- **How I checked the selected rows.** I added an INFO line to `er_verify` that prints the SHA-256 of every non-comment line, byte for byte. I ran validation once before the edit and twice after. All three runs show the same 281 lines and the same digest `c018a5f1a4360f91b6fb168ea2d4031dcc5c8cbda54455f1f2bbb1db92635f6d`. All three also show "70 units, 272 direct requests, 8 authors-of-work rows", and `split-assignment.tsv` still matches the rule.

### 4. Optional items
- **NIT-3.** Release-level reasons now show the relative path `datasets/downloads/dblp/full/...` instead of an absolute path. A new fixture case checks the `release-missing` and `release-sidecar-missing` rows in FETCH-FAILURES.tsv.
- **NIT-4.** The docstring premise is corrected. The repair message now warns that a row may be cut inside its last column (for PROVENANCE.tsv, the free-text `note`, which `--verify` cannot check). It says to add the newline only if every column is complete, and otherwise to delete the line. The fixture checks the new text.
- **NIT-5(a).** curl gets `-q` as its first argument in the full download, in the sample fetch (`ds_fetch`) and in the Python sample fetch (`er_curl`). The fixture checks that `-q` comes first.
- **NIT-5(b).** The MD5 check of an existing `.part` before the request now records one of: match, mismatch, failed, or unchecked. The HTTP 416 message claims an MD5 mismatch only for "mismatch". For "failed" it says the MD5 could not be computed and not to delete the file yet. New fixture cases cover this for curl exit 22 and curl exit 0.
- **NIT-7, dataset side.** The manifest's `labeler_visibility` entry and `er-candidates.md` now say that what is withheld is the unit-to-DOI link, not the DOI value.
- **Left open:** NIT-6 (fixture gaps for one-pass hashing and the case-insensitive file-name collision) and NIT-7's entity-resolution-engineer part (`BlindViewSuite`).

### 5. Updated full-download command summary (for review; not run)
- **Command**, from the repository root: `SCHOLARCHAIN_CONFIRM_FULL_DOWNLOAD=dblp-2026-10-03 datasets/scripts/fetch-dblp.sh --full`. If the script is not executable, put `bash ` before `datasets/scripts/fetch-dblp.sh`.
- **What it fetches.** One HTTPS GET of `https://drops.dagstuhl.de/storage/artifacts/dblp/xml/2026/dblp-2026-10-03.xml.gz`, about 1.03 GB. Redirects stay on HTTPS but may go to any host.
- **curl options:** `-q --fail --location --proto =https --proto-redir =https --max-filesize 2000000000 --connect-timeout 30 --speed-limit 1024 --speed-time 120 --write-out %{http_code} -A <UA> -C - -o <part> <URL>`.
- **What it writes.** Only `datasets/downloads/dblp/full/dblp-2026-10-03.xml.gz.part`, then `dblp-2026-10-03.xml.gz`, `.xml.gz.sha256.tmp` (briefly) and `.xml.gz.sha256`. All are git-ignored.
- **Bounds.** 30 s to connect. A transfer below 1024 B/s for 120 s is a stall (exit 28) and can be resumed. 2 GB size cap. No total time limit.
- **Gates, in order:**
  1. Argument and path parsing; python3, curl and awk present; five local manifest reads.
  2. The confirmation variable must equal the snapshot id, otherwise exit 3.
  3. gzip and an MD5 tool present.
  4. **New:** symlink refusal, exit 2, before any network access.
  5. Final file matches its sidecar: exit 0 with no network. Final file without a sidecar: MD5 pin and `gzip -t`, then the sidecar is written.
  6. `mkdir`, then the symlink check again.
  7. A `.part` that already matches the pin skips curl.
  8. curl.
  9. Symlink check again.
  10. MD5 of `.part` equals `cbd329100ea1bbb2873fa2f6d414ea00`.
  11. `gzip -t`.
  12. Rename to the final name.
  13. SHA-256 sidecar written atomically, with an exclusive-create temporary file.
- **Failure behaviour.**

  | Failure | Result |
  |---|---|
  | curl exit 28 | `.part` kept, re-run to resume, exit 28 |
  | HTTP 416 (curl exit 22 or 0) | `.part` kept, exit 22. Message depends on the earlier MD5 check: mismatch gives an escaped `rm` step; failed says do not delete and fix the MD5 tool; unchecked says re-run first, `rm` only if it repeats |
  | curl exit 33 | `.part` kept, escaped `rm` step, exit 33 |
  | curl exit 63 | `.part` kept, stop and report, exit 63 |
  | Any other curl error | `.part` kept, exit with curl's code |
  | Transfer complete but MD5 or `gzip -t` fails | `.part` deleted, exit 1 |
  | MD5 tool fails after the transfer | `.part` kept, exit 1 |
  | A path is a symlink | exit 2, nothing written or deleted through it, link kept |
  | Ctrl-C | `.part` kept |
- **Remaining risks:**
  - A link planted between a check and the write it guards (curl's `-o` append and `mv`) is not prevented. The checks before and after the transfer narrow that window.
  - `CURL_HOME` and proxy environment variables still apply.
  - No total time limit.
  - MD5 is the only integrity pin against redirect hosts.
  - The real release layout is unknown.
  - The later ER `--sample` also makes OpenAlex and Crossref requests in the same call.
- **Prerequisites:**
  1. Commit the reviewed `datasets/scripts/**`. They are modified or untracked, and the provenance gate needs them to match HEAD.
  2. Optionally confirm with `ls -ld` that `datasets/downloads` and its subpaths are real directories (the script now refuses links itself).
  3. About 1.1 GB of free disk.
  4. Check `CURL_HOME` and proxy variables.
  5. python3, curl, awk, gzip, and md5 or md5sum installed.
  6. Afterwards run `datasets/scripts/fetch-dblp.sh --verify` and route the observed size and SHA-256 to dataset-engineer.

```text
STATUS: COMPLETE
OWNER: dataset-engineer
WORK ORDER: DS-P5-06 (Phase 5): RV-P5-07 MINOR-1 (refuse symlinked --full paths) and MINOR-2 (Crossref ORCIDs in the cross-split check); nit 9 selection.tsv comment (approved text, "fetched" -> "extracted", rows unchanged); optional NIT-3, NIT-4, NIT-5, NIT-7 (dataset side); offline verification; updated full-download command summary (not run)
FILES CREATED: none
FILES MODIFIED: /Users/taufeeqpatel/Desktop/ScholarChain/datasets/scripts/lib-datasets.sh; /Users/taufeeqpatel/Desktop/ScholarChain/datasets/scripts/dsutil.py; /Users/taufeeqpatel/Desktop/ScholarChain/datasets/scripts/fetch-dblp.sh (header comment only); /Users/taufeeqpatel/Desktop/ScholarChain/datasets/er-candidates/selection.tsv (comment lines 7 and 10 only); /Users/taufeeqpatel/Desktop/ScholarChain/datasets/dataset-manifest.yaml; /Users/taufeeqpatel/Desktop/ScholarChain/datasets/schemas/er-candidates.md. Not touched: dblp pin, URL, MD5, snapshot id, split-assignment.tsv (sha256 c00458ce125d1935728815230c240acbf07f13c6e63c06ef1f9daf7dfa57f338), stored records, fetch-samples.sh, modules/**, tests/**, docs/**, hooks, settings, test marker.
COMMANDS RUN: (1) git status --short; (2) git ls-files --others --exclude-standard -- datasets; (3) git ls-files -- datasets/scripts datasets/schemas; (4) datasets/scripts/validate-manifest.sh --verify (baseline: only the INFO selection-digest line added, before any other edit); (5) datasets/scripts/fetch-samples.sh --verify datasets/er-candidates/fixture; (6) datasets/scripts/validate-manifest.sh --verify; (7) datasets/scripts/validate-manifest.sh --verify (after the last manifest and schema text edits); (8) shasum -a 256 on the 6 changed files plus split-assignment.tsv; (9) git status --porcelain. One refused call before any work: a grep command was blocked by guard-dangerous.sh [shell-syntax-gate] (character '"'); nothing ran, and I switched to Read. No sbt, no --full, no --sample, no network, no AWS.
TEST RESULTS: run (5): "er-fixture: 98 checks, 0 failures (temporary directory removed; repository not written)" and "PASS fixture: no network call was attempted (er_curl and subprocess.run trapped)". Its FAIL lines are expected failures inside negative cases; each top-level check passed. New PASS lines: 7 symlink cases (6 direct, 1 through fetch-dblp.sh --full), Crossref straddle and control, 2 NIT-3, 1 -q, 2 HTTP 416 with a failing MD5 tool, NIT-4 message. That each new case fails without its fix is from code reading, not from a run of the old code.
VALIDATION RESULTS: run (4): "manifest validation: 1350 checks, 0 failures", 508 distinct IDs, selection digest 281 lines c018a5f1a4360f91b6fb168ea2d4031dcc5c8cbda54455f1f2bbb1db92635f6d. Runs (6) and (7): "manifest validation: 1350 checks, 0 failures"; "PASS er: no OpenAlex author ID or ORCID iD (OpenAlex records, Crossref works and dblp excerpts) occurs in both dev and heldout (508 distinct IDs checked)"; same 281 lines and same digest, so the selected rows are byte-identical. SHA-256 from run (8): dsutil.py b28e6edfe29a697940d4cdbbe923ed70cd1e55560af148469c2fabe9b5ecad61; lib-datasets.sh 5e20b42c324b86d01162e8447d6a5cf6d0bc20900c7282ac89e719a6a184d833; fetch-dblp.sh 89a7d07d884628608fd4fd5d7276ef0dffe485f343202b6448d3739317b20e0e; selection.tsv 22def82ec54b45ee5999bec02dca46b2486bd4b113fe7cdc01590e81b317bd64; dataset-manifest.yaml dd249d615b525743d1ce82a494538992a3451b8c3b49f7f4e0e7316ba396b650; er-candidates.md fa46cd495b2b07c9d9dbb3e426f0049805001f3c651124d7564687769bed8272. git status lists the same paths as at the start.
ASSUMPTIONS: the fixture ran on the final script bytes; only manifest and schema text changed after it, and the manifest download_url the fixture reads was not changed. Crossref stores ORCIDs in message.items[].author[].ORCID (the shape of the stored envelope). Setting DS_DOWNLOADS inside the fixture after sourcing the library is an acceptable test seam; production never reads it from the environment. The 13 Crossref ORCID assertions per RV-P5-07 were not listed one by one; their inclusion is inferred from the unchanged count of 508 with 0 shared IDs.
RISKS: a link planted between a check and the write it guards (curl -o append, mv) is not prevented, only narrowed by the checks before and after the transfer; a committed symlink named datasets/downloads is not matched by the directory-only .gitignore rule (build-engineer), though --full now refuses it; CURL_HOME and proxy environment variables still affect curl; no total time limit; MD5 is the only pin against redirect hosts; the real release layout is unknown; the new INFO digest line changes validation output but not the check counts.
MANUAL ACTION REQUIRED: code-reviewer re-reviews this diff and the command summary above; the human decides on the download separately; before running it, commit datasets/scripts/** so the bytes run equal the bytes reviewed and later --verify calls pass the provenance gate; check CURL_HOME and proxy variables. Optional: route NIT-6 (fixture gaps) back to dataset-engineer, and NIT-7's BlindViewSuite part to entity-resolution-engineer.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer focused re-review of DS-P5-06 (MINOR-1, MINOR-2, nit 9, NIT-3, NIT-4, NIT-5, NIT-7 dataset side) and the updated full-download command; the human then decides on the download (decisions-10 item 1).
```

---

## ER-P5-12 (entity-resolution-engineer) — hand-back 2026-11-12, verbatim

Orchestrator note (not part of the report): routed per decisions-11.md items 2 and 3; interrupted once by an API usage limit and resumed by SendMessage; no full `sbt check` allowed; at most three `sbt entityResolution/test` runs.

ER-P5-12 is done: the policy is now er-policy.v8. Both human decisions (decisions-11 items 2 and 3) are in, with regression tests. My last allowed `sbt entityResolution/test` run (3 of 3) passed: "[info] Passed: Total 368, Failed 0, Errors 0, Passed 368". That is 351 before plus 17 new tests. `sbt check` was not run. No gold-set evaluation was run, because no human labels exist. 12 tests under tests/** will now fail; they are listed below for test-engineer.

**Part A: record keys need an established publication stage (item 2)**
- **Rule.** For a work pair, a shared dblp-key or scopus-eid now decides only when the stage is established (same-stage-preprint, same-stage-published or same-stage-venue). This holds whether or not a preprint identifier is shared.
- **When the stage is cross-stage or uncertain** and no T1 agreement (orcid, dblp-pid, doi, arxiv) decides, the pair stays UNRESOLVED on exact-id at confidence 0.0. It is not LLM-eligible and no fallback path can accept it. It records:
  - reason `record-key-agreement-blocked-by-publication-stage`, plus `publication-stage-<verdict>`;
  - rule `exact-id.unresolved.record-key-agreement-blocked-by-publication-stage.<scheme>`.
- **How the v6/v7 blocks fit in.** The general rule covers every pair the v6 (preprint DOI) and v7 (arXiv) blocks applied to. Those blocks' own codes are still recorded, in addition, when their trigger applies. This kept every V6/V7 assertion intact.
- **Recorded only where it decides.** The new codes appear only on the step-6c outcome the block decided. Steps 2 and 3 (conflict handling) are unchanged, ACCEPTED never carries conflicts, and researcher pairs are unaffected.
- **Coverage (PolicyV8Suite).**
  - RV-P5-08 inputs (a)-(d), both keys, both orientations, six profiles, including a score at or above 0.85 and the exact title-year-authors rule.
  - `{key}` vs `{key}` with no venue: UNRESOLVED.
  - Controls that still accept: same venue; two pure preprints with no shared preprint identifier; a shared publication DOI.
  - T1 CONFLICT and step-3 CONFLICT, plus a sweep showing a record key decides alone only at an established stage.

**Part B: arXiv base-ID comparison (item 3, m7)**
- **One normalisation.** `ExactIdentifiers.agreement` is new and uses the same `comparable` function as `disagreement`: strip a trailing vN, keep old-style and new-style IDs as they are. The Resolver takes its arXiv agreement from it. So:
  - v1 vs v2, v1 vs bare, and `hep-th/9901001` vs `hep-th/9901001v3` agree;
  - `2101.00001` vs `2101.00002`, and old-style vs new-style, disagree and give CONFLICT as T1;
  - agreement and disagreement are mutually exclusive (tested by sweep).
- **Original IDs kept.** The candidate's `exactIdAgreements` is unchanged. A new field, `ResolutionOutcome.policyAgreements`, keeps the versioned originals of both sides, and so do contradicted-agreement conflicts. A fold-only agreement adds reason `arxiv-agreement-by-base-submission-id`.
- **Stage rule unchanged.** Two pure preprints with v1/v2 accept at same-stage-preprint. The MINOR-1 input (A={key, v1}, B={key, v2, doi}) is now an arXiv agreement and is UNRESOLVED.
- **Policy text.** "versions vN are compared as one value" is now true, and the scope text explains the base-ID rule.
- **Features not bumped.** er-features stays v2. I did not change `MatchFeatures`: changing it would change the candidates Hadoop builds. The normalisation is applied in the policy layer. This departs from the literal wording "MatchFeatures ~219-222 must use the same normalisation". The feature `id.arxiv.conflict` is still 1 for a v1/v2 pair; it has weight 0 and the policy does not read it.
- **RV-P5-05 #2 left open.** Relating an arXiv ID to its 10.48550 DOI compares two schemes. That creates new T1 conflict sources and needs a decision on which scheme the result is recorded under, so it is not small. Its record-key exposure (input (d)) is already closed by Part A.

**Test changes beyond the two named pins (please review)**
- **The two named pins:** PolicyV6Suite (~217) and PolicyV7Suite (~263) old accepts are now UNRESOLVED regressions. V6 also gains a same-venue accept control.
- **PolicyV3Suite ~504** "per-record identifiers accept without corroboration": `{key}` vs `{key}` with no venue is now UNRESOLVED by decision. The record-key cases now carry same-venue features, so the test still shows no corroboration is needed, and I added the no-venue UNRESOLVED assertion.
- **AdjudicationSuite** "work band pair sent only when stage established": its "two preprints" case used v1/v2, which now agree and are ACCEPTED on exact-id (asserted).
  - The case is now arXiv ID vs arXiv-form DOI.
  - Candidates are built just before each decision. The fixture registry is keyed by candidate value, so this pair collided with the `{arx}` vs `{}` case.
- **Version pins v7 to v8 only:** PolicyV3/V4/V5/V6/V7, MatchPolicySuite, ResolverSuite, AdjudicationSuite (2), ReviewQueueSuite, GoldMembershipSuite, CuratedRunReaderSuite (2), EvaluationBatchSuite (2).

**Optional nits:** NIT-2 is done (the sensitivity method text now says the LLM band and adjudication are not modelled). NIT-1 is not done: renaming `settings.configuredModelId` changes the report JSON, and EvaluationBatchSuite:267 pins it.

**tests/** tests that will fail (for test-engineer; predicted from reading, not run)**
1. MatchContractCrossModuleSuite, 4 tests:
   - "every Resolver outcome kind…" (line 150);
   - "config band ordering…" (activePolicy at 243; also line 286 expects v8 to be refused, and it is now active; v7 should join the retired list);
   - "config loader…" (334-343);
   - "policy versioning: v7…" (383-429: DefaultResource, er-policy-file.v7, RetiredV7Resource now exists).
2. ClusterCorroborationAdversarialSuite, 3 tests through `ActivePolicyVersion = "er-policy.v7"` (385): "preprint DOI (decisions-07 a)" (466), "(decisions-07 c) shared publication DOIs" (606), "(decisions-07 c) case and resolver-URL forms" (660).
3. ArxivRecordKeyScoreGateAdversarialSuite, 2 tests:
   - "decisions-09 item 1…" (164): `ActivePolicyVersion` v7 pin only.
   - The CHARACTERIZATION at 319-331: this is the intended behaviour change, now UNRESOLVED with the new reason. It should become a regression.
4. MembershipEvaluationAdversarialSuite, 3 tests through `activePolicy = "er-policy.v7"` (464): "entry-point premises" (484), "freeze mismatch and --provisional…" (497), "199 reviewed…" (593).

Read and expected to pass: ExactIdPrecedenceAdversarialSuite and AdjudicationAdversarialSuite. Not read: BlindViewLeakageAdversarialSuite and UnicodeNormalizationRegressionSuite.

STATUS: COMPLETE
OWNER: entity-resolution-engineer
WORK ORDER: ER-P5-12 (Phase 5): decisions-11 item 2 (record keys decide only at an established publication stage, for both key paths, with conflict handling preserved) and item 3 (RV-P5-04 m7: arXiv base-ID comparison for agreement and disagreement, original IDs kept, still stage-gated; RV-P5-08 MINOR-1); optional RV-P5-08 NIT-1/NIT-2; one bump to er-policy.v8 / er-policy-file.v8, with v7 retired.
FILES CREATED:
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/resources/scholarchain/entityresolution/policy/er-policy.v8.json
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/policy/PolicyV8Suite.scala (17 tests)
FILES MODIFIED (all under /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/):
- main/scala/scholarchain/entityresolution/identifiers/ExactIdentifiers.scala (new `agreement` and `agreesOnlyByComparable`)
- main/scala/scholarchain/entityresolution/policy/Resolver.scala (arXiv agreement from ExactIdentifiers.agreement; `policyAgreements`; general record-key stage block; Rules.recordKeyBlockedByStage; Reason.RecordKeyBlockedByStage and ArxivAgreementByBaseId; Scaladoc)
- main/scala/scholarchain/entityresolution/policy/MatchPolicy.scala (er-policy-file.v8; DefaultResource v8; v7 added to both retired sets; RetiredV7Resource)
- main/scala/scholarchain/entityresolution/evaluation/GoldSetEvaluation.scala (NIT-2: SensitivityMethod text only)
- test/.../policy/PolicyV6Suite.scala, PolicyV7Suite.scala (named old-accept pins replaced; v8 pins)
- test/.../policy/PolicyV3Suite.scala (v8 pins; per-record-identifier test as described above)
- test/.../adjudication/AdjudicationSuite.scala (v8 pins; band-stage test as described above)
- test/.../policy/{PolicyV4Suite,PolicyV5Suite,MatchPolicySuite,ResolverSuite}.scala, test/.../review/{ReviewQueueSuite,GoldMembershipSuite,CuratedRunReaderSuite}.scala, test/.../evaluation/EvaluationBatchSuite.scala (v8 pins only)
- er-policy.v7.json is unchanged and kept as refused history. MatchFeatures.scala is unchanged. Not touched: tests/**, core, datasets/**, hooks, settings, docs/agent-runs, the test marker, prompts.
COMMANDS RUN:
- git ls-files (ER tracked and untracked; core; tests/er): ok
- Three Bash calls refused by guard-dangerous.sh before execution (two grep calls hit the shell-syntax-gate on quote characters; one `git diff --stat --no-index` had no allowlist entry). Nothing ran. These were not audit refusals.
- sbt entityResolution/Test/compile: success (27 s)
- sbt entityResolution/test, run 1: one attempt got no verdict from the auto-mode classifier and did not execute; the retry ran. Exit 1: 21 failures across 10 suites (version pins, the PolicyV3 per-record-identifier pin, the AdjudicationSuite v1/v2 band case).
- sbt entityResolution/test, run 2: exit 1; 2 failures (the AdjudicationSuite fixture-key collision, and a second EvaluationBatchSuite pin at line 276).
- sbt entityResolution/Test/compile: success (10 s)
- sbt entityResolution/test, run 3 of 3: success
- git status --porcelain: ok, same path set as at the start, no audit refusal
- sbt check: NOT run (work order). No sbt process is left running.
TEST RESULTS: run 3 console, verbatim: "[info] Passed: Total 368, Failed 0, Errors 0, Passed 368" / "[success] Total time: 6 s". PolicyV8Suite 17/17. All other suites pass with the same test counts as before. tests/**: not run; 12 predicted failures listed above.
VALIDATION RESULTS:
- Policy er-policy.v8 (file er-policy-file.v8), calibrated=false. Features er-features.v2 and blocking er-blocking.v2 unchanged; no feature value changed. Thresholds unchanged: accept 0.85, reject 0.2, LLM band [0.4, 0.7] (configuration overrides). Prompt er-adjudication-prompt.v2 and response schema er-adjudication-response.v1 unchanged; the new outcomes are exact-id and never reach the LLM. Model ID only from models.llmModelId; no adjudicator ran.
- Gold-set evaluation NOT run: no human labels exist and the goldSetEval alias is inactive. So there is no confusion matrix, precision/recall/F1, unresolved rate, gold threshold sensitivity, or FP/FN examples. The sample probe (SampleQueueProbeSuite) still passes.
ASSUMPTIONS:
- "Uncertain" includes `{key}` vs `{key}` with no stage marker and no venue agreement; the policy's existing definition says so.
- A decision record may show the original IDs through `policyAgreements`, conflict values and the endpoint records, because core's MatchDecision has no field for agreement values.
- Keeping MatchFeatures exact (no er-features.v3) meets "same normalisation" at the policy level.
RISKS:
- Recall: pairs that share a record key but have no venue agreement or DOI now need a manual decision. How often this happens in real data is unknown.
- The candidate feature `id.arxiv.conflict` is still 1 for v1/v2 pairs (weight 0; the policy does not read it). Folding it would need er-features.v3 and hadoop-engineer.
- PolicyFixtures stores endpoint IDs keyed by candidate value, so two pairs with equal features and agreements can collide in tests (test-only hazard; worked around in AdjudicationSuite).
- Still open: RV-P5-05 #2, RV-P5-08 NIT-1 and NIT-3, RV-P5-04 m8/N1/N2, RV-P5-05 #3.
MANUAL ACTION REQUIRED:
- Route the 12 tests/** tests above to test-engineer: pin v8, add v7 to the retired lists, and turn ArxivRecordKeyScoreGate 319-331 into a regression.
- Human: review the two additional pin changes (PolicyV3Suite, AdjudicationSuite), then run one foreground `sbt check`.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: test-engineer updates the 12 tests/** tests; the human runs one foreground `sbt check`; then a focused code-reviewer review of decisions-11 items 2 and 3 and the two extra pin changes.

---

## DS-P5-07 (dataset-engineer) — hand-back 2026-11-12, verbatim

Orchestrator note (not part of the report): routed per decisions-12.md (human committed checkpoint 5e324bd and ran the download and `--verify`). Extraction was not run.

DS-P5-07 is complete. The local DBLP archive matches the human's values, and the manifest now records the retrieval date, byte size and SHA-256. The extraction command does make network requests: it extracts the DBLP records offline, then contacts api.crossref.org and possibly api.openalex.org in the same run. No offline-only mode exists today.

**1. Cross-check (read-only)**
- **SHA-256:** my `shasum -a 256` of the archive printed `20e45961bec5610dc07b8e932ccd27a2387534cfa12c92a24289fe873aebe969  datasets/downloads/dblp/full/dblp-2026-10-03.xml.gz`.
- **Sidecar:** the `.sha256` file holds the same value: `20e45961bec5610dc07b8e932ccd27a2387534cfa12c92a24289fe873aebe969`.
- **Result:** that equals the human's value, character for character.
- **MD5 pin:** `datasets/scripts/fetch-dblp.sh --verify` passed the provenance gate and ended with:
  - `manifest validation: 110 checks, 0 failures`
  - `[fetch-dblp] full release verified: md5, sha256 sidecar, gzip -t`
  - `[fetch-dblp] done (mode --verify, fetched 0 bytes)`
  
  So the archive matches the pinned MD5 cbd329100ea1bbb2873fa2f6d414ea00 and passes `gzip -t`.
- **Byte size:** I could not measure it. None of my allowed commands gives a file size (no `wc`, `ls` or `stat`), and `--verify` does not print one. Because the SHA-256 is identical, the file I hashed has the same bytes as the file the human measured, so I recorded the human's 1107081001 and labelled it as human-observed.
- **Unchanged:** the archive and sidecar were not modified, moved or deleted.

**2. Manifest changes** (`/Users/taufeeqpatel/Desktop/ScholarChain/datasets/dataset-manifest.yaml`, dblp entry)
- `snapshot`: `retrieval_date` is "2026-11-12", `status` is "downloaded-and-verified", and a new `retrieved_by` field says the human ran the download from checkpoint 5e324bd, plus my cross-check.
- `size.observed`: `bytes` is 1107081001, `status` is "observed-by-human", with the file path, date, and a note that I did not measure the count myself.
- `integrity.our_sha256`: "20e45961bec5610dc07b8e932ccd27a2387534cfa12c92a24289fe873aebe969", plus a note that the human report, the sidecar and my run agree.
- `integrity.upstream_checksum.local_match`: says the MD5 pin matched on 2026-11-12.
- `validation_commands`: the `shasum` line now names the expected SHA-256, and a new `wc -c` line names the expected byte count as a manual check.
- The "exact compressed byte size" entry stays under `unverified`, because upstream still publishes only "1.03 GB". Its reason now gives our observed size.
- `last_reviewed` is "2026-11-12".
- The pin, URL, snapshot id and MD5 value are unchanged.

**Validation not automated.** The validator does not compare `size.observed.bytes` or `our_sha256` with the local archive. It checks only that the size is a positive integer and the SHA-256 is 64 hex characters. Adding that comparison means changing `datasets/scripts/dsutil.py` or `fetch-dblp.sh`. I did not, because that would make the script directory differ from the reviewed checkpoint 5e324bd until the human commits it again.

**3. Manifest validation after the edit**
`datasets/scripts/validate-manifest.sh --verify` ended with `manifest validation: 1350 checks, 0 failures`. It also printed:
- `PASS er: total 623126 bytes <= 8388608`
- `INFO er: stored files 171 (bytes 623126); planned direct requests without a stored record: 121`
- `PASS er: no OpenAlex author ID or ORCID iD (...) occurs in both dev and heldout (508 distinct IDs checked)`

The 121 records not yet stored are 120 DBLP excerpts plus 1 Crossref record (`dev/crossref-works/10.23919_cinc49843.2019.9005722-1a6002da.json`). Nothing in `datasets/scripts` was changed; `git diff --stat -- datasets` shows only the manifest (16 insertions, 9 deletions).

**4. Extraction command (not run)**
From the repository root:
```
datasets/scripts/fetch-samples.sh --sample datasets/er-candidates
```
This calls `bash datasets/scripts/fetch-er-candidates.sh --sample`, which runs `dsutil.py er-fetch <root> sample`. It requires `curl` even though the DBLP part is offline.

Order of work in `er_fetch`:
1. **Selection:** it reads `datasets/er-candidates/selection.tsv`. It recomputes the split and compares it with `datasets/er-candidates/split-assignment.tsv`, and refuses to continue if they differ.
2. **Ledger repair:** it drops a torn last line of `PROVENANCE.tsv` or `DBLP-RELEASE-RANGES.tsv` only if that line provably has too few columns. Otherwise it stops.
3. **DBLP extraction, offline:**
   - It reads the pin from the manifest: the snapshot id must be `dblp-2026-10-03`, the MD5 must be 32 hex characters, and the URL file name must match.
   - It reads `datasets/downloads/dblp/full/dblp-2026-10-03.xml.gz` and its `.sha256` sidecar. In one pass it decompresses and scans the release and computes its MD5 and SHA-256.
   - Nothing is written unless the MD5 equals the pin and the SHA-256 equals the sidecar. A missing release or sidecar, or a layout error, refuses the whole release.
   - Each record can fail on its own: key not in the pinned release, record larger than 200000 bytes, or excerpt content check failed (expected DOI or author). It is never replaced from another source.
   - Each excerpt counts against the 8 MiB budget before it is written; 623126 bytes are used now.
   - Each excerpt is written as `.part`, re-hashed and renamed, then its ranges row is written, then its `PROVENANCE.tsv` row is appended last.
   
   It writes 120 files under `datasets/samples/er-candidates/{dev,heldout}/dblp/<key with / replaced by __>.xml`, creates `DBLP-RELEASE-RANGES.tsv`, and appends to `PROVENANCE.tsv`. It makes no request to dblp.org, the DTD URL or drops.dagstuhl.de; DBLP items have no URL in the code, and `run()` raises an error if asked to fetch one.
4. **Network:**
   - The script then makes a request for every OpenAlex or Crossref record that has no `PROVENANCE.tsv` row. A record that has a row and a matching file is skipped. A record that has a row but a missing or changed file is recorded as a failure, with no request.
   - Hosts are only api.openalex.org and api.crossref.org. Requests are https only, redirects are followed but only to https, the timeout is 60 s, and there are no retries after HTTP 429.
   - From the current state there will be at least one request: `https://api.crossref.org/works?filter=doi:10.23919/cinc49843.2019.9005722&rows=2&select=...`. It failed in the last run with "expected exactly one Crossref item" and will probably fail again, which makes the run exit 1.
5. **OpenAlex authors:** for each stored OpenAlex work, up to 6 author IDs are requested from `api.openalex.org/authors/<A-ID>` if not already stored. The last failure file lists no author failures, so I expect none, but I did not verify each work.
6. **Failure file:** `FETCH-FAILURES.tsv` is rewritten completely.

Timing caveat: the 500 s run time limit starts before the DBLP scan. If hashing and scanning the 1.1 GB archive in Python takes longer than 500 s, every pending network request is skipped as "run time budget reached". So whether the network is contacted also depends on timing; it is not a guarantee either way.

The cross-split check is not part of `--sample`. It runs only in verification. After extracting, run `datasets/scripts/fetch-samples.sh --verify datasets/er-candidates datasets/downloads/dblp/full/dblp-2026-10-03.xml.gz`. That also re-reads every recorded byte range from the release, and the cross-split check will then include the ORCID iDs in the DBLP excerpts.

**Offline-only option.** There is no flag or mode for it today. `--dry-run`, `--verify` and `--fixture` are offline but do not extract anything. A minimal offline mode would need:
- an `er_fetch` mode that stops after `er_dblp_step`, skipping the OpenAlex and Crossref requests and the author step;
- a `FETCH-FAILURES.tsv` update that keeps the rows for those sources instead of rewriting the file;
- no `curl` requirement;
- the time limit either starting after the DBLP step or not applying;
- a test case in the extractor fixture;
- an entry point using a path argument (for example `fetch-samples.sh --sample datasets/er-candidates/dblp-release`) so it fits my existing allowlisted command shape; a new flag would need an allowlist change by factory-engineer.

It would also need review and a new human commit of `datasets/scripts`. I added nothing without your decision. A workaround that is not enforced would be for you to run the current command with networking turned off; the Crossref request would then fail as a recorded failure.

STATUS: COMPLETE
OWNER: dataset-engineer
WORK ORDER: DS-P5-07 (Phase 5): cross-check the DBLP archive, record retrieval date, size and SHA-256 in the manifest, run manifest validation, report the extraction command and its network behaviour.
FILES CREATED: none
FILES MODIFIED: /Users/taufeeqpatel/Desktop/ScholarChain/datasets/dataset-manifest.yaml
COMMANDS RUN:
- `git status --porcelain` (the whole tree at the start; no UNATTRIBUTED refusal)
- `shasum -a 256 datasets/downloads/dblp/full/dblp-2026-10-03.xml.gz datasets/downloads/dblp/full/dblp-2026-10-03.xml.gz.sha256` (the archive hash is the matching value above; the sidecar file itself hashes to f411fc827907095efc1980336d928952488de77dbb9a2682f545338b793fe0b1)
- `datasets/scripts/fetch-dblp.sh --verify` (passed, output above)
- `git log --oneline --max-count=3 -- datasets/scripts` (printed 5e324bd and fee6135)
- `datasets/scripts/validate-manifest.sh --verify` (1350 checks, 0 failures)
- `git status --porcelain -- datasets`: REFUSED by guard-dangerous.sh with `BLOCKED [no-allowlist-entry]`. The command was my mistake (`git status` takes no path in my allowlist); nothing was executed, and it was not an audit refusal.
- `git diff --stat -- datasets` (only `datasets/dataset-manifest.yaml`: 16 insertions, 9 deletions)
TEST RESULTS: none. sbt was not run, as the work order required, and no Scala was changed.
VALIDATION RESULTS: `fetch-dblp.sh --verify` before the edit: 110 checks, 0 failures; full release verified (MD5, SHA-256 sidecar, gzip -t). `validate-manifest.sh --verify` after the edit: 1350 checks, 0 failures; ER sample total 623126 bytes; 121 planned records not stored (120 DBLP, 1 Crossref); cross-split check passed over 508 IDs. The archive SHA-256 equals the human's value and the sidecar. The byte size was not measured by me.
ASSUMPTIONS:
- The byte count 1107081001 is the human's measurement. I accepted it because my SHA-256 of the same file matches.
- "retrieval_date" means the human's download date, 2026-11-12, from decisions-12.md.
- `fetch-samples.sh` keeps its executable bit as committed; if not, prefix the command with `bash`.
RISKS:
- The recorded size and SHA-256 are not compared with the archive automatically; only the manual commands in `validation_commands` do that.
- The extraction run also makes network requests (at least the Crossref one, which will probably fail and make the run exit 1), and whether they happen depends on the 500 s time limit.
- Adding ORCID iDs from the 120 DBLP excerpts may cause the cross-split check to fail at the next `--verify`.
- I did not check whether text in the manifest's `er_candidates` section still describes the release as pending download.
MANUAL ACTION REQUIRED:
- Review and commit the manifest change.
- Decide whether to run the extraction command as it is (it will contact the network), with networking off, or after an offline-only mode is added.
- Decide whether to authorise changing `datasets/scripts` so validation checks the recorded size and SHA-256 (this needs a new checkpoint commit).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Human review of the manifest diff and a decision on how to run the extraction. Then run `datasets/scripts/fetch-samples.sh --sample datasets/er-candidates`, followed by `datasets/scripts/fetch-samples.sh --verify datasets/er-candidates datasets/downloads/dblp/full/dblp-2026-10-03.xml.gz`, plus code-reviewer review of the manifest change.

---

## DS-P5-08 (dataset-engineer) — hand-back 2026-11-12, verbatim

Orchestrator note (not part of the report): routed per decisions-13.md; manifest-only. The orchestrator then ran `git diff -- datasets/dataset-manifest.yaml` (no audit refusal; the human extraction had not yet run) and showed it to the human.

STATUS: COMPLETE
OWNER: dataset-engineer
WORK ORDER: DS-P5-08 (Phase 5). In the dblp manifest entry, make the `shasum -a 256` and `wc -c` validation commands copy-pasteable, move their expected values into adjacent notes, keep both checks manual, and fix any er_candidates text that still says the release is waiting to be downloaded.

FILES CREATED: none

FILES MODIFIED:
- /Users/taufeeqpatel/Desktop/ScholarChain/datasets/dataset-manifest.yaml (the only file changed. datasets/scripts/**, the archive, the sidecar, samples, selection and split files were not touched.)

What changed in this work order (the uncommitted DS-P5-07 changes in the same file are still there):
1. `datasets.dblp.validation_commands`: the two entries are now plain commands with no parenthetical text:
   - "shasum -a 256 datasets/downloads/dblp/full/dblp-2026-10-03.xml.gz"
   - "wc -c < datasets/downloads/dblp/full/dblp-2026-10-03.xml.gz"
   The other three entries are unchanged.
2. Format choice: I added a new key, `datasets.dblp.validation_command_notes`, directly after `validation_commands`. I read the validator to pick the form. In dsutil.py `validate_dataset` (lines 548-551), `validation_commands` must be a non-empty list of non-empty strings, so an entry that is a mapping would fail. The validator does not reject extra keys on a dataset, so a sibling key is accepted. No file in datasets/scripts was changed. The new key is a list of two mappings, one per command:
   - shasum entry: `command` (the exact command string), `expect: "20e45961bec5610dc07b8e932ccd27a2387534cfa12c92a24289fe873aebe969"`, `expect_field: "integrity.our_sha256"`, and `check` ("Manual. ... the validator checks only the format of integrity.our_sha256, not the local file.").
   - wc entry: `command`, `expect: "1107081001"`, `expect_field: "size.observed.bytes"`, and `check` ("Manual. ... observed by the human (decisions-12.md), not by dataset-engineer; the validator does not measure the local file.").
   Both checks remain manual. Nothing new is automated.
3. er_candidates corrections, because the download is now done:
   a. `byte_range_ledger.state`: was "No dblp excerpt exists yet (the release has not been downloaded), so the file is not present." It now says the pinned release was downloaded by the human and verified on 2026-11-12, that the ranges file is written only by the human-run extraction (`retrieval.sample_command`) and is absent until that run, and that `--verify` reports the current state.
   b. `validation_commands`, 4th entry: I removed the trailing " (after the human download)". That condition is now met, and the command becomes copy-pasteable. The command itself is unchanged.
   c. `unverified` item "Layout of the real dblp-2026-10-03 release ...": the reason started "The release has not been downloaded." It now starts "The release was downloaded and verified on 2026-11-12, but its records have not yet been read by the extractor." The rest of the reason and the item itself are unchanged, and the item stays unverified.
   I left "Which of the 120 selection.tsv dblp keys are in dblp-2026-10-03" ("The release has not been read.") and the missing_record_policy text "(UNVERIFIED until the release is read)" as they are. Both are still true: the validator output shows every dblp excerpt as "not stored", so the extraction has not run yet.

COMMANDS RUN:
- `git diff --stat -- datasets/dataset-manifest.yaml` (before editing): 1 file changed, 16 insertions(+), 9 deletions(-). These are the earlier DS-P5-07 changes.
- `git diff -- datasets/dataset-manifest.yaml` (before and after editing)
- `datasets/scripts/validate-manifest.sh --verify` (once, after the edits)
- `git diff -- datasets/dataset-manifest.yaml` (final diff for the orchestrator to show the human)
- Two calls were refused by the guard and did not run: a `git diff ...; grep ...` compound (refused for shell-syntax characters) and `tail -n 15 <tool-result file>` (no matching allowlist entry). I read the validator output with the Read tool instead. Neither refusal was an UNATTRIBUTED audit refusal.

TEST RESULTS: none. No sbt run, as the work order required. No other dataset tests were run.

VALIDATION RESULTS:
- `datasets/scripts/validate-manifest.sh --verify` summary line, verbatim: `manifest validation: 1350 checks, 0 failures`
- The last PASS line before it: "PASS er: no OpenAlex author ID or ORCID iD (OpenAlex records, Crossref works and dblp excerpts) occurs in both dev and heldout (508 distinct IDs checked)". The INFO lines show every dblp ER excerpt as "not stored", so the human extraction had not run when this validation ran.
- The full output (1492 lines) is in /Users/taufeeqpatel/.claude/projects/-Users-taufeeqpatel-Desktop-ScholarChain/107a6780-f081-4d82-af1b-11c6ff34610e/tool-results/b0bt9zd8c.txt

ASSUMPTIONS:
- A sibling key (`validation_command_notes`) satisfies "adjacent notes". It is machine-readable and placed right after the list, and the validator accepts it. I did not use YAML `#` comments: the parser allows only full-line comments, and parsers drop them.
- I kept `wc -c < file` instead of `wc -c file` because it prints only the number, which can be compared directly with `expect`. Both forms can be pasted into bash or zsh.
- Removing "(after the human download)" falls under the work order's permission to correct er_candidates text about the download. I am reporting it here so the human can reverse it if they disagree.

RISKS:
- `integrity.our_sha256_note` (DS-P5-07 text) still ends "See validation_commands for the manual check." That is still accurate, but the expected value is now in `validation_command_notes`. I did not reword it, to keep the change minimal and keep to a single validator run. It is a nit that can be fixed in a follow-up if wanted.
- The validator does not check `validation_command_notes`. If someone edits one of the commands later, the matching note will not be flagged as stale.
- The final manifest diff for the manifest-only commit still includes the uncommitted DS-P5-07 changes (snapshot, observed size, our_sha256, MD5 local_match, the dblp unverified size item, last_reviewed) as well as the DS-P5-08 changes above. The orchestrator's `git diff -- datasets/dataset-manifest.yaml` shows them all.

MANUAL ACTION REQUIRED:
- The orchestrator shows the human the final `git diff -- datasets/dataset-manifest.yaml`, as requested. The human reviews it and commits only that file if they accept it.
- The human may run `datasets/scripts/fetch-samples.sh --sample datasets/er-candidates` (decisions-13). After that run, dataset-engineer should run the verification with the release, and the er_candidates `byte_range_ledger.state` and the two "not yet read" unverified entries should be updated from the observed results.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none

NEXT GATE: Human review of the manifest diff and the manifest-only commit. Then the human-run ER extraction, followed by dataset-engineer verification (`fetch-samples.sh --verify datasets/er-candidates datasets/downloads/dblp/full/dblp-2026-10-03.xml.gz`), with the Crossref lookup reported separately from the dblp extraction, as decisions-13 requires.

---

## ER-P5-13 (entity-resolution-engineer) — hand-back 2026-11-12, verbatim

Orchestrator note (not part of the report): routed per decisions-13.md; documentation only, no sbt.

I wrote the auditor note at `/Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/docs/audit-notes.md`, section AN-1. I added no pointer to it from the policy or the label guide. Both are files loaded at runtime, so either edit would need a version bump, which this work order does not allow. I also found a sentence in `er-policy.v8.json` that is misleading for this exact case, and I am reporting it rather than changing it (see RISKS).

The module had no README or docs location before, so I created `modules/entity-resolution/docs/`. The note covers each item the work order asked for:
- **When it happens:** a work pair whose arXiv IDs share the base submission ID but no versioned value. It shows on every outcome of such a pair: ACCEPTED at `same-stage-preprint`, and UNRESOLVED or CONFLICT otherwise.
- **Which stored fields look wrong and why:** the review row's `exactIdAgreements=[]`, and `id.arxiv.both/agree/conflict = 1/0/1` in both the review row and `MatchDecision.features`. These come from er-features.v2, which compares exact values. The policy folds versions under er-policy.v8, and `ResolutionOutcome.policyAgreements` is kept in memory only. The one exception is a contradicted-agreement conflict, whose entry does carry the original values.
- **The deciding evidence:** rule `exact-id.agree.arxiv` and reason `arxiv-agreement-by-base-submission-id`, found in `system.rulesFired` / `system.reasonCodes` in a review row and as top-level fields in a `MatchDecision`. It also notes NIT-1: the reason code is missing when one value is shared exactly, but then the stored fields do not contradict each other.
- **How an auditor reproduces the agreement:**
  1. Find the endpoint work IDs and run from the row or the `MatchDecision`.
  2. Read `WorkRecord.arxivId` plus any `additionalIds` entries of scheme `arxiv`. These are the same values er-features.v2 used.
  3. Strip one trailing `v<digits>`, but only if what remains is still a valid arXiv ID.
  4. Compare the base IDs: a shared base means the policy agreement holds, and the agreement values are every original on either side with that base, versions kept.
  5. If the base IDs are disjoint, the policy saw a disagreement, so the outcome is CONFLICT or REJECTED and the reason code must be absent.
- **Not folded (NIT-2):** subject-class forms such as `math.AG/0101001` vs `math/0101001` are not equated. Old-style and new-style IDs never share a base ID.
- **Deferral and labelers:** the row schema v5 change (persisting `policyAgreements`) is recorded as deferred by decisions-13. Labelers never see these fields, because `BlindView.HiddenFields` hides `features`, `exactIdAgreements`, `rulesFired`, `reasonCodes` and `conflicts`.

STATUS: COMPLETE
OWNER: entity-resolution-engineer
WORK ORDER: ER-P5-13 (Phase 5), documentation only: auditor note for RV-P5-10 MINOR-1 (folded arXiv agreement vs stored er-features.v2 evidence), per the human decision in decisions-13; review-row schema change deferred.
FILES CREATED: /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/docs/audit-notes.md
FILES MODIFIED: none
COMMANDS RUN:
- `git ls-files -- modules/entity-resolution` (succeeded)
- `git ls-files --others --exclude-standard -- modules/entity-resolution` (succeeded)
- `git ls-files -- modules/core/src/main/scala` (succeeded)
- `git ls-files --others --exclude-standard -- modules/entity-resolution/docs` (succeeded; lists only the new file)
- Refused by the guard, nothing executed: two `grep` calls (character gate) and `git status --porcelain -- modules/entity-resolution` (no allowlist row for that shape).
TEST RESULTS: not run. The work order says not to run sbt, the change is a single Markdown file that is not sbt source, and no Scala, test or resource file was touched.
VALIDATION RESULTS: I checked every claim in the note against the code:
- Version folding: `ExactIdentifiers.scala` 67, 89-97 and 127-157, plus core `Identifiers.isValidArxiv` (lines 126-129).
- Rules, reason codes, the fold reason, and `policyAgreements` held only in memory: `Resolver.scala` 120-155, 169, 184, 287, 637-687 and 1011-1030.
- Fields written to the review row: `ReviewQueue.scala` 76-99 and 201-233.
- Fields hidden from labelers: `BlindView.HiddenFields` (lines 237-270).
- How endpoint IDs are rebuilt and how a candidate with flags that don't match its endpoint records is refused: `EndpointIdentifiers.scala`.
- How er-features.v2 computes `id.arxiv.*`: `MatchFeatures.scala` 215-238.
- Where the arXiv values sit on a work: core `WorkRecord` (`arxivId`, `additionalIds`, `dedicatedIds`).
ASSUMPTIONS:
- A new `modules/entity-resolution/docs/` directory is acceptable, since there was no existing README or docs location.
- `.md` files under the module are neither loaded at runtime nor treated as test-gated source by `verify-tests-ran.sh`.
- The NIT-2 statement that arXiv treats subject-class forms as one submission is the reviewer's, from memory. The note says arXiv "may" regard them as one.
RISKS:
- Misleading text in a runtime-loaded file (reported, not changed): the `scope` of the arxiv rule in `er-policy.v8.json` (line 142) says "the original versioned identifiers are kept in the decision record". Under row schema v4 and the current `MatchDecision`, that is true only through the endpoint records. The note says so. Fixing the text needs a new policy version (er-policy.v9) or the deferred row v5 change, which is the human's decision.
- The Resolver Scaladoc (lines 394-396) says the originals are "kept in `ResolutionOutcome.policyAgreements`". That is accurate but holds in memory only. I left it unchanged because the work order forbids Scaladoc changes.
- Auditors still have to re-read endpoint records to confirm a folded agreement until the deferred schema change lands.
- NIT-1 (the reason code under-reports the fold) and NIT-2 (subject-class forms are not folded) are still open code findings. The note only documents them.
- I could not view my own diff with `git status --porcelain` (refused by the allowlist). Untracked-file listing confirms only the new doc file is new under `docs/`.
MANUAL ACTION REQUIRED:
- Human: review the note before gold labeling.
- Human: decide whether the er-policy.v8.json wording in RISKS needs a later policy version, or should wait for the deferred row-v5 change.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Orchestrator routes the note to code-reviewer for a read-only check against RV-P5-10 MINOR-1, then the human reviews it ahead of gold labeling.

---

## DS-P5-09 (dataset-engineer) — hand-back 2026-11-13, verbatim

Orchestrator note (not part of the report): routed per extraction-failure-01.md (human decision); one read-only inspection run was moved to the background by the harness and completed (exit 0) before this hand-back.

## DS-P5-09 report: dblp record boundaries fixed, ready for the human script checkpoint

The extraction failure was the scanner's fault, not the release's. The pinned release often puts the start of one record on the same line as the end of the previous one, and the old scanner only accepted record start tags at the start of a line. I confirmed this with a new read-only inspection of the local archive and fixed the boundary rule in `dsutil.py`. Run in memory on the real release (nothing written), the fixed rule accepts the layout: 119 of the 120 selected keys cut cleanly and one fails its own selection check. The offline fixture passes (120 checks, 0 failures) and manifest validation passes (1350 checks, 0 failures). The human needs to commit the scripts before retrying extraction.

**What the archive shows (step 1).**
- The archive passed the gate in the same pass as the read: MD5 `cbd329100ea1bbb2873fa2f6d414ea00` equals the pin, and SHA-256 `20e45961bec5610dc07b8e932ccd27a2387534cfa12c92a24289fe873aebe969` equals its sidecar.
- Decompressed stream: 5349755924 bytes, 126952598 lines.
- Header (bytes 0+96): `<?xml version="1.0" encoding="ISO-8859-1"?>\x0a<!DOCTYPE dblp SYSTEM "dblp-2023-06-28.dtd">\x0a<dblp>\x0a`. The last non-blank line (offset 5349755916) is `</dblp>\x0a`.
- The failing key, `conf/dac/0001AG12` (bytes escaped, a newline shown as `\x0a`):
  - previous line (offset 43784455): `<url>db/conf/dac/dac1965.html#Morrissey65</url>\x0a`
  - the failing line (offset 43784503) starts with `</inproceedings>`, and the start tag follows directly at offset 43784519: `<inproceedings mdate="2018-11-06" key="conf/dac/0001AG12">\x0a`
  - its end-tag line (offset 43784994): `</inproceedings><inproceedings mdate="2018-11-06" key="conf/dac/OdawaraHIYD87">\x0a`
- The same shape appears for `conf/bdc/RazaqTB16` (tag at offset 79636142) and `conf/cdc/JinJYW02` (tag at offset 103554191).
- Across the whole release, 12998506 record start tags carry a key:

| Where the start tag sits | Count |
|---|---|
| At the start of its line | 8055743 |
| Directly after exactly one record end tag on the same line | 4942406 |
| After other bytes (e.g. one space, or `</phdthesis> `; around offset 5.277e9; none is a selected key) | 357 |

- No line holds two start tags. Of the record end tags: 8055734 end their line, 4942406 are followed directly by the next start tag, and 366 by other bytes.
- The inspection runs through the allowlisted shape `datasets/scripts/fetch-samples.sh --dry-run datasets/er-candidates datasets/downloads/dblp/full/dblp-2026-10-03.xml.gz`. It writes nothing and makes no network request.

**The fix (step 2, `datasets/scripts/dsutil.py`).** The scanner is now the class `_DblpScanner`.
- A record's bytes run from the `<` of its start tag to the `>` of its first end tag, plus the line ending directly after it if there is one.
- A start tag is accepted only in three positions: at the start of its line; directly after exactly one record end tag and nothing else on that line; or directly after the previous wanted record on the same line.
- After the end tag, the line must end or the next record's start tag must follow directly. No other record start tag may appear inside the record.
- Anything else is still `release-layout-error`, and the message now shows the escaped bytes involved.
- The offline `--verify` check now also requires each record slice to end with its own end tag.
- Unchanged: exact byte slices, decompressed offsets and lengths in `DBLP-RELEASE-RANGES.tsv`, the one-pass MD5 + SHA-256 gate before any write, the 200000-byte cap, the DOI/author content checks, the 8 MiB budget, all-or-nothing write order, and the `--verify --with-release` byte comparison. The pin, URL, MD5, snapshot id, `selection.tsv` and `split-assignment.tsv` are untouched.
- This also closes a silent gap in the old scanner: a wanted key whose tag followed another whole record on the same line would have been reported as absent from the release. It is now refused instead.

**Scanner verdict on the real release (in memory, nothing written).**
- All 120 selected keys are in the release, including the 5 suspected 2025-2026 keys.
- 119 cut cleanly: 57 start directly after an end tag and 56 at a line start; 57 end directly before the next record and 62 at the line end. Their excerpts total 75225 bytes; 623126 bytes are already in `PROVENANCE.tsv`; the budget is 8388608.
- One fails on its own: `conf/armor/BustosFAR12` (heldout) gives `excerpt-check-failed: selection check failed: no author 'Juan-Carlos Ruiz-Garcia 0001'`. I did not investigate it or change `selection.tsv`; it is recorded as unverified in the manifest.

**Regression fixture (step 3).**
- The positive case is the realistic fixture with the 8 newlines between records removed, which reproduces the observed layout. A precondition check shows all 4 wanted tags sit where the old line-start rule refused them. The 4 records are extracted, their offsets match an independent search, and the offline check, `--with-release` comparison and end-to-end `er_fetch` all pass.
- The inspection command is also tested: it writes nothing on a good layout and prints the refusal on a bad one. A record slice cut one byte short fails the offline check.
- These layouts still fail closed and write nothing: field text, white space, two end tags, or a whole one-line record before a wanted tag; a start tag inside an open record; text between an end tag and the next start tag; `</dblp>` on the last record's end line.
- I did not run the fixture against the old code; "fails without the fix" rests on that precondition check plus the real-release observation.
- The fixture now prints its summary and any failing checks before the full log, so a truncated console still shows the result.

**Crossref miss (step 4).** It is recorded separately from the dblp failure in the manifest (`er_candidates.known_source_misses` plus an `unverified` entry) and in a new section of `datasets/schemas/er-candidates.md`. In short: `10.23919/cinc49843.2019.9005722` returned something other than exactly one item; the rejected body is never stored, so zero versus several items is unknown. It was not retried. Every later `--sample` run will request that one URL again, and that can change nothing except this one record. The OpenAlex work for the same DOI is a separate selection row, not a substitute.

**Files for the script checkpoint (step 6).** The human must commit `datasets/scripts/**` before retrying extraction, otherwise the provenance gate asks for approval on every script run.

| File | Status | SHA-256 |
|---|---|---|
| `datasets/scripts/dsutil.py` | modified | `b70cd144c1dc6ea329daeaadd451d3a77a7399f220677c863f595f9a950f3594` |
| `datasets/scripts/fetch-samples.sh` | modified | `055166b76f880aa16527e9bf82ea636decb16195dc83fcd2d6553a61fb83b354` |
| `datasets/scripts/fetch-er-candidates.sh` | modified | `904620c14506747cf4d40e9b527a8571f8dfaeed12f86276f22cac39c962c42f` |
| `datasets/dataset-manifest.yaml` | modified | `10c1b5683c582e5824636df02d59e07b93c656307fc97d6f3186c9acd5404310` |
| `datasets/schemas/er-candidates.md` | untracked, updated | `6645d75114451b25b131adc3f7f9adc66341628487c779db822ecb5403732998` |

```text
STATUS: COMPLETE
OWNER: dataset-engineer
WORK ORDER: DS-P5-09 (Phase 5): inspect the dblp release-layout-error at offset 43784503, fix record boundary detection, add regression fixture, record the Crossref miss separately
FILES CREATED: none (datasets/schemas/er-candidates.md was already present but untracked)
FILES MODIFIED: /Users/taufeeqpatel/Desktop/ScholarChain/datasets/scripts/dsutil.py; /Users/taufeeqpatel/Desktop/ScholarChain/datasets/scripts/fetch-samples.sh; /Users/taufeeqpatel/Desktop/ScholarChain/datasets/scripts/fetch-er-candidates.sh; /Users/taufeeqpatel/Desktop/ScholarChain/datasets/dataset-manifest.yaml; /Users/taufeeqpatel/Desktop/ScholarChain/datasets/schemas/er-candidates.md
COMMANDS RUN: git status --short; git diff --stat 5e324bd -- datasets; git log --oneline --max-count=5 -- datasets/scripts; git diff 5e324bd -- datasets/dataset-manifest.yaml; git diff --stat HEAD -- datasets/scripts; git show --stat HEAD; git ls-files (tracked and --others) -- datasets; datasets/scripts/fetch-samples.sh --verify datasets/er-candidates/fixture (3 runs: 1st exit 1, output truncated, so I made the fixture print failing checks first and fixed a wrong precondition; 2nd 120 checks 1 failure, a wrong expected count in my own check; 3rd 120 checks 0 failures); datasets/scripts/fetch-samples.sh --dry-run datasets/er-candidates datasets/downloads/dblp/full/dblp-2026-10-03.xml.gz (background, exit 0, read-only); datasets/scripts/validate-manifest.sh --verify (exit 0); shasum -a 256 on the 5 files above; git diff --stat HEAD -- datasets. Refused by the guard (nothing executed): git status --porcelain -- datasets/downloads; git status --short -- datasets.
TEST RESULTS: "er-fixture: 120 checks, 0 failures (temporary directory removed; repository not written)". sbt not run (forbidden by the work order; no Scala changed).
VALIDATION RESULTS: "manifest validation: 1350 checks, 0 failures" (includes "PASS er: no OpenAlex author ID or ORCID iD ... occurs in both dev and heldout (508 distinct IDs checked)"). Inspection: "er-inspect: scanner accepts the release layout; 120 dblp keys: excerpt-check-failed 1, ok 119; excerpt bytes 75225, bytes already in PROVENANCE.tsv 623126, budget 8388608 (nothing was written)".
ASSUMPTIONS: dblp record elements do not nest and field text cannot hold a literal "<" (dblp.dtd and XML escaping), so start and end tags are the record boundaries. A start tag after exactly one record end tag on the same line is unambiguous; every other prefix fails closed. The read-only in-memory scan is the inspection the work order allowed, not the extraction. The fixture's shared-lines variant is synthetic CC0-shaped data modelled on the observed bytes.
RISKS: the release has 357 start tags after other bytes (for example a space) near offset 5.28e9; none is a selected key today, but a future selection that hits one would be refused (fail closed). conf/armor/BustosFAR12 will fail excerpt-check-failed in the real run (cause not investigated). dsutil.py changed substantially (+564 lines diffstat, mostly fixture and inspection code); focused review is needed. FETCH-FAILURES.tsv was not touched, but the next --sample run will rewrite it (its evidence is preserved in extraction-failure-01.md). The retry will request the Crossref URL once more.
MANUAL ACTION REQUIRED: request focused code-reviewer review of the dsutil.py scanner (_DblpScanner, er_dblp_row_problems end check), cmd_er_inspect, the fixture additions, and the two shell routing changes. Then the human commits datasets/scripts/** (and optionally the manifest and schema notes) so the provenance gate passes, and retries "bash datasets/scripts/fetch-samples.sh --sample datasets/er-candidates". Afterwards run "--verify datasets/er-candidates datasets/downloads/dblp/full/dblp-2026-10-03.xml.gz".
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: focused code-reviewer review of DS-P5-09, then human script checkpoint commit and extraction retry
```

---

## DS-P5-10 (dataset-engineer) — hand-back 2026-11-13, verbatim

Orchestrator note (not part of the report): routed per extraction-result-02.md (human retry from checkpoint e86a620).

## DS-P5-10 report: the verify run against the local release passed

`datasets/scripts/fetch-samples.sh --verify datasets/er-candidates datasets/downloads/dblp/full/dblp-2026-10-03.xml.gz` ran once, in the foreground (it was not moved to the background), and passed. Verbatim summary line: **`er-verify --with-release: 2205 checks, 0 failures`**.
- The provenance gate did not ask and did not deny.
- No audit hook refused any call.
- The output has no FAIL lines.
- The full output is in /Users/taufeeqpatel/.claude/projects/-Users-taufeeqpatel-Desktop-ScholarChain/107a6780-f081-4d82-af1b-11c6ff34610e/tool-results/b1v9cysak.txt

### What the verify output shows (verbatim lines)

**Release gate:**
`PASS er: --with-release: dblp-2026-10-03.xml.gz md5 matches the pin and sha256 matches its sidecar (20e45961bec5610dc07b8e932ccd27a2387534cfa12c92a24289fe873aebe969)`
It is preceded by:
`PASS er: dblp records are bound to the pinned release dblp-2026-10-03 (md5 cbd329100ea1bbb2873fa2f6d414ea00)`

**Ledger, ranges file and excerpt files agree:**
- `PASS er: datasets/samples/er-candidates/PROVENANCE.tsv ends with a complete row`
- `PASS er: datasets/samples/er-candidates/DBLP-RELEASE-RANGES.tsv ends with a complete row`
- Each of the 119 dblp excerpts has these PASS lines:
  - `... byte ranges recorded`
  - `... byte ranges consistent (offline)`. This offline check covers: a ledger row has a ranges row, the ranges are ordered and add up to the file size, the SHA-256 values agree, and the slice rule holds.
  - `... recorded release sha256`

**Byte-range comparison with the release:** all 119 excerpts have a line of the form `PASS er: <file> equals release bytes header 0+96, record <offset>+<length>, footer 5349755916+8`. Examples:
- `PASS er: datasets/samples/er-candidates/heldout/dblp/conf__dac__0001AG12.xml equals release bytes header 0+96, record 43784519+491, footer 5349755916+8`. This is the key that caused the earlier release-layout-error.
- `PASS er: datasets/samples/er-candidates/dev/dblp/conf__bdc__RazaqTB16.xml equals release bytes header 0+96, record 79636142+507, footer 5349755916+8`
- `PASS er: datasets/samples/er-candidates/heldout/dblp/conf__cdc__JinJYW02.xml equals release bytes header 0+96, record 103554191+490, footer 5349755916+8`

**Dev/held-out overlap check:**
`PASS er: no OpenAlex author ID or ORCID iD (OpenAlex records, Crossref works and dblp excerpts) occurs in both dev and heldout (512 distinct IDs checked)`
That is 512 distinct IDs, up from 508 before extraction, so the dblp excerpt ORCIDs added 4 new ones. No identifier appears in both splits, so no human decision is needed.

**Budget:**
- `PASS er: total 698351 bytes <= 8388608`
- `INFO er: stored files 290 (bytes 698351); planned direct requests without a stored record: 2`

**Selection and split are unchanged:**
- `PASS er: selection parses: 70 units, 272 direct requests, 8 authors-of-work rows`
- selection.tsv sha256 `c018a5f1a4360f91b6fb168ea2d4031dcc5c8cbda54455f1f2bbb1db92635f6d` (comment lines excluded)
- `PASS er: datasets/er-candidates/split-assignment.tsv matches the deterministic rule`

### Saved counts by split and source (verify INFO lines)

| Split | dblp excerpts | OpenAlex works | OpenAlex authors | Crossref works | Total |
|---|---|---|---|---|---|
| dev | 41 | 45 | 6 | 6 | 98 |
| heldout | 78 | 87 | 14 | 13 | 192 |
| all | 119 | 132 | 20 | 19 | 290 |

This agrees with the human's summary (171 already present + 119 fetched = 290; 698351 bytes in the ledger).

**Units with at least one missing record:** 1 in dev and 1 in heldout. Both misses are kept; they were not retried or substituted, and FETCH-FAILURES.tsv was not touched.
- dev: unit `md-cinc-fotiadoukhv19` (multi-doi). Missing the Crossref work `10.23919/cinc49843.2019.9005722`. Verify line: `INFO er: not stored: datasets/samples/er-candidates/dev/crossref-works/10.23919_cinc49843.2019.9005722-1a6002da.json`
- heldout: unit `hr-juan-carlos-ruiz-garcia` (homonym-rare). Missing the dblp record `conf/armor/BustosFAR12`. Verify line: `INFO er: not stored: datasets/samples/er-candidates/heldout/dblp/conf__armor__BustosFAR12.xml`

### Manifest changes (only er_candidates text that the observed results made stale)

Diffstat: 1 file changed, 1 insertion, 3 deletions.

1. **`er_candidates.byte_range_ledger.state`** said "until that run it is not present", which is no longer true. It now records:
   - the human retry from checkpoint e86a620 (at_utc 2026-11-13T05:55:45Z) wrote 119 excerpts (dev 41, heldout 78), each with its ranges row and PROVENANCE row;
   - BustosFAR12 was not written (excerpt-check-failed);
   - this DS-P5-10 run's gate, offline-agreement and byte-comparison results, and its summary line.
2. **Removed the `unverified` item "Which dblp excerpts are actually written, and their final byte ranges".** The extraction and this verify run have now settled it. The release layout facts it repeated are still recorded under `datasets.dblp.format.record_layout_observed` (referenced in the removed text; I did not re-read that key).

Left unchanged because they are still accurate:
- the unverified item on why BustosFAR12 lacks 'Juan-Carlos Ruiz-Garcia 0001' (still not investigated);
- the unverified item on the Crossref miss;
- `missing_record_policy`;
- `known_source_misses`.

`validate-manifest.sh --verify` summary line, verbatim: **`manifest validation: 2303 checks, 0 failures`**. Its overlap line is the same as above (512 distinct IDs). Full output: /Users/taufeeqpatel/.claude/projects/-Users-taufeeqpatel-Desktop-ScholarChain/107a6780-f081-4d82-af1b-11c6ff34610e/tool-results/b2iqkaaw9.txt

### Proposed next step: generating the held-out labeling queue (for the human to decide; I did not start it)

Verification passed, so the held-out records are ready to feed the pipeline. The queue generator (`review/ReviewQueueMain`, from ER-P5-01) belongs to entity-resolution-engineer, and the curated run that feeds it comes from the Hadoop stage (hadoop-engineer). A suggested order:
1. The human commits `datasets/samples/er-candidates/**` and the manifest change as the sample checkpoint.
2. hadoop-engineer runs the bounded local pipeline over `datasets/samples/er-candidates/heldout` only, with dev as a separate run so no candidate pair crosses splits (split_rule). The `sbt localPipeline` row is still inactive (B10), so that needs to be resolved first. The Crossref-envelope reader limitation noted in the manifest also applies.
3. entity-resolution-engineer generates the held-out review queue and blind view from that run.
4. The human labels.

One thing to know: ER-P5-01 found that 119 pairs came from the earlier sample. Pair counts for the new records have not been observed yet, so the 200-pair minimum is still open.

```text
STATUS: COMPLETE
OWNER: dataset-engineer
WORK ORDER: DS-P5-10 (Phase 5): run the reviewed --verify with the local dblp release after the human extraction retry; report byte-range verification, dev/heldout overlap, saved counts by split; keep selection fixed; preserve both misses
FILES CREATED: none
FILES MODIFIED: /Users/taufeeqpatel/Desktop/ScholarChain/datasets/dataset-manifest.yaml (er_candidates.byte_range_ledger.state rewritten to observed facts; resolved unverified item "Which dblp excerpts are actually written, and their final byte ranges" removed)
COMMANDS RUN:
- git log --oneline --max-count=3: ok (HEAD e86a620)
- datasets/scripts/fetch-samples.sh --verify datasets/er-candidates datasets/downloads/dblp/full/dblp-2026-10-03.xml.gz: once, foreground, exit 0, "er-verify --with-release: 2205 checks, 0 failures"
- datasets/scripts/validate-manifest.sh --verify: once, after the manifest edit, "manifest validation: 2303 checks, 0 failures"
- git diff --stat -- datasets: 1 file changed, 1 insertion(+), 3 deletions(-)
- No --sample, --full, network, sbt, AWS or git write commands. No guard or audit refusal.
TEST RESULTS: no sbt run (forbidden by the work order; no Scala changed). Dataset verification: er-verify --with-release 2205 checks, 0 failures; 0 FAIL lines.
VALIDATION RESULTS:
- Release gate PASS: MD5 = pin cbd329100ea1bbb2873fa2f6d414ea00; SHA-256 = sidecar 20e45961bec5610dc07b8e932ccd27a2387534cfa12c92a24289fe873aebe969.
- 119/119 excerpts: ranges recorded, consistent offline, release sha256 recorded, and equal to release bytes (header 0+96, record, footer 5349755916+8).
- Overlap check PASS: 512 distinct IDs, none in both splits.
- Budget 698351 <= 8388608 bytes.
- Stored: dev 98 (dblp 41, OpenAlex works 45, OpenAlex authors 6, Crossref 6); heldout 192 (dblp 78, OpenAlex works 87, OpenAlex authors 14, Crossref 13). Units with a missing record: dev 1 (md-cinc-fotiadoukhv19), heldout 1 (hr-juan-carlos-ruiz-garcia).
- manifest validation: 2303 checks, 0 failures.
ASSUMPTIONS:
- Removing a now-resolved unverified item counts as updating stale "unverified" text under step 4.
- The extraction ran from checkpoint e86a620 at 05:55:45Z, taken from the human report and the FETCH-FAILURES at_utc values.
- The jump in the overlap count from 508 to 512 is credited to the newly stored dblp excerpt ORCIDs; the run does not break the count down by source.
RISKS:
- er_candidates.known_source_misses[0].seen lists only the DS-P5-01 run and the 04:55:53Z run, not the third Crossref miss at 05:55:45Z (preserved in extraction-result-02.md). It is incomplete but not false, so I left it under the narrow edit scope. A one-line follow-up can add it if wanted.
- The fixture verified_on date was not refreshed (no fixture run in this order).
- The 200-pair held-out minimum is not yet shown to be reachable.
- The cause of the BustosFAR12 miss is still not investigated.
MANUAL ACTION REQUIRED:
- Human: review the manifest diff (git diff -- datasets/dataset-manifest.yaml).
- Human: commit the extracted samples (datasets/samples/er-candidates/**, untracked) together with the manifest if accepted.
- Human: decide on the held-out queue route above, including activating sbt localPipeline (B10).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human review and sample-checkpoint commit; then a bounded per-split local pipeline run over heldout (hadoop-engineer, alias pending B10), then held-out review-queue generation (entity-resolution-engineer), then human labeling.
```

---

## ER-P5-14 (entity-resolution-engineer) — hand-back 2026-11-13, verbatim (report only)

Orchestrator note (not part of the report): routed per decisions-14.md; report only, no Bash, no writes. Ran in parallel with HD-P5-01, BE-P5-01 and FE-P5-02.

ER-P5-14 report: local entity-resolution commands for the separate dev and held-out runs (report only)

I made no Bash calls, wrote no files and ran no sbt. Everything below comes from reading the code and documents; nothing was executed. I could not do the parallel HD-P5-01 work, so wherever these commands need a Hadoop run directory they use the placeholders `<DEV_RUN>` and `<HELDOUT_RUN>`. Take the exact values from HD-P5-01.

### Read this first (the findings that change the commands)

- **Wrong argument order in the FE-P5-01 draft.** Its membership command ends `... 240 <seed>`. `ReviewQueueMain.scala:274-277` reads `<seed> [capacity]`, so the draft makes the seed "240" and tries to read the real seed as the capacity. The run then stops with `error: capacity must be an integer` and writes nothing. The correct order is `... <SEED> 240`. Owner: factory-engineer (FE-P5-02).
- **The proposed `sbt goldSetEval` alias cannot produce final held-out metrics.** The draft alias passes no `--manifest-sha256`. The allowlist row is exactly `sbt goldSetEval`, and its character gate forbids the quoting needed to add arguments. Without the hash, `GoldSetEvalMain.scala:282-292` prints coverage only and exits 2. The hash has to come from the alias itself or from a file it reads (gap G2).
- **Distinct pairs are counted only by `membership`.** Its `population=` value is the distinct-pair count. `diagnostics` counts by candidate ID. `queue` counts members, not the run's pairs.

### Shared setup (both splits; human, regular Terminal, repository root)

```
cd /Users/taufeeqpatel/Desktop/ScholarChain
export SCHOLARCHAIN_CONFIG_FILES=/Users/taufeeqpatel/scholarchain-local/er-local-p5.conf
env | grep '^SCHOLARCHAIN_CFG_'      # must print nothing: any unknown SCHOLARCHAIN_CFG_* variable is refused, and a known one overrides the file
```

- **Why the repository root:** `Compile/run` is not forked in `build.sbt`. The program therefore runs inside sbt's JVM, relative paths resolve from the directory sbt was started in, and `BuildGuard` requires that directory to be the repository root.
- **The config file:** it is outside the repository and is not an `.env*` file; its outline is in section 6. `membership` never reads it. `queue`, `diagnostics` and `GoldSetEvalMain` refuse to run without it, and there is no fallback.

### 1. Commands per split

#### Held-out (output directory `gold/entity-resolution/heldout-v1/`)

**H1. Membership and blind view.** Capacity 240, seeded, created once, prints the manifest SHA-256.
```
sbt "entityResolution/runMain scholarchain.entityresolution.review.ReviewQueueMain membership datasets/er-candidates/split-assignment.tsv datasets/samples/er-candidates/PROVENANCE.tsv <HELDOUT_RUN> heldout gold/entity-resolution/heldout-v1/er-gold-membership.jsonl gold/entity-resolution/heldout-v1/er-blind-label-view.jsonl <HELDOUT_SEED> 240"
shasum -a 256 gold/entity-resolution/heldout-v1/er-gold-membership.jsonl   # must equal the printed "manifest sha256="
```
- The blind view uses schema `er-blind-label-view.v5` (`BlindView.RowSchemaVersion`).
- The human chooses the seed before the run. It must be a token `[A-Za-z0-9._-]+`, for example `er-p5-heldout-v1`.
- Then the human records the SHA-256 in `docs/agent-runs/phase-05/human-gate.md`. The manifest and blind view are committed together, with no labels and no queue in that commit.

**H2.** The human labels from the blind view only and writes `gold/entity-resolution/heldout-v1/er-gold-labels.jsonl`, committed separately.

**H3. Review queue.** Rows use `er-review-queue-row.v4` under `er-policy.v8`. Run it after the labels are committed; labelers never open this file.
```
sbt "entityResolution/runMain scholarchain.entityresolution.review.ReviewQueueMain queue gold/entity-resolution/heldout-v1/er-gold-membership.jsonl <HELDOUT_RUN> gold/entity-resolution/heldout-v1/er-review-queue.jsonl"
```

**H4. Evaluation, later.**
- Human Terminal equivalent:
  ```
  sbt "entityResolution/runMain scholarchain.entityresolution.evaluation.GoldSetEvalMain gold/entity-resolution/heldout-v1/er-gold-membership.jsonl gold/entity-resolution/heldout-v1/er-review-queue.jsonl gold/entity-resolution/heldout-v1/er-gold-labels.jsonl --manifest-sha256=<RECORDED_HEX> --max-errors=10"
  ```
- Agent form after FE-P5-01 G9-b: `sbt goldSetEval` with mode `ask`, so the human approves every run. It needs gaps G2 and G3 fixed first.
- Without the matching hash, held-out metrics are refused. With fewer than 200 reviewed members the program exits 3 and prints coverage only. There is no `--provisional`.

**Diagnostics:** none for held-out. `diagnostics` refuses held-out endpoints (`ReviewQueue.scala:258-280`).

#### Dev (gold files in `gold/entity-resolution/dev-v1/`, diagnostics in `target/er-review/dev-diagnostics/<DEV_RUN_ID>/`)

**D1. Diagnostics.** The outcome-stratified queue, for DEV inspection only. It can never be evaluated, because `GoldSetEvalMain` needs a membership manifest.
```
sbt "entityResolution/runMain scholarchain.entityresolution.review.ReviewQueueMain diagnostics datasets/er-candidates/split-assignment.tsv datasets/samples/er-candidates/PROVENANCE.tsv <DEV_RUN> target/er-review/dev-diagnostics/<DEV_RUN_ID>/er-dev-diagnostic-queue.jsonl target/er-review/dev-diagnostics/<DEV_RUN_ID>/er-dev-diagnostic-blind.jsonl 240 10 <DEV_DIAG_SEED>"
```
If the last three arguments are left out, the defaults are 240, 10 and `er-p5`.

**D2. Dev membership.** Only needed if dev labels are wanted. The capacity argument always defaults to 240, so leaving it out does not give a census. A population below 240 is taken whole and the shortfall is printed.
```
sbt "entityResolution/runMain scholarchain.entityresolution.review.ReviewQueueMain membership datasets/er-candidates/split-assignment.tsv datasets/samples/er-candidates/PROVENANCE.tsv <DEV_RUN> dev gold/entity-resolution/dev-v1/er-gold-membership.jsonl gold/entity-resolution/dev-v1/er-blind-label-view.jsonl <DEV_SEED> 240"
```

**D3. Dev queue:**
```
sbt "entityResolution/runMain scholarchain.entityresolution.review.ReviewQueueMain queue gold/entity-resolution/dev-v1/er-gold-membership.jsonl <DEV_RUN> gold/entity-resolution/dev-v1/er-review-queue.jsonl"
```

**D4. Dev evaluation.**
- The command is the same as H4 with the `dev-v1` paths.
- `--manifest-sha256` is optional here.
- Every output line starts with `DEV` and ends with `split=dev`. The metrics are development diagnostics only, never final gold metrics.
- No alias exists for it. The human runs it in a Terminal.

#### Which allowlist rows an agent would need

- **No agent row exists, and none should be added, for any `ReviewQueueMain` mode.**
  - Direct `runMain` with arguments needs quotes, which the character gate forbids. FE-P5-01 probe 196 blocks it.
  - The gold outputs are human-owned.
- **Evaluation** is the only agent path: `entity-resolution-engineer sbt goldSetEval`. Today that row is `inactive:B10-alias-pending`. FE-P5-01 proposes `active`/`ask`, and it serves held-out only, because the alias hard-codes the `heldout-v1` paths.
- **A dev evaluation alias** would need its own BE and FE change.

### 2. Inputs per split

| Input | Dev | Held-out |
|---|---|---|
| Hadoop run directory (from HD-P5-01) | `<DEV_RUN>` | `<HELDOUT_RUN>` |
| Selection files (data only; owner dataset-engineer; read by `membership` and `diagnostics`) | `datasets/er-candidates/split-assignment.tsv`, `datasets/samples/er-candidates/PROVENANCE.tsv` | same two files |
| Config file (`SCHOLARCHAIN_CONFIG_FILES`; read by `queue`, `diagnostics`, `GoldSetEvalMain`) | yes | yes |
| Policy (module resource, not an argument) | `er-policy.v8.json` | `er-policy.v8.json` |

**Assumed run directory layout** (from `job-contracts.md` and `CuratedRunReader`): `<curated-root>/hadoop/snapshot=<dblp snapshot>/run=<run ID hex>/`.
- The reader needs `normalize/_SUCCESS` and `candidates/_SUCCESS`.
- It reads `normalize/works/part-*`, `normalize/profiles/part-*`, and `candidates/final/{researcher,work}/part-*` (plain or `.gz`).
- Every line is decoded strictly; one bad line refuses the whole run.

**Requirements on the Hadoop runs:**
- `scholarchain.blocking.keySourceVersion=er-blocking.v2` and `scholarchain.candidates.featureVersion=er-features.v2`. Otherwise the policy refuses every candidate.
- Each run reads only its own split: `datasets/samples/er-candidates/dev` or `.../heldout`. Phase 4 sample records in a run make `membership` refuse it with "maps to no selection unit". `diagnostics` tolerates them.

### 3. Output paths

| File | Path | Owner under FE-P5-01 `gold/**` → HUMAN |
|---|---|---|
| Held-out manifest `er-gold-membership.v1` | `gold/entity-resolution/heldout-v1/er-gold-membership.jsonl` | HUMAN (gold) |
| Held-out blind view (v5) | `gold/entity-resolution/heldout-v1/er-blind-label-view.jsonl` | HUMAN (gold) |
| Held-out internal queue (v4) | `gold/entity-resolution/heldout-v1/er-review-queue.jsonl` | HUMAN (gold); labelers never see it |
| Held-out labels | `gold/entity-resolution/heldout-v1/er-gold-labels.jsonl` | HUMAN, human-authored |
| Dev manifest, blind view, queue, labels | `gold/entity-resolution/dev-v1/` (same file names) | HUMAN (gold) |
| Dev diagnostic queue and blind view | `target/er-review/dev-diagnostics/<DEV_RUN_ID>/` | build output; gitignored (`/target/`); not gold; never labeled for metrics; deleted by `sbt clean` |

- **Create once:** every mode refuses an output that already exists (`<path> exists; refusing to overwrite`) and refuses two equal output paths. Writes are temp file plus hard link (`CreateOnceFile`), parent directories are created, and a failed multi-file write removes the files it already created.
- **New policy version:** the human moves the old queue aside or uses a new name. The manifest is never regenerated.
- **Before ratification:** until FE-P5-01 is ratified, `gold/**` is UNASSIGNED. No agent can write it; the human can, from a Terminal.

### 4. How pairs are counted, and the exact output lines

#### `membership`: the only distinct-pair count
- A pair is identified by `memberId = gm:SHA-256(er-gold-member.v1, entityKind, canonical endpoint pair)`. Candidate ID, blocking version and run are deliberately not inputs.
- `population` is the number of distinct member IDs over all candidates of the run: the distinct candidate pairs of the split.
- Cross-check: Hadoop's `candidates/final` writes exactly one `MatchCandidate` per candidate ID, which is the canonical pair plus versions plus run. Within one run, `population` should therefore equal the number of final candidate lines (`EMITTED.<entity>-candidates` in `candidates/_counters.json`).

Lines (`GoldMembership.summary`):
```
membership split=<heldout|dev> seed=<SEED> capacity=240 population=<P> sampled=<S> rule=<er-gold-sample.v1|er-gold-census.v1> shortfall=<n>[ (census: the population is below the capacity; nothing padded)]
population researcher=<r> work=<w>
sample researcher=<r> work=<w>
stratum <entityKind>/<criterion> population=<n> sample=<m>        (one line per stratum)
manifest sha256=<64 hex> members=<S>
blind view rows=<S> schema=er-blind-label-view.v5 labelGuide=<LabelGuide.Version> (<LabelGuide.Resource>)
freeze: record the manifest sha256 above in the human gate before labeling; GoldSetEvalMain --manifest-sha256=<hex> refuses any other manifest
```
- No policy is loaded, so no outcome, blocked or unresolved count appears; the step is policy-independent by design.
- Refusal: the run is refused (`error: ...`, exit 2, nothing written, every problem listed) if any endpoint has no source record, maps to no selection unit, or belongs to the other split, or if the pairs come from more than one (run, blocking version).

#### `queue`: counts members, not the run's pairs
```
policy=er-policy.v8 members=<S> rows=<n> not-generated=<n> refused=<n> untraceable=<n> candidates-outside-manifest=<n>
not-generated gm:...        refused gm:...: <reasons>        untraceable gm:...: <why>
settings policy=er-policy.v8 blocking=er-blocking.v2 features=er-features.v2 accept=0.85 reject=0.2 llmBand=[0.4,0.7] prompt=er-adjudication-prompt.v2 model=none (no adjudicator ran; this queue makes no LLM call) config=applied
```
- Members are looked up by member ID, never by candidate ID.
- On the generating run, `not-generated` should be 0, and `candidates-outside-manifest` should be P − S.
- The queue prints no counts of outcomes, blocked rows or UNRESOLVED rows; they appear only at evaluation.
- Refused and untraceable members are listed. At evaluation they count as not-generated, and a refused gold match lowers end-to-end recall.

#### `diagnostics` (dev only): counts by candidate ID
```
candidates resolved=<n> refused=<n> untraceable=<n> traceable=<n> selected=<n> target=240 shortfall=<n>
stratum <kind>/<path>/<outcome>/<band> available=<n> selected=<n> shortfall_vs_min=<n>
cell <kind>/<ACCEPTED|REJECTED|UNRESOLVED|CONFLICT|llm-band> available=<n> selected=<n>
untraceable <candidateId>: <why>      refused <candidateId>: <reasons>
blind view rows=<n>
settings ...
```
- Within one run, candidate ID and canonical pair are one-to-one, so `resolved + refused` equals the number of distinct pairs.
- UNRESOLVED counts appear per stratum and in the `UNRESOLVED` cell. Pairs blocked by identifier disagreements or the publication-stage rule are UNRESOLVED with reason codes. No separate "blocked" total is printed.

#### Evaluation
- Each segment line reports `unresolved(match/non)` and `conflict(match/non)`.
- The sensitivity lines report `blockedAccept`, `blockedReject` and `gateUnknown`.

#### Is 200 reviewed held-out pairs reachable?
Unknown until the held-out Hadoop run. The only numbers are:
- an estimate from DS-P5-01 (about 600 with dblp);
- an older observation (ER-P5-01: 119 pairs from the earlier sample).

If the printed held-out `population` is below 200, the minimum cannot be met from this selection. That needs a human decision, and nothing is padded.

### 5. What keeps the splits separate

- **Membership:** refuses any endpoint whose unit is in the other split, and refuses pairs from more than one run.
- **Manifest parse:** refuses lines whose manifest-level fields differ (split, run, blocking version, rule, seed, capacity, population).
- **Diagnostics:** refuses any held-out endpoint.
- **Evaluation:** the split is read from the manifest. Every line carries `split=`. Dev lines are marked `DEV` and are never final. Held-out final metrics require the recorded hash.
- **Records:** the dataset overlap check passed (512 IDs, none in both splits).
- **Held-out labels never feed tuning:**
  - The policy and config never read labels.
  - The thresholds stay the `er-policy.v8` values in the config and are fixed before labeling.
  - The `ask` mode limits how often the held-out FP and FN lists are shown.
  - Any threshold change is made on dev only.
- **Not refused today** (follow-up in my module, G8): `queue` does not check that the run's split matches the manifest's. A wrong run gives `rows=0 not-generated=<S>` instead of a refusal. I did not verify whether the evaluation refuses queue rows from outside the manifest.

### 6. Outline for the `SCHOLARCHAIN_CONFIG_FILES` file (HOCON, synthetic placeholders only, no secrets)

```
configSchemaVersion = "scholarchain.config.v2"
environment = "local"
aws { region = "us-east-1", accountId = "000000000000" }
storage {
  rawBucket = "placeholder-raw", curatedBucket = "placeholder-curated"
  contextBucket = "placeholder-context", vectorBucket = "placeholder-vector"
  vectorIndex = "placeholder-index", dynamoTablePrefix = "placeholder"
  neptuneEndpoint = "placeholder.invalid"
}
models { llmModelId = "placeholder-llm-not-called", embeddingModelId = "placeholder-embedding", embeddingDimension = 8 }
graph { windows = [ { startYear = 2000, endYear = 2026 } ], policyVersion = "placeholder-graph-policy", identityVersion = "v1" }
bridge { algorithm = "placeholder", sampleSize = 1, randomSeed = 1, maxIterations = 1 }
community { algorithm = "placeholder", maxIterations = 1 }
entityResolution {
  policyVersion = "er-policy.v8"
  acceptThreshold = 0.85, rejectThreshold = 0.2
  llmBandLower = 0.4, llmBandUpper = 0.7
  promptVersion = "er-adjudication-prompt.v2"
}
runtime { maxPathHops = 4, retrievalTopK = 10, defaultWindow = { startYear = 2000, endYear = 2026 } }
```
- **Required values:** `policyVersion` and `promptVersion` must equal the policy's, or the run is refused. The thresholds must satisfy reject < band lower < band upper < accept. The values shown are the policy-file values.
- **Model ID:** taken only from `models.llmModelId`. No adjudicator runs, so it is printed as `model=none (no adjudicator ran)`.
- **Other rules:** `openSearchEndpoint` may be left out. Includes and substitutions are refused. The account ID must be a quoted 12-digit string.
- **Caution:** the outline follows the current worktree `ConfigLoader.scala`, which has uncommitted changes I do not own (G9).

### 7. Gaps that block a correct run

| # | Gap | Owner |
|---|---|---|
| G1 | The FE-P5-01 draft has the membership argument order wrong (`240 <seed>`; correct is `<seed> 240`). `gold/**` ownership is unratified, and execution is held by decisions-14. | factory-engineer (FE-P5-02), human ratification |
| G2 | The `goldSetEval` alias has no `--manifest-sha256`, and the allowlist row accepts no arguments, so held-out final metrics always exit 2. Options: (a) build-engineer puts the recorded hash into the alias after the freeze; (b) I add `--manifest-sha256-file=` reading a human-owned file under `gold/**`. The alias also covers held-out only. | build-engineer (BE-P5-01), factory-engineer; option (b) is mine; human decides |
| G3 | An agent-run `goldSetEval` needs `SCHOLARCHAIN_CONFIG_FILES` in the environment of the Claude Code process. There is no ratified placeholder config path, and no row accepts an environment prefix. | human / factory-engineer (placeholder amendment), build-engineer |
| G4 | `Compile/run` is not forked, and `sys.exit` runs inside the sbt JVM. Its behaviour under sbt 1.11.7 is not verified (I expect batch sbt to end with the program's exit code). If fork is added without `baseDirectory`, relative paths resolve from `modules/entity-resolution`. | build-engineer |
| G5 | Each per-split Hadoop run must contain only that split's er-candidates records (no Phase 4 samples) and use `er-blocking.v2` / `er-features.v2`. | hadoop-engineer |
| G6 | The run directory must be kept until the queue is generated and while it may be regenerated. If it sits under `/target/`, `sbt clean` deletes it. | hadoop-engineer / human |
| G7 | Any `normalize/profiles` record not anchored in `PROVENANCE.tsv` (for example a reference profile) makes `membership` refuse the run. Unknown until the run; nothing is dropped silently. | ER and dataset-engineer, human decision if it happens |
| G8 | `queue` does not refuse a run of the other split. The evaluation check on queue rows outside the manifest is unverified. | entity-resolution-engineer (follow-up work order) |
| G9 | Core config sources are modified and uncommitted in the worktree, so the key table may change. | domain-engineer |
| G10 | Whether 200 reviewed held-out pairs are reachable is unknown until the held-out run prints `population=`. | human, after the run |
| G11 | Doc nit: the `ReviewQueueMain` Scaladoc says blind view v4; the code writes v5. | entity-resolution-engineer |

```text
STATUS: COMPLETE
OWNER: entity-resolution-engineer
WORK ORDER: ER-P5-14 (Phase 5): report-only preparation of the ER-side local commands for separate dev and held-out runs: membership (capacity 240, seeded, create-once, manifest SHA-256), review queue (er-review-queue-row.v4, er-policy.v8), blind view (er-blind-label-view.v5), dev diagnostics, inputs, outputs, distinct-pair reporting, split separation, config outline, gaps
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (no Bash calls; Read only)
TEST RESULTS: not run (report only; no behaviour change; sbt forbidden by the work order)
VALIDATION RESULTS: nothing executed. Read: decisions-04/05/06/09/10/14, amendment-fe-p5-01.md, the DS-P5-10 section of agent-report.md, docs/agents.md, build.sbt, project/build.properties (sbt 1.11.7), .gitignore, modules/hadoop/docs/job-contracts.md, core ConfigLoader.scala and ScholarChainConfig.scala (worktree), and ER ReviewQueueMain, GoldMembership, BlindView, CuratedRunReader, CreateOnceFile, SelectionUnits, ReviewQueue, GoldSetEvalMain, GoldSetEvaluation (header), MatchPolicy, er-policy.v8.json (header). Verified by reading: argument order of every mode (FE-P5-01 draft order is wrong); output line formats quoted above; create-once and refusal behaviour; distinct-pair counting by memberId in membership; candidate-ID counting in diagnostics; held-out refusal in diagnostics; split and freeze handling in GoldSetEvalMain.
ASSUMPTIONS: Hadoop run layout <curated-root>/hadoop/snapshot=<snap>/run=<hex>/ with normalize/_SUCCESS and candidates/_SUCCESS, one run per split (exact paths from HD-P5-01); the human runs every ReviewQueueMain and dev evaluation command from the repository root after FE-P5-01 ratification and the decisions-14 release; the config file lives outside the repository; seeds are chosen by the human before generation; LabelGuide version left symbolic; non-forked runMain ending in sys.exit ends batch sbt with the program's exit code (not verified).
RISKS: membership refuses the whole run if any endpoint is unanchored (fail closed; may need a decision); 200 reviewed held-out pairs not yet shown reachable; queue does not refuse a wrong-split run; held-out FP/FN lists are visible to whoever runs the evaluation (ask gate only); the uncommitted core config changes may alter the outline; a run directory under /target/ is lost on sbt clean.
MANUAL ACTION REQUIRED: Human: review these commands; ratify or reject FE-P5-01 after FE-P5-02 corrects the membership argument order; decide G2 (hash in the alias vs a hash file) and G3 (how the config reaches agent runs); create the local config file from section 6; choose and record the seeds; after the held-out run, record the manifest SHA-256 in human-gate.md and commit the manifest and blind view before any label. Orchestrator: send G1 to FE-P5-02, G2/G4 to BE-P5-01, G5/G6 to hadoop-engineer (HD-P5-01), G9 to domain-engineer; open an ER work order for G8 and G11 if wanted.
BLOCKED-ON OWNER: none (this report-only order is complete; execution is held by the human per decisions-14)
BLOCKED-ON EVIDENCE: none
NEXT GATE: human review of the HD-P5-01 and ER-P5-14 command sets together with the BE-P5-01 and FE-P5-02 drafts; ratification and a fresh session; then the per-split Hadoop runs, held-out membership and freeze commit, blind labeling, queue generation, and goldSetEval with the recorded manifest SHA-256.
```

---

## HD-P5-01 (hadoop-engineer) — hand-back 2026-11-13, verbatim (report only)

Orchestrator note (not part of the report): routed per decisions-14.md; report only, no Bash, no writes.

HD-P5-01 report (hadoop-engineer, report only). Nothing was written, no sbt was run, and no Bash call was made. I used only Read.

### Summary

**No correct per-split run can be made today, by an agent or by the human.** Six things stand in the way:
- **Allowlist.** The guard's `<SBT:hadoop>` slot admits only `hadoop/{compile,Test/compile,test,scalafmt,Test/scalafmt,scalafmtCheck}` (guard-dangerous.sh:66,136-137). The character gate refuses quotes, so no agent can pass `runMain` arguments.
- **Alias.** `sbt localPipeline` does not exist (build.sbt:275-283 defines only `fmt`, `check`, `packageJobs` and `dockerLocal`), and its row is inactive (B10).
- **Run manifest.** `PipelineDriver` needs a run-manifest JSON file, and none exists for these inputs.
- **Crossref.** The reader cannot read the Crossref envelope. All 6 dev and 13 held-out Crossref files will be rejected.
- **Split separation.** Nothing in Hadoop knows about splits.
- **Run ID.** The run ID does not depend on the input paths. Two splits run with identical manifests get the same run ID, so they must use different curated roots and different manifests.

The pipeline itself, its counters and its output layout are ready. The number of distinct candidate pairs is already reported by existing counters.

### 1. Commands per split

#### (a) Proposed alias form (for BE-P5-01; nothing written)
An agent can pass no arguments (no quotes, and every space-separated token is a separate sbt command), so the arguments must be fixed inside the alias. Proposal:
```
addCommandAlias("localPipelineDev",     "hadoop/Test/runMain scholarchain.hadoop.local.ErSplitPipelineMain dev")
addCommandAlias("localPipelineHeldout", "hadoop/Test/runMain scholarchain.hadoop.local.ErSplitPipelineMain heldout")
```
- Optional: `localPipeline` = `; localPipelineDev ; localPipelineHeldout`. These are two separate forked runs, but the human asked to keep the splits separate, so I recommend the two per-split aliases.
- `ErSplitPipelineMain` does not exist yet. It is a small test-scope entry point, owner hadoop-engineer, to be built in a later work order. For split `<s>` it would:
  - fix the input root to `datasets/samples/er-candidates/<s>/`;
  - fix the output root to `modules/hadoop/target/local-pipeline/er-candidates/<s>/curated`;
  - set `hadoop.tmp.dir` to `.../<s>/hadoop-tmp`;
  - load the committed manifest `modules/hadoop/local-runs/er-candidates.<s>.run-manifest.json`;
  - run all 5 stages.
- Test scope is proposed because the Test classpath includes the Provided Hadoop jars, and `Test / fork := true` and `Test / javaOptions ++= java17Opens` already exist in build.sbt.
- Build settings build-engineer may need (unverified):
  - `hadoop / Test / run / baseDirectory := (ThisBuild / baseDirectory).value`, so relative paths resolve from the repository root;
  - confirmation that the forked `Test/runMain` inherits `Test / fork`, `Test / javaOptions` and `Test / envVars`.
- Allowlist rows needed (factory-engineer, FE-P5-02): hadoop-engineer `sbt localPipelineDev` and `sbt localPipelineHeldout`, or activate the existing `sbt localPipeline` row if the combined alias is chosen.

#### (b) Human Terminal command that should work today (not verified; I could not run it)
Run it from `/Users/taufeeqpatel/Desktop/ScholarChain`, after the human writes the manifest file described below. For dev (use absolute `file://` paths, because the forked JVM's working directory is `modules/hadoop`):
```
sbt "hadoop/Test/runMain scholarchain.hadoop.pipeline.PipelineDriver -D hadoop.tmp.dir=/Users/taufeeqpatel/Desktop/ScholarChain/modules/hadoop/target/local-pipeline/er-candidates/dev/hadoop-tmp --manifest file:///Users/taufeeqpatel/Desktop/ScholarChain/modules/hadoop/target/local-pipeline/er-candidates/dev/run-manifest.json --curated-root file:///Users/taufeeqpatel/Desktop/ScholarChain/modules/hadoop/target/local-pipeline/er-candidates/dev/curated --stages normalize,exact-ids,teams,blocking,candidates --dblp file:///Users/taufeeqpatel/Desktop/ScholarChain/datasets/samples/er-candidates/dev/dblp --openalex-works file:///Users/taufeeqpatel/Desktop/ScholarChain/datasets/samples/er-candidates/dev/openalex-works --openalex-authors file:///Users/taufeeqpatel/Desktop/ScholarChain/datasets/samples/er-candidates/dev/openalex-authors --crossref-works file:///Users/taufeeqpatel/Desktop/ScholarChain/datasets/samples/er-candidates/dev/crossref-works"
```
For held-out, run the same command with every `dev` replaced by `heldout`, including the manifest path.

Notes on this command:
- I chose `Test/runMain` over plain `hadoop/runMain` because, by sbt's default, `run`/`runMain` use the Runtime classpath, which excludes the Provided Hadoop jars. I expect plain `hadoop/runMain` to fail with NoClassDefFoundError and without the Java 17 opens. This is unverified.
- No agent row can run either command.
- Exit codes: 0 means every stage ran or was already complete, 1 means a stage failed or refused to overwrite, 2 means bad arguments or a bad manifest.

**Manifest content (draft; `Json.decode[RunManifest]` checks it, and any mismatch exits 2 with the error):**
- `schemaVersion` "scholarchain.run-manifest.v1", `stage` "hadoop.pipeline", `attempt` 1.
- `codeVersion`: the 40-hex commit SHA. Use a clean tree; core is currently modified and uncommitted.
- `configDigest`: 64 hex and **different per split**. Proposal: the SHA-256 of the split's sorted file list with each file's SHA-256, for example `cd datasets/samples/er-candidates/<s> && find . -type f | LC_ALL=C sort | xargs shasum -a 256 | shasum -a 256`.
- `inputSnapshots`:
  - dblp "dblp-2026-10-03";
  - openalex and crossref tokens TO BE DECIDED (see item 7);
  - no orcid entry.
- `parameters` (the same for both splits):
  - DBLP: `scholarchain.dblp.recordTypes=article,inproceedings,proceedings,book,incollection,phdthesis,mastersthesis`, `scholarchain.dblp.yearRange=*`, `scholarchain.dblp.venues=*`.
  - **`scholarchain.dblp.encoding=ISO-8859-1`.** The excerpts declare and are ISO-8859-1. The Phase 4 test value UTF-8 would mis-decode them.
  - Size limits: `scholarchain.dblp.maxRecordBytes=1048576`, `scholarchain.orcid.maxRecordBytes=1048576`, `scholarchain.reject.maxExcerptChars=256`.
  - Teams: `scholarchain.team.sources=dblp`, `scholarchain.team.maxSize=100`.
  - Blocking: `scholarchain.blocking.keySource=scholarchain.hadoop.jobs.EntityResolutionKeySource`, `scholarchain.blocking.keySourceVersion=er-blocking.v2`, `scholarchain.blocking.maxBlockSize=4`, `scholarchain.blocking.oversizePolicy=drop`.
  - Candidates: `scholarchain.candidates.featureVersion=er-features.v2`.
  - Reducers: `scholarchain.reducers.{normalize,exactIds,teams,blocking,candidates}=1`.
  - Codecs: `scholarchain.codec.intermediate=org.apache.hadoop.io.compress.DefaultCodec`, `scholarchain.codec.output=org.apache.hadoop.io.compress.GzipCodec`.

The driver refuses unknown or missing parameters. It also refuses a key-source or feature version that differs from the loaded entity-resolution code (Pipeline.scala:150-235).

### 2. Inputs and readers

The globs are the same for each split `<s>`. Only these four directories are read: no Phase 4 samples, no ORCID, nothing from the other split.

| Input | Path | Reader | Notes |
|---|---|---|---|
| dblp | `datasets/samples/er-candidates/<s>/dblp/*.xml` (dev 41, held-out 78) | `DblpInputFormat` + `DblpMapper` | Byte-level, split-safe scanner. The DOCTYPE line is skipped as markup and `</dblp>` is ignored. A record ends at its own end tag, even when `</dblp>` follows directly. A directory input is listed one level deep. |
| OpenAlex works | `.../<s>/openalex-works/*.json` (45 / 87) | `LocatedTextInputFormat` (line reader) | Each file is one single-line object with no trailing newline, so one record per file. The fields the normalizer reads are all in the `select=` list. |
| OpenAlex authors | `.../<s>/openalex-authors/*.json` (6 / 14) | same | Become `SourceAuthorProfile` records. |
| Crossref works | `.../<s>/crossref-works/*.json` (6 / 13) | `CrossrefWorksMapper` (one bare work per line) | Fails on the envelope; see below. |

DBLP detail: entities are the Latin-1 HTML 4 set, and any other entity rejects the record as `malformed-xml`. Whether the 2023-06-28 DTD's entity list matches is unverified.

**Crossref envelope limitation (manifest `er_candidates.limitations[2]`).** The file is `{"status":"ok",...,"message":{"items":[work]}}`. `CrossrefNormalizer.workLine` looks for a top-level `DOI`, finds none, and rejects the file (JsonSources.scala:417-418). Effect on the run:
- every Crossref file becomes INPUT.crossref +1, REJECTED +1, MISSING_KEY +1, with a `missing-key` reject row in `normalize/rejects`, assuming each body is a single line (unverified);
- **no Crossref work, mention or ORCID enters the run**;
- Crossref-side pairs are lost from the `ordinary` units, the 3 `retraction` units and the second DOI of `multi-doi` units.

### 3. Outputs, IDs and provenance

Each split has its own output root, inside the gitignored `/modules/hadoop/target/` (.gitignore:14) and separate from the `target/test-work/**` that tests wipe:
- dev: `modules/hadoop/target/local-pipeline/er-candidates/dev/curated/hadoop/snapshot=dblp-2026-10-03/run=<hex>/`
- held-out: `modules/hadoop/target/local-pipeline/er-candidates/heldout/curated/hadoop/snapshot=dblp-2026-10-03/run=<hex>/`

Files in each run directory (Pipeline.scala:99-111):
- `_run-manifest.json`;
- `normalize/{works,mentions,profiles,venues,institutions,topics,rejects}`;
- `exact-ids/{links,rejects,stats}`;
- `teams/{teams,pairs,rejects,stats}`;
- `blocking/round-<r>/{blocks/<entity>,members/<entity>,spill,rejects,stats}`;
- `candidates/{join/…,pairs/{pairs,rejects,stats},final/{researcher,work,rejects,stats}}`;
- in each stage, `_counters.json`, then `_SUCCESS` last.

Record formats:
- **MatchCandidate** (`scholarchain.match-candidate.v1`, blocking `er-blocking.v2`, features `er-features.v2`): one line per distinct pair in `candidates/final/{researcher,work}/part-r-NNNNN.gz`.
- **Rejects**: `scholarchain.hadoop.reject.v1`, with the run ID, in each `<stage>/rejects/`.

Run ID and provenance:
- The run ID is `RunId.of(manifest)`, the name of the run directory. It is deterministic for a given manifest. I cannot state the hex without running.
- Records carry `Provenance(sourceDataset, sourceRecordId, snapshot from the manifest, runId, schemaVersion, …)`. Candidates and blocks carry `scholarchain-derived` provenance with the run ID.
- Idempotency: a stage with `_SUCCESS` is skipped. A stage directory without it is refused, and the run must be repeated with `attempt` + 1.
- `sbt clean` deletes these outputs. Because runs are deterministic, the same manifest re-creates the same run ID and the same outputs.

### 4. Reporting the number of distinct candidate pairs

The counters already exist (CandidateJob.scala:468, 550, 577-578). The driver prints them under `stage candidates: ran -> <dir>` as `  <name>=<value>`, and they are stored in `<run>/candidates/_counters.json`:

| Counter | Meaning |
|---|---|
| `EMITTED.researcher-candidates`, `EMITTED.work-candidates` | **distinct candidate pairs**, by entity |
| `CANDIDATE_PAIRS` (`.researcher`, `.work`) | per-block pairs before consolidation |
| `CANDIDATE_MERGED` | duplicate copies folded together |
| `CANDIDATE_MULTI_BLOCK` | candidates found in more than one block |

Invariant: distinct = CANDIDATE_PAIRS − CANDIDATE_MERGED. This is asserted in LocalPipelineSuite:333-341 for the Phase 4 samples. One candidateId means one canonical pair within one run.

Also relevant: `BLOCK_MEMBERS_DROPPED.<entity>` and `OVERSIZE_BLOCK` in `blocking/_counters.json` (members never paired), and the stats `largestBlockPairs` and `maxBlockKeysPerCandidate.*`.

Human cross-check (Terminal; R = the run directory):
- `gunzip -c R/candidates/final/researcher/part-r-*.gz R/candidates/final/work/part-r-*.gz | wc -l`
- `gunzip -c <same files> | grep -o '"candidateId":"mcand:[0-9a-f]*"' | sort -u | wc -l`

Both numbers must equal the sum of the two `EMITTED.*-candidates` counters.

No single summary line exists today. Minimal addition (owner hadoop-engineer, not implemented): `ErSplitPipelineMain` prints `[local-pipeline] split=<s> run=<id> distinct-candidate-pairs=<n> researcher=<r> work=<w> pairs=<P> merged=<M> dropped-members=<D>`. It exits non-zero if n ≠ P − M or n ≠ the distinct candidateIds read back from `final/`.

### 5. Split separation

How it holds:
- Pairs form only inside blocks of one run, and a run reads only the paths on its command line. Tasks share no state.
- The dev and held-out runs have disjoint input roots, distinct curated roots, and (with the per-split `configDigest`) distinct run IDs.
- At the data level, DS-P5-10 found no OpenAlex author ID or ORCID iD in both splits (512 IDs checked).
- Downstream, entity-resolution's `membership` mode refuses any endpoint that maps to a unit of the other split (ER-P5-05).

What does not hold today:
- **Hadoop enforces nothing.** A human could pass both directories in one run.
- **Same-root collision.** With identical manifests, both splits get the same run ID. With the same curated root, the held-out run would find dev's `_SUCCESS` and **silently skip, reusing dev's outputs**.

Proposed guard in `ErSplitPipelineMain` (hadoop-engineer):
- refuse any input path outside `datasets/samples/er-candidates/<s>/`;
- refuse an output root shared with the other split, or one that already holds a different run;
- put the split and the input digest into `configDigest`.

Also, never add the Phase 4 `datasets/samples/{dblp,openalex,crossref,orcid}` files. Entity-resolution's membership step refuses endpoints it cannot map to a selection unit.

### 6. Expected number of pairs

**Not derivable** without running the entity-resolution blocking keys over these records, so I give no estimate.

What is known:
- **Work members**: about 86 in dev (41 dblp + 45 OpenAlex) and about 165 in held-out (78 + 87), with Crossref 0 because of the reader. Duplicates may lower these.
- **Researcher members**: every mention plus 6 or 14 profiles. The mention counts are not observed.
- **Upper bound (an assumption):** if every member ends in exactly one block (asserted at LocalPipelineSuite:302-308), then at maxBlockSize 4 the distinct pairs are at most 1.5 × members per entity. Members dropped from oversize blocks produce no pairs.
- **Comparison with the 200 minimum:** only held-out counts toward the 200 human-reviewed pairs. Reaching 200 is neither shown nor ruled out. The earlier probe (Phase 4 samples, a different input) gave 119.

### 7. What blocks a correct run

1. **No alias and no allowlist row (B10).**
   - build-engineer: the per-split aliases, and possibly the `Test/run/baseDirectory` setting.
   - factory-engineer: the `sbt localPipelineDev` and `sbt localPipelineHeldout` rows, or activating `sbt localPipeline`.
   - Activation is held for human ratification (decisions-14).
2. **Crossref envelope reader.** Owner: hadoop-engineer, me (a code change with a feature test). It must land **before the held-out run that generates membership**, because membership is fixed to one generating run (ER-P5-05). Otherwise the held-out census permanently excludes Crossref pairs.
3. **Committed per-split run manifests, `ErSplitPipelineMain` (split guard and summary line), and tests.** Owner: hadoop-engineer, under a follow-up work order.
4. **Snapshot tokens for the API-fetched OpenAlex and Crossref ER records.** Owner: dataset-engineer or the human. Using `openalex-2026-10-14` or `crossref-public-data-file-2026-03` would misstate provenance.
5. **Blocking parameters for the gold runs.** Owner: the human, with entity-resolution-engineer input. maxBlockSize 4 and the `drop` policy match the manifest's assumption, but dropped members mean pairs that are never generated.
6. **Code state.**
   - `modules/core/**` is modified and uncommitted, so it is unverified whether hadoop compiles and passes on it now.
   - `codeVersion` should be a clean commit.
   - `hadoop.tmp.dir` defaults to /tmp unless `-D` is given.
   - `Test/runMain` behaviour is unverified.
7. **Smaller limits, not blockers:**
   - the dblp DTD entity coverage is unverified;
   - BustosFAR12 (held-out) and one Crossref DOI (dev) are missing, so those units have fewer records.

```text
STATUS: BLOCKED-ON
OWNER: hadoop-engineer
WORK ORDER: HD-P5-01 (Phase 5), report only: exact local per-split (dev, held-out) Hadoop pipeline commands, inputs, output directories, distinct-candidate-pair reporting, split separation
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (Read only, as the work order required; no Bash, no sbt)
TEST RESULTS: none (sbt not run, as the work order required)
VALIDATION RESULTS: none run. From code reading:
- the distinct-pair counters exist (EMITTED.researcher-candidates and EMITTED.work-candidates, with CANDIDATE_PAIRS − CANDIDATE_MERGED);
- CrossrefNormalizer rejects the envelope with MISSING_KEY;
- the run ID does not depend on input paths;
- the <SBT:hadoop> allowlist slot does not admit runMain or arguments.
ASSUMPTIONS:
- Test/runMain inherits Test/fork, Test/javaOptions and Test/envVars, and its classpath includes the Provided Hadoop jars; plain runMain does not.
- Each Crossref envelope body is one line.
- The RunManifest and SnapshotRef JSON field names match the case classes.
- The dblp excerpts need encoding ISO-8859-1.
- The 1.5 × members bound rests on the one-block-per-member invariant asserted in LocalPipelineSuite.
RISKS:
- A held-out run made before the Crossref reader fix freezes a membership that has no Crossref pairs.
- With a shared curated root and identical manifests, the held-out run silently skips and reuses dev's outputs.
- The drop policy loses pairs without generating them.
- sbt clean deletes the run outputs; they can be re-created deterministically.
- Uncommitted core changes leave the build state unverified.
- Wrong snapshot tokens would misstate provenance.
MANUAL ACTION REQUIRED:
- Human: decide the snapshot tokens, maxBlockSize and oversize policy; approve a hadoop-engineer follow-up for the Crossref envelope reader, the per-split manifests and ErSplitPipelineMain; ratify the aliases and rows.
- No run before those are in place.
BLOCKED-ON OWNER: build-engineer (aliases, run settings); factory-engineer (allowlist rows, B10); dataset-engineer/human (snapshot tokens for the API-fetched ER records); human (blocking parameters). The hadoop-engineer follow-up (Crossref reader, manifests, entry point) needs a new work order.
BLOCKED-ON EVIDENCE:
- build.sbt:275-283 has no localPipeline alias.
- docs/agents.md lists hadoop-engineer `sbt localPipeline` as inactive:B10-alias-pending.
- guard-dangerous.sh:66,136-137 limits <SBT:hadoop> to compile/test/scalafmt tasks.
- JsonSources.scala:417-418 rejects a line without top-level DOI.
- Pipeline.scala:122-136 builds the run directory only from the manifest's dblp snapshot and run ID.
NEXT GATE: human review of these commands together with ER-P5-14, BE-P5-01 and FE-P5-02; a hadoop-engineer follow-up work order (Crossref envelope reader, per-split manifests, ErSplitPipelineMain, sbt check); ratification and activation in a fresh session; then the dev run, then the held-out run.
```

---

## DE-P5-03 (domain-engineer), decisions-15: which uncommitted core changes the pipeline needs (report only) — verbatim

## DE-P5-03: which uncommitted core changes the pipeline needs, and why they must be committed first (report only)

**Answer:** The entity-resolution mains (`ReviewQueueMain` queue/diagnostics, `GoldSetEvalMain`) need every main-source group of the core diff. The Hadoop jobs themselves need none of it.

There are actually **17 uncommitted core paths, not 15**. The 15 modified files depend on two untracked files: `modules/core/src/main/scala/scholarchain/core/MatchDecision.scala` and `modules/core/src/test/scala/scholarchain/core/MatchDecisionV2Suite.scala`. The 17 paths only compile and pass as one unit.

The ER mains are not committed either. `git ls-files --others` shows about 60 untracked files under `modules/entity-resolution/**`, including `ReviewQueueMain.scala`, `GoldSetEvalMain.scala`, `MatchPolicy.scala`, `Resolver.scala` and all policy JSON, plus `tests/src/test/scala/scholarchain/tests/er/`. Committing core is necessary for "runs against committed code", but it is not enough on its own.

I wrote no files, ran no sbt, and committed nothing.

### 1. The 17 paths: change and origin
Origins come from `docs/agent-runs/phase-05/agent-report.md` (DE-P5-01 and DE-P5-02 sections) and `decisions-06.md`.

| # | File | Change | Origin |
|---|---|---|---|
| 1 | main `Contract.scala` | `SchemaVersions.MatchDecision` goes v1 → `scholarchain.match-decision.v2`; v1 added to `retired`; history Scaladoc | DE-P5-01 |
| 2 | main `Enums.scala` | Adds `MatchDecisionPath`, `AdjudicationVerdict`, `IdentifierConflictKind`; adds `ExactIdScheme` cases `s2-corpus`, `s2-paper`, `s2-author`, `repository-url` | DE-P5-01 |
| 3 | main `Identifiers.scala` | Semantic Scholar validators and normalizers; `normalizeRepositoryUrl`, `CaseInsensitiveRepositoryHosts`, `isCanonicalRepositoryUrl`; `validateExact` / `normalizeExact` extended to the 4 new cases | DE-P5-01 |
|   |   | `QueryIgnoringRepositoryHosts`, query refusal, `WithheldRepositoryUrl`, `errorDisplay` | DE-P5-02 (D2 = RV-P5-04 m10, D3) |
| 4 | main `Identity.scala` | v1 `MatchDecision` and `checkInstant` removed (−104 lines); they moved to #16 | DE-P5-01 |
| 5 | main `SourceIdentity.scala` | `ExactId.of` / `check` show errors through `Identifiers.errorDisplay` | DE-P5-01, tightened by DE-P5-02 |
| 6 | main `config/ConfigLoader.scala` | Schema `scholarchain.config.v2`; v1 retired; required keys `entityResolution.llmBandLower`, `llmBandUpper`, `promptVersion`; cross-key check delegated to `EntityResolutionConfig.problems` | DE-P5-01 |
| 7 | main `config/ScholarChainConfig.scala` | `EntityResolutionConfig` grows from 3 to 6 fields; new `object EntityResolutionConfig.problems` (rule: reject < bandLower < bandUpper < accept) | DE-P5-01 |
| 8 | test `EnumCodecSuite` | Pins the new scheme list; round-trip test for the 3 new enums | DE-P5-01 |
| 9 | test `Fixtures` | v2 decision, conflict, LLM and work-candidate fixtures; 6-field config | DE-P5-01 (diff not read line by line) |
| 10 | test `IdentifiersSuite` | S2 and repository tests | DE-P5-01 |
|   |   | 7 credential-echo / query tests | DE-P5-02 |
| 11 | test `SchemaVersionSuite` | v2 pin; v1 JSON refused; `retired` key set | DE-P5-01 |
| 12 | test `StableIdSuite` | Decision-ID goldens (formula unchanged, new v2 goldens) | DE-P5-01 |
| 13 | test `ValidationSuite` | Pairwise CONFLICT rules | DE-P5-01 |
| 14 | test `WireContractSuite` | v2 goldens (exact-id ACCEPTED, DOI CONFLICT, LLM REJECTED) plus a work `MatchCandidate` golden | DE-P5-01 |
| 15 | test `config/ConfigLoaderSuite` | config.v2 keys and band ordering | DE-P5-01 (diff not read) |
| 16 | **untracked** main `MatchDecision.scala` | `MatchDecision` v2, `IdentifierConflict`, `AdjudicationReference`, `MatchDecision.build` | DE-P5-01 |
| 17 | **untracked** test `MatchDecisionV2Suite.scala` | v2 contract tests | DE-P5-01 |

### 2. Where the other modules use these symbols
**`modules/hadoop/**`: no use of any changed or added symbol, and no Hadoop file is modified or untracked.**
- `jobs/ExactIdJob.scala` imports `ExactIdScheme` (11-28). It names only the old cases (108-110, 120, 133, 155-157) and calls `fromTag` (181).
- `jobs/CandidateJob.scala` imports only unchanged core types (13-26) and uses `SchemaVersions.CandidateBlock` (148).
- `pipeline/PipelineDriver.scala:9` uses `Json` and `RunManifest`; `pipeline/Pipeline.scala:24` uses `Json`, `RunId`, `RunManifest`, `SourceType`. None of these changed.
- No Hadoop code reads `ConfigLoader`, `MatchDecision`, or the new schemes.
- `StableIds.scala` is not in the diff, so the run-ID and candidate-ID formulas are unchanged.

**Committed ER (`MatchFeatures.scala`):** uses fixed scheme lists (160-169) and old cases only.

**Untracked ER (what the mains run):**
- **`policy/MatchPolicy.scala`**
  - Imports `MatchDecisionPath` (:11) and `EntityResolutionConfig` (:12).
  - `MatchPath.toCore` (35-40) uses `MatchDecisionPath`.
  - `validate` builds the expected schemes from `ExactIdScheme.values` (:504) and refuses rules for unknown schemes (519-523). `er-policy.v8.json:93` has an `s2-author` rule, so under HEAD core `loadDefault()` fails. The work rules for s2-corpus, s2-paper and repository-url are in the ER-P5-07 table; I read only the first 150 lines of that file.
  - `withConfig` (659-686) calls `EntityResolutionConfig.problems` (:662) and reads `c.promptVersion` (:671) and `c.llmBandLower` / `c.llmBandUpper` (:681).
- **`policy/Resolver.scala`** imports `AdjudicationReference`, `AdjudicationVerdict`, `IdentifierConflict`, `MatchDecision` (v2) and `SchemaVersions` (8-21). It has a `conflicts: Vector[IdentifierConflict]` field (:148), and `toMatchDecision` maps to v2 (Scaladoc 85-86).
- **`identifiers/ExactIdentifiers.scala`** imports `IdentifierConflict` and `Identifiers` (:3), calls `Identifiers.normalizeExact`, including the new schemes (:87), and calls `IdentifierConflict.disagreement` (:172).
- **`review/ReviewQueueMain.scala`** imports the config types (:8). `effectivePolicy` calls `withConfig(base, cfg.entityResolution)` (235-236), and `ConfigLoader.loadFromSystem()` is called at :261, which accepts only config.v2.
- **`evaluation/GoldSetEvalMain.scala`** imports the config types (:11), calls `withConfig` (211-212) and `loadFromSystem` (:219).
- `adjudication/Adjudication.scala` maps a valid trace to `AdjudicationReference`. I did not re-read this file; it is per the DE-P5-01 report.

**Would HEAD's committed code compile and behave the same against HEAD core?**
- **Yes for committed code.** HEAD's Hadoop, committed ER and committed `tests/**` use none of the changed symbols. `git diff --stat 1bc5382 HEAD -- modules` is empty.
- **No for the working tree.** The working-tree ER code (untracked) already depends on uncommitted core. sbt's `hadoop` project depends on `entityResolution`, so compiling the Hadoop project or its test tree (where `ErSplitPipelineMain` will live) in the working tree also compiles the untracked ER sources. That compile only succeeds against the working-tree core.

### 3. Classification
- **REQUIRED by the pipeline (the ER mains):**
  - `Contract.scala` (v2 tag used by `MatchDecision.build`);
  - `Enums.scala` (`MatchDecisionPath` at MatchPolicy:11/35; `AdjudicationVerdict` at Resolver:10; the 4 schemes, without which policy v8 fails to load at MatchPolicy:504/519);
  - `Identifiers.scala` (ExactIdentifiers:87; required by the `Enums` cases);
  - `Identity.scala` plus untracked `MatchDecision.scala` (the v2 record used by Resolver:15; must go together, or `MatchDecision` is defined twice or missing);
  - `ConfigLoader.scala` and `ScholarChainConfig.scala` (both mains: `withConfig` and `loadFromSystem`).
- **NOT REQUIRED by the pipeline, but not separable:** `SourceIdentity.scala`. It is the D2 credential-redaction fix and the pipeline never builds repository URLs. But `IdentifiersSuite` asserts that `ExactId.of` errors contain `WithheldRepositoryUrl`, and the TE-P5-01 D2 regression in `tests/er` depends on it. Holding it back would fail `sbt check`, so commit it with the unit.
- **TEST-ONLY support for the required changes:** `EnumCodecSuite`, `Fixtures`, `IdentifiersSuite`, `SchemaVersionSuite`, `StableIdSuite`, `ValidationSuite`, `WireContractSuite`, `ConfigLoaderSuite`, and untracked `MatchDecisionV2Suite`.
- **The Hadoop jobs alone require none of these changes.** Only their compile in the working tree does, through the ER project.

### 4. Why the required changes must be committed before any evidence run
- **`git_head` binding.** The last marker records `git_head=1bc5382…` and `git_worktree=dirty` (`verification-v8.md`). Evidence tied to `git_head=36ffcf9` would name a commit that has neither core v2 nor the ER mains, so checking out that commit could not rebuild the code that produced it. At HEAD the ER mains do not exist; with ER committed but core not, the ER compile fails.
- **What differs between HEAD core and working-tree core:**
  - **Decision records.** Bytes and `schemaVersion` are `match-decision.v2` (adds `candidateId`, `matchPath`, `score`, `blockingVersion`, `featureVersion`, `conflicts`, `adjudication`). HEAD has only v1, which is retired in the working tree.
  - **Configuration.** The mains require `configSchemaVersion = scholarchain.config.v2` with the 3 new keys. HEAD core accepts only v1, and the effective thresholds and band recorded on every queue row come from those keys.
  - **Policy loading.** Policy v8 validates against the 4 new schemes; on HEAD core it does not load.
  - **What does not differ.** `MatchDecisionId` (hash tag still `scholarchain.match-decision.v1`), `RunId`, `MatchCandidateId` and pair canonicalization are unchanged, and the Hadoop curated output bytes do not depend on this diff.
- **Committed-input checks.** The decisions require committed, unmodified inputs. `markTestRun` evidence on a dirty tree does not prove which core produced a run. The manifest SHA-256 freeze in `GoldSetEvalMain` binds the data but not the code.

### 5. Is it consistent to commit as one unit, and was it tested?
- **One unit, and it cannot be split by file:**
  - `Identity.scala` −MatchDecision goes with `MatchDecision.scala` +MatchDecision.
  - `Enums.scala` (new schemes) goes with `Identifiers.scala`: `validateExact` / `normalizeExact` match on every case.
  - `SourceIdentity.scala` needs `Identifiers.errorDisplay`.
  - `MatchDecision.scala` needs `Contract.scala` and the new `Enums`.
  - `ConfigLoader.scala` needs `EntityResolutionConfig.problems` and the new fields.
  - `Fixtures` and the suites span all three groups.
- **Order.** Records say committed Hadoop, committed ER and committed `tests/**` pass against this core:
  - DE-P5-01 found that only untracked ER call sites broke, and it fixed none of the committed code;
  - every later full run included the core changes.
  - So core can be committed first. The ER sources and `tests/er` must still be committed before any evidence run.
- **Last observed check.** Human Terminal `sbt -no-colors check`, Nov 12 2026 9:18:56 PM: 873 passed, 71 suites, 0 failed. Core was 299, which equals DE-P5-02's final `sbt core/test` count (299), and HEAD was 1bc5382 with a dirty tree (`verification-v8.md`). No core work order after DE-P5-02 (Nov 6) is recorded, and 1bc5382..HEAD changes no module files. So the records indicate that run covered these core changes.
- **What I could not check.** I could not confirm by mtime that core is unchanged since that run: `verify-tests-ran.sh --check` is not in my allowlist. The orchestrator checked it on Nov 12 and got OK.

### Contract summary (unchanged by this order)
- **Decision ID:** `MatchDecisionId.of(entityKind, sourceId, targetId, provenance.policyVersion, provenance.runId)`, hash tag `scholarchain.match-decision.v1`.
- **Pair order:** canonical, `sourceId < targetId` by UTF-8 bytes. `MatchDecision.build` swaps the conflict sides when it reorders the pair (MatchDecision.scala:403-407).
- **Frozen v2 samples:** `WireContractSuite.scala` (`MatchDecisionGolden`, `ConflictDecisionGolden`, `LlmDecisionGolden`).
- **Schema versions:** match-decision.v2 (v1 retired), config.v2 (v1 retired), match-candidate.v1 unchanged.

```text
STATUS: COMPLETE
OWNER: domain-engineer
WORK ORDER: DE-P5-03 (Phase 5): report-only analysis of which uncommitted modules/core changes the per-split pipeline and ER mains require, and why they must be committed first
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: git diff --stat (ok); git diff -- modules/core/src/main (ok); git ls-files --others --exclude-standard -- modules (ok); git status --short (ok); git ls-files -- modules/entity-resolution modules/hadoop tests (ok); git ls-files -- modules/core config conf (ok); git ls-files -- *.conf *.hocon *.yaml (REFUSED by guard-dangerous.sh shell-syntax-gate, nothing ran); git show --stat fee6135 (ok); git log --oneline --max-count=15 -- modules/core (ok); git diff --stat 1bc5382 HEAD -- modules (ok, empty); git diff of 5 core test suites (ok); git diff of IdentifiersSuite (ok)
TEST RESULTS: not applicable (report only; no sbt run, per the work order). Last recorded: human Terminal sbt check Nov 12 2026, 873 passed / 71 suites / 0 failed (core 299), marker git_head=1bc5382, worktree dirty (human-reported, orchestrator-checked marker; not observed by me)
VALIDATION RESULTS: by reading code: HEAD's committed hadoop/ER/tests use no changed core symbol; working-tree ER main code depends on all three core groups (v2 decision, new schemes, config v2) at the file:line references above; no module change between 1bc5382 and HEAD
ASSUMPTIONS: Fixtures.scala and ConfigLoaderSuite.scala diffs summarized from the DE-P5-01 report, not read line by line; Adjudication.scala use of AdjudicationReference taken from the DE-P5-01 report; er-policy.v8.json work rules for s2-corpus/s2-paper/repository-url taken from the ER-P5-07 table (only the s2-author rule at :93 was read); core unchanged since the Nov 12 run inferred from records and matching counts (299), not verified by mtime
RISKS: committing core alone does not make the ER mains committed (about 60 untracked ER files and tests/er remain); the marker is bound to 1bc5382 and a dirty tree, so a fresh sbt check at the new commit is needed before evidence runs; Fixtures/ConfigLoaderSuite not line-reviewed in this order
MANUAL ACTION REQUIRED: Human: commit all 17 core paths (15 modified plus untracked MatchDecision.scala and MatchDecisionV2Suite.scala) as one unit, and the ER sources/resources and tests/er before any evidence run; then one foreground sbt check at the new HEAD
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human review and commit of the core unit (with or before the ER and tests/er trees), a fresh sbt check recording the new git_head, then HD-P5-02 / ER-P5-15 per decisions-15
```

---

## HD-P5-02 (hadoop-engineer), decisions-15: Crossref fix, per-split entry point and manifests — verbatim

## HD-P5-02 hand-back (hadoop-engineer)

All five tasks are done and `sbt check` passes: **886 passed, 0 failed, 73 suites** (hadoop 80/80), run in the foreground in 135 s. One item is blocked: the OpenAlex and Crossref snapshot tokens. Committed provenance fixes the retrieval date (2026-11-05 in both splits), but no committed artifact defines a token for API-fetched records. The manifests therefore carry a *proposed* token, which needs ratification by dataset-engineer and the human.

Nothing was run on held-out data, no held-out sample file was opened, and nothing was committed. One thing to know about the dev measurement: the human has not run the `runMain` command yet. The dev numbers below come from the same entry-point code, run on dev inside `ErSplitPipelineSuite` (output under `target/test-work`, not the fixed output root).

**Main finding for ER-P5-15:** on dev, cap 4 drops nothing. The largest level-0 group is exactly 4 for both entities, so there is no headroom. Caps 2 and 3 would drop members; caps 5, 6, 8 and no cap give the same result as 4.

### 1. Crossref reader fix
- `CrossrefNormalizer.unwrap` (JsonSources.scala) treats any line with a `status`, `message-type` or `message` key as a REST envelope.
  - It accepts `status:"ok"` with `work-list` and exactly one object in `message.items`, or with `work` and an object `message`.
  - Everything else is rejected into `normalize/rejects` (`scholarchain.hadoop.reject.v1`, with excerpt and counters): missing envelope field → `missing-field`; bad status, unknown message-type, items not an array or more than one item → `malformed-field`; empty items → `missing-key`; non-object item → `malformed-json`.
  - Bare work lines behave as before.
- Regression test `CrossrefEnvelopeSuite` reads the 6 committed **dev** envelopes; all are accepted. Before the fix each was rejected `missing-key` (JsonSources.scala:417-418, per HD-P5-01). I did not re-run the test against the old code.
  - One dev work, the retraction notice `10.1155/2023/9869061`, has no author list in Crossref. It is kept with no mentions, and the test pins this.
  - The suite also covers every malformed variant and an MR normalize run: `INPUT.crossref-works=4`, `ACCEPTED=1`, `REJECTED.malformed-field=2`, `REJECTED.missing-key=1`.

### 2. Entry point `scholarchain.hadoop.local.ErSplitPipelineMain` (test tree)
- **Arguments:** exactly `dev` or `heldout`; anything else exits 2. The repository root is found by walking up from the working directory, so it works whether sbt forks in the root or in `modules/hadoop`.
- **Checks before any job runs (all exit 2):**
  - **Manifest:** `scholarchain.input.split` equals the argument (split guard); `scholarchain.input.digest` equals the digest of that split's PROVENANCE.tsv rows; `configDigest` equals the parameter digest; the dblp snapshot equals that split's `release_id` in DBLP-RELEASE-RANGES.tsv; there is no ORCID snapshot.
  - **Inputs:** the split root holds only `dblp/`, `openalex-works/`, `openalex-authors/` and `crossref-works/`; no symbolic links; every real path is inside the split root; the file set equals that split's ledger rows; every SHA-256 matches the ledger.
- **Retry and partial-run rule:**
  - Every run creates a new `attempt-<max+1>/` and writes `_IN_PROGRESS` first.
  - Success writes `summary.txt` and `_SUCCESS` and removes `_IN_PROGRESS`. A failed stage or failed consistency check writes `_FAILED`.
  - An attempt with neither marker (killed or timed out) is reported as `status=partial` on the next start and left untouched. Failed attempts are also reported and left untouched.
  - A complete attempt refuses every further run of that split (exit 2) and names the directory to move away.
- **Run:** the 5 stages run one after another through `Pipeline.run`, each timed. Exit 0 = complete and consistent; 1 = a stage failed or a consistency check failed.
- **Distinct pairs:** `distinct.<entity>` = `EMITTED.<entity>-candidates`. The check requires their sum to equal `CANDIDATE_PAIRS − CANDIDATE_MERGED`, and each to equal the distinct `candidateId`s read back from `candidates/final/<entity>`.
- **Block-size report:** a driver-side diagnostic over the curated outputs, not part of the MR job graph. It replays the BlockReducer rules, and it must reproduce the run's own drops, oversize groups and per-block pairs at the manifest cap (`simulationMatchesRun`); otherwise the run is marked `inconsistent`.
- The new manifest keys `scholarchain.input.split` and `scholarchain.input.digest` were added to `JobParams.ManifestKeys` as optional keys. No job reads them; they only change the run ID.

**Summary-line format** (stdout and `summary.txt`; every line starts with `[er-split] split=<s>`; tokens are `key=value`; grep `\[er-split\]`):
```
elapsed stage=<s> ms=<n> status=<ran|skipped|failed>
normalize input=<in> read=<n> accepted=<n>
normalize rejected= flagged= works= mentions= profiles= duplicate= conflict=
distinct.researcher= distinct.work= distinct.total= candidatePairs= candidatePairs.researcher= candidatePairs.work= candidateMerged= readBack.researcher= readBack.work= check=...:<ok|FAILED>
oversize entity=<e> cap= policy= groups= groupsWithDrops= membersDropped= membersRefined= uncappedPairsOfOversizeGroups= uncappedPairsOfDroppingGroups= pairsInvolvingDroppedMembers= largestOversizeGroup= droppedByKind=<kind:n,...|none> simulationMatchesRun=<bool>
histogram entity=<e> level=0 members= groups= sizes=<size:count,...>
capsim entity=<e> cap=<2|3|4|5|6|8|none> blocks= pairs= oversizeGroups= membersDropped= largestBlock= droppedByKind=
teams teams= coauthorPairs= oversizeTeams= maxTeamSize=<n(id)> maxPairExpansion=<n(id)>
elapsed total ms=<n>
runId=<id> attempt=<dir> status=<complete|inconsistent|failed> output=<dir>
```
Refusals print a single line: `[er-split] split=<s> status=refused reason=<message with spaces as _>`.

### 3. Manifests, run IDs and snapshot tokens

**`modules/hadoop/local-runs/er-candidates.dev.run-manifest.json`** (full content):
```json
{
  "schemaVersion": "scholarchain.run-manifest.v1",
  "stage": "hadoop.pipeline",
  "codeVersion": "36ffcf95e585c7def6aa06835a784820936e1b60",
  "configDigest": "f29ca56926aebf7d02cb7dda44e472ac4bd489a0a196886f7dae1a82e7acb62c",
  "inputSnapshots": [
    {"source": "dblp", "snapshot": "dblp-2026-10-03"},
    {"source": "openalex", "snapshot": "openalex-api-2026-11-05"},
    {"source": "crossref", "snapshot": "crossref-api-2026-11-05"}
  ],
  "parameters": {
    "scholarchain.blocking.keySource": "scholarchain.hadoop.jobs.EntityResolutionKeySource",
    "scholarchain.blocking.keySourceVersion": "er-blocking.v2",
    "scholarchain.blocking.maxBlockSize": "4",
    "scholarchain.blocking.oversizePolicy": "drop",
    "scholarchain.candidates.featureVersion": "er-features.v2",
    "scholarchain.codec.intermediate": "org.apache.hadoop.io.compress.DefaultCodec",
    "scholarchain.codec.output": "org.apache.hadoop.io.compress.GzipCodec",
    "scholarchain.dblp.encoding": "ISO-8859-1",
    "scholarchain.dblp.maxRecordBytes": "1048576",
    "scholarchain.dblp.recordTypes": "article,inproceedings,proceedings,book,incollection,phdthesis,mastersthesis",
    "scholarchain.dblp.venues": "*",
    "scholarchain.dblp.yearRange": "*",
    "scholarchain.input.digest": "97897d97c73fcdbb2e749e1d665247a97f58632d840766c00ff52e450f04ad7d",
    "scholarchain.input.split": "dev",
    "scholarchain.orcid.maxRecordBytes": "1048576",
    "scholarchain.reducers.blocking": "1",
    "scholarchain.reducers.candidates": "1",
    "scholarchain.reducers.exactIds": "1",
    "scholarchain.reducers.normalize": "1",
    "scholarchain.reducers.teams": "1",
    "scholarchain.reject.maxExcerptChars": "256",
    "scholarchain.team.maxSize": "100",
    "scholarchain.team.sources": "dblp"
  },
  "attempt": 1
}
```

**`modules/hadoop/local-runs/er-candidates.heldout.run-manifest.json`**: byte-for-byte the same, except for three values (a test enforces that only the two input parameters differ):
- `"configDigest": "0506b32fb4886e5dd3864578bcbaca0e0ff870f4d897e8164ea35c11e6fc60ed"`
- `"scholarchain.input.digest": "bf26d518de202a48cbafc631f547bf69d3dc5bc2f86f3825f2fb7cd533b94731"`
- `"scholarchain.input.split": "heldout"`

**Digest formulas:**
- `input.digest` = `Canonical.hashFields("scholarchain.hadoop.er-split-inputs.v1", file1, sha256_1, …)` over that split's PROVENANCE.tsv rows, sorted by `file` (98 dev rows, 192 heldout rows).
- `configDigest` = `Canonical.hashFields("scholarchain.hadoop.local-config.v1", k1, v1, …)` over the sorted parameters. I used this because the Hadoop pipeline reads no ScholarChainConfig file.

**Run IDs** (`RunId.of(manifest)`, computed in the test):
- dev = `run:d8d2bf39506d4705d0d6ac694151181906406292dbcb59716d462eedd0561a3e`
- heldout = `run:ee9da33f0638ef5f120f3e3fb6a21476b07e43a008657eec3aff36314ffc9631`

**Block cap:** a single parameter, `scholarchain.blocking.maxBlockSize=4` with `oversizePolicy=drop`, pending entity-resolution-engineer's review.

**Snapshot-token derivation** (PROVENANCE.tsv columns `split`, `source`, `kind`, `retrieved_at_utc`):

| split | source | rows | retrieved_at_utc range |
|---|---|---|---|
| dev | openalex | 51 (45 work-by-doi, 6 author) | 2026-11-05T20:04:49Z to 20:10:45Z |
| dev | crossref | 6 (work-by-doi) | 2026-11-05T20:08:46Z to 20:10:35Z |
| heldout | openalex | 101 (87 work-by-doi, 14 author) | 2026-11-05T20:05:00Z to 20:10:46Z |
| heldout | crossref | 13 (work-by-doi) | 2026-11-05T20:08:47Z to 20:10:38Z |

- **dblp** is determined: `dblp-2026-10-03`, the `release_id` of all 119 DBLP-RELEASE-RANGES.tsv rows (dev 41, heldout 78). It is verified at runtime.
- **OpenAlex and Crossref** are not determined. The retrieval date is unique (2026-11-05), but no committed file defines a token for API-fetched records. The manifest's `openalex-2026-10-14` and `crossref-public-data-file-2026-03` name the bulk snapshots, which these records do not come from (`er_candidates.limitations[0]`).

Options for that decision:
- **(A)** `openalex-api-2026-11-05` / `crossref-api-2026-11-05`. Proposed; this is what the manifests carry, and it is enforced by `ErSplit.proposedApiSnapshot`.
- **(B)** Reuse the bulk snapshot IDs. Not recommended, because it misstates provenance.
- **(C)** A ledger-bound token, for example the PROVENANCE.tsv SHA prefix.
- **(D)** Recommended: dataset-engineer records explicit snapshot IDs in the `er_candidates` manifest section (value as in A), and Hadoop copies them.

A different token changes only `inputSnapshots` and the run ID, not `configDigest`.

### 4. Dev measurement (from the test run, not yet from the human's command)
- **Inputs:** dblp 41, openalex-works 45, openalex-authors 6, crossref-works 6. All read and accepted; rejected 0.
- **Normalize:** works 90, mentions 286, profiles 6. `DUPLICATE`=2, `CONFLICT`=2 and `FLAGGED`=2 were observed but not investigated.
- **Distinct candidate pairs:** researcher 296, work 78, total 374. `CANDIDATE_PAIRS`=374, `CANDIDATE_MERGED`=0, and the read-back matches (check ok).
- **Oversize at cap 4:** 0 groups, 0 members dropped, 0 refined, for both entities. `simulationMatchesRun=true` for both.
- **Level-0 group sizes:**
  - researcher: 292 members, 108 groups; sizes 1:3, 2:59, 3:13, 4:33.
  - work: 90 members, 36 groups; sizes 2:24, 3:6, 4:6.

Members dropped by cap:

| cap | researcher dropped (pairs) | work dropped (pairs) |
|---|---|---|
| 2 | 56 (106): crossref 6, dblp 24, openalex 26 | 17 (34): crossref 2, dblp 7, openalex 8 |
| 3 | 20 (160): crossref 3, dblp 7, openalex 10 | 8 (51): crossref 1, dblp 3, openalex 4 |
| 4, 5, 6, 8, none | 0 (296) | 0 (78) |

- **Teams:** 41 teams, 182 coauthor pairs, 0 oversize. Maximum team size 5 and largest pair expansion 10, both `conf/aaai/YuanBZLD23`. Largest block is 4 for both entities.
- **Timing (test JVM):** normalize 2180 ms, exact-ids 1187, teams 1187, blocking 1220, candidates 3482; total 9461 ms. These exclude sbt start-up, compile and JVM fork. I have no `runMain` measurement, so I am not proposing a timeout value.

### Exact dev Terminal command (human, from `/Users/taufeeqpatel/Desktop/ScholarChain`)
```
time sbt "hadoop/Test/runMain scholarchain.hadoop.local.ErSplitPipelineMain dev"
```
- **Send back:** every line containing `[er-split]` (sbt may prefix them with `[info]`), or the file `modules/hadoop/target/local-pipeline/er-candidates/dev/attempt-<n>/summary.txt`; the `time` real/user/sys values; sbt's final `[success]` or `[error]` line; and `echo $?`.
- **Retry:** just re-run. A failed or partial attempt is left untouched and the next run uses `attempt-<n+1>`. If an attempt is already complete, the run refuses; first move that attempt away, for example `mv modules/hadoop/target/local-pipeline/er-candidates/dev/attempt-1 modules/hadoop/target/local-pipeline/er-candidates/dev/attempt-1.old`. Note that after such a move the next attempt reuses the name `attempt-1`, in a new directory.

### Held-out command (HELD, do not run)
```
time sbt "hadoop/Test/runMain scholarchain.hadoop.local.ErSplitPipelineMain heldout"
```

### Output paths
- Fixed root: `/Users/taufeeqpatel/Desktop/ScholarChain/modules/hadoop/target/local-pipeline/er-candidates/<split>/attempt-<n>/`.
  - dev curated outputs: `curated/hadoop/snapshot=dblp-2026-10-03/run=d8d2bf39506d4705d0d6ac694151181906406292dbcb59716d462eedd0561a3e/`
  - heldout curated outputs: `curated/hadoop/snapshot=dblp-2026-10-03/run=ee9da33f0638ef5f120f3e3fb6a21476b07e43a008657eec3aff36314ffc9631/`
  - Also in each attempt: `hadoop-tmp/`, `_IN_PROGRESS` / `_FAILED` / `_SUCCESS`, `summary.txt`.
- Test outputs (git-ignored):
  - `/Users/taufeeqpatel/Desktop/ScholarChain/modules/hadoop/target/test-work/er-split-dev-run/dev/attempt-1/summary.txt`
  - `/Users/taufeeqpatel/Desktop/ScholarChain/modules/hadoop/target/test-work/er-split-report.txt`

```text
STATUS: BLOCKED-ON
OWNER: hadoop-engineer
WORK ORDER: HD-P5-02 (Phase 5): Crossref envelope reader fix with regression test; ErSplitPipelineMain (dev/heldout) with split guard, retry rule and summary; per-split run manifests; snapshot tokens from committed provenance; dev measurement command and block-size report; verification
FILES CREATED:
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/hadoop/local-runs/er-candidates.dev.run-manifest.json
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/hadoop/local-runs/er-candidates.heldout.run-manifest.json
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/hadoop/src/test/scala/scholarchain/hadoop/local/ErSplitPipeline.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/hadoop/src/test/scala/scholarchain/hadoop/local/ErSplitPipelineMain.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/hadoop/src/test/scala/scholarchain/hadoop/local/ErSplitPipelineSuite.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/hadoop/src/test/scala/scholarchain/hadoop/normalize/CrossrefEnvelopeSuite.scala
FILES MODIFIED:
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/hadoop/src/main/scala/scholarchain/hadoop/normalize/JsonSources.scala (CrossrefNormalizer.unwrap, EnvelopeKeys)
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/hadoop/src/main/scala/scholarchain/hadoop/JobParams.scala (optional manifest keys scholarchain.input.split, scholarchain.input.digest)
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/hadoop/docs/job-contracts.md (Crossref line shapes, the two parameters, test fixtures, new section "Local per-split pipeline")
COMMANDS RUN:
- git status --short (ok); git ls-files on modules/hadoop, datasets/samples/er-candidates, datasets, modules/core/src/main, build.sbt and project (ok); git rev-parse HEAD (ok: 36ffcf95e585c7def6aa06835a784820936e1b60); git diff --stat (ok; only my three tracked hadoop files changed in modules/hadoop)
- Refused by guard-dangerous.sh, nothing ran: git show --stat HEAD -- <file>; git grep (twice); git status --short -- modules/hadoop
- sbt hadoop/Test/compile: first run failed (ambiguous given in SortedMap.empty), fixed; second run success
- sbt hadoop/test: pass 1 and pass 2 exit 1 (expected: placeholder manifest digests, and one wrong test expectation, an authorless retraction notice, fixed in the test); pass 3 passed 80/80 (55 s); pass 4, after adding the team stats, passed 80/80 (63 s)
- sbt check: run 1 success 886 passed (134 s); run 2, after the last source change, success 886 passed (135 s)
TEST RESULTS:
- Final sbt check (observed): core 299, entityResolution 368, hadoop 80, tests 139 passed; Failed 0, Errors 0; markTestRun wrote .claude/.last-test-run (git_head=36ffcf95e585c7def6aa06835a784820936e1b60, worktree=dirty, suites=73, passed=886, skipped=0); [success] Total time 135 s
- New tests: CrossrefEnvelopeSuite 5, ErSplitPipelineSuite 8, all passing
VALIDATION RESULTS:
- The dev split ran end to end through the entry-point code inside ErSplitPipelineSuite, into target/test-work (not the fixed root): exit 0, status=complete, the pair check ok, simulationMatchesRun=true for both entities, and the second run refused (complete attempt exists). Numbers are in section 4.
- Both committed manifests pass checkManifest against the ledgers; their run IDs differ; their parameters differ only in input.split and input.digest.
- No held-out sample file was opened by me or by any test. The held-out manifest was checked only against PROVENANCE.tsv and DBLP-RELEASE-RANGES.tsv metadata rows.
ASSUMPTIONS:
- Test/runMain forks with Test/fork, Test/javaOptions and Test/envVars, and its classpath includes the Provided Hadoop jars. This is unverified because I cannot run runMain.
- configDigest is defined as the digest of the manifest parameters, since the Hadoop pipeline loads no ScholarChainConfig. Domain-engineer may want to review this use of the core field.
- codeVersion is the current HEAD (36ffcf95…), which does not contain this code. It must be set to the accepted commit before any evidence run. That changes the run IDs, but not configDigest or input.digest.
- The PROVENANCE.tsv rows of each split are the complete and authoritative input set; the fetch tooling's own --verify was not re-run by me.
RISKS:
- Cap 4 has no headroom on dev: the largest level-0 group is exactly 4. Held-out groups of 5 or more will refine or drop, and the held-out distribution has not been measured.
- The dev end-to-end test adds about 9-10 s to sbt check and reads dev samples on every run.
- The dev normalize counters DUPLICATE=2, CONFLICT=2 and FLAGGED=2 were not investigated (possibly OpenAlex works shared by two DOIs).
- Proposed snapshot tokens: if a different token is ratified, the manifests and the run IDs change.
- Uncommitted core and entity-resolution sources (DE-P5-03) compile into the hadoop test classpath; the marker records worktree=dirty.
MANUAL ACTION REQUIRED:
- Human: review; run the dev Terminal command above and send back the [er-split] lines, the time output and the exit code.
- Human, with dataset-engineer: ratify the OpenAlex and Crossref snapshot tokens.
- Human, with entity-resolution-engineer: decide the cap before the held-out run.
- Before evidence runs: commit core, entity-resolution and this work, then set codeVersion in both manifests to the accepted commit.
- Held-out execution, activation and commits stay held.
BLOCKED-ON OWNER: dataset-engineer / human (OpenAlex and Crossref snapshot tokens only)
BLOCKED-ON EVIDENCE: The PROVENANCE.tsv retrieved_at_utc values of all openalex rows (dev 51, heldout 101) and all crossref rows (dev 6, heldout 13) fall on 2026-11-05, but no committed artifact names a snapshot token for API-fetched records. datasets/dataset-manifest.yaml snapshot.id values openalex-2026-10-14 and crossref-public-data-file-2026-03 name bulk dumps, and er_candidates.limitations[0] says the record bytes come from the live APIs on the retrieval date. The manifests carry the proposed openalex-api-2026-11-05 / crossref-api-2026-11-05 (option A); option D is recommended.
NEXT GATE: human dev run with the command above → ER-P5-15 cap review using the dev histogram and cap simulation → token ratification → BE-P5-02 alias and timeout from the human's measured dev wall time → FE-P5-03 rows → commits and codeVersion update → separate approval for the held-out run
```

---

## ER-P5-15 (entity-resolution-engineer), decisions-15 and dev-run-01: freeze file, split checks, config evidence, block cap — verbatim

## ER-P5-15 report (entity-resolution-engineer)

Summary: four of the five requested pieces work and are tested: the freeze-file check, `--expect-split` in the evaluation and the queue, the wrong-run refusal in the queue (the G8 item), and printed config evidence. `sbt check` passes: 900 passed, 0 failed, 75 suites, 132 s.

The fifth piece is blocked. The file cannot yet be the *only* freeze accepted for final held-out metrics. That would break test-engineer's `MembershipEvaluationAdversarialSuite`, which expects exit 0 from the old hex flag, and I cannot edit that suite. Details are in section 1, under "Where I disagree".

My block-cap recommendation is `maxBlockSize=8` with `oversizePolicy=drop`. No gold labels were read, nothing touched the held-out data, and no gold metrics exist yet.

### 1. Hash file and `--expect-split` (GoldSetEvalMain)

**I agree with your single format:** `gold/entity-resolution/<split>-v1/er-gold-membership.jsonl.sha256`. It is exactly one line, `<64 lowercase hex><two spaces>er-gold-membership.jsonl`, followed by one LF.
- `FreezeRecord.parse` refuses all of the following, with a reason (nothing is repaired):
  - an empty file, a BOM, any CR, a missing final LF, or extra lines (including a trailing blank line);
  - bytes that are not UTF-8, upper-case hex, or 63 digits;
  - one space or a tab as separator, the binary marker ` *`, or an escaped `\` line;
  - whitespace around the name, or a name other than the membership file's base name (`./name` is also refused).
- `FreezeRecord.read` also refuses a symlink, a non-regular or missing file, a file over 4096 bytes, and a file outside the membership file's directory.
- The evaluation hashes the same single read of the membership bytes that it parses, and refuses with exit 2 on any mismatch.

**New flags:**
- `--manifest-sha256-file=<path>`. It requires `--expect-split`, and giving it together with `--manifest-sha256` is refused.
- `--expect-split=<heldout|dev>`. A manifest of the other split is refused (exit 2) before anything is evaluated.
- Every flag may appear only once.
- The output line is `manifest sha256=<sha> freeze=matches file=<abs path>`, plus `expect-split=<s> matches the manifest`.

**Where I disagree:**
- Your order says held-out final metrics must refuse without the file, so I would remove `--manifest-sha256=<hex>`.
- I can't do that this round. `tests/src/test/scala/scholarchain/tests/er/MembershipEvaluationAdversarialSuite.scala` lines 593-640 expect exit 0 with only `--manifest-sha256=<hex>`, and lines 497-591 rely on the same flag. That is test-engineer's file, so removing the flag would make `sbt check` fail.
- So the file is authoritative: the evidence path and the build task use it. The hex flag stays as an equivalent legacy check until test-engineer moves that suite to `--manifest-sha256-file` plus `--expect-split`.
- After that, removing the hex flag is a one-line change on my side, and I would make both flags required.
- `--expect-split` is optional for the same reason, except that it is required whenever the file flag is given. The planned build tasks always pass both.

### 2. Queue split guard (G8, done)
- `queue ... [--expect-split=<s>]` refuses a manifest of the other split.
- `queue` now also refuses (exit 2, nothing written) any run that does not hold every member's endpoint records (`RunQueue.missingEndpoints`). Without that, a run of the other split would silently produce `rows=0 not-generated=S`. A rerun of the same split under a new blocking, feature or policy version still passes, because the endpoints are record IDs.
- `membership` already refuses endpoints from the other split. `diagnostics` already refuses held-out endpoints.
- The G11 doc nit is fixed: the Scaladoc now names `BlindView.RowSchemaVersion` instead of v4.

### 3. Config
- **What gets loaded:** `GoldSetEvalMain` and `ReviewQueueMain` (in `queue` and `diagnostics` modes) now load through `ConfigEvidence.system()`. That is `ConfigLoader.load(sys.env, ConfigLoader.readLocalFile)`, exactly what `loadFromSystem` does (ConfigLoader.scala:240-241), plus a record of each file read. They read every comma-separated path in `SCHOLARCHAIN_CONFIG_FILES` and nothing else: no Typesafe defaults, and includes and substitutions are refused. With the build task's fork envVars set to one absolute path, that one file is all they read. `membership` reads no config; a test checks the source is never called.
- **Overrides:** `SCHOLARCHAIN_CFG_*` overrides the files key by key, and unknown names are refused (core precedence, unchanged). An inherited variable passes through a fork's envVars, and I don't refuse overrides in the program. Instead every configured run prints:
  - `config file=<abs> sha256=<hex>` for each file, hashed from the bytes parsed, so it equals `shasum -a 256`;
  - `config overrides=<names|none>`, with names only.
- **Recommendations:** the build task refuses any override (the BE draft already does), and the human checks `env | grep '^SCHOLARCHAIN_CFG_'` prints nothing.
- **Keys for `gold/entity-resolution/<split>-v1/er-config.conf`:** the same file is used for the queue run and the evaluation. Dummy values only, no secrets.
```
configSchemaVersion = "scholarchain.config.v2"
environment = local
aws { region = "us-east-1", accountId = "000000000000" }          # accountId must be a quoted 12-digit string
storage { rawBucket = "placeholder-raw", curatedBucket = "placeholder-curated", contextBucket = "placeholder-context", vectorBucket = "placeholder-vector", vectorIndex = placeholder-index, dynamoTablePrefix = placeholder, neptuneEndpoint = "placeholder.invalid" }   # openSearchEndpoint optional
models { llmModelId = "placeholder-llm-not-called", embeddingModelId = "placeholder-embedding", embeddingDimension = 8 }
graph { windows = [ { startYear = 2000, endYear = 2026 } ], policyVersion = placeholder-graph-policy, identityVersion = v1 }
bridge { algorithm = placeholder, sampleSize = 1, randomSeed = 1, maxIterations = 1 }
community { algorithm = placeholder, maxIterations = 1 }
entityResolution { policyVersion = er-policy.v8, acceptThreshold = 0.85, rejectThreshold = 0.2, llmBandLower = 0.4, llmBandUpper = 0.7, promptVersion = er-adjudication-prompt.v2 }
runtime { maxPathHops = 4, retrievalTopK = 10, defaultWindow = { startYear = 2000, endYear = 2026 } }
```
- **Where each ER value comes from:** `er-policy.v8.json` line 3 (policyVersion), line 22 (thresholds 0.85 / 0.2), line 23 (llmBand 0.4 / 0.7) and line 28 (promptVersion).
- **Other rules:**
  - `MatchPolicy.withConfig` refuses any other policy or prompt version.
  - The loader enforces reject < bandLower < bandUpper < accept.
  - `llmModelId` only has to be non-empty, trimmed text. No row is adjudicated, so it prints as `model=none (no adjudicator ran)`.
  - Bucket names must match `[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]`; tokens must match `[A-Za-z0-9._-]+`.

### 4. Block cap: recommend `maxBlockSize=8`, `oversizePolicy=drop` before the held-out run
- **Cap 4 has no headroom on dev.** The largest level-0 group is exactly 4: 33 of 108 researcher groups and 6 of 36 work groups sit at the cap.
- **Cap 8 changes nothing on dev.** Caps 4, 5, 6, 8 and none give the same 296 researcher and 78 work pairs (capsim lines 16-20 and 25-29 of dev `attempt-1/summary.txt`).
- **Held-out is about twice as large** (192 vs 98 provenance rows; 78 vs 41 DBLP records). An author on two sampled works across 3 sources plus a profile can reach a group of 7 or more.
- **The loss is larger than the drop count.** At cap 4, any group of 5 or more is refined, not just dropped:
  - level 1 splits "J." from "John";
  - level 2 separates profiles (`*`) from mentions;
  - level 3 splits by year.
- **That loss is invisible to the gold set.** Membership is drawn only from generated pairs, so pairs blocking loses never enter it.
- **The cost of cap 8 is bounded.** It allows at most 28 pairs per block instead of 6. Labeling cost does not grow: capacity is fixed at 240, and a larger cap can only raise the population, which helps reach the 200 minimum.
- **Why not "none" or 16:** both allow unbounded or dominating homonym blocks, which skew one stratum, and neither fits EMR scale.
- **Where the cap lives:** it is a Hadoop run-manifest parameter, not part of `er-blocking.v2`. `BlockingKeys.scala` lines 13-15 say the cap and policy belong to the run configuration. No blocking-version bump is needed, because keys and features are unchanged. The change shows up in `configDigest`, so the run ID changes, and membership records `generatingRunId`.
- **hadoop-engineer follow-up:**
  - update both manifests;
  - re-run dev so both splits use the same parameters (the dev run ID changes from `d8d2…`);
  - recompute the held-out run ID (`ee9da…` is valid only for cap 4 and codeVersion 36ffcf9).
- **Rule after the run:** report the held-out `oversize` line as it comes and do not re-tune the cap after seeing it.

### 5. Dev normalize counts (report only)
- **What the counts are:** works = 90 = 41 + 45 + 6 work inputs minus 2. So the 2 DUPLICATEs are work records. Both copy pairs differ in content (CONFLICT=2), and the reducer kept the copy with the smallest content hash (NormalizeJob.scala:195-246). FLAGGED=2 probably equals those 2 conflict flags (assumption).
- **What I could not check:** which source they come from. The reject parts are gzip and I did not read them. My guess is OpenAlex works fetched by two DOIs.
- **Effect on ER:**
  - there are no duplicate members;
  - the dropped copy's DOI or title variant never reaches the features;
  - if the kept copy does not map to a selection unit, membership refuses the run (fail-closed, G7).
- **Recommended next steps:**
  - run dev membership (D2) before held-out, so any such refusal shows up on dev;
  - hadoop-engineer prints the two record IDs.

### 6. Held-out command sequence (HELD, do not run)
All commands run from `/Users/taufeeqpatel/Desktop/ScholarChain` after the cap/manifest update and the commits.
- `R` = `modules/hadoop/target/local-pipeline/er-candidates/heldout/attempt-1/curated/hadoop/snapshot=dblp-2026-10-03/run=<HEX>`, where `<HEX>` comes from the pipeline's `runId=` line.
- `G` = `gold/entity-resolution/heldout-v1`.

1. **Pipeline (HD-P5-02):** `time sbt "hadoop/Test/runMain scholarchain.hadoop.local.ErSplitPipelineMain heldout"`.
   - Output: `R`. The pair count is on the `[er-split] ... distinct.total=` line.
   - Refuses a manifest or ledger of the wrong split.
2. **Membership:** `sbt "entityResolution/runMain scholarchain.entityresolution.review.ReviewQueueMain membership datasets/er-candidates/split-assignment.tsv datasets/samples/er-candidates/PROVENANCE.tsv R heldout G/er-gold-membership.jsonl G/er-blind-label-view.jsonl <SEED> 240"`.
   - Inputs: the two selection files and `R`. Outputs: the manifest and the blind view, each created once.
   - It prints `membership split=heldout seed=<SEED> capacity=240 population=<P> ...`. `<P>` is the distinct-pair count and must equal `distinct.total`.
   - It also prints `manifest sha256=` and a `freeze file:` hint. It refuses dev endpoints ("unit ... is in the dev split").
3. **Freeze (human):** `cd G && shasum -a 256 er-gold-membership.jsonl > er-gold-membership.jsonl.sha256 && shasum -a 256 -c er-gold-membership.jsonl.sha256 && cd -`.
   - The value must equal the printed sha256.
   - Record it in `human-gate.md`, then commit the manifest, blind view, `.sha256` file and `er-config.conf` before any label exists.
4. **Labels:** the human labels from `G/er-blind-label-view.jsonl` only and writes `G/er-gold-labels.jsonl`.
5. **Queue:**
   - Set up: `export SCHOLARCHAIN_CONFIG_FILES="$PWD/G/er-config.conf"`, then `env | grep '^SCHOLARCHAIN_CFG_'`, which must print nothing.
   - Run: `sbt "entityResolution/runMain scholarchain.entityresolution.review.ReviewQueueMain queue G/er-gold-membership.jsonl R G/er-review-queue.jsonl --expect-split=heldout"`.
   - It prints `members=<S> rows=<n> not-generated=0 ... candidates-outside-manifest=<P−S>`, then `expect-split=heldout matches the manifest`, `settings ...`, `config file=... sha256=...` and `config overrides=none`.
   - It refuses a wrong-split manifest or a wrong run.
6. **Evaluation:**
   - **Task form:** `sbt goldSetEvalHeldout`. This is the BE draft; it is not built and its row is inactive. The BE draft must change one argument to `--manifest-sha256-file=<abs G>/er-gold-membership.jsonl.sha256` and keep `--expect-split=heldout`.
   - **runMain form (same exported config):** `sbt "entityResolution/runMain scholarchain.entityresolution.evaluation.GoldSetEvalMain G/er-gold-membership.jsonl G/er-review-queue.jsonl G/er-gold-labels.jsonl --manifest-sha256-file=G/er-gold-membership.jsonl.sha256 --expect-split=heldout"`.
   - It prints `coverage members-sampled=<S> population=<P>` and the freeze and config lines.
   - Exit codes: 0 final, 2 refused (includes the wrong split and a freeze mismatch), 3 below the minimum.

**Dev, briefly:**
- **Diagnostics:** `sbt "entityResolution/runMain scholarchain.entityresolution.review.ReviewQueueMain diagnostics datasets/er-candidates/split-assignment.tsv datasets/samples/er-candidates/PROVENANCE.tsv <DEV R> target/er-review/dev-diagnostics/<run>/queue.jsonl target/er-review/dev-diagnostics/<run>/blind.jsonl 240 10 <SEED>"`.
  - Config: `SCHOLARCHAIN_CONFIG_FILES` = `gold/entity-resolution/dev-v1/er-config.conf`.
  - `resolved + refused` should equal 374. It refuses held-out endpoints.
- **Membership, queue and evaluation:** the same as steps 2-6 with `dev-v1`, `dev` and `--expect-split=dev`. The task form is `sbt goldSetEvalDev`, and every output line is marked DEV.

### 7. Other owners
- **build-engineer:** set the BE task to pass `--manifest-sha256-file` and `--expect-split`, and set the config via fork envVars. `Compile/run` is still not forked (G4, item C), so `sys.exit` behaviour inside sbt is still unverified. The 27 MB of Hadoop DEBUG output in `sbt check` belongs to BE-P5-03.
- **factory-engineer:** the FE rev-2 probes and text name `er-gold-membership.sha256`. They must become `er-gold-membership.jsonl.sha256`.

```text
STATUS: BLOCKED-ON
OWNER: entity-resolution-engineer
WORK ORDER: ER-P5-15 (Phase 5): --manifest-sha256-file single hash format, --expect-split runtime split check (evaluation and queue, G8), config evidence, block-cap recommendation, dev normalize note, held-out and dev command sequences
FILES CREATED:
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/evaluation/FreezeRecord.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/ConfigEvidence.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/evaluation/FreezeSplitConfigSuite.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/review/QueueSplitGuardSuite.scala
FILES MODIFIED:
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/evaluation/GoldSetEvalMain.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/review/ReviewQueueMain.scala
COMMANDS RUN: git status --short (ok, twice); git ls-files on modules/entity-resolution, tests, modules/hadoop/src/main (ok); git diff --stat --no-ext-diff -- modules/entity-resolution (ok, empty); sbt entityResolution/Test/compile (success); sbt entityResolution/test (success, 382 passed); sbt check (success, 132 s)
TEST RESULTS: sbt entityResolution/test: Passed 382, Failed 0, Errors 0 (was 368; +10 FreezeSplitConfigSuite, +4 QueueSplitGuardSuite). sbt check: core 299, entityResolution 382, hadoop 80, tests 139 passed; Failed 0; markTestRun wrote .claude/.last-test-run (git_head=36ffcf95e585c7def6aa06835a784820936e1b60, worktree=dirty, suites=75, passed=900, skipped=0); [success] Total time 132 s
VALIDATION RESULTS: No gold-set evaluation was run (no human labels exist; held-out forbidden). Policy er-policy.v8 unchanged: features er-features.v2, blocking er-blocking.v2, accept 0.85, reject 0.2, LLM band [0.4,0.7], prompt er-adjudication-prompt.v2, no model called. Confusion matrix, precision, recall, F1, unresolved rate, threshold sensitivity, false positives and false negatives: none (no labels). The new tests fail without the change: before it, the new flags are refused as "unknown argument" (exit 2) and FreezeRecord, ConfigEvidence, runWithConfig and RunQueue.missingEndpoints do not exist. Read the dev summary.txt (dev only).
ASSUMPTIONS: FLAGGED=2 equals the 2 CONFLICT flags; the dev duplicates are work records (inferred from 92 inputs giving 90 works); the FE gold/** row will be ratified with the .jsonl.sha256 name; the human runs runMain from the repository root; SCHOLARCHAIN_CFG_* are not set in evidence runs.
RISKS: Until test-engineer migrates, --manifest-sha256=<hex> still unlocks held-out final metrics (it is equivalent, but not the single mechanism); changing the cap to 8 changes both run IDs, so the HD-P5-02 IDs are stale; non-forked runMain sys.exit behaviour is unverified (G4); refinement at any cap can lose true pairs that the gold set cannot see; the dev conflicts were not traced to records.
MANUAL ACTION REQUIRED: Human: decide maxBlockSize=8 / drop; write er-config.conf per split (section 3); choose seeds; at the freeze, write the .jsonl.sha256 with shasum and record it in human-gate.md. Orchestrator: route the cap and manifest update to hadoop-engineer; the --manifest-sha256-file and --expect-split arguments to build-engineer (BE-P5-02); the .jsonl.sha256 name in the probes and rows to factory-engineer (FE-P5-03); and the suite migration to test-engineer.
BLOCKED-ON OWNER: test-engineer (only for making --manifest-sha256-file the sole freeze for held-out final metrics and making --expect-split required)
BLOCKED-ON EVIDENCE: tests/src/test/scala/scholarchain/tests/er/MembershipEvaluationAdversarialSuite.scala lines 593-640 (held-out, --manifest-sha256=<hex> only, assertEquals(c200, 0)) and lines 497-591 (--manifest-sha256 with exit 3 and refusals) call GoldSetEvalMain.run without --manifest-sha256-file or --expect-split; refusing hex-only held-out metrics would fail sbt check. Required change: pass --manifest-sha256-file=<freeze file> --expect-split=heldout (writing the freeze file in the temporary directory); then ER removes the hex flag in a follow-up.
NEXT GATE: test-engineer suite migration, then ER hex-flag removal plus sbt check; hadoop-engineer cap=8 manifests and dev re-run; BE-P5-02 and FE-P5-03 reconciliation; code-reviewer; human commits; held-out run only after separate approval
```

---

## ER-P5-16 (entity-resolution-engineer): hex flag removed, freeze file name enforced — verbatim

## ER-P5-16 report (entity-resolution-engineer)

Summary: the `--manifest-sha256=<hex>` flag is gone, so `--manifest-sha256-file` is now the only way to freeze a membership for final held-out metrics. The freeze file must also now be named exactly `<membership file name>.sha256`. `sbt check` passes: 906 passed, 0 failed, 1 ignored, 75 suites, 129 s. The ignored test is test-engineer's TARGET test, as you expected.

### 1. The hex flag is removed
- **Removed:** the option case, the `Hex64` constant, the `expectedSha` field, the hex branch of the freeze check and the "not both" rule. All of this is in `GoldSetEvalMain.scala`.
- **What a caller sees now:** `--manifest-sha256=...` is an unknown argument, so the run prints `unknown argument '--manifest-sha256=...'` and exits 2. That is the behaviour test-engineer's TARGET test asserts, so it should pass once its `.ignore` is removed. I have not run it un-ignored, because that file belongs to test-engineer.
- **Docs:** the doc comment is updated. The membership-mode freeze hint in `ReviewQueueMain` now names `--manifest-sha256-file`.
- **My tests:** they all use the freeze file now, through a new `SyntheticEval.freezeArgs` helper that writes the record the same way `shasum -a 256 <name> > <name>.sha256` does. This covers `GoldSetEvaluationSuite`, `EvaluationBatchSuite` and `FreezeSplitConfigSuite`. They now also assert that the hex flag is refused as an unknown argument.

### 2. Which flags are required
**Rule:** final held-out metrics need `--manifest-sha256-file`, and that flag needs `--expect-split`. So every run that can print final held-out metrics carries both. A run without them can still do three things, none of them final:
- print held-out coverage and refuse the metrics with exit 2 ("final held-out gold metrics refused"), or exit 3 below the minimum;
- report a refusal;
- print DEV diagnostics.

**Why I did not make both flags required for every run:** test-engineer's `MembershipEvaluationAdversarialSuite` deliberately runs the evaluation with no flags and expects particular refusals or exits:
- lines 645-651: the configuration-mismatch reasons;
- line 663: exit 3 below the minimum;
- lines 669-670: "final held-out" with no freeze argument.

Making the flags mandatory would replace each of those with a usage error and fail `sbt check`. The planned `goldSetEvalDev` and `goldSetEvalHeldout` tasks should still always pass both flags. If you want both mandatory for every run, test-engineer has to change those three call sites first; after that it is a one-line change in `options` on my side.

### 3. The freeze file's own name is checked
- **The rule:** `FreezeRecord.read` refuses a record whose file name is not exactly the membership file name plus `.sha256`. The message is "must be named exactly 'er-gold-membership.jsonl.sha256' ... not '<name>'". The comparison is exact and case-sensitive.
- **Order of checks:** symlink, regular file, size, directory, name, then the line's content. The directory check runs before the name check, so test-engineer's existing NotInDir expectations still hold.
- **New feature tests** (they fail without the change, because before it the correct line in `anything.txt` was accepted):
  - `FreezeSplitConfigSuite` "freeze record file: it must be named exactly ...": these names are each refused: `anything.txt`, `er-gold-membership.sha256`, `er-gold-membership.jsonl.SHA256`, `….sha256.txt`, `other-….sha256`, and `….sha256 ` with a trailing space. The correctly named file is accepted.
  - "entry point: a correct record under another name beside the manifest is refused": exit 2, and no coverage or report is printed. The correctly named record gives exit 0.

```text
STATUS: COMPLETE
OWNER: entity-resolution-engineer
WORK ORDER: ER-P5-16 (Phase 5): remove --manifest-sha256=<hex> so --manifest-sha256-file is the single freeze mechanism; state the flag-requirement rule; enforce the freeze file name <membership file name>.sha256 with feature tests; verify
FILES CREATED: none
FILES MODIFIED:
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/evaluation/GoldSetEvalMain.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/evaluation/FreezeRecord.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/main/scala/scholarchain/entityresolution/review/ReviewQueueMain.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/evaluation/GoldSetEvaluationSuite.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/evaluation/EvaluationBatchSuite.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/entity-resolution/src/test/scala/scholarchain/entityresolution/evaluation/FreezeSplitConfigSuite.scala
COMMANDS RUN: sbt entityResolution/test (success); sbt check (success, 129 s)
TEST RESULTS: sbt entityResolution/test: Passed 384, Failed 0, Errors 0 (was 382; +2 freeze-name tests). sbt check: core 299, entityResolution 384, hadoop 80, tests 143 passed with Ignored 1; Failed 0, Errors 0; markTestRun wrote .claude/.last-test-run (git_head=36ffcf95e585c7def6aa06835a784820936e1b60, worktree=dirty, suites=75, passed=906, skipped=1); [success] Total time 129 s
VALIDATION RESULTS: No gold-set evaluation was run (no labels; held-out not touched). Policy er-policy.v8 unchanged (accept 0.85, reject 0.2, band [0.4,0.7], prompt er-adjudication-prompt.v2). Confusion matrix, metrics, unresolved rate, sensitivity, FP/FN: none (no labels). Read tests/src/test/scala/scholarchain/tests/er/MembershipEvaluationAdversarialSuite.scala lines 430-875 to keep its expectations green.
ASSUMPTIONS: The TARGET test passes un-ignored (unknown argument, exit 2, no report or metrics printed); not run by me because it is ignored in test-engineer's file. The build tasks will always pass --manifest-sha256-file and --expect-split.
RISKS: Runs without the flags are still allowed for coverage and diagnostics (never final). The name check is case-sensitive, so on case-insensitive macOS a file written as ....SHA256 is refused unless the path is given in lower case. That is intended: the human must name it exactly.
MANUAL ACTION REQUIRED: Orchestrator: route the removal of `.ignore` from the TE-P5-06 TARGET test to test-engineer. If both flags should be mandatory for every run, test-engineer first updates the three no-flag call sites (lines 645-651, 663, 669-670) and then I make the parser require them.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: test-engineer removes the TARGET `.ignore` and runs sbt check; code-reviewer reviews ER-P5-15/16; human commits; then the remaining ER-P5-15 items (hadoop-engineer cap 8 manifests and dev re-run, BE-P5-02 task arguments, FE-P5-03 `.jsonl.sha256` name in probes and rows)
```

---

## HD-P5-03 (hadoop-engineer): cap 8 manifests, dev re-run, named dev conflicts/duplicates — verbatim

## HD-P5-03 hand-back (hadoop-engineer)

The block cap is now 8 (policy `drop`) in both split manifests, and `sbt check` passes: **908 passed, 0 failed, 75 suites, 133 s**, run in the foreground. My earlier figure was 886; the extra 22 are entity-resolution and `tests` work that landed in between (entityResolution 384, tests 145).

The dev split at cap 8 gives the same result as at cap 4: 374 distinct pairs and 0 drops. The 2 CONFLICT and 2 DUPLICATE records on dev are now named in the summary. Nothing was run on held-out, no held-out sample file was opened, and nothing was committed.

### 1. Cap change, digests and run IDs
The parameters diff is one line in each manifest; everything else is unchanged:
```
-    "scholarchain.blocking.maxBlockSize": "4",
+    "scholarchain.blocking.maxBlockSize": "8",
```
- `oversizePolicy` stays `drop`, and `input.digest`, `input.split` and `inputSnapshots` are unchanged.
- The only parameters that differ between the two splits are still `scholarchain.input.split` and `scholarchain.input.digest`.

| Split | New configDigest | Old configDigest | New run ID (codeVersion 36ffcf9) |
|---|---|---|---|
| dev | `179e02020754eb95b9abc855d55d8209c3e8baa2b46d4696a0b9a857a204ca40` | `f29ca569…` | `run:9a6d688f364032e542b77cede7c3fef370603064bbbfacdbf0f7be48625b0b62` |
| heldout | `34da975e0cbcf03181ff95e13c3db34128a2c5be3c163ac5bee599b43d0e786b` | `0506b32f…` | `run:63574417f7d6c31e23cebd6ef45ff23468f4dd1e7be50e5b5c89209be673ccef` |

- The test that pinned cap 4 now pins `"8"` (`ErSplitPipelineSuite`). No test pins a digest or run ID literally: the suite recomputes them from the ledgers and parameters.
- `configDigest` covers only the parameters. So setting `codeVersion` changes both run IDs again, but not `configDigest` or `input.digest`.

**codeVersion procedure (not circular):**
1. Human commits commit **A**: all code plus both manifests, which still carry the stand-in `codeVersion` 36ffcf9.
2. In both manifests, change only the `codeVersion` value to the 40-hex output of `git rev-parse HEAD` (commit A). Commit that as commit **B**, containing only those two files.
3. Check: `git diff --stat A B` must list only the two `modules/hadoop/local-runs/*.run-manifest.json` files.
4. Run the evidence pipeline at B. The code that ran is exactly A's, `codeVersion` names A, and the run IDs are fixed by B's manifests.
5. Optionally, run `sbt check` once at B.

The entry point does not check `codeVersion` against git, because it runs no git commands. This check is a manual review step.

### 2. Dev result at cap 8 (in-suite run of the same entry point)
The output went to `target/test-work`, not the fixed root.
- **Status:** exit 0, `status=complete`, `run:9a6d688f…`.
- **Inputs:** dblp 41, openalex-works 45, openalex-authors 6, crossref-works 6. All read and accepted; rejected 0.
- **Distinct pairs:** researcher 296, work 78, total 374. `CANDIDATE_PAIRS`=374, `CANDIDATE_MERGED`=0, read-back matches (check ok).
- **Oversize at cap 8:** 0 groups, 0 members dropped, 0 refined, for both entities. `simulationMatchesRun=true` for both.
- **Histograms (unchanged):** researcher sizes 1:3, 2:59, 3:13, 4:33; work sizes 2:24, 3:6, 4:6. The cap simulation gives identical results for caps 4, 5, 6, 8 and no cap.
- **Teams:** 41 teams, 182 coauthor pairs, 0 oversize; max team size 5 and largest pair expansion 10 (`conf/aaai/YuanBZLD23`).
- **Timing:** normalize 2212 ms, exact-ids 1163, teams 1146, blocking 1150, candidates 3448; total 9211 ms.

### 3. The dev duplicate and conflict records
My earlier reading that these were OpenAlex copy conflicts was wrong.

- **CONFLICT=2:** both are DBLP mapper flags for records with several distinct DOIs (record kept, the first DOI dedicated, the others in `additionalIds`). Neither is a content conflict between copies.
  - `conf/cinc/FotiadouKHV19`, file `conf__cinc__FotiadouKHV19.xml@96`, `reject:894c65b5a1789b5d17e410b6b8ab5d18f9cfc08d617aa14cb3a73d7a0bcfd7b2`. DOIs `10.22489/cinc.2019.015` and `10.23919/cinc49843.2019.9005722`.
  - `conf/ppopp/DiceKL16`, file `conf__ppopp__DiceKL16.xml@96`, `reject:adac394b5ac192b6bf64380e0b1646c63c76395b8547aee812b606dc90801b4b`. DOIs `10.1145/2851141.2851162` and `10.1145/3016078.2851162`.
- **DUPLICATE=2:** identical OpenAlex copies, which carry no reject record. The driver now names them from the JSON inputs (`allDuplicatesNamed=true`):
  - `W3170161232` in `10.1109_access.2021.3107189-a1bc886d.json` and `10.48550_arxiv.2106.04708-777fb342.json`.
  - `W4389175248` in `10.1007_978-3-031-47751-5_18-ff7c8ac8.json` and `10.48550_arxiv.2208.02999-54a956d8.json`.
- These look like preprint/version-of-record merges by OpenAlex. I did not map them to unit IDs; ER can do that through the PROVENANCE.tsv `file` column.

New summary lines:
- `normalize conflict source= id= disposition= file= rejectId= detail=`
- `normalize duplicate source= id= copies= files=a|b`
- `normalize conflictRecords= duplicateCounter= duplicateCopiesNamedFromJsonInputs= allDuplicatesNamed=`

The duplicate naming is a driver-side diagnostic outside MapReduce and is documented as such in `job-contracts.md`.

### 5. Terminal commands, from `/Users/taufeeqpatel/Desktop/ScholarChain`
**Dev (for evidence):**
```
time sbt "hadoop/Test/runMain scholarchain.hadoop.local.ErSplitPipelineMain dev"
```
- The human's earlier dev attempt (cap 4) is complete, so this run will refuse with exit 2 until that attempt is moved away first:
```
mv modules/hadoop/target/local-pipeline/er-candidates/dev/attempt-1 modules/hadoop/target/local-pipeline/er-candidates/dev/attempt-1.cap4
```
  If the earlier attempt has a different number, use that number.
- **Expected output:** `modules/hadoop/target/local-pipeline/er-candidates/dev/attempt-1/` with `summary.txt`, `_SUCCESS`, and `curated/hadoop/snapshot=dblp-2026-10-03/run=9a6d688f364032e542b77cede7c3fef370603064bbbfacdbf0f7be48625b0b62/`.

**Held-out (HELD, do not run):**
```
time sbt "hadoop/Test/runMain scholarchain.hadoop.local.ErSplitPipelineMain heldout"
```
- **Expected output:** `modules/hadoop/target/local-pipeline/er-candidates/heldout/attempt-1/curated/hadoop/snapshot=dblp-2026-10-03/run=63574417f7d6c31e23cebd6ef45ff23468f4dd1e7be50e5b5c89209be673ccef/`.

Both run IDs change once `codeVersion` is set to commit A.

```text
STATUS: BLOCKED-ON
OWNER: hadoop-engineer
WORK ORDER: HD-P5-03 (Phase 5, follow-up to HD-P5-02): cap 8 (drop) in both split manifests with recomputed digests; dev re-run at cap 8; identify dev DUPLICATE/CONFLICT records; sbt check; restate commands
FILES CREATED: none
FILES MODIFIED:
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/hadoop/local-runs/er-candidates.dev.run-manifest.json (maxBlockSize 8, configDigest 179e0202…)
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/hadoop/local-runs/er-candidates.heldout.run-manifest.json (maxBlockSize 8, configDigest 34da975e…)
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/hadoop/src/test/scala/scholarchain/hadoop/local/ErSplitPipeline.scala (conflict-record lines, JSON-input duplicate naming, inputs passed to summarize)
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/hadoop/src/test/scala/scholarchain/hadoop/local/ErSplitPipelineSuite.scala (cap pin 8, all splits' digests printed before asserting, report file reset per suite, assertions for the new lines)
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/hadoop/docs/job-contracts.md (new summary lines)
(all are HD-P5-02 files, uncommitted)
COMMANDS RUN:
- sbt hadoop/test: run 1 exit 1 (expected: stale dev configDigest; the new digest was printed); run 2 exit 1 (stale held-out configDigest; my conflict parser reported "kind=?"; fixed); run 3 passed 80/80 (57 s)
- sbt hadoop/Test/compile: failed once (missing summarize argument), fixed before run 3
- git diff --stat -- modules/hadoop (ok)
- sbt check: success
TEST RESULTS:
- sbt check (observed): core 299, entityResolution 384, hadoop 80, tests 145 passed; Failed 0, Errors 0
- markTestRun wrote .claude/.last-test-run (git_head=36ffcf95e585c7def6aa06835a784820936e1b60, worktree=dirty, suites=75, passed=908, skipped=0); [success] Total time 133 s
- A stack trace appears in the log, but every suite reports Failed 0
VALIDATION RESULTS:
- Dev at cap 8 (in-suite): exit 0, complete, 374 pairs (296/78), 0 drops, simulationMatchesRun=true for both entities, conflicts and duplicates named, allDuplicatesNamed=true
- Both manifests pass checkManifest against the ledgers; run IDs differ; only the split inputs differ
- No held-out sample was read
ASSUMPTIONS:
- codeVersion is still the stand-in 36ffcf9 until the two-commit procedure in section 1
- The in-suite dev run is representative of the human's Terminal run (same code; only sbt start-up and the fork differ)
RISKS:
- The held-out oversize result at cap 8 is unknown; per ER-P5-15 it is reported as-is and the cap is not re-tuned after seeing it
- The human's cap-4 dev attempt must be moved away before the evidence dev run, or the entry point refuses (by design)
- The proposed OpenAlex/Crossref snapshot tokens are still unratified (carried over from HD-P5-02)
MANUAL ACTION REQUIRED:
- Human: move the old dev attempt, re-run the dev command and send back the [er-split] lines, the time output and the exit code
- Human: ratify the snapshot tokens (dataset-engineer option D or A)
- Human: commit A and set codeVersion in commit B as in section 1
- Held-out run, activation and commits remain held
BLOCKED-ON OWNER: dataset-engineer / human (OpenAlex and Crossref snapshot tokens only, unchanged from HD-P5-02)
BLOCKED-ON EVIDENCE: Every OpenAlex and Crossref row in PROVENANCE.tsv was retrieved on 2026-11-05, but no committed artifact names a snapshot token for API-fetched records. The manifests carry the proposed openalex-api-2026-11-05 / crossref-api-2026-11-05.
NEXT GATE: human dev evidence run at cap 8 → token ratification → commits A and B (codeVersion) and sbt check at B → separate human approval for the held-out run
```
