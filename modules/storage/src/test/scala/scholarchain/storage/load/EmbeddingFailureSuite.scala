package scholarchain.storage.load

import scala.collection.immutable.SortedMap

import scholarchain.core.TargetStore
import scholarchain.storage.{Fixtures, OpeningFixture, TestData}
import scholarchain.storage.OpeningFixture.{CountingEmbedder, Dimension, World, config, sources}
import scholarchain.storage.fake.{
  InMemoryKeyValueStore,
  InMemoryObjectStore,
  InMemoryVectorStore,
  SyntheticTestEmbedding
}
import scholarchain.storage.model.*
import scholarchain.storage.port.{
  Embedder,
  KeyQuery,
  RuntimeStores,
  SortKeyCondition,
  VectorReader,
  VectorStore
}

/**
 * CR-P8-01 repairs (SE-P8-02): M1 (early exits record their rejections and the failure in the
 * RunLedger; a `Permanent` batch embedding error is split down to per-item rejections), M2 (reads
 * name the query's model version; no result mixes models) and m1 (a Phase 7 unscoped s3-vectors
 * ledger row is refused). Every vector is SYNTHETIC or a hand-made test record; no real model.
 */
class EmbeddingFailureSuite extends munit.FunSuite {

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

  private def query(w: World, prefix: String) =
    w.ledgerStore
      .query(
        TableRole.RunLedger,
        KeyQuery(None, "run#" + Fixtures.RunId, SortKeyCondition.BeginsWith(prefix), 1000)
      )
      .fold(e => fail(e.toString), identity)

  private def vectorRows(w: World): Map[Option[String], LoadEntry] =
    query(w, "load#")
      .map(i => ItemCodecs.LoadEntryCodec.decode(i).fold(e => fail(e.toString), identity))
      .filter(_.targetStore == V)
      .map(e => e.embeddingModel -> e)
      .toMap

  private def ledgerRejects(w: World): Vector[RejectedRecord] =
    query(w, "reject#").map(i =>
      ItemCodecs.RejectedRecordCodec.decode(i).fold(e => fail(e.toString), identity)
    )

  private def rejectionSets(w: World): Vector[RejectionSet] =
    query(w, "rejectset#").map(i =>
      ItemCodecs.RejectionSetCodec.decode(i).fold(e => fail(e.toString), identity)
    )

  /** The V1 work manifest of a fully loaded temporary world (its items in batch order). */
  private lazy val manifest: EmbeddingWorkManifest = {
    val tmp = new World
    assert(runWith(tmp).report.complete)
    val row = vectorRows(tmp)(Some(V1))
    val ref = TestData.layout
      .embeddingWorkManifest(row.loadId, V1, row.embedding.get.workManifestSha256)
      .fold(e => fail(e.toString), identity)
    val body = tmp.objectStore
      .get(ref)
      .fold(e => fail(e.toString), identity)
      .getOrElse(fail(s"no $ref"))
      ._2
    EmbeddingWorkManifest.parse(body).fold(e => fail(e.toString), identity)
  }

  /** A vector store that answers every put of `victim` with a per-record Transient failure. */
  private def throttling(w: World, victim: String): VectorStore = new VectorStore {
    def describe(i: String) = w.vectors.describe(i)
    def getVectors(i: String, k: Vector[String]) = w.vectors.getVectors(i, k)
    def query(i: String, v: Vector[Float], k: Int, f: Option[VectorFilter]) =
      w.vectors.query(i, v, k, f)
    def putVectors(i: String, rs: Vector[VectorRecord]) = {
      val good = rs.filterNot(_.key == victim)
      val done = if (good.isEmpty) Right(Vector.empty) else w.vectors.putVectors(i, good)
      done.map { ok =>
        val byKey = ok.map(r => r.key -> r).toMap
        rs.map(r =>
          byKey.getOrElse(r.key, VectorPutResult(r.key, Left(StoreError.Transient("SlowDown"))))
        )
      }
    }
  }

  // ------------------------------------------------------------------ M1, failure 1

