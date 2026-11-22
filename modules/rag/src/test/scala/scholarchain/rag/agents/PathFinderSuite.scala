package scholarchain.rag.agents

import scholarchain.core.{Canonical, Json, YearCount}
import scholarchain.storage.model.{GraphParamValue, GraphQuery}

/** RE-P9-01 stage 4: bounded templates, hop evidence, depth, result-count and time limits. */
class PathFinderSuite extends munit.FunSuite {
  import AgentWorld.*

  private def isLeft[A](e: Either[AgentError, A])(
      pf: PartialFunction[AgentError, Boolean]
  ): Boolean =
    e.left.exists(x => pf.applyOrElse(x, (_: AgentError) => false))

  test(
    "direct collaborators: strongest edge first, every hop with stored evidence and provenance"
  ) {
    val w = new AgentWorld()
    w.standard()
    val r = w.pathFinder().directCollaborators(bob, window, 10).fold(e => fail(e.message), identity)
    assertEquals(r.template, PathTemplate.DirectCollaborators)
    assertEquals(r.hops.map(_.toId), Vector(ada, cyd))
    val first = r.hops.head.evidence
    assertEquals(first.jointPublicationCount, 2L)
    assertEquals(first.supportingWorkIds, Vector(work(1), work(2)))
    assertEquals(first.publicationsByYear, Vector(YearCount(2019, 1), YearCount(2021, 1)))
    assertEquals((first.firstCollaborationYear, first.lastCollaborationYear), (2019, 2021))
    assertEquals(first.source.role, AnalysisRoles.CoauthorEdges)
    assertEquals(first.source.runId, run)
    assertEquals(first.source.subjectId, first.coauthorEdgeId)
    assert(!r.truncated)
    assertEquals(r.executedQueries, Vector("DirectCollaborators"))
  }

  test("direct collaborators: the result-count limit cuts and flags truncation") {
    val w = new AgentWorld()
    w.standard()
    val r = w.pathFinder().directCollaborators(bob, window, 1).fold(e => fail(e.message), identity)
    assertEquals(r.hops.map(_.toId), Vector(ada))
    assert(r.truncated)
    assert(w.pathFinder().directCollaborators(bob, window, 0).isLeft)
    assert(w.pathFinder().directCollaborators(bob, window, 51).isLeft)
  }

  test("direct edge between two named researchers, present or absent") {
    val w = new AgentWorld()
    w.standard()
    val yes = w.pathFinder().directEdge(ada, bob, window).fold(e => fail(e.message), identity)
    assertEquals(yes.hop.map(_.evidence.jointPublicationCount), Some(2L))
    val no = w.pathFinder().directEdge(ada, cyd, window).fold(e => fail(e.message), identity)
    assertEquals(no.hop, None)
    assert(w.pathFinder().directEdge(ada, ada, window).isLeft)
  }

  test("shortest path: hop count from the traversal, every hop's IDs, weight, works, years, run") {
    val w = new AgentWorld()
    w.standard()
    val r = w.pathFinder().shortestPath(ada, dan, window, 4).fold(e => fail(e.message), identity)
    r.outcome match {
      case PathOutcome.Found(nodes, hops) =>
        assertEquals(nodes, Vector(ada, bob, cyd, dan))
        assertEquals(hops.size, 3)
        assertEquals(hops.map(h => (h.fromId, h.toId)), Vector(ada -> bob, bob -> cyd, cyd -> dan))
        assertEquals(
          hops.map(_.evidence.supportingWorkIds),
          Vector(Vector(work(1), work(2)), Vector(work(3)), Vector(work(4)))
        )
        assert(hops.forall(_.evidence.source.runId == run))
      case other => fail(s"expected Found, got $other")
    }
    assert(r.executedQueries.forall(q => q == "DirectCollaborators" || q == "ResearcherById"))
  }

