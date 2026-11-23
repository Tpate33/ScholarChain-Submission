package scholarchain.services

import scala.collection.mutable

import scholarchain.storage.model.StoreError

/** States of an asynchronous query job. */
enum JobState(val tag: String) {
  case Queued extends JobState("queued")
  case Running extends JobState("running")
  case Succeeded extends JobState("succeeded")
  case Failed extends JobState("failed")

  def finished: Boolean = this == Succeeded || this == Failed
}

/** One asynchronous query job (in-memory; owned by the client that created it). */
final case class Job(
    jobId: String,
    queryId: String,
    clientId: String,
    state: JobState,
    stage: String,
    createdAtMillis: Long,
    startedAtMillis: Option[Long],
    finishedAtMillis: Option[Long],
    error: Option[ApiError],
    result: Option[Reply]
)

object Job {
  val StageQueued = "queued"
  val StageWorkflow = "graph-rag-workflow"
  val StageCompleted = "completed"
  val StageFailed = "failed"
}

/**
 * SV-INT-02: the job store port. Implemented by the bounded in-memory [[InMemoryJobStore]] (local,
 * one replica) and by [[DurableJobStore]] over storage's durable `QueryJobStore` (shared by
 * QueryJobs replicas). Every call has an error channel, and the query service makes EVERY call as
 * one operation of the key-value write policy ([[ReadPolicy.writeTracked]]: per-attempt timeout,
 * serial retries, the `readRequestTimeoutMillis` cap; A1-W).
 *
 * A job is visible only to its own client: `get` of another client's job is `None`, exactly as for
 * a missing job, so another client sees `404` and never the job or its owner. Admission control
 * (`429` at `maxJobs` unfinished jobs) is NOT a store property: it is [[JobAdmission]], per
 * replica, in front of `create`. An implementation never refuses for capacity. MUST be safe for
 * concurrent use.
 */
trait JobStore {
  def create(clientId: String, jobId: String, queryId: String): Either[StoreError, Job]
  def get(clientId: String, jobId: String): Either[StoreError, Option[Job]]
  def start(jobId: String): Either[StoreError, Unit]
  def succeed(jobId: String, reply: Reply): Either[StoreError, Unit]
  def fail(jobId: String, error: ApiError): Either[StoreError, Unit]

  /** Deletes `job` only while it is still exactly as created (its execution was refused). */
  def remove(job: Job): Either[StoreError, Unit]

  /** Jobs per state tag as this replica knows them (metrics; see each implementation). */
  def counts: Map[String, Int]

  /**
   * True for a store inside this process that never blocks (the in-memory store): its calls run
   * directly, as before SV-INT-02, never through the key-value pool and breaker. A store that
   * leaves the process keeps the default (false) and runs under the write policy (A1-W).
   */
  def inProcess: Boolean = false
}

/**
 * SV-INT-02: admission control of asynchronous jobs, per replica (services, never the store): at
 * most `maxJobs` unfinished jobs ADMITTED by this replica at a time. A job holds its slot from
 * admission until its final write was attempted (or its creation or execution was refused); one
 * more is `429 too-many-requests` with `Retry-After`. The durable store has no capacity, so with
 * durable jobs this is the only job bound, and it is per replica (a fleet admits at most
 * `replicas x maxJobs`).
 */
final class JobAdmission(maxJobs: Int, retryAfterSeconds: Int) {
  require(maxJobs >= 1, "maxJobs must be >= 1")
  private val held = new java.util.concurrent.atomic.AtomicInteger(0)

  /** Takes one slot, or the `429`. */
  def admit(): Either[ApiError, Unit] = {
    @annotation.tailrec
    def loop(): Boolean = {
      val n = held.get
      if (n >= maxJobs) false else if (held.compareAndSet(n, n + 1)) true else loop()
    }
    if (loop()) Right(())
    else
      Left(
        ApiError(
          ErrorCode.TooManyRequests,
          "the asynchronous job capacity is exhausted",
          headers = ApiError.retryAfter(retryAfterSeconds)
        )
      )
  }

