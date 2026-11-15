package scholarchain.entityresolution.adjudication

import scala.collection.immutable.SortedMap

import upickle.default.{write, ReadWriter}

import scholarchain.core.Canonical
import scholarchain.entityresolution.policy.{MatchPolicy, ResolutionOutcome}

/**
 * Display context of one endpoint for the adjudicator: its ID, dataset, and a few text lines (name
 * as written, work title, venue, year). Untrusted source text: it is data in the prompt, never an
 * instruction. Lines are bounded ([[EndpointEvidence.bounded]]).
 */
final case class EndpointEvidence(id: String, sourceDataset: String, text: Vector[String])
    derives ReadWriter

object EndpointEvidence {
  val MaxLines: Int = 8
  val MaxLineLength: Int = 300

  /** Prompt data delimiters; source text can never reproduce them. */
  val Delimiters: Vector[String] = Vector("BEGIN_CANDIDATE_DATA", "END_CANDIDATE_DATA")

  private def neutralize(s: String): String =
    Delimiters.foldLeft(s)((acc, d) => acc.replaceAll("(?i)" + d, "[delimiter removed]"))

  /**
   * At most [[MaxLines]] lines of at most [[MaxLineLength]] code points, controls removed, prompt
   * delimiters neutralized.
   */
  def bounded(e: EndpointEvidence): EndpointEvidence =
    e.copy(text = Option(e.text).getOrElse(Vector.empty).take(MaxLines).map { line =>
      val clean =
        neutralize(Option(line).getOrElse("").map(c => if (Character.isISOControl(c)) ' ' else c))
      val n = clean.codePointCount(0, clean.length)
      if (n <= MaxLineLength) clean
      else clean.substring(0, clean.offsetByCodePoints(0, MaxLineLength))
    })
}

/**
 * The adjudication input, serialized once as canonical JSON: that JSON is both embedded in the
 * prompt and hashed for the cache key. The score is deliberately not included (the adjudicator
 * judges the evidence, not the policy's estimate).
 *
 * @param allowedEvidence
 *   every evidence reference the response may cite: the candidate ID, both endpoint IDs, `exact-id:
 *   <scheme>:<value>` for each identifier agreement, and `feature:<name>` for each feature
 */
final case class AdjudicationRequest(
    requestSchemaVersion: String,
    candidateId: String,
    entityKind: String,
    policyVersion: String,
    promptVersion: String,
    responseSchemaVersion: String,
    endpointA: EndpointEvidence,
    endpointB: EndpointEvidence,
    exactIdAgreements: Vector[String],
    features: SortedMap[String, Double],
    allowedEvidence: Vector[String],
    allowedReasonCodes: Vector[String]
) derives ReadWriter

/** The three adjudication outcomes of the strict response schema. */
enum AdjudicationDecision(val tag: String) {
  case Accept extends AdjudicationDecision("accept")
  case Reject extends AdjudicationDecision("reject")
  case Abstain extends AdjudicationDecision("abstain")
}

/** A response that passed [[AdjudicationProtocol.validateResponse]]. */
final case class ValidatedResponse(
    decision: AdjudicationDecision,
    evidence: Vector[String],
    reasonCodes: Vector[String]
)

/** Cache key: input hash, prompt version, model version, policy version (README Phase 5). */
final case class AdjudicationCacheKey(
    inputHash: String,
    promptVersion: String,
    modelVersion: String,
    policyVersion: String
)

/**
 * The versioned adjudication protocol: request construction, the canonical input hash, the prompt
 * template, and the strict response validator.
 */
object AdjudicationProtocol {
  val RequestSchemaVersion: String = "er-adjudication-request.v1"
  val ResponseSchemaVersion: String = "er-adjudication-response.v1"

  /**
   * The active prompt (er-policy.v4). v2 states the publication-stage target of the label guide
   * er-label-guide.v1: a work pair is the same entity only at the same publication stage, and a
   * preprint against its published version is a different entity.
   */
  val PromptVersion: String = "er-adjudication-prompt.v2"

