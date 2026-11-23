package scholarchain.storage.aws.s3vectors

import scala.collection.immutable.SortedMap
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.core.document.Document
import software.amazon.awssdk.services.s3vectors.S3VectorsClient
import software.amazon.awssdk.services.s3vectors.model.{
  GetIndexRequest,
  GetIndexResponse,
  GetVectorsRequest,
  GetVectorsResponse,
  QueryVectorsRequest,
  QueryVectorsResponse,
  VectorData
}

import scholarchain.storage.aws.AwsErrors
import scholarchain.storage.model.{
  DistanceMetric,
  IndexInfo,
  MetaType,
  MetaValue,
  MetadataField,
  StoreError,
  VectorFilter,
  VectorFilters,
  VectorIndexSpec,
  VectorIndexes,
  VectorMatch,
  VectorMetadata,
  VectorRecord
}
import scholarchain.storage.port.{IndexInspector, VectorReader}

/**
 * SE-INT-04 (A5): the narrow seam over the SDK `S3VectorsClient`: QueryVectors, GetVectors and
 * GetIndex only. No PutVectors, delete, create or list operation is reachable. (IAM should grant
 * s3vectors:QueryVectors, s3vectors:GetVectors and s3vectors:GetIndex on the one vector bucket:
 * routing item for infra-engineer.)
 */
trait S3VectorsCalls {
  def queryVectors(request: QueryVectorsRequest): QueryVectorsResponse
  def getVectors(request: GetVectorsRequest): GetVectorsResponse
  def getIndex(request: GetIndexRequest): GetIndexResponse
}

object S3VectorsCalls {
  def of(client: S3VectorsClient): S3VectorsCalls = {
    require(client != null, "client is required")
    new S3VectorsCalls {
      def queryVectors(request: QueryVectorsRequest) = client.queryVectors(request)
      def getVectors(request: GetVectorsRequest) = client.getVectors(request)
      def getIndex(request: GetIndexRequest) = client.getIndex(request)
    }
  }
}

/**
 * SE-INT-04: bounds of the S3 Vectors reader (caller-supplied; no defaults).
 *
 * @param vectorBucket
 *   the one vector bucket (configuration `storage.vectorBucket`)
 * @param maxTopK
 *   1..100: QueryVectors returns at most 100 results per page (Q13), so storage caps topK at 100
 *   and reads exactly ONE page (no nextToken is followed; a response that carries one is `Corrupt`)
 * @param maxGetKeys
 *   1..100 (GetVectors key cap, Q14) and at least `maxTopK`, because the runtime facade
 *   ([[VectorReader.verified]]) re-reads every match of a query with one GetVectors call
 */
final case class S3VectorsReaderSettings private (
    vectorBucket: String,
    maxTopK: Int,
    maxGetKeys: Int
)

object S3VectorsReaderSettings {

  /** Results per QueryVectors page (Q13/Q17, checked 2026-11-22). */
  val MaxTopKPerPage = 100

  /** Keys per GetVectors request (Q14/Q17, checked 2026-11-22). */
  val MaxGetKeys = 100

  private val BucketName = "^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$".r

  def of(
      vectorBucket: String,
      maxTopK: Int,
      maxGetKeys: Int
  ): Either[StoreError, S3VectorsReaderSettings] =
    if (vectorBucket == null || !BucketName.matches(vectorBucket))
      Left(StoreError.Refused("s3vectors: vectorBucket is not a vector bucket name"))
    else if (maxTopK < 1 || maxTopK > MaxTopKPerPage)
      Left(StoreError.Refused(s"s3vectors: maxTopK must be in 1..$MaxTopKPerPage"))
    else if (maxGetKeys < 1 || maxGetKeys > MaxGetKeys)
      Left(StoreError.Refused(s"s3vectors: maxGetKeys must be in 1..$MaxGetKeys"))
    else if (maxGetKeys < maxTopK)
      Left(StoreError.Refused("s3vectors: maxGetKeys must be at least maxTopK"))
    else Right(new S3VectorsReaderSettings(vectorBucket, maxTopK, maxGetKeys))
}

