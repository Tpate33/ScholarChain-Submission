package scholarchain.services

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import scholarchain.core.{Canonical, StableId}

/** What a client may call (authorization). */
enum Scope(val tag: String) {

  /** POST /v1/query and the client's own jobs. */
  case Query extends Scope("query")

  /** Evidence, graph exploration and explanations. */
  case Read extends Scope("read")

  /** GET /metrics. */
  case Ops extends Scope("ops")
}

/**
 * One API client: its ID (a token, logged), the SHA-256 digest of its bearer API key (32 bytes; the
 * key itself is never held by the service) and its scopes.
 */
final class ApiClient private (val clientId: String, digest: Array[Byte], val scopes: Set[Scope]) {
  private[services] def keyDigest: Array[Byte] = digest.clone()
  override def toString: String = s"ApiClient($clientId, ${scopes.map(_.tag).toVector.sorted})"
}

object ApiClient {

  /** A client from its ID, the lowercase hex SHA-256 of its key, and its scopes. */
  def of(clientId: String, keySha256Hex: String, scopes: Set[Scope]): Either[String, ApiClient] =
    if (!StableId.isToken(clientId)) Left("clientId: not a token")
    else if (keySha256Hex == null || !Canonical.isHex64(keySha256Hex))
      Left("keySha256Hex: expected 64 lowercase hex characters")
    else if (scopes == null || scopes.isEmpty) Left("scopes: at least one")
    else
      Right(
        new ApiClient(
          clientId,
          keySha256Hex.grouped(2).map(h => Integer.parseInt(h, 16).toByte).toArray,
          scopes
        )
      )
}

/**
 * The port that supplies the configured clients and their key digests. Production source: an AWS
 * Secrets Manager secret referenced by ARN (deferred; no AWS call in Phase 10). Tests use a fake.
 */
trait ApiKeyDigestSource {
  def clients(): Either[String, Vector[ApiClient]]
}

/**
 * Bearer API-key authentication (course deployment). The presented key is hashed with SHA-256 and
 * compared with EVERY configured digest by `MessageDigest.isEqual` (constant time for equal
 * lengths), without an early exit, so the response time does not depend on which digest matched.
 * Neither the key nor its digest is ever logged or returned.
 */
final class ApiKeyAuthenticator(source: ApiKeyDigestSource, retryAfterSeconds: Int) {

  /** SE-P10-02 n4: the auth scheme is case-insensitive (RFC 9110 11.1); the key is not. */
  private val Bearer = "^(?i:bearer) ([A-Za-z0-9._~+/=-]{16,512})$".r

  private def unauthenticated(message: String) =
    ApiError(
      ErrorCode.Unauthenticated,
      message,
      headers = Vector("WWW-Authenticate" -> "Bearer")
    )

  def authenticate(authorization: Vector[String]): Either[ApiError, ApiClient] =
    authorization match {
      case Vector() => Left(unauthenticated("an Authorization: Bearer API key is required"))
      case Vector(Bearer(key)) =>
        source.clients() match {
          case Left(_) =>
            Left(
              ApiError(
                ErrorCode.NotReady,
                "the credential source is unavailable",
                headers = ApiError.retryAfter(retryAfterSeconds)
              )
            )
          case Right(clients) =>
            val presented =
              MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.US_ASCII))
            var found: Option[ApiClient] = None
            clients.foreach { c =>
              if (MessageDigest.isEqual(presented, c.keyDigest) && found.isEmpty) found = Some(c)
            }
            found.toRight(unauthenticated("the API key is not valid"))
        }
      case _ => Left(unauthenticated("the Authorization header is not one Bearer API key"))
    }

  def authorize(client: ApiClient, scope: Scope): Either[ApiError, ApiClient] =
    if (client.scopes.contains(scope)) Right(client)
    else Left(ApiError(ErrorCode.Forbidden, s"this client lacks the '${scope.tag}' scope"))
}
