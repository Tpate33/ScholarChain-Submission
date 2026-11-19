# Phase 06: emr-run launch attempt 3 (bootstrap failure), Phase A, and the archive-listing and CLI fixes (2026-11-19)

Orchestrator-condensed record; exact values and verdicts kept. The diff is recorded by the commit that contains this file. Deploy-engineer, infra-engineer and code-reviewer reports are condensed; full text in the session transcript.

## Plan review (DE-P6-07)
- Human Terminal at b50d8be: dry run RESULT DRY-RUN-OK; `--stage` recheck RESULT STAGED; `scripts/plan.sh dev emr-run` add=4 change=0 destroy=0 replace=0, PLAN-SHA256 f744d11e992e2a170dae4ee0020cbc6d45de3f08d0c5c23a644d1302888bcacd, RESULT PLAN-OK.
- DE-P6-07: HEAD clean; shasum = meta (readonly, 4/0/0/0, 663570374448, us-east-1, created_at 2026-11-19T16:39:35Z); `terraform show` 4 creates, no drift; no active cluster; cluster tags exactly Name + for-use-with-amazon-emr-managed-policies="true" (plus the 5 defaults); KMS resources without the managed tag; all other fields equal DE-P6-05. Flags none. Worst case 0.7314 USD / 86 min.

## Launch attempt 3
- Signed outside the repository. `scripts/deploy.sh dev b50d8be… f744d11e…`: gates ok (admin profile, quota 16.0 vCPU); created sg-0cc171bda53f0aa11, sgr-085d7854d3b04cdea, vpce-04740c420b3526fbb (1m4s); aws_emr_cluster.run failed after ~2m20s: `unexpected state 'TERMINATING' ... BOOTSTRAP_FAILURE: On the master instance (i-0ed85958223cd0b06), bootstrap action 1 returned a non-zero return code`; deploy.sh FAIL exit=5. The attempt-2 service-role EC2 denial did not recur (instances launched).
- `--execute` was not run (a typed-ahead command was cancelled at a shell continuation prompt).

## Diagnosis (DE-P6-08, read-only)
- Cluster j-07561051T9CLB5NGYBZY TERMINATED_WITH_ERRORS (created 17:49:36Z, ended 17:56:01Z); ErrorDetails BOOTSTRAP_FAILURE_PRIMARY_WITH_NON_ZERO_CODE, return-code 3, script s3://scholarchain-dev-data-663570374448-us-east-1/artifacts/emr-scripts/07d11283…/install-spark-dist.sh; 8 bootstrap args as tfvars; NormalizedInstanceHours 0; steps []; no active cluster; no object under submissions/ (no run record); all 7 staged artifacts present.
- Bootstrap logs in s3://scholarchain-dev-logs-663570374448-us-east-1/emr/j-07561051T9CLB5NGYBZY/node/: master i-0ed85958223cd0b06 and core i-0f9016c99e0f2cbda; bootstrap-actions/1/stderr.gz (134 B) and stdout.gz (552 B) byte-identical on both nodes (same ETag).
- Both buckets SSE-S3, no EMR log encryption: the logs did not depend on the KMS endpoint.
- Human archive (read-only): ~/ScholarChain-archive/phase-06/attempt-3-logs/ (master, core, SHA256SUMS.txt). All six digest CHECK lines passed. stderr: `[install-spark-dist] FAIL exit=3 spark-3.5.5-bin-hadoop3-scala2.13.tgz has 2 entries outside spark-3.5.5-bin-hadoop3-scala2.13/ or with unexpected characters`.
- Human macOS listing of the pinned tgz: no entry of a type other than `-`/`d`; exactly two names fail the name regex: `spark-3.5.5-bin-hadoop3-scala2.13/examples/src/main/python/ml/__init__,py` and `spark-3.5.5-bin-hadoop3-scala2.13/examples/src/main/python/sql/streaming/__init__,py` (comma, 0x2C, outside `[A-Za-z0-9._+=@-]`; upstream Spark 3.5.5 names, confirmed by infra-engineer in the attempt-4 extracted copy).
- aws-cli/2.34.61 rejects `aws emr list-bootstrap-actions` and `aws emr list-instance-groups` ("Found invalid choice", exit 252), observed by deploy-engineer, infra-engineer and the human; `--execute` called both.

## Phase A (human Terminal, HEAD b50d8be)
- plan-destroy: 0 add, 0 change, 3 destroy (aws_security_group.kms_endpoint, aws_vpc_endpoint.kms vpce-04740c420b3526fbb, aws_vpc_security_group_ingress_rule.kms_endpoint_https); PLAN-SHA256 d6ee363169d0f8a83f0c3e774cea97b36460561faf23a7f0ffd37397a151851b; RESULT DESTROY-PLAN-OK.
- apply-destroy 18:09:00Z: endpoint destroyed in 4m54s; `Apply complete! Resources: 0 added, 0 changed, 3 destroyed.`; RESULT DESTROYED stack=emr-run at=2026-11-19T18:14:00Z. Staged S3 objects untouched.

