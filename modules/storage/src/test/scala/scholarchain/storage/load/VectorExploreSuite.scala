package scholarchain.storage.load

import scholarchain.core.TargetStore
import scholarchain.storage.Fixtures.*
import scholarchain.storage.{OpeningFixture, RunSettingsFixture, TestData, UpstreamFixture}
import scholarchain.storage.fake.*
import scholarchain.storage.model.*
import scholarchain.storage.port.{Embedder, KeyQuery, KeyValueReader, SortKeyCondition, VectorStore}

/**
 * SE-P7-09 (WP6; checklist V1-V6, L2, L3): the vector loader through the PRODUCTION gate. The
 * s3-vectors gate opens in production with the upstream fixtures alone (parquet-rows,
 * row-contracts, summary-evidence really performed; no check is marked here). Vectors are SYNTHETIC
 * test vectors. (File name kept from a removed exploration suite: agents cannot delete files; the
 * human may rename it to VectorLoadSuite.scala.)
 */
class VectorLoadSuite extends munit.FunSuite {

  private val V = TargetStore.S3Vectors
  private val Dim = OpeningFixture.Dimension
  private val Model = Some(SyntheticTestEmbedding.ModelVersion)

  private lazy val production: VerificationReport = LoadVerifier.verify(
    source(runDir(run1Root)),
    source(loadDir(run1Root), loadBounds),
    KeyValueReader.readOnly(new InMemoryKeyValueStore(100)),
    Some(UpstreamFixture.context)
  )

  private final class World(indexDim: Int = Dim) {
    val vectorStore =
      new InMemoryVectorStore(OpeningFixture.indexInfos(indexDim), OpeningFixture.openingLimits)
    val vectors = new RecordingVectorStore(vectorStore)
    val objectStore = new InMemoryObjectStore(1000)
    val objects = new RecordingObjectStore(objectStore)
    val registry = new RecordingKeyValueStore(new InMemoryKeyValueStore(1000))
    val ledgerStore = new InMemoryKeyValueStore(1000)
    val ledger = new RecordingKeyValueStore(ledgerStore)
    val clock = new ManualClock(1_700_000_000_000L)
    val sleeper = new RecordingSleeper(clock)
    def targets(
        store: VectorStore = vectors,
        embedder: Embedder = new SyntheticTestEmbedding(Dim)
    ) =
      VectorLoadTargets(
        store,
        objects,
        registry,
        ledger,
        embedder,
        clock,
        sleeper,
        new FractionJitter(0.5)
      )
    def dataWrites: Int = vectors.batches + objects.counts.calls + registry.counts.calls
    def vectorCount: Int = VectorIndexes.all.map(i => vectorStore.count(i.name)).sum
  }

  private def load(
      w: World,
      config: VectorLoadConfig = OpeningFixture.vectorConfig(),
      store: Option[VectorStore] = None,
      embedder: Embedder = new SyntheticTestEmbedding(Dim)
  ): Either[StoreError, VectorLoadReport] =
    LoadGate.proceed(production, V)(p =>
      VectorLoad.load(
        p,
        RunSettingsFixture.parquetBounds,
        TestData.layout,
        config,
        w.targets(store.getOrElse(w.vectors), embedder)
      )
    ) match {
      case Left(rs) => fail(s"the production gate refused s3-vectors: $rs")
      case Right(out) => out
    }

  private def ok(r: Either[StoreError, VectorLoadReport]): VectorLoadReport =
    r.fold(e => fail(e.toString), identity)

  /** A store that fails the listed keys permanently (per record) and passes everything else. */
  private def failing(w: World, keys: Set[String], err: StoreError): VectorStore = new VectorStore {
    def describe(i: String) = w.vectors.describe(i)
    def getVectors(i: String, k: Vector[String]) = w.vectors.getVectors(i, k)
    def query(i: String, v: Vector[Float], k: Int, f: Option[VectorFilter]) =
      w.vectors.query(i, v, k, f)
    def putVectors(i: String, rs: Vector[VectorRecord]) = {
      val (bad, good) = rs.partition(r => keys.contains(r.key))
      val done =
        if (good.isEmpty) Right(Vector.empty) else w.vectors.putVectors(i, good)
      done.map { ok =>
        val byKey = ok.map(r => r.key -> r).toMap
        rs.map(r => byKey.getOrElse(r.key, VectorPutResult(r.key, Left(err))))
      }
    }
  }

  private lazy val reference: (World, VectorLoadReport) = {
    val w = new World
    (w, ok(load(w)))
  }

  private def someKeys(n: Int): Vector[String] = {
    val (w, _) = reference
    w.vectorStore
      .query("researcher-profile-v1", Vector.fill(Dim)(0.3f), n, None)
      .fold(e => fail(e.toString), identity)
      .map(_.key)
  }

  private def ledgerRejects(w: World): Vector[RejectedRecord] =
    w.ledgerStore
      .query(
        TableRole.RunLedger,
        KeyQuery(None, "run#" + RunId, SortKeyCondition.BeginsWith("reject#"), 1000)
      )
      .fold(e => fail(e.toString), identity)
      .map(i => ItemCodecs.RejectedRecordCodec.decode(i).fold(e => fail(e.toString), identity))

  test("production: the s3-vectors gate opens with its checks really performed") {
    assertEquals(production.rejections, Vector.empty)
    assertEquals(production.outstandingFor(V), Vector.empty)
  }

  test("V6: 249 rows routed to the five indexes (176/51/8/9/5), batched at the configured size") {
    val (w, r) = reference
    assertEquals(r.rows, 249)
    assertEquals(
      r.indexes.map(i => (i.index, i.routed, i.written, i.stored)),
      Vector(
        ("researcher-profile-v1", 176, 176L, 176),
        ("collaboration-community-v1", 51, 51L, 51),
        ("paper-abstract-v1", 8, 8L, 8),
        ("collaboration-path-v1", 9, 9L, 9),
        ("topic-institution-summary-v1", 5, 5L, 5)
      )
    )
    assertEquals(
      (r.written, r.alreadyPresent, r.rejected, r.finalState),
      (249L, 0L, Vector.empty, LoadState.Loaded)
    )
    // batchSize 50: 4 + 2 + 1 + 1 + 1 put batches, one index per batch.
    assertEquals(w.vectors.batches, 9)
    assertEquals((r.textObjects, r.textWritten, r.registryRows), (249, 249L, 249))
  }

