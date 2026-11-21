package scholarchain.storage

import scholarchain.core.{Canonical, EvidenceBundle, TargetStore}
import scholarchain.storage.model.*
import scholarchain.storage.model.ItemCodecs.given
import TestData.*

/** Suite 16 (SE-P7-01; README L1411): item codecs and the rejected-record schema. */
class SerializationSuite extends munit.FunSuite {

  private def roundTrip[A](a: A)(using c: ItemCodec[A]): Item = {
    val item = c.encode(a) match {
      case Right(i) => i
      case Left(e) => fail(s"encode failed: $e")
    }
    assertEquals(c.decode(item), Right(a))
    item
  }

  private val entry =
    RunLedgerStates.start(runId, loadId, TargetStore.DynamoDb, hex("parts"), 21, 5L).toOption.get

  test("every record type round-trips through its item codec") {
    roundTrip(publication())
    roundTrip(entry)
    roundTrip(rejected())
    roundTrip(artifact("reports/loads/x/load-report.json"))
    roundTrip(bundle("s3://bucket/a", "s3://bucket/b"))
    roundTrip(
      EvidenceItemRow(
        EvidenceItemRow.SchemaVersion,
        bundle("s3://b/a").bundleId,
        queryId,
        evidenceItem("s3://b/a")
      )
    )
    roundTrip(session())
    roundTrip(step(0))
    roundTrip(link())
    roundTrip(
      metric(group = Some(communityId), pair = Some(Vector(pairFirst, pairSecond)), rank = Some(4))
    )
  }

  test("items carry the key, body, recordSha256, version and TTL attributes exactly") {
    val item = roundTrip(entry)
    val body = item(Attrs.Body) match {
      case AttrValue.S(b) => b
      case other => fail(s"body: $other")
    }
    assertEquals(item(Attrs.RecordSha256), AttrValue.S(Canonical.sha256HexUtf8(body)))
    assertEquals(item(Attrs.Version), AttrValue.N("1"))
    assertEquals(item("byLoadPk"), AttrValue.S(s"load#$loadId"))
    assertEquals(item("byLoadSk"), AttrValue.S(s"run#$runId"))
    assert(!item.contains(Attrs.ExpiresAt), "RunLedger has no TTL")
    val s = roundTrip(session(expires = 1900000000L))
    assertEquals(s(Attrs.ExpiresAt), AttrValue.N("1900000000"))
    val m = roundTrip(metric())
    assertEquals(
      m.keySet,
      Set("pk", "sk", Attrs.SchemaVersion, Attrs.Body, Attrs.RecordSha256),
      "sparse: no GSI attribute without a group, pair or rank"
    )
    val ranked = roundTrip(metric(rank = Some(3)))
    assertEquals(ranked("rankSk"), AttrValue.S(s"0000000003#$researcherA"))
  }

  test("a tampered body, hash, key, index attribute, version or TTL never decodes") {
    val c = ItemCodecs.LoadEntryCodec
    val item = c.encode(entry).toOption.get
    val body = item(Attrs.Body).asInstanceOf[AttrValue.S].value
    val tamperedBody = body.replace("\"itemCount\":21", "\"itemCount\":22")
    def corrupt(i: Item, why: String) =
      c.decode(i) match {
        case Left(StoreError.Corrupt(_)) => ()
        case other => fail(s"$why: expected Corrupt, got $other")
      }
    corrupt(item.updated(Attrs.Body, AttrValue.S(tamperedBody)), "body changed, hash not")
    val movedBody = body.replace(loadId, otherLoadId)
    corrupt(
      item
        .updated(Attrs.Body, AttrValue.S(movedBody))
        .updated(Attrs.RecordSha256, AttrValue.S(Canonical.sha256HexUtf8(movedBody))),
      "body and hash rewritten for another load: the body no longer matches the item's keys"
    )
    corrupt(item.updated("sk", AttrValue.S(s"load#$otherLoadId")), "key")
    corrupt(item.updated("byLoadPk", AttrValue.S("load#x")), "index attribute")
    corrupt(item.updated(Attrs.Version, AttrValue.N("2")), "version")
    corrupt(item - Attrs.Body, "missing body")
    corrupt(item.updated(Attrs.SchemaVersion, AttrValue.S("other.v1")), "schema version")
    corrupt(item.updated(Attrs.Body, AttrValue.S("{not json")), "undecodable")
    val s = ItemCodecs.QuerySessionCodec.encode(session()).toOption.get
    corrupt2(ItemCodecs.QuerySessionCodec.decode(s.updated(Attrs.ExpiresAt, AttrValue.N("1"))))
  }

  private def corrupt2[A](r: Either[StoreError, A]): Unit = r match {
    case Left(StoreError.Corrupt(_)) => ()
    case other => fail(s"expected Corrupt, got $other")
  }

