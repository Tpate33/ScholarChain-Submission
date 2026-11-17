# Phase 06 work order SP-P6-02 step 1A repair 01 (CR-P6-19 F1-F6) (verbatim)

Received by the orchestrator on 2026-11-16 after CR-P6-19 (REQUEST_CHANGES, cr-p6-19-review.md). The decisions in this text are the human's own words. The human records them in `human-gate.md`.

```text
Approve one SP-P6-02 step 1A repair for CR-P6-19 findings F1–F6.
Delegate it to spark-engineer within modules/spark.

Add targeted tests for each missing run-chain refusal and verify that
the input limits apply before decoding. Bound driver-side reads by
bytes as well as rows, and refuse the archive if the required directory
listing fails.

For publication, make sure a run that fails verification cannot be
accepted as complete. Define what happens to staging after a failed
move, and refuse an existing destination without replacing it. If the
filesystem API cannot guarantee no replacement under concurrent writers,
state that limitation and propose the smallest safe remedy for this
local runner. Correct the check-order description without adding a
test just for the wording.

Run one sbt check after the repair and get one focused re-review. If
the verdict is APPROVE, confirm the build still matches e8c6ecc and
have spark-engineer run standalone sbt sparkIT once. Record the observed
edge counts, JUnit result, and output integrity evidence. Keep Phase 5
accepted and hold the next Spark step and AWS work.
```
