package scholarchain.storage.aws

import scala.util.control.NonFatal

import software.amazon.awssdk.auth.credentials.{AwsCredentialsProvider, DefaultCredentialsProvider}
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.{
  BatchGetItemRequest,
  DeleteItemRequest,
  GetItemRequest,
  PutItemRequest,
  QueryRequest
}
import software.amazon.awssdk.services.neptunedata.NeptunedataClient
import software.amazon.awssdk.services.neptunedata.model.ExecuteOpenCypherQueryRequest
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{
  GetObjectRequest,
  GetObjectResponse,
  HeadObjectRequest,
  ListObjectsV2Request
}
import software.amazon.awssdk.services.s3vectors.S3VectorsClient
import software.amazon.awssdk.services.s3vectors.model.{
  GetIndexRequest,
  GetVectorsRequest,
  QueryVectorsRequest
}

import scholarchain.storage.aws.dynamodb.{DynamoDbCalls, DynamoDbClientSettings, DynamoDbClients}
import scholarchain.storage.aws.neptune.{
  NeptuneCalls,
  NeptuneClientSettings,
  NeptuneGraphReader,
  NeptuneReaderSettings
}
import scholarchain.storage.aws.s3.{S3Calls, S3ObjectReader, S3ReaderSettings}
import scholarchain.storage.aws.s3vectors.{S3VectorsCalls, S3VectorsReader, S3VectorsReaderSettings}
import scholarchain.storage.model.StoreError
import scholarchain.storage.port.{GraphReader, ObjectReader}

/**
 * SE-INT-04: how storage builds each SDK seam: the seam plus the resource that releases it. The
 * production instance builds real SDK clients (no AWS call is made by building one); tests inject
 * stubs.
 */
trait SdkBuilders {
  def dynamoDb(
      s: DynamoDbClientSettings,
      c: AwsCredentialsProvider,
      r: RetrySetting
  ): (DynamoDbCalls, AutoCloseable)
  def s3(s: AwsClientSettings, c: AwsCredentialsProvider): (S3Calls, AutoCloseable)
  def neptune(s: NeptuneClientSettings, c: AwsCredentialsProvider): (NeptuneCalls, AutoCloseable)
  def s3Vectors(s: AwsClientSettings, c: AwsCredentialsProvider): (S3VectorsCalls, AutoCloseable)
}

object SdkBuilders {

  /**
   * SDK clients with Region, credentials, endpoint, both timeouts and the explicit retry. SE-INT-05
   * (CR-INT-07-2): without an `endpointOverride`, [[AmbientEndpoints.enforce]] runs BEFORE the SDK
   * builder and throws [[AmbientEndpointException]] (names only) when an ambient
   * `AWS_ENDPOINT_URL*` variable or `aws.endpointUrl*` property is present. The two-argument forms
   * read the process ([[AmbientSettings.process]]).
   */
  def s3Client(s: AwsClientSettings, c: AwsCredentialsProvider): S3Client =
    s3Client(s, c, AmbientSettings.process)

  def s3Client(
      s: AwsClientSettings,
      c: AwsCredentialsProvider,
      ambient: AmbientSettings
  ): S3Client = {
    AmbientEndpoints.enforce("s3", s.endpointOverride, ambient)
    val b = S3Client
      .builder()
      .region(Region.of(s.region))
      .credentialsProvider(c)
      .overrideConfiguration(AwsOverrides.of(s))
    s.endpointOverride.foreach(u => b.endpointOverride(u))
    b.build()
  }

  def neptuneClient(s: NeptuneClientSettings, c: AwsCredentialsProvider): NeptunedataClient =
    NeptunedataClient
      .builder()
      .region(Region.of(s.client.region))
      .credentialsProvider(c)
      .endpointOverride(s.endpoint)
      .overrideConfiguration(AwsOverrides.of(s.client))
      .build()

