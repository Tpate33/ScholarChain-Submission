package scholarchain.storage.aws.dynamodb

import scala.jdk.CollectionConverters.*

import software.amazon.awssdk.services.dynamodb.model.{
  AttributeValue,
  BatchGetItemRequest,
  DeleteItemRequest,
  GetItemRequest,
  PutItemRequest,
  QueryRequest,
  ReturnValuesOnConditionCheckFailure
}

import scholarchain.storage.fake.{InMemoryKeyValueStore, ManualClock, RecordingSleeper}
import scholarchain.storage.model.{AttrValue, Item, PrimaryKey, PutOutcome, StoreError, TableRole}
import scholarchain.storage.port.{DeleteOutcome, KeyQuery, SortKeyCondition}

/**
 * SE-INT-01 item 1-2: the DynamoDB adapter's conditional writes, reads, queries and batch reads.
 */
class DynamoDbAdapterSuite extends munit.FunSuite {
  import DynamoDbAdapterSuite.*

  private val Job = TableRole.QueryJob

  test("putIfAbsent: Written, AlreadyPresent for the same recordSha256, Conflict otherwise") {
    val stub = Ddb.stub()
    val kv = Ddb.adapter(stub)
    assertEquals(kv.putIfAbsent(Job, row("job#1", h('a'))), Right(PutOutcome.Written))
    assertEquals(kv.putIfAbsent(Job, row("job#1", h('a'))), Right(PutOutcome.AlreadyPresent))
    assert(isConflict(kv.putIfAbsent(Job, row("job#1", h('b')))))
    assertEquals(stub.itemCount(Job), 1)
    val put = stub.recorded.collect { case r: PutItemRequest => r }.head
    assertEquals(put.conditionExpression(), "attribute_not_exists(#pk)")
    assertEquals(put.expressionAttributeNames().asScala.toMap, Map("#pk" -> "pk"))
    assertEquals(
      put.returnValuesOnConditionCheckFailure(),
      ReturnValuesOnConditionCheckFailure.ALL_OLD
    )
    assertEquals(put.tableName(), "sc-test-QueryJob")
  }

  test("putIfAbsent without ALL_OLD on the failure falls back to one consistent GetItem") {
    val stub = Ddb.stub()
    stub.omitOldItem = true
    val kv = Ddb.adapter(stub)
    kv.putIfAbsent(Job, row("job#1", h('a')))
    assertEquals(kv.putIfAbsent(Job, row("job#1", h('a'))), Right(PutOutcome.AlreadyPresent))
    assert(isConflict(kv.putIfAbsent(Job, row("job#1", h('b')))))
    val gets = stub.recorded.collect { case r: GetItemRequest => r }
    assertEquals(gets.size, 2)
    assert(gets.forall(_.consistentRead() == java.lang.Boolean.TRUE))
  }

  test("putIfVersion: replaces on the expected version; StaleVersion otherwise; Refused shapes") {
    val stub = Ddb.stub()
    val kv = Ddb.adapter(stub)
    assert(isStale(kv.putIfVersion(Job, row("job#1", h('a'), Some(2)), 1)), "absent item")
    kv.putIfAbsent(Job, row("job#1", h('a'), Some(1)))
    assertEquals(kv.putIfVersion(Job, row("job#1", h('b'), Some(2)), 1), Right(()))
    assert(isStale(kv.putIfVersion(Job, row("job#1", h('c'), Some(2)), 1)), "lost race")
    assertEquals(
      kv.putIfVersion(Job, row("job#1", h('c'), Some(4)), 2).left.map(_.ordinal),
      Left(StoreError.Refused("").ordinal),
      "the new version must be expected + 1"
    )
    assert(kv.putIfVersion(Job, row("job#1", h('c'), None), 2).isLeft, "version required")
    val versioned = stub.recorded.collect {
      case r: PutItemRequest if r.conditionExpression() == "#v = :expected" => r
    }
    assertEquals(versioned.size, 3, "refusals are never sent")
    assertEquals(versioned.head.expressionAttributeValues().get(":expected").n(), "1")
    assertEquals(versioned.head.expressionAttributeNames().get("#v"), "version")
  }

