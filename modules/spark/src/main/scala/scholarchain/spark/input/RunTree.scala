package scholarchain.spark.input

import java.io.IOException
import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}
import java.nio.ByteBuffer
import java.nio.file.{Files, LinkOption, Path}
import java.nio.file.attribute.BasicFileAttributes

import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*
import scala.util.Using

import scholarchain.core.ObservedFile

/**
 * The checks of the verified archive reader (work order SP-P6-02 step 1, CR-P6-14 nit 3, CR-P6-19
 * F6; step 1A repair 02 adds the Hadoop curated-run steps 2, 7 and 10). `order` numbers the checks;
 * the exact sequence is documented on [[VerifiedArchiveReader]]: the layout checks (orders 1-7) run
 * for all three runs before any file content is read (2 and 7 for the curated run only, 5 for the
 * decision and grouping runs only); then, per run, part-manifest (8, decision and grouping),
 * run-manifest (9) and stage-counters (10, curated only) in the order curated 9, 10, decision 8, 9,
 * grouping 8, 9; then orders 11-15 once over the chain. The reader stops at the first stage that
 * refuses, so no check of a later stage runs after a refusal.
 */
enum CheckStep(val tag: String, val order: Int) {

  /** The run directory is named `run=<64 lowercase hex>`. */
  case RunName extends CheckStep("run-name", 1)

  /**
   * Curated run only (HD-P6-01 rule 1): its run ID is the pinned run ID the caller supplies, and it
   * lies in `hadoop/snapshot=<token>/`.
   */
  case CuratedRun extends CheckStep("curated-run", 2)

  /** No `_UNVERIFIED` marker in the run directory. */
  case UnverifiedMarker extends CheckStep("unverified-marker", 3)

  /** No `.staging-*` or `.unverified-*` entry beside or inside the run directory. */
  case StagingEntry extends CheckStep("staging-entry", 4)

  /** Decision and grouping runs: a regular `_SUCCESS` marker in the run directory. */
  case SuccessMarker extends CheckStep("success-marker", 5)

  /** The run is a real directory whose tree holds only regular files and directories, bounded. */
  case Tree extends CheckStep("tree", 6)

  /** Curated run only: HD-P6-01 structural rules 2-8, 10 and 11 ([[HadoopCuratedRun]]). */
  case HadoopLayout extends CheckStep("hadoop-layout", 7)

  /** Every file verifies against `_parts.json` (rows, bytes, sha256) and the run is complete. */
  case PartManifest extends CheckStep("part-manifest", 8)

  /** `_run-manifest.json` decodes, its run ID is the directory's, and it is bound to the parts. */
  case RunManifest extends CheckStep("run-manifest", 9)

  /** Curated run only: each stage's `_counters.json` content (HD-P6-01 rule 4). */
  case StageCounters extends CheckStep("stage-counters", 10)

  /** The grouping run names the decision and curated runs; the decision run names the curated. */
  case RunChain extends CheckStep("run-chain", 11)

  /** The declared rows and declared bytes fit the configured driver validation bounds. */
  case DriverBound extends CheckStep("driver-bound", 12)

  /** Every row of every consumed part decodes through its core contract. */
  case Decode extends CheckStep("decode", 13)

  /** `AnalysisValidation.checkGraphInput`. */
  case GraphInput extends CheckStep("graph-input", 14)

  /** `AnalysisValidation.checkIdentity` (run-chain binding of assignments and decisions). */
  case Identity extends CheckStep("identity", 15)
}

/** One refusal: the check that refused, the run it concerns and why. */
final case class Refusal(step: CheckStep, run: String, message: String) {
  override def toString: String = s"[${step.tag}] $run: $message"
}

