package scholarchain.storage

import scholarchain.core.TargetStore
import scholarchain.storage.fake.InMemoryKeyValueStore
import scholarchain.storage.model.*
import TestData.*

/** Suite 17 (SE-P7-01): the RunLedger load state machine and its versioned writes. */
class RunLedgerStatesSuite extends munit.FunSuite {
  import LoadState.*

  private def start() =
    RunLedgerStates.start(runId, loadId, TargetStore.S3Vectors, hex("parts"), 4, 100L).toOption.get
  private val zero = LoadCounts(0, 0, 0)

  test("frozen tags and terminal states") {
    assertEquals(
      LoadState.values.toVector.map(_.tag),
      Vector("planned", "verified", "writing", "documents-staged", "loaded", "failed", "refused")
    )
    assertEquals(LoadState.values.filter(_.terminal).toSet, Set(Loaded, Refused))
    LoadState.values
      .filter(_.terminal)
      .foreach(s => assert(RunLedgerStates.transitions(s).isEmpty, s.tag))
  }

  test("the allowed transitions are exactly as designed") {
    val allowed = for {
      from <- LoadState.values.toVector
      to <- LoadState.values.toVector
      if RunLedgerStates.allowed(from, to)
    } yield s"${from.tag}->${to.tag}"
    assertEquals(
      allowed.toSet,
      Set(
        "planned->verified",
        "planned->refused",
        "verified->writing",
        "writing->loaded",
        "writing->documents-staged",
        "writing->failed",
        "documents-staged->writing",
        "failed->writing"
      )
    )
    LoadState.values.foreach(s => assert(!RunLedgerStates.allowed(s, s), s"${s.tag} never loops"))
  }

  test("a full load: start at version 1, each transition adds exactly 1") {
    val e1 = start()
    assertEquals((e1.state, e1.version), (Planned, 1L))
    val e2 = RunLedgerStates.advance(e1, Verified, zero, 101L).toOption.get
    val e3 = RunLedgerStates.advance(e2, Writing, zero, 102L).toOption.get
    val e4 = RunLedgerStates.advance(e3, Failed, LoadCounts(2, 0, 1), 103L).toOption.get
    val e5 = RunLedgerStates.advance(e4, Writing, LoadCounts(2, 0, 1), 104L).toOption.get
    val e6 = RunLedgerStates.advance(e5, DocumentsStaged, LoadCounts(3, 2, 1), 105L).toOption.get
    assertEquals(Vector(e1, e2, e3, e4, e5, e6).map(_.version), Vector(1L, 2L, 3L, 4L, 5L, 6L))
    assertEquals(
      (e6.written, e6.alreadyPresent, e6.rejected, e6.updatedAtMillis),
      (3L, 2L, 1L, 105L)
    )
  }

  test("disallowed transitions, terminal states and decreasing counts are Refused") {
    val e1 = start()
    def refused(r: Either[StoreError, LoadEntry]) = r match {
      case Left(StoreError.Refused(_)) => ()
      case other => fail(s"expected Refused, got $other")
    }
    refused(RunLedgerStates.advance(e1, Loaded, zero, 1L))
    refused(RunLedgerStates.advance(e1, Writing, zero, 1L))
    refused(RunLedgerStates.advance(e1, Planned, zero, 1L))
    val loaded = e1.copy(state = Loaded)
    LoadState.values.foreach(s => refused(RunLedgerStates.advance(loaded, s, zero, 1L)))
    val writing = e1.copy(state = Writing, written = 5)
    refused(RunLedgerStates.advance(writing, Loaded, LoadCounts(4, 0, 0), 1L))
  }

  test("start refuses foreign IDs, bad hashes and empty loads") {
    assert(RunLedgerStates.start(loadId, loadId, TargetStore.S3, hex("p"), 1, 0L).isLeft)
    assert(RunLedgerStates.start(runId, runId, TargetStore.S3, hex("p"), 1, 0L).isLeft)
    assert(RunLedgerStates.start(runId, loadId, TargetStore.S3, "nothex", 1, 0L).isLeft)
    assert(RunLedgerStates.start(runId, loadId, null, hex("p"), 1, 0L).isLeft)
    assert(RunLedgerStates.start(runId, loadId, TargetStore.S3, hex("p"), 0, 0L).isLeft)
  }

  test("versioned ledger writes: of two loaders advancing the same version, one wins") {
    val kv = new InMemoryKeyValueStore(10)
    val c = ItemCodecs.LoadEntryCodec
    val e1 = start()
    assertEquals(
      kv.putIfAbsent(TableRole.RunLedger, c.encode(e1).toOption.get),
      Right(PutOutcome.Written)
    )
    val mine = RunLedgerStates.advance(e1, Verified, zero, 1L).toOption.get
    val theirs = RunLedgerStates.advance(e1, Refused, zero, 2L).toOption.get
    assertEquals(
      kv.putIfVersion(TableRole.RunLedger, c.encode(mine).toOption.get, e1.version),
      Right(())
    )
    kv.putIfVersion(TableRole.RunLedger, c.encode(theirs).toOption.get, e1.version) match {
      case Left(StoreError.StaleVersion(_)) => ()
      case other => fail(s"expected StaleVersion, got $other")
    }
    val stored = kv.get(TableRole.RunLedger, c.key(e1).toOption.get).toOption.flatten.get
    assertEquals(c.decode(stored).map(_.state), Right(Verified))
  }

  test("a rerun of a loaded load is a no-op: the same row is AlreadyPresent") {
    val kv = new InMemoryKeyValueStore(10)
    val c = ItemCodecs.LoadEntryCodec
    val e1 = start()
    kv.putIfAbsent(TableRole.RunLedger, c.encode(e1).toOption.get)
    assertEquals(
      kv.putIfAbsent(TableRole.RunLedger, c.encode(e1).toOption.get),
      Right(PutOutcome.AlreadyPresent)
    )
    assertEquals(kv.itemCount(TableRole.RunLedger), 1)
  }
}
