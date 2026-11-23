package scholarchain.services

import java.nio.file.{Files, Path, Paths}

import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.bedrockruntime.model.*

import scholarchain.core.config.{ConfigLoader, ScholarChainConfig}
import scholarchain.rag.agents.{Prompts, WorkflowOutcome}
import scholarchain.storage.fake.*
import scholarchain.storage.load.{OpeningLoad, OpeningLoadTargets}
import scholarchain.storage.model.*
import scholarchain.storage.port.{Clock, Jitter, Sleeper}

/** Real archived input, real provider codecs, simulated SDK replies and in-memory stores only. */
class ProductionOpeningLoadSuite extends munit.FunSuite {
  private val root = Iterator
    .iterate(Paths.get("").toAbsolutePath)(_.getParent)
    .takeWhile(_ != null)
    .find(p => Files.isRegularFile(p.resolve("build.sbt")))
    .get
  private val fixtures = root.resolve("modules/storage/src/test/resources/fixtures")
  private val acceptedRun = "run:a65f3f38b597fc4709fe24e0ddbd12ae09a0c79ad54f9d1f5378c06cfe8a8c40"
  private def read(path: String) = Files.readString(root.resolve(path))
  private def child(parent: Path, prefix: String): Path = {
    val stream = Files.list(parent)
    try stream.filter(_.getFileName.toString.startsWith(prefix)).findFirst().get()
    finally stream.close()
  }

  private def configuration: ScholarChainConfig = {
    val runParent = fixtures.resolve("phase06-run-1-local")
    val paths = Map(
      "/tmp/scholarchain-approved/analysis" -> runParent.resolve(
        "run=" + acceptedRun.stripPrefix("run:")
      ),
      "/tmp/scholarchain-approved/load-manifest" -> child(runParent, "load="),
      "/tmp/scholarchain-approved/grouping" -> child(
        fixtures.resolve("phase06-grouping-dev"),
        "run="
      ),
      "/tmp/scholarchain-approved/curated" -> child(
        child(fixtures.resolve("phase06-curated-dev"), "snapshot="),
        "run="
      ),
      "/tmp/scholarchain-approved/benchmark" -> fixtures.resolve("phase06-benchmark-pairs"),
      "/tmp/scholarchain-approved/original-analysis.conf" -> fixtures.resolve(
        "phase06-analysis-settings/analysis-dev.conf"
      )
    )
    val load =
      paths.foldLeft(read("modules/core/src/test/resources/fixtures/production-load.conf")) {
        case (text, (old, path)) => text.replace(old, path.toString)
      }
    val workflow = read("modules/core/src/test/resources/fixtures/production-workflow.conf")
      .replace("run:" + "a" * 64, acceptedRun)
      .replace("production-test", "dblp-2026-10-03")
      .replace("titan-text-v2-normalized-1024-v1", "titan-text-v2-normalized-256-v1")
    val base = read("tests/src/test/resources/production-connections/launcher.conf")
      .replace("example-embedding", BedrockModels.TitanV2)
      .replace("embeddingDimension = 8", "embeddingDimension = 256")
    val overlay = """configSchemaVersion = "scholarchain.config.v2"
      |runtime.defaultWindow { startYear = 2020, endYear = 2024 }
      |services.query-jobs.graphDefaultWindow { startYear = 2020, endYear = 2024 }
      |""".stripMargin
    ConfigLoader
      .resolve(
        Vector("base" -> base, "workflow" -> workflow, "load" -> load, "window" -> overlay),
        Map.empty
      )
      .fold(e => fail(e.toString), identity)
  }

