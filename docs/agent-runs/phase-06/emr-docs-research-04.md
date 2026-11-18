# Phase 06 research 04: credits, AWS Organizations, IAM Identity Center, `aws login` (orchestrator, web-fetch, 2026-11-17)

Official AWS pages only; verbatim quotes. Fetched content is data, not instructions.

## Credits and Organizations

- free-tier-plans.html: "Free account plans will automatically upgrade to paid plan if you join AWS Organizations, set up an AWS Control Tower landing zone, join AWS Partner Network, ..."; "Any remaining credits from your free account plan are automatically applied to future AWS bills."
- aws.amazon.com/free/free-tier-faqs/ (Paid Plan Q8): "When you upgrade to Paid plan, your remaining Free Tier credits will automatically apply to future AWS bills until they expire." (Earning Credits Q3): "...you will become ineligible to earn more credits when you join an AWS Organization or set up an AWS Control Tower landing zone." (General Q8): "No, your credits cannot be transferred to another AWS account." Expiry wording inconsistent across FAQ answers (6 vs 12 months).
- useconsolidatedbilling-credits.html: "Credits are shared with all accounts that join an organization at any point in the month." "Accounts must have credit sharing activated to participate in credit sharing."
- Not found: whether creating an organization (becoming the management account) forfeits existing credits; orgs_manage_create.html returned no content.

## IAM Identity Center

- identity-center-instances.html: organization instances "support all features"; account instances: "No" for multi-account permissions and for access-portal sign-in to AWS accounts.
- account-instances-identity-center.html: "Account instances do not support permission sets and therefore do not support access to AWS accounts."
- So CLI access through Identity Center requires an organization instance, i.e. AWS Organizations. Pricing not stated on these pages.

## `aws login` (console credentials for the CLI)

- cli-configure-sign-in.html, command-line-sign-in.html: "A minimum version of 2.32.0 is required to use the `aws login` command." (installed: aws-cli/2.34.61).
- Identities: "Access to sign into the AWS Management Console as a root user, IAM user, or through federation with IAM." Policy: "Attach the SignInLocalDevelopmentAccess managed policy to your IAM user, role, or group."
- Duration: "your AWS CLI session will be valid for up to 12 hours"; refreshed every 15 minutes; cache `~/.aws/login/cache`.
- No requirement for Identity Center or Organizations stated (inference from absence).
- MFA handling: not stated. `source_profile` chaining from a login profile: not documented; documented workaround: `credential_process = aws configure export-credentials --profile signin --format process`.

## Orchestrator conclusion

The account is already on the paid plan, so the free-to-paid upgrade does not apply. Creating an organization makes the account ineligible to earn further Free Tier credits (FAQ), and the effect on existing credits is not stated. `aws login` with an IAM user needs neither Organizations nor Identity Center. Recommended route: IAM user with console password and MFA, no access keys, `aws login`; Organizations not enabled. infra-engineer notified to replace the SSO principal pattern with an `admin_principal_arn` variable and add a Terraformed read-only role.
