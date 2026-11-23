package scholarchain.storage.state

import scholarchain.storage.fake.ManualClock
import scholarchain.storage.model.{
  Attrs,
  AttrValue,
  IdempotencyStates,
  Item,
  JobStates,
  ServiceStateBounds,
  ServiceStateIds,
  StoreError,
  TableKeys,
  TableRole
}
import StateWorld.*

/**
 * SE-INT-01 item 3: the behaviour of the three service-state stores. Every test runs twice: over
 * the in-memory store and over the DynamoDB adapter on the stub (see the two subclasses below).
 * SE-INT-02 adds the CR-INT-01 repair tests (ambiguous writes, encoded size, clock skew).
 */
abstract class ServiceStateStoresContract(backend: Backend) extends munit.FunSuite {
  private def t(name: String)(body: => Any)(using munit.Location): Unit =
    test(s"[${backend.label}] $name")(body)

  private def kind(r: Either[StoreError, ?]): String = r.fold(_.productPrefix, _ => "Right")

  // ------------------------------------------------------------------ QueryOwnership

  t("ownership: record is create-once; the same client again is idempotent") {
    val w = world(backend)
    assertEquals(w.stores.ownership.record(qA, "client-a"), Right(()))
    assertEquals(w.stores.ownership.record(qA, "client-a"), Right(()))
    w.clock.advance(5000)
    assertEquals(w.stores.ownership.record(qA, "client-a"), Right(()), "later retry")
    assertEquals(w.stores.ownership.owner(qA), Right(Some("client-a")))
    assertEquals(w.stores.ownership.owner(qB), Right(None))
  }

  t("ownership: another client is Conflict, and the error names neither client") {
    val w = world(backend)
    w.stores.ownership.record(qA, "client-a")
    val r = w.stores.ownership.record(qA, "client-b")
    assertEquals(r, Left(StoreError.Conflict(w.stores.ownership.OwnedByAnother)))
    assert(!r.swap.toOption.get.message.contains("client-"), "no client named")
    assertEquals(w.stores.ownership.owner(qA), Right(Some("client-a")), "owner unchanged")
  }

  t("ownership: ownedBy is one bounded batch read of the caller's live queries") {
    val w = world(backend)
    w.stores.ownership.record(qA, "client-a")
    w.stores.ownership.record(qB, "client-b")
    w.stores.ownership.record(qC, "client-a")
    val asked = Vector(qA, qB, qC, qA, "query:never-recorded", "not-a-query-id")
    assertEquals(w.stores.ownership.ownedBy("client-a", asked), Right(Set(qA, qC)))
    assertEquals(w.kv.getManyCalls.get, 1, "one batch read")
    assertEquals(w.kv.lastGetManyKeys.size, 4, "distinct valid query IDs only")
    assertEquals(w.stores.ownership.ownedBy("client-b", asked), Right(Set(qB)))
    assertEquals(w.stores.ownership.ownedBy("client-a", Vector.empty), Right(Set.empty[String]))
    assertEquals(w.kv.getManyCalls.get, 2, "no call for an empty list")
    assert(w.stores.ownership.ownedBy("bad client", asked).isLeft)
  }

  t("ownership: more distinct IDs than the bound are Refused before any read") {
    val w = world(backend, settings(maxOwnedBy = 3))
    val ids = (1 to 4).toVector.map(i => s"query:q$i")
    assertEquals(kind(w.stores.ownership.ownedBy("client-a", ids)), "Refused")
    assertEquals(w.kv.getManyCalls.get, 0)
    assertEquals(w.stores.ownership.ownedBy("client-a", ids.take(3) ++ ids.take(3)), Right(Set()))
  }

  t("ownership: an owner past its TTL is not served") {
    val w = world(backend)
    w.stores.ownership.record(qA, "client-a")
    w.clock.advance(OwnerTtl * 1000 - 1000)
    assertEquals(w.stores.ownership.owner(qA), Right(Some("client-a")))
    w.clock.advance(1000)
    assertEquals(w.stores.ownership.owner(qA), Right(None))
    assertEquals(w.stores.ownership.ownedBy("client-a", Vector(qA)), Right(Set.empty[String]))
  }

  // ------------------------------------------------------------------ QueryJob

  t("jobs: create-once at version 1, visible only to the creating client") {
    val w = world(backend)
    val j = w.stores.jobs.create("job-1", qA, "client-a", "queued").toOption.get
    assertEquals((j.state, j.version, j.stage, j.writer), (JobStates.Queued, 1L, "queued", Writer))
    assertEquals(w.stores.jobs.get("client-a", "job-1"), Right(Some(j)))
    assertEquals(w.stores.jobs.get("client-b", "job-1"), Right(None), "as if missing")
    w.clock.advance(10)
    assertEquals(w.stores.jobs.create("job-1", qA, "client-a", "queued"), Right(j), "retry")
    val reuse = w.stores.jobs.create("job-1", qB, "client-b", "queued")
    assertEquals(reuse, Left(StoreError.Conflict("QueryJob: the job ID is already in use")))
  }

