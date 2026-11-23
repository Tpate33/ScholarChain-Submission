package scholarchain.core.config

import java.net.URI

import scala.util.control.NonFatal

import upickle.default.ReadWriter

/*
 * DE-INT-04 (integration round 01): configuration contracts of the SE-INT-04/05 storage adapters
 * (S3, Neptune, S3 Vectors), the explicit DynamoDB retry setting, and the external contract of the
 * Secrets Manager-backed API-key digest source (SV-INT-03).
 *
 * Conventions (DE-INT-01): every key is OPTIONAL at load time (additive within
 * `scholarchain.config.v2`), excluded from every pipeline-stage digest
 * ([[ScholarChainConfig.withoutProduction]]) and omitted from the full digest when unset, so every
 * existing configuration loads unchanged and keeps its exact digest. A set key is checked against
 * its own bound when loaded; a rule is checked when every key it reads is set. The processes that
 * use a section require it through [[ProductionSettings.forLauncher]] (see
 * [[AdapterKeys.requiredFor]]). Error reasons name keys and bounds, never values. No value here is
 * a secret: the API-key secret is referenced by ARN or name only.
 *
 * Core depends on no other internal module, so every storage bound is RESTATED here with the source
 * file and line it mirrors (working tree of 2026-11-22, SE-INT-04/05 code under
 * modules/storage/src/main/scala/scholarchain/storage/aws/). A change there needs the same change
 * here.
 *
 * SDK timeout semantics (se-int-04-docs.md Q4, CONFIRMED): `apiCallTimeout` bounds ONE SDK call
 * including ALL its attempts and the waits between them; `apiCallAttemptTimeout` bounds a single
 * HTTP attempt; the abort of a timed-out call is not strictly guaranteed (residual A1-W/P3). Every
 * timing rule below therefore costs one SDK call at `apiCallTimeoutMillis`, whatever
 * `retryMaxAttempts` is, and never multiplies attempts by `apiCallTimeoutMillis`.
 */

/** `storage.s3.*`: storage `AwsClientSettings` and `S3ReaderSettings` (no defaults). */
final case class S3Config(
    apiCallTimeoutMillis: Option[Long] = None,
    apiCallAttemptTimeoutMillis: Option[Long] = None,
    retryMaxAttempts: Option[Int] = None,
    endpointOverride: Option[String] = None,
    maxObjectBytes: Option[Int] = None,
    maxListKeys: Option[Int] = None
) derives ReadWriter

/**
 * `storage.neptune.*`: storage `NeptuneClientSettings` and `NeptuneReaderSettings`. `endpoint` is
 * the typed data-API endpoint `https://<host>:<port>` (it replaces the free-text
 * `storage.neptuneEndpoint` for the adapter; see [[AdapterRules]] NEP-3 for the migration rule).
 */
final case class NeptuneConfig(
    endpoint: Option[String] = None,
    apiCallTimeoutMillis: Option[Long] = None,
    apiCallAttemptTimeoutMillis: Option[Long] = None,
    retryMaxAttempts: Option[Int] = None,
    maxRows: Option[Int] = None
) derives ReadWriter

/** `storage.s3vectors.*`: storage `AwsClientSettings` and `S3VectorsReaderSettings`. */
final case class S3VectorsConfig(
    apiCallTimeoutMillis: Option[Long] = None,
    apiCallAttemptTimeoutMillis: Option[Long] = None,
    retryMaxAttempts: Option[Int] = None,
    endpointOverride: Option[String] = None,
    maxTopK: Option[Int] = None,
    maxGetKeys: Option[Int] = None
) derives ReadWriter

/**
 * `services.apiKeys.*`: the external contract of the Secrets Manager-backed services
 * `ApiKeyDigestSource` (SV-INT-03). `secretId` is a REFERENCE (ARN or name), never a secret value.
 * `cacheTtlMillis` bounds how long one read of the secret may serve: services
 * `ApiKeyAuthenticator.authenticate` calls `source.clients()` synchronously on every Bearer request
 * (modules/services/src/main/scala/scholarchain/services/Auth.scala:77-78), so without a cache
 * every request would be one Secrets Manager call.
 */
