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
import scholarchain.storage.port.{KeyQuery, KeyValueStore, SortKeyCondition}

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
 *     `limit`; there is no scan.
 *
 * Not thread-safe: tests only.
 *
 * @param maxQueryLimit
 *   the largest `limit` a query accepts
 */
final class InMemoryKeyValueStore(maxQueryLimit: Int, specs: Vector[TableSpec] = TableSpecs.all)
    extends KeyValueStore {
  private val tables: Map[TableRole, (TableSpec, mutable.Map[PrimaryKey, Item])] =
    specs.map(s => s.role -> (s, mutable.Map.empty[PrimaryKey, Item])).toMap
  private val faults = Faults()

  /** The next writes fail with these errors, in order, before touching the store. */
  def failNextWrites(errors: StoreError*): Unit = faults.enqueue(errors*)

  def itemCount(table: TableRole): Int = tables.get(table).map(_._2.size).getOrElse(0)

  private def tableOf(
      role: TableRole
  ): Either[StoreError, (TableSpec, mutable.Map[PrimaryKey, Item])] =
    Option(role)
      .flatMap(tables.get)
      .toRight(StoreError.Refused(s"table: unknown ${Option(role).map(_.tag).orNull}"))

  private def keyOf(spec: TableSpec, item: Item): Either[StoreError, PrimaryKey] = {
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

  private def sameRecord(a: Item, b: Item): Boolean =
    (a.get(Attrs.RecordSha256), b.get(Attrs.RecordSha256)) match {
      case (Some(x), Some(y)) => x == y
      case _ => a == b
    }

  def get(table: TableRole, key: PrimaryKey): Either[StoreError, Option[Item]] =
    tableOf(table).map(_._2.get(key))

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
            case Some(existing) if sameRecord(existing, item) => Right(PutOutcome.AlreadyPresent)
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

  def query(table: TableRole, q: KeyQuery): Either[StoreError, Vector[Item]] =
    for {
      t <- tableOf(table)
      (spec, rows) = t
      _ <-
        if (q != null && q.limit >= 1 && q.limit <= maxQueryLimit) Right(())
        else Left(StoreError.Refused(s"limit: outside 1..$maxQueryLimit"))
      attrs <- spec
        .keyAttributes(q.index)
        .toRight(StoreError.Refused(s"index: unknown ${q.index.orNull} on ${spec.role.tag}"))
      _ <-
        if (q.partition != null && q.partition.nonEmpty) Right(())
        else Left(StoreError.Refused("partition: missing"))
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
