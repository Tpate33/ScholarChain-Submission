package scholarchain.entityresolution.review

import upickle.default.write

import scholarchain.core.*
import scholarchain.entityresolution.Samples
import scholarchain.entityresolution.Samples.A
import scholarchain.entityresolution.evaluation.{GoldLabel, GoldLabels}
import scholarchain.entityresolution.policy.PolicyFixtures.candidateRunId

/**
 * OpenAlex cluster-ID masking in the blind view (view v5, RV-P5-04 m9, human instruction of
 * 2026-11-06). Every record, name, and ID here is SYNTHETIC (invented A and W IDs, the unregistered
 * DOI prefix 10.9999, and the documented example ORCID iD 0000-0002-1825-0097). No label here is
 * gold data.
 */
class OpenAlexMaskingSuite extends munit.FunSuite {
  import OpenAlexMaskingSuite.*

  private val M = OpenAlexMasking.Masked

  test(
    "pattern table: A and W IDs, OpenAlex links, encoded links; DOIs, ORCIDs, institutions kept"
  ) {
    assertEquals(OpenAlexMasking.Version, "er-openalex-display-mask.v1")
    Vector(
      "openalex:A9900000001" -> M,
      "openalex-author:A9900000001" -> M,
      "openalex-work:W9900000002" -> M,
      "work:openalex:W9900000002" -> M,
      "source-author:openalex:A9900000001" -> M,
      "https://openalex.org/W9900000002" -> M,
      "https://openalex.org/w9900000002" -> M,
      "https://openalex.org/authors/A9900000001" -> M,
      "https://api.openalex.org/works/W9900000002?select=id,doi" -> M,
      "https://api.openalex.org/people/A9900000001" -> M,
      "https://api.openalex.org/works?filter=openalex:W9900000002|W9900000003" -> M,
      "https://r.example.org/?to=https%3A%2F%2Fopenalex.org%2FW9900000002" -> M,
      "https://r.example.org/?to=https%253A%252F%252Fapi.openalex.org%252Fauthors%252FA9900000001" -> M,
      "see https://openalex.org/W9900000002 here" -> s"see $M here",
      "doi:10.9999/syn.2021.1" -> "doi:10.9999/syn.2021.1",
      "https://doi.org/10.9999/syn.2021.1" -> "https://doi.org/10.9999/syn.2021.1",
      "orcid:0000-0002-1825-0097" -> "orcid:0000-0002-1825-0097",
      "institution:openalex:I9900000004" -> "institution:openalex:I9900000004",
      "https://openalex.org/I9900000004" -> "https://openalex.org/I9900000004",
      "mag:9900000005" -> "mag:9900000005",
      "W9900000002" -> "W9900000002",
      "Quentin Synthwell" -> "Quentin Synthwell"
    ).foreach { case (in, want) => assertEquals(OpenAlexMasking.text(in), want, in) }
    // A bare value needs context; once named by an OpenAlex token in the pair it is masked.
    val values = OpenAlexMasking.clusterValues(Vector("https://openalex.org/w9900000002"))
    assertEquals(values, Set("W9900000002"))
    assertEquals(OpenAlexMasking.text("record W9900000002.", values), s"record $M.")
    assertEquals(OpenAlexMasking.text("XW9900000002", values), "XW9900000002", "whole values only")
    // Idempotent; the DBLP marker and the OpenAlex marker never mask each other.
    assertEquals(OpenAlexMasking.text(M), M)
    assertEquals(OpenAlexMasking.text(DblpMasking.Masked), DblpMasking.Masked)
    assertEquals(DblpMasking.text(M), M)
  }

  private val evidence = BlindView.index(works, profiles)
  private val view: Vector[BlindRow] =
    BlindView.build(items, evidence, "t").fold(e => fail(e.mkString("; ")), identity)
  private val jsonl = BlindView.toJsonl(view)

