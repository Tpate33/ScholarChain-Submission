package scholarchain.storage.load

import scala.compiletime.testing.typeCheckErrors

import scholarchain.core.TargetStore
import scholarchain.storage.Fixtures.*
import scholarchain.storage.fake.InMemoryKeyValueStore
import scholarchain.storage.model.StoreError
import scholarchain.storage.port.{KeyValueReader, RunSource}

/**
 * Controls for LoadGateSuite, inside package `load` (SE-P7-04, SE-P7-05): the constructor and read
 * snippets refused outside the package compile here, so their refusal there is an access refusal
 * and nothing else; and the gate's pass branch, unreachable from real verification while a deferred
 * check is open for every store, is exercised with a hand-built [[VerifiedRun]].
 */
class LoadPackageControlSuite extends munit.FunSuite {

  private def report(): (VerificationReport, VerifiedRun, RunSource) = {
    val src = source(runDir(run1Root))
    val r = LoadVerifier.verify(
      src,
      source(loadDir(run1Root), loadBounds),
      KeyValueReader.readOnly(new InMemoryKeyValueStore(100))
    )
    (r, r.verified.getOrElse(fail("structurally valid")), src)
  }

  private def withPassed(
      v: VerifiedRun,
      passed: Map[TargetStore, Set[DeferredCheck]],
      src: RunSource
  ) =
    new VerifiedRun(
      v.runId,
      v.partManifestSha256,
      v.partManifest,
      v.runManifest,
      v.loads,
      passed,
      src,
      v.partManifestSchemaVersion
    )

  test("control: the constructor and read snippets compile inside package load") {
    val (_, v, src) = report()
    val path = "venue-vertices/part-00001.csv"
    assertEquals(
      typeCheckErrors(
        "new VerifiedRun(v.runId, v.partManifestSha256, v.partManifest, v.runManifest, v.loads, v.passedChecks, src, v.partManifestSchemaVersion)"
      ),
      Nil
    )
    assertEquals(
      typeCheckErrors(
        "VerifiedRun(v.runId, v.partManifestSha256, v.partManifest, v.runManifest, v.loads, v.passedChecks, src, v.partManifestSchemaVersion)"
      ),
      Nil
    )
    assertEquals(typeCheckErrors("new LoadPermit(v, TargetStore.S3)"), Nil)
    assertEquals(typeCheckErrors("LoadPermit(v, TargetStore.S3)"), Nil)
    assertEquals(typeCheckErrors("v.readVerified(path)"), Nil)
    assert(path.nonEmpty)
    // A plain class has no Mirror and no copy anywhere, including here.
    assert(typeCheckErrors("summon[scala.deriving.Mirror.ProductOf[VerifiedRun]]").nonEmpty)
    assert(typeCheckErrors("v.copy()").nonEmpty)
    // The permit's run is private even inside the package (only the class itself reads it).
    assert(typeCheckErrors("new LoadPermit(v, TargetStore.S3).run").nonEmpty)
  }

  test("the gate passes exactly when every applicable deferred check has passed for that store") {
    val (r, v, src) = report()
    TargetStore.values.foreach { t =>
      val all = DeferredCheck.appliesTo(t)
      val passing = r.copy(verified = Some(withPassed(v, Map(t -> all.toSet), src)))
      var permit: Option[LoadPermit] = None
      assertEquals(
        LoadGate.proceed(passing, t) { p =>
          permit = Some(p); Right(p.items.size)
        },
        Right(Right(v.load(t).get.items.size)),
        t.tag
      )
      assertEquals(permit.map(_.target), Some(t))
      assertEquals(permit.map(_.runId), Some(v.runId))
      assertEquals(permit.map(_.partManifestSha256), Some(v.partManifestSha256))
      assert(passing.acceptedFor(t), t.tag)

      val oneOpen = r.copy(verified = Some(withPassed(v, Map(t -> all.tail.toSet), src)))
      var called = false
      val refused = LoadGate.proceed(oneOpen, t) { _ =>
        called = true; Right(())
      }
      assert(!called, t.tag)
      assertEquals(
        refused.left.toOption.map(_.map(j => (j.check, j.subject))),
        Some(Vector((VerificationCheck.DeferredChecks, t.tag))),
        t.tag
      )
      assert(refused.left.toOption.get.head.error.message.endsWith(all.head.tag), t.tag)

      // Checks passed for every other store do not open this one.
      val others = TargetStore.values.toVector
        .filterNot(_ == t)
        .map(o => o -> DeferredCheck.values.toSet)
        .toMap
      val elsewhere = r.copy(verified = Some(withPassed(v, others, src)))
      assert(!elsewhere.acceptedFor(t), s"${t.tag}: passes recorded for other stores")
    }
  }

