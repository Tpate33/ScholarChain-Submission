package scholarchain.spark.testkit

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path, Paths}

import scala.jdk.CollectionConverters.*
import scala.util.Using

import org.apache.hadoop.fs.{Path => HPath}
import org.apache.parquet.hadoop.ParquetFileReader
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.apache.parquet.hadoop.util.HadoopInputFile
import org.apache.spark.sql.{SaveMode, SparkSession}
import org.apache.spark.sql.functions.{broadcast, col, count, lit}

/**
 * SP-P7-01 (human decision 2026-11-20, DU-P7-04): every test SparkSession compresses with Snappy,
 * and shuffle, spill and broadcast compression stay on ([[LocalSpark.TestCompression]]).
 *
 *   1. The settings are exactly the eight expected values, and the started session carries them.
 *   2. Each key exists in the running Spark's own config registry (no guessed key name); the
 *      defaults they replace are printed.
 *   3. A shuffle, a broadcast hash join and a Parquet write and read succeed; every shuffle data
 *      file starts with the Snappy stream header, and every Parquet column chunk is SNAPPY.
 *   4. A source scan of `modules/spark/src`: no ORC, LZ4_RAW, LZO or Zstandard codec use anywhere,
 *      and test sessions are built only by [[LocalSpark]] (other codec words are printed).
 *
 * One `local[1]` session from [[LocalSpark]] under a fresh directory in `java.io.tmpdir`, deleted
 * in `afterAll`; loopback only, no network, no AWS. Driver memory: at most ten result rows and the
 * first eight bytes of each shuffle data file.
 */
class TestCompressionSuite extends munit.FunSuite {

  private var tmp: Path = null
  private var spark: SparkSession = null

  override def beforeAll(): Unit = {
    tmp = Files.createTempDirectory("scholarchain-spark-codec-")
    spark = LocalSpark.start("scholarchain-test-compression", tmp)
  }

  override def afterAll(): Unit =
    try if (spark != null) spark.stop()
    finally LocalSpark.deleteTree(tmp)

  private val Expected: Vector[(String, String)] = Vector(
    "spark.io.compression.codec" -> "snappy",
    "spark.shuffle.mapStatus.compression.codec" -> "snappy",
    "spark.eventLog.compression.codec" -> "snappy",
    "spark.sql.streaming.stateStore.compression.codec" -> "snappy",
    "spark.sql.parquet.compression.codec" -> "snappy",
    "spark.shuffle.compress" -> "true",
    "spark.shuffle.spill.compress" -> "true",
    "spark.broadcast.compress" -> "true"
  )

  /** xerial SnappyOutputStream stream header (SnappyCodec.MAGIC_HEADER): 0x82 "SNAPPY" 0x00. */
  private val SnappyHeader: Vector[Byte] =
    (0x82.toByte +: "SNAPPY".getBytes(StandardCharsets.US_ASCII).toVector) :+ 0.toByte

  test("test sessions carry exactly the Snappy compression settings") {
    assertEquals(LocalSpark.TestCompression, Expected)
    assertEquals(LocalSpark.TestCompression.map(_._1).distinct.size, Expected.size)
    val conf = spark.sparkContext.getConf
    Expected.foreach { case (k, v) =>
      assertEquals(conf.getOption(k), Some(v), s"SparkConf $k")
      assertEquals(spark.conf.get(k), v, s"session conf $k")
    }
    // The session is the one this suite started (getOrCreate did not return an older session).
    assertEquals(conf.get("spark.local.dir"), tmp.resolve("local").toString)
  }

  test("every compression key is registered by the running Spark") {
    // Load the core and SQL config holders, so every entry is in ConfigEntry's registry.
    Class.forName("org.apache.spark.internal.config.package$")
    Class.forName("org.apache.spark.sql.internal.SQLConf$")
    val registry =
      Class.forName("org.apache.spark.internal.config.ConfigEntry$").getField("MODULE$").get(null)
    val find = registry.getClass.getMethod("findEntry", classOf[String])
    println(s"SP-P7-01 spark.version=${spark.version}")
    assert(spark.version.startsWith("3.5."), spark.version)
    Expected.foreach { case (k, _) =>
      val entry = find.invoke(registry, k)
      assert(entry != null, s"$k is not a config key of Spark ${spark.version}")
      val key = entry.getClass.getMethod("key").invoke(entry)
      val default = entry.getClass.getMethod("defaultValueString").invoke(entry)
      assertEquals(key, k)
      println(s"SP-P7-01 config key=$k registered=true default=$default")
    }
    // Counter-test: the registry lookup does refuse an unknown key.
    assertEquals(find.invoke(registry, "spark.io.compression.codec.scholarchainUnknown"), null)
  }

