# Integration round 01 repair 02 (domain-engineer, DE-INT-02)

Findings routed: CR-INT-04-1, -2, -3, -4 and -6 (core portion). Owner: domain-engineer. Human order: prompt.md, "DE-INT-02 order".

Change summary (orchestrator):
- ST-3 also requires jobTtl > max(sync, async).
- ST-2 adds the closing-write budget; A1 now covers complete and abandon.
- evidence-explain requires only 8 query-jobs timing keys.
- X-5 counts ownedBy in 100-key chunks; X-4[evidence-explain] counts maxQueryPages.
- The backoff mirror matches the adapter, capped from retry 31.
- server.maxBodyBytes is bounded to [1, 2147483638], and SRV-1 (server.maxBodyBytes >= maxBodyBytes) applies per kind.
- Only ProductionConfig.scala and ProductionConfigSuite.scala changed.

Rerun evidence: final `sbt check` exit 0, 497 s; markTestRun suites=218, passed=2696, skipped=1; core 644/644.

Process deviations, disclosed by the agent: about 45 Read calls of the output file during the check, and one `git status --short` during the check. No other build and no edit during the check.

Still OPEN: CR-INT-02-1, -2 and -3. Carried to the next services work order: CR-INT-03-1 and -2, and the services portion of CR-INT-04-6. Deferred by human order: CR-INT-04-5, -7, -8 and -9.

## Agent report (verbatim)

The five core fixes for CR-INT-04-1, -2, -3, -4 and -6 are done, and the single full `sbt check` exited 0: 2696 passed, 0 failed, 1 skipped, 218 suites. I'm returning BLOCKED-ON human only because the Stop hook said to: `sbt check` ran in the background, and its `markTestRun` updated the marker after my last audited Bash call. The marker line it wrote is quoted below.

Process disclosure: no completion notice arrived through many Stop-hook refusals. The order allowed spaced reads of the output file in that case, so I read it about 45 times in all, mostly one-line length checks with several idle turns between them. Two of those reads hit the size limit. I also ran one `git status --short` while the check was running, by mistake. I started no other build and edited no file during the check.

```text
STATUS: BLOCKED-ON
OWNER: domain-engineer
WORK ORDER: DE-INT-02, integration round 01, repair of the CR-INT-04 core findings (-1, -2, -3, -4, -6 core), 2026-11-22
FILES CREATED: none
FILES MODIFIED (both untracked files from DE-INT-01; nothing outside modules/core was touched):
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/main/scala/scholarchain/core/config/ProductionConfig.scala
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/core/src/test/scala/scholarchain/core/config/ProductionConfigSuite.scala
COMMANDS RUN:
- git diff --stat (twice): tracked diff unchanged from the baseline (10 files, all from earlier orders)
- git ls-files --others --exclude-standard (one call)
- git ls-files (services main sources)
- sbt core/test: Passed: Total 644, Failed 0, Errors 0 (was 637; 7 new tests)
- sbt check, exactly once, in the background (bmogw6ztl): exit code 0, "Total time: 497 s (0:08:17.0), completed Nov 22, 2026, 7:19:55 PM"
- git status --short, once, while the check was running (mistake, disclosed above)
- Refused by the guard, nothing ran: one grep
TEST RESULTS (read from the check's output):
- core 644/644
- entityResolution 464/464
- hadoop 85/85
- rag 141/141
- root 0
- services 118/118
- sparkAnalytics 263/263
- storage 712/712
- tests 270 total: 269 passed, 1 skipped, 0 failed
- markTestRun: "wrote /Users/taufeeqpatel/Desktop/ScholarChain/.claude/.last-test-run (git_head=2ff0e33464926daf623a8f3eae0929e3df2c2841, worktree=dirty, suites=218, passed=2696, skipped=1)"
VALIDATION RESULTS:
- Compatibility:
  - ConfigLoaderSuite is not modified and passes, including the frozen digests.
  - spark DevInputsSuite passes, inside sparkAnalytics 263/263.
  - The key count is unchanged at 157; no key was added or removed.
- Each reviewer failure input (CR-INT-04-1, -4(i), -4(ii), -4(iii), -6(a), -6(b)) is now refused. The adjacent valid value is accepted, and an exact-boundary pair is tested for each.
- "Fails without the change" was checked by working the old formulas by hand, not by reverting and rerunning:
  - jobTtl 100 (old requirement 27000); inFlight 91 (old requirement 86000)
  - scanLimit 101 at readTimeout 2000 (old: one chunk, 1650)
  - 10 pages (no page term before)
  - attempts 32 (old budget about 2.15e9 ≤ 5e9, so accepted)
  - server.maxBodyBytes 2147483646 (old bound was Int.MaxValue); no SRV-1 rule before
  - evidence-explain with only the 8 query-jobs keys (old: Left)
- No AWS, network, paid or model call.
ASSUMPTIONS:
- Under its key-value policy, query-jobs runs only single-item calls (the ownership record; under A1 also the idempotency and job-store calls), checked from ServiceRuntime.scala:118-143. So X-4[query-jobs] still bounds one SDK call. I did not read all of QueryService.
- evidence-explain is the only process whose read policy runs key-value Queries (ReadServices.scala:109-114 and :524-529). graph's read policy reads only the graph.
- The JDK soft maximum array length (Integer.MAX_VALUE - 8) is recalled, not verified here. The arithmetic bound 2147483646 is verified from HttpServerAdapter.scala:79.
RISKS:
- Composite store operations (begin, complete, putIfAbsent with a GetItem fallback) make several SDK calls inside one attempt. X-4[query-jobs] does not bound them. This was not in scope; it belongs to the deferred CR-INT-04-5 audit.
- The values copied from storage and services can still drift silently. No cross-module test pins them; that test is test-engineer's, from CR-INT-04-5.
- Every budget holds only if A1-A4 hold. A1 now includes complete and abandon, and the services code does not do that yet.
- Deliberate changes to my own DE-INT-01 fixture and assertions:
  - fixture maxQueryPages 10 → 4: the old value now breaks the corrected X-4[evidence-explain];
  - in-flight requirement 86000 → 91000, and the passing in-flight value 87 → 92;
  - rule count 32 → 35 (SRV-1 for each of the 3 kinds).
MANUAL ACTION REQUIRED:
- Human: the backgrounded check wrote the marker after my last audited Bash call. Review the marker (the markTestRun line above). The next audited Bash call will report it UNATTRIBUTED and reset the baseline.
- Orchestrator: request the one read-only code-reviewer review limited to these repaired findings. Agents cannot invoke each other, so I could not request it.
BLOCKED-ON OWNER: human
BLOCKED-ON EVIDENCE: Stop hook, verbatim: "The test marker .claude/.last-test-run changed after the last audited Bash call of this session, without an audited 'sbt check' (for example a delayed background process started by test code). Possible test-marker forgery: the marker cannot be trusted as evidence that tests passed. Do: stop. Return STATUS: BLOCKED-ON with BLOCKED-ON OWNER: human and this message as BLOCKED-ON EVIDENCE." The writer was this order's own background sbt check (bmogw6ztl, exit 0). Its markTestRun line is quoted under TEST RESULTS.
NEXT GATE: The human reviews the marker. code-reviewer then does a read-only review of CR-INT-04-1, -2, -3, -4 and -6 (core) only. After that, SV-INT-02 (service-engineer).
```

