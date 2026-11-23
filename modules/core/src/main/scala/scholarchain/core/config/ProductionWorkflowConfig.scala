package scholarchain.core.config

import upickle.default.ReadWriter

/** Optional additive production-only section. No defaults for any operational value. */
final case class ProductionWorkflowConfig(
    analysisRunId: Option[String] = None,
    snapshot: Option[String] = None,
    answerModelVersion: Option[String] = None,
    embeddingModelVersion: Option[String] = None,
    maxResults: Option[Int] = None,
    maxGraphCalls: Option[Int] = None,
    maxKeyValueCalls: Option[Int] = None,
    maxVisited: Option[Int] = None,
    maxCandidates: Option[Int] = None,
    maxContextRows: Option[Int] = None,
    maxNames: Option[Int] = None,
    evidenceMaxItems: Option[Int] = None,
    evidenceMaxBytes: Option[Int] = None,
    evidenceMaxPublicationItems: Option[Int] = None,
    synthesisMaxAttempts: Option[Int] = None,
    synthesisMaxPromptBytes: Option[Int] = None,
    synthesisMaxOutputBytes: Option[Int] = None,
    synthesisMaxClaims: Option[Int] = None,
    modelMaxTokens: Option[Int] = None,
    modelMaxResponseBytes: Option[Int] = None,
    maxEmbeddingInputBytes: Option[Int] = None,
    maxEmbeddingBatchTexts: Option[Int] = None,
    semanticTopK: Option[Int] = None,
    semanticMaxItems: Option[Int] = None,
    semanticMaxExcerptBytes: Option[Int] = None,
    semanticMaxContextBytes: Option[Int] = None,
    semanticMaxRetrievals: Option[Int] = None,
    rankK: Option[Int] = None,
    directLimit: Option[Int] = None,
    stageTimeoutMillis: Option[Long] = None,
    synthesisMaxTokens: Option[Long] = None,
    auditTtlSeconds: Option[Long] = None,
    auditMaxJsonBytes: Option[Long] = None,
    temperature: Option[Double] = None,
    normalize: Option[Boolean] = None,
    bedrock: BedrockConfig = BedrockConfig()
) derives ReadWriter

final case class BedrockConfig(
    apiCallTimeoutMillis: Option[Long] = None,
    apiCallAttemptTimeoutMillis: Option[Long] = None,
    retryMaxAttempts: Option[Int] = None,
    endpointOverride: Option[String] = None
) derives ReadWriter

/** Values consumed by services; core has no dependency on RAG or concrete clients. */
final case class ProductionWorkflowSettings(
    analysisRunId: String,
    snapshot: String,
    answerModelVersion: String,
    embeddingModelVersion: String,
    maxResults: Int,
    maxGraphCalls: Int,
    maxKeyValueCalls: Int,
    maxVisited: Int,
    maxCandidates: Int,
    maxContextRows: Int,
    maxNames: Int,
    evidenceMaxItems: Int,
    evidenceMaxBytes: Int,
    evidenceMaxPublicationItems: Int,
    synthesisMaxAttempts: Int,
    synthesisMaxPromptBytes: Int,
    synthesisMaxOutputBytes: Int,
    synthesisMaxClaims: Int,
    modelMaxTokens: Int,
    modelMaxResponseBytes: Int,
    maxEmbeddingInputBytes: Int,
    maxEmbeddingBatchTexts: Int,
    semanticTopK: Int,
    semanticMaxItems: Int,
    semanticMaxExcerptBytes: Int,
    semanticMaxContextBytes: Int,
    semanticMaxRetrievals: Int,
    rankK: Int,
    directLimit: Int,
    stageTimeoutMillis: Long,
    synthesisMaxTokens: Long,
    auditTtlSeconds: Long,
    auditMaxJsonBytes: Long,
    temperature: Double,
    normalize: Boolean,
    bedrock: AdapterClientSettings,
    models: ModelConfig,
    runtime: RuntimeConfig,
    contextBucket: String
)

