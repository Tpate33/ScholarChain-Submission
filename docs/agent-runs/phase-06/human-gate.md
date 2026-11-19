
## DE-P6-02 contract acceptance (2026-11-16)

I accept DE-P6-02 repair 01 following CR-P6-14. A counted work has exactly one canonical team if it has at least one author mention in the grouping run. A counted work without authors has no team. Each benchmark pair list is bound to the grouping run that issued its researcher IDs. Phase 6 remains open.

## FE-P6-05 ratification (2026-11-16)

I ratify the standalone `sbt sparkIT` command in allow mode for
`spark-engineer` only, against the reviewed build at `e8c6ecc`.
Allowlist SHA-256: `71034e28f6b00faecfc09be9ab13e3204b207b80b78c034a7cc760fcf040d707`.
This permission takes effect after this commit and a fresh Claude Code session.
Integration attempts are cleanable. Preserve the run evidence before cleanup.

## Curated archive repair and Spark integration retry (2026-11-17)

I accept BE-P6-04 and SP-P6-02 step 1A repair 02 following CR-P6-21
(APPROVE). Option A applies only to the pinned curated dev run. The
archive's stage markers establish stage completion. Attempt-level success
comes from the original attempt and the recorded dev-run evidence.
The read-only archive check found no .DS_Store files.

I approve one standalone `sbt sparkIT` retry by spark-engineer using
the reviewed build in the commit containing this entry. This supersedes
the e8c6ecc build approval for this retry. The allowlist scope is unchanged.
Carry CR-P6-21 m1, n1, and n2 forward before any step reads curated data
parts. Phase 5 remains accepted.

## SparkIT classpath repair and run 03 approval (2026-11-17)

I accept BE-P6-05 following CR-P6-22 (APPROVE) and defer N1 and N2.
Run 02 passed archive preflight but failed to compile. The repaired
integration suite now compiles under sbt check without archive access.

I approve one standalone `sbt sparkIT` run by spark-engineer using the
reviewed build in the commit containing this entry. This supersedes
the retry approval at 7244398. The allowlist scope is unchanged.
The archive and Phase 5 acceptance are preserved.

## SparkIT runner repair and run 04 approval (2026-11-17)

I accept BE-P6-06 following CR-P6-23 (APPROVE). Run 03 remains failed
evidence. The corrected JUnit lookup and result handling passed the
archive-independent runner checks. I defer F1 and F2. I accept the
JUnit ledger entry as a validation value for the already-resolved
dependency. G5 remains open and ownership permissions are unchanged.

I approve one standalone `sbt sparkIT` run by spark-engineer using the
reviewed build in the commit containing this entry. This supersedes the
run 03 approval at 982eecf. Phase 5 remains accepted.

## Spark researcher metrics and run 05 approval (2026-11-17)

I accept SP-P6-02 step 1B following CR-P6-24 (APPROVE).
Run 04 remains passing evidence for step 1A. I accept the documented
limits of the within-JVM logical reproducibility check and carry
CR-P6-24 m1 and n1-n4 forward. Cross-JVM reproducibility remains open.

I approve one standalone `sbt sparkIT` run by spark-engineer using the
reviewed code in the commit containing this entry. Use the existing
dev archive and record the nine-test result, metric totals, edge counts,
and output integrity evidence. Phase 6 remains open.

## Local Spark milestone acceptance (2026-11-17)

I accept SP-P6-03 steps 2A–2C, DE-P6-03 and BE-P6-07 following
CR-P6-25 (APPROVE). Run 07 passed 14 integration tests. My final
Terminal sbt check completed successfully with 1517 passed and zero
failures, errors or skips.

CR-P6-25 f1 is a required Phase 9 prerequisite. Carry f2 into the EMR
work and retain the other documented non-blocking findings.
Logical reproducibility was demonstrated within one JVM.
Cross-JVM reproducibility remains untested.

