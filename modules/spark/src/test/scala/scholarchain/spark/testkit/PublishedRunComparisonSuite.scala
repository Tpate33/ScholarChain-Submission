package scholarchain.spark.testkit

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path}

import scala.collection.immutable.SortedMap
import scala.jdk.CollectionConverters.*
import scala.util.Using

import org.apache.spark.sql.SparkSession

import scholarchain.core.*
import scholarchain.core.config.{AnalysisRunSettings, ConfigLoader}
import scholarchain.spark.analysis.{AnalysisJobConfig, AnalysisRun, CollaborationRunResult}
import scholarchain.spark.graph.Checkpointing
import scholarchain.spark.input.{ReaderLimits, VerifiedArchiveReader}
import scholarchain.spark.output.LoadManifests

/**
 * [[PublishedRunComparisonMain]] (SP-P6-06) over three runs published from the synthetic fixture
 * chain of the job suite (RunFixtures + MetricsFixtures, analysis-fixture.conf):
 *   - A and B: the same input and configuration with 1 and 2 partitions (partitions are not part of
 *     the run identity): one run ID, every dataset logically equal; the result file is checked line
 *     by line against facts computed here from the runs' own part and load manifests, and against
 *     the parse rules of the build's `SparkSubmitLocal.parseComparison` (replicated below, the
 *     build's code cannot be imported);
 *   - C: attempt 2 of the same run manifest (another run ID): compared (exit 0) and `different`.
 * Refusals: argument refusals exit 2 before a Spark session is asked for (an existing result file
 * included, left unchanged); a tampered part, `_UNVERIFIED`, a missing `_SUCCESS`, a missing load
 * directory, two runs in one root or a missing root exit 1 and write no result file.
 */
class PublishedRunComparisonSuite extends munit.FunSuite {

  private val code = "fedcba9876543210fedcba9876543210fedcba98"
  private val limits = ReaderLimits(1000, 6, 10000, 1L << 20, 1L << 16)
  private val nonce = "0123456789abcdef" * 4

  private var tmp: Path = null
  private var spark: SparkSession = null
  private var a: CollaborationRunResult = null
  private var b: CollaborationRunResult = null
  private var c: CollaborationRunResult = null
  private var rootA: Path = null
  private var rootB: Path = null
  private var rootC: Path = null
  private var results: Path = null

  override def beforeAll(): Unit = {
    // Real path: on macOS java.io.tmpdir lies behind the /var -> /private/var link, which the
    // comparison refuses on a root path.
    tmp = Files.createTempDirectory("scholarchain-compare-").toRealPath()
    spark = LocalSpark.start("scholarchain-compare-test", tmp.resolve("spark"))
    val written = RunFixtures.write(tmp.resolve("fixture"), MetricsFixtures.spec())
    val confText = LocalSpark.resourceText("scholarchain/spark/job/analysis-fixture.conf")
    val settings = ConfigLoader
      .resolve(Vector("classpath:analysis-fixture.conf" -> confText), Map.empty)
      .flatMap(AnalysisRunSettings.from)
      .fold(e => fail(e.map(_.message).mkString("\n")), identity)
    val input =
      VerifiedArchiveReader.read(written.runs, limits).fold(r => fail(r.mkString("\n")), identity)
    val pairs = RunFixtures
      .benchmarkPairs(written.groupingRunId)
      .copy(pairs =
        Vector(MetricsFixtures.rA, MetricsFixtures.rD)
          .sortWith((x, y) => Canonical.compareUtf8(x, y) < 0) match {
          case Vector(x, y) => Vector(BenchmarkPair(x, y))
          case _ => Vector.empty
        }
      )
    def publish(name: String, partitions: Int, attempt: Int): (Path, CollaborationRunResult) = {
      val root = Files.createDirectories(tmp.resolve(name))
      val r = AnalysisRun
        .runCollaboration(
          spark,
          AnalysisJobConfig(
            settings,
            code,
            attempt,
            SortedMap.empty,
            partitions,
            Checkpointing(tmp.resolve(s"ck-$name").toString, 1)
          ),
          input,
          pairs,
          root
        )
        .fold(e => fail(e.mkString("\n")), identity)
      (root, r)
    }
    val (ra, xa) = publish("out-a", 1, 1)
    val (rb, xb) = publish("out-b", 2, 1)
    val (rc, xc) = publish("out-c", 1, 2)
    rootA = ra; rootB = rb; rootC = rc
    a = xa; b = xb; c = xc
    results = Files.createDirectories(tmp.resolve("results"))
  }

