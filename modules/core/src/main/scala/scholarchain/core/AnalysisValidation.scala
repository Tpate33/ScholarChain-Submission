package scholarchain.core

import scala.collection.immutable.SortedMap
import scala.collection.mutable

import scholarchain.core.config.AnalysisRunSettings

import Validated.{all, when}

/**
 * The grouping-run datasets the collaboration analysis reads (validator input): its publication
 * teams, work assignments and researcher assignments, each complete and decoded through
 * [[Json.decode]] (so every record already passed its contract).
 */
final case class GraphInput(
    teams: Vector[PublicationTeam],
    works: Vector[WorkAssignment],
    researchers: Vector[ResearcherAssignment]
)

/**
 * The datasets of one analysis run (validator input for the cross-dataset checks). The DE-P6-03
 * datasets (canonical tables, window structures, path-length estimates, temporal comparisons,
 * entity summary inputs) default to empty, so callers of the DE-P6-02 shape keep compiling; every
 * record present is covered by [[AnalysisValidation.checkRunChain]].
 */
final case class AnalysisOutputs(
    metrics: Vector[ResearcherMetricsV2],
    edges: Vector[CoauthorEdgeV2],
    components: Vector[ComponentMembership],
    communities: Vector[CommunityMembership],
    bridges: Vector[BridgeMeasure],
    coreness: Vector[Coreness],
    paths: Vector[EvidencePath],
    windows: Vector[AnalysisWindow],
    summaryInputs: Vector[SemanticSummaryInput],
    nodes: Vector[GraphNodeV2] = Vector.empty,
    graphEdges: Vector[GraphEdgeV2] = Vector.empty,
    windowStructures: Vector[WindowStructure] = Vector.empty,
    pathLengthEstimates: Vector[PathLengthEstimate] = Vector.empty,
    windowComparisons: Vector[WindowComparison] = Vector.empty,
    temporalChanges: Vector[ResearcherTemporalChange] = Vector.empty,
    entitySummaryInputs: Vector[EntitySummaryInput] = Vector.empty
) {

  /** (lineage, provenance) of every record, for [[AnalysisValidation.checkRunChain]]. */
  def lineageAndProvenance: Vector[(String, AnalysisLineage, Provenance)] = {
    def of[A](name: String, xs: Vector[A])(l: A => AnalysisLineage, p: A => Provenance) =
      Option(xs).getOrElse(Vector.empty).filter(_ != null).map(x => (name, l(x), p(x)))
    of("metrics", metrics)(_.lineage, _.provenance) ++
      of("edges", edges)(_.lineage, _.provenance) ++
      of("components", components)(_.lineage, _.provenance) ++
      of("communities", communities)(_.lineage, _.provenance) ++
      of("bridges", bridges)(_.lineage, _.provenance) ++
      of("coreness", coreness)(_.lineage, _.provenance) ++
      of("paths", paths)(_.lineage, _.provenance) ++
      of("windows", windows)(_.lineage, _.provenance) ++
      of("summaryInputs", summaryInputs)(_.lineage, _.provenance) ++
      of("nodes", nodes)(_.lineage, _.provenance) ++
      of("graphEdges", graphEdges)(_.lineage, _.provenance) ++
      of("windowStructures", windowStructures)(_.lineage, _.provenance) ++
      of("pathLengthEstimates", pathLengthEstimates)(_.lineage, _.provenance) ++
      of("windowComparisons", windowComparisons)(_.lineage, _.provenance) ++
      of("temporalChanges", temporalChanges)(_.lineage, _.provenance) ++
      of("entitySummaryInputs", entitySummaryInputs)(_.lineage, _.provenance)
  }
}

/**
 * Pure dataset-level validation of one collaboration-analysis run (Phase 6, DE-P6-02). An empty
 * result means the datasets satisfy the contracts. Core validates only; the analysis itself is
 * Spark's (module `spark`). Every deterministic output (edges, metrics, components, bridge degrees
 * and participation, coreness, path distances, window counts) is recomputed here from the grouping
 * run's counted teams and compared exactly, so these checks are the executable definition of the
 * metric semantics. Algorithm-defined outputs (community assignment, the bridge score) are checked
 * for shape, configuration and consistency only.
 *
 * Graph scope (binding decision 1, O9): only teams of works with graphEligibility `counted`, from
 * the grouping run; identified researchers only for edges, C_r and W_r; unresolved mentions count
 * in T_r, A_r and M_r and in their separate fields only (binding decision 2).
 *
 * Preconditions: as [[AssignmentValidation]]: complete datasets, each record decoded; run
 * [[checkGraphInput]] first and stop on its errors. Every check starts with the record contracts of
 * its own dataset and stops on their errors.
 *
 * Cost: linear in the input plus, per window, O(sum of squared team sizes) for the pairs, O(E log
 * V) for coreness, and one bounded breadth-first search per path record. Intended for dev and
 * sample runs; it is not a distributed job.
 */
object AnalysisValidation {

  private def invalid(field: String, reason: String) = ValidationError.InvalidValue(field, reason)

  private def sortedUtf8(xs: Iterable[String]): Vector[String] =
    xs.toVector.sortWith((a, b) => Canonical.compareUtf8(a, b) < 0)

  private def wkey(w: TimeWindow): String = s"${w.startYear}-${w.endYear}"

  private def records[A](label: String, xs: Vector[A])(using c: RecordContract[A]) =
    if (xs == null || xs.exists(_ == null)) Vector(ValidationError.MissingField(label))
    else xs.zipWithIndex.flatMap { case (x, i) => c.validateAt(x, s"$label[$i]") }

  private def duplicates(field: String, keys: Vector[String]): Vector[ValidationError] =
    keys.groupBy(identity).toVector.filter(_._2.size > 1).sortBy(_._1).map { case (k, same) =>
      invalid(field, s"$k appears in ${same.size} records; one record per key")
    }

  /** The configured window and graph policy of a record. */
  private def configured(
      f: String,
      w: TimeWindow,
      pr: Provenance,
      settings: AnalysisRunSettings
  ): Vector[ValidationError] =
    all(
      when(!settings.windows.contains(w))(
        invalid(s"$f.window", s"${wkey(w)} is not a configured window (graph.windows)")
      ),
      when(AnalysisRules.policyOf(pr) != Some(settings.graphPolicyVersion))(
        invalid(s"$f.provenance.policyVersion", s"must be ${settings.graphPolicyVersion}")
      )
    )

  // ---------------------------------------------------------------- graph input

  /** The teams the graph is built from: those of counted works (decision O9). */
  def countedTeams(input: GraphInput): Vector[PublicationTeam] = {
    val counted = Option(input.works)
      .getOrElse(Vector.empty)
      .filter(w => w != null && w.graphEligibility == GraphEligibility.Counted)
      .map(_.workId)
      .toSet
    Option(input.teams).getOrElse(Vector.empty).filter(t => t != null && counted.contains(t.workId))
  }

  /**
   * The grouping run ID (RunId of the grouping manifest) and its decision run ID (manifest
   * parameter `decisionRunId`): the runs the analysis inputs must come from.
   */
  private def upstreamRuns(
      groupingManifest: RunManifest
  ): Either[Vector[ValidationError], (String, String)] =
    if (groupingManifest == null) Left(Vector(ValidationError.MissingField("groupingManifest")))
    else
      RunId.of(groupingManifest) match {
        case Left(e) => Left(Vector(e))
        case Right(g) =>
          Option(groupingManifest.parameters).flatMap(
            _.get(GroupingManifestKeys.DecisionRunId)
          ) match {
            case None =>
              Left(
                Vector(
                  ValidationError.MissingField(
                    s"groupingManifest.parameters.${GroupingManifestKeys.DecisionRunId}"
                  )
                )
              )
            case Some(d) => Right((g, d))
          }
      }

