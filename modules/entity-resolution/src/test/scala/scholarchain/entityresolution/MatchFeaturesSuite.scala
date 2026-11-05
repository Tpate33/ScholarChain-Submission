package scholarchain.entityresolution

import scala.collection.immutable.SortedMap

import scholarchain.core.*

import Samples.*

class MatchFeaturesSuite extends munit.FunSuite {
  import MatchFeatures.{researcher, work}

  private def zeros(names: Vector[String]): SortedMap[String, Double] =
    SortedMap.from(names.map(_ -> 0.0))

  private def rz(kv: (String, Double)*): SortedMap[String, Double] =
    zeros(MatchFeatures.ResearcherFeatureNames) ++ kv

  private def wz(kv: (String, Double)*): SortedMap[String, Double] =
    zeros(MatchFeatures.WorkFeatureNames) ++ kv

  /** SYNTHETIC: one ORCID work summary with its holder as the only author. */
  private val orcidWorkId = "work:orcid-work:0000-0002-2814-962X/work/12345"
  private val orcidWork = buildWork(
    SourceType.Orcid,
    orcidWorkId,
    "0000-0002-2814-962X/work/12345",
    "Cyber security research in the Arab region",
    Some(2021),
    None,
    None,
    None,
    Vector(Samples.A("Christina Poepper"))
  )

  /** SYNTHETIC: two different people named Ada Example with different (test) ORCID iDs. */
  private val adaA = buildWork(
    SourceType.Dblp,
    "work:dblp:journals/example/AdaA",
    "journals/example/AdaA",
    "First Example",
    Some(2020),
    None,
    None,
    None,
    Vector(Samples.A("Ada Example", Some("0000-0000-0000-0001")))
  )
  private val adaB = buildWork(
    SourceType.Dblp,
    "work:dblp:journals/example/AdaB",
    "journals/example/AdaB",
    "Second Example",
    Some(2022),
    None,
    None,
    None,
    Vector(Samples.A("Ada Example", Some("0000-0000-0000-001X")))
  )

  /** SYNTHETIC: a DBLP homonym `Christopher Frost 0002`. */
  private val frost2 = buildWork(
    SourceType.Dblp,
    "work:dblp:journals/example/Frost2",
    "journals/example/Frost2",
    "Another Example",
    Some(2013),
    None,
    None,
    None,
    Vector(Samples.A("Christopher Frost 0002"))
  )

  /** SYNTHETIC: an unreadable title and author, no year, no venue, and a second empty work. */
  private val edge = buildWork(
    SourceType.Dblp,
    "work:dblp:journals/example/Edge",
    "journals/example/Edge",
    "!!!",
    None,
    None,
    None,
    None,
    Vector(Samples.A("..."))
  )
  private val emptyWork =
    edge.copy(workId = "work:dblp:journals/example/Empty", authors = Vector.empty)
  private val namelessProfile = popperOrcid.copy(givenNames = None, familyName = None)

  test("pinned version") {
    assertEquals(MatchFeatures.Version, "er-features.v2")
    assertEquals(EntityResolution.featuresVersion, "er-features.v2")
    assert(StableId.isToken(MatchFeatures.Version))
  }

  test("feature name lists are sorted tokens and are exactly the emitted keys") {
    Vector(MatchFeatures.ResearcherFeatureNames, MatchFeatures.WorkFeatureNames).foreach { ns =>
      assertEquals(ns, ns.sorted)
      assert(ns.forall(StableId.isToken))
      assertEquals(ns.distinct, ns)
    }
    assertEquals(
      researcher(em(spannerDblp, 4), ep(popperOrcid)).keys.toVector,
      MatchFeatures.ResearcherFeatureNames
    )
    assertEquals(
      researcher(ep(popperOpenAlex), ep(namelessProfile)).keys.toVector,
      MatchFeatures.ResearcherFeatureNames
    )
    assertEquals(work(spannerDblp, emptyWork).keys.toVector, MatchFeatures.WorkFeatureNames)
  }

