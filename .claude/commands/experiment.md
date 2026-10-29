---
description: Route a controlled ScholarChain experiment or evaluation to experiment-engineer
argument-hint: <experiment-manifest-path> <environment>
disable-model-invocation: true
---

# /experiment

Responsible agent: `experiment-engineer`

Arguments: `$ARGUMENTS` (path to a committed manifest under `experiments/`, plus the environment).

## Gates

1. The manifest is committed and records commit SHA, dataset snapshot, configuration, resource limits, Region, and expected duration.
2. The human approved the manifest's cost and scale bound. Anything beyond it is an expensive scale change and escalates.
3. The environment is the approved, deployed one, and the working tree is clean.
4. No production algorithm, configuration default, or scaling policy changes during the experiment.
5. The permission policy asks the human to confirm the `scripts/run-experiment.sh` invocation.

## Orchestrator procedure

1. Verify the gates. If the manifest is missing or unapproved, stop with `BLOCKED-ON human`.
2. Invoke: "Use the experiment-engineer agent to execute the approved manifest <path> in <env> through scripts/run-experiment.sh. Preserve raw results immutably, keep failed runs, and report measured facts separately from interpretation."
3. Archive the report. Route the narrative write-up to `documentation-engineer` only after raw outputs are frozen.

## Limits

- No edits outside `experiments/**` and `load-tests/**`.
- No direct EMR or ECS scaling commands. Scaling comes from the deployed policy only.
