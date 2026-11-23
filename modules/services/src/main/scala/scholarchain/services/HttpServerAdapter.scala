package scholarchain.services

import java.io.{ByteArrayOutputStream, InputStream}
import java.net.InetSocketAddress
import java.util.concurrent.{RejectedExecutionHandler, ThreadPoolExecutor, TimeUnit}

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import com.sun.net.httpserver.{HttpExchange, HttpServer}

/**
 * The HTTP transport: the JDK's built-in `com.sun.net.httpserver` (no extra dependency) in front of
 * the pure [[Router]]. It reads at most `maxBodyBytes + 1` body bytes (more marks the request
 * `bodyTooLarge`), passes raw path and query to the router, and writes the router's response. Host
 * and port come from configuration (port 0 binds an ephemeral port).
 *
 * SE-P10-03 N2, admission control: exchanges run on a bounded worker pool (`workers` threads,
 * `queueCapacity` waiting). When it is full, the JDK dispatcher thread never runs the exchange
 * itself (no CallerRunsPolicy: that blocked accepting and serving for every client). The exchange
 * is handed to a separate one-thread admission pool that answers at once without reading the body
 * or calling a service: `503 overloaded` with request and trace IDs, except `/healthz` and
 * `/readyz`, which it serves normally (they call no dependency). If the admission pool is full too
 * (`overflowCapacity`), the JDK closes that connection (residual: only under extreme overload).
 *
 * Residuals (JDK internals, not verified from this repository; SE-P10-04 records them, not chased):
 *   - U2: the JDK server API has no per-exchange read deadline; a slow body upload holds one
 *     worker. The deployment can bound it with the JDK's `sun.net.httpserver.maxReqTime` and
 *     `maxRspTime` system properties (names unverified on this JDK).
 *   - U-1: when the admission queue is full too, the AbortPolicy rejection reaches the JDK
 *     dispatcher; whether the connection is closed or leaked is JDK behaviour.
 *   - U-2: request headers are read on the executor thread, so a trickling client can stall the
 *     single admission thread (extends U2).
 *   - U-3: closing an exchange on the admission path may drain part of an unread body (JDK limit).
 */
final class HttpServerAdapter private (
    server: HttpServer,
    workers: ThreadPoolExecutor,
    admission: ThreadPoolExecutor
) {
  def port: Int = server.getAddress.getPort

  /** Exchanges waiting for a worker (tests). */
  private[services] def queued: Int = workers.getQueue.size

  /** Stops accepting, waits at most `graceSeconds` for exchanges, then stops the workers. */
  def stop(graceSeconds: Int): Unit = {
    server.stop(graceSeconds)
    Vector(workers, admission).foreach { p =>
      p.shutdown()
      if (!p.awaitTermination(graceSeconds.toLong, TimeUnit.SECONDS)) p.shutdownNow()
    }
  }
}

object HttpServerAdapter {

  final case class Settings(
      host: String,
      port: Int,
      backlog: Int,
      workers: Int,
      queueCapacity: Int,
      maxBodyBytes: Int,
      overflowCapacity: Int
  )

  /** Set while an exchange runs on the admission pool (the workers were full). */
  private val Admission = new ThreadLocal[java.lang.Boolean] {
    override def initialValue(): java.lang.Boolean = java.lang.Boolean.FALSE
  }

  /**
   * The largest body bound the reader accepts: core `ProductionBounds.MaxServerBodyBytes`
   * (`Int.MaxValue - 9`, CR-INT-04-6), so the buffer of `max + 1` bytes stays an array a JVM can
   * allocate. [[start]] refuses a larger `maxBodyBytes`.
   */
  val MaxBodyBytes: Int = scholarchain.core.config.ProductionBounds.MaxServerBodyBytes

