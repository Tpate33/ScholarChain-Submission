# Integration round 01 repair 03 (domain-engineer, DE-INT-03)

**Finding routed:** CR-INT-05-1 (minor). **Owner:** domain-engineer. **Human order:** prompt.md, "DE-INT-03 order".

**Change summary (orchestrator).** X-4[query-jobs] is now `3 · storage.dynamodb.apiCallTimeoutMillis ≤ services.query-jobs.readTimeoutMillis` (saturating). The 3 is the worst-case number of SDK calls in one `QueryOwnership.record` attempt:
1. the conditional PutItem;
2. the GetItem fallback when ALL_OLD is absent;
3. the conflict-recovery GetItem.

The A1-W wiring obligation is documented. The composite operations begin, complete, abandon and the job writes are NOT yet bounded per attempt (open obligation). Only ProductionConfig.scala and ProductionConfigSuite.scala changed.

**Rerun evidence:** final `sbt check` exit 0, 490 s; markTestRun suites=218, passed=2697, skipped=1; core 645/645.

**Process disclosure (agent):** 33 Read calls of the output file during the check; no edits, no git calls and no second build.

**Not yet reviewed.** By human order, this correction must be included in the next required integration review before anything relies on it.

## Agent report (verbatim)

CR-INT-05-1 is fixed in core. X-4[query-jobs] now bounds one complete `QueryOwnership.record` attempt as 3 × apiCallTimeoutMillis ≤ Q.readTimeoutMillis; the old rule counted 1 SDK call. The single full `sbt check` exited 0 in 490 s, with suites=218, passed=2697, skipped=1.

I am returning BLOCKED-ON human only because the Stop hook requires it. The check was moved to the background after 180 s, and its `markTestRun` then updated the marker after my last audited Bash call. The marker line it wrote is quoted under TEST RESULTS.

Process disclosure: no completion notice arrived before the marker write, so I read the output file 33 times while the check ran. Most were 2-line length checks, with several idle turns between reads. I made no edits, no git calls and started no second build.

EXACT CORRECTED BOUND
- **Formula (X-4[query-jobs]):** `TimingBudgets.ownershipRecordAttemptBudgetMillis = SatMath.mul(3, storage.dynamodb.apiCallTimeoutMillis) <= services.query-jobs.readTimeoutMillis`. The multiplication saturates, so it cannot overflow. No other term is added, because the record path has no sleep or local retry.
- **Call count: 3 SDK calls per attempt in the worst case.** Every line below is in modules/storage/src/main/scala/scholarchain/storage/; DynamoDbKeyValueStore.scala and DynamoDbSettings.scala are under aws/dynamodb/.
  1. The conditional PutItem of `putIfAbsent` (ServiceStateStores.scala:276; DynamoDbKeyValueStore.scala:331-341).
  2. When ConditionalCheckFailed returns no ALL_OLD item, the consistent GetItem fallback (DynamoDbKeyValueStore.scala:104-105 and :344-348, via `get` at :109-129, which is one call). A different stored record then gives Conflict (:352-353).
  3. On that Conflict, the conflict-recovery GetItem in `stored` (ServiceStateStores.scala:278-279 and :255-263, via `get`).
  - Nothing else in the path calls the SDK. `attempt` (DynamoDbKeyValueStore.scala:94-99) does not retry, and `ServiceStateKeyValues` adds no wrapper.
  - Each SDK call, including the SDK's own retries, is bounded by apiCallTimeout (DynamoDbSettings.scala:21-22, :81-82, :94).
  - If DynamoDB returns ALL_OLD, the count is 2. Whether it always does is unverified (P1).
- **Boundary values tested:**
  - Reviewer input apiCall=2000, Q.readTimeout=2000: refused, as X-4[query-jobs] only. DE-INT-02 accepted it.
  - At apiCall=2000: Q.readTimeout 6000 is accepted and 5999 is refused.
  - At the fixture's Q.readTimeout=2000: apiCall 666 (1998) is accepted and 667 (2001) is refused. The old rule accepted 667.
  - Overflow: apiCall=Long.MaxValue/2 against Long.MaxValue-1 is refused, because the product saturates instead of wrapping negative. apiCall=(Long.MaxValue-1)/3 is accepted.
  - The cases above set E.maxQueryPages=1 and E.readTimeout=E.readRequestTimeout=7000. That keeps X-4[evidence-explain] and X-5 from firing, so X-4[query-jobs] alone decides each result.
