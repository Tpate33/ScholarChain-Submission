package scholarchain.entityresolution.evaluation

import java.time.Instant

import scala.util.control.NonFatal

import upickle.default.{read, ReadWriter}
import upickle.implicits.allowUnknownKeys

import scholarchain.entityresolution.review.{
  BlindView,
  GoldMember,
  GoldMembership,
  LabelGuide,
  ReviewQueue,
  ReviewQueueRow
}

/**
 * One human gold label (proposed schema [[GoldLabels.SchemaVersion]], one JSON object per line).
 * Written only by a human reviewer; no code in this repository creates, edits, or infers one.
 *
 * The label is keyed by `memberId` (a hash of the entity kind and the two endpoint IDs only), so it
 * stays attached to its pair when the matching version, blocking version, feature version, or
 * pipeline run changes.
 *
 * Schema v3 (human decision 1, 2026-11-06, Option A): every label names the labeling target and the
 * label guide it answers ([[LabelGuide]]). `labelingTarget` must be the target of the member's
 * entity kind (`publication-stage` for work, `person` for researcher) and `labelGuideVersion` the
 * current guide version. There is one label per pair; no second (lifecycle) label exists.
 *
 * @param memberId
 *   the gold member being labeled (membership manifest)
 * @param labelingTarget
 *   [[LabelGuide.target]] of the entity kind
 * @param labelGuideVersion
 *   [[LabelGuide.Version]]
 * @param sourceIdA
 *   an endpoint ID of the member, or the label reference the blind view shows for it
 *   (`masked-endpoint:` when the endpoint ID names a DBLP person); both are checked against the
 *   manifest member
 * @param label
 *   `match`, `non-match`, or `unsure`
 * @param labeler
 *   reviewer token (initials or handle), `[A-Za-z0-9._-]{1,64}`
 * @param labeledAt
 *   ISO-8601 UTC instant (`...Z`)
 */
@allowUnknownKeys(false)
final case class GoldLabel(
    schemaVersion: String,
    memberId: String,
    entityKind: String,
    labelingTarget: Option[String],
    labelGuideVersion: Option[String],
    sourceIdA: String,
    sourceIdB: String,
    label: Option[String],
    labeler: Option[String],
    labeledAt: Option[String],
    notes: Option[String]
) derives ReadWriter

/** A refused label line (1-based line number, 0 for file-level problems). */
final case class LabelProblem(line: Int, memberId: String, problem: String)

/**
 * Ingest and validation of a human label file against the membership manifest it labels. Refusal is
 * all-or-nothing: if any line is unlabeled, malformed, duplicate, or untraceable, no label is
 * returned and every problem is listed.
 */
object GoldLabels {
  val SchemaVersion: String = "er-gold-label.v3"

  /**
   * Retired: v1 labels were keyed by the policy-dependent row ID; v2 labels named no labeling
   * target. None of either was ever written.
   */
  val RetiredSchemaVersions: Set[String] = Set("er-gold-label.v1", "er-gold-label.v2")

  /** The only accepted label strings, compared exactly (never trimmed or case-folded). */
  val Labels: Set[String] = Set("match", "non-match", "unsure")
  private val Labeler = "^[A-Za-z0-9._-]{1,64}$".r

  /** True when the label is exactly one of [[Labels]]. */
  def canonical(label: Option[String]): Boolean = label.exists(Labels.contains)

  /** A refused label value shown with control and space characters escaped (diagnostics only). */
  private def printable(s: String): String =
    s.flatMap {
      case c if c.isControl || c.isWhitespace || Character.getType(c) == Character.FORMAT =>
        "U+" + f"${c.toInt}%04X"
      case c => c.toString
    }

  private def validInstant(s: String): Boolean =
    try { Instant.parse(s); s.endsWith("Z") }
    catch { case NonFatal(_) => false }

  /**
   * Parses review-queue JSON Lines; a generated row must have all label fields null, its member ID
   * must match its kind and endpoints, and its row ID must match its member and policy version.
   */
  def parseQueue(lines: Vector[String]): Either[Vector[String], Vector[ReviewQueueRow]] = {
    val parsed = lines.zipWithIndex.filter(_._1.trim.nonEmpty).map { case (l, i) =>
      (try Right(read[ReviewQueueRow](l))
      catch { case NonFatal(e) => Left(s"queue line ${i + 1}: not a queue row (${e.getMessage})") })
        .flatMap { r =>
          if (r.schemaVersion != ReviewQueue.RowSchemaVersion)
            Left(s"queue line ${i + 1}: schemaVersion must be ${ReviewQueue.RowSchemaVersion}")
          else if (
            !GoldMembership.memberId(r.entityKind, r.sourceIdA, r.sourceIdB).contains(r.memberId)
          )
            Left(s"queue line ${i + 1}: memberId does not match the entity kind and endpoints")
          else if (r.rowId != ReviewQueue.rowId(r.memberId, r.policyVersion))
            Left(s"queue line ${i + 1}: rowId does not match memberId and policyVersion")
          else if (Vector(r.label, r.labeler, r.labeledAt, r.labelNotes).exists(_.isDefined))
            Left(
              s"queue line ${i + 1}: a generated queue row carries label fields; labels belong " +
                "in the separate human label file"
            )
          else Right(r)
        }
    }
    val errors = parsed.collect { case Left(e) => e }
    val rows = parsed.collect { case Right(r) => r }
    val dup =
      rows.groupBy(_.rowId).collect { case (id, rs) if rs.size > 1 => s"duplicate queue row $id" }
    if (errors.nonEmpty || dup.nonEmpty) Left(errors ++ dup.toVector.sorted) else Right(rows)
  }

