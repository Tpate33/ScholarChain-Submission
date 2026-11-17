# Phase 06 work order: consolidated local Spark deliverables (verbatim)

Received by the orchestrator on 2026-11-17 after sparkit-run-05.md. The decisions in this text are the human's own words. The human records them in `human-gate.md`.

```text
Accept run 05 as passing local evidence for edges and researcher metrics.
Include its evidence files in the next reviewed implementation commit.

Proceed under my consolidated Phase 6 authorization. Delegate the remaining
local deliverables to spark-engineer: components, label-propagation
communities, cross-community degree, k-core, selected shortest paths,
temporal comparisons, computed summaries, and required Parquet exports
and load manifests. Route ownership blockers through the assigned owners,
one agent at a time.

Continue through necessary local diagnosis, repairs, tests and permitted
sparkIT reruns. Use focused checks during implementation, then the required
sbt check, full local integration, and consolidated independent review.
Stop for actual human-only gates or material decisions requiring approval.

Carry the documented Parquet byte-reproducibility limits forward. Require
logical results to be reproducible and retain each attempt's integrity
manifest. Do not hold analytics solely to diagnose different Parquet bytes.

Maintain the professor-requirement checklist and prepare the minimum
bounded EMR execution plan for approval. Preserve Phase 5 acceptance and
held-out restrictions. AWS spending and execution require my approval.
```

## Orchestrator handling

- Run 05 evidence (prompt-sparkit-run-05.md, sparkit-run-05.md) stays uncommitted and goes into the next reviewed implementation commit.
- Steps (spark-engineer, one at a time, focused `sbt sparkAnalytics/...` checks during implementation):
  - 2A: connected components; label-propagation communities; cross-community degree (bridge measure, plus number of distinct adjacent communities); k-core coreness.
  - 2B: stored shortest paths for the versioned benchmark-pair list; temporal comparisons across the configured windows.
  - 2C: computed semantic-summary inputs; Parquet exports with part manifest v2 and the load manifests.
- Then one `sbt check`, one `sbt sparkIT` (full local integration), and one consolidated code-reviewer review. Repairs may rerun focused checks, `sbt check` and `sbt sparkIT` under this authorization.
- sparkIT under this authorization runs on uncommitted code (commits are human-only). Each run report records HEAD plus `git diff --stat` and the untracked file list at run time, because the run manifest's codeVersion is the unpinned placeholder. Each attempt's `_parts.json`, `_run-manifest.json`, junit-result.txt and result.txt are copied into the run report.
- Ownership blockers (core contracts: domain-engineer; build: build-engineer; allowlist: factory-engineer) are routed one agent at a time; a constitutional change stops for the human.
- Checklist: `requirements-checklist.md` (orchestrator, maintained each step) maps prompt.md items to evidence.
- EMR plan: `emr-plan-draft.md`, a draft for human approval only. No AWS call, Terraform or script is run or authored for it in this step; Terraform and scripts belong to infra-engineer under a later approved work order.
- Requested human record: the consolidated authorization (sparkIT reruns on uncommitted code during this step) in `human-gate.md`.
