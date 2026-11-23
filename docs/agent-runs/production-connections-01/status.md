# Minimum production connections — local preparation

Starting checkpoint: Integration Round 02, implementation `1079eac`, human
acceptance `19c5b1e`, pushed to `origin/main` on November 23, 2026.

## Authorization and scope

The student requests the minimum production connections needed for a genuine
AWS end-to-end ScholarChain run before November 25, 11 PM CST. Local implementation
and offline tests are authorized. No AWS calls, paid model calls, deployment,
provider switch, commit, or push is authorized by this work order. Existing
guidance, skills, hooks, and human acceptance records remain unchanged.

The student's reported published course guidance permits reduced-scale experiments
for classmates: approximately 10K/50K DBLP works, up to 10K real S3 Vectors,
small documented elasticity experiments, and Anthropic/Voyage alternatives when
Bedrock quotas prevent execution. Individual approval is not yet recorded.
Do not repeatedly reopen general reduction questions during local preparation.
Before paid execution, present concrete resources, current pricing, historical
and proposed cumulative spending before credits (recommended ceiling $25),
execution bounds, evidence collection, teardown, and the exact approval needed.
Current account spending and resource inventory have not been inspected here.

## Owner assignments

- RAG inspection: existing model/embedder ports, evidence validation, and workflow
  suffice; no RAG or prompt changes required.
- Build: add Secrets Manager and Bedrock Runtime SDK modules to services at the
  existing AWS SDK BOM; preserve dependency fences and isolation.
- Services: real Bedrock Converse/Titan embedding adapters, digest-only Secrets
  Manager key source, lifecycle ownership, production workflow composition.
- Storage: concrete loader-only S3 object, Neptune graph, and S3 Vectors writers
  behind existing load ports and StorageClients; runtime readers stay read-only.
- Domain: additive typed external workflow/model bounds and serving provenance,
  preserving old configuration digests when the section is absent.

## Verification status

Local implementation and verification are complete; human closeout is pending.
Local adapter tests use injected SDK responses, not live
AWS. Dependency resolution/build reload passed. Completed owner-observed focused
checks: storage writers 11 passed; typed workflow/load/adapter configuration 23
passed; existing ConfigLoader regressions 42 passed; repaired producer-config
mapping 5 passed; final model/key/workflow/launcher/loader checks 18 passed.
These are separate scoped runs, not a full-gate total.

The test-owner's cross-module `ProductionOpeningLoadSuite` passed 1 test at
16:13:43 CST. It actually ran `OpeningLoad` against the accepted DBLP-derived
artifacts with simulated Bedrock SDK responses and in-memory targets under
real-model admission. It then ran the production workflow, verified three
coauthor claims/citations and the expected DBLP supporting work, persisted an
audit, and verified exactly-once model closure. This is not live AWS/model evidence.
The existing DBLP API test was preserved.

The single final full gate passed after sources froze: **2,882 passed, zero
failures/errors, one skipped, 241 suites; exit 0**. Command:
`sbt check productionLoadClasspath pipelineDemoClasspath services/stage`.
The normal test marker was written at 16:24:09 CST; both classpaths and staging
passed, with staging complete at 16:24:21 CST. No additional full run is needed
absent a substantive change. See [test-report.md](test-report.md) and
[verification-summary.txt](verification-summary.txt).

The independent snapshot reviewer and the services test both identified a real
integration failure (PR-PROD-1): producer analysis digests include the original
model/resource configuration, so deriving producer settings from new runtime
settings refuses the existing accepted artifacts. Repaired: an explicit bounded
`productionLoad.analysisConfigFile` supplies archived producer settings separately
from current runtime settings. Both the loader preparation and actual opening-load
regressions pass while preserving source manifests and digests.

The loader's normal/default and `--validate` actions perform only local
configuration and source-envelope checks. `--execute <analysisRunId>` is a separate
paid/mutating path and has not been invoked. Classpath generation passed; a
standalone command-line validation replay has not been claimed.

Independent source-snapshot review **APPROVED the local checkpoint** with no open
verified major/critical finding; [review-report.md](review-report.md) records its
scope and PR-PROD-1 closure. All 33 implementation/test hashes remained unchanged
through the final gate. The Git index remains empty. No new commit or push was made.

## Explicit remaining boundaries

Passing offline tests does not verify credentials, IAM, model access/quotas,
Neptune query support, live index metadata, real loading, runtime networking,
scaling, or successful end-to-end deployment. A production loader entry point and
runtime infrastructure/configuration must be assessed against the implemented
adapters before execution is proposed. No production-readiness claim is made.

## Human closeout preparation

