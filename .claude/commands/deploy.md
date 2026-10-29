---
description: Route a human-approved ScholarChain deployment to deploy-engineer after verifying every gate
argument-hint: <environment> <commit-sha> <plan-sha256>
disable-model-invocation: true
---

# /deploy

Responsible agent: `deploy-engineer`

Arguments: `$ARGUMENTS` (expected: environment, commit SHA, Terraform plan SHA-256).

## Gates

The orchestrator verifies every gate **before** invoking the agent. If any gate is missing, it stops and reports `BLOCKED-ON human` without invoking anyone.

1. All three arguments are present. The environment is the one named in the human-approved plan.
2. `docs/agent-runs/phase-NN/human-gate.md` (human-authored) records approval of this exact commit SHA and plan checksum, plus confirmed target account, Region, cost bound, and no unexplained destroy or replacement of stateful resources.
3. `code-reviewer` returned APPROVE for the infrastructure and application diff at this commit.
4. The working tree is clean (`git status --porcelain` is empty) and `HEAD` equals the commit SHA.
5. `sbt check` passed at the tested commit, shown by committed evidence. `docs/agent-runs/phase-NN/test-report.md` (or the owning agent's verbatim report in `agent-report.md`) must record, captured immediately before the `sbt check` run, the output of `git rev-parse HEAD` (the tested commit SHA) and of `git status --porcelain`, which must be empty (the tested tree was clean). It must then record the exit status 0 and the exact observed `sbt check` counts (passed, failed, ignored). `git diff --name-status <tested-sha> <commit-sha>` must list only paths under `docs/agent-runs/`; for every rename or copy entry (`R` or `C`), both the source and the destination path must be under `docs/agent-runs/`. This gate fails if the report is missing; if the `git rev-parse HEAD` or `git status --porcelain` record is missing; if that status output was not empty; if the counts are missing; if the failed count is nonzero; or if any listed path is outside `docs/agent-runs/`. The test marker is not evidence for a commit: `.claude/hooks/verify-tests-ran.sh --check` sees only uncommitted changes and always reports OK on the clean tree that gate 4 requires. Binding the marker to a commit SHA is a Phase 2 `build-engineer` item (`markTestRun`).

## Orchestrator procedure

1. Check the gates read-only. Do not run any deployment command yourself. The hooks block `scripts/deploy.sh` for every identity except `deploy-engineer`.
2. Invoke: "Use the deploy-engineer agent to deploy commit <sha> with plan checksum <sum> to environment <env>. Gates verified: <list with evidence>. Execute only approved scripts. Run smoke tests. On failure follow the approved rollback policy for this deployment only."
3. The permission policy asks the human to confirm the `scripts/deploy.sh` invocation.
4. Archive the agent's report verbatim as `docs/agent-runs/phase-NN/agent-report.md`. The human writes `human-gate.md`.

## Limits

- No `terraform apply` outside `scripts/deploy.sh`, and no direct AWS mutation.
- No destructive data repair, no scale changes outside the plan, no cleanup.
- Rollback of an already released revision goes to `/rollback` (`incident-responder`).
