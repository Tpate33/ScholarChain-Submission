package scholarchain.storage.aws

import java.io.IOException
import java.net.SocketException
import java.time.Duration
import java.util.ServiceLoader

import scala.collection.mutable
import scala.jdk.CollectionConverters.*

import software.amazon.awssdk.auth.credentials.{
  AnonymousCredentialsProvider,
  AwsCredentials,
  AwsCredentialsProvider
}
import software.amazon.awssdk.core.exception.{ApiCallTimeoutException, SdkClientException}
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.http.SdkHttpService
import software.amazon.awssdk.services.dynamodb.model.{
  BatchGetItemRequest,
  BatchGetItemResponse,
  DeleteItemRequest,
  DeleteItemResponse,
  GetItemRequest,
  GetItemResponse,
  PutItemRequest,
  PutItemResponse,
  QueryRequest,
  QueryResponse
}
import software.amazon.awssdk.services.neptunedata.model.{
  ExecuteOpenCypherQueryRequest,
  ExecuteOpenCypherQueryResponse
}
import software.amazon.awssdk.services.s3.model.{
  GetObjectRequest,
  GetObjectResponse,
  HeadObjectRequest,
  HeadObjectResponse,
  ListObjectsV2Request,
  ListObjectsV2Response,
  S3Exception
}
import software.amazon.awssdk.services.s3vectors.model.{
  GetIndexRequest,
  GetIndexResponse,
  GetVectorsRequest,
  GetVectorsResponse,
  QueryVectorsRequest,
  QueryVectorsResponse
}

import scholarchain.storage.aws.dynamodb.{DynamoDbCalls, DynamoDbClientSettings}
import scholarchain.storage.aws.neptune.{NeptuneCalls, NeptuneClientSettings, NeptuneReaderSettings}
import scholarchain.storage.aws.s3.{S3Calls, S3ReaderSettings}
import scholarchain.storage.aws.s3vectors.{S3VectorsCalls, S3VectorsReaderSettings}
import scholarchain.storage.model.{ObjectRef, StoreError}

/** SE-INT-04 (A1, A2, A6, A7): lifecycle, explicit client settings and the shared error table. */
class StorageClientsSuite extends munit.FunSuite {
  import StorageClientsSuite.*

  private val ddbSettings = DynamoDbClientSettings.of("us-east-1", None, 2000, 1000).toOption.get
  private val aws = AwsClientSettings.of("us-east-1", None, 2000, 1000, 3).toOption.get
  private val neptune =
    NeptuneClientSettings.of("us-east-1", "https://db.example.com:8182", 2000, 1000, 3).toOption.get
  private val s3Settings = S3ReaderSettings.of(Set("scholarchain-test-raw"), 10, 5).toOption.get
  private val vecSettings =
    S3VectorsReaderSettings.of("scholarchain-test-vectors", 5, 5).toOption.get
  private val retry = RetrySetting.of(3).toOption.get

  private def everything(b: StorageClients) = {
    val f = b.dynamoDbFactory(retry).toOption.get
    val calls = f(ddbSettings).toOption.get
    val objects = b.objectReader(aws, s3Settings).toOption.get
    val graph = b.graphReader(neptune, NeptuneReaderSettings.of(5).toOption.get).toOption.get
    val vectors = b.vectorReader(aws, vecSettings).toOption.get
    (f, calls, objects, graph, vectors)
  }

  test("close: every client, then the provider exactly once; idempotent") {
    val log = mutable.ArrayBuffer.empty[String]
    val provider = new CountingProvider(log)
    val b =
      StorageClients.withSeams(provider, provider, new StubBuilders(log), AmbientSettings.empty)
    everything(b)
    b.close()
    b.close()
    assertEquals(provider.closes, 1)
    assertEquals(log.count(_.startsWith("close ")), 5)
    assertEquals(log.last, "close provider")
    assertEquals(
      log.filter(_.startsWith("close ")).toSet,
      Set("close dynamodb", "close s3", "close neptune", "close s3vectors", "close provider")
    )
    assert(b.isClosed)
    assertEquals(b.closeFailures, Vector.empty)
  }