  test("deleteIfRecord: Deleted only for the exact record; Absent; Conflict keeps the row") {
    val stub = Ddb.stub()
    val kv = Ddb.adapter(stub)
    val key = PrimaryKey("job#1", "job")
    assertEquals(kv.deleteIfRecord(Job, key, h('a')), Right(DeleteOutcome.Absent))
    kv.putIfAbsent(Job, row("job#1", h('a')))
    assert(isConflict(kv.deleteIfRecord(Job, key, h('b'))))
    assertEquals(stub.itemCount(Job), 1)
    assertEquals(kv.deleteIfRecord(Job, key, h('a')), Right(DeleteOutcome.Deleted))
    assertEquals(stub.itemCount(Job), 0)
    val del = stub.recorded.collect { case r: DeleteItemRequest => r }.head
    assertEquals(del.conditionExpression(), "#rs = :rs")
    assertEquals(del.expressionAttributeNames().get("#rs"), "recordSha256")
  }

  test("deleteIfRecord is refused outside QueryJob and IdempotencyKey, before any request") {
    val stub = Ddb.stub()
    val kv = Ddb.adapter(stub)
    TableRole.values
      .filterNot(r => r == TableRole.QueryJob || r == TableRole.IdempotencyKey)
      .foreach { r =>
        assert(isRefused(kv.deleteIfRecord(r, PrimaryKey("a", "b"), h('a'))), r.tag)
      }
    assert(isRefused(kv.deleteIfRecord(Job, PrimaryKey("a", "b"), "not-a-hash")))
    assert(isRefused(kv.deleteIfRecord(Job, PrimaryKey("", "b"), h('a'))))
    assertEquals(stub.recorded.size, 0)
  }

  test("get is strongly consistent; a table without a configured name is Refused") {
    val stub = Ddb.stub()
    val kv = Ddb.adapter(stub)
    kv.putIfAbsent(Job, row("job#1", h('a')))
    assertEquals(kv.get(Job, PrimaryKey("job#1", "job")), Right(Some(row("job#1", h('a')))))
    assertEquals(kv.get(Job, PrimaryKey("job#2", "job")), Right(None))
    assertEquals(kv.get(Job, PrimaryKey("", "job")), Right(None), "empty key: no request")
    val partial = DynamoDbKeyValueStore(
      stub,
      DynamoDbTableNames.of(Map(TableRole.QueryOwnership -> "own-table")).toOption.get,
      Ddb.settings(),
      new RecordingSleeper(new ManualClock(0))
    )
    assert(isRefused(partial.get(Job, PrimaryKey("job#1", "job"))))
    assert(isRefused(partial.putIfAbsent(Job, row("job#1", h('a')))))
    assertEquals(stub.recorded.collect { case r: GetItemRequest => r }.size, 2)
  }

  private def evidenceRows(n: Int): Vector[Item] =
    (1 to n).toVector.map { i =>
      Map(
        "pk" -> AttrValue.S(f"bundle#$i%03d"),
        "sk" -> AttrValue.S("bundle"),
        "byQueryPk" -> AttrValue.S("query#q"),
        "byQuerySk" -> AttrValue.S(f"bundle#$i%03d"),
        "recordSha256" -> AttrValue.S(h('a'))
      )
    }

  test("query follows pages up to the bound and returns at most the limit, in sort order") {
    val stub = Ddb.stub()
    stub.pageSize = 3
    val kv = Ddb.adapter(stub)
    evidenceRows(8).foreach(i => kv.putIfAbsent(TableRole.EvidenceBundle, i))
    val q = KeyQuery(Some("byQuery"), "query#q", SortKeyCondition.All, 7)
    val got = kv.query(TableRole.EvidenceBundle, q).toOption.get
    assertEquals(got.map(_("pk")), evidenceRows(7).map(_("pk")))
    val qs = stub.recorded.collect { case r: QueryRequest => r }
    assertEquals(qs.size, 3, "three pages of at most 3")
    assertEquals(qs.map(_.limit().intValue), Vector(7, 4, 1), "limit is the remainder")
    assert(qs.forall(_.consistentRead() == java.lang.Boolean.FALSE), "GSI: eventually consistent")
    assertEquals(qs.head.indexName(), "byQuery")
    val down = kv.query(TableRole.EvidenceBundle, q.copy(limit = 2, ascending = false))
    assertEquals(down.map(_.map(_("pk"))), Right(evidenceRows(8).reverse.take(2).map(_("pk"))))
  }

