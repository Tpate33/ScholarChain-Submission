package scholarchain.storage.load

import scholarchain.core.TargetStore
import scholarchain.storage.{Fixtures, OpeningFixture, TestData}
import scholarchain.storage.OpeningFixture.{CountingEmbedder, Dimension, World, config, sources}
import scholarchain.storage.fake.{
  InMemoryKeyValueStore,
  RecordingKeyValueStore,
  SyntheticTestEmbedding
}
import scholarchain.storage.model.*
import scholarchain.storage.port.{Embedder, KeyQuery, SortKeyCondition, VectorStore}

/**
 * SE-P8-01 (Phase 8 increment 1): embedding model versioning in vector keys, the skip rule (content
 * hash and model version), the manifest-driven bounded embedding work queue, failures and retry
 * counts in the RunLedger, and the Phase 8 report fields. Everything runs on the real fixtures
 * through the production entry point [[OpeningLoad.run]] (and at unit level for the skip rule).
 * Every vector is SYNTHETIC; no real model exists or is called.
 */
class EmbeddingWorkSuite extends munit.FunSuite {

  private val V = TargetStore.S3Vectors
  private val V1 = SyntheticTestEmbedding.ModelVersion
  private val V2 = "synthetic-test-only.v2"
  private val Profile = "researcher-profile-v1"

  private def show(r: OpeningLoadReport): String =
    r.stores
      .map(s =>
        s"${s.target.tag}: ${s.status.tag} written=${s.written} present=${s.alreadyPresent}" +
          s.error.map(e => s" error=$e").getOrElse("")
      )
      .mkString("\n")

  private def count(s: StoreLoadOutcome, name: String): Long =
    s.counts.find(_._1 == name).map(_._2).getOrElse(fail(s"no count $name in ${s.counts}"))

  private def counting(model: String = V1) =
    new CountingEmbedder(new SyntheticTestEmbedding(Dimension, model))

  private def runWith(
      w: World,
      embedder: Embedder = null,
      vectorConfig: VectorLoadConfig = OpeningFixture.vectorConfig(),
      vectorStore: VectorStore = null
  ): OpeningLoadResult = {
    val base = w.targets(vectorStore = Option(vectorStore).getOrElse(w.vectors))
    OpeningLoad.run(
      sources(),
      config.copy(vectors = vectorConfig),
      base.copy(embedder = Option(embedder).getOrElse(base.embedder))
    )
  }

  /** Every s3-vectors ledger row (one per model scope), by its model version. */
  private def vectorRows(w: World): Map[Option[String], LoadEntry] =
    w.ledgerStore
      .query(
        TableRole.RunLedger,
        KeyQuery(None, "run#" + Fixtures.RunId, SortKeyCondition.BeginsWith("load#"), 100)
      )
      .fold(e => fail(e.toString), identity)
      .map(i => ItemCodecs.LoadEntryCodec.decode(i).fold(e => fail(e.toString), identity))
      .filter(_.targetStore == V)
      .map(e => e.embeddingModel -> e)
      .toMap

  private def loadId(w: World): String = vectorRows(w).values.head.loadId

  /** The work manifest the model scope's ledger row records (its key is content-addressed). */
  private def manifestOf(w: World, model: String): (ObjectRef, Bytes, EmbeddingWorkManifest) = {
    val sha = vectorRows(w)(Some(model)).embedding.getOrElse(fail("no totals")).workManifestSha256
    val ref = TestData.layout
      .embeddingWorkManifest(loadId(w), model, sha)
      .fold(e => fail(e.toString), identity)
    val body =
      w.objectStore.get(ref).fold(e => fail(e.toString), identity).getOrElse(fail(s"no $ref"))._2
    (ref, body, EmbeddingWorkManifest.parse(body).fold(e => fail(e.toString), identity))
  }

  private def ledgerRejects(w: World): Vector[RejectedRecord] =
    w.ledgerStore
      .query(
        TableRole.RunLedger,
        KeyQuery(None, "run#" + Fixtures.RunId, SortKeyCondition.BeginsWith("reject#"), 1000)
      )
      .fold(e => fail(e.toString), identity)
      .map(i => ItemCodecs.RejectedRecordCodec.decode(i).fold(e => fail(e.toString), identity))

