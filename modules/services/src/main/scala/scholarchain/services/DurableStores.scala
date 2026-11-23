package scholarchain.services

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

import scala.util.control.NonFatal

import scholarchain.storage.model.{QueryJobRecord, StoreError}
import scholarchain.storage.port.{Clock, ServiceStateKeyValues}
import scholarchain.storage.state.{
  IdempotencyKeyStore,
  IdempotencyStart,
  IdempotencyTicket,
  QueryJobStore,
  QueryOwnershipStore,
  ServiceStateSettings,
  ServiceStateStores
}

/**
 * SV-INT-02: the services values the durable stores keep as opaque JSON (job results, job errors,
 * replayed idempotent outcomes). Encoding is total; decoding is strict (exact members, known error
 * codes, statuses 100..599) and a stored value that does not decode is `Corrupt` (the read policy's
 * `502`), never a guess. No secret is ever part of these values: they are the responses the
 * services already return.
 */
private[services] object StateCodecs {
  private val Corrupt = StoreError.Corrupt("a stored services value failed to decode")

  private def pairs(xs: Vector[(String, ujson.Value)]): ujson.Arr =
    ujson.Arr.from(xs.map { case (k, v) => ujson.Arr(ujson.Str(k), v) })

  def reply(r: Reply): ujson.Obj =
    ujson.Obj(
      "status" -> r.status,
      "body" -> r.body,
      "headers" -> pairs(r.headers.map { case (k, v) => k -> ujson.Str(v) })
    )

  def error(e: ApiError): ujson.Obj =
    ujson.Obj(
      "code" -> e.code.tag,
      "message" -> e.message,
      "extra" -> pairs(e.extra),
      "headers" -> pairs(e.headers.map { case (k, v) => k -> ujson.Str(v) })
    )

  def outcome(o: IdempotentOutcome): String =
    (o match {
      case IdempotentOutcome.Final(Right(r)) => ujson.Obj("kind" -> "reply", "reply" -> reply(r))
      case IdempotentOutcome.Final(Left(e)) => ujson.Obj("kind" -> "error", "error" -> error(e))
      case IdempotentOutcome.JobRef(jobId) => ujson.Obj("kind" -> "job", "jobId" -> jobId)
    }).render()

  private def parse(s: String): Option[ujson.Value] =
    try Some(ujson.read(s))
    catch { case NonFatal(_) => None }

  private def obj(v: ujson.Value, keys: Set[String]): Option[ujson.Obj] = v match {
    case o: ujson.Obj if o.value.keySet.toSet == keys => Some(o)
    case _ => None
  }

  private def readPairs(v: ujson.Value): Option[Vector[(String, ujson.Value)]] = v match {
    case a: ujson.Arr =>
      a.value.toVector.foldLeft(Option(Vector.empty[(String, ujson.Value)])) { (acc, x) =>
        acc.flatMap(done =>
          x match {
            case p: ujson.Arr if p.value.size == 2 =>
              p.value(0) match {
                case ujson.Str(k) => Some(done :+ (k -> p.value(1)))
                case _ => None
              }
            case _ => None
          }
        )
      }
    case _ => None
  }

  private def readHeaders(v: ujson.Value): Option[Vector[(String, String)]] =
    readPairs(v).flatMap(_.foldLeft(Option(Vector.empty[(String, String)])) {
      case (acc, (k, ujson.Str(s))) => acc.map(_ :+ (k -> s))
      case _ => None
    })

  def readReply(v: ujson.Value): Option[Reply] =
    for {
      o <- obj(v, Set("status", "body", "headers"))
      status <- o("status") match {
        case ujson.Num(n) if n.isWhole && n >= 100 && n <= 599 => Some(n.toInt)
        case _ => None
      }
      headers <- readHeaders(o("headers"))
    } yield Reply(status, o("body"), headers)

  def readError(v: ujson.Value): Option[ApiError] =
    for {
      o <- obj(v, Set("code", "message", "extra", "headers"))
      code <- o("code").strOpt.flatMap(t => ErrorCode.values.find(_.tag == t))
      message <- o("message").strOpt
      extra <- readPairs(o("extra"))
      headers <- readHeaders(o("headers"))
    } yield ApiError(code, message, extra, headers)

  def readOutcome(s: String): Either[StoreError, IdempotentOutcome] =
    parse(s)
      .flatMap {
        case o: ujson.Obj =>
          o.value.get("kind").flatMap(_.strOpt) match {
            case Some("reply") =>
              obj(o, Set("kind", "reply"))
                .flatMap(x => readReply(x("reply")))
                .map(r => IdempotentOutcome.Final(Right(r)))
            case Some("error") =>
              obj(o, Set("kind", "error"))
                .flatMap(x => readError(x("error")))
                .map(e => IdempotentOutcome.Final(Left(e)))
            case Some("job") =>
              obj(o, Set("kind", "jobId"))
                .flatMap(_("jobId").strOpt)
                .map(IdempotentOutcome.JobRef(_))
            case _ => None
          }
        case _ => None
      }
      .toRight(Corrupt)

  def readReplyJson(s: String): Either[StoreError, Reply] =
    parse(s).flatMap(readReply).toRight(Corrupt)

  def readErrorJson(s: String): Either[StoreError, ApiError] =
    parse(s).flatMap(readError).toRight(Corrupt)

  /** The services view of a durable job row. */
  def job(r: QueryJobRecord): Either[StoreError, Job] =
    for {
      state <- JobState.values.find(_.tag == r.state).toRight(Corrupt)
      result <- r.resultJson.fold[Either[StoreError, Option[Reply]]](Right(None))(s =>
        readReplyJson(s).map(Some(_))
      )
      error <- r.errorJson.fold[Either[StoreError, Option[ApiError]]](Right(None))(s =>
        readErrorJson(s).map(Some(_))
      )
    } yield Job(
      r.jobId,
      r.queryId,
      r.clientId,
      state,
      r.stage,
      r.createdAtMillis,
      r.startedAtMillis,
      r.finishedAtMillis,
      error,
      result
    )
}

