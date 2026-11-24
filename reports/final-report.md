# ScholarChain: implementation and evaluation report

Taufeeq Patel — tpate33@uic.edu
Working submission report, November 23, 2026. Deadline: November 25, 2026, 11 PM CST.

This report describes implemented artifacts and observed results, including failures.
It does not claim that all assignment acceptance criteria are met. The runnable local
API demonstration now loads verified, real DBLP-derived Phase 6 dev outputs into
in-memory stores and uses a template answer synthesizer and hashed-token stand-in
embeddings. The older synthetic fixture profile is preserved. Neither profile is a
deployed graph-plus-vector RAG application or a real-model evaluation.
Subsequent local preparation adds concrete AWS loading/model/key-source connections
and an operational loader around the existing `OpeningLoad`. Its offline tests do
not establish live deployment; current evidence and pending gates are tracked in
the [production preparation record](../docs/agent-runs/production-connections-01/status.md).
See the [deliverable index](../docs/deliverable-index.md) for unmet requirements and
[verification record](../docs/verification.md) for current command results.
This sanitized publication candidate is unpublished and has not received new human
acceptance. [Publication notes](../docs/publication.md) distinguish original accepted
commits from derived history, unchanged demo fixtures and candidate verification.
A fresh local clone of candidate Q `69d63538` passed 2,886 tests, zero failures/errors,
four skips, 243 suites and all seven HTTP smoke checks. Q’s full agent validator
initially exited 1. After a user-ratified candidate-only lint repair to two hooks
at `00bf656a`, full validation passes with 939 behavior probes passing. Application
code, build, tests and manifests are unchanged; no full-suite, HTTP or Parquet rerun
is claimed after the repair. See publication notes and the
[repair evidence](../docs/agent-runs/publication-01/factory-lint-repair.json).
Anonymous public cloning remains unverified; publication remains unauthorized.

## 1. Problem and motivation

Publication count alone does not describe collaboration breadth, repeated teamwork,
or the researchers who connect otherwise separate groups. ScholarChain separates
identity resolution, graph computation and evidence-backed explanation so that a
reported collaboration measure can be traced to records and a time window.

## 2. Research questions and metrics

The first question concerns broad collaboration networks and large author teams:
publication count, distinct coauthors, repeated coauthor occurrences, average and
maximum coauthors per paper, and works responsible for each maximum. The second
concerns connectivity: direct edges, shortest paths, connected components and
component sizes. The third concerns bridges between communities: community labels,
cross-community connectivity, a defined bridge measure and coreness. These measures
must retain their time window and identity uncertainty. The bounded real-data demo illustrates these evidence paths; it cannot establish
population-level scientific findings.

## 3. Dataset selection and licenses

The selected portfolio is DBLP, OpenAlex, ORCID and Crossref. Dataset-specific
sources, license statements, snapshot identifiers, verification dates and unresolved
items are maintained in the [manifest](../datasets/dataset-manifest.yaml), with
sample attribution under `datasets/samples/`. License claims here refer to that
record; they were not independently reverified during the Codex takeover. The
committed field-level sample excerpts are distinguished from downloaded raw bytes.
The current pipeline demo reuses the verified Phase 6 dev lineage: 41 DBLP, 43
OpenAlex and six Crossref normalized work records. It does not rerun acquisition,
Hadoop, ER or Spark at startup. It does not establish three additional independently
ingested enrichment datasets or the required corpus scale. The older synthetic
profile remains a separate explicit fixture.

## 4. Agentic software factory architecture

The historical factory uses a Claude orchestrator, specialist role definitions,
disjoint ownership, deterministic hooks, test reports, reviewer findings and human
acceptance records. Phase evidence resides under `docs/agent-runs/`. Contracts and
module dependency rules constrain generated code. On November 23 the student
requested Codex implementation takeover after the Claude budget limit. That
completion work is disclosed rather than represented as another independent
Claude specialist/reviewer cycle.

## 5. Authority and ownership

The student controls approval, submission and cloud spending. The takeover preserves
existing uncommitted integration-02 work. AGENTS.md, skills and hooks were unchanged
during that historical takeover. The later sanitized candidate has the two explicitly
ratified lint-only hook exceptions described above; the original repository is unchanged.
No AWS mutation, paid model call, commit or push is authorized as part of this local
completion. Direct Codex edits and checks do not constitute independent review.
Separate read-only reviewer contexts subsequently inspected the local scopes and
approved them after an owning service-engineer repair; reviewers neither edited
the implementation nor independently executed tests.

## 6. Human gates and repair loops

