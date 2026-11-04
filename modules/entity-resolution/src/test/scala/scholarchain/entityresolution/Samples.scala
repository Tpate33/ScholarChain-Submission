package scholarchain.entityresolution

import scholarchain.core.*

/**
 * Test records mirroring the committed samples under `datasets/samples/`: the four DBLP records of
 * `dblp/dblp-sample.xml` (entities decoded: `P&ouml;pper` is `Pöpper`, `Varaj&atilde;o` is
 * `Varajão`), the Crossref Spanner and CACM records of `crossref/works-sample.jsonl` (rawName =
 * `given family`, as the Hadoop Crossref normalizer builds it), the OpenAlex author `A5113870699`
 * of `openalex/authors-sample.jsonl`, and the ORCID holder name of `orcid/0000-0002-2814-962X.xml`
 * (`Christina` / `Poepper`). `venueName` is filled as the core migration notes for Hadoop work
 * order 3 describe. Records marked SYNTHETIC are invented for edge cases and assert nothing about
 * real people or works.
 */
object Samples {
  def ok[A](e: Either[?, A]): A =
    e.fold(err => throw new AssertionError(s"fixture is invalid: $err"), identity)

  val runId: String = "run:" + "0123456789abcdef" * 4

  def prov(source: SourceType, recordId: String): Provenance = {
    val (snapshot, schema) = source match {
      case SourceType.Dblp => ("dblp-2026-10-03", "dblp-raw/1.0.0")
      case SourceType.OpenAlex => ("openalex-2026-10-14", "openalex-raw/1.0.0")
      case SourceType.Crossref => ("crossref-2026-10-01", "crossref-raw/1.0.0")
      case _ => ("orcid-2025-10-01", "orcid-raw/1.0.0")
    }
    Provenance(source, recordId, snapshot, runId, schema, None, None, None)
  }

  /** One author as written: raw name, ORCID, raw affiliation strings. */
  final case class A(
      name: String,
      orcid: Option[String] = None,
      affs: Vector[String] = Vector.empty,
      affIds: Vector[String] = Vector.empty,
      sourceAuthorId: Option[ExternalId] = None
  )

  def buildWork(
      source: SourceType,
      workId: String,
      recordId: String,
      title: String,
      year: Option[Int],
      venueId: Option[String],
      venueName: Option[String],
      doi: Option[String],
      authors: Vector[A]
  ): WorkRecord = {
    val p = prov(source, recordId)
    WorkRecord(
      schemaVersion = SchemaVersions.WorkRecord,
      workId = workId,
      source = source,
      sourceRecordId = recordId,
      title = title,
      normalizedTitle = title.toLowerCase(java.util.Locale.ROOT),
      year = year,
      venueId = venueId,
      doi = doi,
      arxivId = None,
      authors = authors.zipWithIndex.map { case (a, i) =>
        AuthorMention(
          schemaVersion = SchemaVersions.AuthorMention,
          mentionId = ok(MentionId.of(workId, i)),
          workId = workId,
          position = i,
          rawName = a.name,
          normalizedName = a.name.toLowerCase(java.util.Locale.ROOT),
          sourceAuthorId = a.sourceAuthorId,
          orcid = a.orcid,
          affiliationIds = a.affIds,
          rawAffiliations = a.affs,
          provenance = p
        )
      },
      abstractTextRef = None,
      provenance = p,
      venueName = venueName
    )
  }

  val spannerNames: Vector[String] = Vector(
    "James C. Corbett",
    "Jeffrey Dean",
    "Michael Epstein",
    "Andrew Fikes",
    "Christopher Frost 0001",
    "J. J. Furman",
    "Sanjay Ghemawat",
    "Andrey Gubarev",
    "Christopher Heiser",
    "Peter Hochschild",
    "Wilson C. Hsieh",
    "Sebastian Kanthak",
    "Eugene Kogan",
    "Hongyi Li",
    "Alexander Lloyd",
    "Sergey Melnik 0001",
    "David Mwaura",
    "David Nagle",
    "Sean Quinlan",
    "Rajesh Rao",
    "Lindsay Rolig",
    "Yasushi Saito",
    "Michal Szymaniak",
    "Christopher Taylor",
    "Ruth Wang",
    "Dale Woodford"
  )

  val spannerDblp: WorkRecord = buildWork(
    SourceType.Dblp,
    "work:dblp:journals/tocs/CorbettDEFFFGGHHHKKLLMMNQRRSSTWW13",
    "journals/tocs/CorbettDEFFFGGHHHKKLLMMNQRRSSTWW13",
    "Spanner: Google's Globally Distributed Database.",
    Some(2013),
    None,
    Some("ACM Trans. Comput. Syst."),
    Some("10.1145/2491245"),
    spannerNames.map(A(_))
  )

  /** Crossref: `given family`; DBLP homonym numbers absent; middle initial of Hsieh absent. */
  val spannerCrossrefNames: Vector[String] = spannerNames.map {
    case "Christopher Frost 0001" => "Christopher Frost"
    case "Sergey Melnik 0001" => "Sergey Melnik"
    case "Wilson C. Hsieh" => "Wilson Hsieh"
    case other => other
  }