/** SV-INT-02: the services [[QueryOwnership]] port over storage's durable `QueryOwnershipStore`. */
final class DurableQueryOwnership private[services] (store: QueryOwnershipStore)
    extends QueryOwnership {
  def record(queryId: String, clientId: String): Either[StoreError, Unit] =
    store.record(queryId, clientId)
  def owner(queryId: String): Either[StoreError, Option[String]] = store.owner(queryId)
  def ownedBy(clientId: String, queryIds: Vector[String]): Either[StoreError, Set[String]] =
    store.ownedBy(clientId, queryIds)
}

/**
 * SV-INT-02: the services [[JobStore]] port over storage's durable `QueryJobStore` (shared by
 * QueryJobs replicas). Another client's job is `None` (storage filters by client), so it is the
 * same `404` as a missing job and never names its owner. The store has no capacity: admission is
 * [[JobAdmission]].
 *
 * `counts` are PER REPLICA: the queued and running jobs this replica created or started and has not
 * yet finished, and the succeeded and failed jobs it finished since it started. Every operation is
 * idempotent under retries of the same call, and so is the bookkeeping (keyed by job ID; finished
 * jobs only increment counters, so the map holds at most the replica's unfinished jobs).
 */
final class DurableJobStore private[services] (store: QueryJobStore) extends JobStore {
  private val live = new ConcurrentHashMap[String, String]()
  private val succeeded = new AtomicLong(0)
  private val failed = new AtomicLong(0)

  def create(clientId: String, jobId: String, queryId: String): Either[StoreError, Job] =
    store.create(jobId, queryId, clientId, Job.StageQueued).flatMap(StateCodecs.job).map { j =>
      live.putIfAbsent(jobId, JobState.Queued.tag)
      j
    }

