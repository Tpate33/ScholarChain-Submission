# Phase 06 IE-P6-02 repair 01: infra-engineer, CR-P6-27 fixes and identity change (2026-11-17)

- Routed: CR-P6-27 REQUEST_CHANGES (infra-engineer M1, M2, m1, n1, n2; unverified 6a-6d), plus the orchestrator scope update under prompt-blockers-and-checklist.md (priorities; IAM user with `aws login` instead of Identity Center; read-only role). Authoring only: nothing applied, deployed or called on AWS.
- STATUS: COMPLETE (authoring). Not run: terraform fmt, validate, plan, shellcheck, scanner (terraform and shellcheck exit 127, `command not found`). `bash -n` exit 0 for 7 scripts; cleanup.sh not attempted (guard `cleanup-human-only`).

## Files

Created scripts/emr/check-submit-args.sh. Modified scripts/emr/submit-collaboration-job.sh, scripts/emr/install-spark-dist.sh, scripts/run-bounded-spark.sh, scripts/cleanup.sh, scripts/deploy.sh, scripts/plan.sh, scripts/lib/common.sh, infra/terraform/README.md, environments/dev/env.conf, base/{versions.tf, iam.tf, storage.tf, network.tf, outputs.tf}, emr-run/{main.tf, terraform.tfvars}, runs/spark-dev-run-01.conf.

## Changes by ID

- M1: wrapper derives the authority from `hdfs getconf -confKey fs.defaultFS` (scheme hdfs, non-empty DNS host ≤253, optional port 1-65535, at most a trailing `/`; else exit 3) and passes `hdfs://<authority>/scholarchain/checkpoints/<submission id>`, event log `hdfs://<authority>/scholarchain/spark-events`, `spark.yarn.stagingDir` `hdfs://<authority>/scholarchain/staging`. Input and output must be non-overlapping `s3a://<data bucket>/<segments>`; config and pairs absolute normalized local paths; run paths relative; segments `[A-Za-z0-9._=-]`, not empty, `.` or `..`. run-bounded-spark.sh applies the same checks. `hdfs:///` default removed from bootstrap spark-defaults. `--self-check <sample fs.defaultFS>` prints the argv without AWS or HDFS calls; check-submit-args.sh drives 4 accepted and 18 refused values (not executed: no row).
- M2: EMR step `["/usr/bin/timeout","-k","60","<step_timeout_minutes x 60>", wrapper, ...]` with `ActionOnFailure: TERMINATE_CLUSTER`; wrapper runs spark-submit under `/usr/bin/timeout --foreground -k 60 <budget - 240 s>`; `--step-timeout-seconds` 300-1800, `--kill-after-seconds` 10-120; after add-steps the script reads the step back and cancels unless TERMINATE_CLUSTER and the exact timeout prefix are recorded; trap INT, TERM, HUP installed before the run record; message corrected; poll deadline step timeout + 60 + 120 s; bootstrap fails unless `/usr/bin/timeout` is GNU coreutils. `keep_job_flow_alive_when_no_steps` stays true (trade-off recorded in emr-run/main.tf). Idle timeout 1800 s -> 900 s: `--execute` within 15 minutes of the apply.
- m1: `empty-buckets base` requires same-named, same-size local copies of every `submissions/` object, each run's `spark-events/<id>/` and `emr/<cluster>/steps/<step>/` logs, and `listings/output-spark-prefixes.txt` equal to current run=/load= prefixes; a human `notes/<id>.txt` may waive a missing summary, event log or step logs, never an existing object; `inventory` prints ARCHIVE-COMMAND lines; only the repo and its subdirectories refused; `empty-buckets bootstrap` requires the base listing.
- n1: yarn-site `yarn.resourcemanager.am.max-attempts=1`, checked by run-bounded-spark.sh.
- n2: deploy.sh requires `region=` in the meta and the deploy Arn equal to `admin_principal_arn`.
- 6a: unconditional `s3:ListBucket` on the data bucket for R2 and R3 (absent keys give 404); Get, Put, Delete stay prefix-scoped.
- 6b: emr-run adds a KMS interface endpoint (private DNS), its security group and HTTPS-from-VPC ingress; cluster depends on it. base adds egress for primary and core SGs: tcp 443 to the S3 prefix list, all within the VPC CIDR; outputs vpc_id, vpc_cidr. No NAT, IGW or public IP. Not added (no node-side calls expected; UNVERIFIED): elasticmapreduce, sts, ec2, logs endpoints. Endpoint price UNVERIFIED (reference 0.01 USD per endpoint-AZ-hour + 0.01 USD/GB; budgeted 0.02 USD/h; ~0.24 USD/day until Phase A).
- 6c: R2 GetObject adds `output/`.
- 6d: wrapper creates `/scholarchain` as HDFS superuser (`sudo -n -u hdfs`) owned by the step user, mode 0750; verifies owner; creates spark-events/, staging/, checkpoints/; refuses an existing checkpoint directory.
- Identity: `admin_principal_arn` variable (no default; `^arn:aws:iam::<12 digits>:user/[A-Za-z0-9._-]{1,64}$`; same account) used in R3 trust (direct principal), three bucket-policy delete exceptions (`ArnNotEquals`) and the new R5 trust. New R5 `scholarchain-dev-readonly` with AWS managed ReadOnlyAccess (ViewOnlyAccess judged insufficient for plan; UNVERIFIED), output `readonly_role_arn`. env.conf config v2. plan.sh: read-only profile default, always for emr-run; deploy profile only if `SCHOLARCHAIN_PLAN_FIRST_WITH_DEPLOY_PROFILE=<bootstrap|base>` names the stack, CLAUDECODE unset, TTY, and `iam get-role` for R5 returns NoSuchEntity; meta records `plan_profile`. README profiles: `scholarchain-login` (aws login), `scholarchain-admin` (`credential_process = aws configure export-credentials --profile scholarchain-login --format process`), `scholarchain-readonly` and `scholarchain-operator` (`source_profile = scholarchain-admin`). None tried; Terraform reading these credentials UNVERIFIED.