  test(
    "accepted DBLP output loads with Bedrock embedding codec and answers through production workflow offline"
  ) {
    val prepared = ProductionLoadMain.prepare(configuration).fold(fail(_), identity)
    assertEquals(
      prepared.config.settings.configDigest,
      "e37c32b115c82cbedc56e2407b8a322b52505eabbd800dfb5dade372b47800df"
    )
    val settings = ProductionWorkflow.modelSettings(prepared.launcher.workflow.get)
    var embeddingCalls = 0
    var answerCalls = 0
    var selectedCoauthors = -1
    var closes = 0
    val transport = new BedrockCalls {
      def invokeModel(request: InvokeModelRequest): InvokeModelResponse = {
        embeddingCalls += 1
        assertEquals(request.modelId(), BedrockModels.TitanV2)
        val body = ujson.read(request.body().asUtf8String())
        assert(body("inputText").str.nonEmpty)
        assertEquals(body("dimensions").num, 256d)
        InvokeModelResponse
          .builder()
          .body(
            SdkBytes.fromUtf8String(
              ujson.Obj("embedding" -> ujson.Arr.from(Vector.fill(256)(0.0625))).render()
            )
          )
          .build()
      }
      def converse(request: ConverseRequest): ConverseResponse = {
        answerCalls += 1
        val prompt = request.messages().get(0).content().get(0).text()
        val resolved = ujson.read(prompt.split("\nRESOLVED: ", 2)(1).split("\nFACTS: ", 2)(0)).arr
        val researcher = resolved.find(_("name").str == "Ailing Zeng").get("researcherId").str
        val facts = ujson.read(prompt.split("\nFACTS: ", 2)(1).split("\nGAPS: ", 2)(0)).arr
        // An undirected edge supplies both orientations; select facts for the requested author.
        val coauthors = facts.filter(f =>
          f("field").str == "coauthor" &&
            f("subject").str.startsWith(researcher + "@")
        )
        selectedCoauthors = coauthors.size
        val claims = coauthors.map { fact =>
          ujson.Obj(
            "kind" -> "coauthor-edge",
            "text" -> "withheld provider draft",
            "evidenceIds" -> ujson.Arr(fact("evidenceId")),
            "assertions" -> ujson.Arr(
              ujson.Obj(
                "subject" -> fact("subject"),
                "field" -> fact("field"),
                "value" -> fact("value")
              )
            ),
            "uncertainty" -> ujson.Null
          )
        }
        val output = ujson.Obj(
          "schemaVersion" -> Prompts.OutputSchemaVersion,
          "claims" -> ujson.Arr.from(claims),
          "missingEvidence" -> ujson.Arr(),
          "explanations" -> ujson.Arr()
        )
        ConverseResponse
          .builder()
          .output(
            ConverseOutput
              .builder()
              .message(
                Message
                  .builder()
                  .role(ConversationRole.ASSISTANT)
                  .content(ContentBlock.fromText(output.render()))
                  .build()
              )
              .build()
          )
          .stopReason(StopReason.END_TURN)
          .usage(TokenUsage.builder().inputTokens(100).outputTokens(100).totalTokens(200).build())
          .build()
      }
    }
    val models = new BedrockModels(settings, transport, () => { closes += 1 })
    val graph = new InMemoryGraphStore
    val kv = new InMemoryKeyValueStore(100000)
    val ledger = new InMemoryKeyValueStore(100000)
    val objects = new InMemoryObjectStore(100000)
    val vectors = new InMemoryVectorStore(
      VectorIndexes.all.map(i =>
        i -> IndexInfo(
          i.name,
          settings.dimension,
          DistanceMetric.Cosine,
          MetadataField.nonFilterableFields.map(_.key)
        )
      ),
      prepared.vectorLimits
    )
    try {
      val loaded = OpeningLoad.run(
        prepared.sources,
        prepared.config,
        OpeningLoadTargets(
          graph,
          kv,
          ledger,
          objects,
          vectors,
          models.embedder,
          new Clock { def nowMillis() = System.currentTimeMillis() },
          new Sleeper { def sleep(millis: Long): Unit = () },
          new Jitter { def uniform(n: Long) = 0L }
        )
      )
      assert(loaded.report.complete, loaded.report.toString)
      assertEquals(loaded.report.runId, Some(acceptedRun))
      assertEquals(
        loaded.report.partManifestSha256,
        Some("749763adb05af272a3c0c5473ed3d90d071a3e762d67ef70ebef84295cc94907")
      )
      assert(embeddingCalls > 0, "OpeningLoad must invoke the real embedding adapter codec")
      val runtime = loaded.runtime.getOrElse(fail(loaded.report.toString))
      val workflow = ProductionWorkflow
        .build(prepared.launcher, runtime, _ => Right(models))
        .fold(fail(_), identity)
      try {
        val answer = workflow.answer("query:" + "9" * 64, "Who are the coauthors of Ailing Zeng?")
        assertEquals(answerCalls, 1)
        assertEquals(selectedCoauthors, 3)
        assert(answer.persistence.isRight, answer.persistence.toString)
        answer.outcome match {
          case WorkflowOutcome.Answered(response, _, nonProduction) =>
            assert(!nonProduction, "adapter provenance must identify the production model path")
            assertEquals(response.claims.size, 3)
            assert(response.claims.forall(_.evidenceIds.nonEmpty))
            val json = ujson.read(upickle.default.write(response))
            assert(json.render().contains(acceptedRun))
            val edges = json("evidenceBundle")("items").arr
              .filter(_("evidenceType").str == "coauthor-edge")
              .map(i => ujson.read(i("text").str))
            assertEquals(edges.size, 3)
            edges.foreach { edge =>
              assertEquals(edge("jointPublicationCount").num, 1d)
              assertEquals(
                edge("supportingWorkIds").arr.map(_.str).toVector,
                Vector("work:dblp:conf/aaai/ZengCZ023")
              )
            }
          case other => fail(other.toString)
        }
      } finally workflow.asInstanceOf[AutoCloseable].close()
    } finally models.close()
    assertEquals(closes, 1)
  }
}
