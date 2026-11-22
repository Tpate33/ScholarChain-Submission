# Phase 10 agent reports (orchestrator assembly, 2026-11-21)

Records state only; approves nothing.

## BE-P10-01 (build-engineer): bounded test logging

Condensed from the agent's final report (all report-contract fields kept).

- STATUS: BLOCKED-ON (only for the R1-b human marker review; work complete, `sbt check` exit 0).
- OWNER: build-engineer
- WORK ORDER: BE-P10-01, bound the tests-project DEBUG logging (Phase 9 test-report risk 5).
- FILES CREATED: project/TestLogging.scala; project/test-logging/tests-log4j2.properties (root warn, stderr); project/test-logging/services-logback.xml (root WARN, stderr).
- FILES MODIFIED: build.sbt (keys testLoggingInputs, verifyTestLogging; def boundedTestLogging; tests: Log4j2 with forced provider; services: Logback; verifyTestLogging in checkSteps after verifyDependencyScopes, before markTestRun). Version ledger unchanged; no new dependency.
- DIAGNOSIS: the tests Test classpath has two SLF4J 2 providers: logback-classic 1.5.18 (via `services % Test`, services declares logback % Runtime) first, then log4j-slf4j2-impl 2.20.0 (Spark). slf4j-api 2.0.17 binds logback, which has no configuration and defaults to root DEBUG; Spark/Hadoop/Netty/Parquet/AWS SDK logged at DEBUG. sparkAnalytics (owner log4j2-test at warn), storage and hadoop (no binding, NOP), and rag/core/entityResolution (no provider) are not affected. services had the same latent default.
- FIX: Test/javaOptions system properties only. tests: -Dslf4j.provider=org.apache.logging.slf4j.SLF4JServiceProvider, -Dlog4j2.configurationFile=<repo>/project/test-logging/tests-log4j2.properties, -Dslf4j.internal.verbosity=WARN. services: -Dlogback.configurationFile=<repo>/project/test-logging/services-logback.xml, -Dslf4j.internal.verbosity=WARN. The build's file is dropped if the owner adds a test logging config.
- GUARD: verifyTestLogging (part of check): self-test (28 inputs), static provider/config rules, config-level rules, end-to-end probes on the real tests and services Test classpaths, counter-probe with a DEBUG config, baseline probe reported as INFO.
- COMMANDS RUN: `sbt Test/compile` exit 1 (ExceptionInInitializerError in TestLogging, fixed); `sbt Test/compile` success; `sbt fmt` success; `git status --porcelain`; one `sbt check` (backgrounded by the harness after 180 s, exit 0, 446 s, completed Nov 21, 2026, 11:30:24 PM). Two commands refused by the guard, nothing ran.
- TEST RESULTS (sbt check): core 621/0; entityResolution 464/0; hadoop 85/0; rag 132/0; root 0; services 0; sparkAnalytics 263/0; storage 437/0; tests Total 231, Failed 0, Passed 230, Skipped 1. markTestRun: `wrote .claude/.last-test-run (git_head=3d9da3e6d8ef22e1d6f4bcfee1417350af25d758, worktree=dirty, suites=183, passed=2232, skipped=1)`.
- VALIDATION RESULTS: `verifyTestLogging: OK, 16 checks passed`; verifyDependencyScopes 64 checks; ledger OK 28 entries. Log volume: before ~7.7M lines (Phase 9 figure, not re-measured); after, the whole `sbt check` output 4,845 lines; tests project prints WARN only (mostly Hadoop LocalJobRunner).
- ASSUMPTIONS: log4j2 for tests is acceptable; bounding services Test now is in scope; WARN is the intended level.
- RISKS: (1) services production runtime/container still runs logback with no config (DEBUG): needs modules/services/src/main/resources/logback.xml (service-engineer). (2) Owner test configs replace the build file. (3) A few hundred Hadoop WARN lines remain. (4) SLF4J 1.x, JUL and commons-logging routes not covered. (5) ~3 s added to check. (6) verifyTestLogging has no standalone allowlist row.
- MANUAL ACTION REQUIRED: human marker review (R1-b).
- BLOCKED-ON OWNER: human. BLOCKED-ON EVIDENCE: Stop hook refusal ("test marker changed after the last audited Bash call"); cause: backgrounded audited `sbt check` wrote the marker at 11:30:24 PM.
- NEXT GATE: human marker review, code-reviewer CR-P10-01, human acceptance.

### Orchestrator observations (BE-P10-01)
- Marker read 2026-11-22: written_at_utc=2026-11-22T05:30:24.645843Z, git_head=3d9da3e…, worktree=dirty, suites=183, passed=2232, failed=0, errored=0, skipped=1, production_sources=180: matches the markTestRun line above.
- The orchestrator's next `git status --porcelain` was refused by audit-bash-writes.sh as UNATTRIBUTED (.claude/.last-test-run, owner GENERATED); the baseline was reset. Human review of the marker and `git status` is pending.
- Human answered (2026-11-22): continue; marker reviewed at closeout.

## SE-P10-01 (service-engineer): minimal Phase 10 services, local, in-memory fakes

Condensed from the agent's final report (all report-contract fields kept).

