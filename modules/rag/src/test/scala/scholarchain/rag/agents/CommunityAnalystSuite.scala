package scholarchain.rag.agents

import scholarchain.core.CollaborationMetric

/** RE-P9-01 stage 5: membership, size, cross-community edges, bridges; degree kept apart. */
class CommunityAnalystSuite extends munit.FunSuite {
  import AgentWorld.*

  private def view(w: AgentWorld, id: String): CommunityView =
    w.communities.view(id, window).fold(e => fail(e.message), identity)

  test("community view: stored membership, members, component, cross-community edges") {
    val w = new AgentWorld().standard()
    val v = view(w, bob)
    assertEquals(v.community.map(_._1.communityId), Some(c1))
    assertEquals(v.community.map(_._1.communitySize), Some(2L))
    assertEquals(v.community.map(_._2.runId), Some(run))
    assertEquals(v.members.toSet, Set(ada, bob))
    assert(!v.membersTruncated)
    assertEquals(v.component.map(_._1.componentId), Some(k1))
    assertEquals(
      v.crossCommunityEdges.map(e => (e.collaboratorId, e.collaboratorCommunityId)),
      Vector(cyd -> c2)
    )
    assertEquals(v.crossCommunityEdges.head.jointPublicationCount, 1L)
    assertEquals(v.neighbouringCommunities, Vector(NeighbouringCommunity(c2, Vector(cyd))))
    assertEquals(v.collaboratorsWithoutCommunity, Vector.empty)
    assertEquals(v.gaps, Vector.empty)
  }

  test("direct degree (C_r) and structural bridge importance are distinct fields") {
    val w = new AgentWorld().standard()
    val bobView = view(w, bob)
    val adaView = view(w, ada)
    assertEquals(
      bobView.directDegree.map(_.reading.metric),
      Some(CollaborationMetric.DistinctCoauthorCount)
    )
    assertEquals(bobView.directDegree.map(_.reading.value), Some(MetricValue.Count(2L)))
    assertEquals(bobView.structuralBridge.map(_.record.crossCommunityDegree), Some(1L))
    assertEquals(bobView.structuralBridge.map(_.record.score), Some(0.5))
    assertEquals(bobView.structuralBridge.map(_.source.role), Some(AnalysisRoles.BridgeMeasures))
    assertEquals(
      bobView.directDegree.map(_.reading.source.role),
      Some(AnalysisRoles.ResearcherMetrics)
    )
    // Ada: a direct coauthor, but no structural bridge importance
    assertEquals(adaView.directDegree.map(_.reading.value), Some(MetricValue.Count(1L)))
    assertEquals(adaView.structuralBridge.map(_.record.crossCommunityDegree), Some(0L))
    assertEquals(adaView.crossCommunityEdges, Vector.empty)
  }

  test("compare: same community and component by stored ID equality") {
    val w = new AgentWorld().standard()
    val same = w.communities.compare(ada, bob, window).fold(e => fail(e.message), identity)
    assertEquals((same.sameCommunity, same.sameComponent), (Some(true), Some(true)))
    val apart = w.communities.compare(ada, cyd, window).fold(e => fail(e.message), identity)
    assertEquals((apart.sameCommunity, apart.sameComponent), (Some(false), Some(true)))
    val far = w.communities.compare(ada, eve, window).fold(e => fail(e.message), identity)
    assertEquals((far.sameCommunity, far.sameComponent), (Some(false), Some(false)))
    val unknown = w.communities.compare(ada, ghost, window).fold(e => fail(e.message), identity)
    assertEquals((unknown.sameCommunity, unknown.sameComponent), (None, None))
    assert(unknown.b.gaps.nonEmpty)
  }

  test("bridge ranking: the stored bridge order, each entry with structural and direct values") {
    val w = new AgentWorld().standard()
    val r = w.communities.bridgeRanking(window, 2).fold(e => fail(e.message), identity).get
    assertEquals(r.entries.map(_.researcherId), Vector(bob, cyd))
    assertEquals(r.entries.map(_.rank), Vector(1, 2))
    assert(r.entries.forall(_.structuralBridge.record.crossCommunityDegree == 1L))
    assertEquals(
      r.entries.map(_.directDegree.map(_.reading.value)),
      Vector(Some(MetricValue.Count(2L)), Some(MetricValue.Count(2L)))
    )
    assert(r.truncated)
    assert(r.orderingRule.startsWith("crossCommunityDegree desc"))
  }

  test("a stored member list that omits the researcher is Corrupt") {
    val w = new AgentWorld().standard()
    // ghost's own row names c1, but c1's stored member list (complete: {Ada, Bob}) omits ghost
    w.put(
      w.community(ghost, c1, 2L, k1),
      AnalysisRoles.CommunityMemberships,
      ghost,
      scholarchain.core.SchemaVersions.CommunityMembership,
      group = None
    )
    assert(w.communities.view(ghost, window).left.exists {
      case AgentError.Corrupt(d) => d.contains("omits")
      case _ => false
    })
  }

  test("the cross-community listing is bounded and flags a cut") {
    val w = new AgentWorld(
      AgentWorld.defaultLimits(maxResults = 2, maxCandidates = 1, maxContextRows = 2)
    ).standard()
    val v = view(w, bob)
    assert(v.collaboratorsTruncated)
    assert(v.membersTruncated)
  }
}
