package scholarchain.spark.job

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path}

import scala.collection.immutable.SortedMap
import scala.jdk.CollectionConverters.*

import org.apache.spark.sql.SparkSession

import scholarchain.core.*
import scholarchain.core.config.{AnalysisRunSettings, ConfigLoader}
import scholarchain.spark.analysis.{AnalysisJobConfig, AnalysisRun, CollaborationRunResult}
import scholarchain.spark.graph.Checkpointing
import scholarchain.spark.input.{ReaderLimits, RunTree, SyntheticCuratedPin, VerifiedArchiveReader}
import scholarchain.spark.output.{LoadManifests, PublishedRun, RunPublisher}
import scholarchain.spark.store.{HadoopRunStore, RunStore, S3SimFileSystem, StorePath}
import scholarchain.spark.testkit.{LocalSpark, LogicalReproducibility, MetricsFixtures, RunFixtures}

/**
 * The job entry point (SP-P6-04, [[CollaborationJob]], [[JobArguments]]): the argument contract
 * (every flag required once, every refusal before a Spark session exists, the unpinned sparkIT
 * placeholder refused, no `s3:` scheme, no overlap of input, output and checkpoint roots), and a
 * local end-to-end run over the synthetic fixture chain with `file:` URIs and with `s3sim` (S3A
 * rename semantics, [[S3SimFileSystem]]): exit status 0, the same run ID as
 * [[AnalysisRun.runCollaboration]] on the same input and configuration (the java.nio protocol), and
 * every Parquet and Neptune CSV dataset logically equal to it (same StructType, same row multiset;
 * [[LogicalReproducibility]]); the load manifests verify; a second run is refused (exit 1).
 */
class CollaborationJobSuite extends munit.FunSuite {

  private val code = "fedcba9876543210fedcba9876543210fedcba98"
  private val limits = ReaderLimits(1000, 6, 10000, 1L << 20, 1L << 16)

  // ---------------------------------------------------------------- argument contract (pure)

