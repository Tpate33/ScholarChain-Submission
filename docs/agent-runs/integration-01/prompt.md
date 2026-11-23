# Production integration, round 01: work order (verbatim, human, 2026-11-22)

Orchestrator notes:
- This directory holds the production-integration follow-up that the human paused for Phase 11. It is not a numbered course phase. The human may rename it at closeout.
- Starting state: HEAD 2ff0e33, working tree clean. The orchestrator's first Bash call was refused UNATTRIBUTED, listing the three human-owned acceptance files committed by the human: docs/agent-runs/phase-10/human-gate.md, phase-11/commit.txt and phase-11/human-gate.md. That refusal reset the baseline.
- Per the human, the Phase 10 and Phase 11 human-gate.md files are saved drafts, not completed acceptance. They are left as they are.

```text
Phase 11 implementation and acceptance-record drafts are committed and pushed. HEAD is 2ff0e33, and the working tree is clean.

I am deferring further acceptance-record editing. Leave those files as they are; do not describe the drafts as completed acceptance.

Resume the paused production integration work. Start with the storage adapters and shared durable ownership, job, and idempotency stores, then connect the production ServiceProvider, configuration, and service launchers.

Before enabling durable QuerySession storage, fix compatibility with old v2 audit records, as recorded in CR-P11-02-1.

Proceed with local implementation and meaningful tests. Preserve the Phase 11 adversarial tests. Run only one sbt build at a time and avoid concurrent file edits during the final check. Report actual test results and clearly identify anything still unverified against AWS.

If an unresolved design decision affects correctness, explain the specific decision and your recommendation. Otherwise continue through the authorized local work.

Do not make live AWS or paid model calls, create resources, deploy, commit, or push. AWS account access and the remaining budget still need verification.
```

## Repair order after CR-INT-01 (verbatim, human, 2026-11-22)

Orchestrator state when received:
- SE-INT-01 was complete. Its backgrounded sbt check: exit 0, suites=215, passed=2558, skipped=1.
- CR-INT-01 returned REQUEST_CHANGES (one major).
- The Stop hook was refusing completion because of the marker write.

After this message the orchestrator ran `git status --porcelain` twice:
- The first call was refused UNATTRIBUTED for `.claude/.last-test-run` (GENERATED), `AGENTS.md` (UNASSIGNED) and `skills/scholarchain-work-orders/SKILL.md` (UNASSIGNED). The baseline was reset.
- The second call listed only the SE-INT-01 storage files, `docs/agent-runs/integration-01/`, `AGENTS.md` and `skills/`.

Per the human, AGENTS.md and skills/ were created by Codex at the human's request and are to be preserved. No agent may edit them: they are UNASSIGNED in the ownership map.

```text
Continue with one bounded storage repair, followed by review. Do not start configuration or service wiring yet.

The marker was inspected through Codex and records HEAD 2ff0e33, 215 suites, 2558 passed, 0 failed, 1 skipped. AGENTS.md and skills/ were created by Codex at my request; preserve them. Follow the existing audit recovery procedure without editing the marker or hooks.

Owner: storage-engineer.
Scope: modules/storage/** and the orchestrator's integration-01 reports.

Use CR-INT-01 as the repair checklist:
1. Fix ambiguous-write recovery for begin and complete. Preserve one attempt identity across retries of the same logical operation, while keeping independent callers distinct. Add tests that apply a write and then lose its response; ensure retries cannot launch concurrent work under the same ticket.
2. Bound encoded payload size, rather than only raw JSON size.
3. Prevent the runtime read facade from accessing service-state tables.
4. Add a configurable clock-skew allowance and overflow-safe validation to the in-flight expiry bound. Document the remaining duplicate-execution risk and required clock assumptions.
5. Correct retry classification for connection-reset failures, with focused tests.
Address findings 6–7 where directly related; report any deferred item explicitly.

I approve implementing bounded in-flight expiry with conditional attempt-token protection and a clock-skew allowance for local validation. This is not approval for production deployment or paid calls.

Run focused tests while repairing, then one final sbt check with no concurrent build or file edits. Preserve Phase 11 adversarial tests. Report observed results.

Request one read-only review of the repaired diff against these findings. Stop after reporting that verdict; do not expand into unrelated cleanup.

No AWS calls, paid model calls, deployment, commits, pushes, or acceptance-record edits.
```

