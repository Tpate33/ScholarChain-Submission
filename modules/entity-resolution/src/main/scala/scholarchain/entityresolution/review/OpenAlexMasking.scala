package scholarchain.entityresolution.review

import scala.util.matching.Regex

/**
 * Display masking of OpenAlex cluster identifiers for the blind labeling view (human instruction
 * 2026-11-06, decisions-06; RV-P5-04 m9). OpenAlex author IDs (`A...`) and work IDs (`W...`) are
 * algorithmic identity assignments, and the exact-id path decides on them, so a labeler who saw
 * them could anchor on the very assignment being evaluated. They are removed from every displayed
 * string. Masking applies to the display only: source records, the membership manifest, and
 * provenance are never changed.
 *
 * Rules (version [[Version]]):
 *   1. Token rule, every displayed string: a token (a maximal run without white space, quotes, or
 *      angle brackets) is replaced by [[Masked]] when the token, or any of its percent-decoded
 *      layers ([[DisplayDecoding]]), contains `openalex` (any case) and an A or W ID as a whole
 *      value ([[ClusterId]]). This covers `openalex:A..`, `openalex-author:A..`,
 *      `openalex-work:W..`, `work:openalex:W..`, `source-author:openalex:A..`,
 *      `https://openalex.org/W..`, `https://openalex.org/authors/A..`,
 *      `https://api.openalex.org/works/W..`, filter URLs such as
 *      `https://api.openalex.org/works?filter=openalex:W..`, and any of these percent-encoded
 *      inside another link.
 *   2. Value rule: every A or W value found by rule 1 anywhere in the pair, and the source record
 *      ID of an OpenAlex endpoint when it is an A or W ID, is replaced wherever it still occurs as
 *      a whole value (not preceded or followed by an ASCII letter or digit), any case.
 *
 * Kept: DOIs, ORCID iDs, institution IDs (`I...`, including OpenAlex institution IDs), venues, and
 * every other identifier. MAG IDs are not covered by this instruction and stay visible.
 *
 * Every replacement is the one constant [[Masked]], whatever the value, so two masked values never
 * reveal whether they were equal. Masking is deterministic and idempotent.
 */
object OpenAlexMasking {
  val Version: String = "er-openalex-display-mask.v1"

  /** The one constant replacement of every masked OpenAlex cluster identifier and link. */
  val Masked: String = "[masked: OpenAlex cluster identifier]"

  /** Row note when an OpenAlex A or W ID was removed from the pair. */
  val Note: String =
    "An OpenAlex author or work cluster identifier (A or W ID) was removed from this pair."

  /** An OpenAlex author (A) or work (W) ID as a whole value. */
  val ClusterId: Regex = "(?i)(?<![A-Za-z0-9])[AW][0-9]{4,15}(?![A-Za-z0-9])".r

  private val Token: Regex = "[^\\s\"'<>]+".r

  private def namesOpenAlex(layer: String): Boolean =
    layer.toLowerCase(java.util.Locale.ROOT).contains("openalex") &&
      ClusterId.findFirstIn(layer).isDefined

  /** Rule 1: the token or one of its decoded layers names an OpenAlex A or W ID. */
  def isClusterToken(t: String): Boolean = DisplayDecoding.layers(t).exists(namesOpenAlex)

  /** The A and W values named by rule-1 tokens of the strings (rule 2 inputs), upper case. */
  def clusterValues(strings: Iterable[String]): Set[String] =
    strings.iterator
      .flatMap(s => Token.findAllIn(s))
      .flatMap(DisplayDecoding.layers)
      .filter(namesOpenAlex)
      .flatMap(l => ClusterId.findAllIn(l))
      .map(_.toUpperCase(java.util.Locale.ROOT))
      .toSet

  private def bounded(v: String): Regex =
    ("(?i)(?<![A-Za-z0-9])" + Regex.quote(v) + "(?![A-Za-z0-9])").r

  /** Rules 1 and 2 on one displayed string. */
  def text(s: String, values: Set[String] = Set.empty): String = {
    val tokens = Token.replaceAllIn(
      s,
      m => Regex.quoteReplacement(if (isClusterToken(m.matched)) Masked else m.matched)
    )
    values.toVector.sorted.foldLeft(tokens)((acc, v) =>
      bounded(v).replaceAllIn(acc, Regex.quoteReplacement(Masked))
    )
  }

  /** True when the string names an OpenAlex A or W ID (rule 1 would change it). */
  def reveals(s: String): Boolean = text(s) != s
}
