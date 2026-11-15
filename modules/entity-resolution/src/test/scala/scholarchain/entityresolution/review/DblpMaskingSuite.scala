package scholarchain.entityresolution.review

import scholarchain.core.*
import scholarchain.entityresolution.Samples
import scholarchain.entityresolution.Samples.A
import scholarchain.entityresolution.evaluation.{GoldLabel, GoldLabels}
import scholarchain.entityresolution.policy.PolicyFixtures.{candidateRunId, repoRoot}

/**
 * DBLP display masking (ER-P5-06) over SYNTHETIC records shaped like real DBLP records: invented
 * people (Quentin Synthwell, Zora Fictus, Ines Imaginaria), invented pids (`99/9901`, `99/9902`,
 * `98/7703-1`), and the documented example ORCID iD 0000-0002-1825-0097 (a fictional holder). No
 * real person is named and no label is gold data.
 */
class DblpMaskingSuite extends munit.FunSuite {
  import DblpMaskingSuite.*

  private val evidence = BlindView.index(works, profiles)
  private val view: Vector[BlindRow] =
    BlindView.build(items, evidence, "t").fold(e => fail(e.mkString("; ")), identity)
  private val jsonl = BlindView.toJsonl(view)

  test(
    "pattern table: suffixes, pid identifiers, person links, spaced pids; publication links kept"
  ) {
    val M = DblpMasking.Masked
    Vector(
      "Quentin Synthwell 0001" -> "Quentin Synthwell",
      "Synthwell 0002, Quentin" -> "Synthwell, Quentin",
      "Quentin 0001 / Synthwell" -> "Quentin / Synthwell",
      "Quentin Synthwell" -> "Quentin Synthwell",
      "Quentin Synthwell 2nd" -> "Quentin Synthwell 2nd",
      "Q. Synthwell 12345" -> "Q. Synthwell 12345"
    ).foreach { case (in, want) => assertEquals(DblpMasking.name(in), want, in) }
    Vector(
      "dblp-pid:99/9901" -> M,
      "source-author:dblp-pid:99/9901" -> s"source-author:$M",
      "https://dblp.org/pid/99/9901.html" -> M,
      "https://dblp.uni-trier.de/pid/98/7703-1" -> M,
      "http://dblp.org/pers/hd/s/Synthwell_0001:Quentin" -> M,
      "dblp.org/pers/s/Synthwell:Quentin.html" -> M,
      "https://dblp.org/rec/homepages/99/9901" -> M,
      "https://dblp.org/search?q=Synthwell&pid=99/9901" -> M,
      "https://dblp.org/search/publ/api?q=pid%3A99%2F9901" -> M,
      "https://example.org/profile?dblp_id=99/9901" -> M,
      "pid/99/9901.html" -> M,
      "/pers/s/Synthwell:Quentin" -> M,
      "homepages/99/9901" -> M,
      "dblp-key:homepages/99/9901" -> M,
      s"see dblp pid 99/9901 here" -> s"see $M here",
      "DBLP_PID = 99/9901" -> M,
      "dblp-pid" -> M,
      "https://dblp.org/rec/journals/syn/Synthwell21.html" ->
        "https://dblp.org/rec/journals/syn/Synthwell21.html",
      "db/journals/syn/syn21.html#Synthwell21" -> "db/journals/syn/syn21.html#Synthwell21",
      "work:dblp:journals/syn/Synthwell21" -> "work:dblp:journals/syn/Synthwell21",
      "doi:10.9999/syn.2021.1" -> "doi:10.9999/syn.2021.1",
      "https://doi.org/10.9999/syn.2021.1" -> "https://doi.org/10.9999/syn.2021.1",
      "orcid:0000-0002-1825-0097" -> "orcid:0000-0002-1825-0097"
    ).foreach { case (in, want) => assertEquals(DblpMasking.text(in), want, in) }
    // A pid value found elsewhere in the pair is masked wherever it occurs as a whole value.
    assertEquals(DblpMasking.text("dblp:99/9901", Set("99/9901")), s"dblp:$M")
    assertEquals(DblpMasking.text("dblp:99/9901"), "dblp:99/9901", "a bare value needs context")
    assertEquals(DblpMasking.text("doi:10.1199/99010", Set("99/9901")), "doi:10.1199/99010")
    assertEquals(
      DblpMasking.pidValues(Vector("https://dblp.org/pid/99/9901.html", "dblp-pid:98/7703-1")),
      Set("99/9901", "98/7703-1")
    )
  }