final case class ApiKeySourceConfig(
    secretId: Option[String] = None,
    apiCallTimeoutMillis: Option[Long] = None,
    apiCallAttemptTimeoutMillis: Option[Long] = None,
    retryMaxAttempts: Option[Int] = None,
    endpointOverride: Option[String] = None,
    cacheTtlMillis: Option[Long] = None
) derives ReadWriter

/** Mirrored storage bounds and adapter SDK-call counts (source references on each). */
object AdapterBounds {

  /** Storage `RetrySetting.MaxAttempts` (AwsCommon.scala:37; checked 1..10 at :39-41). */
  val MaxRetryAttempts: Int = 10

  /**
   * Storage `S3ReaderSettings.MaxObjectBytesLimit` = `Int.MaxValue - 9` (S3ObjectReader.scala:79;
   * checked 1..limit at :92).
   */
  val MaxS3ObjectBytes: Int = Int.MaxValue - 9

  /** Storage `S3ReaderSettings.MaxListKeysLimit` (S3ObjectReader.scala:73; checked at :94). */
  val MaxS3ListKeys: Int = 1000

  /** Storage `S3VectorsReaderSettings.MaxTopKPerPage` (S3VectorsReader.scala:82; :96). */
  val MaxS3VectorsTopK: Int = 100

  /**
   * Storage `S3VectorsReaderSettings.MaxGetKeys` (S3VectorsReader.scala:85; :98), and at least
   * `maxTopK` (:100).
   */
  val MaxS3VectorsGetKeys: Int = 100

  /** Storage `S3VectorsReaderSettings` vector-bucket name rule (S3VectorsReader.scala:87, :94). */
  val VectorBucketPattern: String = "^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$"

  // SDK calls per adapter operation, counted on the code paths (each bounded by apiCallTimeout,
  // retries included, Q4).

  /** `S3ObjectReader.head`: one HeadObject (S3ObjectReader.scala:179-191, the call at :181). */
  val S3HeadSdkCalls: Int = 1

  /**
   * `S3ObjectReader.get`: one GetObject (S3ObjectReader.scala:193-209, the call at :201), then the
   * body read (:215-287), which is NOT an SDK call and NOT bounded here (see the streaming
   * limitation in [[AdapterBudgets]]).
   */
  val S3GetSdkCalls: Int = 1

  /**
   * `S3ObjectReader.list`: one ListObjectsV2 page (S3ObjectReader.scala:303-310), then one
   * HeadObject per listed key (:328-337 through `head`), at most `maxKeys <= maxListKeys` keys
   * (:300-302, :319-320): `1 + maxListKeys` calls (decision D6).
   */
  val S3ListPageSdkCalls: Int = 1

  /** `NeptuneGraphReader.run`: one ExecuteOpenCypherQuery (NeptuneGraphReader.scala:141). */
  val NeptuneRunSdkCalls: Int = 1

  /**
   * A runtime vector query: `S3VectorsReader.query` makes one QueryVectors
   * (S3VectorsReader.scala:354), and the verified facade services uses (`VectorReader.verified`,
   * modules/storage/src/main/scala/scholarchain/storage/port/ VectorStore.scala:167 then :188-190)
   * re-reads every non-empty result with ONE GetVectors (S3VectorsReader.scala:406-418): 2 calls.
   */
  val S3VectorsVerifiedQuerySdkCalls: Int = 2

  /** `S3VectorsReader.getVectors`: one GetVectors (S3VectorsReader.scala:406-418). */
  val S3VectorsGetSdkCalls: Int = 1

  /** `S3VectorsReader.describe`: one GetIndex (S3VectorsReader.scala:468-476). */
  val S3VectorsDescribeSdkCalls: Int = 1

  /**
   * Secrets Manager secret name: 1..512 of `[A-Za-z0-9/_+=.@-]`. AS REMEMBERED, UNVERIFIED against
   * AWS documentation (no page was fetched for DE-INT-04); conservative (no `:`), so an ARN never
   * passes as a name.
   */
  val SecretNamePattern: String = "^[A-Za-z0-9/_+=.@-]{1,512}$"

