package scholarchain.core

import scala.collection.mutable

import scholarchain.core.config.AnalysisRunSettings

import Validated.{all, when}

/**
 * Dataset-level validation of the DE-P6-03 analysis outputs: window structures (course items 4, 6),
 * path-length estimates (item 5), temporal comparisons (item 9), work and topic summary inputs
 * (item 10) and the load manifests (item 11). As [[AnalysisValidation]]: pure, in memory, for dev
 * and sample runs; every check starts with the record contracts of its own dataset and stops on
 * their errors, and assumes the datasets it compares with already passed their own checks
 * (components, communities, bridges, coreness, edges, metrics, canonical tables).
 */
object StructureValidation {
  import DatasetRules.*

  private def invalid(field: String, reason: String) = ValidationError.InvalidValue(field, reason)

  private def configuredPolicy(f: String, pr: Provenance, settings: AnalysisRunSettings) =
    when(AnalysisRules.policyOf(pr) != Some(settings.graphPolicyVersion))(
      invalid(s"$f.provenance.policyVersion", s"must be ${settings.graphPolicyVersion}")
    )

  private def inWindow[A](xs: Vector[A], w: TimeWindow)(win: A => TimeWindow): Vector[A] =
    Option(xs).getOrElse(Vector.empty).filter(x => x != null && win(x) == w)

  // ---------------------------------------------------------------- window structures

  /**
   * Window structures: one per configured window in configured order; every count equal to the
   * run's records of the window (researchers = metrics records; components and their sizes,
   * singletons = component memberships; communities and the largest = community memberships;
   * cross-community edges = coauthor edges whose researchers' communities differ, and half the
   * bridge measures' cross-community degrees; max coreness); the configured community algorithm;
   * iterations at most `community.maxIterations` and equal to it when not converged; the outcome
   * equal to the analysis manifest parameters
   * [[AnalysisValidation.AnalysisManifestKeys.communityConverged]] and `communityIterations` of the
   * window; and the shared counts equal to the window's [[AnalysisWindow]] when one is stored.
   */
  def checkWindowStructures(
      structures: Vector[WindowStructure],
      outputs: AnalysisOutputs,
      settings: AnalysisRunSettings,
      analysisManifest: RunManifest
  ): Vector[ValidationError] = {
    val rec = records("windowStructures", structures)
    if (rec.nonEmpty) rec
    else {
      val K = AnalysisValidation.AnalysisManifestKeys
      val params = Option(analysisManifest).flatMap(m => Option(m.parameters)).getOrElse(Map.empty)
      val maxIterations = settings.communityParameters.get("maxIterations").flatMap(_.toIntOption)
      all(
        when(structures.map(_.window) != settings.windows)(
          invalid(
            "windowStructures",
            "one record per configured window, in configured order (graph.windows)"
          )
        ),
        structures.zipWithIndex.flatMap { case (r, i) =>
          val w = r.window
          val f = s"windowStructures[${wkey(w)}]"
          def eq(name: String, got: Any, exp: Any) =
            when(got != exp)(
              invalid(s"$f.$name", s"expected $exp, from the run's records of the window")
            )
          val metrics = inWindow(outputs.metrics, w)(_.window)
          val comps = inWindow(outputs.components, w)(_.window)
          val comms = inWindow(outputs.communities, w)(_.window)
          val edges = inWindow(outputs.edges, w)(_.window)
          val cores = inWindow(outputs.coreness, w)(_.window)
          val bridges = inWindow(outputs.bridges, w)(_.window)
          val compSizes = comps.groupBy(_.componentId).values.map(_.size.toLong).toVector
          val commSizes = comms.groupBy(_.communityId).values.map(_.size.toLong).toVector
          val communityOf = comms.map(c => c.researcherId -> c.communityId).toMap
          val cross =
            edges
              .count(e => communityOf.get(e.researcherA) != communityOf.get(e.researcherB))
              .toLong
          val largest = compSizes.maxOption.getOrElse(0L)
          all(
            configuredPolicy(f, r.provenance, settings),
            when(r.ordinal != i)(invalid(s"$f.ordinal", s"expected $i")),
            eq("researcherCount", r.researcherCount, metrics.size.toLong),
            eq("edgeCount", r.edgeCount, edges.size.toLong),
            eq("componentCount", r.componentCount, compSizes.size.toLong),
            eq("largestComponentSize", r.largestComponentSize, largest),
            eq("singletonCount", r.singletonCount, compSizes.count(_ == 1L).toLong),
            eq("communityCount", r.communityCount, commSizes.size.toLong),
            eq("largestCommunitySize", r.largestCommunitySize, commSizes.maxOption.getOrElse(0L)),
            eq("crossCommunityEdgeCount", r.crossCommunityEdgeCount, cross),
            when(
              bridges.nonEmpty && bridges
                .map(_.crossCommunityDegree)
                .sum != 2L * r.crossCommunityEdgeCount
            )(
              invalid(
                s"$f.crossCommunityEdgeCount",
                "must be half the sum of the bridge measures' crossCommunityDegree"
              )
            ),
            eq("maxCoreness", r.maxCoreness, cores.map(_.coreness).maxOption.getOrElse(0L)),
            when(r.communityAlgorithm != settings.communityAlgorithm)(
              invalid(
                s"$f.communityAlgorithm",
                s"must be ${settings.communityAlgorithm} (community.algorithm)"
              )
            ),
            maxIterations.toVector.flatMap { m =>
              all(
                when(r.communityIterations > m)(
                  invalid(s"$f.communityIterations", s"exceeds community.maxIterations $m")
                ),
                when(!r.communityConverged && r.communityIterations != m)(
                  invalid(
                    s"$f.communityIterations",
                    s"a non-converged window ran community.maxIterations ($m) iterations"
                  )
                )
              )
            },
            when(params.get(K.communityConverged(w)) != Some(r.communityConverged.toString))(
              invalid(
                s"$f.communityConverged",
                s"must equal the analysis manifest parameter ${K.communityConverged(w)}"
              )
            ),
            when(params.get(K.communityIterations(w)) != Some(r.communityIterations.toString))(
              invalid(
                s"$f.communityIterations",
                s"must equal the analysis manifest parameter ${K.communityIterations(w)}"
              )
            ),
            inWindow(outputs.windows, w)(_.window).flatMap { a =>
              all(
                eq("researcherCount", r.researcherCount, a.researcherCount),
                eq("edgeCount", r.edgeCount, a.edgeCount),
                eq("componentCount", r.componentCount, a.componentCount),
                eq("communityCount", r.communityCount, a.communityCount)
              )
            }
          )
        }
      )
    }
  }

