package scholarchain.core.config

class ProductionWorkflowConfigSuite extends munit.FunSuite {
  private val fx = new AdapterConfigSuite
  private val overlay = {
    val s = scala.io.Source.fromResource("fixtures/production-workflow.conf")
    try s.mkString
    finally s.close()
  }
  private def load(extra: String = "", env: Map[String, String] = Map.empty) =
    ConfigLoader.resolve(Vector("base.conf" -> fx.full, "workflow.conf" -> (overlay + extra)), env)
  private def ok(extra: String = "", env: Map[String, String] = Map.empty) =
    load(extra, env).fold(e => fail(e.map(_.message).mkString("; ")), identity)

  test("complete explicit workflow section resolves Bedrock and reused model/runtime settings") {
    val c = ok()
    val s = ProductionSettings.forLauncher(c, ServiceKindKey.QueryJobs).toOption.get.workflow.get
    assertEquals(s.analysisRunId, "run:" + "a" * 64)
    assertEquals(s.models, c.models)
    assertEquals(s.runtime, c.runtime)
    assertEquals(s.bedrock.region, c.aws.region)
    assertEquals(s.contextBucket, c.storage.contextBucket)
    assertEquals(s.temperature, 0.0)
    assertEquals(s.normalize, true)
    assertEquals(
      ProductionSettings.forLauncher(c, ServiceKindKey.Graph).toOption.get.workflow,
      None
    )
  }

  test("unset section preserves existing config serialization/digest and launcher behavior") {
    val c = ConfigLoader.resolve(Vector("base.conf" -> fx.full), Map.empty).toOption.get
    val json = upickle.default.write(c)
    assert(!json.contains("productionWorkflow"))
    val historical = upickle.default.read[ScholarChainConfig](json)
    assertEquals(ScholarChainConfig.digest(c), ScholarChainConfig.digest(historical))
    assertEquals(
      ProductionSettings.forLauncher(c, ServiceKindKey.QueryJobs).toOption.get.workflow,
      None
    )
    assertEquals(
      ScholarChainConfig.digestWithoutGrouping(c),
      ScholarChainConfig.digestWithoutGrouping(ok())
    )
    assertNotEquals(ScholarChainConfig.digest(c), ScholarChainConfig.digest(ok()))
  }

  test("partial section is refused without echoing configuration values") {
    val bad = ConfigLoader.resolve(
      Vector(
        "base.conf" -> fx.full,
        "partial.conf" -> "configSchemaVersion = scholarchain.config.v2\nproductionWorkflow.snapshot = private-snapshot"
      ),
      Map.empty
    )
    assert(bad.isLeft)
    assert(!bad.left.toOption.get.map(_.message).mkString.contains("private-snapshot"))
  }

  test("decimal temperature supports environment precedence and rejects invalid finite range") {
    val c = ok(env = Map(ConfigKeys.envNameOf("productionWorkflow.temperature") -> "0.25"))
    assertEquals(c.productionWorkflow.temperature, Some(0.25))
    Vector("-0.1", "1.1", "NaN", "Infinity").foreach { value =>
      assert(
        load(env = Map(ConfigKeys.envNameOf("productionWorkflow.temperature") -> value)).isLeft
      )
    }
  }

  test("cross-field bounds, real-only versions, invalid run and SDK timeout refuse") {
    Vector(
      "maxCandidates = 100",
      "maxContextRows = 101",
      "rankK = 101",
      "directLimit = 101",
      "semanticMaxItems = 11",
      "semanticMaxRetrievals = 6",
      "synthesisMaxAttempts = 6",
      "embeddingModelVersion = synthetic-test-v1",
      "answerModelVersion = nonproduction-template-v1",
      "analysisRunId = invalid",
      "bedrock.apiCallAttemptTimeoutMillis = 1000",
      "modelMaxResponseBytes = 10"
    ).foreach { setting =>
      assert(load("\nproductionWorkflow." + setting).isLeft, setting)
    }
  }

  test("programmatically assembled incomplete section is refused by launcher") {
    val c = ok().copy(productionWorkflow = ProductionWorkflowConfig(snapshot = Some("example")))
    assert(ProductionSettings.forLauncher(c, ServiceKindKey.QueryJobs).isLeft)
  }
}
