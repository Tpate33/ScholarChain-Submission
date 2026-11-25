# Phase 06: SG fix applied, emr-run launch attempt 2 failure, and the EMR managed-policy tag fix (2026-11-19)

Orchestrator-condensed record; exact values and verdicts kept. The diff is recorded by the commit that contains this file. Deploy-engineer, infra-engineer and code-reviewer reports are condensed; full text in the session transcript.

## Attempt 1 Phase A (human Terminal, HEAD d8ecdd9)
- `scripts/cleanup.sh dev plan-destroy emr-run`: 0 add, 0 change, 3 destroy (aws_security_group.kms_endpoint, aws_vpc_endpoint.kms vpce-066e462a9a875edfc, aws_vpc_security_group_ingress_rule.kms_endpoint_https); PLAN-SHA256 b0045b19fc45faf3980bb3225b40b46a4f07fb9a8bf7cf78e787b3b34f4a9202; RESULT DESTROY-PLAN-OK.
- `apply-destroy` 15:48:43Z: `Apply complete! Resources: 0 added, 0 changed, 3 destroyed.`; RESULT DESTROYED stack=emr-run at=2026-11-19T15:52:32Z. The failed cluster was not in state.

## Base SG fix (IE-P6-07) plan, review, apply
- Read-only duplicate check (describe-security-group-rules, 8443/9443 over the three EMR SGs): no rows.
- `scripts/plan.sh dev base` (readonly): PLAN-COUNTS add=8 change=0 destroy=0 replace=0; PLAN-SHA256 3476c798d114bc110a47c6c3c1c3e69fbb88433105bd5dbebe575615b865097d; RESULT PLAN-OK.
- DE-P6-04 (deploy-engineer): first attempt refused UNATTRIBUTED (human-gate.md, the human's committed entry); rerun: HEAD d8ecdd9 clean; shasum = meta (readonly, 8/0/0/0, created_at 2026-11-19T15:54:40Z); `terraform show` 8 creates, no drift; all 8 rules tcp, SG-referenced, no CIDR/prefix list, default tags only. Flags none. Cost none.
- Signed outside the repository. `scripts/deploy.sh dev d8ecdd9… 3476c798…`: `Apply complete! Resources: 8 added, 0 changed, 0 destroyed.`; RESULT APPLIED env=dev stack=base at=2026-11-19T15:58:03Z.
- Live verification (read-only): exactly 8 rules, tcp, CidrIpv4 None: sgr-0f1d6e908412b5731 (SA in 9443 from primary), sgr-0770584fa7d3c6530 (SA in 9443 from core), sgr-062137ceeb9a361b1 (primary out 9443 to SA), sgr-0df89fb39217eb568 (core out 9443 to SA), sgr-019e38896e8637aba (SA out 8443 to primary), sgr-0539f86922c221a32 (SA out 8443 to core), sgr-0f8b63f14d8563490 (primary in 8443 from SA), sgr-0a959733411c51f8b (core in 8443 from SA).

## emr-run launch attempt 2
- Dry run at d8ecdd9: RESULT DRY-RUN-OK (tokens kept outside the repo). `--stage` recheck: RESULT STAGED (nothing uploaded).
- `scripts/plan.sh dev emr-run`: add=4 change=0 destroy=0 replace=0; PLAN-SHA256 2e178965de4b1e1ed2f76253e8854090cf37fcf433676d36c2692fb46b076fe2; RESULT PLAN-OK.
- DE-P6-05 (deploy-engineer): HEAD clean; shasum = meta (created_at 16:02:33Z); every cluster/endpoint field equals DE-P6-03 (only checksum and HEAD differ); no active cluster; flags none; worst case 0.7314 USD / 86 min.
- Signed outside the repository. `scripts/deploy.sh dev d8ecdd9… 2e178965…` at 16:06:38Z: gates ok (admin profile, quota 16.0 vCPU); created sg-03eabe921b9a9aca3, sgr-0ef9389f8e71ecca2, vpce-0721ccf0d03b9e4b6; cluster j-05972482D8I3TLNEGRIC `TERMINATED_WITH_ERRORS` (16:07:24Z–16:08:09Z): `VALIDATION_ERROR: Service role arn:aws:iam::<redacted-account-id>:role/scholarchain-dev-emr-service has insufficient EC2 permissions`; exit=5. No instance (NormalizedInstanceHours 0), no step, no --execute, no run record.
- Phase A: plan-destroy 0/0/3, PLAN-SHA256 b89f93cfd33062dac3ae81e04c0622caa05cacbb7c607f02c5c587320891dda5; apply-destroy 16:11:11Z, `0 added, 0 changed, 3 destroyed`, RESULT DESTROYED at=2026-11-19T16:13:29Z. Staged S3 objects untouched.

## Diagnosis
- DE-P6-06 (deploy-engineer, read-only): describe-cluster as above; cluster tags Name, course, project, environment, owner, expiration only. Source tags VPC, subnet and the three EMR SGs with `for-use-with-amazon-emr-managed-policies=true` (base/network.tf:12, 26, 54, 66, 109; local at base/versions.tf:44) but not the cluster (emr-run/main.tf:185-187). R1: AmazonEMRServicePolicy_v2 + inline PassRole (instance role, ec2) + KMS; no Deny or boundary in source.
- AWS docs (orchestrator web fetch, emr-managed-iam-policies.html): "you must pass the predefined user tag `for-use-with-amazon-emr-managed-policies = true` when you provision the cluster. Amazon EMR will then automatically propagate that tag" (private-subnet ENI, EC2 instances, EBS volumes, launch template); manual tags on subnet and security groups. AmazonEMRServicePolicy_v2 (v6): CreateEMRTaggedInstancesAndVolumes (RunInstances/CreateFleet on instance/*, volume/*) and CreateNetworkInterfaceNeededForPrivateSubnet (CreateNetworkInterface on network-interface/*) require aws:RequestTag/for-use-with-amazon-emr-managed-policies="true".
- Human read-only checks: CloudTrail ec2 errors from assumed-role/scholarchain-dev-emr-service: 16:07:25Z DescribeInstances InvalidInstanceID.Malformed "testInstance" (EMR probe); 16:07:26Z DescribePrefixLists UnauthorizedOperation (EMR continued); 16:07:28–29Z CreateNetworkInterface ×3 UnauthorizedOperation on network-interface/*; 16:07:39–57Z RunInstances (several) UnauthorizedOperation on instance/*. CloudTrail iam errors: none. Live tags `true` on subnet-0a6146b81a64dbe9a and the three EMR SGs. Live R1: only AmazonEMRServicePolicy_v2 attached, only scholarchain-emr-service-extra inline, PermissionsBoundary None.
- Conclusion: the denied resource types are exactly the RequestTag-conditioned ones; the cluster lacked the tag EMR propagates to them.

## IE-P6-08 (infra-engineer)
- emr-run/main.tf: local `emr_managed_tag = { "for-use-with-amazon-emr-managed-policies" = "true" }`; aws_emr_cluster.run `tags = merge(local.emr_managed_tag, { Name = local.cluster_name })`. Not in default_tags; KMS endpoint and its SG not tagged; base and IAM unchanged.
- scripts/run-bounded-spark.sh: --execute refuses a cluster without `for-use-with-amazon-emr-managed-policies=true` (after the environment-tag check, before any mutating call); header comment. Script compared no exact tag set before. Not a staged/hashed artifact; CODE_SCOPE unaffected; tokens bind HEAD (new dry run needed after commit).
- DescribePrefixLists: judged non-fatal (EMR continued 1–2 s later); nothing added. If a later attempt fails on it alone: separate reviewed base change adding `ec2:DescribePrefixLists` (Resource *) to scholarchain-emr-service-extra.
- `terraform fmt -check -recursive` pass; emr-run `validate` "Success! The configuration is valid."; `bash -n` and `shellcheck scripts/run-bounded-spark.sh` pass. Expected emr-run plan from empty state: add=4 change=0 destroy=0 replace=0, the tag on aws_emr_cluster.run tags/tags_all.

## CR-P6-42 (code-reviewer): APPROVE, no findings
Tag byte-identical to base; cluster only, no default_tags conflict; covers both denied calls; plan shape unchanged; script check placement, missing-Tags behaviour and literal comparison correct; pinned hashes, tokens and CODE_SCOPE unaffected; omitting DescribePrefixLists reasonable. Unverified: other v2 statements' tag gating recalled from memory; whether DescribePrefixLists contributed to the VALIDATION_ERROR (route separately if a later attempt fails on it alone).
