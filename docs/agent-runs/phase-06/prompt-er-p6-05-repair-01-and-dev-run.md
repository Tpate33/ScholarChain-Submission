# Phase 06 work orders: ER-P6-05 repair 01, durable dev run, DE-P6-02 (verbatim)

Received by the orchestrator on 2026-11-15, after CR-P6-07 (APPROVE). The approvals and acceptances in this text are the human's own words. The human records them in `human-gate.md`.

```text
I approve the four reviewed ER-P6-05 additions. Accept the grouping rules for bounded use, with grouping quality still unevaluated. Phase 5 remains accepted and its pairwise policy and evidence stay unchanged.

First, delegate the CR-P6-07 provenance repair to entity-resolution-engineer. Reuse the decision exporter's provenance check where appropriate. Verify that curated candidates, works, and profiles belong to the recorded curated run before publishing grouping output. Add a focused mixed-run regression. Include the feature/blocking version checks and clarify that group-size diagnostics exclude unresolved records if both changes fit the same repair. Run sbt check once and request a review of the repair diff only.

Then check whether an existing authorized command can produce a durable dev decision and grouping run. If none exists, have build-engineer add a bounded dev-only task and factory-engineer prepare the narrow allowlist change, one owner at a time. Keep held-out inputs and outputs out of scope. Present the exact permission change for my ratification before activating or running it.

Use the verified sample output for DE-P6-02 contract work wherever it is sufficient. Do not make a durable dev run a prerequisite for that work unless there is a specific technical dependency. Keep Spark implementation and AWS execution out of this work order.
```

## Orchestrator check: is there an existing authorized command for a durable dev decision and grouping run? (2026-11-15)

No. The active entity-resolution-engineer rows are `@GIT`, `@SBT-ALL` (`sbt check`, `sbt clean check`, `sbt compile`, `sbt Test/compile`, `sbt test`), own-module `sbt <SBT:entityResolution>` tasks, and `sbt goldSetEvalDev`.
- `goldSetEvalDev` writes only evaluation diagnostics under `target/local-runs/dev/`; it does not publish decision or assignment runs.
- A direct `runMain` is refused: probes.tsv line 565 expects `sbt entityResolution/runMain ...` to be blocked with `no-allowlist-entry`.
- The committed-sample probe inside `sbt check` writes only under `modules/entity-resolution/target/test-work/`, which each test run deletes.

A durable dev run therefore needs a new build-engineer task plus a factory-engineer allowlist row, ratified by the human before it is activated or run.
