package scholarchain.services

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

import scala.util.control.NonFatal

/**
 * The deployable services (SE-P10-03 Part B; human decision: three services). `All` is the
 * single-process composition (every route), kept for tests and a one-process local run.
 */
enum ServiceKind(val tag: String) {
  case QueryJobs extends ServiceKind("query-jobs")
  case EvidenceExplain extends ServiceKind("evidence-explain")
  case Graph extends ServiceKind("graph")
  case All extends ServiceKind("all")

  def serves(s: ServiceKind): Boolean = this == All || this == s
}

/**
 * One route of the table: method, path template, required scope, allowed query parameters and the
 * service that serves it (`None`: an operations route served by every service).
 */
final case class RouteSpec(
    method: String,
    template: String,
    scope: Option[Scope],
    params: Set[String],
    service: Option[ServiceKind]
)

/**
 * The application services one router may call; a service a deployable does not run is `None`, and
 * its routes are not in that router's table.
 */
final case class RoutedServices(
    query: Option[QueryService],
    evidence: Option[EvidenceService],
    graph: Option[GraphService],
    explain: Option[ExplainService]
)

/**
 * The pure in-process router (`HttpRequest => HttpResponse`) of one [[ServiceKind]]: it serves
 * exactly that service's routes plus the operations routes; every other path is 404. Routes are
 * thin: they parse path and query parameters, authenticate and authorize, delegate to one
 * application service and map its result; no retrieval, graph or synthesis algorithm lives here.
 * Cross-cutting behaviour:
 *
 *   - request ID (always generated) and trace ID (a valid `X-Trace-Id` or W3C `traceparent` is
 *     propagated, otherwise generated), both returned as headers and in every error body;
 *   - `/v1` path versioning; JSON request and response bodies;
 *   - authentication (bearer API key) and authorization (scopes) before any service call;
 *   - the typed error model for every failure, including unexpected exceptions (`500 internal`;
 *     neither the exception message nor its stack reaches the response or the access log);
 *   - one access-log event and one metrics count per request.
 */
