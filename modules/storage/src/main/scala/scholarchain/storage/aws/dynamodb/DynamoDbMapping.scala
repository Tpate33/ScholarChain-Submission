package scholarchain.storage.aws.dynamodb

import java.io.{IOException, UncheckedIOException}

import scala.jdk.CollectionConverters.*

import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.core.exception.{
  AbortedException,
  ApiCallAttemptTimeoutException,
  ApiCallTimeoutException,
  SdkClientException
}
import software.amazon.awssdk.services.dynamodb.model.{
  AttributeValue,
  InternalServerErrorException,
  ProvisionedThroughputExceededException,
  RequestLimitExceededException,
  TransactionConflictException
}

import scholarchain.storage.model.{AttrValue, Item, StoreError}

/** SE-INT-01: one-to-one mapping between storage [[AttrValue]] and SDK `AttributeValue`. */
object DynamoDbAttrs {

  /** Nesting accepted when decoding (DynamoDB allows 32 levels as remembered; UNVERIFIED). */
  val MaxDepth = 32

  def toSdk(v: AttrValue): AttributeValue = v match {
    case AttrValue.S(s) => AttributeValue.fromS(s)
    case AttrValue.N(n) => AttributeValue.fromN(n)
    case AttrValue.Bool(b) => AttributeValue.fromBool(java.lang.Boolean.valueOf(b))
    case AttrValue.Null => AttributeValue.fromNul(java.lang.Boolean.TRUE)
    case AttrValue.L(xs) => AttributeValue.fromL(xs.map(toSdk).asJava)
    case AttrValue.M(m) => AttributeValue.fromM(m.map { case (k, x) => k -> toSdk(x) }.asJava)
  }

  def toSdkItem(item: Item): java.util.Map[String, AttributeValue] =
    item.map { case (k, v) => k -> toSdk(v) }.asJava

  private def traverse[A](xs: Iterable[A])(f: A => Either[String, AttrValue]) =
    xs.foldLeft[Either[String, Vector[AttrValue]]](Right(Vector.empty)) { (acc, x) =>
      acc.flatMap(v => f(x).map(v :+ _))
    }

  def fromSdk(a: AttributeValue, depth: Int = 0): Either[String, AttrValue] =
    if (a == null) Left("null attribute value")
    else if (depth > MaxDepth) Left("attribute nesting too deep")
    else
      a.`type`() match {
        case AttributeValue.Type.S => Right(AttrValue.S(a.s()))
        case AttributeValue.Type.N => Right(AttrValue.N(a.n()))
        case AttributeValue.Type.BOOL => Right(AttrValue.Bool(a.bool().booleanValue))
        case AttributeValue.Type.NUL => Right(AttrValue.Null)
        case AttributeValue.Type.L =>
          traverse(a.l().asScala)(fromSdk(_, depth + 1)).map(AttrValue.L(_))
        case AttributeValue.Type.M =>
          val entries = a.m().asScala.toVector
          traverse(entries)(e => fromSdk(e._2, depth + 1))
            .map(vs => AttrValue.M(entries.map(_._1).zip(vs).toMap))
        case other => Left(s"unsupported attribute type $other")
      }

  /** A stored item, or `Corrupt` naming the attribute (never its value). */
  def fromSdkItem(item: java.util.Map[String, AttributeValue]): Either[StoreError, Item] =
    item.asScala.toVector
      .foldLeft[Either[StoreError, Item]](Right(Map.empty)) { case (acc, (name, v)) =>
        acc.flatMap(m =>
          fromSdk(v).left
            .map(why => StoreError.Corrupt(s"attribute $name: $why"))
            .map(x => m + (name -> x))
        )
      }
}

