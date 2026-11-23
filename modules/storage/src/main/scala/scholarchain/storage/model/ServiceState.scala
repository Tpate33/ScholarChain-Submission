package scholarchain.storage.model

import java.nio.charset.StandardCharsets

import upickle.default.ReadWriter
import upickle.implicits.allowUnknownKeys

import scholarchain.core.{Canonical, IdKind, StableId}

/**
 * SE-INT-01: identifiers and digests of the service-state tables. Client IDs, job IDs, attempt IDs
 * and writer labels are tokens `[A-Za-z0-9._-]{1,128}`; query IDs are core `query:` IDs.
 */
object ServiceStateIds {
  val MaxTokenLength = 128

  /** Domain prefix of the idempotency digest; a new digest form needs a new prefix. */
  val IdempotencyDigestVersion = "scholarchain.storage.idempotency-key-digest.v1"

  def token(value: String, field: String): Either[StoreError, String] =
    if (value != null && value.length <= MaxTokenLength && StableId.isToken(value)) Right(value)
    else Left(StoreError.Refused(s"$field: not a token [A-Za-z0-9._-]{1,$MaxTokenLength}"))

  def clientId(v: String): Either[StoreError, String] = token(v, "clientId")
  def jobId(v: String): Either[StoreError, String] = token(v, "jobId")

  /** SE-INT-02: the shortest caller-supplied attempt ID `begin` accepts. */
  val MinAttemptIdLength = 16

  /**
   * SE-INT-02 (CR-INT-01-1): the caller's attempt ID of one LOGICAL idempotent request: a token of
   * [[MinAttemptIdLength]]..[[MaxTokenLength]] characters, minted once per logical request (for
   * example by `AttemptTokens.secureRandom()`, 128 random bits) and reused unchanged by every retry
   * of that request. Independent requests must use independent IDs: two callers that share one ID
   * are, to the store, one request.
   */
  def attemptId(v: String): Either[StoreError, String] =
    token(v, "attemptId").flatMap(a =>
      if (a.length >= MinAttemptIdLength) Right(a)
      else Left(StoreError.Refused(s"attemptId: shorter than $MinAttemptIdLength characters"))
    )
  def queryId(v: String): Either[StoreError, String] = StoreIds.require(IdKind.Query, v, "queryId")

  /**
   * The partition digest of one (client ID, Idempotency-Key) pair: SHA-256 of
   * `<version>\n<len(client)>:<client>\n<len(key)>:<key>` (lengths in UTF-8 bytes). The length
   * prefixes make the encoding injective, so ("ab", "c") and ("a", "bc") never share a digest. The
   * raw key is never stored; error details never echo it.
   */
  def idempotencyKeySha256(clientId: String, key: String): Either[StoreError, String] =
    for {
      c <- ServiceStateIds.clientId(clientId)
      k <- token(key, "idempotencyKey")
    } yield {
      def len(s: String) = s.getBytes(StandardCharsets.UTF_8).length
      Canonical.sha256HexUtf8(s"$IdempotencyDigestVersion\n${len(c)}:$c\n${len(k)}:$k")
    }
}

/**
 * SE-INT-01: payload bounds of the service-state tables. Job results, job errors and replayed
 * idempotent outcomes are opaque JSON written by services; storage checks only that each is one
 * well-formed JSON value within the bound.
 */
object ServiceStateBounds {

  /**
   * Storage's own hard cap on one payload (256 KiB), whatever the configuration says. A storage
   * design limit chosen to stay well under the DynamoDB item size limit (400 KB as remembered;
   * UNVERIFIED against AWS documentation, aws-docs MCP unavailable on 2026-11-22).
   */
  val CeilingBytes: Int = 262144

  def bytes(s: String): Int = s.getBytes(StandardCharsets.UTF_8).length

