# Phase 06 full sbt check and sparkIT run 07 (orchestrator work order under the consolidated authorization)

- Authority: prompt-sp-p6-03-consolidated.md ("then the required sbt check, full local integration"), prompt-sp-p6-03-audit-review.md ("focused checks during development"; full check after a code change).
- Purpose: the one agent full `sbt check` after the last code change (steps 2A-2C, DE-P6-03, BE-P6-07), then the full local integration run of the completed tree, before the consolidated review.
- Code: uncommitted working tree on HEAD c2d9a82; the report records `git status --porcelain` and `git diff --stat` at run time.
- sbt check expectations: exit 0; sparkAnalytics 202; core 621; SparkIT compiled; verifySparkItGuards and verifySparkItRunner PASS. The call is expected to be moved to the background at 180 s; the marker event is reported with exact paths and output (human direction).
- sparkIT expectations: sp-p6-03-step-2c-report.md "IT expectations" plus the run-06 counts (edges 259; metrics 176; nodes 117/45/143/64/60/30; AUTHORED 143, RESOLVES_TO 143, COAUTHORED_WITH 259); new attempt (expected attempt-5); attempts 1-4 untouched; no `sbt clean`; within 120 s.
