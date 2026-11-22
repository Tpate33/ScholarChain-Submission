# Phase 10 prompt and work orders (orchestrator assembly, 2026-11-22)

Records state only; approves nothing.

## Availability
- Verbatim below: the human's opening message and later human instructions in this session, and every orchestrator work order and review order sent to subagents (copied from this session's tool calls).
- Not verbatim: the human's answers to orchestrator multiple-choice questions are recorded as the selected option labels plus a paraphrase of each question. Each question's full text is in this session's transcript, which is not stored in the repository.
- Not available here: no instructor or course text beyond README.md "Phase 10: expose the runtime through RESTful microservices" (L1576-1658 at 3d9da3e), the authoritative Phase 10 work order, cited and not copied.

## 1. Opening message (verbatim)

```text
Begin with the bounded logging fix, review it, then implement the
minimal Phase 10 services with in-memory fakes.

Read docs/agent-runs/phase-10/release-scan-cr-rel-01.md and retain
its findings for later remediation. Do not let publication cleanup
expand this implementation task.

Keep this local: no deployment, paid calls, AWS resource changes,
publication, Git history rewriting, commits or pushes.

At completion, report test results, review findings, changed files,
remaining gaps and the exact next human closeout steps.

The professor’s prompt defines the requirements. The work order
defines our immediate implementation scope and priorities.

Start with the bounded logging fix, then implement the minimal
Phase 10 services locally with in-memory fakes. Reuse existing
working components.

Do not repeat the submission plan or expand into later phases.
Record deferred requirements explicitly; do not claim they are done.
If these instructions conflict with a requirement, identify the
specific conflict while continuing independent authorized work.

No deployment, paid calls, AWS resource changes, publication,
history rewriting, commits or pushes.

Submission context:
- Deadline: November 25 at 11 PM CST; target submission at 8 PM.
- Phase 9 is accepted locally and pushed: implementation 2926661,
  closeout 3d9da3e. Reuse that work.
- Overall priority: a working, reproducible AWS application with
  evidence-backed answers, then evaluation and submission artifacts.
- Targets: local Phase 10 services by Sunday Nov 22 at 3 PM;
  real adapters and packaging by Sunday night; first small deployed
  end-to-end query by Monday Nov 23 at 9 PM; at least 10,000 meaningful
  real embeddings loaded by Tuesday noon.
- These are scheduling targets, not permission to skip requirements.
  Document deferred work and possible deductions honestly.
- Total budget is $25 including previous spending. Account identity
  and remaining budget are still being checked.
- Read docs/agent-runs/phase-10/release-scan-cr-rel-01.md. Preserve its
  findings for remediation; do not expand this task into publication cleanup.
- Collect documentation and test evidence alongside implementation.

Immediate scope:
Start with the bounded logging fix and review, then implement minimal
Phase 10 services locally with in-memory fakes. Follow repository
ownership and review rules. Run builds sequentially.

Do not repeat the overall plan or implement later phases now.
No deployment, paid calls, AWS resource changes, publication,
history rewriting, commits or pushes.

Finish with test results, review findings, remaining gaps and the
exact next human closeout steps.
```

## 2. Later human instructions (verbatim)

### 2a. After the first marker pause
```text
The saved test marker and git status have been checked. The marker
shows 188 suites, 2278 passed, 0 failed, 0 errored and 1 skipped,
written at 2026-11-22T06:08:02.667059Z against HEAD 3d9da3e.

Continue collecting the services review. Route required fixes to
the owning agent, then rerun the appropriate checks after changes.
Do not rerun the unchanged build solely because of the audit pause.

Do not close Phase 10, commit or push yet. Report the review verdict
and remaining gaps, particularly the deferred separate service entry
points required for deployment.
```

### 2b. While repair 01's check was running
```text
Collect the existing shell’s result and service-engineer’s repair
report. If the check passed, confirm the refreshed test marker and
send the repairs for reviewer recheck. If it failed, route the
specific failures back to the owning agent.

Do not start a duplicate build or change the hooks.
Phase 10 remains open; no commits or pushes.
```

### 2c. Repeated request during repair 01's check
```text
Collect service-engineer’s completed report and existing test-run
result. Report any failure explicitly; if it passed, verify the
refreshed marker and route the repairs for reviewer recheck.
Do not rerun solely because of the repeated Stop-hook messages.
```

### 2d. After the repair-01 marker pause (three-service decision)
```text
The saved marker and git status have been checked. The marker shows
189 suites, 2293 passed, 0 failed, 0 errored and 1 skipped, written at
2026-11-22T06:31:33.109969Z against HEAD 3d9da3e. Changed paths match
the reported Phase 10 work.

Continue collecting CR-P10-03's re-review verdict. Do not rerun the
unchanged build solely because of this audit pause. Do not modify
the marker or hooks.

Phase 10 remains open. After the review, implement the missing local
service entry points through the appropriate owners, following our
three-service grouping: query/jobs, evidence/explain, and graph.
Keep packaging and infrastructure work assigned to their owners.
No deployment, paid calls, commits or pushes.
```

### 2e. While repair 02's check was running
```text
Collect the existing repair-02 test run’s output and exit status.
If it passed, verify the refreshed marker and request reviewer
recheck of both the repairs and the three entry points.
If it failed, report and repair the specific failures.
Do not launch a duplicate build or modify the hooks.
```

### 2f. Closeout request
```text
The final saved marker and git status have been checked:
194 suites, 2331 passed, 0 failed, 0 errored, 1 skipped;
written 2026-11-22T16:05:35.223980Z against HEAD 3d9da3e.
Changed paths match the reported Phase 10 work.

Prepare the closeout records now:
1. Assemble prompt.md from the original prompt and work orders
   verbatim where available; identify anything unavailable rather
   than reconstructing it as an exact quotation.
2. Record all residuals, deferred work, owners and deployment
   prerequisites in outstanding.md.
3. Draft human-gate.md for my review, explicitly distinguishing
   local acceptance from deployment readiness and disclosing that
   repair 05 passed tests but was not re-reviewed.
4. Summarize the logging minor finding and three nits so I can
   make the remaining acceptance decision.
5. Provide the exact human commit, hash-recording and push sequence.

Do not sign acceptance for me, commit, push, rerun unchanged tests,
or begin another phase.
```

