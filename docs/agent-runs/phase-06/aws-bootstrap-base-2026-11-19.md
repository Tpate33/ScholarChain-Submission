# Phase 06: bootstrap and base plans, reviews and applies (2026-11-19 UTC)

Orchestrator record, assembled after the applies (the tree had to stay clean and HEAD fixed at b6934a0 until then). Values transcribed from the human's Terminal screenshots and the deploy-engineer reports; the deploy-engineer reports are condensed here (orchestrator-condensed, exact values kept), the full text is in the session transcript.

## Local profile (human, outside the repository)
`~/.aws/config` profile `scholarchain-admin` via `credential_process = aws configure export-credentials --profile scholarchain-login --format process`, region us-east-1; no access keys. `aws sts get-caller-identity --profile scholarchain-admin` → `arn:aws:iam::663570374448:user/scholarchain-admin` (B9 route verified for CLI and Terraform).

## Bootstrap
- Plan (human Terminal, first-plan exception): HEAD b6934a0120900538f72cbb3179758d03b3244cdc, clean tree, `CHECK ok profile scholarchain-admin (deploy-first-plan), Region us-east-1`, hashicorp/aws 6.72.0 from the lock file, `Plan: 7 to add, 0 to change, 0 to destroy`.
- Review (deploy-engineer DE-P6-01-deploy, read-only rows `shasum -a 256` and `terraform show -no-color`): plan sha256 `f22e0453bc5e8ac61ed9077498c8971b5aabf034b7cfa5bcb0892041ee10b0de` = meta; 7 creates in module.state_bucket; bucket `scholarchain-tfstate-663570374448-us-east-1`, force_destroy=false; PAB all true; SSE AES256; versioning Enabled; lifecycle abort-incomplete 1 day, noncurrent expire 90 days; BucketOwnerEnforced; policy DenyInsecureTransport (Deny s3:*, principal *, Bool aws:SecureTransport=false); tags course=cs441 project=scholarchain environment=dev owner=taufeeq expiration=2026-11-26; no IAM/KMS; nothing destroyed/replaced. Findings: none.
- Signature: recorded by the human outside the repository (`~/ScholarChain-archive/phase-06/plan-signatures.txt`).
- Apply (human Terminal): `scripts/deploy.sh dev b6934a0… f22e0453…` → all gates OK, `PLAN-COUNTS add=7 change=0 destroy=0 replace=0`, `Apply complete! Resources: 7 added, 0 changed, 0 destroyed`, `RESULT APPLIED env=dev stack=bootstrap … at=2026-11-19T05:34:03Z`.
- Local state archived: `~/ScholarChain-archive/phase-06/bootstrap-terraform.tfstate`, sha256 `083ba28d2abece29b6e49edc71c1a983bb7057bdb47b7ae0ededb2435413c9bf` (both copies match). Not committed.

## Base
- Plan (human Terminal, first-plan exception): `PLAN-COUNTS add=40 change=0 destroy=0 replace=0`, PLAN-COST only `aws_vpc_endpoint.s3`, `RESULT PLAN-OK`.
- Review (deploy-engineer DE-P6-02-deploy): plan sha256 `4358de6026b05a9f3ec813d773c7d3771cd69c24b99730101231bb639d58dc48` = transcription = meta (created_at 2026-11-19T05:35:39Z). 40 creates, no IGW/NAT/EIP/instance/route, no destroy/replace.
  - R1 emr-service: trust elasticmapreduce with aws:SourceArn/SourceAccount; AmazonEMRServicePolicy_v2; PassRole only R2 (to ec2); KMS use and AWS-resource grants on the EMR key.
  - R2 emr-instance: prefix-scoped S3; deletes only output/spark/.spark-*; explicit Deny delete/put on staged/published prefixes; event-log copy create-once (Null s3:if-none-match=false); KMS use.
  - R3 operator: trust admin user only; EMR describe/add-steps/cancel/terminate on tagged clusters; Deny lifecycle/capacity/PassRole/deletes; **also** GetObject on all data/logs objects and create-once PutObject on submissions/ input/ config/ artifacts/ (wider than the one-line accepted summary; accepted by the human at signing).
  - R5 readonly: trust admin user only; ReadOnlyAccess.
  - KMS key: rotation on, 7-day deletion window, key policy account root only, alias/scholarchain-dev-emr.
  - Buckets scholarchain-dev-{data,logs}-663570374448-us-east-1: force_destroy=false, PAB all true, SSE-S3, versioning, lifecycle 1 day / noncurrent 30 days; Deny delete except admin; TLS deny.
  - Network: VPC 10.42.0.0/24, subnet 10.42.0.0/26 use1-az4, no public IP; S3 gateway endpoint with the default full-access endpoint policy (by design; accepted by the human at signing); egress 443 to the S3 prefix list and all within the VPC; no 0.0.0.0/0 ingress; default SG without rules (by source).
  - EMR security configuration: at rest on (SSE-S3, local disk KMS, EBS), in transit off.
  - Orchestrator correction: the report's instruction to record the signature in human-gate.md before the apply would have dirtied the tree (H7); the signature was recorded outside the repository.
