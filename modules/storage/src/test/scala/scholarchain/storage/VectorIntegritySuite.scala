package scholarchain.storage

import java.nio.charset.StandardCharsets

import scholarchain.core.{Canonical, TargetStore}
import scholarchain.storage.fake.SyntheticTestEmbedding
import scholarchain.storage.load.*
import scholarchain.storage.model.*
import scholarchain.storage.port.{Embedder, RuntimeStores, VectorStore}
import OpeningFixture.*

/**
 * SE-P7-11 item 1 (human requirement 2026-11-20; CR-P7-12 F1, tests T1-T6): stored vector values
 * are verified against their own `dataSha256` on the pre-write probe and on every runtime read, so
 * changed floats are never accepted even with intact metadata, while a NONDETERMINISTIC embedder
 * still resumes (stored vectors are never compared with a new embedding). Vectors are synthetic.
 */
class VectorIntegritySuite extends munit.FunSuite {

  private val V = TargetStore.S3Vectors
  private val Ix = "researcher-profile-v1"

  private def count(s: StoreLoadOutcome, name: String): Option[Long] =
    s.counts.find(_._1 == name).map(_._2)

  /** One fully loaded world (the reference). */
  private lazy val loaded: World = {
    val w = new World
    assert(run(w).report.complete)
    w
  }

  private def get(w: World, key: String): VectorRecord =
    w.vectorStore.getVectors(Ix, Vector(key)).fold(e => fail(e.toString), identity).head

  /** Two researcher-profile keys of the loaded world (closest to a fixed query, deterministic). */
  private lazy val twoKeys: (String, String) = {
    val ms = loaded.vectorStore
      .query(Ix, Vector.fill(Dimension)(0.5f), 2, None)
      .fold(e => fail(e.toString), identity)
    (ms(0).key, ms(1).key)
  }

  /**
   * Plants `planted` (derived from a loaded record) in fresh stores and runs a fresh-ledger
   * OpeningLoad: the s3-vectors load must fail at the probe with zero vector-loader writes, zero
   * embedder calls, the planted record unchanged and not counted as stored.
   */
  private def refusedByProbe(what: String, planted: VectorRecord, fragment: String): Unit = {
    val w = new World
    assertEquals(
      w.vectorStore.putVectors(Ix, Vector(planted)).map(_.map(_.outcome)),
      Right(Vector(Right(PutOutcome.Written))),
      what
    )
    val r = run(w).report
    val v = r.store(V)
    assertEquals(v.status, StoreLoadStatus.Failed, what)
    assert(
      v.error.exists(e => e.isInstanceOf[StoreError.Corrupt] && e.message.contains(fragment)),
      s"$what: ${v.error}"
    )
    assert(v.error.exists(_.message.contains(planted.key)), s"$what names the key: ${v.error}")
    assertEquals((v.written, w.vectors.batches, w.vectorCount), (0L, 0, 1), s"$what: no vector put")
    assertEquals(w.embedder.calls, 0, s"$what: zero embedder calls")
    // No text object or registry row of the vector loader (only the s3 loader's 29 parts and,
    // SE-P7-13 item 2, its 2 run files).
    assertEquals(w.objectStore.size, 31, what)
    assertEquals(w.kvStore.itemCount(TableRole.ArtifactRegistry), 31, what)
    assertEquals(get(w, planted.key), planted, s"$what: never overwritten")
    assert(count(v, s"index:$Ix:stored").forall(_ == 0L), s"$what: not counted as stored")
    assert(!r.complete, what)
  }

  test("T1: one changed float with intact metadata and digest is refused by the probe") {
    val stored = get(loaded, twoKeys._1)
    assertEquals(VectorDigest.verify(Ix, stored), Right(()), "the loaded record is intact")
    val bad = stored.copy(data = stored.data.updated(3, stored.data(3) + 0.25f))
    assertEquals(bad.metadata, stored.metadata)
    refusedByProbe("T1", bad, "do not match its dataSha256")
  }

  test("T2: an all-zero vector and another key's vector under this key are refused") {
    val stored = get(loaded, twoKeys._1)
    val other = get(loaded, twoKeys._2)
    assertNotEquals(other.data, stored.data)
    refusedByProbe("T2 zero", stored.copy(data = Vector.fill(Dimension)(0f)), "dataSha256")
    refusedByProbe("T2 swapped", stored.copy(data = other.data), "dataSha256")
  }