  test("D4: a homonym number after any Unicode separator or format character is masked (v2)") {
    assertEquals(DblpMasking.Version, "er-dblp-display-mask.v2")
    // U+00A0, U+2007, U+202F (Zs); U+2028 (Zl); U+200B, U+2060, U+FEFF, U+00AD (Cf).
    val separators = Vector(0x00a0, 0x2007, 0x202f, 0x2028, 0x200b, 0x2060, 0xfeff, 0x00ad)
    separators.map(_.toChar.toString).foreach { sep =>
      val tag = f"U+${sep.codePointAt(0)}%04X"
      assertEquals(DblpMasking.name(s"Quentin Synthwell${sep}0001"), "Quentin Synthwell", tag)
      assertEquals(DblpMasking.name(s"Synthwell${sep}0002, Quentin"), "Synthwell, Quentin", tag)
      // A trailing separator or format character after the number does not hide it either.
      assert(!DblpMasking.name(s"Quentin Synthwell 0001$sep").contains("0001"), tag)
    }
    // Without any separator the number is part of the name and stays (NIT-2 is unchanged).
    assertEquals(DblpMasking.name("Quentin Synthwell0001"), "Quentin Synthwell0001")
    // The parser limitation NIT-2 is untouched: a format character still joins the family key.
    val zwsp = 0x200b.toChar.toString
    val parsed = scholarchain.entityresolution.PersonName.parse(s"Christopher Frost${zwsp}0001")
    assertEquals((parsed.familyKey, parsed.homonymSuffix), ("frost0001", None))
    // Titles are not person names: their digits stay.
    assertEquals(DblpMasking.text(s"Report${zwsp}2021"), s"Report${zwsp}2021")
  }

  test("D5: a dblp person URL inside a percent-encoded link, nested or not, masks the token") {
    val M = DblpMasking.Masked
    Vector(
      "https://redirect.example.org/out?to=https%3A%2F%2Fdblp.org%2Fpid%2F99%2F9901.html",
      "https://redirect.example.org/out?to=https%253A%252F%252Fdblp.org%252Fpid%252F99%252F9901",
      "https://r.example.org/?u=https%3A%2F%2Fdblp.uni-trier.de%2Fpers%2Fhd%2Fs%2FSynthwell",
      "https://r.example.org/?u=dblp.org%2Frec%2Fhomepages%2F99%2F9901",
      "https://r.example.org/?q=dblp%20pid%3A99%2F9901",
      "https%3A%2F%2Fdblp.org%2Fpid%2F99%2F9901"
    ).foreach(in => assertEquals(DblpMasking.text(in), M, in))
    // The pid value inside the encoding is found and masked elsewhere in the pair.
    val pids = DblpMasking.pidValues(
      Vector("https://r.example.org/?to=https%3A%2F%2Fdblp.org%2Fpid%2F99%2F9901.html")
    )
    assertEquals(pids, Set("99/9901"))
    assertEquals(DblpMasking.text("dblp:99/9901", pids), s"dblp:$M")
    // Encoded publication links and DOIs are kept.
    Vector(
      "https://r.example.org/?to=https%3A%2F%2Fdblp.org%2Frec%2Fjournals%2Fsyn%2FSynthwell21",
      "https://doi.org/10.9999%2Fsyn.2021.1",
      "100%25 synthetic"
    ).foreach(in => assertEquals(DblpMasking.text(in), in, in))
    // Masking stays idempotent.
    assertEquals(DblpMasking.text(M), M)
  }

