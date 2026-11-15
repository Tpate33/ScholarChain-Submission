# ScholarChain Hadoop job contracts

Owner: `hadoop-engineer`. Module: `modules/hadoop` (package `scholarchain.hadoop`). This file is
the authoritative contract of every MapReduce job in the module (role law 7). Curated record
contracts come from `modules/core`; the reject, spill, block-membership, joined-member, and stats
line formats are hadoop-internal and versioned here. Blocking-key formulas and match features come
from `modules/entity-resolution` (pure functions only).

## Job graph

```text
raw zone inputs                               stage: MapReduce jobs                      curated outputs
DBLP XML -----------(DblpInputFormat)---------+
OpenAlex works/authors/institutions JSONL ----+--> normalize (1 job, MultipleInputs, ---> works, mentions, profiles, venues,
Crossref works JSONL --------------------------+     combiner)                            institutions, topics, rejects
ORCID summary XML (WholeFileInputFormat) ------+
                                                         |
normalize/{works,profiles} -----------------------------+--> exact-ids (1 job) -------> links (IdentityLink), rejects, stats
normalize/works ----------------------------------------+--> teams (1 job) -----------> teams, pairs, rejects, stats
normalize/{works,profiles} -----------------------------+--> blocking (1 job per round, both entities)
                                                         |      round 0 .. L-1 -------> blocks/<entity>, members/<entity>,
                                                         |                              spill, rejects, stats
blocking/round-*/members + normalize/{works,profiles} --+--> candidates (3 jobs):
                                                                join -> pairs -> final -> final/<entity> (MatchCandidate),
                                                                                           rejects, stats
```

`exact-ids`, `teams`, and `blocking` need a complete `normalize` of the same run and are
independent of each other. `candidates` needs complete `normalize` and `blocking` of the same run.
Researcher and work blocking run in ONE blocking stage under ONE run ID (review H2); normalize is
never re-run for a second entity.

## Record versions written

| Output | Contract (version) | Owner |
|---|---|---|
| `normalize/works` | `WorkRecord` `scholarchain.work-record.v3` | core |
| `normalize/mentions` | `AuthorMention` `scholarchain.author-mention.v1` (the author-paper record) | core |
| `normalize/profiles` | `SourceAuthorProfile` `scholarchain.source-author-profile.v1` | core |
| `normalize/{venues,institutions,topics}` | `Venue`, `Institution`, `Topic` v1 | core |
| `exact-ids/links` | `IdentityLink` `scholarchain.identity-link.v1` | core |
| `teams/teams` | `PublicationTeam` `scholarchain.publication-team.v1` | core |
| `teams/pairs` | `CoauthorPairCandidate` `scholarchain.coauthor-pair-candidate.v2` (with positions) | core |
| `blocking/round-<r>/blocks/<entity>` | `CandidateBlock` `scholarchain.candidate-block.v1` | core |
| `candidates/final/<entity>` | `MatchCandidate` `scholarchain.match-candidate.v1` | core |
| `<stage>/rejects` | `RejectRecord` `scholarchain.hadoop.reject.v1` | hadoop |
| `blocking/round-<r>/members/<entity>` | `BlockMembership` `scholarchain.hadoop.block-membership.v1` (join index) | hadoop |
| `blocking/round-<r>/spill` | `BlockMember` JSON (between-round intermediate, unversioned, not curated) | hadoop |
| `candidates/join/joined` | `JoinedMember` `scholarchain.hadoop.joined-member.v1` (intermediate) | hadoop |
| `candidates/pairs/pairs` | `MatchCandidate` per block, before consolidation (intermediate) | core |
| `<job>/stats` | `StatLine` JSON (`metric`, `value`, `id`) | hadoop |

Every curated record carries provenance: dataset records carry source dataset, source record ID,
snapshot, run ID, `<source>-raw/1.0.0`, and `policyVersion = hadoop-transform.v2`. Derived records
(blocks, candidates) carry `scholarchain-derived`, the DBLP snapshot, the run ID, the schema of
their primary input (`scholarchain.work-record.v3`, `scholarchain.author-mention.v1`,
`scholarchain.source-author-profile.v1`, or `scholarchain.candidate-block.v1`), and the policy
version; they also name `blockingVersion` (and candidates `featureVersion`). Every reader of a
WorkRecord (and of every other core record) uses the strict core decoder `Json.decode`, never
`upickle.default.read`.

## Run identity, parameters, layout, idempotency

- The driver reads a `RunManifest` (`scholarchain.run-manifest.v1`, `stage = hadoop.pipeline`).
  The run ID is `RunId.of(manifest)`; every job parameter comes from `manifest.parameters`, so a
  parameter change is a new run ID. There are no defaults: a missing parameter is an error; `*`
  means "no restriction" explicitly. Unknown parameters are refused.
- Output root: `<curated-root>/hadoop/snapshot=<dblp snapshot>/run=<run ID hex>/`, holding
  `_run-manifest.json` (byte-compared on reuse) and one directory per stage.
- Commit order (review H6): the job output committers write NO `_SUCCESS`
  (`mapreduce.fileoutputcommitter.marksuccessfuljobs=false`). After all jobs of a stage succeed the
  driver writes `<stage>/_counters.json` (all `scholarchain` counters, the ten required ones always
  present, plus aggregated stats) and only then `<stage>/_SUCCESS`.
- Idempotency: a stage with `_SUCCESS` is skipped and its `_counters.json` is reported. A stage
  directory without `_SUCCESS` (failed job, or a driver that stopped before committing) is never
  overwritten: the run must be repeated with `attempt + 1`. A stage with `_SUCCESS` but no
  `_counters.json` (only possible by tampering) is refused, never reported with empty counters.
  The driver never deletes anything.

