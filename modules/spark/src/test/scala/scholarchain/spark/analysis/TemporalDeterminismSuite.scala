package scholarchain.spark.analysis

import java.nio.file.{Files, Path}

import scala.collection.immutable.SortedMap

import org.apache.spark.{HashPartitioner, TaskContext}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession

import scholarchain.core.*
import scholarchain.spark.graph.{Checkpointing, TemporalJob}
import scholarchain.spark.input.{ReaderLimits, VerifiedArchiveReader, VerifiedInput}
import scholarchain.spark.testkit.{LocalSpark, MetricsFixtures, RunFixtures}

/**
 * A key whose hash depends on the task that computes it, as an identity hash depends on the JVM
 * (SP-P6-07): equal by value everywhere, but map task m routes it to reducer `m mod P`, and the
 * driver (no task) hashes it to 0. One JVM thereby reproduces what two executor JVMs do to a Scala
 * 3 enum case in a shuffle key.
 */
final case class TaskHashedKey(value: String) {
  override def hashCode: Int = {
    val tc = TaskContext.get()
    if (tc == null) 0 else tc.partitionId()
  }
}

/**
 * SP-P6-07: the temporal per-pair counts are independent of executor JVMs, partitioning and
 * recomputation.
 *
 * Root cause (BE-P6-10 attempt-3, `local-cluster[2,1,1024]`: 112 change records, the comparisons
 * counted 55 researchers): the presence counts were `countByValue` over (pair, TemporalPresence)
 * keys. A Scala 3 enum case hashes by identity, which differs between JVMs, so map tasks on two
 * executors routed one presence class to two reducers, and `countByValue`'s `collect().toMap` kept
 * only one of the two partial counts. In one JVM (`local[N]`, every suite here) the identity hash
 * is consistent, so a full run cannot show it; the tests below therefore check (1) the hazard
 * itself, (2) that the counting used now is exact for a key routed to several reducers, which the
 * old merge is not (reproduced in one JVM by [[TaskHashedKey]]), (3) that the keys the temporal job
 * shuffles hash by value, and (4) on the hand-checkable [[MetricsFixtures]] chain, that runs at 2
 * and 4 partitions give the hand counts (in both 3, only before 2, only after 1; edges 2, 0, 4) and
 * 6 changes, with the run's temporal check passing. A true two-JVM run is the
 * `sbt sparkSubmitLocal` gate (build-engineer), not part of `sbt check`.
 */
class TemporalDeterminismSuite extends munit.FunSuite {

  private var tmp: Path = null
  private var spark: SparkSession = null
  private var written: RunFixtures.Written = null
  private var input: VerifiedInput = null

  private val limits = ReaderLimits(1000, 6, 10000, 1L << 20, 1L << 16)

  override def beforeAll(): Unit = {
    tmp = Files.createTempDirectory("scholarchain-temporal-determinism-")
    spark = LocalSpark.start("scholarchain-temporal-determinism-test", tmp.resolve("spark"))
    written = RunFixtures.write(tmp.resolve("fixture"), MetricsFixtures.spec())
    input =
      VerifiedArchiveReader.read(written.runs, limits).fold(r => fail(r.mkString("\n")), identity)
  }

  override def afterAll(): Unit =
    try if (spark != null) spark.stop()
    finally LocalSpark.deleteTree(tmp)

  test("a Scala 3 enum case hashes by identity, so it is never part of a shuffle key") {
    TemporalPresence.values.foreach { x =>
      assertEquals(x.hashCode, System.identityHashCode(x), x.tag)
    }
    // The key the temporal job shuffles instead: Int and the frozen tag, hashed by value.
    val k = (0, TemporalJob.presenceOf(true, true).tag)
    assertEquals(k, (0, "in-both"))
    assertEquals(k.hashCode, (0, new String("in-both".toCharArray)).hashCode)
  }

