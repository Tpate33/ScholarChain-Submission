package scholarchain.entityresolution

import scholarchain.core.{AuthorMention, Canonical, SourceAuthorProfile, WorkRecord}

/**
 * Versioned, deterministic blocking-key formulas (README Phase 4 job 4). Pure functions: no I/O, no
 * clock, no randomness, no default-locale case mapping, no dependence on hash-set iteration order.
 * A blocking key only groups records that will be compared; it is never evidence of identity.
 *
 * Keys are hierarchical and nested: the level-`l + 1` key is the level-`l` key followed by `|` and
 * one more component, so every level-`l + 1` block is a subset of exactly one level-`l` block.
 * Hadoop refines an oversize level-`l` block by regrouping its members on their level-`l + 1` keys;
 * the nesting guarantees that members spilled from different parent blocks never merge. The maximum
 * block size and the spill, drop, or fail policy belong to the Hadoop run configuration, not to
 * these formulas.
 *
 * Every component is built from [[TextFolding]] output (lower-case letters and digits of any
 * script), a decimal year, a stable institution ID, or one of the markers [[Missing]] (`-`) and
 * [[ProfileMarker]] (`*`). Keys therefore never contain a tab, a control character, or a blank, and
 * the separator `|` never occurs inside a component.
 *
 * Researcher mention keys (work and mention as context):
 *   - level 0: `<family>|<first initial>`. Family = [[PersonName.familyBlockKey]] (folded,
 *     particles joined, vowel digraphs collapsed, DBLP homonym number removed); first initial of
 *     the first given part, or `-` when the name has no given part.
 *   - level 1: level 0 + `|<first given name>` ([[PersonName.firstGivenKey]]; `-` when none).
 *     Separates `Wang, Yi` from `Wang, Yan`.
 *   - level 2: level 1 + `|<coauthor anchor>`: the smallest (UTF-8 byte order) family block key
 *     among the other authors of the same work, `-` for a single-author work. Coauthor context: the
 *     same paper seen in two sources keeps the same anchor, so cross-source mentions of one paper
 *     stay together while unrelated homonyms split.
 *   - level 3: level 2 + `|<year>` (`-` when the work has no year).
 *
 * Source author profile keys (no work context): levels 0 and 1 as for mentions, from the structured
 * ORCID name when present, else the display name; level 2 = level 1 + `|*` (profiles have no
 * coauthor context, so at level 2 and deeper they are compared only with other profiles); level 3 =
 * level 2 + `|<first institution ID>` (`-` when none).
 *
 * Work keys:
 *   - level 0: the first two title content tokens joined by `_` ([[TitleText.contentTokens]]; all
 *     title words when every word is a stopword).
 *   - level 1: level 0 + `|<content tokens 3 and 4 joined by _>` (`-` when the title has none).
 *   - level 2: level 1 + `|<year>` (`-` when missing).
 *   - level 3: level 2 + `|<family block key of the first author>` (`-` when none).
 *
 * Affiliation, venue, and source are deliberately not key components: affiliations are absent from
 * DBLP mentions, venue names differ between sources (`ACM Trans. Comput. Syst.` and `ACM
 * Transactions on Computer Systems`), and keying on source would prevent cross-source candidates.
 * They are match features instead ([[MatchFeatures]]).
 *
 * A record yields no keys (an empty vector) when no family name or no title word can be read.
 */
object BlockingKeys {

  /** Version of the key formulas; recorded as `blockingVersion` on blocks and candidates. */
  val Version: String = "er-blocking.v1"

  /** Maximum number of key levels any record yields. */
  val Levels: Int = 4

  /** Component separator. */
  val Separator: String = "|"

  /** Marker for an absent component. */
  val Missing: String = "-"

  /** Level-2 marker of source author profiles (no coauthor context). */
  val ProfileMarker: String = "*"

  private def join(prefix: String, component: String): String = prefix + Separator + component

  private def minUtf8(xs: Iterable[String]): Option[String] =
    xs.foldLeft(Option.empty[String]) {
      case (None, x) => Some(x)
      case (Some(m), x) => if (Canonical.compareUtf8(x, m) < 0) Some(x) else Some(m)
    }

  /** Levels 0 and 1 of a researcher name, or None when no family name can be read. */
  private def nameLevels(name: PersonName): Option[(String, String)] =
    if (name.isEmpty) None
    else {
      val l0 = join(name.familyBlockKey, name.firstInitial.getOrElse(Missing))
      Some((l0, join(l0, name.firstGivenKey.getOrElse(Missing))))
    }

  /** The coauthor anchor of `mention` on `work` (level-2 component). */
  def coauthorAnchor(work: WorkRecord, mention: AuthorMention): String =
    minUtf8(
      work.authors.iterator
        .filter(_.mentionId != mention.mentionId)
        .map(m => PersonName.parse(m.rawName).familyBlockKey)
        .filter(_.nonEmpty)
        .toVector
    ).getOrElse(Missing)

  /**
   * Keys of one author mention with its work as context, level 0 first (at most [[Levels]]). Reads
   * `mention.rawName`, the other authors' raw names, and `work.year`; never the source-normalized
   * `normalizedName`.
   */
  def researcherKeys(work: WorkRecord, mention: AuthorMention): Vector[String] =
    nameLevels(PersonName.parse(mention.rawName)) match {
      case None => Vector.empty
      case Some((l0, l1)) =>
        val l2 = join(l1, coauthorAnchor(work, mention))
        val l3 = join(l2, work.year.fold(Missing)(_.toString))
        Vector(l0, l1, l2, l3)
    }

  /** The name used to block a profile: structured family/given names first, else display name. */
  def profileName(profile: SourceAuthorProfile): PersonName = {
    val structured = PersonName.structured(profile.givenNames, profile.familyName)
    if (!structured.isEmpty) structured
    else PersonName.parse(profile.displayName.getOrElse(""))
  }

  /** Keys of one source author profile, level 0 first (at most [[Levels]]). */
  def profileKeys(profile: SourceAuthorProfile): Vector[String] =
    nameLevels(profileName(profile)) match {
      case None => Vector.empty
      case Some((l0, l1)) =>
        val l2 = join(l1, ProfileMarker)
        val l3 = join(l2, profile.institutionIds.headOption.getOrElse(Missing))
        Vector(l0, l1, l2, l3)
    }

  /**
   * Keys of one work, level 0 first (at most [[Levels]]). Reads `title`, never `normalizedTitle`.
   */
  def workKeys(work: WorkRecord): Vector[String] = {
    val content = TitleText.contentTokens(work.title)
    val tokens = if (content.nonEmpty) content else TitleText.words(work.title)
    if (tokens.isEmpty) Vector.empty
    else {
      val l0 = tokens.take(2).mkString("_")
      val more = tokens.slice(2, 4)
      val l1 = join(l0, if (more.isEmpty) Missing else more.mkString("_"))
      val l2 = join(l1, work.year.fold(Missing)(_.toString))
      val first = work.authors.headOption
        .map(m => PersonName.parse(m.rawName).familyBlockKey)
        .filter(_.nonEmpty)
      val l3 = join(l2, first.getOrElse(Missing))
      Vector(l0, l1, l2, l3)
    }
  }
}
