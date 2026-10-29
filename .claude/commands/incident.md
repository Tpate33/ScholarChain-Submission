---
description: Route ScholarChain incident diagnosis and reporting to incident-responder
argument-hint: <symptom or alarm> [trace-id|run-id] [environment]
disable-model-invocation: true
---

# /incident

Responsible agent: `incident-responder`

Arguments: `$ARGUMENTS` (symptom or alarm, and any trace ID, run ID, and environment).

## Gates

1. Diagnosis is read-only (logs, metrics, traces, describe/list/get through `mcp__aws-api`). This needs no approval.
2. Any recovery action beyond diagnosis needs explicit human approval. The only pre-authorized action is `/rollback` to a confirmed released revision.
3. Data repair, schema changes, security changes, deletion, scale changes, and Terraform changes always escalate to the human and are never performed by this agent.
4. Never manufacture a destructive incident in a shared course account.

## Orchestrator procedure

1. Invoke: "Use the incident-responder agent to diagnose <symptom> in <env> (trace/run IDs: <ids>). Collect evidence read-only, identify root cause with evidence, assess data integrity, and write docs/incidents/<incident-id>.md with every required field. Do not change anything else."
2. If the report recommends rollback, the human decides and then runs `/rollback`.
3. Route each preventive test or control to its owner from `docs/agents.md` as a separate work order.
