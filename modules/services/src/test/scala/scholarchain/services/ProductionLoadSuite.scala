package scholarchain.services

import java.nio.file.{Files, Path, Paths}

import scholarchain.core.config.{ConfigLoader, ScholarChainConfig}

/** Local-only CLI guards and wiring. Does not invoke execute with matching confirmation. */
class ProductionLoadSuite extends munit.FunSuite {
  private val root = Iterator
    .iterate(Paths.get("").toAbsolutePath)(_.getParent)
    .takeWhile(_ != null)
    .find(p => Files.isRegularFile(p.resolve("build.sbt")))
    .get
  private def read(relative: String) = Files.readString(root.resolve(relative))
  private val fixtures = root.resolve("modules/storage/src/test/resources/fixtures")
  private val runId = "run:a65f3f38b597fc4709fe24e0ddbd12ae09a0c79ad54f9d1f5378c06cfe8a8c40"
  private def config: ScholarChainConfig = {
    val analysis = ConfigLoader
      .resolve(
        Vector(
          "analysis" -> read(
            "modules/storage/src/test/resources/fixtures/phase06-analysis-settings/analysis-dev.conf"
          )
        ),
        Map.empty
      )
      .toOption
      .get
    val runHex = runId.stripPrefix("run:")
    val loadsParent = fixtures.resolve("phase06-run-1-local")
    val ds = Files.list(loadsParent)
    val loadDir =
      try ds.filter(p => p.getFileName.toString.startsWith("load=")).findFirst().get()
      finally ds.close()
    val groupingParent = fixtures.resolve("phase06-grouping-dev")
    val gs = Files.list(groupingParent)
    val groupingDir =
      try gs.filter(p => p.getFileName.toString.startsWith("run=")).findFirst().get()
      finally gs.close()
    val cs = Files.walk(fixtures.resolve("phase06-curated-dev"), 2)
    val curatedDir =
      try
        cs.filter(p => Files.isDirectory(p) && p.getFileName.toString.startsWith("run="))
          .findFirst()
          .get()
      finally cs.close()
    val sources = Map(
      "/tmp/scholarchain-approved/analysis" -> loadsParent.resolve("run=" + runHex),
      "/tmp/scholarchain-approved/load-manifest" -> loadDir,
      "/tmp/scholarchain-approved/grouping" -> groupingDir,
      "/tmp/scholarchain-approved/curated" -> curatedDir,
      "/tmp/scholarchain-approved/benchmark" -> fixtures.resolve("phase06-benchmark-pairs")
    )
    val loadTemplate = read("modules/core/src/test/resources/fixtures/production-load.conf")
      .replace(
        "/tmp/scholarchain-approved/original-analysis.conf",
        fixtures.resolve("phase06-analysis-settings/analysis-dev.conf").toString
      )
    val load = sources.foldLeft(loadTemplate) { case (s, (old, path)) =>
      s.replace(old, path.toString)
    }
    val wf = read("modules/core/src/test/resources/fixtures/production-workflow.conf")
      .replace("run:" + "a" * 64, runId)
      .replace("snapshot = \"production-test\"", "snapshot = \"dblp-2026-10-03\"")
    val base = LauncherFixture
      .text()
      .replace("example-embedding", BedrockModels.TitanV2)
      .replace("embeddingDimension = 8", "embeddingDimension = 256")
    val configured = ConfigLoader
      .resolve(Vector("base" -> base, "workflow" -> wf, "load" -> load), Map.empty)
      .fold(e => fail(e.toString), identity)
    // Use the exact analysis scope of the accepted dev run; production sections do not alter it.
    configured.copy(
      graph = analysis.graph,
      bridge = analysis.bridge,
      community = analysis.community,
      entityResolution = analysis.entityResolution,
      runtime = analysis.runtime,
      analysis = analysis.analysis
    )
  }

  test(
    "production loader defaults to validation and requires explicit exact-run execution confirmation"
  ) {
    assertEquals(ProductionLoadMain.parseArgs(Vector.empty), Right(None))
    assertEquals(ProductionLoadMain.parseArgs(Vector("--validate")), Right(None))
    assertEquals(ProductionLoadMain.parseArgs(Vector("--execute", runId)), Right(Some(runId)))
    assert(ProductionLoadMain.parseArgs(Vector("--execute")).isLeft)
    assert(ProductionLoadMain.parseArgs(Vector("--execute", "wrong")).isLeft)
    assert(ProductionLoadMain.parseArgs(Vector("--validate", "--execute", runId)).isLeft)
  }

  test(
    "production loader prepares accepted dev artifacts offline and refuses wrong-run execution before opening clients"
  ) {
    scholarchain.core.config.ProductionSettings
      .forLauncher(config, scholarchain.core.config.ServiceKindKey.QueryJobs)
      .fold(e => fail(e.toString), identity)
    val prepared = ProductionLoadMain.prepare(config).fold(fail(_), identity)
    assertEquals(prepared.runId, runId)
    assertEquals(
      prepared.partManifestSha256,
      "749763adb05af272a3c0c5473ed3d90d071a3e762d67ef70ebef84295cc94907"
    )
    assertEquals(
      prepared.config.settings.configDigest,
      "e37c32b115c82cbedc56e2407b8a322b52505eabbd800dfb5dade372b47800df"
    )
    assertEquals(prepared.launcher.workflow.get.models.embeddingModelId, BedrockModels.TitanV2)
    assertEquals(prepared.launcher.workflow.get.models.embeddingDimension, 256)
    assertEquals(
      prepared.config.vectors.embedding,
      scholarchain.storage.model.EmbeddingPolicy.RealModelsOnly
    )
    assert(ProductionLoadMain.execute(prepared, "run:" + "b" * 64).isLeft)
    val wrong = config.copy(productionWorkflow =
      config.productionWorkflow.copy(analysisRunId = Some("run:" + "b" * 64))
    )
    assert(ProductionLoadMain.prepare(wrong).isLeft)
    val wrongSnapshot = config.copy(productionWorkflow =
      config.productionWorkflow.copy(snapshot = Some("wrong-snapshot"))
    )
    assert(ProductionLoadMain.prepare(wrongSnapshot).isLeft)
    val badBatch =
      config.copy(productionLoad = config.productionLoad.copy(vectorBatchSize = Some(11)))
    assert(ProductionLoadMain.prepare(badBatch).isLeft)
    val privateConfig = config.copy(productionLoad =
      config.productionLoad.copy(analysisConfigFile = Some(root.resolve(".env.conf").toString))
    )
    assert(ProductionLoadMain.prepare(privateConfig).left.exists(_.contains("protected")))
  }

  test("production loader refuses protected and symlink sources before reading source content") {
    assert(
      ProductionLoadMain
        .safeSourcePath(root.resolve("gold/entity-resolution/heldout-v1").toString)
        .isLeft
    )
    assert(ProductionLoadMain.safeSourcePath(root.resolve(".aws").toString).isLeft)
    val directory = Files.createTempDirectory("production-load-source-")
    val link = directory.resolveSibling(directory.getFileName.toString + "-link")
    try {
      Files.createSymbolicLink(link, directory)
      assert(ProductionLoadMain.safeSourcePath(link.toString).isLeft)
    } finally { Files.deleteIfExists(link); Files.deleteIfExists(directory) }
  }
}