  test("shuffle, broadcast join and Parquet round trip compress with Snappy") {
    val facts = spark
      .range(0L, 2000L)
      .select(col("id"), (col("id") % 10).as("k"))
    val names = spark.range(0L, 10L).select(col("id").as("k"), lit("name").as("prefix"))
    val joined = facts.join(broadcast(names), "k")
    val counted = joined.groupBy("k").agg(count(lit(1)).as("n"))
    assert(
      counted.queryExecution.executedPlan.toString.contains("BroadcastHashJoin"),
      counted.queryExecution.executedPlan.toString
    )

    val out = tmp.resolve("codec-parquet")
    counted.coalesce(1).write.mode(SaveMode.ErrorIfExists).parquet(out.toUri.toString)
    val back = spark.read
      .parquet(out.toUri.toString)
      .orderBy("k")
      .collect()
      .toVector
      .map(r => (r.getLong(0), r.getLong(1)))
    assertEquals(back, (0L until 10L).toVector.map(k => (k, 200L)))

    // Every shuffle data file written by this session starts with the Snappy stream header.
    val shuffleData = Using.resource(Files.walk(tmp.resolve("local"))) { s =>
      s.iterator()
        .asScala
        .filter(p => Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
        .filter(p => p.getFileName.toString.matches("shuffle_\\d+_\\d+_\\d+\\.data"))
        .filter(p => Files.size(p) > 0L)
        .toVector
    }
    assert(shuffleData.nonEmpty, s"no shuffle data file under ${tmp.resolve("local")}")
    shuffleData.foreach { p =>
      val head = Using.resource(Files.newInputStream(p))(_.readNBytes(SnappyHeader.size)).toVector
      assertEquals(head, SnappyHeader, s"stream header of $p")
    }
    println(s"SP-P7-01 shuffle data files with the Snappy header: ${shuffleData.size}")

    // Every column chunk of every written Parquet part reports SNAPPY in its footer.
    val parts = Using.resource(Files.list(out)) { s =>
      s.iterator().asScala.filter(_.getFileName.toString.endsWith(".parquet")).toVector
    }
    assertEquals(parts.size, 1, s"$parts")
    val codecs = parts.flatMap { p =>
      val in =
        HadoopInputFile.fromPath(new HPath(p.toUri), spark.sparkContext.hadoopConfiguration)
      Using.resource(ParquetFileReader.open(in)) { r =>
        r.getFooter.getBlocks.asScala.toVector.flatMap(_.getColumns.asScala.map(_.getCodec))
      }
    }
    assert(codecs.nonEmpty)
    assertEquals(codecs.distinct, Vector(CompressionCodecName.SNAPPY))
    println(s"SP-P7-01 parquet column chunks: ${codecs.size}, codecs=${codecs.distinct}")
  }

  // ---------------------------------------------------------------- source scan

  /** `modules/spark/src`, from the forked test JVM's working directory (the project base). */
  private def sourceRoot: Path =
    Vector(Paths.get("src"), Paths.get("modules", "spark", "src"))
      .map(_.toAbsolutePath.normalize)
      .find(p => Files.isDirectory(p.resolve("main/scala/scholarchain/spark")))
      .getOrElse(fail(s"modules/spark/src not found from ${Paths.get("").toAbsolutePath}"))

  private val ThisFile = "TestCompressionSuite.scala"

  /** (path relative to src, line number, line) of every line of every file under src. */
  private def sourceLines(root: Path): Vector[(String, Int, String)] =
    Using.resource(Files.walk(root)) { s =>
      s.iterator()
        .asScala
        .filter(p => Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
        .filterNot(_.getFileName.toString == ThisFile)
        .toVector
        .sortBy(_.toString)
        .flatMap { p =>
          val rel = root.relativize(p).iterator().asScala.mkString("/")
          new String(Files.readAllBytes(p), StandardCharsets.UTF_8)
            .split("\n", -1)
            .toVector
            .zipWithIndex
            .map { case (l, i) => (rel, i + 1, l) }
        }
    }

  test(
    "no ORC, LZ4_RAW, LZO or Zstandard codec in modules/spark/src; sessions only in LocalSpark"
  ) {
    val root = sourceRoot
    val lines = sourceLines(root)
    assert(lines.exists(_._1.startsWith("main/")), "main sources scanned")
    assert(lines.exists(_._1.startsWith("test/")), "test sources scanned")
    assert(lines.exists(_._1.startsWith("it/")), "it sources scanned")

    val forbidden = "(?i)(\\borc\\b|lz4_?raw|\\blzo\\b|zstd)".r
    val forbiddenHits = lines.filter(l => forbidden.findFirstIn(l._3).nonEmpty)
    forbiddenHits.foreach { case (f, n, l) => println(s"SP-P7-01 forbidden-codec hit $f:$n $l") }
    assertEquals(forbiddenHits.map(h => s"${h._1}:${h._2}"), Vector.empty)

    val words = "(?i)(\\blz4\\b|brotli|gzip|snappy|compress|codec)".r
    val wordHits = lines.filter(l => words.findFirstIn(l._3).nonEmpty)
    wordHits.foreach { case (f, n, l) => println(s"SP-P7-01 codec-word $f:$n ${l.trim}") }
    println(s"SP-P7-01 codec-word lines: ${wordHits.size}")

    // Test and SparkIT sessions come only from LocalSpark (so they all get TestCompression).
    val builderSites = "SparkSession\\s*\\.\\s*builder".r
    val texts = lines.groupBy(_._1).view.mapValues(_.sortBy(_._2).map(_._3).mkString("\n")).toMap
    val testBuilders = texts
      .collect {
        case (f, t) if !f.startsWith("main/") && builderSites.findFirstIn(t).nonEmpty => f
      }
      .toVector
      .sorted
    val mainBuilders = texts
      .collect {
        case (f, t) if f.startsWith("main/") && builderSites.findFirstIn(t).nonEmpty => f
      }
      .toVector
      .sorted
    println(s"SP-P7-01 production SparkSession.builder sites (unchanged): $mainBuilders")
    assertEquals(testBuilders, Vector("test/scala/scholarchain/spark/testkit/LocalSpark.scala"))
    val contexts = lines.filter(l => l._3.contains("new SparkContext") && !l._1.startsWith("main/"))
    assertEquals(contexts, Vector.empty)
  }
}
