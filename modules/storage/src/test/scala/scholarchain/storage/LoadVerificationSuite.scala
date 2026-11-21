package scholarchain.storage

import java.nio.file.{Files, Path, StandardOpenOption}

import scholarchain.core.{
  Json,
  PartManifestV2,
  PartManifests,
  SchemaVersions,
  StoreLoadId,
  StoreLoadManifest,
  TargetStore
}
import scholarchain.storage.fake.{InMemoryKeyValueStore, InMemoryObjectStore}
import scholarchain.storage.fake.{RecordingKeyValueStore, RecordingObjectStore}
import scholarchain.storage.load.*
import scholarchain.storage.model.*
import scholarchain.storage.port.KeyValueReader
import Fixtures.*

/**
 * Load verification before any write (SE-P7-03; checklist L1, R1): success on the real fixture, one
 * refusal per corruption class, the conflicting run-2 publication, and a deterministic rerun. Every
 * refusal is shown to reach zero store writes through [[LoadGate]] and recording fakes.
 */
class LoadVerificationSuite extends munit.FunSuite {

  /** Recording stores plus the gate: the only path from a report to writes. */
  private final class Stores {
    val kv = new RecordingKeyValueStore(new InMemoryKeyValueStore(100))
    val objects = new RecordingObjectStore(new InMemoryObjectStore(100))
    def ledger: KeyValueReader = KeyValueReader.readOnly(kv)

    /** What a later loader does through the gate: publication row, then one verified item. */
    def load(report: VerificationReport, target: TargetStore = TargetStore.S3) =
      LoadGate.proceed(report, target) { p =>
        for {
          _ <- record(p.runId, p.partManifestSha256, p.load.loadId)
          path = p.items.head.path
          body <- p.readItem(path)
          ref <- TestData.layout.runObject(Zone.Curated, p.runId, path)
          o <- objects.putCreateOnce(ref, body, "application/octet-stream")
        } yield o
      }

    /** The publication row a loader records first (SE-P7-01 R1), written directly by a test. */
    def record(runId: String, partManifestSha256: String, loadId: String) =
      ItemCodecs.RunPublicationCodec
        .encode(RunPublication(RunPublication.SchemaVersion, runId, partManifestSha256, loadId))
        .flatMap(kv.putIfAbsent(ItemCodecs.RunPublicationCodec.table.role, _))

    def writes: Int = kv.counts.calls + objects.counts.calls
  }

  private def verify(run: Path, load: Path, stores: Stores = new Stores): VerificationReport =
    LoadVerifier.verify(source(run), source(load, loadBounds), stores.ledger)

  /** Verifies, then asserts the refusal: no VerifiedRun, the expected check, zero writes. */
  private def refusedBy(
      run: Path,
      load: Path,
      check: VerificationCheck,
      fragment: String,
      stores: Stores = new Stores
  ): VerificationReport = {
    val r = verify(run, load, stores)
    assert(!r.structurallyValid, "refused")
    assertEquals(r.verified, None)
    assert(
      r.rejections.exists(j => j.check == check && j.error.message.contains(fragment)),
      s"expected ${check.tag} containing '$fragment'; got:\n" +
        r.rejections.map(j => s"  ${j.check.tag} ${j.subject}: ${j.error}").mkString("\n")
    )
    TargetStore.values.foreach { t =>
      assertEquals(stores.load(r, t), Left(r.rejections), s"the gate refuses ${t.tag}")
      assert(!r.acceptedFor(t))
    }
    assertEquals(stores.writes, 0, "zero writes on refusal")
    r
  }

  private def manifest(load: Path, t: TargetStore): StoreLoadManifest =
    Json
      .decode[StoreLoadManifest](text(load.resolve(s"${t.tag}.json")).stripSuffix("\n"))
      .fold(e => fail(e.toString), identity)

  /** Rewrites a load manifest, recomputing its load ID so only the intended change is wrong. */
  private def rewrite(load: Path, t: TargetStore)(
      change: StoreLoadManifest => StoreLoadManifest
  ): Unit = {
    val m = change(manifest(load, t))
    val id = StoreLoadId
      .of(
        m.targetStore,
        m.sourceRunId,
        m.partManifestSha256,
        m.items.map(i => (i.path, i.targetRole))
      )
      .fold(e => fail(e.toString), identity)
    write(load.resolve(s"${t.tag}.json"), Json.write(m.copy(loadId = id)) + "\n")
  }