  test("M1: an embedding failure after a rejection records that rejection and the failure") {
    val w = new World
    val victim = manifest.items.head.vectorKey // in the first batch (50 profiles)
    var calls = 0
    val firstOnly = new Embedder {
      private val real = new SyntheticTestEmbedding(Dimension)
      def modelVersion = V1
      def dimension = Dimension
      def embed(texts: Vector[String]) = {
        calls += 1
        if (calls == 1) real.embed(texts) else Left(StoreError.Transient("ThrottlingException"))
      }
    }
    val v = runWith(w, embedder = firstOnly, vectorStore = throttling(w, victim)).report.store(V)
    assertEquals(v.status, StoreLoadStatus.Failed)
    assert(v.error.exists(_.message.startsWith("embedder: a batch of")), v.error.toString)
    assertEquals(v.rejectedRecords, 1L)
    // The batch-1 rejection is in the RunLedger: its row, an INCOMPLETE set (SE-P8-03: the set of
    // a failed call, never current), and the failed row (whose `rejected` count it does not set).
    val rows = ledgerRejects(w)
    assertEquals(
      rows.map(r => (r.recordKey, r.reason, r.attempts)),
      Vector((victim, RejectReason.RetriesExhausted, 4))
    )
    val row = vectorRows(w)(Some(V1))
    assertEquals((row.state, row.rejected), (LoadState.Failed, 0L))
    val sets = rejectionSets(w)
    assertEquals(
      sets.map(s => (s.generation, s.count, s.complete)),
      Vector((rows.head.generation, 1L, false))
    )
    assertEquals(RejectionSet.current(sets), None)
    val f = row.failure.getOrElse(fail("no failure recorded"))
    assertEquals((f.errorClass, f.generation), ("transient", rows.head.generation))
    assert(
      f.detail.startsWith("embedder: a batch of 50 stayed throttled after 4 attempts"),
      f.detail
    )
    assert(
      w.objectStore
        .get(ObjectRef("test-data-bucket", sets.head.rejectsObjectKey))
        .exists(_.isDefined)
    )
    // A rerun with working stores loads everything and clears the failure.
    val again = runWith(w).report.store(V)
    assertEquals(again.status, StoreLoadStatus.Loaded)
    val done = vectorRows(w)(Some(V1))
    assertEquals((done.state, done.failure, done.rejected), (LoadState.Loaded, None, 0L))
    assertEquals(w.vectorCount, 249)
  }

  // ------------------------------------------------------------------ M1, failure 2

  /** Refuses (Permanent) every batch that holds the text of `inputId`. */
  private final class Refusing(inputId: String) extends Embedder {
    private val real = new SyntheticTestEmbedding(Dimension)
    var calls = 0
    def modelVersion = V1
    def dimension = Dimension
    def embed(texts: Vector[String]) = {
      calls += 1
      if (texts.exists(_.contains(s"\ninputId: $inputId\n")))
        Left(StoreError.Permanent("ValidationException: input refused"))
      else real.embed(texts)
    }
  }

  test("M1: a text the provider always refuses becomes one per-item rejection; the load goes on") {
    val bad = manifest.items(3)
    val w = new World
    val e = new Refusing(bad.inputId)
    val v = runWith(w, embedder = e).report.store(V)
    assertEquals((v.status, v.written, v.rejectedRecords), (StoreLoadStatus.Loaded, 248L, 1L))
    // The 50-text batch is split 50 > 25 > 12 > 6 > 3 > 1: 11 calls for it, 8 for the others.
    assertEquals(e.calls, 19)
    assertEquals(count(v, "embed-retries"), 50L + 25L + 12L + 6L + 3L)
    assertEquals(count(v, "embedded"), 249L)
    val r = ledgerRejects(w).headOption.getOrElse(fail("no rejection"))
    assertEquals(
      (r.recordKey, r.reason, r.attempts),
      (bad.vectorKey, RejectReason.EmbeddingFailure, 6)
    )
    assert(r.detail.contains("ValidationException: input refused"), r.detail)
    assertEquals(vectorRows(w)(Some(V1)).rejected, 1L)
    assertEquals(
      w.vectorStore.getVectors(bad.index, Vector(bad.vectorKey)).map(_.size),
      Right(0),
      "the refused item has no vector"
    )
  }

  test("M1: per-item embedding rejections count against maxRejected (0): failed, recorded") {
    val bad = manifest.items(3)
    val w = new World
    val v = runWith(
      w,
      embedder = new Refusing(bad.inputId),
      vectorConfig = OpeningFixture.vectorConfig(maxRejected = 0)
    ).report.store(V)
    assertEquals(v.status, StoreLoadStatus.Failed)
    assert(v.error.exists(_.message.contains("exceed maxRejected 0")), v.error.toString)
    assertEquals(ledgerRejects(w).map(_.recordKey), Vector(bad.vectorKey))
    val row = vectorRows(w)(Some(V1))
    // SE-P8-03: the bound stops the call, so its set is incomplete and not the ledger count.
    assertEquals((row.state, row.rejected), (LoadState.Failed, 0L))
    assertEquals(rejectionSets(w).map(s => (s.count, s.complete)), Vector((1L, false)))
    assertEquals(row.failure.map(_.errorClass), Some("refused"))
  }

