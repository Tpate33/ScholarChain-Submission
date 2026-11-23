package scholarchain.storage.state

import java.security.SecureRandom

import scala.annotation.tailrec

import scholarchain.storage.model.{
  Attrs,
  AttrValue,
  IdempotencyRecord,
  IdempotencyStates,
  Item,
  ItemCodec,
  JobStates,
  QueryJobRecord,
  QueryOwnerRecord,
  ServiceStateBounds,
  ServiceStateCodecs,
  ServiceStateIds,
  StoreError,
  StoreIds,
  TableKeys,
  TableRole
}
import scholarchain.storage.port.{Clock, DeleteOutcome, ServiceStateKeyValues}

/**
 * SE-INT-01: settings of the service-state stores (configuration; no defaults). Construction checks
 * the cross-field rules that storage can check; the rules against services configuration
 * (QuerySession TTL, idempotency TTL) are routing items for domain-engineer.
 *
 * @param writer
 *   provenance token recorded on every row (for example the writing service's name) EXPIRY AND
 *   LIVENESS (SE-INT-03, CR-INT-02-3). Every service-state row (owner, job, in-flight and completed
 *   idempotency) gets `expiresAt` = the epoch second AT OR AFTER `write time + TTL` (rounded UP,
 *   [[Rows.expiryCeil]]), so the second granularity of DynamoDB TTL never shortens a lifetime. A
 *   job row's `expiresAt` is recomputed from each transition's `updatedAtMillis`; an owner row's
 *   and an in-flight record's are fixed at their first applied write. Every reader treats a row as
 *   live while `expiresAt * 1000 + maxClockSkewMillis > its own clock` ([[Rows.live]]), so a reader
 *   whose clock runs ahead of the writer's by at most the allowance never treats a live row as
 *   expired; the cost is that a reader whose clock runs behind can treat a row as live for up to
 *   the allowance after its writer-time expiry.
 *
 * @param ownershipTtlSeconds
 *   lifetime of an owner row (must be at least the QuerySession TTL: checked by core config)
 * @param jobTtlSeconds
 *   lifetime of a job row after its last update; `jobTtlSeconds * 1000` must exceed the longest
 *   workflow timeout. SE-INT-03 (CR-INT-02-3): this storage check deliberately has NO drain or skew
 *   term. Core ST-3 (`TimingBudgets.jobTtlRequirementMillis`) requires `jobTtlSeconds * 1000 >
 *   max(jobGap + drain + skew + 1000, max(sync, async))`, which already carries the drain and skew
 *   margins for the job-row gap; adding `drain + skew` to the `max(sync, async)` term here would
 *   refuse configurations core accepts (for example sync 100000, drain 5000, jobTtl 101), which
 *   CR-INT-04-1 forbids. Every core-accepted value is accepted here.
 * @param completedTtlSeconds
 *   lifetime of a completed idempotency record (the replay window)
 * @param inFlightTtlSeconds
 *   DESIGN DECISION (approved by the human for local validation, 2026-11-22; not a production
 *   approval): the crash bound of an in-flight idempotency record. `inFlightTtlSeconds * 1000` must
 *   exceed both the longest workflow timeout plus drainMillis and the caller's
 *   `inFlightRequirementMillis` (see [[ServiceStateSettings.of]]). The record's `expiresAt` is
 *   fixed at its FIRST APPLIED write (rounded up); a later `begin` retry of the same attempt that
 *   takes the record over gets the stored record and does NOT extend it, so every retry, setup and
 *   closing write must fit inside the requirement, measured from the first applied write. REMAINING
 *   DUPLICATE-EXECUTION RISK (SE-INT-03, CR-INT-02-2): a request STILL RUNNING past the bound could
 *   be executed a second time by another attempt under the same key, from
 *   - `inFlightTtlSeconds` after the first applied write (writer clock), when a replica judges the
 *     record expired (`begin` replaces it): under the CLOCK ASSUMPTION below the skew allowance
 *     only delays that judgement, never advances it, so the window opens at `inFlightTtl`, NOT at
 *     `inFlightTtl + maxClockSkewMillis`; and
 *   - `inFlightTtlSeconds * 1000 - maxClockSkewMillis` after it, when DynamoDB TTL deletion removes
 *     the row (`putIfAbsent` then succeeds for any attempt): the deletion uses the DynamoDB service
 *     clock, which may run ahead of the writer's by up to the allowance, and bypasses the
 *     reader-side allowance. When TTL deletion actually happens after expiry is DynamoDB behaviour
 *     (P8) and is UNVERIFIED (as remembered, usually well after expiry, up to days; never relied on
 *     for correctness, only as the earliest possible deletion). Core ST-2 includes
 *     `maxClockSkewMillis` in the requirement for this reason. When the first request finally
 *     finishes, its `complete` writes nothing (`StaleVersion`). The in-memory services store never
 *     expires an in-flight record; a durable store cannot keep that rule without letting a crashed
 *     replica pin a key forever.
 * @param maxPayloadBytes
 *   the largest job result, job error or replay outcome (UTF-8 JSON),
 *   1..[[ServiceStateBounds.CeilingBytes]]; the encoded item is bounded separately by
 *   [[ServiceStateBounds.MaxItemBytes]] (CR-INT-01-2)
 * @param maxConflictRetries
 *   re-reads after a lost optimistic update (job transitions, idempotency begin)
 * @param maxOwnedByQueryIds
 *   the most distinct query IDs one `ownedBy` call accepts (one batch read)
 * @param maxClockSkewMillis
 *   SE-INT-02 (CR-INT-01-4), SE-INT-03 (CR-INT-02-2, -3): the caller's allowance for wall-clock
 *   skew. A reader treats ANY service-state row (owner, job, in-flight or completed idempotency) as
 *   expired only once its own clock passes `expiresAt` PLUS this allowance, because `expiresAt` was
 *   set by the writing replica's clock. CLOCK ASSUMPTION: the clocks of all replicas AND the
 *   DynamoDB service clock that drives TTL deletion agree pairwise within this allowance. If a
 *   replica clock runs ahead of the writer's by more than the allowance, it can treat a live row as
 *   expired early by the excess (for an in-flight record: start a second execution inside the
 *   workflow timeout; for a job or owner row: report it missing). If the DynamoDB clock runs ahead
 *   by more than the allowance, TTL deletion can free a row earlier than the second bullet of
 *   `inFlightTtlSeconds` states. Nothing in storage can check this assumption; it is an environment
 *   requirement (core A4). Must be below `inFlightTtlSeconds` (the version-based ABA protection
 *   also assumes that bound).
 */
