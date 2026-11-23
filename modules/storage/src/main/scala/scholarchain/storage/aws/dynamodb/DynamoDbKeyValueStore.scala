package scholarchain.storage.aws.dynamodb

import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import software.amazon.awssdk.services.dynamodb.model.{
  AttributeValue,
  BatchGetItemRequest,
  ConditionalCheckFailedException,
  DeleteItemRequest,
  GetItemRequest,
  KeysAndAttributes,
  PutItemRequest,
  QueryRequest,
  ReturnValuesOnConditionCheckFailure
}

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
  KeyValueBatchReader,
  KeyValueDeleter,
  KeyValueRules,
  ServiceStateKeyValues,
  Sleeper,
  SortKeyCondition
}

/**
 * SE-INT-01: the DynamoDB adapter of the key-value ports ([[KeyValueReader]], [[KeyValueWriter]],
 * [[KeyValueBatchReader]], [[KeyValueDeleter]]), over the narrow [[DynamoDbCalls]] seam. Same
 * semantics as the in-memory store (shared [[KeyValueRules]]; the parity suites run one operation
 * script against both):
 *
 *   - `get`: GetItem, strongly consistent;
 *   - `putIfAbsent`: PutItem with `attribute_not_exists(<pk>)` and ALL_OLD on a failed condition
 *     (SDK 2.21 or later as remembered; UNVERIFIED): the old item with the same `recordSha256` is
 *     `AlreadyPresent`, another is `Conflict`; when the failure carries no item, one consistent
 *     GetItem decides (no item then is `Transient`: the caller's retry is safe);
 *   - `putIfVersion`: PutItem with `<version> = :expected`; a failed condition (absent item or
 *     another version) is `StaleVersion`;
 *   - `deleteIfRecord`: DeleteItem with `<recordSha256> = :hash` and ALL_OLD on the deletable
 *     tables only; when a failed condition carries no item, one consistent GetItem decides
 *     (SE-INT-02, CR-INT-01-6: no item `Absent`, another record `Conflict`, the same record now
 *     `Transient`);
 *   - `query`: Query on the table key (consistent) or a GSI (eventually consistent: GSIs do not
 *     support consistent reads as remembered; UNVERIFIED), pages followed up to `maxQueryPages`;
 *     hitting the page bound before the limit is `Permanent`, never a partial result;
 *   - `getMany`: BatchGetItem, consistent, at most [[KeyValueBatchReader.MaxKeysPerCall]] keys per
 *     call, unprocessed keys retried with capped exponential backoff up to `batchGetMaxAttempts`
 *     calls, then `Transient` for the whole call.
 *
 * Thread-safe: it holds only immutable values and the seam; no lock and no mutable state. Errors
 * map as [[DynamoDbErrors]] states.
 */