## 3. Human answers to orchestrator questions (option labels; questions paraphrased)
1. After BE-P10-01's backgrounded marker write: continue with Bash-running agents, marker reviewed at closeout. Answer: "Continue (Recommended)".
2. CR-P10-04 m1, how evidence sharing should work. Answer: "Caller-only occurrences (Recommended)".
3. CR-P10-04 remaining minors and nits. Answer: "Fix now with m1 (Recommended)".
4. CR-P10-05 F1/F2 side channels. Answer: "Accept both as residuals (Recommended)".
5. CR-P10-05 F3/F4. Answer: "Fix both now (Recommended)".
6. CR-P10-06 m-A, how to finish Phase 10. Answer: "One last small fix (Recommended)": fix m-A, correct n-A and n-B, one `sbt check` verified by the orchestrator, no further review round, disclosed in human-gate.md.

## 4. Orchestrator work orders and review orders (verbatim)

### BE-P10-01 (build-engineer)
```text
WORK ORDER BE-P10-01 (Phase 10, bounded logging fix). Orchestrator: main session. Date 2026-11-21. HEAD 3d9da3e (Phase 9 accepted locally).

Context (from docs/agent-runs/phase-09/test-report.md "Observed risks" item 5 and outstanding.md "Process"): a full `sbt check` produces roughly 7.7 million lines of DEBUG logging from the `tests` sbt project (cross-module/adversarial tests that exercise Spark/Hadoop/other libraries). The human deferred it to build-engineer; it is now authorized as the first Phase 10 item.

Goal: bound that logging (e.g. default WARN for third-party loggers, or INFO at most) so test runs emit a bounded, small amount of log output, WITHOUT hiding test failures, sbt test summaries, or the markTestRun line, and without weakening any test or gate.

Steps:
1. Diagnose with Read/Grep/Glob first: which logging backend(s) land on the `tests` project Test classpath (slf4j binding, log4j2, logback, slf4j-simple, nop, or none -> default), why DEBUG is the effective level (e.g. a backend with no config defaulting to DEBUG, such as logback with no logback-test.xml), and whether other projects (sparkAnalytics, hadoop, storage, rag) have the same issue. Report the evidence (file:line, dependency names from the version ledger).
2. Fix inside your territory only (build.sbt, project/**). Preferred approaches: a build-level setting such as Test/javaOptions system properties for the forked test JVM, a test-scoped logging binding/config choice in the ledger, or excluding a stray DEBUG-default binding. Any new dependency must go through the single version ledger with a pinned version; keep it Test scope. If the only correct fix requires a resource file under tests/** (test-engineer) or modules/** (module owners), do NOT write it: return STATUS: BLOCKED-ON with the owner and the exact file content needed.
3. Add a guard so the regression is caught (a build-level check or a test owned by you, if one fits your territory; if a test is required in another owner's tree, describe it under BLOCKED-ON/RISKS rather than writing it).
4. Run `sbt check` exactly once at the end (sequentially, no parallel builds). Report the exact per-project totals and the markTestRun line. If the harness backgrounds the run after 180 s, wait for it and say so; do not make another Bash call until it finishes. Report the observed log volume before/after if you can measure it with allowed commands (otherwise say it was not measured).

Constraints: local only. No deployment, AWS calls, paid calls, publication, commits, pushes, or history rewriting. Do not touch Phase 10 services work, docs, or publication-cleanup items (CR-REL-01 findings are recorded separately and are out of scope). Do not edit the test marker by hand.

End with the full report contract: STATUS, OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN, TEST RESULTS, VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE.
```

### CR-P10-01 (code-reviewer)
```text
REVIEW ORDER CR-P10-01 (Phase 10, read-only). Orchestrator: main session. Date 2026-11-21. Base: HEAD 3d9da3e.

Review the uncommitted build-engineer change BE-P10-01 "bounded test logging", scope exactly:
- build.sbt (modified: keys testLoggingInputs, verifyTestLogging; def boundedTestLogging; tests uses boundedTestLogging(Log4j2, forceProvider = true); services uses boundedTestLogging(Logback, forceProvider = false); verifyTestLogging added to checkSteps after verifyDependencyScopes, before markTestRun)
- project/TestLogging.scala (new)
- project/test-logging/tests-log4j2.properties (new)
- project/test-logging/services-logback.xml (new)
Ignore docs/agent-runs/phase-10/ and anything else.

Context: Phase 9 recorded ~7.7M DEBUG lines per `sbt check` from the `tests` project. Diagnosis by build-engineer: tests Test classpath has two SLF4J 2 providers (logback-classic 1.5.18 via `services % Test`, and log4j-slf4j2-impl 2.20.0 via Spark); logback binds first with no configuration, so it defaults to DEBUG. Fix: Test/javaOptions only (-Dslf4j.provider=org.apache.logging.slf4j.SLF4JServiceProvider, -Dlog4j2.configurationFile=<repo>/project/test-logging/tests-log4j2.properties, -Dslf4j.internal.verbosity=WARN for tests; -Dlogback.configurationFile=<repo>/project/test-logging/services-logback.xml for services), dropped when an owner test config exists. A guard verifyTestLogging (self-test with 28 inputs, static rules, probes forking a Java source launcher on the real Test classpath, counter-probe) runs inside `sbt check`. Reported: one `sbt check` exit 0, 446 s; 183 suites, 2232 passed, 0 failed, 1 skipped; full output 4,845 lines; "verifyTestLogging: OK, 16 checks passed". No new dependency, ledger unchanged.

Verify, with file:line evidence:
1. Correctness: does the fix actually bind log4j2 for tests and bound levels at WARN? Could it hide test failures, sbt summaries, or the markTestRun line? Does it weaken any gate or test?
2. Ownership: only build-engineer paths changed; no file under tests/** or modules/**; no *.sbt outside build.sbt/project (F1 BuildGuard).
3. Guard quality: can verifyTestLogging pass while DEBUG is effectively on (false pass)? Is the counter-probe meaningful? Does the probe write anywhere outside target/? Are its forks bounded (timeouts) and environment-isolated consistently with the build's AWS/SCHOLARCHAIN isolation? Any path built from user.home or env that conflicts with "configuration is external"/held-out fences?
4. Robustness: absolute-path javaOptions vs. relocation, Windows/spaces, behavior when the owner config appears, forkless test runs (are tests and services forked so javaOptions apply?).
5. Security: no secrets, no network, no gold/ or held-out reads.

Return APPROVE or REQUEST_CHANGES with verified findings (severity, file:line, failure input, impact, owning agent). Do not fix anything.
```

