package scholarchain.services

/** Evidence API, graph exploration API and explain API (SE-P10-01). */
class ReadApiSuite extends munit.FunSuite {
  import TestHttp.*
  import ServiceWorld.{ada, bob, cyd, eve, stanford}

  test("missing evidence: 404 not-found with trace ID; malformed evidence ID: 400") {
    val w = new ServiceWorld
    val s = w.service()
    try {
      val r = get(s.router, "/v1/evidence/ev:" + "0" * 64)
      assertEquals(r.status, 404)
      val j = json(r)
      assertEquals(ApiContract.check("error.response.json", j), Vector.empty)
      assertEquals(j("error")("code").str, "not-found")
      assertEquals(j("error")("traceId").str, r.header("X-Trace-Id").get)
      val bad = get(s.router, "/v1/evidence/not-an-id")
      assertEquals((bad.status, json(bad)("error")("code").str), (400, "invalid-parameter"))
      assertEquals(get(s.router, "/v1/evidence/ev:" + "0" * 64 + "?x=1").status, 400)
    } finally s.close()
  }

  test("graph node: researcher 200 (frozen example), unknown 404, other kinds 422, bad 400") {
    val w = new ServiceWorld
    val s = w.service()
    try {
      val r = get(s.router, s"/v1/graph/nodes/$ada")
      assertEquals(r.status, 200, r.bodyText)
      assertEquals(ApiContract.check("graph-node.response.json", json(r)), Vector.empty)
      assertEquals(json(r)("nodeId").str, ada)
      val ghost = ScholarIds.researcher("99/9")
      assertEquals(get(s.router, s"/v1/graph/nodes/$ghost").status, 404)
      val inst = get(s.router, s"/v1/graph/nodes/$stanford")
      assertEquals(
        (inst.status, json(inst)("error")("code").str),
        (422, "unsupported-graph-request")
      )
      assertEquals(get(s.router, "/v1/graph/nodes/nonsense").status, 400)
      // Percent-encoded IDs decode to the same node.
      assertEquals(get(s.router, s"/v1/graph/nodes/${ada.replace(":", "%3A")}").status, 200)
    } finally s.close()
  }

  test("graph neighbours: bounded, paginated by cursor, frozen example") {
    val w = new ServiceWorld
    val s = w.service()
    try {
      val first = get(s.router, s"/v1/graph/nodes/$bob/neighbors?edgeType=coauthored-with&limit=1")
      assertEquals(first.status, 200, first.bodyText)
      assertEquals(ApiContract.check("graph-neighbors.response.json", json(first)), Vector.empty)
      // Bob's coauthors in template order (joint publications desc): Ada (2), then Cyd (1).
      val p1 = json(first)
      assertEquals(p1("items").arr.map(_("nodeId").str).toVector, Vector(ada))
      val c1 = p1("page")("nextCursor").str
      val p2 = json(get(s.router, s"/v1/graph/nodes/$bob/neighbors?limit=1&cursor=$c1"))
      assertEquals(p2("items").arr.map(_("nodeId").str).toVector, Vector(cyd))
      assertEquals(p2("page")("nextCursor"), ujson.Null)
      // Another window has no edges; other edge types need no window.
      val other = json(get(s.router, s"/v1/graph/nodes/$bob/neighbors?window=2013-2017"))
      assertEquals(other("items").arr.size, 0)
      val aff = json(get(s.router, s"/v1/graph/nodes/$bob/neighbors?edgeType=affiliated-with"))
      assertEquals(aff("items")(0)("nodeId").str, stanford)
      assertEquals(aff("items")(0)("properties")("name").str, "Stanford University")
      assertEquals(aff("window"), ujson.Null)
      val authored = get(s.router, s"/v1/graph/nodes/$eve/neighbors?edgeType=authored")
      assertEquals(authored.status, 200)
    } finally s.close()
  }

