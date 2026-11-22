package scholarchain.services

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** One named readiness probe; it must be cheap and must never throw secrets into the response. */
final case class ReadinessCheck(name: String, probe: () => Boolean)

/**
 * Liveness and readiness (role law 5, distinct semantics).
 *
 *   - `GET /healthz` (liveness): the process serves HTTP. Always `200 {"status":"ok"}` while the
 *     router runs; it checks no dependency, so a dependency outage never restarts the process.
 *   - `GET /readyz` (readiness): the process should receive traffic: every check passes (credential
 *     source loaded, no circuit open, executors running, not draining). `200` or `503`, with each
 *     check's name and `ok` / `failing` only: no configuration value, endpoint, key or error text.
 */
final class Health(checks: Vector[ReadinessCheck]) {
  def live: Reply = Reply(200, ujson.Obj("status" -> "ok"))

  def ready: Reply = {
    val results = checks.map { c =>
      val ok =
        try c.probe()
        catch { case NonFatal(_) => false }
      c.name -> ok
    }
    val allOk = results.forall(_._2)
    Reply(
      if (allOk) 200 else 503,
      ujson.Obj(
        "status" -> (if (allOk) "ready" else "not-ready"),
        "checks" -> ujson.Obj.from(results.map { case (n, ok) =>
          n -> ujson.Str(if (ok) "ok" else "failing")
        })
      )
    )
  }
}

/** Request counters by route template and status (no IDs, no client names), Prometheus text. */
final class Metrics {
  private val requests = new ConcurrentHashMap[(String, Int), LongAdder]()

  def record(route: String, status: Int): Unit =
    requests.computeIfAbsent((route, status), _ => new LongAdder).increment()

  def count(route: String, status: Int): Long =
    Option(requests.get((route, status))).map(_.sum()).getOrElse(0L)

  def render(jobs: Map[String, Int], circuits: Vector[(String, String)]): String = {
    def esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
    val lines = Vector.newBuilder[String]
    lines += "# TYPE scholarchain_http_requests_total counter"
    requests.asScala.toVector
      .map { case ((r, s), n) => (r, s, n.sum()) }
      .sortBy(x => (x._1, x._2))
      .foreach { case (r, s, n) =>
        lines += s"""scholarchain_http_requests_total{route="${esc(r)}",status="$s"} $n"""
      }
    lines += "# TYPE scholarchain_jobs gauge"
    jobs.toVector.sortBy(_._1).foreach { case (state, n) =>
      lines += s"""scholarchain_jobs{state="${esc(state)}"} $n"""
    }
    lines += "# TYPE scholarchain_circuit_open gauge"
    circuits.foreach { case (name, state) =>
      val open = if (state == "open") 1 else 0
      lines += s"""scholarchain_circuit_open{dependency="${esc(name)}"} $open"""
    }
    lines.result().mkString("", "\n", "\n")
  }
}

/**
 * One access-log event. Only IDs, the route TEMPLATE (never the raw path), status, duration, the
 * client ID and the error code are recorded: never headers (so never the Authorization key), the
 * body, the question text or researcher names.
 */
final case class AccessEvent(
    requestId: String,
    traceId: String,
    method: String,
    route: String,
    status: Int,
    durationMillis: Long,
    clientId: Option[String],
    errorCode: Option[String]
) {
  def render: String =
    s"http method=$method route=$route status=$status durationMs=$durationMillis " +
      s"requestId=$requestId traceId=$traceId client=${clientId.getOrElse("-")} " +
      s"error=${errorCode.getOrElse("-")}"
}

/** The access-log port (production: SLF4J; tests: a recorder). */
trait AccessLog {
  def record(e: AccessEvent): Unit
}

object AccessLog {
  def slf4j(): AccessLog = new AccessLog {
    private val log = org.slf4j.LoggerFactory.getLogger("scholarchain.services.access")
    def record(e: AccessEvent): Unit =
      if (e.status >= 500) log.warn(e.render) else log.info(e.render)
  }
}
