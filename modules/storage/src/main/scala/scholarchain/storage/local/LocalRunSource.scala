package scholarchain.storage.local

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.{
  DirectoryStream,
  FileVisitOption,
  FileVisitResult,
  Files,
  LinkOption,
  OpenOption,
  Path,
  SecureDirectoryStream,
  SimpleFileVisitor,
  StandardOpenOption
}
import java.nio.file.attribute.{BasicFileAttributeView, BasicFileAttributes}

import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.Using

import scholarchain.core.{Canonical, PartManifest}
import scholarchain.storage.model.{Bytes, SourceBounds, StoreError}
import scholarchain.storage.port.RunSource

/**
 * How [[LocalRunSource]] reaches a file below its root (SE-P7-05, risk T1). Frozen tags.
 *
 *   - `directory-handles`: the platform gives a `SecureDirectoryStream` (openat): every directory
 *     segment is opened relative to the already-opened parent handle with `NOFOLLOW_LINKS`, its
 *     type read from that handle (fstatat, no link followed), and the file opened relative to the
 *     last handle with `NOFOLLOW_LINKS`. A segment swapped for a link after the parent was opened
 *     is never followed.
 *   - `path-checks`: no `SecureDirectoryStream` (for example a JDK on macOS that lacks the openat
 *     family): every segment is checked without following links before the open and again after it,
 *     and the file's identity (`fileKey`) must be the same before and after the open. A swap that
 *     is undone between the two checks is not detected; the verifier's SHA-256 comparison still
 *     refuses changed content.
 */
enum Traversal(val tag: String) {
  case DirectoryHandles extends Traversal("directory-handles")
  case PathChecks extends Traversal("path-checks")
}

/**
 * A published directory on the local filesystem (SE-P7-03, SE-P7-05): the Phase 6 archive or a test
 * copy.
 *
 *   - The root must be a directory that is not itself a symbolic link (checked without following
 *     links). Its ancestors are not checked: they belong to the operator's filesystem.
 *   - Listing walks the tree without following links. A symbolic link anywhere, a special file, a
 *     file name outside the part-manifest path rules, more than `maxFiles` files, more than
 *     `maxTotalBytes` bytes in total, or a file deeper than `maxDepth` refuses the whole listing.
 *   - Reading refuses a path outside the part-manifest path rules or deeper than `maxDepth`. It
 *     reaches the file by [[Traversal]] (never following a link in any segment), refuses anything
 *     that is not a regular file before opening it (so a FIFO or device is never opened and cannot
 *     block the reader), re-reads the type and identity right after the open and refuses a change,
 *     and reads at most `min(maxTotalBytes, maxBytes) + 1` bytes: a file larger than the bound is
 *     refused, a file whose size at the open already exceeds it without reading a byte.
 *
 * What Java cannot guarantee (documented, not hidden): NIO has no `O_NONBLOCK` open and no `fstat`
 * of an open channel, so "not a regular file" is decided from the directory entry immediately
 * before and after the open, not from the open descriptor itself. Residual (availability only,
 * CR-P7-07 m3 corrected): a swap between the pre-open check and the open can make the open reach a
 * FIFO and block: in both modes when the last segment itself is swapped for a FIFO, and in
 * `path-checks` mode also when an INTERMEDIATE directory is swapped (for a link, or for a directory
 * holding a FIFO under the file's name), because that mode resolves the whole path again at the
 * open; `directory-handles` opens relative to already-opened handles, so an intermediate swap
 * cannot redirect it. No mitigation is possible in NIO without `O_NONBLOCK`; the post-open checks
 * refuse the result if the open returns, and every other swap is refused or yields bytes the
 * verifier's SHA-256 comparison refuses. The operator's mitigation is a source directory no other
 * user can write (the archive and the fixtures are owner-only). It does not require a prior
 * listing; `maxFiles` and the listing's byte total bound listings only. Errors name relative paths
 * only.
 */