Historical phase folders record prompts, repairs, reviews and acceptance decisions.
Integration 01 was accepted for local scope, with cloud and production residuals
explicitly retained. Integration 02 was accepted as a local checkpoint against
implementation `1079eac`, recorded in acceptance commit `19c5b1e`. The subsequent
production connections were accepted in the original history; offline AWS preparation
was subsequently accepted against original implementation `09c3d4c`. These historical
decisions do not ratify rewritten candidate commits. See the publication commit map. A stale
Claude test marker caused repeated stop-hook refusals during handoff; repeating the
same refusal did not add verification. Actual check output, rather than a manually
edited marker, is the basis for the current verification record.

## 7. Hadoop pipeline

`modules/hadoop/` implements the initial record-processing boundary and versioned
outputs. Hadoop provides distributed parsing and preparation before graph analytics;
it is not interchangeable with a runtime graph query. Historical local evidence is
retained in Phase 4. A successful complete required EMR pipeline is not established
by this report or by the demo.

## 8. Entity resolution and evaluation

`modules/entity-resolution/` separates researcher/work identity from display names
and retains uncertainty rather than asserting that matching names imply identity.
Phase 5 contains the historical evaluation and human gate. Those results have not
been recomputed for a new production corpus here. The original synthetic demo deliberately contains ambiguous John Smith identities
and verifies abstention. The real-data demo uses loaded identity links and retains
the source grouping evaluation status (`unevaluated`) in provenance. Neither replay
is a new identity-resolution accuracy estimate.

## 9. Spark collaboration analytics

Spark consumes prepared records to compute temporal weighted graph outputs, metrics,
paths, communities and bridge-related evidence. Distributed computation belongs in
this batch layer rather than in an answer model. Phase 6 records local results of
259 edges, 176 metric rows, 112 temporal changes and 28 matching datasets. These
counts are historical local output, not evidence of successful cloud execution.

## 10. Storage architecture

Storage ports separate application logic from AWS adapters. S3 holds objects and
provenance artifacts, Neptune is the intended graph store, and DynamoDB is intended
for durable operational state. Integration 01 added AWS adapters and durable state
contracts with local tests. Live store loads and their production behavior remain
unverified. Subsequent preparation adds concrete loader-only S3 conditional writes,
Neptune parameterized graph writes and S3 Vectors Get/compare/Put. The latter requires
one externally serialized loader because its writes are not conditional across
processes. The existing demo still instantiates in-memory implementations and loses
state on exit.

## 11. S3 Vectors and optional search

The intended semantic boundary is a vector reader returning source pointers that can
be included in evidence. Adapter code does not prove a populated S3 Vectors index.
The original synthetic demo has no semantic documents. The DBLP-derived profile
loads 249 derived vector/context records using the explicitly non-production
hashed-token embedder. Their existence is not evidence of learned semantic relevance;
the observed coauthor query is checked against graph evidence and its source paper. Optional OpenSearch is not needed for
the local demonstration and no functioning deployment is claimed.

## 12. Runtime analysis agents

The collaboration workflow coordinates typed planning, identity resolution, graph
retrieval, evidence construction, synthesis and claim verification. Keeping numbers
and paths in typed evidence allows verification before returning claims. In the demo,
`NonProductionTemplateSynthesizer` formats the response: it is not an LLM. Production
composition now has concrete Bedrock Converse answer and Titan Text Embeddings V2
adapters, with typed model/version/dimension and request bounds. They are tested
with injected SDK responses, not real provider output. Missing production settings
fail closed; neither demo implementation is a production fallback.

## 13. Services and API

The service module exposes query, job, evidence, explain, graph, health and metrics
routes through versioned contracts. Both local entry points bind only to loopback,
print NON-PRODUCTION banners and identify their profiles in HTTP headers. The
[API reference](../docs/api-reference.md) describes routes and the public demo key.
The real-data replay exercises a synchronous query about Ailing Zeng, three graph
neighbors, citation lookup, explanation, missing-key rejection and idempotent replay.
Its three coauthor edges each cite `work:dblp:conf/aaai/ZengCZ023`, with one shared
publication in the bounded sample. The original synthetic replay additionally checks
ambiguous abstention.

## 14. Terraform architecture

The existing Terraform stacks concern historical bootstrap/base/EMR work. Terraform
is intended to make resource changes reviewable and repeatable rather than relying
on console edits. Complete runtime services, storage deployment, model integration
and elasticity infrastructure are not delivered by the current local demo. No new
plan, apply or cloud inventory was run during this takeover.

## 15. Build and reproduction automation

Use JDK 17, the repository's sbt version and Python 3. Run
`bash scripts/pipeline-demo.sh --stage`, followed by `python3 scripts/pipeline-demo-smoke.py`.
The local-only classpath includes storage’s optional Parquet readers; normal service
packaging keeps its dependency exclusions. The wrapper selects the explicit real-data main and
removes inherited AWS environment variables. The old synthetic stage still uses
sbt-native-packager and remains available separately. The replay uses only Python's standard
library, bypasses ambient HTTP proxies for loopback and terminates its own child.
`sbt check` is the repository-wide gate. See [demo instructions](../docs/demo-script.md)
and [runbook](../docs/runbook.md); no clean-checkout reproduction is claimed yet.

