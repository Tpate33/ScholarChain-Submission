package scholarchain.entityresolution.policy

import scala.collection.immutable.SortedMap

import scholarchain.core.{ExactId, ExactIdScheme}

/**
 * Result of the publication-stage corroboration test: whether it holds, and every clause with its
 * value (in clause order), for the decision record.
 */
final case class CorroborationResult(corroborated: Boolean, clauses: Vector[(String, Boolean)]) {

  /** Clauses that did not hold, in clause order. */
  def failed: Vector[String] = clauses.collect { case (c, false) => c }
}

/**
 * "Corroborating publication-stage evidence" (human decision 4, 2026-11-06): what a cluster
 * identifier agreement (`accept-if-corroborated`: OpenAlex W, MAG, Semantic Scholar corpus and
 * paper IDs) needs before it may decide ACCEPTED. It reads only er-features.v2 features of the
 * candidate and the endpoint identifiers that the er-features.v2 functions rebuild
 * ([[EndpointIds]]); never a label, a score, or any other identifier agreement.
 *
 * Why it exists: those sources build one record by merging copies automatically, and they are known
 * to merge a preprint with its published version (DS-P5-01: in 7 of 8 preprint-versus-published
 * units, both DOIs returned one OpenAlex work). The policy's work entity is one publication at one
 * publication stage (labeling target Option A), so a shared cluster ID alone does not show that two
 * records are the same stage. An uncorroborated agreement leaves the pair UNRESOLVED.
 *
 * The test holds iff all four clauses hold (bounds in [[WorkCorroboration]]):
 *   1. `venue`: `venue.id_agree = 1`, or `venue.name_both = 1` and `venue.name_jaccard >=
 *      venueNameJaccardMin`. A preprint server and a journal or proceedings venue never share a
 *      venue ID and share few venue-name tokens.
 *   2. `year`: `year.both = 1` and `year.abs_diff <= maxYearDiff` (0: the same year). A preprint
 *      and its published version are often in different years.
 *   3. `title`: `title.exact = 1`, or `titleSimMin <= title.sim < 1` (the `< 1` excludes two titles
 *      that both fold to empty text).
 *   4. `stage`: the identifier stages of the two sides are compatible
 *      ([[StageResult.identifierCompatible]]): both sides are pure preprints, or neither side
 *      carries a preprint marker. A preprint marker is an `arxiv` identifier or a DOI that starts
 *      with one of `preprintDoiPrefixes` (`10.48550/`, arXiv's DOI prefix). A marker on exactly one
 *      side is a preprint against a published version, which is never accepted, whatever the other
 *      clauses say. Two preprints are allowed. Since er-policy.v4 a side that carries both a
 *      preprint marker and a published DOI (a published record listing its preprint's arXiv ID) is
 *      of uncertain stage and fails the clause.
 *
 * A missing input fails its clause (no venue, no year, no title), so an incomplete record is left
 * UNRESOLVED rather than accepted. The test is deliberately strict (it errs toward "not
 * corroborated"): a false "corroborated" accepts automatically, while a false "not corroborated"
 * only leaves the pair for a manual decision.
 */
object Corroboration {

  private def v(f: SortedMap[String, Double], k: String): Double = f.getOrElse(k, 0.0)
  private def flag(f: SortedMap[String, Double], k: String): Boolean = v(f, k) == 1.0

  /** True when the identifiers carry an arXiv ID or a DOI with a preprint prefix. */
  def preprintMarker(c: WorkCorroboration, ids: Vector[ExactId]): Boolean =
    ids.exists(e =>
      e.scheme == ExactIdScheme.Arxiv ||
        (e.scheme == ExactIdScheme.Doi && c.preprintDoiPrefixes.exists(p => e.value.startsWith(p)))
    )

  /** True when the identifiers carry a DOI without a preprint prefix (a published object). */
  def publishedDoi(c: WorkCorroboration, ids: Vector[ExactId]): Boolean =
    ids.exists(e =>
      e.scheme == ExactIdScheme.Doi && !c.preprintDoiPrefixes.exists(p => e.value.startsWith(p))
    )

  /** The venue clause: the same venue ID, or both venue names with enough shared tokens. */
  def venueAgrees(c: WorkCorroboration, f: SortedMap[String, Double]): Boolean =
    flag(f, "venue.id_agree") ||
      (flag(f, "venue.name_both") && v(f, "venue.name_jaccard") >= c.venueNameJaccardMin)

  def work(
      c: WorkCorroboration,
      f: SortedMap[String, Double],
      ids: EndpointIds
  ): CorroborationResult = {
    val venue = venueAgrees(c, f)
    val year = flag(f, "year.both") && v(f, "year.abs_diff") <= c.maxYearDiff
    val sim = v(f, "title.sim")
    val title = flag(f, "title.exact") || (sim >= c.titleSimMin && sim < 1.0)
    val stage = PublicationStage.of(c, f, ids).identifierCompatible
    val clauses = Vector("venue" -> venue, "year" -> year, "title" -> title, "stage" -> stage)
    CorroborationResult(clauses.forall(_._2), clauses)
  }
}

