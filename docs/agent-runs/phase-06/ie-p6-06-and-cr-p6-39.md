# IE-P6-06 (stage smallest objects first) and CR-P6-39 review

Orchestrator-condensed record; exact values and verdicts kept. The diff is recorded by the commit that contains this file.

## IE-P6-06 (infra-engineer) — STATUS: COMPLETE
- File modified: scripts/run-bounded-spark.sh only (scripts/emr/* and every .tf untouched).
- Change: --stage order is now install script, submit script, analysis config, benchmark pairs, hadoop-aws, scala-library, job jar, the input tree files (manifest order), aws-java-sdk-bundle, Spark tgz, tree manifest (last). `STAGE_LIST` split into `STAGE_LIST_BEFORE_INPUT` and `STAGE_LIST_AFTER_INPUT`; print_plan lists the same order. Create-once (head-object + ChecksumSHA256 compare, `--checksum-sha256`, `--if-none-match "*"`), refusal on mismatch, token material and the --execute checks unchanged. Success line `CHECK ok staged <key> bytes=<n> seconds=<s>`; failure `FAIL exit=4 put-object <key> failed after <s>s, bytes=<n> (AWS CLI error above; nothing is overwritten)`; the CLI's stderr (S3 error text) still passes through. No `--debug`, no checksum-setting pin.
- Commands: `bash -n scripts/run-bounded-spark.sh` exit 0; `shellcheck scripts/run-bounded-spark.sh scripts/lib/common.sh` exit 0. No dry run (dirty tree), no AWS call.
- Reading the next --stage: small objects stage, a large one fails → H3 (network/large body); first small object fails with explicit AccessDenied → H1 (base iam.tf change to R3 StageCreateOnce and R2 CopyEventLogCreateOnce); first small object fails with connection closed → network/proxy at all sizes; 412 → object appeared in between, nothing overwritten. Multipart would also need IAM (CreateMultipartUpload/UploadPart carry no If-None-Match).

## CR-P6-39 (code-reviewer) — VERDICT: APPROVE
- Check 1 pass: lists hold the same 10 keys once each; print_plan matches the stage calls; execute checks loop over EXPECT (all 10 keys with their sha256); create-once unchanged; token material has no order/timing term.
- Check 2 pass: `file_bytes` is `wc -c < file | awk` (common.sh:64), no stat flavour issue, already used by build_tree and cleanup.sh; `local` declared separately so failures aren't masked; `SECONDS` safe under set -u; `die` prints via printf %s.
- Check 3 pass: tree manifest staged last after all inputs and the large artifacts; --execute tree verification (checksum, downloaded manifest hash, key/size cmp) does not depend on order.
- Check 4 pass: no `--debug`; only key, bytes and seconds added to output; credential unset line unchanged.
- Check 5: the first put (install script, a few KB) uses the same StageCreateOnce statement and request shape as the Spark tgz, so it discriminates H1 vs H3. Caveat: botocore may add Expect: 100-continue only above a size threshold (unverified); does not affect the H1 test.
- Nits (infra-engineer, deferred): n1 "smallest first" is a fixed order, not a size sort; n2 the input loop's stdin is inherited by the aws calls (if the CLI ever read stdin, manifest lines could be skipped; --execute's key-set cmp would still refuse) — suggested `</dev/null` hardening.
- Unverified: shellcheck/bash -n not re-run by the reviewer; whether the n2 stdin pattern predates this change.