  private val base: Map[String, String] = Map(
    JobArguments.InputRoot -> "s3a://archive-bucket/dev-archive",
    JobArguments.CuratedRun -> s"curated/hadoop/snapshot=dblp-x/run=${"a" * 64}",
    JobArguments.DecisionRun -> s"er-decisions/run=${"b" * 64}",
    JobArguments.GroupingRun -> s"er-assignments/run=${"c" * 64}",
    JobArguments.OutputRoot -> "s3a://output-bucket/analysis",
    JobArguments.CheckpointDir -> "hdfs://namenode:8020/scholarchain/checkpoints",
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

  private def argv(m: Map[String, String]): Vector[String] =
    JobArguments.Flags.filter(m.contains).flatMap(f => Vector(f, m(f)))

  private def refused(m: Map[String, String], fragment: String): Unit =
    JobArguments.parse(argv(m)) match {
      case Right(a) => fail(s"accepted: $a")
      case Left(ps) => assert(ps.exists(_.contains(fragment)), ps.mkString("\n"))
    }

  test("the argument contract: every flag explicit, parsed into locations and bounds") {
    val a = JobArguments.parse(argv(base)).fold(e => fail(e.mkString("\n")), identity)
    assertEquals(a.inputRoot.toString, "s3a://archive-bucket/dev-archive")
    assertEquals(a.outputRoot.authority, Some("output-bucket"))
    assertEquals(a.curatedRun, base(JobArguments.CuratedRun))
    assertEquals(a.codeVersion, code)
    assertEquals((a.attempt, a.partitions), (1, 4))
    assertEquals(a.checkpointing, Checkpointing("hdfs://namenode:8020/scholarchain/checkpoints", 2))
    assertEquals(a.limits, ReaderLimits(20000, 4, 2000L, 67108864L, 1048576L))
    assertEquals(JobArguments.Flags.size, 17)
    assertEquals(JobArguments.Flags.distinct.size, 17)
    // Local absolute paths and file: URIs are accepted too.
    val local = base ++ Map(
      JobArguments.InputRoot -> "/data/archive",
      JobArguments.OutputRoot -> "file:///data/out",
      JobArguments.CheckpointDir -> "/data/checkpoints",
      JobArguments.Config -> "/data/analysis.conf",
      JobArguments.BenchmarkPairs -> "file:/data/pairs.json"
    )
    assert(JobArguments.parse(argv(local)).isRight)
  }

  test("refused: a missing, unknown, repeated or valueless flag, and an odd argument count") {
    refused(base - JobArguments.CodeVersion, "--code-version is required")
    refused(base - JobArguments.MaxMetadataBytes, "--max-metadata-bytes is required")
    JobArguments.parse(argv(base) ++ Vector("--master", "local[2]")) match {
      case Left(ps) => assert(ps.exists(_.contains("unknown flag '--master'")), ps)
      case Right(_) => fail("accepted --master")
    }
    JobArguments.parse(argv(base) ++ Vector(JobArguments.Attempt, "2")) match {
      case Left(ps) => assert(ps.exists(_.contains("--attempt is given 2 times")), ps)
      case Right(_) => fail("accepted a repeated flag")
    }
    JobArguments.parse(argv(base) :+ "--extra") match {
      case Left(ps) => assert(ps.exists(_.contains("expected '--flag value' pairs")), ps)
      case Right(_) => fail("accepted an odd count")
    }
    refused(base + (JobArguments.Partitions -> "--attempt"), "--partitions has no value")
    assert(JobArguments.parse(Vector.empty).left.exists(_.size == JobArguments.Flags.size))
  }

  test("refused: the unpinned sparkIT placeholder and any code version that is not 40 hex") {
    refused(
      base + (JobArguments.CodeVersion -> "unpinned-sparkit-dev"),
      "unpinned sparkIT placeholder"
    )
    refused(base + (JobArguments.CodeVersion -> ("0" * 40)), "unpinned sparkIT placeholder")
    refused(base + (JobArguments.CodeVersion -> code.toUpperCase), "not 40 lowercase hex")
    refused(base + (JobArguments.CodeVersion -> code.take(39)), "not 40 lowercase hex")
  }

  test("refused: s3: and s3n: schemes, malformed locations and run paths outside the root") {
    refused(base + (JobArguments.OutputRoot -> "s3://output-bucket/analysis"), "use s3a://")
    refused(base + (JobArguments.InputRoot -> "s3n://b/x"), "scheme 's3n' is not accepted")
    refused(base + (JobArguments.Config -> "relative/analysis.conf"), "--config:")
    refused(base + (JobArguments.CheckpointDir -> "s3://b/ck"), "--checkpoint-dir: scheme 's3'")
    refused(base + (JobArguments.CheckpointDir -> "/tmp/a/../ck"), "--checkpoint-dir:")
    refused(base + (JobArguments.GroupingRun -> "../er-assignments/run=c"), "'.' or '..'")
    refused(base + (JobArguments.GroupingRun -> "/er-assignments/run=c"), "is absolute")
    refused(
      base + (JobArguments.DecisionRun -> base(JobArguments.GroupingRun)),
      "must be three different runs"
    )
  }

  test("refused: overlapping input, output and checkpoint roots; non-positive bounds") {
    refused(
      base + (JobArguments.OutputRoot -> "s3a://archive-bucket/dev-archive/out"),
      "never writes into the archive"
    )
    refused(
      base + (JobArguments.CheckpointDir -> "s3a://archive-bucket/dev-archive/ck"),
      "never writes into the archive"
    )
    refused(
      base + (JobArguments.CheckpointDir -> "s3a://output-bucket/analysis/ck"),
      "--checkpoint-dir s3a://output-bucket/analysis/ck and --output-root"
    )
    refused(base + (JobArguments.Partitions -> "0"), "--partitions: '0' is not an integer >= 1")
    refused(base + (JobArguments.MaxTreeDepth -> "x"), "--max-tree-depth")
    refused(base + (JobArguments.MaxMetadataBytes -> (RunTree.MaxArrayBytes + 1).toString), "1..")
  }

  test("CR-P6-26 n2: root overlap is refused whatever the authority spelling") {
    // The reviewer's failure input: one name node with and without its port.
    refused(
      base ++ Map(
        JobArguments.OutputRoot -> "hdfs://namenode:8020/analysis",
        JobArguments.CheckpointDir -> "hdfs://namenode/analysis/ck"
      ),
      "--checkpoint-dir hdfs://namenode/analysis/ck and --output-root hdfs://namenode:8020/analysis overlap"
    )
    refused(
      base + (JobArguments.OutputRoot -> "s3a://archive-bucket:443/dev-archive/out"),
      "never writes into the archive"
    )
    // HDFS: another spelling of the name node (an address, no port) is the same file system.
    refused(
      base ++ Map(
        JobArguments.InputRoot -> "hdfs://namenode:8020/scholarchain/archive",
        JobArguments.CheckpointDir -> "hdfs://10.0.0.1/scholarchain"
      ),
      "--checkpoint-dir hdfs://10.0.0.1/scholarchain and --input-root hdfs://namenode:8020/scholarchain/archive overlap"
    )
    // Disjoint paths on one name node, and different buckets, stay accepted.
    assert(
      JobArguments
        .parse(
          argv(
            base ++ Map(
              JobArguments.OutputRoot -> "hdfs://namenode/analysis",
              JobArguments.CheckpointDir -> "hdfs://namenode:8020/checkpoints"
            )
          )
        )
        .isRight
    )
  }

  test("CR-P6-26 n2: the checkpoint and output overlap check of AnalysisRun ignores the port") {
    val cfg = AnalysisJobConfig(
      settings,
      code,
      1,
      SortedMap.empty,
      1,
      Checkpointing("hdfs://namenode/analysis/ck", 1)
    )
    def at(root: String) =
      AnalysisRun.structureProblems(cfg, StorePath.parse(root).fold(e => fail(e), identity))
    assert(at("hdfs://namenode:8020/analysis").exists(_.contains("overlaps the output root")))
    assert(at("hdfs://namenode/analysis/ck/out").exists(_.contains("overlaps the output root")))
    assertEquals(at("hdfs://namenode:8020/other"), Vector.empty)
  }

  test("CR-P6-26 n3: a comma or a glob character in any location is refused") {
    Vector(
      JobArguments.InputRoot -> "s3a://archive-bucket/dev,archive",
      JobArguments.InputRoot -> "/data/archive*",
      JobArguments.OutputRoot -> "s3a://output-bucket/out*",
      JobArguments.OutputRoot -> "/data/out?",
      JobArguments.CheckpointDir -> "/data/ck[0]",
      JobArguments.CheckpointDir -> "hdfs://namenode:8020/ck,x",
      JobArguments.Config -> "/data/{a,b}.conf",
      JobArguments.BenchmarkPairs -> "s3a://artifacts-bucket/pairs,x.json"
    ).foreach { case (flag, v) =>
      refused(base + (flag -> v), s"$flag: ")
      refused(base + (flag -> v), "comma or a Hadoop glob character")
    }
  }

  test("a cluster master refuses local input, output and checkpoint roots (executors' disks)") {
    val local = base ++ Map(
      JobArguments.InputRoot -> "/data/archive",
      JobArguments.OutputRoot -> "file:///data/out",
      JobArguments.CheckpointDir -> "/data/checkpoints",
      JobArguments.Config -> "/data/analysis.conf",
      JobArguments.BenchmarkPairs -> "/data/pairs.json"
    )
    val a = JobArguments.parse(argv(local)).fold(e => fail(e.mkString("\n")), identity)
    val p = JobArguments.clusterProblems("yarn", a)
    assertEquals(p.size, 3, p.mkString("\n"))
    Vector(JobArguments.InputRoot, JobArguments.OutputRoot, JobArguments.CheckpointDir).foreach {
      f => assert(p.exists(_.startsWith(s"$f ")), s"$f: $p")
    }
    // The driver-only files (configuration, pairs) may stay local; local masters share one disk.
    Vector("local", "local[2]", "local-cluster[2,1,1024]").foreach { m =>
      assertEquals(JobArguments.clusterProblems(m, a), Vector.empty, m)
    }
    val shared = JobArguments.parse(argv(base)).fold(e => fail(e.mkString("\n")), identity)
    assertEquals(JobArguments.clusterProblems("yarn", shared), Vector.empty)
  }

  test("refused arguments exit 2 before any Spark session is created") {
    val bytes = new ByteArrayOutputStream
    val code2 = CollaborationJob.run(
      argv(base - JobArguments.Config),
      () => fail("a Spark session was created for refused arguments"),
      new PrintStream(bytes, true, StandardCharsets.UTF_8),
      SyntheticCuratedPin.opener("run:" + "0" * 64)
    )
    assertEquals(code2, CollaborationJob.ExitArguments)
    val text = bytes.toString(StandardCharsets.UTF_8)
    assert(text.contains("scholarchain-collaboration-job refused: --config is required"), text)
    assert(text.contains("arguments (each required once"), text)
  }

  // ---------------------------------------------------------------- local end-to-end runs

  private var tmp: Path = null
  private var spark: SparkSession = null
  private var store: RunStore = null
  private var written: RunFixtures.Written = null
  private var configPath: Path = null
  private var pairsPath: Path = null
  private var baseline: CollaborationRunResult = null
  private var settings: AnalysisRunSettings = null

  override def beforeAll(): Unit = {
    tmp = Files.createTempDirectory("scholarchain-job-")
    spark = LocalSpark.start("scholarchain-job-test", tmp.resolve("spark"))
    S3SimFileSystem.register(spark.sparkContext.hadoopConfiguration)
    store = new HadoopRunStore(spark.sparkContext.hadoopConfiguration)
    written = RunFixtures.write(tmp.resolve("fixture"), MetricsFixtures.spec())
    val conf = Files.createDirectories(tmp.resolve("conf"))
    configPath = conf.resolve("analysis.conf")
    Files.writeString(
      configPath,
      LocalSpark.resourceText("scholarchain/spark/job/analysis-fixture.conf")
    )
    pairsPath = conf.resolve("pairs.json")
    Files.writeString(pairsPath, Json.write(pairs) + "\n")
    // The baseline: the java.nio reader and the staged-rename publisher, same settings and input.
    val text = Files.readString(configPath)
    settings = ConfigLoader
      .resolve(Vector(StorePath.local(configPath).toString -> text), Map.empty)
      .flatMap(AnalysisRunSettings.from)
      .fold(e => fail(e.map(_.message).mkString("\n")), identity)
    val input =
      VerifiedArchiveReader.read(written.runs, limits).fold(r => fail(r.mkString("\n")), identity)
    baseline = AnalysisRun
      .runCollaboration(
        spark,
        AnalysisJobConfig(
          settings,
          code,
          1,
          SortedMap.empty,
          1,
          Checkpointing(tmp.resolve("checkpoints-baseline").toString, 1)
        ),
        input,
        pairs,
        Files.createDirectories(tmp.resolve("out-baseline"))
      )
      .fold(e => fail(e.mkString("\n")), identity)
  }

  override def afterAll(): Unit =
    try if (spark != null) spark.stop()
    finally LocalSpark.deleteTree(tmp)

  private def pairs: BenchmarkPairList = RunFixtures
    .benchmarkPairs(written.groupingRunId)
    .copy(pairs =
      Vector(MetricsFixtures.rA, MetricsFixtures.rD)
        .sortWith((a, b) => Canonical.compareUtf8(a, b) < 0) match {
        case Vector(a, b) => Vector(BenchmarkPair(a, b))
        case _ => Vector.empty
      }
    )

  private def fileUri(p: Path): String = "file://" + p.toAbsolutePath.toString
  private def rel(p: Path): String = written.runs.archiveRoot.relativize(p).toString

  private def jobArgs(inputRoot: String, outputRoot: String, checkpoint: String): Vector[String] =
    argv(
      Map(
        JobArguments.InputRoot -> inputRoot,
        JobArguments.CuratedRun -> rel(written.runs.curatedRun),
        JobArguments.DecisionRun -> rel(written.runs.decisionRun),
        JobArguments.GroupingRun -> rel(written.runs.groupingRun),
        JobArguments.OutputRoot -> outputRoot,
        JobArguments.CheckpointDir -> checkpoint,
        JobArguments.Config -> fileUri(configPath),
        JobArguments.BenchmarkPairs -> fileUri(pairsPath),
        JobArguments.CodeVersion -> code,
        JobArguments.Attempt -> "1",
        JobArguments.Partitions -> "2",
        JobArguments.CheckpointInterval -> "1",
        JobArguments.MaxTreeEntries -> limits.maxTreeEntries.toString,
        JobArguments.MaxTreeDepth -> limits.maxTreeDepth.toString,
        JobArguments.MaxDriverValidationRows -> limits.maxDriverValidationRows.toString,
        JobArguments.MaxDriverValidationBytes -> limits.maxDriverValidationBytes.toString,
        JobArguments.MaxMetadataBytes -> limits.maxMetadataBytes.toString
      )
    )

  /** Runs the job body; (exit status, printed lines). */
  private def runJob(args: Vector[String], schemes: Set[String] = JobArguments.Schemes) = {
    val bytes = new ByteArrayOutputStream
    val status = CollaborationJob.run(
      args,
      () => spark,
      new PrintStream(bytes, true, StandardCharsets.UTF_8),
      SyntheticCuratedPin.opener(written.runs.pinnedCuratedRunId),
      schemes
    )
    (status, bytes.toString(StandardCharsets.UTF_8).split("\n").toVector)
  }

  private val parquetSchemas = LogicalReproducibility.ParquetDatasets

  private val csvDatasets = LogicalReproducibility.CsvDatasets

  private def hex = baseline.runId.stripPrefix("run:")

  /** The job's published run equals the baseline logically, with its verified load manifests. */
  private def logicallyEqualToBaseline(root: StorePath): PublishedRun = {
    val published = RunPublisher
      .verifyPublished(
        spark,
        store,
        root.resolve(s"run=$hex"),
        baseline.runId,
        limits.maxMetadataBytes
      )
      .fold(e => fail(e.mkString("\n")), identity)
    assertEquals(published.manifest, baseline.published.manifest)
    parquetSchemas.foreach { s =>
      assert(published.parquetPartsOf(s).nonEmpty, s)
      val r = LogicalReproducibility.compare(
        spark,
        baseline.published.parquetPartsOf(s),
        published.parquetPartsOf(s)
      )
      assert(r.equal, s"$s:\n${r.describe}")
    }
    csvDatasets.foreach { n =>
      val r = LogicalReproducibility.compareCsv(
        spark,
        baseline.published.datasetParts(n),
        published.datasetParts(n)
      )
      assert(r.equal, s"$n:\n${r.describe}")
    }
    val loads = LoadManifests
      .verify(store, root.resolve(s"load=$hex"), published, limits.maxMetadataBytes)
      .fold(e => fail(e.mkString("\n")), identity)
    def roles(ms: Vector[StoreLoadManifest]) =
      ms.map(m => (m.targetStore, m.items.map(_.targetRole).toSet)).toSet
    assertEquals(roles(loads.manifests), roles(baseline.step2C.loads.manifests))
    published
  }

  private def names(dir: Path): Vector[String] =
    if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) Vector.empty
    else Files.list(dir).iterator().asScala.map(_.getFileName.toString).toVector.sorted

