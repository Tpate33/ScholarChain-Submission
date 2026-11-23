package scholarchain.storage.fake

import scala.collection.mutable

import scholarchain.core.Canonical
import scholarchain.storage.model.{
  Attrs,
  AttrValue,
  Item,
  PrimaryKey,
  PutOutcome,
  StoreError,
  TableRole,
  TableSpec,
  TableSpecs
}
import scholarchain.storage.port.{
  DeleteOutcome,
  KeyQuery,
  KeyValueDeleter,
  KeyValueRules,
  ServiceStateKeyValues,
  SortKeyCondition
}

/**
 * In-memory DynamoDB with the adapter's conditional-write semantics (SE-P7-01):
 *
 *   - every item needs its table's `pk` and `sk` as type S; GSI key attributes, when present, must
 *     be type S and come in pairs (sparse indexes);
 *   - `putIfAbsent`: absent is `Written`; present with the same `recordSha256` (or, for items
 *     without one, an equal item) is `AlreadyPresent`; anything else is `Conflict`;
 *   - `putIfVersion`: the stored `version` must equal `expectedVersion` and the new item's must be
 *     `expectedVersion + 1`, else `StaleVersion` (or `Refused` for a malformed new version);
 *   - `query` serves the table key or a declared GSI, sort keys in UTF-8 byte order, bounded by
 *     `limit`; there is no scan;
 *   - SE-INT-01 `getMany`: the items found among the (deduplicated) keys;
 *   - SE-INT-01 `deleteIfRecord`: only on [[KeyValueDeleter.DeletableTables]]; deletes only an item
 *     whose `recordSha256` equals the given hash (`Deleted`), `Absent` when no item, `Conflict`
 *     when another record is stored.
 *
 * Key and query checks are the shared [[KeyValueRules]], so the DynamoDB adapter refuses exactly
 * the same requests. Not thread-safe: tests and the serialized local runtime only.
 *
 * @param maxQueryLimit
 *   the largest `limit` a query accepts
 */
