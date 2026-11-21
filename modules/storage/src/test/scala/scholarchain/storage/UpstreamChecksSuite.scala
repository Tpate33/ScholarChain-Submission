package scholarchain.storage

import scholarchain.core.*
import scholarchain.storage.fake.InMemoryKeyValueStore
import scholarchain.storage.load.*
import scholarchain.storage.port.KeyValueReader
import Fixtures.*

/**
 * SE-P7-07 items 2, 3 (CR-P7-08 M2, m2): the upstream-dependent checks performed by the PRODUCTION
 * verifier on the real fixtures, with the upstream read only by the production readers. Nothing is
 * marked passed by hand.
 */
class UpstreamChecksSuite extends munit.FunSuite {
  import DeferredCheck.*

  private lazy val report: VerificationReport =
    LoadVerifier.verify(
      source(runDir(run1Root)),
      source(loadDir(run1Root), loadBounds),
      KeyValueReader.readOnly(new InMemoryKeyValueStore(100)),
      Some(UpstreamFixture.context)
    )

  test("the real fixtures pass every upstream-dependent check that their inputs allow") {
    assertEquals(report.rejections.take(40).mkString("\n"), "")
    val v = report.verified.getOrElse(fail("verified"))
    assertEquals(
      TargetStore.values.toVector.map(t => t -> v.passedFor(t)).toMap,
      Map(
        TargetStore.NeptuneBulkLoad -> Set(
          RowContracts,
          GraphIds,
          GraphIdParity,
          EvidenceTrace,
          AnalysisRecompute,
          CanonicalTables
        ),
        TargetStore.DynamoDb -> Set(ParquetRows, RowContracts, EvidenceTrace, AnalysisRecompute),
        TargetStore.S3 -> Set(
          ParquetRows,
          RowContracts,
          GraphIdParity,
          EvidenceTrace,
          SummaryEvidence,
          AnalysisRecompute,
          CanonicalTables
        ),
        TargetStore.S3Vectors -> Set(ParquetRows, RowContracts, SummaryEvidence)
      )
    )
    // Open: only paths-run-chain, which needs the benchmark pair list (not in the fixtures).
    assertEquals(
      TargetStore.values.toVector.map(t => t -> report.outstandingFor(t)).toMap,
      Map(
        TargetStore.NeptuneBulkLoad -> Vector.empty,
        TargetStore.DynamoDb -> Vector(PathsRunChain),
        TargetStore.S3 -> Vector(PathsRunChain),
        TargetStore.S3Vectors -> Vector.empty
      )
    )
  }

  test("without the upstream, neptune is refused (fail closed, CR-P7-08 M2)") {
    val r = LoadVerifier.verify(
      source(runDir(run1Root)),
      source(loadDir(run1Root), loadBounds),
      KeyValueReader.readOnly(new InMemoryKeyValueStore(100)),
      Some(RunSettingsFixture.context)
    )
    assertEquals(r.rejections, Vector.empty)
    assertEquals(
      r.outstandingFor(TargetStore.NeptuneBulkLoad),
      Vector(AnalysisRecompute, CanonicalTables)
    )
    assert(!r.acceptedFor(TargetStore.NeptuneBulkLoad))
  }

  test(
    "item 6: the production gate opens for s3-vectors (its 4 summary-input parts; VectorLoad, SE-P7-09)"
  ) {
    val out = LoadGate.proceed(report, TargetStore.S3Vectors)(p =>
      Right(p.items.map(i => (i.path, p.readItem(i.path).map(_.sha256))))
    )
    val parts = out.fold(rs => fail(s"gate refused: $rs"), _.fold(e => fail(e.toString), identity))
    assertEquals(parts.size, 4)
    val v = report.verified.get
    parts.foreach { case (path, sha) =>
      assertEquals(
        sha,
        Right(v.load(TargetStore.S3Vectors).get.items.find(_.path == path).get.sha256)
      )
    }
    // dynamodb and s3 stay refused, naming paths-run-chain only.
    Vector(TargetStore.DynamoDb, TargetStore.S3).foreach { t =>
      val r = LoadGate.proceed(report, t)(_ => Right(()))
      assert(r.left.exists(_.head.error.message.endsWith("paths-run-chain")), r.toString)
    }
  }

  test("verifying with the upstream twice gives an identical report") {
    val again = LoadVerifier.verify(
      source(runDir(run1Root)),
      source(loadDir(run1Root), loadBounds),
      KeyValueReader.readOnly(new InMemoryKeyValueStore(100)),
      Some(UpstreamFixture.context)
    )
    assertEquals(again, report)
  }
}
