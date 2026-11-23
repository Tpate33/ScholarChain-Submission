package scholarchain.storage.aws.s3vectors

import scala.jdk.CollectionConverters.*
import software.amazon.awssdk.core.document.Document
import software.amazon.awssdk.services.s3vectors.model.*
import scholarchain.storage.TestData
import scholarchain.storage.model.{
  MetaValue,
  MetadataField,
  PutOutcome,
  StoreError,
  VectorDigest,
  VectorIndexes,
  VectorKeys,
  VectorLimits,
  VectorMetadata,
  VectorRecord
}

class S3VectorsStoreSuite extends munit.FunSuite {
  private val spec = VectorIndexes.ResearcherProfile
  private val settings = S3VectorsReaderSettings.of("test-vector-bucket", 10, 100).toOption.get
  private val limits = VectorLimits(100, 2048, 2048, 40000, 50, 20, 100, 10)
  private val data = Vector(0.1f, 0.2f)
  private val md = VectorDigest.withDigest(
    TestData.withMeta(TestData.profileMetadata(), "modelVersion", MetaValue.Str("real-model-v1")),
    VectorDigest.of(data).toOption.get
  )
  private val key = VectorKeys.of(spec, "real-model-v1", TestData.inputId).toOption.get
  private val record = VectorRecord(key, data, md)
  private def doc(m: VectorMetadata) = Document.fromMap(m.values.map {
    case (k, MetaValue.Str(s)) => k -> Document.fromString(s)
    case (k, MetaValue.StrList(ss)) => k -> Document.fromList(ss.map(Document.fromString).asJava)
    case (k, MetaValue.Num(n)) => k -> Document.fromNumber(n)
  }.asJava)
  private class Stub extends S3VectorsCalls with S3VectorsPutCalls {
    var puts = Vector.empty[PutVectorsRequest]
    var gets = 0
    var existing = Vector.empty[VectorRecord]
    var dimension = 2
    var getFailure = false
    def putVectors(r: PutVectorsRequest) = { puts :+= r; PutVectorsResponse.builder().build() }
    def getVectors(r: GetVectorsRequest) = {
      gets += 1
      if (getFailure)
        throw S3VectorsException.builder().statusCode(503).message("private-data").build()
      GetVectorsResponse
        .builder()
        .vectors(
          existing
            .map(v =>
              GetOutputVector
                .builder()
                .key(v.key)
                .data(
                  VectorData.builder().float32(v.data.map(java.lang.Float.valueOf).asJava).build()
                )
                .metadata(doc(v.metadata))
                .build()
            )
            .asJava
        )
        .build()
    }
    def queryVectors(r: QueryVectorsRequest): QueryVectorsResponse = throw new AssertionError(
      "no query"
    )
    def getIndex(r: GetIndexRequest) = GetIndexResponse
      .builder()
      .index(
        Index
          .builder()
          .indexName(spec.name)
          .dimension(dimension)
          .distanceMetric(DistanceMetric.COSINE)
          .metadataConfiguration(
            MetadataConfiguration
              .builder()
              .nonFilterableMetadataKeys(MetadataField.nonFilterableFields.map(_.key).asJava)
              .build()
          )
          .build()
      )
      .build()
  }
  private def store(s: Stub) = S3VectorsStore.of(s, s, settings, limits)
  test("only absent keys are put with exact data and metadata") {
    val s = new Stub
    assertEquals(
      store(s).putVectors(spec.name, Vector(record)).map(_.head.outcome),
      Right(Right(PutOutcome.Written))
    )
    assertEquals(s.gets, 1)
    val r = s.puts.head
    assertEquals(r.vectorBucketName(), settings.vectorBucket)
    assertEquals(r.indexName(), spec.name)
    assertEquals(r.vectors().get(0).key(), key)
    assertEquals(r.vectors().get(0).data().float32().asScala.toVector.map(_.floatValue()), data)
    assertEquals(VectorMetadataDocuments.decode(r.vectors().get(0).metadata()), Right(md))
  }
  test("equal vector is already present, differing vector is conflict, neither is overwritten") {
    val s = new Stub
    s.existing = Vector(record)
    assertEquals(
      store(s).putVectors(spec.name, Vector(record)).map(_.head.outcome),
      Right(Right(PutOutcome.AlreadyPresent))
    )
    val changedData = Vector(0.3f, 0.4f)
    val changed = record.copy(
      data = changedData,
      metadata = VectorDigest.withDigest(md, VectorDigest.of(changedData).toOption.get)
    )
    assert(
      store(s)
        .putVectors(spec.name, Vector(changed))
        .toOption
        .get
        .head
        .outcome
        .left
        .exists(_.isInstanceOf[StoreError.Conflict])
    )
    assertEquals(s.puts.size, 0)
  }
  test(
    "dimension mismatch, invalid digest, duplicate keys and read failures never reach PutVectors"
  ) {
    val s = new Stub
    s.dimension = 3
    assert(store(s).putVectors(spec.name, Vector(record)).isLeft)
    s.dimension = 2
    assert(store(s).putVectors(spec.name, Vector(record.copy(data = Vector(9f, 9f)))).isLeft)
    assert(store(s).putVectors(spec.name, Vector(record, record)).isLeft)
    s.getFailure = true
    val failed = store(s).putVectors(spec.name, Vector(record))
    assert(failed.isLeft)
    assert(!failed.toString.contains("private-data"))
    assertEquals(s.puts.size, 0)
  }
}
