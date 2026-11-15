package scholarchain.entityresolution.review

import scala.util.matching.Regex

/**
 * Display masking of DBLP identity assignments for the blind labeling view (human instruction
 * 2026-11-05, decisions-04): DBLP homonym numbers and DBLP person identifiers are removed from
 * every displayed string, so the labeler never sees DBLP's own assignment of a name to a person.
 * Masking applies to the display only. Source records, the membership manifest, and provenance are
 * never changed.
 *
 * Rules (version [[Version]]), applied in this order:
 *
 *   1. Homonym number, person-name fields only (names as written, structured names, name variants,
 *      coauthor names): a run of whitespace followed by exactly four ASCII digits, at the end of
 *      the name or right before `,` `;` or `/` ([[HomonymSuffix]]). `Tyler Moore 0001` becomes
 *      `Tyler Moore`; `Moore 0001, Tyler` becomes `Moore, Tyler`. Nothing else in the name changes.
 *      Titles and other text are never stripped of digits.
 *   2. Pid scheme names, every displayed string: `dblp-pid:<pid>`, `dblp_pid = <pid>`, `dblp pid
 *      <pid>` (the name with its value), and a bare `dblp-pid` / `dblp_pid` / `dblppid` word
 *      ([[SpacedPid]]). `source-author:dblp-pid:<pid>` becomes `source-author:` + [[Masked]].
 *   3. Person-link tokens, every displayed string: a token (a maximal run without whitespace,
 *      quotes, or angle brackets) is replaced by [[Masked]] when
 *      - it contains `dblp` (any case) and a person marker ([[PersonMarker]]): `/pid/`, `/pers/`,
 *        `homepages/`, a `pid=` or `dblp...=` query parameter, `pid%3a`, or `dblp-pid`. This covers
 *        `https://dblp.org/pid/..`, `https://dblp.uni-trier.de/pid/..`, `dblp.org/pers/..`,
 *        `https://dblp.org/rec/homepages/..`, `dblp-key:homepages/..`, search URLs carrying a pid,
 *        and any URL with a `dblp...=` parameter; or
 *      - it is a relative DBLP person path ([[PersonPath]]): `pid/..`, `/pid/..`, `pers/..`,
 *        `homepages/..`, `rec/homepages/..`, `work:dblp:homepages/..`. Publication links
 *        (`https://dblp.org/rec/journals/..`, `db/journals/..`) are kept.
 *   4. Pid values, every displayed string of the pair: each pid value named anywhere in the pair
 *      ([[pidValues]]), and the source record ID of a DBLP profile, is replaced wherever it still
 *      occurs as a whole value (not preceded or followed by `[A-Za-z0-9._/-]`), for example
 *      `dblp:<pid>` in a profile's identifier list.
 *
 * Every replacement is the one constant [[Masked]], whatever the value, so two masked values never
 * reveal whether they were equal. Masking is a pure function of the strings: the same input always
 * gives the same output, and it is idempotent.
 *
 * Version v2 (ER-P5-08, TE-P5-01 D4 and D5):
 *   - rule 1 accepts any run of Unicode white space, Unicode separators (`\p{Z}`), and format
 *     characters (`\p{Cf}`, for example U+200B ZERO WIDTH SPACE) before the four digits, not only
 *     `\s`. This is display masking only: the parser limitation NIT-2 of `Names` and `BlockingKeys`
 *     (a format character joins the number to the family key) is unchanged;
 *   - rules 2 and 3 also inspect every percent-decoded layer of a token ([[DisplayDecoding]],
 *     nested encodings included). When a decoded layer names a DBLP person, the whole original
 *     token is replaced, and pid values found in decoded layers are masked by rule 4.
 */
object DblpMasking {
  val Version: String = "er-dblp-display-mask.v2"

  /** The one constant replacement of every masked identifier, link, and pid value. */
  val Masked: String = "[masked: DBLP person identifier]"

  /** Row note when a homonym number was removed from at least one name of the pair. */
  val HomonymNote: String =
    "A DBLP homonym number was removed from at least one person name in this pair; " +
      "the number is never shown."

  /** Row note when a DBLP person identifier or person link was removed from the pair. */
  val PersonIdNote: String =
    "A DBLP person identifier or person-page link was removed from this pair."

