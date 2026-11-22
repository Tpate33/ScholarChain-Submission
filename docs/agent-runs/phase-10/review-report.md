# Phase 10 review reports (orchestrator assembly)

Records state only; approves nothing.

## CR-P10-01 (code-reviewer, read-only): BE-P10-01 bounded test logging — VERDICT: APPROVE

Scope: build.sbt, project/TestLogging.scala, project/test-logging/tests-log4j2.properties, project/test-logging/services-logback.xml. Reviewer ran no commands (Read/Grep/Glob only) and did not re-run `sbt check`.

Verified: tests bind log4j2 via -Dslf4j.provider (build.sbt:598; TestLogging.scala:105,196; slf4j 2.0.17); build configs root WARN to stderr; Spark does not load its INFO defaults; markTestRun counts structured test events (project/TestMarker.scala:25), so no result is hidden; verifyTestLogging inserted at build.sbt:936 before markTestRun; ownership limited to build-engineer paths; probe writes only gitignored target/test-logging-probe; forks bounded (60 s + 10 s), stdin /dev/null, awsIsolationEnv applied; no user.home/env paths, no gold/held-out, no network, no secrets; Test / fork := true (build.sbt:313).

| ID | Sev | Location | Issue | Owner |
|---|---|---|---|---|
| m1 | minor | project/TestLogging.scala:49-52, 662-712; build.sbt:936 | Runtime reconfiguration (Configurator.setRootLevel(DEBUG), setLogLevel("DEBUG"), spark.log.level=DEBUG in test code) is neither detected nor listed as not covered; guard runs before tests and never measures real test log volume. | build-engineer |
| n1 | nit | project/TestLogging.scala:315-343 | buildConfigProblems misses log4j2 `logger.x = DEBUG, console` shorthand and logback `<level value="DEBUG"/>` child form. | build-engineer |
| n2 | nit | build.sbt:5-36 | Environment header omits that verifyTestLogging probe JVMs get awsIsolationEnv and otherwise inherit sbt's environment. | build-engineer |
| n3 | nit | project/TestLogging.scala:199 | log4j2.configurationFile is comma-separated; a repo path with a comma would split it (fails closed). | build-engineer |

Unverified: whether IO.delete at TestLogging.scala:658 follows a planted symlink at target/test-logging-probe (low risk); the reported 4,845-line output, 446 s and counts.

Orchestrator routing (CR-P10-01): m1 and n1-n3 not fixed in this phase so far (a build-engineer repair would contend with the concurrent service-engineer builds). Human decides at closeout: accept as residuals or route to build-engineer in a later work order.

## CR-P10-02 (code-reviewer, read-only): SE-P10-01 services — VERDICT: REQUEST_CHANGES