  test(
    "depth limit: a path longer than maxDepth is NotConnectedWithin; above the config, refused"
  ) {
    val w = new AgentWorld()
    w.standard()
    assertEquals(
      w.pathFinder().shortestPath(ada, dan, window, 2).map(_.outcome),
      Right(PathOutcome.NotConnectedWithin(2))
    )
    assert(isLeft(w.pathFinder().shortestPath(ada, dan, window, 5)) { case AgentError.Refused(_) =>
      true
    })
    assert(isLeft(w.pathFinder().shortestPath(ada, dan, window, 0)) { case AgentError.Refused(_) =>
      true
    })
    assertEquals(
      w.pathFinder().shortestPath(ada, eve, window, 4).map(_.outcome),
      Right(PathOutcome.NotConnectedWithin(4))
    )
  }

  test("result-count limit: a cut neighbour list makes 'not connected' Inconclusive") {
    val w = new AgentWorld(
      AgentWorld.defaultLimits(maxResults = 2, maxCandidates = 1, maxContextRows = 2)
    )
    w.standard()
    w.pathFinder().shortestPath(ada, eve, window, 4).map(_.outcome) match {
      case Right(PathOutcome.Inconclusive(why)) => assert(why.contains("result limit"))
      case other => fail(s"expected Inconclusive, got $other")
    }
    // RE-P9-04 (M1): a path found after a cut list still connects the two researchers, but it
    // is NOT proven shortest (bob's and cyd's lists reached the limit of 2), so it is never Found.
    w.pathFinder().shortestPath(ada, dan, window, 4).map(_.outcome) match {
      case Right(PathOutcome.FoundNotProvenShortest(nodes, hops, why)) =>
        assertEquals(nodes.size, 4)
        assertEquals(hops.size, 3)
        assert(why.contains("result limit"), why)
      case other => fail(s"expected FoundNotProvenShortest, got $other")
    }
  }

  /** CR-P9-01 M1 world: S strong to A and B, weak direct to T; A-T. */
  private def m1World(): AgentWorld = {
    val w = new AgentWorld(
      AgentWorld.defaultLimits(maxResults = 2, maxCandidates = 1, maxContextRows = 2)
    )
    Vector(ada, bob, cyd, dan).foreach(w.researcherVertex(_))
    w.coauthor(ada, bob, Vector(YearCount(2019, 3)), Vector(work(11), work(12), work(13)))
    w.coauthor(ada, cyd, Vector(YearCount(2020, 3)), Vector(work(21), work(22), work(23)))
    w.coauthor(ada, dan, Vector(YearCount(2021, 1)), Vector(work(31)))
    w.coauthor(bob, dan, Vector(YearCount(2021, 1)), Vector(work(41)))
    w
  }

  test(
    "M1 regression: a cut neighbour list hides a 1-hop path; the 2-hop path is not proven shortest"
  ) {
    val w = m1World()
    val r = w.pathFinder().shortestPath(ada, dan, window, 4).fold(e => fail(e.message), identity)
    r.outcome match {
      case PathOutcome.FoundNotProvenShortest(nodes, hops, _) =>
        assertEquals(nodes, Vector(ada, bob, dan))
        assertEquals(hops.size, 2)
      case other => fail(s"expected FoundNotProvenShortest (true shortest is 1 hop), got $other")
    }
    // Without the cut (limit 50) the true 1-hop shortest path is Found.
    val full = new AgentWorld()
    Vector(ada, bob, cyd, dan).foreach(full.researcherVertex(_))
    full.coauthor(ada, bob, Vector(YearCount(2019, 3)), Vector(work(11), work(12), work(13)))
    full.coauthor(ada, cyd, Vector(YearCount(2020, 3)), Vector(work(21), work(22), work(23)))
    full.coauthor(ada, dan, Vector(YearCount(2021, 1)), Vector(work(31)))
    full.coauthor(bob, dan, Vector(YearCount(2021, 1)), Vector(work(41)))
    full.pathFinder().shortestPath(ada, dan, window, 4).map(_.outcome) match {
      case Right(PathOutcome.Found(nodes, _)) => assertEquals(nodes, Vector(ada, dan))
      case other => fail(s"expected Found, got $other")
    }
  }