/**
 * Bounds of the reader, supplied by the caller (never built in).
 *
 * @param maxTreeEntries
 *   most entries (files and directories) walked per run directory
 * @param maxTreeDepth
 *   deepest directory level below a decision or grouping run directory (the Hadoop curated run is
 *   walked to the producer contract's fixed depths, [[HadoopCuratedRun.MaxFileDepth]] and
 *   [[HadoopCuratedRun.MaxDirDepth]])
 * @param maxDriverValidationRows
 *   most data rows (all consumed parts together) decoded on the driver for the core validators
 * @param maxDriverValidationBytes
 *   most bytes (declared, and verified against the observed bytes, of all consumed parts together)
 *   read into driver memory for decoding; each part is then read with at most its declared bytes
 * @param maxMetadataBytes
 *   most bytes of one `_parts.json`, `_run-manifest.json` or curated `_counters.json` read into
 *   driver memory (checked from the file size before reading, and again while reading)
 */
final case class ReaderLimits(
    maxTreeEntries: Int,
    maxTreeDepth: Int,
    maxDriverValidationRows: Long,
    maxDriverValidationBytes: Long,
    maxMetadataBytes: Long
)

/** One entry of a run directory tree, observed without following symbolic links. */
final case class TreeEntry(relativePath: String, kind: TreeEntry.Kind, depth: Int)

object TreeEntry {
  enum Kind {
    case Regular, Directory, SymbolicLink, Other
  }
}

/**
 * File-system helpers of the reader. Every stat uses `NOFOLLOW_LINKS`, every read opens the file
 * with `NOFOLLOW_LINKS`, and nothing is ever written. Driver-side by design: run directories are
 * observed on the driver's file system (local or a mounted copy), one buffered stream at a time.
 */
object RunTree {

  val RunDirName = "^run=([0-9a-f]{64})$".r
  val StagingPrefixes: Vector[String] = Vector(".staging-", ".unverified-")

  /** Marker of an unverified (unpublished) run directory (DecisionExport/GroupingExport rule). */
  val UnverifiedMarker = "_UNVERIFIED"
  private val BufferBytes = 64 * 1024

  private def attrs(p: Path): Option[BasicFileAttributes] =
    try Some(Files.readAttributes(p, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS))
    catch { case _: IOException => None }

  def kindOf(p: Path): Option[TreeEntry.Kind] =
    attrs(p).map { a =>
      if (a.isSymbolicLink) TreeEntry.Kind.SymbolicLink
      else if (a.isDirectory) TreeEntry.Kind.Directory
      else if (a.isRegularFile) TreeEntry.Kind.Regular
      else TreeEntry.Kind.Other
    }

  def isRealDirectory(p: Path): Boolean = kindOf(p).contains(TreeEntry.Kind.Directory)
  def isRegularFile(p: Path): Boolean = kindOf(p).contains(TreeEntry.Kind.Regular)
  def exists(p: Path): Boolean = kindOf(p).isDefined

  /** The size of a regular file (NOFOLLOW stat); None for anything else or a failed stat. */
  def sizeOf(p: Path): Option[Long] =
    attrs(p).filter(a => a.isRegularFile && !a.isSymbolicLink).map(_.size)

  def isStagingName(name: String): Boolean = StagingPrefixes.exists(name.startsWith)

  /**
   * Names in `dir`, sorted; Left(why) when it cannot be listed (fail closed, CR-P6-19 F5: an
   * unlistable directory is never treated as empty).
   */
  def names(dir: Path): Either[String, Vector[String]] =
    try
      Right(Using.resource(Files.newDirectoryStream(dir)) { s =>
        s.iterator().asScala.map(_.getFileName.toString).toVector.sorted
      })
    catch { case e: IOException => Left(s"cannot list '$dir': $e") }