  test("no OpenAlex A or W ID survives anywhere in the view, as a value, link, or endpoint ID") {
    assertEquals(BlindView.RowSchemaVersion, "er-blind-label-view.v5")
    Vector("A9900000001", "W9900000002", "W9900000003", "a9900000001", "w9900000002")
      .foreach(x => assert(!jsonl.contains(x), s"'$x' survived: $jsonl"))
    view.foreach { r =>
      Vector(r.endpointA, r.endpointB).foreach { e =>
        if (e.sourceDataset == "openalex") {
          // Work and profile endpoint IDs carry the cluster ID; mention IDs are opaque hashes.
          if (e.endpointKind != "mention")
            assert(e.endpointId.startsWith(BlindView.MaskedRefPrefix), e.endpointId)
          assertEquals(e.sourceRecordId, M)
        }
      }
    }
    // The check is not vacuous: the unmasked evidence carries the IDs.
    val raw = items.flatMap(i => Vector(evidence(i.sourceIdA), evidence(i.sourceIdB))).map(write(_))
    assert(raw.exists(_.contains("W9900000002")) && raw.exists(_.contains("A9900000001")))
  }

  test("DOIs, ORCID iDs, institution IDs, titles, venues and coauthors stay visible") {
    val mention = view
      .flatMap(r => Vector(r.endpointA, r.endpointB))
      .find(e => e.endpointKind == "mention" && e.sourceDataset == "openalex")
      .get
    assertEquals(mention.nameAsWritten, Some("Quentin Synthwell"))
    assert(mention.identifiers.contains("orcid:0000-0002-1825-0097"), mention.identifiers.toString)
    assert(mention.identifiers.contains(M), "the OpenAlex author ID of the mention is masked")
    assertEquals(mention.institutionIds, Vector("institution:openalex:I9900000004"))
    val w = mention.work.get
    assertEquals(w.workId, M)
    assertEquals(w.title, "Synthetic Studies of Imaginary Systems")
    assertEquals(w.venue, Some("Journal of Synthetic Systems"))
    assert(w.identifiers.contains("doi:10.9999/syn.2021.1"), w.identifiers.toString)
    assert(w.identifiers.contains(M), w.identifiers.toString)
    assertEquals(w.authors, Vector("Quentin Synthwell", "Zora Fictus"))
    val profile =
      view.flatMap(r => Vector(r.endpointA, r.endpointB)).find(_.endpointKind == "profile").get
    assert(profile.identifiers.contains("orcid:0000-0002-1825-0097"))
    assert(!profile.identifiers.exists(_.contains("9900000001")), profile.identifiers.toString)
  }

  test("constant marker: two different W IDs look exactly like the same W ID twice") {
    def workRow(x: WorkRecord, y: WorkRecord): BlindRow = {
      val Vector(a, b) = Vector(x.workId, y.workId).sortWith(Canonical.compareUtf8(_, _) < 0)
      val id = GoldMembership.memberId("work", a, b).toOption.get
      BlindView
        .row(BlindItem(id, "work", a, b), BlindView.index(Vector(x, y), Vector.empty))
        .fold(e => fail(e), identity)
    }
    val crossref = crossrefWork
    def shown(r: BlindRow) = {
      val oa = Vector(r.endpointA, r.endpointB).find(_.sourceDataset == "openalex").get
      (r.maskingNotes, oa.copy(endpointId = ""))
    }
    // The same record under another W ID everywhere (work ID, record ID, identifier, link).
    val otherW = "W9900000003"
    val otherWork = openAlexWork.copy(
      workId = s"work:openalex:$otherW",
      sourceRecordId = otherW,
      additionalIds = Vector(ExactId(ExactIdScheme.OpenAlexWork, otherW)),
      eeLinks = Vector(s"https://openalex.org/$otherW", "https://doi.org/10.9999/syn.2021.1")
    )
    val one = workRow(openAlexWork, crossref)
    val other = workRow(otherWork, crossref)
    assertNotEquals(one.memberId, other.memberId)
    assertEquals(shown(one), shown(other), "the display does not reveal which W ID it was")
    assert(one.maskingNotes.contains(OpenAlexMasking.Note))
    assert(!write(other).contains(otherW))
  }

