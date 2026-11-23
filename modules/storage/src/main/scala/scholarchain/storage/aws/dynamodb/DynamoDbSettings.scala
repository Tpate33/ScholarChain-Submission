package scholarchain.storage.aws.dynamodb

import java.net.URI
import java.time.Duration

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient

import scholarchain.storage.model.{StoreError, TableRole}

/**
 * SE-INT-01: settings of the SDK client. Every value comes from the caller (configuration); there
 * is no default region, endpoint or timeout. Proposed core keys: see the SE-INT-01 routing items.
 *
 * @param region
 *   the AWS Region code, for example as configured for the deployment
 * @param endpointOverride
 *   for local use only (a DynamoDB-compatible endpoint): `https`, or `http` on a loopback host
 * @param apiCallTimeoutMillis
 *   the bound of one whole SDK call including the SDK's own retries
 * @param apiCallAttemptTimeoutMillis
 *   the bound of one HTTP attempt; below `apiCallTimeoutMillis`
 */
final case class DynamoDbClientSettings private (
    region: String,
    endpointOverride: Option[URI],
    apiCallTimeoutMillis: Long,
    apiCallAttemptTimeoutMillis: Long
)

object DynamoDbClientSettings {
  private val RegionCode = "^[a-z]{2}(-[a-z]+)+-[0-9]{1,2}$".r
  private val Loopback = Set("localhost", "127.0.0.1", "[::1]", "::1")

  private def endpoint(s: String): Either[StoreError, URI] =
    (try Right(new URI(s))
    catch { case _: Exception => Left(StoreError.Refused("endpointOverride: not a URI")) })
      .flatMap { u =>
        val scheme = Option(u.getScheme).getOrElse("")
        val host = Option(u.getHost).getOrElse("")
        if (host.isEmpty || u.getRawUserInfo != null || u.getRawQuery != null)
          Left(StoreError.Refused("endpointOverride: needs a host and no user info or query"))
        else if (scheme == "https" || (scheme == "http" && Loopback.contains(host))) Right(u)
        else Left(StoreError.Refused("endpointOverride: https, or http on a loopback host only"))
      }

  def of(
      region: String,
      endpointOverride: Option[String],
      apiCallTimeoutMillis: Long,
      apiCallAttemptTimeoutMillis: Long
  ): Either[StoreError, DynamoDbClientSettings] =
    for {
      r <-
        if (region != null && RegionCode.matches(region)) Right(region)
        else Left(StoreError.Refused("region: not a Region code"))
      e <- Option(endpointOverride) match {
        case None => Left(StoreError.Refused("endpointOverride: must be absent or present"))
        case Some(None) => Right(None)
        case Some(Some(s)) => endpoint(s).map(Some(_))
      }
      _ <-
        if (apiCallAttemptTimeoutMillis >= 1 && apiCallTimeoutMillis > apiCallAttemptTimeoutMillis)
          Right(())
        else
          Left(
            StoreError.Refused(
              "timeouts: apiCallAttemptTimeoutMillis must be >= 1 and below apiCallTimeoutMillis"
            )
          )
    } yield new DynamoDbClientSettings(r, e, apiCallTimeoutMillis, apiCallAttemptTimeoutMillis)
}

/** SE-INT-01: builds the SDK client from caller settings (no AWS call is made by building it). */
object DynamoDbClients {

  /**
   * A synchronous `DynamoDbClient` with the given Region, optional endpoint override, and call and
   * attempt timeouts. The SDK's own retry policy is left at its default and is bounded by
   * `apiCallTimeoutMillis` (the default DynamoDB retry count is not re-verified: UNVERIFIED). The
   * caller owns and closes the client.
   */
  def build(s: DynamoDbClientSettings, credentials: AwsCredentialsProvider): DynamoDbClient = {
    require(s != null && credentials != null, "settings and credentials are required")
    // SE-INT-05 (CR-INT-07-2): the same ambient-endpoint guard as every other storage build path.
    scholarchain.storage.aws.AmbientEndpoints
      .enforce("dynamoDb", s.endpointOverride, scholarchain.storage.aws.AmbientSettings.process)
    val b = DynamoDbClient
      .builder()
      .region(Region.of(s.region))
      .credentialsProvider(credentials)
      .overrideConfiguration(
        ClientOverrideConfiguration
          .builder()
          .apiCallTimeout(Duration.ofMillis(s.apiCallTimeoutMillis))
          .apiCallAttemptTimeout(Duration.ofMillis(s.apiCallAttemptTimeoutMillis))
          .build()
      )
    s.endpointOverride.foreach(u => b.endpointOverride(u))
    b.build()
  }

  /**
   * SE-INT-04: as [[build]], plus the EXPLICIT retry strategy of
   * [[scholarchain.storage.aws.RetrySetting]] (`AwsRetryStrategy.standardRetryStrategy()` with
   * `maxAttempts` total attempts), so the attempt count no longer depends on the SDK default
   * (legacy at the pinned 2.57.8, about 9 attempts for DynamoDB per the SDK guide, Q4). This is the
   * path of [[scholarchain.storage.aws.DynamoDbClientFactory]]; the two-argument [[build]] is kept
   * unchanged for existing callers.
   */
  def build(
      s: DynamoDbClientSettings,
      credentials: AwsCredentialsProvider,
      retry: scholarchain.storage.aws.RetrySetting
  ): DynamoDbClient = build(s, credentials, retry, scholarchain.storage.aws.AmbientSettings.process)

