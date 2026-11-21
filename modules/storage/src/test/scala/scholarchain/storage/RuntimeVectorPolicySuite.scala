package scholarchain.storage

import scala.collection.immutable.SortedMap

import scholarchain.storage.fake.{InMemoryKeyValueStore, InMemoryObjectStore, InMemoryVectorStore}
import scholarchain.storage.model.*
import scholarchain.storage.port.*

/**
 * CR-P7-11 m2: the runtime vector reader checks every returned vector's `modelVersion` against an
 * explicit policy. `RuntimeStores` has no default policy, so production composition cannot pick the
 * synthetic test policy implicitly.
 */
class RuntimeVectorPolicySuite extends munit.FunSuite {

  private val Ix = "researcher-profile-v1"
  private val Dim = OpeningFixture.Dimension

  /** An intact record (SE-P7-11: with the dataSha256 of its floats). */
  private def record(key: String, model: Option[String]): VectorRecord = {
    val data = Vector.fill(Dim)(0.25f)
    VectorRecord(
      key,
      data,
      VectorMetadata(
        SortedMap.from(
          Vector(
            "documentType" -> MetaValue.Str("researcher-profile"),
            "dataSha256" -> MetaValue.Str(VectorDigest.of(data).toOption.get)
          ) ++
            model.map(m => "modelVersion" -> MetaValue.Str(m))
        )
      )
    )
  }

  private def store(records: VectorRecord*): InMemoryVectorStore = {
    val s = new InMemoryVectorStore(OpeningFixture.indexInfos(), OpeningFixture.openingLimits)
    if (records.nonEmpty) assert(s.putVectors(Ix, records.toVector).isRight)
    s
  }

  private def runtime(s: InMemoryVectorStore, policy: EmbeddingPolicy): RuntimeStores = {
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

  private val q = Vector.fill(Dim)(0.5f)

  test("RealModelsOnly (production) refuses any read that would return a synthetic vector") {
    val s = store(record("a", Some("synthetic-test-only.v1")), record("b", Some("real-model.v3")))
    val rt = runtime(s, EmbeddingPolicy.RealModelsOnly)
    val out = rt.vectors.query(Ix, q, 10, None)
    assert(
      out.left.exists(_.message.contains("refused by the RealModelsOnly policy")),
      out.toString
    )
    assert(rt.vectors.getVectors(Ix, Vector("a")).isLeft)
    assertEquals(rt.vectors.getVectors(Ix, Vector("b")).map(_.map(_.key)), Right(Vector("b")))
    // A vector without a model version is refused too (fail closed).
    val unlabelled = runtime(store(record("c", None)), EmbeddingPolicy.RealModelsOnly)
    assert(
      unlabelled.vectors.query(Ix, q, 10, None).left.exists(_.message.contains("no modelVersion"))
    )
  }

  test(
    "RealModelsOnly reads real-model vectors; SyntheticTestOnly (explicit, tests) reads only synthetic"
  ) {
    val real = store(record("b", Some("real-model.v3")))
    assertEquals(
      runtime(real, EmbeddingPolicy.RealModelsOnly).vectors
        .query(Ix, q, 10, None)
        .map(_.map(_.key)),
      Right(Vector("b"))
    )
    assert(runtime(real, EmbeddingPolicy.SyntheticTestOnly).vectors.query(Ix, q, 10, None).isLeft)
    val synthetic = store(record("a", Some("synthetic-test-only.v1")))
    assertEquals(
      runtime(synthetic, EmbeddingPolicy.SyntheticTestOnly).vectors
        .query(Ix, q, 10, None)
        .map(_.map(_.key)),
      Right(Vector("a"))
    )
  }

  test("the policy is a required argument: no implicit or default selection") {
    assert(compileErrors("RuntimeStores(???, ???, ???, ???, ???, ???)").nonEmpty)
    assert(compileErrors("RuntimeStores(???, ???, ???, ???, ???, ???, ???)").isEmpty)
    intercept[IllegalArgumentException](VectorReader.verified(store(), null))
  }

  test("n1: a stored vector without metadata is refused, never a NullPointerException") {
    val s = store()
    val bare: VectorReader = new VectorReader {
      def query(i: String, v: Vector[Float], k: Int, f: Option[VectorFilter]) =
        Right(Vector(VectorMatch("x", 0.1, null)))
      def getVectors(i: String, k: Vector[String]) =
        Right(Vector(VectorRecord("x", Vector.fill(Dim)(0.25f), null)))
    }
    val r = VectorReader.verified(bare, EmbeddingPolicy.SyntheticTestOnly)
    assert(r.getVectors(Ix, Vector("x")).isLeft)
    assert(r.query(Ix, q, 1, None).isLeft)
    assert(s.count(Ix) == 0)
  }
}
