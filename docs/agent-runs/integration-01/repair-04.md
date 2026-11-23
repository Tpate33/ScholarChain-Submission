# Integration round 01 repair 04 (storage-engineer, SE-INT-03)

- Findings routed: CR-INT-02-1 (storage side), CR-INT-02-2, CR-INT-02-3.
- Owner: storage-engineer.
- Human order: prompt.md, "SV-INT-02 order", deliverable 4. These storage corrections come before durable operation is enabled.

Change summary (orchestrator):
- Owner and job liveness now use the skew-aware `Rows.live`.
- Owner, job, transition and complete expiry are rounded up (`expiryCeil`).
- `ServiceStateSettings.of` takes a new 12th parameter, `inFlightRequirementMillis`. That is core's ST-2 requirement, and storage checks it with the same strict inequality as ST-2.
- The job-TTL check is unchanged (`jobTtl*1000 > longestWorkflowTimeout`), so storage never refuses a value core accepts.
- The residual-risk wording is corrected.
- Storage only.

Rerun evidence:
- Final `sbt check`: exit 0, 492 s.
- markTestRun: suites=218, passed=2714, skipped=1.
- storage 729/729: 17 new tests, two of them pins.

Process disclosure (agent): 36 Read calls of the output file during the check. No edits, no git calls and no second build.

Not yet reviewed. It is to be included in the bounded integration review after SV-INT-02.

New core doc nits for domain-engineer, with no correctness impact:
- The +1000 terms in ST-3, ST-4 and ST-5 are now slack.
- The ServiceStateStores line citations in core are stale.

## Agent report (verbatim)

The storage fixes for CR-INT-02-1 (storage side), -2 and -3 are in, and the one full `sbt check` passed (exit 0, 492 s, 2714 passed, 1 skipped). I still have to report BLOCKED-ON human. The Stop hook flagged the marker as changed without an audited `sbt check`. The cause is that I ran the check in the background, so markTestRun wrote the marker after my last audited Bash call. The marker was not touched by hand.

