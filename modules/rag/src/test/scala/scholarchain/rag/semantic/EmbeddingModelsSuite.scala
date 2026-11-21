package scholarchain.rag.semantic

import scholarchain.storage.model.{EmbeddingModelVersion, EmbeddingPolicy, StoreError}

/** RE-P8-01 items 3 and 4: model versioning and the non-production stand-in embedder. */
class EmbeddingModelsSuite extends munit.FunSuite {

  private val e = new NonProductionHashedTokenEmbedder(64)

  private def cos(a: Vector[Float], b: Vector[Float]): Double =
    a.indices.map(i => a(i).toDouble * b(i)).sum

  test("the stand-in's model version is canonical, synthetic and names itself non-production") {
    assertEquals(e.modelVersion, "synthetic-nonprod-hashed-token-bag.v1")
    assertEquals(EmbeddingModelVersion.problem(e.modelVersion), None)
    assert(EmbeddingPolicy.isSynthetic(e.modelVersion))
    assert(EmbeddingModels.isNonProduction(e.modelVersion))
    assert(e.modelVersion.contains("nonprod"))
  }

  test("production policy refuses the stand-in; the synthetic test policy admits it") {
    assert(EmbeddingModels.admit(e, EmbeddingPolicy.RealModelsOnly, 64).isLeft)
    assertEquals(
      EmbeddingModels.admit(e, EmbeddingPolicy.SyntheticTestOnly, 64),
      Right(e.modelVersion)
    )
    assert(EmbeddingModels.admit(e, EmbeddingPolicy.SyntheticTestOnly, 32).isLeft)
    assert(EmbeddingModels.admit(null, EmbeddingPolicy.SyntheticTestOnly, 64).isLeft)
  }

  test("non-canonical model versions are refused and never compared equal") {
    Vector("Synthetic-x.v1", " a.v1", "a:b", "", null, "-a", "a" * 129).foreach { v =>
      assert(EmbeddingModels.check(v).isLeft, s"accepted '$v'")
    }
    assert(EmbeddingModels.same("model-a.v1", "model-a.v1"))
    assert(!EmbeddingModels.same("model-a.v1", "model-a.v2"))
    assert(!EmbeddingModels.same("Model-A.v1", "Model-A.v1"))
    assert(!EmbeddingModels.same("model-a.v1", "MODEL-A.v1"))
  }

  test("an embedder with a non-canonical model version is not admitted") {
    val bad = new scholarchain.storage.port.Embedder {
      def modelVersion = "Synthetic-Upper.v1"
      def dimension = 64
      def embed(t: Vector[String]) = Left(StoreError.Refused("unused"))
    }
    assert(EmbeddingModels.admit(bad, EmbeddingPolicy.SyntheticTestOnly, 64).isLeft)
  }

  test("tokens are ASCII lower-cased [a-z0-9] runs") {
    assertEquals(
      NonProductionHashedTokenEmbedder.tokens("Researcher:SC:v1:ABC12 -- x_y"),
      Vector("researcher", "sc", "v1", "abc12", "x", "y")
    )
  }

  test("vectors are deterministic, L2-normalised, of the dimension, and order-free") {
    val Right(Vector(a, b, c)) =
      e.embed(Vector("alpha beta gamma", "gamma alpha beta", "alpha beta gamma")): @unchecked
    assertEquals(a.size, 64)
    assertEquals(a, b)
    assertEquals(a, c)
    assertEqualsDouble(math.sqrt(a.map(x => x.toDouble * x).sum), 1.0, 1e-5)
    val Right(Vector(again)) =
      new NonProductionHashedTokenEmbedder(64).embed(Vector("alpha beta gamma")): @unchecked
    assertEquals(again, a)
  }

  test("shared tokens are closer than disjoint tokens (identity of a token, not meaning)") {
    val Right(Vector(q, near, far)) = e.embed(
      Vector("community 7f3a delta", "community 7f3a delta epsilon", "zeta eta theta iota")
    ): @unchecked
    assert(cos(q, near) > cos(q, far), s"${cos(q, near)} <= ${cos(q, far)}")
  }

  test("a text without tokens is a per-text Permanent refusal; null is refused") {
    assert(e.embed(Vector("ok", " -- ")) match {
      case Left(StoreError.Permanent(_)) => true
      case _ => false
    })
    assert(e.embed(null).isLeft)
    assert(e.embed(Vector(null)).isLeft)
    intercept[IllegalArgumentException](new NonProductionHashedTokenEmbedder(0))
  }
}
