package scholarchain.storage.aws.s3vectors

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import software.amazon.awssdk.core.document.Document
import software.amazon.awssdk.services.s3vectors.S3VectorsClient
import software.amazon.awssdk.services.s3vectors.model.{
  PutInputVector,
  PutVectorsRequest,
  PutVectorsResponse,
  VectorData
}
import scholarchain.storage.aws.AwsErrors
import scholarchain.storage.model.*
import scholarchain.storage.port.VectorStore

/** Loader-only PutVectors seam, separate from the read-only runtime seam. */
trait S3VectorsPutCalls {
  def putVectors(request: PutVectorsRequest): PutVectorsResponse
}
object S3VectorsPutCalls {
  def of(client: S3VectorsClient): S3VectorsPutCalls = new S3VectorsPutCalls {
    def putVectors(r: PutVectorsRequest) = client.putVectors(r)
  }
}

/**
 * Get/compare/Put for a SINGLE externally serialized loader in fresh, run-owned indexes. PutVectors
 * has no conditional insert API: synchronization here only serializes this instance, not other
 * processes. Concurrent writers are unsupported and must be excluded operationally. AWS limits
 * checked 2026-11-23: 500 vectors, 20 MiB per Put; this adapter conservatively limits the batch to
 * the reader's <=100-key bound and refuses a JSON payload above 19 MiB.
 * https://docs.aws.amazon.com/AmazonS3/latest/API/API_S3VectorBuckets_PutVectors.html
 * https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-vectors-limitations.html
 */