final class Router(
    kind: ServiceKind,
    services: RoutedServices,
    health: Health,
    metrics: Metrics,
    metricsText: () => String,
    auth: ApiKeyAuthenticator,
    accepting: AtomicBoolean,
    accessLog: AccessLog,
    config: ServiceConfig,
    ids: IdSource,
    clock: ServiceClock
) {
  import Router.*

  /** The routes this router serves. */
  val routes: Vector[RouteSpec] = routesFor(kind)

  private def json(status: Int, body: ujson.Value, extra: Vector[(String, String)]) =
    HttpResponse(
      status,
      Vector(
        "Content-Type" -> "application/json; charset=utf-8",
        "Cache-Control" -> "no-store",
        "X-Content-Type-Options" -> "nosniff"
      ) ++ extra,
      body.render().getBytes(StandardCharsets.UTF_8)
    )

  private def traceOf(req: HttpRequest): String =
    req.header("x-trace-id") match {
      case Vector(t) if TraceId.matches(t) => t
      case _ =>
        req.header("traceparent") match {
          case Vector(TraceParent(t)) if t != "0" * 32 => t
          case _ => ids.token()
        }
    }

  def handle(req: HttpRequest): HttpResponse =
    respond(req, c => dispatch(req, c))

  /**
   * SE-P10-03 N2: the response of the transport's admission control when the HTTP workers are
   * saturated: `503 overloaded` with request and trace IDs, decided without reading the body or
   * calling a service. Liveness and readiness are still answered (they call no dependency).
   */
  def overloaded(req: HttpRequest): HttpResponse =
    segments(req.rawPath).map(_.mkString("/", "/", "")) match {
      case Some(p) if req.method == "GET" && (p == Healthz || p == Readyz) => handle(req)
      case _ =>
        respond(
          req,
          _ =>
            (
              "overloaded",
              Left(
                ApiError(
                  ErrorCode.Overloaded,
                  "the service is at its request capacity",
                  headers = ApiError.retryAfter(config.retryAfterSeconds)
                )
              )
            )
        )
    }

  private def respond(
      req: HttpRequest,
      run: (ApiClient => Unit) => (String, Either[ApiError, Either[String, Reply]])
  ): HttpResponse = {
    val t0 = clock.monotonicNanos()
    val requestId = s"req-${ids.token()}"
    val traceId = traceOf(req)
    var client: Option[ApiClient] = None
    val (route, result) =
      try run(c => client = Some(c))
      catch {
        case NonFatal(_) =>
          ("unmatched", Left(ApiError(ErrorCode.Internal, "internal error")))
      }
    val ids2 = Vector("X-Request-Id" -> requestId, "X-Trace-Id" -> traceId)
    val response = result match {
      case Right(Left(text)) =>
        HttpResponse(
          200,
          Vector(
            "Content-Type" -> "text/plain; version=0.0.4; charset=utf-8",
            "Cache-Control" -> "no-store"
          ) ++ ids2,
          text.getBytes(StandardCharsets.UTF_8)
        )
      case Right(Right(r)) => json(r.status, r.body, r.headers ++ ids2)
      case Left(e) => json(e.code.status, ApiError.body(e, requestId, traceId), e.headers ++ ids2)
    }
    metrics.record(route, response.status)
    accessLog.record(
      AccessEvent(
        requestId,
        traceId,
        Router.safeMethod(req.method),
        route,
        response.status,
        (clock.monotonicNanos() - t0) / 1000000L,
        client.map(_.clientId),
        result.left.toOption.map(_.code.tag)
      )
    )
    response
  }

  private def notFound = ApiError(ErrorCode.NotFound, "no such route")

  /** (route template, result): Right(Left(text)) is a text body, Right(Right(reply)) JSON. */
  private def dispatch(
      req: HttpRequest,
      seen: ApiClient => Unit
  ): (String, Either[ApiError, Either[String, Reply]]) =
    segments(req.rawPath) match {
      case None =>
        ("unmatched", Left(ApiError(ErrorCode.InvalidParameter, "the path is not valid")))
      case Some(segs) =>
        matchRoute(routes, req.method, segs) match {
          case None => ("unmatched", Left(notFound))
          case Some((spec, pathParams)) =>
            val name = s"${spec.method} ${spec.template}"
            // SE-P10-02 n5: an unexpected exception is a 500 of the MATCHED route.
            def internal = Left(ApiError(ErrorCode.Internal, "internal error"))
            val out =
              try routed(req, spec, pathParams, seen)
              catch { case NonFatal(_) => internal }
            (name, out)
        }
    }

  private def routed(
      req: HttpRequest,
      spec: RouteSpec,
      pathParams: Vector[String],
      seen: ApiClient => Unit
  ): Either[ApiError, Either[String, Reply]] =
    for {
      params <- queryParams(req.rawQuery, spec.params)
      _ <-
        if (spec.template.startsWith("/v1/") && !accepting.get())
          Left(
            ApiError(
              ErrorCode.NotReady,
              "the service is not accepting requests",
              headers = ApiError.retryAfter(config.retryAfterSeconds)
            )
          )
        else Right(())
      client <- spec.scope match {
        case None => Right(None)
        case Some(scope) =>
          auth
            .authenticate(req.header("authorization"))
            .flatMap { c =>
              seen(c); auth.authorize(c, scope)
            }
            .map(Some(_))
      }
      r <- call(spec, pathParams, params, client, req)
    } yield r

  private def need(c: Option[ApiClient]): Either[ApiError, ApiClient] =
    c.toRight(ApiError(ErrorCode.Internal, "route without authentication"))

  private def running[A](s: Option[A]): Either[ApiError, A] = s.toRight(notFound)

  private def call(
      spec: RouteSpec,
      path: Vector[String],
      params: Map[String, String],
      client: Option[ApiClient],
      req: HttpRequest
  ): Either[ApiError, Either[String, Reply]] = {
    def reply(r: Either[ApiError, Reply]) = r.map(Right(_))
    spec.template match {
      case Healthz => Right(Right(health.live))
      case Readyz => Right(Right(health.ready))
      case MetricsPath =>
        if (config.metricsEnabled) Right(Left(metricsText())) else Left(notFound)
      case QueryPath =>
        for {
          q <- running(services.query)
          c <- need(client)
          _ <- body(req)
          r <- q.submit(c, req.body, req.header("idempotency-key"))
        } yield Right(r)
      case JobPath =>
        running(services.query).flatMap(q => need(client).flatMap(c => reply(q.job(c, path(0)))))
      case JobResultPath =>
        running(services.query).flatMap(q =>
          need(client).flatMap(c => reply(q.jobResult(c, path(0))))
        )
      case EvidencePath =>
        running(services.evidence).flatMap(e => need(client).flatMap(c => reply(e.get(c, path(0)))))
      case NodePath => running(services.graph).flatMap(g => reply(g.node(path(0))))
      case NeighborsPath =>
        running(services.graph).flatMap(g => reply(g.neighbors(path(0), params)))
      case ExplainPath =>
        running(services.explain).flatMap(x =>
          need(client).flatMap(c => reply(x.explain(c, path(0))))
        )
      case _ => Left(notFound)
    }
  }

  private def body(req: HttpRequest): Either[ApiError, Unit] =
    if (req.bodyTooLarge || req.body.length > config.maxBodyBytes)
      Left(ApiError(ErrorCode.BodyTooLarge, s"the body exceeds ${config.maxBodyBytes} bytes"))
    else
      req.header("content-type") match {
        case Vector(ct) if ct.toLowerCase.split(";").head.trim == "application/json" => Right(())
        case _ =>
          Left(ApiError(ErrorCode.UnsupportedMediaType, "Content-Type must be application/json"))
      }
}

