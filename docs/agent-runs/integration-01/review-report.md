# Integration round 01 review reports (verbatim, assembled by the orchestrator)

## CR-INT-01 (code-reviewer, read-only): review of SE-INT-01 (DynamoDB adapter and durable ownership, job and idempotency stores)

VERDICT: REQUEST_CHANGES. One major finding remains (CR-INT-01-1).

Most of the work holds up when read against the code:
- **Conditional writes.**
  - putIfAbsent uses ALL_OLD and, when the failure carries no item, falls back to a consistent GetItem.
  - putIfVersion maps a failed condition to StaleVersion.
  - deleteIfRecord is fenced to the QueryJob and IdempotencyKey tables.
- **Reads.**
  - Query never returns a partial result when it hits the page bound.
  - BatchGetItem is chunked at 100 keys, removes duplicate keys, bounds its retries of unprocessed keys and treats an unrequested key as Corrupt.
- **Idempotency tickets.** The ticket check and the version check together protect against ABA, as long as replica clocks are not badly skewed.
- **Jobs.** Transitions are forward-only and safe to repeat when a write's outcome is ambiguous. Another client's job reads as None, and the Conflict messages name no client.
- **Isolation and secrets.**
  - The adapter can reach only five SDK operations.
  - No region, account ID or endpoint is hardcoded, and there are no secrets.
  - Provenance fields are present.
- **Tests.** The fake-versus-adapter parity suite compares results step by step, and the tests are not vacuous.
- **Ownership.** All edits are under modules/storage/**. The edits to TableSpecsSuite and RuntimeStoresTypeSuite are justified: 3 new TTL tables, and a new isolation test.

### Findings

**CR-INT-01-1 (major, L). Owner: storage-engineer. Idempotency `begin`, and partly `complete`, break when a DynamoDB write's outcome is ambiguous.**
- Where:
  - `modules/storage/src/main/scala/scholarchain/storage/state/ServiceStateStores.scala:474` generates `attemptId = tokens.next()` fresh on every `begin` call.
  - Lines 503-504 and 531 pass a Transient error from putIfAbsent or putIfVersion straight back to the caller, with no ticket.
  - Lines 559-575: `complete` re-reads the row and checks its hash, then maps StaleVersion to the "Replaced" error.
- Failure input:
  1. `begin` sends PutItem, and the item is written.
  2. The response is lost: ApiCallTimeout, a 500 after the write was applied, a services-side attempt timeout, or SDK retries that end in a condition failure.
  3. `begin` returns Transient (or StaleVersion on the expired-row replacement path) and no ticket.
  4. The services key-value policy retries Transient errors and timed-out attempts automatically (`modules/services/src/main/scala/scholarchain/services/Resilience.scala:359-360`), or the client retries with the same Idempotency-Key after the 5xx.
  5. The retry has a new attemptId, finds the stranded in-flight row and answers `InProgress`.
- Impact:
  - Nothing is executing, yet every request under that key gets InProgress until `inFlightTtlSeconds`, which is longer than the workflow timeout plus drain.
  - Services cannot release the key, because it never received a ticket to `abandon` with.
  - This breaks the port contract: InProgress should mean a live in-flight request exists. It hits exactly the failure that Idempotency-Key retries exist for. The in-memory store cannot fail this way.
  - Similarly, `complete` retried after an ambiguous but applied write returns StaleVersion, with a detail claiming the request "may have run again". The stored data is correct, but the error is false.
- Why the tests miss it: the stub throws injected faults before applying the write (`StubDynamoDb.scala:51-54`), so "applied, then error" is never exercised.
- Suggested fix:
  - Let services pass an attempt token per logical request and reuse it across retries. In `begin`, a live in-flight row with the same `attemptId` and request digest then returns `Fresh(ticket)` built from the stored row.
  - In `complete`, on StaleVersion, re-read the row and return `Right` when it already equals the completed record.
  - Add stub support for "apply, then throw", with tests on both backends.

**CR-INT-01-2 (minor, L; the exact limit is P). Owner: storage-engineer. The 256 KiB payload ceiling does not keep items under DynamoDB's 400 KB limit.**
- Where: `modules/storage/src/main/scala/scholarchain/storage/model/ServiceState.scala:56,63` checks the raw `outcomeJson`, `resultJson` and `errorJson`. But `ItemCodecs.scala:58,63` stores each one as a string inside the JSON `body`, so every `"` and `\` is escaped to 2 bytes.
- Failure input: a valid JSON outcome of about 250 KiB that is dense in quotes or backslashes, for example `["","",...]`. The encoded body is up to about 2x larger, over 400 KB.
- Impact:
  - PutItem fails with ValidationException, mapped to Permanent.
  - `succeed` fails, so the job stays running until its TTL; `complete` fails, so the key stays in flight.
  - The stub has no item-size limit, so no test catches this.
- Fix: bound the encoded item, or at least the `body` bytes, rather than the raw payload.

**CR-INT-01-3 (minor, L). Owner: storage-engineer (facade); service-engineer must wire accordingly. The claim that rag "cannot reach the new tables" is overstated.**
- Where:
  - `modules/storage/src/main/scala/scholarchain/storage/port/RuntimeStores.scala:156` wraps `KeyValueReader.readOnly(keyValues)`.
  - `modules/storage/src/main/scala/scholarchain/storage/port/KeyValueStore.scala:204-207` forwards every table role.
  - `RowPointer.parseKv` (`modules/rag/src/main/scala/scholarchain/rag/agents/EvidenceBuilder.scala:149`) accepts every `TableRole` tag, including the 3 new ones.
- Failure input: production composition passes one `DynamoDbKeyValueStore` with all 9 table names as `keyValues`. Then `stores.keyValues.get(TableRole.QueryJob, PrimaryKey("job#<id>","job"))` returns another client's job row, including clientId and resultJson.
- Impact: there is no read path to these tables today (EvidenceBuilder resolves only specific roles), so this is a latent cross-client read. The new test in RuntimeStoresTypeSuite checks return types only, not table scope.
- Fix: make the RuntimeStores read facade refuse `serviceState` tables, and test it.

**CR-INT-01-4 (minor, L). Owner: storage-engineer. Replica clock skew shortens the in-flight bound, and the settings check has no margin for it.**
- Where: `ServiceStateStores.scala:463` and `:510`. Whether a row is live is judged by the reading replica's clock against `expiresAt` set by the writing replica's clock. `ServiceStateSettings.of` (line 100) has no skew margin.
- Failure input: replica B's clock is Δ seconds ahead of replica A's. B treats A's live in-flight row as expired Δ seconds early and replaces it.
- Impact: a second execution while A is still well inside the workflow timeout. So the documented re-execution window is not the only consequence: skew moves it earlier by Δ. The completed replay window shrinks the same way. The report names skew only for ABA.
- Fix: document this and add a configured skew margin to the inequality, or record it as a human-accepted residual.

**CR-INT-01-5 (minor, L; P for which SDK exceptions actually surface). Owner: storage-engineer. Connection resets are mapped Permanent.**
- Where: `modules/storage/src/main/scala/scholarchain/storage/aws/dynamodb/DynamoDbMapping.scala:111-112,143-144`. Only an `InterruptedIOException` cause makes a client exception Transient; a `SocketException("Connection reset")`, SSL or other IOException cause becomes Permanent.
- Impact:
  - No data loss, because every write is conditional and a retry would be safe.
  - But Permanent skips the services retry and counts as a breaker failure (`Resilience.scala:364-366`).
  - On `begin`, it adds to CR-INT-01-1 through client retries.
- TransactionConflictException mapped Permanent is a nit: the adapter issues no transactions.

**CR-INT-01-6 (nit, P). Owner: storage-engineer. deleteIfRecord treats a failed condition without an item as Absent, with no re-read.**
- Where: `DynamoDbKeyValueStore.scala:425-426`. putIfAbsent re-reads in the same situation (lines 342-345).
- Impact: if DeleteItem does not return ALL_OLD, a Conflict collapses into Absent. Current callers (`remove`, `abandon`) treat both as `false`, so there is no impact today, but the port contract is not met.

**CR-INT-01-7 (nit, L). Owner: storage-engineer. The settings sum can overflow.**
- Where: `ServiceStateStores.scala:100`.
- Failure input: `drainMillis = Long.MaxValue` overflows `longestWorkflowTimeoutMillis + drainMillis` to a negative number, so the check passes. This is an absurd configuration; core config bounds would also close it.

### Items only real DynamoDB can settle (P)
- **P1 (CR-INT-01-1):** ALL_OLD on a failed PutItem or DeleteItem condition, and how often the SDK's internal retries end in a condition failure after the write was applied.
- **P2 (CR-INT-01-2):** the exact 400 KB item-size accounting.
- **P3 (CR-INT-01-5):** which client exceptions surface after the SDK's default retries.
- **P4 (CR-INT-01-6):** DeleteItem returning ALL_OLD on a failed condition.
- **P5:** numeric comparison in `#v = :expected`. The stub compares N values as strings; this is harmless because the adapter writes canonical `Long.toString`.
- **P6:** that DynamoDB returns N values in the same normalized form. `ItemCodec.decode` requires `expected == item` exactly.
- **P7:** the BatchGetItem limits (100 keys, 16 MB), the 1 MB Query page and the meaning of `Limit`, consistent reads on BatchGetItem, and the duplicate-key ValidationException.
- **P8:** how long TTL deletion is delayed. The code already treats expired rows as absent.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: CR-INT-01, integration round 01, review of SE-INT-01, 2026-11-22
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (Read, Grep and Glob only)
TEST RESULTS: not run by the reviewer. The reported sbt check (exit 0; suites=215, passed=2558, skipped=1; storage 607/607) was not observed by me.
VALIDATION RESULTS: findings checked against the source files listed above. Verified facts: the per-call attemptId; the stub injects faults before applying writes; the services retry policy retries Transient errors and timed-out attempts; the codec stores the payload inside the escaped body; RuntimeStores' read facade has no table scope; only an InterruptedIOException cause is Transient. Only modules/storage/** is touched, and no other module imports scholarchain.storage.aws or scholarchain.storage.state.
ASSUMPTIONS:
- SV-INT-02 will call the durable stores through the existing key-value resilience policy, as Stores.scala:126-129 requires for QueryOwnership. CR-INT-01-1 is reachable through client retries even without that.
- The working tree matches the SE-INT-01 file list.
RISKS:
- Durable idempotency keys can be stranded as InProgress for inFlightTtlSeconds (CR-INT-01-1).
- Large, quote-dense outcomes fail to store (CR-INT-01-2).
- A latent cross-client read path depends on how SV-INT-02 wires the stores (CR-INT-01-3).
- Clock skew shortens the in-flight bound (CR-INT-01-4).
- The stub's DynamoDB semantics come from memory (P1-P8).
MANUAL ACTION REQUIRED:
- The human decides whether CR-INT-01-1 must be fixed now or accepted as a residual.
- The human accepts or overrules the in-flight TTL design decision, taking CR-INT-01-4 into account.
- Settle P1-P8 on real DynamoDB before production use.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: The orchestrator routes CR-INT-01-1 to CR-INT-01-7 to storage-engineer and the CR-INT-01-3 wiring note to service-engineer (SV-INT-02). Then the repaired diff is re-reviewed, before DE-INT-01 and SV-INT-02 rely on the IdempotencyKeyStore ticket API.
VERDICT: REQUEST_CHANGES
FINDINGS:
- storage-engineer:
  - CR-INT-01-1 major (L), ServiceStateStores.scala:474,503-504,531,559-575
  - CR-INT-01-2 minor (L/P), ServiceState.scala:56,63 and ItemCodecs.scala:58,63
  - CR-INT-01-3 minor (L), RuntimeStores.scala:156 and KeyValueStore.scala:204-207 (service-engineer: wiring)
  - CR-INT-01-4 minor (L), ServiceStateStores.scala:100,463,510
  - CR-INT-01-5 minor (L/P), DynamoDbMapping.scala:111-112,143-144
  - CR-INT-01-6 nit (P), DynamoDbKeyValueStore.scala:425-426
  - CR-INT-01-7 nit (L), ServiceStateStores.scala:100
UNVERIFIED CONCERNS:
- A job queued for longer than jobTtlSeconds before start() would be refused as expired (Permanent). Whether the services job queue wait is bounded below jobTtlSeconds is not checked here.
- QueryJobStore.start treats any running row as "same", so a second worker's start returns Right. That is harmless only while the creating replica is the only executor (SV-INT-02 to confirm).
- The in-flight bound starts at begin. If services do work between begin and the workflow call (pool wait, ownership recording), the workflow timeout plus drain may not cover the whole in-flight period (service-engineer to confirm).
- The reflective Proxy surface test and the compileErrors checks in DynamoDbSurfaceSuite were not read line by line.
```

## CR-INT-09 (code-reviewer, read-only): focused review of DE-INT-04

**Verdict: APPROVE.** No blocking findings in DE-INT-04's core scope. Two minor findings and two nits, all non-blocking.

### Non-blocking findings

**CR-INT-09-1 (minor, L; domain-engineer, after SV-INT-03).** No timing rule bounds the API-key source's SDK call, and no ceiling bounds its cache lifetime.
- Where: AdapterConfig.scala:245-248; rule table at :428-518.
- Failure input: `services.apiKeys.apiCallTimeoutMillis = 86400000` with attempt 1000, or `cacheTtlMillis = 31536000000`. Both are accepted, because only KEY-1 applies.
- Impact:
  - `authenticate` calls `source.clients()` synchronously on every Bearer request (Auth.scala:77-78), so a slow call can hold a worker up to the call timeout.
  - A long TTL delays key revocation.

**CR-INT-09-2 (minor, L; domain-engineer).** For an in-memory config, `forLauncher` can return values the storage constructors refuse.
- Where: ProductionConfig.scala:1713-1726, 1746, 1764, and the `applies` guards at AdapterConfig.scala:459 and :488.
- Failure input: an in-memory config with `aws.region`, `storage.vectorBucket` or `rawBucket` null or invalid.
- Cause: `ruleInputs` leaves nulls out, so ADP-1 and S3V-3 don't apply, and `forLauncher` doesn't re-check bucket names.
- Impact: it fails closed, but later, at storage construction. ConfigLoader input is unaffected (ConfigLoader.scala:550-551, 715-725).

**CR-INT-09-3 (nit, P; domain-engineer).** The ARN tail is limited to 512 characters.
- Where: AdapterConfig.scala:161-163.
- Failure input: a 512-character name plus the 7-character AWS suffix is refused. This fails closed; the pattern is already UNVERIFIED.

**CR-INT-09-4 (nit, L; domain-engineer).** NEP-3 handles a trailing `/` on one side only, and its host comparison is case-sensitive.
- Where: AdapterConfig.scala:190-194.
- Impact: a spurious refusal, which fails closed.

### Verification

1. **Constructor compatibility.** Matches in both directions, apart from CR-INT-09-2:
   - retry 1..10;
   - timeouts with attempt < call;
   - maxObjectBytes and maxListKeys;
   - maxTopK and maxGetKeys with S3V-2;
   - maxRows;
   - `neptuneEndpointOk` is a line-by-line copy of `checkEndpoint`;
   - the vector-bucket pattern;
   - ADP-1 and `KeyBound.Endpoint` are stricter than storage.
2. **Timing rules.** Correct.
   - No rule multiplies attempts by apiCallTimeout; a test shows retries 1 and 10 give the same boundaries.
   - Call counts match the code: S3 list is 1 + keys, S3 get and head are 1 each, Neptune is 1, and S3 Vectors is QueryVectors plus the GetVectors re-read.
   - Each rule binds the correct process.
   - X-4 = 3·apiCall still holds.
   - The streaming-body limitation is stated explicitly.
   - Arithmetic saturates.
3. **forLauncher.** Safe and sufficient.
   - The per-process required sets match ProductionComposition.scala:236-240.
   - `forProcess` produces no adapter settings, so misusing it is visible.
   - Requiring the new keys belongs to SV-INT-03. SV-INT-03 should add a test that its launcher refuses a config without the adapter sections.
4. **Compatibility.** Preserved.
   - The legacy free-text Neptune endpoint still loads.
   - NEP-3 applies only when the typed key is set.
   - With no adapter keys, the JSON and digest are unchanged.
   - `ProductionRules.all` and `ProductionKeys.all` are unchanged.
   - Error messages are value-free; the SECRETX tests confirm this.
5. **API-key settings.** Minimal, with `secretId` as a reference only and the patterns marked UNVERIFIED. Inherent residual: a raw value that happens to fit the name pattern passes.
6. **Tests.** Meaningful, boundary-exact and non-vacuous. Every rule ID is covered.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: CR-INT-09, integration round 01, 2026-11-22: focused read-only review of DE-INT-04 (core config contracts for the storage adapters and the API-key source).
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none
TEST RESULTS: not rerun, as the order said. Reported: sbt check exit 0; suites=228; passed=2820; skipped=1; core 657.
VALIDATION RESULTS: All six verification points were checked by reading the files. Storage constructors read: AwsCommon.scala, DynamoDbSettings.scala, S3ObjectReader.scala, NeptuneGraphReader.scala, S3VectorsReader.scala, VectorStore.scala. Services files read (read-only): ProductionComposition.scala, Auth.scala. Core files read: AdapterConfig.scala, ProductionConfig.scala, ScholarChainConfig.scala, ConfigLoader.scala, AdapterConfigSuite.scala.
ASSUMPTIONS: (L) means verifiable locally and (P) means it needs live AWS or the AWS documentation. Production launchers load through ConfigLoader. The query-jobs rag reads will run under the query-jobs read policy; this is the wiring obligation the report itself flags.
RISKS: A launcher could call forProcess instead of forLauncher (SV-INT-03 obligation). The GetObject body read is unbounded (preserved limitation). The rag-path read-policy wiring is not yet verified. The Secrets Manager call and cache TTL have no timing rule (CR-INT-09-1).
MANUAL ACTION REQUIRED: none from this review. The human decides whether to accept the non-blocking findings or route them.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: SV-INT-03 launcher wiring (service-engineer) through ProductionSettings.forLauncher. CR-INT-09-1 to -4 can be routed to domain-engineer then or later.
VERDICT: APPROVE
FINDINGS:
- CR-INT-09-1, minor, L: no timing rule or TTL ceiling for services.apiKeys (AdapterConfig.scala:245-248). Owner: domain-engineer, after SV-INT-03.
- CR-INT-09-2, minor, L: forLauncher on an in-memory config with null region or buckets (ProductionConfig.scala:1713-1726; AdapterConfig.scala:459, :488). Owner: domain-engineer.
- CR-INT-09-3, nit, P: the ARN tail is limited to 512 characters (AdapterConfig.scala:161-163). Owner: domain-engineer.
- CR-INT-09-4, nit, L: NEP-3 trailing-slash and case asymmetry (AdapterConfig.scala:190-194). Owner: domain-engineer.
UNVERIFIED CONCERNS: I did not trace whether rag's workflow reads in query-jobs actually run under a ReadPolicy attempt bounded by readTimeoutMillis. The T-rules bound those reads only if they do.
```

## CR-INT-08 (code-reviewer, read-only): focused re-review of CR-INT-07-1 and -2 after SE-INT-05

**Verdict: APPROVE.** Both blockers are closed. No new blocking finding.

### CR-INT-07-1: CLOSED (L). Live S3 Vectors behaviour stays P.

- **Translation (S3VectorsReader.scala:209-238).**
  - `In(StrList, Vector(v))` becomes `$eq`; `In` with more than one value becomes `$or` of `$eq`.
  - Q15 CONFIRMED that `$eq` on an array matches when any element matches, and that `$or` exists. The translation is therefore exactly `xs.exists(vs.contains)` (Vectors.scala:695).
  - The `Eq`, string-`In`, `Range` and `And` translations are unchanged, and no other `In` or `Eq` translation diverges.
  - `$or` nested inside `$and` is marked P (lines 140-142).
- **Fail-closed handling.**
  - `VectorFilters.problems` refuses a null or empty set before translation (Vectors.scala:671-672; S3VectorsReader.scala:184-186).
  - A null or empty element is refused at line 213, before `tooLong`, so it cannot cause an NPE.
  - An empty `$or` or `$in` can never be built.
- **Constraint cap (lines 162-166, 188-193).**
  - The count covers the whole tree: `$and`, `$or` and each comparison count 1, and `$in` counts 1 per value. A filter is refused when the count exceeds 100.
  - Arithmetic checked: researcher n+8 (92 max), paper n+1 (99 max), community n+7.
- **The 2048-byte bound** is reasonable and marked P. It is derived from the 2 KB CLASSIC filterable total. On ENHANCED indexes it could refuse a value that might have matched, which is the safe direction.
- **Real rag shapes (SemanticContextAgent.scala:98-102, 112-128).** Both are tested exactly, including the one-id forms and the document that reaches the stub request (S3VectorsFilterSuite.scala:32-81, 41-49).
- **Equivalence test (lines 83-118).**
  - It covers 2,912 combinations, including missing and empty lists and windows.
  - The evaluator throws on shapes it does not model, so a regression back to `$in` fails rather than passing.
  - It asserts that at least one combination matches.
- **S3VectorsReaderSuite changes (lines 144-147, 227-229).** These are legitimate. The old assertions encoded the defect; the empty set and the `Vector("")` refusals are kept.

### CR-INT-07-2: CLOSED (L). The profile `endpoint_url` residual stays P and is documented.

- **Guard placement.** The guard runs before every affected SDK builder:
  - `SdkBuilders.s3Client`: StorageClients.scala:77
  - `SdkBuilders.s3VectorsClient`: :104
  - `objectReader`: :268
  - `vectorReader`: :295
  - `DynamoDbClientFactory.apply`: :208
  - `DynamoDbClients.build`, 2-argument: DynamoDbSettings.scala:88-89
  - 3-argument: delegates to the 4-argument form at :117
  - 4-argument: :136
  - Neptune takes an explicit endpoint (:92).
- **No unguarded builder.** The only SDK client builders under modules/*/src/main are StorageClients.scala:79, 89 and 106 and DynamoDbSettings.scala:91 and 138. Services main builds no SDK clients; the only reference is the deferred scaladoc at ProductionComposition.scala:189 (CR-INT-07-3).
- **Matching (AwsCommon.scala:155-171).**
  - Case-insensitive (`Locale.ROOT`) and presence-based: an empty value also refuses.
  - Covers both environment variables and system properties.
  - Messages carry names only (:188-191). An unreadable source reports only the exception class name (:152).
