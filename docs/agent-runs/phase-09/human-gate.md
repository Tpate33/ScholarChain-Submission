# Phase 9 human acceptance — local scope

Date: 2026-11-21
Decision: Accept the local development scope only.
Accepted implementation commit: 29266611d0a926b0000f06e90ddeb6fbeaf80115

Reviewed the agent reports, repair records (repair-01.md, repair-02.md),
test report, review report (final verdict CR-P9-03 APPROVE) and
outstanding.md. This does not claim a personal line-by-line source review.
No manual implementation changes were made during acceptance.

Final verification (TE-P9-04):
- sbt check exit 0; 183 suites; 2232 passed; 0 failed; 1 skipped.
- Marker: git_head=a5cbdab, worktree=dirty, written 2026-11-21 9:05:24 PM.
- The run was moved to the background by the harness; I reviewed the marker
  and git status before the audit recovery.

Decisions recorded in this phase:
- Focused module tests during implementation; one final full check.
- Entity-link loading is opt-in; corpus tests enable it explicitly.
- The audit stores the structured QueryPlan with resolved researcher IDs and
  explicit unresolved arguments; no raw question text or researcher names.
- Factual claim wording is rendered from verified structured assertions;
  model prose never establishes verification.
- Superseded v1/v2 prompt and schema files are kept as unused history
  (prompts/SUPERSEDED.md).

Non-production components: answer synthesis uses a deterministic template
stand-in; semantic retrieval uses the Phase 8 stand-in embedder. Benchmark
results cover deterministic graph retrieval and plumbing on the local corpus
only, not real-model quality.

Accepted residuals for this LOCAL closeout only (not fixed): CR-P9-03 R1, R2,
R3. R1 must be reassessed before real-LLM integration and before deployment.
Deferred earlier: m7, n1-n4.

Outstanding (mandatory, not waived; see outstanding.md): real LLM and access;
real embedding model; the 10000-vector corpus or instructor approval for a
smaller corpus; live Neptune, DynamoDB and S3 Vectors with bounded AWS
evidence, IAM and Terraform; Phase 7 and Phase 8 live AWS items; DynamoDB
item-size verification; production-scale retrieval and groundedness
evaluation; sbt benchmarkQueries alias; runtime config keys.

This acceptance does not authorize paid calls, AWS operations, deletions,
or hook changes.
