package scholarchain.entityresolution.grouping

import scholarchain.core.*
import scholarchain.entityresolution.{MatchFeatures, ResearcherEndpoint}
import scholarchain.entityresolution.Samples.A
import scholarchain.entityresolution.decisions.DecisionExport

/**
 * ER-P6-05: the grouping layer er-grouping.v1 on SYNTHETIC fixtures ([[GroupingFixtures]]). Each
 * veto test also shows that plain transitive closure of the ACCEPTED decisions would give a
 * different result, so it fails without the rule. No gold file, label or held-out output is read.
 */
class GroupingSuite extends munit.FunSuite {
  import GroupingFixtures.*

  private val O1 = orcid(1)
  private val O2 = orcid(2)
  private val Exact = MatchDecisionPath.ExactId
  private val Det = MatchDecisionPath.DeterministicBibliographic
  private val Rejected = MatchOutcome.Rejected
  private val Unresolved = MatchOutcome.Unresolved

  private def refused(in: GroupingInputs): Vector[String] =
    Grouping.group(in).fold(identity, b => fail(s"expected a refusal, got ${b.runId}"))

  private def vetoed(b: GroupingBatch, in: GroupingInputs, d: D): Vector[String] = {
    val l = link(b, in, d)
    assertEquals(l.outcome, LinkOutcome.Vetoed, l.toString)
    l.reasonCodes
  }

  private def merged(b: GroupingBatch, in: GroupingInputs, d: D): Unit =
    assertNotEquals(link(b, in, d).outcome, LinkOutcome.Vetoed, link(b, in, d).toString)

  /** The vetoed decision is recorded on both endpoints, with the reason codes on both. */
  private def recordedOnBoth(b: GroupingBatch, in: GroupingInputs, d: D, codes: Vector[String]) = {
    val id = decisionIdOf(in, d)
    if (d.kind == MatchEntityKind.Researcher)
      Vector(d.a, d.b).foreach { e =>
        val r = researcher(b, e)
        assert(r.vetoedDecisionIds.contains(id), s"$e: ${r.vetoedDecisionIds}")
        codes.foreach(c => assert(r.reasonCodes.contains(c), s"$e: ${r.reasonCodes}"))
      }
    else
      Vector(d.a, d.b).foreach { e =>
        val x = work(b, e)
        assert(x.vetoedDecisionIds.contains(id), s"$e: ${x.vetoedDecisionIds}")
        codes.foreach(c => assert(x.reasonCodes.contains(c), s"$e: ${x.reasonCodes}"))
      }
  }

  // ---------------------------------------------------------------- 1. resource and refusals

  /** Three DBLP-free researchers on three works: m1 O1, m2 O1, m3 without identifiers. */
  private val n1 = dblpWork("conf/x/N1", Vector(A("Wei Wang", orcid = Some(O1))))
  private val n2 = crossrefWork("10.1000/n2", Vector(A("Wei Wang", orcid = Some(O1))))
  private val n3 = dblpWork("conf/x/N3", Vector(A("Wei Wang")))
  private val nWorks = Vector(n1, n2, n3)
  private def nDecisions(negative: MatchOutcome): Vector[D] = Vector(
    r(m(n1), m(n2), path = Exact, confidence = 0.99),
    r(m(n2), m(n3), confidence = 0.90),
    r(m(n1), m(n3), negative, confidence = 0.9)
  )

  test("1. the resource loads with exactly the implemented rules and fits er-policy.v8") {
    assertEquals(policy.groupingPolicyVersion, "er-grouping.v1")
    assertEquals(policy.supportedMatchPolicies, Vector("er-policy.v8"))
    assertEquals(policy.vetoRules.map(r => r.id -> r.kind).toSet, GroupingPolicy.ImplementedRules)
    assertEquals(GroupingPolicy.checkAgainst(policy, matchPolicy), Vector.empty)
    assertEquals(policy.anchorOrder, GroupingPolicy.AnchorOrder)
    // A resource without one implemented rule is refused by the loader.
    val text: String = ok(GroupingPolicy.resourceText)
    val json = ujson.read(text)
    json("vetoRules") = ujson.Arr(json("vetoRules").arr.filterNot(_("id").str == "ST").toSeq*)
    val dropped = GroupingPolicy.parse(ujson.write(json))
    assert(dropped.left.exists(_.exists(_.contains("vetoRules must be exactly"))), dropped)
    // A T1 or BD scheme list that differs from the match policy's is refused.
    val narrowed = policy.copy(vetoRules = policy.vetoRules.map { v =>
      if (v.id == "T1" && v.kind == "researcher") v.copy(schemes = Vector("orcid")) else v
    })
    assert(
      GroupingPolicy.checkAgainst(narrowed, matchPolicy).exists(_.contains("T1 researcher")),
      GroupingPolicy.checkAgainst(narrowed, matchPolicy)
    )
  }