final case class ServiceStateSettings private (
    writer: String,
    ownershipTtlSeconds: Long,
    jobTtlSeconds: Long,
    completedTtlSeconds: Long,
    inFlightTtlSeconds: Long,
    maxPayloadBytes: Int,
    maxConflictRetries: Int,
    maxOwnedByQueryIds: Int,
    maxClockSkewMillis: Long,
    inFlightRequirementMillis: Long
)

object ServiceStateSettings {

  /** Ten years: a sanity bound on every TTL (rejects unit mistakes such as milliseconds). */
  val MaxTtlSeconds: Long = 10L * 366 * 24 * 3600

  /**
   * SE-INT-02 (CR-INT-01-7): the same sanity bound in milliseconds, on every duration input, so no
   * sum below can overflow (each term is at most about 3.2e11).
   */
  val MaxDurationMillis: Long = MaxTtlSeconds * 1000L

  /**
   * @param longestWorkflowTimeoutMillis
   *   the larger of the sync and async workflow timeouts of the service using these stores
   * @param drainMillis
   *   the service's shutdown drain time
   * @param maxClockSkewMillis
   *   the replica clock-skew allowance (see [[ServiceStateSettings]])
   * @param inFlightRequirementMillis
   *   SE-INT-03 (CR-INT-02-1, storage side): the longest an in-flight record must stay live after
   *   its first applied write, as the CALLER derived it (production: core
   *   `DurableStateSettings.inFlightRequirementMillis`, the value core rule ST-2 checked, which
   *   includes the begin retry budget, the pool wait, the workflow timeout, the closing write, the
   *   drain and the skew allowance). Storage does not recompute core's formula; it only refuses an
   *   `inFlightTtlSeconds` whose milliseconds do not exceed it (the same strict inequality as ST-2,
   *   so storage accepts every value core accepts). Any value `>= 0` is accepted as input; `0`
   *   leaves only the storage floor (workflow timeout plus drain).
   */
  def of(
      writer: String,
      ownershipTtlSeconds: Long,
      jobTtlSeconds: Long,
      completedTtlSeconds: Long,
      inFlightTtlSeconds: Long,
      maxPayloadBytes: Int,
      maxConflictRetries: Int,
      maxOwnedByQueryIds: Int,
      longestWorkflowTimeoutMillis: Long,
      drainMillis: Long,
      maxClockSkewMillis: Long,
      inFlightRequirementMillis: Long
  ): Either[StoreError, ServiceStateSettings] = {
    def ttl(name: String, v: Long) =
      if (v >= 1 && v <= MaxTtlSeconds) Right(v)
      else Left(StoreError.Refused(s"$name: outside 1..$MaxTtlSeconds"))
    def millis(name: String, v: Long, min: Long) =
      if (v >= min && v <= MaxDurationMillis) Right(v)
      else Left(StoreError.Refused(s"$name: outside $min..$MaxDurationMillis"))
    for {
      w <- ServiceStateIds.token(writer, "writer")
      _ <- ttl("ownershipTtlSeconds", ownershipTtlSeconds)
      _ <- ttl("jobTtlSeconds", jobTtlSeconds)
      _ <- ttl("completedTtlSeconds", completedTtlSeconds)
      _ <- ttl("inFlightTtlSeconds", inFlightTtlSeconds)
      _ <- millis("longestWorkflowTimeoutMillis", longestWorkflowTimeoutMillis, 1)
      _ <- millis("drainMillis", drainMillis, 0)
      _ <- millis("maxClockSkewMillis", maxClockSkewMillis, 0)
      // Every term is bounded above, so neither product nor sum overflows.
      _ <-
        if (inFlightTtlSeconds * 1000L > longestWorkflowTimeoutMillis + drainMillis) Right(())
        else
          Left(
            StoreError.Refused(
              "inFlightTtlSeconds: must exceed the longest workflow timeout plus drainMillis"
            )
          )
      _ <-
        if (inFlightRequirementMillis >= 0) Right(())
        else Left(StoreError.Refused("inFlightRequirementMillis: below 0"))
      // A comparison only (no arithmetic on the requirement), so any Long is safe.
      _ <-
        if (inFlightTtlSeconds * 1000L > inFlightRequirementMillis) Right(())
        else
          Left(
            StoreError.Refused(
              "inFlightTtlSeconds: must exceed the caller's in-flight requirement " +
                "(inFlightRequirementMillis)"
            )
          )
      _ <-
        if (maxClockSkewMillis < inFlightTtlSeconds * 1000L) Right(())
        else Left(StoreError.Refused("maxClockSkewMillis: must be below inFlightTtlSeconds"))
      _ <-
        if (jobTtlSeconds * 1000L > longestWorkflowTimeoutMillis) Right(())
        else Left(StoreError.Refused("jobTtlSeconds: must exceed the longest workflow timeout"))
      _ <-
        if (maxPayloadBytes >= 1 && maxPayloadBytes <= ServiceStateBounds.CeilingBytes) Right(())
        else
          Left(
            StoreError.Refused(s"maxPayloadBytes: outside 1..${ServiceStateBounds.CeilingBytes}")
          )
      _ <-
        if (maxConflictRetries >= 1 && maxConflictRetries <= 100) Right(())
        else Left(StoreError.Refused("maxConflictRetries: outside 1..100"))
      _ <-
        if (maxOwnedByQueryIds >= 1) Right(())
        else Left(StoreError.Refused("maxOwnedByQueryIds: below 1"))
    } yield new ServiceStateSettings(
      w,
      ownershipTtlSeconds,
      jobTtlSeconds,
      completedTtlSeconds,
      inFlightTtlSeconds,
      maxPayloadBytes,
      maxConflictRetries,
      maxOwnedByQueryIds,
      maxClockSkewMillis,
      inFlightRequirementMillis
    )
  }
}