  // ------------------------------------------------------------------ M2

  private val Dim = Dimension
  private def record(key: String, model: String, x: Float): VectorRecord = {
    val data = Vector.fill(Dim)(x)
    VectorRecord(
      key,
      data,
      VectorMetadata(
        SortedMap(
          "documentType" -> MetaValue.Str("researcher-profile"),
          "modelVersion" -> MetaValue.Str(model),
          "dataSha256" -> MetaValue.Str(VectorDigest.of(data).toOption.get)
        )
      )
    )
  }

  private def runtime(s: VectorStore, policy: EmbeddingPolicy): RuntimeStores = {
    val kv = new InMemoryKeyValueStore(10)
    RuntimeStores(
      new InMemoryObjectStore(10),
      kv,
      kv,
      (_: BoundQuery) => Right(Vector.empty),
      s,
      s,
      policy
    )
  }

  test("M2: two models of one dimension in one index: a read names its model and gets only it") {
    val (m3, m4) = ("real-model.v3", "real-model.v4")
    val s = new InMemoryVectorStore(OpeningFixture.indexInfos(), OpeningFixture.openingLimits)
    val recs = Vector(
      record(s"$Profile/$m3/a", m3, 0.25f),
      record(s"$Profile/$m4/a", m4, 0.26f),
      record(s"$Profile/$m3/b", m3, 0.27f),
      record(s"$Profile/$m4/b", m4, 0.28f)
    )
    assert(s.putVectors(Profile, recs).isRight)
    val rt = runtime(s, EmbeddingPolicy.RealModelsOnly)
    val q = Vector.fill(Dim)(0.5f)
    Vector(m3, m4).foreach { m =>
      val out = rt.vectorsFor(m).query(Profile, q, 10, None).fold(e => fail(e.toString), identity)
      assertEquals(out.map(_.key).sorted, Vector(s"$Profile/$m/a", s"$Profile/$m/b"), m)
      assert(out.forall(_.metadata.string(MetadataField.ModelVersion).contains(m)), m)
    }
    // SE-P8-03 (CR-P8-02 m-B): the unnamed reader refuses every query (here: two models).
    val mixed = rt.vectors.query(Profile, q, 10, None)
    assert(
      mixed.left.exists(e =>
        e.isInstanceOf[StoreError.Refused] && e.message.contains("names no query model")
      ),
      mixed.toString
    )
    // A get of another model's key is refused; a non-canonical or policy-refused model refuses.
    assert(rt.vectorsFor(m3).getVectors(Profile, Vector(s"$Profile/$m4/a")).isLeft)
    assertEquals(
      rt.vectorsFor(m3).getVectors(Profile, Vector(s"$Profile/$m3/a")).map(_.map(_.key)),
      Right(Vector(s"$Profile/$m3/a"))
    )
    assert(rt.vectorsFor("Real:Model").query(Profile, q, 10, None).isLeft)
    assert(rt.vectorsFor(V1).query(Profile, q, 10, None).isLeft, "synthetic under RealModelsOnly")
    intercept[IllegalArgumentException](
      VectorReader.verified(s, EmbeddingPolicy.RealModelsOnly, null)
    )
  }

  test("M2: after a model change the loaded index holds both; a v1 read returns only v1 vectors") {
    val w = new World
    assert(runWith(w).report.complete)
    assert(
      runWith(
        w,
        embedder = new CountingEmbedder(new SyntheticTestEmbedding(Dimension, V2))
      ).report.complete
    )
    val rt = runtime(w.vectors, EmbeddingPolicy.SyntheticTestOnly)
    val q = Vector.fill(Dimension)(0.5f)
    Vector(V1, V2).foreach { m =>
      val out = rt.vectorsFor(m).query(Profile, q, 30, None).fold(e => fail(e.toString), identity)
      assert(out.nonEmpty, m)
      assert(out.forall(_.metadata.string(MetadataField.ModelVersion).contains(m)), m)
      val inputs = out.map(_.metadata.string(MetadataField.InputId))
      assertEquals(inputs.distinct.size, inputs.size, s"$m: no input twice")
    }
  }