### SE-P10-01 (service-engineer)
```text
WORK ORDER SE-P10-01 (Phase 10: expose the runtime through RESTful microservices, minimal LOCAL scope). Orchestrator: main session. Date 2026-11-21. Base HEAD 3d9da3e (Phase 9 accepted locally: implementation 2926661). The tree also holds an uncommitted build-engineer change (build.sbt, project/TestLogging.scala, project/test-logging/**) under review; do not touch it.

Authoritative requirements: README.md "Phase 10: expose the runtime through RESTful microservices" (L1576-1658). Read it first. Summary:
- Query API: POST /v1/query (validate, create query ID and idempotency record, start sync or async Graph-RAG workflow, return answer or job reference).
- Jobs API: GET /v1/jobs/{jobId}, GET /v1/jobs/{jobId}/result (state, timestamps, stage, errors, result link).
- Evidence API: GET /v1/evidence/{evidenceId} (provenance, source record, source URI, graph path or text pointer, compact supporting content).
- Graph: GET /v1/graph/nodes/{nodeId}, GET /v1/graph/nodes/{nodeId}/neighbors (bounded depth, edge types, result count, timeout; pagination/cursor).
- Explain/audit: GET /v1/explain/{queryId} (QueryPlan, stages, prompt versions, model versions, evidence IDs, score components, counters, verification status; never chain-of-thought).
- Ops: GET /healthz, GET /readyz (distinct semantics), GET /metrics.
- HTTP behavior: JSON contracts, /v1 path versioning, request IDs and trace IDs (every error carries the trace ID), Idempotency-Key on POST, timeouts, bounded retries, circuit breaker, typed error model, authentication/authorization, no secrets in responses or logs, thin routes (no retrieval algorithms in routes; logic in services/ports).
- Required tests: successful sync query; async query path; invalid JSON; unsupported query constraint; missing evidence; idempotent repeated request (and same key with a different body); timeout and downstream failure; unauthorized request; health vs readiness distinction; full query-to-evidence lifecycle with fakes reaching an evidence-backed answer on bounded fixtures. Request/response examples must match contract tests.

Scope and decisions (immediate priority is a working local service with in-memory fakes; keep it minimal, reuse existing components):
1. REUSE the Phase 9 rag workflow (modules/rag: the collaboration-analysis workflow, WorkflowResult, PersistReport, AuditConfig, abstention reasons, evidence bundles) and the existing storage ports and in-memory fakes / fixture world already used by rag and storage tests. Do not reimplement retrieval, resolution, synthesis or verification. Wire services -> core, rag, storage only (CLAUDE.md module graph). rag gives ports only; services composes.
2. HTTP transport: prefer the JDK built-in com.sun.net.httpserver (no new dependency) behind a pure, in-process router (Request => Response) so most tests run without sockets; add at least one test over a real loopback socket on an ephemeral port. JSON: uPickle (the one JSON library). If you conclude a new dependency is truly needed, do NOT edit build.sbt: return BLOCKED-ON build-engineer with the exact need.
3. Authentication for the course deployment: bearer API key compared in constant time against a configured SHA-256 digest supplied through a config/secret port (production source: AWS Secrets Manager, deferred). Tests use a fake. No hardcoded secrets, endpoints, thresholds or model IDs; pass limits as explicit configuration values (core RuntimeConfig keys are not yet defined, so if you need a core contract change, record it as deferred to domain-engineer rather than editing modules/core).
4. Async path: in-memory job store with a bounded executor; job states and timestamps. Idempotency: in-memory store keyed by key + request digest (a repeated identical request returns the same query/job; same key with a different body is a typed conflict error).
5. Resilience: per-call timeouts, bounded retries for idempotent downstream reads only, and a simple circuit breaker, tested with fakes that fail or hang (use bounded waits; no long sleeps).
6. Logging: add a production modules/services/src/main/resources/logback.xml at INFO or WARN (BE-P10-01 risk 1: services runtime currently defaults logback to DEBUG). Never log Authorization headers, keys or raw question text if the audit policy forbids it (Phase 9 decision: the audit stores no raw question text or researcher names).
7. Status-code mapping and typed errors: document in the report (400 invalid JSON/validation, 401 missing/invalid credentials, 403 if authz applies, 404 unknown evidence/job/node/query, 409 idempotency conflict, 422 unsupported constraint, 429 if relevant, 503 not ready/circuit open, 504 timeout, 500 internal; each with code, message, requestId, traceId).
8. Do not implement later phases: no Terraform, ECS, Docker changes, real AWS adapters, real LLM, or real embeddings. Do not touch publication cleanup. Record every deferred requirement explicitly (e.g. real Neptune/DynamoDB/S3 Vectors-backed services, Secrets Manager key source, real LLM, deployed /metrics model, independent scaling as separate deployables if you implement them as one process with separable service classes).

Process:
- Builds strictly sequential. Iterate with your own-module tasks (e.g. `sbt services/test`, `sbt services/Test/compile`). Run `sbt check` exactly once at the end and report exact per-project totals and the markTestRun line. The harness may background sbt after 180 s; if so, wait and make no Bash call until it finishes, and report it.
- An earlier backgrounded run caused an UNATTRIBUTED audit refusal on the test marker; the human has approved continuing. If your first Bash call is refused as UNATTRIBUTED for .claude/.last-test-run only, the baseline is reset: re-run the same command once and report it. Any other UNATTRIBUTED path: stop, BLOCKED-ON human.
- Every behavior change ships with feature tests in modules/services/src/test/scala.
- Local only: no deployment, AWS calls, paid calls, publication, commits, pushes or history rewriting.

Report: route table, status-code mapping, timeout/retry/circuit-breaker policy, internal dependency graph, authentication method, request/response examples and the tests that pin them, exact test counts, the list of deferred requirements, plus the full report contract: STATUS, OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN, TEST RESULTS, VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE.
```