  private def parts(run: Path): PartManifestV2 =
    PartManifests
      .decode(text(run.resolve("_parts.json")))
      .map(PartManifests.asV2)
      .fold(e => fail(e.toString), identity)

  // ------------------------------------------------------------------ success and rerun

  test(
    "the real fixture is structurally valid: 56 files, four stores with 24, 21, 29 and 4 items"
  ) {
    val stores = new Stores
    val r = verify(runDir(run1Root), loadDir(run1Root), stores)
    assertEquals(r.rejections, Vector.empty)
    assert(r.structurallyValid)
    assertEquals(r.runId, Some(RunId))
    assertEquals(r.partManifestSha256, Some(PartsSha256))
    assertEquals(r.publication, Some(PublicationStatus.Unrecorded))
    assertEquals(r.observedFiles, 56)
    assertEquals(r.csvPartsCounted, 24)
    assertEquals(r.csvRowsCounted, 1258L)
    assertEquals(
      r.stores.map(s => s.targetStore -> s.items),
      Vector(
        TargetStore.NeptuneBulkLoad -> 24,
        TargetStore.DynamoDb -> 21,
        TargetStore.S3 -> 29,
        TargetStore.S3Vectors -> 4
      )
    )
    assertEquals(r.stores.map(_.roles.size), Vector(12, 12, 16, 2))
    assertEquals(
      r.stores.find(_.targetStore == TargetStore.DynamoDb).map(_.declaredRows),
      Some(1302L)
    )
    assertEquals(
      r.stores.find(_.targetStore == TargetStore.S3Vectors).map(_.declaredRows),
      Some(249L)
    )
    assertEquals(r.verified.map(_.runId), Some(RunId))
    assertEquals(r.verified.map(_.loads.size), Some(4))
    assertEquals(r.deferred, DeferredCheck.values.toVector, "every deferred check is listed")
    assertEquals(
      r.verified.map(_.passedChecks),
      Some(
        Map(
          TargetStore.NeptuneBulkLoad -> Set[DeferredCheck](
            DeferredCheck.RowContracts,
            DeferredCheck.GraphIds
          )
        )
      ),
      "SE-P7-05: the Neptune CSV row contracts and graph IDs pass, for that store only"
    )
    assertEquals(stores.writes, 0, "verification itself writes nothing")
    TargetStore.values.foreach { t =>
      assert(stores.load(r, t).isLeft, s"structurally valid is not accepted for ${t.tag}")
    }
    assertEquals(stores.writes, 0, "the gate wrote nothing")
  }

  test("verifying twice gives an identical report") {
    val a = verify(runDir(run1Root), loadDir(run1Root))
    val b = verify(runDir(run1Root), loadDir(run1Root))
    assertEquals(a, b)
    withCopy { (run, load) =>
      assertEquals(verify(run, load), a, "a byte-identical copy gives the same report")
      Files.delete(run.resolve("metrics/part-00001.parquet"))
      assertEquals(verify(run, load), verify(run, load), "refusals are deterministic too")
    }
  }

  // ------------------------------------------------------------------ refusals

  test("missing _SUCCESS: refused, and nothing else is read") {
    withCopy { (run, load) =>
      Files.delete(run.resolve("_SUCCESS"))
      val r = refusedBy(run, load, VerificationCheck.SuccessMarker, "no _SUCCESS marker")
      assertEquals(r.rejections.size, 1)
      assertEquals(r.runId, None, "_parts.json was not read")
      // SE-P7-04 n4: recording sources prove it: one listing of the run, no read at all.
      val runSource = recording(run)
      val loadSource = recording(load, loadBounds)
      val again = LoadVerifier.verify(runSource, loadSource, new Stores().ledger)
      assertEquals(again, r)
      assertEquals(runSource.listings, 1)
      assertEquals(runSource.reads.toVector, Vector.empty)
      assertEquals(loadSource.listings, 0)
      assertEquals(loadSource.reads.toVector, Vector.empty)
    }
  }

  test("every file is read exactly once during verification (n1)") {
    val runSource = recording(runDir(run1Root))
    val loadSource = recording(loadDir(run1Root), loadBounds)
    val r = LoadVerifier.verify(runSource, loadSource, new Stores().ledger)
    assert(r.structurallyValid)
    val listed = runSource.listFiles().toOption.get
    runSource.reads.clear()
    val again = LoadVerifier.verify(runSource, loadSource, new Stores().ledger)
    assertEquals(again, r)
    assertEquals(
      runSource.reads.groupBy(identity).view.mapValues(_.size).toMap,
      listed.map(_ -> 1).toMap,
      "_parts.json, _run-manifest.json and every other listed file read exactly once"
    )
  }

