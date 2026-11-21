package scholarchain.storage.load

import scholarchain.core.TargetStore
import scholarchain.storage.Fixtures.*
import scholarchain.storage.RunSettingsFixture
import scholarchain.storage.fake.*
import scholarchain.storage.model.*
import scholarchain.storage.port.{KeyValueReader, ObjectStore}

/**
 * SE-P7-06 item 7 (checklist S1-S3, L2, L3; risk T2): the ordinary-S3 loader.
 *
 * Stated plainly, as the human acceptance rule requires: in PRODUCTION (SE-P7-07: with the upstream
 * grouping and curated runs read by the production readers) the gate refuses `s3`, because
 * paths-run-chain is not performed: it needs the run's benchmark pair list, which is not in the
 * fixtures. The first test asserts exactly that. Every other test here is an IN-PACKAGE
 * GATE-MECHANICS test: it starts from that production report (every other check really performed)
 * and marks ONLY paths-run-chain passed, to exercise the writer. They are not evidence of a
 * complete s3 load.
 */
class ObjectLoadSuite extends munit.FunSuite {

  private val S3 = TargetStore.S3
  private val Open: Set[DeferredCheck] = Set(DeferredCheck.PathsRunChain)

  private lazy val production: VerificationReport = LoadVerifier.verify(
    source(runDir(run1Root)),
    source(loadDir(run1Root), loadBounds),
    KeyValueReader.readOnly(new InMemoryKeyValueStore(100)),
    Some(scholarchain.storage.UpstreamFixture.context)
  )

  /** Gate mechanics only: the production report plus the two open s3 checks, marked here. */
  private lazy val marked: VerificationReport = {
    val v = production.verified.get
    val m = new VerifiedRun(
      v.runId,
      v.partManifestSha256,
      v.partManifest,
      v.runManifest,
      v.loads,
      v.passedChecks + (S3 -> (v.passedFor(S3) ++ Open)),
      source(runDir(run1Root)),
      v.partManifestSchemaVersion
    )
    production.copy(verified = Some(m))
  }

  private val layout: S3Layout = S3Layout
    .from(
      S3LayoutSettings(
        Zone.values.toVector.map(z => z -> ZoneLocation("test-bucket", s"zone-${z.tag}")).toMap,
        1024
      )
    )
    .fold(e => throw new AssertionError(e.toString), identity)

  private final class World {
    val objects = new RecordingObjectStore(new InMemoryObjectStore(1000))
    val registry = new RecordingKeyValueStore(new InMemoryKeyValueStore(100))
    val ledger = new RecordingKeyValueStore(new InMemoryKeyValueStore(100))
    val clock = new ManualClock(1_700_000_000_000L)
  }

  /** An object store whose reads are `store`'s and whose writes go through `put`. */
  private def writingVia(store: ObjectStore)(
      put: (ObjectRef, Bytes, String) => Either[StoreError, PutOutcome]
  ): ObjectStore = new ObjectStore {
    def head(ref: ObjectRef) = store.head(ref)
    def get(ref: ObjectRef) = store.get(ref)
    def list(bucket: String, prefix: String, maxKeys: Int) = store.list(bucket, prefix, maxKeys)
    def putCreateOnce(ref: ObjectRef, body: Bytes, contentType: String) =
      put(ref, body, contentType)
  }

  private def load(w: World, writer: ObjectStore): Either[StoreError, ObjectLoadReport] =
    LoadGate.proceed(marked, S3)(p =>
      ObjectLoad.load(p, layout, writer, w.registry, w.ledger, w.clock)
    ) match {
      case Left(rs) => fail(s"gate refused: $rs")
      case Right(out) => out
    }

  private lazy val items = production.verified.get.load(S3).get.items
  private lazy val loadId = production.verified.get.load(S3).get.loadId

  private def entry(w: World): LoadEntry =
    (for {
      k <- TableKeys.RunLedger.load(RunId, loadId)
      i <- w.ledger.get(TableRole.RunLedger, k)
      e <- ItemCodecs.LoadEntryCodec.decode(i.get)
    } yield e).fold(e => fail(e.toString), identity)

