package scholarchain.core

import upickle.default.ReadWriter
import upickle.implicits.allowUnknownKeys

import Validated.{all, at, when}

// =====================================================================================
// Phase 6 (DE-P6-03): window structure summaries (course items 4, 6), component-level path-length
// estimates (item 5), temporal comparisons (item 9), work and topic summary inputs (item 10) and
// the load-role vocabulary (item 11). Every record carries the analysis lineage and provenance of
// [[AnalysisRules]]; validation against the other datasets is in [[StructureValidation]].
// =====================================================================================

import AnalysisRules.*

// ------------------------------------------------------------------ window structure (items 4, 6)

/**
 * Graph-structure totals of one configured window (schema `window-structure.v1`). One record per
 * window of `graph.windows`, in configured order (`ordinal` 0..n-1), next to its
 * [[AnalysisWindow]]. Every count is derived from the run's per-researcher records of the window
 * ([[StructureValidation.checkWindowStructures]]); the label-propagation outcome is the algorithm's
 * and is checked against the analysis run manifest.
 *
 * @param researcherCount
 *   identified researchers with a metrics record in the window (the graph's vertices)
 * @param edgeCount
 *   coauthor edges of the window
 * @param componentCount
 *   connected components (a researcher without identified coauthors is a component of size 1)
 * @param largestComponentSize
 *   size of the largest component (0 for an empty window)
 * @param largestComponentFraction
 *   `largestComponentSize.toDouble / researcherCount.toDouble`, 0.0 for an empty window
 *   ([[WindowStructure.fraction]])
 * @param singletonCount
 *   components of size 1 (researchers without identified coauthors in the window)
 * @param communityCount
 *   distinct communities
 * @param largestCommunitySize
 *   size of the largest community
 * @param crossCommunityEdgeCount
 *   coauthor edges whose two researchers are in different communities (each undirected edge once;
 *   equals half the sum of the bridge measures' `crossCommunityDegree`)
 * @param communityAlgorithm
 *   the configured `community.algorithm`
 * @param communityIterations
 *   iterations the community algorithm ran in this window
 * @param communityConverged
 *   whether it converged; when false, `communityIterations` is `community.maxIterations`
 * @param maxCoreness
 *   the largest coreness of the window (0 when empty or edgeless)
 * @param provenance
 *   `scholarchain-derived`; `sourceRecordId` = `window:<start>-<end>`; graph policy
 */
@allowUnknownKeys(false)
final case class WindowStructure(
    schemaVersion: String,
    window: TimeWindow,
    ordinal: Int,
    researcherCount: Long,
    edgeCount: Long,
    componentCount: Long,
    largestComponentSize: Long,
    largestComponentFraction: Double,
    singletonCount: Long,
    communityCount: Long,
    largestCommunitySize: Long,
    crossCommunityEdgeCount: Long,
    communityAlgorithm: String,
    communityIterations: Int,
    communityConverged: Boolean,
    maxCoreness: Long,
    lineage: AnalysisLineage,
    provenance: Provenance
) derives ReadWriter

object WindowStructure {

  /** The largest-component fraction as the contract defines it. */
  def fraction(largest: Long, researchers: Long): Double =
    if (researchers == 0) 0.0 else largest.toDouble / researchers.toDouble

  given contract: RecordContract[WindowStructure] =
    new RecordContract[WindowStructure]("WindowStructure", SchemaVersions.WindowStructure, true) {
      def schemaVersionOf(t: WindowStructure): String = t.schemaVersion
      def provenanceOf(t: WindowStructure): Provenance = t.provenance
      protected def fields(t: WindowStructure, p: String): Vector[ValidationError] = {
        def nn(name: String, v: Long) = Validated.nonNegative(at(p, name), v)
        def bad(name: String, why: String) = ValidationError.InvalidValue(at(p, name), why)
        val counts = all(
          nn("researcherCount", t.researcherCount),
          nn("edgeCount", t.edgeCount),
          nn("componentCount", t.componentCount),
          nn("largestComponentSize", t.largestComponentSize),
          nn("singletonCount", t.singletonCount),
          nn("communityCount", t.communityCount),
          nn("largestCommunitySize", t.largestCommunitySize),
          nn("crossCommunityEdgeCount", t.crossCommunityEdgeCount),
          nn("maxCoreness", t.maxCoreness),
          when(t.ordinal < 0)(bad("ordinal", "negative")),
          when(t.communityIterations < 0)(bad("communityIterations", "negative"))
        )
        val n = t.researcherCount
        val relations =
          if (counts.nonEmpty) Vector.empty
          else
            all(
              when(t.componentCount > n)(bad("componentCount", "exceeds researcherCount")),
              when(t.largestComponentSize > n)(
                bad("largestComponentSize", "exceeds researcherCount")
              ),
              when(t.singletonCount > t.componentCount)(
                bad("singletonCount", "exceeds componentCount")
              ),
              when(
                t.singletonCount + 2L * (t.componentCount - t.singletonCount) > n
              )(
                bad("componentCount", "non-singleton components have at least two researchers each")
              ),
              when(n > 0 && (t.componentCount < 1 || t.largestComponentSize < 1))(
                bad("componentCount", "a window with researchers has a component")
              ),
              when(t.communityCount > n)(bad("communityCount", "exceeds researcherCount")),
              when(n > 0 && t.communityCount < t.componentCount)(
                bad("communityCount", "every community lies inside one component")
              ),
              when(t.largestCommunitySize > t.largestComponentSize)(
                bad("largestCommunitySize", "a community lies inside one component")
              ),
              when(t.crossCommunityEdgeCount > t.edgeCount)(
                bad("crossCommunityEdgeCount", "exceeds edgeCount")
              ),
              when(t.maxCoreness > math.max(t.largestComponentSize - 1L, 0L))(
                bad("maxCoreness", "a k-core has at least k + 1 researchers in one component")
              ),
              when(n == 0 && (t.edgeCount != 0 || t.communityCount != 0))(
                bad("researcherCount", "an empty window has no edge and no community")
              ),
              when(t.largestComponentFraction != fraction(t.largestComponentSize, n))(
                bad(
                  "largestComponentFraction",
                  s"largestComponentSize / researcherCount: expected ${fraction(t.largestComponentSize, n)}"
                )
              )
            )
        all(
          counts,
          relations,
          Validated.finite(at(p, "largestComponentFraction"), t.largestComponentFraction),
          AssignmentRules.token(at(p, "communityAlgorithm"), t.communityAlgorithm),
          common(p, AnalysisWindow.subjectOf(t.window), t.window, t.lineage, t.provenance)
        )
      }
    }
}

