package scholarchain.storage.fake

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import scholarchain.storage.model.StoreError
import scholarchain.storage.port.Embedder

/**
 * SYNTHETIC TEST VECTORS ONLY (SE-P7-09; human decision 2026-11-20 item 3). NOT an embedding model:
 * the vector of a text is SHA-256 in counter mode over the text's UTF-8 bytes, mapped to [-1, 1]
 * and L2-normalized. It is deterministic and carries no meaning; nearest neighbours under it are
 * arbitrary. Real embeddings are Phase 8 and remain OUTSTANDING.
 *
 * Its model version starts with [[SyntheticTestEmbedding.Prefix]], so every vector it produces is
 * labelled synthetic in its `modelVersion` metadata, and [[scholarchain.storage.load.VectorLoad]]
 * refuses it unless the load configuration explicitly says `EmbeddingPolicy.SyntheticTestOnly` (a
 * production configuration uses `RealModelsOnly`, which refuses it).
 */
final class SyntheticTestEmbedding(val dimension: Int) extends Embedder {
  require(dimension >= 1, "dimension must be >= 1")

  val modelVersion: String = SyntheticTestEmbedding.ModelVersion

  def embed(texts: Vector[String]): Either[StoreError, Vector[Vector[Float]]] =
    if (texts == null || texts.contains(null)) Left(StoreError.Refused("texts: missing"))
    else Right(texts.map(SyntheticTestEmbedding.vectorOf(_, dimension)))
}

object SyntheticTestEmbedding {

  /** Reserved prefix of every synthetic model version; no real model may use it. */
  val Prefix: String = scholarchain.storage.model.EmbeddingPolicy.SyntheticPrefix

  /** The model version recorded on every synthetic vector. */
  val ModelVersion = "synthetic-test-only.v1"

  def vectorOf(text: String, dimension: Int): Vector[Float] = {
    val bytes = text.getBytes(StandardCharsets.UTF_8)
    val raw = (0 until dimension).map { i =>
      val md = MessageDigest.getInstance("SHA-256")
      md.update(bytes)
      md.update(BigInt(i / 8).toByteArray)
      val d = md.digest()
      val j = (i % 8) * 4
      val n = ((d(j) & 0xff) << 24) | ((d(j + 1) & 0xff) << 16) | ((d(j + 2) & 0xff) << 8) |
        (d(j + 3) & 0xff)
      (n.toDouble / Int.MaxValue.toDouble).max(-1.0).min(1.0)
    }
    val norm = math.sqrt(raw.map(x => x * x).sum)
    if (norm == 0.0) Vector.fill(dimension)(0f).updated(0, 1f)
    else raw.map(x => (x / norm).toFloat).toVector
  }
}