/**
 * SE-INT-04 (A5, D5), SE-INT-05 (CR-INT-07-1): the storage [[VectorFilter]] as an S3 Vectors filter
 * document (Q15, Q17, checked 2026-11-22). Only shapes whose documented semantics equal
 * [[VectorFilters.matches]] are translated; every other shape is `Refused`:
 *
 *   - `Eq(f, v)` -> `{f: {"$eq": v}}`; on a list field `$eq` is true when any element matches (Q15,
 *     CONFIRMED), as in `matches`;
 *   - `In(f, vs)` on a string field -> `{f: {"$in": [vs]}}`;
 *   - `In(f, vs)` on a LIST field (for example `researcherIds`, which rag's SemanticContextAgent
 *     sends on researcher-profile-v1 and paper-abstract-v1) is NOT sent as `$in` (the semantics of
 *     `$in` against an array value are NOT STATED). It is sent as the documented equivalent built
 *     from CONFIRMED operators only: `{"$or": [{f: {"$eq": v1}}, {f: {"$eq": v2}}, ...]}`, which is
 *     true when any element equals any value, exactly `xs.exists(vs.contains)` of `matches`. A
 *     single value is sent as `{f: {"$eq": v1}}` (same semantics, no one-element `$or`);
 *   - `Range(f, lo, hi)` -> `{"$and": [{f: {"$gte": lo}}, {f: {"$lte": hi}}]}`, with both bounds
 *     within +-(2^53 - 1) (numbers may be held as doubles: P);
 *   - `And(fs)` -> `{"$and": [...]}`, non-empty.
 *
 * Empty value sets: `In(f, Vector())` matches nothing under `matches`; it is refused by
 * [[VectorFilters.problems]] before translation (fail closed, as for every backend that checks
 * `problems`), never sent as an empty `$or`/`$in` (Q15: `$in` needs a non-empty array). A null or
 * empty value inside the set is refused as well.
 *
 * Constraint cap (Q15/Q17, CONFIRMED for ENHANCED indexes: "up to 100 filter constraints" per query
 * filter; the docs state no CLASSIC limit, so storage applies [[MaxConstraints]] to every index:
 * P). How AWS counts a "constraint" is NOT STATED; storage counts conservatively over the WHOLE
 * translated document, nesting included: every comparison operator applied to one value counts 1
 * (`$eq`, `$gte`, `$lte`; a `$in` counts one per value), and every logical operator (`$and`, `$or`)
 * counts 1. A filter above the cap is `Refused` (value-free) before any call.
 *
 * Value size (Q17, CONFIRMED: filterable metadata is at most 2 KB per vector on CLASSIC indexes, 40
 * KB on ENHANCED): a string filter value above [[MaxValueBytes]] UTF-8 bytes cannot equal any
 * stored filterable value, so it is `Refused` rather than sent (P: the exact byte accounting is NOT
 * STATED). No per-filter-value limit is stated by the docs; this is the derived bound.
 *
 * A missing field never matching `$eq`, `$in`, `$gte`, `$lte` (as `matches` assumes) is NOT STATED,
 * UNVERIFIED (P); so is the nesting of `$or` inside `$and` (both operators are CONFIRMED, their
 * nesting is not stated; P). The filter is first checked with [[VectorFilters.problems]].
 */
object VectorFilterDocuments {
  import VectorFilter.*

  val MaxSafeInteger: Long = (1L << 53) - 1

  /** Filter constraints per query filter (Q15/Q17, ENHANCED; applied to CLASSIC as well: P). */
  val MaxConstraints = 100

  /** UTF-8 bytes of one string filter value (Q17 CLASSIC filterable metadata, 2 KB; P). */
  val MaxValueBytes = 2048

  /** The translated filter before rendering, so the constraint count covers the whole tree. */
  private enum Node {
    case Cmp(field: String, operator: String, value: Document)
    case InSet(field: String, values: Vector[String])
    case Logic(operator: String, children: Vector[Node])
  }

