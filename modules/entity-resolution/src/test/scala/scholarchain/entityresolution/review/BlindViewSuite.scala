package scholarchain.entityresolution.review

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import upickle.default.write

import scholarchain.core.{Canonical, MatchOutcome}
import scholarchain.entityresolution.Samples
import scholarchain.entityresolution.Samples.{em, ep}
import scholarchain.entityresolution.policy.*
import scholarchain.entityresolution.policy.PolicyFixtures.*

/**
 * The blind labeling view (human decision 4) over sample-mirroring records, with SYNTHETIC
 * selection units. No labels anywhere.
 */
class BlindViewSuite extends munit.FunSuite {

  private val ada = new MatchFeaturesFixtures
  private val adaWorks = Vector(ada.adaA, ada.adaB).collect {
    case scholarchain.entityresolution.ResearcherEndpoint.OfMention(w, _) => w
  }
  private val works = Vector(
    Samples.spannerDblp,
    Samples.spannerCrossref,
    Samples.cacmDblp,
    Samples.cacmCrossref,
    Samples.varajaoDblp,
    Samples.bigtableDblp
  ) ++ adaWorks
  private val profiles = Vector(Samples.popperOpenAlex, Samples.popperOrcid)
  private val refs = EndpointRef.index(works, profiles)
  private val evidence = BlindView.index(works, profiles)

  private val candidates = Vector(
    researcherCandidate(em(Samples.cacmDblp, 0), ep(Samples.popperOpenAlex)),
    researcherCandidate(em(Samples.spannerDblp, 4), em(Samples.spannerCrossref, 4)),
    researcherCandidate(em(Samples.spannerDblp, 1), em(Samples.bigtableDblp, 1)),
    researcherCandidate(em(Samples.cacmCrossref, 0), ep(Samples.popperOrcid)),
    researcherCandidate(ada.adaA, ada.adaB),
    workCandidate(Samples.spannerDblp, Samples.spannerCrossref),
    workCandidate(Samples.cacmDblp, Samples.varajaoDblp),
    workCandidate(Samples.cacmDblp, Samples.cacmCrossref)
  )
  private val outcomes: Vector[ResolutionOutcome] = candidates.map(decide(_))
  private val internal =
    ReviewQueue
      .buildDevDiagnostics(
        policy,
        outcomes,
        refs,
        SamplingDesign(240, 10, "t"),
        SelectionUnits.Empty
      )
      .fold(e => fail(e.mkString("; ")), _.rows)
  private val blind =
    BlindView
      .build(internal.map(BlindItem.of), evidence, "t")
      .fold(e => fail(e.mkString("; ")), identity)

  /**
   * SYNTHETIC selection units whose names mimic the DS-P5-01 forms, including a unit ID that
   * encodes a dblp person ID (`nv-208-1029-...`) and an `ov-` unit.
   */
  private val synUnits = Vector(
    ("nv-208-1029-synthwell", "name-variant", "heldout", Vector(Samples.cacmDblp.workId)),
    ("ov-16-493-1", "orcid-vs-none", "heldout", Vector(Samples.popperOpenAlex.profileId)),
    (
      "hr-syn-homonym",
      "homonym-rare",
      "heldout",
      Vector(
        Samples.spannerDblp.workId,
        Samples.spannerCrossref.workId,
        Samples.bigtableDblp.workId
      )
    ),
    ("cj-syn-varajao", "conf-vs-journal", "heldout", Vector(Samples.varajaoDblp.workId))
  )
  private val members: Vector[GoldMember] =
    GoldMembership
      .build(
        candidates.take(3).map(CandidatePair.of) ++
          Vector(candidates(5), candidates(6)).map(CandidatePair.of),
        refs,
        SyntheticUnits.of(synUnits),
        MembershipDesign.census("heldout")
      )
      .fold(e => fail(e.mkString("; ")), identity)
  private val memberBlind =
    RunQueue
      .blindOfMembers(members, CuratedRun(repoRoot, works, profiles, candidates), "t")
      .fold(e => fail(e.mkString("; ")), identity)