  test("the gate refuses a store the run has no load manifest for") {
    val (r, v, src) = report()
    val noS3 = new VerifiedRun(
      v.runId,
      v.partManifestSha256,
      v.partManifest,
      v.runManifest,
      v.loads.filterNot(_.targetStore == TargetStore.S3),
      TargetStore.values.toVector.map(_ -> DeferredCheck.values.toSet).toMap,
      src,
      v.partManifestSchemaVersion
    )
    val out = LoadGate.proceed(r.copy(verified = Some(noS3)), TargetStore.S3)(_ => Right(()))
    assertEquals(
      out.left.toOption.map(_.map(_.check)),
      Some(Vector(VerificationCheck.TargetLoad))
    )
  }

  test("A2: a permit reads only items of its own store's load, never another file") {
    val (_, v, _) = report()
    val neptune = new LoadPermit(v, TargetStore.NeptuneBulkLoad)
    assert(neptune.readItem("researcher-vertices/part-00000.csv").isRight)
    val notMine = Vector(
      "metrics/part-00000.parquet", // a dynamodb and s3 item
      "summary-inputs/part-00000.parquet", // an s3-vectors item
      "_run-manifest.json", // listed in _parts.json, never a load item
      "_parts.json",
      "_SUCCESS",
      "../load=x/neptune-bulk-load.json"
    )
    notMine.foreach { p =>
      neptune.readItem(p) match {
        case Left(StoreError.Refused(d)) =>
          assert(d.contains("not an item of the neptune-bulk-load load"), d)
        case other => fail(s"$p: expected Refused, got $other")
      }
    }
    val vectors = new LoadPermit(v, TargetStore.S3Vectors)
    assert(vectors.readItem("researcher-vertices/part-00000.csv").isLeft, "a neptune item")
    assertEquals(vectors.items, v.load(TargetStore.S3Vectors).get.items)
  }

  test("SE-P7-14 (CR-P7-16 n1): runFileSchema of _parts.json is the decoded manifest's version") {
    val (_, v, src) = report()
    // The fixture run publishes a v2 manifest: the verifier records v2.
    assertEquals(v.partManifestSchemaVersion, "scholarchain.part-manifest.v2")
    assertEquals(
      new LoadPermit(v, TargetStore.S3).runFileSchema("_parts.json"),
      Some("scholarchain.part-manifest.v2")
    )
    // A v1 _parts.json decodes as Left; its v2 view says v2, the recorded schema stays v1.
    val v1 = scholarchain.core.PartManifest(
      scholarchain.core.SchemaVersions.PartManifest,
      v.runId,
      Vector.empty
    )
    assertEquals(
      scholarchain.core.PartManifests.asV2(Left(v1)).schemaVersion,
      v.partManifestSchemaVersion
    )
    assertEquals(LoadVerifier.decodedSchemaVersion(Left(v1)), "scholarchain.part-manifest.v1")
    assertEquals(
      LoadVerifier.decodedSchemaVersion(Right(v.partManifest)),
      v.partManifest.schemaVersion
    )
    val fromV1 = new VerifiedRun(
      v.runId,
      v.partManifestSha256,
      v.partManifest,
      v.runManifest,
      v.loads,
      v.passedChecks,
      src,
      LoadVerifier.decodedSchemaVersion(Left(v1))
    )
    val s3 = new LoadPermit(fromV1, TargetStore.S3)
    assertEquals(s3.runFileSchema("_parts.json"), Some("scholarchain.part-manifest.v1"))
    assertEquals(s3.runFileSchema("_run-manifest.json"), Some("scholarchain.run-manifest.v1"))
    assertEquals(new LoadPermit(fromV1, TargetStore.DynamoDb).runFileSchema("_parts.json"), None)
    assertNotEquals(fromV1, v, "the recorded schema is part of the verified content")
  }

  test("A2: reads through a permit record no access to anything but its items") {
    val (_, v0, _) = report()
    val rec = recording(runDir(run1Root))
    val v = withPassed(v0, Map.empty, rec)
    val permit = new LoadPermit(v, TargetStore.S3Vectors)
    Vector("_run-manifest.json", "metrics/part-00000.parquet").foreach(p =>
      assert(permit.readItem(p).isLeft)
    )
    assertEquals(rec.reads.toVector, Vector.empty, "a refused path is never read")
    val item = permit.items.head
    assert(permit.readItem(item.path).isRight)
    assertEquals(
      rec.bounds.toVector,
      Vector(item.path -> Some(item.byteCount)),
      "bounded by its byte count"
    )
  }
}
