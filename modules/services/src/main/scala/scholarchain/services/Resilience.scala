package scholarchain.services

import java.util.concurrent.{
  ArrayBlockingQueue,
  Callable,
  CountDownLatch,
  ExecutionException,
  RejectedExecutionException,
  RejectedExecutionHandler,
  ThreadFactory,
  ThreadPoolExecutor,
  TimeUnit,
  TimeoutException
}
import java.util.concurrent.atomic.AtomicInteger

import scholarchain.storage.model.StoreError

/** How one bounded call ended without a value. */
enum CallFailure {

  /** The call ran past its run-time bound (measured from the moment it started). */
  case TimedOut

  /** The pool's queue was full: the call was never queued. */
  case Rejected

  /** The call waited in the queue past the queue-wait bound and was withdrawn unstarted. */
  case NotStarted
  case Interrupted
  case Threw(exceptionClass: String)

  /** True when the dependency was never reached (no breaker failure, never retried). */
  def neverStarted: Boolean = this == Rejected || this == NotStarted
}

/** Bounded thread pools (fixed size, bounded queue, daemon threads). */
object Pools {
  def bounded(
      name: String,
      threads: Int,
      queue: Int,
      whenFull: RejectedExecutionHandler = new ThreadPoolExecutor.AbortPolicy()
  ): ThreadPoolExecutor = {
    val n = new AtomicInteger(0)
    val factory: ThreadFactory = (r: Runnable) => {
      val t = new Thread(r, s"scholarchain-$name-${n.incrementAndGet()}")
      t.setDaemon(true)
      t
    }
    new ThreadPoolExecutor(
      threads,
      threads,
      0L,
      TimeUnit.MILLISECONDS,
      new ArrayBlockingQueue[Runnable](queue),
      factory,
      whenFull
    )
  }
}

/** Test seams of [[Calls]]: run on the worker just before and just after it claims a call. */
trait CallsProbe {
  def beforeClaim(): Unit
  def afterClaim(): Unit
}

object CallsProbe {
  val none: CallsProbe = new CallsProbe {
    def beforeClaim(): Unit = ()
    def afterClaim(): Unit = ()
  }
}

/**
 * Runs calls of ONE dependency class on its own bounded pool. Two bounds apply (SE-P10-02 M2): the
 * queue wait (a call not started by then is withdrawn and is `NotStarted`) and the run time
 * (measured from the moment the call started; past it the call is cancelled and is `TimedOut`). A
 * full queue is `Rejected`.
 *
 * SE-P10-03:
 *   - N3: a call is CLAIMED exactly once, by an atomic compare-and-set, either by the worker (it
 *     runs) or by the waiting caller (it is withdrawn, `NotStarted`, and the worker skips it). A
 *     call is never both run and reported unstarted.
 *   - X2: `totalMillis` bounds queue wait plus run time together (the read request deadline), so
 *     time spent waiting for a thread is charged to the request.
 *   - N4: cancellation interrupts a timed-out call, but code that ignores interruption (or waits
 *     for the store lock) keeps running and keeps its thread. [[runTracked]] returns, besides the
 *     outcome, `settled`, completed only when the call has actually ended (or was never run), so
 *     callers that must not start a duplicate (idempotency) wait for it.
 */
