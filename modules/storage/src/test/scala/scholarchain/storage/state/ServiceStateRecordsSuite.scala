package scholarchain.storage.state

import scholarchain.core.Canonical
import scholarchain.storage.model.*
import StateWorld.*

/** SE-INT-01 items 3-5: service-state records, codecs, digests, payload bounds and settings. */
class ServiceStateRecordsSuite extends munit.FunSuite {
  private val QueryOwnerCodec = ServiceStateCodecs.QueryOwnerCodec
  private val QueryJobCodec = ServiceStateCodecs.QueryJobCodec
  private val IdempotencyCodec = ServiceStateCodecs.IdempotencyCodec

  private val owner =
    QueryOwnerRecord(
      QueryOwnerRecord.SchemaVersion,
      qA,
      "client-a",
      Writer,
      Start,
      Start / 1000 + 60
    )

  private val job = QueryJobRecord(
    QueryJobRecord.SchemaVersion,
    "job-1",
    qA,
    "client-a",
    JobStates.Succeeded,
    "completed",
    Start,
    Some(Start + 1),
    Some(Start + 2),
    Some(ok),
    None,
    3L,
    Writer,
    Start + 2,
    Start / 1000 + 60
  )

  private val idem = IdempotencyRecord(
    IdempotencyRecord.SchemaVersion,
    "c" * 64,
    reqA,
    IdempotencyStates.Completed,
    "attempt-1",
    Some(ok),
    Start,
    Some(Start + 5),
    Start + 1,
    Writer,
    Start / 1000 + 600
  )

  private def roundTrip[A](codec: ItemCodec[A], a: A): Item = {
    val item = codec.encode(a).toOption.get
    assertEquals(codec.decode(item), Right(a))
    item
  }

  test("codec round trips; key, TTL, version and schema attributes on every row") {
    val o = roundTrip(QueryOwnerCodec, owner)
    assertEquals(o("pk"), AttrValue.S(s"query#$qA"))
    assertEquals(o("sk"), AttrValue.S("owner"))
    assertEquals(o(Attrs.ExpiresAt), AttrValue.n(Start / 1000 + 60))
    assert(!o.contains(Attrs.Version), "owner rows are create-once, unversioned")
    val j = roundTrip(QueryJobCodec, job)
    assertEquals((j("pk"), j(Attrs.Version)), (AttrValue.S("job#job-1"), AttrValue.n(3)))
    val i = roundTrip(IdempotencyCodec, idem)
    assertEquals(i("pk"), AttrValue.S("idem#" + "c" * 64))
    assertEquals(i(Attrs.SchemaVersion), AttrValue.S("scholarchain.storage.idempotency-key.v1"))
    Vector(o, j, i).foreach(x =>
      assert(x.contains(Attrs.ExpiresAt) && x.contains(Attrs.RecordSha256))
    )
  }

  test("a tampered body, hash, key or TTL never decodes") {
    val item = QueryJobCodec.encode(job).toOption.get
    val body = item(Attrs.Body).asInstanceOf[AttrValue.S].value
    val forged = body.replace("client-a", "client-b")
    // A body whose job ID no longer matches the item key, with a consistent hash.
    val moved = body.replace("\"job-1\"", "\"job-2\"")
    assertNotEquals(moved, body)
    val cases = Vector(
      item + (Attrs.Body -> AttrValue.S(forged)),
      item + (Attrs.Body -> AttrValue.S(moved)) +
        (Attrs.RecordSha256 -> AttrValue.S(Canonical.sha256HexUtf8(moved))),
      item + ("pk" -> AttrValue.S("job#job-2")),
      item + (Attrs.ExpiresAt -> AttrValue.n(1)),
      item + (Attrs.Version -> AttrValue.n(4)),
      item - Attrs.ExpiresAt
    )
    cases.zipWithIndex.foreach { case (c, n) =>
      assert(QueryJobCodec.decode(c).left.exists(_.isInstanceOf[StoreError.Corrupt]), s"case $n")
    }
  }

