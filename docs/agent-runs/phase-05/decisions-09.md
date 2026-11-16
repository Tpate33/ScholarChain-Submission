# Phase 5 human decisions after ER-P5-10 (transcribed by the orchestrator)

Received 2026-11-11 in response to the ER-P5-10 report and open-findings-01.md. This is a verbatim transcription, not a human-gate record.

```text
1. Yes. If a shared arXiv ID leaves a pair unresolved because the
   publication stages differ or are uncertain, a shared DBLP key or
   Scopus EID must not override that result. Preserve authoritative
   conflict handling and add regression coverage for both record keys.

2. Batch the required evaluation fixes before the next full check.
   Include threshold sensitivity so it respects the policy's acceptance
   gates, and report the effective thresholds and prompt/model versions.
   Once the policy version is settled, route the stale version assertions
   to test-engineer and preserve the behavioral regression tests.

3. Proceed with the acquisition repairs and local fixture verification
   now. Bring me the reviewed download command for a separate decision.

Once the repairs are ready and no test process is active, tell me when
to run one foreground sbt check in my regular Terminal.
```

## Routing

| Item | Work order |
|---|---|
| 1. arXiv cross-stage trigger blocks dblp-key / scopus-eid override; reason code; regressions for both keys; T1 conflicts unchanged | ER-P5-11 (entity-resolution-engineer) |
| 2. Evaluation batch: RV-P5-04 m1 (sensitivity respects acceptance gates), m6 (effective thresholds, prompt and model versions in output; external config applied), N3, N4; RV-P5-05 #4, #5; RV-P5-06 nit 2 | ER-P5-11 (same order, so the policy version is settled once) |
| 2. Stale version assertions in tests/** | test-engineer, after ER-P5-11 settles the version; behavioural regressions preserved |
| 3. Acquisition repairs (RV-P5-02 #3, #5 schema-side, #6, #8, nits 9-12; RV-P5-03 N1-N6) and local fixture verification | dataset-engineer, after ER-P5-11 (sequential Bash use) |
| 3. Reviewed download command | code-reviewer after the dataset repairs; presented to the human for a separate decision (download not authorized by this instruction) |
| Full check | the human runs one foreground `sbt check` in a regular Terminal when the orchestrator reports that repairs are ready and no test process is active |
| Not covered | RV-P5-04 m7, m8, N1, N2; RV-P5-05 #2, #3; arXiv rule on ACCEPTED outcomes (RV-P5-06 nit 1 remainder); TE-P5-01 comma-form homonym; FE-P5-01 ratification; labeling |