final class LocalRunSource private (root: Path, bounds: SourceBounds) extends RunSource {
  import LocalRunSource.*

  private def relative(p: Path): String =
    root.relativize(p).iterator().asScala.map(_.toString).mkString("/")

  def listFiles(): Either[StoreError, Vector[String]] = {
    val out = mutable.ArrayBuffer.empty[String]
    var total = 0L
    var problem: Option[StoreError] = None
    def stop(e: StoreError): FileVisitResult = { problem = Some(e); FileVisitResult.TERMINATE }
    val visitor = new SimpleFileVisitor[Path] {
      override def preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
        if (dir != root && attrs.isSymbolicLink) stop(link(relative(dir)))
        else FileVisitResult.CONTINUE

      override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
        val rel = relative(file)
        if (attrs.isSymbolicLink) stop(link(rel))
        else if (attrs.isDirectory)
          stop(StoreError.Refused(s"$rel: deeper than maxDepth ${bounds.maxDepth}"))
        else if (!attrs.isRegularFile) stop(StoreError.Refused(s"$rel: not a regular file"))
        else
          PartManifest.pathProblem(rel) match {
            case Some(why) => stop(StoreError.Refused(s"$rel: file name $why"))
            case None =>
              out += rel
              total += attrs.size
              if (out.size > bounds.maxFiles)
                stop(StoreError.Refused(s"more than maxFiles ${bounds.maxFiles} files"))
              else if (total > bounds.maxTotalBytes)
                stop(StoreError.Refused(s"more than maxTotalBytes ${bounds.maxTotalBytes} bytes"))
              else FileVisitResult.CONTINUE
          }
      }

      override def visitFileFailed(file: Path, e: IOException): FileVisitResult =
        stop(
          StoreError.Permanent(s"${relative(file)}: cannot be read (${e.getClass.getSimpleName})")
        )
    }
    try {
      checkRoot(root).foreach(e => problem = Some(e))
      if (problem.isEmpty)
        Files.walkFileTree(
          root,
          java.util.EnumSet.noneOf(classOf[FileVisitOption]),
          bounds.maxDepth,
          visitor
        )
      problem match {
        case Some(e) => Left(e)
        case None => Right(out.toVector.sortWith((a, b) => Canonical.compareUtf8(a, b) < 0))
      }
    } catch {
      case e: IOException =>
        Left(StoreError.Permanent(s"the directory cannot be listed (${e.getClass.getSimpleName})"))
    }
  }

  def read(relativePath: String): Either[StoreError, Bytes] =
    readWithin(
      relativePath,
      bounds.maxTotalBytes,
      s"more than maxTotalBytes ${bounds.maxTotalBytes}"
    )

  def readAtMost(relativePath: String, maxBytes: Long): Either[StoreError, Bytes] =
    if (maxBytes < 0) Left(StoreError.Refused(s"maxBytes: $maxBytes is below 0"))
    else if (maxBytes >= bounds.maxTotalBytes)
      readWithin(
        relativePath,
        bounds.maxTotalBytes,
        s"more than maxTotalBytes ${bounds.maxTotalBytes}"
      )
    else readWithin(relativePath, maxBytes, s"more than $maxBytes")

  /** The traversal this source uses on this platform (observable for tests and reports). */
  def traversal: Traversal = {
    val probe =
      try Some(Files.newDirectoryStream(root))
      catch { case _: IOException => None }
    probe match {
      case Some(s: SecureDirectoryStream[?]) => s.close(); Traversal.DirectoryHandles
      case Some(s) => s.close(); Traversal.PathChecks
      case None => Traversal.PathChecks
    }
  }

  private def readWithin(rel: String, limit: Long, over: String): Either[StoreError, Bytes] =
    PartManifest.pathProblem(rel) match {
      case Some(why) => Left(StoreError.Refused(s"path: $why"))
      case None if rel.split("/").length > bounds.maxDepth =>
        Left(StoreError.Refused(s"$rel: deeper than maxDepth ${bounds.maxDepth}"))
      case None =>
        val bound = math.min(limit, MaxArrayBytes.toLong)
        try
          checkRoot(root).toLeft(()).flatMap { _ =>
            Using.resource(Files.newDirectoryStream(root)) {
              case s: SecureDirectoryStream[?] =>
                viaHandles(rel, s.asInstanceOf[SecureDirectoryStream[Path]], bound, over)
              case _ => viaPathChecks(rel, bound, over)
            }
          }
        catch {
          case e: IOException =>
            Left(StoreError.Permanent(s"$rel: cannot be read (${e.getClass.getSimpleName})"))
        }
    }

  /** `directory-handles`: each segment opened relative to its parent's handle, never followed. */
  private def viaHandles(
      rel: String,
      rootStream: SecureDirectoryStream[Path],
      bound: Long,
      over: String
  ): Either[StoreError, Bytes] = {
    val segments = rel.split("/").toVector
    val fs = root.getFileSystem
    val opened = mutable.ArrayBuffer.empty[SecureDirectoryStream[Path]]
    def attrsIn(dir: SecureDirectoryStream[Path], name: String): Option[BasicFileAttributes] =
      try
        Some(
          dir
            .getFileAttributeView(fs.getPath(name), classOf[BasicFileAttributeView], NoFollow*)
            .readAttributes()
        )
      catch { case _: IOException => None }
    try {
      var dir = rootStream
      var problem: Option[StoreError] = None
      segments.init.zipWithIndex.foreach { case (seg, i) =>
        if (problem.isEmpty) {
          val shown = segments.take(i + 1).mkString("/")
          attrsIn(dir, seg) match {
            case Some(a) if a.isDirectory && !a.isSymbolicLink =>
              try {
                // openat(O_NOFOLLOW | O_DIRECTORY) relative to the parent handle.
                val next = dir.newDirectoryStream(fs.getPath(seg), NoFollow*)
                opened += next
                dir = next
              } catch {
                case _: IOException => problem = Some(notDirectory(shown))
              }
            case _ => problem = Some(notDirectory(shown))
          }
        }
      }
      problem.toLeft(()).flatMap { _ =>
        val name = segments.last
        regular(rel, attrsIn(dir, name), bound, over).flatMap { before =>
          Using.resource(dir.newByteChannel(fs.getPath(name), ReadNoFollow)) { ch =>
            sameAfterOpen(rel, before, attrsIn(dir, name))
              .flatMap(_ => readBounded(rel, ch, bound, over))
          }
        }
      }
    } catch {
      case _: java.nio.file.FileSystemLoopException => Left(link(rel))
    } finally opened.reverseIterator.foreach(s => s.close())
  }

  /** `path-checks`: every segment checked before and after the open; identity unchanged. */
  private def viaPathChecks(rel: String, bound: Long, over: String): Either[StoreError, Bytes] =
    segmentsNoFollow(rel).flatMap { file =>
      regular(rel, attributes(file), bound, over).flatMap { before =>
        Using.resource(Files.newByteChannel(file, ReadNoFollow)) { ch =>
          segmentsNoFollow(rel)
            .flatMap(again => sameAfterOpen(rel, before, attributes(again)))
            .flatMap(_ => readBounded(rel, ch, bound, over))
        }
      }
    }

  private def segmentsNoFollow(rel: String): Either[StoreError, Path] = {
    val segments = rel.split("/").toVector
    val dirs = segments.init.scanLeft(root)((p, s) => p.resolve(s)).tail
    dirs.find(d => !isRealDirectory(d)) match {
      case Some(d) => Left(notDirectory(relative(d)))
      case None => Right(segments.foldLeft(root)((p, s) => p.resolve(s)))
    }
  }

  /** The entry is a regular file of at most `bound` bytes (decided before any open). */
  private def regular(
      rel: String,
      a: Option[BasicFileAttributes],
      bound: Long,
      over: String
  ): Either[StoreError, BasicFileAttributes] =
    a match {
      case None => Left(StoreError.Refused(s"$rel: missing"))
      case Some(x) if x.isSymbolicLink => Left(link(rel))
      case Some(x) if !x.isRegularFile => Left(StoreError.Refused(s"$rel: not a regular file"))
      case Some(x) if x.size > bound =>
        Left(StoreError.Refused(s"$rel: $over bytes (the file holds ${x.size} bytes at open)"))
      case Some(x) => Right(x)
    }

  /** The opened entry is still the regular file seen before the open (same fileKey). */
  private def sameAfterOpen(
      rel: String,
      before: BasicFileAttributes,
      after: Option[BasicFileAttributes]
  ): Either[StoreError, Unit] =
    after match {
      case Some(a) if a.isRegularFile && !a.isSymbolicLink && a.fileKey == before.fileKey =>
        Right(())
      case _ =>
        Left(StoreError.Refused(s"$rel: changed while it was opened (links are never followed)"))
    }

  private def readBounded(
      rel: String,
      ch: SeekableByteChannel,
      bound: Long,
      over: String
  ): Either[StoreError, Bytes] = {
    val out = new java.io.ByteArrayOutputStream()
    val buf = ByteBuffer.allocate(ReadChunkBytes)
    var total = 0L
    var done = false
    var exceeded = false
    while (!done && !exceeded) {
      buf.clear()
      // Never ask for more than bound + 1 bytes in total.
      val want = math.min(ReadChunkBytes.toLong, bound + 1 - total).toInt
      buf.limit(math.max(want, 1))
      val n = ch.read(buf)
      if (n < 0) done = true
      else {
        total += n
        if (total > bound) exceeded = true
        else out.write(buf.array(), 0, n)
      }
    }
    if (exceeded) Left(StoreError.Refused(s"$rel: $over bytes"))
    else Right(Bytes.of(out.toByteArray))
  }
}