  def get(clientId: String, jobId: String): Either[StoreError, Option[Job]] =
    store.get(clientId, jobId).flatMap {
      case None => Right(None)
      case Some(r) => StateCodecs.job(r).map(Some(_))
    }

  def start(jobId: String): Either[StoreError, Unit] =
    store.start(jobId, Job.StageWorkflow).map { _ =>
      live.replace(jobId, JobState.Running.tag)
      ()
    }

  private def finished(jobId: String, counter: AtomicLong): Unit =
    if (live.remove(jobId) != null) counter.incrementAndGet()

  def succeed(jobId: String, reply: Reply): Either[StoreError, Unit] =
    store
      .succeed(jobId, Job.StageCompleted, StateCodecs.reply(reply).render())
      .map(_ => finished(jobId, succeeded))

  def fail(jobId: String, error: ApiError): Either[StoreError, Unit] =
    store
      .fail(jobId, Job.StageFailed, StateCodecs.error(error).render())
      .map(_ => finished(jobId, failed))

  /**
   * Deletes the job only while it is still the row `create` wrote (queued, version 1): one read of
   * the caller's own row, then storage's exact-record delete, which deletes nothing if the row
   * changed meanwhile.
   */
  def remove(job: Job): Either[StoreError, Unit] =
    store.get(job.clientId, job.jobId).flatMap {
      case Some(r) if r.state == JobState.Queued.tag && r.version == 1L =>
        store.remove(r).map { _ =>
          live.remove(job.jobId); ()
        }
      case _ => Right(())
    }

  private def int(n: Long): Int = math.min(n, Int.MaxValue.toLong).toInt

  def counts: Map[String, Int] = {
    val states = live.values().toArray.toVector
    Map(
      JobState.Queued.tag -> states.count(_ == JobState.Queued.tag),
      JobState.Running.tag -> states.count(_ == JobState.Running.tag),
      JobState.Succeeded.tag -> int(succeeded.get),
      JobState.Failed.tag -> int(failed.get)
    )
  }
}

/**
 * SV-INT-02: the services [[IdempotencyStore]] port over storage's durable `IdempotencyKeyStore`.
 * The hold wraps storage's ticket; the outcome is kept as [[StateCodecs.outcome]] JSON. Never
 * `Full` (the durable store has no capacity). A stored outcome that does not decode is `Corrupt`.
 */
final class DurableIdempotencyStore private[services] (store: IdempotencyKeyStore)
    extends IdempotencyStore {
  private final class Hold(val ticket: IdempotencyTicket) extends IdempotencyHold {
    override def toString: String = s"DurableHold($ticket)"
  }

  private val NotDurable = StoreError.Refused("idempotency: the hold is not a durable hold")

  def begin(
      clientId: String,
      key: String,
      digest: String,
      attemptId: String
  ): Either[StoreError, IdempotencyBegin] =
    store.begin(clientId, key, digest, attemptId).flatMap {
      case IdempotencyStart.Fresh(t) => Right(IdempotencyBegin.Fresh(new Hold(t)))
      case IdempotencyStart.Replay(json) =>
        StateCodecs.readOutcome(json).map(IdempotencyBegin.Replay(_))
      case IdempotencyStart.InProgress => Right(IdempotencyBegin.InProgress)
      case IdempotencyStart.Mismatch => Right(IdempotencyBegin.Mismatch)
    }

  def complete(hold: IdempotencyHold, outcome: IdempotentOutcome): Either[StoreError, Unit] =
    hold match {
      case h: Hold => store.complete(h.ticket, StateCodecs.outcome(outcome))
      case _ => Left(NotDurable)
    }

  def abandon(hold: IdempotencyHold): Either[StoreError, Unit] =
    hold match {
      case h: Hold => store.abandon(h.ticket).map(_ => ())
      case _ => Left(NotDurable)
    }
}

