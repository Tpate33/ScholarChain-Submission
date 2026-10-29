---
description: Run a bounded ScholarChain Hadoop-to-Spark data pipeline in an approved environment through the approved script
argument-hint: <environment> <dataset-manifest-snapshot> <bounded-config>
disable-model-invocation: true
---

# /run-pipeline

Responsible agent: `deploy-engineer`

This routing is **proposed, pending ratification** (gap G6 in `docs/agents.md`). The matrix names no pipeline operator. `deploy-engineer` is proposed because control rule 5 makes deployment operators the executors of approved scripts. Local bounded runs during development remain part of the `hadoop-engineer` and `spark-engineer` gates and do not use this command.

Arguments: `$ARGUMENTS` (environment, dataset manifest snapshot, bounded run configuration).

## Gates

1. `scripts/run-bounded-pipeline.sh` exists, is authored by `infra-engineer`, and was approved in a ratified phase.
2. The environment is deployed and approved, and the dataset snapshot exists in `datasets/dataset-manifest.yaml`.
3. The run is bounded: record limits, EMR capacity within the Terraform-defined maximum, and a cost estimate the human approved.
4. The working tree is clean, and the commit SHA is recorded.
5. The permission policy asks the human to confirm the script invocation.

## Orchestrator procedure

1. Verify the gates. If any is missing, stop with `BLOCKED-ON human` or the owner of the missing artifact.
2. Invoke: "Use the deploy-engineer agent to run scripts/run-bounded-pipeline.sh for <env> with snapshot <snap> and config <cfg>. Record run IDs, counters, output prefixes, duration, and cost indicators. Do not change capacity, terminate clusters directly, or edit any file."
3. Archive the report. Route output-invariant failures to `hadoop-engineer` or `spark-engineer`.

## Limits

- No direct EMR create, terminate, or scale commands (the guard blocks them).
- No data deletion. Rerun outputs go to new run-ID prefixes.
