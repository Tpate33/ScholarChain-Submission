package scholarchain.storage

import scala.compiletime.testing.typeCheckErrors

import scholarchain.core.TargetStore
import scholarchain.storage.fake.{InMemoryKeyValueStore, InMemoryObjectStore, RecordingObjectStore}
import scholarchain.storage.load.*
import scholarchain.storage.model.*
import scholarchain.storage.port.{KeyValueReader, RunSource}
import Fixtures.*

/**
 * SE-P7-04 (CR-P7-03 m1, m2 and the Mirror check) and SE-P7-05 (A1, A2, A6): structural validity is
 * not load acceptance; the gate refuses each store while its deferred checks are outstanding for
 * that store; part bytes are reachable only through a [[LoadPermit]] (the re-checking reads of a
 * [[VerifiedRun]] are package-private, and their behaviour is tested in package `load`,
 * `load.VerifiedReadSuite`); and no constructor surface is reachable outside package `load`.
 */
class LoadGateSuite extends munit.FunSuite {

  private def ledger: KeyValueReader =
    KeyValueReader.readOnly(new InMemoryKeyValueStore(100))

  private def verified(src: RunSource, loads: RunSource): (VerificationReport, VerifiedRun) = {
    val r = LoadVerifier.verify(src, loads, ledger)
    assertEquals(r.rejections, Vector.empty)
    (r, r.verified.getOrElse(fail("structurally valid")))
  }

  private def refused(errors: List[scala.compiletime.testing.Error], fragment: String): Unit = {
    assert(errors.nonEmpty, "the snippet must not compile")
    assert(
      errors.exists(_.message.contains(fragment)),
      s"expected '$fragment' in:\n" + errors.map(_.message).mkString("\n")
    )
  }

  // ------------------------------------------------------------------ m1: deferred checks gate writes

  test("the store to deferred-check mapping, explicitly (SE-P7-06: analysis-recompute)") {
    import DeferredCheck.*
    assertEquals(
      TargetStore.values.toVector.map(t => t -> DeferredCheck.appliesTo(t)).toMap,
      Map(
        // SE-P7-07 (CR-P7-08 M2): neptune also needs the recomputation from the upstream runs.
        TargetStore.NeptuneBulkLoad -> Vector(
          RowContracts,
          GraphIds,
          GraphIdParity,
          EvidenceTrace,
          AnalysisRecompute,
          CanonicalTables
        ),
        TargetStore.DynamoDb -> Vector(
          ParquetRows,
          RowContracts,
          EvidenceTrace,
          AnalysisRecompute,
          PathsRunChain
        ),
        TargetStore.S3 -> Vector(
          ParquetRows,
          RowContracts,
          GraphIdParity,
          EvidenceTrace,
          SummaryEvidence,
          AnalysisRecompute,
          CanonicalTables,
          PathsRunChain
        ),
        TargetStore.S3Vectors -> Vector(ParquetRows, RowContracts, SummaryEvidence)
      )
    )
    assertEquals(
      DeferredCheck.values.toVector.map(_.tag),
      Vector(
        "parquet-rows",
        "row-contracts",
        "graph-ids",
        "graph-id-parity",
        "evidence-trace",
        "summary-evidence",
        "analysis-recompute",
        "canonical-tables",
        "paths-run-chain"
      )
    )
    assertEquals(
      TargetStore.values.toVector.flatMap(DeferredCheck.appliesTo).toSet,
      DeferredCheck.values.toSet,
      "every deferred check gates at least one store"
    )
  }

  test("parquet-rows gates exactly the fixture stores that load Parquet parts") {
    val (_, v) = verified(source(runDir(run1Root)), source(loadDir(run1Root), loadBounds))
    TargetStore.values.foreach { t =>
      val hasParquet =
        v.load(t).get.items.exists(_.format == scholarchain.core.PartFormat.Parquet)
      assertEquals(
        DeferredCheck.appliesTo(t).contains(DeferredCheck.ParquetRows),
        hasParquet,
        t.tag
      )
    }
  }

