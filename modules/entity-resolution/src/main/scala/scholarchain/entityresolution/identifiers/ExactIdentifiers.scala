package scholarchain.entityresolution.identifiers

import scholarchain.core.{ExactId, ExactIdScheme, IdentifierConflict, Identifiers}

/** Agreement of two identifier value sets of one scheme. */
enum IdAgreement {

  /** At least one side has no value: no evidence either way. */
  case Absent

  /** The comparable value sets intersect. */
  case Agree

  /** Both sides have values and the comparable sets are disjoint: disagreeing identifiers. */
  case Conflict
}

/**
 * Exact identifier rules (README Phase 5) over the core schemes ([[ExactIdScheme]]). This module
 * has no identifier kinds of its own: every rule is keyed by a core scheme, and the canonical form
 * of every value is the core one ([[Identifiers.normalizeExact]]), so values compared here are the
 * same values that Hadoop writes into candidates and that core validates in match decisions.
 *
 * [[normalize]] accepts a few additional raw forms seen in bibliographic text before delegating to
 * core (it never relaxes the core canonical form): surrounding whitespace; `www.doi.org` and
 * scheme-less resolver prefixes for DOIs; `arXiv:` and arxiv.org `abs/` and `pdf/` URLs (and
 * `.pdf`) for arXiv IDs; scheme-less and `www.` ORCID URIs and a lower-case check character `x`;
 * `api.openalex.org` and `/works/` / `/authors/` OpenAlex URIs and a lower-case entity letter.
 * Semantic Scholar and repository URLs are normalized by core alone: in particular repository paths
 * are never shortened (a `gitlab.com/group/sub/repo` path keeps every segment; only `github.com` is
 * case-folded and must be exactly `<owner>/<repository>`).
 *
 * Comparison ([[comparable]]): arXiv values are compared without their version suffix (`vN`),
 * because every version of a preprint is one work; every other scheme compares canonical values.
 */
object ExactIdentifiers {

  private def stripPrefixCI(s: String, prefixes: Seq[String]): String =
    prefixes
      .find(p => s.regionMatches(true, 0, p, 0, p.length))
      .fold(s)(p => s.substring(p.length))

  private val schemes = Vector("https://", "http://", "")

  private def withSchemes(hosts: String*): Vector[String] =
    for { sc <- schemes; h <- hosts.toVector } yield sc + h

  private val doiPrefixes = withSchemes("www.doi.org/", "doi.org/", "dx.doi.org/") :+ "doi:"
  private val arxivPrefixes =
    withSchemes(
      "www.arxiv.org/abs/",
      "arxiv.org/abs/",
      "www.arxiv.org/pdf/",
      "arxiv.org/pdf/",
      "export.arxiv.org/abs/"
    ) :+ "arxiv:"
  private val orcidPrefixes = withSchemes("www.orcid.org/", "orcid.org/")
  private val openAlexPrefixes =
    withSchemes(
      "api.openalex.org/works/",
      "api.openalex.org/authors/",
      "api.openalex.org/",
      "openalex.org/works/",
      "openalex.org/authors/",
      "openalex.org/"
    )
  private val ArxivVersion = "^(.*?)v[0-9]+$".r

  private def preClean(scheme: ExactIdScheme, s: String): String = scheme match {
    case ExactIdScheme.Doi => stripPrefixCI(s, doiPrefixes)
    case ExactIdScheme.Arxiv => stripPrefixCI(s, arxivPrefixes).stripSuffix(".pdf")
    case ExactIdScheme.Orcid =>
      val bare = stripPrefixCI(s, orcidPrefixes)
      if (bare.endsWith("x")) bare.init + "X" else bare
    case ExactIdScheme.OpenAlexWork => openAlexLetter('W', stripPrefixCI(s, openAlexPrefixes))
    case ExactIdScheme.OpenAlexAuthor => openAlexLetter('A', stripPrefixCI(s, openAlexPrefixes))
    case _ => s
  }

  private def openAlexLetter(letter: Char, bare: String): String =
    if (bare.nonEmpty && bare.charAt(0).toUpper == letter) letter.toString + bare.drop(1) else bare

  /** The core canonical form of `raw` under `scheme`, or why it is not one. */
  def normalize(scheme: ExactIdScheme, raw: String): Either[String, String] =
    if (scheme == null) Left("no scheme")
    else if (raw == null) Left("null")
    else Identifiers.normalizeExact(scheme, preClean(scheme, raw.trim))