  /** Returns one slot (exactly once per successful [[admit]]). */
  def release(): Unit = { held.updateAndGet(n => math.max(0, n - 1)); () }

  /** Slots in use (tests and metrics). */
  def inUse: Int = held.get
}

/**
 * The bounded in-memory job store (local, one replica). At most `maxJobs` jobs are kept: creating
 * one more evicts the oldest FINISHED job. [[JobAdmission]] (same `maxJobs`) admits at most
 * `maxJobs` unfinished jobs, so a finished job is always there to evict; should none be (a job
 * whose final write was skipped), creation is `Refused`, never an eviction of an unfinished job. It
 * never fails otherwise. `counts` are the retained jobs per state.
 */
final class InMemoryJobStore(maxJobs: Int, clock: ServiceClock) extends JobStore {
  require(maxJobs >= 1, "maxJobs must be >= 1")
  private val jobs = mutable.LinkedHashMap.empty[String, Job]

  def create(clientId: String, jobId: String, queryId: String): Either[StoreError, Job] =
    synchronized {
      if (jobs.size >= maxJobs)
        jobs.collectFirst { case (id, j) if j.state.finished => id }.foreach(jobs.remove)
      if (jobs.size >= maxJobs)
        Left(StoreError.Refused("jobs: every retained job is unfinished"))
      else {
        val j = Job(
          jobId,
          queryId,
          clientId,
          JobState.Queued,
          Job.StageQueued,
          clock.nowMillis(),
          None,
          None,
          None,
          None
        )
        jobs(jobId) = j
        Right(j)
      }
    }

  def get(clientId: String, jobId: String): Either[StoreError, Option[Job]] =
    synchronized(Right(jobs.get(jobId).filter(_.clientId == clientId)))

  def remove(job: Job): Either[StoreError, Unit] = synchronized {
    if (jobs.get(job.jobId).contains(job)) jobs.remove(job.jobId)
    Right(())
  }

  private def update(jobId: String)(f: Job => Job): Either[StoreError, Unit] =
    synchronized(Right(jobs.get(jobId).foreach(j => jobs(jobId) = f(j))))

  def start(jobId: String): Either[StoreError, Unit] =
    update(jobId)(
      _.copy(
        state = JobState.Running,
        stage = Job.StageWorkflow,
        startedAtMillis = Some(clock.nowMillis())
      )
    )

  def succeed(jobId: String, reply: Reply): Either[StoreError, Unit] =
    update(jobId)(
      _.copy(
        state = JobState.Succeeded,
        stage = Job.StageCompleted,
        finishedAtMillis = Some(clock.nowMillis()),
        result = Some(reply)
      )
    )

  def fail(jobId: String, error: ApiError): Either[StoreError, Unit] =
    update(jobId)(
      _.copy(
        state = JobState.Failed,
        stage = Job.StageFailed,
        finishedAtMillis = Some(clock.nowMillis()),
        error = Some(error)
      )
    )

  def counts: Map[String, Int] = synchronized {
    JobState.values.map(s => s.tag -> jobs.values.count(_.state == s)).toMap
  }

  override def inProcess: Boolean = true
}

/**
 * SE-P10-03 N1: who submitted a query. GET /v1/explain/{queryId} is served only to that client, and
 * (SE-P10-04 m1) GET /v1/evidence/{id} lists only the occurrences of that client's queries. The
 * port lets a separate EvidenceExplain process check ownership recorded by the QueryJobs process:
 * in production a shared durable store backs it (deferred: storage-engineer adapter, infra-engineer
 * table); locally the processes share one [[InMemoryQueryOwnership]].
 *
 * SE-P10-04 m2: every call has an error channel and is made by the services through the key-value
 * read policy (own pool, per-attempt timeout, request deadline, bounded retries of transient
 * errors, the `key-value` circuit breaker). Recording is an idempotent put (same query, same
 * client), so a retry is safe.
 *
 * SE-P10-05 F4: an implementation MUST be safe for concurrent use. The services call it outside
 * [[StoreAccess]] (the store lock of the local in-memory runtime stores), so that recording one
 * query's owner never waits for another query's workflow. A durable adapter (storage-engineer) may
 * serialize internally if it needs to. SE-P10-05 U-b: the owner is recorded only once the workflow
 * call is admitted (circuit allowed, pool thread claimed), never for a refused submission.
 */
