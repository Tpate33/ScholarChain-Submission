package scholarchain.core

import java.time.Instant
import java.time.format.DateTimeParseException

import scala.collection.immutable.SortedMap

import upickle.default.ReadWriter
import upickle.implicits.allowUnknownKeys

import Validated.{all, at, when}

/**
 * One identifier conflict between the two sides of a [[MatchDecision]]: side A is the decision's
 * `sourceId`, side B its `targetId`. Pairwise by construction: it never names a third record.
 *
 *   - `identifier-disagreement`: both sides assert `scheme` and their canonical value sets are
 *     disjoint. `valuesA` and `valuesB` are every value of `scheme` that each side asserts.
 *     `contradictingRule` is absent. A disagreement alone does not establish that the pair is a
 *     conflicted identity: the policy decides whether the pair is a plausible match (CONFLICT) or
 *     not (REJECTED or UNRESOLVED, with the disagreement recorded as evidence).
 *   - `agreement-contradicted`: both sides share the values in `valuesA` (equal to `valuesB`), but
 *     the policy rule named by `contradictingRule` (a token, for example a same-work or a
 *     homonym-number rule) contradicts that agreement. Only allowed on a CONFLICT decision.
 *
 * Values are canonical for `scheme` ([[Identifiers.validateExact]]), sorted by UTF-8 bytes without
 * duplicates, and non-empty on both sides; `scheme` identifies the decision's entity kind.
 */
@allowUnknownKeys(false)
final case class IdentifierConflict(
    kind: IdentifierConflictKind,
    scheme: ExactIdScheme,
    valuesA: Vector[String],
    valuesB: Vector[String],
    contradictingRule: Option[String]
) derives ReadWriter

object IdentifierConflict {

  private def sorted(vs: Iterable[String]): Vector[String] =
    vs.toVector.distinct.sortWith((a, b) => Canonical.compareUtf8(a, b) < 0)

  private def valuesOf(scheme: ExactIdScheme, ids: Vector[ExactId]): Vector[String] =
    sorted(
      Option(ids)
        .getOrElse(Vector.empty)
        .iterator
        .filter(e => e != null && e.scheme == scheme)
        .map(_.value)
        .toVector
    )

  /**
   * The disagreement of `scheme` between the identifier sets of side A and side B: Some exactly
   * when both sides assert the scheme and share no value (the same rule as the `id.<scheme>.both`
   * and `id.<scheme>.conflict` candidate features).
   */
  def disagreement(
      scheme: ExactIdScheme,
      idsA: Vector[ExactId],
      idsB: Vector[ExactId]
  ): Option[IdentifierConflict] = {
    val a = valuesOf(scheme, idsA)
    val b = valuesOf(scheme, idsB)
    if (scheme == null || a.isEmpty || b.isEmpty || a.exists(b.toSet)) None
    else Some(IdentifierConflict(IdentifierConflictKind.Disagreement, scheme, a, b, None))
  }

  /** The agreement on `values` of `scheme`, contradicted by the policy rule `rule`. */
  def contradictedAgreement(
      scheme: ExactIdScheme,
      values: Vector[String],
      rule: String
  ): IdentifierConflict = {
    val v = sorted(Option(values).getOrElse(Vector.empty))
    IdentifierConflict(IdentifierConflictKind.ContradictedAgreement, scheme, v, v, Some(rule))
  }

  /** The same conflict seen from the other side (A and B exchanged). */
  def swapSides(c: IdentifierConflict): IdentifierConflict =
    c.copy(valuesA = c.valuesB, valuesB = c.valuesA)

  /** Scheme tag, then kind tag, then contradicting rule ("" when absent), by UTF-8 bytes. */
  def compare(a: IdentifierConflict, b: IdentifierConflict): Int = {
    val s = Canonical.compareUtf8(a.scheme.tag, b.scheme.tag)
    if (s != 0) s
    else {
      val k = Canonical.compareUtf8(a.kind.tag, b.kind.tag)
      if (k != 0) k
      else
        Canonical.compareUtf8(a.contradictingRule.getOrElse(""), b.contradictingRule.getOrElse(""))
    }
  }

