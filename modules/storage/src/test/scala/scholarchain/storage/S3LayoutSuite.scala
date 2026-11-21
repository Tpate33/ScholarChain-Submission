package scholarchain.storage

import scholarchain.core.SourceType
import scholarchain.storage.fake.{InMemoryObjectStore, RecordingObjectStore}
import scholarchain.storage.model.*
import scholarchain.storage.port.{ObjectReader, ObjectWriter}
import TestData.*

/** Suite 12 (SE-P7-01): S3 zones, versioned keys and create-once writes on the fake. */
class S3LayoutSuite extends munit.FunSuite {
  private val runHex = hex("run-1")
  private val loadHex = hex("load-1")

  test("exactly the six zones of README L1368, with frozen tags") {
    assertEquals(
      Zone.values.toVector.map(_.tag),
      Vector("raw", "curated", "graph", "semantic-context", "reports", "logs")
    )
  }

  test("SE-P7-13 item 3: no load-report key: the load record is the RunLedger, not an object") {
    assert(compileErrors("layout.loadReport(loadId)").nonEmpty, "the unused key is removed")
    assert(compileErrors("layout.rejects(loadId, 0)").isEmpty, "control: the rejects key exists")
  }

  test("SE-P7-13 item 2: the run files have run keys in the graph zone") {
    assertEquals(
      layout.runObject(Zone.Graph, runId, "_parts.json").map(_.key),
      Right(s"graph/runs/$runHex/_parts.json")
    )
    assertEquals(
      layout.runObject(Zone.Graph, runId, "_run-manifest.json").map(_.key),
      Right(s"graph/runs/$runHex/_run-manifest.json")
    )
  }

  test("run, load, text and reject keys embed the run or load hex") {
    assertEquals(
      layout.runObject(Zone.Curated, runId, "metrics/part-00000.parquet"),
      Right(ObjectRef("test-data-bucket", s"curated/runs/$runHex/metrics/part-00000.parquet"))
    )
    assertEquals(
      layout.loadObject(Zone.Graph, loadId, "vertices/researcher.csv"),
      Right(ObjectRef("test-data-bucket", s"graph/loads/$loadHex/vertices/researcher.csv"))
    )
    assertEquals(
      layout.contextText(loadId, chunkId),
      Right(
        ObjectRef("test-data-bucket", s"semantic-context/loads/$loadHex/text/${hex("chunk-1")}.txt")
      )
    )
    assertEquals(
      layout.rejects(loadId, 3).map(_.key),
      Right(s"reports/loads/$loadHex/rejects/part-00003.jsonl")
    )
    assertEquals(
      layout.rawSnapshot(SourceType.Dblp, "dblp-2026-10-03", "dblp.xml.gz"),
      Right(ObjectRef("test-raw-bucket", "raw/snapshots/dblp/dblp-2026-10-03/dblp.xml.gz"))
    )
    assertEquals(layout.zonePrefix(Zone.Logs), ObjectRef("test-data-bucket", "logs/"))
  }

  test("different runs and loads never share a key") {
    val a = layout.runObject(Zone.Graph, runId, "p.csv").toOption.get
    val b = layout.runObject(Zone.Graph, otherRunId, "p.csv").toOption.get
    assertNotEquals(a, b)
    assertNotEquals(layout.rejects(loadId, 0), layout.rejects(otherLoadId, 0))
  }

  test("keys refuse foreign ID kinds, traversal, bad segments and the wrong zone") {
    assert(layout.runObject(Zone.Curated, loadId, "a").isLeft, "a load ID is not a run ID")
    assert(layout.loadObject(Zone.Reports, runId, "a").isLeft)
    assert(layout.runObject(Zone.Curated, runId, "../other").isLeft)
    assert(layout.runObject(Zone.Curated, runId, "a//b").isLeft)
    assert(layout.runObject(Zone.Curated, runId, "/abs").isLeft)
    assert(layout.runObject(Zone.Curated, runId, "a b").isLeft)
    assert(layout.runObject(Zone.Curated, runId, "a#b").isLeft)
    assert(layout.runObject(Zone.Raw, runId, "a").isLeft, "raw holds snapshots only")
    assert(layout.loadObject(Zone.Curated, loadId, "a").isLeft, "curated holds run outputs only")
    assert(layout.rawSnapshot(SourceType.Derived, "s", "a").isLeft, "raw is for dataset sources")
    assert(layout.rejects(loadId, -1).isLeft)
  }

