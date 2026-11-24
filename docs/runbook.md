# Runbook

## Local use

Follow [the replayable demo](demo-script.md). Stage once after source changes, then
run the wrapper or smoke replay. No AWS/model credentials are needed. Use JDK 17.
To test the repository run `sbt check`; it may take several minutes. Do not run
concurrent builds in the same checkout. Preserve target evidence before cleaning.

| Symptom | Action |
|---|---|
| Demo is not staged | `bash scripts/pipeline-demo.sh --stage` (DBLP-derived) or `bash scripts/local-demo.sh --stage` (synthetic) |
| Address in use | Stop your prior demo or use `--port 0` for an ephemeral port. |
| 401 | Use the documented public demo key; do not supply production credentials. |
| Query abstains | Inspect the reason and evidence; ambiguity is an intentional result. |
| Missing production workflow/model/key-source configuration | Production startup fails closed. Supply the reviewed production settings only for an approved run; the local demo remains separate. |
| sbt cache permission denied in Codex | Approve the scoped local build cache access; do not alter hooks/markers. |
| Repeated Claude test-marker hook | Preserve the log; stop competing writers; use the documented human-run check recovery. Do not forge a marker. |

## Sanitized candidate reproduction

Follow [publication notes](publication.md) for a fresh candidate clone. A fresh local
clone of Q `69d63538` passed the offline gate (2,886 tests, zero failures/errors, four
skips, 243 suites) and seven HTTP smoke checks. The extra three ER skips require
generated curated-a state. All 939 behavior probes passed, but full agent validation
exited 1 on unchanged-hook ShellCheck findings; do not report the validator as PASS.
Anonymous public cloning remains unverified. Existing committed DBLP demo fixtures remain
bit-identical; do not copy an old `target/` directory. Historical ER/Spark runs
require separately prepared dev inputs because rewritten ancestors do not satisfy
their original identity pins. Never substitute held-out inputs or relabel old outputs.

## Deployment and rollback

The new `ProductionLoadMain` uses the existing `OpeningLoad` with explicit bounded
source directories and concrete AWS/model adapters. Default/`--validate` mode is
local configuration/source-envelope validation only; it does not build clients or
load stores. It is not proof that every row, remote resource or permission is valid.
The separate `productionLoadClasspath` task supplies its optional Parquet runtime
and has been built. Preparation/argument methods have offline tests; no standalone
CLI replay is claimed. See the
[current preparation record](agent-runs/production-connections-01/status.md).
The coordinator observed the new full gate pass with **2,882 tests, zero
failures/errors, one skipped, 241 suites**, followed by successful loader/DBLP
classpath generation and service staging. See the
[production test report](agent-runs/production-connections-01/test-report.md).
Subsequent original-history accepted offline preparation recorded 2,889 passed,
zero failures/errors, one skipped, 243 suites; see the
[offline report](agent-runs/aws-plan-01/test-report.md). It prepared portable workers
and private runtime infrastructure, not a successful deployment. Healthy container
verification remains blocked by a zero-byte base-image `libjvm.so`; Docker recovery
and alternative environments remain deferred. This is historical evidence, not a
new candidate build result.
The core test-resource `production-workflow.conf` and `production-load.conf` overlays
document the required non-secret fields and illustrative values, not deployable
credentials or an approved resource selection.
`productionLoad.analysisConfigFile` must point to the archived producer `.conf`,
separate from the runtime configuration. It is bounded to 64 KiB and parsed without
ambient overrides so that the original analysis digest can be verified unchanged.

The loader's `--execute` mode can write remote stores and call a paid embedding
provider. Do not invoke it during local preparation. An exact matching run ID is a
technical guard, not spending authorization. Before execution, review concrete
resources, IAM/network access, model access, serialized-loader exclusion, existing
and proposed charges before credits, bounded duration/request counts, evidence and
teardown. Human approval must cover the run and cumulative spending within the
recommended $25 ceiling. The runtime's token accounting is not a billing hard stop.
Count vectors and provider requests across all five indexes; 10K works does not
imply 10K total vectors.

There is no verified runtime deployment in this release state. Do not infer one from
adapter code or successful local checks. Historical reviewed-plan scripts are
`scripts/plan.sh`, `scripts/deploy.sh`, and `scripts/cleanup.sh`; their scope is the
implemented Terraform stacks including offline private-runtime preparation; no
live runtime application is verified. Apply/cleanup
require human approval and a direct interactive Terminal. No safe universal rollback
command is claimed. Inspect the exact reviewed plan and prior release before acting.

## Evidence and cleanup

The smoke replay terminates only its own child and preserves JSON/startup logs in
the requested output directory. An interactive demo stops with Ctrl-C. It creates no
cloud resources. Phase A cleanup was recorded as successful on November 19; base
stack/buckets/logs were intentionally retained. Current live inventory has not been
rechecked. Do not run Phase B or delete retained evidence as part of local cleanup.
For incidents preserve errors, revision, configuration identifiers and logs without
secret contents; obtain a reviewed correction before cloud mutation.
