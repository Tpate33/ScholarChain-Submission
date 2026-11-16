# Phase 06 work orders: BE-P6-03 (sparkIT task) and FE-P6-04 (sparkIT allowlist activation) (verbatim)

Received by the orchestrator on 2026-11-16, after CR-P6-15 (APPROVE). The decisions in this text are the human's own words. The human records them in `human-gate.md`.

```text
I accept SP-P6-02 step 0 and CR-P6-15. Include the step-0 test and evidence
in the upcoming sparkIT commit unless a binding rule requires a separate
commit. Carry N1 and N2 into the next Spark change.

Have build-engineer define a bounded, opt-in sparkIT task with a configured
path to the verified dev archive and output under the Spark build target.
Keep routine sbt check portable and independent of the archive. Keep the
current local Spark and Scala setup.

Then have factory-engineer prepare the activation diff for the existing
standalone `sbt sparkIT` entry, scoped to spark-engineer. Present the exact
permission change, validation evidence, and combined commit scope for my
ratification. Wait for ratification and a fresh session before using it.

Keep Phase 5 accepted. Use the archived dev inputs without rerunning entity
resolution. Hold graph implementation and AWS execution until the task and
permission are ready.
```

## Orchestrator notes (2026-11-16)

- Separate commit for step 0: no binding rule found. CLAUDE.md and docs/agents.md say a phase "normally" uses one commit; nothing requires a separate commit for a reviewed test file. FE-P6-02 already established that no rule requires splitting a change.
- Carried forward to the next Spark change (spark-engineer): CR-P6-15 N1 (assert each Parquet part's StructType matches its labelled record schema; optionally a neutral directory name in the step-0 test) and N2 (reword the "no network" Scaladoc to "loopback listeners only; no outbound network").
- Order: BE-P6-03 first (build-engineer), then FE-P6-04 (factory-engineer), then human ratification and a fresh session, then any use of `sbt sparkIT`.
