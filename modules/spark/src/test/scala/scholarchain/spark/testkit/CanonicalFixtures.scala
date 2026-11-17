package scholarchain.spark.testkit

import scala.collection.immutable.SortedMap

import scholarchain.core.*

import RunFixtures.*

/**
 * A hand-checkable chain for the canonical tables (SP-P6-03 step 2B, course item 1): the default
 * [[RunFixtures]] chain (researchers A, B, C, D resolved, E a singleton; the unresolved mention u1 =
 * w1 position 3; counted works w1..w5, enrichment-only x1; the same teams, so the same edges and
 * metrics) plus:
 *   - a non-representative copy `wc` (OpenAlex W9, member-of-counted) of w2: w2 becomes the grouped
 *     representative (groupSize 2); wc has one unresolved mention `wc/0` (no team, no node);
 *   - curated venues V1 (`venue:dblp:journals/example1`) and V2 (`venue:openalex:S7`), topic T1,
 *     institution I1; no record for `venue:dblp:journals/missing`, T9 or I8;
 *   - assertions: w1 -> V1 and T1; w3 -> the missing venue; wc -> V2, T1 and T9; affiliations: A's
 *     mention w1/0 -> I1; u1 -> I1 and I8; wc/0 -> I1.
 *
 * Hand derivation (CanonicalGraph rules; every value below is derived by hand, never by calling the
 * code under test):
 * {{{
 *   nodes (26)
 *     Researcher   A resolved 3, B resolved 2, C resolved 2, D resolved 2 (anchor dblp-pid),
 *                  E singleton 1 (no pending decision, so not t1-anchored)
 *     Work         w1 counted 1 2018, w2 counted 2 2019, w3 counted 1 2021, w4 counted 1 2022,
 *                  w5 counted 1 (no year), x1 enrichment-only 1 (no year); wc is no node
 *     AuthorMention the 11 mentions on representatives: w1/0..3, w2/0..1, w3/0..1, w4/0,
 *                  w5/0..1 (u1 = w1/3 unresolved, w4/0 singleton, the rest resolved); wc/0 none
 *     Venue V1 "Example Journal One", V2 "Example Source Seven"; Institution I1; Topic T1
 *   edges (31)
 *     RESOLVES_TO  10, one per identified mention on a representative
 *     AUTHORED     10 researcher edges (one per authorship record, evidence the mention) and
 *                  u1 -> w1 (position 3): the unresolved mention keeps its authorship, unmerged
 *     PUBLISHED_IN w1 -> V1 (evidence w1); w2 -> V2 (evidence wc: the copy's venue joins its
 *                  representative)
 *     HAS_TOPIC    w1 -> T1 (evidence w1); w2 -> T1 (evidence wc)
 *     AFFILIATED   A -> I1 (evidence w1/0); u1 -> I1 (evidence u1); wc/0 is unresolved on a copy:
 *                  no edge and not counted
 *     COAUTHORED   2015-2019 A-B (2018-2019, 2), A-C (2018, 1), B-C (2018, 1); 2020-2024 C-D
 *                  (2021, 1)
 *   unmatched      venues 1 (w3's missing venue), topics 1 (wc's T9), institutions 1 (u1's I8)
 * }}}
 */
object CanonicalFixtures {

  val wc: String = "work:openalex:W9"
  val wcMention: String = ok(MentionId.of(wc, 0))
  def m(w: String, pos: Int): String = ok(MentionId.of(w, pos))

  val v1 = "venue:dblp:journals/example1"
  val v2 = "venue:openalex:S7"
  val vMissing = "venue:dblp:journals/missing"
  val t1 = "topic:openalex:T1"
  val t9 = "topic:openalex:T9"
  val i1 = "institution:openalex:I1"
  val i8 = "institution:openalex:I8"

  private val workDecision = "match:" + Canonical.sha256HexUtf8("canonical fixture work w2 wc")

