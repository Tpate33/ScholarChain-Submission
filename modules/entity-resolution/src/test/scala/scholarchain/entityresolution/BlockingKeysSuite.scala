package scholarchain.entityresolution

import java.util.Locale

import scholarchain.core.{
  BlockKey,
  CandidateBlockId,
  MatchEntityKind,
  SchemaVersions,
  SourceAuthorProfile,
  SourceType,
  StableId,
  WorkRecord
}

import Samples.*

class BlockingKeysSuite extends munit.FunSuite {
  import BlockingKeys.{profileKeys, researcherKeys, workKeys}

  private def rkeys(w: WorkRecord, i: Int) = researcherKeys(w, w.authors(i))

  test("pinned versions and level count") {
    assertEquals(BlockingKeys.Version, "er-blocking.v1")
    assertEquals(EntityResolution.blockingKeysVersion, "er-blocking.v1")
    assertEquals(BlockingKeys.Levels, 4)
    assertEquals(EntityResolution.blockingLevels, 4)
    assert(StableId.isToken(BlockingKeys.Version))
  }

  test("golden: level-0 keys of all 26 Spanner authors (DBLP sample)") {
    assertEquals(
      spannerDblp.authors.indices.map(rkeys(spannerDblp, _).head).toVector,
      Vector(
        "corbett|j",
        "dean|j",
        "epstein|m",
        "fikes|a",
        "frost|c",
        "furman|j",
        "ghemawat|s",
        "gubarev|a",
        "heiser|c",
        "hochschild|p",
        "hsieh|w",
        "kanthak|s",
        "kogan|e",
        "li|h",
        "lloyd|a",
        "melnik|s",
        "mwaura|d",
        "nagle|d",
        "quinlan|s",
        "rao|r",
        "rolig|l",
        "saito|y",
        "szymaniak|m",
        "taylor|c",
        "wang|r",
        "woodford|d"
      )
    )
  }

  test("golden: every level for selected sample mentions") {
    assertEquals(
      rkeys(spannerDblp, 4),
      Vector(
        "frost|c",
        "frost|c|christopher",
        "frost|c|christopher|corbett",
        "frost|c|christopher|corbett|2013"
      ),
      "Christopher Frost 0001: homonym number removed, anchor = smallest coauthor family"
    )
    assertEquals(
      rkeys(spannerDblp, 0),
      Vector("corbett|j", "corbett|j|james", "corbett|j|james|dean", "corbett|j|james|dean|2013")
    )
    assertEquals(
      rkeys(spannerDblp, 2),
      Vector(
        "epstein|m",
        "epstein|m|michal",
        "epstein|m|michal|corbett",
        "epstein|m|michal|corbett|2013"
      ),
      "vowel-digraph collapse also applies to the given-name component"
    )
    assertEquals(
      rkeys(cacmDblp, 0),
      Vector(
        "popper|c",
        "popper|c|christina",
        "popper|c|christina|dipietro",
        "popper|c|christina|dipietro|2021"
      ),
      "Pöpper (diacritic folded), anchor = Di Pietro (particle joined)"
    )
    assertEquals(
      rkeys(cacmDblp, 2),
      Vector(
        "dipietro|r",
        "dipietro|r|roberto",
        "dipietro|r|roberto|maniatakos",
        "dipietro|r|roberto|maniatakos|2021"
      )
    )
    assertEquals(
      rkeys(varajaoDblp, 0),
      Vector("varajao|j", "varajao|j|joao", "varajao|j|joao|-", "varajao|j|joao|-|2021"),
      "single-author work: no coauthor anchor"
    )
    assertEquals(
      rkeys(bigtableDblp, 1),
      Vector("dean|j", "dean|j|jeffrey", "dean|j|jeffrey|burrows", "dean|j|jeffrey|burrows|2006")
    )
  }

  test("golden: single-name author and missing year (synthetic)") {
    assertEquals(
      rkeys(synthetic, 0),
      Vector("madonna|-", "madonna|-|-", "madonna|-|-|example", "madonna|-|-|example|-")
    )
    assertEquals(
      rkeys(synthetic, 1),
      Vector("example|a", "example|a|ada", "example|a|ada|madonna", "example|a|ada|madonna|-")
    )
  }

