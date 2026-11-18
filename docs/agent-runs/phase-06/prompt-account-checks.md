# Phase 06 human message: account checks and vCPU quota (2026-11-18)

Verbatim:

> CLI profile: scholarchain-login
> Verified identity: arn:aws:iam::663570374448:user/scholarchain-admin
>
> Account checks in us-east-1:
> - Standard On-Demand vCPU quota: 5.0
> - EMR release: emr-7.14.0
> - m5.xlarge zone IDs: use1-az6, use1-az2, use1-az1,
>   use1-az5, use1-az4
>
> The proposed cluster needs 8 vCPUs. Give me the steps to request
> an increase to 8 now, while continuing authorized local preparation.
> If a smaller configuration preserves the required distributed run,
> explain that alternative before changing the plan.
>
> Reuse passing evidence for unchanged code. Deployment, uploads,
> and spending remain pending approval.

## Orchestrator notes

- CR-P6-28 unverified concern resolved: `aws login` reports the IAM user ARN `arn:aws:iam::663570374448:user/scholarchain-admin` (deploy.sh Arn check and bucket-policy exceptions use this form).
- Blocker: vCPU quota 5.0 < 8 needed for 2 x m5.xlarge. The quota increase request is a human action (a service-quota request; no resource, no charge).
- Plan not changed. A smaller configuration is explained to the human first, after checking EMR's supported instance types and prices in official documentation.
- Local preparation continues: infra-engineer fills the known account values (account ID, admin principal ARN, Region us-east-1, availability zone ID) in env.conf and tfvars; `owner` and `expiration` stay REPLACE-ME. (Superseded: the human's next message supplied owner `taufeeq`, expiration `2026-11-26` and confirmed us-east-1; see prompt-setup-choices.md, recorded after CR-P6-32 m1.)
