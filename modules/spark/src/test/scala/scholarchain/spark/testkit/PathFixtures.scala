package scholarchain.spark.testkit

import scholarchain.core.*
import scholarchain.core.config.*

import RunFixtures.{gprov, ok, sorted, WorkDecor}

/**
 * A hand-checkable grouping run for stored paths, path-length estimates, summaries and load
 * manifests (SP-P6-03 step 2C), written through [[RunFixtures.write]] (same curated and decision
 * layout, same windows 2015-2019 and 2020-2024). Every identifier is synthetic.
 *
 * Researchers A..I (sc IDs anchored on dblp-pid `20/000n`): A, B, C, D, E, I resolved (one ACCEPTED
 * decision, confidence 0.9, between each two consecutive mentions); F, G, H singletons. Counted
 * works (all DBLP, one team each, no unresolved mention):
 * {{{
 *   work  year  team        window      work  year  team   window
 *   q01   2015  A B         2015-2019   q07   2018  B I    2015-2019
 *   q02   2015  A C         2015-2019   q08   2018  I C    2015-2019
 *   q03   2016  B D         2015-2019   q09   2019  A B    2015-2019 (A-B again)
 *   q04   2016  C D         2015-2019   q10   2021  A E    2020-2024
 *   q05   2017  D E         2015-2019   q11   2022  E H    2020-2024
 *   q06   2017  F G         2015-2019
 * }}}
 * Window graphs: 2015-2019 edges A-B (q01, q09), A-C (q02), B-D (q03), C-D (q04), D-E (q05), F-G
 * (q06), B-I (q07), C-I (q08): components {A,B,C,D,E,I} (6) and {F,G} (2); H absent. 2020-2024
 * edges A-E (q10), E-H (q11): one component {A,E,H} (3).
 *
 * Configuration ([[config]]): maxPathHops 3, maxPathsPerPair 2, maxEvidenceWorksPerHop 1; the
 * path-length keys sampleSize 4, seed 7, minComponentSize 3.
 *
 * Benchmark pairs (canonical order computed, [[pairs]]): (A, E), (A, F), (B, C), (A, H). Hand
 * derivation of the stored paths (x < y is UTF-8 order of the researcher IDs, decided at run time;
 * the source is the smaller ID of the pair):
 *   - (A, E) 2015-2019: exactly two shortest paths of 3 hops, A-B-D-E and A-C-D-E (E's only
 *     neighbour is D, whose neighbours at distance 1 from A are B and C); both stored, ordered by B
 *     against C. 2020-2024: A-E directly (1 hop).
 *   - (A, F) 2015-2019: different components, no path; 2020-2024: F is not in the window, none.
 *   - (B, C) 2015-2019: three shortest paths of 2 hops, B-A-C, B-D-C and B-I-C; with
 *     maxPathsPerPair 2 only the two whose middle researcher is smallest among A, D, I are stored
 *     (a tie broken by node sequence, then truncated). 2020-2024: neither is in the window.
 *   - (A, H) 2015-2019: H is not in the window; 2020-2024: A-E-H (2 hops).
 * Six paths in total. Hop evidence (one work per hop, the smallest supporting work): A-B shows q01
 * of {q01, q09}; every other edge has one supporting work.
 *
 * Path-length estimates (component >= 3 researchers): 2015-2019 {A,B,C,D,E,I}, size 6 > 4, so
 * sampled with the four members of smallest `PathSampleKey`; {F,G} (size 2) none. 2020-2024
 * {A,E,H}, size 3 <= 4, exact. Breadth-first distances by hand (pairs at distance 1, 2, 3):
 * {{{
 *   2015-2019 from A (2,2,1)  B (3,2)  C (3,2)  D (3,2)  E (1,2,2)  I (2,2,1)
 *   2020-2024 from A (1,1)    E (2)    H (1,1)  -> counts d1 4, d2 2; 6 pairs; average 8/6;
 *                                                  max 2; effective diameter (90 %) 2
 * }}}
 * The sampled counts are the sum of the rows of the four sampled sources.
 *
 * Curated decor: q01 -> venue V1 and topic T1; q05 -> topics T1 and T2; q10 -> venue V2. Selected
 * works of the entity summaries are the evidence works of the six paths; selected topics are their
 * HAS_TOPIC targets (T1 and T2, both carried by q01 or q05).
 */