### SE-P10-01 follow-up messages (verbatim)
```text
Orchestrator status check for SE-P10-01: is your final `sbt check` still running? When it finishes, report its exact per-project totals and the markTestRun line in your final report contract. If you are not running it, run `sbt check` once now (sequentially, no other Bash call while it runs). If it failed and you can't fix it in modules/services/**, return STATUS: FAILED or BLOCKED-ON with the failing output. Don't touch the test marker.
```
```text
Orchestrator: SE-P10-01 shows as completed but no final report reached me, and the test marker is unchanged (still 2026-11-22T05:30:24Z, 2232 passed). Please deliver your final report now with the full report contract. If your `sbt check` finished, give its exact exit status, per-project totals and markTestRun line. If it failed or never ran, say so with the failing output, and run `sbt check` once now if no other sbt process is running.
```

### CR-P10-02 (code-reviewer)
```text
REVIEW ORDER CR-P10-02 (Phase 10, read-only). Orchestrator: main session. Base HEAD 3d9da3e.

Scope: the new service-engineer files under modules/services/src/** (main/scala/scholarchain/services/{Support,Errors,ServiceConfig,Auth,Resilience,Stores,QueryService,ReadServices,Ops,Router,HttpServerAdapter,ServiceRuntime}.scala; main/resources/logback.xml; test/scala/scholarchain/services/{ServiceWorld,TestHttp,QueryApiSuite,ResilienceSuite,ReadApiSuite,LifecycleSuite,OpsSuite}.scala; test/resources/api-examples/*.json). Out of scope: build.sbt and project/** (reviewed separately as CR-P10-01), docs/agent-runs/**.

Requirements: README.md "Phase 10: expose the runtime through RESTful microservices" (L1576-1658), including its Gate: routes do not embed retrieval algorithms; error responses include trace IDs; health checks expose no secrets; request/response examples match contract tests; at least one end-to-end API test reaches an evidence-backed answer on bounded fixtures. Also the CLAUDE.md invariants: thin routes, provenance and evidence IDs that resolve, deterministic facts not from LLM text, no hidden reasoning in explain, external configuration (no hardcoded thresholds, model IDs, endpoints), no secrets in responses or logs, module graph services -> core, rag, storage only (rag ports, no concrete AWS clients), Phase 9 audit decision (no raw question text or researcher names stored), Phase 9 residual R1 (ID-shaped subject tokens in PARTIALLY SUPPORTED labels) as it surfaces through the API.

Agent's claims to verify (from its report): JDK HttpServer + uPickle, no new dependency; QueryWorkflow adapter over rag CollaborationWorkflow, nothing reimplemented; reads via RuntimeStores read-only facades and closed GraphQuery templates; bearer key SHA-256 compared via MessageDigest.isEqual against all digests without early exit; scopes query/read/ops; job ownership enforced (another client's job -> 404); idempotency key + body digest, 409 reused / in-progress; workflow never retried; read retries only on transient errors; per-dependency breakers fail /readyz; status mapping 400/401/403/404/409/422/429/502/503/504/500 with code, message, requestId, traceId; 15 frozen examples matched by exact key sets. Known test history: a race at QueryApiSuite.scala:271 failed under full `sbt check` load and was fixed with an `entered` latch; check whether other concurrency tests have the same timing hazard. Known risks: in-memory fakes serialized behind one lock; a cancelled workflow keeps running and persists its audit.

Look especially for: auth bypass (unauthenticated routes, scope checks, timing), idempotency races and unbounded maps, thread/executor leaks, timeouts that don't bound, header injection, information leaks in 500/502 bodies or logs, weak tests (assertions that can't fail, sleeps), mismatches between examples and the spec, and any spec requirement silently missing rather than recorded as deferred.

Return APPROVE or REQUEST_CHANGES with verified findings (severity, file:line, failure input, impact, owning agent). Do not fix anything.
```

### SE-P10-02 (service-engineer, repair 01)
```text
WORK ORDER SE-P10-02 (Phase 10 repair 01 for CR-P10-02 REQUEST_CHANGES). Orchestrator. Your SE-P10-01 run 2 `sbt check` was observed passing by the orchestrator (exit 0, 409 s; services 46/46; 188 suites, 2278 passed, 1 skipped); the human reviewed the marker. Stay in modules/services/** only; local only; no commits, pushes, AWS or paid calls; no build.sbt change (BLOCKED-ON build-engineer if needed).

Fix, each with a feature test that fails without the fix:
- M1 (major) ReadServices.scala:67-69,71-107: EvidenceService.get returns 502 when byEvidence rows for one evidence ID hold different EvidenceItems. The evidence ID (core StableIds.scala:480-505) omits score/text; item rows are per bundle (storage TableKeys.scala:159-169); semantic items carry question-dependent distance/rank (rag EvidenceBuilder.scala:548-555). Two answers retrieving the same chunk at different distances must not make GET /v1/evidence/{id} fail. Compare only the identity fields (type, sourceUri, workId, chunkId, graphPath, runId and provenance) for integrity, and return the query-dependent fields per bundle (for example a list of {bundleId, score} or omit them) without inventing a single score. Add a test with vector documents and two queries sharing a chunk at different distances, plus an end-to-end check that every links.evidence URL of both answers resolves. If the correct fix needs a rag, storage or core change, return BLOCKED-ON that owner with the exact need, instead of editing it.
- M2 (major) ServiceRuntime.scala:51-53,60-77; Resilience.scala:55-60,162,169-176: separate bounded pools per dependency class (workflow vs key-value reads vs graph reads; async jobs must not starve sync or reads). A call that never started (queue wait, rejected) must not count as a breaker failure and must not be retried into the same queue. Measure the timeout from task start or bound queue wait separately with a typed 503 overloaded. Test: saturate the workflow pool, then show evidence/graph/explain reads still succeed, the key-value circuit stays closed and /readyz stays 200.
- m1 QueryService.scala:180: do not pass rag AgentError/store detail text in abstention bodies; return a stable reason tag only (as for refused outcomes). Test that no store key, role or StoreError detail reaches the body.
- m2: add a test that produces an abstention through the workflow (an ambiguous name from the fixture world) and a frozen api-example of the abstained 200 body; assert no researcher name or question text appears.
- m3 Stores.scala:150-157,176-180; ServiceConfig.scala:95-147: never expire in-flight idempotency entries (or require idempotencyTtlMillis > max(sync, async) timeout in config validation, and keep in-flight entries until complete). Test duplicate-execution scenario.
- m4 ReadServices.scala:354,367-372,381: one overall request deadline for explain (and other multi-read requests). Test with a slow store.
- n1 sanitize the logged method (and any request-derived log field) to a safe token set. n2 try/finally so a job or in-flight key is always finalised on unexpected exceptions (test). n4 case-insensitive Bearer scheme (test). n5 attribute 500s to the matched route. n3: keep current behaviour, document it as a design decision in your report.
- Unverified concerns, fix if cheap: U1 HTTP pool rejection must yield a 503 with trace ID rather than a dropped connection (CallerRuns or catch and respond); U2 bound request read time if the JDK allows (else record as residual); U3 bound JSON nesting depth before parsing, or catch StackOverflowError locally, with a test; U4 if ExplainService returns auditSha256, verify it against auditJson or say where it is verified.
- Record graph nodes researcher-only as a deferral.

Also, without implementing them, list precisely what separate service entry points / deployables for independent scaling would need (mains per service: query+jobs, evidence, graph, explain, ops; config; which owner packages them), as the human asked for that gap explicitly.

Process: iterate with `sbt services/test`; run `sbt check` once at the end. If backgrounded, wait for it and report; give exact per-project totals and the markTestRun line. Full report contract at the end.
```

