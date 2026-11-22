package scholarchain.rag.agents

import scholarchain.core.{
  AnswerClaim,
  ClaimId,
  EvidenceBundle,
  EvidenceType,
  Provenance,
  SchemaVersions,
  SourceType,
  StableId,
  VerificationStatus
}

/**
 * The README Phase 9 verification outcomes (stage 9), with their exact literal tags, and the core
 * [[VerificationStatus]] each is stored as. The mapping is one-to-one, so no outcome is lost:
 *
 *   - `VERIFIED` -> `supported`: every assertion is a fact of evidence the claim cites, and the
 *     claim's text is rendered from those assertions ([[ClaimRendering]]);
 *   - `PARTIALLY_SUPPORTED` -> `weakened`: at least one assertion is supported and renderable,
 *     something is not; the claim is kept only with an explicit `[PARTIALLY SUPPORTED: ...]` label
 *     (the weakening) followed by the rendering of its usable assertions;
 *   - `UNSUPPORTED` -> `unsupported`: nothing the claim states is usable; such a claim is REMOVED
 *     from the answer and listed in the verification report;
 *   - `CONFLICTING` -> `contradicted`: the bundle holds a different value for a stated subject and
 *     field; the claim is kept only with an explicit `[CONFLICTING WITH EVIDENCE ...]` label, the
 *     rendering of the values the evidence holds, and cites the conflicting evidence.
 */
enum Verdict(val tag: String, val status: VerificationStatus) {
  case Verified extends Verdict("VERIFIED", VerificationStatus.Supported)
  case PartiallySupported extends Verdict("PARTIALLY_SUPPORTED", VerificationStatus.Weakened)
  case Unsupported extends Verdict("UNSUPPORTED", VerificationStatus.Unsupported)
  case Conflicting extends Verdict("CONFLICTING", VerificationStatus.Contradicted)
}

/** The check of one assertion. */
enum AssertionCheck {
  case Supported(evidenceIds: Vector[String])
  case SupportedUncited(evidenceIds: Vector[String])
  case Conflicting(evidenceIds: Vector[String])
  case NotProof
  case Missing
}

/** The verification of one model claim (README stage 9). */
final case class ClaimResult(
    ordinal: Int,
    kind: String,
    verdict: Verdict,
    assertions: Vector[ModelAssertion],
    problems: Vector[String],
    fabricatedEvidenceIds: Vector[String],
    kept: Option[AnswerClaim]
)

/**
 * The verification of one answer: per-claim results, the kept core claims, the missing-evidence
 * lines (RE-P9-04, M3: exactly the workflow-detected gaps, never model text), the number of model
 * missing-evidence lines dropped because they are not a workflow gap, and the overall outcome.
 */
final case class VerificationReport(
    results: Vector[ClaimResult],
    claims: Vector[AnswerClaim],
    missingEvidence: Vector[String],
    droppedMissingEvidence: Int,
    overall: Verdict
) {
  def counts: Map[String, Int] =
    Verdict.values.map(v => v.tag -> results.count(_.verdict == v)).toMap
}

