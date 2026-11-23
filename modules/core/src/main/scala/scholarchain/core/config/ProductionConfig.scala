package scholarchain.core.config

import java.net.URI

import scala.collection.immutable.SortedMap
import scala.util.control.NonFatal

import upickle.default.ReadWriter

import scholarchain.core.{StableId, TimeWindow}

/*
 * DE-INT-01 (integration round 01): production configuration contracts of the DynamoDB adapter,
 * the durable service-state stores and the three service processes.
 *
 * Every key below is OPTIONAL at load time (additive within `scholarchain.config.v2`, the same
 * precedent as the grouping and analysis keys), so every existing configuration loads unchanged and
 * keeps its exact digest (unset keys are omitted from the digest input). A set key is checked
 * against its own bound when loaded; a cross-field rule is checked when every key it names is set.
 * A process that needs a section refuses a configuration without it ([[ProductionSettings]]).
 *
 * Core depends on no other internal module (CLAUDE.md, module graph), so the storage and services
 * bounds are RESTATED here; each mirrored constant names its source file and line (working tree of
 * 2026-11-22, SE-INT-01/02 and Phase 10 code). A change there needs the same change here.
 *
 * No value here is a secret. Error reasons name keys and bounds, never values.
 */

/** Mirrored constants (see the source references on each). */
object ProductionBounds {

  /**
   * Ten years in seconds: storage `ServiceStateSettings.MaxTtlSeconds`
   * (modules/storage/src/main/scala/scholarchain/storage/state/ServiceStateStores.scala:82).
   */
  val MaxTtlSeconds: Long = 10L * 366 * 24 * 3600

  /** The same in milliseconds: storage `ServiceStateSettings.MaxDurationMillis` (same file:88). */
  val MaxDurationMillis: Long = MaxTtlSeconds * 1000L

  /**
   * Storage `ServiceStateBounds.CeilingBytes`
   * (modules/storage/src/main/scala/scholarchain/storage/model/ServiceState.scala:72).
   */
  val MaxPayloadBytes: Int = 262144

  /** Storage `ServiceStateSettings.of` maxConflictRetries bound (ServiceStateStores.scala:148). */
  val MaxConflictRetries: Int = 100

  /** Storage `ServiceStateIds.MaxTokenLength` (ServiceState.scala:15), the writer token bound. */
  val MaxTokenLength: Int = 128

  /**
   * Storage `DynamoDbClientSettings` Region code
   * (modules/storage/src/main/scala/scholarchain/storage/aws/dynamodb/DynamoDbSettings.scala:34).
   */
  val RegionPattern: String = "^[a-z]{2}(-[a-z]+)+-[0-9]{1,2}$"

  /** Storage `DynamoDbTableNames` name rule (DynamoDbSettings.scala:172). */
  val TableNamePattern: String = "^[A-Za-z0-9_.-]{3,255}$"

  /**
   * Storage `DynamoDbClientSettings` loopback hosts for an http endpoint
   * (DynamoDbSettings.scala:35).
   */
  val LoopbackHosts: Set[String] = Set("localhost", "127.0.0.1", "[::1]", "::1")

  /**
   * Services `GraphService.SupportedEdgeTypes`
   * (modules/services/src/main/scala/scholarchain/services/ReadServices.scala:353-358).
   */
  val SupportedEdgeTypes: Vector[String] = Vector("coauthored-with", "affiliated-with", "authored")

  /** Services `GraphService.MaxSupportedDepth` (ReadServices.scala:361). */
  val MaxSupportedDepth: Int = 1

  /** Services `HttpServerAdapter.start` port bound (HttpServerAdapter.scala:122). */
  val MaxPort: Int = 65535

  /**
   * Granularity of `expiresAt` (epoch seconds, DynamoDB TTL). Storage floors job, ownership and
   * completed-record expiries to the second (CR-INT-02-3), so the TTL rules add one second.
   */
  val SecondMillis: Long = 1000L

  /** Longest host name. */
  val MaxHostLength: Int = 253

  /**
   * Keys per BatchGetItem call of the adapter: storage `KeyValueBatchReader.MaxKeysPerCall`
   * (modules/storage/src/main/scala/scholarchain/storage/port/KeyValueStore.scala:78), by which
   * `DynamoDbKeyValueStore.getMany` splits one read into chunks (DynamoDbKeyValueStore.scala:227).
   */
  val BatchGetKeysPerCall: Int = 100

  /**
   * The last BatchGetItem retry whose backoff factor is `2^(retry-1)`: storage
   * `DynamoDbKeyValueStore.backoff` (DynamoDbKeyValueStore.scala:239-246) caps the factor at
   * `Long.MaxValue` once `retry - 1 >= 30`, so every retry from 31 on sleeps exactly `max` (when
   * `base > 0`). CR-INT-04-4(iii).
   */
  val BackoffDoublingRetries: Int = 30

  /**
   * DE-INT-03 (CR-INT-05-1): the most DynamoDB SDK calls ONE attempt of storage
   * `QueryOwnershipStore.record` makes (modules/storage/src/main/scala/scholarchain/storage/state/
   * ServiceStateStores.scala:265-288), counted on the adapter's path
   * (modules/storage/src/main/scala/scholarchain/storage/aws/dynamodb/DynamoDbKeyValueStore.scala):
   *   1. the conditional PutItem of `putIfAbsent` (ServiceStateStores.scala:276;
   *      DynamoDbKeyValueStore.scala:331-341);
   *   2. on ConditionalCheckFailed without ALL_OLD, the consistent GetItem fallback inside
   *      `putIfAbsent` (DynamoDbKeyValueStore.scala:344-348, via `get` at :109-129, one call);
   *   3. on the resulting `Conflict` (a different record: DynamoDbKeyValueStore.scala:352-353), the
   *      conflict-recovery GetItem of `stored` (ServiceStateStores.scala:278-279 and :255-263, via
   *      `get`, one call).
   * Nothing else in the path calls the SDK (encoding and decoding are local; the adapter does not
   * retry on its own, :94-99), and every SDK call, including the SDK's internal retries, is bounded
   * by `apiCallTimeoutMillis` (DynamoDbSettings.scala:21-22, :81-82, :94). With ALL_OLD returned
   * the count is 2; 3 is the worst case (whether DynamoDB always returns ALL_OLD is UNVERIFIED).
   */
  val OwnershipRecordSdkCalls: Int = 3

  /**
   * CR-INT-04-6: the largest `services.<kind>.server.maxBodyBytes` the services body reader
   * handles. `HttpServerAdapter.readBounded`
   * (modules/services/src/main/scala/scholarchain/services/ HttpServerAdapter.scala:73-86) computes
   * `max + 1 - total` in Int (:79), which wraps at `max = Int.MaxValue`, so the arithmetic bound is
   * `Int.MaxValue - 1` (2147483646: then `total <= max` gives `1 <= max + 1 - total <= max + 1` and
   * `total + keep <= max + 1 <= Int.MaxValue`). The reader also buffers up to `max + 1` bytes in
   * one `ByteArrayOutputStream`, whose growth stays within the JDK soft maximum array length
   * `Integer.MAX_VALUE - 8` (`jdk.internal.util.ArraysSupport.SOFT_MAX_ARRAY_LENGTH`; as
   * remembered, unverified here) only while `max + 1 <= Int.MaxValue - 8`; beyond it the allocation
   * can fail with OutOfMemoryError, which is fatal and not caught by the reader's NonFatal handler
   * (:117). The cap is therefore `Int.MaxValue - 9` (2147483638).
   */
  val MaxServerBodyBytes: Int = Int.MaxValue - 9

  /** Storage `DynamoDbClientSettings.endpoint` (DynamoDbSettings.scala:37-47), restated. */
  def endpointOk(s: String): Boolean =
    s != null && s.nonEmpty && !s.exists(c => c.isWhitespace || c.isControl) &&
      (try {
        val u = new URI(s)
        val scheme = Option(u.getScheme).getOrElse("")
        val host = Option(u.getHost).getOrElse("")
        host.nonEmpty && u.getRawUserInfo == null && u.getRawQuery == null &&
        (scheme == "https" || (scheme == "http" && LoopbackHosts.contains(host)))
      } catch { case NonFatal(_) => false })
}

/** The three service processes and their key segment (services `ServiceKind.tag`). */
enum ServiceKindKey(val tag: String) {
  case QueryJobs extends ServiceKindKey("query-jobs")
  case EvidenceExplain extends ServiceKindKey("evidence-explain")
  case Graph extends ServiceKindKey("graph")

  /** `services.<tag>.` */
  def prefix: String = s"services.$tag."
}

/** Physical DynamoDB table name per storage `TableRole` (TableSpecs.scala:15-25). */
final case class DynamoDbTablesConfig(
    runLedger: Option[String] = None,
    artifactRegistry: Option[String] = None,
    evidenceBundle: Option[String] = None,
    querySession: Option[String] = None,
    entityLinkCache: Option[String] = None,
    researcherMetricCache: Option[String] = None,
    queryOwnership: Option[String] = None,
    queryJob: Option[String] = None,
    idempotencyKey: Option[String] = None
) derives ReadWriter

/** `storage.dynamodb.*`: storage `DynamoDbClientSettings` and `DynamoDbAdapterSettings`. */
final case class DynamoDbConfig(
    region: Option[String] = None,
    endpointOverride: Option[String] = None,
    tables: DynamoDbTablesConfig = DynamoDbTablesConfig(),
    maxQueryLimit: Option[Int] = None,
    maxQueryPages: Option[Int] = None,
    apiCallTimeoutMillis: Option[Long] = None,
    apiCallAttemptTimeoutMillis: Option[Long] = None,
    batchGetMaxAttempts: Option[Int] = None,
    batchGetBackoffBaseMillis: Option[Long] = None,
    batchGetBackoffMaxMillis: Option[Long] = None,
    // DE-INT-04: storage `RetrySetting` of the DynamoDB client factory (AdapterKeys.dynamoDbRetry).
    retryMaxAttempts: Option[Int] = None
) derives ReadWriter

/**
 * `storage.serviceState.*`: storage `ServiceStateSettings` plus `querySessionTtlSeconds`, the TTL
 * of QuerySession rows that the ownership TTL is checked against (the writer of QuerySession rows
 * must use this value: a wiring obligation).
 */
final case class ServiceStateConfig(
    writer: Option[String] = None,
    querySessionTtlSeconds: Option[Long] = None,
    ownershipTtlSeconds: Option[Long] = None,
    jobTtlSeconds: Option[Long] = None,
    completedTtlSeconds: Option[Long] = None,
    inFlightTtlSeconds: Option[Long] = None,
    maxPayloadBytes: Option[Int] = None,
    maxConflictRetries: Option[Int] = None,
    maxOwnedByQueryIds: Option[Int] = None,
    maxClockSkewMillis: Option[Long] = None
) derives ReadWriter

/**
 * `services.<kind>.server.*`: services `HttpServerAdapter.Settings`
 * (HttpServerAdapter.scala:58-66).
 */
final case class ServerConfig(
    host: Option[String] = None,
    port: Option[Int] = None,
    backlog: Option[Int] = None,
    workers: Option[Int] = None,
    queueCapacity: Option[Int] = None,
    maxBodyBytes: Option[Int] = None,
    overflowCapacity: Option[Int] = None
) derives ReadWriter

