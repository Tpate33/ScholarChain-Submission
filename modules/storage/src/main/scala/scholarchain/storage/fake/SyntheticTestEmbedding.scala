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
final class SyntheticTestEmbedding(val dimension: Int, val modelVersion: String) extends Embedder {
  require(dimension >= 1, "dimension must be >= 1")
  require(
    scholarchain.storage.model.EmbeddingModelVersion.problem(modelVersion).isEmpty &&
      modelVersion.startsWith(SyntheticTestEmbedding.Prefix),
    s"a synthetic model version is canonical and starts with ${SyntheticTestEmbedding.Prefix}"
  )

  /** The default synthetic model version [[SyntheticTestEmbedding.ModelVersion]]. */
  def this(dimension: Int) = this(dimension, SyntheticTestEmbedding.ModelVersion)

  def embed(texts: Vector[String]): Either[StoreError, Vector[Vector[Float]]] =
    if (texts == null || texts.contains(null)) Left(StoreError.Refused("texts: missing"))
    else Right(texts.map(SyntheticTestEmbedding.vectorOf(_, dimension, modelVersion)))
}

object SyntheticTestEmbedding {

  /** Reserved prefix of every synthetic model version; no real model may use it. */
  val Prefix: String = scholarchain.storage.model.EmbeddingPolicy.SyntheticPrefix

  /** The model version recorded on every synthetic vector. */
  val ModelVersion = "synthetic-test-only.v1"

  /**
   * SE-P8-01: the vector of `text` under another synthetic model version (to exercise model
   * versioning): the version is hashed in front of the text, so another version gives other
   * vectors. The default version keeps exactly the Phase 7 vectors.
   */
  def vectorOf(text: String, dimension: Int, modelVersion: String): Vector[Float] =
    if (modelVersion == ModelVersion) vectorOf(text, dimension)
    else vectorOf(modelVersion + "\n" + text, dimension)

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