object PathFixtures {

  private def rid(n: Int) = ok(ResearcherId.scholarChain("v1", "dblp-pid", s"20/000$n"))
  val rA: String = rid(1)
  val rB: String = rid(2)
  val rC: String = rid(3)
  val rD: String = rid(4)
  val rE: String = rid(5)
  val rF: String = rid(6)
  val rG: String = rid(7)
  val rH: String = rid(8)
  val rI: String = rid(9)

  private val anchorOf: Map[String, String] =
    Vector(rA, rB, rC, rD, rE, rF, rG, rH, rI).zipWithIndex.map { case (r, i) =>
      r -> s"20/000${i + 1}"
    }.toMap

  private def wid(n: Int) = ok(WorkId.dblp(f"journals/example/Path$n%02d"))
  val q: Vector[String] = Vector.tabulate(12)(i => if (i == 0) "" else wid(i))

  /** (work, year, team) of every counted work. */
  val counted: Vector[(String, Int, Vector[String])] = Vector(
    (q(1), 2015, Vector(rA, rB)),
    (q(2), 2015, Vector(rA, rC)),
    (q(3), 2016, Vector(rB, rD)),
    (q(4), 2016, Vector(rC, rD)),
    (q(5), 2017, Vector(rD, rE)),
    (q(6), 2017, Vector(rF, rG)),
    (q(7), 2018, Vector(rB, rI)),
    (q(8), 2018, Vector(rI, rC)),
    (q(9), 2019, Vector(rA, rB)),
    (q(10), 2021, Vector(rA, rE)),
    (q(11), 2022, Vector(rE, rH))
  )

  def mention(w: String, pos: Int): String = ok(MentionId.of(w, pos))

  def teams(g: String): Vector[PublicationTeam] = counted.map { case (w, y, ids) =>
    RunFixtures.team(w, Some(y), ids, Vector.empty, g)
  }

  def works(g: String, d: String): Vector[WorkAssignment] =
    counted.map(c => RunFixtures.work(c._1, GraphEligibility.Counted, g, d))

  private val allMentions: Vector[(String, Int, String)] =
    counted.flatMap { case (w, _, ids) => ids.zipWithIndex.map { case (r, i) => (w, i, r) } }

  def membersOf(r: String): Vector[String] =
    allMentions.collect { case (w, i, x) if x == r => mention(w, i) }

  /** One ACCEPTED decision between each two consecutive mentions of a resolved researcher. */
  def decisions(runId: String): Vector[MatchDecision] =
    anchorOf.keys.toVector.sorted.flatMap { r =>
      val ms = membersOf(r)
      ms.indices.dropRight(1).map { k =>
        RunFixtures.acceptedDecision(runId, s"paths $r $k", ms(k), ms(k + 1), 0.9)
      }
    }

  private def supportingOf(r: String, d: String): Vector[String] =
    sorted(decisions(d).filter(x => membersOf(r).contains(x.sourceId)).map(_.decisionId)*)

  def researchers(g: String, d: String): Vector[ResearcherAssignment] =
    allMentions.map { case (w, pos, r) =>
      val m = mention(w, pos)
      val size = membersOf(r).size
      val status = if (size == 1) AssignmentStatus.Singleton else AssignmentStatus.Resolved
      ResearcherAssignment(
        SchemaVersions.ResearcherAssignment,
        m,
        Some(w),
        Some(pos),
        status,
        Some(r),
        "v1",
        Some("dblp-pid"),
        Some(anchorOf(r)),
        size,
        if (status == AssignmentStatus.Resolved) supportingOf(r, d) else Vector.empty,
        Vector.empty,
        Vector.empty,
        Vector.empty,
        Vector.empty,
        Vector(s"grouping.${status.tag}"),
        RunFixtures.groupingPolicy,
        RunFixtures.matchPolicy,
        d,
        RunFixtures.assignedAt,
        gprov(m, SchemaVersions.AuthorMention, g)
      )
    }

