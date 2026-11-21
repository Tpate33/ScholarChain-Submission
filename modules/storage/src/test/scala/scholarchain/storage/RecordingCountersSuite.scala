package scholarchain.storage

import scala.compiletime.testing.typeCheckErrors

import scholarchain.storage.fake.*
import scholarchain.storage.model.{ObjectRef, PutOutcome, TableRole}
import TestData.*

/**
 * SE-P7-05 A4 (risk T4): the recording wrappers' counters and logs are read-only outside package
 * `fake`, so a "zero writes" or "no new writes" assertion can only be made true by behaviour.
 */
class RecordingCountersSuite extends munit.FunSuite {

  private def refused(errors: List[scala.compiletime.testing.Error], fragment: String): Unit = {
    assert(errors.nonEmpty, "the snippet must not compile")
    assert(
      errors.exists(_.message.contains(fragment)),
      s"expected '$fragment' in:\n" + errors.map(_.message).mkString("\n")
    )
  }

  test("WriteCounts fields cannot be assigned or reset from outside package fake") {
    val objects = new RecordingObjectStore(new InMemoryObjectStore(10))
    assertEquals(
      objects.putCreateOnce(
        ObjectRef("bucket", "k"),
        scholarchain.storage.model.Bytes.utf8("x"),
        "text/plain"
      ),
      Right(PutOutcome.Written)
    )
    assertEquals((objects.counts.calls, objects.counts.written), (1, 1))
    refused(typeCheckErrors("objects.counts.calls = 0"), "Reassignment to")
    refused(typeCheckErrors("objects.counts.written = 0"), "Reassignment to")
    refused(typeCheckErrors("objects.counts.alreadyPresent = 0"), "Reassignment to")
    refused(typeCheckErrors("objects.counts.failed = 0"), "Reassignment to")
    refused(typeCheckErrors("objects.counts.calls += 1"), "")
    refused(typeCheckErrors("objects.counts.written += 1"), "")
    refused(
      typeCheckErrors("objects.counts.record(Right(PutOutcome.Written))"),
      "cannot be accessed"
    )
    refused(typeCheckErrors("objects.counts.callCount = 0"), "cannot be accessed")
    refused(typeCheckErrors("objects.counts = new WriteCounts"), "Reassignment to")
    assertEquals(objects.counts.calls, 1, "unchanged")
  }

  test("byTable, batches and sleeps are immutable snapshots") {
    val kv = new RecordingKeyValueStore(new InMemoryKeyValueStore(10))
    val vectors = new RecordingVectorStore(new InMemoryVectorStore(Vector.empty, limits))
    val sleeper = new RecordingSleeper(new ManualClock(0L))
    sleeper.sleep(5L)
    assertEquals(sleeper.sleeps, Vector(5L))
    assertEquals(kv.byTable(TableRole.RunLedger), 0, "absent tables count 0")
    assertEquals(vectors.batches, 0)

    refused(typeCheckErrors("kv.byTable(TableRole.RunLedger) = 3"), "update")
    refused(typeCheckErrors("kv.byTable.clear()"), "clear")
    refused(typeCheckErrors("kv.perTable(TableRole.RunLedger) = 3"), "cannot be accessed")
    refused(typeCheckErrors("vectors.batches = 0"), "Reassignment to")
    refused(typeCheckErrors("vectors.batches += 1"), "")
    refused(typeCheckErrors("sleeper.sleeps += 1L"), "")
    refused(typeCheckErrors("sleeper.sleeps.clear()"), "clear")
    refused(typeCheckErrors("sleeper.recorded.clear()"), "cannot be accessed")
    // A snapshot taken earlier does not change when the log grows.
    val before = sleeper.sleeps
    sleeper.sleep(7L)
    assertEquals(before, Vector(5L))
    assertEquals(sleeper.sleeps, Vector(5L, 7L))
  }
}
