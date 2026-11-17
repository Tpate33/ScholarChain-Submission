package scholarchain.spark.output

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path}
import java.nio.file.attribute.BasicFileAttributes

import scala.collection.immutable.SortedMap
import scala.jdk.CollectionConverters.*

import org.apache.spark.sql.{Row, SparkSession}
import org.apache.spark.sql.types.{IntegerType, LongType, StringType, StructField, StructType}

import scholarchain.core.*
import scholarchain.spark.input.{CheckStep, ReaderLimits, VerifiedArchiveReader}
import scholarchain.spark.testkit.{LocalSpark, RunFixtures}

/**
 * The Parquet layout and the staged publisher: the row layout mirrors the record's JSON keys, a
 * record survives a Parquet round trip, and a frame whose StructType is not the layout of its
 * record schema label is refused before anything is published (CR-P6-15 N1). The F4 tests
 * (CR-P6-19) drive each defined publication outcome through the [[PublishHooks]] seams: a failed
 * post-rename verification, a destination that exists, and a failed rename.
 */
class RunPublisherSuite extends munit.FunSuite {

  /** Metadata read bound of these tests (test configuration). */
  private val meta = 1L << 16

  private var tmp: Path = null
  private var spark: SparkSession = null

  override def beforeAll(): Unit = {
    tmp = Files.createTempDirectory("scholarchain-publisher-")
    spark = LocalSpark.start("scholarchain-publisher-test", tmp.resolve("spark"))
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
    "spark.publisher-test",
    "0123456789abcdef0123456789abcdef01234567",
    "5" * 64,
    RunFixtures.snapshots,
    SortedMap("probe" -> "publisher"),
    1
  )
  private val runId = RunFixtures.ok(RunId.of(manifest))

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

  private def nullable(
      t: org.apache.spark.sql.types.DataType
  ): org.apache.spark.sql.types.DataType =
    t match {
      case s: StructType =>
        StructType(s.fields.map(f => f.copy(dataType = nullable(f.dataType), nullable = true)))
      case a: org.apache.spark.sql.types.ArrayType =>
        org.apache.spark.sql.types.ArrayType(nullable(a.elementType), containsNull = true)
      case other => other
    }

  private def keysOf(v: ujson.Value): Vector[String] = v match {
    case o: ujson.Obj => o.value.keys.toVector
    case _ => Vector.empty
  }

  test("the edge row layout has the record's JSON keys, in order, at every level") {
    val json = ujson.read(Json.write(edge))
    assertEquals(CoauthorEdgeRows.schema.fieldNames.toVector, keysOf(json))
    assertEquals(CoauthorEdgeRows.WindowType.fieldNames.toVector, keysOf(json("window")))
    assertEquals(CoauthorEdgeRows.LineageType.fieldNames.toVector, keysOf(json("lineage")))
    assertEquals(CoauthorEdgeRows.ProvenanceType.fieldNames.toVector, keysOf(json("provenance")))
    assertEquals(
      CoauthorEdgeRows.YearCountType.fieldNames.toVector,
      keysOf(json("publicationsByYear")(0))
    )
  }

  test("an edge is published, re-verified and read back unchanged") {
    val out = Files.createDirectories(tmp.resolve("out-ok"))
    val frame =
      spark.createDataFrame(Vector(CoauthorEdgeRows.toRow(edge)).asJava, CoauthorEdgeRows.schema)
    val p = RunPublisher
      .publish(
        spark,
        out,
        manifest,
        Vector(ParquetDataset("edges", SchemaVersions.CoauthorEdgeV2, frame, 1)),
        meta
      )
      .fold(e => fail(e.mkString("\n")), identity)
    assertEquals(
      p.partManifest.files.map(_.path),
      Vector("_run-manifest.json", "edges/part-00000.parquet")
    )
    val f = p.partManifest.files.find(_.format == PartFormat.Parquet).get
    assertEquals((f.rowCount, f.rowCountSource), (1L, RowCountSource.ParquetFooter))
    val back = spark.read
      .schema(CoauthorEdgeRows.schema)
      .parquet(p.partsOf(SchemaVersions.CoauthorEdgeV2).head.toUri.toString)
      .collect()
      .toVector
      .map(CoauthorEdgeRows.fromRow)
    assertEquals(back, Vector(Right(edge)))
    // Only the run directory remains under the output root: no staging or scratch directory.
    val left = Files.list(out).iterator().asScala.map(_.getFileName.toString).toVector
    assertEquals(left, Vector(p.dir.getFileName.toString))
  }