  /**
   * Secrets Manager secret ARN: `arn:<aws partition>:secretsmanager:<Region>:<12-digit
   * account>:secret:<name>`. AS REMEMBERED, UNVERIFIED (as [[SecretNamePattern]]); the Region uses
   * the storage Region rule ([[ProductionBounds.RegionPattern]]).
   */
  val SecretArnPattern: String =
    "^arn:aws(-[a-z]+)*:secretsmanager:[a-z]{2}(-[a-z]+)+-[0-9]{1,2}:[0-9]{12}:secret:" +
      "[A-Za-z0-9/_+=.@-]{1,512}$"

  /**
   * Storage `NeptuneClientSettings.checkEndpoint` (NeptuneGraphReader.scala:76-94), restated
   * exactly: no whitespace or control character, a parseable URI with scheme `https`, a non-empty
   * host, an explicit port in 1..65535, and no user info, query, fragment or path other than empty
   * or `/`.
   */
  def neptuneEndpointOk(s: String): Boolean =
    s != null && !s.exists(c => c.isWhitespace || c.isControl) &&
      (try {
        val u = new URI(s)
        u.getScheme == "https" && Option(u.getHost).exists(_.nonEmpty) &&
        u.getPort >= 1 && u.getPort <= ProductionBounds.MaxPort && u.getRawUserInfo == null &&
        u.getRawQuery == null && u.getRawFragment == null &&
        Option(u.getRawPath).forall(p => p.isEmpty || p == "/")
      } catch { case NonFatal(_) => false })

  /** A secret ARN or a secret name ([[SecretArnPattern]], [[SecretNamePattern]]). */
  def secretRefOk(s: String): Boolean =
    s != null && (s.matches(SecretArnPattern) || s.matches(SecretNamePattern))

  /**
   * NEP-3: the legacy free-text `storage.neptuneEndpoint` names the same endpoint as the typed
   * `storage.neptune.endpoint`: it equals it (with or without a trailing `/`), its host, or
   * `host:port`.
   */
  def legacyNeptuneMatches(legacy: String, typed: String): Boolean =
    legacy != null && typed != null && neptuneEndpointOk(typed) && {
      val u = new URI(typed)
      Set(typed, typed.stripSuffix("/"), u.getHost, s"${u.getHost}:${u.getPort}").contains(legacy)
    }
}

/** The DE-INT-04 key table, as data. */
object AdapterKeys {
  import ValueKind.*
  import AdapterBounds.*

  // Literal prefixes (no reference to ProductionKeys: object initialization stays acyclic).
  val S3Prefix = "storage.s3."
  val NeptunePrefix = "storage.neptune."
  val S3VectorsPrefix = "storage.s3vectors."
  val ApiKeysPrefix = "services.apiKeys."
  val DynamoDbRetryKey = "storage.dynamodb.retryMaxAttempts"

  private def millis(p: String) =
    KeySpec(p, OptionalInt64, KeyBound.Range(1, ProductionBounds.MaxDurationMillis))
  private def retry(p: String) =
    KeySpec(p, OptionalInt32, KeyBound.Range(1, MaxRetryAttempts.toLong))
  private def int(p: String, max: Long) = KeySpec(p, OptionalInt32, KeyBound.Range(1, max))

  /** Call timeout, attempt timeout, retry attempts and (optionally) the endpoint override. */
  private def client(prefix: String, endpointOverride: Boolean): Vector[KeySpec] =
    Vector(
      millis(prefix + "apiCallTimeoutMillis"),
      millis(prefix + "apiCallAttemptTimeoutMillis"),
      retry(prefix + "retryMaxAttempts")
    ) ++ Option
      .when(endpointOverride)(
        KeySpec(prefix + "endpointOverride", OptionalString, KeyBound.Endpoint)
      )
      .toVector

