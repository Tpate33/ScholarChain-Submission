package scholarchain.storage.model

/**
 * A DynamoDB attribute value, independent of the AWS SDK (SE-P7-01): the SDK adapter (increment 7)
 * maps it one to one. Numbers are kept as their decimal text, as DynamoDB transmits them.
 */
enum AttrValue {
  case S(value: String)
  case N(value: String)
  case Bool(value: Boolean)
  case Null
  case L(values: Vector[AttrValue])
  case M(values: Map[String, AttrValue])
}

object AttrValue {
  private val Decimal = "^-?(0|[1-9][0-9]*)$".r

  def n(value: Long): AttrValue = N(value.toString)

  /** The string of an `S` attribute. */
  def string(item: Item, name: String): Either[StoreError, String] =
    item.get(name) match {
      case Some(S(v)) => Right(v)
      case Some(_) => Left(StoreError.Corrupt(s"attribute $name: expected type S"))
      case None => Left(StoreError.Corrupt(s"attribute $name: missing"))
    }

  /** The integer of an `N` attribute. */
  def long(item: Item, name: String): Either[StoreError, Long] =
    item.get(name) match {
      case Some(N(v)) if Decimal.matches(v) && v.toLongOption.isDefined => Right(v.toLong)
      case Some(N(_)) => Left(StoreError.Corrupt(s"attribute $name: not an integer"))
      case Some(_) => Left(StoreError.Corrupt(s"attribute $name: expected type N"))
      case None => Left(StoreError.Corrupt(s"attribute $name: missing"))
    }
}

/** One table's primary key: partition key and sort key values (both type S). */
final case class PrimaryKey(pk: String, sk: String)
