package scholarchain.core

import AnalysisValidation.selectShortestPaths

/**
 * The deterministic stored-path rule (DE-P6-02 repair 01, CR-P6-13 finding 4): for one pair and
 * window, the first K shortest paths in node-sequence order (lexicographic, element by element, by
 * unsigned UTF-8 bytes). Hand-checkable tie fixtures with the expected selections written out
 * literally, plus a cross-check against exhaustive enumeration. The exhaustive enumeration lives in
 * this test only (dev scale, bounded by maxHops); production code never enumerates every shortest
 * path.
 */
class EvidencePathSelectionSuite extends munit.FunSuite {

  private def orcid(s: String): String = Fixtures.ok(ResearcherId.orcid(s))

  // Checksum-valid synthetic ORCID iDs (ISO 7064 11-2 over 0..0k). Their researcher IDs sort in
  // the order n1 < n2 < n3 < n4 < n5 < n6 (unsigned UTF-8 bytes: "0001" < "001X" < "0028" < ...).
  private val n1 = orcid("0000-0000-0000-0001")
  private val n2 = orcid("0000-0000-0000-001X")
  private val n3 = orcid("0000-0000-0000-0028")
  private val n4 = orcid("0000-0000-0000-0036")
  private val n5 = orcid("0000-0000-0000-0044")
  private val n6 = orcid("0000-0000-0000-0052")

  private def graph(edges: (String, String)*): Map[String, Set[String]] =
    edges.foldLeft(Map.empty[String, Set[String]]) { case (m, (a, b)) =>
      m.updated(a, m.getOrElse(a, Set.empty) + b).updated(b, m.getOrElse(b, Set.empty) + a)
    }

  test("the synthetic node IDs are in the stated order") {
    val ns = Vector(n1, n2, n3, n4, n5, n6)
    assertEquals(ns.sortWith((a, b) => Canonical.compareUtf8(a, b) < 0), ns)
    assertEquals(n1, "researcher:orcid:0000-0000-0000-0001")
    assertEquals(n6, "researcher:orcid:0000-0000-0000-0052")
  }

  test("node order: element by element by UTF-8 bytes; first difference decides; prefix first") {
    import EvidencePath.compareNodes
    assert(compareNodes(Vector(n1, n2, n6), Vector(n1, n3, n6)) < 0)
    assert(compareNodes(Vector(n1, n3, n4), Vector(n1, n2, n6)) > 0, "the first difference decides")
    assert(compareNodes(Vector(n1, n2), Vector(n1, n2, n3)) < 0)
    assertEquals(compareNodes(Vector(n1, n2), Vector(n1, n2)), 0)
  }

  // Diamond with three middles: n1 - {n2, n3, n4} - n6. Edges given in reverse order on purpose.
  private val diamond =
    graph(n4 -> n6, n3 -> n6, n2 -> n6, n1 -> n4, n1 -> n3, n1 -> n2)

  test("diamond, three tied 2-hop paths: K = 1, 2, 3 and 5, written out") {
    assertEquals(selectShortestPaths(diamond, n1, n6, 3, 1), Vector(Vector(n1, n2, n6)))
    assertEquals(
      selectShortestPaths(diamond, n1, n6, 3, 2),
      Vector(Vector(n1, n2, n6), Vector(n1, n3, n6))
    )
    val all3 = Vector(Vector(n1, n2, n6), Vector(n1, n3, n6), Vector(n1, n4, n6))
    assertEquals(selectShortestPaths(diamond, n1, n6, 3, 3), all3)
    assertEquals(selectShortestPaths(diamond, n1, n6, 3, 5), all3, "fewer ties than K: all of them")
  }

  test("diamond: the selection does not depend on edge insertion order") {
    val forward = graph(n1 -> n2, n1 -> n3, n1 -> n4, n2 -> n6, n3 -> n6, n4 -> n6)
    for (k <- 1 to 4)
      assertEquals(
        selectShortestPaths(forward, n1, n6, 3, k),
        selectShortestPaths(diamond, n1, n6, 3, k)
      )
  }

