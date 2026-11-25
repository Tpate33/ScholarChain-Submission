# Phase 06 IE-P6-03: infra-engineer, account values, version pins, quota gate (2026-11-18)

- Inputs: prompt-account-checks.md and the human setup message (Region us-east-1, owner taufeeq, expiration 2026-11-26, operator the human; Terraform 1.17.2, ShellCheck 0.11.0); emr-docs-research-05.md. Authoring only; no AWS call, init, plan or apply.
- STATUS: COMPLETE.

## Changes

- Created scripts/.shellcheckrc (`external-sources=true`).
- env.conf: aws_account_id=<redacted-account-id>, aws_region=us-east-1, availability_zone_id=use1-az4 (in the human's list; KMS endpoint support there UNVERIFIED), admin_principal_arn=arn:aws:iam::<redacted-account-id>:user/scholarchain-admin, owner=taufeeq, expiration=2026-11-26; login profile scholarchain-login documented (deploy profile scholarchain-admin wraps it via credential_process).
- hashicorp/aws `= 6.0.0` -> `= 6.72.0` in bootstrap/main.tf, base/versions.tf, emr-run/main.tf; module private-bucket `>= 6.0.0, < 7.0.0`; required_version unchanged `>= 1.10.0, < 2.0.0`.
- base/iam.tf whitespace only (fmt); README sections on version compatibility, state lock, vCPU quota, local checks.
- deploy.sh: header "Operator: the human only, in a Terminal, with reviewed commands (decision D1 …; CR-P6-31 n1)"; emr-run only: read-only `aws service-quotas get-service-quota --service-code ec2 --quota-code L-1216C47A` with the deploy profile; exit 3 if below `EMR_RUN_REQUIRED_VCPUS=8`, exit 4 if the call fails or is non-numeric.
- run-bounded-spark.sh: BOUND line `vcpus` (cap 8; quota must be ≥ this; deploy.sh checks); shellcheck-only changes (SC2154 directive, `\~/*` pattern, SC2016 and SC2329 directives). Cluster shape and HC_MAX_INSTANCES=2 unchanged.
- common.sh: SC2154,SC2034 directive; `load_env_conf <env> [allow-expired]` (default unchanged: past expiration refuses).
- cleanup.sh (outside the work order list): `load_env_conf "$ARG_ENV" allow-expired`, so teardown works after the expiration date with a NOTE.
- plan.sh: quoting fix (SC2209). emr/install-spark-dist.sh, emr/submit-collaboration-job.sh: directive comments only (sha256 values change).

## Commands and results

- `terraform -chdir=infra/terraform fmt -check -recursive`: before edits exit 3 (`environments/dev/base/iam.tf`); after a wrong re-alignment (reverted) exit 3; final exit 0, no output.
- `shellcheck` final on check-submit-args.sh, install-spark-dist.sh, submit-collaboration-job.sh, deploy.sh, plan.sh, lib/common.sh, run-bounded-spark.sh: no output, exit 0 (first shellcheck run on these scripts; earlier findings SC2154, SC2034, SC2209, SC2088, SC2016, SC2329, SC2015 info: none behavioural). run-bounded-pipeline.sh (earlier phase) still has SC2154, SC2329, SC2016 findings; unchanged. cleanup.sh not checked (guard).
- `bash -n` on 7 scripts: no output. Guard refused (nothing ran): `shellcheck -f gcc …`, `git status --porcelain -- infra scripts`. Also `git ls-files` x3, `git diff --stat HEAD -- infra scripts`.
- Not run: terraform init/validate/plan (rows inactive), check-submit-args.sh (no row), AWS calls.
- Manual review: no `region` argument on any resource (only the S3 backend config in `terraform_remote_state`); no deprecated inline aws_s3_bucket arguments; no IAM or bucket-policy change.
- State lock: state bucket has only the TLS-only deny; scholarchain-admin (AdministratorAccess) can Get/Put/Delete `…/terraform.tfstate.tflock`; versioned (delete markers; noncurrent 90 days); plan.sh and cleanup plan-destroy use `-lock=false`; bootstrap local backend. No fix needed.

## Human local checks (no AWS call; init downloads the provider from the Registry)

```
terraform -chdir=infra/terraform fmt -check -recursive
for d in infra/terraform/bootstrap infra/terraform/environments/dev/base infra/terraform/environments/dev/emr-run; do
  terraform -chdir="$d" init -input=false -backend=false && terraform -chdir="$d" validate
done
shellcheck scripts/plan.sh scripts/deploy.sh scripts/cleanup.sh scripts/run-bounded-spark.sh scripts/lib/common.sh scripts/emr/*.sh
bash -n scripts/cleanup.sh
bash scripts/emr/check-submit-args.sh
```

Expected: fmt no output; each init "Terraform has been successfully initialized!"; each validate "Success! The configuration is valid." (review warnings); shellcheck and bash -n no output; check-submit-args 4 accepted and 18 refused "CASE ok" lines, no "CASE FAIL", "[check-submit-args] RESULT PASS", exit 0.

## Still REPLACE-ME

emr-run/terraform.tfvars: install_script_sha256, submit_script_sha256 (from committed scripts/emr files), scala_library_sha256, hadoop_aws_sha256, sdk_bundle_sha256 (sparkSubmitLocal inventory). runs/spark-dev-run-01.conf: code_version, job_jar_local_path and sha256, spark_dist_sha256, scala_library, hadoop_aws, sdk_bundle local paths and sha256, input_tree_sha256 (64-zero sentinel then --dry-run), partitions (spark-engineer).

## Risks (as reported)

6.72.0 argument behaviour vs main docs, lifecycle `filter {}`, deprecation warnings unverified until validate; after 2026-11-26 plan/deploy/run refuse until expiration is changed by a reviewed commit (cleanup still works: needs review); script sha256 values changed (earlier tokens invalid); quota check ignores other running Standard instances; changing AZ later replaces the subnet; endpoint rate unverified; get-service-quota response shape and aws:PrincipalArn for `aws login` UNVERIFIED (fail closed).
