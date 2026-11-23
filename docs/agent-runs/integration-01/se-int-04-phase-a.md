# SE-INT-04 phase A (storage-engineer): read-only plan (verbatim, assembled by the orchestrator)

Orchestrator decisions on D1-D7 (recorded for phase B; the human may overrule):
- D1: one HeadObject per listed key, bounded by maxKeys. The port contract is unchanged.
- D2: an object without a full-object SHA-256 checksum is Corrupt (fail closed).
- D3: storage applies a structural check only (https, host, port). Reader-endpoint enforcement is routed to core (G2) and infra/IAM.
- D4: the bucket allowlist is raw, curated and context.
- D5: refuse any filter shape whose documented semantics differ from `VectorFilters.matches`.
- D6: the list call count is documented and routed under G4.
- D7: phase B builds explicit caller-supplied adapter settings types with no defaults, tested with stubs. G1-G3 (domain-engineer) and G4/G5 (service-engineer) are reported as owner items and are not done in this order, per the human's scope.

Documentation answers for Q1-Q22 are fetched by the orchestrator from public AWS pages and recorded in se-int-04-docs.md.

---

SE-INT-04 phase A (storage-engineer): read-only plan

I read the order, the injection contracts and the existing ports. I made no edits and ran no build or network call.

The storage SDK modules are already on the classpath. The config gaps are new timeout and bound keys for S3, Neptune and S3 Vectors, which domain-engineer would add. Services also has to close the SDK clients at shutdown, which is service-engineer's change. Nothing has to change outside storage before phase B, provided the orchestrator either approves explicit constructor settings for the missing values or routes the core keys first (decision D7). I recommend routing them first.

## 1. IMPLEMENTATION LIST

The injection contracts come from `modules/services/src/main/scala/scholarchain/services/ProductionComposition.scala` and fix what I must implement:
- **dynamoDb**: `Option[DynamoDbClientSettings => Either[String, DynamoDbCalls]]`. Its scaladoc expects `DynamoDbCalls.of(DynamoDbClients.build(settings, credentials))`.
- **objects**: `ObjectReader`. The query-jobs process uses it.
- **graph**: `GraphReader`. The query-jobs and graph processes use it.
- **vectors**: `VectorReader`. Query-jobs uses it.
- **vectorIndexes**: `IndexInspector`. Query-jobs uses it.

Services wraps these itself (`RuntimeStores.apply` adds the read-only facades and `VectorReader.verified`) and refuses anything under `scholarchain.storage.fake.*`. My adapters must not live in that package.

Items (all under `modules/storage/**`):

- **A1. DynamoDB client factory and lifecycle** (`aws/dynamodb`, in addition to the existing `DynamoDbClients.build` and `DynamoDbCalls.of`).
  - A `DynamoDbClientFactory`: an AutoCloseable `DynamoDbClientSettings => Either[String, DynamoDbCalls]`.
  - It builds the client with `DynamoDbClients.build` and the standard credentials chain (`DefaultCredentialsProvider`; whether to use the builder or `create()` depends on Q1).
  - It turns builder exceptions into `Left` with value-free text, tracks every client it creates, and has an idempotent `close()` that closes those clients and any provider it owns.
  - After close it refuses to build new clients.
  - Tests use an injected builder seam, so no network and no credentials are needed.
- **A2. Storage client bundle** (`aws/StorageClients`). One AutoCloseable owner for the four SDK clients and one shared credentials provider, so the composing process can release everything with a single call. Close is idempotent and closes every client even if one close throws.
- **A3. S3 ObjectReader** (`aws/s3`).
  - A narrow seam, `S3Calls`, with exactly HeadObject, GetObject and ListObjectsV2. No write, delete or copy operation is reachable.
  - An adapter implementing `head`, `get` and `list`.
  - **sha256:** `ObjectMeta.sha256` is hex. I propose deriving it from the stored SHA-256 checksum, which is base64 and which the loader's create-once PutObject records. HeadObject and GetObject would be called with `ChecksumMode=ENABLED`.
  - **get:** recomputes `Bytes.sha256` from the body and returns `Corrupt` on mismatch.
  - **Missing data:** a missing key is `Right(None)`. An object without a full-object SHA-256 checksum is `Corrupt`.
  - **Size bound:** `get` checks content-length against a configured maximum before reading, reads at most that many bytes plus one, and aborts the stream when the bound is exceeded.
  - **list:** one ListObjectsV2 page with `MaxKeys = maxKeys`, refused outside 1..maxListKeys. ListObjectsV2 returns neither content type nor checksum value (to be confirmed by Q8), so I propose one HeadObject per listed key, bounded by maxKeys (decision D1).
  - **Bucket allowlist:** only the buckets named in `storage.rawBucket`, `curatedBucket` and `contextBucket` are accepted; any other bucket is `Refused` before a call.
