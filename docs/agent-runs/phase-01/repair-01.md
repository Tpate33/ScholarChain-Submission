# Phase 01 -- repair-01

> Drafted by factory-engineer in session a9fda787 (scratchpad `factory/rev5/phase-01-evidence/repair-01.md`) and
> committed by the human. Only the "Agent report" section was completed afterwards, by the orchestrator, as a
> pointer to the verbatim report.

## Trigger

An independent human-commissioned review of the unratified Phase 1 constitution
returned eight findings (paste the review verbatim into `review-report.md`).

## Findings, routing, and outcome

| # | Finding | Bucket | Routed to | Outcome |
|---|---|---|---|---|
| 1 | `docs/agent-runs/README.md` missing; writer ambiguous; documentation-engineer description looked like it claimed `docs/agent-runs/**` | B (writer) + A (description wording) | human (A1) / factory-engineer | description reworded; content drafted in `docs-agent-runs-README.md`; writer pending A1 |
| 2 | Bash guard did not enforce file ownership | A | factory-engineer | structural shell analyzer + ownership check in `guard-dangerous.sh`; fail-closed forms; detective `audit-bash-writes.sh` |
| 3 | AWS destructive detection bypassed by global options/wrappers | A | factory-engineer | option-tolerant rules + canonical AWS parsing; settings.json mirror |
| 4 | Secret-read coverage gaps | A | factory-engineer | extended patterns, symlink/glob/home-recursion checks, settings.json Read/Bash denies |
| 5 | format-scala.sh succeeded when scalafmt/config missing | A | factory-engineer | exit 2 with install command / build-engineer ownership; silent exit 0 when no repository Scala changed |
| 6 | Ownership gaps G1-G12 | mixed (see report) | human | proposals A3-A11; docs pointer added |
| 7 | Phase 1 evidence missing | B | human (A2) | evidence drafted in this folder |
| 8 | `feature-implementer` discrepancy | A (docs) + human decision | human | G8 row rewritten; no silent substitution |

## Rerun evidence (deterministic outputs in this folder)

| Suite | File | Result |
|---|---|---|
| Earlier guard/ownership/format/validator-hook probes | `probes-earlier-after.txt` | 94 matched, 0 mismatched (2 expectations changed, documented) |
| New regression probes (findings 2, 3, 4, reviewer fence, deploy allowlist) | `probes-regression.txt` | 194 matched, 0 mismatched |
| format-scala sandbox probes (finding 5) | `probes-format.txt` | 12 matched, 0 mismatched |
| audit-bash-writes sandbox probes | `probes-audit.txt` | 9 matched, 0 mismatched |
| Stop-hook sandbox probes | `probes-stop.txt` | 9 matched, 0 mismatched |
| Validator negative (12 seeded faults) | `probes-validator-negative.txt` | all 12 detected; exit 1 |
| JSON / bash -n / +x / validator | `validation-output.txt` | all OK; RESULT: PASS (0 failures) |
| Repair-only diff | `phase-01-repair.diff` | sha256 recorded in the agent report |

## Expected results that changed

1. `rm single explicit file` (`rm modules/core/target/tmp.txt` as ORCHESTRATOR): ALLOW -> BLOCK.
   Shell writes now obey the one-writer map, and the orchestrator does not own `modules/core/**`.
2. `Scala write, scalafmt absent` (file in the scratchpad, outside the repo): still ALLOW (exit 0), but the
   reason changed. A file outside the repository is not a repository Scala change. The
   missing-scalafmt case for repository files now exits 2 (`probes-format.txt`).

## Agent report

The factory-engineer repair-01 final report is reproduced verbatim in `agent-report.md`, in the section whose
source is `5a5ff524-b402-4b6e-a946-d7680a10489c/agent-a8fa914e87c28d6d0.jsonl` (timestamp 2026-10-27T23:37:08Z,
"STATUS: BLOCKED-ON ... WORK ORDER: Phase 1 repair (repair-01)").

Later repair rounds (allowlist guard redesign, C1-C5, NEW-1..NEW-6, reviewer pass-3 findings R1-R6, and the final
targeted repair round) have no separate narrative repair record. Their verbatim work orders are in
`work-orders-and-instructions.md`, and their verbatim reports are in `agent-report.md` and `review-report.md`.