## IE-P6-09 and repairs 01-03 (infra-engineer)
- run-bounded-spark.sh `--execute`: bootstrap action (exactly 1; Name install-scholarchain-spark; ScriptPath; compact Args equal to the 8 pins in order) and instance groups (INSTANCE_GROUP, non-empty; count, type and AutoScalingPolicy checks unchanged) read from the already fetched describe-cluster; READ_VERBS and plan output updated; comment cites aws-cli/2.34.61; per-group whole-number count guard (EX_AWS). emr-run/main.tf: comment only.
- install-spark-dist.sh: listing checks in `check_archive_listing` (LC_ALL=C tar): type only `-`/`d`; name regex (dots escaped) or exact match of the two fixed ODD_NAMES; each present exception exactly once and a regular file; `..` refused; grep errors fail closed; up to 20 OFFENDING lines before FAIL; `--check-archive <tgz>` mode (listing only, no download, sudo or install). The 8-argument EMR path and digest-before-listing order unchanged.
- New scripts/emr/check-archive-listing.sh (human macOS Terminal): pinned archive and a mini archive pass; 16 crafted archives must be refused with the intended reason (dotdot, absolute, outside, second-top, symlink, hardlink, comma-x, comma-near, space, newline, control, odd-dir, odd-symlink, odd-hardlink, odd-dup, odd-dir-noslash); macOS metadata off.
- terraform.tfvars install_script_sha256: 07d11283933c63afda1b03c6fe046bace19444ccc4a42e0df4dabf98fbbd8ff0 → 38835028c231444ec2fa6c6e7ec180bcb1bfbae09f6acaa29ecb4af4acb7c2a1 (human `shasum -a 256`, archived install-spark-dist-sha256.txt); provenance comment rewritten.
- bash -n, shellcheck, `terraform fmt -check -recursive`, emr-run `validate` pass.
- Human Terminal: `bash scripts/emr/check-archive-listing.sh` → every case `CASE ok ... reason asserted`, `RESULT PASS cases=18` (archived check-archive-listing-1.txt).
- No script cross-checks the tfvars pin against the committed file's sha256 before apply; manual check: the dry run's `CONFIRM install_script_sha256=` line and the bootstrap path in `terraform show` of the emr-run plan must equal tfvars line 17 (install_script_sha256). Hardening deferred.
- Repair 04 (CR-P6-46 minor and nit): tfvars comment only; "(attempts 1-3; attempt 3 failed BOOTSTRAP_FAILURE on the comma entries)" and a header sentence excepting the two script pins from "must equal the run config". Orchestrator `git diff` confirmed no value line changed besides install_script_sha256.

## Reviews
- CR-P6-43 (CLI change): APPROVE. Minor: scripts/run-bounded-pipeline.sh:993/1000 uses list-instance-groups/list-instance-fleets (inactive script; repair before activation; deferred). Nits fixed in repair 01. Unverified: whether describe-cluster's merged InstanceGroups keep AutoScalingPolicy (other cost bounds hold).
- CR-P6-44 (archive fix): APPROVE. Minor (check 3 unexercised, exit-code-only cases) and nits fixed in repair 02; AppleDouble concern addressed.
- CR-P6-45 (test script): APPROVE; three cosmetic nits deferred (OFFENDING prefix for newline/control, literal cases=18, silenced cleanup error).
- CR-P6-46 (pin): APPROVE. Value 64 lowercase hex; submit_script_sha256 and all other values unchanged (equal human-shasum-2026-11-18.md and the run config); no automated tfvars-to-file cross-check before apply (first automated comparison is `--execute` on a WAITING cluster; a wrong pin would show as BOOTSTRAP_FAILURE at apply). Minor (comment overstated attempts 1-2 as comma failures) and header nit fixed in repair 04; orchestrator nit (line number) fixed in this file. Hazard: the old 07d11283… object stays staged, so a lost pin edit would silently bootstrap the old script.

## Deferred
- Factory amendment: allowlist rows `aws emr list-bootstrap-actions` and `aws emr list-instance-groups` name subcommands aws-cli/2.34.61 rejects.
- Read-only role denied elasticmapreduce:GetAutoTerminationPolicy (base IAM, human-gated security change).
- run-bounded-pipeline.sh CLI repair; tfvars-pin cross-check hardening; CR-P6-45 nits; DescribePrefixLists (only if a later attempt fails on it).
