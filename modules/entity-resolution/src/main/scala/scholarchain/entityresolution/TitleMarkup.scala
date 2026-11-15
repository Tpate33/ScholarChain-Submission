package scholarchain.entityresolution

/**
 * Removal of markup from titles (README Phase 5 normalization: "title markup"). Crossref and
 * OpenAlex titles can carry HTML, JATS, or MathML elements (`<i>E. coli</i>`, `<jats:sub>2</jats:
 * sub>`), character references (`&amp;`, `&#946;`), and inline LaTeX (`$\beta$`, `\emph{x}`).
 *
 * NOT WIRED INTO `er-blocking.v2` OR `er-features.v2`. Those versions are frozen by the accepted
 * Phase 4 and Unicode gates; applying this function inside [[TitleText]] changes title keys and
 * features and therefore requires `er-blocking.v3` and `er-features.v3` together with the Hadoop
 * pipeline (owner hadoop-engineer). Until then it is available to callers that compare titles
 * outside the v2 features (for example adjudication display text).
 *
 * Steps, in order: element tags `<name ...>`, `</name>`, `<name/>` (letters, digits, `:`, `_`, `-`,
 * `.`) are replaced by a space; the named references `amp lt gt quot apos nbsp` and numeric
 * references `&#N;` / `&#xH;` (valid scalar values only) are decoded; LaTeX command names such as
 * `\emph` and `\beta` are removed, keeping their braced arguments, and `$`, `{`, `}` are removed;
 * whitespace runs are collapsed. Text that only looks like a comparison (`a < b`) is kept, because
 * a tag must start with `<` immediately followed by a letter or `/`.
 */
object TitleMarkup {

  private val Tag = "</?[A-Za-z][A-Za-z0-9:_.-]*(\\s[^<>]*)?/?>".r
  private val NamedRef = "&(amp|lt|gt|quot|apos|nbsp);".r
  private val DecRef = "&#([0-9]{1,7});".r
  private val HexRef = "&#[xX]([0-9A-Fa-f]{1,6});".r
  private val LatexCommand = "\\\\[A-Za-z]+\\*?".r

  private def scalar(cp: Int): Option[String] =
    if (Character.isValidCodePoint(cp) && !(cp >= 0xd800 && cp <= 0xdfff))
      Some(new String(Character.toChars(cp)))
    else None

  def strip(raw: String): String =
    if (raw == null) ""
    else {
      val noTags = Tag.replaceAllIn(raw, " ")
      val named = NamedRef.replaceAllIn(
        noTags,
        m =>
          java.util.regex.Matcher.quoteReplacement(m.group(1) match {
            case "amp" => "&"
            case "lt" => "<"
            case "gt" => ">"
            case "quot" => "\""
            case "apos" => "'"
            case _ => " "
          })
      )
      val dec = DecRef.replaceAllIn(
        named,
        m =>
          java.util.regex.Matcher
            .quoteReplacement(scalar(m.group(1).toInt).getOrElse(m.matched))
      )
      val hex = HexRef.replaceAllIn(
        dec,
        m =>
          java.util.regex.Matcher
            .quoteReplacement(scalar(Integer.parseInt(m.group(1), 16)).getOrElse(m.matched))
      )
      val noLatex =
        LatexCommand.replaceAllIn(hex, " ").filterNot(c => c == '$' || c == '{' || c == '}')
      noLatex.split("\\s+").iterator.filter(_.nonEmpty).mkString(" ")
    }
}
