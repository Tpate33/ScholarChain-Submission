package scholarchain.spark.output

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path}

import scala.collection.immutable.SortedMap
import scala.jdk.CollectionConverters.*

import org.apache.spark.sql.SparkSession

import scholarchain.core.*
import scholarchain.spark.input.{CheckStep, ReaderLimits, VerifiedArchiveReader}
import scholarchain.spark.store.{HadoopRunStore, NioRunStore, RunStore, S3SimFileSystem, StorePath}
import scholarchain.spark.testkit.{LocalSpark, RunFixtures}

/**
 * The commit-marker publication protocol (SP-P6-04, [[RunPublisher]] protocol B) through the Hadoop
 * FileSystem: on the local file system through `file:` URIs, and on `s3sim`, a test file system
 * with S3A's rename semantics (copy then delete, not atomic) that refuses every rename outside a
 * Spark scratch prefix ([[S3SimFileSystem]]), so a publication that renamed anything under `run=`
 * would fail. Crash points (a non-catchable throw at a fixed step, so nothing is cleaned or
 * marked), caught failures before and after `_SUCCESS`, a row-count refusal before anything is
 * written, and an existing destination. Every partial run must be refused by both readers
 * ([[VerifiedArchiveReader.layout]] and [[RunPublisher.verifyPublished]]).
 */
class CommitPublisherSuite extends munit.FunSuite {

  private val meta = 1L << 16
  private val limits = ReaderLimits(1000, 6, 10000, 1L << 20, meta)

  private var tmp: Path = null
  private var spark: SparkSession = null
  private var store: RunStore = null

  override def beforeAll(): Unit = {
    tmp = Files.createTempDirectory("scholarchain-commit-publisher-")
    spark = LocalSpark.start("scholarchain-commit-publisher-test", tmp.resolve("spark"))
    S3SimFileSystem.register(spark.sparkContext.hadoopConfiguration)
    store = new HadoopRunStore(spark.sparkContext.hadoopConfiguration)
  }

  override def afterAll(): Unit =
    try if (spark != null) spark.stop()
    finally LocalSpark.deleteTree(tmp)

  private val lineage = AnalysisLineage(
    "run:" + "1" * 64,
    "run:" + "2" * 64,
    "run:" + "3" * 64,
    "0123456789abcdef0123456789abcdef01234567",
    "4" * 64,
    "er-grouping.v1",
    "er-policy.v8",
    "v1",
    GroupingEvaluation.Unevaluated
  )

  private val manifest = RunManifest(
    SchemaVersions.RunManifest,
    "spark.commit-publisher-test",
    "0123456789abcdef0123456789abcdef01234567",
    "5" * 64,
    RunFixtures.snapshots,
    SortedMap("probe" -> "commit-publisher"),
    1
  )
  private val runId = RunFixtures.ok(RunId.of(manifest))
  private val hex = runId.stripPrefix("run:")

  private def edge: CoauthorEdgeV2 = RunFixtures.ok(
    CoauthorEdgeV2.build(
      RunFixtures.rB,
      RunFixtures.rA,
      RunFixtures.window1,
      Vector(YearCount(2018, 1), YearCount(2019, 1)),
      Vector(RunFixtures.w2, RunFixtures.w1),
      lineage,
      "sc-graph.v1",
      id =>
        Provenance(
          SourceType.Derived,
          id,
          RunFixtures.snapshot,
          runId,
          SchemaVersions.PublicationTeam,
          Some("sc-graph.v1"),
          None,
          None
        )
    )
  )

  private def edges(expected: Long = 1) = Vector(
    ParquetDataset(
      "edges",
      SchemaVersions.CoauthorEdgeV2,
      spark.createDataFrame(Vector(CoauthorEdgeRows.toRow(edge)).asJava, CoauthorEdgeRows.schema),
      expected
    )
  )

  /** One Neptune CSV dataset holding its header only (a valid empty vertex file). */
  private def emptyVertices: Vector[CsvDataset] = {
    val (_, name, schema, header) = NeptuneCsvParts.vertexDatasets.head
    Vector(
      CsvDataset(
        name,
        schema,
        header,
        spark.sparkContext.parallelize(Vector((0L, header + "\n")), 1),
        0L
      )
    )
  }

  private def localRoot(name: String): (Path, StorePath) = {
    val p = Files.createDirectories(tmp.resolve(name))
    (p, StorePath.parse(p.toUri.toString).fold(e => fail(e), identity))
  }

  private def s3Root(name: String): (Path, StorePath) = {
    val p = Files.createDirectories(tmp.resolve(name))
    (p, S3SimFileSystem.locationOf(p))
  }

  private def publish(
      root: StorePath,
      hooks: CommitHooks = CommitHooks.Noop,
      datasets: Vector[ParquetDataset] = edges(),
      csv: Vector[CsvDataset] = Vector.empty
  ) =
    RunPublisher.publishCommitted(spark, store, root, manifest, datasets, csv, meta, hooks)