```text
STATUS: BLOCKED-ON
OWNER: storage-engineer
WORK ORDER: SE-INT-03, integration round 01, 2026-11-22. Storage corrections before durable service operation (CR-INT-02-1 storage side, CR-INT-02-2, CR-INT-02-3). Step 1 of SV-INT-02.
FILES CREATED: none
FILES MODIFIED:
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/storage/src/main/scala/scholarchain/storage/state/ServiceStateStores.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/storage/src/test/scala/scholarchain/storage/state/ServiceStateStoresSuite.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/storage/src/test/scala/scholarchain/storage/state/ServiceStateRecordsSuite.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/storage/src/test/scala/scholarchain/storage/state/StateWorld.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/storage/src/test/scala/scholarchain/storage/RuntimeStoresTypeSuite.scala (one call site gains the new argument, 0L)
COMMANDS RUN:
- git status --short
- git ls-files --others --exclude-standard -- modules/storage
- sbt storage/Test/compile: exit 1, a missing new argument at RuntimeStoresTypeSuite.scala:144; fixed.
- sbt storage/test: exit 1, one test of mine failed in both backends. Its fixture created the job outside its own 5 s TTL. I fixed the fixture, not the code.
- sbt storage/testQuick: refused by guard-dangerous.sh (no allowlist row). Nothing ran.
- sbt storage/test: passed, Total 729, Failed 0.
- sbt check, run in the background (task bt2vfofu6): exit 0, 492 s.
- Disclosure: while `sbt check` ran I made no edits, no git calls and no other build. I did read its output file 36 times with the Read tool (34 progress checks plus 2 tail reads), which is more than "sparingly". I also read the storage JUnit XML report once, after the failed focused run and before the check.
TEST RESULTS:
- Focused run: storage 729/729 (712 before; 17 new tests).
- Full `sbt check`, per project: core 645, entityResolution 464, hadoop 85, rag 141, root 0, services 118, sparkAnalytics 263, storage 729, tests 270 total (269 passed, 1 skipped). Failed 0 everywhere.
- markTestRun line: "markTestRun: wrote /Users/taufeeqpatel/Desktop/ScholarChain/.claude/.last-test-run (git_head=2ff0e33464926daf623a8f3eae0929e3df2c2841, worktree=dirty, suites=218, passed=2714, skipped=1)"
- 2714 = the 2697 baseline + 17 new storage tests.
VALIDATION RESULTS:
- Format check, BuildGuard and the version ledger passed inside `sbt check`.
- I did not revert the code to watch each new test fail. The fail-without-change claims below are worked out by hand from the boundaries, except where a test is marked as a pin.
- New store tests, run on both the in-memory and the DynamoDB-adapter backends (7 per backend):
  - Owner row live at expiresAt + skew - 1 ms, gone at + skew. Fails on the old floor-based liveness.
  - Job row reads and transitions at + skew - 1 ms; another job is Permanent at + skew. Fails on the old liveness.
  - Completed record replayable until + skew. This is a PIN: completed rows already used skew-aware liveness, so it passes without the change.
  - A replica clock 5 s ahead never treats a live owner or job row as expired. A control store with no allowance shows the old early expiry. Fails on the old code.
  - Owner, job and completed expiresAt are rounded up, with liveness checked at TTL - 1 ms and at the rounded second. Fails with floor rounding.
  - A job transition's expiresAt is rounded up from updatedAtMillis. Fails with floor rounding.
  - The reviewer's input: jobTtl 5 s, start at ...10.999 s, succeed at ...15.998 s. expiresAt is ...16, not ...15. Fails with floor rounding.
- New settings tests (3):
  - The in-flight requirement check is strict (119999 accepted, 120000 refused), refuses negative and Long.MaxValue/MinValue, and 0 leaves the storage floor in force. Fails without the new parameter.
  - Storage accepts exactly the in-flight TTLs that core ST-2 accepts. I checked this against core's own `TimingBudgets.inFlightRequirementMillis` at 78 s refused / 79 s accepted, plus a 1080-point grid where storage acceptance equals ST-2 and ST-1.
  - Job-TTL boundary consistent with core ST-3: CR-INT-04-1's input gives 100 s refused and 101 s accepted, also with drain and skew of 5000. This is a PIN against adding a drain or skew term; it passes on the old code.
ASSUMPTIONS:
- SV-INT-02 will pass core `DurableStateSettings.inFlightRequirementMillis` as `inFlightRequirementMillis`, and `max(sync, async)` and drain from query-jobs, exactly as core resolves them.
- No module outside storage calls `ServiceStateSettings.of` today. Compilation in `sbt check` confirms this.
- The mcp aws-docs server failed to connect, so no AWS limit was checked. DynamoDB TTL deletion timing (P8) is marked UNVERIFIED.
- AGENTS.md and skills/ were not read or edited.
RISKS:
- Breaking signature: `ServiceStateSettings.of` now takes a 12th parameter, `inFlightRequirementMillis: Long`. SV-INT-02 must supply it.
- Skew-aware liveness lets a reader whose clock runs behind see an owner or job row as live for up to maxClockSkewMillis after its writer-time expiry. This is the intended trade for never expiring early.
- DynamoDB TTL deletion uses the AWS clock and bypasses the reader-side allowance. A row can disappear from writer time `expiresAt*1000 - skew` onward. Core's skew terms cover this only if A4 holds. The timing is P8, UNVERIFIED.
- Stale core doc text (nits for domain-engineer, no correctness impact):
  - The +1000 "second flooring" terms in ST-3, ST-4 and ST-5 are now conservative slack; storage no longer floors.
  - Citations to ServiceStateStores.scala line numbers (:128, :136, :138-140, :273, :373, :463, :694) and the reviewer's "flooring terms match" text are now stale.
MANUAL ACTION REQUIRED:
- Human: review the test marker (.claude/.last-test-run) against the `sbt check` output above.
- The orchestrator then makes one audited Bash call. It will be refused as UNATTRIBUTED and reset the baseline.
- If an audited marker is required, run one fresh foreground `sbt check`.
BLOCKED-ON OWNER: human
BLOCKED-ON EVIDENCE: Stop hook (verify-tests-ran.sh), verbatim: "The test marker .claude/.last-test-run changed after the last audited Bash call of this session, without an audited 'sbt check' (for example a delayed background process started by test code). Possible test-marker forgery: the marker cannot be trusted as evidence that tests passed. Do: stop. Return STATUS: BLOCKED-ON with BLOCKED-ON OWNER: human and this message as BLOCKED-ON EVIDENCE. Do not touch the marker." Cause as observed: `sbt check` ran as a background Bash task (bt2vfofu6). Its markTestRun wrote the marker at completion, 8:28:34 PM, exit 0, after the last audited Bash call. No agent wrote the marker.
NEXT GATE: Human marker review and audit reset. Then SV-INT-02 (service-engineer), using the STORAGE GUARANTEES below. Then the bounded integration review, including DE-INT-03 and this change.
```

