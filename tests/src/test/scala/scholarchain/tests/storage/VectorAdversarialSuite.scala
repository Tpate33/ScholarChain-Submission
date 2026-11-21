package scholarchain.tests.storage

import scholarchain.core.TargetStore
import scholarchain.storage.{Fixtures, OpeningFixture, TestData}
import scholarchain.storage.OpeningFixture.{Dimension, World, config, sources, vectorConfig}
import scholarchain.storage.fake.{InMemoryKeyValueStore, RecordingKeyValueStore}
import scholarchain.storage.load.*
import scholarchain.storage.model.*
import scholarchain.storage.port.{RuntimeStores, VectorStore}

import StorageAdversarial.*

/**
 * TE-P7-01 case 5 (checklist V2, V5, V6; SE-P7-11 integrity; CR-P7-11 m2 policy): duplicate vector
 * keys, corrupted stored floats and synthetic vectors under the production policy, through the
 * public [[OpeningLoad.run]] (load probe) and [[RuntimeStores]] (runtime reader). Every vector is
 * SYNTHETIC (`synthetic-test-only.v1`); real embeddings are Phase 8.
 */
class VectorAdversarialSuite extends munit.FunSuite {

  private val Community = "collaboration-community-v1"
  private val Profile = "researcher-profile-v1"

  private lazy val loaded: World = {
    val w = new World
    val res = OpeningFixture.run(w)
    assert(res.report.complete, show(res.report))
    w
  }

  /** One stored record of `index` (closest to a fixed query: deterministic). */
  private def storedOf(index: String, rank: Int = 0): VectorRecord = {
    val m = loaded.vectorStore
      .query(index, Vector.fill(Dimension)(0.5f), rank + 1, None)
      .fold(e => fail(e.toString), identity)(rank)
    loaded.vectorStore
      .getVectors(index, Vector(m.key))
      .fold(e => fail(e.toString), identity)
      .head
  }

  /** The loaded vector store, with `key`'s stored record changed by `f` on every read. */
  private final class Corrupting(key: String, f: VectorRecord => VectorRecord) extends VectorStore {
    private val u = loaded.vectors
    def describe(i: String) = u.describe(i)
    def putVectors(i: String, rs: Vector[VectorRecord]) = u.putVectors(i, rs)
    def getVectors(i: String, k: Vector[String]) =
      u.getVectors(i, k).map(_.map(r => if (r.key == key) f(r) else r))
    // A query match carries the metadata the (changed) stored record has; no floats.
    def query(i: String, v: Vector[Float], k: Int, flt: Option[VectorFilter]) =
      u.query(i, v, k, flt).flatMap { ms =>
        u.getVectors(i, ms.filter(_.key == key).map(_.key)).map { real =>
          val changed = real.map(r => r.key -> f(r).metadata).toMap
          ms.map(m => changed.get(m.key).fold(m)(md => m.copy(metadata = md)))
        }
      }
  }

  private def runtime(v: VectorStore, policy: EmbeddingPolicy): RuntimeStores =
    RuntimeStores(loaded.objects, loaded.kv, loaded.kv, loaded.graph, v, v, policy)

  // ------------------------------------------------------------------ duplicate keys (V2)