  /** `storage.dynamodb.retryMaxAttempts`: storage `RetrySetting` of the DynamoDB client factory. */
  val dynamoDbRetry: Vector[KeySpec] = Vector(retry(DynamoDbRetryKey))

  val s3: Vector[KeySpec] = client(S3Prefix, endpointOverride = true) ++ Vector(
    int(S3Prefix + "maxObjectBytes", MaxS3ObjectBytes.toLong),
    int(S3Prefix + "maxListKeys", MaxS3ListKeys.toLong)
  )

  val neptune: Vector[KeySpec] =
    Vector(KeySpec(NeptunePrefix + "endpoint", OptionalString, KeyBound.NeptuneEndpoint)) ++
      client(NeptunePrefix, endpointOverride = false) ++
      Vector(int(NeptunePrefix + "maxRows", Int.MaxValue.toLong))

  val s3Vectors: Vector[KeySpec] = client(S3VectorsPrefix, endpointOverride = true) ++ Vector(
    int(S3VectorsPrefix + "maxTopK", MaxS3VectorsTopK.toLong),
    int(S3VectorsPrefix + "maxGetKeys", MaxS3VectorsGetKeys.toLong)
  )

  val apiKeys: Vector[KeySpec] =
    Vector(KeySpec(ApiKeysPrefix + "secretId", OptionalString, KeyBound.SecretReference)) ++
      client(ApiKeysPrefix, endpointOverride = true) ++
      Vector(millis(ApiKeysPrefix + "cacheTtlMillis"))

  /** Every DE-INT-04 key, in table order. */
  val all: Vector[KeySpec] = dynamoDbRetry ++ s3 ++ neptune ++ s3Vectors ++ apiKeys

  /** The endpoint overrides: optional even where their section is required. */
  val OptionalKeys: Set[String] =
    Set(S3Prefix, S3VectorsPrefix, ApiKeysPrefix).map(_ + "endpointOverride")

  private def required(v: Vector[KeySpec]): Vector[String] =
    v.map(_.path).filterNot(OptionalKeys.contains)

  /**
   * The DE-INT-04 keys a process requires at launch: query-jobs the DynamoDB retry, S3, Neptune, S3
   * Vectors and the API-key source; evidence-explain the DynamoDB retry and the API-key source;
   * graph Neptune and the API-key source (the processes' composition, services
   * ProductionComposition.scala:236-240).
   */
  def requiredFor(kind: ServiceKindKey): Vector[String] = kind match {
    case ServiceKindKey.QueryJobs =>
      required(dynamoDbRetry) ++ required(s3) ++ required(neptune) ++ required(s3Vectors) ++
        required(apiKeys)
    case ServiceKindKey.EvidenceExplain => required(dynamoDbRetry) ++ required(apiKeys)
    case ServiceKindKey.Graph => required(neptune) ++ required(apiKeys)
  }
}

/**
 * Derived adapter budgets (saturating; [[SatMath]]). One SDK call costs at most its client's
 * `apiCallTimeoutMillis`, retries and backoff included (Q4), so a budget is (SDK calls on the
 * operation's path) x `apiCallTimeoutMillis`, the calls being sequential.
 *
 * Whole-call bound: no rule relates `retryMaxAttempts x apiCallAttemptTimeoutMillis` (plus backoff)
 * to `apiCallTimeoutMillis`. The SDK enforces `apiCallTimeout` over all attempts whatever their
 * number; such a rule would neither tighten nor loosen any budget, and its backoff term is an SDK
 * internal that storage does not configure and the fetched documentation does not state. The only
 * whole-call consistency rule is the constructor's `apiCallAttemptTimeoutMillis <
 * apiCallTimeoutMillis` (S3-1, NEP-1, S3V-1, KEY-1).
 *
 * DynamoDB: the explicit retry setting changes no DynamoDB budget. X-4[query-jobs] (3 SDK calls per
 * ownership-record attempt), X-4[evidence-explain] (`maxQueryPages` calls) and X-5 (BatchGetItem
 * chunks, whose retries of unprocessed keys are separate adapter-level calls) already cost each SDK
 * call at `apiCallTimeoutMillis`, which covers that call's SDK retries; DE-INT-03's `3 *
 * apiCallTimeoutMillis` remains correct.
 *
 * STREAMING LIMITATION (unresolved; Q7 NOT STATED): `S3ObjectReader.get` reads the GetObject body
 * AFTER the SDK call returns (S3ObjectReader.scala:215-287). Whether `apiCallTimeout` covers that
 * read is not stated by the documentation; the SDK auto-aborts after 60 s only when `read()` is
 * never invoked. No budget here bounds the body read, so [[s3AttemptBudgetMillis]] bounds the SDK
 * calls of a `get`, not its total duration. What would close it: a per-read socket timeout on the
 * HTTP client (storage/build), a documented SDK option covering the body, or a services-side
 * deadline around the whole `get` that abandons the stream (service-engineer).
 */