  t("jobs: forward-only transitions; repeating the last one is idempotent") {
    val w = world(backend)
    w.stores.jobs.create("job-1", qA, "client-a", "queued")
    w.clock.advance(100)
    val run = w.stores.jobs.start("job-1", "graph-rag-workflow").toOption.get
    assertEquals((run.state, run.version, run.startedAtMillis), ("running", 2L, Some(Start + 100)))
    assertEquals(w.stores.jobs.start("job-1", "graph-rag-workflow"), Right(run), "repeat")
    w.clock.advance(100)
    val done = w.stores.jobs.succeed("job-1", "completed", ok).toOption.get
    assertEquals((done.state, done.version, done.resultJson), ("succeeded", 3L, Some(ok)))
    assertEquals(done.finishedAtMillis, Some(Start + 200))
    assertEquals(w.stores.jobs.succeed("job-1", "completed", ok), Right(done), "repeat")
    assertEquals(
      kind(w.stores.jobs.succeed("job-1", "completed", ok.replace("200", "201"))),
      "Conflict"
    )
    assertEquals(kind(w.stores.jobs.fail("job-1", "failed", err)), "Conflict", "no way back")
    assertEquals(kind(w.stores.jobs.start("job-1", "graph-rag-workflow")), "Conflict")
    assertEquals(w.stores.jobs.get("client-a", "job-1"), Right(Some(done)))
  }

  t("jobs: queued may fail directly but never succeed; a missing job is Permanent") {
    val w = world(backend)
    w.stores.jobs.create("job-1", qA, "client-a", "queued")
    assertEquals(kind(w.stores.jobs.succeed("job-1", "completed", ok)), "Conflict")
    val f = w.stores.jobs.fail("job-1", "failed", err).toOption.get
    assertEquals((f.state, f.startedAtMillis, f.errorJson), ("failed", None, Some(err)))
    assertEquals(kind(w.stores.jobs.start("job-404", "x")), "Permanent")
  }

  t("jobs: a lost race is re-read and retried a bounded number of times") {
    val w = world(backend, settings(maxConflictRetries = 3))
    w.stores.jobs.create("job-1", qA, "client-a", "queued")
    w.kv.staleNext.set(3)
    assertEquals(w.stores.jobs.start("job-1", "run").map(_.version), Right(2L), "3 losses, 1 win")
    assertEquals(w.kv.putIfVersionCalls.get, 4)
    w.kv.staleNext.set(4)
    val r = w.stores.jobs.succeed("job-1", "completed", ok)
    assertEquals(kind(r), "StaleVersion")
    assertEquals(w.kv.putIfVersionCalls.get, 8, "exactly 1 + maxConflictRetries attempts")
    assertEquals(w.stores.jobs.get("client-a", "job-1").map(_.map(_.state)), Right(Some("running")))
  }

  t("jobs: remove deletes only the exact row; payloads are bounded JSON") {
    val w = world(backend)
    val j = w.stores.jobs.create("job-1", qA, "client-a", "queued").toOption.get
    w.stores.jobs.start("job-1", "run")
    assertEquals(w.stores.jobs.remove(j), Right(false), "the row changed: kept")
    assert(w.stores.jobs.get("client-a", "job-1").exists(_.isDefined))
    val j2 = w.stores.jobs.create("job-2", qB, "client-a", "queued").toOption.get
    assertEquals(w.stores.jobs.remove(j2), Right(true))
    assertEquals(w.stores.jobs.get("client-a", "job-2"), Right(None))
    assertEquals(w.stores.jobs.remove(j2), Right(false))
    assertEquals(kind(w.stores.jobs.succeed("job-1", "completed", "not json")), "Refused")
    assertEquals(
      kind(w.stores.jobs.succeed("job-1", "completed", "\"" + "x" * 5000 + "\"")),
      "Refused"
    )
    assertEquals(w.kv.putIfVersionCalls.get, 1, "refused payloads are never written")
  }

  t("jobs: a job past its TTL is neither served nor updated") {
    val w = world(backend)
    w.stores.jobs.create("job-1", qA, "client-a", "queued")
    w.clock.advance(JobTtl * 1000)
    assertEquals(w.stores.jobs.get("client-a", "job-1"), Right(None))
    assertEquals(kind(w.stores.jobs.start("job-1", "run")), "Permanent")
  }

  // ------------------------------------------------------------------ IdempotencyKey

  private def fresh(r: Either[StoreError, IdempotencyStart]): IdempotencyTicket = r match {
    case Right(IdempotencyStart.Fresh(t)) => t
    case other => fail(s"expected Fresh, got $other")
  }