  private def names(dir: Path): Vector[String] =
    if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) Vector.empty
    else Files.list(dir).iterator().asScala.map(_.getFileName.toString).toVector.sorted

  private def exists(p: Path) = Files.exists(p, LinkOption.NOFOLLOW_LINKS)

  /** Both readers refuse the run at `dir`: the layout steps they report, and verifyPublished. */
  private def refusedByReaders(dir: StorePath): Set[CheckStep] = {
    assert(RunPublisher.verifyPublished(spark, store, dir, runId, meta).isLeft)
    val steps = VerifiedArchiveReader.layout(store, "analysis", dir, limits).map(_.step).toSet
    assert(steps.nonEmpty, s"the layout check accepted $dir")
    steps
  }

  /**
   * A simulated process death at a hook: a ControlThrowable is not NonFatal, so the publisher's
   * failure handling neither catches nor marks it (only its `finally` scratch cleanup runs). Not an
   * InterruptedException, which would also disturb the test thread and Spark.
   */
  private class Crash extends scala.util.control.ControlThrowable("simulated crash")

  /** Publishes with `hooks` and requires the simulated crash (munit's intercept is NonFatal). */
  private def crashed(root: StorePath, hooks: CommitHooks): Unit =
    try {
      val r = publish(root, hooks)
      fail(s"the publication did not reach the crash point: $r")
    } catch { case _: Crash => () }

  test("file: URIs through Hadoop: published, re-verified by both stores, read back unchanged") {
    val (out, root) = localRoot("out-file")
    val p = publish(root, csv = emptyVertices).fold(e => fail(e.mkString("\n")), identity)
    val csvName = NeptuneCsvParts.vertexDatasets.head._2
    assertEquals(
      p.partManifest.files.map(_.path),
      Vector("_run-manifest.json", "edges/part-00000.parquet", s"$csvName/part-00000.csv").sorted
    )
    assertEquals(p.store, store)
    assertEquals(names(out), Vector(s"run=$hex"), "only the run: the scratch prefix is removed")
    val dir = out.resolve(s"run=$hex")
    // The raw file system writes no checksum sidecar; _SUCCESS was written (empty).
    assertEquals(
      names(dir),
      Vector("_SUCCESS", "_parts.json", "_run-manifest.json", csvName, "edges").sorted
    )
    assertEquals(Files.size(dir.resolve("_SUCCESS")), 0L)
    // The local java.nio reader and verifier accept the run published through Hadoop.
    assert(RunPublisher.verifyPublished(spark, dir, runId, meta).isRight)
    assertEquals(VerifiedArchiveReader.layout("analysis", dir, limits), Vector.empty)
    assert(
      RunPublisher.verifyPublished(spark, NioRunStore, StorePath.local(dir), runId, meta).isRight
    )
    val back = spark.read
      .schema(CoauthorEdgeRows.schema)
      .parquet(p.partsOf(SchemaVersions.CoauthorEdgeV2).map(_.toUri.toString)*)
      .collect()
      .toVector
      .map(CoauthorEdgeRows.fromRow)
    assertEquals(back, Vector(Right(edge)))
  }

  test("S3 semantics (no atomic rename): published, and nothing outside the scratch is renamed") {
    val (out, root) = s3Root("out-s3sim")
    S3SimFileSystem.renames.clear()
    val p = publish(root, csv = emptyVertices).fold(e => fail(e.mkString("\n")), identity)
    assertEquals(p.dir, root.resolve(s"run=$hex"))
    assertEquals(p.dir.scheme, S3SimFileSystem.Scheme)
    val log = S3SimFileSystem.renameLog
    assert(log.nonEmpty, "Spark's commit protocol renamed inside the scratch prefix (FS in use)")
    assert(log.forall(_.contains(s"/.spark-$hex/")), log.mkString("\n"))
    assert(!log.exists(_.contains(s"/run=$hex")), log.mkString("\n"))
    assertEquals(names(out), Vector(s"run=$hex"))
    assert(RunPublisher.verifyPublished(spark, store, p.dir, runId, meta).isRight)
    assertEquals(VerifiedArchiveReader.layout(store, "analysis", p.dir, limits), Vector.empty)
  }

  test("crash after the data parts: no _parts.json, no _SUCCESS, refused, not re-publishable") {
    val (out, root) = s3Root("out-crash-parts")
    crashed(root, CommitHooks(afterDataParts = _ => throw new Crash))
    val dir = out.resolve(s"run=$hex")
    assertEquals(names(dir), Vector("edges"))
    assertEquals(names(out), Vector(s"run=$hex"), "the scratch prefix is removed")
    assert(refusedByReaders(root.resolve(s"run=$hex")).contains(CheckStep.SuccessMarker))
    val again = publish(root)
    assert(again.left.exists(_.exists(_.contains("already exists: never overwritten"))), again)
  }

  test("crash just before _SUCCESS: every byte verified, still refused without the marker") {
    val (out, root) = localRoot("out-crash-success")
    crashed(root, CommitHooks(beforeSuccess = _ => throw new Crash))
    val dir = out.resolve(s"run=$hex")
    assertEquals(names(dir), Vector("_parts.json", "_run-manifest.json", "edges"))
    val steps = refusedByReaders(root.resolve(s"run=$hex"))
    assertEquals(steps, Set(CheckStep.SuccessMarker))
    val v = RunPublisher.verifyPublished(spark, store, root.resolve(s"run=$hex"), runId, meta)
    assert(v.left.exists(_.exists(_.contains("no regular _SUCCESS"))), v)
    assert(publish(root).left.exists(_.exists(_.contains("never overwritten"))))
  }

  test("a caught failure before _SUCCESS marks the partial run _UNVERIFIED; readers refuse it") {
    val (out, root) = s3Root("out-fail-before")
    val r = publish(root, CommitHooks(beforePartManifest = _ => throw new RuntimeException("boom")))
    assert(r.left.exists(_.exists(_.contains("failure while publishing"))), r)
    assert(r.left.exists(_.exists(_.contains("has no _SUCCESS, and it is marked _UNVERIFIED"))), r)
    val dir = out.resolve(s"run=$hex")
    assertEquals(names(dir), Vector("_UNVERIFIED", "_run-manifest.json", "edges"))
    val steps = refusedByReaders(root.resolve(s"run=$hex"))
    assertEquals(steps, Set(CheckStep.UnverifiedMarker, CheckStep.SuccessMarker))
    assertEquals(names(out), Vector(s"run=$hex"))
  }

  test("pre-commit verification: a part changed after hashing is refused before _SUCCESS") {
    val (out, root) = localRoot("out-precommit")
    val r = publish(
      root,
      CommitHooks(beforePartManifest = d => {
        val part = d.resolve("edges/part-00000.parquet").localPath
        val b = Files.readAllBytes(part)
        b(b.length / 2) = (b(b.length / 2) ^ 1).toByte
        Files.write(part, b)
        ()
      })
    )
    assert(r.left.exists(_.exists(_.contains("pre-commit verification failed"))), r)
    assert(r.left.exists(_.exists(_.contains("sha256"))), r)
    val dir = out.resolve(s"run=$hex")
    assert(!exists(dir.resolve("_SUCCESS")))
    assert(exists(dir.resolve("_UNVERIFIED")))
    assert(refusedByReaders(root.resolve(s"run=$hex")).contains(CheckStep.UnverifiedMarker))
  }

  test("post-commit verification failure: _UNVERIFIED beside _SUCCESS (never deleted); refused") {
    val (out, root) = s3Root("out-post")
    val r = publish(
      root,
      CommitHooks(afterSuccess = d => {
        assertEquals(d, root.resolve(s"run=$hex"))
        val part = out.resolve(s"run=$hex/edges/part-00000.parquet")
        val b = Files.readAllBytes(part)
        b(b.length / 2) = (b(b.length / 2) ^ 1).toByte
        Files.write(part, b)
        ()
      })
    )
    assert(r.left.exists(_.exists(_.contains("post-publication verification failed"))), r)
    assert(r.left.exists(_.exists(_.contains("_SUCCESS is not deleted"))), r)
    val dir = out.resolve(s"run=$hex")
    assert(exists(dir.resolve("_SUCCESS")))
    assert(exists(dir.resolve("_UNVERIFIED")))
    assertEquals(refusedByReaders(root.resolve(s"run=$hex")), Set(CheckStep.UnverifiedMarker))
  }

  test("a footer row count other than expected is refused before anything is written to run=") {
    val (out, root) = s3Root("out-rows")
    val r = publish(root, datasets = edges(expected = 2))
    assert(r.left.exists(_.exists(_.contains("expected 2"))), r)
    assertEquals(names(out), Vector.empty, "no run and no scratch left")
    // Nothing was written under run=: the same run ID can be published afterwards.
    assert(publish(root).isRight)
  }

  test("an existing object under run= is refused before any write and left untouched") {
    val (out, root) = localRoot("out-exists")
    val dir = Files.createDirectories(out.resolve(s"run=$hex"))
    val foreign = "written by another process\n".getBytes(StandardCharsets.UTF_8)
    Files.write(dir.resolve("foreign.txt"), foreign)
    val r = publish(root)
    assert(r.left.exists(_.exists(_.contains("already exists: never overwritten"))), r)
    assertEquals(names(out), Vector(s"run=$hex"))
    assertEquals(names(dir), Vector("foreign.txt"))
    assertEquals(Files.readAllBytes(dir.resolve("foreign.txt")).toVector, foreign.toVector)
  }

  test("an output root that is a file is refused; an absent root is created by the first write") {
    val file = Files.write(tmp.resolve("root-is-a-file"), Array[Byte](1))
    val asFile = StorePath.parse(file.toUri.toString).fold(e => fail(e), identity)
    assert(publish(asFile).left.exists(_.exists(_.contains("is not a directory"))))
    val absent = S3SimFileSystem.locationOf(tmp.resolve("not-yet/out"))
    assert(publish(absent).isRight, "an S3 prefix exists only once an object is written")
  }
}