  test("end to end over file: URIs: exit 0, the baseline's run ID, logically equal datasets") {
    val out = tmp.resolve("out-job-file")
    val (status, lines) = runJob(
      jobArgs(fileUri(written.runs.archiveRoot), fileUri(out), fileUri(tmp.resolve("ck-job-file")))
    )
    assertEquals(status, CollaborationJob.ExitOk, lines.mkString("\n"))
    val run = lines
      .find(_.startsWith("scholarchain-collaboration-job run: "))
      .getOrElse(fail("no run line"))
    assert(run.contains(s"analysisRunId=${baseline.runId}"), run)
    assert(run.contains(s"edges=${baseline.edgeCount} metrics=${baseline.metricsCount}"), run)
    assert(run.contains(s"codeVersion=$code attempt=1 partitions=2"), run)
    assert(lines.exists(_.contains("structure window: window=2015-2019")), lines.mkString("\n"))
    assertEquals(lines.count(_.contains(" load manifest: store=")), TargetStore.values.size)
    assert(
      lines.exists(
        _.contains(s"output: run=${out.resolve(s"run=$hex")} loads=${out.resolve(s"load=$hex")}")
      ),
      lines.mkString("\n")
    )
    logicallyEqualToBaseline(StorePath.local(out))
    assertEquals(names(out), Vector(s"load=$hex", s"run=$hex"), "no scratch prefix left")
    // The local java.nio reader accepts the run the job published through Hadoop.
    assertEquals(
      VerifiedArchiveReader.layout("analysis", out.resolve(s"run=$hex"), limits),
      Vector.empty
    )
    // A second run of the same arguments is refused: the run exists (create-once).
    val (again, againLines) = runJob(
      jobArgs(
        fileUri(written.runs.archiveRoot),
        fileUri(out),
        fileUri(tmp.resolve("ck-job-file-2"))
      )
    )
    assertEquals(again, CollaborationJob.ExitRefused, againLines.mkString("\n"))
    assert(
      againLines.exists(_.contains("already exists: never overwritten")),
      againLines.mkString("\n")
    )
  }