/**
 * Random attempt IDs. SE-INT-02: the store no longer mints them; the CALLER mints one per logical
 * idempotent request (for example per HTTP request carrying an Idempotency-Key) and passes it to
 * every `begin` retry of that request ([[ServiceStateIds.attemptId]]).
 */
trait AttemptTokens {

  /** A fresh attempt ID `[A-Za-z0-9._-]{16,128}`. */
  def next(): String
}

object AttemptTokens {

  /** 128 random bits from `SecureRandom`, as 32 lowercase hex characters. */
  def secureRandom(): AttemptTokens = new AttemptTokens {
    private val random = new SecureRandom()
    def next(): String = {
      val b = new Array[Byte](16)
      random.nextBytes(b)
      b.map(x => f"${x & 0xff}%02x").mkString
    }
  }
}

private[state] object Rows {
  def sha(item: Item): Option[String] =
    item.get(Attrs.RecordSha256).collect { case AttrValue.S(v) => v }

  def payload(field: String, json: String, max: Int): Either[StoreError, String] =
    ServiceStateBounds.problem(field, json, max).toLeft(json).left.map(StoreError.Refused(_))

  /** SE-INT-02 (CR-INT-01-2): the encoded item, refused when it exceeds the item bound. */
  def encode[A](codec: ItemCodec[A], a: A): Either[StoreError, Item] =
    codec.encode(a).flatMap { item =>
      ServiceStateBounds
        .itemProblem(codec.table.role.tag, item)
        .map(StoreError.Refused(_))
        .toLeft(item)
    }

  /**
   * SE-INT-02 (CR-INT-01-4, -7): whether a row with `expiresAtEpochSeconds` is live at `nowMillis`
   * with `skewMillis` of allowance: `expiresAt * 1000 + skew > now`, saturating instead of
   * overflowing (a huge stored `expiresAt` is live, never negative).
   */
  def live(expiresAtEpochSeconds: Long, skewMillis: Long, nowMillis: Long): Boolean = {
    val ms =
      if (expiresAtEpochSeconds >= Long.MaxValue / 1000) Long.MaxValue
      else if (expiresAtEpochSeconds <= Long.MinValue / 1000) Long.MinValue
      else expiresAtEpochSeconds * 1000
    val withSkew =
      if (skewMillis > 0 && ms > Long.MaxValue - skewMillis) Long.MaxValue
      else ms + math.max(0L, skewMillis)
    withSkew > nowMillis
  }

  /** The epoch second at or after `nowMillis + ttlSeconds * 1000` (rounded up). */
  def expiryCeil(nowMillis: Long, ttlSeconds: Long): Long =
    Math.floorDiv(nowMillis, 1000L) + ttlSeconds + (if (Math.floorMod(nowMillis, 1000L) > 0) 1
                                                    else 0)
}

