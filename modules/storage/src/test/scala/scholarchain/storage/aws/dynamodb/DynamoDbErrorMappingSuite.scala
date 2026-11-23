package scholarchain.storage.aws.dynamodb

import java.io.{EOFException, IOException, UncheckedIOException}
import java.net.{SocketException, SocketTimeoutException}
import javax.net.ssl.SSLException

import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.core.exception.{
  AbortedException,
  ApiCallAttemptTimeoutException,
  ApiCallTimeoutException,
  SdkClientException
}
import software.amazon.awssdk.services.dynamodb.model.{
  DynamoDbException,
  InternalServerErrorException,
  ProvisionedThroughputExceededException,
  RequestLimitExceededException,
  ResourceNotFoundException,
  TransactionConflictException
}

import scholarchain.storage.model.{PrimaryKey, StoreError, TableRole}
import scholarchain.storage.port.{KeyQuery, SortKeyCondition}

/** SE-INT-01 item 1: the error-mapping table, for every operation of the adapter. */
class DynamoDbErrorMappingSuite extends munit.FunSuite {
  import DynamoDbAdapterSuite.{h, row}

  /** A value an exception message could echo; it must never reach a StoreError detail. */
  private val Marker = "SECRET-request-value-7f3a"

  private def details(code: String) =
    AwsErrorDetails.builder().errorCode(code).errorMessage(Marker).build()

  private def service(status: Int, code: String): Throwable =
    DynamoDbException
      .builder()
      .statusCode(status)
      .awsErrorDetails(details(code))
      .message(Marker)
      .build()

  private val cases: Vector[(String, () => Throwable, String)] = Vector(
    (
      "ProvisionedThroughputExceeded",
      () =>
        ProvisionedThroughputExceededException
          .builder()
          .statusCode(400)
          .awsErrorDetails(details("ProvisionedThroughputExceededException"))
          .message(Marker)
          .build(),
      "Transient"
    ),
    (
      "RequestLimitExceeded",
      () => RequestLimitExceededException.builder().statusCode(400).message(Marker).build(),
      "Transient"
    ),
    (
      "InternalServerError",
      () => InternalServerErrorException.builder().statusCode(500).message(Marker).build(),
      "Transient"
    ),
    ("status 500", () => service(500, "InternalFailure"), "Transient"),
    ("status 503", () => service(503, "ServiceUnavailable"), "Transient"),
    ("status 429", () => service(429, "Unknown"), "Transient"),
    ("ThrottlingException code", () => service(400, "ThrottlingException"), "Transient"),
    (
      "ApiCallTimeout",
      () => ApiCallTimeoutException.builder().message(Marker).build(),
      "Transient"
    ),
    (
      "ApiCallAttemptTimeout",
      () => ApiCallAttemptTimeoutException.builder().message(Marker).build(),
      "Transient"
    ),
    (
      "socket timeout",
      () =>
        SdkClientException
          .builder()
          .message(Marker)
          .cause(new SocketTimeoutException(Marker))
          .build(),
      "Transient"
    ),
    // SE-INT-02 (CR-INT-01-5): transport failures are Transient (every write is conditional).
    (
      "connection reset (SocketException cause)",
      () => SdkClientException.builder().message(Marker).cause(new SocketException(Marker)).build(),
      "Transient"
    ),
    (
      "SSLException cause",
      () => SdkClientException.builder().message(Marker).cause(new SSLException(Marker)).build(),
      "Transient"
    ),
    (
      "connection closed (EOFException cause)",
      () => SdkClientException.builder().message(Marker).cause(new EOFException(Marker)).build(),
      "Transient"
    ),
    (
      "connection closed (IOException deep in the cause chain)",
      () =>
        SdkClientException
          .builder()
          .message(Marker)
          .cause(new RuntimeException(Marker, new IOException(Marker)))
          .build(),
      "Transient"
    ),
    (
      "UncheckedIOException cause",
      () =>
        SdkClientException
          .builder()
          .message(Marker)
          .cause(new UncheckedIOException(Marker, new SocketException(Marker)))
          .build(),
      "Transient"
    ),
    ("bare IOException", () => new IOException(Marker), "Transient"),
    (
      "TransactionConflict",
      () =>
        TransactionConflictException
          .builder()
          .statusCode(400)
          .awsErrorDetails(details("TransactionConflictException"))
          .message(Marker)
          .build(),
      "Transient"
    ),
    (
      "client exception with a non-IO cause",
      () =>
        SdkClientException
          .builder()
          .message(Marker)
          .cause(new IllegalStateException(Marker))
          .build(),
      "Permanent"
    ),
    (
      "aborted with an IO cause stays Permanent",
      () => AbortedException.builder().message(Marker).cause(new SocketException(Marker)).build(),
      "Permanent"
    ),
    ("ValidationException", () => service(400, "ValidationException"), "Permanent"),
    ("AccessDenied", () => service(400, "AccessDeniedException"), "Permanent"),
    (
      "ResourceNotFound",
      () => ResourceNotFoundException.builder().statusCode(400).message(Marker).build(),
      "Permanent"
    ),
    (
      "client exception without timeout",
      () => SdkClientException.builder().message(Marker).build(),
      "Permanent"
    ),
    ("aborted", () => AbortedException.builder().message(Marker).build(), "Permanent"),
    ("runtime exception", () => new IllegalStateException(Marker), "Permanent")
  )

  private val Job = TableRole.QueryJob
  private val key = PrimaryKey("job#1", "job")

  private val ops: Vector[(String, DynamoDbKeyValueStore => Either[StoreError, ?])] = Vector(
    "get" -> (_.get(Job, key)),
    "putIfAbsent" -> (_.putIfAbsent(Job, row("job#1", h('a')))),
    "putIfVersion" -> (_.putIfVersion(Job, row("job#1", h('a'), Some(2)), 1)),
    "deleteIfRecord" -> (_.deleteIfRecord(Job, key, h('a'))),
    "query" -> (_.query(Job, KeyQuery(None, "job#1", SortKeyCondition.All, 5))),
    "getMany" -> (_.getMany(Job, Vector(key)))
  )

  for ((label, mk, expected) <- cases; (op, run) <- ops)
    test(s"$label in $op is $expected, and the detail never echoes the message") {
      val stub = Ddb.stub()
      val kv = Ddb.adapter(stub)
      stub.failNext(mk())
      val r = run(kv)
      assert(r.isLeft, r.toString)
      val e = r.swap.toOption.get
      assertEquals(e.productPrefix, expected, e.toString)
      assertEquals(e.retryable, expected == "Transient")
      assert(!e.message.contains(Marker), e.message)
      assert(e.message.startsWith("DynamoDB "), e.message)
      assert(!Thread.interrupted(), "no interrupt is invented")
    }

  test("an InterruptedException is Permanent and restores the thread's interrupt flag") {
    val stub = Ddb.stub()
    val kv = Ddb.adapter(stub)
    stub.failNext(new InterruptedException(Marker))
    val r = kv.get(Job, key)
    assert(Thread.interrupted(), "interrupt restored (and cleared here)")
    assert(r.left.exists(e => e.isInstanceOf[StoreError.Permanent] && !e.message.contains(Marker)))
  }

  test("the detail names the operation, the role, the status and the error code") {
    val e = DynamoDbErrors.classify("PutItem", "QueryJob", service(503, "ServiceUnavailable"))
    assertEquals(
      e,
      StoreError.Transient(
        "DynamoDB PutItem on QueryJob: DynamoDbException (status 503, code ServiceUnavailable)"
      )
    )
  }
}
