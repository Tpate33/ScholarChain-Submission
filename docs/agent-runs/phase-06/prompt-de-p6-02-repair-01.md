# Phase 06 work order DE-P6-02 repair 01, and Spark planning decisions (verbatim)

Received by the orchestrator on 2026-11-16, after CR-P6-13 (REQUEST_CHANGES). The decisions in this text are the human's own words. The human records them in `human-gate.md`.

```text
Approve one DE-P6-02 repair for CR-P6-13 findings 1–4. Keep it in modules/core.

1. Validate the manifest digest against the actual analysis settings. Test that changing an analysis setting changes the run ID and that a mismatched digest is refused.
2. Bind graph and decision inputs to the correct upstream run provenance. Require exactly one canonical team per counted work.
3. Include the sorted path-to-role mapping in the new store load ID so different roles yield different IDs.
4. Define deterministic selection of up to K shortest paths per configured pair and window. Use a stable order on the canonical researcher ID sequences. Check ties on hand-checkable fixtures without requiring production Spark to enumerate every shortest path.

Carry findings 5–6 forward. Record the separate upstream settings-digest inconsistency for a versioned fix before the next entity-resolution rerun. Preserve the archived run and Phase 5 acceptance. Run sbt check once and get a focused review of the repair diff.

For the first Spark work order, use a bounded fixture integration test within sbt check if the existing build supports it. Use an explicit, versioned list of benchmark pairs for stored paths. Use documented label propagation for communities, cross-community degree for the bridge measure, and k-core peeling for coreness. Defer sampled Brandes. Cross-community degree already satisfies the course requirement.

After the review, return the exact contract commit scope and the smallest build compatibility check needed for local Spark. Start with the existing Scala 3 project and Spark 2.13 artifacts. Treat EMR runtime compatibility as a separate gate. Use the archived dev run for local integration and do not re-pin or rerun entity resolution.
```

## Recorded carry-forwards and known issues (orchestrator, 2026-11-16)

- CR-P6-13 finding 5 (nit, validator rebuilds models in each check; fix before sample scale) and finding 6 (nit, `runtime.maxPathHops` vs `analysis.maxPathHops` not cross-checked): carried forward, owner domain-engineer.
- Upstream settings-digest inconsistency (CR-P6-13 open question 5): `graph.windows`, `bridge.*`, `community.algorithm` and `community.maxIterations` are inside the base config digest, which feeds the entity-resolution decision and grouping digests, while the new `community.randomSeed` and `analysis.*` keys are analysis-scope only. Changing a graph or community setting therefore changes upstream ER run identities although ER does not use them. Human decision: record now; make a versioned digest-scope fix (domain-engineer, with entity-resolution and build impact assessed) before the next entity-resolution rerun. Not changed in this repair; the archived run's digests (61655dc1…, 48484593…) stay valid.
- Spark planning decisions: bounded fixture integration test inside `sbt check` if the build supports it; explicit, versioned benchmark-pair list for stored paths; documented label propagation for communities; cross-community degree as the bridge measure; k-core peeling for coreness; sampled Brandes deferred; start from the existing Scala 3 project with Spark `_2.13` artifacts; EMR runtime compatibility is a separate gate; local integration uses the archived dev run; no ER re-pin or rerun.