/**
 * SV-INT-02: the durable service state of one process: storage's three stores over ONE key-value
 * adapter (production: the DynamoDB adapter), wrapped as the services ports.
 */
final class DurableServiceState private (
    val ownership: QueryOwnership,
    val ports: ServiceStatePorts,
    val settings: ServiceStateSettings
)

object DurableServiceState {

  /** The service clock as storage's wall clock (one clock for both). */
  def storageClock(clock: ServiceClock): Clock = new Clock {
    def nowMillis(): Long = clock.nowMillis()
  }

  def apply(
      kv: ServiceStateKeyValues,
      clock: ServiceClock,
      settings: ServiceStateSettings
  ): Either[String, DurableServiceState] =
    if (kv == null || clock == null || settings == null)
      Left("durable service state: the key-value store, clock and settings are required")
    else {
      val stores = ServiceStateStores(kv, storageClock(clock), settings)
      Right(
        new DurableServiceState(
          new DurableQueryOwnership(stores.ownership),
          ServiceStatePorts(
            new DurableJobStore(stores.jobs),
            new DurableIdempotencyStore(stores.idempotency)
          ),
          settings
        )
      )
    }
}

/**
 * SV-INT-02, CR-INT-02 (A1-W, open part): the WORST CASE of ONE attempt of each composite
 * service-state operation on the DynamoDB adapter, as SDK calls times `apiCallTimeoutMillis`
 * (saturating). The call counts are those core documents for A1-W (DE-INT-03, from the storage call
 * paths; R = `maxConflictRetries`): ownership `record` 3, idempotency `begin` 4·(R+1), `complete`
 * 3, `abandon` 2, job `create` 3, `start`/`succeed`/`fail` 2·(R+1), `remove` 2 plus the services
 * read before it (1 GetItem): 3.
 *
 * Services bound every operation as a whole: each attempt's CALLER waits at most
 * `min(readTimeoutMillis, time left)`, a timed-out attempt is retried only after it ended, and the
 * whole operation returns within `readRequestTimeoutMillis` ([[ReadPolicy.writeTracked]]). What is
 * NOT bounded by configuration is the work of the LAST attempt after its caller gave up: it can run
 * up to its worst case below (at most one such attempt per operation, holding one key-value
 * thread). [[exceeding]] lists the operations whose worst case exceeds `readTimeoutMillis`: for
 * those the per-attempt bound is not validated, and the gap is reported, not hidden.
 */
final case class CompositeAttemptBudgets(
    apiCallTimeoutMillis: Long,
    maxConflictRetries: Int,
    sdkCalls: Map[String, Long]
) {
  private def mul(a: Long, b: Long): Long =
    try Math.multiplyExact(a, b)
    catch { case _: ArithmeticException => Long.MaxValue }

  /** Worst-case milliseconds of one attempt, per operation. */
  def millis: Map[String, Long] = sdkCalls.map { case (op, n) =>
    op -> mul(n, apiCallTimeoutMillis)
  }

  /** Operations whose one-attempt worst case exceeds `readTimeoutMillis`, sorted. */
  def exceeding(readTimeoutMillis: Long): Vector[String] =
    millis.collect { case (op, ms) if ms > readTimeoutMillis => op }.toVector.sorted
}

object CompositeAttemptBudgets {
  def of(apiCallTimeoutMillis: Long, maxConflictRetries: Int): CompositeAttemptBudgets = {
    val rounds = math.max(0L, maxConflictRetries.toLong) + 1L
    CompositeAttemptBudgets(
      apiCallTimeoutMillis,
      maxConflictRetries,
      Map(
        "ownership.record" -> 3L,
        "idempotency.begin" -> 4L * rounds,
        "idempotency.complete" -> 3L,
        "idempotency.abandon" -> 2L,
        "job.create" -> 3L,
        "job.start" -> 2L * rounds,
        "job.succeed" -> 2L * rounds,
        "job.fail" -> 2L * rounds,
        "job.remove" -> 3L
      )
    )
  }
}
