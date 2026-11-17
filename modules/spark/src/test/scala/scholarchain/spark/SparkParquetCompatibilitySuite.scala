package scholarchain.spark

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path => JPath}
import java.security.MessageDigest
import java.util.Comparator

import scala.collection.immutable.SortedMap
import scala.jdk.CollectionConverters.*
import scala.util.Using

import org.apache.hadoop.fs.{Path => HPath}
import org.apache.parquet.hadoop.ParquetFileReader
import org.apache.parquet.hadoop.util.HadoopInputFile
import org.apache.spark.sql.{Encoders, Row, SaveMode, SparkSession}
import org.apache.spark.sql.types.{IntegerType, LongType, StringType, StructField, StructType}

import scholarchain.core.*

/**
 * SP-P6-02 step 0: local Spark and Parquet compatibility of the sparkAnalytics test classpath
 * (Scala 3 calling the Spark `_2.13` artifacts) with the core contracts. Synthetic data only, one
 * local[1] SparkSession bound to the loopback address, every write under one fresh directory in
 * `java.io.tmpdir`, deleted in `afterAll`. Loopback listeners only; no outbound network. No AWS, no
 * graph job.
 *
 * Driver-memory note: the `collect` calls below act on at most five synthetic rows; production jobs
 * never collect graph data to the driver.
 */
class SparkParquetCompatibilitySuite extends munit.FunSuite {

  private var tmp: JPath = null
  private var spark: SparkSession = null

  override def beforeAll(): Unit = {
    tmp = Files.createTempDirectory("scholarchain-spark-compat-")
    Files.createDirectories(tmp.resolve("local"))
    spark = SparkSession
      .builder()
      .master("local[1]")
      .appName("scholarchain-spark-compat")
      .config("spark.ui.enabled", "false")
      .config("spark.sql.shuffle.partitions", "1")
      .config("spark.default.parallelism", "1")
      .config("spark.sql.warehouse.dir", tmp.resolve("warehouse").toUri.toString)
      .config("spark.local.dir", tmp.resolve("local").toString)
      .config("spark.hadoop.hadoop.tmp.dir", tmp.resolve("hadoop").toString)
      .config("spark.driver.host", "127.0.0.1")
      .config("spark.driver.bindAddress", "127.0.0.1")
      .config("spark.sql.session.timeZone", "UTC")
      .getOrCreate()
    spark.sparkContext.setLogLevel("WARN")
  }

  override def afterAll(): Unit =
    try if (spark != null) spark.stop()
    finally if (tmp != null) deleteTree(tmp)

  // ------------------------------------------------------------------ helpers

  private def deleteTree(root: JPath): Unit =
    if (Files.exists(root))
      Using.resource(Files.walk(root)) { s =>
        s.sorted(Comparator.reverseOrder[JPath]()).iterator().asScala.foreach { p =>
          Files.deleteIfExists(p)
          ()
        }
      }

  /** A valid synthetic run manifest; `attempt` makes several distinct ones. */
  private def runManifest(attempt: Int): RunManifest =
    RunManifest(
      SchemaVersions.RunManifest,
      "spark.compat-test",
      "0" * 40,
      Canonical.sha256HexUtf8("scholarchain spark parquet compatibility test"),
      Vector(SnapshotRef(SourceType.Dblp, "dblp-synthetic-1")),
      SortedMap("probe" -> "spark-parquet"),
      attempt
    )

  private def orFail[T](e: Either[Vector[ValidationError], T]): T =
    e.fold(errors => fail(s"unexpected validation errors: $errors"), identity)

  /** Independent SHA-256 (JDK), not the core observer under test. */
  private def sha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString

  private def jarOf(c: Class[?]): String =
    Option(c.getProtectionDomain.getCodeSource)
      .map(cs => new java.io.File(cs.getLocation.toURI).getName)
      .getOrElse("unknown")

  /** Row count (sum of row-group counts) and created-by string from the Parquet footer. */
  private def footer(file: JPath): (Long, String) = {
    val in = HadoopInputFile.fromPath(new HPath(file.toUri), spark.sparkContext.hadoopConfiguration)
    val reader = ParquetFileReader.open(in)
    try (reader.getRecordCount, reader.getFooter.getFileMetaData.getCreatedBy)
    finally reader.close()
  }

  /** Every regular file under `dir`, observed from its bytes, with `/`-separated relative paths. */
  private def observe(dir: JPath): Vector[ObservedFile] =
    Using.resource(Files.walk(dir)) { s =>
      s.iterator()
        .asScala
        .filter(p => Files.isRegularFile(p))
        .map(p =>
          ObservedFile.of(dir.relativize(p).iterator().asScala.mkString("/"), Files.readAllBytes(p))
        )
        .toVector
        .sortBy(_.path)
    }