  /** Validates every label line against the membership manifest. */
  def ingest(
      lines: Vector[String],
      manifest: Vector[GoldMember]
  ): Either[Vector[LabelProblem], Vector[GoldLabel]] = {
    val byMember = manifest.map(m => m.memberId -> m).toMap
    val seen = scala.collection.mutable.HashMap.empty[String, Int]
    val problems = Vector.newBuilder[LabelProblem]
    val labels = Vector.newBuilder[GoldLabel]
    lines.zipWithIndex.foreach { case (raw, i) =>
      val n = i + 1
      if (raw.trim.nonEmpty) {
        val decoded =
          try Right(read[GoldLabel](raw))
          catch { case NonFatal(e) => Left(s"malformed label line (${e.getMessage})") }
        decoded match {
          case Left(why) => problems += LabelProblem(n, "", why)
          case Right(g) =>
            val local = Vector.newBuilder[String]
            if (RetiredSchemaVersions.contains(g.schemaVersion))
              local += s"schemaVersion ${g.schemaVersion} is retired; use $SchemaVersion (memberId)"
            else if (g.schemaVersion != SchemaVersion)
              local += s"schemaVersion must be $SchemaVersion"
            // RV-P5-04 M1 / TE-P5-01 D1: the label string must be exactly canonical. It is never
            // trimmed or case-folded, so the value validated here is the value evaluated.
            g.label match {
              case None => local += "unlabeled: label is missing or blank"
              case Some(l) if l.trim.isEmpty => local += "unlabeled: label is missing or blank"
              case Some(l) if !Labels.contains(l) =>
                local += s"label '${printable(l)}' is not one of " +
                  s"${Labels.toVector.sorted.mkString(", ")} (exactly: lower case, no surrounding " +
                  "or inner whitespace)"
              case _ => ()
            }
            LabelGuide.target(g.entityKind) match {
              case Some(t) if g.labelingTarget.contains(t) => ()
              case Some(t) =>
                local += s"labelingTarget must be '$t' for a ${g.entityKind} pair " +
                  s"(${LabelGuide.Version}); found ${g.labelingTarget.getOrElse("none")}"
              case None => () // the entity kind is reported as untraceable below
            }
            if (!g.labelGuideVersion.contains(LabelGuide.Version))
              local += s"labelGuideVersion must be ${LabelGuide.Version}"
            if (!g.labeler.exists(Labeler.matches)) local += "labeler is missing or not a token"
            if (!g.labeledAt.exists(validInstant))
              local += "labeledAt is not an ISO-8601 UTC instant"
            val stated = Set(g.sourceIdA, g.sourceIdB)
            val member = byMember.get(g.memberId)
            // A label may repeat the blind view's label references (BlindView.labelRef) instead of
            // the endpoint IDs; they are recomputed from the manifest member, never trusted.
            val shownRefs = member.map(m =>
              Set(
                BlindView.labelRef(m.memberId, m.endpointA.endpointId),
                BlindView.labelRef(m.memberId, m.endpointB.endpointId)
              )
            )
            val (ida, idb) = member match {
              case Some(m) if shownRefs.contains(stated) =>
                (m.endpointA.endpointId, m.endpointB.endpointId)
              case _ => (g.sourceIdA, g.sourceIdB)
            }
            if (!GoldMembership.memberId(g.entityKind, ida, idb).contains(g.memberId))
              local += "untraceable: memberId does not match the entity kind and endpoints"
            member match {
              case None => local += "untraceable: memberId is not in the membership manifest"
              case Some(m) =>
                val ends = Set(m.endpointA.endpointId, m.endpointB.endpointId)
                if (m.entityKind != g.entityKind || (ends != stated && !shownRefs.contains(stated)))
                  local += "untraceable: entity kind or endpoints differ from the manifest member"
            }
            seen.get(g.memberId) match {
              case Some(first) => local += s"duplicate memberId (first on line $first)"
              case None => seen.update(g.memberId, n)
            }
            val ps = local.result()
            if (ps.isEmpty) labels += g
            else ps.foreach(p => problems += LabelProblem(n, g.memberId, p))
        }
      }
    }
    val all = problems.result()
    if (all.nonEmpty) Left(all) else Right(labels.result())
  }
}
