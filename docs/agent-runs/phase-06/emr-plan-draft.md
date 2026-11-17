# Phase 06 draft: minimum bounded EMR Spark run (for human approval)

Status: DRAFT by the orchestrator, 2026-11-17. Not approved. No AWS call, Terraform, script or allowlist change has been made or requested for it. Course requirement: prompt.md, "Run sbt check, a local or small-cluster Spark integration test, and a bounded EMR Spark run", with stage DAGs, partition counts and shuffle metrics reported.

## Goal

One Spark step on a small, short-lived EMR cluster that runs the same analysis job as the local sparkIT, over the same archived dev inputs (curated run `run:2881724b…8baa`, decision run `run:736cb170…b76a`, grouping run `run:84dd896f…d7d2`, a few MB), and writes one create-once output run. The result is compared logically (schema plus full row multiset) with the local sparkIT output of the same commit. This gives the course its EMR evidence (Spark UI/event-log DAGs, partitions, shuffle metrics) at the lowest cost. A full-DBLP or functional-scale run is not part of this plan.

## What does not exist yet (each a gate)

| # | Prerequisite | Owner | Gate |
|---|---|---|---|
| P1 | **Runtime decision (review F2, open):** the build compiles Spark code in Scala 3 against `spark-sql_2.13` 3.5.5; EMR 7.x's own Spark is understood to be built for Scala 2.12 (versions.properties comment; to be verified from AWS documentation by infra-engineer). Scala 3 code needs a Scala 2.13 Spark runtime. Options: (a) EMR on EC2 with a pinned Apache Spark 3.5.x Scala 2.13 distribution installed by a bootstrap action and run with its own `spark-submit`; (b) EMR Serverless with a custom image carrying that distribution; (c) EMR on EKS custom image. Orchestrator recommendation: (a), the fewest new services, provided infra-engineer confirms it from documentation | human decides; infra-engineer verifies | material decision |
| P2 | Job packaging: a Spark job jar (assembly) with `Provided` Spark and Hadoop, a `main` entry point that reads input root, output root and config from arguments/config (no hardcoded bucket), and the run-manifest chain | build-engineer (packaging), spark-engineer (entry point) | sbt check, review |
| P2b | **Distributed file-system I/O (CR-P6-25 f2):** the input reader and the publishers use the local java.nio file system (RunTree, VerifiedArchiveReader, RunPublisher `Files.list` after Spark writes, LoadManifests atomic rename). Needed: a Hadoop-FileSystem reader and publisher for S3 (or HDFS staging then copy to S3) with create-once semantics on S3 (no atomic rename there: e.g. conditional put of a final commit marker). Also, the driver-side validation bounds (`maxDriverValidation*`) keep the main path at dev/sample scale; the bounded EMR run stays on the dev archive, so this is acceptable for G3 but not for a full-DBLP run | spark-engineer | sbt check, review, sparkIT |
| P3 | Terraform environment (one non-production env): S3 buckets/prefixes (input copy, artifacts with immutable jar path, output, EMR logs), encrypted, public access blocked; EMR cluster or Serverless app; operator and cluster IAM roles with least privilege; tags including `expiration` | infra-engineer | fmt, validate, plan; human signs the plan checksum (Phase 12 gate) |
| P4 | Run script: `scripts/run-bounded-pipeline.sh` accepts only `pipeline=hadoop` today. Extend it (or add a sibling) for one Spark step with the same dry-run, confirmation token, hard caps, write-once outputs, and post-run verification | infra-engineer | bash -n, shellcheck, review |
| P5 | Operator (gap G6, proposed deploy-engineer), environment list ratification, active allowlist row | human; factory-engineer for the row | constitutional ratification, session restart |
| P6 | Upload of the archived dev inputs to the input prefix (create-once, sha256 recorded against the archive `_parts.json`) | operator under the approved script | human approval |
| P7 | Cleanup after the run (cluster termination is automatic; bucket contents retained or removed) | human runs `scripts/cleanup.sh` | human only |

## Proposed bounds (tighter than the existing Hadoop hard caps)

- Cluster: 1 primary + 1 core, `m5.xlarge` (or `m6i.xlarge`), no task nodes, no managed scaling; auto-termination idle timeout 30 min; or terminate-after-step.
- One step; step timeout 30 min; one attempt (a retry is a new approval).
- Cost cap: human-supplied instance-hour price; proposed max_cost_usd 5 for the step window, plus a budget alarm in Terraform. The orchestrator has no verified price and states none.
- Input: the archived dev run only (dev split; no gold/, no held-out data).
- Output: one `run=<id>/` under the output prefix, create-once; `_parts.json`, `_run-manifest.json`; codeVersion pinned to the jar's commit (not the sparkIT placeholder).
- Evidence collected: step state, Spark event log (DAGs, stages, partitions, shuffle read/write), driver stdout summaries, output `_parts.json` and run manifest, and a local logical comparison with the sparkIT output of the same commit.

## Order

1. Finish local deliverables (SP-P6-03 steps 2A-2C), sbt check, sparkIT, consolidated review.
2. Human decides P1 and approves this plan's bounds.
3. Work orders: build-engineer (P2 packaging), spark-engineer (P2 entry point and P2b file-system I/O), infra-engineer (P3, P4), factory-engineer (P5 row), each reviewed.
4. Human signs the Terraform plan; deploy path applies it (human-approved `scripts/deploy.sh` route).
5. Dry run of the run script; human sets the confirmation token; one execution; evidence assembly; human cleanup.

## Status notes

- 2026-11-17: the `aws-docs` MCP server (granted to infra-engineer) failed to connect in this session (`uvx` not on PATH), so the P1 runtime facts (EMR release, its Spark and Scala build, Java version) cannot yet be verified from documentation by the owner. Remedy: install `uv`/`uvx` and restart, then route infra-engineer for a documentation-only check. No AWS API call is involved.
- Run identity on EMR: the step-2A checkpoint directory is a job parameter (absolute path or hdfs/s3/s3a URI), so the EMR step can point it at HDFS or the output bucket's scratch prefix without a code change.
- The job will publish, besides Parquet, Neptune bulk-load CSV parts and a store-load-manifest.v1 per target store (DE-P6-03). Loading into Neptune, DynamoDB or S3 Vectors is not part of this plan (storage-engineer, later phase).

## Questions for the human

1. Runtime option for P1 ((a), (b) or (c)).
2. Region, account and environment name for the single non-production environment.
3. The cost cap and instance-hour price bound.
4. Operator for G6 (proposed deploy-engineer).
5. Whether the dev archive (no personal data beyond public bibliographic names and ORCID iDs) may be copied to S3.