  t("idempotency: Fresh, then InProgress, Mismatch, and Replay after completion") {
    val w = world(backend)
    val s = w.stores.idempotency
    val tk = fresh(s.begin("client-a", "key-1", reqA, tokA))
    assertEquals(s.begin("client-a", "key-1", reqA, tokB), Right(IdempotencyStart.InProgress))
    assertEquals(s.begin("client-a", "key-1", reqB, tokB), Right(IdempotencyStart.Mismatch))
    assertEquals(s.complete(tk, ok), Right(()))
    assertEquals(s.begin("client-a", "key-1", reqA, tokB), Right(IdempotencyStart.Replay(ok)))
    assertEquals(s.begin("client-a", "key-1", reqB, tokC), Right(IdempotencyStart.Mismatch))
    fresh(s.begin("client-b", "key-1", reqA, tokA)) // another client: another digest
  }

  t("idempotency: abandon deletes exactly the in-flight record") {
    val w = world(backend)
    val s = w.stores.idempotency
    val tk = fresh(s.begin("client-a", "key-1", reqA, tokA))
    assertEquals(s.abandon(tk), Right(true))
    assertEquals(s.abandon(tk), Right(false))
    val tk2 = fresh(s.begin("client-a", "key-1", reqB, tokB))
    assertEquals(s.abandon(tk), Right(false), "an old ticket never deletes a new record")
    assertEquals(s.begin("client-a", "key-1", reqB, tokC), Right(IdempotencyStart.InProgress))
    assertEquals(s.complete(tk2, ok), Right(()))
    assertEquals(s.abandon(tk2), Right(false), "a completed record is not abandoned")
    assertEquals(s.begin("client-a", "key-1", reqB, tokC), Right(IdempotencyStart.Replay(ok)))
  }

  t("idempotency DESIGN DECISION: an in-flight record frees its key after the crash bound") {
    val w = world(backend)
    val s = w.stores.idempotency
    val a = fresh(s.begin("client-a", "key-1", reqA, tokA))
    w.clock.advance(InFlightTtl * 1000 - 1000)
    assertEquals(s.begin("client-a", "key-1", reqA, tokB), Right(IdempotencyStart.InProgress))
    w.clock.advance(1000)
    val b = fresh(s.begin("client-a", "key-1", reqA, tokB)) // the documented re-execution window
    assertEquals(b.record.version, a.record.version + 1, "replaced, not recreated")
    assertEquals(
      s.begin("client-a", "key-1", reqA, tokA),
      Right(IdempotencyStart.InProgress),
      "the outlived request's own retry does not take the key back"
    )
    assertEquals(kind(s.complete(a, ok)), "StaleVersion", "the outlived request writes nothing")
    assertEquals(s.abandon(a), Right(false))
    assertEquals(s.complete(b, err), Right(()))
    assertEquals(s.begin("client-a", "key-1", reqA, tokC), Right(IdempotencyStart.Replay(err)))
  }

  t("idempotency: a completed record expires after the completed TTL") {
    val w = world(backend)
    val s = w.stores.idempotency
    s.complete(fresh(s.begin("client-a", "key-1", reqA, tokA)), ok)
    w.clock.advance(CompletedTtl * 1000 - 1000)
    assertEquals(s.begin("client-a", "key-1", reqB, tokB), Right(IdempotencyStart.Mismatch))
    w.clock.advance(1000)
    fresh(s.begin("client-a", "key-1", reqB, tokB))
  }

  t("idempotency: outcomes are bounded JSON; a refused outcome leaves the key in flight") {
    val w = world(backend)
    val s = w.stores.idempotency
    val tk = fresh(s.begin("client-a", "key-1", reqA, tokA))
    assertEquals(kind(s.complete(tk, "{not json")), "Refused")
    assertEquals(kind(s.complete(tk, "\"" + "y" * 5000 + "\"")), "Refused")
    assertEquals(s.begin("client-a", "key-1", reqA, tokB), Right(IdempotencyStart.InProgress))
    assertEquals(kind(s.begin("client a", "key-1", reqA, tokB)), "Refused")
    assertEquals(kind(s.begin("client-a", "key 1", reqA, tokB)), "Refused")
    assertEquals(kind(s.begin("client-a", "key-1", "short", tokB)), "Refused")
  }

  t("idempotency: the attempt ID is required, a token, and at least 16 characters") {
    val w = world(backend)
    val s = w.stores.idempotency
    Vector(null, "", "short-token", "a" * 15, "has space attempt-0001", "a" * 129).foreach { a =>
      assertEquals(kind(s.begin("client-a", "key-1", reqA, a)), "Refused", String.valueOf(a))
    }
    assertEquals(w.kv.putIfAbsentCalls.get, 0, "nothing written")
    fresh(s.begin("client-a", "key-1", reqA, "a" * 16))
    fresh(s.begin("client-a", "key-2", reqA, AttemptTokens.secureRandom().next()))
  }

  t("idempotency: lost races during begin are retried a bounded number of times") {
    val w = world(backend, settings(maxConflictRetries = 2))
    val s = w.stores.idempotency
    fresh(s.begin("client-a", "key-1", reqA, tokA))
    w.clock.advance(InFlightTtl * 1000)
    w.kv.staleNext.set(5)
    assertEquals(kind(s.begin("client-a", "key-1", reqA, tokB)), "StaleVersion")
    assertEquals(w.kv.putIfVersionCalls.get, 3, "1 + maxConflictRetries replacements tried")
  }