- **Relation to the readRequestTimeout cap (A1), from the code:**
  - `ReadPolicy.read(deadline(), readRequestTimeoutMillis)` (Resilience.scala:312-328; QueryService.scala:567-569) gives each attempt `min(readTimeoutMillis, time left)` (:345-355).
  - It starts at most readMaxAttempts attempts, each only while time is left (:353), and retries immediately.
  - So the whole operation returns to its caller within readRequestTimeoutMillis: the cap (:315-317) plus the deadline (ServiceRuntime.scala:100-101).
  - The policy enforces no relation readMaxAttempts·readTimeout ≤ readRequestTimeout; it truncates the last attempt instead. SVC-4 requires only readRequestTimeout ≥ readTimeout. I added no such relation.
  - Residual: an attempt cut short by the deadline can leave its SDK work running after the caller returns, by at most that attempt's worst case. For record that is ≤ 3·apiCall ≤ readTimeout.

UPDATED WIRING OBLIGATION (A1-W, now in the TimingBudgets doc in ProductionConfig.scala)
- service-engineer must run each complete store operation as one `op` of the key-value `ReadPolicy.read(deadline(), readRequestTimeoutMillis)`, exactly as the ownership record does today. The operations are: ownership `record`; idempotency `begin`, `complete` and `abandon`; job `create`, `start`, `succeed`, `fail` and `remove`.
- Core validates the per-attempt bound for the ownership record only.
- The other operations are composite. Their per-attempt bound is not validated and stays an open obligation. Worst-case SDK calls per attempt, with R = maxConflictRetries:
  - `begin`: up to 4·(R+1), at ServiceStateStores.scala:619-646 (PutItem, GetItem fallback, GetItem and a conditional PutItem per round).
  - job `start`, `succeed`, `fail`: up to 2·(R+1), at :444-478. `putIfVersion` has no fallback (DynamoDbKeyValueStore.scala:389-399).
  - `complete`: 3, at :697, :700 and :704.
  - job `create`: 3, at :375-388.
  - `abandon` and job `remove`: 2 (DeleteItem plus its GetItem fallback, DynamoDbKeyValueStore.scala:413-433).
- With the fixture's R=10, `begin` alone is up to 44 calls (22000 ms at apiCall 500), against Q.readTimeout 2000. A rule for these needs a later work order; I did not broaden the rule set.