trait QueryOwnership {
  def record(queryId: String, clientId: String): Either[StoreError, Unit]
  def owner(queryId: String): Either[StoreError, Option[String]]

  /** The subset of `queryIds` that `clientId` owns (one bounded call for a whole list). */
  def ownedBy(clientId: String, queryIds: Vector[String]): Either[StoreError, Set[String]]
}

/**
 * The bounded in-memory ownership record: at most `capacity` queries, the oldest forgotten first. A
 * forgotten query's explanation is then 404 for every client, and its evidence occurrences are no
 * longer listed (fail closed). It never fails.
 */
final class InMemoryQueryOwnership(capacity: Int) extends QueryOwnership {
  require(capacity >= 1, "capacity must be >= 1")
  private val owners = mutable.LinkedHashMap.empty[String, String]

  def record(queryId: String, clientId: String): Either[StoreError, Unit] = synchronized {
    owners.remove(queryId)
    owners(queryId) = clientId
    while (owners.size > capacity) owners.remove(owners.head._1)
    Right(())
  }

  def owner(queryId: String): Either[StoreError, Option[String]] =
    synchronized(Right(owners.get(queryId)))

  def ownedBy(clientId: String, queryIds: Vector[String]): Either[StoreError, Set[String]] =
    synchronized(Right(queryIds.filter(q => owners.get(q).contains(clientId)).toSet))
}

/** What an idempotency key resolved to. */
enum IdempotentOutcome {

  /** A synchronous result or a non-retryable error, replayed as is. */
  case Final(result: Either[ApiError, Reply])

  /** An accepted asynchronous job, replayed with its current state. */
  case JobRef(jobId: String)
}

/**
 * SV-INT-02: what a `Fresh` begin hands to its caller: `complete` and `abandon` act only on the
 * in-flight record it names (opaque; never shown to a client).
 */
trait IdempotencyHold

/** The answer of [[IdempotencyStore.begin]]. */
enum IdempotencyBegin {

  /**
   * This logical request owns the key: run it, then `complete` or `abandon` with `hold`. A retry of
   * the same logical request (same attempt ID and request digest) that finds its own in-flight
   * record gets `Fresh` again with a hold of that same record.
   */
  case Fresh(hold: IdempotencyHold)
  case Replay(outcome: IdempotentOutcome)

  /** In flight under ANOTHER attempt ID (an independent request). */
  case InProgress
  case Mismatch

  /** In-memory capacity only; the durable store never answers it. */
  case Full
}

/**
 * SV-INT-02: the idempotency store port. Implemented by [[InMemoryIdempotencyStore]] (local) and by
 * [[DurableIdempotencyStore]] over storage's `IdempotencyKeyStore` (shared by replicas). The query
 * service mints ONE attempt ID per logical request (`AttemptTokens.secureRandom`) and passes it to
 * every retry of that request's `begin`; independent requests use independent IDs. Every call runs
 * as one operation of the key-value write policy (A1-W). MUST be safe for concurrent use.
 */
trait IdempotencyStore {
  def begin(
      clientId: String,
      key: String,
      digest: String,
      attemptId: String
  ): Either[StoreError, IdempotencyBegin]

  /** Records the outcome of `hold`'s request; repeating it with the same outcome is `Right`. */
  def complete(hold: IdempotencyHold, outcome: IdempotentOutcome): Either[StoreError, Unit]

  /** Forgets `hold`'s in-flight record (nothing when it is no longer that record). */
  def abandon(hold: IdempotencyHold): Either[StoreError, Unit]

  /** As [[JobStore.inProcess]]: true only for the non-blocking in-memory store. */
  def inProcess: Boolean = false
}

