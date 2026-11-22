# Phase 10 repair 02 (SE-P10-03, service-engineer) for CR-P10-03, plus local service entry points

Records state only; approves nothing. Condensed from the agent's final report (STATUS: COMPLETE). Combined by the orchestrator with the human-ordered three-service entry points to use one `sbt check`.

## Part A: findings routed and fixes (tests in RepairP10bSuite unless noted)
- N1 (also n3): evidence occurrences carry bundleId (opaque hash, no route maps it to a query), no queryId. GET /v1/explain/{queryId} served only to the submitting client via new `QueryOwnership` port with bounded `InMemoryQueryOwnership` (queryOwnershipCapacity); unknown, forgotten or other client's -> same 404 before any store read. M1 test now asserts absence; LifecycleSuite and evidence.response.json updated. Design decision documented on EvidenceService: evidence shared across clients (non-personal facts of the published run), no query or client linkage exposed. Durable ownership deferred (storage-engineer adapter, infra-engineer table); no rag/domain audit field needed.
- N2: CallerRunsPolicy removed. Full HTTP pool -> one-thread admission pool answers 503 overloaded with trace ID immediately, never reads body; /healthz and /readyz still served. Dispatcher never runs an exchange. Real-HTTP test: 1 worker + 1 queue slot taken, third slow request 503 in < 3 s with trace ID, /healthz and /readyz 200.
- N3: single atomic CAS claim by worker or waiting caller; two deterministic latch tests, one per race order.
- N4: retryable outcome (e.g. 504) releases the idempotency key only after the workflow call ends; docstrings corrected (Calls, IdempotencyStore.abandon, StoreAccess). Test: interrupt-ignoring workflow -> 504; same-key retry 409 in-progress; one run until the first ends.
- N5: recorded residual in code docs (QueryWorkflow.of, StoreAccess): serialized lock lets a slow workflow block reads, open circuit-key-value and fail /readyz; M2 test does not cover it; fix is thread-safe adapters (storage-engineer).
- X1: config rule readRequestTimeoutMillis >= readTimeoutMillis; deadline-truncated attempts not counted as breaker failures (test, config test). X2: queue wait counts against the request deadline (Calls unit test). X3: closed body nested 5,000 deep -> invalid-json end to end. X4: occurrencesTruncated true only when more rows exist (fetch one extra; tested at max 1, 2, 3). X5: evidence shape `occurrences[] {bundleId, score, content{text, bytes, truncated, sha256}}` + occurrencesTruncated diverges from README.md:2500-2516 (documentation-engineer).
- U-c (code reading): rag semantic gap lines are fixed templates ("index <name> is not configured", "index <name> skipped: maxRetrievals <n> reached"); no stored text, but the maxRetrievals config value can appear in an abstention body; reported to rag-engineer. The accompanying test does not prove the property.
- U2: residual; JDK server API has no per-exchange read deadline.