object AdapterBudgets {
  import SatMath.*
  import AdapterBounds.*

  /** `calls` sequential SDK calls of at most `apiCallTimeoutMillis` each. */
  def sequentialMillis(calls: Long, apiCallTimeoutMillis: Long): Long =
    mul(calls, apiCallTimeoutMillis)

  /** SDK calls of one `list`: one page plus one HeadObject per key, `1 + maxListKeys` (D6). */
  def s3ListSdkCalls(maxListKeys: Long): Long =
    add(S3ListPageSdkCalls.toLong, math.max(0L, maxListKeys))

  /** The most SDK calls of one S3 reader operation: max(get 1, head 1, list 1 + maxListKeys). */
  def s3WorstSdkCalls(maxListKeys: Long): Long =
    math.max(math.max(S3GetSdkCalls.toLong, S3HeadSdkCalls.toLong), s3ListSdkCalls(maxListKeys))

  /** The SDK-call budget of the worst S3 reader operation (the body read is NOT included). */
  def s3AttemptBudgetMillis(maxListKeys: Long, apiCallTimeoutMillis: Long): Long =
    sequentialMillis(s3WorstSdkCalls(maxListKeys), apiCallTimeoutMillis)

  /** One graph read: one ExecuteOpenCypherQuery. */
  def neptuneAttemptBudgetMillis(apiCallTimeoutMillis: Long): Long =
    sequentialMillis(NeptuneRunSdkCalls.toLong, apiCallTimeoutMillis)

  /** The worst vector operation: a verified query, QueryVectors then GetVectors (2 calls). */
  def s3VectorsAttemptBudgetMillis(apiCallTimeoutMillis: Long): Long =
    sequentialMillis(
      math.max(
        S3VectorsVerifiedQuerySdkCalls.toLong,
        math.max(S3VectorsGetSdkCalls.toLong, S3VectorsDescribeSdkCalls.toLong)
      ),
      apiCallTimeoutMillis
    )
}

/**
 * The DE-INT-04 rule table, as data. Rules may read four non-production keys
 * ([[AdapterRules.NonProductionInputs]]), which [[ProductionConfig.ruleInputs]] supplies.
 *
 * Timing assumption (wiring obligation, service-engineer and rag-engineer): each adapter operation
 * is run as ONE attempt of the consuming process's services read policy, whose attempt is bounded
 * by `readTimeoutMillis` (Resilience.scala:345-355), as the graph service does
 * (ReadServices.scala:322). The T-rules bound the operation's SDK calls within that attempt (N4: a
 * timed-out attempt's SDK work ends by the attempt bound, up to the non-guaranteed abort, Q4).
 * Reads the query-jobs workflow makes through rag must run under that policy too, or these rules do
 * not bound them.
 */
object AdapterRules {
  import AdapterKeys.*
  import AdapterBudgets.*

  val AwsRegionKey = "aws.region"
  val VectorBucketKey = "storage.vectorBucket"
  val LegacyNeptuneKey = "storage.neptuneEndpoint"
  val RetrievalTopKKey = "runtime.retrievalTopK"

  /** The non-production keys the rules read. */
  val NonProductionInputs: Vector[String] =
    Vector(AwsRegionKey, VectorBucketKey, LegacyNeptuneKey, RetrievalTopKKey)