  /** Sorted ([[compare]]), duplicate-free copy. */
  def canonical(cs: Vector[IdentifierConflict]): Vector[IdentifierConflict] =
    cs.distinct.sortWith((a, b) => compare(a, b) < 0)

  private def sideCheck(
      field: String,
      scheme: ExactIdScheme,
      raw: Vector[String]
  ): Vector[ValidationError] = {
    val vs = Option(raw).getOrElse(Vector.empty)
    when(vs.isEmpty)(ValidationError.MissingField(field)) ++
      Option(scheme).toVector.flatMap { s =>
        vs.zipWithIndex.flatMap { case (v, i) => ExactId.check(s"$field[$i]", ExactId(s, v)) }
      } ++
      Validated.strictlyAscending(field, vs)
  }

  /** One conflict of a decision about entity kind `kind`. */
  def check(field: String, c: IdentifierConflict, kind: MatchEntityKind): Vector[ValidationError] =
    if (c == null) Vector(ValidationError.MissingField(field))
    else {
      val a = Option(c.valuesA).getOrElse(Vector.empty)
      val b = Option(c.valuesB).getOrElse(Vector.empty)
      val rule = Option(c.contradictingRule).getOrElse(None)
      all(
        when(c.kind == null)(ValidationError.MissingField(at(field, "kind"))),
        when(c.scheme == null)(ValidationError.MissingField(at(field, "scheme"))),
        when(c.scheme != null && kind != null && c.scheme.entityKind != kind)(
          ValidationError.InvalidValue(
            at(field, "scheme"),
            s"'${c.scheme.tag}' does not identify a ${kind.tag}"
          )
        ),
        sideCheck(at(field, "valuesA"), c.scheme, c.valuesA),
        sideCheck(at(field, "valuesB"), c.scheme, c.valuesB),
        Option(c.kind).toVector.flatMap {
          case IdentifierConflictKind.Disagreement =>
            when(a.exists(b.toSet))(
              ValidationError.InvalidValue(
                at(field, "valuesB"),
                "an identifier disagreement needs disjoint value sets"
              )
            ) ++ when(rule.isDefined)(
              ValidationError.InvalidValue(
                at(field, "contradictingRule"),
                "only allowed for agreement-contradicted"
              )
            )
          case IdentifierConflictKind.ContradictedAgreement =>
            when(a != b)(
              ValidationError.InvalidValue(
                at(field, "valuesB"),
                "a contradicted agreement lists the same agreeing values on both sides"
              )
            ) ++ when(!rule.exists(StableId.isToken))(
              ValidationError.InvalidValue(
                at(field, "contradictingRule"),
                "required: the token of the policy rule that contradicts the agreement"
              )
            )
        }
      )
    }

  /** Each conflict valid, and the list strictly ascending by [[compare]]. */
  def checkList(
      field: String,
      cs: Vector[IdentifierConflict],
      kind: MatchEntityKind
  ): Vector[ValidationError] =
    if (cs == null) Vector(ValidationError.MissingField(field))
    else {
      val each = cs.zipWithIndex.flatMap { case (c, i) => check(s"$field[$i]", c, kind) }
      each ++ when(
        each.isEmpty && cs.sliding(2).exists(w => w.size == 2 && compare(w(0), w(1)) >= 0)
      )(
        ValidationError
          .InvalidValue(field, "must be sorted by (scheme, kind, rule) without duplicates")
      )
    }
}