  /**
   * SE-INT-02 (CR-INT-01-2): storage's cap on one ENCODED service-state item (350 KiB), counted by
   * [[itemBytes]]. The raw payload bound above is not enough: the codec stores each payload as a
   * string inside the JSON `body`, so every `"` and `\` of the payload costs 2 bytes there, and a
   * quote- or backslash-dense payload under the raw bound can nearly double. Chosen below the
   * DynamoDB item size limit (400 KB as remembered; UNVERIFIED against AWS documentation, aws-docs
   * MCP unavailable on 2026-11-22) with a margin for the size accounting itself.
   */
  val MaxItemBytes: Int = 358400

  /**
   * A conservative (never smaller) estimate of an item's DynamoDB size, as remembered: every
   * attribute name in UTF-8 bytes; S as UTF-8 bytes; N as its decimal text plus 1 (DynamoDB stores
   * about one byte per two digits plus one); BOOL and NULL 1; L and M 3 plus 1 per element plus the
   * elements (and, for M, the element names). UNVERIFIED against AWS documentation.
   */
  def itemBytes(item: Item): Long = {
    def value(v: AttrValue): Long = v match {
      case AttrValue.S(s) => bytes(s).toLong
      case AttrValue.N(n) => n.length.toLong + 1
      case AttrValue.Bool(_) | AttrValue.Null => 1L
      case AttrValue.L(xs) => 3L + xs.iterator.map(x => 1L + value(x)).sum
      case AttrValue.M(m) =>
        3L + m.iterator.map { case (k, x) => 1L + bytes(k) + value(x) }.sum
    }
    item.iterator.map { case (k, v) => bytes(k).toLong + value(v) }.sum
  }

  /** Why the encoded `item` is too large for `max` bytes; None when it fits. */
  def itemProblem(table: String, item: Item, max: Long = MaxItemBytes): Option[String] = {
    val n = itemBytes(item)
    Option.when(n > max)(s"$table: the encoded item is $n bytes, over the bound of $max")
  }

  /** Why `json` is not an acceptable payload of at most `max` bytes; None when acceptable. */
  def problem(field: String, json: String, max: Int): Option[String] =
    if (json == null || json.isEmpty) Some(s"$field: missing or empty")
    else if (bytes(json) > math.min(max, CeilingBytes))
      Some(s"$field: ${bytes(json)} bytes exceed the bound of ${math.min(max, CeilingBytes)}")
    else
      try { ujson.read(json); None }
      catch { case _: Exception => Some(s"$field: not one well-formed JSON value") }
}

/**
 * SE-INT-01: the owner of one query (QueryOwnership, QO-1/QO-2, TTL `expiresAt`).
 *
 * @param writer
 *   provenance: the component that wrote the row (a configured token, for example the service name)
 * @param recordedAtMillis
 *   provenance: when the owner was first recorded (epoch milliseconds)
 */
@allowUnknownKeys(false)
final case class QueryOwnerRecord(
    schemaVersion: String,
    queryId: String,
    clientId: String,
    writer: String,
    recordedAtMillis: Long,
    expiresAtEpochSeconds: Long
) derives ReadWriter

object QueryOwnerRecord {
  val SchemaVersion = "scholarchain.storage.query-owner.v1"
}

/**
 * SE-INT-01: job states, forward only: queued -> running -> succeeded | failed; queued -> failed.
 */
object JobStates {
  val Queued = "queued"
  val Running = "running"
  val Succeeded = "succeeded"
  val Failed = "failed"
  val All: Set[String] = Set(Queued, Running, Succeeded, Failed)
  val Terminal: Set[String] = Set(Succeeded, Failed)

  /** The allowed forward transitions (from, to). */
  val Forward: Set[(String, String)] =
    Set(Queued -> Running, Queued -> Failed, Running -> Succeeded, Running -> Failed)
}

/**
 * SE-INT-01: one asynchronous job (QueryJob, QJ-1, TTL `expiresAt`, optimistic `version`). The
 * result and error are opaque JSON written by services.
 */