The 41 intended changed/new files below were reconciled against the complete
working tree. All 33 implementation/test hashes still match
`implementation-files.json`; the other eight paths are three current documents
and five evidence files. No new implementation or full test run was needed for
closeout. The Git index was empty. `main` and the locally fetched `origin/main`
both named `19c5b1ec6b3ce7f9e3678a12dae6fa8593799474`; fetch again before committing.
Origin is `https://github.com/Tpate33/ScholarChain-Project.git`.

The scoped credential scan found no AWS access keys/secret assignments, GitHub
tokens, Anthropic/OpenAI token patterns, or private-key blocks. The intended paths
exclude `gold/`, private held-out outputs, `.env*`, credentials/state, `.claude/`,
`AGENTS.md` and `skills/`; fixtures use non-secret examples and injected responses.
References to held-out guards/counts in code and reports are not private labels.
This is a scoped content/path check, not a guarantee against every possible
unrecognized secret format. No credential or private-label files were opened.

Keep exactly these 41 implementation/documentation/evidence paths in the first
commit. The inventory and acceptance proposal are embedded here to avoid adding
extra implementation-commit files. The subsequent human-owned `human-gate.md`
and `commit.txt` are **two additional closeout files**, not part of the 41.
Agents must not create or sign those human records.

<!-- closeout-paths:start -->
```text
build.sbt
docs/agent-runs/production-connections-01/implementation-files.json
docs/agent-runs/production-connections-01/review-report.md
docs/agent-runs/production-connections-01/status.md
docs/agent-runs/production-connections-01/test-report.md
docs/agent-runs/production-connections-01/verification-summary.txt
docs/cloud-preparation.md
docs/runbook.md
modules/core/src/main/scala/scholarchain/core/config/AdapterConfig.scala
modules/core/src/main/scala/scholarchain/core/config/ConfigLoader.scala
modules/core/src/main/scala/scholarchain/core/config/ProductionConfig.scala
modules/core/src/main/scala/scholarchain/core/config/ProductionLoadConfig.scala
modules/core/src/main/scala/scholarchain/core/config/ProductionWorkflowConfig.scala
modules/core/src/main/scala/scholarchain/core/config/ScholarChainConfig.scala
modules/core/src/test/resources/fixtures/production-load.conf
modules/core/src/test/resources/fixtures/production-workflow.conf
modules/core/src/test/scala/scholarchain/core/config/AdapterConfigSuite.scala
modules/core/src/test/scala/scholarchain/core/config/ProductionLoadConfigSuite.scala
modules/core/src/test/scala/scholarchain/core/config/ProductionWorkflowConfigSuite.scala
modules/services/src/main/scala/scholarchain/services/Auth.scala
modules/services/src/main/scala/scholarchain/services/BedrockModels.scala
modules/services/src/main/scala/scholarchain/services/ProductionComposition.scala
modules/services/src/main/scala/scholarchain/services/ProductionLauncher.scala
modules/services/src/main/scala/scholarchain/services/ProductionLoad.scala
modules/services/src/main/scala/scholarchain/services/ProductionLoadMain.scala
modules/services/src/main/scala/scholarchain/services/ProductionWorkflow.scala
modules/services/src/main/scala/scholarchain/services/SecretsManagerKeys.scala
modules/services/src/test/scala/scholarchain/services/ProductionLauncherSuite.scala
modules/services/src/test/scala/scholarchain/services/ProductionLoadSuite.scala
modules/services/src/test/scala/scholarchain/services/ProductionModelsSuite.scala
modules/storage/src/main/scala/scholarchain/storage/aws/StorageClients.scala
modules/storage/src/main/scala/scholarchain/storage/aws/neptune/NeptuneGraphWriter.scala
modules/storage/src/main/scala/scholarchain/storage/aws/s3/S3ObjectStore.scala
modules/storage/src/main/scala/scholarchain/storage/aws/s3vectors/S3VectorsStore.scala
modules/storage/src/test/scala/scholarchain/storage/aws/neptune/NeptuneGraphWriterSuite.scala
modules/storage/src/test/scala/scholarchain/storage/aws/s3/S3ObjectStoreSuite.scala
modules/storage/src/test/scala/scholarchain/storage/aws/s3vectors/S3VectorsStoreSuite.scala
project/DependencyScopes.scala
reports/final-report.md
tests/src/test/resources/production-connections/launcher.conf
tests/src/test/scala/scholarchain/tests/services/ProductionOpeningLoadSuite.scala
```
<!-- closeout-paths:end -->

### Proposed acceptance for human review