final class Calls(
    executor: ThreadPoolExecutor,
    queueWaitMillis: Long,
    probe: CallsProbe = CallsProbe.none
) {
  def isShutdown: Boolean = executor.isShutdown

  private val overdue = new java.util.concurrent.atomic.AtomicInteger(0)

  /**
   * SE-P10-04 m3: calls still running after their caller timed out. Such a call keeps its thread
   * until it ends (cancellation only interrupts); a call that ignores interruption forever pins it
   * forever. At most `threads` calls can be overdue.
   */
  def overdueCount: Int = overdue.get

  /** The pool's thread count. */
  def threads: Int = executor.getMaximumPoolSize

  /** True while at least one thread is not pinned by an overdue call (readiness). */
  def hasLiveThread: Boolean = !executor.isShutdown && overdue.get < threads

  def run[A](timeoutMillis: Long, totalMillis: Long = Long.MaxValue)(
      f: => A
  ): Either[CallFailure, A] = runTracked(timeoutMillis, totalMillis)(f)._1

  def runTracked[A](timeoutMillis: Long, totalMillis: Long = Long.MaxValue)(
      f: => A
  ): (Either[CallFailure, A], java.util.concurrent.CompletableFuture[Unit]) = {
    val t0 = System.nanoTime()
    val claimed = new java.util.concurrent.atomic.AtomicBoolean(false)
    val started = new CountDownLatch(1)
    val settled = new java.util.concurrent.CompletableFuture[Unit]()
    def elapsedMillis = (System.nanoTime() - t0) / 1000000L
    try {
      val future = executor.submit(new Callable[Option[A]] {
        def call(): Option[A] = {
          probe.beforeClaim()
          if (!claimed.compareAndSet(false, true)) None // withdrawn by the caller
          else {
            probe.afterClaim() // the race window: claimed, not yet signalled as started
            started.countDown()
            try Some(f)
            finally { settled.complete(()); () }
          }
        }
      })
      try {
        val wait = math.min(queueWaitMillis, totalMillis)
        if (!started.await(wait, TimeUnit.MILLISECONDS) && claimed.compareAndSet(false, true)) {
          future.cancel(false)
          executor.purge()
          settled.complete(())
          (Left(CallFailure.NotStarted), settled)
        } else {
          val budget = math.max(0L, math.min(timeoutMillis, totalMillis - elapsedMillis))
          future.get(budget, TimeUnit.MILLISECONDS) match {
            case Some(a) => (Right(a), settled)
            case None => (Left(CallFailure.NotStarted), settled) // unreachable: caller claimed
          }
        }
      } catch {
        case _: TimeoutException =>
          future.cancel(true)
          if (!settled.isDone) {
            overdue.incrementAndGet()
            settled.whenComplete((_, _) => { overdue.decrementAndGet(); () })
          }
          (Left(CallFailure.TimedOut), settled)
        case e: ExecutionException =>
          (Left(CallFailure.Threw(Option(e.getCause).getOrElse(e).getClass.getName)), settled)
        case _: InterruptedException =>
          future.cancel(true)
          Thread.currentThread().interrupt()
          (Left(CallFailure.Interrupted), settled)
        case _: java.util.concurrent.CancellationException =>
          (Left(CallFailure.Interrupted), settled)
      }
    } catch {
      case _: RejectedExecutionException =>
        settled.complete(())
        (Left(CallFailure.Rejected), settled)
    }
  }
}

/**
 * A consecutive-failure circuit breaker. Closed: calls pass; `threshold` consecutive failures open
 * it. Open: calls are refused for `openMillis`; then ONE trial call passes (half-open), whose
 * success closes the circuit and whose failure opens it again. A neutral outcome (the call never
 * reached the dependency) releases the trial without changing the state.
 */
final class CircuitBreaker(
    val name: String,
    threshold: Int,
    openMillis: Long,
    clock: ServiceClock
) {
  private var failures = 0
  private var openedAt: Option[Long] = None
  private var trial = false

  def allow(): Boolean = synchronized {
    openedAt match {
      case None => true
      case Some(t) if clock.nowMillis() - t >= openMillis && !trial =>
        trial = true
        true
      case Some(_) => false
    }
  }

  def success(): Unit = synchronized {
    failures = 0
    openedAt = None
    trial = false
    unprovenAt = None
  }

  def failure(): Unit = synchronized {
    unprovenAt = None
    if (openedAt.isDefined) {
      openedAt = Some(clock.nowMillis())
      trial = false
    } else {
      failures += 1
      if (failures >= threshold) openedAt = Some(clock.nowMillis())
    }
  }

  def neutral(): Unit = synchronized { trial = false }

  /** When a half-open trial was last spent unproven (service clock, wall milliseconds). */
  private var unprovenAt: Option[Long] = None

  /**
   * SE-P10-04 m3: the call never reached the dependency (full queue, queue-wait bound). Like
   * [[neutral]] it releases a half-open trial without changing the state; it also records that the
   * trial was spent unproven, so readiness does not report a half-open circuit as healthy while its
   * trials cannot even start. A later success or failure clears it, and so does time (below).
   *
   * SE-P10-05 F3: the mark also EXPIRES one `openMillis` period after it was set, without any
   * traffic. A ready-only load balancer sends no request to a not-ready instance, so a mark that
   * only a real outcome could clear would hold readiness at 503 forever after one transient
   * overload. After the period the circuit is an ordinary half-open circuit again: ready, and its
   * next call is the trial. Pinned workers are reported separately (the `*-workers` checks).
   *
   * Residual (CR-P10-06 U-1, recorded, not fixed): the breaker does not track WHICH call holds the
   * trial. With `queueWaitMillis > breakerOpenMillis`, a call admitted earlier can report
   * `notStarted()` (or `neutral()`, as before) after a newer half-open trial began, and release
   * that concurrent trial, so two trials may run at once.
   */
  def notStarted(): Unit = synchronized {
    if (trial) unprovenAt = Some(clock.nowMillis())
    trial = false
  }

  private def unprovenNow: Boolean =
    unprovenAt.exists(t => clock.nowMillis() - t < openMillis)

  /** `closed`, `open` or `half-open`. */
  def state: String = synchronized {
    openedAt match {
      case None => "closed"
      case Some(t) if clock.nowMillis() - t >= openMillis => "half-open"
      case Some(_) => "open"
    }
  }

  /**
   * Readiness: closed, or half-open with no trial spent unproven within the last `openMillis`
   * (SE-P10-05 F3: the mark expires without traffic).
   */
  def ready: Boolean = synchronized {
    state match {
      case "closed" => true
      case "half-open" => !unprovenNow
      case _ => false
    }
  }
}