/**
 * Reference to the validated LLM adjudication that decided a [[MatchDecision]] on the
 * `llm-adjudicated` path. No model text is stored: only the cache key inputs and the validated,
 * structured response (decision, evidence references, reason codes).
 *
 * @param inputHash
 *   SHA-256 (64 lowercase hex) of the canonical adjudication request
 * @param promptVersion
 *   prompt version (token); equals `provenance.promptVersion` of the decision
 * @param modelVersion
 *   model ID (printable ASCII without whitespace); equals `provenance.modelVersion`
 * @param policyVersion
 *   match policy version (token); equals `provenance.policyVersion`
 * @param decision
 *   accept, reject, or abstain; implies the decision's outcome ([[AdjudicationVerdict.outcome]])
 * @param evidenceIds
 *   evidence references cited by the response, sorted by UTF-8 bytes without duplicates; non-empty
 *   for accept and reject. Each is the decision's `candidateId`, `sourceId`, or `targetId`;
 *   `feature:<name>` for a key of the decision's `features`; or `exact-id:<scheme tag>:<canonical
 *   value>` of a scheme of the decision's entity kind. Nothing else is allowed, so a response
 *   cannot introduce an identifier that the candidate does not carry by this route.
 * @param reasonCodes
 *   response reason codes (tokens), sorted by UTF-8 bytes without duplicates
 */
@allowUnknownKeys(false)
final case class AdjudicationReference(
    inputHash: String,
    promptVersion: String,
    modelVersion: String,
    policyVersion: String,
    decision: AdjudicationVerdict,
    evidenceIds: Vector[String],
    reasonCodes: Vector[String]
) derives ReadWriter

object AdjudicationReference {
  val FeaturePrefix = "feature:"
  val ExactIdPrefix = "exact-id:"

  /** Checks that need no decision context. */
  def check(field: String, r: AdjudicationReference): Vector[ValidationError] =
    if (r == null) Vector(ValidationError.MissingField(field))
    else {
      val ev = Option(r.evidenceIds).getOrElse(Vector.empty)
      val codes = Option(r.reasonCodes).getOrElse(Vector.empty)
      all(
        when(!Canonical.isHex64(r.inputHash))(
          ValidationError.InvalidValue(at(field, "inputHash"), "expected 64 lowercase hex")
        ),
        when(!StableId.isToken(r.promptVersion))(
          ValidationError.InvalidValue(at(field, "promptVersion"), "missing or not a token")
        ),
        when(!Identifiers.isPrintableAscii(r.modelVersion))(
          ValidationError.InvalidValue(
            at(field, "modelVersion"),
            "missing or not printable ASCII without whitespace"
          )
        ),
        when(!StableId.isToken(r.policyVersion))(
          ValidationError.InvalidValue(at(field, "policyVersion"), "missing or not a token")
        ),
        when(r.decision == null)(ValidationError.MissingField(at(field, "decision"))),
        when(r.evidenceIds == null)(ValidationError.MissingField(at(field, "evidenceIds"))),
        ev.zipWithIndex.flatMap { case (e, i) =>
          when(!Identifiers.isPrintableAscii(e))(
            ValidationError.InvalidValue(
              at(field, s"evidenceIds[$i]"),
              "not printable ASCII without whitespace"
            )
          )
        },
        Validated.strictlyAscending(at(field, "evidenceIds"), ev),
        when(r.decision != null && r.decision != AdjudicationVerdict.Abstain && ev.isEmpty)(
          ValidationError.InvalidValue(
            at(field, "evidenceIds"),
            s"'${r.decision.tag}' requires at least one evidence reference"
          )
        ),
        when(r.reasonCodes == null)(ValidationError.MissingField(at(field, "reasonCodes"))),
        codes.zipWithIndex.flatMap { case (c, i) =>
          when(!StableId.isToken(c))(
            ValidationError.InvalidValue(at(field, s"reasonCodes[$i]"), "not a token")
          )
        },
        Validated.strictlyAscending(at(field, "reasonCodes"), codes)
      )
    }

