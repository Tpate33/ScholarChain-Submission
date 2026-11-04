package scholarchain.entityresolution

import java.text.Normalizer

/**
 * Locale-independent Unicode folding used by every blocking key and match feature.
 *
 * `fold` applies, in order:
 *   1. Unicode NFKD (compatibility decomposition: ligatures such as U+FB01 become `fi`, accented
 *      letters become base letter plus combining mark, superscript digits become digits).
 *   2. Removal of every combining mark (Unicode categories Mn, Me, Mc): `o` + U+0308 (from `ö`)
 *      becomes `o`, `a` + U+0303 (from `ã`) becomes `a`.
 *   3. An explicit transliteration table for letters that NFKD does not decompose ([[Special]]):
 *      `ß` -> `ss`, `æ` -> `ae`, `œ` -> `oe`, `ø` -> `o`, `ł` -> `l`, `đ`/`ð` -> `d`, `þ` -> `th`,
 *      dotless `ı` -> `i`, `ħ` -> `h`, `ŧ` -> `t` (both cases).
 *   4. Lower-casing per code point with `Character.toLowerCase(int)`, which uses Unicode data only
 *      and never the default locale (so Turkish `I` is `i` on every JVM).
 *   5. Apostrophes (U+0027, U+2018, U+2019, U+02BC, U+0060) are deleted, so `O'Brien` is `obrien`.
 *   6. Hyphens and dashes (U+002D, U+2010-U+2015, U+2212) become `-` when `keepHyphen`, otherwise a
 *      space. Every other character that is not a letter or digit (any script) becomes a space.
 *   7. Space runs are collapsed and the result trimmed.
 *
 * The output therefore contains only lower-case letters and digits of any script, single spaces,
 * and (with `keepHyphen`) `-`. It never contains a tab, a control character, or `|`.
 */
object TextFolding {

  /** Letters NFKD leaves undecomposed, with their ASCII transliteration (both cases). */
  val Special: Map[Int, String] = Map(
    0x00df -> "ss", // sharp s
    0x1e9e -> "ss", // capital sharp s
    0x00e6 -> "ae", // ae ligature
    0x00c6 -> "ae",
    0x0153 -> "oe", // oe ligature
    0x0152 -> "oe",
    0x00f8 -> "o", // o with stroke
    0x00d8 -> "o",
    0x0142 -> "l", // l with stroke
    0x0141 -> "l",
    0x0111 -> "d", // d with stroke
    0x0110 -> "d",
    0x00f0 -> "d", // eth
    0x00d0 -> "d",
    0x00fe -> "th", // thorn
    0x00de -> "th",
    0x0131 -> "i", // dotless i
    0x0127 -> "h", // h with stroke
    0x0126 -> "h",
    0x0167 -> "t", // t with stroke
    0x0166 -> "t"
  )

  private val Apostrophes: Set[Int] = Set(0x0027, 0x2018, 0x2019, 0x02bc, 0x0060)
  private val Hyphens: Set[Int] =
    Set(0x002d, 0x2010, 0x2011, 0x2012, 0x2013, 0x2014, 0x2015, 0x2212)

  private def isMark(cp: Int): Boolean = {
    val t = Character.getType(cp)
    t == Character.NON_SPACING_MARK || t == Character.ENCLOSING_MARK ||
    t == Character.COMBINING_SPACING_MARK
  }

  /** Folds `raw` as documented on the object. `null` folds to the empty string. */
  def fold(raw: String, keepHyphen: Boolean): String =
    if (raw == null) ""
    else {
      val d = Normalizer.normalize(raw, Normalizer.Form.NFKD)
      val sb = new java.lang.StringBuilder(d.length)
      var i = 0
      while (i < d.length) {
        val cp = d.codePointAt(i)
        i += Character.charCount(cp)
        if (!isMark(cp)) {
          Special.get(cp) match {
            case Some(s) => sb.append(s)
            case None =>
              if (Character.isLetterOrDigit(cp)) sb.appendCodePoint(Character.toLowerCase(cp))
              else if (Apostrophes.contains(cp)) ()
              else if (keepHyphen && Hyphens.contains(cp)) sb.append('-')
              else sb.append(' ')
          }
        }
      }
      collapseSpaces(sb.toString)
    }

