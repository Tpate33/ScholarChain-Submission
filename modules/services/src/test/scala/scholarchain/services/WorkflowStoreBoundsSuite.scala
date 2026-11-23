package scholarchain.services

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

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
  SortKeyCondition,
  VectorReader
}

/** SV-INT-03 (G4): every port method of the workflow's stores, under the query-jobs read policy. */
class WorkflowStoreBoundsSuite extends munit.FunSuite {

  // readTimeout 100 ms per attempt, 2 attempts, all within 250 ms; breaker opens after 2 failures.
  private val config = ServiceWorld
    .config()
    .copy(
      readTimeoutMillis = 100L,
      readRequestTimeoutMillis = 250L,
      readMaxAttempts = 2,
      queueWaitMillis = 100L,
      breakerFailureThreshold = 2,
      breakerOpenMillis = 60000L
    )

  /** One programmable answer for every port method; counts calls. */
  private final class Store
      extends ObjectReader
      with KeyValueReader
      with KeyValueWriter
      with GraphReader
      with VectorReader
      with IndexInspector {
    val calls = new AtomicInteger(0)
    @volatile var answer: () => Either[StoreError, Any] = () => Right(null)
    @volatile var hang = false
    private val never = new CountDownLatch(1)
    private def go[A](ok: A): Either[StoreError, A] = {
      calls.incrementAndGet()
      if (hang) never.await(30, TimeUnit.SECONDS)
      answer() match {
        case Left(e) => Left(e)
        case Right(_) => Right(ok)
      }
    }
    def head(ref: ObjectRef): Either[StoreError, Option[ObjectMeta]] = go(None)
    def get(ref: ObjectRef): Either[StoreError, Option[(ObjectMeta, Bytes)]] = go(None)
    def list(bucket: String, prefix: String, maxKeys: Int) = go(Vector.empty[ObjectMeta])
    def get(table: TableRole, key: PrimaryKey): Either[StoreError, Option[Item]] = go(None)
    def query(table: TableRole, q: KeyQuery): Either[StoreError, Vector[Item]] = go(Vector.empty)
    def putIfAbsent(table: TableRole, item: Item): Either[StoreError, PutOutcome] =
      go(PutOutcome.Written)
    def putIfVersion(table: TableRole, item: Item, expectedVersion: Long) = go(())
    def run(query: BoundQuery): Either[StoreError, Vector[GraphRow]] = go(Vector.empty)
    def query(index: String, vector: Vector[Float], topK: Int, filter: Option[VectorFilter]) =
      go(Vector.empty[VectorMatch])
    def getVectors(index: String, keys: Vector[String]) = go(Vector.empty[VectorRecord])
    def describe(index: String): Either[StoreError, Option[IndexInfo]] = go(None)
  }

  private def withBounds(f: (WorkflowStoreBounds, Store) => Unit): Unit = {
    val b = WorkflowStoreBounds(config, ServiceClock.system)
    try f(b, new Store)
    finally b.close()
  }

  private val ref = ObjectRef("example-raw-bucket", "k")
  private val key = PrimaryKey("a", "b")
  private val kq = KeyQuery(None, "p", SortKeyCondition.All, 1)

  /** Every wrapped method, by name (reads first, then the two writes). */
  private def methods(
      b: WorkflowStoreBounds,
      s: Store
  ): Vector[(String, () => Either[StoreError, Any])] =
    Vector(
      "s3.head" -> (() => b.objects(s).head(ref)),
      "s3.get" -> (() => b.objects(s).get(ref)),
      "s3.list" -> (() => b.objects(s).list("example-raw-bucket", "p/", 1)),
      "dynamodb.get" -> (() => b.keyValues(s).get(TableRole.QuerySession, key)),
      "dynamodb.query" -> (() => b.keyValues(s).query(TableRole.QuerySession, kq)),
      "neptune.run" -> (() => b.graph(s).run(null)),
      "s3vectors.query" -> (() => b.vectors(s).query("i", Vector(1f), 1, None)),
      "s3vectors.getVectors" -> (() => b.vectors(s).getVectors("i", Vector("k"))),
      "s3vectors.describe" -> (() => b.indexes(s).describe("i")),
      "dynamodb.putIfAbsent" -> (() => b.writer(s).putIfAbsent(TableRole.QuerySession, Map.empty)),
      "dynamodb.putIfVersion" -> (() =>
        b.writer(s).putIfVersion(TableRole.QuerySession, Map.empty, 1L)
      )
    )