/**
 * `services.<kind>.*`: every services `ServiceConfig` field (ServiceConfig.scala:83-118), in the
 * same order, then the server bind and the shutdown timing of `ServiceSettings`
 * (ServiceMain.scala:20-25).
 */
final case class ServiceKindConfig(
    maxBodyBytes: Option[Int] = None,
    maxQuestionBytes: Option[Int] = None,
    syncTimeoutMillis: Option[Long] = None,
    asyncTimeoutMillis: Option[Long] = None,
    readTimeoutMillis: Option[Long] = None,
    readMaxAttempts: Option[Int] = None,
    breakerFailureThreshold: Option[Int] = None,
    breakerOpenMillis: Option[Long] = None,
    readRequestTimeoutMillis: Option[Long] = None,
    queueWaitMillis: Option[Long] = None,
    workflowWorkers: Option[Int] = None,
    workflowQueueCapacity: Option[Int] = None,
    keyValueWorkers: Option[Int] = None,
    keyValueQueueCapacity: Option[Int] = None,
    graphWorkers: Option[Int] = None,
    graphQueueCapacity: Option[Int] = None,
    jobWorkers: Option[Int] = None,
    jobQueueCapacity: Option[Int] = None,
    maxJobs: Option[Int] = None,
    idempotencyCapacity: Option[Int] = None,
    idempotencyTtlMillis: Option[Long] = None,
    queryOwnershipCapacity: Option[Int] = None,
    graphMaxDepth: Option[Int] = None,
    graphMaxPageSize: Option[Int] = None,
    graphDefaultPageSize: Option[Int] = None,
    graphMaxResults: Option[Int] = None,
    graphEdgeTypes: Option[Vector[String]] = None,
    graphDefaultWindow: Option[TimeWindow] = None,
    evidenceMaxContentBytes: Option[Int] = None,
    evidenceMaxBundles: Option[Int] = None,
    evidenceScanLimit: Option[Int] = None,
    explainMaxSteps: Option[Int] = None,
    metricsEnabled: Option[Boolean] = None,
    retryAfterSeconds: Option[Int] = None,
    server: ServerConfig = ServerConfig(),
    graceSeconds: Option[Int] = None,
    drainMillis: Option[Long] = None
) derives ReadWriter

/** `services.*`, one section per process. */
final case class ServicesConfig(
    queryJobs: ServiceKindConfig = ServiceKindConfig(),
    evidenceExplain: ServiceKindConfig = ServiceKindConfig(),
    graph: ServiceKindConfig = ServiceKindConfig(),
    // DE-INT-04: `services.apiKeys.*`, the API-key source shared by the three processes.
    apiKeys: ApiKeySourceConfig = ApiKeySourceConfig()
) derives ReadWriter {
  def of(kind: ServiceKindKey): ServiceKindConfig = kind match {
    case ServiceKindKey.QueryJobs => queryJobs
    case ServiceKindKey.EvidenceExplain => evidenceExplain
    case ServiceKindKey.Graph => graph
  }
}

/** The individual bound of one key, as data. Reasons name the bound, never the value. */
enum KeyBound {

  /** An Int or Long value in `[min, max]`. */
  case Range(min: Long, max: Long)

  /** A finite decimal in the closed range. */
  case DecimalRange(min: Double, max: Double)

  /** An absolute local artifact directory path, never an URI or traversal. */
  case LocalDirectory

  /** An archived producer configuration file path; existence is checked by the reader. */
  case LocalArtifactFile

  /** A string fully matching `pattern`. */
  case Matches(pattern: String, what: String)

  /** A token `[A-Za-z0-9._-]` of 1..maxLength characters. */
  case Token(maxLength: Int)

  /** An endpoint override: https, or http on a loopback host; a host; no user info or query. */
  case Endpoint

  /**
   * A bind host: non-empty, at most [[ProductionBounds.MaxHostLength]], no whitespace or control.
   */
  case Host

  /** A non-empty list whose every element is one of `allowed`. */
  case Subset(allowed: Vector[String])

  /** Any value of the key's kind (boolean, window). */
  case Unbounded

  /**
   * DE-INT-04: the Neptune data-API endpoint `https://<host>:<port>`, exactly the storage rule
   * ([[AdapterBounds.neptuneEndpointOk]]).
   */
  case NeptuneEndpoint

  /**
   * DE-INT-04: a Secrets Manager secret reference, an ARN or a name
   * ([[AdapterBounds.secretRefOk]]).
   */
  case SecretReference

  /** Why `v` violates the bound, or None. */
  def problem(v: Any): Option[String] = this match {
    case LocalDirectory | LocalArtifactFile =>
      val artifact = if (this == LocalArtifactFile) "file" else "directory"
      v match {
        case s: String if s.nonEmpty && !s.exists(_.isControl) =>
          scala.util.Try(java.nio.file.Paths.get(s)).toOption match {
            case Some(p) if p.isAbsolute && p.normalize() == p && !s.startsWith("//") => None
            case _ => Some(s"must be an absolute normalized local artifact $artifact path")
          }
        case _ => Some(s"must be an absolute normalized local artifact $artifact path")
      }
    case DecimalRange(min, max) =>
      v match {
        case n: Double if n.isFinite && n >= min && n <= max => None
        case _ => Some(s"must be a finite decimal in [$min, $max]")
      }
    case NeptuneEndpoint =>
      v match {
        case s: String if AdapterBounds.neptuneEndpointOk(s) => None
        case _ =>
          Some(
            "must be https://<host>:<port> (port 1..65535) with no user info, path, query, " +
              "fragment or whitespace"
          )
      }
    case SecretReference =>
      v match {
        case s: String if AdapterBounds.secretRefOk(s) => None
        case _ =>
          Some(
            "must be a Secrets Manager secret ARN or a secret name of 1..512 characters " +
              "[A-Za-z0-9/_+=.@-]"
          )
      }
    case Range(min, max) =>
      val n: Option[Long] = v match {
        case i: Int => Some(i.toLong)
        case l: Long => Some(l)
        case _ => None
      }
      n match {
        case Some(x) if x >= min && x <= max => None
        case _ => Some(s"must be an integer in [$min, $max]")
      }
    case Matches(pattern, what) =>
      v match {
        case s: String if s.matches(pattern) => None
        case _ => Some(s"must be $what (pattern $pattern)")
      }
    case Token(maxLength) =>
      v match {
        case s: String if s.length <= maxLength && StableId.isToken(s) => None
        case _ => Some(s"must be a token [A-Za-z0-9._-]{1,$maxLength}")
      }
    case Endpoint =>
      v match {
        case s: String if ProductionBounds.endpointOk(s) => None
        case _ =>
          Some(
            "must be an https URI, or an http URI on a loopback host, with a host and no user " +
              "info or query"
          )
      }
    case Host =>
      v match {
        case s: String
            if s.nonEmpty && s.length <= ProductionBounds.MaxHostLength &&
              !s.exists(c => c.isWhitespace || c.isControl) =>
          None
        case _ =>
          Some(
            s"must be a host of 1..${ProductionBounds.MaxHostLength} characters without " +
              "whitespace or control characters"
          )
      }
    case Subset(allowed) =>
      v match {
        case xs: Vector[?] if xs.nonEmpty && xs.forall(x => allowed.contains(x)) => None
        case _ => Some(s"must be a non-empty list of ${allowed.mkString(", ")}")
      }
    case Unbounded => if (v == null) Some("missing") else None
  }
}

/** One production key: dotted path, value kind and individual bound. */
final case class KeySpec(path: String, kind: ValueKind, bound: KeyBound) {
  def problem(v: Any): Option[String] = bound.problem(v)
}

/** The frozen production key table (DE-INT-01). */
object ProductionKeys {
  import ValueKind.*
  import ProductionBounds.*

  val DynamoDbPrefix = "storage.dynamodb."
  val ServiceStatePrefix = "storage.serviceState."
  val TablesPrefix: String = DynamoDbPrefix + "tables."

  private val IntMax: Long = Int.MaxValue.toLong
  private def positiveInt(p: String) = KeySpec(p, OptionalInt32, KeyBound.Range(1, IntMax))
  private def millis(p: String, min: Long) =
    KeySpec(p, OptionalInt64, KeyBound.Range(min, MaxDurationMillis))
  private def ttl(p: String) = KeySpec(p, OptionalInt64, KeyBound.Range(1, MaxTtlSeconds))

  /** (key leaf, storage `TableRole.tag`) of the nine tables, in storage `TableRole` order. */
  val TableRoles: Vector[(String, String)] = Vector(
    "runLedger" -> "RunLedger",
    "artifactRegistry" -> "ArtifactRegistry",
    "evidenceBundle" -> "EvidenceBundle",
    "querySession" -> "QuerySession",
    "entityLinkCache" -> "EntityLinkCache",
    "researcherMetricCache" -> "ResearcherMetricCache",
    "queryOwnership" -> "QueryOwnership",
    "queryJob" -> "QueryJob",
    "idempotencyKey" -> "IdempotencyKey"
  )

  val TableKeys: Vector[String] = TableRoles.map(TablesPrefix + _._1)

  val EndpointOverrideKey: String = DynamoDbPrefix + "endpointOverride"

  val dynamoDb: Vector[KeySpec] = {
    val d = DynamoDbPrefix
    Vector(
      KeySpec(d + "region", OptionalString, KeyBound.Matches(RegionPattern, "an AWS Region code")),
      KeySpec(EndpointOverrideKey, OptionalString, KeyBound.Endpoint)
    ) ++ TableKeys.map(
      KeySpec(_, OptionalString, KeyBound.Matches(TableNamePattern, "a DynamoDB table name"))
    ) ++ Vector(
      positiveInt(d + "maxQueryLimit"),
      positiveInt(d + "maxQueryPages"),
      millis(d + "apiCallTimeoutMillis", 1),
      millis(d + "apiCallAttemptTimeoutMillis", 1),
      positiveInt(d + "batchGetMaxAttempts"),
      millis(d + "batchGetBackoffBaseMillis", 0),
      millis(d + "batchGetBackoffMaxMillis", 0)
    )
  }

  val serviceState: Vector[KeySpec] = {
    val s = ServiceStatePrefix
    Vector(
      KeySpec(s + "writer", OptionalString, KeyBound.Token(MaxTokenLength)),
      ttl(s + "querySessionTtlSeconds"),
      ttl(s + "ownershipTtlSeconds"),
      ttl(s + "jobTtlSeconds"),
      ttl(s + "completedTtlSeconds"),
      ttl(s + "inFlightTtlSeconds"),
      KeySpec(s + "maxPayloadBytes", OptionalInt32, KeyBound.Range(1, MaxPayloadBytes.toLong)),
      KeySpec(
        s + "maxConflictRetries",
        OptionalInt32,
        KeyBound.Range(1, MaxConflictRetries.toLong)
      ),
      positiveInt(s + "maxOwnedByQueryIds"),
      millis(s + "maxClockSkewMillis", 0)
    )
  }