  test("presenceOf and presenceCounts: the tag counts map back to the three classes") {
    import TemporalPresence.*
    assertEquals(
      Vector((true, true), (true, false), (false, true)).map { case (f, t) =>
        TemporalJob.presenceOf(f, t)
      },
      Vector(InBoth, OnlyFrom, OnlyTo)
    )
    assertEquals(
      TemporalJob.presenceCounts(
        Map((0, "in-both") -> 3L, (0, "only-from") -> 2L, (1, "only-to") -> 1L)
      ),
      Map((0, InBoth) -> 3L, (0, OnlyFrom) -> 2L, (1, OnlyTo) -> 1L)
    )
  }

  test("countsOf is exact for a key routed to several reducers; the old toMap merge is not") {
    val sc = spark.sparkContext
    // 10 equal keys in 2 map partitions (5 each); map task m sends them to reducer m.
    val keys: RDD[TaskHashedKey] = sc.parallelize(Vector.fill(10)(TaskHashedKey("k")), 2)
    val reduced = keys
      .map(k => (k, 1L))
      .reduceByKey(new HashPartitioner(2), _ + _)
      .collect()
      .toVector
    // The failure input: one key reaches the driver from two reducers, 5 + 5.
    assertEquals(reduced.map(_._2).sorted, Vector(5L, 5L))
    // countByValue's merge (collect().toMap) keeps one partial count: the SP-P6-07 defect.
    assertEquals(reduced.toMap.apply(TaskHashedKey("k")), 5L)
    // countsOf sums them, at every partition count and on every evaluation.
    Vector(1, 2, 3, 5).foreach { p =>
      assertEquals(TemporalJob.countsOf(keys, p), Map(TaskHashedKey("k") -> 10L), s"partitions $p")
      assertEquals(TemporalJob.countsOf(keys, p), Map(TaskHashedKey("k") -> 10L), s"again, $p")
    }
    // Value-hashed keys, as the temporal job uses: exact as well.
    val tagged = sc.parallelize(
      Vector.fill(3)((0, "in-both")) ++ Vector.fill(2)((0, "only-from")) :+ ((0, "only-to")),
      3
    )
    assertEquals(
      TemporalJob.countsOf(tagged, 2),
      Map((0, "in-both") -> 3L, (0, "only-from") -> 2L, (0, "only-to") -> 1L)
    )
  }

  private def pairs = RunFixtures
    .benchmarkPairs(written.groupingRunId)
    .copy(pairs = Vector(RunFixtures.sorted(MetricsFixtures.rA, MetricsFixtures.rD) match {
      case Vector(a, b) => BenchmarkPair(a, b)
      case other => fail(s"pair $other")
    }))

  private def run(partitions: Int): CollaborationRunResult =
    AnalysisRun
      .runCollaboration(
        spark,
        AnalysisJobConfig(
          RunFixtures.settings,
          "0123456789abcdef0123456789abcdef01234567",
          1,
          SortedMap.empty,
          partitions,
          Checkpointing(tmp.resolve(s"checkpoints-p$partitions").toString, 2)
        ),
        input,
        pairs,
        Files.createDirectories(tmp.resolve(s"out-p$partitions"))
      )
      .fold(e => fail(e.mkString("\n")), identity)

  test("runs at 2 and 4 partitions: hand counts, 6 changes, the temporal check passes") {
    val results = Vector(2, 4).map(run)
    results.foreach { r =>
      val c = r.step2C.windowComparisons
      assertEquals(c.size, 1)
      assertEquals(
        (
          c.head.researchersInBoth,
          c.head.researchersOnlyFrom,
          c.head.researchersOnlyTo,
          c.head.edgesInBoth,
          c.head.edgesOnlyFrom,
          c.head.edgesOnlyTo
        ),
        (3L, 2L, 1L, 2L, 0L, 4L)
      )
      assertEquals(r.step2C.temporalChangeCount, 6L)
    }
    assertEquals(results(1).step2C.windowComparisons, results(0).step2C.windowComparisons)
    assertEquals(results(1).runId, results(0).runId)
  }
}