- STATUS: FAILED (no passing `sbt check` observed by the agent).
- OWNER: service-engineer. WORK ORDER: SE-P10-01.
- BUILT (modules/services/** only; JDK com.sun.net.httpserver + uPickle/ujson; no new dependency, no build change): pure in-process router with thin routes; separate query/jobs, evidence, graph and explain services; `QueryWorkflow` port with an adapter over rag `CollaborationWorkflow` (no retrieval/resolution/synthesis/verification reimplemented); reads through `RuntimeStores` read-only facades and closed `GraphQuery` templates; bounded in-memory job and idempotency stores; per-call timeouts, bounded read retries, per-dependency circuit breakers; bearer API key; production logback.xml; services-local copy of rag's fixture world for tests.
- ROUTES: POST /v1/query (scope query); GET /v1/jobs/{jobId}, /v1/jobs/{jobId}/result (query, owner only); GET /v1/evidence/{evidenceId}, /v1/graph/nodes/{nodeId}, /v1/graph/nodes/{nodeId}/neighbors (edgeType, depth, window, limit, cursor), /v1/explain/{queryId} (read); GET /healthz, /readyz (no auth); GET /metrics (ops; 404 when metricsEnabled=false).
- STATUS CODES: 200 answer or abstention; 202 job reference; 400 invalid-json, invalid-request, invalid-parameter, body-too-large, unsupported-media-type; 401 unauthenticated (WWW-Authenticate: Bearer); 403 forbidden; 404 not-found (incl. another client's job); 409 idempotency-key-reused, idempotency-in-progress, job-not-finished; 422 unsupported-constraint, unsupported-query, unsupported-graph-request; 429 too-many-requests (Retry-After); 502 downstream-failure; 503 circuit-open, overloaded, not-ready (Retry-After); 504 timeout; 500 internal. Every error body `{"error":{code,message,requestId,traceId,...}}`, IDs also in X-Request-Id / X-Trace-Id.
- POLICY: bounded `calls` pool with per-call timeout and cancellation; syncTimeoutMillis / asyncTimeoutMillis; workflow never retried (create-once audit rows); reads at most readMaxAttempts × readTimeoutMillis, transient errors and timeouts only, no sleep; breakers per workflow, key-value, graph (breakerFailureThreshold, breakerOpenMillis, one trial call); open circuit fails /readyz.
- DEPENDENCIES: services -> core, rag, storage; router imports neither rag nor storage (test-checked).
- AUTH: `Authorization: Bearer <key>`, SHA-256 compared with every configured digest via MessageDigest.isEqual without early exit; `ApiKeyDigestSource` port (Secrets Manager deferred; fake in tests); scopes query, read, ops; no key, digest, question text or researcher name in responses or logs (test-checked).
- EXAMPLES: 15 frozen JSON files in modules/services/src/test/resources/api-examples/, matched by exact key sets with typed placeholders.
- FILES CREATED: main/scala/scholarchain/services/{Support,Errors,ServiceConfig,Auth,Resilience,Stores,QueryService,ReadServices,Ops,Router,HttpServerAdapter,ServiceRuntime}.scala; main/resources/logback.xml; test/scala/scholarchain/services/{ServiceWorld,TestHttp,QueryApiSuite,ResilienceSuite,ReadApiSuite,LifecycleSuite,OpsSuite}.scala; test/resources/api-examples/ (15 JSON). FILES MODIFIED: none else.
- COMMANDS RUN: `git log --oneline --max-count=3`; `sbt services/compile` success; `sbt services/test` (compile error, then 44/44); `sbt services/test services/scalafmtCheck` 46/46, format clean; `sbt check` run 1 (backgrounded) exit 1, 422 s; `git status --short`; `sbt check` run 2 (background, unfinished at hand-off). Two calls refused by the guard (git grep, sbt services/Test/scalafmtCheck); nothing ran.
- TEST RESULTS: run 1: other projects' summaries 621/621, 464/464, 85/85, 132/132 and 0/0 (agent could not attach names); services Total 46, Failed 1, Passed 45; failing test "idempotent async request replays the same job; an in-flight sync key is 409 in-progress" (QueryApiSuite.scala:271); markTestRun `sbt.TestsFailedException: Tests unsuccessful` (marker not refreshed). Cause: race (main-thread poller could win the idempotency slot); fixed with an `entered` latch. Run 2: unobserved.
- DEFERRED: Secrets Manager key source; real Neptune/DynamoDB/S3 Vectors adapters (thread-safe, replacing StoreAccess.serialized); real LLM; query constraints (rag accepts question only); services.* RuntimeConfig keys (domain-engineer); production main/entry point, deployed /metrics model, separate deployables for independent scaling; multi-hop graph (depth capped at 1); durable job and idempotency stores.
- ASSUMPTIONS: wrong method -> 404, oversize body -> 400; /healthz and /readyz unauthenticated, /metrics needs ops; abstention is 200; off-topic planner reason `no-allowlisted-operation`.
- RISKS: in-memory fakes serialized behind one lock (a hung workflow blocks reads until breakers open); cancelled workflow keeps running and persists its audit; timing-dependent concurrency tests.
- MANUAL ACTION REQUIRED: observe run 2 or rerun `sbt check`; confirm services 46/46 and markTestRun success.
- BLOCKED-ON OWNER: none. NEXT GATE: passing `sbt check`, code-reviewer review.
