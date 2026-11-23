package scholarchain.storage.aws.s3vectors

import scala.collection.immutable.SortedMap
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.core.document.Document
import software.amazon.awssdk.services.s3vectors.model.{
  DistanceMetric as SdkMetric,
  GetIndexRequest,
  GetIndexResponse,
  GetOutputVector,
  GetVectorsRequest,
  GetVectorsResponse,
  MetadataConfiguration,
  QueryOutputVector,
  QueryVectorsRequest,
  QueryVectorsResponse,
  S3VectorsException,
  VectorData
}

import scholarchain.storage.model.{
  DistanceMetric,
  IndexInfo,
  MetaValue,
  MetadataField,
  StoreError,
  VectorFilter,
  VectorIndexes,
  VectorMetadata
}

/** SE-INT-04 (A5, A7): the S3 Vectors reader over a stub seam (no credentials, no network). */
class S3VectorsReaderSuite extends munit.FunSuite {
  import S3VectorsReaderSuite.*

  private val Index = VectorIndexes.ResearcherProfile.name
  private val settings = S3VectorsReaderSettings.of(VectorBucket, 3, 4).toOption.get
  private def reader(stub: Stub) = S3VectorsReader.of(stub, settings)
  private def key(n: Int) = s"$Index/model-a/suminput:$n"

  private val mdDoc: Document = Document.fromMap(
    Map(
      "documentType" -> Document.fromString("researcher-profile"),
      "researcherIds" -> Document.fromList(java.util.List.of(Document.fromString("researcher:x"))),
      "windowStart" -> Document.fromNumber(2000L)
    ).asJava
  )
  private val md = VectorMetadata(
    SortedMap(
      "documentType" -> MetaValue.Str("researcher-profile"),
      "researcherIds" -> MetaValue.StrList(Vector("researcher:x")),
      "windowStart" -> MetaValue.Num(2000L)
    )
  )

  private def hit(k: String, d: Float, m: Document = mdDoc) =
    QueryOutputVector.builder().key(k).distance(java.lang.Float.valueOf(d)).metadata(m).build()

  test("query: request fields, one page, decoded matches in order") {
    val stub = Stub()
    stub.query =
      QueryVectorsResponse.builder().vectors(hit(key(1), 0.1f), hit(key(2), 0.2f)).build()
    val got = reader(stub).query(Index, Vector(0.5f, 0.25f), 2, None)
    assertEquals(got.map(_.map(_.key)), Right(Vector(key(1), key(2))))
    assertEquals(got.map(_.head.metadata), Right(md))
    val r = stub.queries.head
    assertEquals(r.vectorBucketName(), VectorBucket)
    assertEquals(r.indexName(), Index)
    assertEquals(r.topK(), Integer.valueOf(2))
    assertEquals(r.returnMetadata(), java.lang.Boolean.TRUE)
    assertEquals(r.returnDistance(), java.lang.Boolean.TRUE)
    assertEquals(r.queryVector().float32().asScala.toVector.map(_.floatValue), Vector(0.5f, 0.25f))
    assertEquals(r.filter(), null)
  }

  test("query: an empty result is empty") {
    val stub = Stub()
    stub.query =
      QueryVectorsResponse.builder().vectors(java.util.List.of[QueryOutputVector]()).build()
    assertEquals(reader(stub).query(Index, Vector(1f), 1, None), Right(Vector.empty))
  }

  test(
    "query: out-of-order distances (P), too many matches, a next page, foreign keys are Corrupt"
  ) {
    val bad = Vector(
      QueryVectorsResponse.builder().vectors(hit(key(1), 0.3f), hit(key(2), 0.2f)).build(),
      QueryVectorsResponse
        .builder()
        .vectors(hit(key(1), 0.1f), hit(key(2), 0.2f), hit(key(3), 0.3f))
        .build(),
      QueryVectorsResponse.builder().vectors(hit(key(1), 0.1f)).nextToken("t").build(),
      QueryVectorsResponse.builder().vectors(hit("paper-abstract-v1/m/x", 0.1f)).build(),
      QueryVectorsResponse.builder().vectors(hit(key(1), 0.1f), hit(key(1), 0.2f)).build(),
      QueryVectorsResponse.builder().vectors(hit(key(1), Float.NaN)).build(),
      QueryVectorsResponse.builder().build()
    )
    for (resp <- bad) {
      val stub = Stub()
      stub.query = resp
      val got = reader(stub).query(Index, Vector(1f), 2, None)
      assert(got.left.exists(_.isInstanceOf[StoreError.Corrupt]), s"$resp: $got")
    }
  }

