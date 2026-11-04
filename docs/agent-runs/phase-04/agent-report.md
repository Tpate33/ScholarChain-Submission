# Phase 4 agent reports

## hadoop-engineer, run 1 (final report, verbatim)

Phase 4 is partly done. The DBLP parser, all four source normalizers, team and pair extraction, the exact-identifier counters, the blocking machinery, rejects, counters, idempotency and the local bounded pipeline are built and tested, and `sbt check` passes (215 tests). Job 5 (candidate features and `MatchCandidate`) is not built. Parts of jobs 2, 3 and 6 also stop short, because modules/core has no contract for some records and entity-resolution has not supplied blocking keys or match features. So the status is BLOCKED-ON domain-engineer and entity-resolution-engineer.

Job 2 shortfall: OpenAlex author profiles and ORCID holder names are rejected or flagged as `no-target-contract`.
Job 3 shortfall: it emits counters and conflict flags, but no identity-link records.
Job 6 shortfall: pair records lack author positions.

**Job graph (all real MapReduce, run on Hadoop's LocalJobRunner in tests)**
- `normalize` reads every source in one job (MultipleInputs). It writes works, mentions, venues, institutions, topics and rejects.
- `exact-ids`, `teams` and `blocking` each read `normalize/works` and are independent of each other.
- `blocking` runs one job per round (`round-<r>`); round r+1 reads round r's spill output.
- Job 7 (export) is not a separate job: the `normalize/*` and `teams/*` outputs are the partitioned, compressed JSON Lines files Spark reads.
- The full contract for every mapper, reducer, combiner and partitioner is in modules/hadoop/docs/job-contracts.md.

Key/value contracts:

| Stage | Input | Map output (key → value) | Combiner | Reducer | Reducer count |
|---|---|---|---|---|---|
| normalize | DblpInputFormat (key `<path>@<offset>`, value DblpRawRecord); LocatedTextInputFormat for JSON Lines; WholeFileInputFormat for ORCID | `<kind>\t<id>` → envelope `<rank>\t<copies>\t<conflict>\t<contentHash>\t<json>` | EnvelopeCombiner (associative, commutative) | CanonicalRecordReducer | `scholarchain.reducers.normalize` (3 in test) |
| exact-ids | `normalize/works` | `doi\|arxiv\|orcid\|openalex-author\t<value>` → `<source>\t<entityId>\t<workId>` | none | ExactIdReducer | `scholarchain.reducers.exactIds` (2) |
| teams | `normalize/works` | workId → work JSON | none | TeamReducer: PublicationTeam v1 + n(n-1)/2 CoauthorPairCandidate v1 | `scholarchain.reducers.teams` (2) |
| blocking | works (round 0) or spill (round r) | `<level>\t<key>` → BlockMember JSON | none | BlockReducer: holds at most maxBlockSize+1 members; groups within the limit become one block; larger groups spill to the next fallback key, or are dropped or fail the job by policy | `scholarchain.reducers.blocking` (2) |

- **Partitioner:** `StableHashPartitioner` everywhere (FNV-1a over the key bytes, modulo reducer count).
- **Codecs:** map output uses `scholarchain.codec.intermediate` (DefaultCodec in the test); final output uses `scholarchain.codec.output` (GzipCodec in the test).
- **Configuration:** every parameter comes from the run manifest's parameters, so changing one changes the run ID. Nothing has a default, and `*` means "no restriction". DBLP record types, year range and venue list are all parameters; no venue subset is built in.
- **Idempotency:** a stage with `_SUCCESS` is skipped. A stage directory without `_SUCCESS` is never overwritten; the run must be repeated with attempt+1.
- **Output layout:** `<curated-root>/hadoop/snapshot=<dblp snapshot>/run=<runId hex>/{_run-manifest.json, normalize/{works,mentions,venues,institutions,topics,rejects}, exact-ids/{rejects,stats}, teams/{teams,pairs,rejects,stats}, blocking/round-<r>/{blocks,spill,rejects,stats}}`, plus `_SUCCESS` and `_counters.json` in each stage.
- **Rejects:** versioned format `scholarchain.hadoop.reject.v1`, written to `<stage>/rejects/part-{m,r}-NNNNN.gz`, with disposition `rejected` or `flagged`.

**Observed local pipeline run** (the integration suite LocalPipelineSuite, inside `sbt check`, over every committed sample; DBLP was read through 7 splits of 512 bytes)
- Run root: modules/hadoop/target/test-work/local-pipeline/curated-a/hadoop/snapshot=dblp-2026-10-03/run=aeff671d4aee9442fcfd771aef83e872d0483b43112e8430e6e49f19fd783cd2
- **normalize:**
  - INPUT=15 (dblp 4, openalex-works 3, openalex-authors 1, openalex-institutions 1, crossref-works 3, orcid-records 3); ACCEPTED=14.
  - REJECTED=1 (no-target-contract); FLAGGED=3 (no-target-contract); UNSUPPORTED=4.
  - Emitted: works 13, mentions 99, venues 6, institutions 4, topics 3 (EMITTED=125).
  - MERGED_REFERENCE=27 (institution 25, venue 2).
  - DUPLICATE, CONFLICT, MISSING_KEY, SCHEMA_FAILURE, OVERSIZE_TEAM, OVERSIZE_BLOCK and EMITTED_PAIR all 0.
- **exact-ids:**
  - INPUT=13, ACCEPTED=13 (dblp 4, openalex 3, crossref 3, orcid 3).
  - EXACT_ID_KEY=40 (doi 3, orcid 10, openalex-author 27); EXACT_ID_CROSS_SOURCE=7 (doi 3, orcid 4); CONFLICT=0.
  - Largest group: doi 6 (doi:10.1145/3447741), orcid 2, openalex-author 1.
- **teams:**
  - INPUT=13, ACCEPTED=4, TEAM_SOURCE_EXCLUDED=9 (openalex 3, crossref 3, orcid 3).
  - EMITTED.teams=4, EMITTED_PAIR=364 (325+36+3+0), OVERSIZE_TEAM=0.
  - **Maximum observed team size is 26 and the largest pair expansion is 325**, both from work:dblp:journals/tocs/CorbettDEFFFGGHHHKKLLMMNQRRSSTWW13.
- **blocking (test fixture keys only, not a blocking policy):**
  - INPUT=91 (works 13, spill-round-0 78), ACCEPTED=99.
  - OVERSIZE_BLOCK=7, BLOCK_MEMBERS_REFINED=78, BLOCK_EMITTED=37 (level-0 8, level-1 29), FLAGGED=7.
  - **Largest observed blocking group is 13 (key `0:j`); the largest emitted block is 4.**
- **Rejected records:**
  - normalize/rejects: 1 rejected (the OpenAlex author A5113870699) and 3 flagged (one per ORCID file).
  - No rejects in exact-ids or teams.
  - blocking: 7 flagged oversize-block records.
- **Second run, team max size 20 with skip-pairs:** OVERSIZE_TEAM=1, PAIRS_SUPPRESSED=325, EMITTED_PAIR=39, and one flagged record for the Spanner work.
- **Third run, blocking with one key level and the drop policy:** BLOCK_MEMBERS_DROPPED=78, OVERSIZE_BLOCK=7, BLOCK_EMITTED=8. With the fail policy the job fails, as intended.
- **Repeat and independent runs:** re-running the same manifest skipped all 4 stages and left outputs unchanged. A second independent run produced line-identical outputs. The spill intermediate was compared after sorting.

**Decision for you: the `localPipeline` alias.** It is not needed to run the pipeline: the full local pipeline already runs inside `sbt check` and `sbt hadoop/test`. If you want a dedicated alias for evidence, it is build-engineer's job (the B10 alias) and then factory-engineer's to activate the allowlist row. I did not attempt it.

**Not verified: Typesafe Config conflict in the EMR jar.** I saw no conflict on the local test classpath, but that is not the job jar. `sbt hadoop/assembly` was refused by the allowlist, so build-engineer's `sbt packageJobs` and the EMR smoke run must check it.

```text
STATUS: BLOCKED-ON
OWNER: hadoop-engineer
WORK ORDER: Phase 4 – ScholarChain Hadoop MapReduce pipeline (docs/agent-runs/phase-04/prompt.md)
FILES CREATED:
- modules/hadoop/docs/job-contracts.md
- modules/hadoop/src/main/scala/scholarchain/hadoop/{Counters,JobParams,RejectRecord,Shuffle}.scala
- modules/hadoop/src/main/scala/scholarchain/hadoop/dblp/{DblpInputFormat,DblpMapping,DblpParser}.scala
- modules/hadoop/src/main/scala/scholarchain/hadoop/io/InputFormats.scala
- modules/hadoop/src/main/scala/scholarchain/hadoop/jobs/{BlockingJob,ExactIdJob,NormalizeJob,TeamJob}.scala
- modules/hadoop/src/main/scala/scholarchain/hadoop/normalize/{JsonSources,OrcidNormalizer,Outcome}.scala
- modules/hadoop/src/main/scala/scholarchain/hadoop/pipeline/{Pipeline,PipelineDriver}.scala
- modules/hadoop/src/test/scala/scholarchain/hadoop/{ShuffleAndParamsSuite,TestSupport}.scala
- modules/hadoop/src/test/scala/scholarchain/hadoop/dblp/{DblpMappingSuite,DblpScannerSuite}.scala
- modules/hadoop/src/test/scala/scholarchain/hadoop/jobs/PairExpansionSuite.scala
- modules/hadoop/src/test/scala/scholarchain/hadoop/normalize/SourceNormalizersSuite.scala
- modules/hadoop/src/test/scala/scholarchain/hadoop/pipeline/LocalPipelineSuite.scala
FILES MODIFIED: none
COMMANDS RUN:
- git status --short, git ls-files, git ls-files --others --exclude-standard -- modules/hadoop
- sbt hadoop/compile: failed first (4 type errors on MultipleOutputs), then succeeded
- sbt hadoop/test: 41 passed
- sbt check: success
- sbt hadoop/assembly: refused by guard-dangerous.sh (no allowlist entry); nothing ran
TEST RESULTS:
- sbt check output: "Passed: Total 174, Failed 0, Errors 0, Passed 174" (core) and "Passed: Total 41, Failed 0, Errors 0, Passed 41" (hadoop); other projects 0.
- markTestRun: "suites=16, passed=215, skipped=0", git_head=8c679ad, worktree=dirty.
VALIDATION RESULTS:
- Local bounded pipeline: 4 stages ran with the counters above. Every work and pair line decodes and validates against its core contract.
- DBLP reader: correct at every byte offset, at 3-way splits around record boundaries, inside UTF-8 sequences, on gzip (1 split), and with 7 real 512-byte splits in the MapReduce run (INPUT.dblp=4).
- Pair counts n(n-1)/2 checked for 0, 1, 2, 3, 26 (325) and 200 (19900) authors.
ASSUMPTIONS:
- Entity table is ISO 8859-1 (HTML 4 Latin-1); the real dblp.dtd list is unverified, and any other entity rejects the record.
- Name and title normalization is mechanical only: NFC, whitespace collapse, lowercase, and one trailing period removed from titles.
- DBLP venue comes only from the crossref key; record keys are never parsed. Editors are not authors.
- Teams and pairs use only scholarchain.team.sources (dblp in the test), so the same paper from several sources is not counted twice.
- Pair endpoints are mention IDs; their provenance is the work's source provenance.
- Many ORCID works sharing one DOI is not a conflict.
- ORCID input must be the extracted per-record XML files.
RISKS:
- Typesafe Config conflict in the EMR jar is not verified (see above).
- WholeFileInputFormat on millions of small ORCID files will be slow; a combine input format may be needed.
- Spark could consume ORCID works (no authors) and OpenAlex/Crossref works without a policy for cross-source duplicates.
- A DBLP split that starts inside a comment containing a record tag is not handled; DBLP dumps are not known to contain such comments.
MANUAL ACTION REQUIRED:
- Human: decide the DBLP venue subset and record it in the run manifest's scholarchain.dblp.venues.
- infra-engineer: write the bounded EMR smoke-run script from the entry point and layout in job-contracts.md.
BLOCKED-ON OWNER: domain-engineer; entity-resolution-engineer
BLOCKED-ON EVIDENCE:
domain-engineer, missing core contracts:
1. CoauthorPairCandidate needs positionA: Int and positionB: Int (role law 3 and work order 6 require author positions on every pair).
2. A MatchCandidate record: schemaVersion, candidateId (hash ID kind), entityKind: MatchEntityKind, sourceId, targetId (canonical pair), blockKeys: Vector[String], blockingVersion, features: SortedMap[String, Double], exactIdMatches: Vector[ExternalId], provenance (policyVersion required).
3. A CandidateBlock record: blockKey, level, entity, keySourceVersion, size, sorted memberIds, provenance. It would replace the hadoop-internal scholarchain.hadoop.block.v1.
4. An identity-link / exact-identifier record: scheme, value, entityKind, entityId, workId, assertedBySource, provenance.
5. WorkRecord additions: topicIds: Vector[String], recordType, venueName, volume/number/pages, eeUrls/urls, and other source work IDs (OpenAlex ids).
6. A source author profile record for OpenAlex authors and the ORCID holder: source ID, names and name variants, ORCID, last known institutions, and the ORCID holder-to-work claim.
7. A field in Provenance or the work record for the ORCID work-summary assertion source (client ID and source name), which the schema note says must be preserved.
entity-resolution-engineer, pure functions needed in modules/entity-resolution (deterministic, no I/O, no randomness, no locale dependence, versioned):
- blockingKeysVersion: String
- researcherBlockingKeys(work: WorkRecord, mention: AuthorMention): Vector[String] (level 0 first, then fallbacks; at most levels)
- workBlockingKeys(work: WorkRecord): Vector[String]
- blockingLevels: Int
- matchFeatures(a: AuthorMention, aWork: WorkRecord, b: AuthorMention, bWork: WorkRecord): SortedMap[String, Double] and a work-pair equivalent (finite values)
- optionally normalizeName and normalizeTitle to replace the mechanical normalization
Hadoop wraps these through jobs.BlockingKeySource.
NEXT GATE:
1. code-reviewer reviews modules/hadoop/**.
2. domain-engineer adds contracts 1–7, and entity-resolution-engineer adds the functions above.
3. Re-run hadoop-engineer to add positions to pairs, emit identity-link and MatchCandidate records (job 5), and switch blocking to the ER keys. The gate is again sbt check plus the local pipeline.
4. build-engineer runs sbt packageJobs to check the Typesafe Config jar risk.
```

## domain-engineer, work order 1 (final report, verbatim)

STATUS: COMPLETE. All seven contracts are in modules/core, `sbt check` passes (284 tests), and modules/hadoop compiles and passes its 41 tests unchanged. Two existing wire formats were bumped: `work-record.v2` and `coauthor-pair-candidate.v2`. Both reject v1, because no v1 record was ever accepted into a curated output.

One thing needs your attention first. Hadoop calls the old 6-argument pair builder (TeamJob.scala:51-58). Pairs now require positions, and `-deprecation -Werror` rules out a `@deprecated` overload. So I kept that call shape as an undecorated **transitional bridge**. It recovers each mention's position by recomputing `MentionId.of(workId, p)` for p = 0 up to teamSize, which is O(teamSize) SHA-256 per pair. Hadoop's 200-author test now takes 2.49 s. For a team of about 1,000 authors this is roughly 3×10^8 hashes, a few minutes in one reducer. Hadoop work order 3 must switch to the positional builder, and core should then delete the bridge. Its output is valid v2 data, so hadoop's pairs already carry correct positions.

### Contracts

**C3: CoauthorPairCandidate** (`scholarchain.coauthor-pair-candidate.v2`)
- **Fields:** schemaVersion, endpointA, endpointB, positionA: Int, positionB: Int, workId, year: Option[Int], teamSize: Int, provenance.
- **Validation:**
  - Each position is non-negative and below teamSize, and the two positions differ.
  - Positions follow their endpoints: positionA belongs to endpointA. Canonical order is by endpoint ID, not by position, so positionA > positionB is normal.
  - For mention endpoints, `MentionId.of(workId, position)` must equal the endpoint.
- **Builders:**
  - `of(x, posX, y, posY, workId, year, teamSize, prov)`: orders the pair canonically and swaps the positions with their endpoints.
  - The 6-argument transitional bridge described above.
- **Tests:** PairPositionSuite (7 tests). SchemaVersionSuite: "coauthor-pair-candidate.v1 JSON (no positions) is rejected". Pair goldens in WireContractSuite.

**C1: CandidateBlock** (`scholarchain.candidate-block.v1`)
- **Fields:**
  - schemaVersion, blockId, entityKind (researcher | work)
  - blockingVersion (token), level: Int (0 or more), blockKey (non-blank, no control characters)
  - maxBlockSize (2 or more), size (equals memberIds.size, at least 1, at most maxBlockSize)
  - memberIds: sorted by UTF-8 bytes, distinct. Mention or source-author IDs for researcher blocks, work IDs for work blocks.
  - sourceDatasets: sorted by tag, distinct, datasets only, must include every source a member ID names.
  - provenance: must be `scholarchain-derived`. This also resolves review finding H5 (blocks had no full provenance).
- **ID:** `block:` + SHA-256(netstrings("scholarchain.candidate-block.v1", entityKind, blockingVersion, level, blockKey, runId)).
- **Builder:** `CandidateBlock.build(...)` sorts the members and sources and sets size and ID.
- **Tests:** CandidateContractSuite (5 block tests). StableIdSuite: "candidate block ID is SHA-256 of the documented netstring encoding".

**C1: MatchCandidate** (`scholarchain.match-candidate.v1`)
- **Fields:**
  - schemaVersion, candidateId, entityKind (researcher | work)
  - sourceIdA < sourceIdB (canonical pair), with sourceDatasetA and sourceDatasetB. Each dataset must agree with the source the ID names, if it names one; mention IDs name none.
  - blockKeys: Vector[BlockKey(level, key)], non-empty, sorted by (level, key), distinct.
  - blockingVersion and featureVersion (tokens).
  - features: SortedMap[String, Double] with token keys and finite values.
  - exactIdAgreements: Vector[ExactId], of the candidate's entity kind, sorted, distinct.
  - provenance: `scholarchain-derived`.
- **No score, threshold, confidence or decision.** A test pins the exact key set.
- **ID:** `mcand:` + SHA-256(netstrings("scholarchain.match-candidate.v1", entityKind, first, second, blockingVersion, featureVersion, runId)).
- **Tests:** CandidateContractSuite (9 candidate tests). StableIdSuite: "match candidate ID is SHA-256 of the documented netstring encoding (canonical pair)" and "new hash IDs change with every documented input".

**C4: IdentityLink** (`scholarchain.identity-link.v1`)
- **Fields:** schemaVersion, linkId, identifier: ExactId, entityKind, entityId, workId: Option, assertedBy: SourceType, assertionSource: Option[AssertionSource], provenance.
- **Validation:**
  - The identifier's scheme must identify the entity kind (doi, arxiv, dblp-key, openalex-work, mag, scopus-eid identify works; orcid, openalex-author, dblp-pid identify researchers).
  - Work links: entityId is a work ID in the asserting source's namespace, and workId is null.
  - Researcher links on a mention: workId is required.
  - Researcher links on a source-author profile: the profile must belong to assertedBy, and workId is optional (used for a claimed work).
  - assertedBy must equal provenance.sourceDataset. assertionSource is allowed only when assertedBy is ORCID.
- **ID:** `idlink:` + SHA-256(netstrings("scholarchain.identity-link.v1", scheme, value, entityKind, entityId, workId or "", assertedBy, runId)).
- **Tests:** SourceIdentitySuite (6 link tests). StableIdSuite: "identity link ID is SHA-256 of the documented netstring encoding".

**C5: WorkRecord** (`scholarchain.work-record.v2`)
- **New fields**, placed after provenance so hadoop's 13-argument positional call still compiles:
  - recordType: Option (token)
  - venueName, volume, issue, pages: Option (non-blank, no control characters)
  - eeLinks, urls: Vector (no whitespace, distinct, kept in source order)
  - topicIds: Vector (topic IDs, distinct)
  - additionalIds: Vector[ExactId] (work schemes only, sorted, distinct, never repeating `doi`, `arxivId` or the work's own ID; a second, different DOI is kept)
  - assertionSource: Option (ORCID works only)
- **Defaults:** the new fields have empty Scala defaults, but they are always written to JSON (`@serializeDefaults(true)`). `Json.decode` rejects a supported-version object that lacks any of them, through a new `RecordContract.fieldsWithDefaults`.
- **Tests:** WorkRecordV2Suite (9 tests). SchemaVersionSuite: "work-record.v1 JSON is rejected, and v1-shaped JSON labelled v2 lacks required keys". Three WorkRecord goldens (DBLP, ORCID, OpenAlex).

**C6: SourceAuthorProfile** (`scholarchain.source-author-profile.v1`)
- **Fields:** schemaVersion, profileId, source, sourceRecordId, displayName, givenNames, familyName, nameVariants, orcid, externalIds, institutionIds, claimedWorkIds, provenance.
- **Validation:**
  - profileId must equal `SourceAuthorId.of(namespace of source, sourceRecordId)`. Only openalex, orcid and dblp-pid sources have profiles.
  - An ORCID profile must carry the holder's iD.
  - claimedWorkIds is allowed only for ORCID. Each entry must be `work:orcid-work:<holder>/work/<put-code>`, sorted by UTF-8 bytes.
  - Names are optional, so a private ORCID record is valid. nameVariants, externalIds and institutionIds are distinct.
- **New ID kind:** `source-author:<openalex|orcid|dblp-pid>:<id>`.
- **Tests:** SourceIdentitySuite (4 profile tests). StableIdSuite: "source author profile IDs are namespace plus the source's canonical author ID".

**Item 7: ORCID assertion source**
- **Value object:** `AssertionSource(sourceKind: client|orcid, sourceId, sourceName, originKind, originId, originName)`.
- **Where it lives:** a field on WorkRecord and on IdentityLink, not in Provenance. It is a fact about the ORCID item. Provenance is shared by all 24 contracts, so adding it there would change and re-version every wire format.
- **Tests:** "ORCID assertion source: kinds, IDs, and origin are validated", plus the WorkRecord and IdentityLink tests above.

### Other decisions
- **New identifier enum.** I added `ExactIdScheme` (9 tags) instead of extending `ExternalIdScheme`. Extending it would change the frozen contract of 4 existing records without a version bump.
- **Frozen goldens kept stable.** The test fixture `derivedProv` now uses the literal `"scholarchain.work-record.v1"`. Otherwise the WorkRecord bump would silently change 8 unrelated frozen goldens.
- **Pinned IDs.** Each new ID is pinned twice: once against a hand-written netstring encoding, and once against the literal value observed on the first test run. The blockId is `block:5e4eb700…13d6`, the candidateId is `mcand:400ad937…d300`, and the linkIds are `idlink:91a46459…5dbe` (mention link) and `idlink:24c2c226…f04e` (ORCID claim link).
- **Pair canonicalization rule** is unchanged: first < second by unsigned UTF-8 bytes. Configuration precedence is unchanged.
- **Sample record:** `{"schemaVersion":"scholarchain.coauthor-pair-candidate.v2","endpointA":"mention:191f…","endpointB":"mention:4d99…","positionA":1,"positionB":0,"workId":"work:dblp:journals/example/Synthetic21","year":2021,"teamSize":2,"provenance":{…}}`. Every other new record has a literal golden in WireContractSuite.scala.

### Migration notes for hadoop-engineer (work order 3)
1. **Pairs:** TeamJob.scala:51-58 should call `CoauthorPairCandidate.of(ms(i).mentionId, i, ms(j).mentionId, j, w.workId, w.year, n, w.provenance)`. Core then removes the 6-argument bridge.
2. **DBLP works** (DblpMapping.scala:172):
   - recordType = element name, venueName = journal or booktitle (so articles keep their venue even with venueId None).
   - volume, issue (DBLP `number`), pages.
   - eeLinks = every `ee`, urls = `url`.
   - additionalIds = any extra distinct DOIs instead of dropping them.
   - Update DblpMappingSuite:76 if it asserts loss.
3. **OpenAlex works** (JsonSources.scala:280): recordType = `type`, venueName, biblio volume/issue/pages, topicIds (primary topic first), additionalIds = `ids.mag` via `ExactId.of(ExactIdScheme.Mag, …)`, sorted with `ExactId.canonical`. **Crossref works** (JsonSources.scala:403): recordType, venueName = container-title[0], volume/issue/page.
4. **ORCID works** (OrcidNormalizer.scala:202): recordType = work:type, venueName = journal-title, additionalIds = `eid` as scopus-eid, assertionSource from `common:source` and `assertion-origin-*`.
5. **OpenAlex authors** (JsonSources.scala:137-150) and **ORCID holders** (OrcidNormalizer.scala:135-145): emit `SourceAuthorProfile` instead of rejecting or flagging as `no-target-contract`. ORCID claimedWorkIds = the holder's orcid-work IDs, sorted.
6. **ExactIdJob:** emit `IdentityLink.build(...)` per asserted identifier, with the work's or profile's provenance.
7. **BlockingJob:** replace `BlockRecord` / `scholarchain.hadoop.block.v1` with `CandidateBlock.build(...)` using `scholarchain-derived` provenance. Job 5 emits `MatchCandidate.build(...)`.
8. **Tests:** LocalPipelineSuite counter expectations for `no-target-contract` (lines 127, 150) will change once profiles are emitted.

### Unresolved questions
- Scopus EID (`2-s2.0-<digits>`) and MAG (digits) formats come from the observed forms in identifiers.md, not official grammars.
- `pages` for OpenAlex is documented as `<first>-<last>`; confirm this joining rule.

```text
STATUS: COMPLETE
OWNER: domain-engineer
WORK ORDER: Phase 4, work order 1 of 7 -- shared contracts in modules/core for the Phase 4 Hadoop pipeline (C1, C3, C4, C5, C6, item 7)
FILES CREATED:
- modules/core/src/main/scala/scholarchain/core/Candidates.scala
- modules/core/src/main/scala/scholarchain/core/SourceIdentity.scala
- modules/core/src/test/scala/scholarchain/core/CandidateContractSuite.scala
- modules/core/src/test/scala/scholarchain/core/PairPositionSuite.scala
- modules/core/src/test/scala/scholarchain/core/SourceIdentitySuite.scala
- modules/core/src/test/scala/scholarchain/core/WorkRecordV2Suite.scala
FILES MODIFIED:
- modules/core/src/main/scala/scholarchain/core/{Bibliographic,Collaboration,Contract,Enums,Identifiers,StableIds,ValidationError}.scala
- modules/core/src/test/scala/scholarchain/core/{EnumCodecSuite,Fixtures,SchemaVersionSuite,StableIdSuite,WireContractSuite}.scala
- (PairAndWindowSuite.scala: a temporary probe test added and removed; net unchanged per git status)
COMMANDS RUN:
- git ls-files (core, phase-04, schemas); git ls-files --others --exclude-standard; git status --porcelain
- sbt core/compile: success
- sbt Test/compile: success (hadoop main and test compile unchanged)
- sbt core/test x3: failed as intended while hash placeholders were pending (4 failures), plus one real fix (key check now runs only for a supported version)
- sbt core/testOnly scholarchain.core.StableIdSuite: refused by guard-dangerous.sh (no allowlist row); nothing ran
- sbt check: success
TEST RESULTS:
- sbt check: core "Passed: Total 243, Failed 0, Errors 0, Passed 243"; hadoop "Passed: Total 41, Failed 0, Errors 0, Passed 41"; entityResolution, rag, root, services, sparkAnalytics, storage, tests: 0
- markTestRun: "wrote .claude/.last-test-run (git_head=8c679adfac2fd5d2d62697973d2d4c326f0e813e, worktree=dirty, suites=20, passed=284, skipped=0)"
VALIDATION RESULTS:
- scalafmtCheckAll passed inside sbt check
- Every new or changed record type has a literal golden checked in both directions (encode and decode); the frozen-type set equals SchemaVersions.all (24 types)
- New ID formulas pinned to hand-written netstring encodings and literal hex values
- No file outside modules/core modified (git status)
ASSUMPTIONS:
- No v1 WorkRecord or CoauthorPairCandidate data exists outside test target/ directories, so v1 is rejected rather than migrated
- MatchCandidate and CandidateBlock cover only researcher and work entity kinds; other MatchEntityKind values need a version bump
- Researcher candidate endpoints are author mentions or source-author profiles (not resolved researcher IDs)
- Scopus EID and MAG formats follow the observed forms in datasets/schemas/identifiers.md
RISKS:
- Transitional 6-argument CoauthorPairCandidate.of costs O(teamSize) SHA-256 per pair (200-author test 2.49 s); a very large team on EMR before hadoop work order 3 lands would be slow in one reducer
- It cannot be annotated @deprecated (-deprecation -Werror), so nothing flags its use at compile time
- WorkRecord v2 fields have Scala defaults: wire strictness is enforced by Json.decode, but direct upickle.default.read[WorkRecord] (TeamJob.scala:160) bypasses the missing-key check
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer reviews the modules/core diff; then hadoop-engineer work order 3 adopts the contracts (migration notes above); afterwards domain-engineer removes the transitional 6-argument CoauthorPairCandidate.of
```

## entity-resolution-engineer, work order 2 (final report, verbatim)

Work order 2 is done: the blocking-key functions and match-feature functions are in `modules/entity-resolution`, and `sbt check` passes. Entity resolution has 56 tests, all passing, and the repository total is 340. All functions are pure, versioned, symmetric where they compare two sides, and locale-independent. They produce no scores, thresholds or decisions. Nothing outside `modules/entity-resolution/**` was written, and no core contract was missing.

### Signatures
All are in package `scholarchain.entityresolution`. The façade `object EntityResolution` uses the names hadoop-engineer asked for:
- `val blockingKeysVersion: String` = `"er-blocking.v1"` (same as `BlockingKeys.Version`)
- `val featuresVersion: String` = `"er-features.v1"` (same as `MatchFeatures.Version`; use it as `MatchCandidate.featureVersion`)
- `val blockingLevels: Int` = 4 (same as `BlockingKeys.Levels`)
- `def researcherBlockingKeys(work: WorkRecord, mention: AuthorMention): Vector[String]`
- `def profileBlockingKeys(profile: SourceAuthorProfile): Vector[String]`
- `def workBlockingKeys(work: WorkRecord): Vector[String]`
- `def matchFeatures(a: ResearcherEndpoint, b: ResearcherEndpoint): SortedMap[String, Double]`
- `def matchFeatures(a: AuthorMention, aWork: WorkRecord, b: AuthorMention, bWork: WorkRecord): SortedMap[String, Double]`
- `def workMatchFeatures(a: WorkRecord, b: WorkRecord): SortedMap[String, Double]`
- `def researcherExactIdAgreements(a: ResearcherEndpoint, b: ResearcherEndpoint): Vector[ExactId]` and `def workExactIdAgreements(a: WorkRecord, b: WorkRecord): Vector[ExactId]`. These fill `MatchCandidate.exactIdAgreements` (sorted, no duplicates).
- `def normalizeName(raw: String): String` and `def normalizeTitle(raw: String): String`. These are for display only. Hadoop does **not** need to replace its own normalization: none of my functions reads `normalizedName` or `normalizedTitle`. They read `rawName` and `title`, and a test proves this.

Supporting types:
- `enum ResearcherEndpoint { case OfMention(work, mention); case OfProfile(profile) }`, with `id` and `source`.
- For speed in job 5: `MatchFeatures.prepare(e: ResearcherEndpoint): PreparedResearcher` and `MatchFeatures.prepare(w: WorkRecord): PreparedWork`, plus `MatchFeatures.researcher(x: PreparedResearcher, y: PreparedResearcher)` and `MatchFeatures.work(x: PreparedWork, y: PreparedWork)`. A test checks that prepared and unprepared calls give the same result.

### Normalization
- Steps, in order:
  1. Unicode NFKD.
  2. Remove all combining marks.
  3. Apply an explicit transliteration table: ß→ss, æ→ae, œ→oe, ø→o, ł→l, đ/ð→d, þ→th, ı→i, ħ→h, ŧ→t.
  4. Lowercase each character with `Character.toLowerCase(int)`, which ignores the default locale.
  5. Delete apostrophes.
  6. Turn hyphens into `-` for names and into a space for titles. Every other non-letter or non-digit, in any script, becomes a space.
- Name keys also collapse the vowel pairs `ae`, `oe` and `ue` to `a`, `o` and `u`. This makes Pöpper, Poepper and Popper all `popper`.
- Name parsing:
  - The DBLP 4-digit homonym number is removed (`Christopher Frost 0001` becomes `frost`) and kept as `homonymSuffix`.
  - Jr, Sr, II, III and IV are dropped.
  - `Family, Given` comma form is recognised.
  - Particles (di, van, der, …) are joined into the family name when a given name remains before them (`Roberto Di Pietro` becomes `dipietro`).
  - A single word is a family name with no initial.
- Titles: a trailing editorial note in brackets is removed (`(Awarded Best Paper!)`), and so is the trailing period.

### Key formula per level
Separator is `|`, a missing value is `-`. Each level is the previous level plus one component, so every refined block is a subset of exactly one parent block. Components contain only folded letters and digits, a year, an institution ID, `-` or `*`, so a key never contains a tab or control character, is never blank, and never contains `|` inside a component.

**Researcher mention:**

| Level | Key | Sample |
|---|---|---|
| 0 | family + first initial | `frost\|c` |
| 1 | + first given name (vowel pairs collapsed) | `frost\|c\|christopher` |
| 2 | + coauthor anchor: smallest family key among the other authors of the same work, by UTF-8 order | `frost\|c\|christopher\|corbett` |
| 3 | + year | `frost\|c\|christopher\|corbett\|2013` |

More samples:
- Pöpper: `popper|c|christina|dipietro|2021`
- Varajão, solo author: `varajao|j|joao|-|2021`
- Dean on Bigtable: `dean|j|jeffrey|burrows|2006`
- Synthetic single name with no year: `madonna|-|-|example|-`

Every Spanner and CACM author gets identical keys at all four levels from DBLP and from Crossref. Two DBLP homonyms (0001 and 0002) share blocks; only the features tell them apart.

**Source author profile:**
- Levels 0 and 1 are as for a mention, using the ORCID given/family names, otherwise the display name.
- Level 2 is level 1 + `|*`. Profiles have no coauthors, so from level 2 down they are compared only with other profiles.
- Level 3 adds the first institution ID.
- Samples: the ORCID holder Christina/Poepper gives `popper|c|christina|*|-`. OpenAlex author A5113870699 gives `…|*|institution:openalex:I120250893`. Both share levels 0 and 1 with the DBLP Pöpper mention.

**Work:**

| Level | Key | Sample |
|---|---|---|
| 0 | first two title words that are not stopwords, joined by `_` | `spanner_googles` |
| 1 | + title words 3 and 4 | `spanner_googles\|globally_distributed` |
| 2 | + year | `spanner_googles\|globally_distributed\|2013` |
| 3 | + first author's family key | `spanner_googles\|globally_distributed\|2013\|corbett` |

DBLP and Crossref give the same keys for Spanner and for CACM. Bigtable gives `bigtable_distributed|storage_system|2006|chang`.

Affiliation, venue and source are deliberately not part of any key: DBLP has no affiliations, venue names differ between sources, and keying on source would stop cross-source candidates. They are features instead. A record with no readable name or title returns an empty vector, which Hadoop counts as MISSING_KEY.

### Features
Value ranges: a flag is 0 or 1; a similarity or Jaccard is between 0 and 1; a count or difference is a non-negative integer stored as a Double. When an input is missing on either side, the measurement is 0, so always read it together with its `*.both` flag. The exact key sets are pinned in tests as `MatchFeatures.ResearcherFeatureNames` (43 keys) and `WorkFeatureNames` (26 keys).

**Researcher features:**
- **Name:** `family_exact`, `family_key_agree` (after vowel-pair collapse), `family_sim` (normalized Levenshtein), `given_both`, `first_initial_agree`, `initials_compatible` (one set of initials is a prefix of the other), `first_given_agree`, `given_sim`, `token_jaccard`, `best_variant_sim` (best match over all name variants), `homonym_suffix_both`, `homonym_suffix_agree`.
- **Identifiers:** `id.{orcid,openalex-author,dblp-pid}.{both,agree,conflict}`. `conflict` means both sides assert the identifier with different values.
- **Context:** `same_source`, `work_both`, `same_work`, `claimed_work`.
- **Coauthors:** `coauthor.both`, `coauthor.shared` (count), `coauthor.jaccard`.
- **Year and venue:** `year.both`, `year.abs_diff`, `venue.id_both`, `venue.id_agree`, `venue.name_both`, `venue.name_jaccard`.
- **Affiliation:** `affiliation.id_{both,shared,jaccard}`, `affiliation.text_{both,jaccard}`.
- **Work:** `work.title_jaccard`, `work.position_agree`, `work.team_size_agree`, `work.doi_agree`.

**Work features:**
- **Identifiers:** `id.{doi,arxiv}.{both,agree,conflict}`; `id.other.{agree,conflict}` counts across dblp-key, openalex-work, mag and scopus-eid.
- **Title:** `title.exact`, `title.sim`, `title.token_jaccard`.
- **Year and venue:** as for researchers.
- **Authors:** `authors.both`, `count_diff`, `family_shared`, `family_jaccard`, `first_family_agree`, `sequence_exact`, `sequence_lcs_ratio`, `orcid_shared`.
- `context.same_source`.

Citation-neighbourhood features are left out because no citation dataset is selected.

Hand-computed checks in the tests:
- Spanner, DBLP vs Crossref: `venue.name_jaccard` = 1/7.
- Dean on Bigtable vs Dean on Spanner: `coauthor.shared` = 3, `coauthor.jaccard` = 3/30, `year.abs_diff` = 7, `work.title_jaccard` = 1/10.
- Spanner vs Bigtable: `family_jaccard` = 4/31, `sequence_lcs_ratio` = 3/26, `id.other.conflict` = 1.
- Pöpper vs the ORCID holder Poepper: `family_exact` = 0, `family_key_agree` = 1, `id.orcid.agree` = 1.

### Integration notes for hadoop-engineer (work order 3)
1. **Key adapter.** In `modules/hadoop`, write a class with a no-argument constructor that implements `BlockingKeySource`:
   - `version` = `EntityResolution.blockingKeysVersion`
   - `levels` = `EntityResolution.blockingLevels`
   - `researcherKeys(w, m)` = `EntityResolution.researcherBlockingKeys(w, m)`
   - `workKeys(w)` = `EntityResolution.workBlockingKeys(w)`

   Profiles need a new member path in your interface: call `profileBlockingKeys(p)` and use `memberId = p.profileId` with `source = p.source`. Keys are already nested, so `BlockingSpillMapper` keying on `keys(round)` is safe as written. A member may return fewer than 4 keys (only when it returns none).
2. **Job 5, inputs.** `BlockMember` holds only the ID. Join each member back to its record before computing features: for a mention, the full `WorkRecord` and the mention (coauthors, year, venue, title and position are all read); for a profile, the `SourceAuthorProfile`. A reduce-side join on workId is better than copying the whole team into every member.
3. **Job 5, within each emitted block:**
   - Call `MatchFeatures.prepare` once per member.
   - For each unordered pair i < j, compute `MatchFeatures.researcher(px, py)` (or `work(px, py)`) and the matching `*ExactIdAgreements`.
   - Build with `MatchCandidate.build(kind, a.id, a.source, b.id, b.source, blockKeys, blockingKeysVersion, featuresVersion, features, agreements, derivedProvenance)`.
4. **Job 5, grouping (review task d).** Group by canonical pair across all emitted blocks and levels, take the union of `BlockKey(level, key)`, and emit once. Features depend only on the two records, so every copy of a pair is identical. Do not drop same-work pairs: `context.same_work` records them, and dropping them is a Phase 5 policy choice.
5. **Fill `venueName`** on WorkRecord v2 (core migration note 2). Otherwise every `venue.name_*` feature is 0.
6. **Configuration.** Maximum block size and the spill/drop/fail policy stay in your run configuration; I set no size thresholds. The blocking-key and feature version strings belong in the run manifest and MatchCandidate records.

### Risks and known limits (no gold set yet)
These are expected false negatives and false positives, to be measured in Phase 5:
- Reversed name order (for example Chinese names, `Yi Wang` vs `Wang Yi`) splits blocks at level 0.
- An initial alone vs a full first name (`J.` vs `James`) splits at level 1.
- Deeper levels split a person across different coauthor anchors and years. That is the cost of keeping blocks bounded, and it only happens when a block is too large.
- Profiles meet mentions only at levels 0 and 1.
- Vowel-pair collapse also merges unrelated spellings (`Michael`/`Michal`), and the particle rule can misread names like `Hoang Le Minh`.
- Homonyms always share blocks; only features separate them.
- Two works share a level-0 key only if their first two non-stopword title words match.
- Feature cost: name-pair Levenshtein and author-sequence LCS are quadratic in their lengths, about 9×10^6 steps for two 3,000-author teams, so very large teams cost more per pair. Using `prepare` once per member avoids re-reading whole teams for every pair.

Gold-set evaluation does not apply in Phase 4. No decisions or thresholds exist, and there are no gold labels, so there is no confusion matrix, unresolved rate or threshold-sensitivity result.

```text
STATUS: COMPLETE
OWNER: entity-resolution-engineer
WORK ORDER: Phase 4, work order 2 of 7 -- blocking-key functions and deterministic match-feature functions for the Hadoop pipeline
FILES CREATED:
- modules/entity-resolution/src/main/scala/scholarchain/entityresolution/TextFolding.scala
- modules/entity-resolution/src/main/scala/scholarchain/entityresolution/Names.scala
- modules/entity-resolution/src/main/scala/scholarchain/entityresolution/BlockingKeys.scala
- modules/entity-resolution/src/main/scala/scholarchain/entityresolution/MatchFeatures.scala
- modules/entity-resolution/src/main/scala/scholarchain/entityresolution/EntityResolution.scala
- modules/entity-resolution/src/test/scala/scholarchain/entityresolution/Samples.scala
- modules/entity-resolution/src/test/scala/scholarchain/entityresolution/TextFoldingSuite.scala
- modules/entity-resolution/src/test/scala/scholarchain/entityresolution/NamesSuite.scala
- modules/entity-resolution/src/test/scala/scholarchain/entityresolution/BlockingKeysSuite.scala
- modules/entity-resolution/src/test/scala/scholarchain/entityresolution/MatchFeaturesSuite.scala
FILES MODIFIED: none
COMMANDS RUN:
- git status --short (twice); git ls-files --others --exclude-standard; git ls-files -- modules/entity-resolution datasets modules/core/src/main; git diff --stat
- one grep command (over 400 bytes) and one grep pipeline (contained quotes): refused by guard-dangerous.sh; nothing ran
- sbt entityResolution/compile: success
- sbt entityResolution/test: 1 failure (my feature-name list was not sorted: coauthor.* must come before context.*); fixed
- sbt check: success
TEST RESULTS:
- sbt check: core "Passed: Total 243, Failed 0, Errors 0, Passed 243"; entityResolution "Passed: Total 56, Failed 0, Errors 0, Passed 56"; hadoop "Passed: Total 41, Failed 0, Errors 0, Passed 41"; rag, root, services, sparkAnalytics, storage, tests: 0 tests each
- markTestRun: "wrote .claude/.last-test-run (git_head=8c679adfac2fd5d2d62697973d2d4c326f0e813e, worktree=dirty, suites=24, passed=340, skipped=0)"
VALIDATION RESULTS:
- scalafmtCheckAll passed inside sbt check
- Every sample and adversarial key passes core BlockKey.checkKey and CandidateBlockId.of; every key list has at most 4 entries and each level extends the previous one
- Keys are identical under a Turkish default locale and on repeated calls
- Features: symmetric over 11 researcher pairs and 7 work pairs; all values finite and in range; core MatchCandidate.build accepts a researcher candidate and a work candidate
- The sample fixtures validate against the core WorkRecord and SourceAuthorProfile contracts
- Gold-set evaluation: not applicable in Phase 4 (no decisions or thresholds, no human gold labels)
ASSUMPTIONS:
- Crossref rawName is "given family" and the DBLP rawName keeps the homonym number, as the current Hadoop normalizers produce them
- venueName will be filled by hadoop work order 3; venue name features are 0 until then
- The stopword and particle lists and the vowel-pair collapse are fixed parts of the er-blocking.v1 / er-features.v1 formulas, not thresholds
- Same-work mention pairs are kept as candidates; filtering them is a Phase 5 policy question
RISKS:
- Expected false negatives: reversed name order, an initial alone vs a full first name at level 1, and refinement at levels 2-3 splitting one person; profiles meet mentions only at levels 0-1
- Expected false positives: homonyms share blocks, vowel-pair collapse merges unrelated spellings, the particle rule can misread some names
- Feature cost is quadratic in name and team length for title Levenshtein and author LCS (about 9e6 steps for two 3,000-author teams); prepare-once is provided
- Hadoop's BlockingKeySource has no profile method yet
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer reviews modules/entity-resolution/**; hadoop-engineer work order 3 adopts these functions (integration notes 1-6 above); Phase 5 then measures the risks above against human-labelled gold sets
```

## domain-engineer, work order 1b: ORCID source-work-id (final report, verbatim)

### Summary

I added the ORCID `source-work-id` to `WorkRecord` as a new value type, `SourceLocalWorkId`. Every entry carries the submitting source that issued it, so the value can never be read without that source. It is not an `ExactId`, so it cannot enter `additionalIds`, `dedicatedIds`, exact-identifier agreement or `IdentityLink`. Adding the key changes the bytes of the frozen WorkRecord examples, so I bumped the schema to `scholarchain.work-record.v3` and retired v2. `sbt check` passes (exact counts below). modules/hadoop and modules/entity-resolution are unchanged and still compile and pass. I wrote only under `modules/core/**`.

**What was added**
- **New type** (`SourceIdentity.scala`), strict decoding (unknown keys rejected):
  `final case class SourceLocalWorkId(issuerKind: AssertionSourceKind, issuerId: String, value: String)`
  - Ordering: by issuer kind tag, then issuer ID, then value, all by UTF-8 bytes.
  - Helpers: `compare`, `canonical`, `issuedBy(assertion, value)`, `check`, `checkSet`.
- **New WorkRecord field** (`Bibliographic.scala`), last position, Scala default empty, so existing constructor calls still compile:
  `sourceLocalWorkIds: Vector[SourceLocalWorkId] = Vector.empty`
  - The key is always written and must be present on decode (new `WorkRecord.V3Fields` and `WorkRecord.DefaultedFields`; `V2Fields` is kept unchanged so callers still compile).
- **No new `ExactIdScheme` or `ExternalIdScheme` tag.** That is deliberate: a scheme tag would make the value look like a global identifier.
- `AssertionSource.checkId` is now `private[core]` so the new type can reuse it.

**Validation rules** (field path `sourceLocalWorkIds`)
1. A null list is reported as `MissingField`.
2. A non-empty list is allowed only when `source` is ORCID ("only allowed for ORCID works").
3. A non-empty list requires `assertionSource` ("requires assertionSource (the issuing source)").
4. Each entry's issuer must equal the work's submitting source, `assertionSource.sourceKind` and `.sourceId`. The assertion origin and any other client are rejected ("issuer differs from assertionSource (sourceKind, sourceId)").
5. `issuerKind` must be present. `issuerId` follows the existing rules: a checksum-valid bare ORCID iD for kind `orcid`, non-empty printable ASCII for kind `client`.
6. `value` must be non-blank, without control characters, and without surrounding whitespace. Inner spaces are allowed (for example "Pure 12345").
7. The list must be strictly ascending by issuer kind, issuer ID and value, with no duplicates.

**Schema version decision: bump to v3.** My rule (and law 6) is that any change to a frozen example's bytes needs a bump. Adding this key changes all three WorkRecord examples, even when the list is empty. v2 is added to `SchemaVersions.retired`, and v2 JSON is rejected as `UnsupportedSchemaVersion`. This costs nothing in practice: v2 was never committed and never accepted into a curated output. Hadoop and entity-resolution use the `SchemaVersions.WorkRecord` constant, so they are unaffected.

The candidate-block and match-candidate examples still hold the literal provenance pointer `"scholarchain.work-record.v2"` (as the `derivedProv` fixture already did for v1), so their bytes and versions are unchanged.

**Two-way JSON example** (WireContractSuite, `OrcidWorkGolden`; the test is now named "WorkRecord (ORCID, assertion source, source-work-id)"). It ends:
`..."assertionSource":{"sourceKind":"client","sourceId":"APP-EXAMPLE000000001",...},"sourceLocalWorkIds":[{"issuerKind":"client","issuerId":"APP-EXAMPLE000000001","value":"00000000-0000-4000-8000-000000000001"}]}`
The DBLP and OpenAlex examples now start with `"schemaVersion":"scholarchain.work-record.v3"` and end with `"sourceLocalWorkIds":[]`.

**Entity-resolution check (read only, nothing to route).** In `MatchFeatures.scala`, `workExactIds` (line 209) uses `WorkRecord.dedicatedIds(w) ++ w.additionalIds`, and `exactIdAgreements`, `schemeCompare` and `OtherWorkIdSchemes` (lines 158-232) work only on `ExactId`. The new field has a different type and is never read there, so no agreement, conflict or `id.other.*` feature can come from it. `IdentityLink.identifier` is also an `ExactId`, so a local ID cannot be put in a link.

**Migration note for hadoop-engineer (OrcidNormalizer):** for each `work:work-summary`, take only the summary's own `common:external-ids` entries with type `source-work-id` (not the `activities:group` ones), build `SourceLocalWorkId.issuedBy(assertionSource, normalizedValue)`, pass them through `SourceLocalWorkId.canonical` to sort and dedupe, and set `assertionSource` from `common:source`; never put them in `additionalIds`.

```text
STATUS: COMPLETE
OWNER: domain-engineer
WORK ORDER: Phase 4, work order 1b -- preserve ORCID source-work-id with its submitting-source context (review O3, human decision)
FILES CREATED:
  modules/core/src/test/scala/scholarchain/core/SourceLocalWorkIdSuite.scala
FILES MODIFIED:
  modules/core/src/main/scala/scholarchain/core/SourceIdentity.scala (SourceLocalWorkId; AssertionSource.checkId now private[core])
  modules/core/src/main/scala/scholarchain/core/Bibliographic.scala (WorkRecord.sourceLocalWorkIds, V3Fields, DefaultedFields, validation, docs)
  modules/core/src/main/scala/scholarchain/core/Contract.scala (work-record.v3; v2 retired; version history)
  modules/core/src/test/scala/scholarchain/core/Fixtures.scala (localWorkId; orcidWork carries it)
  modules/core/src/test/scala/scholarchain/core/WireContractSuite.scala (three WorkRecord examples now v3 with the new key)
  modules/core/src/test/scala/scholarchain/core/WorkRecordV2Suite.scala (DefaultedFields; legacy tail; assertion-source test clears local IDs)
  modules/core/src/test/scala/scholarchain/core/SchemaVersionSuite.scala (v3 constant; v1 test; new v2-rejection test)
COMMANDS RUN:
  git status --short; git diff --stat; git ls-files (ORCID samples; untracked hadoop and entity-resolution files)
  sbt check (1st run: failed, 1 failing core test: WorkRecordV2Suite "the assertion source is allowed only on ORCID works and is validated"; the old fixture assumption was wrong, test updated)
  sbt check (2nd run: success)
  git status --porcelain
TEST RESULTS (2nd sbt check, observed):
  core: Passed: Total 252, Failed 0, Errors 0
  entityResolution: Passed: Total 56, Failed 0, Errors 0
  hadoop: Passed: Total 41, Failed 0, Errors 0
  rag, root, services, sparkAnalytics, storage, tests: Total 0 (no tests)
  markTestRun: wrote .claude/.last-test-run (git_head=8c679adfac2fd5d2d62697973d2d4c326f0e813e, worktree=dirty, suites=25, passed=349, skipped=0)
  [success] Total time: 27 s
  New tests (9):
    SourceLocalWorkIdSuite: "an ORCID work keeps its source-work-id qualified by the submitting source"; "the wire form of a source-work-id always carries its issuer"; "a source-work-id is not an exact identifier and never a dedicated or additional ID"; "two holders' copies of one client's work share no exact identifier through it"; "the issuer must be the submitting source, not another client or the assertion origin"; "a source-work-id requires the assertion source and an ORCID work"; "issuer IDs and values are validated"; "a work's source-work-ids are sorted and distinct"
    SchemaVersionSuite: "work-record.v2 JSON is rejected, and v2-shaped JSON labelled v3 lacks sourceLocalWorkIds"
  Fails without the change: the new suite and the ORCID example do not compile without the type and field, and the v3 examples and v2-rejection test fail on v2 bytes.
VALIDATION RESULTS: scalafmt check passed; build-source guard and version ledger checks passed; all three WorkRecord examples encode and decode both ways; MatchFeatures.scala read and confirmed it does not read the new field as an identifier (lines 158-232).
ASSUMPTIONS:
  - The issuer of a source-work-id is the work summary's common:source (the submitting client or iD holder), not the assertion origin. In sample 0000-0001-6899-0651 the Scopus summary with an assertion origin has no source-work-id; the NYU Scholars summary has one.
  - Only work-summary-level source-work-ids belong to a WorkRecord. Group-level external-ids merge several summaries and do not say which source issued them.
  - Values may contain inner spaces (client-defined); the samples show UUIDs.
  - The bump to v3 is required by my versioning rule even though v2 was never committed.
RISKS:
  - Until hadoop-engineer adopts the field, OrcidNormalizer still writes an empty list (valid v3), so the ID is still lost in pipeline output.
  - If hadoop takes group-level source-work-ids, or attaches them to a summary from another source, validation rejects the whole work only when the issuer does not match assertionSource. A wrong attribution with a matching issuer cannot be detected in core.
  - The existing WorkRecordV2Suite test name "a v1-era constructor call ... valid v2 record" now builds a v3 record (cosmetic; not renamed, to keep the scope small).
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer focused review of this delta (SourceIdentity.scala SourceLocalWorkId, Bibliographic.scala WorkRecord v3, Contract.scala, the 4 test files); then hadoop-engineer (work order 3) adopts sourceLocalWorkIds in OrcidNormalizer per the migration note; integration task (b) (bridge removal) remains open, blocked on hadoop task (a).
```

## hadoop-engineer, work order 3 (final report, verbatim)

I wrote the remaining Phase 4 Hadoop work. `sbt check` passes with 367 tests (core 252, entityResolution 56, hadoop 59), and the bounded local pipeline runs all five stages over every committed sample with the real entity-resolution keys and features.

Everything is in `modules/hadoop/**` only: `git status` shows the same paths as at session start. Nothing ran on AWS and nothing was committed.

### 1. Checklist

| Item | Status | Evidence |
|---|---|---|
| H1 / (c) no pair suppression | DONE | `skip-pairs`, its parameter, its counter and its test are gone. `TeamJob.scala:200-229` emits every n(n-1)/2 pair and checks the count. Oversize teams increment OVERSIZE_TEAM and get a `flagged` reject. `maxPairExpansion` is now n(n-1)/2 for every team. Tests: "oversize teams: counted and flagged, and ALL their pairs are still emitted" (threshold 20: OVERSIZE_TEAM=1, EMITTED_PAIR=364, the Spanner work emits 325 pairs) and "a removed pair-suppression parameter is refused". |
| H2 both entities in one run | DONE | One blocking stage blocks researchers (mentions and profiles) and works under one run ID, writing `blocks/<entity>` and `members/<entity>`. Normalize is not re-run (`Pipeline.runBlocking`, `BlockingJob.scala` seed mappers). |
| H3 DBLP tests | DONE | `DblpMappingSuite`: "a repeated author name keeps both mentions…" and "a record missing optional fields (year, ee, url, venue, volume, pages) is accepted". |
| H4 DUPLICATE/CONFLICT at MapReduce level | DONE | Test "same DBLP key twice…": DUPLICATE=2, CONFLICT=1, plus one `conflict` reject with disposition `flagged`. |
| H5 / (e) `CandidateBlock.build` | DONE | `BlockRecord` / `hadoop.block.v1` is removed. `BlockMember` now carries `source` and `recordKey`. `BlockReducer.emitBlock` builds a core `CandidateBlock` with member source datasets and full derived provenance (`BlockKeys.provenance`). |
| H6 counters survive a crash | DONE | Job committers no longer write `_SUCCESS`. The driver writes `_counters.json`, then `_SUCCESS` (`Pipeline.scala:348-353`, `newJob` line 369+). A stage with `_SUCCESS` but no counters is refused. Test: "a driver stop between the jobs and the commit leaves no _SUCCESS; reruns are refused". The docs now say spill is unsorted. Blocking INPUT has one unit: blocking members. |
| (a) / (f) positional builder and `Json.decode` | DONE | `TeamJob.scala:52-61` uses `of(m_i, i, m_j, j, …)`. `TeamReducer` and `CanonicalRecordReducer` now use `Json.decode`; every core record read in hadoop goes through it. |
| (d) Job 5 | DONE | `CandidateJob.scala` runs three MapReduce jobs: join, pairs, final. The pairs job calls `MatchFeatures.prepare` once per member, then `researcher`/`work` and `exactIdAgreements` per pair. The final job emits one `MatchCandidate` per candidate ID with the sorted union of its `BlockKey`s. Same-work pairs are kept; there are no scores or decisions. Per-block pair work is bounded by maxBlockSize and documented. |
| (g) bad ee/url | DONE | `Build.links` drops and flags invalid values and removes duplicates. Test: "a bad ee or url is dropped and flagged…". |
| (h) docs | DONE | `modules/hadoop/docs/job-contracts.md` is rewritten: every job including job 5, record versions, counters, reject reasons, paths. `skip-pairs` is removed. |
| (i) EMR cost note | DONE | "Cost notes" section in `job-contracts.md`. A 1000-author test produces 499,500 pairs in 2.65 s and has a 60 s guard. |
| (j) ORCID source-work-id | DONE | `OrcidNormalizer.work` reads only the summary's own external IDs and uses `issuedBy` then `canonical`. Never written to `additionalIds`. Test: the Scopus summary takes no ID from its group. |
| Core migration notes 2-6 | DONE | All v3 fields for DBLP, OpenAlex, Crossref and ORCID. OpenAlex and ORCID records now become `SourceAuthorProfile`s, so there are no more `no-target-contract` rejects or flags. `ExactIdJob` emits `IdentityLink`s for works, mentions, ORCID claims and profiles. |
| Entity-resolution integration | DONE | `EntityResolutionKeySource` (no-argument) plus a `profileKeys` member path. The driver refuses a run unless `scholarchain.blocking.keySourceVersion` and `scholarchain.candidates.featureVersion` (new manifest parameters) match the code. The local pipeline uses the real keys; the fixture key sources are only used for the drop/fail plumbing test. |

### 2. Job graph and key/value contracts

Partitioner is `StableHashPartitioner` (FNV-1a) everywhere. Map output is compressed with `codec.intermediate` (DefaultCodec in the test). All outputs are compressed with `codec.output` (GzipCodec in the test). Reducer counts used in the test are in brackets.

| Stage / job | Map output (key → value) | Combiner | Reducer → outputs |
|---|---|---|---|
| normalize (3) | `<kind>\t<id>` → envelope | EnvelopeCombiner | CanonicalRecordReducer → works, mentions, profiles, venues, institutions, topics, rejects |
| exact-ids (2) | `<scheme>\t<value>` → source, entity, work, IdentityLink JSON | none | ExactIdReducer → links, rejects, stats |
| teams (2) | workId → work JSON | none | TeamReducer → teams, pairs, rejects, stats |
| blocking round r (2 each, up to 4 rounds) | `<entity>\t<level>\t<key>` → BlockMember | none | BlockReducer → blocks/<entity>, members/<entity>, spill, rejects, stats |
| candidates/join (2) | recordKey → `M`/`W`/`P` + JSON | none | JoinReducer → joined |
| candidates/pairs (2) | blockId → JoinedMember | none | PairFeatureReducer → pairs |
| candidates/final (2) | candidateId → MatchCandidate | none | CandidateConsolidateReducer → final/<entity> |

### 3. Counters from the local pipeline

- **normalize:**
  - INPUT=15 (dblp 4), ACCEPTED=15, REJECTED=0, FLAGGED=0.
  - DUPLICATE=0, CONFLICT=0, MISSING_KEY=0, SCHEMA_FAILURE=0, MERGED_REFERENCE=27.
  - EMITTED: works 13, mentions 99, profiles 4, venues 6, institutions 4, topics 3.
- **exact-ids:**
  - INPUT=17 (13 works, 4 profiles), ACCEPTED=17, CONFLICT=0.
  - EMITTED.links=74, EXACT_ID_KEY=51: doi 3, dblp-key 4, openalex-work 3, mag 3, scopus-eid 1, orcid 10, openalex-author 27.
  - EXACT_ID_CROSS_SOURCE=7 (doi 3, orcid 4).
- **teams:** INPUT=13, ACCEPTED=4, TEAM_SOURCE_EXCLUDED=9, EMITTED.teams=4, EMITTED_PAIR=364, OVERSIZE_TEAM=0.
- **blocking:**
  - INPUT=116 (103 researcher, 13 work), ACCEPTED=116, MISSING_KEY=0.
  - OVERSIZE_BLOCK=5, BLOCK_MEMBERS_REFINED=28, REFINE_INPUT=28, dropped=0.
  - BLOCK_EMITTED=42: researcher 34 at level 0 and 2 at level 2; work 4 at level 0 and 2 at level 3.
- **candidates:**
  - INPUT=116, ACCEPTED=116, MISSING_KEY=0, CONFLICT=0.
  - CANDIDATE_PAIRS=119 (109 researcher, 10 work), EMITTED: 109 researcher and 10 work candidates.
- **Every stage:** EMITTED_PAIR, OVERSIZE_*, DUPLICATE and SCHEMA_FAILURE are 0 unless listed above.
- **Rejects in the main run:** 5, all `blocking/round-0/rejects` (`oversize-block`, flagged). The other stages wrote none.
- **Other test runs:**
  - Oversize-team run: 1 `oversize-team` flag.
  - Duplicate-key run: 1 `conflict` flag.
  - Fixture drop run: 9 oversize-block flags, 3 missing-key flags (the ORCID profiles have no display name for the fixture), and 91 members dropped.

### 4. Output layout and examples

Run root: `modules/hadoop/target/test-work/local-pipeline/curated-a/hadoop/snapshot=dblp-2026-10-03/run=1053ff9dd391c25e38efdd991bb0032cee06d55efa197fffdaf15b96b8372fe8/`

```text
_run-manifest.json
normalize/{works,mentions,profiles,venues,institutions,topics,rejects}
exact-ids/{links,rejects,stats}
teams/{teams,pairs,rejects,stats}
blocking/round-<r>/{blocks/<entity>,members/<entity>,spill,rejects,stats}
candidates/{join,pairs,final/<entity>}
each stage: _counters.json, then _SUCCESS
```

Example lines from the run (provenance trimmed):
- **WorkRecord, DBLP:** `{"schemaVersion":"scholarchain.work-record.v3","workId":"work:dblp:journals/cacm/PopperMP21",…,"recordType":"article","venueName":"Commun. ACM","volume":"64","issue":"4","pages":"96-101","eeLinks":["https://doi.org/10.1145/3447741"],…}`
- **WorkRecord, ORCID:** `{…"workId":"work:orcid-work:0000-0002-2814-962X/work/94866473",…"additionalIds":[{"scheme":"scopus-eid","value":"2-s2.0-85103197452"}],"assertionSource":{"sourceKind":"client","sourceId":"APP-VOZZVWHV1K15COEN","sourceName":"New York University Scholars",…},"sourceLocalWorkIds":[{"issuerKind":"client","issuerId":"APP-VOZZVWHV1K15COEN","value":"06b25b01-8677-4af5-b139-0de6dd3c6eb9"}]}`
- **AuthorMention:** `{"schemaVersion":"scholarchain.author-mention.v1","mentionId":"mention:a73322a5…","workId":"work:dblp:journals/tocs/CorbettDEFFFGGHHHKKLLMMNQRRSSTWW13","position":0,"rawName":"James C. Corbett",…}`
- **PublicationTeam:** `{"schemaVersion":"scholarchain.publication-team.v1","workId":"work:dblp:journals/cacm/PopperMP21","year":2021,"teamSize":3,"unresolvedMentionIds":["mention:e0778a8f…","mention:2dd92580…","mention:b6da813f…"],…}`
- **CoauthorPairCandidate:** `{"schemaVersion":"scholarchain.coauthor-pair-candidate.v2","endpointA":"mention:2dd92580…","endpointB":"mention:e0778a8f…","positionA":1,"positionB":0,"workId":"work:dblp:journals/cacm/PopperMP21","year":2021,"teamSize":3,…}`
- **IdentityLink, mention:** `{…"identifier":{"scheme":"openalex-author","value":"A5002433369"},"entityKind":"researcher","entityId":"mention:e59a577f…","workId":"work:openalex:W3137759927","assertedBy":"openalex",…}`
- **IdentityLink, ORCID claim:** `{…"identifier":{"scheme":"orcid","value":"0000-0002-2814-962X"},"entityId":"source-author:orcid:0000-0002-2814-962X","workId":"work:orcid-work:0000-0002-2814-962X/work/94866473","assertedBy":"orcid","assertionSource":{"sourceKind":"client",…}}`
- **SourceAuthorProfile, OpenAlex:** `{…"profileId":"source-author:openalex:A5113870699","displayName":"Christina Pöpper","nameVariants":[6 variants],"orcid":"0000-0002-2814-962X","institutionIds":["institution:openalex:I120250893"],"claimedWorkIds":[],…}`
- **SourceAuthorProfile, ORCID:** `{…"profileId":"source-author:orcid:0000-0001-6899-0651","givenNames":"Michail","familyName":"Maniatakos","nameVariants":["Mihalis Maniatakos"],"claimedWorkIds":["…/work/107628996","…/work/94865737"],…}`
- **CandidateBlock, researcher:** `{"schemaVersion":"scholarchain.candidate-block.v1",…,"entityKind":"researcher","blockingVersion":"er-blocking.v1","level":0,"blockKey":"burrows|m","maxBlockSize":4,"size":1,…,"sourceDatasets":["dblp"],"provenance":{"sourceDataset":"scholarchain-derived","sourceRecordId":"researcher:0:burrows|m",…}}`
- **CandidateBlock, work:** `{…"entityKind":"work","level":0,"blockKey":"bigtable_distributed","memberIds":["work:dblp:conf/osdi/ChangDGHWBCFG06"],…}`
- **MatchCandidate, researcher:** `{…"sourceIdA":"mention:26b6edff…","sourceIdB":"mention:2dd92580…","sourceDatasetA":"openalex","sourceDatasetB":"dblp","blockKeys":[{"level":0,"key":"maniatakos|m"}],"featureVersion":"er-features.v1","features":{…43 keys…},"exactIdAgreements":[{"scheme":"orcid","value":"0000-0001-6899-0651"}],…}`
- **MatchCandidate, work:** `{…"sourceIdA":"work:doi:10.1145/3447741","sourceIdB":"work:openalex:W3147033813","blockKeys":[{"level":3,"key":"cyber_security|research_arab|2021|popper"}],…,"exactIdAgreements":[{"scheme":"doi","value":"10.1145/3447741"}]}`
- **MatchCandidate with several block keys:** none occurs in the sample run. The consolidation test produces `"blockKeys":[{"level":0,"key":"a"},{"level":1,"key":"b|key"}]`.
- **Reject:** `{"schemaVersion":"scholarchain.hadoop.reject.v1",…,"reason":"oversize-block","disposition":"flagged","locator":"researcher:0:popper|c","detail":["entity=researcher","level=0","size=5","maxBlockSize=4","refinedToNextLevel=5","dropped=0","policy=drop"]}`

### 5. Sizes
- **Teams and pairs:** maximum team size is 26 and the largest pair expansion is 325, both from the Spanner work. Total pairs: 364.
- **Largest observed blocking group:** researcher 5 (`popper|c`, level 0); work 6 (`cyber_security`, level 0).
- **Largest emitted block:** researcher 4 (`dean|j`); work 3 (`software_development`).
- **MatchCandidates:** 119 in total. 0 have more than one block key.

That is structural, not a bug. The entity-resolution keys nest, so each member lands in exactly one emitted block per entity, and a pair can share only one block. The consolidation step is therefore a guarantee for future key formulas, and it is tested with synthetic input.

### 6. The six-argument bridge
The only `CoauthorPairCandidate.of` call in `modules/hadoop` is the 8-argument positional builder at `TeamJob.scala:52-61`. No test calls `of`. I confirmed this by reading or writing every hadoop file except `dblp/DblpInputFormat.scala` and `dblp/DblpScannerSuite.scala`, which I did not open (they are scanner code and unchanged). I had no Grep tool, so a reviewer Grep is a cheap confirmation before domain-engineer removes the bridge.

### 7. Still incomplete for Phase 4
- **domain-engineer:** remove the six-argument bridge and its tests (task b). Optional items O2 and O4 remain deferred.
- **code-reviewer:** final Phase 4 review covering `modules/hadoop`, entity-resolution and the O3 change.
- **test-engineer:** adversarial tests (C9).
- **infra-engineer:** bounded EMR smoke-run script (C7). It should use `--stages normalize,exact-ids,teams,blocking,candidates`.
- **build-engineer:** the `localPipeline` alias (C8, then factory-engineer activates the allowlist row) and the job-jar Typesafe Config check via `sbt packageJobs`.
- **Human:** the DBLP venue subset decision.
- **Not measured:** giant-team performance (reduce-side expansion; the join copies a work's JSON once per mention), the DBLP entity-set coverage, and ORCID small-file scale.

```text
STATUS: COMPLETE
OWNER: hadoop-engineer
WORK ORDER: Phase 4, work order 3 of 7 -- H1-H6, integration tasks (a), (c)-(j), core migration notes 2-6, entity-resolution integration, job 5
FILES CREATED:
- modules/hadoop/src/main/scala/scholarchain/hadoop/jobs/CandidateJob.scala
FILES MODIFIED (all under modules/hadoop, untracked since run 1):
- docs/job-contracts.md
- src/main/scala/scholarchain/hadoop/{Counters,JobParams,RejectRecord,Shuffle}.scala
- src/main/scala/scholarchain/hadoop/dblp/DblpMapping.scala
- src/main/scala/scholarchain/hadoop/jobs/{BlockingJob,ExactIdJob,NormalizeJob,TeamJob}.scala
- src/main/scala/scholarchain/hadoop/normalize/{JsonSources,OrcidNormalizer,Outcome}.scala
- src/main/scala/scholarchain/hadoop/pipeline/{Pipeline,PipelineDriver}.scala
- src/test/scala/scholarchain/hadoop/{TestSupport,ShuffleAndParamsSuite}.scala
- src/test/scala/scholarchain/hadoop/dblp/DblpMappingSuite.scala
- src/test/scala/scholarchain/hadoop/jobs/PairExpansionSuite.scala
- src/test/scala/scholarchain/hadoop/normalize/SourceNormalizersSuite.scala
- src/test/scala/scholarchain/hadoop/pipeline/LocalPipelineSuite.scala
COMMANDS RUN:
- git status --short (twice)
- git ls-files (twice)
- python3 --version: refused by guard-dangerous.sh (no allowlist entry); nothing ran
- sbt hadoop/compile: success
- sbt hadoop/test: Passed 59, Failed 0
- sbt check: success
TEST RESULTS:
- sbt check: core "Passed: Total 252, Failed 0, Errors 0"; entityResolution "Passed: Total 56"; hadoop "Passed: Total 59, Failed 0, Errors 0"; rag, root, services, sparkAnalytics, storage, tests: 0
- markTestRun: "wrote .claude/.last-test-run (git_head=8c679adfac2fd5d2d62697973d2d4c326f0e813e, worktree=dirty, suites=25, passed=367, skipped=0)"
VALIDATION RESULTS:
- Bounded local pipeline (LocalPipelineSuite inside sbt check): 5 stages, about 12 MapReduce jobs on LocalJobRunner, 7 DBLP splits, real entity-resolution keys and features
- Every curated line decodes with Json.decode
- Repeated run skipped all 5 stages with unchanged outputs; an independent second run was line-identical (spill compared after sorting)
- scalafmt check passed
ASSUMPTIONS:
- maxBlockSize 4, team threshold 100 and reducer counts are test values, not production choices
- Block and candidate provenance uses the DBLP snapshot plus the run ID (the manifest lists every snapshot)
- Researcher-block sourceSchemaVersion is author-mention unless every member is a profile
- ORCID displayName comes from credit-name only
- OpenAlex pages are "<first>-<last>"
- Several DOIs in one DBLP or ORCID record are still flagged as a conflict, and the extra ones are now kept in additionalIds
RISKS:
- Giant teams concentrate n(n-1)/2 pairs in one reduce call and O(n^2) bytes in the candidate join; not measured on EMR
- Multi-block-key candidates cannot occur with the current nested keys (consolidation is exercised only by the synthetic test)
- Typesafe Config conflict in the job jar not checked
- The DBLP entity set and ORCID small-file scale are not verified
MANUAL ACTION REQUIRED: human decides the DBLP venue subset; human reviews and commits
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: domain-engineer removes the 6-argument bridge (task b); code-reviewer final Phase 4 review (hadoop plus entity-resolution and O3); test-engineer adversarial tests; infra-engineer smoke-run script; build-engineer packageJobs and localPipeline alias
```

## domain-engineer, integration task (b): bridge removal (final report, verbatim)

The six-argument `CoauthorPairCandidate.of` bridge is removed and `sbt check` passes: 367 tests, 0 failed, and all modules, including hadoop and entity-resolution, compiled without the bridge.

**Step 1: checking for callers.** I could not do the grep check you asked for. I have no Grep tool in this session, and neither `grep` nor `git grep` is on the domain-engineer Bash allowlist. I used two other checks instead:
- **Reading the files that use the type.** I read every non-core file that references `CoauthorPairCandidate`:
  - `modules/hadoop/src/main/scala/scholarchain/hadoop/jobs/TeamJob.scala:52-61` uses the 8-argument positional call only.
  - `PairExpansionSuite.scala`, `LocalPipelineSuite.scala` and `CandidateJob.scala` use the type, contract and codec but never the 6-argument call.
  - I did not open the remaining hadoop and entity-resolution files one by one. The compiler check below covers them.
- **Compiler check.** A 6-argument call cannot match the 8-argument signature, so any remaining caller would fail to compile. The first `sbt check` after the deletion compiled hadoop main and test without errors. The only failures were 8 type errors in core's own `PairAndWindowSuite.scala` (lines 70-76 and 84), which is my own module. No caller remains in modules/hadoop, modules/entity-resolution or tests/** (tests/** holds only a `.gitkeep`). Nothing outside core needed changing, so there was no reason to return BLOCKED-ON.

**Step 2: what was removed and kept.**
- **Production code:** in `modules/core/src/main/scala/scholarchain/core/Collaboration.scala` I deleted the whole TRANSITIONAL overload, old lines 122-160 (39 lines). The 8-argument builder, `positionCheck` and the contract are unchanged.
- **Tests removed** from `modules/core/src/test/scala/scholarchain/core/PairPositionSuite.scala`. Both existed only for the bridge:
  - "transitional builder recovers mention positions and equals the positional builder"
  - "transitional builder refuses endpoints that are not mentions of the work"
- **Tests added in the same file, re-expressed against the positional builder:**
  - "positional builder: argument order is irrelevant and positions reproduce MentionId.of". For every pair i<j of a 5-author work, the forward and backward calls give the same result, and `MentionId.of(workId, positionA/B)` recomputed in the test equals `endpointA/B`.
  - "positional builder refuses mentions of another work and positions at or above teamSize". A mention from another work and a position of 2 with teamSize 2 are both rejected with an `InvalidValue` on positionA or positionB.
  - The removed test's other assertion (reversed mentions give `pairCandidate`) is already covered by the first test in the suite.
- **Bridge-only case with no positional equivalent:** two researcher endpoints passed without positions. The positional builder always takes explicit positions, and the existing "researcher endpoints carry positions too" test covers that.
- **Kept unchanged:** all other positional-builder tests. That is the first two tests, "positions are validated…", "researcher endpoints…" and "JSON whose positions do not match…".
- **Additional fix the compiler found:** `modules/core/src/test/scala/scholarchain/core/PairAndWindowSuite.scala`, test "pair candidates are canonicalized by construction and validated when stored", used the bridge. I switched it to the positional builder: `(m0,0,m1,1,…)` must equal `pairCandidate`, and the self-pair `(m0,0,m0,0,…)` must be rejected. The test still checks the same things.

**Step 3: no wire change.** The `CoauthorPairCandidate` record, codec, validation and `SchemaVersions` are unchanged, so there is no version bump. I did not edit `WireContractSuite.scala` or `Fixtures.scala` in this work order; their `M` status in git comes from earlier Phase 4 work. Both `CoauthorPairCandidate` frozen wire tests (encode and decode) and the 1-pair `SchemaVersionSuite` check pass.

**Step 4:** I did no optional cleanup (O1, O2, O4, O5, O6) and did not start Phase 5.

```text
STATUS: COMPLETE
OWNER: domain-engineer
WORK ORDER: Phase 4, integration task (b): remove the temporary six-argument CoauthorPairCandidate.of bridge
FILES CREATED: none
FILES MODIFIED:
  modules/core/src/main/scala/scholarchain/core/Collaboration.scala (overload deleted, 39 lines)
  modules/core/src/test/scala/scholarchain/core/PairPositionSuite.scala (2 bridge tests replaced by 2 positional-builder tests)
  modules/core/src/test/scala/scholarchain/core/PairAndWindowSuite.scala (1 test moved to the positional builder)
COMMANDS RUN:
  git ls-files -- modules tests (exit 0)
  git ls-files --others --exclude-standard -- modules tests (exit 0)
  sbt check (exit 1: 8 compile errors, all in core PairAndWindowSuite.scala:70-84; hadoop main and test compiled)
  sbt check (exit 0, [success] Total time: 43 s)
  git status --short (exit 0)
TEST RESULTS (second sbt check, observed):
  core: Passed: Total 252, Failed 0, Errors 0, Passed 252
  entityResolution: Passed: Total 56, Failed 0, Errors 0, Passed 56
  hadoop: Passed: Total 59, Failed 0, Errors 0, Passed 59
  rag, root, services, sparkAnalytics, storage, tests: Total 0 each ("No tests to run")
  markTestRun: wrote .claude/.last-test-run (git_head=8c679adfac2fd5d2d62697973d2d4c326f0e813e, worktree=dirty, suites=25, passed=367, skipped=0)
  PairPositionSuite: 7 tests, all passed (was 7: 2 removed, 2 added)
VALIDATION RESULTS: Build sources OK; Version ledger OK (14 pinned entries); scalafmt check passed; CoauthorPairCandidate frozen wire encode/decode tests passed; "coauthor-pair-candidate.v1 JSON (no positions) is rejected" passed; no schema version changed.
ASSUMPTIONS: Compiling every module is acceptable evidence in place of a grep, which was not available in this session; overload resolution makes any remaining 6-argument call a compile error.
RISKS: Low. Grep was not run, but any call site would have failed to compile. Possible blind spots: a call in a module not compiled by sbt check (none exist), or a stale doc mention in modules/hadoop/docs/job-contracts.md, which I did not read and which is hadoop-engineer's to check.
MANUAL ACTION REQUIRED: Human review and commit as part of the Phase 4 diff.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer review of the three changed core files, then human acceptance of Phase 4
```

## infra-engineer, work order 5: bounded EMR smoke-run script (final report, verbatim)

I wrote the bounded EMR smoke-run script at `scripts/run-bounded-pipeline.sh` (about 900 lines). `bash -n` passes, but shellcheck is not installed, so lint has not been run. I did not execute the script, call AWS, touch Terraform, install anything, commit or push.

**Name.** I used `run-bounded-pipeline.sh` because `docs/agents.md` already has a row for it (inactive pending decisions B2 and G6): `scripts/run-bounded-pipeline.sh <ENV> <PATH:datasets/> <PATH>`, with `git+args`. `/run-pipeline` takes the same three arguments, and README Phase 12/13 lists this script by that name. The three-argument form matches that row.

### Usage
```
scripts/run-bounded-pipeline.sh <environment> <dataset-manifest> <run-config>
scripts/run-bounded-pipeline.sh --dry-run        <environment> <dataset-manifest> <run-config>
scripts/run-bounded-pipeline.sh --preflight-scan <environment> <dataset-manifest> <run-config>
```
- **Three-argument form:** a dry run, unless `SCHOLARCHAIN_CONFIRM_BOUNDED_RUN` holds the token that a dry run of exactly the same inputs printed. Only then does it execute. A token that doesn't match exits 3; it never falls back silently.
- **`--dry-run`:** always a dry run, even if the token is set.
- **`--preflight-scan`:** only the local scan of the DBLP input. No clean-tree or environment gate, no AWS.
- **Every mode:** must be run from the repository root. It never prompts and never uses `eval`. The config is parsed, never sourced.
- **Exit codes:** 0 ok, 2 usage or invalid input, 3 refused by a gate or bound, 4 an AWS precondition failed, 5 the step failed, was cancelled or timed out, 6 output verification failed.

### Required parameters and their checks
**Arguments:**
- `<environment>` must match `^[a-z][a-z0-9-]{1,30}$` and equal the config's `environment`. It also needs a committed `infra/terraform/environments/<env>/` directory. None exists, so every mode except `--preflight-scan` refuses today.
- `<dataset-manifest>` must be under `datasets/`, a regular file, and committed unchanged from HEAD.
- `<run-config>` must be committed unchanged from HEAD.
- In every mode except preflight, the working tree must be clean, including untracked files.

**Run config** (`scholarchain.bounded-pipeline-config.v1`): one `key=value` per line. Values may only use `A-Za-z0-9._:/=,@+-`. All 36 keys are required, none has a default, and unknown keys, repeated keys, empty values or `REPLACE-ME` are refused.
- `config_version` must equal the schema name.
- `run_profile` must be `smoke`. Functional runs are refused: they need caps you ratify and your venue decision.
- `pipeline` must be `hadoop`.
- `aws_profile`: profile-name pattern. `aws_region`: Region pattern. `aws_account_id`: 12 digits.
- `emr_cluster_id`: `j-` followed by 8–20 uppercase letters or digits. `emr_release_label`: `emr-X.Y.Z`.
- `emr_allowed_instance_types`: every type must be in the hard-cap list.
- `emr_max_instances` 1..5, `emr_max_idle_timeout_seconds` 60..3600, `step_timeout_minutes` 1..90, `poll_interval_seconds` 30..300.
- `instance_hour_price_usd`: a positive decimal. `max_cost_usd`: at most 10.
- `raw_bucket`: bucket-name pattern.
- `curated_root_uri` and `pipeline_runs_uri`: `s3://bucket[/prefix]`, no trailing slash, and neither may sit inside the other.
- `jar_uri`: must contain `/<jar_git_sha>/` and end in `.jar`.
- `jar_git_sha`: 40 hex. It must be an ancestor of HEAD, with no change since then in `modules/core`, `modules/entity-resolution`, `modules/hadoop`, `build.sbt` or `project`, and it must equal the run manifest's `codeVersion`.
- `jar_sha256`: 64 hex. `jar_max_bytes`: at most 512 MiB.
- `run_manifest_path`: a committed `.json`. `run_manifest_sha256` must match its bytes.
- `dblp_snapshot_id`: must equal the dataset manifest's dblp snapshot id (currently `dblp-2026-10-03`).
- `dblp_input_uri`: must be an object under `s3://<raw_bucket>/raw/dblp/snapshot_date=<date>/`, built from the manifest's `raw_prefix`.
- `dblp_input_local_path`: a plain path ending `.xml` or `.xml.gz`, not tracked by git, with the same file name as the URI. `dblp_input_sha256` must match both the local file and the S3 object.
- `max_input_bytes` at most 64 MiB. `max_input_records` at most 10,004.
- `stages`: a subset of `normalize,exact-ids,teams,blocking,candidates`, in that order, with `normalize` required and `candidates` only with `blocking`.
- `dblp_markup_policy`: `refuse-if-present` or `accept-reported`.
- `team_max_authors` 2..3000, `team_max_pairs` 1..5,000,000, `teams_oversize_override`: `refuse` or `allow`.

**Run manifest checks (with `jq`):**
- Basics: schema must be `scholarchain.run-manifest.v1` with stage `hadoop.pipeline`; `attempt` 1..1000; `configDigest` 64 hex.
- `inputSnapshots` holds exactly one `{source:"dblp", snapshot:<id>}`.
- `parameters` must be exactly the 21 keys in `JobParams.ManifestKeys`, all non-empty strings, and within bounds:
  - `dblp.maxRecordBytes` and `orcid.maxRecordBytes`: 1024..1 MiB.
  - `reject.maxExcerptChars`: 0..4096. `team.maxSize`: 2..3000. `blocking.maxBlockSize`: 2..200.
  - `oversizePolicy`: `drop` or `fail`. Each reducer count: 1..8.
  - Encoding must be one of the three the driver supports; record types must be publication types.
  - Codec classes must be in `org.apache.hadoop.io.compress`; version strings must be tokens.
- `scholarchain.dblp.venues` and `yearRange` are copied as written and printed as `CONFIRM` lines. The script never chooses a venue subset.

### Bounds, hard caps, and where they live
- **Hard caps** are a `readonly HC_*` block at the top of the script, marked as needing your ratification. A config can only tighten them.
  - Instances 5 (primary + core + task, including the managed-scaling maximum); instance types `m5.xlarge m6i.xlarge m7i.xlarge`.
  - Idle timeout 3600 s, step timeout 90 min, poll interval 30–300 s, cost 10 USD.
  - Input 64 MiB and 10,004 publication records; jar 512 MiB; `maxRecordBytes` 1 MiB.
  - Reducers 8, block size 200, largest team 3000 authors, 5,000,000 team pairs, 50,000,000 candidate pairs.
  - `_counters.json` 1 MiB; 1000 run prefixes listed.
- **Cost estimate:** max instances × hourly price × timeout ÷ 60 must be at most `max_cost_usd`. It covers only the step window, not the cluster's idle time, S3 or data transfer.
- **Runtime:** a deadline set from `step_timeout_minutes`. At the deadline, or on Ctrl-C, it cancels its own step only, then waits up to 10 minutes for the cancel to finish.
- **Single run:**
  - Two write-once keys per manifest checksum.
  - No pending or running step may be on the cluster.
  - A local lock directory under `TMPDIR`.
  - A failed run needs a new manifest with `attempt + 1`.
- **Giant teams:** the local scan reports the largest team and the total pair count, sum of n(n-1)/2. If `teams` is a stage and either exceeds its config bound, the run is refused unless `teams_oversize_override=allow`; hard caps always apply.
- **XML markup:** the scan counts comments, CDATA, processing instructions (excluding the XML declaration), DOCTYPE and ENTITY declarations. It assumes nothing about the dump. Any non-zero count is refused under `refuse-if-present`, or recorded under `accept-reported`.
- **Split overread:** `maxRecordBytes` and the per-split overread (`maxRecordBytes + 16`) are printed and stored in the summary.

### Dry-run output
Greppable lines prefixed `[run-bounded-pipeline]`:
- `STEP`, `CHECK ok`, `SCAN k=v` (16 scan values plus a note that the scan is a heuristic).
- `BOUND name=value cap=… source=…`, `PLAN-AWS-CALL NN read|write: aws …` with resolved values.
- `CONFIRM …`: environment, account, Region, profile, cluster, HEAD, the config, manifest, dataset-manifest, jar and input checksums, stages, bounds, submission prefix.
- `CONFIRMATION-TOKEN brp-<32 hex>`, then `RESULT DRY-RUN-OK`.

The token is a SHA-256 over all of those values, so any change, including a new commit, invalidates it. Failures print `FAIL exit=N reason` to stderr. In execute mode, `AWS-CALL` lines also go to stderr.

### Planned AWS calls (none were executed)
- **Reads:**
  - `sts get-caller-identity`, which must equal the configured account.
  - `emr describe-cluster`: state WAITING or RUNNING, release label, Hadoop present, the five required tags with matching environment and a future expiration, a LogUri.
  - `emr list-instance-groups` or `list-instance-fleets`: types allowed, no instance-group AutoScalingPolicy, fleet weights equal to 1.
  - `emr get-managed-scaling-policy`: unit type Instances or InstanceFleetUnits; primary plus maximum is at most the instance bound.
  - `emr get-auto-termination-policy`: must exist and be within the idle cap.
  - `emr list-steps` for pending or running steps: must be none.
  - `s3api head-object` / `get-object` on the jar and the input, with size caps, then SHA-256 checks.
  - `head-object` on both submission keys: both must be absent.
  - `list-objects-v2` of `<curated>/hadoop/snapshot=<id>/`.
- **Write:** `s3api put-object --if-none-match "*"` of the run manifest to `<pipeline_runs_uri>/submission=<manifest sha256>/run-manifest.json`.
- **Write:** `emr add-steps`: one CUSTOM_JAR step, `ActionOnFailure=CONTINUE`, main class `scholarchain.hadoop.pipeline.PipelineDriver`, arguments `--manifest <staged> --curated-root <root> --stages <list> --dblp <input>`.
- **Read:** `emr describe-step`, polled until the deadline.
- **Write, only at the deadline or on interrupt:** `emr cancel-steps` for its own step ID.
- **Reads:** `list-objects-v2` again (exactly one new `run=<64 hex>/` prefix is required), then `head-object` of `_run-manifest.json` and each stage's `_SUCCESS` and `_counters.json`, plus a capped `get-object` of each counters file.
- **Write:** `put-object --if-none-match "*"` of `run-summary.json`.

All calls go through two wrappers that refuse any operation outside a fixed list. The script never creates, terminates or scales a cluster, and never deletes or overwrites anything.

**Run summary** (`scholarchain.bounded-pipeline-summary.v1`): printed between `SUMMARY-BEGIN` and `SUMMARY-END` and written once. It holds status, environment, account, Region, commit, times and token; the config, dataset manifest and run manifest with checksums (including attempt, codeVersion, configDigest, venues and parameters); the jar; the input and its scan; bounds and hard caps; EMR details (cluster, release, LogUri, observed instance bound and types, scaling maximum, idle timeout, step ID, state, failure); and outputs (run URI, run ID, and per-stage `_SUCCESS`, counters URI, counters checksum and counters JSON). It is written on every exit path after the step is submitted.

### Infrastructure it assumes (for Phase 12)
- A committed `infra/terraform/environments/<env>/`.
- One EMR cluster managed by Terraform:
  - Hadoop installed, an auto-termination policy and a LogUri.
  - Either no scaling, or managed scaling counted in Instances or InstanceFleetUnits with weight 1.
  - The tags course, project, environment, owner and expiration, with expiration as YYYY-MM-DD.
- Encrypted, non-public S3 locations for raw, curated, artifacts and pipeline-runs. The artifact location needs to be immutable (versioning plus a no-overwrite policy, or Object Lock).
- An operator IAM role limited to the actions listed in the script header, with no TerminateJobFlows, scaling changes or `s3:DeleteObject`.
- An EMR instance role that can read the jar and input and write the curated root.

### Decisions for you or other owners
1. **Operator (G6):** the operator is not decided, and the `deploy-engineer` row stays inactive (B2+G6, ENV-unratified). Only `factory-engineer` can activate it.
2. **Phase 12 inputs:** the environment list and the Terraform environment.
3. **Hard caps:** you need to ratify them, along with the instance types and the 10 USD cost cap.
4. **Where my writes go:** the script writes under `<pipeline_runs_uri>/submission=<manifest sha256>/`, not under `run=<runId>`. The run ID can only be computed by `RunId.of` in core, and the driver needs the manifest in S3 before it runs. I didn't reimplement that hash in bash. The driver alone writes the curated `run=` prefix. Please accept this, or ask `domain-engineer` or `build-engineer` for a tool that prints the run ID.
5. **No owner yet for two files:** the run config and the run manifest. Suggestion: configs under `infra/terraform/environments/<env>/` (mine), with manifests owned by your decision.
6. **No subset tool:** nothing builds the smoke subset file (4 sample records, then the first 10,000). That is `dataset-engineer`'s. The script only checks the size cap and checksum; it can't confirm the subset's composition.
7. **Raw-zone upload:** the dataset manifest says the pipeline script uploads to the raw zone. This one doesn't, because it never writes to raw. It needs a separate approved upload step.
8. **No enrichment inputs:** OpenAlex, Crossref and ORCID are not passed in the smoke run. Adding them needs bounded subsets and caps first.
9. **Jar packaging:** packaging the jar and checking the Typesafe Config conflict belongs to `build-engineer`. `scripts/package.sh` stays inactive (B3).
10. **Venue subset:** this is still your decision. The functional profile is refused until you make it.
11. **Executable bit:** the file isn't executable, and I have no allowlist row for `chmod`. Please run `chmod +x scripts/run-bounded-pipeline.sh` before committing; script provenance compares the executable bit against HEAD.
12. **shellcheck:** install it so the lint can actually run, since `code-reviewer` can't run it either.

```text
STATUS: COMPLETE
OWNER: infra-engineer
WORK ORDER: Phase 4, work order 5 of 7 -- author the bounded EMR smoke-run script for the Hadoop pipeline (local syntax checks only; no execution, no AWS, no Terraform)
FILES CREATED:
- scripts/run-bounded-pipeline.sh
FILES MODIFIED: none
COMMANDS RUN:
- git status --short (exit 0; at start and at end)
- git ls-files -- scripts infra .github datasets/scripts (exit 0)
- git ls-files --others --exclude-standard -- docs/agent-runs/phase-04 (exit 0)
- git ls-files -- .claude/hooks (exit 0); git ls-files -- .claude/commands .claude/agents (exit 0)
- git ls-files --others --exclude-standard -- modules/hadoop/src/main (exit 0); git ls-files -- modules/core/src/main (exit 0)
- bash -n scripts/run-bounded-pipeline.sh (exit 0, no output; run twice, the second after the final edits)
- shellcheck scripts/run-bounded-pipeline.sh (exit 127: "(eval):1: command not found: shellcheck")
TEST RESULTS: none (shell script only; no Scala or source change, so sbt check does not apply; the script was not executed in any mode)
VALIDATION RESULTS:
- bash -n: passed (exit 0, no output)
- shellcheck: NOT RUN; shellcheck is not installed (exit 127). Nothing was installed. Manual review instead: strict mode, quoted expansions, no eval, bash 3.2 compatible, helpers that may die are wrapped in subshells with mapped exit codes after submission
- terraform fmt/validate/plan: not applicable (no Terraform changed; init/plan rows are inactive and forbidden by this work order)
- security scan: none run (no Terraform; no scanner selected or installed)
- git status --short: the only new path from this work order is scripts/run-bounded-pipeline.sh; every other entry was already present at session start
ASSUMPTIONS:
- The target is an existing Terraform-managed EMR cluster (add-steps); the script never creates a cluster. Step Functions is a possible Phase 12 alternative
- The run manifest JSON encodes inputSnapshots[].source as the tag "dblp"; if not, the script refuses (fails closed)
- The ten EMR and S3 API field names, cancel-steps --step-cancellation-option, and put-object --if-none-match are taken from my knowledge; the aws-docs and terraform MCP servers failed to connect this session, so they are not verified against current docs
- The smoke subset is DBLP only, and the record cap counts publication records
RISKS:
- shellcheck has not run; possible warnings (for example SC2154 on variables set via printf -v) are unreviewed by the tool
- The scan is a line-based heuristic: record and team counts are exact only when no markup constructs are present (the summary says so)
- The cost estimate covers the step window only; the cluster's idle cost depends on the Terraform auto-termination policy
- Only one jar checksum check happens; EMR re-reads jar_uri afterwards, so the artifact location must be immutable (Phase 12)
- Not checked against current AWS docs (MCP servers were unavailable)
MANUAL ACTION REQUIRED:
- Human: chmod +x scripts/run-bounded-pipeline.sh before committing; review and ratify the hard caps; decide G6 (operator), the environment list, the owner and path of the run config and run manifest, raw-zone upload, and accept the submission-prefix write location (decision 4 above)
- Human: install shellcheck so the lint can be run and recorded
- Do not run the script until the items in its header are ratified
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer review of scripts/run-bounded-pipeline.sh; shellcheck run once installed; human ratification of caps, operator (G6), environments (Phase 12); factory-engineer activates the allowlist row only after that; dataset-engineer provides the smoke-subset tool; build-engineer provides the SHA-versioned job jar
```

## build-engineer, work order 6: job-jar packaging (final report, verbatim)

`sbt packageJobs` succeeded and built both job jars. The assembly log shows no merge conflicts and no warnings. I could not list what is actually inside either jar, because my Bash allowlist has no jar-listing command. Every content finding below therefore comes from sbt's own records of what went into each jar (the assembly debug logs, the runtime classpaths and the compiled class directories). None of this shows the jars will run on EMR; that needs a real EMR run.

**Packaging result**
- Hadoop jar: `modules/hadoop/target/scala-3.3.6/scholarchain-hadoop-jobs-0.1.0.jar`, jar hash 70c8132361844a04ba3450acedfd31d2b559e0ad, `[success] Total time: 2 s`.
- Spark jar: `modules/spark/target/scala-3.3.6/scholarchain-spark-jobs-0.1.0.jar`, jar hash 5822710b68c48716096484a17ef1985d34cd1e7c, `[success] Total time: 1 s`.
- Jar sizes were not printed and I could not read them with permitted tools.
- Merge lines, identical for both jars:
  - `[info] 2 file(s) merged using strategy 'Rename'`: these are `LICENSE` and `NOTICE` from scala-library-2.13.16.jar, renamed to `LICENSE_scala-library-2.13.16` and `NOTICE_scala-library-2.13.16`.
  - `[info] 9 file(s) merged using strategy 'Discard'`: these are the `META-INF/MANIFEST.MF` files from geny_3-1.1.1, ujson_3-4.1.0, upack_3-4.1.0, upickle-core_3-4.1.0, upickle-implicits_3-4.1.0, upickle_3-4.1.0, config-1.4.3, scala-library-2.13.16 and scala3-library_3-3.3.6.
  - No Deduplicate, First, Last or Concat lines appeared. The "Finding remaining conflicts that were not merged" step reported nothing, and no `module-info.class` entry hit the custom discard rule.

**Hadoop jar contents (from its runtime classpath)**
- Inputs: the compiled classes of hadoop, core and entity-resolution, plus these third-party jars: scala3-library_3 3.3.6, scala-library 2.13.16, the upickle family 4.1.0 (upickle, ujson, upack, upickle-core, upickle-implicits), geny_3 1.1.1 and config 1.4.3.
- Application classes present in the hadoop classes directory:
  - `scholarchain/hadoop/pipeline/PipelineDriver.class` and `PipelineDriver$.class`.
  - The sources `jobs/NormalizeJob.scala`, `ExactIdJob.scala`, `TeamJob.scala`, `BlockingJob.scala` and `CandidateJob.scala` exist, but **no classes named `NormalizeJob`, `ExactIdJob`, `TeamJob`, `BlockingJob` or `CandidateJob` exist**. Those files compile to mapper and reducer classes plus `jobs/Stages`, for example NormalizeMapper, CanonicalRecordReducer, ExactIdMapper, ExactIdReducer, TeamMapper, TeamReducer, BlockingSeedMapper, BlockReducer, BlockPairMapper, CandidateConsolidateMapper, CandidateConsolidateReducer and PairFeatureReducer.
  - `scholarchain.entityresolution.*` classes are present: BlockingKeys, MatchFeatures, PersonName, Similarity, TextFolding, TitleText, ResearcherEndpoint, PreparedWork, PreparedResearcher, MentionContext, EntityResolution.
  - `scholarchain.core.*` classes are present, including `scholarchain/core/config/ConfigLoader` and `ScholarChainConfig`.
- Main-Class: sbt discovered exactly one main class, `["scholarchain.hadoop.pipeline.PipelineDriver"]`. sbt-assembly normally writes that into the manifest when there is a single main class, but I did not read the manifest, so this is inferred.

**Spark jar contents (from its runtime classpath)**
- Inputs: the spark module's classes, core's classes, and the same third-party jars as the hadoop jar.
- The spark classes directory is empty, because `modules/spark` has only `.gitkeep` files. So the jar has no `sparkAnalytics` classes, only core and third-party classes.
- No main class was discovered, so no Main-Class is expected.

**Provided dependencies**
Neither runtime classpath lists hadoop-client-api, hadoop-client-runtime, spark-sql or any Hadoop or Spark jar, and none appears among the merge origins. That is consistent with them being left out of both jars. A listing of the jars is still needed to confirm this directly.

**Typesafe Config**
- Packaging evidence:
  - config-1.4.3.jar (`com.typesafe`) is on both runtime classpaths and is a merge origin in both assemblies, so `com/typesafe/config/**` is bundled.
  - It is not relocated: build.sbt defines no shade rules.
  - The version comes from the ledger (`typesafe-config=1.4.3`) and the classpath file name. I could not read `META-INF/maven/com.typesafe/config/pom.properties` from inside the jar.
  - No module has a `reference.conf` or `application.conf` in its main resources, so there is no HOCON resource merge conflict.
- Still unverified until a real EMR run:
  - which Typesafe Config version, if any, is on EMR's own Hadoop, YARN and Spark classpath;
  - whether Hadoop or Spark on EMR ships its own copy;
  - classloader order on EMR (`mapreduce.job.user.classpath.first` / `mapreduce.job.classloader` for MapReduce; `spark.driver.userClassPathFirst` / `spark.executor.userClassPathFirst` for Spark);
  - whether an EMR-supplied copy shadows 1.4.3 at runtime.
  - The Phase 3 human-gate risk ("Typesafe Config is bundled unshaded into the Hadoop and Spark job jars. A classpath conflict with an EMR-supplied copy must be watched") stays open.

**Other compatibility notes**
- Both jars bundle scala-library 2.13.16 and scala3-library 3.3.6. On Spark these can collide with EMR's Scala runtime; the ledger says EMR 7.x Spark is built for Scala 2.12 (decision F2, pending before Phase 6). On MapReduce I'd expect no collision, but that is unverified.
- The hadoop jar was built from uncommitted Phase 4 sources in the working tree.

To get direct evidence, a read-only listing row such as `jar tf <PATH:modules/*/target/scala-3.3.6/>` (or `unzip -l` on the same path) would be needed. That is a constitutional change for factory-engineer and the human; I am not asking for it to be applied now.

```text
STATUS: COMPLETE
OWNER: build-engineer
WORK ORDER: Phase 4, work order 6 of 7. Run `sbt packageJobs`, inspect both job jars for application classes, Typesafe Config packaging, exclusion of Provided dependencies, bundled third-party packages and Main-Class, and report merge conflicts. No build changes.
FILES CREATED: none. The build tool wrote two gitignored artifacts, `modules/hadoop/target/scala-3.3.6/scholarchain-hadoop-jobs-0.1.0.jar` and `modules/spark/target/scala-3.3.6/scholarchain-spark-jobs-0.1.0.jar`.
FILES MODIFIED: none
COMMANDS RUN:
- `sbt packageJobs`: success (hadoop/assembly `[success]` in 2 s, sparkAnalytics/assembly `[success]` in 1 s).
- `git ls-files --others -- modules/hadoop/target/streams/compile/assembly`: no output (path does not exist).
- `git ls-files --others -- modules/hadoop/target/streams`: listed the stream files.
- `git ls-files --others -- modules/hadoop/target/scala-3.3.6/classes modules/entity-resolution/target/scala-3.3.6/classes modules/spark/target/scala-3.3.6/classes`: listed hadoop and entity-resolution classes; spark had none.
- `git ls-files --others --exclude-standard -- modules/hadoop/src/main modules/spark`: listed 18 untracked hadoop sources; spark had none.
- `git ls-files -- modules/hadoop/src/main modules/spark modules/core/src/main`: tracked core sources; only `.gitkeep` files for hadoop and spark.
- `git ls-files --others -- modules/core/target/scala-3.3.6/classes`: listed core classes.
- `git ls-files --others -- <core config classes dir and the reference.conf/application.conf paths>`: config classes listed; no `.conf` files.
- `git ls-files -- modules/core/src/main/resources modules/hadoop/src/main/resources modules/entity-resolution/src/main/resources`: no output.
- Read-tool calls (not Bash): build.sbt, project/plugins.sbt, project/versions.properties, both assembly stream logs, both runtime fullClasspath exports, the hadoop assembly-outputs file, discoveredMainClasses/data and the mainClass stream (empty). Glob was not available in this session.
TEST RESULTS: none run. `sbt check` was not run because nothing changed.
VALIDATION RESULTS:
- Both assemblies succeeded with no conflicts: 2 Rename (scala-library LICENSE/NOTICE) and 9 Discard (MANIFEST.MF).
- Verified by indirect evidence: PipelineDriver and the mapper/reducer classes; core and entity-resolution classes on the hadoop jar inputs; Typesafe Config 1.4.3 bundled and not relocated; Hadoop and Spark absent from both jar inputs; spark jar has no application classes of its own.
- Not verified: actual jar entries, jar sizes, manifest Main-Class, pom.properties inside the jar, duplicate packages inside the jars.
ASSUMPTIONS:
- The runtime fullClasspath exports and the assembly debug logs accurately reflect the jar inputs.
- sbt-assembly writes the single discovered main class into the manifest.
- The `*Job` names in the work order refer to source files, not classes.
RISKS:
- Typesafe Config 1.4.3 is bundled unshaded; a clash with an EMR-supplied copy is possible and untested.
- Bundled Scala 2.13 and Scala 3 standard libraries in the Spark jar against EMR's Scala 2.12 Spark (F2 pending).
- The hadoop jar was built from uncommitted Phase 4 sources.
- No EMR runtime compatibility is shown.
MANUAL ACTION REQUIRED: To get direct evidence, the human can run `jar tf` on both jars, read `META-INF/MANIFEST.MF`, and check for `com/typesafe/config/` and for any `org/apache/hadoop` or `org/apache/spark` entries. Optionally, ratify a read-only jar-listing allowlist row through factory-engineer.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Orchestrator and code-reviewer review of this report; Phase 4 work order 7; a real bounded EMR run to settle the Typesafe Config classpath and classloader-order risk.
```