// ------------------------------------------------------------------ path-length estimate (item 5)

/** Ordered (source, target) pairs at one shortest-path distance. */
@allowUnknownKeys(false)
final case class DistanceCount(distance: Int, pairs: Long) derives ReadWriter

/**
 * Component-level shortest-path length estimate of one connected component in one window (schema
 * `path-length-estimate.v1`; course item 5 "where feasible"). Stored only when the analysis
 * configuration sets the `analysis.pathLength*` keys
 * ([[scholarchain.core.config.PathLengthSampling]]), then for every component of the window with
 * at least `minComponentSize` researchers.
 *
 * Method `sampled-bfs`: the sources S are the `min(sampleSize, componentSize)` members with the
 * smallest [[PathSampleKey]] (seed, window, researcher); one unbounded breadth-first search per
 * source over the window's coauthor graph gives the distance to every other member. A distributed
 * implementation runs the |S| searches together (one frontier per source, one superstep per
 * distance), so the cost is O(|S| * (V + E)) of the component and nothing is collected on the
 * driver; no all-pairs computation. When |S| = componentSize the estimate is exact (`exact`): the
 * average is the component's average shortest-path length and `maxObservedDistance` its diameter.
 * Otherwise `maxObservedDistance` is a lower bound of the diameter.
 *
 * @param distanceCounts
 *   ordered (source in S, other member) pairs per distance; distances 1..`maxObservedDistance`,
 *   each with at least one pair
 * @param pairCount
 *   |S| * (componentSize - 1), the sum of the counts
 * @param averageDistance
 *   sum of distance * pairs as Long, then `.toDouble / pairCount.toDouble` ([[PathLengthEstimate.average]])
 * @param effectiveDiameter90
 *   the smallest distance d with 10 * (pairs at distance <= d) >= 9 * pairCount (integer arithmetic,
 *   the 90th-percentile effective diameter of the sampled distribution)
 * @param provenance
 *   `scholarchain-derived`; `sourceRecordId` = `componentId`; graph policy
 */
@allowUnknownKeys(false)
final case class PathLengthEstimate(
    schemaVersion: String,
    window: TimeWindow,
    componentId: String,
    componentSize: Long,
    method: String,
    sampleSeed: Long,
    sampleSize: Int,
    sourceIds: Vector[String],
    exact: Boolean,
    distanceCounts: Vector[DistanceCount],
    pairCount: Long,
    averageDistance: Double,
    maxObservedDistance: Int,
    effectiveDiameter90: Int,
    lineage: AnalysisLineage,
    provenance: Provenance
) derives ReadWriter

object PathLengthEstimate {

  /** The only method of v1. */
  val Method = "sampled-bfs"

  /** The average distance as the contract defines it (0.0 for no pair). */
  def average(counts: Vector[DistanceCount]): Double = {
    val pairs = counts.map(_.pairs).sum
    if (pairs == 0) 0.0
    else counts.map(c => c.distance.toLong * c.pairs).sum.toDouble / pairs.toDouble
  }

  /** The 90th-percentile effective diameter as the contract defines it (0 for no pair). */
  def effectiveDiameter90(counts: Vector[DistanceCount]): Int = {
    val pairs = counts.map(_.pairs).sum
    var cum = 0L
    counts
      .find { c =>
        cum += c.pairs; 10L * cum >= 9L * pairs
      }
      .map(_.distance)
      .getOrElse(0)
  }

