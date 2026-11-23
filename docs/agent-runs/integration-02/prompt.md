# Integration round 02: work orders (verbatim)

## Human order, 2026-11-23

See handoff.md, "Human order (verbatim, 2026-11-23)". The text is identical and is not repeated here.

## SV-INT-03 work order (orchestrator to service-engineer, 2026-11-23)

```text
Work order SV-INT-03: production launcher wiring, Secrets Manager API-key source, NON-PRODUCTION local demo profile.
Owner and scope: service-engineer; modules/services/** only. Do not edit other modules, build.sbt, project/**, tests/**, hooks or docs.
Starting evidence: HEAD bdbf5df (integration-01 accepted for local development only). Read narrowly:
- docs/agent-runs/integration-02/handoff.md (acceptance criteria A, B and C are binding);
- docs/agent-runs/integration-01/outstanding.md ("Remaining startup dependencies");
- docs/agent-runs/integration-01/agent-report.md, the DE-INT-04 section (LauncherSettings, ApiKeySourceSettings, timing rules) and the SE-INT-04 phase B section (StorageClients).
Prerequisites met: ProductionSettings.forLauncher, StorageClients and the DynamoDB-backed durable state are committed. The human accepted forLauncher as the required launcher entry point.

Steps (in this order; report after each step that completes):
1. Criterion A (production wiring):
   - Wire the service mains through ProductionSettings.forLauncher. Build one StorageClients bundle; close it exactly once, on shutdown and on a failed start.
   - G4: apply the read policy and readTimeoutMillis to every storage read, including the rag workflow's reads in query-jobs. If that needs a change in modules/rag, stop that sub-item and report BLOCKED-ON rag-engineer with the minimal interface you need. Do not edit rag.
   - CR-INT-07-3: correct the scaladoc.
   - A missing workflow (production model and embedder) still fails closed and names itself.
2. Criterion C (local demo profile, highest priority for the deadline):
   - A separate NON-PRODUCTION composition and entry point, never reachable from the production provider.
   - In-memory stores seeded from committed fixtures; the existing NON-PRODUCTION template synthesizer and stand-in embedder; a local API-key digest fixture. Digests only; document the demo key, which is a non-secret value.
   - A NON-PRODUCTION banner, and the profile is identified in every response or in the startup log.
   - A loopback HTTP test of: POST /v1/query with an evidence-backed answer, then GET /v1/evidence/{id} for a cited ID, GET /v1/explain/{queryId}, one graph route, a 401, and an Idempotency-Key replay.
   - In your report, state the exact main class and arguments that build-engineer must stage.
3. Criterion B (Secrets Manager ApiKeyDigestSource):
   - Implement it as specified in handoff.md B1-B5, including fail-closed refresh: a failed refresh after the TTL returns 503 and never serves stale keys.
   - If the AWS SDK secretsmanager artifact is not on the services classpath, skip B entirely and report BLOCKED-ON build-engineer with the exact artifact coordinate (at the existing BOM version). Steps 1 and 2 still complete.

Done when: criteria A, C and (unless blocked) B in handoff.md hold. Every behaviour has a feature test that fails without it. Existing tests are unchanged unless you justify each change in the report. No fake reaches the production path.
Validation: focused checks only: `sbt services/test` (plus your own module tasks). Do NOT run a full `sbt check` (it is moved to the background and cannot be audit-attributed; the human runs it after review). Report the exact observed counts.
Limits: no AWS calls of any kind, no network apart from loopback, no paid model calls, no hook, marker or settings changes, one sbt build at a time.
Stop/report when: done, or blocked. Use the full report contract; under RISKS list what remains unverified locally versus live.
```

## SV-INT-03 outcome and re-issue (orchestrator, 2026-11-23, fresh session)

- The SV-INT-03 agent terminated on the monthly spending limit before writing anything. Observed at HEAD bdbf5df: `git diff --stat` empty, and `git ls-files --others --exclude-standard` lists only AGENTS.md, skills/ and this directory. No other session or agent was running (ListAgents). No partial report exists.
- Human decision (2026-11-23, fresh-session prompt): "Use the proposed 503 response when an expired API-key cache cannot refresh." This settles handoff.md B3.
- To limit loss if a run ends early again, SV-INT-03 is re-issued as two orders, with the deadline-critical one first: SV-INT-03a (criterion C) and then SV-INT-03b (criteria A and B). The acceptance criteria are unchanged.

