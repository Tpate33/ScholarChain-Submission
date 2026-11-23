package scholarchain.services

import scholarchain.core.Canonical
import scholarchain.rag.agents.AuditRecord
import scholarchain.storage.model.{Attrs, AttrValue, Item, ItemCodecs, QueryAuditRecord, TableRole}

/**
 * SV-INT-01 (CR-P11-02-1): explain reads historical v2 audit rows and current v3 rows, each under
 * its own version's rules; corrupt rows stay the 502 integrity failure, unknown versions are the
 * 502 unsupported-version error.
 *
 * The frozen bodies below are literal bytes, written by hand from `prompts/schemas/audit-record.v2`
 * and `.v3`, never produced by the current rag codec; their hashes are pinned literals too.
 */
class AuditVersionsSuite extends munit.FunSuite {
  import TestHttp.*

  private val Integrity = "a stored audit record failed its integrity check"
  private val Unsupported =
    "a stored audit record has a schema version this service does not read"

  private val V2Query = "query:frozen-v2-0001"
  private val V3Query = "query:frozen-v3-0001"

  /** A v2 audit body (abstained, with its plan; no verifierVersion), frozen. */
  private val V2Json: String =
    """{"schemaVersion":"scholarchain.rag.audit-record.v2","queryId":"query:frozen-v2-0001",""" +
      """"outcome":"abstained","reason":"unresolved-researcher",""" +
      """"planSha256":"1111111111111111111111111111111111111111111111111111111111111111",""" +
      """"planVersion":"scholarchain.rag.query-planner.v1","operations":["resolve-researcher"],""" +
      """"plan":{"planVersion":"scholarchain.rag.query-planner.v1","window":"2018-2022",""" +
      """"steps":[{"ordinal":0,"operation":"resolve-researcher","metrics":[],"intent":null,""" +
      """"researchers":[{"nameIndex":0,"researcherId":null,"status":"unresolved"}],""" +
      """"compareWindow":null,"windowSource":"default","rankingRule":null,"filters":""" +
      """[{"key":"filter.institution","valueSha256":""" +
      """"2222222222222222222222222222222222222222222222222222222222222222","applied":false}],""" +
      """"unresolvedArguments":["name"]}]},"resolvedResearcherIds":[],"abstainedNames":1,""" +
      """"evidenceIds":[],"bundleId":null,"metricVersions":[],""" +
      """"graphRunId":"run:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",""" +
      """"executedTemplates":[],"promptVersion":null,"outputSchemaVersion":null,""" +
      """"modelVersion":null,"nonProductionModel":false,"synthesisAttempts":0,""" +
      """"synthesisOutputSha256":null,"inputTokens":null,"outputTokens":null,""" +
      """"latencyMillis":12,"verification":{},"overallVerification":null,""" +
      """"droppedMissingEvidence":0,"answerSha256":null}"""

  private val V2Sha = "ec194543c20e42f3d68c806a29aef5096c990af44bb62b93f55a11f32e2f878c"

  /** A v3 audit body (refused; verifierVersion null), frozen. */
  private val V3Json: String =
    """{"schemaVersion":"scholarchain.rag.audit-record.v3","queryId":"query:frozen-v3-0001",""" +
      """"outcome":"refused","reason":"no-allowlisted-operation","planSha256":null,""" +
      """"planVersion":null,"operations":[],"plan":null,"resolvedResearcherIds":[],""" +
      """"abstainedNames":0,"evidenceIds":[],"bundleId":null,"metricVersions":[],""" +
      """"graphRunId":"run:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",""" +
      """"executedTemplates":[],"promptVersion":null,"outputSchemaVersion":null,""" +
      """"modelVersion":null,"nonProductionModel":false,"synthesisAttempts":0,""" +
      """"synthesisOutputSha256":null,"inputTokens":null,"outputTokens":null,""" +
      """"latencyMillis":3,"verification":{},"overallVerification":null,""" +
      """"droppedMissingEvidence":0,"answerSha256":null,"verifierVersion":null}"""