  test(
    "M1 regression: the truncation flag is in the path evidence; a shortest-path claim is never VERIFIED"
  ) {
    val w = m1World()
    val r = w.pathFinder().shortestPath(ada, dan, window, 4).fold(e => fail(e.message), identity)
    val limits = EvidenceLimits.of(100, 1000000, 5).toOption.get
    val built = new EvidenceBuilderAgent(serving, limits, "bucket")
      .build("query:m1-1", Vector(StageOutput.Path(0, r)))
      .fold(e => fail(e.message), identity)
    val traversal = built.bundle.items
      .find(_.provenance.sourceSchemaVersion == EvidenceSchemas.PathTraversal)
      .get
    val p = upickle.default.read[PathTraversalEvidence](traversal.text.get)
    assertEquals(p.outcome, PathTraversalEvidence.FoundNotProvenShortest)
    assert(p.searchTruncated)
    assertEquals(p.hopCount, Some(2))
    val facts = BundleFacts.of(built.bundle).fold(e => fail(e), identity)
    val sub = BundleFacts.path(ada, dan, windowKeyText)
    assert(!facts.exists(_.field == FactFields.ShortestPathHops), facts.toString)
    assert(
      facts.exists(f => f.subject == sub && f.field == FactFields.PathFoundHops && f.value == "2")
    )
    assert(facts.exists(f => f.subject == sub && f.field == FactFields.PathSearchTruncated))
    val shortest = ModelClaim(
      "path",
      s"In $windowKeyText, the shortest coauthorship path from $ada to $dan has 2 hops.",
      Vector(traversal.evidenceId),
      Vector(ModelAssertion(sub, FactFields.ShortestPathHops, "2")),
      None
    )
    val upper = ModelClaim(
      "path",
      s"In $windowKeyText, a coauthorship path of 2 hops connects $ada and $dan; the search was truncated, so a shorter path may exist.",
      Vector(traversal.evidenceId),
      Vector(ModelAssertion(sub, FactFields.PathFoundHops, "2")),
      None
    )
    // RE-P9-05 (F1): the model's "shortest" wording is withheld; the claim is VERIFIED only as
    // the rendering of its path-found-hops assertion, which never says "shortest".
    val sneaky = upper.copy(text =
      s"In $windowKeyText, the shortest coauthorship path of 2 hops connects $ada and $dan."
    )
    val report = new ClaimVerificationAgent(serving).verify(
      "query:m1-1",
      built.bundle,
      facts,
      SynthesisOutput(
        Prompts.OutputSchemaVersion,
        Vector(shortest, upper, sneaky),
        Vector.empty,
        Vector.empty
      ),
      Set(windowKeyText),
      "test-model",
      Prompts.AnswerSynthesisVersion
    )
    assertEquals(
      report.results.map(_.verdict),
      Vector(Verdict.Unsupported, Verdict.Verified, Verdict.Verified)
    )
    val rendered = report.results.drop(1).map(_.kept.map(_.text).getOrElse(""))
    assertEquals(rendered(0), rendered(1))
    assert(rendered.forall(t => !t.contains("shortest") && t.contains("upper bound")), rendered)
  }

  test("visit bound: the search stops and is Inconclusive") {
    val w = new AgentWorld(AgentWorld.defaultLimits(maxVisited = 2))
    w.standard()
    w.pathFinder().shortestPath(ada, dan, window, 4).map(_.outcome) match {
      case Right(PathOutcome.Inconclusive(why)) => assert(why.contains("visited"))
      case other => fail(s"expected Inconclusive, got $other")
    }
  }

  test("timeout: the time budget is checked before every store call") {
    val w = new AgentWorld(AgentWorld.defaultLimits(timeoutMillis = 3L))
    w.standard()
    val slow = new StepClock(1L)
    assert(isLeft(w.pathFinder(slow).shortestPath(ada, dan, window, 4)) {
      case AgentError.TimedOut(_) => true
    })
    assert(isLeft(w.pathFinder(new StepClock(5L)).directCollaborators(bob, window, 5)) {
      case AgentError.TimedOut(_) => true
    })
  }

