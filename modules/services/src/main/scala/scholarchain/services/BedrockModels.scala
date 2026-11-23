package scholarchain.services

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import software.amazon.awssdk.services.bedrockruntime.model.{
  ContentBlock,
  ConversationRole,
  ConverseRequest,
  ConverseResponse,
  InferenceConfiguration,
  InvokeModelRequest,
  InvokeModelResponse,
  Message
}

import scholarchain.core.config.AdapterClientSettings
import scholarchain.rag.agents.{AnswerModel, ModelFailure, ModelReply, ModelRequest}
import scholarchain.storage.aws.{AmbientEndpoints, AmbientSettings, AwsClientSettings, AwsOverrides}
import scholarchain.storage.model.{EmbeddingModelVersion, EmbeddingPolicy, StoreError}
import scholarchain.storage.port.Embedder

trait BedrockCalls {
  def converse(request: ConverseRequest): ConverseResponse
  def invokeModel(request: InvokeModelRequest): InvokeModelResponse
}

/** Explicit invocation limits; the runtime and loader must share these embedding settings. */
final case class BedrockModelSettings(
    client: AdapterClientSettings,
    answerModelId: String,
    answerModelVersion: String,
    embeddingModelId: String,
    embeddingModelVersion: String,
    dimension: Int,
    maxTokens: Int,
    temperature: Float,
    normalize: Boolean,
    maxPromptBytes: Int,
    maxResponseBytes: Int,
    maxEmbeddingInputBytes: Int,
    maxEmbeddingBatchTexts: Int
)

/** Concrete Bedrock adapters. No request is sent by construction; all replies remain untrusted. */
final class BedrockModels private[services] (
    val settings: BedrockModelSettings,
    calls: BedrockCalls,
    release: () => Unit
) extends AutoCloseable {
  private val closed = new AtomicBoolean(false)
  private def bytes(s: String) = s.getBytes(StandardCharsets.UTF_8).length

  val answer: AnswerModel = new AnswerModel {
    val modelVersion = settings.answerModelVersion
    val nonProduction = false
    def complete(request: ModelRequest): Either[ModelFailure, ModelReply] = {
      def refused = Left(ModelFailure.Unavailable("Bedrock answer unavailable or invalid"))
      if (
        closed.get() || request == null || request.prompt == null || request.maxOutputBytes < 1 ||
        bytes(request.prompt) > settings.maxPromptBytes
      ) refused
      else
        try {
          val r = calls.converse(
            ConverseRequest
              .builder()
              .modelId(settings.answerModelId)
              .messages(
                Message
                  .builder()
                  .role(ConversationRole.USER)
                  .content(ContentBlock.fromText(request.prompt))
                  .build()
              )
              .inferenceConfig(
                InferenceConfiguration
                  .builder()
                  .maxTokens(settings.maxTokens)
                  .temperature(settings.temperature)
                  .build()
              )
              .build()
          )
          if (
            r == null || r.output() == null || r.output().message() == null || r
              .stopReasonAsString() != "end_turn"
          ) refused
          else {
            val content = r.output().message().content().asScala.toVector
            if (content.isEmpty || content.exists(c => c == null || c.text() == null)) refused
            else {
              val text = content.map(_.text()).mkString
              val in = Option(r.usage()).flatMap(u => Option(u.inputTokens())).map(_.longValue())
              val out = Option(r.usage()).flatMap(u => Option(u.outputTokens())).map(_.longValue())
              if (
                bytes(text) > math.min(request.maxOutputBytes, settings.maxResponseBytes) ||
                in.exists(_ < 0) || out.exists(n => n < 0 || n > settings.maxTokens)
              ) refused
              else Right(ModelReply(text, in, out))
            }
          }
        } catch { case NonFatal(_) => refused }
    }
  }

  val embedder: Embedder = new Embedder {
    val modelVersion = settings.embeddingModelVersion
    val dimension = settings.dimension
    def embed(texts: Vector[String]): Either[StoreError, Vector[Vector[Float]]] = {
      if (closed.get()) Left(StoreError.Refused("Bedrock embedding client closed"))
      else if (
        texts == null || texts.size > settings.maxEmbeddingBatchTexts ||
        texts.exists(t => t == null || t.trim.isEmpty || bytes(t) > settings.maxEmbeddingInputBytes)
      )
        Left(StoreError.Permanent("Bedrock embedding input exceeds configured bounds"))
      else
        texts.foldLeft[Either[StoreError, Vector[Vector[Float]]]](Right(Vector.empty)) {
          (acc, text) =>
            acc.flatMap { rows =>
              try {
                val body = ujson.write(
                  ujson.Obj(
                    "inputText" -> text,
                    "dimensions" -> dimension,
                    "normalize" -> settings.normalize
                  )
                )
                val r = calls.invokeModel(
                  InvokeModelRequest
                    .builder()
                    .modelId(settings.embeddingModelId)
                    .contentType("application/json")
                    .accept("application/json")
                    .body(SdkBytes.fromUtf8String(body))
                    .build()
                )
                if (
                  r == null || r
                    .body() == null || r.body().asByteArray().length > settings.maxResponseBytes
                )
                  Left(StoreError.Corrupt("Bedrock embedding response exceeds bounds"))
                else {
                  val value = ujson.read(r.body().asUtf8String())
                  val vector = value("embedding").arr.toVector.map(_.num.toFloat)
                  if (
                    vector.size != dimension || vector
                      .exists(v => !java.lang.Float.isFinite(v)) || vector.forall(_ == 0f)
                  )
                    Left(
                      StoreError
                        .Corrupt("Bedrock embedding response has invalid dimensions or values")
                    )
                  else Right(rows :+ vector)
                }
              } catch {
                case NonFatal(_) =>
                  Left(StoreError.Transient("Bedrock embedding unavailable or invalid"))
              }
            }
        }
    }
  }

  def close(): Unit = if (closed.compareAndSet(false, true)) release()
}