  test("query: reaching the page bound before the limit is an error, never a partial result") {
    val stub = Ddb.stub()
    stub.pageSize = 2
    val kv = Ddb.adapter(stub, Ddb.settings(maxQueryPages = 2))
    evidenceRows(8).foreach(i => kv.putIfAbsent(TableRole.EvidenceBundle, i))
    val q = KeyQuery(Some("byQuery"), "query#q", SortKeyCondition.All, 5)
    val r = kv.query(TableRole.EvidenceBundle, q)
    assert(r.left.exists(_.isInstanceOf[StoreError.Permanent]), r.toString)
    assert(r.left.exists(_.message.contains("no partial result")))
    // The bound is not hit when the partition ends within it.
    assertEquals(kv.query(TableRole.EvidenceBundle, q.copy(limit = 4)).map(_.size), Right(4))
  }

  test("query: table-key queries are consistent; the shared rules refuse bad requests unsent") {
    val stub = Ddb.stub()
    val kv = Ddb.adapter(stub, Ddb.settings(maxQueryLimit = 5))
    kv.putIfAbsent(Job, row("job#1", h('a')))
    assertEquals(
      kv.query(Job, KeyQuery(None, "job#1", SortKeyCondition.Equals("job"), 5)).map(_.size),
      Right(1)
    )
    assert(stub.recorded.collect { case r: QueryRequest => r }.head.consistentRead())
    val before = stub.recorded.size
    assert(isRefused(kv.query(Job, KeyQuery(None, "job#1", SortKeyCondition.All, 6))))
    assert(isRefused(kv.query(Job, KeyQuery(None, "job#1", SortKeyCondition.All, 0))))
    assert(isRefused(kv.query(Job, KeyQuery(Some("nope"), "job#1", SortKeyCondition.All, 1))))
    assert(isRefused(kv.query(Job, KeyQuery(None, "", SortKeyCondition.All, 1))))
    assertEquals(stub.recorded.size, before)
  }

  test("getMany: chunks of at most 100 distinct keys, consistent, absent keys omitted") {
    val stub = Ddb.stub()
    val kv = Ddb.adapter(stub)
    (1 to 230).foreach(i => kv.putIfAbsent(Job, row(s"job#$i", h('a'))))
    val keys = (1 to 250).toVector.map(i => PrimaryKey(s"job#$i", "job"))
    val got = kv.getMany(Job, keys ++ keys.take(10))
    assertEquals(got.map(_.keySet), Right(keys.take(230).toSet))
    val calls = stub.recorded.collect { case r: BatchGetItemRequest => r }
    val sizes = calls.map(_.requestItems().get("sc-test-QueryJob").keys().size)
    assertEquals(sizes, Vector(100, 100, 50))
    assert(calls.forall(_.requestItems().get("sc-test-QueryJob").consistentRead()))
    assertEquals(kv.getMany(Job, Vector.empty), Right(Map.empty[PrimaryKey, Item]))
    assertEquals(stub.recorded.collect { case r: BatchGetItemRequest => r }.size, 3, "no call")
    assert(isRefused(kv.getMany(Job, Vector(PrimaryKey("", "job")))))
  }

  test("getMany: unprocessed keys are retried with capped backoff, then succeed") {
    val stub = Ddb.stub()
    val sleeper = new RecordingSleeper(new ManualClock(0))
    val kv = Ddb.adapter(stub, Ddb.settings(attempts = 4, base = 10, max = 25), sleeper)
    (1 to 5).foreach(i => kv.putIfAbsent(Job, row(s"job#$i", h('a'))))
    stub.unprocessed = (call, ks) => if (call <= 3) ks.take(1) else Vector.empty
    val keys = (1 to 5).toVector.map(i => PrimaryKey(s"job#$i", "job"))
    assertEquals(kv.getMany(Job, keys).map(_.size), Right(5))
    assertEquals(sleeper.sleeps, Vector(10L, 20L, 25L), "exponential, capped")
  }