Phase 6 remains open for the bounded EMR run and its required evidence.
AWS execution requires my approval. Phase 5 remains accepted.

## Cloud I/O milestone acceptance (2026-11-17)

I accept SP-P6-04 following CR-P6-26 (APPROVE), using the existing
sbt check and sparkIT run 08 evidence. Carry the documented nits forward.

I approve Option A for local preparation and runtime compatibility
validation. Cloud execution, uploads, infrastructure changes and spending
require approval of the concrete plan. Phase 6 remains open.

## Phase 6 EMR preparation: implementation acceptance and account setup (2026-11-18)

I accept SP-P6-05, SP-P6-06, BE-P6-09 with repair 01, IE-P6-02 with repairs 01–03, and IE-P6-03 for commit as preparation only, following reviews CR-P6-27 (REQUEST_CHANGES, repaired), CR-P6-28, CR-P6-29, CR-P6-31 and CR-P6-32 (APPROVE).

Evidence: last reported agent sbt check had 1570 passed, 0 failed, 0 errored and 0 skipped. My local Terraform init -backend=false and validate succeeded for bootstrap, base and emr-run with hashicorp/aws 6.72.0. The submit-argument self-check returned RESULT PASS. ShellCheck on scripts/cleanup.sh and bash -n scripts/cleanup.sh both exited 0.

Manual account setup outside Terraform:
- Personal AWS Paid account 663570374448; root MFA enabled; no root access keys.
- ScholarChain-monthly budget alert: USD 10/month. This is an alert, not a spending cap.
- IAM user scholarchain-admin: console password, MFA, no access keys, AdministratorAccess and SignInLocalDevelopmentAccess.
- CLI profile scholarchain-login uses aws login. Verified caller: arn:aws:iam::663570374448:user/scholarchain-admin. AWS Organizations and IAM Identity Center are not enabled.
- Requested quota L-1216C47A in us-east-1 increase to 8; AWS Support approved 16 vCPUs at 13:26 on 2026-11-18. Effective quota remains unverified until rechecked. Cluster deployment must refuse below 8.
- Read-only checks confirmed emr-7.14.0 and m5.xlarge offerings in use1-az6, use1-az2, use1-az1, use1-az5 and use1-az4.

Decisions: Option A runtime; us-east-1; dev; use1-az4; owner taufeeq; expiration 2026-11-26; D1 (I run deployment commands in my Terminal). The planned cluster stays one primary and one core m5.xlarge. I approve the nine proposed version pins in project/versions.properties for preparation, with the two Scala-library values still subject to confirmation by sparkSubmitLocal, and hashicorp/aws 6.72.0.

I accept the security design for planning only: the proposed IAM roles, tag-scoped emergency termination, account-wide ReadOnlyAccess role assumable only by scholarchain-admin, bucket and key policies, private subnet with S3 and KMS endpoints, and inter-node in-transit encryption off. I understand the read-only role can read account objects and that local programs running as my user may access my active admin credentials; direct-agent command guards do not isolate those programs. This does not approve any Terraform apply.

I ratify FE-P6-06 with D1 and CR-P6-30 amendments m1 (state access only through scripts/plan.sh; tfplan Read denies and secret-path term), m2 (fixture probes reviewed in the applied diff), n1 and n2, for application by factory-engineer and review before the constitutional commit. Deferred nits: CR-P6-29 n4 residuals; CR-P6-30 n3; CR-P6-32 m2, n1 and n2. CR-P6-31 n1 is fixed.

Phase 5 remains accepted. Phase 6 remains open. AWS deployment, uploads and spending remain pending my approval of specific signed plans.

## Phase 6 FE-P6-06 application acceptance (2026-11-18)

I accept the applied FE-P6-06 changes under D1 following CR-P6-33 (APPROVE), as documented in fe-p6-06-application-report.md.