  test(
    "V3, L3: metadata holds compact fields and the text pointer; provenance on vector and text"
  ) {
    val (w, _) = reference
    def all(ix: String) =
      w.vectorStore
        .query(ix, Vector.fill(Dim)(0.3f), 30, None)
        .fold(e => fail(e.toString), identity)
    val community = all("collaboration-community-v1").head.metadata
    assertEquals(
      community.string(MetadataField.CommunityId),
      community.string(MetadataField.SubjectId)
    )
    val path = all("collaboration-path-v1").head.metadata
    assertEquals(
      path.get(MetadataField.ResearcherIds),
      None,
      "no researcher ID is cited: none invented"
    )
    assert(path.get(MetadataField.WindowStart).isDefined)
    val paper = all("paper-abstract-v1").head.metadata
    assertEquals(paper.get(MetadataField.WindowStart), None, "work summaries are not windowed")
    assert(paper.get(MetadataField.ResearcherIds).isDefined, "authors cited by the facts")
    val profile = all("researcher-profile-v1").head.metadata
    assertEquals(
      profile.get(MetadataField.ResearcherIds),
      profile.string(MetadataField.SubjectId).map(s => MetaValue.StrList(Vector(s)))
    )
    // Text object provenance: its registry row names run, load, source part and record schema.
    val key = profile.string(MetadataField.TextS3Key).get
    val row = TableKeys.ArtifactRegistry
      .artifact(RunId, Zone.SemanticContext, key)
      .flatMap(w.registry.get(TableRole.ArtifactRegistry, _))
      .flatMap(i => ItemCodecs.ArtifactCodec.decode(i.get))
      .fold(e => fail(e.toString), identity)
    assertEquals(row.loadId, profile.string(MetadataField.LoadId))
    assertEquals(row.sourcePath, profile.string(MetadataField.SourcePath))
    assertEquals(row.recordSchemaVersion, Some("scholarchain.semantic-summary-input.v1"))
    assertEquals(Some(row.sha256), profile.string(MetadataField.TextSha256))
    assert(
      key.startsWith("semantic-context/loads/") && key.contains("/text/") && key.endsWith(".txt"),
      key
    )
  }

  test("V4: filters on researcher, year window, source, topic, community and document type") {
    val (w, _) = reference
    import MetadataField.*
    def keys(ix: String, f: VectorFilter) =
      w.vectorStore
        .query(ix, Vector.fill(Dim)(0.3f), 30, Some(f))
        .fold(e => fail(e.toString), identity)
        .map(_.key)
        .toSet
    val p = "researcher-profile-v1"
    val one = w.vectorStore.query(p, Vector.fill(Dim)(0.3f), 1, None).toOption.get.head
    val researcher = one.metadata.string(SubjectId).get
    val byResearcher = keys(p, VectorFilter.Eq(ResearcherIds, researcher))
    assert(byResearcher.contains(one.key) && byResearcher.size <= 2, byResearcher.toString)
    assertEquals(keys(p, VectorFilter.In(ResearcherIds, Vector(researcher))), byResearcher)
    val start = one.metadata.get(WindowStart).collect { case MetaValue.Num(n) => n }.get
    val inWindow = keys(p, VectorFilter.Range(WindowStart, start, start))
    assert(inWindow.contains(one.key))
    assertEquals(keys(p, VectorFilter.Range(WindowEnd, 3000, 3001)), Set.empty[String])
    assertEquals(keys(p, VectorFilter.Eq(SourceDataset, "dblp")), Set.empty[String])
    assertEquals(keys(p, VectorFilter.Eq(SourceDataset, "scholarchain-derived")).size, 30)
    assertEquals(keys(p, VectorFilter.Eq(DocumentTypeField, "researcher-profile")).size, 30)
    val c = "collaboration-community-v1"
    val comm = w.vectorStore.query(c, Vector.fill(Dim)(0.3f), 1, None).toOption.get.head
    assertEquals(
      keys(c, VectorFilter.Eq(CommunityId, comm.metadata.string(CommunityId).get)),
      Set(comm.key)
    )
    val a = "paper-abstract-v1"
    val withTopic = w.vectorStore
      .query(a, Vector.fill(Dim)(0.3f), 8, None)
      .toOption
      .get
      .find(_.metadata.get(TopicIds).isDefined)
      .get
    val topic = withTopic.metadata.get(TopicIds).collect { case MetaValue.StrList(t) => t.head }.get
    assert(keys(a, VectorFilter.Eq(TopicIds, topic)).contains(withTopic.key))
    // Institution: the fixture cites no institution ID, so no vector has institutionIds (the
    // filter is valid and matches none); the fake's institution filter is tested on synthetic
    // metadata in VectorMetadataSuite.
    assertEquals(
      keys(a, VectorFilter.Eq(InstitutionIds, TestData.institutionId)),
      Set.empty[String]
    )
  }

  test("V5: transient throttling then success: bounded backoff with jitter, every vector once") {
    val w = new World
    w.vectorStore.failNextWrites(
      StoreError.Transient("ThrottlingException"),
      StoreError.Transient("ThrottlingException")
    )
    val r = ok(load(w))
    assertEquals((r.written, r.finalState), (249L, LoadState.Loaded))
    assertEquals(w.sleeper.sleeps, Vector(50L, 100L))
    assertEquals(w.vectorStore.count("researcher-profile-v1"), 176)
  }

  test("V5: a permanent per-record failure becomes a rejected record; the run continues") {
    val w = new World
    val bad = someKeys(2).toSet
    val r = ok(load(w, store = Some(failing(w, bad, StoreError.Permanent("ValidationException")))))
    assertEquals((r.written, r.finalState), (247L, LoadState.Loaded))
    assertEquals(r.rejected.map(_.recordKey).toSet, bad)
    assert(r.rejected.forall(_.reason == RejectReason.PermanentWriteFailure))
    assertEquals(r.indexes.head.rejected, 2)
    val rows = ledgerRejects(w)
    assertEquals(rows.map(_.recordKey).toSet, bad)
    assert(rows.forall(x => x.runId == RunId && x.targetStore == V && x.attempts == 1))
    // CR-P7-11 m1: the evidence is keyed by the call's write generation (planned 1, verified 2,
    // writing 3 for a first call).
    assertEquals(rows.map(_.generation).toSet, Set(3L))
    val ref = TestData.layout
      .rejects(production.verified.get.load(V).get.loadId, rows.head.generation.toInt, Model)
      .toOption
      .get
    // SE-P8-01: the evidence is scoped by the embedding model version (rows, object, set).
    assert(rows.forall(_.embeddingModel == Model), rows.head.toString)
    assert(ref.key.contains("/rejects/model=synthetic-test-only.v1/part-"), ref.key)
    val body = w.objectStore.get(ref).toOption.flatten.getOrElse(fail("no rejects object"))._2
    val lines = body.utf8.split("\n").toVector
    assertEquals(lines.map(RejectedRecord.parseLine(_).map(_.recordKey)).toSet, bad.map(Right(_)))
    val entry = w.ledgerStore
      .query(
        TableRole.RunLedger,
        KeyQuery(None, "run#" + RunId, SortKeyCondition.BeginsWith("load#"), 10)
      )
      .toOption
      .get
      .map(i => ItemCodecs.LoadEntryCodec.decode(i).toOption.get)
      .head
    assertEquals((entry.state, entry.written, entry.rejected), (LoadState.Loaded, 247L, 2L))
  }

