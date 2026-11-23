# Deliverable index and outstanding work

This index is a candid inventory, not a statement that every course gate passed.
Author: Taufeeq Patel, tpate33@uic.edu. Deadline: November 25, 2026, 11 PM CST.

| Requirement group | Location / status |
|---|---|
| Agent definitions, permissions, ownership | `.claude/`, `docs/agents.md`; historical ratification in phase records; unchanged by takeover |
| Versioned contracts | `modules/core/` |
| Dataset selection, samples, licenses | `datasets/dataset-manifest.yaml`, `datasets/schemas/`, Phase 3 records |
| Hadoop / ER / Spark | `modules/hadoop/`, `modules/entity-resolution/`, `modules/spark/`; local evidence in Phases 4–6 |
| Storage and runtime agents | `modules/storage/`, `modules/rag/`; local implementations and adapter tests; live use unverified |
| REST and local demos | Verified DBLP-derived profile: `scripts/pipeline-demo.sh`, `scripts/pipeline-demo-smoke.py`; original synthetic profile preserved |
| Terraform | `infra/terraform/`; historical EMR stacks only, incomplete runtime environment |
| Automation | Existing plan/deploy/cleanup/pipeline scripts plus local demo/replay; full required automation incomplete |
| Deployment / images / release | No verified runtime deployment/image digests; `release-manifest.json` records absences |
| Elasticity experiments | Not performed; no plots or scale-event claims |
| Evaluation | `evaluation.md`; historical ER/local graph evidence, real-data and synthetic API replays; incomplete live evaluation |
| Documentation | architecture, data-lineage, threat-model, service-limits, runbook, evaluation, demo-script, api-reference in `docs/` |
| Final report | `reports/final-report.md` |
| Video | Student recording outstanding; script in `demo-script.md` |
| Cleanup | November 19 Phase A recorded in Phase 6 human gate; retained base/cloud inventory not rechecked |

Outstanding graded requirements include successful cloud processing, live store loads,
real embedding/answer models, required corpus size or instructor-approved deviation,
complete runtime IaC/deployment, elasticity experiments, final course acceptance,
clean-checkout verification, video, and release acceptance. No instructor waiver is
claimed. Local fixtures must not be presented as an approved substitute.

Local preparation and remaining deployment connections: [cloud preparation](cloud-preparation.md).

Integration 02 local closeout: [review](agent-runs/integration-02/review-report.md), [tests](agent-runs/integration-02/test-report.md), [proposed human gate](agent-runs/integration-02/human-gate.md).
Both independent review scopes approve the local checkpoint after the major cleanup
repair. Final post-repair verification passed 2,851 tests with zero failures/errors
and one skipped across 233 suites. Human acceptance and the
[human Git steps](agent-runs/integration-02/closeout-steps.md) remain unperformed.