  // ---------------------------------------------------------------- path-length estimates

  /**
   * Breadth-first distances from every source to every other member (ordered pairs per distance,
   * ascending). Unbounded within the component; used to recompute [[PathLengthEstimate]].
   */
  def distanceCounts(
      adjacency: Map[String, Set[String]],
      sources: Vector[String]
  ): Vector[DistanceCount] = {
    val counts = mutable.TreeMap.empty[Int, Long]
    sources.foreach { s =>
      val seen = mutable.HashSet(s)
      var frontier = Vector(s)
      var d = 0
      while (frontier.nonEmpty) {
        d += 1
        val next =
          frontier.flatMap(adjacency.getOrElse(_, Set.empty)).filterNot(seen.contains).distinct
        if (next.nonEmpty) counts.update(d, counts.getOrElse(d, 0L) + next.size.toLong)
        seen ++= next
        frontier = next
      }
    }
    counts.toVector.map { case (d, c) => DistanceCount(d, c) }
  }

  /**
   * Path-length estimates: none when the configuration sets no `analysis.pathLength*` keys;
   * otherwise exactly one per (window, connected component with at least `minComponentSize`
   * researchers), with the configured seed and sample size, the sources of [[PathSampleKey.sample]]
   * and the distance counts recomputed by breadth-first search on the window's coauthor graph.
   */
  def checkPathLengthEstimates(
      estimates: Vector[PathLengthEstimate],
      input: GraphInput,
      settings: AnalysisRunSettings
  ): Vector[ValidationError] = {
    val rec = records("pathLengthEstimates", estimates)
    if (rec.nonEmpty) rec
    else
      settings.pathLengthSampling match {
        case None =>
          when(estimates.nonEmpty)(
            invalid(
              "pathLengthEstimates",
              "the analysis configuration sets no analysis.pathLength* keys: no estimate is stored"
            )
          )
        case Some(s) =>
          val ms = AnalysisValidation.models(input, settings)
          val got = estimates.map(e => (e.window, e.componentId) -> e).toMap
          val expected = settings.windows.flatMap { w =>
            val m = ms(w)
            m.components.values.toVector.distinct
              .filter(_.size >= s.minComponentSize)
              .flatMap(g =>
                ComponentId.of(w, settings.graphPolicyVersion, g).toOption.map(id => (w, id) -> g)
              )
          }.toMap
          all(
            duplicates(
              "pathLengthEstimates",
              estimates.map(e => s"${e.componentId} ${wkey(e.window)}")
            ),
            estimates.flatMap(e =>
              configuredPolicy(s"pathLengthEstimates[${e.componentId}]", e.provenance, settings)
            ),
            expected.keys.toVector
              .filterNot(got.contains)
              .sortBy(k => (wkey(k._1), k._2))
              .map { case (w, id) =>
                invalid(
                  "pathLengthEstimates",
                  s"component $id of ${wkey(w)} has >= ${s.minComponentSize} researchers but no estimate"
                )
              },
            estimates.sortBy(e => (wkey(e.window), e.componentId)).flatMap { e =>
              val f = s"pathLengthEstimates[${e.componentId} ${wkey(e.window)}]"
              expected.get((e.window, e.componentId)) match {
                case None =>
                  Vector(
                    invalid(
                      f,
                      s"not a component of the window with >= ${s.minComponentSize} researchers"
                    )
                  )
                case Some(group) =>
                  PathSampleKey.sample(s.seed, e.window, group, s.sampleSize) match {
                    case Left(err) => Vector(err)
                    case Right(sources) =>
                      all(
                        when(e.componentSize != group.size.toLong)(
                          invalid(s"$f.componentSize", s"expected ${group.size}")
                        ),
                        when(e.sampleSeed != s.seed)(
                          invalid(s"$f.sampleSeed", s"must be ${s.seed} (analysis.pathLengthSeed)")
                        ),
                        when(e.sampleSize != s.sampleSize)(
                          invalid(
                            s"$f.sampleSize",
                            s"must be ${s.sampleSize} (analysis.pathLengthSampleSize)"
                          )
                        ),
                        when(e.sourceIds != sources)(
                          invalid(s"$f.sourceIds", s"expected the PathSampleKey sample $sources")
                        ),
                        when(
                          e.sourceIds == sources &&
                            e.distanceCounts != distanceCounts(ms(e.window).adjacency, sources)
                        )(
                          invalid(
                            s"$f.distanceCounts",
                            s"expected ${distanceCounts(ms(e.window).adjacency, sources)} by breadth-first search"
                          )
                        )
                      )
                  }
              }
            }
          )
      }
  }