final class S3VectorsStore private (
    reads: S3VectorsCalls,
    writes: S3VectorsPutCalls,
    settings: S3VectorsReaderSettings,
    limits: VectorLimits
) extends VectorStore {
  private val reader = S3VectorsReader.of(reads, settings)
  def query(index: String, vector: Vector[Float], topK: Int, filter: Option[VectorFilter]) =
    reader.query(index, vector, topK, filter)
  def getVectors(index: String, keys: Vector[String]) = reader.getVectors(index, keys)
  def describe(index: String) = reader.describe(index)

  private def metadata(md: VectorMetadata): Document = Document.fromMap(md.values.map {
    case (k, MetaValue.Str(s)) => k -> Document.fromString(s)
    case (k, MetaValue.StrList(ss)) => k -> Document.fromList(ss.map(Document.fromString).asJava)
    case (k, MetaValue.Num(n)) => k -> Document.fromNumber(n)
  }.asJava)

  private def validate(spec: VectorIndexSpec, r: VectorRecord): Either[StoreError, Unit] =
    if (
      r == null || r.metadata == null || r.metadata.values == null || r.metadata.values.values
        .exists(_ == null)
    )
      Left(StoreError.Refused("s3vectors: missing record or metadata"))
    else
      for {
        _ <- VectorDigest.verify(spec.name, r)
        key <- VectorKeys.of(
          spec,
          r.metadata.string(MetadataField.ModelVersion).orNull,
          r.metadata.string(MetadataField.InputId).orNull
        )
        _ <-
          if (key == r.key) Right(())
          else Left(StoreError.Refused("s3vectors: key differs from model/input metadata"))
        problems = VectorMetadataRules.problems(spec, r.metadata, limits)
        _ <-
          if (problems.isEmpty) Right(())
          else Left(StoreError.Refused("s3vectors: " + problems.mkString("; ")))
      } yield ()

  def putVectors(
      index: String,
      records: Vector[VectorRecord]
  ): Either[StoreError, Vector[VectorPutResult]] = synchronized {
    for {
      spec <- Option(index)
        .flatMap(VectorIndexes.byName)
        .toRight(StoreError.Refused("s3vectors: unknown index"))
      _ <-
        if (
          records == null || records.isEmpty || records.size > math.min(
            limits.maxBatchSize,
            settings.maxGetKeys
          ) ||
          records.contains(null) || records.map(_.key).distinct.size != records.size
        )
          Left(StoreError.Refused("s3vectors: missing, duplicate or oversize batch"))
        else Right(())
      _ <- records.foldLeft[Either[StoreError, Unit]](Right(()))((a, r) =>
        a.flatMap(_ => validate(spec, r))
      )
      info <- reader
        .describe(index)
        .flatMap(_.toRight(StoreError.Refused("s3vectors: index missing")))
      _ <-
        if (records.forall(_.data.size == info.dimension)) Right(())
        else Left(StoreError.Refused("s3vectors: vector dimension differs from index"))
      _ <-
        if (
          spec.allowed.filterNot(_.filterable).map(_.key).subsetOf(info.nonFilterableKeys.toSet) &&
          !spec.allowed.filter(_.filterable).exists(f => info.nonFilterableKeys.contains(f.key))
        ) Right(())
        else Left(StoreError.Refused("s3vectors: index metadata configuration differs from schema"))
      existing <- reader.getVectors(index, records.map(_.key))
      _ <- existing.foldLeft[Either[StoreError, Unit]](Right(()))((a, r) =>
        a.flatMap(_ => VectorDigest.verify(index, r))
      )
      byKey = existing.map(r => r.key -> r).toMap
      missing = records.filterNot(r => byKey.contains(r.key))
      _ <- send(index, missing)
    } yield records.map(r =>
      VectorPutResult(
        r.key,
        byKey.get(r.key) match {
          case None => Right(PutOutcome.Written)
          case Some(old) if old == r => Right(PutOutcome.AlreadyPresent)
          case Some(_) =>
            Left(StoreError.Conflict("s3vectors: existing key has different data or metadata"))
        }
      )
    )
  }

  private def send(index: String, rs: Vector[VectorRecord]): Either[StoreError, Unit] =
    if (rs.isEmpty) Right(())
    else {
      // The SDK document encoder escapes strings; use the same JSON content for a conservative
      // payload-size bound before any Put. 1 MiB remains for envelope and SDK formatting.
      val encoded = ujson.Arr
        .from(
          rs.map(r =>
            ujson.Obj(
              "key" -> r.key,
              "data" -> ujson.Obj(
                "float32" -> ujson.Arr.from(r.data.map(f => ujson.Num(f.toDouble)))
              ),
              "metadata" -> ujson.Obj.from(r.metadata.values.map {
                case (k, MetaValue.Str(s)) => k -> ujson.Str(s)
                case (k, MetaValue.StrList(ss)) => k -> ujson.Arr.from(ss.map(ujson.Str(_)))
                case (k, MetaValue.Num(n)) => k -> ujson.Num(n.toDouble)
              })
            )
          )
        )
        .render()
        .getBytes(java.nio.charset.StandardCharsets.UTF_8)
        .length
      if (encoded > 19 * 1024 * 1024)
        Left(StoreError.Refused("s3vectors: request payload over conservative 19 MiB bound"))
      else
        try {
          val response = writes.putVectors(
            PutVectorsRequest
              .builder()
              .vectorBucketName(settings.vectorBucket)
              .indexName(index)
              .vectors(
                rs.map(r =>
                  PutInputVector
                    .builder()
                    .key(r.key)
                    .data(
                      VectorData
                        .builder()
                        .float32(r.data.map(java.lang.Float.valueOf).asJava)
                        .build()
                    )
                    .metadata(metadata(r.metadata))
                    .build()
                ).asJava
              )
              .build()
          )
          if (response == null)
            Left(StoreError.Corrupt("s3vectors: PutVectors returned no response"))
          else Right(())
        } catch { case NonFatal(e) => Left(AwsErrors.classify("S3 Vectors", "PutVectors", e)) }
    }
}
object S3VectorsStore {
  def of(
      reads: S3VectorsCalls,
      writes: S3VectorsPutCalls,
      settings: S3VectorsReaderSettings,
      limits: VectorLimits
  ): S3VectorsStore = {
    require(
      reads != null && writes != null && settings != null && VectorLimits.problems(limits).isEmpty,
      "calls and valid settings/limits required"
    )
    new S3VectorsStore(reads, writes, settings, limits)
  }
}