/**
 * RE-P9-02 stage 9, ClaimVerificationAgent (v4, RG-P11-02; v3, RE-P9-05). Deterministic checks of
 * every claim's structured assertions against the evidence bundle's facts ([[BundleFacts]]), never
 * against model text:
 *
 *   1. cited evidence must exist in the bundle (IDs not in it are reported as fabricated, shown
 *      only as a count);
 *   2. every assertion (identity, title, count, team size, path hop and node, community, component,
 *      bridge score, rank, window) must equal a fact of a cited item; a fact of an uncited item is
 *      partial support; another value for the same subject and field is a conflict; a value
 *      supported only by semantic context is never proof of a deterministic field;
 *   3. (RE-P9-05, CR-P9-02 F1) a descriptive field (`title`, `has-context-document`) never supports
 *      a deterministic claim, and a deterministic field never appears in a descriptive claim;
 *   4. (F1) a supported assertion is usable only when [[ClaimRendering]] can render it: its subject
 *      has the field's form, every ID is a printable-ASCII stable ID, its window is a window of the
 *      plan, and its value has the field's grammar (integer, decimal, tag, ID, `true`, or a quoted
 *      string for names and titles);
 *   5. a deterministic claim must cite deterministic evidence; a claim without assertions is
 *      unsupported.
 *
 * The answer text of every kept claim is RENDERED here from its usable assertions with fixed
 * templates ([[ClaimRendering]]); the model's own claim text is never shown and never establishes
 * verification (it is withheld). Negative wording appears only for assertions that are themselves
 * negative facts (`not-connected-within`, `path-inconclusive`). Retrieved titles appear only quoted
 * and attributed as descriptive retrieved context. A rendered text that fails the
 * [[ClaimRendering.safe]] guard (non-ASCII outside quoted tokens, or a non-letter non-ASCII
 * character anywhere) is never kept.
 *
 * Problem labels (the `[PARTIALLY SUPPORTED: ...]` and `[CONFLICTING WITH EVIDENCE ...]` text and
 * the claim's `uncertainty`) are built only from fixed wording, closed-vocabulary field and kind
 * tags, subjects of bundle facts and counts (v4): a model-chosen subject that no bundle fact has is
 * shown as [[ClaimVerificationAgent.SubjectNotInEvidence]].
 *
 * Identity: `resolved-from-name` is checked by the surface-form hash of the stated name against the
 * cited entity-link row (the name is never stored).
 */
final class ClaimVerificationAgent(serving: ServingContext) {
  import ClaimVerificationAgent.*

  private def check(a: ModelAssertion, cited: Set[String], facts: Vector[Fact]): AssertionCheck = {
    val (field, value) =
      if (a.field == FactFields.ResolvedFromName)
        (FactFields.ResolvedSurfaceSha256, SurfaceForms.sha256(a.value))
      else (a.field, a.value)
    val all = facts.filter(f => f.subject == a.subject && f.field == field)
    val usable = usableFacts(all, field)
    val matching = usable.filter(_.value == value)
    if (usable.isEmpty && all.nonEmpty) AssertionCheck.NotProof
    else if (matching.exists(f => cited.contains(f.evidenceId)))
      AssertionCheck.Supported(matching.map(_.evidenceId).filter(cited).distinct)
    else if (matching.nonEmpty) AssertionCheck.SupportedUncited(matching.map(_.evidenceId).distinct)
    else if (usable.nonEmpty) AssertionCheck.Conflicting(usable.map(_.evidenceId).distinct)
    else AssertionCheck.Missing
  }

  private def usableFacts(all: Vector[Fact], field: String): Vector[Fact] =
    if (FactFields.descriptive.contains(field)) all else all.filter(_.deterministic)

