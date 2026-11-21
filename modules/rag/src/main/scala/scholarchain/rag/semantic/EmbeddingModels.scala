package scholarchain.rag.semantic

import scholarchain.storage.model.{EmbeddingModelVersion, EmbeddingPolicy, StoreError}
import scholarchain.storage.port.Embedder

/**
 * RE-P8-01 item 3: embedding model versioning on the rag side.
 *
 *   - Naming: a model version is storage's canonical label ([[EmbeddingModelVersion]], frozen form
 *     `[a-z0-9][a-z0-9._-]{0,127}`), taken from configuration, never a provider model ID. Any
 *     change of model, model revision or embedding setting (dimension, tokenizer, normalisation)
 *     must produce a new label. Synthetic and other non-production embedders carry the reserved
 *     prefix [[EmbeddingPolicy.SyntheticPrefix]].
 *   - Recording: storage records it in every vector's `modelVersion` metadata and in every vector
 *     key (`<index>/<modelVersion>/<inputId>`); rag records it in every [[SemanticContext]] and in
 *     each of its items.
 *   - Comparison: exact string equality of two canonical labels ([[same]]). Nothing is normalised
 *     (no case folding, no trimming), so a non-canonical spelling is refused instead of being
 *     matched.
 *   - Admission ([[admit]]): a query embedder is used only when its label is canonical, the runtime
 *     policy accepts it (`RealModelsOnly`, production, refuses every synthetic label) and its
 *     dimension equals the index's configured dimension.
 */
object EmbeddingModels {

  /** The canonical label, or `Refused`. */
  def check(modelVersion: String): Either[StoreError, String] =
    EmbeddingModelVersion.check(modelVersion)

  /** Whether two labels name the same model: both canonical and byte-equal. */
  def same(a: String, b: String): Boolean =
    EmbeddingModelVersion.problem(a).isEmpty && EmbeddingModelVersion.problem(b).isEmpty && a == b

  /** True for synthetic (non-production) labels; such vectors carry no real semantic meaning. */
  def isNonProduction(modelVersion: String): Boolean = EmbeddingPolicy.isSynthetic(modelVersion)

  /**
   * The embedder's label when it may serve queries of an index of `indexDimension` under `policy`.
   */
  def admit(
      embedder: Embedder,
      policy: EmbeddingPolicy,
      indexDimension: Int
  ): Either[StoreError, String] =
    if (embedder == null) Left(StoreError.Refused("embedder: missing"))
    else
      for {
        m <- check(embedder.modelVersion)
        _ <- EmbeddingPolicy.refusal(policy, Some(m)).map(StoreError.Refused(_)).toLeft(())
        _ <-
          if (embedder.dimension == indexDimension) Right(())
          else
            Left(
              StoreError.Refused(
                s"embedder $m: dimension ${embedder.dimension} is not the index dimension $indexDimension"
              )
            )
      } yield m
}