  /**
   * Builds an estimate from the sources and the per-distance counts (all derived fields computed
   * here, then the record contract applied); `provenanceOf` receives the component ID.
   */
  def build(
      window: TimeWindow,
      componentId: String,
      componentSize: Long,
      seed: Long,
      sampleSize: Int,
      sourceIds: Vector[String],
      counts: Vector[DistanceCount],
      lineage: AnalysisLineage,
      provenanceOf: String => Provenance
  ): Either[Vector[ValidationError], PathLengthEstimate] = {
    val cs = Option(counts).getOrElse(Vector.empty).filter(_ != null).sortBy(_.distance)
    val e = PathLengthEstimate(
      SchemaVersions.PathLengthEstimate,
      window,
      componentId,
      componentSize,
      Method,
      seed,
      sampleSize,
      Option(sourceIds).getOrElse(Vector.empty).sortWith((a, b) => Canonical.compareUtf8(a, b) < 0),
      Option(sourceIds).exists(_.size.toLong == componentSize),
      cs,
      cs.map(_.pairs).sum,
      average(cs),
      cs.lastOption.map(_.distance).getOrElse(0),
      effectiveDiameter90(cs),
      lineage,
      provenanceOf(componentId)
    )
    val errors = contract.validate(e)
    if (errors.isEmpty) Right(e) else Left(errors)
  }

  given contract: RecordContract[PathLengthEstimate] =
    new RecordContract[PathLengthEstimate](
      "PathLengthEstimate",
      SchemaVersions.PathLengthEstimate,
      true
    ) {
      def schemaVersionOf(t: PathLengthEstimate): String = t.schemaVersion
      def provenanceOf(t: PathLengthEstimate): Provenance = t.provenance
      protected def fields(t: PathLengthEstimate, p: String): Vector[ValidationError] = {
        def bad(name: String, why: String) = ValidationError.InvalidValue(at(p, name), why)
        val sources = Option(t.sourceIds).getOrElse(Vector.empty)
        val counts = Option(t.distanceCounts).getOrElse(Vector.empty)
        val countErrors =
          if (t.distanceCounts == null || counts.isEmpty)
            Vector(ValidationError.MissingField(at(p, "distanceCounts")))
          else
            counts.zipWithIndex.flatMap { case (c, i) =>
              if (c == null) Vector(ValidationError.MissingField(at(p, s"distanceCounts[$i]")))
              else
                all(
                  when(c.distance != i + 1)(
                    bad(
                      s"distanceCounts[$i].distance",
                      s"distances are 1..max without gaps: expected ${i + 1}"
                    )
                  ),
                  when(c.pairs < 1)(bad(s"distanceCounts[$i].pairs", "must be >= 1"))
                )
            }
        val expectedSources = math.min(t.sampleSize.toLong, t.componentSize)
        all(
          StableId.check(IdKind.Component, t.componentId, at(p, "componentId")),
          when(t.componentSize < 2)(
            bad("componentSize", "a path-length estimate needs >= 2 researchers")
          ),
          when(t.method != Method)(bad("method", s"must be $Method")),
          when(t.sampleSize < 1)(bad("sampleSize", "must be >= 1")),
          sortedIds(at(p, "sourceIds"), IdKind.Researcher, t.sourceIds, true),
          when(t.sampleSize >= 1 && sources.size.toLong != expectedSources)(
            bad("sourceIds", s"min(sampleSize, componentSize) = $expectedSources sources")
          ),
          when(t.exact != (sources.size.toLong == t.componentSize))(
            bad("exact", "true exactly when every member is a source")
          ),
          countErrors,
          if (countErrors.nonEmpty) Vector.empty
          else
            all(
              when(t.pairCount != counts.map(_.pairs).sum)(
                bad("pairCount", "must be the sum of the counts")
              ),
              when(
                t.componentSize >= 2 && t.pairCount != sources.size.toLong * (t.componentSize - 1L)
              )(
                bad(
                  "pairCount",
                  "every source reaches every other member: |sources| * (componentSize - 1)"
                )
              ),
              when(t.averageDistance != average(counts))(
                bad("averageDistance", s"expected ${average(counts)}")
              ),
              when(t.maxObservedDistance != counts.last.distance)(
                bad("maxObservedDistance", s"expected ${counts.last.distance}")
              ),
              when(t.maxObservedDistance.toLong > t.componentSize - 1L)(
                bad("maxObservedDistance", "a shortest path visits distinct members")
              ),
              when(t.effectiveDiameter90 != effectiveDiameter90(counts))(
                bad("effectiveDiameter90", s"expected ${effectiveDiameter90(counts)}")
              )
            ),
          Validated.finite(at(p, "averageDistance"), t.averageDistance),
          common(p, t.componentId, t.window, t.lineage, t.provenance)
        )
      }
    }
}

// ------------------------------------------------------------------ temporal comparison (item 9)

/**
 * A stated limitation of a window comparison. Frozen tags.
 *   - `overlapping-windows`: the two windows share years, so their works overlap and changes are
 *     not independent.
 *   - `unequal-window-lengths`: the windows span a different number of years.
 *   - `works-without-year-excluded`: counted works without a year are in no window.
 *   - `community-not-converged`: label propagation did not converge in at least one window.
 *   - `grouping-unevaluated`: the identity grouping both windows rest on is unevaluated (binding
 *     decision 3); always stated.
 */