  test("1. refusals: grouping-version mismatch, match-policy mismatch, unsupported policy") {
    val ds = nDecisions(Rejected)
    // The configured grouping policy differs from the resource.
    val v2 = settings.copy(groupingPolicyVersion = "er-grouping.v2")
    assert(
      refused(inputs(nWorks, Vector.empty, ds, s = v2))
        .exists(_.contains("differs from the grouping policy resource er-grouping.v1"))
    )
    // The configured match policy differs from the decisions' policyVersion.
    val v7 = settings.copy(matchPolicyVersion = "er-policy.v7")
    val mismatch = refused(inputs(nWorks, Vector.empty, ds, s = v7))
    assert(mismatch.exists(_.contains("the decision run's policyVersion er-policy.v8")), mismatch)
    assert(mismatch.exists(_.contains("the effective match policy er-policy.v8")), mismatch)
    // The decision run's policy is not supported by the resource.
    val unsupported = policy.copy(supportedMatchPolicies = Vector("er-policy.v9"))
    assert(
      refused(inputs(nWorks, Vector.empty, ds, g = unsupported))
        .exists(_.contains("match policy er-policy.v8 is not supported by er-grouping.v1"))
    )
    // A manifest parameter that differs from the verified inputs, an extra parameter, a missing
    // assignedAt, a clock-like assignedAt and a wrong stage.
    val base = inputs(nWorks, Vector.empty, ds)
    val dm = base.decisionManifest
    val cases = Vector(
      groupingManifest(dm, set = Map(Grouping.Params.DecisionRunId -> ("run:" + "1" * 64))) ->
        "the verified decision run's ID",
      groupingManifest(dm, set = Map(Grouping.Params.InputRunId -> ("run:" + "2" * 64))) ->
        "the curated run's ID",
      groupingManifest(dm, set = Map(Grouping.Params.IdentityVersion -> "v2")) ->
        "graph.identityVersion",
      groupingManifest(dm, set = Map("extra" -> "x")) -> "unknown parameter 'extra'",
      groupingManifest(dm, drop = Set(Grouping.Params.AssignedAt)) ->
        "parameter 'assignedAt' is required",
      groupingManifest(dm, at = "2026-11-15T12:00:00+01:00") -> "must be a UTC instant",
      groupingManifest(dm, stage = "er.other") -> "manifest stage is 'er.other'",
      groupingManifest(dm, digest = "0" * 64) -> "configDigest",
      groupingManifest(dm, inputs = snapshots.take(1)) -> "inputSnapshots"
    )
    cases.foreach { case (m, expected) =>
      val es = refused(base.copy(manifest = m))
      assert(es.exists(_.contains(expected)), s"$expected: $es")
    }
    // The decision run did not decide this curated run.
    val other = base.copy(curatedManifest = curatedManifest.copy(attempt = 2))
    assert(refused(other).exists(_.contains("the decision run decided curated run")))
    // The decisions do not decide exactly the curated run's candidates.
    val extraId = "mcand:" + "9" * 64
    val missing =
      base.copy(candidates =
        base.candidates :+ CuratedCandidate(extraId, curatedProvenance(extraId))
      )
    assert(refused(missing).exists(_.contains("1 undecided")))
    // A decision of another decision run, and of another match policy.
    val foreign = base.copy(decisions =
      base.decisions.updated(
        0,
        base
          .decisions(0)
          .copy(provenance =
            base
              .decisions(0)
              .provenance
              .copy(runId = "run:" + "3" * 64, policyVersion = Some("er-policy.v7"))
          )
      )
    )
    val fs = refused(foreign)
    assert(fs.exists(_.contains("belongs to run run:" + "3" * 64)), fs)
    assert(fs.exists(_.contains("has match policy er-policy.v7, not er-policy.v8")), fs)
    // A decision whose endpoint is not a member of the curated run.
    val orphan = base.copy(works = nWorks.take(2))
    assert(refused(orphan).exists(_.contains("is not a researcher of the curated run")))
  }

  // ---------------------------------------------------------------- CR-P6-07 repair

  test("CR-P6-07 finding 1: curated records of another run refuse the run (mixed runs)") {
    val other = "run:" + "4" * 64
    val prof = openAlexProfile("A31", "Ana Lima")
    val base = inputs(nWorks, Vector(prof), nDecisions(Rejected))
    assert(Grouping.group(base).isRight, "every record carries the recorded curated run ID")
    val expected = s"provenance.runId is not the curated run $curatedRunId"
    def refusedFor(clue: String, in: GroupingInputs, prefixes: String*): Unit = {
      val es = refused(in)
      prefixes.foreach(p =>
        assert(es.exists(e => e.startsWith(p) && e.endsWith(expected)), s"$clue $p: $es")
      )
    }
    // The curated manifest names run R; the works (and their mentions) come from another run.
    refusedFor(
      "works",
      base.copy(works = base.works.map(ofRun(_, other))),
      s"work ${n1.workId}",
      s"mention ${m(n1)}"
    )
    // Only the mentions of one work come from another run.
    val mixedMentions = base.works.updated(
      0,
      base
        .works(0)
        .copy(authors =
          base.works(0).authors.map(a => a.copy(provenance = a.provenance.copy(runId = other)))
        )
    )
    val mentionErrors = refused(base.copy(works = mixedMentions))
    assert(mentionErrors.exists(_.startsWith(s"mention ${m(n1)}")), mentionErrors)
    assert(!mentionErrors.exists(_.startsWith("work ")), mentionErrors)
    // The profiles come from another run.
    refusedFor(
      "profiles",
      base.copy(profiles = base.profiles.map(profileOfRun(_, other))),
      s"profile ${prof.profileId}"
    )
    // The candidates come from another run, or carry no provenance.
    val c0 = base.candidates(0)
    refusedFor(
      "candidates",
      base.copy(candidates =
        base.candidates.map(c => c.copy(provenance = curatedProvenance(c.candidateId, other)))
      ),
      s"candidate ${c0.candidateId}"
    )
    refusedFor(
      "null provenance",
      base.copy(candidates = base.candidates.updated(0, c0.copy(provenance = null))),
      s"candidate ${c0.candidateId}"
    )
    // The shared check is the decision exporter's.
    assertEquals(
      DecisionExport.foreignProvenance(curatedRunId, Vector("x" -> curatedProvenance("x", other))),
      Vector(s"x: $expected")
    )
  }

  test("CR-P6-07 nit 2: decision feature and blocking versions must match the policy and code") {
    val ds = nDecisions(Rejected)
    val feature = refused(
      inputs(nWorks, Vector.empty, ds, decisionSet = Map("featureVersion" -> "er-features.v1"))
    )
    assert(
      feature.exists(_.contains("the decision run's featureVersion er-features.v1 differs")),
      feature
    )
    val blocking = refused(
      inputs(nWorks, Vector.empty, ds, decisionSet = Map("blockingVersion" -> "er-blocking.v1"))
    )
    assert(
      blocking.exists(_.contains("the decision run's blockingVersion er-blocking.v1 differs")),
      blocking
    )
    // An effective policy whose versions differ from the decision run's (and the code's).
    val mp = matchPolicy.copy(featureVersion = "er-features.v9", blockingVersion = "er-blocking.v9")
    val policyMismatch = refused(inputs(nWorks, Vector.empty, ds, mp = mp))
    assert(
      policyMismatch.exists(_.contains("effective match policy's er-features.v9")),
      policyMismatch
    )
    assert(
      policyMismatch.exists(_.contains("effective match policy's er-blocking.v9")),
      policyMismatch
    )
  }

  test("CR-P6-07 nit 3: the group-size diagnostic says that unresolved members are excluded") {
    val b = group(inputs(nWorks, Vector.empty, nDecisions(Rejected)))
    assertEquals(researcher(b, m(n3)).status, AssignmentStatus.Unresolved)
    assert(
      b.diagnostics.contains(
        "unevaluated researcher groupSizes=2:1 " +
          "(identified groups only; unresolved members excluded: 1)"
      ),
      b.diagnostics.mkString("\n")
    )
    assert(b.diagnostics.forall(_.startsWith("unevaluated")))
  }

  // ---------------------------------------------------------------- 2. vetoes

