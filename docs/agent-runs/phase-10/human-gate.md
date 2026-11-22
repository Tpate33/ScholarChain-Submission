# Phase 10 human acceptance — local scope

DRAFT prepared by the orchestrator for the human's review. Not signed. Edit every bracketed item; remove this line when you accept.

Date: [2026-11-__]
Decision: [Accept / Reject] the LOCAL development scope only.
Accepted implementation commit: [40-character SHA of the Phase 10 implementation commit]

## What was reviewed
Agent reports (agent-report.md; repair-01.md to repair-05.md), test report (test-report.md), review reports (review-report.md: CR-P10-01 APPROVE; CR-P10-02 and CR-P10-03 REQUEST_CHANGES; CR-P10-04, CR-P10-05 and CR-P10-06 APPROVE), outstanding.md and prompt.md. [State whether you did a personal line-by-line source review; the Phase 9 record said it did not.]
Manual implementation changes during acceptance: [none / describe].

## Final verification
- sbt check exit 0; 194 suites; 2331 passed; 0 failed; 0 errored; 1 skipped (orchestrator read of the run output, shell b91m6puid, 410 s).
- Per project: core 621, entityResolution 464, hadoop 85, rag 132, root 0, services 99, sparkAnalytics 263, storage 437, tests 230 (1 skipped).
- Marker: written 2026-11-22T16:05:35.223980Z, git_head=3d9da3e, worktree=dirty; reviewed by me together with git status. The run was backgrounded by the harness, so the marker write was flagged UNATTRIBUTED; I checked it against the run's markTestRun line.

## Disclosure: change after the last code review
Repair 05 (SE-P10-06: m-A fix, n-A and n-B comments, U-1 and U-3 records) passed sbt check (the run above) but was NOT re-reviewed by code-reviewer, by my decision. The last review verdict, CR-P10-06 APPROVE, covers the code through repair 04.

## Decisions recorded in this phase
- Bounded test logging (BE-P10-01) accepted. Logging review findings m1 and n1-n3 accepted as documented residuals for local Phase 10.
- Three services: query/jobs, evidence/explain, graph (QueryJobsMain, EvidenceExplainMain, GraphMain).
- Evidence: shared identity and provenance for any read client; per-answer occurrences visible only to the client that submitted the query.
- CR-P10-05 F1 and F2 (evidence side channels) accepted as recorded residuals.
- Explain served only to the submitting client.
- One last small fix after CR-P10-06 (repair 05), without a further review round.

## Local acceptance versus deployment readiness
Accepted here: local services with in-memory fakes, three entry points tested in one JVM, and the bounded test logging.
NOT accepted here, and NOT deployment-ready:
- no production ServiceProvider or real adapters;
- no per-service config keys in core;
- no launchers or images;
- no Terraform, ECS or ALB;
- no Secrets Manager key source;
- no durable ownership, job or idempotency stores;
- no real LLM or real embeddings;
- no live AWS evidence.
See outstanding.md, "Deployment prerequisites".

## Remaining risk (see outstanding.md)
- Accepted residuals: F1, F2, N5, owner eviction, m-A jitter, U-1 and U-3 (CR-P10-06), U2 and the JDK-internal U-1 to U-3 (CR-P10-04), graph scope, query constraints.
- Phase 9 R1 now reaches API clients; it must be reassessed before real-LLM integration and before deployment.
- CR-REL-01 release-scan findings F1-F11 are unchanged. release-scan-cr-rel-01.md is committed with the literal account ID in its `git log -S` command replaced by `<AWS_ACCOUNT_ID>`; this does not resolve the existing account-ID exposure elsewhere in the repository (F1).

This acceptance does not authorize deployment, paid calls, AWS operations, publication, deletions or hook changes.