enum ComparisonLimitation(val tag: String) {
  case OverlappingWindows extends ComparisonLimitation("overlapping-windows")
  case UnequalWindowLengths extends ComparisonLimitation("unequal-window-lengths")
  case WorksWithoutYearExcluded extends ComparisonLimitation("works-without-year-excluded")
  case CommunityNotConverged extends ComparisonLimitation("community-not-converged")
  case GroupingUnevaluated extends ComparisonLimitation("grouping-unevaluated")
}
object ComparisonLimitation {
  def fromTag(tag: String): Option[ComparisonLimitation] = values.find(_.tag == tag)
  given ReadWriter[ComparisonLimitation] =
    EnumCodec.tagged("ComparisonLimitation", values.toSeq, _.tag)
}

/**
 * Comparison of two consecutive configured windows (schema `window-comparison.v1`): one record per
 * pair (`graph.windows(i)`, `graph.windows(i + 1)`), `ordinal` = i.
 *
 * Inclusion-rule compatibility: both windows come from one analysis run (same lineage, graph
 * policy, counted-work rule and identity version), so `inclusionRuleCompatible` is true by
 * construction and v1 refuses false; comparing windows of two runs needs a new schema version. What
 * still differs between the windows is stated in `limitations` (sorted by tag, distinct), which
 * [[StructureValidation.checkTemporal]] recomputes.
 *
 * @param overlapYears
 *   years in both windows
 * @param researchersInBoth
 *   researchers with a metrics record in both windows (and `researchersOnlyFrom`,
 *   `researchersOnlyTo` in exactly one)
 * @param edgesInBoth
 *   canonical researcher pairs with a coauthor edge in both windows (and `edgesOnlyFrom`,
 *   `edgesOnlyTo` in exactly one)
 * @param provenance
 *   `scholarchain-derived`; `sourceRecordId` = [[WindowComparison.subjectOf]]; graph policy
 */
@allowUnknownKeys(false)
final case class WindowComparison(
    schemaVersion: String,
    fromWindow: TimeWindow,
    toWindow: TimeWindow,
    ordinal: Int,
    inclusionRuleCompatible: Boolean,
    overlapYears: Int,
    limitations: Vector[ComparisonLimitation],
    researchersInBoth: Long,
    researchersOnlyFrom: Long,
    researchersOnlyTo: Long,
    edgesInBoth: Long,
    edgesOnlyFrom: Long,
    edgesOnlyTo: Long,
    lineage: AnalysisLineage,
    provenance: Provenance
) derives ReadWriter

object WindowComparison {

  /** `window-pair:<start>-<end>:<start>-<end>`. */
  def subjectOf(from: TimeWindow, to: TimeWindow): String =
    if (from == null || to == null) null
    else s"window-pair:${from.startYear}-${from.endYear}:${to.startYear}-${to.endYear}"

  /** Years in both windows. */
  def overlap(a: TimeWindow, b: TimeWindow): Int =
    math.max(0, math.min(a.endYear, b.endYear) - math.max(a.startYear, b.startYear) + 1)

  given contract: RecordContract[WindowComparison] =
    new RecordContract[WindowComparison](
      "WindowComparison",
      SchemaVersions.WindowComparison,
      true
    ) {
      def schemaVersionOf(t: WindowComparison): String = t.schemaVersion
      def provenanceOf(t: WindowComparison): Provenance = t.provenance
      protected def fields(t: WindowComparison, p: String): Vector[ValidationError] = {
        def bad(name: String, why: String) = ValidationError.InvalidValue(at(p, name), why)
        val windows = TimeWindow.check(at(p, "fromWindow"), t.fromWindow) ++
          TimeWindow.check(at(p, "toWindow"), t.toWindow)
        val lims = Option(t.limitations).getOrElse(Vector.empty)
        all(
          windows,
          when(windows.isEmpty && t.fromWindow == t.toWindow)(
            bad("toWindow", "compares two different windows")
          ),
          when(t.ordinal < 0)(bad("ordinal", "negative")),
          when(!t.inclusionRuleCompatible)(
            bad(
              "inclusionRuleCompatible",
              "v1 compares windows of one analysis run only (same lineage, graph policy, counted-work rule and identity version)"
            )
          ),
          when(windows.isEmpty && t.overlapYears != overlap(t.fromWindow, t.toWindow))(
            bad("overlapYears", s"expected ${overlap(t.fromWindow, t.toWindow)}")
          ),
          when(t.limitations == null || lims.exists(_ == null))(
            ValidationError.MissingField(at(p, "limitations"))
          ),
          Validated.strictlyAscending(at(p, "limitations"), lims.filter(_ != null).map(_.tag)),
          when(!lims.contains(ComparisonLimitation.GroupingUnevaluated))(
            bad("limitations", "grouping-unevaluated is always stated (binding decision 3)")
          ),
          when(
            windows.isEmpty && (t.overlapYears > 0) != lims.contains(
              ComparisonLimitation.OverlappingWindows
            )
          )(
            bad("limitations", "overlapping-windows is stated exactly when the windows share years")
          ),
          when(
            windows.isEmpty &&
              ((t.fromWindow.endYear - t.fromWindow.startYear) != (t.toWindow.endYear - t.toWindow.startYear)) !=
              lims.contains(ComparisonLimitation.UnequalWindowLengths)
          )(bad("limitations", "unequal-window-lengths is stated exactly when the lengths differ")),
          Vector(
            "researchersInBoth" -> t.researchersInBoth,
            "researchersOnlyFrom" -> t.researchersOnlyFrom,
            "researchersOnlyTo" -> t.researchersOnlyTo,
            "edgesInBoth" -> t.edgesInBoth,
            "edgesOnlyFrom" -> t.edgesOnlyFrom,
            "edgesOnlyTo" -> t.edgesOnlyTo
          ).flatMap { case (k, v) => Validated.nonNegative(at(p, k), v) },
          AnalysisLineage.check(at(p, "lineage"), t.lineage),
          provenance(p, subjectOf(t.fromWindow, t.toWindow), t.provenance, t.lineage)
        )
      }
    }
}