@allowUnknownKeys(false)
final case class QueryJobRecord(
    schemaVersion: String,
    jobId: String,
    queryId: String,
    clientId: String,
    state: String,
    stage: String,
    createdAtMillis: Long,
    startedAtMillis: Option[Long],
    finishedAtMillis: Option[Long],
    resultJson: Option[String],
    errorJson: Option[String],
    version: Long,
    writer: String,
    updatedAtMillis: Long,
    expiresAtEpochSeconds: Long
) derives ReadWriter

object QueryJobRecord {
  val SchemaVersion = "scholarchain.storage.query-job.v1"

  /** Why `a` is not a consistent job row; empty when valid. */
  def problems(a: QueryJobRecord): Vector[String] = {
    val opt = Vector(a.startedAtMillis, a.finishedAtMillis, a.resultJson, a.errorJson)
    if (opt.contains(null)) Vector("optional fields must be absent or present, never null")
    else {
      val s = a.state
      Vector(
        Option.when(!JobStates.All.contains(s))(s"state: '$s' is not a job state"),
        Option.when(a.version < 1)(s"version: ${a.version} is below 1"),
        Option.when(a.createdAtMillis < 0 || a.updatedAtMillis < a.createdAtMillis)(
          "createdAtMillis and updatedAtMillis: negative or out of order"
        ),
        Option.when((s == JobStates.Queued) != a.startedAtMillis.isEmpty && s != JobStates.Failed)(
          "startedAtMillis: present exactly after the job started"
        ),
        Option.when(JobStates.Terminal.contains(s) != a.finishedAtMillis.isDefined)(
          "finishedAtMillis: present exactly on a finished job"
        ),
        Option.when((s == JobStates.Succeeded) != a.resultJson.isDefined)(
          "resultJson: present exactly on a succeeded job"
        ),
        Option.when((s == JobStates.Failed) != a.errorJson.isDefined)(
          "errorJson: present exactly on a failed job"
        )
      ).flatten ++
        a.resultJson.flatMap(ServiceStateBounds.problem("resultJson", _, Int.MaxValue)) ++
        a.errorJson.flatMap(ServiceStateBounds.problem("errorJson", _, Int.MaxValue))
    }
  }
}

/** SE-INT-01: idempotency record states. */
object IdempotencyStates {
  val InFlight = "in-flight"
  val Completed = "completed"
}

/**
 * SE-INT-01: one (client, Idempotency-Key) record (IdempotencyKey, IK-1, TTL `expiresAt`,
 * optimistic `version`). Keyed by `keySha256` ([[ServiceStateIds.idempotencyKeySha256]]); neither
 * the raw key nor the client ID is stored.
 *
 * @param requestSha256
 *   the digest of the request the key is bound to (services' canonical request digest)
 * @param attemptId
 *   SE-INT-02 (CR-INT-01-1): the caller's attempt ID of the LOGICAL request that wrote the
 *   in-flight record ([[ServiceStateIds.attemptId]]), reused by every retry of that request: a
 *   retry that finds a live in-flight record with its own attempt ID and request digest takes the
 *   record over (`Fresh`), any other attempt ID is `InProgress`
 * @param version
 *   epoch milliseconds of the fresh in-flight write (at least 1), then +1 per replacement, so a
 *   record written after an expired one was deleted by TTL does not reuse the old version
 */
@allowUnknownKeys(false)
final case class IdempotencyRecord(
    schemaVersion: String,
    keySha256: String,
    requestSha256: String,
    state: String,
    attemptId: String,
    outcomeJson: Option[String],
    createdAtMillis: Long,
    completedAtMillis: Option[Long],
    version: Long,
    writer: String,
    expiresAtEpochSeconds: Long
) derives ReadWriter

object IdempotencyRecord {
  val SchemaVersion = "scholarchain.storage.idempotency-key.v1"

