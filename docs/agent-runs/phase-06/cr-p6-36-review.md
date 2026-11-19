# CR-P6-36: review of SP-P6-07 (code-reviewer final report, verbatim)

```
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: CR-P6-36 (Phase 6). Read-only review of spark-engineer SP-P6-07: uncommitted changes against HEAD 5242754 to modules/spark/src/main/scala/scholarchain/spark/graph/TemporalJob.scala (modified) and modules/spark/src/test/scala/scholarchain/spark/analysis/TemporalDeterminismSuite.scala (new).
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none. I used only Read, Grep and Glob. I read both working-tree files in full, plus sp-p6-07-report.md, be-p6-10-attempt-3-report.md, AnalysisRun.scala:1015-1069 (temporalChecks and countBy), AnalysisStructure.scala:494-501 (TemporalPresence and fromTag), CanonicalGraphJob.scala:370-612, GraphStructureJob.scala:425-570, CoauthorEdgeJob.scala:170-250, ResearcherMetricsJob.scala:300-338, SummaryJob.scala:150-391, LoadManifests.scala:70-104 and .claude/.last-test-run. I also grepped every shuffle, distinct, countBy*, collectAsMap and groupBy in modules/spark/src/main, plus the output package, the core enum list and spark.serializer/Kryo settings.
TEST RESULTS: I ran no tests. The reported sbt check numbers check out. The per-project totals add up: 621+464+85+259+145 = 1574. The marker /Users/taufeeqpatel/Desktop/ScholarChain/.claude/.last-test-run shows written_at_utc=2026-11-19T04:09:47Z (10:09:47 PM local, matching the report), git_head=5242754…, worktree=dirty, suites=113, tests_passed=1574, failed 0, errored 0, skipped 0. The previous baseline was suites=112, passed=1570 and sparkAnalytics 255 (be-p6-09-repair-01/02/03, cr-p6-35-review). So 259 = 255 + 4 new tests and 113 = 112 + 1 new suite, as claimed.
VALIDATION RESULTS:
1. Root cause: confirmed by reasoning, with the doubts below.
   - TemporalPresence (core AnalysisStructure.scala:494-497) is a Scala 3 enum. Its cases are singletons with a parent constructor argument. It is not a case class, and neither it nor scala.reflect.Enum overrides hashCode or equals, so the cases hash and compare by identity. Test 1 asserts the identity hash on the runtime and passed.
   - The tuple hash (MurmurHash product hash) is built from the element hashCodes. So a (pair, enum) key is routed by each map JVM's identity hash, and two executor JVMs can send one key to two reducers.
   - countByValue is countByKey: reduceByKey, then collect().toMap. On equal keys, toMap keeps only the last entry, so the total can only fall. 55 out of 112 fits this.
   - readResolve: on the driver, deserialization must resolve each case to its singleton for toMap to merge the keys and for comparisons.getOrElse((p, InBoth)) to find them. Without readResolve, every lookup would miss and the count would be 0, not 55. Independent support: shuffle and collect data are Java-serialized even in local[N], and the existing pattern matches on these enums after deserialization (for example StructureValidation and checkTemporal) pass throughout sbt check.
   - Doubt: I did not inspect the bytecode for readResolve, and the 2-reducer default (spark.default.parallelism unset; job partitions=2) is inferred, not observed. The mechanism fully explains the attempt-2 pass and attempt-3 failure with the same jar. Without stage logs it is still not directly proven.
2. Fix correctness: verified.
   - countsOf (TemporalJob.scala:85-89) uses reduceByKey onto HashPartitioner(P), then sums per key on the driver with foldLeft/updated. It is exact even if hashing were inconsistent.
   - Presence is shuffled as (Int, tag String) (line 259). Edge classes are (Int, Int) (line 263).
   - presenceCounts (102-105) maps tags back to the enum. The tag-to-case mapping is 1:1, so toMap cannot collide.
   - fromTag returning None: the entry is silently dropped. The comparisons total then falls below changeCount, and temporalChecks refuses. The failure is not hidden, but the message points away from the cause (nit N1).
   - temporalChecks (AnalysisRun.scala:1022-1043) is unchanged and not weakened.
   - comparisons() (291-325) reads the maps only by getOrElse, so ordering is irrelevant.
3. Determinism audit: no other instance found.
   - Every remaining shuffle key in modules/spark/src/main is an Int, a String, Vertex = (Int, String) (GraphStructureJob.scala:198), or a tuple of these. This covers CanonicalGraphJob 390/440-552/581-601, GraphStructureJob 312-560, CoauthorEdgeJob 143-241, ResearcherMetricsJob 69-91/237-335, StructureRecordsJob 64-248, SummaryJob 164-390 (document-type keys use .tag at 173/211/364/386), PathJob, and AnalysisRun.countBy (1054-1055, String tags at 1100-1101).
   - The output package was not audited by the agent. RunPublisher shuffles only RDD[(Long, String)] and saves it (lines 42, 481-483, 790-792). LoadManifests and the *Rows converters are driver-side or row mapping and have no keyed shuffle. No issue found.
   - Enum values appear only as shuffled values or closure-captured constants: CanonicalGraphJob.scala:576-584 (NodeLabel sets), :590 and :596, and SummaryJob.scala:301, 305, 314, 350 and 370. These rely on readResolve under JavaSerializer, as the agent stated.
   - Nothing in the repository (outside target/) sets spark.serializer or Kryo, including infra/terraform/environments/dev/emr-run/main.tf.
4. Tests: they are meaningful but do not protect against a regression; see finding m1.
   - The TaskHashedKey simulation is valid:
     - A map task's partitionId p routes the key to reducer p mod P.
     - Map-side combine and the reducer's hash map are each consistent within their own task.
     - Equality is case-class value equality.
     - The driver hashes with no TaskContext, so toMap and foldLeft merge on the driver.
   - The suite shows the old merge yields 5 and countsOf yields 10. At P=1 both map tasks go to reducer 0, which is a trivial case, but harmless.
   - Test 4 (hand counts 3/2/1 and edges 2/0/4 at 2 and 4 partitions, temporal check passing) exercises the real changes() path end to end in one JVM.
5. Outputs unchanged for passing runs: verified by reasoning.
   - Each of the three presence counts could only fall under the bug, and the check compares their sum with changeCount. So a run that passed had all three counts exact.
   - Edge-class keys were (Int, Int) before and after, so edge counts are unaffected.
   - The change records (`out`) are untouched.
   - sparkIT runs in a single JVM, so the run 08 count baselines (edges 259, metrics 176, 28 datasets, load rows and roles) are unaffected. The run ID changes only through codeVersion, as for any commit.
   - The agent correctly says it did not observe a sparkIT re-run.
6. Report honesty: consistent. The counts and the marker match. The statements "the old production path is not caught" and "two-JVM check pending (attempt-4)" are candid and accurate. STATUS BLOCKED-ON human is only for the known R1-b marker remedy, and the marker contents match the claimed sbt check.
ASSUMPTIONS:
- Scala 3 generates readResolve for singleton enum cases. I did not inspect the bytecode. I infer it from 55 being nonzero and from sbt suites passing that pattern-match on deserialized enums.
- The attempt-3 local-cluster run used Spark's default JavaSerializer.
RISKS:
- The two-JVM confirmation is still pending. Attempt-4 sbt sparkSubmitLocal (build-engineer, human approval) at the committed fix is the real check.
- If Kryo is enabled later, shuffled enum values (CanonicalGraphJob.scala:584, SummaryJob.scala:301/305/350/370, ResearcherTemporalChange.presence) could deserialize as new instances, and the identity-based == and pattern matches would then fail. Nothing enables Kryo today.
- No sbt-check test fails if someone reintroduces an enum shuffle key elsewhere.
MANUAL ACTION REQUIRED:
- Human: do the R1-b marker remedy as described in the agent report, then commit the two files and the report.
- Approve attempt-4 `sbt sparkSubmitLocal` (build-engineer) at the committed fix. Archive target/spark-submit-local/attempt-3 first if it is wanted as evidence.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human commit, then human-approved `sbt sparkSubmitLocal` attempt-4 (build-engineer). Both runs must report consistent temporal counts (changes=112 and comparisons summing to 112). An optional sparkIT re-run would confirm the baselines.
VERDICT: APPROVE
FINDINGS:
spark-engineer:
- m1 (minor, non-blocking): no sbt-check test catches a regression to an enum shuffle key.
  - Where: modules/spark/src/test/scala/scholarchain/spark/analysis/TemporalDeterminismSuite.scala:93-120 and 147-167; production line TemporalJob.scala:257-263.
  - Failure input: change line 259 back to `(p, presenceOf(...))` with `.countByValue().toMap`, or add a new `countByValue` over an enum-keyed tuple in any job.
  - Expected impact: all 259 sparkAnalytics tests still pass, because one JVM hashes consistently. The defect would reappear only under multi-JVM spark-submit or EMR, as a refusal or, for counts that no check cross-validates, as silently wrong published counts. Today the suite fails without the fix only because the new symbols are missing, as the agent admits.
  - Why non-blocking: the fix is verified correct by reasoning, test 3 validly proves countsOf is exact under split routing, and attempt-4 is the real two-JVM gate.
  - Feasible strengthening for a later work order, any of:
    - (a) Make the presence-key builder an injectable `K` (or a function argument of changes) so a test can feed TaskHashedKey-wrapped keys through the real changes() and countsOf path.
    - (b) Add a small helper that refuses at runtime any key whose hashCode equals System.identityHashCode for a reflect.Enum element, and call it from countsOf.
    - (c) Add a source-scan test that bans `countByValue` and enum-typed keys in modules/spark/src/main.
- N1 (nit): presenceCounts drops an unknown tag silently.
  - Where: TemporalJob.scala:102-105.
  - Failure input: a tag that fromTag cannot resolve. This is impossible today, because the tags come from the enum's own `.tag`.
  - Impact: fails closed, but through temporalChecks with the misleading message "temporal changes: N records, the comparisons count M researchers" rather than the cause.
  - Suggestion: return Left or throw InvariantFailure naming the tag.
- N2 (nit): test 1 pins a compiler implementation detail.
  - Where: TemporalDeterminismSuite.scala:67-70, which asserts hashCode == identityHashCode.
  - Impact: a future Scala that gives enum cases a value hash would fail this test even though the fix stays correct. The failure is harmless and would at least prompt a revisit.
UNVERIFIED CONCERNS:
- That Scala 3 emits readResolve for these enum cases. Strongly supported indirectly, not inspected.
- That the attempt-3 refusal came from exactly this mechanism and not some other cross-JVM effect. No stage, partition or shuffle metrics were collected. Attempt-4 consistency across both runs (and ideally a repeated local-cluster run) is the confirmation.
- EMR may set spark.default.parallelism or other spark-defaults at runtime that the repository does not show. This does not affect the fix's correctness, because countsOf is exact for any P.
```
