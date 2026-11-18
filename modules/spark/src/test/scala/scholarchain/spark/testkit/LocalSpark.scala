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
 */
object LocalSpark {

  def start(appName: String, root: Path, master: String = "local[1]"): SparkSession = {
    require(master.startsWith("local"), s"LocalSpark starts local masters only, not $master")
    Files.createDirectories(root.resolve("local"))
    val s = SparkSession
      .builder()
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