  private def oneStored(w: World, index: String = Profile): VectorRecord = {
    val k = w.vectorStore
      .query(index, Vector.fill(Dimension)(0.5f), 1, None)
      .fold(e => fail(e.toString), identity)
      .head
      .key
    w.vectorStore.getVectors(index, Vector(k)).fold(e => fail(e.toString), identity).head
  }

  // ------------------------------------------------------------------ item 1: keys

  test("item 1: every key is <index>/<modelVersion>/<inputId>; the model version is stored") {
    val w = new World
    val r = runWith(w).report
    assert(r.complete, show(r))
    VectorIndexes.all.foreach { ix =>
      val all = w.vectorStore
        .query(ix.name, Vector.fill(Dimension)(0.5f), 30, None)
        .fold(e => fail(e.toString), identity)
      assert(all.nonEmpty, ix.name)
      all.foreach { m =>
        val input = m.metadata.string(MetadataField.InputId).get
        assertEquals(m.key, s"${ix.name}/$V1/$input")
        assertEquals(VectorKeys.of(ix, V1, input), Right(m.key))
        assertEquals(m.metadata.string(MetadataField.ModelVersion), Some(V1))
      }
    }
  }

  test("item 1/2: same text under a NEW model version is embedded under new keys; v1 untouched") {
    val w = new World
    assert(runWith(w).report.complete)
    val before = oneStored(w)
    val objects = w.objectStore.size
    val e2 = counting(V2)
    val r = runWith(w, embedder = e2).report
    println("SE-P8-01 new model version:\n" + show(r))
    assert(r.complete, show(r))
    // The other stores are already loaded; only the new model's vectors are new work.
    Vector(TargetStore.NeptuneBulkLoad, TargetStore.DynamoDb, TargetStore.S3).foreach(t =>
      assertEquals(r.store(t).status, StoreLoadStatus.Skipped, t.tag)
    )
    val v = r.store(V)
    assertEquals((v.status, v.written, v.alreadyPresent), (StoreLoadStatus.Loaded, 249L, 0L))
    assertEquals((count(v, "embedded"), count(v, "skipped-unchanged")), (249L, 0L))
    assertEquals(count(v, s"embedding-model:$V2"), Dimension.toLong)
    assertEquals((e2.calls, e2.texts), (9, 249L))
    assertEquals(w.vectorCount, 2 * 249, "v1 and v2 vectors side by side, never overwritten")
    // The v1 vector is exactly as before; its v2 sibling has the same text hash and pointer.
    assertEquals(w.vectorStore.getVectors(Profile, Vector(before.key)), Right(Vector(before)))
    val input = before.metadata.string(MetadataField.InputId).get
    val sibling = w.vectorStore
      .getVectors(Profile, Vector(s"$Profile/$V2/$input"))
      .fold(e => fail(e.toString), identity)
      .headOption
      .getOrElse(fail("no v2 vector"))
    assertEquals(sibling.metadata.string(MetadataField.ModelVersion), Some(V2))
    Vector(MetadataField.TextSha256, MetadataField.TextS3Key).foreach(f =>
      assertEquals(sibling.metadata.string(f), before.metadata.string(f), f.key)
    )
    assertNotEquals(sibling.data, before.data, "another (synthetic) model gives other vectors")
    // Text is model-independent: no new text object; one new work manifest (+ its registry row).
    assertEquals((count(v, "text-written"), count(v, "text-present")), (0L, 249L))
    assertEquals(w.objectStore.size, objects + 1)
    // Two ledger scopes, each loaded with its own totals.
    val rows = vectorRows(w)
    assertEquals(rows.keySet, Set(Some(V1), Some(V2)))
    rows.values.foreach { e =>
      assertEquals((e.state, e.written), (LoadState.Loaded, 249L), e.embeddingModel.toString)
      assertEquals(
        e.embedding.map(t => (t.workItems, t.embedded, t.remaining)),
        Some((249L, 249L, 0L))
      )
    }
    assertNotEquals(manifestOf(w, V1)._2, manifestOf(w, V2)._2)
    // A rerun under v2 is skipped by its own ledger row: zero embedding calls.
    val again = runWith(w, embedder = e2).report
    assertEquals(again.store(V).status, StoreLoadStatus.Skipped)
    assertEquals(e2.calls, 9)
  }

