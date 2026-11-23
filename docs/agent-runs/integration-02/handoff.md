# Integration round 02: handoff and acceptance criteria (orchestrator, 2026-11-23)

Records the plan only; approves nothing. Starting state: HEAD bdbf5df (integration-01 closeout), main == origin/main. Only AGENTS.md and skills/ are untracked; leave them untouched (UNASSIGNED, Codex guidance created at the human's request).

## Human order (verbatim, 2026-11-23)

```text
Integration round 01 is committed and pushed:
- Implementation: e5ed604
- Local closeout: bdbf5df
- main matches origin/main.
- Only AGENTS.md and skills/ remain untracked; leave them untouched.

Read docs/agent-runs/integration-01/human-gate.md and outstanding.md.

Proceed with the next local implementation work: SV-INT-03 launcher wiring and API-key source, followed by the launchers needed to demonstrate one complete local API/query flow. Follow the repository ownership and independent-review workflow.

Prioritize a reproducible runnable application before November 25 at 11 PM CST. Use local fixtures/stubs where necessary and label them clearly. Required production models and live AWS verification remain outstanding.

No AWS mutations, paid model calls, EMR launches, or hook changes. Run targeted checks during development; request a manual full check when the reviewed changes are ready.

First state the concrete acceptance criteria, then proceed with implementation. If this session needs replacing, save a persistent handoff and give me the complete fresh-session prompt.
```

## Inputs to read (narrowly)

- docs/agent-runs/integration-01/human-gate.md: accepted decisions, including `ProductionSettings.forLauncher` as the required launcher entry point.
- docs/agent-runs/integration-01/outstanding.md: "Remaining startup dependencies", open residuals, and the test-environment notes.
- docs/agent-runs/integration-01/agent-report.md, top two sections:
  - DE-INT-04: the SV-INT-03 inputs and the open questions;
  - SE-INT-04 phase B: the `StorageClients` interfaces.
- docs/agent-runs/integration-01/review-report.md: CR-INT-09 (-1 is the API-key timing and TTL ceiling, after SV-INT-03) and CR-INT-07-3 (the scaladoc).

## Acceptance criteria for round 02 (local scope)

**A. SV-INT-03, production launcher wiring (service-engineer, modules/services/\*\*)**
1. Each of the three service mains resolves configuration through `ProductionSettings.forLauncher(c, kind)`, never `forProcess`. A test proves that a config without the adapter sections is refused before any client is built.
2. The launcher builds one `StorageClients` bundle and maps `LauncherSettings` 1:1 into it:
   - `RetrySetting` → `dynamoDbFactory`;
   - `objectReader`, `graphReader`, `vectorReader` → `ProductionDependencies`.
   `vectorReader` serves both `vectors` and `vectorIndexes`. `close()` runs exactly once, on shutdown and on a failed start. Tests use stub seams only.
3. G4: each storage operation runs under the consuming process's read policy, bounded by `readTimeoutMillis`, including the rag workflow's reads in query-jobs. If rag code must change for this, route that change to rag-engineer, kept minimal.
4. CR-INT-07-3: the services scaladoc points to `StorageClients.dynamoDbFactory`.
5. Any production dependency that is still missing (`workflow`, meaning the real model and embedder) keeps failing closed and names itself. No fake is substituted on the production path.

**B. Secrets Manager `ApiKeyDigestSource` (service-engineer)**
1. Built from `ApiKeySourceSettings(secretId, client, cacheTtlMillis)`, with the same ambient-endpoint refusal as storage `AmbientEndpoints` when no override is given.
2. Secret JSON layout: a versioned document listing clients with clientId, keySha256Hex and scopes. It carries no raw keys. The layout is documented and validated strictly; malformed means fail closed.
3. Refresh policy (orchestrator recommendation; the human may overrule): serve the cached list within `cacheTtlMillis`. When a refresh fails after expiry, authentication fails closed with 503 and the stale list is not served.
4. The Secrets Manager call is bounded by the request deadline, and errors are value-free.
5. Tests use a stubbed client only.
6. If the `secretsmanager` SDK artifact is missing from the classpath, stop: return BLOCKED-ON build-engineer with the exact artifact, at the BOM version.

**C. Local demo profile (service-engineer), clearly labelled NON-PRODUCTION**
1. A separate local-demo composition and entry point. It is never selected by the production provider or by ServiceLoader. It uses:
   - in-memory stores seeded from committed local fixtures (the existing Phase 9 corpus and fixtures);
   - the existing NON-PRODUCTION template synthesizer and stand-in embedder;
   - a local API-key digest file or fixture.
2. On start it prints a NON-PRODUCTION banner. Every response, or a startup log line, identifies the profile.
3. A test over real loopback HTTP runs one complete flow:
   - `POST /v1/query`, which returns an evidence-backed answer;
   - `GET /v1/evidence/{id}` for a cited evidence ID;
   - `GET /v1/explain/{queryId}`;
   - one graph route;
   - plus an auth failure (401) and a replay under the same Idempotency-Key.

**D. Launchers (build-engineer, build.sbt and project/\*\*, after A to C)**
1. Staged launcher scripts, for example via sbt-native-packager, one per service main plus the local-demo entry point.
2. `META-INF/services` registration of the production `ServiceProvider`.
3. A documented command sequence (stage, then run) that a grader can replay locally with no AWS access.
4. No container images and no AWS calls. A new sbt alias that agents must run needs an allowlist row. Hook changes are forbidden this round, so the human runs it in the Terminal instead.

**E. Gates**
- Feature tests for every behaviour; the Phase 11 tests/** stay unchanged.
- One code-reviewer review for each implementation order (A to C together, and D).
- After both reviews, the human runs a manual `sbt check` in the Terminal (recovery option 1), and runs the local demo command sequence once.
- No AWS mutations, paid calls, EMR launches or hook changes.

**Still outstanding after round 02:**
- the production answer model and embedder (rag-engineer, plus a human decision on paid calls);
- runtime Terraform and the packaging and smoke scripts (infra-engineer);
- deployment;
- live AWS verification;
- experiments;
- Phase 17 documentation.

## Process notes for the fresh session

- Agent-run full `sbt check` is moved to the background at about 180 s and cannot be audit-attributed. Agents run focused checks (`sbt <project>/test`) only. The human runs the full check in the Terminal.
- Audit recovery: after any out-of-band change, the first audited Bash call is refused UNATTRIBUTED and resets the baseline. Re-run it once the human has reviewed the change.
- Only the 18 ratified agent types have tool authority. Explore, general-purpose and similar agents are refused by the guard.
- The orchestrator's Bash shapes are limited to the @GIT set and version checks. The orchestrator cannot run sbt.
- The aws-docs MCP server is down (`uvx` missing). Fetch public AWS documentation through the web-fetch agent when needed.
