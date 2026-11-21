# Phase 07 requirement checklist (orchestrator, maintained)

Source: README.md at d23d307, Phase 7 work order and gate (L1350-1432); rubric deductions L3059-3060. HEAD at start: 0c975573a2e65e4dbe36c384e156d9e26bae0830, clean tree.

Status values: PLANNED, DONE-LOCAL (implemented, passing in `sbt check` on committed fixtures), AWS-GATE (needs a live AWS run the human approves), OUT (not in Phase 7 scope, tracked elsewhere).

Inputs observed (archive, read-only): run `run:a65f3f38…`, `_parts.json` sha256 `749763ad…`; four `store-load-manifest.v1` files: neptune-bulk-load 24 items (CSV, `graph-node.v2`/`graph-edge.v2`), dynamodb 21 items (Parquet), s3 29 items (Parquet), s3-vectors 4 items (Parquet: `entity-summary-input.v1`, `semantic-summary-input.v1`). Spark emits six relationship types: AUTHORED, COAUTHORED_WITH, PUBLISHED_IN, AFFILIATED_WITH, HAS_TOPIC, RESOLVES_TO. DERIVED_FROM and SUPPORTED_BY are not in the implemented graph model ("as required by the implemented graph model", L1374).

## Local verification (this phase)

| # | Requirement (spec line) | Planned implementation (storage-engineer unless noted) | Local validation |
|---|---|---|---|
| S1 | S3 prefixes raw, curated, graph, semantic-context, reports, logs (L1368) | `ObjectStore` port; `S3Layout` key builder from config (no bucket literal); in-memory and local-filesystem fakes | key layout tests; config refusal of missing prefix |
| S2 | Versioned object keys, run manifests (L1369) | keys embed `runId`/`loadId`; create-once put (`ifNoneMatch`) | rerun = no new objects; differing bytes under same key refused |
| S3 | Content hashes, source references (L1370) | every put records sha256 + source run/path; `ArtifactRegistry` row per object | hash mismatch refused; registry rows match manifest |
| N1 | Bulk-load manifests for Researcher, Work, Venue, Institution, Topic, context nodes (L1373) | `GraphLoader` reads `neptune-bulk-load` manifest; builds Neptune bulk-loader request (format csv, source prefix, failOnError, parallelism) without sending it | request JSON golden test; all 6 vertex roles Spark emits mapped (Researcher, AuthorMention, Work, Venue, Institution, Topic; corrected per SE-P7-01) |
| N2 | Relationships (L1374) | the six Spark relationship labels; DERIVED_FROM / SUPPORTED_BY documented as not produced by the implemented model | label set test against fixture CSV headers |
| N3 | Coauthor-edge properties jointPublicationCount, first/lastCollaborationYear, window, evidence pointer (L1375) | CSV header parser with Neptune `name:Type` columns; required-column check per label | missing column / bad type / dangling endpoint refused |
| N4 | Metrics for bounded lookup (L1376) | metrics served from DynamoDB `ResearcherMetricCache` (justified) or vertex properties; decision recorded | lookup test by researcherId + window |
| N5 | Idempotent IDs, parameterized openCypher (L1377) | `GraphStore` port with fixed allowlisted query templates and parameter maps only; fake executes the same templates in memory | injection string stays a parameter; reload = same vertex/edge sets |
| N6 | Read-only runtime graph credentials (L1378) | `GraphReader` (runtime) vs `GraphLoader` (batch) split ports; runtime composition receives only the reader | type-level test; IAM side is infra-engineer (AWS-GATE) |
| G-ID | Neptune IDs equal Spark IDs (gate L1427) | loader never mints IDs; `~id/~from/~to` copied verbatim, validated with core `StableId.check` | fixture load: every loaded ID set equals the CSV ID set; every edge endpoint exists |
| G-EV | COAUTHORED_WITH traceable to supporting work evidence (gate L1428) | `sourceEvidence` → coauthor-edge record (`edges/*.parquet`, supporting works) → AUTHORED edges | test: every fixture COAUTHORED_WITH resolves to ≥1 work both endpoints AUTHORED; broken pointer refused |
| D1 | Tables RunLedger, EntityLinkCache, QuerySession, EvidenceBundle, ArtifactRegistry, optional ResearcherMetricCache (L1381-1386) | `KeyValueStore` port + per-table schemas (`TableSpec`: PK, SK, GSIs, TTL attribute) | schema tests |
| D2 | Explicit partition and sort keys (L1387) | documented key design per access pattern (e.g. RunLedger PK `run#<runId>` SK `load#<loadId>#<stage>`) | access-pattern tests: each stated query served by key or GSI, no scan |
| D3 | Conditional writes for idempotency (L1388) | `putIfAbsent`, `putIfVersion` (optimistic), `ConditionFailed` outcome; fake enforces same semantics | double put → condition failure handled as no-op when equal, conflict when different |
| D4 | TTL only where disposable (L1389) | TTL on QuerySession and EntityLinkCache only; none on RunLedger, ArtifactRegistry, EvidenceBundle (evidence must resolve) | schema test |
| V1 | Vector bucket and index abstractions (L1392) | `VectorStore` port: index spec inspection (dimension, distance, non-filterable keys; creation is Terraform), put, query, get; no delete (storage never deletes; corrected per SE-P7-01) | fake honours dimension mismatch, unknown index |
| V2 | Stable vector keys, compact filterable metadata (L1393) | key = core stable ID of the source entity + index version; metadata schema per index | duplicate key with different text hash refused; same hash = no-op |
| V3 | Long text in S3, references only in metadata (L1394, gate L1426) | metadata carries `textS3Key`, `textSha256`; size budget check per metadata (limits from current AWS docs) | oversize / long-text metadata refused |
| V4 | Filters researcher, year window, source, topic, institution, community, document type (L1395) | filter model limited to these fields; fake implements equality/range/in | filter tests per field |
| V5 | Batch writes, retry transient failures, record rejected vectors (L1396) | batcher (size cap from service limits), bounded exponential backoff with jitter (injectable clock), `RejectedRecord` rows to RunLedger and an S3 rejects object | fake injects throttling then success; permanent failure → rejected record, run continues within bound |
| V6 | Five indexes researcher-profile-v1, collaboration-community-v1, paper-abstract-v1, collaboration-path-v1, topic-institution-summary-v1 (L1403-1408) | `VectorIndexes` registry with all five names, document types and metadata schemas; loader maps `summary-inputs`/`entity-summary-inputs` rows to index documents (text to S3, vectors pending Phase 8 embeddings) | registry test lists exactly five; each fixture document routed to one index; indexes with no source rows reported, not invented |
| L1 | Loaders validate manifests and part integrity | verify `_parts.json` (core `PartManifests.verify`), `StoreLoadManifest.checkAgainst`, per-part sha256/bytes/rows before any write | missing part, truncated part, flipped byte, wrong manifest hash, extra/unknown role all refused before any write |
| L2 | Idempotent reload (L1430) | RunLedger records `loadId` completion; per-record upserts keyed by stable IDs | second load: zero new writes, identical store state; interrupted load resumes without duplicates |
| L3 | Provenance | each stored record keeps runId, source path, loadId, schema version | provenance-present tests |
| T1 | Required tests (L1411-1417): serialization, idempotent reload, missing/malformed metadata, duplicate vector keys, Neptune edge endpoint and evidence validation, DynamoDB conditional writes, local fakes | munit suites in modules/storage/src/test | `sbt check` exact counts |
| F1 | Fixtures derived from archived outputs | exact copies of the archived run directory files and four load manifests (sha256 checked against the archive's SHA256SUMS.txt) | fixture-integrity test |
| A1 | Offline AWS adapters (authorized offline only) | thin adapters behind each port, compiled and unit-tested with stubbed clients; no network in tests | depends on dependency decision (see below) |

## Live AWS acceptance (AWS-GATE: not done, needs a signed plan)

| # | Criterion | Status |
|---|---|---|
| W1 | `sbt check` plus "the bounded storage integration suite" (L1420; `sbt storageIT`, row inactive B10-alias-pending) run against real Neptune, DynamoDB, S3, S3 Vectors | AWS-GATE |
| W2 | Neptune bulk load succeeds; loaded ID sets equal Spark IDs in the live cluster | AWS-GATE (no Neptune resource exists) |
| W3 | DynamoDB conditional-write behaviour observed live | AWS-GATE |
| W4 | S3 Vectors index create/put/query observed live; limits confirmed | AWS-GATE |
| W5 | Read-only runtime graph credentials (IAM) | AWS-GATE (infra-engineer Terraform) |
| W6 | Cleanup procedure for the four stores | documented in Phase 7; executed only by the human |

## Closeout status (2026-11-21, from CR-P7-20; latest `sbt check` 162 suites / 2006 passed, 3:07:45 AM CST)

| ID | Status | Evidence / reason |
|---|---|---|
| S1 | DONE-LOCAL | S3Layout, S3LayoutSuite. Outstanding: core zone config keys (domain-engineer); live S3 (W1). |
| S2 | DONE-LOCAL | create-once run/load keys; run files stored; rerun 0 new objects. Live S3 outstanding. |
| S3 | DONE-LOCAL | 31 + 249 objects with ArtifactRegistry rows. Text objects probed by HeadObject sha256 only. |
| N1 | DONE-LOCAL (plan only) | NeptuneBulkLoad.plan, 12 requests, golden test. Outstanding: CSV staging to the graph zone (CR-P7-07 n6); fields unverified; nothing sent (W2). |
| N2 | DONE-LOCAL | six labels; DERIVED_FROM/SUPPORTED_BY documented as not produced. |
| N3 | DONE-LOCAL | row contracts; TE GraphIdConsistency. |
| N4 | DONE-LOCAL | DynamoDB MC-1/MC-2 on the fake; live outstanding. |
| N5 | DONE-LOCAL (fake) | TE GraphInjection. Outstanding: Neptune openCypher support; adapter must RETURN and compare (CR-P7-07 m2). |
| N6 | DONE-LOCAL (type level) | RuntimeStoresTypeSuite. Outstanding: IAM (W5). |
| G-ID | DONE-LOCAL | 459 + 799 equal across CSV and Parquet; TE cases. Live cluster outstanding (W2). |
| G-EV | DONE-LOCAL | evidence-trace check; broken pointer refused. |
| D1-D4 | DONE-LOCAL | TableSpecsSuite, ConditionalWritesSuite; no-scan access patterns; TTL only on QuerySession/EntityLinkCache. Terraform tables and live behaviour outstanding (W3). |
| V1 | DONE-LOCAL | describe/put/get/query; no delete. Index Terraform and W4 outstanding. |
| V2 | DONE-LOCAL | TE VectorAdversarial. Phase 8: a new model version under the same key is a Conflict. |
| V3 | DONE-LOCAL | textS3Key/textSha256; oversize refused. AWS limits unverified. |
| V4 | DONE-LOCAL (6 of 7 fields on data) | institution only on synthetic metadata. |
| V5 | DONE-LOCAL | throttle/resume; rejected records. Real PutVectors semantics outstanding. |
| V6 | DONE-LOCAL (synthetic vectors) | 176/51/8/9/5 = 249. Real embeddings Phase 8. |
| L1 | DONE-LOCAL | TE PartIntegrity + storage suites. Outstanding: curated-zone integrity on S3 (no .crc; CR-P7-09 U1). |
| L2 | DONE-LOCAL (one run per store) | TE LoadIdempotency. Multi-run coexistence deferred by the human. |
| L3 | DONE-LOCAL | provenance on graph, registry, analysis rows, vector metadata. |
| T1 | DONE-LOCAL | storage 395, tests 178. Outstanding: `sbt storageIT` (inactive, B10). |
| F1 | DONE-LOCAL | FixtureIntegritySuite, UpstreamFixtureIntegritySuite. Curated fixture CRC32 only. |
| A1 | OUTSTANDING | Deferred by the human. README L1451 puts S3 Vectors write batches in Phase 8 storage work. |
| W1-W6 | OUTSTANDING | AWS-GATE; no resources; W5 infra-engineer IAM; W6 design only. |

Other outstanding cloud deliverables carried from Phase 6 (unchanged): bounded EMR Spark run; Phase 8 ≥10,000 vectors or instructor-approved smaller corpus; Phases 12-15 deploy/scaling/elasticity; release manifest; demo cloud items; resource inventory before/after cleanup. No instructor approval of any deviation has been obtained.