  test("job rows must be state-consistent") {
    def bad(j: QueryJobRecord) = assert(QueryJobCodec.encode(j).isLeft, j.toString)
    bad(job.copy(state = "paused"))
    bad(job.copy(resultJson = None))
    bad(job.copy(errorJson = Some(err)))
    bad(job.copy(finishedAtMillis = None))
    bad(job.copy(state = JobStates.Running, resultJson = None)) // finished but running
    bad(
      job.copy(
        state = JobStates.Queued,
        startedAtMillis = Some(1),
        finishedAtMillis = None,
        resultJson = None
      )
    )
    bad(job.copy(version = 0))
    bad(job.copy(resultJson = Some("{broken")))
    bad(job.copy(resultJson = null))
    bad(job.copy(clientId = "has space"))
    bad(job.copy(queryId = "run:x"))
    bad(job.copy(writer = ""))
    bad(job.copy(updatedAtMillis = Start - 1))
    val failedQueued = job.copy(
      state = JobStates.Failed,
      startedAtMillis = None,
      resultJson = None,
      errorJson = Some(err)
    )
    assert(QueryJobCodec.encode(failedQueued).isRight, "queued -> failed has no start time")
  }

  test("idempotency and owner rows are validated") {
    def bad(i: IdempotencyRecord) = assert(IdempotencyCodec.encode(i).isLeft, i.toString)
    bad(idem.copy(keySha256 = "raw-key"))
    bad(idem.copy(requestSha256 = "x"))
    bad(idem.copy(state = "done"))
    bad(idem.copy(outcomeJson = None))
    bad(idem.copy(state = IdempotencyStates.InFlight))
    bad(idem.copy(completedAtMillis = null))
    bad(idem.copy(attemptId = "a b"))
    bad(idem.copy(expiresAtEpochSeconds = 0))
    assert(QueryOwnerCodec.encode(owner.copy(queryId = "query:")).isLeft)
    assert(QueryOwnerCodec.encode(owner.copy(clientId = "c" * 129)).isLeft)
    assert(QueryOwnerCodec.encode(owner.copy(schemaVersion = "v0")).isLeft)
  }

  test("the idempotency digest is length-prefixed: no two (client, key) pairs share one") {
    val d = ServiceStateIds.idempotencyKeySha256
    assertNotEquals(d("ab", "c"), d("a", "bc"))
    assertNotEquals(d("client-a", "k"), d("client-b", "k"))
    assertEquals(d("client-a", "k"), d("client-a", "k"))
    assertEquals(
      d("ab", "c"),
      Right(Canonical.sha256HexUtf8("scholarchain.storage.idempotency-key-digest.v1\n2:ab\n1:c"))
    )
    assert(d("a b", "c").isLeft)
    assert(d("a", "").isLeft)
    assert(d("a", "k" * 129).isLeft)
    assert(d("a", "k").exists(Canonical.isHex64))
  }

  test("payloads: one well-formed JSON value within the configured bound and the ceiling") {
    val p = ServiceStateBounds.problem
    assertEquals(p("f", ok, 4096), None)
    assert(p("f", "", 4096).isDefined)
    assert(p("f", null, 4096).isDefined)
    assert(p("f", "{\"a\":", 4096).isDefined)
    assert(p("f", "\"" + "x" * 10 + "\"", 5).isDefined)
    val big = "\"" + "x" * ServiceStateBounds.CeilingBytes + "\""
    assert(p("f", big, Int.MaxValue).isDefined, "the storage ceiling applies whatever the bound")
  }

  private def of(
      inFlight: Long,
      longest: Long,
      drain: Long,
      jobTtl: Long = 1800,
      skew: Long = 0,
      req: Long = 0
  ) =
    ServiceStateSettings
      .of(Writer, 3600, jobTtl, 600, inFlight, 4096, 3, 250, longest, drain, skew, req)