  private def constraints(n: Node): Int = n match {
    case Node.Cmp(_, _, _) => 1
    case Node.InSet(_, vs) => vs.size
    case Node.Logic(_, cs) => 1 + cs.map(constraints).sum
  }

  private def render(n: Node): Document = n match {
    case Node.Cmp(f, o, v) =>
      Document.fromMap(Map(f -> Document.fromMap(Map(o -> v).asJava)).asJava)
    case Node.InSet(f, vs) =>
      Document.fromMap(
        Map(
          f -> Document.fromMap(
            Map("$in" -> Document.fromList(vs.map(Document.fromString).asJava)).asJava
          )
        ).asJava
      )
    case Node.Logic(o, cs) =>
      Document.fromMap(Map(o -> Document.fromList(cs.map(render).asJava)).asJava)
  }

  def of(index: VectorIndexSpec, f: VectorFilter): Either[StoreError, Document] = {
    val problems = VectorFilters.problems(index, f)
    if (problems.nonEmpty)
      Left(StoreError.Refused(s"s3vectors filter for ${index.name}: ${problems.mkString("; ")}"))
    else
      translate(f).flatMap { n =>
        val c = constraints(n)
        if (c > MaxConstraints)
          refused(s"$c filter constraints exceed the cap of $MaxConstraints")
        else Right(render(n))
      }
  }

  /** The number of filter constraints of the translated filter (the count the cap applies to). */
  def constraintCount(index: VectorIndexSpec, f: VectorFilter): Either[StoreError, Int] = {
    val problems = VectorFilters.problems(index, f)
    if (problems.nonEmpty)
      Left(StoreError.Refused(s"s3vectors filter for ${index.name}: ${problems.mkString("; ")}"))
    else translate(f).map(constraints)
  }

  private def refused(why: String) = Left(StoreError.Refused(s"s3vectors filter: $why (D5)"))

  private def tooLong(v: String): Boolean =
    v.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MaxValueBytes

  private def translate(f: VectorFilter): Either[StoreError, Node] = f match {
    case Eq(m, v) if tooLong(v) =>
      refused(s"${m.key}: a value above $MaxValueBytes bytes")
    case Eq(m, v) => Right(Node.Cmp(m.key, "$eq", Document.fromString(v)))
    case In(m, vs) if vs.exists(v => v == null || v.isEmpty) =>
      refused(s"${m.key}: an empty value")
    case In(m, vs) if vs.exists(tooLong) =>
      refused(s"${m.key}: a value above $MaxValueBytes bytes")
    case In(m, Vector(v)) if m.tpe == MetaType.StrList =>
      Right(Node.Cmp(m.key, "$eq", Document.fromString(v)))
    case In(m, vs) if m.tpe == MetaType.StrList =>
      Right(Node.Logic("$or", vs.map(v => Node.Cmp(m.key, "$eq", Document.fromString(v)))))
    case In(m, vs) => Right(Node.InSet(m.key, vs))
    case Range(m, lo, hi) if math.abs(lo) > MaxSafeInteger || math.abs(hi) > MaxSafeInteger =>
      refused(s"${m.key}: a bound outside +-2^53")
    case Range(m, lo, hi) =>
      Right(
        Node.Logic(
          "$and",
          Vector(
            Node.Cmp(m.key, "$gte", Document.fromNumber(lo)),
            Node.Cmp(m.key, "$lte", Document.fromNumber(hi))
          )
        )
      )
    case And(fs) =>
      fs.foldLeft[Either[StoreError, Vector[Node]]](Right(Vector.empty)) { (acc, x) =>
        acc.flatMap(ns => translate(x).map(ns :+ _))
      }.map(Node.Logic("$and", _))
  }
}

/** SE-INT-04: metadata documents of S3 Vectors as storage [[VectorMetadata]] (Q18). */
object VectorMetadataDocuments {

  private def integral(d: Document): Either[String, Long] = {
    val b = d.asNumber().bigDecimalValue()
    if (b.signum != 0 && b.stripTrailingZeros.scale > 0) Left("a fractional number")
    else
      try Right(b.longValueExact())
      catch { case _: ArithmeticException => Left("an integer outside Long") }
  }

