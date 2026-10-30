package scholarchain.core

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Canonical encoding, hashing, and ordering used by every stable ID.
 *
 * Encoding (frozen, version 1): a sequence of fields is encoded as the concatenation of one
 * netstring per field, `<decimal byte length>:<UTF-8 bytes>,`. The length is the number of UTF-8
 * bytes, written in ASCII decimal without leading zeros. Example: fields ("ab", "") encode to the
 * ASCII bytes `2:ab,0:,`. Length prefixes make the encoding injective: no two different field
 * sequences produce the same bytes, whatever characters the fields contain.
 *
 * Hash: SHA-256 (JDK `MessageDigest`) over the encoding, rendered as 64 lowercase hex characters.
 *
 * Ordering: unsigned lexicographic comparison of the UTF-8 bytes. This is independent of JVM locale
 * and of UTF-16 surrogate ordering.
 */
object Canonical {

  /** Encodes fields as concatenated netstrings. */
  def encode(fields: Seq[String]): Array[Byte] = {
    val out = new ByteArrayOutputStream()
    fields.foreach { field =>
      val bytes = field.getBytes(StandardCharsets.UTF_8)
      out.write(bytes.length.toString.getBytes(StandardCharsets.US_ASCII))
      out.write(':'.toInt)
      out.write(bytes)
      out.write(','.toInt)
    }
    out.toByteArray
  }

  /** SHA-256 of the given bytes as 64 lowercase hex characters. */
  def sha256Hex(bytes: Array[Byte]): String = {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val sb = new StringBuilder(digest.length * 2)
    digest.foreach { b =>
      val v = b & 0xff
      sb.append(Hex.charAt(v >>> 4)).append(Hex.charAt(v & 0x0f))
    }
    sb.toString
  }

  /** SHA-256 hex of the UTF-8 bytes of `text` (used for text hashes inside chunk IDs). */
  def sha256HexUtf8(text: String): String = sha256Hex(text.getBytes(StandardCharsets.UTF_8))

  /** SHA-256 hex of the canonical encoding of `fields`. */
  def hashFields(fields: String*): String = sha256Hex(encode(fields))

  /** Unsigned byte-wise comparison of the UTF-8 encodings of `a` and `b`. */
  def compareUtf8(a: String, b: String): Int =
    java.util.Arrays.compareUnsigned(
      a.getBytes(StandardCharsets.UTF_8),
      b.getBytes(StandardCharsets.UTF_8)
    )

  /** True when `s` is exactly 64 lowercase hex characters. */
  def isHex64(s: String): Boolean = s.length == 64 && s.forall(c => Hex.indexOf(c.toInt) >= 0)

  /** True when `s` is exactly 40 lowercase hex characters (a full git commit SHA). */
  def isHex40(s: String): Boolean = s.length == 40 && s.forall(c => Hex.indexOf(c.toInt) >= 0)

  private val Hex = "0123456789abcdef"
}