  // ------------------------------------------------- CR-INT-01-1: ambiguous-write recovery

  t("CR-INT-01-1: begin applies, then loses its response; the same attempt recovers Fresh") {
    val w = world(backend)
    val s = w.stores.idempotency
    w.kv.appliedThenTransient.set(1)
    assertEquals(kind(s.begin("client-a", "key-1", reqA, tokA)), "Transient", "applied, lost")
    w.clock.advance(250) // the retry comes later, so its own new record would differ
    val tk = fresh(s.begin("client-a", "key-1", reqA, tokA))
    assertEquals(tk.record.attemptId, tokA)
    assertEquals(tk.record.createdAtMillis, Start, "the ticket is the stored record")
    assertEquals(
      s.begin("client-a", "key-1", reqA, tokB),
      Right(IdempotencyStart.InProgress),
      "an independent caller stays distinct: no second holder"
    )
    assertEquals(s.begin("client-a", "key-1", reqB, tokA), Right(IdempotencyStart.Mismatch))
    val again = fresh(s.begin("client-a", "key-1", reqA, tokA))
    assertEquals(again.recordSha256, tk.recordSha256, "the same ticket, not a new record")
    assertEquals(s.complete(tk, ok), Right(()))
    assertEquals(s.begin("client-a", "key-1", reqA, tokB), Right(IdempotencyStart.Replay(ok)))
    assertEquals(s.begin("client-a", "key-1", reqA, tokA), Right(IdempotencyStart.Replay(ok)))
    assertEquals(w.kv.putIfVersionCalls.get, 1, "one completion write")
  }

  t("CR-INT-01-1: across lost responses and interleaved callers, one attempt ever holds the key") {
    val w = world(backend)
    val s = w.stores.idempotency
    w.kv.appliedThenTransient.set(1)
    val order = Vector(tokA, tokB, tokC, tokA, tokB, tokA, tokC)
    val out = order.map(a => a -> s.begin("client-a", "key-1", reqA, a))
    assertEquals(kind(out.head._2), "Transient")
    val holders = out.collect { case (a, Right(IdempotencyStart.Fresh(_))) => a }.toSet
    assertEquals(holders, Set(tokA))
    assertEquals(
      out.collect { case (a, Right(IdempotencyStart.InProgress)) => a }.toSet,
      Set(tokB, tokC)
    )
    assertEquals(w.kv.putIfVersionCalls.get, 0, "no replacement of a live record")
  }

  t("CR-INT-01-1: an expired record's replacement applies, then loses its response") {
    val w = world(backend)
    val s = w.stores.idempotency
    val a = fresh(s.begin("client-a", "key-1", reqA, tokA))
    w.clock.advance(InFlightTtl * 1000)
    w.kv.appliedThenTransient.set(1)
    assertEquals(kind(s.begin("client-a", "key-1", reqA, tokB)), "Transient")
    val b = fresh(s.begin("client-a", "key-1", reqA, tokB))
    assertEquals(b.record.version, a.record.version + 1, "the applied replacement, recovered")
    assertEquals(s.begin("client-a", "key-1", reqA, tokA), Right(IdempotencyStart.InProgress))
    assertEquals(s.begin("client-a", "key-1", reqA, tokC), Right(IdempotencyStart.InProgress))
    assertEquals(kind(s.complete(a, ok)), "StaleVersion")
    assertEquals(s.complete(b, ok), Right(()))
    assertEquals(w.kv.putIfVersionCalls.get, 2, "one replacement, one completion")
  }

  t("CR-INT-01-1: a replacement whose resend fails its condition is recovered inside begin") {
    val w = world(backend)
    val s = w.stores.idempotency
    val a = fresh(s.begin("client-a", "key-1", reqA, tokA))
    w.clock.advance(InFlightTtl * 1000)
    w.kv.appliedThenResent.set(1)
    val b = fresh(s.begin("client-a", "key-1", reqA, tokB)) // one call
    assertEquals(b.record.version, a.record.version + 1)
    assertEquals(s.begin("client-a", "key-1", reqA, tokC), Right(IdempotencyStart.InProgress))
  }

  t("CR-INT-01-1: complete applies, then loses its response; the retry is Right, writes nothing") {
    val w = world(backend)
    val s = w.stores.idempotency
    val tk = fresh(s.begin("client-a", "key-1", reqA, tokA))
    w.kv.appliedThenTransient.set(1)
    assertEquals(kind(s.complete(tk, ok)), "Transient", "applied, lost")
    w.clock.advance(1000) // the retry's completion would carry other timestamps
    assertEquals(s.complete(tk, ok), Right(()), "idempotent: the stored completion is this one")
    assertEquals(w.kv.putIfVersionCalls.get, 1, "the retry wrote nothing")
    assertEquals(kind(s.complete(tk, err)), "Conflict", "the same attempt, another outcome")
    assertEquals(s.begin("client-a", "key-1", reqA, tokB), Right(IdempotencyStart.Replay(ok)))
    assertEquals(s.abandon(tk), Right(false), "a completed record is not abandoned")
  }

