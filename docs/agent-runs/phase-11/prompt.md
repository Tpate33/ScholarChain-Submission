# Phase 11 work order (verbatim, human, 2026-11-22)

Orchestrator note: this phase directory holds the production storage integration follow-up together with the Phase 11 adversarial-test mapping the order requires. Evidence directory naming is the orchestrator's choice; the human may rename it at closeout.

```text
ScholarChain production integration follow-up.

Phase 10 is accepted for LOCAL scope and pushed:
implementation dcdd534, closeout a49317a. Working tree was clean
and HEAD matched origin/main.

Follow CLAUDE.md and docs/agents.md. Read Phase 10 human-gate.md
and outstanding.md before starting. Preserve the original course
requirements.

Our next milestone is real storage/model adapters, configuration
and packaging by Sunday night, supporting the first deployed
evidence-backed query Monday Nov 23 by 9 PM CST. Submission target
is Wednesday Nov 25 at 8 PM; actual deadline is 11 PM CST.

Begin the outstanding production storage integration through the
appropriate owners: thread-safe real AWS adapters and durable
query ownership, jobs and idempotency. Reuse existing ports.
Identify required configuration changes and route them to their
owners. Keep implementation and tests local with stubbed clients.

Do not silently skip Phase 11: map existing adversarial coverage
and add relevant tests for the integration as it is implemented.
Reassess Phase 9 R1 before connecting a real answer model.

AWS identity, access and remaining budget are still being checked.
No live AWS calls, paid model calls, deployment, resource changes,
publication, history rewriting, commits or pushes.

Run builds sequentially. Do not repeat the full submission plan.
Start the next bounded work order, then report tests, review
findings, remaining prerequisites and human closeout steps.
```

## Sequencing correction (verbatim, human, 2026-11-22)

Orchestrator state when received: SE-P11-01 (storage-engineer, DynamoDB adapter and durable stores) running; RG-P11-R1 (rag-engineer, read-only R1 reassessment) running.

```text
Correction to sequencing: preserve any work already completed or
running. Do not start another concurrent build.

Our immediate priority is the bounded Phase 11 adversarial review
of the accepted local Phase 10 implementation. Follow the original
Phase 11 requirements in the repository.

Map required categories to existing tests, add meaningful missing
coverage, and route critical/major fixes to their owners. Separate
checks verifiable locally now from those requiring production
adapters, and carry the latter into integration explicitly.

If an adapter work order is already running, let it reach a safe
checkpoint and report its status before switching focus.

Production integration follows this local review. Keep all previous
restrictions on live calls, deployment, commits and pushes.
```

## Phase 11 test-engineer order (verbatim, human, 2026-11-22)

Orchestrator state when received: SE-P11-01 asked to reach a safe checkpoint and report; RG-P11-R1 running (read-only); TE-P11-01 (read-only coverage map) running. The category list was forwarded to TE-P11-01 as authoritative.

```text
Use the test-engineer agent on the complete ScholarChain repository produced through Phase 10.

Add adversarial tests without modifying production code.

Required categories:
- Malformed and truncated DBLP XML.
- Unicode normalization and confusable characters.
- Duplicate and conflicting DOI records.
- Common author names and reordered author lists.
- Very large blocking groups.
- Coauthor edges with unresolved or conflicting researcher endpoints.
- Empty and disconnected graphs.
- Distinct-degree, component, shortest-path, bridge, and k-core known-graph cases.
- Large-team pair expansion and common-name researcher-resolution cases.
- Missing topic, institution, abstract, or researcher-profile metadata.
- Duplicate vector keys and stale model versions.
- Prompt injection in title, abstract, and full-text chunks.
- LLM malformed JSON and fabricated identifiers.
- Unsupported answer claims.
- Neptune timeout.
- DynamoDB conditional-write conflict.
- S3 Vectors throttling or transient failure.
- Replayed workflow and idempotency.
- API authentication, authorization, malformed bodies, and timeouts.
- Logging redaction.
- Configuration omissions.

Run all tests that do not require live AWS. Run bounded AWS integration tests only under the approved profile.
Leave any production defect exposed by a failing test. Report it to the owning agent instead of changing production code.
Report exact test counts, failures, coverage by requirement, and residual risks.
```

## Priority confirmation (verbatim, human, 2026-11-22)

Orchestrator state when received: RG-P11-R1 complete; SE-P11-01 waiting on its own background `sbt check` before reporting; TE-P11-01 (read-only coverage map) running. No prompt text followed this message; the orchestrator applies the Phase 11 test-engineer order above as "the original prompt".

```text
Prioritize Phase 11 now over the production-integration request I
sent earlier. Preserve any existing work and let an active build
finish; do not start concurrent builds.

Apply the original prompt below. No live AWS tests are authorized
yet—use local tests and stubbed clients only. No commits or pushes.
```

## Step 2 instruction (verbatim, human, 2026-11-22)

Orchestrator state when received: TE-P11-01 complete (coverage map archived); SE-P11-01 had reported only "waiting on its own background work"; no storage files in the working tree.

```text
Collect storage-engineer's current shell result and checkpoint
report. Then proceed with Phase 11 step 2 before launching more
integration work.

Keep test-engineer's changes test-only. Preserve any defect-exposing
failures and report them to the owning agents; do not weaken tests
to get a pass. After the results, proceed to the prescribed review
and owner repair loop.

No live AWS/model calls, commits or pushes.
```

## Marker review and step 2 go-ahead (verbatim, human, 2026-11-22)

Orchestrator state when received: Stop hook refusing completion (marker changed by SE-P11-01's backgrounded sbt check). After this message the orchestrator ran `git status --porcelain` once (refused UNATTRIBUTED for `.claude/.last-test-run` only; baseline reset), then again (printed `?? docs/agent-runs/phase-11/`).

```text
The marker and git status have been checked:
written_at_utc=2026-11-22T16:50:39.485893Z,
HEAD=a49317a, 194 suites, 2331 passed, 0 failed,
0 errored, 1 skipped. Git status shows only untracked
docs/agent-runs/phase-11/.

Continue with test-engineer step 2: add and run the planned
adversarial tests. Keep production-defect failures in place and
report them to their owners. Then proceed to the prescribed
read-only review and owner repair loop.

Do not resume storage integration yet. Do not rerun the unchanged
baseline solely for the audit pause. No marker/hook edits,
live AWS/model calls, commits or pushes.
```
