# Phase 06: base read-only role applied, run attempt 2 (wrapper picked Java 8), Phase A, and the Java 17 selection fix (2026-11-19)

Orchestrator-condensed record; exact values and verdicts kept. The diff is recorded by the commit that contains this file. Deploy-engineer, infra-engineer and code-reviewer reports are condensed; full text in the session transcript.

## Commits and checks after IE-P6-10 / IE-P6-11
- b0ecbcc (IE-P6-10, add-steps CLI shape, attempt=2) and 3892b0f90ac43bdd9fbc659fe58b964ab4fbf042 (IE-P6-11, base read-only role inline policy).
- Human Terminal at 3892b0f: dry run RESULT DRY-RUN-OK, submission a2-21cbfd0fcbb09435; STEPS-JSON extracted, `jq` check true; `aws emr add-steps --profile scholarchain-readonly --cluster-id j-06940833JLSMC7HH8ZE4 --steps file://…` → `AccessDeniedException ... AddJobFlowSteps` (the CLI accepted and converted the flat shape; no step possible).
- Base plan (readonly): 1 create aws_iam_role_policy.readonly_emr_extra (Allow elasticmapreduce:GetAutoTerminationPolicy on arn:aws:elasticmapreduce:us-east-1:663570374448:cluster/*), add=1 change=0 destroy=0 replace=0, PLAN-SHA256 c33283f2c240e2c063455e4c4eee3a8b67313b6f8c432e433fdb41ad76fea07b. DE-P6-11: shasum = meta (created_at 21:52:54Z), exactly that create, R5 ReadOnlyAccess attachment and other roles untouched; flags none; cost none.
- Signed; `scripts/deploy.sh dev 3892b0f… c33283f2…`: `Apply complete! Resources: 1 added, 0 changed, 0 destroyed.`; RESULT APPLIED env=dev stack=base at=2026-11-19T21:59:23Z. Read-only `aws emr get-auto-termination-policy --cluster-id j-06940833JLSMC7HH8ZE4`: first AccessDenied (IAM propagation), retry `{"AutoTerminationPolicy": {"IdleTimeout": 900}}`.

## Run attempt 2
- `--stage` recheck at 3892b0f: every object "already staged", RESULT STAGED submission_id=a2-21cbfd0fcbb09435 at=2026-11-19T22:05:36Z.
- `scripts/plan.sh dev emr-run`: add=4 change=0 destroy=0 replace=0, PLAN-SHA256 9ea9ddb0472330148c47b16917318656654f1f0e99accf572d4a77bc1e67d8e9. DE-P6-12: shasum = meta (created_at 22:06:10Z); no emr-run or scripts/emr change since 66b6d60; every field equals DE-P6-09; no active cluster; submissions/ held only a1-3752b3deafaabde2; planned cluster passes every --execute check. Flags none. Worst case 0.7314 USD / 86 min.
- Signed; deploy.sh: RESULT APPLIED; cluster j-012151719ZVXGHAG9DXG created 22:14:01Z, WAITING 22:18:49Z.
- `--execute` (execute-attempt-2.txt): all checks ok; run record submissions/a2-21cbfd0fcbb09435/run-record.json (3467 B); add-steps → step s-046206027FR8L1OQMFZ7 (IE-P6-10 fix works); first poll: ActionOnFailure TERMINATE_CLUSTER and timeout prefix ok; step 22:19:24.3Z–22:19:30.7Z FAILED, FailureDetails "Unknown Error."; run-summary STEP-FAILED (3659 B); `FAIL exit=5`.
- DE-P6-13 (read-only): no YARN container logs, no spark-events/a2-21cbfd0fcbb09435/, no output/spark/ objects — Spark did not start; cluster TERMINATING (STEP_FAILURE), instances terminated 22:20:50Z; step logs controller.gz 2017 B, stdout.gz 267 B, stderr.gz 162 B.
- Human archive ~/ScholarChain-archive/phase-06/attempt-a2/ (submission/, step-logs/, text/). stderr: `[submit-collaboration-job] FAIL exit=4 java on the primary node is not 17 (/usr/lib/jvm/java-1.8.0-amazon-corretto.x86_64/jre/bin/java)` / `Command exiting with ret '4'`. stdout: locations ok; job.jar, analysis.conf, benchmark-pairs.json sha256 ok. controller: JAVA_HOME=/etc/alternatives/jre, `_=/usr/lib/jvm/jre-17/bin/java`, PATH /usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:…

## Phase A (human Terminal, HEAD 3892b0f)
- plan-destroy 0/0/3 (terminated cluster dropped on refresh; the read-only refresh no longer denied); apply-destroy: endpoint vpce-03d6c5f8905306a09 8m35s, SG sg-03f20becba9f71bae; `0 added, 0 changed, 3 destroyed`; RESULT DESTROYED stack=emr-run at=2026-11-19T22:50:33Z.

## IE-P6-12 and repairs 01-02 (infra-engineer)
- Cause: the wrapper used `readlink -f "$(command -v java)"` (first PATH java, /usr/bin/java → Corretto 8), ignoring JAVA_HOME.
- submit-collaboration-job.sh: select_java17 over /usr/lib/jvm candidates jre-17 then java-17-amazon-corretto.x86_64; each must exist, resolve (readlink -f) to <root>/*/bin/java, be an executable regular file and report `version "17.`; `java -version` bounded at 10 s by a bash watchdog (timeout = candidate failure); else `FAIL exit=4 java on the primary node is not 17 (...)`, no fallback. The selected home feeds JAVA_HOME for spark-submit, spark.yarn.appMasterEnv.JAVA_HOME and spark.executorEnv.JAVA_HOME. `--check-java <root> [<bound 1..10>]` and `--self-check <fs> <stub JVM root>` call the same functions as the real run. EMR argv path unchanged.
- check-submit-args.sh: stub JVMs in one mktemp dir (no rm -rf), attempt-2 environment (JAVA_HOME and first PATH java = 1.8 stub); accepted fs cases assert the three JAVA_HOME args equal the selected home (two layouts, two homes); no-Java-17 root → exit 4 without argv; java cases (a) old selection → 1.8, new → 17; (a2), (a3), (b), (c1), (c2), (d); timeout cases (e1) bounded 2s, (e2) bounded 1s, (e3) bound refused.
- Run config attempt=2 → 3 (comment records a2-21cbfd0fcbb09435). terraform.tfvars submit_script_sha256 ccf45f6cff9e202338dc377d559308abe028e5115d2bcee02e80d37c393db34f → 8ed99c72e9636eef7d1192a61a4c5255ae3142e5ff5e4a46fd733d069ad5fcf3 (line 20; human shasum, archived submit-wrapper-sha256.txt); install pin unchanged.
- bash -n, shellcheck, fmt -check, emr-run validate pass. Human Terminal: `bash scripts/emr/check-submit-args.sh` → every CASE ok, RESULT PASS (archived check-submit-args-ie-p6-12.txt).

## Reviews
- CR-P6-49: APPROVE; m1 (unbounded java -version), m2 (self-check used a constant Java home), n1 (temp-dir leak) fixed in repair 01; n2, n3 cosmetic.
- CR-P6-50: APPROVE, no findings. Unverified: bash 3.2 `wait` after early reap (the Mac run passed, so it behaves), cosmetic "Killed" notice.
- CR-P6-51 (pin): APPROVE, no findings. Line 20 equals the archived submit-wrapper-sha256.txt hash byte for byte; 64 lowercase hex; install pin and every other value unchanged; comment accurate. Orchestrator `git diff` confirmed the tfvars change is the comment block and that value only.
- Residual: the selected Java 17 home on the core node is inferred from the same image, not observed (a missing path fails closed under TERMINATE_CLUSTER and the step timeout); the wrapper's hdfs/hadoop CLI calls still run on the inherited Corretto 8 (worked in attempt 2).

## Deferred
- CR-P6-47 n1, n2; CR-P6-48 nit; CR-P6-45 nits; CR-P6-49 n2, n3; run-bounded-pipeline.sh CLI repair; tfvars-pin cross-check hardening; factory amendment for the allowlist rows naming list-bootstrap-actions and list-instance-groups; DescribePrefixLists (only if a later attempt fails on it).