  /** Words of the folded text (split on single spaces, no empty words). */
  def words(raw: String, keepHyphen: Boolean): Vector[String] = {
    val f = fold(raw, keepHyphen)
    if (f.isEmpty) Vector.empty else f.split(' ').toVector.filter(_.nonEmpty)
  }

  /** Collapses runs of spaces to one space and trims. */
  def collapseSpaces(s: String): String =
    s.split(' ').iterator.filter(_.nonEmpty).mkString(" ")

  /**
   * Collapses the German vowel digraphs `ae`, `oe`, `ue` to `a`, `o`, `u` (left to right). Applied
   * to already folded name keys, so that `Pöpper` (folded `popper`), `Poepper`, and `Popper` share
   * the key `popper`, and `Müller`/`Mueller` share `muller`. This is a blocking-recall device, not
   * evidence of identity: unrelated spellings such as `Michael`/`Michal` also collapse together.
   */
  def collapseVowelDigraphs(s: String): String = {
    val sb = new java.lang.StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
      val c = s.charAt(i)
      sb.append(c)
      if ((c == 'a' || c == 'o' || c == 'u') && i + 1 < s.length && s.charAt(i + 1) == 'e') i += 2
      else i += 1
    }
    sb.toString
  }

  /** First code point of `s` as a string (empty for an empty string). */
  def firstCodePoint(s: String): String =
    if (s.isEmpty) "" else new String(Character.toChars(s.codePointAt(0)))

  /** Number of code points. */
  def codePointLength(s: String): Int = s.codePointCount(0, s.length)
}

/** Deterministic similarity measures. Every result is finite. */
object Similarity {

  /** Levenshtein edit distance over Unicode code points (unit costs). */
  def levenshtein(a: String, b: String): Int = {
    val x = a.codePoints.toArray
    val y = b.codePoints.toArray
    if (x.isEmpty) y.length
    else if (y.isEmpty) x.length
    else {
      var prev = Array.tabulate(y.length + 1)(identity)
      var cur = new Array[Int](y.length + 1)
      var i = 1
      while (i <= x.length) {
        cur(0) = i
        var j = 1
        while (j <= y.length) {
          val cost = if (x(i - 1) == y(j - 1)) 0 else 1
          cur(j) = math.min(math.min(cur(j - 1) + 1, prev(j) + 1), prev(j - 1) + cost)
          j += 1
        }
        val t = prev
        prev = cur
        cur = t
        i += 1
      }
      prev(y.length)
    }
  }

  /**
   * `1 - levenshtein(a, b) / max(|a|, |b|)` in [0, 1] (lengths in code points). Defined as 0 when
   * either string is empty: an absent value is no evidence of similarity.
   */
  def levenshteinSimilarity(a: String, b: String): Double = {
    val la = TextFolding.codePointLength(a)
    val lb = TextFolding.codePointLength(b)
    if (la == 0 || lb == 0) 0.0
    else 1.0 - levenshtein(a, b).toDouble / math.max(la, lb).toDouble
  }

  /** `|a ∩ b| / |a ∪ b|` in [0, 1]; 0 when both sets are empty. */
  def jaccard(a: Set[String], b: Set[String]): Double = {
    val inter = a.count(b.contains)
    val union = a.size + b.size - inter
    if (union == 0) 0.0 else inter.toDouble / union.toDouble
  }

  /**
   * Length of the longest common subsequence of two sequences. Empty strings (unreadable values)
   * never match anything.
   */
  def lcsLength(a: IndexedSeq[String], b: IndexedSeq[String]): Int =
    if (a.isEmpty || b.isEmpty) 0
    else {
      var prev = new Array[Int](b.length + 1)
      var cur = new Array[Int](b.length + 1)
      var i = 1
      while (i <= a.length) {
        cur(0) = 0
        var j = 1
        while (j <= b.length) {
          cur(j) =
            if (a(i - 1).nonEmpty && a(i - 1) == b(j - 1)) prev(j - 1) + 1
            else math.max(prev(j), cur(j - 1))
          j += 1
        }
        val t = prev
        prev = cur
        cur = t
        i += 1
      }
      prev(b.length)
    }
}