  test("close: continues past a client whose close throws, and records it by class only") {
    val log = mutable.ArrayBuffer.empty[String]
    val provider = new CountingProvider(log)
    val builders = new StubBuilders(log, throwOnClose = Set("s3"))
    val b = StorageClients.withSeams(provider, provider, builders, AmbientSettings.empty)
    everything(b)
    b.close()
    assertEquals(provider.closes, 1)
    assertEquals(log.count(_.startsWith("close ")), 5)
    assertEquals(b.closeFailures, Vector("S3 client: IllegalStateException"))
    assert(!b.closeFailures.exists(_.contains(Secret)))
  }

  test("after close: builders refuse and every returned seam refuses before the SDK") {
    val log = mutable.ArrayBuffer.empty[String]
    val provider = new CountingProvider(log)
    val builders = new StubBuilders(log)
    val b = StorageClients.withSeams(provider, provider, builders, AmbientSettings.empty)
    val (f, calls, objects, graph, vectors) = everything(b)
    b.close()
    def refused(e: Either[StoreError, ?]) =
      assert(e.left.exists(_.isInstanceOf[StoreError.Refused]), e.toString)
    refused(b.dynamoDbFactory(retry))
    refused(b.objectReader(aws, s3Settings))
    refused(b.graphReader(neptune, NeptuneReaderSettings.of(5).toOption.get))
    refused(b.vectorReader(aws, vecSettings))
    assert(f(ddbSettings).isLeft)
    assert(f.isClosed)
    intercept[ClientClosedException](calls.getItem(GetItemRequest.builder().build()))
    refused(objects.head(ObjectRef("scholarchain-test-raw", "k")))
    refused(objects.list("scholarchain-test-raw", "", 1))
    refused(
      graph.run(
        scholarchain.storage.model.BoundQuery
          .bind(
            scholarchain.storage.model.GraphQuery.ResearcherById,
            Map(
              "researcherId" -> scholarchain.storage.model.GraphParamValue
                .Str("researcher:orcid:0000-0002-1825-0097")
            ),
            5
          )
          .toOption
          .get
      )
    )
    refused(vectors.describe("researcher-profile-v1"))
    refused(vectors.getVectors("researcher-profile-v1", Vector("k")))
    assertEquals(builders.sdkCalls, 0)
  }

  test("a DynamoDB seam of a closed factory is Refused through the adapter's error table") {
    assertEquals(
      scholarchain.storage.aws.dynamodb.DynamoDbErrors
        .classify("GetItem", "QueryJob", new ClientClosedException("x"))
        .getClass
        .getSimpleName,
      "Refused"
    )
  }

  test("the DynamoDB factory: owns its clients, uses the bundle provider and the explicit retry") {
    val log = mutable.ArrayBuffer.empty[String]
    val provider = new CountingProvider(log)
    val builders = new StubBuilders(log)
    val b = StorageClients.withSeams(provider, provider, builders, AmbientSettings.empty)
    val f = b.dynamoDbFactory(retry).toOption.get
    f(ddbSettings)
    f(ddbSettings)
    assertEquals(builders.ddbRetries.toVector, Vector(3, 3))
    assert(builders.providers.forall(_ eq provider))
    f.close()
    f.close()
    assertEquals(log.count(_ == "close dynamodb"), 2)
    assertEquals(provider.closes, 0) // the factory never closes the bundle's provider
    assert(f(ddbSettings).isLeft)
    b.close()
    assertEquals(provider.closes, 1)
    assertEquals(log.count(_ == "close dynamodb"), 2)
    assertEquals(f(null), Left("dynamoDb: client settings are required"))
  }

  test("a builder that throws is a value-free Left; nothing is registered") {
    val log = mutable.ArrayBuffer.empty[String]
    val provider = new CountingProvider(log)
    val b = StorageClients.withSeams(
      provider,
      provider,
      new StubBuilders(log, failBuild = true),
      AmbientSettings.empty
    )
    val f = b.dynamoDbFactory(retry).toOption.get
    val e = f(ddbSettings)
    assert(e.left.exists(m => m.contains("client build failed") && !m.contains(Secret)), e.toString)
    val o = b.objectReader(aws, s3Settings)
    assert(o.left.exists(_.isInstanceOf[StoreError.Permanent]), o.toString)
    b.close()
    assertEquals(log.count(_.startsWith("close ")), 1) // the provider only
  }