  /** The value as compared: an arXiv ID without its version suffix; otherwise unchanged. */
  def comparable(scheme: ExactIdScheme, canonical: String): String = scheme match {
    case ExactIdScheme.Arxiv =>
      canonical match {
        case ArxivVersion(base) if Identifiers.isValidArxiv(base) => base
        case other => other
      }
    case _ => canonical
  }

  /** Canonical values of `raws` under `scheme`; values that are not of the scheme are dropped. */
  def canonicalSet(scheme: ExactIdScheme, raws: Iterable[String]): Set[String] =
    raws.iterator.flatMap(r => normalize(scheme, r).toOption).toSet

  /** Agreement of two sets of canonical values of one scheme ([[comparable]] forms). */
  def compareCanonical(scheme: ExactIdScheme, a: Set[String], b: Set[String]): IdAgreement = {
    val x = a.map(comparable(scheme, _))
    val y = b.map(comparable(scheme, _))
    if (x.isEmpty || y.isEmpty) IdAgreement.Absent
    else if (x.exists(y.contains)) IdAgreement.Agree
    else IdAgreement.Conflict
  }

  /** Agreement of two raw value collections of one scheme. */
  def compare(scheme: ExactIdScheme, a: Iterable[String], b: Iterable[String]): IdAgreement =
    compareCanonical(scheme, canonicalSet(scheme, a), canonicalSet(scheme, b))

  /**
   * The agreement of `scheme` between two endpoint identifier sets ([[compareCanonical]] forms):
   * every original canonical value of either side whose [[comparable]] form both sides share, in
   * canonical order; empty exactly when [[compareCanonical]] is not `Agree`. It is the counterpart
   * of [[disagreement]] and uses the same normalisation, so for every scheme exactly one of the two
   * holds when both sides carry values. For arXiv the comparable form is the base submission ID, so
   * `2101.00001v1` against `2101.00001v2` (or against `2101.00001`) agree, and the returned values
   * keep their versions (both `2101.00001v1` and `2101.00001v2`), as the endpoint records state
   * them (er-policy.v8, RV-P5-04 m7). Old-style (`hep-th/9901001`) and new-style (`2101.00001`)
   * identifiers are different submissions and never share a base ID.
   */
  def agreement(
      scheme: ExactIdScheme,
      idsA: Vector[ExactId],
      idsB: Vector[ExactId]
  ): Vector[ExactId] = {
    def values(ids: Vector[ExactId]) = ids.iterator.filter(_.scheme == scheme).map(_.value).toSet
    val a = values(idsA)
    val b = values(idsB)
    val shared = a.map(comparable(scheme, _)).intersect(b.map(comparable(scheme, _)))
    if (shared.isEmpty) Vector.empty
    else
      ExactId.canonical(
        (a ++ b).iterator
          .filter(v => shared.contains(comparable(scheme, v)))
          .map(v => ExactId(scheme, v))
          .toVector
      )
  }

  /**
   * True when `scheme` agrees between the two sides only through [[comparable]] (no original value
   * is shared byte for byte): for arXiv, two different revisions vN of one submission.
   */
  def agreesOnlyByComparable(
      scheme: ExactIdScheme,
      idsA: Vector[ExactId],
      idsB: Vector[ExactId]
  ): Boolean = {
    def values(ids: Vector[ExactId]) = ids.iterator.filter(_.scheme == scheme).map(_.value).toSet
    agreement(scheme, idsA, idsB).nonEmpty && !values(idsA).exists(values(idsB).contains)
  }

  /**
   * The disagreement of `scheme` between two endpoint identifier sets, as a core
   * [[IdentifierConflict]]: Some exactly when [[compareCanonical]] is `Conflict`. For every scheme
   * but arXiv this is [[IdentifierConflict.disagreement]] itself; for arXiv, versions of one ID are
   * not a disagreement.
   */
  def disagreement(
      scheme: ExactIdScheme,
      idsA: Vector[ExactId],
      idsB: Vector[ExactId]
  ): Option[IdentifierConflict] = {
    def values(ids: Vector[ExactId]) = ids.iterator.filter(_.scheme == scheme).map(_.value).toSet
    compareCanonical(scheme, values(idsA), values(idsB)) match {
      case IdAgreement.Conflict => IdentifierConflict.disagreement(scheme, idsA, idsB)
      case _ => None
    }
  }
}