  // ---------------------------------------------------------------- temporal comparisons

  /** The consecutive pairs of configured windows a run compares. */
  def windowPairs(settings: AnalysisRunSettings): Vector[(TimeWindow, TimeWindow)] =
    settings.windows.sliding(2).collect { case Vector(a, b) => (a, b) }.toVector

  /** The expected limitations of one pair, sorted by tag. */
  private def limitationsOf(
      from: TimeWindow,
      to: TimeWindow,
      withoutYear: Long,
      converged: TimeWindow => Option[Boolean]
  ): Vector[ComparisonLimitation] = {
    import ComparisonLimitation.*
    Vector(
      Option.when(WindowComparison.overlap(from, to) > 0)(OverlappingWindows),
      Option.when((from.endYear - from.startYear) != (to.endYear - to.startYear))(
        UnequalWindowLengths
      ),
      Option.when(withoutYear > 0)(WorksWithoutYearExcluded),
      Option.when(converged(from).contains(false) || converged(to).contains(false))(
        CommunityNotConverged
      ),
      Some(GroupingUnevaluated)
    ).flatten.sortBy(_.tag)
  }

  /**
   * Temporal comparisons: exactly one [[WindowComparison]] per consecutive pair of configured
   * windows ([[windowPairs]]), in order, with the recomputed overlap, limitations (the community
   * convergence from the run's [[WindowStructure]] records, which must be present for both windows)
   * and researcher and edge counts; and exactly one [[ResearcherTemporalChange]] per pair and
   * researcher with a metrics record in either window, whose presence, both window states, coauthor
   * turnover and community overlap equal the run's records.
   */
  def checkTemporal(
      comparisons: Vector[WindowComparison],
      changes: Vector[ResearcherTemporalChange],
      outputs: AnalysisOutputs,
      input: GraphInput,
      settings: AnalysisRunSettings
  ): Vector[ValidationError] = {
    val rec = records("windowComparisons", comparisons) ++ records("temporalChanges", changes)
    if (rec.nonEmpty) rec
    else {
      val pairs = windowPairs(settings)
      val withoutYear = AnalysisValidation.countedTeams(input).count(_.year.isEmpty).toLong
      val structureOf =
        Option(outputs.windowStructures).getOrElse(Vector.empty).map(s => s.window -> s).toMap
      def idx[A](xs: Vector[A])(w: A => TimeWindow, r: A => String): Map[(TimeWindow, String), A] =
        Option(xs).getOrElse(Vector.empty).filter(_ != null).map(x => (w(x), r(x)) -> x).toMap
      val metrics = idx(outputs.metrics)(_.window, _.researcherId)
      val comps = idx(outputs.components)(_.window, _.researcherId)
      val comms = idx(outputs.communities)(_.window, _.researcherId)
      val bridges = idx(outputs.bridges)(_.window, _.researcherId)
      val cores = idx(outputs.coreness)(_.window, _.researcherId)
      def researchersOf(w: TimeWindow): Set[String] =
        inWindow(outputs.metrics, w)(_.window).map(_.researcherId).toSet
      def edgeKeys(w: TimeWindow): Set[(String, String)] =
        inWindow(outputs.edges, w)(_.window).map(e => (e.researcherA, e.researcherB)).toSet
      def coauthors(w: TimeWindow, r: String): Set[String] =
        inWindow(outputs.edges, w)(_.window).collect {
          case e if e.researcherA == r => e.researcherB
          case e if e.researcherB == r => e.researcherA
        }.toSet
      def largest(w: TimeWindow): Long =
        inWindow(outputs.components, w)(_.window).map(_.componentSize).maxOption.getOrElse(0L)
      def communityMembers(w: TimeWindow, id: String): Set[String] =
        inWindow(outputs.communities, w)(_.window)
          .filter(_.communityId == id)
          .map(_.researcherId)
          .toSet
      def state(w: TimeWindow, r: String): Either[String, ResearcherWindowState] =
        (
          metrics.get((w, r)),
          comps.get((w, r)),
          comms.get((w, r)),
          bridges.get((w, r)),
          cores.get((w, r))
        ) match {
          case (Some(m), Some(c), Some(cm), Some(b), Some(k)) =>
            Right(
              ResearcherWindowState(
                m.publicationCount,
                m.distinctCoauthorCount,
                m.averageCoauthorsPerPaper,
                m.maximumCoauthorsOnOnePaper,
                c.componentSize,
                c.componentSize == largest(w),
                cm.communitySize,
                b.crossCommunityDegree,
                b.communitiesReached,
                b.participationCoefficient,
                k.coreness
              )
            )
          case _ =>
            Left(
              s"$r has a metrics record in ${wkey(w)} but not every component, community, bridge and coreness record"
            )
        }

      val comparisonChecks = all(
        when(comparisons.map(c => (c.fromWindow, c.toWindow)) != pairs)(
          invalid(
            "windowComparisons",
            "one record per consecutive pair of configured windows, in configured order (graph.windows)"
          )
        ),
        comparisons.zipWithIndex.flatMap { case (c, i) =>
          val f = s"windowComparisons[${wkey(c.fromWindow)} ${wkey(c.toWindow)}]"
          val missing = Vector(c.fromWindow, c.toWindow).filterNot(structureOf.contains)
          if (missing.nonEmpty)
            Vector(
              invalid(
                f,
                s"needs the window structures of ${missing.map(wkey).mkString(", ")} (community convergence)"
              )
            )
          else {
            val (ra, rb) = (researchersOf(c.fromWindow), researchersOf(c.toWindow))
            val (ea, eb) = (edgeKeys(c.fromWindow), edgeKeys(c.toWindow))
            def eq(name: String, got: Any, exp: Any) =
              when(got != exp)(invalid(s"$f.$name", s"expected $exp"))
            all(
              configuredPolicy(f, c.provenance, settings),
              when(c.ordinal != i)(invalid(s"$f.ordinal", s"expected $i")),
              eq(
                "limitations",
                c.limitations,
                limitationsOf(
                  c.fromWindow,
                  c.toWindow,
                  withoutYear,
                  w => structureOf.get(w).map(_.communityConverged)
                )
              ),
              eq("researchersInBoth", c.researchersInBoth, (ra intersect rb).size.toLong),
              eq("researchersOnlyFrom", c.researchersOnlyFrom, (ra diff rb).size.toLong),
              eq("researchersOnlyTo", c.researchersOnlyTo, (rb diff ra).size.toLong),
              eq("edgesInBoth", c.edgesInBoth, (ea intersect eb).size.toLong),
              eq("edgesOnlyFrom", c.edgesOnlyFrom, (ea diff eb).size.toLong),
              eq("edgesOnlyTo", c.edgesOnlyTo, (eb diff ea).size.toLong)
            )
          }
        }
      )

      val changeChecks = all(
        duplicates(
          "temporalChanges",
          changes.map(c => s"${c.researcherId} ${wkey(c.fromWindow)} ${wkey(c.toWindow)}")
        ),
        changes.filterNot(c => pairs.contains((c.fromWindow, c.toWindow))).map { c =>
          invalid(
            s"temporalChanges[${c.researcherId}]",
            s"${wkey(c.fromWindow)} -> ${wkey(c.toWindow)} is not a consecutive pair of configured windows"
          )
        },
        pairs.flatMap { case (a, b) =>
          val got = changes
            .filter(c => c.fromWindow == a && c.toWindow == b)
            .map(c => c.researcherId -> c)
            .toMap
          val (ra, rb) = (researchersOf(a), researchersOf(b))
          val everyone = ra union rb
          sortedUtf8(everyone.filterNot(got.contains)).map { r =>
            invalid(
              "temporalChanges",
              s"$r has a metrics record in ${wkey(a)} or ${wkey(b)} but no change record"
            )
          } ++ sortedUtf8(got.keys).flatMap { r =>
            val c = got(r)
            val f = s"temporalChanges[$r ${wkey(a)} ${wkey(b)}]"
            if (!everyone.contains(r))
              Vector(invalid(f, "the researcher has no metrics record in either window"))
            else {
              val presence =
                if (ra.contains(r) && rb.contains(r)) TemporalPresence.InBoth
                else if (ra.contains(r)) TemporalPresence.OnlyFrom
                else TemporalPresence.OnlyTo
              val from = if (ra.contains(r)) Some(state(a, r)) else None
              val to = if (rb.contains(r)) Some(state(b, r)) else None
              (from.flatMap(_.left.toOption) ++ to.flatMap(_.left.toOption)).toVector match {
                case problems if problems.nonEmpty => problems.map(invalid(f, _))
                case _ =>
                  val na = if (ra.contains(r)) coauthors(a, r) else Set.empty[String]
                  val nb = if (rb.contains(r)) coauthors(b, r) else Set.empty[String]
                  val overlap =
                    if (presence != TemporalPresence.InBoth) None
                    else
                      Some(
                        (communityMembers(a, comms((a, r)).communityId) intersect
                          communityMembers(b, comms((b, r)).communityId)).size.toLong
                      )
                  def eq(name: String, got: Any, exp: Any) =
                    when(got != exp)(invalid(s"$f.$name", s"expected $exp, from the run's records"))
                  all(
                    configuredPolicy(f, c.provenance, settings),
                    eq("presence", c.presence, presence),
                    eq("from", c.from, from.flatMap(_.toOption)),
                    eq("to", c.to, to.flatMap(_.toOption)),
                    eq("retainedCoauthors", c.retainedCoauthors, (na intersect nb).size.toLong),
                    eq("gainedCoauthors", c.gainedCoauthors, (nb diff na).size.toLong),
                    eq("lostCoauthors", c.lostCoauthors, (na diff nb).size.toLong),
                    eq("communityOverlap", c.communityOverlap, overlap)
                  )
              }
            }
          }
        }
      )
      all(comparisonChecks, changeChecks)
    }
  }