/** Whether a researcher has a metrics record in the first, the second or both windows. */
enum TemporalPresence(val tag: String) {
  case InBoth extends TemporalPresence("in-both")
  case OnlyFrom extends TemporalPresence("only-from")
  case OnlyTo extends TemporalPresence("only-to")
}
object TemporalPresence {
  def fromTag(tag: String): Option[TemporalPresence] = values.find(_.tag == tag)
  given ReadWriter[TemporalPresence] = EnumCodec.tagged("TemporalPresence", values.toSeq, _.tag)
}

/**
 * One researcher's measures in one window, copied from the run's records of that window: P_r, C_r,
 * A_r, M_r (metrics); component size and whether the component is a largest one of the window
 * (component memberships); community size (community memberships); cross-community degree,
 * communities reached and participation coefficient (bridge measures); coreness.
 */
@allowUnknownKeys(false)
final case class ResearcherWindowState(
    publicationCount: Long,
    distinctCoauthorCount: Long,
    averageCoauthorsPerPaper: Double,
    maximumCoauthorsOnOnePaper: Long,
    componentSize: Long,
    inLargestComponent: Boolean,
    communitySize: Long,
    crossCommunityDegree: Long,
    communitiesReached: Long,
    participationCoefficient: Double,
    coreness: Long
) derives ReadWriter

object ResearcherWindowState {
  def check(field: String, s: ResearcherWindowState): Vector[ValidationError] =
    if (s == null) Vector(ValidationError.MissingField(field))
    else {
      def bad(name: String, why: String) = ValidationError.InvalidValue(at(field, name), why)
      all(
        when(s.publicationCount < 1)(bad("publicationCount", "must be >= 1")),
        Validated.nonNegative(at(field, "distinctCoauthorCount"), s.distinctCoauthorCount),
        Validated.nonNegativeD(at(field, "averageCoauthorsPerPaper"), s.averageCoauthorsPerPaper),
        Validated
          .nonNegative(at(field, "maximumCoauthorsOnOnePaper"), s.maximumCoauthorsOnOnePaper),
        when(s.componentSize < 1)(bad("componentSize", "must be >= 1")),
        when(s.communitySize < 1 || s.communitySize > s.componentSize)(
          bad("communitySize", "in [1, componentSize]: a community lies inside one component")
        ),
        when(
          s.componentSize < s.distinctCoauthorCount + 1L
        )(
          bad("componentSize", "the component holds the researcher and every coauthor")
        ),
        when(s.crossCommunityDegree < 0 || s.crossCommunityDegree > s.distinctCoauthorCount)(
          bad("crossCommunityDegree", "in [0, distinctCoauthorCount]")
        ),
        when(s.communitiesReached < 0 || s.communitiesReached > s.crossCommunityDegree)(
          bad("communitiesReached", "in [0, crossCommunityDegree]")
        ),
        when(
          s.participationCoefficient.isNaN || s.participationCoefficient < 0.0 ||
            s.participationCoefficient >= 1.0
        )(bad("participationCoefficient", "must lie in [0, 1)")),
        when(s.coreness < 0 || s.coreness > s.distinctCoauthorCount)(
          bad("coreness", "in [0, distinctCoauthorCount]")
        )
      )
    }
}

/**
 * Change of one researcher between two consecutive configured windows (schema
 * `researcher-temporal-change.v1`, course item 9): one record per researcher with a metrics record
 * in either window of each [[WindowComparison]] pair. It measures change in distinct coauthors and
 * coauthor turnover, team size (A_r, M_r), component membership (size, largest component),
 * community membership (size and overlap) and bridge behaviour (cross-community degree, communities
 * reached, participation coefficient, coreness) by stating both windows' values; the limitations of
 * the pair are on its [[WindowComparison]].
 *
 * @param from
 *   the state in `fromWindow`, present exactly for `in-both` and `only-from`
 * @param to
 *   the state in `toWindow`, present exactly for `in-both` and `only-to`
 * @param retainedCoauthors
 *   identified coauthors in both windows
 * @param gainedCoauthors
 *   identified coauthors in `toWindow` only
 * @param lostCoauthors
 *   identified coauthors in `fromWindow` only
 * @param communityOverlap
 *   `in-both` only: researchers (the researcher included) in both its `fromWindow` community and
 *   its `toWindow` community; the Jaccard index is overlap / (fromSize + toSize - overlap)
 * @param provenance
 *   `scholarchain-derived`; `sourceRecordId` = `researcherId`; graph policy
 */