- **Explicit override.** It bypasses the guard (:179). The override is already validated as https, or http on a loopback host (DynamoDbSettings.scala:45).
- **Production default.** `AmbientSettings.process` reads environment and property names at each check and holds no values (AwsCommon.scala:107-113). `SdkBuilders.production`, `StorageClients.open()` and the 2- and 3-argument builders all use it.
- **Unreadable source.** A null source or a throwing reader returns Refused; `open(null)` returns Left.
- **Profile residual.** Documented at AwsCommon.scala:139-144.
- **Tests (AmbientEndpointsSuite).**
  - Nine forms are tested against every path. `providers.size == 1` proves no stub builder was reached except Neptune's.
  - The real builders throw before building.
  - The override bypass is verified on real clients.
  - Unrelated names, a failing reader and the process default are covered.
  - The forked-JVM case (`AWS_ENDPOINT_URL` blanked by the build) is refused by the guard.
  - The StorageClientsSuite `withSeams(..., AmbientSettings.empty)` calls and the comment at :209-212 are legitimate.
- **Can a production caller reach an SDK builder without the guard?** No. The only way around it is to deliberately inject a non-process `AmbientSettings` (N1).

### Regressions

None found within the scoped files.
- The 2-argument DynamoDB retry is unchanged, and Neptune is unchanged.
- No hardcoded endpoints or credentials in main code.
- No fakes on a production path.
- Services main does not reference the changed APIs, so the `withSeams` change breaks no other module.

### BLOCKING findings

None.

### Optional nits (non-blocking; no new review round requested)

- **N1 (minor, L, storage-engineer).** Production code could opt out of the guard by passing `AmbientSettings.empty` or a stub. The public entry points are:
  - `AmbientSettings.empty` (AwsCommon.scala:116) and the `AmbientSettings` constructor;
  - `StorageClients.open(ambient)` (StorageClients.scala:321);
  - the 3-argument `SdkBuilders.s3Client` and `s3VectorsClient`;
  - `forAmbient`;
  - the 4-argument `DynamoDbClients.build`.
  
  No such caller exists today. Making these `private[storage]` would close it.