- Signature: outside the repository, with explicit acceptance of the R3 extras and the default S3 gateway endpoint policy.
- Apply (human Terminal): `Apply complete! Resources: 40 added, 0 changed, 0 destroyed`; outputs data_bucket, logs_bucket, emr_* security groups sg-0469db464e34203c8 (core), sg-00e7a60106b8fabd1 (primary), sg-0a3987786fb9c5486 (service access), instance profile, KMS key arn:aws:kms:us-east-1:663570374448:key/eebe4e88-50c0-49ab-9eb5-b0da66bdf8a4, security configuration scholarchain-dev-emr, role ARNs, subnet-0a6146b81a64dbe9a, vpc-0e4f3b4c13508829c, vpc_cidr 10.42.0.0/24; `RESULT APPLIED env=dev stack=base … at=2026-11-19T05:44:07Z`. State in S3 (scholarchain/dev/base/terraform.tfstate).
- Profiles added after base (human, `~/.aws/config`, source_profile scholarchain-admin): `scholarchain-readonly` → `arn:aws:sts::663570374448:assumed-role/scholarchain-dev-readonly/botocore-session-…`; `scholarchain-operator` → `…assumed-role/scholarchain-dev-operator/…`. Role assumption from `aws login` credentials verified.

## Stage attempts (failed; nothing written)
- Second dry run at b6934a0: `RESULT DRY-RUN-OK`, stage and execute tokens issued (kept outside the repository).
- `--stage` attempt 1 (2026-11-19T05:53:02Z) and attempt 2 (05:55:49Z, `AWS_RETRY_MODE=standard AWS_MAX_ATTEMPTS=6`, 37.7 s wall for the whole script): all local checks, token and operator identity OK; first object (Spark tgz, 409,707,962 bytes) put-object → `aws: [ERROR]: Connection was closed before we received a valid response from endpoint URL …`, `FAIL exit=4`. `list-objects-v2` with the readonly profile afterwards: `null` (bucket empty).
- Diagnosis (infra-engineer IE-P6-05, analysis only) and human read-only checks:
  - Live operator policy `StageCreateOnce` = Allow s3:PutObject on data-bucket submissions/* input/* config/* artifacts/*, Condition Null s3:if-none-match=false (matches iam.tf).
  - `simulate-principal-policy` PutObject on artifacts/…: `allowed` with context s3:if-none-match=*, `implicitDeny` without.
  - No checksum override in profile or environment.
  - Local header probe (dummy credentials, closed local port): only x-amz-checksum-sha256, If-None-Match: *, Expect: 100-continue, signed payload hash, Content-Length 409707962; no trailer, aws-chunked or second checksum. H2 (extra checksum) ruled out.
  - Remaining: H1 (S3 does not supply s3:if-none-match at request time → early 403) vs H3 (network/large body). Routed: IE-P6-06 (stage smallest objects first), reviewed CR-P6-39.