  test("production: the gate refuses s3 (paths-run-chain only); nothing is written") {
    assertEquals(production.rejections, Vector.empty)
    assertEquals(production.outstandingFor(S3), Vector(DeferredCheck.PathsRunChain))
    val w = new World
    val out = LoadGate.proceed(production, S3)(p =>
      ObjectLoad.load(p, layout, w.objects, w.registry, w.ledger, w.clock)
    )
    assert(out.isLeft)
    assertEquals(
      (w.objects.counts.calls, w.registry.counts.calls, w.ledger.counts.calls),
      (0, 0, 0)
    )
  }

  test(
    "mechanics: 29 parts + 2 run files create-once under run keys, each with a registry row (S1-S3)"
  ) {
    val w = new World
    val r = load(w, w.objects).fold(e => fail(e.toString), identity)
    assertEquals(
      (r.objects, r.runFiles, r.written, r.alreadyPresent, r.finalState, r.registryRows),
      (31, 2, 31L, 0L, LoadState.Loaded, 31)
    )
    // SE-P7-13 item 2: the run files, byte-identical to the fixture, in the graph zone.
    LoadPermit.RunFiles.foreach { name =>
      val ref = layout.runObject(Zone.Graph, RunId, name).toOption.get
      assertEquals(ref.key, s"zone-graph/runs/$RunHex/$name")
      val (meta, body) = w.objects.get(ref).toOption.flatten.getOrElse(fail(s"missing $name"))
      assertEquals(body.toArray.toVector, bytes(runDir(run1Root).resolve(name)).toVector, name)
      assertEquals(meta.contentType, ObjectLoad.RunFileContentType)
      val row = TableKeys.ArtifactRegistry
        .artifact(RunId, Zone.Graph, ref.key)
        .flatMap(w.registry.get(TableRole.ArtifactRegistry, _))
        .flatMap(i => ItemCodecs.ArtifactCodec.decode(i.get))
        .fold(e => fail(e.toString), identity)
      assertEquals(
        (row.sourcePath, row.sha256, row.loadId),
        (Some(name), meta.sha256, Some(loadId))
      )
    }
    assertEquals(w.objects.counts.written, 31)
    items.foreach { it =>
      val zone = ObjectLoad.zoneOf(it.recordSchemaVersion)
      val ref = layout.runObject(zone, RunId, it.path).toOption.get
      assert(ref.key.startsWith(s"zone-${zone.tag}/runs/$RunHex/"), ref.key)
      val (meta, body) = w.objects.get(ref).toOption.flatten.getOrElse(fail(s"missing ${ref.uri}"))
      assertEquals((meta.sha256, meta.byteCount), (it.sha256, it.byteCount), it.path)
      assertEquals(body.sha256, it.sha256)
      val row = TableKeys.ArtifactRegistry
        .artifact(RunId, zone, ref.key)
        .flatMap(w.registry.get(TableRole.ArtifactRegistry, _))
        .flatMap(i => ItemCodecs.ArtifactCodec.decode(i.get))
        .fold(e => fail(e.toString), identity)
      assertEquals(
        (
          row.runId,
          row.loadId,
          row.sourcePath,
          row.recordSchemaVersion,
          row.sha256,
          row.byteCount,
          row.bucket
        ),
        (
          RunId,
          Some(loadId),
          Some(it.path),
          Some(it.recordSchemaVersion),
          it.sha256,
          it.byteCount,
          "test-bucket"
        )
      )
    }
    assertEquals(entry(w).state, LoadState.Loaded)
    assertEquals(
      items.map(i => ObjectLoad.zoneOf(i.recordSchemaVersion)).toSet,
      Set(Zone.Graph, Zone.SemanticContext)
    )
  }

