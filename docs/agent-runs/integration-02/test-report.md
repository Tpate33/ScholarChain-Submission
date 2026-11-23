# Integration Round 02 test report

Scope: local checkpoint, base `bdbf5df02133a0cb5f7493fcd1fd08b98469db60`, dirty
working tree. Results are Codex/owner observations, not independently executed
reviewer tests or a human Terminal run.

## Completed evidence

- Before review repair: `sbt check services/stage` passed 2,850 tests, zero
  failures/errors, one skipped, 233 suites. Preserved in
  `verification/real-data-full-check-summary.txt`.
- DBLP-derived standalone replay passed query, original source paper, three graph
  neighbors, cited evidence, explain, replay and authentication. The answer keeps
  real Phase 6 run/provenance IDs. `verification/pipeline-demo/summary.json`.
- Original synthetic replay passed separately, including ambiguous abstention.
  `verification/synthetic-regression-summary.json`.
- SV-INT-04 before-fix regression: expected failure, 1 failed / 0 passed. It proves
  the new test detects the reviewed bug. `verification/workflow-factory-before.txt`.
- SV-INT-04 after-fix focused gate: 14 passed / 0 failed / 0 errors (10 launcher,
  four workflow-bound tests). `verification/workflow-factory-after.txt`.
- Prior offline ShellCheck/Bash and existing Terraform validation results are in
  `verification/cloud-preparation-summary.json`; these are not live cloud checks.

## Final post-repair gate

Command: `sbt check services/stage pipelineDemoClasspath`.
Status: **PASS, exit 0**. **2,851 passed, zero failures/errors, one skipped,
233 suites**. The check task completed November 23, 2026 at 15:22:28 CST in
466 seconds; service staging and local classpath preparation completed at 15:22:29.
Services: 174 passed. The one skip is the existing semantic corpus neighbors case.
The normal markTestRun task wrote its marker; no manual marker edit occurred.
[Archived summary and full-log digest](verification/closeout-full-check-summary.txt).
Full local log: `/private/tmp/scholarchain-integration02-closeout-check.log`.
This is the one full rerun justified by the substantive major lifecycle repair.
No full rerun was performed merely for documentation or acceptance polish.

## Limits

Real data means the bounded, existing DBLP-derived dev artifact, not a new ingestion
or full-corpus result. Stores are in-memory; answer synthesis is templated;
embeddings are stand-ins. No live Neptune, DynamoDB, S3 Vectors, Secrets Manager,
production model, successful cloud Spark execution or elasticity result is tested
or accepted here. The preserved known semantic smoke skip is not counted as a pass.
Phase 11 adversarial files remain unchanged; the new PipelineDemoSuite is additive.
Full logs and summaries record failure/skip counts without reclassifying them.
