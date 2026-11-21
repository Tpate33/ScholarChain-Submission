package scholarchain.storage

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path, Paths, StandardCopyOption}
import java.util.Comparator

import scala.jdk.CollectionConverters.*
import scala.util.Using

import scholarchain.storage.local.LocalRunSource
import scholarchain.storage.model.SourceBounds
import scholarchain.storage.port.RunSource

/**
 * The Phase 6 archive fixtures (copied by the human, 2026-11-19) and temporary copies of them.
 * Fixture files are only read. Every change a test makes is made to a copy inside a temporary
 * directory the test creates and removes ([[withCopy]]).
 */
object Fixtures {
  val RunHex = "a65f3f38b597fc4709fe24e0ddbd12ae09a0c79ad54f9d1f5378c06cfe8a8c40"
  val RunId: String = "run:" + RunHex
  val PartsSha256 = "749763adb05af272a3c0c5473ed3d90d071a3e762d67ef70ebef84295cc94907"
  val Run2PartsSha256 = "a3200b82f3f91ed38b202150e8a68fcf5eab6d5e55206e167c31034f8c065b1f"

  private def resource(name: String): Path =
    Paths.get(getClass.getResource(s"/fixtures/$name").toURI)

  lazy val run1Root: Path = resource("phase06-run-1-local")
  lazy val run2Root: Path = resource("phase06-run-2-local-cluster")
  def runDir(root: Path): Path = root.resolve(s"run=$RunHex")
  def loadDir(root: Path): Path = root.resolve(s"load=$RunHex")

  val runBounds: SourceBounds = SourceBounds.of(100, 8L * 1024 * 1024, 2).toOption.get
  val loadBounds: SourceBounds = SourceBounds.of(10, 1024L * 1024, 1).toOption.get

  def source(dir: Path, bounds: SourceBounds = runBounds): RunSource =
    LocalRunSource.open(dir, bounds) match {
      case Right(s) => s
      case Left(e) => throw new AssertionError(s"cannot open $dir: $e")
    }

  /**
   * Records every listing and every read (by path) of the wrapped source; `bounds` records the
   * `maxBytes` of every bounded read (None for an unbounded `read`).
   */
  final class RecordingRunSource(underlying: RunSource) extends RunSource {
    var listings: Int = 0
    val reads: scala.collection.mutable.ArrayBuffer[String] =
      scala.collection.mutable.ArrayBuffer.empty
    val bounds: scala.collection.mutable.ArrayBuffer[(String, Option[Long])] =
      scala.collection.mutable.ArrayBuffer.empty
    def listFiles() = { listings += 1; underlying.listFiles() }
    def read(relativePath: String) = {
      reads += relativePath
      bounds += relativePath -> None
      underlying.read(relativePath)
    }
    def readAtMost(relativePath: String, maxBytes: Long) = {
      reads += relativePath
      bounds += relativePath -> Some(maxBytes)
      underlying.readAtMost(relativePath, maxBytes)
    }
  }

  /** The Phase 6 fixture's Parquet footer bound for tests (configuration value, test-chosen). */
  val footerBounds: scholarchain.storage.parquet.ParquetFooterBounds =
    scholarchain.storage.parquet.ParquetFooterBounds.of(1024 * 1024).toOption.get

  def recording(dir: Path, bounds: SourceBounds = runBounds): RecordingRunSource =
    new RecordingRunSource(source(dir, bounds))

  def bytes(p: Path): Array[Byte] = Files.readAllBytes(p)
  def text(p: Path): String = new String(bytes(p), StandardCharsets.UTF_8)
  def write(p: Path, s: String): Unit = { Files.write(p, s.getBytes(StandardCharsets.UTF_8)); () }

  /** Copies the regular files of `src` (recursively, never following links) into `dst`. */
  private def copyTree(src: Path, dst: Path): Unit =
    Using.resource(Files.walk(src)) { s =>
      s.iterator().asScala.foreach { p =>
        val target = dst.resolve(src.relativize(p).toString)
        if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(target)
        else if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
          Files.copy(p, target, StandardCopyOption.COPY_ATTRIBUTES)
        else throw new AssertionError(s"fixture entry is not a regular file: $p")
      }
    }

  /** Removes a temporary tree this suite created (links removed as links, never followed). */
  private def removeTemp(root: Path): Unit =
    Using.resource(Files.walk(root)) { s =>
      s.sorted(Comparator.reverseOrder[Path]()).iterator().asScala.foreach(Files.delete)
    }

  /** A fresh temporary directory, removed after `f`. */
  def withTemp[A](f: Path => A): A = {
    val tmp = Files.createTempDirectory("scholarchain-storage-test-")
    try f(tmp)
    finally removeTemp(tmp)
  }

  /**
   * In a temporary copy only: replaces the bytes of one part and re-derives everything that
   * describes it, so the change passes every structural check and only its content is wrong: the
   * `_parts.json` entry (byte count, SHA-256, declared rows), and in every load manifest the item,
   * `partManifestSha256` and the load ID. `rows` is the new declared row count.
   */
  def reseal(run: Path, load: Path, path: String, content: Array[Byte], rows: Long): Unit = {
    import scholarchain.core.{Canonical, Json, PartManifests, StoreLoadId, StoreLoadManifest}
    Files.write(run.resolve(path), content)
    val sha = Canonical.sha256Hex(content)
    val pm = PartManifests
      .decode(text(run.resolve("_parts.json")))
      .map(PartManifests.asV2)
      .fold(e => throw new AssertionError(e.toString), identity)
    val changed = pm.copy(files =
      pm.files.map(f =>
        if (f.path == path) f.copy(byteCount = content.length.toLong, sha256 = sha, rowCount = rows)
        else f
      )
    )
    val partsText = Json.write(changed) + "\n"
    write(run.resolve("_parts.json"), partsText)
    val partsSha = Canonical.sha256HexUtf8(partsText)
    scholarchain.core.TargetStore.values.foreach { t =>
      val file = load.resolve(s"${t.tag}.json")
      val m = Json
        .decode[StoreLoadManifest](text(file).stripSuffix("\n"))
        .fold(e => throw new AssertionError(e.toString), identity)
      val items = m.items.map(i =>
        if (i.path == path) i.copy(byteCount = content.length.toLong, sha256 = sha, rowCount = rows)
        else i
      )
      val id = StoreLoadId
        .of(m.targetStore, m.sourceRunId, partsSha, items.map(i => (i.path, i.targetRole)))
        .fold(e => throw new AssertionError(e.toString), identity)
      write(
        file,
        Json.write(m.copy(loadId = id, partManifestSha256 = partsSha, items = items)) + "\n"
      )
    }
  }

  /** [[reseal]] with a text replacement in one CSV part; the declared rows are recounted. */
  def resealCsv(run: Path, load: Path, path: String)(change: String => String): Unit = {
    val content = change(text(run.resolve(path))).getBytes(StandardCharsets.UTF_8)
    val rows = scholarchain.storage.load.CsvRecords
      .scan(content)
      .fold(why => throw new AssertionError(why), _.dataRows)
    reseal(run, load, path, content, rows)
  }

  /** A temporary copy of the run-1 fixture: `f(runDir, loadDir)` may change it freely. */
  def withCopy[A](f: (Path, Path) => A): A =
    withTemp { tmp =>
      val root = tmp.resolve("fixture")
      copyTree(run1Root, root)
      f(runDir(root), loadDir(root))
    }
}