  test("mechanics: a rerun is skipped with zero object and registry writes (L2)") {
    val w = new World
    load(w, w.objects).fold(e => fail(e.toString), identity)
    val before = (w.objects.counts.calls, w.registry.counts.calls)
    val again = load(w, w.objects).fold(e => fail(e.toString), identity)
    assertEquals((again.skipped, again.written), (true, 0L))
    assertEquals((w.objects.counts.calls, w.registry.counts.calls), before)
  }

  test("mechanics: a rerun against a fresh ledger finds every object and row present") {
    val w = new World
    load(w, w.objects).fold(e => fail(e.toString), identity)
    val w2 = new World
    val r = LoadGate.proceed(marked, S3)(p =>
      ObjectLoad.load(p, layout, w.objects, w.registry, w2.ledger, w2.clock)
    )
    assertEquals(r.map(_.map(x => (x.written, x.alreadyPresent))), Right(Right((0L, 31L))))
    assertEquals(w.objects.counts.written, 31)
  }

  test("mechanics: an interrupted load resumes without duplicates") {
    val w = new World
    val store = new InMemoryObjectStore(1000)
    val recording = new RecordingObjectStore(store)
    val failing = writingVia(store) { (ref, body, contentType) =>
      if (recording.counts.calls == 10) Left(StoreError.Transient("throttled"))
      else recording.putCreateOnce(ref, body, contentType)
    }
    assertEquals(load(w, failing), Left(StoreError.Transient("throttled")))
    assertEquals(store.size, 10)
    assertEquals((entry(w).state, entry(w).written), (LoadState.Failed, 10L))
    val resumed = load(w, recording).fold(e => fail(e.toString), identity)
    assertEquals((resumed.written, resumed.alreadyPresent), (21L, 10L))
    assertEquals(store.size, 31)
    assertEquals((entry(w).state, entry(w).written), (LoadState.Loaded, 31L))
  }

  test("mechanics: other bytes under a key are a Conflict, never an overwrite") {
    val w = new World
    val it = items.head
    val ref =
      layout.runObject(ObjectLoad.zoneOf(it.recordSchemaVersion), RunId, it.path).toOption.get
    assertEquals(
      w.objects.putCreateOnce(ref, Bytes.utf8("other"), "text/plain"),
      Right(PutOutcome.Written)
    )
    load(w, w.objects) match {
      case Left(StoreError.Conflict(d)) => assert(d.contains(ref.uri), d)
      case other => fail(s"expected Conflict, got $other")
    }
    assertEquals(
      w.objects.get(ref).toOption.flatten.map(_._2),
      Some(Bytes.utf8("other")),
      "unchanged"
    )
    assertEquals(entry(w).state, LoadState.Failed)
  }

  test(
    "CR-P7-09 M1: another run's element under a LATE key refuses the whole load with zero writes"
  ) {
    val w = new World
    val it = items.last
    val ref =
      layout.runObject(ObjectLoad.zoneOf(it.recordSchemaVersion), RunId, it.path).toOption.get
    val other = Bytes.utf8("another run's bytes")
    assertEquals(w.objects.putCreateOnce(ref, other, "text/plain"), Right(PutOutcome.Written))
    load(w, w.objects) match {
      case Left(StoreError.Conflict(d)) => assert(d.contains("pre-write probe"), d)
      case x => fail(s"expected Conflict, got $x")
    }
    assertEquals(
      w.objects.counts.written,
      1,
      "only the planted object; every other key untouched"
    )
    assertEquals(w.registry.counts.calls, 0)
    assertEquals(w.objects.get(ref).toOption.flatten.map(_._2), Some(other), "intact")
    assertEquals(entry(w).state, LoadState.Failed)
  }