  private val parquetSchema = StructType(
    Seq(
      StructField("researcherKey", StringType, nullable = false),
      StructField("year", IntegerType, nullable = false),
      StructField("jointPublicationCount", LongType, nullable = false)
    )
  )

  private val parquetRows: Vector[(String, Int, Long)] = Vector(
    ("r-c", 2021, 1L),
    ("r-a", 2019, 3L),
    ("r-b", 2020, 2L)
  )

  // ------------------------------------------------------------------ tests

  test("runtime versions observed in the forked test JVM") {
    val sparkVersion = spark.version
    val scalaLibrary = scala.util.Properties.versionNumberString
    val parquetVersion = org.apache.parquet.Version.FULL_VERSION
    val hadoopVersion = org.apache.hadoop.util.VersionInfo.getVersion
    println(
      "SP-P6-02 runtime: " +
        s"spark.version=$sparkVersion; " +
        s"scala.util.Properties.versionString=${scala.util.Properties.versionString}; " +
        s"scala3-library jar=${jarOf(classOf[scala.CanEqual[?, ?]])}; " +
        s"spark-sql jar=${jarOf(classOf[SparkSession])}; " +
        s"parquet Version.FULL_VERSION=$parquetVersion; " +
        s"parquet-hadoop jar=${jarOf(classOf[ParquetFileReader])}; " +
        s"hadoop VersionInfo=$hadoopVersion; " +
        s"java.version=${System.getProperty("java.version")}; " +
        s"master=${spark.sparkContext.master}"
    )
    assert(sparkVersion.nonEmpty)
    // Spark is used through its _2.13 artifacts, so the runtime standard library is 2.13.x.
    assert(scalaLibrary.startsWith("2.13."), scalaLibrary)
    assertEquals(spark.sparkContext.master, "local[1]")
    assertEquals(spark.conf.get("spark.sql.shuffle.partitions"), "1")
  }

  test("uPickle JSON of core records round-trips through a Dataset[String]") {
    val manifests = Vector(1, 2, 3).map(runManifest)
    val manifestLines = manifests.map(m => orFail(Json.encode(m)))
    val windows = Vector(TimeWindow(2019, 2021), TimeWindow(2022, 2022))
    val windowLines = windows.map(w => upickle.default.write(w))
    val all = manifestLines ++ windowLines

    val ds = spark.createDataset(all.asJava)(using Encoders.STRING)
    assertEquals(ds.count(), all.size.toLong)
    // orderBy adds a shuffle (one partition): the strings cross a real Spark exchange.
    val back = ds.orderBy("value").collect().toVector
    assertEquals(back, all.sortWith((a, b) => Canonical.compareUtf8(a, b) < 0))

    val decodedManifests =
      back.filter(_.contains("\"schemaVersion\"")).map(s => orFail(Json.decode[RunManifest](s)))
    assertEquals(decodedManifests.toSet, manifests.toSet)
    assertEquals(decodedManifests.size, manifests.size)
    val decodedWindows =
      back.filterNot(_.contains("\"schemaVersion\"")).map(upickle.default.read[TimeWindow](_))
    assertEquals(decodedWindows.toSet, windows.toSet)
    assertEquals(decodedWindows.size, windows.size)
    // Each decoded manifest derives the same run ID as its original.
    assertEquals(
      decodedManifests.map(RunId.of).toSet,
      manifests.map(RunId.of).toSet
    )
    assertEquals(manifests.map(RunId.of).distinct.size, 3)
  }

