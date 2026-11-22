package scholarchain.tests.services

/**
 * TE-P11-02 T15 (Phase 11 category 7, empty and disconnected graphs) at the API: a singleton
 * researcher's neighbourhood, a path question across two components, and a world with no stored
 * data at all. Required: 200 with empty items or an abstention, never a 5xx; no shortest-path fact
 * is verified across components.
 */
class EmptyGraphAdversarialSuite extends munit.FunSuite {
  import Http.*

  test("T15 a singleton researcher (no coauthor edge): 200 node, 200 empty neighbourhood") {
    val w = new ApiWorld
    val s = w.service()
    try {
      val node = get(s.router, s"/v1/graph/nodes/${ApiWorld.eve}")
      assertEquals(node.status, 200, node.bodyText)
      val n = get(s.router, s"/v1/graph/nodes/${ApiWorld.eve}/neighbors")
      assertEquals(n.status, 200, n.bodyText)
      assertEquals(json(n)("items").arr.size, 0, n.bodyText)
      assertEquals(json(n)("page")("nextCursor"), ujson.Null)
      // Control: Ada has a neighbour, so the empty list above is not a broken route.
      val a = get(s.router, s"/v1/graph/nodes/${ApiWorld.ada}/neighbors")
      assertEquals(a.status, 200)
      assert(json(a)("items").arr.nonEmpty, a.bodyText)
    } finally s.close()
  }

  test("T15 a path question across two components never verifies a shortest path") {
    val w = new ApiWorld
    val s = w.service()
    try {
      val r = post(
        s.router,
        "/v1/query",
        query("What is the shortest path between Ada Lovelace and Eve Example?")
      )
      assertEquals(r.status, 200, r.bodyText)
      val j = json(r)
      println(s"TE-P11-02 T15 cross-component path: ${j("status").str}")
      if (j("status").str == "answered") {
        val texts = j("answer")("claims").arr.map(_("text").str)
        assert(!texts.exists(_.contains("shortest-path-hops")), texts.mkString("\n"))
        assert(!texts.exists(_.contains("path-found-hops")), texts.mkString("\n"))
        assert(
          texts.exists(t => t.contains("not-connected-within") || t.contains("path-inconclusive")),
          s"no negative path fact for a disconnected pair: ${texts.mkString("\n")}"
        )
      }
      // Control: a connected pair (Ada-Bob-Cyd-Dan, 3 hops) in the same world does verify one.
      val c = json(
        post(
          s.router,
          "/v1/query",
          query("What is the shortest path between Ada Lovelace and Dan Example?")
        )
      )
      assertEquals(c("status").str, "answered", c.render())
      assert(
        c("answer")("claims").arr.exists(_("text").str.contains("shortest-path-hops 3")),
        c("answer")("answerText").str
      )
    } finally s.close()
  }

  test("T15 a world with no stored data: abstentions and 404s, never a 5xx") {
    val w = new ApiWorld(empty = true)
    val s = w.service()
    try {
      Vector(
        "Who are the coauthors of Ada Lovelace?",
        "How many publications does Ada Lovelace have?",
        "What is the shortest path between Ada Lovelace and Eve Example?",
        "Who has the most distinct coauthors?",
        "Which researchers bridge communities?",
        "Explain the research on graph methods by Ada Lovelace"
      ).foreach { q =>
        val r = post(s.router, "/v1/query", query(q))
        assert(r.status < 500, s"$q: ${r.status} ${r.bodyText}")
        if (r.status == 200)
          assertNotEquals(json(r)("status").str, "answered", s"$q answered from an empty world")
        println(
          s"TE-P11-02 T15 empty world [$q]: ${r.status} ${json(r).obj.get("status").map(_.str).getOrElse(json(r)("error")("code").str)}"
        )
      }
      val node = get(s.router, s"/v1/graph/nodes/${ApiWorld.ada}")
      assertEquals(node.status, 404, node.bodyText)
      val nb = get(s.router, s"/v1/graph/nodes/${ApiWorld.ada}/neighbors")
      assert(nb.status == 404 || (nb.status == 200 && json(nb)("items").arr.isEmpty), nb.bodyText)
      assertEquals(get(s.router, "/v1/evidence/ev:" + "1" * 64).status, 404)
    } finally s.close()
  }
}