  test("CR-P7-09 M1: a registry row of another load under a late key refuses with zero writes") {
    val w = new World
    val it = items.last
    val zone = ObjectLoad.zoneOf(it.recordSchemaVersion)
    val ref = layout.runObject(zone, RunId, it.path).toOption.get
    val foreign = ArtifactRecord(
      ArtifactRecord.SchemaVersion,
      RunId,
      Some(scholarchain.storage.TestData.otherLoadId),
      zone.tag,
      ref.bucket,
      ref.key,
      it.sha256,
      it.byteCount,
      ObjectLoad.contentTypeOf(it.format),
      Some(it.path),
      Some(it.recordSchemaVersion)
    )
    ItemCodecs.ArtifactCodec
      .encode(foreign)
      .flatMap(w.registry.putIfAbsent(TableRole.ArtifactRegistry, _))
      .fold(e => fail(e.toString), identity)
    assert(load(w, w.objects).left.exists(_.isInstanceOf[StoreError.Conflict]))
    assertEquals(w.objects.counts.calls, 0)
    assertEquals(w.registry.counts.calls, 1, "only the planted row")
  }

  test("mechanics T2: two interleaved loaders write each object once; one loaded row") {
    val w = new World
    val store = new InMemoryObjectStore(1000)
    val b = new RecordingObjectStore(store)
    var second: Option[Either[StoreError, ObjectLoadReport]] = None
    val a = new RecordingObjectStore(store)
    val interleaving = writingVia(store) { (ref, body, contentType) =>
      if (a.counts.calls == 12 && second.isEmpty) second = Some(load(w, b))
      a.putCreateOnce(ref, body, contentType)
    }
    val first = load(w, interleaving).fold(e => fail(e.toString), identity)
    val other = second.get.fold(e => fail(e.toString), identity)
    assertEquals((first.finalState, other.finalState), (LoadState.Loaded, LoadState.Loaded))
    assertEquals(a.counts.written + b.counts.written, 31, "each object written exactly once")
    assertEquals(store.size, 31)
    assertEquals(entry(w).state, LoadState.Loaded)
  }

  test("mechanics T2: another _parts.json recorded for the run ID is a Conflict with zero writes") {
    val w = new World
    ItemCodecs.RunPublicationCodec
      .encode(RunPublication(RunPublication.SchemaVersion, RunId, Run2PartsSha256, loadId))
      .flatMap(w.ledger.putIfAbsent(TableRole.RunLedger, _))
      .fold(e => fail(e.toString), identity)
    load(w, w.objects) match {
      case Left(StoreError.Conflict(d)) => assert(d.contains(Run2PartsSha256), d)
      case other => fail(s"expected Conflict, got $other")
    }
    assertEquals((w.objects.counts.calls, w.registry.counts.calls), (0, 0))
  }

  test("the loader refuses a permit for another store and a key the layout refuses") {
    val tiny = S3Layout
      .from(
        S3LayoutSettings(
          Zone.values.toVector.map(z => z -> ZoneLocation("test-bucket", s"zone-${z.tag}")).toMap,
          40
        )
      )
      .toOption
      .get
    val w = new World
    val out = LoadGate.proceed(marked, S3)(p =>
      ObjectLoad.load(p, tiny, w.objects, w.registry, w.ledger, w.clock)
    )
    assert(out.exists(_.left.exists(_.message.contains("exceeds maxKeyBytes 40"))), out.toString)
    assertEquals(
      (w.objects.counts.calls, w.ledger.counts.calls),
      (0, 0),
      "refused before any write"
    )
    // The Neptune gate is open in production; its permit is refused by the object loader.
    val n = LoadGate.proceed(production, TargetStore.NeptuneBulkLoad)(p =>
      ObjectLoad.load(p, layout, w.objects, w.registry, w.ledger, w.clock)
    )
    assert(
      n.exists(_.left.exists(_.message.contains("the permit is for neptune-bulk-load"))),
      n.toString
    )
  }

  // ------------------------------------------------------------------ SE-P7-13 item 2