  // ------------------------------------------------------------------ item 2: the skip rule

  test("item 2 (unit): Absent, Unchanged, Conflict on other text, Corrupt on changed floats") {
    val w = new World
    assert(runWith(w).report.complete)
    val s = oneStored(w)
    val expected = VectorDigest.withoutDigest(s.metadata)
    assertEquals(
      VectorLoad.classify(Profile, s.key, expected, Dimension, None),
      Right(StoredVector.Absent)
    )
    assertEquals(
      VectorLoad.classify(Profile, s.key, expected, Dimension, Some(s)),
      Right(StoredVector.Unchanged)
    )
    // Changed text under the same key (same model): Conflict, naming the stored text hash.
    val otherText = TestData.hex("changed text")
    VectorLoad.classify(
      Profile,
      s.key,
      TestData.withMeta(expected, "textSha256", MetaValue.Str(otherText)),
      Dimension,
      Some(s)
    ) match {
      case Left(StoreError.Conflict(d)) =>
        assert(d.startsWith(s"$Profile ${s.key}: holds textSha256 "), d)
      case x => fail(s"expected Conflict, got $x")
    }
    // Another model version recorded under the key: Conflict too (never silently accepted).
    assert(
      VectorLoad
        .classify(
          Profile,
          s.key,
          TestData.withMeta(expected, "modelVersion", MetaValue.Str(V2)),
          Dimension,
          Some(s)
        )
        .left
        .exists(_.isInstanceOf[StoreError.Conflict])
    )
    // Same metadata, changed floats: Corrupt.
    val bent = s.copy(data = s.data.updated(0, s.data(0) + 0.25f))
    assert(
      VectorLoad
        .classify(Profile, s.key, expected, Dimension, Some(bent))
        .left
        .exists(_.isInstanceOf[StoreError.Corrupt])
    )
  }

  test(
    "item 2: changed text under the same key and model is a Conflict: zero puts, zero embedding"
  ) {
    val loaded = new World
    assert(runWith(loaded).report.complete)
    val s = oneStored(loaded)
    val planted = s.copy(metadata =
      TestData.withMeta(s.metadata, "textSha256", MetaValue.Str(TestData.hex("changed text")))
    )
    val w = new World
    assertEquals(
      w.vectorStore.putVectors(Profile, Vector(planted)).map(_.map(_.outcome)),
      Right(Vector(Right(PutOutcome.Written)))
    )
    val e = counting()
    val r = runWith(w, embedder = e).report
    val v = r.store(V)
    assertEquals(v.status, StoreLoadStatus.Failed, show(r))
    assert(v.error.exists(_.isInstanceOf[StoreError.Conflict]), v.error.toString)
    assertEquals((w.vectors.batches, e.calls, w.vectorCount), (0, 0, 1))
    assertEquals(w.vectorStore.getVectors(Profile, Vector(s.key)), Right(Vector(planted)))
    // The same text under a new model version is not this key: it loads beside the planted one.
    val e2 = counting(V2)
    val r2 = runWith(w, embedder = e2).report
    assertEquals(r2.store(V).status, StoreLoadStatus.Loaded, show(r2))
    assertEquals((r2.store(V).written, e2.texts), (249L, 249L))
    assertEquals(w.vectorStore.getVectors(Profile, Vector(s.key)), Right(Vector(planted)))
  }

  test("item 2: unchanged vectors are skipped before embedding (fresh ledger, same stores)") {
    val w = new World
    assert(runWith(w).report.complete)
    val fresh = new RecordingKeyValueStore(new InMemoryKeyValueStore(1000))
    val e = counting()
    val r =
      OpeningLoad.run(sources(), config, w.targets(ledgerStore = fresh).copy(embedder = e)).report
    val v = r.store(V)
    assertEquals((v.status, v.written, v.alreadyPresent), (StoreLoadStatus.Loaded, 0L, 249L))
    assertEquals((count(v, "skipped-unchanged"), count(v, "embedded"), e.calls), (249L, 0L, 0))
    assertEquals(count(v, "duplicates-on-put"), 0L)
  }

  // ------------------------------------------------------------------ item 3: the work queue

