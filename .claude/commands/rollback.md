---
description: Route a rollback of a released ScholarChain revision to incident-responder with bounded autonomy
argument-hint: <environment> <target-previous-revision> <reason>
disable-model-invocation: true
---

# /rollback

Responsible agent: `incident-responder`

Arguments: `$ARGUMENTS` (expected: environment, target previously released revision, reason or incident ID).

Routing split (gap G11 in `docs/agents.md`): a rollback **during** a failing deployment run is handled inside that run by `deploy-engineer`. This command handles rollback of an **already released** revision.

## Gates

1. The target revision is a previously released, recorded revision (release record or archived deployment report) and the human confirms it explicitly.
2. An incident ID exists or is opened by this command. Evidence of the user-visible problem is attached.
3. The rollback restores application revisions only. It changes no schema, data, or infrastructure.
4. The permission policy asks the human to confirm the `scripts/rollback.sh` invocation.

## Orchestrator procedure

1. Confirm the gates. If the target revision is unknown or unconfirmed, stop with `BLOCKED-ON human`.
2. Invoke: "Use the incident-responder agent to roll back <env> to released revision <rev> for incident <id>. Run only scripts/rollback.sh, then scripts/smoke-test.sh. Do not repair data, change schemas, scale, or delete anything. Write the incident report under docs/incidents/."
3. Archive the report and route preventive follow-ups to their owners.

## Limits on autonomous action

- Allowed: read-only diagnosis, `scripts/rollback.sh` to the confirmed revision, `scripts/smoke-test.sh`, and writing the incident report.
- Escalate to the human: anything involving data, schema, security, deletion, Terraform, or scale.