  override def afterAll(): Unit =
    try if (spark != null) spark.stop()
    finally LocalSpark.deleteTree(tmp)

  private def argv(left: Path, right: Path, result: Path, n: String = nonce): Vector[String] =
    Vector(
      PublishedRunComparisonMain.LeftRoot,
      left.toString,
      PublishedRunComparisonMain.RightRoot,
      right.toString,
      PublishedRunComparisonMain.NonceFlag,
      n,
      PublishedRunComparisonMain.ResultFlag,
      result.toString
    )

  /** Runs the main body with the suite's session (or a session that must not be asked for). */
  private def compare(args: Seq[String], allowSession: Boolean = true): (Int, String) = {
    val bytes = new ByteArrayOutputStream
    val status = PublishedRunComparisonMain.run(
      args,
      () => if (allowSession) spark else fail("a Spark session was asked for"),
      new PrintStream(bytes, true, StandardCharsets.UTF_8)
    )
    (status, bytes.toString(StandardCharsets.UTF_8))
  }

  private def read(p: Path): String =
    new String(Files.readAllBytes(p), StandardCharsets.UTF_8)

  private def copyTree(src: Path, dst: Path): Path = {
    Using.resource(Files.walk(src)) { s =>
      s.iterator().asScala.foreach { p =>
        val t = dst.resolve(src.relativize(p).toString)
        if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(t)
        else Files.copy(p, t)
      }
    }
    dst
  }

  private def hexOf(r: CollaborationRunResult) = r.runId.stripPrefix("run:")

  // ---------------------------------------------------------------- the build's parse rules

  private val RunDir = "^run=[0-9a-f]{64}$".r
  private def nat(s: String): Option[Long] = scala.util.Try(s.toLong).toOption.filter(_ >= 0)

  /**
   * A replica of `SparkSubmitLocal.parseComparison` (project/SparkSubmitLocal.scala,
   * build-engineer): the same line rules; returns the runs, counts, loads and dataset lines.
   */
  private def parseLikeBuild(text: String, n: String): Either[
    String,
    (
        Map[String, String],
        Map[(String, String), Long],
        Map[(String, String), (Long, Long)],
        Vector[(String, Long, Long, Boolean)]
    )
  ] = {
    val lines = text.split("\n", -1).toVector
    if (!text.endsWith("\n") || lines.size < 6) Left("incomplete")
    else if (lines(0) != s"format=${PublishedRunComparisonMain.Format}") Left("format")
    else if (lines(1) != s"nonce=$n" || lines(lines.size - 2) != s"complete=$n") Left("nonce")
    else {
      val body = lines.slice(2, lines.size - 3)
      var runs = Map.empty[String, String]
      var counts = Map.empty[(String, String), Long]
      var loads = Map.empty[(String, String), (Long, Long)]
      var ds = Vector.empty[(String, Long, Long, Boolean)]
      def side(s: String) = s == "left" || s == "right"
      val bad = body.zipWithIndex.filterNot { case (l, _) =>
        l.split("\t", -1).toSeq match {
          case Seq("run", s, r) if side(s) && RunDir.matches(r) && !runs.contains(s) =>
            runs += s -> r; true
          case Seq("count", s, k, v)
              if side(s) && (k == "edges" || k == "metrics") && nat(v).isDefined &&
                !counts.contains((s, k)) =>
            counts += (s, k) -> nat(v).get; true
          case Seq("load", s, st, x, y)
              if side(s) && st.nonEmpty && nat(x).isDefined && nat(y).isDefined &&
                !loads.contains((s, st)) =>
            loads += (s, st) -> (nat(x).get, nat(y).get); true
          case Seq("dataset", name, x, y, e)
              if name.nonEmpty && nat(x).isDefined && nat(y).isDefined &&
                (e == "equal" || e == "different") && !ds.exists(_._1 == name) =>
            ds :+= ((name, nat(x).get, nat(y).get, e == "equal")); true
          case _ => false
        }
      }
      val declared = lines(lines.size - 3)
      if (bad.nonEmpty) Left(s"malformed lines ${bad.map(_._2 + 3).mkString(",")}")
      else if (!declared.startsWith("datasets=") || nat(declared.drop(9)) != Some(ds.size.toLong))
        Left("datasets=")
      else if (!runs.contains("left") || !runs.contains("right")) Left("run line missing")
      else Right((runs, counts, loads, ds))
    }
  }

