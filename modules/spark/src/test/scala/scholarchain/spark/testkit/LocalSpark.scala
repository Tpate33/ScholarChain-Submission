package scholarchain.spark.testkit

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path}
import java.util.Comparator

import scala.jdk.CollectionConverters.*
import scala.util.Using

import org.apache.spark.sql.SparkSession

import scholarchain.core.config.{AnalysisRunSettings, ConfigLoader, ScholarChainConfig}

/**
 * Local Spark for tests and the sparkIT suites: `local[1]` (or the given local master, for example
 * `local[2]` in [[PublishedRunComparisonMain]]), UI off, one shuffle partition, driver bound to
 * 127.0.0.1 (loopback listeners only; no outbound network is used), UTC, and every warehouse, local
 * and `hadoop.tmp.dir` directory under `root` (a directory inside `java.io.tmpdir`).
 *
 * Compression ([[TestCompression]], SP-P7-01, human decision 2026-11-20, DU-P7-04): every codec
 * Spark can choose is Snappy, and shuffle, spill and broadcast compression are pinned on, so
 * compression stays exercised without the LZ4 and Zstandard codecs. Test sessions only: the
 * production job ([[scholarchain.spark.job.CollaborationJob]]) sets no Spark setting in code.
 */
object LocalSpark {

  /**
   * The compression settings of every test session (keys checked against the Spark 3.5.5 config
   * registry by `TestCompressionSuite`, which also prints the defaults they replace: LZ4 for
   * `spark.io.compression.codec` and `spark.sql.streaming.stateStore.compression.codec`, Zstandard
   * for `spark.shuffle.mapStatus.compression.codec` and `spark.eventLog.compression.codec`; the
   * Parquet codec (default Snappy) and the three `true` flags (defaults `true`) are pinned.
   */
  val TestCompression: Vector[(String, String)] = Vector(
    "spark.io.compression.codec" -> "snappy",
    "spark.shuffle.mapStatus.compression.codec" -> "snappy",
    "spark.eventLog.compression.codec" -> "snappy",
    "spark.sql.streaming.stateStore.compression.codec" -> "snappy",
    "spark.sql.parquet.compression.codec" -> "snappy",
    "spark.shuffle.compress" -> "true",
    "spark.shuffle.spill.compress" -> "true",
    "spark.broadcast.compress" -> "true"
  )

  def start(appName: String, root: Path, master: String = "local[1]"): SparkSession = {
    require(master.startsWith("local"), s"LocalSpark starts local masters only, not $master")
    Files.createDirectories(root.resolve("local"))
    val builder = TestCompression.foldLeft(SparkSession.builder()) { case (b, (k, v)) =>
      b.config(k, v)
    }
    val s = builder
      .master(master)
      .appName(appName)
      .config("spark.ui.enabled", "false")
      .config("spark.sql.shuffle.partitions", "1")
      .config("spark.default.parallelism", "1")
      .config("spark.sql.warehouse.dir", root.resolve("warehouse").toUri.toString)
      .config("spark.local.dir", root.resolve("local").toString)
      .config("spark.hadoop.hadoop.tmp.dir", root.resolve("hadoop").toString)
      .config("spark.driver.host", "127.0.0.1")
      .config("spark.driver.bindAddress", "127.0.0.1")
      .config("spark.sql.session.timeZone", "UTC")
      .getOrCreate()
    s.sparkContext.setLogLevel("WARN")
    s
  }

  def deleteTree(root: Path): Unit =
    if (root != null && Files.exists(root, LinkOption.NOFOLLOW_LINKS))
      Using.resource(Files.walk(root)) { s =>
        s.sorted(Comparator.reverseOrder[Path]()).iterator().asScala.foreach { p =>
          Files.deleteIfExists(p)
          ()
        }
      }

  /** UTF-8 text of a class-path resource (test resources are on the Test and SparkIT paths). */
  def resourceText(name: String): String = {
    val in = getClass.getClassLoader.getResourceAsStream(name)
    if (in == null) throw new AssertionError(s"resource $name not found")
    try new String(in.readAllBytes(), StandardCharsets.UTF_8)
    finally in.close()
  }

  val DevConfigResource = "scholarchain/spark/dev-run-01/analysis-dev.conf"
  val DevBenchmarkPairsResource = "scholarchain/spark/dev-run-01/benchmark-pairs-dev.v1.json"

  /** The committed dev analysis configuration, resolved with an empty environment. */
  def devConfig(): ScholarChainConfig =
    ConfigLoader
      .resolve(
        Vector(s"classpath:$DevConfigResource" -> resourceText(DevConfigResource)),
        Map.empty
      )
      .fold(e => throw new AssertionError(s"dev config: ${e.map(_.message)}"), identity)

  def devSettings(): AnalysisRunSettings =
    AnalysisRunSettings
      .from(devConfig())
      .fold(e => throw new AssertionError(s"dev settings: ${e.map(_.message)}"), identity)
}
