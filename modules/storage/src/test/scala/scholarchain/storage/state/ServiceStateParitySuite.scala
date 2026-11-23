package scholarchain.storage.state

import scholarchain.storage.model.{AttrValue, PrimaryKey, StoreError, TableRole}
import scholarchain.storage.port.{KeyQuery, SortKeyCondition}
import StateWorld.*

/**
 * SE-INT-01 item 5: for each store (and the key-value ports under them), the in-memory store and
 * the DynamoDB adapter over the stub return identical results for the same operation sequence.
 * Results are compared exactly; errors by their class (details may word the key differently).
 */
class ServiceStateParitySuite extends munit.FunSuite {

  private def norm(r: Either[StoreError, ?]): String =
    r.fold(e => s"L:${e.productPrefix}", v => s"R:$v")

  /** One script over one backend; every result in order. */
  private def script(b: Backend): Vector[String] = {
    val w = world(b)
    val o = w.stores.ownership
    val j = w.stores.jobs
    val i = w.stores.idempotency
    val out = Vector.newBuilder[String]
    def rec(r: Either[StoreError, ?]): Unit = out += norm(r)
    def ticket(r: Either[StoreError, IdempotencyStart]): Option[IdempotencyTicket] = {
      rec(r)
      r.toOption.collect { case IdempotencyStart.Fresh(t) => t }
    }

    // ownership
    rec(o.record(qA, "client-a"))
    rec(o.record(qA, "client-a"))
    rec(o.record(qA, "client-b"))
    rec(o.record(qB, "client-b"))
    rec(o.owner(qA))
    rec(o.owner(qC))
    rec(o.owner("not-an-id"))
    rec(o.ownedBy("client-a", Vector(qA, qB, qC)))
    rec(o.ownedBy("client-b", Vector(qB, qB)))
    w.clock.advance(OwnerTtl * 1000)
    rec(o.owner(qA))
    rec(o.ownedBy("client-a", Vector(qA)))

    // jobs
    val created = j.create("job-1", qA, "client-a", "queued")
    rec(created)
    rec(j.create("job-1", qA, "client-a", "queued"))
    rec(j.create("job-1", qB, "client-b", "queued"))
    rec(j.get("client-a", "job-1"))
    rec(j.get("client-b", "job-1"))
    rec(j.succeed("job-1", "completed", ok))
    w.clock.advance(250)
    rec(j.start("job-1", "graph-rag-workflow"))
    rec(j.start("job-1", "graph-rag-workflow"))
    created.foreach(c => rec(j.remove(c)))
    w.clock.advance(250)
    rec(j.fail("job-1", "failed", err))
    rec(j.succeed("job-1", "completed", ok))
    rec(j.fail("job-1", "failed", err))
    rec(j.start("job-2", "x"))
    val c3 = j.create("job-3", qC, "client-a", "queued")
    rec(c3)
    c3.foreach(c => rec(j.remove(c)))
    rec(j.get("client-a", "job-3"))
    w.kv.staleNext.set(10)
    rec(j.create("job-4", qC, "client-a", "queued").flatMap(_ => j.start("job-4", "run")))
    w.kv.staleNext.set(0)

    // idempotency
    val t1 = ticket(i.begin("client-a", "key-1", reqA, tokA))
    ticket(i.begin("client-a", "key-1", reqA, tokB))
    ticket(i.begin("client-a", "key-1", reqB, tokB))
    t1.foreach(t => rec(i.complete(t, ok)))
    ticket(i.begin("client-a", "key-1", reqA, tokB))
    val t2 = ticket(i.begin("client-a", "key-2", reqA, tokA))
    t2.foreach(t => rec(i.abandon(t)))
    t2.foreach(t => rec(i.abandon(t)))
    val t3 = ticket(i.begin("client-a", "key-2", reqB, tokB))
    // SE-INT-02: in-flight expiry is rounded up to the next second; the clock is mid-second here.
    w.clock.advance(InFlightTtl * 1000 + 1000)
    val t4 = ticket(i.begin("client-a", "key-2", reqB, tokC))
    t3.foreach(t => rec(i.complete(t, ok)))
    t4.foreach(t => rec(i.complete(t, err)))
    ticket(i.begin("client-a", "key-2", reqB, tokA))
    w.clock.advance(CompletedTtl * 1000)
    ticket(i.begin("client-a", "key-2", reqA, tokA))
    // SE-INT-02 (CR-INT-01-1): applied writes whose responses are lost, then retried.
    w.kv.appliedThenTransient.set(1)
    ticket(i.begin("client-a", "key-3", reqA, tokA))
    val t5 = ticket(i.begin("client-a", "key-3", reqA, tokA))
    ticket(i.begin("client-a", "key-3", reqA, tokB))
    w.kv.appliedThenTransient.set(1)
    t5.foreach(t => rec(i.complete(t, ok)))
    t5.foreach(t => rec(i.complete(t, ok)))
    t5.foreach(t => rec(i.complete(t, err)))
    val t6 = ticket(i.begin("client-a", "key-4", reqA, tokB))
    w.kv.appliedThenResent.set(1)
    t6.foreach(t => rec(i.complete(t, ok)))
    ticket(i.begin("client-a", "key-4", reqA, tokC))

    // key-value ports directly
    val kv = w.kv
    val jobKey = PrimaryKey("job#job-1", "job")
    rec(
      kv.getMany(TableRole.QueryJob, Vector(jobKey, jobKey, PrimaryKey("job#none", "job")))
        .map(_.keySet)
    )
    rec(
      kv.query(TableRole.QueryJob, KeyQuery(None, "job#job-1", SortKeyCondition.All, 5)).map(_.size)
    )
    rec(
      kv.query(TableRole.QueryJob, KeyQuery(None, "job#job-1", SortKeyCondition.Equals(""), 5))
        .map(_.size)
    )
    rec(
      kv.query(TableRole.QueryJob, KeyQuery(None, "job#job-1", SortKeyCondition.BeginsWith(""), 5))
        .map(_.size)
    )
    rec(
      kv.query(
        TableRole.QueryJob,
        KeyQuery(None, "job#job-1", SortKeyCondition.Between("z", "a"), 5)
      ).map(_.size)
    )
    rec(
      kv.query(
        TableRole.QueryJob,
        KeyQuery(None, "job#job-1", SortKeyCondition.Between("", "job"), 5)
      ).map(_.size)
    )
    rec(kv.query(TableRole.QueryJob, KeyQuery(None, "job#job-1", SortKeyCondition.All, 51)))
    rec(kv.deleteIfRecord(TableRole.QuerySession, jobKey, "a" * 64))
    rec(
      kv.putIfAbsent(TableRole.QueryJob, Map("pk" -> AttrValue.S(""), "sk" -> AttrValue.S("job")))
    )
    out.result()
  }

  test("the in-memory store and the DynamoDB adapter give identical results, step by step") {
    val fake = script(Backend.Fake)
    val dynamo = script(Backend.Dynamo)
    assertEquals(fake.size, dynamo.size)
    fake.zip(dynamo).zipWithIndex.foreach { case ((f, d), n) =>
      assertEquals(d, f, s"step $n")
    }
    // The script is not vacuous: it reaches every outcome class it is meant to compare.
    val all = fake.mkString("\n")
    Vector(
      "L:Conflict",
      "L:Permanent",
      "L:StaleVersion",
      "L:Refused",
      "L:Transient",
      "Replay",
      "InProgress",
      "Mismatch"
    )
      .foreach(tag => assert(all.contains(tag), tag))
  }
}