  /** The section prefixes whose keys make ADP-1 apply. */
  val AdapterPrefixes: Vector[String] =
    Vector(S3Prefix, NeptunePrefix, S3VectorsPrefix, ApiKeysPrefix)

  private def l(m: Map[String, Any], k: String): Long = m(k) match {
    case i: Int => i.toLong
    case x: Long => x
    case _ => 0L
  }

  private def s(m: Map[String, Any], k: String): String = m(k) match {
    case x: String => x
    case _ => ""
  }

  private def anyKey(m: Map[String, Any], prefixes: Vector[String]): Boolean =
    m.keysIterator.exists(k => prefixes.exists(k.startsWith))

  private val Q = ServiceKindKey.QueryJobs.prefix

  private def attemptBelowCall(id: String, p: String, source: String) =
    ConfigRule(
      id,
      Vector(p + "apiCallAttemptTimeoutMillis", p + "apiCallTimeoutMillis"),
      s"${p}apiCallAttemptTimeoutMillis < ${p}apiCallTimeoutMillis",
      s"$source; whole-call bound: apiCallTimeoutMillis covers every attempt and backoff of one " +
        "SDK call (se-int-04-docs.md Q4)",
      m => l(m, p + "apiCallAttemptTimeoutMillis") < l(m, p + "apiCallTimeoutMillis")
    )

  private val StorageClientSource =
    "storage AwsClientSettings.of (AwsCommon.scala:60-81) through DynamoDbClientSettings.of " +
      "(DynamoDbSettings.scala:64-72)"

  /** Neptune serves the graph limits of a process that reads the graph. */
  private def neptuneRows(kind: ServiceKindKey) = {
    val p = kind.prefix
    ConfigRule(
      s"NEP-2[${kind.tag}]",
      Vector(NeptunePrefix + "maxRows", p + "graphMaxResults", p + "graphMaxPageSize"),
      s"${NeptunePrefix}maxRows >= max(${p}graphMaxResults, ${p}graphMaxPageSize)",
      "storage NeptuneGraphReader refuses more than maxRows rows (NeptuneGraphReader.scala:" +
        "162-167); the graph service binds limit = offset + pageSize + 1 <= graphMaxResults " +
        "(ReadServices.scala:294-307)",
      m =>
        l(m, NeptunePrefix + "maxRows") >=
          math.max(l(m, p + "graphMaxResults"), l(m, p + "graphMaxPageSize"))
    )
  }

  /** Sequential: one ExecuteOpenCypherQuery within one read attempt of the process. */
  private def neptuneTiming(kind: ServiceKindKey) = {
    val p = kind.prefix
    ConfigRule(
      s"T-NEP[${kind.tag}]",
      Vector(NeptunePrefix + "apiCallTimeoutMillis", p + "readTimeoutMillis"),
      s"AdapterBudgets.neptuneAttemptBudgetMillis = ${AdapterBounds.NeptuneRunSdkCalls} * " +
        s"${NeptunePrefix}apiCallTimeoutMillis <= ${p}readTimeoutMillis",
      "NeptuneGraphReader.run makes one SDK call (NeptuneGraphReader.scala:141), retries inside " +
        "apiCallTimeout (Q4), within one read attempt (Resilience.scala:345-355; " +
        "ReadServices.scala:322)",
      m =>
        neptuneAttemptBudgetMillis(l(m, NeptunePrefix + "apiCallTimeoutMillis")) <=
          l(m, p + "readTimeoutMillis")
    )
  }