  def verify(
      queryId: String,
      bundle: EvidenceBundle,
      facts: Vector[Fact],
      output: SynthesisOutput,
      allowedWindows: Set[String],
      modelVersion: String,
      promptVersion: String,
      workflowGaps: Vector[String] = Vector.empty
  ): VerificationReport = {
    val inBundle = bundle.items.map(_.evidenceId).toSet
    val semantic =
      bundle.items.filter(_.evidenceType == EvidenceType.TextChunk).map(_.evidenceId).toSet
    val prov = Provenance(
      SourceType.QueryRuntime,
      queryId,
      serving.snapshot,
      serving.analysisRunId,
      SchemaVersions.QueryPlan,
      Some(Version),
      Some(modelVersion),
      Some(promptVersion)
    )
    // v4 (RG-P11-02, CR-P11-01-1): a label shows a subject only when it is the subject of a
    // bundle fact; any other subject (a model-chosen ID, an ID with a window outside the plan,
    // free text inside an ID body) is replaced by a fixed placeholder, so no identity, number or
    // window the verifier did not check reaches claim text or uncertainty.
    val knownSubjects = facts.iterator.map(_.subject).toSet
    def subjectLabel(s: String): String =
      if (s != null && knownSubjects.contains(s)) show(s) else SubjectNotInEvidence
    var ordinal = 0
    def keep(
        text: String,
        ids: Vector[String],
        v: Verdict,
        uncertainty: Option[String]
    ): Option[AnswerClaim] = {
      val claim = ClaimId.of(queryId, ordinal, text).toOption.map { cid =>
        AnswerClaim(SchemaVersions.AnswerClaim, cid, text, ids, v.status, None, uncertainty, prov)
      }
      val ok = claim.filter(c => AnswerClaim.contract.validate(c).isEmpty)
      if (ok.isDefined) ordinal += 1
      ok
    }
    val results = output.claims.zipWithIndex.map { case (c, i) =>
      val cited = c.evidenceIds.filter(inBundle)
      val fabricated = c.evidenceIds.filterNot(inBundle)
      val descriptiveKind = Prompts.DescriptiveKinds.contains(c.kind)
      val checks = c.assertions.map(a => a -> check(a, cited.toSet, facts))
      // Rule 3: a field of the wrong family for the claim kind is never support.
      def kindProblem(a: ModelAssertion): Option[String] =
        if (!descriptiveKind && FactFields.descriptive.contains(a.field))
          Some(
            s"a ${c.kind} claim states ${a.field} of ${subjectLabel(a.subject)}, a descriptive field " +
              "that can never support it"
          )
        else if (descriptiveKind && !FactFields.descriptive.contains(a.field))
          Some(s"a ${c.kind} claim states ${a.field}, a deterministic field")
        else None
      val supported = checks.collect { case (a, AssertionCheck.Supported(_)) => a }
      // Rule 4: a supported assertion of the right family must also be renderable.
      val renderProblems = supported
        .filter(a => kindProblem(a).isEmpty)
        .flatMap(a =>
          ClaimRendering
            .problem(a, allowedWindows)
            .map(p => a -> s"${a.field} of ${subjectLabel(a.subject)}: $p")
        )
      val usable =
        supported.filter(a => kindProblem(a).isEmpty && !renderProblems.exists(_._1 == a))
      // v4 (CR-P11-01-2): fabricated evidence IDs are reported as a count only; the IDs stay in
      // ClaimResult.fabricatedEvidenceIds and are never shown.
      val problems =
        (if (fabricated.isEmpty) Vector.empty else Vector(fabricatedLabel(fabricated.size))) ++
          checks.collect {
            case (a, AssertionCheck.SupportedUncited(_)) =>
              s"${a.field} of ${subjectLabel(a.subject)} is supported only by uncited evidence"
            case (a, AssertionCheck.Conflicting(_)) =>
              s"${a.field} of ${subjectLabel(a.subject)} differs from the evidence"
            case (a, AssertionCheck.NotProof) =>
              s"${a.field} of ${subjectLabel(a.subject)} is backed only by semantic context, which is not proof"
            case (a, AssertionCheck.Missing) =>
              s"${a.field} of ${subjectLabel(a.subject)} is not in the evidence"
          } ++
          c.assertions.flatMap(kindProblem).distinct ++
          renderProblems.map(_._2)
      val conflicting = checks.collect { case (a, AssertionCheck.Conflicting(es)) => a -> es }
      val conflicts = conflicting.flatMap(_._2).distinct
      val deterministicCited = cited.exists(e => !semantic.contains(e))
      val verdict0 =
        if (cited.isEmpty) Verdict.Unsupported
        else if (!descriptiveKind && !deterministicCited) Verdict.Unsupported
        else if (c.assertions.isEmpty) Verdict.Unsupported
        else if (conflicts.nonEmpty) Verdict.Conflicting
        else if (usable.size == c.assertions.size && problems.isEmpty) Verdict.Verified
        else if (usable.nonEmpty) Verdict.PartiallySupported
        else Verdict.Unsupported
      val extraProblems =
        (if (cited.isEmpty) Vector("cites no evidence of the bundle") else Vector.empty) ++
          (if (cited.nonEmpty && !descriptiveKind && !deterministicCited)
             Vector(
               "cites only semantic context: vector similarity is not proof of an edge or metric"
             )
           else Vector.empty) ++
          (if (c.assertions.isEmpty) Vector("states no checkable assertion") else Vector.empty)
      val allProblems = problems ++ extraProblems
      val label = allProblems.mkString("; ")
      val body = ClaimRendering.render(usable)
      // The values the bundle holds for each conflicting subject and field (rendered from facts).
      val evidenceStates = ClaimRendering.render(
        conflicting
          .flatMap { case (a, _) =>
            usableFacts(facts.filter(f => f.subject == a.subject && f.field == a.field), a.field)
              .map(f => ModelAssertion(f.subject, f.field, f.value))
          }
          .distinct
          .filter(a => kindProblem(a).isEmpty && ClaimRendering.problem(a, allowedWindows).isEmpty)
      )
      def join(xs: String*): String = xs.filter(_.nonEmpty).mkString(" ")
      val candidate: Option[(String, Vector[String], Option[String])] = verdict0 match {
        case Verdict.Verified => Some((body, cited.distinct, None))
        case Verdict.PartiallySupported =>
          Some((join(s"[PARTIALLY SUPPORTED: $label]", body), cited.distinct, Some(label)))
        case Verdict.Conflicting =>
          Some(
            (
              join(
                s"[CONFLICTING WITH EVIDENCE, not asserted: $label]",
                body,
                if (evidenceStates.isEmpty) "" else s"The evidence bundle states: $evidenceStates"
              ),
              (cited ++ conflicts).distinct,
              Some(label)
            )
          )
        case Verdict.Unsupported => None
      }
      // The final guard: a text that is not provably rendered-safe is never kept.
      val guarded = candidate.filter { case (t, _, _) => t.nonEmpty && ClaimRendering.safe(t) }
      val (verdict, finalProblems) =
        if (candidate.isDefined && guarded.isEmpty)
          (Verdict.Unsupported, allProblems :+ "the rendered text failed the rendering guard")
        else (verdict0, allProblems)
      val kept = guarded.flatMap { case (t, ids, u) => keep(t, ids, verdict, u) }
      ClaimResult(i, c.kind, verdict, c.assertions, finalProblems, fabricated, kept)
    }
    // RE-P9-04 (M3, m2): the answer's missing-evidence lines are the workflow-detected gaps only,
    // verbatim. A model line is never shown: one equal to a gap adds nothing, any other is
    // dropped and counted (audited and surfaced by the workflow as a fixed notice).
    val missing = workflowGaps.distinct
    val dropped = output.missingEvidence.filterNot(missing.contains)
    val verdicts = results.map(_.verdict)
    val overall =
      if (verdicts.isEmpty || verdicts.forall(_ == Verdict.Unsupported)) Verdict.Unsupported
      else if (verdicts.contains(Verdict.Conflicting)) Verdict.Conflicting
      else if (verdicts.forall(_ == Verdict.Verified)) Verdict.Verified
      else Verdict.PartiallySupported
    VerificationReport(results, results.flatMap(_.kept), missing, dropped.size, overall)
  }
}