  test("keys longer than maxKeyBytes are refused") {
    val tight = S3Layout.from(layoutSettings.copy(maxKeyBytes = 70)).toOption.get
    assert(tight.runObject(Zone.Curated, runId, "a").isLeft)
  }

  test("settings refuse a missing zone, a bad bucket, a bad prefix and overlapping prefixes") {
    assert(S3Layout.from(layoutSettings.copy(zones = layoutSettings.zones - Zone.Logs)).isLeft)
    val badBucket = layoutSettings.zones.updated(Zone.Raw, ZoneLocation("Bad_Bucket", "raw"))
    assert(S3Layout.from(layoutSettings.copy(zones = badBucket)).isLeft)
    val badPrefix = layoutSettings.zones.updated(Zone.Raw, ZoneLocation("test-raw-bucket", "/raw/"))
    assert(S3Layout.from(layoutSettings.copy(zones = badPrefix)).isLeft)
    val emptyPrefix = layoutSettings.zones.updated(Zone.Raw, ZoneLocation("test-raw-bucket", ""))
    assert(S3Layout.from(layoutSettings.copy(zones = emptyPrefix)).isLeft)
    val nested =
      layoutSettings.zones.updated(Zone.Logs, ZoneLocation("test-data-bucket", "reports/logs"))
    val problems = S3Layout.problems(layoutSettings.copy(zones = nested))
    assert(problems.exists(_.contains("overlap")), problems.toString)
    assert(S3Layout.from(layoutSettings.copy(maxKeyBytes = 0)).isLeft)
    assertEquals(S3Layout.problems(layoutSettings), Vector.empty)
  }

  test("create-once: rerun writes no new object, different bytes under a key are a Conflict") {
    val store = new RecordingObjectStore(new InMemoryObjectStore(maxListKeys = 1000))
    val ref = layout.loadObject(Zone.Reports, loadId, "x.json").toOption.get
    val body = Bytes.utf8("""{"loaded":true}""")
    assertEquals(store.putCreateOnce(ref, body, "application/json"), Right(PutOutcome.Written))
    assertEquals(
      store.putCreateOnce(ref, body, "application/json"),
      Right(PutOutcome.AlreadyPresent)
    )
    store.putCreateOnce(ref, Bytes.utf8("""{"loaded":false}"""), "application/json") match {
      case Left(StoreError.Conflict(_)) => ()
      case other => fail(s"expected Conflict, got $other")
    }
    assertEquals(store.get(ref).map(_.map(_._2)), Right(Some(body)), "stored bytes unchanged")
    assertEquals(store.head(ref).map(_.map(_.sha256)), Right(Some(body.sha256)))
    assertEquals(
      (store.counts.written, store.counts.alreadyPresent, store.counts.failed),
      (1, 1, 1)
    )
  }

  test("list is bounded and ascending by key") {
    val store = new InMemoryObjectStore(maxListKeys = 2)
    Vector("c", "a", "b").foreach { n =>
      store.putCreateOnce(
        layout.loadObject(Zone.Logs, loadId, n).toOption.get,
        Bytes.utf8(n),
        "text/plain"
      )
    }
    val listed = store.list("test-data-bucket", s"logs/loads/$loadHex/", 2).toOption.get
    assertEquals(listed.map(_.ref.key.takeRight(1)), Vector("a", "b"))
    assert(store.list("test-data-bucket", "logs/", 3).isLeft)
  }

  test("stored bytes cannot be changed through the caller's array") {
    val store = new InMemoryObjectStore(10)
    val raw = Array[Byte](1, 2, 3)
    val ref = layout.loadObject(Zone.Logs, loadId, "x").toOption.get
    store.putCreateOnce(ref, Bytes.of(raw), "application/octet-stream")
    raw(0) = 9
    assertEquals(
      store.get(ref).toOption.flatten.map(_._2.toArray.toVector),
      Some(Vector[Byte](1, 2, 3))
    )
  }

  test("the object ports and the fake have no delete or overwrite") {
    val forbidden =
      Set("delete", "deleteobject", "deleteobjects", "put", "putobject", "overwrite", "copy")
    Vector(classOf[ObjectReader], classOf[ObjectWriter], classOf[InMemoryObjectStore]).foreach {
      c =>
        val names = c.getMethods.map(_.getName.toLowerCase).toSet
        assert(
          names.intersect(forbidden).isEmpty,
          s"${c.getSimpleName}: ${names.intersect(forbidden)}"
        )
    }
  }
}