  test("V5: per-record throttling that never clears is rejected as retries-exhausted") {
    val w = new World
    val bad = someKeys(1).toSet
    val r = ok(load(w, store = Some(failing(w, bad, StoreError.Transient("SlowDown")))))
    assertEquals(
      r.rejected.map(r => (r.recordKey, r.reason, r.attempts)),
      bad.toVector.map(k => (k, RejectReason.RetriesExhausted, 4))
    )
    assertEquals(w.sleeper.sleeps, Vector(50L, 100L, 200L))
  }

  test("V5: more rejections than maxRejected mark the load failed") {
    val w = new World
    val bad = someKeys(3).toSet
    val out = load(
      w,
      OpeningFixture.vectorConfig(maxRejected = 2),
      Some(failing(w, bad, StoreError.Permanent("ValidationException")))
    )
    assert(out.left.exists(_.message.contains("exceed maxRejected 2")), out.toString)
    assertEquals(ledgerRejects(w).size, 3, "the rejected records are still recorded")
  }

  test("V3: oversize metadata (per-value cap below the text key) is refused before any write") {
    val w = new World
    val tiny = TestData.limits.copy(maxValueBytes = 100)
    val out = load(w, OpeningFixture.vectorConfig(limits = tiny))
    assert(
      out.left.exists(_.message.contains("249 rejected records exceed maxRejected 5")),
      out.toString
    )
    assertEquals((w.vectors.batches, w.objectStore.size), (0, 1), "only the rejects object")
    val rows = ledgerRejects(w)
    assertEquals(rows.size, 249)
    assert(rows.forall(_.reason == RejectReason.OversizeMetadata), rows.head.toString)
    assert(rows.head.detail.contains("per-value cap"), rows.head.detail)
  }

  test("V3/V5: under a 256-byte per-value cap two work summaries are rejected; the run continues") {
    val w = new World
    val r = ok(load(w, OpeningFixture.vectorConfig(limits = TestData.limits)))
    assertEquals((r.written, r.finalState), (247L, LoadState.Loaded))
    assertEquals(r.rejected.size, 2)
    assert(r.rejected.forall(x => x.reason == RejectReason.OversizeMetadata && x.attempts == 0))
    assert(r.rejected.forall(_.recordKey.startsWith("paper-abstract-v1/")))
    assert(r.rejected.forall(_.detail.contains("researcherIds")), r.rejected.map(_.detail).toString)
    assertEquals(
      r.indexes.find(_.index == "paper-abstract-v1").map(i => (i.routed, i.written, i.rejected)),
      Some((8, 6L, 2))
    )
    assertEquals(ledgerRejects(w).size, 2)
  }

  test("dimension: an index, embedder or vector dimension mismatch is refused") {
    val w = new World(indexDim = 4)
    val out = load(w)
    assert(out.left.exists(_.message.contains("dimension 4 is not the configured 8")), out.toString)
    assertEquals(w.dataWrites + w.ledger.counts.calls, 0)
    val w2 = new World
    val out2 = load(w2, embedder = new SyntheticTestEmbedding(4))
    assert(out2.left.exists(_.message.contains("embedder dimension 4")), out2.toString)
    assertEquals(w2.dataWrites + w2.ledger.counts.calls, 0)
    // One vector of the wrong length: that record is rejected (dimension-mismatch).
    val w3 = new World
    val short = new Embedder {
      private val real = new SyntheticTestEmbedding(Dim)
      def modelVersion = real.modelVersion
      def dimension = Dim
      def embed(texts: Vector[String]) =
        real.embed(texts).map(vs => vs.zipWithIndex.map((v, i) => if (i == 0) v.take(3) else v))
    }
    val r = ok(load(w3, OpeningFixture.vectorConfig(maxRejected = 10), embedder = short))
    // CR-P7-11 m4: one embedding call per put batch (one index per batch, at most 50): the first
    // vector of each of the 4 + 2 + 1 + 1 + 1 batches is short.
    assertEquals(r.rejected.size, 9)
    assert(r.rejected.forall(_.reason == RejectReason.DimensionMismatch))
    assertEquals(r.written + r.rejected.size, 249L)
  }

  test("an absent index and undeclared non-filterable keys are refused before any write") {
    val w = new World
    val partial = new InMemoryVectorStore(OpeningFixture.indexInfos().tail, TestData.limits)
    val out = load(w, store = Some(partial))
    assert(
      out.left.exists(_.message.contains("absent (index creation is Terraform)")),
      out.toString
    )
    val undeclared = new InMemoryVectorStore(
      VectorIndexes.all.map(i =>
        i -> IndexInfo(i.name, Dim, DistanceMetric.Cosine, Vector("textS3Key"))
      ),
      TestData.limits
    )
    val out2 = load(w, store = Some(undeclared))
    assert(out2.left.exists(_.message.contains("not declared non-filterable")), out2.toString)
    assertEquals(w.dataWrites + w.ledger.counts.calls, 0)
  }

  test("embedding policy: production refuses synthetic vectors; tests refuse a real model") {
    val w = new World
    val prod = load(w, OpeningFixture.vectorConfig(policy = EmbeddingPolicy.RealModelsOnly))
    assert(
      prod.left.exists(_.message.contains("refused by the RealModelsOnly policy")),
      prod.toString
    )
    val real = new Embedder {
      def modelVersion = "some-real-model.v1"
      def dimension = Dim
      def embed(texts: Vector[String]) = fail("a real model must never be called in tests")
    }
    val test = load(w, embedder = real)
    assert(test.left.exists(_.message.contains("accepts only synthetic")), test.toString)
    assertEquals(w.dataWrites + w.ledger.counts.calls, 0)
    assert(EmbeddingPolicy.isSynthetic(SyntheticTestEmbedding.ModelVersion))
  }