@allowUnknownKeys(false)
final case class ResearcherTemporalChange(
    schemaVersion: String,
    researcherId: String,
    fromWindow: TimeWindow,
    toWindow: TimeWindow,
    presence: TemporalPresence,
    from: Option[ResearcherWindowState],
    to: Option[ResearcherWindowState],
    retainedCoauthors: Long,
    gainedCoauthors: Long,
    lostCoauthors: Long,
    communityOverlap: Option[Long],
    lineage: AnalysisLineage,
    provenance: Provenance
) derives ReadWriter

object ResearcherTemporalChange {
  given contract: RecordContract[ResearcherTemporalChange] =
    new RecordContract[ResearcherTemporalChange](
      "ResearcherTemporalChange",
      SchemaVersions.ResearcherTemporalChange,
      true
    ) {
      def schemaVersionOf(t: ResearcherTemporalChange): String = t.schemaVersion
      def provenanceOf(t: ResearcherTemporalChange): Provenance = t.provenance
      protected def fields(t: ResearcherTemporalChange, p: String): Vector[ValidationError] = {
        def bad(name: String, why: String) = ValidationError.InvalidValue(at(p, name), why)
        val from = Option(t.from).flatten
        val to = Option(t.to).flatten
        val overlap = Option(t.communityOverlap).flatten
        val windows = TimeWindow.check(at(p, "fromWindow"), t.fromWindow) ++
          TimeWindow.check(at(p, "toWindow"), t.toWindow)
        val presence = t.presence match {
          case null => Vector(ValidationError.MissingField(at(p, "presence")))
          case TemporalPresence.InBoth =>
            all(
              when(from.isEmpty)(ValidationError.MissingField(at(p, "from"))),
              when(to.isEmpty)(ValidationError.MissingField(at(p, "to"))),
              when(overlap.isEmpty)(ValidationError.MissingField(at(p, "communityOverlap")))
            )
          case TemporalPresence.OnlyFrom =>
            all(
              when(from.isEmpty)(ValidationError.MissingField(at(p, "from"))),
              when(to.isDefined)(bad("to", "only for in-both and only-to")),
              when(t.gainedCoauthors != 0 || t.retainedCoauthors != 0)(
                bad("gainedCoauthors,retainedCoauthors", "0 when absent from toWindow")
              )
            )
          case TemporalPresence.OnlyTo =>
            all(
              when(to.isEmpty)(ValidationError.MissingField(at(p, "to"))),
              when(from.isDefined)(bad("from", "only for in-both and only-from")),
              when(t.lostCoauthors != 0 || t.retainedCoauthors != 0)(
                bad("lostCoauthors,retainedCoauthors", "0 when absent from fromWindow")
              )
            )
        }
        all(
          StableId.check(IdKind.Researcher, t.researcherId, at(p, "researcherId")),
          windows,
          when(windows.isEmpty && t.fromWindow == t.toWindow)(
            bad("toWindow", "compares two different windows")
          ),
          presence,
          when(t.presence != TemporalPresence.InBoth && overlap.isDefined)(
            bad("communityOverlap", "only for in-both")
          ),
          from.toVector.flatMap(ResearcherWindowState.check(at(p, "from"), _)),
          to.toVector.flatMap(ResearcherWindowState.check(at(p, "to"), _)),
          Validated.nonNegative(at(p, "retainedCoauthors"), t.retainedCoauthors),
          Validated.nonNegative(at(p, "gainedCoauthors"), t.gainedCoauthors),
          Validated.nonNegative(at(p, "lostCoauthors"), t.lostCoauthors),
          from.toVector.flatMap(s =>
            when(t.retainedCoauthors + t.lostCoauthors != s.distinctCoauthorCount)(
              bad("retainedCoauthors,lostCoauthors", "sum to the fromWindow distinctCoauthorCount")
            )
          ),
          to.toVector.flatMap(s =>
            when(t.retainedCoauthors + t.gainedCoauthors != s.distinctCoauthorCount)(
              bad("retainedCoauthors,gainedCoauthors", "sum to the toWindow distinctCoauthorCount")
            )
          ),
          (overlap, from, to) match {
            case (Some(o), Some(a), Some(b)) =>
              when(o < 1 || o > math.min(a.communitySize, b.communitySize))(
                bad("communityOverlap", "in [1, min(community sizes)]: the researcher is in both")
              )
            case _ => Vector.empty
          },
          AnalysisLineage.check(at(p, "lineage"), t.lineage),
          provenance(p, t.researcherId, t.provenance, t.lineage)
        )
      }
    }
}

// ------------------------------------------------------------------ work and topic summaries (item 10)

/** Kind of record an entity summary fact cites. Frozen tags. */
enum EntityEvidenceKind(val tag: String) {

  /** A [[GraphNodeV2]] of the run's canonical tables (ID of any canonical node kind). */
  case GraphNode extends EntityEvidenceKind("graph-node")

  /** A [[GraphEdgeV2]] of the run's canonical tables (`gedge:` ID). */
  case GraphEdge extends EntityEvidenceKind("graph-edge")

