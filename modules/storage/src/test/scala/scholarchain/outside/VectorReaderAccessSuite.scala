package scholarchain.outside

/**
 * SE-P7-12 (CR-P7-13 m2): code outside package `scholarchain.storage` (this suite's package) cannot
 * obtain an unchecked vector-reader facade from storage. The only facade is
 * `VectorReader.verified`, which applies the integrity and model-policy checks.
 */
class VectorReaderAccessSuite extends munit.FunSuite {

  test("no unchecked vector-reader facade is reachable from outside storage") {
    assert(
      compileErrors("scholarchain.storage.port.VectorReader.readOnly(???)").nonEmpty,
      "the unchecked readOnly facade must not exist"
    )
    assert(
      compileErrors(
        "scholarchain.storage.port.VectorReader.verified(???, scholarchain.storage.model.EmbeddingPolicy.RealModelsOnly)"
      ).isEmpty,
      "control: the verified facade compiles from outside storage"
    )
  }

  test("the digest preimage helper is storage-private") {
    assert(
      compileErrors("scholarchain.storage.model.VectorDigest.preimage(Vector(1f))").nonEmpty
    )
    assert(
      compileErrors("scholarchain.storage.model.VectorDigest.of(Vector(1f))").isEmpty,
      "control: the public digest compiles"
    )
  }
}