object LocalRunSource {

  /** Largest single read (a JVM array bound, not a tunable threshold). */
  private val MaxArrayBytes: Int = Int.MaxValue - 8

  /** Read buffer size (I/O granularity only; no effect on results). */
  private val ReadChunkBytes: Int = 64 * 1024

  private val NoFollow: Array[LinkOption] = Array(LinkOption.NOFOLLOW_LINKS)

  private val ReadNoFollow: java.util.Set[OpenOption] =
    java.util.Set.of[OpenOption](StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)

  private def link(rel: String) =
    StoreError.Refused(s"$rel: a symbolic link is never followed")

  private def notDirectory(rel: String) =
    StoreError.Refused(s"$rel: not a directory (links are never followed)")

  private def attributes(p: Path): Option[BasicFileAttributes] =
    try Some(Files.readAttributes(p, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS))
    catch { case _: IOException => None }

  private def isRealDirectory(p: Path): Boolean =
    attributes(p).exists(a => a.isDirectory && !a.isSymbolicLink)

  private def checkRoot(root: Path): Option[StoreError] =
    attributes(root) match {
      case None => Some(StoreError.Refused("the directory does not exist"))
      case Some(a) if a.isSymbolicLink =>
        Some(StoreError.Refused("the directory is a symbolic link (never followed)"))
      case Some(a) if !a.isDirectory => Some(StoreError.Refused("not a directory"))
      case Some(_) => None
    }

  /** A source over `root`; refuses a missing root, a non-directory and a symbolic-link root. */
  def open(root: Path, bounds: SourceBounds): Either[StoreError, LocalRunSource] =
    if (root == null || bounds == null) Left(StoreError.Refused("root and bounds are required"))
    else {
      val abs = root.toAbsolutePath
      checkRoot(abs).toLeft(new LocalRunSource(abs, bounds))
    }
}