  /** A curated source work record (`work:` ID) that is a member of the subject canonical work. */
  case SourceWork extends EntityEvidenceKind("source-work")
}
object EntityEvidenceKind {
  def fromTag(tag: String): Option[EntityEvidenceKind] = values.find(_.tag == tag)
  given ReadWriter[EntityEvidenceKind] = EnumCodec.tagged("EntityEvidenceKind", values.toSeq, _.tag)
}

@allowUnknownKeys(false)
final case class EntityEvidenceRef(kind: EntityEvidenceKind, id: String) derives ReadWriter

/**
 * One fact of a work or topic summary. `name` is from the frozen vocabulary of
 * [[StructureValidation.EntityFactVocabulary]], and `value` is exactly the named field of the first
 * cited record of the named kind; never computed from text.
 */
@allowUnknownKeys(false)
final case class EntityFact(name: String, value: String, evidence: Vector[EntityEvidenceRef])
    derives ReadWriter

/**
 * Input of one selected paper or topic summary (schema `entity-summary-input.v1`, course item 10).
 * Not windowed: the subject is a canonical work (`work-summary`, a Work node) or a topic
 * (`topic-summary`, a Topic node). Which works and topics are selected is the producer's choice
 * (for example the works on stored evidence paths); every fact is verified against the canonical
 * tables and the curated records ([[StructureValidation.checkEntitySummaries]]).
 *
 * @param inputId
 *   [[EntitySummaryInputId.of]](documentType, subjectId, graph policy)
 * @param facts
 *   non-empty; (name, value) pairs distinct
 * @param provenance
 *   `scholarchain-derived`; `sourceRecordId` = `inputId`; graph policy
 */
@allowUnknownKeys(false)
final case class EntitySummaryInput(
    schemaVersion: String,
    inputId: String,
    documentType: DocumentType,
    subjectId: String,
    facts: Vector[EntityFact],
    lineage: AnalysisLineage,
    provenance: Provenance
) derives ReadWriter

object EntitySummaryInput {

  /** Subject ID kind of each entity summary document type. */
  val SubjectKind: Map[DocumentType, IdKind] = Map(
    DocumentType.WorkSummary -> IdKind.Work,
    DocumentType.TopicSummary -> IdKind.Topic
  )

  /** ID kind each evidence kind is resolved by (graph nodes: any canonical node kind). */
  private def idProblems(r: EntityEvidenceRef, field: String): Vector[ValidationError] =
    r.kind match {
      case EntityEvidenceKind.GraphNode =>
        StableId.checkOneOf(
          CanonicalGraph.NodeLabels.map(GraphNode.IdKindOf).toSet,
          r.id,
          at(field, "id")
        )
      case EntityEvidenceKind.GraphEdge => StableId.check(IdKind.GraphEdge, r.id, at(field, "id"))
      case EntityEvidenceKind.SourceWork => StableId.check(IdKind.Work, r.id, at(field, "id"))
    }

  given contract: RecordContract[EntitySummaryInput] =
    new RecordContract[EntitySummaryInput](
      "EntitySummaryInput",
      SchemaVersions.EntitySummaryInput,
      true
    ) {
      def schemaVersionOf(t: EntitySummaryInput): String = t.schemaVersion
      def provenanceOf(t: EntitySummaryInput): Provenance = t.provenance
      protected def fields(t: EntitySummaryInput, p: String): Vector[ValidationError] = {
        val facts = Option(t.facts).getOrElse(Vector.empty)
        val kind = Option(t.documentType).flatMap(SubjectKind.get)
        all(
          when(t.documentType == null)(ValidationError.MissingField(at(p, "documentType"))),
          when(t.documentType != null && kind.isEmpty)(
            ValidationError.InvalidValue(
              at(p, "documentType"),
              s"'${t.documentType.tag}' is not a work or topic summary"
            )
          ),
          kind.toVector.flatMap(k => StableId.check(k, t.subjectId, at(p, "subjectId"))),
          when(t.facts == null || facts.isEmpty)(ValidationError.MissingField(at(p, "facts"))),
          facts.zipWithIndex.flatMap { case (fact, i) =>
            val f = at(p, s"facts[$i]")
            if (fact == null) Vector(ValidationError.MissingField(f))
            else {
              val ev = Option(fact.evidence).getOrElse(Vector.empty)
              all(
                AssignmentRules.token(at(f, "name"), fact.name),
                Validated.text(at(f, "value"), fact.value),
                when(ev.isEmpty)(ValidationError.MissingField(at(f, "evidence"))),
                ev.zipWithIndex.flatMap { case (r, j) =>
                  val g = at(f, s"evidence[$j]")
                  if (r == null || r.kind == null) Vector(ValidationError.MissingField(g))
                  else idProblems(r, g)
                },
                when(ev.filter(_ != null).distinct.size != ev.size)(
                  ValidationError.InvalidValue(at(f, "evidence"), "contains duplicate values")
                )
              )
            }
          },
          when(
            facts.filter(_ != null).map(x => (x.name, x.value)).distinct.size != facts.count(
              _ != null
            )
          )(
            ValidationError.InvalidValue(at(p, "facts"), "(name, value) pairs must be distinct")
          ),
          StableId.check(IdKind.SummaryInput, t.inputId, at(p, "inputId")),
          (policyOf(t.provenance), kind) match {
            case (Some(pv), Some(_)) =>
              formula(
                at(p, "inputId"),
                t.inputId,
                EntitySummaryInputId.of(t.documentType, t.subjectId, pv)
              )
            case _ => Vector.empty
          },
          AnalysisLineage.check(at(p, "lineage"), t.lineage),
          provenance(p, t.inputId, t.provenance, t.lineage)
        )
      }
    }
}