  /** Every object key and every string and number leaf of a JSON document. */
  private def leaves(v: ujson.Value): (Vector[String], Vector[String], Vector[Double]) = v match {
    case ujson.Obj(m) =>
      m.foldLeft((m.keys.toVector, Vector.empty[String], Vector.empty[Double])) {
        case ((k, s, n), (_, child)) =>
          val (k2, s2, n2) = leaves(child)
          (k ++ k2, s ++ s2, n ++ n2)
      }
    case ujson.Arr(xs) =>
      xs.foldLeft((Vector.empty[String], Vector.empty[String], Vector.empty[Double])) {
        case ((k, s, n), child) =>
          val (k2, s2, n2) = leaves(child)
          (k ++ k2, s ++ s2, n ++ n2)
      }
    case ujson.Str(s) => (Vector.empty, Vector(s), Vector.empty)
    case ujson.Num(d) => (Vector.empty, Vector.empty, Vector(d))
    case _ => (Vector.empty, Vector.empty, Vector.empty)
  }

  private val allowedKeys: Set[String] = Set(
    // BlindRow
    "schemaVersion",
    "memberId",
    "entityKind",
    "labelingTarget",
    "labelGuideVersion",
    "question",
    "note",
    "maskingNotes",
    "sourceIdA",
    "sourceIdB",
    "endpointA",
    "endpointB",
    "labelOptions",
    // BlindEndpoint
    "endpointId",
    "endpointKind",
    "sourceDataset",
    "sourceRecordId",
    "snapshot",
    "nameAsWritten",
    "structuredName",
    "nameVariants",
    "authorPosition",
    "affiliations",
    "institutionIds",
    "identifiers",
    "claimedWorkIds",
    "work",
    // BlindWork
    "workId",
    "title",
    "year",
    "venue",
    "authorCount",
    "authors",
    "eeLinks",
    "urls"
  )

  /**
   * Every way a blind line could reveal the internal row: a hidden key, a key outside the allowed
   * set, or a string or number leaf equal to a hidden value of the internal row (outcome, path,
   * stratum, score band, rule IDs, reason codes, feature names, conflict schemes and kinds, model
   * and prompt versions, policy version, row and candidate IDs, score, confidence).
   */
  private def leaks(blindLine: String, r: ReviewQueueRow): Vector[String] = {
    val (keys, strings, numbers) = leaves(ujson.read(blindLine))
    val s = r.system
    val band = r.stratum.split('/').last
    val hiddenStrings: Set[String] =
      (Vector(s.outcome, s.path, r.stratum, band, r.rowId, r.candidateId, r.policyVersion) ++
        s.rulesFired ++ s.reasonCodes ++ r.features.keys ++ s.conflicts.map(_.kind.tag) ++
        s.modelVersion.toVector ++ s.promptVersion.toVector ++ MatchOutcome.values.map(_.tag) ++
        MatchPath.values.map(_.tag) ++ ScoreBand.values.map(_.tag)).toSet
    val hiddenNumbers = Vector(s.score, s.confidence).filter(d => d != math.rint(d))
    keys.filter(BlindView.HiddenFields.contains).map(k => s"hidden key $k") ++
      keys.filterNot(allowedKeys.contains).distinct.map(k => s"unexpected key $k") ++
      strings.filter(hiddenStrings.contains).map(v => s"hidden value '$v'") ++
      numbers.filter(hiddenNumbers.contains).map(v => s"hidden number $v")
  }

  /**
   * dblp person ID fragments encoded in a DS-P5-01 unit ID: `nv-208-1029-name` gives `208/1029` and
   * `208-1029`; `ov-16-493-1` gives `16/493-1` and `16-493-1`.
   */
  private def pidFragments(unitId: String): Vector[String] =
    if (!(unitId.startsWith("nv-") || unitId.startsWith("ov-"))) Vector.empty
    else {
      val parts = unitId.drop(3).split('-').toVector.takeWhile(_.forall(_.isDigit))
      if (parts.size < 2) Vector.empty
      else Vector(parts.head + "/" + parts.tail.mkString("-"), parts.mkString("-"))
    }