  test("settings: the crash bound must exceed the longest workflow timeout plus drain") {
    assert(of(66, 60000, 5000).isRight)
    assert(of(65, 60000, 5000).isLeft, "65 s is not above 65 s")
    assert(of(120, 60000, 5000, jobTtl = 60).isLeft, "job TTL must exceed the workflow timeout")
    assert(of(120, 0, 0).isLeft)
    assert(of(120, 60000, -1).isLeft)
    assert(of(0, 60000, 5000).isLeft)
    assert(of(ServiceStateSettings.MaxTtlSeconds + 1, 60000, 5000).isLeft)
    def full(w: String, payload: Int, retries: Int, ownedBy: Int) =
      ServiceStateSettings.of(w, 3600, 1800, 600, 120, payload, retries, ownedBy, 60000, 0, 0, 0)
    assert(full(Writer, 4096, 3, 250).isRight, "control")
    assert(full("bad writer", 4096, 3, 250).isLeft)
    assert(full(Writer, 0, 3, 250).isLeft)
    assert(full(Writer, 262145, 3, 250).isLeft)
    assert(full(Writer, 4096, 0, 250).isLeft)
    assert(full(Writer, 4096, 3, 0).isLeft)
  }

  // ------------------------------------------------------------------ SE-INT-03

  test("SE-INT-03 (CR-INT-02-1): the caller's in-flight requirement is checked, strictly") {
    assert(of(120, 60000, 5000, req = 119999).isRight)
    assertEquals(
      of(120, 60000, 5000, req = 120000).left.map(_.message),
      Left(
        "inFlightTtlSeconds: must exceed the caller's in-flight requirement " +
          "(inFlightRequirementMillis)"
      ),
      "not above the requirement"
    )
    assert(of(120, 60000, 5000, req = 500000).isLeft)
    assert(of(120, 60000, 5000, req = -1).isLeft, "a negative requirement is refused")
    assert(of(120, 60000, 5000, req = Long.MaxValue).isLeft, "no overflow: a comparison only")
    assert(of(120, 60000, 5000, req = Long.MinValue).isLeft)
    assertEquals(of(120, 60000, 5000, req = 0).map(_.inFlightRequirementMillis), Right(0L))
    assert(of(65, 60000, 5000, req = 0).isLeft, "0 leaves the storage floor in force")
    assertEquals(of(120, 60000, 5000, req = 90000).map(_.inFlightRequirementMillis), Right(90000L))
  }

  /** Core's ST-2 check, by core's own formula (storage does not copy it). */
  private def coreSt2(
      inFlight: Long,
      rr: Long,
      qw: Long,
      sync: Long,
      async: Long,
      dr: Long,
      sk: Long
  ): (Boolean, Long) = {
    val req =
      scholarchain.core.config.TimingBudgets.inFlightRequirementMillis(rr, qw, sync, async, dr, sk)
    (inFlight * 1000 > req, req)
  }

  test("SE-INT-03 (CR-INT-02-1): storage accepts exactly the in-flight TTLs core ST-2 accepts") {
    // Core's own requirement for these inputs: 5000 + 1000 + 60000 + 5000 + 5000 + 2000 = 78000.
    val (ok78, req) = coreSt2(78, 5000, 1000, 60000, 30000, 5000, 2000)
    assertEquals(req, 78000L)
    assert(!ok78)
    assert(of(78, 60000, 5000, skew = 2000, req = req).isLeft, "78 s refused by both")
    assert(coreSt2(79, 5000, 1000, 60000, 30000, 5000, 2000)._1)
    assert(of(79, 60000, 5000, skew = 2000, req = req).isRight, "79 s accepted by both")
    // A grid: wherever core ST-2 (and ST-1) hold, storage accepts with core's derived inputs.
    for {
      rr <- Vector(0L, 1000L, 5000L)
      qw <- Vector(0L, 1000L)
      sync <- Vector(1L, 30000L, 100000L)
      async <- Vector(1L, 60000L)
      dr <- Vector(0L, 5000L)
      sk <- Vector(0L, 2000L)
      inFlight <- Vector(1L, 70L, 110L, 120L, 200L)
    } {
      val (coreOk, r) = coreSt2(inFlight, rr, qw, sync, async, dr, sk)
      val st1 = sk < inFlight * 1000
      val storage = of(inFlight, math.max(sync, async), dr, jobTtl = 3600, skew = sk, req = r)
      assertEquals(storage.isRight, coreOk && st1, s"in=$inFlight rr=$rr qw=$qw s=$sync a=$async")
    }
  }

