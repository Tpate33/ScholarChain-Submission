# Phase 06 CR-P6-28: code-reviewer focused re-review of IE-P6-02 repair 01 (2026-11-17)

- Scope: infra/terraform/** and scripts/** repair only. Read, Grep, Glob; nothing under gold/, target/ or ~/.aws. Static reading against StorePath, JobArguments, GraphStructureJob.checkpointProblem, StartupChecks, PartManifest.pathProblem.

## Verdict: APPROVE

## Status per original finding

- M1 CLOSED: `hdfs://<authority>/scholarchain/checkpoints/<id>` from validated fs.defaultFS (submit-collaboration-job.sh:188-196, 208-232) passes checkpointProblem (GraphStructureJob.scala:214-232) and StorePath.parse; input/output `s3a://<data bucket>/input/<name>` and `s3a://<data bucket>/output/spark` pass scheme, globProblem and mayOverlap; config and pairs `/tmp/scholarchain-submit.<8 alnum>/…` pass; run paths pass PartManifest.pathProblem; StartupChecks satisfied; `hdfs:///` removed (install-spark-dist.sh:103-114).
- M2 CLOSED: step `/usr/bin/timeout -k 60 <T>` leads its own process group, inner `timeout --foreground` stays inside it, spark-submit execs to the JVM; TERMINATE_CLUSTER, termination protection off, step read back (run-bounded-spark.sh:595-602); idle 900 s before and after; trap INT/TERM/HUP before the run record (:580); message corrected (:568). Window 86 min; 0.7314 USD ≤ max_cost_usd 1.
- m1 MOSTLY CLOSED (residue m1-r). n1 CLOSED (emr-run/main.tf:177-182; run-bounded-spark.sh:471-472). n2 CLOSED (deploy.sh:55-57).
- 6a-6d and identity: unconditional ListBucket gives 404s (iam.tf:110-114, 317-321); KMS endpoint with private DNS, SG 443 from VPC CIDR, cluster depends_on; node egress 443 to S3 prefix list plus VPC CIDR; R2 GetObject `output`, `output/`; HDFS dirs via `sudo -u hdfs`, owner verified; admin_principal_arn validated (common.sh:129-131; versions.tf:84-95) in R3, R5 trusts and three ArnNotEquals exceptions; plan.sh `-lock=false`; deploy-profile exception (plan.sh:49-65) unusable by agents or non-TTY paths. No other new defect.

## Findings

- m1-r (minor, infra-engineer) scripts/cleanup.sh:147-150: with a `notes/<id>.txt` waiver and no archived summary, the loop `continue`s and skips `require_mirrored` for `spark-events/<id>/`; step logs cannot be located. Failure input: `--execute` killed after the run record, before the summary; event log copied; human writes a note; `empty-buckets base` deletes unverified evidence. Fix: always mirror `spark-events/<id>/`; note waives only an empty listing.
- n3 (nit, infra-engineer) run-bounded-spark.sh:120 accepts step_timeout_minutes 5..30; wrapper (:44, 47-48, 237) needs ≥120 s after the 240 s reserve, so <360 s is refused on the cluster (wasted window). Fix: minimum 6 minutes or mirror the check.

## Unverified concerns (fail closed; could block or waste a run)

- `aws login`: get-caller-identity reporting the user ARN (deploy.sh:75); AssumeRole into R3 and R5; credential_process + source_profile in CLI and the Terraform Go SDK; aws:PrincipalArn for bucket-policy deletes.
- AddJobFlowSteps with ActionOnFailure TERMINATE_CLUSTER while R3 denies TerminateJobFlows (iam.tf:289): if EMR authorizes the caller for termination, add-steps is denied (one window wasted).
- DescribeStep may echo `TERMINATE_JOB_FLOW` instead of `TERMINATE_CLUSTER` (run-bounded-spark.sh:596 would cancel).
- Private-subnet EMR 7.14 with only S3 gateway + KMS endpoints; EMR egress on customer-supplied managed SGs after default egress is stripped (network.tf:47-69).
- Idle detection treats HDFS utilization ≥10% as not idle; checkpoints never removed.
- `s3:if-none-match` condition key (iam.tf:193-202, 350-355); node CLI `--if-none-match` support.
- `hadoop` passwordless sudo to `hdfs`.
- Carried: Hadoop 3.3.4 client vs YARN 3.4.2; `fs.s3a.endpoint.region` in 3.3.4; provider `= 6.0.0`; ReadOnlyAccess coverage of refresh reads; endpoint rate.

## Risks and manual action (as reported)

R5 ReadOnlyAccess reads every object and most configuration (explicit human acceptance); read-only and operator profiles derive from the admin login under the same OS user (opaque tools could use the admin profile while the session is valid; fence is the guard's `--profile <RO>` slot); 15-minute execute window; endpoint billing until Phase A; scripts 0644; no fmt/validate/plan/shellcheck/scan; plan counts 7/40/4 unobserved. Before signing any plan: `aws sts get-caller-identity --profile scholarchain-admin` must report `arn:aws:iam::<account>:user/<name>`; after base, `--profile scholarchain-readonly` must succeed; human runs `scripts/emr/check-submit-args.sh` and shellcheck in a Terminal.
