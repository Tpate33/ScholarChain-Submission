package scholarchain.storage

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path, Paths}

import scala.jdk.CollectionConverters.*
import scala.util.Using

/**
 * DU-P7-02 item 9a: a lexical scan of `modules/storage/src` (main and test) for the native codec
 * packages. Storage MAIN must name none of them (its Parquet path uses only the pure SnappyRaw
 * decoder); every test-tree hit is printed with file:line for the dependency report.
 */
class CodecReferenceScanSuite extends munit.FunSuite {

  private val Patterns = Vector(
    "org.xerial",
    "io.airlift",
    "com.github.luben",
    "org.apache.hadoop.io.compress",
    "org.apache.commons.pool"
  )

  /** `modules/storage/src`: the forked test JVM runs in the project base or the repository root. */
  private lazy val src: Path =
    Vector(Paths.get("src"), Paths.get("modules", "storage", "src"))
      .map(_.toAbsolutePath.normalize)
      .find(p => Files.isDirectory(p.resolve("main"), LinkOption.NOFOLLOW_LINKS))
      .getOrElse(fail(s"storage src not found from ${sys.props("user.dir")}"))

  private def hits(root: Path): Vector[String] =
    Using.resource(Files.walk(root)) { s =>
      s.iterator()
        .asScala
        .filter(p => Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
        .filter(p => p.toString.endsWith(".scala") || p.toString.endsWith(".java"))
        .toVector
        .sortBy(_.toString)
        .flatMap { p =>
          new String(Files.readAllBytes(p), StandardCharsets.UTF_8)
            .split("\n", -1)
            .toVector
            .zipWithIndex
            .collect {
              case (line, i) if Patterns.exists(line.contains) =>
                s"${src.getParent.getParent.getParent.relativize(p)}:${i + 1}: ${line.trim}"
            }
        }
    }

  /**
   * The only main hit allowed: a STRING VALUE compared with the curated run manifest's recorded
   * output codec parameter (the curated `.gz` parts are then read with the JDK's GZIPInputStream);
   * no class of that package is named, imported or loaded.
   */
  private val AllowedMain = Vector(
    "modules/storage/src/main/scala/scholarchain/storage/load/UpstreamRuns.scala:" ->
      "private val GzipCodec = \"org.apache.hadoop.io.compress.GzipCodec\""
  )

  test("storage main names no native codec package (one manifest string value); hits listed") {
    val main = hits(src.resolve("main"))
    val test = hits(src.resolve("test"))
    println(s"DU-P7-02 9a: storage main hits: ${main.size}")
    main.foreach(h => println("  main " + h))
    println(s"DU-P7-02 9a: storage test hits: ${test.size}")
    test.foreach(h => println("  test " + h))
    val unexpected = main.filterNot(h =>
      AllowedMain.exists((file, line) => h.startsWith(file) && h.endsWith(": " + line))
    )
    assertEquals(unexpected, Vector.empty)
    assertEquals(main.size, AllowedMain.size)
  }
}