object ClaimVerificationAgent {

  /**
   * v4 (RG-P11-02, CR-P11-01-1 and -2, Phase 9 residual R1): problem labels show a subject only
   * when it is the subject of a bundle fact (else [[SubjectNotInEvidence]]), and fabricated
   * evidence IDs only as a count ([[fabricatedLabel]]). v3 (RE-P9-05, CR-P9-02 F1): claim text
   * rendered from verified structured assertions ([[ClaimRendering.Version]]); model wording
   * withheld; descriptive fields never support a deterministic claim. v2 (RE-P9-04) had the
   * closed-wording licence, now removed.
   */
  val Version = "scholarchain.rag.claim-verification.v4"

  /** The fixed placeholder for a subject that is not the subject of any bundle fact. */
  val SubjectNotInEvidence = "(a subject not in the evidence)"

  /** The label of `n` cited evidence IDs that are not in the bundle (the IDs are never shown). */
  def fabricatedLabel(n: Int): String = s"cites $n evidence ID(s) not in the evidence bundle"

  /** `s` when it is a printable-ASCII token safe to show in a label, else a fixed placeholder. */
  private[agents] def show(s: String): String =
    if (s != null && ClaimRendering.SafeToken.matches(s)) s else "(an unprintable value)"
}

/**
 * RE-P9-05 (CR-P9-02 F1): the fixed templates that render a claim's answer text from its verified
 * structured assertions (version [[ClaimRendering.Version]]). It replaces the v1 closed wording
 * (`scholarchain.rag.claim-wording.v1`): model prose is never shown, so no word of it can make a
 * claim VERIFIED.
 *
 *   - Every sentence is a code literal (ASCII) with checked tokens inserted: the field tag, a value
 *     of the field's grammar, the subject's IDs and window.
 *   - Names (`resolved-from-name`) and titles (`title`) are inserted only as quoted tokens
 *     ([[ClaimRendering.quote]]): a JSON string literal in which every non-ASCII character that is
 *     not a letter or a non-enclosing combining mark (digits and numerals such as superscript,
 *     circled or Roman numerals, symbols such as enclosed letters, punctuation, spaces, format
 *     characters) is written as a `\\uXXXX` escape.
 *   - Titles and context documents are attributed: their sentence starts with
 *     [[ClaimRendering.DescriptivePrefix]].
 *   - Negative wording exists only in the templates of negative facts (`not-connected-within`,
 *     `path-inconclusive`).
 */
