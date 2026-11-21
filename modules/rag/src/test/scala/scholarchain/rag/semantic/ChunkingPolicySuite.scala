package scholarchain.rag.semantic

import java.nio.charset.StandardCharsets

import scholarchain.core.{ChunkId, DocumentType}

/** RE-P8-01 item 2: the chunking policy (unused locally: no abstracts in the archived run). */
class ChunkingPolicySuite extends munit.FunSuite {

  private val work = "researcher:sc:v1:" + "e" * 64
  private val sv = "paper-abstract-v1"
  private def b(s: String) = s.getBytes(StandardCharsets.UTF_8).length
  private def policy(max: Int, overlap: Int, n: Int) =
    ChunkingPolicy.of(max, overlap, n).fold(e => fail(e), identity)

  test("version pinned; invalid configurations refused") {
    assertEquals(ChunkingPolicy.Version, "scholarchain.rag.chunking.v1")
    assert(ChunkingPolicy.of(3, 0, 1).isLeft)
    assert(ChunkingPolicy.of(40, 20, 1).isLeft)
    assert(ChunkingPolicy.of(40, -1, 1).isLeft)
    assert(ChunkingPolicy.of(40, 5, 0).isLeft)
  }

  test("a short text is one chunk with the core chunk ID of ordinal 0; whitespace collapses") {
    val Right(Vector(c)) =
      policy(100, 10, 5).chunk(work, sv, DocumentType.WorkAbstract, " a  b\n\tc "): @unchecked
    assertEquals(c.text, "a b c")
    assertEquals(c.ordinal, 0)
    assertEquals(
      Right(c.chunkId),
      ChunkId.of(work, sv, DocumentType.WorkAbstract, Some(0), "a b c")
    )
  }

  test("long text: bounded chunks, ordinals in order, every word kept, overlap, deterministic") {
    val words = (0 until 200).map(i => s"w$i").toVector
    val text = words.mkString(" ")
    val p = policy(60, 12, 100)
    val Right(cs) = p.chunk(work, sv, DocumentType.WorkAbstract, text): @unchecked
    assert(cs.size > 1)
    assertEquals(cs.map(_.ordinal), cs.indices.toVector)
    cs.foreach(c => assert(b(c.text) <= 60, c.text))
    assertEquals(cs.flatMap(_.text.split(" ")).distinct, words)
    cs.zip(cs.tail).foreach { (x, y) =>
      val xs = x.text.split(" ").toVector
      val ys = y.text.split(" ").toVector
      // Overlap: y starts inside x and repeats x's last word, then adds new words.
      assert(xs.contains(ys.head) && ys.contains(xs.last), s"${x.text} | ${y.text}")
      assert(ys.last != xs.last)
    }
    assertEquals(p.chunk(work, sv, DocumentType.WorkAbstract, text), Right(cs))
    assertEquals(cs.map(_.chunkId).distinct.size, cs.size)
  }

  test("an over-long word is split at code-point boundaries") {
    val word = "é" * 30 // 60 bytes
    val Right(cs) = policy(16, 0, 10).chunk(work, sv, DocumentType.WorkAbstract, word): @unchecked
    cs.foreach(c => assert(b(c.text) <= 16))
    assertEquals(cs.map(_.text).mkString, word)
  }

  test("more chunks than the bound refuses the whole text; empty text refused") {
    val text = (0 until 100).map(i => s"w$i").mkString(" ")
    assert(policy(20, 0, 3).chunk(work, sv, DocumentType.WorkAbstract, text).isLeft)
    assert(policy(20, 0, 3).chunk(work, sv, DocumentType.WorkAbstract, "  ").isLeft)
    assert(policy(20, 0, 3).chunk("not-an-id", sv, DocumentType.WorkAbstract, "a").isLeft)
  }
}
