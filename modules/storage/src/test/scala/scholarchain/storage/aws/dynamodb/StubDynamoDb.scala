package scholarchain.storage.aws.dynamodb

import scala.collection.mutable
import scala.jdk.CollectionConverters.*

import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.services.dynamodb.model.*

import scholarchain.core.Canonical
import scholarchain.storage.fake.RecordingSleeper
import scholarchain.storage.fake.ManualClock
import scholarchain.storage.model.{TableRole, TableSpecs}

/**
 * A test DynamoDB server behind the [[DynamoDbCalls]] seam: it interprets exactly the condition and
 * key-condition expressions the adapter sends (anything else fails the test loudly), stores items
 * per table, pages queries, and can inject failures (before a request, or after an applied write),
 * SDK-style resends of an applied write, unprocessed batch keys and missing ALL_OLD items. It has
 * no item-size limit (the stores bound encoded items themselves). Each request is atomic (one
 * monitor per stub, as a server would be); the adapter under test takes no lock of its own.
 */
final class StubDynamoDb(val names: Map[TableRole, String]) extends DynamoDbCalls {
  private type SdkMap = java.util.Map[String, AttributeValue]

  private val specs = TableSpecs.all.map(s => names(s.role) -> s).toMap
  private val rows = mutable.Map.empty[String, mutable.LinkedHashMap[(String, String), SdkMap]]
  private val faults = mutable.Queue.empty[Throwable]
  private val requests = mutable.ArrayBuffer.empty[AnyRef]
  private var batchCalls = 0

  /** Items per Query page (the 1 MB page end, emulated by count). */
  @volatile var pageSize: Int = Int.MaxValue

  /** Given the batch call number (1-based) and its keys, the keys to report unprocessed. */
  @volatile var unprocessed: (Int, Vector[SdkMap]) => Vector[SdkMap] = (_, _) => Vector.empty

  /** When true, a failed condition never carries the old item (pre-ALL_OLD behaviour). */
  @volatile var omitOldItem: Boolean = false

  def failNext(errors: Throwable*): Unit = synchronized(faults.enqueueAll(errors))

  private val afterFaults = mutable.Queue.empty[Throwable]
  private var sdkRetries = 0

  /**
   * SE-INT-02 (CR-INT-01-1): the next writes (PutItem, DeleteItem) are APPLIED, then throw these
   * errors in order: the response is lost after the write took effect (a timeout, a 500 or a
   * connection reset after the server applied it).
   */
  def failAfterApplyNext(errors: Throwable*): Unit = synchronized(afterFaults.enqueueAll(errors))

  /**
   * SE-INT-02: the next `n` writes are applied and then sent AGAIN, unchanged, as the SDK's own
   * retry would after a lost response; the caller sees the second outcome (for a conditional write,
   * normally a failed condition against its own first write).
   */
  def retryAfterApplyNext(n: Int): Unit = synchronized(sdkRetries += n)

  private def afterWrite(r: AnyRef)(again: => AnyRef): Option[AnyRef] =
    if (afterFaults.nonEmpty) throw afterFaults.dequeue()
    else if (sdkRetries > 0) {
      sdkRetries -= 1
      requests += r
      Some(again)
    } else None

  def recorded: Vector[AnyRef] = synchronized(requests.toVector)

  def itemCount(role: TableRole): Int = synchronized(rows.get(names(role)).map(_.size).getOrElse(0))

  private def table(name: String) = {
    if (!specs.contains(name))
      throw ResourceNotFoundException.builder().statusCode(400).message("no table").build()
    rows.getOrElseUpdate(name, mutable.LinkedHashMap.empty)
  }

  private def before(r: AnyRef): Unit = {
    requests += r
    if (faults.nonEmpty) throw faults.dequeue()
  }

  private def keyOf(name: String, item: SdkMap): (String, String) = {
    val s = specs(name)
    val pk = item.get(s.partitionKey)
    val sk = item.get(s.sortKey)
    if (pk == null || sk == null || pk.s() == null || sk.s() == null || pk.s().isEmpty)
      throw DynamoDbException.builder().statusCode(400).message("ValidationException").build()
    (pk.s(), sk.s())
  }