  /** `Corrupt`-ready reason, or the metadata: every key a known field with its declared type. */
  def decode(d: Document): Either[String, VectorMetadata] =
    if (d == null || !d.isMap) Left("metadata missing or not a map")
    else
      d.asMap()
        .asScala
        .toVector
        .foldLeft[Either[String, SortedMap[String, MetaValue]]](
          Right(SortedMap.empty)
        ) { case (acc, (k, v)) =>
          acc.flatMap { m =>
            val field = MetadataField.fromKey(k).toRight(s"metadata key $k is not a metadata field")
            field.flatMap { f =>
              val value: Either[String, MetaValue] =
                if (v == null) Left(s"$k: null")
                else
                  f.tpe match {
                    case MetaType.Str if v.isString => Right(MetaValue.Str(v.asString()))
                    case MetaType.StrList if v.isList =>
                      val xs = v.asList().asScala.toVector
                      if (xs.forall(x => x != null && x.isString))
                        Right(MetaValue.StrList(xs.map(_.asString())))
                      else Left(s"$k: a list element is not a string")
                    case MetaType.Int if v.isNumber =>
                      integral(v).left.map(w => s"$k: $w").map(MetaValue.Num(_))
                    case t => Left(s"$k: expected $t")
                  }
              value.map(x => m.updated(k, x))
            }
          }
        }
        .map(VectorMetadata(_))
}

/**
 * SE-INT-04 (A5): the S3 Vectors [[VectorReader]] and [[IndexInspector]], bound to one vector
 * bucket. Index names must be one of [[VectorIndexes.all]]; any other name is `Refused` before a
 * call. Services wraps it in [[VectorReader.verified]] (integrity and model policy).
 *
 *   - `query`: a non-empty finite vector, topK in 1..maxTopK, the filter translated by
 *     [[VectorFilterDocuments]]; one QueryVectors call with returnMetadata and returnDistance. The
 *     response must hold at most topK distinct keys of this index (`<index>/...`), each with a
 *     finite distance and decodable metadata, no nextToken (one page), and distances in
 *     NON-DECREASING order. Ascending order (smaller is closer) is NOT STATED by the docs ("ranked
 *     by vector distance", Q13): UNVERIFIED (P); a response in any other order is `Corrupt`.
 *   - `getVectors`: 1..maxGetKeys distinct keys; returnData and returnMetadata set (both default to
 *     false, Q14). Missing keys are handled both ways (Q14, NOT STATED): keys absent from the
 *     response are OMITTED (the runtime facade fails closed on a matched key that is missing), and
 *     a 404 error is `Permanent` (it may mean a missing index or key). A returned key that was not
 *     requested, a duplicate, or data that is not a non-empty float32 list is `Corrupt`. Float32
 *     bit-exactness is NOT STATED (P); the facade's `dataSha256` check fails closed if it is not.
 *   - `describe`: GetIndex; a 404 (NotFoundException, Q16) is `Right(None)`; the index name must
 *     match, the dimension be 1..4096 (Q17) and the metric cosine or euclidean, else `Corrupt`.
 */