  val v1 = "venue:dblp:journals/pathvenue1"
  val v2 = "venue:openalex:S11"
  val t1 = "topic:openalex:T21"
  val t2 = "topic:openalex:T22"

  private def openAlex(id: String) =
    RunFixtures.sourceProvenance(
      SourceType.OpenAlex,
      id,
      "openalex-2026-11-05",
      RunFixtures.curatedRunId
    )

  val venues: Vector[Venue] = Vector(
    Venue(
      SchemaVersions.Venue,
      v1,
      "Path Venue One",
      None,
      Vector.empty,
      Vector.empty,
      RunFixtures.sourceProvenance(
        SourceType.Dblp,
        "journals/pathvenue1",
        RunFixtures.snapshot,
        RunFixtures.curatedRunId
      )
    ),
    Venue(
      SchemaVersions.Venue,
      v2,
      "Path Venue Two",
      None,
      Vector.empty,
      Vector.empty,
      openAlex("S11")
    )
  )

  val topics: Vector[Topic] = Vector(
    Topic(SchemaVersions.Topic, t1, "Path Topic One", None, openAlex("T21")),
    Topic(SchemaVersions.Topic, t2, "Path Topic Two", None, openAlex("T22"))
  )

  val spec: RunFixtures.Spec = RunFixtures.Spec(
    teams = Some(teams),
    works = Some(works),
    researchers = Some(researchers),
    decisions = Some(decisions),
    venues = venues,
    topics = topics,
    decor = Map(
      q(1) -> WorkDecor(Some(v1), Vector(t1)),
      q(5) -> WorkDecor(None, Vector(t1, t2)),
      q(10) -> WorkDecor(Some(v2))
    )
  )

  val sampling: PathLengthSampling = PathLengthSampling(4, 7L, 3)

  val config: ScholarChainConfig = RunFixtures.config.copy(
    analysis = AnalysisConfig(
      Some(3),
      Some(2),
      Some(1),
      Some("parquet"),
      Some(sampling.sampleSize),
      Some(sampling.seed),
      Some(sampling.minComponentSize)
    )
  )

  val settings: AnalysisRunSettings = ok(AnalysisRunSettings.from(config))

  /** A canonical benchmark pair. */
  def pair(x: String, y: String): BenchmarkPair = sorted(x, y) match {
    case Vector(a, b) => BenchmarkPair(a, b)
    case other => throw new AssertionError(s"pair $other")
  }

  /** The fixture's benchmark pair list (ascending), bound to grouping run `g`. */
  def pairs(g: String): BenchmarkPairList =
    RunFixtures
      .benchmarkPairs(g)
      .copy(
        listVersion = "benchmark-pairs-paths.v1",
        pairs = Vector(pair(rA, rE), pair(rA, rF), pair(rB, rC), pair(rA, rH)).sortWith { (x, y) =>
          val c = Canonical.compareUtf8(x.researcherA, y.researcherA)
          if (c != 0) c < 0 else Canonical.compareUtf8(x.researcherB, y.researcherB) < 0
        },
        provenance = RunFixtures
          .benchmarkPairs(g)
          .provenance
          .copy(sourceRecordId = "benchmark-pairs-paths.v1")
      )

  /** The hand distance rows of the 2015-2019 component (pairs at distance 1, 2, 3 per source). */
  val handRows2015: Map[String, Vector[Long]] = Map(
    rA -> Vector(2L, 2L, 1L),
    rB -> Vector(3L, 2L),
    rC -> Vector(3L, 2L),
    rD -> Vector(3L, 2L),
    rE -> Vector(1L, 2L, 2L),
    rI -> Vector(2L, 2L, 1L)
  )

  /** The sum of the hand rows of `sources`, as distance counts. */
  def handCounts(sources: Vector[String]): Vector[DistanceCount] = {
    val rows = sources.map(handRows2015)
    val n = rows.map(_.size).max
    (0 until n).toVector.map(i => DistanceCount(i + 1, rows.map(_.lift(i).getOrElse(0L)).sum))
  }
}
