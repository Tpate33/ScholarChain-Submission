# Cloud preparation — November 23, 2026

The runnable local synthetic demo is verified. The subsequent DBLP-derived local
profile now also passes its focused API checks (see [demo instructions](demo-script.md)). A complete cloud deployment is not
ready to execute. No new cloud run, AWS call, model call, commit or push was made
in this preparation. The historical Phase 6 acceptance explicitly stopped EMR retries;
this preparation does not reopen that authorization.

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
| Store loading | `OpeningLoad`, load ports, tests; DynamoDB adapter supports writes | Production graph/object/vector load adapters or operational loader wiring, real embedder, and live load verification |
| Runtime storage reads | Concrete Neptune, DynamoDB, S3 and S3 Vectors adapters | Provisioned/populated stores, connectivity and live queries |
| Production startup | Validated launcher and durable-state composition | Secrets Manager key source; `ProductionLauncher.Seams.system.workflow` is still `None` |
| Model providers | Typed synthesis/embedder ports and non-production implementations | Real answer/embedding implementations, version/dimension configuration and approved paid use |
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
2. Complete the loader/runtime connections above, with local stub-based tests for
   SDK calls and model responses. Add only the runtime resources needed by that
   bounded path. This is implementation work, not just supplying credentials.
3. Before any launch, obtain a read-only current inventory, choose the real model
   and embedding dimensions, review a new Terraform plan, price its concrete
   resources, and agree a run duration, spending bound and cleanup plan. No new
   live inventory or current price quote was obtained during this preparation.
4. Only after approval, run one bounded data/load/query demonstration, archive its
   outputs and errors, and measure the required scaling behavior. Update the report
   with observed results and keep any unmet requirements explicit.

No reliable total deployment cost can be quoted from the present partial runtime.
The old Phase 6 EMR estimate does not price Neptune, service compute, models,
endpoints, storage and retained resources. It must not be reused as the budget for
this deployment.

Before reusing the changed wrapper in an approved cloud run, the user must approve
a commit, and its immutable script hash and version pins must be refreshed in a
new reviewed run configuration and plan. The historical pins and plans have been
left intact; they will not silently deploy this uncommitted script. Do not apply an
old plan or stage a different script under an existing immutable object key.

Recording the video can wait until the implementation being demonstrated is final.