  // ---------------------------------------------------------------- the facts expected

  private def tag(t: TargetStore) = PublishedRunComparisonMain.storeTag(t)

  private def expectedText(
      l: CollaborationRunResult,
      r: CollaborationRunResult,
      equal: Boolean
  ): String = {
    def rows(x: CollaborationRunResult, parts: Vector[String]) =
      x.published.partManifest.files.filter(f => parts.contains(f.path)).map(_.rowCount).sum
    def parquet(x: CollaborationRunResult, s: String) =
      x.published.partManifest.files
        .filter(f => f.recordSchemaVersion == s && f.format == PartFormat.Parquet)
        .map(_.path)
    def csv(x: CollaborationRunResult, n: String) =
      x.published.partManifest.files.filter(_.path.startsWith(n + "/")).map(_.path)
    def counts(label: String, x: CollaborationRunResult) = Vector(
      s"count\t$label\tedges\t${x.edgeCount}",
      s"count\t$label\tmetrics\t${x.metricsCount}"
    )
    def loads(label: String, x: CollaborationRunResult) =
      TargetStore.values.toVector.map { t =>
        val m = x.step2C.loads.of(t).getOrElse(fail(s"no ${t.tag} manifest"))
        s"load\t$label\t${tag(t)}\t${m.items.size}\t${m.items.map(_.rowCount).sum}"
      }
    val e = if (equal) "equal" else "different"
    val datasets =
      LogicalReproducibility.ParquetDatasets.map(s =>
        s"dataset\t$s\t${rows(l, parquet(l, s))}\t${rows(r, parquet(r, s))}\t$e"
      ) ++ LogicalReproducibility.CsvDatasets.map(n =>
        s"dataset\t$n\t${rows(l, csv(l, n))}\t${rows(r, csv(r, n))}\t$e"
      )
    (Vector(
      s"format=scholarchain.spark.published-run-comparison.v1",
      s"nonce=$nonce",
      s"run\tleft\trun=${hexOf(l)}",
      s"run\tright\trun=${hexOf(r)}"
    ) ++ counts("left", l) ++ counts("right", r) ++ loads("left", l) ++ loads("right", r) ++
      datasets ++ Vector("datasets=28", s"complete=$nonce")).mkString("", "\n", "\n")
  }

  // ---------------------------------------------------------------- tests

  test("the dataset lists: 16 Parquet and 12 Neptune CSV datasets, 28 distinct names") {
    assertEquals(LogicalReproducibility.ParquetDatasets.size, 16)
    assertEquals(LogicalReproducibility.CsvDatasets.size, 12)
    val all = LogicalReproducibility.ParquetDatasets ++ LogicalReproducibility.CsvDatasets
    assertEquals(all.distinct.size, 28)
    assert(all.forall(n => n.nonEmpty && !n.exists(ch => ch == '\t' || ch == '\n')), all)
    assertEquals(
      TargetStore.values.toVector.map(tag),
      Vector("neptune", "dynamodb", "s3", "s3-vectors")
    )
  }