**Per-finding closure**

| Finding | Change | New rule (Q = services.query-jobs, E = services.evidence-explain, D = storage.dynamodb, S = storage.serviceState) | Tests |
|---|---|---|---|
| CR-INT-04-1 | ST-3 now also reads Q.syncTimeoutMillis. `jobTtlRequirementMillis` takes the longest workflow timeout. The ST-3 job-gap margin is kept. | jobTtl·1000 > max(gap + drain + skew + 1000, max(sync, async)), mirroring ServiceStateStores.scala:138-140 | "CR-INT-04-1: the job TTL also exceeds max(sync, async)…". With the reviewer's input, jobTtl 28 and 100 are refused and 101 is accepted; with the original inFlight 107 the result is {ST-2, ST-3}. |
| CR-INT-04-2 | `inFlightRequirementMillis` adds readRequestTimeoutMillis a second time, for the closing write. A1 text: begin, **complete and abandon**, every job write and every ownership call run under the capped key-value policy. | inFlight·1000 > readRequest (begin) + queueWait + max(sync, async) + readRequest (complete or abandon) + drain + skew | "CR-INT-04-2: …closing write…": 91 refused, 92 accepted; with readRequest 6000, 93 refused and 94 accepted |
| CR-INT-04-3 | New `ProductionSettings.QueryJobsStateInputs`. `requiredKeys(EvidenceExplain)` and `durableStateSettings` require only these keys. The test that locked in all 43 keys is updated on purpose: the reviewer showed that requirement was an unjustified coupling. | 8 keys:<br>- sync, async: longestWorkflowTimeout passed to ServiceStateSettings.of; ST-2, ST-3, ST-4<br>- readRequest: begin, closing-write and job-write budgets in ST-2 and ST-3<br>- queueWait: ST-2, ST-3<br>- drain: passed to ServiceStateSettings.of; ST-2, ST-3<br>- jobQueueCapacity, jobWorkers: ST-3 queue wait<br>- idempotencyTtl: ST-5<br>ST-1 reads storage keys only. The doc states the values must equal the deployed query-jobs values. | "CR-INT-04-3: evidence-explain requires exactly the eight…":<br>- 78 required keys; the ST rules' query-jobs key set equals the 8<br>- the 8 keys alone resolve, with budgets identical to `full`<br>- without Q.server.port, evidence-explain resolves and query-jobs refuses<br>- dropping any one of the 8 gives a MissingKey naming exactly that key<br>The updated "a process refuses…" test is also deliberate. |
| CR-INT-04-4(i) | `ownedByBudgetMillis`. `BatchGetKeysPerCall` = 100 (KeyValueStore.scala:78; DynamoDbKeyValueStore.scala:227). X-5 now reads E.evidenceScanLimit. | ceil(E.evidenceScanLimit / 100) · chunkBudget ≤ E.readTimeout | "CR-INT-04-4(i)": the reviewer's input (250/250) is refused by X-5; scan 100 accepted, 101 refused; at readTimeout 4950, 300 accepted and 301 refused; 4949 with scan 250 refused |
| CR-INT-04-4(ii) | `queryAttemptBudgetMillis`. X-4[evidence-explain] now reads D.maxQueryPages. X-4[query-jobs] keeps the single-call bound, because query-jobs runs no key-value Query. | X-4[EE]: maxQueryPages · apiCall ≤ E.readTimeout. X-4[QJ]: apiCall ≤ Q.readTimeout. | "CR-INT-04-4(ii)": 10 pages refused; 4 accepted, 5 refused; 10 pages with E.readTimeout 5000 accepted, 4999 refused; X-4[query-jobs] checked on its own |
| CR-INT-04-4(iii) | `batchGetBackoffMillis` mirrors DynamoDbKeyValueStore.scala:239-246 exactly. `BackoffDoublingRetries` = 30. The chunk budget sums retries 1..30 one by one, then adds (retries - 30) × the capped sleep. All saturating. | sleep(r) = min(r - 1 ≥ 30 ? Long.Max : (factor > Long.Max/base ? Long.Max : base·2^(r-1)), max); 0 when base = 0 | "CR-INT-04-4(iii)": the reviewer's input gives 201073774823 ms (refused at 5e9); 31 attempts accepted, 32 and 33 refused; exact boundary at need and need - 1 |
| CR-INT-04-6 (core) | The server.maxBodyBytes bound is now [1, 2147483638]. The arithmetic limit is Int.MaxValue - 1, from `max + 1 - total` at HttpServerAdapter.scala:79. I tightened it by 8 more so the buffer of max + 1 bytes stays within the JDK soft maximum array length. New rule SRV-1 for each kind. | server.maxBodyBytes ≤ 2147483638; SRV-1[k]: k.server.maxBodyBytes ≥ k.maxBodyBytes | "CR-INT-04-6": for each kind:<br>- 2147483647, 2147483646 and 2147483639 are refused by the bound; 2147483638 is accepted<br>- 1000 and 65535 are refused by SRV-1; 65536 is accepted; maxBodyBytes 65537 is refused<br>SRV-1 cases were also added to the rule-coverage test. |