## Human order, 2026-11-23 (deadline mode, verbatim)

```text
We are in deadline mode: November 25 at 11 PM CST.

Prioritize completing round 02 and demonstrating one reproducible local end-to-end query with retrievable cited evidence. Freeze optional features and unrelated refactoring.

Use targeted tests and batch independent reviews at coherent milestones. I will run the final full check manually. Avoid repeated unchanged builds, status audits, and hook-repair work.

Prepare minimal run/demo documentation alongside implementation. Keep every unmet assignment requirement explicit. No new AWS spending or paid model calls without approval.

Report progress through concrete acceptance criteria and demonstrated behavior. Once the local demo works, provide the commit steps and a scoped assessment of what deployment work can realistically follow.
```

Orchestrator sequencing under this order: SV-INT-03a (C) → SV-INT-03b (A, B) → one batched code-reviewer review of 03a+03b → build-engineer launchers (D), with documentation-engineer writing the minimal run/demo page (docs/demo.md) in parallel once the staged commands are known → one review of D plus the docs → human full check and demo replay. Service orders run one at a time: both touch modules/services, and the hooks do not support concurrent Bash in one session.

## SV-INT-03b work order (orchestrator to service-engineer, 2026-11-23)

Issued after SV-INT-03a reported COMPLETE (services/test 158/0/0). Text below.

```text
Work order SV-INT-03b: production launcher wiring (handoff.md criterion A) and the Secrets Manager ApiKeyDigestSource (criterion B).
Owner and scope: service-engineer; modules/services/** only. Do not edit other modules, build.sbt, project/**, tests/**, hooks, AGENTS.md, skills/ or docs. Deadline mode: no optional features, no unrelated refactoring.
Starting state: HEAD bdbf5df plus the uncommitted SV-INT-03a demo work (modules/services/src/main/scala/scholarchain/services/localdemo/**, the two local-demo JSON resources, LocalDemoSuite, and the extraHeaders parameter on HttpServerAdapter.start). Keep it intact; LocalDemoSuite must still pass unchanged.
Read narrowly: docs/agent-runs/integration-02/handoff.md (criteria A and B are binding); docs/agent-runs/integration-01/outstanding.md ("Remaining startup dependencies"); docs/agent-runs/integration-01/agent-report.md, only the DE-INT-04 section (LauncherSettings, ApiKeySourceSettings, timing rules) and the SE-INT-04 phase B section (StorageClients); docs/agent-runs/integration-01/review-report.md, only CR-INT-09-1 and CR-INT-07-3.

Steps, in this order:
1. Criterion A:
   - The three production service mains resolve configuration through ProductionSettings.forLauncher(c, kind), never forProcess. A test shows a config without the adapter sections is refused before any client is built.
   - Build one StorageClients bundle; map LauncherSettings 1:1 (RetrySetting -> dynamoDbFactory; objectReader, graphReader, vectorReader -> ProductionDependencies; vectorReader serves vectors and vectorIndexes). close() runs exactly once, on shutdown and on a failed start. Tests use stub seams only.
   - G4: every storage read runs under the consuming process's read policy, bounded by readTimeoutMillis, including the rag workflow's reads in query-jobs. If this needs a change in modules/rag, do not edit rag: finish the rest and report BLOCKED-ON rag-engineer for that sub-item with the minimal interface you need.
   - CR-INT-07-3: the services scaladoc points to StorageClients.dynamoDbFactory.
   - A missing workflow (production answer model and embedder) still fails closed and names itself. No fake on the production path.
   - Hardening flagged by SV-INT-03a: ProductionServiceProvider explicitly refuses any scholarchain.services.localdemo object, with a test.
2. Criterion B (human decision recorded: an expired cache whose refresh fails returns 503, never stale keys):
   - ApiKeyDigestSource backed by Secrets Manager, built from ApiKeySourceSettings(secretId, client, cacheTtlMillis), with the same ambient-endpoint refusal as storage AmbientEndpoints when no override is given.
   - A versioned secret JSON layout listing clients (clientId, keySha256Hex, scopes), no raw keys, documented in scaladoc, strictly validated; malformed means fail closed.
   - Serve the cached list within cacheTtlMillis; when a refresh after expiry fails, authentication fails closed with 503 and the stale list is not served. The Secrets Manager call is bounded by the request deadline, and errors are value-free (no secret bytes, no key material in messages or logs).
   - Tests use a stubbed client only.
   - If the AWS SDK secretsmanager artifact is not on the services compile classpath, skip criterion B entirely and report BLOCKED-ON build-engineer with the exact coordinate at the existing BOM version. Step 1 still completes.

Done when: A and (unless blocked) B hold; every behaviour has a feature test that fails without it; existing tests unchanged unless justified one by one.
Validation: focused checks only, `sbt services/test` and `sbt services/scalafmtCheck`. Do NOT run a full `sbt check`. Avoid repeated unchanged builds. Report exact observed counts.
Limits: no AWS calls, no network apart from loopback, no paid model calls, no hook, marker or settings changes, one sbt build at a time.
Report: the full report contract. State the exact production main classes and how each refuses without AWS configuration (for build-engineer staging). Under RISKS, separate what is verified locally from what needs live AWS verification.
```