  test("graph call budget: the search stops with BudgetExceeded") {
    val w = new AgentWorld(AgentWorld.defaultLimits(maxGraphCalls = 3))
    w.standard()
    assert(isLeft(w.pathFinder().shortestPath(ada, dan, window, 4)) {
      case AgentError.BudgetExceeded(_) => true
    })
  }

  test("a graph weight that disagrees with the stored edge record is Corrupt") {
    val w = new AgentWorld()
    researcherVertexes(w)
    w.coauthor(ada, bob, Vector(YearCount(2019, 1)), Vector(work(1)), graphWeight = Some(7L))
    assert(isLeft(w.pathFinder().directCollaborators(ada, window, 5)) {
      case AgentError.Corrupt(d) => d.contains("differs")
    })
  }

  test("an edge without a stored coauthor-edges record is Corrupt, never shown without evidence") {
    val w = new AgentWorld()
    researcherVertexes(w)
    val e = scholarchain.core.CoauthorEdgeV2
      .build(
        ada,
        bob,
        window,
        Vector(YearCount(2019, 1)),
        Vector(work(1)),
        lineage,
        graphPolicy,
        id => prov(id)
      )
      .toOption
      .get
    w.edge(
      scholarchain.core.EdgeLabel.CoauthoredWith,
      e.researcherA,
      e.researcherB,
      "firstCollaborationYear" -> scholarchain.storage.model.GraphValue.IntValue(2019L),
      "jointPublicationCount" -> scholarchain.storage.model.GraphValue.IntValue(1L),
      "lastCollaborationYear" -> scholarchain.storage.model.GraphValue.IntValue(2019L),
      "windowEndYear" -> scholarchain.storage.model.GraphValue.IntValue(2022L),
      "windowStartYear" -> scholarchain.storage.model.GraphValue.IntValue(2018L),
      "sourceEvidence" -> scholarchain.storage.model.GraphValue.Str(e.edgeId),
      "runId" -> scholarchain.storage.model.GraphValue.Str(run)
    )
    assert(isLeft(w.pathFinder().directCollaborators(ada, window, 5)) {
      case AgentError.Corrupt(d) => d.contains("no stored coauthor-edges record")
    })
    assertEquals(Canonical.sha256HexUtf8(Json.write(e)).length, 64)
  }

  test("only the closed templates run, each with typed ID, year and limit parameters") {
    val w = new AgentWorld()
    w.standard()
    w.pathFinder().shortestPath(ada, dan, window, 4)
    w.pathFinder().directCollaborators(bob, window, 10)
    w.pathFinder().directEdge(ada, bob, window)
    val allowed = PathTemplate.values.flatMap(_.queries).toSet + GraphQuery.ResearcherById
    assert(w.graph.seen.nonEmpty)
    w.graph.seen.foreach { q =>
      assert(allowed.contains(q.query), q.query.toString)
      q.parameters.foreach {
        case (_, GraphParamValue.Str(s)) =>
          assert(scholarchain.core.StableId.validateAny(s).isRight, s)
        case (_, GraphParamValue.IntValue(n)) => assert(n >= 1 && n <= 9999, n.toString)
      }
    }
  }

  test("unknown or invalid endpoints are refused") {
    val w = new AgentWorld()
    w.standard()
    assert(isLeft(w.pathFinder().shortestPath(ada, ghost, window, 3)) {
      case AgentError.Refused(_) => true
    })
    assert(isLeft(w.pathFinder().shortestPath(ada, ada, window, 3)) { case AgentError.Refused(_) =>
      true
    })
    assert(isLeft(w.pathFinder().shortestPath("MATCH (n) DETACH DELETE n", dan, window, 3)) {
      case AgentError.Refused(_) => true
    })
    assertEquals(w.graph.seen.size, 2) // only the ghost check reached the graph (ResearcherById x2)
  }

  private def researcherVertexes(w: AgentWorld): Unit =
    Vector(ada, bob).foreach(w.researcherVertex(_))
}