/**
 * SE-INT-01: the durable QueryOwnership (services port `QueryOwnership`): who submitted a query.
 *
 *   - `record`: create-once; the same client again is idempotent (`Right`, also on a retry after a
 *     lost response); another client is `Conflict` whose detail never names either client;
 *   - `owner`: the client, or None when absent or past its TTL plus `maxClockSkewMillis`;
 *   - `ownedBy`: the caller's live queries among at most `maxOwnedByQueryIds` distinct query IDs,
 *     read in ONE [[scholarchain.storage.port.KeyValueBatchReader.getMany]] call (which the adapter
 *     splits into BatchGetItem chunks); IDs that are not query IDs are not owned. Never `Full`.
 *
 * An expired row of the same client is not refreshed by `record` (query IDs are unique per query;
 * documented residual). Safe for concurrent use: no mutable state; all state is in the store.
 */
final class QueryOwnershipStore private[state] (
    kv: ServiceStateKeyValues,
    clock: Clock,
    s: ServiceStateSettings
) {
  private val codec = ServiceStateCodecs.QueryOwnerCodec
  private val table = TableRole.QueryOwnership

  /** The fixed detail of a foreign owner (names no client). */
  val OwnedByAnother = "QueryOwnership: the query is owned by another client"

  // SE-INT-03 (CR-INT-02-3): skew-aware, as for idempotency records.
  private def live(r: QueryOwnerRecord) =
    Rows.live(r.expiresAtEpochSeconds, s.maxClockSkewMillis, clock.nowMillis())

  private def stored(queryId: String): Either[StoreError, Option[QueryOwnerRecord]] =
    for {
      k <- TableKeys.QueryOwnership.owner(queryId)
      i <- kv.get(table, k)
      r <- i match {
        case None => Right(None)
        case Some(item) => codec.decode(item).map(Some(_))
      }
    } yield r

  def record(queryId: String, clientId: String): Either[StoreError, Unit] = {
    val now = clock.nowMillis()
    val rec = QueryOwnerRecord(
      QueryOwnerRecord.SchemaVersion,
      queryId,
      clientId,
      s.writer,
      now,
      Rows.expiryCeil(now, s.ownershipTtlSeconds) // SE-INT-03: rounded up
    )
    Rows.encode(codec, rec).flatMap { item =>
      kv.putIfAbsent(table, item) match {
        case Right(_) => Right(())
        case Left(StoreError.Conflict(_)) =>
          stored(queryId).flatMap {
            case Some(r) if r.clientId == clientId => Right(())
            case Some(_) => Left(StoreError.Conflict(OwnedByAnother))
            case None =>
              Left(StoreError.Transient("QueryOwnership: the owner row changed; retry"))
          }
        case Left(e) => Left(e)
      }
    }
  }

  def owner(queryId: String): Either[StoreError, Option[String]] =
    stored(queryId).map(_.filter(live).map(_.clientId))

  def ownedBy(clientId: String, queryIds: Vector[String]): Either[StoreError, Set[String]] =
    for {
      c <- ServiceStateIds.clientId(clientId)
      ids <- Option(queryIds).toRight(StoreError.Refused("queryIds: missing")).map(_.distinct)
      _ <-
        if (ids.size <= s.maxOwnedByQueryIds) Right(())
        else
          Left(
            StoreError.Refused(
              s"queryIds: ${ids.size} distinct IDs exceed the bound ${s.maxOwnedByQueryIds}"
            )
          )
      keys = ids.flatMap(q => TableKeys.QueryOwnership.owner(q).toOption)
      found <- if (keys.isEmpty) Right(Map.empty) else kv.getMany(table, keys)
      recs <- found.values.toVector.foldLeft[Either[StoreError, Vector[QueryOwnerRecord]]](
        Right(Vector.empty)
      )((acc, i) => acc.flatMap(v => codec.decode(i).map(v :+ _)))
    } yield recs.filter(r => r.clientId == c && live(r)).map(_.queryId).toSet
}

