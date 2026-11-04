package scholarchain.hadoop

import org.apache.hadoop.io.Text

import scholarchain.core.SourceType

class ShuffleAndParamsSuite extends munit.FunSuite {

  private def prov(id: String) =
    s"""{"sourceDataset":"openalex","sourceRecordId":"$id"}"""
  private def inst(name: String, from: String) =
    s"""{"institutionId":"institution:openalex:I1","name":"$name","provenance":${prov(from)}}"""

  test("envelope merge is associative and commutative (combiner safety)") {
    val es = Vector(
      Envelope.of(Rank.Reference, inst("A", "W1")),
      Envelope.of(Rank.Reference, inst("A", "W2")),
      Envelope.of(Rank.Reference, inst("B", "W3")),
      Envelope.of(Rank.Primary, inst("A", "I1"))
    )
    val results = es.permutations.map(_.reduce(Envelope.merge)).toSet
    assertEquals(results.size, 1)
    val r = results.head
    assertEquals(r.rank, Rank.Primary, "a primary record beats references")
    assertEquals(r.copies, 1L)
    val refs = es.take(3)
    val grouped = Set(
      Envelope.merge(Envelope.merge(refs(0), refs(1)), refs(2)),
      Envelope.merge(refs(0), Envelope.merge(refs(1), refs(2)))
    )
    assertEquals(grouped.size, 1)
    assertEquals(grouped.head.copies, 3L)
    assert(grouped.head.conflicting, "name A vs name B is a content conflict")
  }

  test("copies that differ only in provenance are duplicates, not conflicts") {
    val m = Envelope.merge(Envelope.of(1, inst("A", "W1")), Envelope.of(1, inst("A", "W2")))
    assertEquals(m.copies, 2L)
    assert(!m.conflicting)
    assertEquals(Envelope.decode(m.encode), m)
  }

  test("stable partitioner is deterministic, in range, and spreads keys") {
    val keys = (0 until 1000).map(i => new Text(s"work\twork:dblp:journals/x/K$i"))
    val p1 = keys.map(k => StableHash.partition(k, 7))
    val p2 =
      keys.map(k => new StableHashPartitioner().getPartition(new Text(k.toString), new Text(""), 7))
    assertEquals(p1, p2)
    assert(p1.forall(p => p >= 0 && p < 7))
    assertEquals(p1.toSet.size, 7)
    assertEquals(StableHash.of("".getBytes, 0), 0x811c9dc5)
    assertEquals(StableHash.of("a".getBytes, 1), 0xe40c292c)
  }

  test("text normalization is mechanical: NFC, whitespace, case, one trailing period") {
    assertEquals(TextNormalization.name("  João   VARAJÃO "), "joão varajão")
    assertEquals(
      TextNormalization.title("Spanner:  Google's\nDatabase."),
      "spanner: google's database"
    )
    assertEquals(TextNormalization.title("No period"), "no period")
  }

  test("reject records: deterministic ID, versioned, truncated excerpt, JSON round trip") {
    val run = TestSupport.run(SourceType.Dblp).copy(maxExcerptChars = 3)
    def mk = RejectRecord.build(
      "normalize",
      RejectReason.MissingKey,
      Disposition.Rejected,
      run,
      None,
      "file:/x@10",
      Vector("no key"),
      Some("😀😀😀😀")
    )
    assertEquals(mk.rejectId, mk.rejectId)
    assert(mk.rejectId.startsWith("reject:"))
    assertEquals(mk.schemaVersion, "scholarchain.hadoop.reject.v1")
    assertEquals(mk.excerpt, Some("😀😀😀"))
    assertEquals(RejectRecord.fromJson(RejectRecord.toJson(mk)), mk)
    val other = RejectRecord.build(
      "normalize",
      RejectReason.MissingKey,
      Disposition.Rejected,
      run,
      None,
      "file:/x@11",
      Vector("no key"),
      None
    )
    assertNotEquals(other.rejectId, mk.rejectId)
  }

  test("missing-key and invalid-key members are distinguished (blocking reject reasons)") {
    assertEquals(RejectReason.MissingKey.counter, Counters.MissingKey)
    assertEquals(RejectReason.InvalidBlockingKey.counter, Counters.SchemaFailure)
    assertEquals(RejectReason.UnresolvedMember.counter, Counters.MissingKey)
  }

