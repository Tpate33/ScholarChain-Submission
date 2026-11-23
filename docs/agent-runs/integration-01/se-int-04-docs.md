# SE-INT-04 documentation answers (orchestrator, via web-fetch of public AWS docs, checked 2026-11-22)

Condensed from five web-fetch reports. Quotes are abridged. Each answer is labelled CONFIRMED (the page states it), INFERRED (follows from page text), or NOT STATED (the page doesn't say; treat as unverified, P). Javadoc was read at SDK 2.59.7; the project pins 2.57.8.

## SDK for Java v2

**Q1. Credentials (CONFIRMED)**
- The chain order is: system properties, environment variables, web identity token plus role ARN, shared credentials/config `[default]` profile, ECS container credentials, then EC2 instance profile.
- `DefaultCredentialsProvider.create()` returns a deprecated singleton. The javadoc warns it "can cause issues if one client closes the provider while others are still using it."
- The javadoc recommends `builder().build()` for independent instances.
- Sources: https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/credentials-chain.html and https://docs.aws.amazon.com/java/api/latest/software/amazon/awssdk/auth/credentials/DefaultCredentialsProvider.html
- **Implication:** create one owned provider with `builder().build()`, and have the bundle close it.

**Q2. Client lifecycle (partly CONFIRMED)**
- CONFIRMED: "All service clients are thread safe". The docs recommend sharing one client and closing it when it is no longer needed. Source: best-practices.html.
- NOT STATED:
  - whether `close()` is idempotent;
  - whether closing a client closes a provider the caller passed in;
  - what happens on a call after close.
- **Implication:** the storage wrappers must make their own `close()` idempotent, close the provider explicitly and separately from the clients, and refuse use after close themselves.

**Q3. HTTP client (CONFIRMED)**
- Apache5HttpClient has been the default synchronous client since 2.46.0.
- Service modules add a *runtime* dependency on `apache5-client`.
- You don't need to configure it explicitly.
- NOT STATED: how the SDK chooses when several HTTP implementations are on the classpath.
- Sources: http-configuration.html and http-configuration-apache5.md.
- **Implication:** no build change is needed as long as exactly one sync HTTP implementation is on the runtime classpath. Verify the services runtime classpath has one.

**Q4. Retries and timeouts (CONFIRMED)**
- Standard is the default retry strategy from **2.58.0**. Before 2.58.0 the legacy strategy is the default. The project pins **2.57.8, so the legacy strategy applies**.
- Standard retry: 3 attempts in total, but DynamoDB clients make 4.
- Legacy retry: 4 attempts in total, and the docs imply DynamoDB makes 9 ("4 total attempts instead of 9").
- `apiCallTimeout` "Includes all retry attempts" and the waits between them.
- When `apiCallAttemptTimeout` expires, the SDK retries.
- The SDK sets neither timeout by default.
- The javadoc says the abort is not strictly guaranteed: "doesn't have strict guarantees on how quickly a request is aborted".
- Sources: retry-strategy.html, timeouts.html, ClientOverrideConfiguration javadoc.
- **Implications:**
  - Every adapter must set both timeouts. The validation already requires them.
  - Pin the retry strategy explicitly, so behaviour doesn't change silently with the SDK version.
  - The "abort not strictly guaranteed" caveat feeds the A1-W/P3 residual.

## S3

**Q5. Missing objects**
- GetObject on a missing key returns 404 NoSuchKey if the caller has s3:ListBucket. Without that permission it returns 403 AccessDenied (CONFIRMED).
- A HEAD error carries "no response body" and a generic code (404, 403 and so on). "It's not possible to retrieve the exact exception" (CONFIRMED).
- **Implications:**
  - Missing means a 404 status, for both GET and HEAD.
  - A 403 is Permanent, never "missing".
  - IAM must grant s3:ListBucket (infra-engineer).

**Q6. Checksums**
- `x-amz-checksum-mode: ENABLED` is required to retrieve the checksum (CONFIRMED).
- `x-amz-checksum-sha256` is "The Base64 encoded, 256-bit SHA256 digest ... only present if the checksum was uploaded with the object" (CONFIRMED).
- PutObject uses the full-object checksum type (CONFIRMED). For SHA256, "full object" is listed only for the CRC algorithms; SHA256 multipart uploads are COMPOSITE.
- NOT STATED: whether a "-N" suffix appears on composite checksum values.
- Sources: GetObject and HeadObject API pages, checking-object-integrity-upload.html.
- **Implication:** accept a SHA256 checksum only when ChecksumType is FULL_OBJECT, or the checksum is a plain 44-character base64 value. Treat a COMPOSITE or suffixed value as Corrupt (consistent with D2).

**Q7. Response stream (CONFIRMED)**
- Close the stream after reading it, or call `abort()` to drop the connection.
- The stream auto-aborts after 60 s if `read()` isn't called.
- With the Apache client, `close()` reads the remaining data.
- **The checksum mismatch is reported only on the read that returns -1.**
- Content-Length is a documented response header. NOT STATED: the Java accessor, which is expected to be `GetObjectResponse.contentLength()`.
- **Implications:**
  - Read to EOF within the size bound, so the SDK's checksum validation fires. The adapter also recomputes SHA-256 itself.
  - Abort when the size bound is exceeded.

**Q8. ListObjectsV2 (CONFIRMED)**
- MaxKeys is at most 1,000.
- Results are in lexicographic order by UTF-8 byte value.
- Contents entries carry Key, LastModified, ETag, Size, StorageClass, ChecksumAlgorithm, ChecksumType, plus Owner and RestoreStatus on request.
- **No ContentType and no checksum value** are returned.
- **Implication:** D1 is confirmed: one HeadObject per listed key.

**Q9. Keys (CONFIRMED)**
- Object keys are at most 1,024 UTF-8 bytes.

## Neptune

**Q10. ExecuteOpenCypherQuery**
- The API reference body uses `"query"` and `"parameters"`, where parameters is a JSON string. SDK examples name the field `openCypherQuery`. **Verify the field name at compile time.**
- Results come back as `{"results":[{col: value}]}`: integers are JSON numbers, strings are JSON strings, IDs are strings (CONFIRMED).
- NOT STATED: how null is represented.
- Retry table (errors-engine-codes.html):
  - Retry Yes: CancelledByUser, ConcurrentModification, ConstraintViolation, FailureByQuery, MemoryLimitExceeded, QueryLimitExceeded, Throttling (500), TooManyRequests (429).
  - Retry Maybe: TimeLimitExceeded.
  - Retry No: AccessDenied (403), BadRequest, InternalFailure, InvalidNumericData, InvalidParameter, MalformedQuery, MethodNotAllowed, MissingParameter, QueryLimit, QueryTooLarge, ReadOnlyViolation, UnsupportedOperation.
  - NOT STATED (listed without a retry flag): ClientTimeout (408), Parsing, PreconditionsFailed.
- **Implication:** classify errors by this table, with TimeLimitExceeded as Transient and an unknown null as Corrupt.

**Q11. Client setup**
- The endpoint override is `https://<cluster endpoint>:<port>`, default port 8182 (CONFIRMED).
- NOT STATED: whether the reader endpoint works.
- The SigV4 service name is `neptune-db`.
- The IAM action for reads is `neptune-db:ReadDataViaQuery`.
- A per-query timeout exists as the hint `USING QUERY:TIMEOUTMILLISECONDS n`, from engine 1.3.2.0. The effective timeout is the minimum of the hint and the cluster-level `neptune_query_timeout` (default 120,000 ms).
- The neptune-data-api.html overview page returned no content.
- **Implication:** the adapter does not rewrite templates to add the hint. The server-side bound is the cluster parameter (infra-engineer). The client-side bound is the SDK timeouts.

**Q12. openCypher**
- `id(n)` returns the loader ID as a string, with a namespace prefix when the column has one (CONFIRMED).
- DISTINCT and ORDER BY are supported.
- **RETURN and WITH are unsupported "when used with non-static values for SKIP or LIMIT"** (CONFIRMED).
- NOT STATED: whether a parameterized `LIMIT $limit` counts as static.
- NOT STATED: how numeric parameters are typed.
- **RISK:** if storage's GraphQueries templates use `LIMIT $limit`, Neptune may reject them. Verify against a live engine (P), and report the affected templates.

## S3 Vectors

**Q13. QueryVectors**
- topK must be at least 1. The limits page gives "Up to 10,000" but **"Results per page in a QueryVectors response: Up to 100"**, and the response has `nextToken`.
- The response carries `distanceMetric` (euclidean or cosine) and vectors with key, distance (Float), metadata and scores.
- Results are "ranked by vector distance".
- NOT STATED: ascending order, and whether smaller means closer.
- **Implications:**
  - Either cap maxTopK at 100 so one page suffices, or follow nextToken with a bound.
  - The "Corrupt unless sorted ascending" check rests on an unstated assumption. Make it explicit and P.

**Q14. GetVectors**
- At most 100 keys. returnData and returnMetadata both default to false (CONFIRMED).
- NOT STATED: how missing keys behave, and whether float32 data is bit-exact.
- **Implication:** handle missing keys both as omitted and as an error. Bit-exact float32 is P; the verified facade's `dataSha256` check fails closed if it isn't.

**Q15. Filters (CONFIRMED)**
- Supported: `$eq $ne $gt $gte $lt $lte $in $nin $exists $and $or`, plus `$startsWith`, which needs the ENHANCED index mode.
- `$eq` against an array value is true if any element matches.
- Supported types are string, number, boolean and list.
- `$in` and `$nin` take a non-empty array of primitives.
- On ENHANCED indexes, at most 100 filter constraints.
- **Implication:** compare against `VectorFilters.matches` and refuse any shape whose semantics differ (D5).

**Q16. GetIndex (CONFIRMED)**
- The response is `index{dimension, dataType, distanceMetric, metadataConfiguration.nonFilterableMetadataKeys, ...}`.
- A missing index is NotFoundException (404).
- Errors: TooManyRequestsException (429), ServiceUnavailableException (503).

**Q17. Limits (CONFIRMED)**
- topK up to 10,000, at most 100 per page.
- GetVectors up to 100 keys.
- Total metadata 40 KB on CLASSIC indexes and 100 KB on ENHANCED.
- Filterable metadata 2 KB on CLASSIC and 40 KB on ENHANCED.
- At most 50 metadata keys and at most 10 non-filterable keys.
- Dimension from 1 to 4,096.
- **Region availability in us-east-1: NOT VERIFIED.** The page returned no content.

**Q18. Java model (CONFIRMED)**
- `QueryOutputVector.metadata()` returns a `Document`, `distance()` returns `Float`, and `key()` returns `String`.
- `VectorData.float32()` returns `List<Float>`.

## DynamoDB

**Q19. ALL_OLD on failed conditions**
- With ReturnValuesOnConditionCheckFailure=ALL_OLD, the docs say the error carries "Item which caused the ConditionalCheckFailedException", but **NOT STATED** that the item is always present.
- **Implication:** keep the GetItem fallback. X-4 stays at 3 calls.

**Q20. BatchGetItem (CONFIRMED)**
- At most 100 items and 16 MB.
- UnprocessedKeys has the same shape as RequestItems.
- ConsistentRead is set per table.
- **Duplicate keys cause a ValidationException.**

**Q21. Query (CONFIRMED)**
- A page reads up to `Limit` items or 1 MB, then applies the filter.
- `Limit` is "items to evaluate".
- An empty page can still carry a LastEvaluatedKey.

**Q22. Limits (CONFIRMED)**
- Table names are 3 to 255 characters from `[a-zA-Z0-9_.-]`.
- Items are at most 400 KB.
- Attributes nest at most 32 levels.
- TTL deletes items "typically within a few days"; there is no guarantee.