object ClaimRendering {
  val Version = "scholarchain.rag.claim-rendering.v1"

  val DescriptivePrefix =
    "Retrieved descriptive context only (not evidence of an edge or a metric):"

  /** A printable-ASCII token without space, quote or backslash (IDs, subjects, fields). */
  private[agents] val SafeToken = "^[!-~&&[^\"\\\\]]{1,600}$".r

  private val IntValue = "^-?(0|[1-9][0-9]{0,18})$".r
  private val DecimalValue = "^-?[0-9]{1,19}(\\.[0-9]{1,19})?([eE]-?[0-9]{1,3})?$".r
  private val TagValue = "^[a-z0-9][a-z0-9._-]{0,127}$".r
  private val WindowValue = "^[0-9]{4}-[0-9]{4}$".r

  private enum Subject { case Plain, At, Pair }
  private enum Value { case Integer, Decimal, Tag, Id, True, Quoted }
  private enum Style {
    case Has, PairHas, PathHas, Name, ShortestHops, FoundHops, Truncated, NotConnected,
      Inconclusive, Supports, ContextDocument, Title
  }

  private final case class Spec(subject: Subject, value: Value, style: Style)

  private val Specs: Map[String, Spec] = {
    import FactFields.*
    val at = (v: Value) => Spec(Subject.At, v, Style.Has)
    Map(
      ResolvedFromName -> Spec(Subject.Plain, Value.Quoted, Style.Name),
      IdentityStatus -> at(Value.Tag),
      PublicationCount -> at(Value.Integer),
      DistinctCoauthorCount -> at(Value.Integer),
      TotalCoauthorOccurrences -> at(Value.Integer),
      AverageCoauthorsPerPaper -> at(Value.Decimal),
      MaximumCoauthorsOnOnePaper -> at(Value.Integer),
      WeightedDegree -> at(Value.Integer),
      ComponentId -> at(Value.Id),
      ComponentSize -> at(Value.Integer),
      CommunityId -> at(Value.Id),
      CommunitySize -> at(Value.Integer),
      BridgeScore -> at(Value.Decimal),
      CrossCommunityDegree -> at(Value.Integer),
      ParticipationCoefficient -> at(Value.Decimal),
      BridgeMeasureName -> at(Value.Tag),
      Coreness -> at(Value.Integer),
      Coauthor -> at(Value.Id),
      JointPublicationCount -> Spec(Subject.Pair, Value.Integer, Style.PairHas),
      FirstCollaborationYear -> Spec(Subject.Pair, Value.Integer, Style.PairHas),
      LastCollaborationYear -> Spec(Subject.Pair, Value.Integer, Style.PairHas),
      ShortestPathHops -> Spec(Subject.Pair, Value.Integer, Style.ShortestHops),
      PathFoundHops -> Spec(Subject.Pair, Value.Integer, Style.FoundHops),
      PathSearchTruncated -> Spec(Subject.Pair, Value.True, Style.Truncated),
      NotConnectedWithin -> Spec(Subject.Pair, Value.Integer, Style.NotConnected),
      PathInconclusive -> Spec(Subject.Pair, Value.True, Style.Inconclusive),
      SupportsCoauthorEdge -> Spec(Subject.Plain, Value.Id, Style.Supports),
      HasContextDocument -> Spec(Subject.Plain, Value.Tag, Style.ContextDocument),
      Title -> Spec(Subject.Plain, Value.Quoted, Style.Title)
    )
  }

