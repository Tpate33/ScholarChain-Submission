# Phase 06 CR-P6-27: code-reviewer combined review of SP-P6-05, SP-P6-06, BE-P6-09, IE-P6-02 (2026-11-17)

- Scope: all uncommitted work on HEAD bd9c0a5. Read, Grep, Glob only; one repository-wide Grep refused by guard rule heldout-label-read and re-scoped to modules/spark/src and scripts/. No gold/, held-out, target/ or ~/.aws path read.

## Verdict: REQUEST_CHANGES

## Findings

infra-engineer
- M1 (major) scripts/emr/submit-collaboration-job.sh:98 passes `--checkpoint-dir "hdfs:///scholarchain/checkpoints/$O_submission_id"` (no authority); JobArguments.scala:231-232 and StorePath.scala:187-188 refuse a non-`file:` URI without authority. Every `--execute` exits 2 before any Spark session: a paid run wasted. The local gate uses a local checkpoint (SparkSubmitLocal.scala:902) and cannot catch it. Same form documented at be-p6-09-report.md:93. Fix: full `hdfs://<fs.defaultFS>/…` validated on the node (or spark-engineer accepts `hdfs:///`, owner decision); test of the wrapper's exact argument shape.
- M2 (major) cost cap not enforced cluster-side: wrapper spark-submit (:84-103) has no `timeout`; cancel only by the operator process (run-bounded-spark.sh:535-575), trap INT/TERM only (:549); emr-run/main.tf:61 `keep_job_flow_alive_when_no_steps = true` and idle auto-termination (:67-69) never fires while a step runs. A hung job plus a closed Terminal, sleep, network loss or expired credentials bills ~0.49 USD/h without limit; message at :538 is false while a step runs. Fix: `timeout -k <s> <step_timeout>` checked against the run config; trap HUP; correct the message.
- m1 (minor) cleanup.sh:84-90 `require_evidence` accepts any non-empty directory outside the repo; does not check this run's evidence (run summary, event-log key, `run=` listing, step logs) before `empty-buckets base` deletes every version. Prefix test also refuses `<repo>-archive` siblings (fails safe).
- n1 (nit) emr-run/main.tf:121-128 no yarn-site `yarn.resourcemanager.am.max-attempts`; settings otherwise consistent (install-spark-dist.sh:102, :110; wrapper :94; core-site; be-p6-09-report.md:90); StartupChecks refuses otherwise.
- n2 (nit) deploy.sh:54 does not check `region=` recorded by plan.sh:56.

build-engineer
- m2 (minor) SparkSubmitLocal.scala:1299-1312, 1331-1332: pre-compile HEAD discarded; post-compile recomputes HEAD; "HEAD must not move" not enforced (commit during compile names a new HEAD for an old jar). Fix: carry the first HEAD and refuse on difference.
- m3 (minor) SparkSubmitLocal.scala:1491-1513: comparison JVM forked via `LocalRuns.runForked` with prefix stripping only (LocalRuns.scala:756-758); JAVA_TOOL_OPTIONS, _JAVA_OPTIONS, JDK_JAVA_OPTIONS inherited by the verdict JVM. Fix: strip `StrippedNames` too.
- n3 (nit) SparkSubmitLocal.scala:676 concurrent `dest` makes `Files.createLink` throw uncaught; verified partial left behind contrary to :611-614.

spark-engineer
- none. CR-P6-26 n1-n6, u1, u2 fixed with failing-without tests (StorePathSuite.scala:89-130, CollaborationJobSuite.scala:476-519, CommitPublisherSuite.scala:306-320, StartupChecksSuite; wiring CollaborationJob.scala:128-132). Run identity unchanged. SP-P6-06 output matches `parseComparison` and `verdict`.

## Checked, sound (summary)

Assembly exclusions and check; download (sha512 before use, `Redirect.NEVER`, bounded, own partial only); tar reader (links, specials, absolute, `..`, foreign top refused; CREATE_NEW; bounded); child scrubbing of spark-submit runs; code scope raw bytes vs HEAD; guards self-test no network or fork; LocalRuns refactor keeps erDev path. IAM: no IAM write anywhere; R1 PassRole only R2 for ec2; R3 denies PassRole, terminate, run, modify, scaling; delete denies on run=/load= and all version deletes except admin. No IGW, NAT, public IP or SSH key. SSE-S3, key policy. deploy.sh applies only checksum- and meta-matched plan, refuses deletes except the emr-run cluster. cleanup.sh human-only (CLAUDECODE, TTY) and token-gated. Bootstrap verifies every digest before extraction. No hardcoded account or bucket; no secret.

## Unverified concerns

- ListBucket only with `s3:prefix` conditions (iam.tf:107-118, 317-326): HeadObject on an absent key may return 403 instead of 404 (breaks run-bounded-spark.sh `s3_head` :369-378 and S3A existence probes; fails closed, costs a run).
- Private subnet with only the S3 gateway endpoint: KMS local-disk encryption (kms_emr.tf:45-49) needs KMS reachability; EMR-managed SGs with default egress removed; provisioning may fail.
- R2 lacks GetObject on `output/` (iam.tf:123-131) for S3A ancestor probes.
- `hadoop` user HDFS permission for `/scholarchain/*`; `tar -tv` hard-link letters (install-spark-dist.sh:82; archive sha512-pinned).
- Hadoop 3.3.4 client vs YARN 3.4.2; EMR core-site seen by Apache S3A; `fs.s3a.endpoint.region` in 3.3.4.
- Pinned sha512 value, provider `= 6.0.0`, IE-P6-02 [U] items.

## Risks (not defects)

R2 can overwrite published objects (versions kept 30 days); AddJobFlowSteps runs code with R2 powers; `--code-version` operator-asserted; scripts mode 0644; terraform fmt/validate, shellcheck and scan never run; run-bounded-spark.sh and cleanup.sh untested; plan counts 7/34/1 not observed.

## Orchestrator note

be-p6-09-report.md (orchestrator-assembled evidence) quotes the refused `hdfs:///` checkpoint form in the EMR command; it is a verbatim record of the BE-P6-09 report and is left unchanged; the corrected form will be recorded with the repair.
