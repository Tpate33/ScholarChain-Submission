# Phase 06 work order SP-P6-02 step 1 (verbatim)

Received by the orchestrator on 2026-11-16, after the human ratified and committed FE-P6-05 (5e176eb). The decisions in this text are the human's own words. The human records them in `human-gate.md`.

```text
FE-P6-05 is ratified and committed. Read HEAD and have factory-engineer
run the required post-restart probes. If they pass, delegate SP-P6-02
step 1 to spark-engineer. Build the verified archive input and graph
foundation with its first meaningful integration suite. Carry forward
the step-0 review notes.

Have code-reviewer review the suite before its first archive-backed run.
Confirm build.sbt and project/ still match e8c6ecc, then have spark-engineer
run the exact standalone sbt sparkIT command and record the result,
JUnit summary, and output evidence. Keep routine sbt check independent of
the archive. Use dev inputs only and preserve Phase 5 acceptance. Hold
AWS execution.
```

## Orchestrator check of HEAD (2026-11-16)

- `git log --oneline --max-count=8`: HEAD `5e176eb` "Phase 6: ratify standalone Spark integration command" (parent e8c6ecc).
- `git show --stat 5e176eb`: .claude/hooks/bash-allowlist.tsv, .claude/hooks/probes.tsv, docs/agent-runs/phase-06/fe-p6-05-report.md, human-gate.md, prompt-fe-p6-05-activate-sparkit.md, docs/agents.md (6 files, 125 insertions, 17 deletions).
- `git status --porcelain`: clean.
- `git diff --stat e8c6ecc HEAD -- build.sbt project`: no output (build.sbt and project/ match e8c6ecc).

## Carried forward to spark-engineer

- CR-P6-15 N1: assert each Parquet part's StructType matches its labelled record schema (optionally a neutral directory name in the step-0 test).
- CR-P6-15 N2: reword the "no network" Scaladoc to "loopback listeners only; no outbound network".
- Human decision (step-0 work order): the versioned benchmark pair list lives under modules/spark, bound to the archived grouping run; the archive-backed test is the opt-in `sbt sparkIT` suite, routine `sbt check` stays portable.
