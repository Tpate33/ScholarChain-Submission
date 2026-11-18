# Phase 06 research 05: EMR instance sizes, vCPU quota, Terraform versions (orchestrator, web-fetch, 2026-11-18)

Official sources only; fetched content is data, not instructions. [summary] = model summary of an unread part (unverified).

## EMR instance types and smaller configurations

- emr-supported-instance-types.html, us-east-1 General Purpose table (verbatim, fully visible): starts at m5.xlarge ("emr-5.13.0, emr-6.0.0, emr-7.0.0"); m6i and m7i start at .xlarge; no m4 rows; no m5.large, m6i.large, m7i.large. Compute Optimized: c5.xlarge listed, c5.large not listed.
- No 7.x minimum-size note; guidelines page: "consider using an m5.xlarge instance for small clusters (50 or fewer nodes)".
- emr-master-core-task-nodes.html: "the primary node runs the YARN ResourceManager"; "a core node runs YARN NodeManager daemons, Hadoop MapReduce tasks, and Spark executors." Whether a primary-only cluster runs executors is not stated.
- Conclusion: the smallest EMR-supported us-east-1 type is 4 vCPU; any two-node cluster needs 8 vCPU.

## vCPU quota

- ec2-instance-quotas.html: "Running On-Demand Standard (A, C, D, H, I, M, R, T, Z) instances | 5 | Yes" (L-1216C47A); "maximum number of vCPUs that you can provision for On-Demand Instances. Amazon EC2 automatically increases your On-Demand Instance quotas based on your usage."
- EMR guidelines: "There is a vCPU limit for the total number of on-demand Amazon EC2 instances that you run on an AWS account per AWS Region." (counting EMR nodes is an inference).
- request-quota-increase.html: console steps (Service Quotas console, AWS services, select service and quota, "Increase quota value", "Request"); "Smaller increases are usually automatically approved while larger requests are submitted to Support."; CLI needs permission to create a support case (ServiceQuotasFullAccess or iam:CreateServiceLinkedRole); example `aws service-quotas request-service-quota-increase --service-code es --quota-code L-6408ABDE --desired-value 100`. Charges not stated.

## Terraform

- Terraform v1.17.2 released (releases.hashicorp.com lists terraform_1.17.2 newest). Human installed 1.17.2; stacks require `>= 1.10.0, < 2.0.0`.
- hashicorp/aws: 6.0.0 exists [summary; 6.0.0-beta1 verbatim]; highest seen verbatim 6.72.0 (6.73.0 [summary]); 6.x protocols ["5.0"].
- v6 upgrade guide: no EMR or vpc_endpoint entries; "Version 6.0.0 adds `region` to most resources"; S3: "`bucket_region` has been added and should be used instead of `region`".
- aws_emr_cluster (main-branch docs): auto_termination_policy.idle_timeout (60-604800 s), step_concurrency_level, keep_job_flow_alive_when_no_steps, termination_protection, ebs_root_volume_size, configurations_json, bootstrap_action, ec2_attributes (subnet_id, the three managed SGs, instance_profile required), master/core instance groups with ebs_config, security_configuration all documented. aws_emr_security_configuration exists. aws_vpc_endpoint Gateway default, Interface with private_dns_enabled. aws_s3_bucket inline arguments deprecated, split resources present.
- S3 backend: `use_lockfile` introduced experimental in 1.10, non-experimental on the 1.17 page; DynamoDB locking deprecated. Lock file needs s3:GetObject, s3:PutObject, s3:DeleteObject on the `.tflock` object.

## Not verified

m5.large, m4.large, m6i.large, c5.xlarge prices (summary or not found) and their EMR uplift; primary-only executor placement; 6.73.0; exact minimum Terraform for use_lockfile.