  /**
   * Selection facts that must never reach the view (RV-P5-02 finding 5): unit IDs, criteria,
   * splits, pid fragments of unit IDs, and given `expect_*` values. Returns every leak in a line.
   */
  private def selectionLeaks(
      blindLine: String,
      unitIds: Vector[String],
      criteria: Vector[String],
      expect: Vector[String]
  ): Vector[String] = {
    val (keys, strings, _) = leaves(ujson.read(blindLine))
    val selectionKeys = Vector("criterion", "unitId", "unitIds", "split", "selectionRule")
    val substrings =
      (unitIds ++ criteria ++ unitIds.flatMap(pidFragments) ++ expect)
        .filter(_.length >= 3)
        .distinct
    keys.filter(selectionKeys.contains).map(k => s"selection key $k") ++
      strings.filter(SelectionUnits.Splits.contains).map(v => s"split value '$v'") ++
      substrings.flatMap(x =>
        strings.filter(_.contains(x)).map(v => s"selection value '$x' in '$v'")
      )
  }

  private def internalOf(b: BlindRow): ReviewQueueRow = internal.find(_.memberId == b.memberId).get

  test("every internal row has one blind row with the same member ID and the three label options") {
    assertEquals(blind.size, internal.map(_.memberId).distinct.size)
    assertEquals(blind.map(_.memberId).toSet, internal.map(_.memberId).toSet)
    blind.foreach { b =>
      val r = internalOf(b)
      assertEquals(b.schemaVersion, "er-blind-label-view.v5")
      // The shown references are the label references: the endpoint ID itself, or a
      // masked-endpoint hash when it names a DBLP person or an OpenAlex A or W ID (view v5).
      assertEquals(
        (b.sourceIdA, b.sourceIdB),
        (BlindView.labelRef(b.memberId, r.sourceIdA), BlindView.labelRef(b.memberId, r.sourceIdB))
      )
      assertEquals(b.labelOptions, Vector("match", "non-match", "unsure"))
      assert(b.question.contains("unsure"))
      assertEquals(b.labelGuideVersion, "er-label-guide.v1")
      assertEquals(
        (b.entityKind, b.labelingTarget, b.question),
        if (b.entityKind == "work")
          ("work", "publication-stage", LabelGuide.WorkQuestion)
        else ("researcher", "person", LabelGuide.ResearcherQuestion)
      )
      assertEquals(b.note, "If the evidence shown is insufficient, choose unsure.")
    }
    assertEquals(allowedKeys, BlindView.VisibleFields.toSet)
    assert(internal.map(_.system.outcome).toSet.size >= 3, "the fixture spans several outcomes")
  }

  test("no hidden field, and no value derived only from one, appears in the blind view") {
    BlindView.toJsonl(blind).split('\n').toVector.zip(blind).foreach { case (line, b) =>
      assertEquals(leaks(line, internalOf(b)), Vector.empty, line)
    }
  }

  test("the leakage check fails when a hidden field or value is present") {
    val b = blind.head
    val r = internalOf(b)
    val line = write(b)
    assertNotEquals(leaks(line.replaceFirst("\\{", "{\"score\":0.5,"), r), Vector.empty)
    assertNotEquals(
      leaks(line.replaceFirst("\\{", s"""{"x":"${r.system.outcome}","""), r),
      Vector.empty
    )
    assertNotEquals(
      leaks(line.replaceFirst("\\{", s"""{"injected":"${r.stratum}","""), r),
      Vector.empty
    )
    assertNotEquals(leaks(line.replaceFirst("\\{", s"""{"x":"${r.rowId}","""), r), Vector.empty)
    val internalLine = write(r)
    assertNotEquals(leaks(internalLine, r), Vector.empty, "the internal row itself leaks")
  }

  test("the blind case classes declare no hidden field") {
    val sample = write(blind.head)
    val (keys, _, _) = leaves(ujson.read(sample))
    assertEquals(keys.filter(BlindView.HiddenFields.contains), Vector.empty)
    assert(keys.toSet.subsetOf(allowedKeys), keys.toSet.diff(allowedKeys).toString)
  }

  test("criterion, unit IDs and split are hidden fields") {
    Vector("criterion", "unitId", "unitIds", "split", "selectionRule", "rowId", "candidateId")
      .foreach(f => assert(BlindView.HiddenFields.contains(f), f))
  }

