# Phase 07 work order (human, 2026-11-19), verbatim

We are continuing ScholarChain in a fresh session. Start Phase 7.

Project:
 /Users/taufeeqpatel/Desktop/ScholarChain

Deadline:
 November 25, 2026 at 11:00 PM America/Chicago (CST).
Prioritize completing the required working application and submission evidence. Do not promise completion times or grade outcomes.

Read first:
- CLAUDE.md and applicable repository instructions.
- docs/agents.md.
- README.md, especially the assignment requirements, grading rubric, Phase 7 work order, and acceptance gates.
- docs/agent-runs/phase-06/human-gate.md.
- docs/agent-runs/phase-06/emr-run-attempt-a3-and-phase-6-status.md.
- The saved handoff:
  /Users/taufeeqpatel/ScholarChain-archive/phase-06/handoff/phase-07-prompt.md.

Inspect the actual repository before making assumptions. Follow its ownership, review, testing, and human-commit workflow.

Current state:
- Phase 6 closeout commit: 0c97557. Verify the current full HEAD and working-tree status.
- Local Spark results passed: sparkSubmitLocal attempt 4 and sparkIT runs 07–08.
- Results: 259 edges, 176 metric rows, 112 temporal changes, and 28 matching datasets.
- Six EMR launches were attempted. The final attempt started Spark/YARN with Java 17 but failed on the first textFile input read with:
  java.lang.RuntimeException: Error in configuring object
- A missing EMR codec class is a suspected cause, not a confirmed diagnosis.
- No successful cloud output was published.
- Phase A cleanup succeeded. S3 data, logs, artifacts, and the base stack were retained.
- Phase 6 is closed for continuing development; its required cloud execution remains incomplete. Do not describe it as fully successful.
- No further EMR launches are authorized.

Preserved evidence:
 /Users/taufeeqpatel/ScholarChain-archive/phase-06/local-evidence/
 /Users/taufeeqpatel/ScholarChain-archive/phase-06/attempt-a3/
 /Users/taufeeqpatel/ScholarChain-archive/phase-06/handoff/

Recorded checksum verification:
- local-evidence: 2079 files passed.
- attempt-a3: 33 files passed.

Phase 7 objective:
Implement the storage interfaces and manifest-driven loaders required by the assignment, using the verified local Spark outputs for development and contract tests.

Include, as required by the actual specification:
- Storage interfaces for S3, Neptune, DynamoDB, and S3 Vectors.
- Local implementations/fakes for development and testing.
- Loaders that validate manifests and part integrity, preserve Spark's stable identifiers, and safely handle reruns without duplicates.
- Small, reproducible fixtures derived from the archived outputs.
- Meaningful tests for successful loading, corrupt or missing parts, identifier consistency, and reruns.
- Clear documentation distinguishing implemented interfaces, locally verified behavior, and unverified live AWS behavior.

Constraints:
- No AWS mutations, resource launches, paid services, or live loading without my explicit approval of a concrete plan.
- No Phase B cleanup or deletion of retained cloud evidence.
- Do not run sbt clean or remove generated outputs until the required evidence and fixtures are safely preserved.
- Do not change the professor's requirements to match our implementation.
- Instructor approval for deviations has NOT been obtained. Keep required cloud stores, corpus size, deployment, elasticity, and other unfinished deliverables tracked as outstanding.
- Local fakes and tests are development steps, not proof that required cloud integrations work.
- Keep this session focused on Phase 7. Prepare a clear handoff for subsequent retrieval, API, and testing work.

Before implementation, produce a concise checklist mapping every Phase 7 requirement to planned implementation and validation. Explicitly cover all five required vector indexes, graph relationships and evidence properties, DynamoDB keys and conditional writes, vector metadata and filters, batching, retries, rejected records, and failure-handling tests. Distinguish local verification from live AWS acceptance criteria. Offline AWS adapter implementation is authorized within repository ownership and dependency rules; live AWS execution still requires my approval.

Begin by checking the repository and identifying the exact Phase 7 acceptance criteria. Briefly explain the implementation order and then proceed with the authorized local work through testing and review. Ask me only for decisions that materially affect scope or require approval.

When I need to run something myself:
Give me the complete ready-to-paste Terminal block, with actual paths and values. Separate Terminal commands from messages intended for Claude. Do not tell me merely to refer to an earlier message, replace placeholders, or reconstruct commands from screenshots.

At the end:
Report what is implemented, what tests actually passed, what remains incomplete, and the exact review/commit steps. Do not mark Phase 7 complete until its applicable acceptance criteria are met.