  // ---------------------------------------------------------------- entity summaries

  /** How one entity fact name is verified. */
  enum EntityFactRule {

    /** A property of the subject node itself. */
    case SubjectProperty(key: String)

    /** The `name` of a node that is the subject or adjacent to it through an edge of the tables. */
    case NeighbourName

    /** The other endpoint of an edge of `edgeType` with the subject as `from` (or as `to`). */
    case EdgeEnd(edgeType: EdgeLabel, subjectIsFrom: Boolean)

    /** The title of a curated member work of the subject canonical work. */
    case SourceTitle
  }

  /**
   * The frozen fact vocabulary of entity summary inputs (DE-P6-03), by document type: name -> the
   * evidence kind it must cite and how its value is verified.
   *   - `work-summary`: `title` (source-work), `year`, `graphEligibility`, `groupSize` (the subject
   *     Work node), `name` (an adjacent Venue or Topic node), `author` (AUTHORED into the subject:
   *     the researcher or unresolved mention), `venue` (PUBLISHED_IN), `topic` (HAS_TOPIC).
   *   - `topic-summary`: `name` (the subject Topic node), `work` (HAS_TOPIC into the subject).
   */
  val EntityFactVocabulary: Map[DocumentType, Map[String, (EntityEvidenceKind, EntityFactRule)]] = {
    import EntityEvidenceKind.*
    import EntityFactRule.*
    Map(
      DocumentType.WorkSummary -> Map(
        "title" -> (SourceWork, SourceTitle),
        "year" -> (GraphNode, SubjectProperty("year")),
        "graphEligibility" -> (GraphNode, SubjectProperty("graphEligibility")),
        "groupSize" -> (GraphNode, SubjectProperty("groupSize")),
        "name" -> (GraphNode, NeighbourName),
        "author" -> (GraphEdge, EdgeEnd(EdgeLabel.Authored, false)),
        "venue" -> (GraphEdge, EdgeEnd(EdgeLabel.PublishedIn, true)),
        "topic" -> (GraphEdge, EdgeEnd(EdgeLabel.HasTopic, true))
      ),
      DocumentType.TopicSummary -> Map(
        "name" -> (GraphNode, SubjectProperty("name")),
        "work" -> (GraphEdge, EdgeEnd(EdgeLabel.HasTopic, false))
      )
    )
  }