### SE-P10-02 follow-up messages (verbatim)
```text
Orchestrator: SE-P10-02 shows completed but no report reached me. If your `sbt check` is still running in the background, wait for it, then deliver your final report with its exit status, exact per-project totals and markTestRun line. If no sbt process is running and no check has completed since your last edits, run `sbt check` once now. Full report contract, including the per-finding fix list and the separate-entry-points gap.
```
```text
Orchestrator, SE-P10-02: do NOT start a new `sbt check` or any other sbt command. Collect the result of the `sbt check` you already started in the background. Name its background shell ID and output-file path. If it is still running, wait for it to finish without making any other Bash call. Then deliver your final report: exit status, exact per-project totals, the markTestRun line, and any failing test names with their output. Include the per-finding fix list for M1, M2, m1-m4, n1-n5 and U1-U4, plus the separate-entry-points gap list. Full report contract. Do not touch the marker or the hooks.
```

### CR-P10-03 (code-reviewer)
```text
REVIEW ORDER CR-P10-03 (Phase 10 re-review, read-only). Orchestrator. Base HEAD 3d9da3e.

Re-review modules/services/src/** after repair SE-P10-02, against your prior CR-P10-02 findings. The finding table is in docs/agent-runs/phase-10/review-report.md (section CR-P10-02) and the repair summary is in docs/agent-runs/phase-10/repair-01.md. Read both first. Out of scope: build.sbt, project/**, docs.

Observed test evidence (orchestrator, from the run's output file): `sbt check` exit 0, 420 s; services "Passed: Total 61, Failed 0, Errors 0, Passed 61"; 189 suites, 2293 passed, 0 failed, 1 skipped; markTestRun wrote the marker at 2026-11-22T06:31:33Z.

For each of M1, M2, m1-m4, n1-n5 and U1-U4, verify the fix in the code with file:line. Confirm a test exists that would fail without the fix, and is not a tautology or a sleep-dependent flake. Then look for regressions the repair introduced:
- the new pools: leaks, shutdown, bounded queues, NotStarted purge correctness, and readiness semantics
- the `occurrences` evidence shape against the README Phase 10 Evidence API contract (provenance, source record, source URI, graph path or text pointer, compact supporting content), and whether any per-bundle field leaks question text
- CallerRunsPolicy on the HTTP pool (dispatcher-thread blocking)
- the nesting-scan correctness (escapes inside strings)
- the idempotency TTL rule and config validation
- the explain deadline
- the abstained example against the Phase 9 no-names decision

Also check n3: the repair text describes it confusingly. Confirm that explain and evidence remain unscoped per client, and say whether that is recorded acceptably.

Return APPROVE or REQUEST_CHANGES with verified findings (severity, file:line, failure input, impact, owner). Do not fix anything.
```