- **A4. Neptune GraphReader** (`aws/neptune`).
  - A seam, `NeptuneCalls`, with only ExecuteOpenCypherQuery on the neptunedata client.
  - The adapter sends `BoundQuery.query.text` unchanged, and sends `BoundQuery.parameters` as a JSON object rendered with uPickle (Str becomes a JSON string, IntValue a JSON number). Text is never concatenated.
  - It decodes the `results` Document into `Vector[GraphRow]`: strings become `GraphValue.Str`, integral numbers `IntValue`, null `Null`. Any other type, and any column not in the template's RETURN list, is `Corrupt`.
  - It enforces a configured `maxRows`, refusing a larger result rather than truncating it.
  - It maps errors to `Transient` or `Permanent`. A read-only-violation error is `Permanent`; it is not expected, since the templates are read-only.
  - The endpoint must be the reader (`cluster-ro-`) endpoint over https. I can only check this with a heuristic in storage (decision D3).
- **A5. S3 Vectors VectorReader and IndexInspector** (`aws/s3vectors`).
  - A seam, `S3VectorsCalls`, with exactly QueryVectors, GetVectors and GetIndex. PutVectors and every delete or create operation are unreachable.
  - The adapter is bound to the one `storage.vectorBucket`. Index names must be in `VectorIndexes.all`; any other index is `Refused`.
  - **query:**
    - topK must be within 1..maxTopK.
    - `VectorFilter` is translated to the service filter document (see Q15).
    - Metadata and distance are both requested.
    - Response metadata is decoded into `VectorMetadata` using `MetadataField` types.
    - An unknown key or a wrong type is `Corrupt`, and so is a response that is not sorted by distance.
  - **getVectors:** the number of keys must be within 1..maxGetKeys. Data and metadata are both requested, and float32 values are decoded into `Vector[Float]`. Missing keys are simply omitted; the verified facade above already fails closed on a matched key that comes back missing.
  - **describe:** calls GetIndex. A not-found error is `Right(None)`. The distance metric is mapped to `DistanceMetric`, and an unknown metric is `Corrupt`. `nonFilterableKeys` comes from the index's metadata configuration.
- **A6. Shared error classification** (`aws/AwsErrors`). The DynamoDB rules (5xx, 429, throttling, timeouts and IOException causes are `Transient`; abort or interrupt is `Permanent`) generalised per service. Error details name the operation, the exception class, the HTTP status and the error code only, never a message.
- **A7. Tests.** Stub seams for each adapter, covering: success, missing data, service and transport errors, bounds and pagination (list maxKeys, the get size bound, maxRows, topK and key-count caps), checksum mismatch, refused bucket or index, decode corruption, and lifecycle (idempotent close, close when one client throws, refusal after close). None needs credentials or network.

## 2. SDK OPERATIONS