// ------------------------------------------------------------------ load roles (item 11)

/**
 * One logical role of a store load (the `targetRole` of a [[LoadItem]]): the target store, the
 * record schema the role's parts hold, and the formats allowed. For Neptune, the node label or edge
 * class the CSV file holds ([[NeptuneCsv]]). Physical names (tables, indexes, buckets, endpoints)
 * are never part of a role; the loader maps roles to them from configuration.
 */
final case class LoadRole(
    target: TargetStore,
    role: String,
    recordSchemaVersion: String,
    formats: Set[PartFormat],
    nodeLabel: Option[NodeLabel] = None,
    edgeLabel: Option[EdgeLabel] = None
)

/**
 * The frozen load-role vocabulary of an analysis run (DE-P6-03, versioned by
 * `store-load-manifest.v1`; serialization contract only, no loader):
 *   - `neptune-bulk-load`: one role per canonical node label (`researcher-vertices`,
 *     `author-mention-vertices`, `work-vertices`, `venue-vertices`, `institution-vertices`,
 *     `topic-vertices`) and edge class (`authored-edges`, `resolves-to-edges`,
 *     `coauthored-with-edges`, `published-in-edges`, `affiliated-with-edges`, `has-topic-edges`):
 *     `graph-node.v2` / `graph-edge.v2` in the [[NeptuneCsv]] layout, CSV only.
 *   - `dynamodb`: the per-key lookup datasets (metrics, edges, memberships, bridges, coreness,
 *     paths, windows, window structures, path-length estimates, window comparisons, temporal
 *     changes), in an analysis export format.
 *   - `s3-vectors`: the summary inputs to embed (`summary-inputs`, `entity-summary-inputs`).
 *   - `s3`: every analysis dataset (the archive copy), including the canonical tables
 *     (`graph-nodes`, `graph-edges`).
 */
object LoadRoles {
  private val exports: Set[PartFormat] = PartFormat.AnalysisExportFormats.toSet
  private val csv: Set[PartFormat] = Set(PartFormat.Csv)

  private def kebab(s: String): String =
    s.flatMap(c => if (c.isUpper) "-" + c.toLower else c.toString).stripPrefix("-")

  /** `researcher-vertices`, `author-mention-vertices`, ... */
  def vertexRole(l: NodeLabel): String = kebab(l.tag) + "-vertices"

  /** `authored-edges`, `resolves-to-edges`, ... */
  def edgeRole(l: EdgeLabel): String = l.tag.toLowerCase.replace('_', '-') + "-edges"

  private val lookups: Vector[(String, String)] = Vector(
    "researcher-metrics" -> SchemaVersions.ResearcherMetricsV2,
    "coauthor-edges" -> SchemaVersions.CoauthorEdgeV2,
    "component-memberships" -> SchemaVersions.ComponentMembership,
    "community-memberships" -> SchemaVersions.CommunityMembership,
    "bridge-measures" -> SchemaVersions.BridgeMeasure,
    "coreness" -> SchemaVersions.Coreness,
    "evidence-paths" -> SchemaVersions.EvidencePath,
    "analysis-windows" -> SchemaVersions.AnalysisWindow,
    "window-structures" -> SchemaVersions.WindowStructure,
    "path-length-estimates" -> SchemaVersions.PathLengthEstimate,
    "window-comparisons" -> SchemaVersions.WindowComparison,
    "temporal-changes" -> SchemaVersions.ResearcherTemporalChange
  )

  private val summaries: Vector[(String, String)] = Vector(
    "summary-inputs" -> SchemaVersions.SemanticSummaryInput,
    "entity-summary-inputs" -> SchemaVersions.EntitySummaryInput
  )

  val all: Vector[LoadRole] =
    CanonicalGraph.NodeLabels.map(l =>
      LoadRole(
        TargetStore.NeptuneBulkLoad,
        vertexRole(l),
        SchemaVersions.GraphNodeV2,
        csv,
        nodeLabel = Some(l)
      )
    ) ++ CanonicalGraph.EdgeLabels.map(l =>
      LoadRole(
        TargetStore.NeptuneBulkLoad,
        edgeRole(l),
        SchemaVersions.GraphEdgeV2,
        csv,
        edgeLabel = Some(l)
      )
    ) ++ lookups.map { case (r, s) => LoadRole(TargetStore.DynamoDb, r, s, exports) } ++
      summaries.map { case (r, s) => LoadRole(TargetStore.S3Vectors, r, s, exports) } ++
      (lookups ++ summaries ++ Vector(
        "graph-nodes" -> SchemaVersions.GraphNodeV2,
        "graph-edges" -> SchemaVersions.GraphEdgeV2
      )).map { case (r, s) => LoadRole(TargetStore.S3, r, s, exports) }

  def of(target: TargetStore, role: String): Option[LoadRole] =
    all.find(r => r.target == target && r.role == role)
}
