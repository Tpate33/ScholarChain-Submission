package scholarchain.rag.agents

import scholarchain.core.{Canonical, CollaborationMetric, Json, ResearcherMetricsV2, SchemaVersions}
// TEST SCOPE ONLY: the loader's role map, to catch drift between it and rag's role vocabulary;
// rag production code never imports scholarchain.storage.load.
import scholarchain.storage.load.KeyValueLoad

/** RE-P9-01 stage 3: exact stored values with metric version and run ID; gaps, never estimates. */
class CollaborationMetricsSuite extends munit.FunSuite {
  import AgentWorld.*
  import CollaborationMetric.*

  private val all = Vector(
    PublicationCount,
    DistinctCoauthorCount,
    TotalCoauthorOccurrences,
    AverageCoauthorsPerPaper,
    MaximumCoauthorsOnOnePaper,
    WeightedDegree,
    ComponentId,
    ComponentSize,
    CommunityId,
    BridgeScore,
    CrossCommunityDegree,
    ParticipationCoefficient,
    Coreness
  )

  test("rag's role vocabulary and metric versions equal the loader's (drift check)") {
    val loader = KeyValueLoad.RoleOf.map(_.swap)
    CollaborationMetricsAgent.SchemaOf.foreach { case (role, schema) =>
      assertEquals(loader.get(role), Some(schema), role)
    }
    assertEquals(AnalysisRoles.all.toSet, CollaborationMetricsAgent.SchemaOf.keySet)
  }

  test("every per-researcher metric is the stored value, verbatim, with version and run") {
    val w = new AgentWorld().standard()
    val r = w.metricsAgent.metrics(bob, window, all).fold(e => fail(e.message), identity)
    assertEquals(r.gaps, Vector.empty)
    val byMetric = r.readings.map(x => x.metric -> x).toMap
    val stored = metrics(bob, 3, 2, 3, 1, resolvedIdentity(3))
    import MetricValue.*
    assertEquals(byMetric(PublicationCount).value, Count(stored.publicationCount))
    assertEquals(byMetric(DistinctCoauthorCount).value, Count(2L))
    assertEquals(byMetric(TotalCoauthorOccurrences).value, Count(3L))
    assertEquals(byMetric(AverageCoauthorsPerPaper).value, Real(stored.averageCoauthorsPerPaper))
    assertEquals(byMetric(MaximumCoauthorsOnOnePaper).value, Count(1L))
    assertEquals(byMetric(WeightedDegree).value, Count(3L))
    assertEquals(byMetric(ComponentId).value, Id(k1))
    assertEquals(byMetric(ComponentSize).value, Count(4L))
    assertEquals(byMetric(CommunityId).value, Id(c1))
    assertEquals(byMetric(BridgeScore).value, Real(0.5))
    assertEquals(byMetric(CrossCommunityDegree).value, Count(1L))
    assertEquals(byMetric(ParticipationCoefficient).value, Real(0.5))
    assertEquals(byMetric(Coreness).value, Count(1L))
    assertEquals(byMetric(BridgeScore).method, Some("sampled-betweenness"))
    assertEquals(byMetric(CommunityId).method, Some("label-propagation"))
    r.readings.foreach { x =>
      assertEquals(x.source.runId, run)
      assertEquals(x.source.policyVersion, Some(graphPolicy))
      assertEquals(x.source.window, windowKeyText)
      assertEquals(x.source.subjectId, bob)
    }
    val rm = byMetric(PublicationCount).source
    assertEquals(rm.recordSchemaVersion, SchemaVersions.ResearcherMetricsV2)
    assertEquals(rm.recordSha256, Canonical.sha256HexUtf8(Json.write(stored)))
    assertEquals(r.researcherMetrics.map(_._1), Some(stored))
  }

  test("absent rows and pair metrics are gaps; nothing is estimated") {
    val w = new AgentWorld().standard()
    val r = w.metricsAgent
      .metrics(
        eve,
        window,
        Vector(PublicationCount, BridgeScore, Coreness, JointPublicationCount, ShortestPathLength)
      )
      .fold(e => fail(e.message), identity)
    assertEquals(r.readings.map(_.metric), Vector(PublicationCount))
    assertEquals(
      r.gaps.map(_.metric),
      Vector(BridgeScore, Coreness, JointPublicationCount, ShortestPathLength)
    )
    val other = w.metricsAgent
      .metrics(ghost, window, Vector(PublicationCount))
      .fold(e => fail(e.message), identity)
    assertEquals(other.readings, Vector.empty)
    assertEquals(other.gaps.map(_.metric), Vector(PublicationCount))
  }

