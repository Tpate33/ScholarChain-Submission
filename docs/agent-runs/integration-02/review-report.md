# Integration Round 02 independent review

Base: `bdbf5df02133a0cb5f7493fcd1fd08b98469db60`. Local checkpoint only.
Two independent reviewer contexts inspected supplied source/diff snapshots. Neither
reviewer authored the implementation, edited files or executed tests/commands.
The coordinator supplied exact source supplements because native Read/Grep tools
were unavailable. This is a bounded code review, not a whole-repository audit or
proof of live AWS operation. Final source hashes are in `reviewed-files.json`.

## Scope A: production wiring and lifecycle

Reviewer: `code_reviewer_services`, repository role `code-reviewer`.
Initial verdict: REQUEST_CHANGES. Final repair verdict: **APPROVE — local checkpoint**.

**CR-INT02-A-1, major, service-engineer — resolved.** A throwing workflow factory
escaped `ProductionComposition.compose`, bypassing workflow-pool cleanup and the
client/key-source release in `startReleasing`. The launcher guard previously only
protected creation of the factory option. The owner wrapped execution of
`factory(stores)` with `ProductionLauncher.guard("workflow")` and flattened the
result into the existing failure path. Exceptions now yield a value-free refusal;
existing cleanup releases the pool, clients and closeable key source.

The owner's regression performs a bounded read, throws a dummy private-message
exception, asserts the sanitized Left and close counters of one, and verifies later
store reads cannot reach the client. The pre-fix run failed; after repair, 14 focused
tests passed. The reviewer inspected this exact delta and observed-result evidence,
and reported **no verified major or critical finding remaining in scope A**.

Deferred unverified residual: `RunningService.stop` performs its positive drain wait
before entering the release finally. Interrupted-drain behavior was not exercised;
it is partly pre-existing and outside this narrow repair. Owner: service-engineer.
It is not represented as a verified passing shutdown scenario.

Final reviewer report fields:
- STATUS: COMPLETE; OWNER: code-reviewer; WORK ORDER: final repair review CR-INT02-A-1.
- FILES CREATED: none; FILES MODIFIED: none; COMMANDS RUN: none.
- TEST RESULTS: parent-observed pre-fix failure and 14 post-fix passes; no reviewer test run.
- VALIDATION RESULTS: exact repaired call, regression, cleanup path, Calls/Deadline/port supplements inspected.
- ASSUMPTIONS: supplied snapshots/logs match frozen working files.
- RISKS: missing production keys/models; live AWS unverified; interrupted-drain residual.
- MANUAL ACTION REQUIRED: human local acceptance and Git authorization after closeout gates.
- BLOCKED-ON OWNER: none; BLOCKED-ON EVIDENCE: none for code-review approval.
- NEXT GATE: one post-repair full gate, then human acceptance.
- VERDICT: APPROVE — scope A, local checkpoint only.

## Local application, build, scripts and offline codec preparation

Reviewer: `code_reviewer_local`, repository role `code-reviewer`.
Final verdicts: **APPROVE — local application** and **APPROVE — build/scripts**.
No verified major or critical finding in these scopes.

The reviewer inspected the real-data loader/profile, source/run pins, missing/changed
input refusal, provenance, synthetic profile isolation, separate optional reader
classpath, explicit non-production models/storage, launcher/smoke behavior and the
EMR codec argument patch. The patch retains existing Java/checkpoint/timeout controls;
its approval is not approval to deploy or evidence of a successful cloud run.

Deferred verified minor findings (owner infra-engineer; no optional repair this round):
1. `scripts/demo-smoke.py:54`–77 uses Python assertions. `python3 -O` or
   `PYTHONOPTIMIZE=1` removes those checks and can allow a misleading PASS. The
   recorded invocation used normal Python; actual API behavior was also tested.
2. `scripts/demo-smoke.py:17`–22 and 78–91 leaves an old PASS `summary.json` if a
   later replay into the same directory fails. The failed process still exits
   unsuccessfully. Interpret summary together with that run's exit/output; use a
   fresh output directory. The real-data smoke already clears stale summaries and
   uses explicit runtime checks.

Final reviewer report fields:
- STATUS: COMPLETE; OWNER: code-reviewer; WORK ORDER: Integration 02 local/build/scripts.
- FILES CREATED: none; FILES MODIFIED: none; COMMANDS RUN: none.
- TEST RESULTS: parent-reported archived 2,850-pass pre-repair gate and both replays; no reviewer tests.
- VALIDATION RESULTS: supplied snapshots/diffs and exact patches inspected; both scoped verdicts APPROVE.
- ASSUMPTIONS: snapshots match working files, bound by final inventory hashes.
- RISKS: in-memory storage, template/stand-in models, bounded dev sample, missing live production functionality; old cloud pins/plans cannot deploy the modified wrapper.
- MANUAL ACTION REQUIRED: explicit narrowed local acceptance, including B deferral and direct launcher replacing original D2 registration plan.
- BLOCKED-ON OWNER: none; BLOCKED-ON EVIDENCE: none in reviewed scope.
- NEXT GATE: separate production repair review, verification, then human acceptance.
- VERDICT: APPROVE — local application and build/scripts only.
- UNVERIFIED CONCERNS: AWS, model/embedding quality, corpus scale and deployed performance; no deployment-readiness verdict.

The final post-repair test gate is recorded separately in `test-report.md`. Human
acceptance remains pending; review approval does not sign it or authorize Git/AWS.