  test("metadata decoding: unknown keys and wrong types are Corrupt") {
    val bad = Vector(
      Map("notAField" -> Document.fromString("x")),
      Map("documentType" -> Document.fromNumber(1L)),
      Map("researcherIds" -> Document.fromString("x")),
      Map("researcherIds" -> Document.fromList(java.util.List.of(Document.fromNumber(1L)))),
      Map("windowStart" -> Document.fromNumber(2000.5)),
      Map("windowStart" -> Document.fromString("2000"))
    )
    for (m <- bad) {
      val stub = Stub()
      stub.query =
        QueryVectorsResponse.builder().vectors(hit(key(1), 0f, Document.fromMap(m.asJava))).build()
      val got = reader(stub).query(Index, Vector(1f), 1, None)
      assert(got.left.exists(_.isInstanceOf[StoreError.Corrupt]), s"$m: $got")
    }
    val stub = Stub()
    stub.query = QueryVectorsResponse.builder().vectors(hit(key(1), 0f, null)).build()
    assert(
      reader(stub).query(Index, Vector(1f), 1, None).left.exists(_.isInstanceOf[StoreError.Corrupt])
    )
  }

  test("query: refused before any call (index, topK, vector, filter)") {
    val stub = Stub()
    val r = reader(stub)
    def refused(e: Either[StoreError, ?]) =
      assert(e.left.exists(_.isInstanceOf[StoreError.Refused]), e.toString)
    refused(r.query("other-index", Vector(1f), 1, None))
    refused(r.query(Index, Vector(1f), 0, None))
    refused(r.query(Index, Vector(1f), 4, None))
    refused(r.query(Index, Vector.empty, 1, None))
    refused(r.query(Index, Vector(Float.NaN), 1, None))
    refused(r.query(Index, Vector(1f), 1, null))
    refused(r.query(Index, Vector(1f), 1, Some(VectorFilter.Eq(MetadataField.TextS3Key, "x"))))
    // SE-INT-05 (CR-INT-07-1): In on a list field is translated now; an EMPTY set stays refused.
    refused(
      r.query(Index, Vector(1f), 1, Some(VectorFilter.In(MetadataField.ResearcherIds, Vector())))
    )
    refused(r.getVectors(Index, Vector.empty))
    refused(r.getVectors(Index, Vector("a", "a")))
    refused(r.getVectors(Index, Vector("a", "b", "c", "d", "e")))
    refused(r.getVectors("other-index", Vector("a")))
    refused(r.describe("other-index"))
    assertEquals(stub.calls, 0)
  }

