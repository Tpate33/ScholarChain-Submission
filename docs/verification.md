# Sanitized publication candidate status

This document preserves historical observed results. The original source checkpoint
was `e08aa0938bed6cb704b8cff990a6796aa7f2af50`; its source implementation for offline
preparation was `09c3d4cda6b0935aa607dcddecfdbefab84b6ddb`. Source evidence recorded
2,889 passed, zero failures/errors, one skipped, 243 suites; see the
[offline test report](agent-runs/aws-plan-01/test-report.md). These are not new clean-clone
results.

A fresh local clone of candidate **Q `69d63538`** subsequently passed
`sbt -Dsbt.offline=true check pipelineDemoClasspath`: **2,886 passed, zero
failures/errors, four skipped, 243 suites**, exit 0, 472 seconds. Four worker CLI
relocation checks and 64 dependency-fence checks passed. All seven HTTP smoke checks
passed (six HTTP 200 responses and the expected unauthenticated 401). This used the
existing dependency cache and unchanged committed dev fixtures; it is not anonymous
public-clone evidence or a cold dependency-bootstrap result.

The three additional skips are `DecisionExportSuite`, `GroupingExportSuite` and
`SampleQueueProbeSuite`, which need generated
`modules/hadoop/target/test-work/local-pipeline/curated-a` output absent from a fresh
clone. The fourth is the existing semantic-neighbor skip. No tests were weakened and
no private labels were read. The original 2,889/one-skip result remains historical.

At Q the 939 behavior probes passed but the complete validator exited 1 on
ShellCheck imported-global/cross-file-assignment findings. The user subsequently
ratified a minimal candidate-only factory lint repair at `00bf656a`: two hooks,
five insertions and one deletion. The affected complete validator now **PASS, exit 0,
zero failures**, with **888 table + 51 fixture = 939 behavior probes passing**.
All nine shell hooks passed syntax and ShellCheck checks; JSON, role definitions,
registrations, executable bits, collision/orphan and ownership checks passed.
These are two explicit lint-only hook exceptions to the original baseline, with no
matrix, tool, guard-logic or privilege change. Application code, build definitions,
tests and manifests remain unchanged. No full application gate, HTTP replay or
Parquet scan was repeated after this repair; Q’s application results remain the
attributed results above, not new-head full-suite results. See the
[factory repair evidence](agent-runs/publication-01/factory-lint-repair.json).
Independent publication security review approved Q within its recorded scope; it
is not a new application-wide review or a universal privacy guarantee. See the
[publication record](agent-runs/publication-01/publication-record.md) for exact evidence.
Publication itself remains unauthorized; the scoped lint ratification is not general
acceptance of the candidate or permission to publish.

[Publication notes](publication.md) explain derived history and replay limitations.

# Historical update: DBLP-derived local query

The separate real-data profile now loads the accepted Phase 6 dev output through
`OpeningLoad` before starting HTTP. No test implementation or synthetic collaboration
seed is used by this profile. Storage is in-memory, the answer model is a template,
and embeddings are hashed-token stand-ins. No live AWS result is claimed.

Observed commands:

```sh
sbt services/scalafmt tests/Test/scalafmt pipelineDemoClasspath \
  'tests/testOnly scholarchain.tests.services.PipelineDemoSuite'
python3 scripts/pipeline-demo-smoke.py
```

Focused result: **3 passed, zero failures/errors**. The tests cover missing data,
a changed manifest, and an actual query/citation/explain over HTTP. The standalone
replay checks health, the source-paper-backed query, three graph neighbors,
citation, explain, idempotent replay and a missing-key 401. Every HTTP response
has the DBLP-DERIVED / NON-PRODUCTION profile header.

The query about Ailing Zeng returns three coauthor edges for the bounded 1970–2026
sample, each with joint publication count one and support from
`work:dblp:conf/aaai/ZengCZ023`. The original run ID and grouping/curated lineage are
retained; `groupingEvaluation` remains `unevaluated`. This is not an exhaustive
career-level result or a new identity-quality evaluation.

[Replay summary](agent-runs/integration-02/verification/pipeline-demo/summary.json),
[query](agent-runs/integration-02/verification/pipeline-demo/query.json),
[graph](agent-runs/integration-02/verification/pipeline-demo/graph.json),
[focused test log](agent-runs/integration-02/verification/pipeline-demo-focused-tests.txt).
The startup log's target-store labels refer to local in-memory loads, not AWS calls.

Pre-repair full gate: **PASS, exit 0** (`sbt check services/stage`).
**2,850 passed, zero failures/errors, one skipped, 233 suites**. Both the check
and refreshed service staging completed on November 23 at 12:39 PM CST. The one
skip is the existing `SemanticRetrievalCorpusSmokeSuite` neighbors case. The normal
`markTestRun` task wrote its marker; it was not edited manually.
[Archived final summary and log digest](agent-runs/integration-02/verification/real-data-full-check-summary.txt).
The original synthetic standalone demo also passed after the refreshed stage,
including its ambiguity-abstention check. Its generated output went to a separate
directory, preserving the student's earlier replay evidence.
[Regression summary](agent-runs/integration-02/verification/synthetic-regression-summary.json).
Full local log: `/private/tmp/scholarchain-real-data-full-check.log`. The earlier
results below describe the prior completion, before the real-data connection.

## Integration 02 closeout review and repair