  test("item 3: the work manifest is deterministic, create-once in S3, registered, and complete") {
    val w = new World
    assert(runWith(w).report.complete)
    val (ref, body, m) = manifestOf(w, V1)
    assert(ref.key.startsWith("semantic-context/loads/"), ref.key)
    assert(ref.key.endsWith(s"/embedding-work/model=$V1/manifest-${body.sha256}.json"), ref.key)
    assertEquals(
      (m.runId, m.loadId, m.modelVersion, m.dimension),
      (Fixtures.RunId, loadId(w), V1, Dimension)
    )
    assertEquals(m.items.size, 249)
    // One item per (index, inputId, modelVersion), in index order then key order.
    assertEquals(m.items.map(i => (i.index, i.inputId, i.modelVersion)).distinct.size, 249)
    assertEquals(
      m.items.map(_.index).distinct,
      VectorIndexes.all.map(_.name),
      "index order (README order)"
    )
    VectorIndexes.all.foreach { ix =>
      val keys = m.items.filter(_.index == ix.name).map(_.vectorKey)
      assertEquals(keys, keys.sortWith((a, b) => scholarchain.core.Canonical.compareUtf8(a, b) < 0))
    }
    // Every item's text is fetchable through its ordinary-S3 pointer and has its hash; the vector
    // under its key records the same pointer and hash.
    m.items.foreach { i =>
      val (meta, text) = w.objectStore
        .get(ObjectRef("test-data-bucket", i.textS3Key))
        .fold(e => fail(e.toString), identity)
        .getOrElse(fail(s"no text ${i.textS3Key}"))
      assertEquals((meta.sha256, text.sha256), (i.textSha256, i.textSha256))
      val vec = w.vectorStore
        .getVectors(i.index, Vector(i.vectorKey))
        .fold(e => fail(e.toString), identity)
        .head
      assertEquals(vec.metadata.string(MetadataField.TextSha256), Some(i.textSha256))
      assertEquals(vec.metadata.string(MetadataField.TextS3Key), Some(i.textS3Key))
    }
    // Registry row of the manifest object.
    val row = TableKeys.ArtifactRegistry
      .artifact(Fixtures.RunId, Zone.SemanticContext, ref.key)
      .flatMap(w.kv.get(TableRole.ArtifactRegistry, _))
      .flatMap(i => ItemCodecs.ArtifactCodec.decode(i.getOrElse(fail("no registry row"))))
      .fold(e => fail(e.toString), identity)
    assertEquals((row.sha256, row.byteCount), (body.sha256, body.size.toLong))
    assertEquals(row.recordSchemaVersion, Some(EmbeddingWorkManifest.SchemaVersion))
    // Determinism: another world builds byte-identical manifest bytes; the ledger records its hash.
    val w2 = new World
    assert(runWith(w2).report.complete)
    assertEquals(manifestOf(w2, V1)._2, body)
    assertEquals(vectorRows(w)(Some(V1)).embedding.map(_.workManifestSha256), Some(body.sha256))
  }