  test("summary-evidence gates exactly the fixture stores that load summary inputs (A1)") {
    val (_, v) = verified(source(runDir(run1Root)), source(loadDir(run1Root), loadBounds))
    TargetStore.values.foreach { t =>
      // semantic-summary-input.v1 and entity-summary-input.v1 (checklist "Inputs observed")
      val loadsSummaries =
        v.load(t).get.items.exists(_.recordSchemaVersion.contains("summary-input."))
      assertEquals(
        DeferredCheck.appliesTo(t).contains(DeferredCheck.SummaryEvidence),
        loadsSummaries,
        t.tag
      )
    }
  }

  /**
   * What the real fixture has open per store when verified WITHOUT a verification context (steps
   * 1-11 only; the record-level checks with a context are in [[RecordChecksSuite]]).
   */
  private val stillOpen: Map[TargetStore, Vector[DeferredCheck]] = {
    import DeferredCheck.*
    Map(
      TargetStore.NeptuneBulkLoad -> Vector(
        GraphIdParity,
        EvidenceTrace,
        AnalysisRecompute,
        CanonicalTables
      ),
      TargetStore.DynamoDb -> Vector(
        ParquetRows,
        RowContracts,
        EvidenceTrace,
        AnalysisRecompute,
        PathsRunChain
      ),
      TargetStore.S3 -> Vector(
        ParquetRows,
        RowContracts,
        GraphIdParity,
        EvidenceTrace,
        SummaryEvidence,
        AnalysisRecompute,
        CanonicalTables,
        PathsRunChain
      ),
      TargetStore.S3Vectors -> Vector(ParquetRows, RowContracts, SummaryEvidence)
    )
  }

  test("a structurally valid run may not proceed to any store while deferred checks are open") {
    val (r, v) = verified(source(runDir(run1Root)), source(loadDir(run1Root), loadBounds))
    assert(r.structurallyValid)
    TargetStore.values.foreach { t =>
      val objects = new RecordingObjectStore(new InMemoryObjectStore(100))
      var called = false
      val out = LoadGate.proceed(r, t) { p =>
        called = true
        objects.putCreateOnce(
          ObjectRef("bucket", s"x/${t.tag}"),
          p.readItem(p.items.head.path).toOption.get,
          "application/octet-stream"
        )
      }
      val open = stillOpen(t)
      assertEquals(r.outstandingFor(t), open, t.tag)
      assertEquals(
        v.passedFor(t),
        DeferredCheck.appliesTo(t).filterNot(open.contains).toSet,
        s"${t.tag}: passed checks are per store"
      )
      assertEquals(
        out,
        Left(
          Vector(
            Rejection(
              VerificationCheck.DeferredChecks,
              t.tag,
              StoreError.Refused(
                s"deferred checks not yet performed and passed for ${t.tag}: " +
                  open.map(_.tag).mkString(", ")
              )
            )
          )
        ),
        t.tag
      )
      assert(!called, s"the writer was never called for ${t.tag}")
      assertEquals(objects.counts.calls, 0, t.tag)
      assert(!r.acceptedFor(t), t.tag)
      assertEquals(LoadGate.refusal(r, t), out.left.toOption, t.tag)
    }
  }

  test("a row-contracts pass for neptune says nothing about the other stores") {
    val (_, v) = verified(source(runDir(run1Root)), source(loadDir(run1Root), loadBounds))
    assert(v.passedFor(TargetStore.NeptuneBulkLoad).contains(DeferredCheck.RowContracts))
    Vector(TargetStore.DynamoDb, TargetStore.S3, TargetStore.S3Vectors).foreach { t =>
      assertEquals(v.passedFor(t), Set.empty[DeferredCheck], t.tag)
      assert(DeferredCheck.appliesTo(t).contains(DeferredCheck.RowContracts), t.tag)
    }
  }

