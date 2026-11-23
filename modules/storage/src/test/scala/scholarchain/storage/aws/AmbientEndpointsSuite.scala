package scholarchain.storage.aws

import scala.collection.mutable
import scala.jdk.CollectionConverters.*

import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider

import scholarchain.storage.aws.dynamodb.{DynamoDbClientSettings, DynamoDbClients}
import scholarchain.storage.aws.neptune.{NeptuneClientSettings, NeptuneReaderSettings}
import scholarchain.storage.aws.s3.S3ReaderSettings
import scholarchain.storage.aws.s3vectors.S3VectorsReaderSettings
import scholarchain.storage.model.StoreError

/**
 * SE-INT-05 (CR-INT-07-2): without an explicit endpointOverride, S3, S3 Vectors and DynamoDB
 * clients are refused before their builder runs while an ambient `AWS_ENDPOINT_URL*` environment
 * variable or `aws.endpointUrl*` system property is present; the refusal names names only. Offline:
 * stub ambient maps and stub builders (no credentials, no network, no AWS call).
 */
class AmbientEndpointsSuite extends munit.FunSuite {
  import StorageClientsSuite.{CountingProvider, Secret, StubBuilders}

  private val ddbNone = DynamoDbClientSettings.of("us-east-1", None, 2000, 1000).toOption.get
  private val ddbLocal =
    DynamoDbClientSettings.of("us-east-1", Some("http://localhost:1"), 2000, 1000).toOption.get
  private val awsNone = AwsClientSettings.of("us-east-1", None, 2000, 1000, 3).toOption.get
  private val awsLocal =
    AwsClientSettings.of("us-east-1", Some("http://localhost:1"), 2000, 1000, 3).toOption.get
  private val neptune =
    NeptuneClientSettings.of("us-east-1", "https://db.example.com:8182", 2000, 1000, 3).toOption.get
  private val s3Settings = S3ReaderSettings.of(Set("scholarchain-test-raw"), 10, 5).toOption.get
  private val vecSettings =
    S3VectorsReaderSettings.of("scholarchain-test-vectors", 5, 5).toOption.get
  private val retry = RetrySetting.of(3).toOption.get

  private def env(kv: (String, String)*) = AmbientSettings(() => kv.toMap, () => Map.empty)
  private def props(kv: (String, String)*) = AmbientSettings(() => Map.empty, () => kv.toMap)

  /** Every ambient form that must refuse, with the name the refusal must carry. */
  private val forms: Vector[(String, AmbientSettings, String)] = Vector(
    (
      "AWS_ENDPOINT_URL",
      env("AWS_ENDPOINT_URL" -> s"http://$Secret:4566"),
      "environment variable AWS_ENDPOINT_URL"
    ),
    (
      "AWS_ENDPOINT_URL_S3",
      env("AWS_ENDPOINT_URL_S3" -> Secret),
      "environment variable AWS_ENDPOINT_URL_S3"
    ),
    (
      "AWS_ENDPOINT_URL_S3VECTORS",
      env("AWS_ENDPOINT_URL_S3VECTORS" -> Secret),
      "environment variable AWS_ENDPOINT_URL_S3VECTORS"
    ),
    (
      "AWS_ENDPOINT_URL_DYNAMODB",
      env("AWS_ENDPOINT_URL_DYNAMODB" -> Secret),
      "environment variable AWS_ENDPOINT_URL_DYNAMODB"
    ),
    (
      "blank AWS_ENDPOINT_URL",
      env("AWS_ENDPOINT_URL" -> ""),
      "environment variable AWS_ENDPOINT_URL"
    ),
    (
      "lower-case name",
      env("aws_endpoint_url_s3" -> Secret),
      "environment variable aws_endpoint_url_s3"
    ),
    ("aws.endpointUrl", props("aws.endpointUrl" -> Secret), "system property aws.endpointUrl"),
    (
      "aws.endpointUrlS3",
      props("aws.endpointUrlS3" -> Secret),
      "system property aws.endpointUrlS3"
    ),
    (
      "aws.endpointUrlDynamoDb",
      props("aws.endpointUrlDynamoDb" -> Secret),
      "system property aws.endpointUrlDynamoDb"
    )
  )