  test("configuration is validated: exactly five indexes, batch size within the service cap") {
    val idx = VectorIndexes.all.map(i => VectorIndexSettings(i.name, Dim, DistanceMetric.Cosine))
    val retry = RetryPolicy.of(3, 10L, 100L).toOption.get
    def of(ix: Vector[VectorIndexSettings] = idx, batch: Int = 10, maxRejected: Int = 0) =
      VectorLoadConfig.of(
        TestData.limits,
        ix,
        batch,
        retry,
        maxRejected,
        1024,
        512,
        EmbeddingPolicy.SyntheticTestOnly,
        100
      )
    assert(of().isRight)
    assert(of(ix = idx.tail).isLeft)
    assert(of(ix = idx :+ idx.head).isLeft)
    assert(of(batch = TestData.limits.maxBatchSize + 1).isLeft)
    assert(of(batch = 0).isLeft)
    assert(of(maxRejected = -1).isLeft)
    assert(RetryPolicy.of(0, 1L, 1L).isLeft)
    assert(RetryPolicy.of(2, 10L, 5L).isLeft)
    assertEquals(
      (1 to 6).map(RetryPolicy.of(9, 100L, 1600L).toOption.get.delayBound),
      Vector(100L, 200L, 400L, 800L, 1600L, 1600L)
    )
  }

  test("L2: same ledger rerun is skipped; fresh ledger finds every vector and text present") {
    val (w, _) = reference
    val before = w.dataWrites
    val again = ok(load(w))
    assertEquals((again.skipped, again.written), (true, 0L))
    assertEquals(w.dataWrites, before)
    val fresh = new World
    val r = LoadGate.proceed(production, V)(p =>
      VectorLoad.load(
        p,
        RunSettingsFixture.parquetBounds,
        TestData.layout,
        OpeningFixture.vectorConfig(),
        VectorLoadTargets(
          w.vectors,
          w.objects,
          w.registry,
          fresh.ledger,
          new SyntheticTestEmbedding(Dim),
          fresh.clock,
          fresh.sleeper,
          new FractionJitter(0.5)
        )
      )
    )
    val rep = r.toOption.get.toOption.get
    assertEquals(
      (rep.written, rep.alreadyPresent, rep.textWritten, rep.textPresent),
      (0L, 249L, 0L, 249L)
    )
  }

  // ------------------------------------------------------------------ CR-P7-11 M1 (direct)

  private def plantOwner(w: World, t: TargetStore): Unit =
    ItemCodecs.StoreOwnershipCodec
      .encode(
        StoreOwnership(StoreOwnership.SchemaVersion, t, TestData.otherRunId, TestData.hex("o"))
      )
      .flatMap(w.ledgerStore.putIfAbsent(TableRole.RunLedger, _))
      .fold(e => fail(e.toString), _ => ())

  private def ownerOf(w: World, t: TargetStore): Option[String] =
    LoadLedger.ownerOf(w.ledgerStore, t).fold(e => fail(e.toString), _.map(_.runId))

  test("CR-P7-11 M1: the vector loader refuses when ANY store it writes is another run's") {
    assertEquals(VectorLoad.WrittenStores, Vector(V, TargetStore.S3, TargetStore.DynamoDb))
    VectorLoad.WrittenStores.foreach { owned =>
      val w = new World
      plantOwner(w, owned)
      val counting = new OpeningFixture.CountingEmbedder(new SyntheticTestEmbedding(Dim))
      val out = load(w, embedder = counting)
      assert(
        out.left.exists(e =>
          e.isInstanceOf[StoreError.Conflict] &&
            e.message.startsWith(s"${owned.tag}: this store instance is owned by run")
        ),
        s"${owned.tag}: $out"
      )
      assertEquals(w.dataWrites + w.ledger.counts.calls, 0, s"${owned.tag}: zero writes")
      assertEquals(counting.calls, 0, s"${owned.tag}: zero embedding calls")
      VectorLoad.WrittenStores.filterNot(_ == owned).foreach(t => assertEquals(ownerOf(w, t), None))
    }
    // A store the vector loader does not write (the graph) does not concern it.
    val w = new World
    plantOwner(w, TargetStore.NeptuneBulkLoad)
    assertEquals(ok(load(w)).written, 249L)
    VectorLoad.WrittenStores.foreach(t => assertEquals(ownerOf(w, t), Some(RunId), t.tag))
  }

  // ------------------------------------------------------------------ CR-P7-11 m4

  test(
    "CR-P7-11 m4: embedding happens only after the ledger clears the call, one batch at a time"
  ) {
    val w = new World
    val counting = new OpeningFixture.CountingEmbedder(new SyntheticTestEmbedding(Dim))
    val r = ok(load(w, embedder = counting))
    assertEquals((counting.calls, counting.texts, r.embedded), (9, 249L, 249L))
    // Rerun of the loaded load (same ledger): skipped, zero embedding calls.
    val again = ok(load(w, embedder = counting))
    assertEquals((again.skipped, counting.calls, again.embedded), (true, 9, 0L))
    // An index or embedder refusal also never embeds.
    val w2 = new World(indexDim = 4)
    val c2 = new OpeningFixture.CountingEmbedder(new SyntheticTestEmbedding(Dim))
    assert(load(w2, embedder = c2).isLeft)
    assertEquals(c2.calls, 0)
  }

  // ------------------------------------------------------------------ CR-P7-11 m1

  private def rejectsObjects(w: World): Vector[(String, Vector[String])] = {
    val loadId = production.verified.get.load(V).get.loadId
    val prefix =
      TestData.layout.rejects(loadId, 0, Model).toOption.get.key.stripSuffix("part-00000.jsonl")
    w.objectStore
      .list("test-data-bucket", prefix, 1000)
      .fold(e => fail(e.toString), identity)
      .map { m =>
        val body = w.objectStore.get(m.ref).toOption.flatten.get._2.utf8
        m.ref.key.stripPrefix(prefix) -> body.split("\n").toVector.filter(_.nonEmpty)
      }
  }