I acknowledge deviation (a): the tfplan fence is a separate PLAN_FILE check on Read, Grep and Glob, not a SECRET_PATH term, so the approved deploy-engineer shasum and terraform show rows remain usable. I accept deviations (b)–(e) as reviewed in CR-P6-33.

I ran .claude/hooks/validate-agent-files.sh --probes in my Terminal:
NOTE 888 table probes run
NOTE 51 fixture probes run
RESULT: FAIL (5 failures)

The only FAIL lines were ShellCheck findings in unchanged hooks:
audit-bash-writes.sh, enforce-ownership.sh, lib-baseline.sh,
post-write.sh and verify-tests-ran.sh.
These match the existing failures identified by CR-P6-33 m1.
I defer those findings and CR-P6-33 N1–N4 to a later factory-engineer work order. This records the observed failure result, not an overall validator pass.

Effective EC2 quota L-1216C47A in us-east-1 is verified at 16.0 vCPUs. The planned cluster remains one primary and one core m5.xlarge.

The constitutional changes are accepted for commit and activation after a session restart. Phase 5 remains accepted. Phase 6 remains open. AWS deployment, uploads and spending remain pending my approval of specific signed plans.

## sparkSubmitLocal layout repair (2026-11-18)

I accept BE-P6-09 repair 02 following CR-P6-34 (APPROVE) and defer its two nits.
BE-P6-10 attempt-1 downloaded and verified the pinned Spark distribution, then refused
because bin/load-spark-env.sh ships 0644 upstream (my tar listing); bin/spark-class
only sources it. The agent sbt check passed (1570/0/0/0) but its marker landed after
the last audited Bash call because the harness moved sbt check to the background after
180 s; I reviewed that marker and the baseline was reset. My Terminal sbt check at
103d05d with the repair in the work tree: exit 0, 1570 passed, 0 failed, 0 errored,
0 skipped (marker 2026-11-19T02:45:52Z). I approve one sbt sparkSubmitLocal run by
build-engineer at the commit containing this entry. Phase 6 remains open. AWS
deployment, uploads and spending remain pending my approval of specific signed plans.

## sparkSubmitLocal verdict repair (2026-11-18)