  test("SE-INT-03 (CR-INT-02-3): the job-TTL bound is core's ST-3 floor, with no extra margin") {
    // CR-INT-04-1's input: sync 100000 dominates; core ST-3 requires jobTtl * 1000 > 100000.
    import scholarchain.core.config.TimingBudgets.*
    def coreSt3(jobTtl: Long, dr: Long, sk: Long) = {
      val gap = jobGapMillis(5000, 1000, 10000, 1, 4)
      jobTtl * 1000 > jobTtlRequirementMillis(gap, dr, sk, math.max(100000L, 10000L))
    }
    assert(!coreSt3(100, 0, 0) && coreSt3(101, 0, 0))
    assert(of(200, 100000, 0, jobTtl = 100).isLeft, "100 s refused by both")
    assert(of(200, 100000, 0, jobTtl = 101).isRight, "101 s accepted by both")
    // With drain and skew, core still accepts 101 (its margin sits on the job gap term); storage
    // must too: a storage `longest + drain + skew` term would refuse it (CR-INT-04-1).
    assert(coreSt3(101, 5000, 5000))
    assert(of(200, 100000, 5000, jobTtl = 101, skew = 5000).isRight)
    assert(of(200, 100000, 5000, jobTtl = 100, skew = 5000).isLeft)
  }

  test("CR-INT-01-7: settings validation is overflow-safe (Long.MaxValue and Long.MinValue)") {
    val Max = Long.MaxValue
    // Before SE-INT-02, drain = Long.MaxValue overflowed the sum to a negative number and passed.
    assert(of(120, 60000, Max).isLeft, "drain Long.MaxValue")
    assert(of(120, Max, 5000).isLeft, "workflow timeout Long.MaxValue")
    assert(of(120, Max, Max).isLeft, "both Long.MaxValue")
    assert(of(120, 60000, Long.MinValue).isLeft, "drain Long.MinValue")
    assert(of(120, Long.MinValue, 0).isLeft)
    assert(of(Max, 60000, 5000).isLeft, "in-flight TTL Long.MaxValue")
    assert(of(120, 60000, 5000, jobTtl = Max).isLeft, "job TTL Long.MaxValue")
    assert(of(120, 60000, 5000, skew = Max).isLeft, "skew Long.MaxValue")
    assert(of(120, 60000, 5000, skew = Long.MinValue).isLeft)
    val maxTtl = ServiceStateSettings.MaxTtlSeconds
    val maxMs = ServiceStateSettings.MaxDurationMillis
    assertEquals(maxMs, maxTtl * 1000)
    assert(of(maxTtl, maxMs - 1, 0, jobTtl = maxTtl).isRight, "largest accepted values")
    assert(of(maxTtl, maxMs, 0, jobTtl = maxTtl).isLeft, "not above the bound")
    assert(of(maxTtl, maxMs + 1, 0, jobTtl = maxTtl).isLeft, "outside the duration bound")
    assert(of(maxTtl, 1, maxMs + 1, jobTtl = maxTtl).isLeft)
  }

  test("CR-INT-01-4: the clock-skew allowance is caller-supplied and below the in-flight TTL") {
    assertEquals(of(120, 60000, 5000).map(_.maxClockSkewMillis), Right(0L))
    assertEquals(of(120, 60000, 5000, skew = 5000).map(_.maxClockSkewMillis), Right(5000L))
    assert(of(120, 60000, 5000, skew = 119999).isRight)
    assert(of(120, 60000, 5000, skew = 120000).isLeft, "not below the in-flight TTL")
    assert(of(120, 60000, 5000, skew = -1).isLeft)
  }

