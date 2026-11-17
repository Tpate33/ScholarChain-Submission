package scholarchain.spark.testkit

import scholarchain.core.*

import RunFixtures.{gprov, ok, sorted}

/**
 * A hand-checkable grouping run for researcher metrics v2 (SP-P6-02 step 1B), written through
 * [[RunFixtures.write]] (same curated and decision layout, same windows 2015-2019 and 2020-2024,
 * same settings). Every identifier is synthetic.
 *
 * Researchers (sc IDs anchored on dblp-pid `10/000n`): A, B, C, D resolved; E a t1-anchored
 * singleton (it carries a pending decision); F a plain singleton; G a singleton on a yearless work
 * only. `uXy` are unresolved mentions (no researcher ID, a pending decision each).
 *
 * Counted works (team = identified researchers + unresolved mentions; n = teamSize):
 * {{{
 *   work  year  identified    unresolved     n   notes
 *   m1    2016  A, B          u1a            3   one unresolved mention
 *   m2    2017  A, C          u2a, u2b       4   several unresolved mentions
 *   m3    2018  E             u3a, u3b       3   E's only coauthors are unresolved
 *   m4    2019  F             -              1   solo work
 *   m5    2021  A, B          -              2   A-B again (repeated coauthor)
 *   m6    2022  A, B, C, D    -              4
 *   m7    2023  D             u7a            2
 *   m8    -     A, G          -              2   no year: in no window
 *   m9    2024  B, C, D       u9a            4   ties m6 for B, C and D (n - 1 = 3)
 * }}}
 * Uncounted: `mc1` (member-of-counted copy of m6, a mention of A, no team) and `x1`
 * (enrichment-only DOI work, no mention). Neither is in any metric.
 *
 * Grouping members per researcher: A 6 (m1, m2, m5, m6, m8, mc1), B 4, C 3, D 3, E 1, F 1, G 1.
 * Decisions of the decision run (ACCEPTED, confidence): dA1 0.97 and dA2 0.91 (A), dB 0.88 (B), dC
 * 0.95 (C), dD1 0.99 and dD2 0.93 (D). One member of C carries the reason code
 * `grouping.anchor-collision.orcid`.
 *
 * Expected identity (IdentityConfidence.derive, core Analysis.scala:323-378): A resolved, 6
 * members, measured 0.91 (min 0.97, 0.91); B resolved, 4, measured 0.88; C resolved, 3, collision
 * code [grouping.anchor-collision.orcid], measured 0.95; D resolved, 3, measured 0.93 (min 0.99,
 * 0.93); E singleton-t1-anchored, 1, unknown no-supporting-decision; F singleton, 1, unknown
 * no-supporting-decision. All anchored on dblp-pid.
 *
 * Expected metrics, hand-derived (core Analysis.scala:505-520). T = sum (n - 1); U = sum u; A_r = T
 * / P; M = max (n - 1); C = distinct identified coauthors; W = sum of edge weights = T - U.
 * {{{
 *   window 2015-2019 (teams m1 m2 m3 m4):
 *   r  works   P  T            U      Wu A_r  M  maxWorks  C (who)  W
 *   A  m1 m2   2  2+3=5        1+2=3  2  2.5  3  m2        2 (B C)  1+1=2 = 5-3
 *   B  m1      1  2            1      1  2.0  2  m1        1 (A)    1     = 2-1
 *   C  m2      1  3            2      1  3.0  3  m2        1 (A)    1     = 3-2
 *   E  m3      1  2            2      1  2.0  2  m3        0        0     = 2-2
 *   F  m4      1  0            0      0  0.0  0  m4        0        0
 *   edges: A-B (m1, 2016), A-C (m2, 2017); totals: 5 records, P 6, T 12, U 8, Wu 5, C 4, W 4, M 3
 *
 *   window 2020-2024 (teams m5 m6 m7 m9):
 *   A  m5 m6     2  1+3=4      0      0  2.0  3  m6        3 (B C D) AB2+AC1+AD1=4 = 4-0
 *   B  m5 m6 m9  3  1+3+3=7    1      1  7/3  3  m6 m9     3 (A C D) AB2+BC2+BD2=6 = 7-1
 *   C  m6 m9     2  3+3=6      1      1  3.0  3  m6 m9     3 (A B D) AC1+BC2+CD2=5 = 6-1
 *   D  m6 m7 m9  3  3+1+3=7    0+1+1  2  7/3  3  m6 m9     3 (A B C) AD1+BD2+CD2=5 = 7-2
 *   edges: A-B (m5 2021, m6 2022), A-C (m6), A-D (m6), B-C (m6, m9), B-D (m6, m9), C-D (m6, m9)
 *   totals: 4 records, P 10, T 24, U 4, Wu 4, C 12, W 20, M 3
 * }}}
 * (Wu = worksWithUnresolvedCoauthors.) A is in both windows with different values; D only in
 * 2020-2024; E and F only in 2015-2019; G (only the yearless m8) in none. The sum of U over records
 * (8 and 4) counts per researcher; the team-level unresolved mentions of the windows are 5 (m1 1,
 * m2 2, m3 2) and 2 (m7 1, m9 1). A's uncounted mc1 and yearless m8 are not in its P.
 */
