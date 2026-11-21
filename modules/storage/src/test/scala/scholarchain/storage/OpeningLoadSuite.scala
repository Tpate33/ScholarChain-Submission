package scholarchain.storage

import java.nio.file.Files

import scholarchain.core.TargetStore
import scholarchain.storage.load.*
import scholarchain.storage.model.*
import scholarchain.storage.port.{KeyValueStore, VectorStore}
import OpeningFixture.*

/**
 * SE-P7-08 items 1-3 and SE-P7-09 (human acceptance rule): the real fixtures loaded through the
 * PRODUCTION path only, now including s3-vectors. [[OpeningLoad.run]] reads the grouping run, the
 * curated run and the benchmark pair list with the production readers, verifies the run with the
 * full context and loads through [[LoadGate.proceed]] and the real loaders. This suite is outside
 * package `load`: it cannot build a VerifiedRun or a LoadPermit, so no check can be marked passed
 * by hand. Vectors are SYNTHETIC test vectors (model `synthetic-test-only.v1`); real embeddings are
 * Phase 8.
 */
class OpeningLoadSuite extends munit.FunSuite {

  private val Neptune = TargetStore.NeptuneBulkLoad
  private val Dynamo = TargetStore.DynamoDb
  private val S3 = TargetStore.S3
  private val Vectors = TargetStore.S3Vectors

  /** Rows per index in the fixture (176 + 51 + 8 + 9 + 5 = 249). */
  private val Routed = Vector(
    "researcher-profile-v1" -> 176L,
    "collaboration-community-v1" -> 51L,
    "paper-abstract-v1" -> 8L,
    "collaboration-path-v1" -> 9L,
    "topic-institution-summary-v1" -> 5L
  )