  t("CR-INT-01-1: complete whose resend fails its condition is Right after a re-read") {
    val w = world(backend)
    val s = w.stores.idempotency
    val tk = fresh(s.begin("client-a", "key-1", reqA, tokA))
    w.kv.appliedThenResent.set(1)
    assertEquals(s.complete(tk, ok), Right(()))
    assertEquals(s.begin("client-a", "key-1", reqA, tokB), Right(IdempotencyStart.Replay(ok)))
  }

  t("CR-INT-01-1: abandon applies, then loses its response; the retry is false, the key free") {
    val w = world(backend)
    val s = w.stores.idempotency
    val tk = fresh(s.begin("client-a", "key-1", reqA, tokA))
    w.kv.appliedThenTransient.set(1)
    assertEquals(kind(s.abandon(tk)), "Transient")
    assertEquals(s.abandon(tk), Right(false), "documented: the applied delete is not reported")
    fresh(s.begin("client-a", "key-1", reqA, tokB))
  }

  t("CR-INT-01-1: a ticket of a replaced record never completes another attempt's record") {
    val w = world(backend)
    val s = w.stores.idempotency
    val a = fresh(s.begin("client-a", "key-1", reqA, tokA))
    w.clock.advance(InFlightTtl * 1000)
    val b = fresh(s.begin("client-a", "key-1", reqA, tokB))
    assertEquals(s.complete(b, ok), Right(()))
    assertEquals(
      kind(s.complete(a, ok)),
      "StaleVersion",
      "the same outcome, but another attempt's completion: not idempotent for the old ticket"
    )
  }

  // ------------------------------------------------- CR-INT-01-2: encoded item bound

  private val Big = settings(maxPayload = ServiceStateBounds.CeilingBytes)

  /** A JSON string dense in escaped backslashes (raw 2 bytes each, 4 in the encoded body). */
  private def backslashDense(n: Int) = "\"" + "\\\\" * 60000 + "x" * n + "\""

  /** A JSON array dense in empty strings (raw 3 bytes each, 5 in the encoded body). */
  private def quoteDense(n: Int) = "[" + "\"\"," * 60000 + "\"" + "x" * n + "\"]"

  private def jobItem(w: World): Item =
    w.kv.get(TableRole.QueryJob, TableKeys.QueryJob.job("job-1").toOption.get).toOption.flatten.get

  private def idemItem(w: World): Item = {
    val d = ServiceStateIds.idempotencyKeySha256("client-a", "key-1").toOption.get
    w.kv
      .get(TableRole.IdempotencyKey, TableKeys.IdempotencyKey.request(d).toOption.get)
      .toOption
      .flatten
      .get
  }

  private def boundary[S](
      shape: Int => String,
      setup: World => S,
      write: (World, S, String) => Either[StoreError, ?],
      stored: World => Item,
      intact: World => Unit
  ): Unit = {
    def run(n: Int) = {
      val w = world(backend, Big); val st = setup(w); (w, write(w, st, shape(n)))
    }
    val (w0, r0) = run(0)
    assert(r0.isRight, r0.toString)
    val s0 = ServiceStateBounds.itemBytes(stored(w0))
    val n = (ServiceStateBounds.MaxItemBytes - s0).toInt
    assert(n > 0, s"baseline $s0")
    val (w1, r1) = run(n)
    assert(r1.isRight, r1.toString)
    assertEquals(ServiceStateBounds.itemBytes(stored(w1)), ServiceStateBounds.MaxItemBytes.toLong)
    val (w2, r2) = run(n + 1)
    assertEquals(kind(r2), "Refused", "one byte over the encoded bound")
    intact(w2)
    val over = shape(n + 1)
    assert(ServiceStateBounds.bytes(over) <= ServiceStateBounds.CeilingBytes)
    assertEquals(
      ServiceStateBounds.problem("p", over, ServiceStateBounds.CeilingBytes),
      None,
      "the raw payload bound alone would have accepted it"
    )
  }

  private def jobSetup(w: World): Unit = {
    w.stores.jobs.create("job-1", qA, "client-a", "queued")
    w.stores.jobs.start("job-1", "run")
  }

  private def jobIntact(w: World): Unit = {
    assertEquals(w.stores.jobs.get("client-a", "job-1").map(_.map(_.state)), Right(Some("running")))
    assertEquals(w.kv.putIfVersionCalls.get, 1, "only the start was written")
  }

  private def idemIntact(w: World): Unit = {
    assertEquals(w.kv.putIfVersionCalls.get, 0, "nothing written")
    assertEquals(
      w.stores.idempotency.begin("client-a", "key-1", reqA, tokB),
      Right(IdempotencyStart.InProgress)
    )
  }

