# Phase 10 repair 03 (SE-P10-04, service-engineer) for CR-P10-04

Records state only; approves nothing. Condensed from the agent's final report (STATUS: COMPLETE). Human decisions applied: m1 caller-only occurrences; fix m2, m3, n1-n5 now.

## Findings routed and fixes (tests in RepairP10cSuite and EntryPointsSuite unless noted)
- m1 (caller-only occurrences): any read client gets shared identity and provenance; `occurrences` lists only bundles whose query the caller owns (stored row's query ID checked via the ownership port, never returned); foreign bundles omitted entirely (no score, excerpt, bundle ID or count); occurrencesTruncated counts only the caller's occurrences; a caller owning none gets 200 with an empty list; 404 only for a nonexistent evidence ID; design-decision note rewritten. New config evidenceScanLimit > evidenceMaxBundles; an own occurrence beyond the scan window is not listed (recorded completeness residual, never a leak). Tests: B cannot see A's bundle IDs, scores or query IDs; owning nothing -> empty list, truncated false, identical identity fields; foreign bundles never set truncated; owner sees per-bundle scores; config rule. evidence.response.json notice updated. Further README.md:2500-2516 divergence for documentation-engineer.
- m2 (ownership error channel): record, owner and new batch ownedBy return Either[StoreError, ...] through the key-value read policy (pool, timeout, request deadline, bounded retries, key-value breaker); QueryJobs now has the key-value pool. Policy: ownership recorded before the workflow runs; if it can't be recorded the query is refused with the policy's 5xx, no workflow runs, Idempotency-Key released (retryable). Ownership outage on explain or evidence fails closed: 502 downstream-failure (failing store), 504 timeout (hanging), 503 (open circuit); never 404, never an unchecked list. Tests: outage on explain and evidence; record-failure policy (no workflow, record retried within bound, key released, retry succeeds).
- m3 (stuck workers): Calls counts calls running past their timeout; new readiness checks workflow-workers and job-workers fail when every pool thread is pinned. CircuitBreaker.notStarted() marks a half-open trial that never reached the dependency; `ready` false until a real outcome; readiness uses breaker.ready. Residual: an interrupt-ignoring workflow can still pin its thread, bounded by worker count. Tests: pinned single worker -> /readyz 503 with workflow-workers failing, recovers when the call ends; breaker unit test. readyz.response.json and readiness tests updated.
- n1: overflowCapacity has no default, validated; tests set it; validation test.
- n2: test retitled "three services in one JVM, each with its own router and loopback port"; also checks caller-only occurrences across services.
- n3: ServiceSettings.drainMillis: readiness and /v1 503 while the server still serves for drainMillis, then server stops within graceSeconds, then HTTP and service pools stop. Documented worst case drainMillis + 3 x graceSeconds. Test with a blocked in-flight graph exchange: /readyz 503 during drain; stop time between drainMillis and the bound + 2 s.
- n4: X1 test deterministic (TestClock decides truncation, latch makes timeout certain); control test shows a full-length timeout opens the breaker.
- n5: missing ownership port is a composition error for QueryJobs and EvidenceExplain; only All (and Graph, which does not use ownership) may default. Tested.
- U-1, U-2, U-3: recorded as residuals on HttpServerAdapter with U2 (not chased, per order).

## Follow-ups for other owners (not done)
- storage-engineer: durable QueryOwnership adapter with batch ownedBy (e.g. DynamoDB BatchGetItem keyed by query ID), errors mapped to StoreError.
- infra-engineer: ownership table; drainMillis >= load-balancer health-check interval x unhealthy threshold.
- domain-engineer: services.* config keys for evidenceScanLimit, drainMillis, overflowCapacity in addition to those in repair-02.md.
- documentation-engineer: evidence-shape divergence from README.

## Files
Created: test/.../RepairP10cSuite.scala. Modified: main HttpServerAdapter, QueryService, ReadServices, Resilience, Router, ServiceConfig, ServiceMain, ServiceRuntime, Stores; test EntryPointsSuite, LifecycleSuite, OpsSuite, RepairP10bSuite, ServiceWorld; api-examples/evidence.response.json, readyz.response.json.

## Commands and rerun evidence
`sbt services/compile` (compile error, then success); `sbt services/test` (test-compile error, then 1 failure on a stale example notice, then 92/92 with clean services/scalafmtCheck); `sbt check` (bkr6qqk2d) exit 0 — see test-report.md CURRENT.

## Agent-stated assumptions and risks
Assumptions: ownership store failure -> 502, timeout -> 504, open circuit -> 503; ownership recorded at query creation (stricter than on completion). Risks: N5 serialized-lock residual; interrupt-ignoring workflow can pin its thread (bounded, now visible in readiness); own occurrence beyond evidenceScanLimit not listed; U2, U-1, U-2, U-3 remain.

Next gate: code-reviewer CR-P10-05.