  test("outstanding is the applicable checks minus the passed ones") {
    TargetStore.values.foreach { t =>
      val all = DeferredCheck.appliesTo(t)
      assertEquals(DeferredCheck.outstanding(t, Set.empty), all)
      assertEquals(DeferredCheck.outstanding(t, DeferredCheck.values.toSet), Vector.empty)
      assertEquals(DeferredCheck.outstanding(t, all.tail.toSet), Vector(all.head))
    }
  }

  test("F4: a hand-built report with neither rejection nor verified run is tagged not-verified") {
    val (r, _) = verified(source(runDir(run1Root)), source(loadDir(run1Root), loadBounds))
    val empty = r.copy(verified = None)
    TargetStore.values.foreach { t =>
      assertEquals(
        LoadGate.refusal(empty, t).map(_.map(j => (j.check, j.subject))),
        Some(Vector((VerificationCheck.NotVerified, "run"))),
        t.tag
      )
    }
    assertEquals(VerificationCheck.NotVerified.tag, "not-verified")
  }

  // ------------------------------------------------------------------ A2: no read surface but the permit

  test("A2: part bytes are not readable through a VerifiedRun outside package load") {
    val (r, v) = verified(source(runDir(run1Root)), source(loadDir(run1Root), loadBounds))
    val path = "venue-vertices/part-00001.csv"
    assertEquals(v.partManifest.files.count(_.path == path), 1, "a listed part")
    refused(typeCheckErrors("v.readVerified(path)"), "cannot be accessed")
    refused(typeCheckErrors("r.verified.get.readVerified(path)"), "cannot be accessed")
    refused(typeCheckErrors("v.readVerified(source(runDir(run1Root)), path)"), "readVerified")
    assert(typeCheckErrors("v.runId").isEmpty, "control: metadata stays readable")
    assert(r.verified.isDefined)
  }

  test("A2: a permit exposes no VerifiedRun") {
    refused(typeCheckErrors("(??? : LoadPermit).run"), "cannot be accessed")
    refused(typeCheckErrors("(??? : LoadPermit).run.readVerified(\"_parts.json\")"), "run")
    assert(typeCheckErrors("(??? : LoadPermit).readItem(\"a/b.csv\")").isEmpty, "control")
    assert(typeCheckErrors("(??? : LoadPermit).items").isEmpty, "control")
    assert(typeCheckErrors("(??? : LoadPermit).partManifestSha256").isEmpty, "control")
  }

  // ------------------------------------------------------------------ item 3: no constructor surface

  test("VerifiedRun and LoadPermit cannot be constructed outside package load (F5: exact texts)") {
    val (_, v) = verified(source(runDir(run1Root)), source(loadDir(run1Root), loadBounds))
    val src: RunSource = source(runDir(run1Root))
    assertEquals(v.runId, RunId)
    assert(src.listFiles().isRight)

    refused(
      typeCheckErrors(
        "new VerifiedRun(v.runId, v.partManifestSha256, v.partManifest, v.runManifest, v.loads, v.passedChecks, src, v.partManifestSchemaVersion)"
      ),
      "cannot be accessed"
    )
    refused(
      typeCheckErrors(
        "VerifiedRun(v.runId, v.partManifestSha256, v.partManifest, v.runManifest, v.loads, v.passedChecks, src, v.partManifestSchemaVersion)"
      ),
      "cannot be accessed"
    )
    refused(
      typeCheckErrors("summon[scala.deriving.Mirror.ProductOf[VerifiedRun]]"),
      "No given instance of type"
    )
    refused(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[VerifiedRun]].fromProduct((v.runId, v.partManifestSha256, v.partManifest, v.runManifest, v.loads, v.passedChecks, src))"
      ),
      "No given instance of type"
    )
    refused(typeCheckErrors("v.copy()"), "is not a member of")
    refused(typeCheckErrors("new LoadPermit(v, TargetStore.S3)"), "cannot be accessed")
    refused(typeCheckErrors("LoadPermit(v, TargetStore.S3)"), "cannot be accessed")
    refused(
      typeCheckErrors("summon[scala.deriving.Mirror.ProductOf[LoadPermit]]"),
      "No given instance of type"
    )
  }
}