  test("2. N1: a REJECTED cross pair vetoes the later link (closure would merge m1 and m3)") {
    Vector(Rejected, MatchOutcome.Conflict).foreach { negative =>
      val ds = nDecisions(negative)
      val in = inputs(nWorks, Vector.empty, ds)
      val b = group(in)
      val d23 = ds(1)
      assertEquals(vetoed(b, in, d23), Vector("grouping.veto.negative-decision"), negative.tag)
      recordedOnBoth(b, in, d23, Vector("grouping.veto.negative-decision"))
      assert(together(b, m(n1), m(n2)))
      assert(!together(b, m(n2), m(n3)) && !together(b, m(n1), m(n3)))
      assert(closureJoins(ds, m(n1), m(n3)))
      // m3 has a vetoed link and no identifier of its own: unresolved, no researcher ID.
      val r3 = researcher(b, m(n3))
      assertEquals(r3.status, AssignmentStatus.Unresolved)
      assert(r3.reasonCodes.contains("grouping.unresolved.vetoed-decision"), r3.reasonCodes)
      assertEquals(researcher(b, m(n1)).researcherId, Some(s"researcher:orcid:$O1"))
    }
    // An UNRESOLVED decision never vetoes (O7).
    val pending = nDecisions(Unresolved)
    val in = inputs(nWorks, Vector.empty, pending)
    val b = group(in)
    merged(b, in, pending(1))
    assert(together(b, m(n1), m(n3)))
  }

  test("2. T1 orcid and dblp-pid: a disagreeing cross pair vetoes; m3 keeps its own anchor") {
    val t1 = dblpWork("conf/x/T1", Vector(A("Wei Wang", orcid = Some(O1))))
    val t2 = crossrefWork("10.1000/t2", Vector(A("Wei Wang")))
    val t3 = dblpWork("conf/x/T3", Vector(A("Wei Wang", orcid = Some(O2))))
    val ds =
      Vector(r(m(t1), m(t2), path = Det, confidence = 0.95), r(m(t2), m(t3), confidence = 0.88))
    val in = inputs(Vector(t1, t2, t3), Vector.empty, ds)
    val b = group(in)
    assertEquals(vetoed(b, in, ds(1)), Vector("grouping.veto.t1-disagreement.orcid"))
    recordedOnBoth(b, in, ds(1), Vector("grouping.veto.t1-disagreement.orcid"))
    assert(closureJoins(ds, m(t1), m(t3)) && !together(b, m(t1), m(t3)))
    val r3 = researcher(b, m(t3))
    assertEquals(r3.status, AssignmentStatus.Singleton)
    assertEquals(r3.researcherId, Some(s"researcher:orcid:$O2"))
    assert(r3.reasonCodes.contains("grouping.singleton.t1-anchored"), r3.reasonCodes)
    // dblp-pid.
    val p1 = dblpWork("conf/x/P1", Vector(A("Wei Wang", sourceAuthorId = pid("p/1"))))
    val p3 = dblpWork("conf/x/P3", Vector(A("Wei Wang", sourceAuthorId = pid("p/2"))))
    val pds =
      Vector(r(m(p1), m(t2), path = Det, confidence = 0.95), r(m(t2), m(p3), confidence = 0.88))
    val pin = inputs(Vector(p1, t2, p3), Vector.empty, pds)
    val pb = group(pin)
    assertEquals(vetoed(pb, pin, pds(1)), Vector("grouping.veto.t1-disagreement.dblp-pid"))
    assert(closureJoins(pds, m(p1), m(p3)) && !together(pb, m(p1), m(p3)))
    assertEquals(researcher(pb, m(p3)).anchorNamespace, Some("dblp-pid"))
  }

  test("2. T1 doi and arxiv on works: different values veto, two versions of one arXiv ID do not") {
    val d1 = crossrefWork("10.1000/a", Vector(A("Wei Wang")))
    val d2 = dblpWork("conf/x/D2", Vector(A("Wei Wang")))
    val d3 = crossrefWork("10.1000/b", Vector(A("Wei Wang")))
    val ds = Vector(
      w(d1.workId, d2.workId, path = Det, confidence = 0.95),
      w(d2.workId, d3.workId, confidence = 0.9)
    )
    val in = inputs(Vector(d1, d2, d3), Vector.empty, ds)
    val b = group(in)
    assertEquals(vetoed(b, in, ds(1)), Vector("grouping.veto.t1-disagreement.doi"))
    recordedOnBoth(b, in, ds(1), Vector("grouping.veto.t1-disagreement.doi"))
    assert(closureJoins(ds, d1.workId, d3.workId) && !sameWork(b, d1.workId, d3.workId))
    def arxivCase(third: String) = {
      val a1 = openAlexWork("W21", Vector(A("Wei Wang")), arxiv = Some("2101.00001v1"))
      val a2 = dblpWork("conf/x/A22", Vector(A("Wei Wang")))
      val a3 = openAlexWork("W23", Vector(A("Wei Wang")), arxiv = Some(third))
      val xs = Vector(
        w(a1.workId, a2.workId, path = Det, confidence = 0.95),
        w(a2.workId, a3.workId, confidence = 0.9)
      )
      val xin = inputs(Vector(a1, a2, a3), Vector.empty, xs)
      (group(xin), xin, xs, a1.workId, a3.workId)
    }
    val (same, sin, sds, s1, s3) = arxivCase("2101.00001v2")
    merged(same, sin, sds(1))
    assert(sameWork(same, s1, s3), "v1 and v2 of one submission are one base ID")
    val (diff, din, dds, x1, x3) = arxivCase("2101.00002")
    assertEquals(vetoed(diff, din, dds(1)), Vector("grouping.veto.t1-disagreement.arxiv"))
    assert(closureJoins(dds, x1, x3) && !sameWork(diff, x1, x3))
  }

