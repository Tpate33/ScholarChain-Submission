package scholarchain.core

import java.util.Locale

/**
 * Format rules for source identifiers. Patterns follow `datasets/schemas/identifiers.md` (observed
 * forms). Only ORCID and ISSN have official check-digit algorithms, and both are verified here.
 * Every other rule is a format check, not proof that the identifier exists. Every identifier is
 * printable ASCII without whitespace.
 */
object Identifiers {

  /** Printable ASCII (0x21-0x7E), non-empty. */
  def isPrintableAscii(s: String): Boolean =
    s != null && s.nonEmpty && s.forall(c => c >= '!' && c <= '~')

  // ---------------------------------------------------------------- ORCID
  private val OrcidBare = "^\\d{4}-\\d{4}-\\d{4}-\\d{3}[0-9X]$".r
  private val OrcidPrefixes = Vector("https://orcid.org/", "http://orcid.org/")

  /** ISO 7064 MOD 11-2 check character for the 15 base digits of an ORCID iD. */
  def orcidCheckChar(baseDigits: String): Char = {
    val total = baseDigits.foldLeft(0)((acc, c) => (acc + (c - '0')) * 2)
    val result = (12 - total % 11) % 11
    if (result == 10) 'X' else ('0' + result).toChar
  }

  /** True for the bare 19-character form with a valid check character. */
  def isValidOrcid(s: String): Boolean =
    s != null && OrcidBare.matches(s) && {
      val digits = s.filter(_ != '-')
      orcidCheckChar(digits.take(15)) == digits.last
    }

  /** Accepts the bare form or an `https://orcid.org/` URI; returns the bare form. */
  def normalizeOrcid(raw: String): Either[String, String] = {
    val bare = OrcidPrefixes.find(p => raw != null && raw.startsWith(p)) match {
      case Some(p) => raw.substring(p.length)
      case None => raw
    }
    if (bare == null || !OrcidBare.matches(bare)) Left("not of the form NNNN-NNNN-NNNN-NNN[0-9X]")
    else if (!isValidOrcid(bare)) Left("ISO 7064 MOD 11-2 check character mismatch")
    else Right(bare)
  }

  // ---------------------------------------------------------------- DOI
  private val DoiPattern = "^10\\.\\d{4,9}/[!-~]+$".r
  private val DoiPrefixes =
    Vector("https://doi.org/", "http://doi.org/", "https://dx.doi.org/", "http://dx.doi.org/")

  /** True for a canonical DOI: bare `10.<registrant>/<suffix>`, lowercase, printable ASCII. */
  def isCanonicalDoi(s: String): Boolean =
    s != null && DoiPattern.matches(s) && s == s.toLowerCase(Locale.ROOT)

  /** Strips a doi.org resolver prefix or `doi:` and lowercases (DOIs are case-insensitive). */
  def normalizeDoi(raw: String): Either[String, String] = {
    if (raw == null) Left("null")
    else {
      val lower = raw.toLowerCase(Locale.ROOT)
      val stripped = DoiPrefixes.find(lower.startsWith) match {
        case Some(p) => lower.substring(p.length)
        case None if lower.startsWith("doi:") => lower.substring(4)
        case None => lower
      }
      if (isCanonicalDoi(stripped)) Right(stripped)
      else Left("not of the form 10.<registrant>/<suffix> in printable ASCII")
    }
  }

  // ---------------------------------------------------------------- ISSN
  private val IssnPattern = "^\\d{4}-\\d{3}[0-9X]$".r

  /** ISSN `NNNN-NNNC` with the ISO 3297 mod-11 check character. */
  def isValidIssn(s: String): Boolean =
    s != null && IssnPattern.matches(s) && {
      val digits = s.filter(_ != '-')
      val sum = digits.take(7).zipWithIndex.map { case (c, i) => (c - '0') * (8 - i) }.sum
      val check = (11 - sum % 11) % 11
      val expected = if (check == 10) 'X' else ('0' + check).toChar
      digits.last == expected
    }

  // ---------------------------------------------------------------- ROR
  private val RorPattern = "^0[0-9a-hjkmnp-tv-z]{6}[0-9]{2}$".r
  private val RorPrefix = "https://ror.org/"

  /** ROR suffix: leading 0, six Crockford base32 characters, two digits (format only). */
  def isValidRor(s: String): Boolean = s != null && RorPattern.matches(s)

  def normalizeRor(raw: String): Either[String, String] = {
    val bare =
      if (raw != null && raw.startsWith(RorPrefix)) raw.substring(RorPrefix.length) else raw
    if (isValidRor(bare)) Right(bare) else Left("not a 9-character ROR suffix")
  }

  // ---------------------------------------------------------------- OpenAlex
  private val OpenAlexPrefix = "https://openalex.org/"

  /** OpenAlex short ID with the given entity letter (W, A, I, S, T) followed by digits. */
  def isValidOpenAlex(letter: Char, s: String): Boolean =
    s != null && s.length >= 2 && s.charAt(0) == letter &&
      s.drop(1).forall(c => c >= '0' && c <= '9')

  /** Any OpenAlex short ID (W, A, I, S, or T entity). */
  def isAnyOpenAlex(s: String): Boolean =
    s != null && s.nonEmpty && "WAIST".contains(s.charAt(0)) && isValidOpenAlex(s.charAt(0), s)