  test("percent-decoding is for detection only: layered, bounded, total") {
    assertEquals(
      DisplayDecoding.layers("a%252Fb"),
      Vector("a%252Fb", "a%2Fb", "a/b")
    )
    assertEquals(DisplayDecoding.layers("no-encoding"), Vector("no-encoding"))
    assertEquals(DisplayDecoding.decodeOnce("50% off %zz %4"), "50% off %zz %4")
    assertEquals(DisplayDecoding.decodeOnce("%C3%B6"), "ö")
    assertEquals(DisplayDecoding.decodeOnce("a+b"), "a+b")
    val deep = (0 until 20).foldLeft("/")((s, _) => s.replace("%", "%25").replace("/", "%2F"))
    assert(DisplayDecoding.layers(deep).size <= DisplayDecoding.MaxLayers + 1)
  }

  test("names, coauthors, identifier lists and URLs are masked in the view") {
    val dblpMention = endpoint(view, mentionId(dblpWork1, 0))
    assertEquals(dblpMention.nameAsWritten, Some("Quentin Synthwell"))
    assertEquals(dblpMention.identifiers, Vector("orcid:0000-0002-1825-0097", DblpMasking.Masked))
    val w = dblpMention.work.get
    assertEquals(w.authors, Vector("Quentin Synthwell", "Zora Fictus", "Ines Imaginaria"))
    assert(w.identifiers.contains(DblpMasking.Masked), w.identifiers.toString)
    assert(w.eeLinks.contains(DblpMasking.Masked) && w.urls.contains(DblpMasking.Masked))
    val zora = endpoint(view, mentionId(dblpWork1, 1))
    assertEquals(zora.affiliations, Vector(s"Synthetic Institute, ${DblpMasking.Masked}"))
    val profile = view
      .flatMap(r => Vector(r.endpointA, r.endpointB))
      .find(_.endpointKind == "profile")
      .get
    assert(profile.endpointId.startsWith("masked-endpoint:"), profile.endpointId)
    assertEquals(profile.sourceRecordId, DblpMasking.Masked)
    assertEquals(profile.nameAsWritten, Some("Quentin Synthwell"))
    assertEquals(profile.nameVariants, Vector("Synthwell, Quentin", "Q. Synthwell"))
    assert(!profile.identifiers.exists(_.contains("99/9901")), profile.identifiers.toString)
    assert(profile.identifiers.contains("orcid:0000-0002-1825-0097"))
    view
      .filter(r => r.endpointA.endpointKind == "profile" || r.endpointB.endpointKind == "profile")
      .foreach { r =>
        assert(Vector(r.sourceIdA, r.sourceIdB).exists(_.startsWith("masked-endpoint:")))
        assertEquals(r.maskingNotes, Vector(DblpMasking.HomonymNote, DblpMasking.PersonIdNote))
      }
  }

  test("no dblp-pid, pid fragment, person link, or homonym number survives anywhere in the view") {
    assertEquals(survivors(view), Vector.empty)
    // The check is not vacuous: the unmasked source evidence fails it.
    val unmasked = view.map(r =>
      r.copy(
        endpointA = evidence(rawIdOf(r.endpointA.endpointId)),
        endpointB = evidence(rawIdOf(r.endpointB.endpointId))
      )
    )
    assert(survivors(unmasked).nonEmpty)
    Vector("99/9901", "99/9902", "98/7703-1", "99-9901", "dblp-pid", "/pid/", "/pers/")
      .foreach(x => assert(!jsonl.contains(x), s"'$x' survived"))
    // No four-digit group follows a name anywhere (hash IDs are excluded: hex can contain digits).
    allStrings(jsonl.linesIterator.mkString("[", ",", "]"))
      .filterNot(s =>
        s.startsWith("gm:") || s.startsWith("masked-endpoint:") || s.startsWith("mention:")
      )
      .foreach(s => assert(!"(?U)\\s[0-9]{4}(?:\\s*[,;/]|$)".r.findFirstIn(s).isDefined, s))
  }