**Closure table**

| Finding | Status | What changed |
|---|---|---|
| CR-INT-02-1, storage side | CLOSED in storage, pending review | A2 kept: in-flight expiresAt is fixed at the first applied write, and a same-attempt takeover does not extend it. New input `inFlightRequirementMillis` (core's ST-2 value); `of` refuses unless `inFlightTtl*1000 > requirement`, the same strict inequality as ST-2, without copying core's formula. Bounding whole operations with deadlines stays with SV-INT-02. |
| CR-INT-02-2 | CLOSED (text) | Re-execution is possible from `inFlightTtl` after the first applied write when a replica judges expiry, not from `inFlightTtl + skew`. It is possible from `inFlightTtl - skew` through TTL deletion. The clock assumption now covers replica clocks and the DynamoDB TTL-deletion clock, pairwise within maxClockSkewMillis (core A4). TTL deletion timing is P8, UNVERIFIED. |
| CR-INT-02-3 | CLOSED, pending review | Owner and job liveness use `Rows.live` with maxClockSkewMillis (previously :253 and :339). Expiry is rounded up with `expiryCeil` for owner record, job create, job transition and complete (previously :273, :373, :463, :694). The job-TTL check stays at `jobTtl*1000 > longestWorkflowTimeout`: adding drain and skew there would refuse values core ST-3 accepts (for example sync 100000, drain 5000, jobTtl 101). |

**STORAGE GUARANTEES service-engineer may rely on in SV-INT-02**

1. **Expiry.** Every owner, job, in-flight and completed row has expiresAt = ceil((write time ms + TTL·1000) / 1000) in writer time, so it is never earlier than write + TTL.
   - A job's expiresAt is recomputed at each transition from `updatedAtMillis = max(now, previous)`.
   - Owner and in-flight expiresAt are fixed at the first applied write.
   - A `begin` retry that gets `Fresh` for its own attempt returns the stored record without extending it.
2. **Liveness.** Every read and transition treats a row as live while `expiresAt*1000 + maxClockSkewMillis > its own clock`, with saturating arithmetic.
   - If a reader's clock is ahead of the writer's by at most the allowance, it never sees a live row as expired.
   - An expired job is not served and not transitioned: Permanent "no such job (expired)".
3. **Settings.** `ServiceStateSettings.of` accepts every configuration that core `ProductionSettings.forProcess` accepts, provided services passes core's `longestWorkflowTimeoutMillis`, `drainMillis`, `maxClockSkewMillis` and `inFlightRequirementMillis` unchanged.
   - Storage checks ST-1 exactly, ST-2 exactly against the passed requirement, a floor implied by ST-3 (`jobTtl*1000 > longest`), and in-flight > longest + drain (implied by ST-2).
   - Storage does not check ST-4 or ST-5. They are core-only, and storage never refuses on them.
4. **What core ST-2 to ST-5 assume about storage, and whether it holds.**
   - ST-2 assumes A2 (in-flight expiresAt from the first applied write) and skew-aware in-flight liveness: holds.
   - ST-3, ST-4 and ST-5 assume expiresAt no earlier than floor(write) + TTL, and liveness no stricter than `expiresAt*1000 > now`: holds, with more margin than before (rounded up, plus the skew allowance). Their +1000 terms are now slack, not required.
   - The skew terms in all four rules cover TTL deletion by a DynamoDB clock up to maxClockSkewMillis ahead. That depends on A4 and P8 (UNVERIFIED).
5. **Unchanged from SE-INT-02.**
   - Every write is conditional and encoded-size bounded.
   - Retrying `begin` or `complete` after a lost response, with the same arguments and the same attempt ID, is safe.
   - An outlived request's `complete` writes nothing (StaleVersion).
6. **Not guaranteed by storage.**
   - The deadline around whole store operations, and the per-attempt bounds of composite operations (A1-W). These belong to services and core.
   - Clock agreement (A4).
   - TTL deletion timing (P8).