## 16. Security and threat model

The [threat model](../docs/threat-model.md) covers credential disclosure, unauthorized
routes, unsupported claims, malicious input and accidental cloud mutation. The demo
key is intentionally public and gives no production security assurance. Loopback
binding limits exposure but is not user isolation. Production secret retrieval,
rotation, TLS, deployment IAM and live security verification are outstanding.

## 17. Elasticity experiments

No scale-out or scale-in experiment has been performed for a deployed runtime.
There are no measured scaling graphs, load percentiles or elasticity conclusions.
Completing documentation does not satisfy this experimental requirement.

## 18. Retrieval and answer evaluation

The current replay verifies actual HTTP behavior, the accepted analysis run ID,
three coauthor edges and their DBLP source paper on verified dev artifacts. An API
integration test covers the same data path plus startup refusal for missing/changed
artifacts. The original synthetic replay is retained, and services tests cover
additional contracts and failure paths.
The pre-repair repository gate passed 2,850 tests with zero failures/errors and one
existing skip across 233 suites; service staging and both standalone demo replays
also passed. These observations are recorded in the verification document.
Independent review then found a throwing-factory cleanup defect. The owner repaired
it; the new regression failed before repair and the focused launcher/bounds suites
passed 14 tests afterward. The coordinator then observed the one post-repair full
gate pass **2,851 tests, zero failures/errors, one skipped, 233 suites**; services
passed 174 tests. The check took 466 seconds; refreshed services staging and the
separate local pipeline classpath also completed. This result supersedes the earlier
2,850-pass validation for the repaired code; it is archived in the
[closeout test report](../docs/agent-runs/integration-02/test-report.md).
Neither check measures learned retrieval relevance, LLM factuality, real-corpus
coverage or deployed performance. Historical and current results are separated in
[the evaluation record](../docs/evaluation.md).

After that accepted checkpoint, owners reported focused offline production-connection
results: 11 storage writer tests, 23 workflow/load/adapter configuration tests,
42 existing configuration regressions, a subsequent five-test producer-config run,
and 18 services tests passed. These separate runs are not a repository-wide total.
A cross-module test passed using the accepted DBLP artifacts, existing `OpeningLoad`,
simulated Bedrock responses and in-memory stores, verifying three claims, citation
support, audit persistence and exactly-once model closure. No provider call was made.
The operational loader separately reads the archived producer config (at most
64 KiB, no ambient overrides) to preserve its digest while runtime models/resources
change. This repairs the verified production-review digest finding. Independent
source-snapshot review approved the local checkpoint after the verified major digest
finding was closed; no major/critical finding remains open in that scope. It did
not constitute a full line-by-line audit. The coordinator then observed
`sbt check productionLoadClasspath pipelineDemoClasspath services/stage` pass:
**2,882 tests passed, zero failures/errors, one skipped, 241 suites**. The check
completed at 4:24:09 PM CST on November 23, with the normal marker task
(`markTestRun`: 485 seconds); both classpath tasks succeeded and staging completed
at 4:24:21 PM.
See the [production test report](../docs/agent-runs/production-connections-01/test-report.md)
and [preparation record](../docs/agent-runs/production-connections-01/status.md).
The loader classpath task has been built; no standalone CLI replay is claimed.
The 2,851-test result belongs to Integration Round 02; the 2,882-test result belongs
to production connections. Subsequent original-history offline preparation recorded
**2,889 passed, zero failures/errors, one skipped, 243 suites**. That gate ran on the
source candidate before its original commit, not a fresh sanitized clone. See the
[offline test report](../docs/agent-runs/aws-plan-01/test-report.md) and
[historical acceptance](../docs/agent-runs/aws-plan-01/human-gate.md). The later Q
fresh-local-clone gate and HTTP replay are separately recorded in publication notes;
three generated-state ER probes additionally skip there. Portable packaging and private
runtime infrastructure were prepared offline. A zero-byte `libjvm.so` in the Java
base still blocks healthy container verification; further Docker recovery is deferred.

## 19. Factory evaluation

The phase evidence provides an audit trail of repair and human acceptance. The
handoff preserved partial work and identified missing production pieces. Budget
exhaustion, long sessions and repeated stop-hook refusals increased coordination
cost. This report supplies no fabricated token total or productivity comparison.
Independent Integration 02 closeout reviewers approved the local scopes after one
major cleanup repair. The student recorded
[local checkpoint acceptance](../docs/agent-runs/integration-02/human-gate.md)
against implementation commit `1079eac`; acceptance commit `19c5b1e` was pushed.
The student explanation/demo remains pending; this is not production-readiness
approval or acceptance of subsequent production changes.