  test("non-DBLP evidence stays: DOI, ORCID, affiliation, coauthors, title, venue, links") {
    val crossref = endpoint(view, mentionId(crossrefWork, 0))
    assertEquals(crossref.nameAsWritten, Some("Quentin Synthwell"))
    assertEquals(crossref.affiliations, Vector("Synthetic University"))
    assert(crossref.identifiers.contains("orcid:0000-0002-1825-0097"))
    val w = crossref.work.get
    assertEquals(w.identifiers, Vector("doi:10.9999/syn.2021.1"))
    assertEquals(w.authors, Vector("Quentin Synthwell", "Zora Fictus", "Ines Imaginaria"))
    val d = endpoint(view, mentionId(dblpWork1, 0)).work.get
    assertEquals(d.title, "Synthetic Studies of Imaginary Systems.")
    assertEquals((d.year, d.venue), (Some(2021), Some("J. Synth. Syst.")))
    assert(d.identifiers.contains("doi:10.9999/syn.2021.1"))
    assert(d.eeLinks.contains("https://doi.org/10.9999/syn.2021.1"))
    assert(d.urls.contains("db/journals/syn/syn21.html#Synthwell21"))
    assertEquals(d.workId, dblpWork1.workId)
    val profile =
      view.flatMap(r => Vector(r.endpointA, r.endpointB)).find(_.endpointKind == "profile")
    assertEquals(profile.get.institutionIds, Vector("institution:ror:0abcdef12"))
  }

  test("masking is deterministic, input-order independent, and idempotent") {
    val again = BlindView.build(items.reverse, BlindView.index(works.reverse, profiles), "t")
    assertEquals(again.map(BlindView.toJsonl), Right(jsonl))
    view.foreach { r =>
      Vector(r.endpointA, r.endpointB).foreach { e =>
        e.nameAsWritten.foreach(n => assertEquals(DblpMasking.name(n), n))
        e.identifiers.foreach(i => assertEquals(DblpMasking.text(i), i))
      }
    }
  }

  test("one constant marker: homonym 0001 vs 0002 looks the same as 0001 vs 0001") {
    val different = view.find(_.memberId == member("researcher", dblpWork1, 0, dblpWork2, 0)).get
    val same = view.find(_.memberId == member("researcher", dblpWork1, 0, dblpWork3, 0)).get
    assertEquals(different.maskingNotes, same.maskingNotes)
    assertEquals(
      Vector(different.endpointA, different.endpointB).map(_.nameAsWritten),
      Vector(same.endpointA, same.endpointB).map(_.nameAsWritten)
    )
  }

  test("source records and the membership manifest are untouched; provenance stays") {
    assertEquals(dblpWork1.authors(0).rawName, "Quentin Synthwell 0001")
    assertEquals(evidence(mentionId(dblpWork1, 0)).nameAsWritten, Some("Quentin Synthwell 0001"))
    assertEquals(dblpProfile.profileId, "source-author:dblp-pid:99/9901")
    // DS-P5-01 selection units name no dblp person records, so the manifest pairs here are the
    // mention and work pairs; the profile pair is covered by the label-reference test below.
    val refs = EndpointRef.index(works, profiles)
    val units =
      SyntheticUnits.of(Vector(("syn-mask-unit", "syn-mask", "heldout", works.map(_.workId))))
    val mentionItems = items
      .filterNot(_.sourceIdA.startsWith("source-author:"))
      .filterNot(
        _.sourceIdB.startsWith("source-author:")
      )
    val pairs = mentionItems.map(i =>
      CandidatePair(i.entityKind, i.sourceIdA, i.sourceIdB, "b", candidateRunId)
    )
    val manifest = GoldMembership
      .build(pairs, refs, units, MembershipDesign.census("heldout"))
      .fold(e => fail(e.mkString("; ")), identity)
    val text = GoldMembership.toJsonl(manifest)
    assert(text.contains("\"sourceRecordId\":\"journals/syn/Synthwell21\""), "record IDs kept")
    assert(text.contains(mentionId(dblpWork1, 0)))
    val fromManifest = RunQueue
      .blindOfMembers(manifest, CuratedRun(repoRoot, works, profiles, Vector.empty), "t")
      .fold(e => fail(e.mkString("; ")), identity)
    val sameRows = view.filter(r => manifest.exists(_.memberId == r.memberId))
    assertEquals(sameRows.size, manifest.size)
    assertEquals(BlindView.toJsonl(fromManifest), BlindView.toJsonl(sameRows))
    // The internal endpoint reference of the profile keeps its pid for provenance.
    val profileRef = refs(dblpProfile.profileId)
    assertEquals((profileRef.sourceDataset, profileRef.sourceRecordId), ("dblp", "99/9901"))
  }