- **N2 (nit, L, storage-engineer).** Stale scaladoc: DynamoDbSettings.scala:110-111 still says "kept unchanged", and the 2-argument scaladoc at :79-84 does not mention the guard.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: CR-INT-08, integration round 01, focused read-only re-review of CR-INT-07-1 and CR-INT-07-2 after SE-INT-05, 2026-11-22
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (Read, Grep and Glob only)
TEST RESULTS: not observed by me. Reported by storage-engineer: `sbt check` exit 0; suites=227, passed=2808, skipped=1; storage 795/795; tests 269 passed, 1 skipped.
VALIDATION RESULTS: none run by me. Static verification: translation and count arithmetic checked by hand against `VectorFilters.problems` and `matches`; guard placement checked on every SDK client builder under modules/*/src/main.
ASSUMPTIONS: I could not run git, so I cannot confirm from a diff that SE-INT-05 edited only modules/storage/**; I relied on repair-05.md (the other modified files in the session's git status, in core and services, predate this repair). I treated the S3VectorsFilterSuite Q15 evaluator as a faithful model of the CONFIRMED doc statements; its behaviour for a missing field is the existing P item.
RISKS: These remain P: live S3 Vectors filter semantics, constraint counting, `$or` nested in `$and`, the 2048-byte bound on ENHANCED indexes, and the profile `endpoint_url` route. N1: the guard can be bypassed by explicit injection. More than 92 researcher ids (99 for paper-abstract) are now refused; rag-engineer may want to bound or batch them.
MANUAL ACTION REQUIRED: The human decides whether to accept the P residuals. N1 and N2 are optional and can be routed to storage-engineer at the human's discretion, with no review round required.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Human acceptance of the integration-01 SE-INT-05 repair. CR-INT-07-3 to -6 stay deferred, and the G5 production wiring (service-engineer) and IAM and endpoint_url absence (infra-engineer) remain open.
VERDICT: APPROVE
FINDINGS: none blocking; non-blocking N1 (minor) and N2 (nit), above.
UNVERIFIED CONCERNS: the actual S3 Vectors service behaviour for the translated `$or` of `$eq` filters and the 100-constraint accounting. These cannot be verified offline (P).
```

## CR-INT-07 (code-reviewer, read-only): review of the SE-INT-04 production storage adapters

VERDICT: REQUEST_CHANGES

I found two major issues. Most of the adapter code holds up: the port fit, lifecycle, S3 checksum and bounds handling, Neptune decoding and the error table all check out. But the S3 Vectors filter translation breaks the production retrieval path that rag uses today. And the environment-endpoint issue is only worked around inside a test; production code has no guard.

### BLOCKING findings

**CR-INT-07-1 (major, (L)). Owner: storage-engineer. The adapter refuses `In` on list fields, but rag sends exactly that filter on every researcher-scoped semantic query.**
- Where:
  - `/Users/taufeeqpatel/Desktop/ScholarChain/modules/storage/src/main/scala/scholarchain/storage/aws/s3vectors/S3VectorsReader.scala:143-144` refuses `In(m, _)` when `m.tpe == MetaType.StrList`.
  - The production caller is `/Users/taufeeqpatel/Desktop/ScholarChain/modules/rag/src/main/scala/scholarchain/rag/agents/SemanticContextAgent.scala:118` (`VectorFilter.In(ResearcherIds, ids)` on researcher-profile-v1) and `:126` (the same filter on paper-abstract-v1 when ids are named). `ResearcherIds` is `MetaType.StrList` (`Vectors.scala:28`).
  - The filter reaches the adapter unchanged: `SemanticContext.scala:231` calls `vectorsFor(m).query(..., filter)`, and the verified facade passes it on (`VectorStore.scala:167`).
- Failure input: any question that names a researcher, for example a SemanticRequest with `researcherIds = Vector("researcher:orcid:...")`. The adapter returns `Refused("s3vectors filter: researcherIds: \"$in\" on a list field ...")`. `SemanticContextAgent.retrieve` folds that into `AgentError.Store`, so the whole semantic retrieval fails (lines 154-172).
- Impact: in production, the core Graph-RAG use case (semantic context about named researchers) always fails. It fails closed, but nobody flagged the effect: the SE-INT-04 report lists the D5 refusals without saying that rag emits this shape.
- Suggested fix: an equivalent translation needs only CONFIRMED operators. Q15 confirms "`$eq` against an array value is true if any element matches", and `$or` is supported. So `In(listField, vs)` becomes `{"$or": [{f: {"$eq": v1}}, ...]}`, which is exactly `VectorFilters.matches`: `xs.exists(vs.contains)`. Bound the number of values (the documented 100-constraint cap, plus `$and` nesting) and refuse above it. Add a test with the rag filter shape. If the human prefers to keep the refusal, rag-engineer would have to change the filter, but storage owns `VectorFilter` semantics, so storage-engineer is the natural owner.

**CR-INT-07-2 (major, (L) for the missing guard; the SDK resolution details beyond the observed `AWS_ENDPOINT_URL` are (P)). Owner: storage-engineer. Production clients built without an endpoint override pick up endpoints from the environment, which bypasses storage's own endpoint rule.**
- Where:
  - `/Users/taufeeqpatel/Desktop/ScholarChain/modules/storage/src/main/scala/scholarchain/storage/aws/StorageClients.scala:69` (S3) and `:88` (S3 Vectors) set an endpoint only when `endpointOverride` is defined.
  - `/Users/taufeeqpatel/Desktop/ScholarChain/modules/storage/src/main/scala/scholarchain/storage/aws/dynamodb/DynamoDbSettings.scala:124` does the same for the DynamoDB factory path.
  - Neptune is not affected: its endpoint is always explicit.
- Evidence: the agent observed the client build fail when the test JVM blanked `AWS_ENDPOINT_URL`, and `/Users/taufeeqpatel/Desktop/ScholarChain/build.sbt:281-298` confirms the build blanks it. The only "fix" is in the test (`StorageClientsSuite.scala:203-208` passes an explicit loopback endpoint); production code has no guard.
- Failure input: a task environment containing `AWS_ENDPOINT_URL=http://10.0.0.5:4566`, or a per-service `AWS_ENDPOINT_URL_S3`. From memory and not in the fetched docs: the `aws.endpointUrl*` system properties and a profile `endpoint_url` probably work the same way.
- Impact:
  - Signed S3, S3 Vectors and DynamoDB requests go to that host, over plain http to a non-loopback host. `DynamoDbClientSettings.endpoint` (https only, or http on loopback) would refuse that address if it were configured, so the variable silently bypasses storage's own rule and the "configuration is external, validated" invariant.
  - The S3 checksum and vector `dataSha256` checks catch corruption but not a forging endpoint (see the residual note in `Vectors.scala:118-121`), so evidence integrity is lost without any signal.
  - "Infra never sets these variables" is a convention, not a control.
- Suggested fix: in `SdkBuilders`, when `endpointOverride` is None, refuse with a value-free `Refused` that names only the variable names. Refuse when any environment variable name starts with `AWS_ENDPOINT_URL` or any system property starts with `aws.endpointUrl`. Inject the environment map so this can be tested with stubs. Document the remaining residual: the profile-file `endpoint_url`.
- Severity rationale: practical exposure is zero until G5 wires the bundle. But the fix lies entirely within this order's scope and is cheap, and these adapters are what G5 will wire.

### DEFERRED findings (minor or nit; not blocking)

**CR-INT-07-3 (minor, (L)). Owners: storage-engineer and service-engineer. The legacy-retry DynamoDB build is still the one the services docs point to.**
- The two-argument `DynamoDbClients.build` (`DynamoDbSettings.scala:85-100`) still uses the SDK default retry: legacy at 2.57.8, about 9 DynamoDB attempts per Q4.
- Answer to the work-order question: services does NOT call it in production composition. `ProductionServiceProvider.adapter` only calls the injected `deps.dynamoDb` factory (`ProductionComposition.scala:266-267`). The only code caller is the test `DynamoDbSurfaceSuite.scala:95`.
- However, the services scaladoc (`ProductionComposition.scala:188-190`) tells the G5 wiring to use `DynamoDbCalls.of(DynamoDbClients.build(settings, credentials))`, which is the legacy path.
- Is it safe today? Bounded, yes: every attempt still sits inside `apiCallTimeout`. Deterministic, no: the attempt count would change at the 2.58.0 upgrade.
- Fix: storage restricts or deprecates the two-argument overload (for example `private[storage]`), and service-engineer points the scaladoc at `StorageClients.dynamoDbFactory`.

**CR-INT-07-4 (minor, (L)). Owner: storage-engineer. A missing bucket is reported as a missing object.**
- `S3ObjectReader.scala:142-145` and `:203` treat every 404 on GetObject as "missing", including error code `NoSuchBucket`.
- Failure input: an allowlisted bucket that does not exist, or was deleted. Every `get` returns `Right(None)` instead of an error, so context text looks absent rather than the store looking broken.
- HEAD cannot tell the two apart (Q5: no body), but GET can.
- Fix: on GET, a 404 with code `NoSuchBucket` is `Permanent`. Add a test.

**CR-INT-07-5 (nit, (L)). Owner: storage-engineer. Interrupts are not handled as `AwsErrors` intends.**
- `NonFatal` does not match `InterruptedException`. So the `InterruptedException` branch of `AwsErrors.classify` (`AwsCommon.scala:240-242`) is unreachable from the S3, Neptune and S3 Vectors try/catch blocks.
- In `S3ObjectReader.read` (`:281-285`), such a throwable, which in practice only arrives by a sneaky throw because the SDK wraps interrupts as `AbortedException`, would escape without `abort()`.
- Fix: catch `InterruptedException` explicitly, as `DynamoDbKeyValueStore.attempt` does (`:97`).

**CR-INT-07-6 (nit, (L)). Owner: storage-engineer. Two test gaps.**
- The lifecycle test checks the set of closes and that the provider closes last (`StorageClientsSuite.scala:89-94`), but not the reverse order among the clients.
- Nothing tests the environment-endpoint behaviour (this goes with CR-INT-07-2).

### Verification summary by work-order item

1. **Port contracts.** Pass. The adapters implement `ObjectReader`, `GraphReader`, `VectorReader` and `IndexInspector` exactly. The S3, Neptune and S3 Vectors seams expose only HeadObject, GetObject and ListObjectsV2; ExecuteOpenCypherQuery; and QueryVectors, GetVectors and GetIndex. `NeptuneCalls` is never handed out; only `GraphReader` is. Everything lives in `scholarchain.storage.aws.*`, not `storage.fake`. The DynamoDB seam's Put and Delete are the existing ones that durable state needs.

2. **Docs conformance.**
   - Credentials: `builder().build()` is owned and closed exactly once, last. `create()` is never used.
   - Lifecycle: close is idempotent and runs in reverse order, continues past a throwing close, and refuses after close. Builders and seams are guarded before the SDK is reached. Late adds are closed and refused.
   - Retry and timeouts: an explicit standard retry with `maxAttempts`, plus both timeouts, on every storage-built client. The test checks this at line 202.
   - S3: 404 means missing and 403 is Permanent. Only a full-object SHA-256 is accepted; COMPOSITE, suffixed and non-44-character values are Corrupt. Content-length is checked before reading. Reads go to EOF within the bound; the stream is aborted on over-length and on exceptions. One HeadObject per listed key. Bucket allowlist and the 1000 cap are in place.
   - Neptune: the `openCypherQuery` field is used; parameters go through ujson with no concatenation (IntValue is an Int, so the double conversion is exact). The column set must match exactly. maxRows is Permanent rather than truncated. The retry table matches the docs, with TimeLimitExceeded as Transient per the orchestrator's decision. No hints are added. The D3 structural endpoint check is in place.
   - S3 Vectors: topK is capped at 100 and a nextToken is Corrupt. maxGetKeys is 1..100 and at least maxTopK. The ordering check is marked P. D5 holds except CR-INT-07-1. Missing GetVectors keys are handled both ways (omitted, or 404 Permanent). describe maps 404 to None. Metadata decodes to `MetadataField` types and float32 decodes correctly.

3. **Error mapping and leakage.** The classification is correct, and no exception message is ever included. Error details carry bucket and key URIs, sha256 digests and metadata key names, as the existing fake store does (`InMemoryObjectStore.scala:63`). No values or credentials appear.

4. **Bounds.**
   - `byteCount.toInt` is safe, because `maxObjectBytes` is at most Int.MaxValue-9; the running total is a Long.
   - The read buffer is 8 KB. `ByteArrayOutputStream` starts at min(limit, 1 MB) and grows to the limit, so peak memory is about 2-3 times maxObjectBytes, which configuration bounds (G1).
   - A never-ending body is aborted once it passes Content-Length.
   - Neptune and S3 Vectors responses are fully parsed by the SDK before the row and count checks (bounded by LIMIT and topK).

5. **Environment endpoint.** See CR-INT-07-2 (major). Production clients should refuse when those variables or properties are set and no override is given. Passing an explicit endpoint everywhere is the alternative, but it hard-binds endpoint construction.

6. **Tests.** All 52 are meaningful and non-vacuous. The stubs use real SDK model builders (`ResponseInputStream` plus `AbortableInputStream`, `S3Exception`, `NeptunedataException`, Documents). Each test exercises a branch that would fail without the code under test. Gaps: CR-INT-07-6.

7. **Regressions and ownership.** The `DynamoDbMapping` change only adds `ClientClosedException` → Refused ahead of the existing cases; nothing else changes. No hardcoded names, Regions, endpoints or credentials (the test constants are test-only).

### Closure verdicts

| Item | Verdict | Notes |
|---|---|---|
| A1 DynamoDB factory | CLOSED | Residual: CR-INT-07-3, minor |
| A2 bundle lifecycle | CLOSED | |
| A3 S3 ObjectReader | CLOSED | Residual: CR-INT-07-4 and -5, minor and nit |
| A4 Neptune GraphReader | CLOSED | The P items are as reported |
| A5 S3 Vectors reader and inspector | NOT CLOSED | CR-INT-07-1 |
| A6 error table | CLOSED | |
| A7 tests | CLOSED | Gaps: CR-INT-07-6, plus the CR-INT-07-2 test |

G1-G5 are reported accurately as open owner items. The UNVERIFIED (P) list matches the code comments.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: CR-INT-07, integration round 01, read-only review of the SE-INT-04 production storage adapters, 2026-11-22
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (Read, Grep and Glob only)
TEST RESULTS: not observed by me. Agent-reported: sbt check exit 0; 225 suites; 2794 passed; 1 skipped; storage 781.
VALIDATION RESULTS: none run by me
ASSUMPTIONS:
- The SE-INT-04 edits are limited to the listed storage files. I cannot run git, and the session's git-status snapshot was truncated and mixes in earlier orders.
- I took the agent's observed `AWS_ENDPOINT_URL` build failure as evidence of the SDK's environment-endpoint behaviour.
- The S3 Vectors `$or` and array-`$eq` semantics come from se-int-04-docs.md Q15 (CONFIRMED).
RISKS: the P items reported by the agent remain. Also, a production researcher-scoped semantic query fails until CR-INT-07-1 is fixed. The CR-INT-06 residuals (-1 to -6) and A1-W are still not accepted by the human.
MANUAL ACTION REQUIRED: the orchestrator routes CR-INT-07-1 and -2 (blocking), and -3 to -6 (deferred), to storage-engineer, and sends the CR-INT-07-3 scaladoc part to service-engineer. If the human prefers a rag-side change for CR-INT-07-1, it goes to rag-engineer instead.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: storage-engineer repairs CR-INT-07-1 and -2, with tests, and runs sbt check; then a focused re-review by code-reviewer.

UNVERIFIED CONCERNS:
- The exception type of the SDK's own checksum failure on the EOF read is NOT STATED. If it is a plain `SdkClientException`, it maps to Permanent rather than Corrupt (`S3ObjectReader.scala:281-284`, `AwsCommon.scala:249`). It still fails closed.
- I could not confirm from the docs whether `apiCallTimeout` covers reading a sync GetObject body after the response is returned. If it does not, a slowly trickling body is bounded in bytes but only by per-read socket timeouts in time. This goes to G4 and the A1-W residual.
- Whether neptunedata exposes Neptune's `code` field as `awsErrorDetails().errorCode()` is unverified. If it does not, the status rule applies; for example, InternalFailure (500) would become Transient instead of Permanent.
- A listed zero-byte "directory marker" object (no checksum) would make `list` Corrupt.
```

## CR-INT-06 (code-reviewer, read-only): bounded integration review of SE-INT-03, DE-INT-03 and SV-INT-02

**Verdict: APPROVE.** There are no critical or major findings. I found two minor findings and four nits, none blocking. DE-INT-03's corrected X-4 holds up against the storage code, so it can be relied on as stated. The cause of the 2989 s check is not determined.

**Verification results**

1. **X-4 (DE-INT-03): correct.**
   - The worst case of one `QueryOwnershipStore.record` attempt is 3 SDK calls (ServiceStateStores.scala:327-350):
     - the PutItem of `putIfAbsent`;
     - the adapter's GetItem fallback when ALL_OLD is absent;
     - the `stored` GetItem on Conflict.
   - A retry always builds a new `now`, so the item differs and takes the Conflict path.
   - The A1-W counts in core and in `CompositeAttemptBudgets` match the storage paths:
     - `begin` 4·(R+1) (:686-712);
     - transitions 2·(R+1) (:510-544);
     - `complete` 3 (:763-770);
     - `create` 3 (:441-453);
     - `abandon` 2;
     - services `remove` 3 (its own GetItem plus a delete with fallback).
   - The relation to the readRequestTimeout cap is stated accurately for `ReadPolicy.read`. The record still uses it (QueryService.scala:680).
   - The tests pin the boundary: 2000/2000 refused, 6000/5999, 666/667, and the saturation case (ProductionConfigSuite.scala:490-525).
   - Nit: the core A1-W text now describes the wrong policy for the other operations (CR-INT-06-6).
2. **SE-INT-03: verified.**
   - Liveness is skew-aware: `Rows.live` is used for owner (:314), job (:404) and idempotency (:619) rows.
   - `expiryCeil` (:284-286) equals ceil((now + ttl·1000)/1000). It is applied at :335, :439, :529, :649 and :760.
   - The job-TTL check (:197-199) is implied by ST-3's `max(..., longest)`, so storage never refuses a value core accepts.
   - The in-flight check (:185-193) is the same strict inequality as ST-2. Core passes `max(sync, async)`, drain, skew and its requirement (ProductionConfig.scala:1478-1481). The requirement already contains max+drain, so the storage floor is implied.
   - The residual wording is true. With a replica ahead by Δ ≤ skew, a replacement is possible only from writer time expiresAt ≥ write + TTL. TTL deletion is possible from expiresAt − skew.
   - Repair-04 guarantees 1-6 hold as written.
3. **SV-INT-02.**
   - **Config mapping:** all 34 `ServiceConfig` fields are copied 1:1 (ProductionComposition.scala:60-96). The 12 `ServiceStateSettings.of` arguments come from core unchanged (:113-130). Both are tested (ProductionCompositionSuite.scala:214-284).
   - **Deadline coverage:** every whole store operation runs as one `writeTracked(deadline(), readRequestTimeoutMillis)` op:
     - begin, complete, abandon;
     - job create, start, succeed, fail, remove;
     - both job reads.
     The in-process stores are the exception: they are called directly (QueryService.scala:467-478).
   - **Attempt identity:** one ID is minted per `idempotent` call (:541) and reused by the retries inside `serial` and by `releaseOrphan`. Independent requests get distinct IDs, which is tested.
   - **Settle before retry:** `serial` retries a timed-out attempt only after `settledWithin` (Resilience.scala:408). Otherwise it returns 504 with the unsettled handle, and no wait goes past the cap or the deadline. Across operations (start then fail, succeed then fail), overlap is safe because every write is version-conditioned.
   - **Idempotency semantics** match the in-memory contract: Fresh (own attempt), Replay, InProgress, Mismatch, `complete`/`abandon` by hold, and the 409 cases.
   - **Admission and isolation:** 429 admission is per replica, and slots are released on every path. Client isolation holds for jobs, results, explain and evidence. All of this is tested.
   - **readBounded:** it counts in Long and is overflow-safe. The `start` cap is `Int.MaxValue - 9` = 2147483638, which equals core's `MaxServerBodyBytes`.
   - **CR-INT-03 tests:** real and non-vacuous. Each has a control (intact row Right, unknown version Unsupported), and CR-INT-03-2 isolates the v3 member rule.
   - **Composition:** ProductionServiceProvider fails closed for every missing injection point, naming it, and never substitutes a store itself. The fake refusal works by class-name prefix only (CR-INT-06-4).
   - **A1-W:** honestly reported as partly closed and pinned by a test (ProductionCompositionSuite.scala:443-460). Severity is assessed in CR-INT-06-3.
4. **Regressions.**
   - Positional signatures are kept: the new `ServiceRuntime.build` and `ServicePorts` parameters are trailing with defaults, and `QueryService` is constructed only in ServiceRuntime.
   - In-memory behaviour is kept: in-process stores run directly, as before, and the OpsSuite ports keep their assertions.
   - services imports core, rag and storage only. Main code has no `storage.fake` import.
   - No secrets and no hardcoded names, Regions or endpoints.
   - tests/**: the git snapshot shows no modified tests/ file, and the count is still 270.

**BLOCKING FINDINGS:** none.

**DEFERRED FINDINGS**

Owner service-engineer:

- **CR-INT-06-1 (minor, P). An idempotency key can be stranded in a way the report does not list.**
  - Where: QueryService.scala:548-551, against Resilience.scala:407, :422-436.
  - Failure input: begin attempt 1 applies its PutItem and loses the response (Transient). Attempt 2 is then Rejected or NotStarted because the key-value pool is full (503 Overloaded), or the caller is interrupted (500).
  - `releaseOrphan` runs only for Timeout and DownstreamFailure, so the applied record of a request that never ran holds the key. Every retry under the key gets 409 for inFlightTtl plus skew (120 s or more at the fixture).
  - Related: `releaseOrphan` (:512-530) can itself create a new in-flight record when the original never applied. That causes a brief 409, and if the abandon then fails, the same strand.
  - Fix direction: release on every non-Right outcome after an attempt that started, or document these cases.
- **CR-INT-06-2 (minor, P). ServiceRuntime does not fail closed for query-jobs without durable state.**
  - Where: ServiceRuntime.scala:68-69 and :107-117; ServiceMain.scala:41-43.
  - Failure input: any ServiceProvider, for example the future ServiceLoader provider, returns `ServicePorts(..., state = None)` for QueryJobs. The process then starts with per-replica in-memory jobs and idempotency.
  - In a fleet, a job polled on another replica returns 404, and duplicates are not caught across replicas.
  - Only ProductionServiceProvider passes `Some` today. The ownership port already fails closed for split kinds (SE-P10-04 n5), and state should do the same.

Owners domain-engineer (rule) and service-engineer (startup surfacing):

- **CR-INT-06-3 (minor, P). The A1-W residual is not validated, and nothing at runtime reports it.**
  - Where: DurableStores.scala:322-376; ProductionComposition.scala:288-292 and :397-398 (`start` discards `attemptBudgets`); ProductionConfig.scala:539-546.
  - Failure input: the core fixture (apiCall 500, R = 10, readTimeout 2000) is accepted, yet begin's last attempt can run up to 22000 ms and the transitions up to 11000 ms after the caller returned. Each holds one key-value thread.
  - Impact for the first deployment: limited. It needs both conflict rounds and slow DynamoDB calls. Serial retries keep it to one attempt per operation. The SDK honouring interrupts would shorten it, but that is P3 and unverified.
  - Several such attempts can still fill the shared key-value pool and give 503 on ownership, evidence and explain.
  - Recommendation: add a core rule or lower R and apiCall before Phase 14, and log the exceeding operations at startup.

Nits:

- **CR-INT-06-4 (nit, P; service-engineer). The fake refusal checks only one class-name prefix.**
  - Where: ProductionComposition.scala:253-260 and :320-324.
  - A wrapper or anonymous subclass of a storage fake passes, as does any fake outside `scholarchain.storage.fake.`. The workflow factory's model is not checked either.
  - The doc wording is accurate, but this is not a strong guard. Ownership comes from the entry point and code review.
- **CR-INT-06-5 (nit, P; service-engineer). `DurableJobStore.live` keeps entries for jobs whose final write failed.**
  - Where: DurableStores.scala:181-228.
  - The entry stays when the succeed/fail write or the remove fails, so the queued/running metric counts are inflated and the map grows by one entry per such job.
  - Metrics only: admission is unaffected.
- **CR-INT-06-6 (nit, L; domain-engineer). Stale core documentation.**
  - ProductionConfig.scala:526-536: A1-W names `ReadPolicy.read` with immediate retries, but services now uses `writeTracked`, which waits for `settled` before a retry.
  - ProductionConfig.scala:123-135: still says `readBounded` wraps in Int.
  - Also the repair-04 nits: stale line citations and the +1000 slack terms.

**Closure verdicts**

| Finding | Verdict | Basis |
|---|---|---|
| CR-INT-02-1 | CLOSED | Storage requirement check plus services whole-operation bounds. The per-attempt residual is A1-W (CR-INT-06-3). |
| CR-INT-02-2 | CLOSED | The text is corrected and accurate. P8 stays unverified. |
| CR-INT-02-3 | CLOSED | Skew-aware liveness and rounded-up expiry for owner, job and completed rows. The job-TTL check is consistent with ST-3. |
| CR-INT-03-1 | CLOSED | Direct `AuditVersions.read` cases with controls. |
| CR-INT-03-2 | CLOSED | Isolated case, both direct and through explain. |
| CR-INT-04-6 (services) | CLOSED | Long counting; `start` cap equals core's. |
| CR-INT-05-1 | CLOSED | 3·apiCall ≤ Q.readTimeout, saturating and pinned. |
| A1-W | PARTLY CLOSED, accepted as deferred | Wiring complete. Composite per-attempt bound not validated (CR-INT-06-3). |

**Cause of the 2989 s check: not determined.** What I ruled out in the new tests:
- The only real sleeps are 600 ms (DurableStateSuite.scala:338) and 5 ms polls in `eventually`.
- Every `hangNext` latch is released in `finally`. The stub's interrupt-ignoring wait is capped at 60 s.
- Every gated workflow is released, and no hang entry is left unconsumed in the passing paths.
- Pools close through `shutdownNow`. No real SDK client or credentials chain is built, and no port is bound.
- The suite timeout is 120 s per test, so even 13 timeouts could not add up to 2490 s, and all tests passed.

Machine load or the backgrounded build mode is plausible but not shown. Separately, the wall-clock assertions (`elapsed < 2500`) could flake under load.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: CR-INT-06, integration round 01, bounded read-only integration review of SE-INT-03, DE-INT-03 and SV-INT-02, 2026-11-22
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (Read, Grep and Glob only)
TEST RESULTS: not run by me. Reported by service-engineer and not observed: sbt check exit 0, 2989 s, suites=221, passed=2742, skipped=1; services 146/146; tests 269 passed, 1 skipped.
VALIDATION RESULTS: Every finding was checked against the cited lines. The SDK-call counts were traced through ServiceStateStores.scala and the X-4 test. The ST-2 equivalence was checked from storage :185-193 and core :1468-1481. The residual wording was worked by hand for a replica ahead by at most the skew and for TTL deletion.
ASSUMPTIONS: (L) means local or test quality, (P) means production-affecting. Phase 11 tests/** being unchanged rests on the git snapshot (no modified tests/ file) and the unchanged count of 270; the untracked list was truncated. AGENTS.md and skills/ were not read.
RISKS: A1-W per-attempt overrun (CR-INT-06-3); undocumented stranded-key cases (CR-INT-06-1); a future provider silently running in-memory state for query-jobs (CR-INT-06-2); P1, P3 and P8 unverified against AWS; the unexplained 6x check duration; timing-sensitive test assertions.
MANUAL ACTION REQUIRED: Human: accept or route CR-INT-06-1 to -6. Decide the A1-W rule, or lower R and apiCall values, before Phase 14. Optionally run sbt check in the foreground to see whether the 2989 s duration reproduces.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Stop. The orchestrator reports APPROVE with the deferred findings to the human, routing them to service-engineer (CR-INT-06-1, -2, -3 surfacing, -4, -5) and domain-engineer (CR-INT-06-3 rule, -6) on the human's decision.
VERDICT: APPROVE
FINDINGS:
BLOCKING: none
DEFERRED:
- service-engineer: CR-INT-06-1 minor (P), QueryService.scala:548-551 and :512-530; CR-INT-06-2 minor (P), ServiceRuntime.scala:68-69 and :107-117; CR-INT-06-4 nit (P), ProductionComposition.scala:253-260 and :320-324; CR-INT-06-5 nit (P), DurableStores.scala:181-228
- domain-engineer and service-engineer: CR-INT-06-3 minor (P), DurableStores.scala:322-376, ProductionComposition.scala:288-292 and :397-398, ProductionConfig.scala:539-546
- domain-engineer: CR-INT-06-6 nit (L), ProductionConfig.scala:123-135 and :526-536
UNVERIFIED CONCERNS:
- Whether SDK v2 aborts an in-progress call on interrupt (P3). This decides how long CR-INT-06-3 overruns actually last.
- The cause of the 2989 s check (not determined from the code).
- Whether any untracked file under tests/ exists in the truncated part of the git snapshot.
```

## CR-INT-05 (code-reviewer, read-only): re-review of the DE-INT-02 repair (CR-INT-04-1, -2, -3, -4, -6 core)

**Verdict: APPROVE.** All five repaired findings are closed. I found one new minor finding and one nit, and neither blocks. The one thing to settle before SV-INT-02 relies on X-4 is CR-INT-05-1: X-4[query-jobs] still bounds only one SDK call, while one ownership-record attempt can make two or three.

### Closure verdicts

**CR-INT-04-1: CLOSED.**
- `jobTtlRequirementMillis` (ProductionConfig.scala:584-593) is `max(gap + drain + skew + 1000, max(sync, async))`. ST-3 (:951-987) passes `max(sync, async)` as the storage term.
- `stateOf` (:1416, :1429) gives storage the same `longestWorkflowTimeoutMillis = max(sync, async)`. So ST-3 implies the storage check at ServiceStateStores.scala:138-140. Both are strict, and the product cannot overflow (jobTtl ≤ MaxTtlSeconds).
- Opposite direction: the only storage-accepted values core refuses come from the deliberate job-gap margin.
- The other constructor checks are also implied:
  - :128 by ST-2, whose requirement is at least longest + drain;
  - :136 by ST-1;
  - the TTL, millis, payload, conflict-retry and ownedBy bounds by the key bounds at :351-407.
- No mismatch remains in either direction.

**CR-INT-04-2: CLOSED.**
- `inFlightRequirementMillis` (:525-540) now counts readRequest twice: once for begin, once for complete or abandon.
- The A1 text (:501-506) and the repair-02 obligations name complete and abandon.
- Against QueryService.scala:433-459 and :561-574, I found nothing else missing from the in-flight period:
  - The ownership record runs inside the workflow timer (`runTracked(timeoutMillis)`, :531, :567), so it falls within max(sync, async).
  - Async completes JobRef at submission.
  - The retryable path's `settled.thenRun(abandon)` is bounded only under A3, which was already recorded.

**CR-INT-04-3: CLOSED.**
- The ST rules read these query-jobs keys:
  - ST-2: rr, qw, sync, async, drain;
  - ST-3: the same plus jobQueueCapacity and jobWorkers;
  - ST-4: sync and async;
  - ST-5: idempotencyTtl.
- `stateOf` (:1395-1436) reads the same eight. Their union is exactly `QueryJobsStateInputs` (:1323-1335): nothing missing, nothing extra.
- When only these eight are present, SVC-5[query-jobs] and SVC-6[query-jobs] also apply, because their keys are a subset. That is harmless and consistent. X-4[query-jobs] and SRV-1[query-jobs] do not apply.
- The query-jobs requirement (:1342) is still the full service section plus storage (70 keys), unchanged.
- The test change (ProductionConfigSuite.scala:289-295) is justified. It now pins the exact set of missing keys instead of just `isLeft`.

**CR-INT-04-4: CLOSED (i, ii, iii).**
- **(i) ownedBy chunk count.** `ownedByBudgetMillis` is `ceil(scan / 100) · chunk` (:677-681). This matches `grouped(MaxKeysPerCall)` with chunks read in sequence (DynamoDbKeyValueStore.scala:226-230; KeyValueStore.scala:78 = 100).
- **(ii) page count.** `maxQueryPages · apiCall` matches the page loop (:193, :206-213), which makes one SDK call per page up to the limit.
  - The agent's claim holds as stated. The only key-value Queries in services are ReadServices.scala:110-112 and :525-527, both in evidence-explain. QueryService's key-value policy runs only `ownership.record` (:567), which is PutItem plus GetItem.
  - The conclusion that X-4[query-jobs] can keep a single-call bound does not hold, because record is a composite operation. See CR-INT-05-1.
- **(iii) backoff mirror.**
  - `batchGetBackoffMillis` (:629-638) equals `backoff` at :239-246 term for term: factor `Long.MaxValue` once retry - 1 ≥ 30, else `1L << (retry - 1)`; raw 0 when base = 0, saturating when factor > Long.Max / base; then `min(raw, max)`. The `base <= 0` versus `== 0` difference cannot arise, because the key bound is ≥ 0.
  - Sleeps happen before calls 2..attempts using `backoff(call)` with call = 1..attempts-1 (:296-305). That is what the chunk budget sums: retries 1..min(retries, 30) one by one, then (retries - 30) · `backoff(31)`.
- **Overflow:** safe throughout.
  - The loop runs at most 30 times, and the largest shift is 29.
  - `retries - doubling` is ≥ 0.
  - Every product and sum goes through `SatMath`.
  - `ceilDiv` on an Int-range scan limit is safe.

**CR-INT-04-6 (core): CLOSED.**
- **Bound.** The cap is 2147483638 (:117, :456-460).
  - At this value `max + 1 ≤ Int.MaxValue`, and `keep = min(n, max + 1 - total)` is in [1, max + 1 - total] while total ≤ max.
  - So `total + keep ≤ max + 1` and nothing wraps at HttpServerAdapter.scala:78-81. The arithmetic limit alone would be 2147483646.
  - The extra 8 bytes rest on the recalled `SOFT_MAX_ARRAY_LENGTH = Integer.MAX_VALUE - 8`, which matches my own understanding of JDK 17+ `ArraysSupport`. It does not matter for correctness: it only tightens a cap that is already overflow-safe, so a wrong recall cannot reintroduce the overflow.
- **SRV-1.** It is generated per kind (:785-796, :1028-1029).
  - When `server.maxBodyBytes ≥ maxBodyBytes`, a body over the application limit is either fully read (`body.length > maxBodyBytes`) or flagged `tooLarge` at more than server.max ≥ app.max.
  - Either way the Router.scala:271-272 message is true.
- **Services reader.** The `readBounded` fix is correctly left to service-engineer. No services file was changed.

### Tests (item 6)
Each fix has exact boundary pairs, includes my CR-INT-04 failure inputs, and would fail on the DE-INT-01 formulas:
- **-1:** jobTtl 100 refused, 101 accepted. My exact input (inFlight 107, jobTtl 28) gives {ST-2, ST-3}.
- **-2:** 91 refused, 92 accepted; with readRequest 6000, 93 refused, 94 accepted. The old requirement of 86000 would accept 91.
- **-3:** the required count is pinned at 78 (old: 113). The ST-rule key set equals the eight. Dropping each of the eight gives a MissingKey for exactly that key.
- **-4(i):** 100 accepted, 101 refused; at 4950, 300 accepted and 301 refused; 4949 with 250 refused.
- **-4(ii):** pages 4 accepted, 5 refused; 10 pages at 5000 accepted, at 4999 refused.
- **-4(iii):** 31 attempts accepted, 32 and 33 refused; exact need and need - 1. The old code accepts 32 and 33.
- **-6:** for each kind, 2147483647, 2147483646 and 2147483639 refused and 2147483638 accepted; SRV-1 at 1000 and 65535 refused, 65536 accepted; maxBodyBytes 65537 refused.

I checked the side constraints in the fixtures by hand (SVC-4/5, DDB-1, X-3 and X-5 in the `oneCall` case), so each assertion isolates the rule it names. The rule count of 35 is correct. Coverage of every rule includes SRV-1.

### Regressions (item 7)
- Key count: 18 + 10 + 3·43 = 157, asserted at ProductionConfigSuite.scala:153.
- The fixture's maxQueryPages changed from 10 to 4. This does not affect the frozen wire-format test (:789-796), which uses base plus the graph section only, or the compatibility test (:240-261).
- ConfigLoaderSuite is not in the session-start `git status`, so it is unmodified.
- The tracked modified set matches the 10 files from earlier orders. See ASSUMPTIONS.

### New findings

**Non-blocking (recommend fixing before SV-INT-02 relies on X-4):**

**CR-INT-05-1 (minor, L). X-4[query-jobs] does not bound one ownership-record attempt.**
- Owner: domain-engineer for the rule. The SDK-call counts of composite operations go with the deferred CR-INT-04-5 audit, by human decision.
- Where: ProductionConfig.scala:851-867. The comment "so one SDK call is its longest" is not true.
- Why: `QueryOwnership.record` (ServiceStateStores.scala:265-288) is `putIfAbsent`, then `stored` (a GetItem) on Conflict. `putIfAbsent` (DynamoDbKeyValueStore.scala:325-362) can itself fall back to a GetItem when ALL_OLD is absent. So one attempt makes up to 3 SDK calls.
- Failure input:
  1. `apiCallTimeoutMillis = 2000` and `Q.readTimeoutMillis = 2000`, which X-4 accepts at equality.
  2. Attempt 1 of record times out after its PutItem applied.
  3. Attempt 2 (readMaxAttempts ≥ 2): the PutItem fails its condition, because `createdAt = now` differs, so the records are not the same. Conflict leads to a GetItem. That is up to 4000 ms, or 6000 ms without ALL_OLD, against a 2000 ms attempt.
- Under A1, `begin`, `complete` and the job writes are composite too (get plus a conditional put, with up to maxConflictRetries loops).
- Impact: the same N4 class as CR-INT-04-4. A timed-out attempt can keep a key-value thread, and the rule gives a false bound.
- The agent disclosed this under RISKS, but it is not part of CR-INT-04-5 as written.

**CR-INT-05-2 (nit, L). Doc citation typos.**
- Owner: domain-engineer.
- ProductionConfig.scala:107 has a stray space: "services/ HttpServerAdapter.scala".
- :675 has a spaced citation: "DynamoDbKeyValueStore.scala: 226-230".
- No behavioural impact.

**Blocking:** none.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: CR-INT-05, integration round 01, read-only review of the DE-INT-02 repair (CR-INT-04-1, -2, -3, -4, -6 core), 2026-11-22
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (Read, Grep and Glob only)
TEST RESULTS: not run by the reviewer. Reported by domain-engineer, not observed by me: sbt check exit 0 in 497 s; core 644/644; tests 269 passed, 1 skipped; markTestRun suites=218, passed=2696, skipped=1.
VALIDATION RESULTS: Every closure and finding was checked against the cited lines in /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/main/scala/scholarchain/core/config/ProductionConfig.scala, /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/test/scala/scholarchain/core/config/ProductionConfigSuite.scala, ServiceStateStores.scala:98-153,255-309, DynamoDbKeyValueStore.scala:94-362, KeyValueStore.scala:78, HttpServerAdapter.scala:73-118, Router.scala:270-272, ServiceRuntime.scala:100-159, ReadServices.scala:100-130,515-529 and QueryService.scala:420-594. These reads go beyond the cited services lines: QueryService was read to check the in-flight period (item 2) and the X-4[query-jobs] claim (item 4(ii)). The boundary values in the tests and the fixture side constraints were worked by hand.
ASSUMPTIONS:
- I could not diff the untracked ProductionConfig files against their DE-INT-01 state. "Only two files changed" rests on the session-start git status (the same 10 tracked modified files as before) and the agent's git diff --stat.
- The JDK soft maximum array length is as I understand it. It does not affect overflow safety.
- AGENTS.md and skills/ were ignored.
RISKS:
- CR-INT-05-1: X-4[query-jobs] understates one attempt for composite store operations, now and more so under A1.
- A server.maxBodyBytes near the cap needs several GB of heap for the buffer and its copies, or the reader hits a fatal OutOfMemoryError. That is a deployment sizing concern.
- The mirrored storage and services constants and the backoff formula can still drift silently until the CR-INT-04-5 cross-module test exists (test-engineer).
- Every budget still depends on A1 and A3, which services code does not yet satisfy.
MANUAL ACTION REQUIRED:
- Human: decide whether to route CR-INT-05-1 before SV-INT-02 or fold it into the deferred CR-INT-04-5 audit.
- CR-INT-02-1, -2 and -3 remain OPEN. CR-INT-03-1, -2 and the services portion of CR-INT-04-6 are carried to SV-INT-02.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Stop. The orchestrator reports APPROVE with CR-INT-05-1 and -2 to the human, then proceeds to SV-INT-02 (service-engineer) on the human's decision.
VERDICT: APPROVE
FINDINGS:
- Closure: CR-INT-04-1 CLOSED; CR-INT-04-2 CLOSED; CR-INT-04-3 CLOSED; CR-INT-04-4 (i), (ii), (iii) CLOSED; CR-INT-04-6 (core) CLOSED.
- domain-engineer:
  - CR-INT-05-1 minor (L), non-blocking: ProductionConfig.scala:851-867. X-4[query-jobs] bounds one SDK call, but ownership.record makes up to 3 (ServiceStateStores.scala:276-284; DynamoDbKeyValueStore.scala:325-360). Failure input: apiCall = Q.readTimeout = 2000, retry after an applied-but-timed-out PutItem takes 4000-6000 ms. Impact: N4.
  - CR-INT-05-2 nit (L): ProductionConfig.scala:107 and :675, citation typos.
UNVERIFIED CONCERNS:
- Whether DynamoDB always returns ALL_OLD on ConditionalCheckFailed. This decides whether record's worst case is 2 or 3 calls (P).
- Whether the AWS SDK aborts an in-progress call on interrupt (P3, carried from CR-INT-04).
- BatchGetItem's 100-key limit is marked unverified in KeyValueStore.scala:75-76. Core mirrors it.
```

## CR-INT-04 (code-reviewer, read-only): review of DE-INT-01 (production configuration contracts)

**Verdict: APPROVE.** None of my findings is critical or major. Five verified minor findings and four nits remain. Four of the minor findings (CR-INT-04-1 to -4) should be fixed before SV-INT-02 starts relying on `ProductionSettings`: they are timing or contract terms that the rules leave out or get wrong. CR-INT-02-1, -2 and -3 stay OPEN. CR-INT-03-1 and -2 were not reviewed.

### Check results (summary)

**1. Bounds against the constructors.**
- Every mirrored constant matches its source:
  - `MaxTtlSeconds` and `MaxDurationMillis`: ServiceStateStores.scala:82 and :88.
  - `CeilingBytes` (262144): ServiceState.scala:72. `MaxTokenLength` (128): ServiceState.scala:15.
  - `maxConflictRetries` 1..100: ServiceStateStores.scala:148.
  - Region pattern: DynamoDbSettings.scala:34. Loopback hosts and the endpoint rule: :35-47. Table name pattern: :172. Distinct names: :178. Attempt timeout below call timeout: :65. Backoff max not below base: :139.
  - Edge types: ReadServices.scala:353-358. Depth: :361. Port: HttpServerAdapter.scala:122.
  - SVC-1 to SVC-6 match ServiceConfig.scala:163-204.
- Where core is narrower (the ten-year caps, the host check), that is safe.
- Two exceptions:
  - CR-INT-04-1: core accepts a job TTL that storage refuses.
  - CR-INT-04-6: core accepts a `server.maxBodyBytes` that the server mishandles.

**2. Timing.**
- Saturation is overflow-safe everywhere I checked: `SatMath`; the batch loop of at most about 64 iterations; `mul(rest, max)`; and the `mul(ttl, 1000)` comparisons.
- The job queue-wait term `ceil(cap/workers)·hold` is right for the last job in a FIFO queue. It matches ServiceRuntime.scala:132-135 and QueryService.scala:683-695.
- The one-second flooring terms in ST-3, ST-4 and ST-5 match the floored expiries at ServiceStateStores.scala:273, :373, :463 and :694.
- The skew terms cover A4.
- Problems found: CR-INT-04-2 (ST-2 is missing a term), CR-INT-04-4 (X-4 and X-5 are incomplete), CR-INT-04-8 (a term in the job-gap formula is always dominated).

**Assumptions A1 to A4 against today's code.**
- **A1 is not satisfied.**
  - `idempotency.begin` is called directly at QueryService.scala:434.
  - `jobs.create`, `start`, `succeed` and `fail` are called directly at :663 and :686-694.
  - Only the ownership record is capped (:567).
  - A1 also leaves out `complete` and `abandon` (see CR-INT-04-2).
- **A2 is already satisfied.** `expiresAt` comes from `now` at each write: ServiceStateStores.scala:571, :583 and :621. The takeover at :611 keeps the expiry of the first applied write. The report lists A2 as a storage obligation, which is inaccurate (CR-INT-04-9).
- **A3 is not enforceable.** It depends on the workflow honouring interruption (residual m3).
- **A4 is an environment assumption.** Nothing in the code enforces it. Job and ownership liveness still ignore skew (CR-INT-02-3, open).

**3. Compatibility.**
- `digestWithoutGrouping` now leaves out the production scope (ScholarChainConfig.scala:705-720).
- Default sections are omitted from the JSON, and the suite's compatibility test checks this.
- No existing key in ConfigKeys.all (ConfigLoader.scala:130-173) contains `-`, so the `-` to `_` mapping changes no existing env name. The `byEnv.size == all.size` test guards against collisions.
- The parse reasons of the production kinds are constants (ConfigLoader.scala:486-513 and :673-698). Bound and rule reasons name only bounds and formulas.

**4. Specific points.**
- (a) `querySessionTtlSeconds` is justified. It duplicates no core key. The existing source is rag `AuditConfig.ttlSeconds` (Audit.scala:247-251 and :303), which today is set only in tests (3600). That is a wiring obligation, not a conflict. Its place under `storage.serviceState` is a nit (CR-INT-04-9).
- (b) X-4 and X-5 are needed in principle (N4 defence) but do not bound what they claim to bound (CR-INT-04-4).
- (c) Evidence-explain needing some query-jobs values is justified: `ServiceStateSettings.of` takes `longestWorkflowTimeoutMillis` and `drainMillis` (ServiceStateStores.scala:107-108). Requiring all 43 query-jobs keys is not (CR-INT-04-3). Graph needs no storage keys: `storage.neptuneEndpoint` is already a required base key. The `server.maxBodyBytes` versus `maxBodyBytes` dependency is missing (CR-INT-04-6).

**5. Tests.** They are not vacuous, and they target the new code, so they would fail without it. Boundary coverage is incomplete (CR-INT-04-5).

### BLOCKING FINDINGS
None under the approval rule (no critical or major finding). CR-INT-04-1 to -4 are recommended prerequisites for SV-INT-02.

### FINDINGS (all owned by domain-engineer unless stated)

**CR-INT-04-1 (minor, L). Core accepts a job TTL that storage refuses.**
- Where: ProductionConfig.scala:810-842 (ST-3) and the `stateOf` result at :1234, against ServiceStateStores.scala:138-140 (`jobTtlSeconds*1000 > longestWorkflowTimeoutMillis`, which includes the sync timeout).
- Failure input, query-jobs: sync=100000, async=10000, readRequest=5000, queueWait=1000, jobWorkers=4, jobQueueCapacity=1, drain=0, skew=0; idempotencyTtl=200000; inFlight=107; jobTtl=28.
  - Every core rule passes. ST-3 requires more than 27000 ms, and 28000 is accepted.
  - `forProcess` returns Right. Storage `ServiceStateSettings.of(..., longestWorkflowTimeoutMillis = 100000, ...)` then refuses with "jobTtlSeconds: must exceed the longest workflow timeout".
- Impact: a configuration that core validated fails at process start, which contradicts the claim that the storage rules are mirrored. It fails closed.
- Fix: add the storage rule (jobTtl·1000 > max(sync, async)) to ST-3, or have storage-engineer change :139 to the async timeout and keep the two in step.

**CR-INT-04-2 (minor, L). ST-2 leaves out the duration of the `complete` write.**
- Where: TimingBudgets.inFlightRequirementMillis, ProductionConfig.scala:487-501, and ST-2 at :783-809; the A1 text at :467-470.
- Failure input:
  1. A sync idempotent request runs its workflow to the sync timeout.
  2. `idempotency.complete` then runs (QueryService.scala:451-455; ServiceStateStores.scala:683-704: a get, then a conditional put).
  3. The in-flight record expires while `complete` is running. With drain=0 and inFlight·1000 = requirement + 1, any `complete` slower than 1 ms crosses the expiry.
  4. A client retry under a new attempt ID gets a replacement at :595-599 and runs a second execution. The first request's `complete` returns StaleVersion "Replaced".
- A1 caps `begin`, job writes and ownership calls, but not `complete` or `abandon`, so even with A1 this term is unbounded.
- Impact: the duplicate-execution window that CR-INT-02-1 is about remains, hidden only by the drain margin.
- Fix:
  - domain-engineer: add `readRequestTimeoutMillis` (the `complete` budget) to the formula.
  - service-engineer: run `complete` and `abandon` under the capped key-value policy in A1.

**CR-INT-04-3 (minor, L). Evidence-explain requires all 43 query-jobs keys.**
- Where: ProductionConfig.scala:1162-1163 and :1211. The behaviour is locked in by ProductionConfigSuite.scala:284-287.
- Failure input: an evidence-explain config that has every key the ST rules and `ServiceStateSettings.of` need, but no `services.query-jobs.server.port` (or `graphEdgeTypes`, `metricsEnabled`, and so on). `forProcess(c, EvidenceExplain)` returns Left MissingKey.
- Impact: an unjustified deployment coupling; the evidence-explain task must carry the full query-jobs server and graph config. Requiring the copy also gives no guarantee that it equals the deployed query-jobs values when the processes use separate files.
- Fix: require only the keys the ST rules and `stateOf` read: sync, async, readRequest, queueWait, drain, jobQueueCapacity, jobWorkers and idempotencyTtl. Optionally document that the values must equal the query-jobs deployment.

**CR-INT-04-4 (minor, L). X-4 and X-5 do not bound one service attempt as they claim.**
- Where: ProductionConfig.scala:741-771 and TimingBudgets.batchGetChunkBudgetMillis at :579-598.
- (i) **ownedBy can be several chunks.** `ownedBy` reads up to `maxOwnedByQueryIds` IDs in one `getMany` (ServiceStateStores.scala:298-306), which the adapter splits into chunks of 100 (DynamoDbKeyValueStore.scala:227; KeyValueStore.scala:78). Evidence passes up to `evidenceScanLimit` distinct IDs (ReadServices.scala:128-129).
  - Failure input: evidenceScanLimit=250, maxOwnedByQueryIds=250, maxQueryLimit=1000, apiCall=500, attempts=3, base=50, max=200, E.readTimeout=2000.
  - X-5 passes (one chunk is 1650 ms). The call is really 3 chunks, up to 4950 ms.
  - Fix: multiply by `ceil(E.evidenceScanLimit / 100)`.
- (ii) **Queries can be several pages.** X-4 bounds one SDK call, but one query attempt makes up to `maxQueryPages` calls (DynamoDbKeyValueStore.scala:206-213).
  - Failure input: maxQueryPages=10 and apiCall=500 against readTimeout=2000 is accepted, while the attempt can run up to 5000 ms.
- (iii) **The backoff mirror is wrong for long retry chains.** Storage's backoff is `max` from retry 31 onwards (factor capped at :240-245). Core keeps doubling.
  - Failure input: attempts=33, base=1, max=1e11, apiCall=1000, E.readTimeout=5e9. Core computes a budget of about 4.29e9 ms and accepts. The real worst case is about 2.0e11 ms.
- Impact: a timed-out attempt can keep a key-value thread (N4). The rules give a false bound.

**CR-INT-04-5 (minor, L). Boundary tests are incomplete.**
- Where: ProductionConfigSuite.scala:293-320 and :411-438.
- Range keys: the maximum value is never asserted to be accepted, so `Range(1, max-1)` would still pass the suite.
- These rules are broken only away from their boundary: SVC-4 (6000 against 5000), DDB-2 (300 against 200), X-4 (400 against 500), X-5 (attempts=10). Their passing sides at the boundary (for example apiCall == readTimeout) are untested.
- An in-memory `graphDefaultWindow = TimeWindow(2020, 2010)` is accepted by `ProductionConfig.problems` (KeyBound.Unbounded at :414). The loader refuses it (ConfigLoader.scala:691-694), but the docstring at :1137-1138 says in-memory configurations get the same checks.
- No test pins the backoff mirror against storage's formula. That would be a cross-module test (test-engineer, under tests/**).
- Owner: domain-engineer; test-engineer for the cross-module mirror.

**CR-INT-04-6 (minor, L). `server.maxBodyBytes` admits a value the server breaks on, and no rule ties it to `maxBodyBytes`.**
- Where: ProductionConfig.scala:426 (range [1, Int.MaxValue]); HttpServerAdapter.scala:73-85; Router.scala:271-272.
- Failure input (a): `server.maxBodyBytes = 2147483647`. `max + 1 - total` overflows to a negative number, `out.write(buf, 0, negative)` throws, and NonFatal at :117 resets every request that has a body.
- Failure input (b): `server.maxBodyBytes = 1000` and `maxBodyBytes = 65536`. A 2000-byte body gets 413 with the message "the body exceeds 65536 bytes", which is false.
- Fix:
  - domain-engineer: add a rule `server.maxBodyBytes >= maxBodyBytes`, and cap the value below Int.MaxValue.
  - service-engineer: make the arithmetic in `readBounded` overflow-safe.

**CR-INT-04-7 (nit, L). Duplicate naming and region sources.**
- `storage.dynamodb.tables.*` and `storage.dynamodb.region` sit beside the required base keys `storage.dynamoTablePrefix` (unused in main code) and `aws.region`. No rule or doc relates them.

**CR-INT-04-8 (nit, L). A term in the job-gap formula is always dominated.**
- In `jobGapMillis` (ProductionConfig.scala:533-536), the second term `queueWait + async + readRequest` is always smaller than the first, because `ceil(...) >= 1` and the hold time includes `2·readRequest`. It is harmless.

**CR-INT-04-9 (nit, L). Wording inaccuracies.**
- The report lists A2 as an open storage obligation, but it is already met.
- `querySessionTtlSeconds` sits under `storage.serviceState`, although its consumer is rag `AuditConfig`. Document it as the rag stage-10 TTL source.

### DEFERRED IMPROVEMENTS
- CR-INT-04-6(b) could be deferred with SV-INT-02 if (a) is fixed first.
- CR-INT-04-7 to -9.
- The cross-module mirror test from CR-INT-04-5 (test-engineer).

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: CR-INT-04, integration round 01, read-only review of DE-INT-01 (production configuration contracts), 2026-11-22
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (Read, Grep and Glob only)
TEST RESULTS: not run by the reviewer. Reported by domain-engineer and not observed by me: sbt check exit 0 in 528 s; core 637/637; tests 269 passed, 1 skipped; markTestRun suites=218, passed=2689, skipped=1.
VALIDATION RESULTS: Every finding was checked against the files at the cited lines. CR-INT-04-1 and CR-INT-04-4(iii) were checked by working the formulas by hand for the stated inputs.
ASSUMPTIONS:
- The working tree is the DE-INT-01 state. Every cited line refers to the working tree.
- upickle omits default-valued fields. I relied on the suite's compatibility test for this and did not re-derive it.
- AGENTS.md and skills/ were ignored.
RISKS:
- Until CR-INT-04-2 is fixed, ST-2 understates the in-flight requirement.
- The constants mirrored from storage and services can drift silently, because no cross-module test pins them.
- A1 and A3 are not satisfied by today's services code, so every budget is conditional.
- A4 (and P8, the timing of TTL deletion) needs production evidence.
MANUAL ACTION REQUIRED:
- Human: decide whether to route CR-INT-04-1 to -4 before SV-INT-02. I recommend it.
- CR-INT-02-1, -2 and -3 remain OPEN.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Stop after this verdict. The orchestrator reports APPROVE to the human with CR-INT-04-1 to -9 and routes them on the human's decision: domain-engineer (1-5, 6 rule, 7-9), service-engineer (2 A1 scope, 6 readBounded), storage-engineer (1 alternative), test-engineer (5 mirror test).
VERDICT: APPROVE
FINDINGS:
- domain-engineer:
  - CR-INT-04-1 minor (L): ProductionConfig.scala:810-842,1234 vs ServiceStateStores.scala:138-140. Core accepts a jobTtl that storage refuses when sync > async.
  - CR-INT-04-2 minor (L): ProductionConfig.scala:487-501,783-809,467-470. The complete-write term is missing (service-engineer: A1 scope).
  - CR-INT-04-3 minor (L): ProductionConfig.scala:1162-1163,1211. Evidence-explain requires all 43 query-jobs keys.
  - CR-INT-04-4 minor (L): ProductionConfig.scala:579-598,741-771. Multi-chunk ownedBy, multi-page queries, backoff mirror from retry 31.
  - CR-INT-04-5 minor (L): ProductionConfigSuite.scala:293-320,411-438; ProductionConfig.scala:414,1137-1138. Boundary gaps (test-engineer: cross-module mirror).
  - CR-INT-04-6 minor (L): ProductionConfig.scala:426. Missing server.maxBodyBytes rule and cap (service-engineer: HttpServerAdapter.scala:78-79 overflow).
  - CR-INT-04-7 nit (L): duplicate table-prefix and region sources.
  - CR-INT-04-8 nit (L): ProductionConfig.scala:533-536, a dominated term.
  - CR-INT-04-9 nit (L): report A2 wording; querySessionTtlSeconds placement and doc.
- Open and not resolved here: CR-INT-02-1, -2, -3.
UNVERIFIED CONCERNS:
- Whether the AWS SDK aborts an in-progress call on thread interrupt. This decides how much CR-INT-04-4 matters in practice (P3).
- Whether QuerySession rows are filtered by expiresAt on read, or rely on DynamoDB TTL deletion (P8). This affects how ST-4 should be read.
- I did not check Typesafe ConfigUtil.joinPath quoting of '-' segments directly. I relied on the suite's full-file load test that is reported as passing.
```

## CR-INT-03 (code-reviewer, read-only): review of SV-INT-01 (old v2 audit-record compatibility)

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: CR-INT-03, integration round 01. Read-only review of SV-INT-01 (CR-P11-02-1). The scope is the `AuditVersions` object and the explain call site in /Users/taufeeqpatel/Desktop/ScholarChain/modules/services/src/main/scala/scholarchain/services/ReadServices.scala, and /Users/taufeeqpatel/Desktop/ScholarChain/modules/services/src/test/scala/scholarchain/services/AuditVersionsSuite.scala.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none. Only Read and Grep were used.
TEST RESULTS: Nothing was rerun. I relied on the orchestrator's evidence: `sbt check` exited 0 in 469 s; services 118/118; tests 269 passed, 1 skipped; markTestRun suites=217, passed=2673, skipped=1.
VALIDATION RESULTS:
1. Valid v2 and v3 rows return 200: verified. In `ReadServices.scala:455-476`, a v2 row needs label = body schemaVersion = v2 and no `verifierVersion` member. A v3 row needs the member, and null is allowed. The rag codec (`Audit.scala:168-200`, `@allowUnknownKeys(false)`, `verifierVersion: Option[String] = None`) decodes a v2 body to None, so no rag change was needed. Test 3 checks the response keys against `explain.response.json` and checks the main values. Tests 4 and 5 cover a frozen v3 row and a v3 row written by the current workflow.
2. Integrity uses the stored bytes: verified.
   - Line 458 compares `Canonical.sha256HexUtf8(row.auditJson)` with the stored `row.auditSha256`. Nothing is re-serialized; the body is only parsed.
   - The storage item-level check is unchanged. Line 521 still calls `ItemCodecs.QueryAuditCodec.decode`, which checks `recordSha256`, re-encodes the item and compares it, and runs `QueryAuditRecord.problems`, which covers the body hash, the body queryId and the token-form version (`ItemCodecs.scala:69-84`, `Records.scala:142-177`).
   - Accepting v2 bypasses none of these checks. The new rules (body schemaVersion equals the label, and the per-version member check) only add strictness.
3. Corrupt, mislabelled and unsupported rows: verified by reading the code.
   - Both errors are typed `ApiError(DownstreamFailure)` and map to 502 (`Errors.scala:25`).
   - The corruption checks (null row, null body, foreign queryId, hash) come before the `Supported` lookup (lines 456-462).
   - Both messages are fixed strings, so no stored string is echoed. Test 8 asserts this.
   - A null row or a foreign queryId returns corrupt (line 457; unit test 10).
   - `ujson.read` and the upickle read are wrapped in `Try`; the `strOpt` and `objOpt` calls are total. Only the two intended 502s can come out.
4. New writes stay v3: verified. `AuditStage.auditRow` (`Audit.scala:307-313`) still writes `AuditRecord.SchemaVersion` = v3. Test 2 pins `AuditRecord.SchemaVersion == AuditVersions.V3` and checks it is in `Supported`.
5. Regression tests:
   - The v2 and v3 bodies are hand-written string literals, and their hashes are literals asserted in test 1. No current code generates them.
   - The mislabel cases in test 7 are not vacuous. Removing the member check would let case (a) return 200, and removing the schemaVersion check would let case (c) return 200.
   - The three tests reported as failing against HEAD would plausibly fail: test 3 (HEAD accepted only v3), test 7 case (b) (under HEAD, a v2 body with a v3 label decodes and returns 200), and test 8 (HEAD gave the integrity message). I could not view the HEAD file, so see the unverified concerns.
   - Gap: the tamper tests and test 9 never reach `AuditVersions`. See CR-INT-03-1.
ASSUMPTIONS: I read "(L)" as a local or test-quality issue with no production behaviour impact, and "(P)" as production-affecting. If the orchestrator uses another meaning, re-tag accordingly. HEAD's explain logic is as the agent describes it (`row.auditSchemaVersion == AuditRecord.SchemaVersion`).
RISKS:
- The `AuditVersions` hash check (line 458) and its corruption-before-version ordering are protected only by reading the code. No test fails if either is removed, because the storage decode rejects those inputs first.
- Rows of a future v4 return the 502 unsupported error until `Supported` is updated. Test 2 forces that update.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Human acceptance of SV-INT-01. Optionally, route CR-INT-03-1 and -2 to service-engineer. CR-INT-02-1, -2 and -3 remain OPEN as prerequisites for durable service wiring; this review does not resolve or accept them.
VERDICT: APPROVE
FINDINGS:
Owner: service-engineer (both findings)
- CR-INT-03-1 (minor, L). Where: `AuditVersionsSuite.scala:164-182` (test 6), `:218-227` (test 9) and the `forged` helper at `:72-82`, against `ReadServices.scala:456-462`.
  - Failure input: delete the `Canonical.sha256HexUtf8(row.auditJson) != row.auditSha256` clause at `ReadServices.scala:458`, or move the `Supported.get` lookup ahead of the corruption checks. Every test in the suite still passes.
  - Why: every tampered item reaches `ItemCodecs.QueryAuditCodec.decode` at `ReadServices.scala:521` first. That decode re-encodes the item, runs `QueryAuditRecord.problems` (`Records.scala:172-175`, the body hash) and compares the item (`ItemCodecs.scala:81-83`), so it already rejects the input as corrupt. `forged` recomputes only `recordSha256`; it cannot get past the re-encode and `problems` checks, despite its "as an attacker could" comment. Test 9 ("tampered row of an unknown version is corrupt, not unsupported") therefore passes whatever order `AuditVersions` uses, and test 10 (unit) never feeds a hash mismatch to `AuditVersions.read`.
  - Impact: no production behaviour is wrong today, because storage enforces the same rule. But the service-level integrity check and the ordering rule this work order asked about are untested, and a regression in either would not be detected. The helper comment also overstates what the tests prove.
  - Fix direction (owner decides): add unit cases that call `AuditVersions.read` directly with (i) a v2 or v3 row whose `auditSha256` does not match `auditJson`, and (ii) an unknown-version row with a bad hash, expecting `Integrity`. Correct the `forged` comment.
- CR-INT-03-2 (nit, L). Where: `AuditVersionsSuite.scala:184-201` (test 7), against `ReadServices.scala:467`.
  - Failure input: change line 467 so that v3 no longer requires `verifierVersion` (for example `!o.contains(AddedInV3) || needsVerifier`). Test 7 still passes, because its only v3-labelled body without `verifierVersion` (case b) is also rejected by the schemaVersion check at line 466.
  - Impact: the rule "v3 must carry `verifierVersion`" is not tested on its own.
  - Fix direction: add a case with label v3, body schemaVersion v3 and no `verifierVersion` member, re-hashed consistently, expecting `Integrity`.
UNVERIFIED CONCERNS:
- I could not view HEAD's explain code (no git access), so the claim that the three tests fail under the HEAD logic is checked by reasoning only, assuming HEAD's check was `row.auditSchemaVersion == AuditRecord.SchemaVersion`.
- I could not compute the pinned SHA-256 literals; their correctness rests on test 1 passing in the observed `sbt check`.
- `scala.util.Try` does not catch fatal errors such as `StackOverflowError` from a deeply nested body. This is pre-existing: storage's `problems` parses the same body first, and its size is bounded by `AuditBounds.CeilingBytes`. It is outside this change and was not raised as a finding.
```

## CR-INT-02 (code-reviewer, read-only): re-review of SE-INT-02 (repair of CR-INT-01-1 to -7)

**Verdict: APPROVE.** All seven CR-INT-01 findings are closed in code, and I found no critical or major issue. I report three new minor findings and one nit, all owned by storage-engineer. None of them blocks approval, but CR-INT-02-1 and CR-INT-02-2 should be fixed before SV-INT-02 relies on the in-flight bound.

### Closure verdicts
| Finding | Verdict | Basis |
|---|---|---|
| CR-INT-01-1 (major) | **CLOSED (L); P1 stays open** | See the checks below. |
| CR-INT-01-2 | **CLOSED (L); P2 stays open** | See the checks below. |
| CR-INT-01-3 | **CLOSED** | See the checks below. |
| CR-INT-01-4 | **CLOSED in code; the residual text needs correcting** | Corrections in CR-INT-02-1 and CR-INT-02-2. The idempotency checks themselves are consistent (see below). |
| CR-INT-01-7 | **CLOSED** | See the checks below. |
| CR-INT-01-5 | **CLOSED (L); P3 stays open** | See the checks below. |
| CR-INT-01-6 | **CLOSED (L); P4 stays open** | See the checks below. |

### CR-INT-01-1: ambiguous-write recovery
- **Attempt identity.** `begin` validates a caller-supplied attempt ID (`ServiceStateIds.attemptId`, 16 to 128 characters).
  - At `ServiceStateStores.scala:608-612`, a live in-flight record with the same attempt ID and the same request digest returns `Fresh`, with the ticket rebuilt from the stored item and its hash.
  - Any other attempt ID gets `InProgress`, and a different digest gets `Mismatch`, checked first. Independent callers therefore stay distinct.
- **Interleavings I traced.** All of them end with at most one stored in-flight record and one ticket hash per attempt:
  - Expired-row replacement where the write applies and the response is lost: the retry gets `Fresh` from the stored record.
  - SDK resend of a replacement: `StaleVersion` triggers a re-read at :602, then `Fresh`.
  - SDK resend of a fresh put: `AlreadyPresent`, then `Fresh`.
  - Slow original racing its retry, on both the fresh and the replacement path: whichever write lands first is what both calls read back.
- **Old tickets.** An old ticket cannot complete a record that was replaced by another attempt or re-begun by the same attempt. `completionOf` at :659-665 requires the same `attemptId`, `createdAtMillis` and `version + 1`.
- **Idempotent `complete`.** It is correct (:683-708).
  - The current hash equals the ticket's: write with `putIfVersion`, re-reading on `StaleVersion`.
  - The stored row is this attempt's completion with the same outcome: `Right`.
  - The same completion with another outcome: `Conflict`.
  - Anything else: `StaleVersion`.
- **Apply-then-lose tests are real.**
  - `StubDynamoDb.scala:111-115` and `:140-144` run `applyPut` / `applyDelete` before `afterWrite` throws or resends.
  - `CountingKv.lost` (`StateWorld.scala:34-38`) calls the underlying store first.
  - They are not vacuous. For example, `ServiceStateStoresSuite.scala:264-285` advances the clock so that a newly written record would differ, and asserts `createdAtMillis == Start`. `DynamoDbConcurrencySuite.scala:97-121` asserts that the single holder is the thread that retried once.

### CR-INT-01-2: encoded size bound
- Every service-state write goes through `Rows.encode` (:199-205): ownership `record`, job `create`, job transitions, begin (fresh and replacement), and `complete`.
- `itemBytes` (`ServiceState.scala:92-102`) measures the actual encoded `body` string, so escaping is counted exactly. Names, numbers (+1), lists and maps are overestimated, as remembered.
- The boundary tests are exact. Each extra `x` adds exactly 1 byte, the clocks are deterministic, the test asserts `itemBytes == MaxItemBytes` exactly, and +1 is `Refused` with nothing written.

### CR-INT-01-3: runtime read facade
- `runtimeReadOnly` (`KeyValueStore.scala:225-230`) refuses every `serviceState` role on `get` and `query` before the store is reached.
- `RuntimeStores.scala:156` uses it.
- The facade has no batch path, and the runtime writers are fixed to runtime-writable codecs (`RuntimeStores.scala:21`).
- The test stores real rows and checks that the runtime tables still pass through.

### CR-INT-01-4: what holds in code
- `Rows.live` (:212-221) saturates correctly.
- The idempotency checks (replacement, replay, `InProgress`) all use the skew allowance.
- `complete` is fenced by version, not by liveness. That is correct: a stale record that nobody replaced is still this attempt's to complete.
- The in-flight `expiresAt` is rounded up (`expiryCeil`).
- Leaving skew out of the `inFlightTtl > timeout + drain` inequality is sound: a replica ahead by Δ ≤ skew judges expiry no earlier than `expiresAt` in writer time.

### CR-INT-01-7: overflow-safe validation
- Every TTL is bounded to 1..MaxTtlSeconds and every millisecond input to 0..MaxDurationMillis, so no product or sum can overflow (each term is at most about 3.2e11).

### CR-INT-01-5: error mapping
- An `IOException` or `UncheckedIOException` anywhere in the cause chain of an `SdkClientException`, or bare, is now `Transient` (`DynamoDbMapping.scala:120-121, 154-155`).
- `AbortedException` and `ApiCallTimeout` are matched before the generic `SdkClientException` case.
- `AbortedException` stays `Permanent` and restores the interrupt flag when the chain holds an `InterruptedException`.
- An SSL handshake failure becoming `Transient` is acceptable. Every write is conditional and the stores recover ambiguous outcomes, so the only cost is bounded retry latency before the breaker.

### CR-INT-01-6: deleteIfRecord re-read
- Correct (`DynamoDbKeyValueStore.scala:427-449`): no item is `Absent`, another record is `Conflict`, and the same record is `Transient`.
- The test (`DynamoDbAmbiguousWriteSuite.scala:123-137`) counts exactly two consistent GetItem calls.

### Regressions
None found.
- **Port contract.** The `KeyValueStore` / `Reader` / `Writer` signatures are unchanged.
- **Fake versus adapter.** The suites run on both backends, and the parity script gained the applied-then-lost steps (`ServiceStateParitySuite.scala:87-97`).
- **Phase 11 tests.** `tests/**` is untouched according to git status.
- **Callers.** No module outside storage imports `scholarchain.storage.state` or `scholarchain.storage.aws`. The only other hit is `project/RagSourceImports.scala`, which is unmodified.
- **Ownership.** Only `modules/storage/**` changed.
- **Configuration.** No hardcoded region, endpoint, account ID or secret.

### Findings (all owned by storage-engineer)

**CR-INT-02-1 (minor, L). The in-flight bound is measured from the first applied write, but the takeover hands the ticket out later and does not extend it.**
- **Where:**
  - `ServiceStateStores.scala:611` returns the stored record unchanged, keeping its original `expiresAt`.
  - The settings check at :127-134 requires only `inFlightTtl*1000 > longestWorkflowTimeout + drain`.
- **Failure input:**
  1. `begin` applies at t0 and its response is lost.
  2. The services key-value policy retries with backoff and attempt timeouts and gets `Fresh` at t0+δ.
  3. The workflow then runs for its full timeout plus drain.
  4. The record expires at t0+inFlightTtl, which can fall before t0+δ+timeout+drain.
- **Impact:** another attempt can replace the record and run a second execution while the first is still inside its workflow timeout. The duplicate-execution window opens δ early, where δ is up to the services begin-retry budget. Real margins are probably large, so this is minor.
- **Fix:** add the caller's begin-retry budget (and any work between begin and the workflow call) as a settings term in the inequality, or document it in the residual. service-engineer and domain-engineer would then supply the value.

**CR-INT-02-2 (minor, L for the text; P for the TTL timing). The documented residual and clock assumption are inaccurate and incomplete.**
- **Where:** `ServiceStateStores.scala:41-49` and :58-65, and the repair-01 residual: "A request still running after inFlightTtlSeconds plus the skew allowance can be run a second time".
- **Failure inputs:**
  - (a) Under the stated assumption, a replica ahead of the writer by the full allowance treats the record as expired at writer-time `expiresAt`. The earliest re-execution is therefore at `inFlightTtl` after the write, not at `inFlightTtl + skew`.
  - (b) DynamoDB TTL deletion uses the service's clock and bypasses the skew allowance entirely. Once the row is deleted, `putIfAbsent` succeeds for any attempt. If the writer's clock is behind AWS by Δ', the key is freed Δ' early in writer time, and the rounding up does not cover that.
- **Impact:** the human is asked to accept a residual whose stated window is later than the real one, and whose clock assumption leaves out the writer-to-DynamoDB clock bound.
- **Fix:** correct the text to say re-execution is possible from `inFlightTtl` after the first applied write. Extend the clock assumption to "replica clocks and the DynamoDB service clock within `maxClockSkewMillis`". Note the dependence on TTL deletion timing (P8).

**CR-INT-02-3 (minor, L). Job and ownership liveness use neither the skew allowance nor rounding up, and the job-TTL check has no margin.**
- **Where:**
  - Liveness checks: `ServiceStateStores.scala:253` (ownership) and :339 (jobs).
  - Floor-rounded expiry: `expiresAt = updated/1000 + jobTtl` at :463 and :373.
  - Settings check: `jobTtlSeconds*1000 > longestWorkflowTimeout` at :138-140, with no drain or skew term.
- **Failure input:**
  1. Settings `jobTtlSeconds=5` and `longestWorkflowTimeoutMillis=4999` are accepted.
  2. `start` runs at epoch ms ...10999, so `expiresAt` is ...15.
  3. `succeed` at ...15000 to ...15998 is still inside the workflow timeout, but returns `Permanent` "no such job (expired)", and the result is lost.
  4. A polling replica whose clock is ahead by Δ gets `None` from `get` Δ early. Ownership `owner` and `ownedBy` likewise fail closed (404) Δ early.
- **Impact:** a job can be lost or reported missing near the bound. Realistic TTLs (for example 1800 s against 60 s) hide this.
- **Fix:** apply `Rows.live` with the allowance and `expiryCeil` to jobs and ownership, and add drain and skew to the job-TTL inequality. Alternatively, document these as accepted. A related point: the completed idempotency record also uses floor rounding (:694), so its replay window can be up to 1 s shorter.

**CR-INT-02-4 (nit, L). A report claim does not match the code.**
- **Where:** repair-01 says the encoded bound is "applied to every service-state write, before any read or write". In `QueryJobStore.transition`, the read at :449 comes before `Rows.encode` at :465.
- **Impact:** none, since nothing is written, which matches the code's own docstring at :322-324. Correct the report wording in any future revision.

### Items only real DynamoDB can settle (P)
- **P1:** how often the SDK's internal resends of an applied conditional write end in a condition failure, and whether a failed PutItem condition returns ALL_OLD. The code now recovers both ways.
- **P2:** the exact 400 KB item-size accounting behind the 358400-byte cap.
- **P3:** which transport and interrupt exceptions SDK v2 surfaces after its own retries, and how they are wrapped.
- **P4:** whether a failed DeleteItem condition returns ALL_OLD.
- **P5 to P7:** unchanged from CR-INT-01.
- **P8:** the timing of TTL deletion, which now matters for CR-INT-02-2(b).
- **New:** whether `TransactionConflictException` can reach a non-transactional PutItem or DeleteItem.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: CR-INT-02, integration round 01, re-review of repair SE-INT-02, 2026-11-22
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (Read, Grep and Glob only)
TEST RESULTS: not run by the reviewer. The reported results (sbt check exit 0; suites=216, passed=2663, skipped=1; storage 712/712; tests 269 passed, 1 skipped) were not observed by me.
VALIDATION RESULTS: Read against the files: ServiceStateStores.scala, ServiceState.scala, KeyValueStore.scala, RuntimeStores.scala, TableSpecs.scala, DynamoDbKeyValueStore.scala, DynamoDbMapping.scala, InMemoryKeyValueStore.scala (write methods), StubDynamoDb.scala, StateWorld.scala, ServiceStateStoresSuite.scala (idempotency, CR-INT-01-1, -2 and -4 sections), DynamoDbAmbiguousWriteSuite.scala, DynamoDbConcurrencySuite.scala, the CR-INT-01-3 test in RuntimeStoresTypeSuite.scala, grep of DynamoDbErrorMappingSuite.scala and ServiceStateParitySuite.scala, and services Resilience.scala:75-174 for the retry and cancellation context. Verified: the stub and CountingKv apply before they throw or resend; the attempt-ID takeover; idempotent complete; that every write goes through Rows.encode; the runtime facade refusal; saturating liveness; the duration bounds; the IOException cause-chain mapping; the deleteIfRecord re-read; no callers outside storage; no hardcoded names, regions or secrets.
ASSUMPTIONS:
- The working tree matches the SE-INT-01 and SE-INT-02 file lists. git status shows only modules/storage/** plus the out-of-scope AGENTS.md, skills/ and docs/agent-runs/integration-01/.
- SV-INT-02 will mint one attempt ID per logical request and will wait on Calls.runTracked's `settled` before retrying a timed-out begin (Resilience.scala:88-91).
RISKS:
- The duplicate-execution window opens earlier than documented (CR-INT-02-1, CR-INT-02-2).
- A job near its TTL can be lost or read as missing (CR-INT-02-3).
- The stub's DynamoDB semantics are still from memory (P1-P8).
MANUAL ACTION REQUIRED:
- Decide whether to route CR-INT-02-1 to -3 now or accept them as residuals. If accepted, CR-INT-02-2's text correction should still precede the human acceptance of the duplicate-execution residual.
- Settle P1-P8 on real DynamoDB before production use.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Human order: stop after this verdict. The orchestrator reports APPROVE to the human, together with CR-INT-02-1 to -4 (storage-engineer; settings terms for service-engineer and domain-engineer).
VERDICT: APPROVE
FINDINGS:
- storage-engineer:
  - CR-INT-02-1 minor (L), ServiceStateStores.scala:611,127-134 (service-engineer and domain-engineer: begin-retry budget setting)
  - CR-INT-02-2 minor (L; P for TTL timing), ServiceStateStores.scala:41-49,58-65 and the repair-01 residual text
  - CR-INT-02-3 minor (L), ServiceStateStores.scala:253,339,373,463,138-140,694
  - CR-INT-02-4 nit (L), repair-01 claim versus ServiceStateStores.scala:449,465
- Closure: CR-INT-01-1 CLOSED (L; P1); -2 CLOSED (L; P2); -3 CLOSED; -4 CLOSED in code, residual text corrected by CR-INT-02-1 and -2; -5 CLOSED (L; P3); -6 CLOSED (L; P4); -7 CLOSED.
UNVERIFIED CONCERNS:
- If a services cancel(true) interrupt surfaces as an SdkClientException with an InterruptedIOException cause rather than AbortedException, it maps Transient and the interrupt flag is not restored. The caller has already given up and no store loop continues after Transient (P3).
- A timed-out original begin whose write lands after the retry's ticket was abandoned would strand an in-flight row for inFlightTtl. This is closed only if SV-INT-02 waits on `settled` before retrying begin (service-engineer to confirm).
- I did not re-read InMemoryKeyValueStore's thread-safety, ServiceStateRecordsSuite, or the full DynamoDbErrorMappingSuite line by line.
- The CR-INT-01 unverified concerns (job queue wait against job TTL, second-worker start, work between begin and the workflow) remain service-engineer items. CR-INT-02-1 overlaps the third.
```
