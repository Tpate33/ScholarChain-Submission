package scholarchain.storage.aws

import java.io.{IOException, UncheckedIOException}
import java.net.URI
import java.time.Duration

import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.exception.{
  AbortedException,
  ApiCallAttemptTimeoutException,
  ApiCallTimeoutException,
  SdkClientException
}

import scholarchain.storage.aws.dynamodb.{DynamoDbClientSettings, DynamoDbErrors}
import scholarchain.storage.model.StoreError

/**
 * SE-INT-04: the explicit retry setting of every SDK client storage builds (Q4, checked 2026-11-22:
 * the SDK default changed from the legacy to the standard strategy in 2.58.0 and the project pins
 * 2.57.8, so storage never relies on the default). The client is built with
 * `AwsRetryStrategy.standardRetryStrategy().toBuilder().maxAttempts(maxAttempts).build()`:
 * `maxAttempts` is the TOTAL number of attempts of one SDK call (first attempt included), all of
 * them inside `apiCallTimeout`.
 *
 * Bound 1..[[RetrySetting.MaxAttempts]] is a storage sanity bound, not an AWS limit.
 */
final case class RetrySetting private (maxAttempts: Int)

object RetrySetting {
  val MaxAttempts = 10

  def of(maxAttempts: Int): Either[StoreError, RetrySetting] =
    if (maxAttempts >= 1 && maxAttempts <= MaxAttempts) Right(new RetrySetting(maxAttempts))
    else Left(StoreError.Refused(s"retry: maxAttempts must be in 1..$MaxAttempts"))
}

/**
 * SE-INT-04: settings of one SDK client (S3, S3 Vectors; Neptune through
 * [[scholarchain.storage.aws.neptune.NeptuneClientSettings]]). Every value comes from the caller;
 * nothing has a default. Region, endpoint and timeout rules are exactly those of
 * [[DynamoDbClientSettings]] (the SDK sets neither timeout by default, Q4), plus the explicit
 * [[RetrySetting]].
 */
final case class AwsClientSettings private (
    region: String,
    endpointOverride: Option[URI],
    apiCallTimeoutMillis: Long,
    apiCallAttemptTimeoutMillis: Long,
    retry: RetrySetting
)

object AwsClientSettings {
  def of(
      region: String,
      endpointOverride: Option[String],
      apiCallTimeoutMillis: Long,
      apiCallAttemptTimeoutMillis: Long,
      retryMaxAttempts: Int
  ): Either[StoreError, AwsClientSettings] =
    for {
      d <- DynamoDbClientSettings.of(
        region,
        endpointOverride,
        apiCallTimeoutMillis,
        apiCallAttemptTimeoutMillis
      )
      r <- RetrySetting.of(retryMaxAttempts)
    } yield new AwsClientSettings(
      d.region,
      d.endpointOverride,
      d.apiCallTimeoutMillis,
      d.apiCallAttemptTimeoutMillis,
      r
    )

  /** Storage-internal: settings whose endpoint was already checked by a stricter rule. */
  private[aws] def withEndpoint(s: AwsClientSettings, endpoint: URI): AwsClientSettings =
    new AwsClientSettings(
      s.region,
      Some(endpoint),
      s.apiCallTimeoutMillis,
      s.apiCallAttemptTimeoutMillis,
      s.retry
    )
}

/**
 * SE-INT-05 (CR-INT-07-2): the ambient process settings an SDK client could take an endpoint from,
 * as name-to-value maps. Only the NAMES are ever read by storage; values are never inspected,
 * logged or put into an error. Tests inject stub maps; production uses [[AmbientSettings.process]].
 */
final case class AmbientSettings(
    environment: () => Map[String, String],
    systemProperties: () => Map[String, String]
)

object AmbientSettings {

  /** The process: `System.getenv` and `System.getProperties`, read at each check. */
  val process: AmbientSettings = AmbientSettings(
    () => System.getenv().asScala.toMap,
    () => {
      val p = System.getProperties
      p.stringPropertyNames().asScala.iterator.map(n => n -> "").toMap
    }
  )

  /** No environment and no properties (tests). */
  val empty: AmbientSettings = AmbientSettings(() => Map.empty, () => Map.empty)
}

/**
 * SE-INT-05 (CR-INT-07-2): thrown by the storage SDK client builders when [[AmbientEndpoints]]
 * refuses; the message names setting names only.
 */
final class AmbientEndpointException(message: String) extends IllegalStateException(message)