  private def grouped(w: String, eligibility: GraphEligibility, g: String, d: String) =
    RunFixtures
      .work(w, eligibility, g, d)
      .copy(
        canonicalWorkId = w2,
        isRepresentative = w == w2,
        status = WorkGroupStatus.Grouped,
        representativeRule = "grouping.dblp-representative",
        groupSize = 2,
        supportingDecisionIds = Vector(workDecision),
        reasonCodes = Vector("grouping.grouped")
      )

  def works(g: String, d: String): Vector[WorkAssignment] =
    RunFixtures
      .works(g, d)
      .map(w => if (w.workId == w2) grouped(w2, GraphEligibility.Counted, g, d) else w) :+
      grouped(wc, GraphEligibility.MemberOfCounted, g, d)

  def researchers(g: String, d: String): Vector[ResearcherAssignment] = {
    val base = RunFixtures.researchers(g, d)
    val unresolved = base.find(_.memberId == u1).get
    base :+ unresolved.copy(
      memberId = wcMention,
      workId = Some(wc),
      position = Some(0),
      provenance = gprov(wcMention, SchemaVersions.AuthorMention, g)
    )
  }

  private def openAlex(id: String) =
    sourceProvenance(SourceType.OpenAlex, id, "openalex-2026-11-05", curatedRunId)

  val venues: Vector[Venue] = Vector(
    Venue(
      SchemaVersions.Venue,
      v1,
      "Example Journal One",
      None,
      Vector.empty,
      Vector.empty,
      sourceProvenance(SourceType.Dblp, "journals/example1", snapshot, curatedRunId)
    ),
    Venue(
      SchemaVersions.Venue,
      v2,
      "Example Source Seven",
      None,
      Vector.empty,
      Vector.empty,
      openAlex("S7")
    )
  )
  val institutions: Vector[Institution] = Vector(
    Institution(
      SchemaVersions.Institution,
      i1,
      "Example University",
      None,
      None,
      Vector.empty,
      openAlex("I1")
    )
  )
  val topics: Vector[Topic] =
    Vector(Topic(SchemaVersions.Topic, t1, "Example Topic", None, openAlex("T1")))

  val spec: Spec = Spec(
    works = Some(works),
    researchers = Some(researchers),
    venues = venues,
    institutions = institutions,
    topics = topics,
    decor = Map(
      w1 -> WorkDecor(Some(v1), Vector(t1)),
      w3 -> WorkDecor(Some(vMissing)),
      wc -> WorkDecor(Some(v2), Vector(t1, t9))
    ),
    affiliations = Map(m(w1, 0) -> Vector(i1), u1 -> Vector(i1, i8), wcMention -> Vector(i1))
  )

  // ---------------------------------------------------------------- the hand tables

  private def props(kv: (String, String)*) = SortedMap(kv*)

  val handNodes: Vector[ExpectedNode] = {
    def researcher(r: String, status: String, n: Int) = ExpectedNode(
      r,
      NodeLabel.Researcher,
      props(
        "anchorNamespace" -> "dblp-pid",
        "identityStatus" -> status,
        "memberCount" -> n.toString
      )
    )
    def work(w: String, eligibility: String, size: Int, year: Option[Int]) = ExpectedNode(
      w,
      NodeLabel.Work,
      SortedMap(
        (Vector("graphEligibility" -> eligibility, "groupSize" -> size.toString) ++
          year.map(y => "year" -> y.toString))*
      )
    )
    def mention(w: String, pos: Int, status: String) = ExpectedNode(
      m(w, pos),
      NodeLabel.AuthorMention,
      props("assignmentStatus" -> status, "position" -> pos.toString, "workId" -> w)
    )
    Vector(
      researcher(rA, "resolved", 3),
      researcher(rB, "resolved", 2),
      researcher(rC, "resolved", 2),
      researcher(rD, "resolved", 2),
      researcher(rE, "singleton", 1),
      work(w1, "counted", 1, Some(2018)),
      work(w2, "counted", 2, Some(2019)),
      work(w3, "counted", 1, Some(2021)),
      work(w4, "counted", 1, Some(2022)),
      work(w5, "counted", 1, None),
      work(x1, "enrichment-only", 1, None),
      mention(w1, 0, "resolved"),
      mention(w1, 1, "resolved"),
      mention(w1, 2, "resolved"),
      mention(w1, 3, "unresolved"),
      mention(w2, 0, "resolved"),
      mention(w2, 1, "resolved"),
      mention(w3, 0, "resolved"),
      mention(w3, 1, "resolved"),
      mention(w4, 0, "singleton"),
      mention(w5, 0, "resolved"),
      mention(w5, 1, "resolved"),
      ExpectedNode(v1, NodeLabel.Venue, props("name" -> "Example Journal One")),
      ExpectedNode(v2, NodeLabel.Venue, props("name" -> "Example Source Seven")),
      ExpectedNode(i1, NodeLabel.Institution, props("name" -> "Example University")),
      ExpectedNode(t1, NodeLabel.Topic, props("name" -> "Example Topic"))
    )
  }