  test("every method passes a store answer through, and runs on the workflow-stores pool") {
    withBounds { (b, s) =>
      val thread = new java.util.concurrent.atomic.AtomicReference[String]()
      s.answer = () => { thread.set(Thread.currentThread().getName); Right(null) }
      methods(b, s).foreach { case (name, call) =>
        assert(call().isRight, name)
        assert(thread.get.startsWith("scholarchain-workflow-stores-"), s"$name on ${thread.get}")
      }
      // The store's own non-retryable errors pass unchanged, after one attempt.
      s.answer = () => Left(StoreError.Conflict("c"))
      val before = s.calls.get
      assertEquals(
        b.writer(s).putIfAbsent(TableRole.QuerySession, Map.empty),
        Left(StoreError.Conflict("c"))
      )
      assertEquals(b.objects(s).get(ref), Left(StoreError.Conflict("c")))
      assertEquals(s.calls.get - before, 2)
    }
  }

  test(
    "a hanging call is cut at readTimeoutMillis; reads retry within the request bound, writes never"
  ) {
    // The method names only (the calls are not evaluated here).
    methods(null, null).map(_._1).foreach { name =>
      // A fresh bounds per method: the breaker of one store must not hide another's timeout.
      withBounds { (b, s) =>
        s.hang = true
        val call = methods(b, s).find(_._1 == name).get._2
        val t0 = System.nanoTime()
        val r = call()
        val ms = (System.nanoTime() - t0) / 1000000L
        assert(r.left.exists(_.isInstanceOf[StoreError.Transient]), s"$name: $r")
        assert(r.left.exists(_.message.contains("readTimeoutMillis")), s"$name: $r")
        assert(ms >= 100 && ms < 1500, s"$name took $ms ms")
        val expected = if (name.contains(".put")) 1 else 2
        assertEquals(s.calls.get, expected, s"$name attempts")
      }
    }
  }

  test("Transient is retried up to readMaxAttempts; the breaker opens after its threshold") {
    withBounds { (b, s) =>
      s.answer = () => Left(StoreError.Transient("t"))
      assertEquals(b.graph(s).run(null), Left(StoreError.Transient("t")))
      assertEquals(s.calls.get, 2, "two attempts")
      s.answer = () => Left(StoreError.Permanent("p"))
      assertEquals(b.graph(s).run(null), Left(StoreError.Permanent("p")))
      assertEquals(s.calls.get, 3, "Permanent is not retried")
      // Two failures (threshold 2): the Neptune circuit is open; the call never reaches the store.
      val open = b.graph(s).run(null)
      assert(open.left.exists(_.message.contains("circuit is open")), open.toString)
      assertEquals(s.calls.get, 3)
      // Other stores have their own breakers.
      s.answer = () => Right(null)
      assert(b.objects(s).head(ref).isRight)
      // Refused is neutral and passes through.
      s.answer = () => Left(StoreError.Refused("r"))
      assertEquals(b.objects(s).head(ref), Left(StoreError.Refused("r")))
    }
  }

  test("after close no call reaches the store") {
    val b = WorkflowStoreBounds(config, ServiceClock.system)
    val s = new Store
    b.close()
    assert(b.isClosed)
    val r = b.objects(s).head(ref)
    assert(r.left.exists(_.message.contains("no capacity")), r.toString)
    assertEquals(s.calls.get, 0)
  }
}