  test(
    "2. researcher BD: openalex-author disagreement vetoes unless the pair itself shares a T1 id"
  ) {
    def bd(first: A, second: A, third: A) = {
      val b1 = openAlexWork("W11", Vector(first))
      val b2 = dblpWork("conf/x/B12", Vector(second))
      val b3 = openAlexWork("W13", Vector(third))
      val ds = Vector(r(m(b1), m(b2), confidence = 0.93), r(m(b2), m(b3), confidence = 0.90))
      val in = inputs(Vector(b1, b2, b3), Vector.empty, ds)
      (group(in), in, ds, m(b1), m(b3))
    }
    val (b, in, ds, x, y) = bd(
      A("Wei Wang", sourceAuthorId = openAlexAuthor("A1")),
      A("Wei Wang"),
      A("Wei Wang", sourceAuthorId = openAlexAuthor("A2"))
    )
    assertEquals(
      vetoed(b, in, ds(1)),
      Vector("grouping.veto.blocking-disagreement.openalex-author")
    )
    recordedOnBoth(b, in, ds(1), Vector("grouping.veto.blocking-disagreement.openalex-author"))
    assert(closureJoins(ds, x, y) && !together(b, x, y))
    // The pair (m1, m3) shares an ORCID iD: the 6a override, no veto.
    val (ob, oin, ods, ox, oy) = bd(
      A("Wei Wang", orcid = Some(O1), sourceAuthorId = openAlexAuthor("A1")),
      A("Wei Wang"),
      A("Wei Wang", orcid = Some(O1), sourceAuthorId = openAlexAuthor("A2"))
    )
    merged(ob, oin, ods(1))
    assert(together(ob, ox, oy))
    // Only m2 and m3 share the ORCID iD: the cross pair (m1, m3) has no override (O3).
    val (pb, pin, pds, px, py) = bd(
      A("Wei Wang", sourceAuthorId = openAlexAuthor("A1")),
      A("Wei Wang", orcid = Some(O1)),
      A("Wei Wang", orcid = Some(O1), sourceAuthorId = openAlexAuthor("A2"))
    )
    assertEquals(
      vetoed(pb, pin, pds(1)),
      Vector("grouping.veto.blocking-disagreement.openalex-author")
    )
    assert(!together(pb, px, py))
  }

  test(
    "2. work BD: dblp-key disagreement vetoes; publication DOI and pure-preprint arXiv override"
  ) {
    val b1 = dblpWork("conf/x/A20", Vector(A("Wei Wang")))
    val b2 = crossrefWork("10.1000/c", Vector(A("Wei Wang")))
    val b3 = dblpWork("journals/y/A21", Vector(A("Wei Wang")), doi = Some("10.1000/c"))
    val ds = Vector(
      w(b2.workId, b3.workId, path = Exact, confidence = 0.99),
      w(b1.workId, b2.workId, path = Det, confidence = 0.95)
    )
    val in = inputs(Vector(b1, b2, b3), Vector.empty, ds)
    val b = group(in)
    assertEquals(vetoed(b, in, ds(1)), Vector("grouping.veto.blocking-disagreement.dblp-key"))
    recordedOnBoth(b, in, ds(1), Vector("grouping.veto.blocking-disagreement.dblp-key"))
    assert(closureJoins(ds, b1.workId, b3.workId) && !sameWork(b, b1.workId, b3.workId))
    // Both DBLP records carry the same publication DOI: overridden, one canonical work, the
    // smaller DBLP key (by bytes) is the representative.
    val o1 = dblpWork("conf/x/B20", Vector(A("Wei Wang")), doi = Some("10.1000/d"))
    val o2 = crossrefWork("10.1000/d", Vector(A("Wei Wang")))
    val o3 = dblpWork("journals/y/B21", Vector(A("Wei Wang")), doi = Some("10.1000/d"))
    val ods = Vector(
      w(o1.workId, o2.workId, path = Exact, confidence = 0.99),
      w(o2.workId, o3.workId, path = Exact, confidence = 0.99)
    )
    val oin = inputs(Vector(o1, o2, o3), Vector.empty, ods)
    val ob = group(oin)
    merged(ob, oin, ods(1))
    assertEquals(work(ob, o3.workId).canonicalWorkId, o1.workId)
    assert(work(ob, o1.workId).reasonCodes.contains("grouping.representative.utf8-tiebreak"))
    // Two pure preprints that share an arXiv base ID override the dblp-key disagreement; a mixed
    // record (arXiv plus a publication DOI) does not.
    def preprint(thirdDoi: Option[String]) = {
      val p1 = dblpWork(
        "journals/corr/abs-2101-00003",
        Vector(A("Wei Wang")),
        arxiv = Some("2101.00003v1")
      )
      val p2 = openAlexWork("W33", Vector(A("Wei Wang")), arxiv = Some("2101.00003"))
      val p3 =
        dblpWork("conf/y/P33", Vector(A("Wei Wang")), doi = thirdDoi, arxiv = Some("2101.00003v2"))
      val xs = Vector(
        w(p1.workId, p2.workId, path = Exact, confidence = 0.99),
        w(p2.workId, p3.workId, path = Exact, confidence = 0.98)
      )
      val xin = inputs(Vector(p1, p2, p3), Vector.empty, xs)
      (group(xin), xin, xs, p1.workId, p3.workId)
    }
    val (pb, pin, pds, px, py) = preprint(None)
    merged(pb, pin, pds(1))
    assert(sameWork(pb, px, py))
    val (mb, min, mds, mx, my) = preprint(Some("10.1000/p"))
    assertEquals(vetoed(mb, min, mds(1)), Vector("grouping.veto.blocking-disagreement.dblp-key"))
    assert(closureJoins(mds, mx, my) && !sameWork(mb, mx, my))
  }

  test("2. SW: two mentions of one work never share a researcher") {
    val s1 = dblpWork("conf/x/S20", Vector(A("Wei Wang"), A("Li Wang")))
    val s2 = crossrefWork("10.1000/s", Vector(A("Wei Wang")))
    val ds = Vector(r(m(s1, 0), m(s2), path = Det, confidence = 0.95), r(m(s2), m(s1, 1)))
    val in = inputs(Vector(s1, s2), Vector.empty, ds)
    val b = group(in)
    assertEquals(vetoed(b, in, ds(1)), Vector("grouping.veto.same-work"))
    recordedOnBoth(b, in, ds(1), Vector("grouping.veto.same-work"))
    assert(closureJoins(ds, m(s1, 0), m(s1, 1)) && !together(b, m(s1, 0), m(s1, 1)))
  }

