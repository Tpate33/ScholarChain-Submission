package scholarchain.storage.port

import scholarchain.storage.model.{
  EmbeddingPolicy,
  IndexInfo,
  MetadataField,
  StoreError,
  VectorDigest,
  VectorMetadata,
  VectorFilter,
  VectorMatch,
  VectorPutResult,
  VectorRecord
}

/** Index inspection (creation and deletion are Terraform; storage never creates or deletes). */
trait IndexInspector {
  def describe(index: String): Either[StoreError, Option[IndexInfo]]
}

/** Read side of S3 Vectors. */
trait VectorReader {

  /** The `topK` closest vectors that satisfy `filter`, closest first. */
  def query(
      index: String,
      vector: Vector[Float],
      topK: Int,
      filter: Option[VectorFilter]
  ): Either[StoreError, Vector[VectorMatch]]

  def getVectors(index: String, keys: Vector[String]): Either[StoreError, Vector[VectorRecord]]
}

/**
 * Write side of S3 Vectors: one batch put with GetVectors-compare semantics (SE-P7-01). For every
 * record the store compares the existing vector under the key: none is `Written`; equal data and
 * metadata is `AlreadyPresent`; anything else is `Conflict` and nothing is overwritten. A
 * batch-level failure (unknown index, oversize batch, throttling) is a `Left`; per-record failures
 * are in the results. There is no delete.
 */
trait VectorWriter {
  def putVectors(
      index: String,
      records: Vector[VectorRecord]
  ): Either[StoreError, Vector[VectorPutResult]]
}

/** Both sides plus inspection, for loaders. */
trait VectorStore extends VectorReader with VectorWriter with IndexInspector

/**
 * SE-P7-12 (CR-P7-13 m2): the only vector-reader facade storage provides is [[verified]]. The
 * former unchecked `readOnly` facade was removed, so storage offers no runtime path that serves
 * stored vectors without the integrity and model-policy checks.
 */
object VectorReader {

  /**
   * The runtime vector reader (read-only facade; nothing is ever returned from a refused read):
   *
   *   - SE-P7-11 integrity (human requirement 2026-11-20): every vector a read would return is
   *     checked with [[VectorDigest.verify]]: its stored floats must match its own stored
   *     `dataSha256`; a missing or malformed digest or a mismatch is `Corrupt`, so a vector whose
   *     values changed is never served even when its metadata is intact. QueryVectors returns no
   *     floats, so a query's matches are re-read with one GetVectors call over the match keys (at
   *     most `topK` keys: the configured topK must not exceed the GetVectors key cap, otherwise the
   *     store refuses the call and the read fails closed); every match must be returned, with the
   *     same metadata, and intact.
   *   - CR-P7-11 m2 model policy: a vector the `policy` does not accept by its `modelVersion`
   *     ([[EmbeddingPolicy.refusal]]) refuses the read (`Refused`): under `RealModelsOnly`
   *     (production) a synthetic test vector, or one without a model version, never reaches a
   *     caller.
   */
  def verified(r: VectorReader, policy: EmbeddingPolicy): VectorReader = {
    require(r != null && policy != null, "reader and policy are required")
    def model(index: String, md: VectorMetadata): Either[StoreError, Unit] =
      EmbeddingPolicy.refusal(
        policy,
        Option(md).filter(_.values != null).flatMap(_.string(MetadataField.ModelVersion))
      ) match {
        case Some(why) => Left(StoreError.Refused(s"runtime vector read of $index refused: $why"))
        case None => Right(())
      }
    def all[A](xs: Vector[A])(f: A => Either[StoreError, Unit]): Either[StoreError, Vector[A]] =
      xs.iterator.map(f).collectFirst { case Left(e) => e }.toLeft(xs)
    def intact(index: String, vs: Vector[VectorRecord]) =
      all(vs)(v => VectorDigest.verify(index, v).flatMap(_ => model(index, v.metadata)))
    new VectorReader {
      def query(index: String, vector: Vector[Float], topK: Int, filter: Option[VectorFilter]) =
        r.query(index, vector, topK, filter).flatMap { ms =>
          if (ms == null) Left(StoreError.Corrupt(s"$index: query returned no result vector"))
          else if (ms.isEmpty) Right(ms)
          else
            for {
              _ <- all(ms)(m =>
                if (m == null) Left(StoreError.Corrupt(s"$index: a null query match"))
                else model(index, m.metadata)
              )
              got <- r.getVectors(index, ms.map(_.key))
              byKey = Option(got)
                .getOrElse(Vector.empty)
                .filter(_ != null)
                .map(v => v.key -> v)
                .toMap
              out <- all(ms)(m =>
                byKey.get(m.key) match {
                  case None =>
                    Left(StoreError.Corrupt(s"$index ${m.key}: matched but not readable (refused)"))
                  case Some(v) if v.metadata != m.metadata =>
                    Left(
                      StoreError
                        .Corrupt(s"$index ${m.key}: query and get metadata differ (refused)")
                    )
                  case Some(v) => VectorDigest.verify(index, v)
                }
              )
            } yield out
        }
      def getVectors(index: String, keys: Vector[String]) =
        r.getVectors(index, keys).flatMap { vs =>
          if (vs == null) Left(StoreError.Corrupt(s"$index: get returned no result vector"))
          else intact(index, vs)
        }
    }
  }
}

object IndexInspector {

  /** A facade exposing only inspection. */
  def readOnly(r: IndexInspector): IndexInspector = new IndexInspector {
    def describe(index: String) = r.describe(index)
  }
}