object MetricsFixtures {

  private def rid(n: Int) = ok(ResearcherId.scholarChain("v1", "dblp-pid", s"10/000$n"))
  val rA: String = rid(1)
  val rB: String = rid(2)
  val rC: String = rid(3)
  val rD: String = rid(4)
  val rE: String = rid(5)
  val rF: String = rid(6)
  val rG: String = rid(7)

  private val anchorOf: Map[String, String] =
    Map(rA -> 1, rB -> 2, rC -> 3, rD -> 4, rE -> 5, rF -> 6, rG -> 7).map { case (r, n) =>
      r -> s"10/000$n"
    }

  private def wid(name: String) = ok(WorkId.dblp(s"journals/example/$name"))
  val m1: String = wid("Metrics1")
  val m2: String = wid("Metrics2")
  val m3: String = wid("Metrics3")
  val m4: String = wid("Metrics4")
  val m5: String = wid("Metrics5")
  val m6: String = wid("Metrics6")
  val m7: String = wid("Metrics7")
  val m8: String = wid("Metrics8")
  val m9: String = wid("Metrics9")
  val mc1: String = wid("MetricsCopy6")
  val x1: String = ok(WorkId.doi("10.1234/fixture.metrics.x1"))

  val collisionCode = "grouping.anchor-collision.orcid"

  /** (work, year, mentions in author order: Some(researcher) or None for unresolved). */
  val counted: Vector[(String, Option[Int], Vector[Option[String]])] = Vector(
    (m1, Some(2016), Vector(Some(rA), Some(rB), None)),
    (m2, Some(2017), Vector(Some(rA), Some(rC), None, None)),
    (m3, Some(2018), Vector(Some(rE), None, None)),
    (m4, Some(2019), Vector(Some(rF))),
    (m5, Some(2021), Vector(Some(rA), Some(rB))),
    (m6, Some(2022), Vector(Some(rA), Some(rB), Some(rC), Some(rD))),
    (m7, Some(2023), Vector(Some(rD), None)),
    (m8, None, Vector(Some(rA), Some(rG))),
    (m9, Some(2024), Vector(Some(rB), Some(rC), Some(rD), None))
  )

  /** Mentions on the uncounted copy mc1 (no team). */
  private val uncountedMentions: Vector[(String, Vector[Option[String]])] =
    Vector((mc1, Vector(Some(rA))))

  def mention(w: String, pos: Int): String = ok(MentionId.of(w, pos))

  def teams(g: String): Vector[PublicationTeam] = counted.map { case (w, year, ms) =>
    val ids = ms.flatten
    val unresolved = ms.zipWithIndex.collect { case (None, i) => mention(w, i) }
    RunFixtures.team(w, year, ids, unresolved, g)
  }