  test("2. HN: different DBLP homonym numbers on one base name veto; every other case does not") {
    def hn(third: String, dblpThird: Boolean) = {
      val h1 = dblpWork("conf/x/H1", Vector(A("Wei Wang 0003")))
      val h2 = openAlexWork("W2", Vector(A("Wei Wang")))
      val h3 =
        if (dblpThird) dblpWork("conf/x/H3", Vector(A(third)))
        else openAlexWork("W3", Vector(A(third)))
      val ds = Vector(r(m(h1), m(h2), confidence = 0.91), r(m(h2), m(h3), confidence = 0.89))
      val in = inputs(Vector(h1, h2, h3), Vector.empty, ds)
      (group(in), in, ds, m(h1), m(h3))
    }
    val cases = Vector(
      ("Wei Wang 0001", true, true),
      ("Wang, Wei 0001", true, true), // comma form, same base name
      ("Wen Wang 0001", true, false), // different base name
      ("Wei Wang", true, false), // a number against no number
      ("Wei Wang 2019", false, false), // not DBLP-sourced
      ("Wei Wang 001", true, false) // a 3-digit token is not a homonym number
    )
    cases.foreach { case (name, dblp, veto) =>
      val (b, in, ds, x, y) = hn(name, dblp)
      if (veto) {
        assertEquals(vetoed(b, in, ds(1)), Vector("grouping.veto.homonym-number"), name)
        assert(closureJoins(ds, x, y) && !together(b, x, y), name)
      } else {
        merged(b, in, ds(1))
        assert(together(b, x, y), name)
      }
    }
  }

  test(
    "2. ST (backstop): a pure preprint is not chained to a published record (synthetic decisions)"
  ) {
    val s1 = openAlexWork("W41", Vector(A("Wei Wang")), arxiv = Some("2101.00004"))
    val s2 = openAlexWork(
      "W42",
      Vector(A("Wei Wang")),
      doi = Some("10.1000/p4"),
      arxiv = Some("2101.00004")
    )
    val s3 = crossrefWork("10.1000/p4", Vector(A("Wei Wang")))
    val ds = Vector(
      w(s2.workId, s3.workId, path = Exact, confidence = 0.99),
      w(s1.workId, s2.workId, confidence = 0.8) // a decision er-policy.v8 cannot produce
    )
    val in = inputs(Vector(s1, s2, s3), Vector.empty, ds)
    val b = group(in)
    assertEquals(vetoed(b, in, ds(1)), Vector("grouping.veto.publication-stage"))
    recordedOnBoth(b, in, ds(1), Vector("grouping.veto.publication-stage"))
    assert(closureJoins(ds, s1.workId, s3.workId) && !sameWork(b, s1.workId, s3.workId))
    assert(b.diagnostics.exists(_.startsWith("unevaluated ST=1 ")), b.diagnostics.mkString("\n"))
  }

  test("O11: every rule that fires is recorded, not only the first") {
    // m3 disagrees with m1 on ORCID iD and has a REJECTED decision with it.
    val e1 = dblpWork("conf/x/E1", Vector(A("Wei Wang", orcid = Some(O1))))
    val e2 = crossrefWork("10.1000/e2", Vector(A("Wei Wang")))
    val e3 = dblpWork("conf/x/E3", Vector(A("Wei Wang", orcid = Some(O2))))
    val ds = Vector(
      r(m(e1), m(e2), path = Det, confidence = 0.95),
      r(m(e2), m(e3)),
      r(m(e1), m(e3), MatchOutcome.Conflict, path = Exact, confidence = 0.99)
    )
    val in = inputs(Vector(e1, e2, e3), Vector.empty, ds)
    val b = group(in)
    assertEquals(
      vetoed(b, in, ds(1)),
      Vector("grouping.veto.negative-decision", "grouping.veto.t1-disagreement.orcid")
    )
  }

  // ---------------------------------------------------------------- 3. endpoint self-check

  test("3. endpoint self-check: a curated input changed after deciding refuses the run") {
    val t1 = dblpWork("conf/x/T1", Vector(A("Wei Wang", orcid = Some(O1))))
    val t2 = crossrefWork("10.1000/t2", Vector(A("Wei Wang", orcid = Some(O1))))
    val ds = Vector(r(m(t1), m(t2), path = Exact, confidence = 0.99))
    assert(Grouping.group(inputs(Vector(t1, t2), Vector.empty, ds)).isRight)
    // The decided m2 carried O1; the curated record now asserts O2 on the same mention.
    val changed = crossrefWork("10.1000/t2", Vector(A("Wei Wang", orcid = Some(O2))))
    val es = refused(inputs(Vector(t1, changed), Vector.empty, ds))
    assert(
      es.exists(e =>
        e.startsWith(GroupingPolicy.EndpointCheckCode) &&
          e.contains("grouping.veto.t1-disagreement.orcid")
      ),
      es
    )
  }

  // ---------------------------------------------------------------- 4. monotonicity

  test("4. monotonicity: a later link between the groups of a vetoed link is vetoed too") {
    val q1 = dblpWork("conf/x/Q1", Vector(A("Wei Wang", orcid = Some(O1))))
    val q2 = crossrefWork("10.1000/q2", Vector(A("Wei Wang")))
    val q3 = dblpWork("conf/x/Q3", Vector(A("Wei Wang", orcid = Some(O2))))
    val q4 = crossrefWork("10.1000/q4", Vector(A("Wei Wang")))
    val ds = Vector(
      r(m(q1), m(q2), path = Exact, confidence = 0.99),
      r(m(q3), m(q4), path = Exact, confidence = 0.99),
      r(m(q2), m(q3), confidence = 0.9),
      r(m(q1), m(q4), confidence = 0.8)
    )
    val in = inputs(Vector(q1, q2, q3, q4), Vector.empty, ds)
    val b = group(in)
    assertEquals(vetoed(b, in, ds(2)), Vector("grouping.veto.t1-disagreement.orcid"))
    assertEquals(vetoed(b, in, ds(3)), Vector("grouping.veto.t1-disagreement.orcid"))
    assert(together(b, m(q1), m(q2)) && together(b, m(q3), m(q4)))
    assert(!together(b, m(q2), m(q3)) && !together(b, m(q1), m(q4)))
    assert(closureJoins(ds, m(q1), m(q4)))
  }

  // ---------------------------------------------------------------- 5. determinism

  private def richInputs(attempt: Int = 1, at: String = assignedAt, reverse: Boolean = false) = {
    val works = nWorks ++ Vector(
      dblpWork("conf/x/A20", Vector(A("Wei Wang"), A("Li Wang"))),
      crossrefWork("10.1000/c", Vector(A("Wei Wang"), A("Li Wang"))),
      dblpWork("journals/y/A21", Vector(A("Wei Wang")), doi = Some("10.1000/c")),
      // works(6): a t1-anchored singleton (own pid, pending decision); works(7): no decision.
      dblpWork("conf/x/T9", Vector(A("Wei Wang", sourceAuthorId = pid("p/99")))),
      dblpWork("conf/x/L1", Vector(A("Lo Ne")))
    )
    val profiles = Vector(openAlexProfile("A9", "Wei Wang", Some(O1)))
    val ds = nDecisions(Rejected) ++ Vector(
      w(works(4).workId, works(5).workId, path = Exact, confidence = 0.99),
      w(works(3).workId, works(4).workId, path = Det, confidence = 0.95),
      r(m(works(3)), m(works(4)), path = Det, confidence = 0.95),
      r(m(works(3), 1), m(works(4), 1), path = Det, confidence = 0.95),
      r(m(n1), profiles(0).profileId, path = Exact, confidence = 0.99),
      r(m(works(5)), m(works(3)), Unresolved, confidence = 0.5),
      r(m(works(6)), m(n3), Unresolved, confidence = 0.5)
    )
    if (!reverse) inputs(works, profiles, ds, attempt, at)
    else inputs(works.reverse, profiles.reverse, ds.reverse, attempt, at)
  }

