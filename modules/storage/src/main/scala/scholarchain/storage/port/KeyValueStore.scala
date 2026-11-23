package scholarchain.storage.port

import scholarchain.storage.model.{
  Attrs,
  AttrValue,
  Item,
  PrimaryKey,
  PutOutcome,
  StoreError,
  StoreIds,
  TableRole,
  TableSpec
}

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

/**
 * SE-INT-01: strongly consistent batch reads (DynamoDB BatchGetItem), outside [[KeyValueStore]] so
 * existing implementations and callers in other modules are unchanged.
 *
 * `getMany` returns the items found, by key (absent keys are simply missing). Duplicate keys are
 * read once. The adapter sends at most [[KeyValueBatchReader.MaxKeysPerCall]] keys per call and
 * retries unprocessed keys a bounded number of times; keys still unprocessed after the bound are a
 * `Transient` error for the whole call, never a silent partial result.
 */
trait KeyValueBatchReader {
  def getMany(table: TableRole, keys: Vector[PrimaryKey]): Either[StoreError, Map[PrimaryKey, Item]]
}

object KeyValueBatchReader {

  /**
   * Keys per BatchGetItem call: 100 per AWS documentation as remembered (with a 16 MB response
   * limit); UNVERIFIED against AWS documentation (aws-docs MCP unavailable on 2026-11-22).
   */
  val MaxKeysPerCall = 100
}

/** SE-INT-01: what a conditional delete did. */
enum DeleteOutcome {

  /** The item held exactly the given record and is gone. */
  case Deleted

  /** No item under the key; nothing changed. */
  case Absent
}

/**
 * SE-INT-01: conditional delete of one exact record, outside [[KeyValueStore]]. Only the tables of
 * [[KeyValueDeleter.DeletableTables]] (QueryJob and IdempotencyKey) accept it; any other table is
 * `Refused` before anything is sent, so no loader-written or evidence table can lose a row.
 *
 * `deleteIfRecord` deletes the item under `key` only when its `recordSha256` equals the given hash:
 * `Deleted`; no item: `Absent`; an item holding another record: `Conflict`, nothing deleted.
 */
trait KeyValueDeleter {
  def deleteIfRecord(
      table: TableRole,
      key: PrimaryKey,
      recordSha256: String
  ): Either[StoreError, DeleteOutcome]
}

object KeyValueDeleter {
  val DeletableTables: Set[TableRole] = Set(TableRole.QueryJob, TableRole.IdempotencyKey)

  /** The refusal of a delete outside [[DeletableTables]] or with a malformed hash; None if ok. */
  def refusal(table: TableRole, key: PrimaryKey, recordSha256: String): Option[StoreError] =
    if (table == null || !DeletableTables.contains(table))
      Some(StoreError.Refused(s"delete: table ${Option(table).map(_.tag).orNull} is not deletable"))
    else if (key == null || key.pk == null || key.pk.isEmpty || key.sk == null || key.sk.isEmpty)
      Some(StoreError.Refused("delete: key attributes must be non-empty"))
    else
      StoreIds.hex64(recordSha256, "recordSha256").left.toOption
}

/** A store with every DynamoDB capability the service-state stores need. */
trait ServiceStateKeyValues extends KeyValueStore with KeyValueBatchReader with KeyValueDeleter

/** SE-INT-01: request checks shared by the in-memory store and the DynamoDB adapter. */
object KeyValueRules {

  /** The table key of `item` under `spec`: pk and sk non-empty S; GSI attributes paired S. */
  def keyOf(spec: TableSpec, item: Item): Either[StoreError, PrimaryKey] = {
    def s(name: String): Either[StoreError, String] = item.get(name) match {
      case Some(AttrValue.S(v)) if v.nonEmpty => Right(v)
      case _ =>
        Left(StoreError.Refused(s"${spec.role.tag}: key attribute $name must be a non-empty S"))
    }
    for {
      pk <- s(spec.partitionKey)
      sk <- s(spec.sortKey)
      _ <- spec.indexes
        .map { g =>
          (item.get(g.partitionKey), item.get(g.sortKey)) match {
            case (None, None) => Right(())
            case (Some(AttrValue.S(a)), Some(AttrValue.S(b))) if a.nonEmpty && b.nonEmpty =>
              Right(())
            case _ =>
              Left(
                StoreError.Refused(
                  s"${spec.role.tag}: index ${g.name} key attributes must be a pair of non-empty S"
                )
              )
          }
        }
        .collectFirst { case Left(e) => e }
        .toLeft(())
    } yield PrimaryKey(pk, sk)
  }

