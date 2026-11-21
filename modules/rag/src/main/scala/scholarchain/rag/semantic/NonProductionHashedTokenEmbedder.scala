package scholarchain.rag.semantic

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import scholarchain.storage.model.StoreError
import scholarchain.storage.port.Embedder

/**
 * NON-PRODUCTION STAND-IN EMBEDDER (RE-P8-01 item 4; human decision 2026-11-21). For local
 * development retrieval smoke tests only. It is NOT an embedding model and its results are NEVER
 * evidence of real embedding quality, retrieval quality or production readiness.
 *
 * Method (frozen as [[NonProductionHashedTokenEmbedder.ModelVersion]]): the text is lower-cased
 * (ASCII only) and split into tokens of `[a-z0-9]` characters; every other character separates
 * tokens. Each token adds +1 or -1 to one of `dimension` buckets, both chosen from the first bytes
 * of SHA-256(`scholarchain.rag.hashed-token.v1\n` + token) (signed feature hashing). The bucket
 * vector is L2-normalised. Equal texts give equal vectors; token order does not matter; texts that
 * share tokens (for example the hex body of a researcher or community ID) get a smaller cosine
 * distance. No network, no model, no cost.
 *
 * Its model version starts with the synthetic prefix, so the production policy (`RealModelsOnly`)
 * refuses it in the vector loader and in the runtime vector reader, and only a `SyntheticTestOnly`
 * configuration accepts it. A text with no token is refused as `Permanent` (per text, as the
 * Embedder port requires).
 *
 * @param dimension
 *   the vector length; configuration, must equal the index's configured dimension
 */
final class NonProductionHashedTokenEmbedder(val dimension: Int) extends Embedder {
  require(dimension >= 1, "dimension must be >= 1")

  val modelVersion: String = NonProductionHashedTokenEmbedder.ModelVersion

  def embed(texts: Vector[String]): Either[StoreError, Vector[Vector[Float]]] =
    if (texts == null || texts.contains(null)) Left(StoreError.Refused("texts: missing"))
    else {
      val out = texts.map(NonProductionHashedTokenEmbedder.vectorOf(_, dimension))
      out.collectFirst { case None => () } match {
        case Some(_) =>
          Left(StoreError.Permanent("non-production embedder: a text has no [a-z0-9] token"))
        case None => Right(out.flatten)
      }
    }
}

object NonProductionHashedTokenEmbedder {

  /**
   * The model version of every vector it produces: synthetic prefix, "nonprod" in the name. A
   * change of the tokenizer, hash or weighting needs a new version.
   */
  val ModelVersion = "synthetic-nonprod-hashed-token-bag.v1"

  private val HashDomain = "scholarchain.rag.hashed-token.v1\n"

  /** The `[a-z0-9]` tokens of `text`, ASCII lower-cased, in order. */
  def tokens(text: String): Vector[String] =
    text
      .map(c => if (c >= 'A' && c <= 'Z') (c + 32).toChar else c)
      .split("[^a-z0-9]+")
      .toVector
      .filter(_.nonEmpty)

  /** The vector of one text, or None when it has no token. */
  def vectorOf(text: String, dimension: Int): Option[Vector[Float]] = {
    val ts = tokens(text)
    if (ts.isEmpty) None
    else {
      val acc = new Array[Double](dimension)
      ts.foreach { t =>
        val d = MessageDigest
          .getInstance("SHA-256")
          .digest((HashDomain + t).getBytes(StandardCharsets.UTF_8))
        val n = ((d(0) & 0xff).toLong << 24) | ((d(1) & 0xff).toLong << 16) |
          ((d(2) & 0xff).toLong << 8) | (d(3) & 0xff).toLong
        val bucket = (n % dimension).toInt
        val sign = if ((d(4) & 1) == 0) 1.0 else -1.0
        acc(bucket) += sign
      }
      val norm = math.sqrt(acc.map(x => x * x).sum)
      // All buckets cancelled out (possible only for tiny dimensions): a fixed unit vector.
      if (norm == 0.0) Some(Vector.fill(dimension)(0f).updated(0, 1f))
      else Some(acc.toVector.map(x => (x / norm).toFloat))
    }
  }
}