  test("hand-computed: Christopher Frost 0001 (DBLP) vs Christopher Frost (Crossref), same paper") {
    assertEquals(
      researcher(em(spannerDblp, 4), em(spannerCrossref, 4)),
      rz(
        "name.family_exact" -> 1.0,
        "name.family_key_agree" -> 1.0,
        "name.family_sim" -> 1.0,
        "name.given_both" -> 1.0,
        "name.first_initial_agree" -> 1.0,
        "name.initials_compatible" -> 1.0,
        "name.first_given_agree" -> 1.0,
        "name.given_sim" -> 1.0,
        "name.token_jaccard" -> 1.0,
        "name.best_variant_sim" -> 1.0,
        "context.work_both" -> 1.0,
        "coauthor.both" -> 1.0,
        "coauthor.shared" -> 25.0,
        "coauthor.jaccard" -> 1.0,
        "year.both" -> 1.0,
        "venue.name_both" -> 1.0,
        "venue.name_jaccard" -> 1.0 / 7.0, // {acm,trans,comput,syst} vs {acm,transactions,computer,systems}
        "work.title_jaccard" -> 1.0,
        "work.position_agree" -> 1.0,
        "work.team_size_agree" -> 1.0,
        "work.doi_agree" -> 1.0
      )
    )
    assertEquals(
      EntityResolution
        .matchFeatures(m(spannerDblp, 4), spannerDblp, m(spannerCrossref, 4), spannerCrossref),
      researcher(em(spannerDblp, 4), em(spannerCrossref, 4))
    )
  }

  test("hand-computed: Jeffrey Dean on Bigtable (2006) vs on Spanner (2013)") {
    assertEquals(
      researcher(em(bigtableDblp, 1), em(spannerDblp, 1)),
      rz(
        "name.family_exact" -> 1.0,
        "name.family_key_agree" -> 1.0,
        "name.family_sim" -> 1.0,
        "name.given_both" -> 1.0,
        "name.first_initial_agree" -> 1.0,
        "name.initials_compatible" -> 1.0,
        "name.first_given_agree" -> 1.0,
        "name.given_sim" -> 1.0,
        "name.token_jaccard" -> 1.0,
        "name.best_variant_sim" -> 1.0,
        "context.same_source" -> 1.0,
        "context.work_both" -> 1.0,
        "coauthor.both" -> 1.0,
        "coauthor.shared" -> 3.0, // ghemawat|s, hsieh|w, fikes|a
        "coauthor.jaccard" -> 3.0 / 30.0, // 8 + 25 - 3
        "year.both" -> 1.0,
        "year.abs_diff" -> 7.0,
        "venue.name_both" -> 1.0,
        "work.title_jaccard" -> 1.0 / 10.0, // only `distributed` shared; 5 + 6 - 1
        "work.position_agree" -> 1.0
      )
    )
  }

  test("hand-computed: Pöpper (DBLP mention, ORCID asserted) vs ORCID holder Christina Poepper") {
    val f = researcher(em(cacmDblp, 0), ep(popperOrcid))
    assertEquals(
      f,
      rz(
        "name.family_key_agree" -> 1.0,
        "name.family_sim" -> 1.0,
        "name.given_both" -> 1.0,
        "name.first_initial_agree" -> 1.0,
        "name.initials_compatible" -> 1.0,
        "name.first_given_agree" -> 1.0,
        "name.given_sim" -> 1.0,
        "name.token_jaccard" -> 1.0,
        "name.best_variant_sim" -> 1.0,
        "id.orcid.both" -> 1.0,
        "id.orcid.agree" -> 1.0
      ),
      "family_exact is 0: popper vs poepper differ before digraph collapse"
    )
    assertEquals(
      EntityResolution.researcherExactIdAgreements(em(cacmDblp, 0), ep(popperOrcid)),
      Vector(ExactId(ExactIdScheme.Orcid, "0000-0002-2814-962X"))
    )
  }