  test("T3: a tampered digest, a malformed digest and a missing digest are refused") {
    val stored = get(loaded, twoKeys._1)
    def md(v: Option[String]) = v match {
      case Some(d) => TestData.withMeta(stored.metadata, "dataSha256", MetaValue.Str(d))
      case None => TestData.withoutMeta(stored.metadata, "dataSha256")
    }
    refusedByProbe(
      "T3 tampered",
      stored.copy(metadata = md(Some(TestData.hex("x")))),
      "do not match"
    )
    refusedByProbe("T3 malformed", stored.copy(metadata = md(Some("ABC"))), "not 64 lowercase")
    refusedByProbe("T3 missing", stored.copy(metadata = md(None)), "no dataSha256")
    // Also refused by the metadata rules (required field, hex64).
    val rules =
      VectorMetadataRules.problems(VectorIndexes.ResearcherProfile, md(None), openingLimits)
    assert(rules.contains("dataSha256: missing"), rules.toString)
    assert(
      VectorMetadataRules
        .problems(VectorIndexes.ResearcherProfile, md(Some("ABC")), openingLimits)
        .exists(_.startsWith("dataSha256:"))
    )
  }

  /** Synthetic-labelled embedder whose floats differ on every call (CR-P7-11 R-a). */
  private final class Nondeterministic extends Embedder {
    private val base = new SyntheticTestEmbedding(Dimension)
    var calls = 0
    var texts = 0L
    val modelVersion = "synthetic-nondeterministic-test.v1"
    val dimension = Dimension
    def embed(xs: Vector[String]) = {
      calls += 1
      texts += xs.size
      val jitter = calls * 1e-3f
      base.embed(xs).map(_.map(v => v.updated(0, v(0) + jitter)))
    }
  }

  test("T4: a nondeterministic embedder resumes an interrupted load without a Conflict") {
    val e = new Nondeterministic
    assert(EmbeddingPolicy.isSynthetic(e.modelVersion))
    val probe = new Nondeterministic
    assertNotEquals(
      probe.embed(Vector("same text")).toOption.get,
      probe.embed(Vector("same text")).toOption.get,
      "different floats on every call"
    )
    val w = new World
    val putKeys = scala.collection.mutable.LinkedHashMap.empty[String, String]
    var batch = 0
    def store(throttle: Boolean): VectorStore = new VectorStore {
      def describe(i: String) = w.vectors.describe(i)
      def getVectors(i: String, k: Vector[String]) = w.vectors.getVectors(i, k)
      def query(i: String, v: Vector[Float], k: Int, f: Option[VectorFilter]) =
        w.vectors.query(i, v, k, f)
      def putVectors(i: String, rs: Vector[VectorRecord]) = {
        batch += 1
        if (throttle && batch >= 3) Left(StoreError.Transient("ThrottlingException"))
        else {
          rs.foreach(r => putKeys.update(r.key, i))
          w.vectors.putVectors(i, rs)
        }
      }
    }
    val t1 = w.targets(vectorStore = store(throttle = true)).copy(embedder = e)
    val v1 = OpeningLoad.run(sources(), config, t1).report.store(V)
    assertEquals((v1.status, v1.written, w.vectorCount), (StoreLoadStatus.Failed, 100L, 100))
    val n = 100L
    val before = putKeys.toVector.map((k, i) => k -> w.vectorStore.getVectors(i, Vector(k)))
    val callsBefore = e.texts
    val t2 = w.targets(vectorStore = store(throttle = false)).copy(embedder = e)
    val v2 = OpeningLoad.run(sources(), config, t2).report.store(V)
    assertEquals(
      (v2.status, v2.alreadyPresent, v2.written, v2.conflicts, count(v2, "embedded")),
      (StoreLoadStatus.Loaded, n, 249L - n, 0L, Some(249L - n))
    )
    assertEquals(e.texts - callsBefore, 249L - n, "only the missing vectors were embedded")
    assertEquals(w.vectorCount, 249)
    // The first call's vectors are unchanged; every stored record is intact.
    before.foreach((k, r) => assertEquals(w.vectorStore.getVectors(putKeys(k), Vector(k)), r, k))
    assertEquals(putKeys.size, 249)
    putKeys.toVector.groupBy(_._2).foreach { (ix, ks) =>
      ks.map(_._1).grouped(100).foreach { g =>
        val got = w.vectorStore.getVectors(ix, g).fold(x => fail(x.toString), identity)
        assertEquals(got.size, g.size)
        got.foreach(r => assertEquals(VectorDigest.verify(ix, r), Right(()), r.key))
        got.foreach(r =>
          assertEquals(r.metadata.string(MetadataField.ModelVersion), Some(e.modelVersion))
        )
      }
    }
    // A further rerun is skipped (same ledger), with zero embedding.
    val t3 = w.targets().copy(embedder = e)
    val textsNow = e.texts
    assertEquals(
      OpeningLoad.run(sources(), config, t3).report.store(V).status,
      StoreLoadStatus.Skipped
    )
    assertEquals(e.texts, textsNow)
  }

