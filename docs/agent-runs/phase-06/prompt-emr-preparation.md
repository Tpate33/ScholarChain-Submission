# Phase 06 human order: Option A preparation and local runtime validation (2026-11-17)

Verbatim:

> The cloud I/O milestone is committed. Option A is approved for preparation
> and local runtime validation. Continue through the assigned owners under
> the consolidated authorization. Reuse existing test evidence.
>
> Prepare the minimum packaging, verified Spark download, compatibility
> test, Terraform and execution plan. Ask only for account information
> needed now and unavailable through permitted inspection. Present final
> IAM, verified cost estimates and execution approvals with the concrete
> plan. Treat the quoted $2 as an estimate pending verification.
>
> AWS mutations, uploads and spending remain pending approval.

## Orchestrator observations at start

- HEAD `bd9c0a5` ("Phase 6: add cloud-capable Spark I/O and job entry point"), 37 files; `git status --short` empty.
- First Bash call (`git log --oneline --max-count=3`) refused by `audit-bash-writes.sh --pre` as UNATTRIBUTED, changed paths `.claude/.last-test-run` (GENERATED) and `docs/agent-runs/phase-06/human-gate.md` (HUMAN); re-run once. Marker read: `written_by=sbt markTestRun`, `written_at_utc=2026-11-18T02:27:11.280539Z`, `git_head=facb73d…`, `git_worktree=dirty`, `suites=110`, `tests_passed=1547`, `tests_failed=0`, `tests_errored=0`, `tests_skipped=0` (the human's pre-commit sbt check).

## Routing plan (one agent at a time)

1. Orchestrator (web-fetch, official sources only): Apache Spark 3.5.5 Scala 2.13 distribution checksum and bundled Hadoop version; matching hadoop-aws and AWS SDK bundle; AWS prices for the planned resources.
2. spark-engineer: CR-P6-26 n1-n6, u1 start-up refusal of S3A marker deletion, u2.
3. build-engineer: assembly for the job, assembly-content check, pinned distribution download with checksum verification, `sbt sparkSubmitLocal` local compatibility gate (the row needs factory-engineer and ratification before it can run).
4. factory-engineer: proposed allowlist rows (draft for ratification; not effective until restart).
5. infra-engineer: Terraform (bootstrap, base, emr-run), EMR bootstrap and submit scripts, `scripts/run-bounded-spark.sh`; fmt check, script syntax and lint only (validate and plan rows inactive).
6. code-reviewer: one review of steps 2-5.
