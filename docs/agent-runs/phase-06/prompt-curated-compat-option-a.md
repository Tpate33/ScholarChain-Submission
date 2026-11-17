# Phase 06 curated-archive compatibility repair: Option A decision (verbatim)

Received by the orchestrator on 2026-11-16 after hd-p6-01-report.md. The decisions in this text are the human's own words. The human records them in `human-gate.md`.

```text
Approve Option A for the exact pinned curated dev run in dev-run-01.md.
Apply HD-P6-01 rules 1–11 to its archived run directory and reject
.DS_Store there. Record attempt-level success separately from the
original repository copy and dev-run-01.md. Do not generalize this
exception to other runs or modify the archive.

Proceed with build-engineer first and spark-engineer second. Make the
preflight and reader enforce the same curated-run contract. Keep the
decision and grouping checks, run-ID validation, bounded traversal,
and symlink refusals in place. Add focused fixtures for the valid
Hadoop layout and incomplete input.

Run the required checks and one focused review of the combined repair.
Return the verdict, exact commit scope, and any approval required for
the revised build. Wait for that approval before retrying sparkIT.
Preserve Phase 5 acceptance and keep held-out data and AWS out of scope.
```

## Attempt-level success of the pinned curated run (recorded separately, not proven by the archive)

- Run: `run:2881724b5c86b561a235836d51946fbd884beed36a14f929b27c278cb5de8baa`, attempt directory `modules/hadoop/target/local-pipeline/er-candidates/dev/attempt-1` (repository copy, gitignored build output).
- HD-P6-01 (hd-p6-01-report.md), Read of the repository copy: `A/_SUCCESS` present with content `runId=run:2881724b…8baa`; `A/summary.txt` last line `status=complete`; `A/_FAILED` and `A/_IN_PROGRESS` absent.
- dev-run-01.md: erDecisionGroupingDev preflight named this run "the one complete dev attempt".
- The archive (`~/ScholarChain-archive/phase-06/dev-run-01/`) holds the run directory only; HD-P6-01 rule 12 is not checked against it. This exception applies to this one pinned run only.
