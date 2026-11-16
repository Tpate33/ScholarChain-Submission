# Phase 06 work orders: CR-P6-09 repairs (step 1) (verbatim)

Received by the orchestrator on 2026-11-16, after CR-P6-09 (REQUEST_CHANGES). The acceptances and approvals in this text are the human's own words. The human records them in `human-gate.md`.

```text
Accept ER-P6-05 repair 01. Proceed with step 1, one owner at a time.

First, have entity-resolution-engineer fix M2 and the CR-P6-08 decideAll provenance gap. A run that fails read-back must be distinguishable from a pre-publication refusal, and its output must not be accepted by later readers. Prefer verifying before final publication where practical. Preserve any failed artifacts for diagnosis.

Then have build-engineer fix M1 and M3. Report the actual publication state, and add focused coverage for the dev task's refusal paths. Include the uncommitted-code check, scoped to the build and source files that affect the run and excluding generated outputs. Make sure the check permits a later manifest-only commit to pin the earlier implementation commit.

Run the required checks for the changed code and get a focused review. Keep sbt erDecisionGroupingDev inactive until MAJ-1 is resolved and I ratify the exact allowlist change. Defer the broader hook hardening.

DE-P6-02 can proceed separately using the verified sample output. Keep Phase 5 accepted and leave its policy and evidence intact. Do not access held-out labels or evaluation output.

Return the repair results and the exact files for the first human commit. Explain how the manifest pinning will work before asking me to commit. Do not stage, commit, push, or launch the durable dev run.
```

## Orchestrator sequencing note (2026-11-16)

- Work orders: ER-P6-07 (entity-resolution-engineer: M2, CR-P6-08 minor 1), then BE-P6-02 (build-engineer: M1, M3, uncommitted-code check), then CR-P6-10 (focused review of both diffs).
- BE-P6-02 runs after ER-P6-07 because M1 keys off the exit-code contract ER-P6-07 defines.
- MAJ-1 is not repaired in this step: it needs the human's first commit. The allowlist row is not prepared in this step.
- DE-P6-02 is held until after the first human commit so the commit's file list stays fixed; it has no technical dependency on the dev run.