  /** Records whose `provenance.runId` is not `runId` (a null provenance counts as foreign). */
  private def foreignRun[A](label: String, xs: Vector[A], runId: String, run: String)(
      key: A => String,
      pr: A => Provenance
  ): Vector[ValidationError] =
    xs.filter(_ != null).flatMap { x =>
      when(Option(pr(x)).forall(_.runId != runId))(
        invalid(s"$label[${key(x)}].provenance.runId", s"must be the $run $runId")
      )
    }

  /**
   * The graph input is usable and comes from the grouping run of `groupingManifest` (DE-P6-02
   * repair 01, CR-P6-13 finding 2):
   *   - every team, work assignment and researcher assignment carries `provenance.runId` = the
   *     grouping run ID, and every assignment's `decisionRunId` is the grouping manifest's
   *     `decisionRunId`;
   *   - every team belongs to a counted work of the grouping run (a team of a member-of-counted or
   *     enrichment-only work, or of an unknown work, is refused, never silently counted);
   *   - exactly one team per counted work that has mentions in the grouping run (a researcher
   *     assignment with that `workId`): a duplicate or a missing team is refused, so P_r is never
   *     silently undercounted. This is the canonical-team rule of
   *     [[AssignmentValidation.checkTeams]]; a counted work without any mention has no team (a team
   *     has `teamSize >= 1`);
   *   - `teamSize` = identified researchers + unresolved mentions (so that every coauthor
   *     occurrence is either identified or unresolved), and every team researcher is an identified
   *     researcher of the grouping run.
   */
  def checkGraphInput(input: GraphInput, groupingManifest: RunManifest): Vector[ValidationError] =
    if (input == null || input.teams == null || input.works == null || input.researchers == null)
      Vector(ValidationError.MissingField("graphInput"))
    else {
      val teams = records("teams", input.teams)
      if (teams.nonEmpty) teams
      else
        upstreamRuns(groupingManifest) match {
          case Left(e) => e
          case Right((groupingRunId, decisionRunId)) =>
            graphInputProblems(input, groupingRunId, decisionRunId)
        }
    }

  private def graphInputProblems(
      input: GraphInput,
      groupingRunId: String,
      decisionRunId: String
  ): Vector[ValidationError] = {
    val byWork = input.works.filter(_ != null).map(w => w.workId -> w).toMap
    val identified = input.researchers
      .filter(r => r != null && r.status != null && r.status.identified)
      .flatMap(_.researcherId)
      .toSet
    val teamWorks = input.teams.map(_.workId).toSet
    val mentionWorks = input.researchers.filter(_ != null).flatMap(_.workId).toSet
    val run = "grouping run"
    all(
      foreignRun("teams", input.teams, groupingRunId, run)(_.workId, _.provenance),
      foreignRun("works", input.works, groupingRunId, run)(_.workId, _.provenance),
      foreignRun("researchers", input.researchers, groupingRunId, run)(_.memberId, _.provenance),
      input.works.filter(w => w != null && w.decisionRunId != decisionRunId).map { w =>
        invalid(s"works[${w.workId}].decisionRunId", s"must be the decision run $decisionRunId")
      },
      input.researchers.filter(r => r != null && r.decisionRunId != decisionRunId).map { r =>
        invalid(
          s"researchers[${r.memberId}].decisionRunId",
          s"must be the decision run $decisionRunId"
        )
      },
      sortedUtf8(
        byWork.values
          .filter(w => w.graphEligibility == GraphEligibility.Counted)
          .map(_.workId)
          .filter(w => mentionWorks.contains(w) && !teamWorks.contains(w))
      ).map(w => invalid("teams", s"counted work $w has mentions in the grouping run but no team")),
      duplicates("teams.workId", input.teams.map(_.workId)),
      input.teams.flatMap { t =>
        val f = s"teams[${t.workId}]"
        all(
          byWork.get(t.workId) match {
            case None => Vector(invalid(f, "the work has no work assignment"))
            case Some(w) =>
              when(w.graphEligibility != GraphEligibility.Counted)(
                invalid(
                  f,
                  s"graphEligibility is ${w.graphEligibility.tag}: only counted works enter the graph"
                )
              )
          },
          when(t.teamSize != t.researcherIds.size + t.unresolvedMentionIds.size)(
            invalid(f, "teamSize must equal identified researchers plus unresolved mentions")
          ),
          t.researcherIds.filterNot(identified.contains).map { r =>
            invalid(f, s"$r is not an identified researcher of the grouping run")
          }
        )
      }
    )
  }

  // ---------------------------------------------------------------- window model

  private[core] final case class PairKey(a: String, b: String)

  private[core] final class EdgeAcc {
    val works = mutable.ArrayBuffer.empty[String]
    val byYear = mutable.TreeMap.empty[Int, Long]
  }

  /** The recomputed graph of one window from the counted teams. */
  private[core] final class WindowModel(val window: TimeWindow, counted: Vector[PublicationTeam]) {
    val teams: Vector[PublicationTeam] = counted.filter(_.year.exists(window.contains))
    val edges: Map[PairKey, EdgeAcc] = {
      val m = mutable.HashMap.empty[PairKey, EdgeAcc]
      teams.foreach { t =>
        val ids = t.researcherIds.distinct
        for (i <- ids.indices; j <- i + 1 until ids.size) {
          val (a, b) =
            if (Canonical.compareUtf8(ids(i), ids(j)) < 0) (ids(i), ids(j)) else (ids(j), ids(i))
          val acc = m.getOrElseUpdate(PairKey(a, b), new EdgeAcc)
          acc.works += t.workId
          val y = t.year.get
          acc.byYear.update(y, acc.byYear.getOrElse(y, 0L) + 1L)
        }
      }
      m.toMap
    }
    val vertices: Set[String] = teams.flatMap(_.researcherIds).toSet
    val adjacency: Map[String, Set[String]] = {
      val m = mutable.HashMap.empty[String, Set[String]]
      vertices.foreach(v => m(v) = Set.empty)
      edges.keys.foreach { k =>
        m(k.a) = m(k.a) + k.b; m(k.b) = m(k.b) + k.a
      }
      m.toMap
    }
    val teamsOf: Map[String, Vector[PublicationTeam]] =
      teams.flatMap(t => t.researcherIds.distinct.map(_ -> t)).groupMap(_._1)(_._2)

    /** Researcher -> sorted member list of its connected component. */
    lazy val components: Map[String, Vector[String]] = {
      val parent = mutable.HashMap.empty[String, String]
      vertices.foreach(v => parent(v) = v)
      def find(v: String): String = {
        var r = v
        while (parent(r) != r) r = parent(r)
        var x = v
        while (parent(x) != r) { val n = parent(x); parent(x) = r; x = n }
        r
      }
      edges.keys.foreach(k => parent(find(k.a)) = find(k.b))
      val groups = vertices.toVector.groupBy(find).values.map(sortedUtf8).toVector
      groups.flatMap(g => g.map(_ -> g)).toMap
    }

    /**
     * k-core coreness by peeling (ties broken by UTF-8 order; the result does not depend on it).
     */
    lazy val coreness: Map[String, Long] = {
      given Ordering[String] = Ordering.fromLessThan((x, y) => Canonical.compareUtf8(x, y) < 0)
      val degree = mutable.HashMap.empty[String, Int]
      adjacency.foreach { case (v, ns) => degree(v) = ns.size }
      val queue = mutable.TreeSet.empty[(Int, String)]
      degree.foreach { case (v, d) => queue += ((d, v)) }
      val removed = mutable.HashSet.empty[String]
      val core = mutable.HashMap.empty[String, Long]
      var k = 0
      while (queue.nonEmpty) {
        val (d, v) = queue.head
        queue -= ((d, v))
        k = math.max(k, d)
        core(v) = k.toLong
        removed += v
        adjacency(v).filterNot(removed.contains).foreach { n =>
          val dn = degree(n)
          queue -= ((dn, n))
          degree(n) = dn - 1
          queue += ((dn - 1, n))
        }
      }
      core.toMap
    }

    /** Shortest-path distance from `s` to `t`, searched up to `limit` hops. */
    def distance(s: String, t: String, limit: Int): Option[Int] =
      if (!vertices.contains(s) || !vertices.contains(t)) None
      else if (s == t) Some(0)
      else {
        val seen = mutable.HashSet(s)
        var frontier = Vector(s)
        var d = 0
        var found: Option[Int] = None
        while (found.isEmpty && frontier.nonEmpty && d < limit) {
          d += 1
          val next = frontier.flatMap(adjacency(_)).filterNot(seen.contains).distinct
          if (next.contains(t)) found = Some(d)
          seen ++= next
          frontier = next
        }
        found
      }
  }