  /** Same record: equal `recordSha256` when both carry one, else equal items. */
  def sameRecord(a: Item, b: Item): Boolean =
    (a.get(Attrs.RecordSha256), b.get(Attrs.RecordSha256)) match {
      case (Some(x), Some(y)) => x == y
      case _ => a == b
    }

  /** The (partition, sort) attribute names of a valid query, else `Refused`. */
  def checkQuery(
      spec: TableSpec,
      q: KeyQuery,
      maxQueryLimit: Int
  ): Either[StoreError, (String, String)] =
    for {
      _ <-
        if (q != null && q.limit >= 1 && q.limit <= maxQueryLimit) Right(())
        else Left(StoreError.Refused(s"limit: outside 1..$maxQueryLimit"))
      attrs <- spec
        .keyAttributes(q.index)
        .toRight(StoreError.Refused(s"index: unknown ${q.index.orNull} on ${spec.role.tag}"))
      _ <-
        if (q.partition != null && q.partition.nonEmpty) Right(())
        else Left(StoreError.Refused("partition: missing"))
      _ <- q.sort match {
        case null => Left(StoreError.Refused("sort: missing"))
        case SortKeyCondition.Equals(v) if v == null => Left(StoreError.Refused("sort: missing"))
        case SortKeyCondition.BeginsWith(v) if v == null =>
          Left(StoreError.Refused("sort: missing"))
        case SortKeyCondition.Between(a, b) if a == null || b == null =>
          Left(StoreError.Refused("sort: missing"))
        case _ => Right(())
      }
    } yield attrs

  /** Valid batch keys, duplicates removed (first occurrence order). */
  def checkKeys(keys: Vector[PrimaryKey]): Either[StoreError, Vector[PrimaryKey]] =
    if (keys == null) Left(StoreError.Refused("keys: missing"))
    else
      keys.find(k =>
        k == null || k.pk == null || k.pk.isEmpty || k.sk == null || k.sk.isEmpty
      ) match {
        case Some(_) => Left(StoreError.Refused("keys: every key needs a non-empty pk and sk"))
        case None => Right(keys.distinct)
      }
}

object KeyValueReader {

  /** A facade exposing only the read methods of `r`. */
  def readOnly(r: KeyValueReader): KeyValueReader = new KeyValueReader {
    def get(table: TableRole, key: PrimaryKey) = r.get(table, key)
    def query(table: TableRole, q: KeyQuery) = r.query(table, q)
  }

  /** SE-INT-02: the refusal of a service-state table on the runtime read path. */
  def serviceStateRefusal(table: TableRole): Option[StoreError] =
    Option.when(table != null && table.serviceState)(
      StoreError.Refused(
        s"${table.tag}: a service-state table is not readable through the runtime stores"
      )
    )

  /**
   * SE-INT-02 (CR-INT-01-3): the runtime read facade of [[RuntimeStores]]: [[readOnly]], plus
   * `Refused`, before anything reaches `r`, for every service-state table
   * ([[TableRole.serviceState]]: QueryOwnership, QueryJob, IdempotencyKey) on `get` and `query`. It
   * is no [[KeyValueBatchReader]], so there is no batch path at all. Production may therefore pass
   * one DynamoDB adapter configured with every table name: the rag workflow still cannot read
   * another client's owner, job or idempotency row.
   */
  def runtimeReadOnly(r: KeyValueReader): KeyValueReader = new KeyValueReader {
    def get(table: TableRole, key: PrimaryKey) =
      serviceStateRefusal(table).toLeft(()).flatMap(_ => r.get(table, key))
    def query(table: TableRole, q: KeyQuery) =
      serviceStateRefusal(table).toLeft(()).flatMap(_ => r.query(table, q))
  }
}