  test("SE-P7-13 item 2: only an s3 permit reads the two run files; nothing else widens (F2)") {
    val s3 = LoadGate.proceed(marked, S3)(p => Right(p)).toOption.get.toOption.get
    assertEquals(s3.runFileNames, Vector("_parts.json", "_run-manifest.json"))
    assertEquals(
      s3.readRunFile("_parts.json").map(_.toArray.toVector),
      Right(bytes(runDir(run1Root).resolve("_parts.json")).toVector)
    )
    assertEquals(
      s3.readRunFile("_run-manifest.json").map(_.sha256),
      Right(
        scholarchain.core.Canonical.sha256Hex(bytes(runDir(run1Root).resolve("_run-manifest.json")))
      )
    )
    assertEquals(s3.runFileSchema("_parts.json"), Some("scholarchain.part-manifest.v2"))
    assertEquals(s3.runFileSchema("_run-manifest.json"), Some("scholarchain.run-manifest.v1"))
    val neptuneItem = production.verified.get.load(TargetStore.NeptuneBulkLoad).get.items.head.path
    Vector("_SUCCESS", neptuneItem, "s3.json", "../_parts.json", "_parts.json/").foreach { p =>
      assert(s3.readRunFile(p).left.exists(_.isInstanceOf[StoreError.Refused]), p)
      assertEquals(s3.runFileSchema(p), None, p)
    }
    // readItem is unchanged: the run files are still not items.
    LoadPermit.RunFiles.foreach(f => assert(s3.readItem(f).isLeft, f))
    // A permit for any other store names no run file and reads none.
    val n = LoadGate
      .proceed(production, TargetStore.NeptuneBulkLoad)(p => Right(p))
      .toOption
      .get
      .toOption
      .get
    assertEquals(n.runFileNames, Vector.empty)
    LoadPermit.RunFiles.foreach { f =>
      assert(n.readRunFile(f).left.exists(_.message.contains("not a run file")), f)
      assertEquals(n.runFileSchema(f), None)
    }
  }

  test("SE-P7-13 item 2: a _parts.json changed after verification refuses with zero writes") {
    withCopy { (rd, ld) =>
      val rep = LoadVerifier.verify(
        source(rd),
        source(ld, loadBounds),
        KeyValueReader.readOnly(new InMemoryKeyValueStore(100)),
        Some(scholarchain.storage.UpstreamFixture.context)
      )
      val v = rep.verified.getOrElse(fail(rep.rejections.toString))
      val m = rep.copy(verified =
        Some(
          new VerifiedRun(
            v.runId,
            v.partManifestSha256,
            v.partManifest,
            v.runManifest,
            v.loads,
            v.passedChecks + (S3 -> (v.passedFor(S3) ++ Open)),
            source(rd),
            v.partManifestSchemaVersion
          )
        )
      )
      val p = rd.resolve("_parts.json")
      java.nio.file.Files.write(p, java.nio.file.Files.readAllBytes(p) :+ ' '.toByte)
      val w = new World
      LoadGate.proceed(m, S3)(pm =>
        ObjectLoad.load(pm, layout, w.objects, w.registry, w.ledger, w.clock)
      ) match {
        case Right(Left(StoreError.Corrupt(d))) =>
          assert(d.contains("_parts.json: changed since verification"), d)
        case other => fail(s"expected Corrupt, got $other")
      }
      assertEquals(
        (w.objects.counts.calls, w.registry.counts.calls, w.ledger.counts.calls),
        (0, 0, 0),
        "refused before any write, the ledger included"
      )
    }
  }

  test("SE-P7-13 item 2: other bytes under a run-file key are a Conflict, never overwritten") {
    val w = new World
    val ref = layout.runObject(Zone.Graph, RunId, "_run-manifest.json").toOption.get
    val other = Bytes.utf8("{\"not\":\"this run\"}\n")
    assertEquals(
      w.objects.putCreateOnce(ref, other, ObjectLoad.RunFileContentType),
      Right(PutOutcome.Written)
    )
    load(w, w.objects) match {
      case Left(StoreError.Conflict(d)) => assert(d.contains("pre-write probe"), d)
      case x => fail(s"expected Conflict, got $x")
    }
    assertEquals(w.objects.counts.written, 1, "only the planted object")
    assertEquals(w.objects.get(ref).toOption.flatten.map(_._2), Some(other), "intact")
  }
}
