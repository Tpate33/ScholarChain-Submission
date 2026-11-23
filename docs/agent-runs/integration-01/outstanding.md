# Integration round 01: closeout state (orchestrator, 2026-11-23)

Records state only. It approves nothing. Human acceptance belongs in human-gate.md, which is human-owned and not written here.

## Final verification

**Human Terminal run** (recovery option 1, observed by the human):
- `sbt check`: CHECK_EXIT=0, 578 s, HEAD 2ff0e33, worktree dirty.
- suites=228, passed=2817, failed=0, errored=0, skipped=4.
- Log: /Users/taufeeqpatel/ScholarChain-archive/integration-01/manual-check-20261123-000133.txt.
- Per project: core 657, entityResolution 461 (+3 skipped), hadoop 85, rag 141, services 146, sparkAnalytics 263, storage 795, tests 269 (+1 skipped).
- The orchestrator reconciled the audit baseline afterwards: one `git status` was refused UNATTRIBUTED for `.claude/.last-test-run` only, then the re-run showed the expected tree.

**Why the counts differ from the agent-reported DE-INT-04 run** (2820 passed, 1 skipped). Three entityResolution probes were skipped, not failed. The same 3 tests passed in earlier runs. They are:
- DecisionExportSuite "committed-sample probe" (DecisionExportSuite.scala:451-456)
- GroupingExportSuite "13. committed-sample probe" (GroupingExportSuite.scala:703-713)
- SampleQueueProbeSuite "sample review-queue strata" (SampleQueueProbeSuite.scala:24-29)

Each probe calls munit `assume` on a completed Hadoop local-pipeline run under `modules/hadoop/target/test-work/local-pipeline/curated-a`, which hadoop's LocalPipelineSuite writes. TestSupport.workDir (TestSupport.scala:36-40) deletes and recreates that directory, and nothing in the build orders entityResolution's tests after hadoop's. In this run every entityResolution suite ran before LocalPipelineSuite.
- Verdict (code-reviewer lookup): an order- and leftover-dependent test environment, not a regression.
- Inferred cause: either curated-a was absent or the two suites raced; the log cannot say which.
- Coverage: the probes give no reliable coverage until fixed (entity-resolution-engineer for the probe design, build-engineer for test ordering). Deferred; not part of integration-01.

**Earlier agent run, superseded.** The agent-run verification check (background task bpai4o45c, 625 s) failed with exit 1. One test failed: sparkAnalytics LogicalReproducibilitySuite "the three runs have one run ID; the 3-partition run writes more than one part" timed out at 34.4 s against its 30 s limit. It passed in the human's run. This is a possibly load-dependent timeout; owner spark-engineer, deferred, to be watched.

**Process limit.** The harness moves any foreground call to the background at about 180 s. A full check takes about 460-625 s, so agent-run full checks cannot be audit-attributed. Recovery options 2 and 3 (split check, or hook provenance) remain human decisions.

## Orders and reviews in this round

| Order | Owner | Review | Verdict |
|---|---|---|---|
| SE-INT-01 DynamoDB adapter, durable stores | storage-engineer | CR-INT-01 | REQUEST_CHANGES, repaired by SE-INT-02 → CR-INT-02 APPROVE |
| SV-INT-01 v2 audit compatibility | service-engineer | CR-INT-03 | APPROVE |
| DE-INT-01 production config | domain-engineer | CR-INT-04 | APPROVE; repaired by DE-INT-02 → CR-INT-05 APPROVE |
| DE-INT-03 X-4 ownership-record bound | domain-engineer | CR-INT-06 (included) | APPROVE |
| SE-INT-03 storage expiry/skew (CR-INT-02-1/-2/-3) | storage-engineer | CR-INT-06 | APPROVE |
| SV-INT-02 durable service wiring | service-engineer | CR-INT-06 | APPROVE |
| SE-INT-04 S3, Neptune, S3 Vectors adapters, client bundle | storage-engineer | CR-INT-07 | REQUEST_CHANGES, repaired by SE-INT-05 → CR-INT-08 APPROVE |
| DE-INT-04 adapter and API-key config contracts | domain-engineer | CR-INT-09 | APPROVE |

Closed findings: CR-INT-01-1..7, CR-INT-02-1..3, CR-INT-03-1/-2, CR-INT-04-1/-2/-3/-4/-6 (core and services), CR-INT-05-1, CR-INT-07-1/-2, CR-P11-02-1.

## Open findings and residuals (none blocking; not accepted by the human)

- **CR-INT-06 residuals:**
  - -1: stranded idempotency key on attempt 2 rejected or interrupted;
  - -2: query-jobs without durable state does not fail closed;
  - -3: A1-W per-attempt overrun not validated or surfaced;
  - -4, -5, -6: nits.
- **CR-INT-04-5, -7, -8, -9 and CR-INT-05-2:** deferred.
- **CR-INT-07:**
  - -3: services scaladoc points to the legacy DynamoDB build;
  - -4: NoSuchBucket is reported as missing;
  - -5: interrupt handling;
  - -6: test gaps;
  - N1: guard can be bypassed by explicit injection;
  - N2: stale scaladoc.
- **CR-INT-09:**
  - -1: no API-key timing rule or TTL ceiling;
  - -2: in-memory forLauncher;
  - -3: ARN tail length;
  - -4: NEP-3 slash and case.
- **Unresolved limitations:**
  - S3 GetObject body read is not time-bounded;
  - SDK abort is not guaranteed;
  - Neptune reader endpoint is not checked;
  - Secrets Manager ARN pattern is unverified;
  - profile `endpoint_url` is not guarded.
- **Live verification needed (P):**
  - DynamoDB P1-P8;
  - five Neptune `LIMIT $limit` templates;
  - S3 Vectors filter semantics, constraint counting and us-east-1 availability;
  - 14 SDK behaviours not stated in the docs.
- **Test environment:** the 3 order-dependent ER probes, and the LogicalReproducibilitySuite timeout.

## Remaining startup dependencies (next orders)

1. **SV-INT-03 (service-engineer):**
   - launcher via `ProductionSettings.forLauncher`, never `forProcess`;
   - build `StorageClients` and close it on shutdown and on failed start (G5);
   - G4 enforcement, including rag workflow reads under the query-jobs read policy;
   - Secrets Manager ApiKeyDigestSource with the ambient-endpoint guard;
   - CR-INT-07-3 scaladoc.
   - Open questions for it: the secret's JSON layout; whether a failed refresh serves stale keys or returns 503; the auth deadline; whether the secretsmanager SDK is on the classpath (build-engineer).
2. **build-engineer:** launchers and ServiceLoader registration.
3. **rag-engineer plus human:** production answer model and embedder, or a disclosed non-production synthesizer.
4. **infra-engineer, then human:**
   - runtime Terraform;
   - IAM (s3:GetObject and s3:ListBucket, neptune-db:ReadDataViaQuery, s3vectors read actions, the five DynamoDB actions);
   - Neptune VPC access and neptune_query_timeout;
   - no `AWS_ENDPOINT_URL*` variables or profile `endpoint_url`;
   - package.sh and smoke-test.sh.
5. **Human:**
   - AWS identity and budget;
   - state of the resources applied in Phase 6;
   - acceptance of the residuals above.

## Untracked files outside this round

`AGENTS.md` and `skills/scholarchain-work-orders/SKILL.md` were created by Codex at the human's request. They are UNASSIGNED in the ownership map and are not part of integration-01. No agent may edit them; whether to commit them is the human's decision.
