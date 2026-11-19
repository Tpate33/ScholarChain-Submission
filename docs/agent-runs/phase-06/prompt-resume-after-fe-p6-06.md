# Phase 06 work order: resume after FE-P6-06 ratification (2026-11-18)

Verbatim human work order to the orchestrator (fresh session after commit B):

```
Resume Phase 6 after FE-P6-06 ratification. Commit B is 103d05d. Confirm HEAD matches that commit and the working tree is clean.

Read these files under docs/agent-runs/phase-06:
- handoff-2026-11-17.md
- approval-package-2026-11-18.md
- human-probes-and-quota-2026-11-18.md
- human-gate.md

Have factory-engineer run .claude/hooks/validate-agent-files.sh --probes once after this restart and record whether the result matches my Terminal run: 888 table probes, 51 fixture probes, and only the five known ShellCheck failures. Report any additional failures before proceeding.

The cleanup.sh ShellCheck and bash -n checks are already recorded; do not repeat them. Effective EC2 quota L-1216C47A in us-east-1 was verified at 16.0 vCPUs.

Then have build-engineer run sbt sparkSubmitLocal, requesting approval as configured. The first invocation downloads; the second runs. Record the inventory and verdict, and route failures to their owner.

After compatibility passes, have infra-engineer prepare the artifact digests, dry-run instructions, and bootstrap plan command for me to run in my Terminal.

Reuse passing evidence for unchanged code and defer unrelated nits. AWS deployment, uploads, and spending remain pending my approval of each specific signed plan.
```

Orchestrator observations at session start:
- `git rev-parse HEAD`: `103d05dbc8f6f71ff21f2c84665b8a5f299b2929` (matches commit B).
- `git status --porcelain`: no output (clean tree).