  private val V3Sha = "f115c6bf06481c358af11ad25cbd626ea55867914f52e8708eaa63e981675a23"

  private val Expires = 1900000000L

  private def row(q: String, version: String, json: String, sha: String) =
    QueryAuditRecord(QueryAuditRecord.SchemaVersion, q, version, json, sha, Expires)

  /** The item of a valid row, through the storage codec. */
  private def item(r: QueryAuditRecord): Item =
    ItemCodecs.QueryAuditCodec.encode(r).fold(e => fail(e.message), identity)

  /**
   * The item of `tampered`, built from the item of the valid `base` with the body replaced and only
   * the item-level `recordSha256` recomputed. CR-INT-03-1: this does NOT get past storage. Its
   * decode re-encodes the item and checks `QueryAuditRecord.problems` (the body hash among them),
   * so explain tests built on it show the end-to-end 502, never `AuditVersions` itself; the direct
   * `AuditVersions.read` cases below cover the service-level rules.
   */
  private def forged(base: QueryAuditRecord, tampered: QueryAuditRecord): Item = {
    val body = upickle.default.write(tampered)
    item(base) ++ Map(
      Attrs.Body -> AttrValue.S(body),
      Attrs.RecordSha256 -> AttrValue.S(Canonical.sha256HexUtf8(body))
    )
  }

  /** Explain of `q` by its owner over a world holding exactly `audit` as the audit row. */
  private def explain(q: String, audit: Item): HttpResponse = {
    val w = new ServiceWorld
    assert(w.kv.putIfAbsent(TableRole.QuerySession, audit).isRight, "fixture row stored")
    val owners = new InMemoryQueryOwnership(10)
    assert(owners.record(q, "client-full").isRight)
    val s = w.service(ownership = owners)
    try get(s.router, s"/v1/explain/$q")
    finally s.close()
  }

  private def assertError(r: HttpResponse, message: String, tag: String): Unit = {
    assertEquals(r.status, 502, s"$tag: ${r.bodyText}")
    val e = json(r)("error")
    assertEquals(e("code").str, "downstream-failure", tag)
    assertEquals(e("message").str, message, tag)
    assertEquals(e("traceId").str, r.header("X-Trace-Id").get, tag)
  }

  // ------------------------------------------------------------------ frozen fixtures

  test("the frozen bodies are pinned: their stored hashes are literal") {
    assertEquals(Canonical.sha256HexUtf8(V2Json), V2Sha)
    assertEquals(Canonical.sha256HexUtf8(V3Json), V3Sha)
    assert(!ujson.read(V2Json).obj.contains("verifierVersion"))
    assert(ujson.read(V3Json).obj.contains("verifierVersion"))
  }

  test("the writer's version is a version explain reads") {
    assertEquals(AuditRecord.SchemaVersion, AuditVersions.V3)
    assert(AuditVersions.Supported.contains(AuditRecord.SchemaVersion))
  }

  // ------------------------------------------------------------------ v2 and v3 accepted

  test("CR-P11-02-1: a frozen v2 audit row is explained (200), not a 502") {
    val r = explain(V2Query, item(row(V2Query, AuditVersions.V2, V2Json, V2Sha)))
    assertEquals(r.status, 200, r.bodyText)
    val j = json(r)
    // The same response shape as the frozen (answered, v3) example: the same members.
    val example = ujson.read(resource("explain.response.json"))
    assertEquals(j.obj.keySet.toSet, example.obj.keySet.toSet)
    assertEquals(j("queryId").str, V2Query)
    assertEquals(j("outcome").str, "abstained")
    assertEquals(j("reason").str, "unresolved-researcher")
    assertEquals(j("auditSha256").str, V2Sha)
    assertEquals(j("plan")("steps")(0)("unresolvedArguments")(0).str, "name")
    assertEquals(j("counters")("abstainedNames").num, 1.0)
    assertEquals(j("verification")("overall"), ujson.Null)
  }

