package scholarchain.tests.rag

import scholarchain.core.EvidenceBundle
import scholarchain.rag.agents.{
  BundleFacts,
  ClaimResult,
  ClaimRendering,
  CollaborationWorkflow,
  FactFields,
  ModelAssertion,
  Prompts,
  SurfaceForms,
  Verdict,
  VerificationReport
}

/**
 * TE-P9-03 (CR-P9-02 F1): an INDEPENDENT re-implementation, in the tests tree, of the documented
 * claim-rendering templates (RE-P9-05, `scholarchain.rag.claim-rendering.v1`). A kept claim's text
 * must equal the oracle's rendering of the assertions the oracle itself finds verified; a template
 * change in rag is then a visible test failure, never a silent drift. Written from the work order's
 * template list and the ClaimRendering documentation, not by calling `ClaimRendering.render` or
 * `ClaimRendering.quote`.
 */
object RenderingOracle {
  import FactFields.*

  val Prefix =
    "Retrieved descriptive context only (not evidence of an edge or a metric):"

  private val HasFields: Set[String] = Set(
    IdentityStatus,
    PublicationCount,
    DistinctCoauthorCount,
    TotalCoauthorOccurrences,
    AverageCoauthorsPerPaper,
    MaximumCoauthorsOnOnePaper,
    WeightedDegree,
    ComponentId,
    ComponentSize,
    CommunityId,
    CommunitySize,
    BridgeScore,
    CrossCommunityDegree,
    ParticipationCoefficient,
    BridgeMeasureName,
    Coreness,
    Coauthor
  )
  private val PairFields: Set[String] =
    Set(JointPublicationCount, FirstCollaborationYear, LastCollaborationYear)

  private enum S { case Has, Pair, Path, Single }

  private def style(f: String): S =
    if (HasFields(f) || f.startsWith(RankPrefix)) S.Has
    else if (PairFields(f)) S.Pair
    else if (f.startsWith(PathNodePrefix)) S.Path
    else S.Single

  /**
   * The oracle's quoting: JSON string literal; non-ASCII non-letters as lower-case UTF-16 escapes.
   */
  def quote(s: String): String = {
    val b = new StringBuilder("\"")
    var i = 0
    while (i < s.length) {
      val cp = s.codePointAt(i)
      val n = Character.charCount(cp)
      val t = Character.getType(cp)
      if (cp == '"') b ++= "\\\""
      else if (cp == '\\') b ++= "\\\\"
      else if (cp >= 0x20 && cp <= 0x7e) b += cp.toChar
      else if (
        cp >= 0x80 && (Character.isLetter(cp) || t == Character.NON_SPACING_MARK ||
          t == Character.COMBINING_SPACING_MARK)
      ) b ++= s.substring(i, i + n)
      else s.substring(i, i + n).foreach(ch => b ++= "\\u%04x".format(ch.toInt))
      i += n
    }
    (b += '"').toString
  }

  private def split(subject: String): (Vector[String], String) =
    subject.split("@", -1).toVector match {
      case Vector(ids, w) => (ids.split("\\|", -1).toVector, w)
      case Vector(ids) => (ids.split("\\|", -1).toVector, "")
      case _ => (Vector(subject), "")
    }

  private def one(a: ModelAssertion): String = {
    val (ids, w) = split(a.subject)
    a.field match {
      case ResolvedFromName => s"The name ${quote(a.value)} resolves to ${ids(0)}."
      case ShortestPathHops =>
        s"In $w, the shortest coauthorship path from ${ids(0)} to ${ids(1)} has $ShortestPathHops ${a.value}."
      case PathFoundHops =>
        s"In $w, a coauthorship path from ${ids(0)} to ${ids(1)} has $PathFoundHops ${a.value}, " +
          "found by a truncated search: an upper bound on the path length."
      case PathSearchTruncated =>
        s"In $w, the search for a coauthorship path from ${ids(0)} to ${ids(1)} was cut at a " +
          s"configured limit ($PathSearchTruncated ${a.value}), so a shorter path may exist."
      case NotConnectedWithin =>
        s"In $w, no coauthorship path of at most ${a.value} hops connects ${ids(0)} and ${ids(1)} " +
          s"($NotConnectedWithin ${a.value})."
      case PathInconclusive =>
        s"In $w, the bounded search could not establish whether ${ids(0)} and ${ids(1)} are " +
          s"connected ($PathInconclusive ${a.value})."
      case SupportsCoauthorEdge => s"Publication ${ids(0)} has $SupportsCoauthorEdge ${a.value}."
      case HasContextDocument => s"$Prefix a ${a.value} document about ${ids(0)} was retrieved."
      case Title => s"$Prefix a retrieved document gives ${ids(0)} the title ${quote(a.value)}."
      case other => throw new AssertionError(s"oracle: no single template for $other")
    }
  }