  test("N1: a frame whose StructType is not its label's layout is refused and nothing is left") {
    val out = Files.createDirectories(tmp.resolve("out-n1"))
    val wrong = StructType(
      Seq(
        StructField("researcherKey", StringType),
        StructField("year", IntegerType),
        StructField("n", LongType)
      )
    )
    val frame = spark.createDataFrame(Vector(Row("r", 2020, 1L)).asJava, wrong)
    val r = RunPublisher.publish(
      spark,
      out,
      manifest,
      Vector(ParquetDataset("edges", SchemaVersions.CoauthorEdgeV2, frame, 1)),
      meta
    )
    assert(r.left.exists(_.exists(_.contains("is not the layout of"))), r)
    assertEquals(Files.list(out).iterator().asScala.toVector, Vector.empty)
  }

  test("N1: a record schema without a Parquet layout is refused") {
    assert(
      RecordSchemas.problem(SchemaVersions.ResearcherMetricsV2, CoauthorEdgeRows.schema).nonEmpty
    )
    assertEquals(
      RecordSchemas.problem(SchemaVersions.CoauthorEdgeV2, CoauthorEdgeRows.schema),
      None
    )
    // Nullability alone is not a layout difference (Spark writes file sources nullable).
    assertEquals(
      RecordSchemas.problem(
        SchemaVersions.CoauthorEdgeV2,
        nullable(CoauthorEdgeRows.schema).asInstanceOf[StructType]
      ),
      None
    )
  }

  test("a footer row count that differs from the expected rows is refused") {
    val out = Files.createDirectories(tmp.resolve("out-rows"))
    val frame =
      spark.createDataFrame(Vector(CoauthorEdgeRows.toRow(edge)).asJava, CoauthorEdgeRows.schema)
    val r = RunPublisher.publish(
      spark,
      out,
      manifest,
      Vector(ParquetDataset("edges", SchemaVersions.CoauthorEdgeV2, frame, 2)),
      meta
    )
    assert(r.left.exists(_.exists(_.contains("expected 2"))), r)
    assertEquals(Files.list(out).iterator().asScala.toVector, Vector.empty)
  }

  // ---------------------------------------------------------------- F4 publication outcomes

  private val hex = runId.stripPrefix("run:")

  private def edgeFrame =
    spark.createDataFrame(Vector(CoauthorEdgeRows.toRow(edge)).asJava, CoauthorEdgeRows.schema)

  private def publishWith(out: Path, hooks: PublishHooks) =
    RunPublisher.publishWith(
      spark,
      out,
      manifest,
      Vector(ParquetDataset("edges", SchemaVersions.CoauthorEdgeV2, edgeFrame, 1)),
      meta,
      hooks
    )

  private def namesIn(dir: Path): Vector[String] =
    Files.list(dir).iterator().asScala.map(_.getFileName.toString).toVector.sorted

  private def fileKey(p: Path): AnyRef =
    Files.readAttributes(p, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS).fileKey()

  test(
    "F4(a): a run that fails post-rename verification is marked _UNVERIFIED and loses _SUCCESS"
  ) {
    val out = Files.createDirectories(tmp.resolve("out-f4a"))
    val r = publishWith(
      out,
      PublishHooks(afterMove = dir => {
        val part = dir.resolve("edges/part-00000.parquet")
        val b = Files.readAllBytes(part)
        b(b.length / 2) = (b(b.length / 2) ^ 1).toByte
        Files.write(part, b)
        ()
      })
    )
    assert(r.left.exists(_.exists(_.contains("post-publication verification failed"))), r)
    val dir = out.resolve(s"run=$hex")
    assert(Files.isRegularFile(dir.resolve("_UNVERIFIED"), LinkOption.NOFOLLOW_LINKS))
    assert(!Files.exists(dir.resolve("_SUCCESS"), LinkOption.NOFOLLOW_LINKS))
    // Neither consumer accepts it as complete.
    val limits = ReaderLimits(1000, 6, 10000, 1L << 20, meta)
    val steps = VerifiedArchiveReader.layout("analysis", dir, limits).map(_.step).toSet
    assert(steps.contains(CheckStep.UnverifiedMarker), steps)
    assert(steps.contains(CheckStep.SuccessMarker), steps)
    assert(RunPublisher.verifyPublished(spark, dir, runId, meta).isLeft)
  }