  test("a frozen v3 audit row is explained (200)") {
    val r = explain(V3Query, item(row(V3Query, AuditVersions.V3, V3Json, V3Sha)))
    assertEquals(r.status, 200, r.bodyText)
    val j = json(r)
    assertEquals(j("outcome").str, "refused")
    assertEquals(j("reason").str, "no-allowlisted-operation")
    assertEquals(j("plan"), ujson.Null)
    assertEquals(j("auditSha256").str, V3Sha)
  }

  test("a v3 audit row written by the current workflow is explained (200)") {
    val w = new ServiceWorld
    val s = w.service()
    try {
      val a = post(
        s.router,
        "/v1/query",
        ujson.Obj("question" -> "Who are the coauthors of Ada Lovelace?", "mode" -> "sync").render()
      )
      assertEquals(a.status, 200, a.bodyText)
      val q = json(a)("queryId").str
      val e = get(s.router, s"/v1/explain/$q")
      assertEquals(e.status, 200, e.bodyText)
      assertEquals(json(e)("outcome").str, "answered")
    } finally s.close()
  }

  // ------------------------------------------------------------------ corrupt rows

  test("a tampered body under each version is the 502 integrity failure") {
    Vector(
      ("v2", V2Query, AuditVersions.V2, V2Json, V2Sha),
      ("v3", V3Query, AuditVersions.V3, V3Json, V3Sha)
    ).foreach { case (tag, q, v, body, sha) =>
      val good = row(q, v, body, sha)
      val edited = body.replace("\"latencyMillis\":", "\"latencyMillis\":9")
      assertNotEquals(edited, body)
      // The body changed, its stored hash did not.
      val r = explain(q, forged(good, good.copy(auditJson = edited)))
      assertError(r, Integrity, s"$tag body")
      // The stored hash changed, the body did not.
      val h = explain(q, forged(good, good.copy(auditSha256 = "0" * 64)))
      assertError(h, Integrity, s"$tag hash")
      // The item-level hash no longer matches the item's body.
      val i = item(good) ++ Map(Attrs.RecordSha256 -> AttrValue.S("f" * 64))
      assertError(explain(q, i), Integrity, s"$tag item hash")
    }
  }

  test("a body re-hashed consistently but not of its row's version is the 502 integrity failure") {
    // A v3-shaped body (with verifierVersion) labeled v2: v2 has no such member.
    val v2WithVerifier =
      V2Json.dropRight(1) + ""","verifierVersion":"scholarchain.rag.claim-verification.v4"}"""
    val a = row(V2Query, AuditVersions.V2, v2WithVerifier, Canonical.sha256HexUtf8(v2WithVerifier))
    assertError(explain(V2Query, item(a)), Integrity, "v2 row with verifierVersion")
    // A v2 body under a v3 row label (v3 requires verifierVersion; its own label says v2).
    val b = row(V2Query, AuditVersions.V3, V2Json, V2Sha)
    assertError(explain(V2Query, item(b)), Integrity, "v2 body labeled v3")
    // A v3 body (with verifierVersion) whose own schemaVersion says v2, labeled v3.
    val v3SaysV2 = V3Json.replace(AuditVersions.V3, AuditVersions.V2)
    val c = row(V3Query, AuditVersions.V3, v3SaysV2, Canonical.sha256HexUtf8(v3SaysV2))
    assertError(explain(V3Query, item(c)), Integrity, "v3 body labeled v2 inside")
    // A v2 body under its v2 label but with the queryId of another query.
    val other = V2Json.replace(V2Query, "query:frozen-v2-0002")
    val d = row(V2Query, AuditVersions.V2, other, Canonical.sha256HexUtf8(other))
    assert(ItemCodecs.QueryAuditCodec.encode(d).isLeft, "storage refuses a foreign queryId")
  }

  // ------------------------------------------------------------------ unknown versions