  /** Why `e` is not an allowed evidence reference of decision `d`, or None when it is. */
  def evidenceProblem(e: String, d: MatchDecision): Option[String] =
    if (e == null) Some("missing")
    else if (e == d.candidateId || e == d.sourceId || e == d.targetId) None
    else if (e.startsWith(FeaturePrefix)) {
      val name = e.substring(FeaturePrefix.length)
      if (d.features != null && d.features.contains(name)) None
      else Some("names no feature of this decision")
    } else if (e.startsWith(ExactIdPrefix)) {
      val rest = e.substring(ExactIdPrefix.length)
      val i = rest.indexOf(':')
      (if (i <= 0) None else ExactIdScheme.fromTag(rest.substring(0, i))) match {
        case None => Some("names no exact identifier scheme")
        case Some(s) if d.entityKind != null && s.entityKind != d.entityKind =>
          Some(s"scheme '${s.tag}' does not identify a ${d.entityKind.tag}")
        case Some(s) =>
          Identifiers
            .validateExact(s, rest.substring(i + 1))
            .left
            .toOption
            .map(_ => s"not a canonical ${s.tag} value")
      }
    } else
      Some(
        "must be the candidate ID, an endpoint ID, feature:<name>, or exact-id:<scheme>:<value>"
      )
}

/**
 * One entity-resolution decision about one [[MatchCandidate]] pair (schema `match-decision.v2`).
 * Pairwise: side A is `sourceId` (the candidate's `sourceIdA`), side B is `targetId` (its
 * `sourceIdB`), and the pair is stored in canonical order (`sourceId < targetId` by UTF-8 bytes).
 * The outcome says what the policy concluded about whether A and B are the same entity; it says
 * nothing about any third record.
 *
 * @param decisionId
 *   [[MatchDecisionId.of]](entityKind, sourceId, targetId, provenance.policyVersion,
 *   provenance.runId)
 * @param candidateId
 *   the decided [[MatchCandidate]]; equals `provenance.sourceRecordId`
 * @param entityKind
 *   `researcher` (endpoints are author mention or source author profile IDs) or `work` (work IDs)
 * @param matchPath
 *   the evidence level that decided the pair
 * @param decision
 *   ACCEPTED, REJECTED, UNRESOLVED, or CONFLICT
 * @param confidence
 *   in [0,1]: the policy's confidence that `decision` is correct (policy-defined; not a score)
 * @param score
 *   the policy's match score in [0,1], kept separate from `confidence`; required on the
 *   `probabilistic` and `llm-adjudicated` paths, optional on `exact-id` and
 *   `deterministic-bibliographic`
 * @param blockingVersion
 *   blocking-key version of the candidate (token)
 * @param featureVersion
 *   feature version of the candidate (token)
 * @param features
 *   the features the policy scored: token keys, finite values, ordered by key
 * @param rulesFired
 *   policy rules that fired, in evaluation order: non-empty, tokens, distinct
 * @param reasonCodes
 *   reason codes: non-empty, tokens, distinct
 * @param conflicts
 *   identifier conflicts between side A and side B, sorted ([[IdentifierConflict.compare]]):
 *   non-empty for CONFLICT, empty for ACCEPTED (an identifier conflict always prevents automatic
 *   acceptance), and only disagreements on REJECTED or UNRESOLVED
 * @param adjudication
 *   present exactly on the `llm-adjudicated` path
 * @param decidedAt
 *   ISO-8601 instant (UTC `Z`). Not an input of any ID
 * @param provenance
 *   `scholarchain-derived`; `sourceRecordId` = `candidateId`; `sourceSchemaVersion` = the
 *   candidate's schema (`scholarchain.match-candidate.v<N>`); `runId` = the decision run;
 *   `policyVersion` (token) required; `modelVersion` and `promptVersion` set exactly when an
 *   adjudication is recorded, and equal to its versions
 */