  // Two levels: n1 - {n2, n3}; n2 - {n4, n5}; n3 - n4; {n4, n5} - n6; and n4 - n5, which only adds
  // longer walks (for example the 4-hop n1 - n3 - n4 - n5 - n6), never a shortest path.
  private val twoLevel =
    graph(n1 -> n2, n1 -> n3, n2 -> n4, n2 -> n5, n3 -> n4, n4 -> n6, n5 -> n6, n4 -> n5)

  test("two levels, three tied 3-hop paths: K = 1, 2 take both paths through n2 first") {
    assertEquals(selectShortestPaths(twoLevel, n1, n6, 3, 1), Vector(Vector(n1, n2, n4, n6)))
    assertEquals(
      selectShortestPaths(twoLevel, n1, n6, 3, 2),
      Vector(Vector(n1, n2, n4, n6), Vector(n1, n2, n5, n6))
    )
    assertEquals(
      selectShortestPaths(twoLevel, n1, n6, 3, 3),
      Vector(Vector(n1, n2, n4, n6), Vector(n1, n2, n5, n6), Vector(n1, n3, n4, n6))
    )
  }

  test("bounds and edge cases: beyond maxHops, unknown nodes, same node, K or maxHops below 1") {
    assertEquals(selectShortestPaths(twoLevel, n1, n6, 2, 3), Vector.empty, "distance 3 > 2")
    assertEquals(selectShortestPaths(diamond, n1, n5, 3, 1), Vector.empty, "n5 not in the graph")
    assertEquals(selectShortestPaths(diamond, n1, n1, 3, 1), Vector.empty)
    assertEquals(selectShortestPaths(diamond, n1, n6, 3, 0), Vector.empty)
    assertEquals(selectShortestPaths(diamond, n1, n6, 0, 1), Vector.empty)
    val split = graph(n1 -> n2, n3 -> n4)
    assertEquals(selectShortestPaths(split, n1, n4, 5, 1), Vector.empty, "not connected")
    assertEquals(selectShortestPaths(diamond, n1, n2, 3, 2), Vector(Vector(n1, n2)), "one hop")
  }

  /** Every shortest simple path of at most `maxHops` hops, sorted; exhaustive (dev scale only). */
  private def exhaustive(
      g: Map[String, Set[String]],
      s: String,
      t: String,
      maxHops: Int
  ): Vector[Vector[String]] = {
    def go(path: Vector[String]): Vector[Vector[String]] =
      if (path.last == t) Vector(path)
      else if (path.size > maxHops) Vector.empty
      else
        g.getOrElse(path.last, Set.empty)
          .toVector
          .filterNot(path.contains)
          .flatMap(n => go(path :+ n))
    if (s == t || !g.contains(s) || !g.contains(t)) Vector.empty
    else {
      val found = go(Vector(s))
      if (found.isEmpty) Vector.empty
      else {
        val d = found.map(_.size).min
        found.filter(_.size == d).sorted(using EvidencePath.nodeOrder)
      }
    }
  }

  test(
    "cross-check: the selection equals the first K of all shortest paths (fixtures and a grid)"
  ) {
    val rs = (10 until 19)
      .map(i => Fixtures.ok(ResearcherId.scholarChain("v1", "dblp-pid", s"00/00$i")))
      .toVector
    // A 3 x 3 grid: corner-to-corner pairs have 6 tied shortest paths of 4 hops.
    val gridEdges = for {
      r <- 0 until 3
      c <- 0 until 3
      (dr, dc) <- Vector((0, 1), (1, 0))
      if r + dr < 3 && c + dc < 3
    } yield rs(r * 3 + c) -> rs((r + dr) * 3 + c + dc)
    val grid = graph(gridEdges*)
    val graphs = Vector(diamond, twoLevel, grid)
    var checked = 0
    for {
      g <- graphs
      s <- g.keys.toVector
      t <- g.keys.toVector
      if Canonical.compareUtf8(s, t) < 0
      maxHops <- 1 to 4
      k <- 1 to 7
    } {
      assertEquals(
        selectShortestPaths(g, s, t, maxHops, k),
        exhaustive(g, s, t, maxHops).take(k),
        s"$s $t maxHops=$maxHops k=$k"
      )
      checked += 1
    }
    assert(checked > 1000, s"checked $checked cases")
    assertEquals(exhaustive(grid, rs(0), rs(8), 4).size, 6, "the grid really has 6 ties")
  }
}