  test("Parquet write and read, footer row count, part-manifest v2 verification") {
    val out = tmp.resolve("parquet-out")
    val rows = parquetRows.map { case (k, y, n) => Row(k, y, n) }
    spark
      .createDataFrame(rows.asJava, parquetSchema)
      .coalesce(1)
      .write
      .mode(SaveMode.ErrorIfExists)
      .parquet(out.toUri.toString)

    val parts = Using.resource(Files.list(out)) { s =>
      s.iterator()
        .asScala
        .filter { p =>
          val n = p.getFileName.toString
          n.startsWith("part-") && n.endsWith(".parquet")
        }
        .toVector
    }
    assertEquals(parts.size, 1, s"one Parquet part file expected, found $parts")
    val part = parts.head

    val readBack = spark.read
      .schema(parquetSchema)
      .parquet(out.toUri.toString)
      .orderBy("researcherKey")
      .collect()
      .toVector
      .map(r => (r.getString(0), r.getInt(1), r.getLong(2)))
    assertEquals(readBack, parquetRows.sortBy(_._1))

    val (footerRows, createdBy) = footer(part)
    println(s"SP-P6-02 parquet footer: rowCount=$footerRows; createdBy=$createdBy")
    assertEquals(footerRows, parquetRows.size.toLong)

    // A published run directory: the Parquet part, the run manifest, the part manifest, _SUCCESS.
    val runDir = tmp.resolve("run")
    val dataPath = "synthetic/part-00000.parquet"
    Files.createDirectories(runDir.resolve("synthetic"))
    val partBytes = Files.readAllBytes(part)
    Files.write(runDir.resolve(dataPath), partBytes)
    val rm = runManifest(1)
    val runId = RunId.of(rm).fold(e => fail(s"run ID: $e"), identity)
    val rmBytes = (orFail(Json.encode(rm)) + "\n").getBytes(StandardCharsets.UTF_8)
    Files.write(runDir.resolve(PartManifest.RunManifestFile), rmBytes)

    // The rows of this synthetic file are not records of any ScholarChain schema; the manifest
    // contract requires a current record schema label, so one is used here as a placeholder.
    val parquetEntry = PartFileV2(
      dataPath,
      SchemaVersions.ResearcherMetricsV2,
      PartFormat.Parquet,
      footerRows,
      RowCountSource.ParquetFooter,
      partBytes.length.toLong,
      sha256(partBytes)
    )
    val rmEntry = PartFileV2(
      PartManifest.RunManifestFile,
      SchemaVersions.RunManifest,
      PartFormat.Jsonl,
      1L,
      RowCountSource.LfCount,
      rmBytes.length.toLong,
      sha256(rmBytes)
    )
    val manifest = orFail(PartManifestV2.build(runId, Vector(parquetEntry, rmEntry)))
    val manifestJson = Json.write(manifest)
    Files.write(
      runDir.resolve(PartManifest.FileName),
      manifestJson.getBytes(StandardCharsets.UTF_8)
    )
    Files.write(runDir.resolve(PartManifest.SuccessMarker), Array.emptyByteArray)

    val observed = observe(runDir)
    assertEquals(
      observed.map(_.path),
      Vector(
        PartManifest.FileName,
        PartManifest.SuccessMarker,
        PartManifest.RunManifestFile,
        dataPath
      ).sorted
    )
    assertEquals(PartManifestVerifier.verifyV2(manifest, runId, observed), Vector.empty)
    assertEquals(PartManifestV2.checkRunManifest(manifest, rm), Vector.empty)
    assertEquals(PartManifests.decode(manifestJson), Right(Right(manifest)))
    assertEquals(PartManifests.verify(Right(manifest), runId, observed), Vector.empty)

    // The JSONL newline rule is not applied to the binary file.
    val obsPart = observed.find(_.path == dataPath).getOrElse(fail("part not observed"))
    val magic = "PAR1".getBytes(StandardCharsets.US_ASCII)
    assert(partBytes.take(4).sameElements(magic), "Parquet header magic")
    assert(partBytes.takeRight(4).sameElements(magic), "Parquet footer magic")
    assert(!obsPart.endsWithLf, "a Parquet file ends in PAR1, not LF")
    assert(partBytes.length.toLong >= PartManifestV2.ParquetMinimumBytes)

    // Counter-test: the same bytes declared as jsonl fail the newline rule.
    val asJsonl = parquetEntry.copy(
      format = PartFormat.Jsonl,
      rowCountSource = RowCountSource.LfCount,
      rowCount = math.max(obsPart.rowCount, 1L)
    )
    val jsonlManifest = orFail(PartManifestV2.build(runId, Vector(asJsonl, rmEntry)))
    val jsonlErrors = PartManifestVerifier.verifyV2(jsonlManifest, runId, observed)
    assert(
      jsonlErrors.exists(_.toString.contains("a non-empty file must end in LF")),
      jsonlErrors
    )

    // Counter-test: a truncated copy of the Parquet file is refused by byte count and SHA-256.
    val truncated = observed.map(o =>
      if (o.path == dataPath) ObservedFile.of(dataPath, partBytes.dropRight(1)) else o
    )
    val truncatedErrors = PartManifestVerifier.verifyV2(manifest, runId, truncated).map(_.toString)
    assert(truncatedErrors.exists(_.contains("byteCount: listed")), truncatedErrors)
    assert(truncatedErrors.exists(_.contains("sha256: listed")), truncatedErrors)
  }
}
