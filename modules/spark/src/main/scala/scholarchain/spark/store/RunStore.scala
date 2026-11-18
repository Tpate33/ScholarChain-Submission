package scholarchain.spark.store

import java.io.{FileNotFoundException, IOException, InputStream, OutputStream}
import java.nio.file.{Files, LinkOption, NoSuchFileException, Path, StandardOpenOption}
import java.nio.file.attribute.BasicFileAttributes
import java.util.Comparator

import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.control.NonFatal

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{ChecksumFileSystem, FileStatus, FileSystem}

import scholarchain.spark.input.TreeEntry

/** The NOFOLLOW kind and the size (0 unless regular) of one entry. */
final case class StoreStat(kind: TreeEntry.Kind, size: Long)

/** One entry of a directory listing: its name, NOFOLLOW kind and size (0 unless regular). */
final case class StoreEntry(name: String, kind: TreeEntry.Kind, size: Long)

/**
 * The file-system operations the verified reader and the publishers use (SP-P6-04), so the same
 * checks run on the local file system and on any Hadoop FileSystem (HDFS, S3A). Driver-side only: a
 * store is never captured by a Spark task (Spark reads and writes the data parts itself through the
 * URIs of the locations).
 *
 * Contract of every implementation:
 *   - [[stat]] and [[list]] never follow a symbolic link of the entry they report: a link is
 *     reported as [[TreeEntry.Kind.SymbolicLink]], a special file as [[TreeEntry.Kind.Other]];
 *   - [[stat]] is Right(None) only for an entry that is known to be absent; a failure to stat is a
 *     Left, so a caller can fail closed;
 *   - [[list]] is a Left when the directory cannot be listed (never an empty listing);
 *   - [[createNew]] is create-once: it fails when anything exists at the location and never
 *     replaces an existing entry (atomicity per implementation, see each one);
 *   - nothing is renamed by the store-generic code: [[supportsAtomicDirectoryRename]] says whether
 *     the publishers may use the local staged-rename protocol instead.
 */
trait RunStore {

  /** A short description for messages (no secret, no credential). */
  def describe: String

  /** True only for the local java.nio store, whose directory rename is one atomic rename(2). */
  def supportsAtomicDirectoryRename: Boolean

  def stat(p: StorePath): Either[String, Option[StoreStat]]

  /** The entries of `dir`, sorted by name; Left(cause) when it cannot be listed. */
  def list(dir: StorePath): Either[String, Vector[StoreEntry]]

  /** Opens a regular file for reading (throws IOException). */
  def open(p: StorePath): InputStream

  /**
   * Creates a new file (create-once; parent directories are created as the store needs them) and
   * opens it for writing (throws IOException, FileAlreadyExistsException when it exists).
   */
  def createNew(p: StorePath): OutputStream

  /** Deletes `p` and everything below it (no-op when absent; throws IOException). */
  def deleteRecursively(p: StorePath): Unit
}

/**
 * The local file system through java.nio (the behaviour before SP-P6-04): every stat uses
 * `NOFOLLOW_LINKS`, every read opens the file with `NOFOLLOW_LINKS` (the final path component is
 * never followed, atomically), create-once is `CREATE_NEW` (atomic O_EXCL). Only `file` locations.
 */
object NioRunStore extends RunStore {

  val describe: String = "local file system (java.nio, NOFOLLOW_LINKS)"
  val supportsAtomicDirectoryRename: Boolean = true

  private def local(p: StorePath): Path = p.localPath

  private def kindOf(a: BasicFileAttributes): TreeEntry.Kind =
    if (a.isSymbolicLink) TreeEntry.Kind.SymbolicLink
    else if (a.isDirectory) TreeEntry.Kind.Directory
    else if (a.isRegularFile) TreeEntry.Kind.Regular
    else TreeEntry.Kind.Other

