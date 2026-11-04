# Phase 4 review reports

## code-reviewer, review 1 (verbatim)

### Phase 4 Hadoop review: REQUEST_CHANGES

I can't approve this phase. The parts that are built are well done: a real MapReduce job graph, a DBLP reader that is safe at split boundaries, counters, versioned rejects, idempotent run paths, and n(n-1)/2 pair expansion. Two problems remain inside modules/hadoop (findings H1 and H2 below, both major), and three required jobs are still incomplete because of dependencies on other agents (job 5 not built at all; jobs 3 and 6 partly).

The `sbt check` result was not rerun, because I have no Bash. The marker file `.claude/.last-test-run` reads `suites=16, tests_passed=215, tests_failed=0, git_head=8c679ad, git_worktree=dirty`. That matches what the agent reported.

### Answers to your five questions

**1. Suppressing pairs for oversize teams is not compliant.** The README states the pair rule with no exception:
- README:102: "A paper with `n` authors induces `n * (n - 1) / 2` undirected coauthor pairs before duplicate researcher pairs are consolidated. Large author teams can therefore create substantial pair expansion, which is one reason the project uses distributed processing rather than a local collection loop."
- README:1152-1155: "Emit candidate unordered coauthor pairs for every paper with at least two authors. / A paper with n authors generates n * (n - 1) / 2 candidate pairs before duplicates are consolidated. / … / Count and report unusually large author teams because they can create quadratic pair expansion."
- README:3056: "Coauthor-pair preparation is missing or incorrect for large author teams: up to 2 points lost."
- README:33: the system must report "the papers responsible for unusually large collaboration teams."

The README never allows a cap or suppression for author teams. The only oversize policy it allows is for blocking groups (README:2245). So large teams must be counted and reported, never pruned.

There is no default in the code. `scholarchain.team.maxSize` and `scholarchain.team.oversizePolicy` must both be set (JobParams.scala:63-65, 251-260), so "no cap" is not a mode. The `emit-pairs` value is compliant: TeamJob.scala:181-218 emits all pairs, increments OVERSIZE_TEAM, writes a flagged reject, and records maxTeamSize and maxPairExpansion. The `skip-pairs` value (TeamJob.scala:198-199) breaks the README:1153 rule.

A compliant policy is: always emit all pairs, count OVERSIZE_TEAM, flag the team, and report the largest team and expansion. If a cost-bounded experiment needs suppression, the suppression must travel with the data. That means a field on PublicationTeam or pair output (owned by domain-engineer), and Spark must refuse or label such runs. Today nothing in the curated output shows that pairs are missing. See H1.

**2. Blocking keys and candidate features (README:1140-1147, 2227-2231, 2245).**
- Done by hadoop-engineer (plumbing): bounded blocks, a maximum block size of at least 2, fallback levels run as one MapReduce job per round, counters (OVERSIZE_BLOCK, BLOCK_EMITTED, BLOCK_MEMBERS_REFINED, BLOCK_MEMBERS_DROPPED, MISSING_KEY), a drop/fail policy, and a reducer memory bound of maxBlockSize+1 (BlockingJob.scala:183-283; LocalPipelineSuite:194-208, 254-283).
- Missing, owned by entity-resolution-engineer: every key formula. Names, initials, coauthor context, title, year, venue, affiliation and source all go unused, because no production `BlockingKeySource` exists. The only implementations are the test fixtures `FixtureKeySource` and `SingleLevelKeySource` (TestSupport.scala:114-136). Job 5's feature functions are also missing. Minimal functions needed: `blockingKeysVersion`, `blockingLevels`, `researcherBlockingKeys(work, mention)`, `workBlockingKeys(work)`, and `matchFeatures(...)`.
- Missing, owned by domain-engineer: `MatchCandidate` and `CandidateBlock` contracts. Without them job 5 cannot emit MatchCandidate records.
- Missing, owned by hadoop-engineer: researcher and work blocking cannot both run in one run (H2), and the candidate-join job's plumbing does not exist at all.
- No ownership leak. Main code has only the `BlockingKeySource` interface (BlockingJob.scala:36-58) and mechanical NFC/whitespace/lowercase normalization (Shuffle.scala:31-41). No key or feature policy sits in hadoop main code, and nothing outside core and hadoop is imported.

**3. Checks of the agent's claims.**
- **Records never split across mappers: confirmed.** A record belongs to the split holding the `<` of its start tag; the reader reads past the split end to finish it (DblpInputFormat.scala:61-181). Tests split at every byte offset, use 3-way splits around record boundaries, split inside a UTF-8 sequence, use 64-byte real splits and gzip (DblpScannerSuite:78-193), and do a 512-byte split pipeline run (LocalPipelineSuite:106-124).
- **Real MapReduce: confirmed.** Job and MultipleInputs are used, with the stable partitioner and combiner (Pipeline.scala:285-460), run on LocalJobRunner.
- **No shared mutable state: confirmed.** All mutable state lives in per-task instances (for example TeamJob.scala:138-140, ExactIdJob.scala:136-137).
- **Compression: confirmed.** Map output compression and compressed final output are set at Pipeline.scala:289-305.
- **Determinism: confirmed for curated outputs.** The partitioner uses FNV-1a (Shuffle.scala:133-157), and reducers pick a canonical record and sort member lists. Spill order is not deterministic; the test compares it after sorting.
- **Idempotent paths: confirmed.** Paths are `snapshot=<dblp>/run=<id>`, `_SUCCESS` means skip, and a stage directory without `_SUCCESS` is refused (Pipeline.scala:96-116, 233-252).
- **All ten counters: confirmed reachable.** INPUT, ACCEPTED, REJECTED: NormalizeJob.scala:87-104 and RejectRecord.scala:151-155. DUPLICATE: NormalizeJob.scala:240-242 and TeamJob.scala:158. CONFLICT: NormalizeJob.scala:244, ExactIdJob.scala:164, DblpMapping.scala:97-105. OVERSIZE_BLOCK: BlockingJob.scala:261. OVERSIZE_TEAM: TeamJob.scala:182. EMITTED_PAIR: TeamJob.scala:216. MISSING_KEY: DblpMapping.scala:52-56 and BlockingJob.scala:139-150. SCHEMA_FAILURE: through the reject reasons. The driver always reports all ten (Pipeline.scala:262).
- **Versioned rejects: confirmed.** Format `scholarchain.hadoop.reject.v1` (RejectRecord.scala:53-115).
- **Explicit partition strategy: confirmed.** `StableHashPartitioner`, with reducer counts set in the manifest.
- **Provenance: confirmed for works, mentions, venues, institutions, topics, teams and pairs** (Outcome.scala:48-58). Block records lack it (H5).
- **No hardcoded config: confirmed.** No buckets, endpoints or thresholds; venue and year selections come from the manifest.
- **Pairs: work ID, year, team size and provenance are present. Author position is missing** (C3).

**4. Test quality.** Pair tests are hand-checkable and not tautological: 0, 1, 2, 3 (checks the (0,1),(0,2),(1,2) order), 26 → 325, and 200 → 19900 (PairExpansionSuite:62-119). Scanner tests are strong. Gaps are in H3 and H4, and one test locks in the non-compliant `skip-pairs` behaviour (LocalPipelineSuite:230-252).

**5. Ownership: no change outside modules/hadoop.** The git status snapshot shows only untracked `modules/hadoop/{docs,src/main/scala/scholarchain,src/test/scala/scholarchain}` and `docs/agent-runs/phase-04/`, and no modified tracked files. Glob confirms every new file is under `modules/hadoop/**`.

### Requirement traceability

**(A) Implemented**

| README requirement | Evidence | Test that would fail without it |
|---|---|---|
| 1125 / 2243: read complete DBLP records, never split across mappers | DblpInputFormat.scala:47-181 | DblpScannerSuite:78-193; LocalPipelineSuite:124 |
| 1126: support the selected DBLP record types | JobParams.scala:192-203, DblpMapping.scala:45-48 | DblpMappingSuite:91-99 |
| 1128: emit valid WorkRecord and AuthorMention | DblpMapping.scala:137-198; NormalizeJob.scala:262-268 | DblpMappingSuite:50-77; LocalPipelineSuite:140-142 |
| 2243: entity declarations, multiline titles, Unicode, near-boundary records | DblpParser.scala:42-149; scanner | DblpScannerSuite:24-150; DblpMappingSuite:148-174 |
| 1131 / 1134: one normalizer per dataset; malformed records go to versioned rejects | JsonSources.scala, OrcidNormalizer.scala; RejectRecord.scala | SourceNormalizersSuite:27-190; LocalPipelineSuite:143-154 |
| 1137: normalize DOI, arXiv, ORCID, OpenAlex IDs; duplicate and conflict counters | DblpMapping.scala:121-160; ExactIdJob.scala:45-178 | LocalPipelineSuite:187-192 |
| 1142 / 2245: maximum block size, fallback keys, counters, oversize policy | BlockingJob.scala:183-283; Pipeline.scala:409-460 | LocalPipelineSuite:194-208, 254-283 |
| 1150: one team record per retained work, with ordered mentions | TeamJob.scala:62-71, 165-179 | PairExpansionSuite:68-76; LocalPipelineSuite:163-168 |
| 1151: author-paper records keyed by mention and work ID | `mentions` output, NormalizeJob.scala:262-268 | LocalPipelineSuite:129 |
| 1152-1153 / 1193: n(n-1)/2 pairs; 1, 2, 3 and many authors | TeamJob.scala:42-59 | PairExpansionSuite:62-119 |
| 1155: count and report oversize teams | TeamJob.scala:181-196, stats at 224-226 | LocalPipelineSuite:230-252 |
| 1158: partitioned exports for Spark | job-contracts.md:146-149 | LocalPipelineSuite:139-184 |
| 1161: the ten required counters | Counters.scala:14-37; Pipeline.scala:262 | LocalPipelineSuite:102-104 (presence only) |
| 1162-1166: partition strategy, idempotent paths, no shared state, compression, determinism | Shuffle.scala:133-157; Pipeline.scala:96-116, 233-252, 285-305 | ShuffleAndParamsSuite:43-53; LocalPipelineSuite:210-227, 285-307 |
| 1191: at least two real MapReduce jobs | Pipeline.scala:331-460 | LocalPipelineSuite:90-101 |
| 2194-2210: documented mapper and reducer contracts | modules/hadoop/docs/job-contracts.md | n/a |