  test(
    "SE-P8-03 m-B: an all-v1 index: the unnamed query is refused, a v2 query gets no v1 vector"
  ) {
    val w = new World
    assert(runWith(w).report.complete)
    var storeQueries = 0
    val counted = new VectorStore {
      def describe(i: String) = w.vectors.describe(i)
      def getVectors(i: String, k: Vector[String]) = w.vectors.getVectors(i, k)
      def putVectors(i: String, rs: Vector[VectorRecord]) = w.vectors.putVectors(i, rs)
      def query(i: String, v: Vector[Float], k: Int, f: Option[VectorFilter]) = {
        storeQueries += 1
        w.vectors.query(i, v, k, f)
      }
    }
    val rt = runtime(counted, EmbeddingPolicy.SyntheticTestOnly)
    // A v2-embedded query vector (another embedding space than every stored vector).
    val q2 = SyntheticTestEmbedding.vectorOf("a v2 query", Dimension, V2)
    // Failure input of m-B: before SE-P8-03 this returned every v1 match with no error.
    val unnamed = rt.vectors.query(Profile, q2, 10, None)
    assert(
      unnamed.left.exists(e =>
        e.isInstanceOf[StoreError.Refused] && e.message.contains("names no query model")
      ),
      unnamed.toString
    )
    // The store's matches were intact and allowed (only checked, never returned): still refused.
    assertEquals(storeQueries, 1)
    val empty = runtime(
      new InMemoryVectorStore(OpeningFixture.indexInfos(), OpeningFixture.openingLimits),
      EmbeddingPolicy.SyntheticTestOnly
    )
    assert(empty.vectors.query(Profile, q2, 10, None).isLeft, "refused on an empty result too")
    // The named reader for v2 returns nothing of v1; for v1 it returns v1 vectors only.
    assertEquals(rt.vectorsFor(V2).query(Profile, q2, 10, None), Right(Vector()))
    val v1 = rt.vectorsFor(V1).query(Profile, q2, 10, None).fold(e => fail(e.toString), identity)
    assertEquals(v1.size, 10)
    assert(v1.forall(_.metadata.string(MetadataField.ModelVersion).contains(V1)))
    // Get by key stays available (keys name their model; integrity and policy checked).
    val k = v1.head.key
    assertEquals(rt.vectors.getVectors(Profile, Vector(k)).map(_.map(_.key)), Right(Vector(k)))
  }

  // ------------------------------------------------------------------ SE-P8-03 m-A

  test(
    "SE-P8-03 m-A: a failed call's partial set is incomplete; the complete set stays current"
  ) {
    val profiles = manifest.items.filter(_.index == Profile)
    val (p, q) = (profiles(0).vectorKey, profiles(1).vectorKey)
    val firstOther = manifest.items.indexWhere(_.index != Profile)
    val a = manifest.items(firstOther).vectorKey // in a later index: a later batch than P and Q
    var generation = 1
    val w = new World
    // Generation 1: P, Q and A stay throttled. Generation 2: P stays throttled, Q is a Conflict.
    val store = new VectorStore {
      def describe(i: String) = w.vectors.describe(i)
      def getVectors(i: String, k: Vector[String]) = w.vectors.getVectors(i, k)
      def query(i: String, v: Vector[Float], k: Int, f: Option[VectorFilter]) =
        w.vectors.query(i, v, k, f)
      def putVectors(i: String, rs: Vector[VectorRecord]) = {
        def special(k: String): Option[StoreError] =
          if (k == p || k == a || (k == q && generation == 1))
            Some(StoreError.Transient("SlowDown"))
          else if (k == q) Some(StoreError.Conflict(s"$i $k: injected conflict"))
          else None
        val good = rs.filter(r => special(r.key).isEmpty)
        val done = if (good.isEmpty) Right(Vector.empty) else w.vectors.putVectors(i, good)
        done.map { ok =>
          val byKey = ok.map(r => r.key -> r).toMap
          rs.map(r => byKey.getOrElse(r.key, VectorPutResult(r.key, Left(special(r.key).get))))
        }
      }
    }
    // Bounded call: reaches A (first item of the second index) and leaves work: documents-staged.
    val cfg = OpeningFixture.vectorConfig(maxItemsPerCall = firstOther + 10)
    val g1 = runWith(w, vectorConfig = cfg, vectorStore = store).report.store(V)
    assertEquals(g1.status, StoreLoadStatus.Staged, g1.toString)
    val row1 = vectorRows(w)(Some(V1))
    assertEquals((row1.state, row1.rejected), (LoadState.DocumentsStaged, 3L))
    val set1 = RejectionSet.current(rejectionSets(w)).getOrElse(fail("no current set"))
    assertEquals((set1.count, set1.complete), (3L, true))
    // Generation 2: batch 1 is [P, Q]: P is rejected again, Q's Conflict stops the call before A.
    generation = 2
    val g2 = runWith(w, vectorConfig = cfg, vectorStore = store).report.store(V)
    assertEquals(g2.status, StoreLoadStatus.Failed)
    assert(g2.error.exists(_.isInstanceOf[StoreError.Conflict]), g2.error.toString)
    val sets = rejectionSets(w).sortBy(_.generation)
    assertEquals(sets.size, 2)
    val partial = sets.last
    assert(partial.generation > set1.generation)
    assertEquals((partial.count, partial.complete), (1L, false), "the partial set is marked")
    // The current set is still generation 1's complete set; the ledger count is not replaced.
    assertEquals(RejectionSet.current(sets), Some(set1))
    val row2 = vectorRows(w)(Some(V1))
    assertEquals((row2.state, row2.rejected), (LoadState.Failed, 3L))
    assertEquals(row2.failure.map(_.generation), Some(partial.generation))
    // The partial rows are stored as evidence of the failed call: exactly P.
    assertEquals(
      ledgerRejects(w).filter(_.generation == partial.generation).map(_.recordKey),
      Vector(p)
    )
    // The complete set's summary JSON is unchanged by the new field (omitted when true).
    val body = query(w, "rejectset#")
      .map(i => AttrValue.string(i, Attrs.Body).fold(e => fail(e.toString), identity))
    assertEquals(body.count(_.contains("\"complete\":false")), 1)
    assertEquals(body.count(_.contains("complete")), 1, body.mkString("\n"))
  }