object ProductionWorkflowKeys {
  import ValueKind.*
  val Prefix = "productionWorkflow."
  val BedrockPrefix = Prefix + "bedrock."
  val IntFields: Vector[String] = Vector(
    "maxResults",
    "maxGraphCalls",
    "maxKeyValueCalls",
    "maxVisited",
    "maxCandidates",
    "maxContextRows",
    "maxNames",
    "evidenceMaxItems",
    "evidenceMaxBytes",
    "evidenceMaxPublicationItems",
    "synthesisMaxAttempts",
    "synthesisMaxPromptBytes",
    "synthesisMaxOutputBytes",
    "synthesisMaxClaims",
    "modelMaxTokens",
    "modelMaxResponseBytes",
    "maxEmbeddingInputBytes",
    "maxEmbeddingBatchTexts",
    "semanticTopK",
    "semanticMaxItems",
    "semanticMaxExcerptBytes",
    "semanticMaxContextBytes",
    "semanticMaxRetrievals",
    "rankK",
    "directLimit"
  )
  val LongFields: Vector[String] =
    Vector("stageTimeoutMillis", "synthesisMaxTokens", "auditTtlSeconds", "auditMaxJsonBytes")
  private val modelPattern = "[a-z0-9][a-z0-9._-]{0,127}"
  val all: Vector[KeySpec] =
    Vector(
      KeySpec(
        Prefix + "analysisRunId",
        OptionalString,
        KeyBound.Matches("run:[0-9a-f]{64}", "a run ID")
      ),
      KeySpec(Prefix + "snapshot", OptionalString, KeyBound.Token(128)),
      KeySpec(
        Prefix + "answerModelVersion",
        OptionalString,
        KeyBound.Matches(modelPattern, "a canonical model version")
      ),
      KeySpec(
        Prefix + "embeddingModelVersion",
        OptionalString,
        KeyBound.Matches(modelPattern, "a canonical model version")
      )
    ) ++ IntFields.map(n =>
      KeySpec(
        Prefix + n,
        OptionalInt32,
        KeyBound.Range(if (n == "evidenceMaxPublicationItems") 0 else 1, Int.MaxValue.toLong)
      )
    ) ++
      LongFields.map(n =>
        KeySpec(Prefix + n, OptionalInt64, KeyBound.Range(1, ProductionBounds.MaxDurationMillis))
      ) ++
      Vector(
        KeySpec(Prefix + "temperature", OptionalDouble, KeyBound.DecimalRange(0.0, 1.0)),
        KeySpec(Prefix + "normalize", OptionalBoolean, KeyBound.Unbounded),
        KeySpec(
          BedrockPrefix + "apiCallTimeoutMillis",
          OptionalInt64,
          KeyBound.Range(1, ProductionBounds.MaxDurationMillis)
        ),
        KeySpec(
          BedrockPrefix + "apiCallAttemptTimeoutMillis",
          OptionalInt64,
          KeyBound.Range(1, ProductionBounds.MaxDurationMillis)
        ),
        KeySpec(
          BedrockPrefix + "retryMaxAttempts",
          OptionalInt32,
          KeyBound.Range(1, AdapterBounds.MaxRetryAttempts)
        ),
        KeySpec(BedrockPrefix + "endpointOverride", OptionalString, KeyBound.Endpoint)
      )
  val required: Vector[String] = all.map(_.path).filterNot(_ == BedrockPrefix + "endpointOverride")

  private def i(m: Map[String, Any], n: String): Int = m(Prefix + n).asInstanceOf[Int]
  private def l(m: Map[String, Any], n: String): Long = m(Prefix + n).asInstanceOf[Long]
  private def rule(id: String, names: Vector[String], formula: String)(
      f: Map[String, Any] => Boolean
  ) =
    ConfigRule(id, names.map(Prefix + _), formula, "production workflow port contracts", f)
  val rules: Vector[ConfigRule] = Vector(
    ConfigRule(
      "WF-COMPLETE",
      required,
      "all productionWorkflow fields are set together",
      "production workflow configuration",
      m => required.forall(m.contains),
      applies = Some(m => all.exists(k => m.contains(k.path)))
    ),
    rule("WF-CANDIDATES", Vector("maxCandidates", "maxResults"), "maxCandidates < maxResults")(m =>
      i(m, "maxCandidates") < i(m, "maxResults")
    ),
    rule("WF-CONTEXT", Vector("maxContextRows", "maxResults"), "maxContextRows <= maxResults")(m =>
      i(m, "maxContextRows") <= i(m, "maxResults")
    ),
    rule("WF-RANK", Vector("rankK", "maxResults"), "rankK <= maxResults")(m =>
      i(m, "rankK") <= i(m, "maxResults")
    ),
    rule("WF-DIRECT", Vector("directLimit", "maxResults"), "directLimit <= maxResults")(m =>
      i(m, "directLimit") <= i(m, "maxResults")
    ),
    rule(
      "WF-ITEMS",
      Vector("semanticMaxItems", "semanticTopK"),
      "semanticMaxItems <= semanticTopK"
    )(m => i(m, "semanticMaxItems") <= i(m, "semanticTopK")),
    rule("WF-RETRIEVALS", Vector("semanticMaxRetrievals"), "semanticMaxRetrievals <= 5")(m =>
      i(m, "semanticMaxRetrievals") <= 5
    ),
    rule("WF-ATTEMPTS", Vector("synthesisMaxAttempts"), "synthesisMaxAttempts <= 5")(m =>
      i(m, "synthesisMaxAttempts") <= 5
    ),
    rule(
      "WF-EMBEDDING",
      Vector("embeddingModelVersion"),
      "embeddingModelVersion must not be synthetic"
    )(m => !m(Prefix + "embeddingModelVersion").asInstanceOf[String].startsWith("synthetic-")),
    rule(
      "WF-ANSWER",
      Vector("answerModelVersion"),
      "answerModelVersion must not name a nonproduction model"
    )(m => {
      val s = m(Prefix + "answerModelVersion").asInstanceOf[String]
      !s.startsWith("synthetic-") && !s.startsWith("nonproduction-")
    }),
    rule(
      "WF-TIMEOUT",
      Vector("bedrock.apiCallAttemptTimeoutMillis", "bedrock.apiCallTimeoutMillis"),
      "Bedrock attempt timeout < call timeout"
    )(m =>
      m(BedrockPrefix + "apiCallAttemptTimeoutMillis").asInstanceOf[Long] <
        m(BedrockPrefix + "apiCallTimeoutMillis").asInstanceOf[Long]
    ),
    rule(
      "WF-RESPONSE",
      Vector("synthesisMaxOutputBytes", "modelMaxResponseBytes"),
      "modelMaxResponseBytes >= synthesisMaxOutputBytes"
    )(m => i(m, "modelMaxResponseBytes") >= i(m, "synthesisMaxOutputBytes"))
  )