## Part B: three service entry points (ServiceMain.scala)
| Service | Routes | Pools | Breaker |
|---|---|---|---|
| QueryJobsMain | POST /v1/query, GET /v1/jobs/{jobId}, GET /v1/jobs/{jobId}/result | workflow, jobs, job-workflow | workflow |
| EvidenceExplainMain | GET /v1/evidence/{evidenceId}, GET /v1/explain/{queryId} | key-value | key-value |
| GraphMain | GET /v1/graph/nodes/{nodeId}, GET /v1/graph/nodes/{nodeId}/neighbors | graph | graph |
All serve GET /healthz, /readyz, /metrics. Route subsets from the single Router.Routes table (each RouteSpec names its service; other services' routes 404). ServiceKind.All keeps the single-process composition for tests.
- Mains take no arguments (stray argument -> exit 2); load exactly one ServiceProvider via java.util.ServiceLoader supplying ServiceSettings (ServiceConfig, host, port, backlog, workers, queue, body bound, overflow capacity, grace seconds) and ServicePorts (stores, workflow, key source, ownership, store access, access log, clock, IDs). No provider in main code: a main currently exits 1 "no scholarchain.services.ServiceProvider on the classpath" (tested). Graceful shutdown: drain (readiness fails, /v1 503), stop server within grace, stop pools. QueryJobs records ownership, EvidenceExplain checks it via the shared port (in-JVM shared in-memory; production needs a durable shared store).
- EntryPointsSuite: subsets partition /v1 routes; each router serves only its routes; readiness per service's own pools and circuit; missing port refuses composition; main argument/provider handling; graceful drain; three services on separate loopback ports in one JVM: query (QueryJobs) -> every evidence link (EvidenceExplain) -> explain (owner 200, other client 404) -> graph neighbours (Graph), reaching an evidence-backed answer; cross-service routes 404.
- Config fields (validated, no defaults): readRequestTimeoutMillis, queueWaitMillis, workflowWorkers, workflowQueueCapacity, keyValueWorkers, keyValueQueueCapacity, graphWorkers, graphQueueCapacity, queryOwnershipCapacity; server host, port, backlog, workers, queueCapacity, maxBodyBytes, overflowCapacity; graceSeconds.

## Follow-ups for other owners (not done)
- domain-engineer: core ConfigLoader rejects unknown keys; per-service keys needed under services.query-jobs.*, services.evidence-explain.*, services.graph.* (all ServiceConfig fields: positive Int/Long, graphEdgeTypes token list, graphDefaultWindow window, metricsEnabled boolean; server.host text, server.port 0..65535, server.backlog, server.workers, server.queueCapacity, server.maxBodyBytes, server.overflowCapacity, graceSeconds). Until then mains take explicit ServiceSettings.
- build-engineer: one launcher per main (sbt-native-packager mainClass per script, or three images); register the production ServiceProvider (META-INF/services) once it exists.
- storage-engineer: thread-safe production read adapters (StoreAccess.concurrent; closes N5); durable QueryOwnership adapter (e.g. DynamoDB item keyed by queryId with clientId, TTL matching QuerySession); durable shared job and idempotency stores (needed before >1 QueryJobs replica).
- infra-engineer: three ECS services with task sizes and autoscaling; ALB path routing (/v1/query, /v1/jobs* -> QueryJobs; /v1/evidence*, /v1/explain* -> EvidenceExplain; /v1/graph* -> Graph; health checks /healthz, /readyz); Secrets Manager key-digest source; ownership, job and idempotency tables; per-service configuration; sun.net.httpserver.maxReqTime / maxRspTime (U2, names unverified).
- rag-engineer: maxRetrievals value in gap lines (U-c); real LLM and model adapter.
- documentation-engineer: evidence-shape divergence from README (X5).

## Files
Created: main/.../ServiceMain.scala; test/.../RepairP10bSuite.scala; test/.../EntryPointsSuite.scala. Modified: main HttpServerAdapter, QueryService, ReadServices, Resilience, Router, ServiceConfig, ServiceRuntime, Stores, Support; test LifecycleSuite, RepairP10Suite, ResilienceSuite (key-value outage test now uses a second evidence read), ServiceWorld; api-examples/evidence.response.json.

## Commands and rerun evidence
`git ls-files --others --exclude-standard -- docs/agent-runs/phase-10`; `git diff --stat`; `sbt services/compile`; `sbt services/test` 81/81; `sbt services/scalafmtCheck` clean; `sbt check` (bhfl2ttwd) exit 0 — see test-report.md CURRENT.

## Agent-stated assumptions and risks
Evidence shared across clients (design decision); bundleId safe to expose; admission pool 1 thread with configurable overflow. Risks: N5 residual; full admission queue under extreme overload still closes the connection; U2 open; in-memory ownership, jobs and idempotency per-process and lost on restart.

Next gate: code-reviewer CR-P10-04.
