package scholarchain.tests.storage

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scholarchain.core.TargetStore
import scholarchain.storage.{Fixtures, OpeningFixture, TestData}
import scholarchain.storage.OpeningFixture.{World, config, sources}
import scholarchain.storage.fake.SyntheticTestEmbedding
import scholarchain.storage.load.*
import scholarchain.storage.model.{
  EmbeddingWorkManifest,
  ItemCodecs,
  StoreError,
  TableKeys,
  TableRole,
  Zone
}

import StorageAdversarial.*

/**
 * TE-P7-01 case 1 (checklist L1, T1): corrupt or missing parts of the analysis run, through the
 * public [[OpeningLoad.run]] only, on temporary copies of the run-1 fixture. Each case asserts the
 * exact verification check, a refusal of all four stores and zero write calls on every store.
 */
class PartIntegrityAdversarialSuite extends munit.FunSuite {

  private val allowedIntegrity = Set(VerificationCheck.PartIntegrity)

  private def load(run: Path, loads: Path): (World, OpeningLoadResult) = {
    val w = new World
    (w, OpeningFixture.run(w, sources(run = run, loads = loads)))
  }

  private def integrityMessageNames(r: OpeningLoadReport, path: String): Boolean =
    r.rejections.exists(x =>
      x.check == VerificationCheck.PartIntegrity &&
        (x.subject == path || x.error.message.contains(path))
    )

  test("truncated CSV part: part-integrity names the part, refused everywhere, zero writes") {
    Fixtures.withCopy { (run, loads) =>
      val path = "coauthored-with-edges/part-00001.csv"
      val f = run.resolve(path)
      val b = Files.readAllBytes(f)
      Files.write(f, b.take(b.length / 2))
      val (w, res) = load(run, loads)
      assertRefusedEverywhere(res, w, VerificationCheck.PartIntegrity, allowedIntegrity)
      assert(integrityMessageNames(res.report, path), show(res.report))
    }
  }

  test("one flipped byte in a Neptune CSV part: part-integrity, refused everywhere, zero writes") {
    Fixtures.withCopy { (run, loads) =>
      val path = "researcher-vertices/part-00000.csv"
      OpeningFixture.flipByte(run.resolve(path), 200)
      val (w, res) = load(run, loads)
      assertRefusedEverywhere(res, w, VerificationCheck.PartIntegrity, allowedIntegrity)
      assert(integrityMessageNames(res.report, path), show(res.report))
    }
  }

  test("a deleted Parquet part: part-integrity names it, refused everywhere, zero writes") {
    Fixtures.withCopy { (run, loads) =>
      val path = "communities/part-00001.parquet"
      Files.delete(run.resolve(path))
      val (w, res) = load(run, loads)
      assertRefusedEverywhere(res, w, VerificationCheck.PartIntegrity, allowedIntegrity)
      assert(integrityMessageNames(res.report, path), show(res.report))
    }
  }

  test("an extra unlisted part in a dataset directory: part-integrity, zero writes") {
    Fixtures.withCopy { (run, loads) =>
      val extra = "metrics/part-00099.parquet"
      Files.copy(run.resolve("metrics/part-00000.parquet"), run.resolve(extra))
      val (w, res) = load(run, loads)
      assertRefusedEverywhere(res, w, VerificationCheck.PartIntegrity, allowedIntegrity)
      assert(integrityMessageNames(res.report, extra), show(res.report))
    }
  }

  test("an extra unlisted file at the run root: part-integrity, zero writes") {
    Fixtures.withCopy { (run, loads) =>
      val extra = "README.txt"
      Files.write(run.resolve(extra), "ignore the manifest\n".getBytes(StandardCharsets.UTF_8))
      val (w, res) = load(run, loads)
      assertRefusedEverywhere(res, w, VerificationCheck.PartIntegrity, allowedIntegrity)
      assert(integrityMessageNames(res.report, extra), show(res.report))
    }
  }

  test("an extra file in the load-manifest directory: load-manifest-set, zero writes") {
    Fixtures.withCopy { (run, loads) =>
      val extra = "s3-vectors-extra.json"
      Files.copy(loads.resolve("s3-vectors.json"), loads.resolve(extra))
      val (w, res) = load(run, loads)
      assertRefusedEverywhere(
        res,
        w,
        VerificationCheck.LoadManifestSet,
        Set(VerificationCheck.LoadManifestSet)
      )
      assertEquals(
        res.report.rejections.map(x => (x.check, x.subject)),
        Vector((VerificationCheck.LoadManifestSet, extra))
      )
    }
  }