  def build(m: Map[String, Any]): ProductionWorkflowConfig = {
    def get[A](n: String): Option[A] = m.get(Prefix + n).map(_.asInstanceOf[A])
    ProductionWorkflowConfig(
      analysisRunId = get[String]("analysisRunId"),
      snapshot = get[String]("snapshot"),
      answerModelVersion = get[String]("answerModelVersion"),
      embeddingModelVersion = get[String]("embeddingModelVersion"),
      maxResults = get[Int]("maxResults"),
      maxGraphCalls = get[Int]("maxGraphCalls"),
      maxKeyValueCalls = get[Int]("maxKeyValueCalls"),
      maxVisited = get[Int]("maxVisited"),
      maxCandidates = get[Int]("maxCandidates"),
      maxContextRows = get[Int]("maxContextRows"),
      maxNames = get[Int]("maxNames"),
      evidenceMaxItems = get[Int]("evidenceMaxItems"),
      evidenceMaxBytes = get[Int]("evidenceMaxBytes"),
      evidenceMaxPublicationItems = get[Int]("evidenceMaxPublicationItems"),
      synthesisMaxAttempts = get[Int]("synthesisMaxAttempts"),
      synthesisMaxPromptBytes = get[Int]("synthesisMaxPromptBytes"),
      synthesisMaxOutputBytes = get[Int]("synthesisMaxOutputBytes"),
      synthesisMaxClaims = get[Int]("synthesisMaxClaims"),
      modelMaxTokens = get[Int]("modelMaxTokens"),
      modelMaxResponseBytes = get[Int]("modelMaxResponseBytes"),
      maxEmbeddingInputBytes = get[Int]("maxEmbeddingInputBytes"),
      maxEmbeddingBatchTexts = get[Int]("maxEmbeddingBatchTexts"),
      semanticTopK = get[Int]("semanticTopK"),
      semanticMaxItems = get[Int]("semanticMaxItems"),
      semanticMaxExcerptBytes = get[Int]("semanticMaxExcerptBytes"),
      semanticMaxContextBytes = get[Int]("semanticMaxContextBytes"),
      semanticMaxRetrievals = get[Int]("semanticMaxRetrievals"),
      rankK = get[Int]("rankK"),
      directLimit = get[Int]("directLimit"),
      stageTimeoutMillis = get[Long]("stageTimeoutMillis"),
      synthesisMaxTokens = get[Long]("synthesisMaxTokens"),
      auditTtlSeconds = get[Long]("auditTtlSeconds"),
      auditMaxJsonBytes = get[Long]("auditMaxJsonBytes"),
      temperature = get[Double]("temperature"),
      normalize = get[Boolean]("normalize"),
      bedrock = BedrockConfig(
        apiCallTimeoutMillis = get[Long]("bedrock.apiCallTimeoutMillis"),
        apiCallAttemptTimeoutMillis = get[Long]("bedrock.apiCallAttemptTimeoutMillis"),
        retryMaxAttempts = get[Int]("bedrock.retryMaxAttempts"),
        endpointOverride = get[String]("bedrock.endpointOverride")
      )
    )
  }