Scope: modules/services/src/**. Reviewer ran no commands. Its note "run 2 unobserved" is superseded: the orchestrator observed run 2 exit 0, services 46/46 (test-report.md).

| ID | Sev | Location | Issue | Owner |
|---|---|---|---|---|
| M1 | major | ReadServices.scala:67-69, 71-107 | EvidenceService.get requires every byEvidence row to hold an identical EvidenceItem, else 502. Evidence ID omits score/text (StableIds.scala:480-505), item rows are per bundle (TableKeys.scala:159-169), semantic items carry question-dependent distance/rank (EvidenceBuilder.scala:548-555). Two answers retrieving the same chunk at different distances -> permanent 502; answer evidence links stop resolving. Untested (no vector docs in ServiceWorld; one query in LifecycleSuite). | service-engineer (alt: rag/domain identity change) |
| M2 | major | ServiceRuntime.scala:51-53, 60-77; Resilience.scala:55-60, 162, 169-176 | One `calls` pool runs sync workflows, async job workflows and all reads; timeout counts queue wait; ReadPolicy retries TimedOut and records breaker failure for tasks that never reached the store. 8 slow queries -> evidence reads time out in queue -> circuit-key-value opens -> /readyz 503 with healthy stores. Defeats README L1584. | service-engineer |
| m1 | minor | QueryService.scala:180 | Abstention `detail` passes rag AgentError text (store keys, roles, StoreError.detail) to clients in a 200 body; contradicts Errors.scala:5-7. | service-engineer |
| m2 | minor | QueryService.scala:171-195 | Abstained response path has no test and no frozen example (no-names decision unguarded). | service-engineer |
| m3 | minor | Stores.scala:150-157, 176-180; ServiceConfig.scala:95-147 | expire() removes in-flight idempotency entries past TTL; no rule TTL > syncTimeout -> duplicate execution under one key. | service-engineer |
| m4 | minor | ReadServices.scala:354, 367-372, 381 | Explain has no overall request deadline (up to ~45 s with test bounds). | service-engineer |
| n1 | nit | Router.scala:98; Ops.scala:93 | Raw HTTP method logged unvalidated (log forging). | service-engineer |
| n2 | nit | QueryService.scala:270-283, 410-416 | No try/finally: unexpected exception leaves job running forever / key in flight until TTL. | service-engineer |
| n3 | nit | Router.scala:176-179 | Explain and evidence not scoped to requesting client (128-bit IDs); record as design decision. | service-engineer |
| n4 | nit | Auth.scala:63 | Bearer scheme match case-sensitive (RFC 7235). | service-engineer |
| n5 | nit | Router.scala:76-77 | 500 from a matched route counted as "unmatched". | service-engineer |

Risks noted: Phase 9 R1 reaches API clients via answerText/claims (QueryService.scala:155), must be reassessed before real-LLM integration and deployment; StoreAccess.serialized lock held for the whole workflow.
Unverified: U1 AbortPolicy RejectedExecutionException in JDK HttpServer dispatcher (no 503?); U2 no request-time bound on slow body uploads; U3 deeply nested JSON StackOverflowError within maxBodyBytes; U4 whether QueryAuditCodec verifies auditSha256.
Verified without finding: JDK HttpServer + uPickle only, no AWS client; no reimplementation; closed GraphQuery templates; constant-time digest compare over all digests; scopes; job ownership; idempotency binding; workflow not retried; per-dependency breakers fail /readyz; error bodies carry requestId/traceId; all 15 examples used; no header injection; thin routes; health/readiness expose names only; explain has no reasoning, question text or names; maps and queues bounded; no second race of the :271 kind. Unrecorded deferral: graph nodes researcher-only (typed 422).

Orchestrator routing: all findings to service-engineer as SE-P10-02 (repair 01).

## CR-P10-03 (code-reviewer, read-only re-review after repair 01) — VERDICT: REQUEST_CHANGES

Prior findings: M1 FIXED; M2 FIXED at pool level (gaps N3, N5); m1 FIXED; m2 FIXED (no-names consistent); m3 PARTIALLY FIXED (N4); m4 FIXED; n1, n2, n4, n5 FIXED; n3 NOT RECORDED (explain/evidence still unscoped per client; rationale broken by N1); U1 introduced N2; U2 unaddressed (residual); U3 FIXED; U4 VERIFIED (storage Records.scala:173-175 and ReadServices.scala:402-406).

| ID | Sev | Location | Issue | Owner |
|---|---|---|---|---|
| N1 | major | ReadServices.scala:126-129; Router.scala:224, 227 | Evidence `occurrences[].queryId` reveals other clients' query IDs; any read-scope client can then GET /v1/explain/{id} for their plan. Violates README.md:2719 (cross-tenant/unauthorized evidence access). Introduced by repair; M1 test asserts the leaking field. | service-engineer |
| N2 | major | HttpServerAdapter.scala:93-98 | CallerRunsPolicy runs Exchanges on the single JDK dispatcher thread when the http pool is full: whole server stops accepting/serving (incl. /healthz, /readyz) for up to queueWait+syncTimeout, indefinitely with a slow body (U2). Reintroduces head-of-line blocking at transport. Untested. | service-engineer |
| N3 | minor | Resilience.scala:80-82 | cancel(false) succeeds on an already-running FutureTask: a started call reported NotStarted -> 503, key abandoned, retry runs a second workflow. Race untested. Fix: CAS claim. | service-engineer |
| N4 | minor | Stores.scala:151-154; QueryService.scala:311; Resilience.scala:85-87; Support.scala:51 | After sync 504, cancel(true) cannot stop an interrupt-ignoring or lock-waiting workflow; key abandoned -> duplicate execution under one key; docstring claim false. | service-engineer |
| N5 | minor | RepairP10Suite.scala:22-34, 82-118 vs QueryService.scala:24-28, Support.scala:49-52 | M2 test hangs outside `access`; with StoreAccess.serialized a slow workflow still blocks reads, opens circuit-key-value, fails /readyz. Acceptable only if recorded explicitly as uncovered residual (thread-safe adapters: storage-engineer). | service-engineer |
| X1 | nit | Resilience.scala:224-226; ServiceConfig.scala:116-180 | Deadline-truncated attempt counted as breaker failure; no rule readRequestTimeoutMillis >= readTimeoutMillis. | service-engineer |
| X2 | nit | Resilience.scala:80-83, 206 | Queue wait not charged to request deadline. | service-engineer |
| X3 | nit | RepairP10Suite.scala:349-351 | End-to-end U3 case passes without the scan; use a closed deep body. | service-engineer |
| X4 | nit | ReadServices.scala:134 | occurrencesTruncated true at exactly evidenceMaxBundles rows. | service-engineer |
| X5 | nit | ReadServices.scala:115-137 vs README.md:2500-2516 | Evidence shape diverges from README example; record (docs: documentation-engineer). | service-engineer |

Unverified: U-a JDK dispatcher behaviour on rejection; U-b sun.net.httpserver.maxReqTime/maxRspTime names; U-c whether rag semantic gap lines carry stored text into abstention bodies; U-d counts not observed by reviewer (orchestrator observed them, test-report.md).

Orchestrator routing: N1-N5, X1-X5, n3 record and U-c trace to service-engineer as SE-P10-03 (repair 02), combined with the human-ordered local entry points (three services) to use one `sbt check`; then CR-P10-04.

## CR-P10-04 (code-reviewer, read-only re-review of repair 02 + three entry points) — VERDICT: APPROVE

No verified critical or major finding remains. Reviewer ran no commands; test evidence from the orchestrator (test-report.md CURRENT).

Part A: N1 FIXED (no queryId in occurrences, ReadServices.scala:141-147; explain ownership-gated before store read, :418-420; identical 404 for unknown/other; jobs client-filtered; metrics and access log carry no queryId; bundleId not invertible, 128-bit SecureRandom queryId; test discriminates). N2 FIXED for the dispatcher (admission pool, HttpServerAdapter.scala:125-133; no readBounded on that path; overloaded router serves only /healthz, /readyz; test discriminates). N3 FIXED (single CAS, Resilience.scala:116, 127; both orders tested with probes). N4 FIXED (QueryService.scala:325-327; Resilience.scala:121; test discriminates). N5 RECORDED (QueryService.scala:23-32; Support.scala:44-47). X1-X4 FIXED with discriminating tests. X5 RECORDED. U-c conclusion holds for semantic, metric (CollaborationMetrics.scala:332) and community (CommunityAnalyst.scala:133-139) gap sources; test does not prove the property.
Part B: single route table, filtered per kind, /v1 partition tested; pools/breakers/readiness per kind; main code depends only on core, rag.agents, storage.model, storage.port; no hardcoded hosts, ports or digests; no secrets logged; three-service test uses ServiceMain.start per kind with separate routers and HttpServers and reaches an evidence-backed answer; shutdown bounded (~3 x graceSeconds).
Spec L1576-1658: nothing missing that is not recorded as deferred.

| ID | Sev | Location | Issue | Owner |
|---|---|---|---|---|
| m1 | minor | ReadServices.scala:53-59 vs 46-51, 141-148 | Occurrences expose other clients' per-query score and excerpt; matching bundleIds reveal which evidence a foreign query co-cited; polling reveals query activity (README.md:2719 side channel). No query ID or plan exposed. Options: caller-only occurrences, drop score/text/bundleId for others, or human accepts sharing and rationale is corrected. | service-engineer (human decision) |
| m2 | minor | Stores.scala:124-127; ReadServices.scala:419; QueryService.scala:468 | QueryOwnership port has no error channel and runs on the HTTP worker outside pool/timeout/retry/breaker; a durable adapter outage silently loses ownership or blocks a worker; 404 instead of 503/504. Fix: Either[StoreError, ...] through ReadPolicy/Calls. | service-engineer (then storage-engineer adapter) |
| m3 | minor | Stores.scala:184-191, 218-227; QueryService.scala:325-327; Resilience.scala:179-181, 202, 205-209; ServiceRuntime.scala:138-139 | A never-ending, interrupt-ignoring workflow pins its key and thread forever; when all workflow threads are stuck the breaker sits half-open and /readyz reports 200 while every query gets 503. Fix: stuck-worker readiness check, or record residual. | service-engineer |
| n1 | nit | HttpServerAdapter.scala:59 | overflowCapacity default 64 hardcoded (contradicts "no defaults"); tests rely on it. | service-engineer |
| n2 | nit | EntryPointsSuite.scala:138; ServiceWorld.scala:574-583 | "three processes" test is one JVM sharing StoreAccess and ownership; proves separate routers/ports, not processes; wording. | service-engineer |
| n3 | nit | ServiceMain.scala:62-66; HttpServerAdapter.scala:41-47 | No window between drain and server.stop for a load balancer; ~3 x graceSeconds worst case undocumented and untested with an in-flight exchange. | service-engineer |
| n4 | nit | RepairP10bSuite.scala:219-242 | X1 test uses system clock with 50 ms margins; possible flake on a loaded runner. | service-engineer |
| n5 | nit | ServiceRuntime.scala:61, 69-70 | Null ownership silently becomes a per-process in-memory record; split deployment makes every explain 404 without a composition error. | service-engineer |

Unverified (JDK internals, not in repository): U-1 AbortPolicy rejection from a full admission queue may leak the connection or reach the dispatcher loop; U-2 headers read on the executor thread, so a trickling client can stall the single admission thread (extends U2); U-3 ExchangeImpl.close may drain up to ~64 KiB of body on the admission path; U-4 not every gap source checked; U-5 counts not observed by reviewer.

Manual action named by reviewer: the human accepts or rejects the evidence-sharing design (m1) in human-gate.md.

Human decisions (2026-11-22, orchestrator question): m1 -> caller-only occurrences (shared identity/provenance to any read client; occurrences only for the caller's own queries). Fix m2, m3, n1-n5 now. Orchestrator routing: service-engineer SE-P10-04 (repair 03); U-1..U-3 recorded as residuals with U2; then CR-P10-05.

## CR-P10-05 (code-reviewer, read-only re-review of repair 03) — VERDICT: APPROVE

No verified critical or major finding remains. Reviewer ran no commands; test evidence from the orchestrator (test-report.md CURRENT: services 92/92; 192 suites, 2324 passed, 1 skipped).

Verified: m1 FIXED as specified (ReadServices.scala:107-110, 146-154, 76-78, 97-106; fail closed :107, :428; tests RepairP10cSuite.scala:20-68, 70-98, 125-152 discriminate), with residual side channels F1, F2 and unverified U-a. m2 FIXED (Stores.scala:131-137; Resilience.scala:289-352; QueryService.scala:478-481, 331-332; async record before jobs.create; ownedBy bounded by evidenceScanLimit; test :154-180). m3 FIXED for workers (Resilience.scala:155-160; ServiceRuntime.scala:155-160; deterministic test :184-210); breaker latch defective (F3). n1 FIXED (HttpServerAdapter.scala:58-66, 124-127; EntryPointsSuite.scala:177-188). n2 FIXED (:190-192). n3 FIXED (ServiceMain.scala:79-87; EntryPointsSuite.scala:286-332). n4 FIXED (RepairP10bSuite.scala:212-259 plus control). n5 FIXED (ServiceRuntime.scala:68-71; EntryPointsSuite.scala:146-157). Spec L1576-1658: nothing missing that is not recorded as deferred.

| ID | Sev | Location | Issue | Owner |
|---|---|---|---|---|
| F1 | minor | ReadServices.scala:88-93, 107-110 (claim :64-66); ItemCodecs.scala:250-252; InMemoryKeyValueStore.scala:169-170 | Scan window cut before the ownership filter: foreign bundle IDs sorting below the caller's push its own occurrence out, so polling reveals a count of foreign co-citations and their timing. Repair claim "never a leak" false. Untested beyond the window. Fix: query the caller's rows directly (owner-keyed index or byQuery path) or page the partition; or accept and correct the docstring. | service-engineer (human decision) |
| F2 | minor | ReadServices.scala:97-99 (claim :54-61); rag Audit.scala:276-286; StableIds.scala:480-509 | EvidenceBundle rows are written only by per-query audits and evidence IDs are computable from public inputs, so 404 -> 200 reveals the first foreign citation and its timing. Docstring premise false. | human decision, then service-engineer |
| F3 | minor | Resilience.scala:225-236, 248-254; ServiceRuntime.scala:161-162 | `unprovenTrial` latch cleared only by real success/failure; after a transient overload readiness stays 503 and a ready-only load balancer never sends the traffic that would clear it (/healthz 200, no restart). Unit test clears it with direct calls. Fix: expire the latch with the next openMillis period, or readiness = workers live + not open. | service-engineer |
| F4 | nit | QueryService.scala:478; Resilience.scala:320; ServiceWorld.scala:551-557 | ownership.record now runs inside StoreAccess; with serialized local access a concurrent query's record waits on the workflow's lock, can count as key-value breaker failure, 504, and open circuit-key-value. N5 note mentions reads only. Record, or keep the in-memory ownership call outside `access`. | service-engineer |

Unverified: U-a latency side channel with a durable batch lookup; U-b ownership capacity eviction by floods of refused submissions (record precedes breaker/pool/queue checks; not checked whether this predates repair 03); U-c evidenceScanLimit not validated against the adapter's maxQueryLimit (Refused -> 500); U-d U2 and U-1..U-3 remain; U-e counts not observed by reviewer.

Manual action named by reviewer: human decides F2, and whether F1 is fixed or accepted as a recorded residual.

Human decisions (2026-11-22, orchestrator question): accept F1 and F2 as recorded residuals with corrected docstrings (durable owner-keyed index deferred to storage-engineer); fix F3 and F4 now. Orchestrator routing: service-engineer SE-P10-05 (repair 04), including U-b check and U-c record; then CR-P10-06.

## CR-P10-06 (code-reviewer, read-only re-review of repair 04) — VERDICT: APPROVE

Reviewer ran no commands; test evidence from the orchestrator (test-report.md CURRENT: services 97/97; 193 suites, 2329 passed, 1 skipped).

Verified: F1, F2, U-c docstrings accurate and complete (ReadServices.scala:53-82, 175-177; U-c matches InMemoryKeyValueStore.scala:151-152 -> Resilience.scala:339-341). F3 correct (Resilience.scala:198-200, 205-221, 240-246, 261-267; trial released on every path, render :451-493, ReadPolicy :327-363; no false 200 beyond ordinary half-open; tests RepairP10dSuite.scala:32-47, 49-94, 108-147 discriminate). F4 correct (ServiceRuntime.scala:108-115; QueryService.scala:441; ReadServices.scala:127-129, 452; InMemoryQueryOwnership synchronized, Stores.scala:154-166; test :151-184 discriminates). U-b correct (record only inside `admitted`, QueryService.scala:411-418, 436-444; workflow only on Right; sync retry keeps ownership; job path records once; refused submissions record nothing; test :188-214; m2 test still holds). No other regressions found.

| ID | Sev | Location | Issue | Owner |
|---|---|---|---|---|
| m-A | minor | QueryService.scala:418, 436-444, 463-471; ServiceConfig.scala:182-193; Resilience.scala:357-359 | Ownership record now runs under the workflow timer but is bounded by readRequestTimeoutMillis, which config does not require to fit within sync/async timeouts. With a hanging record (validated defaults 10000/5000x3/20000), the workflow timer fires first: 504 "workflow did not finish", workflow breaker failure, key-value breaker neutral; circuit-workflow opens and /readyz blames the workflow. Fail closed still holds. Regression from repair 04. Fix: bound ownership by the remaining workflow budget, treat a timeout before workflow.answer starts as neutral, or add a config rule. | service-engineer |
| n-A | nit | ServiceRuntime.scala:104-107; QueryService.scala:33-35 | "Never waits" claims too strong: ownership shares the key-value pool and breaker with store reads that block on the serialized lock, so under N5 ownership records can get 503 overloaded or circuit-open. Correct the comments. | service-engineer |
| n-B | nit | QueryService.scala:340-341, 545-548 vs repair-04.md:12 | Idempotency-Key release on ownership-record failure holds for sync only; async completes the key as JobRef at 202 and a later record failure becomes the job's final error, replayed until TTL. Consistent with existing async refusal handling; state it. | service-engineer |

Unverified: U-1 if queueWaitMillis > breakerOpenMillis, a late notStarted() could release a concurrent half-open trial (pre-existing via neutral()); U-2 counts not observed by reviewer; U-3 key-value pool has no `*-workers` readiness check.

Manual action named by reviewer: human decides whether m-A is fixed now or recorded as a residual.

Human decision (2026-11-22, orchestrator question): one last small fix. service-engineer SE-P10-06 (repair 05) fixes m-A, corrects n-A and n-B comments, and records U-1 and U-3. One `sbt check`, verified by the orchestrator against the run output; no further code-review round. The change after CR-P10-06 is to be disclosed in human-gate.md.
