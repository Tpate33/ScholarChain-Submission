package scholarchain.spark.job

import org.apache.hadoop.conf.Configuration
import org.apache.spark.SparkConf

/**
 * The start-up checks of the effective configuration ([[StartupChecks]]; CR-P6-26 u1 and the
 * single-writer risk), pure: Hadoop Configurations without default resources and SparkConfs without
 * system properties, no file system, no Spark context, no YARN.
 */
class StartupChecksSuite extends munit.FunSuite {

  private val code = "fedcba9876543210fedcba9876543210fedcba98"

  private val s3a: Map[String, String] = Map(
    JobArguments.InputRoot -> "s3a://archive-bucket/dev-archive",
    JobArguments.CuratedRun -> s"curated/hadoop/snapshot=dblp-x/run=${"a" * 64}",
    JobArguments.DecisionRun -> s"er-decisions/run=${"b" * 64}",
    JobArguments.GroupingRun -> s"er-assignments/run=${"c" * 64}",
    JobArguments.OutputRoot -> "s3a://output-bucket/analysis",
    JobArguments.CheckpointDir -> "s3a://output-bucket/checkpoints",
    JobArguments.Config -> "s3a://artifacts-bucket/config/analysis.conf",
    JobArguments.BenchmarkPairs -> "s3a://artifacts-bucket/config/pairs.json",
    JobArguments.CodeVersion -> code,
    JobArguments.Attempt -> "1",
    JobArguments.Partitions -> "4",
    JobArguments.CheckpointInterval -> "2",
    JobArguments.MaxTreeEntries -> "20000",
    JobArguments.MaxTreeDepth -> "4",
    JobArguments.MaxDriverValidationRows -> "2000",
    JobArguments.MaxDriverValidationBytes -> "67108864",
    JobArguments.MaxMetadataBytes -> "1048576"
  )

  private def args(m: Map[String, String]): JobArguments =
    JobArguments
      .parse(JobArguments.Flags.flatMap(f => Vector(f, m(f))))
      .fold(e => fail(e.mkString("\n")), identity)

  private def conf(kv: (String, String)*): Configuration = {
    val c = new Configuration(false)
    kv.foreach { case (k, v) => c.set(k, v) }
    c
  }

  private val key = StartupChecks.MarkerRetentionKey

  test("u1: unset or not 'keep' is refused, once per s3a: location, naming the key and value") {
    val a = args(s3a)
    val unset = StartupChecks.markerRetentionProblems(conf(), a)
    assertEquals(unset.size, 5, unset.mkString("\n"))
    assert(unset.forall(_.contains(s"the effective $key is unset")), unset.mkString("\n"))
    Vector(
      JobArguments.InputRoot,
      JobArguments.OutputRoot,
      JobArguments.CheckpointDir,
      JobArguments.Config,
      JobArguments.BenchmarkPairs
    ).foreach(f => assert(unset.exists(_.startsWith(s"$f s3a://")), s"$f: $unset"))
    Vector("delete", "authoritative", "KEEP", "", "keep,delete").foreach { v =>
      val p = StartupChecks.markerRetentionProblems(conf(key -> v), a)
      assertEquals(p.size, 5, s"'$v': $p")
      assert(p.forall(_.contains(s"$key is '$v'")), s"'$v': $p")
    }
  }

  test("u1: 'keep' is accepted (trimmed); the per-bucket key overrides the base key") {
    val a = args(s3a)
    assertEquals(StartupChecks.markerRetentionProblems(conf(key -> "keep"), a), Vector.empty)
    assertEquals(StartupChecks.markerRetentionProblems(conf(key -> " keep "), a), Vector.empty)
    // Base keep, one bucket deleting: only that bucket's two locations are refused.
    val outKey = StartupChecks.bucketMarkerRetentionKey("output-bucket")
    assertEquals(outKey, "fs.s3a.bucket.output-bucket.directory.marker.retention")
    val one = StartupChecks.markerRetentionProblems(conf(key -> "keep", outKey -> "delete"), a)
    assertEquals(
      one.map(_.takeWhile(_ != ' ')).sorted,
      Vector(JobArguments.CheckpointDir, JobArguments.OutputRoot).sorted
    )
    assert(one.forall(_.contains(s"the effective $outKey is 'delete'")), one)
    // Base delete, every bucket keeping: accepted.
    val all = Vector("archive-bucket", "output-bucket", "artifacts-bucket")
      .map(b => StartupChecks.bucketMarkerRetentionKey(b) -> "keep")
    assertEquals(
      StartupChecks.markerRetentionProblems(conf((key -> "delete") +: all*), a),
      Vector.empty
    )
    // A port in the authority does not change the bucket of the per-bucket key.
    val ported = args(s3a + (JobArguments.InputRoot -> "s3a://archive-bucket:443/dev-archive"))
    assertEquals(
      StartupChecks.markerRetentionProblems(conf((key -> "delete") +: all*), ported),
      Vector.empty
    )
  }

  test("u1: no s3a: location, no requirement (local and HDFS roots)") {
    val local = args(
      s3a ++ Map(
        JobArguments.InputRoot -> "/data/archive",
        JobArguments.OutputRoot -> "hdfs://namenode:8020/analysis",
        JobArguments.CheckpointDir -> "hdfs://namenode:8020/checkpoints",
        JobArguments.Config -> "/data/analysis.conf",
        JobArguments.BenchmarkPairs -> "file:/data/pairs.json"
      )
    )
    assertEquals(StartupChecks.markerRetentionProblems(conf(), local), Vector.empty)
    assertEquals(StartupChecks.markerRetentionProblems(conf(key -> "delete"), local), Vector.empty)
  }

  private def spark(kv: (String, String)*): SparkConf = {
    val c = new SparkConf(false)
    kv.foreach { case (k, v) => c.set(k, v) }
    c
  }

  private val attempts = StartupChecks.MaxAppAttemptsKey

  test("YARN: spark.yarn.maxAppAttempts must be exactly 1; unset or any other value is refused") {
    assertEquals(attempts, "spark.yarn.maxAppAttempts")
    val unset = StartupChecks.attemptProblems("yarn", spark())
    assertEquals(unset.size, 1)
    assert(unset.head.contains(s"$attempts is unset"), unset)
    Vector("2", "0", "", "one", "1,2").foreach { v =>
      val p = StartupChecks.attemptProblems("yarn", spark(attempts -> v))
      assert(p.size == 1 && p.head.contains(s"$attempts is '$v'"), s"'$v': $p")
    }
    assertEquals(StartupChecks.attemptProblems("yarn", spark(attempts -> "1")), Vector.empty)
    assertEquals(StartupChecks.attemptProblems("yarn", spark(attempts -> " 1 ")), Vector.empty)
  }

  test("YARN check applies to YARN masters only") {
    Vector("local", "local[2]", "local-cluster[2,1,1024]", "spark://host:7077").foreach { m =>
      assertEquals(StartupChecks.attemptProblems(m, spark()), Vector.empty, m)
    }
    assertEquals(StartupChecks.attemptProblems(null, spark()), Vector.empty)
  }

  test("problems: both checks, attempts first, then markers") {
    val a = args(s3a)
    val p = StartupChecks.problems("yarn", spark(), conf(), a)
    assertEquals(p.size, 6, p.mkString("\n"))
    assert(p.head.contains(attempts), p)
    assertEquals(
      StartupChecks.problems("yarn", spark(attempts -> "1"), conf(key -> "keep"), a),
      Vector.empty
    )
  }
}