/**
 * SE-INT-05 (CR-INT-07-2): the ambient-endpoint guard of every storage-built SDK client that has no
 * explicit `endpointOverride` (S3, S3 Vectors, DynamoDB; Neptune always has an explicit endpoint).
 *
 * Rule: when `endpointOverride` is None, the client is refused BEFORE its SDK builder runs if any
 * environment variable NAME starts with `AWS_ENDPOINT_URL` (the global and every per-service form,
 * for example `AWS_ENDPOINT_URL_S3`, `AWS_ENDPOINT_URL_DYNAMODB`) or any JVM system property NAME
 * starts with `aws.endpointUrl` (for example `aws.endpointUrlS3`). Presence is enough: an empty
 * value is refused too (an empty `AWS_ENDPOINT_URL` was observed to break the client build in
 * SE-INT-04). Names are matched case-insensitively (conservative). The `Refused` names the
 * offending names (sorted) and never a value. An explicit, validated `endpointOverride` (https, or
 * http on loopback: [[scholarchain.storage.aws.dynamodb.DynamoDbClientSettings]]) takes precedence
 * over ambient settings in the SDK and bypasses this check.
 *
 * RESIDUAL (not covered, P): the shared AWS config/profile file `endpoint_url` (top-level or per
 * service section), and any other SDK endpoint-resolution source the fetched documentation does not
 * state, are NOT checked here. Storage cannot read those files under the project's secret-read
 * rules, and the precedence rules beyond the observed `AWS_ENDPOINT_URL` were not verified against
 * current AWS documentation (CR-INT-07-2). The deployment must not ship a config file with an
 * `endpoint_url` (infra-engineer), or must configure an explicit endpoint.
 */
object AmbientEndpoints {
  val EnvironmentPrefix = "AWS_ENDPOINT_URL"
  val PropertyPrefix = "aws.endpointUrl"

  private def names(read: () => Map[String, String]): Either[String, Vector[String]] =
    try Right(Option(read()).map(_.keys.toVector).getOrElse(Vector.empty).filter(_ != null))
    catch { case NonFatal(e) => Left(e.getClass.getSimpleName) }

  /** The offending names (environment first, then properties), or a value-free reason. */
  def offending(ambient: AmbientSettings): Either[String, Vector[String]] =
    if (ambient == null) Left("no ambient settings source")
    else
      for {
        env <- names(ambient.environment)
        props <- names(ambient.systemProperties)
      } yield env
        .filter(_.toUpperCase(java.util.Locale.ROOT).startsWith(EnvironmentPrefix))
        .sorted
        .map(n => s"environment variable $n") ++
        props
          .filter(
            _.toLowerCase(java.util.Locale.ROOT)
              .startsWith(PropertyPrefix.toLowerCase(java.util.Locale.ROOT))
          )
          .sorted
          .map(n => s"system property $n")

  /** Right when `endpointOverride` is set or nothing ambient could redirect the client. */
  def check(
      service: String,
      endpointOverride: Option[URI],
      ambient: AmbientSettings
  ): Either[StoreError, Unit] =
    if (endpointOverride != null && endpointOverride.isDefined) Right(())
    else
      offending(ambient) match {
        case Left(why) =>
          Left(StoreError.Refused(s"$service: ambient endpoint settings unreadable ($why)"))
        case Right(Vector()) => Right(())
        case Right(found) =>
          Left(
            StoreError.Refused(
              s"$service: no endpointOverride is configured, but ambient endpoint settings would " +
                s"redirect the client (${found.mkString(", ")}; values not shown): unset them or " +
                "configure an explicit endpointOverride"
            )
          )
      }

  /** As [[check]], throwing [[AmbientEndpointException]] (for the SDK client builders). */
  def enforce(service: String, endpointOverride: Option[URI], ambient: AmbientSettings): Unit =
    check(service, endpointOverride, ambient).left.foreach(e =>
      throw new AmbientEndpointException(e.message)
    )
}

/** SE-INT-04: the override configuration every storage-built SDK client uses. */
object AwsOverrides {
  def of(
      apiCallTimeoutMillis: Long,
      apiCallAttemptTimeoutMillis: Long,
      retry: RetrySetting
  ): ClientOverrideConfiguration =
    ClientOverrideConfiguration
      .builder()
      .apiCallTimeout(Duration.ofMillis(apiCallTimeoutMillis))
      .apiCallAttemptTimeout(Duration.ofMillis(apiCallAttemptTimeoutMillis))
      .retryStrategy(
        AwsRetryStrategy.standardRetryStrategy().toBuilder().maxAttempts(retry.maxAttempts).build()
      )
      .build()

  def of(s: AwsClientSettings): ClientOverrideConfiguration =
    of(s.apiCallTimeoutMillis, s.apiCallAttemptTimeoutMillis, s.retry)
}

/**
 * SE-INT-04: thrown by a storage seam whose owning bundle or factory was closed. The SDK does not
 * document what a call on a closed client does (Q2, NOT STATED), so storage refuses it before the
 * SDK is reached; the adapters map it to `Refused`.
 */
final class ClientClosedException(what: String) extends IllegalStateException(s"$what is closed")

/**
 * SE-INT-04: an idempotent, ordered closer. Resources are closed in reverse registration order;
 * every close is attempted even when an earlier one throws (failures are recorded by class name
 * only). After [[close]] nothing can be added (an item offered then is closed at once and refused)
 * and [[ensureOpen]] throws [[ClientClosedException]].
 */
