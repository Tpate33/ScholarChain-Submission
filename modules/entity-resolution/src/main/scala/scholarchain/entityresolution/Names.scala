package scholarchain.entityresolution

import java.text.Normalizer

/**
 * A personal name split into folded parts. Parsing is a heuristic over the name as written; it
 * never implies identity (dataset manifest identity_policy: a matching name is not evidence of
 * identity on its own).
 *
 * @param givenParts
 *   folded given-name parts in source order; hyphenated given names are split (`Jean-Pierre` gives
 *   `jean`, `pierre`), initials are single letters (`J. J.` gives `j`, `j`)
 * @param familyWords
 *   folded family-name words in source order, nobiliary particles included (`Di Pietro` gives `di`,
 *   `pietro`); words keep inner hyphens
 * @param homonymSuffix
 *   DBLP homonym number (`0001` in `Christopher Frost 0001`), removed from the name parts
 */
final case class PersonName(
    givenParts: Vector[String],
    familyWords: Vector[String],
    homonymSuffix: Option[String]
) {

  /** Family words concatenated without spaces or hyphens (`dipietro`, `garciamolina`). */
  def familyKey: String = familyWords.mkString.replace("-", "")

  /** [[familyKey]] with German vowel digraphs collapsed (the blocking form). */
  def familyBlockKey: String = TextFolding.collapseVowelDigraphs(familyKey)

  /** First given-name initial (first code point of the first given part). */
  def firstInitial: Option[String] = givenParts.headOption.map(TextFolding.firstCodePoint)

  /** All given-name initials concatenated (`jc` for `James C.`). */
  def initials: String = givenParts.map(TextFolding.firstCodePoint).mkString

  /** First given part with vowel digraphs collapsed (the blocking form). */
  def firstGivenKey: Option[String] =
    givenParts.headOption.map(TextFolding.collapseVowelDigraphs)

  /** Given parts joined with single spaces. */
  def givenText: String = givenParts.mkString(" ")

  /** Given parts and the blocking family key, joined with single spaces. */
  def fullKey: String = (givenParts :+ familyBlockKey).filter(_.nonEmpty).mkString(" ")

  /** Name tokens: given parts plus the blocking family key. */
  def tokens: Set[String] = (givenParts :+ familyBlockKey).filter(_.nonEmpty).toSet

  /** True when no family name could be read (no blocking key, no name features). */
  def isEmpty: Boolean = familyKey.isEmpty
}

object PersonName {
  val Empty: PersonName = PersonName(Vector.empty, Vector.empty, None)

  /**
   * Lower-case nobiliary particles absorbed into the family name when they directly precede the
   * last word and at least one given word remains before them.
   */
  val Particles: Set[String] = Set(
    "da",
    "das",
    "de",
    "del",
    "della",
    "den",
    "der",
    "des",
    "di",
    "do",
    "dos",
    "du",
    "la",
    "le",
    "ten",
    "ter",
    "van",
    "von"
  )

  /** Generational suffixes dropped from the end of a name. */
  val Generational: Set[String] = Set("jr", "sr", "ii", "iii", "iv")

  private def isHomonymSuffix(w: String): Boolean =
    w.length == 4 && w.forall(c => c >= '0' && c <= '9')

  private def nameWords(s: String): Vector[String] =
    TextFolding
      .words(s, keepHyphen = true)
      .map(w => w.dropWhile(_ == '-').reverse.dropWhile(_ == '-').reverse)
      .filter(_.nonEmpty)

  private def splitGiven(ws: Vector[String]): Vector[String] =
    ws.flatMap(_.split('-').toVector.filter(_.nonEmpty))

  /** Removes a trailing DBLP homonym number, then a trailing generational suffix. */
  private def stripSuffixes(ws: Vector[String]): (Vector[String], Option[String]) = {
    val (a, suffix) =
      if (ws.size >= 2 && isHomonymSuffix(ws.last)) (ws.init, Some(ws.last)) else (ws, None)
    val b = if (a.size >= 2 && Generational.contains(a.last)) a.init else a
    (b, suffix)
  }