Only these operations are needed:
- **DynamoDB:** `DynamoDbClient.builder()` with region, credentialsProvider, overrideConfiguration (`apiCallTimeout`, `apiCallAttemptTimeout`) and an optional endpointOverride; the client's `close()`. The five calls stay unchanged: GetItem, PutItem, DeleteItem, Query, BatchGetItem.
- **Credentials:** `DefaultCredentialsProvider` (the builder or `create()`, per Q1). Close it only if it is not a shared singleton.
- **S3:** HeadObject (bucket, key, checksumMode ENABLED), GetObject (same, read as `ResponseInputStream` with a bounded read and `abort()`), ListObjectsV2 (bucket, prefix, maxKeys; single page, no continuation), `S3Client.close()`.
- **Neptune:** `NeptunedataClient.builder()` with endpointOverride set to the reader endpoint URI, region, credentials and timeouts; ExecuteOpenCypherQuery (openCypherQuery, parameters); `close()`.
- **S3 Vectors:** `S3VectorsClient.builder()`; QueryVectors (vectorBucketName, indexName, queryVector float32, topK, filter, returnMetadata, returnDistance); GetVectors (vectorBucketName, indexName, keys, returnData, returnMetadata); GetIndex (vectorBucketName, indexName); `close()`.

## 3. CONFIG GAPS

