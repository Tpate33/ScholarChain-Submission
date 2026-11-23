package scholarchain.services

import java.util.concurrent.{CountDownLatch, TimeUnit}

import scala.collection.mutable
import scala.jdk.CollectionConverters.*

import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.dynamodb.model.*

import scholarchain.storage.aws.dynamodb.{
  DynamoDbAdapterSettings,
  DynamoDbCalls,
  DynamoDbKeyValueStore,
  DynamoDbTableNames
}
import scholarchain.storage.model.{TableRole, TableSpecs}

/**
 * SV-INT-02: a local DynamoDB behind storage's five-operation [[DynamoDbCalls]] seam, for the
 * services tests (storage's own stub is in storage's test tree, which services cannot see). It
 * interprets exactly the condition expressions the adapter sends for GetItem, PutItem, DeleteItem
 * and BatchGetItem (Query is not used by the service-state stores and fails loudly). Each request
 * is atomic (one monitor), as a server would be. Faults: an error after the write was APPLIED (the
 * response is lost), and a request that HANGS until released (it then completes normally). No AWS,
 * no network.
 */
final class DdbStub(val names: Map[TableRole, String]) extends DynamoDbCalls {
  private type SdkMap = java.util.Map[String, AttributeValue]

  private val specs = TableSpecs.all.map(s => names(s.role) -> s).toMap
  private val rows = mutable.Map.empty[String, mutable.LinkedHashMap[(String, String), SdkMap]]
  private val lostAfterApply = mutable.Map.empty[String, mutable.Queue[Throwable]]
  private val hangs = mutable.Queue.empty[(String, CountDownLatch, CountDownLatch)]
  private val log = mutable.ArrayBuffer.empty[String]

  /**
   * The next writes (PutItem, DeleteItem) to `role`'s table are APPLIED, then throw these errors in
   * order: the response is lost after the write took effect.
   */
  def loseResponseAfterApply(role: TableRole, errors: Throwable*): Unit = synchronized {
    lostAfterApply.getOrElseUpdate(names(role), mutable.Queue.empty).enqueueAll(errors)
    ()
  }

  /**
   * The next request of operation `op` ("GetItem", "PutItem", "DeleteItem") blocks, outside the
   * monitor, until `release` is counted down; then it runs normally. `entered` is counted down when
   * it starts blocking.
   */
  def hangNext(op: String, release: CountDownLatch, entered: CountDownLatch): Unit =
    synchronized(hangs.enqueue((op, release, entered)))

  /** Every request, in order: its operation and table name. */
  def requests: Vector[String] = synchronized(log.toVector)

  def itemCount(role: TableRole): Int =
    synchronized(rows.get(names(role)).map(_.size).getOrElse(0))

  private def maybeHang(op: String): Unit = {
    val h = synchronized {
      if (hangs.headOption.exists(_._1 == op)) Some(hangs.dequeue()) else None
    }
    h.foreach { case (_, release, entered) =>
      entered.countDown()
      // Ignores interruption, like an SDK call that does not abort on interrupt (P3).
      var done = false
      while (!done)
        try { release.await(60, TimeUnit.SECONDS); done = true }
        catch { case _: InterruptedException => () }
    }
  }

  private def table(name: String) = {
    if (!specs.contains(name))
      throw ResourceNotFoundException.builder().statusCode(400).message("no table").build()
    rows.getOrElseUpdate(name, mutable.LinkedHashMap.empty)
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
    if (allOld) old.foreach(o => b.item(o))
    b.build().asInstanceOf[ConditionalCheckFailedException]
  }

  private def afterWrite(table: String): Unit =
    lostAfterApply.get(table).filter(_.nonEmpty).foreach(q => throw q.dequeue())

  def getItem(r: GetItemRequest): GetItemResponse = {
    maybeHang("GetItem")
    synchronized {
      log += s"GetItem ${r.tableName()}"
      table(r.tableName()).get(keyOf(r.tableName(), r.key())) match {
        case Some(i) => GetItemResponse.builder().item(i).build()
        case None => GetItemResponse.builder().build()
      }
    }
  }

  def putItem(r: PutItemRequest): PutItemResponse = {
    maybeHang("PutItem")
    synchronized {
      log += s"PutItem ${r.tableName()}"
      val t = table(r.tableName())
      val k = keyOf(r.tableName(), r.item())
      val old = t.get(k)
      val allOld =
        r.returnValuesOnConditionCheckFailure == ReturnValuesOnConditionCheckFailure.ALL_OLD
      val ns = r.expressionAttributeNames().asScala
      val vs = r.expressionAttributeValues().asScala
      r.conditionExpression() match {
        case null => ()
        case "attribute_not_exists(#pk)" => if (old.isDefined) throw ccf(old, allOld)
        case "#v = :expected" =>
          val ok = old.flatMap(o => Option(o.get(ns("#v")))).exists(_.n() == vs(":expected").n())
          if (!ok) throw ccf(old, allOld)
        case other => throw new IllegalArgumentException(s"stub: unsupported condition $other")
      }
      t(k) = r.item()
      afterWrite(r.tableName())
      PutItemResponse.builder().build()
    }
  }

  def deleteItem(r: DeleteItemRequest): DeleteItemResponse = {
    maybeHang("DeleteItem")
    synchronized {
      log += s"DeleteItem ${r.tableName()}"
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
      afterWrite(r.tableName())
      DeleteItemResponse.builder().build()
    }
  }

  def query(r: QueryRequest): QueryResponse =
    throw new IllegalArgumentException("stub: Query is not used by the service-state stores")

  def batchGetItem(r: BatchGetItemRequest): BatchGetItemResponse = synchronized {
    log += "BatchGetItem"
    val out = mutable.Map.empty[String, java.util.List[SdkMap]]
    r.requestItems().asScala.foreach { case (name, ka) =>
      val keys = ka.keys().asScala.toVector
      if (keys.size > 100 || keys.distinct.size != keys.size)
        throw DynamoDbException.builder().statusCode(400).message("ValidationException").build()
      val t = table(name)
      out(name) = keys.flatMap(k => t.get(keyOf(name, k))).asJava
    }
    BatchGetItemResponse.builder().responses(out.asJava).build()
  }
}

object DdbStub {
  val names: Map[TableRole, String] = TableRole.values.map(r => r -> s"sv-test-${r.tag}").toMap

  /** A transport failure after the write was applied (Transient for the adapter, CR-INT-01-5). */
  def connectionReset(): Throwable =
    SdkClientException
      .builder()
      .message("stub: connection reset after the write")
      .cause(new java.net.SocketException("Connection reset"))
      .build()

  def adapter(stub: DdbStub): DynamoDbKeyValueStore =
    DynamoDbKeyValueStore(
      stub,
      DynamoDbTableNames.of(stub.names).toOption.get,
      DynamoDbAdapterSettings.of(1000, 4, 3, 0, 0).toOption.get,
      (_: Long) => ()
    )
}