  /** The key specs of one service process, in [[ServiceKindConfig]] field order. */
  def service(kind: ServiceKindKey): Vector[KeySpec] = {
    val p = kind.prefix
    val ints = (n: String) => positiveInt(p + n)
    val ms = (n: String) => millis(p + n, 1)
    Vector(
      ints("maxBodyBytes"),
      ints("maxQuestionBytes"),
      ms("syncTimeoutMillis"),
      ms("asyncTimeoutMillis"),
      ms("readTimeoutMillis"),
      ints("readMaxAttempts"),
      ints("breakerFailureThreshold"),
      ms("breakerOpenMillis"),
      ms("readRequestTimeoutMillis"),
      ms("queueWaitMillis"),
      ints("workflowWorkers"),
      ints("workflowQueueCapacity"),
      ints("keyValueWorkers"),
      ints("keyValueQueueCapacity"),
      ints("graphWorkers"),
      ints("graphQueueCapacity"),
      ints("jobWorkers"),
      ints("jobQueueCapacity"),
      ints("maxJobs"),
      ints("idempotencyCapacity"),
      ms("idempotencyTtlMillis"),
      ints("queryOwnershipCapacity"),
      KeySpec(p + "graphMaxDepth", OptionalInt32, KeyBound.Range(1, MaxSupportedDepth.toLong)),
      ints("graphMaxPageSize"),
      ints("graphDefaultPageSize"),
      ints("graphMaxResults"),
      KeySpec(p + "graphEdgeTypes", OptionalTokenList, KeyBound.Subset(SupportedEdgeTypes)),
      KeySpec(p + "graphDefaultWindow", OptionalWindow, KeyBound.Unbounded),
      ints("evidenceMaxContentBytes"),
      ints("evidenceMaxBundles"),
      ints("evidenceScanLimit"),
      ints("explainMaxSteps"),
      KeySpec(p + "metricsEnabled", OptionalBoolean, KeyBound.Unbounded),
      ints("retryAfterSeconds"),
      KeySpec(p + "server.host", OptionalString, KeyBound.Host),
      KeySpec(p + "server.port", OptionalInt32, KeyBound.Range(0, MaxPort.toLong)),
      ints("server.backlog"),
      ints("server.workers"),
      ints("server.queueCapacity"),
      KeySpec(
        p + "server.maxBodyBytes",
        OptionalInt32,
        KeyBound.Range(1, MaxServerBodyBytes.toLong)
      ),
      ints("server.overflowCapacity"),
      KeySpec(p + "graceSeconds", OptionalInt32, KeyBound.Range(0, MaxTtlSeconds)),
      millis(p + "drainMillis", 0)
    )
  }

  val services: Vector[KeySpec] = ServiceKindKey.values.toVector.flatMap(service)

  /** Every DE-INT-01 production key, in table order (the frozen DE-INT-01 table). */
  val all: Vector[KeySpec] = dynamoDb ++ serviceState ++ services

  /**
   * Every production key: the DE-INT-01 table, then the DE-INT-04 adapter table
   * ([[AdapterKeys.all]]). The loader and the validation use this table.
   */
  val allKeys: Vector[KeySpec] =
    all ++ AdapterKeys.all ++ ProductionWorkflowKeys.all ++ ProductionLoadKeys.all

  val byPath: Map[String, KeySpec] = allKeys.map(s => s.path -> s).toMap
}

/** Saturating arithmetic for the timing rules: a sum or product never wraps. */
object SatMath {
  def add(a: Long, b: Long): Long = {
    val r = a + b
    if (((a ^ r) & (b ^ r)) < 0) (if (a < 0) Long.MinValue else Long.MaxValue) else r
  }

  def sum(xs: Long*): Long = xs.foldLeft(0L)(add)

  def mul(a: Long, b: Long): Long =
    try Math.multiplyExact(a, b)
    catch {
      case _: ArithmeticException => if ((a < 0) == (b < 0)) Long.MaxValue else Long.MinValue
    }

  /** Ceiling of `a / b` for `a >= 0`, `b >= 1`. */
  def ceilDiv(a: Long, b: Long): Long =
    Math.floorDiv(a, b) + (if (Math.floorMod(a, b) > 0) 1 else 0)
}

/**
 * Derived timing budgets (CR-INT-02-1, -2, -3). Every budget is computed from configured values
 * with saturating arithmetic; none is a fixed constant. The ASSUMPTIONS that make each budget an
 * upper bound are service-wiring prerequisites (see the DE-INT-01 report, REMAINING WIRING
 * OBLIGATIONS):
 *
 *   - A1: services run idempotency `begin`, `complete` and `abandon`, every durable job-store write
 *     and every ownership call through the key-value read policy, capped in real time by
 *     `readRequestTimeoutMillis` (as the ownership record already is, QueryService.scala:567),
 *     reusing one attempt ID per logical request (SE-INT-02). `complete` and `abandon` are in A1
 *     because the in-flight record must stay live until the request's last write to it ends
 *     (CR-INT-04-2);
 *   - A1-W (DE-INT-03, CR-INT-05-1), the WIRING OBLIGATION of service-engineer: every COMPLETE
 *     store operation (ownership `record`; idempotency `begin`, `complete`, `abandon`; job
 *     `create`, `start`, `succeed`, `fail`, `remove`) runs as ONE `op` of the key-value
 *     `ReadPolicy.read(deadline(), readRequestTimeoutMillis)` (Resilience.scala:312-328), exactly
 *     as the ownership record does (QueryService.scala:567-569). That policy bounds what core
 *     validates, and only that: each attempt runs under `min(readTimeoutMillis, time left)`
 *     (Resilience.scala:345-355), at most `readMaxAttempts` attempts start, each only while time is
 *     left (:353), and retries are immediate, so the whole operation returns to its caller within
 *     `readRequestTimeoutMillis` (the cap, :315-317, and the request deadline,
 *     ServiceRuntime.scala:100-101). The policy enforces NO relation `readMaxAttempts *
 *     readTimeoutMillis <= readRequestTimeoutMillis`; it truncates the last attempt instead, and
 *     SVC-4 requires only `readRequestTimeoutMillis >= readTimeoutMillis`. The per-ATTEMPT store
 *     bound (N4: a timed-out attempt's SDK work ends by its attempt timeout) is validated by
 *     X-4[query-jobs] for the ownership record only (3 SDK calls). The other operations are
 *     composite too and their per-attempt bound is NOT validated (open obligation; counts from the
 *     storage code, R = `maxConflictRetries`): `begin` up to 4·(R+1) calls
 *     (ServiceStateStores.scala:619-646: PutItem, GetItem fallback, GetItem, conditional PutItem
 *     per round), job `start`/`succeed`/`fail` up to 2·(R+1) (:444-478; `putIfVersion` has no
 *     fallback, DynamoDbKeyValueStore.scala:389-399), `complete` 3 (:697, :700, :704), job `create`
 *     3 (:375-388, as `record`), `abandon` and job `remove` 2 (DeleteItem and its GetItem fallback,
 *     DynamoDbKeyValueStore.scala:413-433). Residual even under A1-W: an attempt the deadline
 *     truncates (budget below `readTimeoutMillis`) can leave its SDK work running past the caller's
 *     return, by at most that attempt's worst case (for `record`, at most
 *     `3 * apiCallTimeoutMillis <= readTimeoutMillis`);
 *   - A2: storage computes an in-flight record's `expiresAt` at or after the start of the request's
 *     first `begin` attempt;
 *   - A3: a workflow call honours interruption (residual m3 otherwise: an interrupt-ignoring
 *     workflow can outlive every bound);
 *   - A4: replica clocks and the DynamoDB service clock (TTL deletion) agree within
 *     `maxClockSkewMillis` (CR-INT-02-2).
 */
object TimingBudgets {
  import SatMath.*

  /**
   * CR-INT-02-1, -2, CR-INT-04-2: the longest an in-flight idempotency record must stay live after
   * its first applied write, in writer time: the begin retry budget (`readRequestTimeoutMillis`,
   * A1), the workflow pool wait (`queueWaitMillis`), the longest workflow timeout, the bounded
   * duration of the closing write (`complete`, or `abandon` instead, run under the same capped
   * key-value policy: `readRequestTimeoutMillis` again, A1), the drain time, and the skew allowance
   * (A4: TTL deletion by a DynamoDB clock up to that much ahead).
   */
  def inFlightRequirementMillis(
      readRequestTimeoutMillis: Long,
      queueWaitMillis: Long,
      syncTimeoutMillis: Long,
      asyncTimeoutMillis: Long,
      drainMillis: Long,
      maxClockSkewMillis: Long
  ): Long =
    sum(
      readRequestTimeoutMillis, // begin
      queueWaitMillis,
      math.max(syncTimeoutMillis, asyncTimeoutMillis),
      readRequestTimeoutMillis, // complete or abandon (CR-INT-04-2)
      drainMillis,
      maxClockSkewMillis
    )

  /**
   * How long one job holds a job-runner thread: `jobs.start` write, job-workflow pool wait, the
   * async workflow timeout, and the finishing write (QueryService.scala:683-695; A1, A3).
   */
  def jobHoldMillis(
      readRequestTimeoutMillis: Long,
      queueWaitMillis: Long,
      asyncTimeoutMillis: Long
  ): Long =
    sum(readRequestTimeoutMillis, queueWaitMillis, asyncTimeoutMillis, readRequestTimeoutMillis)

  /**
   * The longest wait of a queued job for a runner: `ceil(jobQueueCapacity / jobWorkers)` jobs ahead
   * per runner, each holding it at most [[jobHoldMillis]].
   */
  def jobQueueWaitMillis(jobQueueCapacity: Long, jobWorkers: Long, holdMillis: Long): Long =
    mul(ceilDiv(jobQueueCapacity, math.max(1L, jobWorkers)), holdMillis)

  /**
   * CR-INT-02-3: the longest gap between two writes of one job row: created -> started (queue wait
   * plus the start write), or started -> finished (pool wait, async timeout, finishing write).
   */
  def jobGapMillis(
      readRequestTimeoutMillis: Long,
      queueWaitMillis: Long,
      asyncTimeoutMillis: Long,
      jobQueueCapacity: Long,
      jobWorkers: Long
  ): Long = {
    val hold = jobHoldMillis(readRequestTimeoutMillis, queueWaitMillis, asyncTimeoutMillis)
    math.max(
      add(jobQueueWaitMillis(jobQueueCapacity, jobWorkers, hold), readRequestTimeoutMillis),
      sum(queueWaitMillis, asyncTimeoutMillis, readRequestTimeoutMillis)
    )
  }

  /**
   * `jobTtlSeconds * 1000` must exceed this: the larger of the CR-INT-02-3 margin (job gap, drain,
   * skew, second flooring) and storage's own constructor bound, the longest workflow timeout
   * `max(sync, async)` (`ServiceStateSettings.of`, ServiceStateStores.scala:138-140; CR-INT-04-1).
   * Both are strict, so exceeding the larger exceeds both.
   */
  def jobTtlRequirementMillis(
      jobGapMillis: Long,
      drainMillis: Long,
      maxClockSkewMillis: Long,
      longestWorkflowTimeoutMillis: Long
  ): Long =
    math.max(
      sum(jobGapMillis, drainMillis, maxClockSkewMillis, ProductionBounds.SecondMillis),
      longestWorkflowTimeoutMillis
    )