/**
 * SE-INT-01: durable asynchronous jobs (services `JobStore`), shared by QueryJobs replicas.
 *
 *   - `create`: create-once at version 1 (`queued`); a retry of the same creation is `Right`; a job
 *     ID already used for another job is `Conflict` (no client named);
 *   - `get`: only the creating client sees a live job (another client: None, as for a missing one);
 *     live means not past `expiresAt` plus `maxClockSkewMillis` (SE-INT-03), the same rule the
 *     transitions use to refuse an expired job;
 *   - `start`, `succeed`, `fail`: forward-only transitions ([[JobStates.Forward]]) by
 *     `putIfVersion` with at most `maxConflictRetries` re-reads after a lost race; repeating the
 *     transition that already happened (same state and payload) is `Right` with the stored row; a
 *     backward or sideways transition is `Conflict`; a missing or expired job is `Permanent`; a
 *     transition whose encoded row exceeds [[ServiceStateBounds.MaxItemBytes]] is `Refused` and
 *     nothing is written;
 *   - `remove`: deletes the job only while it is exactly the given row (for a job whose execution
 *     was refused right after creation); a changed row is never deleted.
 *
 * Capacity (429) and per-state counts are not store reads: routing item for service-engineer
 * (admission control and per-replica metrics). Safe for concurrent use.
 */
final class QueryJobStore private[state] (
    kv: ServiceStateKeyValues,
    clock: Clock,
    s: ServiceStateSettings
) {
  private val codec = ServiceStateCodecs.QueryJobCodec
  private val table = TableRole.QueryJob

  // SE-INT-03 (CR-INT-02-3): skew-aware, as for idempotency records.
  private def live(r: QueryJobRecord) =
    Rows.live(r.expiresAtEpochSeconds, s.maxClockSkewMillis, clock.nowMillis())

  private def stored(jobId: String): Either[StoreError, Option[QueryJobRecord]] =
    for {
      k <- TableKeys.QueryJob.job(jobId)
      i <- kv.get(table, k)
      r <- i match {
        case None => Right(None)
        case Some(item) => codec.decode(item).map(Some(_))
      }
    } yield r

  def create(
      jobId: String,
      queryId: String,
      clientId: String,
      stage: String
  ): Either[StoreError, QueryJobRecord] = {
    val now = clock.nowMillis()
    val rec = QueryJobRecord(
      QueryJobRecord.SchemaVersion,
      jobId,
      queryId,
      clientId,
      JobStates.Queued,
      stage,
      now,
      None,
      None,
      None,
      None,
      1L,
      s.writer,
      now,
      Rows.expiryCeil(now, s.jobTtlSeconds) // SE-INT-03: rounded up
    )
    Rows.encode(codec, rec).flatMap { item =>
      kv.putIfAbsent(table, item) match {
        case Right(_) => Right(rec)
        case Left(StoreError.Conflict(_)) =>
          stored(jobId).flatMap {
            case Some(r)
                if r.queryId == queryId && r.clientId == clientId && r.version == 1 &&
                  r.state == JobStates.Queued =>
              Right(r)
            case _ => Left(StoreError.Conflict("QueryJob: the job ID is already in use"))
          }
        case Left(e) => Left(e)
      }
    }
  }

  def get(clientId: String, jobId: String): Either[StoreError, Option[QueryJobRecord]] =
    stored(jobId).map(_.filter(r => r.clientId == clientId && live(r)))

  def start(jobId: String, stage: String): Either[StoreError, QueryJobRecord] =
    transition(jobId, JobStates.Running, _ => true) { (cur, now) =>
      cur.copy(
        state = JobStates.Running,
        stage = stage,
        startedAtMillis = Some(math.max(now, cur.createdAtMillis))
      )
    }

  def succeed(
      jobId: String,
      stage: String,
      resultJson: String
  ): Either[StoreError, QueryJobRecord] =
    Rows.payload("resultJson", resultJson, s.maxPayloadBytes).flatMap { json =>
      transition(jobId, JobStates.Succeeded, _.resultJson.contains(json)) { (cur, now) =>
        cur.copy(
          state = JobStates.Succeeded,
          stage = stage,
          finishedAtMillis = Some(math.max(now, cur.updatedAtMillis)),
          resultJson = Some(json)
        )
      }
    }

  def fail(jobId: String, stage: String, errorJson: String): Either[StoreError, QueryJobRecord] =
    Rows.payload("errorJson", errorJson, s.maxPayloadBytes).flatMap { json =>
      transition(jobId, JobStates.Failed, _.errorJson.contains(json)) { (cur, now) =>
        cur.copy(
          state = JobStates.Failed,
          stage = stage,
          finishedAtMillis = Some(math.max(now, cur.updatedAtMillis)),
          errorJson = Some(json)
        )
      }
    }

  /** Deletes the job only while it is exactly `job`: true when deleted, false otherwise. */
  def remove(job: QueryJobRecord): Either[StoreError, Boolean] =
    for {
      item <- codec.encode(job)
      k <- codec.key(job)
      sha <- Rows.sha(item).toRight(StoreError.Refused("QueryJob: row has no hash"))
      out <- kv.deleteIfRecord(table, k, sha) match {
        case Right(DeleteOutcome.Deleted) => Right(true)
        case Right(DeleteOutcome.Absent) | Left(StoreError.Conflict(_)) => Right(false)
        case Left(e) => Left(e)
      }
    } yield out

  private def transition(jobId: String, to: String, same: QueryJobRecord => Boolean)(
      build: (QueryJobRecord, Long) => QueryJobRecord
  ): Either[StoreError, QueryJobRecord] = {
    @tailrec
    def loop(retry: Int): Either[StoreError, QueryJobRecord] =
      stored(jobId) match {
        case Left(e) => Left(e)
        case Right(None) => Left(StoreError.Permanent(s"QueryJob $jobId: no such job"))
        case Right(Some(cur)) if !live(cur) =>
          Left(StoreError.Permanent(s"QueryJob $jobId: no such job (expired)"))
        case Right(Some(cur)) if cur.state == to && same(cur) => Right(cur)
        case Right(Some(cur)) if !JobStates.Forward.contains(cur.state -> to) =>
          Left(StoreError.Conflict(s"QueryJob $jobId: ${cur.state} to $to is not forward"))
        case Right(Some(cur)) =>
          val now = clock.nowMillis()
          val updated = math.max(now, cur.updatedAtMillis)
          val next = build(cur, now).copy(
            version = cur.version + 1,
            updatedAtMillis = updated,
            expiresAtEpochSeconds = Rows.expiryCeil(updated, s.jobTtlSeconds) // SE-INT-03
          )
          Rows.encode(codec, next).flatMap(kv.putIfVersion(table, _, cur.version)) match {
            case Right(()) => Right(next)
            case Left(StoreError.StaleVersion(_)) if retry < s.maxConflictRetries => loop(retry + 1)
            case Left(StoreError.StaleVersion(_)) =>
              Left(
                StoreError.StaleVersion(
                  s"QueryJob $jobId: lost ${retry + 1} concurrent update(s); not applied"
                )
              )
            case Left(e) => Left(e)
          }
      }
    loop(0)
  }
}

