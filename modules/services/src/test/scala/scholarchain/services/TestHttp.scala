package scholarchain.services

import java.nio.charset.StandardCharsets

/** Request builders and JSON helpers of the services tests (in-process router, no socket). */
object TestHttp {
  def auth(key: String): Vector[(String, String)] = Vector("Authorization" -> s"Bearer $key")

  def get(
      router: Router,
      path: String,
      key: Option[String] = Some(FakeKeys.FullKey),
      headers: Vector[(String, String)] = Vector.empty
  ): HttpResponse = {
    val (p, q) = path.split("\\?", 2).toVector match {
      case Vector(a, b) => (a, Some(b))
      case v => (v.head, None)
    }
    router.handle(
      HttpRequest(
        "GET",
        p,
        q,
        HttpRequest.headersOf(key.toVector.flatMap(auth) ++ headers),
        Array.emptyByteArray
      )
    )
  }

  def post(
      router: Router,
      path: String,
      body: String,
      key: Option[String] = Some(FakeKeys.FullKey),
      idempotencyKey: Option[String] = None,
      contentType: String = "application/json",
      headers: Vector[(String, String)] = Vector.empty
  ): HttpResponse =
    router.handle(
      HttpRequest(
        "POST",
        path,
        None,
        HttpRequest.headersOf(
          key.toVector.flatMap(auth) ++ Vector("Content-Type" -> contentType) ++
            idempotencyKey.toVector.map("Idempotency-Key" -> _) ++ headers
        ),
        body.getBytes(StandardCharsets.UTF_8)
      )
    )

  def json(r: HttpResponse): ujson.Value = ujson.read(r.bodyText)

  def resource(name: String): String = {
    val in = getClass.getResourceAsStream(s"/api-examples/$name")
    assert(in != null, s"missing example $name")
    try new String(in.readAllBytes(), StandardCharsets.UTF_8)
    finally in.close()
  }

  /** Waits (bounded, short polls) until `ok` holds; false on the deadline. */
  def eventually(timeoutMillis: Long)(ok: => Boolean): Boolean = {
    val deadline = System.nanoTime() + timeoutMillis * 1000000L
    var done = ok
    while (!done && System.nanoTime() < deadline) {
      Thread.sleep(5)
      done = ok
    }
    done
  }
}

/**
 * The frozen request/response examples (`src/test/resources/api-examples/`) and their matcher. A
 * pattern is JSON; the strings `<string>`, `<number>`, `<bool>`, `<object>`, `<array>`,
 * `<string-or-null>` and `<any>` match any value of that kind; every other value must be equal.
 * Objects must have exactly the pattern's keys; arrays must have the pattern's length.
 */
object ApiContract {
  def mismatches(pattern: ujson.Value, actual: ujson.Value, at: String = "$"): Vector[String] =
    (pattern, actual) match {
      case (ujson.Str("<any>"), _) => Vector.empty
      case (ujson.Str("<string>"), ujson.Str(_)) => Vector.empty
      case (ujson.Str("<string-or-null>"), ujson.Str(_) | ujson.Null) => Vector.empty
      case (ujson.Str("<number>"), ujson.Num(_)) => Vector.empty
      case (ujson.Str("<bool>"), ujson.Bool(_)) => Vector.empty
      case (ujson.Str("<object>"), _: ujson.Obj) => Vector.empty
      case (ujson.Str("<array>"), _: ujson.Arr) => Vector.empty
      case (p: ujson.Obj, a: ujson.Obj) =>
        val pk = p.value.keySet.toSet
        val ak = a.value.keySet.toSet
        (pk -- ak).toVector.sorted.map(k => s"$at.$k: missing") ++
          (ak -- pk).toVector.sorted.map(k => s"$at.$k: not in the example") ++
          pk.intersect(ak).toVector.sorted.flatMap(k => mismatches(p(k), a(k), s"$at.$k"))
      case (p: ujson.Arr, a: ujson.Arr) =>
        if (p.value.size != a.value.size)
          Vector(s"$at: ${a.value.size} items, expected ${p.value.size}")
        else p.value.indices.toVector.flatMap(i => mismatches(p(i), a(i), s"$at[$i]"))
      case (p, a) if p == a => Vector.empty
      case (p, a) => Vector(s"$at: ${a.render()} does not match ${p.render()}")
    }

  /** Mismatches of `actual` against the response example `name`. */
  def check(name: String, actual: ujson.Value): Vector[String] =
    mismatches(ujson.read(TestHttp.resource(name)), actual)
}
