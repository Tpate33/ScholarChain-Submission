# Phase 06 human message: IAM user created (2026-11-17)

Verbatim:

> Created scholarchain-admin with AdministratorAccess and
> SignInLocalDevelopmentAccess, enabled MFA, and signed in as that user.
> No access keys created. Give me the aws login command next.

## Orchestrator handling

- Recorded as a human console change (identity bootstrap, outside Terraform); to be disclosed by the human in human-gate.md.
- Organizations and IAM Identity Center not enabled (emr-docs-research-04.md).
- Given to the human: `aws login --profile scholarchain-login`, then a caller-identity check of the user ARN (the CR-P6-28 unverified concern for deploy.sh and the bucket-policy exceptions), then the account checks with the same profile (the read-only role R5 does not exist until the base stack is applied). No credential requested or received.
