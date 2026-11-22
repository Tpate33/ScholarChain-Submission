# Phase 10 repair 05 (SE-P10-06, service-engineer) for CR-P10-06 — final, not re-reviewed

Records state only; approves nothing. Condensed from the agent's final report (STATUS: COMPLETE). Human decision: one last small fix after CR-P10-06 APPROVE; counts verified by the orchestrator; NO further code-review round. This change post-dates the last review and must be disclosed in human-gate.md.

## Findings and results
- m-A (fixed): ReadPolicy.read(deadline, capMillis) takes a real-time cap (System.nanoTime, covering all attempts and queue waits). In QueryService.admitted the ownership record is capped at readRequestTimeoutMillis; `render` gets a `reached` flag set just before workflow.answer. New ServiceConfig rule (documented): readRequestTimeoutMillis < syncTimeoutMillis and < asyncTimeoutMillis, so a hanging ownership store is reported by the key-value policy (504/503/502, key-value breaker), not as a workflow timeout; messages prefixed `recording query ownership:`. If the workflow timer still fires first (jitter), the result is neutral for the workflow breaker and the 504 names the ownership step and says the workflow did not start. ServiceWorld.config derives defaults when unset: readRequestTimeoutMillis = min(8000, 4/5 of the shorter workflow timeout); readTimeoutMillis = min(5000, that). Tests (RepairP10eSuite): hanging ownership record (sync 1000 ms, request bound 800 ms, 2 x 300 ms attempts, threshold 1) -> 504 timeout "recording query ownership...", workflow never ran, circuit-workflow closed/ok, circuit-key-value open/failing; config-rule tests. No deterministic test for the jitter branch (covered by the rule and reasoning). X1 control test in RepairP10bSuite changed from a 20 s to a 5 s request deadline to satisfy the rule; it still opens the circuit.
- n-A (comments): ServiceRuntime.scala key-value policy block and QueryService.scala QueryWorkflow.of note: ownership calls do not wait for the serialized lock themselves, but share the key-value pool and breaker with store reads that do; under N5 they can get 503 overloaded or circuit-open.
- n-B (docstring): an accepted async job completes its Idempotency-Key as JobRef at 202; a later ownership-record failure becomes the job's final error, replayed under that key until TTL; only sync failures release the key.
- U-1 (recorded on CircuitBreaker.notStarted): the breaker does not track which call holds the trial; with queueWaitMillis > breakerOpenMillis a late notStarted() or neutral() can release a concurrent half-open trial.
- U-3 (recorded in ServiceRuntime): the key-value pool has no `*-workers` readiness check; a pinned key-value pool shows only through circuit-key-value.
- U-2 not chased.

## Files
Created: test/.../RepairP10eSuite.scala. Modified: main ServiceConfig, Resilience, QueryService, ServiceRuntime; test ServiceWorld, RepairP10bSuite.

## Commands and rerun evidence
`sbt services/test` (97/99: new m-A test failed on the key-value breaker because the final attempt was truncated, X1 control failed the new rule; then 99/99); `sbt check` (b91m6puid) exit 0 — see test-report.md CURRENT (FINAL).

## Agent-stated assumptions and risks
Assumptions: headroom min(sync, async) - readRequestTimeoutMillis left to deployment config, documented; a deadline-truncated final attempt stays neutral (X1 decision), so circuit-key-value counts a hanging record only when the final attempt runs in full. Risks: with very small headroom, jitter can still let the workflow timer fire first (neutral, names the ownership step, not counted by the key-value breaker); jitter branch untested; residuals U-1, U-2, U-3, N5, F1, F2 remain.
