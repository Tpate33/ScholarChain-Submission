# Phase 5 human decisions after TE-P5-01 / RV-P5-04 (transcribed by the orchestrator)

Received 2026-11-06. This is a verbatim transcription, not a human-gate record.

```text
Proceed with focused repairs. Route D1, D4, and D5 to
entity-resolution-engineer and D2 and D3 to domain-engineer. Preserve
the five failing regression tests and the original failure evidence.

For the pending Option A decisions, enforce the same publication-stage
target on inferred acceptance, arXiv-based acceptance, and LLM adjudication.
A shared arXiv ID must not by itself merge a preprint with a published
version. If the stage is uncertain and the evidence cannot establish a
match at the same stage, leave it unresolved. Version the adjudication
prompt to state the same target.

Mask OpenAlex author and work cluster IDs in the blind labeling view.
Require the recorded manifest SHA-256 for final held-out metrics and
clearly identify the split in evaluation output.

After the repairs, run one foreground sbt check and have the independent
reviewer re-check the fixes and report any remaining acceptance blockers.
Leave the test marker to the normal successful test run. Preserve the
hooks and their settings. The download and constitutional ratification
remain pending. No commits, pushes, deployments, or paid model calls.
```

## Status after this instruction

| Item | Decision / routing |
|---|---|
| D1 (= RV-P5-04 M1), D4, D5 | entity-resolution-engineer (ER-P5-08) |
| D2 (= m10), D3 | domain-engineer (DE-P5-02) |
| The five TE-P5-01 failing tests | preserved unchanged; they must pass because of the fixes, never by being edited. The original failure evidence stays in `test-report.md`. |
| Option A gaps 1-3 (RV-P5-04 m2, m3) | stage target enforced on inferred acceptance, arXiv-based acceptance and LLM adjudication. A shared arXiv ID never by itself merges preprint and published. Uncertain stage → UNRESOLVED. Adjudication prompt versioned (v2). |
| m9 | mask OpenAlex author and work cluster IDs in the blind view |
| m4 | manifest SHA-256 required for final held-out metrics |
| m5 | split identified in evaluation output |
| Not covered by this instruction; recorded as open | RV-P5-04 m1 (sensitivity counts blocked rows), m6 (thresholds/model/prompt versions in output; config not applied), m7 (arXiv vN agreement folding), m8 (title markup; er-features.v3), N1-N4; TE-P5-01 comma-form homonym characterization; RV-P5-03 N1-N6 and RV-P5-02 open items; the 180 s sbt-check duration issue |
| Verification | one foreground `sbt check` after the repairs; the marker is written only by a normal passing run. Hooks and settings unchanged. |
| Download, FE-P5-01 ratification | pending |