  /** The documented rendering: grouped fields per subject in first-appearance order. */
  def render(assertions: Vector[ModelAssertion]): String = {
    val as = assertions.distinct
    val keys = as.zipWithIndex.map { case (a, i) =>
      style(a.field) match {
        case S.Single => (a.subject, S.Single, i)
        case s => (a.subject, s, -1)
      }
    }
    keys.distinct
      .map { k =>
        val group = as.zip(keys).filter(_._2 == k).map(_._1)
        val (ids, w) = split(k._1)
        def list(xs: Vector[ModelAssertion]) =
          xs.map(x => s"${x.field} ${x.value}").mkString(", ")
        k._2 match {
          case S.Has => s"In $w, ${ids(0)} has ${list(group)}."
          case S.Pair => s"In $w, the coauthor pair ${ids(0)} and ${ids(1)} has ${list(group)}."
          case S.Path =>
            val sorted = group.sortBy(_.field.stripPrefix(PathNodePrefix).toInt)
            s"In $w, the coauthorship path from ${ids(0)} to ${ids(1)} has ${list(sorted)}."
          case S.Single => one(group.head)
        }
      }
      .mkString(" ")
  }

  /**
   * The assertions of `r` that the oracle finds usable: a fact of a CITED bundle item with the same
   * subject, field and value (a deterministic item unless the field is descriptive), of the claim
   * kind's field family, and renderable (field grammar, via rag's `problem`, plan windows).
   */
  def usable(
      r: ClaimResult,
      facts: Vector[scholarchain.rag.agents.Fact],
      cited: Set[String],
      windows: Set[String]
  ): Vector[ModelAssertion] = {
    val descriptiveKind = Prompts.DescriptiveKinds(r.kind)
    r.assertions.filter { a =>
      val (field, value) =
        if (a.field == ResolvedFromName) (ResolvedSurfaceSha256, SurfaceForms.sha256(a.value))
        else (a.field, a.value)
      val familyOk = descriptiveKind == FactFields.descriptive(a.field)
      val supported = facts.exists(f =>
        f.subject == a.subject && f.field == field && f.value == value &&
          cited(f.evidenceId) && (FactFields.descriptive(field) || f.deterministic)
      )
      familyOk && supported && ClaimRendering.problem(a, windows).isEmpty
    }
  }

  /**
   * The text a kept VERIFIED or PARTIALLY_SUPPORTED claim must have, recomputed by the oracle; None
   * for other verdicts (checked by the callers with exact expectations).
   */
  def expected(
      r: ClaimResult,
      facts: Vector[scholarchain.rag.agents.Fact],
      windows: Set[String]
  ): Option[String] = {
    val cited = r.kept.toVector.flatMap(_.evidenceIds).toSet
    r.verdict match {
      case Verdict.Verified => Some(render(r.assertions))
      case Verdict.PartiallySupported =>
        Some(
          s"[PARTIALLY SUPPORTED: ${r.problems.mkString("; ")}] " +
            render(usable(r, facts, cited, windows))
        )
      case _ => None
    }
  }

  /** The plan windows of an answered result (the window and any compared window). */
  def windows(plan: scholarchain.core.QueryPlan): Set[String] =
    Set(BundleFacts.windowText(plan.window)) ++ plan.steps.flatMap { s =>
      val a = s.arguments.toMap
      for {
        x <- a.get("compare.toWindowStart").flatMap(_.toIntOption)
        y <- a.get("compare.toWindowEnd").flatMap(_.toIntOption)
      } yield f"$x%04d-$y%04d"
    }

  /**
   * Every line of an answer text is a kept claim's text, a workflow gap line, or one of the FIXED
   * texts (notices, explanation texts): no model-written line can be in the answer.
   */
  def foreignLines(answerText: String, report: VerificationReport): Vector[String] = {
    val allowed: Set[String] =
      report.claims.map(_.text).toSet ++ report.missingEvidence.map("Missing evidence: " + _) ++
        Set(
          CollaborationWorkflow.NonProductionNotice,
          CollaborationWorkflow.SemanticNotice,
          CollaborationWorkflow.droppedNotice(report.droppedMissingEvidence)
        ) ++ Prompts.Explanations.map(_._2)
    answerText.split("\n", -1).toVector.filterNot(allowed)
  }

  /** Whether `bundle` holds a deterministic item with this ID. */
  def deterministicItem(bundle: EvidenceBundle, id: String): Boolean =
    bundle.items.exists(i =>
      i.evidenceId == id && i.evidenceType != scholarchain.core.EvidenceType.TextChunk
    )
}