  val spannerCrossref: WorkRecord = buildWork(
    SourceType.Crossref,
    "work:doi:10.1145/2491245",
    "10.1145/2491245",
    "Spanner: Google's Globally Distributed Database",
    Some(2013),
    Some("venue:issn:0734-2071"),
    Some("ACM Transactions on Computer Systems"),
    Some("10.1145/2491245"),
    spannerCrossrefNames.map(n => A(n, affs = Vector("Google, Inc.")))
  )

  val cacmDblp: WorkRecord = buildWork(
    SourceType.Dblp,
    "work:dblp:journals/cacm/PopperMP21",
    "journals/cacm/PopperMP21",
    "Cyber security research in the Arab region: a blooming ecosystem with global ambitions.",
    Some(2021),
    None,
    Some("Commun. ACM"),
    Some("10.1145/3447741"),
    Vector(
      A("Christina Pöpper", Some("0000-0002-2814-962X")),
      A("Michail Maniatakos", Some("0000-0001-6899-0651")),
      A("Roberto Di Pietro", Some("0000-0003-1909-0336"))
    )
  )

  val cacmCrossref: WorkRecord = buildWork(
    SourceType.Crossref,
    "work:doi:10.1145/3447741",
    "10.1145/3447741",
    "Cyber security research in the Arab region: a blooming ecosystem with global ambitions",
    Some(2021),
    Some("venue:issn:0001-0782"),
    Some("Communications of the ACM"),
    Some("10.1145/3447741"),
    Vector(
      A("Christina Pöpper", affs = Vector("NYU Abu Dhabi, UAE")),
      A("Michail Maniatakos", affs = Vector("NYU Abu Dhabi, UAE")),
      A("Roberto Di Pietro", affs = Vector("HBKU-CSE, Doha-Qatar"))
    )
  )

  val varajaoDblp: WorkRecord = buildWork(
    SourceType.Dblp,
    "work:dblp:journals/cacm/Varajao21",
    "journals/cacm/Varajao21",
    "Software development in disruptive times.",
    Some(2021),
    None,
    Some("Commun. ACM"),
    Some("10.1145/3453932"),
    Vector(A("João Varajão", Some("0000-0002-4303-3908")))
  )

  val bigtableDblp: WorkRecord = buildWork(
    SourceType.Dblp,
    "work:dblp:conf/osdi/ChangDGHWBCFG06",
    "conf/osdi/ChangDGHWBCFG06",
    "Bigtable: A Distributed Storage System for Structured Data (Awarded Best Paper!).",
    Some(2006),
    Some("venue:dblp:conf/osdi/2006"),
    Some("OSDI"),
    None,
    Vector(
      "Fay Chang",
      "Jeffrey Dean",
      "Sanjay Ghemawat",
      "Wilson C. Hsieh",
      "Deborah A. Wallach",
      "Michael Burrows",
      "Tushar Chandra",
      "Andrew Fikes",
      "Robert Gruber"
    ).map(A(_))
  )

  /** SYNTHETIC: no year, no venue, a single-name author and a second author. */
  val synthetic: WorkRecord = buildWork(
    SourceType.Dblp,
    "work:dblp:journals/example/Synthetic01",
    "journals/example/Synthetic01",
    "On the Collaboration of Examples.",
    None,
    None,
    None,
    None,
    Vector(A("Madonna"), A("Ada Example"))
  )

  /** OpenAlex author A5113870699 (authors-sample.jsonl). */
  val popperOpenAlex: SourceAuthorProfile = SourceAuthorProfile(
    schemaVersion = SchemaVersions.SourceAuthorProfile,
    profileId = "source-author:openalex:A5113870699",
    source = SourceType.OpenAlex,
    sourceRecordId = "A5113870699",
    displayName = Some("Christina Pöpper"),
    givenNames = None,
    familyName = None,
    nameVariants = Vector(
      "Christina Poepper",
      "Christina Popper",
      "Christina Pöpper",
      "Poepper, Christina",
      "Pöpper, C.",
      "Pöpper, Christina"
    ),
    orcid = Some("0000-0002-2814-962X"),
    externalIds = Vector.empty,
    institutionIds = Vector("institution:openalex:I120250893"),
    claimedWorkIds = Vector.empty,
    provenance = prov(SourceType.OpenAlex, "A5113870699")
  )

  /** ORCID holder 0000-0002-2814-962X (public name `Christina` / `Poepper`). */
  val popperOrcid: SourceAuthorProfile = SourceAuthorProfile(
    schemaVersion = SchemaVersions.SourceAuthorProfile,
    profileId = "source-author:orcid:0000-0002-2814-962X",
    source = SourceType.Orcid,
    sourceRecordId = "0000-0002-2814-962X",
    displayName = None,
    givenNames = Some("Christina"),
    familyName = Some("Poepper"),
    nameVariants = Vector.empty,
    orcid = Some("0000-0002-2814-962X"),
    externalIds = Vector.empty,
    institutionIds = Vector.empty,
    claimedWorkIds = Vector.empty,
    provenance = prov(SourceType.Orcid, "0000-0002-2814-962X")
  )

  def m(w: WorkRecord, i: Int): AuthorMention = w.authors(i)
  def em(w: WorkRecord, i: Int): ResearcherEndpoint = ResearcherEndpoint.OfMention(w, w.authors(i))
  def ep(p: SourceAuthorProfile): ResearcherEndpoint = ResearcherEndpoint.OfProfile(p)
}
