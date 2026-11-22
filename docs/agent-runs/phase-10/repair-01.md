# Phase 10 repair 01 (SE-P10-02, service-engineer) for CR-P10-02

Records state only; approves nothing. Condensed from the agent's final report (STATUS: COMPLETE).

## Findings routed and fixes
- M1: EvidenceService compares rows on identity fields only (score and text cleared); mismatch still 502. Response drops top-level score/content and adds `occurrences` [{bundleId, queryId, score, content{text, bytes, truncated, sha256}}] plus `occurrencesTruncated`. evidence.response.json updated. Test: fixture semantic documents; two questions share chunks at different distances; each shared ID 200 with per-bundle scores; every links.evidence URL of both answers resolves. No rag/storage/core change.
- M2: five bounded pools (workflow, jobs, job-workflow, key-value, graph); queue wait bounded by queueWaitMillis (NotStarted withdrawn), run time measured from start; Rejected/NotStarted -> 503 overloaded, never a breaker failure, never retried. Config: callWorkers/callQueueCapacity replaced by per-class workers and queue capacity plus queueWaitMillis. Tests: saturated workflow pool -> 503 overloaded while evidence, graph, explain 200, key-value circuit closed, /readyz 200; NotStarted never runs.
- m1: abstention bodies drop `detail` (reason tag and gap lines only). Test with detailed StoreError: no key, role, store text or `store:` prefix.
- m2: "Who are the coauthors of John Smith?" abstains identity-unresolved with two candidate IDs; new frozen example query-abstained.response.json; no name or question text; explain link resolves to an abstained audit.
- m3: in-flight idempotency records never expire; completed TTL from completion; config requires idempotencyTtlMillis > both workflow timeouts. Store, config and service tests (409 in-progress, workflow once).
- m4: one readRequestTimeoutMillis deadline per read request; attempts bounded by min(per-attempt, remaining); explain shares one deadline. Real-clock test: 504 where it was 200.
- n1: logged method restricted to standard set, else OTHER (CR/LF test). n2: try/finally releases key and finalises job; rendering guarded -> 500 (tests). n3: kept as design decision (agent text describes 404 wrong-method / 400 body-too-large; explain/evidence remain unscoped per client — reviewer to confirm). n4: case-insensitive Bearer (test). n5: 500 attributed to matched route (test).
- U1: HTTP pool CallerRunsPolicy (untested). U2: residual; deployment may set -Dsun.net.httpserver.maxReqTime / maxRspTime (unverified names). U3: nesting cap 16 by linear scan before parse -> 400 invalid-json (test with 200,000 `[`, strings ignored). U4: storage QueryAuditCodec verifies auditSha256 on decode; ExplainService also recomputes, 502 on mismatch; no dedicated test (tampered row not storable via codec).
- Deferral recorded: graph routes serve researcher nodes only (422 otherwise).

## Separate deployables (not implemented; agent's list)
1. Mains in modules/services on ServiceRuntime/HttpServerAdapter: QueryJobsMain (/v1/query, /v1/jobs/*), EvidenceMain, GraphMain, ExplainMain; each serves /healthz, /readyz, /metrics (service-engineer).
2. Route subset per deployable from Router.Routes (service-engineer).
3. Per-service config section in core (services.query.* etc.) and loader (domain-engineer).
4. Shared job/idempotency store for >1 query+jobs replica, e.g. DynamoDB with TTL: port/adapter storage-engineer, table infra-engineer.
5. Launcher per main via sbt-native-packager, one image with selectable mains or separate images (build-engineer).
6. ECS service per deployable with task size, autoscaling, ALB path routing, Secrets Manager key-source wiring (infra-engineer).
7. Thread-safe AWS adapters so StoreAccess.concurrent replaces the serialized lock (storage-engineer).

## Files
Created: test/scala/.../RepairP10Suite.scala; test/resources/api-examples/query-abstained.response.json. Modified: main Auth, HttpServerAdapter, QueryService, ReadServices, Resilience, Router, ServiceConfig, ServiceRuntime, Stores; test LifecycleSuite, OpsSuite, ServiceWorld; api-examples/evidence.response.json.

## Commands and rerun evidence
`git ls-files -- modules/storage/src/main/scala/scholarchain/storage/load`; `sbt services/compile`; `sbt services/test` 61/61; `sbt services/test services/scalafmtCheck` 61/61 clean; `sbt check` (b8ciu4pgn) exit 0 — see test-report.md CURRENT.

## Agent-stated assumptions and risks
New config fields have no defaults (domain-engineer keys pending); nesting cap 16 fixed. Serialized in-memory lock still behind all pools (isolation fully effective only with concurrent adapters); m4 test uses real clock (loose elapsed bound); U1 untested; U2 residual.

Next gate: code-reviewer re-review (CR-P10-03).
