package scholarchain.storage.parquet

/**
 * SE-P7-06 item 1: the pure raw-Snappy decoder on hand-built streams (format: varint length, then
 * literal tag 00 and copy tags 01, 10, 11). Every malformed stream is a Left, never an exception.
 */
class SnappyRawSuite extends munit.FunSuite {

  private def b(xs: Int*): Array[Byte] = xs.map(_.toByte).toArray
  private def ascii(s: String): Seq[Int] = s.getBytes("US-ASCII").toSeq.map(_.toInt)

  private def run(in: Array[Byte], expected: Int, max: Int = 1024) =
    SnappyRaw.decompress(in, 0, in.length, expected, max).map(a => new String(a, "US-ASCII"))

  test("a literal-only stream") {
    assertEquals(run(b((Seq(5, (5 - 1) << 2) ++ ascii("hello"))*), 5), Right("hello"))
  }

  test("an overlapping 1-byte-offset copy repeats the produced bytes") {
    // "abc" then copy length 6 offset 3: tag ((6-4) << 2) | 1, offset byte 3.
    assertEquals(
      run(b((Seq(9, 2 << 2) ++ ascii("abc") ++ Seq(0x09, 0x03))*), 9),
      Right("abcabcabc")
    )
  }

  test("2-byte and 4-byte offset copies") {
    // "ab" then copy length 4 offset 2 (tag ((4-1) << 2) | 2, offset 02 00).
    assertEquals(
      run(b((Seq(6, 1 << 2) ++ ascii("ab") ++ Seq(0x0e, 0x02, 0x00))*), 6),
      Right("ababab")
    )
    assertEquals(
      run(b((Seq(6, 1 << 2) ++ ascii("ab") ++ Seq(0x0f, 0x02, 0, 0, 0))*), 6),
      Right("ababab")
    )
  }

  test("a long literal with a 1-byte extra length") {
    val text = "x" * 70
    // tag 60 << 2 (one extra length byte), length - 1 = 69.
    assertEquals(run(b((Seq(70, 60 << 2, 69) ++ ascii(text))*), 70), Right(text))
  }

  test("malformed streams are refused") {
    def refused(in: Array[Byte], expected: Int, fragment: String, max: Int = 1024): Unit =
      run(in, expected, max) match {
        case Left(why) => assert(why.contains(fragment), why)
        case Right(s) => fail(s"decoded '$s'")
      }
    refused(b((Seq(5, 4 << 2) ++ ascii("hello"))*), 6, "differs from the page's uncompressed size")
    refused(b((Seq(5, 4 << 2) ++ ascii("hello"))*), 5, "exceeds the bound 4", max = 4)
    refused(b((Seq(5, 4 << 2) ++ ascii("hel"))*), 5, "literal runs past the input")
    refused(b((Seq(3, 4 << 2) ++ ascii("hello"))*), 3, "literal runs past the declared length")
    refused(b((Seq(9, 2 << 2) ++ ascii("abc") ++ Seq(0x09, 0x04))*), 9, "copy offset 4 outside")
    refused(b((Seq(9, 2 << 2) ++ ascii("abc") ++ Seq(0x09, 0x00))*), 9, "copy offset 0 outside")
    refused(b((Seq(7, 2 << 2) ++ ascii("abc") ++ Seq(0x09, 0x03))*), 7, "copy runs past")
    refused(b((Seq(6, 2 << 2) ++ ascii("abc"))*), 6, "stream ends after 3 of 6 bytes")
    refused(b(0x80, 0x80, 0x80, 0x80, 0x80, 0x01), 0, "longer than 5 bytes")
    refused(b(0x80), 0, "truncated stream")
    refused(b((Seq(6, 1 << 2) ++ ascii("ab") ++ Seq(0x0e, 0x02))*), 6, "truncated stream")
    assert(SnappyRaw.decompress(Array[Byte](1, 2), 1, 5, 0, 10).isLeft, "range outside")
  }
}