object Router {
  val Healthz = "/healthz"
  val Readyz = "/readyz"
  val MetricsPath = "/metrics"
  val QueryPath = "/v1/query"
  val JobPath = "/v1/jobs/{jobId}"
  val JobResultPath = "/v1/jobs/{jobId}/result"
  val EvidencePath = "/v1/evidence/{evidenceId}"
  val NodePath = "/v1/graph/nodes/{nodeId}"
  val NeighborsPath = "/v1/graph/nodes/{nodeId}/neighbors"
  val ExplainPath = "/v1/explain/{queryId}"

  private val Q = Some(ServiceKind.QueryJobs)
  private val E = Some(ServiceKind.EvidenceExplain)
  private val G = Some(ServiceKind.Graph)

  /** The full route table (every service). */
  val Routes: Vector[RouteSpec] = Vector(
    RouteSpec("POST", QueryPath, Some(Scope.Query), Set.empty, Q),
    RouteSpec("GET", JobPath, Some(Scope.Query), Set.empty, Q),
    RouteSpec("GET", JobResultPath, Some(Scope.Query), Set.empty, Q),
    RouteSpec("GET", EvidencePath, Some(Scope.Read), Set.empty, E),
    RouteSpec("GET", ExplainPath, Some(Scope.Read), Set.empty, E),
    RouteSpec("GET", NodePath, Some(Scope.Read), Set.empty, G),
    RouteSpec("GET", NeighborsPath, Some(Scope.Read), GraphService.NeighborParams, G),
    RouteSpec("GET", Healthz, None, Set.empty, None),
    RouteSpec("GET", Readyz, None, Set.empty, None),
    RouteSpec("GET", MetricsPath, Some(Scope.Ops), Set.empty, None)
  )

  /** The routes one service serves: its own plus the operations routes. */
  def routesFor(kind: ServiceKind): Vector[RouteSpec] =
    Routes.filter(_.service.forall(kind.serves))

  private val Methods = Set("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")

  /**
   * SE-P10-02 n1: the request method as logged: one of the standard methods, else `OTHER` (a
   * client-chosen method can never inject text or line breaks into a log line). The other logged
   * request-derived fields are already bounded: the route is a template, the trace ID matches
   * `[A-Za-z0-9-]{8,64}` or is generated, the client ID is a configured token.
   */
  def safeMethod(m: String): String = if (m != null && Methods(m)) m else "OTHER"

  private val TraceId = "^[A-Za-z0-9-]{8,64}$".r
  private val TraceParent = "^00-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$".r

  private def decode(s: String): Option[String] =
    try Some(URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8))
    catch { case _: IllegalArgumentException => None }

  /** Decoded path segments; None for a path that is not `/...` or has a bad escape. */
  private[services] def segments(rawPath: String): Option[Vector[String]] =
    if (rawPath == null || !rawPath.startsWith("/")) None
    else {
      val raw = rawPath.substring(1).split("/", -1).toVector
      val decoded = raw.map(decode)
      if (decoded.exists(_.isEmpty)) None else Some(decoded.flatten)
    }

  /** The route of `routes` and its path parameters, or None (unknown path or method: 404). */
  private[services] def matchRoute(
      routes: Vector[RouteSpec],
      method: String,
      segs: Vector[String]
  ): Option[(RouteSpec, Vector[String])] =
    routes.iterator
      .filter(_.method == method)
      .flatMap { r =>
        val t = r.template.substring(1).split("/", -1).toVector
        if (t.size != segs.size) None
        else {
          val pairs = t.zip(segs)
          if (pairs.forall { case (a, b) => (a.startsWith("{") && b.nonEmpty) || a == b })
            Some(r -> pairs.collect { case (a, b) if a.startsWith("{") => b })
          else None
        }
      }
      .nextOption()

  /** Query parameters: each at most once, only the route's names. */
  private[services] def queryParams(
      raw: Option[String],
      allowed: Set[String]
  ): Either[ApiError, Map[String, String]] = {
    def bad(m: String) = Left(ApiError(ErrorCode.InvalidParameter, m))
    raw.filter(_.nonEmpty) match {
      case None => Right(Map.empty)
      case Some(q) =>
        val pairs = q.split("&", -1).toVector.map { p =>
          val i = p.indexOf('=')
          if (i <= 0) None
          else
            for {
              k <- decode(p.substring(0, i))
              v <- decode(p.substring(i + 1))
            } yield k -> v
        }
        if (pairs.exists(_.isEmpty)) bad("the query string is not well formed")
        else {
          val kvs = pairs.flatten
          val unknown = kvs.map(_._1).filterNot(allowed).distinct.sorted
          if (unknown.nonEmpty) bad(s"unknown query parameter(s): ${unknown.mkString(", ")}")
          else if (kvs.map(_._1).distinct.size != kvs.size) bad("a query parameter is repeated")
          else Right(kvs.toMap)
        }
    }
  }
}
