# Phase 5 prerequisite (Unicode format characters): human prompts

> **PREREQUISITE WORK ONLY. THIS IS NOT COMPLETION OF PHASE 5.**
> This directory currently holds only evidence for the prerequisite recorded in
> `docs/agent-runs/phase-04/human-gate.md`, "Accepted deferrals", first item. The main Phase 5 work
> order has not been given to the orchestrator and no main Phase 5 work has started. Files for the
> main Phase 5 run (`prompt.md`, `agent-report.md`, ...) will be added separately.

Session date: 2026-11-04. HEAD at start: `d16e8c9717f806f6f4018b56beb456382c5ed23a` (clean tree).

## Prompt 1 (verbatim)

```text
Before beginning the professor’s Phase 5 work order, resolve the Unicode blocking issue recorded in the Phase 4 human gate.

Delegate the fix to entity-resolution-engineer and regression assertions to test-engineer, respecting file ownership. Fix invisible Unicode format characters splitting name blocking keys, update the blocking version appropriately, preserve raw names, and run sbt check.

Report exact test results and obtain a focused code-reviewer review of this change. Do not change constitutional rules, commit, push, access AWS, or proceed with the rest of Phase 5 yet.
```

## Prompt 2 (verbatim)

```text
Assemble the Unicode prerequisite work orders, agent reports, exact validation results, reviewer verdict, and remaining limitations under docs/agent-runs/phase-05/. Clearly label this as prerequisite work, not completion of Phase 5.

Keep the nonblocking review notes documented for now. Do not make further implementation changes, write my human-gate.md, commit, push, access AWS, or begin the main Phase 5 work yet.

Report when the evidence is assembled.
```

## Files in this evidence set

| File | Content |
|---|---|
| `prereq-unicode-prompt.md` | this file: the human prompts, verbatim |
| `prereq-unicode-work-orders.md` | the four work orders the orchestrator issued, verbatim |
| `prereq-unicode-agent-report.md` | final reports of entity-resolution-engineer, hadoop-engineer, test-engineer, verbatim |
| `prereq-unicode-repair-01.md` | the BLOCKED-ON routing to hadoop-engineer and its rerun evidence |
| `prereq-unicode-test-report.md` | tested state and exact `sbt check` results |
| `prereq-unicode-review-report.md` | code-reviewer verdict verbatim, plus orchestrator follow-up checks |
| `prereq-unicode-limitations.md` | status, non-blocking review notes, open policy decisions, remaining limitations |

`human-gate.md` and `commit.txt` are human-only and were not written.
