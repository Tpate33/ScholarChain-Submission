# Phase 06 work order DE-P6-01 (verbatim)

Received by the orchestrator on 2026-11-15, after decision package 01 (docs/agent-runs/phase-06/decision-package-01.md). The approvals and clarifications in this text are the human's own words. The human records the decisions in `human-gate.md`; the orchestrator does not write that file.

```text
Approve DE-P6-01 for domain-engineer, limited to C1–C5 in modules/core. Use the decision package as the work order with these clarifications.

Keep er-grouping.v1 separate from er-policy.v8. The contracts must preserve match decisions and provenance, allow a supported singleton to have an ID without a pairwise match, and preserve unresolved mentions without assigning them a researcher ID. Support deterministic work representatives and one author list per counted work. Before implementing, check the existing ID constructors and authorship/config compatibility in the relevant core files. Preserve existing wire readers and config behavior where applicable.

Use DBLP-backed canonical works for the Phase 6 graph and retain other works for enrichment. Keep preprint-to-publication linking deferred. For the later Spark work order, count unresolved coauthor occurrences in T_r, A_r and M_r and report them separately. Use identified coauthors for C_r, graph edges and W_r.

For C5, implement the shared integrity manifest and pure verifier. Required file checks must detect a truncated copied run. Leave file I/O to the producing and consuming agents.

Keep Phase 5 accepted. This contract work does not change its pairwise policy, features, thresholds or evidence. Phase 5 evaluation supports its existing pairwise claims only. Do not access held-out labels or evaluation output.

Add focused contract tests, run sbt check once, and have code-reviewer review the contract change. Return the results and the next grouping work order for approval. In that work order, spell out B3's group-wide vetoes and show when they would override an accepted link. In particular, verify that DBLP homonym suffixes are compared in the correct name context. Do not implement grouping or start Spark or AWS work yet.
```