  test("item 3: bounded calls (maxItemsPerCall 100): staged, staged, loaded; resumable, no dups") {
    val w = new World
    val cfg = OpeningFixture.vectorConfig(maxItemsPerCall = 100)
    val e = counting()
    val r1 = runWith(w, embedder = e, vectorConfig = cfg)
    val v1 = r1.report.store(V)
    assertEquals((v1.status, v1.written), (StoreLoadStatus.Staged, 100L), show(r1.report))
    assertEquals((count(v1, "remaining"), count(v1, "embedded")), (149L, 100L))
    assertEquals(r1.runtime, None, "no runtime view while embedding work remains")
    assertEquals(vectorRows(w)(Some(V1)).state, LoadState.DocumentsStaged)
    assertEquals(vectorRows(w)(Some(V1)).embedding.map(_.remaining), Some(149L))
    assertEquals(w.vectorCount, 100)
    // Every text and the manifest are stored on the first call (the queue's inputs).
    assertEquals(count(v1, "text-written"), 249L)
    val r2 = runWith(w, embedder = e, vectorConfig = cfg).report.store(V)
    assertEquals((r2.status, r2.written, r2.alreadyPresent), (StoreLoadStatus.Staged, 100L, 100L))
    assertEquals((count(r2, "skipped-unchanged"), count(r2, "remaining")), (100L, 49L))
    val r3res = runWith(w, embedder = e, vectorConfig = cfg)
    val r3 = r3res.report.store(V)
    assertEquals((r3.status, r3.written, r3.alreadyPresent), (StoreLoadStatus.Loaded, 49L, 200L))
    assert(r3res.runtime.isDefined)
    assertEquals(w.vectorCount, 249)
    assertEquals(e.texts, 249L, "every text embedded exactly once over the three calls")
    // One manifest, written once and found present twice.
    assertEquals(w.objectStore.size, 31 + 249 + 1)
    val row = vectorRows(w)(Some(V1))
    assertEquals((row.state, row.written, row.alreadyPresent), (LoadState.Loaded, 249L, 0L))
    assertEquals(
      row.embedding.map(t => (t.workItems, t.embedded, t.remaining, t.passes)),
      Some((249L, 249L, 0L, 3L))
    )
    // A fourth call is skipped by the ledger.
    assertEquals(
      runWith(w, embedder = e, vectorConfig = cfg).report.store(V).status,
      StoreLoadStatus.Skipped
    )
    assertEquals(e.texts, 249L)
  }

  test("item 3: a configuration change after a staged call writes a second manifest, not wedged") {
    val w = new World
    val e = counting()
    // Call 1, 256-byte per-value cap (two work summaries rejected before any write: 247 items),
    // bounded to 100 items: staged.
    val tight = OpeningFixture.vectorConfig(limits = TestData.limits, maxItemsPerCall = 100)
    val v1 = runWith(w, embedder = e, vectorConfig = tight).report.store(V)
    assertEquals(
      (v1.status, count(v1, "work-items"), v1.written),
      (StoreLoadStatus.Staged, 247L, 100L)
    )
    val first = manifestOf(w, V1)
    // Call 2, the default (1024-byte) cap: 249 items, another manifest object; resumes and loads.
    val v2 = runWith(w, embedder = e).report.store(V)
    assertEquals((v2.status, count(v2, "work-items")), (StoreLoadStatus.Loaded, 249L))
    assertEquals((v2.written, v2.alreadyPresent), (149L, 100L))
    val second = manifestOf(w, V1)
    assertNotEquals(first._1, second._1)
    assertEquals((first._3.items.size, second._3.items.size), (247, 249))
    // Both manifests stay (create-once history); the ledger names the latest.
    assert(w.objectStore.get(first._1).exists(_.isDefined))
    assertEquals(
      vectorRows(w)(Some(V1)).embedding.map(t => (t.workManifestSha256, t.workItems, t.passes)),
      Some((second._2.sha256, 249L, 2L))
    )
    assertEquals((w.vectorCount, e.texts), (249, 249L))
  }

  test("item 3: other bytes under the manifest's content-addressed key are a Conflict") {
    val w = new World
    val ref = {
      val tmp = new World
      assert(runWith(tmp).report.complete)
      manifestOf(tmp, V1)._1
    }
    assertEquals(
      w.objectStore.putCreateOnce(ref, Bytes.utf8("{\"other\":true}\n"), "application/json"),
      Right(PutOutcome.Written)
    )
    val e = counting()
    val v = runWith(w, embedder = e).report.store(V)
    assertEquals(v.status, StoreLoadStatus.Failed)
    assert(
      v.error.exists(x =>
        x.isInstanceOf[StoreError.Conflict] && x.message.contains("another embedding work manifest")
      ),
      v.error.toString
    )
    assertEquals((w.vectors.batches, e.calls, w.vectorCount), (0, 0, 0))
  }

  test("item 3: maxItemsPerCall is required, at least 1 and above maxRejected") {
    val idx =
      VectorIndexes.all.map(i => VectorIndexSettings(i.name, Dimension, DistanceMetric.Cosine))
    val retry = RetryPolicy.of(3, 10L, 100L).toOption.get
    def of(maxItems: Int, maxRejected: Int) =
      VectorLoadConfig.of(
        TestData.limits,
        idx,
        10,
        retry,
        maxRejected,
        1024,
        512,
        EmbeddingPolicy.SyntheticTestOnly,
        maxItems
      )
    assert(of(1, 0).isRight)
    assert(of(6, 5).isRight)
    Vector((0, 0), (-1, 0), (5, 5), (3, 5)).foreach { case (m, r) =>
      assert(of(m, r).left.exists(_.exists(_.startsWith("maxItemsPerCall:"))), s"$m $r")
    }
  }