  test("5. determinism: input order, decision run ID and assignedAt change nothing else") {
    val a = group(richInputs())
    val shuffled = group(richInputs(reverse = true))
    assertEquals(shuffled.researchers, a.researchers)
    assertEquals(shuffled.works, a.works)
    assertEquals(shuffled.authorships, a.authorships)
    assertEquals(shuffled.teams, a.teams)
    assertEquals(shuffled.links, a.links)
    assertEquals(group(richInputs()), a, "a pure function of its inputs")
    // Another decision run: every decision ID changes; groups and IDs do not.
    val other = group(richInputs(attempt = 2))
    def identities(b: GroupingBatch) =
      b.researchers.map(r =>
        (r.memberId, r.status, r.researcherId, r.anchorNamespace, r.anchorValue)
      )
    assertEquals(identities(other), identities(a))
    assertEquals(
      other.works.map(w => w.workId -> w.canonicalWorkId),
      a.works.map(w => w.workId -> w.canonicalWorkId)
    )
    assertNotEquals(other.links.map(_.decisionId), a.links.map(_.decisionId))
    assertEquals(
      other.links.map(l => (l.sourceId, l.targetId, l.outcome)),
      a.links.map(l => (l.sourceId, l.targetId, l.outcome))
    )
    // Another assignedAt: only assignedAt changes (and the grouping run ID it is an input of).
    val later = group(richInputs(at = "2026-11-16T08:00:00Z"))
    assertNotEquals(later.runId, a.runId)
    def normal(b: GroupingBatch) = (
      b.researchers.map(r => r.copy(assignedAt = "", provenance = r.provenance.copy(runId = "x"))),
      b.works.map(w => w.copy(assignedAt = "", provenance = w.provenance.copy(runId = "x"))),
      b.authorships.map(x => x.copy(provenance = x.provenance.copy(runId = "x"))),
      b.teams.map(t => t.copy(provenance = t.provenance.copy(runId = "x")))
    )
    assertEquals(normal(later), normal(a))
    assert(later.researchers.forall(_.assignedAt == "2026-11-16T08:00:00Z"))
  }

  // ---------------------------------------------------------------- 6-7. anchors and statuses

  test(
    "6. anchors: orcid, dblp-pid, openalex-author, member, and two distinct values fall through"
  ) {
    val a1 = dblpWork("conf/x/G1", Vector(A("Ana Lima", sourceAuthorId = pid("p/7"))))
    val a2 = crossrefWork("10.1000/g2", Vector(A("Ana Lima")))
    val b1 = openAlexWork("W51", Vector(A("Bo Chen", sourceAuthorId = openAlexAuthor("A77"))))
    val b2 = dblpWork("conf/x/G4", Vector(A("Bo Chen")))
    val c1 = dblpWork("conf/x/G5", Vector(A("Cy Diaz")))
    val c2 = dblpWork("conf/x/G6", Vector(A("Cy Diaz")))
    val d1 = crossrefWork("10.1000/g7", Vector(A("Di Egan", orcid = Some(orcid(5)))))
    val prof = openAlexProfile("A88", "Di Egan", Some(orcid(5)))
      .copy(externalIds = Vector(ExternalId(ExternalIdScheme.Orcid, orcid(6))))
    val ds = Vector(
      r(m(a1), m(a2)),
      r(m(b1), m(b2)),
      r(m(c1), m(c2)),
      r(m(d1), prof.profileId, path = Exact, confidence = 0.99)
    )
    val b = group(inputs(Vector(a1, a2, b1, b2, c1, c2, d1), Vector(prof), ds))
    val ra = researcher(b, m(a2))
    assertEquals((ra.anchorNamespace, ra.anchorValue), (Some("dblp-pid"), Some("p/7")))
    assertEquals(ra.researcherId, Some(ok(ResearcherId.scholarChain("v1", "dblp-pid", "p/7"))))
    assertEquals(researcher(b, m(b2)).anchorNamespace, Some("openalex-author"))
    val rc = researcher(b, m(c2))
    assertEquals(rc.anchorNamespace, Some("member"))
    assertEquals(
      rc.anchorValue,
      Some(Vector(m(c1), m(c2)).sortWith(Canonical.compareUtf8(_, _) < 0).head)
    )
    // The profile asserts two ORCID iDs: the group has two distinct values, so it falls through
    // to its OpenAlex author ID (the profile's own).
    val rd = researcher(b, m(d1))
    assertEquals((rd.anchorNamespace, rd.anchorValue), (Some("openalex-author"), Some("A88")))
    assert(rd.reasonCodes.contains("grouping.anchor.openalex-author"))
    val ids = b.researchers.flatMap(_.researcherId).distinct
    assertEquals(ids.size, 4, "one researcher ID per group, all distinct")
  }

  test("6. collisions (C2, O6): a shared value anchors no group, an unresolved member included") {
    def run(pendingOnC5: Boolean) = {
      val g1 =
        dblpWork("conf/x/C1", Vector(A("Wei Wang", orcid = Some(O1), sourceAuthorId = pid("p/1"))))
      val g2 = crossrefWork("10.1000/c2", Vector(A("Wei Wang")))
      val g5 = crossrefWork("10.1000/c5", Vector(A("Wei Wang", orcid = Some(O1))))
      val g6 = dblpWork("conf/x/C6", Vector(A("Wu Wang")))
      val ds = Vector(r(m(g1), m(g2), path = Det, confidence = 0.95)) ++
        (if (pendingOnC5) Vector(r(m(g5), m(g6), Unresolved, confidence = 0.5)) else Vector.empty)
      (group(inputs(Vector(g1, g2, g5, g6), Vector.empty, ds)), m(g1), m(g5))
    }
    val (b, x, y) = run(pendingOnC5 = false)
    val rx = researcher(b, x)
    val ry = researcher(b, y)
    assertEquals(rx.anchorNamespace, Some("dblp-pid"))
    assertEquals(ry.anchorNamespace, Some("member"))
    assertEquals(ry.status, AssignmentStatus.Singleton)
    Vector(rx, ry).foreach(r =>
      assert(r.reasonCodes.contains("grouping.anchor-collision.orcid"), r)
    )
    assert(!b.researchers.exists(_.researcherId.contains(s"researcher:orcid:$O1")))
    assert(!together(b, x, y), "nothing is merged")
    // The colliding member is unresolved (pending decision, no usable T1 anchor): still a collider.
    val (u, ux, uy) = run(pendingOnC5 = true)
    assertEquals(researcher(u, uy).status, AssignmentStatus.Unresolved)
    assertEquals(researcher(u, ux).anchorNamespace, Some("dblp-pid"))
    assert(researcher(u, uy).reasonCodes.contains("grouping.anchor-collision.orcid"))
  }