  private def keysOf(lines: Vector[String]): Set[String] =
    lines.map(RejectedRecord.parseLine(_).fold(e => fail(e.toString), _.recordKey)).toSet

  test("CR-P7-11 m1 (a): a rerun that rejects another set (config change) is not wedged") {
    val w = new World
    // Call 1, 256-byte per-value cap: two work summaries are rejected before any write, one more
    // than maxRejected 1 allows: the load fails with only its rejects evidence written.
    val out1 = load(w, OpeningFixture.vectorConfig(limits = TestData.limits, maxRejected = 1))
    assert(
      out1.left.exists(_.message.contains("2 rejected records exceed maxRejected 1")),
      out1.toString
    )
    assertEquals((w.vectors.batches, w.vectorCount), (0, 0))
    val first = rejectsObjects(w).flatMap(o => keysOf(o._2)).toSet
    assertEquals(first.size, 2)
    assert(first.forall(_.startsWith("paper-abstract-v1/")), first.toString)
    // Call 2, another configuration (1024-byte cap) rejecting another set: three profiles that
    // the store refuses permanently. A single create-once rejects object would Conflict here.
    val second = someKeys(3).toSet
    val r = ok(
      load(
        w,
        OpeningFixture.vectorConfig(maxRejected = 5),
        Some(failing(w, second, StoreError.Permanent("ValidationException")))
      )
    )
    assertEquals((r.finalState, r.rejected.map(_.recordKey).toSet), (LoadState.Loaded, second))
    val objs = rejectsObjects(w)
    println(s"CR-P7-11 m1 (a) rejects objects: ${objs.map((k, ls) => s"$k:${ls.size}")}")
    assertEquals(objs.map(_._1), Vector("part-00003.jsonl", "part-00005.jsonl"))
    assertEquals(keysOf(objs(0)._2), first, "history: generation 3")
    assertEquals(keysOf(objs(1)._2), second, "the current (highest) generation")
    // Both first-call rejections are stored now; the current evidence does not name them.
    first.foreach(k =>
      assertEquals(
        w.vectorStore.getVectors("paper-abstract-v1", Vector(k)).map(_.size),
        Right(1),
        k
      )
    )
    assertEquals(w.vectorCount, 249 - 3)
  }

  test("CR-P7-11 m1 (b): the same record rejected again with another error detail is not wedged") {
    val w = new World
    val bad = someKeys(3).toSet
    val out1 = load(
      w,
      OpeningFixture.vectorConfig(maxRejected = 2),
      Some(failing(w, bad, StoreError.Permanent("ValidationException #1")))
    )
    assert(out1.isLeft)
    val r = ok(
      load(
        w,
        OpeningFixture.vectorConfig(maxRejected = 5),
        Some(failing(w, bad, StoreError.Permanent("ValidationException #2")))
      )
    )
    assertEquals(r.finalState, LoadState.Loaded)
    assert(r.rejected.forall(_.detail.contains("#2")), r.rejected.map(_.detail).toString)
    val rows = ledgerRejects(w)
    val byGeneration = rows.groupBy(_.generation)
    assertEquals(byGeneration.keySet, Set(3L, 5L))
    assertEquals(
      byGeneration.map((g, rs) => g -> rs.map(_.recordKey).toSet),
      Map(3L -> bad, 5L -> bad)
    )
    assert(byGeneration(3L).forall(_.detail.contains("#1")), byGeneration(3L).toString)
    assert(byGeneration(5L).forall(_.detail.contains("#2")), byGeneration(5L).toString)
  }

  test("CR-P7-11 m1 (c): a successful rerun leaves no stale rejection as the current evidence") {
    val w = new World
    val bad = someKeys(3).toSet
    assert(
      load(
        w,
        OpeningFixture.vectorConfig(maxRejected = 2),
        Some(failing(w, bad, StoreError.Permanent("ValidationException")))
      ).isLeft
    )
    val r = ok(load(w))
    assertEquals((r.finalState, r.rejected), (LoadState.Loaded, Vector.empty))
    assertEquals(r.written + r.alreadyPresent, 249L, "the rerun wrote the rest, found the others")
    assertEquals(r.embedded, r.written, "only the missing vectors were embedded")
    assertEquals(r.indexes.map(_.rejected).sum, 0)
    assertEquals(r.indexes.map(_.stored).sum, 249)
    val objs = rejectsObjects(w)
    assertEquals(objs.map(_._1), Vector("part-00003.jsonl", "part-00005.jsonl"))
    assertEquals(keysOf(objs(0)._2), bad, "history: the failed call's rejections")
    assertEquals(objs(1)._2, Vector.empty, "current: nothing is rejected")
    // The ledger row: loaded, 249 written in total; SE-P7-11 item 4: rejected is the CURRENT set
    // (generation 5, empty), not the 3 rows of the failed generation 3.
    assertEquals(
      (loadEntry(w).state, loadEntry(w).written, loadEntry(w).rejected),
      (LoadState.Loaded, 249L, 0L)
    )
    assertEquals(summaries(w).map(s => s.generation -> s.count), Vector(3L -> 3L, 5L -> 0L))
  }

  // ------------------------------------------------------------------ SE-P7-11 items 4, F2

  private def loadEntry(w: World): LoadEntry =
    w.ledgerStore
      .query(
        TableRole.RunLedger,
        KeyQuery(None, "run#" + RunId, SortKeyCondition.BeginsWith("load#"), 10)
      )
      .fold(e => fail(e.toString), identity)
      .map(i => ItemCodecs.LoadEntryCodec.decode(i).fold(e => fail(e.toString), identity))
      .head

  /** RL-8: the rejection-set summaries of the vector load, in generation order. */
  private def summaries(w: World): Vector[RejectionSet] = {
    val loadId = production.verified.get.load(V).get.loadId
    val prefix = TableKeys.RunLedger.rejectionSetPrefix(loadId).toOption.get
    w.ledgerStore
      .query(
        TableRole.RunLedger,
        KeyQuery(None, "run#" + RunId, SortKeyCondition.BeginsWith(prefix), 1000)
      )
      .fold(e => fail(e.toString), identity)
      .map(i => ItemCodecs.RejectionSetCodec.decode(i).fold(e => fail(e.toString), identity))
  }