  /**
   * Parses a name as written by a source.
   *
   *   - `Family, Given` (one comma, a non-empty given part that is not only a generational suffix)
   *     is read as family name then given names.
   *   - Otherwise the last word is the family name, extended to the left by particles
   *     ([[Particles]]) while at least one given word remains; earlier words are given names.
   *   - A single word is a family name without given names.
   *   - A trailing 4-digit DBLP homonym number and a generational suffix are removed first.
   */
  def parse(raw: String): PersonName =
    if (raw == null) Empty
    else {
      val nfc = Normalizer.normalize(raw, Normalizer.Form.NFC)
      val comma = nfc.indexOf(',')
      val commaForm =
        if (comma < 0) None
        else {
          val left = nameWords(nfc.substring(0, comma))
          val right = nameWords(nfc.substring(comma + 1))
          if (left.nonEmpty && right.nonEmpty && !right.forall(Generational.contains)) {
            val (givenWords, suffix) = stripSuffixes(right)
            val family = if (left.size >= 2 && Generational.contains(left.last)) left.init else left
            Some(PersonName(splitGiven(givenWords), family, suffix))
          } else None
        }
      commaForm.getOrElse {
        val (ws, suffix) = stripSuffixes(nameWords(nfc))
        if (ws.isEmpty) Empty
        else if (ws.size == 1) PersonName(Vector.empty, ws, suffix)
        else {
          var start = ws.size - 1
          while (start - 1 >= 1 && Particles.contains(ws(start - 1))) start -= 1
          PersonName(splitGiven(ws.take(start)), ws.drop(start), suffix)
        }
      }
    }

  /**
   * A name given as separate fields (ORCID `given-names` and `family-name`). Without a usable
   * family name the given field is parsed as a full name.
   */
  def structured(givenNames: Option[String], familyName: Option[String]): PersonName = {
    val fw = familyName.toVector.flatMap(nameWords)
    if (fw.isEmpty) parse(givenNames.getOrElse(""))
    else PersonName(splitGiven(givenNames.toVector.flatMap(nameWords)), fw, None)
  }
}

/**
 * Title and other free-text folding for keys and features.
 *
 * Titles: format characters (Unicode Cf) are deleted, then a trailing parenthesised note (DBLP
 * appends editorial notes such as `(Awarded Best Paper!)`) is removed when text remains before it;
 * then the title is folded with hyphens as spaces ([[TextFolding.fold]]), so `Cyber-security` and
 * `Cyber security` agree and the trailing `.` of DBLP titles disappears.
 */
object TitleText {

  /** English function words ignored by title keys and token features. */
  val Stopwords: Set[String] = Set(
    "a",
    "an",
    "and",
    "are",
    "as",
    "at",
    "by",
    "for",
    "from",
    "in",
    "into",
    "is",
    "its",
    "of",
    "on",
    "or",
    "the",
    "to",
    "toward",
    "towards",
    "via",
    "with"
  )

  private def stripTrailingNote(s: String): String = {
    val t = s.reverse.dropWhile(c => c == '.' || Character.isWhitespace(c)).reverse
    if (!t.endsWith(")")) s
    else {
      val open = t.lastIndexOf('(')
      if (open <= 0) s
      else {
        val inner = t.substring(open + 1, t.length - 1)
        val before = t.substring(0, open)
        if (inner.contains("(") || inner.contains(")")) s
        else if (TextFolding.fold(before, keepHyphen = false).isEmpty) s
        else before
      }
    }
  }

  /**
   * Folded title words in order (may be empty). Format characters are deleted before the trailing
   * note is detected, so an invisible character after `)` or `.` does not hide the note.
   */
  def words(title: String): Vector[String] =
    if (title == null) Vector.empty
    else
      TextFolding.words(
        stripTrailingNote(
          TextFolding.removeFormat(Normalizer.normalize(title, Normalizer.Form.NFC))
        ),
        keepHyphen = false
      )

  /** Title words without [[Stopwords]]. */
  def contentTokens(title: String): Vector[String] = words(title).filterNot(Stopwords.contains)

  /** Folded title text: [[words]] joined by single spaces. */
  def normalize(title: String): String = words(title).mkString(" ")

  /** Content tokens of other free text (venue names, affiliation strings) as a set. */
  def textTokens(text: String): Set[String] =
    TextFolding.words(text, keepHyphen = false).filterNot(Stopwords.contains).toSet
}