| Parameter | Meaning | Local test value |
|---|---|---|
| `scholarchain.dblp.recordTypes` | DBLP publication record types kept (subset of article, inproceedings, proceedings, book, incollection, phdthesis, mastersthesis) | `article,inproceedings` |
| `scholarchain.dblp.yearRange` | `*` or `YYYY-YYYY` | `*` |
| `scholarchain.dblp.venues` | `*` or `|`-separated exact `journal`/`booktitle` strings (venue subset is a pending human decision) | `*` |
| `scholarchain.dblp.encoding` | `UTF-8`, `ISO-8859-1`, or `US-ASCII`; must match the XML declaration of the input (for the pinned snapshot this is UNVERIFIED, manifest `format.encoding`) | `UTF-8` |
| `scholarchain.dblp.maxRecordBytes` | larger records are rejected `unterminated-record` (>= 1024); also the DBLP markup bound W and the split lookback (W + 16 bytes) | `1048576` |
| `scholarchain.orcid.maxRecordBytes` | larger ORCID files are rejected | `1048576` |
| `scholarchain.reject.maxExcerptChars` | excerpt length in reject records | `256` |
| `scholarchain.team.sources` | dataset sources whose works form teams and pairs | `dblp` |
| `scholarchain.team.maxSize` | oversize-team REPORTING threshold (>= 2): larger teams are counted and flagged; all their pairs are still emitted | `100` (`20` in the oversize test) |
| `scholarchain.blocking.keySource` | class implementing `jobs.BlockingKeySource`; production: `scholarchain.hadoop.jobs.EntityResolutionKeySource` | `EntityResolutionKeySource` |
| `scholarchain.blocking.keySourceVersion` | must equal the key source's `version` (driver check); records the key-formula version in the manifest | `er-blocking.v2` |
| `scholarchain.blocking.maxBlockSize` | maximum block size (>= 2); also bounds the candidate stage | `4` |
| `scholarchain.blocking.oversizePolicy` | `drop` or `fail` after the last fallback level | `drop` |
| `scholarchain.candidates.featureVersion` | must equal `EntityResolution.featuresVersion` (driver check); records the feature version in the manifest | `er-features.v2` |
| `scholarchain.reducers.{normalize,exactIds,teams,blocking,candidates}` | reducer counts (`candidates` applies to all three candidate jobs) | `3, 2, 2, 2, 2` |
| `scholarchain.codec.intermediate` | map-output codec class | `org.apache.hadoop.io.compress.DefaultCodec` |
| `scholarchain.codec.output` | final-output codec class | `org.apache.hadoop.io.compress.GzipCodec` |
| `scholarchain.input.split` (optional) | label of the input split (`dev`, `heldout`); read by no job, recorded in the run ID; required and verified by the local split entry point (see "Local per-split pipeline") | absent |
| `scholarchain.input.digest` (optional) | 64-hex digest of the exact input files; read by no job, recorded in the run ID; required and verified by the local split entry point | absent |

There is no pair-suppression parameter: the former `scholarchain.team.oversizePolicy` (and its
`skip-pairs` value) and `scholarchain.blocking.entity` were removed; a manifest that still names
either is refused as an unknown parameter.

## Common rules (all jobs)

- Partitioner: `StableHashPartitioner` (FNV-1a 32 over the UTF-8 key bytes, mod reducer count):
  independent of JVM, run, and `Object.hashCode`.
- Map output compressed with `scholarchain.codec.intermediate`; every output (JSON Lines, one
  record per line, `TextOutputFormat` via `LazyOutputFormat` + `MultipleOutputs`) compressed with
  `scholarchain.codec.output`. Speculative execution is off (side outputs).
- Determinism: keys reach reducers sorted. Every reducer emits one canonical value per key
  (smallest by content hash, then UTF-8 bytes) and sorts every curated list it emits (member IDs,
  memberships, block keys, identifier sets, claims, links). The one exception is the blocking
  `spill` intermediate, whose lines are written in value-arrival order; spill is not a curated
  output, and the block it finally produces is sorted. No task shares mutable state; the only
  mutable state is task-local (Writables, per-task buffers, per-task maxima written in `cleanup`).
- Rejects: format `scholarchain.hadoop.reject.v1` (`RejectRecord`), one JSON object per line under
  `<job output>/rejects/part-{m,r}-NNNNN`. `disposition` is `rejected` (record not in any curated
  output) or `flagged` (kept, or a stage-level finding, and reported). `rejectId` is a SHA-256 of
  the record's identifying fields. Nothing is only logged.
- Counters (group `scholarchain`): `INPUT`, `ACCEPTED`, `REJECTED`, `DUPLICATE`, `CONFLICT`,
  `OVERSIZE_BLOCK`, `OVERSIZE_TEAM`, `EMITTED_PAIR`, `MISSING_KEY`, `SCHEMA_FAILURE` (always
  reported), with `.<qualifier>` detail counters; plus `FLAGGED`, `FILTERED`,
  `SKIPPED_NON_PUBLICATION`, `MERGED_REFERENCE`, `EMITTED.<output>`, `TEAM_NO_AUTHORS`,
  `TEAM_SOURCE_EXCLUDED`, `EXACT_ID_KEY`, `EXACT_ID_CROSS_SOURCE`, `RECORDS_READ`,
  `REFINE_INPUT`, `BLOCK_EMITTED`, `BLOCK_MEMBERS_REFINED`, `BLOCK_MEMBERS_DROPPED`,
  `CANDIDATE_PAIRS`, `CANDIDATE_MERGED`, `CANDIDATE_MULTI_BLOCK`.
