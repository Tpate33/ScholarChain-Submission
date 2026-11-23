package scholarchain.services

import scala.util.control.NonFatal

import scholarchain.core.config.{LauncherSettings, ProductionWorkflowSettings}
import scholarchain.rag.agents.*
import scholarchain.rag.semantic.SemanticContextBudget
import scholarchain.storage.model.VectorIndexes
import scholarchain.storage.port.RuntimeStores

/** Production-only composition. All IDs, budgets and model choices come from resolved config. */
object ProductionWorkflow {
  private[services] def modelSettings(s: ProductionWorkflowSettings): BedrockModelSettings =
    BedrockModelSettings(
      s.bedrock,
      s.models.llmModelId,
      s.answerModelVersion,
      s.models.embeddingModelId,
      s.embeddingModelVersion,
      s.models.embeddingDimension,
      s.modelMaxTokens,
      s.temperature.toFloat,
      s.normalize,
      s.synthesisMaxPromptBytes,
      s.modelMaxResponseBytes,
      s.maxEmbeddingInputBytes,
      s.maxEmbeddingBatchTexts
    )

  private[services] def config(
      s: ProductionWorkflowSettings,
      questionBytes: Int,
      models: BedrockModels
  ): Either[String, (ServingContext, WorkflowConfig)] = for {
    serving <- ServingContext.of(s.analysisRunId, s.snapshot)
    limits <- AgentLimits.of(
      s.runtime,
      s.maxResults,
      s.stageTimeoutMillis,
      s.maxGraphCalls,
      s.maxKeyValueCalls,
      s.maxVisited,
      s.maxCandidates,
      s.maxContextRows,
      questionBytes,
      s.maxNames
    )
    evidence <- EvidenceLimits.of(
      s.evidenceMaxItems,
      s.evidenceMaxBytes,
      s.evidenceMaxPublicationItems
    )
    synthesis <- SynthesisConfig.of(
      models.answer,
      s.synthesisMaxAttempts,
      s.synthesisMaxPromptBytes,
      s.synthesisMaxOutputBytes,
      s.synthesisMaxClaims,
      s.synthesisMaxTokens
    )
    audit <- AuditConfig.of(s.auditTtlSeconds, s.auditMaxJsonBytes)
    budget <- SemanticContextBudget.of(
      s.semanticTopK,
      s.semanticMaxItems,
      s.semanticMaxExcerptBytes,
      s.semanticMaxContextBytes,
      questionBytes
    )
    semantic <- SemanticConfig.of(
      models.embedder,
      VectorIndexes.all.map(SemanticIndex(_, s.models.embeddingDimension)),
      s.contextBucket,
      budget,
      s.semanticMaxRetrievals
    )
    workflow <- WorkflowConfig.of(
      limits,
      evidence,
      synthesis,
      semantic,
      audit,
      s.rankK,
      s.directLimit
    )
  } yield serving -> workflow

  def build(settings: LauncherSettings, stores: RuntimeStores): Either[String, QueryWorkflow] =
    build(settings, stores, BedrockModels.open)

  private[services] def build(
      settings: LauncherSettings,
      stores: RuntimeStores,
      open: BedrockModelSettings => Either[String, BedrockModels]
  ): Either[String, QueryWorkflow] = for {
    s <- settings.workflow.toRight("workflow: productionWorkflow configuration is required")
    models <- open(modelSettings(s))
    result <- {
      val built =
        try
          config(s, settings.process.service.maxQuestionBytes, models).map { case (serving, wc) =>
            val delegate = QueryWorkflow.of(
              new CollaborationWorkflow(stores, serving, wc, AgentClock.system),
              StoreAccess.concurrent
            )
            new QueryWorkflow with AutoCloseable {
              def answer(queryId: String, question: String): WorkflowResult =
                delegate.answer(queryId, question)
              def close(): Unit = models.close()
            }
          }
        catch { case NonFatal(_) => Left("workflow: production composition failed") }
      built.left.map { e =>
        models.close(); e
      }
    }
  } yield result
}
