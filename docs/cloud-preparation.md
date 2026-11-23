# Cloud preparation — November 23, 2026

The runnable local synthetic demo is verified. The subsequent DBLP-derived local
profile now also passes its focused API checks (see [demo instructions](demo-script.md)).
Integration Round 02 was accepted as a local checkpoint and pushed separately;
the subsequent production-connection work described below remains local preparation.
A complete cloud deployment is not ready to execute. No AWS or paid model call,
deployment, commit or push was made by this subsequent preparation. The historical
Phase 6 acceptance explicitly stopped EMR retries; this work does not reopen that
authorization.

## Production connections implemented locally

The current source adds concrete S3 create-once object writes, Neptune graph writes,
and S3 Vectors writes behind the existing storage ports. `ProductionLoadMain`
composes those adapters, DynamoDB and a real Bedrock embedder through the existing
`OpeningLoad`; it does not implement a second loader. Its default and `--validate`
modes inspect local configuration and bounded source envelopes without constructing
AWS clients. Full source/store verification remains in `OpeningLoad` during an
approved execution. Execution additionally requires an explicit matching run ID.

Production services now compose Bedrock Converse answer synthesis and Titan Text
Embeddings V2 with the existing RAG evidence and claim-verification stages. The
Secrets Manager key source reads a versioned digest-only document, caches only
within its configured TTL, and refuses an expired cache if refresh fails. These
are concrete clients with offline SDK-response tests; they have not contacted the
live services. No Anthropic or Voyage direct-provider switch was made.

The additive `productionWorkflow.*` and `productionLoad.*` configuration sections
make serving provenance, model versions, source directories, writer endpoint and
input/resource bounds explicit. Non-secret example overlays live under
`modules/core/src/test/resources/fixtures/production-workflow.conf` and
`production-load.conf`; their locations and limits require approved-run replacement.
Graph/report/log zone buckets must be among the existing raw/curated/context S3
adapter allowlist, with suitable distinct zone prefixes. A loader uses the explicit
Neptune writer endpoint rather than a read replica.

`productionLoad.analysisConfigFile` separately names the archived producer's
original `.conf` file. The loader reads at most 64 KiB and resolves it through the
same configuration loader without ambient overrides, then verifies the original
analysis digest. Live runtime model/resource configuration must not replace that
producer configuration: doing so would change the digest of an accepted artifact.

The separate `productionLoadClasspath` build task supplies the optional Parquet
reader runtime to this loader. Normal service packaging and the existing local
DBLP/synthetic demo classpaths remain separate. The classpath task has been built;
no standalone CLI replay is claimed. Tests exercise its preparation/argument methods.
The coordinator observed the final repository gate pass on November 23, 2026:
**2,882 passed, zero failures/errors, one skipped, 241 suites**. The check completed
at 4:24:09 PM CST, using the normal test-marker task (`markTestRun`: 485 seconds);
refreshed loader
and DBLP classpaths and service staging also succeeded. See the
[production test report](agent-runs/production-connections-01/test-report.md) and
[production preparation status](agent-runs/production-connections-01/status.md)
for subsequent observed results. Owner reports record 11 storage tests,
23 workflow/load/adapter configuration tests, 42 existing configuration regressions,
a later five-test producer-config regression run, and 18 services tests passing.
These are separate focused runs, not counts to add into a repository total.
A cross-module test also passed using the accepted DBLP artifacts, `OpeningLoad`,
simulated Bedrock replies and in-memory stores: three verified claims, resolvable
citations/support paper, audit persistence and exactly-once model closure were
checked. None of these results is live AWS evidence. Independent source-snapshot
review approved the local checkpoint with no open major/critical finding after the
producer-config digest finding was repaired. The final gate subsequently passed;
the review was not a full line-by-line audit or live approval.

S3 Vectors loading requires one externally serialized loader into run-owned indexes:
Get/compare/Put has no atomic conditional insert across processes. Neptune's exact
map-SET/custom-ID behavior, vector float round trips and bounded-load throughput
still require live verification.

## Local correction prepared

The archived attempt-a3 Spark event log records these EMR Hadoop codec entries:
`GzipCodec`, `DefaultCodec`, `BZip2Codec`, `SnappyCodec`,
`com.hadoop.compression.lzo.LzoCodec` and `com.hadoop.compression.lzo.LzopCodec`.
Using the local pinned Spark 3.5.5 / Hadoop 3.3.4 distribution, a Java 17 probe called
`TextInputFormat.configure(new JobConf(false))` after supplying that codec list.
It failed with `ClassNotFoundException: Class com.hadoop.compression.lzo.LzoCodec not found`.
The same probe with the four Hadoop built-in entries passed. It opened no dataset,
Spark session, AWS client or network connection.

This reproduces the codec failure mechanism suspected in Phase 6. The original
cloud log did not preserve a complete cause chain, so this is not proof that it
was the only cause of that run's failure.

`scripts/emr/submit-collaboration-job.sh` now passes an explicit
`spark.hadoop.io.compression.codecs` list containing those four built-in entries.
It prevents inheritance of unavailable EMR LZO codecs in the standalone Apache
Spark runtime. This does not add support for LZO-compressed inputs. JSON and
Parquet inputs must still satisfy the existing manifest/reader checks. No data
selection, model, resource size, timeout or retry limit was changed.