  // ---------------------------------------------------------------- decisions

  /**
   * (researcher, seed, k, confidence) of every ACCEPTED decision of the decision run. Decision k of
   * a researcher links its members k and k + 1 (a decision ID is a function of its endpoints, so
   * two decisions of one group need two different member pairs).
   */
  private val decisionSpecs: Vector[(String, String, Int, Double)] = Vector(
    (rA, "metrics dA1", 0, 0.97),
    (rA, "metrics dA2", 1, 0.91),
    (rB, "metrics dB", 0, 0.88),
    (rC, "metrics dC", 0, 0.95),
    (rD, "metrics dD1", 0, 0.99),
    (rD, "metrics dD2", 1, 0.93)
  )

  private def decisionOf(runId: String, r: String, seed: String, k: Int, c: Double) = {
    val ms = membersOf(r)
    RunFixtures.acceptedDecision(runId, seed, ms(k), ms(k + 1), c)
  }

  /** Every decision of the decision run `runId` (`without` seeds left out, for refusal tests). */
  def decisions(runId: String, without: Set[String] = Set.empty): Vector[MatchDecision] =
    decisionSpecs.filterNot(s => without.contains(s._2)).map { case (r, seed, k, c) =>
      decisionOf(runId, r, seed, k, c)
    }

  /** The supporting decision IDs of researcher `r` (sorted), from the full decision list. */
  private def supportingOf(r: String, d: String): Vector[String] =
    sorted(
      decisionSpecs
        .filter(_._1 == r)
        .map { case (_, seed, k, c) => decisionOf(d, r, seed, k, c).decisionId }*
    )

  // ---------------------------------------------------------------- assignments

  private val allMentions: Vector[(String, Int, Option[String])] =
    (counted.map(c => (c._1, c._3)) ++ uncountedMentions).flatMap { case (w, ms) =>
      ms.zipWithIndex.map { case (r, i) => (w, i, r) }
    }

  /** Mention IDs of researcher `r`, in fixture order. */
  def membersOf(r: String): Vector[String] =
    allMentions.collect { case (w, i, Some(x)) if x == r => mention(w, i) }

  def researchers(g: String, d: String): Vector[ResearcherAssignment] =
    allMentions.map { case (w, pos, r) =>
      val m = mention(w, pos)
      val size = r.map(membersOf(_).size).getOrElse(1)
      val status = r match {
        case None => AssignmentStatus.Unresolved
        case Some(_) if size == 1 => AssignmentStatus.Singleton
        case Some(_) => AssignmentStatus.Resolved
      }
      val pending =
        if (status == AssignmentStatus.Unresolved || r.contains(rE))
          Vector(RunFixtures.fakePending)
        else Vector.empty
      val codes =
        if (r.contains(rC) && w == m2) sorted(collisionCode, "grouping.resolved")
        else Vector(s"grouping.${status.tag}")
      ResearcherAssignment(
        SchemaVersions.ResearcherAssignment,
        m,
        Some(w),
        Some(pos),
        status,
        r,
        "v1",
        r.map(_ => "dblp-pid"),
        r.map(anchorOf),
        size,
        if (status == AssignmentStatus.Resolved) supportingOf(r.get, d) else Vector.empty,
        Vector.empty,
        pending,
        Vector.empty,
        Vector.empty,
        codes,
        RunFixtures.groupingPolicy,
        RunFixtures.matchPolicy,
        d,
        RunFixtures.assignedAt,
        gprov(m, SchemaVersions.AuthorMention, g)
      )
    }

  private val workDecision = "match:" + Canonical.sha256HexUtf8("metrics work decision m6 mc1")

