package scholarchain.services

import java.util.concurrent.ThreadPoolExecutor

import scholarchain.storage.model.{
  BoundQuery,
  GraphRow,
  IndexInfo,
  Item,
  ObjectRef,
  PrimaryKey,
  PutOutcome,
  StoreError,
  TableRole,
  VectorFilter,
  VectorMatch,
  VectorRecord
}
import scholarchain.storage.model.Bytes
import scholarchain.storage.port.{
  GraphReader,
  IndexInspector,
  KeyQuery,
  KeyValueReader,
  KeyValueWriter,
  ObjectMeta,
  ObjectReader,
  VectorReader
}

/**
 * SV-INT-03 (G4; DE-INT-04 timing assumption, CR-INT-09 unverified concern): every storage
 * operation the query-jobs workflow (rag `CollaborationWorkflow`) makes through its `RuntimeStores`
 * runs as ONE read of the query-jobs process's read policy, exactly as the graph, evidence and
 * explain services' reads do ([[ReadPolicy]]):
 *
 *   - its own bounded pool `workflow-stores` (daemon threads; `workflowWorkers + jobWorkers`
 *     threads, the most workflow calls that can run at once, and as many queue slots), waiting at
 *     most `queueWaitMillis` for a thread;
 *   - at most `readMaxAttempts` attempts, each bounded by `min(readTimeoutMillis, time left)`, all
 *     of them (queue waits included) within `readRequestTimeoutMillis` of the call;
 *   - only `Transient` errors and timed-out attempts are retried, immediately, while time is left;
 *     a write (`putIfAbsent`, `putIfVersion`) is never retried (a timed-out conditional write may
 *     still land; rag's create-once writers then see `AlreadyPresent` or `Conflict`);
 *   - one consecutive-failure breaker per store (`workflow-s3`, `workflow-dynamodb`,
 *     `workflow-neptune`, `workflow-s3vectors`; `breakerFailureThreshold`, `breakerOpenMillis`).
 *     `Transient`, `Permanent` and timed-out attempts count as failures; any other answer is a
 *     success; `Refused` and calls that never started are neutral.
 *
 * Unlike [[ReadPolicy]], the result stays a [[StoreError]] (rag's port type): the store's own error
 * passes through unchanged, and a bound reached is `Transient` (timeout, no capacity, open circuit)
 * or `Permanent` (interrupted, an exception), with a detail naming the store and the bound only,
 * never a value.
 *
 * So the T-rules of core (`T-S3`, `T-NEP`, `T-S3V` against `readTimeoutMillis`) bound the SDK calls
 * of every adapter operation the workflow makes. These breakers are not part of `/readyz` (the
 * workflow's own readiness checks are `workflow-workers` and `job-workers`). Residual (N4, as for
 * every read policy): a timed-out call that ignores interruption keeps its thread.
 */