  /**
   * CR-INT-02-3: `ownershipTtlSeconds * 1000` must be at least this. The owner row is written at
   * workflow admission, before the QuerySession rows (at most one workflow timeout later), so it
   * must outlive them by that time, the skew allowance (an EvidenceExplain replica ahead of the
   * writer) and one second of flooring.
   */
  def ownershipTtlRequirementMillis(
      querySessionTtlSeconds: Long,
      syncTimeoutMillis: Long,
      asyncTimeoutMillis: Long,
      maxClockSkewMillis: Long
  ): Long =
    sum(
      mul(querySessionTtlSeconds, 1000L),
      math.max(syncTimeoutMillis, asyncTimeoutMillis),
      maxClockSkewMillis,
      ProductionBounds.SecondMillis
    )

  /**
   * `completedTtlSeconds * 1000` must be at least this: the services replay window
   * (`idempotencyTtlMillis`), plus the skew allowance (TTL deletion, A4) and one second of flooring
   * (CR-INT-02-3, related point).
   */
  def completedTtlRequirementMillis(idempotencyTtlMillis: Long, maxClockSkewMillis: Long): Long =
    sum(idempotencyTtlMillis, maxClockSkewMillis, ProductionBounds.SecondMillis)

  /**
   * The sleep before BatchGetItem retry `retry` (1-based), exactly as storage
   * `DynamoDbKeyValueStore.backoff` (DynamoDbKeyValueStore.scala:239-246) computes it: factor
   * `2^(retry-1)` while `retry - 1 < 30`, else `Long.MaxValue`; raw `0` when `base == 0`, else
   * `Long.MaxValue` when `factor > Long.MaxValue / base`, else `base * factor`; sleep
   * `min(raw, max)`. CR-INT-04-4(iii). (`base < 0` cannot pass the key bound; it is treated as 0.)
   */
  def batchGetBackoffMillis(retry: Long, backoffBaseMillis: Long, backoffMaxMillis: Long): Long = {
    val factor =
      if (retry - 1 >= ProductionBounds.BackoffDoublingRetries) Long.MaxValue
      else 1L << math.max(0L, retry - 1).toInt
    val raw =
      if (backoffBaseMillis <= 0) 0L
      else if (factor > Long.MaxValue / backoffBaseMillis) Long.MaxValue
      else backoffBaseMillis * factor
    math.min(raw, backoffMaxMillis)
  }

  /**
   * The worst case of one BatchGetItem chunk (storage `DynamoDbKeyValueStore.chunkRead`,
   * DynamoDbKeyValueStore.scala:248-317): at most `attempts` SDK calls of at most
   * `apiCallTimeoutMillis` each (:270, :296), plus [[batchGetBackoffMillis]] before retries
   * 1..attempts-1 (:305). Retries 1..30 are summed one by one; every later retry sleeps the same
   * `batchGetBackoffMillis(31, ...)`, multiplied once. Saturating throughout.
   */
  def batchGetChunkBudgetMillis(
      attempts: Long,
      apiCallTimeoutMillis: Long,
      backoffBaseMillis: Long,
      backoffMaxMillis: Long
  ): Long = {
    val retries = math.max(0L, attempts - 1)
    val doubling = math.min(retries, ProductionBounds.BackoffDoublingRetries.toLong)
    var sleeps = 0L
    var r = 1L
    while (r <= doubling) {
      sleeps = add(sleeps, batchGetBackoffMillis(r, backoffBaseMillis, backoffMaxMillis))
      r += 1
    }
    val capped = batchGetBackoffMillis(
      ProductionBounds.BackoffDoublingRetries + 1L,
      backoffBaseMillis,
      backoffMaxMillis
    )
    val tail = mul(retries - doubling, capped)
    sum(mul(attempts, apiCallTimeoutMillis), sleeps, tail)
  }

  /**
   * CR-INT-04-4(i): the worst case of one `ownedBy` call of evidence, which reads up to
   * `evidenceScanLimit` distinct query IDs (ReadServices.scala:128-129) in ONE read-policy attempt;
   * the adapter splits them into `ceil(evidenceScanLimit / 100)` BatchGetItem chunks
   * ([[ProductionBounds.BatchGetKeysPerCall]]), read one after another
   * (DynamoDbKeyValueStore.scala: 226-230).
   */
  def ownedByBudgetMillis(evidenceScanLimit: Long, chunkBudgetMillis: Long): Long =
    mul(
      ceilDiv(math.max(0L, evidenceScanLimit), ProductionBounds.BatchGetKeysPerCall.toLong),
      chunkBudgetMillis
    )

  /**
   * CR-INT-04-4(ii): the worst case of one key-value Query attempt: up to `maxQueryPages` SDK Query
   * calls of at most `apiCallTimeoutMillis` each (DynamoDbKeyValueStore.scala:171-216, the page
   * loop and its bound at :206).
   */
  def queryAttemptBudgetMillis(maxQueryPages: Long, apiCallTimeoutMillis: Long): Long =
    mul(maxQueryPages, apiCallTimeoutMillis)

  /**
   * DE-INT-03 (CR-INT-05-1): the worst case of one attempt of the ownership record:
   * [[ProductionBounds.OwnershipRecordSdkCalls]] (3) SDK calls of at most `apiCallTimeoutMillis`
   * each, saturating. No sleep or other term is added: the record path has none.
   */
  def ownershipRecordAttemptBudgetMillis(apiCallTimeoutMillis: Long): Long =
    mul(ProductionBounds.OwnershipRecordSdkCalls.toLong, apiCallTimeoutMillis)
}

/**
 * One cross-field rule, as data: `id`, the keys it reads, its formula (keys and constants only),
 * its source, and its check over the flattened values ([[ProductionConfig.flatten]]). It applies
 * when `applies` holds (by default: every key is set).
 */
final case class ConfigRule(
    id: String,
    keys: Vector[String],
    formula: String,
    source: String,
    check: Map[String, Any] => Boolean,
    applies: Option[Map[String, Any] => Boolean] = None
) {
  def appliesTo(m: Map[String, Any]): Boolean = applies.fold(keys.forall(m.contains))(_(m))

  /** The reason reported on `keys.head`: names the rule and its formula, never a value. */
  def reason: String = s"$id: must satisfy $formula ($source)"
}

/** The frozen cross-field rule table (DE-INT-01). */
object ProductionRules {
  import ProductionKeys.{DynamoDbPrefix => D, ServiceStatePrefix => S}
  import TimingBudgets.*

  private def l(m: Map[String, Any], k: String): Long = m(k) match {
    case i: Int => i.toLong
    case x: Long => x
    case _ => 0L
  }

  private val Q = ServiceKindKey.QueryJobs.prefix
  private val E = ServiceKindKey.EvidenceExplain.prefix

  /** The rules of services `ServiceConfig.problems` (ServiceConfig.scala:159-205), per process. */
  def service(kind: ServiceKindKey): Vector[ConfigRule] = {
    val p = kind.prefix
    val t = kind.tag
    val src = "services ServiceConfig.problems"
    Vector(
      ConfigRule(
        s"SVC-1[$t]",
        Vector(p + "graphDefaultPageSize", p + "graphMaxPageSize"),
        s"${p}graphDefaultPageSize <= ${p}graphMaxPageSize",
        s"$src (ServiceConfig.scala:163)",
        m => l(m, p + "graphDefaultPageSize") <= l(m, p + "graphMaxPageSize")
      ),
      ConfigRule(
        s"SVC-2[$t]",
        Vector(p + "graphMaxPageSize", p + "graphMaxResults"),
        s"${p}graphMaxPageSize < ${p}graphMaxResults",
        s"$src (ServiceConfig.scala:166)",
        m => l(m, p + "graphMaxPageSize") < l(m, p + "graphMaxResults")
      ),
      ConfigRule(
        s"SVC-3[$t]",
        Vector(p + "evidenceScanLimit", p + "evidenceMaxBundles"),
        s"${p}evidenceScanLimit > ${p}evidenceMaxBundles",
        s"$src (ServiceConfig.scala:182); phase-10 outstanding",
        m => l(m, p + "evidenceScanLimit") > l(m, p + "evidenceMaxBundles")
      ),
      ConfigRule(
        s"SVC-4[$t]",
        Vector(p + "readRequestTimeoutMillis", p + "readTimeoutMillis"),
        s"${p}readRequestTimeoutMillis >= ${p}readTimeoutMillis",
        s"$src (ServiceConfig.scala:186); phase-10 outstanding",
        m => l(m, p + "readRequestTimeoutMillis") >= l(m, p + "readTimeoutMillis")
      ),
      ConfigRule(
        s"SVC-5[$t]",
        Vector(p + "readRequestTimeoutMillis", p + "syncTimeoutMillis", p + "asyncTimeoutMillis"),
        s"${p}readRequestTimeoutMillis < ${p}syncTimeoutMillis and < ${p}asyncTimeoutMillis",
        s"$src (ServiceConfig.scala:194); phase-10 outstanding",
        m =>
          l(m, p + "readRequestTimeoutMillis") <
            math.min(l(m, p + "syncTimeoutMillis"), l(m, p + "asyncTimeoutMillis"))
      ),
      ConfigRule(
        s"SVC-6[$t]",
        Vector(p + "idempotencyTtlMillis", p + "syncTimeoutMillis", p + "asyncTimeoutMillis"),
        s"${p}idempotencyTtlMillis > max(${p}syncTimeoutMillis, ${p}asyncTimeoutMillis)",
        s"$src (ServiceConfig.scala:200); phase-10 outstanding",
        m =>
          l(m, p + "idempotencyTtlMillis") >
            math.max(l(m, p + "syncTimeoutMillis"), l(m, p + "asyncTimeoutMillis"))
      )
    )
  }

  /**
   * CR-INT-04-6(b): the server's body reader limit must not be below the application limit, or a
   * body between the two is cut by the reader (HttpServerAdapter.scala:110) and then answered 413
   * with the application limit in the message (Router.scala:271-272), which would be false.
   */
  def server(kind: ServiceKindKey): Vector[ConfigRule] = {
    val p = kind.prefix
    Vector(
      ConfigRule(
        s"SRV-1[${kind.tag}]",
        Vector(p + "server.maxBodyBytes", p + "maxBodyBytes"),
        s"${p}server.maxBodyBytes >= ${p}maxBodyBytes",
        "CR-INT-04-6 (HttpServerAdapter.scala:73-86,110; Router.scala:271-272)",
        m => l(m, p + "server.maxBodyBytes") >= l(m, p + "maxBodyBytes")
      )
    )
  }

  val dynamoDb: Vector[ConfigRule] = Vector(
    ConfigRule(
      "DDB-1",
      Vector(D + "apiCallAttemptTimeoutMillis", D + "apiCallTimeoutMillis"),
      s"${D}apiCallAttemptTimeoutMillis < ${D}apiCallTimeoutMillis",
      "storage DynamoDbClientSettings.of (DynamoDbSettings.scala:65); SE-INT-01 routing",
      m => l(m, D + "apiCallAttemptTimeoutMillis") < l(m, D + "apiCallTimeoutMillis")
    ),
    ConfigRule(
      "DDB-2",
      Vector(D + "batchGetBackoffMaxMillis", D + "batchGetBackoffBaseMillis"),
      s"${D}batchGetBackoffMaxMillis >= ${D}batchGetBackoffBaseMillis",
      "storage DynamoDbAdapterSettings.of (DynamoDbSettings.scala:139); SE-INT-01 routing",
      m => l(m, D + "batchGetBackoffMaxMillis") >= l(m, D + "batchGetBackoffBaseMillis")
    ),
    ConfigRule(
      "DDB-3",
      ProductionKeys.TableKeys,
      s"every set ${ProductionKeys.TablesPrefix}<role> name is distinct",
      "storage DynamoDbTableNames.of (DynamoDbSettings.scala:178); SE-INT-01 routing",
      m => {
        val names = ProductionKeys.TableKeys.flatMap(m.get)
        names.distinct.size == names.size
      },
      applies = Some(m => ProductionKeys.TableKeys.count(m.contains) >= 2)
    )
  )