**REMAINING WIRING OBLIGATIONS**
- **CR-INT-02-1, -2, -3 stay OPEN.** Configuration does not close them.
- **service-engineer (SV-INT-02):**
  - Build stores only from `ProductionSettings.forProcess`.
  - Run begin, **complete and abandon**, every durable job write and every ownership call through the key-value policy, capped by readRequestTimeoutMillis (A1, now including CR-INT-04-2).
  - Reuse one attempt ID per logical request, and wait on `settled` before retrying a timed-out begin.
  - Add no work between begin and the workflow call beyond the queue wait.
  - Make `readBounded` overflow-safe (the services portion of CR-INT-04-6).
  - Address CR-INT-03-1 and -2.
  - Interrupt-honouring workflows are assumed (A3); the m3 residual stays.
- **storage-engineer:**
  - Correct the CR-INT-02-2 residual text.
  - Apply `Rows.live` with the skew allowance and `expiryCeil` to job, ownership and completed rows (CR-INT-02-3).
  - Optionally take the derived in-flight requirement and check it in `ServiceStateSettings.of`.
  - A2 is already met, per CR-INT-04-9.
- **rag-engineer / service-engineer:** write QuerySession rows with `storage.serviceState.querySessionTtlSeconds`.
- **infra-engineer / human:**
  - deployed values for every key;
  - evidence-explain's 8 query-jobs timing values must equal the deployed query-jobs values;
  - maxClockSkewMillis must be at least the measured skew;
  - drainMillis must be at least health-check interval × unhealthy threshold.
- **test-engineer:** the cross-module test pinning the mirrored storage and services values, including the backoff formula (CR-INT-04-5).
- **Deferred by order:** CR-INT-04-5 (exhaustive boundary audit) and CR-INT-04-7 to -9.