The existing `check-submit-args.sh` now verifies that the actual submission argv
contains exactly one matching codec override following `--conf`, alongside the
existing Java 17, checkpoint and timeout checks. Its local suite passed. A temporary
copy with the override removed was rejected as expected. This is a local correction,
not a successful EMR run.

Evidence: [codec probe](agent-runs/integration-02/verification/spark-codec-probe.txt),
[wrapper checks](agent-runs/integration-02/verification/check-submit-args.txt),
[check summary](agent-runs/integration-02/verification/cloud-preparation-summary.json),
[negative control](agent-runs/integration-02/verification/codec-negative-control.txt).

## Verified local infrastructure checks

Terraform formatting passed. `terraform validate -no-color` passed independently
in `bootstrap/`, `environments/dev/base/` and `environments/dev/emr-run/` using
cached providers. The first sandboxed validation could not start the provider's
local plugin IPC; scoped execution outside that sandbox passed. Neither validation
ran `plan`, `apply`, `destroy`, credential lookup or a live inventory.

ShellCheck and Bash syntax checks passed for both changed scripts. ShellCheck also
passed for the demo wrapper, plan/deploy/cleanup scripts, bounded Spark script,
shared helper and other EMR scripts. The older bounded Hadoop pipeline script has
existing warnings, including variables populated dynamically by its parser; it is
not described as lint-clean. No unrelated cleanup was made.

## Remaining code gaps before a real end-to-end cloud query

| Boundary | Existing evidence | Missing connection |
|---|---|---|
| Real data -> Hadoop -> Spark | Local phase evidence and verified manifests | Successful bounded cloud execution; current corpus-scale compliance unestablished |
| Store loading | Existing `OpeningLoad` has concrete graph/object/vector writers, a real Bedrock embedder, typed source/bound settings and an operational main; offline integration and full gate passed | Standalone CLI replay, approved input artifacts, populated stores and live load verification |
| Runtime storage reads | Concrete Neptune, DynamoDB, S3 and S3 Vectors adapters | Provisioned/populated stores, connectivity and live queries |
| Production startup | Launcher now selects the real workflow and Secrets Manager digest source; missing configuration fails closed | Approved real configuration, credentials/IAM, network reachability and live startup |
| Model providers | Concrete Bedrock Converse/Titan clients and explicit model/version/dimension bounds; injected-response tests | Model access/quotas, live output/embedding compatibility, measured quality and approved paid use |
| Runtime infrastructure | Base network/buckets/IAM and transient EMR stack | Neptune, runtime tables/vector index, service compute/network/IAM, deployment and scaling configuration |
| Evaluation | Local contracts and synthetic HTTP replay | Real-data query, model/retrieval evaluation and measured scale-out/scale-in |

The existing Terraform stacks do not define the missing runtime resources. The
opening claim in the historical Terraform README that nothing was applied is
outdated relative to Phase 6's human gate; that gate records historical applies and
cleanup. Neither document is a current live resource inventory.

## Smallest useful execution sequence

1. **Local data connection completed:** the separate pipeline demo loads the
   accepted Phase 6 DBLP-derived dev output and serves a verified query. Preserve
   the template-model/in-memory labels; this does not satisfy the live-cloud or
   corpus-scale course requirements.
2. Preserve the passing local integration, independent review and full-gate evidence.
   A standalone CLI replay is not claimed. Add only the runtime resources needed by that bounded path;
   application adapters do not provision their target resources.
3. Before any launch, obtain a read-only current inventory, choose the real model
   and embedding dimensions, review a new Terraform plan, price its concrete
   resources, and agree a run duration, spending bound and cleanup plan. No new
   live inventory or current price quote was obtained during this preparation.
4. Only after approval, run one bounded data/load/query demonstration, archive its
   outputs and errors, and measure the required scaling behavior. Update the report
   with observed results and keep any unmet requirements explicit.

The student reports published course guidance allowing approximately 10K/50K DBLP
works, up to 10K real S3 Vectors, small documented elasticity experiments, and
Anthropic/Voyage alternatives when Bedrock quotas prevent execution for classmates.
Individual approval has not been recorded. This guidance informs a minimum run
proposal; it is not permission for a paid operation or provider change here.
Before paid execution, present the exact resources, current prices, existing and
proposed cumulative charges **before credits**, run limits and teardown plan for
human approval, using the professor's recommended cumulative ceiling of $25.
The loader uses five vector indexes: total vectors and provider requests must be
bounded explicitly across all indexes; a work count is not a vector count.

No reliable total deployment cost can be quoted from the present partial runtime.
The old Phase 6 EMR estimate does not price Neptune, service compute, models,
endpoints, storage and retained resources. It must not be reused as the budget for
this deployment.

Before reusing the corrected wrapper in an approved cloud run, its accepted commit,
immutable script hash and version pins must be recorded in a new reviewed run
configuration and plan. The historical pins and plans have been left intact;
they will not silently deploy a different script. Do not apply an
old plan or stage a different script under an existing immutable object key.

Recording the video can wait until the implementation being demonstrated is final.