### SE-P10-03 (service-engineer, repair 02 + three entry points)
```text
WORK ORDER SE-P10-03 (Phase 10: repair 02 for CR-P10-03 REQUEST_CHANGES, then local service entry points). Orchestrator. Your SE-P10-02 `sbt check` was observed passing (exit 0; services 61/61; 189 suites, 2293 passed, 1 skipped); human reviewed the marker. Findings table: docs/agent-runs/phase-10/review-report.md, section CR-P10-03 — read it. Stay in modules/services/** only. Local only: no AWS, paid calls, packaging, Terraform, commits or pushes; no build.sbt/project change (BLOCKED-ON build-engineer if needed); no core/rag/storage edits (BLOCKED-ON that owner with the exact need).

PART A — repair 02 (each fix with a test that fails without it):
- N1 (major): evidence must not reveal other clients' query IDs. Remove queryId from occurrences (and check bundleId cannot be turned into another client's queryId through any route). Scope GET /v1/explain/{queryId} to the client that submitted the query, using services' own bounded ownership record (as for jobs; unknown or other client's -> 404). If durable ownership requires a rag/domain audit field, implement the in-memory ownership now and report the durable part as BLOCKED-ON/deferred to rag-engineer/domain-engineer. Fix the M1 test that asserts the leaking field. Record the evidence cross-client policy (evidence items are shared, non-personal graph/text facts; no query linkage exposed) as an explicit design decision in code docs and your report (closes n3).
- N2 (major): remove CallerRunsPolicy. Admission control in the handler or adapter: an in-flight bound returning 503 overloaded with trace ID immediately; keep the dispatcher thread non-blocking. Test that /healthz still answers while the http pool is saturated.
- N3: CAS claim between worker start and caller withdrawal so a call is either run or NotStarted, never both. Deterministic test of the race window (latches).
- N4: keep the idempotency key in flight until the background workflow actually ends (or record a terminal outcome then); fix the docstring. Test: 504 then same-key retry -> 409 in-progress, workflow runs once.
- N5: record explicitly (code comment and report) that with StoreAccess.serialized a slow workflow still blocks reads and can open circuit-key-value, and that the M2 test does not cover it; residual owned by storage-engineer (thread-safe adapters).
- X1 config rule readRequestTimeoutMillis >= readTimeoutMillis and do not count deadline-truncated attempts as breaker failures; X2 charge queue wait to the request deadline; X3 closed deep-nesting end-to-end test; X4 correct occurrencesTruncated; X5 record the evidence-shape divergence from README.md:2500-2516 in your report (docs are documentation-engineer's).
- U-c: trace whether rag semantic gap lines (rag Workflow.scala:282, r.gaps) can carry stored text into abstention bodies; if yes, filter in services and test, or BLOCKED-ON rag-engineer.

PART B — local service entry points (human decision: three services):
1. QueryJobs: POST /v1/query, GET /v1/jobs/{jobId}, /v1/jobs/{jobId}/result; workflow, jobs and job-workflow pools.
2. EvidenceExplain: GET /v1/evidence/{evidenceId}, GET /v1/explain/{queryId}; key-value pool. Note: explain ownership (N1) needs the query owner, which lives in the QueryJobs process — design this so a separate EvidenceExplain process can check ownership (e.g. an ownership port the stores can back later; in-memory/local implementation now) and report what durable store it needs (storage/infra).
3. Graph: GET /v1/graph/nodes/{nodeId}, /neighbors; graph pool.
Each: its own main object in modules/services, route subset from the existing router (no route logic duplicated), only its own pools, /healthz, /readyz, /metrics, graceful shutdown, bind host/port from configuration. Keep the existing single-process composition working for the tests. Configuration must be external (no hardcoded ports, hosts, thresholds or key digests): if loading the per-service settings needs a core configuration contract (services.query-jobs.*, services.evidence-explain.*, services.graph.*), check core's existing loader first; if a core change is needed, return BLOCKED-ON domain-engineer with the exact keys and types rather than editing core — but still implement and test the mains taking an explicit ServiceConfig. Stores: there are no production adapters yet, so mains take a stores/workflow provider port; provide no fake in main code unless it already exists in main scope — if a local runnable demo needs fixture data, say what is missing and who owns it. Tests: each main's route table serves exactly its subset (others 404), health/readiness per service, and a loopback socket test that starts each of the three on ephemeral ports and runs query -> evidence -> explain -> graph across the three processes' routers (in-JVM is fine), reaching an evidence-backed answer.
Do not do packaging (launchers, images: build-engineer) or ECS/ALB (infra-engineer); list exactly what they need.

Process: iterate with `sbt services/test`; run `sbt check` exactly once at the end; if backgrounded, wait for it and report the exact per-project totals and markTestRun line. Full report contract, with Part A per-finding results and Part B route tables per service, config keys, and the follow-up list for domain-engineer, build-engineer, storage-engineer and infra-engineer.
```

### SE-P10-03 follow-up message (verbatim)
```text
Orchestrator: the marker now shows 2026-11-22T06:55:30Z, 191 suites, 2313 passed, 0 failed. In your final report, include the background shell ID and output-file path of that `sbt check`, and state whether you changed any file after the run started (the Stop hook still lists 16 services files as newer than the marker). Do not start another sbt command unless a source changed after that run; if one did, say which and why, and run `sbt check` exactly once.
```

### CR-P10-04 (code-reviewer)
```text
REVIEW ORDER CR-P10-04 (Phase 10, read-only re-review). Orchestrator. Base HEAD 3d9da3e.

Scope: modules/services/src/** after SE-P10-03, which combined two pieces of work:
- Part A: repair 02 for your CR-P10-03 findings N1-N5, X1-X5, n3 and U-c.
- Part B: three local service entry points (QueryJobsMain, EvidenceExplainMain, GraphMain in ServiceMain.scala), per the human's three-service decision: query/jobs, evidence/explain, graph.

Read these first: docs/agent-runs/phase-10/review-report.md (section CR-P10-03) and docs/agent-runs/phase-10/repair-02.md. Out of scope: build.sbt, project/**, docs.

Observed test evidence (orchestrator, from the run's output file): `sbt check` exit 0, 403 s; services "Passed: Total 81, Failed 0, Errors 0, Passed 81"; 191 suites, 2313 passed, 0 failed, 1 skipped; marker 2026-11-22T06:55:30Z.

Part A: for each finding, verify the fix with file:line, and confirm a discriminating test exists, not a tautology or a sleep-flake. Specifically:
- N1: is there any remaining route by which one client learns another client's query ID, plan or job? This includes bundleId, explain 404 timing or shape differences, metrics labels and access logs. Is the evidence-sharing design decision sound against README.md:2719?
- N2: does the admission pool keep the dispatcher non-blocking? Does it ever read a body? What happens when the overflow queue itself is full? Are /healthz and /readyz really served under saturation?
- N3: is the CAS claim correct in both orders?
- N4: is the key held until the workflow ends, and can that hold leak forever if the workflow never ends?
- N5: is the residual recorded adequately?
- X1-X4.
- U-c: is the reasoning sound?

Part B: check that:
- the route subsets come from the single table with no duplicated logic and partition /v1;
- each main composes only its own pools and breakers;
- the readiness semantics are per service;
- graceful shutdown is bounded;
- ServiceLoader provider handling has no hardcoded hosts, ports, thresholds or key digests;
- there are no secrets in logs;
- main code depends only on core, rag ports and storage ports (no test or fake code in main);
- the cross-process ownership design is workable;
- the three-loopback-service test reaches an evidence-backed answer and actually exercises separate routers.

Flag anything the Phase 10 spec (README.md L1576-1658) requires that is still missing and not recorded as deferred.

Return APPROVE or REQUEST_CHANGES with verified findings (severity, file:line, failure input, impact, owner). Do not fix anything.
```