  test("the production bundle: one owned DefaultCredentialsProvider, open and close, no AWS call") {
    val b = StorageClients.open().toOption.get
    b.close()
    b.close()
    assert(b.isClosed)
    assertEquals(b.closeFailures, Vector.empty)
  }

  test("production SDK clients carry both timeouts and the explicit retry attempts (Q4)") {
    // An explicit endpoint: without one the SDK reads AWS_ENDPOINT_URL[_<SERVICE>] from the
    // environment, which the build blanks in forked test JVMs (observed: a blank value fails the
    // client build). SE-INT-05: storage's ambient-endpoint guard now refuses that case first, and
    // an explicit override bypasses it (AmbientEndpointsSuite). No connection is opened.
    val c = AnonymousCredentialsProvider.create()
    val local =
      AwsClientSettings.of("us-east-1", Some("http://localhost:1"), 2000, 1000, 3).toOption.get
    val localDdb =
      DynamoDbClientSettings.of("us-east-1", Some("http://localhost:1"), 2000, 1000).toOption.get
    val s3 = SdkBuilders.s3Client(local, c)
    val nd = SdkBuilders.neptuneClient(neptune, c)
    val sv = SdkBuilders.s3VectorsClient(local, c)
    val ddb = scholarchain.storage.aws.dynamodb.DynamoDbClients.build(localDdb, c, retry)
    try {
      for (
        o <- Vector(
          s3.serviceClientConfiguration().overrideConfiguration(),
          nd.serviceClientConfiguration().overrideConfiguration(),
          sv.serviceClientConfiguration().overrideConfiguration(),
          ddb.serviceClientConfiguration().overrideConfiguration()
        )
      ) {
        assertEquals(o.apiCallTimeout().get, Duration.ofMillis(2000))
        assertEquals(o.apiCallAttemptTimeout().get, Duration.ofMillis(1000))
        assertEquals(o.retryStrategy().get.maxAttempts(), 3)
      }
      assertEquals(
        nd.serviceClientConfiguration().endpointOverride().get,
        java.net.URI.create("https://db.example.com:8182")
      )
    } finally {
      s3.close(); nd.close(); sv.close(); ddb.close()
    }
  }

  test("exactly one synchronous SDK HTTP implementation is on the classpath (Q3)") {
    val impls = ServiceLoader.load(classOf[SdkHttpService]).iterator().asScala.toVector
    assertEquals(impls.size, 1, impls.map(_.getClass.getName).mkString(","))
    assert(impls.head.getClass.getName.contains("apache5"), impls.head.getClass.getName)
  }

  test("settings: retry and client bounds") {
    assert(RetrySetting.of(0).isLeft)
    assert(RetrySetting.of(RetrySetting.MaxAttempts + 1).isLeft)
    assert(RetrySetting.of(1).isRight)
    assert(AwsClientSettings.of("us-east-1", None, 1000, 1000, 3).isLeft)
    assert(AwsClientSettings.of("nowhere", None, 2000, 1000, 3).isLeft)
    assert(AwsClientSettings.of("us-east-1", Some("http://example.com"), 2000, 1000, 3).isLeft)
    assert(AwsClientSettings.of("us-east-1", Some("http://localhost:4566"), 2000, 1000, 3).isRight)
    assert(AwsClientSettings.of("us-east-1", None, 2000, 1000, 0).isLeft)
  }

