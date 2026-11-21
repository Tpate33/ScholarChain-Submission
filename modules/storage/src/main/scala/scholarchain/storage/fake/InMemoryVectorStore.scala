package scholarchain.storage.fake

import scala.collection.mutable

import scholarchain.storage.model.{
  DistanceMetric,
  IndexInfo,
  MetadataField,
  PutOutcome,
  StoreError,
  VectorFilter,
  VectorFilters,
  VectorIndexSpec,
  VectorLimits,
  VectorMatch,
  VectorMetadataRules,
  VectorPutResult,
  VectorRecord
}
import scholarchain.storage.port.VectorStore

/**
 * In-memory S3 Vectors with the loader's GetVectors-compare put semantics (SE-P7-01). Indexes are
 * seeded by the constructor (creation is Terraform). Per batch: an unknown index or more than
 * `maxBatchSize` records is a `Left`. Per record: a dimension mismatch, a non-finite component or
 * metadata over the service limits is `Refused`; an absent key is `Written`; an equal record is
 * `AlreadyPresent`; a different record under the key (other text hash, data or metadata) is
 * `Conflict` and nothing is overwritten. Queries validate the filter against the index schema and
 * honour non-filterable keys. No delete exists. Not thread-safe: tests only.
 */
final class InMemoryVectorStore(indexes: Vector[(VectorIndexSpec, IndexInfo)], limits: VectorLimits)
    extends VectorStore {
  require(VectorLimits.problems(limits).isEmpty, VectorLimits.problems(limits).mkString("; "))
  private val byName: Map[String, (VectorIndexSpec, IndexInfo)] =
    indexes.map(p => p._2.name -> p).toMap
  private val data: Map[String, mutable.TreeMap[String, VectorRecord]] =
    byName.keys.map(_ -> mutable.TreeMap.empty[String, VectorRecord]).toMap
  private val faults = Faults()

  /** The next put batches fail with these errors, in order, before touching the store. */
  def failNextWrites(errors: StoreError*): Unit = faults.enqueue(errors*)

  def count(index: String): Int = data.get(index).map(_.size).getOrElse(0)

  private def indexOf(name: String): Either[StoreError, (VectorIndexSpec, IndexInfo)] =
    byName.get(name).toRight(StoreError.Refused(s"index: unknown '$name'"))

  def describe(index: String): Either[StoreError, Option[IndexInfo]] =
    Right(byName.get(index).map(_._2))

  def putVectors(
      index: String,
      records: Vector[VectorRecord]
  ): Either[StoreError, Vector[VectorPutResult]] =
    faults.next() match {
      case Some(e) => Left(e)
      case None =>
        for {
          ix <- indexOf(index)
          _ <-
            if (records != null && records.nonEmpty && records.size <= limits.maxBatchSize)
              Right(())
            else Left(StoreError.Refused(s"batch: size must be 1..${limits.maxBatchSize}"))
          _ <-
            if (records.map(_.key).distinct.size == records.size) Right(())
            else Left(StoreError.Refused("batch: duplicate keys in one batch"))
        } yield records.map(r =>
          VectorPutResult(Option(r).map(_.key).orNull, putOne(ix._2, data(index), r))
        )
    }

  private def putOne(
      info: IndexInfo,
      rows: mutable.TreeMap[String, VectorRecord],
      r: VectorRecord
  ): Either[StoreError, PutOutcome] =
    if (r == null || r.key == null || r.key.isEmpty || r.data == null)
      Left(StoreError.Refused("record: key and data are required"))
    else if (r.data.size != info.dimension)
      Left(StoreError.Refused(s"${r.key}: dimension ${r.data.size} is not ${info.dimension}"))
    else if (r.data.exists(f => f.isNaN || f.isInfinite))
      Left(StoreError.Refused(s"${r.key}: non-finite component"))
    else {
      val limitProblems = VectorMetadataRules.limitProblems(r.metadata, limits)
      if (limitProblems.nonEmpty)
        Left(StoreError.Refused(s"${r.key}: ${limitProblems.mkString("; ")}"))
      else
        rows.get(r.key) match {
          case None =>
            rows(r.key) = r
            Right(PutOutcome.Written)
          case Some(existing) if existing == r => Right(PutOutcome.AlreadyPresent)
          case Some(existing) =>
            val was = existing.metadata.string(MetadataField.TextSha256).orNull
            val now = r.metadata.string(MetadataField.TextSha256).orNull
            Left(
              StoreError.Conflict(
                s"${r.key}: holds textSha256 $was, refused textSha256 $now (or other data)"
              )
            )
        }
    }

  def getVectors(index: String, keys: Vector[String]): Either[StoreError, Vector[VectorRecord]] =
    for {
      _ <- indexOf(index)
      _ <-
        if (keys != null && keys.size <= limits.maxBatchSize) Right(())
        else Left(StoreError.Refused(s"keys: at most ${limits.maxBatchSize}"))
    } yield keys.flatMap(data(index).get)

  def query(
      index: String,
      vector: Vector[Float],
      topK: Int,
      filter: Option[VectorFilter]
  ): Either[StoreError, Vector[VectorMatch]] =
    for {
      ix <- indexOf(index)
      (spec, info) = ix
      _ <-
        if (topK >= 1 && topK <= limits.maxTopK) Right(())
        else Left(StoreError.Refused(s"topK: $topK is outside 1..${limits.maxTopK}"))
      _ <-
        if (vector != null && vector.size == info.dimension) Right(())
        else Left(StoreError.Refused(s"query vector: dimension must be ${info.dimension}"))
      _ <- filter match {
        case None => Right(())
        case Some(f) =>
          val p = VectorFilters.problems(spec, f) ++ nonFilterable(info, f)
          if (p.isEmpty) Right(()) else Left(StoreError.Refused(p.mkString("; ")))
      }
    } yield data(index).values.toVector
      .filter(r => filter.forall(VectorFilters.matches(_, r.metadata)))
      .map(r => VectorMatch(r.key, distance(info.distance, vector, r.data), r.metadata))
      .sortBy(m => (m.distance, m.key))
      .take(topK)

  private def nonFilterable(info: IndexInfo, f: VectorFilter): Vector[String] = f match {
    case VectorFilter.Eq(m, _) => keyCheck(info, m)
    case VectorFilter.In(m, _) => keyCheck(info, m)
    case VectorFilter.Range(m, _, _) => keyCheck(info, m)
    case VectorFilter.And(fs) => fs.flatMap(nonFilterable(info, _))
  }

  private def keyCheck(info: IndexInfo, m: MetadataField): Vector[String] =
    if (m != null && info.nonFilterableKeys.contains(m.key))
      Vector(s"${m.key}: declared non-filterable on ${info.name}")
    else Vector.empty

  private def distance(metric: DistanceMetric, a: Vector[Float], b: Vector[Float]): Double =
    metric match {
      case DistanceMetric.Euclidean =>
        math.sqrt(a.indices.map(i => math.pow((a(i) - b(i)).toDouble, 2)).sum)
      case DistanceMetric.Cosine =>
        val dot = a.indices.map(i => a(i).toDouble * b(i)).sum
        val na = math.sqrt(a.map(x => x.toDouble * x).sum)
        val nb = math.sqrt(b.map(x => x.toDouble * x).sum)
        if (na == 0.0 || nb == 0.0) 1.0 else 1.0 - dot / (na * nb)
    }
}