  private[core] def models(
      input: GraphInput,
      settings: AnalysisRunSettings
  ): Map[TimeWindow, WindowModel] = {
    val teams = countedTeams(input)
    settings.windows.map(w => w -> new WindowModel(w, teams)).toMap
  }

  // ---------------------------------------------------------------- C2 edges

  /**
   * Coauthor edges v2 (C2): identified endpoints only, counted supporting works only, the
   * configured windows and graph policy, one record per pair and window, and exactly the edges,
   * per-year weights and supporting works recomputed from the counted teams of each window.
   */
  def checkEdges(
      edges: Vector[CoauthorEdgeV2],
      input: GraphInput,
      settings: AnalysisRunSettings
  ): Vector[ValidationError] = {
    val rec = records("edges", edges)
    if (rec.nonEmpty) rec
    else {
      val identified = Option(input.researchers)
        .getOrElse(Vector.empty)
        .filter(r => r != null && r.status.identified)
        .flatMap(_.researcherId)
        .toSet
      val counted = countedTeams(input).map(_.workId).toSet
      val ms = models(input, settings)
      val perRecord = edges.flatMap { e =>
        val f = s"edges[${e.edgeId}]"
        all(
          configured(f, e.window, e.provenance, settings),
          Vector(e.researcherA, e.researcherB).filterNot(identified.contains).map { r =>
            invalid(
              f,
              s"endpoint $r is not an identified researcher (edges join identified researchers only)"
            )
          },
          e.supportingWorkIds.filterNot(counted.contains).map { w =>
            invalid(f, s"supporting work $w is not a counted work with a team")
          }
        )
      }
      val byKey = edges.groupBy(e => (e.window, PairKey(e.researcherA, e.researcherB)))
      val keyDup = byKey.toVector.filter(_._2.size > 1).map { case ((w, k), _) =>
        invalid("edges", s"${k.a} ${k.b} in ${wkey(w)} has more than one record")
      }
      val expected = settings.windows.flatMap { w =>
        val m = ms(w)
        val got =
          edges.filter(_.window == w).map(e => PairKey(e.researcherA, e.researcherB) -> e).toMap
        val missing = m.edges.keys.toVector
          .filterNot(got.contains)
          .sortBy(k => (k.a, k.b))
          .map(k => invalid("edges", s"missing edge ${k.a} ${k.b} in ${wkey(w)}"))
        val compared = got.toVector.sortBy(_._2.edgeId).flatMap { case (k, e) =>
          val f = s"edges[${e.edgeId}]"
          m.edges.get(k) match {
            case None =>
              Vector(invalid(f, s"no counted team of ${wkey(w)} holds both researchers"))
            case Some(acc) =>
              all(
                when(e.supportingWorkIds != sortedUtf8(acc.works))(
                  invalid(f, "supportingWorkIds differ from the counted teams of the window")
                ),
                when(e.publicationsByYear != acc.byYear.toVector.map { case (y, c) =>
                  YearCount(y, c)
                })(
                  invalid(f, "publicationsByYear differs from the counted teams of the window")
                )
              )
          }
        }
        missing ++ compared
      }
      all(perRecord, keyDup, expected)
    }
  }

  // ---------------------------------------------------------------- C1 metrics, C5 identity

  /**
   * Researcher metrics v2 (C1): one record per identified researcher with a counted work in each
   * configured window, with exactly the recomputed P_r, T_r, the unresolved occurrences, the works
   * with unresolved coauthors, A_r, M_r and its works, C_r and W_r. Identified and unresolved
   * occurrences are never mixed: C_r and W_r come from identified researchers only.
   */
  def checkMetrics(
      metrics: Vector[ResearcherMetricsV2],
      input: GraphInput,
      settings: AnalysisRunSettings
  ): Vector[ValidationError] = {
    val rec = records("metrics", metrics)
    if (rec.nonEmpty) rec
    else {
      val ms = models(input, settings)
      val dup = duplicates("metrics", metrics.map(m => s"${m.researcherId} ${wkey(m.window)}"))
      val cfg = metrics.flatMap(m =>
        configured(
          s"metrics[${m.researcherId} ${wkey(m.window)}]",
          m.window,
          m.provenance,
          settings
        )
      )
      val perWindow = settings.windows.flatMap { w =>
        val m = ms(w)
        val got = metrics.filter(_.window == w).map(x => x.researcherId -> x).toMap
        val missing = sortedUtf8(m.vertices.filterNot(got.contains)).map { r =>
          invalid("metrics", s"$r has counted works in ${wkey(w)} but no metrics record")
        }
        val compared = sortedUtf8(got.keys).flatMap { r =>
          val x = got(r)
          val f = s"metrics[$r ${wkey(w)}]"
          m.teamsOf.get(r) match {
            case None => Vector(invalid(f, s"no counted team of ${wkey(w)} holds the researcher"))
            case Some(ts) =>
              val occ = ts.map(t => t.teamSize.toLong - 1L)
              val total = occ.sum
              val unresolved = ts.map(_.unresolvedMentionIds.size.toLong).sum
              val max = occ.max
              val maxWorks = sortedUtf8(ts.filter(t => t.teamSize.toLong - 1L == max).map(_.workId))
              val identified = ts.map(t => t.researcherIds.distinct.size.toLong - 1L).sum
              def eq(name: String, got: Any, exp: Any) =
                when(got != exp)(
                  invalid(s"$f.$name", s"expected $exp, recomputed from counted teams")
                )
              all(
                eq("publicationCount", x.publicationCount, ts.size.toLong),
                eq("totalCoauthorOccurrences", x.totalCoauthorOccurrences, total),
                eq("unresolvedCoauthorOccurrences", x.unresolvedCoauthorOccurrences, unresolved),
                eq(
                  "worksWithUnresolvedCoauthors",
                  x.worksWithUnresolvedCoauthors,
                  ts.count(_.unresolvedMentionIds.nonEmpty).toLong
                ),
                eq(
                  "averageCoauthorsPerPaper",
                  x.averageCoauthorsPerPaper,
                  ResearcherMetricsV2.average(total, ts.size.toLong)
                ),
                eq("maximumCoauthorsOnOnePaper", x.maximumCoauthorsOnOnePaper, max),
                eq("maximumTeamWorkIds", x.maximumTeamWorkIds, maxWorks),
                eq("distinctCoauthorCount", x.distinctCoauthorCount, m.adjacency(r).size.toLong),
                eq("weightedDegree", x.weightedDegree, identified)
              )
          }
        }
        missing ++ compared
      }
      all(dup, cfg, perWindow)
    }
  }

  /**
   * Identity confidence (C5) of every metrics record equals [[IdentityConfidence.derive]] from the
   * researcher's grouping members and the decision run's confidences. The inputs are bound to the
   * run chain first (DE-P6-02 repair 01, CR-P6-13 finding 2): every researcher assignment carries
   * `provenance.runId` = the grouping run ID, and every decision carries `provenance.runId` = the
   * grouping manifest's `decisionRunId`; otherwise nothing is derived.
   */
  def checkIdentity(
      metrics: Vector[ResearcherMetricsV2],
      researchers: Vector[ResearcherAssignment],
      decisions: Vector[MatchDecision],
      groupingManifest: RunManifest
  ): Vector[ValidationError] =
    upstreamRuns(groupingManifest) match {
      case Left(e) => e
      case Right((groupingRunId, decisionRunId)) =>
        val chain = all(
          foreignRun(
            "researchers",
            Option(researchers).getOrElse(Vector.empty),
            groupingRunId,
            "grouping run"
          )(
            _.memberId,
            _.provenance
          ),
          foreignRun(
            "decisions",
            Option(decisions).getOrElse(Vector.empty),
            decisionRunId,
            "decision run"
          )(
            _.decisionId,
            _.provenance
          )
        )
        if (chain.nonEmpty) chain else identityProblems(metrics, researchers, decisions)
    }