  test("filters: documented shapes only (D5)") {
    val spec = VectorIndexes.ResearcherProfile
    def doc(f: VectorFilter) = VectorFilterDocuments.of(spec, f).map(_.toString)
    assertEquals(
      VectorFilterDocuments.of(spec, VectorFilter.Eq(MetadataField.ResearcherIds, "r")),
      Right(
        Document.fromMap(
          Map(
            "researcherIds" -> Document.fromMap(Map("$eq" -> Document.fromString("r")).asJava)
          ).asJava
        )
      )
    )
    assertEquals(
      VectorFilterDocuments.of(spec, VectorFilter.In(MetadataField.SubjectId, Vector("a", "b"))),
      Right(
        Document.fromMap(
          Map(
            "subjectId" -> Document.fromMap(
              Map(
                "$in" -> Document.fromList(
                  java.util.List.of(Document.fromString("a"), Document.fromString("b"))
                )
              ).asJava
            )
          ).asJava
        )
      )
    )
    val range =
      VectorFilterDocuments.of(spec, VectorFilter.Range(MetadataField.WindowStart, 2000, 2010))
    assertEquals(
      range,
      Right(
        Document.fromMap(
          Map(
            "$and" -> Document.fromList(
              java.util.List.of(
                Document.fromMap(
                  Map(
                    "windowStart" -> Document.fromMap(
                      Map("$gte" -> Document.fromNumber(2000L)).asJava
                    )
                  ).asJava
                ),
                Document.fromMap(
                  Map(
                    "windowStart" -> Document.fromMap(
                      Map("$lte" -> Document.fromNumber(2010L)).asJava
                    )
                  ).asJava
                )
              )
            )
          ).asJava
        )
      )
    )
    assert(
      VectorFilterDocuments
        .of(
          spec,
          VectorFilter.And(
            Vector(
              VectorFilter.Eq(MetadataField.SubjectId, "a"),
              VectorFilter.Range(MetadataField.WindowEnd, 1, 2)
            )
          )
        )
        .isRight
    )
    // SE-INT-05 (CR-INT-07-1): In on a list field is the $eq form (S3VectorsFilterSuite).
    assert(doc(VectorFilter.In(MetadataField.ResearcherIds, Vector("a"))).isRight)
    assert(doc(VectorFilter.In(MetadataField.SubjectId, Vector(""))).isLeft)
    assert(doc(VectorFilter.Range(MetadataField.WindowStart, 0, 1L << 54)).isLeft)
    assert(doc(VectorFilter.And(Vector.empty)).isLeft)
    // the filter reaches the request
    val stub = Stub()
    stub.query = QueryVectorsResponse.builder().vectors(java.util.List.of()).build()
    reader(stub).query(Index, Vector(1f), 1, Some(VectorFilter.Eq(MetadataField.SubjectId, "a")))
    assert(stub.queries.head.filter() != null)
  }

  test("getVectors: request fields, decoded records, missing keys omitted") {
    val stub = Stub()
    stub.get = GetVectorsResponse
      .builder()
      .vectors(
        GetOutputVector
          .builder()
          .key(key(1))
          .data(
            VectorData
              .builder()
              .float32(java.lang.Float.valueOf(0.5f), java.lang.Float.valueOf(-1f))
              .build()
          )
          .metadata(mdDoc)
          .build()
      )
      .build()
    val got = reader(stub).getVectors(Index, Vector(key(1), key(2)))
    assertEquals(got.map(_.map(_.key)), Right(Vector(key(1))))
    assertEquals(got.map(_.head.data), Right(Vector(0.5f, -1f)))
    assertEquals(got.map(_.head.metadata), Right(md))
    val r = stub.gets.head
    assertEquals(r.keys().asScala.toVector, Vector(key(1), key(2)))
    assertEquals(r.returnData(), java.lang.Boolean.TRUE)
    assertEquals(r.returnMetadata(), java.lang.Boolean.TRUE)
    assertEquals(r.vectorBucketName(), VectorBucket)
  }

  test(
    "getVectors: unrequested or duplicate keys and missing data are Corrupt; a 404 is Permanent"
  ) {
    def out(k: String, data: VectorData) =
      GetOutputVector.builder().key(k).data(data).metadata(mdDoc).build()
    val one = VectorData.builder().float32(java.lang.Float.valueOf(1f)).build()
    val bad = Vector(
      GetVectorsResponse.builder().vectors(out(key(9), one)).build(),
      GetVectorsResponse.builder().vectors(out(key(1), one), out(key(1), one)).build(),
      GetVectorsResponse.builder().vectors(out(key(1), null)).build(),
      GetVectorsResponse
        .builder()
        .vectors(out(key(1), VectorData.builder().float32(java.util.List.of()).build()))
        .build(),
      GetVectorsResponse.builder().build()
    )
    for (resp <- bad) {
      val stub = Stub()
      stub.get = resp
      val got = reader(stub).getVectors(Index, Vector(key(1)))
      assert(got.left.exists(_.isInstanceOf[StoreError.Corrupt]), s"$resp: $got")
    }
    val stub = Stub()
    stub.failWith = Some(service(404, "NotFoundException"))
    val got = reader(stub).getVectors(Index, Vector(key(1)))
    assert(got.left.exists(_.isInstanceOf[StoreError.Permanent]), got.toString)
    assert(!got.toString.contains(Marker))
  }