final class DynamoDbKeyValueStore private (
    calls: DynamoDbCalls,
    names: DynamoDbTableNames,
    settings: DynamoDbAdapterSettings,
    sleeper: Sleeper,
    specs: Map[TableRole, TableSpec]
) extends ServiceStateKeyValues {

  private type SdkMap = java.util.Map[String, AttributeValue]

  private def target(role: TableRole): Either[StoreError, (TableSpec, String)] =
    for {
      spec <- Option(role)
        .flatMap(specs.get)
        .toRight(StoreError.Refused(s"table: unknown ${Option(role).map(_.tag).orNull}"))
      name <- names.of(role)
    } yield (spec, name)

  private def keyMap(spec: TableSpec, k: PrimaryKey): SdkMap =
    Map(
      spec.partitionKey -> AttributeValue.fromS(k.pk),
      spec.sortKey -> AttributeValue.fromS(k.sk)
    ).asJava

  /** Runs one SDK call; any failure is returned, an interrupt is preserved for the classifier. */
  private def attempt[A](f: => A): Either[Throwable, A] =
    try Right(f)
    catch {
      case e: InterruptedException => Left(e)
      case NonFatal(e) => Left(e)
    }

  private def fail(op: String, role: TableRole, e: Throwable): StoreError =
    DynamoDbErrors.classify(op, role.tag, e)

  private def oldItem(c: ConditionalCheckFailedException): Option[SdkMap] =
    if (c.hasItem && !c.item().isEmpty) Some(c.item()) else None

  // ---------------------------------------------------------------------------------- reads

  def get(table: TableRole, key: PrimaryKey): Either[StoreError, Option[Item]] =
    target(table).flatMap { case (spec, name) =>
      if (key == null || key.pk == null || key.pk.isEmpty || key.sk == null || key.sk.isEmpty)
        Right(None) // no item can have an empty key (same answer as the in-memory store)
      else
        attempt(
          calls.getItem(
            GetItemRequest
              .builder()
              .tableName(name)
              .key(keyMap(spec, key))
              .consistentRead(java.lang.Boolean.TRUE)
              .build()
          )
        ) match {
          case Left(e) => Left(fail("GetItem", table, e))
          case Right(r) =>
            if (r.hasItem && !r.item().isEmpty) DynamoDbAttrs.fromSdkItem(r.item()).map(Some(_))
            else Right(None)
        }
    }

  /** The key condition, or None when no item can match (empty Equals, empty or inverted range). */
  private def condition(
      sort: SortKeyCondition
  ): Option[(String, Map[String, AttributeValue])] = {
    val base = "#p = :p"
    sort match {
      case SortKeyCondition.All => Some(base -> Map.empty)
      case SortKeyCondition.Equals(v) =>
        Option.when(v.nonEmpty)(s"$base AND #s = :s" -> Map(":s" -> AttributeValue.fromS(v)))
      case SortKeyCondition.BeginsWith(p) =>
        if (p.isEmpty) Some(base -> Map.empty)
        else Some(s"$base AND begins_with(#s, :s)" -> Map(":s" -> AttributeValue.fromS(p)))
      case SortKeyCondition.Between(lo, hi) =>
        if (hi.isEmpty || Canonical.compareUtf8(lo, hi) > 0) None
        else if (lo.isEmpty) Some(s"$base AND #s <= :hi" -> Map(":hi" -> AttributeValue.fromS(hi)))
        else
          Some(
            s"$base AND #s BETWEEN :lo AND :hi" ->
              Map(":lo" -> AttributeValue.fromS(lo), ":hi" -> AttributeValue.fromS(hi))
          )
    }
  }

  def query(table: TableRole, q: KeyQuery): Either[StoreError, Vector[Item]] =
    for {
      t <- target(table)
      (spec, name) = t
      attrs <- KeyValueRules.checkQuery(spec, q, settings.maxQueryLimit)
      out <- condition(q.sort) match {
        case None => Right(Vector.empty)
        case Some((expr, sortValues)) =>
          val (pkAttr, skAttr) = attrs
          val namesMap =
            (Map("#p" -> pkAttr) ++ (if (expr.contains("#s")) Map("#s" -> skAttr) else Map.empty))
          val values = Map(":p" -> AttributeValue.fromS(q.partition)) ++ sortValues
          pages(table, name, q, expr, namesMap, values, None, 1, Vector.empty)
      }
    } yield out

  @tailrec
  private def pages(
      table: TableRole,
      name: String,
      q: KeyQuery,
      expr: String,
      names: Map[String, String],
      values: Map[String, AttributeValue],
      start: Option[SdkMap],
      page: Int,
      acc: Vector[Item]
  ): Either[StoreError, Vector[Item]] = {
    val b = QueryRequest
      .builder()
      .tableName(name)
      .keyConditionExpression(expr)
      .expressionAttributeNames(names.asJava)
      .expressionAttributeValues(values.asJava)
      .limit(Integer.valueOf(q.limit - acc.size))
      .scanIndexForward(java.lang.Boolean.valueOf(q.ascending))
      .consistentRead(java.lang.Boolean.valueOf(q.index.isEmpty))
    q.index.foreach(b.indexName)
    start.foreach(b.exclusiveStartKey)
    attempt(calls.query(b.build())) match {
      case Left(e) => Left(fail("Query", table, e))
      case Right(r) =>
        val items = r.items().asScala.toVector.map(DynamoDbAttrs.fromSdkItem)
        items.collectFirst { case Left(e) => e } match {
          case Some(e) => Left(e)
          case None =>
            val all = acc ++ items.collect { case Right(i) => i }
            val next =
              if (r.hasLastEvaluatedKey && !r.lastEvaluatedKey().isEmpty)
                Some(r.lastEvaluatedKey())
              else None
            if (all.size >= q.limit || next.isEmpty) Right(all.take(q.limit))
            else if (page >= settings.maxQueryPages)
              Left(
                StoreError.Permanent(
                  s"DynamoDB Query on ${table.tag}: ${settings.maxQueryPages} page(s) read before " +
                    s"the limit ${q.limit} or the end of the partition; no partial result"
                )
              )
            else pages(table, name, q, expr, names, values, next, page + 1, all)
        }
    }
  }

  def getMany(
      table: TableRole,
      keys: Vector[PrimaryKey]
  ): Either[StoreError, Map[PrimaryKey, Item]] =
    for {
      t <- target(table)
      (spec, name) = t
      ks <- KeyValueRules.checkKeys(keys)
      out <- ks
        .grouped(KeyValueBatchReader.MaxKeysPerCall)
        .foldLeft[Either[StoreError, Map[PrimaryKey, Item]]](Right(Map.empty)) { (acc, chunk) =>
          acc.flatMap(m => chunkRead(table, spec, name, chunk.toSet, chunk, 1).map(m ++ _))
        }
    } yield out

  private def keyOfStored(spec: TableSpec, item: Item): Option[PrimaryKey] =
    for {
      p <- item.get(spec.partitionKey).collect { case AttrValue.S(v) => v }
      s <- item.get(spec.sortKey).collect { case AttrValue.S(v) => v }
    } yield PrimaryKey(p, s)

  private def backoff(retry: Int): Long = {
    val factor = if (retry - 1 >= 30) Long.MaxValue else 1L << (retry - 1)
    val raw =
      if (settings.batchGetBackoffBaseMillis == 0) 0L
      else if (factor > Long.MaxValue / settings.batchGetBackoffBaseMillis) Long.MaxValue
      else settings.batchGetBackoffBaseMillis * factor
    math.min(raw, settings.batchGetBackoffMaxMillis)
  }

  @tailrec
  private def chunkRead(
      table: TableRole,
      spec: TableSpec,
      name: String,
      requested: Set[PrimaryKey],
      pending: Vector[PrimaryKey],
      call: Int,
      acc: Map[PrimaryKey, Item] = Map.empty
  ): Either[StoreError, Map[PrimaryKey, Item]] = {
    val request = BatchGetItemRequest
      .builder()
      .requestItems(
        Map(
          name -> KeysAndAttributes
            .builder()
            .keys(pending.map(keyMap(spec, _)).asJava)
            .consistentRead(java.lang.Boolean.TRUE)
            .build()
        ).asJava
      )
      .build()
    attempt(calls.batchGetItem(request)) match {
      case Left(e) => Left(fail("BatchGetItem", table, e))
      case Right(r) =>
        val found = Option(r.responses().get(name)).map(_.asScala.toVector).getOrElse(Vector.empty)
        val decoded = found.map(DynamoDbAttrs.fromSdkItem(_).flatMap { i =>
          keyOfStored(spec, i).filter(requested) match {
            case Some(k) => Right(k -> i)
            case None =>
              Left(StoreError.Corrupt(s"DynamoDB BatchGetItem on ${table.tag}: unrequested item"))
          }
        })
        decoded.collectFirst { case Left(e) => e } match {
          case Some(e) => Left(e)
          case None =>
            val got = acc ++ decoded.collect { case Right(kv) => kv }
            val unprocessedRaw = Option(r.unprocessedKeys().get(name))
              .map(_.keys().asScala.toVector)
              .getOrElse(Vector.empty)
            val unprocessed = unprocessedRaw.map(k =>
              DynamoDbAttrs.fromSdkItem(k).toOption.flatMap(keyOfStored(spec, _))
            )
            if (unprocessed.isEmpty) Right(got)
            else if (unprocessed.exists(_.forall(k => !pending.contains(k))))
              Left(
                StoreError.Corrupt(s"DynamoDB BatchGetItem on ${table.tag}: unrequested key")
              )
            else if (call >= settings.batchGetMaxAttempts)
              Left(
                StoreError.Transient(
                  s"DynamoDB BatchGetItem on ${table.tag}: ${unprocessed.size} key(s) still " +
                    s"unprocessed after $call call(s); no partial result"
                )
              )
            else {
              val slept =
                try { sleeper.sleep(backoff(call)); true }
                catch {
                  case _: InterruptedException =>
                    Thread.currentThread().interrupt()
                    false
                }
              if (!slept)
                Left(
                  StoreError.Permanent(
                    s"DynamoDB BatchGetItem on ${table.tag}: interrupted during backoff"
                  )
                )
              else chunkRead(table, spec, name, requested, unprocessed.flatten, call + 1, got)
            }
        }
    }
  }

  // ---------------------------------------------------------------------------------- writes

  def putIfAbsent(table: TableRole, item: Item): Either[StoreError, PutOutcome] =
    for {
      t <- target(table)
      (spec, name) = t
      it = Option(item).getOrElse(Map.empty[String, AttrValue])
      k <- KeyValueRules.keyOf(spec, it)
      out <- attempt(
        calls.putItem(
          PutItemRequest
            .builder()
            .tableName(name)
            .item(DynamoDbAttrs.toSdkItem(it))
            .conditionExpression("attribute_not_exists(#pk)")
            .expressionAttributeNames(Map("#pk" -> spec.partitionKey).asJava)
            .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD)
            .build()
        )
      ) match {
        case Right(_) => Right(PutOutcome.Written)
        case Left(c: ConditionalCheckFailedException) =>
          val stored = oldItem(c) match {
            case Some(old) => DynamoDbAttrs.fromSdkItem(old).map(Some(_))
            case None => get(table, k)
          }
          stored.flatMap {
            case Some(existing) if KeyValueRules.sameRecord(existing, it) =>
              Right(PutOutcome.AlreadyPresent)
            case Some(_) =>
              Left(StoreError.Conflict(s"${table.tag} ${k.pk} ${k.sk}: holds a different record"))
            case None =>
              Left(
                StoreError.Transient(
                  s"${table.tag} ${k.pk} ${k.sk}: condition failed but no item is stored now"
                )
              )
          }
        case Left(e) => Left(fail("PutItem", table, e))
      }
    } yield out

  def putIfVersion(table: TableRole, item: Item, expectedVersion: Long): Either[StoreError, Unit] =
    for {
      t <- target(table)
      (spec, name) = t
      it = Option(item).getOrElse(Map.empty[String, AttrValue])
      k <- KeyValueRules.keyOf(spec, it)
      next <- AttrValue
        .long(it, Attrs.Version)
        .left
        .map(_ => StoreError.Refused("version: missing or not an integer"))
      _ <-
        if (next == expectedVersion + 1) Right(())
        else Left(StoreError.Refused(s"version: new version $next is not ${expectedVersion + 1}"))
      out <- attempt(
        calls.putItem(
          PutItemRequest
            .builder()
            .tableName(name)
            .item(DynamoDbAttrs.toSdkItem(it))
            .conditionExpression("#v = :expected")
            .expressionAttributeNames(Map("#v" -> Attrs.Version).asJava)
            .expressionAttributeValues(
              Map(":expected" -> AttributeValue.fromN(expectedVersion.toString)).asJava
            )
            .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.NONE)
            .build()
        )
      ) match {
        case Right(_) => Right(())
        case Left(_: ConditionalCheckFailedException) =>
          Left(
            StoreError.StaleVersion(
              s"${table.tag} ${k.pk} ${k.sk}: absent, or stored version is not $expectedVersion"
            )
          )
        case Left(e) => Left(fail("PutItem", table, e))
      }
    } yield out

  def deleteIfRecord(
      table: TableRole,
      key: PrimaryKey,
      recordSha256: String
  ): Either[StoreError, DeleteOutcome] =
    KeyValueDeleter.refusal(table, key, recordSha256) match {
      case Some(e) => Left(e)
      case None =>
        target(table).flatMap { case (spec, name) =>
          attempt(
            calls.deleteItem(
              DeleteItemRequest
                .builder()
                .tableName(name)
                .key(keyMap(spec, key))
                .conditionExpression("#rs = :rs")
                .expressionAttributeNames(Map("#rs" -> Attrs.RecordSha256).asJava)
                .expressionAttributeValues(Map(":rs" -> AttributeValue.fromS(recordSha256)).asJava)
                .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD)
                .build()
            )
          ) match {
            case Right(_) => Right(DeleteOutcome.Deleted)
            case Left(c: ConditionalCheckFailedException) =>
              // SE-INT-02 (CR-INT-01-6): a failure without the old item is decided by one
              // consistent GetItem, as in putIfAbsent: no item is Absent, another record Conflict.
              val stored = oldItem(c) match {
                case Some(old) => DynamoDbAttrs.fromSdkItem(old).map(Some(_))
                case None => get(table, key)
              }
              stored.flatMap {
                case None => Right(DeleteOutcome.Absent)
                case Some(i) if i.get(Attrs.RecordSha256).contains(AttrValue.S(recordSha256)) =>
                  Left(
                    StoreError.Transient(
                      s"${table.tag} ${key.pk} ${key.sk}: condition failed but the record is " +
                        "stored now; retry"
                    )
                  )
                case Some(_) =>
                  Left(
                    StoreError.Conflict(
                      s"${table.tag} ${key.pk} ${key.sk}: holds a different record"
                    )
                  )
              }
            case Left(e) => Left(fail("DeleteItem", table, e))
          }
        }
    }
}

object DynamoDbKeyValueStore {

  /**
   * @param calls
   *   the five-operation seam ([[DynamoDbCalls.of]] over the SDK client, or a test stub)
   * @param sleeper
   *   BatchGetItem backoff (the system sleeper in production, a recording one in tests)
   */
  def apply(
      calls: DynamoDbCalls,
      names: DynamoDbTableNames,
      settings: DynamoDbAdapterSettings,
      sleeper: Sleeper,
      specs: Vector[TableSpec] = TableSpecs.all
  ): DynamoDbKeyValueStore = {
    require(
      calls != null && names != null && settings != null && sleeper != null && specs != null,
      "calls, names, settings, sleeper and specs are required"
    )
    new DynamoDbKeyValueStore(calls, names, settings, sleeper, specs.map(s => s.role -> s).toMap)
  }

  /** The production sleeper: `Thread.sleep` (an interrupt ends the call, see `getMany`). */
  val ThreadSleeper: Sleeper = (millis: Long) => if (millis > 0) Thread.sleep(millis)
}
