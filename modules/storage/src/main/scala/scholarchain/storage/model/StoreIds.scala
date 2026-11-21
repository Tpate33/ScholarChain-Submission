package scholarchain.storage.model

import scholarchain.core.{GraphPropertyKind, IdKind, StableId}

/** ID helpers of the storage layer: every ID comes from `core` and is only validated here. */
object StoreIds {

  /** The ID itself when it is a valid ID of `kind`, else `Refused`. */
  def require(kind: IdKind, id: String, field: String): Either[StoreError, String] =
    StableId
      .validate(kind, id, field)
      .left
      .map(e => StoreError.Refused(e.message))

  /**
   * The 64-hex body of a hash ID (`run:`, `load:`, `chunk:` and the other hash kinds): the stable,
   * filesystem- and key-safe form used inside object keys.
   */
  def hexOf(kind: IdKind, id: String, field: String): Either[StoreError, String] =
    require(kind, id, field).flatMap { valid =>
      val body = valid.substring(kind.prefix.length + 1)
      if (scholarchain.core.Canonical.isHex64(body)) Right(body)
      else Left(StoreError.Refused(s"$field: ${kind.prefix} is not a hash ID kind"))
    }

  /** A valid ID of any kind, else `Refused`. */
  def requireAny(id: String, field: String): Either[StoreError, String] =
    StableId
      .validateAny(id, field)
      .left
      .map(e => StoreError.Refused(e.message))
      .map(_ => id)

  /** A token `[A-Za-z0-9._-]+`, else `Refused`. */
  def token(value: String, field: String): Either[StoreError, String] =
    if (StableId.isToken(value)) Right(value)
    else Left(StoreError.Refused(s"$field: '$value' is not a token [A-Za-z0-9._-]+"))

  /** A calendar year accepted by the canonical graph profiles, else `Refused`. */
  def year(value: Int, field: String): Either[StoreError, Int] =
    GraphPropertyKind.Year.problem(value.toString) match {
      case None => Right(value)
      case Some(why) => Left(StoreError.Refused(s"$field: $why"))
    }

  /** A window `start..end` (both years, start <= end), rendered `SSSS-EEEE`. */
  def window(startYear: Int, endYear: Int): Either[StoreError, String] =
    for {
      s <- year(startYear, "windowStartYear")
      e <- year(endYear, "windowEndYear")
      _ <-
        if (s <= e) Right(())
        else Left(StoreError.Refused(s"window: start $s is after end $e"))
    } yield f"$s%04d-$e%04d"

  private val WindowKey = "^([0-9]{4})-([0-9]{4})$".r

  /** The years of a window key `SSSS-EEEE` (as [[window]] renders it), else `Refused`. */
  def windowYears(key: String, field: String): Either[StoreError, (Int, Int)] =
    key match {
      case WindowKey(a, b) => window(a.toInt, b.toInt).map(_ => (a.toInt, b.toInt))
      case _ => Left(StoreError.Refused(s"$field: '$key' is not a window key SSSS-EEEE"))
    }

  /** A valid window key, else `Refused`. */
  def windowKey(key: String, field: String): Either[StoreError, String] =
    windowYears(key, field).map(_ => key)

  /** 64 lowercase hex characters, else `Refused`. */
  def hex64(value: String, field: String): Either[StoreError, String] =
    if (value != null && scholarchain.core.Canonical.isHex64(value)) Right(value)
    else Left(StoreError.Refused(s"$field: expected 64 lowercase hex characters"))

  /** Collects the first error of a sequence of checks. */
  def firstError(checks: Either[StoreError, ?]*): Either[StoreError, Unit] =
    checks.collectFirst { case Left(e) => e }.toLeft(())
}