  test(
    "SE-P7-11 item 4: the ledger rejected count is the current generation's set (CR-P7-11 (a))"
  ) {
    val w = new World
    val out1 = load(w, OpeningFixture.vectorConfig(limits = TestData.limits, maxRejected = 1))
    assert(out1.isLeft)
    // SE-P8-03 (CR-P8-02 m-A): the failed call's 2 rejections are recorded as an INCOMPLETE set,
    // which is never current and does not set the ledger count.
    assertEquals((loadEntry(w).state, loadEntry(w).rejected), (LoadState.Failed, 0L))
    assertEquals(summaries(w).map(s => (s.count, s.complete)), Vector((2L, false)))
    assertEquals(RejectionSet.current(summaries(w)), None)
    val second = someKeys(3).toSet
    val r = ok(
      load(
        w,
        OpeningFixture.vectorConfig(maxRejected = 5),
        Some(failing(w, second, StoreError.Permanent("ValidationException")))
      )
    )
    assertEquals(r.finalState, LoadState.Loaded)
    // Not 2 + 3 = 5: the current set is generation 5's three records.
    assertEquals((loadEntry(w).state, loadEntry(w).rejected), (LoadState.Loaded, 3L))
    val current = summaries(w).last
    assertEquals((current.generation, current.count, current.complete), (5L, 3L, true))
    assertEquals(RejectionSet.current(summaries(w)), Some(current))
    assertEquals(
      ledgerRejects(w).filter(_.generation == current.generation).map(_.recordKey).toSet,
      second
    )
    // A clean rerun is skipped (loaded); the ledger row may decrease only via a recorded set.
    val tightened = RunLedgerStates.advance(
      loadEntry(w).copy(state = LoadState.Writing),
      LoadState.Loaded,
      LoadCounts(249L, 0L, 0L),
      1L
    )
    assert(tightened.isRight, "rejected may decrease (a new current set)")
    assert(
      RunLedgerStates
        .advance(
          loadEntry(w).copy(state = LoadState.Writing),
          LoadState.Loaded,
          LoadCounts(1L, 0L, 0L),
          1L
        )
        .isLeft,
      "written never decreases"
    )
  }

  test("F2 (a): stale evidence with a ledger count of 0 is superseded by an empty current set") {
    val w = new World
    val bad = someKeys(3).toSet
    // Call 1 records 3 rejections at generation 3, but its row stays `writing` (the failed mark is
    // refused), so the ledger count stays 0 while the stored evidence names 3 records.
    val stuck = new scholarchain.storage.port.KeyValueStore {
      def get(t: TableRole, k: PrimaryKey) = w.ledger.get(t, k)
      def query(t: TableRole, q: KeyQuery) = w.ledger.query(t, q)
      def putIfAbsent(t: TableRole, i: Item) = w.ledger.putIfAbsent(t, i)
      def putIfVersion(t: TableRole, i: Item, v: Long) =
        if (ItemCodecs.LoadEntryCodec.decode(i).exists(_.state == LoadState.Failed))
          Left(StoreError.Transient("ledger unavailable"))
        else w.ledger.putIfVersion(t, i, v)
    }
    val out1 = LoadGate.proceed(production, V)(p =>
      VectorLoad.load(
        p,
        RunSettingsFixture.parquetBounds,
        TestData.layout,
        OpeningFixture.vectorConfig(maxRejected = 2),
        w.targets(failing(w, bad, StoreError.Permanent("ValidationException"))).copy(ledger = stuck)
      )
    )
    assert(out1.exists(_.isLeft), out1.toString)
    assertEquals((loadEntry(w).state, loadEntry(w).rejected), (LoadState.Writing, 0L))
    assertEquals(summaries(w).map(s => s.generation -> s.count), Vector(3L -> 3L))
    // Call 2 is clean. Deciding from the ledger count (0) would record nothing and leave
    // generation 3 as the highest evidence; deciding from the stored summaries records an empty
    // generation-5 set, which is current.
    val r = ok(load(w))
    assertEquals((r.finalState, r.rejected), (LoadState.Loaded, Vector.empty))
    assertEquals(summaries(w).map(s => s.generation -> s.count), Vector(3L -> 3L, 5L -> 0L))
    assertEquals(rejectsObjects(w).map(_._1), Vector("part-00003.jsonl", "part-00005.jsonl"))
    assertEquals(rejectsObjects(w)(1)._2, Vector.empty)
    assertEquals(loadEntry(w).rejected, 0L)
  }

  test("F2 (b): rows without a summary are incomplete and never current; a clean load adds none") {
    val w = new World
    val bad = someKeys(3).toSet
    // Call 1 writes its 3 rejection rows, then the rejects object put fails: no summary.
    val loadId = production.verified.get.load(V).get.loadId
    val rejectsPrefix =
      TestData.layout.rejects(loadId, 0, Model).toOption.get.key.stripSuffix("part-00000.jsonl")
    val brokenObjects = new scholarchain.storage.port.ObjectStore {
      private val u = w.objects
      def head(r: ObjectRef) = u.head(r)
      def get(r: ObjectRef) = u.get(r)
      def list(b: String, p: String, n: Int) = u.list(b, p, n)
      def putCreateOnce(r: ObjectRef, b: Bytes, c: String) =
        if (r.key.startsWith(rejectsPrefix)) Left(StoreError.Transient("object store unavailable"))
        else u.putCreateOnce(r, b, c)
    }
    val out1 = LoadGate.proceed(production, V)(p =>
      VectorLoad.load(
        p,
        RunSettingsFixture.parquetBounds,
        TestData.layout,
        OpeningFixture.vectorConfig(maxRejected = 2),
        w.targets(failing(w, bad, StoreError.Permanent("ValidationException")))
          .copy(objects = brokenObjects)
      )
    )
    assert(out1.exists(_.isLeft), out1.toString)
    assertEquals(ledgerRejects(w).map(_.generation).toSet, Set(3L), "orphan rows exist")
    assertEquals(summaries(w), Vector.empty, "no summary: incomplete set")
    assertEquals(loadEntry(w).rejected, 0L, "no set recorded")
    // Call 2 is clean: no summary exists, so nothing is current and nothing is added.
    val r = ok(load(w))
    assertEquals((r.finalState, r.rejected), (LoadState.Loaded, Vector.empty))
    assertEquals(summaries(w), Vector.empty)
    assertEquals(rejectsObjects(w), Vector.empty)
    assertEquals(loadEntry(w).rejected, 0L)
  }

