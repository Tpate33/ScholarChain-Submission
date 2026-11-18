# Phase 06 human order: AWS account and local sign-in guidance (2026-11-17)

Verbatim:

> I created a personal AWS Paid account, enabled root MFA, and created
> the ScholarChain-monthly $10 budget alert.
>
> Continue the minimum EMR preparation. Guide me through signing in
> locally with a non-root identity and temporary credentials. Do not
> request root access keys or credentials in chat.
>
> Reuse existing test evidence unless code changes justify another check.
> Prepare the concrete deployment plan and verified cost estimate.
> AWS deployment, uploads, and spending remain pending my approval.

## Orchestrator observations

- SP-P6-05 (spark-engineer) was running in the background when this order arrived.
- `aws --version` was first refused by `audit-bash-writes.sh --pre` as UNATTRIBUTED, changed path `modules/spark/src/main/scala/scholarchain/spark/analysis/AnalysisRun.scala` (owner spark-engineer). The running SP-P6-05 work order covers this file (CR-P6-26 n2 and n4). Re-run once: `aws-cli/2.34.61 Python/3.14.5 Darwin/25.6.0 exe/arm64`.
- Account facts from the human: personal paid account (not AWS Academy); root MFA enabled; budget `ScholarChain-monthly`, USD 10, created in the console by the human. It is a human-created account guardrail, not project infrastructure. The `base` stack therefore drops its own budget resource to avoid a duplicate (IE-P6-01 listed one).
- Sign-in guidance given to the human: IAM Identity Center user with MFA; two permission sets (read-only for inspection and agents, administrator for the human-only deploy path); `aws configure sso` profiles `scholarchain-readonly` and `scholarchain-admin`; no access keys. No credential was requested or received.