  /**
   * SE-INT-05 (CR-INT-07-2): as the three-argument [[build]], with the ambient settings of the
   * endpoint guard injected. Without `s.endpointOverride`,
   * [[scholarchain.storage.aws.AmbientEndpoints]] runs BEFORE the SDK builder and throws
   * `AmbientEndpointException` (names only) when an ambient `AWS_ENDPOINT_URL*` variable or
   * `aws.endpointUrl*` property is present.
   */
  def build(
      s: DynamoDbClientSettings,
      credentials: AwsCredentialsProvider,
      retry: scholarchain.storage.aws.RetrySetting,
      ambient: scholarchain.storage.aws.AmbientSettings
  ): DynamoDbClient = {
    require(
      s != null && credentials != null && retry != null && ambient != null,
      "settings are required"
    )
    scholarchain.storage.aws.AmbientEndpoints.enforce("dynamoDb", s.endpointOverride, ambient)
    val b = DynamoDbClient
      .builder()
      .region(Region.of(s.region))
      .credentialsProvider(credentials)
      .overrideConfiguration(
        scholarchain.storage.aws.AwsOverrides
          .of(s.apiCallTimeoutMillis, s.apiCallAttemptTimeoutMillis, retry)
      )
    s.endpointOverride.foreach(u => b.endpointOverride(u))
    b.build()
  }
}

/**
 * SE-INT-01: bounds of the adapter (configuration; no defaults).
 *
 * @param maxQueryLimit
 *   the largest `limit` a query accepts (same rule as the in-memory store)
 * @param maxQueryPages
 *   the most Query pages one query may read; reaching it before the limit or the end of the
 *   partition is an error, never a partial result (pages end at 1 MB per AWS documentation as
 *   remembered; UNVERIFIED)
 * @param batchGetMaxAttempts
 *   BatchGetItem calls per chunk (the first plus retries of unprocessed keys); keys still
 *   unprocessed after it are `Transient`
 * @param batchGetBackoffBaseMillis
 *   sleep before the n-th retry: `base * 2^(n-1)`, capped by `batchGetBackoffMaxMillis`
 */
final case class DynamoDbAdapterSettings private (
    maxQueryLimit: Int,
    maxQueryPages: Int,
    batchGetMaxAttempts: Int,
    batchGetBackoffBaseMillis: Long,
    batchGetBackoffMaxMillis: Long
)

object DynamoDbAdapterSettings {
  def of(
      maxQueryLimit: Int,
      maxQueryPages: Int,
      batchGetMaxAttempts: Int,
      batchGetBackoffBaseMillis: Long,
      batchGetBackoffMaxMillis: Long
  ): Either[StoreError, DynamoDbAdapterSettings] =
    if (maxQueryLimit < 1) Left(StoreError.Refused("maxQueryLimit: below 1"))
    else if (maxQueryPages < 1) Left(StoreError.Refused("maxQueryPages: below 1"))
    else if (batchGetMaxAttempts < 1) Left(StoreError.Refused("batchGetMaxAttempts: below 1"))
    else if (batchGetBackoffBaseMillis < 0)
      Left(StoreError.Refused("batchGetBackoffBaseMillis: negative"))
    else if (batchGetBackoffMaxMillis < batchGetBackoffBaseMillis)
      Left(StoreError.Refused("batchGetBackoffMaxMillis: below batchGetBackoffBaseMillis"))
    else
      Right(
        new DynamoDbAdapterSettings(
          maxQueryLimit,
          maxQueryPages,
          batchGetMaxAttempts,
          batchGetBackoffBaseMillis,
          batchGetBackoffMaxMillis
        )
      )
}

/**
 * SE-INT-01: physical table name per logical role (configuration). A role without a name is
 * `Refused` when used. Names must be distinct and match `[A-Za-z0-9_.-]{3,255}` (the DynamoDB
 * table-name rule as remembered; UNVERIFIED against AWS documentation on 2026-11-22).
 */
final class DynamoDbTableNames private (names: Map[TableRole, String]) {
  def roles: Set[TableRole] = names.keySet

  def of(role: TableRole): Either[StoreError, String] =
    Option(role)
      .flatMap(names.get)
      .toRight(
        StoreError.Refused(s"table: no physical name for ${Option(role).map(_.tag).orNull}")
      )

  override def toString: String = s"DynamoDbTableNames(${names.keySet.map(_.tag).toVector.sorted})"
}

object DynamoDbTableNames {
  private val Name = "^[A-Za-z0-9_.-]{3,255}$".r

  def of(names: Map[TableRole, String]): Either[StoreError, DynamoDbTableNames] =
    if (names == null || names.isEmpty) Left(StoreError.Refused("tables: at least one name"))
    else if (names.exists { case (r, n) => r == null || n == null || !Name.matches(n) })
      Left(StoreError.Refused("tables: every name must match [A-Za-z0-9_.-]{3,255}"))
    else if (names.values.toVector.distinct.size != names.size)
      Left(StoreError.Refused("tables: names must be distinct"))
    else Right(new DynamoDbTableNames(names))
}
