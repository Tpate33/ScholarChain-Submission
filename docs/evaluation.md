# Evaluation and evidence

Results are separated by observation source and scope. Local contract tests are not
measurements of deployed AWS functionality or model quality.

| Result | Evidence | Scope |
|---|---|---|
| Phase 5 entity-resolution evaluation accepted | `agent-runs/phase-05/human-gate.md`, heldout evaluation records | Historical human-reviewed evidence; not rerun here |
| 259 edges, 176 metrics, 112 temporal changes, 28 matching datasets | `agent-runs/phase-06/human-gate.md` | Historical local Spark results |
| Integration 01: 2,817 passed, 4 skipped, zero failures/errors | `agent-runs/integration-01/human-gate.md` | Human Terminal run, 578 s |
| Final post-repair Integration 02 check: 2,851 passed, 1 skipped, zero failures/errors | [Closeout test report](agent-runs/integration-02/test-report.md) | Coordinator-observed run, 233 suites, services 174 passed; check 466 s; services staging and pipeline classpath preparation passed |
| Pre-repair real-data connection check: 2,850 passed, 1 skipped, zero failures/errors | `verification.md`, archived check summary | Observed Codex run, 233 suites; predates CR-INT02-A-1 lifecycle repair |
| CR-INT02-A-1 repair focused checks: 14 passed, zero failures/errors | `agent-runs/integration-02/test-report.md` | Owner-observed launcher/bounds suites; preserved pre-fix regression failed |
| Earlier integration 02 check: 2,847 passed, 1 skipped, zero failures/errors | `verification.md`, archived check summary | Observed Codex run; 232 suites, check task 470 s |
| Earlier Integration 02 services: 173 passed, zero failures/errors | `verification.md` | Observed by Codex before the cleanup regression was added |
| DBLP-derived standalone HTTP replay passed | `verification.md`, real-data replay JSON | Real bounded Phase 6 data; three coauthors tied to `conf/aaai/ZengCZ023`; in-memory stores/template model |
| Synthetic staged HTTP replay passed | `verification.md`, replay JSON | Synthetic loopback application, not live AWS |

The synthetic HTTP replay asserts a verified evidence-backed response, a cited evidence lookup,
explain response, graph neighbor result, missing-key 401, same-key idempotent replay,
and abstention on an ambiguous entity. It does not evaluate learned semantic
retrieval, real model answer quality, population coverage, concurrency scaling,
performance percentiles, or a cloud datastore.

Three historical ER probes skip depending on leftover pipeline output; a semantic
corpus smoke test is also skipped in the recorded full integration-01 run. Their
absence is not a pass. Tests that require generated state should eventually create
isolated fixtures. A historical Spark reproducibility test had a timing-dependent
failure before passing in the human run. Full current check status is recorded
separately in verification.md.

No elasticity experiment or live scale-out/scale-in result is available. Experiment
plots and confidence intervals cannot honestly be supplied without measurements.
Threats to validity include tiny synthetic examples, stale external archives,
conditional skips, missing live model/store verification, and single-host execution.

The DBLP-derived replay also checks that the three coauthor edges have one joint
publication each and identify the accepted DBLP source paper. This is an observed
end-to-end data-to-query path on the bounded dev artifact. The retained grouping
lineage remains marked unevaluated; no new held-out identity metric, corpus-size
claim or live-cloud verification is inferred.

Independent code reviewers approved the local checkpoint scopes after the cleanup
repair; they did not independently execute tests. The coordinator-observed post-repair
full gate passed and is recorded in the [closeout test report](agent-runs/integration-02/test-report.md).
Human checkpoint acceptance remains pending. The two minor synthetic-smoke issues
and interrupted-drain concern are retained in the review record; none is presented
as a new passing behavior or waived for deployment.