/**
 * SE-INT-01: the error-mapping table of the adapter. A detail names the operation, the table role,
 * the exception class, the HTTP status and the AWS error code only: never the exception message,
 * which can echo request values.
 *
 * | Failure                                                       | StoreError                                                                         |
 * |:--------------------------------------------------------------|:-----------------------------------------------------------------------------------|
 * | ProvisionedThroughputExceeded, RequestLimitExceeded,          | Transient                                                                          |
 * | TransactionConflict (SE-INT-02: an item held by a concurrent  |                                                                                    |
 * | transaction; the adapter itself issues none)                  |                                                                                    |
 * | InternalServerError, any service exception with status >= 500 | Transient                                                                          |
 * | status 429, a throttling error code, SDK throttling flag      | Transient                                                                          |
 * | ApiCallTimeout, ApiCallAttemptTimeout                         | Transient                                                                          |
 * | SE-INT-02: client exception caused by any IOException (socket | Transient                                                                          |
 * | or connect timeout, connection reset, SSL, connection closed) |                                                                                    |
 * | or UncheckedIOException; a bare IOException                   |                                                                                    |
 * | SE-INT-04: ClientClosedException (closed bundle or factory)   | Refused                                                                            |
 * | ConditionalCheckFailed                                        | per operation: Conflict (putIfAbsent, deleteIfRecord), StaleVersion (putIfVersion) |
 * | AbortedException, InterruptedException (interrupt restored)   | Permanent                                                                          |
 * | everything else (validation, missing table, access denied)    | Permanent                                                                          |
 */
object DynamoDbErrors {

  /** AWS throttling error codes (as remembered; UNVERIFIED against AWS documentation). */
  val ThrottlingCodes: Set[String] = Set(
    "ThrottlingException",
    "Throttling",
    "ThrottledException",
    "RequestThrottled",
    "RequestThrottledException",
    "TooManyRequestsException",
    "ProvisionedThroughputExceededException",
    "RequestLimitExceeded",
    "SlowDown"
  )

  private def causes(e: Throwable): Iterator[Throwable] =
    Iterator.iterate(e)(_.getCause).takeWhile(_ != null).take(16)

  /**
   * SE-INT-02 (CR-INT-01-5): a transport failure anywhere in the cause chain: any `IOException`
   * (socket and connect timeouts, `SocketException` such as "Connection reset", `SSLException`,
   * `EOFException`, a closed or unanswered connection), or an `UncheckedIOException`.
   */
  private def causedByIo(e: Throwable): Boolean =
    causes(e).exists(c => c.isInstanceOf[IOException] || c.isInstanceOf[UncheckedIOException])

  private def describe(op: String, role: String, e: Throwable): String = e match {
    case s: AwsServiceException =>
      val code = Option(s.awsErrorDetails()).flatMap(d => Option(d.errorCode())).getOrElse("-")
      s"DynamoDB $op on $role: ${e.getClass.getSimpleName} (status ${s.statusCode()}, code $code)"
    case _ => s"DynamoDB $op on $role: ${e.getClass.getSimpleName}"
  }

  def classify(op: String, role: String, e: Throwable): StoreError = {
    val d = describe(op, role, e)
    e match {
      // SE-INT-04: a seam of a closed bundle or factory refuses before the SDK is reached.
      case _: scholarchain.storage.aws.ClientClosedException => StoreError.Refused(d)
      case _: ProvisionedThroughputExceededException | _: RequestLimitExceededException |
          _: InternalServerErrorException | _: TransactionConflictException =>
        StoreError.Transient(d)
      case s: AwsServiceException =>
        val code = Option(s.awsErrorDetails()).flatMap(x => Option(x.errorCode())).getOrElse("")
        if (
          s.statusCode() >= 500 || s.statusCode() == 429 || s.isThrottlingException ||
          ThrottlingCodes.contains(code)
        ) StoreError.Transient(d)
        else StoreError.Permanent(d)
      case _: ApiCallTimeoutException | _: ApiCallAttemptTimeoutException =>
        StoreError.Transient(d)
      case _: InterruptedException =>
        Thread.currentThread().interrupt()
        StoreError.Permanent(d + " (interrupted)")
      case a: AbortedException =>
        if (causes(a).exists(_.isInstanceOf[InterruptedException]))
          Thread.currentThread().interrupt()
        StoreError.Permanent(d + " (aborted)")
      // Every adapter write is conditional, so retrying after a transport failure is safe even
      // when the write was applied (the stores recover the ambiguous outcome, CR-INT-01-1).
      case c: SdkClientException if causedByIo(c) => StoreError.Transient(d)
      case _: IOException | _: UncheckedIOException => StoreError.Transient(d)
      case _ => StoreError.Permanent(d)
    }
  }
}