  /** The fields that have a rendering template (every fixed fact field). */
  def templatedFields: Set[String] = Specs.keySet

  private def spec(field: String): Option[Spec] =
    if (field == null || !FactFields.known(field)) None
    else if (field.startsWith(FactFields.PathNodePrefix))
      Some(Spec(Subject.Pair, Value.Id, Style.PathHas))
    else if (field.startsWith(FactFields.RankPrefix))
      Some(Spec(Subject.At, Value.Integer, Style.Has))
    else Specs.get(field)

  private final case class Parts(ids: Vector[String], window: Option[String])

  private def parts(s: String): Parts =
    s.split("@", -1).toVector match {
      case Vector(i, w) => Parts(i.split("\\|", -1).toVector, Some(w))
      case other => Parts(other.head.split("\\|", -1).toVector, None)
    }

  private def idOk(id: String): Boolean =
    SafeToken.matches(id) && StableId.validateAny(id).isRight

  private def valueOk(kind: Value, v: String): Boolean =
    v != null && (kind match {
      case Value.Integer => IntValue.matches(v)
      case Value.Decimal => DecimalValue.matches(v)
      case Value.Tag => TagValue.matches(v)
      case Value.Id => idOk(v)
      case Value.True => v == "true"
      case Value.Quoted => v.nonEmpty
    })

  /** Why `a` cannot be rendered, or None when it can (rule 4 of the verifier). */
  def problem(a: ModelAssertion, allowedWindows: Set[String]): Option[String] =
    spec(a.field) match {
      case None => Some("the field has no rendering template")
      case Some(sp) =>
        val p = parts(Option(a.subject).getOrElse(""))
        val shapeOk = sp.subject match {
          case Subject.Plain => p.ids.size == 1 && p.window.isEmpty
          case Subject.At => p.ids.size == 1 && p.window.isDefined
          case Subject.Pair => p.ids.size == 2 && p.window.isDefined
        }
        if (!shapeOk) Some("the subject does not have the form of the field")
        else if (!p.ids.forall(idOk)) Some("the subject is not a renderable stable ID")
        else if (p.window.exists(w => !WindowValue.matches(w)))
          Some("the subject window is not a window")
        else if (p.window.exists(w => !allowedWindows.contains(w)))
          Some("the subject window is not a window of the plan")
        else if (!valueOk(sp.value, a.value))
          Some("the value does not have the grammar of the field")
        else None
    }

  /**
   * A JSON string literal of `s` in which every character other than printable ASCII, a letter or a
   * non-enclosing combining mark is a `\\uXXXX` escape (so no non-ASCII digit, numeral, symbol,
   * punctuation, space or format character is ever shown as itself).
   */
  def quote(s: String): String = {
    val b = new StringBuilder("\"")
    var i = 0
    val str = Option(s).getOrElse("")
    while (i < str.length) {
      val cp = str.codePointAt(i)
      val n = Character.charCount(cp)
      if (cp == '"') b.append("\\\"")
      else if (cp == '\\') b.append("\\\\")
      else if (cp >= 0x20 && cp < 0x7f) b.append(cp.toChar)
      else if (cp >= 0x80 && shownAsIs(cp)) b.append(str.substring(i, i + n))
      else
        str.substring(i, i + n).foreach(ch => b.append("\\u").append(f"${ch.toInt}%04x"))
      i += n
    }
    b.append('"').toString
  }

