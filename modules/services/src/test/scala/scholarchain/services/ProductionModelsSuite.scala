package scholarchain.services

import java.util.concurrent.atomic.AtomicInteger

import munit.FunSuite
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.bedrockruntime.model.*
import software.amazon.awssdk.services.secretsmanager.model.{
  GetSecretValueRequest,
  GetSecretValueResponse
}

import scholarchain.core.config.{
  AdapterClientSettings,
  ModelConfig,
  ProductionWorkflowSettings,
  RuntimeConfig,
  ServiceKindKey
}
import scholarchain.rag.agents.{ModelRequest, Prompts, WorkflowOutcome}
import scholarchain.storage.model.EmbeddingPolicy
import scholarchain.storage.port.RuntimeStores
import scholarchain.services.localdemo.LocalDemoWorld

class ProductionModelsSuite extends FunSuite {
  private val settings = BedrockModelSettings(
    AdapterClientSettings("us-east-1", None, 1000, 500, 1),
    "configured-answer-model",
    "bedrock-answer.v1",
    BedrockModels.TitanV2,
    "titan-v2-256-normalized.v1",
    256,
    64,
    0f,
    true,
    4096,
    16384,
    4096,
    2
  )
  private def request(maxBytes: Int = 1024) =
    ModelRequest("prompt.v1", "schema.v1", "untrusted prompt", null, 1, None, maxBytes)
  private final class Calls extends BedrockCalls {
    var conversations = Vector.empty[ConverseRequest]
    var embeddings = Vector.empty[InvokeModelRequest]
    var fail = false
    var answerText = "{\"claims\":[]}"
    var embeddingText = ujson.write(ujson.Obj("embedding" -> ujson.Arr.from(Vector.fill(256)(0.1))))
    def converse(r: ConverseRequest): ConverseResponse = {
      conversations :+= r
      if (fail) throw new IllegalStateException("private-provider-body")
      ConverseResponse
        .builder()
        .output(
          ConverseOutput
            .builder()
            .message(
              Message
                .builder()
                .role(ConversationRole.ASSISTANT)
                .content(ContentBlock.fromText(answerText))
                .build()
            )
            .build()
        )
        .stopReason(StopReason.END_TURN)
        .usage(TokenUsage.builder().inputTokens(10).outputTokens(5).totalTokens(15).build())
        .build()
    }
    def invokeModel(r: InvokeModelRequest): InvokeModelResponse = {
      embeddings :+= r
      if (fail) throw new IllegalStateException("private-provider-body")
      InvokeModelResponse.builder().body(SdkBytes.fromUtf8String(embeddingText)).build()
    }
  }

  test(
    "Bedrock codecs preserve configured request bounds and return provider usage, without construction calls"
  ) {
    val calls = new Calls
    val closes = new AtomicInteger
    val models = new BedrockModels(settings, calls, () => { closes.incrementAndGet(); () })
    assert(calls.conversations.isEmpty && calls.embeddings.isEmpty)
    val reply = models.answer.complete(request()).toOption.get
    assertEquals(reply.inputTokens, Some(10L))
    assertEquals(reply.outputTokens, Some(5L))
    assertEquals(calls.conversations.head.modelId(), settings.answerModelId)
    assertEquals(calls.conversations.head.inferenceConfig().maxTokens().intValue(), 64)
    assertEquals(
      calls.conversations.head.messages().get(0).content().get(0).text(),
      "untrusted prompt"
    )
    assert(!models.answer.nonProduction)
    assertEquals(
      models.embedder.embed(Vector("one", "two")).toOption.get.map(_.size),
      Vector(256, 256)
    )
    val body = ujson.read(calls.embeddings.head.body().asUtf8String())
    assertEquals(body("inputText").str, "one")
    assertEquals(body("dimensions").num, 256d)
    assert(body("normalize").bool)
    models.close(); models.close()
    assertEquals(closes.get(), 1)
    assert(models.answer.complete(request()).isLeft)
    assert(models.embedder.embed(Vector("three")).isLeft)
    assertEquals(calls.conversations.size, 1)
    assertEquals(calls.embeddings.size, 2)
  }