  val PromptResource: String =
    "/scholarchain/entityresolution/adjudication/er-adjudication-prompt.v2.txt"

  /**
   * The retired v1 prompt ("the same published work", no stage target). Kept as history and to
   * prove that it is refused ([[MatchPolicy.RetiredPromptVersions]]).
   */
  val RetiredV1PromptResource: String =
    "/scholarchain/entityresolution/adjudication/er-adjudication-prompt.v1.txt"
  val SchemaResource: String =
    "/scholarchain/entityresolution/adjudication/er-adjudication-response.v1.schema.json"

  /** Exactly these top-level keys, all required. */
  val ResponseKeys: Vector[String] =
    Vector("schemaVersion", "candidateId", "decision", "evidence", "reasonCodes")

  /** Closed reason-code vocabulary of the response. */
  val ReasonCodes: Vector[String] = Vector(
    "affiliation-consistent",
    "affiliation-inconsistent",
    "coauthors-overlap",
    "coauthors-disjoint",
    "different-publication",
    "insufficient-evidence",
    "names-consistent",
    "names-inconsistent",
    "same-publication",
    "title-consistent",
    "title-inconsistent",
    "venue-consistent",
    "venue-inconsistent",
    "year-consistent",
    "year-inconsistent"
  )

  val PromptVersionHeader: String = "prompt-version: "
  val RequestPlaceholder: String = "{{REQUEST_JSON}}"
  val SchemaPlaceholder: String = "{{RESPONSE_SCHEMA}}"

  /** The evidence references a response may cite for this outcome. */
  def allowedEvidence(o: ResolutionOutcome): Vector[String] =
    (Vector(o.candidateId, o.sourceIdA, o.sourceIdB) ++
      o.exactIdAgreements.map(e => s"exact-id:${e.scheme.tag}:${e.value}") ++
      o.features.keys.map(k => s"feature:$k")).distinct.sorted

  def request(
      p: MatchPolicy,
      o: ResolutionOutcome,
      a: EndpointEvidence,
      b: EndpointEvidence
  ): AdjudicationRequest =
    AdjudicationRequest(
      RequestSchemaVersion,
      o.candidateId,
      o.entityKind.tag,
      o.policyVersion,
      p.adjudication.promptVersion,
      p.adjudication.responseSchemaVersion,
      EndpointEvidence.bounded(a),
      EndpointEvidence.bounded(b),
      o.exactIdAgreements.map(e => s"${e.scheme.tag}:${e.value}"),
      o.features,
      allowedEvidence(o),
      ReasonCodes
    )

  /** Canonical request JSON (frozen field order, sorted features). */
  def requestJson(r: AdjudicationRequest): String = write(r)

  /** SHA-256 hex of the canonical request JSON. */
  def inputHash(r: AdjudicationRequest): String = Canonical.sha256HexUtf8(requestJson(r))

  def cacheKey(r: AdjudicationRequest, modelVersion: String): AdjudicationCacheKey =
    AdjudicationCacheKey(inputHash(r), r.promptVersion, modelVersion, r.policyVersion)

  /** A loaded prompt template with its declared version. */
  final case class PromptTemplate(version: String, body: String) {

    /** The prompt: the schema and the request JSON inserted as delimited data. */
    def render(schemaJson: String, requestJson: String): String =
      body.replace(SchemaPlaceholder, schemaJson).replace(RequestPlaceholder, requestJson)
  }

