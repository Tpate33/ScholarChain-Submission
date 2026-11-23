package scholarchain.core.config

class ProductionLoadConfigSuite extends munit.FunSuite {
  private val base = new AdapterConfigSuite().full
  private val overlay = {
    val s = scala.io.Source.fromResource("fixtures/production-load.conf")
    try s.mkString
    finally s.close()
  }
  private def load(extra: String = "", env: Map[String, String] = Map.empty) =
    ConfigLoader.resolve(Vector("base.conf" -> base, "load.conf" -> (overlay + extra)), env)
  private def ok(extra: String = "", env: Map[String, String] = Map.empty) =
    load(extra, env).fold(e => fail(e.map(_.message).mkString("; ")), identity)

  test("explicit operational settings resolve local roots, writer endpoint and vector bounds") {
    val s = ProductionLoadSettings.from(ok()).toOption.get
    assertEquals(s.analysisConfigFile, "/tmp/scholarchain-approved/original-analysis.conf")
    assertEquals(s.runDirectory, "/tmp/scholarchain-approved/analysis")
    assertEquals(s.writerEndpoint, "https://neptune-writer.example.invalid:8182")
    assertEquals(s.vectorBatchSize, 10)
    assertEquals(s.vectorDistance, "cosine")
    assertEquals(s.vectorRetryBaseMillis, 0L)
  }

  test(
    "unset loading section preserves old digest and runtime pipeline digest excludes load inputs"
  ) {
    val c = ConfigLoader.resolve(Vector("base.conf" -> base), Map.empty).toOption.get
    assert(!upickle.default.write(c).contains("productionLoad"))
    assert(ProductionLoadSettings.from(c).isLeft)
    assertEquals(
      ScholarChainConfig.digestWithoutGrouping(c),
      ScholarChainConfig.digestWithoutGrouping(ok())
    )
    assertNotEquals(ScholarChainConfig.digest(c), ScholarChainConfig.digest(ok()))
  }

  test("partial section and programmatic partial settings are refused") {
    val partial = ConfigLoader.resolve(
      Vector(
        "base.conf" -> base,
        "partial.conf" -> "configSchemaVersion = scholarchain.config.v2\nproductionLoad.sourceMaxFiles = 1"
      ),
      Map.empty
    )
    assert(partial.isLeft)
    val c = ok().copy(productionLoad = ProductionLoadConfig(sourceMaxFiles = Some(1)))
    assert(ProductionLoadSettings.from(c).isLeft)
  }

  test("path, endpoint, batch, retry and rejection limits fail closed") {
    Vector(
      "runDirectory = relative",
      "analysisConfigFile = relative",
      "analysisConfigFile = \"/tmp/../analysis.conf\"",
      "loadDirectory = \"/tmp/../private\"",
      "writerEndpoint = \"http://example.invalid:8182\"",
      "rawPrefix = \"../raw\"",
      "vectorDistance = unsupported",
      "vectorBatchSize = 101",
      "vectorRetryBaseMillis = 1",
      "maxItemsPerCall = 5",
      "upstreamMaxBytes = 2147483640",
      "benchmarkFile = \"../labels.json\"",
      "sourceMaxFiles = 0"
    ).foreach { setting => assert(load("\nproductionLoad." + setting).isLeft, setting) }
  }

  test("loading fields support standard external environment precedence") {
    val c = ok(env = Map(ConfigKeys.envNameOf("productionLoad.maxItemsPerCall") -> "50000"))
    assertEquals(ProductionLoadSettings.from(c).toOption.get.maxItemsPerCall, 50000)
  }
}