This is an unsigned proposal, not a human acceptance file. Only the human may
record the decision after the implementation commit exists. The repository's
human-owned gate and commit-record rules require the human to create those files.

<!-- acceptance-draft:start -->
```markdown
# Production connections 01: human acceptance — local checkpoint

Decision: PENDING
Accepted implementation commit: PENDING
Acceptance date (America/Chicago): PENDING
Human: Taufeeq Patel (tpate33@uic.edu)

While Decision is PENDING this document is unsigned and authorizes nothing.

## Accepted local scope

Accept the local production-connection implementation: typed runtime and bounded
loader configuration; concrete S3/Neptune/S3 Vectors writer adapters; operational
composition through existing OpeningLoad and isolated Parquet classpath; Bedrock
Converse/Titan adapters; digest-only Secrets Manager key source; production
workflow wiring and cleanup. The existing DBLP and synthetic profiles remain.

The verified major producer/runtime configuration-digest finding PR-PROD-1 was
repaired by its owners and independently reviewed. Archived producer settings are
separate from runtime model/resource settings; original artifacts were not resealed.

## Evidence and attribution

Accept test-report.md, verification-summary.txt, implementation-files.json,
review-report.md, and the exact 41-path inventory in status.md. Final gate:
2,882 passed, zero failures/errors, one skipped, 241 suites. Classpath generation
and services staging passed. The build wrote its marker normally.

The cross-module test ran actual OpeningLoad over accepted DBLP-derived dev
artifacts with simulated SDK responses and in-memory targets, then verified three
coauthor claims/citations and audit persistence through production workflow code.
The existing DBLP query/evidence/explain HTTP regression passed. This does not
establish real model inference, real embeddings, live storage loading or deployment.

Independent review used supplied source snapshots and mechanical configuration
summaries; it was not a full line-by-line repository audit and the reviewer ran no
tests. Human acceptance relies on disclosed archived evidence; it does not claim
the human personally ran the full gate or independently audited every source line.

## Explicit limitations retained

No live AWS/provider call, real secret retrieval, IAM/network verification, model
quota/access check, populated cloud-store query, successful EMR execution, measured
throughput or elasticity is established by this checkpoint. Standalone loader CLI
replay is not claimed. Runtime infrastructure still needs a concrete reviewed plan.
S3 Vectors requires one externally serialized loader into run-owned stores because
Get/compare/Put is not an atomic conditional insert across processes. Neptune query
semantics and vector round trips await live evidence. Five-index vector/request
totals must be bounded independently of DBLP work count. Original Integration 01
and Integration 02 residual risks and unmet course requirements remain recorded.

Course reduced-scale/provider-alternative guidance is reported; individual approval
is not recorded here. Prior spending and live inventory are unverified. Before any
paid action, require exact resources, bounded requests/duration, cumulative costs
before credits against the professor's $25 guidance, evidence/teardown plans and
explicit human approval. This acceptance grants no deployment or paid-call approval.
Public/anonymous cloneability and final Teams submission remain separate checks.

## Git authorization

Approve the exact 41-file implementation commit, then a second commit containing
only this human-gate.md and commit.txt recording the implementation's actual SHA,
and a normal push of both commits to origin/main. Exclude AGENTS.md, skills/,
hooks, secrets and private held-out labels. No force push, tag, history rewrite,
AWS action, paid call or provider switch is authorized.
```
<!-- acceptance-draft:end -->

### Prepared Git sequence (human executes one action at a time)

1. Approve the proposed local acceptance and normal two-commit push. Fetch origin
   and verify repository, branch, remote and base; stop for unexpected divergence.
2. Read the 41 paths between `closeout-paths` markers, verify source hashes, stage
   exactly those paths, compare the staged list and inspect `git diff --cached --check`.
   Never use `git add .`.
3. Create the implementation commit with subject
   `Production connections 01: verified offline loading and runtime integration`.
4. The human extracts the `acceptance-draft` block into `human-gate.md`, replaces
   the three PENDING fields with ACCEPT LOCAL CHECKPOINT, actual implementation
   SHA and current America/Chicago timestamp, and writes that SHA plus newline to
   `commit.txt`. Do not change or sign historical Integration 02 acceptance.
5. Stage only those two human closeout files, verify their content, and commit with
   subject `Record production connections 01 local checkpoint acceptance`.
6. Run normal `git push origin main`, fetch, and verify HEAD equals origin/main.
   Only the protected untracked guidance paths should remain. Do not force push.

No test rerun is required for acceptance/report-only recording. After successful
push, prepare a concrete bounded AWS plan with cumulative costs before credits;
no AWS operation or paid model call is authorized by these closeout steps.
