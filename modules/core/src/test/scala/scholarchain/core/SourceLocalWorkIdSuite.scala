package scholarchain.core

import Fixtures.*

/**
 * Work order 1b (review O3): the ORCID `source-work-id` is preserved together with the submitting
 * source that assigned it (work-record.v3), and is never a global identifier.
 */
class SourceLocalWorkIdSuite extends munit.FunSuite {

  private def errs(w: WorkRecord) = WorkRecord.contract.validate(w)
  private def withLocal(ids: SourceLocalWorkId*) = orcidWork.copy(sourceLocalWorkIds = ids.toVector)

  private val Field = "sourceLocalWorkIds"

  test("an ORCID work keeps its source-work-id qualified by the submitting source") {
    assertEquals(errs(orcidWork), Vector.empty)
    assertEquals(
      orcidWork.sourceLocalWorkIds,
      Vector(
        SourceLocalWorkId(
          AssertionSourceKind.Client,
          "APP-EXAMPLE000000001",
          "00000000-0000-4000-8000-000000000001"
        )
      )
    )
    val a = orcidWork.assertionSource.get
    assertEquals(
      orcidWork.sourceLocalWorkIds.map(s => (s.issuerKind, s.issuerId)),
      Vector((a.sourceKind, a.sourceId))
    )
    val decoded = Json.decode[WorkRecord](Json.write(orcidWork))
    assertEquals(decoded.map(_.sourceLocalWorkIds), Right(orcidWork.sourceLocalWorkIds))
  }

  test("the wire form of a source-work-id always carries its issuer") {
    val json = Json.write(orcidWork)
    assert(
      json.endsWith(
        "\"sourceLocalWorkIds\":[{\"issuerKind\":\"client\",\"issuerId\":\"APP-EXAMPLE000000001\"," +
          "\"value\":\"00000000-0000-4000-8000-000000000001\"}]}"
      ),
      json
    )
    val noIssuerId = json.replace("\"issuerId\":\"APP-EXAMPLE000000001\",", "")
    assert(
      Json
        .decode[WorkRecord](noIssuerId)
        .left
        .exists(_.exists(_.isInstanceOf[ValidationError.Undecodable])),
      "an entry without its issuer does not decode"
    )
    val bareString =
      json.replace(
        "[{\"issuerKind\":\"client\",\"issuerId\":\"APP-EXAMPLE000000001\"," +
          "\"value\":\"00000000-0000-4000-8000-000000000001\"}]",
        "[\"00000000-0000-4000-8000-000000000001\"]"
      )
    assert(Json.decode[WorkRecord](bareString).isLeft, "a bare value does not decode")
    val extra = json.replace("\"issuerKind\":\"client\",", "\"issuerKind\":\"client\",\"x\":1,")
    assert(Json.decode[WorkRecord](extra).isLeft, "unknown keys in an entry are rejected")
  }

  test("a source-work-id is not an exact identifier and never a dedicated or additional ID") {
    assertEquals(ExactIdScheme.fromTag("source-work-id"), None)
    assertEquals(ExternalIdScheme.fromTag("source-work-id"), None)
    assertEquals(
      WorkRecord.dedicatedIds(orcidWork),
      Set(ExactId(ExactIdScheme.Doi, "10.1234/example.2021.001"))
    )
    assert(!orcidWork.additionalIds.exists(_.value == localWorkId.value))
  }

  test("two holders' copies of one client's work share no exact identifier through it") {
    // As in datasets/samples/orcid: one client submits the same source-work-id to two records.
    val bare = orcidWork.copy(doi = None, additionalIds = Vector.empty)
    val otherId = ok(WorkId.orcidWork(orcidB, 2002L))
    val other = bare.copy(
      workId = otherId,
      sourceRecordId = s"$orcidB/work/2002",
      provenance = orcidProv(s"$orcidB/work/2002")
    )
    assertEquals(errs(bare), Vector.empty)
    assertEquals(errs(other), Vector.empty)
    assertEquals(bare.sourceLocalWorkIds, other.sourceLocalWorkIds)
    def exact(w: WorkRecord) = WorkRecord.dedicatedIds(w) ++ w.additionalIds
    assertEquals(exact(bare), Set.empty[ExactId])
    assertEquals(exact(bare).intersect(exact(other)), Set.empty[ExactId])
  }

