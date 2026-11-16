# Phase 5 dev pipeline run 01 (human Terminal run; evidence transcription)

Recorded by the orchestrator, 2026-11-13. Not a human-gate record. Dev split only; no held-out run.

## Human report (verbatim transcription)

```text
My dev Terminal run of
sbt "hadoop/Test/runMain scholarchain.hadoop.local.ErSplitPipelineMain dev"
completed successfully on Nov 13 at 5:15:03 PM Central, exit 0.

Timing: 15.513 s wall, 20.15 s user, 2.91 s system.
Pipeline elapsed: 10.957 s. Output: dev/attempt-1, status=complete.
Distinct pairs: 374 total, 296 researcher and 78 work.
Output read-back matched the counters. Cap 4 dropped zero members in
either kind. Normalization accepted all 98 inputs, including all six
Crossref records, with 0 rejected, 2 flagged, 2 duplicates, 2 conflicts.

Use this result to propose the timeout and finalize the block cap.
Bring me the consolidated amendment and exact held-out command for
review before activation or a held-out run.
```

## Context

- Code: working tree at HEAD 36ffcf9 plus uncommitted HD-P5-02, core and entity-resolution changes (DE-P5-03). The manifest's `codeVersion` is 36ffcf9; not evidence-grade until committed and re-run.
- Matches the in-suite dev measurement in HD-P5-02 (374 = 296 + 78; cap 4 drops 0; largest level-0 group 4 for both kinds).
- Output: `modules/hadoop/target/local-pipeline/er-candidates/dev/attempt-1/` (git-ignored).
- Measured wall time 15.513 s for the full sbt invocation (already compiled), pipeline 10.957 s.
