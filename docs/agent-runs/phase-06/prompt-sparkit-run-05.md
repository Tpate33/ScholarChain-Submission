# Phase 06 work order: sparkIT run 05 (verbatim)

Received by the orchestrator on 2026-11-17 after commit c2d9a82. The decisions in this text are the human's own words. The human records them in `human-gate.md`.

```text
Step 1B and the run 05 approval are committed. Read HEAD and confirm
the build and Spark sources match the commit. Have spark-engineer run
standalone sbt sparkIT once. Preserve earlier attempts and record the
nine-test JUnit result, edge and metric totals, reproducibility scope,
and output integrity evidence. If it fails, report the exact failure
and stop. Hold the next Spark step and AWS execution.
```

## Orchestrator pre-run checks (read-only git)

- `git rev-parse HEAD`: `c2d9a8216b945f2dd57eb587099f0b938ed1d670`.
- `git show --stat c2d9a82`: 20 files, 1929 insertions, 59 deletions: the 14 reviewed modules/spark files (8 modified, 6 new), the 5 evidence files (cr-p6-24-review.md, prompt-sp-p6-02-step-1b.md, prompt-sparkit-run-04.md, sp-p6-02-step-1b-report.md, sparkit-run-04.md) and the human-gate.md entry "Spark researcher metrics and run 05 approval (2026-11-17)". Matches the scope returned after CR-P6-24.
- `git status --porcelain`: empty. `git diff --stat HEAD -- build.sbt project modules/spark modules/core`: empty. `git ls-files --others --exclude-standard -- build.sbt project modules`: empty.
- The first Bash call of this step was refused by `audit-bash-writes.sh` as an UNATTRIBUTED inter-call change to `docs/agent-runs/phase-06/human-gate.md` (owner HUMAN), the human's committed gate entry; the baseline was reset and the same command re-run.
- Approval: human-gate.md entry above, one standalone `sbt sparkIT` by spark-engineer at the reviewed code in c2d9a82.

## Delegation 1 stopped before the run (orchestrator error)

The orchestrator's checks above ran before this file was written, and the first order to spark-engineer said status must be empty. spark-engineer observed `?? docs/agent-runs/phase-06/prompt-sparkit-run-05.md`, stopped as ordered, and ran no sbt command (`sbt sparkIT` not run; no attempt directory created; attempt-1 and attempt-2 read only). The precondition was the orchestrator's own wording, not the human's. Re-issued to a fresh spark-engineer with the precondition corrected to: tracked tree clean against HEAD, and the only untracked path is this file under docs/agent-runs/ (outside the sparkIT code scope). The human's approval of one run is unused until that run.

## Expected (hand-derived, CR-P6-24 recomputed)

- 9 IT tests in 1 class, 0 failures, 0 ignored, 0 assumption failures; JUnit junit-4.13.2.jar.
- Edges as run 04: 1970-2026 41/112/0/163/182; 2020-2024 24/64/0/96/107; 259 edges.
- Metrics (records/P/T/U/Wu/C/W/max M): 1970-2026 112/135/364/0/0/326/364/4; 2020-2024 64/78/214/0/0/192/214/4; 176 rows; four named researchers as in sp-p6-02-step-1b-report.md.
- Reproducibility lines for edges and metrics (two builds within the one forked JVM).
- New attempt directory (expected attempt-3); attempt-1 and attempt-2 untouched; no `sbt clean`.
