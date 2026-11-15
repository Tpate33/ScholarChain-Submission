package scholarchain.entityresolution.evaluation

import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import java.nio.file.{Files, LinkOption, Path}

import scala.util.control.NonFatal

/**
 * The human-recorded freeze of a gold membership manifest (ER-P5-15; human decision 2026-11-13,
 * decisions-15 item 2: "a human-recorded manifest SHA-256 ... one hash-file format").
 *
 * '''Format (the only one accepted).''' The file `<membership file>.sha256`, next to the membership
 * file, holds exactly the output of `shasum -a 256 <membership file name>` run in that directory:
 * one line `<64 lowercase hex><two spaces><membership file name>` followed by a single LF, and
 * nothing else. For the gold layout:
 * {{{
 * gold/entity-resolution/<split>-v1/er-gold-membership.jsonl.sha256
 *   <64 lowercase hex>  er-gold-membership.jsonl\n
 * }}}
 * It can be checked independently with `shasum -a 256 -c er-gold-membership.jsonl.sha256` in that
 * directory.
 *
 * '''Refused''' (never repaired): an empty file; a UTF-8 byte-order mark; any CR (CRLF line end);
 * no final LF; more than one line (including a trailing blank line); bytes that are not UTF-8;
 * upper-case hex; a separator other than exactly two spaces (one space, a tab, the binary-mode
 * marker `*` of `shasum -b`); an escaped `shasum` line (leading backslash); whitespace or control
 * characters around the file name; a file name other than the membership file's base name; a file
 * that is a symbolic link, not a regular file, larger than [[MaxBytes]], not in the membership
 * file's directory, or not named exactly `<membership file name>.sha256` (ER-P5-16).
 *
 * The recorded value is only read here; the membership bytes are hashed by the evaluation itself
 * ([[scholarchain.entityresolution.review.GoldMembership.readFileWithSha256]]) and compared.
 */
object FreezeRecord {

  /** File-name suffix of a freeze record (appended to the membership file name). */
  val Suffix: String = ".sha256"

  /** Upper bound of a freeze file's size (one digest line and a file name). */
  val MaxBytes: Int = 4096

  private val Hex64 = "[0-9a-f]{64}".r
  private val AnyCaseHex64 = "[0-9a-fA-F]{64}".r

  private def shape(name: String): String =
    s"must be exactly one line '<64 lowercase hex><two spaces>$name' followed by one LF " +
      s"(the output of 'shasum -a 256 $name' run in the membership file's directory)"

  private def utf8(bytes: Array[Byte]): Either[String, String] =
    try
      Right(
        StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString
      )
    catch { case NonFatal(_) => Left("is not UTF-8") }

  /**
   * The recorded lowercase hex digest of a freeze record's bytes, or why the bytes are refused.
   *
   * @param expectedName
   *   the membership file's base name, which the record's file-name field must equal exactly
   */
  def parse(bytes: Array[Byte], expectedName: String): Either[String, String] = {
    val lf = '\n'.toByte
    val bom = bytes.length >= 3 && (bytes(0) & 0xff) == 0xef && (bytes(1) & 0xff) == 0xbb &&
      (bytes(2) & 0xff) == 0xbf
    if (bytes.isEmpty) Left("is empty; " + shape(expectedName))
    else if (bom) Left("starts with a UTF-8 byte-order mark; " + shape(expectedName))
    else if (bytes.contains('\r'.toByte))
      Left("contains a carriage return (CR or CRLF line end); " + shape(expectedName))
    else if (bytes.last != lf) Left("does not end with a line feed; " + shape(expectedName))
    else if (bytes.count(_ == lf) != 1)
      Left("holds more than one line; " + shape(expectedName))
    else
      utf8(bytes.dropRight(1)).left.map(_ + "; " + shape(expectedName)).flatMap { line =>
        val head = line.take(64)
        if (line.startsWith("\\"))
          Left(
            "is an escaped shasum line (a file name with a backslash or a newline); " +
              shape(expectedName)
          )
        else if (!Hex64.matches(head))
          if (AnyCaseHex64.matches(head)) Left("has upper-case hex; " + shape(expectedName))
          else Left(shape(expectedName))
        else if (line.length >= 66 && line.substring(64, 66) == " *")
          Left(
            "uses the binary-mode marker '*' (shasum -b); " + shape(expectedName)
          )
        else if (line.length < 67 || line.substring(64, 66) != "  ") Left(shape(expectedName))
        else {
          val name = line.substring(66)
          if (name != name.trim || name.exists(_.isControl))
            Left(
              "has whitespace or a control character around the file name; " +
                shape(expectedName)
            )
          else if (name != expectedName)
            Left(s"names '$name', not the membership file '$expectedName'")
          else Right(head)
        }
      }
  }

  /**
   * Reads and parses the freeze record of `membership`. Refused when the record is a symbolic link,
   * is not a regular file, is larger than [[MaxBytes]], is not in the membership file's directory,
   * or its bytes are refused by [[parse]].
   */
  def read(freeze: Path, membership: Path): Either[String, String] = {
    val where = s"freeze file $freeze"
    try {
      if (Files.isSymbolicLink(freeze)) Left(s"$where is a symbolic link; refused")
      else if (!Files.isRegularFile(freeze, LinkOption.NOFOLLOW_LINKS))
        Left(s"$where is missing or not a regular file")
      else if (Files.size(freeze) > MaxBytes)
        Left(s"$where is larger than $MaxBytes bytes")
      else {
        val name = membership.getFileName.toString
        val freezeDir = freeze.toAbsolutePath.normalize.getParent.toRealPath()
        val membershipDir = membership.toAbsolutePath.normalize.getParent.toRealPath()
        val expectedFile = name + Suffix
        if (freezeDir != membershipDir)
          Left(
            s"$where is not in the membership file's directory $membershipDir (the record is " +
              "written and checked with shasum in that directory)"
          )
        else if (freeze.getFileName.toString != expectedFile)
          Left(
            s"$where must be named exactly '$expectedFile' (the membership file name plus " +
              s"'$Suffix'), not '${freeze.getFileName}'"
          )
        else parse(Files.readAllBytes(freeze), name).left.map(e => s"$where $e")
      }
    } catch { case NonFatal(e) => Left(s"cannot read $where: ${e.getMessage}") }
  }
}