  private def identityProblems(
      metrics: Vector[ResearcherMetricsV2],
      researchers: Vector[ResearcherAssignment],
      decisions: Vector[MatchDecision]
  ): Vector[ValidationError] = {
    val members = Option(researchers)
      .getOrElse(Vector.empty)
      .filter(r => r != null && r.researcherId.isDefined)
      .groupBy(_.researcherId.get)
    val conf = Option(decisions)
      .getOrElse(Vector.empty)
      .filter(_ != null)
      .map(d => d.decisionId -> d.confidence)
      .toMap
    Option(metrics).getOrElse(Vector.empty).filter(_ != null).flatMap { m =>
      val f = s"metrics[${m.researcherId} ${wkey(m.window)}].identity"
      IdentityConfidence.derive(
        m.researcherId,
        members.getOrElse(m.researcherId, Vector.empty),
        conf.get
      ) match {
        case Left(e) => e
        case Right(exp) =>
          when(exp != m.identity)(invalid(f, s"expected $exp from the grouping run"))
      }
    }
  }

  // ---------------------------------------------------------------- C3 structure

  /**
   * Component memberships: one per researcher with a metrics record in each window, exactly the
   * connected components of the window's edges, with the ID formula and size.
   */
  def checkComponents(
      components: Vector[ComponentMembership],
      input: GraphInput,
      settings: AnalysisRunSettings
  ): Vector[ValidationError] = {
    val rec = records("components", components)
    if (rec.nonEmpty) rec
    else {
      val ms = models(input, settings)
      all(
        duplicates("components", components.map(c => s"${c.researcherId} ${wkey(c.window)}")),
        components.flatMap(c =>
          configured(s"components[${c.researcherId}]", c.window, c.provenance, settings)
        ),
        settings.windows.flatMap { w =>
          val m = ms(w)
          val got = components.filter(_.window == w).map(c => c.researcherId -> c).toMap
          sortedUtf8(m.vertices.filterNot(got.contains)).map(r =>
            invalid("components", s"$r has no component membership in ${wkey(w)}")
          ) ++ sortedUtf8(got.keys).flatMap { r =>
            val c = got(r)
            val f = s"components[$r ${wkey(w)}]"
            m.components.get(r) match {
              case None => Vector(invalid(f, s"not a researcher of ${wkey(w)}"))
              case Some(group) =>
                all(
                  when(c.componentSize != group.size.toLong)(
                    invalid(s"$f.componentSize", s"expected ${group.size}")
                  ),
                  AnalysisRules.formula(
                    s"$f.componentId",
                    c.componentId,
                    ComponentId.of(w, settings.graphPolicyVersion, group)
                  )
                )
            }
          }
        }
      )
    }
  }

  /**
   * Community memberships: one per researcher with a component membership in each window; the
   * configured algorithm and parameters on every record; each community inside one component (the
   * member's own); size and ID formula per community. Which partition the algorithm chose is not
   * re-derived.
   */
  def checkCommunities(
      communities: Vector[CommunityMembership],
      components: Vector[ComponentMembership],
      settings: AnalysisRunSettings
  ): Vector[ValidationError] = {
    val rec = records("communities", communities)
    if (rec.nonEmpty) rec
    else {
      val componentOf =
        Option(components)
          .getOrElse(Vector.empty)
          .filter(_ != null)
          .map(c => (c.window, c.researcherId) -> c.componentId)
          .toMap
      all(
        duplicates("communities", communities.map(c => s"${c.researcherId} ${wkey(c.window)}")),
        communities.flatMap { c =>
          val f = s"communities[${c.researcherId} ${wkey(c.window)}]"
          all(
            configured(f, c.window, c.provenance, settings),
            when(c.algorithm != settings.communityAlgorithm)(
              invalid(
                s"$f.algorithm",
                s"must be ${settings.communityAlgorithm} (community.algorithm)"
              )
            ),
            when(c.parameters != settings.communityParameters)(
              invalid(s"$f.parameters", s"must be ${settings.communityParameters} (community.*)")
            ),
            componentOf.get((c.window, c.researcherId)) match {
              case None =>
                Vector(invalid(f, "the researcher has no component membership in the window"))
              case Some(comp) =>
                when(comp != c.componentId)(
                  invalid(s"$f.componentId", s"the researcher's component is $comp")
                )
            }
          )
        }, {
          val have = communities.map(c => (c.window, c.researcherId)).toSet
          componentOf.keys.toVector
            .filterNot(have.contains)
            .sortBy(k => (wkey(k._1), k._2))
            .map { case (w, r) =>
              invalid("communities", s"$r has no community membership in ${wkey(w)}")
            }
        },
        communities
          .groupBy(c => (c.window, c.communityId))
          .toVector
          .sortBy(g => (wkey(g._1._1), g._1._2))
          .flatMap { case ((w, id), group) =>
            val f = s"communities[$id ${wkey(w)}]"
            val members = sortedUtf8(group.map(_.researcherId))
            all(
              when(group.exists(_.communitySize != group.size.toLong))(
                invalid(s"$f.communitySize", s"expected ${group.size}")
              ),
              when(group.map(_.componentId).distinct.size > 1)(
                invalid(f, "a community lies inside one connected component")
              ),
              AnalysisRules.formula(
                s"$f.communityId",
                id,
                CommunityId.of(
                  w,
                  settings.graphPolicyVersion,
                  settings.communityAlgorithm,
                  settings.communityParameters,
                  members
                )
              )
            )
          }
      )
    }
  }

  /**
   * Bridge measures: one per researcher with a community membership in each window; the configured
   * measure and parameters; the researcher's own community; and exactly the recomputed degree,
   * cross-community degree, communities reached and participation coefficient. The score is
   * algorithm-defined and checked for shape only (record contract).
   */
  def checkBridges(
      bridges: Vector[BridgeMeasure],
      communities: Vector[CommunityMembership],
      input: GraphInput,
      settings: AnalysisRunSettings
  ): Vector[ValidationError] = {
    val rec = records("bridges", bridges)
    if (rec.nonEmpty) rec
    else {
      val ms = models(input, settings)
      val communityOf =
        Option(communities)
          .getOrElse(Vector.empty)
          .filter(_ != null)
          .map(c => (c.window, c.researcherId) -> c.communityId)
          .toMap
      all(
        duplicates("bridges", bridges.map(b => s"${b.researcherId} ${wkey(b.window)}")), {
          val have = bridges.map(b => (b.window, b.researcherId)).toSet
          communityOf.keys.toVector
            .filterNot(have.contains)
            .sortBy(k => (wkey(k._1), k._2))
            .map { case (w, r) => invalid("bridges", s"$r has no bridge measure in ${wkey(w)}") }
        },
        bridges.flatMap { b =>
          val f = s"bridges[${b.researcherId} ${wkey(b.window)}]"
          val cfg = all(
            configured(f, b.window, b.provenance, settings),
            when(b.measure != settings.bridgeAlgorithm)(
              invalid(s"$f.measure", s"must be ${settings.bridgeAlgorithm} (bridge.algorithm)")
            ),
            when(b.parameters != settings.bridgeParameters)(
              invalid(s"$f.parameters", s"must be ${settings.bridgeParameters} (bridge.*)")
            )
          )
          val derived = (communityOf.get((b.window, b.researcherId)), ms.get(b.window)) match {
            case (Some(own), Some(m)) if m.vertices.contains(b.researcherId) =>
              val ns = m.adjacency(b.researcherId).toVector
              val commOf = ns.map(n => communityOf.get((b.window, n)))
              if (commOf.exists(_.isEmpty))
                Vector(invalid(f, "a coauthor has no community membership"))
              else {
                val cs = commOf.flatten
                val cross = cs.filter(_ != own)
                val sumSq = cs.groupBy(identity).values.map(v => v.size.toLong * v.size.toLong).sum
                all(
                  when(b.communityId != own)(
                    invalid(s"$f.communityId", s"the researcher's community is $own")
                  ),
                  when(b.degree != ns.size.toLong)(invalid(s"$f.degree", s"expected ${ns.size}")),
                  when(b.crossCommunityDegree != cross.size.toLong)(
                    invalid(s"$f.crossCommunityDegree", s"expected ${cross.size}")
                  ),
                  when(b.communitiesReached != cross.distinct.size.toLong)(
                    invalid(s"$f.communitiesReached", s"expected ${cross.distinct.size}")
                  ),
                  when(
                    b.participationCoefficient != BridgeMeasure.participation(ns.size.toLong, sumSq)
                  )(
                    invalid(
                      s"$f.participationCoefficient",
                      s"expected ${BridgeMeasure.participation(ns.size.toLong, sumSq)}"
                    )
                  )
                )
              }
            case _ => Vector(invalid(f, "the researcher has no community membership in the window"))
          }
          cfg ++ derived
        }
      )
    }
  }

