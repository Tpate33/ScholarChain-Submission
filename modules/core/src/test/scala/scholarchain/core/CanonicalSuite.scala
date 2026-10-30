package scholarchain.core

import java.nio.charset.StandardCharsets.UTF_8

class CanonicalSuite extends munit.FunSuite {

  private def ascii(b: Array[Byte]): String = new String(b, UTF_8)

  test("netstring encoding is <byte length>:<bytes>, per field") {
    assertEquals(ascii(Canonical.encode(Seq("ab", ""))), "2:ab,0:,")
    assertEquals(ascii(Canonical.encode(Seq.empty)), "")
  }

  test("length prefixes count UTF-8 bytes, not characters") {
    assertEquals(ascii(Canonical.encode(Seq("é"))), "2:é,")
  }

  test("encoding is injective across field boundaries") {
    val joined = Canonical.encode(Seq("a,b"))
    val split = Canonical.encode(Seq("a", "b"))
    assert(!joined.sameElements(split))
    assertNotEquals(Canonical.hashFields("a:b", ""), Canonical.hashFields("a", "b"))
  }

  test("SHA-256 matches the FIPS 180-2 test vectors") {
    assertEquals(
      Canonical.sha256Hex(Array.emptyByteArray),
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    )
    assertEquals(
      Canonical.sha256HexUtf8("abc"),
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    )
  }

  test("hashFields is SHA-256 of the netstring encoding") {
    assertEquals(Canonical.hashFields("ab", ""), Canonical.sha256HexUtf8("2:ab,0:,"))
  }

  test("ordering is unsigned UTF-8 byte order, not UTF-16 order") {
    val bmpMax = "￿" // UTF-8 EF BF BF
    val emoji = "😀" // U+1F600, UTF-8 F0 9F 98 80; UTF-16 starts below U+FFFF
    assert(bmpMax.compareTo(emoji) > 0, "precondition: UTF-16 order puts the emoji first")
    assert(Canonical.compareUtf8(bmpMax, emoji) < 0)
    assert(Canonical.compareUtf8("Z", "a") < 0)
    assertEquals(Canonical.compareUtf8("same", "same"), 0)
    assert(Canonical.compareUtf8("ab", "abc") < 0)
  }

  test("hex helpers accept only lowercase hex of the exact length") {
    assert(Canonical.isHex64("0" * 64))
    assert(!Canonical.isHex64("A" * 64))
    assert(!Canonical.isHex64("0" * 63))
    assert(Canonical.isHex40("f" * 40))
    assert(!Canonical.isHex40("g" * 40))
  }
}
