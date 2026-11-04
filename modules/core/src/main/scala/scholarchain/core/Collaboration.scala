package scholarchain.core

import upickle.default.ReadWriter
import upickle.implicits.allowUnknownKeys

import Validated.{all, at, nonEmpty, when}

/**
 * The author team of one work after identity resolution.
 *
 * @param researcherIds
 *   distinct resolved researchers, in author order
 * @param teamSize
 *   number of author mentions on the work (the M_r basis)
 * @param unresolvedMentionIds
 *   mentions not resolved to a researcher
 */
@allowUnknownKeys(false)
final case class PublicationTeam(
    schemaVersion: String,
    workId: String,
    year: Option[Int],
    researcherIds: Vector[String],
    teamSize: Int,
    unresolvedMentionIds: Vector[String],
    provenance: Provenance
) derives ReadWriter

object PublicationTeam {
  given contract: RecordContract[PublicationTeam] =
    new RecordContract[PublicationTeam]("PublicationTeam", SchemaVersions.PublicationTeam, true) {
      def schemaVersionOf(t: PublicationTeam): String = t.schemaVersion
      def provenanceOf(t: PublicationTeam): Provenance = t.provenance
      protected def fields(t: PublicationTeam, p: String): Vector[ValidationError] = {
        val members = Option(t.researcherIds).getOrElse(Vector.empty)
        val unresolved = Option(t.unresolvedMentionIds).getOrElse(Vector.empty)
        all(
          StableId.check(IdKind.Work, t.workId, at(p, "workId")),
          Option(t.year).getOrElse(None).toVector.flatMap(Validated.year(at(p, "year"), _)),
          when(t.researcherIds == null)(ValidationError.MissingField(at(p, "researcherIds"))),
          members.zipWithIndex.flatMap { case (r, i) =>
            StableId.check(IdKind.Researcher, r, at(p, s"researcherIds[$i]"))
          },
          Validated.distinct(at(p, "researcherIds"), members),
          when(t.unresolvedMentionIds == null)(
            ValidationError.MissingField(at(p, "unresolvedMentionIds"))
          ),
          unresolved.zipWithIndex.flatMap { case (m, i) =>
            StableId.check(IdKind.Mention, m, at(p, s"unresolvedMentionIds[$i]"))
          },
          Validated.distinct(at(p, "unresolvedMentionIds"), unresolved),
          when(t.teamSize < 1)(ValidationError.InvalidValue(at(p, "teamSize"), "must be >= 1")),
          when(t.teamSize < members.size + unresolved.size)(
            ValidationError.InvalidValue(
              at(p, "teamSize"),
              "smaller than resolved researchers plus unresolved mentions"
            )
          )
        )
      }
    }
}

/**
 * One undirected coauthor pair emitted for one work (before consolidation), schema
 * `coauthor-pair-candidate.v2`. Endpoints are researcher IDs or, before identity resolution, author
 * mention IDs, and are stored in canonical order (`endpointA < endpointB`, see [[CanonicalPair]]).
 *
 * Positions travel with their endpoints: `positionA` is the 0-based author position of `endpointA`
 * on the work and `positionB` that of `endpointB`. Canonical order is by endpoint ID, not by
 * position, so `positionA > positionB` is normal. Positions are distinct and below `teamSize`; for
 * an author mention endpoint the position must reproduce the mention ID ([[MentionId.of]]).
 */
@allowUnknownKeys(false)
final case class CoauthorPairCandidate(
    schemaVersion: String,
    endpointA: String,
    endpointB: String,
    positionA: Int,
    positionB: Int,
    workId: String,
    year: Option[Int],
    teamSize: Int,
    provenance: Provenance
) derives ReadWriter

object CoauthorPairCandidate {

  /**
   * Builds a candidate from two endpoints and their author positions on the work. The pair is put
   * in canonical order, and each position follows its endpoint.
   */
  def of(
      x: String,
      positionX: Int,
      y: String,
      positionY: Int,
      workId: String,
      year: Option[Int],
      teamSize: Int,
      provenance: Provenance
  ): Either[Vector[ValidationError], CoauthorPairCandidate] =
    CanonicalPair.of(x, y, "endpointA,endpointB") match {
      case Left(e) => Left(Vector(e))
      case Right(pair) =>
        val (pa, pb) = if (pair.first == x) (positionX, positionY) else (positionY, positionX)
        val c = CoauthorPairCandidate(
          SchemaVersions.CoauthorPairCandidate,
          pair.first,
          pair.second,
          pa,
          pb,
          workId,
          year,
          teamSize,
          provenance
        )
        val errors = contract.validate(c)
        if (errors.isEmpty) Right(c) else Left(errors)
    }