## 20. Cost analysis

No new AWS resources or paid model calls were made by this local completion. That
does not mean the account has zero charges: previously retained resources may incur
cost. A current billing export and resource inventory have not been obtained.
Historical cloud attempts consumed resources without producing the required final
Spark output. No unsupported dollar estimate is supplied.

## 21. Failures and incidents

Phase 6's final EMR attempt started Spark/YARN on Java 17 but failed at its first
`textFile` read with “Error in configuring object.” A missing codec class is a
suspected cause, not a confirmed diagnosis from that run. A subsequent offline probe reproduced
a missing LZO codec with the archived configuration; a built-in codec override is
prepared and shell-tested locally, with no new EMR run. See
[cloud preparation](../docs/cloud-preparation.md). No cloud output was published. A
historical local Spark reproducibility timeout later passed in the human run; it
remains a validity concern. The initial Codex sandbox refused build-cache/socket
access; scoped execution permission allowed the same local build/replay to run.

## 22. Threats to validity

The bounded DBLP-derived dev sample and the separate five-researcher synthetic
fixture give weak coverage of real ambiguity, large teams and sparse metadata. Local test success cannot establish
cloud credentials, network reachability, service limits or distributed consistency.
Conditional skips, external archives and historical results reduce reproducibility
unless their exact inputs are preserved. Independent local-checkpoint review is recorded separately; course-final
acceptance and production verification remain outstanding.

## 23. Limitations and unfinished requirements

The required deployed end-to-end real-data system is incomplete. Missing items
include live Neptune/DynamoDB/S3 Vectors loading/query verification, live model and
Secrets Manager validation, live runtime IaC application/deployment, measured elasticity,
a full bounded producer-pipeline replay, anonymous public-clone verification and video. Corpus-scale requirements and instructor-approved deviations
are not established for this project individually. The student reports published
reduced-scale course guidance for classmates (10K/50K works, up to 10K real vectors,
small elasticity experiments, and quota-related provider alternatives); no paid
execution or provider change follows from this local preparation. A concrete run
proposal must account for historical and proposed spending before credits against
the recommended cumulative $25 ceiling, including total vectors and provider
requests across all five indexes rather than assuming work and vector counts are
equal. The synthetic demo must not be described as an approved
substitute or as satisfaction of all minimum course acceptance criteria.

## 24. Lessons and contributions

Explicit evidence boundaries prevent a polished synthetic response from becoming a
false deployment claim. A staged launcher and deterministic replay make local
behavior easier to inspect. The new data connection reuses verified loaders and source records rather than
reconstructing collaboration facts by hand. Scope should follow submission
requirements and measured remaining gaps; additional unrequested hardening does not replace a
working end-to-end path.

| Contributor | Contribution and evidence |
|---|---|
| Taufeeq Patel | Student owner; supplied intent and acceptance decisions; historical human checks/commits recorded in phase gates. Final report review and demonstration remain pending. |
| Claude agents | Historical architecture/code/phase documentation and integration-02 demo/wiring per recorded role reports; individual claims remain subject to their evidence. |
| Codex implementation/coordinator | Local launcher/build completion, verified DBLP-artifact loading into the query API, observed verification, reproducible HTTP replays and submission documentation drafts; authors do not approve their own work. |
| Independent reviewer contexts | Read-only review of supplied source/diff snapshots, major cleanup finding and repair re-review, and scoped local-checkpoint approval; no implementation edits or independent test execution. |

## 25. Reproduction, cleanup and submission

Follow the demo script from the repository root and preserve generated JSON before
cleaning build outputs. The replay stops its child; an interactive server stops with
Ctrl-C. This demo creates no cloud resources. Historical Phase A cleanup succeeded
on November 19; base resources, buckets and logs were retained and have not been
re-inventoried. Do not delete them without a reviewed approved plan. Before submitting,
the student must review the report, record the video, obtain remaining required
evidence or explicitly disclose gaps, and approve the final Git checkpoint.
The professor's updated requirement, as supplied by the student, is a **public,
anonymously cloneable repository**. Actual visibility and anonymous cloneability
have not been independently verified here. The student must submit its URL through
the designated Microsoft Teams assignment before **November 25, 2026, 11 PM CST**.
No final submission tag or video link exists in this working state. Publication must
use a separately approved independent repository, not expose original private history.
Historical ER/Spark identity pins cannot automatically replay through rewritten
ancestry; new dev inputs/pins need separate preparation. The unchanged local demo
fixtures do not require that pin repair. See publication notes for the bounded plan.
