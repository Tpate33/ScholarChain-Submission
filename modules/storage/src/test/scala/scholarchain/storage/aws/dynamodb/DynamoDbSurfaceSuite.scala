package scholarchain.storage.aws.dynamodb

import java.lang.reflect.{InvocationHandler, Method, Modifier, Proxy}
import java.util.concurrent.ConcurrentLinkedQueue

import scala.jdk.CollectionConverters.*

import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient

import scholarchain.storage.model.{PrimaryKey, StoreError, TableRole}
import scholarchain.storage.port.{KeyQuery, SortKeyCondition}

/** SE-INT-01 item 1: Scan and every other write path are unreachable; caller-supplied settings. */
class DynamoDbSurfaceSuite extends munit.FunSuite {
  import DynamoDbAdapterSuite.{h, row}

  test("the seam has exactly GetItem, PutItem, DeleteItem, Query and BatchGetItem") {
    // Instance methods only: Scala adds a static forwarder for the companion's `of`.
    val methods = classOf[DynamoDbCalls].getMethods
      .filterNot(m => Modifier.isStatic(m.getModifiers))
      .map(_.getName)
      .toSet
    assertEquals(methods, Set("getItem", "putItem", "deleteItem", "query", "batchGetItem"))
    assert(compileErrors("(??? : DynamoDbCalls).scan(???)").nonEmpty)
    assert(compileErrors("(??? : DynamoDbCalls).batchWriteItem(???)").nonEmpty)
    assert(compileErrors("(??? : DynamoDbCalls).updateItem(???)").nonEmpty)
    assert(compileErrors("(??? : DynamoDbCalls).transactWriteItems(???)").nonEmpty)
    assert(compileErrors("(??? : DynamoDbCalls).executeStatement(???)").nonEmpty)
    assert(compileErrors("(??? : DynamoDbCalls).getItem(???)").isEmpty, "control")
  }

  test("the adapter holds no SDK client and no mutable field") {
    val fields = classOf[DynamoDbKeyValueStore].getDeclaredFields.toVector
    assert(!fields.exists(f => classOf[DynamoDbClient].isAssignableFrom(f.getType)))
    fields.foreach(f => assert(Modifier.isFinal(f.getModifiers), s"${f.getName} is final"))
  }

  test("every adapter operation over a real client interface calls only the five operations") {
    val invoked = new ConcurrentLinkedQueue[String]()
    val stub = Ddb.stub()
    val five = classOf[DynamoDbCalls].getMethods
      .filterNot(m => Modifier.isStatic(m.getModifiers))
      .map(m => m.getName -> m)
      .toMap
    val handler: InvocationHandler = (_: AnyRef, m: Method, args: Array[AnyRef]) => {
      invoked.add(m.getName)
      five.get(m.getName) match {
        case Some(target) if args != null && args.length == 1 =>
          try target.invoke(stub, args(0))
          catch { case e: java.lang.reflect.InvocationTargetException => throw e.getCause }
        case _ => throw new AssertionError(s"unexpected DynamoDbClient call ${m.getName}")
      }
    }
    val client = Proxy
      .newProxyInstance(getClass.getClassLoader, Array(classOf[DynamoDbClient]), handler)
      .asInstanceOf[DynamoDbClient]
    val kv = DynamoDbKeyValueStore(
      DynamoDbCalls.of(client),
      DynamoDbTableNames.of(Ddb.names).toOption.get,
      Ddb.settings(),
      DynamoDbKeyValueStore.ThreadSleeper
    )
    val Job = TableRole.QueryJob
    val k = PrimaryKey("job#1", "job")
    kv.putIfAbsent(Job, row("job#1", h('a'), Some(1)))
    kv.putIfAbsent(Job, row("job#1", h('b'), Some(1)))
    kv.putIfVersion(Job, row("job#1", h('c'), Some(2)), 1)
    kv.get(Job, k)
    kv.query(Job, KeyQuery(None, "job#1", SortKeyCondition.BeginsWith("j"), 5))
    kv.getMany(Job, Vector(k))
    kv.deleteIfRecord(Job, k, h('c'))
    val names = invoked.asScala.toSet
    assertEquals(names, Set("putItem", "getItem", "query", "batchGetItem", "deleteItem"))
    assert(!names.exists(n => n.startsWith("scan") || n.startsWith("transact")))
  }