I accept BE-P6-09 repair 03 following CR-P6-35 (APPROVE) and defer nits n1, n2 and n4
to build-engineer and n3 (sbt's bundled JSON parser in project/ code) to a later
factory-engineer clarification. Attempt-2 at 609f27a ran both spark-submit modes
(local[2], local-cluster[2,1,1024]) with exit 0, one identical published run and
28 of 28 datasets equal, and confirmed scala-library 2.13.8 in the distribution and
2.13.16 at runtime; its verdict failed only because load-manifest item counts depend
on the partition count. The verdict now pins rows and distinct roles per store to the
sparkIT run 08 baseline and requires items to match between the runs. My Terminal
sbt check at 609f27a with the repair in the work tree: exit 0, 1570 passed, 0 failed,
0 errored, 0 skipped (marker 2026-11-19T03:49:17.215033Z). I approve one sbt sparkSubmitLocal run by
build-engineer at the commit containing this entry. Phase 6 remains open. AWS
deployment, uploads and spending remain pending my approval of specific signed plans.

## Temporal count determinism repair (2026-11-18)

I accept SP-P6-07 following CR-P6-36 (APPROVE). Attempt-3 at 5242754 showed that
CollaborationJob's temporal presence counts were nondeterministic across executor
JVMs: run 1 (local[2]) passed, run 2 (local-cluster[2,1,1024]) refused with
"temporal changes: 112 records, the comparisons count 55 researchers", and the same
job jar had passed in attempt-2. Cause: a Scala 3 enum used in a shuffle key hashes
by object identity, which differs between JVMs, and countByValue's toMap dropped
partial counts. The fix shuffles only Int and String keys and sums duplicate keys on
the driver; the consistency check is unchanged. I defer m1 (no single-JVM regression
test for enum shuffle keys) and nits N1 and N2 to a later spark-engineer work order.
My Terminal sbt check at 5242754 with the fix in the work tree: exit 0, 1574 passed,
0 failed, 0 errored, 0 skipped (marker 2026-11-19T04:41:17.779442Z). I approve one sbt
sparkSubmitLocal run by build-engineer at the commit containing this entry. Phase 6
remains open. AWS deployment, uploads and spending remain pending my approval of
specific signed plans.

## Local spark-submit PASS and EMR run configuration (2026-11-18)

I accept BE-P6-10 attempt-4 at 148b4b9: sbt sparkSubmitLocal PASS on local[2] and
local-cluster[2,1,1024], one published run a65f3f38..., 28 of 28 datasets logically
equal, temporal changes 112 in both runs, scala-library 2.13.8 (distribution) and
2.13.16 (runtime) confirmed. I accept SP-P6-08: partitions=2 for the EMR run; loadId
and partManifestSha256 differences across topologies are intended (byte layout is
not a reproducibility criterion) and the loader idempotency rule is carried to Phase 7.
I accept IE-P6-04 and repair 01 following CR-P6-37 and CR-P6-38 (APPROVE): runtime and
job jar digests from the attempt-4 inventory, my sha256 values for the two EMR scripts
and the Spark distribution, and run-bounded-spark.sh now refuses in every mode when
build.sbt, project, modules/core or modules/spark differ from code_version. I defer the
CR-P6-37 nits and the CR-P6-38 unverified concerns (inherited GIT_* variables,
case-only paths). This does not approve any apply, upload or spend.

## Bootstrap and base applied; staging diagnosis (2026-11-19)

I signed and applied the bootstrap and base plans at commit
b6934a0120900538f72cbb3179758d03b3244cdc after deploy-engineer reviews.
Bootstrap added 7 resources and base added 40, with RESULT APPLIED for both.
Bootstrap state was archived outside the repository; signatures are also
stored outside the repository. For base, I accepted the operator role's
create-once staging writes and read of data/logs objects, and the S3
gateway endpoint's default policy. All three AWS profiles work.

Two staging attempts failed on the first Spark distribution upload.
I accept IE-P6-06 (stage smallest objects first) following CR-P6-39
(APPROVE), and defer its nits. AWS spending on emr-run remains pending
my approval of a signed plan.

## EMR security-group fix after emr-run attempt 1 (2026-11-19)

Staging succeeded on a different network. The emr-run apply at d856047
failed with exit 5: the cluster ended TERMINATED_WITH_ERRORS because
the service-access security group lacked the required ingress rule
on port 9443 from the primary security group. No step was submitted.

I accept IE-P6-07 with repair 01 following CR-P6-40 and CR-P6-41
(APPROVE): eight group-to-group TCP 8443/9443 rules between the three
EMR security groups in base/network.tf, with no CIDR or internet path
and no security-group replacement. I defer the header-comment nit.

This accepts the security change but does not approve an apply.
I will clean up the failed emr-run, check for duplicate rules, and
approve the base plan only if it adds 8 rules and changes or destroys
nothing.

## EMR managed-policy tag fix after launch attempt 2 (2026-11-19)

The reviewed base security-group fix was applied and its eight rules
verified. Launch attempt 2 failed with insufficient EC2 permissions.
No instance or step ran. Phase A cleanup removed the three remaining
endpoint resources.

CloudTrail showed RunInstances and CreateNetworkInterface denials for
the EMR service role. Live network resources carry the managed-policy
tag, and the service role has the expected policies and no boundary.

I accept IE-P6-08 following CR-P6-42 (APPROVE): add
for-use-with-amazon-emr-managed-policies=true to the cluster and make
--execute refuse a cluster without it. No IAM or base change.
This does not approve an apply. I will sign the new emr-run plan only
after review confirms four creates with that tag on the cluster.