  test("two publications of one run: exit 0, every dataset equal, the exact v1 lines") {
    assertEquals(a.runId, b.runId, "partitions are not part of the run identity")
    val out = results.resolve("equal.txt")
    val (status, log) = compare(argv(rootA, rootB, out))
    assertEquals(status, PublishedRunComparisonMain.ExitCompared, log)
    val text = read(out)
    assertEquals(text, expectedText(a, b, equal = true))
    // Byte form: UTF-8, LF only, final LF, the nonce line last.
    val bytes = Files.readAllBytes(out)
    assert(!bytes.contains('\r'.toByte), "no CR")
    assertEquals(bytes.last, '\n'.toByte)
    assertEquals(text.split("\n").last, s"complete=$nonce")
    assert(log.contains("compared: 28 datasets, 0 different"), log)
    // The counts are the run result's own (edgeCount, metricsCount), read back by the build's rules.
    val (runs, counts, loads, ds) = parseLikeBuild(text, nonce).fold(fail(_), identity)
    assertEquals(runs, Map("left" -> s"run=${hexOf(a)}", "right" -> s"run=${hexOf(a)}"))
    assertEquals(counts((("left", "edges"))), a.edgeCount)
    assertEquals(counts((("right", "metrics"))), b.metricsCount)
    assertEquals(
      loads.keySet.map(_._2),
      Set("neptune", "dynamodb", "s3", "s3-vectors")
    )
    assertEquals(loads.size, 8)
    assertEquals(ds.size, 28)
    assert(ds.forall(d => d._4 && d._2 == d._3), ds)
    // Datasets the fixture leaves empty (no venue, institution or topic records) compare as equal
    // zero-row multisets; the edge dataset holds the run's edges.
    assert(ds.exists(_._2 == 0L), ds)
    assert(ds.contains((SchemaVersions.CoauthorEdgeV2, a.edgeCount, a.edgeCount, true)), ds)
    // A second comparison onto the same result file is refused (create-once).
    val before = Files.readAllBytes(out)
    val (again, againLog) = compare(argv(rootA, rootB, out), allowSession = false)
    assertEquals(again, PublishedRunComparisonMain.ExitArguments, againLog)
    assert(againLog.contains("already exists (the result file is created once)"), againLog)
    assert(java.util.Arrays.equals(Files.readAllBytes(out), before), "unchanged")
  }

  test("another run (attempt 2): exit 0, other run name, datasets reported different") {
    assertNotEquals(a.runId, c.runId)
    val out = results.resolve("different.txt")
    val (status, log) = compare(argv(rootA, rootC, out))
    assertEquals(status, PublishedRunComparisonMain.ExitCompared, log)
    val text = read(out)
    val (runs, counts, _, ds) = parseLikeBuild(text, nonce).fold(fail(_), identity)
    assertEquals(runs, Map("left" -> s"run=${hexOf(a)}", "right" -> s"run=${hexOf(c)}"))
    assertEquals(counts((("left", "edges"))), counts((("right", "edges"))))
    assertEquals(ds.size, 28)
    assert(ds.exists(!_._4), s"at least one dataset differs: $ds")
    // Every line other than the run, dataset-verdict and load lines is the same fact as for A/B.
    val lines = text.split("\n").toVector
    assertEquals(lines.count(_.startsWith("dataset\t")), 28)
    lines.filter(_.startsWith("dataset\t")).foreach { l =>
      val f = l.split("\t", -1)
      if (f(4) == "equal") assertEquals(f(2), f(3), l)
    }
    assert(log.contains(s"compared: 28 datasets, ${ds.count(!_._4)} different"), log)
  }

  test("argument refusals exit 2 before any Spark session; nothing is written") {
    val out = results.resolve("refused.txt")
    def refused(args: Seq[String], fragment: String): Unit = {
      val (status, log) = compare(args, allowSession = false)
      assertEquals(status, PublishedRunComparisonMain.ExitArguments, log)
      assert(log.contains(fragment), s"'$fragment' not in:\n$log")
      assert(!Files.exists(out, LinkOption.NOFOLLOW_LINKS), "no result file")
    }
    val ok = argv(rootA, rootB, out)
    refused(ok.dropRight(2), "expected exactly 8 arguments")
    refused(Vector.empty, "expected exactly 8 arguments")
    refused(ok.updated(6, "--output"), "unknown flag '--output'")
    refused(ok.updated(6, PublishedRunComparisonMain.NonceFlag), "--nonce is given 2 times")
    refused(ok.updated(6, PublishedRunComparisonMain.NonceFlag), "--result is required")
    refused(ok.updated(5, nonce.toUpperCase), "--nonce is not 64 lower-case hex")
    refused(ok.updated(5, nonce.drop(1)), "--nonce is not 64 lower-case hex")
    refused(ok.updated(1, "relative/out"), "--left-output-root 'relative/out' is not absolute")
    refused(ok.updated(3, rootB.toString + "/"), "is not normalized")
    refused(ok.updated(3, rootB.toString + "/../out-b"), "is not normalized")
    refused(argv(rootA, rootA, out), "are the same directory")
    refused(argv(rootA, rootA.resolve("run=x"), out), "are nested")
    refused(argv(rootA, rootB, rootA.resolve("r.txt")), "--result lies inside an output root")
    refused(
      argv(rootA, rootB, tmp.resolve("absent-dir").resolve("r.txt")),
      "is missing or reached through a symbolic link"
    )
    val existing = Files.writeString(results.resolve("existing.txt"), "keep\n")
    val (status, log) = compare(argv(rootA, rootB, existing), allowSession = false)
    assertEquals(status, PublishedRunComparisonMain.ExitArguments, log)
    assert(log.contains("already exists"), log)
    assertEquals(read(existing), "keep\n")
  }