- Reject reasons and the required counter each increments: `malformed-xml`, `malformed-json`,
  `unterminated-record`, `missing-field`, `malformed-field`, `malformed-identifier`,
  `schema-failure`, `invalid-blocking-key` -> `SCHEMA_FAILURE`; `missing-key`,
  `unresolved-member` -> `MISSING_KEY`; `conflict` -> `CONFLICT`; `oversize-team` ->
  `OVERSIZE_TEAM`; `oversize-block` -> `OVERSIZE_BLOCK`. Each also increments `REJECTED.<reason>`
  or `FLAGGED.<reason>`.

## 1-2. normalize (DBLP extraction and source normalization)

| Item | Contract |
|---|---|
| Input formats | `DblpInputFormat` (key `Text` locator `<path>@<offset>`, value `DblpRawRecord`); `LocatedTextInputFormat` (key locator, value `Text` line) for OpenAlex works, authors, institutions and Crossref works; `WholeFileInputFormat` (key path, value `BytesWritable`) for ORCID. Combined with `MultipleInputs`. |
| Mappers | `DblpMapper`, `OpenAlexWorksMapper`, `OpenAlexAuthorsMapper`, `OpenAlexInstitutionsMapper`, `CrossrefWorksMapper`, `OrcidMapper` |
| Map output | key `Text` `<kind>\t<id>` (kind: work, venue, institution, topic, profile); value `Text` envelope `<rank>\t<copies>\t<conflict>\t<contentHash>\t<record JSON>` (rank 0 = primary record, 1 = embedded reference) |
| Combiner | `EnvelopeCombiner` (associative, commutative merge: lowest rank wins; copies add; conflict = distinct content) |
| Partitioner | `StableHashPartitioner` |
| Reducer | `CanonicalRecordReducer`: one record per key to `works`/`profiles`/`venues`/`institutions`/`topics`; each kept work is decoded with `Json.decode` and its mentions go to `mentions`. Extra primary copies -> `DUPLICATE.<kind>`; extra reference copies -> `MERGED_REFERENCE.<kind>`; different content -> `CONFLICT` flag (smallest content hash kept). |
| Counters | `INPUT.<input>`, `ACCEPTED.<input>`, `REJECTED.<reason>`, `FLAGGED.<reason>`, `FILTERED.{record-type,year,venue}`, `SKIPPED_NON_PUBLICATION.non-publication:{www,person,data}`, `DUPLICATE`, `CONFLICT`, `MERGED_REFERENCE`, `EMITTED.<output>` |
| Reject conditions | rejected: malformed XML/JSON, undeclared entity, DBLP record interrupted by the next record start (`malformed-xml`), DBLP markup not closed within `maxRecordBytes` (`malformed-xml`), unterminated or oversize record, missing or malformed key, missing title, empty author name, malformed year, core contract failure. Flagged (record kept): malformed optional identifiers (dropped), several DOIs or arXiv IDs in one record (first is dedicated, others in `additionalIds`), `ee`/`url` with whitespace or control characters (dropped; duplicates removed silently), invalid optional text (venue name, volume, issue, pages, type), ORCID `source-work-id` without a valid `common:source`, conflicting copies. |
| Reducers | `scholarchain.reducers.normalize` |
| S3 prefixes | in: `raw/dblp/...`, `raw/openalex/...`, `raw/crossref/...`, `raw/orcid/.../extracted/`; out: `<run>/normalize/` |
| Test fixtures | `DblpScannerSuite`, `DblpSynchronizationSuite`, `DblpMappingSuite`, `SourceNormalizersSuite`, `CrossrefEnvelopeSuite`, `LocalPipelineSuite` |

Crossref line shapes (HD-P5-02). `CrossrefWorksMapper` accepts, per line, either one bare work
object (public data file shape) or one REST API envelope holding exactly one work, as stored in the
ER candidate samples (`datasets/schemas/er-candidates.md`): `{"status":"ok","message-type":
"work-list","message":{...,"items":[<work>]}}` or `{"status":"ok","message-type":"work",
"message":<work>}`. A line with any of the keys `status`, `message-type`, `message` is an envelope
(`CrossrefNormalizer.unwrap`) and fails closed, rejected into `normalize/rejects` with its excerpt:
missing `status`, `message`, `message-type` or `message.items` -> `missing-field`; `status` other
than `"ok"`, an unknown `message-type`, `message.items` that is not an array or holds more than one
work -> `malformed-field`; an empty `message.items` -> `missing-key` (no work, no DOI); a non-object
item or message -> `malformed-json`. The unwrapped work is then normalized exactly like a bare
line (so a work without `DOI` is still `missing-key`). A Crossref work without an author list (for
example a retraction notice) is kept with no mentions.

DBLP reader (`dblp.DblpScanner`, `dblp.DblpInputFormat`; Phase 4 repair of D1/D2/D3). The
records and rejects it yields are the same for every split size (README 1166, 2243), except in the
untested case described under "XML-reader limitations" below:

- **Lexical model.** A byte-level lexer (never decodes). Outside markup, `<` starts a start tag,
  an end tag, or a markup construct: comment `<!-- -->`, CDATA `<![CDATA[ ]]>`, processing
  instruction `<? ?>`, or declaration `<! >` (DOCTYPE with quotes, a bracketed internal subset,
  and comments/PIs nested in it). Nothing inside markup is a tag. A **record start** is `<` + a
  DBLP record type (`DblpRecordTypes.All`) + whitespace, `>` or `/`, outside markup. The model
  assumes, from `dblp.dtd` (manifest `format.file_format`), that records are direct children of
  `<dblp>` and that no field element is named like a record type; this is not verified against the
  bytes of the pinned snapshot.
- **Markup bound W = `scholarchain.dblp.maxRecordBytes`.** An opener opens a construct only if
  its closer lies within W bytes after the opener prefix. Otherwise the opener is read as text and
  the split owning its `<` emits one `UnclosedMarkup` raw record, rejected `malformed-xml`
  (counted in `INPUT.dblp`, `REJECTED.malformed-xml`, `SCHEMA_FAILURE`). An unclosed comment
  therefore never swallows the records after it.