  private[store] def nioStat(p: Path): Either[String, Option[StoreStat]] =
    try {
      val a = Files.readAttributes(p, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
      val k = kindOf(a)
      Right(Some(StoreStat(k, if (k == TreeEntry.Kind.Regular) a.size else 0L)))
    } catch {
      case _: NoSuchFileException => Right(None)
      case e: IOException => Left(e.toString)
    }

  def stat(p: StorePath): Either[String, Option[StoreStat]] =
    if (!p.isLocal) Left(s"$p is not a local location")
    else nioStat(local(p))

  def list(dir: StorePath): Either[String, Vector[StoreEntry]] =
    if (!dir.isLocal) Left(s"$dir is not a local location")
    else
      try {
        val d = local(dir)
        val names = Using.resource(Files.newDirectoryStream(d)) { s =>
          s.iterator().asScala.map(_.getFileName.toString).toVector.sorted
        }
        Right(names.map { n =>
          nioStat(d.resolve(n)) match {
            case Right(Some(st)) => StoreEntry(n, st.kind, st.size)
            case _ => StoreEntry(n, TreeEntry.Kind.Other, 0L)
          }
        })
      } catch { case e: IOException => Left(e.toString) }

  def open(p: StorePath): InputStream = Files.newInputStream(local(p), LinkOption.NOFOLLOW_LINKS)

  def createNew(p: StorePath): OutputStream = {
    val f = local(p)
    Option(f.getParent).foreach(Files.createDirectories(_))
    Files.newOutputStream(f, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
  }

  def deleteRecursively(p: StorePath): Unit = {
    val root = local(p)
    if (Files.exists(root, LinkOption.NOFOLLOW_LINKS))
      Using.resource(Files.walk(root)) { s =>
        s.sorted(Comparator.reverseOrder[Path]()).iterator().asScala.foreach { x =>
          Files.deleteIfExists(x)
          ()
        }
      }
  }
}

/**
 * Any Hadoop FileSystem (HDFS, S3A, the local file system through `file:` URIs), resolved per
 * location from `conf` (normally the SparkContext's Hadoop configuration, which carries the
 * cluster's file-system settings; no endpoint, bucket or credential is set here). Never renames.
 *
 * NOFOLLOW mapping:
 *   - `file`: Hadoop's RawLocalFileSystem follows symbolic links in `listStatus` and
 *     `getFileStatus`, and its `listStatus` silently drops an entry it cannot stat (a dangling
 *     link), so listings and stats of `file` locations are java.nio `NOFOLLOW_LINKS` operations
 *     ([[NioRunStore.list]], [[NioRunStore.stat]]); reads, writes and deletes go through
 *     RawLocalFileSystem, which opens without NOFOLLOW, so a link created between the stat and the
 *     open is followed (a check-then-open window; the archive is read-only by convention);
 *   - HDFS: `listStatus` returns a link entry unresolved (`isSymlink`) and [[stat]] uses
 *     `getFileLinkStatus`, which does not resolve the final component; symbolic links are disabled
 *     in Hadoop by default, and one that exists is refused by the callers; `open` resolves links
 *     (the same check-then-open window);
 *   - S3A (and other object stores): there are no symbolic links; a directory is a key prefix (or a
 *     directory marker object), every entry is a regular file or a directory.
 *
 * A checksummed local file system (`LocalFileSystem`, a `ChecksumFileSystem`) is unwrapped to its
 * raw file system, so the `.crc` sidecars of the Hadoop curated run are listed and read as the
 * regular files they are (the reader verifies them itself) and no sidecar is written for a
 * published file.
 *
 * Create-once: `FileSystem.create(path, overwrite = false)`. HDFS: the name node refuses an
 * existing path atomically. S3A: an existence check, then the object is uploaded and appears
 * atomically (complete or absent) at `close`; the check and the upload are not one atomic step, and
 * no conditional put is relied on. RawLocalFileSystem: an existence check, then the file is created
 * (not O_EXCL). The publishers therefore assume one writer per output root (documented on
 * [[scholarchain.spark.output.RunPublisher]]).
 */
final class HadoopRunStore(conf: Configuration) extends RunStore {

  val describe: String = "Hadoop FileSystem"
  val supportsAtomicDirectoryRename: Boolean = false

  private def fsOf(p: StorePath): FileSystem =
    FileSystem.get(p.hadoopUri, conf) match {
      case c: ChecksumFileSystem => c.getRawFileSystem
      case other => other
    }

  private def kindOf(st: FileStatus): TreeEntry.Kind =
    if (st.isSymlink) TreeEntry.Kind.SymbolicLink
    else if (st.isDirectory) TreeEntry.Kind.Directory
    else if (st.isFile) TreeEntry.Kind.Regular
    else TreeEntry.Kind.Other

  private def statOf(st: FileStatus): StoreStat = {
    val k = kindOf(st)
    StoreStat(k, if (k == TreeEntry.Kind.Regular) st.getLen else 0L)
  }

  def stat(p: StorePath): Either[String, Option[StoreStat]] =
    if (p.isLocal) NioRunStore.nioStat(p.localPath)
    else
      try Right(Some(statOf(fsOf(p).getFileLinkStatus(p.hadoopPath))))
      catch {
        case _: FileNotFoundException => Right(None)
        case NonFatal(e) => Left(e.toString)
      }

  def list(dir: StorePath): Either[String, Vector[StoreEntry]] =
    if (dir.isLocal) NioRunStore.list(dir)
    else
      try {
        val statuses = fsOf(dir).listStatus(dir.hadoopPath).toVector
        // listStatus of a file returns the file itself: refused, never read as a directory.
        if (statuses.exists(s => s.getPath.toUri.getPath == dir.path))
          Left(s"$dir is a file, not a directory")
        else
          Right(
            statuses
              .map { s =>
                val st = statOf(s)
                StoreEntry(s.getPath.getName, st.kind, st.size)
              }
              .sortBy(_.name)
          )
      } catch { case NonFatal(e) => Left(e.toString) }

  def open(p: StorePath): InputStream =
    try fsOf(p).open(p.hadoopPath)
    catch {
      case e: IOException => throw e
      case NonFatal(e) => throw new IOException(s"cannot open $p", e)
    }

  def createNew(p: StorePath): OutputStream =
    try fsOf(p).create(p.hadoopPath, false)
    catch {
      case e: IOException => throw e
      case NonFatal(e) => throw new IOException(s"cannot create $p", e)
    }

  def deleteRecursively(p: StorePath): Unit = {
    val fs = fsOf(p)
    if (fs.exists(p.hadoopPath) && !fs.delete(p.hadoopPath, true))
      throw new IOException(s"cannot delete $p")
  }
}