  test(
    "the same paper in DBLP and Crossref yields identical keys for every author at every level"
  ) {
    spannerDblp.authors.indices.foreach { i =>
      assertEquals(rkeys(spannerCrossref, i), rkeys(spannerDblp, i), spannerNames(i))
    }
    cacmDblp.authors.indices.foreach { i =>
      assertEquals(rkeys(cacmCrossref, i), rkeys(cacmDblp, i))
    }
  }

  test("Pöpper, Poepper and Popper share levels 0 and 1 across mention and profiles") {
    assertEquals(
      profileKeys(popperOpenAlex),
      Vector(
        "popper|c",
        "popper|c|christina",
        "popper|c|christina|*",
        "popper|c|christina|*|institution:openalex:I120250893"
      )
    )
    assertEquals(
      profileKeys(popperOrcid),
      Vector("popper|c", "popper|c|christina", "popper|c|christina|*", "popper|c|christina|*|-"),
      "ORCID structured name Christina / Poepper"
    )
    val mentionKeys = rkeys(cacmDblp, 0)
    assertEquals(profileKeys(popperOrcid).take(2), mentionKeys.take(2))
    assertEquals(profileKeys(popperOpenAlex).take(2), mentionKeys.take(2))
    assertEquals(EntityResolution.profileBlockingKeys(popperOrcid), profileKeys(popperOrcid))
  }

  test("a profile without a readable name has no keys; display name is the fallback") {
    val nameless = popperOrcid.copy(givenNames = None, familyName = None)
    assertEquals(profileKeys(nameless), Vector.empty)
    val display =
      popperOrcid.copy(givenNames = None, familyName = None, displayName = Some("C. Popper"))
    assertEquals(profileKeys(display).take(2), Vector("popper|c", "popper|c|c"))
  }

  test("DBLP homonyms share blocks (separated by features, not by blocking)") {
    val other =
      synthetic.copy(authors = Vector(m(synthetic, 0).copy(rawName = "Christopher Frost 0002")))
    assertEquals(researcherKeys(other, other.authors(0)).take(2), rkeys(spannerDblp, 4).take(2))
  }

  test("golden: work keys of the samples; DBLP and Crossref copies agree at every level") {
    assertEquals(
      workKeys(spannerDblp),
      Vector(
        "spanner_googles",
        "spanner_googles|globally_distributed",
        "spanner_googles|globally_distributed|2013",
        "spanner_googles|globally_distributed|2013|corbett"
      )
    )
    assertEquals(workKeys(spannerCrossref), workKeys(spannerDblp))
    assertEquals(
      workKeys(cacmDblp),
      Vector(
        "cyber_security",
        "cyber_security|research_arab",
        "cyber_security|research_arab|2021",
        "cyber_security|research_arab|2021|popper"
      )
    )
    assertEquals(workKeys(cacmCrossref), workKeys(cacmDblp))
    assertEquals(
      workKeys(varajaoDblp),
      Vector(
        "software_development",
        "software_development|disruptive_times",
        "software_development|disruptive_times|2021",
        "software_development|disruptive_times|2021|varajao"
      )
    )
    assertEquals(
      workKeys(bigtableDblp),
      Vector(
        "bigtable_distributed",
        "bigtable_distributed|storage_system",
        "bigtable_distributed|storage_system|2006",
        "bigtable_distributed|storage_system|2006|chang"
      ),
      "editorial note (Awarded Best Paper!) removed"
    )
    assertEquals(
      workKeys(synthetic),
      Vector(
        "collaboration_examples",
        "collaboration_examples|-",
        "collaboration_examples|-|-",
        "collaboration_examples|-|-|madonna"
      ),
      "missing year and short title"
    )
    assertEquals(EntityResolution.workBlockingKeys(spannerDblp), workKeys(spannerDblp))
  }

  test("work keys: all-stopword titles use every word; unreadable titles and no authors") {
    assertEquals(workKeys(synthetic.copy(title = "On The")).take(2), Vector("on_the", "on_the|-"))
    assertEquals(workKeys(synthetic.copy(title = "!!!")), Vector.empty)
    assertEquals(
      workKeys(synthetic.copy(authors = Vector.empty)).last,
      "collaboration_examples|-|-|-"
    )
  }