  test("an unknown audit schema version is a typed 502, never a crash or a 200") {
    Vector(
      "scholarchain.rag.audit-record.v1",
      "scholarchain.rag.audit-record.v4",
      "something-else"
    ).foreach { v =>
      val body = V3Json.replace(AuditVersions.V3, v)
      val r = explain(V3Query, item(row(V3Query, v, body, Canonical.sha256HexUtf8(body))))
      assertError(r, Unsupported, v)
      assert(!r.bodyText.contains(v), s"$v: stored version echoed")
    }
  }

  test("a tampered row of an unknown version is reported as corrupt, not unsupported") {
    val v = "scholarchain.rag.audit-record.v4"
    val body = V3Json.replace(AuditVersions.V3, v)
    val good = row(V3Query, v, body, Canonical.sha256HexUtf8(body))
    assertError(
      explain(V3Query, forged(good, good.copy(auditSha256 = "0" * 64))),
      Integrity,
      "unknown version, bad hash"
    )
  }

  test("CR-INT-03-1 unit: a hash mismatch is corrupt under each version, read directly") {
    Vector(
      ("v2", V2Query, AuditVersions.V2, V2Json, V2Sha),
      ("v3", V3Query, AuditVersions.V3, V3Json, V3Sha)
    ).foreach { case (tag, q, v, body, sha) =>
      val good = row(q, v, body, sha)
      assert(AuditVersions.read(good, q).isRight, s"$tag control")
      // The stored hash differs from the body's hash (the body is unchanged).
      val badHash = good.copy(auditSha256 = "0" * 64)
      assertEquals(AuditVersions.read(badHash, q).left.map(_.message), Left(Integrity), tag)
      // The body differs from the one the stored hash was computed over.
      val edited = good.copy(auditJson = body.replace("\"latencyMillis\":", "\"latencyMillis\":9"))
      assertEquals(AuditVersions.read(edited, q).left.map(_.message), Left(Integrity), tag)
    }
  }

  test(
    "CR-INT-03-1 unit: an unknown version with a bad hash is corrupt (integrity before version)"
  ) {
    val v = "scholarchain.rag.audit-record.v4"
    val body = V3Json.replace(AuditVersions.V3, v)
    val good = row(V3Query, v, body, Canonical.sha256HexUtf8(body))
    // Control: with an intact hash the same row is the unsupported-version error.
    assertEquals(AuditVersions.read(good, V3Query).left.map(_.message), Left(Unsupported))
    val bad = good.copy(auditSha256 = "0" * 64)
    assertEquals(AuditVersions.read(bad, V3Query).left.map(_.message), Left(Integrity))
  }

  test("CR-INT-03-2: a v3 label and v3 body without verifierVersion is corrupt, on its own") {
    val noVerifier = V3Json.replace(""","verifierVersion":null""", "")
    assertNotEquals(noVerifier, V3Json)
    assert(!ujson.read(noVerifier).obj.contains(AuditVersions.AddedInV3))
    assertEquals(ujson.read(noVerifier)("schemaVersion").str, AuditVersions.V3)
    val r = row(V3Query, AuditVersions.V3, noVerifier, Canonical.sha256HexUtf8(noVerifier))
    // Only the v3 member rule refuses it: label, body version, hash and query all agree.
    assertEquals(AuditVersions.read(r, V3Query).left.map(_.message), Left(Integrity))
    // Storage accepts the row, so explain reaches AuditVersions and returns the 502.
    assertError(explain(V3Query, item(r)), Integrity, "v3 without verifierVersion")
  }

  test("unit: AuditVersions.read refuses a null row and a foreign query as corrupt") {
    assertEquals(AuditVersions.read(null, V2Query).left.map(_.message), Left(Integrity))
    val r = row(V2Query, AuditVersions.V2, V2Json, V2Sha)
    assertEquals(AuditVersions.read(r, V3Query).left.map(_.message), Left(Integrity))
    assertEquals(AuditVersions.read(r, V2Query).map(_.verifierVersion), Right(None))
  }
}