  // ------------------------------------------------------------------ SE-P8-03 n-A

  test("SE-P8-03 n-A: a text refused alone keeps its rejection when its sibling half fails") {
    val bad = manifest.items(3)
    val w = new World
    var refusedAlone = false
    val e = new Embedder {
      private val real = new SyntheticTestEmbedding(Dimension)
      def modelVersion = V1
      def dimension = Dimension
      def embed(texts: Vector[String]) =
        if (refusedAlone) Left(StoreError.Corrupt("embedder: broken response"))
        else if (texts.exists(_.contains(s"\ninputId: ${bad.inputId}\n"))) {
          if (texts.size == 1) refusedAlone = true
          Left(StoreError.Permanent("ValidationException: input refused"))
        } else real.embed(texts)
    }
    val v = runWith(w, embedder = e).report.store(V)
    assertEquals(v.status, StoreLoadStatus.Failed)
    assert(v.error.exists(_.message.contains("broken response")), v.error.toString)
    // Failure input of n-A: before SE-P8-03 the refusal of `bad` was discarded here.
    val rows = ledgerRejects(w)
    assertEquals(
      rows.map(r => (r.recordKey, r.reason)),
      Vector((bad.vectorKey, RejectReason.EmbeddingFailure))
    )
    val sets = rejectionSets(w)
    assertEquals(sets.map(s => (s.count, s.complete)), Vector((1L, false)))
    assertEquals(w.vectorCount, 0)
  }

  // ------------------------------------------------------------------ m1

  test(
    "m1: a Phase 7 unscoped s3-vectors row of the load is refused: zero writes, zero embedding"
  ) {
    val planted = {
      val tmp = new World
      assert(runWith(tmp).report.complete)
      vectorRows(tmp)(Some(V1))
    }
    val legacy = RunLedgerStates
      .start(planted.runId, planted.loadId, V, planted.partManifestSha256, planted.itemCount, 1L)
      .flatMap(ItemCodecs.LoadEntryCodec.encode)
      .fold(e => fail(e.toString), identity)
    val w = new World
    assertEquals(w.ledgerStore.putIfAbsent(TableRole.RunLedger, legacy), Right(PutOutcome.Written))
    val e = new CountingEmbedder(new SyntheticTestEmbedding(Dimension))
    val r = runWith(w, embedder = e).report
    val v = r.store(V)
    assertEquals(v.status, StoreLoadStatus.Failed, show(r))
    assert(
      v.error.exists(x =>
        x.isInstanceOf[StoreError.Refused] && x.message.contains("unscoped (Phase 7)")
      ),
      v.error.toString
    )
    assertEquals((w.vectors.batches, w.vectorCount, e.calls), (0, 0, 0))
    assertEquals(vectorRows(w).keySet, Set(None), "no model-scoped row was created")
  }
}