  private val EndpointKinds: Set[IdKind] = Set(IdKind.Researcher, IdKind.Mention)

  private def positionCheck(
      field: String,
      endpoint: String,
      position: Int,
      t: CoauthorPairCandidate
  ): Vector[ValidationError] =
    if (position < 0) Vector(ValidationError.InvalidValue(field, s"$position is negative"))
    else if (t.teamSize >= 2 && position >= t.teamSize)
      Vector(ValidationError.InvalidValue(field, s"$position is not below teamSize ${t.teamSize}"))
    else if (StableId.prefixKind(endpoint).contains(IdKind.Mention))
      MentionId.of(t.workId, position) match {
        case Right(expected) =>
          when(expected != endpoint)(
            ValidationError.InvalidValue(field, s"is not the position of $endpoint on ${t.workId}")
          )
        case Left(_) => Vector.empty
      }
    else Vector.empty

  given contract: RecordContract[CoauthorPairCandidate] =
    new RecordContract[CoauthorPairCandidate](
      "CoauthorPairCandidate",
      SchemaVersions.CoauthorPairCandidate,
      true
    ) {
      def schemaVersionOf(t: CoauthorPairCandidate): String = t.schemaVersion
      def provenanceOf(t: CoauthorPairCandidate): Provenance = t.provenance
      protected def fields(t: CoauthorPairCandidate, p: String): Vector[ValidationError] =
        all(
          StableId.checkOneOf(EndpointKinds, t.endpointA, at(p, "endpointA")),
          StableId.checkOneOf(EndpointKinds, t.endpointB, at(p, "endpointB")),
          CanonicalPair.checkStored(at(p, "endpointA,endpointB"), t.endpointA, t.endpointB),
          StableId.check(IdKind.Work, t.workId, at(p, "workId")),
          Option(t.year).getOrElse(None).toVector.flatMap(Validated.year(at(p, "year"), _)),
          when(t.teamSize < 2)(ValidationError.InvalidValue(at(p, "teamSize"), "must be >= 2")),
          positionCheck(at(p, "positionA"), t.endpointA, t.positionA, t),
          positionCheck(at(p, "positionB"), t.endpointB, t.positionB, t),
          when(t.positionA == t.positionB)(
            ValidationError.InvalidValue(at(p, "positionA,positionB"), "positions must differ")
          )
        )
    }
}

/**
 * Consolidated undirected coauthorship edge for one canonical researcher pair in one window.
 * `researcherA < researcherB` (canonical order); `edgeId` is [[CoauthorEdgeId.of]] over the pair,
 * the window, and `provenance.policyVersion` (required).
 *
 * @param representativeWorkIds
 *   supporting works, distinct, at most `jointPublicationCount`
 */
@allowUnknownKeys(false)
final case class CoauthorEdge(
    schemaVersion: String,
    edgeId: String,
    researcherA: String,
    researcherB: String,
    window: TimeWindow,
    jointPublicationCount: Long,
    firstCollaborationYear: Int,
    lastCollaborationYear: Int,
    representativeWorkIds: Vector[String],
    provenance: Provenance
) derives ReadWriter

object CoauthorEdge {

  /** Builds an edge: orders the pair canonically and computes the edge ID. */
  def build(
      x: String,
      y: String,
      window: TimeWindow,
      jointPublicationCount: Long,
      firstCollaborationYear: Int,
      lastCollaborationYear: Int,
      representativeWorkIds: Vector[String],
      provenance: Provenance
  ): Either[Vector[ValidationError], CoauthorEdge] = {
    val built = for {
      pair <- CanonicalPair.researchers(x, y)
      policy <- Option(provenance)
        .flatMap(_.policyVersion)
        .toRight(ValidationError.MissingField("provenance.policyVersion"))
      id <- CoauthorEdgeId.of(pair, window, policy)
    } yield CoauthorEdge(
      SchemaVersions.CoauthorEdge,
      id,
      pair.first,
      pair.second,
      window,
      jointPublicationCount,
      firstCollaborationYear,
      lastCollaborationYear,
      representativeWorkIds,
      provenance
    )
    built match {
      case Left(e) => Left(Vector(e))
      case Right(edge) =>
        val errors = contract.validate(edge)
        if (errors.isEmpty) Right(edge) else Left(errors)
    }
  }