/**
 * The bounded in-memory idempotency store. A record is keyed by (client ID, Idempotency-Key) and
 * binds the request digest: the same key with the same request replays the recorded outcome, the
 * same key with another request is a conflict. Records expire after `ttlMillis`; at capacity the
 * oldest completed record is evicted, and when every record is in flight a new key is refused
 * (`Full`). SV-INT-02: an in-flight record carries its attempt ID; the same attempt finds its own
 * record `Fresh`, any other attempt `InProgress`.
 */
final class InMemoryIdempotencyStore(capacity: Int, ttlMillis: Long, clock: ServiceClock)
    extends IdempotencyStore {
  private final case class Entry(
      digest: String,
      createdAt: Long,
      outcome: Option[IdempotentOutcome],
      attemptId: String
  )
  private final case class Hold(clientId: String, key: String, attemptId: String)
      extends IdempotencyHold
  private val entries = mutable.LinkedHashMap.empty[(String, String), Entry]

  /**
   * SE-P10-02 m3: only COMPLETED records expire, `ttlMillis` after completion. An in-flight record
   * stays until its request completes or is abandoned, so a repeat of a still-running request is
   * always `InProgress`, never a second execution, however long the first one runs.
   */
  private def expire(): Unit = {
    val now = clock.nowMillis()
    entries
      .filter { case (_, e) => e.outcome.isDefined && now - e.createdAt >= ttlMillis }
      .keys
      .toVector
      .foreach(entries.remove)
  }

  def begin(
      clientId: String,
      key: String,
      digest: String,
      attemptId: String
  ): Either[StoreError, IdempotencyBegin] = synchronized {
    expire()
    Right(entries.get((clientId, key)) match {
      case Some(e) if e.digest != digest => IdempotencyBegin.Mismatch
      case Some(Entry(_, _, None, a)) if a == attemptId =>
        IdempotencyBegin.Fresh(Hold(clientId, key, attemptId))
      case Some(Entry(_, _, None, _)) => IdempotencyBegin.InProgress
      case Some(Entry(_, _, Some(o), _)) => IdempotencyBegin.Replay(o)
      case None =>
        if (entries.size >= capacity)
          entries.collectFirst { case (k, e) if e.outcome.isDefined => k }.foreach(entries.remove)
        if (entries.size >= capacity) IdempotencyBegin.Full
        else {
          entries((clientId, key)) = Entry(digest, clock.nowMillis(), None, attemptId)
          IdempotencyBegin.Fresh(Hold(clientId, key, attemptId))
        }
    })
  }

  private def inFlightOf(hold: IdempotencyHold): Option[((String, String), Entry)] =
    hold match {
      case h: Hold =>
        entries
          .get((h.clientId, h.key))
          .filter(e => e.outcome.isEmpty && e.attemptId == h.attemptId)
          .map((h.clientId, h.key) -> _)
      case _ => None
    }

  def complete(hold: IdempotencyHold, outcome: IdempotentOutcome): Either[StoreError, Unit] =
    synchronized {
      inFlightOf(hold).foreach { case (k, e) =>
        entries(k) = e.copy(createdAt = clock.nowMillis(), outcome = Some(outcome))
      }
      Right(())
    }

  /**
   * Forgets an in-flight key whose outcome may differ on a retry (5xx, 429). SE-P10-03 N4: the
   * query service calls this only once the request's workflow call has actually ENDED (a timed-out
   * call that ignores interruption keeps its key in flight until it returns), so a retry under the
   * key never runs a second workflow concurrently with the first.
   */
  def abandon(hold: IdempotencyHold): Either[StoreError, Unit] = synchronized {
    inFlightOf(hold).foreach { case (k, _) => entries.remove(k) }
    Right(())
  }

  def size: Int = synchronized(entries.size)

  override def inProcess: Boolean = true
}

/**
 * SV-INT-02: the job and idempotency stores of one QueryJobs process. Production passes the durable
 * pair ([[DurableServiceState]]); without one, [[ServiceRuntime.build]] composes the in-memory pair
 * (local, one replica).
 */
final case class ServiceStatePorts(jobs: JobStore, idempotency: IdempotencyStore)