/** SE-INT-01: what an Idempotency-Key resolved to. */
enum IdempotencyStart {

  /**
   * No live record of another attempt: this logical request owns the key; finish with `complete` or
   * `abandon`. SE-INT-02: a retry of the same logical request (same attempt ID and request digest)
   * that finds its own live in-flight record gets `Fresh` again, with the ticket rebuilt from the
   * stored record.
   */
  case Fresh(ticket: IdempotencyTicket)

  /** A completed record of the same request: replay its opaque outcome. */
  case Replay(outcomeJson: String)

  /** A live in-flight record of the same request, written under ANOTHER attempt ID. */
  case InProgress

  /** A live record of another request under the same key. */
  case Mismatch
}

/** The in-flight record a `Fresh` begin wrote: `complete` and `abandon` act only on it. */
final class IdempotencyTicket private[state] (
    val keySha256: String,
    private[state] val record: IdempotencyRecord,
    private[state] val recordSha256: String
) {
  override def toString: String = s"IdempotencyTicket(version ${record.version})"
}

/**
 * SE-INT-01: durable idempotency (services `IdempotencyStore`), shared by QueryJobs replicas.
 * Records are keyed by [[ServiceStateIds.idempotencyKeySha256]]; the raw key is never stored and
 * never appears in an error detail.
 *
 *   - `begin(clientId, key, requestSha256, attemptId)`: writes an in-flight record (create-once)
 *     carrying the CALLER's attempt ID ([[ServiceStateIds.attemptId]]). An existing record past its
 *     TTL plus `maxClockSkewMillis` (`expiresAt`, checked on read because DynamoDB TTL deletion is
 *     delayed; as remembered, up to days; UNVERIFIED) is replaced by `putIfVersion`, so a crashed
 *     replica's in-flight record frees its key after `inFlightTtlSeconds` (see the DESIGN DECISION
 *     on [[ServiceStateSettings]]). A live record answers `Mismatch` (other request digest),
 *     `Fresh` (in flight under the SAME attempt ID: the caller's own earlier attempt, whose
 *     response was lost), `InProgress` (in flight under another attempt ID) or `Replay`. Never
 *     `Full`;
 *   - `complete`: replaces exactly the ticket's in-flight record with the completed outcome (opaque
 *     JSON, at most `maxPayloadBytes`, encoded row at most [[ServiceStateBounds.MaxItemBytes]]) by
 *     `putIfVersion`. Idempotent: when the stored record already is this attempt's completion with
 *     the same outcome (an earlier `complete` applied but its response was lost), `Right`; with
 *     another outcome, `Conflict`; if the record was replaced or removed meanwhile (the request
 *     outlived its in-flight bound), `StaleVersion` and nothing is written;
 *   - `abandon`: deletes exactly the ticket's in-flight record (true), or nothing (false; also when
 *     an earlier `abandon` of the same ticket applied but its response was lost).
 *
 * AMBIGUOUS WRITES (CR-INT-01-1). Every write is conditional, so a caller may retry any `Transient`
 * (or `StaleVersion` from `begin`) with the SAME arguments, including the same attempt ID:
 *   - `begin` applied, response lost: the retry finds its own live in-flight record and gets
 *     `Fresh` with the same record; any other attempt ID still gets `InProgress`, so no independent
 *     caller becomes a second holder;
 *   - `complete` applied, response lost: the retry is `Right` and writes nothing.
 * One attempt ID is ONE logical request: the caller must run at most one execution per attempt ID
 * at a time and must not use a ticket returned to an attempt whose result it discarded (for example
 * after its own attempt timeout) in parallel with the retry's ticket. Two holders of one attempt ID
 * are indistinguishable to the store.
 */