  given contract: RecordContract[CoauthorEdge] =
    new RecordContract[CoauthorEdge]("CoauthorEdge", SchemaVersions.CoauthorEdge, true) {
      def schemaVersionOf(t: CoauthorEdge): String = t.schemaVersion
      def provenanceOf(t: CoauthorEdge): Provenance = t.provenance
      protected def fields(t: CoauthorEdge, p: String): Vector[ValidationError] = {
        val reps = Option(t.representativeWorkIds).getOrElse(Vector.empty)
        val policy = Option(t.provenance).flatMap(pr => Option(pr.policyVersion).flatten)
        val windowErrors = TimeWindow.check(at(p, "window"), t.window)
        all(
          StableId.check(IdKind.Researcher, t.researcherA, at(p, "researcherA")),
          StableId.check(IdKind.Researcher, t.researcherB, at(p, "researcherB")),
          CanonicalPair.checkStored(at(p, "researcherA,researcherB"), t.researcherA, t.researcherB),
          windowErrors,
          when(t.jointPublicationCount < 1)(
            ValidationError.InvalidValue(at(p, "jointPublicationCount"), "must be >= 1")
          ),
          when(t.firstCollaborationYear > t.lastCollaborationYear)(
            ValidationError.InvalidWindow(
              at(p, "firstCollaborationYear,lastCollaborationYear"),
              t.firstCollaborationYear,
              t.lastCollaborationYear
            )
          ),
          when(
            windowErrors.isEmpty && (!t.window.contains(t.firstCollaborationYear) ||
              !t.window.contains(t.lastCollaborationYear))
          )(
            ValidationError.InvalidValue(
              at(p, "firstCollaborationYear,lastCollaborationYear"),
              "outside the edge window"
            )
          ),
          when(reps.isEmpty)(ValidationError.MissingField(at(p, "representativeWorkIds"))),
          reps.zipWithIndex.flatMap { case (w, i) =>
            StableId.check(IdKind.Work, w, at(p, s"representativeWorkIds[$i]"))
          },
          Validated.distinct(at(p, "representativeWorkIds"), reps),
          when(reps.size.toLong > t.jointPublicationCount && t.jointPublicationCount >= 1)(
            ValidationError.InvalidValue(
              at(p, "representativeWorkIds"),
              "more works than jointPublicationCount"
            )
          ),
          when(policy.isEmpty)(ValidationError.MissingField(at(p, "provenance.policyVersion"))),
          StableId.check(IdKind.CoauthorEdge, t.edgeId, at(p, "edgeId")),
          (CanonicalPair.of(t.researcherA, t.researcherB), policy) match {
            case (Right(pair), Some(pv)) if windowErrors.isEmpty && pair.first == t.researcherA =>
              CoauthorEdgeId.of(pair, t.window, pv) match {
                case Right(expected) =>
                  when(expected != t.edgeId)(
                    ValidationError.InvalidValue(at(p, "edgeId"), s"expected $expected")
                  )
                case Left(_) => Vector.empty
              }
            case _ => Vector.empty
          }
        )
      }
    }
}

/**
 * Spark-computed collaboration metrics of one researcher in one explicit window. The six
 * specification quantities are separate fields and are never combined into one score:
 *   - P_r `publicationCount`, C_r `distinctCoauthorCount`, T_r `totalCoauthorOccurrences`,
 *   - A_r `averageCoauthorsPerPaper`, M_r `maximumCoauthorsOnOnePaper`, W_r `weightedDegree`.
 *
 * @param maximumTeamWorkIds
 *   works that produced M_r (non-empty when P_r > 0)
 * @param bridgeMeasure
 *   name of the bridge measure behind `bridgeScore` (required with it)
 */