  /** Rules between storage.dynamodb, storage.serviceState and the services processes. */
  val cross: Vector[ConfigRule] = Vector(
    ConfigRule(
      "X-1",
      Vector(E + "evidenceScanLimit", D + "maxQueryLimit"),
      s"${E}evidenceScanLimit <= ${D}maxQueryLimit",
      "CR-P10-05 U-c (ReadServices.scala:112 queries with this limit); SE-INT-01 routing",
      m => l(m, E + "evidenceScanLimit") <= l(m, D + "maxQueryLimit")
    ),
    ConfigRule(
      "X-2",
      Vector(E + "explainMaxSteps", D + "maxQueryLimit"),
      s"${E}explainMaxSteps <= ${D}maxQueryLimit",
      "explain queries the plan steps with this limit (ReadServices.scala:527)",
      m => l(m, E + "explainMaxSteps") <= l(m, D + "maxQueryLimit")
    ),
    ConfigRule(
      "X-3",
      Vector(S + "maxOwnedByQueryIds", E + "evidenceScanLimit"),
      s"${S}maxOwnedByQueryIds >= ${E}evidenceScanLimit",
      "evidence calls ownedBy with the distinct query IDs of the scan (ReadServices.scala:129); " +
        "SE-INT-01 routing",
      m => l(m, S + "maxOwnedByQueryIds") >= l(m, E + "evidenceScanLimit")
    )
  ) ++ Vector(
    // X-4: a timed-out services attempt must not keep a key-value thread (N4), so the longest
    // key-value operation one attempt (Resilience.scala:345-355, `readTimeoutMillis`) runs must end
    // within it. query-jobs runs the ownership record under its key-value policy today
    // (QueryService.scala:567-569; ServiceRuntime.scala:118-143). DE-INT-03 (CR-INT-05-1): one
    // record attempt is not one SDK call but up to 3 (ProductionBounds.OwnershipRecordSdkCalls:
    // PutItem, the GetItem fallback without ALL_OLD, the conflict-recovery GetItem), so the bound
    // is 3 * apiCallTimeoutMillis. Under A1-W the idempotency and job-store operations run under
    // the same policy; their larger per-attempt counts are an open obligation, not validated here
    // (see TimingBudgets, A1-W). evidence-explain is the only process whose read policy runs
    // key-value Queries (evidence ReadServices.scala:109-114, explain plan steps :524-529); one
    // Query attempt is up to `maxQueryPages` SDK calls (CR-INT-04-4(ii)). graph's read policy
    // governs graph reads only (no key-value).
    ConfigRule(
      "X-4[query-jobs]",
      Vector(D + "apiCallTimeoutMillis", Q + "readTimeoutMillis"),
      s"TimingBudgets.ownershipRecordAttemptBudgetMillis = " +
        s"${ProductionBounds.OwnershipRecordSdkCalls} * ${D}apiCallTimeoutMillis <= " +
        s"${Q}readTimeoutMillis",
      "CR-INT-05-1: one QueryOwnership.record attempt (ServiceStateStores.scala:265-288) makes up " +
        "to 3 SDK calls, the conditional PutItem and the GetItem fallback of putIfAbsent " +
        "(DynamoDbKeyValueStore.scala:331-348) and the conflict-recovery GetItem " +
        "(ServiceStateStores.scala:278-279), each bounded by apiCallTimeoutMillis with the SDK's " +
        "own retries (DynamoDbSettings.scala:94); all must end within the services attempt that " +
        "runs them (Resilience.scala:345-355), so a timed-out attempt does not keep a key-value " +
        "thread (N4)",
      m =>
        ownershipRecordAttemptBudgetMillis(l(m, D + "apiCallTimeoutMillis")) <=
          l(m, Q + "readTimeoutMillis")
    ),
    ConfigRule(
      "X-4[evidence-explain]",
      Vector(D + "apiCallTimeoutMillis", D + "maxQueryPages", E + "readTimeoutMillis"),
      s"TimingBudgets.queryAttemptBudgetMillis = ${D}maxQueryPages * ${D}apiCallTimeoutMillis " +
        s"<= ${E}readTimeoutMillis",
      "CR-INT-04-4(ii): one key-value Query attempt reads up to maxQueryPages pages, one SDK call " +
        "each (DynamoDbKeyValueStore.scala:171-216), within one evidence or explain read " +
        "attempt (Resilience.scala:345-355; N4)",
      m =>
        queryAttemptBudgetMillis(l(m, D + "maxQueryPages"), l(m, D + "apiCallTimeoutMillis")) <=
          l(m, E + "readTimeoutMillis")
    ),
    ConfigRule(
      "X-5",
      Vector(
        D + "batchGetMaxAttempts",
        D + "apiCallTimeoutMillis",
        D + "batchGetBackoffBaseMillis",
        D + "batchGetBackoffMaxMillis",
        E + "evidenceScanLimit",
        E + "readTimeoutMillis"
      ),
      s"TimingBudgets.ownedByBudgetMillis = ceil(${E}evidenceScanLimit / " +
        s"${ProductionBounds.BatchGetKeysPerCall}) * TimingBudgets.batchGetChunkBudgetMillis(" +
        s"${D}batchGetMaxAttempts, ${D}apiCallTimeoutMillis, ${D}batchGetBackoffBaseMillis, " +
        s"${D}batchGetBackoffMaxMillis) <= ${E}readTimeoutMillis, where the chunk budget is " +
        "attempts * apiCall + the sum over retries r = 1..attempts-1 of min(base * 2^(r-1), max) " +
        "for r <= 30 and max for r >= 31 (every sleep 0 when base = 0)",
      "CR-INT-04-4(i), (iii): one ownedBy call (ReadServices.scala:128-129) is " +
        "ceil(evidenceScanLimit / 100) BatchGetItem chunks (KeyValueStore.scala:78; " +
        "DynamoDbKeyValueStore.scala:227) in one evidence read attempt; backoff as " +
        "DynamoDbKeyValueStore.scala:239-246",
      m =>
        ownedByBudgetMillis(
          l(m, E + "evidenceScanLimit"),
          batchGetChunkBudgetMillis(
            l(m, D + "batchGetMaxAttempts"),
            l(m, D + "apiCallTimeoutMillis"),
            l(m, D + "batchGetBackoffBaseMillis"),
            l(m, D + "batchGetBackoffMaxMillis")
          )
        ) <= l(m, E + "readTimeoutMillis")
    )
  )

  /** The durable-state timing rules (CR-INT-02-1, -2, -3), against the query-jobs process. */
  val serviceState: Vector[ConfigRule] = Vector(
    ConfigRule(
      "ST-1",
      Vector(S + "maxClockSkewMillis", S + "inFlightTtlSeconds"),
      s"${S}maxClockSkewMillis < ${S}inFlightTtlSeconds * 1000",
      "storage ServiceStateSettings.of (ServiceStateStores.scala:136); SE-INT-02 routing",
      m => l(m, S + "maxClockSkewMillis") < SatMath.mul(l(m, S + "inFlightTtlSeconds"), 1000L)
    ),
    ConfigRule(
      "ST-2",
      Vector(
        S + "inFlightTtlSeconds",
        Q + "readRequestTimeoutMillis",
        Q + "queueWaitMillis",
        Q + "syncTimeoutMillis",
        Q + "asyncTimeoutMillis",
        Q + "drainMillis",
        S + "maxClockSkewMillis"
      ),
      s"${S}inFlightTtlSeconds * 1000 > TimingBudgets.inFlightRequirementMillis = " +
        s"${Q}readRequestTimeoutMillis (begin) + ${Q}queueWaitMillis + max(${Q}syncTimeoutMillis, " +
        s"${Q}asyncTimeoutMillis) + ${Q}readRequestTimeoutMillis (complete or abandon) + " +
        s"${Q}drainMillis + ${S}maxClockSkewMillis",
      "CR-INT-02-1 (begin retry budget), CR-INT-02-2 (TTL-deletion clock), CR-INT-04-2 (the " +
        "closing write, QueryService.scala:451-455, ServiceStateStores.scala:683-704); storage " +
        "ServiceStateStores.scala:128",
      m =>
        SatMath.mul(l(m, S + "inFlightTtlSeconds"), 1000L) >
          inFlightRequirementMillis(
            l(m, Q + "readRequestTimeoutMillis"),
            l(m, Q + "queueWaitMillis"),
            l(m, Q + "syncTimeoutMillis"),
            l(m, Q + "asyncTimeoutMillis"),
            l(m, Q + "drainMillis"),
            l(m, S + "maxClockSkewMillis")
          )
    ),
    ConfigRule(
      "ST-3",
      Vector(
        S + "jobTtlSeconds",
        Q + "readRequestTimeoutMillis",
        Q + "queueWaitMillis",
        Q + "syncTimeoutMillis",
        Q + "asyncTimeoutMillis",
        Q + "jobQueueCapacity",
        Q + "jobWorkers",
        Q + "drainMillis",
        S + "maxClockSkewMillis"
      ),
      s"${S}jobTtlSeconds * 1000 > TimingBudgets.jobTtlRequirementMillis = max(jobGapMillis + " +
        s"${Q}drainMillis + ${S}maxClockSkewMillis + 1000, max(${Q}syncTimeoutMillis, " +
        s"${Q}asyncTimeoutMillis)), jobGapMillis = max(ceil(" +
        s"${Q}jobQueueCapacity / ${Q}jobWorkers) * (2 * ${Q}readRequestTimeoutMillis + " +
        s"${Q}queueWaitMillis + ${Q}asyncTimeoutMillis) + ${Q}readRequestTimeoutMillis, " +
        s"${Q}queueWaitMillis + ${Q}asyncTimeoutMillis + ${Q}readRequestTimeoutMillis)",
      "CR-INT-02-3 (job TTL margin); CR-INT-01 unverified concern (queue wait vs job TTL); " +
        "CR-INT-04-1 (storage ServiceStateSettings.of, ServiceStateStores.scala:138-140: " +
        "jobTtlSeconds * 1000 > longestWorkflowTimeoutMillis = max(sync, async))",
      m =>
        SatMath.mul(l(m, S + "jobTtlSeconds"), 1000L) >
          jobTtlRequirementMillis(
            jobGapMillis(
              l(m, Q + "readRequestTimeoutMillis"),
              l(m, Q + "queueWaitMillis"),
              l(m, Q + "asyncTimeoutMillis"),
              l(m, Q + "jobQueueCapacity"),
              l(m, Q + "jobWorkers")
            ),
            l(m, Q + "drainMillis"),
            l(m, S + "maxClockSkewMillis"),
            math.max(l(m, Q + "syncTimeoutMillis"), l(m, Q + "asyncTimeoutMillis"))
          )
    ),
    ConfigRule(
      "ST-4",
      Vector(
        S + "ownershipTtlSeconds",
        S + "querySessionTtlSeconds",
        Q + "syncTimeoutMillis",
        Q + "asyncTimeoutMillis",
        S + "maxClockSkewMillis"
      ),
      s"${S}ownershipTtlSeconds * 1000 >= TimingBudgets.ownershipTtlRequirementMillis = " +
        s"${S}querySessionTtlSeconds * 1000 + max(${Q}syncTimeoutMillis, ${Q}asyncTimeoutMillis) " +
        s"+ ${S}maxClockSkewMillis + 1000",
      "CR-INT-02-3 (ownership liveness); SE-INT-01 routing (at least the QuerySession TTL)",
      m =>
        SatMath.mul(l(m, S + "ownershipTtlSeconds"), 1000L) >=
          ownershipTtlRequirementMillis(
            l(m, S + "querySessionTtlSeconds"),
            l(m, Q + "syncTimeoutMillis"),
            l(m, Q + "asyncTimeoutMillis"),
            l(m, S + "maxClockSkewMillis")
          )
    ),
    ConfigRule(
      "ST-5",
      Vector(S + "completedTtlSeconds", Q + "idempotencyTtlMillis", S + "maxClockSkewMillis"),
      s"${S}completedTtlSeconds * 1000 >= TimingBudgets.completedTtlRequirementMillis = " +
        s"${Q}idempotencyTtlMillis + ${S}maxClockSkewMillis + 1000",
      "SE-INT-01 routing (at least idempotencyTtlMillis); CR-INT-02-3 (floor rounding of the " +
        "completed record)",
      m =>
        SatMath.mul(l(m, S + "completedTtlSeconds"), 1000L) >=
          completedTtlRequirementMillis(
            l(m, Q + "idempotencyTtlMillis"),
            l(m, S + "maxClockSkewMillis")
          )
    )
  )

