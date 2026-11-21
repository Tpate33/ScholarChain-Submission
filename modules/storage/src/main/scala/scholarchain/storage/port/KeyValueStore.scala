package scholarchain.storage.port

import scholarchain.storage.model.{Item, PrimaryKey, PutOutcome, StoreError, TableRole}

/** Sort-key condition of a Query. */
enum SortKeyCondition {
  case All
  case Equals(value: String)
  case BeginsWith(prefix: String)
  case Between(low: String, high: String)
}

/**
 * One Query on the table key (`index` None) or a GSI: a partition value, a sort condition and a
 * bound. There is no Scan anywhere in the storage ports (SE-P7-01).
 */
final case class KeyQuery(
    index: Option[String],
    partition: String,
    sort: SortKeyCondition,
    limit: Int,
    ascending: Boolean = true
)

/** Read side of DynamoDB: GetItem and Query only, never Scan. */
trait KeyValueReader {
  def get(table: TableRole, key: PrimaryKey): Either[StoreError, Option[Item]]
  def query(table: TableRole, q: KeyQuery): Either[StoreError, Vector[Item]]
}

/**
 * Write side of DynamoDB: conditional writes only (README L1388). `BatchWriteItem` is never used
 * for loads, because it cannot carry a condition.
 *
 *   - `putIfAbsent`: `attribute_not_exists(pk)` with ALL_OLD on failure; an existing item with the
 *     same `recordSha256` is `AlreadyPresent`, a different one is `Conflict`.
 *   - `putIfVersion`: replaces the item only when the stored `version` equals `expectedVersion`;
 *     the new item's `version` must be `expectedVersion + 1`. A lost race or a missing item is
 *     `StaleVersion`.
 */
trait KeyValueWriter {
  def putIfAbsent(table: TableRole, item: Item): Either[StoreError, PutOutcome]
  def putIfVersion(table: TableRole, item: Item, expectedVersion: Long): Either[StoreError, Unit]
}

/** Both sides, for loaders. */
trait KeyValueStore extends KeyValueReader with KeyValueWriter

object KeyValueReader {

  /** A facade exposing only the read methods of `r`. */
  def readOnly(r: KeyValueReader): KeyValueReader = new KeyValueReader {
    def get(table: TableRole, key: PrimaryKey) = r.get(table, key)
    def query(table: TableRole, q: KeyQuery) = r.query(table, q)
  }
}
