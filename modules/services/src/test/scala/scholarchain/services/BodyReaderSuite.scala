package scholarchain.services

import java.io.ByteArrayInputStream

/**
 * SV-INT-02 (CR-INT-04-6, services part): the transport's bounded body reader is overflow-safe for
 * every `maxBodyBytes` up to `Int.MaxValue`, and the server refuses a bound above the reader's cap.
 */
class BodyReaderSuite extends munit.FunSuite {

  private def bytes(n: Int): Array[Byte] = Array.tabulate(n)(i => (i % 251).toByte)

  test("CR-INT-04-6: a bound at or near Int.MaxValue reads a small body whole (no overflow)") {
    Vector(Int.MaxValue, Int.MaxValue - 1, Int.MaxValue - 9).foreach { max =>
      val (body, tooLarge) = HttpServerAdapter.readBounded(new ByteArrayInputStream(bytes(10)), max)
      assertEquals(body.toVector, bytes(10).toVector, s"max $max")
      assertEquals(tooLarge, false, s"max $max")
    }
  }

  test("the bound itself: max bytes are accepted, max + 1 are marked too large and cut to max") {
    val (exact, over) = HttpServerAdapter.readBounded(new ByteArrayInputStream(bytes(5)), 5)
    assertEquals((exact.length, over), (5, false))
    val (cut, tooLarge) = HttpServerAdapter.readBounded(new ByteArrayInputStream(bytes(6)), 5)
    assertEquals((cut.toVector, tooLarge), (bytes(5).toVector, true))
    // Across several 8 KiB reads: reading stops once max + 1 bytes were kept.
    val in = new ByteArrayInputStream(bytes(100000))
    val (big, large) = HttpServerAdapter.readBounded(in, 20000)
    assertEquals((big.toVector, large), (bytes(20000).toVector, true))
    assert(in.available() > 0, "the rest of the body was not read")
    val (empty, none) = HttpServerAdapter.readBounded(new ByteArrayInputStream(Array.empty), 1)
    assertEquals((empty.length, none), (0, false))
  }

  test("the server refuses a body bound above the reader's cap, before binding") {
    val s = HttpServerAdapter.Settings("127.0.0.1", 0, 8, 1, 1, Int.MaxValue, 1)
    val w = new ServiceWorld
    val rt = w.service()
    try {
      assertEquals(
        HttpServerAdapter.start(rt.router, s).left.toOption,
        Some(s"maxBodyBytes: at most ${HttpServerAdapter.MaxBodyBytes}")
      )
      assertEquals(HttpServerAdapter.MaxBodyBytes, Int.MaxValue - 9)
    } finally rt.close()
  }
}