  test("7. statuses: resolved, singleton (REJECTED only), t1-anchored, unresolved, profiles") {
    val s1 = dblpWork("conf/x/S1", Vector(A("Jie Chen", sourceAuthorId = pid("p/8"))))
    val s2 = dblpWork("conf/x/S2", Vector(A("Jie Chen")))
    val s3 = dblpWork("conf/x/S3", Vector(A("Jie Chen")))
    val s4 = dblpWork("conf/x/S4", Vector(A("Jie Chen", sourceAuthorId = pid("p/9"))))
    val s7 = openAlexWork("W8", Vector(A("J. Chen"), A("Ann Bell"), A("Bo Ross"), A("J. Chen")))
    val prof = openAlexProfile("A12", "Jie Chen")
    val ds = Vector(
      r(m(s1), m(s2), path = Det, confidence = 0.95), // resolved group G3
      r(prof.profileId, m(s1), confidence = 0.92), // a profile joins it
      r(m(s3), m(s2), Rejected, confidence = 0.9), // s3: REJECTED only
      r(m(s4), m(s2), Unresolved, confidence = 0.5), // s4: pending, own pid
      r(m(s7, 3), m(s1), Unresolved, confidence = 0.55) // example 4
    )
    val b = group(inputs(Vector(s1, s2, s3, s4, s7), Vector(prof), ds))
    assertEquals(researcher(b, m(s1)).status, AssignmentStatus.Resolved)
    assertEquals(researcher(b, prof.profileId).status, AssignmentStatus.Resolved)
    assertEquals(researcher(b, prof.profileId).groupSize, 3)
    val r3 = researcher(b, m(s3))
    assertEquals(r3.status, AssignmentStatus.Singleton)
    assertEquals(r3.anchorNamespace, Some("member"))
    assert(r3.reasonCodes.contains("grouping.singleton"))
    assertEquals(r3.rejectedDecisionIds.size, 1)
    val r4 = researcher(b, m(s4))
    assertEquals((r4.status, r4.anchorNamespace), (AssignmentStatus.Singleton, Some("dblp-pid")))
    assert(r4.reasonCodes.contains("grouping.singleton.t1-anchored"))
    val r7 = researcher(b, m(s7, 3))
    assertEquals(r7.status, AssignmentStatus.Unresolved)
    assertEquals(r7.researcherId, None)
    assertEquals(r7.pendingDecisionIds.size, 1)
    assert(r7.reasonCodes.contains("grouping.unresolved.pending-decision"))
    // The other mentions of W8 have no decision: singletons with their own member anchor.
    assertEquals(researcher(b, m(s7, 1)).status, AssignmentStatus.Singleton)
  }

  // ---------------------------------------------------------------- 8-9. works, authorships, teams

  test("8. works: example 5 is counted once from its DBLP representative; enrichment-only groups") {
    val names = Vector(A("Ana Lima"), A("Bo Chen"), A("Cy Diaz"))
    val e1 = dblpWork("conf/x/A20", names, doi = Some("10.1000/abc"))
    val e2 = crossrefWork("10.1000/abc", names)
    val e3 = openAlexWork("W1", names, doi = Some("10.1000/abc"))
    val e4 = openAlexWork("W2", names, arxiv = Some("2101.00009"))
    val f1 = crossrefWork("10.1000/e", Vector(A("Di Egan")))
    val f2 = openAlexWork("W5", Vector(A("Di Egan")), doi = Some("10.1000/e"))
    val single = dblpWork("conf/x/Z9", Vector(A("Zoe Ng")))
    val ds = Vector(
      w(e1.workId, e2.workId, path = Exact, confidence = 0.99),
      w(e1.workId, e3.workId, path = Exact, confidence = 0.99),
      w(e2.workId, e3.workId, path = Exact, confidence = 0.99),
      w(e3.workId, e4.workId, Unresolved, confidence = 0.5),
      w(f1.workId, f2.workId, path = Exact, confidence = 0.99)
    )
    val b = group(inputs(Vector(e1, e2, e3, e4, f1, f2, single), Vector.empty, ds))
    val rep = work(b, e1.workId)
    assertEquals(rep.graphEligibility, GraphEligibility.Counted)
    assertEquals(rep.groupSize, 3)
    assert(rep.reasonCodes.contains("grouping.representative.dblp"))
    Vector(e2, e3).foreach { c =>
      assertEquals(work(b, c.workId).canonicalWorkId, e1.workId)
      assertEquals(work(b, c.workId).graphEligibility, GraphEligibility.MemberOfCounted)
    }
    val pre = work(b, e4.workId)
    assertEquals(
      (pre.status, pre.graphEligibility),
      (WorkGroupStatus.Single, GraphEligibility.EnrichmentOnly)
    )
    assert(pre.reasonCodes.contains("grouping.pending-decision"))
    // The canonical work's one team comes from the representative's three mentions only.
    assertEquals(
      b.teams.map(_.workId),
      Vector(e1.workId, single.workId).sortWith(Canonical.compareUtf8(_, _) < 0)
    )
    assertEquals(b.teams.find(_.workId == e1.workId).map(_.teamSize), Some(3))
    // Enrichment-only group: the Crossref record is the representative (doi before openalex),
    // with authorships but no team.
    assertEquals(work(b, f2.workId).canonicalWorkId, f1.workId)
    assert(
      Vector(f1, f2).forall(x =>
        work(b, x.workId).graphEligibility == GraphEligibility.EnrichmentOnly
      )
    )
    assert(b.authorships.exists(_.workId == f1.workId))
    assert(!b.authorships.exists(a => a.workId == e2.workId || a.workId == f2.workId))
    assertEquals(work(b, single.workId).status, WorkGroupStatus.Single)
    assert(
      b.diagnostics.exists(_.contains("counted=2 member-of-counted=2 enrichment-only=3")),
      b.diagnostics.mkString("\n")
    )
  }