  test("F4(c): an empty destination created after the pre-checks is never replaced") {
    val out = Files.createDirectories(tmp.resolve("out-f4c"))
    var key: AnyRef = null
    val r = publishWith(
      out,
      PublishHooks(beforeClaim = dir => {
        Files.createDirectory(dir)
        key = fileKey(dir)
      })
    )
    assert(r.left.exists(_.exists(_.contains("never overwritten"))), r)
    val dir = out.resolve(s"run=$hex")
    assertEquals(fileKey(dir), key, "the existing directory was replaced")
    assertEquals(namesIn(dir), Vector.empty)
    assertEquals(namesIn(out), Vector(s"run=$hex"), "staging and scratch are removed")
  }

  test("F4(c): an existing empty destination is refused before staging and left as it was") {
    val out = Files.createDirectories(tmp.resolve("out-f4c-pre"))
    val dir = Files.createDirectory(out.resolve(s"run=$hex"))
    val key = fileKey(dir)
    val r = publishWith(out, PublishHooks.Noop)
    assert(r.left.exists(_.exists(_.contains("never overwritten"))), r)
    assertEquals(fileKey(dir), key)
    assertEquals(namesIn(dir), Vector.empty)
    assertEquals(namesIn(out), Vector(s"run=$hex"))
  }

  test("F4(b): a failed rename removes staging and keeps foreign content of the claim untouched") {
    val out = Files.createDirectories(tmp.resolve("out-f4b"))
    val foreign = "written by another process\n".getBytes(StandardCharsets.UTF_8)
    val r = publishWith(
      out,
      PublishHooks(beforeMove = dir => {
        Files.write(dir.resolve("foreign.txt"), foreign)
        ()
      })
    )
    assert(r.left.exists(_.exists(_.contains("the rename of"))), r)
    assert(r.left.exists(_.exists(_.contains("kept untouched"))), r)
    val dir = out.resolve(s"run=$hex")
    assertEquals(namesIn(out), Vector(s"run=$hex"), "staging and scratch are removed")
    assertEquals(namesIn(dir), Vector("foreign.txt"))
    assertEquals(Files.readAllBytes(dir.resolve("foreign.txt")).toVector, foreign.toVector)
  }

  test("F4(b): a failed rename with an empty claim removes the staging directory and the claim") {
    val out = Files.createDirectories(tmp.resolve("out-f4b-empty"))
    val aside = tmp.resolve("out-f4b-empty-aside")
    val r = publishWith(
      out,
      PublishHooks(beforeMove = dir => {
        // The source disappears, so the rename fails while the claim is still empty.
        Files.move(dir.getParent.resolve(s".staging-$hex"), aside)
        ()
      })
    )
    assert(r.left.exists(_.exists(_.contains("the empty claim was removed"))), r)
    assertEquals(namesIn(out), Vector.empty)
    LocalSpark.deleteTree(aside)
  }

  test("F3: verifyPublished reads _parts.json and _run-manifest.json with a byte bound") {
    val out = Files.createDirectories(tmp.resolve("out-f3"))
    val p = publishWith(out, PublishHooks.Noop).fold(e => fail(e.mkString("\n")), identity)
    val partsBytes = Files.size(p.dir.resolve("_parts.json"))
    val rmBytes = Files.size(p.dir.resolve("_run-manifest.json"))
    assert(rmBytes < partsBytes, s"fixture: run manifest $rmBytes, parts $partsBytes")
    val partsBound = RunPublisher.verifyPublished(spark, p.dir, runId, partsBytes - 1)
    assert(partsBound.left.exists(_.exists(_.contains("_parts.json' has"))), partsBound)
    // A bound of exactly the _parts.json size admits both files.
    assert(RunPublisher.verifyPublished(spark, p.dir, runId, partsBytes).isRight)
  }
}