  /**
   * Coreness: one per researcher with a metrics record in each window, exactly the k-core numbers.
   */
  def checkCoreness(
      coreness: Vector[Coreness],
      input: GraphInput,
      settings: AnalysisRunSettings
  ): Vector[ValidationError] = {
    val rec = records("coreness", coreness)
    if (rec.nonEmpty) rec
    else {
      val ms = models(input, settings)
      all(
        duplicates("coreness", coreness.map(c => s"${c.researcherId} ${wkey(c.window)}")),
        coreness.flatMap(c =>
          configured(s"coreness[${c.researcherId}]", c.window, c.provenance, settings)
        ),
        settings.windows.flatMap { w =>
          val m = ms(w)
          val got = coreness.filter(_.window == w).map(c => c.researcherId -> c).toMap
          sortedUtf8(m.vertices.filterNot(got.contains)).map(r =>
            invalid("coreness", s"$r has no coreness record in ${wkey(w)}")
          ) ++ sortedUtf8(got.keys).flatMap { r =>
            val c = got(r)
            val f = s"coreness[$r ${wkey(w)}]"
            if (!m.vertices.contains(r)) Vector(invalid(f, s"not a researcher of ${wkey(w)}"))
            else
              all(
                when(c.coreness != m.coreness(r))(
                  invalid(s"$f.coreness", s"expected ${m.coreness(r)}")
                ),
                when(c.degree != m.adjacency(r).size.toLong)(
                  invalid(s"$f.degree", s"expected ${m.adjacency(r).size}")
                )
              )
          }
        }
      )
    }
  }

  /**
   * Evidence paths (C3/C6): the configured windows, policy and bounds; every hop an edge of the
   * window whose supporting works include the hop's evidence; every node a researcher of the window
   * whose identity confidence equals its metrics record's; and `hopCount` the shortest-path
   * distance. Selection (DE-P6-02 repair 01, CR-P6-13 finding 4): the benchmark pair list passes
   * its contract and names identified researchers of the grouping run; no path is stored for a pair
   * outside the list; and for every listed pair and configured window the stored paths, by ordinal
   * 0..n-1, are exactly [[selectShortestPaths]] (the first `maxPathsPerPair` shortest paths in
   * node-sequence order, none when the pair is not connected within `maxHops`).
   */
  def checkPaths(
      paths: Vector[EvidencePath],
      metrics: Vector[ResearcherMetricsV2],
      input: GraphInput,
      settings: AnalysisRunSettings,
      benchmarkPairs: BenchmarkPairList
  ): Vector[ValidationError] = {
    val rec = records("paths", paths)
    if (rec.nonEmpty) rec
    else {
      val ms = models(input, settings)
      val identityOf =
        Option(metrics)
          .getOrElse(Vector.empty)
          .filter(_ != null)
          .map(m => (m.window, m.researcherId) -> m.identity)
          .toMap
      val perPath = paths.flatMap { p =>
        val f = s"paths[${p.pathId}]"
        val cfg = all(
          configured(f, p.window, p.provenance, settings),
          when(p.bounds != settings.pathBounds)(
            invalid(s"$f.bounds", s"must be ${settings.pathBounds} (analysis.*)")
          )
        )
        val graph = ms.get(p.window).toVector.flatMap { m =>
          all(
            p.nodeIds.zipWithIndex.flatMap { case (n, i) =>
              identityOf.get((p.window, n)) match {
                case None =>
                  Vector(invalid(s"$f.nodeIds[$i]", s"$n has no metrics record in the window"))
                case Some(id) =>
                  when(p.nodeIdentities.lift(i) != Some(id))(
                    invalid(s"$f.nodeIdentities[$i]", "differs from the node's metrics identity")
                  )
              }
            },
            p.hops.zipWithIndex.flatMap { case (h, i) =>
              val (x, y) = (p.nodeIds(i), p.nodeIds(i + 1))
              val key = if (Canonical.compareUtf8(x, y) < 0) PairKey(x, y) else PairKey(y, x)
              m.edges.get(key) match {
                case None => Vector(invalid(s"$f.hops[$i]", s"no edge $x $y in ${wkey(p.window)}"))
                case Some(acc) =>
                  h.evidenceWorkIds.filterNot(acc.works.toSet.contains).map { w =>
                    invalid(s"$f.hops[$i].evidenceWorkIds", s"$w does not support the edge")
                  }
              }
            },
            m.distance(
              p.sourceResearcherId,
              p.targetResearcherId,
              settings.pathBounds.maxHops
            ) match {
              case Some(d) =>
                when(d != p.hopCount)(invalid(s"$f.hopCount", s"the shortest path has $d hop(s)"))
              case None =>
                Vector(invalid(s"$f.hopCount", "the pair is not connected within the bound"))
            }
          )
        }
        cfg ++ graph
      }
      val list = records("benchmarkPairs", Vector(benchmarkPairs))
      if (list.nonEmpty) all(perPath, list)
      else {
        val configuredPairs = benchmarkPairs.pairs.map(x => (x.researcherA, x.researcherB))
        val pairSet = configuredPairs.toSet
        val identified = Option(input.researchers)
          .getOrElse(Vector.empty)
          .filter(r => r != null && r.status != null && r.status.identified)
          .flatMap(_.researcherId)
          .toSet
        val pairResearchers = benchmarkPairs.pairs.zipWithIndex.flatMap { case (x, i) =>
          Vector(x.researcherA, x.researcherB).filterNot(identified.contains).map { r =>
            invalid(
              s"benchmarkPairs.pairs[$i]",
              s"$r is not an identified researcher of the grouping run"
            )
          }
        }
        val unconfigured = sortedUtf8(
          paths
            .filterNot(p => pairSet.contains((p.sourceResearcherId, p.targetResearcherId)))
            .map(_.pathId)
        ).map { id =>
          invalid(
            s"paths[$id]",
            s"the pair is not in benchmark pair list ${benchmarkPairs.listVersion}: paths are stored for configured pairs only"
          )
        }
        val byKey = paths.groupBy(p => (p.window, p.sourceResearcherId, p.targetResearcherId))
        val k = settings.pathBounds.maxPathsPerPair
        val selection = settings.windows.flatMap { w =>
          val m = ms(w)
          configuredPairs.flatMap { case (s, t) =>
            val f = s"paths[$s $t ${wkey(w)}]"
            val group = byKey.getOrElse((w, s, t), Vector.empty)
            val stored = group.sortBy(_.ordinal)
            val expected =
              selectShortestPaths(m.adjacency, s, t, settings.pathBounds.maxHops, k)
            def show(xs: Vector[Vector[String]]) =
              xs.map(_.mkString("[", " ", "]")).mkString(", ")
            all(
              duplicates(s"$f.pathId", group.map(_.pathId)),
              when(group.size > k)(invalid(f, s"at most $k path(s) per pair")),
              when(stored.map(_.ordinal) != stored.indices.toVector)(
                invalid(s"$f.ordinal", "ordinals are 0..n-1 in node-sequence order")
              ),
              when(stored.map(_.nodeIds) != expected)(
                invalid(
                  f,
                  s"stored paths must be the first ${expected.size} shortest path(s) in node-sequence order: expected ${show(expected)}"
                )
              )
            )
          }
        }
        all(perPath, pairResearchers, unconfigured, selection)
      }
    }
  }

