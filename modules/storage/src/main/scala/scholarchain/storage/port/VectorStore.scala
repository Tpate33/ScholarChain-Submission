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
   *   - CR-P8-01 M2, SE-P8-03 (CR-P8-02 m-B): one index can hold several models' vectors after a
   *     model change (keys carry the model version; `modelVersion` is non-filterable metadata, so
   *     no server-side filter exists), and a query vector is meaningful only in its own model's
   *     embedding space. This reader names no query model, so NO `query` through it ever returns a
   *     match: when the store's matches include a corrupt or policy-refused vector that error is
   *     returned (the integrity and policy diagnostics above, fail closed), and otherwise the query
   *     is `Refused` ("names no query model version"), also for an empty result. The only way to
   *     query is [[verified]] with the query's model version (`RuntimeStores.vectorsFor`), which
   *     returns only that model's vectors. `getVectors` stays available: keys name their model and
   *     every returned vector carries its own `modelVersion`, with the integrity and policy checks
   *     above.
   */
  def verified(r: VectorReader, policy: EmbeddingPolicy): VectorReader =
    checked(r, policy, None)

  /**
   * CR-P8-01 M2: the runtime vector reader for queries embedded with model `modelVersion` (the
   * embedder's canonical [[scholarchain.storage.model.EmbeddingModelVersion]]): every check of
   * [[verified]], plus
   *
   *   - `query`: matches whose stored `modelVersion` differs (another embedding space, or none) are
   *     EXCLUDED before the integrity re-read, so the result holds only this model's vectors and
   *     never one input twice (one key per index, model and input). The store's topK is applied
   *     before the exclusion (the field is not filterable): a result may hold fewer than `topK`
   *     matches while other models' vectors remain in the index;
   *   - `getVectors`: a requested vector of another model is REFUSED (`Refused`; keys name their
   *     model, so asking for one is a caller error).
   *
   * A non-canonical `modelVersion`, or one the policy refuses, refuses every read.
   */
  def verified(r: VectorReader, policy: EmbeddingPolicy, modelVersion: String): VectorReader = {
    require(modelVersion != null, "the query's model version is required")
    checked(r, policy, Some(modelVersion))
  }

  private def checked(
      r: VectorReader,
      policy: EmbeddingPolicy,
      queryModel: Option[String]
  ): VectorReader = {
    require(r != null && policy != null, "reader and policy are required")
    def modelOf(md: VectorMetadata): Option[String] =
      Option(md).filter(_.values != null).flatMap(_.string(MetadataField.ModelVersion))
    val modelRefusal: Option[String] = queryModel.flatMap(m =>
      scholarchain.storage.model.EmbeddingModelVersion
        .problem(m)
        .orElse(EmbeddingPolicy.refusal(policy, Some(m)))
    )
    def refusedModel(index: String) =
      modelRefusal.map(why => StoreError.Refused(s"runtime vector read of $index refused: $why"))
    def model(index: String, md: VectorMetadata): Either[StoreError, Unit] =
      EmbeddingPolicy.refusal(policy, modelOf(md)) match {
        case Some(why) => Left(StoreError.Refused(s"runtime vector read of $index refused: $why"))
        case None => Right(())
      }
    // CR-P8-01 M2: a requested vector of another model than the query's is refused.
    def sameModel(index: String, v: VectorRecord): Either[StoreError, Unit] =
      queryModel match {
        case Some(q) if !modelOf(v.metadata).contains(q) =>
          Left(
            StoreError.Refused(
              s"runtime vector read of $index refused: ${v.key} holds modelVersion " +
                s"${modelOf(v.metadata).getOrElse("(none)")}, the read names $q"
            )
          )
        case _ => Right(())
      }
    def all[A](xs: Vector[A])(f: A => Either[StoreError, Unit]): Either[StoreError, Vector[A]] =
      xs.iterator.map(f).collectFirst { case Left(e) => e }.toLeft(xs)
    def intact(index: String, vs: Vector[VectorRecord]) =
      all(vs)(v =>
        VectorDigest
          .verify(index, v)
          .flatMap(_ => model(index, v.metadata))
          .flatMap(_ => sameModel(index, v))
      )
    // SE-P8-03 (CR-P8-02 m-B): no query runs without a named query model.
    def unnamedQuery(index: String) =
      queryModel match {
        case None =>
          Some(
            StoreError.Refused(
              s"runtime vector query of $index refused: the reader names no query model version; " +
                "query with VectorReader.verified(reader, policy, modelVersion) " +
                "(RuntimeStores.vectorsFor(modelVersion))"
            )
          )
        case Some(_) => None
      }
    new VectorReader {
      def query(index: String, vector: Vector[Float], topK: Int, filter: Option[VectorFilter]) =
        refusedModel(index)
          .toLeft(())
          .flatMap(_ => r.query(index, vector, topK, filter))
          .flatMap { raw =>
            if (raw == null) Left(StoreError.Corrupt(s"$index: query returned no result vector"))
            else if (raw.contains(null)) Left(StoreError.Corrupt(s"$index: a null query match"))
            else {
              // CR-P8-01 M2: only the query's model; other models' matches are excluded. The
              // unnamed reader keeps every match only to check it (below); it never returns one.
              val ms = queryModel.fold(raw)(q => raw.filter(m => modelOf(m.metadata).contains(q)))
              val checkedMatches =
                if (ms.isEmpty) Right(ms)
                else
                  for {
                    _ <- all(ms)(m => model(index, m.metadata))
                    out <- reread(index, ms)
                  } yield out
              // SE-P8-03 (CR-P8-02 m-B): the unnamed reader NEVER returns a match: a corrupt or
              // policy-refused match is still reported as such (fail closed, SE-P7-11 and
              // CR-P7-11 m2 diagnostics), and otherwise the query is refused.
              checkedMatches.flatMap(out => unnamedQuery(index).toLeft(out))
            }
          }
      private def reread(index: String, ms: Vector[VectorMatch]) =
        for {
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
      def getVectors(index: String, keys: Vector[String]) =
        refusedModel(index).toLeft(()).flatMap(_ => r.getVectors(index, keys)).flatMap { vs =>
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