  test("describe: the index, a 404 is None, malformed answers are Corrupt") {
    def resp(name: String, dim: Integer, metric: SdkMetric, keys: java.util.List[String]) =
      GetIndexResponse
        .builder()
        .index(
          software.amazon.awssdk.services.s3vectors.model.Index
            .builder()
            .indexName(name)
            .dimension(dim)
            .distanceMetric(metric)
            .metadataConfiguration(
              MetadataConfiguration.builder().nonFilterableMetadataKeys(keys).build()
            )
            .build()
        )
        .build()
    val stub = Stub()
    stub.index = resp(Index, 1024, SdkMetric.COSINE, java.util.List.of("textS3Key", "dataSha256"))
    assertEquals(
      reader(stub).describe(Index),
      Right(Some(IndexInfo(Index, 1024, DistanceMetric.Cosine, Vector("textS3Key", "dataSha256"))))
    )
    assertEquals(stub.indexes.head.vectorBucketName(), VectorBucket)
    stub.failWith = Some(service(404, "NotFoundException"))
    assertEquals(reader(stub).describe(Index), Right(None))
    for (
      bad <- Vector(
        resp("paper-abstract-v1", 1024, SdkMetric.COSINE, java.util.List.of()),
        resp(Index, 0, SdkMetric.COSINE, java.util.List.of()),
        resp(Index, 4097, SdkMetric.EUCLIDEAN, java.util.List.of()),
        resp(Index, 8, SdkMetric.UNKNOWN_TO_SDK_VERSION, java.util.List.of()),
        GetIndexResponse.builder().build()
      )
    ) {
      val s = Stub()
      s.index = bad
      assert(
        reader(s).describe(Index).left.exists(_.isInstanceOf[StoreError.Corrupt]),
        bad.toString
      )
    }
  }

  test("errors: 429 and 503 are Transient, 403 Permanent") {
    for (
      (st, code, expected) <- Vector(
        (429, "TooManyRequestsException", "Transient"),
        (503, "ServiceUnavailableException", "Transient"),
        (403, "AccessDeniedException", "Permanent")
      )
    ) {
      val stub = Stub()
      stub.failWith = Some(service(st, code))
      assertEquals(
        reader(stub).query(Index, Vector(1f), 1, None).left.map(_.getClass.getSimpleName),
        Left(expected)
      )
      assertEquals(reader(stub).describe(Index).left.map(_.getClass.getSimpleName), Left(expected))
    }
  }

  test("settings: bounds (topK and keys capped at 100; maxGetKeys >= maxTopK)") {
    assert(S3VectorsReaderSettings.of(VectorBucket, 100, 100).isRight)
    assert(S3VectorsReaderSettings.of(VectorBucket, 101, 100).isLeft)
    assert(S3VectorsReaderSettings.of(VectorBucket, 10, 101).isLeft)
    assert(S3VectorsReaderSettings.of(VectorBucket, 10, 9).isLeft)
    assert(S3VectorsReaderSettings.of(VectorBucket, 0, 9).isLeft)
    assert(S3VectorsReaderSettings.of("Bad_Bucket", 1, 1).isLeft)
  }
}

object S3VectorsReaderSuite {
  val VectorBucket = "scholarchain-test-vectors"
  val Marker = "SECRET-request-value-7f3a"

  def service(status: Int, code: String): Throwable =
    S3VectorsException
      .builder()
      .statusCode(status)
      .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).errorMessage(Marker).build())
      .message(Marker)
      .build()

  final class Stub extends S3VectorsCalls {
    var query: QueryVectorsResponse = QueryVectorsResponse.builder().build()
    var get: GetVectorsResponse = GetVectorsResponse.builder().build()
    var index: GetIndexResponse = GetIndexResponse.builder().build()
    var failWith: Option[Throwable] = None
    val queries = mutable.ArrayBuffer.empty[QueryVectorsRequest]
    val gets = mutable.ArrayBuffer.empty[GetVectorsRequest]
    val indexes = mutable.ArrayBuffer.empty[GetIndexRequest]
    def calls: Int = queries.size + gets.size + indexes.size
    def queryVectors(r: QueryVectorsRequest) = {
      queries += r; failWith.foreach(e => throw e); query
    }
    def getVectors(r: GetVectorsRequest) = { gets += r; failWith.foreach(e => throw e); get }
    def getIndex(r: GetIndexRequest) = { indexes += r; failWith.foreach(e => throw e); index }
  }
}
