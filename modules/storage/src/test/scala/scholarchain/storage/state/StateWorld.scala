package scholarchain.storage.state

import java.util.concurrent.atomic.AtomicInteger

import scholarchain.storage.aws.dynamodb.Ddb
import scholarchain.storage.fake.{InMemoryKeyValueStore, ManualClock}
import scholarchain.storage.model.{Item, PrimaryKey, StoreError, TableRole}
import scholarchain.storage.port.{DeleteOutcome, KeyQuery, ServiceStateKeyValues}

/**
 * Wraps a store: counts batch reads and writes, can make the next versioned puts lose
 * (StaleVersion), and (SE-INT-02, CR-INT-01-1) can APPLY the next writes and then lose their
 * response, as `Transient` (a timeout or reset after the server applied it) or as `StaleVersion` /
 * `Conflict` (the SDK's own resend then failed its condition against the first write). Works over
 * both backends, so the recovery tests run on the in-memory store and on the DynamoDB adapter.
 */
final class CountingKv(u: ServiceStateKeyValues) extends ServiceStateKeyValues {
  val getManyCalls = new AtomicInteger(0)
  val putIfAbsentCalls = new AtomicInteger(0)
  val putIfVersionCalls = new AtomicInteger(0)
  val deleteCalls = new AtomicInteger(0)
  val staleNext = new AtomicInteger(0)

  /** The next N writes are applied, then answered `Transient` (response lost). */
  val appliedThenTransient = new AtomicInteger(0)

  /** The next N writes are applied, then answered as if resent: putIfVersion `StaleVersion`. */
  val appliedThenResent = new AtomicInteger(0)

  @volatile var lastGetManyKeys: Vector[PrimaryKey] = Vector.empty

  private def take(n: AtomicInteger): Boolean = n.getAndUpdate(x => if (x > 0) x - 1 else 0) > 0

  private def lost[A](applied: Either[StoreError, A], resent: => Either[StoreError, A]) =
    if (applied.isLeft) applied
    else if (take(appliedThenTransient)) Left(StoreError.Transient("injected: applied, lost"))
    else if (take(appliedThenResent)) resent
    else applied

  def get(t: TableRole, k: PrimaryKey) = u.get(t, k)
  def query(t: TableRole, q: KeyQuery) = u.query(t, q)
  def getMany(t: TableRole, ks: Vector[PrimaryKey]): Either[StoreError, Map[PrimaryKey, Item]] = {
    getManyCalls.incrementAndGet()
    lastGetManyKeys = ks
    u.getMany(t, ks)
  }
  def putIfAbsent(t: TableRole, i: Item) = {
    putIfAbsentCalls.incrementAndGet()
    lost(u.putIfAbsent(t, i), u.putIfAbsent(t, i))
  }
  def putIfVersion(t: TableRole, i: Item, v: Long): Either[StoreError, Unit] = {
    putIfVersionCalls.incrementAndGet()
    if (take(staleNext)) Left(StoreError.StaleVersion("injected lost race"))
    else lost(u.putIfVersion(t, i, v), u.putIfVersion(t, i, v))
  }
  def deleteIfRecord(
      t: TableRole,
      k: PrimaryKey,
      sha: String
  ): Either[StoreError, DeleteOutcome] = {
    deleteCalls.incrementAndGet()
    lost(u.deleteIfRecord(t, k, sha), u.deleteIfRecord(t, k, sha))
  }
}

/** Deterministic attempt IDs of the minimum length. */
final class CountingTokens extends AttemptTokens {
  private val n = new AtomicInteger(0)
  def next(): String = f"attempt-${n.incrementAndGet()}%08d"
}

object StateWorld {
  val Start: Long = 1_700_000_000_000L
  val Writer = "scholarchain.services.query-jobs"
  val InFlightTtl = 120L
  val CompletedTtl = 600L
  val OwnerTtl = 3600L
  val JobTtl = 1800L

  /** Attempt IDs of independent logical requests (each at least 16 characters). */
  val tokA = "attempt-a-0000000001"
  val tokB = "attempt-b-0000000002"
  val tokC = "attempt-c-0000000003"

  def settings(
      maxConflictRetries: Int = 3,
      maxOwnedBy: Int = 250,
      skewMillis: Long = 0L,
      maxPayload: Int = 4096
  ): ServiceStateSettings =
    ServiceStateSettings
      .of(
        Writer,
        OwnerTtl,
        JobTtl,
        CompletedTtl,
        InFlightTtl,
        maxPayload,
        maxConflictRetries,
        maxOwnedBy,
        60000L,
        5000L,
        skewMillis,
        0L
      )
      .toOption
      .get

  /** A backend: the in-memory store or the DynamoDB adapter over the stub. */
  enum Backend(val label: String) {
    case Fake extends Backend("in-memory")
    case Dynamo extends Backend("dynamodb-adapter")

    def kv(): ServiceStateKeyValues = this match {
      case Fake => new InMemoryKeyValueStore(50)
      case Dynamo => Ddb.adapter(Ddb.stub())
    }
  }

  final case class World(
      kv: CountingKv,
      clock: ManualClock,
      stores: ServiceStateStores
  )

  def world(b: Backend, s: ServiceStateSettings = settings()): World = {
    val kv = new CountingKv(b.kv())
    val clock = new ManualClock(Start)
    World(kv, clock, ServiceStateStores(kv, clock, s))
  }

  val qA = "query:alpha-1"
  val qB = "query:beta-2"
  val qC = "query:gamma-3"
  val ok = """{"status":200,"body":{"queryId":"query:alpha-1"}}"""
  val err = """{"code":"timeout","message":"the Graph-RAG workflow did not finish"}"""
  val reqA = "1" * 64
  val reqB = "2" * 64
}
