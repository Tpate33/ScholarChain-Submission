package scholarchain.storage.local

import java.nio.file.{Files, LinkOption, Path}
import scala.util.control.NonFatal
import upickle.default.ReadWriter
import upickle.implicits.allowUnknownKeys
import scholarchain.storage.aws.AwsClientSettings
import scholarchain.storage.aws.s3.S3ReaderSettings
import scholarchain.storage.model.{Bytes, ObjectRef, StoreError, StoreIds}
import scholarchain.storage.port.ObjectReader

@allowUnknownKeys(false)
final case class MaterializeEntry(
    bucket: String,
    key: String,
    sha256: String,
    byteCount: Long,
    localPath: String
) derives ReadWriter

@allowUnknownKeys(false)
final case class MaterializeManifest(
    schemaVersion: String,
    runId: String,
    region: String,
    apiCallTimeoutMillis: Long,
    apiCallAttemptTimeoutMillis: Long,
    retryMaxAttempts: Int,
    maxFiles: Int,
    maxTotalBytes: Long,
    maxObjectBytes: Int,
    entries: Vector[MaterializeEntry]
) derives ReadWriter

/**
 * Exact-object bootstrap for an exclusively owned worker directory. Never lists or writes S3.
 * Publication uses an atomic create-only hard link to a complete same-directory temporary file. The
 * worker filesystem must support hard links; an unsupported filesystem fails closed.
 */
object S3Materialize {
  val SchemaVersion = "scholarchain.s3-materialize.v1"
  private val noFollow = LinkOption.NOFOLLOW_LINKS
  private def refuse(s: String) = Left(StoreError.Refused("materialize: " + s))
  private def guarded[A](body: => Either[StoreError, A]): Either[StoreError, A] =
    try body
    catch { case NonFatal(e) => refuse("local operation failed: " + e.getClass.getSimpleName) }

  private def safePath(root: Path, relative: String): Either[StoreError, Path] = {
    val parts = Option(relative).getOrElse("").split("/", -1).toVector
    if (parts.isEmpty || parts.exists(p => p.isEmpty || p == "." || p == ".." || p.contains('\\')))
      refuse("localPath must be a normalized relative path")
    else {
      val dest = root.resolve(relative).normalize()
      if (!dest.startsWith(root) || dest == root) refuse("localPath escapes root")
      else {
        val ancestors = Iterator
          .iterate(Option(dest))(_.flatMap(p => Option(p.getParent)))
          .takeWhile(_.nonEmpty)
          .flatten
          .toVector
        if (ancestors.exists(Files.isSymbolicLink(_))) refuse("symlink in local path")
        else if (
          ancestors
            .drop(1)
            .exists(p => Files.exists(p, noFollow) && !Files.isDirectory(p, noFollow))
        )
          refuse("local parent is not a directory")
        else Right(dest)
      }
    }
  }

  def clientSettings(m: MaterializeManifest): Either[StoreError, AwsClientSettings] =
    AwsClientSettings.of(
      m.region,
      None,
      m.apiCallTimeoutMillis,
      m.apiCallAttemptTimeoutMillis,
      m.retryMaxAttempts
    )

  def readerSettings(m: MaterializeManifest): Either[StoreError, S3ReaderSettings] =
    S3ReaderSettings.of(m.entries.map(_.bucket).toSet, m.maxObjectBytes, 1)

  /** Validation only: no client construction, directory creation or object reads. */
  def validate(m: MaterializeManifest, root: Path): Either[StoreError, Vector[Path]] = guarded {
    if (m == null || m.entries == null || m.entries.contains(null) || root == null)
      refuse("manifest, entries and root required")
    else if (
      m.schemaVersion != SchemaVersion || (!scholarchain.core.StableId.isToken(m.runId) &&
        scholarchain.core.StableId.validate(scholarchain.core.IdKind.Run, m.runId, "runId").isLeft)
    )
      refuse("schemaVersion or runId invalid")
    else if (
      m.maxFiles < 1 || m.maxTotalBytes < 1 || m.entries.isEmpty || m.entries.size > m.maxFiles
    )
      refuse("entry count or total byte bound invalid")
    else if (
      m.entries.exists(e => e.byteCount < 0 || e.byteCount > m.maxObjectBytes) ||
      m.entries.map(e => BigInt(e.byteCount)).sum > BigInt(m.maxTotalBytes)
    )
      refuse("manifest exceeds byte bounds")
    else if (!root.isAbsolute || root != root.normalize() || !Files.isDirectory(root, noFollow))
      refuse("root must be an existing absolute directory")
    else
      for {
        _ <- clientSettings(m)
        _ <- readerSettings(m)
        paths <- m.entries.foldLeft[Either[StoreError, Vector[Path]]](Right(Vector.empty)) {
          (acc, e) =>
            for {
              xs <- acc
              _ <- StoreIds.hex64(e.sha256, "sha256")
              _ <-
                if (
                  e.key == null || e.key.isEmpty || e.key
                    .getBytes("UTF-8")
                    .length > S3ReaderSettings.MaxKeyBytes
                )
                  refuse("invalid exact object key")
                else Right(())
              p <- safePath(root.normalize(), e.localPath)
              _ <-
                if (
                  Files.exists(p, noFollow) && (!Files.isRegularFile(p, noFollow) ||
                    Files
                      .size(p) != e.byteCount || Bytes.of(Files.readAllBytes(p)).sha256 != e.sha256)
                )
                  refuse("existing local bytes differ")
                else Right(())
            } yield xs :+ p
        }
        _ <-
          if (
            paths.distinct.size != paths.size || paths
              .exists(p => paths.exists(q => p != q && p.startsWith(q)))
          )
            refuse("duplicate or overlapping local paths")
          else Right(())
      } yield paths
  }

  /** Explicitly called execution seam; reader is injectable and has no S3 writer capability. */
  def materialize(
      m: MaterializeManifest,
      root: Path,
      reader: ObjectReader
  ): Either[StoreError, Int] =
    validate(m, root).flatMap { paths =>
      m.entries.zip(paths).foldLeft[Either[StoreError, Int]](Right(0)) { case (acc, (e, dest)) =>
        acc.flatMap { count =>
          guarded {
            safePath(root.normalize(), e.localPath).flatMap { _ =>
              if (Files.exists(dest, noFollow)) {
                if (
                  Files.isRegularFile(dest, noFollow) && Files.size(dest) == e.byteCount &&
                  Bytes.of(Files.readAllBytes(dest)).sha256 == e.sha256
                ) Right(count)
                else refuse("existing local bytes differ")
              } else
                reader.get(ObjectRef(e.bucket, e.key)).flatMap {
                  case None => refuse("required object missing")
                  case Some((meta, bytes)) =>
                    if (
                      meta.ref != ObjectRef(e.bucket, e.key) || meta.byteCount != e.byteCount ||
                      meta.sha256 != e.sha256 || bytes.size.toLong != e.byteCount || bytes.sha256 != e.sha256
                    )
                      refuse("object digest or byte count differs from manifest")
                    else {
                      Files.createDirectories(dest.getParent)
                      safePath(root.normalize(), e.localPath).flatMap { _ =>
                        val temp = Files.createTempFile(dest.getParent, ".materialize-", ".tmp")
                        try {
                          Files.write(temp, bytes.toArray)
                          Files.createLink(dest, temp)
                          Right(count + 1)
                        } finally Files.deleteIfExists(temp)
                      }
                    }
                }
            }
          }
        }
      }
    }
}