### SE-P10-04 (service-engineer, repair 03)
```text
WORK ORDER SE-P10-04 (Phase 10 repair 03 for CR-P10-04, which was APPROVE with minors). Orchestrator.
- Evidence of your SE-P10-03 run, observed by me: `sbt check` exit 0 (bhfl2ttwd); services 81/81; 191 suites, 2313 passed, 1 skipped. The human checked the marker.
- Findings table: docs/agent-runs/phase-10/review-report.md, section CR-P10-04. Read it.
- Rules unchanged: modules/services/** only; local only; no build/core/rag/storage edits (BLOCKED-ON the owner with the exact need); no commits or pushes.

Human decisions:
1. m1: caller-only occurrences.
   - GET /v1/evidence/{id} returns the shared identity and provenance fields to any read-scope client.
   - `occurrences` lists only bundles whose query the calling client owns. Use the stored row's query ID internally, checked through the ownership port; never expose it.
   - Other clients' occurrences are omitted entirely: no score, excerpt, bundleId or count. `occurrencesTruncated` must not reveal their existence.
   - If the caller owns none: 200 with identity and provenance and an empty occurrences list. Keep 404 for a nonexistent evidence ID only, so existence stays a property of the published run, not of other queries.
   - Correct the design-decision note.
   - Tests: client B cannot see A's score, excerpt or bundleId; truncation is unaffected by foreign bundles; the owner still sees its own occurrences with per-bundle scores.
   - Update api-examples/evidence.response.json if the shape changes, and record any further README.md:2500-2516 divergence for documentation-engineer.
2. Fix all of m2, m3 and n1-n5 now:
   - m2: give QueryOwnership an error channel (Either[StoreError, ...]) and run record and owner calls through the key-value Calls/ReadPolicy, with timeout, breaker and deadline. A failed record on query completion must not silently lose ownership: choose and document a policy and test it. An ownership outage gives 503 or 504 on explain, never 404. For caller-only evidence, an ownership outage must fail closed (omit occurrences or return 503; your choice, documented and tested).
   - m3: add a readiness signal for stuck or saturated workflow workers (for example, calls running past their timeout), so /readyz fails when every workflow thread is wedged. Also handle a half-open breaker whose trial ended NotStarted. Test both. Record that an interrupt-ignoring workflow can still pin its thread, bounded by workers.
   - n1: no hardcoded overflowCapacity default; make it a validated setting and update the tests.
   - n2: correct the test title and wording (one JVM, separate routers and ports).
   - n3: add a configurable drain window between readiness failing and server.stop; document and test the worst-case shutdown bound with an in-flight exchange.
   - n4: make the X1 test deterministic (TestClock or latches), not 50 ms margins.
   - n5: a missing ownership port is a composition error for split services. Keep the in-memory default only for ServiceKind.All, or require it explicitly, and test it.
3. U-1, U-2, U-3 (JDK internals): do not chase them. Record them as residuals with U2.

Process:
- Iterate with `sbt services/test`.
- Run `sbt check` exactly once at the end. If it is backgrounded, wait, then report the shell ID, the output-file path, the exact per-project totals and the markTestRun line. State whether any file changed after the run started.
- End with the full report contract and a per-finding result list.
```

### CR-P10-05 (code-reviewer)
```text
REVIEW ORDER CR-P10-05 (Phase 10, read-only re-review). Orchestrator. Base HEAD 3d9da3e.

Scope: modules/services/src/** after repair 03 (SE-P10-04) for your CR-P10-04 findings m1, m2, m3 and n1-n5. Read docs/agent-runs/phase-10/review-report.md (section CR-P10-04, including the human decisions) and docs/agent-runs/phase-10/repair-03.md first. Out of scope: build.sbt, project/**, docs.

Human decisions to check against:
- m1, caller-only occurrences: any read-scope client gets the shared identity and provenance fields. `occurrences` lists only bundles whose query the caller owns. Foreign bundles leave no trace: no score, excerpt, bundle ID, count or truncation signal. 404 only for a nonexistent evidence ID.
- Fix m2, m3 and n1-n5 now.

Observed test evidence (orchestrator, from the run's output file): `sbt check` exit 0, 405 s. services "Passed: Total 92, Failed 0, Errors 0, Passed 92"; 192 suites, 2324 passed, 0 failed, 1 skipped; marker 2026-11-22T15:19:46Z.

Verify, with file:line, for each finding: the fix, and a discriminating test (not a tautology or a sleep-flake). Specifically:
- m1:
  - can any client still infer a foreign query's existence, timing or co-cited evidence? Check occurrencesTruncated, response size, latency differences from the ownership batch lookup, the evidenceScanLimit window, metrics and logs.
  - does an ownership outage fail closed?
- m2:
  - does the record-before-workflow policy behave correctly with idempotency and async jobs?
  - are retries bounded?
  - is the batch ownedBy bounded?
- m3:
  - is stuck-worker readiness correct and race-free, with no false 503 under normal load?
  - is breaker.ready consistent with the breaker's state machine?
- n3: is the drain plus shutdown bound real, and is the in-flight test deterministic?
- n5: are composition errors raised for the split services?

Also look for regressions the repair introduced anywhere else in the module, and any Phase 10 spec requirement (README.md L1576-1658) still missing and not recorded as deferred.

Return APPROVE or REQUEST_CHANGES with verified findings (severity, file:line, failure input, impact, owner). Do not fix anything.
```

