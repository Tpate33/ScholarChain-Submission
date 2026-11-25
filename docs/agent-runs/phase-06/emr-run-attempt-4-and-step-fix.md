# Phase 06: emr-run launch attempt 4 (add-steps refused client-side), Phase A, the add-steps shape fix and the read-only role repair (2026-11-19)

Orchestrator-condensed record; exact values and verdicts kept. The diffs are recorded by the commits that contain this file and the IE-P6-11 change. Deploy-engineer, infra-engineer and code-reviewer reports are condensed; full text in the session transcript.

## Plan review (DE-P6-09)
- Commit 66b6d609dd364c7fd5fe6f9d4110dfb6a05f24fa (IE-P6-09). Orchestrator's first Bash after it refused UNATTRIBUTED (scripts/emr/check-archive-listing.sh, the human's chmod +x); rerun clean.
- Human Terminal at 66b6d60: PIN-MATCH (file sha256 = archived shasum = tfvars line 17); dry run RESULT DRY-RUN-OK with CONFIRM install_script_sha256=38835028…; --stage RESULT STAGED (one new object); `scripts/plan.sh dev emr-run` add=4 change=0 destroy=0 replace=0, PLAN-SHA256 a1b43d534dd0068be04e706dd0b4920a12d1a8a31893afd3a85be0d6a6b3a218, RESULT PLAN-OK.
- DE-P6-09: HEAD clean; shasum = meta (readonly, 4/0/0/0, created_at 2026-11-19T20:19:08Z); 4 creates, no drift; bootstrap path …/artifacts/emr-scripts/38835028c231444ec2fa6c6e7ec180bcb1bfbae09f6acaa29ecb4af4acb7c2a1/install-spark-dist.sh, 8 args equal tfvars and run config; all other fields equal DE-P6-07. New install object 11142 B (20:17:21Z, SHA256 FULL_OBJECT); 7 earlier artifacts untouched. No active cluster. Flags info only.

## Launch attempt 4
- Signed outside the repository; `scripts/deploy.sh dev 66b6d60… a1b43d53…`: RESULT APPLIED. Cluster j-06940833JLSMC7HH8ZE4 created 21:03:43Z, WAITING 21:08:12Z (bootstrap succeeded: the IE-P6-09 archive fix works on EMR GNU tar).
- `--execute` (log ~/ScholarChain-archive/phase-06/execute-attempt-1.txt), 21:08:35Z–21:08:56Z: every check passed (head, clean tree, code scope, run config, operator identity; cluster WAITING, 2 x m5.xlarge, idle 900 s, pinned bootstrap via describe-cluster, no active step; 10 artifacts and 82 input objects match). Run record written: submissions/a1-3752b3deafaabde2/run-record.json (3594 B). Then `emr add-steps --steps file://…/steps.json` → `aws: [ERROR]: An error occurred (ParamValidation): Parameter validation failed: Unknown parameter in [0]: "HadoopJarStep", must be one of: Type, Name, ActionOnFailure, Jar, Args, MainClass, Properties, LogUri, EncryptionKeyArn`. run-summary.json (3597 B, status SUBMIT-FAILED) written; `FAIL exit=4 add-steps returned no step id`.
- DE-P6-10 (read-only): list-steps {"Steps": []} (no step submitted: client-side validation); cluster WAITING; output/spark/ empty.
- The cluster idle-terminated: TERMINATED 21:29:46Z, "Terminated according to the attached auto-termination policy after 900 idle seconds".

## Phase A (human Terminal, HEAD 66b6d60)
- IE-P6-10 edits stashed (`git stash push -m ie-p6-10`) for a clean tree.
- First plan-destroy at 21:24:53Z (cluster not yet terminated) failed: `reading EMR Cluster (j-06940833JLSMC7HH8ZE4) auto-termination policy: ... AccessDeniedException: ... assumed-role/scholarchain-dev-readonly/... is not authorized to perform: elasticmapreduce:GetAutoTerminationPolicy`; FAIL exit=5. cleanup.sh has no read-profile override for emr-run (lines 63-71).
- After TERMINATED: plan-destroy 0/0/3 (KMS SG sg-0e3abf2f731345a18, vpce-016d45ebafa2b276b, sgr-01fe55d4e0ebcdca1; the terminated cluster dropped from state on refresh); apply-destroy: endpoint 9m6s, `0 added, 0 changed, 3 destroyed`, RESULT DESTROYED stack=emr-run at=2026-11-19T21:44:34Z. Stash restored (2 files). Staged artifacts, run record and logs untouched.

## IE-P6-10 (infra-engineer): add-steps in the aws-cli shape
- run-bounded-spark.sh: steps file `[{Type:"CUSTOM_JAR", Name, ActionOnFailure:"TERMINATE_CLUSTER", Jar:"command-runner.jar", Args}]` (Args unchanged); jq shape guard in every mode (one step, CLI key set, fixed Type/Jar/ActionOnFailure, Args equal and timeout prefix), exit 2; dry run prints `STEPS-JSON <json>`; cancel_own_step requires its StepId with Status SUBMITTED in CancelStepsInfoList; describe-step comment. Run config attempt=1 → attempt=2 (comment; orchestrator `git diff` confirmed nothing else changed).
- Audit: list-steps, get-managed-scaling-policy, get-auto-termination-policy, list-clusters, describe-cluster observed working on aws-cli/2.34.61; describe-step paths and cancel-steps flags correct by reading. check-submit-args.sh RESULT PASS at 66b6d60 (unchanged wrapper).
- bash -n, shellcheck pass.
- CR-P6-47: APPROVE. m1 (procedure): a non-existent cluster ID stops add-steps at its implicit DescribeCluster before the step transform; use a terminated real cluster instead. Nits deferred: n1 cancel FAIL text always claims the cluster-side bound; n2 emr.steps records the CLI input shape.

## IE-P6-11 (infra-engineer): read-only role reads the auto-termination policy
- base/iam.tf: inline policy scholarchain-readonly-emr-extra on R5 (scholarchain-dev-readonly), one statement: elasticmapreduce:GetAutoTerminationPolicy on arn:aws:elasticmapreduce:us-east-1:<redacted-account-id>:cluster/* (local.emr_cluster_arns); no tag condition (support unverified; fail-closed risk). GetManagedScalingPolicy not added. Expected base plan 1/0/0. fmt -check and base validate pass.
- CR-P6-48: APPROVE; nit (comment says WAITING/TERMINATING, evidence says alive) deferred. Residual: the provider's full Read call list is unverified; another missing Get* would fail closed at the next live-cluster refresh.

## Deferred
- CR-P6-47 n1, n2; CR-P6-48 nit; CR-P6-45 nits; run-bounded-pipeline.sh CLI repair; tfvars-pin cross-check hardening; factory amendment for the allowlist rows naming list-bootstrap-actions and list-instance-groups; DescribePrefixLists (only if a later attempt fails on it).