  /**
   * Rule 1: a run of white space, separators, or format characters, then four ASCII digits, at the
   * end of a name or before `,` `;` `/` (v2: `\p{Z}` and `\p{Cf}` included, TE-P5-01 D4).
   */
  val HomonymSuffix: Regex =
    "(?U)[\\s\\p{Z}\\p{Cf}]+[0-9]{4}(?=[\\s\\p{Z}\\p{Cf}]*(?:[,;/]|$))".r

  private val Token: Regex = "[^\\s\"'<>]+".r

  /** Rule 2, first branch: person markers inside a token that also contains `dblp`. */
  val PersonMarker: Regex =
    "(?i)/pid/|/pers/|homepages/|[?&;#]pid=|[?&;#]dblp[a-z_-]*=|pid%3a|dblp[_-]?pid".r

  /** Rule 2, second branch: a relative DBLP person path (whole token). */
  val PersonPath: Regex =
    "(?i)(?:dblp-key:|work:dblp:|/?rec/|/)?(?:pid|pers|homepages)/\\S+".r

  /** Rule 3: a spaced or bare `dblp pid` scheme name, with its value when one follows. */
  val SpacedPid: Regex =
    "(?i)dblp[\\s_-]?pid(?:\\s*[:=/]?\\s*[A-Za-z0-9]+/[A-Za-z0-9._-]+)?".r

  private val PidValue = "[A-Za-z0-9]+/[A-Za-z0-9._-]+"
  private val PidCaptures: Vector[Regex] = Vector(
    s"(?i)dblp[\\s_-]?pid\\s*[:=/]?\\s*($PidValue)".r,
    s"(?i)/pid/($PidValue)".r,
    s"(?i)homepages/($PidValue)".r,
    s"(?i)[?&;#]pid=($PidValue)".r
  )
  private val PageSuffix = "(?i)\\.(?:html?|xml|rdf|nt|ttl|bib|json)$".r

  private def isPersonLayer(t: String): Boolean =
    (t.toLowerCase(java.util.Locale.ROOT).contains("dblp") &&
      PersonMarker.findFirstIn(t).isDefined) || PersonPath.matches(t)

  /**
   * A token names a DBLP person when it, or any of its percent-decoded layers, does (v2, D5). A
   * decoded layer is also checked for a spaced pid scheme name (`dblp%20pid%3A..`).
   */
  private def isPersonToken(t: String): Boolean =
    DisplayDecoding
      .layers(t)
      .exists(l => isPersonLayer(l) || (l != t && SpacedPid.findFirstIn(l).isDefined))

  /**
   * The pid values named in the strings (rule 4 inputs): values after a `dblp-pid` scheme name, in
   * a `/pid/` or `homepages/` path, or in a `pid=` parameter, without a page suffix. Every
   * percent-decoded layer of every string is read (v2).
   */
  def pidValues(strings: Iterable[String]): Set[String] =
    strings.iterator
      .flatMap(DisplayDecoding.layers)
      .flatMap { s =>
        PidCaptures.iterator.flatMap(
          _.findAllMatchIn(s).map(m => PageSuffix.replaceFirstIn(m.group(1), ""))
        )
      }
      .toSet

  private def bounded(v: String): Regex =
    ("(?<![A-Za-z0-9._/-])" + Regex.quote(v) + "(?![A-Za-z0-9._/-])").r

  /** Rules 2 to 4 on one displayed string. */
  def text(s: String, pids: Set[String] = Set.empty): String = {
    val spaced = SpacedPid.replaceAllIn(s, Regex.quoteReplacement(Masked))
    val tokens = Token.replaceAllIn(
      spaced,
      m => Regex.quoteReplacement(if (isPersonToken(m.matched)) Masked else m.matched)
    )
    pids.toVector.sorted.foldLeft(tokens)((acc, v) =>
      bounded(v).replaceAllIn(acc, Regex.quoteReplacement(Masked))
    )
  }

  /** Rule 1 alone: the name without its homonym number. */
  def stripHomonym(name: String): String = HomonymSuffix.replaceAllIn(name, "")

  /** Rules 1 to 4 on one displayed person name. */
  def name(s: String, pids: Set[String] = Set.empty): String = text(stripHomonym(s), pids)

  /** True when the string would be changed by rules 2 and 3 (it names a DBLP person). */
  def revealsPerson(s: String): Boolean = text(s) != s || pidValues(Vector(s)).nonEmpty
}
