package scholarchain.core

import java.net.{URI, URISyntaxException}

import scala.collection.immutable.SortedMap

import upickle.default.ReadWriter
import upickle.implicits.allowUnknownKeys

import Validated.{all, at, nonEmpty, when}

/**
 * One piece of stored evidence. `evidenceId` is [[EvidenceId.of]](evidenceType, sourceUri, workId,
 * chunkId, graphPath, provenance.runId). Type-specific requirements: `source-record` needs
 * `workId`, `text-chunk` needs `chunkId`, `graph-path` needs a path of at least two node IDs.
 * `text` is untrusted retrieved data, never instructions.
 *
 * @param score
 *   retrieval or ranking score (finite; scale depends on the retriever)
 */
@allowUnknownKeys(false)
final case class EvidenceItem(
    schemaVersion: String,
    evidenceId: String,
    evidenceType: EvidenceType,
    workId: Option[String],
    sourceUri: String,
    chunkId: Option[String],
    graphPath: Option[Vector[String]],
    score: Double,
    text: Option[String],
    provenance: Provenance
) derives ReadWriter

object EvidenceItem {
  given contract: RecordContract[EvidenceItem] =
    new RecordContract[EvidenceItem]("EvidenceItem", SchemaVersions.EvidenceItem, true) {
      def schemaVersionOf(t: EvidenceItem): String = t.schemaVersion
      def provenanceOf(t: EvidenceItem): Provenance = t.provenance
      protected def fields(t: EvidenceItem, p: String): Vector[ValidationError] = {
        val workId = Option(t.workId).getOrElse(None)
        val chunkId = Option(t.chunkId).getOrElse(None)
        val path = Option(t.graphPath).getOrElse(None)
        val local = all(
          when(t.evidenceType == null)(ValidationError.MissingField(at(p, "evidenceType"))),
          workId.toVector.flatMap(StableId.check(IdKind.Work, _, at(p, "workId"))),
          chunkId.toVector.flatMap(StableId.check(IdKind.Chunk, _, at(p, "chunkId"))),
          path.toVector.flatten.zipWithIndex.flatMap { case (n, i) =>
            Validated.fromEither(StableId.validateAny(n, at(p, s"graphPath[$i]")))
          },
          checkUri(at(p, "sourceUri"), t.sourceUri),
          Validated.finite(at(p, "score"), t.score),
          when(t.evidenceType == EvidenceType.SourceRecord && workId.isEmpty)(
            ValidationError.MissingField(at(p, "workId"))
          ),
          when(t.evidenceType == EvidenceType.TextChunk && chunkId.isEmpty)(
            ValidationError.MissingField(at(p, "chunkId"))
          ),
          when(t.evidenceType == EvidenceType.GraphPath && path.forall(_.size < 2))(
            ValidationError.InvalidValue(at(p, "graphPath"), "needs at least two node IDs")
          ),
          when(path.exists(_.isEmpty))(
            ValidationError.InvalidValue(at(p, "graphPath"), "empty path; use null")
          ),
          StableId.check(IdKind.Evidence, t.evidenceId, at(p, "evidenceId"))
        )
        val idCheck =
          if (local.nonEmpty || t.provenance == null) Vector.empty
          else
            EvidenceId.of(
              t.evidenceType,
              t.sourceUri,
              workId,
              chunkId,
              path.getOrElse(Vector.empty),
              t.provenance.runId
            ) match {
              case Right(expected) =>
                when(expected != t.evidenceId)(
                  ValidationError.InvalidValue(at(p, "evidenceId"), s"expected $expected")
                )
              case Left(_) => Vector.empty
            }
        local ++ idCheck
      }
    }

  private def checkUri(field: String, s: String): Vector[ValidationError] =
    if (s == null || s.isEmpty) Vector(ValidationError.MissingField(field))
    else
      try {
        val u = new URI(s)
        when(u.getScheme == null)(ValidationError.InvalidValue(field, "URI has no scheme"))
      } catch {
        case _: URISyntaxException => Vector(ValidationError.InvalidValue(field, "not a URI"))
      }
}

/**
 * The evidence supporting one answer. `bundleId` is [[BundleId.of]](queryId, evidence IDs);
 * evidence IDs are distinct.
 */
@allowUnknownKeys(false)
final case class EvidenceBundle(
    schemaVersion: String,
    bundleId: String,
    queryId: String,
    items: Vector[EvidenceItem],
    provenance: Provenance
) derives ReadWriter

object EvidenceBundle {