  /** Every DE-INT-01 rule, in table order (the frozen DE-INT-01 table). */
  val all: Vector[ConfigRule] =
    ServiceKindKey.values.toVector.flatMap(service) ++ ServiceKindKey.values.toVector.flatMap(
      server
    ) ++ dynamoDb ++ cross ++ serviceState

  /** Every rule: the DE-INT-01 table, then the DE-INT-04 adapter table ([[AdapterRules.all]]). */
  val every: Vector[ConfigRule] =
    all ++ AdapterRules.all ++ ProductionWorkflowKeys.rules ++ ProductionLoadKeys.rules

  /**
   * (key, reason) of every rule that applies to `m` and fails. A rule that reads a key in `bad` (a
   * key that failed its own bound) is skipped, so one bad value is reported once. The adapter rules
   * that read non-production keys apply only when `m` carries them
   * ([[ProductionConfig.ruleInputs]]).
   */
  def violations(m: Map[String, Any], bad: Set[String] = Set.empty): Vector[(String, String)] =
    every
      .filter(r => r.appliesTo(m) && !r.keys.exists(bad.contains))
      .filterNot(_.check(m))
      .map(r => r.keys.head -> r.reason)
}

/** Building and flattening the production sections. */
object ProductionConfig {
  import ProductionKeys.*

  /** The production sections of `c` as (key path -> value), set keys only. */
  def flatten(c: ScholarChainConfig): Map[String, Any] =
    if (c == null) Map.empty
    else
      (Option(c.dynamoDb).toVector.flatMap(flat(DynamoDbPrefix, _)) ++
        Option(c.serviceState).toVector.flatMap(flat(ServiceStatePrefix, _)) ++
        Option(c.services).toVector.flatMap(s =>
          ServiceKindKey.values.toVector
            .flatMap(k => Option(s.of(k)).toVector.flatMap(flat(k.prefix, _)))
        ) ++
        // DE-INT-04 sections.
        Option(c.s3).toVector.flatMap(flat(AdapterKeys.S3Prefix, _)) ++
        Option(c.neptune).toVector.flatMap(flat(AdapterKeys.NeptunePrefix, _)) ++
        Option(c.s3Vectors).toVector.flatMap(flat(AdapterKeys.S3VectorsPrefix, _)) ++
        Option(c.services)
          .flatMap(s => Option(s.apiKeys))
          .toVector
          .flatMap(flat(AdapterKeys.ApiKeysPrefix, _)) ++
        Option(c.productionWorkflow).toVector.flatMap(flat(ProductionWorkflowKeys.Prefix, _)) ++
        Option(c.productionLoad).toVector.flatMap(flat(ProductionLoadKeys.Prefix, _))).toMap

  /**
   * DE-INT-04: the inputs of the rule table: [[flatten]] plus the non-production keys the adapter
   * rules read ([[AdapterRules.NonProductionInputs]]), each only when present. Bound checks use
   * [[flatten]] alone.
   */
  def ruleInputs(c: ScholarChainConfig): Map[String, Any] =
    if (c == null) Map.empty
    else {
      val aws = Option(c.aws)
      val storage = Option(c.storage)
      val extra: Vector[(String, Any)] =
        aws.flatMap(a => Option(a.region)).map(AdapterRules.AwsRegionKey -> _).toVector ++
          storage
            .flatMap(s => Option(s.vectorBucket))
            .map(AdapterRules.VectorBucketKey -> _)
            .toVector ++
          storage
            .flatMap(s => Option(s.neptuneEndpoint))
            .map(AdapterRules.LegacyNeptuneKey -> _)
            .toVector ++
          Option(c.runtime).map(r => AdapterRules.RetrievalTopKKey -> r.retrievalTopK).toVector
      flatten(c) ++ extra
    }

  private def flat(prefix: String, p: Product): Vector[(String, Any)] =
    p.productElementNames
      .zip(p.productIterator)
      .toVector
      .flatMap {
        case (n, Some(v)) if v != null => Vector(prefix + n -> v)
        case (_, Some(_)) | (_, None) | (_, null) => Vector.empty
        case (n, q: Product) => flat(prefix + n + ".", q)
        case _ => Vector.empty
      }

  /** Individual-bound problems of the set keys of `c` as (key, reason). */
  def boundProblems(m: Map[String, Any]): Vector[(String, String)] =
    m.toVector.sortBy(_._1).flatMap { case (k, v) =>
      byPath.get(k) match {
        case Some(spec) => spec.problem(v).map(k -> _)
        case None => Some(k -> "unknown production key")
      }
    }

  /** Every problem of the set production keys of `c` (bounds, then rules), empty when valid. */
  def problems(c: ScholarChainConfig): Vector[(String, String)] = {
    val bounds = boundProblems(flatten(c))
    bounds ++ ProductionRules.violations(ruleInputs(c), bounds.map(_._1).toSet)
  }

  // ------------------------------------------------------------------ construction (loader)

  /** The sections built from typed values by key (the loader's parsed production keys). */
  def build(v: Map[String, Any]): (DynamoDbConfig, ServiceStateConfig, ServicesConfig) = {
    def get[A](k: String): Option[A] = v.get(k).map(_.asInstanceOf[A])
    val d = DynamoDbPrefix
    val t = TablesPrefix
    val dynamo = DynamoDbConfig(
      region = get(d + "region"),
      endpointOverride = get(EndpointOverrideKey),
      tables = DynamoDbTablesConfig(
        runLedger = get(t + "runLedger"),
        artifactRegistry = get(t + "artifactRegistry"),
        evidenceBundle = get(t + "evidenceBundle"),
        querySession = get(t + "querySession"),
        entityLinkCache = get(t + "entityLinkCache"),
        researcherMetricCache = get(t + "researcherMetricCache"),
        queryOwnership = get(t + "queryOwnership"),
        queryJob = get(t + "queryJob"),
        idempotencyKey = get(t + "idempotencyKey")
      ),
      maxQueryLimit = get(d + "maxQueryLimit"),
      maxQueryPages = get(d + "maxQueryPages"),
      apiCallTimeoutMillis = get(d + "apiCallTimeoutMillis"),
      apiCallAttemptTimeoutMillis = get(d + "apiCallAttemptTimeoutMillis"),
      batchGetMaxAttempts = get(d + "batchGetMaxAttempts"),
      batchGetBackoffBaseMillis = get(d + "batchGetBackoffBaseMillis"),
      batchGetBackoffMaxMillis = get(d + "batchGetBackoffMaxMillis"),
      retryMaxAttempts = get(AdapterKeys.DynamoDbRetryKey)
    )
    val s = ServiceStatePrefix
    val state = ServiceStateConfig(
      writer = get(s + "writer"),
      querySessionTtlSeconds = get(s + "querySessionTtlSeconds"),
      ownershipTtlSeconds = get(s + "ownershipTtlSeconds"),
      jobTtlSeconds = get(s + "jobTtlSeconds"),
      completedTtlSeconds = get(s + "completedTtlSeconds"),
      inFlightTtlSeconds = get(s + "inFlightTtlSeconds"),
      maxPayloadBytes = get(s + "maxPayloadBytes"),
      maxConflictRetries = get(s + "maxConflictRetries"),
      maxOwnedByQueryIds = get(s + "maxOwnedByQueryIds"),
      maxClockSkewMillis = get(s + "maxClockSkewMillis")
    )
    def svc(k: ServiceKindKey): ServiceKindConfig = {
      val p = k.prefix
      ServiceKindConfig(
        maxBodyBytes = get(p + "maxBodyBytes"),
        maxQuestionBytes = get(p + "maxQuestionBytes"),
        syncTimeoutMillis = get(p + "syncTimeoutMillis"),
        asyncTimeoutMillis = get(p + "asyncTimeoutMillis"),
        readTimeoutMillis = get(p + "readTimeoutMillis"),
        readMaxAttempts = get(p + "readMaxAttempts"),
        breakerFailureThreshold = get(p + "breakerFailureThreshold"),
        breakerOpenMillis = get(p + "breakerOpenMillis"),
        readRequestTimeoutMillis = get(p + "readRequestTimeoutMillis"),
        queueWaitMillis = get(p + "queueWaitMillis"),
        workflowWorkers = get(p + "workflowWorkers"),
        workflowQueueCapacity = get(p + "workflowQueueCapacity"),
        keyValueWorkers = get(p + "keyValueWorkers"),
        keyValueQueueCapacity = get(p + "keyValueQueueCapacity"),
        graphWorkers = get(p + "graphWorkers"),
        graphQueueCapacity = get(p + "graphQueueCapacity"),
        jobWorkers = get(p + "jobWorkers"),
        jobQueueCapacity = get(p + "jobQueueCapacity"),
        maxJobs = get(p + "maxJobs"),
        idempotencyCapacity = get(p + "idempotencyCapacity"),
        idempotencyTtlMillis = get(p + "idempotencyTtlMillis"),
        queryOwnershipCapacity = get(p + "queryOwnershipCapacity"),
        graphMaxDepth = get(p + "graphMaxDepth"),
        graphMaxPageSize = get(p + "graphMaxPageSize"),
        graphDefaultPageSize = get(p + "graphDefaultPageSize"),
        graphMaxResults = get(p + "graphMaxResults"),
        graphEdgeTypes = get(p + "graphEdgeTypes"),
        graphDefaultWindow = get(p + "graphDefaultWindow"),
        evidenceMaxContentBytes = get(p + "evidenceMaxContentBytes"),
        evidenceMaxBundles = get(p + "evidenceMaxBundles"),
        evidenceScanLimit = get(p + "evidenceScanLimit"),
        explainMaxSteps = get(p + "explainMaxSteps"),
        metricsEnabled = get(p + "metricsEnabled"),
        retryAfterSeconds = get(p + "retryAfterSeconds"),
        server = ServerConfig(
          host = get(p + "server.host"),
          port = get(p + "server.port"),
          backlog = get(p + "server.backlog"),
          workers = get(p + "server.workers"),
          queueCapacity = get(p + "server.queueCapacity"),
          maxBodyBytes = get(p + "server.maxBodyBytes"),
          overflowCapacity = get(p + "server.overflowCapacity")
        ),
        graceSeconds = get(p + "graceSeconds"),
        drainMillis = get(p + "drainMillis")
      )
    }
    (
      dynamo,
      state,
      ServicesConfig(
        svc(ServiceKindKey.QueryJobs),
        svc(ServiceKindKey.EvidenceExplain),
        svc(ServiceKindKey.Graph),
        apiKeys = {
          val k = AdapterKeys.ApiKeysPrefix
          ApiKeySourceConfig(
            secretId = get(k + "secretId"),
            apiCallTimeoutMillis = get(k + "apiCallTimeoutMillis"),
            apiCallAttemptTimeoutMillis = get(k + "apiCallAttemptTimeoutMillis"),
            retryMaxAttempts = get(k + "retryMaxAttempts"),
            endpointOverride = get(k + "endpointOverride"),
            cacheTtlMillis = get(k + "cacheTtlMillis")
          )
        }
      )
    )
  }