## Recomputed bound

Window 20 provisioning + 15 idle before + 31 step (1800 + 60 s) + 15 idle after + 5 termination = 86 min. 0.4903 USD/h compute and EBS + 0.02 USD/h endpoint bound = 0.5103 USD/h x 86/60 = 0.7314 USD cluster-side (max_cost_usd = 1); S3 requests bound 0.24; one run ~0.97 USD; base 7 days ~0.24 USD; total ~1.21 USD, plus ~0.24 USD/day if Phase A is delayed.

## Expected plans (not observed)

bootstrap 7 add; base 40 add; emr-run 4 add (cluster, KMS endpoint, endpoint SG, ingress); 0 change, 0 destroy. IAM: 4 roles (R1, R2, R3, R5), 2 managed attachments, 3 inline policies, 1 instance profile, 3 bucket policies, 1 key policy.

## Privilege delta

R2 and R3 ListBucket on the data bucket unconditional; R2 GetObject `output/`; R3 trust and bucket-policy exceptions now the single IAM user ARN; new R5 ReadOnlyAccess (reads every object in the account; no writes; no kms:Decrypt or GetSecretValue, UNVERIFIED); primary and core egress tcp 443 to the S3 prefix list and all within the VPC CIDR; KMS endpoint SG ingress 443 from the VPC CIDR. No IAM write permission added.

## Assumptions and risks (as reported)

Steps run as `hadoop` with passwordless sudo and an `hdfs` user exists; GNU timeout on nodes; spark-submit execs to the JVM; AddJobFlowSteps with TERMINATE_CLUSTER needs no TerminateJobFlows although R3 denies it (UNVERIFIED; if wrong, add-steps is denied and nothing runs); EMR pushes step logs on termination; `aws login` credentials report the IAM user ARN; endpoint price. No fmt/validate/plan; check-submit-args.sh, run-bounded-spark.sh and cleanup.sh never executed; endpoint set may be insufficient (fails cheaply); 15-minute execute window; R5 reads every object; read-only profile sources admin credentials via credential_process in the same OS account (agent boundary is the guard's mandatory `--profile <RO>` plus the role); scripts mode 0644.

Commands: `git ls-files --others --exclude-standard -- infra scripts`, `git ls-files -- modules/spark/src/main/scala/scholarchain/spark/graph`, terraform fmt (127), `bash -n` x7 (0; four re-run, 0), shellcheck (127), `git status --porcelain`.