  /**
   * The stored shortest paths of one pair in one window (DE-P6-02 repair 01, CR-P6-13 finding 4),
   * the executable definition Spark must reproduce: the first `k` shortest paths from `source` to
   * `target` with at most `maxHops` hops, in [[EvidencePath.nodeOrder]] (lexicographic on the node
   * sequence, element by element by unsigned UTF-8 bytes). Empty when either researcher is not in
   * the graph, when they are not connected within `maxHops`, or when `source == target`.
   *
   * Method (no enumeration of all shortest paths): a breadth-first search from `target`, bounded by
   * `maxHops`, gives each node's distance to `target`; the shortest paths are exactly the walks
   * from `source` whose distance drops by one at every hop. A depth-first walk that tries
   * neighbours in ascending UTF-8 order therefore emits them in node-sequence order, and stops
   * after `k`. Cost: O(V + E) for the search plus O(k * maxHops * degree) for the walk. Any
   * implementation (for example a distributed one) must produce the same set and order.
   *
   * @param adjacency
   *   undirected neighbours of every node of the window graph
   */
  def selectShortestPaths(
      adjacency: Map[String, Set[String]],
      source: String,
      target: String,
      maxHops: Int,
      k: Int
  ): Vector[Vector[String]] =
    if (
      adjacency == null || source == null || target == null || source == target || maxHops < 1 ||
      k < 1 || !adjacency.contains(source) || !adjacency.contains(target)
    ) Vector.empty
    else {
      val dist = mutable.HashMap(target -> 0)
      var frontier = Vector(target)
      var d = 0
      while (frontier.nonEmpty && d < maxHops && !dist.contains(source)) {
        d += 1
        val next =
          frontier.flatMap(adjacency.getOrElse(_, Set.empty)).filterNot(dist.contains).distinct
        next.foreach(n => dist(n) = d)
        frontier = next
      }
      dist.get(source) match {
        case None => Vector.empty
        case Some(_) =>
          val out = mutable.ArrayBuffer.empty[Vector[String]]
          def walk(path: Vector[String]): Unit =
            if (out.size < k) {
              val v = path.last
              if (v == target) out += path
              else {
                val r = dist(v)
                sortedUtf8(
                  adjacency.getOrElse(v, Set.empty).filter(n => dist.get(n).contains(r - 1))
                )
                  .foreach(n => walk(path :+ n))
              }
            }
          walk(Vector(source))
          out.toVector
      }
    }

  // ---------------------------------------------------------------- C4 windows

  /**
   * Window records (C4): exactly one per configured window, in configured order, with counts equal
   * to the counted teams and the other datasets of the run.
   */
  def checkWindows(
      windows: Vector[AnalysisWindow],
      outputs: AnalysisOutputs,
      input: GraphInput,
      settings: AnalysisRunSettings
  ): Vector[ValidationError] = {
    val rec = records("windows", windows)
    if (rec.nonEmpty) rec
    else {
      val ms = models(input, settings)
      val withoutYear = countedTeams(input).count(_.year.isEmpty).toLong
      def in[A](xs: Vector[A], w: TimeWindow)(win: A => TimeWindow) =
        Option(xs).getOrElse(Vector.empty).filter(x => x != null && win(x) == w)
      all(
        when(windows.map(_.window) != settings.windows)(
          invalid(
            "windows",
            "one record per configured window, in configured order (graph.windows)"
          )
        ),
        windows.zipWithIndex.flatMap { case (r, i) =>
          val f = s"windows[${wkey(r.window)}]"
          def eq(name: String, got: Long, exp: Long) =
            when(got != exp)(invalid(s"$f.$name", s"expected $exp"))
          ms.get(r.window) match {
            case None => configured(f, r.window, r.provenance, settings)
            case Some(m) =>
              all(
                configured(f, r.window, r.provenance, settings),
                when(r.ordinal != i)(invalid(s"$f.ordinal", s"expected $i")),
                eq("countedWorkCount", r.countedWorkCount, m.teams.size.toLong),
                eq("countedWorksWithoutYear", r.countedWorksWithoutYear, withoutYear),
                eq("researcherCount", r.researcherCount, m.vertices.size.toLong),
                eq("edgeCount", r.edgeCount, m.edges.size.toLong),
                eq("componentCount", r.componentCount, m.components.values.toSet.size.toLong),
                eq(
                  "communityCount",
                  r.communityCount,
                  in(outputs.communities, r.window)(_.window)
                    .map(_.communityId)
                    .distinct
                    .size
                    .toLong
                ),
                eq(
                  "unresolvedCoauthorOccurrences",
                  r.unresolvedCoauthorOccurrences,
                  m.teams.map(_.unresolvedMentionIds.size.toLong).sum
                )
              )
          }
        }
      )
    }
  }

  // ---------------------------------------------------------------- C6 summary inputs

  /**
   * Semantic-summary inputs (C6): every subject and every cited ID resolves to a stored record of
   * the same window (metrics, edge, path, component, community, bridge measure, coreness, or a
   * counted work of the window), and a researcher subject's identity equals its metrics identity.
   */
  def checkSummaryInputs(
      inputs: Vector[SemanticSummaryInput],
      outputs: AnalysisOutputs,
      input: GraphInput,
      settings: AnalysisRunSettings
  ): Vector[ValidationError] = {
    val rec = records("summaryInputs", inputs)
    if (rec.nonEmpty) rec
    else {
      val ms = models(input, settings)
      def ids[A](xs: Vector[A])(w: A => TimeWindow, id: A => String): Set[(TimeWindow, String)] =
        Option(xs).getOrElse(Vector.empty).filter(_ != null).map(x => (w(x), id(x))).toSet
      val index: Map[SummaryEvidenceKind, Set[(TimeWindow, String)]] = Map(
        SummaryEvidenceKind.ResearcherMetrics -> ids(outputs.metrics)(_.window, _.researcherId),
        SummaryEvidenceKind.CoauthorEdge -> ids(outputs.edges)(_.window, _.edgeId),
        SummaryEvidenceKind.EvidencePath -> ids(outputs.paths)(_.window, _.pathId),
        SummaryEvidenceKind.Component -> ids(outputs.components)(_.window, _.componentId),
        SummaryEvidenceKind.Community -> ids(outputs.communities)(_.window, _.communityId),
        SummaryEvidenceKind.BridgeMeasure -> ids(outputs.bridges)(_.window, _.researcherId),
        SummaryEvidenceKind.Coreness -> ids(outputs.coreness)(_.window, _.researcherId),
        SummaryEvidenceKind.CountedWork -> ms.toVector.flatMap { case (w, m) =>
          m.teams.map(t => (w, t.workId))
        }.toSet
      )
      val subjectKind: Map[DocumentType, SummaryEvidenceKind] = Map(
        DocumentType.ResearcherProfile -> SummaryEvidenceKind.ResearcherMetrics,
        DocumentType.CollaborationSummary -> SummaryEvidenceKind.CoauthorEdge,
        DocumentType.PathSummary -> SummaryEvidenceKind.EvidencePath,
        DocumentType.CommunitySummary -> SummaryEvidenceKind.Community
      )
      val identityOf =
        Option(outputs.metrics)
          .getOrElse(Vector.empty)
          .filter(_ != null)
          .map(m => (m.window, m.researcherId) -> m.identity)
          .toMap
      val values = new FactValues(outputs, ms)
      all(
        duplicates("summaryInputs.inputId", inputs.map(_.inputId)),
        inputs.flatMap { s =>
          val f = s"summaryInputs[${s.inputId}]"
          all(
            configured(f, s.window, s.provenance, settings),
            subjectKind.get(s.documentType).toVector.flatMap { k =>
              when(!index(k).contains((s.window, s.subjectId)))(
                invalid(
                  s"$f.subjectId",
                  s"${s.subjectId} resolves to no stored ${k.tag} record of the window"
                )
              )
            },
            s.identity.toVector.flatMap { id =>
              when(identityOf.get((s.window, s.subjectId)) != Some(id))(
                invalid(s"$f.identity", "differs from the subject's metrics identity")
              )
            },
            s.facts.flatMap { fact =>
              fact.evidence.flatMap { r =>
                when(!index(r.kind).contains((s.window, r.id)))(
                  invalid(
                    s"$f.facts[${fact.name}]",
                    s"${r.kind.tag} ${r.id} resolves to no stored record of the window"
                  )
                )
              } ++ (SummaryFactVocabulary.get(fact.name) match {
                case None =>
                  Vector(
                    invalid(
                      s"$f.facts[${fact.name}]",
                      s"'${fact.name}' is not a computed fact of the frozen vocabulary (SummaryFactVocabulary)"
                    )
                  )
                case Some(k) =>
                  fact.evidence.find(_.kind == k) match {
                    case None =>
                      Vector(
                        invalid(
                          s"$f.facts[${fact.name}]",
                          s"a ${fact.name} fact cites a ${k.tag} record"
                        )
                      )
                    case Some(r) =>
                      values.valueOf(fact.name, s.window, r.id).toVector.flatMap { v =>
                        when(v != fact.value)(
                          invalid(
                            s"$f.facts[${fact.name}].value",
                            s"expected $v, the ${fact.name} of ${k.tag} ${r.id}"
                          )
                        )
                      }
                  }
              })
            }
          )
        }
      )
    }
  }

