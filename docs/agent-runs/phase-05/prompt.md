# Phase 5 (main) work order given to the orchestrator

Received 2026-11-04. Verbatim:

```text
Work in /Users/taufeeqpatel/Desktop/ScholarChain.

Phase 4 is accepted. The Phase 5 Unicode prerequisite is accepted in
commit 127819b, with closure evidence in 1bc5382. Human-run clean-tree
verification passed 443 tests. The working tree is clean.

Read CLAUDE.md, docs/agents.md, README.md Phase 5, and the Phase 5
prerequisite evidence before proceeding. Preserve the accepted Unicode
limitations and use er-blocking.v2 / er-features.v2.

Begin the main Phase 5 work through the required owning agents.
Preserve independent testing and review. Gold labels must be genuinely
human-reviewed; do not fabricate labels or evaluation results.
Do not commit, push, deploy, or run AWS operations.

Use the entity-resolution-engineer agent to implement the ScholarChain entity-resolution subsystem against the Phase 3 contracts and Phase 4 MatchCandidate records.

Required behavior:
- Exact identifier rules for DOI, arXiv ID, ORCID, OpenAlex ID, Semantic Scholar ID, and repository URL.
- Normalization for Unicode, punctuation, whitespace, title markup, initials, name order, and DOI prefixes.
- Feature functions for researcher-name similarity, initials, author order, coauthor overlap, affiliation compatibility, ORCID or external IDs, title similarity, year distance, venue compatibility, citation-neighborhood evidence when available, and source reliability.
- A versioned scoring policy.
- Separate thresholds for accepted, rejected, and unresolved results.
- Conflict detection when authoritative identifiers disagree.
- LLM adjudication only for a bounded unresolved band.
- Strict JSON schema for LLM adjudication with accept, reject, and abstain outcomes.
- No LLM call may alter source records or create an identifier not present in the candidates.
- Cache LLM adjudications by input hash, prompt version, model version, and policy version.
- Produce accepted, rejected, unresolved, and conflict outputs.
- Generate a review queue for human-labeled evaluation examples.

Evaluation requirements:
- At least 200 manually reviewed candidate pairs.
- Precision, recall, F1, confusion matrix, and coverage, reported separately for researcher-identity resolution and work resolution when both are implemented.
- Metrics by match path: exact ID, deterministic bibliographic, probabilistic, and LLM-adjudicated.
- Error analysis with at least ten false positives and ten false negatives when available.
- Threshold sensitivity analysis.

Run sbt check and the gold-set evaluation.
Report the policy version, feature definitions, thresholds, model and prompt versions, confusion matrix, unresolved rate, and representative errors.
```

## Orchestrator decomposition (recorded before routing)

Known constraints at the start of the phase:

1. Human gold labels have no owning path (gap G9: `UNASSIGNED`). No agent may write them.
2. `sbt goldSetEval` allowlist row is `inactive:B10-alias-pending`; activation needs a
   `build-engineer` alias and a ratified `factory-engineer` allowlist change.
3. Phase 4 local pipeline reported 119 MatchCandidates over committed samples, which may be
   fewer than the 200 reviewed pairs required.
4. No AWS or live LLM endpoint may be used; adjudication is a port with deterministic test doubles.

Planned order: ER-P5-01 (entity-resolution-engineer: implementation, review-queue generator,
evaluation tooling) -> TE-P5-01 (test-engineer) -> RV-P5-01 (code-reviewer) -> human labeling
on a ratified path -> gold-set evaluation run -> final review and human gate.