  def s3VectorsClient(s: AwsClientSettings, c: AwsCredentialsProvider): S3VectorsClient =
    s3VectorsClient(s, c, AmbientSettings.process)

  def s3VectorsClient(
      s: AwsClientSettings,
      c: AwsCredentialsProvider,
      ambient: AmbientSettings
  ): S3VectorsClient = {
    AmbientEndpoints.enforce("s3vectors", s.endpointOverride, ambient)
    val b = S3VectorsClient
      .builder()
      .region(Region.of(s.region))
      .credentialsProvider(c)
      .overrideConfiguration(AwsOverrides.of(s))
    s.endpointOverride.foreach(u => b.endpointOverride(u))
    b.build()
  }

  /** The real SDK builders, checking `ambient` before each S3, S3 Vectors and DynamoDB build. */
  def forAmbient(ambient: AmbientSettings): SdkBuilders = {
    require(ambient != null, "ambient settings are required")
    new SdkBuilders {
      def dynamoDb(s: DynamoDbClientSettings, c: AwsCredentialsProvider, r: RetrySetting) = {
        val client: DynamoDbClient = DynamoDbClients.build(s, c, r, ambient)
        (DynamoDbCalls.of(client), client)
      }
      def s3(s: AwsClientSettings, c: AwsCredentialsProvider) = {
        val client = s3Client(s, c, ambient)
        (S3Calls.of(client), client)
      }
      def neptune(s: NeptuneClientSettings, c: AwsCredentialsProvider) = {
        val client = neptuneClient(s, c)
        (NeptuneCalls.of(client), client)
      }
      def s3Vectors(s: AwsClientSettings, c: AwsCredentialsProvider) = {
        val client = s3VectorsClient(s, c, ambient)
        (S3VectorsCalls.of(client), client)
      }
    }
  }

  /** The real SDK builders over the process environment and system properties. */
  val production: SdkBuilders = forAmbient(AmbientSettings.process)
}

/** Seams that refuse every call once their group is closed (before the SDK is reached). */
private[aws] object Guarded {
  def dynamoDb(g: CloseGroup, c: DynamoDbCalls): DynamoDbCalls = new DynamoDbCalls {
    def getItem(r: GetItemRequest) = { g.ensureOpen(); c.getItem(r) }
    def putItem(r: PutItemRequest) = { g.ensureOpen(); c.putItem(r) }
    def deleteItem(r: DeleteItemRequest) = { g.ensureOpen(); c.deleteItem(r) }
    def query(r: QueryRequest) = { g.ensureOpen(); c.query(r) }
    def batchGetItem(r: BatchGetItemRequest) = { g.ensureOpen(); c.batchGetItem(r) }
  }
  def s3(g: CloseGroup, c: S3Calls): S3Calls = new S3Calls {
    def headObject(r: HeadObjectRequest) = { g.ensureOpen(); c.headObject(r) }
    def getObject(r: GetObjectRequest): ResponseInputStream[GetObjectResponse] = {
      g.ensureOpen(); c.getObject(r)
    }
    def listObjectsV2(r: ListObjectsV2Request) = { g.ensureOpen(); c.listObjectsV2(r) }
  }
  def neptune(g: CloseGroup, c: NeptuneCalls): NeptuneCalls = new NeptuneCalls {
    def executeOpenCypherQuery(r: ExecuteOpenCypherQueryRequest) = {
      g.ensureOpen(); c.executeOpenCypherQuery(r)
    }
  }
  def s3Vectors(g: CloseGroup, c: S3VectorsCalls): S3VectorsCalls = new S3VectorsCalls {
    def queryVectors(r: QueryVectorsRequest) = { g.ensureOpen(); c.queryVectors(r) }
    def getVectors(r: GetVectorsRequest) = { g.ensureOpen(); c.getVectors(r) }
    def getIndex(r: GetIndexRequest) = { g.ensureOpen(); c.getIndex(r) }
  }

  def built[A](what: String)(f: => A): Either[StoreError, A] =
    try Right(f)
    catch {
      case NonFatal(e) =>
        Left(StoreError.Permanent(s"$what: client build failed (${e.getClass.getSimpleName})"))
    }
}