final class WorkflowStoreBounds private (
    pool: ThreadPoolExecutor,
    config: ServiceConfig,
    clock: ServiceClock
) extends AutoCloseable {

  private val calls = new Calls(pool, config.queueWaitMillis)

  private def breaker(name: String) =
    new CircuitBreaker(
      s"workflow-$name",
      config.breakerFailureThreshold,
      config.breakerOpenMillis,
      clock
    )

  private val s3 = breaker("s3")
  private val dynamoDb = breaker("dynamodb")
  private val neptune = breaker("neptune")
  private val s3Vectors = breaker("s3vectors")

  /** The breakers, for tests and diagnostics. */
  def breakers: Vector[CircuitBreaker] = Vector(s3, dynamoDb, neptune, s3Vectors)

  def isClosed: Boolean = pool.isShutdown

  /** Interrupts the pool's threads; later calls are `Transient` (no capacity). */
  def close(): Unit = { pool.shutdownNow(); () }

  private def transient(store: String, why: String) =
    Left(StoreError.Transient(s"workflow $store read: $why"))

  /** One store operation under the policy described on the class. */
  private[services] def bounded[A](store: String, b: CircuitBreaker, maxAttempts: Int)(
      op: => Either[StoreError, A]
  ): Either[StoreError, A] = {
    val deadline = Deadline(clock.monotonicNanos() + config.readRequestTimeoutMillis * 1000000L)
    if (!b.allow()) transient(store, "the circuit is open")
    else attempt(store, b, maxAttempts, deadline, 1, op)
  }

  @annotation.tailrec
  private def attempt[A](
      store: String,
      b: CircuitBreaker,
      maxAttempts: Int,
      deadline: Deadline,
      n: Int,
      op: => Either[StoreError, A]
  ): Either[StoreError, A] = {
    val remaining = deadline.remainingMillis(clock)
    val budget = math.min(config.readTimeoutMillis, remaining)
    val truncated = budget < config.readTimeoutMillis
    if (budget <= 0) {
      b.neutral()
      transient(store, s"no time left within readRequestTimeoutMillis (${n - 1} attempt(s))")
    } else {
      def again = n < maxAttempts && deadline.remainingMillis(clock) > 0
      calls.run(budget, remaining)(op) match {
        case Right(Right(a)) =>
          b.success()
          Right(a)
        case Right(Left(e)) if e.retryable && again =>
          attempt(store, b, maxAttempts, deadline, n + 1, op)
        case Right(Left(e: StoreError.Refused)) =>
          b.neutral()
          Left(e)
        case Right(Left(e @ (_: StoreError.Transient | _: StoreError.Permanent))) =>
          b.failure()
          Left(e)
        case Right(Left(e)) =>
          b.success() // the store answered (missing, conflict, stale, corrupt)
          Left(e)
        case Left(CallFailure.TimedOut) if again =>
          attempt(store, b, maxAttempts, deadline, n + 1, op)
        case Left(CallFailure.TimedOut) =>
          if (truncated) b.neutral() else b.failure()
          transient(store, s"no answer within readTimeoutMillis ($n attempt(s))")
        case Left(f) if f.neverStarted =>
          b.notStarted()
          transient(store, "no capacity in the workflow-stores pool")
        case Left(CallFailure.Interrupted) =>
          b.neutral()
          Left(StoreError.Permanent(s"workflow $store read: interrupted"))
        case Left(CallFailure.Threw(cls)) =>
          b.failure()
          Left(StoreError.Permanent(s"workflow $store read: failed ($cls)"))
        case Left(_) =>
          b.failure()
          Left(StoreError.Permanent(s"workflow $store read: failed"))
      }
    }
  }

  private def read[A](store: String, b: CircuitBreaker)(op: => Either[StoreError, A]) =
    bounded(store, b, config.readMaxAttempts)(op)

  private def write[A](store: String, b: CircuitBreaker)(op: => Either[StoreError, A]) =
    bounded(store, b, 1)(op)

  def objects(r: ObjectReader): ObjectReader = new ObjectReader {
    def head(ref: ObjectRef): Either[StoreError, Option[ObjectMeta]] = read("S3", s3)(r.head(ref))
    def get(ref: ObjectRef): Either[StoreError, Option[(ObjectMeta, Bytes)]] =
      read("S3", s3)(r.get(ref))
    def list(bucket: String, prefix: String, maxKeys: Int): Either[StoreError, Vector[ObjectMeta]] =
      read("S3", s3)(r.list(bucket, prefix, maxKeys))
  }

  def keyValues(r: KeyValueReader): KeyValueReader = new KeyValueReader {
    def get(table: TableRole, key: PrimaryKey): Either[StoreError, Option[Item]] =
      read("DynamoDB", dynamoDb)(r.get(table, key))
    def query(table: TableRole, q: KeyQuery): Either[StoreError, Vector[Item]] =
      read("DynamoDB", dynamoDb)(r.query(table, q))
  }

  def writer(w: KeyValueWriter): KeyValueWriter = new KeyValueWriter {
    def putIfAbsent(table: TableRole, item: Item): Either[StoreError, PutOutcome] =
      write("DynamoDB", dynamoDb)(w.putIfAbsent(table, item))
    def putIfVersion(
        table: TableRole,
        item: Item,
        expectedVersion: Long
    ): Either[StoreError, Unit] =
      write("DynamoDB", dynamoDb)(w.putIfVersion(table, item, expectedVersion))
  }

  def graph(g: GraphReader): GraphReader = new GraphReader {
    def run(query: BoundQuery): Either[StoreError, Vector[GraphRow]] =
      read("Neptune", neptune)(g.run(query))
  }

  def vectors(v: VectorReader): VectorReader = new VectorReader {
    def query(
        index: String,
        vector: Vector[Float],
        topK: Int,
        filter: Option[VectorFilter]
    ): Either[StoreError, Vector[VectorMatch]] =
      read("S3 Vectors", s3Vectors)(v.query(index, vector, topK, filter))
    def getVectors(index: String, keys: Vector[String]): Either[StoreError, Vector[VectorRecord]] =
      read("S3 Vectors", s3Vectors)(v.getVectors(index, keys))
  }

  def indexes(i: IndexInspector): IndexInspector = new IndexInspector {
    def describe(index: String): Either[StoreError, Option[IndexInfo]] =
      read("S3 Vectors", s3Vectors)(i.describe(index))
  }
}

object WorkflowStoreBounds {

  /** The bounds of one query-jobs process (see the class doc); `config` must be valid. */
  def apply(config: ServiceConfig, clock: ServiceClock): WorkflowStoreBounds = {
    val threads = math.max(1, config.workflowWorkers + config.jobWorkers)
    new WorkflowStoreBounds(Pools.bounded("workflow-stores", threads, threads), config, clock)
  }
}