object BedrockModels {

  /**
   * This wire codec supports Titan Text Embeddings V2 only; another model cannot silently use it.
   */
  val TitanV2 = "amazon.titan-embed-text-v2:0"

  private[services] def validate(s: BedrockModelSettings): Either[String, Unit] =
    if (
      s == null || s.client == null || s.answerModelId == null || s.answerModelId.trim.isEmpty ||
      s.answerModelVersion == null || s.answerModelVersion.trim.isEmpty ||
      EmbeddingPolicy.isSynthetic(s.answerModelVersion) || s.answerModelVersion.toLowerCase
        .contains("nonproduction") ||
      s.embeddingModelId != TitanV2 || EmbeddingModelVersion
        .check(s.embeddingModelVersion)
        .isLeft ||
      EmbeddingPolicy
        .isSynthetic(s.embeddingModelVersion) || !Set(256, 512, 1024).contains(s.dimension) ||
      s.maxTokens < 1 || !java.lang.Float.isFinite(
        s.temperature
      ) || s.temperature < 0 || s.temperature > 1 ||
      s.maxPromptBytes < 1 || s.maxResponseBytes < 1 || s.maxEmbeddingInputBytes < 1 || s.maxEmbeddingBatchTexts < 1
    )
      Left("models: invalid Bedrock model, version, dimension or invocation bounds")
    else Right(())

  def open(settings: BedrockModelSettings): Either[String, BedrockModels] = for {
    _ <- validate(settings)
    s = settings.client
    clientSettings <- AwsClientSettings
      .of(
        s.region,
        s.endpointOverride,
        s.apiCallTimeoutMillis,
        s.apiCallAttemptTimeoutMillis,
        s.retryMaxAttempts
      )
      .left
      .map(_ => "models: invalid Bedrock client settings")
    _ <- AmbientEndpoints
      .check("bedrock-runtime", clientSettings.endpointOverride, AmbientSettings.process)
      .left
      .map(_ => "models: ambient endpoint override refused")
    result <- {
      val credentials = DefaultCredentialsProvider.builder().build()
      try {
        val b = BedrockRuntimeClient
          .builder()
          .region(Region.of(s.region))
          .credentialsProvider(credentials)
          .overrideConfiguration(AwsOverrides.of(clientSettings))
        clientSettings.endpointOverride.foreach(b.endpointOverride)
        val client = b.build()
        val calls = new BedrockCalls {
          def converse(r: ConverseRequest) = client.converse(r)
          def invokeModel(r: InvokeModelRequest) = client.invokeModel(r)
        }
        Right(
          new BedrockModels(
            settings,
            calls,
            () =>
              try client.close()
              finally credentials.close()
          )
        )
      } catch {
        case NonFatal(_) => credentials.close(); Left("models: Bedrock client construction failed")
      }
    }
  } yield result
}
