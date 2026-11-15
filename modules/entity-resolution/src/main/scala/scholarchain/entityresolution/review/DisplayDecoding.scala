package scholarchain.entityresolution.review

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Percent-decoding for DETECTION only (blind-view masking, ER-P5-08, TE-P5-01 D5). A displayed
 * token can hide an identifier behind URL encoding, for example a redirect link whose `to=`
 * parameter is `https%3A%2F%2Fdblp.org%2Fpid%2F..`, possibly encoded more than once (`%252F`). The
 * masking rules inspect every decoded layer, and when any layer names a masked identifier the WHOLE
 * ORIGINAL token is replaced. A decoded string is never displayed.
 *
 * Decoding is total and never throws: a `%` not followed by two hex digits is kept as is, and byte
 * sequences that are not UTF-8 decode to U+FFFD. `+` is not turned into a space.
 */
object DisplayDecoding {

  /** Maximum number of decoding rounds (nested encodings deeper than this stay encoded). */
  val MaxLayers: Int = 8

  /** The value of an ASCII hex digit, or -1. */
  private def hex(c: Char): Int =
    if (c >= '0' && c <= '9') c - '0'
    else if (c >= 'a' && c <= 'f') c - 'a' + 10
    else if (c >= 'A' && c <= 'F') c - 'A' + 10
    else -1

  /** One round of percent-decoding (UTF-8). */
  def decodeOnce(s: String): String =
    if (s == null || s.indexOf('%') < 0) s
    else {
      val out = new ByteArrayOutputStream(s.length)
      var i = 0
      while (i < s.length) {
        val c = s.charAt(i)
        if (
          c == '%' && i + 2 < s.length &&
          hex(s.charAt(i + 1)) >= 0 && hex(s.charAt(i + 2)) >= 0
        ) {
          out.write(hex(s.charAt(i + 1)) * 16 + hex(s.charAt(i + 2)))
          i += 3
        } else {
          val cp = s.codePointAt(i)
          val n = Character.charCount(cp)
          out.write(s.substring(i, i + n).getBytes(StandardCharsets.UTF_8))
          i += n
        }
      }
      new String(out.toByteArray, StandardCharsets.UTF_8)
    }

  /**
   * The string and each successive decoded layer until decoding changes nothing (at most
   * [[MaxLayers]] rounds), distinct, original first.
   */
  def layers(s: String): Vector[String] =
    if (s == null) Vector.empty
    else {
      val b = Vector.newBuilder[String]
      b += s
      var cur = s
      var n = 0
      var done = false
      while (!done && n < MaxLayers) {
        val next = decodeOnce(cur)
        if (next == cur) done = true
        else { b += next; cur = next; n += 1 }
      }
      b.result().distinct
    }
}
