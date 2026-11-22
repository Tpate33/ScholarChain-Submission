package scholarchain.services

import java.nio.charset.StandardCharsets
import java.security.SecureRandom

/** Wall-clock and monotonic time of the service (injected, so tests are deterministic). */
trait ServiceClock {
  def nowMillis(): Long
  def monotonicNanos(): Long
}

object ServiceClock {
  val system: ServiceClock = new ServiceClock {
    def nowMillis(): Long = System.currentTimeMillis()
    def monotonicNanos(): Long = System.nanoTime()
  }
}

/**
 * Source of random tokens for request, trace, query and job IDs. Every token is 32 lowercase hex
 * characters (128 random bits in production), so it is a valid core query token
 * (`[A-Za-z0-9._-]{1,128}`) and a W3C-style trace ID.
 */
trait IdSource {
  def token(): String
}

object IdSource {
  def secure(): IdSource = new IdSource {
    private val random = new SecureRandom()
    def token(): String = {
      val b = new Array[Byte](16)
      random.synchronized(random.nextBytes(b))
      b.map(x => f"${x & 0xff}%02x").mkString
    }
  }
}

/**
 * How service code reaches the runtime stores. The in-memory fakes of `storage` are not
 * thread-safe, so local composition passes [[StoreAccess.serialized]]; an adapter that is safe for
 * concurrent use (the deferred AWS adapters) passes [[StoreAccess.concurrent]].
 *
 * SE-P10-03 N4/N5: `serialized` is one lock for every store call. A timed-out call that is still
 * running (cancellation only interrupts; a call waiting for this lock or ignoring interruption
 * keeps going) holds or waits for it, so reads queue behind a slow workflow. This is a recorded
 * local residual, removed by thread-safe adapters (storage-engineer).
 *
 * SE-P10-05 F4: query-ownership calls ([[QueryOwnership]], record and lookups) never go through
 * this access: the port is required to be safe for concurrent use, so recording a query's owner
 * never waits for another query's workflow holding the lock. Evidence, explain and graph STORE
 * reads still do (the residual above).
 */
trait StoreAccess {
  def apply[A](f: => A): A
}

object StoreAccess {
  def serialized(): StoreAccess = new StoreAccess {
    private val lock = new Object
    def apply[A](f: => A): A = lock.synchronized(f)
  }
  val concurrent: StoreAccess = new StoreAccess {
    def apply[A](f: => A): A = f
  }
}

/**
 * One HTTP request as the router sees it. `rawPath` and `rawQuery` are still percent-encoded;
 * header names are lower case. `bodyTooLarge` is set by the transport when the body exceeded the
 * configured bound (the body is then cut).
 */
final case class HttpRequest(
    method: String,
    rawPath: String,
    rawQuery: Option[String],
    headers: Map[String, Vector[String]],
    body: Array[Byte],
    bodyTooLarge: Boolean = false
) {
  def header(name: String): Vector[String] = headers.getOrElse(name.toLowerCase, Vector.empty)
}

object HttpRequest {

  /** Lower-cases header names and merges repeated ones. */
  def headersOf(pairs: Iterable[(String, String)]): Map[String, Vector[String]] =
    pairs.toVector.groupBy(_._1.toLowerCase).map { case (k, vs) => k -> vs.map(_._2) }
}

/** One HTTP response. */
final case class HttpResponse(status: Int, headers: Vector[(String, String)], body: Array[Byte]) {
  def bodyText: String = new String(body, StandardCharsets.UTF_8)
  def header(name: String): Option[String] =
    headers.collectFirst { case (k, v) if k.equalsIgnoreCase(name) => v }
}

/** A successful service result: status, JSON body and extra headers. */
final case class Reply(
    status: Int,
    body: ujson.Value,
    headers: Vector[(String, String)] = Vector.empty
)

/** Small JSON helpers shared by the views. */
private[services] object Js {
  def opt(v: Option[ujson.Value]): ujson.Value = v.getOrElse(ujson.Null)
  def str(v: Option[String]): ujson.Value = opt(v.map(ujson.Str(_)))
  def num(v: Option[Long]): ujson.Value = opt(v.map(n => ujson.Num(n.toDouble)))
  def strs(xs: Iterable[String]): ujson.Arr = ujson.Arr.from(xs.map(ujson.Str(_)))
  def counts(m: Map[String, Int]): ujson.Obj =
    ujson.Obj.from(m.toVector.sortBy(_._1).map { case (k, n) => k -> ujson.Num(n.toDouble) })
  def iso(millis: Long): String = java.time.Instant.ofEpochMilli(millis).toString
  def utf8Bytes(s: String): Int = s.getBytes(StandardCharsets.UTF_8).length

  /** `s` cut to at most `maxBytes` UTF-8 bytes on a code point boundary. */
  def cutUtf8(s: String, maxBytes: Int): String =
    if (utf8Bytes(s) <= maxBytes) s
    else {
      val out = new StringBuilder
      var used = 0
      var i = 0
      var stop = false
      while (i < s.length && !stop) {
        val cp = s.codePointAt(i)
        val n = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length
        if (used + n > maxBytes) stop = true
        else { out.appendAll(Character.toChars(cp)); used += n; i += Character.charCount(cp) }
      }
      out.toString
    }
}