  /**
   * Entity summary inputs: unique input IDs; the subject is a Work (work-summary) or Topic
   * (topic-summary) node of the canonical tables; every cited record resolves (a graph node, a
   * graph edge, or a curated source work); and every fact is in [[EntityFactVocabulary]] for its
   * document type, cites a record of the required kind, and states exactly that record's field.
   */
  def checkEntitySummaries(
      inputs: Vector[EntitySummaryInput],
      nodes: Vector[GraphNodeV2],
      edges: Vector[GraphEdgeV2],
      works: Vector[WorkAssignment],
      sourceWorks: Vector[WorkRecord],
      settings: AnalysisRunSettings
  ): Vector[ValidationError] = {
    val rec = records("entitySummaryInputs", inputs)
    if (rec.nonEmpty) rec
    else {
      import EntityFactRule.*
      val nodeById =
        Option(nodes).getOrElse(Vector.empty).filter(_ != null).map(n => n.nodeId -> n).toMap
      val edgeById =
        Option(edges).getOrElse(Vector.empty).filter(_ != null).map(e => e.edgeId -> e).toMap
      val adjacent: Map[String, Set[String]] =
        edgeById.values.toVector
          .flatMap(e => Vector(e.fromId -> e.toId, e.toId -> e.fromId))
          .groupMap(_._1)(_._2)
          .map { case (k, v) => k -> v.toSet }
      val canonicalOf = Option(works)
        .getOrElse(Vector.empty)
        .filter(_ != null)
        .map(w => w.workId -> w.canonicalWorkId)
        .toMap
      val sourceById =
        Option(sourceWorks).getOrElse(Vector.empty).filter(_ != null).map(w => w.workId -> w).toMap
      val subjectLabel = Map(
        DocumentType.WorkSummary -> NodeLabel.Work,
        DocumentType.TopicSummary -> NodeLabel.Topic
      )
      all(
        duplicates("entitySummaryInputs.inputId", inputs.map(_.inputId)),
        inputs.flatMap { s =>
          val f = s"entitySummaryInputs[${s.inputId}]"
          val vocabulary = EntityFactVocabulary.getOrElse(s.documentType, Map.empty)
          val subject = nodeById.get(s.subjectId)
          all(
            configuredPolicy(f, s.provenance, settings),
            when(!subject.exists(n => subjectLabel.get(s.documentType).contains(n.label)))(
              invalid(
                s"$f.subjectId",
                s"${s.subjectId} is not a ${subjectLabel.get(s.documentType).map(_.tag).getOrElse("?")} node of the canonical tables"
              )
            ),
            s.facts.flatMap { fact =>
              val ff = s"$f.facts[${fact.name}]"
              val unresolved = fact.evidence.flatMap { r =>
                val found = r.kind match {
                  case EntityEvidenceKind.GraphNode => nodeById.contains(r.id)
                  case EntityEvidenceKind.GraphEdge => edgeById.contains(r.id)
                  case EntityEvidenceKind.SourceWork => sourceById.contains(r.id)
                }
                when(!found)(invalid(ff, s"${r.kind.tag} ${r.id} resolves to no record of the run"))
              }
              val verified = vocabulary.get(fact.name) match {
                case None =>
                  Vector(
                    invalid(
                      ff,
                      s"'${fact.name}' is not a fact of the ${s.documentType.tag} vocabulary (EntityFactVocabulary)"
                    )
                  )
                case Some((kind, rule)) =>
                  fact.evidence.find(_.kind == kind) match {
                    case None =>
                      Vector(invalid(ff, s"a ${fact.name} fact cites a ${kind.tag} record"))
                    case Some(r) =>
                      val expected: Either[String, Option[String]] = rule match {
                        case SubjectProperty(key) =>
                          if (r.id != s.subjectId) Left("must cite the subject node")
                          else Right(nodeById.get(r.id).flatMap(_.properties.get(key)))
                        case NeighbourName =>
                          if (
                            r.id != s.subjectId && !adjacent
                              .getOrElse(s.subjectId, Set.empty)
                              .contains(r.id)
                          )
                            Left("must cite the subject node or a node adjacent to it")
                          else Right(nodeById.get(r.id).flatMap(_.properties.get("name")))
                        case EdgeEnd(t, subjectIsFrom) =>
                          edgeById.get(r.id) match {
                            case None => Right(None)
                            case Some(e) =>
                              if (e.edgeType != t) Left(s"must cite a ${t.tag} edge")
                              else if ((if (subjectIsFrom) e.fromId else e.toId) != s.subjectId)
                                Left(s"the cited ${t.tag} edge does not touch the subject")
                              else Right(Some(if (subjectIsFrom) e.toId else e.fromId))
                          }
                        case SourceTitle =>
                          if (canonicalOf.get(r.id) != Some(s.subjectId))
                            Left("must cite a member work of the subject canonical work")
                          else Right(sourceById.get(r.id).map(_.title))
                      }
                      expected match {
                        case Left(why) => Vector(invalid(ff, why))
                        case Right(None) => Vector.empty // unresolved, reported above
                        case Right(Some(v)) =>
                          when(v != fact.value)(
                            invalid(s"$ff.value", s"expected $v, from ${kind.tag} ${r.id}")
                          )
                      }
                  }
              }
              unresolved ++ verified
            }
          )
        }
      )
    }
  }