/**
 * SE-INT-04 (A1): the production DynamoDB SDK-call factory, the value of services
 * `ProductionDependencies.dynamoDb` (`DynamoDbClientSettings => Either[String, DynamoDbCalls]`).
 * Each call builds one client with the bundle's credentials provider and the EXPLICIT retry setting
 * ([[DynamoDbClients.build]] with [[RetrySetting]]), and owns it.
 *
 * Lifecycle (Q2: SDK close idempotence and use-after-close are NOT STATED): [[close]] is idempotent
 * and closes every client this factory built (each attempted even when one throws); it never closes
 * the credentials provider (the bundle owns it). After close the factory refuses to build and every
 * seam it returned refuses every call (`Refused`, before the SDK is reached).
 */
final class DynamoDbClientFactory private[aws] (
    credentials: AwsCredentialsProvider,
    retry: RetrySetting,
    builders: SdkBuilders,
    ambient: AmbientSettings
) extends (DynamoDbClientSettings => Either[String, DynamoDbCalls])
    with AutoCloseable {

  private val group = new CloseGroup("DynamoDB client factory")

  def retrySetting: RetrySetting = retry

  /**
   * SE-INT-05 (CR-INT-07-2): settings without an `endpointOverride` are refused (names only, before
   * the builder runs) while an ambient endpoint setting is present ([[AmbientEndpoints]]).
   */
  def apply(s: DynamoDbClientSettings): Either[String, DynamoDbCalls] =
    if (s == null) Left("dynamoDb: client settings are required")
    else if (group.isClosed) Left("dynamoDb: the client factory is closed")
    else
      (for {
        _ <- AmbientEndpoints.check("dynamoDb", s.endpointOverride, ambient)
        built <- Guarded.built("dynamoDb")(builders.dynamoDb(s, credentials, retry))
        _ <- group.add("DynamoDB client", built._2)
      } yield Guarded.dynamoDb(group, built._1)).left.map(_.message)

  def isClosed: Boolean = group.isClosed
  def close(): Unit = group.close()
  def closeFailures: Vector[String] = group.closeFailures
}

/**
 * SE-INT-04 (A2): the one owner of every storage SDK client of a process and of its ONE credentials
 * provider: `DefaultCredentialsProvider.builder().build()` (Q1: the deprecated `create()` singleton
 * is never used; chain: system properties, environment, web identity, shared profile, container,
 * instance profile).
 *
 * The composing process builds the readers it needs from here, passes them (and
 * [[dynamoDbFactory]]) to services `ProductionDependencies`, and calls [[close]] once on shutdown.
 * Lifecycle (Q2, NOT STATED by the SDK, so enforced here):
 *   - [[close]] is idempotent;
 *   - it closes every client (and the DynamoDB factories, which close theirs) in reverse creation
 *     order, then the credentials provider exactly once, continuing past a failing close (failures
 *     in [[closeFailures]], class names only);
 *   - after close every builder method is `Refused` and every seam it returned refuses every call.
 *
 * Residual: a call already inside the SDK when close runs is not interrupted (the SDK's behaviour
 * then is NOT STATED, P).
 */