final class S3VectorsReader private (calls: S3VectorsCalls, settings: S3VectorsReaderSettings)
    extends VectorReader
    with IndexInspector {

  private val bucket = settings.vectorBucket

  private def spec(index: String): Either[StoreError, VectorIndexSpec] =
    Option(index)
      .flatMap(VectorIndexes.byName)
      .toRight(StoreError.Refused("s3vectors: not one of the vector indexes"))

  private def fail(op: String, e: Throwable): StoreError = AwsErrors.classify("S3 Vectors", op, e)

  private def corrupt(index: String, why: String) =
    Left(StoreError.Corrupt(s"s3vectors $index: $why"))

  def query(
      index: String,
      vector: Vector[Float],
      topK: Int,
      filter: Option[VectorFilter]
  ): Either[StoreError, Vector[VectorMatch]] =
    for {
      s <- spec(index)
      v <-
        if (vector == null || vector.isEmpty || vector.exists(f => f.isNaN || f.isInfinite))
          Left(StoreError.Refused("s3vectors: the query vector is empty or not finite"))
        else Right(vector)
      k <-
        if (topK >= 1 && topK <= settings.maxTopK) Right(topK)
        else Left(StoreError.Refused(s"topK: $topK is outside 1..${settings.maxTopK}"))
      f <- Option(filter) match {
        case None => Left(StoreError.Refused("s3vectors: filter must be absent or present"))
        case Some(None) => Right(None)
        case Some(Some(x)) => VectorFilterDocuments.of(s, x).map(Some(_))
      }
      resp <- {
        val b = QueryVectorsRequest
          .builder()
          .vectorBucketName(bucket)
          .indexName(s.name)
          .queryVector(
            VectorData.builder().float32(v.map(x => java.lang.Float.valueOf(x)).asJava).build()
          )
          .topK(Integer.valueOf(k))
          .returnMetadata(java.lang.Boolean.TRUE)
          .returnDistance(java.lang.Boolean.TRUE)
        f.foreach(d => b.filter(d))
        try Right(calls.queryVectors(b.build()))
        catch { case NonFatal(e) => Left(fail(s"QueryVectors ${s.name}", e)) }
      }
      out <- matches(s.name, k, resp)
    } yield out

  private def matches(
      index: String,
      topK: Int,
      resp: QueryVectorsResponse
  ): Either[StoreError, Vector[VectorMatch]] =
    // `vectors` absent (hasVectors false, not merely empty) is Corrupt: the field is taken as
    // always present in a QueryVectors response (UNVERIFIED (P)).
    Option(resp).filter(_.hasVectors).map(_.vectors().asScala.toVector) match {
      case None => corrupt(index, "QueryVectors returned no vectors")
      case Some(_) if Option(resp.nextToken()).exists(_.nonEmpty) =>
        corrupt(index, "QueryVectors returned more than one page")
      case Some(vs) if vs.size > topK => corrupt(index, s"${vs.size} matches, above topK $topK")
      case Some(vs) =>
        vs.foldLeft[Either[StoreError, Vector[VectorMatch]]](Right(Vector.empty)) { (acc, o) =>
          acc.flatMap { ms =>
            if (o == null || o.key() == null || !o.key().startsWith(index + "/"))
              corrupt(index, "a match key is missing or of another index")
            else if (o.distance() == null || o.distance().isNaN || o.distance().isInfinite)
              corrupt(index, s"${o.key()}: no finite distance")
            else
              VectorMetadataDocuments.decode(o.metadata()) match {
                case Left(why) => corrupt(index, s"${o.key()}: $why")
                case Right(md) => Right(ms :+ VectorMatch(o.key(), o.distance().toDouble, md))
              }
          }
        }.flatMap { ms =>
          if (ms.map(_.key).distinct.size != ms.size) corrupt(index, "duplicate match keys")
          // UNVERIFIED (P): ascending distance order is assumed, not stated (Q13).
          else if (ms.zip(ms.drop(1)).exists { case (a, b) => b.distance < a.distance })
            corrupt(index, "matches are not in non-decreasing distance order")
          else Right(ms)
        }
    }

  def getVectors(index: String, keys: Vector[String]): Either[StoreError, Vector[VectorRecord]] =
    for {
      s <- spec(index)
      ks <-
        if (keys == null || keys.isEmpty || keys.size > settings.maxGetKeys)
          Left(StoreError.Refused(s"s3vectors: 1..${settings.maxGetKeys} keys per read"))
        else if (keys.exists(k => k == null || k.isEmpty))
          Left(StoreError.Refused("s3vectors: a key is missing"))
        else if (keys.distinct.size != keys.size)
          Left(StoreError.Refused("s3vectors: duplicate keys"))
        else Right(keys)
      resp <-
        try
          Right(
            calls.getVectors(
              GetVectorsRequest
                .builder()
                .vectorBucketName(bucket)
                .indexName(s.name)
                .keys(ks.asJava)
                .returnData(java.lang.Boolean.TRUE)
                .returnMetadata(java.lang.Boolean.TRUE)
                .build()
            )
          )
        catch {
          case e: AwsServiceException if e.statusCode() == 404 =>
            Left(
              StoreError.Permanent(
                AwsErrors.describe("S3 Vectors", s"GetVectors ${s.name}", e) +
                  " (missing index or key; UNVERIFIED)"
              )
            )
          case NonFatal(e) => Left(fail(s"GetVectors ${s.name}", e))
        }
      out <- records(s.name, ks.toSet, resp)
    } yield out

  private def records(
      index: String,
      requested: Set[String],
      resp: GetVectorsResponse
  ): Either[StoreError, Vector[VectorRecord]] =
    // `vectors` absent (hasVectors false) is Corrupt, as for QueryVectors (UNVERIFIED (P)).
    Option(resp).filter(_.hasVectors).map(_.vectors().asScala.toVector) match {
      case None => corrupt(index, "GetVectors returned no vectors")
      case Some(vs) =>
        vs.foldLeft[Either[StoreError, Vector[VectorRecord]]](Right(Vector.empty)) { (acc, o) =>
          acc.flatMap { rs =>
            if (o == null || o.key() == null || !requested.contains(o.key()))
              corrupt(index, "GetVectors returned a key that was not requested")
            else {
              val data = Option(o.data()).flatMap(d => Option(d.float32())).map(_.asScala.toVector)
              data match {
                case None => corrupt(index, s"${o.key()}: no float32 data")
                case Some(fs) if fs.isEmpty || fs.contains(null) =>
                  corrupt(index, s"${o.key()}: empty or incomplete float32 data")
                case Some(fs) =>
                  VectorMetadataDocuments.decode(o.metadata()) match {
                    case Left(why) => corrupt(index, s"${o.key()}: $why")
                    case Right(md) =>
                      Right(rs :+ VectorRecord(o.key(), fs.map(_.floatValue), md))
                  }
              }
            }
          }
        }.flatMap(rs =>
          if (rs.map(_.key).distinct.size != rs.size) corrupt(index, "duplicate keys returned")
          else Right(rs)
        )
    }

  def describe(index: String): Either[StoreError, Option[IndexInfo]] =
    spec(index).flatMap { s =>
      (try
        Right(
          Option(
            calls.getIndex(
              GetIndexRequest.builder().vectorBucketName(bucket).indexName(s.name).build()
            )
          )
        )
      catch {
        case e: AwsServiceException if e.statusCode() == 404 => Right(None)
        case NonFatal(e) => Left(fail(s"GetIndex ${s.name}", e))
      }).flatMap {
        case None => Right(None)
        case Some(r) =>
          Option(r.index()) match {
            case None => corrupt(s.name, "GetIndex returned no index")
            case Some(i) =>
              val metric = Option(i.distanceMetricAsString()).flatMap(m =>
                DistanceMetric.values.find(_.tag == m)
              )
              val dim = Option(i.dimension()).map(_.intValue)
              if (i.indexName() != s.name) corrupt(s.name, "GetIndex returned another index")
              else if (!dim.exists(d => d >= 1 && d <= 4096))
                corrupt(s.name, "dimension missing or outside 1..4096")
              else if (metric.isEmpty) corrupt(s.name, "unknown distance metric")
              else {
                val nonFilterable = Option(i.metadataConfiguration())
                  .flatMap(c => Option(c.nonFilterableMetadataKeys()))
                  .map(_.asScala.toVector)
                  .getOrElse(Vector.empty)
                if (nonFilterable.contains(null)) corrupt(s.name, "a null non-filterable key")
                else Right(Some(IndexInfo(s.name, dim.get, metric.get, nonFilterable)))
              }
          }
      }
    }
}

object S3VectorsReader {
  def of(calls: S3VectorsCalls, settings: S3VectorsReaderSettings): S3VectorsReader = {
    require(calls != null && settings != null, "calls and settings are required")
    new S3VectorsReader(calls, settings)
  }
}