@allowUnknownKeys(false)
final case class ResearcherMetrics(
    schemaVersion: String,
    researcherId: String,
    window: TimeWindow,
    publicationCount: Long,
    distinctCoauthorCount: Long,
    totalCoauthorOccurrences: Long,
    averageCoauthorsPerPaper: Double,
    maximumCoauthorsOnOnePaper: Long,
    maximumTeamWorkIds: Vector[String],
    weightedDegree: Double,
    componentId: String,
    componentSize: Long,
    communityId: Option[String],
    bridgeScore: Option[Double],
    bridgeMeasure: Option[String],
    coreness: Option[Int],
    provenance: Provenance
) derives ReadWriter

object ResearcherMetrics {
  given contract: RecordContract[ResearcherMetrics] =
    new RecordContract[ResearcherMetrics](
      "ResearcherMetrics",
      SchemaVersions.ResearcherMetrics,
      true
    ) {
      def schemaVersionOf(t: ResearcherMetrics): String = t.schemaVersion
      def provenanceOf(t: ResearcherMetrics): Provenance = t.provenance
      protected def fields(t: ResearcherMetrics, p: String): Vector[ValidationError] = {
        val maxWorks = Option(t.maximumTeamWorkIds).getOrElse(Vector.empty)
        val bridge = Option(t.bridgeScore).getOrElse(None)
        val measure = Option(t.bridgeMeasure).getOrElse(None)
        all(
          StableId.check(IdKind.Researcher, t.researcherId, at(p, "researcherId")),
          TimeWindow.check(at(p, "window"), t.window),
          Validated.nonNegative(at(p, "publicationCount"), t.publicationCount),
          Validated.nonNegative(at(p, "distinctCoauthorCount"), t.distinctCoauthorCount),
          Validated.nonNegative(at(p, "totalCoauthorOccurrences"), t.totalCoauthorOccurrences),
          Validated.nonNegativeD(at(p, "averageCoauthorsPerPaper"), t.averageCoauthorsPerPaper),
          Validated.nonNegative(at(p, "maximumCoauthorsOnOnePaper"), t.maximumCoauthorsOnOnePaper),
          Validated.nonNegativeD(at(p, "weightedDegree"), t.weightedDegree),
          when(t.distinctCoauthorCount > t.totalCoauthorOccurrences)(
            ValidationError.InvalidValue(
              at(p, "distinctCoauthorCount"),
              "exceeds totalCoauthorOccurrences"
            )
          ),
          when(t.maximumCoauthorsOnOnePaper > t.totalCoauthorOccurrences)(
            ValidationError.InvalidValue(
              at(p, "maximumCoauthorsOnOnePaper"),
              "exceeds totalCoauthorOccurrences"
            )
          ),
          when(
            t.publicationCount == 0 && (t.totalCoauthorOccurrences != 0 ||
              t.averageCoauthorsPerPaper != 0.0 || maxWorks.nonEmpty)
          )(
            ValidationError.InvalidValue(
              at(p, "publicationCount"),
              "zero publications require zero coauthor counts and no maximum-team works"
            )
          ),
          when(t.publicationCount > 0 && maxWorks.isEmpty)(
            ValidationError.MissingField(at(p, "maximumTeamWorkIds"))
          ),
          maxWorks.zipWithIndex.flatMap { case (w, i) =>
            StableId.check(IdKind.Work, w, at(p, s"maximumTeamWorkIds[$i]"))
          },
          Validated.distinct(at(p, "maximumTeamWorkIds"), maxWorks),
          when(t.maximumTeamWorkIds == null)(
            ValidationError.MissingField(at(p, "maximumTeamWorkIds"))
          ),
          nonEmpty(at(p, "componentId"), t.componentId),
          when(t.componentSize < 1)(
            ValidationError.InvalidValue(at(p, "componentSize"), "must be >= 1")
          ),
          Option(t.communityId).getOrElse(None).toVector.flatMap(nonEmpty(at(p, "communityId"), _)),
          bridge.toVector.flatMap(Validated.finite(at(p, "bridgeScore"), _)),
          when(bridge.isDefined && measure.forall(_.trim.isEmpty))(
            ValidationError.MissingField(at(p, "bridgeMeasure"))
          ),
          Option(t.coreness).getOrElse(None).toVector.flatMap { c =>
            when(c < 0)(ValidationError.InvalidValue(at(p, "coreness"), "negative"))
          }
        )
      }
    }
}