  test("9. authorships and teams: minimum supporting confidence; unresolved mentions in the team") {
    val t1 = dblpWork("conf/x/M1", Vector(A("Wei Wang"), A("Kim Park")))
    val t2 = crossrefWork("10.1000/m2", Vector(A("Wei Wang")))
    val t3 = dblpWork("conf/x/M3", Vector(A("Wei Wang")))
    val t4 = dblpWork("conf/x/M4", Vector(A("Kim Park")))
    val ds = Vector(
      r(m(t1), m(t2), path = Exact, confidence = 0.99),
      r(m(t1), m(t3), confidence = 0.90),
      r(m(t1, 1), m(t4), Unresolved, confidence = 0.5)
    )
    val b = group(inputs(Vector(t1, t2, t3, t4), Vector.empty, ds))
    val a1 = b.authorships.find(_.mentionId == m(t1)).get
    assertEquals(a1.matchConfidence, Some(0.90))
    assertEquals(a1.supportingDecisionIds.size, 2)
    assertEquals(b.authorships.find(_.mentionId == m(t3)).flatMap(_.matchConfidence), Some(0.90))
    assert(!b.authorships.exists(_.mentionId == m(t1, 1)), "an unresolved mention has none")
    val team = b.teams.find(_.workId == t1.workId).get
    assertEquals(team.teamSize, 2)
    assertEquals(team.researcherIds, Vector(researcher(b, m(t1)).researcherId.get))
    assertEquals(team.unresolvedMentionIds, Vector(m(t1, 1)))
    assert(!b.teams.exists(_.workId == t2.workId), "Crossref works are never counted")
    assertEquals(a1.year, t1.year)
  }

  test("the grouping parses names exactly as the pairwise features do") {
    val w = dblpWork("conf/x/NM", Vector(A("Wang, Wei 0001"), A("Christopher Frost 0001")))
    w.authors.foreach { mention =>
      val e = ResearcherEndpoint.OfMention(w, mention)
      assertEquals(Grouping.nameOf(e), MatchFeatures.prepare(e).name)
    }
    val p = openAlexProfile("A5", "Wei Wang")
    val e = ResearcherEndpoint.OfProfile(p)
    assertEquals(Grouping.nameOf(e), MatchFeatures.prepare(e).name)
  }

  // ---------------------------------------------------------------- 10. fail closed

  test("10. corrupted outputs are refused by each core validator and by the producer checks") {
    val in = richInputs()
    val b = group(in)
    assertEquals(Grouping.verify(in, b), Vector.empty)
    def refusedBy(tag: String, x: GroupingBatch): Unit = {
      val es = Grouping.verify(in, x)
      assert(es.exists(_.startsWith(tag)), s"$tag: ${es.mkString("\n")}")
    }
    val core = "grouping.invariant.core."
    refusedBy(core + "checkResearchers", b.copy(researchers = b.researchers :+ b.researchers.head))
    val copy = b.works.find(_.graphEligibility == GraphEligibility.MemberOfCounted).get
    refusedBy(
      core + "checkWorks",
      b.copy(works =
        b.works.map(w => if (w == copy) w.copy(graphEligibility = GraphEligibility.Counted) else w)
      )
    )
    val mentionWork = b.works
      .find(w =>
        w.status == WorkGroupStatus.Single && b.researchers.exists(_.workId.contains(w.workId)) &&
          WorkAssignment.decisionLists(w).forall(_._2.isEmpty)
      )
      .get
    refusedBy(core + "checkRun", b.copy(works = b.works.filterNot(_ == mentionWork)))
    // A REJECTED decision dropped from both endpoints' lists.
    val rejected = b.researchers.flatMap(_.rejectedDecisionIds).head
    refusedBy(
      core + "checkAgainstDecisions",
      b.copy(researchers =
        b.researchers.map(r =>
          r.copy(rejectedDecisionIds = r.rejectedDecisionIds.filterNot(_ == rejected))
        )
      )
    )
    refusedBy(core + "checkAuthorships", b.copy(authorships = b.authorships.tail))
    val resolved = b.authorships.find(_.matchConfidence.isDefined).get
    refusedBy(
      core + "checkAuthorshipConfidence",
      b.copy(authorships =
        b.authorships.map(a => if (a == resolved) a.copy(matchConfidence = Some(0.5)) else a)
      )
    )
    refusedBy(
      core + "checkTeams",
      b.copy(teams = b.teams.map(t => t.copy(teamSize = t.teamSize + 1)))
    )
    // A singleton without decisions dropped: its work's author list is no longer covered.
    val lone = b.researchers
      .find(r =>
        r.workId.isDefined && r.status == AssignmentStatus.Singleton &&
          ResearcherAssignment.decisionLists(r).forall(_._2.isEmpty)
      )
      .get
    refusedBy(core + "checkCoverage", b.copy(researchers = b.researchers.filterNot(_ == lone)))
    // A t1-anchored singleton moved onto a pid it does not assert.
    val t1 = b.researchers
      .find(ResearcherAssignment.requiresT1Anchor)
      .getOrElse(fail("fixture lacks a t1-anchored singleton"))
    val moved = t1.copy(
      anchorNamespace = Some("dblp-pid"),
      anchorValue = Some("q/404"),
      researcherId = Some(ok(ResearcherId.scholarChain("v1", "dblp-pid", "q/404")))
    )
    refusedBy(
      core + "checkSingletonAnchors",
      b.copy(researchers = b.researchers.map(r => if (r == t1) moved else r))
    )
    // A vetoed link without its reason codes, and a supporting decision recorded as vetoed.
    val vetoedLink = b.links.find(_.outcome == LinkOutcome.Vetoed).get
    refusedBy(
      "grouping.invariant.producer",
      b.copy(links =
        b.links.map(l => if (l == vetoedLink) l.copy(reasonCodes = Vector.empty) else l)
      )
    )
    val mergedLink = b.links.find(_.outcome == LinkOutcome.Merged).get
    refusedBy(
      "grouping.invariant.producer",
      b.copy(links =
        b.links.map(l =>
          if (l == mergedLink) l.copy(outcome = LinkOutcome.Vetoed, reasonCodes = Vector("x"))
          else l
        )
      )
    )
  }
}