final class CloseGroup(name: String) {
  private val lock = new Object
  private var closed = false
  private val items = ArrayBuffer.empty[(String, AutoCloseable)]
  private val failures = ArrayBuffer.empty[String]

  def isClosed: Boolean = lock.synchronized(closed)

  def ensureOpen(): Unit = if (isClosed) throw new ClientClosedException(name)

  def add[A <: AutoCloseable](label: String, a: A): Either[StoreError, A] = {
    val accepted = lock.synchronized {
      if (closed) false
      else {
        items += (label -> a)
        true
      }
    }
    if (accepted) Right(a)
    else {
      closeOne(label, a).foreach(f => lock.synchronized(failures += f))
      Left(StoreError.Refused(s"$name is closed"))
    }
  }

  private def closeOne(label: String, a: AutoCloseable): Option[String] =
    try {
      a.close()
      None
    } catch { case NonFatal(e) => Some(s"$label: ${e.getClass.getSimpleName}") }

  /** Closes every registered resource once; later calls do nothing. */
  def close(): Unit = {
    val toClose = lock.synchronized {
      if (closed) Vector.empty
      else {
        closed = true
        items.toVector.reverse
      }
    }
    val failed = toClose.flatMap { case (l, a) => closeOne(l, a) }
    lock.synchronized(failures ++= failed)
  }

  /** The close failures so far (`label: ExceptionClass`), never a message. */
  def closeFailures: Vector[String] = lock.synchronized(failures.toVector)
}

/**
 * SE-INT-04 (A6): the error-mapping table of the S3, Neptune and S3 Vectors adapters, generalised
 * from [[DynamoDbErrors]]. A detail names the service, the operation, the exception class, the HTTP
 * status and the AWS error code only, never the exception message (which can echo request values).
 *
 *   - [[ClientClosedException]]: Refused (the owning bundle was closed; nothing was sent);
 *   - a service exception whose error code the service table `codeRule` names: Transient when the
 *     table says retry, else Permanent;
 *   - other service exceptions: Transient for status >= 500, 429, an SDK throttling flag or a
 *     throttling code; else Permanent;
 *   - ApiCallTimeout, ApiCallAttemptTimeout, a client exception caused by an IOException, a bare
 *     IOException or UncheckedIOException: Transient;
 *   - interrupt and abort: Permanent (the interrupt flag is restored); everything else: Permanent.
 */
object AwsErrors {

  private def causes(e: Throwable): Iterator[Throwable] =
    Iterator.iterate(e)(_.getCause).takeWhile(_ != null).take(16)

  private def causedByIo(e: Throwable): Boolean =
    causes(e).exists(c => c.isInstanceOf[IOException] || c.isInstanceOf[UncheckedIOException])

  /** The AWS error code of a service exception, or "" when it has none. */
  def code(s: AwsServiceException): String =
    Option(s.awsErrorDetails()).flatMap(d => Option(d.errorCode())).getOrElse("")

  /** The HTTP status of `e` when it is a service exception. */
  def status(e: Throwable): Option[Int] = e match {
    case s: AwsServiceException => Some(s.statusCode())
    case _ => None
  }

  def describe(service: String, op: String, e: Throwable): String = e match {
    case s: AwsServiceException =>
      val c = Some(code(s)).filter(_.nonEmpty).getOrElse("-")
      s"$service $op: ${e.getClass.getSimpleName} (status ${s.statusCode()}, code $c)"
    case _ => s"$service $op: ${e.getClass.getSimpleName}"
  }

  /**
   * @param codeRule
   *   the service's documented retry table by error code: Some(true) retry, Some(false) no retry,
   *   None not listed (the status rule applies)
   */
  def classify(
      service: String,
      op: String,
      e: Throwable,
      codeRule: String => Option[Boolean] = _ => None
  ): StoreError = {
    val d = describe(service, op, e)
    e match {
      case _: ClientClosedException => StoreError.Refused(d)
      case s: AwsServiceException =>
        codeRule(code(s)) match {
          case Some(true) => StoreError.Transient(d)
          case Some(false) => StoreError.Permanent(d)
          case None =>
            if (
              s.statusCode() >= 500 || s.statusCode() == 429 || s.isThrottlingException ||
              DynamoDbErrors.ThrottlingCodes.contains(code(s))
            ) StoreError.Transient(d)
            else StoreError.Permanent(d)
        }
      case _: ApiCallTimeoutException | _: ApiCallAttemptTimeoutException =>
        StoreError.Transient(d)
      case _: InterruptedException =>
        Thread.currentThread().interrupt()
        StoreError.Permanent(d + " (interrupted)")
      case a: AbortedException =>
        if (causes(a).exists(_.isInstanceOf[InterruptedException]))
          Thread.currentThread().interrupt()
        StoreError.Permanent(d + " (aborted)")
      case c: SdkClientException if causedByIo(c) => StoreError.Transient(d)
      case _: IOException | _: UncheckedIOException => StoreError.Transient(d)
      case _ => StoreError.Permanent(d)
    }
  }
}
