package scholarchain.services

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

import scala.util.control.NonFatal

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient
import software.amazon.awssdk.services.secretsmanager.model.{
  GetSecretValueRequest,
  GetSecretValueResponse
}
import upickle.default.ReadWriter
import upickle.implicits.allowUnknownKeys

import scholarchain.core.config.ApiKeySourceSettings
import scholarchain.storage.aws.{AmbientEndpoints, AmbientSettings, AwsClientSettings, AwsOverrides}

/** Injectable SDK boundary. Tests never construct a credential provider or contact AWS. */
trait SecretValueCalls {
  def getSecretValue(request: GetSecretValueRequest): GetSecretValueResponse
}

@allowUnknownKeys(false)
private[services] final case class DigestEntry(
    clientId: String,
    keySha256Hex: String,
    scopes: Vector[String]
) derives ReadWriter
@allowUnknownKeys(false)
private[services] final case class DigestDocument(
    schemaVersion: String,
    clients: Vector[DigestEntry]
) derives ReadWriter

/** Digest-only key source. An expired cache never serves stale keys after a failed refresh. */
final class SecretsManagerKeys private[services] (
    settings: ApiKeySourceSettings,
    calls: SecretValueCalls,
    release: () => Unit,
    nanoTime: () => Long
) extends ApiKeyDigestSource
    with AutoCloseable {
  private val closed = new AtomicBoolean(false)
  private var cache: Option[(Long, Vector[ApiClient])] = None

  def clients(): Either[String, Vector[ApiClient]] = synchronized {
    if (closed.get()) Left("apiKeys: source closed")
    else {
      val now = nanoTime()
      cache match {
        case Some((at, entries))
            if now - at >= 0 && (now - at) / 1000000 < settings.cacheTtlMillis =>
          Right(entries)
        case _ =>
          cache = None
          val refreshed =
            try {
              val response = calls.getSecretValue(
                GetSecretValueRequest
                  .builder()
                  .secretId(settings.secretId)
                  .versionStage("AWSCURRENT")
                  .build()
              )
              if (
                response == null || response.secretString() == null || response
                  .secretBinary() != null
              )
                Left("apiKeys: expected a string digest document")
              else SecretsManagerKeys.parse(response.secretString())
            } catch { case NonFatal(_) => Left("apiKeys: refresh unavailable") }
          refreshed.map { entries =>
            cache = Some(nanoTime() -> entries); entries
          }
      }
    }
  }

  def close(): Unit = synchronized {
    if (closed.compareAndSet(false, true)) { cache = None; release() }
  }
}

object SecretsManagerKeys {
  val SchemaVersion = "scholarchain.services.api-key-digests.v1"
  val MaxDocumentBytes = 65536
  val MaxClients = 256

  private[services] def parse(json: String): Either[String, Vector[ApiClient]] = {
    if (json == null || json.getBytes(StandardCharsets.UTF_8).length > MaxDocumentBytes)
      Left("apiKeys: digest document exceeds byte bound")
    else
      (try Right(upickle.default.read[DigestDocument](json))
      catch {
        case NonFatal(_) => Left("apiKeys: malformed digest document")
      }).flatMap { doc =>
        if (
          doc.schemaVersion != SchemaVersion || doc.clients.isEmpty || doc.clients.size > MaxClients
        )
          Left("apiKeys: invalid schema or client count")
        else if (
          doc.clients.map(_.clientId).distinct.size != doc.clients.size ||
          doc.clients.map(_.keySha256Hex).distinct.size != doc.clients.size
        )
          Left("apiKeys: duplicate client or digest")
        else
          doc.clients.foldLeft[Either[String, Vector[ApiClient]]](Right(Vector.empty)) {
            (acc, entry) =>
              for {
                xs <- acc
                scopes <- entry.scopes.foldLeft[Either[String, Set[Scope]]](Right(Set.empty)) {
                  (ss, tag) =>
                    for {
                      s <- ss;
                      scope <- Scope.values.find(_.tag == tag).toRight("apiKeys: invalid scope")
                    } yield s + scope
                }
                client <- ApiClient
                  .of(entry.clientId, entry.keySha256Hex, scopes)
                  .left
                  .map(_ => "apiKeys: invalid client digest")
              } yield xs :+ client
          }
      }
  }

  /** Building clients performs no secret fetch. Credentials resolve only on the first request. */
  def open(settings: ApiKeySourceSettings): Either[String, SecretsManagerKeys] = {
    val s = settings.client
    AwsClientSettings
      .of(
        s.region,
        s.endpointOverride,
        s.apiCallTimeoutMillis,
        s.apiCallAttemptTimeoutMillis,
        s.retryMaxAttempts
      )
      .left
      .map(_ => "apiKeys: invalid client settings")
      .flatMap { clientSettings =>
        if (settings.cacheTtlMillis < 1 || settings.secretId == null || settings.secretId.isEmpty)
          Left("apiKeys: invalid source settings")
        else
          AmbientEndpoints
            .check("secretsmanager", clientSettings.endpointOverride, AmbientSettings.process)
            .left
            .map(_ => "apiKeys: ambient endpoint override refused")
            .flatMap { _ =>
              val credentials = DefaultCredentialsProvider.builder().build()
              try {
                val builder = SecretsManagerClient
                  .builder()
                  .region(Region.of(s.region))
                  .credentialsProvider(credentials)
                  .overrideConfiguration(AwsOverrides.of(clientSettings))
                clientSettings.endpointOverride.foreach(builder.endpointOverride)
                val client = builder.build()
                val calls = new SecretValueCalls {
                  def getSecretValue(r: GetSecretValueRequest) = client.getSecretValue(r)
                }
                Right(
                  new SecretsManagerKeys(
                    settings,
                    calls,
                    () =>
                      try client.close()
                      finally credentials.close(),
                    () => System.nanoTime()
                  )
                )
              } catch {
                case NonFatal(_) => credentials.close(); Left("apiKeys: client construction failed")
              }
            }
      }
  }
}