  /**
   * Reads at most `max + 1` bytes of `in`: the body and whether it exceeded `max` (the body is then
   * cut to `max`). SV-INT-02 (CR-INT-04-6, services part): the counting is in Long, so no bound up
   * to `Int.MaxValue` wraps (`max + 1 - total` overflowed at `max = Int.MaxValue`, and the negative
   * length made every request with a body fail).
   */
  private[services] def readBounded(in: InputStream, max: Int): (Array[Byte], Boolean) = {
    val limit = math.max(0L, max.toLong) + 1L
    val out = new ByteArrayOutputStream()
    val buf = new Array[Byte](8192)
    var total = 0L
    var n = in.read(buf)
    while (n >= 0 && total < limit) {
      val keep = math.min(n.toLong, limit - total).toInt
      out.write(buf, 0, keep)
      total += keep
      n = if (total < limit) in.read(buf) else -1
    }
    val bytes = out.toByteArray
    if (bytes.length.toLong > max.toLong) (bytes.take(math.max(0, max)), true) else (bytes, false)
  }

  private def requestOf(ex: HttpExchange, body: Array[Byte], tooLarge: Boolean) = {
    val uri = ex.getRequestURI
    HttpRequest(
      ex.getRequestMethod,
      Option(uri.getRawPath).getOrElse("/"),
      Option(uri.getRawQuery),
      HttpRequest.headersOf(
        ex.getRequestHeaders.asScala.toVector.flatMap { case (k, vs) =>
          vs.asScala.toVector.map(k -> _)
        }
      ),
      body,
      tooLarge
    )
  }

  private def handle(
      router: Router,
      maxBodyBytes: Int,
      extraHeaders: Vector[(String, String)],
      ex: HttpExchange
  ): Unit =
    try {
      val res =
        if (Admission.get().booleanValue)
          router.overloaded(requestOf(ex, Array.emptyByteArray, tooLarge = false))
        else {
          val (body, tooLarge) = readBounded(ex.getRequestBody, maxBodyBytes)
          router.handle(requestOf(ex, body, tooLarge))
        }
      res.headers.foreach { case (k, v) => ex.getResponseHeaders.add(k, v) }
      extraHeaders.foreach { case (k, v) => ex.getResponseHeaders.add(k, v) }
      ex.sendResponseHeaders(res.status, if (res.body.isEmpty) -1L else res.body.length.toLong)
      if (res.body.nonEmpty) ex.getResponseBody.write(res.body)
    } catch {
      case NonFatal(_) => () // the exchange is closed below; the client sees a reset
    } finally ex.close()

  /**
   * Binds and starts the server. `extraHeaders` are added to EVERY response (admission and router
   * responses alike); production composition passes none. SV-INT-03a: the NON-PRODUCTION local demo
   * passes its profile header here.
   */
  def start(
      router: Router,
      s: Settings,
      extraHeaders: Vector[(String, String)] = Vector.empty
  ): Either[String, HttpServerAdapter] =
    if (s.host == null || s.host.isEmpty) Left("host: missing")
    else if (extraHeaders == null) Left("extraHeaders: null")
    else if (s.port < 0 || s.port > 65535) Left("port: must be 0..65535")
    else if (
      s.backlog < 1 || s.workers < 1 || s.queueCapacity < 1 || s.maxBodyBytes < 1 ||
      s.overflowCapacity < 1
    )
      Left("backlog, workers, queueCapacity, maxBodyBytes and overflowCapacity must be >= 1")
    else if (s.maxBodyBytes > MaxBodyBytes) Left(s"maxBodyBytes: at most $MaxBodyBytes")
    else
      try {
        val server = HttpServer.create(new InetSocketAddress(s.host, s.port), s.backlog)
        val admission = Pools.bounded("http-admission", 1, s.overflowCapacity)
        val toAdmission: RejectedExecutionHandler = (r: Runnable, _: ThreadPoolExecutor) =>
          admission.execute(() => {
            Admission.set(java.lang.Boolean.TRUE)
            try r.run()
            finally Admission.set(java.lang.Boolean.FALSE)
          })
        val workers = Pools.bounded("http", s.workers, s.queueCapacity, toAdmission)
        server.setExecutor(workers)
        server.createContext(
          "/",
          (ex: HttpExchange) => handle(router, s.maxBodyBytes, extraHeaders, ex)
        )
        server.start()
        Right(new HttpServerAdapter(server, workers, admission))
      } catch { case NonFatal(e) => Left(s"could not bind: ${e.getClass.getSimpleName}") }
}