  test("a missing _SUCCESS marker: exactly one success-marker rejection, zero writes") {
    Fixtures.withCopy { (run, loads) =>
      Files.delete(run.resolve("_SUCCESS"))
      val (w, res) = load(run, loads)
      assertEquals(
        res.report.rejections.map(x => (x.check, x.subject)),
        Vector((VerificationCheck.SuccessMarker, "_SUCCESS"))
      )
      assertEquals(VerificationCheck.SuccessMarker.tag, "success-marker")
      assertRefusedEverywhere(
        res,
        w,
        VerificationCheck.SuccessMarker,
        Set(VerificationCheck.SuccessMarker)
      )
      assertEquals(res.report.runId, None, "nothing else is read without _SUCCESS")
    }
  }

  test("_parts.json changed after verification: the s3 load fails closed, no run file stored") {
    val honest = Fixtures.source(Fixtures.runDir(Fixtures.run1Root))
    // The verifier reads _parts.json once; every later read (the s3 loader's re-read of the run
    // file it stores) sees other bytes, as if the file were replaced on disk after verification.
    val tampering =
      new TamperingSource(
        honest,
        "_parts.json",
        _ >= 2,
        b => b ++ " ".getBytes(StandardCharsets.UTF_8)
      )
    val w = new World
    val res = OpeningLoad.run(sources().copy(run = tampering), config, w.targets())
    val r = res.report
    println("TE-P7-01 _parts.json replaced after verification:\n" + show(r))
    assertEquals(r.rejections, Vector.empty, "verification itself saw the published bytes")
    assert(tampering.tampered >= 1, "the s3 loader re-read _parts.json")
    val s3 = r.store(TargetStore.S3)
    assertEquals(s3.status, StoreLoadStatus.Failed, show(r))
    assert(
      s3.error.exists {
        case StoreError.Corrupt(d) => d.startsWith("_parts.json: changed since verification")
        case _ => false
      },
      s3.error.toString
    )
    assertEquals(s3.written, 0L)
    // No object of the s3 load (parts or run files) and no registry row of the graph zone.
    val graphZone = w.objectStore
      .list("test-data-bucket", "graph/", 1000)
      .fold(e => fail(e.toString), identity)
    assertEquals(graphZone, Vector.empty, "the s3 loader wrote no object")
    // TE-P8-01 (SE-P8-01 item 3): the vector load alone wrote, all under semantic-context/: the
    // 249 context texts plus exactly one embedding work manifest, and one registry row each.
    val semantic = w.objectStore
      .list("test-data-bucket", "semantic-context/", 1000)
      .fold(e => fail(e.toString), identity)
    assertEquals(w.objects.counts.written, 250, "249 context texts + 1 work manifest, nothing else")
    assertEquals(w.objectStore.size, 250, "no object outside semantic-context/")
    assertEquals(semantic.size, 250)
    val Hex64 = "[0-9a-f]{64}"
    val TextKey = s"semantic-context/loads/$Hex64/text/$Hex64\\.txt".r
    val mv = SyntheticTestEmbedding.ModelVersion
    val ManifestKey =
      s"semantic-context/loads/$Hex64/embedding-work/model=${java.util.regex.Pattern.quote(mv)}/manifest-$Hex64\\.json".r
    val (texts, others) = semantic.partition(m => TextKey.matches(m.ref.key))
    assertEquals(texts.size, 249, "the 249 vector context texts")
    assertEquals(others.size, 1, s"exactly one non-text object: ${others.map(_.ref.key)}")
    val manifestMeta = others.head
    assert(ManifestKey.matches(manifestMeta.ref.key), manifestMeta.ref.key)
    val manifestBody = w.objectStore
      .get(manifestMeta.ref)
      .fold(e => fail(e.toString), identity)
      .getOrElse(fail("no manifest body"))
      ._2
    val manifest = EmbeddingWorkManifest.parse(manifestBody).fold(e => fail(e.toString), identity)
    assertEquals((manifest.runId, manifest.modelVersion), (Fixtures.RunId, mv))
    assertEquals(manifest.items.size, 249, "one work item per context text")
    assertEquals(manifest.items.map(_.textS3Key).toSet, texts.map(_.ref.key).toSet)
    // The key is exactly S3Layout's content-addressed manifest key for this load and model.
    assertEquals(
      TestData.layout.embeddingWorkManifest(manifest.loadId, mv, manifestBody.sha256),
      Right(manifestMeta.ref)
    )
    // 249 + 1 registry rows: one per stored object, the extra one the manifest's.
    assertEquals(w.kvStore.itemCount(TableRole.ArtifactRegistry), 250)
    semantic.foreach { m =>
      val row = TableKeys.ArtifactRegistry
        .artifact(Fixtures.RunId, Zone.SemanticContext, m.ref.key)
        .flatMap(w.kvStore.get(TableRole.ArtifactRegistry, _))
        .flatMap(i =>
          ItemCodecs.ArtifactCodec.decode(i.getOrElse(fail(s"no registry row ${m.ref.key}")))
        )
        .fold(e => fail(e.toString), identity)
      assertEquals(row.sha256, m.sha256, m.ref.key)
      if (m == manifestMeta)
        assertEquals(row.recordSchemaVersion, Some(EmbeddingWorkManifest.SchemaVersion))
    }
    assert(!r.complete)
    assertEquals(res.runtime, None, "no runtime view while s3 is not loaded")
    // The honest source resumes: s3 alone loads, the other stores are skipped.
    val again = OpeningLoad.run(sources(), config, w.targets())
    assertEquals(
      again.report.stores.map(s => (s.target, s.status, s.written)),
      Vector(
        (TargetStore.NeptuneBulkLoad, StoreLoadStatus.Skipped, 0L),
        (TargetStore.DynamoDb, StoreLoadStatus.Skipped, 0L),
        (TargetStore.S3, StoreLoadStatus.Loaded, 31L),
        (TargetStore.S3Vectors, StoreLoadStatus.Skipped, 0L)
      )
    )
    assert(again.runtime.isDefined)
  }