### SE-P10-05 (service-engineer, repair 04)
```text
WORK ORDER SE-P10-05 (Phase 10 repair 04 for CR-P10-05, which was APPROVE with minors). Orchestrator.
- Your SE-P10-04 run, observed by me: `sbt check` exit 0 (bkr6qqk2d); services 92/92; 192 suites, 2324 passed, 1 skipped.
- Findings: docs/agent-runs/phase-10/review-report.md, section CR-P10-05. Read it.
- Rules unchanged: modules/services/** only; local only; no other module edits (BLOCKED-ON the owner); no commits or pushes.

Human decisions:
1. Accept F1 and F2 as recorded residuals. Make no behaviour change for them, but fix the false claims:
   - Correct the docstrings at ReadServices.scala:54-61 and :64-66 and any "never a leak" wording.
   - F1: say that the scan window is cut before the ownership filter, so foreign co-citations can push out the caller's own occurrence and reveal a rough count and timing.
   - F2: say that evidence-ID existence (404 to 200) reveals the first citation by any query, because evidence rows are written per query and IDs are computable.
   - Name the deferred fix: an owner-keyed index or published-run evidence table (storage-engineer, possibly rag/domain).
   - Also record U-c: evidenceScanLimit is not validated against the adapter's maxQueryLimit. This is deployment config, so note it for infra and domain.
2. Fix F3 and F4 now:
   - F3: the breaker's `unprovenTrial` latch must not hold readiness at 503 indefinitely without traffic. For example, expire it with the next breakerOpenMillis period, so the breaker returns to an allowable half-open state, or base readiness on workers live plus not open. Add a deterministic test with TestClock: an overload makes a NotStarted trial, the pinned calls end, no further traffic arrives, and after the period /readyz returns 200. Cover the key-value breaker too.
   - F4: the in-memory ownership record and lookup must not run inside StoreAccess (the serialized store lock). Keep them on the key-value policy for timeouts and errors, but outside `access`, or give ownership its own access. A durable adapter can later choose. Test: two concurrent sync queries with a slow workflow in the All composition; the second query's ownership record does not wait on the first's lock, gets no 504, and the key-value circuit stays closed. Update the N5 note.
   - U-b: if ownership is still recorded before breaker, pool or job-queue admission, check whether a flood of refused submissions can evict older owners from the bounded in-memory record. If yes, record only after admission succeeds, or record it as a residual. Report which.

Process:
- Iterate with `sbt services/test`.
- Run `sbt check` exactly once at the end. Report the shell ID, the output-file path, exact per-project totals and the markTestRun line, and whether any file changed after the run started.
- End with the full report contract.
```

### CR-P10-06 (code-reviewer)
```text
REVIEW ORDER CR-P10-06 (Phase 10, read-only re-review). Orchestrator. Base HEAD 3d9da3e.

Scope: modules/services/src/** after repair 04 (SE-P10-05) for your CR-P10-05 findings. First read docs/agent-runs/phase-10/review-report.md (section CR-P10-05, including the human decisions) and docs/agent-runs/phase-10/repair-04.md. Out of scope: build.sbt, project/**, docs.

Human decisions:
- F1 and F2 are accepted residuals. The only required change is corrected docstrings, with no false "never a leak" claims and the deferred fix named.
- F3 and F4 are to be fixed. The repair also includes a U-b fix (ownership recorded only after admission) and a U-c record.

Observed test evidence (orchestrator, from the run's output file): `sbt check` exit 0, 421 s; services "Passed: Total 97, Failed 0, Errors 0, Passed 97"; 193 suites, 2329 passed, 0 failed, 1 skipped; marker 2026-11-22T15:43:18Z.

Verify, with file:line:
- F1, F2, U-c: the docstrings are now accurate and complete.
- F3: the expiring unproven-trial mark is correct against the breaker state machine; there is no path where readiness is falsely 200 while the dependency is down; the TestClock tests discriminate.
- F4: ownership runs outside StoreAccess but still under the key-value policy; the shared breaker semantics are sound; InMemoryQueryOwnership is concurrency-safe; the concurrent-query test discriminates (it would 504 on the old code).
- U-b: recording only after admission has no hole.
  - Ask: can a workflow run without its owner being recorded?
  - Ask: can an idempotent retry or an async job lose ownership?
  - Ask: do the job paths record exactly once?

Also look for regressions anywhere in the module.

Return APPROVE or REQUEST_CHANGES with verified findings (severity, file:line, failure input, impact, owner). Do not fix anything.
```

### SE-P10-06 (service-engineer, repair 05; not re-reviewed)
```text
WORK ORDER SE-P10-06 (Phase 10 repair 05, final small fix for CR-P10-06, which was APPROVE).
- Prior run, observed by me: `sbt check` exit 0 (bv1udyol6), services 97/97, 193 suites, 2329 passed, 1 skipped. I verified the marker against that run's output.
- Findings: docs/agent-runs/phase-10/review-report.md, section CR-P10-06. Read it.
- Rules unchanged: modules/services/** only; local only; no commits or pushes.
- Human decision: one last small fix. I will check the counts myself, and there is no further review round, so keep the change minimal and tightly scoped.

1. m-A:
   - Bound the ownership record's deadline by the remaining workflow budget (sync and async), so a hanging record is reported against the key-value dependency (key-value breaker; 504 or 503 text naming the ownership store, not the workflow), never against the workflow breaker.
   - A workflow timeout that fires before `workflow.answer` starts must be neutral for the workflow breaker.
   - Add a ServiceConfig rule: readRequestTimeoutMillis < syncTimeoutMillis and < asyncTimeoutMillis, or the equivalent bound you choose. Document it.
   - Tests: a hanging ownership record, using the validated defaults, does not open circuit-workflow; the 504 text names the ownership step; circuit-key-value accounts for it; plus the config-rule test.
2. n-A: correct the "never waits" comments at ServiceRuntime.scala:104-107 and QueryService.scala:33-35. Ownership shares the key-value pool and breaker with store reads, so under the N5 serialized lock it can still get 503 overloaded or circuit-open.
3. n-B: document that an async ownership-record failure becomes the job's final error and is replayed under the same key until its TTL. This matches the existing async refusal handling. Fix the docstring only.
4. Do not chase U-1, U-2 or U-3. Record U-3 (the key-value pool has no `*-workers` readiness check) and U-1 (a late notStarted versus a concurrent half-open trial when queueWaitMillis > breakerOpenMillis) in code comments as residuals.

Process:
- Iterate with `sbt services/test`.
- Run `sbt check` exactly once at the end. Report the shell ID, the output path, exact per-project totals and the markTestRun line, and confirm no file changed after the run started.
- Full report contract.
```