```text
STATUS: BLOCKED-ON
OWNER: domain-engineer
WORK ORDER: DE-INT-03, integration round 01, fix CR-INT-05-1 (X-4[query-jobs] bounds one complete ownership.record attempt), 2026-11-22
FILES CREATED: none
FILES MODIFIED:
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/main/scala/scholarchain/core/config/ProductionConfig.scala
  - new ProductionBounds.OwnershipRecordSdkCalls = 3, with the call-path citations
  - new TimingBudgets.ownershipRecordAttemptBudgetMillis
  - X-4[query-jobs] check, formula and source text changed to 3 * apiCall <= Q.readTimeout
  - A1-W wiring-obligation text added to the TimingBudgets doc
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/test/scala/scholarchain/core/config/ProductionConfigSuite.scala
  - new test "CR-INT-05-1: X-4[query-jobs] bounds one ownership-record attempt of 3 SDK calls"
  - one deliberate fixture change, in test "CR-INT-04-4(ii)": its passing case used Q.readTimeout=2001 with apiCall 2001. It now uses Q.readTimeout=6003 and Q.readRequestTimeout=6003 (raised so SVC-4 still holds), because the corrected rule refuses 2001. The refused case (apiCall 2001 against 2000) is unchanged. No other fixture or assertion changed.
COMMANDS RUN:
- git ls-files --others --exclude-standard -- modules/storage/src/main (to locate the storage files)
- sbt core/test: Passed: Total 645, Failed 0, Errors 0 (was 644; 1 new test)
- sbt check, exactly once (it moved itself to the background after 180 s, task b7tky5zri): exit 0, "Total time: 490 s (0:08:10.0), completed Nov 22, 2026, 7:58:36 PM"
- Refused by the guard, nothing ran: one grep (quote character)
- 33 Read calls of the check output file during the run (disclosed above)
TEST RESULTS (from the check output):
- core 645/645
- entityResolution 464/464
- hadoop 85/85
- rag 141/141
- root 0
- services 118/118
- sparkAnalytics 263/263
- storage 712/712
- tests 270 total: 269 passed, 1 skipped
- markTestRun: "wrote /Users/taufeeqpatel/Desktop/ScholarChain/.claude/.last-test-run (git_head=2ff0e33464926daf623a8f3eae0929e3df2c2841, worktree=dirty, suites=218, passed=2697, skipped=1)"
VALIDATION RESULTS:
- Compatibility:
  - Key count unchanged at 157; the frozen-count assertion passes. No key was added or removed.
  - ConfigLoaderSuite is not modified and passes, including its digests.
  - The rule count is unchanged at 35; the existing coverage case for X-4[query-jobs] (Q.readTimeout 400) still refuses.
  - spark DevInputsSuite passes.
- "Fails on DE-INT-02 code" was checked by working the old rule (apiCall <= Q.readTimeout) by hand, not by reverting: it accepts 2000/2000, 2000/5999 and 667/2000, and it accepts (Long.MaxValue/2, Long.MaxValue-1). The new test asserts refusal of all four, so it fails on the old code.
- No AWS, network, paid or model call.
ASSUMPTIONS:
- In query-jobs, the ownership record is the only store operation under the key-value policy today; the rest are A1-W. Checked at QueryService.scala:561-574 and ServiceRuntime.scala:118-143.
- The SDK's apiCallTimeout bounds one whole call including internal retries, per DynamoDbSettings.scala:21-22. It is unverified against real SDK behaviour, as the storage doc says at :81-82.
- Encoding, decoding and the in-process code between SDK calls are taken as negligible; core adds no term for them.
RISKS:
- Per-attempt bounds for begin, complete, abandon and the job writes are not validated (A1-W). At the fixture values, begin's worst case far exceeds Q.readTimeout. Until a rule or a wiring change exists, N4 can still hold for those operations under A1.
- An attempt cut short by the deadline can leave SDK work running up to its worst case after the caller returns (documented residual).
- The SDK-call counts mirror storage code by hand, and no cross-module test pins them (the CR-INT-04-5 test-engineer item).
- ALL_OLD behaviour (P1) decides whether the worst case is 2 or 3 calls. Core assumes 3, which is the safe side.
MANUAL ACTION REQUIRED:
- Human: review the marker; the markTestRun line is quoted above. The next audited Bash call will report it UNATTRIBUTED and reset the baseline.
- CR-INT-02-1, -2 and -3 remain OPEN. CR-INT-03-1, CR-INT-03-2 and the services portion of CR-INT-04-6 belong to SV-INT-02. CR-INT-05-2, CR-INT-04-5 and CR-INT-04-7 to -9 are left alone, as ordered.
BLOCKED-ON OWNER: human
BLOCKED-ON EVIDENCE: Stop hook, verbatim: "The test marker .claude/.last-test-run changed after the last audited Bash call of this session, without an audited 'sbt check' (for example a delayed background process started by test code). Possible test-marker forgery: the marker cannot be trusted as evidence that tests passed. Do: stop. Return STATUS: BLOCKED-ON with BLOCKED-ON OWNER: human and this message as BLOCKED-ON EVIDENCE. Do not touch the marker. The human reviews it; the next audited Bash call reports the change as UNATTRIBUTED and resets the baseline, and a fresh 'sbt check' re-records the marker." The writer was this order's own sbt check (b7tky5zri, exit 0), which moved itself to the background.
NEXT GATE: The human reviews the marker. This correction must be included in the next required integration review before anything relies on X-4[query-jobs], including the A1-W obligation for the composite operations. Then SV-INT-02 (service-engineer) implements A1-W.
```
