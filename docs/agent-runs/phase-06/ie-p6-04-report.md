# IE-P6-04: EMR run digests, dry-run instructions, bootstrap plan command (infra-engineer final report, verbatim)

```
IE-P6-04 is done as far as I can take it, but the run config is not final yet. Three sha256 values have to come from your `shasum` (I have no shasum row), and spark-engineer has to set `partitions`. So I did not run the dry run. Nothing touched AWS. Nothing was applied, deployed, staged or uploaded, and no script was changed.

STATUS: COMPLETE
OWNER: infra-engineer
WORK ORDER: IE-P6-04 (Phase 6). Fill the artifact digests, prepare dry-run instructions, and prepare the bootstrap plan command for the human's Terminal. HEAD 148b4b91bc904a63680b94f91f275173f743c4b6.

FILES CREATED: none

FILES MODIFIED:
- /Users/taufeeqpatel/Desktop/ScholarChain/infra/terraform/environments/dev/emr-run/terraform.tfvars
- /Users/taufeeqpatel/Desktop/ScholarChain/infra/terraform/environments/dev/runs/spark-dev-run-01.conf

COMMANDS RUN (exact results):
1. `git status --porcelain`: `?? docs/agent-runs/phase-06/be-p6-10-attempt-4-report.md`
2. `git ls-files -- infra scripts docs/agent-runs/phase-06`: exit 0. All three `.terraform.lock.hcl` files are tracked.
3. Two `git -C …` calls were refused by the guard (`no-allowlist-entry`); nothing ran.
4. `git log --oneline --max-count=6`: HEAD is 148b4b9.
5. `terraform -chdir=infra/terraform fmt -check -recursive`: no output, exit 0 (after my edits).
6. `terraform -chdir=infra/terraform/environments/dev/emr-run validate -no-color`: `Success! The configuration is valid.`
7. `terraform -chdir=infra/terraform/bootstrap validate -no-color`: `Success! The configuration is valid.`
8. `terraform -chdir=infra/terraform/environments/dev/base validate -no-color`: `Success! The configuration is valid.`
9. `git diff -- infra/terraform`: the diff at the end of this report.
10. `git ls-files --modified -- scripts`: no output, so no script changed.
11. `scripts/emr/check-submit-args.sh`: 4 `CASE ok accepted` and 18 `CASE ok refused`, no `CASE FAIL`, ending `[check-submit-args] RESULT PASS`. The first case printed its argv (argc=86).

Not run:
- `terraform init`: not needed, `.terraform/` was already there.
- `scripts/plan.sh`: for bootstrap it refuses inside Claude Code, and the tree is not clean.
- `run-bounded-spark.sh --dry-run`: the run config is edited but not committed, and it still has REPLACE-ME values.
- `bash -n` and `shellcheck`: no script changed.
- No AWS call.

TEST RESULTS: not applicable. I changed no source and ran no sbt.

VALIDATION RESULTS:
- fmt: exit 0, no output.
- validate: all three stacks valid.
- Plan counts: none observed, because no plan was run. Expected from reading the code, not observed: bootstrap 7 add / 0 change / 0 destroy, base 40 add, emr-run 4 add.
- Script syntax: no script changed. `check-submit-args.sh` RESULT PASS.
- Security scan: not run. No scanner is selected yet (B5 open), so there is no row for one.

## 1. Every placeholder, its value, who sets it, how it is checked

**env.conf**
- No REPLACE-ME is left.
- account 663570374448, us-east-1, use1-az4, `admin_principal_arn=arn:aws:iam::663570374448:user/scholarchain-admin`, owner taufeeq, expiration 2026-11-26, profiles. All of these are your values from IE-P6-03 and human-gate.md.
- `load_env_conf` checks the ARN's form and account. `deploy.sh` checks that `sts get-caller-identity` equals that ARN.

**emr-run/terraform.tfvars**
- Filled by me, from attempt-4 `inventory.txt`, which I read directly:
  - `scala_library_sha256=1ebb2b6f…30f1`
  - `hadoop_aws_sha256=53f9ae03…3a4a`
  - `sdk_bundle_sha256=873fe7cf…bae5`
- `install_script_sha256` and `submit_script_sha256` are still REPLACE-ME:
  - They must be the sha256 of the committed `scripts/emr/install-spark-dist.sh` and `scripts/emr/submit-collaboration-job.sh`. I have no shasum row, and the dry run only prints them once the config is complete.
  - You compute them (command below).
  - Terraform validation refuses REPLACE-ME at the emr-run plan. `--execute` compares the cluster's bootstrap path and arguments with the committed scripts' hashes.
  - Any later change to either script invalidates both values.

**runs/spark-dev-run-01.conf**
- Filled by me:
  - `code_version=148b4b91bc904a63680b94f91f275173f743c4b6`
  - `job_jar_local_path=target/spark-submit-local/attempt-4/job/scholarchain-spark-jobs-0.1.0.jar` with `job_jar_sha256=cd62042c…f597a`
  - the three runtime-jar paths under `target/spark-submit-local/attempt-4/runtime-jars/`, with the inventory sha256 values
  - `input_tree_sha256` set to 64 zeros, the documented placeholder that makes the first dry run print the real value.
  - The dry run and `--stage` re-hash every local file and refuse on any mismatch.
- `spark_dist_sha256` is still REPLACE-ME:
  - It is the sha256 of `target/spark-dist/spark-3.5.5-bin-hadoop3-scala2.13.tgz`. The evidence only has its sha512.
  - You compute it. The dry run checks both sha512 and sha256, and stops on this file before it reaches the input tree.
- `partitions` is still REPLACE-ME:
  - spark-engineer decides it. The gate and sparkIT used 2; the EMR wrapper runs 2 executors with 1 core each.
  - I write the value it decides (or you do).
- `input_tree_sha256`, second step:
  - The first committed dry run refuses with `computed input tree manifest sha256 <X> differs …`.
  - You review the tree it lists, I or you replace the zeros with X, and you commit.

**Which jars to stage, and the code-version binding**
- I used the create-once copies the gate actually ran (`attempt-4/job/` and `attempt-4/runtime-jars/`, as in `submit-commands.txt`), not `modules/spark/target/…`. A later sbt packaging run can rebuild that file; the attempt-4 copy is left alone.
- No rebuild is needed as long as `build.sbt project modules/core modules/spark` stay unchanged between 148b4b9 and the run HEAD. Check with `git diff --stat 148b4b9 HEAD -- build.sbt project modules/core modules/spark`, which must print nothing.
- If that code scope changes, run `sbt sparkSubmitLocal` again at the new commit, then update `code_version`, the jar path, `job_jar_sha256` and the runtime-jar paths.
- Do not run `sbt clean` before `--stage`. It deletes `target/`, including the jars and the Spark distribution.

## 2. Your Terminal steps before the dry run (repository root)

shasum -a 256 scripts/emr/install-spark-dist.sh scripts/emr/submit-collaboration-job.sh target/spark-dist/spark-3.5.5-bin-hadoop3-scala2.13.tgz
# optional cross-check of what I filled (the dry run repeats it):
shasum -a 256 target/spark-submit-local/attempt-4/job/scholarchain-spark-jobs-0.1.0.jar target/spark-submit-local/attempt-4/runtime-jars/scala-library-2.13.16.jar target/spark-submit-local/attempt-4/runtime-jars/hadoop-aws-3.3.4.jar target/spark-submit-local/attempt-4/runtime-jars/aws-java-sdk-bundle-1.12.262.jar
shasum -a 1 target/spark-submit-local/attempt-4/runtime-jars/hadoop-aws-3.3.4.jar   # expect a65839fbf1869f81a1632e09f415e586922e4f80

Then:
1. Fill the two script values in the tfvars and `spark_dist_sha256` in the run config. Either you edit them, or a work order gives me the shasum output and I edit them.
2. Set `partitions` once spark-engineer has decided it.
3. Commit:

git add infra/terraform/environments/dev/emr-run/terraform.tfvars infra/terraform/environments/dev/runs/spark-dev-run-01.conf docs/agent-runs/phase-06
git status --porcelain          # must print nothing after the commit below
git commit -m "Phase 6: fill EMR run artifact digests (IE-P6-04)"
git diff --stat 148b4b9 HEAD -- build.sbt project modules/core modules/spark   # must print nothing
git ls-files -s -- scripts      # 100755 for plan.sh deploy.sh run-bounded-spark.sh cleanup.sh emr/install-spark-dist.sh emr/submit-collaboration-job.sh emr/check-submit-args.sh
command -v jq xxd base64        # the scripts require these

## 3. Dry run (I can run it once the config is committed; so can you)

`scripts/run-bounded-spark.sh --dry-run dev infra/terraform/environments/dev/runs/spark-dev-run-01.conf`

- **First run**, expected:
  - `CHECK ok` lines for the five artifacts and `CHECK ok input tree …: N files, B bytes`
  - then `FAIL exit=3 computed input tree manifest sha256 <X> differs from input_tree_sha256=000…`
  - You review N and B, X goes into the config, and you commit.
- **Second run**, expected:
  - BOUND lines: 2 instances, idle 900 s, step 30 min, window 86 min, worst case about 0.73 USD with max_cost_usd 1, 8 vCPUs
  - the planned stage and execute calls, and the CONFIRM lines (including the install and submit sha256, which must equal the tfvars)
  - `CONFIRMATION-TOKEN stage …`, `CONFIRMATION-TOKEN execute …`, `RESULT DRY-RUN-OK`
- The tokens are tied to HEAD, so any commit means running the dry run again.

## 4. Bootstrap plan (your Terminal, first-plan exception)

cd /Users/taufeeqpatel/Desktop/ScholarChain
git status --porcelain                       # must be empty (the untracked attempt-4 report must be committed first)
aws login --profile scholarchain-login
aws sts get-caller-identity --profile scholarchain-admin --query Arn --output text   # expect arn:aws:iam::663570374448:user/scholarchain-admin (checks the credential_process route, B9)
SCHOLARCHAIN_PLAN_FIRST_WITH_DEPLOY_PROFILE=bootstrap scripts/plan.sh dev bootstrap
shasum -a 256 infra/terraform/bootstrap/tfplan    # must equal the PLAN-SHA256 line
terraform -chdir=infra/terraform/bootstrap show -no-color tfplan

Expected output (not observed):
- `NOTE first plan of bootstrap with the deploy profile scholarchain-admin: role scholarchain-dev-readonly does not exist yet` and `CHECK ok profile scholarchain-admin (deploy-first-plan)`
- init (local backend `~/ScholarChain-tfstate/bootstrap/terraform.tfstate`), `Success! The configuration is valid.`, then `Plan: 7 to add, 0 to change, 0 to destroy.`
- 7 `PLAN-CHANGE create` lines: aws_s3_bucket, _ownership_controls, _public_access_block, _server_side_encryption_configuration, _versioning, _lifecycle_configuration, _policy
- one `PLAN-SECURITY create … aws_s3_bucket_policy`, no PLAN-STATEFUL, no PLAN-COST
- `PLAN-COUNTS add=7 change=0 destroy=0 replace=0`, `PLAN-SHA256 <hex> infra/terraform/bootstrap/tfplan`, the deploy.sh command, `RESULT PLAN-OK`

Where files land: `infra/terraform/bootstrap/tfplan` and `infra/terraform/bootstrap/.terraform/scholarchain-plan.meta`, both gitignored.

What to check in `show`:
- bucket `scholarchain-tfstate-663570374448-us-east-1`, `force_destroy = false`
- all four public-access-block flags true, AES256, versioning Enabled
- lifecycle: abort incomplete uploads after 1 day; noncurrent versions expire after 90 days
- policy: TLS-only deny (`aws:SecureTransport` = false); it may show as known after apply
- `tags_all` with course, project, environment, owner and expiration
- no IAM or KMS resource, and nothing destroyed or replaced
- cost about 0 USD

Order after that (nothing started):
1. Sign the checksum, then run `scripts/deploy.sh dev <HEAD sha40> <plan sha256>` in your Terminal. Keep a copy of the local state file in your archive.
2. Run `SCHOLARCHAIN_PLAN_FIRST_WITH_DEPLOY_PROFILE=base scripts/plan.sh dev base` (expected about 40 add: IAM R1–R3 and R5, KMS key, VPC, buckets). Sign, then `deploy.sh`.
3. Add the `scholarchain-readonly` and `scholarchain-operator` profiles with role ARNs `arn:aws:iam::663570374448:role/scholarchain-dev-{readonly,operator}`, and verify them with `sts get-caller-identity`.
4. Run the dry run at the current HEAD, then `SCHOLARCHAIN_CONFIRM_SPARK_RUN=<stage token> … --stage` (operator profile).
5. Run `scripts/plan.sh dev emr-run` (read-only profile; I can run it after base). Sign, then `deploy.sh` (it checks the quota is at least 8).
6. Run `--execute` within 900 s.
7. Cleanup Phase A, archive the evidence, then Phase B.

## 5. Blockers left before the first plan
- H1 (human or orchestrator): commit the untracked `docs/agent-runs/phase-06/be-p6-10-attempt-4-report.md`. `plan.sh`, `deploy.sh` and the dry run all refuse a tree with untracked files.
- H2 (human): the two script sha256 values and `spark_dist_sha256`, then the commit.
- H3 (spark-engineer): `partitions`.
- H4 (human): commit `input_tree_sha256` after the first dry run.
- H5, B9 (human): check that `scholarchain-admin` (the credential_process wrapper) returns the user ARN. The readonly and operator profiles come after base.
- H6 (human): confirm the `100755` modes with `git ls-files -s -- scripts`. `check-submit-args.sh` executed, so its bit is set; I cannot see the others.
- H7 (human): a plan is tied to HEAD and to a clean tree. Recording the signed checksum in `human-gate.md` before the apply changes HEAD or dirties the tree, and `deploy.sh` then refuses. Record the signature outside the repo, or after the apply, and plan again after each commit.

Already resolved: the lock files are committed, and the quota is 16.

ASSUMPTIONS:
- The inventory's job-jar sha256 also holds for the `attempt-4/job/` copy. The dry run checks this.
- Terraform ignores the `TF_VAR_*` values that bootstrap does not declare.
- `plan.sh` works with a missing local bootstrap state directory, because plan writes no state (not observed).

RISKS:
- `sbt clean` deletes the jars and the distribution.
- The expiration 2026-11-26 stops plan, deploy and run after that date (cleanup still works).
- The credential_process route through the Terraform SDK is unverified.
- The run-to-run differences in loadId and partManifestSha256 (attempt-4 RISK 1) are still open for spark-engineer.
- The MCP servers did not connect this session, so nothing was checked against the Registry or AWS docs.

MANUAL ACTION REQUIRED: H1–H7 above, the plan signatures, every `deploy.sh`, `--stage`, `--execute`, and cleanup.
BLOCKED-ON OWNER: none for this work order. The next step depends on the human (H1, H2, H4–H7) and spark-engineer (`partitions`).
BLOCKED-ON EVIDENCE: `parse_kv` refuses any REPLACE-ME. `check_local` stops on the `spark_dist_sha256` mismatch before the input tree. The dry run requires a committed config and a clean tree.
NEXT GATE: code-reviewer review of this diff → you commit with the three hashes and `partitions` filled → dry run 1 (prints the tree hash) → commit → dry run 2 (prints the tokens) → bootstrap plan in your Terminal.
```

The complete diff (emr-run/terraform.tfvars and runs/spark-dev-run-01.conf) was returned with the report and is recorded by the commit that contains this file.
