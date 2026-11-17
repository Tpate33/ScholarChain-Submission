# Phase 06 SparkIT runner repair: work order (verbatim)

Received by the orchestrator on 2026-11-17 after sparkit-run-03.md (runner `NoSuchMethodException`). The text is the human's own words.

```text
Delegate the SparkIT runner repair to build-engineer. Local javap on
JUnit 4.13.2 confirms Result.getIgnoreCount(). The runner currently
calls getIgnoredCount(). Fix the lookup and verify which JUnit jar the
fork actually loads. Avoid dependency changes unless that check shows
a separate conflict.

Collect and report JUnit failures before optional result diagnostics
can obscure them. Keep the verdict fail-closed if required result data
is missing, or if tests fail, are ignored, or skip via assumptions.

Add a small archive-independent sbt check test that exercises the real
forked runner with the SparkIT JUnit classpath. Verify that a passing
fixture passes and failed or skipped fixtures are refused. Run sbt check
once and request one focused review.

Preserve run 03 as failed evidence and leave its artifacts intact.
Return the exact commit scope and approval wording for run 04. Wait
for my approval of the reviewed commit before retrying sparkIT.
```