  test("keys read raw names and titles, never the source-normalized fields") {
    val garbled = spannerDblp.copy(
      normalizedTitle = "x",
      authors = spannerDblp.authors.map(_.copy(normalizedName = "zzz"))
    )
    assertEquals(workKeys(garbled), workKeys(spannerDblp))
    assertEquals(researcherKeys(garbled, garbled.authors(4)), rkeys(spannerDblp, 4))
  }

  test("an unreadable author name yields no keys (Hadoop counts MISSING_KEY)") {
    val w = synthetic.copy(authors = Vector(m(synthetic, 0).copy(rawName = "...")))
    assertEquals(researcherKeys(w, w.authors(0)), Vector.empty)
  }

  private val adversarialNames = Vector(
    "Tab\tName",
    "Nul\u0000Name",
    "New\nLine Name",
    "Zero‍Width Joiner",
    "王小明",
    "محمد علي",
    "Emoji 😀 Person",
    "A|Pipe B",
    "  Spaced   Out  ",
    "İlker Işık"
  )

  private def allSampleKeys: Vector[Vector[String]] = {
    val works = Vector(
      spannerDblp,
      spannerCrossref,
      cacmDblp,
      cacmCrossref,
      varajaoDblp,
      bigtableDblp,
      synthetic
    )
    val adversarial = synthetic.copy(
      title = "Tab\tTitle | with \u0000 control",
      authors = adversarialNames.zipWithIndex.map { case (n, i) =>
        m(synthetic, 0).copy(
          rawName = n,
          position = i,
          mentionId = ok(scholarchain.core.MentionId.of(synthetic.workId, i))
        )
      }
    )
    (works :+ adversarial).flatMap(w => w.authors.map(researcherKeys(w, _)) :+ workKeys(w)) ++
      Vector(profileKeys(popperOpenAlex), profileKeys(popperOrcid))
  }

  test("every key is a valid block key: non-blank, no tab or control character, a valid block ID") {
    val keys = allSampleKeys
    assert(keys.forall(_.nonEmpty))
    keys.flatten.foreach { k =>
      assertEquals(BlockKey.checkKey("k", k), Vector.empty, k)
      assert(!k.contains('\t'), k)
      assert(
        CandidateBlockId.of(MatchEntityKind.Researcher, BlockingKeys.Version, 0, k, runId).isRight
      )
    }
  }

  test("bounded and nested: at most Levels keys, and each level extends the previous one") {
    allSampleKeys.foreach { ks =>
      assert(ks.size <= BlockingKeys.Levels, ks)
      ks.sliding(2).foreach {
        case Vector(a, b) => assert(b.startsWith(a + "|"), s"$a -> $b")
        case _ => ()
      }
    }
  }

  test("a pipe inside a name never leaks into a component") {
    val w = synthetic.copy(authors = Vector(m(synthetic, 0).copy(rawName = "A|Pipe B")))
    assertEquals(researcherKeys(w, w.authors(0)).head, "b|a")
  }

  test("deterministic: repeated calls and a Turkish default locale give the same keys") {
    val first = allSampleKeys
    val before = Locale.getDefault
    val turkish =
      try {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
        allSampleKeys
      } finally Locale.setDefault(before)
    assertEquals(allSampleKeys, first)
    assertEquals(turkish, first)
  }

  test("sample fixtures are valid core records") {
    Vector(
      spannerDblp,
      spannerCrossref,
      cacmDblp,
      cacmCrossref,
      varajaoDblp,
      bigtableDblp,
      synthetic
    )
      .foreach(w => assertEquals(WorkRecord.contract.validate(w), Vector.empty, w.workId))
    Vector(popperOpenAlex, popperOrcid).foreach { p =>
      assertEquals(SourceAuthorProfile.contract.validate(p), Vector.empty, p.profileId)
    }
    assertEquals(SchemaVersions.WorkRecord, spannerDblp.schemaVersion)
    assertEquals(spannerCrossref.source, SourceType.Crossref)
  }
}