  test("end to end with S3 semantics (s3sim input and output): no rename outside the scratch") {
    val out = tmp.resolve("out-job-s3sim")
    S3SimFileSystem.renames.clear()
    val (status, lines) = runJob(
      jobArgs(
        S3SimFileSystem.locationOf(written.runs.archiveRoot).toString,
        S3SimFileSystem.locationOf(out).toString,
        fileUri(tmp.resolve("ck-job-s3sim"))
      ),
      JobArguments.Schemes + S3SimFileSystem.Scheme
    )
    assertEquals(status, CollaborationJob.ExitOk, lines.mkString("\n"))
    val log = S3SimFileSystem.renameLog
    assert(log.nonEmpty, "Spark renamed inside the scratch prefix")
    assert(log.forall(_.contains(s"/.spark-$hex/")), log.mkString("\n"))
    assert(
      !log.exists(e => e.contains(s"/run=$hex") || e.contains(s"/load=$hex")),
      log.mkString("\n")
    )
    val published = logicallyEqualToBaseline(S3SimFileSystem.locationOf(out))
    assertEquals(published.dir.scheme, S3SimFileSystem.Scheme)
    assertEquals(names(out), Vector(s"load=$hex", s"run=$hex"))
    assertEquals(
      VerifiedArchiveReader.layout(store, "analysis", published.dir, limits),
      Vector.empty
    )
  }