## SV-INT-01 order: old audit-record compatibility (verbatim, human, 2026-11-22)

Orchestrator state when received:
- SE-INT-02 complete; CR-INT-02 APPROVE, with CR-INT-02-1 to -4 open.
- Stop hook refusing on SE-INT-02's backgrounded marker write.

After this message the orchestrator ran `git status --porcelain` twice:
- The first call was refused UNATTRIBUTED for `.claude/.last-test-run` only, and the baseline was reset.
- The second call listed the SE-INT-01/02 storage files, `docs/agent-runs/integration-01/`, `AGENTS.md` and `skills/`.

**Open, by human order: CR-INT-02-1, -2 and -3.** These stay open for the configuration and service-wiring step, and must be closed before durable stores are enabled. They are not accepted and not resolved.

```text
Continue through the existing audit recovery procedure. Codex inspected the updated marker: HEAD 2ff0e33, 216 suites, 2663 passed, 0 failed, 1 skipped. Do not edit the marker or hooks.

Next bounded task: SV-INT-01 — old audit-record compatibility.
Owner: service-engineer; route any required rag-owned codec changes to rag-engineer.

Read the relevant audit codecs, explain handler, and CR-P11-02-1. Avoid rereading unrelated phase reports.

Implement:
1. Explain accepts valid historical v2 audit records and current v3 records.
2. Preserve integrity verification using the appropriate version's rules. Do not bypass integrity checks to accept v2.
3. New records continue to use v3.
4. Unknown versions and genuinely corrupt records return typed errors.
5. Add regression tests using a frozen v2 record, a v3 record, a corrupt record, and an unknown version.

Run focused tests, then the required final sbt check with one build and no concurrent edits. Preserve existing adversarial tests. Report actual results and stop after this task.

Keep CR-INT-02-1 through -3 explicitly open for the configuration/service-wiring step, before durable stores are enabled. They require retry/setup timing bounds, accurate expiry documentation, and job/ownership TTL margins. Do not mark them accepted or resolved.

No AWS calls, paid calls, deployment, commits, pushes, acceptance-record edits, or unrelated cleanup.
```

## DE-INT-01 order: production configuration contracts (verbatim, human, 2026-11-22)

Orchestrator state when received:
- CR-INT-03 APPROVE for SV-INT-01; CR-INT-03-1 (minor) and CR-INT-03-2 (nit) are kept for the next services change.
- Last full check: suites=217, passed=2673, skipped=1.
- One `git status --porcelain` was run; it was not refused. It listed the uncommitted SE-INT-01/02 and SV-INT-01 work, `docs/agent-runs/integration-01/`, `AGENTS.md` and `skills/`.