  test("T5: the runtime reader refuses corrupted stored floats on get and query, serves intact") {
    val (bad, good) = twoKeys
    val corrupting: VectorStore = new VectorStore {
      private val u = loaded.vectors
      def describe(i: String) = u.describe(i)
      def query(i: String, v: Vector[Float], k: Int, f: Option[VectorFilter]) = u.query(i, v, k, f)
      def putVectors(i: String, rs: Vector[VectorRecord]) = u.putVectors(i, rs)
      def getVectors(i: String, k: Vector[String]) =
        u.getVectors(i, k)
          .map(_.map(r => if (r.key == bad) r.copy(data = r.data.updated(0, -r.data(0))) else r))
    }
    val rt = RuntimeStores(
      loaded.objects,
      loaded.kv,
      loaded.kv,
      loaded.graph,
      corrupting,
      corrupting,
      EmbeddingPolicy.SyntheticTestOnly
    )
    def corrupt(out: Either[StoreError, ?], what: String) =
      assert(
        out.left.exists(e => e.isInstanceOf[StoreError.Corrupt] && e.message.contains(bad)),
        s"$what: $out"
      )
    corrupt(rt.vectors.getVectors(Ix, Vector(bad)), "get bad")
    corrupt(rt.vectors.getVectors(Ix, Vector(good, bad)), "get good and bad: nothing served")
    assertEquals(rt.vectors.getVectors(Ix, Vector(good)).map(_.map(_.key)), Right(Vector(good)))
    def subject(k: String) = get(loaded, k).metadata.string(MetadataField.SubjectId).get
    val q = Vector.fill(Dimension)(0.5f)
    // SE-P8-03: queries name their model (the unnamed reader refuses every query).
    val mv = SyntheticTestEmbedding.ModelVersion
    def bySubject(k: String) =
      rt.vectorsFor(mv)
        .query(Ix, q, 5, Some(VectorFilter.Eq(MetadataField.SubjectId, subject(k))))
    corrupt(bySubject(bad), "query matching the bad key")
    corrupt(rt.vectorsFor(mv).query(Ix, q, 2, None), "query whose top 2 include the bad key")
    assert(bySubject(good).exists(_.map(_.key).contains(good)), bySubject(good).toString)
    // Intact data through the plain runtime view of the loaded stores.
    val plain = RuntimeStores(
      loaded.objects,
      loaded.kv,
      loaded.kv,
      loaded.graph,
      loaded.vectors,
      loaded.vectors,
      EmbeddingPolicy.SyntheticTestOnly
    )
    assertEquals(
      plain.vectorsFor(mv).query(Ix, q, 2, None).map(_.map(_.key)),
      Right(Vector(bad, good))
    )
    assertEquals(plain.vectors.getVectors(Ix, Vector(bad)).map(_.size), Right(1))
  }

  // ------------------------------------------------------------------ T6 and (d): the digest

  /**
   * SE-P7-12 (CR-P7-13 m1): the exact 46-byte scheme-v1 preimage of Vector(1f, -2.5f, 0.125f, 0f),
   * written by hand, not derived from the implementation: the UTF-8 bytes of
   * `scholarchain.vector-data.v1\n4\n` (30 bytes), then the big-endian IEEE-754 binary32 bits
   * 3f800000 (1.0), c0200000 (-2.5), 3e000000 (0.125), 00000000 (0.0).
   */
  private val FixedPreimageHex =
    "7363686f6c6172636861696e2e766563746f722d646174612e76310a340a" + // header
      "3f800000" + "c0200000" + "3e000000" + "00000000"

  /**
   * SHA-256 of [[FixedPreimageHex]]. The human can verify it outside the build with:
   * {{{
   * printf 'scholarchain.vector-data.v1\n4\n\x3f\x80\x00\x00\xc0\x20\x00\x00\x3e\x00\x00\x00\x00\x00\x00\x00' | shasum -a 256
   * }}}
   * expected `1b0a02150f1892c23d47cb3487b41d6f405340a101c88136e541d87865ae8137`.
   */
  private val PinnedFixed = "1b0a02150f1892c23d47cb3487b41d6f405340a101c88136e541d87865ae8137"

  private def hexOf(b: Array[Byte]): String = b.map(x => f"${x & 0xff}%02x").mkString

  private def bytesOf(hex: String): Array[Byte] =
    hex.grouped(2).map(h => Integer.parseInt(h, 16).toByte).toArray