  test("CR-P6-26 u1: s3a: roots without kept directory markers are refused (exit 1) before I/O") {
    val conf = spark.sparkContext.hadoopConfiguration
    val key = StartupChecks.MarkerRetentionKey
    val before = Option(conf.get(key))
    conf.set(key, "delete")
    val (status, lines) =
      try runJob(argv(base))
      finally before.fold(conf.unset(key))(v => conf.set(key, v))
    assertEquals(status, CollaborationJob.ExitRefused, lines.mkString("\n"))
    val refusals = lines.filter(_.startsWith("scholarchain-collaboration-job refused: "))
    // One per s3a: location of base (input, output, configuration, pairs; the checkpoint is hdfs).
    assertEquals(refusals.size, 4, lines.mkString("\n"))
    assert(refusals.forall(_.contains(s"$key is 'delete'")), refusals.mkString("\n"))
    assert(refusals.exists(_.contains("--output-root s3a://output-bucket/analysis")), refusals)
    // Refused before the configuration file was read (no S3A client was ever asked for it).
    assert(!lines.exists(_.contains("configuration:")), lines.mkString("\n"))
    assert(!lines.exists(_.contains("failure:")), lines.mkString("\n"))
  }

  test("CR-P6-26 n4: a load-manifest refusal after the run published names the load directory") {
    val out = tmp.resolve("out-job-load-exists")
    val foreign = Files.createDirectories(out.resolve(s"load=$hex"))
    val (status, lines) = runJob(
      jobArgs(fileUri(written.runs.archiveRoot), fileUri(out), fileUri(tmp.resolve("ck-job-load")))
    )
    assertEquals(status, CollaborationJob.ExitRefused, lines.mkString("\n"))
    val text = lines.mkString("\n")
    assert(text.contains(s"load manifests: ${out.resolve(s"load=$hex")} already exists"), text)
    assert(
      text.contains(
        s"run ${baseline.runId} was published and verified at ${out.resolve(s"run=$hex")}, " +
          s"but it has no verified load manifests: ${out.resolve(s"load=$hex")} is absent, or present"
      ),
      text
    )
    assert(!text.contains("its load manifests were not written"), text)
    // The run itself verifies; the existing load directory is left exactly as it was.
    val run = StorePath.local(out.resolve(s"run=$hex"))
    assert(
      RunPublisher
        .verifyPublished(spark, store, run, baseline.runId, limits.maxMetadataBytes)
        .isRight
    )
    assertEquals(names(foreign), Vector.empty)
    assertEquals(names(out), Vector(s"load=$hex", s"run=$hex"))
  }
}