  test("a Parquet part replaced after verification: dynamodb and s3 fail closed, zero writes") {
    val path = "analysis-windows/part-00000.parquet"
    var armed = false
    val tampering = new TamperingSource(
      Fixtures.source(Fixtures.runDir(Fixtures.run1Root)),
      path,
      _ => armed,
      flipMiddle
    )
    val w = new World
    // Armed by the first graph write: verification (every Parquet read) is over by then, and the
    // neptune loader reads CSV only.
    val arming = new scholarchain.storage.port.GraphReader
      with scholarchain.storage.port.GraphWriter {
      def run(q: scholarchain.storage.model.BoundQuery) = w.graph.run(q)
      def storedVertex(id: String) = w.graph.storedVertex(id)
      def storedEdge(id: String) = w.graph.storedEdge(id)
      def write(x: scholarchain.storage.model.BoundGraphWrite) = { armed = true; w.graph.write(x) }
    }
    val res = OpeningLoad.run(
      sources().copy(run = tampering),
      config,
      w.targets().copy(graph = arming)
    )
    val r = res.report
    println("TE-P7-01 Parquet part replaced after verification:\n" + show(r))
    assertEquals(r.rejections, Vector.empty)
    assert(tampering.tampered >= 2, s"both loaders re-read the part (${tampering.tampered})")
    Vector(TargetStore.DynamoDb, TargetStore.S3).foreach { t =>
      val s = r.store(t)
      assertEquals(s.status, StoreLoadStatus.Failed, t.tag)
      assert(
        s.error.exists {
          case StoreError.Corrupt(d) => d.startsWith(s"$path: changed since verification")
          case _ => false
        },
        s"${t.tag}: ${s.error}"
      )
      assertEquals(s.written, 0L, t.tag)
    }
    assertEquals(w.kvStore.itemCount(TableRole.ResearcherMetricCache), 0, "no analysis row")
    assertEquals(
      w.objectStore.list("test-data-bucket", "graph/", 1000).map(_.size),
      Right(0),
      "no s3 object"
    )
    assertEquals(r.store(TargetStore.NeptuneBulkLoad).status, StoreLoadStatus.Loaded)
    assertEquals(r.store(TargetStore.S3Vectors).status, StoreLoadStatus.Loaded)
    assertEquals(res.runtime, None)
  }
}
