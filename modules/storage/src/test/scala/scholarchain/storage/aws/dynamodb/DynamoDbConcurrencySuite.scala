package scholarchain.storage.aws.dynamodb

import java.util.concurrent.{Callable, CountDownLatch, Executors, TimeUnit}

import software.amazon.awssdk.core.exception.SdkClientException

import scholarchain.storage.fake.ManualClock
import scholarchain.storage.model.{PrimaryKey, PutOutcome, StoreError, TableRole}
import scholarchain.storage.state.{AttemptTokens, IdempotencyStart, ServiceStateStores, StateWorld}

/**
 * SE-INT-01 item 1: one adapter instance shared by many threads (no lock, no shared mutable state
 * in the adapter or the stores; the stub serializes each request as a server would).
 */
class DynamoDbConcurrencySuite extends munit.FunSuite {
  import DynamoDbAdapterSuite.{h, row}

  private val Threads = 16

  /** Runs `n` tasks released together; their results in task order. */
  private def race[A](n: Int)(task: Int => A): Vector[A] = {
    val pool = Executors.newFixedThreadPool(n)
    val gate = new CountDownLatch(1)
    try {
      val futures = (0 until n).map { i =>
        pool.submit(new Callable[A] { def call(): A = { gate.await(); task(i) } })
      }
      gate.countDown()
      futures.map(_.get(30, TimeUnit.SECONDS)).toVector
    } finally {
      pool.shutdownNow()
      assert(pool.awaitTermination(30, TimeUnit.SECONDS))
    }
  }

  test("concurrent putIfAbsent of different records under one key: exactly one Written") {
    val stub = Ddb.stub()
    val kv = Ddb.adapter(stub)
    val hexes = "0123456789abcdef"
    val out = race(Threads)(i => kv.putIfAbsent(TableRole.QueryJob, row("job#1", h(hexes(i)))))
    assertEquals(out.count(_ == Right(PutOutcome.Written)), 1)
    assertEquals(out.count(_.left.exists(_.isInstanceOf[StoreError.Conflict])), Threads - 1)
  }

  test("concurrent putIfVersion from one version: exactly one wins, the rest StaleVersion") {
    val stub = Ddb.stub()
    val kv = Ddb.adapter(stub)
    kv.putIfAbsent(TableRole.QueryJob, row("job#1", h('0'), Some(1)))
    val hexes = "0123456789abcdef"
    val out =
      race(Threads)(i => kv.putIfVersion(TableRole.QueryJob, row("job#1", h(hexes(i)), Some(2)), 1))
    assertEquals(out.count(_ == Right(())), 1)
    assertEquals(out.count(_.left.exists(_.isInstanceOf[StoreError.StaleVersion])), Threads - 1)
  }

  private def stores(kv: DynamoDbKeyValueStore) =
    ServiceStateStores(
      kv,
      new ManualClock(StateWorld.Start),
      StateWorld.settings(maxConflictRetries = Threads)
    )

  /** One attempt ID per logical request (per thread), as services mint them. */
  private def attempts(n: Int): Vector[String] = {
    val g = AttemptTokens.secureRandom()
    Vector.fill(n)(g.next())
  }

  test("ownership race: one owner wins; every other client gets Conflict; the owner is stable") {
    val s = stores(Ddb.adapter(Ddb.stub()))
    val out = race(Threads)(i => s.ownership.record(StateWorld.qA, s"client-$i"))
    assertEquals(out.count(_ == Right(())), 1)
    val winner = out.indexWhere(_ == Right(()))
    assertEquals(s.ownership.owner(StateWorld.qA), Right(Some(s"client-$winner")))
  }

  test("idempotency race: one Fresh, every other concurrent begin is InProgress") {
    val s = stores(Ddb.adapter(Ddb.stub()))
    val ids = attempts(Threads)
    val out = race(Threads)(i => s.idempotency.begin("client-a", "key-1", StateWorld.reqA, ids(i)))
    assertEquals(out.count(_.exists(_.isInstanceOf[IdempotencyStart.Fresh])), 1)
    assertEquals(out.count(_ == Right(IdempotencyStart.InProgress)), Threads - 1)
  }