  test(
    "Bedrock rejects oversized outputs, malformed vectors and excess inputs; errors exclude provider data"
  ) {
    val calls = new Calls
    val models = new BedrockModels(settings, calls, () => ())
    assert(models.answer.complete(request(1)).isLeft)
    calls.embeddingText = "{\"embedding\":[1,2]}"
    assert(models.embedder.embed(Vector("one")).isLeft)
    assert(models.embedder.embed(Vector("one", "two", "three")).isLeft)
    assertEquals(calls.embeddings.size, 1)
    calls.fail = true
    assert(!models.answer.complete(request()).toString.contains("private-provider-body"))
    assert(!models.embedder.embed(Vector("one")).toString.contains("private-provider-body"))
    assert(BedrockModels.validate(settings.copy(embeddingModelId = "different-provider")).isLeft)
    assert(BedrockModels.validate(settings.copy(dimension = 8)).isLeft)
    assert(
      BedrockModels.validate(settings.copy(embeddingModelVersion = "synthetic-test.v1")).isLeft
    )
  }

  private def document(extra: String = "") =
    s"""{"schemaVersion":"${SecretsManagerKeys.SchemaVersion}","clients":[{"clientId":"client-one","keySha256Hex":"${"a" * 64}","scopes":["query","read"]}]$extra}"""

  test(
    "SecretsManager keys fetch AWSCURRENT lazily, cache within TTL, refuse stale refresh and close once"
  ) {
    val s =
      LauncherFixture.launcherSettings(ServiceKindKey.QueryJobs).apiKeys.copy(cacheTtlMillis = 100)
    var now = 0L
    var fetches = 0
    var fail = false
    var last: GetSecretValueRequest = null
    val closes = new AtomicInteger
    val calls = new SecretValueCalls {
      def getSecretValue(r: GetSecretValueRequest) = {
        fetches += 1; last = r
        if (fail) throw new IllegalStateException("private-secret-value")
        GetSecretValueResponse.builder().secretString(document()).build()
      }
    }
    val keys = new SecretsManagerKeys(s, calls, () => { closes.incrementAndGet(); () }, () => now)
    assertEquals(fetches, 0)
    assertEquals(keys.clients().toOption.get.head.clientId, "client-one")
    assertEquals(last.versionStage(), "AWSCURRENT")
    assert(keys.clients().isRight)
    assertEquals(fetches, 1)
    now = 100000000L; fail = true
    val result = keys.clients()
    assert(result.isLeft)
    assert(!result.toString.contains("private-secret-value"))
    assertEquals(fetches, 2)
    keys.close(); keys.close()
    assertEquals(closes.get(), 1)
    assert(keys.clients().isLeft)
    assertEquals(fetches, 2)
  }

  test(
    "SecretsManager digest schema rejects unknown fields, raw keys, invalid scopes and duplicate IDs"
  ) {
    assert(SecretsManagerKeys.parse(document()).isRight)
    assert(SecretsManagerKeys.parse(document(",\"rawKey\":\"private\"")).isLeft)
    assert(SecretsManagerKeys.parse(document().replace("\"query\"", "\"admin\"")).isLeft)
    assert(SecretsManagerKeys.parse(document().replace("a" * 64, "private-raw-key")).isLeft)
    val duplicate = ujson.read(document())
    duplicate("clients") = ujson.Arr(duplicate("clients")(0), duplicate("clients")(0))
    assert(SecretsManagerKeys.parse(duplicate.render()).isLeft)
    assert(SecretsManagerKeys.parse("private-invalid-json").isLeft)
  }