  /** DE-INT-04: the `storage.s3`, `storage.neptune` and `storage.s3vectors` sections, by key. */
  def buildAdapters(v: Map[String, Any]): (S3Config, NeptuneConfig, S3VectorsConfig) = {
    def get[A](k: String): Option[A] = v.get(k).map(_.asInstanceOf[A])
    val s = AdapterKeys.S3Prefix
    val n = AdapterKeys.NeptunePrefix
    val x = AdapterKeys.S3VectorsPrefix
    (
      S3Config(
        apiCallTimeoutMillis = get(s + "apiCallTimeoutMillis"),
        apiCallAttemptTimeoutMillis = get(s + "apiCallAttemptTimeoutMillis"),
        retryMaxAttempts = get(s + "retryMaxAttempts"),
        endpointOverride = get(s + "endpointOverride"),
        maxObjectBytes = get(s + "maxObjectBytes"),
        maxListKeys = get(s + "maxListKeys")
      ),
      NeptuneConfig(
        endpoint = get(n + "endpoint"),
        apiCallTimeoutMillis = get(n + "apiCallTimeoutMillis"),
        apiCallAttemptTimeoutMillis = get(n + "apiCallAttemptTimeoutMillis"),
        retryMaxAttempts = get(n + "retryMaxAttempts"),
        maxRows = get(n + "maxRows")
      ),
      S3VectorsConfig(
        apiCallTimeoutMillis = get(x + "apiCallTimeoutMillis"),
        apiCallAttemptTimeoutMillis = get(x + "apiCallAttemptTimeoutMillis"),
        retryMaxAttempts = get(x + "retryMaxAttempts"),
        endpointOverride = get(x + "endpointOverride"),
        maxTopK = get(x + "maxTopK"),
        maxGetKeys = get(x + "maxGetKeys")
      )
    )
  }
}

/** Resolved `storage.dynamodb.*` (every key set except the optional endpoint override). */
final case class DynamoDbConnectionSettings(
    region: String,
    endpointOverride: Option[String],
    tableNames: SortedMap[String, String],
    maxQueryLimit: Int,
    maxQueryPages: Int,
    apiCallTimeoutMillis: Long,
    apiCallAttemptTimeoutMillis: Long,
    batchGetMaxAttempts: Int,
    batchGetBackoffBaseMillis: Long,
    batchGetBackoffMaxMillis: Long
)

/**
 * Resolved `storage.serviceState.*` with the inputs storage `ServiceStateSettings.of` takes from
 * services (`longestWorkflowTimeoutMillis`, `drainMillis`, from `services.query-jobs.*`) and the
 * derived requirements the rules checked.
 */
final case class DurableStateSettings(
    writer: String,
    querySessionTtlSeconds: Long,
    ownershipTtlSeconds: Long,
    jobTtlSeconds: Long,
    completedTtlSeconds: Long,
    inFlightTtlSeconds: Long,
    maxPayloadBytes: Int,
    maxConflictRetries: Int,
    maxOwnedByQueryIds: Int,
    maxClockSkewMillis: Long,
    longestWorkflowTimeoutMillis: Long,
    drainMillis: Long,
    inFlightRequirementMillis: Long,
    jobTtlRequirementMillis: Long,
    ownershipTtlRequirementMillis: Long,
    completedTtlRequirementMillis: Long
)

/** Resolved `services.<kind>.*`: services `ServiceConfig`, the server bind and shutdown timing. */
final case class ServiceProcessSettings(
    kind: ServiceKindKey,
    maxBodyBytes: Int,
    maxQuestionBytes: Int,
    syncTimeoutMillis: Long,
    asyncTimeoutMillis: Long,
    readTimeoutMillis: Long,
    readMaxAttempts: Int,
    breakerFailureThreshold: Int,
    breakerOpenMillis: Long,
    readRequestTimeoutMillis: Long,
    queueWaitMillis: Long,
    workflowWorkers: Int,
    workflowQueueCapacity: Int,
    keyValueWorkers: Int,
    keyValueQueueCapacity: Int,
    graphWorkers: Int,
    graphQueueCapacity: Int,
    jobWorkers: Int,
    jobQueueCapacity: Int,
    maxJobs: Int,
    idempotencyCapacity: Int,
    idempotencyTtlMillis: Long,
    queryOwnershipCapacity: Int,
    graphMaxDepth: Int,
    graphMaxPageSize: Int,
    graphDefaultPageSize: Int,
    graphMaxResults: Int,
    graphEdgeTypes: Vector[String],
    graphDefaultWindow: TimeWindow,
    evidenceMaxContentBytes: Int,
    evidenceMaxBundles: Int,
    evidenceScanLimit: Int,
    explainMaxSteps: Int,
    metricsEnabled: Boolean,
    retryAfterSeconds: Int,
    serverHost: String,
    serverPort: Int,
    serverBacklog: Int,
    serverWorkers: Int,
    serverQueueCapacity: Int,
    serverMaxBodyBytes: Int,
    serverOverflowCapacity: Int,
    graceSeconds: Int,
    drainMillis: Long
)

/** The settings one service process needs. */
final case class ProcessSettings(
    service: ServiceProcessSettings,
    dynamoDb: Option[DynamoDbConnectionSettings],
    durableState: Option[DurableStateSettings]
)

/**
 * Feature-level resolution: the keys a process needs are required here (MissingKey for each unset
 * one), then every individual bound and every applicable rule of the whole configuration is checked
 * (the same checks as the loader, so in-memory configurations get them too).
 *
 * Required sections: query-jobs needs `services.query-jobs`, `storage.dynamodb` (endpoint override
 * optional) and `storage.serviceState`; evidence-explain needs `services.evidence-explain`, the
 * same two storage sections and only the eight query-jobs timing inputs
 * [[ProductionSettings.QueryJobsStateInputs]] (they define the durable-state settings and rules
 * ST-2 to ST-5 that its ownership reads depend on; CR-INT-04-3); graph needs `services.graph` only.
 */
object ProductionSettings {
  import ProductionKeys.*

  private def missing(m: Map[String, Any], keys: Vector[String]): Vector[ConfigError] =
    keys.filterNot(m.contains).map(k => ConfigError.MissingKey(k, ConfigKeys.envNameOf(k)))

  private def invalid(c: ScholarChainConfig): Vector[ConfigError] =
    ProductionConfig.problems(c).map { case (k, why) =>
      ConfigError.InvalidValue(k, "resolved configuration", why)
    }

  /**
   * CR-INT-04-3: the `services.query-jobs.*` keys that the durable-state settings read (`stateOf`)
   * or that a durable-state rule (ST-2 to ST-5) reads, and no other. Each is a timing of the
   * query-jobs process, the only writer of the durable rows that evidence-explain reads:
   *
   *   - `syncTimeoutMillis`, `asyncTimeoutMillis`:
   *     `longestWorkflowTimeoutMillis = max(sync, async)` passed to storage
   *     `ServiceStateSettings.of` (ServiceStateStores.scala:107, :128, :139); ST-2, ST-3 (and its
   *     storage term), ST-4;
   *   - `readRequestTimeoutMillis`: the begin and closing-write budgets of ST-2, the job-store
   *     write budget of ST-3's job gap;
   *   - `queueWaitMillis`: the workflow pool wait of ST-2 and ST-3;
   *   - `drainMillis`: passed to `ServiceStateSettings.of` (:108, :128); ST-2, ST-3;
   *   - `jobQueueCapacity`, `jobWorkers`: the job queue wait of ST-3;
   *   - `idempotencyTtlMillis`: the replay window of ST-5.
   *
   * ST-1 reads storage keys only. These values MUST equal the deployed query-jobs process's values
   * (when the two processes load separate files, core cannot check that; a deployment obligation).
   */
  val QueryJobsStateInputs: Vector[String] = {
    val q = ServiceKindKey.QueryJobs.prefix
    Vector(
      "syncTimeoutMillis",
      "asyncTimeoutMillis",
      "readRequestTimeoutMillis",
      "queueWaitMillis",
      "drainMillis",
      "jobQueueCapacity",
      "jobWorkers",
      "idempotencyTtlMillis"
    ).map(q + _)
  }

  /** The keys `kind` requires (see the object doc). */
  def requiredKeys(kind: ServiceKindKey): Vector[String] = {
    val storage =
      dynamoDb.map(_.path).filterNot(_ == EndpointOverrideKey) ++ serviceState.map(_.path)
    kind match {
      case ServiceKindKey.QueryJobs => service(kind).map(_.path) ++ storage
      case ServiceKindKey.EvidenceExplain =>
        service(kind).map(_.path) ++ storage ++ QueryJobsStateInputs
      case ServiceKindKey.Graph => service(kind).map(_.path)
    }
  }

  private def resolve[A](c: ScholarChainConfig, keys: Vector[String])(
      f: Map[String, Any] => A
  ): Either[Vector[ConfigError], A] =
    if (c == null)
      Left(Vector(ConfigError.MissingKey("services", ConfigKeys.envNameOf("services"))))
    else {
      val m = ProductionConfig.flatten(c)
      val errors = missing(m, keys) ++ invalid(c)
      if (errors.nonEmpty) Left(errors) else Right(f(m))
    }

  private def i(m: Map[String, Any], k: String): Int = m(k).asInstanceOf[Int]
  private def l(m: Map[String, Any], k: String): Long = m(k).asInstanceOf[Long]
  private def s(m: Map[String, Any], k: String): String = m(k).asInstanceOf[String]

  def dynamoDbSettings(
      c: ScholarChainConfig
  ): Either[Vector[ConfigError], DynamoDbConnectionSettings] =
    resolve(c, ProductionKeys.dynamoDb.map(_.path).filterNot(_ == EndpointOverrideKey))(dynamoOf)

