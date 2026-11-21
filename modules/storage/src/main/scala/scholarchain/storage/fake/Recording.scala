package scholarchain.storage.fake

import scala.collection.mutable

import scholarchain.storage.model.{
  Bytes,
  IndexInfo,
  Item,
  ObjectRef,
  PrimaryKey,
  PutOutcome,
  StoreError,
  TableRole,
  VectorFilter,
  VectorMatch,
  VectorPutResult,
  VectorRecord
}
import scholarchain.storage.port.{KeyQuery, KeyValueStore, ObjectMeta, ObjectStore, VectorStore}

/**
 * Write counters of a recording wrapper: every call and every outcome. Read-only outside package
 * `fake` (SE-P7-05, risk T4): only the wrapper that owns the counters records into them, so a "zero
 * writes" or "no new writes" assertion reflects behaviour, never a reset or an assignment.
 */
final class WriteCounts {
  private var callCount: Int = 0
  private var writtenCount: Int = 0
  private var presentCount: Int = 0
  private var failedCount: Int = 0

  def calls: Int = callCount
  def written: Int = writtenCount
  def alreadyPresent: Int = presentCount
  def failed: Int = failedCount

  private[fake] def record(o: Either[StoreError, PutOutcome]): Unit = {
    callCount += 1
    o match {
      case Right(PutOutcome.Written) => writtenCount += 1
      case Right(PutOutcome.AlreadyPresent) => presentCount += 1
      case Left(_) => failedCount += 1
    }
  }

  override def toString: String =
    s"WriteCounts(calls=$calls, written=$written, alreadyPresent=$alreadyPresent, failed=$failed)"
}

/** Counts every create-once put of the wrapped store. */
final class RecordingObjectStore(underlying: ObjectStore) extends ObjectStore {
  val counts = new WriteCounts
  def head(ref: ObjectRef) = underlying.head(ref)
  def get(ref: ObjectRef) = underlying.get(ref)
  def list(bucket: String, prefix: String, maxKeys: Int) = underlying.list(bucket, prefix, maxKeys)
  def putCreateOnce(
      ref: ObjectRef,
      body: Bytes,
      contentType: String
  ): Either[StoreError, PutOutcome] = {
    val o = underlying.putCreateOnce(ref, body, contentType)
    counts.record(o)
    o
  }
}

/** Counts every conditional write of the wrapped store, per table. */
final class RecordingKeyValueStore(underlying: KeyValueStore) extends KeyValueStore {
  val counts = new WriteCounts
  private val perTable: mutable.Map[TableRole, Int] = mutable.Map.empty.withDefaultValue(0)

  /** Conditional-write calls per table: an immutable snapshot (absent tables count 0). */
  def byTable: Map[TableRole, Int] = perTable.toMap.withDefaultValue(0)

  def get(table: TableRole, key: PrimaryKey) = underlying.get(table, key)
  def query(table: TableRole, q: KeyQuery) = underlying.query(table, q)
  def putIfAbsent(table: TableRole, item: Item): Either[StoreError, PutOutcome] = {
    val o = underlying.putIfAbsent(table, item)
    counts.record(o)
    perTable(table) += 1
    o
  }
  def putIfVersion(
      table: TableRole,
      item: Item,
      expectedVersion: Long
  ): Either[StoreError, Unit] = {
    val o = underlying.putIfVersion(table, item, expectedVersion)
    counts.record(o.map(_ => PutOutcome.Written))
    perTable(table) += 1
    o
  }
}

/** Counts every vector put batch and every per-record outcome of the wrapped store. */
final class RecordingVectorStore(underlying: VectorStore) extends VectorStore {
  val counts = new WriteCounts
  private var batchCount: Int = 0

  /** Put batches sent so far (read-only). */
  def batches: Int = batchCount
  def describe(index: String): Either[StoreError, Option[IndexInfo]] = underlying.describe(index)
  def getVectors(index: String, keys: Vector[String]): Either[StoreError, Vector[VectorRecord]] =
    underlying.getVectors(index, keys)
  def query(
      index: String,
      vector: Vector[Float],
      topK: Int,
      filter: Option[VectorFilter]
  ): Either[StoreError, Vector[VectorMatch]] =
    underlying.query(index, vector, topK, filter)
  def putVectors(
      index: String,
      records: Vector[VectorRecord]
  ): Either[StoreError, Vector[VectorPutResult]] = {
    batchCount += 1
    val o = underlying.putVectors(index, records)
    o.foreach(_.foreach(r => counts.record(r.outcome)))
    o
  }
}

/**
 * Counts every graph write of the wrapped writer (SE-P7-05 B4). `beforeWrite` runs before each
 * write with the number of writes made so far: tests use it to interleave a second loader or to
 * interrupt this one at an exact point.
 */
final class RecordingGraphWriter(
    underlying: scholarchain.storage.port.GraphWriter,
    beforeWrite: Int => Unit = _ => ()
) extends scholarchain.storage.port.GraphWriter {
  val counts = new WriteCounts
  def storedVertex(id: String) = underlying.storedVertex(id)
  def storedEdge(id: String) = underlying.storedEdge(id)
  def write(
      w: scholarchain.storage.model.BoundGraphWrite
  ): Either[StoreError, PutOutcome] = {
    beforeWrite(counts.calls)
    val o = underlying.write(w)
    counts.record(o)
    o
  }
}

/** A manual clock: time moves only when a test (or [[RecordingSleeper]]) advances it. */
final class ManualClock(start: Long) extends scholarchain.storage.port.Clock {
  private var now = start
  def nowMillis(): Long = now
  def advance(millis: Long): Unit = now += millis
}

/** Records every sleep and advances the clock instead of sleeping. */
final class RecordingSleeper(clock: ManualClock) extends scholarchain.storage.port.Sleeper {
  private val recorded: mutable.ArrayBuffer[Long] = mutable.ArrayBuffer.empty

  /** Every sleep so far, in order: an immutable snapshot. */
  def sleeps: Vector[Long] = recorded.toVector

  def sleep(millis: Long): Unit = {
    recorded += millis
    clock.advance(millis)
  }
}

/** Deterministic jitter: a fixed fraction of the upper bound (0.0 to 1.0). */
final class FractionJitter(fraction: Double) extends scholarchain.storage.port.Jitter {
  require(fraction >= 0.0 && fraction <= 1.0, "fraction must be in [0, 1]")
  def uniform(upperInclusive: Long): Long = math.floor(upperInclusive.toDouble * fraction).toLong
}