**(B) Missing or incorrect inside modules/hadoop:** findings H1 to H5 below.

**(C) Dependencies owned by other agents**

| ID | README requirement | Missing contract or function | Owner |
|---|---|---|---|
| C1 | 1144-1147, 2230-2231 (job 5) | `MatchCandidate` and `CandidateBlock` records in modules/core | domain-engineer |
| C2 | 1141 | `researcherBlockingKeys`, `workBlockingKeys`, `blockingLevels`, `blockingKeysVersion`, `matchFeatures` in modules/entity-resolution | entity-resolution-engineer |
| C3 | 1154 "author position … on every pair record" | `CoauthorPairCandidate` (Collaboration.scala:70-78) has no positions. Mention IDs are hashes (StableIds.scala:261-269), so position cannot be recovered without joining to mentions | domain-engineer |
| C4 | 1138, 1180 "identity-link inputs" | No identity-link or exact-ID record contract. ExactIdJob.scala:130 emits only counters, stats and flags | domain-engineer |
| C5 | 1127 "venue fields, ee links, URLs, record type"; 1133 "topic identifiers" | WorkRecord has no venue name, record type, ee/url, volume/pages or topicIds. Every DBLP journal article loses its venue (asserted at DblpMappingSuite:76: Spanner/TOCS and CACM get `venueId = None`). Work-to-topic links are lost when topics are merged | domain-engineer |
| C6 | 1132 "without losing source-specific IDs" | No source author profile contract. OpenAlex authors are rejected (JsonSources.scala:137-150), and ORCID holder-to-work claims are flagged and dropped (OrcidNormalizer.scala:135-145) | domain-engineer |
| C7 | 1169 | Bounded EMR smoke-run script | infra-engineer |
| C8 | 1171 "local bounded pipeline" | `sbt localPipeline` alias is inactive (B10). The pipeline does run inside `sbt check` | build-engineer, then factory-engineer to activate the allowlist row |
| C9 | 1197 | Adversarial XML, Unicode, missing-field, duplicate-ID, common-name and giant-team tests | test-engineer |

### Findings, grouped by owner

**hadoop-engineer**

- **H1 (major). TeamJob.scala:198-199 (also JobParams.scala:20-21, job-contracts.md:50 and 126, LocalPipelineSuite:230-252).**
  - Failure input: a manifest with `scholarchain.team.maxSize=20, scholarchain.team.oversizePolicy=skip-pairs` and the DBLP sample. The 26-author Spanner work produces a PublicationTeam with teamSize=26 but zero pairs (PAIRS_SUPPRESSED=325, EMITTED_PAIR=39).
  - Impact: breaks README:1153 and lands in the README:3056 penalty. Spark would undercount distinct coauthors and weights for exactly the large-team researchers that question 1 asks about. Nothing in `teams/` or `pairs/` marks the omission; it appears only in the reject output. maxPairExpansion is also understated, because `maxPairs` is updated only in the emit branch (TeamJob.scala:217), so the report shows 36 instead of 325.
  - Fix: remove `skip-pairs` from curated runs, or make it unusable for curated output until domain-engineer adds a marker, and record suppressed pairs in the expansion statistic.

- **H2 (major). JobParams.scala:34-38, 265-283; BlockingJob.scala:92-97; Pipeline.scala:409-460.**
  - The blocking stage handles a single `scholarchain.blocking.entity` per run.
  - Failure input: README:1140 needs both researcher and work blocking. Running both needs a second manifest with `entity=work`. Because `RunId.of` hashes every parameter (StableIds.scala:574-583), that second manifest gets a new run ID. The new run directory has no complete normalize stage, so normalize reruns over the full DBLP dump.
  - Impact: researcher and work candidate groups end up under two unrelated run IDs, which breaks a single provenance chain, and full normalization is paid twice.
  - Fix: make the blocking stage process both entities, with per-entity output directories, in one run.

- **H3 (minor). Tests: DblpMappingSuite and DblpScannerSuite have no repeated-author case** (a Grep for repeat, duplicate author and same author finds nothing), and no explicit DBLP record lacking optional fields such as year or ee. README:2243 requires both.
  - Impact: a regression that dedupes or drops a repeated author name would pass.

- **H4 (minor). LocalPipelineSuite:133-137, 162 assert DUPLICATE, CONFLICT, SCHEMA_FAILURE, MISSING_KEY and OVERSIZE_TEAM are all 0.** No MapReduce-level test drives CanonicalRecordReducer's DUPLICATE or CONFLICT path (NormalizeJob.scala:240-259) to a non-zero value. Only the pure Envelope merge is tested (ShuffleAndParamsSuite:14-41).
  - Failure scenario: two DBLP records with the same key and different titles. The counter increment and the conflict reject record are untested.
  - The determinism check (LocalPipelineSuite:217-227) runs twice in one JVM on LocalJobRunner, so it cannot detect value-order effects across real map tasks.

- **H5 (minor). BlockingJob.scala:67-76.** `BlockRecord` carries only `runId` and `keySourceVersion`. It has no source dataset, snapshot or schema/policy provenance, which goes against the CLAUDE.md "Provenance everywhere" rule. It is also a hadoop-internal format (`scholarchain.hadoop.block.v1`) that Phase 5 would consume outside core contracts (overlaps C1).

- **H6 (nit). Pipeline.scala:241, 260-278; job-contracts.md:66-68.**
  - If the driver dies after the job writes `_SUCCESS` but before `_counters.json`, a rerun reports the stage as "skipped" with empty counters (Pipeline.scala:507-508), so the "ten counters always present" promise fails for that report.
  - The doc says every reducer "sorts any lists it emits", but spill order is not sorted.
  - The blocking stage counts INPUT in two different units, works and spilled members (agent report: INPUT=91, ACCEPTED=99).

### Ownership
- No ownership violations found.