  def normalizeOpenAlex(letter: Char, raw: String): Either[String, String] = {
    val bare =
      if (raw != null && raw.startsWith(OpenAlexPrefix)) raw.substring(OpenAlexPrefix.length)
      else raw
    if (isValidOpenAlex(letter, bare)) Right(bare) else Left(s"not an OpenAlex $letter<digits> ID")
  }

  // ---------------------------------------------------------------- DBLP
  private val DblpKeyPattern = "^[a-z]+(/[!-.0-~]+)+$".r
  private val DblpPidPattern = "^[A-Za-z0-9]+/[A-Za-z0-9._-]+$".r

  /** DBLP record or stream key: lowercase type segment then one or more `/`-separated segments. */
  def isValidDblpKey(s: String): Boolean = s != null && DblpKeyPattern.matches(s)

  /** DBLP person ID such as `11/3019`, `69/6042-1`, or `d/JeffreyDean` (observed forms). */
  def isValidDblpPid(s: String): Boolean = s != null && DblpPidPattern.matches(s)

  // ---------------------------------------------------------------- arXiv
  private val ArxivNew = "^\\d{4}\\.\\d{4,5}(v\\d+)?$".r
  private val ArxivOld = "^[a-z]+(-[a-z]+)*(\\.[A-Z]{2})?/\\d{7}(v\\d+)?$".r

  def isValidArxiv(s: String): Boolean = s != null && (ArxivNew.matches(s) || ArxivOld.matches(s))

  // ---------------------------------------------------------------- Crossref member
  def isValidCrossrefMember(s: String): Boolean =
    s != null && s.nonEmpty && s.forall(c => c >= '0' && c <= '9')

  // ---------------------------------------------------------------- MAG, Scopus EID
  private val MagPattern = "^[0-9]+$".r
  private val ScopusEidPattern = "^2-s2\\.0-[0-9]+$".r

  /** Microsoft Academic Graph work ID as carried by OpenAlex `ids.mag`: a digit string. */
  def isValidMag(s: String): Boolean = s != null && MagPattern.matches(s)

  /** Scopus EID as observed inside ORCID external IDs: `2-s2.0-<digits>` (observed form). */
  def isValidScopusEid(s: String): Boolean = s != null && ScopusEidPattern.matches(s)

  /** Validates an exact identifier value against its scheme's canonical form (no rewriting). */
  def validateExact(scheme: ExactIdScheme, value: String): Either[String, String] = {
    val ok = scheme match {
      case ExactIdScheme.Doi => isCanonicalDoi(value)
      case ExactIdScheme.Arxiv => isValidArxiv(value)
      case ExactIdScheme.DblpKey => isValidDblpKey(value)
      case ExactIdScheme.OpenAlexWork => isValidOpenAlex('W', value)
      case ExactIdScheme.Mag => isValidMag(value)
      case ExactIdScheme.ScopusEid => isValidScopusEid(value)
      case ExactIdScheme.Orcid => isValidOrcid(value)
      case ExactIdScheme.OpenAlexAuthor => isValidOpenAlex('A', value)
      case ExactIdScheme.DblpPid => isValidDblpPid(value)
    }
    if (ok) Right(value) else Left(s"not a canonical ${scheme.tag} identifier")
  }

  /**
   * Normalizes a raw exact identifier to its canonical form: DOI resolver prefixes and case, the
   * ORCID URI prefix, and the OpenAlex URI prefix are removed. Other schemes must already be
   * canonical.
   */
  def normalizeExact(scheme: ExactIdScheme, raw: String): Either[String, String] =
    scheme match {
      case ExactIdScheme.Doi => normalizeDoi(raw)
      case ExactIdScheme.Orcid => normalizeOrcid(raw)
      case ExactIdScheme.OpenAlexWork => normalizeOpenAlex('W', raw)
      case ExactIdScheme.OpenAlexAuthor => normalizeOpenAlex('A', raw)
      case other => validateExact(other, raw)
    }

  /** Validates an external identifier value against its scheme's canonical form. */
  def validateExternal(scheme: ExternalIdScheme, value: String): Either[String, String] =
    scheme match {
      case ExternalIdScheme.Orcid =>
        if (isValidOrcid(value)) Right(value) else Left("not a checksum-valid bare ORCID iD")
      case ExternalIdScheme.DblpPid =>
        if (isValidDblpPid(value)) Right(value) else Left("not a DBLP person ID")
      case ExternalIdScheme.OpenAlex =>
        if (isAnyOpenAlex(value)) Right(value) else Left("not an OpenAlex short ID")
      case ExternalIdScheme.Doi =>
        if (isCanonicalDoi(value)) Right(value) else Left("not a canonical lowercase bare DOI")
      case ExternalIdScheme.Ror =>
        if (isValidRor(value)) Right(value) else Left("not a 9-character ROR suffix")
      case ExternalIdScheme.Issn =>
        if (isValidIssn(value)) Right(value) else Left("not a checksum-valid ISSN")
      case ExternalIdScheme.CrossrefMember =>
        if (isValidCrossrefMember(value)) Right(value) else Left("not a Crossref member number")
    }
}
