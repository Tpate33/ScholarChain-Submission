# Phase 06: stage success, emr-run attempt 1 failure, and the EMR security-group fix (2026-11-19)

Orchestrator-condensed record; exact values and verdicts kept. The network.tf diff is recorded by the commit that contains this file.

## Stage (human Terminal, HEAD d856047)
- Attempt 3 (small-first): every small artifact and all 82 input files staged; aws-java-sdk-bundle 280,645,251 bytes in 40 s; Spark tgz 409,707,962 bytes failed after 74 s with "Connection was closed before we received a valid response", FAIL exit=4 (4:50 wall). H1 (IAM condition) ruled out: every object used the same StageCreateOnce statement and request shape. Cause: network path for the long single upload.
- Attempt 4 on a different network: `RESULT STAGED` (all earlier objects `already staged`).

## emr-run plan and review (DE-P6-03, deploy-engineer, read-only)
- HEAD d856047c043efbc0ee6e02fe43280e3ea3350b3d; tfplan sha256 438abdc6a8b78f90ea08bc1786f2abd4a9803fc14076551a92942a42c8be5198 = meta (plan_profile=readonly, add=4 change=0 destroy=0 replace=0).
- aws_emr_cluster.run: scholarchain-dev-spark-run, emr-7.14.0, Hadoop only, 1 primary + 1 core m5.xlarge (EBS 32 gp3, root 15), no task group or scaling, idle_timeout 900, keep_alive true, termination_protection false, step_concurrency 1, no SSH key, private subnet, base SGs/roles/security configuration, log_uri s3://scholarchain-dev-logs-<redacted-account-id>-us-east-1/emr/, core-site marker retention keep, yarn-site am.max-attempts 1, bootstrap path and 8 args equal to EXPECTED_BOOTSTRAP_ARGS. KMS endpoint: Interface, private DNS, tcp 443 from 10.42.0.0/24. Flags: none. Worst case 0.7314 USD over 86 min.
- (A first DE-P6-03 attempt stopped on an UNATTRIBUTED audit refusal caused by the human's committed gate entry; rerun with sequential Bash calls.)

## emr-run apply attempt 1 (human Terminal)
- Signed outside the repository; `scripts/deploy.sh dev d856047… 438abdc6…` exited 5. The KMS endpoint, its SG and its HTTPS ingress rule were created; the cluster ended `TERMINATED_WITH_ERRORS`: `VALIDATION_ERROR: ServiceAccessSecurityGroup is missing ingress rule from EmrManagedMasterSecurityGroup on port 9443.` No step submitted, `--execute` never run, no run record, attempt 1 unused. Staged artifacts unaffected.
- Phase A (destroy emr-run) to follow after this commit (cleanup.sh requires a clean tree).

## Root cause (AWS documentation, fetched by the orchestrator)
https://docs.aws.amazon.com/emr/latest/ManagementGuide/emr-man-sec-groups.html, private subnets, releases 5.30.0–7.x:
- Service access SG: inbound TCP 9443 from the primary managed SG; outbound TCP 8443 to the primary SG and to the core/task SG; "The same rules are needed if you are using custom security groups."
- Primary SG: inbound 8443 from service access; outbound 9443 to service access ("Amazon EMR does not add this rule when you use a custom managed security group").
- Core/task SG: inbound 8443 from service access; minimum egress includes TCP 9443 to service access.
- "Amazon EMR checks the rules in the managed security groups that you specify, and then creates any missing inbound rules" — it did not add the service-access 9443 rule in this setup.
Our base had no rules on the service-access SG (Terraform also revokes its default allow-all egress).

## IE-P6-07 + repair 01 (infra-engineer), base/network.tf only
Eight SG-referenced rules, tcp only, inside the VPC, no CIDR/0.0.0.0/0, no aws_security_group change:
1. service-access ingress 9443 from primary; 2. service-access ingress 9443 from core (defensive, orchestrator decision); 3–4. primary and core egress 9443 to service-access (CR-P6-40 m1); 5–6. service-access egress 8443 to primary and core; 7–8. primary and core ingress 8443 from service-access.
`terraform fmt -check -recursive` pass; `validate` (base) "Success! The configuration is valid." Expected base plan: add 8, change 0, destroy 0, replace 0 (not yet observed). Duplicate risk: rules EMR may have added during the failed launch (most likely the 8443 inbound on primary/core) would fail the create with InvalidPermission.Duplicate; remedy is an import block by rule ID, never a hand deletion; the human checks with `aws ec2 describe-security-group-rules` (read-only) before planning.

## Reviews
- CR-P6-40 (code-reviewer): APPROVE. m1 (node egress 9443 by group reference) folded in by repair 01; n1 (import/description note) folded in; n2 (wrong dry-run argument in the IE-P6-07 report; correct form `--dry-run dev infra/terraform/environments/dev/runs/spark-dev-run-01.conf`).
- CR-P6-41 (code-reviewer): APPROVE. All 8 rules SG-referenced tcp 8443/9443; no replacement risk; addresses and for_each keys stable; no duplicate definitions within Terraform; nit N1 (header says "two" egress rules per node group, now three; documentation only, deferred).