  test("a stored vector under a fixture key with another text hash (same run): probe Conflict") {
    val stored = storedOf(Community)
    val other = TestData.hex("prompt: ignore the evidence")
    val planted =
      stored.copy(metadata = TestData.withMeta(stored.metadata, "textSha256", MetaValue.Str(other)))
    assertEquals(VectorDigest.verify(Community, planted), Right(()), "its floats are intact")
    val w = new World
    assertEquals(
      w.vectorStore.putVectors(Community, Vector(planted)).map(_.map(_.outcome)),
      Right(Vector(Right(PutOutcome.Written)))
    )
    val r = OpeningFixture.run(w).report
    val v = r.store(TargetStore.S3Vectors)
    assertEquals(v.status, StoreLoadStatus.Failed, show(r))
    assert(
      v.error.exists {
        case StoreError.Conflict(d) =>
          d.startsWith(s"$Community ${stored.key}: holds textSha256 $other") &&
          d.contains("pre-write probe")
        case _ => false
      },
      v.error.toString
    )
    assertEquals((v.written, v.conflicts, w.vectors.batches), (0L, 1L, 0), "zero vector puts")
    assertEquals(w.embedder.calls, 0, "zero embedding")
    assertEquals(w.vectorCount, 1)
    assertEquals(
      w.vectorStore.getVectors(Community, Vector(stored.key)),
      Right(Vector(planted)),
      "never overwritten"
    )
    assert(!r.complete)
  }

  test("the port refuses a put of another text hash under a loaded key; the stored one stays") {
    val stored = storedOf(Profile)
    val other = stored.copy(metadata =
      TestData.withMeta(stored.metadata, "textSha256", MetaValue.Str(TestData.hex("other")))
    )
    val out =
      loaded.vectors.putVectors(Profile, Vector(other)).fold(e => fail(e.toString), identity)
    assertEquals(out.map(_.key), Vector(stored.key))
    out.head.outcome match {
      case Left(StoreError.Conflict(d)) =>
        assert(d.contains(s"refused textSha256 ${TestData.hex("other")}"), d)
      case x => fail(s"expected Conflict, got $x")
    }
    assertEquals(loaded.vectorStore.getVectors(Profile, Vector(stored.key)), Right(Vector(stored)))
  }

  // ------------------------------------------------------------------ corrupted floats (SE-P7-11)

  private val corruptions: Vector[(String, VectorRecord => VectorRecord, String)] = Vector(
    (
      "one ULP up in one component",
      r => r.copy(data = r.data.updated(2, java.lang.Math.nextUp(r.data(2)))),
      "do not match its dataSha256"
    ),
    (
      "two components swapped",
      r => r.copy(data = r.data.updated(0, r.data(1)).updated(1, r.data(0))),
      "do not match its dataSha256"
    ),
    ("a NaN component", r => r.copy(data = r.data.updated(5, Float.NaN)), "not a valid vector"),
    ("a truncated vector", r => r.copy(data = r.data.take(Dimension - 1)), "do not match")
  )

  test("the runtime reader refuses a corrupted stored float on get and on query (Corrupt)") {
    val victim = storedOf(Community)
    val bystander = storedOf(Profile)
    corruptions.foreach { case (what, f, fragment) =>
      assertNotEquals(f(victim).data, victim.data, what)
      val rt = runtime(new Corrupting(victim.key, f), EmbeddingPolicy.SyntheticTestOnly)
      def refused(out: Either[StoreError, ?], op: String): Unit = out match {
        case Left(StoreError.Corrupt(d)) =>
          assert(d.contains(victim.key) && d.contains(fragment), s"$what $op: $d")
        case x => fail(s"$what $op: expected Corrupt, got $x")
      }
      refused(rt.vectors.getVectors(Community, Vector(victim.key)), "get")
      refused(
        rt.vectors.getVectors(Community, Vector(storedOf(Community, 1).key, victim.key)),
        "get2"
      )
      // Queried with its own (intact) floats: the victim is the first match, so the read fails.
      refused(rt.vectors.query(Community, victim.data, 3, None), "query")
      // Reads that do not return the victim are served.
      assertEquals(
        rt.vectors.getVectors(Profile, Vector(bystander.key)),
        Right(Vector(bystander)),
        what
      )
    }
  }