  test("graph neighbours: depth, edge type, page size, window, cursor and result bounds") {
    val w = new ServiceWorld
    val s = w.service()
    def code(path: String) = {
      val r = get(s.router, path)
      (r.status, json(r)("error")("code").str)
    }
    try {
      val n = s"/v1/graph/nodes/$bob/neighbors"
      assertEquals(code(s"$n?depth=2"), (422, "unsupported-graph-request"))
      assertEquals(code(s"$n?depth=0"), (400, "invalid-parameter"))
      assertEquals(code(s"$n?edgeType=cites"), (422, "unsupported-graph-request"))
      assertEquals(code(s"$n?limit=21"), (400, "invalid-parameter"))
      assertEquals(code(s"$n?limit=abc"), (400, "invalid-parameter"))
      assertEquals(code(s"$n?window=2022"), (400, "invalid-parameter"))
      assertEquals(
        code(s"$n?edgeType=authored&window=2018-2022"),
        (422, "unsupported-graph-request")
      )
      assertEquals(code(s"$n?cursor=garbage"), (400, "invalid-parameter"))
      assertEquals(code(s"$n?sort=desc"), (400, "invalid-parameter"))
      assertEquals(code(s"$n?limit=1&limit=2"), (400, "invalid-parameter"))
      // A cursor is bound to its edge type and window.
      val c = json(get(s.router, s"$n?limit=1"))("page")("nextCursor").str
      assertEquals(code(s"$n?limit=1&cursor=$c&window=2013-2017"), (400, "invalid-parameter"))
      // A page beyond the template result bound is refused before any graph call.
      val far = GraphService.Cursor.encode(45, "coauthored-with|2018-2022")
      assertEquals(code(s"$n?limit=10&cursor=$far"), (422, "unsupported-graph-request"))
    } finally s.close()
  }

  test("graph edge types are configuration: a disabled type is 422") {
    val w = new ServiceWorld
    val s =
      w.service(config = ServiceWorld.config().copy(graphEdgeTypes = Vector("coauthored-with")))
    try {
      val r = get(s.router, s"/v1/graph/nodes/$bob/neighbors?edgeType=affiliated-with")
      assertEquals(r.status, 422)
    } finally s.close()
  }

  test("explain: unknown query 404, malformed ID 400") {
    val w = new ServiceWorld
    val s = w.service()
    try {
      assertEquals(get(s.router, "/v1/explain/query:never-asked").status, 404)
      assertEquals(get(s.router, "/v1/explain/never-asked").status, 400)
    } finally s.close()
  }

  test("a refused question is audited too: explain shows outcome refused and its reason") {
    val w = new ServiceWorld
    val s = w.service()
    try {
      val r = post(
        s.router,
        "/v1/query",
        ujson.Obj("question" -> "What is the weather like today?").render()
      )
      val q = json(r)("error")("queryId").str
      val e = get(s.router, s"/v1/explain/$q")
      assertEquals(e.status, 200, e.bodyText)
      assertEquals(json(e)("outcome").str, "refused")
      assertEquals(json(e)("reason").str, "no-allowlisted-operation")
      assertEquals(json(e)("plan"), ujson.Null)
      assert(!e.bodyText.contains("weather"))
    } finally s.close()
  }

  test("ServiceConfig: depth above the one-hop templates and inconsistent bounds are refused") {
    val c = ServiceWorld.config()
    assertEquals(ServiceConfig.problems(c), Vector.empty)
    assert(ServiceConfig.problems(c.copy(graphMaxDepth = 2)).exists(_.startsWith("graphMaxDepth")))
    assert(ServiceConfig.problems(c.copy(graphMaxPageSize = 50)).nonEmpty)
    assert(ServiceConfig.problems(c.copy(graphEdgeTypes = Vector("cites"))).nonEmpty)
    assert(ServiceConfig.problems(c.copy(syncTimeoutMillis = 0L)).nonEmpty)
    val w = new ServiceWorld
    assert(
      ServiceRuntime
        .build(
          w.runtime,
          w.ragWorkflow,
          new FakeKeys,
          c.copy(maxJobs = 0),
          w.access,
          new RecordingLog,
          new TestClock,
          new CountingIds
        )
        .isLeft
    )
  }
}

/** Researcher IDs of the fixture's form. */
object ScholarIds {
  def researcher(anchor: String): String =
    scholarchain.core.ResearcherId.scholarChain("v1", "dblp-pid", anchor).toOption.get
}