  /** Only a complete, validated section resolves; no client is built here. */
  def settings(c: ScholarChainConfig, m: Map[String, Any]): Option[ProductionWorkflowSettings] =
    Option.when(
      required.forall(m.contains) && c.models != null && c.runtime != null && c.storage != null
    ) {
      ProductionWorkflowSettings(
        analysisRunId = m(Prefix + "analysisRunId").asInstanceOf[String],
        snapshot = m(Prefix + "snapshot").asInstanceOf[String],
        answerModelVersion = m(Prefix + "answerModelVersion").asInstanceOf[String],
        embeddingModelVersion = m(Prefix + "embeddingModelVersion").asInstanceOf[String],
        maxResults = m(Prefix + "maxResults").asInstanceOf[Int],
        maxGraphCalls = m(Prefix + "maxGraphCalls").asInstanceOf[Int],
        maxKeyValueCalls = m(Prefix + "maxKeyValueCalls").asInstanceOf[Int],
        maxVisited = m(Prefix + "maxVisited").asInstanceOf[Int],
        maxCandidates = m(Prefix + "maxCandidates").asInstanceOf[Int],
        maxContextRows = m(Prefix + "maxContextRows").asInstanceOf[Int],
        maxNames = m(Prefix + "maxNames").asInstanceOf[Int],
        evidenceMaxItems = m(Prefix + "evidenceMaxItems").asInstanceOf[Int],
        evidenceMaxBytes = m(Prefix + "evidenceMaxBytes").asInstanceOf[Int],
        evidenceMaxPublicationItems = m(Prefix + "evidenceMaxPublicationItems").asInstanceOf[Int],
        synthesisMaxAttempts = m(Prefix + "synthesisMaxAttempts").asInstanceOf[Int],
        synthesisMaxPromptBytes = m(Prefix + "synthesisMaxPromptBytes").asInstanceOf[Int],
        synthesisMaxOutputBytes = m(Prefix + "synthesisMaxOutputBytes").asInstanceOf[Int],
        synthesisMaxClaims = m(Prefix + "synthesisMaxClaims").asInstanceOf[Int],
        modelMaxTokens = m(Prefix + "modelMaxTokens").asInstanceOf[Int],
        modelMaxResponseBytes = m(Prefix + "modelMaxResponseBytes").asInstanceOf[Int],
        maxEmbeddingInputBytes = m(Prefix + "maxEmbeddingInputBytes").asInstanceOf[Int],
        maxEmbeddingBatchTexts = m(Prefix + "maxEmbeddingBatchTexts").asInstanceOf[Int],
        semanticTopK = m(Prefix + "semanticTopK").asInstanceOf[Int],
        semanticMaxItems = m(Prefix + "semanticMaxItems").asInstanceOf[Int],
        semanticMaxExcerptBytes = m(Prefix + "semanticMaxExcerptBytes").asInstanceOf[Int],
        semanticMaxContextBytes = m(Prefix + "semanticMaxContextBytes").asInstanceOf[Int],
        semanticMaxRetrievals = m(Prefix + "semanticMaxRetrievals").asInstanceOf[Int],
        rankK = m(Prefix + "rankK").asInstanceOf[Int],
        directLimit = m(Prefix + "directLimit").asInstanceOf[Int],
        stageTimeoutMillis = m(Prefix + "stageTimeoutMillis").asInstanceOf[Long],
        synthesisMaxTokens = m(Prefix + "synthesisMaxTokens").asInstanceOf[Long],
        auditTtlSeconds = m(Prefix + "auditTtlSeconds").asInstanceOf[Long],
        auditMaxJsonBytes = m(Prefix + "auditMaxJsonBytes").asInstanceOf[Long],
        temperature = m(Prefix + "temperature").asInstanceOf[Double],
        normalize = m(Prefix + "normalize").asInstanceOf[Boolean],
        bedrock = AdapterClientSettings(
          c.aws.region,
          m.get(BedrockPrefix + "endpointOverride").map(_.asInstanceOf[String]),
          m(BedrockPrefix + "apiCallTimeoutMillis").asInstanceOf[Long],
          m(BedrockPrefix + "apiCallAttemptTimeoutMillis").asInstanceOf[Long],
          m(BedrockPrefix + "retryMaxAttempts").asInstanceOf[Int]
        ),
        models = c.models,
        runtime = c.runtime,
        contextBucket = c.storage.contextBucket
      )
    }
}
