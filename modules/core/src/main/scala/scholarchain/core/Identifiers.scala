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

  // ---------------------------------------------------------------- Semantic Scholar
  //
  // Not observed: no selected dataset carries Semantic Scholar identifiers
  // (datasets/schemas/identifiers.md). The forms below follow Semantic Scholar's public
  // conventions: a paper has a numeric corpus ID (written `CorpusId:<n>` in its external-ID
  // lists) and a 40-character hexadecimal paper ID; an author has a numeric author ID. Web URLs
  // are `https://www.semanticscholar.org/paper/[<title slug>/]<paper ID>` and
  // `https://www.semanticscholar.org/author/[<name slug>/]<author ID>`. The 18-digit bound keeps
  // numeric IDs inside a signed 64-bit integer; it is a representation limit, not an observed
  // maximum.

  private val S2Numeric = "^[1-9][0-9]{0,17}$".r
  private val S2PaperHex = "^[0-9a-f]{40}$".r
  private val S2CorpusPrefix = "corpusid:"
  private val S2WebUrl =
    "^(?i:https?://(?:www\\.)?semanticscholar\\.org)/(paper|author)/(?:[^/?#]+/)?([^/?#]+)/?(?:[?#].*)?$".r

  /** Semantic Scholar corpus ID (a paper): decimal digits, no leading zero, at most 18 digits. */
  def isValidS2CorpusId(s: String): Boolean = s != null && S2Numeric.matches(s)

  /** Semantic Scholar paper ID (a paper): exactly 40 lowercase hexadecimal characters. */
  def isValidS2PaperId(s: String): Boolean = s != null && S2PaperHex.matches(s)

  /** Semantic Scholar author ID (an author): decimal digits, no leading zero, at most 18 digits. */
  def isValidS2AuthorId(s: String): Boolean = s != null && S2Numeric.matches(s)

  private def s2Web(kind: String, raw: String): Option[String] = raw match {
    case S2WebUrl(k, id) if k.equalsIgnoreCase(kind) => Some(id)
    case _ => None
  }

  /** Accepts the bare digits or `CorpusId:<digits>` (prefix in any case); returns the digits. */
  def normalizeS2CorpusId(raw: String): Either[String, String] = {
    val bare =
      if (raw != null && raw.regionMatches(true, 0, S2CorpusPrefix, 0, S2CorpusPrefix.length))
        raw.substring(S2CorpusPrefix.length)
      else raw
    if (isValidS2CorpusId(bare)) Right(bare)
    else Left("not a Semantic Scholar corpus ID (decimal digits, no leading zero)")
  }

  /** Accepts the bare paper ID (any case) or a semanticscholar.org paper URL; lowercases. */
  def normalizeS2PaperId(raw: String): Either[String, String] = {
    val id = Option(raw).map(r => s2Web("paper", r).getOrElse(r).toLowerCase(Locale.ROOT))
    id.filter(isValidS2PaperId).toRight("not a Semantic Scholar paper ID (40 hexadecimal digits)")
  }

  /** Accepts the bare author ID or a semanticscholar.org author URL; returns the digits. */
  def normalizeS2AuthorId(raw: String): Either[String, String] = {
    val id = Option(raw).map(r => s2Web("author", r).getOrElse(r))
    id.filter(isValidS2AuthorId)
      .toRight("not a Semantic Scholar author ID (decimal digits, no leading zero)")
  }

  // ---------------------------------------------------------------- repository URL
  //
  // Not observed: no selected dataset records repository URLs (datasets/schemas/identifiers.md).

  /**
   * URL schemes accepted on input, with their default port. Every accepted form is rewritten to
   * `https`, so a non-default port (which would not survive that rewrite) is refused.
   */
  private val RepositorySchemes: Map[String, String] = Map(
    "https" -> "443",
    "http" -> "80",
    "ssh" -> "22",
    "git+https" -> "443",
    "git+http" -> "80",
    "git+ssh" -> "22"
  )

  /**
   * Hosts whose owner and repository names are routed case-insensitively, so both are lowercased
   * and the URL must name exactly `<owner>/<repository>`. Only GitHub is listed: case-folding the
   * path of any other host could merge distinct repositories.
   */
  val CaseInsensitiveRepositoryHosts: Set[String] = Set("github.com")

  /**
   * Hosts on which the query string never names the repository, so a query is dropped. On these
   * hosts the repository is exactly the `<owner>/<repository>` path (see
   * [[CaseInsensitiveRepositoryHosts]]). On every other host a query might be the only thing that
   * names the repository (for example gitweb `index.cgi?p=<repository>`), so a URL with a non-empty
   * query is refused rather than risking a merge.
   */
  val QueryIgnoringRepositoryHosts: Set[String] = Set("github.com")

  private val UrlForm =
    "^([A-Za-z][A-Za-z0-9+.-]*)://([^/?#]*)([^?#]*)(?:\\?([^#]*))?(?:#.*)?$".r
  private val ScpForm = "^[A-Za-z0-9._-]+@([A-Za-z0-9.-]+):([^/?#][^?#]*)$".r
  private val HostName =
    "^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$".r
  private val RepositorySegment = "^[A-Za-z0-9._~-]+$".r

  /**
   * Canonical repository URL: `https://<host>/<segment>/<segment>[/<segment>...]`.
   *
   * Accepted inputs: `https`, `http`, `ssh`, `git+https`, `git+http`, `git+ssh` URLs and the
   * scp-like form `<user>@<host>:<path>`. Rewriting: the scheme becomes `https`; the host is
   * lowercased and one leading `www.` removed; user information (credentials) is dropped and never
   * echoed in an error ([[errorDisplay]]); a default port is dropped; the fragment is dropped (it
   * is never sent to the server, so it cannot name a repository); an empty query (`?` alone) is
   * dropped; a non-empty query is dropped only on [[QueryIgnoringRepositoryHosts]] and refused on
   * every other host; trailing slashes are removed; a `.git` suffix of the last segment is removed.
   * Path case is preserved, except on [[CaseInsensitiveRepositoryHosts]], where exactly two
   * segments (owner and repository) are required and both are lowercased. Refused: other schemes,
   * non-default ports, a non-empty query on a host that is not query-ignoring (the query may be the
   * repository name, as in gitweb `index.cgi?p=<repository>`), empty, `.` or `..` segments,
   * characters outside `[A-Za-z0-9._~-]` in a segment (percent-encoding included), fewer than two
   * segments, and hosts that are not dotted DNS names. Deep links such as
   * `/<owner>/<repo>/tree/<branch>` are not shortened: a deep link that is not a repository root
   * fails to match rather than risking a merge. The rule never guesses: it is a format rule, not a
   * check that the repository exists.
   */
  def normalizeRepositoryUrl(raw: String): Either[String, String] =
    if (!isPrintableAscii(raw)) Left("must be non-empty printable ASCII without whitespace")
    else {
      val parts: Either[String, (String, String, String, String)] = raw match {
        case UrlForm(scheme, authority, path, query) =>
          Right((scheme.toLowerCase(Locale.ROOT), authority, path, Option(query).getOrElse("")))
        case ScpForm(host, path) => Right(("ssh", host, "/" + path, ""))
        case _ => Left("not a repository URL")
      }
      parts.flatMap { case (scheme, authority, path, query) =>
        RepositorySchemes.get(scheme) match {
          case None => Left("repository URL scheme must be https, http, ssh, or git+<one of these>")
          case Some(defaultPort) =>
            val hostPort = authority.substring(authority.lastIndexOf('@') + 1)
            val colon = hostPort.indexOf(':')
            val (rawHost, port) =
              if (colon < 0) (hostPort, "") else (hostPort.take(colon), hostPort.drop(colon + 1))
            val host = rawHost.toLowerCase(Locale.ROOT).stripPrefix("www.")
            val trimmed = path.reverse.dropWhile(_ == '/').reverse
            val segments = trimmed.split("/", -1).toVector.drop(1)
            val last = segments.lastOption.map(_.stripSuffix(".git"))
            val segs = segments.dropRight(1) ++ last.toVector
            if (!HostName.matches(host)) Left("repository URL host is not a DNS name")
            else if (port.nonEmpty && port != defaultPort) Left("non-default ports are not allowed")
            else if (query.nonEmpty && !QueryIgnoringRepositoryHosts.contains(host))
              Left(
                "a query string may name the repository on this host; refused rather than risking a merge"
              )
            else if (!trimmed.startsWith("/") || segs.size < 2)
              Left("repository URL must name at least <owner>/<repository>")
            else if (segs.exists(s => !RepositorySegment.matches(s) || s.forall(_ == '.')))
              Left("repository path segments must match [A-Za-z0-9._~-]+ and not be . or ..")
            else if (CaseInsensitiveRepositoryHosts.contains(host))
              if (segs.size != 2)
                Left(s"a $host repository URL must be exactly <owner>/<repository>")
              else Right(s"https://$host/" + segs.map(_.toLowerCase(Locale.ROOT)).mkString("/"))
            else Right(s"https://$host/" + segs.mkString("/"))
        }
      }
    }

  /**
   * What a validation error shows in place of a repository-URL value: a fixed placeholder that
   * contains no part of the value.
   */
  val WithheldRepositoryUrl: String = "<repository-url withheld>"

  /**
   * The value as shown in a validation error. A repository-URL value is never echoed, in whole or
   * in part: it is replaced by [[WithheldRepositoryUrl]]. Pattern-based redaction was rejected
   * (RV-P5-04 m10, TE-P5-01 D2) because credentials can sit in many places that a pattern can miss:
   * after leading whitespace, in a password containing `/`, in scp-like `user:pass@host:path`, in
   * scheme-less user information, or in query parameters such as `access_token=`. Even showing the
   * scheme and host is unsafe, because a password containing `/` makes the user name and a numeric
   * password look like a host and port. Other schemes are echoed unchanged; `null` is shown as
   * `null`.
   */
  def errorDisplay(scheme: ExactIdScheme, raw: String): String =
    if (raw == null) "null"
    else if (scheme == ExactIdScheme.RepositoryUrl) WithheldRepositoryUrl
    else raw

  /** True for a canonical repository URL ([[normalizeRepositoryUrl]] leaves it unchanged). */
  def isCanonicalRepositoryUrl(s: String): Boolean = normalizeRepositoryUrl(s) == Right(s)

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
      case ExactIdScheme.SemanticScholarCorpus => isValidS2CorpusId(value)
      case ExactIdScheme.SemanticScholarPaper => isValidS2PaperId(value)
      case ExactIdScheme.SemanticScholarAuthor => isValidS2AuthorId(value)
      case ExactIdScheme.RepositoryUrl => isCanonicalRepositoryUrl(value)
    }
    if (ok) Right(value) else Left(s"not a canonical ${scheme.tag} identifier")
  }

  /**
   * Normalizes a raw exact identifier to its canonical form: DOI resolver prefixes and case, the
   * ORCID URI prefix, the OpenAlex URI prefix, the Semantic Scholar `CorpusId:` prefix and web
   * URLs, and the repository URL rewriting of [[normalizeRepositoryUrl]]. Other schemes must
   * already be canonical.
   */
  def normalizeExact(scheme: ExactIdScheme, raw: String): Either[String, String] =
    scheme match {
      case ExactIdScheme.Doi => normalizeDoi(raw)
      case ExactIdScheme.Orcid => normalizeOrcid(raw)
      case ExactIdScheme.OpenAlexWork => normalizeOpenAlex('W', raw)
      case ExactIdScheme.OpenAlexAuthor => normalizeOpenAlex('A', raw)
      case ExactIdScheme.SemanticScholarCorpus => normalizeS2CorpusId(raw)
      case ExactIdScheme.SemanticScholarPaper => normalizeS2PaperId(raw)
      case ExactIdScheme.SemanticScholarAuthor => normalizeS2AuthorId(raw)
      case ExactIdScheme.RepositoryUrl => normalizeRepositoryUrl(raw)
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