  test("CR-INT-01-4: liveness with skew saturates instead of overflowing") {
    assert(Rows.live(10, 0, 9999), "expiresAt 10 s is live at 9.999 s")
    assert(!Rows.live(10, 0, 10000), "and expired at 10 s")
    assert(Rows.live(10, 1, 10000), "the allowance extends it")
    assert(!Rows.live(10, 1, 10001))
    assert(Rows.live(Long.MaxValue, 0, Long.MaxValue - 1), "a huge expiresAt is live, not negative")
    assert(Rows.live(Long.MaxValue / 1000 - 1, Long.MaxValue, 0), "a huge skew saturates")
    assert(Rows.live(Long.MaxValue / 1000 - 1, Long.MaxValue, Long.MaxValue - 1))
    assert(!Rows.live(Long.MinValue, 0, 0), "a hugely negative expiresAt is expired")
    assertEquals(Rows.expiryCeil(Start, 120), Start / 1000 + 120)
    assertEquals(Rows.expiryCeil(Start + 1, 120), Start / 1000 + 121)
    assertEquals(Rows.expiryCeil(Start + 999, 120), Start / 1000 + 121)
    assertEquals(Rows.expiryCeil(Start + 1000, 120), Start / 1000 + 121)
    assertEquals(Rows.expiryCeil(Start + 1001, 120), Start / 1000 + 122)
  }

  test("CR-INT-01-1: attempt IDs are tokens of 16 to 128 characters") {
    val a = ServiceStateIds.attemptId
    assert(a("a" * 16).isRight)
    assert(a("a" * 128).isRight)
    assert(a("a" * 15).isLeft)
    assert(a("a" * 129).isLeft)
    assert(a(null).isLeft)
    assert(a("attempt id 000001").isLeft)
    assert(a(AttemptTokens.secureRandom().next()).isRight)
    assertNotEquals(AttemptTokens.secureRandom().next(), AttemptTokens.secureRandom().next())
  }

  test("CR-INT-01-2: itemBytes counts names and values conservatively, in UTF-8 bytes") {
    val item: Item = Map(
      "pk" -> AttrValue.S("ab"), // 2 + 2
      "n" -> AttrValue.N("123"), // 1 + 4
      "b" -> AttrValue.Bool(true), // 1 + 1
      "l" -> AttrValue.L(Vector(AttrValue.S("x"), AttrValue.Null)), // 1 + 3 + 2 + 2
      "m" -> AttrValue.M(Map("k" -> AttrValue.S("vv"))) // 1 + 3 + 1 + 1 + 2
    )
    assertEquals(ServiceStateBounds.itemBytes(item), 27L)
    assertEquals(ServiceStateBounds.itemBytes(Map("é" -> AttrValue.S("é€"))), 7L, "UTF-8")
    assertEquals(ServiceStateBounds.itemProblem("T", item, 27), None)
    assert(ServiceStateBounds.itemProblem("T", item, 26).exists(_.contains("27 bytes")))
    assert(ServiceStateBounds.MaxItemBytes > ServiceStateBounds.CeilingBytes)
  }

  test(
    "CR-INT-01-2: the encoded body escapes quotes and backslashes (why raw bytes are not enough)"
  ) {
    val raw = "[" + "\"\"," * 1000 + "\"\\\\\"]"
    val j = job.copy(resultJson = Some(raw))
    val item = QueryJobCodec.encode(j).toOption.get
    val body = item(Attrs.Body).asInstanceOf[AttrValue.S].value
    val plain = QueryJobCodec.encode(job.copy(resultJson = Some("[]"))).toOption.get
    val plainBody = plain(Attrs.Body).asInstanceOf[AttrValue.S].value
    val growth = ServiceStateBounds.bytes(body) - ServiceStateBounds.bytes(plainBody)
    assert(growth > ServiceStateBounds.bytes(raw) * 3 / 2, s"growth $growth, raw ${raw.length}")
    assert(Rows.encode(QueryJobCodec, j).isRight)
  }
}