  test("a missing part is refused") {
    withCopy { (run, load) =>
      Files.delete(run.resolve("edges/part-00001.parquet"))
      refusedBy(run, load, VerificationCheck.PartIntegrity, "listed file missing")
    }
  }

  test("an extra or unknown part is refused") {
    withCopy { (run, load) =>
      Files.copy(
        run.resolve("metrics/part-00000.parquet"),
        run.resolve("metrics/part-00002.parquet")
      )
      refusedBy(run, load, VerificationCheck.PartIntegrity, "extra file not listed")
    }
    withCopy { (run, load) =>
      Files.createDirectories(run.resolve("unknown"))
      write(run.resolve("unknown/part-00000.csv"), "~id,~label\n")
      refusedBy(run, load, VerificationCheck.PartIntegrity, "files[unknown/part-00000.csv]")
    }
  }

  test("a truncated part is refused (byte count and SHA-256)") {
    withCopy { (run, load) =>
      val p = run.resolve("researcher-vertices/part-00001.csv")
      val b = bytes(p)
      Files.write(p, b.take(b.length - 10), StandardOpenOption.TRUNCATE_EXISTING)
      refusedBy(run, load, VerificationCheck.PartIntegrity, s"byteCount: listed ${b.length}")
      refusedBy(run, load, VerificationCheck.PartIntegrity, "sha256: listed")
    }
  }

  test("one flipped byte is refused (same byte count, different SHA-256)") {
    withCopy { (run, load) =>
      val p = run.resolve("summary-inputs/part-00000.parquet")
      val b = bytes(p)
      b(b.length / 2) = (b(b.length / 2) ^ 0x01).toByte
      Files.write(p, b, StandardOpenOption.TRUNCATE_EXISTING)
      val r = refusedBy(run, load, VerificationCheck.PartIntegrity, "sha256: listed")
      assert(!r.rejections.exists(_.error.message.contains("byteCount")))
    }
  }

  test("a wrong sha256 or byte count in a load manifest item is refused") {
    withCopy { (run, load) =>
      rewrite(load, TargetStore.S3) { m =>
        m.copy(items =
          m.items.map(i =>
            if (i.path == "graph-nodes/part-00000.parquet") i.copy(sha256 = "0" * 64) else i
          )
        )
      }
      refusedBy(
        run,
        load,
        VerificationCheck.LoadManifestFile,
        "differs from its part manifest entry"
      )
    }
    withCopy { (run, load) =>
      rewrite(load, TargetStore.DynamoDb) { m =>
        m.copy(items =
          m.items.map(i =>
            if (i.path == "metrics/part-00000.parquet") i.copy(byteCount = i.byteCount + 1) else i
          )
        )
      }
      refusedBy(
        run,
        load,
        VerificationCheck.LoadManifestFile,
        "differs from its part manifest entry"
      )
    }
  }

  test("a wrong sha256 or byte count in _parts.json is refused") {
    withCopy { (run, load) =>
      val pm = parts(run)
      val changed = pm.copy(files =
        pm.files.map(f =>
          if (f.path == "coreness/part-00000.parquet") f.copy(byteCount = f.byteCount + 1) else f
        )
      )
      write(run.resolve("_parts.json"), Json.write(changed) + "\n")
      refusedBy(run, load, VerificationCheck.PartIntegrity, "coreness/part-00000.parquet")
      refusedBy(
        run,
        load,
        VerificationCheck.LoadManifestFile,
        "the observed _parts.json has sha256"
      )
    }
  }

  test("a wrong partManifestSha256 in a load manifest is refused") {
    withCopy { (run, load) =>
      rewrite(load, TargetStore.S3Vectors)(_.copy(partManifestSha256 = TestData.hex("other")))
      refusedBy(
        run,
        load,
        VerificationCheck.LoadManifestFile,
        "the observed _parts.json has sha256"
      )
    }
  }

  test("an unknown role is refused") {
    withCopy { (run, load) =>
      rewrite(load, TargetStore.DynamoDb) { m =>
        m.copy(items =
          m.items.map(i => if (i.targetRole == "coreness") i.copy(targetRole = "kcore") else i)
        )
      }
      refusedBy(run, load, VerificationCheck.LoadManifestFile, "'kcore' is not a dynamodb role")
    }
  }