  test("a membership blind view hides criterion, unit IDs, split, and pid-bearing unit names") {
    assertEquals(memberBlind.size, members.size)
    assertEquals(memberBlind.map(_.memberId).toSet, members.map(_.memberId).toSet)
    assert(members.exists(_.unitIds.contains("nv-208-1029-synthwell")), "fixture carries the unit")
    val unitIds = synUnits.map(_._1)
    val criteria = synUnits.map(_._2)
    BlindView.toJsonl(memberBlind).split('\n').toVector.foreach { line =>
      assertEquals(selectionLeaks(line, unitIds, criteria, Vector.empty), Vector.empty, line)
    }
    // The manifest keeps them internally.
    assert(GoldMembership.toJsonl(members).contains("nv-208-1029-synthwell"))
    assert(GoldMembership.toJsonl(members).contains("\"criterion\":\"homonym-rare\""))
  }

  test("the selection leakage check fires on unit IDs, criteria, splits, pids, and expect values") {
    val line = write(memberBlind.head)
    val unitIds = synUnits.map(_._1)
    val criteria = synUnits.map(_._2)
    // A key the view does not carry (v3 has a real "note" key; a duplicate would be overwritten).
    def inject(v: String) = line.replaceFirst("\\{", s"""{"injected":"$v",""")
    Vector(
      inject("nv-208-1029-synthwell"),
      inject("name-variant"),
      inject("heldout"),
      inject("dblp-pid:208/1029"),
      inject("x 208-1029 y"),
      inject("dblp-pid:16/493-1"),
      inject("Jordan Q. Synthwell 0001"),
      line.replaceFirst("\\{", "{\"criterion\":\"ordinary\",")
    ).foreach { l =>
      assertNotEquals(
        selectionLeaks(l, unitIds, criteria, Vector("Jordan Q. Synthwell 0001")),
        Vector.empty,
        l
      )
    }
  }

  test(
    "no DS-P5-01 selection.tsv value reaches the blind view (unit IDs, criteria, expect_*, pids)"
  ) {
    val sel = SelectionUnits
      .parseTsv(
        Files.readString(
          repoRoot.resolve("datasets/er-candidates/selection.tsv"),
          StandardCharsets.UTF_8
        ),
        "selection",
        Vector("unit_id", "criterion", "expect_doi", "expect_author")
      )
      .fold(e => fail(e.mkString("; ")), identity)
    assert(sel.size > 100, s"selection.tsv rows: ${sel.size}")
    val unitIds = sel.map(_("unit_id")).distinct
    val criteria = sel.map(_("criterion")).distinct
    val expect =
      sel.flatMap(r => Vector(r("expect_doi"), r("expect_author"))).filter(_ != "-").distinct
    assert(unitIds.exists(pidFragments(_).nonEmpty), "selection has pid-bearing unit IDs")
    (BlindView.toJsonl(blind) ++ BlindView.toJsonl(memberBlind)).split('\n').toVector.foreach {
      line => assertEquals(selectionLeaks(line, unitIds, criteria, expect), Vector.empty, line)
    }
  }

  test("source evidence: names, titles, venues, years, affiliations, coauthors, identifiers") {
    val popper = blind
      .flatMap(b => Vector(b.endpointA, b.endpointB))
      .find(e =>
        e.endpointKind == "mention" && e.nameAsWritten.contains("Christina Pöpper") &&
          e.sourceDataset == "dblp"
      )
      .get
    assertEquals(popper.authorPosition, Some(1))
    assert(popper.identifiers.contains("orcid:0000-0002-2814-962X"))
    val w = popper.work.get
    assert(w.title.startsWith("Cyber security research in the Arab region"))
    assertEquals((w.year, w.venue), (Some(2021), Some("Commun. ACM")))
    assert(w.authors.contains("Michail Maniatakos"))
    assert(w.identifiers.contains("doi:10.1145/3447741"))
    val profile =
      blind.flatMap(b => Vector(b.endpointA, b.endpointB)).find(_.endpointKind == "profile").get
    assert(profile.nameVariants.nonEmpty || profile.structuredName.isDefined)
    assert(profile.identifiers.exists(_.startsWith("orcid:")))
    val crossref = blind
      .flatMap(b => Vector(b.endpointA, b.endpointB))
      .find(e => e.endpointKind == "mention" && e.sourceDataset == "crossref")
      .get
    assert(crossref.affiliations.nonEmpty)
    blind.foreach(b =>
      Vector(b.endpointA, b.endpointB).foreach(e =>
        assert(e.sourceRecordId.nonEmpty && e.snapshot.nonEmpty)
      )
    )
  }