  test("a root that is missing, holds two runs or lacks its load directory: exit 1, no Spark") {
    def impossible(left: Path, right: Path, name: String, fragment: String): Unit = {
      val out = results.resolve(name)
      val (status, log) = compare(argv(left, right, out), allowSession = false)
      assertEquals(status, PublishedRunComparisonMain.ExitImpossible, log)
      assert(log.contains(fragment), s"'$fragment' not in:\n$log")
      assert(log.contains("no result file was written"), log)
      assert(!Files.exists(out, LinkOption.NOFOLLOW_LINKS), "no result file")
    }
    impossible(tmp.resolve("no-such-root"), rootB, "missing.txt", "is not a real directory")
    val two = copyTree(rootA, tmp.resolve("two-runs"))
    copyTree(rootC.resolve(s"run=${hexOf(c)}"), two.resolve(s"run=${hexOf(c)}"))
    impossible(rootA, two, "two.txt", "holds 2 run=<64 hex> directories, expected exactly 1")
    val noLoad = copyTree(
      rootA.resolve(s"run=${hexOf(a)}"),
      tmp.resolve("no-load").resolve(s"run=${hexOf(a)}")
    )
    impossible(
      rootA,
      noLoad.getParent,
      "no-load.txt",
      s"holds no ${LoadManifests.DirPrefix}${hexOf(a)}"
    )
    val extra = copyTree(rootA, tmp.resolve("extra"))
    Files.createDirectory(extra.resolve(".spark-left-over"))
    impossible(rootA, extra, "extra.txt", "holds entries other than")
  }

  test("verification refusals (tampered part, _UNVERIFIED, no _SUCCESS): exit 1, no result") {
    def impossible(right: Path, name: String, fragment: String): Unit = {
      val out = results.resolve(name)
      val (status, log) = compare(argv(rootA, right, out))
      assertEquals(status, PublishedRunComparisonMain.ExitImpossible, log)
      assert(log.contains(fragment), s"'$fragment' not in:\n$log")
      assert(log.contains("right run "), log)
      assert(!Files.exists(out, LinkOption.NOFOLLOW_LINKS), "no result file")
    }
    val run = s"run=${hexOf(b)}"
    // One byte of one Parquet part changed: the part no longer verifies against _parts.json.
    val tampered = copyTree(rootB, tmp.resolve("tampered"))
    val part = tampered.resolve(run).resolve(AnalysisRun.EdgesDataset).resolve("part-00000.parquet")
    val bytes = Files.readAllBytes(part)
    bytes(bytes.length / 2) = (bytes(bytes.length / 2) ^ 0x01).toByte
    Files.write(part, bytes)
    impossible(tampered, "tampered.txt", "could not compare: right run ")
    val unverified = copyTree(rootB, tmp.resolve("unverified"))
    Files.createFile(unverified.resolve(run).resolve("_UNVERIFIED"))
    impossible(unverified, "unverified.txt", "_UNVERIFIED")
    val noSuccess = copyTree(rootB, tmp.resolve("no-success"))
    Files.delete(noSuccess.resolve(run).resolve(PartManifest.SuccessMarker))
    impossible(noSuccess, "no-success.txt", "_SUCCESS")
  }
}