  /**
   * Parses a template: the first line declares the version; both placeholders must occur once. A
   * template that declares a retired version ([[MatchPolicy.RetiredPromptVersions]]) is refused.
   */
  def parseTemplate(text: String): Either[String, PromptTemplate] = {
    val nl = Option(text).map(_.indexOf('\n')).getOrElse(-1)
    if (nl < 0) Left("prompt template has no version header line")
    else {
      val header = text.substring(0, nl)
      val body = text.substring(nl + 1)
      val version = header.stripPrefix(PromptVersionHeader).trim
      def once(ph: String) = body.indexOf(ph) >= 0 && body.indexOf(ph) == body.lastIndexOf(ph)
      if (!header.startsWith(PromptVersionHeader)) Left("first line must be 'prompt-version: <v>'")
      else if (MatchPolicy.RetiredPromptVersions.contains(version))
        Left(s"prompt template $version is retired from active use; use $PromptVersion")
      else if (!once(RequestPlaceholder) || !once(SchemaPlaceholder))
        Left(s"template must contain $RequestPlaceholder and $SchemaPlaceholder exactly once")
      else Right(PromptTemplate(version, body))
    }
  }

  /** The prompt template and schema resources of this module. */
  def loadDefault(): Either[String, (PromptTemplate, String)] =
    for {
      t <- MatchPolicy.resourceText(PromptResource).flatMap(parseTemplate)
      s <- MatchPolicy.resourceText(SchemaResource)
    } yield (t, s)

  /**
   * Validates an untrusted response against the strict schema. Refuses: invalid or non-strict JSON
   * (duplicate keys, trailing content), a non-object, missing or extra keys, a wrong schema
   * version, a candidate ID other than the request's, a decision outside accept/reject/abstain,
   * evidence or reason codes that are not distinct string arrays within the policy limits, any
   * evidence reference not in the request's `allowedEvidence` (an identifier not present in the
   * candidate), any reason code outside the vocabulary, and an accept or reject without evidence.
   */
  def validateResponse(
      raw: String,
      r: AdjudicationRequest,
      maxEvidence: Int,
      maxReasons: Int
  ): Either[String, ValidatedResponse] =
    StrictJson.parse(raw).flatMap {
      case JValue.JObj(members) =>
        val m = members.toMap
        val keys = members.map(_._1)
        val extra = keys.filterNot(ResponseKeys.contains)
        val missing = ResponseKeys.filterNot(keys.contains)
        def strings(field: String, max: Int): Either[String, Vector[String]] = m(field) match {
          case JValue.JArr(items) =>
            val ss = items.collect { case JValue.JStr(s) => s }
            if (ss.size != items.size) Left(s"$field must contain only strings")
            else if (ss.size > max) Left(s"$field has more than $max items")
            else if (ss.distinct.size != ss.size) Left(s"$field has duplicates")
            else Right(ss)
          case _ => Left(s"$field must be an array")
        }
        if (extra.nonEmpty) Left(s"unknown key(s): ${extra.mkString(", ")}")
        else if (missing.nonEmpty) Left(s"missing key(s): ${missing.mkString(", ")}")
        else
          for {
            _ <- m("schemaVersion") match {
              case JValue.JStr(v) if v == r.responseSchemaVersion => Right(())
              case _ => Left(s"schemaVersion must be ${r.responseSchemaVersion}")
            }
            _ <- m("candidateId") match {
              case JValue.JStr(v) if v == r.candidateId => Right(())
              case _ => Left("candidateId differs from the request")
            }
            d <- m("decision") match {
              case JValue.JStr(v) =>
                AdjudicationDecision.values
                  .find(_.tag == v)
                  .toRight("decision must be accept, reject, or abstain")
              case _ => Left("decision must be a string")
            }
            ev <- strings("evidence", maxEvidence)
            rc <- strings("reasonCodes", maxReasons)
            _ <- ev.find(e => !r.allowedEvidence.contains(e)) match {
              case Some(bad) => Left(s"evidence '$bad' is not an identifier of the candidate")
              case None => Right(())
            }
            _ <- rc.find(c => !r.allowedReasonCodes.contains(c)) match {
              case Some(bad) => Left(s"reason code '$bad' is not in the vocabulary")
              case None => Right(())
            }
            _ <-
              if (d != AdjudicationDecision.Abstain && ev.isEmpty)
                Left(s"${d.tag} requires at least one evidence reference")
              else Right(())
          } yield ValidatedResponse(d, ev, rc)
      case _ => Left("response is not a JSON object")
    }
}
