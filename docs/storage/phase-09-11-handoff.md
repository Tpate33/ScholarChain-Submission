# Storage handoff for Phases 9-11 (rag, services, tests)

Owner of this page: `documentation-engineer`. It describes the storage API in the uncommitted working tree on HEAD `0c975573…` as of 2026-11-21. Architecture, schemas and the evidence are on the [storage page](README.md).

Everything here runs against **in-memory fakes with synthetic vectors**. No live AWS adapter exists, and real embeddings are Phase 8.

## 1. Getting a `RuntimeStores`

### From a load (for tests that need populated stores)

Today this works only on a classpath that holds the Parquet reader libraries, which means the storage tests and the `tests` Test classpath. See [Classpath](#classpath-who-can-run-the-load-answered-in-se-p7-13) below.

```scala
// Signatures as in modules/storage/src/main/scala/scholarchain/storage/load/OpeningLoad.scala
OpeningLoad.run(
  sources: OpeningLoadSources, // run, loads, grouping, curated, benchmark (RunSource each), benchmarkFile
  config:  OpeningLoadConfig,  // settings, parquet (ParquetReadLimits), upstream bounds,
                               // benchmarkMaxBytes, keyValueLimits, layout, vectors (VectorLoadConfig)
  targets: OpeningLoadTargets  // graph, keyValues, ledger, objects, vectors, embedder, clock, sleeper, jitter
): OpeningLoadResult           // (report: OpeningLoadReport, runtime: Option[RuntimeStores])
```

- `runtime` is `Some` **only** when `report.complete`, meaning every store is `loaded` or `skipped-already-loaded`. A refused, failed or partial load gives `None`, so code must handle `None` and must not query stores that did not load (CR-P7-10 R5).
- Nothing has a default. Every bound, limit, layout value and the vector policy come from configuration.
- The working example is `OpeningFixture` and `OpeningLoadSuite` under `modules/storage/src/test/scala/scholarchain/storage/`. They build in-memory stores, the synthetic embedder and the test configuration with dimension 8, cosine distance, batch size 50 and the 1024-byte per-value cap.
- `OpeningLoad.run` passes `targets.keyValues` both as the runtime key-value reader and as the runtime-table writer.

### Directly, over stores you already populated

`RuntimeStores.apply(objects, keyValues, runtimeTableWriter, graph, vectors, vectorIndexes, vectorPolicy)` is public ([`port/RuntimeStores.scala`](../../modules/storage/src/main/scala/scholarchain/storage/port/RuntimeStores.scala)). `vectorPolicy` is **required**, with no default:

- `EmbeddingPolicy.RealModelsOnly` in production. A synthetic or missing `modelVersion` refuses the read.
- `EmbeddingPolicy.SyntheticTestOnly` only in local tests, set explicitly. It refuses real model versions.

The policy used by `OpeningLoad.run` is `config.vectors.embedding`. `OpeningLoadSuite` shows that a `RealModelsOnly` view refuses every vector of the fixture load.

### Classpath: who can run the load (answered in SE-P7-13)

Source: SE-P7-13 item 1 ([du-p7-04-and-se-p7-13](../agent-runs/phase-07/du-p7-04-and-se-p7-13.md)), SE-P7-14 and CR-P7-18 ([cr-p7-17-and-se-p7-14](../agent-runs/phase-07/cr-p7-17-and-se-p7-14.md), [cr-p7-18-and-be-p7-08](../agent-runs/phase-07/cr-p7-18-and-be-p7-08.md)), BE-P7-06 ([be-p7-06-report](../agent-runs/phase-07/be-p7-06-report.md)) and [`load/OpeningLoad.scala`](../../modules/storage/src/main/scala/scholarchain/storage/load/OpeningLoad.scala).

- **Configuration needs no reader type.** `OpeningLoadConfig.parquet` is a `scholarchain.storage.load.ParquetReadLimits`: four values (`maxFooterBytes`, `maxPartBytes`, `maxRowsPerPart`, `maxPageBytes`), built only through `ParquetReadLimits.of`, which validates them like the reader's own bounds and has no default. No field type of `OpeningLoadConfig` is in `scholarchain.storage.parquet`, the package that the rag source-import check (`project/RagSourceImports.scala`) forbids.
- **Running the load needs the reader libraries.** Parquet and Hadoop are Optional-scope dependencies of storage ([risk triage](../agent-runs/phase-07/risk-triage-2026-11-20.md), T6), so they are not on the rag or services classpaths. A caller must have parquet-hadoop and hadoop-client on its own Test classpath. `OpeningLoad.ReaderJars` names one class per required jar: parquet-hadoop, parquet-common, parquet-column, parquet-encoding, parquet-format-structures and hadoop-client-api. Classpath failures are never thrown out of `OpeningLoad.run`:
  - a missing reader class refuses every store under check `reader-classpath`: it reads nothing, writes nothing, and returns `runtime = None`;
  - a `LinkageError` before any write (upstream reads, verification, owner check) refuses every store the same way: no write, `runtime = None`;
  - a `LinkageError` inside one store's loader fails that store only (`Permanent` error starting `reader-classpath:`, its partial writes counted); the other stores continue, `runtime = None`, and a later call resumes it.

  These paths were tested only with simulated failures, not a real partial classpath (see the [storage page, section 7](README.md#7-threats-to-validity)).
- **Working example from outside the package.** `scholarchain.outside.OpeningLoadSurfaceSuite` (storage tests, 6 tests) checks that no entry type reaches a Parquet or Hadoop type. It also runs the full fixture load from code outside `scholarchain.storage` and gets a `RuntimeStores`. It runs on storage's Test classpath, where the reader libraries are present.
- **`tests/**` can run the load.** BE-P7-06 added parquet-hadoop at Test scope with the native-codec exclusions, the same exclusions on hadoop-client, and `storage % "test->test"` (reviewed APPROVE in CR-P7-17). The 33 TE-P7-01 adversarial tests under `tests/src/test/scala/scholarchain/tests/storage/` use it ([te-p7-01-and-human-review](../agent-runs/phase-07/te-p7-01-and-human-review.md)).
- **rag and services unit tests** should build their stores with `RuntimeStores.apply` over the in-memory fakes ([above](#directly-over-stores-you-already-populated)), not with a load. Adding Parquet to rag/Test would contradict the `verifyDependencyScopes` check.

## 2. What the runtime view exposes

All readers are wrapped in facades and cannot be cast back to their write side.

| Field | Type | Operations |
|---|---|---|
| `objects` | `ObjectReader` | `head`, `get`, `list(bucket, prefix, maxKeys)` (bounded) |
| `keyValues` | `KeyValueReader` | `get`, `query(KeyQuery)`; no Scan |
| `graph` | `GraphReader` | `run(BoundQuery)` only |
| `vectors` | `VectorReader` (verified) | `query(index, vector, topK, filter)`, `getVectors(index, keys)` |
| `vectorIndexes` | `IndexInspector` | `describe(index)` |
| `querySessions` | `QuerySessionWriter` | `putSession`, `putStep` (create-once) |
| `entityLinks` | `EntityLinkCacheWriter` | `put` (create-once) |
| `evidenceBundles` | `EvidenceBundleWriter` | `put(EvidenceBundle)`: item rows first, bundle row last. A present bundle row means every item row is present, so a retry completes the bundle without duplicates. |

There is no loader, object writer, vector writer, graph writer, or writer for RunLedger, ArtifactRegistry or the analysis-results table.

## 3. Query patterns available

- **Graph.** Nine fixed openCypher templates, bound with `BoundQuery.bind(query, args, maxLimit)`. Only IDs of the declared core kind, years in range and limits in `1..maxLimit` are accepted.

  | Template | Returns |
  |---|---|
  | `ResearcherById` | one researcher |
  | `DirectCollaborators` | coauthors in a window, with counts, years and `sourceEvidence` |
  | `CoauthorEdgeBetween` | the coauthor edge of two researchers in a window |
  | `SharedWorks` | works both researchers authored |
  | `WorksOfResearcher` | a researcher's works |
  | `WorkVenue` | a work's venue |
  | `WorkTopics` | a work's topics |
  | `ResearcherInstitutions` | a researcher's institutions |
  | `MentionResolution` | the researcher an author mention resolves to |

  Neptune support of these constructs is unverified.
- **Analysis results (DynamoDB `ResearcherMetricCache`).** MC-1 to MC-8: metrics by researcher and window, pair connectivity, group members, ranked top-k, window-level results, temporal change and evidence-ID resolution. Key forms are in [the storage page, section 2.2](README.md#22-dynamodb). Counts, path hops, components, communities and bridge scores must come from these records or the graph, never from vector similarity (CLAUDE.md, engineering invariants).
- **Runtime tables.** EvidenceBundle (EB-1 to EB-4), QuerySession (QS-1, QS-2), EntityLinkCache (EL-1 to EL-3), plus the ArtifactRegistry reads AR-1 to AR-4.
- **Vectors.** Five indexes, with filters `Eq`, `In`, `Range` and `And` over each index's filterable fields (`VectorFilters.problems` validates a filter for an index). The context text for each match is in ordinary S3 at the vector's `textS3Key`, and its sha256 is `textSha256`.

## 4. Known limitations and open questions

1. **One analysis run per store instance** (human decision 2026-11-20). For another run, use fresh stores and a fresh RunLedger. Multi-run coexistence and an active-run pointer are **deferred and open**. They become relevant before an EMR run replaces the dev run. Storage-engineer proposed run-scoped keys (`run#<hex>#…`) plus an active-run pointer, and that design is not implemented ([cr-p7-09-and-se-p7-08](../agent-runs/phase-07/cr-p7-09-and-se-p7-08.md)).
2. **Institution filter: synthetic only.** No fixture row carries institution IDs. Core has no institution document type, so `topic-institution-summary-v1` holds topic summaries only (5 rows), and `paper-abstract-v1` holds work summaries only (8 rows, no work-abstract rows).
3. **Real embeddings are pending (Phase 8).** All 249 vectors are synthetic, so similarity results carry no meaning. Before Phase 8, these must be resolved: embedder determinism, and whether S3 Vectors keeps float32 bits exactly (a fail-closed `dataSha256` check). Another open point is PutVectors semantics, which may upsert (CR-P7-11 R-a, R-b; [cr-p7-11-and-du-p7-02](../agent-runs/phase-07/cr-p7-11-and-du-p7-02.md)).
4. **Each vector query costs one extra GetVectors call.** The configured `topK` must not exceed the GetVectors key cap, otherwise the read fails closed. The real caps are unverified.
5. **Synthetic embedder is importable.** `SyntheticTestEmbedding` and the in-memory fakes live in storage **main**, so production code could import them. The runtime policy refuses synthetic vectors at read time, but composition code must pass `RealModelsOnly` (CR-P7-11 m2).
6. **Fakes are not thread-safe** ([se-p7-02-report](../agent-runs/phase-07/se-p7-02-report.md)).
7. **Expired EntityLinkCache item.** An expired item that has not yet been swept returns `Conflict` on `put`. How to handle it is runtime policy, for rag and services to decide ([se-p7-02-report](../agent-runs/phase-07/se-p7-02-report.md)).
8. **Researcher-profile identity data.** Researcher-profile vectors carry `identityStatus` and `anchorCollision` metadata, and their context text includes the ER identity line. rag can filter on or disclose identity uncertainty (CR-P7-11 M2, [se-p7-10-report](../agent-runs/phase-07/se-p7-10-report.md)).
9. **No live store.** Neptune, DynamoDB, S3 and S3 Vectors adapters, IAM, `sbt storageIT`, Terraform indexes (with `dataSha256` non-filterable) and the S3 curated-zone integrity design are all outstanding ([storage page, section 5](README.md#5-phase-7-status)).
