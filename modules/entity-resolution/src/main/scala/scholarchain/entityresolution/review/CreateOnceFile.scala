package scholarchain.entityresolution.review

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.{
  FileAlreadyExistsException,
  FileSystemException,
  Files,
  LinkOption,
  Path,
  StandardOpenOption
}

import scala.util.control.NonFatal

/**
 * Create-once, all-or-nothing file writes (RV-P5-04 N4). The text is written in full to a temporary
 * file in the target's directory and forced to storage; only then is the target created, by a hard
 * link (atomic, and it fails when the target already exists) or, where hard links are not
 * supported, by a move without replacement. The target therefore either does not exist or holds the
 * complete text: an I/O error never leaves a partial target, and an existing target is never
 * overwritten (also when it appears while the text is being written). The temporary file is always
 * removed.
 */
object CreateOnceFile {

  /** Writes the bytes of one temporary file (replaceable in tests to inject an I/O failure). */
  type Writer = (FileChannel, ByteBuffer) => Unit

  val DefaultWriter: Writer = (ch, buf) => while (buf.hasRemaining) ch.write(buf)

  private def exists(path: Path): Boolean = Files.exists(path, LinkOption.NOFOLLOW_LINKS)

  private def refusal(path: Path): String = s"$path exists; refusing to overwrite"

  /** Creates `path` with `text` (UTF-8) exactly once; refused when it exists. */
  def write(path: Path, text: String, writer: Writer = DefaultWriter): Either[String, Unit] =
    if (exists(path)) Left(refusal(path))
    else
      try {
        val target = path.toAbsolutePath.normalize
        val dir = target.getParent
        Files.createDirectories(dir)
        val tmp = Files.createTempFile(dir, "." + target.getFileName.toString + ".", ".part")
        try {
          val ch =
            FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
          try {
            writer(ch, ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8)))
            ch.force(true)
          } finally ch.close()
          try Files.createLink(target, tmp)
          catch {
            case e: FileAlreadyExistsException => throw e
            case _: UnsupportedOperationException | _: FileSystemException =>
              Files.move(tmp, target)
          }
          Right(())
        } finally Files.deleteIfExists(tmp)
      } catch {
        case _: FileAlreadyExistsException => Left(refusal(path))
        case NonFatal(e) => Left(s"cannot write $path: ${e.getMessage}")
      }
}