  test("only the requested roles are read") {
    val w = new AgentWorld(AgentWorld.defaultLimits(maxKeyValueCalls = 1)).standard()
    assert(w.metricsAgent.metrics(bob, window, Vector(PublicationCount, WeightedDegree)).isRight)
    assert(w.metricsAgent.metrics(bob, window, Vector(PublicationCount, Coreness)).left.exists {
      case AgentError.BudgetExceeded(_) => true
      case _ => false
    })
  }

  test("a row of another run, or a record of another subject, is Corrupt (fail closed)") {
    val w = new AgentWorld().standard()
    val foreign = metrics(dan, 1, 1, 1, 1, resolvedIdentity(2), runId = otherRun)
    w.row(
      AnalysisRoles.ResearcherMetrics,
      dan,
      Json.write(foreign),
      SchemaVersions.ResearcherMetricsV2,
      runId = otherRun,
      keyedSubject = Some(ghost)
    )
    assert(w.metricsAgent.metrics(ghost, window, Vector(PublicationCount)).left.exists {
      case AgentError.Corrupt(_) => true
      case _ => false
    })
    val misfiled = metrics(dan, 1, 1, 1, 1, resolvedIdentity(2))
    val w2 = new AgentWorld().standard()
    w2.row(
      AnalysisRoles.ResearcherMetrics,
      dan,
      Json.write(misfiled),
      SchemaVersions.ResearcherMetricsV2,
      keyedSubject = Some(ghost)
    )
    assert(w2.metricsAgent.metrics(ghost, window, Vector(PublicationCount)).left.exists {
      case AgentError.Corrupt(d) => d.contains("subject")
      case _ => false
    })
  }

  test("stored ranking by C_r: the loader's rank, the stored values, truncation flagged") {
    val w = new AgentWorld().standard()
    w.metricsAgent
      .rank(DistinctCoauthorCount, window, 3)
      .fold(e => fail(e.message), identity) match {
      case RankingOutcome.Ranked(r) =>
        assertEquals(r.role, AnalysisRoles.ResearcherMetrics)
        assertEquals(r.entries.map(_.rank), Vector(1, 2, 3))
        assertEquals(r.entries.map(_.subjectId).take(2), Vector(bob, cyd))
        assertEquals(
          r.entries.head.readings.find(_.metric == DistinctCoauthorCount).map(_.value),
          Some(MetricValue.Count(2L))
        )
        assert(r.truncated)
        assert(r.orderingRule.startsWith("distinctCoauthorCount desc"))
      case other => fail(s"expected Ranked, got $other")
    }
    w.metricsAgent
      .rank(DistinctCoauthorCount, window, 10)
      .fold(e => fail(e.message), identity) match {
      case RankingOutcome.Ranked(r) =>
        assertEquals(r.entries.size, 5)
        assert(!r.truncated)
      case other => fail(s"expected Ranked, got $other")
    }
  }

  test("metrics without a stored ranking abstain instead of being ranked here") {
    val w = new AgentWorld().standard()
    Vector(WeightedDegree, JointPublicationCount, MaximumCoauthorsOnOnePaper, BridgeScore).foreach {
      m =>
        assert(
          w.metricsAgent.rank(m, window, 3).exists {
            case RankingOutcome.NoStoredRanking(x, _) => x == m
            case _ => false
          },
          m.tag
        )
    }
    assert(w.metricsAgent.rank(DistinctCoauthorCount, window, 0).isLeft)
    assert(w.metricsAgent.rank(DistinctCoauthorCount, window, 51).isLeft)
  }

  test("a stored rank that is out of sequence is Corrupt") {
    val w = new AgentWorld()
    w.put(
      metrics(ada, 2, 1, 2, 1, resolvedIdentity(2)),
      AnalysisRoles.ResearcherMetrics,
      ada,
      SchemaVersions.ResearcherMetricsV2,
      rank = Some(2)
    )
    assert(w.metricsAgent.rank(DistinctCoauthorCount, window, 5).left.exists {
      case AgentError.Corrupt(_) => true
      case _ => false
    })
  }

  test("requests are validated") {
    val w = new AgentWorld().standard()
    assert(w.metricsAgent.metrics("researcher:bad", window, Vector(PublicationCount)).isLeft)
    assert(w.metricsAgent.metrics(bob, window, Vector.empty).isLeft)
    assert(
      w.metricsAgent
        .metrics(bob, scholarchain.core.TimeWindow(2022, 2018), Vector(PublicationCount))
        .isLeft
    )
    // the stored record returned with the readings is the decoded stored record itself
    val r = w.metricsAgent.metrics(eve, window, Vector(PublicationCount)).toOption.get
    val stored: ResearcherMetricsV2 = metrics(eve, 1, 0, 0, 0, singletonIdentity)
    assertEquals(r.researcherMetrics.map(_._1), Some(stored))
  }
}