  test("error table (A6): status, throttling, timeouts, transport, closed") {
    def kind(e: Throwable) = AwsErrors.classify("S3", "Op", e).getClass.getSimpleName
    assertEquals(kind(S3Exception.builder().statusCode(500).build()), "Transient")
    assertEquals(kind(S3Exception.builder().statusCode(429).build()), "Transient")
    assertEquals(kind(S3Exception.builder().statusCode(403).build()), "Permanent")
    assertEquals(kind(ApiCallTimeoutException.create(1000L)), "Transient")
    assertEquals(
      kind(SdkClientException.create("x", new SocketException("Connection reset"))),
      "Transient"
    )
    assertEquals(kind(new IOException("x")), "Transient")
    assertEquals(kind(SdkClientException.create("x")), "Permanent")
    assertEquals(kind(new ClientClosedException("x")), "Refused")
    assertEquals(kind(new IllegalArgumentException("x")), "Permanent")
    assert(!AwsErrors.classify("S3", "Op", new IOException(Secret)).message.contains(Secret))
  }

  test("CloseGroup: an item offered after close is closed at once and refused") {
    val g = new CloseGroup("g")
    g.close()
    var closed = 0
    assert(g.add("late", new AutoCloseable { def close(): Unit = closed += 1 }).isLeft)
    assertEquals(closed, 1)
    intercept[ClientClosedException](g.ensureOpen())
  }
}

object StorageClientsSuite {
  val Secret = "SECRET-request-value-7f3a"

  final class CountingProvider(log: mutable.ArrayBuffer[String])
      extends AwsCredentialsProvider
      with AutoCloseable {
    var closes = 0
    def resolveCredentials(): AwsCredentials = throw new IllegalStateException("never resolved")
    def close(): Unit = { closes += 1; log += "close provider" }
  }

  final class StubBuilders(
      log: mutable.ArrayBuffer[String],
      throwOnClose: Set[String] = Set.empty,
      failBuild: Boolean = false
  ) extends SdkBuilders {
    var sdkCalls = 0
    val ddbRetries = mutable.ArrayBuffer.empty[Int]
    val providers = mutable.ArrayBuffer.empty[AwsCredentialsProvider]

    private def closer(name: String): AutoCloseable = new AutoCloseable {
      def close(): Unit = {
        log += s"close $name"
        if (throwOnClose(name)) throw new IllegalStateException(Secret)
      }
    }
    private def built(c: AwsCredentialsProvider): Unit = {
      if (failBuild) throw new IllegalArgumentException(Secret)
      providers += c
    }
    private def hit(): Nothing = { sdkCalls += 1; throw new IllegalStateException("sdk") }

    def dynamoDb(s: DynamoDbClientSettings, c: AwsCredentialsProvider, r: RetrySetting) = {
      built(c)
      ddbRetries += r.maxAttempts
      val calls = new DynamoDbCalls {
        def getItem(x: GetItemRequest): GetItemResponse = hit()
        def putItem(x: PutItemRequest): PutItemResponse = hit()
        def deleteItem(x: DeleteItemRequest): DeleteItemResponse = hit()
        def query(x: QueryRequest): QueryResponse = hit()
        def batchGetItem(x: BatchGetItemRequest): BatchGetItemResponse = hit()
      }
      (calls, closer("dynamodb"))
    }
    def s3(s: AwsClientSettings, c: AwsCredentialsProvider) = {
      built(c)
      val calls = new S3Calls {
        def headObject(x: HeadObjectRequest): HeadObjectResponse = hit()
        def getObject(x: GetObjectRequest): ResponseInputStream[GetObjectResponse] = hit()
        def listObjectsV2(x: ListObjectsV2Request): ListObjectsV2Response = hit()
      }
      (calls, closer("s3"))
    }
    def neptune(s: NeptuneClientSettings, c: AwsCredentialsProvider) = {
      built(c)
      val calls = new NeptuneCalls {
        def executeOpenCypherQuery(
            x: ExecuteOpenCypherQueryRequest
        ): ExecuteOpenCypherQueryResponse =
          hit()
      }
      (calls, closer("neptune"))
    }
    def s3Vectors(s: AwsClientSettings, c: AwsCredentialsProvider) = {
      built(c)
      val calls = new S3VectorsCalls {
        def queryVectors(x: QueryVectorsRequest): QueryVectorsResponse = hit()
        def getVectors(x: GetVectorsRequest): GetVectorsResponse = hit()
        def getIndex(x: GetIndexRequest): GetIndexResponse = hit()
      }
      (calls, closer("s3vectors"))
    }
  }
}