  test("profile vs profile: identifiers, variants and affiliations") {
    val f = researcher(ep(popperOpenAlex), ep(popperOrcid))
    assertEquals(f("id.orcid.agree"), 1.0)
    assertEquals(f("id.openalex-author.both"), 0.0, "only the OpenAlex profile has an A-ID")
    assertEquals(f("name.best_variant_sim"), 1.0)
    assertEquals(f("context.same_source"), 0.0)
    assertEquals(f("context.work_both"), 0.0)
    assertEquals(f("affiliation.id_both"), 0.0)
    assertEquals(
      MatchFeatures.researcherExactIds(ep(popperOpenAlex)),
      Vector(
        ExactId(ExactIdScheme.OpenAlexAuthor, "A5113870699"),
        ExactId(ExactIdScheme.Orcid, "0000-0002-2814-962X")
      )
    )
    val sameInst = popperOrcid.copy(institutionIds = popperOpenAlex.institutionIds)
    val g = researcher(ep(popperOpenAlex), ep(sameInst))
    assertEquals(g("affiliation.id_both"), 1.0)
    assertEquals(g("affiliation.id_shared"), 1.0)
    assertEquals(g("affiliation.id_jaccard"), 1.0)
  }

  test("disagreeing ORCID iDs are reported as a conflict, never as an agreement") {
    val f = researcher(em(adaA, 0), em(adaB, 0))
    assertEquals(f("id.orcid.both"), 1.0)
    assertEquals(f("id.orcid.agree"), 0.0)
    assertEquals(f("id.orcid.conflict"), 1.0)
    assertEquals(f("name.family_exact"), 1.0, "same name, different people: features only measure")
    assertEquals(
      EntityResolution.researcherExactIdAgreements(em(adaA, 0), em(adaB, 0)),
      Vector.empty
    )
  }

  test("DBLP homonym numbers: different numbers are both-present but not agreeing") {
    val f = researcher(em(spannerDblp, 4), em(frost2, 0))
    assertEquals(f("name.homonym_suffix_both"), 1.0)
    assertEquals(f("name.homonym_suffix_agree"), 0.0)
    val g = researcher(em(spannerDblp, 4), em(spannerDblp.copy(workId = "work:dblp:x/y/z"), 4))
    assertEquals(g("name.homonym_suffix_agree"), 1.0)
  }

  test("two different authors on the same work") {
    val f = researcher(em(spannerDblp, 4), em(spannerDblp, 8)) // Frost vs Heiser
    assertEquals(f("context.same_work"), 1.0)
    assertEquals(f("context.same_source"), 1.0)
    assertEquals(f("name.first_given_agree"), 1.0)
    assertEquals(f("name.family_key_agree"), 0.0)
    assertEquals(f("name.family_sim"), 1.0 - Similarity.levenshtein("frost", "heiser") / 6.0)
    assertEquals(f("work.position_agree"), 0.0)
    assertEquals(f("work.team_size_agree"), 1.0)
  }

  test("raw affiliation strings are compared as folded token sets") {
    val f = researcher(em(cacmCrossref, 0), em(cacmCrossref, 1))
    assertEquals(f("affiliation.text_both"), 1.0)
    assertEquals(f("affiliation.text_jaccard"), 1.0) // {nyu, abu, dhabi, uae}
    val g = researcher(em(cacmCrossref, 0), em(cacmCrossref, 2))
    assertEquals(g("affiliation.text_jaccard"), 0.0) // vs {hbku, cse, doha, qatar}
  }

  test("an ORCID profile that claims the mention's work") {
    val claiming = popperOrcid.copy(claimedWorkIds = Vector(orcidWorkId))
    assertEquals(researcher(ep(claiming), em(orcidWork, 0))("context.claimed_work"), 1.0)
    assertEquals(researcher(em(orcidWork, 0), ep(claiming))("context.claimed_work"), 1.0)
    assertEquals(researcher(ep(popperOrcid), em(orcidWork, 0))("context.claimed_work"), 0.0)
  }

  test("features read raw names, never the source-normalized normalizedName") {
    val garbled =
      spannerDblp.copy(authors = spannerDblp.authors.map(_.copy(normalizedName = "zzz")))
    assertEquals(
      researcher(em(garbled, 4), em(spannerCrossref, 4)),
      researcher(em(spannerDblp, 4), em(spannerCrossref, 4))
    )
  }

