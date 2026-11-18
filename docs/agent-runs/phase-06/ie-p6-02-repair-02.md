# Phase 06 IE-P6-02 repair 02: infra-engineer, run-blocker follow-up after CR-P6-28 (2026-11-17)

- STATUS: COMPLETE (authoring; no AWS calls). Modified (untracked): infra/terraform/environments/dev/base/iam.tf, infra/terraform/README.md, scripts/run-bounded-spark.sh, scripts/emr/submit-collaboration-job.sh, scripts/cleanup.sh.
- Checks: `bash -n scripts/run-bounded-spark.sh` and `bash -n scripts/emr/submit-collaboration-job.sh` exit 0, no output. cleanup.sh not checked (guard). terraform fmt/validate/plan, shellcheck, scan not run. One `ls -R` blocked by the guard. Also `git ls-files --others --exclude-standard -- infra/terraform scripts` (22 untracked) and `git ls-files -- infra/terraform scripts` (3 tracked).

## Changes

1. R3: `elasticmapreduce:TerminateJobFlows` removed from Deny `NeverChangeClusterLifecycleOrCapacity`; new Allow `EmergencyStopOwnEnvironmentClusters` on `arn:aws:elasticmapreduce:<region>:<account>:cluster/*` with `aws:ResourceTag/project`=var.project and `aws:ResourceTag/environment`=var.environment. README "emergency stop" bullet.
2. Read-back accepts `TERMINATE_CLUSTER` or `TERMINATE_JOB_FLOW`; anything else, empty, or missing timeout prefix cancels (STEP-UNBOUNDED); recorded value printed.
3. m1-r: `require_mirrored` always runs for `spark-events/<id>/`; cluster and step from the summary, or cluster from the run record (then all `emr/<cluster>/steps/` mirrored); a note waives only an empty listing; an existing unarchived object is always refused.
4. n3: `HC_MIN_STEP_TIMEOUT_MINUTES=6` (range 6..30); wrapper `MIN_STEP_TIMEOUT_SECONDS = POST_RESERVE_SECONDS + MIN_SPARK_SECONDS` = 360.
5. After `SPARK_RC == 0` and the event-log copy, `remove_own_checkpoint` rebuilds and compares the expected `hdfs://<authority>/scholarchain/checkpoints/<id>` (id `^[a-z0-9-]{8,64}$`), `hdfs dfs -rm -r -skipTrash` of that one path under bounded timeouts; failure logged as `NOTE checkpoint-cleanup`, non-fatal.

## Privilege delta (R3 only)

TerminateJobFlows: denied on `*` -> allowed on clusters in this account and Region tagged project=scholarchain and environment=dev (the emr-run cluster and any other cluster with both tags). All other denies unchanged. Whether TerminateJobFlows honours `aws:ResourceTag` is unverified; if not, the result is no termination permission, never wider.

## Risks (as reported)

Security change for human ratification; a mistaken terminate loses the in-flight run (cost only falls); add-steps termination check still unverified; stricter step-log mirroring when the step id is unknown; script hashes changed (fresh `--dry-run` and `--stage` needed); checkpoint removal unobserved on a cluster.
