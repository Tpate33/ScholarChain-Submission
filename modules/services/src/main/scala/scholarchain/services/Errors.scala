package scholarchain.services

/**
 * The typed error model (role law 3). Every error response has the body
 * `{"error":{"code":<tag>,"message":<text>,"requestId":<id>,"traceId":<id>, ...extra}}`. Messages
 * are fixed or built from IDs, bounds and tags only: never a secret, a credential, a stored text, a
 * question or a downstream error detail.
 */
enum ErrorCode(val status: Int, val tag: String) {
  case InvalidJson extends ErrorCode(400, "invalid-json")
  case InvalidRequest extends ErrorCode(400, "invalid-request")
  case InvalidParameter extends ErrorCode(400, "invalid-parameter")
  case BodyTooLarge extends ErrorCode(400, "body-too-large")
  case UnsupportedMediaType extends ErrorCode(400, "unsupported-media-type")
  case Unauthenticated extends ErrorCode(401, "unauthenticated")
  case Forbidden extends ErrorCode(403, "forbidden")
  case NotFound extends ErrorCode(404, "not-found")
  case IdempotencyKeyReused extends ErrorCode(409, "idempotency-key-reused")
  case IdempotencyInProgress extends ErrorCode(409, "idempotency-in-progress")
  case JobNotFinished extends ErrorCode(409, "job-not-finished")
  case UnsupportedConstraint extends ErrorCode(422, "unsupported-constraint")
  case UnsupportedQuery extends ErrorCode(422, "unsupported-query")
  case UnsupportedGraphRequest extends ErrorCode(422, "unsupported-graph-request")
  case TooManyRequests extends ErrorCode(429, "too-many-requests")
  case DownstreamFailure extends ErrorCode(502, "downstream-failure")
  case NotReady extends ErrorCode(503, "not-ready")
  case CircuitOpen extends ErrorCode(503, "circuit-open")
  case Overloaded extends ErrorCode(503, "overloaded")
  case Timeout extends ErrorCode(504, "timeout")
  case Internal extends ErrorCode(500, "internal")

  /**
   * A 5xx or 429: the outcome may differ on a retry, so it is never kept as an idempotent result.
   */
  def retryable: Boolean = status >= 500 || status == 429
}

/** One typed error, before the request and trace IDs are attached by the router. */
final case class ApiError(
    code: ErrorCode,
    message: String,
    extra: Vector[(String, ujson.Value)] = Vector.empty,
    headers: Vector[(String, String)] = Vector.empty
)

object ApiError {

  /** The JSON body of `e` for one request. */
  def body(e: ApiError, requestId: String, traceId: String): ujson.Obj =
    ujson.Obj(
      "error" -> ujson.Obj.from(
        Vector[(String, ujson.Value)](
          "code" -> e.code.tag,
          "message" -> e.message,
          "requestId" -> requestId,
          "traceId" -> traceId
        ) ++ e.extra
      )
    )

  def retryAfter(seconds: Int): Vector[(String, String)] = Vector("Retry-After" -> seconds.toString)
}