  test("hand-computed work features: Spanner in DBLP vs Crossref") {
    assertEquals(
      work(spannerDblp, spannerCrossref),
      wz(
        "id.doi.both" -> 1.0,
        "id.doi.agree" -> 1.0,
        "title.exact" -> 1.0,
        "title.sim" -> 1.0,
        "title.token_jaccard" -> 1.0,
        "year.both" -> 1.0,
        "venue.name_both" -> 1.0,
        "venue.name_jaccard" -> 1.0 / 7.0,
        "authors.both" -> 1.0,
        "authors.family_shared" -> 26.0,
        "authors.family_jaccard" -> 1.0,
        "authors.first_family_agree" -> 1.0,
        "authors.sequence_exact" -> 1.0,
        "authors.sequence_lcs_ratio" -> 1.0
      )
    )
    assertEquals(
      EntityResolution.workExactIdAgreements(spannerDblp, spannerCrossref),
      Vector(ExactId(ExactIdScheme.Doi, "10.1145/2491245"))
    )
    assertEquals(
      EntityResolution.workMatchFeatures(spannerDblp, spannerCrossref),
      work(spannerDblp, spannerCrossref)
    )
  }

  test("hand-computed work features: Spanner vs Bigtable (both DBLP)") {
    val f = work(spannerDblp, bigtableDblp)
    assertEquals(f("authors.count_diff"), 17.0)
    assertEquals(f("authors.family_shared"), 4.0) // dean, fikes, ghemawat, hsieh
    assertEquals(f("authors.family_jaccard"), 4.0 / 31.0)
    assertEquals(f("authors.sequence_lcs_ratio"), 3.0 / 26.0) // dean, ghemawat, hsieh
    assertEquals(f("authors.first_family_agree"), 0.0)
    assertEquals(f("authors.sequence_exact"), 0.0)
    assertEquals(f("title.token_jaccard"), 1.0 / 10.0)
    assertEquals(f("title.exact"), 0.0)
    assertEquals(f("year.abs_diff"), 7.0)
    assertEquals(f("id.doi.both"), 0.0, "Bigtable has no DOI")
    assertEquals(f("id.other.conflict"), 1.0, "two different DBLP keys")
    assertEquals(f("id.other.agree"), 0.0)
    assertEquals(f("context.same_source"), 1.0)
    assertEquals(f("venue.name_jaccard"), 0.0)
  }

  test("different DOIs are a DOI conflict; shared ORCIDs are counted") {
    val f = work(spannerDblp, cacmDblp)
    assertEquals((f("id.doi.both"), f("id.doi.agree"), f("id.doi.conflict")), (1.0, 0.0, 1.0))
    assertEquals(
      work(cacmDblp, cacmDblp.copy(workId = "work:dblp:x/y/z"))("authors.orcid_shared"),
      3.0
    )
  }

  private val researcherPairs: Vector[(ResearcherEndpoint, ResearcherEndpoint)] = Vector(
    em(spannerDblp, 4) -> em(spannerCrossref, 4),
    em(bigtableDblp, 1) -> em(spannerDblp, 1),
    em(cacmDblp, 0) -> ep(popperOrcid),
    ep(popperOpenAlex) -> ep(popperOrcid),
    em(adaA, 0) -> em(adaB, 0),
    em(cacmCrossref, 0) -> em(cacmCrossref, 2),
    ep(popperOrcid.copy(claimedWorkIds = Vector(orcidWorkId))) -> em(orcidWork, 0),
    em(edge, 0) -> em(synthetic, 0),
    em(edge, 0) -> ep(namelessProfile),
    ep(namelessProfile) -> ep(popperOpenAlex),
    em(varajaoDblp, 0) -> em(synthetic, 0)
  )

  private val workPairs: Vector[(WorkRecord, WorkRecord)] = Vector(
    spannerDblp -> spannerCrossref,
    spannerDblp -> bigtableDblp,
    cacmDblp -> cacmCrossref,
    edge -> emptyWork,
    edge -> synthetic,
    emptyWork -> varajaoDblp,
    orcidWork -> cacmCrossref
  )

  test("symmetric: swapping the two sides never changes a feature") {
    researcherPairs.foreach { case (a, b) =>
      assertEquals(researcher(a, b), researcher(b, a), s"${a.id} ${b.id}")
    }
    workPairs.foreach { case (a, b) =>
      assertEquals(work(a, b), work(b, a), s"${a.workId} ${b.workId}")
    }
  }