  /** Begin, retrying Transient up to 3 times with the SAME attempt ID (one logical request). */
  private def beginWithRetries(s: ServiceStateStores, attempt: String) = {
    def go(left: Int): (Int, Either[StoreError, IdempotencyStart]) =
      s.idempotency.begin("client-a", "key-1", StateWorld.reqA, attempt) match {
        case Left(e) if e.retryable && left > 0 =>
          val (n, r) = go(left - 1)
          (n + 1, r)
        case r => (0, r)
      }
    go(3)
  }

  test("CR-INT-01-1 race: the first applied PutItem loses its response; still one holder") {
    val stub = Ddb.stub()
    val s = stores(Ddb.adapter(stub))
    stub.failAfterApplyNext(
      SdkClientException
        .builder()
        .message("lost")
        .cause(new java.net.SocketException("Connection reset"))
        .build()
    )
    val ids = attempts(Threads)
    val out = race(Threads)(i => beginWithRetries(s, ids(i)))
    val fresh = out.zipWithIndex.collect { case ((_, Right(IdempotencyStart.Fresh(t))), i) =>
      i -> t
    }
    assertEquals(fresh.size, 1, out.toString)
    assertEquals(out.count(_._2 == Right(IdempotencyStart.InProgress)), Threads - 1)
    val winner = fresh.head._1
    assertEquals(out(winner)._1, 1, "the holder is the request whose applied write was lost")
    assertEquals(s.idempotency.complete(fresh.head._2, StateWorld.ok), Right(()))
    assertEquals(
      s.idempotency.begin("client-a", "key-1", StateWorld.reqA, ids((winner + 1) % Threads)),
      Right(IdempotencyStart.Replay(StateWorld.ok))
    )
  }

  test("job transitions race: the forward chain is applied once each, never lost") {
    val s = stores(Ddb.adapter(Ddb.stub()))
    s.jobs.create("job-1", StateWorld.qA, "client-a", "queued")
    val starts = race(Threads)(_ => s.jobs.start("job-1", "run"))
    assert(starts.forall(_.isRight), starts.toString)
    assertEquals(starts.map(_.map(_.version)).distinct, Vector(Right(2L)), "one update, repeats")
    val ends = race(Threads)(i =>
      if (i % 2 == 0) s.jobs.succeed("job-1", "done", StateWorld.ok)
      else s.jobs.fail("job-1", "failed", StateWorld.err)
    )
    assertEquals(ends.count(_.isRight) >= 1, true)
    val finalJob = s.jobs.get("client-a", "job-1").toOption.flatten.get
    assertEquals(finalJob.version, 3L, "exactly one terminal update")
    assert(ends.forall(r => r.isRight || r.left.exists(_.isInstanceOf[StoreError.Conflict])))
  }

  test("mixed load: many threads on distinct keys through one adapter; nothing lost") {
    val stub = Ddb.stub()
    val kv = Ddb.adapter(stub)
    val perThread = 50
    val out = race(Threads) { t =>
      (0 until perThread)
        .map { i =>
          val pk = s"job#t$t-$i"
          val w = kv.putIfAbsent(TableRole.QueryJob, row(pk, h('a'), Some(1)))
          val v = kv.putIfVersion(TableRole.QueryJob, row(pk, h('b'), Some(2)), 1)
          val g = kv.getMany(TableRole.QueryJob, Vector(PrimaryKey(pk, "job")))
          w == Right(PutOutcome.Written) && v == Right(()) && g.map(_.size) == Right(1)
        }
        .forall(identity)
    }
    assert(out.forall(identity))
    assertEquals(stub.itemCount(TableRole.QueryJob), Threads * perThread)
    val keys =
      (0 until Threads).flatMap(t => (0 until perThread).map(i => PrimaryKey(s"job#t$t-$i", "job")))
    assertEquals(
      kv.getMany(TableRole.QueryJob, keys.toVector).map(_.size),
      Right(Threads * perThread)
    )
  }
}