  private def dynamoOf(m: Map[String, Any]): DynamoDbConnectionSettings = {
    val d = DynamoDbPrefix
    DynamoDbConnectionSettings(
      region = s(m, d + "region"),
      endpointOverride = m.get(EndpointOverrideKey).map(_.asInstanceOf[String]),
      tableNames =
        SortedMap.from(TableRoles.map { case (leaf, tag) => tag -> s(m, TablesPrefix + leaf) }),
      maxQueryLimit = i(m, d + "maxQueryLimit"),
      maxQueryPages = i(m, d + "maxQueryPages"),
      apiCallTimeoutMillis = l(m, d + "apiCallTimeoutMillis"),
      apiCallAttemptTimeoutMillis = l(m, d + "apiCallAttemptTimeoutMillis"),
      batchGetMaxAttempts = i(m, d + "batchGetMaxAttempts"),
      batchGetBackoffBaseMillis = l(m, d + "batchGetBackoffBaseMillis"),
      batchGetBackoffMaxMillis = l(m, d + "batchGetBackoffMaxMillis")
    )
  }

  /**
   * Durable state needs `storage.serviceState` and the query-jobs timing inputs
   * ([[QueryJobsStateInputs]]) it is checked against.
   */
  def durableStateSettings(
      c: ScholarChainConfig
  ): Either[Vector[ConfigError], DurableStateSettings] =
    resolve(c, serviceState.map(_.path) ++ QueryJobsStateInputs)(stateOf)

  private def stateOf(m: Map[String, Any]): DurableStateSettings = {
    import TimingBudgets.*
    val st = ServiceStatePrefix
    val q = ServiceKindKey.QueryJobs.prefix
    val skew = l(m, st + "maxClockSkewMillis")
    val sync = l(m, q + "syncTimeoutMillis")
    val async = l(m, q + "asyncTimeoutMillis")
    val rr = l(m, q + "readRequestTimeoutMillis")
    val qw = l(m, q + "queueWaitMillis")
    val drain = l(m, q + "drainMillis")
    DurableStateSettings(
      writer = s(m, st + "writer"),
      querySessionTtlSeconds = l(m, st + "querySessionTtlSeconds"),
      ownershipTtlSeconds = l(m, st + "ownershipTtlSeconds"),
      jobTtlSeconds = l(m, st + "jobTtlSeconds"),
      completedTtlSeconds = l(m, st + "completedTtlSeconds"),
      inFlightTtlSeconds = l(m, st + "inFlightTtlSeconds"),
      maxPayloadBytes = i(m, st + "maxPayloadBytes"),
      maxConflictRetries = i(m, st + "maxConflictRetries"),
      maxOwnedByQueryIds = i(m, st + "maxOwnedByQueryIds"),
      maxClockSkewMillis = skew,
      longestWorkflowTimeoutMillis = math.max(sync, async),
      drainMillis = drain,
      inFlightRequirementMillis = inFlightRequirementMillis(rr, qw, sync, async, drain, skew),
      jobTtlRequirementMillis = jobTtlRequirementMillis(
        jobGapMillis(
          rr,
          qw,
          async,
          i(m, q + "jobQueueCapacity").toLong,
          i(m, q + "jobWorkers").toLong
        ),
        drain,
        skew,
        math.max(sync, async)
      ),
      ownershipTtlRequirementMillis =
        ownershipTtlRequirementMillis(l(m, st + "querySessionTtlSeconds"), sync, async, skew),
      completedTtlRequirementMillis =
        completedTtlRequirementMillis(l(m, q + "idempotencyTtlMillis"), skew)
    )
  }

  def serviceSettings(
      c: ScholarChainConfig,
      kind: ServiceKindKey
  ): Either[Vector[ConfigError], ServiceProcessSettings] =
    resolve(c, service(kind).map(_.path))(serviceOf(kind, _))

  private def serviceOf(kind: ServiceKindKey, m: Map[String, Any]): ServiceProcessSettings = {
    val p = kind.prefix
    def ii(n: String) = i(m, p + n)
    def ll(n: String) = l(m, p + n)
    ServiceProcessSettings(
      kind = kind,
      maxBodyBytes = ii("maxBodyBytes"),
      maxQuestionBytes = ii("maxQuestionBytes"),
      syncTimeoutMillis = ll("syncTimeoutMillis"),
      asyncTimeoutMillis = ll("asyncTimeoutMillis"),
      readTimeoutMillis = ll("readTimeoutMillis"),
      readMaxAttempts = ii("readMaxAttempts"),
      breakerFailureThreshold = ii("breakerFailureThreshold"),
      breakerOpenMillis = ll("breakerOpenMillis"),
      readRequestTimeoutMillis = ll("readRequestTimeoutMillis"),
      queueWaitMillis = ll("queueWaitMillis"),
      workflowWorkers = ii("workflowWorkers"),
      workflowQueueCapacity = ii("workflowQueueCapacity"),
      keyValueWorkers = ii("keyValueWorkers"),
      keyValueQueueCapacity = ii("keyValueQueueCapacity"),
      graphWorkers = ii("graphWorkers"),
      graphQueueCapacity = ii("graphQueueCapacity"),
      jobWorkers = ii("jobWorkers"),
      jobQueueCapacity = ii("jobQueueCapacity"),
      maxJobs = ii("maxJobs"),
      idempotencyCapacity = ii("idempotencyCapacity"),
      idempotencyTtlMillis = ll("idempotencyTtlMillis"),
      queryOwnershipCapacity = ii("queryOwnershipCapacity"),
      graphMaxDepth = ii("graphMaxDepth"),
      graphMaxPageSize = ii("graphMaxPageSize"),
      graphDefaultPageSize = ii("graphDefaultPageSize"),
      graphMaxResults = ii("graphMaxResults"),
      graphEdgeTypes = m(p + "graphEdgeTypes").asInstanceOf[Vector[String]],
      graphDefaultWindow = m(p + "graphDefaultWindow").asInstanceOf[TimeWindow],
      evidenceMaxContentBytes = ii("evidenceMaxContentBytes"),
      evidenceMaxBundles = ii("evidenceMaxBundles"),
      evidenceScanLimit = ii("evidenceScanLimit"),
      explainMaxSteps = ii("explainMaxSteps"),
      metricsEnabled = m(p + "metricsEnabled").asInstanceOf[Boolean],
      retryAfterSeconds = ii("retryAfterSeconds"),
      serverHost = s(m, p + "server.host"),
      serverPort = ii("server.port"),
      serverBacklog = ii("server.backlog"),
      serverWorkers = ii("server.workers"),
      serverQueueCapacity = ii("server.queueCapacity"),
      serverMaxBodyBytes = ii("server.maxBodyBytes"),
      serverOverflowCapacity = ii("server.overflowCapacity"),
      graceSeconds = ii("graceSeconds"),
      drainMillis = ll("drainMillis")
    )
  }

  /** Everything one process needs (see the object doc for the required sections). */
  def forProcess(
      c: ScholarChainConfig,
      kind: ServiceKindKey
  ): Either[Vector[ConfigError], ProcessSettings] =
    resolve(c, requiredKeys(kind)) { m =>
      val durable = kind != ServiceKindKey.Graph
      ProcessSettings(
        serviceOf(kind, m),
        Option.when(durable)(dynamoOf(m)),
        Option.when(durable)(stateOf(m))
      )
    }

  /**
   * DE-INT-04: the keys a service LAUNCHER requires: [[requiredKeys]] (unchanged) plus the adapter
   * and API-key source keys of the process ([[AdapterKeys.requiredFor]]).
   */
  def launcherRequiredKeys(kind: ServiceKindKey): Vector[String] =
    requiredKeys(kind) ++ AdapterKeys.requiredFor(kind)

  /**
   * DE-INT-04: everything a production launcher maps into storage `StorageClients` and the Secrets
   * Manager API-key source, with every bound and rule checked. [[forProcess]] keeps its DE-INT-01
   * contract (it does not require the adapter keys, so existing SV-INT-02 compositions keep
   * resolving); a launcher must use this method, which refuses a configuration [[forProcess]] would
   * refuse and, in addition, one without the process's adapter sections. The Region of every
   * adapter client is `aws.region` (rule ADP-1 applies the storage Region rule).
   */
  def forLauncher(
      c: ScholarChainConfig,
      kind: ServiceKindKey
  ): Either[Vector[ConfigError], LauncherSettings] =
    if (c != null && (c.aws == null || c.storage == null))
      Left(
        Vector(
          ConfigError.MissingKey(
            if (c.aws == null) "aws" else "storage",
            ConfigKeys.envNameOf(if (c.aws == null) "aws" else "storage")
          )
        )
      )
    else
      resolve(c, launcherRequiredKeys(kind)) { m =>
        import AdapterKeys.*
        val durable = kind != ServiceKindKey.Graph
        val region = c.aws.region
        def client(p: String): AdapterClientSettings =
          AdapterClientSettings(
            region = region,
            endpointOverride = m.get(p + "endpointOverride").map(_.asInstanceOf[String]),
            apiCallTimeoutMillis = l(m, p + "apiCallTimeoutMillis"),
            apiCallAttemptTimeoutMillis = l(m, p + "apiCallAttemptTimeoutMillis"),
            retryMaxAttempts = i(m, p + "retryMaxAttempts")
          )
        val queryJobs = kind == ServiceKindKey.QueryJobs
        LauncherSettings(
          process = ProcessSettings(
            serviceOf(kind, m),
            Option.when(durable)(dynamoOf(m)),
            Option.when(durable)(stateOf(m))
          ),
          dynamoDbRetryMaxAttempts = Option.when(durable)(i(m, DynamoDbRetryKey)),
          s3 = Option.when(queryJobs)(
            S3AdapterSettings(
              client(S3Prefix),
              Vector(c.storage.rawBucket, c.storage.curatedBucket, c.storage.contextBucket),
              i(m, S3Prefix + "maxObjectBytes"),
              i(m, S3Prefix + "maxListKeys")
            )
          ),
          neptune = Option.when(kind != ServiceKindKey.EvidenceExplain)(
            NeptuneAdapterSettings(
              region = region,
              endpoint = s(m, NeptunePrefix + "endpoint"),
              apiCallTimeoutMillis = l(m, NeptunePrefix + "apiCallTimeoutMillis"),
              apiCallAttemptTimeoutMillis = l(m, NeptunePrefix + "apiCallAttemptTimeoutMillis"),
              retryMaxAttempts = i(m, NeptunePrefix + "retryMaxAttempts"),
              maxRows = i(m, NeptunePrefix + "maxRows")
            )
          ),
          s3Vectors = Option.when(queryJobs)(
            S3VectorsAdapterSettings(
              client(S3VectorsPrefix),
              c.storage.vectorBucket,
              i(m, S3VectorsPrefix + "maxTopK"),
              i(m, S3VectorsPrefix + "maxGetKeys")
            )
          ),
          apiKeys = ApiKeySourceSettings(
            s(m, ApiKeysPrefix + "secretId"),
            client(ApiKeysPrefix),
            l(m, ApiKeysPrefix + "cacheTtlMillis")
          ),
          workflow = if (queryJobs) ProductionWorkflowKeys.settings(c, m) else None
        )
      }
}