  private def refusedNaming(e: Either[StoreError, ?], name: String): Unit = {
    assert(e.left.exists(_.isInstanceOf[StoreError.Refused]), e.toString)
    val m = e.left.toOption.get.message
    assert(m.contains(name), m)
    assert(!m.contains(Secret) && !m.contains("4566"), m)
  }

  test("each environment and property form refuses S3, S3 Vectors and DynamoDB, names only") {
    for ((label, ambient, name) <- forms) {
      refusedNaming(AmbientEndpoints.check("s3", None, ambient), name)
      val log = mutable.ArrayBuffer.empty[String]
      val provider = new CountingProvider(log)
      val builders = new StubBuilders(log)
      val b = StorageClients.withSeams(provider, provider, builders, ambient)
      refusedNaming(b.objectReader(awsNone, s3Settings), name)
      refusedNaming(b.vectorReader(awsNone, vecSettings), name)
      val f = b.dynamoDbFactory(retry).toOption.get
      val d = f(ddbNone)
      assert(d.left.exists(m => m.contains(name) && !m.contains(Secret)), s"$label: $d")
      // Neptune always has an explicit endpoint: unaffected
      assert(b.graphReader(neptune, NeptuneReaderSettings.of(5).toOption.get).isRight, label)
      // refused BEFORE the builder ran: only the Neptune build reached a builder
      assertEquals(builders.providers.size, 1, label)
      b.close()
    }
  }

  test("the SDK client builders refuse before the SDK builder runs (names only)") {
    val c = AnonymousCredentialsProvider.create()
    for ((label, ambient, name) <- forms) {
      def thrown(f: => AutoCloseable): Unit = {
        val e = intercept[AmbientEndpointException](f)
        assert(e.getMessage.contains(name) && !e.getMessage.contains(Secret), s"$label: $e")
      }
      thrown(SdkBuilders.s3Client(awsNone, c, ambient))
      thrown(SdkBuilders.s3VectorsClient(awsNone, c, ambient))
      thrown(DynamoDbClients.build(ddbNone, c, retry, ambient))
      thrown(SdkBuilders.forAmbient(ambient).s3(awsNone, c)._2)
    }
  }

  test("several names are all listed, sorted, environment then properties") {
    val ambient = AmbientSettings(
      () => Map("AWS_ENDPOINT_URL_S3" -> Secret, "AWS_ENDPOINT_URL" -> Secret, "PATH" -> "/bin"),
      () => Map("aws.endpointUrlS3" -> Secret, "java.version" -> "17")
    )
    assertEquals(
      AmbientEndpoints.offending(ambient),
      Right(
        Vector(
          "environment variable AWS_ENDPOINT_URL",
          "environment variable AWS_ENDPOINT_URL_S3",
          "system property aws.endpointUrlS3"
        )
      )
    )
  }

  test("an explicit endpointOverride bypasses the check") {
    val all = AmbientSettings(
      () => Map("AWS_ENDPOINT_URL" -> Secret, "AWS_ENDPOINT_URL_S3" -> Secret),
      () => Map("aws.endpointUrl" -> Secret)
    )
    assertEquals(AmbientEndpoints.check("s3", awsLocal.endpointOverride, all), Right(()))
    val log = mutable.ArrayBuffer.empty[String]
    val provider = new CountingProvider(log)
    val builders = new StubBuilders(log)
    val b = StorageClients.withSeams(provider, provider, builders, all)
    assert(b.objectReader(awsLocal, s3Settings).isRight)
    assert(b.vectorReader(awsLocal, vecSettings).isRight)
    assert(b.dynamoDbFactory(retry).toOption.get(ddbLocal).isRight)
    assertEquals(builders.providers.size, 3)
    b.close()
    // the real SDK builders too (no connection is opened by building a client)
    val c = AnonymousCredentialsProvider.create()
    val s3 = SdkBuilders.s3Client(awsLocal, c, all)
    val sv = SdkBuilders.s3VectorsClient(awsLocal, c, all)
    val ddb = DynamoDbClients.build(ddbLocal, c, retry, all)
    try {
      assertEquals(
        s3.serviceClientConfiguration().endpointOverride().get,
        awsLocal.endpointOverride.get
      )
      assertEquals(
        sv.serviceClientConfiguration().endpointOverride().get,
        awsLocal.endpointOverride.get
      )
      assertEquals(
        ddb.serviceClientConfiguration().endpointOverride().get,
        ddbLocal.endpointOverride.get
      )
    } finally { s3.close(); sv.close(); ddb.close() }
  }

