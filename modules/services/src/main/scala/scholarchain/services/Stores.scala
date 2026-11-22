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
 * The bounded in-memory job store. At most `maxJobs` jobs are kept: creating one more evicts the
 * oldest FINISHED job; when every kept job is unfinished the creation is refused (`429`). Jobs are
 * visible only to their own client (another client sees `404`, never the job).
 */
final class JobStore(maxJobs: Int, clock: ServiceClock, retryAfterSeconds: Int) {
  private val jobs = mutable.LinkedHashMap.empty[String, Job]

  def create(clientId: String, jobId: String, queryId: String): Either[ApiError, Job] =
    synchronized {
      if (jobs.size >= maxJobs)
        jobs.collectFirst { case (id, j) if j.state.finished => id }.foreach(jobs.remove)
      if (jobs.size >= maxJobs)
        Left(
          ApiError(
            ErrorCode.TooManyRequests,
            "the asynchronous job capacity is exhausted",
            headers = ApiError.retryAfter(retryAfterSeconds)
          )
        )
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

  def get(clientId: String, jobId: String): Option[Job] =
    synchronized(jobs.get(jobId).filter(_.clientId == clientId))

  def remove(jobId: String): Unit = synchronized { jobs.remove(jobId); () }

  private def update(jobId: String)(f: Job => Job): Unit =
    synchronized(jobs.get(jobId).foreach(j => jobs(jobId) = f(j)))

  def start(jobId: String): Unit =
    update(jobId)(
      _.copy(
        state = JobState.Running,
        stage = Job.StageWorkflow,
        startedAtMillis = Some(clock.nowMillis())
      )
    )

  def succeed(jobId: String, reply: Reply): Unit =
    update(jobId)(
      _.copy(
        state = JobState.Succeeded,
        stage = Job.StageCompleted,
        finishedAtMillis = Some(clock.nowMillis()),
        result = Some(reply)
      )
    )

  def fail(jobId: String, error: ApiError): Unit =
    update(jobId)(
      _.copy(
        state = JobState.Failed,
        stage = Job.StageFailed,
        finishedAtMillis = Some(clock.nowMillis()),
        error = Some(error)
      )
    )

  /** Jobs per state tag (metrics). */
  def counts: Map[String, Int] = synchronized {
    JobState.values.map(s => s.tag -> jobs.values.count(_.state == s)).toMap
  }
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

/** The answer of [[IdempotencyStore.begin]]. */
enum IdempotencyBegin {
  case Fresh
  case Replay(outcome: IdempotentOutcome)
  case InProgress
  case Mismatch
  case Full
}

/**
 * The bounded in-memory idempotency store. A record is keyed by (client ID, Idempotency-Key) and
 * binds the request digest: the same key with the same request replays the recorded outcome, the
 * same key with another request is a conflict. Records expire after `ttlMillis`; at capacity the
 * oldest completed record is evicted, and when every record is in flight a new key is refused.
 */
final class IdempotencyStore(capacity: Int, ttlMillis: Long, clock: ServiceClock) {
  private final case class Entry(
      digest: String,
      createdAt: Long,
      outcome: Option[IdempotentOutcome]
  )
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

  def begin(clientId: String, key: String, digest: String): IdempotencyBegin = synchronized {
    expire()
    entries.get((clientId, key)) match {
      case Some(e) if e.digest != digest => IdempotencyBegin.Mismatch
      case Some(Entry(_, _, None)) => IdempotencyBegin.InProgress
      case Some(Entry(_, _, Some(o))) => IdempotencyBegin.Replay(o)
      case None =>
        if (entries.size >= capacity)
          entries.collectFirst { case (k, e) if e.outcome.isDefined => k }.foreach(entries.remove)
        if (entries.size >= capacity) IdempotencyBegin.Full
        else {
          entries((clientId, key)) = Entry(digest, clock.nowMillis(), None)
          IdempotencyBegin.Fresh
        }
    }
  }

  def complete(clientId: String, key: String, outcome: IdempotentOutcome): Unit = synchronized {
    entries
      .get((clientId, key))
      .foreach(e =>
        entries((clientId, key)) = e.copy(createdAt = clock.nowMillis(), outcome = Some(outcome))
      )
  }

  /**
   * Forgets an in-flight key whose outcome may differ on a retry (5xx, 429). SE-P10-03 N4: the
   * query service calls this only once the request's workflow call has actually ENDED (a timed-out
   * call that ignores interruption keeps its key in flight until it returns), so a retry under the
   * key never runs a second workflow concurrently with the first.
   */
  def abandon(clientId: String, key: String): Unit = synchronized {
    entries.remove((clientId, key))
    ()
  }

  def size: Int = synchronized(entries.size)
}
