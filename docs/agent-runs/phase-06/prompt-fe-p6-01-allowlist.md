# Phase 06 work order FE-P6-01: prepare the `sbt erDecisionGroupingDev` allowlist entry (verbatim)

Received by the orchestrator on 2026-11-16, after the human's commit Y. The decisions in this text are the human's own words. The human records them in `human-gate.md`.

```text
Commit Y is complete. Read HEAD and delegate preparation of the exact
`sbt erDecisionGroupingDev` allowlist entry to factory-engineer. Scope it
to entity-resolution-engineer and the standalone dev-only command. Include
the agreed refusal probes and present the exact change for my ratification.
Keep the command inactive pending ratification and restart. Hold DE-P6-02
core changes until the pinned dev run is captured.
```

## Orchestrator check of HEAD (2026-11-16)

- `git rev-parse HEAD`: `7b72681f827b3cf19851aa7aef3c4d7603c5d15a` (commit Y, "Phase 6: pin dev run manifests to implementation commit"; parent 8c1bee0 = X).
- `git show --name-only` Y: the two dev run manifests plus `docs/agent-runs/phase-06/prompt-er-p6-08-manifest-pin.md`, `repair-06.md` and `review-report.md`. Nothing else in the dev task's code scope.
- `git status --porcelain`: empty before this file was written.
- Agreed probes (CR-P6-09 check 6): the command for entity-resolution-engineer only; refusals for test-engineer and service-engineer (no-allowlist-entry); `sbt clean erDecisionGroupingDev` and `sbt erDecisionGroupingDev check` refused. The optional "heldout"-token hardening is deferred by the human.
