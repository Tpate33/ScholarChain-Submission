# Phase 06 CR-P6-26: code-reviewer focused review of SP-P6-04 (2026-11-17)

- Scope: uncommitted modules/spark changes on HEAD facb73d (sp-p6-04-report.md file list). Evidence: sparkit-run-08.md; attempt-6/result.txt read. Read, Grep, Glob only.

## Verdict: APPROVE

No critical, major or minor findings. CR-P6-25 f2 closed: reader and both publishers list, hash and copy through the output root's Hadoop FileSystem.

## Checked, sound

Protocol B (RunPublisher.scala:678-692 absence checks; :816-841 create-once copy, re-read, Parquet re-check; manifest, parts, pre-commit verification :897, `_SUCCESS` last :901, re-verify :910; only delete is the `.spark-<hex>` scratch :717; failures only write `_UNVERIFIED` :735; LoadManifests :133-166 never delete); readers refuse missing `_SUCCESS` or present `_UNVERIFIED` (VerifiedArchiveReader.scala:460-495, RunPublisher.scala:958-965). Local path unchanged (NioRunStore NOFOLLOW/CREATE_NEW RunStore.scala:84-120; protocol A for NioRunStore RunPublisher.scala:273-284; sparkIT path AnalysisRun.scala:458). StorePath refusals (StorePath.scala:113-147); `s3:`/`s3n:` refused (JobArguments.scala:96); `file:` under non-local master refused (:115-128); root overlap refused (:260-269; AnalysisRun.scala:398). Entry point: 17 flags exactly once (:170-191), placeholder codeVersion refused (:161-167), exit codes 2/1/0 (CollaborationJob.scala:92-96), no master, empty-environment config (:130); no AWS SDK, s3a literal, Region, setMaster, gold/ or heldout in src/main. Driver lists and hashes through the store; bounds passed (:149). Crash-point tests (CommitPublisherSuite.scala:209-283) and end-to-end equality tests (CollaborationJobSuite.scala:314-423) would fail without the protocol. Protocol B deletes stay inside `.spark-<hex>` (matches planned IAM R2).

## Findings (all nits, spark-engineer)

- n1 StorePath.scala:131-140: more than one trailing slash accepted and dropped, contrary to the Scaladoc (:107-111). Fix: refuse `//` endings; counter-test.
- n2 JobArguments.scala:260, AnalysisRun.scala:398: overlap checks compare the authority literally (`hdfs://nn:8020/x` vs `hdfs://nn/x/ck` not detected). No impact in the planned layout. Fix: document or normalize.
- n3 CoauthorEdgeJob.scala:85 with StorePath.scala:113-147: `,` and glob characters accepted in paths while `sc.textFile` splits on commas and expands globs; fails closed in practice (row-count check :103-104). Fix: refuse them for data roots.
- n4 AnalysisRun.scala:882: message "its load manifests were not written" inaccurate for protocol B (some may exist; LoadManifests.scala:158).
- n5 sp-p6-04-report.md:10, CollaborationJob.scala:101: the job always uses HadoopRunStore, so `file:` job runs use protocol B via RawLocalFileSystem (check-then-create, link-following open); "local whole run atomic" holds only for NioRunStore (sparkIT). Documentation accuracy only.
- n6 RunPublisher.scala:692, CommitPublisherSuite.scala:149-160: refusal when `.spark-<hex>` exists is untested; the simulated crash always runs the scratch cleanup. Fix: pre-create test.

## Risks and unverified concerns (reviewer)

- Single-writer assumption: check-then-create on S3A; recommend `spark.yarn.maxAppAttempts=1` in the run configuration (infra-engineer; client mode via command-runner as planned).
- `--code-version` is asserted by the operator, not bound to jar contents.
- s3sim covers rename semantics only (not directory markers, prefix-as-directory, S3A marker deletes, IAM denials); first observed on EMR.
- u1: no start-up check of `fs.s3a.directory.marker.retention`; without `keep`, Hadoop 3.3.x S3A would try to delete parent marker keys under `run=`/`load=` that the planned IAM denies (believed logged and ignored; unverified). Suggest a start-up refusal (spark-engineer) and confirmation in the bootstrap defaults (infra-engineer).
- u2: port-only authority (`s3a://:80/x`) possibly accepted; would fail later (fail closed); untested.
- u3: object and prefix both named `run=<hex>` makes S3A report a file; HadoopRunStore.list refuses it (fail closed); not exercised by s3sim.