  test("a label may repeat the shown masked reference; it is checked against the manifest") {
    val refs = EndpointRef.index(works, profiles)
    val r = view
      .find(r =>
        r.sourceIdA.startsWith("masked-endpoint:") || r.sourceIdB.startsWith("masked-endpoint:")
      )
      .get
    val i = items.find(_.memberId == r.memberId).get
    val manifest = Vector(
      GoldMember(
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
    )
    assert(GoldMembership.toJsonl(manifest).contains("source-author:dblp-pid:99/9901"))
    def line(a: String, b: String) = upickle.default.write(
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
    assert(GoldLabels.ingest(Vector(line(r.sourceIdA, r.sourceIdB)), manifest).isRight)
    val m = manifest.find(_.memberId == r.memberId).get
    assert(
      GoldLabels
        .ingest(Vector(line(m.endpointA.endpointId, m.endpointB.endpointId)), manifest)
        .isRight
    )
    val forged = "masked-endpoint:" + "0" * 64
    val notShown =
      if (r.sourceIdA.startsWith("masked")) line(forged, r.sourceIdB) else line(r.sourceIdA, forged)
    assert(GoldLabels.ingest(Vector(notShown), manifest).isLeft)
  }
}

object DblpMaskingSuite {
  private val orcid = "0000-0002-1825-0097"
  private val pidQ1 = ExternalId(ExternalIdScheme.DblpPid, "99/9901")
  private val pidQ2 = ExternalId(ExternalIdScheme.DblpPid, "99/9902")

  private def dblp(key: String, title: String, authors: Vector[A]): WorkRecord =
    Samples.buildWork(
      SourceType.Dblp,
      s"work:dblp:$key",
      key,
      title,
      Some(2021),
      None,
      Some("J. Synth. Syst."),
      Some("10.9999/syn.2021.1"),
      authors
    )

  val dblpWork1: WorkRecord = dblp(
    "journals/syn/Synthwell21",
    "Synthetic Studies of Imaginary Systems.",
    Vector(
      A("Quentin Synthwell 0001", Some(orcid), sourceAuthorId = Some(pidQ1)),
      A("Zora Fictus 0002", affs = Vector("Synthetic Institute, https://dblp.org/pid/98/7703-1")),
      A("Ines Imaginaria")
    )
  ).copy(
    eeLinks = Vector("https://doi.org/10.9999/syn.2021.1", "https://dblp.org/pid/99/9901.html"),
    urls = Vector("db/journals/syn/syn21.html#Synthwell21", "pers/hd/s/Synthwell_0001:Quentin"),
    additionalIds = Vector(ExactId(ExactIdScheme.DblpKey, "homepages/99/9901"))
  )

  /** Another dblp homonym (0002) of the same name: DBLP says a different person. */
  val dblpWork2: WorkRecord = dblp(
    "journals/syn/Synthwell22",
    "Further Synthetic Studies.",
    Vector(A("Quentin Synthwell 0002", sourceAuthorId = Some(pidQ2)), A("Ines Imaginaria"))
  )

  /** The same dblp homonym (0001) on a third work: DBLP says the same person. */
  val dblpWork3: WorkRecord = dblp(
    "journals/syn/Synthwell23",
    "Yet More Synthetic Studies.",
    Vector(A("Quentin Synthwell 0001", sourceAuthorId = Some(pidQ1)), A("Zora Fictus 0002"))
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
    Vector(
      A("Quentin Synthwell", Some(orcid), affs = Vector("Synthetic University")),
      A("Zora Fictus"),
      A("Ines Imaginaria")
    )
  )

  val dblpProfile: SourceAuthorProfile = SourceAuthorProfile(
    schemaVersion = SchemaVersions.SourceAuthorProfile,
    profileId = "source-author:dblp-pid:99/9901",
    source = SourceType.Dblp,
    sourceRecordId = "99/9901",
    displayName = Some("Quentin Synthwell 0001"),
    givenNames = None,
    familyName = None,
    nameVariants = Vector("Synthwell 0001, Quentin", "Q. Synthwell"),
    orcid = Some(orcid),
    externalIds = Vector(pidQ1),
    institutionIds = Vector("institution:ror:0abcdef12"),
    claimedWorkIds = Vector.empty,
    provenance = Samples.prov(SourceType.Dblp, "homepages/99/9901")
  )

  val works: Vector[WorkRecord] = Vector(dblpWork1, dblpWork2, dblpWork3, crossrefWork)
  val profiles: Vector[SourceAuthorProfile] = Vector(dblpProfile)

  def mentionId(w: WorkRecord, i: Int): String = w.authors(i).mentionId

  def member(kind: String, wa: WorkRecord, ia: Int, wb: WorkRecord, ib: Int): String =
    GoldMembership.memberId(kind, mentionId(wa, ia), mentionId(wb, ib)).toOption.get

  private def item(kind: String, x: String, y: String): BlindItem = {
    val Vector(a, b) = Vector(x, y).sortWith(Canonical.compareUtf8(_, _) < 0)
    BlindItem(GoldMembership.memberId(kind, a, b).toOption.get, kind, a, b)
  }

  val items: Vector[BlindItem] = Vector(
    item("researcher", mentionId(dblpWork1, 0), mentionId(crossrefWork, 0)),
    item("researcher", dblpProfile.profileId, mentionId(crossrefWork, 0)),
    item("researcher", mentionId(dblpWork1, 0), mentionId(dblpWork2, 0)),
    item("researcher", mentionId(dblpWork1, 0), mentionId(dblpWork3, 0)),
    item("researcher", mentionId(dblpWork1, 1), mentionId(crossrefWork, 1)),
    item("work", dblpWork1.workId, crossrefWork.workId)
  )

  def endpoint(view: Vector[BlindRow], id: String): BlindEndpoint =
    view.flatMap(r => Vector(r.endpointA, r.endpointB)).find(_.endpointId == id).get

  /** The source endpoint ID behind a shown reference (a masked reference maps to the profile). */
  def rawIdOf(shown: String): String =
    if (shown.startsWith(BlindView.MaskedRefPrefix)) dblpProfile.profileId else shown

  private val Forbidden = Vector(
    "(?i)dblp[\\s_-]?pid".r,
    "(?i)dblp[^\\s\"]*(?:/pid/|/pers/|homepages/)".r,
    "(?i)(?:^|[\\s:])(?:pid|pers|homepages)/".r,
    "99/990[12]|98/7703-1|99-990[12]".r
  )

  /** Every surviving pid or homonym pattern in a view: person-name fields and every string. */
  def survivors(view: Vector[BlindRow]): Vector[String] =
    view.flatMap { r =>
      val es = Vector(r.endpointA, r.endpointB)
      val names = es.flatMap(e =>
        e.nameAsWritten.toVector ++ e.structuredName.toVector ++ e.nameVariants ++
          e.work.toVector.flatMap(_.authors)
      )
      val strings = allStrings(upickle.default.write(r))
      names
        .filter(n => DblpMasking.HomonymSuffix.findFirstIn(n).isDefined)
        .map(n => s"name '$n'") ++
        strings.flatMap(s => Forbidden.filter(_.findFirstIn(s).isDefined).map(p => s"'$s' ~ $p"))
    }

  /** Every string leaf of a JSON document. */
  def allStrings(json: String): Vector[String] = {
    def go(v: ujson.Value): Vector[String] = v match {
      case ujson.Obj(m) => m.values.toVector.flatMap(go)
      case ujson.Arr(xs) => xs.toVector.flatMap(go)
      case ujson.Str(s) => Vector(s)
      case _ => Vector.empty
    }
    go(ujson.read(json))
  }
}