  /** The researcher of each identified mention on a representative (from the RunFixtures table). */
  private val identified: Vector[(String, Int, String, String)] = Vector(
    (w1, 0, rA, "resolved"),
    (w1, 1, rB, "resolved"),
    (w1, 2, rC, "resolved"),
    (w2, 0, rA, "resolved"),
    (w2, 1, rB, "resolved"),
    (w3, 0, rC, "resolved"),
    (w3, 1, rD, "resolved"),
    (w4, 0, rE, "singleton"),
    (w5, 0, rA, "resolved"),
    (w5, 1, rD, "resolved")
  )

  /**
   * The COAUTHORED_WITH evidence: the coauthor edge ID of the pair in the window (core formula).
   */
  def coauthorEvidence(a: String, b: String, w: TimeWindow): String =
    ok(CanonicalPair.of(a, b).flatMap(p => CoauthorEdgeId.of(p, w, settings.graphPolicyVersion)))

  val handEdges: Vector[ExpectedEdge] = {
    def co(a: String, b: String, w: TimeWindow, first: Int, last: Int, n: Int) = {
      val pair = sorted(a, b)
      ExpectedEdge(
        EdgeLabel.CoauthoredWith,
        pair(0),
        pair(1),
        coauthorEvidence(a, b, w),
        props(
          "firstCollaborationYear" -> first.toString,
          "jointPublicationCount" -> n.toString,
          "lastCollaborationYear" -> last.toString,
          "windowEndYear" -> w.endYear.toString,
          "windowStartYear" -> w.startYear.toString
        )
      )
    }
    identified.map { case (w, pos, r, status) =>
      ExpectedEdge(
        EdgeLabel.ResolvesTo,
        m(w, pos),
        r,
        m(w, pos),
        props("assignmentStatus" -> status)
      )
    } ++ identified.map { case (w, pos, r, _) =>
      ExpectedEdge(EdgeLabel.Authored, r, w, m(w, pos), props("position" -> pos.toString))
    } ++ Vector(
      ExpectedEdge(EdgeLabel.Authored, u1, w1, u1, props("position" -> "3")),
      ExpectedEdge(EdgeLabel.PublishedIn, w1, v1, w1, props()),
      ExpectedEdge(EdgeLabel.PublishedIn, w2, v2, wc, props()),
      ExpectedEdge(EdgeLabel.HasTopic, w1, t1, w1, props()),
      ExpectedEdge(EdgeLabel.HasTopic, w2, t1, wc, props()),
      ExpectedEdge(EdgeLabel.AffiliatedWith, rA, i1, m(w1, 0), props()),
      ExpectedEdge(EdgeLabel.AffiliatedWith, u1, i1, u1, props()),
      co(rA, rB, window1, 2018, 2019, 2),
      co(rA, rC, window1, 2018, 2018, 1),
      co(rB, rC, window1, 2018, 2018, 1),
      co(rC, rD, window2, 2021, 2021, 1)
    )
  }

  val handUnmatched: UnmatchedReferences = UnmatchedReferences(1, 1, 1)
}