  def problems(a: IdempotencyRecord): Vector[String] =
    if (a.outcomeJson == null || a.completedAtMillis == null)
      Vector("optional fields must be absent or present, never null")
    else {
      val completed = a.state == IdempotencyStates.Completed
      Vector(
        Option.when(a.state != IdempotencyStates.InFlight && !completed)(
          s"state: '${a.state}' is not an idempotency state"
        ),
        Option.when(a.version < 1)(s"version: ${a.version} is below 1"),
        Option.when(a.createdAtMillis < 0)("createdAtMillis: negative"),
        Option.when(completed != a.outcomeJson.isDefined)(
          "outcomeJson: present exactly on a completed record"
        ),
        Option.when(completed != a.completedAtMillis.isDefined)(
          "completedAtMillis: present exactly on a completed record"
        )
      ).flatten ++ a.outcomeJson.flatMap(ServiceStateBounds.problem("outcomeJson", _, Int.MaxValue))
    }
}

/** SE-INT-01: item codecs of the three service-state tables. */
object ServiceStateCodecs {
  private def check(ok: Boolean, why: => String): Either[StoreError, Unit] =
    if (ok) Right(()) else Left(StoreError.Refused(why))

  private def problems(p: Vector[String]) = check(p.isEmpty, p.mkString("; "))

  given QueryOwnerCodec: ItemCodec[QueryOwnerRecord] =
    new ItemCodec[QueryOwnerRecord](TableSpecs.QueryOwnership, QueryOwnerRecord.SchemaVersion) {
      protected def schemaVersionOf(a: QueryOwnerRecord) = a.schemaVersion
      override protected def validate(a: QueryOwnerRecord) =
        StoreIds.firstError(
          ServiceStateIds.queryId(a.queryId),
          ServiceStateIds.clientId(a.clientId),
          ServiceStateIds.token(a.writer, "writer"),
          check(a.recordedAtMillis >= 0, "recordedAtMillis: negative")
        )
      def key(a: QueryOwnerRecord) = TableKeys.QueryOwnership.owner(a.queryId)
      override protected def expiresAt(a: QueryOwnerRecord) = Some(a.expiresAtEpochSeconds)
    }

  given QueryJobCodec: ItemCodec[QueryJobRecord] =
    new ItemCodec[QueryJobRecord](TableSpecs.QueryJob, QueryJobRecord.SchemaVersion) {
      protected def schemaVersionOf(a: QueryJobRecord) = a.schemaVersion
      override protected def validate(a: QueryJobRecord) =
        StoreIds.firstError(
          ServiceStateIds.jobId(a.jobId),
          ServiceStateIds.queryId(a.queryId),
          ServiceStateIds.clientId(a.clientId),
          ServiceStateIds.token(a.stage, "stage"),
          ServiceStateIds.token(a.writer, "writer"),
          problems(QueryJobRecord.problems(a))
        )
      def key(a: QueryJobRecord) = TableKeys.QueryJob.job(a.jobId)
      override protected def version(a: QueryJobRecord) = Some(a.version)
      override protected def expiresAt(a: QueryJobRecord) = Some(a.expiresAtEpochSeconds)
    }

  given IdempotencyCodec: ItemCodec[IdempotencyRecord] =
    new ItemCodec[IdempotencyRecord](TableSpecs.IdempotencyKey, IdempotencyRecord.SchemaVersion) {
      protected def schemaVersionOf(a: IdempotencyRecord) = a.schemaVersion
      override protected def validate(a: IdempotencyRecord) =
        StoreIds.firstError(
          StoreIds.hex64(a.keySha256, "keySha256"),
          StoreIds.hex64(a.requestSha256, "requestSha256"),
          ServiceStateIds.token(a.attemptId, "attemptId"),
          ServiceStateIds.token(a.writer, "writer"),
          problems(IdempotencyRecord.problems(a))
        )
      def key(a: IdempotencyRecord) = TableKeys.IdempotencyKey.request(a.keySha256)
      override protected def version(a: IdempotencyRecord) = Some(a.version)
      override protected def expiresAt(a: IdempotencyRecord) = Some(a.expiresAtEpochSeconds)
    }
}