  private def show(r: OpeningLoadReport): String =
    r.stores
      .map { s =>
        s"${s.target.tag}: ${s.status.tag} written=${s.written} present=${s.alreadyPresent} " +
          s"conflicts=${s.conflicts} rejectedRecords=${s.rejectedRecords} counts=${s.counts
              .map((k, v) => s"$k=$v")
              .mkString(",")} " +
          s"performed=${s.performed.mkString(",")} open=${s.open.mkString(",")}" +
          s.error.map(e => s" error=$e").getOrElse("") +
          (if (s.rejections.isEmpty) ""
           else s" rejections=${s.rejections.take(3).map(x => s"${x.check.tag}:${x.subject}")}")
      }
      .mkString("\n")

  private def count(s: StoreLoadOutcome, name: String): Long =
    s.counts.find(_._1 == name).map(_._2).getOrElse(fail(s"no count $name in ${s.counts}"))

  private lazy val first: (World, OpeningLoadResult) = {
    val w = new World
    val r = run(w)
    println("SE-P7-09 opening load, first run:\n" + show(r.report))
    (w, r)
  }

  private def entry(w: World, t: TargetStore): LoadEntry = {
    val rows = w.ledgerStore
      .query(
        TableRole.RunLedger,
        scholarchain.storage.port.KeyQuery(
          None,
          "run#" + Fixtures.RunId,
          scholarchain.storage.port.SortKeyCondition.BeginsWith("load#"),
          10
        )
      )
      .fold(e => fail(e.toString), identity)
    rows
      .map(i => ItemCodecs.LoadEntryCodec.decode(i).fold(e => fail(e.toString), identity))
      .find(_.targetStore == t)
      .getOrElse(fail(s"no ledger row for ${t.tag}"))
  }

  test("item 1: paths-run-chain passes; the list's core digest equals benchmarkPairsDigest") {
    val r = first._2.report
    assertEquals(r.rejections, Vector.empty)
    assertEquals(r.upstreamProblems, Vector.empty)
    assert(r.benchmarkDigest.isDefined)
    assertEquals(r.benchmarkDigest, r.recordedBenchmarkDigest)
    assert(r.recordedBenchmarkDigest.exists(d => d.startsWith("2e1ff182") && d.endsWith("be9")))
    Vector(Dynamo, S3).foreach(t => assert(r.store(t).performed.contains("paths-run-chain"), t.tag))
  }

  test("item 2: every store's checks are performed by the verifier; none is open") {
    val r = first._2.report
    TargetStore.values.foreach { t =>
      assertEquals(r.store(t).open, Vector.empty, t.tag)
      assertEquals(r.store(t).performed, DeferredCheck.appliesTo(t).map(_.tag), t.tag)
    }
    assertEquals(
      r.store(Vectors).performed,
      Vector("parquet-rows", "row-contracts", "summary-evidence")
    )
  }

  test("item 2: real loads of all four stores into the fakes, exact counts per store") {
    val (w, res) = first
    val r = res.report
    assert(r.complete, show(r))
    val n = r.store(Neptune)
    assertEquals((n.status, n.written, n.alreadyPresent), (StoreLoadStatus.Loaded, 1258L, 0L))
    assertEquals(n.counts, Vector("vertices" -> 459L, "edges" -> 799L))
    assertEquals((w.graph.vertexIds.size, w.graph.edgeIds.size), (459, 799))
    assertEquals(w.graph.writer.counts.written, 1258)
    val d = r.store(Dynamo)
    assertEquals((d.status, d.written, d.alreadyPresent), (StoreLoadStatus.Loaded, 1302L, 0L))
    // CR-P7-10 m2: every role count, exactly.
    assertEquals(
      d.counts,
      Vector(
        "items" -> 1302L,
        "role:analysis-windows" -> 2L,
        "role:bridge-measures" -> 176L,
        "role:coauthor-edges" -> 259L,
        "role:community-memberships" -> 176L,
        "role:component-memberships" -> 176L,
        "role:coreness" -> 176L,
        "role:evidence-paths" -> 9L,
        "role:path-length-estimates" -> 37L,
        "role:researcher-metrics" -> 176L,
        "role:temporal-changes" -> 112L,
        "role:window-comparisons" -> 1L,
        "role:window-structures" -> 2L
      )
    )
    assertEquals(w.kvStore.itemCount(TableRole.ResearcherMetricCache), 1302)
    val s = r.store(S3)
    // SE-P7-13 item 2: 29 parts + the run's _parts.json and _run-manifest.json.
    assertEquals((s.status, s.written, s.alreadyPresent), (StoreLoadStatus.Loaded, 31L, 0L))
    assertEquals(
      s.counts,
      Vector("objects" -> 31L, "run-files" -> 2L, "registry-rows" -> 31L)
    )
    // s3-vectors (SE-P7-09): 249 synthetic vectors in five indexes, 249 text objects.
    val v = r.store(Vectors)
    assertEquals(
      (v.status, v.written, v.alreadyPresent, v.rejectedRecords),
      (StoreLoadStatus.Loaded, 249L, 0L, 0L)
    )
    assertEquals((count(v, "parts"), count(v, "rows")), (4L, 249L))
    Routed.foreach { case (ix, n) =>
      assertEquals(count(v, s"index:$ix:routed"), n, ix)
      assertEquals(count(v, s"index:$ix:written"), n, ix)
      assertEquals(count(v, s"index:$ix:stored"), n, ix)
      assertEquals(count(v, s"index:$ix:rejected"), 0L, ix)
      assertEquals(w.vectorStore.count(ix), n.toInt, ix)
    }
    assertEquals(Routed.map(_._2).sum, 249L)
    assertEquals(
      (count(v, "text-objects"), count(v, "text-written"), count(v, "registry-rows")),
      (249L, 249L, 249L)
    )
    assertEquals(w.vectorCount, 249)
    assertEquals(w.objectStore.size, 31 + 249)
    assertEquals(w.kvStore.itemCount(TableRole.ArtifactRegistry), 31 + 249)
    // RunLedger: 4 store-owner rows, 1 publication row, 4 load rows; no rejected-record row.
    assertEquals(w.ledgerStore.itemCount(TableRole.RunLedger), 9)
    assertEquals(TargetStore.values.map(r.store(_).conflicts).sum, 0L)
    TargetStore.values.foreach(t => assertEquals(entry(w, t).state, LoadState.Loaded, t.tag))
  }

  test("SE-P7-09: every vector is labelled synthetic and carries its provenance and text pointer") {
    val (w, _) = first
    val rt = first._2.runtime.getOrElse(fail("runtime missing"))
    Routed.foreach { case (ix, n) =>
      val q = Vector.fill(Dimension)(1f / math.sqrt(Dimension.toDouble).toFloat)
      val ms =
        rt.vectors.query(ix, q, math.min(n.toInt, 30), None).fold(e => fail(e.toString), identity)
      assertEquals(ms.size, math.min(n.toInt, 30), ix)
      ms.foreach { m =>
        val md = m.metadata
        assertEquals(md.string(MetadataField.ModelVersion), Some("synthetic-test-only.v1"))
        assertEquals(md.string(MetadataField.RunId), Some(Fixtures.RunId))
        assertEquals(md.string(MetadataField.IndexVersion), Some(ix))
        assertEquals(md.string(MetadataField.SchemaVersion), Some(VectorMetadata.SchemaVersion))
        assert(md.string(MetadataField.SourcePath).exists(_.contains("summary-inputs/")))
        assert(m.key.startsWith(ix + "/suminput:"), m.key)
        // The text is in ordinary S3 under textS3Key; its SHA-256 is textSha256 (V3).
        val key = md.string(MetadataField.TextS3Key).get
        val (meta, body) = w.objectStore
          .get(ObjectRef("test-data-bucket", key))
          .toOption
          .flatten
          .getOrElse(fail(s"no text object $key"))
        assertEquals(Some(meta.sha256), md.string(MetadataField.TextSha256))
        // SE-P7-11 item 3: format v2 (identity lines).
        assert(body.utf8.startsWith("scholarchain.context-document.v2\n"))
        assertEquals(ContextText.Version, "scholarchain.context-document.v2")
        assert(body.utf8.contains(s"inputId: ${md.string(MetadataField.InputId).get}"))
        // No metadata value is long text.
        md.values.values.foreach(x => assert(x.byteSize <= openingLimits.maxValueBytes))
        assertEquals(
          VectorMetadataRules.problems(VectorIndexes.byName(ix).get, md, openingLimits),
          Vector.empty
        )
      }
    }
  }

  test("item 2: rerun with the same ledger: every load skipped, zero new writes in every store") {
    val (w, _) = first
    val before = (w.dataWriteCalls, w.ledger.counts.written, w.vectorCount, w.objectStore.size)
    val again = run(w).report
    println("SE-P7-09 rerun:\n" + show(again))
    TargetStore.values.foreach { t =>
      assertEquals(again.store(t).status, StoreLoadStatus.Skipped, t.tag)
      assertEquals(again.store(t).written, 0L, t.tag)
    }
    assertEquals(
      (w.dataWriteCalls, w.ledger.counts.written, w.vectorCount, w.objectStore.size),
      before
    )
  }

  test("item 2: rerun against a fresh ledger: every element AlreadyPresent, zero new elements") {
    val (w, _) = first
    val fresh = new fake.RecordingKeyValueStore(new fake.InMemoryKeyValueStore(1000))
    val r = OpeningLoad.run(sources(), config, w.targets(ledgerStore = fresh)).report
    println("SE-P7-09 fresh-ledger rerun:\n" + show(r))
    assertEquals(
      TargetStore.values.toVector.map(t =>
        (r.store(t).status, r.store(t).written, r.store(t).alreadyPresent)
      ),
      Vector(
        (StoreLoadStatus.Loaded, 0L, 1258L),
        (StoreLoadStatus.Loaded, 0L, 1302L),
        (StoreLoadStatus.Loaded, 0L, 31L),
        (StoreLoadStatus.Loaded, 0L, 249L)
      )
    )
    assertEquals(w.kvStore.itemCount(TableRole.ResearcherMetricCache), 1302)
    assertEquals(w.objectStore.size, 31 + 249)
    assertEquals(w.vectorCount, 249)
  }

  test(
    "m2: an interrupted dynamodb load reports its partial writes and resumes without duplicates"
  ) {
    val w = new World
    var tableWrites = 0
    val flaky = new KeyValueStore {
      def get(t: TableRole, k: PrimaryKey) = w.kv.get(t, k)
      def query(t: TableRole, q: scholarchain.storage.port.KeyQuery) = w.kv.query(t, q)
      def putIfAbsent(t: TableRole, i: Item) =
        if (t == TableRole.ResearcherMetricCache) {
          tableWrites += 1
          if (tableWrites == 501) Left(StoreError.Transient("throttled"))
          else w.kv.putIfAbsent(t, i)
        } else w.kv.putIfAbsent(t, i)
      def putIfVersion(t: TableRole, i: Item, v: Long) = w.kv.putIfVersion(t, i, v)
    }
    val res1 = OpeningLoad.run(sources(), config, w.targets(keyValues = flaky))
    val r1 = res1.report
    assertEquals(r1.store(Dynamo).status, StoreLoadStatus.Failed)
    assertEquals(r1.store(Dynamo).error, Some(StoreError.Transient("throttled")))
    assertEquals(r1.store(Dynamo).written, 500L, "the partial count this call created (m2)")
    assertEquals(w.kvStore.itemCount(TableRole.ResearcherMetricCache), 500)
    assertEquals(r1.store(Neptune).status, StoreLoadStatus.Loaded)
    assertEquals(r1.store(S3).status, StoreLoadStatus.Loaded)
    assertEquals(r1.store(Vectors).status, StoreLoadStatus.Loaded)
    // R5: no runtime view over a partly loaded set of stores.
    assert(!r1.complete)
    assertEquals(res1.runtime, None)
    assertEquals((entry(w, Dynamo).state, entry(w, Dynamo).written), (LoadState.Failed, 500L))
    val res2 = OpeningLoad.run(sources(), config, w.targets(keyValues = flaky))
    val r2 = res2.report
    println("SE-P7-09 resumed dynamodb: " + show(r2))
    assertEquals(
      (r2.store(Dynamo).status, r2.store(Dynamo).written, r2.store(Dynamo).alreadyPresent),
      (StoreLoadStatus.Loaded, 802L, 500L)
    )
    // m2: the ledger counts 1302 created and none found present (its own writes are not re-added).
    val e = entry(w, Dynamo)
    assertEquals((e.state, e.written, e.alreadyPresent), (LoadState.Loaded, 1302L, 0L))
    assertEquals(w.kvStore.itemCount(TableRole.ResearcherMetricCache), 1302)
    Vector(Neptune, S3, Vectors).foreach(t =>
      assertEquals(r2.store(t).status, StoreLoadStatus.Skipped, t.tag)
    )
    assert(res2.runtime.isDefined)
  }

  test("SE-P7-09: an interrupted vector load (throttled mid-batch) resumes without duplicates") {
    val w = new World
    // Batch 3 of researcher-profile-v1 (50 per batch) stays throttled for every attempt (4).
    var batch = 0
    val throttled = new VectorStore {
      def describe(i: String) = w.vectors.describe(i)
      def getVectors(i: String, k: Vector[String]) = w.vectors.getVectors(i, k)
      def query(i: String, v: Vector[Float], k: Int, f: Option[VectorFilter]) =
        w.vectors.query(i, v, k, f)
      def putVectors(i: String, rs: Vector[VectorRecord]) = {
        batch += 1
        if (batch >= 3 && batch <= 6) Left(StoreError.Transient("ThrottlingException"))
        else w.vectors.putVectors(i, rs)
      }
    }
    val r1 = OpeningLoad.run(sources(), config, w.targets(vectorStore = throttled)).report
    println("SE-P7-09 interrupted vectors: " + show(r1))
    val v1 = r1.store(Vectors)
    assertEquals(v1.status, StoreLoadStatus.Failed)
    assert(
      v1.error.exists(_.message.contains("stayed throttled after 4 attempts")),
      v1.error.toString
    )
    assertEquals(v1.written, 100L, "two full batches were written before the throttled one")
    assertEquals(w.vectorCount, 100)
    assertEquals(w.sleeper.sleeps, Vector(50L, 100L, 200L), "bounded backoff, jitter 0.5")
    assertEquals(entry(w, Vectors).state, LoadState.Failed)
    val r2 = run(w).report
    val v2 = r2.store(Vectors)
    assertEquals(
      (v2.status, v2.written, v2.alreadyPresent),
      (StoreLoadStatus.Loaded, 149L, 100L)
    )
    assertEquals(w.vectorCount, 249)
    val e = entry(w, Vectors)
    assertEquals((e.state, e.written, e.alreadyPresent), (LoadState.Loaded, 249L, 0L))
    assertEquals(w.objectStore.size, 31 + 249, "text objects written once")
    // CR-P7-11 m3: the failed call's outcome counts its text-object and registry writes too.
    assertEquals(
      v1.counts,
      Vector("text-written" -> 249L, "registry-written" -> 249L),
      "partial side writes of the failed call"
    )
    // CR-P7-11 m4: the resume embeds only the 149 missing vectors (76 profiles, then 73 others).
    assertEquals(count(v2, "embedded"), 149L)
    assertEquals(count(v2, "text-present"), 249L)
  }

  // ------------------------------------------------------------------ CR-P7-11 M1

  private def plantOwner(w: World, t: TargetStore, runId: String, sha: String): Unit =
    ItemCodecs.StoreOwnershipCodec
      .encode(StoreOwnership(StoreOwnership.SchemaVersion, t, runId, sha))
      .flatMap(w.ledgerStore.putIfAbsent(TableRole.RunLedger, _))
      .fold(e => fail(e.toString), _ => ())

  private def ownerRow(w: World, t: TargetStore): Option[StoreOwnership] =
    TableKeys.RunLedger
      .storeOwner(t)
      .flatMap(w.ledgerStore.get(TableRole.RunLedger, _))
      .fold(e => fail(e.toString), identity)
      .map(i => ItemCodecs.StoreOwnershipCodec.decode(i).fold(e => fail(e.toString), identity))

  private def assertNothingWritten(w: World, what: String): Unit = {
    assertEquals(w.writeCalls, 0, s"$what: no write call on any store, ledger included")
    assertEquals(
      (w.graph.vertexIds.size, w.objectStore.size, w.vectorCount),
      (0, 0, 0),
      what
    )
    assertEquals(w.kvStore.itemCount(TableRole.ResearcherMetricCache), 0, what)
    assertEquals(w.kvStore.itemCount(TableRole.ArtifactRegistry), 0, what)
    assertEquals(w.embedder.calls, 0, s"$what: the embedder is never called")
  }

  test(
    "CR-P7-11 M1: ONE store owned by another run refuses all four stores, zero writes anywhere"
  ) {
    TargetStore.values.foreach { owned =>
      val w = new World
      plantOwner(w, owned, TestData.otherRunId, TestData.hex("other"))
      val res = run(w)
      TargetStore.values.foreach { t =>
        val s = res.report.store(t)
        assertEquals(s.status, StoreLoadStatus.Failed, s"${owned.tag} owned: ${t.tag}")
        assertEquals(s.conflicts, 1L, s"${owned.tag} owned: ${t.tag}")
        assert(
          s.error.exists(e =>
            e.message.startsWith(s"${owned.tag}: this store instance is owned by run") &&
              e.message.contains("one analysis run per local store instance")
          ),
          s"${owned.tag} owned: ${t.tag}: ${s.error}"
        )
        assertEquals((s.written, s.alreadyPresent), (0L, 0L))
      }
      assertNothingWritten(w, s"${owned.tag} owned")
      assertEquals(res.runtime, None)
      // No owner row of this run was claimed anywhere.
      TargetStore.values.filterNot(_ == owned).foreach(t => assertEquals(ownerRow(w, t), None))
    }
  }

  test("CR-P7-11 M1: the reviewed failure sequence is refused for the second run, zero writes") {
    // Step 1 (run A = this run): neptune, dynamodb and s3 load; the vector load is refused (the
    // embedder is refused by the production policy), so s3-vectors stays unclaimed.
    val a = new World
    val refusing = config.copy(vectors = vectorConfig(policy = EmbeddingPolicy.RealModelsOnly))
    val r1 = OpeningLoad.run(sources(), refusing, a.targets()).report
    Vector(Neptune, Dynamo, S3).foreach(t =>
      assertEquals(r1.store(t).status, StoreLoadStatus.Loaded, t.tag)
    )
    assertEquals(r1.store(Vectors).status, StoreLoadStatus.Failed)
    assertEquals(a.embedder.calls, 0)
    val owners = TargetStore.values.toVector.map(t => t -> ownerRow(a, t).map(_.runId)).toMap
    assertEquals(
      owners,
      Map(
        Neptune -> Some(Fixtures.RunId),
        Dynamo -> Some(Fixtures.RunId),
        S3 -> Some(Fixtures.RunId),
        Vectors -> None
      ),
      "exactly the state the review describes: s3-vectors unclaimed"
    )
    // Step 2 (run B): the same ledger state, owned by another run; this run plays B. Its vector
    // load would write into the s3 and dynamodb stores: refused before any write, and it claims
    // nothing (s3-vectors stays unclaimed).
    val b = new World
    Vector(Neptune, Dynamo, S3).foreach(plantOwner(b, _, TestData.otherRunId, TestData.hex("A")))
    val rb = run(b)
    TargetStore.values.foreach(t =>
      assertEquals(rb.report.store(t).status, StoreLoadStatus.Failed, t.tag)
    )
    assertNothingWritten(b, "run B")
    assertEquals(ownerRow(b, Vectors), None)
    // Step 3: run A can still complete: the three loaded stores are skipped, the vectors load.
    val r3res = run(a)
    val r3 = r3res.report
    println("CR-P7-11 M1 owning run completes:\n" + show(r3))
    Vector(Neptune, Dynamo, S3).foreach(t =>
      assertEquals(r3.store(t).status, StoreLoadStatus.Skipped, t.tag)
    )
    assertEquals(
      (r3.store(Vectors).status, r3.store(Vectors).written),
      (StoreLoadStatus.Loaded, 249L)
    )
    assert(r3.complete && r3res.runtime.isDefined)
    assertEquals(ownerRow(a, Vectors).map(_.runId), Some(Fixtures.RunId))
    assertEquals(a.ledgerStore.itemCount(TableRole.RunLedger), 9)
  }

  test("CR-P7-11 M1: stores already owned by THIS run do not refuse it") {
    val w = new World
    val (runId, sha) = (Fixtures.RunId, first._2.report.partManifestSha256.get)
    Vector(Dynamo, S3).foreach(plantOwner(w, _, runId, sha))
    val r = run(w).report
    assert(r.complete, show(r))
    TargetStore.values.foreach(t => assertEquals(ownerRow(w, t).map(_.runId), Some(runId)))
  }

  // ------------------------------------------------------------------ CR-P7-11 m2, m4

  test("CR-P7-11 m2: a production-policy runtime view refuses the synthetic vectors") {
    val (w, _) = first
    val prod = scholarchain.storage.port.RuntimeStores(
      w.objects,
      w.kv,
      w.kv,
      w.graph,
      w.vectors,
      w.vectors,
      EmbeddingPolicy.RealModelsOnly
    )
    val q = Vector.fill(Dimension)(0.5f)
    Routed.foreach { case (ix, _) =>
      val out = prod.vectors.query(ix, q, 5, None)
      assert(
        out.left.exists(_.message.contains("refused by the RealModelsOnly policy")),
        s"$ix: $out"
      )
    }
    // The test view of the same load (explicit SyntheticTestOnly configuration) reads them.
    val rt = first._2.runtime.getOrElse(fail("runtime missing"))
    assert(rt.vectors.query("researcher-profile-v1", q, 5, None).isRight)
  }

  test("CR-P7-11 m4: a rerun of a loaded run and a fresh-ledger rerun call the embedder 0 times") {
    val w = new World
    run(w)
    val afterFirst = (w.embedder.calls, w.embedder.texts)
    assertEquals(afterFirst, (9, 249L), "4 + 2 + 1 + 1 + 1 batches of at most 50")
    run(w)
    assertEquals((w.embedder.calls, w.embedder.texts), afterFirst, "same ledger: skipped")
    val fresh = new fake.RecordingKeyValueStore(new fake.InMemoryKeyValueStore(1000))
    val r = OpeningLoad.run(sources(), config, w.targets(ledgerStore = fresh)).report
    assertEquals(r.store(Vectors).alreadyPresent, 249L)
    assertEquals(count(r.store(Vectors), "embedded"), 0L)
    assertEquals((w.embedder.calls, w.embedder.texts), afterFirst, "fresh ledger: all present")
  }

  test("tampered summary input (one byte) is refused in every store with zero writes") {
    Fixtures.withCopy { (runDir, loadDir) =>
      flipByte(runDir.resolve("summary-inputs/part-00000.parquet"), 20000)
      val w = new World
      val res = run(w, sources(run = runDir, loads = loadDir))
      val r = res.report
      assert(r.rejections.exists(_.check == VerificationCheck.PartIntegrity), r.rejections.toString)
      TargetStore.values.foreach(t =>
        assertEquals(r.store(t).status, StoreLoadStatus.Refused, t.tag)
      )
      assertEquals(w.writeCalls, 0)
      assertEquals(res.runtime, None, "R5: no runtime view")
    }
  }

  test("tampered summary input, resealed so only its content is wrong, is refused, zero writes") {
    Fixtures.withCopy { (runDir, loadDir) =>
      val path = "summary-inputs/part-00001.parquet"
      val b = Files.readAllBytes(runDir.resolve(path))
      b(b.length / 2) = (b(b.length / 2) ^ 0x01).toByte
      Fixtures.reseal(runDir, loadDir, path, b, 122L)
      val w = new World
      val r = run(w, sources(run = runDir, loads = loadDir)).report
      println("SE-P7-09 resealed summary input: " + r.rejections.map(_.check.tag).distinct)
      assert(r.rejections.nonEmpty)
      TargetStore.values.foreach(t =>
        assertEquals(r.store(t).status, StoreLoadStatus.Refused, t.tag)
      )
      assertEquals(w.writeCalls, 0)
    }
  }

  test("m1: a store instance owned by another run refuses every store with zero data writes") {
    // A second analysis run (for example another window set, whose keys collide with none of this
    // run's) owns the stores: simulated by its store-owner rows, since no second analysis run
    // fixture exists (the run-2 fixture holds only a _parts.json).
    val w = new World
    TargetStore.values.foreach { t =>
      ItemCodecs.StoreOwnershipCodec
        .encode(
          StoreOwnership(
            StoreOwnership.SchemaVersion,
            t,
            TestData.otherRunId,
            TestData.hex("other")
          )
        )
        .flatMap(w.ledgerStore.putIfAbsent(TableRole.RunLedger, _))
        .fold(e => fail(e.toString), identity)
    }
    val res = run(w)
    TargetStore.values.foreach { t =>
      val s = res.report.store(t)
      assertEquals(s.status, StoreLoadStatus.Failed, t.tag)
      assertEquals(s.conflicts, 1L, t.tag)
      assert(s.error.exists(_.message.contains("one analysis run per local store instance")), t.tag)
      assertEquals(s.written, 0L)
    }
    assertEquals(w.dataWriteCalls, 0)
    assertEquals((w.graph.vertexIds.size, w.objectStore.size, w.vectorCount), (0, 0, 0))
    assertEquals(w.kvStore.itemCount(TableRole.ResearcherMetricCache), 0)
    assertEquals(res.runtime, None)
  }

  test("SE-P7-09: another run's vector under one key (fresh ledger) is refused by the probe") {
    val (loaded, _) = first
    // One stored researcher profile, re-planted in fresh stores with another text hash and run.
    val m = loaded.vectorStore
      .query("researcher-profile-v1", Vector.fill(Dimension)(0.5f), 1, None)
      .fold(e => fail(e.toString), identity)
      .head
    val stored = loaded.vectorStore
      .getVectors("researcher-profile-v1", Vector(m.key))
      .fold(e => fail(e.toString), identity)
      .head
    val w = new World
    val planted = stored.copy(metadata =
      TestData.withMeta(
        TestData.withMeta(stored.metadata, "textSha256", MetaValue.Str(TestData.hex("other text"))),
        "runId",
        MetaValue.Str(TestData.otherRunId)
      )
    )
    assertEquals(
      w.vectorStore.putVectors("researcher-profile-v1", Vector(planted)).map(_.map(_.outcome)),
      Right(Vector(Right(PutOutcome.Written)))
    )
    val r = run(w).report
    val v = r.store(Vectors)
    assertEquals(v.status, StoreLoadStatus.Failed)
    assert(v.error.exists(_.message.contains("pre-write probe")), v.error.toString)
    assertEquals((v.written, w.vectors.batches, w.vectorCount), (0L, 0, 1), "zero vector writes")
    assertEquals(
      w.vectorStore.getVectors("researcher-profile-v1", Vector(m.key)),
      Right(Vector(planted)),
      "never overwritten"
    )
  }

  test("a missing benchmark list refuses every store (upstream-read), zero writes") {
    Fixtures.withTemp { tmp =>
      val dir = tmp.resolve("empty")
      Files.createDirectories(dir)
      val w = new World
      val r = run(w, sources(benchmark = dir)).report
      assertEquals(r.upstreamProblems.size, 1)
      assert(r.rejections.forall(_.check == VerificationCheck.UpstreamRead))
      TargetStore.values.foreach(t =>
        assertEquals(r.store(t).status, StoreLoadStatus.Refused, t.tag)
      )
      assertEquals(w.writeCalls, 0)
    }
  }

  test("item 1: a benchmark copy with one changed byte is refused (benchmark-pairs), zero writes") {
    Fixtures.withTemp { tmp =>
      val dir = copyOf(benchmarkDir, tmp, "bench")
      val file = dir.resolve(BenchmarkFile)
      val text = utf8(Files.readAllBytes(file))
      val at = text.indexOf("researcher:sc:v1:1aaef") + "researcher:sc:v1:1aae".length
      assertEquals(text.charAt(at), 'f')
      val b = Files.readAllBytes(file)
      b(at) = 'e'.toByte
      Files.write(file, b)
      val w = new World
      val r = run(w, sources(benchmark = dir)).report
      assert(r.rejections.nonEmpty)
      assert(
        r.rejections.forall(_.check == VerificationCheck.BenchmarkPairs),
        r.rejections.toString
      )
      assert(r.rejections.exists(_.error.message.contains("benchmarkPairsDigest")))
      TargetStore.values.foreach(t =>
        assertEquals(r.store(t).status, StoreLoadStatus.Refused, t.tag)
      )
      assertEquals(w.writeCalls, 0)
    }
  }

  test("item 3: the report is deterministic; the runtime view reads the loaded stores") {
    val a = run(new World).report
    val b = run(new World).report
    assertEquals(a, b)
    val rt = first._2.runtime.getOrElse(fail("a complete load hands out a runtime view"))
    val listed =
      rt.objects.list("test-data-bucket", "graph/", 50).fold(e => fail(e.toString), identity)
    assert(listed.nonEmpty)
    val windows = rt.keyValues
      .query(
        TableRole.ResearcherMetricCache,
        scholarchain.storage.port.KeyQuery(
          None,
          "window#" + {
            val w0 = RunSettingsFixture.settings.windows.head
            f"${w0.startYear}%04d-${w0.endYear}%04d"
          },
          scholarchain.storage.port.SortKeyCondition.BeginsWith("result#"),
          10
        )
      )
      .fold(e => fail(e.toString), identity)
    assert(windows.nonEmpty)
  }

  // ------------------------------------------------------------------ SE-P7-13

  test("SE-P7-13 item 2: _parts.json and _run-manifest.json are in S3 with registry rows (S2)") {
    val (w, res) = first
    val expected = Vector(
      "_parts.json" -> "scholarchain.part-manifest.v2",
      "_run-manifest.json" -> "scholarchain.run-manifest.v1"
    )
    expected.foreach { case (name, schema) =>
      val fixture = Fixtures.bytes(Fixtures.runDir(Fixtures.run1Root).resolve(name))
      val ref = TestData.layout.runObject(Zone.Graph, Fixtures.RunId, name).toOption.get
      assertEquals(ref.key, s"graph/runs/${Fixtures.RunHex}/$name")
      val (meta, body) = w.objectStore.get(ref).toOption.flatten.getOrElse(fail(s"no $name"))
      assertEquals(body.toArray.toVector, fixture.toVector, s"$name: the exact verified bytes")
      assertEquals(
        (meta.sha256, meta.byteCount),
        (scholarchain.core.Canonical.sha256Hex(fixture), fixture.length.toLong)
      )
      val row = TableKeys.ArtifactRegistry
        .artifact(Fixtures.RunId, Zone.Graph, ref.key)
        .flatMap(w.kvStore.get(TableRole.ArtifactRegistry, _))
        .flatMap(i => ItemCodecs.ArtifactCodec.decode(i.getOrElse(fail(s"no row for $name"))))
        .fold(e => fail(e.toString), identity)
      assertEquals(
        (row.runId, row.sourcePath, row.recordSchemaVersion, row.sha256, row.byteCount),
        (Fixtures.RunId, Some(name), Some(schema), meta.sha256, meta.byteCount),
        name
      )
      assert(row.loadId.exists(_.startsWith("load:")), name)
    }
    // The stored _parts.json is the run's publication identity.
    assertEquals(
      Some(
        scholarchain.core.Canonical.sha256Hex(
          Fixtures.bytes(Fixtures.runDir(Fixtures.run1Root).resolve("_parts.json"))
        )
      ),
      res.report.partManifestSha256
    )
  }

  test("SE-P7-13 item 1: every Parquet reader class loads on the storage test classpath") {
    assertEquals(OpeningLoad.missingClasses(OpeningLoad.ReaderClasses), Vector.empty)
    assert(OpeningLoad.missingClasses(Vector("scholarchain.storage.load.NoSuchClass")).nonEmpty)
  }

  // ------------------------------------------------------------------ SE-P7-14 (CR-P7-16 m1)

  test("SE-P7-14 m1: each reader class is loaded from the jar it stands for, one per jar") {
    assertEquals(OpeningLoad.ReaderClasses, OpeningLoad.ReaderJars.map(_._2))
    assertEquals(OpeningLoad.ReaderJars.map(_._1).distinct.size, OpeningLoad.ReaderJars.size)
    assert(OpeningLoad.ReaderJars.map(_._1).contains("parquet-encoding"))
    val loader = OpeningLoad.getClass.getClassLoader
    OpeningLoad.ReaderJars.foreach { case (jar, cls) =>
      val c = Class.forName(cls, false, loader)
      val loc = Option(c.getProtectionDomain.getCodeSource).map(_.getLocation.getPath).getOrElse("")
      val file = loc.split('/').lastOption.getOrElse("")
      assert(file.startsWith(jar + "-") && file.endsWith(".jar"), s"$cls is loaded from $loc")
    }
  }

  /**
   * A run source that throws `NoClassDefFoundError` (a `LinkageError`, not matched by `NonFatal`)
   * from `readAtMost` of a Parquet part when `fails(path, nth read of that path)` holds, standing
   * in for a partial Parquet classpath that fails once decoding starts (no build change).
   */
  private final class LinkFailing(
      u: scholarchain.storage.port.RunSource,
      fails: (String, Int) => Boolean
  ) extends scholarchain.storage.port.RunSource {
    private val reads = scala.collection.mutable.Map.empty[String, Int]
    var thrown = 0
    def listFiles() = u.listFiles()
    def read(p: String) = u.read(p)
    def readAtMost(p: String, n: Long) = {
      val k = reads.getOrElse(p, 0) + 1
      reads(p) = k
      if (p.endsWith(".parquet") && fails(p, k)) {
        thrown += 1
        throw new NoClassDefFoundError("org/apache/parquet/column/values/bitpacking/BytePacker")
      }
      u.readAtMost(p, n)
    }
  }

  test("SE-P7-14 m1: a LinkageError during verification refuses every store, zero writes") {
    // Step 6 reads each part once; the second read of a Parquet part is the decoding read.
    val failing =
      new LinkFailing(Fixtures.source(Fixtures.runDir(Fixtures.run1Root)), (_, k) => k == 2)
    val w = new World
    val res = OpeningLoad.run(sources().copy(run = failing), config, w.targets())
    assertEquals(failing.thrown, 1)
    val r = res.report
    assertEquals(
      r.rejections.map(j => (j.check, j.subject)),
      Vector((VerificationCheck.ReaderClasspath, "classpath"))
    )
    val msg = r.rejections.head.error.message
    assert(
      msg.contains("java.lang.NoClassDefFoundError: org/apache/parquet/column/values/bitpacking") &&
        msg.contains("nothing was written"),
      msg
    )
    assertEquals((r.runId, r.partManifestSha256, r.upstreamProblems), (None, None, Vector.empty))
    TargetStore.values.foreach { t =>
      assertEquals(r.store(t).status, StoreLoadStatus.Refused, t.tag)
      assertEquals(r.store(t).written, 0L, t.tag)
      assertEquals(r.store(t).open, DeferredCheck.appliesTo(t).map(_.tag), t.tag)
    }
    assertEquals(w.writeCalls, 0, "no write call on any store, ledger included")
    assertEquals(w.embedder.calls, 0)
    assertEquals(res.runtime, None)
  }

  test("SE-P7-14 m1: a LinkageError inside a loader fails that store only; never thrown; resumes") {
    val w = new World
    var armed = false
    val failing =
      new LinkFailing(Fixtures.source(Fixtures.runDir(Fixtures.run1Root)), (_, _) => armed)
    // Armed by the first graph write: verification is over and the neptune loader (CSV only) runs.
    val arming = new scholarchain.storage.port.GraphReader
      with scholarchain.storage.port.GraphWriter {
      def run(q: BoundQuery) = w.graph.run(q)
      def storedVertex(id: String) = w.graph.storedVertex(id)
      def storedEdge(id: String) = w.graph.storedEdge(id)
      def write(x: BoundGraphWrite) = { armed = true; w.graph.write(x) }
    }
    val res =
      OpeningLoad.run(sources().copy(run = failing), config, w.targets().copy(graph = arming))
    val r = res.report
    println("SE-P7-14 loader LinkageError:\n" + show(r))
    assertEquals(r.rejections, Vector.empty)
    assertEquals(
      (r.store(Neptune).status, r.store(Neptune).written),
      (StoreLoadStatus.Loaded, 1258L)
    )
    Vector(Dynamo, S3, Vectors).foreach { t =>
      val s = r.store(t)
      assertEquals(s.status, StoreLoadStatus.Failed, t.tag)
      assert(
        s.error.exists {
          case StoreError.Permanent(d) =>
            d.startsWith(s"reader-classpath: the ${t.tag} loader failed to link a class") &&
            d.contains("java.lang.NoClassDefFoundError")
          case _ => false
        },
        s"${t.tag}: ${s.error}"
      )
      assertEquals(s.written, 0L, t.tag)
    }
    assert(failing.thrown >= 3, failing.thrown.toString)
    assertEquals(w.kvStore.itemCount(TableRole.ResearcherMetricCache), 0)
    assertEquals((w.objectStore.size, w.vectorCount), (0, 0))
    assert(!r.complete)
    assertEquals(res.runtime, None)
    // With a complete classpath the same stores resume: neptune skipped, the others load.
    val again = OpeningLoad.run(sources(), config, w.targets())
    val r2 = again.report
    assertEquals(r2.store(Neptune).status, StoreLoadStatus.Skipped)
    Vector(Dynamo, S3, Vectors).foreach(t =>
      assertEquals(r2.store(t).status, StoreLoadStatus.Loaded, t.tag)
    )
    assertEquals(
      (r2.store(Dynamo).written, r2.store(S3).written, r2.store(Vectors).written),
      (1302L, 31L, 249L)
    )
    assert(r2.complete && again.runtime.isDefined)
  }

  test("SE-P7-13 item 1: without the Parquet reader classes every store is refused, nothing read") {
    Fixtures.withTemp { tmp =>
      // An empty benchmark directory: had anything been read, the refusal would be upstream-read.
      val dir = tmp.resolve("empty")
      Files.createDirectories(dir)
      val w = new World
      val absent = "scholarchain.storage.load.AbsentReaderClassForTest"
      val res = OpeningLoad.runWith(
        sources(benchmark = dir),
        config,
        w.targets(),
        OpeningLoad.ReaderClasses :+ absent
      )
      val r = res.report
      assertEquals(r.rejections.map(_.check), Vector(VerificationCheck.ReaderClasspath))
      assertEquals(VerificationCheck.ReaderClasspath.tag, "reader-classpath")
      val msg = r.rejections.head.error.message
      assert(msg.contains(absent) && !msg.contains("ParquetFileReader"), msg)
      assertEquals((r.runId, r.upstreamProblems), (None, Vector.empty))
      TargetStore.values.foreach { t =>
        assertEquals(r.store(t).status, StoreLoadStatus.Refused, t.tag)
        assertEquals(r.store(t).open, DeferredCheck.appliesTo(t).map(_.tag), t.tag)
      }
      assertEquals(w.writeCalls, 0)
      assertEquals(w.embedder.calls, 0)
      assertEquals(res.runtime, None)
    }
  }
}