@allowUnknownKeys(false)
final case class MatchDecision(
    schemaVersion: String,
    decisionId: String,
    candidateId: String,
    entityKind: MatchEntityKind,
    sourceId: String,
    targetId: String,
    matchPath: MatchDecisionPath,
    decision: MatchOutcome,
    confidence: Double,
    score: Option[Double],
    blockingVersion: String,
    featureVersion: String,
    features: SortedMap[String, Double],
    rulesFired: Vector[String],
    reasonCodes: Vector[String],
    conflicts: Vector[IdentifierConflict],
    adjudication: Option[AdjudicationReference],
    decidedAt: String,
    provenance: Provenance
) derives ReadWriter

object MatchDecision {

  /** Every accepted `provenance.sourceSchemaVersion` starts with this (a match candidate). */
  val CandidateSchemaPrefix: String = "scholarchain.match-candidate.v"

  /**
   * Builds a decision: puts the pair in canonical order (exchanging the conflict sides with it),
   * sorts the conflicts and the adjudication's evidence and reason codes, computes the decision ID,
   * and validates.
   */
  def build(
      candidateId: String,
      entityKind: MatchEntityKind,
      sourceId: String,
      targetId: String,
      matchPath: MatchDecisionPath,
      decision: MatchOutcome,
      confidence: Double,
      score: Option[Double],
      blockingVersion: String,
      featureVersion: String,
      features: SortedMap[String, Double],
      rulesFired: Vector[String],
      reasonCodes: Vector[String],
      conflicts: Vector[IdentifierConflict],
      adjudication: Option[AdjudicationReference],
      decidedAt: String,
      provenance: Provenance
  ): Either[Vector[ValidationError], MatchDecision] =
    (for {
      p <- Option(provenance).toRight(
        ValidationError.MissingProvenance("MatchDecision", "provenance")
      )
      k <- Option(entityKind).toRight(ValidationError.MissingField("entityKind"))
      policy <- Option(p.policyVersion).flatten
        .toRight(ValidationError.MissingField("provenance.policyVersion"))
      cs <- Option(conflicts)
        .filter(_.forall(c => c != null && c.scheme != null && c.kind != null))
        .toRight(ValidationError.MissingField("conflicts"))
      pair <- CanonicalPair.of(sourceId, targetId, "sourceId,targetId")
      id <- MatchDecisionId.of(k, pair.first, pair.second, policy, p.runId)
    } yield {
      val swapped = pair.first != sourceId
      val sides = if (swapped) cs.map(IdentifierConflict.swapSides) else cs
      def sortedDistinct(v: Vector[String]) =
        Option(v)
          .map(_.distinct.sortWith((a, b) => Canonical.compareUtf8(a, b) < 0))
          .orNull
      MatchDecision(
        SchemaVersions.MatchDecision,
        id,
        candidateId,
        k,
        pair.first,
        pair.second,
        matchPath,
        decision,
        confidence,
        score,
        blockingVersion,
        featureVersion,
        features,
        rulesFired,
        reasonCodes,
        IdentifierConflict.canonical(sides),
        Option(adjudication).flatten
          .map(a =>
            a.copy(
              evidenceIds = sortedDistinct(a.evidenceIds),
              reasonCodes = sortedDistinct(a.reasonCodes)
            )
          ),
        decidedAt,
        p
      )
    }) match {
      case Left(e) => Left(Vector(e))
      case Right(d) =>
        val errors = contract.validate(d)
        if (errors.isEmpty) Right(d) else Left(errors)
    }

  private def tokenField(field: String, v: String): Vector[ValidationError] =
    when(!StableId.isToken(v))(ValidationError.InvalidValue(field, "missing or not a token"))

  private def tokenList(field: String, vs: Vector[String]): Vector[ValidationError] =
    if (vs == null || vs.isEmpty) Vector(ValidationError.MissingField(field))
    else
      vs.zipWithIndex.flatMap { case (v, i) =>
        when(!StableId.isToken(v))(ValidationError.InvalidValue(s"$field[$i]", "not a token"))
      } ++ Validated.distinct(field, vs)