final class IdempotencyKeyStore private[state] (
    kv: ServiceStateKeyValues,
    clock: Clock,
    s: ServiceStateSettings
) {
  private val codec = ServiceStateCodecs.IdempotencyCodec
  private val table = TableRole.IdempotencyKey

  private def live(r: IdempotencyRecord) =
    Rows.live(r.expiresAtEpochSeconds, s.maxClockSkewMillis, clock.nowMillis())

  private def decoded(item: Item) = codec.decode(item)

  def begin(
      clientId: String,
      key: String,
      requestSha256: String,
      attemptId: String
  ): Either[StoreError, IdempotencyStart] =
    for {
      digest <- ServiceStateIds.idempotencyKeySha256(clientId, key)
      req <- StoreIds.hex64(requestSha256, "requestSha256")
      attempt <- ServiceStateIds.attemptId(attemptId)
      k <- TableKeys.IdempotencyKey.request(digest)
      out <- {
        def inFlight(version: Long): IdempotencyRecord = {
          val now = clock.nowMillis()
          IdempotencyRecord(
            IdempotencyRecord.SchemaVersion,
            digest,
            req,
            IdempotencyStates.InFlight,
            attempt,
            None,
            now,
            None,
            version,
            s.writer,
            Rows.expiryCeil(now, s.inFlightTtlSeconds)
          )
        }
        def ticket(r: IdempotencyRecord, item: Item) =
          Rows
            .sha(item)
            .toRight(StoreError.Refused("IdempotencyKey: row has no hash"))
            .map(h => IdempotencyStart.Fresh(new IdempotencyTicket(digest, r, h)))

        /** The answer for a stored record; None to try again. */
        def resolve(storedItem: Item): Either[StoreError, Option[IdempotencyStart]] =
          decoded(storedItem).flatMap { stored =>
            if (!live(stored)) {
              val replacement = inFlight(stored.version + 1)
              Rows.encode(codec, replacement).flatMap { ri =>
                kv.putIfVersion(table, ri, stored.version) match {
                  case Right(()) => ticket(replacement, ri).map(Some(_))
                  // Lost a race, or our own replacement applied and the SDK's retry then failed
                  // its condition: the next round reads the record again and resolves it.
                  case Left(StoreError.StaleVersion(_)) => Right(None)
                  case Left(e) => Left(e)
                }
              }
            } else if (stored.requestSha256 != req)
              Right(Some(IdempotencyStart.Mismatch))
            else if (stored.state == IdempotencyStates.InFlight) {
              // CR-INT-01-1: our own earlier attempt's record (its response was lost): take it
              // over with the stored record, so `complete` and `abandon` act on exactly it.
              if (stored.attemptId == attempt) ticket(stored, storedItem).map(Some(_))
              else Right(Some(IdempotencyStart.InProgress))
            } else
              stored.outcomeJson
                .toRight(StoreError.Corrupt("IdempotencyKey: completed without outcome"))
                .map(o => Some(IdempotencyStart.Replay(o)))
          }

        @tailrec
        def loop(retry: Int): Either[StoreError, IdempotencyStart] = {
          val fresh = inFlight(math.max(1L, clock.nowMillis()))
          val step: Either[StoreError, Option[IdempotencyStart]] =
            Rows.encode(codec, fresh).flatMap { item =>
              kv.putIfAbsent(table, item) match {
                case Right(_) => ticket(fresh, item).map(Some(_))
                case Left(StoreError.Conflict(_)) =>
                  kv.get(table, k).flatMap {
                    case None => Right(None) // removed meanwhile: try again
                    case Some(storedItem) => resolve(storedItem)
                  }
                case Left(e) => Left(e)
              }
            }
          step match {
            case Left(e) => Left(e)
            case Right(Some(r)) => Right(r)
            case Right(None) if retry < s.maxConflictRetries => loop(retry + 1)
            case Right(None) =>
              Left(
                StoreError.StaleVersion(
                  s"IdempotencyKey: the record changed ${retry + 1} time(s) during begin"
                )
              )
          }
        }
        loop(0)
      }
    } yield out

  private val Replaced =
    "IdempotencyKey: the in-flight record was replaced or removed (the request outlived its " +
      "in-flight bound and may have run again); nothing written"

  private val OtherOutcome =
    "IdempotencyKey: this attempt already completed the record with another outcome; nothing " +
      "written"

  /** Whether `r` is the completion of exactly the ticket's in-flight record. */
  private def completionOf(t: IdempotencyTicket, r: IdempotencyRecord): Boolean =
    r.state == IdempotencyStates.Completed &&
      r.keySha256 == t.keySha256 &&
      r.attemptId == t.record.attemptId &&
      r.requestSha256 == t.record.requestSha256 &&
      r.createdAtMillis == t.record.createdAtMillis &&
      r.version == t.record.version + 1

  /** The answer when the stored row is not the ticket's in-flight record. */
  private def settled(
      t: IdempotencyTicket,
      json: String,
      current: Option[Item]
  ): Either[StoreError, Unit] =
    current match {
      case None => Left(StoreError.StaleVersion(Replaced))
      case Some(item) =>
        decoded(item).flatMap { r =>
          if (!completionOf(t, r)) Left(StoreError.StaleVersion(Replaced))
          else if (r.outcomeJson.contains(json)) Right(()) // an earlier complete applied
          else Left(StoreError.Conflict(OtherOutcome))
        }
    }

  def complete(ticket: IdempotencyTicket, outcomeJson: String): Either[StoreError, Unit] =
    for {
      t <- Option(ticket).toRight(StoreError.Refused("IdempotencyKey: ticket missing"))
      json <- Rows.payload("outcomeJson", outcomeJson, s.maxPayloadBytes)
      k <- TableKeys.IdempotencyKey.request(t.keySha256)
      now = clock.nowMillis()
      done = t.record.copy(
        state = IdempotencyStates.Completed,
        outcomeJson = Some(json),
        completedAtMillis = Some(math.max(now, t.record.createdAtMillis)),
        version = t.record.version + 1,
        expiresAtEpochSeconds = Rows.expiryCeil(now, s.completedTtlSeconds) // SE-INT-03
      )
      item <- Rows.encode(codec, done) // refused before any read or write when too large
      current <- kv.get(table, k)
      out <-
        if (current.flatMap(Rows.sha).contains(t.recordSha256))
          kv.putIfVersion(table, item, t.record.version) match {
            case Right(()) => Right(())
            // Lost a race, or our own write applied and the SDK's retry failed its condition:
            // re-read and decide from the stored row.
            case Left(StoreError.StaleVersion(_)) => kv.get(table, k).flatMap(settled(t, json, _))
            case Left(e) => Left(e)
          }
        else settled(t, json, current)
    } yield out

  def abandon(ticket: IdempotencyTicket): Either[StoreError, Boolean] =
    for {
      t <- Option(ticket).toRight(StoreError.Refused("IdempotencyKey: ticket missing"))
      k <- TableKeys.IdempotencyKey.request(t.keySha256)
      out <- kv.deleteIfRecord(table, k, t.recordSha256) match {
        case Right(DeleteOutcome.Deleted) => Right(true)
        case Right(DeleteOutcome.Absent) | Left(StoreError.Conflict(_)) => Right(false)
        case Left(e) => Left(e)
      }
    } yield out
}

/**
 * SE-INT-01: the service-state stores, composed separately from
 * [[scholarchain.storage.port.RuntimeStores]] (whose key-value reader refuses these tables,
 * SE-INT-02), so the rag workflow, which receives only RuntimeStores, cannot reach them. Each store
 * reads and writes only its own table ([[ServiceStateStores.Tables]]).
 */
final class ServiceStateStores private (
    val ownership: QueryOwnershipStore,
    val jobs: QueryJobStore,
    val idempotency: IdempotencyKeyStore
)

object ServiceStateStores {
  val Tables: Set[TableRole] = TableRole.values.filter(_.serviceState).toSet

  /**
   * @param kv
   *   the DynamoDB adapter in production, the in-memory store locally
   */
  def apply(
      kv: ServiceStateKeyValues,
      clock: Clock,
      settings: ServiceStateSettings
  ): ServiceStateStores = {
    require(kv != null && clock != null && settings != null, "all required")
    new ServiceStateStores(
      new QueryOwnershipStore(kv, clock, settings),
      new QueryJobStore(kv, clock, settings),
      new IdempotencyKeyStore(kv, clock, settings)
    )
  }
}
