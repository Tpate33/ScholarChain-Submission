# SP-P6-07: temporal count nondeterminism under multi-JVM spark-submit (spark-engineer final report)

Finding routed: BE-P6-10 attempt-3 run 2 refusal `temporal changes: 112 records, the comparisons count 55 researchers` (be-p6-10-attempt-3-report.md). Owner: spark-engineer.

The report below is verbatim. Its COMPLETE DIFF field was already abridged by the agent (two Scaladoc hunks); the full diff is recorded by the commit that contains this file.

```
STATUS: BLOCKED-ON
OWNER: spark-engineer
WORK ORDER: SP-P6-07 (Phase 6). Diagnose and repair the nondeterminism in CollaborationJob's temporal analysis under multi-JVM spark-submit (BE-P6-10 attempt-3 run 2, local-cluster[2,1,1024], refused with `temporal changes: 112 records, the comparisons count 55 researchers`).

The fix is done and `sbt check` passed: 1574 tests, 0 failed. I am reporting BLOCKED-ON only because the Stop hook refused completion after the backgrounded `sbt check` updated the test marker. That is the known remedy case. Quoted refusal:
"BLOCKED by .claude/hooks/verify-tests-ran.sh: completion refused for 'spark-engineer'. The test marker .claude/.last-test-run changed after the last audited Bash call of this session, without an audited 'sbt check' (for example a delayed background process started by test code). Possible test-marker forgery: the marker cannot be trusted as evidence that tests passed. Do: stop. Return STATUS: BLOCKED-ON with BLOCKED-ON OWNER: human and this message as BLOCKED-ON EVIDENCE."
The marker was written by that `sbt check` itself (`markTestRun: wrote .../.claude/.last-test-run (git_head=5242754..., worktree=dirty, suites=113, passed=1574, skipped=0)`). Its own run was backgrounded by the harness after 180 s, so the hook saw the write as happening outside an audited call.

ROOT CAUSE (high confidence):
- The check that refused is `AnalysisRun.temporalChecks`, /Users/taufeeqpatel/Desktop/ScholarChain/modules/spark/src/main/scala/scholarchain/spark/analysis/AnalysisRun.scala:1035-1037. It compares `changes.count` with the sum of `researchersInBoth + researchersOnlyFrom + researchersOnlyTo` over the comparisons.
- `changes` (the 112 records) is correct. It is keyed by (pair: Int, researcher: String), persisted, and counted.
- The comparisons' researcher counts came from `TemporalJob.changes` (old lines 212-222): `grouped.map(... => (p, TemporalPresence)).countByValue().toMap`. `TemporalPresence` (core AnalysisStructure.scala:494) is a Scala 3 enum. Its cases do not override `hashCode`, so they hash by object identity, and identity hashes differ between JVMs. My new test checks this on our runtime: `x.hashCode == System.identityHashCode(x)` holds for all three cases.
- `countByValue` is `countByKey` = `reduceByKey(_+_)` over the default partitioner, then `collect().toMap`. The default partitioner here has 2 partitions, because `spark.default.parallelism` is unset under spark-submit.
- With two executor JVMs, a map task on executor 1 and one on executor 2 can route the same key, e.g. (0, InBoth), to different reducers. Each reducer returns a partial count. At the driver, Java deserialization resolves both keys to the same singleton, and `toMap` keeps only one of the equal-key entries. The total therefore drops (55 of 112). It can only drop, never rise.
- In one JVM (local[N], every sbt test) identity hashes are consistent, so this cannot happen there.
- In local-cluster it depends on whether each key's identity hash happens to be equal mod 2 in both executors. That is why attempt-2 run 2 passed and attempt-3 run 2 failed with the same jar.
- The edge-class counts (keys (Int, Int)) and every other shuffle key were unaffected.

FIX (modules/spark only; the consistency check is unchanged):
- New `TemporalJob.countsOf[K](keys, partitions): Map[K, Long]`. It does `reduceByKey(HashPartitioner(P))`, `collect`, then sums per key on the driver instead of keeping one entry.
- Presence is shuffled as `(pair, presence.tag)`, Int and String only, through the new `presenceOf`. It is mapped back to the enum on the driver by `presenceCounts` via `TemporalPresence.fromTag`.
- Edge classes also go through `countsOf`.
- The Scaladoc of `TemporalJob` and `TemporalCounts` now states the rule (no enum in a shuffle key) and the cause.

FILES CREATED:
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/spark/src/test/scala/scholarchain/spark/analysis/TemporalDeterminismSuite.scala
FILES MODIFIED:
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/spark/src/main/scala/scholarchain/spark/graph/TemporalJob.scala

COMMANDS RUN:
1. `git status --short`: only `?? docs/agent-runs/phase-06/be-p6-10-attempt-3-report.md`.
2. `git grep ...`: refused by the guard (character gate). Nothing ran.
3. `git ls-files -- modules/spark`, `git ls-files -- modules/core/src/main`: listings.
4. `sbt sparkAnalytics/testOnly scholarchain.spark.analysis.TemporalDeterminismSuite`: refused (no allowlist entry). Nothing ran.
5. `sbt sparkAnalytics/Test/compile`: `[success] Total time: 4 s`.
6. `sbt sparkAnalytics/test`: backgrounded by the harness, exit 0, `[info] Passed: Total 259, Failed 0, Errors 0, Passed 259`. This includes the 4 new tests, all passing.
7. `git diff -- modules/spark`: the diff below.
8. `sbt check`, once: backgrounded by the harness, exit 0, `[success] Total time: 323 s (0:05:23.0), completed Nov 18, 2026, 10:09:47 PM`.

I did not run `sbt sparkIT`, `sbt sparkSubmitLocal` or `sbt clean`, and made no AWS call.

TEST RESULTS (sbt check, verbatim):
- core `Passed: Total 621, Failed 0, Errors 0, Passed 621`
- entityResolution `Passed: Total 464, Failed 0, Errors 0, Passed 464`
- hadoop `Passed: Total 85, Failed 0, Errors 0, Passed 85`
- sparkAnalytics `Passed: Total 259, Failed 0, Errors 0, Passed 259`
- tests `Passed: Total 145, Failed 0, Errors 0, Passed 145`
- rag, root, services and storage: 0 tests
- markTestRun: `suites=113, passed=1574, skipped=0`
- The scalafmt check passed. The build guard tasks passed: verifySparkItGuards (204 cases), verifySparkItRunner (8 cases) and verifySparkSubmitLocalGuards (130 cases).

New suite, all 4 tests passing:
- "a Scala 3 enum case hashes by identity, so it is never part of a shuffle key"
- "presenceOf and presenceCounts: the tag counts map back to the three classes"
- "countsOf is exact for a key routed to several reducers; the old toMap merge is not". A `TaskHashedKey`, whose hash is the task's partition ID, reproduces the cross-JVM routing in one JVM. The test asserts that the old `collect().toMap` merge gives 5 of 10, and that `countsOf` gives 10 at partitions 1, 2, 3 and 5, evaluated twice each.
- "runs at 2 and 4 partitions: hand counts, 6 changes, the temporal check passes". It checks the hand counts in-both 3, only-from 2, only-to 1, edges 2/0/4, and equal comparisons and run ID across both runs.

What local tests can and cannot cover:
- One JVM can never show a different identity hash for the same enum object, so no in-sbt end-to-end run fails on the old code.
- Test 3 reproduces the mechanism (one key reduced on several reducers). It shows the old merge loses counts and the new one does not.
- Test 1 guards the premise. A true two-JVM check is still `sbt sparkSubmitLocal` (build-engineer, human approval).
- Strictly, the suite fails without the fix only because `countsOf`, `presenceOf` and `presenceCounts` do not exist; no assertion catches the old merge path in production code.

VALIDATION RESULTS:
- Other stages audited for the same pattern (enum, or a case class holding one, in a shuffle key, distinct, countByValue or collectAsMap): CoauthorEdgeJob, IdentityJob, ResearcherMetricsJob, GraphStructureJob, StructureRecordsJob, PathJob, SummaryJob and CanonicalGraphJob. None found. Their keys are Int, String or tuples of them.
- SummaryJob and AnalysisRun.countBy already key by `.tag`.
- CanonicalGraphJob.invariants carries NodeLabel only as a join value and compares it in the executor against a set built in the same JVM, so it is not affected.
- `NodeSources` and `EdgeSources` are `Map[enum, _]` object fields initialised per JVM, also not affected.
- Not audited: the output package (RunPublisher, LoadManifests, row converters). Per AnalysisRun's documentation they are driver-side or I/O, but I did not read them in this run.
- Remaining exposure: if Kryo were ever enabled (`spark.serializer`), enum values inside shuffled records (for example `ResearcherTemporalChange.presence`, `GraphNodeV2.label`) could deserialize as new instances, which breaks pattern matches. The job does not set Kryo, and as far as I read SparkSubmitLocal does not either.

Do published outputs change?
- No, for any run that passed the check. Presence counts can only be lost under the bug, so a passing run already had exact counts. The window comparison and temporal change records are the same values.
- Run identity (the manifest) does not depend on these counts. It does depend on codeVersion, so a run at a new commit gets a new run ID, as for any code change.
- The sparkIT run 08 baselines (edges 259, metrics 176, 28 datasets, load rows and roles) should stay the same. I did not observe this, because sparkIT was not run.
- The job jar bytes change, so `sbt sparkSubmitLocal` (attempt-4) must be re-run at the committed fix. A sparkIT re-run is optional; it would confirm the baselines.

ASSUMPTIONS:
- Spark uses its default JavaSerializer, and Scala 3 enum cases deserialize to their singletons. That is consistent with the observed 55 rather than 0.
- `spark.default.parallelism` is unset under the gate, so countByValue used 2 reducers.
- Stage DAGs, partition metrics and shuffle metrics were not collected. No job was run outside sbt tests.

RISKS:
- The full two-JVM confirmation is still pending (attempt-4).
- The Kryo exposure described under VALIDATION RESULTS.
- The output package was not audited this run.

MANUAL ACTION REQUIRED:
- Human: review the marker and `git status` per the known remedy (interrupt, then one Bash call resets the baseline). The marker was written by this session's `sbt check`, which passed with exit 0, as shown above.
- Then review and commit the two files, route to code-reviewer, and approve one fresh `sbt sparkSubmitLocal` (attempt-4, build-engineer) at the committed fix.
- Archive target/spark-submit-local/attempt-3 before any `sbt clean` if it is wanted as evidence.

BLOCKED-ON OWNER: human
BLOCKED-ON EVIDENCE: the Stop hook refusal quoted above (verify-tests-ran.sh: "The test marker .claude/.last-test-run changed after the last audited Bash call of this session, without an audited 'sbt check' ... Possible test-marker forgery"). The marker write is in this session's sbt check output: `markTestRun: wrote /Users/taufeeqpatel/Desktop/ScholarChain/.claude/.last-test-run (git_head=524275482b63c6f05f0682964177ec2abc25ba82, worktree=dirty, suites=113, passed=1574, skipped=0)`, then `[success] Total time: 323 s`, exit code 0. The full log is /private/tmp/claude-501/-Users-taufeeqpatel-Desktop-ScholarChain/7e2adb2c-04c4-4499-bc7c-e377443f1cd5/tasks/b5awe28i1.output, lines 228009-228034.

NEXT GATE: human marker review and commit, then code-reviewer, then the human-approved `sbt sparkSubmitLocal` attempt-4 (build-engineer); sparkIT is optional.

COMPLETE DIFF (TemporalJob.scala; the new suite is the created file above):
@@ TemporalCounts doc
- * three edge classes per consecutive window pair).
+ * three edge classes per consecutive window pair). Built on the driver from distributed counts
+ * keyed by JVM-stable values only ([[TemporalJob.countsOf]]); the enum keys of `presence` exist
+ * only on the driver and never enter a shuffle.
@@ object doc
- * `leftOuterJoin`s and one `sortBy` (pair, researcher). Driver: the per-pair counts
+ * `leftOuterJoin`s and one `sortBy` (pair, researcher); the per-pair counts `reduceByKey` by (pair,
+ * presence tag) and (pair, edge class) ([[countsOf]]). Driver: the per-pair counts
   ...
+ * Shuffle keys hold only Int and String values (SP-P6-07). A Scala 3 enum case such as
+ * [[TemporalPresence]] does not override `hashCode`, so it hashes by object identity, which differs
+ * between JVMs: ... (full rationale, 55 vs 112, BE-P6-10 attempt-3) ...
@@ new members
+  def countsOf[K: ClassTag](keys: RDD[K], partitions: Int): Map[K, Long] =
+    pr(keys.map(k => (k, 1L)))
+      .reduceByKey(new HashPartitioner(partitions), _ + _)
+      .collect()
+      .foldLeft(Map.empty[K, Long]) { case (m, (k, n)) => m.updated(k, m.getOrElse(k, 0L) + n) }
+
+  def presenceOf(f: Boolean, t: Boolean): TemporalPresence =
+    if (f && t) TemporalPresence.InBoth
+    else if (f) TemporalPresence.OnlyFrom
+    else TemporalPresence.OnlyTo
+
+  def presenceCounts(byTag: Map[(Int, String), Long]): Map[(Int, TemporalPresence), Long] =
+    byTag.toVector.flatMap { case ((p, tag), n) =>
+      TemporalPresence.fromTag(tag).map(x => ((p, x), n))
+    }.toMap
@@ changes()
-    val presence = grouped
-      .map { case ((p, _), (f, t)) =>
-        (
-          p,
-          if (f.isDefined && t.isDefined) TemporalPresence.InBoth
-          else if (f.isDefined) TemporalPresence.OnlyFrom
-          else TemporalPresence.OnlyTo
-        )
-      }
-      .countByValue()
-      .toMap
-    val edgeClasses = edgeBits.map { case ((p, _, _), m) => (p, m) }.countByValue().toMap
+    // Keys of Int and String only (SP-P6-07, see the object comment): never the enum itself.
+    val presence = presenceCounts(
+      countsOf(
+        grouped.map { case ((p, _), (f, t)) => (p, presenceOf(f.isDefined, t.isDefined).tag) },
+        partitions
+      )
+    )
+    val edgeClasses = countsOf(edgeBits.map { case ((p, _, _), m) => (p, m) }, partitions)
(The two Scaladoc hunks are abridged here; `git diff -- modules/spark` shows them in full.)
```