  test("an unknown field in a stored body is Corrupt (bodies refuse unknown keys)") {
    val c = ItemCodecs.RunPublicationCodec
    val item = c.encode(publication()).toOption.get
    val body = item(Attrs.Body).asInstanceOf[AttrValue.S].value
    val extra = body.dropRight(1) + ",\"note\":\"x\"}"
    corrupt2(
      c.decode(
        item
          .updated(Attrs.Body, AttrValue.S(extra))
          .updated(Attrs.RecordSha256, AttrValue.S(Canonical.sha256HexUtf8(extra)))
      )
    )
  }

  test("encoding refuses invalid records") {
    assert(ItemCodecs.RunPublicationCodec.encode(publication("XYZ")).isLeft)
    assert(ItemCodecs.LoadEntryCodec.encode(entry.copy(version = 0)).isLeft)
    assert(ItemCodecs.LoadEntryCodec.encode(entry.copy(schemaVersion = "x")).isLeft)
    assert(ItemCodecs.ArtifactCodec.encode(artifact("k").copy(zone = "attic")).isLeft)
    assert(ItemCodecs.ArtifactCodec.encode(artifact("k", sha = "nothex")).isLeft)
    assert(
      ItemCodecs.QuerySessionCodec.encode(session(expires = 0)).isLeft,
      "TTL tables need expiresAt"
    )
    assert(ItemCodecs.EntityLinkCodec.encode(link().copy(candidateRank = 0)).isLeft)
    assert(
      ItemCodecs.AnalysisResultCodec
        .encode(metric(pair = Some(Vector(pairSecond, pairFirst))))
        .isLeft
    )
    assert(ItemCodecs.AnalysisResultCodec.encode(metric(pair = Some(Vector(pairFirst)))).isLeft)
    val b = bundle("s3://b/a")
    assert(
      ItemCodecs.EvidenceBundleCodec.encode(b.copy(bundleId = "bundle:" + hex("wrong"))).isLeft,
      "core contract"
    )
    assert(ItemCodecs.RejectedRecordCodec.encode(rejected().copy(detail = "x" * 600)).isLeft)
  }

  test("rejected-record schema v2: golden JSON line, round trip, unknown keys and bounds refused") {
    val r = rejected()
    val line = RejectedRecord.jsonLine(r)
    // CR-P7-11 m1: v2 adds the write generation.
    assertEquals(
      line,
      "{\"schemaVersion\":\"scholarchain.storage.rejected-record.v2\"," +
        s"\"runId\":\"$runId\",\"loadId\":\"$loadId\",\"targetStore\":\"s3-vectors\"," +
        "\"targetRole\":\"researcher-profiles\",\"sourcePath\":\"summary-inputs/part-00000.parquet\"," +
        "\"recordKey\":\"row:metrics/part-00000.parquet#7\",\"reason\":\"oversize-metadata\"," +
        "\"detail\":\"metadata value textS3Key exceeds the per-value cap\",\"attempts\":0," +
        "\"generation\":3}"
    )
    corrupt2(RejectedRecord.parseLine(line.replace("\"generation\":3", "\"generation\":0")))
    corrupt2(RejectedRecord.parseLine(line.replace(",\"generation\":3", "")))
    corrupt2(RejectedRecord.parseLine(line.replace("rejected-record.v2", "rejected-record.v1")))
    assertEquals(RejectedRecord.parseLine(line), Right(r))
    assert(!line.contains("\n"))
    corrupt2(RejectedRecord.parseLine(line.dropRight(1) + ",\"text\":\"leaked\"}"))
    corrupt2(RejectedRecord.parseLine(line.replace("oversize-metadata", "whatever")))
    corrupt2(RejectedRecord.parseLine(line.replace("\"attempts\":0", "\"attempts\":-1")))
    assertEquals(RejectedRecord.problems(r), Vector.empty)
    assert(RejectedRecord.problems(r.copy(detail = "line\nbreak")).nonEmpty)
    assert(RejectedRecord.problems(r.copy(sourcePath = "../x")).nonEmpty)
    assert(RejectedRecord.problems(r.copy(loadId = runId)).nonEmpty)
    assertEquals(RejectReason.values.map(_.tag).distinct.size, RejectReason.values.size)
  }

  test("the attribute model reads typed values and refuses the wrong type") {
    val i: Item = Map("a" -> AttrValue.S("x"), "n" -> AttrValue.n(42), "bad" -> AttrValue.N("4.2"))
    assertEquals(AttrValue.string(i, "a"), Right("x"))
    assertEquals(AttrValue.long(i, "n"), Right(42L))
    assert(AttrValue.long(i, "bad").isLeft)
    assert(AttrValue.long(i, "a").isLeft)
    assert(AttrValue.string(i, "missing").isLeft)
  }

  test("the evidence bundle codec stores the core bundle with its query index") {
    val b: EvidenceBundle = bundle("s3://b/a")
    val item = roundTrip(b)
    assertEquals(item("byQueryPk"), AttrValue.S(s"query#$queryId"))
    assertEquals(item("byQuerySk"), AttrValue.S(s"bundle#${b.bundleId}"))
  }
}
