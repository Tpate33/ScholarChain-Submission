# Phase 06 work order BE-P6-03 repair 01 (sparkIT), then FE-P6-04 (verbatim)

Received by the orchestrator on 2026-11-16, after CR-P6-16 (REQUEST_CHANGES). The decisions in this text are the human's own words. The human records them in `human-gate.md`.

```text
Approve one build-engineer repair for sparkIT. Address F2–F5, reword F6
to say sbt clean can remove attempt folders, and carry forward the two
small notes. Preserve the existing held-out protections.

Harden the pass rule. Require evidence that every expected integration
suite actually ran and that no tests were skipped, ignored, or failed.
Strip MUNIT_FLAKY_OK from the child environment. Reject archive symlinks
without following them, using synthetic fixtures for regression coverage.

After the edits, run one foreground sbt check with the 600-second limit
and capture its final result for F1. Request one focused review of the
repair. If approved, have factory-engineer prepare the exact standalone
sbt sparkIT activation for my ratification. Keep the command inactive
until ratification and restart.

Leave spark-it/attempt-* cleanable. Record the integration evidence in
the Phase 6 report after the real run. Keep routine sbt check independent
of the dev archive, and preserve Phase 5 acceptance and evidence.
```

## Recorded decisions (orchestrator, 2026-11-16)

- Carried forward (build-engineer): CR-P6-16 nits — attempt-number overflow at `attempt-2147483647`; `LocalRuns.namesHeldout` does not match `held.out` (pre-existing, shared).
- `spark-it/attempt-*` stays cleanable by `sbt clean`; integration evidence is copied into the Phase 6 evidence after the real run.
