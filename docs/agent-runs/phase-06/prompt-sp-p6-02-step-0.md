# Phase 06 work order SP-P6-02 step 0 (verbatim)

Received by the orchestrator on 2026-11-16, after the human accepted and committed DE-P6-02. The decisions in this text are the human's own words. The human records them in `human-gate.md`.

```text
DE-P6-02 is accepted and committed. Read HEAD and dispatch SP-P6-02 step 0
to spark-engineer. Run the small local Spark and Parquet compatibility
test through the existing sbt check. If it fails on the build or
classpath, report the exact error and route it to build-engineer.

For step 1, keep the versioned benchmark pair list under modules/spark,
bound to the archived grouping run. Prefer a separate opt-in integration
test with a configured archive path, so routine sbt check stays portable.
Use an existing permitted invocation if available. If none exists, report
the exact gap before proposing a new task or allowlist entry.

Return the step-0 result. Hold the graph jobs and AWS work for the next
work order.
```

## Orchestrator check of HEAD (2026-11-16)

- `git rev-parse HEAD`: `e59d7539d344eece8120c47c0ac6704773fff518` ("Phase 6: accept Spark analysis contracts"; parent 6be1f1d). `git show --stat`: 29 files, the DE-P6-02 core contracts and tests plus phase-06 evidence including `human-gate.md` (+4).
- `git status --porcelain`: clean.
- The first Bash call after the commit was refused by `audit-bash-writes.sh` as an UNATTRIBUTED inter-call change to `docs/agent-runs/phase-06/human-gate.md` (owner HUMAN). This is the human's own edit committed in e59d753; the refusal reset the audit baseline and the next call succeeded, as documented in docs/agents.md ("Inter-call reports, edits outside Claude Code").

## Orchestrator finding: invocation for the step-1 opt-in integration test

Active spark-engineer rows (docs/agents.md, Bash allowlist): `@GIT`; `@SBT-ALL` (`sbt check`, `sbt clean check`, `sbt compile`, `sbt Test/compile`, `sbt test`); `sbt <SBT:sparkAnalytics>` (up to four of `sparkAnalytics/{compile,Test/compile,test,scalafmt,Test/scalafmt,scalafmtCheck}`). Inactive: `sbt sparkIT` (`inactive:B10-alias-pending`). No `sparkIT` task or alias exists in build.sbt at e59d753. Agents cannot set environment variables or pass arguments (character gate; fixed-shape rows). Therefore every permitted invocation that runs spark tests (`sbt check`, `sbt test`, `sbt sparkAnalytics/test`) runs the routine suite, and no permitted invocation can run an opt-in test that is excluded from routine `sbt check`. Gap: an opt-in invocation needs build-engineer to define `sparkIT` (a separate test configuration or tag filter, reading the archive path from a committed or configured file) and factory-engineer to activate the existing `sbt sparkIT` row, with human ratification and a session restart.
