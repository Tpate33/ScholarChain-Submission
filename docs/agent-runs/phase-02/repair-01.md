# Phase 2 repair-01

Assembled by the orchestrator. Full verbatim reports: `agent-report.md` (section 2) and `review-report.md` (Review 2).

| Finding | Severity | Routed to | Outcome (re-review) |
|---|---|---|---|
| F1 subproject `*.sbt` can override build policy | major | build-engineer (fail-closed guard); factory-engineer (ownership-map row, not yet routed: needs human approval) | FIXED in build (`project/BuildGuard.scala`, load-time guard at `build.sbt:11`, `verifyBuildSources` first in `check`). Ownership-map row still open |
| F2 `spark-sql_2.13` Provided vs EMR 7.x Spark (Scala 2.12, per agent knowledge) | major | human decision; build-engineer recorded only | Accepted deferral: ledger comment marks runtime target pending human decision before Phase 6; dependency unchanged |
| F3 zero-test allowance global | minor | build-engineer | FIXED (per-project check, `TestMarker.scala:31-40`, `64-68`) |
| F4 AWS-free test env incomplete | minor | build-engineer | FIXED, best-effort (all inherited `AWS_*` blanked; network not blocked) |
| F5 plugin pins not checked before resolution | minor | build-engineer | FIXED (`project/plugins.sbt` validates in the meta-build) |
| F6 image tag not digest | minor | human (needs Docker) | Accepted deferral |
| F7 ledger nits, `.gitignore` header, marker temp file | nit | build-engineer | FIXED (literal-version scan partial, see N1) |

Rerun evidence (build-engineer, observed by that agent): `sbt Test/compile` success; `sbt check` success, 9 projects, 0 suites, 0 tests, marker written; `sbt packageJobs` success. F1 and F5 failure paths demonstrated with temporary, reverted changes.

Orchestrator checks after repair (observed in this session):
- `.claude/hooks/verify-tests-ran.sh --check` -> `verify-tests-ran --check: OK (no uncommitted source change is newer than .claude/.last-test-run)`
- `git status --porcelain` -> ` M .gitignore` plus untracked `.scalafmt.conf`, `build.sbt`, `datasets/`, `docs/incidents/`, `experiments/`, `infra/`, `load-tests/`, `modules/`, `project/`, `prompts/`, `scripts/`, `tests/` (and this `docs/agent-runs/phase-02/` directory).

Re-review verdict: **APPROVE**, with nits N1 (literal-version scan can be evaded) and N2 (duplicate owner text in `.gitignore` header), not repaired.
