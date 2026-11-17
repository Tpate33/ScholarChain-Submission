# Phase 06 work order SP-P6-02 step 1B (verbatim)

Received by the orchestrator on 2026-11-17 after sparkit-run-04.md. The decisions in this text are the human's own words. The human records them in `human-gate.md`.

```text
I accept sparkIT run 04 at f834c60 as passing evidence for SP-P6-02
step 1A. Preserve the run report, JUnit result, and integrity manifest.
Keep run 03 recorded as failed. Include the run 04 evidence in the next
reviewed Spark commit.

Record that runs 03 and 04 produced different Parquet bytes and that
row-level equivalence has not been established. If an existing permitted
read-only route can compare the saved outputs, compare the schema and
complete row sets, preserving duplicate counts. Do not rerun sparkIT
solely for this comparison. If comparison is unavailable, carry it forward
as a logical reproducibility test. If the rows differ, report the exact
difference before building on the edge output.

Proceed with the next local Spark work order for researcher collaboration
and team-size metrics. Use the approved contracts, archived dev inputs,
and graph foundation. Include hand-checkable cases with unresolved
coauthors. Run the required checks once and get one focused review.
Keep Phase 5 accepted and AWS execution on hold.
```

## Orchestrator handling

- Comparison: no existing permitted read-only route decodes the saved Parquet outputs (recorded in sparkit-run-04.md). Carried forward as a logical reproducibility test in step 1B.
- Step 1B scope, from work-order-draft-sp-p6-02.md step 1 job 3: ResearcherMetrics v2 (P, C, T, A, M, W; unresolved occurrences in separate fields; identity confidence with explicit unknown) under the approved core contracts, on the step-1A graph foundation.
- Archive-backed execution of any new IT check needs a separate human approval of one `sbt sparkIT` run at the reviewed commit.