  // ------------------------------------------------------------------ item 4: failures, retries

  test("item 4: transient embedding failures are retried, counted and timed (injected clock)") {
    val w = new World
    var failures = 2
    val flaky = new Embedder {
      private val real = new SyntheticTestEmbedding(Dimension)
      def modelVersion = real.modelVersion
      def dimension = Dimension
      def embed(texts: Vector[String]) =
        if (failures > 0) { failures -= 1; Left(StoreError.Transient("ThrottlingException")) }
        else real.embed(texts)
    }
    val v = runWith(w, embedder = flaky).report.store(V)
    assertEquals((v.status, v.written), (StoreLoadStatus.Loaded, 249L))
    // The first batch (50 profiles) was re-sent twice.
    assertEquals((count(v, "embed-retries"), count(v, "put-retries")), (100L, 0L))
    assertEquals(w.sleeper.sleeps, Vector(50L, 100L), "the same bounded backoff as puts")
    assertEquals(count(v, "elapsed-millis"), 150L, "simulated time only (no latency claim)")
    assertEquals(vectorRows(w)(Some(V1)).embedding.map(_.embedRetries), Some(100L))
  }

  test("item 4: an embedder that stays throttled fails the call; the ledger keeps the retries") {
    val w = new World
    val stuck = new Embedder {
      def modelVersion = V1
      def dimension = Dimension
      def embed(texts: Vector[String]) = Left(StoreError.Transient("ThrottlingException"))
    }
    val v = runWith(w, embedder = stuck).report.store(V)
    assertEquals(v.status, StoreLoadStatus.Failed)
    assert(
      v.error.exists(
        _.message.startsWith("embedder: a batch of 50 stayed throttled after 4 attempts")
      ),
      v.error.toString
    )
    assertEquals((w.vectors.batches, w.vectorCount), (0, 0))
    val row = vectorRows(w)(Some(V1))
    assertEquals(row.state, LoadState.Failed)
    assertEquals(
      row.embedding.map(t => (t.embedded, t.embedRetries, t.passes)),
      Some((50L, 150L, 1L))
    )
    // The resume (a working embedder) loads everything.
    assertEquals(runWith(w).report.store(V).status, StoreLoadStatus.Loaded)
    assertEquals(
      vectorRows(w)(Some(V1)).embedding.map(t => (t.embedded, t.passes)),
      Some((299L, 2L))
    )
  }

  test("item 4: a record that stays throttled is a RunLedger failure record with its attempts") {
    val w = new World
    val victim = {
      val tmp = new World
      assert(runWith(tmp).report.complete)
      oneStored(tmp).key
    }
    val failing = new VectorStore {
      def describe(i: String) = w.vectors.describe(i)
      def getVectors(i: String, k: Vector[String]) = w.vectors.getVectors(i, k)
      def query(i: String, v: Vector[Float], k: Int, f: Option[VectorFilter]) =
        w.vectors.query(i, v, k, f)
      def putVectors(i: String, rs: Vector[VectorRecord]) = {
        val (bad, good) = rs.partition(_.key == victim)
        val done = if (good.isEmpty) Right(Vector.empty) else w.vectors.putVectors(i, good)
        done.map { ok =>
          val byKey = ok.map(r => r.key -> r).toMap
          rs.map(r =>
            byKey.getOrElse(r.key, VectorPutResult(r.key, Left(StoreError.Transient("SlowDown"))))
          )
        }
      }
    }
    val v = runWith(w, vectorStore = failing).report.store(V)
    assertEquals((v.status, v.written, v.rejectedRecords), (StoreLoadStatus.Loaded, 248L, 1L))
    assertEquals(count(v, "put-retries"), 3L, "one record re-sent after attempts 1, 2 and 3")
    val rows = ledgerRejects(w)
    assertEquals(rows.size, 1)
    val r = rows.head
    assertEquals(
      (r.recordKey, r.reason, r.attempts, r.embeddingModel),
      (victim, RejectReason.RetriesExhausted, 4, Some(V1))
    )
    assert(r.detail.contains("SlowDown"), r.detail)
    // The row key is in the model scope; the scope row counts the rejection and the retries.
    val key = ItemCodecs.RejectedRecordCodec.key(r).fold(e => fail(e.toString), identity)
    assert(key.sk.startsWith(s"reject#${loadId(w)}#model#$V1#"), key.sk)
    val row = vectorRows(w)(Some(V1))
    assertEquals((row.rejected, row.embedding.map(_.putRetries)), (1L, Some(3L)))
  }