  private def workflowSettings(world: LocalDemoWorld): ProductionWorkflowSettings =
    ProductionWorkflowSettings(
      analysisRunId = world.serving.analysisRunId,
      snapshot = world.serving.snapshot,
      answerModelVersion = settings.answerModelVersion,
      embeddingModelVersion = settings.embeddingModelVersion,
      maxResults = 50,
      maxGraphCalls = 200,
      maxKeyValueCalls = 200,
      maxVisited = 1000,
      maxCandidates = 5,
      maxContextRows = 10,
      maxNames = 4,
      evidenceMaxItems = 300,
      evidenceMaxBytes = 2000000,
      evidenceMaxPublicationItems = 5,
      synthesisMaxAttempts = 1,
      synthesisMaxPromptBytes = 400000,
      synthesisMaxOutputBytes = 400000,
      synthesisMaxClaims = 64,
      modelMaxTokens = 512,
      modelMaxResponseBytes = 400000,
      maxEmbeddingInputBytes = 4096,
      maxEmbeddingBatchTexts = 2,
      semanticTopK = 3,
      semanticMaxItems = 3,
      semanticMaxExcerptBytes = 2048,
      semanticMaxContextBytes = 65536,
      semanticMaxRetrievals = 5,
      rankK = 5,
      directLimit = 10,
      stageTimeoutMillis = 100000,
      synthesisMaxTokens = 1000000,
      auditTtlSeconds = 3600,
      auditMaxJsonBytes = 65536,
      temperature = 0,
      normalize = true,
      bedrock = settings.client,
      models = ModelConfig(settings.answerModelId, settings.embeddingModelId, settings.dimension),
      runtime = RuntimeConfig(4, 10, world.window),
      contextBucket = "local-demo-context"
    )

  test(
    "production workflow uses Bedrock adapter replies, verifies citations and persists audit under real-model admission"
  ) {
    // Fixture stores and simulated provider replies: this is offline composition evidence only.
    val world = LocalDemoWorld.fromClasspath().toOption.get
    val stores = RuntimeStores(
      world.objects,
      world.kv,
      world.kv,
      world.graphStore,
      world.vectors,
      world.vectors,
      EmbeddingPolicy.RealModelsOnly
    )
    var calls = 0
    var closes = 0
    val transport = new BedrockCalls {
      def converse(r: ConverseRequest): ConverseResponse = {
        calls += 1
        val prompt = r.messages().get(0).content().get(0).text()
        val facts = ujson.read(prompt.split("\nFACTS: ", 2)(1).split("\nGAPS: ", 2)(0)).arr
        val fact = facts.find(_("field").str == "coauthor").get
        val body = ujson.Obj(
          "schemaVersion" -> Prompts.OutputSchemaVersion,
          "claims" -> ujson.Arr(
            ujson.Obj(
              "kind" -> "coauthor-edge",
              "text" -> "withheld draft",
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
          ),
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
                  .content(ContentBlock.fromText(body.render()))
                  .build()
              )
              .build()
          )
          .stopReason(StopReason.END_TURN)
          .usage(TokenUsage.builder().inputTokens(100).outputTokens(100).totalTokens(200).build())
          .build()
      }
      def invokeModel(r: InvokeModelRequest): InvokeModelResponse =
        fail("a deterministic coauthor question must not request embeddings")
    }
    val ls = LauncherFixture
      .launcherSettings(ServiceKindKey.QueryJobs)
      .copy(workflow = Some(workflowSettings(world)))
    val workflow = ProductionWorkflow
      .build(ls, stores, s => Right(new BedrockModels(s, transport, () => { closes += 1 })))
      .toOption
      .get
    val result = workflow.answer("query:" + "1" * 64, "Who are the coauthors of Ada Lovelace?")
    assertEquals(calls, 1)
    result.outcome match {
      case WorkflowOutcome.Answered(response, _, nonProduction) =>
        assert(!nonProduction)
        assert(response.claims.nonEmpty)
        assert(response.claims.forall(_.evidenceIds.nonEmpty))
      case other => fail(other.toString)
    }
    assert(result.persistence.isRight)
    workflow.asInstanceOf[AutoCloseable].close(); workflow.asInstanceOf[AutoCloseable].close()
    assertEquals(closes, 1)
    assert(
      ProductionWorkflow
        .build(ls.copy(workflow = None), stores, _ => fail("must not open models"))
        .isLeft
    )
    val invalid = ls.copy(workflow = Some(workflowSettings(world).copy(rankK = 100)))
    assert(
      ProductionWorkflow
        .build(invalid, stores, s => Right(new BedrockModels(s, transport, () => { closes += 1 })))
        .isLeft
    )
    assertEquals(closes, 2, "a composition validation failure closes the opened model bundle")
  }
}