  test("job parameters: nothing defaults, invalid values are explained") {
    val empty = TestSupport.conf(Map.empty)
    assertEquals(DblpSettings.from(empty).left.map(_.key), Left(JobParams.DblpRecordTypes))
    assertEquals(TeamSettings.from(empty).left.map(_.key), Left(JobParams.TeamSources))
    val c =
      TestSupport.conf(TestSupport.baseParams ++ Map(JobParams.DblpRecordTypes -> "article,www"))
    assert(DblpSettings.from(c).left.exists(_.message.contains("www")))
    val y = TestSupport.conf(TestSupport.baseParams ++ Map(JobParams.DblpYearRange -> "2024-2015"))
    assert(DblpSettings.from(y).isLeft)
    val e = TestSupport.conf(TestSupport.baseParams ++ Map(JobParams.DblpEncoding -> "UTF-16"))
    assert(DblpSettings.from(e).isLeft)
    val ok = DblpSettings.from(
      TestSupport.conf(
        TestSupport.baseParams ++ Map(
          JobParams.DblpYearRange -> "2015-2024",
          JobParams.DblpVenues -> "Commun. ACM|OSDI"
        )
      )
    )
    assertEquals(ok.map(_.years), Right(Some((2015, 2024))))
    assertEquals(ok.map(_.venues), Right(Some(Set("Commun. ACM", "OSDI"))))
    val t = TestSupport.conf(
      TestSupport.baseParams ++ Map(JobParams.TeamSources -> "dblp,scholarchain-derived")
    )
    assert(TeamSettings.from(t).isLeft)
    val m = TestSupport.conf(TestSupport.baseParams ++ Map(JobParams.TeamMaxSize -> "1"))
    assert(TeamSettings.from(m).isLeft)
    val codec = TestSupport.conf(Map(JobParams.CodecOutput -> "java.lang.String"))
    assert(JobParams.codecClass(codec, JobParams.CodecOutput).isLeft)
  }

  test("no pair-suppression knob exists: team settings are sources and a reporting threshold") {
    assertEquals(
      TeamSettings.from(TestSupport.conf(TestSupport.baseParams)),
      Right(TeamSettings(Set(SourceType.Dblp), 100))
    )
    assert(!JobParams.ManifestKeys.contains("scholarchain.team.oversizePolicy"))
    assert(!JobParams.ManifestKeys.exists(_.contains("skip")))
  }

  test("blocking and candidate settings record their versions; both entities, no entity knob") {
    val c = TestSupport.conf(
      TestSupport.baseParams ++ TestSupport.blockingParams ++ TestSupport.candidateParams
    )
    val b = BlockingSettings.from(c).toOption.get
    assertEquals(
      b.keySourceVersion,
      scholarchain.entityresolution.EntityResolution.blockingKeysVersion
    )
    assertEquals(b.maxBlockSize, 4)
    assertEquals(
      CandidateSettings.from(c).map(_.featureVersion),
      Right(scholarchain.entityresolution.EntityResolution.featuresVersion)
    )
    assert(!JobParams.ManifestKeys.contains("scholarchain.blocking.entity"))
    val bad = TestSupport.conf(
      TestSupport.blockingParams + (JobParams.BlockingKeySourceVersion -> "not a token")
    )
    assertEquals(
      BlockingSettings.from(bad).left.map(_.key),
      Left(JobParams.BlockingKeySourceVersion)
    )
  }

  test("the production key source delegates to entity-resolution (keys, version, levels)") {
    val src = new jobs.EntityResolutionKeySource
    assertEquals(src.version, "er-blocking.v1")
    assertEquals(src.levels, 4)
    val (w, n) = {
      val norm = normalize.OpenAlexNormalizer.workLine(
        TestSupport.readSample("openalex/works-sample.jsonl").split("\n")(1),
        TestSupport.run(SourceType.OpenAlex)
      )
      norm match {
        case normalize.Outcome.Accepted(_, out) => (out.works.head, out)
        case other => fail(s"unexpected $other")
      }
    }
    assertEquals(n.works.size, 1)
    assertEquals(
      src.researcherKeys(w, w.authors.head),
      scholarchain.entityresolution.EntityResolution.researcherBlockingKeys(w, w.authors.head)
    )
    assertEquals(src.researcherKeys(w, w.authors.head).head, "popper|c")
    assertEquals(
      src.workKeys(w),
      scholarchain.entityresolution.EntityResolution.workBlockingKeys(w)
    )
  }
}