  test("an extra role (a part the store does not load) is refused") {
    withCopy { (run, load) =>
      val nodes = manifest(load, TargetStore.S3).items.filter(_.targetRole == "graph-nodes")
      rewrite(load, TargetStore.DynamoDb) { m =>
        m.copy(items = (m.items ++ nodes).sortBy(_.path))
      }
      refusedBy(
        run,
        load,
        VerificationCheck.LoadManifestFile,
        "'graph-nodes' is not a dynamodb role"
      )
    }
  }

  test("a swapped Neptune role is refused (dataset directory and header)") {
    withCopy { (run, load) =>
      rewrite(load, TargetStore.NeptuneBulkLoad) { m =>
        m.copy(items =
          m.items.map(i =>
            if (i.path == "researcher-vertices/part-00001.csv") i.copy(targetRole = "work-vertices")
            else i
          )
        )
      }
      refusedBy(
        run,
        load,
        VerificationCheck.RoleDataset,
        "this part is in dataset researcher-vertices"
      )
      refusedBy(run, load, VerificationCheck.NeptuneHeader, "NeptuneCsv layout of work-vertices")
    }
    withCopy { (run, load) =>
      // Venue and Topic share a header, so only the dataset rule catches this swap.
      rewrite(load, TargetStore.NeptuneBulkLoad) { m =>
        m.copy(items =
          m.items.map(i =>
            if (i.path == "venue-vertices/part-00001.csv") i.copy(targetRole = "topic-vertices")
            else i
          )
        )
      }
      refusedBy(run, load, VerificationCheck.RoleDataset, "this part is in dataset venue-vertices")
    }
  }

  test("a wrong record schema version is refused") {
    withCopy { (run, load) =>
      rewrite(load, TargetStore.DynamoDb) { m =>
        m.copy(items =
          m.items.map(i =>
            if (i.targetRole == "researcher-metrics")
              i.copy(recordSchemaVersion = SchemaVersions.ResearcherMetrics)
            else i
          )
        )
      }
      refusedBy(
        run,
        load,
        VerificationCheck.LoadManifestFile,
        "differs from its part manifest entry"
      )
      refusedBy(
        run,
        load,
        VerificationCheck.LoadManifestFile,
        "holds scholarchain.researcher-metrics.v2 records"
      )
    }
    withCopy { (run, load) =>
      write(
        load.resolve("s3.json"),
        text(load.resolve("s3.json")).replace("store-load-manifest.v1", "store-load-manifest.v9")
      )
      refusedBy(run, load, VerificationCheck.LoadManifestFile, "unsupported schemaVersion")
    }
  }

  test("a malformed or missing load manifest is refused") {
    withCopy { (run, load) =>
      write(load.resolve("dynamodb.json"), "{not json\n")
      refusedBy(run, load, VerificationCheck.LoadManifestFile, "malformed JSON")
    }
    withCopy { (run, load) =>
      Files.delete(load.resolve("s3-vectors.json"))
      refusedBy(run, load, VerificationCheck.LoadManifestFile, "no load manifest for s3-vectors")
    }
    withCopy { (run, load) =>
      write(load.resolve("s3.json"), text(load.resolve("s3.json")) + "\n")
      refusedBy(run, load, VerificationCheck.LoadManifestFile, "not one LF-terminated row")
    }
    withCopy { (run, load) =>
      Files.copy(
        load.resolve("s3.json"),
        load.resolve("dynamodb.json"),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING
      )
      refusedBy(run, load, VerificationCheck.LoadManifestFile, "holds the s3 load manifest")
    }
    withCopy { (run, load) =>
      write(load.resolve("opensearch.json"), "{}\n")
      refusedBy(
        run,
        load,
        VerificationCheck.LoadManifestSet,
        "not the load manifest of a target store"
      )
    }
  }

  test("a malformed or missing _parts.json or run manifest is refused") {
    withCopy { (run, load) =>
      write(run.resolve("_parts.json"), "{\"schemaVersion\":")
      refusedBy(run, load, VerificationCheck.PartManifestFile, "malformed JSON")
    }
    withCopy { (run, load) =>
      Files.delete(run.resolve("_parts.json"))
      refusedBy(run, load, VerificationCheck.PartManifestFile, "no _parts.json")
    }
    withCopy { (run, load) =>
      Files.delete(run.resolve("_run-manifest.json"))
      refusedBy(run, load, VerificationCheck.RunManifestFile, "no _run-manifest.json")
      refusedBy(run, load, VerificationCheck.PartIntegrity, "files[_run-manifest.json]")
    }
    withCopy { (run, load) =>
      write(
        run.resolve("_run-manifest.json"),
        text(run.resolve("_run-manifest.json")).replace("\"attempt\":1", "\"attempt\":2")
      )
      refusedBy(run, load, VerificationCheck.RunManifestFile, "the run manifest gives run:")
    }
  }