  test(
    "CR-P7-11 m1: a call that stopped with its row left `writing` does not share its generation"
  ) {
    val w = new World
    val bad = someKeys(3).toSet
    // Call 1 cannot mark its row failed (the ledger refuses that one transition): it stays writing.
    val stuck = new scholarchain.storage.port.KeyValueStore {
      def get(t: TableRole, k: PrimaryKey) = w.ledger.get(t, k)
      def query(t: TableRole, q: KeyQuery) = w.ledger.query(t, q)
      def putIfAbsent(t: TableRole, i: Item) = w.ledger.putIfAbsent(t, i)
      def putIfVersion(t: TableRole, i: Item, v: Long) =
        if (ItemCodecs.LoadEntryCodec.decode(i).exists(_.state == LoadState.Failed))
          Left(StoreError.Transient("ledger unavailable"))
        else w.ledger.putIfVersion(t, i, v)
    }
    val out1 = LoadGate.proceed(production, V)(p =>
      VectorLoad.load(
        p,
        RunSettingsFixture.parquetBounds,
        TestData.layout,
        OpeningFixture.vectorConfig(maxRejected = 2),
        w.targets(failing(w, bad, StoreError.Permanent("ValidationException #1")))
          .copy(ledger = stuck)
      )
    )
    assert(out1.exists(_.isLeft), out1.toString)
    def entry = w.ledgerStore
      .query(
        TableRole.RunLedger,
        KeyQuery(None, "run#" + RunId, SortKeyCondition.BeginsWith("load#"), 10)
      )
      .toOption
      .get
      .map(i => ItemCodecs.LoadEntryCodec.decode(i).toOption.get)
      .head
    assertEquals((entry.state, entry.version), (LoadState.Writing, 3L))
    // Call 2 finds the row writing: it is moved to failed first, so call 2 writes generation 5,
    // and its rejections (another error detail) never collide with generation 3's rows.
    val r = ok(
      load(
        w,
        OpeningFixture.vectorConfig(maxRejected = 5),
        Some(failing(w, bad, StoreError.Permanent("ValidationException #2")))
      )
    )
    assertEquals(r.finalState, LoadState.Loaded)
    assertEquals(
      ledgerRejects(w).map(_.generation).groupBy(identity).map((g, v) => g -> v.size),
      Map(3L -> 3, 5L -> 3)
    )
    assertEquals((entry.state, entry.version), (LoadState.Loaded, 6L))
  }

  // ------------------------------------------------------------------ CR-P7-11 m5, nits

  test("CR-P7-11 m5: an index declaring a filterable field non-filterable is refused") {
    val w = new World
    val wrong = new InMemoryVectorStore(
      VectorIndexes.all.map(i =>
        i -> IndexInfo(
          i.name,
          Dim,
          DistanceMetric.Cosine,
          MetadataField.nonFilterableFields.map(_.key) :+ "researcherIds"
        )
      ),
      OpeningFixture.openingLimits
    )
    val out = load(w, store = Some(wrong))
    assert(
      out.left.exists(
        _.message.contains("filterable fields declared non-filterable: researcherIds")
      ),
      out.toString
    )
    assertEquals(w.dataWrites + w.ledger.counts.calls, 0)
  }

  test("nit: jitter is clamped to the delay bound") {
    val p = RetryPolicy.of(4, 100L, 1600L).toOption.get
    val wild = new scholarchain.storage.port.Jitter { def uniform(u: Long) = u * 10 + 7 }
    val negative = new scholarchain.storage.port.Jitter { def uniform(u: Long) = -5L }
    assertEquals((1 to 3).map(p.delay(_, wild)), Vector(100L, 200L, 400L))
    assertEquals((1 to 3).map(p.delay(_, negative)), Vector(0L, 0L, 0L))
    val w = new World
    w.vectorStore.failNextWrites(StoreError.Transient("Throttling"))
    val t = w.targets().copy(jitter = wild)
    val r = LoadGate.proceed(production, V)(p0 =>
      VectorLoad.load(
        p0,
        RunSettingsFixture.parquetBounds,
        TestData.layout,
        OpeningFixture.vectorConfig(),
        t
      )
    )
    assert(r.exists(_.isRight), r.toString)
    assertEquals(w.sleeper.sleeps, Vector(100L))
  }

  test("nit: pre-write rejections keyed by the row fallback are counted in their index") {
    val w = new World
    // A key limit below every vector key: each row is rejected with its row:<path>#<i> key.
    val r = ok(load(w, OpeningFixture.vectorConfig(maxRejected = 300, maxKeyBytes = 10)))
    assert(r.rejected.forall(_.recordKey.startsWith("row:")), r.rejected.head.recordKey)
    assertEquals(
      r.indexes.map(i => (i.index, i.routed, i.rejected, i.written)),
      Vector(
        ("researcher-profile-v1", 176, 176, 0L),
        ("collaboration-community-v1", 51, 51, 0L),
        ("paper-abstract-v1", 8, 8, 0L),
        ("collaboration-path-v1", 9, 9, 0L),
        ("topic-institution-summary-v1", 5, 5, 0L)
      )
    )
  }

  // ------------------------------------------------------------------ CR-P7-11 M2

  private lazy val summaryInputs: Vector[scholarchain.core.SemanticSummaryInput] =
    LoadGate.proceed(production, V)(p =>
      Right(p.items.flatMap { it =>
        p.readItem(it.path)
          .flatMap(
            ParquetRecords
              .decodePart(it.path, it.recordSchemaVersion, _, RunSettingsFixture.parquetBounds)
          )
          .fold(e => fail(e.toString), _.records)
      })
    ) match {
      case Right(Right(rs)) => rs.collect { case x: scholarchain.core.SemanticSummaryInput => x }
      case other => fail(other.toString)
    }