/**
 * The publication stage one work endpoint shows through its own identifiers (er-policy.v4):
 *   - `preprint`: a preprint marker (an arXiv ID, or a DOI with a preprint prefix) and no published
 *     DOI;
 *   - `published`: a published DOI (a DOI without a preprint prefix) and no preprint marker;
 *   - `mixed`: both. Typically a published record that lists the arXiv ID of its preprint; the
 *     stage of the record is not established by its identifiers;
 *   - `unknown`: neither.
 */
enum StageEvidence(val tag: String) {
  case Preprint extends StageEvidence("preprint")
  case Published extends StageEvidence("published")
  case Mixed extends StageEvidence("mixed")
  case Unknown extends StageEvidence("unknown")

  /** The side carries an arXiv ID or a preprint DOI. */
  def hasPreprintMarker: Boolean = this == Preprint || this == Mixed
}

/**
 * Whether two work endpoints are established to be at the same publication stage.
 *
 * Established (`established = true`):
 *   - `same-stage-preprint`: both sides are `preprint`;
 *   - `same-stage-published`: both sides are `published`;
 *   - `same-stage-venue`: neither side carries a preprint marker (each is `published` or `unknown`)
 *     and the venue clause holds (`venue.id_agree = 1`, or `venue.name_both = 1` and
 *     `venue.name_jaccard >= venueNameJaccardMin`): two records of one venue are at that venue's
 *     stage.
 *
 * Not established:
 *   - `cross-stage`: exactly one side carries a preprint marker (preprint against a published
 *     version, or against a record whose stage is unknown);
 *   - `uncertain`: everything else (both sides carry a marker but at least one is `mixed`; or no
 *     marker on either side and no venue agreement).
 */
enum StageVerdict(val tag: String, val established: Boolean) {
  case SamePreprint extends StageVerdict("same-stage-preprint", true)
  case SamePublished extends StageVerdict("same-stage-published", true)
  case SameVenue extends StageVerdict("same-stage-venue", true)
  case CrossStage extends StageVerdict("cross-stage", false)
  case Uncertain extends StageVerdict("uncertain", false)
}

/** The publication-stage check of one work pair ([[PublicationStage.of]]). */
final case class StageResult(
    a: StageEvidence,
    b: StageEvidence,
    venueAgrees: Boolean,
    verdict: StageVerdict
) {
  def established: Boolean = verdict.established

  /** Both sides are pure preprints (the condition of an accepting arXiv agreement). */
  def bothPreprint: Boolean = a == StageEvidence.Preprint && b == StageEvidence.Preprint

  /**
   * The identifiers alone do not contradict the same stage: both sides are pure preprints, or
   * neither side carries a preprint marker. This is the `stage` clause of [[Corroboration.work]]
   * (whose venue clause is separate).
   */
  def identifierCompatible: Boolean =
    bothPreprint || (!a.hasPreprintMarker && !b.hasPreprintMarker)
}

/**
 * The publication-stage target (human decision of 2026-11-06, decisions-06, Option A): the policy's
 * work entity is one publication at one publication stage, so no inferred acceptance (deterministic
 * bibliographic rule, score, LLM) and no arXiv-based acceptance may merge records whose same stage
 * is not established; uncertain stage stays UNRESOLVED.
 *
 * Inputs are only the endpoint identifiers that the er-features.v2 functions rebuild
 * ([[EndpointIds]]) and the er-features.v2 venue features; never a label, a score, or an identifier
 * agreement. The preprint DOI prefixes and the venue bound are those of [[WorkCorroboration]], so
 * the cluster-identifier corroboration and this check share one notion of stage.
 */
object PublicationStage {

  def evidence(c: WorkCorroboration, ids: Vector[ExactId]): StageEvidence =
    (Corroboration.preprintMarker(c, ids), Corroboration.publishedDoi(c, ids)) match {
      case (true, false) => StageEvidence.Preprint
      case (false, true) => StageEvidence.Published
      case (true, true) => StageEvidence.Mixed
      case (false, false) => StageEvidence.Unknown
    }

  def of(c: WorkCorroboration, f: SortedMap[String, Double], ids: EndpointIds): StageResult = {
    val a = evidence(c, ids.a)
    val b = evidence(c, ids.b)
    val venue = Corroboration.venueAgrees(c, f)
    val verdict =
      if (a == StageEvidence.Preprint && b == StageEvidence.Preprint) StageVerdict.SamePreprint
      else if (a == StageEvidence.Published && b == StageEvidence.Published)
        StageVerdict.SamePublished
      else if (a.hasPreprintMarker != b.hasPreprintMarker) StageVerdict.CrossStage
      else if (!a.hasPreprintMarker && venue) StageVerdict.SameVenue
      else StageVerdict.Uncertain
    StageResult(a, b, venue, verdict)
  }
}