  /**
   * Walks `root` without following links. Right(entries) in depth-first, name-sorted order, or
   * Left(why) when the walk exceeds a bound or a directory cannot be listed. Symbolic links and
   * special files are returned as entries (never entered), so the caller refuses them.
   */
  def walk(root: Path, maxEntries: Int, maxDepth: Int): Either[String, Vector[TreeEntry]] = {
    val out = ArrayBuffer.empty[TreeEntry]
    def go(dir: Path, rel: Vector[String], depth: Int): Option[String] =
      if (depth > maxDepth) Some(s"deeper than $maxDepth level(s) at '${rel.mkString("/")}'")
      else {
        val listed =
          try
            Right(Using.resource(Files.newDirectoryStream(dir)) { s =>
              s.iterator().asScala.map(_.getFileName.toString).toVector.sorted
            })
          catch { case e: IOException => Left(s"cannot list '${rel.mkString("/")}': $e") }
        listed match {
          case Left(why) => Some(why)
          case Right(children) =>
            children.iterator
              .map { name =>
                val r = rel :+ name
                val p = dir.resolve(name)
                val k = kindOf(p).getOrElse(TreeEntry.Kind.Other)
                out += TreeEntry(r.mkString("/"), k, depth)
                if (out.size > maxEntries) Some(s"more than $maxEntries entries")
                else if (k == TreeEntry.Kind.Directory) go(p, r, depth + 1)
                else None
              }
              .collectFirst { case Some(why) => why }
        }
      }
    go(root, Vector.empty, 1).toLeft(out.toVector)
  }

  /** The observation of one regular file, streamed (never follows a link). */
  def observe(root: Path, relativePath: String): Either[String, ObservedFile] =
    try {
      val acc = new ObservedFile.Accumulator
      Using.resource(Files.newInputStream(root.resolve(relativePath), LinkOption.NOFOLLOW_LINKS)) {
        in =>
          val buf = new Array[Byte](BufferBytes)
          var n = in.read(buf)
          while (n >= 0) {
            if (n > 0) acc.update(buf, 0, n)
            n = in.read(buf)
          }
      }
      Right(acc.result(relativePath))
    } catch { case e: IOException => Left(s"cannot read '$relativePath': $e") }

  /**
   * Largest byte array the JVM allocates (a property of the platform, not a tunable threshold).
   */
  val MaxArrayBytes: Long = Int.MaxValue.toLong - 8L

  /**
   * Strict UTF-8 text of a regular file of at most `maxBytes` bytes (malformed bytes refused, never
   * replaced). Bounded twice (CR-P6-19 F3): the file size (NOFOLLOW stat) is compared with
   * `maxBytes` before the file is opened, and the read itself stops after `maxBytes + 1` bytes, so
   * a file that grows between the stat and the read is refused, never read unbounded.
   */
  def readUtf8(file: Path, maxBytes: Long): Either[String, String] =
    if (maxBytes < 0L || maxBytes > MaxArrayBytes)
      Left(s"read bound $maxBytes is outside 0..$MaxArrayBytes")
    else
      attrs(file) match {
        case None => Left(s"cannot read '$file': no such file, or it cannot be stat'ed")
        case Some(a) if a.isSymbolicLink || !a.isRegularFile =>
          Left(s"'$file' is not a regular file")
        case Some(a) if a.size > maxBytes =>
          Left(s"'$file' has ${a.size} bytes, more than the read bound $maxBytes")
        case Some(_) =>
          try {
            val bytes = Using.resource(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS))(
              _.readNBytes(maxBytes.toInt + 1)
            )
            if (bytes.length.toLong > maxBytes)
              Left(s"'$file' grew beyond the read bound $maxBytes while it was read")
            else {
              val decoder = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
              Right(decoder.decode(ByteBuffer.wrap(bytes)).toString)
            }
          } catch {
            case e: CharacterCodingException => Left(s"'$file' is not valid UTF-8: $e")
            case e: IOException => Left(s"cannot read '$file': $e")
          }
      }

  /** The LF-terminated rows of a JSONL text (the empty text has no row). */
  def jsonlRows(text: String): Vector[String] =
    if (text.isEmpty) Vector.empty else text.stripSuffix("\n").split("\n", -1).toVector
}