  /**
   * The frozen fact vocabulary of semantic-summary inputs (DE-P6-03, course item 10: summaries come
   * from computed data, never invented metrics): each fact name states one field of one kind of
   * stored record, and the fact's value must equal that field of the first cited record of the kind
   * (`String.valueOf` of the number; `community` is the community ID itself). A fact with any other
   * name is refused.
   */
  val SummaryFactVocabulary: Map[String, SummaryEvidenceKind] = {
    import SummaryEvidenceKind.*
    Map(
      "publicationCount" -> ResearcherMetrics,
      "distinctCoauthorCount" -> ResearcherMetrics,
      "totalCoauthorOccurrences" -> ResearcherMetrics,
      "unresolvedCoauthorOccurrences" -> ResearcherMetrics,
      "averageCoauthorsPerPaper" -> ResearcherMetrics,
      "maximumCoauthorsOnOnePaper" -> ResearcherMetrics,
      "weightedDegree" -> ResearcherMetrics,
      "jointPublicationCount" -> CoauthorEdge,
      "firstCollaborationYear" -> CoauthorEdge,
      "lastCollaborationYear" -> CoauthorEdge,
      "hopCount" -> EvidencePath,
      "componentSize" -> Component,
      "community" -> Community,
      "communitySize" -> Community,
      "crossCommunityDegree" -> BridgeMeasure,
      "communitiesReached" -> BridgeMeasure,
      "participationCoefficient" -> BridgeMeasure,
      "coreness" -> Coreness,
      "teamSize" -> CountedWork,
      "workYear" -> CountedWork
    )
  }

  /** Field values of the stored records by (window, ID), for [[SummaryFactVocabulary]]. */
  private final class FactValues(outputs: AnalysisOutputs, ms: Map[TimeWindow, WindowModel]) {
    private def by[A](
        xs: Vector[A]
    )(w: A => TimeWindow, id: A => String): Map[(TimeWindow, String), A] =
      Option(xs).getOrElse(Vector.empty).filter(_ != null).map(x => (w(x), id(x)) -> x).toMap
    private val metrics = by(outputs.metrics)(_.window, _.researcherId)
    private val edges = by(outputs.edges)(_.window, _.edgeId)
    private val paths = by(outputs.paths)(_.window, _.pathId)
    private val components = by(outputs.components)(_.window, _.componentId)
    private val communities = by(outputs.communities)(_.window, _.communityId)
    private val bridges = by(outputs.bridges)(_.window, _.researcherId)
    private val coreness = by(outputs.coreness)(_.window, _.researcherId)
    private val teams = ms.toVector.flatMap { case (w, m) =>
      m.teams.map(t => (w, t.workId) -> t)
    }.toMap

    def valueOf(name: String, w: TimeWindow, id: String): Option[String] = {
      val k = (w, id)
      name match {
        case "publicationCount" => metrics.get(k).map(_.publicationCount.toString)
        case "distinctCoauthorCount" => metrics.get(k).map(_.distinctCoauthorCount.toString)
        case "totalCoauthorOccurrences" => metrics.get(k).map(_.totalCoauthorOccurrences.toString)
        case "unresolvedCoauthorOccurrences" =>
          metrics.get(k).map(_.unresolvedCoauthorOccurrences.toString)
        case "averageCoauthorsPerPaper" =>
          metrics.get(k).map(m => String.valueOf(m.averageCoauthorsPerPaper))
        case "maximumCoauthorsOnOnePaper" =>
          metrics.get(k).map(_.maximumCoauthorsOnOnePaper.toString)
        case "weightedDegree" => metrics.get(k).map(_.weightedDegree.toString)
        case "jointPublicationCount" => edges.get(k).map(_.jointPublicationCount.toString)
        case "firstCollaborationYear" => edges.get(k).map(_.firstCollaborationYear.toString)
        case "lastCollaborationYear" => edges.get(k).map(_.lastCollaborationYear.toString)
        case "hopCount" => paths.get(k).map(_.hopCount.toString)
        case "componentSize" => components.get(k).map(_.componentSize.toString)
        case "community" => communities.get(k).map(_.communityId)
        case "communitySize" => communities.get(k).map(_.communitySize.toString)
        case "crossCommunityDegree" => bridges.get(k).map(_.crossCommunityDegree.toString)
        case "communitiesReached" => bridges.get(k).map(_.communitiesReached.toString)
        case "participationCoefficient" =>
          bridges.get(k).map(b => String.valueOf(b.participationCoefficient))
        case "coreness" => coreness.get(k).map(_.coreness.toString)
        case "teamSize" => teams.get(k).map(_.teamSize.toString)
        case "workYear" => teams.get(k).flatMap(_.year).map(_.toString)
        case _ => None
      }
    }
  }

  // ---------------------------------------------------------------- run chain and parts

  /** Parameter keys of a grouping run manifest that the analysis reads (er.grouping, Phase 6). */
  object GroupingManifestKeys {
    val DecisionRunId = "decisionRunId"
    val InputRunId = "inputRunId"
    val GroupingPolicyVersion = "groupingPolicyVersion"
    val MatchPolicyVersion = "matchPolicyVersion"
    val IdentityVersion = "identityVersion"
  }

  /** Parameter keys every analysis run manifest carries (the run-ID chain). */
  object AnalysisManifestKeys {
    val GroupingRunId = "groupingRunId"
    val DecisionRunId = "decisionRunId"
    val InputRunId = "inputRunId"

    /** [[BenchmarkPairList.digest]] of the run's benchmark pair list (repair 01, finding 4). */
    val BenchmarkPairsDigest = "benchmarkPairsDigest"