  test("the issuer must be the submitting source, not another client or the assertion origin") {
    val otherClient = SourceLocalWorkId(AssertionSourceKind.Client, "APP-OTHER", "x-1")
    assertEquals(
      errs(withLocal(otherClient)),
      Vector(
        ValidationError.InvalidValue(
          s"$Field[0]",
          "issuer differs from assertionSource (sourceKind, sourceId)"
        )
      )
    )
    val origin = SourceLocalWorkId(AssertionSourceKind.Orcid, orcidA, "x-1")
    assertEquals(
      errs(withLocal(origin)),
      Vector(
        ValidationError.InvalidValue(
          s"$Field[0]",
          "issuer differs from assertionSource (sourceKind, sourceId)"
        )
      )
    )
  }

  test("a source-work-id requires the assertion source and an ORCID work") {
    assertEquals(
      errs(orcidWork.copy(assertionSource = None)),
      Vector(
        ValidationError.InvalidValue(Field, "requires assertionSource (the issuing source)")
      )
    )
    val dblp = errs(work.copy(sourceLocalWorkIds = Vector(localWorkId)))
    assert(dblp.contains(ValidationError.InvalidValue(Field, "only allowed for ORCID works")))
    assertEquals(errs(orcidWork.copy(sourceLocalWorkIds = Vector.empty)), Vector.empty)
    assertEquals(
      errs(orcidWork.copy(sourceLocalWorkIds = null)),
      Vector(ValidationError.MissingField(Field))
    )
  }

  test("issuer IDs and values are validated") {
    val holder = assertion.copy(sourceKind = AssertionSourceKind.Orcid, sourceId = orcidA)
    val holderWork = orcidWork.copy(assertionSource = Some(holder))
    assertEquals(
      errs(holderWork.copy(sourceLocalWorkIds = Vector(SourceLocalWorkId.issuedBy(holder, "h-1")))),
      Vector.empty,
      "an iD holder can be the issuer"
    )
    assert(
      errs(
        withLocal(SourceLocalWorkId(AssertionSourceKind.Orcid, "0000-0000-0000-0002", "h-1"))
      ).exists {
        case ValidationError.MalformedId(f, "orcid", _, _) => f == s"$Field[0].issuerId"
        case _ => false
      }
    )
    def valueErrs(v: String) = errs(withLocal(SourceLocalWorkId.issuedBy(assertion, v)))
    assertEquals(valueErrs(" "), Vector(ValidationError.MissingField(s"$Field[0].value")))
    assertEquals(
      valueErrs("a\tb"),
      Vector(ValidationError.InvalidValue(s"$Field[0].value", "contains control characters"))
    )
    assertEquals(
      valueErrs(" a"),
      Vector(ValidationError.InvalidValue(s"$Field[0].value", "has surrounding whitespace"))
    )
    assertEquals(valueErrs("Pure 12345"), Vector.empty, "inner spaces are kept as given")
  }

  test("a work's source-work-ids are sorted and distinct") {
    val a = SourceLocalWorkId.issuedBy(assertion, "a-1")
    val b = SourceLocalWorkId.issuedBy(assertion, "b-2")
    assertEquals(errs(withLocal(a, b)), Vector.empty)
    val order =
      ValidationError.InvalidValue(
        Field,
        "must be sorted by (issuerKind, issuerId, value) without duplicates"
      )
    assertEquals(errs(withLocal(b, a)), Vector(order))
    assertEquals(errs(withLocal(a, a)), Vector(order))
    assertEquals(SourceLocalWorkId.canonical(Vector(b, a, b)), Vector(a, b))
  }
}