/** The overall deadline of one request (monotonic nanoseconds of the service clock). */
final case class Deadline(atNanos: Long) {
  def remainingMillis(clock: ServiceClock): Long = (atNanos - clock.monotonicNanos()) / 1000000L
}

/**
 * The policy of idempotent downstream reads of one dependency (evidence, graph, explain): circuit
 * check, then at most `maxAttempts` attempts, each under `min(timeoutMillis, time left before the
 * request deadline)` and inside [[StoreAccess]]. Only [[StoreError.Transient]] and timeouts of a
 * call that STARTED are retried, immediately (no sleep) and only while the request deadline leaves
 * time. A call that never started (full queue, queue-wait bound) is `503 overloaded`: it is neither
 * a breaker failure nor retried into the same queue. Store errors map to typed API errors; their
 * details are never returned.
 */
final class ReadPolicy(
    dependency: String,
    calls: Calls,
    breaker: CircuitBreaker,
    access: StoreAccess,
    clock: ServiceClock,
    timeoutMillis: Long,
    maxAttempts: Int,
    retryAfterSeconds: Int
) {
  private def timedOut(n: Int) =
    Left(
      ApiError(
        ErrorCode.Timeout,
        s"the $dependency store did not answer within the request bound ($n attempt(s))"
      )
    )

  /**
   * `capMillis` (SE-P10-06 m-A): an additional REAL-TIME bound on the whole read (all attempts,
   * queue waits included), measured from this call with `System.nanoTime`, whatever the service
   * clock. The ownership record uses it to finish before the workflow timer it runs under.
   */
  def read[A](deadline: Deadline, capMillis: Long = Long.MaxValue)(
      op: => Either[StoreError, A]
  ): Either[ApiError, A] = {
    val capAt =
      if (capMillis == Long.MaxValue) Long.MaxValue
      else System.nanoTime() + capMillis * 1000000L
    if (left(deadline, capAt) <= 0) timedOut(0)
    else if (!breaker.allow())
      Left(
        ApiError(
          ErrorCode.CircuitOpen,
          s"the $dependency circuit is open",
          headers = ApiError.retryAfter(retryAfterSeconds)
        )
      )
    else attempt(1, deadline, capAt, op)
  }

  /** Milliseconds left before the request deadline and the real-time cap, whichever is first. */
  private def left(deadline: Deadline, capAt: Long): Long =
    if (capAt == Long.MaxValue) deadline.remainingMillis(clock)
    else math.min(deadline.remainingMillis(clock), (capAt - System.nanoTime()) / 1000000L)

  private def downstream(message: String): ApiError =
    ApiError(ErrorCode.DownstreamFailure, message)

  @annotation.tailrec
  private def attempt[A](
      n: Int,
      deadline: Deadline,
      capAt: Long,
      op: => Either[StoreError, A]
  ): Either[ApiError, A] = {
    val remaining = left(deadline, capAt)
    val budget = math.min(timeoutMillis, remaining)
    // X1: an attempt cut short by the request deadline says nothing about the store's health.
    val truncated = budget < timeoutMillis
    if (budget <= 0) {
      breaker.neutral()
      timedOut(n - 1)
    } else {
      def again = n < maxAttempts && left(deadline, capAt) > 0
      // X2: queue wait and run time together are bounded by the time left before the deadline.
      calls.run(budget, remaining)(access(op)) match {
        case Right(Right(a)) =>
          breaker.success()
          Right(a)
        case Right(Left(e)) if e.retryable && again => attempt(n + 1, deadline, capAt, op)
        case Left(CallFailure.TimedOut) if again => attempt(n + 1, deadline, capAt, op)
        case Right(Left(_: StoreError.Refused)) =>
          breaker.neutral()
          Left(ApiError(ErrorCode.Internal, s"the $dependency request was refused by its port"))
        case Right(Left(_)) =>
          breaker.failure()
          Left(downstream(s"the $dependency store failed after $n attempt(s)"))
        case Left(CallFailure.TimedOut) =>
          if (truncated) breaker.neutral() else breaker.failure()
          timedOut(n)
        case Left(f) if f.neverStarted =>
          breaker.notStarted()
          Left(
            ApiError(
              ErrorCode.Overloaded,
              s"no capacity for the $dependency call",
              headers = ApiError.retryAfter(retryAfterSeconds)
            )
          )
        case Left(CallFailure.Interrupted) =>
          breaker.neutral()
          Left(ApiError(ErrorCode.Internal, "the request was interrupted"))
        case Left(_) =>
          breaker.failure()
          Left(downstream(s"the $dependency store failed after $n attempt(s)"))
      }
    }
  }
}
