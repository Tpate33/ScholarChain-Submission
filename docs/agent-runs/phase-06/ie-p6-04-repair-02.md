# IE-P6-04 repair 02: input_tree_sha256 for dev-run-01 (infra-engineer final report)

Input: the human's first dry run at HEAD b41b249 (Terminal screenshot, 2026-11-18 11:07 PM): clean tree, code-scope CHECK ok, five artifact digests match, input tree 82 files, 1833950 bytes, expected `FAIL exit=3` on the zero placeholder. The orchestrator transcribed the computed hash from the screenshot, and infra-engineer re-derived it as text before writing it.

The report below is verbatim except its "Commands for the human" section, which the orchestrator replaced with its own commit block. The diff is recorded by the commit that contains this file.

```
STATUS: COMPLETE
OWNER: infra-engineer
WORK ORDER: IE-P6-04 repair 02 (Phase 6): write the computed input_tree_sha256 into the run config.

Summary: I ran the dry run once on the clean tree at HEAD b41b249. It exited 3, as expected, because the config still held the 64-zero placeholder. It reported the same 82 files and 1833950 bytes as the human's run. I compared the sha256 it printed with the transcribed value character by character, and they MATCH:
- my run:      0bb19182973e905c5267c1b05a451162f44a0069aa0496218be2d6eb6f5478f1
- transcribed: 0bb19182973e905c5267c1b05a451162f44a0069aa0496218be2d6eb6f5478f1
I then set `input_tree_sha256` to that value and updated its comment. Nothing else changed. I did not run the dry run again, made no other script or Terraform change, made no AWS call, and did not stage anything.

Dry-run output (verbatim, exit code 3):
[run-bounded-spark] STEP start mode=dry-run env=dev at=2026-11-19T05:08:15Z
[run-bounded-spark] CHECK ok head b41b249ce0a9e9d0f9dc5740c0c707aa3486d69a
[run-bounded-spark] CHECK ok clean tree
[run-bounded-spark] CHECK ok environment config infra/terraform/environments/dev/env.conf (account <redacted-account-id>, Region us-east-1)
[run-bounded-spark] CHECK ok code scope (build.sbt project modules/core modules/spark) unchanged between code_version 148b4b91bc904a63680b94f91f275173f743c4b6 and HEAD
[run-bounded-spark] CHECK ok run config infra/terraform/environments/dev/runs/spark-dev-run-01.conf
[run-bounded-spark] BOUND instances=2 cap=2 source=config/HC_MAX_INSTANCES
[run-bounded-spark] BOUND instance_type=m5.xlarge cap=m5.xlarge m6i.xlarge source=config/HC_ALLOWED_INSTANCE_TYPES
[run-bounded-spark] BOUND idle_timeout_seconds=900 cap=1800 source=config/emr-run idle_timeout_seconds
[run-bounded-spark] BOUND step_timeout_minutes=30 cap=30 source=config; enforced on the cluster by /usr/bin/timeout -k 60 1800
[run-bounded-spark] BOUND step_action_on_failure=TERMINATE_CLUSTER cap=- source=a failed or timed-out step ends the cluster
[run-bounded-spark] BOUND cost_window_minutes=86 cap=- source=provisioning 20 + idle 15 + step 31 (timeout + kill) + idle 15 + termination 5
[run-bounded-spark] BOUND worst_case_cost_usd=0.7314 cap=1 source=(instances x (price + EBS) + interface endpoints x rate) x window; S3 requests and storage excluded (cents); the KMS endpoint keeps billing until cleanup Phase A
[run-bounded-spark] BOUND max_cost_usd=1 cap=2 source=config/HC_MAX_COST_USD
[run-bounded-spark] BOUND steps=1 cap=1 source=one step per submission id; step concurrency 1
[run-bounded-spark] BOUND vcpus=8 cap=8 source=instances x 4 vCPU; EC2 quota L-1216C47A in us-east-1 must be >= this before emr-run is applied (checked by scripts/deploy.sh)
[run-bounded-spark] STEP local artifacts
[run-bounded-spark] CHECK ok job jar target/spark-submit-local/attempt-4/job/scholarchain-spark-jobs-0.1.0.jar digests match
[run-bounded-spark] CHECK ok spark distribution target/spark-dist/spark-3.5.5-bin-hadoop3-scala2.13.tgz digests match
[run-bounded-spark] CHECK ok scala-library target/spark-submit-local/attempt-4/runtime-jars/scala-library-2.13.16.jar digests match
[run-bounded-spark] CHECK ok hadoop-aws target/spark-submit-local/attempt-4/runtime-jars/hadoop-aws-3.3.4.jar digests match
[run-bounded-spark] CHECK ok aws-java-sdk-bundle target/spark-submit-local/attempt-4/runtime-jars/aws-java-sdk-bundle-1.12.262.jar digests match
[run-bounded-spark] CHECK ok input tree /Users/taufeeqpatel/ScholarChain-archive/phase-06/dev-run-01: 82 files, 1833950 bytes
[run-bounded-spark] FAIL exit=3 computed input tree manifest sha256 0bb19182973e905c5267c1b05a451162f44a0069aa0496218be2d6eb6f5478f1 differs from input_tree_sha256=0000000000000000000000000000000000000000000000000000000000000000 (review the tree, then commit the computed value)

FILES CREATED: none
FILES MODIFIED: /Users/taufeeqpatel/Desktop/ScholarChain/infra/terraform/environments/dev/runs/spark-dev-run-01.conf
COMMANDS RUN:
- `git status --porcelain`: empty (clean tree)
- `scripts/run-bounded-spark.sh --dry-run dev infra/terraform/environments/dev/runs/spark-dev-run-01.conf`: exit 3, output above
- `git diff`: diff recorded by the commit
- `git status --short`: ` M infra/terraform/environments/dev/runs/spark-dev-run-01.conf`
TEST RESULTS: none (config value change only; no source changed; sbt check not applicable)
VALIDATION RESULTS:
- fmt / validate / plan: not run (no Terraform change; out of scope)
- script syntax / shellcheck: not run (no script change)
- security scan: not run (B5 still open)
- dry run, the one I ran before the edit: every CHECK up to the input tree passed; the computed sha256 matches the transcribed value exactly.
ASSUMPTIONS: The archive at ~/ScholarChain-archive/phase-06/dev-run-01 is the reviewed dev input tree. It matches the human's run in file count, byte count and sha256.
RISKS: If the archive changes, the second dry run will refuse. Any commit after the second dry run invalidates its tokens.
MANUAL ACTION REQUIRED: Review the diff, commit, confirm `git status --porcelain` is empty, run the second dry run in your Terminal and check its output, then make the bootstrap plan at the final HEAD (H7).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Human commit, then the human's second dry run showing RESULT DRY-RUN-OK and the HEAD-bound confirmation tokens, then the bootstrap plan at the final HEAD (H7).
```