  t("CR-INT-01-2: a backslash-dense job result is bounded by its encoded item, at the boundary") {
    boundary(
      backslashDense,
      jobSetup,
      (w, _, p) => w.stores.jobs.succeed("job-1", "completed", p),
      jobItem,
      jobIntact
    )
  }

  t("CR-INT-01-2: a quote-dense job error is bounded by its encoded item, at the boundary") {
    boundary(
      quoteDense,
      jobSetup,
      (w, _, p) => w.stores.jobs.fail("job-1", "failed", p),
      jobItem,
      jobIntact
    )
  }

  t("CR-INT-01-2: a quote-dense replay outcome is bounded by its encoded item, at the boundary") {
    boundary(
      quoteDense,
      w => fresh(w.stores.idempotency.begin("client-a", "key-1", reqA, tokA)),
      (w, tk, p) => w.stores.idempotency.complete(tk, p),
      idemItem,
      idemIntact
    )
  }

  t("CR-INT-01-2: a backslash-dense replay outcome is bounded by its encoded item") {
    boundary(
      backslashDense,
      w => fresh(w.stores.idempotency.begin("client-a", "key-1", reqA, tokA)),
      (w, tk, p) => w.stores.idempotency.complete(tk, p),
      idemItem,
      idemIntact
    )
  }

  // ------------------------------------------------- CR-INT-01-4: clock-skew allowance

  t("CR-INT-01-4: the skew allowance delays the expiry of in-flight records") {
    val w = world(backend, settings(skewMillis = 5000))
    val s = w.stores.idempotency
    val a = fresh(s.begin("client-a", "key-1", reqA, tokA))
    w.clock.advance(InFlightTtl * 1000)
    assertEquals(s.begin("client-a", "key-1", reqA, tokB), Right(IdempotencyStart.InProgress))
    w.clock.advance(4999)
    assertEquals(s.begin("client-a", "key-1", reqA, tokB), Right(IdempotencyStart.InProgress))
    w.clock.advance(1)
    assertEquals(
      fresh(s.begin("client-a", "key-1", reqA, tokB)).record.version,
      a.record.version + 1
    )
  }

  t("CR-INT-01-4: the skew allowance keeps a completed record replayable for its full window") {
    val w = world(backend, settings(skewMillis = 5000))
    val s = w.stores.idempotency
    s.complete(fresh(s.begin("client-a", "key-1", reqA, tokA)), ok)
    w.clock.advance(CompletedTtl * 1000 + 4999)
    assertEquals(s.begin("client-a", "key-1", reqA, tokB), Right(IdempotencyStart.Replay(ok)))
    w.clock.advance(1)
    fresh(s.begin("client-a", "key-1", reqA, tokB))
  }

  t("CR-INT-01-4: a replica whose clock runs ahead never replaces a live record early") {
    val kv = new CountingKv(backend.kv())
    val skew = 5000L
    val ca = new ManualClock(Start)
    val cb = new ManualClock(Start + skew) // replica B's clock runs 5 s ahead of A's
    val a = ServiceStateStores(kv, ca, settings(skewMillis = skew)).idempotency
    val b = ServiceStateStores(kv, cb, settings(skewMillis = skew)).idempotency
    val b0 = ServiceStateStores(kv, cb, settings()).idempotency // control: no allowance
    fresh(a.begin("client-a", "key-1", reqA, tokA))
    fresh(a.begin("client-a", "key-2", reqA, tokA))
    def both(ms: Long): Unit = { ca.advance(ms); cb.advance(ms) }
    both(InFlightTtl * 1000 - skew) // true time: 5 s inside the in-flight bound
    fresh(b0.begin("client-a", "key-1", reqA, tokB)) // the finding: replaced 5 s early
    assertEquals(b.begin("client-a", "key-2", reqA, tokB), Right(IdempotencyStart.InProgress))
    both(skew - 1)
    assertEquals(b.begin("client-a", "key-2", reqA, tokB), Right(IdempotencyStart.InProgress))
    both(1) // true elapsed time is now exactly the in-flight TTL
    fresh(b.begin("client-a", "key-2", reqA, tokB))
  }

  t("CR-INT-01-4: an in-flight expiresAt is rounded up to the next epoch second") {
    val w = world(backend)
    val s = w.stores.idempotency
    w.clock.advance(500)
    fresh(s.begin("client-a", "key-1", reqA, tokA))
    val ttl = idemItem(w).get(Attrs.ExpiresAt).collect { case AttrValue.N(n) => n.toLong }
    assertEquals(ttl, Some((Start + 500) / 1000 + InFlightTtl + 1))
    w.clock.advance(InFlightTtl * 1000 - 1) // floor rounding would have expired 500 ms ago
    assertEquals(s.begin("client-a", "key-1", reqA, tokB), Right(IdempotencyStart.InProgress))
  }