final class StorageClients private (
    credentials: AwsCredentialsProvider,
    builders: SdkBuilders,
    group: CloseGroup,
    ambient: AmbientSettings
) extends AutoCloseable {

  private def open: Either[StoreError, Unit] =
    if (group.isClosed) Left(StoreError.Refused("storage clients are closed")) else Right(())

  /** A DynamoDB SDK-call factory sharing this bundle's provider; closed with the bundle. */
  def dynamoDbFactory(retry: RetrySetting): Either[StoreError, DynamoDbClientFactory] =
    for {
      _ <- open
      r <- Option(retry).toRight(StoreError.Refused("dynamoDb: retry setting is required"))
      f <- group.add(
        "DynamoDB client factory",
        new DynamoDbClientFactory(credentials, r, builders, ambient)
      )
    } yield f

  /**
   * SE-INT-05 (CR-INT-07-2): without `client.endpointOverride`, `Refused` (names only, before the
   * builder runs) while an ambient endpoint setting is present ([[AmbientEndpoints]]).
   */
  def objectReader(
      client: AwsClientSettings,
      settings: S3ReaderSettings
  ): Either[StoreError, ObjectReader] =
    for {
      _ <- open
      _ <- required(client, settings)
      _ <- AmbientEndpoints.check("s3", client.endpointOverride, ambient)
      built <- Guarded.built("s3")(builders.s3(client, credentials))
      _ <- group.add("S3 client", built._2)
    } yield S3ObjectReader.of(Guarded.s3(group, built._1), settings)

  def graphReader(
      client: NeptuneClientSettings,
      settings: NeptuneReaderSettings
  ): Either[StoreError, GraphReader] =
    for {
      _ <- open
      _ <- required(client, settings)
      built <- Guarded.built("neptune")(builders.neptune(client, credentials))
      _ <- group.add("Neptune client", built._2)
    } yield NeptuneGraphReader.of(Guarded.neptune(group, built._1), settings)

  /**
   * One reader that is both the `vectors` and the `vectorIndexes` dependency. SE-INT-05: the same
   * ambient-endpoint check as [[objectReader]].
   */
  def vectorReader(
      client: AwsClientSettings,
      settings: S3VectorsReaderSettings
  ): Either[StoreError, S3VectorsReader] =
    for {
      _ <- open
      _ <- required(client, settings)
      _ <- AmbientEndpoints.check("s3vectors", client.endpointOverride, ambient)
      built <- Guarded.built("s3vectors")(builders.s3Vectors(client, credentials))
      _ <- group.add("S3 Vectors client", built._2)
    } yield S3VectorsReader.of(Guarded.s3Vectors(group, built._1), settings)

  private def required(a: AnyRef, b: AnyRef): Either[StoreError, Unit] =
    if (a == null || b == null) Left(StoreError.Refused("client and reader settings are required"))
    else Right(())

  def isClosed: Boolean = group.isClosed
  def close(): Unit = group.close()
  def closeFailures: Vector[String] = group.closeFailures
}

object StorageClients {

  /**
   * The production bundle: one owned `DefaultCredentialsProvider.builder().build()` and the real
   * SDK builders. Building it makes no AWS call (credentials resolve on first use).
   */
  def open(): Either[StoreError, StorageClients] = open(AmbientSettings.process)

  /**
   * SE-INT-05: as [[open]], with the ambient settings checked by the endpoint guard
   * ([[AmbientEndpoints]]) injected; production uses [[AmbientSettings.process]].
   */
  def open(ambient: AmbientSettings): Either[StoreError, StorageClients] =
    if (ambient == null) Left(StoreError.Refused("storage clients: ambient settings are required"))
    else
      Guarded
        .built("credentials")(DefaultCredentialsProvider.builder().build())
        .map(p => withSeams(p, p, SdkBuilders.forAmbient(ambient), ambient))

  /**
   * Storage-internal and tests: a bundle over `credentials`, closing `closeCredentials` last and
   * exactly once. The ambient-endpoint guard reads `ambient` (no default: the caller states it).
   */
  private[storage] def withSeams(
      credentials: AwsCredentialsProvider,
      closeCredentials: AutoCloseable,
      builders: SdkBuilders,
      ambient: AmbientSettings
  ): StorageClients = {
    require(
      credentials != null && closeCredentials != null && builders != null && ambient != null,
      "required"
    )
    val group = new CloseGroup("storage clients")
    group.add("credentials provider", closeCredentials)
    new StorageClients(credentials, builders, group, ambient)
  }
}