  test("client settings: every value from the caller, validated") {
    val ok = DynamoDbClientSettings.of("eu-west-1", None, 2000, 500)
    assertEquals(ok.map(_.region), Right("eu-west-1"))
    assert(DynamoDbClientSettings.of("", None, 2000, 500).isLeft)
    assert(DynamoDbClientSettings.of("eu-west-1", None, 500, 500).isLeft, "attempt < call")
    assert(DynamoDbClientSettings.of("eu-west-1", None, 500, 0).isLeft)
    assert(DynamoDbClientSettings.of("eu-west-1", null, 2000, 500).isLeft)
    assert(DynamoDbClientSettings.of("eu-west-1", Some("http://example.com"), 2000, 500).isLeft)
    assert(DynamoDbClientSettings.of("eu-west-1", Some("https://u@h.example"), 2000, 500).isLeft)
    assert(DynamoDbClientSettings.of("eu-west-1", Some("http://localhost:8000"), 2000, 500).isRight)
    assert(DynamoDbClientSettings.of("eu-west-1", Some("https://ddb.example"), 2000, 500).isRight)
  }

  test("the client factory applies the caller's Region, endpoint and timeouts (no AWS call)") {
    val s =
      DynamoDbClientSettings.of("eu-west-1", Some("http://127.0.0.1:1"), 2000, 500).toOption.get
    val client = DynamoDbClients.build(s, AnonymousCredentialsProvider.create())
    try {
      val c = client.serviceClientConfiguration()
      assertEquals(c.region(), Region.of("eu-west-1"))
      assertEquals(c.endpointOverride().map(_.toString).orElse(""), "http://127.0.0.1:1")
      val o = c.overrideConfiguration()
      assertEquals(o.apiCallTimeout().map(_.toMillis).orElse(-1L), 2000L)
      assertEquals(o.apiCallAttemptTimeout().map(_.toMillis).orElse(-1L), 500L)
    } finally client.close()
  }

  test("adapter settings and table names are validated") {
    assert(DynamoDbAdapterSettings.of(0, 1, 1, 0, 0).isLeft)
    assert(DynamoDbAdapterSettings.of(1, 0, 1, 0, 0).isLeft)
    assert(DynamoDbAdapterSettings.of(1, 1, 0, 0, 0).isLeft)
    assert(DynamoDbAdapterSettings.of(1, 1, 1, -1, 0).isLeft)
    assert(DynamoDbAdapterSettings.of(1, 1, 1, 10, 5).isLeft)
    assert(DynamoDbAdapterSettings.of(1, 1, 1, 10, 10).isRight)
    assert(DynamoDbTableNames.of(Map.empty).isLeft)
    assert(DynamoDbTableNames.of(Map(TableRole.QueryJob -> "ab")).isLeft, "too short")
    assert(DynamoDbTableNames.of(Map(TableRole.QueryJob -> "has space")).isLeft)
    assert(
      DynamoDbTableNames
        .of(Map(TableRole.QueryJob -> "same", TableRole.IdempotencyKey -> "same"))
        .isLeft,
      "distinct"
    )
    val names = DynamoDbTableNames.of(Map(TableRole.QueryJob -> "jobs-dev")).toOption.get
    assertEquals(names.of(TableRole.QueryJob), Right("jobs-dev"))
    assert(names.of(TableRole.RunLedger).left.exists(_.isInstanceOf[StoreError.Refused]))
  }
}
