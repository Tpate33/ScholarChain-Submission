package scholarchain.rag.semantic

import java.nio.charset.StandardCharsets

import scholarchain.core.{Canonical, ChunkId, DocumentType}

/** One chunk of a longer text. `chunkId` is core [[ChunkId.of]] with the chunk's ordinal. */
final case class TextChunk(ordinal: Int, chunkId: String, text: String, textSha256: String)

/**
 * RE-P8-01 item 2: the chunking policy for permitted abstracts or other longer free text
 * (`work-abstract`), version [[ChunkingPolicy.Version]]. UNUSED LOCALLY: the archived Phase 7 run
 * has no work-abstract rows, so no chunk exists yet; the policy is defined and unit-tested only.
 *
 * Deterministic algorithm (frozen under the version):
 *   1. The text is split into words at Unicode whitespace; empty words are dropped. Words are
 *      re-joined with one ASCII space (the only normalisation; no case folding, no Unicode
 *      normalisation).
 *   2. Greedy packing: a chunk takes words while its UTF-8 size stays at most `maxChunkBytes`. A
 *      single word longer than that is split at code-point boundaries into pieces of at most
 *      `maxChunkBytes` bytes.
 *   3. Overlap: every chunk after the first starts with the longest suffix of whole words of the
 *      previous chunk whose size (with separators) is at most `overlapBytes`, provided the chunk
 *      still adds at least one new word or piece; so the sequence always progresses.
 *   4. Bound: more than `maxChunks` chunks refuses the whole text (nothing is returned).
 *   5. IDs: `ChunkId.of(entityId, sourceVersion, documentType, Some(ordinal), chunk text)`,
 *      ordinals 0..n-1. The text hash is an input, so other text always yields another chunk ID.
 *
 * A text whose whole word sequence fits in `maxChunkBytes` is one chunk (ordinal 0).
 */
final case class ChunkingPolicy private (maxChunkBytes: Int, overlapBytes: Int, maxChunks: Int) {
  import ChunkingPolicy.bytes

  def chunk(
      entityId: String,
      sourceVersion: String,
      documentType: DocumentType,
      text: String
  ): Either[String, Vector[TextChunk]] =
    if (text == null) Left("chunking: text missing")
    else if (documentType == null) Left("chunking: documentType missing")
    else {
      val words = text.split("(?U)\\s+").toVector.filter(_.nonEmpty).flatMap(pieces)
      if (words.isEmpty) Left("chunking: text has no words")
      else
        pack(words).flatMap { groups =>
          groups.zipWithIndex.foldLeft[Either[String, Vector[TextChunk]]](Right(Vector.empty)) {
            case (acc, (ws, i)) =>
              acc.flatMap { out =>
                val t = ws.mkString(" ")
                ChunkId
                  .of(entityId, sourceVersion, documentType, Some(i), t)
                  .left
                  .map(e => s"chunking: ${e.message}")
                  .map(id => out :+ TextChunk(i, id, t, Canonical.sha256HexUtf8(t)))
              }
          }
        }
    }

  /** A word, or its code-point pieces when it alone exceeds `maxChunkBytes`. */
  private def pieces(w: String): Vector[String] =
    if (bytes(w) <= maxChunkBytes) Vector(w)
    else {
      val out = Vector.newBuilder[String]
      val cur = new StringBuilder
      var i = 0
      while (i < w.length) {
        val cp = w.codePointAt(i)
        val s = new String(Character.toChars(cp))
        if (bytes(cur.toString) + bytes(s) > maxChunkBytes) { out += cur.toString; cur.clear() }
        cur ++= s
        i += Character.charCount(cp)
      }
      if (cur.nonEmpty) out += cur.toString
      out.result()
    }

  private def size(ws: Vector[String]): Int =
    if (ws.isEmpty) 0 else ws.map(bytes).sum + ws.size - 1

  private def pack(words: Vector[String]): Either[String, Vector[Vector[String]]] = {
    val out = Vector.newBuilder[Vector[String]]
    var n = 0
    var next = 0
    var carry = Vector.empty[String]
    var over = false
    while (next < words.size && !over) {
      var cur = carry
      // Drop overlap words until the next new word fits (progress is guaranteed below).
      while (cur.nonEmpty && size(cur :+ words(next)) > maxChunkBytes) cur = cur.tail
      while (next < words.size && size(cur :+ words(next)) <= maxChunkBytes) {
        cur = cur :+ words(next)
        next += 1
      }
      n += 1
      if (n > maxChunks) over = true
      else {
        out += cur
        carry = overlapOf(cur)
      }
    }
    if (over) Left(s"chunking: more than $maxChunks chunks (refused)") else Right(out.result())
  }

  private def overlapOf(ws: Vector[String]): Vector[String] =
    if (overlapBytes == 0) Vector.empty
    else {
      var k = 0
      while (k < ws.size - 1 && size(ws.takeRight(k + 1)) <= overlapBytes) k += 1
      ws.takeRight(k)
    }
}

object ChunkingPolicy {

  val Version = "scholarchain.rag.chunking.v1"

  private[semantic] def bytes(s: String): Int = s.getBytes(StandardCharsets.UTF_8).length

  /**
   * A policy, or why not. Every value is configuration. `maxChunkBytes` must be at least 4 (one
   * code point always fits); `overlapBytes` must be below half of it, so each chunk adds new text.
   */
  def of(maxChunkBytes: Int, overlapBytes: Int, maxChunks: Int): Either[String, ChunkingPolicy] =
    if (maxChunkBytes < 4) Left("maxChunkBytes: must be >= 4")
    else if (overlapBytes < 0 || overlapBytes * 2 >= maxChunkBytes)
      Left("overlapBytes: must be >= 0 and below half of maxChunkBytes")
    else if (maxChunks < 1) Left("maxChunks: must be >= 1")
    else Right(new ChunkingPolicy(maxChunkBytes, overlapBytes, maxChunks))
}
