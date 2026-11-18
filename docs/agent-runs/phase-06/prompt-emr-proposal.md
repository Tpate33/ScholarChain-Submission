# Phase 06 work order: minimum bounded EMR proposal (verbatim)

Received by the orchestrator on 2026-11-17 after the local milestone commit. The decisions in this text are the human's own words. The human records them in `human-gate.md`.

```text
The local milestone is accepted and committed. Read HEAD and continue
preparing the minimum bounded EMR proposal through the assigned owners.

First have build-engineer assess runtime compatibility from the existing
documentation evidence and recommend one approach. Do not build two
runtime variants or perform a major dependency upgrade before presenting
the tradeoffs for my approval.

Proceed with permitted preparation of cloud-capable input/output and the
job entry point, routing ownership correctly. Have infra-engineer prepare
the resource, IAM, cost, cleanup and execution proposal.

Return one concrete proposal with verified runtime compatibility,
estimated total cost, remaining blockers and exact approvals needed.
Identify any account or Region information that cannot be obtained
through permitted inspection. Keep AWS mutations and spending pending
approval.
```

## Orchestrator pre-checks

- `git log --oneline --max-count=3`: `facb73d Phase 6: complete local Spark analytics milestone`, `c2d9a82 …`, `f834c60 …`. `git status --porcelain`: empty.
- The first Bash call was refused by `audit-bash-writes.sh` as UNATTRIBUTED (`docs/agent-runs/phase-06/human-gate.md`, owner HUMAN: the human's committed gate entry); baseline reset, command re-run.

## Routing

1. build-engineer: runtime compatibility assessment and one recommendation from emr-docs-research-01.md and the build (read and report; no runtime variant, no major upgrade).
2. spark-engineer: cloud-capable input/output (Hadoop FileSystem; create-once on S3) and the job entry point, runtime-neutral; focused checks; build changes, if any, routed to build-engineer.
3. infra-engineer: resource, IAM, cost, cleanup and execution proposal (document only; no Terraform apply, no AWS mutation); pricing evidence supplied by a documentation read.
4. Orchestrator: one consolidated proposal (emr-proposal.md) for the human.