  test("a CSV record count different from the declared rows is refused") {
    withCopy { (run, load) =>
      val pm = parts(run)
      val changed = pm.copy(files =
        pm.files.map(f =>
          if (f.path == "topic-vertices/part-00001.csv") f.copy(rowCount = f.rowCount + 1) else f
        )
      )
      write(run.resolve("_parts.json"), Json.write(changed) + "\n")
      refusedBy(run, load, VerificationCheck.CsvRecords, "rowCount: listed 31, counted 30")
    }
  }

  test("a symbolic-link part is refused") {
    withCopy { (run, load) =>
      val p = run.resolve("work-vertices/part-00001.csv")
      val elsewhere = run.getParent.resolve("elsewhere.csv")
      Files.move(p, elsewhere)
      Files.createSymbolicLink(p, elsewhere)
      refusedBy(run, load, VerificationCheck.SourceListing, "a symbolic link is never followed")
    }
  }

  test("source bounds refuse an oversized run before any content is read") {
    val tiny = SourceBounds.of(10, 8L * 1024 * 1024, 2).toOption.get
    val stores = new Stores
    val r = LoadVerifier.verify(
      source(runDir(run1Root), tiny),
      source(loadDir(run1Root), loadBounds),
      stores.ledger
    )
    assertEquals(r.rejections.map(_.check), Vector(VerificationCheck.SourceListing))
    assertEquals(stores.writes, 0)
  }

  // ------------------------------------------------------------------ one publication per run ID (R1)

  /** The gate refuses every store today (m1), so the test records the publication row itself. */
  private def recordRun1Publication(stores: Stores): Unit = {
    val r = verify(runDir(run1Root), loadDir(run1Root), stores)
    val v = r.verified.getOrElse(fail("run 1 is structurally valid"))
    assertEquals(
      stores.record(v.runId, v.partManifestSha256, v.load(TargetStore.S3).get.loadId),
      Right(PutOutcome.Written)
    )
    assertEquals(stores.kv.counts.written, 1, "run-1 publication recorded")
  }

  test("the run-2 _parts.json (same run ID, other bytes) is refused once run 1 is recorded") {
    val stores = new Stores
    recordRun1Publication(stores)
    val before = stores.writes
    withCopy { (run, load) =>
      Files.copy(
        runDir(run2Root).resolve("_parts.json"),
        run.resolve("_parts.json"),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING
      )
      val r = LoadVerifier.verify(source(run), source(load, loadBounds), stores.ledger)
      assertEquals(r.runId, Some(RunId))
      assertEquals(r.partManifestSha256, Some(Run2PartsSha256))
      val conflicts = r.rejections.filter(_.check == VerificationCheck.Publication)
      assertEquals(conflicts.size, 1)
      conflicts.head.error match {
        case StoreError.Conflict(d) =>
          assert(d.contains(PartsSha256) && d.contains(Run2PartsSha256), d)
        case other => fail(s"expected Conflict, got $other")
      }
      assert(stores.load(r).isLeft)
      assertEquals(stores.writes, before, "no write after the conflicting publication")
    }
  }

  test("a rerun of the recorded publication verifies as same-as-recorded") {
    val stores = new Stores
    recordRun1Publication(stores)
    val r = verify(runDir(run1Root), loadDir(run1Root), stores)
    assert(r.structurallyValid)
    assertEquals(r.publication, Some(PublicationStatus.SameAsRecorded))
  }

  test("an unreadable ledger fails closed") {
    val failing = new KeyValueReader {
      def get(table: TableRole, key: PrimaryKey) = Left(StoreError.Transient("throttled"))
      def query(table: TableRole, q: scholarchain.storage.port.KeyQuery) = Right(Vector.empty)
    }
    val r =
      LoadVerifier.verify(source(runDir(run1Root)), source(loadDir(run1Root), loadBounds), failing)
    assertEquals(
      r.rejections.map(j => (j.check, j.error)),
      Vector((VerificationCheck.Publication, StoreError.Transient("throttled")))
    )
    assert(!r.structurallyValid)
  }
}