  private def ccf(old: Option[SdkMap], allOld: Boolean): ConditionalCheckFailedException = {
    val b = ConditionalCheckFailedException
      .builder()
      .statusCode(400)
      .awsErrorDetails(
        AwsErrorDetails.builder().errorCode("ConditionalCheckFailedException").build()
      )
      .message("The conditional request failed")
    if (allOld && !omitOldItem) old.foreach(o => b.item(o))
    b.build().asInstanceOf[ConditionalCheckFailedException]
  }

  def getItem(r: GetItemRequest): GetItemResponse = synchronized {
    before(r)
    table(r.tableName()).get(keyOf(r.tableName(), r.key())) match {
      case Some(i) => GetItemResponse.builder().item(i).build()
      case None => GetItemResponse.builder().build()
    }
  }

  def putItem(r: PutItemRequest): PutItemResponse = synchronized {
    before(r)
    val out = applyPut(r)
    afterWrite(r)(applyPut(r)).fold(out)(_.asInstanceOf[PutItemResponse])
  }

  private def applyPut(r: PutItemRequest): PutItemResponse = {
    val t = table(r.tableName())
    val k = keyOf(r.tableName(), r.item())
    val old = t.get(k)
    val allOld =
      r.returnValuesOnConditionCheckFailure == ReturnValuesOnConditionCheckFailure.ALL_OLD
    val names = r.expressionAttributeNames().asScala
    val values = r.expressionAttributeValues().asScala
    r.conditionExpression() match {
      case null => ()
      case "attribute_not_exists(#pk)" =>
        require(names("#pk") == specs(r.tableName()).partitionKey)
        if (old.isDefined) throw ccf(old, allOld)
      case "#v = :expected" =>
        val ok =
          old.flatMap(o => Option(o.get(names("#v")))).exists(_.n() == values(":expected").n())
        if (!ok) throw ccf(old, allOld)
      case other => throw new IllegalArgumentException(s"stub: unsupported condition $other")
    }
    t(k) = r.item()
    PutItemResponse.builder().build()
  }

  def deleteItem(r: DeleteItemRequest): DeleteItemResponse = synchronized {
    before(r)
    val out = applyDelete(r)
    afterWrite(r)(applyDelete(r)).fold(out)(_.asInstanceOf[DeleteItemResponse])
  }

  private def applyDelete(r: DeleteItemRequest): DeleteItemResponse = {
    val t = table(r.tableName())
    val k = keyOf(r.tableName(), r.key())
    val old = t.get(k)
    val allOld =
      r.returnValuesOnConditionCheckFailure == ReturnValuesOnConditionCheckFailure.ALL_OLD
    r.conditionExpression() match {
      case "#rs = :rs" =>
        val attr = r.expressionAttributeNames().get("#rs")
        val want = r.expressionAttributeValues().get(":rs")
        if (!old.flatMap(o => Option(o.get(attr))).contains(want)) throw ccf(old, allOld)
      case other => throw new IllegalArgumentException(s"stub: unsupported condition $other")
    }
    t.remove(k)
    DeleteItemResponse.builder().build()
  }