  given contract: RecordContract[MatchDecision] =
    new RecordContract[MatchDecision]("MatchDecision", SchemaVersions.MatchDecision, true) {
      def schemaVersionOf(t: MatchDecision): String = t.schemaVersion
      def provenanceOf(t: MatchDecision): Provenance = t.provenance
      protected def fields(t: MatchDecision, p: String): Vector[ValidationError] = {
        val pr = t.provenance
        val policy = Option(pr).flatMap(x => Option(x.policyVersion).flatten)
        val model = Option(pr).flatMap(x => Option(x.modelVersion).flatten)
        val prompt = Option(pr).flatMap(x => Option(x.promptVersion).flatten)
        val score = Option(t.score).getOrElse(None)
        val conflicts = Option(t.conflicts).getOrElse(Vector.empty)
        val adjudication = Option(t.adjudication).getOrElse(None)
        val path = t.matchPath
        val outcome = t.decision
        val scored =
          path == MatchDecisionPath.Probabilistic || path == MatchDecisionPath.LlmAdjudicated
        val local = all(
          CandidateRules.kindCheck(at(p, "entityKind"), t.entityKind),
          StableId.check(IdKind.MatchCandidate, t.candidateId, at(p, "candidateId")),
          CandidateRules.endpointCheck(at(p, "sourceId"), t.entityKind, t.sourceId),
          CandidateRules.endpointCheck(at(p, "targetId"), t.entityKind, t.targetId),
          CanonicalPair.checkStored(at(p, "sourceId,targetId"), t.sourceId, t.targetId),
          when(path == null)(ValidationError.MissingField(at(p, "matchPath"))),
          when(outcome == null)(ValidationError.MissingField(at(p, "decision"))),
          Validated.confidence(at(p, "confidence"), t.confidence),
          score.toVector.flatMap { s =>
            when(s.isNaN || s.isInfinite || s < 0.0 || s > 1.0)(
              ValidationError.InvalidValue(at(p, "score"), s"$s is outside [0,1]")
            )
          },
          when(scored && score.isEmpty)(
            ValidationError.InvalidValue(
              at(p, "score"),
              s"required on the ${path.tag} path"
            )
          ),
          tokenField(at(p, "blockingVersion"), t.blockingVersion),
          tokenField(at(p, "featureVersion"), t.featureVersion),
          when(t.features == null)(ValidationError.MissingField(at(p, "features"))),
          Option(t.features).toVector.flatten.flatMap { case (k, v) =>
            when(!StableId.isToken(k))(
              ValidationError.InvalidValue(at(p, "features"), s"key '$k' is not a token")
            ) ++ Validated.finite(at(p, s"features.$k"), v)
          },
          tokenList(at(p, "rulesFired"), t.rulesFired),
          tokenList(at(p, "reasonCodes"), t.reasonCodes),
          IdentifierConflict.checkList(at(p, "conflicts"), t.conflicts, t.entityKind),
          when(outcome == MatchOutcome.Conflict && conflicts.isEmpty)(
            ValidationError.InvalidValue(at(p, "conflicts"), "required for CONFLICT")
          ),
          when(outcome == MatchOutcome.Accepted && conflicts.nonEmpty)(
            ValidationError.InvalidValue(
              at(p, "conflicts"),
              "an ACCEPTED decision must not carry identifier conflicts"
            )
          ),
          when(
            outcome != null && outcome != MatchOutcome.Conflict &&
              conflicts.exists(c =>
                c != null && c.kind == IdentifierConflictKind.ContradictedAgreement
              )
          )(
            ValidationError.InvalidValue(
              at(p, "conflicts"),
              "agreement-contradicted is only allowed for CONFLICT"
            )
          ),
          when(outcome == MatchOutcome.Conflict && path == MatchDecisionPath.LlmAdjudicated)(
            ValidationError.InvalidValue(
              at(p, "matchPath"),
              "an LLM adjudication never yields CONFLICT"
            )
          ),
          when(path == MatchDecisionPath.LlmAdjudicated && adjudication.isEmpty)(
            ValidationError.MissingField(at(p, "adjudication"))
          ),
          when(path != null && path != MatchDecisionPath.LlmAdjudicated && adjudication.isDefined)(
            ValidationError.InvalidValue(
              at(p, "adjudication"),
              "only allowed on the llm-adjudicated path"
            )
          ),
          adjudication.toVector.flatMap { a =>
            val f = at(p, "adjudication")
            AdjudicationReference.check(f, a) ++ all(
              when(a.decision != null && outcome != null && a.decision.outcome != outcome)(
                ValidationError.InvalidValue(
                  at(f, "decision"),
                  s"'${a.decision.tag}' implies ${a.decision.outcome.tag}, not ${outcome.tag}"
                )
              ),
              when(policy.isDefined && !policy.contains(a.policyVersion))(
                ValidationError
                  .InvalidValue(at(f, "policyVersion"), "differs from provenance.policyVersion")
              ),
              when(pr != null && !model.contains(a.modelVersion))(
                ValidationError.InvalidValue(
                  at(p, "provenance.modelVersion"),
                  "must equal adjudication.modelVersion"
                )
              ),
              when(pr != null && !prompt.contains(a.promptVersion))(
                ValidationError.InvalidValue(
                  at(p, "provenance.promptVersion"),
                  "must equal adjudication.promptVersion"
                )
              ),
              Option(a.evidenceIds).toVector.flatten.zipWithIndex.flatMap { case (e, i) =>
                AdjudicationReference.evidenceProblem(e, t).toVector.map { why =>
                  ValidationError.InvalidValue(at(f, s"evidenceIds[$i]"), why)
                }
              }
            )
          },
          when(adjudication.isEmpty && (model.isDefined || prompt.isDefined))(
            ValidationError.InvalidValue(
              at(p, "provenance.modelVersion,promptVersion"),
              "set only when an LLM adjudication is recorded"
            )
          ),
          checkInstant(at(p, "decidedAt"), t.decidedAt),
          CandidateRules.derivedCheck(at(p, "provenance.sourceDataset"), pr),
          when(pr != null && t.candidateId != null && pr.sourceRecordId != t.candidateId)(
            ValidationError.InvalidValue(
              at(p, "provenance.sourceRecordId"),
              "must equal candidateId (the decided candidate)"
            )
          ),
          when(
            pr != null && pr.sourceSchemaVersion != null &&
              !pr.sourceSchemaVersion.startsWith(CandidateSchemaPrefix)
          )(
            ValidationError.InvalidValue(
              at(p, "provenance.sourceSchemaVersion"),
              s"must be a match candidate schema ($CandidateSchemaPrefix<N>)"
            )
          ),
          when(policy.isEmpty)(ValidationError.MissingField(at(p, "provenance.policyVersion"))),
          policy.toVector.flatMap(tokenField(at(p, "provenance.policyVersion"), _)),
          StableId.check(IdKind.MatchDecision, t.decisionId, at(p, "decisionId"))
        )
        val idCheck = (t.entityKind, pr, policy) match {
          case (k, x, Some(pv)) if k != null && x != null =>
            MatchDecisionId.of(k, t.sourceId, t.targetId, pv, x.runId) match {
              case Right(expected) =>
                when(expected != t.decisionId)(
                  ValidationError.InvalidValue(at(p, "decisionId"), s"expected $expected")
                )
              case Left(_) => Vector.empty
            }
          case _ => Vector.empty
        }
        local ++ idCheck
      }
    }

  private[core] def checkInstant(field: String, s: String): Vector[ValidationError] =
    if (s == null || s.isEmpty) Vector(ValidationError.MissingField(field))
    else
      try {
        Instant.parse(s)
        when(!s.endsWith("Z"))(ValidationError.InvalidValue(field, "instant must be in UTC (Z)"))
      } catch {
        case _: DateTimeParseException =>
          Vector(ValidationError.InvalidValue(field, s"'$s' is not an ISO-8601 instant"))
      }
}