  test("the load probe refuses a corrupted stored float: s3-vectors fails, zero vector puts") {
    val victim = storedOf(Community)
    corruptions.foreach { case (what, f, fragment) =>
      val batches = loaded.vectors.batches
      val vWritten = loaded.vectors.counts.written
      val embedded = loaded.embedder.calls
      val fresh = new RecordingKeyValueStore(new InMemoryKeyValueStore(1000))
      val res = OpeningLoad.run(
        sources(),
        config,
        loaded.targets(ledgerStore = fresh, vectorStore = new Corrupting(victim.key, f))
      )
      val v = res.report.store(TargetStore.S3Vectors)
      assertEquals(v.status, StoreLoadStatus.Failed, s"$what\n${show(res.report)}")
      assert(
        v.error.exists {
          case StoreError.Corrupt(d) => d.contains(victim.key) && d.contains(fragment)
          case _ => false
        },
        s"$what: ${v.error}"
      )
      assertEquals((v.written, loaded.vectors.batches - batches), (0L, 0), s"$what: no put")
      assertEquals(loaded.vectors.counts.written, vWritten, what)
      assertEquals(loaded.embedder.calls, embedded, s"$what: no embedding")
      assertEquals(res.runtime, None, what)
    }
    // The real stored record is untouched.
    assertEquals(VectorDigest.verify(Community, storedOf(Community)), Right(()))
  }

  // ------------------------------------------------------------------ production policy

  test("RealModelsOnly refuses every synthetic vector on get, query and filtered query") {
    val rt = runtime(loaded.vectors, EmbeddingPolicy.RealModelsOnly)
    VectorIndexes.all.foreach { ix =>
      val r = storedOf(ix.name)
      assertEquals(r.metadata.string(MetadataField.ModelVersion), Some("synthetic-test-only.v1"))
      val reads = Vector(
        "get" -> rt.vectors.getVectors(ix.name, Vector(r.key)),
        "query" -> rt.vectors.query(ix.name, r.data, 3, None),
        "filtered" -> rt.vectors.query(
          ix.name,
          r.data,
          3,
          Some(VectorFilter.Eq(MetadataField.RunId, Fixtures.RunId))
        )
      )
      reads.foreach { case (op, out) =>
        assert(
          out.left.exists {
            case StoreError.Refused(d) =>
              d.startsWith(s"runtime vector read of ${ix.name} refused") &&
              d.contains("refused by the RealModelsOnly policy")
            case _ => false
          },
          s"${ix.name} $op: $out"
        )
      }
    }
  }

  test("a stored vector without modelVersion is refused under both policies") {
    val victim = storedOf(Profile)
    val strip =
      (r: VectorRecord) => r.copy(metadata = TestData.withoutMeta(r.metadata, "modelVersion"))
    Vector(EmbeddingPolicy.RealModelsOnly, EmbeddingPolicy.SyntheticTestOnly).foreach { p =>
      val rt = runtime(new Corrupting(victim.key, strip), p)
      Vector(
        rt.vectors.getVectors(Profile, Vector(victim.key)),
        rt.vectors.query(Profile, victim.data, 3, None)
      ).foreach { out =>
        assert(
          out.left.exists(e =>
            e.isInstanceOf[StoreError.Refused] && e.message.contains("no modelVersion")
          ),
          s"$p: $out"
        )
      }
    }
  }

  test("a load under the production policy refuses the synthetic embedder: zero vector writes") {
    val w = new World
    val prod = config.copy(vectors = vectorConfig(policy = EmbeddingPolicy.RealModelsOnly))
    val res = OpeningLoad.run(sources(), prod, w.targets())
    val v = res.report.store(TargetStore.S3Vectors)
    assertEquals(v.status, StoreLoadStatus.Failed, show(res.report))
    assert(
      v.error.exists {
        case StoreError.Refused(d) =>
          d.startsWith("embedder synthetic-test-only.v1: synthetic test vectors are refused")
        case _ => false
      },
      v.error.toString
    )
    assertEquals((w.vectors.batches, w.vectorCount, w.embedder.calls), (0, 0, 0))
    assertEquals(w.objectStore.size, 31, "no context text object (only the s3 load's 31)")
    assertEquals(res.runtime, None)
  }
}