  // ------------------------------------------------------------------ item 5: the Embedder port

  test("item 5: a non-canonical model version is refused before any write or embedding call") {
    Vector("Synthetic-test-only.v1", "synthetic-test-only:v1", "synthetic test").foreach { bad =>
      val w = new World
      var calls = 0
      val e = new Embedder {
        def modelVersion = bad
        def dimension = Dimension
        def embed(texts: Vector[String]) = { calls += 1; Left(StoreError.Permanent("never")) }
      }
      val v = runWith(w, embedder = e).report.store(V)
      assertEquals(v.status, StoreLoadStatus.Failed, bad)
      assert(v.error.exists(_.isInstanceOf[StoreError.Refused]), s"$bad: ${v.error}")
      assertEquals((calls, w.vectors.batches, w.vectorCount), (0, 0, 0), bad)
    }
    intercept[IllegalArgumentException](new SyntheticTestEmbedding(Dimension, "real-model.v1"))
    assertEquals(new SyntheticTestEmbedding(Dimension).modelVersion, V1)
  }

  // ------------------------------------------------------------------ ledger records

  test("ledger: rows of other loads are unchanged (no scope fields); totals are add-only") {
    val w = new World
    assert(runWith(w).report.complete)
    val others = w.ledgerStore
      .query(
        TableRole.RunLedger,
        KeyQuery(None, "run#" + Fixtures.RunId, SortKeyCondition.BeginsWith("load#"), 100)
      )
      .fold(e => fail(e.toString), identity)
    assertEquals(others.size, 4)
    others.foreach { i =>
      val body = AttrValue.string(i, Attrs.Body).fold(e => fail(e.toString), identity)
      val e = ItemCodecs.LoadEntryCodec.decode(i).fold(x => fail(x.toString), identity)
      if (e.targetStore == V) {
        assert(body.contains("\"embeddingModel\":\"" + V1 + "\""), body)
        assertEquals(i.get(Attrs.Body).isDefined, true)
      } else {
        assert(!body.contains("embeddingModel") && !body.contains("embedding"), body)
      }
    }
    val unscoped = vectorRows(w)(Some(V1)).copy(embeddingModel = None, embedding = None)
    assert(
      RunLedgerStates
        .advance(
          unscoped.copy(state = LoadState.Writing),
          LoadState.Loaded,
          LoadCounts(
            249L,
            0L,
            0L,
            Some(EmbeddingTotals(TestData.hex("m"), 1L, 1L, 0L, 0L, 0L, 0L))
          ),
          1L
        )
        .isLeft,
      "embedding totals need a model-scoped row"
    )
    val t = EmbeddingTotals(TestData.hex("m"), 249L, 100L, 1L, 2L, 149L, 1L)
    assertEquals(
      EmbeddingTotals.add(Some(t), t.copy(embedded = 149L, remaining = 0L)),
      Right(EmbeddingTotals(TestData.hex("m"), 249L, 249L, 2L, 4L, 0L, 2L))
    )
    // The latest call's manifest (and its item count) is recorded; counts keep adding up.
    assertEquals(
      EmbeddingTotals
        .add(Some(t), t.copy(workManifestSha256 = TestData.hex("n"), workItems = 247L))
        .map(x => (x.workManifestSha256, x.workItems, x.embedded, x.passes)),
      Right((TestData.hex("n"), 247L, 200L, 2L))
    )
    // Malformed totals are refused.
    assert(EmbeddingTotals.add(None, t.copy(remaining = 250L)).isLeft)
    assert(EmbeddingTotals.add(None, t.copy(embedded = -1L)).isLeft)
  }
}