```text
Next work order: DE-INT-01 — production configuration contracts.
Owner: domain-engineer.
Change scope: modules/core/** only, plus the orchestrator's integration-01 report.

Starting state:
- Storage repair approved by CR-INT-02.
- Audit compatibility approved by CR-INT-03.
- Last reported full check: 2673 passed, 0 failed, 1 skipped, 217 suites.
- Preserve existing uncommitted work.

Read:
- modules/core/src/main/scala/scholarchain/core/config/ScholarChainConfig.scala
- modules/core/src/main/scala/scholarchain/core/config/ConfigLoader.scala
- modules/core/src/test/scala/scholarchain/core/config/ConfigLoaderSuite.scala
- Configuration routing in phase-10/outstanding.md and integration-01/agent-report.md.
- Relevant storage settings and service configuration definitions, including the CR-INT-02 timing findings. Read narrow supporting files as needed.

Implement:
1. Typed DynamoDB connection, table-name, pagination, timeout, and batch-retry configuration matching the implemented adapter.
2. Durable-state retention, in-flight expiry, clock-skew, and retry/setup timing configuration.
3. Per-service configuration for query/jobs, evidence/explain, and graph, including server, pool, request/workflow timeout, shutdown, and drain settings.
4. Validate individual bounds and cross-field relationships using overflow-safe arithmetic. Check evidenceScanLimit against the adapter query limit.
5. Preserve existing configuration behavior; add tests for valid inputs, invalid bounds, and compatibility. Use the existing override mechanism and keep errors free of secret values.

Do not invent fixed timing budgets: derive them from the actual retry/pool/workflow settings, or expose an explicit bound whose enforcement is a service-wiring prerequisite.

CR-INT-02-1 through -3 remain open until storage and service code enforce the corresponding bounds. Configuration alone does not close them.
Keep CR-INT-03's test improvements for the next services change.

Run permitted focused checks, then one final sbt check with no concurrent build or edits. Report actual results, new keys, validation rules, and remaining wiring obligations. Stop after this implementation report.

No AWS calls, paid calls, deployment, commits, pushes, acceptance-record edits, or changes outside the assigned scope.
```

## DE-INT-02 order: repair CR-INT-04 core findings (verbatim, human, 2026-11-22)

Orchestrator state when received: CR-INT-04 APPROVE for DE-INT-01, with findings -1 to -6 minor and -7 to -9 nits.

Carried forward by human order:
- CR-INT-02-1, -2 and -3 OPEN until runtime enforcement.
- CR-INT-03-1 and -2, and the service portion of CR-INT-04-6 (overflow-safe `readBounded`), go into the next services work order.
- Deferred, not in scope: CR-INT-04-5 (exhaustive boundary audit) and -7 to -9.

```text
Next bounded task: repair the core configuration findings from CR-INT-04.
Owner: domain-engineer.
Change scope: modules/core/** and the orchestrator's integration-01 report.

Fix:
1. CR-INT-04-1: job TTL must satisfy the storage constructor's requirement against max(sync, async) workflow timeouts.
2. CR-INT-04-2: include the bounded complete-write duration in the in-flight timing calculation. Record that service wiring must also bound complete and abandon.
3. CR-INT-04-3: evidence-explain should require only the query-jobs timing inputs it actually uses, rather than all 43 settings.
4. CR-INT-04-4: correct X-4/X-5 for batch chunks, query pagination, and the adapter's actual capped-backoff formula.
5. CR-INT-04-6, core portion: reject server body limits that overflow the existing reader and validate their relationship to the application body limit. Leave the service-reader implementation fix for service-engineer.

Add focused boundary tests for these corrections, including valid and invalid sides. Preserve existing configuration compatibility. Do not expand into an exhaustive boundary audit or deferred nits 7–9.

Run permitted focused checks, then one final sbt check with no concurrent build or edits. Request one read-only review limited to these repaired findings. Report actual results and the verdict, then stop.

Keep CR-INT-02-1 through -3 open until runtime enforcement is implemented. Carry CR-INT-03's test improvements and the service portion of CR-INT-04-6 into the next services work order.

No AWS calls, paid calls, deployment, commits, pushes, acceptance-record edits, or unrelated changes.
```

## DE-INT-03 order: fix CR-INT-05-1 (verbatim, human, 2026-11-22)

Orchestrator state when received:
- CR-INT-05 APPROVE for DE-INT-02. CR-INT-05-1 is minor; CR-INT-05-2 is a nit.
- Audit recovery: one `git status --porcelain` was refused UNATTRIBUTED for `.claude/.last-test-run` only, and the baseline was reset.

Carried forward by human order:
- This correction is to be included in the next required integration review before anything relies on it.
- CR-INT-05-2 and the deferred findings are left alone.