  val all: Vector[ConfigRule] = Vector(
    attemptBelowCall("S3-1", S3Prefix, StorageClientSource),
    attemptBelowCall(
      "NEP-1",
      NeptunePrefix,
      "storage NeptuneClientSettings.of (NeptuneGraphReader.scala:58-74) through " +
        "AwsClientSettings.of (AwsCommon.scala:60-81)"
    ),
    attemptBelowCall("S3V-1", S3VectorsPrefix, StorageClientSource),
    attemptBelowCall(
      "KEY-1",
      ApiKeysPrefix,
      "the Secrets Manager client of the API-key source (SV-INT-03), same AWS client rule as " +
        StorageClientSource
    ),
    ConfigRule(
      "S3V-2",
      Vector(S3VectorsPrefix + "maxGetKeys", S3VectorsPrefix + "maxTopK"),
      s"${S3VectorsPrefix}maxGetKeys >= ${S3VectorsPrefix}maxTopK",
      "storage S3VectorsReaderSettings.of (S3VectorsReader.scala:100): the verified facade " +
        "re-reads topK keys with one GetVectors (VectorStore.scala:188-190)",
      m => l(m, S3VectorsPrefix + "maxGetKeys") >= l(m, S3VectorsPrefix + "maxTopK")
    ),
    ConfigRule(
      "S3V-3",
      Vector(VectorBucketKey),
      s"$VectorBucketKey matches ${AdapterBounds.VectorBucketPattern} when any " +
        s"${S3VectorsPrefix}* key is set",
      "storage S3VectorsReaderSettings.of vector-bucket rule (S3VectorsReader.scala:87, :94), " +
        "stricter than the S3 bucket rule (no dots)",
      m => s(m, VectorBucketKey).matches(AdapterBounds.VectorBucketPattern),
      applies = Some(m => m.contains(VectorBucketKey) && anyKey(m, Vector(S3VectorsPrefix)))
    ),
    ConfigRule(
      "S3V-4",
      Vector(S3VectorsPrefix + "maxTopK", RetrievalTopKKey),
      s"${S3VectorsPrefix}maxTopK >= $RetrievalTopKKey",
      "storage S3VectorsReader.query refuses topK above maxTopK (S3VectorsReader.scala:334-336); " +
        "the runtime retrieves runtime.retrievalTopK matches",
      m => l(m, S3VectorsPrefix + "maxTopK") >= l(m, RetrievalTopKKey)
    ),
    neptuneRows(ServiceKindKey.QueryJobs),
    neptuneRows(ServiceKindKey.Graph),
    ConfigRule(
      "NEP-3",
      Vector(NeptunePrefix + "endpoint", LegacyNeptuneKey),
      s"$LegacyNeptuneKey equals ${NeptunePrefix}endpoint, its host or host:port",
      "DE-INT-04 migration: the adapter reads only the typed key; the legacy free-text key may " +
        "not name another endpoint",
      m =>
        AdapterBounds.legacyNeptuneMatches(s(m, LegacyNeptuneKey), s(m, NeptunePrefix + "endpoint"))
    ),
    ConfigRule(
      "ADP-1",
      Vector(AwsRegionKey),
      s"$AwsRegionKey matches ${ProductionBounds.RegionPattern} when any " +
        s"${AdapterPrefixes.map(_ + "*").mkString(", ")} key is set",
      "storage AwsClientSettings.of Region rule (AwsCommon.scala:68; DynamoDbSettings.scala:34, " +
        ":56-58), stricter than the base aws.region rule",
      m => s(m, AwsRegionKey).matches(ProductionBounds.RegionPattern),
      applies = Some(m => m.contains(AwsRegionKey) && anyKey(m, AdapterPrefixes))
    ),
    ConfigRule(
      "T-S3[query-jobs]",
      Vector(S3Prefix + "apiCallTimeoutMillis", S3Prefix + "maxListKeys", Q + "readTimeoutMillis"),
      s"AdapterBudgets.s3AttemptBudgetMillis = (${AdapterBounds.S3ListPageSdkCalls} + " +
        s"${S3Prefix}maxListKeys) * ${S3Prefix}apiCallTimeoutMillis <= ${Q}readTimeoutMillis",
      "sequential SDK calls: list is one ListObjectsV2 plus one HeadObject per key " +
        "(S3ObjectReader.scala:303-310, :328-337; D6), get and head one call each (:181, :201); " +
        "the GetObject body read is NOT bounded (streaming limitation, Q7)",
      m =>
        s3AttemptBudgetMillis(
          l(m, S3Prefix + "maxListKeys"),
          l(m, S3Prefix + "apiCallTimeoutMillis")
        ) <= l(m, Q + "readTimeoutMillis")
    ),
    neptuneTiming(ServiceKindKey.QueryJobs),
    neptuneTiming(ServiceKindKey.Graph),
    ConfigRule(
      "T-S3V[query-jobs]",
      Vector(S3VectorsPrefix + "apiCallTimeoutMillis", Q + "readTimeoutMillis"),
      s"AdapterBudgets.s3VectorsAttemptBudgetMillis = " +
        s"${AdapterBounds.S3VectorsVerifiedQuerySdkCalls} * " +
        s"${S3VectorsPrefix}apiCallTimeoutMillis <= ${Q}readTimeoutMillis",
      "a verified query is QueryVectors then GetVectors (S3VectorsReader.scala:354, :406-418; " +
        "VectorStore.scala:167, :188-190); getVectors and describe are one call each",
      m =>
        s3VectorsAttemptBudgetMillis(l(m, S3VectorsPrefix + "apiCallTimeoutMillis")) <=
          l(m, Q + "readTimeoutMillis")
    )
  )
}