  test("a label may repeat the masked reference of an OpenAlex endpoint; it is checked") {
    val r = view.find(_.entityKind == "work").get
    val i = items.find(_.memberId == r.memberId).get
    assert(
      Vector(r.sourceIdA, r.sourceIdB).exists(_.startsWith(BlindView.MaskedRefPrefix)),
      Vector(r.sourceIdA, r.sourceIdB).toString
    )
    val refs = EndpointRef.index(works, profiles)
    val member = GoldMember(
      GoldMembership.SchemaVersion,
      i.memberId,
      i.entityKind,
      MemberEndpoint.of(refs(i.sourceIdA)),
      MemberEndpoint.of(refs(i.sourceIdB)),
      Vector("syn-unit"),
      "syn-criterion",
      "heldout",
      candidateRunId,
      "b",
      GoldMembership.CensusRule,
      None,
      None,
      1
    )
    def line(a: String, b: String) = write(
      GoldLabel(
        GoldLabels.SchemaVersion,
        r.memberId,
        r.entityKind,
        LabelGuide.target(r.entityKind),
        Some(LabelGuide.Version),
        a,
        b,
        Some("unsure"),
        Some("syn"),
        Some("2026-11-06T00:00:00Z"),
        None
      )
    )
    assert(GoldLabels.ingest(Vector(line(r.sourceIdA, r.sourceIdB)), Vector(member)).isRight)
    assert(GoldLabels.ingest(Vector(line(i.sourceIdA, i.sourceIdB)), Vector(member)).isRight)
    val forged = BlindView.MaskedRefPrefix + "0" * 64
    val bad =
      if (r.sourceIdA.startsWith(BlindView.MaskedRefPrefix)) line(forged, r.sourceIdB)
      else line(r.sourceIdA, forged)
    assert(GoldLabels.ingest(Vector(bad), Vector(member)).isLeft)
  }
}

object OpenAlexMaskingSuite {
  private val orcid = "0000-0002-1825-0097"
  private val authorId = ExternalId(ExternalIdScheme.OpenAlex, "A9900000001")

  /** A SYNTHETIC OpenAlex work record (W ID as source record ID and in its identifiers). */
  val openAlexWork: WorkRecord = Samples
    .buildWork(
      SourceType.OpenAlex,
      "work:openalex:W9900000002",
      "W9900000002",
      "Synthetic Studies of Imaginary Systems",
      Some(2021),
      None,
      Some("Journal of Synthetic Systems"),
      Some("10.9999/syn.2021.1"),
      Vector(
        A(
          "Quentin Synthwell",
          Some(orcid),
          affIds = Vector("institution:openalex:I9900000004"),
          sourceAuthorId = Some(authorId)
        ),
        A("Zora Fictus")
      )
    )
    .copy(
      additionalIds = Vector(ExactId(ExactIdScheme.OpenAlexWork, "W9900000002")),
      eeLinks = Vector("https://openalex.org/W9900000002", "https://doi.org/10.9999/syn.2021.1")
    )

  val crossrefWork: WorkRecord = Samples.buildWork(
    SourceType.Crossref,
    "work:doi:10.9999/syn.2021.1",
    "10.9999/syn.2021.1",
    "Synthetic Studies of Imaginary Systems",
    Some(2021),
    None,
    Some("Journal of Synthetic Systems"),
    Some("10.9999/syn.2021.1"),
    Vector(A("Quentin Synthwell", Some(orcid)), A("Zora Fictus"))
  )

  val openAlexProfile: SourceAuthorProfile = SourceAuthorProfile(
    schemaVersion = SchemaVersions.SourceAuthorProfile,
    profileId = "source-author:openalex:A9900000001",
    source = SourceType.OpenAlex,
    sourceRecordId = "A9900000001",
    displayName = Some("Quentin Synthwell"),
    givenNames = None,
    familyName = None,
    nameVariants = Vector("Q. Synthwell"),
    orcid = Some(orcid),
    externalIds = Vector.empty,
    institutionIds = Vector("institution:openalex:I9900000004"),
    claimedWorkIds = Vector("https://openalex.org/W9900000003"),
    provenance = Samples.prov(SourceType.OpenAlex, "A9900000001")
  )

  val works: Vector[WorkRecord] = Vector(openAlexWork, crossrefWork)
  val profiles: Vector[SourceAuthorProfile] = Vector(openAlexProfile)

  private def item(kind: String, x: String, y: String): BlindItem = {
    val Vector(a, b) = Vector(x, y).sortWith(Canonical.compareUtf8(_, _) < 0)
    BlindItem(GoldMembership.memberId(kind, a, b).toOption.get, kind, a, b)
  }

  val items: Vector[BlindItem] = Vector(
    item("work", openAlexWork.workId, crossrefWork.workId),
    item("researcher", openAlexWork.authors(0).mentionId, crossrefWork.authors(0).mentionId),
    item("researcher", openAlexProfile.profileId, crossrefWork.authors(0).mentionId)
  )
}