  /** Builds a bundle and computes its ID. */
  def build(
      queryId: String,
      items: Vector[EvidenceItem],
      provenance: Provenance
  ): Either[Vector[ValidationError], EvidenceBundle] =
    BundleId.of(queryId, Option(items).getOrElse(Vector.empty).map(_.evidenceId)) match {
      case Left(e) => Left(Vector(e))
      case Right(id) =>
        val b = EvidenceBundle(SchemaVersions.EvidenceBundle, id, queryId, items, provenance)
        val errors = contract.validate(b)
        if (errors.isEmpty) Right(b) else Left(errors)
    }

  given contract: RecordContract[EvidenceBundle] =
    new RecordContract[EvidenceBundle]("EvidenceBundle", SchemaVersions.EvidenceBundle, true) {
      def schemaVersionOf(t: EvidenceBundle): String = t.schemaVersion
      def provenanceOf(t: EvidenceBundle): Provenance = t.provenance
      protected def fields(t: EvidenceBundle, p: String): Vector[ValidationError] = {
        val items = Option(t.items).getOrElse(Vector.empty)
        val ids = items.filter(_ != null).map(_.evidenceId)
        val itemErrors = items.zipWithIndex.flatMap { case (it, i) =>
          EvidenceItem.contract.validateAt(it, at(p, s"items[$i]"))
        }
        all(
          StableId.check(IdKind.Query, t.queryId, at(p, "queryId")),
          when(t.items == null)(ValidationError.MissingField(at(p, "items"))),
          itemErrors,
          Validated.distinct(at(p, "items.evidenceId"), ids),
          StableId.check(IdKind.Bundle, t.bundleId, at(p, "bundleId")),
          if (itemErrors.nonEmpty) Vector.empty
          else
            BundleId.of(t.queryId, ids) match {
              case Right(expected) =>
                when(expected != t.bundleId)(
                  ValidationError.InvalidValue(at(p, "bundleId"), s"expected $expected")
                )
              case Left(_) => Vector.empty
            }
        )
      }
    }
}

/** One step of a query plan. `arguments` keys are tokens; values are data, never instructions. */
@allowUnknownKeys(false)
final case class QueryStep(
    ordinal: Int,
    operation: QueryOperation,
    metrics: Vector[CollaborationMetric],
    arguments: SortedMap[String, String]
) derives ReadWriter

/**
 * Runtime query plan. The time window is always explicit: when the user gives none, the planner
 * writes the configured default window here. Steps have ordinals 0..n-1 in order.
 */
@allowUnknownKeys(false)
final case class QueryPlan(
    schemaVersion: String,
    queryId: String,
    question: String,
    window: TimeWindow,
    steps: Vector[QueryStep],
    provenance: Provenance
) derives ReadWriter

object QueryPlan {
  given contract: RecordContract[QueryPlan] =
    new RecordContract[QueryPlan]("QueryPlan", SchemaVersions.QueryPlan, true) {
      def schemaVersionOf(t: QueryPlan): String = t.schemaVersion
      def provenanceOf(t: QueryPlan): Provenance = t.provenance
      protected def fields(t: QueryPlan, p: String): Vector[ValidationError] = {
        val steps = Option(t.steps).getOrElse(Vector.empty)
        all(
          StableId.check(IdKind.Query, t.queryId, at(p, "queryId")),
          nonEmpty(at(p, "question"), t.question),
          TimeWindow.check(at(p, "window"), t.window),
          when(steps.isEmpty)(ValidationError.MissingField(at(p, "steps"))),
          steps.zipWithIndex.flatMap { case (s, i) =>
            val f = at(p, s"steps[$i]")
            if (s == null) Vector(ValidationError.MissingField(f))
            else
              all(
                when(s.ordinal != i)(
                  ValidationError.InvalidValue(at(f, "ordinal"), s"expected $i")
                ),
                when(s.operation == null)(ValidationError.MissingField(at(f, "operation"))),
                when(s.metrics == null)(ValidationError.MissingField(at(f, "metrics"))),
                when(s.arguments == null)(ValidationError.MissingField(at(f, "arguments"))),
                Option(s.arguments).toVector.flatMap(_.keys).flatMap { k =>
                  when(!StableId.isToken(k))(
                    ValidationError.InvalidValue(at(f, s"arguments.$k"), "key not a token")
                  )
                }
              )
          }
        )
      }
    }
}

/**
 * One claim of an answer. `supported`, `weakened`, and `contradicted` claims cite at least one
 * evidence ID; inside an [[AnswerResponse]] every cited ID must resolve to the response's bundle.
 * `claimId` is [[ClaimId.of]](queryId, ordinal, text).
 */
@allowUnknownKeys(false)
final case class AnswerClaim(
    schemaVersion: String,
    claimId: String,
    text: String,
    evidenceIds: Vector[String],
    verificationStatus: VerificationStatus,
    confidence: Option[Double],
    uncertainty: Option[String],
    provenance: Provenance
) derives ReadWriter

