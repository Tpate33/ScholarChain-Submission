package scholarchain.storage.parquet

/**
 * A pure decoder of the raw Snappy block format (the unframed format Parquet uses for SNAPPY
 * pages): a little-endian base-128 varint with the uncompressed length, then literal and copy
 * elements (tags 00 literal, 01 copy with 1-byte offset, 10 copy with 2-byte offset, 11 copy with
 * 4-byte offset). SE-P7-06 item 1: the storage Parquet reader decompresses with this decoder
 * instead of the snappy-java JNI library (advisories in DU-P7-01) or aircompressor, so no native
 * code and no Hadoop codec runs.
 *
 * Every bound is checked before a byte is written: the declared length must equal the length the
 * page header states and must not exceed the caller's bound; a literal or copy may not run past the
 * input or the output; a copy offset must be at least 1 and at most the bytes already produced; the
 * output must be filled exactly. Any violation is a `Left` with the reason; nothing is thrown.
 */
object SnappyRaw {

  private final class Malformed(why: String) extends RuntimeException(why, null, false, false)

  private def bad(why: String): Nothing = throw new Malformed(why)

  /**
   * Decompresses `input(offset until offset + length)`.
   *
   * @param expected
   *   the uncompressed size the Parquet page header declares
   * @param maxBytes
   *   the largest uncompressed size accepted (configuration)
   */
  def decompress(
      input: Array[Byte],
      offset: Int,
      length: Int,
      expected: Int,
      maxBytes: Int
  ): Either[String, Array[Byte]] =
    if (input == null || offset < 0 || length < 0 || offset > input.length - length)
      Left("input range outside the buffer")
    else if (expected < 0) Left(s"negative expected length $expected")
    else
      try Right(run(input, offset, offset + length, expected, maxBytes))
      catch { case m: Malformed => Left(m.getMessage) }

  private def run(in: Array[Byte], start: Int, end: Int, expected: Int, maxBytes: Int) = {
    var p = start
    def next(): Int = {
      if (p >= end) bad("truncated stream")
      val b = in(p) & 0xff
      p += 1
      b
    }
    def littleEndian(n: Int): Long = {
      if (n > end - p) bad("truncated stream")
      var v = 0L
      var i = 0
      while (i < n) { v |= (in(p + i) & 0xffL) << (8 * i); i += 1 }
      p += n
      v
    }

    // Declared length: at most 5 varint bytes (32 bits).
    var declared = 0L
    var shift = 0
    var more = true
    while (more) {
      if (shift > 28) bad("length varint longer than 5 bytes")
      val b = next()
      declared |= (b & 0x7fL) << shift
      shift += 7
      more = (b & 0x80) != 0
    }
    if (declared != expected.toLong)
      bad(s"declared length $declared differs from the page's uncompressed size $expected")
    if (declared > maxBytes.toLong) bad(s"declared length $declared exceeds the bound $maxBytes")

    val out = new Array[Byte](declared.toInt)
    var o = 0
    while (p < end) {
      val tag = next()
      tag & 3 match {
        case 0 =>
          val small = tag >>> 2
          val n: Long = if (small < 60) small.toLong else littleEndian(small - 59)
          val len = n + 1
          if (len > (end - p).toLong) bad("literal runs past the input")
          if (len > (out.length - o).toLong) bad("literal runs past the declared length")
          System.arraycopy(in, p, out, o, len.toInt)
          p += len.toInt
          o += len.toInt
        case kind =>
          val (len, off) = kind match {
            case 1 => (((tag >>> 2) & 7) + 4, (((tag >>> 5) & 7).toLong << 8) | next().toLong)
            case 2 => ((tag >>> 2) + 1, littleEndian(2))
            case _ => ((tag >>> 2) + 1, littleEndian(4))
          }
          if (off < 1 || off > o.toLong) bad(s"copy offset $off outside the $o bytes produced")
          if (len > out.length - o) bad("copy runs past the declared length")
          // Byte by byte: an offset smaller than the length repeats the copied bytes.
          var i = 0
          val from = o - off.toInt
          while (i < len) { out(o + i) = out(from + i); i += 1 }
          o += len
      }
    }
    if (o != out.length) bad(s"stream ends after $o of ${out.length} bytes")
    out
  }
}