  test("finite for edge cases: empty titles, unreadable names, no coauthors, no year, no venue") {
    val all = researcherPairs.map((a, b) => researcher(a, b)) ++ workPairs.map((a, b) => work(a, b))
    all.foreach(f => f.foreach { case (k, v) => assert(!v.isNaN && !v.isInfinite, s"$k=$v") })
    assertEquals(researcher(em(edge, 0), ep(namelessProfile)), rz(), "nothing comparable: all zero")
    assertEquals(
      work(edge, emptyWork),
      wz("id.other.conflict" -> 1.0, "context.same_source" -> 1.0, "authors.count_diff" -> 1.0)
    )
  }

  test("ranges: flags are 0 or 1, similarities in [0, 1], counts non-negative") {
    val counts = Set(
      "coauthor.shared",
      "affiliation.id_shared",
      "year.abs_diff",
      "authors.count_diff",
      "authors.family_shared",
      "authors.orcid_shared",
      "id.other.agree",
      "id.other.conflict"
    )
    val all = researcherPairs.map((a, b) => researcher(a, b)) ++ workPairs.map((a, b) => work(a, b))
    all.foreach(_.foreach { case (k, v) =>
      if (counts(k)) assert(v >= 0.0 && v == math.floor(v), s"$k=$v")
      else assert(v >= 0.0 && v <= 1.0, s"$k=$v")
    })
  }

  test("format characters inside names and titles do not change any feature (er-features.v2)") {
    def cp(c: Int): String = new String(Character.toChars(c))
    val zwsp = cp(0x200b)
    val shy = cp(0x00ad)
    val dirty = spannerCrossref.copy(
      title = "Span" + zwsp + "ner: Google's Glob" + shy + "ally Distributed Database",
      authors = spannerCrossref.authors.map(a =>
        a.copy(rawName = cp(0xfeff) + a.rawName.patch(2, cp(0x200d), 0) + cp(0x200f))
      )
    )
    assertEquals(
      researcher(em(spannerDblp, 4), em(dirty, 4)),
      researcher(em(spannerDblp, 4), em(spannerCrossref, 4))
    )
    assertEquals(work(spannerDblp, dirty), work(spannerDblp, spannerCrossref))
    assertEquals(work(spannerDblp, dirty)("title.exact"), 1.0)
  }

  test("prepared and unprepared calls agree") {
    researcherPairs.foreach { case (a, b) =>
      assertEquals(researcher(MatchFeatures.prepare(a), MatchFeatures.prepare(b)), researcher(a, b))
    }
  }

  test("features and identifier agreements are accepted by the core MatchCandidate contract") {
    val prov = Provenance(
      SourceType.Derived,
      spannerDblp.workId,
      "dblp-2026-10-03",
      runId,
      SchemaVersions.WorkRecord,
      Some("hadoop-transform.v1"),
      None,
      None
    )
    val (a, b) = (em(cacmDblp, 0), ep(popperOrcid))
    val rc = MatchCandidate.build(
      MatchEntityKind.Researcher,
      a.id,
      a.source,
      b.id,
      b.source,
      BlockingKeys
        .researcherKeys(cacmDblp, m(cacmDblp, 0))
        .take(2)
        .zipWithIndex
        .map((k, l) => BlockKey(l, k)),
      BlockingKeys.Version,
      MatchFeatures.Version,
      researcher(a, b),
      EntityResolution.researcherExactIdAgreements(a, b),
      prov
    )
    assert(rc.isRight, rc.toString)
    val wc = MatchCandidate.build(
      MatchEntityKind.Work,
      spannerDblp.workId,
      SourceType.Dblp,
      spannerCrossref.workId,
      SourceType.Crossref,
      BlockingKeys.workKeys(spannerDblp).zipWithIndex.map((k, l) => BlockKey(l, k)),
      BlockingKeys.Version,
      MatchFeatures.Version,
      work(spannerDblp, spannerCrossref),
      EntityResolution.workExactIdAgreements(spannerDblp, spannerCrossref),
      prov
    )
    assert(wc.isRight, wc.toString)
  }
}