  test("a clean environment builds (stub builders), unrelated names are ignored") {
    val clean = AmbientSettings(
      () => Map("AWS_REGION" -> "us-east-1", "PATH" -> "/bin", "ENDPOINT_URL" -> "x"),
      () => Map("aws.region" -> "us-east-1", "java.version" -> "17")
    )
    for (ambient <- Vector(AmbientSettings.empty, clean)) {
      assertEquals(AmbientEndpoints.check("s3", None, ambient), Right(()))
      val log = mutable.ArrayBuffer.empty[String]
      val provider = new CountingProvider(log)
      val builders = new StubBuilders(log)
      val b = StorageClients.withSeams(provider, provider, builders, ambient)
      assert(b.objectReader(awsNone, s3Settings).isRight)
      assert(b.vectorReader(awsNone, vecSettings).isRight)
      assert(b.dynamoDbFactory(retry).toOption.get(ddbNone).isRight)
      assertEquals(builders.providers.size, 3)
      b.close()
    }
  }

  test("an unreadable ambient source fails closed, value-free") {
    val broken = AmbientSettings(() => throw new SecurityException(Secret), () => Map.empty)
    val e = AmbientEndpoints.check("s3", None, broken)
    assert(
      e.left.exists(x => x.isInstanceOf[StoreError.Refused] && !x.message.contains(Secret)),
      e.toString
    )
    assert(AmbientEndpoints.check("s3", None, null).isLeft)
    assert(StorageClients.open(null).isLeft)
  }

  test("production default: the process environment and system properties (names)") {
    assertEquals(AmbientSettings.process.environment().keySet, System.getenv().asScala.keySet.toSet)
    assertEquals(
      AmbientSettings.process.systemProperties().keySet,
      System.getProperties.stringPropertyNames().asScala.toSet
    )
    assert(
      AmbientSettings.process.systemProperties().values.forall(_.isEmpty),
      "no values are held"
    )
  }

  test("the forked test JVM's blanked AWS_ENDPOINT_URL is caught by the guard, not the SDK") {
    // The build blanks AWS_ENDPOINT_URL in every forked test JVM (build.sbt awsIsolationEnv); in
    // SE-INT-04 that broke the SDK build and StorageClientsSuite had to pass a loopback endpoint.
    // Now the guard refuses first, with a names-only error, and the loopback override bypasses it.
    val present =
      System
        .getenv()
        .asScala
        .keySet
        .exists(_.toUpperCase(java.util.Locale.ROOT).startsWith("AWS_ENDPOINT_URL"))
    val c = AnonymousCredentialsProvider.create()
    if (present) {
      val e = intercept[AmbientEndpointException](SdkBuilders.s3Client(awsNone, c))
      assert(e.getMessage.contains("environment variable AWS_ENDPOINT_URL"), e.getMessage)
      intercept[AmbientEndpointException](SdkBuilders.s3VectorsClient(awsNone, c))
      intercept[AmbientEndpointException](DynamoDbClients.build(ddbNone, c, retry))
      intercept[AmbientEndpointException](DynamoDbClients.build(ddbNone, c))
      val b = StorageClients.open().toOption.get
      try {
        val r = b.objectReader(awsNone, s3Settings)
        assert(
          r.left.exists(_.message.contains("environment variable AWS_ENDPOINT_URL")),
          r.toString
        )
        assert(b.vectorReader(awsNone, vecSettings).isLeft)
        assert(b.dynamoDbFactory(retry).toOption.get(ddbNone).isLeft)
        // the loopback override (the SE-INT-04 workaround) bypasses the guard
        assert(b.objectReader(awsLocal, s3Settings).isRight)
      } finally b.close()
    } else {
      assertEquals(AmbientEndpoints.check("s3", None, AmbientSettings.process), Right(()))
    }
  }
}