  // ------------------------------------------------- SE-INT-03 (CR-INT-02-3)

  private def expiresAt(w: World, role: TableRole, k: scholarchain.storage.model.PrimaryKey) =
    w.kv.get(role, k).toOption.flatten.flatMap(_.get(Attrs.ExpiresAt)).collect {
      case AttrValue.N(n) => n.toLong
    }

  private def ownerKey = TableKeys.QueryOwnership.owner(qA).toOption.get
  private def jobKey = TableKeys.QueryJob.job("job-1").toOption.get

  t("CR-INT-02-3: an owner row is live until expiresAt plus the skew allowance, exactly") {
    val w = world(backend, settings(skewMillis = 5000))
    w.stores.ownership.record(qA, "client-a")
    w.clock.advance(OwnerTtl * 1000 + 4999)
    assertEquals(w.stores.ownership.owner(qA), Right(Some("client-a")))
    assertEquals(w.stores.ownership.ownedBy("client-a", Vector(qA)), Right(Set(qA)))
    w.clock.advance(1)
    assertEquals(w.stores.ownership.owner(qA), Right(None))
    assertEquals(w.stores.ownership.ownedBy("client-a", Vector(qA)), Right(Set.empty[String]))
  }

  t("CR-INT-02-3: a job row is live, and transitions, until expiresAt plus the skew, exactly") {
    val w = world(backend, settings(skewMillis = 5000))
    w.stores.jobs.create("job-1", qA, "client-a", "queued")
    w.stores.jobs.create("job-2", qB, "client-a", "queued")
    w.clock.advance(JobTtl * 1000 + 4999)
    assert(w.stores.jobs.get("client-a", "job-1").exists(_.isDefined), "inside the allowance")
    assertEquals(w.stores.jobs.start("job-1", "run").map(_.state), Right(JobStates.Running))
    w.clock.advance(1)
    assertEquals(w.stores.jobs.get("client-a", "job-2"), Right(None))
    assertEquals(kind(w.stores.jobs.start("job-2", "run")), "Permanent", "expired: not updated")
  }

  t("CR-INT-02-3: a completed record is replayable until expiresAt plus the skew, exactly") {
    val w = world(backend, settings(skewMillis = 5000))
    val s = w.stores.idempotency
    s.complete(fresh(s.begin("client-a", "key-1", reqA, tokA)), ok)
    w.clock.advance(CompletedTtl * 1000 + 4999)
    assertEquals(s.begin("client-a", "key-1", reqB, tokB), Right(IdempotencyStart.Mismatch))
    w.clock.advance(1)
    fresh(s.begin("client-a", "key-1", reqB, tokB))
  }

  t("CR-INT-02-3: a replica whose clock runs ahead never treats a live owner or job as expired") {
    val kv = new CountingKv(backend.kv())
    val skew = 5000L
    val ca = new ManualClock(Start)
    val cb = new ManualClock(Start + skew) // replica B runs 5 s ahead of the writer A
    val a = ServiceStateStores(kv, ca, settings(skewMillis = skew))
    val b = ServiceStateStores(kv, cb, settings(skewMillis = skew))
    val b0 = ServiceStateStores(kv, cb, settings()) // control: no allowance
    a.ownership.record(qA, "client-a")
    a.jobs.create("job-1", qA, "client-a", "queued")
    def both(ms: Long): Unit = { ca.advance(ms); cb.advance(ms) }
    // Jobs (TTL 1800 s): true time 1 ms before the writer's expiry.
    both(JobTtl * 1000 - 1)
    assertEquals(b0.jobs.get("client-a", "job-1"), Right(None), "the finding: missing 5 s early")
    assert(b.jobs.get("client-a", "job-1").exists(_.isDefined), "live for the ahead replica")
    assertEquals(b.jobs.start("job-1", "run").map(_.state), Right(JobStates.Running))
    // The start refreshed the job; the owner row (TTL 3600 s) is next.
    both(OwnerTtl * 1000 - JobTtl * 1000)
    assertEquals(b0.ownership.owner(qA), Right(None), "the finding: missing 5 s early")
    assertEquals(b.ownership.owner(qA), Right(Some("client-a")))
    assertEquals(b.ownership.ownedBy("client-a", Vector(qA)), Right(Set(qA)))
    both(1) // true time is now exactly the owner row's expiry
    assertEquals(b.ownership.owner(qA), Right(None))
    assertEquals(a.ownership.owner(qA), Right(Some("client-a")), "the writer itself allows skew")
  }

