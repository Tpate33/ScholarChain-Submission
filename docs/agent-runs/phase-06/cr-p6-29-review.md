# Phase 06 CR-P6-29: code-reviewer focused review of IE-P6-02 repair 02 (2026-11-17)

## Verdict: APPROVE

- R3 (iam.tf): TerminateJobFlows removed from Deny `NeverChangeClusterLifecycleOrCapacity` (:313-341); Allow `EmergencyStopOwnEnvironmentClusters` (:295-309) grants only TerminateJobFlows on this account and Region's `cluster/*` with both tag conditions (StringEquals); no other statement changed; R3 has no `elasticmapreduce:AddTags`; README.md:28-30 matches.
- Read-back (run-bounded-spark.sh:600-611): accepts only TERMINATE_CLUSTER or TERMINATE_JOB_FLOW; any other, empty, failed describe-step, jq failure or prefix mismatch cancels (STEP-UNBOUNDED, exit 6); not skippable under `set -e`.
- remove_own_checkpoint (submit-collaboration-job.sh:319-351): only this submission's readonly CHECKPOINT_URI (validated authority, readonly base, id `^[a-z0-9-]{8,64}$` checked at :161 and :322); only after SPARK_RC 0 and the event-log copy; `|| true`, exit stays SPARK_RC; ends ≥15 s before the outer timeout.
- cleanup.sh:139-203: `require_mirrored` always on `spark-events/<id>/` (:154) and step logs; cluster id from archived summary or run record (both mirrored by :143); note waives only an empty listing or no cluster id. m1-r closed.
- Minimums match (run-bounded-spark.sh:50; wrapper :52). No bash error found.

## Findings

- n4 (nit, infra-engineer) run-bounded-spark.sh:50,123; submit-collaboration-job.sh:240-243,292: 360 s minimum is not passable once preparation takes ≥1 s (`spark_timeout_now` refuses below 120 s). Failure input `step_timeout_minutes=6`. Impact: one wasted window; committed config uses 30. Fix: 7 minutes or a preparation allowance. Deferred under the human order ("Defer unrelated nits"); no current run affected.

## Unverified concerns

TerminateJobFlows support for `aws:ResourceTag` (fails closed); whether AddJobFlowSteps with TERMINATE_CLUSTER checks the caller's TerminateJobFlows (no longer blockable by an explicit Deny); whether DescribeStep returns TERMINATE_JOB_FLOW; inner timeouts in remove_own_checkpoint without `--foreground` (bounded by construction); HDFS symlinks for `-rm -r`; EMR step-log layout under the log URI; cleanup checks current objects only while empty-buckets deletes all versions (pre-existing).

## Risks and manual action

R3 can terminate any cluster in the account and Region with both tags (human ratification); checkpoint removal unobserved; fresh `--dry-run` and `--stage` after hash changes. Human runs shellcheck, `bash -n scripts/cleanup.sh`, `scripts/emr/check-submit-args.sh`, terraform fmt, validate and plan.