What core already provides:
- `aws.region` (validated Region).
- `storage.rawBucket`, `curatedBucket`, `contextBucket` and `vectorBucket` (validated Bucket).
- `storage.neptuneEndpoint` (**free Text, not validated**).
- `storage.vectorIndex` (Token, singular; the readers do not use it, because index names come from storage's `VectorIndexes` specs).
- The complete `storage.dynamodb.*` set, already mapped by `ProductionMapping.dynamoDbParts`.
- Services `graphMaxResults` and `graphMaxPageSize`, which are the caller's `BoundQuery` maxLimit.

Missing, all owned by domain-engineer. The minimum change is three optional sections, following the DE-INT-01 precedent: optional at load, left out of the digest, and required by `ProductionSettings.forProcess` only for the processes that use them.
- **G1. `storage.s3.*`:** `apiCallTimeoutMillis`, `apiCallAttemptTimeoutMillis` (attempt below call, at least 1), `maxObjectBytes` (at least 1; the get bound), `maxListKeys` (1..1000 if Q8 confirms 1000), and an optional `endpointOverride` using the existing Endpoint bound. Required by query-jobs.
- **G2. `storage.neptune.*`:** `apiCallTimeoutMillis`, `apiCallAttemptTimeoutMillis`, `maxRows` (at least 1, and at least services `graphMaxResults` and `graphMaxPageSize`). Also either a typed reader endpoint (an https URI with host and port) or a `port` key. Today `storage.neptuneEndpoint` is free Text, so "host only" and "full URI" are both possible; I recommend that core validate it as an https URI with a port. Required by query-jobs and graph.
- **G3. `storage.s3vectors.*`:** `apiCallTimeoutMillis`, `apiCallAttemptTimeoutMillis`, `maxTopK` (at least 1 and at most the service topK cap; also at least `runtime.retrievalTopK`), `maxGetKeys` (at least 1, at most the GetVectors key cap, and at least `maxTopK`, because the verified reader re-reads topK keys), and an optional `endpointOverride`. Required by query-jobs.
- **G4. Timing.** service-engineer, together with domain-engineer, must check these new call timeouts against `services.<kind>.readTimeoutMillis` and `readRequestTimeoutMillis`, the same way X-4 does for DynamoDB. That is a later wiring obligation; I will not claim it is closed.

Missing, owned by service-engineer:
- **G5. Lifecycle.** Today ProductionDependencies takes ready-built readers and a factory, and holds no AutoCloseable. Minimum change: the service launcher builds A2 from config, passes its readers and its A1 factory into ProductionDependencies, and calls `close()` once after the drain and grace period on shutdown and on a failed start. One way is a `resources: Vector[AutoCloseable]` field, or closing in the launcher that owns the bundle. Until that exists, nothing closes the clients.
- **G6.** The `apiKeys` (Secrets Manager) and `workflow` (rag) injection points stay unbuilt. They are not storage work.

Routing items for infra-engineer. None of these blocks phase B.
- **IAM actions:** grant only s3:GetObject and s3:ListBucket on the three buckets, neptune-db:ReadDataViaQuery (per Q11), s3vectors:QueryVectors, GetVectors and GetIndex, plus the five DynamoDB actions already noted in SE-INT-01.
- **Network:** Neptune is reachable only inside the VPC.

## 4. CLASSPATH GAPS

- **Already present:** `dynamodb`, `s3`, `s3vectors` and `neptunedata` at 2.57.8 (the `aws-sdk-v2.bom` value), at Compile scope on storage (`build.sbt`, `DependencyScopes.AwsModules`). `auth` and `regions` arrive transitively.
- **HTTP client:** I propose to rely on the SDK's default synchronous HTTP client found at runtime, so the build does not change. This gap only opens if Q3 shows the default sync client is ambiguous or should be set explicitly. Then I would need `software.amazon.awssdk:apache-client` (or `url-connection-client`) declared at Compile scope at the BOM version, plus the matching `DependencyScopes` and services-packaging check. That is build-engineer's change, raised then as BLOCKED-ON.
- Nothing else is missing. uPickle is already present for the parameter and filter JSON.

## 5. DOC QUESTIONS

Shortened URLs: SDK javadoc means `https://sdk.amazonaws.com/java/api/latest/software/amazon/awssdk/...`, and the SDK guide means `https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/...`. Please record the date each answer was checked.

**SDK for Java v2**
- **Q1. Default credentials chain.** What is the exact order of `DefaultCredentialsProvider`'s chain? Does `create()` return a shared singleton, and does closing it affect other users? Should an owned instance come from `builder().build()`? Pages: SDK guide `credentials-chain.html`; javadoc `.../auth/credentials/DefaultCredentialsProvider.html`.
- **Q2. Client close and thread safety.** Is `SdkClient.close()` idempotent? Does it close a credentials provider the caller passed to `credentialsProvider(...)`? What happens on a call after close? Are sync service clients documented as thread-safe? Pages: javadoc `.../core/SdkClient.html`; SDK guide `best-practices.html`.
- **Q3. Default sync HTTP client.** How is it chosen when several HTTP client implementations are on the classpath (for example via the `software.amazon.awssdk.http.service.impl` property)? Which HTTP client artifacts do the service modules pull in, and at which Maven scope? Page: SDK guide `http-configuration.html`.
- **Q4. Default retry strategy.** What are the default retry mode and maximum attempts (any DynamoDB-specific default)? Which errors are retried? How does it interact with `apiCallTimeout` and `apiCallAttemptTimeout`? Pages: SDK guide `retry-strategy.html` and `timeouts.html` (or the ClientOverrideConfiguration javadoc).

**S3**
- **Q5. Missing keys.** Which exception and status does GetObject raise for a missing key (`NoSuchKeyException`, 404), and which when the caller lacks s3:ListBucket (403)? What does HeadObject raise for a missing key (`NoSuchKeyException`, or `S3Exception` with 404 and no error code)? Pages: `https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObject.html` and `API_HeadObject.html`.
- **Q6. Checksums.** With `ChecksumMode=ENABLED`, do HeadObject and GetObject return `ChecksumSHA256` for an object uploaded by a single PutObject with ChecksumSHA256? What is its encoding (base64 of the raw digest)? How do `ChecksumType` FULL_OBJECT and COMPOSITE (the `-N` suffix) differ? Page: `https://docs.aws.amazon.com/AmazonS3/latest/userguide/checking-object-integrity.html`.
- **Q7. GetObject stream.** Is content-length available from the sync `ResponseInputStream` before the body is read? Must the stream be closed or aborted to release the connection, and what does `abort()` do? Pages: javadoc `.../services/s3/S3Client.html` (getObject) and `.../core/ResponseInputStream.html`.
- **Q8. ListObjectsV2.** What is the maximum MaxKeys (1000?) and the ordering guarantee for general purpose buckets (UTF-8 binary ascending)? Which fields does each `Contents` entry carry? In particular, is any content type or checksum value returned, or only `ChecksumAlgorithm` and `ChecksumType`? Page: `https://docs.aws.amazon.com/AmazonS3/latest/API/API_ListObjectsV2.html`.
- **Q9. Key length.** What is the maximum object key length in UTF-8 bytes (1024?)? Page: `https://docs.aws.amazon.com/AmazonS3/latest/userguide/object-keys.html`.

**Neptune**
- **Q10. ExecuteOpenCypherQuery.** What are the request fields (`openCypherQuery`, `parameters` as a JSON string)? What shape does the `results` document have (rows as maps of column to value), and how are integers, strings and nulls represented? Which errors can it return (for example MalformedQuery, QueryLimitExceeded, TimeLimitExceeded, ConcurrentModification, ReadOnlyViolation, Throttling, BadRequest), and which are retryable? Page: `https://docs.aws.amazon.com/neptune/latest/data-api/API_ExecuteOpenCypherQuery.html`.
- **Q11. neptunedata client setup.** Is an endpointOverride of `https://<cluster-ro endpoint>:8182` required? What SigV4 signing name does it use? Which IAM action covers a read query (`neptune-db:ReadDataViaQuery`)? Is a per-query timeout available? Pages: `https://docs.aws.amazon.com/neptune/latest/userguide/neptune-data-api.html`, `.../userguide/iam-dp-actions.html`, `.../userguide/parameters.html` (neptune_query_timeout).
- **Q12. openCypher support.** Does Neptune openCypher support `id(n)` returning the bulk-loaded `~id` string, `RETURN DISTINCT`, `ORDER BY` and a parameterized `LIMIT $limit`? Are JSON-number parameters treated as integers? Page: `https://docs.aws.amazon.com/neptune/latest/userguide/feature-opencypher-compliance.html` (and `access-graph-opencypher-queries.html`).

**S3 Vectors**
- **Q13. QueryVectors.** What are the request fields and the topK maximum? What does the response carry (key, distance, metadata)? Is it sorted by ascending distance, and is smaller always closer for cosine and euclidean? Page: `https://docs.aws.amazon.com/AmazonS3/latest/API/API_S3VectorBuckets_QueryVectors.html`.
- **Q14. GetVectors.** What is the maximum number of keys per request? Are missing keys omitted or reported as an error? What do returnData and returnMetadata do? Is float32 data returned bit-exactly? Page: `https://docs.aws.amazon.com/AmazonS3/latest/API/API_S3VectorBuckets_GetVectors.html`.
- **Q15. Filter syntax.** What is the filter syntax for `$eq`, `$in`, `$gte`/`$lte` (or `$range`) and `$and`? How does equality match an array-valued metadata field (does "contains" match)? What numeric types are supported? Page: `https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-vectors-metadata-filtering.html`.
- **Q16. GetIndex.** What does the response carry (dimension, distanceMetric values, dataType, `metadataConfiguration.nonFilterableMetadataKeys`)? What error does a missing index raise (NotFoundException, 404)? Which errors indicate throttling or unavailability? Page: `https://docs.aws.amazon.com/AmazonS3/latest/API/API_S3VectorBuckets_GetIndex.html`.
- **Q17. Limits and Region.** What are the service limits: topK maximum, GetVectors key maximum, total and filterable metadata size, metadata key count, non-filterable key count, dimension range? Is S3 Vectors available in us-east-1? Pages: `https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-vectors-limitations.html` and `.../s3-vectors-regions.html`.
- **Q18. Java metadata mapping.** In the SDK, how is metadata exposed as `software.amazon.awssdk.core.document.Document` (numbers as `SdkNumber`, lists, strings)? Is `VectorData.float32()` a `List<Float>`? Pages: javadoc `.../services/s3vectors/S3VectorsClient.html` and `.../services/s3vectors/model/VectorData.html`.

**DynamoDB (the UNVERIFIED items that affect these adapters)**
- **Q19. ALL_OLD on a failed condition.** With `ReturnValuesOnConditionCheckFailure=ALL_OLD`, does `ConditionalCheckFailedException.item()` always carry the existing item? This is the 2-versus-3 SDK-call case in `OwnershipRecordSdkCalls`. Page: `https://docs.aws.amazon.com/amazondynamodb/latest/APIReference/API_PutItem.html`.
- **Q20. BatchGetItem.** Is the per-call limit 100 keys and 16 MB? What are the UnprocessedKeys semantics, and is ConsistentRead set per table? Page: `.../amazondynamodb/latest/APIReference/API_BatchGetItem.html`.
- **Q21. Query paging.** Is the page limit 1 MB, and how do Limit and LastEvaluatedKey interact? Page: `.../amazondynamodb/latest/APIReference/API_Query.html`.
- **Q22. Table names and nesting.** Is the table-name rule `[a-zA-Z0-9_.-]{3,255}`? Is the maximum attribute nesting depth 32? Page: `.../amazondynamodb/latest/developerguide/ServiceQuotas.html` (Naming rules and constraints).

## Design decisions for the orchestrator (they affect correctness)

- **D1.** list's `ObjectMeta` needs sha256 and contentType, which ListObjectsV2 probably does not return. My recommendation is one HeadObject per listed key, bounded by maxKeys. The alternative is a list result without verified metadata, which would mean changing the port contract, and I would not do that.
- **D2.** An object without a full-object SHA-256 checksum, for example one uploaded outside the loader, is `Corrupt` (fail closed). There is no metadata-only fallback.
- **D3.** Making sure graph reads hit the reader endpoint is mainly infra and IAM's job. Storage can only check the URI with a heuristic; core validation would be better (G2).
- **D4.** For the S3 bucket allowlist I propose raw, curated and context. Whether the runtime also reads reports or logs zones is undecided (core has no per-zone keys, a known SE-P7-02 gap).
- **D5.** If the fetched filter semantics (Q15) differ from storage's `VectorFilters.matches`, my recommendation is that the adapter refuses that filter shape rather than silently changing results.
- **D6.** The one-HeadObject-per-key path makes `list` cost up to maxKeys + 1 calls. Service read deadlines must allow for this (G4).
- **D7.** Phase B can build adapter settings types now (the way `DynamoDbClientSettings.of` takes caller values, with no defaults) and test them with stubs. Production composition still needs G1-G3 and G5 before any of it is used.

## Report contract

STATUS: COMPLETE
OWNER: storage-engineer
WORK ORDER: SE-INT-04 phase A (read-only planning), integration round 01, production storage dependencies, 2026-11-22
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN:
- `git status --short` (succeeded)
- `git ls-files --others --exclude-standard -- modules/storage modules/core modules/services` (succeeded)
- `git ls-files -- modules/storage/src/main` (succeeded)
- `git ls-files -- project build.sbt` (succeeded)
- One `git grep` call was refused by guard-dangerous.sh (shell-syntax-gate, a quote character); nothing ran. No AWS, network or sbt command was run.
TEST RESULTS: none (planning only; no build run)
VALIDATION RESULTS: none
ASSUMPTIONS:
- The ProductionDependencies signatures in the uncommitted `ProductionComposition.scala` are the binding contract.
- The loader's create-once S3 writes record ChecksumSHA256, per the ObjectWriter port doc.
- The S3 Vectors index names are storage's `VectorIndexes` names.
- `storage.vectorIndex` is not used by the readers.
- The SDK modules at BOM 2.57.8 contain the neptunedata and s3vectors operations listed above. This is unverified until compile.
RISKS:
- Every SDK behaviour, limit and error code above is UNVERIFIED until Q1-Q22 are answered.
- Neptune openCypher support for the existing templates (Q12) and S3 Vectors float32 bit-exactness (Q14) could force template or verification changes that are outside this order.
- Without G5, the SDK clients are never closed in production.
- Without G4, the call timeouts of the new adapters are not bounded by the service read deadlines.
- CR-INT-06 residuals (-1 to -6) and the A1-W residual remain recorded for deployment preparation. The human has not accepted them.
MANUAL ACTION REQUIRED: The orchestrator fetches answers to Q1-Q22 from the listed public pages, recording the date each was checked, and decides D1-D7 or routes them to the human.
BLOCKED-ON OWNER: none for phase B storage work. Production use additionally needs domain-engineer (G1-G3), service-engineer (G4, G5), possibly build-engineer (an explicit HTTP client, only if Q3 requires one), and infra-engineer (IAM and network routing items).
BLOCKED-ON EVIDENCE: none (no blocking condition for phase B; the dependencies are listed under CONFIG GAPS and CLASSPATH GAPS above)
NEXT GATE: The orchestrator fetches the documentation answers and issues phase B.
