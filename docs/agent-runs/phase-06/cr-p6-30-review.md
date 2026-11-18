# Phase 06 CR-P6-30: code-reviewer review of the FE-P6-06 proposal (2026-11-17)

- Read, Grep, Glob only; one repository-wide Grep refused (heldout-label-read) and re-scoped to project/ and build.sbt. Nothing under gold/, target/ or ~/.aws read. Static verdicts.

## Verdict: APPROVE (minors to route before or with application)

- A1-A3 sound: new classes via `re.fullmatch` (guard :155-156); option-spec regex (:186) parses them; S3PREFIX and EC2FILTER cannot hold `:` or start with `-`; `stack()` fullmatch rejects `..`, trailing `/`, extra segments, nothing while `@env` empty; TFPLAN keeps the realpath escape check (:534-538); cleanup carve-out exact for bash and refuses every `-` token for shellcheck, reached only after a row matches; no widening.
- Rows: no env prefix, no `--stage`/`--execute`; deploy-profile exception needs the variable, empty CLAUDECODE and a TTY (plan.sh:49-54); only new writes are the lock file and ignored outputs.
- plan.sh: reads only (`-lock=false`, `-lockfile=readonly`); current HCL has no `external` or `http` data source and no provider `profile`/`assume_role`. `--dry-run` exits (run-bounded-spark.sh:366-376) before `need_tools aws` and the lock; temp directory removed. check-submit-args.sh runs only `--self-check` (submit-collaboration-job.sh:188-257).
- D1 correct (deploy.sh has no CLAUDECODE or TTY check; cleanup.sh:46-47 has both). @AWS-RO shapes read-only and bounded.

## Findings

factory-engineer (to include in the application work order)
- m1 (minor) B3 line 138 / D vs CLAUDE.md "Agents may never read … Terraform state": plan.sh run by an agent reads bootstrap local state (common.sh:169) and writes a `tfplan` holding prior state and sensitive values; no SECRET_PATH term or settings Read deny covers `tfplan`. Fix: explicit constitutional sentence allowing state access only through plan.sh; `Read(**/tfplan)`, `Read(**/*.tfplan)` denies; `tfplan` term in SECRET_PATH.
- m2 (minor) C3: the fixture probe text was not in the reviewed file; review it in the application diff.
- n1 (nit) A3: shellcheck branch accepts any token containing `cleanup.sh` (e.g. `.bak`); harmless; probe expectation must match.
- n2 (nit) C3 missing probes: symlinked TFPLAN; TFCHDIR trailing `/`; S3PREFIX inner `//` or leading `/`; EC2FILTER `Values=a,b`; deploy-engineer `--dry-run`.
- n3 (nit) B5/B4: no `emr get-managed-scaling-policy` or `s3api head-object` read rows (agents cannot independently verify the no-scaling and staged-checksum checks, run-bounded-spark.sh:485, 504); under D1 the deploy-engineer shasum/show rows have no Phase 6 consumer.

infra-engineer
- m3 (minor) scripts/deploy.sh: no CLAUDECODE or TTY refusal; opaque code or a future D2 could apply non-interactively with a valid admin `aws login` session. Fix: mirror cleanup.sh:46-47.
- m4 (minor) scripts/lib/common.sh:137: `aws_readonly_profile` only checked against `aws_deploy_profile`; `scholarchain-operator` would make agent plan.sh use the operator role. Fix: also differ from `aws_operator_profile`, or pin to `scholarchain-readonly`.
- n4 (nit) run-bounded-spark.sh:156-164, 238-252: home-relative paths let an agent dry-run hash and count files anywhere under $HOME (e.g. `.aws`), printing counts, sizes and names on refusal, never contents. Fix: refuse dot-segments under $HOME.

## Unverified concerns

CLAUDECODE set and no TTY in agent Bash; whether `terraform show` for S3-backend stacks initializes the backend with ambient credentials; inherited TF_CLI_ARGS*, TF_CLI_CONFIG_FILE, ~/.terraformrc, AWS_CONFIG_FILE, BASH_ENV; Read tool on a zip `tfplan`; `git status` index refresh (benign).