  private def grouped(w: String, eligibility: GraphEligibility, g: String, d: String) =
    RunFixtures
      .work(w, eligibility, g, d)
      .copy(
        canonicalWorkId = m6,
        isRepresentative = w == m6,
        status = WorkGroupStatus.Grouped,
        representativeRule = "grouping.dblp-representative",
        groupSize = 2,
        supportingDecisionIds = Vector(workDecision),
        reasonCodes = Vector("grouping.grouped")
      )

  def works(g: String, d: String): Vector[WorkAssignment] =
    counted.map(_._1).filterNot(_ == m6).map(RunFixtures.work(_, GraphEligibility.Counted, g, d)) ++
      Vector(
        grouped(m6, GraphEligibility.Counted, g, d),
        grouped(mc1, GraphEligibility.MemberOfCounted, g, d),
        RunFixtures.work(x1, GraphEligibility.EnrichmentOnly, g, d)
      )

  /** The fixture chain's [[RunFixtures.Spec]] (`decisionsWithout`: seeds left out). */
  def spec(decisionsWithout: Set[String] = Set.empty): RunFixtures.Spec = RunFixtures.Spec(
    teams = Some(teams),
    works = Some(works),
    researchers = Some(researchers),
    decisions = Some(r => decisions(r, decisionsWithout))
  )

  // ---------------------------------------------------------------- expected values

  /** The hand-derived identity of each researcher with metrics (see the object comment). */
  val expectedIdentity: Map[String, IdentityConfidence] = {
    def resolved(n: Int, codes: Vector[String], c: Double) =
      IdentityConfidence(
        IdentityStatus.Resolved,
        "dblp-pid",
        n,
        codes,
        DecisionConfidence.measured(c)
      )
    val unknown = DecisionConfidence.unknown(ConfidenceUnknownReason.NoSupportingDecision)
    Map(
      rA -> resolved(6, Vector.empty, 0.91),
      rB -> resolved(4, Vector.empty, 0.88),
      rC -> resolved(3, Vector(collisionCode), 0.95),
      rD -> resolved(3, Vector.empty, 0.93),
      rE -> IdentityConfidence(
        IdentityStatus.SingletonT1Anchored,
        "dblp-pid",
        1,
        Vector.empty,
        unknown
      ),
      rF -> IdentityConfidence(IdentityStatus.Singleton, "dblp-pid", 1, Vector.empty, unknown)
    )
  }

  /** (P, C, T, U, Wu, A_r, M, maxWorks, W) of one researcher in one window. */
  final case class Expected(
      p: Long,
      c: Long,
      t: Long,
      u: Long,
      wu: Long,
      a: Double,
      m: Long,
      maxWorks: Vector[String],
      w: Long
  )

  /** The hand-derived table of the object comment, keyed by (window, researcher). */
  val expected: Map[(TimeWindow, String), Expected] = {
    val w1 = RunFixtures.window1
    val w2 = RunFixtures.window2
    Map(
      (w1, rA) -> Expected(2, 2, 5, 3, 2, 2.5, 3, Vector(m2), 2),
      (w1, rB) -> Expected(1, 1, 2, 1, 1, 2.0, 2, Vector(m1), 1),
      (w1, rC) -> Expected(1, 1, 3, 2, 1, 3.0, 3, Vector(m2), 1),
      (w1, rE) -> Expected(1, 0, 2, 2, 1, 2.0, 2, Vector(m3), 0),
      (w1, rF) -> Expected(1, 0, 0, 0, 0, 0.0, 0, Vector(m4), 0),
      (w2, rA) -> Expected(2, 3, 4, 0, 0, 2.0, 3, Vector(m6), 4),
      (w2, rB) -> Expected(3, 3, 7, 1, 1, 7.0 / 3.0, 3, sorted(m6, m9), 6),
      (w2, rC) -> Expected(2, 3, 6, 1, 1, 3.0, 3, sorted(m6, m9), 5),
      (w2, rD) -> Expected(3, 3, 7, 2, 2, 7.0 / 3.0, 3, sorted(m6, m9), 5)
    )
  }
}
