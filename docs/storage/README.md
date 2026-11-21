# ScholarChain storage layer (Phase 7)

Owner of this page: `documentation-engineer`. Owner of the code: `storage-engineer` (`modules/storage/**`).

**Phase 7 is not complete.** This page covers the bounded local development closeout the human approved on 2026-11-20 ([closeout decision](../agent-runs/phase-07/human-decisions-2026-11-20-closeout.md), [scope proposal](../agent-runs/phase-07/closeout-proposal-2026-11-20.md)). No storage code has run against AWS. No requirement is waived. Every live AWS item is listed as outstanding in [Phase 7 status](#5-phase-7-status).

State described: the working tree on HEAD `0c975573a2e65e4dbe36c384e156d9e26bae0830` as of 2026-11-21. All storage sources, tests and fixtures are still **untracked** (`git status`: `?? modules/storage/src/main/scala/scholarchain/`, `?? modules/storage/src/test/...`). They become part of the repository only when the human commits them.

Phase 9-11 consumers should read [phase-09-11-handoff.md](phase-09-11-handoff.md).

## How to read this page

Each statement belongs to one of three classes.

| Label | Meaning | Evidence required |
|---|---|---|
| **Implemented** | The code exists in the working tree. | The cited source file. |
| **Locally verified** | Tests over in-memory fakes and the committed-to-be fixtures exercise it, and the last `sbt check` that the human observed passed. | An evidence file under `docs/agent-runs/phase-07/` with the counts. |
| **Unverified / outstanding** | Not run against AWS, not implemented, or deferred. | The decision or finding that records it. |

Local fakes and tests are development steps. They do not show that the real Neptune, DynamoDB, S3 or S3 Vectors integrations work ([work order](../agent-runs/phase-07/prompt.md)).

**Latest observed definition of done.** The human reviewed the test marker of an `sbt check` run (started by test-engineer for TE-P7-01) that completed with `[success]` and exit code 0 at 3:07:45 AM CST on 2026-11-21. The run reported 162 suites and 2006 tests passed, 0 skipped. Per project: core 621, entityResolution 464, hadoop 85, sparkAnalytics 263, storage 395, tests 178; rag, root and services 0; all Failed 0, Errors 0. It took 10291 s; the tests JVM logged at DEBUG level (about 5.4 million output lines), and an earlier `sbt test` over all projects in the same work order took 378 s. The logging fix was deferred by the human ([te-p7-01-and-human-review](../agent-runs/phase-07/te-p7-01-and-human-review.md)). The documentation engineer did not replay this command (see [How to run locally](#6-how-to-run-locally)).

---

## 1. Architecture

### 1.1 Packages

All paths are under `modules/storage/src/main/scala/scholarchain/storage/`.

| Package | Role | Implemented |
|---|---|---|
| `port` | Store interfaces (ports), runtime composition (`RuntimeStores`), source, embedder, clock, sleeper and jitter ports | yes |
| `model` | Errors, IDs, S3 layout, DynamoDB table specs and keys, item codecs, graph query and write templates, vector model, rejected records, ledger states | yes |
| `load` | Verification (`LoadVerifier`), gate (`LoadGate`), ledger protocol (`LoadLedger`), the four loaders, upstream readers, the entry point `OpeningLoad` | yes |
| `parquet` | Parquet footer and row reader with a pure-Scala SNAPPY decoder (`SnappyRaw`). Other codecs are refused. | yes |
| `local` | `LocalRunSource`: a bounded filesystem reader that never follows links | yes |
| `fake` | In-memory stores, recording wrappers and a synthetic test embedder. They live in **main**, so rag, services and tests code can use them. | yes |
| `aws.*` (S3, DynamoDB, Neptune data, S3 Vectors SDK adapters) | The concrete AWS adapters | **no: deferred** (see [1.3](#13-aws-adapters-deferred)) |

### 1.2 Ports

Source: [`port/`](../../modules/storage/src/main/scala/scholarchain/storage/port/).

| Port | Methods | Notes |
|---|---|---|
| `ObjectReader` / `ObjectWriter` / `ObjectStore` | `head`, `get`, `list(bucket, prefix, maxKeys)`; `putCreateOnce(ref, body, contentType)` | No overwrite and no delete. Listing is always bounded. |
| `KeyValueReader` / `KeyValueWriter` / `KeyValueStore` | `get`, `query(KeyQuery)`; `putIfAbsent`, `putIfVersion` | No Scan anywhere. `BatchWriteItem` is never used for loads, because it cannot carry a condition. |
| `GraphReader` | `run(BoundQuery)` | Accepts only a bound, allowlisted template. |
| `GraphWriter` | `write(BoundGraphWrite)`, `storedVertex(id)`, `storedEdge(id)` | Loader only. Create-once. The two probe methods serve the pre-write probe. |
| `GraphLoader` | `submit(BulkLoadRequest, dependencies)`, `status(handle)` | Neptune bulk loader. No implementation exists (adapter deferred). |
| `VectorReader` / `VectorWriter` / `IndexInspector` / `VectorStore` | `query`, `getVectors`; `putVectors`; `describe` | No delete. Index creation is left to Terraform. |
| `RunSource` | `listFiles`, `read`, `readAtMost` | Bounded. Refuses symbolic links and non-regular files. |
| `Embedder` | `modelVersion`, `dimension`, `embed` | Phase 7 tests use only the synthetic embedder. |
| `Clock`, `Sleeper`, `Jitter` | injectable time, backoff and jitter | Used by retry tests. |

`StoreError` has six cases ([`model/StoreError.scala`](../../modules/storage/src/main/scala/scholarchain/storage/model/StoreError.scala)): `Transient` (the only retryable case), `Permanent`, `Conflict`, `StaleVersion`, `Corrupt` and `Refused`. `PutOutcome` is `Written` or `AlreadyPresent`.

### 1.3 AWS adapters (deferred)

No `scholarchain.storage.aws.*` package exists in the working tree. The human deferred the concrete AWS SDK adapters and the packaging of the deployed loader until before deployment. They are tracked as outstanding, not waived ([closeout decision](../agent-runs/phase-07/human-decisions-2026-11-20-closeout.md)). Until they exist, no code path leads to the real stores.

Port Scaladoc describes the intended adapter behaviour, but nothing implements it yet:

- S3: `If-None-Match: *` with `ChecksumSHA256`, then HEAD and compare on a precondition failure.
- DynamoDB: `attribute_not_exists` with `ALL_OLD`.
- Neptune: fixed parameterized lookups.

### 1.4 Local fakes

Source: [`fake/`](../../modules/storage/src/main/scala/scholarchain/storage/fake/).

- `InMemoryGraphStore`, `InMemoryKeyValueStore`, `InMemoryObjectStore` and `InMemoryVectorStore` enforce the same create-once, conditional-write and compare-on-put semantics as the ports.
- `Recording*` wrappers count calls. Their counters are private with read-only accessors (risk T4 fix, [risk triage](../agent-runs/phase-07/risk-triage-2026-11-20.md)).
- `SyntheticTestEmbedding` labels every vector with the model version `synthetic-test-only.v1` ([cr-p7-10-and-se-p7-09](../agent-runs/phase-07/cr-p7-10-and-se-p7-09.md)).
- The fakes are not thread-safe ([se-p7-02-report](../agent-runs/phase-07/se-p7-02-report.md), RISKS).

### 1.5 Load flow

The single production entry point is `OpeningLoad.run(sources, config, targets): OpeningLoadResult` ([`load/OpeningLoad.scala`](../../modules/storage/src/main/scala/scholarchain/storage/load/OpeningLoad.scala)).

0. **Reader classpath (runs first, SE-P7-13, SE-P7-14).** Before anything is read, `OpeningLoad` loads one class per Parquet reader library jar without initialising it (`OpeningLoad.ReaderJars`, six entries: parquet-hadoop `ParquetFileReader`, parquet-common `InputFile`, parquet-column `ColumnIOFactory`, parquet-encoding `BytePacker`, parquet-format-structures `FileMetaData`, hadoop-client-api `Configuration`). `OpeningLoadSuite` checks that each class loads from its named jar. These libraries are Optional-scope dependencies of storage, so they are absent from dependent modules unless those modules add them (see [6](#6-how-to-run-locally)). Classpath failures are never thrown out of `OpeningLoad.run` ([`load/OpeningLoad.scala`](../../modules/storage/src/main/scala/scholarchain/storage/load/OpeningLoad.scala); reviewed APPROVE in CR-P7-18, [cr-p7-18-and-be-p7-08](../agent-runs/phase-07/cr-p7-18-and-be-p7-08.md)):
   - a `ReaderJars` class that does not load (`ClassNotFoundException` or `LinkageError`) refuses every store with check `reader-classpath`: nothing is read or written, and `runtime` is `None`;
   - a `LinkageError` raised before any write (while the upstream runs and benchmark list are read, the run is verified, or the store owners are checked; this phase only reads, the RunLedger through a read-only view) refuses every store the same way: zero writes, `runtime` `None`;
   - a `LinkageError` raised inside one store's loader fails that store only (`failed`, a `Permanent` error starting `reader-classpath:`, with the writes this call made so far counted as for any other failure). Its RunLedger row may stay `writing`; the next call moves it to `failed` and resumes. The other stores continue, and `runtime` is `None`.

   Only `LinkageError` is caught; other fatal errors (`VirtualMachineError`, `InterruptedException`) are not. A `LinkageError` that does not come from Parquet is also labelled `reader-classpath` (CR-P7-18 R2).
1. **Upstream reads.** It reads three upstream inputs through the production readers ([`load/UpstreamRuns.scala`](../../modules/storage/src/main/scala/scholarchain/storage/load/UpstreamRuns.scala), [`load/BenchmarkPairs.scala`](../../modules/storage/src/main/scala/scholarchain/storage/load/BenchmarkPairs.scala)):
   - the grouping run, verified like an analysis run against its `_parts.json`;
   - the curated Hadoop run, verified per chunk against its Hadoop CRC32 `.crc` sidecars, which are not cryptographic, plus `_SUCCESS` and `_counters.json` per stage;
   - the benchmark pair list. Its core digest must equal the run manifest's `benchmarkPairsDigest`.

   Any upstream problem refuses every store with zero writes (check `upstream-read`).
2. **Structural verification.** `LoadVerifier.verify` checks the analysis run directory and its load-manifest directory ([`load/LoadVerification.scala`](../../modules/storage/src/main/scala/scholarchain/storage/load/LoadVerification.scala)):
   - it lists the directory within bounds;
   - it requires `_SUCCESS`;
   - it decodes `_parts.json` and records its SHA-256;
   - it allows one publication per run ID;
   - it binds `_run-manifest.json` to the part manifest;
   - it checks the exact file set, byte counts, SHA-256 and LF rows;
   - it scans CSV parts as strict RFC 4180, with row counts equal to the declared rows;
   - it requires one load manifest per store, checked against the part manifest;
   - it requires each Neptune CSV header to equal the core layout byte for byte.
3. **Record-level checks** (the deferred checks, [1.6](#16-verification-gate-and-deferred-checks)). They run with a `VerificationContext`: the analysis settings, Parquet bounds and the bound upstream.
4. **Gate and loaders.** For each target store in `TargetStore` order, `LoadGate.proceed` hands the loader a `LoadPermit` only when all of these hold:
   - the report has no rejection and holds a verified run;
   - the run has a load manifest for that store;
   - every deferred check that applies to the store has passed.

   A rejection anywhere in the report refuses every store.
5. **Runtime view.** The result holds the deterministic `OpeningLoadReport`. It holds a `RuntimeStores` only when every store is `loaded` or `skipped-already-loaded` (`runtime: Option[RuntimeStores]`, CR-P7-10 R5).

`VerifiedRun` and `LoadPermit` are plain classes with `private[load]` constructors. Outside package `load` they cannot be built, copied or summoned through a `Mirror`, so no caller can mark a check passed ([se-p7-04-report](../agent-runs/phase-07/se-p7-04-report.md), constructor surfaces).

What a permit can read ([`load/LoadVerification.scala`](../../modules/storage/src/main/scala/scholarchain/storage/load/LoadVerification.scala), `LoadPermit`):

- `readItem` reads only its own store's load items. Each read is checked again against the verified `_parts.json` entry at read time, by byte count and SHA-256. Any other path is refused without a read.
- An `s3` permit also reads the two run files through `runFileNames`, `runFileSchema` and `readRunFile` (SE-P7-13 item 2). `_parts.json` is re-read and re-verified against the verified part-manifest SHA-256. `_run-manifest.json` is re-read and checked against its `_parts.json` entry (byte count and SHA-256). `ObjectLoad` reads and re-verifies both run files on every call, before the ledger decides whether the load is skipped, so a rerun that ends `skipped-already-loaded` also re-reads them (CR-P7-16 n2; `ObjectLoad.load`, `runFileElements`). For every other store `runFileNames` is empty, and `readRunFile` refuses any path without a read.

### 1.6 Verification gate and deferred checks

There are nine deferred checks (`enum DeferredCheck`, [`load/LoadVerification.scala`](../../modules/storage/src/main/scala/scholarchain/storage/load/LoadVerification.scala)).

| Check | What it verifies |
|---|---|
| `parquet-rows` | Footer rows, decoded rows and declared rows are equal for every Parquet part. |
| `row-contracts` | Every row decodes through its core record contract, and StableId checks pass. Neptune CSV rows are checked by `NeptuneRows`. |
| `graph-ids` | Neptune CSV IDs are copied verbatim, no `~id` is duplicated, and every `~from`/`~to` is a vertex of the run with an allowed label. |
| `graph-id-parity` | The Neptune CSV rows equal the Spark `graph-nodes` / `graph-edges` Parquet records. |
| `evidence-trace` | Each `COAUTHORED_WITH` `sourceEvidence` resolves to its coauthor-edge record, and both endpoints `AUTHORED` the supporting works. Evidence-path hops resolve. |
| `summary-evidence` | Summary inputs cite only stored records and state their values. |
| `analysis-recompute` | The analysis outputs equal the core recomputation from the grouping run. |
| `canonical-tables` | `graph-nodes` / `graph-edges` equal `CanonicalGraph.expected` of the grouping and curated runs. |
| `paths-run-chain` | Evidence paths are exactly the shortest paths of the benchmark pairs, and every record's lineage is the run chain. |

Which checks apply to which store (`DeferredCheck.appliesTo`, verified against the source):

| Store | Checks that must pass before any write |
|---|---|
| `neptune-bulk-load` | row-contracts, graph-ids, graph-id-parity, evidence-trace, analysis-recompute, canonical-tables |
| `dynamodb` | parquet-rows, row-contracts, evidence-trace, analysis-recompute, paths-run-chain |
| `s3` | parquet-rows, row-contracts, graph-id-parity, evidence-trace, summary-evidence, analysis-recompute, canonical-tables, paths-run-chain |
| `s3-vectors` | parquet-rows, row-contracts, summary-evidence |

The human chose to keep analysis-recompute and canonical-tables on Neptune (CR-P7-08 M2, [human-review-and-benchmark-copy](../agent-runs/phase-07/human-review-and-benchmark-copy-2026-11-20.md)).

**Locally verified:** on the real fixtures, every check is performed by the verifier and none stays open for any store. Nothing is marked passed by hand ([cr-p7-09-and-se-p7-08](../agent-runs/phase-07/cr-p7-09-and-se-p7-08.md); `OpeningLoadSuite`, "item 2"). One identity check, `checkIdentity`, is not run by any deferred check, because it needs the ER decision run, which is not in the fixtures ([se-p7-07-and-cr-p7-08](../agent-runs/phase-07/se-p7-07-and-cr-p7-08.md)).

### 1.7 Ledger and ownership protocol

Every loader follows `LoadLedger` ([`load/LoadLedger.scala`](../../modules/storage/src/main/scala/scholarchain/storage/load/LoadLedger.scala)).

0. **Store owners.** The loader reads the store-owner row (`store#<targetStore>` / `owner`, RL-6) of every physical store it writes:

   | Loader | Physical stores it writes |
   |---|---|
   | graph | neptune |
   | key-value | dynamodb |
   | object | s3 and dynamodb (its registry rows) |
   | vector | s3-vectors, s3 and dynamodb |

   A row that names another run, or another `_parts.json` SHA-256, refuses the load with zero writes, the ledger included. The loader then claims the absent rows with `putIfAbsent`. `OpeningLoad` also reads all four owner rows first.
1. **Publication.** The loader claims the run's publication row (`run#<runId>` / `publication`) with `putIfAbsent`. A conflict is decided by `partManifestSha256` alone.
2. **Load row.** It creates or adopts the load row (`load#<loadId>`). A row that is already `loaded` ends the call with zero writes (`skipped`).
3. **Writing.** It moves the row to `writing` with `putIfVersion`. A row found in `writing` is first marked `failed`, so every write has its own generation.
4. **Probe, then write.** It probes every element before the first data write: any stored element that differs gives a `Conflict` and zero data writes. Then it writes create-once.
5. **Loaded.** It moves the row to `loaded` with the counts.

**Limitation (human decision 2026-11-20, option b): one analysis run per local store instance.** Load another run into fresh stores with a fresh RunLedger ([human-review-se-p7-08](../agent-runs/phase-07/human-review-se-p7-08-2026-11-20.md)). Multi-run coexistence and switching the active run are deferred. The code records what this does not guarantee:

- A **fresh** ledger in front of populated stores has no owner rows. The stores are then protected only by the per-key probes, so a run whose keys collide with none of the stored keys would load and mix.
- Check, claim, probe and write are not one atomic step.

### 1.8 Idempotency

- Element keys are Spark's stable IDs (graph `~id`, analysis records, `suminput:` IDs). Storage never mints an ID.
- Every write is create-once. An equal element is `AlreadyPresent`. A different one is `Conflict`, which is never an overwrite.
- A rerun of a loaded load writes nothing. A rerun against a fresh ledger finds every element `AlreadyPresent`. An interrupted load resumes without duplicates.

The counts are in [5.2](#52-locally-verified-counts).

### 1.9 Provenance

- Graph elements carry `GraphProvenance`: runId, loadId, sourcePath and recordSchemaVersion ([`model/GraphWrites.scala`](../../modules/storage/src/main/scala/scholarchain/storage/model/GraphWrites.scala)).
- ArtifactRegistry rows carry sha256, byteCount, runId, loadId, sourcePath and recordSchemaVersion ([se-p7-06-report](../agent-runs/phase-07/se-p7-06-report.md)).
- Analysis-results rows hold the core record JSON plus its sha256 and provenance ([se-p7-07-and-cr-p7-08](../agent-runs/phase-07/se-p7-07-and-cr-p7-08.md)).
- Every vector carries runId, loadId, sourcePath, schemaVersion and modelVersion in its metadata.

### 1.10 Failure handling

- **Errors.** Any verification failure refuses every store before any write.
- **Retry.** Vector puts use bounded exponential backoff with full jitter (`RetryPolicy`). The sleep after attempt `n` is the jitter value, clamped to `0..min(maxDelay, base * 2^(n-1))`. At most `maxAttempts` attempts are made. Only `Transient` errors are retried.
- **Rejected records** (schema `scholarchain.storage.rejected-record.v2`, [`model/RejectedRecord.scala`](../../modules/storage/src/main/scala/scholarchain/storage/model/RejectedRecord.scala)) have eight frozen reasons: `malformed-record`, `missing-metadata`, `malformed-metadata`, `oversize-metadata`, `duplicate-key-conflict`, `dimension-mismatch`, `permanent-write-failure`, `retries-exhausted`.
  - They are written per write generation: RunLedger rows `reject#<loadId>#<generation:10 digits>#<recordKey>`, plus `reports/loads/<loadHex>/rejects/part-<generation:5 digits>.jsonl`.
  - A `RejectionSet` summary row (`rejectset#<loadId>#…`, RL-8) is written last and marks that generation's set as current.
  - `maxRejected` bounds how many rejections a load may have and still finish.

---

## 2. Stores, schemas and keys

### 2.1 Neptune

**Vertex labels (6):** `Researcher`, `AuthorMention`, `Work`, `Venue`, `Institution`, `Topic`. `TextChunk` and `DatasetSnapshot` are runtime-only and are not loaded ([se-p7-01-design](../agent-runs/phase-07/se-p7-01-design.md)).

**Edge labels (6):** `AUTHORED`, `COAUTHORED_WITH`, `PUBLISHED_IN`, `AFFILIATED_WITH`, `HAS_TOPIC`, `RESOLVES_TO`. The implemented graph model does not produce `DERIVED_FROM` and `SUPPORTED_BY`, which the specification requires only "as required by the implemented graph model" (README L1374), so they are refused ([requirements-checklist](../agent-runs/phase-07/requirements-checklist.md), N2).

The CSV headers below are copied from the fixture files `modules/storage/src/test/resources/fixtures/phase06-run-1-local/run=a65f…8c40/<role>/part-00000.csv`.

| Role directory | Header |
|---|---|
| `researcher-vertices` | `~id,~label,anchorNamespace:String,identityStatus:String,memberCount:Int,runId:String` |
| `author-mention-vertices` | `~id,~label,assignmentStatus:String,position:Int,workId:String,runId:String` |
| `work-vertices` | `~id,~label,graphEligibility:String,groupSize:Int,year:Int,runId:String` |
| `venue-vertices`, `institution-vertices`, `topic-vertices` | `~id,~label,name:String,runId:String` |
| `coauthored-with-edges` | `~id,~from,~to,~label,firstCollaborationYear:Int,jointPublicationCount:Int,lastCollaborationYear:Int,windowEndYear:Int,windowStartYear:Int,sourceEvidence:String,runId:String` |
| `authored-edges` | `~id,~from,~to,~label,position:Int,sourceEvidence:String,runId:String` |
| `resolves-to-edges` | `~id,~from,~to,~label,assignmentStatus:String,sourceEvidence:String,runId:String` |
| `published-in-edges`, `affiliated-with-edges`, `has-topic-edges` | `~id,~from,~to,~label,sourceEvidence:String,runId:String` |

These are the edge directions that the runtime templates use ([`model/GraphQueries.scala`](../../modules/storage/src/main/scala/scholarchain/storage/model/GraphQueries.scala)):

- `Researcher-AUTHORED->Work`
- `Work-PUBLISHED_IN->Venue`
- `Work-HAS_TOPIC->Topic`
- `Researcher-AFFILIATED_WITH->Institution`
- `AuthorMention-RESOLVES_TO->Researcher`
- `Researcher-COAUTHORED_WITH-Researcher` (matched in either direction)

**Coauthor-edge properties** (README L1375): `jointPublicationCount`, `firstCollaborationYear`, `lastCollaborationYear`, the window (`windowStartYear`, `windowEndYear`) and the evidence pointer (`sourceEvidence`).

**Researcher metrics** (N4) are not vertex properties. They are served from the DynamoDB analysis-results table (MC-1, MC-2), as recorded in the `ResearcherMetricCache` justification in [`model/TableSpecs.scala`](../../modules/storage/src/main/scala/scholarchain/storage/model/TableSpecs.scala).

**Writes.** There is one allowlisted, parameterized write template per label: 6 vertex and 6 edge templates. Each is a `MERGE ... ON CREATE SET ... += $properties, += $provenance` with no data in its text ([`model/GraphWrites.scala`](../../modules/storage/src/main/scala/scholarchain/storage/model/GraphWrites.scala)). These templates have no `RETURN`, so the deferred Neptune adapter must return and compare the stored element (CR-P7-07 m2, open; [se-p7-07-and-cr-p7-08](../agent-runs/phase-07/se-p7-07-and-cr-p7-08.md)).

**Runtime reads.** There are nine fixed read-only templates: `ResearcherById`, `DirectCollaborators`, `CoauthorEdgeBetween`, `SharedWorks`, `WorksOfResearcher`, `WorkVenue`, `WorkTopics`, `ResearcherInstitutions` and `MentionResolution`. They are bound only through `BoundQuery.bind`, which has a private constructor. IDs are validated by core kind, years by range, and limits against `1..maxLimit`. Neptune support for `id()`, `DISTINCT`, `ORDER BY` and a parameterized `LIMIT` is **unverified**.

**Bulk load.** `NeptuneBulkLoad.plan` is a pure function. Given a Neptune permit, it builds one request per role directory: vertex roles first, then edge roles, with every edge request depending on every vertex request. It sends nothing. For the fixture this is 12 requests, asserted by a golden test ([se-p7-05-report](../agent-runs/phase-07/se-p7-05-report.md), B6; confirmed in SE-P7-13 item 4, [du-p7-04-and-se-p7-13](../agent-runs/phase-07/du-p7-04-and-se-p7-13.md)). The request field names are recalled from memory and **unverified** against AWS documentation.

The plan expects the CSV parts under `<graph>/runs/<runHex>/<role>/`. Copying them there is **not implemented**. The s3 load holds the 29 Parquet items plus the 2 run files (`_parts.json`, `_run-manifest.json`), and no Neptune CSV part. A separate staging-ledger design was proposed but not built (CR-P7-07 n6, still open; [cr-p7-09-and-se-p7-08](../agent-runs/phase-07/cr-p7-09-and-se-p7-08.md), [du-p7-04-and-se-p7-13](../agent-runs/phase-07/du-p7-04-and-se-p7-13.md)).

### 2.2 DynamoDB

There are six logical tables. Physical names come from configuration in the deferred adapter. Each has a string `pk` and a string `sk` ([`model/TableSpecs.scala`](../../modules/storage/src/main/scala/scholarchain/storage/model/TableSpecs.scala)).

| Table | Runtime-writable | TTL | GSIs | Access patterns |
|---|---|---|---|---|
| RunLedger | no | none (audit and idempotency record) | `byLoad` | RL-1 publication; RL-2 loads of a run; RL-3 one load; RL-4 rejected records (all generations); RL-7 one generation's rejected records; RL-8 rejection-set summaries; RL-5 rows of a load ID (`byLoad`); RL-6 store owner (`store#<targetStore>` / `owner`) |
| ArtifactRegistry | no | none (evidence pointers must resolve) | `bySha256` | AR-1 one object (`run#<runId>` / `object#<zone>#<key>`); AR-2 every object of a run; AR-3 objects of a run in one zone; AR-4 objects with a given content hash |
| EvidenceBundle | yes | none (cited evidence IDs must resolve) | `byQuery`, `byEvidence` | EB-1 bundle; EB-2 bundle items; EB-3 bundles of a query; EB-4 resolve an evidence ID |
| QuerySession | yes | `expiresAt` | none | QS-1 session; QS-2 plan steps in order |
| EntityLinkCache | yes | `expiresAt` | `byResearcher` | EL-1 candidates of a surface form; EL-2 one link; EL-3 surface forms of a researcher |
| ResearcherMetricCache (the analysis-results table) | no | none | `byGroup`, `byPair`, `rank` | MC-1 to MC-8, below |

The analysis-results table uses the README's optional `ResearcherMetricCache`. It is justified by the access patterns below and holds 12 roles ([se-p7-07-and-cr-p7-08](../agent-runs/phase-07/se-p7-07-and-cr-p7-08.md)).

| ID | Pattern | Index | Partition | Sort |
|---|---|---|---|---|
| MC-1 | every metric row of a subject | table | `subject#<subjectId>` | begins `metric#` |
| MC-2 | one metric role of a subject in one window | table | `subject#<subjectId>` | `metric#<role>#<SSSS-EEEE>` |
| MC-3 | members of a component or community | `byGroup` | `group#<groupId>#<SSSS-EEEE>` | whole partition |
| MC-4 | direct and indirect connectivity of two researchers in a window | `byPair` | `pair#<first>#<second>` | begins `window#<SSSS-EEEE>#` |
| MC-5 | top-k subjects of a ranked role in a window | `rank` | `rank#<role>#<SSSS-EEEE>` | range `<rank:10 digits>#` |
| MC-6 | window-level results | table | `window#<SSSS-EEEE>` | begins `result#` |
| MC-7 | temporal change of a researcher between two windows | table | `subject#<researcherId>` | `metric#temporal-changes#<SSSS-EEEE>#<SSSS-EEEE>` |
| MC-8 | resolve an evidence ID to its record | table | `subject#<id>` | begins `metric#<role>#` |

Behaviour of the keys and writes:

- Keys carry no run ID. This follows from the one-run-per-store decision, which the RL-6 owner row enforces.
- Conditional writes go through `putIfAbsent`. An equal `recordSha256` gives `AlreadyPresent`; a different one gives `Conflict`. `putIfVersion` gives `StaleVersion` on a lost race. TTL applies to QuerySession and EntityLinkCache only.
- The largest analysis item measured was 3617 bytes, and a test now asserts this value (SE-P7-13 item 4). The number is storage's own size estimate. Whether DynamoDB accounts item size the same way is **unverified**. The 400 KB item limit is also **unverified**, and it is set in configuration as `KeyValueLoadLimits.maxItemBytes` ([se-p7-07-and-cr-p7-08](../agent-runs/phase-07/se-p7-07-and-cr-p7-08.md), [du-p7-04-and-se-p7-13](../agent-runs/phase-07/du-p7-04-and-se-p7-13.md)).

### 2.3 Ordinary S3

The layout has six zones: `raw`, `curated`, `graph`, `semantic-context`, `reports` and `logs`. Each zone gets a bucket and a non-empty prefix from configuration. No bucket name is a literal, and overlapping prefixes are refused ([`model/S3Layout.scala`](../../modules/storage/src/main/scala/scholarchain/storage/model/S3Layout.scala)). Keys embed the 64-hex body of a core run or load ID:

- raw snapshots: `<raw>/snapshots/<source tag>/<snapshot>/<relative path>`
- run outputs: `<zone>/runs/<runHex>/<relative path>` (curated, graph, semantic-context)
- load artifacts: `<zone>/loads/<loadHex>/<relative path>` (graph, semantic-context, reports, logs)
- context text: `<semantic-context>/loads/<loadHex>/text/<chunkHex>.txt`
- rejects: `<reports>/loads/<loadHex>/rejects/part-<NNNNN>.jsonl`
- run files (SE-P7-13 item 2): `<graph>/runs/<runHex>/_parts.json` and `<graph>/runs/<runHex>/_run-manifest.json`

`ObjectLoad` ([`load/ObjectLoad.scala`](../../modules/storage/src/main/scala/scholarchain/storage/load/ObjectLoad.scala)) writes each s3 load item under `runObject`. Summary inputs go to `semantic-context`; every other analysis output goes to `graph`. Each object gets one ArtifactRegistry row.

What the s3 load stores and what it does not (SE-P7-13 items 2 and 3, [du-p7-04-and-se-p7-13](../agent-runs/phase-07/du-p7-04-and-se-p7-13.md)):

- **Run files are stored.** After the s3 load items, `ObjectLoad` writes the run's `_parts.json` and `_run-manifest.json` create-once in the graph zone, each with one ArtifactRegistry row and content type `application/json`. Every key is built, and both run files are read and re-verified, before the first write. A run file that no longer verifies stops the load with zero writes.
- **Load manifests are not stored as files.** Each load's identity (load ID, store, `_parts.json` SHA-256, item count, state and counts) is its RunLedger row.
- **There is no load-report key.** `S3Layout.loadReport` was removed, and a compile test asserts that it is gone. The durable record of a load is its RunLedger row plus one ArtifactRegistry row per stored object. The reason given in the code: an S3 copy of the in-memory `OpeningLoadReport` would be a second record of the same facts, and it could not be create-once, because its per-call fields (`loaded` against `skipped-already-loaded`, `written`) differ between a load and its rerun ([`model/S3Layout.scala`](../../modules/storage/src/main/scala/scholarchain/storage/model/S3Layout.scala)).

Live S3 is not used. The integrity design for real cloud curated runs is also outstanding: EMR output on S3 has no `.crc` sidecars, so the curated reader would refuse every real cloud curated run (CR-P7-09 U1, [cr-p7-09-and-se-p7-08](../agent-runs/phase-07/cr-p7-09-and-se-p7-08.md)).

### 2.4 S3 Vectors

There are exactly five indexes, in README order ([`model/Vectors.scala`](../../modules/storage/src/main/scala/scholarchain/storage/model/Vectors.scala)).

| Index | Document types routed | Index-specific required fields | Fixture rows |
|---|---|---|---|
| `researcher-profile-v1` | researcher-profile | researcherIds, windowStart, windowEnd, identityStatus, anchorCollision | 176 |
| `collaboration-community-v1` | community-summary | communityId, windowStart, windowEnd | 51 |
| `paper-abstract-v1` | work-summary, work-abstract | none | 8 (all work-summary; the run has no work-abstract rows) |
| `collaboration-path-v1` | path-summary, collaboration-summary (provisional routing) | windowStart, windowEnd | 9 |
| `topic-institution-summary-v1` | topic-summary | none | 5 (core has no institution document type; no institution rows exist) |

Total: 249 rows (176 + 51 + 8 + 9 + 5) ([cr-p7-10-and-se-p7-09](../agent-runs/phase-07/cr-p7-10-and-se-p7-09.md)).

**Metadata fields.** Every index requires the 13 common fields. Metadata schema: `scholarchain.storage.vector-metadata.v2`.

- Filterable: `documentType`, `subjectId`, `runId`, `indexVersion`, `sourceDataset`.
- Non-filterable: `textS3Key`, `textSha256`, `modelVersion`, `dataSha256`, `loadId`, `inputId`, `schemaVersion`, `sourcePath`.
- Optional filterable fields, per index: `inputSources`, `researcherIds`, `communityId`, `topicIds`, `institutionIds`, and the window fields.

No field holds text. Long text goes to ordinary S3 under `textS3Key`, and its sha256 is recorded in `textSha256` (V3).

**Keys.**

- Vector key: `<index name>/<suminput ID>`. Another text under the same key is a `Conflict`, and equal text is `AlreadyPresent` (V2).
- Text chunk ID: core `ChunkId.ofTextHash(subjectId, index, documentType, textSha256)`.

**Filters** (V4): `Eq`, `In`, `Range` (integer fields) and `And`, over the filterable fields an index allows. Researcher, year window, source, topic, community and document type were tested on loaded data. **Institution was tested only on synthetic metadata**, because no fixture row carries institution IDs.

**Limits.** Every limit comes from configuration with no default (`VectorLimits`, `VectorLoadConfig`), and every S3 Vectors service limit is **unverified**, because the aws-docs MCP server was unavailable. The per-value cap of 1024 bytes is ScholarChain's own guard, accepted by the human, and is not an AWS limit ([human-review-se-p7-09](../agent-runs/phase-07/human-review-se-p7-09-2026-11-20.md)).

**Index creation** is left to Terraform (infra-engineer) and has not been written. Storage only inspects an index (`describe`) and checks its dimension, distance and non-filterable keys before writing.

---

## 3. Vector integrity design and its limits

Implemented in SE-P7-11 and SE-P7-12. Reviewed APPROVE in CR-P7-13 ([cr-p7-12-and-se-p7-11](../agent-runs/phase-07/cr-p7-12-and-se-p7-11.md), [cr-p7-13-and-be-p7-05](../agent-runs/phase-07/cr-p7-13-and-be-p7-05.md), [cr-p7-14-and-se-p7-12](../agent-runs/phase-07/cr-p7-14-and-se-p7-12.md)).

- **Digest.** `dataSha256` is the lowercase hex SHA-256 over:
  1. the UTF-8 header `scholarchain.vector-data.v1\n<dimension>\n`;
  2. then each component as big-endian IEEE-754 binary32 bits, with `-0.0f` mapped to `+0.0f`.

  NaN and infinity are refused. The loader computes the digest from exactly the floats it puts.
- **Load side.** The pre-write probe classifies a stored vector:
  - metadata other than the digest differs: `Conflict`;
  - wrong data, wrong digest, non-finite values, wrong dimension or a missing digest: `Corrupt`;
  - intact: `AlreadyPresent`, never compared with a fresh embedding, so nondeterministic embedders are supported.
- **Runtime side.** `VectorReader.verified(reader, policy)` is the only vector-reader facade, and `RuntimeStores` always applies it.
  - `getVectors` verifies every record.
  - `query` re-reads the matched keys with one `GetVectors` call and requires the same metadata and an intact digest.
  - Any failure refuses the whole read.
  - The model policy is checked on every vector.
- **Pinned test vector.** The test vector has 4 dimensions, with component bits `3f800000 c0200000 3e000000 00000000` (1.0, -2.5, 0.125, 0.0; the -0.0 variant gives the same digest). Its digest is `1b0a02150f1892c23d47cb3487b41d6f405340a101c88136e541d87865ae8137`. The test asserts a hand-written 46-byte preimage literal ([cr-p7-14-and-se-p7-12](../agent-runs/phase-07/cr-p7-14-and-se-p7-12.md)). An optional human `shasum` check of this value was offered in CR-P7-13, and no record of it being run was found.

**Limits (not closed):**

1. The digest detects **corruption, not tampering**. A writer that can replace a vector can also replace its `dataSha256`. Detecting tampering would need a keyed MAC, which is not implemented.
2. Whether S3 Vectors keeps float32 bits exactly through PutVectors/GetVectors is **unverified until Phase 8**. If the service changes the floats, every such vector must keep being refused (fail closed). The check must not be relaxed to a tolerance or to metadata only.
3. Text objects are probed only by their HeadObject sha256 metadata. Changed bytes with intact metadata are not detected.
4. Each query costs one extra `GetVectors` call. The configured `topK` must not exceed the GetVectors key cap; that real cap is unverified.
5. The Terraform index definitions must declare `dataSha256` non-filterable (infra-engineer, before Phase 12).

---

## 4. Proposed cleanup procedure (W6, design only)

None of the four stores exists in AWS, so this procedure is design only. It has **not been executed** and cannot be verified until the resources exist. Storage code never deletes. Cleanup is the human's `scripts/cleanup.sh`. This is the sequence proposed in [se-p7-01-design](../agent-runs/phase-07/se-p7-01-design.md):

1. Take a read-only inventory per run.
2. Move the serving pointers.
3. Delete vectors, then DynamoDB items, then Neptune data, then the S3 prefixes.
4. Keep the RunLedger and the reports.
5. Take an inventory after cleanup.

Only the human runs it. The closeout lists the cleanup documentation as incomplete until resources exist ([closeout proposal](../agent-runs/phase-07/closeout-proposal-2026-11-20.md), section 2).

---

## 5. Phase 7 status

Requirement IDs are those of [requirements-checklist.md](../agent-runs/phase-07/requirements-checklist.md). That file still shows its planning column; updating it is the orchestrator's task (WP10). "Locally verified" always means in-memory fakes and fixtures, never AWS.

### 5.1 Requirement table

| ID | Requirement | Implemented | Locally verified (evidence) | Outstanding |
|---|---|---|---|---|
| S1 | Six S3 zones and prefixes | yes, `S3Layout` | `S3LayoutSuite` ([se-p7-02](../agent-runs/phase-07/se-p7-02-report.md)) | live S3 (W1); core config keys for zones (BLOCKED-ON domain-engineer, [se-p7-02](../agent-runs/phase-07/se-p7-02-report.md)) |
| S2 | Versioned keys, run manifests | keys versioned by run or load ID; create-once; `_parts.json` and `_run-manifest.json` stored under `<graph>/runs/<runHex>/` (2.3) | run files stored with registry rows, `OpeningLoadSuite` (SE-P7-13 item 2, [du-p7-04-and-se-p7-13](../agent-runs/phase-07/du-p7-04-and-se-p7-13.md)); rerun gives no new objects ([5.2](#52-locally-verified-counts)) | live S3 |
| S3 | Content hashes, source references | ArtifactRegistry row per object | s3: 31 objects + 31 rows (29 items + 2 run files); 249 text objects + 249 rows | live S3 |
| N1 | Bulk-load manifests | `NeptuneBulkLoad.plan`, not sent | 12 requests, golden test ([se-p7-05](../agent-runs/phase-07/se-p7-05-report.md)) | CSV copy to the graph zone (CR-P7-07 n6); request fields unverified; live bulk load (W2) |
| N2 | Relationships | 6 edge labels; DERIVED_FROM and SUPPORTED_BY not produced by the model | label set over fixture CSV | live Neptune |
| N3 | Coauthor-edge properties | CSV row contracts | missing column, bad type and dangling endpoint refused ([se-p7-05](../agent-runs/phase-07/se-p7-05-report.md)) | live Neptune |
| N4 | Metrics for bounded lookup | DynamoDB MC-1, MC-2 | N4 lookups ([se-p7-07-and-cr-p7-08](../agent-runs/phase-07/se-p7-07-and-cr-p7-08.md)) | live DynamoDB |
| N5 | Idempotent IDs, parameterized openCypher | 9 read and 12 write templates | injection string stays a parameter; reload adds nothing ([se-p7-05](../agent-runs/phase-07/se-p7-05-report.md)) | Neptune openCypher support unverified; adapter must return and compare stored elements (CR-P7-07 m2); property round-trip fidelity (CR-P7-10 R1) |
| N6 | Read-only runtime graph credentials | type level only: `RuntimeStores` holds a `GraphReader` facade | `RuntimeStoresTypeSuite` | IAM (W5, infra-engineer) |
| G-ID | Neptune IDs equal Spark IDs | IDs copied verbatim; graph-id-parity | 459 vertex and 799 edge IDs equal the CSV and Parquet sets | in a live cluster (W2) |
| G-EV | COAUTHORED_WITH traceable to work evidence | evidence-trace check | passes on fixture; broken pointer refused ([se-p7-06](../agent-runs/phase-07/se-p7-06-report.md)) | live Neptune |
| D1 | Six tables | `TableSpecs` | `TableSpecsSuite` | live DynamoDB (Terraform) |
| D2 | Explicit keys | pk/sk, GSIs, access patterns | patterns served by key or GSI, no Scan (reflection test, [se-p7-02](../agent-runs/phase-07/se-p7-02-report.md)) | live DynamoDB |
| D3 | Conditional writes | `putIfAbsent`, `putIfVersion` | `ConditionalWritesSuite` | live behaviour (W3) |
| D4 | TTL only where disposable | QuerySession, EntityLinkCache | `TableSpecsSuite` | live TTL |
| V1 | Vector bucket and index abstractions | query, get, put, describe; no create or delete in storage | index dimension, distance and non-filterable keys checked before writes ([cr-p7-11-and-du-p7-02](../agent-runs/phase-07/cr-p7-11-and-du-p7-02.md)) | index creation (Terraform, not written); live S3 Vectors (W4) |
| V2 | Stable keys, compact metadata | `VectorKeys.of` | duplicate key with another text hash refused | live |
| V3 | Long text in S3 | `textS3Key`, `textSha256` | oversize metadata refused; texts in the object store | AWS metadata limits unverified |
| V4 | Seven filters | filter model | six on loaded data; institution synthetic only | institution data (no institution document type); service limits |
| V5 | Batching, retry, rejected records | `VectorLoad`, `RetryPolicy` | throttled batch resumes; backoff 50/100/200 ms with jitter 0.5 | real PutVectors per-record outcomes, which may upsert (CR-P7-11 R-b) |
| V6 | Five indexes | `VectorIndexes.all` | 176/51/8/9/5 = 249 | real embeddings (Phase 8) |
| L1 | Manifest and part integrity | `LoadVerifier`, `readVerified` | missing, extra, truncated or flipped parts and wrong hashes refused with zero writes ([se-p7-03](../agent-runs/phase-07/se-p7-03-report.md)) | S3 curated-zone integrity (no `.crc` on S3, CR-P7-09 U1) |
| L2 | Idempotent reload | `LoadLedger` | rerun, fresh-ledger rerun and resume ([5.2](#52-locally-verified-counts)) | multi-run coexistence (deferred by the human) |
| L3 | Provenance | 1.9 | provenance tests ([se-p7-07-and-cr-p7-08](../agent-runs/phase-07/se-p7-07-and-cr-p7-08.md)) | none locally |
| T1 | Required tests | suites under `modules/storage/src/test/scala/`; 33 cross-module adversarial tests under `tests/src/test/scala/scholarchain/tests/storage/` (TE-P7-01) | storage 395 and tests 178 (145 + the 33 adversarial tests) in the last observed `sbt check` (2026-11-21 3:07:45 AM, 162 suites, 2006 passed; [te-p7-01-and-human-review](../agent-runs/phase-07/te-p7-01-and-human-review.md)) | bounded AWS integration tests behind a profile: `sbt storageIT` is inactive (B10-alias-pending, [docs/agents.md](../agents.md)) |
| F1 | Fixtures from archived outputs | fixture tree (section 6) | `FixtureIntegritySuite`, `UpstreamFixtureIntegritySuite` | curated fixture has no historical SHA-256 |
| A1 | Offline AWS SDK adapters | **no** | none | deferred until before deployment, together with packaging of the deployed loader |
| W1 | `sbt storageIT` against real stores | **no** | none | AWS-GATE |
| W2 | Live Neptune bulk load, ID equality | **no** | none | AWS-GATE; no Neptune resource exists |
| W3 | Live DynamoDB conditional writes | **no** | none | AWS-GATE |
| W4 | Live S3 Vectors create, put and query; limits confirmed | **no** | none | AWS-GATE |
| W5 | Runtime IAM read-only | **no** | none | infra-engineer Terraform |
| W6 | Cleanup for the four stores | design only (section 4) | none | executed only by the human, once resources exist |
| spec | Service limits captured from current documentation (README L1421) | **no** | none | aws-docs MCP unavailable; all limits are unverified configuration |
| spec | Optional OpenSearch | **no** | none | optional; deferred by the human |

### 5.2 Locally verified counts

All counts come from loads of the fixtures into in-memory fakes through `OpeningLoad.run`. They are reported in [cr-p7-09-and-se-p7-08](../agent-runs/phase-07/cr-p7-09-and-se-p7-08.md), [cr-p7-10-and-se-p7-09](../agent-runs/phase-07/cr-p7-10-and-se-p7-09.md), [se-p7-10-report](../agent-runs/phase-07/se-p7-10-report.md), [cr-p7-12-and-se-p7-11](../agent-runs/phase-07/cr-p7-12-and-se-p7-11.md) and, for the s3 run files and the totals, [du-p7-04-and-se-p7-13](../agent-runs/phase-07/du-p7-04-and-se-p7-13.md) (SE-P7-13). They are asserted in `OpeningLoadSuite`.

| Store | Written on first load | Detail |
|---|---|---|
| neptune-bulk-load | 1258 | 459 vertices + 799 edges. The bulk-load plan holds 12 requests (asserted by a golden test; nothing is sent). |
| dynamodb | 1302 | analysis-windows 2, bridge-measures 176, coauthor-edges 259, community-memberships 176, component-memberships 176, coreness 176, evidence-paths 9, path-length-estimates 37, researcher-metrics 176, temporal-changes 112, window-comparisons 1, window-structures 2. Largest item 3617 bytes (asserted; storage's estimate, DynamoDB size accounting unverified). |
| s3 | 31 | 31 objects + 31 ArtifactRegistry rows: 29 load items + 2 run files (`_parts.json`, `_run-manifest.json`) |
| s3-vectors | 249 | 176 / 51 / 8 / 9 / 5 across the five indexes; 0 rejected; 249 text objects + 249 registry rows. Vectors are **synthetic** (`synthetic-test-only.v1`, dimension 8). The embedder was called 9 times (batch size 50). |

Totals: 280 objects (31 s3 + 249 text objects), 280 ArtifactRegistry rows, 0 conflicts. The RunLedger count of 9 rows (4 owner, 1 publication, 4 load) was reported before SE-P7-13. The SE-P7-13 record does not restate it.

| Scenario | Observed result |
|---|---|
| Rerun, same ledger | Every store `skipped-already-loaded`, 0 writes, 0 embedder calls. The s3 loader still re-reads and re-verifies both run files (reads, no write; CR-P7-16 n2). |
| Reader class missing (an absent class name injected by the test; never observed with a real missing jar) | Every store `refused` under `reader-classpath`; no read, no write; no runtime view (SE-P7-13 item 1) |
| `LinkageError` during verification (a `NoClassDefFoundError` thrown by a test run source; not a real partial classpath) | Every store `refused` under `reader-classpath`; 0 write calls, ledger included; no runtime view (SE-P7-14, `OpeningLoadSuite`) |
| `LinkageError` inside the loaders (same simulation, armed after the first graph write) | neptune `loaded` 1258; dynamodb, s3 and s3-vectors `failed` with `reader-classpath:`, 0 written each; not thrown; no runtime view. The rerun with the normal source: neptune skipped, the others loaded 1302 / 31 / 249 (SE-P7-14; [cr-p7-18-and-be-p7-08](../agent-runs/phase-07/cr-p7-18-and-be-p7-08.md)). No test covers a `LinkageError` after counted data writes (CR-P7-18 nit). |
| Rerun, fresh ledger | 0 written; present 1258 / 1302 / 31 / 249; 0 embedder calls |
| DynamoDB interrupted at write 501 | First call failed with 500 written, and no runtime view was returned. The resume wrote 802 with 500 present; the ledger ends at 1302 written. |
| Vectors throttled for 4 attempts | First call failed with 100 written (backoff 50/100/200 ms). The resume wrote 149 with 100 present, embedded 149 and wrote each text object once. |
| One flipped byte in a summary input | Refused by part-integrity in every store; 0 write calls; no runtime view |
| Resealed tampered summary input | Refused in every store; 0 write calls |
| Benchmark list with one changed byte | Refused (benchmark-pairs) in every store; 0 writes |
| Second run (simulated with planted owner rows; no second analysis-run fixture exists) | All four stores `Conflict`; 0 data writes. One foreign-owned store is enough to refuse all four. |
| Run-2 `_parts.json` (same run ID, other bytes) | One `Conflict` after the run-1 publication ([se-p7-03](../agent-runs/phase-07/se-p7-03-report.md)) |
| Corrupted stored float (VectorIntegritySuite T1-T6) | Refused as `Corrupt` naming the key; 0 vector puts; 0 embedder calls ([cr-p7-12-and-se-p7-11](../agent-runs/phase-07/cr-p7-12-and-se-p7-11.md)) |
| Runtime view under `RealModelsOnly` | Every synthetic vector read refused (`OpeningLoadSuite`, "CR-P7-11 m2") |

### 5.3 Reviews and pending gates

- Increment reviews: CR-P7-08, -09, -11 and -12 asked for changes, and each finding was routed. CR-P7-10, -13 and -14 approved.
- CR-P7-15 findings N1 (a corrupted float is refused on `query` and `getVectors`, through both `OpeningLoad.run` and `RuntimeStores.apply`) and N2 are reported done in SE-P7-13. CR-P7-09 n2 is reported not done ([du-p7-04-and-se-p7-13](../agent-runs/phase-07/du-p7-04-and-se-p7-13.md), item 5).
- CR-P7-16 (review of SE-P7-13) findings m1, m2, n1 and n2 were addressed in SE-P7-14, and CR-P7-18 approved SE-P7-14 ([cr-p7-17-and-se-p7-14](../agent-runs/phase-07/cr-p7-17-and-se-p7-14.md), [cr-p7-18-and-be-p7-08](../agent-runs/phase-07/cr-p7-18-and-be-p7-08.md)).
- The `tests` Test classpath now has parquet-hadoop and `storage % "test->test"` (BE-P7-06, reviewed APPROVE in CR-P7-17; [be-p7-06-report](../agent-runs/phase-07/be-p7-06-report.md)). The WP8 adversarial tests (TE-P7-01, 33 tests, all passed, no production defect) exercise the loads from `tests/**`, for example a fresh-ledger rerun with 1258 / 1302 / 31 / 249 present ([te-p7-01-and-human-review](../agent-runs/phase-07/te-p7-01-and-human-review.md)).
- The WP9 whole-phase review (code-reviewer) is pending.

### 5.4 Dependency and security status

The authorized remediation is **applied** (BE-P7-06, reviewed APPROVE in CR-P7-17; follow-up BE-P7-08, reviewed APPROVE in CR-P7-19). The checks below are build-time classpath and jar checks, plus local test runs. Sources: [be-p7-06-report](../agent-runs/phase-07/be-p7-06-report.md), [cr-p7-17-and-se-p7-14](../agent-runs/phase-07/cr-p7-17-and-se-p7-14.md), [cr-p7-18-and-be-p7-08](../agent-runs/phase-07/cr-p7-18-and-be-p7-08.md), [cr-p7-19-and-sp-p7-02](../agent-runs/phase-07/cr-p7-19-and-sp-p7-02.md), [be-p7-07-packagejobs](../agent-runs/phase-07/be-p7-07-packagejobs.md), [du-p7-05-hadoop-shaded-netty](../agent-runs/phase-07/du-p7-05-hadoop-shaded-netty.md), [human-review-be-p7-06](../agent-runs/phase-07/human-review-be-p7-06-2026-11-20.md), [te-p7-01-and-human-review](../agent-runs/phase-07/te-p7-01-and-human-review.md).

| Item | Status | Evidence |
|---|---|---|
| Netty pin | **Applied:** ledger `netty=4.1.141.Final`, pinned on storage, services, sparkAnalytics and tests. `verifyDependencyScopes`: every io.netty jar on the checked classpaths is 4.1.141.Final; no Netty on rag, hadoop Test, or the hadoop and Spark Runtime classpaths. | BE-P7-06; CR-P7-17 |
| Netty compatibility | **Our own tests are the only evidence** for 4.1.141 with AWS SDK 2.57.8 and Spark 3.5.5. Per DU-P7-04, AWS makes no compatibility statement for netty 4.1.139 or later, and Spark 3.5 pins 4.1.96. No gate exercises `netty-nio-client` at runtime (C1). | BE-P7-06 risks; DU-P7-04 C1, C2 |
| Netty 4.1 end of life | 2027-07-01 | BE-P7-06; DU-P7-04 C7 |
| Native codecs aircompressor, zstd-jni, lz4-java | **Absent** from every inspected classpath (`verifyDependencyScopes` INFO). | BE-P7-06 |
| snappy-java | 1.1.10.11, at Test scope only, on storage, sparkAnalytics (Test and SparkIT) and tests. Absent from hadoop Test (exclusion H1). Spark tests use the Snappy codec (SP-P7-01). | BE-P7-06 |
| H1 runtime effect | Verified only by the hadoop tests inside `sbt check` (85 passed). **Not** verified by `sbt localPipelineDev`: the HE-P7-01 run was refused before any job because the earlier complete attempt-1 output already existed, and per the human it was not rerun (attempt-1 untouched). | te-p7-01-and-human-review, "localPipelineDev" |
| `sbt sparkIT` | **PASS**, 14 tests in 1 class, exit 0 (2026-11-20 11:34:31 PM). Local client mode only; not EMR or spark-submit evidence. | SP-P7-02 |
| `sbt packageJobs` | **PASS:** `sparkJobAssemblyCheck` and `hadoopJobAssemblyCheck` found no class under the 13 forbidden prefixes (including `io/netty/`, `org/xerial/snappy/`, `com/github/luben/`, `io/airlift/compress/`, `net/jpountz/`). Both jars were reported "up to date" by the sbt-assembly cache, and their sha256 values describe the uncommitted tree. | BE-P7-07 |
| `sbt sparkSubmitLocal` | **Not run.** It refuses unless the code scope is committed; deferred until the human commits. The pinned Spark distribution it uses still carries Netty 4.1.96, lz4 and zstd (R5). | human-review-be-p7-06; BE-P7-06 risks |
| Hadoop client jar inspection | Every resolved `hadoop-client-api` and `hadoop-client-runtime` jar on 7 classpaths was opened: 3.4.1 on storage Compile, Runtime and Test, hadoop Test and tests Test; 3.3.4 on sparkAnalytics Test and SparkIT. Each showed **0 entries** matching the scanned patterns: entry paths containing `io/netty/` (at the root, relocated under any prefix, or under a multi-release directory), `META-INF/maven/io.netty/`, and Netty native libraries. **Not detected by this scan:** Netty relocated without `io/netty/` in its path; nested inner jars; a standalone `io.netty.versions.properties`; corrupt entry data behind a valid central directory; Hadoop outside the build (EMR, the pinned Spark distribution). The earlier claim that `hadoop-client-runtime` bundles Netty 4.1.100 (DU-P7-04 C3) is **withdrawn**. | BE-P7-06 C3 option (d); BE-P7-08 m1; CR-P7-17, CR-P7-19; DU-P7-05 |

---

## 6. How to run locally

Nothing in this section was replayed by the documentation engineer. The work order for this revision (DOC-P7-03) forbade every Bash command, because a reviewer was running concurrently. Each command below is recorded as observed in the cited evidence.

| Command | Purpose | Last observed |
|---|---|---|
| `sbt check` | Definition of done: format, compile, all tests, dependency-scope checks, `markTestRun` | Run for TE-P7-01; marker reviewed by the human, 2026-11-21 3:07:45 AM CST: `[success]`, exit 0, 162 suites, 2006 passed, 0 skipped, storage 395, tests 178 ([te-p7-01-and-human-review](../agent-runs/phase-07/te-p7-01-and-human-review.md)) |
| `sbt storage/test` | Storage tests only. `OpeningLoadSuite` is the end-to-end test of `OpeningLoad.run` over the fixtures. | storage-engineer for SE-P7-14: "Passed: Total 395, Failed 0, Errors 0, Passed 395", 2026-11-20 11:04:34 PM ([cr-p7-17-and-se-p7-14](../agent-runs/phase-07/cr-p7-17-and-se-p7-14.md)) |

There is no `runMain` entry point. The local load runs only inside the test suites ([cr-p7-09-and-se-p7-08](../agent-runs/phase-07/cr-p7-09-and-se-p7-08.md)).

**Where the loader can run.** Parquet and Hadoop are Optional-scope dependencies of storage (risk T6, [risk triage](../agent-runs/phase-07/risk-triage-2026-11-20.md)). The loader runs only where the reader jars (parquet-hadoop, parquet-common, parquet-column, parquet-encoding, parquet-format-structures, hadoop-client-api) are on the classpath, which today means the storage tests and the `tests` Test classpath (BE-P7-06). Where a reader class is missing, `OpeningLoad.run` refuses every store under `reader-classpath` before it reads or writes anything; a `LinkageError` before any write refuses every store with no write, and one inside a loader fails that store only ([1.5](#15-load-flow), step 0; [handoff](phase-09-11-handoff.md#1-getting-a-runtimestores)).

**Fixtures.** They live under `modules/storage/src/test/resources/fixtures/`, are untracked, and are committed by the human.

| Directory | Content | Evidence and its limits |
|---|---|---|
| `phase06-run-1-local/` | analysis run `run=a65f…8c40` (56 files) + `load=a65f…8c40` (4 load manifests) + `SHA256SUMS.txt` | 60 files matched the archive's sha256 lines, and the archive was verified before and after the copy ([human-fixture-copy-2026-11-19](../agent-runs/phase-07/human-fixture-copy-2026-11-19.md)) |
| `phase06-run-2-local-cluster/` | `_parts.json` only: the same run ID with different bytes | Matches the archive's sha256. Used for the conflicting-publication test. It is not a second analysis run. |
| `phase06-grouping-dev/run=84dd…d7d2/` | grouping run (7 files) | The 5 parts matched the committed Phase 6 record ([human-fixture-copy-2026-11-20](../agent-runs/phase-07/human-fixture-copy-2026-11-20.md)) |
| `phase06-curated-dev/snapshot=dblp-2026-10-03/run=2881…8baa/` | curated Hadoop run with `.crc` sidecars | Byte-identical to the archive at copy time. **No historical SHA-256 verification exists**; integrity rests on CRC32 sidecars only. sbt's test-resource copy drops hidden `.crc` files, so tests read this fixture from the source tree ([se-p7-07-and-cr-p7-08](../agent-runs/phase-07/se-p7-07-and-cr-p7-08.md)). |
| `phase06-analysis-settings/analysis-dev.conf` | dev analysis settings | Equal to the Git HEAD blob; its config digest equals the run manifest ([human-fixture-copy-2026-11-20](../agent-runs/phase-07/human-fixture-copy-2026-11-20.md)) |
| `phase06-benchmark-pairs/benchmark-pairs-dev.v1.json` | benchmark pair list | Equal to the HEAD blob, sha256 `c37e3db9…bae10`. Core digest `2e1ff182…be9` equals the manifest's `benchmarkPairsDigest` ([human-review-and-benchmark-copy](../agent-runs/phase-07/human-review-and-benchmark-copy-2026-11-20.md), [cr-p7-09-and-se-p7-08](../agent-runs/phase-07/cr-p7-09-and-se-p7-08.md)) |

The fixtures are one small dev run: 176 researcher-metric rows, 259 coauthor edges and 249 summary inputs. They show nothing about corpus scale, EMR output or AWS behaviour.

**The load report.** `OpeningLoadReport` ([`load/OpeningLoad.scala`](../../modules/storage/src/main/scala/scholarchain/storage/load/OpeningLoad.scala)) is deterministic: equal inputs and equal store contents give an equal report. It holds:

- `runId`, `partManifestSha256`, `benchmarkDigest`, `recordedBenchmarkDigest`, `upstreamProblems` and `rejections`;
- one `StoreLoadOutcome` per store with:
  - `status`: `loaded`, `skipped-already-loaded`, `refused` or `failed`;
  - `performed` and `open` deferred checks;
  - `written` (also the partial count on failure), `alreadyPresent` and `conflicts`;
  - named `counts`: per role for DynamoDB; per index `routed`, `written`, `present`, `rejected` and `stored` for vectors; on a failed call, the writes made to the other stores;
  - `rejections`, `error` and `rejectedRecords`.

`OpeningLoadSuite` prints the report lines during the test run.

---

## 7. Threats to validity

- **Fakes, not AWS.** Conditional writes, create-once puts, compare-on-put vector semantics and openCypher templates are implemented in-memory by the same team that wrote the loaders. Real service semantics may differ. For example, PutVectors may upsert and DynamoDB item size may be accounted differently (CR-P7-11 R-b; CR-P7-09 U5).
- **One small dev run.** All counts come from one dev run. The second-run tests are simulated, because no second analysis-run fixture exists.
- **Unverified limits.** All AWS service limits are configuration values chosen without current documentation.
- **Synthetic vectors.** Synthetic vectors carry no meaning, so retrieval quality is not assessed.
- **Uncommitted code.** The storage code is uncommitted, and the per-increment evidence is condensed orchestrator records of agent reports and human-observed terminal output. The documentation engineer did not re-run any test.
- **Evidence may lag the tree.** Tests and evidence describe the tree at the 2026-11-21 3:07:45 AM `sbt check` run. Any later change is not covered.
- **Reader-classpath check.** The check names one class per reader jar (six). It cannot prove that every class the reader needs links; such a failure surfaces later as a `LinkageError`, which `OpeningLoad.run` handles as in [1.5](#15-load-flow). Both the missing-class path and the `LinkageError` paths were observed only with simulated failures (an injected absent class name, a `NoClassDefFoundError` thrown by a test run source), never with a real partial classpath. No test covers a `LinkageError` after counted data writes (CR-P7-18).
- **Dependency checks are build-time.** The Netty and codec results are classpath and jar-entry checks over the build's resolved jars, plus local test runs. They say nothing about Hadoop or Netty on EMR or in the pinned Spark distribution, and the Hadoop jar scan has the limits listed in [5.4](#54-dependency-and-security-status).