  def query(r: QueryRequest): QueryResponse = synchronized {
    before(r)
    val spec = specs(r.tableName())
    val (pkAttr, skAttr) = spec.keyAttributes(Option(r.indexName())).get
    val names = r.expressionAttributeNames().asScala
    val values = r.expressionAttributeValues().asScala
    require(names("#p") == pkAttr && names.get("#s").forall(_ == skAttr), "stub: attribute names")
    val p = values(":p").s()
    def v(n: String) = values(n).s()
    def le(a: String, b: String) = Canonical.compareUtf8(a, b) <= 0
    val sortOk: String => Boolean = r.keyConditionExpression() match {
      case "#p = :p" => _ => true
      case "#p = :p AND #s = :s" => _ == v(":s")
      case "#p = :p AND begins_with(#s, :s)" => _.startsWith(v(":s"))
      case "#p = :p AND #s BETWEEN :lo AND :hi" => s => le(v(":lo"), s) && le(s, v(":hi"))
      case "#p = :p AND #s <= :hi" => s => le(s, v(":hi"))
      case other => throw new IllegalArgumentException(s"stub: unsupported key condition $other")
    }
    val t = table(r.tableName())
    def str(i: SdkMap, a: String) = Option(i.get(a)).flatMap(x => Option(x.s()))
    val matching = t.toVector.flatMap { case (tk, i) =>
      for {
        ip <- str(i, pkAttr)
        is <- str(i, skAttr)
        if ip == p && sortOk(is)
      } yield (is, tk, i)
    }
    val sorted = matching.sortWith { (a, b) =>
      val c = Canonical.compareUtf8(a._1, b._1)
      if (c != 0) c < 0 else Canonical.compareUtf8(a._2.toString, b._2.toString) < 0
    }
    val ordered = if (r.scanIndexForward() == java.lang.Boolean.FALSE) sorted.reverse else sorted
    val from =
      if (r.hasExclusiveStartKey && !r.exclusiveStartKey().isEmpty) {
        val start = keyOf(r.tableName(), r.exclusiveStartKey())
        ordered.indexWhere(_._2 == start) + 1
      } else 0
    val n = math.min(Option(r.limit()).map(_.intValue).getOrElse(Int.MaxValue), pageSize)
    val page = ordered.slice(from, from + n)
    val b = QueryResponse.builder().items(page.map(_._3).asJava).count(page.size)
    if (from + page.size < ordered.size && page.nonEmpty) {
      val last = page.last._3
      val lek = (Vector(spec.partitionKey, spec.sortKey) ++
        Option(r.indexName()).toVector.flatMap(_ => Vector(pkAttr, skAttr))).distinct
        .map(a => a -> last.get(a))
        .toMap
      b.lastEvaluatedKey(lek.asJava)
    }
    b.build()
  }

  def batchGetItem(r: BatchGetItemRequest): BatchGetItemResponse = synchronized {
    before(r)
    batchCalls += 1
    val out = mutable.Map.empty[String, java.util.List[SdkMap]]
    val left = mutable.Map.empty[String, KeysAndAttributes]
    r.requestItems().asScala.foreach { case (name, ka) =>
      val keys = ka.keys().asScala.toVector
      if (keys.size > 100 || keys.distinct.size != keys.size)
        throw DynamoDbException.builder().statusCode(400).message("ValidationException").build()
      val t = table(name)
      val skip = unprocessed(batchCalls, keys).toSet
      out(name) = keys.filterNot(skip).flatMap(k => t.get(keyOf(name, k))).asJava
      if (skip.nonEmpty) left(name) = KeysAndAttributes.builder().keys(skip.toVector.asJava).build()
    }
    BatchGetItemResponse.builder().responses(out.asJava).unprocessedKeys(left.asJava).build()
  }
}

/** Shared adapter fixtures. */
object Ddb {
  val names: Map[TableRole, String] = TableRole.values.map(r => r -> s"sc-test-${r.tag}").toMap

  def settings(
      maxQueryLimit: Int = 50,
      maxQueryPages: Int = 10,
      attempts: Int = 3,
      base: Long = 10,
      max: Long = 1000
  ): DynamoDbAdapterSettings =
    DynamoDbAdapterSettings.of(maxQueryLimit, maxQueryPages, attempts, base, max).toOption.get

  def adapter(
      stub: StubDynamoDb,
      s: DynamoDbAdapterSettings = settings(),
      sleeper: scholarchain.storage.port.Sleeper = new RecordingSleeper(new ManualClock(0))
  ): DynamoDbKeyValueStore =
    DynamoDbKeyValueStore(stub, DynamoDbTableNames.of(names).toOption.get, s, sleeper)

  def stub(): StubDynamoDb = new StubDynamoDb(names)
}