- **Record extent.** From the start tag to the first matching `</name\s*>` outside markup
  (`Complete`; self-closing tags are complete). If another record start comes first, the record
  is `Interrupted` right before it, rejected `malformed-xml` ("no end tag before the next record
  start tag at byte N"), and the reader resynchronizes at that start tag, so only the bad record is
  rejected (D2). End of input or more than W bytes -> `Unterminated`, rejected
  `unterminated-record`.
- **Ownership rule.** A record (or unclosed-markup report) belongs to the split containing the
  offset of its `<`; the reader reads past the split end to finish its last record.
- **Synchronization rule (D1).** The reader of a split starting at `s > 0` seeks to
  `max(0, s - (W + 16))` and lexes from there in the outside-markup state, emitting nothing before
  `s`. Every construct is at most W + 9 bytes (bound above), so a construct containing `s` has its
  opener inside the lookback window and, provided the lookback start itself is outside markup
  (limitation 1 below), `s` is classified exactly as a whole-file read classifies it.
  Cost: at most W + 16 extra bytes per split (1 MiB per 128 MiB split at the test value).
- Compressed files are not split (read whole from offset 0). Entities: the ISO 8859-1 (HTML 4
  Latin-1) set; any other entity rejects the record. External entities and DTDs are never loaded.
  The external DTD (`dblp.dtd`) is never read.

**XML-reader limitations (the single authoritative statement; not resolved in Phase 4).**

1. **Split-versus-whole-file divergence (residual D1 case).** The synchronization rule assumes the
   lookback start `s - (W + 16)` is outside markup. If that byte instead falls inside a construct
   (comment, CDATA section, PI, or DOCTYPE/internal subset) whose remaining bytes contain a
   markup-opener look-alike (for example the text `<?` or `<![CDATA[` inside a comment) whose
   closer lies within W bytes, the split reader enters a construct that the whole-file read does
   not see, and the two reads can classify bytes after `s` differently. Possible effects: a real
   record is hidden inside the phantom construct and lost (no work, no reject), or text inside the
   real construct is read as a record start and becomes a ghost work, or an `UnclosedMarkup` report
   appears or disappears. The output then depends on the split size, which breaks the determinism
   guarantee above (records and rejects identical for every split size). This case is NOT covered
   by any test: `DblpSynchronizationSuite` covers splits starting inside each construct, but not a
   lookback start inside a construct with such a look-alike.
2. **Markup longer than W is treated as unclosed.** A comment, CDATA section, or PI whose closer
   lies more than W = `maxRecordBytes` bytes after its opener is not a construct (markup-bound
   rule). Its opener is reported once as `UnclosedMarkup` (`malformed-xml`), its body is read as
   ordinary text, and every record start tag inside it is parsed as a record and becomes a work in
   BOTH whole-file and split reads. This is deterministic, but it differs from XML semantics: the
   only signal is the single `malformed-xml` report on the opener.
3. **The real input is not characterized.** Whether the pinned snapshot `dblp-2026-10-03` contains
   comments, CDATA sections, processing instructions, or DOCTYPE/internal-subset markup in its
   body is UNVERIFIED: the full dump has not been downloaded (`datasets/dataset-manifest.yaml`,
   `datasets.dblp.snapshot.status: pending-human-download`; `format.encoding` is UNVERIFIED), and
   the committed sample `datasets/samples/dblp/dblp-sample.xml` itself begins with a comment right
   after the XML declaration. Limitations 1 and 2 therefore cannot be ruled out for real data.

**Required verification before processing the full snapshot (or any non-smoke input).** Before a
run, a markup scan of the EXACT input bytes to be processed (the same file, identified by its
sha256, decompressed if gzip) must report the counts of comments, CDATA sections, processing
instructions (excluding the XML declaration), and DOCTYPE and ENTITY declarations. Any non-zero
count must be resolved by a recorded human decision before the run. For bounded smoke runs a scan
exists outside this module: `scripts/run-bounded-pipeline.sh --preflight-scan` scans the local copy
of the smoke input (sha256-matched to the S3 object, at most `max_input_bytes`, hard cap 64 MiB
uncompressed) and reports `comments`, `cdata_sections`,
`processing_instructions_excluding_xml_declaration`, `doctype_declarations`, and
`entity_declarations`; with `dblp_markup_policy=refuse-if-present` the run is refused when comments,
CDATA, PIs, or entity declarations are present (`accept-reported` proceeds and records the count).
As that script itself states, its scanner is a heuristic line scanner, and its refusal total does
not include `doctype_declarations` (reported, not gated), so a non-zero DOCTYPE count still needs
the human decision above. No equivalent scan exists yet for the full snapshot: that script supports
only `run_profile=smoke`, refuses functional runs, and caps the input far below the full dump. A
scan for the full snapshot must therefore be provided and run, and its counts recorded, before any
full-snapshot processing. Providing it is outside `modules/hadoop` (infrastructure workflow and
human decision; owner not decided here).

Source mapping (pure functions; details in their Scaladoc):

- DBLP (`dblp.DblpMapping`): `recordType` = element name, `venueName` = `journal` or `booktitle`
  (kept without a Venue record), `volume`, `issue` (= `number`), `pages`, `eeLinks` (every `ee`),
  `urls` (every `url`), DOI and arXiv from `ee` (extra distinct values in `additionalIds`). Authors
  in document order (a repeated name keeps both mentions). Year, ee, url, venue, volume, number,
  and pages are optional.
- OpenAlex works (`normalize.OpenAlexNormalizer`): `recordType` = `type`, `venueName` =
  `primary_location.source.display_name`, `volume`/`issue` from `biblio`, `pages` =
  `<first_page>-<last_page>` (or the first page), `topicIds` (primary topic first, then `topics[]`,
  distinct), `additionalIds` = `ids.mag` (`mag`).
- OpenAlex authors: `SourceAuthorProfile` `source-author:openalex:<A-ID>` (display name, name
  variants, ORCID, A-ID as external ID, last known institutions, which are also emitted as
  reference institution records).
- Crossref (`normalize.CrossrefNormalizer`): `recordType` = `type`, `venueName` =
  `container-title[0]`, `volume`, `issue`, `pages` = `page`.
- ORCID (`normalize.OrcidNormalizer`): per work summary, `recordType` = `work:type`, `venueName` =
  `work:journal-title`, `assertionSource` from `common:source` (client or iD holder, name,
  assertion origin), `sourceLocalWorkIds` from the summary's OWN `source-work-id` external IDs only
  (never the `activities:group` ones), each `SourceLocalWorkId.issuedBy(assertionSource, value)`
  then `SourceLocalWorkId.canonical`, never in `additionalIds`; `eid` (`self`) -> `scopus-eid` and
  extra DOIs in `additionalIds`. The record holder becomes `SourceAuthorProfile`
  `source-author:orcid:<iD>` (public given/family names, credit name, other names, and
  `claimedWorkIds` = the holder's work IDs sorted by UTF-8 bytes).

## 3. exact-ids (exact identifier extraction and identity-link inputs)

| Item | Contract |
|---|---|
| Input | `normalize/works` (`ExactIdMapper`) and `normalize/profiles` (`ExactIdProfileMapper`) via `LocatedTextInputFormat` + `MultipleInputs` |
| Map output | key `Text` `<scheme tag>\t<canonical value>` (schemes: `doi`, `arxiv`, `dblp-key`, `openalex-work`, `mag`, `scopus-eid`, `orcid`, `openalex-author`, `dblp-pid`); value `Text` `<source>\t<entity ID>\t<work ID or "">\t<IdentityLink JSON>` |
| Assertions | work links (own source ID, `doi`, `arxiv`, every `additionalIds` entry; ORCID works carry `assertionSource`); mention links (`orcid`, OpenAlex A-ID, DBLP pid; `workId` = the mention's work); ORCID claim links (identifier = holder iD, entity = `source-author:orcid:<iD>`, `workId` = the claimed ORCID work, `assertionSource` of the summary); profile links (own ID, `orcid`, author `externalIds`). Built with `IdentityLink.build` (validated); provenance = the asserting record's. |
| Combiner | none (group membership must be exact) |
| Partitioner | `StableHashPartitioner` |
| Reducer | `ExactIdReducer`: writes the group's links to `links` (distinct, sorted), `EXACT_ID_KEY.<scheme>`, `EXACT_ID_CROSS_SOURCE.<scheme>`, conflict flags (two works of one publication-scoped source with one work identifier; one researcher identifier on two mentions of one work; one researcher identifier on two profiles of one source), `stats` largest group per scheme. No match is decided. |
| Counters | `INPUT.{works,profiles}`, `ACCEPTED.<source>`, `EMITTED.links`, `EXACT_ID_KEY`, `EXACT_ID_CROSS_SOURCE`, `CONFLICT`, `REJECTED`/`FLAGGED` |
| Reject conditions | undecodable record (rejected); identifier that fails `ExactId.of` or `IdentityLink` validation (flagged); conflicts (flagged) |
| Reducers | `scholarchain.reducers.exactIds` |
| Outputs | `<run>/exact-ids/{links,rejects,stats}` |
| Test fixture | `LocalPipelineSuite` (links of every kind decode and validate) |

## 6. teams (publication teams, author-paper records, coauthor pairs)

| Item | Contract |
|---|---|
| Input | `normalize/works` via `LocatedTextInputFormat` |
| Mapper | `TeamMapper`: works of `scholarchain.team.sources` with >= 1 author (decoded with `Json.decode`); key `Text` work ID, value `Text` work JSON |
| Combiner | none |
| Partitioner | `StableHashPartitioner` |
| Reducer | `TeamReducer`: decodes the work with `Json.decode`; one `PublicationTeam` v1 (all mentions in `unresolvedMentionIds`, in author order; `teamSize = n`) and ALL n(n-1)/2 `CoauthorPairCandidate` v2 per work, one per position pair i < j, built with the positional core builder `CoauthorPairCandidate.of(mention_i, i, mention_j, j, workId, year, n, provenance)`: canonical mention-ID endpoints, each with its author position, work ID, year, team size, and the work's source provenance. The emitted count is checked against n(n-1)/2. |
| Oversize | `n > scholarchain.team.maxSize`: `OVERSIZE_TEAM`, a `flagged` reject (`teamSize`, `pairs`, `maxSize`, `all pairs emitted`). Pairs are NEVER suppressed. |
| Counters | `INPUT.works`, `ACCEPTED.<source>`, `TEAM_SOURCE_EXCLUDED.<source>`, `TEAM_NO_AUTHORS.<source>`, `DUPLICATE.work`, `EMITTED.teams`, `EMITTED_PAIR`, `OVERSIZE_TEAM`, `FLAGGED.oversize-team` |
| Stats | `maxTeamSize`, `maxPairExpansion` (= n(n-1)/2 of the largest team, always the true value) |
| Reducers | `scholarchain.reducers.teams` |
| Outputs | `<run>/teams/{teams,pairs,rejects,stats}` (`normalize/mentions` is the author-paper record keyed by mention ID and work ID) |
| Test fixtures | `PairExpansionSuite` (0, 1, 2, 3, repeated name, 26, 200, 1000 authors), `LocalPipelineSuite` (oversize team at threshold 20 still emits its 325 pairs) |

## 4. blocking (researcher and work blocking, one stage, one run)

| Item | Contract |
|---|---|
| Round 0 input / mappers | `normalize/works` (`BlockingWorkSeedMapper`: one researcher member per mention, keys `researcherKeys(work, mention)`, and one work member, keys `workKeys(work)`) and `normalize/profiles` (`BlockingProfileSeedMapper`: one researcher member per profile, keys `profileKeys(profile)`), via `MultipleInputs`. Records decoded with `Json.decode`. |
| Round 0 map output | key `Text` `<entity>\t0\t<level-0 key>` (entity: `researcher` or `work`); value `Text` `BlockMember` JSON (`entity`, `memberId`, `source`, `recordKey` = work ID or profile ID, all level keys) |
| Round r input / mapper | `blocking/round-<r-1>/spill`; `BlockingSpillMapper` emits `<entity>\t<r>\t<level-r key>` (counted in `REFINE_INPUT`, never in `INPUT`) |
| Key source | `EntityResolutionKeySource` (no-arg) delegates to `EntityResolution.{blockingKeysVersion, blockingLevels, researcherBlockingKeys, profileBlockingKeys, workBlockingKeys}`; keys are nested, so refined blocks never merge across parents. Test fixtures (`FixtureKeySource`, `SingleLevelKeySource`) are used only in plumbing tests. |
| Combiner | none |
| Partitioner | `StableHashPartitioner` |
| Reducer | `BlockReducer`: buffers at most `maxBlockSize + 1` members. Group <= max -> one core `CandidateBlock` built with `CandidateBlock.build` (sorted distinct member IDs, member source datasets, `maxBlockSize`, derived provenance) to `blocks/<entity>/`, plus one `BlockMembership` per member (sorted) to `members/<entity>/`. Group > max -> `OVERSIZE_BLOCK` flag; members with a next-level key go to `spill` (`BLOCK_MEMBERS_REFINED.<entity>`), others are dropped (`BLOCK_MEMBERS_DROPPED.<entity>`) or fail the job (`fail`). A larger reducer is never the strategy. |
| INPUT unit | blocking members (mentions, profiles, works), counted once in round 0: `INPUT` = `ACCEPTED` + `MISSING_KEY` + `FLAGGED.invalid-blocking-key` + `REJECTED` (undecodable lines). `RECORDS_READ.{works,profiles}` counts records. |
| Reject conditions | no key (`missing-key`, flagged), a key with control characters or blank (`invalid-blocking-key`, flagged), oversize group (`oversize-block`, flagged), undecodable record (rejected) |
| Stats | `largestGroup.<entity>` (largest observed blocking group), `largestBlock.<entity>` (largest emitted block) |
| Reducers | `scholarchain.reducers.blocking` per round; rounds <= `levels` (4 for `er-blocking.v2`, `BlockingKeys.Levels`); a round runs only if the previous one spilled |
| Outputs | `<run>/blocking/round-<r>/{blocks/<entity>,members/<entity>,spill,rejects,stats}` |
| Test fixture | `LocalPipelineSuite` (real keys: refinement to levels 2 and 3; fixture keys: drop and fail policies) |

## 5. candidates (candidate feature construction)

Three MapReduce jobs under `<run>/candidates/`:

| Job | Input / mapper | Map output | Reducer and output |
|---|---|---|---|
| `join` | `blocking/round-*/members/<entity>` (`MembershipJoinMapper`), `normalize/works` (`WorkJoinMapper`), `normalize/profiles` (`ProfileJoinMapper`), `MultipleInputs` | key record ID (work ID or profile ID); value `M\t<membership>`, `W\t<work JSON>`, or `P\t<profile JSON>` | `JoinReducer`: at most one record plus its members' memberships (buffer bounded by team size + 1); one `JoinedMember` per membership to `join/joined/` (sorted by block ID, member ID). Missing record or a mention not on its work -> `unresolved-member` rejected. |
| `pairs` | `join/joined` (`BlockPairMapper`) | key block ID; value joined member | `PairFeatureReducer`: at most `maxBlockSize` members (enforced); each prepared ONCE with `MatchFeatures.prepare` (mention with its work, profile, or work); every unordered pair i < j gets `MatchFeatures.researcher` or `MatchFeatures.work` and `MatchFeatures.exactIdAgreements`, built with `MatchCandidate.build` with this block's `BlockKey(level, key)` to `pairs/pairs/` |
| `final` | `pairs/pairs` (`CandidateConsolidateMapper`, decoded with `Json.decode`) | key candidate ID (= canonical pair, blocking and feature versions, run) | `CandidateConsolidateReducer`: exactly ONE `MatchCandidate` per candidate ID to `final/<entity>/`, with the union of every `BlockKey(level, key)` the pair co-occurred in, sorted; copies that differ in anything but block keys are flagged `conflict` (smallest JSON kept) |

- Combiner: none in any of the three jobs. Partitioner: `StableHashPartitioner`. Reducers:
  `scholarchain.reducers.candidates` for each job.
- No score, threshold, confidence, or decision is computed (the core contract forbids them).
  Same-work pairs are kept (`context.same_work` = 1); filtering them is Phase 5 policy.
- Bound on pair work: blocks are capped by `maxBlockSize` = M, so one `pairs` reduce call holds at
  most M members and computes at most M(M-1)/2 pairs; total pairs = sum over emitted blocks of
  size(size-1)/2 <= (members) * (M-1)/2. Per-pair feature cost is bounded by the two records'
  lengths (name Levenshtein, author-sequence LCS: quadratic in team length for work pairs).
- With nested hierarchical keys each member ends in at most one emitted block per entity, so on
  the samples every candidate has exactly one block key; the consolidation job guarantees one
  record per pair regardless (tested with a pair that co-occurs in two blocks).
- Counters: `INPUT.<entity>` (memberships), `ACCEPTED.<entity>` (joined), `RECORDS_READ`,
  `DUPLICATE.record`, `CANDIDATE_PAIRS.<entity>`, `CANDIDATE_MERGED`,
  `CANDIDATE_MULTI_BLOCK.<entity>`, `EMITTED.<entity>-candidates`, `CONFLICT`, `MISSING_KEY`
  (`unresolved-member`), `SCHEMA_FAILURE`.
- Stats: `largestBlockPairs`, `maxBlockKeysPerCandidate.<entity>`.
- Test fixtures: `LocalPipelineSuite` (every block pair becomes one candidate whose block keys
  contain both endpoints; consolidation of a pair seen in two blocks, and of a conflicting copy).

## 7. graph export preparation

No separate job: `normalize/{works,mentions,profiles,venues,institutions,topics}`,
`exact-ids/links`, `teams/{teams,pairs}`, `blocking/round-*/blocks/<entity>`, and
`candidates/final/<entity>` are the partitioned (one part file per reducer), compressed JSON Lines
exports Spark reads through the core contracts.

## Cost notes for EMR and full runs (review task i)

- Pair expansion uses only the 8-argument positional core builder
  `CoauthorPairCandidate.of(mention_i, i, mention_j, j, workId, year, n, provenance)`
  (`jobs/TeamJob.scala:52-61`): O(1) hashing per pair (plus core validation, 2 SHA-256 per pair).
  It is the only `CoauthorPairCandidate.of` overload in core. A 1000-author team expands to
  499,500 pairs in about 3 s in the unit test.
- Remaining risk: a giant team still concentrates n(n-1)/2 pairs in ONE `TeamReducer` call (keyed
  by work) and n copies of its work JSON in the candidate `join` output (one per mention
  membership, O(n^2) bytes before compression). Both are reported (`OVERSIZE_TEAM`,
  `maxPairExpansion`); the reducer reports progress every 10,000 pairs. A 3,000-author team means
  about 4.5 million pairs in one reduce call. Not measured on EMR.

## EMR entry point (for the infra-engineer smoke-run script)

```text
hadoop jar scholarchain-hadoop-jobs-<version>.jar scholarchain.hadoop.pipeline.PipelineDriver \
  --manifest s3://<curated>/manifests/<run>.json --curated-root s3://<curated>/curated \
  --stages normalize,exact-ids,teams,blocking,candidates \
  --dblp s3://<raw>/raw/dblp/snapshot_date=<date>/ \
  --openalex-works ... --openalex-authors ... --openalex-institutions ... \
  --crossref-works ... --orcid s3://<raw>/raw/orcid/snapshot_date=<date>/extracted/
```

Exit codes: 0 success, 1 stage failure or refused overwrite, 2 invalid arguments or manifest. ORCID
input must be the extracted per-record XML files (the upstream tar.gz is not read directly).

## Test fixtures

`DblpScannerSuite` (every split offset, 3-way splits, UTF-8 inside splits, gzip, unterminated,
oversize, comments), `DblpSynchronizationSuite` (D1/D2/D3: ghost records inside comments, PIs,
CDATA, and the DOCTYPE internal subset; unclosed records and an unclosed comment followed by valid
records; every 2-way split offset and every split size 1..64 plus larger, at W = 512 and W = 1 MiB;
splits starting inside each construct; the real `DblpInputFormat` at six split sizes with
identical classified outcomes; raw-record Writable round trip), `DblpMappingSuite` (committed DBLP sample, v3 fields, repeated author name,
missing optional fields, bad links), `SourceNormalizersSuite` (committed OpenAlex, Crossref, ORCID
samples, profiles, assertion source and source-work-id scoping), `PairExpansionSuite`,
`ShuffleAndParamsSuite`, and `LocalPipelineSuite` (the local bounded pipeline over every sample
with real entity-resolution keys and features, LocalJobRunner, 512-byte DBLP splits, determinism,
idempotency, oversize teams, duplicate and conflicting DBLP keys, the commit order, candidate
consolidation, blocking policies, and driver guards). `CrossrefEnvelopeSuite` (every committed DEV
Crossref envelope accepted, content read from `message.items[0]`, bare lines unchanged, every
malformed envelope variant rejected with its reason, and the same through a MapReduce normalize
with counters and versioned rejects) and `ErSplitPipelineSuite` (usage, the dataset-manifest
reader, the D13 option D snapshot tokens and the bulk/wrong/missing-token refusals, the
duplicate-source refusals (openalex bulk+API in both orders, dblp with two snapshots, an exact
duplicate pair, which core decoding already rejects), manifest
checks against the ledgers, split and input guards, retry rule, a hand-checkable block
simulation, and one DEV run end to end whose OpenAlex and Crossref record provenance carries only
the API tokens; held-out samples are never read by a test).

## Local per-split pipeline (ER candidate samples, HD-P5-02)

Entry point `scholarchain.hadoop.local.ErSplitPipelineMain` (test tree, so the Provided Hadoop jars
and forked-JVM options apply), one argument `dev` or `heldout`; anything else exits 2. Everything
else is fixed:

| Item | Value |
|---|---|
| Inputs | `datasets/samples/er-candidates/<split>/{dblp,openalex-works,openalex-authors,crossref-works}/*` (no ORCID, no institutions, never the other split) |
| Manifest | `modules/hadoop/local-runs/er-candidates.<split>.run-manifest.json` (committed) |
| Output | `modules/hadoop/target/local-pipeline/er-candidates/<split>/attempt-<n>/` with `curated/hadoop/snapshot=dblp-2026-10-03/run=<hex>/`, `hadoop-tmp/`, markers, `summary.txt` |
| Repository root | nearest ancestor of the working directory holding `build.sbt` and the samples |

Guards, all before any job runs (exit 2): `inputSnapshots` lists every source at most once,
whatever the snapshot values (HD-P5-05, finding HD-1; core validation only requires distinct
(source, snapshot) pairs, so a repeated source would let different lookups see different snapshots),
checked first so every later lookup is source-unique; the manifest's `scholarchain.input.split`
equals the argument; `scholarchain.input.digest` equals `Canonical.hashFields("scholarchain.hadoop.er-split-
inputs.v1", file1, sha256-1, ...)` over the split's `PROVENANCE.tsv` rows sorted by file; the
`configDigest` equals `Canonical.hashFields("scholarchain.hadoop.local-config.v1", k1, v1, ...)`
over the sorted parameters (the Hadoop pipeline reads no other configuration); the dblp snapshot
equals the split's `release_id` in `DBLP-RELEASE-RANGES.tsv` and `datasets.dblp.snapshot.id` in
`datasets/dataset-manifest.yaml`; no ORCID snapshot. Snapshot tokens of the API-fetched sources
(HD-P5-04, human decision D13 option D): the OpenAlex and Crossref snapshots must equal
`er_candidates.api_snapshots.{openalex,crossref}.snapshot_id` of `datasets/dataset-manifest.yaml`
verbatim (today `openalex-api-2026-11-05`, `crossref-api-2026-11-05`; recorded by
dataset-engineer), and a manifest carrying the bulk snapshot ID `datasets.<source>.snapshot.id`
(`openalex-2026-10-14`, `crossref-public-data-file-2026-03`), another token, or no token is refused
with exit 2. The token must also agree with the split's `PROVENANCE.tsv` retrieval date
(`<source>-api-<UTC date>`). The committed run manifest stays the immutable root of provenance (its
snapshots are part of the run ID); the dataset manifest is read only to verify it, by
`scholarchain.hadoop.local.DatasetManifest`, a fail-closed reader of the manifest's YAML subset
(dotted paths; list items unreachable; duplicate paths, unterminated strings and unreadable lines
are errors). The summary then prints `snapshots <source>=<token> ...`, one `provenance
source=<s> records=<n> snapshots=<tokens> expected=<token>` line per source over every Provenance
object in the normalized works, mentions and profiles, and `provenance
check=every-record-snapshot==manifest-snapshot:<ok|FAILED>`; FAILED makes the run `inconsistent`.
Inputs: the split
root holds only the four input directories; every entry is a regular file (no symbolic link) whose
real path is inside the split root; the file set equals the ledger rows of that split, and every
file's SHA-256 equals its ledger value.

Retry and partial-run rule: each run creates a NEW `attempt-<max+1>` directory (never reused) and
writes `_IN_PROGRESS` first. Success writes `summary.txt` and `_SUCCESS` and removes
`_IN_PROGRESS`; a failed stage or consistency check writes `_FAILED` (with the summary so far). An
attempt with neither `_SUCCESS` nor `_FAILED` (killed or timed out) is partial. On the next start,
partial and failed attempts are printed and left untouched; an attempt with `_SUCCESS` refuses
every further run of the split (exit 2, the message names the directory to move away).

Stages run one after another (normalize, exact-ids, teams, blocking, candidates) through
`Pipeline.run`, each timed. Exit 0 = complete and consistent; 1 = a stage failed or a consistency
check failed. Summary lines (stdout and `summary.txt`) all start with `[er-split] split=<split>`
and are `key=value` tokens: `elapsed stage=<s> ms=<n> status=<ran|skipped|failed>`, `normalize
input=<in> read=<n> accepted=<n>`, one `normalize conflict source=<s> id=<id> disposition=<d>
file=<f> rejectId=<id> detail=<text>` per `conflict` record of normalize, one `normalize duplicate
source=<openalex-work|openalex-author|crossref-work> id=<id> copies=<n> files=<f|f>` per source ID
found in more than one JSON input file (a driver diagnostic: identical copies count `DUPLICATE`
without a record), `normalize conflictRecords=<n> duplicateCounter=<n>
duplicateCopiesNamedFromJsonInputs=<n> allDuplicatesNamed=<bool>`, `distinct.researcher=<n> distinct.work=<n> ...
check=...:<ok|FAILED>`, per entity `oversize entity=<e> cap=<c> policy=<p> groups=<n>
groupsWithDrops=<n> membersDropped=<n> membersRefined=<n> uncappedPairsOfOversizeGroups=<n>
uncappedPairsOfDroppingGroups=<n> pairsInvolvingDroppedMembers=<n> largestOversizeGroup=<n>
droppedByKind=<kind:n,...|none> simulationMatchesRun=<bool>`, `histogram entity=<e> level=0
members=<n> groups=<n> sizes=<size:count,...>`, `capsim entity=<e> cap=<2|3|4|5|6|8|none>
blocks=<n> pairs=<n> oversizeGroups=<n> membersDropped=<n> largestBlock=<n> droppedByKind=...`,
`teams ...`, `elapsed total ms=<n>`, and last `runId=<id> attempt=<dir> status=<complete|
inconsistent|failed> output=<dir>`. Distinct candidate pairs per entity are `EMITTED.<entity>-
candidates`; the check requires their sum to equal `CANDIDATE_PAIRS - CANDIDATE_MERGED` and each to
equal the distinct `candidateId`s read back from `candidates/final/<entity>`. The block-size report
(`histogram`, `capsim`) is a driver-side diagnostic over the run's curated works and profiles with
the same key source; it replays the BlockReducer rules and must reproduce the run's own dropped
members, oversize groups and per-block pairs at the manifest cap (`simulationMatchesRun`), else the
run is `inconsistent`. It is not part of the MapReduce job graph.