  test("getMany: keys still unprocessed after the bound are Transient for the whole call") {
    val stub = Ddb.stub()
    val sleeper = new RecordingSleeper(new ManualClock(0))
    val kv = Ddb.adapter(stub, Ddb.settings(attempts = 3, base = 1, max = 100), sleeper)
    (1 to 3).foreach(i => kv.putIfAbsent(Job, row(s"job#$i", h('a'))))
    stub.unprocessed = (_, ks) => ks.take(1)
    val r = kv.getMany(Job, (1 to 3).toVector.map(i => PrimaryKey(s"job#$i", "job")))
    assert(r.left.exists(_.retryable), r.toString)
    assert(r.left.exists(_.message.contains("1 key(s) still unprocessed after 3 call(s)")))
    assertEquals(stub.recorded.collect { case r: BatchGetItemRequest => r }.size, 3)
    assertEquals(sleeper.sleeps, Vector(1L, 2L))
  }

  test("the in-memory store answers getMany and deleteIfRecord like the adapter") {
    val fake = new InMemoryKeyValueStore(50)
    fake.putIfAbsent(Job, row("job#1", h('a')))
    val keys = Vector(PrimaryKey("job#1", "job"), PrimaryKey("job#2", "job"))
    assertEquals(fake.getMany(Job, keys ++ keys).map(_.keySet), Right(Set(keys.head)))
    assert(isConflict(fake.deleteIfRecord(Job, keys.head, h('b'))))
    assertEquals(fake.deleteIfRecord(Job, keys.head, h('a')), Right(DeleteOutcome.Deleted))
    assertEquals(fake.deleteIfRecord(Job, keys.head, h('a')), Right(DeleteOutcome.Absent))
    assert(isRefused(fake.deleteIfRecord(TableRole.EvidenceBundle, keys.head, h('a'))))
  }

  test("attribute mapping round-trips every supported type; others are Corrupt") {
    val v = AttrValue.M(
      Map(
        "s" -> AttrValue.S("x"),
        "n" -> AttrValue.N("-12"),
        "b" -> AttrValue.Bool(true),
        "z" -> AttrValue.Null,
        "l" -> AttrValue.L(Vector(AttrValue.S("a"), AttrValue.L(Vector.empty)))
      )
    )
    assertEquals(DynamoDbAttrs.fromSdk(DynamoDbAttrs.toSdk(v)), Right(v))
    val bytes = AttributeValue.fromB(software.amazon.awssdk.core.SdkBytes.fromUtf8String("x"))
    assert(DynamoDbAttrs.fromSdk(bytes).isLeft)
    assert(
      DynamoDbAttrs
        .fromSdkItem(Map("a" -> bytes).asJava)
        .left
        .exists(_.message == {
          "attribute a: unsupported attribute type B"
        })
    )
    val deep = (1 to 40).foldLeft(AttributeValue.fromS("x"))((a, _) =>
      AttributeValue.fromL(java.util.List.of(a))
    )
    assert(DynamoDbAttrs.fromSdk(deep).isLeft, "nesting bound")
  }
}

object DynamoDbAdapterSuite {
  def h(c: Char): String = c.toString * 64

  def row(pk: String, sha: String, version: Option[Long] = None): Item =
    Map[String, AttrValue](
      "pk" -> AttrValue.S(pk),
      "sk" -> AttrValue.S("job"),
      "recordSha256" -> AttrValue.S(sha),
      "body" -> AttrValue.S("{}")
    ) ++ version.map(v => "version" -> AttrValue.n(v))

  def isConflict(r: Either[StoreError, ?]): Boolean =
    r.left.exists(_.isInstanceOf[StoreError.Conflict])
  def isStale(r: Either[StoreError, ?]): Boolean =
    r.left.exists(_.isInstanceOf[StoreError.StaleVersion])
  def isRefused(r: Either[StoreError, ?]): Boolean =
    r.left.exists(_.isInstanceOf[StoreError.Refused])
}