  test("T6: a fixed vector hashes exactly a hand-written 46-byte preimage; -0.0 equals +0.0") {
    val fixed = Vector(1f, -2.5f, 0.125f, 0f)
    val expected = bytesOf(FixedPreimageHex)
    assertEquals(expected.length, 46)
    // The header half of the literal is the documented UTF-8 header (a check of the literal).
    assertEquals(
      hexOf(expected.take(30)),
      hexOf("scholarchain.vector-data.v1\n4\n".getBytes(StandardCharsets.UTF_8))
    )
    val pre = VectorDigest.preimage(fixed).fold(e => fail(e.toString), identity)
    assertEquals(hexOf(pre), FixedPreimageHex, "VectorDigest hashes exactly these bytes")
    // The preimage of -0.0 is the preimage of +0.0 (canonicalised before hashing).
    assertEquals(
      VectorDigest.preimage(Vector(1f, -2.5f, 0.125f, -0f)).map(hexOf),
      Right(FixedPreimageHex)
    )
    val d = VectorDigest.of(fixed).toOption.get
    assertEquals(Canonical.sha256Hex(expected), PinnedFixed, "SHA-256 of the literal")
    assertEquals(d, PinnedFixed)
    assertEquals(VectorDigest.of(Vector(-0f, 1f)), VectorDigest.of(Vector(0f, 1f)))
    assertNotEquals(java.lang.Float.floatToRawIntBits(-0f), java.lang.Float.floatToRawIntBits(0f))
  }

  test("(d): the digest binds the dimension and the order; NaN and infinity are refused") {
    val v = Vector(0.1f, 0.2f, 0.3f)
    val d = VectorDigest.of(v).toOption.get
    assertNotEquals(VectorDigest.of(v.reverse).toOption.get, d, "permuted")
    assertNotEquals(VectorDigest.of(Vector(0.2f, 0.1f, 0.3f)).toOption.get, d, "two swapped")
    assertNotEquals(VectorDigest.of(v :+ 0f).toOption.get, d, "extended by a zero")
    assertNotEquals(VectorDigest.of(v.init).toOption.get, d, "truncated")
    Vector(Float.NaN, Float.PositiveInfinity, Float.NegativeInfinity).foreach(x =>
      assert(VectorDigest.of(Vector(1f, x)).isLeft, x.toString)
    )
    assert(VectorDigest.of(Vector.empty).isLeft)
    assert(VectorDigest.of(null).isLeft)
    val rec = VectorRecord("k", v, VectorDigest.withDigest(TestData.profileMetadata(), d))
    assertEquals(VectorDigest.verify(Ix, rec), Right(()))
    assert(
      VectorDigest
        .verify(Ix, rec.copy(data = v.reverse))
        .left
        .exists(_.isInstanceOf[StoreError.Corrupt])
    )
    assert(VectorDigest.verify(Ix, rec.copy(data = v :+ 0f)).isLeft)
    assert(VectorDigest.verify(Ix, rec.copy(metadata = null)).isLeft, "null metadata: no NPE")
    assert(VectorDigest.verify(Ix, null).isLeft)
  }

  test("the digest is non-filterable, required in every index, within the metadata budget") {
    assert(!MetadataField.DataSha256.filterable)
    assert(MetadataField.nonFilterableFields.contains(MetadataField.DataSha256))
    VectorIndexes.all.foreach(i => assert(i.required.contains(MetadataField.DataSha256), i.name))
    assertEquals(VectorMetadata.SchemaVersion, "scholarchain.storage.vector-metadata.v2")
    val stored = get(loaded, twoKeys._1)
    assertEquals(
      VectorMetadataRules.problems(VectorIndexes.ResearcherProfile, stored.metadata, openingLimits),
      Vector.empty
    )
    assert(
      MetadataField.nonFilterableFields.size <= openingLimits.maxNonFilterableKeys,
      MetadataField.nonFilterableFields.size.toString
    )
    // An index that does not declare dataSha256 non-filterable is refused before any write.
    val w = new World
    val undeclared = new fake.InMemoryVectorStore(
      VectorIndexes.all.map(i =>
        i -> IndexInfo(
          i.name,
          Dimension,
          DistanceMetric.Cosine,
          MetadataField.nonFilterableFields.map(_.key).filterNot(_ == "dataSha256")
        )
      ),
      openingLimits
    )
    val v = OpeningLoad.run(sources(), config, w.targets(vectorStore = undeclared)).report.store(V)
    assert(
      v.error.exists(_.message.contains("not declared non-filterable: dataSha256")),
      v.toString
    )
  }
}
