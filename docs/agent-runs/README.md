# Agent run evidence (`docs/agent-runs/`)

This directory is the audit trail of the agentic build. Its contents are human-owned evidence: "Human
ratification records under `docs/agent-runs/**` are human-owned evidence". The orchestrator assembles agent
final reports and deterministic command output here. Only the human writes gate decisions and commit hashes.
Ownership and enforcement are defined in `docs/agents.md`, sections "Path ownership" and "Phase evidence format".

## Layout

There is one directory per phase, `docs/agent-runs/phase-NN/`, where NN is the two-digit phase number.

| File | Required | Written by | Content |
|---|---|---|---|
| `prompt.md` | always | orchestrator (Phase 1: human) | the exact work order given to the orchestrator, verbatim |
| `agent-report.md` | always | orchestrator (Phase 1: human) | each owning agent's final report, verbatim, with every report-contract field |
| `review-report.md` | when review was required | orchestrator (Phase 1: human) | the `code-reviewer` verdict and verified findings: severity, file:line, failure input, impact, owning agent |
| `test-report.md` | when testing was required | orchestrator (Phase 1: human) | captured immediately before the test run: the output of `git rev-parse HEAD` (the tested commit SHA) and of `git status --porcelain`, which must be empty (a clean tested tree); then the exact command, its exit status, and the exact observed pass, fail, and ignored counts, with each failure. `/deploy` gate 5 relies on this file and fails without the HEAD and clean-status record. The test marker is not commit evidence. |
| `repair-NN.md` | per repair loop | orchestrator (Phase 1: human) | findings routed, owning agent, change summary, rerun evidence |
| `versions.md` | Phase 1, and whenever tooling changes | orchestrator (Phase 1: human) | Claude Code version, model configuration when known, JVM, sbt, Scala, Terraform, AWS CLI, scalafmt, and any other replay tools |
| `*.txt`, `*.diff`, `*.patch` | as referenced | orchestrator (Phase 1: human) | deterministic command output and diffs cited by a report |
| `human-gate.md` | always | **human only** | what was reviewed; what was accepted or rejected; any manual changes; any remaining risk; the accepted commit hash |
| `commit.txt` | always | **human only** | the accepted commit SHA |

## Rules

- **Hidden model reasoning is never stored.** No chain-of-thought or other private reasoning is requested,
  stored, or accepted as evidence. Store only work orders, final reports, deterministic command output, review
  findings, and human decisions.
- Reports are pasted verbatim. Never summarize, edit, or "clean up" an agent report after the fact. Put
  corrections in a new `repair-NN.md`.
- No agent ever edits a human decision. `enforce-ownership.sh` refuses `human-gate.md` for every agent and for
  the orchestrator. It refuses `commit.txt` once ownership amendment A2 is ratified.
- No secrets, account IDs, or credential values appear here. Use environment-variable names only.
- Manual edits must be disclosed in `human-gate.md`. The spec says: "Manual editing is not automatically
  prohibited, but it must be disclosed".
- `commit.txt` cannot contain the hash of the commit that adds it. The human therefore records the accepted
  hash for phase NN in a follow-up commit or in the next phase's commit.
- Phase 1 evidence is committed by the human. Before the Phase 1 constitution is ratified, no agent has write
  authority that could be used to assemble it.

## Replaying a phase

Open a fresh Claude Code session. Copy the work order from `phase-NN/prompt.md`, and tell the orchestrator to
use the named owning subagent. Then compare the new report, tests, and diff with the archived evidence. The
wording need not match byte for byte. The required artifacts, contracts, tests, safety gates, and observable
results must match.