  test("CR-P7-11 M2: every researcher-profile text carries its ER identity exactly as the input") {
    val (w, _) = reference
    val profiles =
      summaryInputs.filter(_.documentType == scholarchain.core.DocumentType.ResearcherProfile)
    assertEquals(profiles.size, 176)
    val tally = profiles.map { x =>
      val identity = x.identity.getOrElse(fail(s"${x.inputId}: no identity in the input"))
      val key = s"researcher-profile-v1/${SyntheticTestEmbedding.ModelVersion}/${x.inputId}"
      val v = w.vectorStore
        .getVectors("researcher-profile-v1", Vector(key))
        .fold(e => fail(e.toString), identity => identity)
        .headOption
        .getOrElse(fail(s"$key not stored"))
      val textKey = v.metadata.string(MetadataField.TextS3Key).get
      val text = w.objectStore
        .get(ObjectRef("test-data-bucket", textKey))
        .toOption
        .flatten
        .getOrElse(fail(textKey))
        ._2
        .utf8
      val lines = text.split("\n").toVector
      val idLines = lines.filter(_.startsWith(ContextText.IdentityPrefix))
      assertEquals(idLines.size, 1, key)
      assertEquals(
        upickle.default.read[scholarchain.core.IdentityConfidence](
          idLines.head.stripPrefix(ContextText.IdentityPrefix)
        ),
        identity,
        key
      )
      assert(lines.contains(s"identity.status: ${identity.status.tag}"), key)
      assert(lines.contains(s"identity.memberCount: ${identity.memberCount}"), key)
      assertEquals(
        v.metadata.string(MetadataField.IdentityStatusField),
        Some(identity.status.tag),
        key
      )
      assertEquals(
        v.metadata.string(MetadataField.AnchorCollision),
        Some(if (identity.collisionCodes.nonEmpty) "true" else "false"),
        key
      )
      (identity.status.tag, identity.collisionCodes.nonEmpty)
    }
    println(
      "CR-P7-11 M2 researcher-profile identities (status, collision) -> count: " +
        tally.groupBy(identity).map((k, v) => k -> v.size).toVector.sortBy(_._1.toString)
    )
    // Non-researcher documents carry no identity block.
    val others =
      summaryInputs.filter(_.documentType != scholarchain.core.DocumentType.ResearcherProfile)
    assert(others.forall(_.identity.isEmpty))
    // The identity fields are filterable on loaded data.
    val byStatus = w.vectorStore
      .query(
        "researcher-profile-v1",
        Vector.fill(Dim)(0.3f),
        30,
        Some(VectorFilter.Eq(MetadataField.IdentityStatusField, tally.head._1))
      )
      .fold(e => fail(e.toString), identity)
    assert(byStatus.nonEmpty)
    assert(
      byStatus.forall(_.metadata.string(MetadataField.IdentityStatusField).contains(tally.head._1))
    )
  }

  test("CR-P7-11 M2: a synthetic over-merged identity shows its collision codes, never dropped") {
    val base = summaryInputs
      .find(_.documentType == scholarchain.core.DocumentType.ResearcherProfile)
      .get
    val overMerged = scholarchain.core.IdentityConfidence(
      scholarchain.core.IdentityStatus.Resolved,
      "orcid",
      7,
      Vector("grouping.anchor-collision.dblp-pid", "grouping.anchor-collision.orcid"),
      scholarchain.core.DecisionConfidence.measured(0.61)
    )
    val item = LoadGate.proceed(production, V)(p => Right(p.items.head)).toOption.get.toOption.get
    val text = ContextText.render(
      RunId,
      item,
      base.documentType,
      base.subjectId,
      Some(base.window),
      base.inputId,
      Some(overMerged),
      base.facts.map(f => (f.name, f.value, f.evidence.map(e => (e.kind.tag, e.id))))
    )
    val lines = text.split("\n").toVector
    assert(
      lines.contains(
        "identity.anchorCollisionCodes: \"grouping.anchor-collision.dblp-pid\", " +
          "\"grouping.anchor-collision.orcid\""
      ),
      text
    )
    assert(lines.contains("identity.status: resolved"), text)
    assert(lines.contains("identity.memberCount: 7"), text)
    assert(lines.contains("identity.lowestDecisionConfidence: measured 0.61"), text)
    assertEquals(
      upickle.default.read[scholarchain.core.IdentityConfidence](
        lines
          .find(_.startsWith(ContextText.IdentityPrefix))
          .get
          .stripPrefix(ContextText.IdentityPrefix)
      ),
      overMerged
    )
    // The identity block precedes the facts and differs from the stored input's text.
    assert(lines.indexWhere(_.startsWith("identity")) < lines.indexOf("facts:"))
    assertEquals(
      VectorLoad.identityMetadata(overMerged),
      Vector(
        "identityStatus" -> MetaValue.Str("resolved"),
        "anchorCollision" -> MetaValue.Str("true")
      )
    )
    val singleton = overMerged.copy(
      status = scholarchain.core.IdentityStatus.Singleton,
      memberCount = 1,
      collisionCodes = Vector.empty,
      lowestDecisionConfidence = scholarchain.core.DecisionConfidence
        .unknown(scholarchain.core.ConfidenceUnknownReason.NoSupportingDecision)
    )
    assertEquals(
      VectorLoad.identityMetadata(singleton).map(_._2),
      Vector(MetaValue.Str("singleton"), MetaValue.Str("false"))
    )
    val single = ContextText.render(
      RunId,
      item,
      base.documentType,
      base.subjectId,
      Some(base.window),
      base.inputId,
      Some(singleton),
      Vector.empty
    )
    assert(single.contains("identity.anchorCollisionCodes: none\n"), single)
    assert(
      single.contains("identity.lowestDecisionConfidence: unknown (no-supporting-decision)\n"),
      single
    )
    // CR-P7-12 n2: a researcher-profile vector without the identity fields fails the index's
    // metadata rules (run on such metadata, not only a field-list check).
    val missing = VectorIndexes.ResearcherProfile.required.filter(f =>
      f == MetadataField.IdentityStatusField || f == MetadataField.AnchorCollision
    )
    assertEquals(missing.size, 2)
    val withoutIdentity = TestData.withoutMeta(
      TestData.withoutMeta(TestData.profileMetadata(), "identityStatus"),
      "anchorCollision"
    )
    assertEquals(
      VectorMetadataRules
        .problems(VectorIndexes.ResearcherProfile, TestData.profileMetadata(), TestData.limits),
      Vector.empty
    )
    assertEquals(
      VectorMetadataRules
        .problems(VectorIndexes.ResearcherProfile, withoutIdentity, TestData.limits)
        .filter(_.endsWith(": missing")),
      Vector("identityStatus: missing", "anchorCollision: missing")
    )
  }

  test("the synthetic embedder is deterministic, unit length and text-sensitive") {
    val e = new SyntheticTestEmbedding(16)
    val a = e.embed(Vector("a", "a", "b")).toOption.get
    assertEquals(a(0), a(1))
    assertNotEquals(a(0), a(2))
    assert(math.abs(a(0).map(x => x.toDouble * x).sum - 1.0) < 1e-5)
    assertEquals(e.modelVersion, "synthetic-test-only.v1")
  }
}