### Unverified concerns
- The DBLP entity table is assumed to be Latin-1 only (DblpParser.scala:42-149). If the real dblp.dtd declares more entities, valid records would be rejected as malformed-xml. The agent also flags this as unverified.
- A DBLP split that starts inside a comment containing a record start tag is not handled; the agent lists it as a risk, and I did not check real dumps.
- Pair expansion runs in TeamReducer, keyed by work. A team with thousands of authors (millions of pairs) concentrates in one reduce call. I didn't measure the performance or skew.
- Possible Typesafe Config conflict in the EMR job jar is not checked; `sbt hadoop/assembly` was refused.
- WholeFileInputFormat over millions of small ORCID files may be slow, and gzipped ORCID files are read as raw bytes (InputFormats.scala:60-115). Not tested.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 4 adversarial read-only review of uncommitted modules/hadoop/** against the README (commit d23d307) Phase 4 and Detailed Hadoop requirements
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (Read, Grep and Glob only)
TEST RESULTS: not run (no Bash). Marker .claude/.last-test-run reads suites=16, tests_passed=215, tests_failed=0, git_head=8c679ad, worktree=dirty, which matches the agent report
VALIDATION RESULTS: every finding was checked against file lines; the traceability tables are above
ASSUMPTIONS: the git status snapshot at session start is the current change set; README.md is the spec at d23d307
RISKS: the venue loss for DBLP journal articles (C5) and missing pair positions (C3) will hurt Spark and RAG venue filtering and pair semantics if Phase 6 starts before domain-engineer fixes them
MANUAL ACTION REQUIRED: human decides whether any non-curated pair-suppression mode is acceptable; if so, domain-engineer must add a marker field first
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: hadoop-engineer fixes H1-H6; domain-engineer delivers C1 and C3-C6; entity-resolution-engineer delivers C2; then hadoop-engineer reruns jobs 3, 5 and 6, and code-reviewer re-reviews
VERDICT: REQUEST_CHANGES
FINDINGS: H1 major (skip-pairs breaks README:1153; TeamJob.scala:198-199, 217) and H2 major (single blocking entity per run; JobParams.scala:265-283, Pipeline.scala:409-460), both hadoop-engineer. H3-H5 minor, H6 nit, hadoop-engineer. Dependencies C1, C3-C6 domain-engineer; C2 entity-resolution-engineer; C7 infra-engineer; C8 build-engineer and factory-engineer; C9 test-engineer
UNVERIFIED CONCERNS: DBLP entity set coverage; split starting inside a comment; reducer-side giant-team skew; Typesafe Config conflict in the EMR jar; ORCID small-file and gzip handling
```

## code-reviewer, focused review 2: core contracts from domain-engineer work order 1 (verbatim)

**Verdict: APPROVE.** I found no acceptance blockers in `modules/core`. The new contracts cover C1, C3, C4, C5, C6, H5 and the ORCID assertion source. Validation behaves as the report says, and the goldens and ID pins are literal and would fail if the behaviour broke. Hadoop can adopt everything without further core changes. What remains is work for hadoop-engineer (listed below) plus a few optional core improvements.

I could not run `sbt check` (I have no Bash). The marker `.claude/.last-test-run` reads `suites=20, tests_passed=284, tests_failed=0, git_head=8c679ad, git_worktree=dirty`, which matches the agent's report.

### 1. Phase 4 coverage

| Item | README | Resolved by | Status |
|---|---|---|---|
| C1 (blocks, MatchCandidate) | 1140-1147, 2227-2231 | `CandidateBlock` (Candidates.scala:108-242), `MatchCandidate` (Candidates.scala:272-407) | Resolved |
| C3 (pair positions) | 1154 "author position … on every pair record" | `CoauthorPairCandidate` v2, positionA/positionB (Collaboration.scala:75-85); `MentionId` cross-check (164-181) | Resolved |
| C4 (identity links) | 1138, 1180 "identity-link inputs" | `IdentityLink` (SourceIdentity.scala:156-305). The scheme tags `doi`, `arxiv`, `orcid`, `openalex-author` are exactly the key strings ExactIdJob.scala:48-55 already emits | Resolved |
| C5 (work fields) | 1127, 1133 | WorkRecord v2 adds recordType, venueName, volume, issue, pages, eeLinks, urls, topicIds, additionalIds (Bibliographic.scala:113-139) | Resolved |
| C6 (source author profiles) | 1132 | `SourceAuthorProfile` (SourceIdentity.scala:334-437) for OpenAlex A-IDs, ORCID holders and DBLP pids; ORCID claimedWorkIds | Resolved |
| H5 (block provenance) | CLAUDE.md "Provenance everywhere" | `CandidateBlock.provenance` must be `scholarchain-derived` with runId and snapshot; `sourceDatasets` lists member sources (Candidates.scala:79-82, 202-220) | Resolved |
| ORCID assertion source | datasets/schemas/orcid.md:27 | `AssertionSource` on WorkRecord and IdentityLink, ORCID only (SourceIdentity.scala:79-136; Bibliographic.scala:224-228; SourceIdentity.scala:278-282) | Resolved |

Nothing else that Phase 4 needs is missing from core.
- Abstracts are excluded by the accepted dataset manifest (dataset-manifest.yaml:329, 370; openalex.md:39), so the lack of an abstract-text contract is not a gap.
- Semantic Scholar is not a selected dataset.
- One borderline item, the ORCID `source-work-id`, is listed under optional improvements (O3).

### 2. Schema versions
- `work-record.v2` and `coauthor-pair-candidate.v2` are current. The v1 strings sit in `SchemaVersions.retired` (Contract.scala:47-50), and `checkVersion` rejects them.
- Tests:
  - SchemaVersionSuite:67-96 checks that v1 is rejected, and that v1-shaped JSON labelled v2 fails with every missing v2 key named.
  - SchemaVersionSuite:98-113 checks that pair v1 is rejected and that v2 without positions does not decode.
  - SchemaVersionSuite:26-65 checks the registry against the 24 contracts.
- **`derivedProv` fixture (Fixtures.scala:80-89):** it now uses the literal `"scholarchain.work-record.v1"` as a provenance pointer. The `derived()` golden fragment (WireContractSuite.scala:16) carries the same literal, so the derived goldens keep their bytes.
- **ExactIdScheme vs ExternalIdScheme:** `ExternalIdScheme` still has exactly its 7 tags (EnumCodecSuite:152-154). `ExactIdScheme` is a separate enum (Enums.scala:213-227). No existing record's wire enum changed.
- **`IdKind` additions** (StableIds.scala:29-32) are not on the wire.
- One limit: I cannot diff the existing goldens against commit 8c679ad (no git). See the unverified concerns.

### 3. Validation probes (each traced through the code)
- **Pair positions and endpoints:**
  - Positions must be non-negative, below teamSize and distinct.
  - A mention endpoint must equal `MentionId.of(workId, position)` (Collaboration.scala:164-181, 199-203).
  - Canonical ordering swaps positions together with their endpoints (Collaboration.scala:103-106).
  - Because the WorkRecord contract forces `authors(i).position == i` (Bibliographic.scala:289-291) and AuthorMention forces `mentionId == MentionId.of(workId, position)` (Bibliographic.scala:48-54), the index `i` Hadoop uses is the exact position.
- **CandidateBlock:**
  - `size == memberIds.size`, `1 <= size <= maxBlockSize`, `maxBlockSize >= 2`.
  - Members are strictly ascending by UTF-8 bytes and of the right ID kind.
  - Sources are datasets only, sorted, and cover every source a member names.
  - Provenance must be derived, and the ID binds kind, version, level, key and run.
  - Block keys reject control characters, including tab, which matches Hadoop's tab-separated shuffle key (BlockingJob.scala:83).
- **MatchCandidate:**
  - The key set is pinned with no score, threshold, confidence or decision (CandidateContractSuite:145-167). An extra `decision` key is rejected because `allowUnknownKeys(false)` is set.
  - Feature values must be finite (Candidates.scala:377-382), and feature keys must be tokens, so SortedMap order equals UTF-8 order.
  - The pair is canonical and not a self-pair.
  - exactIdAgreements are of the candidate's entity kind, sorted and distinct.
- **IdentityLink:**
  - The scheme must identify the entity kind.
  - A work link must use the asserting source's namespace and have no workId.
  - A mention link requires workId.
  - A profile link must be on a profile owned by assertedBy.
  - assertedBy must equal provenance.sourceDataset.
  - A null entityKind falls to `case _` with no NPE.
- **SourceAuthorProfile:**
  - profileId must equal namespace + sourceRecordId, and source must equal provenance.
  - An ORCID profile must carry the holder's iD, and only ORCID profiles may claim works, which must be the holder's own and sorted.
  - Private-name ORCID records are valid.
- I found no input that is accepted when it should be refused, or refused when it should be accepted, at blocker level. The weaker spots are optional items O1, O2, O4 and O5.

### 4. Fixed JSON examples
- WireContractSuite.scala:159-202 checks every new or changed record in both directions: `Json.write`, then `Json.encode` (validate and write), then `Json.decode` back to the fixture. All goldens are literal strings.
- Records covered: WorkRecord (DBLP, ORCID with assertion source, OpenAlex with topics and MAG), CoauthorPairCandidate v2, CandidateBlock, MatchCandidate, IdentityLink (mention and ORCID claim), and SourceAuthorProfile (OpenAlex and ORCID).
- The pair golden has positionA=1 and positionB=0, so it exercises the canonical swap.
- The frozen-type set equals `SchemaVersions.all` (WireContractSuite:204-232).
- ID formulas are pinned to hand-written netstrings for block, mcand, idlink (mention link, and the work link with an empty workId), and to literal hashes (StableIdSuite:74-139). I checked by hand that the netstring lengths in the encodings are correct, and Canonical.scala:24-34 measures lengths in UTF-8 bytes.

### 5. Hadoop integration compatibility
- **Positional builder:** a drop-in change at TeamJob.scala:51-58: `of(ms(i).mentionId, i, ms(j).mentionId, j, w.workId, w.year, n, w.provenance)`.
- **Strict `Json.decode`** (Contract.scala:146-194) is the correct strict path. It checks version, then provenance, then the defaulted keys (only for a supported version), then decodes and validates.
- **The `upickle.default.read[WorkRecord]` risk is real but bounded today.** Plain `read` would silently fill the v2 defaults for missing keys and skip validation.
  - TeamJob.scala:160 reads JSON that TeamMapper already accepted through `Json.decode` (TeamJob.scala:104).
  - NormalizeJob.scala:263 reads JSON the same job produced.
  - So no unvalidated record can currently reach those reads, but nothing structural stops that from changing. This is integration task (f).
- **CandidateBlock, MatchCandidate, IdentityLink, SourceAuthorProfile and the WorkRecord v2 fields** are all adoptable as the migration notes describe, with no core change. Hadoop-side items:
  - Task (e): `BlockMember` (BlockingJob.scala:61) has no source dataset, so `sourceDatasets` cannot be filled until it does.
  - Task (d): MatchCandidate needs one record per pair per run.
  - Task (g): bad ee/url values must not reject a whole work.

### 6. The temporary six-argument bridge (Collaboration.scala:122-160)
- **Correctness: confirmed.**
  - It recomputes `hashId(Mention, Tags.Mention, w, p)`, which is identical to `MentionId.of` (StableIds.scala:274-282), for p from 0 to teamSize.
  - It then delegates to the positional builder, so its output is valid v2 data with correct positions.
  - **Duplicate mention IDs** (x == y) set px and py to the same value, then `CanonicalPair.of` returns SelfPair, the result is Left, and TeamJob throws (TeamJob.scala:211-214). This fails loudly and is never a wrong position.
  - **A repeated author name** gives distinct mention IDs per position, so it is correct.
  - **A foreign mention, a researcher ID, or a position at or above teamSize** gives Left (PairPositionSuite:114-142). The suite also checks that the bridge equals the positional builder for every pair of a 5-author team (88-112).
- **Cost:** about max(px, py)+1 hashes per pair, so about n³/3 SHA-256 calls per team. That is about 3×10⁸ for n=1000 and about 9×10⁹ for n=3000. Each pair also costs 2 more hashes for validation, which the positional builder pays as well.
- **To remove it:**
  1. hadoop-engineer migrates TeamJob.scala:51-58 (the only caller).
  2. domain-engineer then deletes the overload and the two bridge tests in PairPositionSuite (lines 88-142).
  3. `sbt check` passes.

### 7. Pair suppression
- Nothing in core represents or permits suppressed pairs. PublicationTeam and CoauthorPairCandidate have no suppression or truncation field; a Grep of modules/core for suppress, skip, oversize and truncat finds only a BlockKey doc comment.
- The `skip-pairs` mode is still present in hadoop: JobParams.scala:21, TeamJob.scala:198-199, Counters.scala:62-63, job-contracts.md:50 and 126, and LocalPipelineSuite.scala:230-252. This is task (c), still open.

### ACCEPTANCE BLOCKERS
None.

### REQUIRED INTEGRATION TASKS (track, not fix now)

| ID | Task | Evidence | Owner | Status |
|---|---|---|---|---|
| (a) | Adopt the positional pair builder and strict `Json.decode` | TeamJob.scala:51-58 (bridge call), 160 (`upickle.default.read`) | hadoop-engineer | Open (work order 3) |
| (b) | Remove the six-argument bridge and its tests after (a) lands | Collaboration.scala:122-160; PairPositionSuite:88-142 | domain-engineer | Open, blocked on (a) |
| (c) | Never suppress required coauthor pairs: remove `skip-pairs` and its test; count and flag oversize teams; report the true maxPairExpansion | TeamJob.scala:198-199, 217; JobParams.scala:21; Counters.scala:62-63; job-contracts.md:50, 126; LocalPipelineSuite.scala:230-252 | hadoop-engineer | Open (review 1, H1) |
| (d) | Emit one MatchCandidate per (pair, versions, run). The ID excludes block keys (StableIds.scala:685-713), and blockKeys must list every co-occurring block, so job 5 must group by candidate before emitting. Otherwise one candidateId appears with differing blockKeys | Candidates.scala:257-259 | hadoop-engineer | Open |
| (e) | Replace `BlockRecord`/`scholarchain.hadoop.block.v1` with `CandidateBlock.build`, and carry each member's source dataset in `BlockMember` so `sourceDatasets` can be filled | BlockingJob.scala:61, 67-80, 236-255 | hadoop-engineer | Open |
| (f) | Use `Json.decode` (not `upickle.default.read`) wherever a WorkRecord is read | TeamJob.scala:160; NormalizeJob.scala:263 | hadoop-engineer | Open (part of (a)) |
| (g) | When filling eeLinks and urls, drop and flag values with whitespace or control characters, and remove duplicates. Otherwise one bad link rejects the whole DBLP work, because `Validated.link` and `distinct` apply to the entire record | ValidationError.scala:102-105; Bibliographic.scala:188-192; DblpMapping.scala:187-188 | hadoop-engineer | Open |
| (h) | Update job-contracts.md, which still documents `PublicationTeam v1 … CoauthorPairCandidate v1` and `skip-pairs` | modules/hadoop/docs/job-contracts.md:50, 125-126 | hadoop-engineer | Open |
| (i) | Do not run TeamJob on EMR or the full DBLP dump until (a) lands (bridge cost about n³/3 hashes per team) | Collaboration.scala:140-148 | hadoop-engineer (code); infra-engineer (smoke-run script must not run before (a)) | Open |

### OPTIONAL IMPROVEMENTS (not blocking; only if the human asks)
- **O1 (minor). Provenance.scala:81-91.** A `scholarchain.*` sourceSchemaVersion is checked only for its form, not against `SchemaVersions.all` and `retired`. A typo such as `scholarchain.work-recrod.v2` validates. Fixtures.scala:85 also stamps the derived fixtures with the retired v1 pointer, which is harmless but stale. Owner: domain-engineer.
- **O2 (minor). SourceIdentity.scala:235-245.** A mention link's `workId` is not cross-checked against the mention ID; mention IDs are hashes, so this is not possible without a position. Failing input: an ORCID link on mention M of work W1 with `workId=W2` validates. Hadoop builds links from the mention, so the risk is low. Owner: domain-engineer.
- **O3 (minor).** ORCID `source-work-id` is observed in every sample (datasets/schemas/orcid.md:25; samples/orcid/*.xml) but has no `ExactIdScheme` tag (Enums.scala:213-223), so it will be dropped. The human decides whether README:1132 "without losing source-specific IDs" covers a client-local ID. If it does, domain-engineer adds a work scheme (an additive enum change). Owner: domain-engineer, after a human decision.
- **O4 (nit). Candidates.scala:214-219.** For blocks with only mention members, `sourceDatasets` cannot be verified and may be a superset. Owner: domain-engineer.
- **O5 (nit). Collaboration.scala:162, 193-194.** A pair with one `researcher:` endpoint and one `mention:` endpoint validates. This is not reachable in Phase 4. Owner: domain-engineer.
- **O6 (nit).** A core helper mapping `ExternalId(openalex, "A…")` to `ExactId(OpenAlexAuthor, …)` would spare Hadoop a hand mapping (AuthorMention.sourceAuthorId uses ExternalIdScheme). Owner: domain-engineer.

### Ownership
No violations. The git status snapshot shows the core changes only under `modules/core/**`, and this review wrote nothing.

### Unverified concerns
- **Unchanged goldens:** I cannot diff against 8c679ad, so I cannot independently confirm that no pre-existing golden other than WorkRecord and CoauthorPairCandidate changed bytes. The fixture comment and the literal `derived()` fragment are consistent with the claim.
- **uPickle 4.1.0 and missing `Option` keys:** I could not confirm whether it treats a missing `Option` key as `None`. If it does, a JSON object missing for example `IdentityLink.workId` or `SourceAuthorProfile.orcid` would decode without error, because `fieldsWithDefaults` covers only WorkRecord's v2 fields. No test pins this. It is a pre-existing pattern across all contracts.
- **Hadoop task timeout:** I did not verify whether MultipleOutputs writes report task progress. If they do not, a very large team under the bridge could hit `mapreduce.task.timeout` before (a) lands.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 4, focused review 2 -- new and changed modules/core contracts from domain-engineer work order 1 (follow-up instruction 3)
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (Read, Grep, Glob only)
TEST RESULTS: not run (no Bash). Marker .claude/.last-test-run: suites=20, tests_passed=284, tests_failed=0, tests_errored=0, git_head=8c679ad, git_worktree=dirty, which matches the domain-engineer report
VALIDATION RESULTS: checked against code: C1, C3, C4, C5, C6, H5 and the ORCID assertion source resolved; v1 rejection and SchemaVersions registry; validation rules; literal two-way goldens; netstring ID pins; bridge correctness and cost; no pair-suppression representation in core
ASSUMPTIONS: the session-start git status is the change set; README.md is the spec at d23d307; abstracts are out of scope per the accepted dataset manifest
RISKS: the bridge's cubic cost if TeamJob runs at scale before task (a); skip-pairs still present in hadoop (task c); duplicate MatchCandidate IDs if job 5 does not consolidate block keys (task d)
MANUAL ACTION REQUIRED: human decides whether ORCID source-work-id must be preserved (O3)
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: work orders 2 and 3 may proceed; hadoop-engineer closes tasks (a), (c)-(i); domain-engineer then removes the bridge (b); code-reviewer re-reviews modules/hadoop
VERDICT: APPROVE
FINDINGS: no acceptance blockers. Required integration tasks (a), (c)-(i) hadoop-engineer ((i) also infra-engineer); (b) domain-engineer after (a). Optional O1-O6 domain-engineer (O3 after a human decision)
UNVERIFIED CONCERNS: unchanged bytes of pre-existing goldens (no git diff); uPickle 4.1.0 handling of missing Option keys; Hadoop task timeout under the bridge for very large teams
```

## code-reviewer, final review (work order 7 of 7, verbatim)

### Phase 4 final review (work order 7 of 7): REQUEST_CHANGES

Phase 4 is close to acceptance. Code and tests meet every README Phase 4 job, operational behaviour and gate item I could check by reading. One acceptance blocker remains, and it is in the documentation: the module's authoritative contract states, without evidence, that the real DBLP dump contains no comments, CDATA or processing instructions. You told the agents not to assume that. The fix is one sentence (owner hadoop-engineer). After it, I expect to APPROVE.

I could not run anything (Read, Grep and Glob only). `.claude/.last-test-run` reads `suites=32, tests_passed=407, tests_failed=0, git_head=8c679ad, git_worktree=dirty`. That matches the repair-01 report (core 252, entityResolution 56, hadoop 67, tests 32). It is indirect evidence only.

---

### ACCEPTANCE BLOCKERS

**B1 (major, documentation honesty). Owner: hadoop-engineer.**
- **Where:** `modules/hadoop/docs/job-contracts.md:180-181`, which says: "The real DBLP dump has no comments, CDATA, or PIs in its body; the external DTD is never read."
- **Why it is wrong:**
  - Nothing in the repository supports the claim. `datasets/dataset-manifest.yaml:86` says the full dump's XML declaration was "UNVERIFIED … not read". The committed DBLP sample itself begins with a comment (`datasets/samples/dblp/dblp-sample.xml:2-9`).
  - It contradicts your instruction 7 ("Do not assume the chosen DBLP snapshot excludes the problematic XML constructs without evidence").
  - The same file calls itself the module's authoritative contract (`job-contracts.md:3-4`).
- **Failure scenario:** an operator reads the contract, concludes the residual reader limitation (below) cannot occur on DBLP, and sets `dblp_markup_policy=accept-reported`, or skips a markup scan for a full run.
  - If the dump does contain a comment, CDATA section or PI with an opener look-alike (for example `<?` inside a comment) near a split's lookback start, a split read can disagree with a whole-file read.
  - The result is a lost record or a ghost work, which breaks README:1166 (deterministic output) and README:2243 (record synchronization).
- **Fix:** replace the sentence with a statement that the snapshot's body markup is unverified and must be scanned before any run, with a pointer to the script's `dblp_markup_policy`. No code change is needed.
- **Related:** the same unsupported claim appears in hadoop-engineer's repair-01 report (`docs/agent-runs/phase-04/repair-01.md:60, 98`). That file is evidence and was transcribed faithfully, so this is a note, not a separate finding.

---

### REQUIRED CHECKS BEFORE FUTURE AWS EXECUTION (not Phase 4 blockers)

1. **Full-dump markup scan (owners: human, infra-engineer).** The residual reader case is real; see assessment 5.
   - For smoke runs, `run-bounded-pipeline.sh` with `dblp_markup_policy=refuse-if-present` covers it (`scripts/run-bounded-pipeline.sh:688-689, 753-757`).
   - Functional runs are refused by the script (lines 429), and the full dump has no equivalent scan yet. One is needed before any non-smoke run.
   - Also note: a comment or CDATA section longer than `maxRecordBytes` is treated as unclosed. Records inside it then become works in both whole-file and split reads, with only a `malformed-xml` report on the opener (`DblpInputFormat.scala:152-158`; job-contracts.md:160-164).

2. **EMR step Main-Class (owners: infra-engineer and build-engineer; inference, not verified).**
   - The script sends `HadoopJarStep.MainClass = scholarchain.hadoop.pipeline.PipelineDriver` (`run-bounded-pipeline.sh:208, 841-842`), and job-contracts.md:312 documents `hadoop jar <jar> scholarchain.hadoop.pipeline.PipelineDriver …`.
   - build-engineer infers that sbt-assembly writes `Main-Class: PipelineDriver` into the jar manifest. As I understand Hadoop RunJar (not verifiable here), when the manifest has a Main-Class, the class-name argument is passed as `args[0]`.
   - `DriverArgs.parse` would then see an odd argument count and exit 2 (`PipelineDriver.scala:44`). This fails closed but makes the smoke run fail.
   - Check: read the jar manifest, then either drop MainClass from the step or keep Main-Class out of the manifest.

3. **Operator IAM gaps for `s3_absent` (owner: infra-engineer).**
   - The script header's IAM list (lines 190-195) gives `s3:PutObject` on `<pipeline_runs_uri>/*` only.
   - `s3_absent` uses `head-object` and treats anything other than 404 as fatal (lines 943-951). Without `s3:ListBucket` (and `s3:GetObject`) on that prefix, S3 returns 403 for a missing key, so every execution stops at "submission keys". It fails closed.
   - Phase 12 IAM must include those permissions, or the check must change.

4. **AWS CLI and API assumptions not checked against current docs (owner: infra-engineer; the agent says the MCP docs servers were unavailable).**
   - `s3api put-object --if-none-match "*"` (lines 864, 1150-1151, 1134-1135).
   - `emr cancel-steps --step-cancellation-option SEND_INTERRUPT` (1163-1164).
   - `emr list-steps --step-states PENDING CANCEL_PENDING RUNNING`, `get-managed-scaling-policy`, `get-auto-termination-policy`.
   - JSON field names: `.Cluster.Status.State`, `.ReleaseLabel`, `.Applications[].Name`, `.Tags`, `.LogUri`, `.InstanceCollectionType`; `InstanceGroups[].{InstanceGroupType,RequestedInstanceCount,RunningInstanceCount,AutoScalingPolicy}`; `InstanceFleets[].{InstanceFleetType,TargetOnDemandCapacity,TargetSpotCapacity,InstanceTypeSpecifications[].WeightedCapacity}`; `.ManagedScalingPolicy.ComputeLimits.{MaximumCapacityUnits,UnitType}`; `.AutoTerminationPolicy.IdleTimeout`; `.Step.Status.{State,FailureDetails}`; `.StepIds[0]`.
   - Required AWS CLI v2 version for `--if-none-match`.

5. **Script lint and executable bit (owner: human).**
   - shellcheck was never run (not installed). `bash -n` passing is the agent's report only.
   - The file is not executable per the agent. You must `chmod +x` it before committing, because script provenance compares the executable bit.

6. **Cost bound semantics (owner: human ratification).** The estimate covers `max_instances × price × step_timeout` only (line 785). The real window can run about 10 minutes plus one poll interval longer (cancel wait, lines 1194-1199). Idle cluster time, S3 and data transfer are excluded (documented at line 807). The hard caps (lines 214-233) still need ratification.

7. **Typesafe Config unshaded in the job jar (owner: build-engineer, settled by the first EMR run).** Version conflicts and classloader order on EMR are unverified (Phase 3 human-gate risk, still open).

8. **Spark jar bundles scala-library 2.13.16 and scala3-library 3.3.6 against EMR's Scala 2.12 Spark (F2; owner build-engineer, before Phase 6).**

9. **Giant teams at EMR scale (owner: hadoop-engineer, measured on EMR).**
   - One `TeamReducer` call per work emits n(n-1)/2 pairs (about 4.5 million for 3,000 authors).
   - The candidate join writes n copies of a work's JSON (`CandidateJob.scala:266-358`).
   - Documented in job-contracts.md:303-307 but not measured. The script caps teams at 3,000 authors.

10. **Still unverified from earlier reviews:**
    - The DBLP DTD entity set (Latin-1 table only, `job-contracts.md:182-183`).
    - Many small ORCID files and gzipped ORCID input through `WholeFileInputFormat`.
    - Whether any real DBLP field element shares a name with a record type (scanner assumption, `DblpInputFormat.scala:159-161`).

---

### OPTIONAL IMPROVEMENTS

**O-ZW (minor, owner entity-resolution-engineer). Zero-width and other format characters split name tokens; recommended before Phase 5.**
- **Code path:** `TextFolding.fold` (`modules/entity-resolution/src/main/scala/scholarchain/entityresolution/TextFolding.scala:70-81`).
  - NFKD leaves U+200D ZWJ, U+200C ZWNJ, U+200B, U+2060, U+FEFF, U+00AD (soft hyphen), U+200E and U+200F unchanged, and all are category Cf.
  - `isMark` covers only Mn, Me and Mc (57-61). They are not in `Special`, `Apostrophes` or `Hyphens` (U+00AD is not in the `Hyphens` set at 54-55).
  - `Character.isLetterOrDigit` is false for them, so line 80 turns each into a space.
- **Effect:** inside a word, the word splits. `"Jian Wa‍ng"` folds to `jian wa ng`, `PersonName.parse` (Names.scala:131-139) makes the family name `ng`, and the level-0 key becomes `ng|j` instead of `wang|j`.
  - At the edges (BOM prefix, RLM wrap) they are trimmed and harmless, as UnicodeSuite:162 asserts.
- **Impact:** blocking recall loss (false negatives) only. Raw names are preserved byte for byte, nothing is over-merged, and the result is deterministic. It is realistic for ZWNJ in Persian names from OpenAlex or ORCID, and for soft hyphens in scraped names.
- **Fix:** delete Cf characters before folding and bump to `er-blocking.v2` / `er-features.v2`.
- **Test gap (owner test-engineer, nit):** UnicodeSuite:161 is named "invisible format characters … give deterministic keys", but it only prints the W3 and W4 keys (line 165), so the ZWJ/ZWNJ behaviour is unasserted.

**Other optional items:**
- **nit, hadoop-engineer:** job-contracts.md:299-301 says the former 6-argument bridge "is no longer called by this module". It no longer exists at all; core now has only the 8-argument `of` (`Collaboration.scala:93-120`).
- **nit, domain-engineer:** stale names after the move to v3.
  - Test name `WorkRecordV2Suite.scala:39` ("…still builds a valid v2 record") now builds v3.
  - Private `v2Fields` (`Bibliographic.scala:198`) also validates the v3 field.
- **minor, infra-engineer:** `run-bounded-pipeline.sh:1157-1175`. The INT/TERM trap is installed only after `add-steps` returns, so a Ctrl-C in that window leaves the step running with no cancel and no summary.
  - Install the trap before submission, guarded by `-n $STEP_ID`.
  - Also, the lock directory (912) is created before `trap cleanup EXIT` (924). A failure in `mktemp` (913) leaves a stale lock.
- **nit, hadoop-engineer:** `DblpMapping.scala:69-70` classifies non-publication types before status. An `Interrupted` or `Unterminated` `<www>`/`<person>` record is counted `SKIPPED_NON_PUBLICATION` and never rejected, so malformed non-publication markup is invisible. No publication data is lost.
- **nit, entity-resolution-engineer:** block keys have no length cap; a key's length grows with the raw name or title (`BlockingKeys.scala:101-145`; `Candidates.scala:20` checks text only).
- **nit:** works with 0 authors get no `PublicationTeam` (core requires teamSize >= 1, `Collaboration.scala:52`). They are counted in `TEAM_NO_AUTHORS` and documented, which is a defensible reading of README:1150 "every retained work".
- **nit:** derived records carry only the DBLP snapshot in `provenance.snapshot` (`BlockingJob.scala:169-190`, `CandidateJob.scala:142-152`). The run ID resolves to all snapshots through `_run-manifest.json`, and member datasets are on `sourceDatasets` / `sourceDatasetA/B`. Hadoop-internal `BlockMembership`, `JoinedMember` and spill carry no provenance and are not curated (job-contracts.md:47-51).

---

### Required assessments

#### 1. Requirement traceability (README:1122-1198)

Key: V = verified by reading code or tests. R = reported by agents only (the run results). M = missing.

| Item | Status | Evidence |
|---|---|---|
| J1 complete records, never split | V (with residual, see 5) | DblpInputFormat.scala:141-181, 185-503, 505-574; DblpSynchronizationSuite:157-192, 248; DblpScannerSuite:80-115; AdversarialXmlSuite:252-280 |
| J1 selected record types | V | JobParams.scala:192-205, 307-333; DblpMapping.scala:69-71 |
| J1 key, title, authors, year, venue, ee, url, type | V | DblpMapping.scala:239-270; DblpMappingSuite:79, 108, 125 |
| J1 valid WorkRecord / AuthorMention | V | Outcome.scala:75-80, 160-188; checkContracts (Harness.scala:300-340) |
| J2 one normalizer per dataset; IDs, affiliations, topics; versioned rejects | V | JsonSources.scala, OrcidNormalizer.scala:31-52, 272-402; RejectRecord.scala:58-120 (abstracts are out of scope per the manifest) |
| J3 identifier normalization; keys; duplicate and conflict counters | V | ExactIdJob.scala:74-166, 237-325 (CONFLICT through RejectReason.Conflict) |
| J4 bounded blocks; pathological groups | V | BlockingJob.scala:339-401; Pipeline.scala:509-561; BlockingKeys.scala |
| J5 join, deterministic features, MatchCandidate | V | CandidateJob.scala:196-592; MatchFeatures.scala; LocalPipelineSuite:521 |
| J6 team, author-paper, n(n-1)/2 pairs, positions, provenance, oversize | V | TeamJob.scala:43-75, 180-232; PairExpansionSuite; GiantTeamsSuite:57-137 |
| J7 partitioned exports | V | job-contracts.md:289-294; Pipeline.scala:99-111 |
| Ops: the ten counters | V | Counters.scala:13-37; Pipeline.scala:335-338; RejectRecord.scala:11-29 |
| Ops: explicit partitioner | V | Shuffle.scala:141-166; Pipeline.scala:375 |
| Ops: idempotent run/snapshot paths | V | Pipeline.scala:122-136, 273-292, 308-356 |
| Ops: no shared mutable state | V | task-local fields only (for example TeamJob.scala:138-145) |
| Ops: intermediate compression | V | Pipeline.scala:365-366, 378-382 |
| Ops: determinism | V (tests); passing R | LocalPipelineSuite:378-387; UnicodeSuite:219-234; AdversarialXmlSuite:271-280 |
| Ops: unit tests (parsers, pairs, normalization, keys) | V | hadoop and entity-resolution suites (listed in 8 and 9) |
| Ops: local integration tests | V | Harness.scala:91-98 (LocalJobRunner); LocalPipelineSuite |
| Ops: EMR smoke script by infra | V (authored); execution M by design | scripts/run-bounded-pipeline.sh |
| "Run sbt check and the local bounded pipeline" | R | marker; the `sbt localPipeline` alias is still inactive (B10, C8), and the pipeline runs inside `sbt check` |
| Gate: at least two real MapReduce jobs | V code; R execution | Pipeline.scala:361-385, 425-668 |
| Gate: record boundaries | V (residual documented) | as J1 |
| Gate: pairs for 1, 2, 3 and larger teams | V tests; R passing | PairExpansionSuite:63-150; GiantTeamsSuite |
| Gate: counters and rejects visible | V code; R values | Pipeline.scala:698-711; RejectSink |
| Gate: repeated runs equivalent | V tests; R passing | LocalPipelineSuite:378-387; UnicodeSuite:210-217 |
| Gate: sample provenance | V | Outcome.scala:54-64; Harness.scala:300-340; LocalPipelineSuite:204, 230, 289-290 |
| Gate: adversarial categories | V | tests/** (see 9) |
| Gate: reviewer APPROVE | pending B1 | |

#### 2. Closure of review 1 and review 2 items

- **H1 closed.**
  - No suppression: TeamJob.scala:200-230.
  - Parameter removed: JobParams.scala:230-255.
  - Tests: ShuffleAndParamsSuite:131-136 and LocalPipelineSuite:397-444.
- **H2 closed.** Both entities in each round: BlockingJob.scala:139-161; Pipeline.scala:505-561.
- **H3 closed.** DblpMappingSuite:108, 125.
- **H4 closed.**
  - DUPLICATE and CONFLICT counted: LocalPipelineSuite:446-483; DuplicateIdsSuite:75-108.
  - Residual: every run uses LocalJobRunner.
- **H5 closed.**
  - `CandidateBlock.build` with derived provenance: BlockingJob.scala:163-190, 424-433.
  - `BlockRecord` and `hadoop.block.v1` are gone (Grep).
- **H6 closed.** Pipeline.scala:348-353, 369-370, 718-730; job-contracts.md:111-116 and 253.
- **C1-C6 closed** (core contracts, adopted in hadoop).
- **C7 delivered** (the script).
- **C8 open.** Alias inactive; needs build-engineer and a factory-engineer decision. Not a blocker.
- **C9 delivered.**
- **Tasks (a)-(j):**
  - (a) closed: TeamJob.scala:52-61.
  - (b) closed: Collaboration.scala:87-120 has only the positional `of`; Grep finds no bridge.
  - (c) closed.
  - (d) closed: CandidateJob.scala:154-187, 521-592.
  - (e) closed.
  - (f) closed: Grep finds no `upickle.default.read` of a core record in hadoop main; only hadoop-internal types are read that way.
  - (g) closed: Outcome.scala:127-140; DblpMapping.scala:244-245.
  - (h) closed except B1 and the job-contracts.md:299-301 nit.
  - (i) closed.
  - (j) closed: OrcidNormalizer.scala:123-134, 308-340.

#### 3. Complete pair generation: verified

- Grep finds no pair suppression in code, parameters, docs or tests. The only `skip-pairs` mentions are a refusal test (LocalPipelineSuite:429-444) and a "removed" note (job-contracts.md:100-102).
- `PairExpansion.iterator` yields exactly i<j for all n (TeamJob.scala:46-62). TeamReducer checks `emitted == n(n-1)/2` and throws otherwise (226-229).
- Positions: the core contract requires each position to reproduce its MentionId (Collaboration.scala:124-141).
- Oversize teams are counted and flagged with "all pairs emitted" (200-214). `maxTeamSize` and `maxPairExpansion` are the true values (198-199).
- Tests that prove it:
  - GiantTeamsSuite:57-96 (0, 1, 2, 3, 100, 101 and 1000 authors; 509,504 distinct pairs; positions match mention IDs).
  - GiantTeamsSuite:113-137 (OVERSIZE_TEAM=2; stats 1000 / 499,500).
  - PairExpansionSuite:63-150.

#### 4. Source provenance: verified

- Dataset records carry dataset, record ID, snapshot, run ID, source schema and `policyVersion=hadoop-transform.v2` (Outcome.scala:54-64).
- Pairs and teams inherit the work's provenance (TeamJob.scala:60, 73).
- Blocks and candidates carry derived provenance (see the nit above).
- Rejects carry dataset, record ID, snapshot, run ID and format version (RejectRecord.scala:58-111).
- IdentityLinks carry the asserting record's provenance and `assertionSource` (ExactIdJob.scala:76-98, 115-166). The core rule `assertedBy == provenance.sourceDataset` was verified in review 2.
- ORCID source-work-id:
  - Always carries its issuer: `SourceLocalWorkId(issuerKind, issuerId, value)` (SourceIdentity.scala:138-237).
  - The issuer must equal `assertionSource`, and the list is ORCID-only (Bibliographic.scala:243-249).
  - It is never an `ExactId`, so it never reaches `additionalIds`, links or agreements (ExactIdJob.scala:116-118; MatchFeatures.scala:209-210).
  - It is read only from the summary's own external IDs, not group-level ones (OrcidNormalizer.scala:123-134).

#### 5. XML split limitation

- **Design is correct by reading.**
  - Every construct is bounded to W+9 bytes (measure, 361-451).
  - Lexing starts at `start-(W+16)` in the outside-markup state (86-90, 547-552).
  - The pre-start token stream equals the whole-file stream, including records interrupted or overflowed before the split start.
  - The ownership rules for records and unclosed-markup reports are consistent (259, 341).
- **The residual case is real:**
  - It happens when the lookback start falls inside a construct whose tail holds an opener look-alike (for example `<?` inside a comment, which is valid XML) with a closer within W.
  - The split reader then mis-lexes and can hide a real record start (data loss) or expose a ghost.
  - It is documented in code (177-180) and in job-contracts.md:178-180, and not tested, by design.
- **Classification:** not a Phase 4 blocker. It is a required check before AWS execution (item 1 above). The unsupported "real dump has none" sentence is B1.

#### 6. Zero-width names

See O-ZW: verified from code that these characters split tokens. Minor, owner entity-resolution-engineer, not a blocker.

#### 7. Stale documentation

- B1 (job-contracts.md:180-181).
- job-contracts.md:299-301 (bridge phrasing).
- WorkRecordV2Suite.scala:39 and `v2Fields`.
- UnicodeSuite:161 name overclaims.
- No remaining mention of `skip-pairs` as a feature, `BlockRecord`/`hadoop.block.v1`, a current v1/v2 record, or the old reader. Contract.scala:13-21 lists v1 and v2 correctly as history, and retires them at 50-53.

#### 8. Entity-resolution functions (first review)

- **Pure and locale-independent.** Folding uses `Character.toLowerCase(int)` (TextFolding.scala:77). No default-locale case mapping, clock, randomness or hash-order dependence in main (Grep). Turkish-locale test: BlockingKeysSuite:317.
- **No Phase 5 leakage.** Features only, with no score, threshold or decision (MatchFeatures.scala:78-147). The core MatchCandidate key set forbids them.
- **Keys nest and are bounded:**
  - At most 4 levels, each level = previous + `|` + component (BlockingKeys.scala:101-145).
  - Components cannot contain `|`, a tab or control characters (folded output, years, `-`, `*`, institution IDs).
  - Hadoop flags invalid keys before using them in the tab-separated shuffle key (BlockingJob.scala:129-130).
- **Features** are symmetric by construction (Levenshtein, Jaccard, LCS, max over the variant product) and finite (Similarity, 0 for empty inputs).
- **Tests are hand-computed and pinned, not tautological** (MatchFeaturesSuite:111-399; BlockingKeysSuite:31-329).
- **Defect found:** O-ZW.

#### 9. Adversarial tests (tests/**)

- They drive the real `Pipeline.run` on LocalJobRunner (Harness.scala:91-98, 173-192).
- Imports are main classes of core, hadoop and entity-resolution only. No hadoop test code is used. Outputs go under `tests/target/test-work`.
- All six categories are present: AdversarialXml, Unicode, MissingFields, DuplicateIds, CommonNames, GiantTeams.
- Assertions are concrete: counters, rejects, locators and excerpts, n(n-1)/2, split-size determinism.
- Runtime is bounded: munit timeouts plus `secs < 600` (GiantTeamsSuite:136).
- Weak spot: UnicodeSuite:161-165 (O-ZW).

#### 10. Smoke script

- **Verified by reading:**
  - `set -euo pipefail` (202). No `eval` or `source` (Grep). The config is parsed with strict regexes (403-420).
  - No hardcoded bucket, account, ARN or endpoint. Only ratifiable caps and instance types.
  - Dry-run and preflight exit before any `aws` call (809-812, 891-898).
  - Only add-steps, cancel-steps of its own step, and write-once put-object go through the wrappers (926-942). Nothing terminates, scales or deletes in AWS; the only `rm` is on its own temp files.
  - The token binds HEAD, all file hashes (the config hash covers every config value) and the bounds (827-834).
  - Preflight markup scan with refuse-if-present (753-757).
  - Clean-tree gate, ratified-environment gate (383-395) and jar ancestry gate (648-657).
- **Agent reports only:** `bash -n` passed. shellcheck was not run.
- **Not verified:** the API assumptions in check 4, the IAM gap in check 3, and the trap window (optional item).

#### 11. Packaging evidence (build-engineer work order 6)

All of it is inference from sbt logs and classpaths. No jar was listed. See the table below.

#### 12. Ownership: no violations

- The session-start git status lists changes only under `modules/core/**`, `modules/entity-resolution/**`, `modules/hadoop/**` (including docs), `tests/**`, `scripts/run-bounded-pipeline.sh` and `docs/agent-runs/phase-04/`. Each is its owner's territory.
- Glob finds no `*.sbt` under modules or tests, and nothing new under infra.
- Imports follow the module graph: hadoop imports only core and entity-resolution; entity-resolution and core import nothing internal (Grep).
- The evidence files under `docs/agent-runs/phase-04/` are faithful to the reports and reviews they record.

---

### Directly verified vs reported by agents vs packaging inferences

| Directly verified (read code and tests) | Reported by agents (needs a run) | Packaging inferences |
|---|---|---|
| Scanner design, ownership and sync rules, residual case | `sbt check` 407/0 (marker corroborates indirectly) | Hadoop jar contains PipelineDriver, mappers and reducers, core and entity-resolution classes (from the runtime classpath) |
| Complete n(n-1)/2 pairs, positions, oversize flagging, true stats | Observed counters (for example EMITTED_PAIR=509,504; local run counters) | Main-Class = PipelineDriver (discovered main, manifest not read) |
| Provenance on every curated type; ORCID source-work-id issuer rules | LocalJobRunner runtimes (12.9 s, 16.8 s) | Typesafe Config 1.4.3 bundled, not relocated |
| No pair suppression anywhere; bridge removed | `bash -n` passed; shellcheck not run | Hadoop and Spark jars excluded (Provided) |
| Entity-resolution purity, nesting, symmetry, no Phase 5 leakage; ZWJ splitting | Determinism and idempotency tests passing | Spark jar holds core plus Scala 2.13 and 3 libraries, no application classes |
| Script fail-closed gates, no AWS in dry-run, no delete/terminate/scale | `sbt packageJobs` success, merge lines | EMR runtime compatibility (not shown) |
| Ownership by path; module import graph | | |

---

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 4, work order 7 of 7: final read-only review of the full Phase 4 change set (hadoop, entity-resolution, core v3 and bridge removal, tests/**, scripts/run-bounded-pipeline.sh, packaging evidence, phase evidence)
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (Read, Grep and Glob only)
TEST RESULTS: not run (no Bash). .claude/.last-test-run reads suites=32, tests_passed=407, tests_failed=0, tests_errored=0, git_head=8c679ad, git_worktree=dirty, which matches the repair-01 report. Indirect evidence only.
VALIDATION RESULTS: every finding checked against file lines; traceability, closure, pair generation, provenance, XML reader, zero-width folding, stale docs, entity-resolution, adversarial tests, script, packaging and ownership assessed above
ASSUMPTIONS: the session-start git status snapshot is the change set; README.md at d23d307 is the specification; Hadoop RunJar Main-Class behaviour is from general knowledge (check 2, unverified here)
RISKS: the residual DBLP split case if the full dump contains body markup; EMR step argument handling with a manifest Main-Class; operator IAM 403 on head-object; Typesafe Config and Scala library conflicts on EMR; giant-team reducer skew at scale
MANUAL ACTION REQUIRED: human: chmod +x scripts/run-bounded-pipeline.sh before committing; install and run shellcheck; ratify the script's hard caps, the operator (G6) and the environments before any AWS use; decide C8 (localPipeline alias)
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: hadoop-engineer fixes B1 (job-contracts.md:180-181; optionally the 299-301 nit); code-reviewer re-checks that one line, and APPROVE is expected; then human acceptance of Phase 4
VERDICT: REQUEST_CHANGES
FINDINGS: B1 major (hadoop-engineer): modules/hadoop/docs/job-contracts.md:180-181 asserts without evidence that the real DBLP dump has no comments/CDATA/PIs, contrary to the human's instruction 7. Optional: O-ZW minor (entity-resolution-engineer) TextFolding.scala:70-81 turns Cf format characters (ZWJ, ZWNJ, ZWSP, soft hyphen, word joiner) into spaces and splits family keys; UnicodeSuite:161-165 does not assert this (test-engineer, nit); job-contracts.md:299-301 stale bridge wording (hadoop-engineer, nit); WorkRecordV2Suite.scala:39 and Bibliographic.scala:198 stale v2 names (domain-engineer, nit); run-bounded-pipeline.sh:1157-1175 trap window and 911-924 lock-before-trap (infra-engineer, minor); DblpMapping.scala:69-70 malformed non-publication records only counted as skipped (hadoop-engineer, nit); unbounded key length (entity-resolution-engineer, nit). Required checks before AWS: full-dump markup scan; EMR MainClass versus manifest Main-Class; operator IAM for head-object; AWS CLI/API field and option assumptions; shellcheck and executable bit; cost bound semantics; Typesafe Config; Scala libraries in the Spark jar; giant-team scale.
UNVERIFIED CONCERNS: Hadoop RunJar passing the class name as args[0] when the manifest has Main-Class (from Hadoop knowledge, not readable here); actual jar contents and manifest; whether any real DBLP field element is named like a record type; the DBLP DTD entity set; ORCID gzip and small-file handling; EMR behaviour of S3 fs.create(overwrite=false) and exists() on prefixes
```

## code-reviewer, re-check after repair 02 (verbatim)

**Phase 4 re-check after repair 02: APPROVE.** B1 is closed, the bridge-wording nit is closed, and nothing else blocks acceptance. I found two documentation nits; neither blocks.

**1. B1 is closed.** I searched `job-contracts.md` for "no comments", "real DBLP", "dump has", "in its body", "the dump", "real dump" and "dblp dump". The only hit is line 208, which says "the full dump has not been downloaded". That is an honest statement of missing evidence. The other statements about the snapshot are now qualified:
- **Line 85 (encoding):** UNVERIFIED, citing the manifest's `format.encoding`.
- **Lines 159-162 (record nesting and field names):** taken from `dblp.dtd` and "not verified against the bytes of the pinned snapshot". This matches `datasets/dataset-manifest.yaml:84`.
- **Lines 206-211 (body markup):** UNVERIFIED. This matches manifest lines 74 and 86 and the sample, which begins with a comment.

No other unevidenced claim about the snapshot remains.

**2. The reader limitation section is accurate against the code.** It is at `job-contracts.md:186-211`, checked against `DblpInputFormat.scala`.
- Limitation 1 matches the code: lookback is `W + OpenerSlack(16)` (lines 83-90), and the residual case is described at lines 177-180. The effects listed (a lost record, a ghost work, a reject report that appears or disappears) are inferred from the design and labelled as such in repair-02. The section states that no test covers the case.
- Limitation 2 matches the markup-bound rule at lines 152-157.
- Limitation 3 records that the real input is uncharacterized.
- Those regions of `DblpInputFormat.scala` are as I cited them in the final review. I saw no change there.

**3. The required-verification paragraph is accurate.** It is at `job-contracts.md:213-230`, checked against `scripts/run-bounded-pipeline.sh`. All of its claims hold:
- `--preflight-scan` exists (lines 22 and 341) and the 64 MiB hard cap is real (line 221).
- The five reported counts match the scanner's output (lines 726-728).
- Only `run_profile=smoke` is allowed (line 429).
- `MARKUP_TOTAL` at line 753 adds comments, CDATA, PIs and entity declarations but leaves out `SCAN_doctype_declarations`. So `refuse-if-present` does not refuse an input whose only markup is a DOCTYPE. True.
- The script prints "SCAN note=heuristic line scanner…" (line 748). True.

I classify the DOCTYPE gap (owner infra-engineer) as a check before any AWS run, not a Phase 4 blocker:
- The script runs only bounded smoke inputs and already reports the DOCTYPE count.
- The doc now requires a human decision for any non-zero count, DOCTYPE included.
- Entity declarations inside an internal subset are already gated.
- The reader lexes DOCTYPE and internal subsets explicitly, and `DblpSynchronizationSuite` covers that case.
- The fix belongs in `scripts/**`, not in the Phase 4 Hadoop deliverable.

**4. The bridge wording is correct.** `job-contracts.md:345-349` now names only the 8-argument builder. `Collaboration.scala:93-102` has the only `def of(` in that file, with the signature (x, positionX, y, positionY, workId, year, teamSize, provenance). `TeamJob.scala:52-61` calls exactly that. Nothing implies the 6-argument version still exists.

**5. Qualifiers E5-E8 are consistent.** The lines 152-153 and 178-180 determinism claims now point to the limitation section. Line 111 is about reducer determinism, and lines 376-377 describe what the tests cover; neither contradicts the limitation.

**6. The repair stayed in scope.** The `DblpInputFormat.scala` regions I spot-checked match my final-review citations, and repair-02 reports that only `.md` was edited. I cannot diff an untracked directory, so this rests on reading plus the agent's report.

**7. The evidence records the deferrals correctly.** The repair-02 table (lines 12-16) matches your instruction 10 (prompt.md:175):
- O-ZW is a prerequisite before Phase 5, owned by entity-resolution-engineer.
- The UnicodeSuite test gap is paired with it (test-engineer).
- The other optional items stay deferred.
- The pre-AWS checks are open, including the DOCTYPE gap (infra-engineer).

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 4, re-check after repair 02 (narrow): final-review blocker B1 and the bridge-wording nit in modules/hadoop/docs/job-contracts.md
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (Read, Grep, Glob only)
TEST RESULTS: not applicable (repair 02 is documentation only; no sbt check needed or run)
VALIDATION RESULTS: B1 closed: grep for snapshot-property phrases finds only line 208, an honest "not downloaded"; lines 85, 159-162 and 206-211 are qualified against datasets/dataset-manifest.yaml:74,84,86. Limitation text (186-211) matches DblpInputFormat.scala:83-90,152-157,173-180. Required-verification paragraph (213-230) matches run-bounded-pipeline.sh:22,221,341,429,726-728,748,753-757 (MARKUP_TOTAL leaves out doctype_declarations; scanner described as heuristic). Bridge wording (345-349) matches Collaboration.scala:93-102 (only `def of(` in that file) and TeamJob.scala:52-61. E5-E8 consistent. Deferred-items table in repair-02.md:12-16 matches prompt.md:175.
ASSUMPTIONS: repair 02 touched only job-contracts.md, per the agent's report and my spot-check of DblpInputFormat.scala; the untracked directory cannot be diffed by reading
RISKS: residual split-vs-whole-file divergence and over-long markup handling remain in code, untested; no full-snapshot markup scan exists; DOCTYPE-only input is not refused by refuse-if-present; the scanner is heuristic
MANUAL ACTION REQUIRED: human: before any full-snapshot or non-smoke run, assign an owner for a full-snapshot markup scan, run it, and record a decision for any non-zero count; decide whether infra-engineer should gate doctype_declarations; complete the remaining pre-AWS checks from the final review (chmod +x the script, shellcheck, MainClass vs manifest, IAM, CLI assumptions, cost-bound ratification); track O-ZW before Phase 5
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human Phase 4 acceptance, commit, and commit.txt
VERDICT: APPROVE
FINDINGS: none blocking. N1 nit (hadoop-engineer): job-contracts.md:182-184, the sentence "External entities and DTDs are never loaded." is followed by the redundant "The external DTD (`dblp.dtd`) is never read."; it should be merged. N2 nit (hadoop-engineer): job-contracts.md:200-201, limitation 2 names comment, CDATA and PI but not the `<! >` declaration kind, which the same markup-bound rule covers (DblpInputFormat.scala:152-157). Carried forward, not a Phase 4 blocker (infra-engineer): run-bounded-pipeline.sh:753, MARKUP_TOTAL leaves out doctype_declarations, so refuse-if-present passes DOCTYPE-only input; a check before AWS execution.
UNVERIFIED CONCERNS: the effects of limitation 1 (lost or ghost record, report that appears or disappears) are inferred from the design and not shown by any test; the DblpInputFormat.scala:159-161 Scaladoc still states the DTD assumption without the doc's qualifier (code comment, out of scope for this re-check)
```