  private def shownAsIs(cp: Int): Boolean = {
    val t = Character.getType(cp)
    Character.isLetter(cp) || t == Character.NON_SPACING_MARK ||
    t == Character.COMBINING_SPACING_MARK
  }

  private val QuotedToken = "\"(?:[^\"\\\\]|\\\\.)*\"".r

  /**
   * The rendering guard: outside quoted tokens only printable ASCII; inside them only printable
   * ASCII, letters and non-enclosing combining marks.
   */
  def safe(text: String): Boolean =
    text != null && {
      val outside = QuotedToken.replaceAllIn(text, "")
      outside.forall(c => c >= 0x20 && c < 0x7f) &&
      text.codePoints().toArray.forall(cp => (cp >= 0x20 && cp < 0x7f) || shownAsIs(cp))
    }

  private def pathNodeIndex(f: String): Int =
    f.stripPrefix(FactFields.PathNodePrefix).toIntOption.getOrElse(Int.MaxValue)

  /**
   * The answer text of `assertions` (each must have passed [[problem]]): one sentence per subject
   * and field group, in order of first appearance; identical assertions are rendered once.
   */
  def render(assertions: Vector[ModelAssertion]): String = {
    val as = assertions.distinct.flatMap(a => spec(a.field).map(a -> _))
    val grouped = Set(Style.Has, Style.PairHas, Style.PathHas)
    val keyed = as.zipWithIndex.map { case ((a, sp), i) =>
      ((a.subject, sp.style, if (grouped(sp.style)) -1 else i), a, sp)
    }
    keyed
      .map(_._1)
      .distinct
      .map { k =>
        val group = keyed.filter(_._1 == k).map(x => (x._2, x._3))
        sentence(group)
      }
      .mkString(" ")
  }

  private def sentence(group: Vector[(ModelAssertion, Spec)]): String = {
    val (a, sp) = group.head
    val p = parts(a.subject)
    val w = p.window.getOrElse("")
    def list(xs: Vector[ModelAssertion]) = xs.map(x => s"${x.field} ${x.value}").mkString(", ")
    sp.style match {
      case Style.Has => s"In $w, ${p.ids.head} has ${list(group.map(_._1))}."
      case Style.PairHas =>
        s"In $w, the coauthor pair ${p.ids(0)} and ${p.ids(1)} has ${list(group.map(_._1))}."
      case Style.PathHas =>
        val nodes = group.map(_._1).sortBy(x => pathNodeIndex(x.field))
        s"In $w, the coauthorship path from ${p.ids(0)} to ${p.ids(1)} has ${list(nodes)}."
      case Style.Name => s"The name ${quote(a.value)} resolves to ${p.ids.head}."
      case Style.ShortestHops =>
        s"In $w, the shortest coauthorship path from ${p.ids(0)} to ${p.ids(1)} has " +
          s"${a.field} ${a.value}."
      case Style.FoundHops =>
        s"In $w, a coauthorship path from ${p.ids(0)} to ${p.ids(1)} has ${a.field} ${a.value}, " +
          "found by a truncated search: an upper bound on the path length."
      case Style.Truncated =>
        s"In $w, the search for a coauthorship path from ${p.ids(0)} to ${p.ids(1)} was cut at " +
          s"a configured limit (${a.field} ${a.value}), so a shorter path may exist."
      case Style.NotConnected =>
        s"In $w, no coauthorship path of at most ${a.value} hops connects ${p.ids(0)} and " +
          s"${p.ids(1)} (${a.field} ${a.value})."
      case Style.Inconclusive =>
        s"In $w, the bounded search could not establish whether ${p.ids(0)} and ${p.ids(1)} " +
          s"are connected (${a.field} ${a.value})."
      case Style.Supports => s"Publication ${p.ids.head} has ${a.field} ${a.value}."
      case Style.ContextDocument =>
        s"$DescriptivePrefix a ${a.value} document about ${p.ids.head} was retrieved."
      case Style.Title =>
        s"$DescriptivePrefix a retrieved document gives ${p.ids.head} the title ${quote(a.value)}."
    }
  }
}