    /**
     * DE-P6-03: the label-propagation outcome of every window, recorded by the analysis run as
     * computed manifest parameters `community.converged.<start>-<end>` (`true` or `false`) and
     * `community.iterations.<start>-<end>` (decimal), so another outcome is another run ID. Checked
     * against [[WindowStructure]] by [[StructureValidation.checkWindowStructures]].
     */
    def communityConverged(w: TimeWindow): String =
      s"community.converged.${w.startYear}-${w.endYear}"
    def communityIterations(w: TimeWindow): String =
      s"community.iterations.${w.startYear}-${w.endYear}"
  }

  /**
   * The run-ID chain: the analysis run manifest names the grouping run (its run ID), the grouping
   * manifest's `decisionRunId` and `inputRunId`, the run's benchmark pair list (its digest), and
   * the same input snapshots; every record's provenance names the analysis run and the configured
   * graph policy; and every record's lineage equals the two manifests (run IDs, code version and
   * config digest of the analysis manifest, grouping policy, match policy and identity version of
   * the grouping manifest), with the configured identity version.
   *
   * Config digest (DE-P6-02 repair 01, CR-P6-13 finding 1): the analysis manifest's `configDigest`
   * must equal `settings.configDigest` ([[AnalysisRunSettings.configDigest]] of the configuration
   * whose settings the other checks recompute against), and so must every record lineage. Because
   * [[RunId.of]] hashes `configDigest`, two runs that differ in any analysis setting cannot share a
   * run ID; a digest of another scope (for example
   * [[scholarchain.core.config.ScholarChainConfig.digestWithoutGrouping]]) or an arbitrary value is
   * refused.
   *
   * Benchmark pairs (finding 4): the manifest parameter `benchmarkPairsDigest` equals
   * [[BenchmarkPairList.digest]] of `benchmarkPairs`, and the list's `provenance.runId` is the
   * grouping run (its researcher IDs are that run's).
   */
  def checkRunChain(
      analysisManifest: RunManifest,
      groupingManifest: RunManifest,
      outputs: AnalysisOutputs,
      settings: AnalysisRunSettings,
      benchmarkPairs: BenchmarkPairList
  ): Vector[ValidationError] =
    (RunId.of(analysisManifest), RunId.of(groupingManifest)) match {
      case (Left(e), _) => Vector(e)
      case (_, Left(e)) => Vector(e)
      case (Right(analysisRunId), Right(groupingRunId)) =>
        val gp = groupingManifest.parameters
        val ap = analysisManifest.parameters
        val G = GroupingManifestKeys
        val A = AnalysisManifestKeys
        def need(m: SortedMap[String, String], label: String, k: String): Vector[ValidationError] =
          when(!m.contains(k))(ValidationError.MissingField(s"$label.parameters.$k"))
        val keys = all(
          Vector(
            G.DecisionRunId,
            G.InputRunId,
            G.GroupingPolicyVersion,
            G.MatchPolicyVersion,
            G.IdentityVersion
          )
            .flatMap(need(gp, "groupingManifest", _)),
          Vector(A.GroupingRunId, A.DecisionRunId, A.InputRunId, A.BenchmarkPairsDigest).flatMap(
            need(ap, "analysisManifest", _)
          ),
          when(settings == null)(ValidationError.MissingField("settings")),
          when(benchmarkPairs == null)(ValidationError.MissingField("benchmarkPairs"))
        )
        if (keys.nonEmpty) keys
        else {
          val expected = AnalysisLineage(
            groupingRunId = groupingRunId,
            decisionRunId = gp(G.DecisionRunId),
            inputRunId = gp(G.InputRunId),
            codeVersion = analysisManifest.codeVersion,
            configDigest = settings.configDigest,
            groupingPolicyVersion = gp(G.GroupingPolicyVersion),
            matchPolicyVersion = gp(G.MatchPolicyVersion),
            identityVersion = gp(G.IdentityVersion),
            groupingEvaluation = GroupingEvaluation.Unevaluated
          )
          all(
            when(analysisManifest.configDigest != settings.configDigest)(
              invalid(
                "analysisManifest.configDigest",
                s"must be the analysis config digest ${settings.configDigest} (AnalysisRunSettings.configDigest of the configuration validated against)"
              )
            ),
            when(ap(A.BenchmarkPairsDigest) != BenchmarkPairList.digest(benchmarkPairs))(
              invalid(
                s"analysisManifest.parameters.${A.BenchmarkPairsDigest}",
                s"the benchmark pair list ${benchmarkPairs.listVersion} has digest ${BenchmarkPairList.digest(benchmarkPairs)}"
              )
            ),
            when(Option(benchmarkPairs.provenance).forall(_.runId != groupingRunId))(
              invalid(
                "benchmarkPairs.provenance.runId",
                s"must be the grouping run $groupingRunId (the pairs name its researcher IDs)"
              )
            ),
            when(ap(A.GroupingRunId) != groupingRunId)(
              invalid(
                s"analysisManifest.parameters.${A.GroupingRunId}",
                s"the grouping manifest gives $groupingRunId"
              )
            ),
            when(ap(A.DecisionRunId) != gp(G.DecisionRunId))(
              invalid(
                s"analysisManifest.parameters.${A.DecisionRunId}",
                s"the grouping run's decisionRunId is ${gp(G.DecisionRunId)}"
              )
            ),
            when(ap(A.InputRunId) != gp(G.InputRunId))(
              invalid(
                s"analysisManifest.parameters.${A.InputRunId}",
                s"the grouping run's inputRunId is ${gp(G.InputRunId)}"
              )
            ),
            when(analysisManifest.inputSnapshots.toSet != groupingManifest.inputSnapshots.toSet)(
              invalid(
                "analysisManifest.inputSnapshots",
                "must equal the grouping run's input snapshots"
              )
            ),
            when(gp(G.IdentityVersion) != settings.identityVersion)(
              invalid(
                "groupingManifest.parameters.identityVersion",
                s"the configured graph.identityVersion is ${settings.identityVersion}"
              )
            ), {
              // DE-P6-03: the index is the record's position within its own dataset.
              val seen = mutable.HashMap.empty[String, Int]
              outputs.lineageAndProvenance.map { case (name, l, pr) =>
                val i = seen.getOrElse(name, 0)
                seen(name) = i + 1
                (name, l, pr, i)
              }
            }.flatMap { case (name, l, pr, i) =>
              val f = s"$name[$i]"
              all(
                when(l != expected)(invalid(s"$f.lineage", s"must equal the run chain $expected")),
                when(pr == null || pr.runId != analysisRunId)(
                  invalid(s"$f.provenance.runId", s"must be the analysis run $analysisRunId")
                ),
                when(AnalysisRules.policyOf(pr) != Some(settings.graphPolicyVersion))(
                  invalid(s"$f.provenance.policyVersion", s"must be ${settings.graphPolicyVersion}")
                )
              )
            }
          )
        }
    }

  /** Record schemas a grouping run must publish for the analysis to consume it. */
  val GroupingRunSchemas: Vector[String] = Vector(
    SchemaVersions.ResearcherAssignment,
    SchemaVersions.WorkAssignment,
    SchemaVersions.AuthorshipRecordV2,
    SchemaVersions.PublicationTeam
  )

  /**
   * Before consumption: the observed grouping run directory verifies against its part manifest
   * (either version, each with its own format rules: [[PartManifests.verify]]) and lists at least
   * one part of each schema in [[GroupingRunSchemas]].
   */
  def checkGroupingRunParts(
      manifest: Either[PartManifest, PartManifestV2],
      groupingRunId: String,
      observed: Vector[ObservedFile]
  ): Vector[ValidationError] = {
    val verified = PartManifests.verify(manifest, groupingRunId, observed)
    if (verified.nonEmpty) verified
    else {
      val schemas = PartManifests.asV2(manifest).files.map(_.recordSchemaVersion).toSet
      GroupingRunSchemas.filterNot(schemas.contains).map { s =>
        invalid("partManifest.files", s"no part of schema $s: not a complete grouping run")
      }
    }
  }
}