  // ---------------------------------------------------------------- load manifests

  /**
   * The load manifests of one published analysis run (course item 11; serialization contracts only,
   * the loaders are storage-engineer's): exactly one manifest per target store (Neptune, DynamoDB,
   * S3 Vectors, ordinary S3); each passes its contract and [[StoreLoadManifest.checkAgainst]] the
   * run's part manifest and observed `_parts.json` SHA-256; every item's role is in [[LoadRoles]]
   * for its store, with that role's record schema and an allowed format; and each store's manifest
   * lists every part of the run that one of its roles accepts (same schema and format), so no part
   * is silently left unloaded.
   */
  def checkLoadManifests(
      manifests: Vector[StoreLoadManifest],
      partManifest: Either[PartManifest, PartManifestV2],
      observedPartManifestSha256: String
  ): Vector[ValidationError] = {
    val rec = records("loadManifests", manifests)
    if (rec.nonEmpty) rec
    else if (partManifest == null) Vector(ValidationError.MissingField("partManifest"))
    else {
      val parts = Option(PartManifests.asV2(partManifest).files)
        .getOrElse(Vector.empty)
        .filter(p => p != null && p.path != PartManifest.RunManifestFile)
      val byStore = manifests.groupBy(_.targetStore)
      all(
        TargetStore.values.toVector.flatMap { t =>
          byStore.getOrElse(t, Vector.empty).size match {
            case 1 => Vector.empty
            case 0 => Vector(invalid("loadManifests", s"no load manifest for ${t.tag}"))
            case n =>
              Vector(
                invalid("loadManifests", s"$n load manifests for ${t.tag}; one per target store")
              )
          }
        },
        manifests.flatMap { m =>
          val f = s"loadManifests[${m.targetStore.tag}]"
          val listed = m.items.map(_.path).toSet
          val roles = LoadRoles.all.filter(_.target == m.targetStore)
          all(
            StoreLoadManifest.checkAgainst(m, partManifest, observedPartManifestSha256).map {
              case ValidationError.InvalidValue(field, why) => invalid(s"$f.$field", why)
              case other => other
            },
            m.items.flatMap { it =>
              val g = s"$f.items[${it.path}]"
              LoadRoles.of(m.targetStore, it.targetRole) match {
                case None =>
                  Vector(
                    invalid(
                      g,
                      s"'${it.targetRole}' is not a ${m.targetStore.tag} role of LoadRoles"
                    )
                  )
                case Some(r) =>
                  all(
                    when(it.recordSchemaVersion != r.recordSchemaVersion)(
                      invalid(g, s"role ${r.role} holds ${r.recordSchemaVersion} records")
                    ),
                    when(!r.formats.contains(it.format))(
                      invalid(
                        g,
                        s"role ${r.role} accepts ${r.formats.map(_.tag).toVector.sorted.mkString(", ")}"
                      )
                    )
                  )
              }
            },
            parts
              .filter(p =>
                roles.exists(r =>
                  r.recordSchemaVersion == p.recordSchemaVersion && r.formats.contains(p.format)
                )
              )
              .filterNot(p => listed.contains(p.path))
              .map(p =>
                invalid(
                  f,
                  s"part ${p.path} (${p.recordSchemaVersion}, ${p.format.tag}) is not loaded"
                )
              )
          )
        }
      )
    }
  }
}