  t("CR-INT-02-3: owner, job and completed expiresAt are rounded up to the next epoch second") {
    val w = world(backend)
    val s = w.stores.idempotency
    w.clock.advance(500) // Start + 500 ms
    w.stores.ownership.record(qA, "client-a")
    w.stores.jobs.create("job-1", qA, "client-a", "queued")
    val tk = fresh(s.begin("client-a", "key-1", reqA, tokA))
    s.complete(tk, ok)
    val sec = (Start + 500) / 1000
    assertEquals(expiresAt(w, TableRole.QueryOwnership, ownerKey), Some(sec + OwnerTtl + 1))
    assertEquals(expiresAt(w, TableRole.QueryJob, jobKey), Some(sec + JobTtl + 1))
    assertEquals(idemItem(w).get(Attrs.ExpiresAt), Some(AttrValue.n(sec + CompletedTtl + 1)))
    // Floor rounding would have expired each row 500 ms before its TTL elapsed.
    w.clock.advance(CompletedTtl * 1000 - 1)
    assertEquals(s.begin("client-a", "key-1", reqB, tokB), Right(IdempotencyStart.Mismatch))
    w.clock.advance(JobTtl * 1000 - CompletedTtl * 1000)
    assert(w.stores.jobs.get("client-a", "job-1").exists(_.isDefined), "job TTL fully elapsed")
    w.clock.advance(OwnerTtl * 1000 - JobTtl * 1000)
    assertEquals(w.stores.ownership.owner(qA), Right(Some("client-a")), "owner TTL fully elapsed")
    w.clock.advance(500)
    assertEquals(w.stores.ownership.owner(qA), Right(Some("client-a")), "1 ms before the second")
    w.clock.advance(1)
    assertEquals(w.stores.ownership.owner(qA), Right(None), "and expired on the rounded second")
  }

  t("CR-INT-02-3: a transition's expiresAt is rounded up from its update time") {
    val w = world(backend)
    w.stores.jobs.create("job-1", qA, "client-a", "queued")
    w.clock.advance(1500)
    w.stores.jobs.start("job-1", "run")
    assertEquals(expiresAt(w, TableRole.QueryJob, jobKey), Some(Start / 1000 + 1 + JobTtl + 1))
    w.clock.advance(JobTtl * 1000 - 1)
    assertEquals(w.stores.jobs.succeed("job-1", "completed", ok).map(_.state), Right("succeeded"))
  }

  t("CR-INT-02-3: the reviewer's input: a finish within the workflow timeout is not lost") {
    // jobTtl 5 s against a 4999 ms workflow timeout; start at ...10.999 s, succeed at ...15.998 s.
    val st = ServiceStateSettings
      .of(Writer, OwnerTtl, 5, CompletedTtl, InFlightTtl, 4096, 3, 250, 4999, 0, 0, 0)
      .toOption
      .get
    val w = world(backend, st)
    w.clock.advance(10000)
    w.stores.jobs.create("job-1", qA, "client-a", "queued")
    w.clock.advance(999)
    assertEquals(w.stores.jobs.start("job-1", "run").map(_.state), Right(JobStates.Running))
    assertEquals(expiresAt(w, TableRole.QueryJob, jobKey), Some(Start / 1000 + 16), "not ...15")
    w.clock.advance(4999)
    assertEquals(w.stores.jobs.succeed("job-1", "completed", ok).map(_.state), Right("succeeded"))
  }

  // ------------------------------------------------------------------ every row

  t("every row carries expiresAt, schemaVersion and writer provenance; no raw key is stored") {
    val w = world(backend)
    w.stores.ownership.record(qA, "client-a")
    w.stores.jobs.create("job-1", qA, "client-a", "queued")
    fresh(w.stores.idempotency.begin("client-a", "RAW-key-4242", reqA, tokA))
    val digest = ServiceStateIds.idempotencyKeySha256("client-a", "RAW-key-4242").toOption.get
    val rows = Vector(
      TableRole.QueryOwnership -> TableKeys.QueryOwnership.owner(qA),
      TableRole.QueryJob -> TableKeys.QueryJob.job("job-1"),
      TableRole.IdempotencyKey -> TableKeys.IdempotencyKey.request(digest)
    ).map { case (role, k) => role -> w.kv.get(role, k.toOption.get).toOption.flatten.get }
    rows.foreach { case (role, item) =>
      val ttl = item.get(Attrs.ExpiresAt).collect { case AttrValue.N(n) => n.toLong }
      val expected = role match {
        case TableRole.QueryOwnership => Start / 1000 + OwnerTtl
        case TableRole.QueryJob => Start / 1000 + JobTtl
        case _ => Start / 1000 + InFlightTtl
      }
      assertEquals(ttl, Some(expected), role.tag)
      assert(item.contains(Attrs.SchemaVersion), role.tag)
      assert(item(Attrs.Body).toString.contains(Writer), s"${role.tag}: writer provenance")
    }
    val idem = rows.last._2
    assert(!idem.toString.contains("RAW-key-4242"), "the raw key is never stored")
    assert(!idem.toString.contains("client-a"), "the client ID is not stored either")
    assert(idem(Attrs.Body).toString.contains(IdempotencyStates.InFlight))
  }
}

class ServiceStateStoresFakeSuite extends ServiceStateStoresContract(Backend.Fake)
class ServiceStateStoresDynamoSuite extends ServiceStateStoresContract(Backend.Dynamo)