Independent reviewer contexts approved the bounded local application/build/scripts
and production-wiring scopes after service-engineer repaired CR-INT02-A-1. A
throwing workflow factory previously bypassed failed-start cleanup; execution now
passes through the existing value-free guard and cleanup path. The preserved new
regression failed before the repair. After repair, the owner observed **14 focused
tests pass, zero failures/errors**. Reviewers did not edit code or execute tests.
See the [independent review](agent-runs/integration-02/review-report.md),
[repair evidence](agent-runs/integration-02/repair-01.md) and
[closeout test report](agent-runs/integration-02/test-report.md).

The coordinator observed the one post-repair full gate complete with **PASS, exit 0**:
`sbt check services/stage pipelineDemoClasspath` passed **2,851 tests, zero
failures/errors, one skipped, 233 suites** (services: 174 passed). The check task
took 466 seconds and completed November 23, 2026 at 3:22:28 PM CST; refreshed
services staging and the pipeline classpath completed at 3:22:29 PM. The existing
`SemanticRetrievalCorpusSmokeSuite` neighbors case remains skipped. The normal
`markTestRun` task wrote its marker; no manual edit was made. See the
[post-repair summary and log digest](agent-runs/integration-02/verification/closeout-full-check-summary.txt).
Full log: `/private/tmp/scholarchain-integration02-closeout-check.log`.
The earlier 2,850-pass result predates this repair. Human
acceptance was pending at the time of that report and was subsequently recorded
in the [original Integration 02 human gate](agent-runs/integration-02/human-gate.md).
The [historical human Git steps](agent-runs/integration-02/closeout-steps.md) are not
current candidate instructions or permission to deploy.

Two minor synthetic-smoke issues remain deferred: optimized Python can disable its
assertions, and a later failed replay can leave an older PASS summary. Recorded
normal-Python invocations and their exit/output evidence remain distinct from those
failure scenarios. The interrupted-drain shutdown concern is unverified/deferred.
No live storage, production-model or cloud readiness approval is given.

---

# Verification — Codex local completion, November 23, 2026

Repository: `/Users/taufeeqpatel/Desktop/ScholarChain`.
Remote: `https://github.com/Tpate33/ScholarChain-Project.git`.
Historical base commit: `bdbf5df` on `main`; integration-02 work was uncommitted
at the time of the following observations, before its original human acceptance.
These are observed Codex results, not an independent review or human acceptance.

## Observed successful commands

```sh
sbt 'services/scalafmt' 'services/Test/scalafmt' 'services/test' 'services/stage'
python3 scripts/demo-smoke.py
bash -n scripts/local-demo.sh
```

Services: **173 passed, zero failures/errors**; staging exited successfully.
The staged HTTP application returned health 200, query 200 with verified claims and
cited evidence, evidence 200, explain 200, graph 200, missing-key 401, replay 200
with the same query/answer, and ambiguity 200 with abstention. Every response was
checked for the NON-PRODUCTION profile header. The smoke process terminated its
own JVM. Python syntax and stage argument refusal (exit 2) also passed.

Archived response evidence: [summary](agent-runs/integration-02/verification/summary.json),
[query](agent-runs/integration-02/verification/query.json),
[evidence](agent-runs/integration-02/verification/evidence.json),
[explain](agent-runs/integration-02/verification/explain.json),
[graph](agent-runs/integration-02/verification/graph.json),
[abstention](agent-runs/integration-02/verification/ambiguous.json).
These records are synthetic fixtures, not results from AWS or a real scholarly corpus.

## Full repository gate

Command started after source formatting and launcher completion:

```sh
sbt scalafmtSbt services/scalafmtCheck services/Test/scalafmtCheck check
```

Status: **PASS, exit 0**. Completed November 23, 2026 at 11:50:29 AM CST.
The final `check` task took 470 seconds. **2,847 passed, zero failures/errors,
one skipped, 232 suites**. The skipped test is
`SemanticRetrievalCorpusSmokeSuite.(b) neighbours: researcher ID -> community summaries naming it`.
The three ER probes that skipped in the historical integration-01 human run passed
in this current environment; this does not remove their dependence on generated state.
The normal `markTestRun` task wrote its marker; no manual marker edit was made.
[Archived summary and full-log digest](agent-runs/integration-02/verification/full-check-summary.txt).
Log: `/private/tmp/scholarchain-codex-full-check.log`.
The earlier focused build log is `/private/tmp/scholarchain-codex-build.log`.
Build cache writes and loopback socket use needed scoped sandbox permission. The
initial permission failures were not application failures. No AWS call was made.

## Limits

At that earlier completion, independent review had not happened. The local scopes
have since been reviewed as recorded above. The later sanitized Q fresh-local-clone
gate is recorded at the top of this page. Still not verified: anonymous public cloning, live cloud deployment,
real model inference, corpus-scale results, load/elasticity measurements, video,
final release acceptance. The historical integration-01 human result is separate:
2,817 passed, four skipped, zero failures/errors. Current results must not be
substituted for an unperformed human final acceptance.

## Subsequent local cloud preparation

After the full Scala check, only the EMR submission wrapper and its shell regression
check changed: an explicit built-in Hadoop codec list avoids inherited unavailable
EMR LZO codecs. `bash scripts/emr/check-submit-args.sh`, ShellCheck and Bash syntax
passed; a temporary wrapper without the override failed the regression check as
expected. No Scala source changed in this preparation, and the full Scala gate
was not repeated. All three existing Terraform stacks validated locally.
See [preparation details](cloud-preparation.md) for the codec probe, exact limits
and remaining production gaps. The user also supplied screenshots of their own
successful demo replay and manual HTTP 200 query on November 23.