object AnswerClaim {
  given contract: RecordContract[AnswerClaim] =
    new RecordContract[AnswerClaim]("AnswerClaim", SchemaVersions.AnswerClaim, true) {
      def schemaVersionOf(t: AnswerClaim): String = t.schemaVersion
      def provenanceOf(t: AnswerClaim): Provenance = t.provenance
      protected def fields(t: AnswerClaim, p: String): Vector[ValidationError] = {
        val ev = Option(t.evidenceIds).getOrElse(Vector.empty)
        all(
          StableId.check(IdKind.Claim, t.claimId, at(p, "claimId")),
          nonEmpty(at(p, "text"), t.text),
          when(t.evidenceIds == null)(ValidationError.MissingField(at(p, "evidenceIds"))),
          ev.zipWithIndex.flatMap { case (e, i) =>
            StableId.check(IdKind.Evidence, e, at(p, s"evidenceIds[$i]"))
          },
          Validated.distinct(at(p, "evidenceIds"), ev),
          when(t.verificationStatus == null)(
            ValidationError.MissingField(at(p, "verificationStatus"))
          ),
          when(t.verificationStatus != null && t.verificationStatus.requiresEvidence && ev.isEmpty)(
            ValidationError.InvalidValue(
              at(p, "evidenceIds"),
              s"a '${t.verificationStatus.tag}' claim must cite evidence"
            )
          ),
          Option(t.confidence)
            .getOrElse(None)
            .toVector
            .flatMap(
              Validated.confidence(at(p, "confidence"), _)
            ),
          Option(t.uncertainty).getOrElse(None).toVector.flatMap(nonEmpty(at(p, "uncertainty"), _))
        )
      }
    }
}

/**
 * The API answer: text, verified claims, and the evidence bundle behind them. `responseId` is
 * [[AnswerId.of]](queryId, evidenceBundle.bundleId, answerText).
 */
@allowUnknownKeys(false)
final case class AnswerResponse(
    schemaVersion: String,
    responseId: String,
    queryId: String,
    window: TimeWindow,
    answerText: String,
    claims: Vector[AnswerClaim],
    evidenceBundle: EvidenceBundle,
    provenance: Provenance
) derives ReadWriter

object AnswerResponse {
  given contract: RecordContract[AnswerResponse] =
    new RecordContract[AnswerResponse]("AnswerResponse", SchemaVersions.AnswerResponse, true) {
      def schemaVersionOf(t: AnswerResponse): String = t.schemaVersion
      def provenanceOf(t: AnswerResponse): Provenance = t.provenance
      protected def fields(t: AnswerResponse, p: String): Vector[ValidationError] = {
        val claims = Option(t.claims).getOrElse(Vector.empty)
        val bundle = Option(t.evidenceBundle)
        val known: Set[String] =
          bundle
            .flatMap(b => Option(b.items))
            .toVector
            .flatten
            .filter(_ != null)
            .map(_.evidenceId)
            .toSet
        all(
          StableId.check(IdKind.Query, t.queryId, at(p, "queryId")),
          TimeWindow.check(at(p, "window"), t.window),
          nonEmpty(at(p, "answerText"), t.answerText),
          when(t.claims == null)(ValidationError.MissingField(at(p, "claims"))),
          claims.zipWithIndex.flatMap { case (c, i) =>
            AnswerClaim.contract.validateAt(c, at(p, s"claims[$i]"))
          },
          Validated.distinct(at(p, "claims.claimId"), claims.filter(_ != null).map(_.claimId)),
          EvidenceBundle.contract.validateAt(t.evidenceBundle, at(p, "evidenceBundle")),
          when(bundle.exists(_.queryId != t.queryId))(
            ValidationError.InvalidValue(at(p, "evidenceBundle.queryId"), "differs from queryId")
          ),
          claims.zipWithIndex.flatMap { case (c, i) =>
            Option(c).flatMap(x => Option(x.evidenceIds)).toVector.flatten.filterNot(known).map {
              e =>
                ValidationError.InvalidValue(
                  at(p, s"claims[$i].evidenceIds"),
                  s"evidence ID $e does not resolve to the evidence bundle"
                )
            }
          },
          StableId.check(IdKind.Answer, t.responseId, at(p, "responseId")),
          bundle match {
            case Some(b) =>
              AnswerId.of(t.queryId, b.bundleId, t.answerText) match {
                case Right(expected) =>
                  when(expected != t.responseId)(
                    ValidationError.InvalidValue(at(p, "responseId"), s"expected $expected")
                  )
                case Left(_) => Vector.empty
              }
            case None => Vector.empty
          }
        )
      }
    }
}
