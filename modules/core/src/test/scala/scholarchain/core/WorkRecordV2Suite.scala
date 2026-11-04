package scholarchain.core

import Fixtures.*

/** C5 (work fields not lost) and item 7 (ORCID assertion source) on work-record.v2. */
class WorkRecordV2Suite extends munit.FunSuite {

  private def invalidAt(errs: Vector[ValidationError], field: String): Boolean =
    errs.exists {
      case ValidationError.InvalidValue(f, _) => f == field
      case ValidationError.MissingField(f) => f == field
      case ValidationError.MalformedId(f, _, _, _) => f == field
      case _ => false
    }

  private def errs(w: WorkRecord) = WorkRecord.contract.validate(w)

  test("a DBLP journal article keeps its venue name, volume, issue, pages, ee links and URLs") {
    val journal = work.copy(venueId = None)
    assertEquals(errs(journal), Vector.empty)
    assertEquals(journal.venueName, Some("Example J."))
    assertEquals(journal.recordType, Some("article"))
    assertEquals(
      (journal.volume, journal.issue, journal.pages),
      (Some("64"), Some("3"), Some("1-22"))
    )
    assertEquals(journal.eeLinks, Vector("https://doi.org/10.1234/example.2021.001"))
    assertEquals(journal.urls, Vector("db/journals/example/example64.html#Synthetic21"))
  }

  test("OpenAlex topics and additional work IDs, and the ORCID assertion source, are kept") {
    assertEquals(errs(openAlexWork), Vector.empty)
    assertEquals(openAlexWork.topicIds, Vector("topic:openalex:T1", "topic:openalex:T2"))
    assertEquals(openAlexWork.additionalIds, Vector(ExactId(ExactIdScheme.Mag, "1000001")))
    assertEquals(errs(orcidWork), Vector.empty)
    assertEquals(orcidWork.assertionSource, Some(assertion))
  }

  test("a v1-era constructor call (no v2 arguments) still builds a valid v2 record") {
    val legacy = WorkRecord(
      SchemaVersions.WorkRecord,
      workId,
      SourceType.Dblp,
      dblpKey,
      "T.",
      "t",
      None,
      None,
      None,
      None,
      Vector.empty,
      None,
      dblpProv
    )
    assertEquals(errs(legacy), Vector.empty)
    val json = Json.write(legacy)
    WorkRecord.DefaultedFields.foreach(k =>
      assert(json.contains(s"\"$k\":"), s"$k is always serialized")
    )
    assert(
      json.endsWith(
        "\"recordType\":null,\"venueName\":null,\"volume\":null,\"issue\":null,\"pages\":null," +
          "\"eeLinks\":[],\"urls\":[],\"topicIds\":[],\"additionalIds\":[],\"assertionSource\":null," +
          "\"sourceLocalWorkIds\":[]}"
      ),
      json
    )
    assertEquals(Json.decode[WorkRecord](json), Right(legacy))
  }

  test("each v2 and v3 key is required on the wire although it has a Scala default") {
    assertEquals(WorkRecord.DefaultedFields.last, "sourceLocalWorkIds")
    val json = Json.write(work)
    WorkRecord.DefaultedFields.foreach { k =>
      val obj = ujson.read(json).obj
      obj.remove(k)
      assertEquals(
        Json.decode[WorkRecord](ujson.write(obj)),
        Left(Vector(ValidationError.Undecodable("WorkRecord", s"missing key(s): $k"))),
        k
      )
    }
  }

  test("record type is a token; text fields are non-blank without control characters") {
    assert(invalidAt(errs(work.copy(recordType = Some("journal article"))), "recordType"))
    assert(invalidAt(errs(work.copy(venueName = Some(" "))), "venueName"))
    assert(invalidAt(errs(work.copy(volume = Some(""))), "volume"))
    assert(invalidAt(errs(work.copy(issue = Some("3\n"))), "issue"))
    assert(invalidAt(errs(work.copy(pages = Some(""))), "pages"))
  }

  test("ee links and URLs: no whitespace, no duplicates, source order kept") {
    assert(invalidAt(errs(work.copy(eeLinks = Vector("https://example.org/a b"))), "eeLinks[0]"))
    assert(invalidAt(errs(work.copy(urls = Vector(""))), "urls[0]"))
    val dup = Vector("https://example.org/a", "https://example.org/a")
    assert(invalidAt(errs(work.copy(eeLinks = dup)), "eeLinks"))
    val unsorted = Vector("https://z.example.org/", "https://a.example.org/")
    assertEquals(errs(work.copy(eeLinks = unsorted)), Vector.empty, "order is the source's")
  }

  test("topic IDs must be distinct topic IDs") {
    assert(invalidAt(errs(openAlexWork.copy(topicIds = Vector("T1"))), "topicIds[0]"))
    assert(
      invalidAt(
        errs(openAlexWork.copy(topicIds = Vector("topic:openalex:T1", "topic:openalex:T1"))),
        "topicIds"
      )
    )
  }

  test("additional IDs: work schemes only, sorted, distinct, never repeating dedicated fields") {
    def withIds(w: WorkRecord, ids: Vector[ExactId]) = errs(w.copy(additionalIds = ids))
    assert(
      invalidAt(
        withIds(openAlexWork, Vector(ExactId(ExactIdScheme.Orcid, orcidA))),
        "additionalIds[0]"
      )
    )
    val two =
      Vector(ExactId(ExactIdScheme.Mag, "1000001"), ExactId(ExactIdScheme.ScopusEid, "2-s2.0-1"))
    assertEquals(withIds(openAlexWork, two), Vector.empty)
    assert(invalidAt(withIds(openAlexWork, two.reverse), "additionalIds"))
    assert(
      invalidAt(
        withIds(openAlexWork, Vector(ExactId(ExactIdScheme.Doi, "10.1234/example.2021.001"))),
        "additionalIds[0]"
      ),
      "repeats doi"
    )
    assert(
      invalidAt(
        withIds(openAlexWork, Vector(ExactId(ExactIdScheme.OpenAlexWork, "W1"))),
        "additionalIds[0]"
      ),
      "repeats the work's own ID"
    )
    assert(
      invalidAt(withIds(work, Vector(ExactId(ExactIdScheme.DblpKey, dblpKey))), "additionalIds[0]"),
      "repeats the DBLP key"
    )
    assertEquals(
      withIds(work, Vector(ExactId(ExactIdScheme.Doi, "10.1234/example.2021.002"))),
      Vector.empty,
      "a second, different DOI is preserved"
    )
  }

  test("the assertion source is allowed only on ORCID works and is validated") {
    assert(invalidAt(errs(work.copy(assertionSource = Some(assertion))), "assertionSource"))
    assert(
      invalidAt(
        errs(
          orcidWork.copy(assertionSource =
            Some(assertion.copy(sourceKind = AssertionSourceKind.Orcid))
          )
        ),
        "assertionSource.sourceId"
      )
    )
    assertEquals(
      errs(orcidWork.copy(assertionSource = None, sourceLocalWorkIds = Vector.empty)),
      Vector.empty
    )
  }
}
