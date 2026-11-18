# Phase 06 human order: approval-and-commit package (2026-11-18)

Verbatim:

> Consolidated update:
>
> AWS Support approved the us-east-1 Standard On-Demand quota increase
> to 16 vCPUs at 1:26 PM today, with up to 30 minutes to take effect.
> The effective quota has not yet been rechecked. Keep the planned
> two m5.xlarge nodes unchanged.
>
> Use the local-check evidence already supplied: three Terraform stacks
> initialized and validated, and the argument self-check returned PASS.
> For fmt, shellcheck, and cleanup bash syntax, identify only missing
> evidence. Do not record an unseen result as passing or repeat checks
> whose results are already established.
>
> Return one concrete approval-and-commit package:
> - Explain the security tradeoffs and exact scope of FE-P6-06 with D1,
>   including the reviewed state-access fix and nine version pins.
> - Provide the human-gate entry for my review, including account,
>   MFA, budget, IAM user, and quota-request setup.
> - Give exact scoped staging/commit commands, script executable changes,
>   and inclusion of the three Terraform lock files.
> - State when factory-engineer applies the ratified changes, when I
>   commit and restart, and the exact resume prompt.
>
> After the required approval and restart, proceed with the outstanding
> local compatibility test and Terraform plan preparation. Reuse passing
> evidence for unchanged code and defer unrelated nits.
>
> This message does not ratify the proposed changes or approve AWS
> deployment, uploads, or spending. Those await the concrete review.

## Orchestrator observations

- `git ls-files --others --exclude-standard -- infra scripts` first refused by `audit-bash-writes.sh --pre` as UNATTRIBUTED, changed paths `infra/terraform/bootstrap/.terraform.lock.hcl`, `infra/terraform/environments/dev/base/.terraform.lock.hcl`, `infra/terraform/environments/dev/emr-run/.terraform.lock.hcl` (owner infra-engineer): created by the human's `terraform init` (human-local-checks-2026-11-18.md). Re-run once: the three lock files are listed as untracked; no `.terraform/` directory is listed (ignored).
- Quota: approved to 16 per the human; effective value not yet observed (deploy.sh checks ≥ 8 before the emr-run apply).
- Evidence status for the three checks named:
  - `terraform fmt -check -recursive`: established by infra-engineer in IE-P6-03 (final run exit 0, no output) after the last .tf edit; the human's init changed no .tf file. Not missing.
  - `shellcheck`: established by infra-engineer in IE-P6-03 for plan.sh, deploy.sh, lib/common.sh, run-bounded-spark.sh and the three emr scripts (exit 0, no output). **Missing: `scripts/cleanup.sh`** (never shellchecked by anyone; guard refusal for agents).
  - `bash -n scripts/cleanup.sh`: **missing** (never run by anyone).
  - The human's fmt, shellcheck and cleanup `bash -n` output from 2026-11-18 was not visible in the screenshots and is not recorded as passing.
- Package recorded in approval-package-2026-11-18.md.