## SV-INT-03a work order (orchestrator to service-engineer, 2026-11-23)

```text
Work order SV-INT-03a: NON-PRODUCTION local demo profile (handoff.md criterion C).
Owner and scope: service-engineer; modules/services/** only. Do not edit other modules, build.sbt, project/**, tests/**, hooks, AGENTS.md, skills/ or docs.
Context: a previous SV-INT-03 run terminated (spending limit) and wrote nothing; you start clean from HEAD bdbf5df. Production wiring (criterion A) and the Secrets Manager source (criterion B) are a separate later order (SV-INT-03b); do not start them here, and do not change the production mains or the production ServiceProvider.
Read narrowly: docs/agent-runs/integration-02/handoff.md (criterion C is binding); then only the services sources you need, plus the existing NON-PRODUCTION template synthesizer, stand-in embedder and Phase 9 corpus/fixtures in modules/rag (read only).

Deliver:
1. A separate NON-PRODUCTION local-demo composition and entry point (one main class) in modules/services. It must never be selected by the production provider or by ServiceLoader; add a test proving the production provider path cannot reach it.
2. In-memory stores seeded from committed local fixtures (the existing Phase 9 corpus and fixtures, read from the classpath or a committed path; no network, no AWS SDK client constructed); the existing NON-PRODUCTION template synthesizer and stand-in embedder; a local API-key digest fixture (SHA-256 digests only). The demo key is a documented non-secret value; state it in the report and in a scaladoc.
3. Bind to loopback only (127.0.0.1) by default; the port is an argument or setting with a documented default (ephemeral port allowed in tests).
4. On start, print a NON-PRODUCTION banner. Identify the profile in every response (for example a response header) or at minimum in the startup log line.
5. A test over real loopback HTTP that runs one complete flow: POST /v1/query returning an evidence-backed answer; GET /v1/evidence/{id} for a cited evidence ID from that answer; GET /v1/explain/{queryId}; one graph route; a 401 for a missing or wrong key; and a replay under the same Idempotency-Key returning the same result.
6. Clean shutdown on SIGTERM/Ctrl-C (shutdown hook), so a grader's run exits cleanly.

Done when: items 1-6 hold, every behaviour has a feature test that fails without it, existing tests are unchanged (justify any change), and no fake reaches the production path.
Validation: focused checks only, `sbt services/test` (plus your own module tasks such as services/compile). Do NOT run a full `sbt check`; the human runs it. Report exact observed counts.
Limits: no AWS calls, no network apart from loopback, no paid model calls, no hook, marker or settings changes, one sbt build at a time.
Report: the full report contract. In addition, state exactly for build-engineer (SV-INT-03a): the fully qualified main class, its arguments and defaults, the required runtime classpath (which projects), and a ready-to-run curl sequence (demo key, POST /v1/query body, then evidence, explain and graph calls) that a grader can replay against the running demo. Under RISKS, list what is demo-only versus what production still needs.
```