final class InMemoryKeyValueStore(maxQueryLimit: Int, specs: Vector[TableSpec] = TableSpecs.all)
    extends ServiceStateKeyValues {
  private val tables: Map[TableRole, (TableSpec, mutable.Map[PrimaryKey, Item])] =
    specs.map(s => s.role -> (s, mutable.Map.empty[PrimaryKey, Item])).toMap
  private val faults = Faults()

  /** The next writes (puts and deletes) fail with these errors, in order, before touching it. */
  def failNextWrites(errors: StoreError*): Unit = faults.enqueue(errors*)

  def itemCount(table: TableRole): Int = tables.get(table).map(_._2.size).getOrElse(0)

  private def tableOf(
      role: TableRole
  ): Either[StoreError, (TableSpec, mutable.Map[PrimaryKey, Item])] =
    Option(role)
      .flatMap(tables.get)
      .toRight(StoreError.Refused(s"table: unknown ${Option(role).map(_.tag).orNull}"))

  private def keyOf(spec: TableSpec, item: Item): Either[StoreError, PrimaryKey] =
    KeyValueRules.keyOf(spec, item)

  def get(table: TableRole, key: PrimaryKey): Either[StoreError, Option[Item]] =
    tableOf(table).map(_._2.get(key))

  def getMany(
      table: TableRole,
      keys: Vector[PrimaryKey]
  ): Either[StoreError, Map[PrimaryKey, Item]] =
    for {
      t <- tableOf(table)
      ks <- KeyValueRules.checkKeys(keys)
    } yield ks.flatMap(k => t._2.get(k).map(k -> _)).toMap

  def putIfAbsent(table: TableRole, item: Item): Either[StoreError, PutOutcome] =
    faults.next() match {
      case Some(e) => Left(e)
      case None =>
        for {
          t <- tableOf(table)
          (spec, rows) = t
          k <- keyOf(spec, Option(item).getOrElse(Map.empty))
          out <- rows.get(k) match {
            case None =>
              rows(k) = item
              Right(PutOutcome.Written)
            case Some(existing) if KeyValueRules.sameRecord(existing, item) =>
              Right(PutOutcome.AlreadyPresent)
            case Some(_) =>
              Left(
                StoreError.Conflict(s"${spec.role.tag} ${k.pk} ${k.sk}: holds a different record")
              )
          }
        } yield out
    }

  def putIfVersion(table: TableRole, item: Item, expectedVersion: Long): Either[StoreError, Unit] =
    faults.next() match {
      case Some(e) => Left(e)
      case None =>
        for {
          t <- tableOf(table)
          (spec, rows) = t
          k <- keyOf(spec, Option(item).getOrElse(Map.empty))
          next <- AttrValue
            .long(item, Attrs.Version)
            .left
            .map(_ => StoreError.Refused("version: missing or not an integer"))
          _ <-
            if (next == expectedVersion + 1) Right(())
            else
              Left(StoreError.Refused(s"version: new version $next is not ${expectedVersion + 1}"))
          existing <- rows
            .get(k)
            .toRight(StoreError.StaleVersion(s"${spec.role.tag} ${k.pk} ${k.sk}: absent"))
          stored <- AttrValue
            .long(existing, Attrs.Version)
            .left
            .map(_ => StoreError.StaleVersion("stored item has no version"))
          _ <-
            if (stored == expectedVersion) Right(())
            else
              Left(
                StoreError.StaleVersion(
                  s"${spec.role.tag} ${k.pk} ${k.sk}: stored version $stored, expected $expectedVersion"
                )
              )
        } yield rows(k) = item
    }

  def deleteIfRecord(
      table: TableRole,
      key: PrimaryKey,
      recordSha256: String
  ): Either[StoreError, DeleteOutcome] =
    KeyValueDeleter.refusal(table, key, recordSha256) match {
      case Some(e) => Left(e)
      case None =>
        faults.next() match {
          case Some(e) => Left(e)
          case None =>
            tableOf(table).flatMap { case (spec, rows) =>
              rows.get(key) match {
                case None => Right(DeleteOutcome.Absent)
                case Some(i) if i.get(Attrs.RecordSha256).contains(AttrValue.S(recordSha256)) =>
                  rows.remove(key)
                  Right(DeleteOutcome.Deleted)
                case Some(_) =>
                  Left(
                    StoreError.Conflict(
                      s"${spec.role.tag} ${key.pk} ${key.sk}: holds a different record"
                    )
                  )
              }
            }
        }
    }

  def query(table: TableRole, q: KeyQuery): Either[StoreError, Vector[Item]] =
    for {
      t <- tableOf(table)
      (spec, rows) = t
      attrs <- KeyValueRules.checkQuery(spec, q, maxQueryLimit)
    } yield {
      val (pkAttr, skAttr) = attrs
      def str(i: Item, n: String) = i.get(n).collect { case AttrValue.S(v) => v }
      val matching = rows.values.toVector.flatMap { i =>
        for {
          p <- str(i, pkAttr)
          s <- str(i, skAttr)
          if p == q.partition && SortMatch(q.sort, s)
        } yield (s, i)
      }
      val sorted = matching.sortWith((a, b) => Canonical.compareUtf8(a._1, b._1) < 0)
      (if (q.ascending) sorted else sorted.reverse).take(q.limit).map(_._2)
    }

  private object SortMatch {
    def apply(c: SortKeyCondition, s: String): Boolean = c match {
      case SortKeyCondition.All => true
      case SortKeyCondition.Equals(v) => s == v
      case SortKeyCondition.BeginsWith(p) => s.startsWith(p)
      case SortKeyCondition.Between(lo, hi) =>
        Canonical.compareUtf8(lo, s) <= 0 && Canonical.compareUtf8(s, hi) <= 0
    }
  }
}
