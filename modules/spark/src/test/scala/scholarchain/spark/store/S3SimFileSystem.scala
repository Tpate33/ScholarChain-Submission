package scholarchain.spark.store

import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentLinkedQueue

import scala.jdk.CollectionConverters.*

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileStatus, FileUtil, Path => HPath, RawLocalFileSystem}

/**
 * A test Hadoop FileSystem with the rename semantics of S3A (SP-P6-04), over a local directory:
 * scheme `s3sim`, `s3sim://sim-bucket<absolute local path>`. Like S3A, it has no symbolic links
 * (`getFileLinkStatus` is `getFileStatus`) and no atomic rename: a rename is a recursive copy
 * followed by a delete of the source, so it is visible half-done. Unlike S3A, it refuses every
 * rename whose source or target is outside a Spark scratch prefix (a path segment starting with
 * `.spark-`) with an IOException, and logs every rename ([[S3SimFileSystem.renames]]). Spark's own
 * commit protocol renames inside the scratch prefix, which therefore still works; a publisher that
 * renamed anything under `run=` or `load=` would fail here. Test code only (never on a main
 * classpath).
 */
class S3SimFileSystem extends RawLocalFileSystem {

  override def getUri: URI = S3SimFileSystem.Uri

  override def getScheme: String = S3SimFileSystem.Scheme

  override def initialize(uri: URI, conf: Configuration): Unit = super.initialize(uri, conf)

  override def supportsSymlinks(): Boolean = false

  override def getFileLinkStatus(f: HPath): FileStatus = getFileStatus(f)

  override def rename(src: HPath, dst: HPath): Boolean = {
    val entry = s"${src.toUri.getPath} -> ${dst.toUri.getPath}"
    S3SimFileSystem.renames.add(entry)
    if (!S3SimFileSystem.inScratch(src) || !S3SimFileSystem.inScratch(dst))
      throw new IOException(s"s3sim: rename outside a Spark scratch prefix is refused: $entry")
    // Not atomic: a copy, then the delete of the source (S3A semantics).
    FileUtil.copy(this, src, this, dst, true, getConf)
  }
}

object S3SimFileSystem {
  val Scheme = "s3sim"

  /** One simulated bucket (an authority, as every S3A location has one). */
  val Bucket = "sim-bucket"
  val Uri: URI = URI.create(s"$Scheme://$Bucket/")

  /** Every rename requested from any instance, in order (`source -> target`). */
  val renames: ConcurrentLinkedQueue[String] = new ConcurrentLinkedQueue[String]

  def renameLog: Vector[String] = renames.asScala.toVector

  def inScratch(p: HPath): Boolean = p.toUri.getPath.split('/').exists(_.startsWith(".spark-"))

  /** Registers the scheme in `conf` (the SparkContext's Hadoop configuration in the suites). */
  def register(conf: Configuration): Unit =
    conf.set(s"fs.$Scheme.impl", classOf[S3SimFileSystem].getName)

  /** The `s3sim` location of an absolute local path (bucket [[Bucket]], key the local path). */
  def locationOf(p: java.nio.file.Path): StorePath =
    StorePath
      .parse(s"$Scheme://$Bucket" + p.toAbsolutePath.toString)
      .fold(e => throw new AssertionError(e), identity)
}