  test("order: a deterministic shuffle by member ID hash, independent of input order and stratum") {
    val expected =
      internal
        .map(_.memberId)
        .distinct
        .sortBy(m => Canonical.hashFields("er-blind-view-order.v2", "t", m))
    assertEquals(blind.map(_.memberId), expected)
    val again = BlindView.build(internal.reverse.map(BlindItem.of), evidence, "t").toOption.get
    assertEquals(again, blind)
    val other = BlindView.build(internal.map(BlindItem.of), evidence, "another-seed").toOption.get
    assertEquals(other.map(_.memberId).toSet, blind.map(_.memberId).toSet)
    // The internal queue is grouped by stratum; the view is not ordered by it.
    val strataInViewOrder = blind.map(b => internalOf(b).stratum)
    assertNotEquals(strataInViewOrder, strataInViewOrder.sorted)
  }

  test("the membership view is the same under another policy version (built from members only)") {
    val q1 =
      RunQueue.resolveMembers(policy, members, CuratedRun(repoRoot, works, profiles, candidates))
    val alt = policy.copy(policyVersion = "er-policy.synthetic-alt")
    val q2 =
      RunQueue.resolveMembers(alt, members, CuratedRun(repoRoot, works, profiles, candidates))
    assertNotEquals(q1.rows.map(_.rowId), q2.rows.map(_.rowId))
    val v1 = BlindView.build(q1.rows.map(BlindItem.of), evidence, "t").toOption.get
    val v2 = BlindView.build(q2.rows.map(BlindItem.of), evidence, "t").toOption.get
    assertEquals(v1, v2)
    assertEquals(v1.map(_.memberId).toSet, memberBlind.map(_.memberId).toSet)
  }

  test(
    "member IDs do not encode the decision: the same pair under another outcome keeps its ID"
  ) {
    val o = outcomes.head
    val flipped = o.copy(outcome = MatchOutcome.Rejected, path = MatchPath.Probabilistic)
    val a = ReviewQueue.row(policy, o, refs).toOption.get
    val b = ReviewQueue.row(policy, flipped, refs).toOption.get
    assertNotEquals(a.system.outcome, b.system.outcome)
    assertEquals(a.rowId, b.rowId)
    assertEquals(a.memberId, b.memberId)
  }

  test(
    "view v5: the OpenAlex author ID of a sample profile is masked; DOI, ORCID, institution stay"
  ) {
    val popper = Samples.popperOpenAlex
    val rows = blind.filter(b =>
      internalOf(b).sourceIdA == popper.profileId || internalOf(b).sourceIdB == popper.profileId
    )
    assert(rows.nonEmpty, "the fixture has a pair with the OpenAlex profile")
    rows.foreach { b =>
      val json = write(b)
      assert(!json.contains("A5113870699"), json)
      assert(!json.toLowerCase.contains("a5113870699"), json)
      assert(b.maskingNotes.contains(OpenAlexMasking.Note), b.maskingNotes.toString)
      val shownProfile = Vector(b.endpointA, b.endpointB).find(_.endpointKind == "profile").get
      assert(shownProfile.endpointId.startsWith(BlindView.MaskedRefPrefix))
      assertEquals(shownProfile.sourceRecordId, OpenAlexMasking.Masked)
      assert(shownProfile.identifiers.contains("orcid:0000-0002-2814-962X"))
      assert(shownProfile.institutionIds.contains("institution:openalex:I120250893"))
    }
    // Pairs without OpenAlex IDs carry no OpenAlex note, and their DOIs are still shown.
    val spanner = blind
      .find(b =>
        internalOf(b).entityKind == "work" &&
          Set(internalOf(b).sourceIdA, internalOf(b).sourceIdB) ==
          Set(Samples.spannerDblp.workId, Samples.spannerCrossref.workId)
      )
      .get
    assert(!spanner.maskingNotes.contains(OpenAlexMasking.Note))
    assert(spanner.endpointA.work.get.identifiers.contains("doi:10.1145/2491245"))
  }

  test("a row whose endpoints have no source evidence is refused, never shown partially") {
    assert(BlindView.build(internal.map(BlindItem.of), Map.empty, "t").isLeft)
  }
}
