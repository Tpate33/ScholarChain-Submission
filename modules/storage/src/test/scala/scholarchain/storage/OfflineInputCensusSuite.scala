package scholarchain.storage

import scholarchain.storage.load.*
import scholarchain.storage.model.{EmbeddingPolicy}

/**
 * Reads the accepted real dev producer artifacts; never instantiates storage targets or embeddings.
 */
object AcceptedDevCensus {
  val ModelVersion = "amazon.titan-embed-text-v2-v1-256"
  private lazy val resources = {
    val cwd = java.nio.file.Paths.get(sys.props("user.dir")).toAbsolutePath
    Vector(cwd.resolve("modules/storage/src/test/resources"), cwd.resolve("src/test/resources"))
      .find(java.nio.file.Files.isDirectory(_))
      .getOrElse(throw new IllegalStateException("run from repository or storage module directory"))
      .resolve("fixtures")
  }
  lazy val config: OpeningLoadConfig = {
    val path = resources.resolve("phase06-analysis-settings/analysis-dev.conf")
    val parsed = scholarchain.core.config.ConfigLoader
      .resolve(
        Vector(path.toString -> java.nio.file.Files.readString(path)),
        Map.empty
      )
      .fold(e => throw new IllegalStateException(e.toString), identity)
    val settings = scholarchain.core.config.AnalysisRunSettings
      .from(parsed)
      .fold(e => throw new IllegalStateException(e.toString), identity)
    OpeningLoadConfig(
      settings,
      OpeningFixture.parquetLimits,
      UpstreamFixture.upstreamBounds,
      OpeningFixture.benchmarkMaxBytes,
      KeyValueLoadLimits.of(400 * 1024).toOption.get,
      TestData.layout,
      OpeningFixture.vectorConfig(dimension = 256, policy = EmbeddingPolicy.RealModelsOnly)
    )
  }
  private def sources: OpeningLoadSources = {
    val analysis = resources.resolve("phase06-run-1-local")
    OpeningFixture.sources(
      run = Fixtures.runDir(analysis),
      loads = Fixtures.loadDir(analysis),
      grouping = resources.resolve("phase06-grouping-dev/run=" + UpstreamFixture.GroupingHex),
      curated = resources.resolve(
        "phase06-curated-dev/snapshot=dblp-2026-10-03/run=" + UpstreamFixture.CuratedHex
      ),
      benchmark = resources.resolve("phase06-benchmark-pairs")
    )
  }
  def inspect(cap: Long = 10000L, cfg: OpeningLoadConfig = config) =
    OfflineInputCensus.inspect(sources, cfg, ModelVersion, cap)

  /**
   * Run explicitly with storage / Test / runMain scholarchain.storage.AcceptedDevCensus. Stdout
   * JSON contains plans and hashes only; it is not a storage-load result.
   */
  def main(args: Array[String]): Unit = {
    require(args.isEmpty, "accepted dev census takes no arguments")
    val r = inspect().fold(e => throw new IllegalStateException(e.message), identity)
    val v = r.vectors
    val out = ujson.Obj(
      "kind" -> "offline-planned-inputs-no-embeddings-no-writes",
      "runId" -> r.runId,
      "partManifestSha256" -> r.partManifestSha256,
      "groupingRunId" -> r.groupingRunId,
      "groupingPartManifestSha256" -> r.groupingPartManifestSha256,
      "benchmarkDigest" -> r.benchmarkDigest,
      "curatedRunId" -> r.curatedRunId,
      "worksBySource" -> ujson.Obj.from(r.worksBySource.map((s, n) => s -> ujson.Num(n))),
      "indexes" -> ujson.Arr.from(v.indexes.map { (name, n, bytes) =>
        ujson.Obj("index" -> name, "candidateVectors" -> n, "embeddingTextBytes" -> bytes)
      }),
      "totalCandidateVectors" -> v.total,
      "dimension" -> v.dimension,
      "embeddingTextBytes" -> v.textBytes,
      "largestEmbeddingTextBytes" -> v.largestTextBytes,
      "plannedWorkManifestSha256" -> v.workManifestSha256,
      "graphVertices" -> r.graphVertices,
      "graphEdges" -> r.graphEdges,
      "analysisItems" -> r.analysisItems,
      "analysisItemBytesEstimate" -> r.analysisItemBytes,
      "largestAnalysisItemBytesEstimate" -> r.largestAnalysisItemBytes,
      "analysisBaseWriteUnitsEstimate" -> r.analysisBaseWriteUnits,
      "analysisAllProjectionGsiWriteUnitsEstimate" -> r.analysisGsiWriteUnits,
      "parts" -> ujson.read(upickle.default.write(r.parts)),
      "embeddingWorkManifest" -> ujson.read(upickle.default.write(v.workManifest))
    )
    println(ujson.write(out, indent = 2))
  }
}

class OfflineInputCensusSuite extends munit.FunSuite {
  test("accepted real dev census counts every index and exact deterministic embedding texts") {
    val r = AcceptedDevCensus.inspect().fold(e => fail(e.message), identity)
    assertEquals(r.worksBySource.toMap, Map("dblp" -> 41, "openalex" -> 43, "crossref" -> 6))
    assertEquals(
      r.vectors.indexes.map((name, n, _) => name -> n),
      Vector(
        "researcher-profile-v1" -> 176,
        "collaboration-community-v1" -> 51,
        "paper-abstract-v1" -> 8,
        "collaboration-path-v1" -> 9,
        "topic-institution-summary-v1" -> 5
      )
    )
    assertEquals(r.vectors.total, 249)
    assertEquals(r.vectors.dimension, 256)
    assertEquals(r.vectors.indexes.map(_._3).sum, r.vectors.textBytes)
    assertEquals(r.vectors.workManifest.items.size, 249)
    assert(r.vectors.textBytes > 0)
    assert(r.graphVertices > 0 && r.graphEdges > 0 && r.analysisItems > 0)
    assertEquals(AcceptedDevCensus.inspect(), Right(r))
  }

  test(
    "global cap counts all indexes regardless of maxItemsPerCall and refuses rather than truncates"
  ) {
    val cfg = AcceptedDevCensus.config.copy(vectors =
      OpeningFixture.vectorConfig(
        dimension = 256,
        policy = EmbeddingPolicy.RealModelsOnly,
        maxItemsPerCall = 1,
        maxRejected = 0
      )
    )
    assertEquals(AcceptedDevCensus.inspect(249, cfg).map(_.vectors.total), Right(249))
    assert(AcceptedDevCensus.inspect(248, cfg).left.exists(_.message.contains("249 total vectors")))
    assert(AcceptedDevCensus.inspect(0, cfg).isLeft)
  }

  test("pre-write metadata rejections fail census instead of silently projecting inputs") {
    val cfg = AcceptedDevCensus.config.copy(vectors =
      OpeningFixture.vectorConfig(
        limits = OpeningFixture.openingLimits.copy(maxValueBytes = 1),
        dimension = 256,
        policy = EmbeddingPolicy.RealModelsOnly
      )
    )
    assert(
      AcceptedDevCensus.inspect(cfg = cfg).left.exists(_.message.contains("no projection allowed"))
    )
  }
}
