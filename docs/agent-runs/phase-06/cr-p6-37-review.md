# CR-P6-37: review of IE-P6-04 (code-reviewer final report, verbatim)

```
CR-P6-37 review of IE-P6-04 (uncommitted tfvars and run-config edits against HEAD 148b4b9)

Verdict: APPROVE. I found no critical or major findings. There is one minor finding and four nits, all owned by `infra-engineer`.

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: CR-P6-37 (Phase 6). Read-only review of the infra-engineer IE-P6-04 uncommitted edits to `infra/terraform/environments/dev/emr-run/terraform.tfvars` and `infra/terraform/environments/dev/runs/spark-dev-run-01.conf`, against HEAD 148b4b91bc904a63680b94f91f275173f743c4b6.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none. I have only Read, Grep and Glob.
TEST RESULTS: not applicable. I ran no tests.
VALIDATION RESULTS:

Check 1: digests, cross-file agreement, code_version.
- I compared every filled value character by character with `/Users/taufeeqpatel/Desktop/ScholarChain/target/spark-submit-local/attempt-4/inventory.txt`. All match:
  - scala-library sha256 `1ebb2b6f…30f1`
  - hadoop-aws sha256 `53f9ae03…3a4a`
  - aws-java-sdk-bundle sha256 `873fe7cf…bae5`
  - job jar sha256 `cd62042c…f597a` (also in `result.txt`)
  - spark distribution sha512 `a462656d…3aa1`
  - hadoop-aws sha1 `a65839fb…4f80`
  - code-version `148b4b91bc904a63680b94f91f275173f743c4b6` (also in `result.txt`)
- The tfvars and the run config agree on every shared key: emr_release_label emr-7.14.0, instance_type m5.xlarge, idle timeout 900 / max_idle 900, spark sha512, scala, hadoop-aws sha256 and sha1, and the sdk bundle.
- The run config's paths and job arguments match `submit-commands.txt`: the jar and runtime-jar paths, input root, curated, decision and grouping runs, analysis config, benchmark pairs, and the checkpoint and tree bounds.
- The ancestry rule is enforced in `scripts/run-bounded-spark.sh:132-135`: 40 hex, not all zeros, the commit exists, and it is an ancestor of HEAD.
- The rule that the code scope is unchanged between code_version and the run HEAD is not enforced. See finding m1.

Check 2: staging the attempt-4 copies.
- I confirmed with Glob that all four jars exist under `target/spark-submit-local/attempt-4/job/` and `target/spark-submit-local/attempt-4/runtime-jars/`, and that `target/spark-dist/spark-3.5.5-bin-hadoop3-scala2.13.tgz` exists.
- `/target/` is gitignored (`.gitignore:10`).
- Path checks: `is_repo_path` rejects `..` and `.` segments, and home-relative paths may not have dot segments.
- `check_local` (`scripts/run-bounded-spark.sh:247-262`) refuses a non-regular file or a symlinked final component in `--stage`. It does not check intermediate directory symlinks. This does not matter, because each file is pinned by digest.
- Re-hashing before upload: `check_local` runs for every mode except `--execute` (line 291). In `--stage` it dies on any mismatch, and it checks sha512 and sha256 for the distribution and sha1 and sha256 for hadoop-aws.
- `stage_object` uploads with `--checksum-sha256` from the config and `--if-none-match *` (lines 460-461). S3 rejects a body that changed between the local check and the upload.
- `--execute` checks the ChecksumSHA256 of every staged object (lines 527-533).
- Risk: `sbt clean` (allowlisted to many agents as `sbt clean check`) deletes `target/`, including these jars and the distribution. The report discloses this.

Check 3: REPLACE-ME and the 64-zero placeholder.
- `parse_kv` (`scripts/lib/common.sh:110`) refuses any value containing REPLACE-ME with exit 3, before any other step. That covers spark_dist_sha256 and partitions.
- Terraform validation refuses install_script_sha256 and submit_script_sha256 at plan time (regex `^[0-9a-f]{64}$`, `emr-run/variables.tf:89-105`). `terraform validate` does not evaluate tfvars, so its reported pass is consistent.
- The zero input_tree_sha256 is refused in `--stage`:
  - The archive must be present (line 304).
  - The computed manifest sha256 must equal the config value (lines 300-301). An all-zero value cannot match.
  - An empty tree is refused (line 276).
- It is also unusable in `--execute`: the staged-tree checksum and the downloaded manifest hash must equal the config value (lines 532 and 535).
- It can therefore never be accepted. It only produces the computed value in a dry-run refusal (but see nit n4).

Check 4: bootstrap plan instructions.
- Every expected output in the report matches the code, except the profile line text (nit n1):
  - `SCHOLARCHAIN_PLAN_FIRST_WITH_DEPLOY_PROFILE=bootstrap` must equal the stack name. The exception refuses inside Claude Code and without a TTY, and needs the iam get-role call to return NoSuchEntity (`scripts/plan.sh:49-65`).
  - The NOTE text matches line 64.
  - The private-bucket module has exactly 7 resources (bucket, ownership controls, public access block, SSE, versioning, lifecycle, policy). The policy document is a data-source read, which `plan_summary` excludes.
  - The policy is the only PLAN-SECURITY line (`_policy$`). There is no PLAN-STATEFUL or PLAN-COST line.
  - plan.sh writes the meta file and prints the PLAN-SHA256 line and the deploy.sh command.
  - The `show` checklist matches `modules/private-bucket/main.tf` and the bootstrap `main.tf`: force_destroy false, four public-access flags true, AES256, versioning Enabled, abort uploads after 1 day, noncurrent versions expire after 90 days, a TLS deny on `aws:SecureTransport` false, and tags from default_tags.
  - Undeclared TF_VAR_* variables are ignored by Terraform.
- H7 is correct: plan.sh records `head=` in the meta file, and deploy.sh requires a clean tree, HEAD equal to the approved commit, and a meta file recording that head (`scripts/deploy.sh:52-53, 69-72`). Recording the signature in the repository before the apply would make deploy.sh refuse. The advice to record it outside the repository or after the apply, and to plan again after every commit, is the right handling.
- I found no unsafe instruction. All AWS actions in the report are read-only or human-Terminal steps.
- The cost arithmetic is correct: window 86 minutes, worst case 0.7314 USD.

Check 5: secrets and account values.
- The diff adds only hex digests, repository-relative paths and comments. It adds no secret, credential, key, account ID or ARN.
- The account ID and admin ARN appear only in the committed `env.conf`, which this diff does not change.

ASSUMPTIONS:
- I could not run git, so I reconstructed the diff from the current file contents and the report. I am assuming that HEAD's versions of these two files differ only in the fields the report names.
- I am assuming the attempt-4 jars have not changed since `inventory.txt` was written. I could not hash them myself; the dry run and `--stage` re-verify them.

RISKS:
- `sbt clean`, or `sbt clean check` run by any agent, before `--stage` destroys the pinned jars and the distribution. A rebuild may produce a different jar sha256.
- The open loadId and partManifestSha256 issue (attempt-4 RISK 1) may lead spark-engineer to change `modules/spark`, which would trigger m1.
- Still unobserved: plan.sh with a missing local bootstrap state directory, and the credential_process route through Terraform.

MANUAL ACTION REQUIRED: the human items H1 to H7 from the report, plus the remedy for m1 if the human wants it enforced.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: the human decides on m1. Then the three human sha256 values and spark-engineer's `partitions` are filled and committed, followed by dry run 1, the input_tree_sha256 commit, dry run 2, and the bootstrap plan in the human's Terminal.

VERDICT: APPROVE

FINDINGS (owner `infra-engineer` for all):

- m1 (minor). Files: `scripts/run-bounded-spark.sh:132-135` and `infra/terraform/environments/dev/runs/spark-dev-run-01.conf:22-24`.
  - Problem: the config comment says the code scope (`build.sbt project modules/core modules/spark`) "must be unchanged between it [code_version] and the run HEAD". The script checks only that code_version is a commit and an ancestor of HEAD.
  - Failure input: a commit after 148b4b9 changes `modules/spark`, for example `modules/spark/src/test/resources/scholarchain/spark/dev-run-01/analysis-dev.conf` or the job code (say, a fix for attempt-4 RISK 1). The dry run, `--stage` and `--execute` then all pass at the new HEAD.
  - Impact: the old jar (code 148b4b9) runs against the analysis config and benchmark pairs read from the new HEAD. The run is content-addressed and records gitCommit=HEAD and code_version=148b4b9, so it is traceable, but it no longer corresponds to any single source tree.
  - Existing mitigation: the only check is the human's one-time `git diff --stat 148b4b9 HEAD -- …` (report line 101). It is not repeated before `--stage` or `--execute` (report section 4, step 4).
  - Fix (owner's choice, a script change that needs review): add `git diff --quiet "$C_code_version" HEAD -- build.sbt project modules/core modules/spark || die "$EX_REFUSED" …` after line 135. Otherwise, reword the config comment as an operator check and add the command to step 4 of the report's run order.

- n1 (nit). Report line 130 gives the expected line as `CHECK ok profile scholarchain-admin (deploy-first-plan)`. `scripts/plan.sh:67` prints `CHECK ok profile scholarchain-admin (deploy-first-plan), Region us-east-1`. A human matching the whole line may stop for no reason.

- n2 (nit). Report line 181 (BLOCKED-ON EVIDENCE) says "`check_local` stops on the `spark_dist_sha256` mismatch before the input tree". While that value is REPLACE-ME, `parse_kv` (`common.sh:110`) refuses the whole config before `check_local` runs. `check_local` applies only once a wrong hex value is filled in.

- n3 (nit). Report lines 99-100 put `git status --porcelain  # must print nothing after the commit below` before the `git commit` it refers to. Run in the order shown, it prints the staged files.

- n4 (nit). Report lines 109-111 say the first dry run refuses with the computed tree hash. If `~/ScholarChain-archive/phase-06/dev-run-01` is absent or a symlink, `build_tree` returns 1 and the dry run prints a NOTE. It then ends `RESULT DRY-RUN-OK` with tokens bound to `tree=000…` (`run-bounded-spark.sh:303-306, 396-400`). Nothing downstream accepts those tokens (`--stage` refuses at line 304), but the instruction should say that tokens printed with a zero tree are unusable.

UNVERIFIED CONCERNS:
- I could not verify that `terraform plan` with the local backend and `-lock=false` succeeds when `~/ScholarChain-tfstate/bootstrap/` does not exist. Only `deploy.sh:111` creates that directory, so this is a reported assumption.
- I could not hash the attempt-4 jars or the tgz to confirm them against `inventory.txt`. The dry run re-checks them.
- I did not read the BE-P6-10 attempt-4 report, so I have not checked for other caveats about the jar's reproducibility.
```
