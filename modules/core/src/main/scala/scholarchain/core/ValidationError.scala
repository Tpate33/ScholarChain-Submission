package scholarchain.core

/**
 * Typed validation failures. Validation never throws for invalid data: it returns these values.
 * `field` is a dotted path inside the record (for example `provenance.runId` or
 * `authors[2].orcid`).
 */
enum ValidationError {
  case MalformedJson(detail: String)
  case MissingSchemaVersion(recordType: String)
  case UnsupportedSchemaVersion(recordType: String, found: String, supported: Vector[String])
  case MissingProvenance(recordType: String, field: String)
  case MissingField(field: String)
  case ConfidenceOutOfRange(field: String, value: Double)
  case MalformedId(field: String, kind: String, value: String, reason: String)
  case SelfPair(field: String, id: String)
  case NonCanonicalPair(field: String, first: String, second: String)
  case InvalidWindow(field: String, startYear: Int, endYear: Int)
  case InvalidValue(field: String, reason: String)
  case Undecodable(recordType: String, detail: String)

  /** Human-readable message (diagnostic only; not part of any wire format). */
  def message: String = this match {
    case MalformedJson(d) => s"malformed JSON: $d"
    case MissingSchemaVersion(r) => s"$r: missing schemaVersion"
    case UnsupportedSchemaVersion(r, f, s) =>
      s"$r: unsupported schemaVersion '$f' (supported: ${s.mkString(", ")})"
    case MissingProvenance(r, f) => s"$r: missing provenance at $f"
    case MissingField(f) => s"$f: missing or empty"
    case ConfidenceOutOfRange(f, v) => s"$f: confidence $v outside [0,1]"
    case MalformedId(f, k, v, why) => s"$f: malformed $k ID '$v': $why"
    case SelfPair(f, id) => s"$f: undirected pair has equal endpoints '$id'"
    case NonCanonicalPair(f, a, b) =>
      s"$f: undirected pair ('$a', '$b') is not in canonical order (first < second)"
    case InvalidWindow(f, s, e) => s"$f: window start year $s exceeds end year $e"
    case InvalidValue(f, why) => s"$f: $why"
    case Undecodable(r, d) => s"$r: cannot decode: $d"
  }
}

object Validated {

  /** Field path `prefix.name`, or `name` at top level. */
  def at(prefix: String, name: String): String = if (prefix.isEmpty) name else s"$prefix.$name"

  /** Collects errors from independent checks. */
  def all(checks: Vector[ValidationError]*): Vector[ValidationError] = checks.toVector.flatten

  def when(cond: Boolean)(err: => ValidationError): Vector[ValidationError] =
    if (cond) Vector(err) else Vector.empty

  def nonEmpty(field: String, value: String): Vector[ValidationError] =
    when(value == null || value.trim.isEmpty)(ValidationError.MissingField(field))

  def confidence(field: String, value: Double): Vector[ValidationError] =
    when(value.isNaN || value < 0.0 || value > 1.0)(
      ValidationError.ConfidenceOutOfRange(field, value)
    )

  def finite(field: String, value: Double): Vector[ValidationError] =
    when(value.isNaN || value.isInfinite)(
      ValidationError.InvalidValue(field, s"$value is not finite")
    )

  def nonNegative(field: String, value: Long): Vector[ValidationError] =
    when(value < 0)(ValidationError.InvalidValue(field, s"$value is negative"))

  def nonNegativeD(field: String, value: Double): Vector[ValidationError] =
    finite(field, value) ++ when(!value.isNaN && value < 0.0)(
      ValidationError.InvalidValue(field, s"$value is negative")
    )

  /** Smallest and largest accepted calendar year (inclusive). Years below 1000 are accepted. */
  val MinYear: Int = 0
  val MaxYear: Int = 9999

  /** Rejects years outside [[MinYear]]..[[MaxYear]] (inclusive). */
  def year(field: String, value: Int): Vector[ValidationError] =
    when(value < MinYear || value > MaxYear)(
      ValidationError.InvalidValue(field, s"year $value is outside [$MinYear, $MaxYear]")
    )

  def distinct(field: String, values: Seq[String]): Vector[ValidationError] =
    when(values.distinct.size != values.size)(
      ValidationError.InvalidValue(field, "contains duplicate values")
    )

  /** Rejects values that are not strictly ascending by UTF-8 bytes (unsorted or duplicated). */
  def strictlyAscending(field: String, values: Seq[String]): Vector[ValidationError] =
    when(
      values.exists(_ == null) ||
        values.sliding(2).exists(w => w.size == 2 && Canonical.compareUtf8(w(0), w(1)) >= 0)
    )(ValidationError.InvalidValue(field, "must be sorted by UTF-8 bytes without duplicates"))

  /** Non-blank text without ISO control characters (tab, newline, ...). */
  def text(field: String, value: String): Vector[ValidationError] =
    nonEmpty(field, value) ++ when(value != null && value.exists(Character.isISOControl))(
      ValidationError.InvalidValue(field, "contains control characters")
    )

  /** A link or URL as given by the source: non-blank, without whitespace or control characters. */
  def link(field: String, value: String): Vector[ValidationError] =
    nonEmpty(field, value) ++ when(
      value != null && value.exists(c => Character.isWhitespace(c) || Character.isISOControl(c))
    )(ValidationError.InvalidValue(field, "contains whitespace or control characters"))

  def fromEither(r: Either[ValidationError, ?]): Vector[ValidationError] =
    r.left.toOption.toVector
}