/** The settings of one AWS SDK client: storage `AwsClientSettings.of` takes exactly these. */
final case class AdapterClientSettings(
    region: String,
    endpointOverride: Option[String],
    apiCallTimeoutMillis: Long,
    apiCallAttemptTimeoutMillis: Long,
    retryMaxAttempts: Int
)

/**
 * `storage.s3.*`: storage `AwsClientSettings.of(client...)` and `S3ReaderSettings.of(buckets.toSet,
 * maxObjectBytes, maxListKeys)`; `buckets` are `storage.rawBucket`, `curatedBucket` and
 * `contextBucket` in that order (decision D4).
 */
final case class S3AdapterSettings(
    client: AdapterClientSettings,
    buckets: Vector[String],
    maxObjectBytes: Int,
    maxListKeys: Int
)

/**
 * `storage.neptune.*`: storage `NeptuneClientSettings.of(region, endpoint, apiCallTimeoutMillis,
 * apiCallAttemptTimeoutMillis, retryMaxAttempts)` and `NeptuneReaderSettings.of(maxRows)`.
 */
final case class NeptuneAdapterSettings(
    region: String,
    endpoint: String,
    apiCallTimeoutMillis: Long,
    apiCallAttemptTimeoutMillis: Long,
    retryMaxAttempts: Int,
    maxRows: Int
)

/**
 * `storage.s3vectors.*`: storage `AwsClientSettings.of(client...)` and
 * `S3VectorsReaderSettings.of(vectorBucket, maxTopK, maxGetKeys)`; `vectorBucket` is
 * `storage.vectorBucket`.
 */
final case class S3VectorsAdapterSettings(
    client: AdapterClientSettings,
    vectorBucket: String,
    maxTopK: Int,
    maxGetKeys: Int
)

/** `services.apiKeys.*`: the Secrets Manager-backed `ApiKeyDigestSource` (SV-INT-03). */
final case class ApiKeySourceSettings(
    secretId: String,
    client: AdapterClientSettings,
    cacheTtlMillis: Long
)

/**
 * Everything a service launcher needs (DE-INT-04): the composition settings of
 * [[ProductionSettings.forProcess]] (unchanged), plus the adapter and API-key source settings of
 * the process. `dynamoDbRetryMaxAttempts` is storage `RetrySetting.of` for
 * `StorageClients.dynamoDbFactory` (query-jobs and evidence-explain); `s3` and `s3Vectors` are
 * query-jobs only; `neptune` is query-jobs and graph.
 */
final case class LauncherSettings(
    process: ProcessSettings,
    dynamoDbRetryMaxAttempts: Option[Int],
    s3: Option[S3AdapterSettings],
    neptune: Option[NeptuneAdapterSettings],
    s3Vectors: Option[S3VectorsAdapterSettings],
    apiKeys: ApiKeySourceSettings,
    workflow: Option[ProductionWorkflowSettings] = None
)
