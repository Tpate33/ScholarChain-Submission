package scholarchain.storage.aws.dynamodb

import java.net.SocketException

import software.amazon.awssdk.core.exception.{ApiCallTimeoutException, SdkClientException}
import software.amazon.awssdk.services.dynamodb.model.{
  DeleteItemRequest,
  GetItemRequest,
  PutItemRequest
}

import scholarchain.storage.fake.ManualClock
import scholarchain.storage.model.{PrimaryKey, StoreError, TableRole}
import scholarchain.storage.port.DeleteOutcome
import scholarchain.storage.state.{IdempotencyStart, IdempotencyTicket, ServiceStateStores}
import scholarchain.storage.state.StateWorld.*

/**
 * SE-INT-02 (CR-INT-01-1, -5, -6): writes the stub APPLIES and whose response is then lost, shaped
 * as the SDK would surface them: a timeout or a connection reset after the server applied the
 * write, or the SDK's own resend of the applied request, which then fails its condition.
 */
class DynamoDbAmbiguousWriteSuite extends munit.FunSuite {
  import DynamoDbAdapterSuite.{h, isConflict, row}

  private def world() = {
    val stub = Ddb.stub()
    val clock = new ManualClock(Start)
    (stub, clock, ServiceStateStores(Ddb.adapter(stub), clock, settings()))
  }

  private def fresh(r: Either[StoreError, IdempotencyStart]): IdempotencyTicket = r match {
    case Right(IdempotencyStart.Fresh(t)) => t
    case other => fail(s"expected Fresh, got $other")
  }

  private def kind(r: Either[StoreError, ?]): String = r.fold(_.productPrefix, _ => "Right")

  private def reset = SdkClientException
    .builder()
    .message("lost")
    .cause(new SocketException("Connection reset"))
    .build()

  private def versionedPuts(stub: StubDynamoDb) =
    stub.recorded.count {
      case p: PutItemRequest => p.conditionExpression() == "#v = :expected"
      case _ => false
    }

  test("begin: PutItem applied, then ApiCallTimeout; the same attempt's retry is Fresh") {
    val (stub, clock, s) = world()
    stub.failAfterApplyNext(ApiCallTimeoutException.builder().message("lost").build())
    assertEquals(kind(s.idempotency.begin("client-a", "key-1", reqA, tokA)), "Transient")
    assertEquals(stub.itemCount(TableRole.IdempotencyKey), 1, "the write was applied")
    clock.advance(100)
    val t = fresh(s.idempotency.begin("client-a", "key-1", reqA, tokA))
    assertEquals(
      s.idempotency.begin("client-a", "key-1", reqA, tokB),
      Right(IdempotencyStart.InProgress)
    )
    assertEquals(s.idempotency.complete(t, ok), Right(()))
  }

  test("begin: PutItem applied, then resent by the SDK; ALL_OLD is its own item, Fresh at once") {
    val (stub, _, s) = world()
    stub.retryAfterApplyNext(1)
    fresh(s.idempotency.begin("client-a", "key-1", reqA, tokA)) // one call
    assertEquals(stub.recorded.count(_.isInstanceOf[PutItemRequest]), 2, "sent, then resent")
    assertEquals(
      s.idempotency.begin("client-a", "key-1", reqA, tokB),
      Right(IdempotencyStart.InProgress)
    )
  }

  test("begin: the resend fails without ALL_OLD; one GetItem decides, still Fresh") {
    val (stub, _, s) = world()
    stub.omitOldItem = true
    stub.retryAfterApplyNext(1)
    fresh(s.idempotency.begin("client-a", "key-1", reqA, tokA))
    assert(stub.recorded.exists(_.isInstanceOf[GetItemRequest]))
  }

  test("complete: PutItem applied, then a connection reset (Transient); the retry is Right") {
    val (stub, clock, s) = world()
    val t = fresh(s.idempotency.begin("client-a", "key-1", reqA, tokA))
    stub.failAfterApplyNext(reset)
    assertEquals(kind(s.idempotency.complete(t, ok)), "Transient", "CR-INT-01-5: not Permanent")
    clock.advance(1000)
    assertEquals(s.idempotency.complete(t, ok), Right(()))
    assertEquals(versionedPuts(stub), 1, "the retry wrote nothing")
    assertEquals(
      s.idempotency.begin("client-a", "key-1", reqA, tokB),
      Right(IdempotencyStart.Replay(ok))
    )
  }

  test("complete: PutItem applied, then resent and failed (StaleVersion); re-read, Right") {
    val (stub, _, s) = world()
    val t = fresh(s.idempotency.begin("client-a", "key-1", reqA, tokA))
    stub.retryAfterApplyNext(1)
    assertEquals(s.idempotency.complete(t, ok), Right(()))
    assertEquals(versionedPuts(stub), 2, "sent, then resent by the SDK")
  }

  test("abandon: DeleteItem applied, then a timeout; the retry is false and the key is free") {
    val (stub, _, s) = world()
    val t = fresh(s.idempotency.begin("client-a", "key-1", reqA, tokA))
    stub.failAfterApplyNext(ApiCallTimeoutException.builder().message("lost").build())
    assertEquals(kind(s.idempotency.abandon(t)), "Transient")
    assertEquals(s.idempotency.abandon(t), Right(false))
    fresh(s.idempotency.begin("client-a", "key-1", reqA, tokB))
  }

  test("job transition: PutItem applied, then reset; the retry repeats it idempotently") {
    val (stub, _, s) = world()
    s.jobs.create("job-1", qA, "client-a", "queued")
    stub.failAfterApplyNext(reset)
    assertEquals(kind(s.jobs.start("job-1", "run")), "Transient")
    assertEquals(s.jobs.start("job-1", "run").map(_.version), Right(2L), "no second update")
  }

  test("CR-INT-01-6: deleteIfRecord without ALL_OLD re-reads: Absent, Conflict, Deleted") {
    val stub = Ddb.stub()
    stub.omitOldItem = true
    val kv = Ddb.adapter(stub)
    val Job = TableRole.QueryJob
    val key = PrimaryKey("job#1", "job")
    assertEquals(kv.deleteIfRecord(Job, key, h('a')), Right(DeleteOutcome.Absent))
    kv.putIfAbsent(Job, row("job#1", h('a')))
    assert(isConflict(kv.deleteIfRecord(Job, key, h('b'))), "was Absent before SE-INT-02")
    assertEquals(stub.itemCount(Job), 1, "kept")
    assertEquals(kv.deleteIfRecord(Job, key, h('a')), Right(DeleteOutcome.Deleted))
    val gets = stub.recorded.collect { case g: GetItemRequest => g }
    assertEquals(gets.size, 2, "one consistent GetItem per failed condition without the item")
    assert(gets.forall(_.consistentRead() == java.lang.Boolean.TRUE))
  }

  test("deleteIfRecord: an applied delete resent by the SDK reads as Absent (documented)") {
    val stub = Ddb.stub()
    val kv = Ddb.adapter(stub)
    val key = PrimaryKey("job#1", "job")
    kv.putIfAbsent(TableRole.QueryJob, row("job#1", h('a')))
    stub.retryAfterApplyNext(1)
    assertEquals(kv.deleteIfRecord(TableRole.QueryJob, key, h('a')), Right(DeleteOutcome.Absent))
    assertEquals(stub.itemCount(TableRole.QueryJob), 0)
    assertEquals(stub.recorded.count(_.isInstanceOf[DeleteItemRequest]), 2)
  }
}