```text
Continue through the existing audit recovery procedure. I checked the marker and working-tree listing against the DE-INT-02 report. Do not edit the marker or hooks.

Next bounded task: fix CR-INT-05-1.
Owner: domain-engineer.
Change scope: modules/core/** and the integration-01 report.

Correct the X-4 timing calculation so it bounds the complete ownership-record operation, including conditional PutItem, conflict recovery reads, and the fallback read when ALL_OLD is unavailable. Read the actual storage call paths rather than assuming one SDK call per operation.

Ensure the calculation remains consistent with the planned service-level deadline around each complete store operation. Document that wiring obligation explicitly.

Add regression tests for the reviewer's failure case and valid/invalid boundaries. Preserve existing configuration compatibility. Leave unrelated deferred findings and citation typos alone.

Run focused checks, then the required final sbt check with no concurrent edits or second build. Report actual results and the exact corrected bound, then stop.

Do not launch another broad review. Include this correction in the next required integration review before relying on it.

No AWS calls, paid calls, deployment, commits, pushes, or acceptance-record edits.
```

## SV-INT-02 order: connect production configuration to durable service-state operations (verbatim, human, 2026-11-22)

Orchestrator state when received:
- DE-INT-03 complete: X-4[query-jobs] = 3·apiCall ≤ Q.readTimeout; not yet reviewed.
- Audit recovery: one `git status --porcelain` refused UNATTRIBUTED for `.claude/.last-test-run` only; baseline reset.

Orchestrator plan, one build at a time:
1. SE-INT-03 (storage-engineer): the storage corrections the order requires before durable operation is enabled.
   - CR-INT-02-2: correct the residual and clock-assumption wording.
   - CR-INT-02-3: skew allowance and round-up on job, ownership and completed rows.
   - CR-INT-02-1: storage side.
2. SV-INT-02 (service-engineer): services wiring.
3. One bounded review of the integration diff, including DE-INT-03.

```text
Continue through the existing audit recovery procedure. Codex inspected the DE-INT-03 report and marker: HEAD 2ff0e33, 218 suites, 2697 passed, 0 failed, 1 skipped. Do not edit markers or hooks.

Next work order: SV-INT-02 — connect production configuration to durable service-state operations.

Primary owner: service-engineer, modules/services/**.
Route necessary storage changes to storage-engineer, modules/storage/**, sequentially. Preserve existing work and ownership boundaries.

Use ProductionSettings.forProcess, the implemented durable stores, and the existing service runtime. Read the relevant interfaces and integration reports; do not reopen unrelated phases.

Deliver:
1. Map validated configuration into ServiceConfig and server settings.
2. Add service wrappers for durable ownership, jobs, and idempotency. Bound entire store operations, including begin, complete, and abandon, with the configured deadlines.
3. Preserve one attempt identity per logical operation; independent requests remain distinct. Handle timed-out attempts settling before retry without escaping the overall request bound.
4. Enforce the CR-INT-02 timing obligations. Route the required storage expiry/rounding corrections and residual wording to storage-engineer before enabling durable operation. Do not claim configuration alone closes them.
5. Preserve admission control and client isolation when using durable jobs.
6. Address the service body-reader overflow from CR-INT-04-6 and the direct audit tests from CR-INT-03 while modifying services.
7. Provide a composition path exercised with injected stub clients. If other production adapters are missing, keep explicit injection points and report them; never silently substitute fakes for production dependencies.

Validate end-to-end durable service operations, replay, ambiguous-write recovery, independent-request concurrency, client isolation, and deadline behavior using local stubs. Preserve Phase 11 adversarial tests.

Run required owner checks sequentially, one build at a time, with no concurrent edits during final checks. Review the bounded integration diff, including DE-INT-03's corrected X-4 formula. Report actual results, finding closures, and remaining production dependencies, then stop.

No live AWS/model calls, deployment, commits, pushes, launcher/package changes, or acceptance-record edits.
```

## SE-INT-04 order: production storage dependencies (verbatim, human, 2026-11-22)

**Orchestrator state when received.**
- SV-INT-02 is complete, and CR-INT-06 returned APPROVE.
- The CR-INT-06 residuals (-1 to -6) and the A1-W residual are recorded for deployment preparation. The human has NOT accepted them.
- Audit recovery: one `git status --porcelain` ran and was not refused (the baseline had already been reset by service-engineer's refused call). It listed only the work of earlier orders, plus `AGENTS.md` and `skills/`.

**Tooling note.**
- storage-engineer's documentation tool (aws-docs MCP) failed to connect this session because `uvx` is missing.
- Public documentation must therefore be read through the orchestrator's web-fetch agent.
- storage-engineer lists the exact documentation questions it needs answered; the orchestrator fetches the answers and routes them back.

```text
Continue toward a runnable production system.

Codex checked the marker: HEAD 2ff0e33, 221 suites, 2742 passed, 0 failed, 1 skipped. Follow the existing audit recovery procedure without editing markers or hooks.

Next work order: production storage dependencies.
Owner: storage-engineer.
Change scope: modules/storage/** and the orchestrator's integration-01 report.

Implement the missing storage dependencies required by ProductionServiceProvider:
1. DynamoDB SDK client factory using the standard credentials provider chain and validated settings.
2. Client lifecycle/close support so the owning composition can release resources.
3. S3 object reader, Neptune graph reader, and S3 Vectors reader behind the existing ports.

Inspect the existing ports and the service composition's injection contracts first. Implement only the operations those contracts require. Preserve module boundaries and external configuration; do not hardcode resource names or credentials.

Use official public documentation to verify SDK behavior instead of relying on memory. Public documentation browsing is allowed; live AWS API calls remain prohibited.

Test through injected stub clients, including successful reads, missing data, errors, pagination/bounds, and lifecycle behavior. Tests must not require credentials or network access.

If a required dependency or configuration field is missing outside storage, report its exact owner and minimum required change. Do not silently substitute a fake or expand scope.

Run focused checks and the required final sbt check, one build at a time. Read-only monitoring is allowed; no concurrent edits or second build. Use normal bounded waits rather than rapid output polling.

Request one review limited to these adapters. Report actual results, the concrete injection interfaces, and remaining startup dependencies, then stop.

Keep CR-INT-06 residuals recorded for deployment preparation; do not claim I accepted them. No unrelated cleanup, AWS/model calls, deployment, commits, pushes, or acceptance-record edits.
```

## SE-INT-05 order: repair CR-INT-07 blockers (verbatim, human, 2026-11-22)

Orchestrator state when received:
- CR-INT-07 returned REQUEST_CHANGES, with CR-INT-07-1 and CR-INT-07-2 major. Findings -3 to -6 are deferred by this order.
- Audit recovery: one `git status --porcelain` was refused UNATTRIBUTED for `.claude/.last-test-run` only, and the baseline was reset. The human had reviewed that marker.

```text
I reviewed the SE-INT-04 marker: 225 suites, 2794 passed,
0 failed, 1 skipped. Continue through the existing audit
recovery procedure. Do not edit the marker or weaken hooks.

Prioritize completion. Route one bounded repair to
storage-engineer, within modules/storage/**:

1. Fix CR-INT-07-1: support the existing researcherIds
   list-field In filter using the documented equivalent
   $or of $eq expressions. Preserve port semantics and
   enforce the documented filter constraints. Test the
   actual filter shape emitted by SemanticContextAgent.

2. Fix CR-INT-07-2: when no explicit endpoint override
   exists, reject ambient AWS_ENDPOINT_URL* environment
   variables and aws.endpointUrl* system properties before
   building affected clients. Errors must name settings
   without exposing values. Use injectable settings for
   offline tests; document the profile endpoint residual.

Defer CR-INT-07-3 through -6 unless directly necessary
for these repairs. Preserve existing adversarial tests.

Run focused regression tests, then one final sbt check,
with one build at a time and stable inputs. Arrange one
focused code-reviewer re-review of these two blockers.
Do not reopen unrelated findings or add review rounds
for optional nits.

No live AWS/model calls, deployment, commits or pushes.
Report actual validation, whether both blockers closed,
and the remaining production wiring dependencies.
```

## Status request after CR-INT-08 (verbatim, human, 2026-11-22)

Orchestrator state when received:
- CR-INT-08 returned APPROVE; CR-INT-07-1 and -2 are closed.
- Audit recovery: one `git status --porcelain` was refused UNATTRIBUTED for `.claude/.last-test-run` only, and the baseline was reset.
- A second `git status --porcelain` listed only the work of earlier orders, plus `AGENTS.md` and `skills/`.

Orchestrator response: a dependency report and a recommendation only. No work order was issued.

```text
I reviewed the SE-INT-05 marker. Continue through the
existing audit recovery procedure without editing the
marker or hooks.

The blocker repair and focused review are complete.
Prioritize finishing the production startup path.
Report the exact remaining dependencies and recommend
the next bounded implementation order. Keep optional
findings deferred. No live AWS/model calls, deployment,
commits or pushes.
```

## DE-INT-04 order: core config contracts for the storage adapters (verbatim, human, 2026-11-22)

Orchestrator state when received: the dependency report and recommendation had just been given. No build was running. The audit baseline had already been reset at the previous recovery.

```text
Proceed with DE-INT-04, owned by domain-engineer within
modules/core/**. Prioritize completing production startup.

Add the configuration contracts needed by the implemented
storage adapters:
- S3, Neptune and S3 Vectors settings.
- Typed Neptune endpoint validation.
- Explicit DynamoDB retry-attempt settings.
- Constructor-compatible bounds and process-specific
  required settings, following existing config conventions.

Derive timing rules from the actual adapter call paths and
SDK timeout semantics. Do not blindly multiply retry attempts
by apiCallTimeout if that timeout already covers SDK retries.
Separate whole-call bounds, sequential calls and streaming
body limitations. Preserve unresolved limitations explicitly.

Use overflow-safe arithmetic and meaningful boundary tests.
Preserve existing configuration compatibility and Phase 11
adversarial tests. Do not reopen deferred optional findings.

Run focused tests, then one final sbt check with stable inputs
and one build at a time. Obtain one focused owner review.
Keep reports concise.

No live AWS/model calls, deployment, commits or pushes.
Do not edit markers, hooks or human acceptance records.

Finish by reporting actual validation and the precise settings
and interfaces SV-INT-03 needs for launcher wiring and the
Secrets Manager API-key source.
```

## Planned sequence (orchestrator; one build at a time)
1. storage-engineer SE-INT-01: DynamoDB KeyValueStore adapter (stubbed client) plus durable QueryOwnership, QueryJob and IdempotencyKey stores (SE-P11-01 design).
2. service-engineer SV-INT-01: CR-P11-02-1, so that explain accepts superseded v2 audit rows. This comes before any durable QuerySession wiring.
3. domain-engineer DE-INT-01: core `storage.dynamodb.*`, `storage.*` TTL and bound keys, and `services.<kind>.*` keys (routing items from SE-P11-01 and phase-10/outstanding.md).
4. service-engineer SV-INT-02: production ServiceProvider; wrappers around the durable stores; config wiring; StoreAccess.concurrent.
5. build-engineer BE-INT-01: one launcher per service main and META-INF/services registration.
6. storage-engineer: S3, S3 Vectors and Neptune read adapters (stubbed clients). Order relative to 4 and 5 to be decided by dependency.
7. test-engineer: adversarial and cross-module tests for the new adapters (Phase 11 categories 15-18 against stubbed SDK clients). code-reviewer reviews after each implementation step, or after a bounded group of steps.
