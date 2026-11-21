package scholarchain.storage

import scholarchain.storage.fake.{InMemoryVectorStore, RecordingVectorStore}
import scholarchain.storage.model.*
import scholarchain.storage.port.{VectorReader, VectorWriter}
import TestData.*

/**
 * Suite 14 (SE-P7-01; README L1393-1396, L1413-1414, gate L1426): metadata schema, per-value cap,
 * filters, duplicate vector keys and the fake vector store.
 */
class VectorMetadataSuite extends munit.FunSuite {
  private val spec = VectorIndexes.ResearcherProfile
  private val info =
    IndexInfo(spec.name, 4, DistanceMetric.Cosine, Vector("textS3Key", "textSha256"))

  private def problems(md: VectorMetadata, index: VectorIndexSpec = spec) =
    VectorMetadataRules.problems(index, md, limits)

  private def store() = new InMemoryVectorStore(
    VectorIndexes.all.map(i =>
      i -> IndexInfo(i.name, 4, DistanceMetric.Cosine, Vector("textS3Key", "textSha256"))
    ),
    limits
  )

  private def record(
      subject: String,
      text: String,
      data: Vector[Float],
      md: VectorMetadata => VectorMetadata = identity
  ) = {
    val sha = hex(text)
    val key =
      VectorKeys.of(spec, "fake-embedder-v1", "suminput:" + hex(subject + "|" + text)).toOption.get
    val base = withMeta(profileMetadata(sha), "subjectId", MetaValue.Str(subject))
    VectorRecord(key, data, md(base))
  }

  test("valid researcher-profile metadata has no problem") {
    assertEquals(problems(profileMetadata()), Vector.empty)
  }

  test("missing required metadata is refused, field by field") {
    spec.required.foreach { f =>
      val p = problems(withoutMeta(profileMetadata(), f.key))
      assert(p.exists(_.startsWith(f.key + ": missing")), s"${f.key}: $p")
    }
  }

  test(
    "malformed metadata is refused: wrong type, bad IDs, bad hash, inverted window, foreign type"
  ) {
    val md = profileMetadata()
    def refused(k: String, v: MetaValue) = assert(problems(withMeta(md, k, v)).nonEmpty, s"$k = $v")
    // CR-P7-11 M2: the identity fields take a core status tag and true/false only.
    refused("identityStatus", MetaValue.Str("merged"))
    refused("identityStatus", MetaValue.StrList(Vector("resolved")))
    refused("anchorCollision", MetaValue.Str("yes"))
    refused("windowStart", MetaValue.Str("2015"))
    refused("researcherIds", MetaValue.Str(researcherA))
    refused("researcherIds", MetaValue.StrList(Vector(workId)))
    refused("researcherIds", MetaValue.StrList(Vector(researcherA, researcherA)))
    refused("runId", MetaValue.Str(loadId))
    refused("loadId", MetaValue.Str(runId))
    refused("communityId", MetaValue.Str(componentId))
    refused("topicIds", MetaValue.StrList(Vector(institutionId)))
    refused("textSha256", MetaValue.Str("ABC"))
    refused("textS3Key", MetaValue.Str("has space"))
    refused("documentType", MetaValue.Str("community-summary"))
    refused("documentType", MetaValue.Str("not-a-type"))
    refused("indexVersion", MetaValue.Str("researcher-profile-v2"))
    refused("sourceDataset", MetaValue.Str("wikipedia"))
    refused("inputSources", MetaValue.StrList(Vector("scholarchain-derived")))
    refused("windowStart", MetaValue.Num(2021))
    refused("windowEnd", MetaValue.Num(99999))
    assert(
      problems(withoutMeta(withoutMeta(md, "windowStart"), "windowEnd")).nonEmpty,
      "required here"
    )
    assert(problems(md, VectorIndexes.CollaborationCommunity).nonEmpty, "wrong index for the type")
  }

  test("unknown keys are refused, so no text field can be added") {
    Vector("text", "profileText", "summary").foreach { k =>
      assert(
        problems(withMeta(profileMetadata(), k, MetaValue.Str("x"))).exists(_.startsWith(k)),
        k
      )
    }
  }

  test("long text is refused by the per-value cap, in any field (gate L1426)") {
    val long = "a" * (limits.maxValueBytes + 1)
    val p = problems(withMeta(profileMetadata(), "textS3Key", MetaValue.Str(long)))
    assert(p.exists(_.contains("per-value cap")), p.toString)
    val atCap = "k" * limits.maxValueBytes
    assert(
      !problems(withMeta(profileMetadata(), "textS3Key", MetaValue.Str(atCap)))
        .exists(_.contains("per-value cap"))
    )
    val list = Vector.fill(limits.maxListValues + 1)(researcherA)
    assert(
      VectorMetadataRules
        .limitProblems(
          withMeta(profileMetadata(), "researcherIds", MetaValue.StrList(list)),
          limits
        )
        .nonEmpty
    )
  }

  test("the fake store refuses oversize metadata on write (service limits)") {
    val s = store()
    val r = record(
      researcherA,
      "t",
      Vector(1f, 0f, 0f, 0f),
      withMeta(_, "textS3Key", MetaValue.Str("a" * 300))
    )
    s.putVectors(spec.name, Vector(r)).toOption.get.head.outcome match {
      case Left(StoreError.Refused(why)) => assert(why.contains("per-value cap"))
      case other => fail(s"expected Refused, got $other")
    }
    assertEquals(s.count(spec.name), 0)
  }

  test("filters: eq, in and range on each README dimension, validated against the index") {
    val s = store()
    val a = record(researcherA, "a", Vector(1f, 0f, 0f, 0f))
    val b = record(
      researcherB,
      "b",
      Vector(0f, 1f, 0f, 0f),
      md =>
        withMeta(
          withMeta(
            withMeta(md, "researcherIds", MetaValue.StrList(Vector(researcherB))),
            "windowStart",
            MetaValue.Num(2018)
          ),
          "topicIds",
          MetaValue.StrList(Vector("topic:openalex:T20002"))
        )
    )
    assert(
      s.putVectors(spec.name, Vector(a, b))
        .toOption
        .get
        .forall(_.outcome == Right(PutOutcome.Written))
    )
    def keys(f: VectorFilter) =
      s.query(spec.name, Vector(1f, 1f, 0f, 0f), 10, Some(f)).toOption.get.map(_.key).toSet
    import MetadataField.*
    assertEquals(keys(VectorFilter.Eq(ResearcherIds, researcherB)), Set(b.key))
    assertEquals(
      keys(VectorFilter.In(ResearcherIds, Vector(researcherA, researcherB))),
      Set(a.key, b.key)
    )
    assertEquals(keys(VectorFilter.Range(WindowStart, 2016, 2020)), Set(b.key))
    assertEquals(keys(VectorFilter.Eq(SourceDataset, "scholarchain-derived")), Set(a.key, b.key))
    assertEquals(keys(VectorFilter.Eq(TopicIds, topicId)), Set(a.key))
    assertEquals(keys(VectorFilter.Eq(InstitutionIds, institutionId)), Set(a.key, b.key))
    assertEquals(keys(VectorFilter.Eq(CommunityId, communityId)), Set(a.key, b.key))
    assertEquals(keys(VectorFilter.Eq(DocumentTypeField, "researcher-profile")), Set(a.key, b.key))
    assertEquals(
      keys(
        VectorFilter.And(
          Vector(VectorFilter.Eq(SubjectId, researcherA), VectorFilter.Range(WindowEnd, 2020, 2020))
        )
      ),
      Set(a.key)
    )
  }

  test("filters on non-filterable fields, ranges on strings and eq on years are refused") {
    import MetadataField.*
    val s = store()
    def refused(f: VectorFilter) =
      assert(s.query(spec.name, Vector(1f, 0f, 0f, 0f), 1, Some(f)).isLeft, f.toString)
    refused(VectorFilter.Eq(TextS3Key, "k"))
    refused(VectorFilter.Eq(TextSha256, hex("x")))
    refused(VectorFilter.Range(SubjectId, 1, 2))
    refused(VectorFilter.Eq(WindowStart, "2015"))
    refused(VectorFilter.Range(WindowStart, 2020, 2015))
    refused(VectorFilter.In(ResearcherIds, Vector.empty))
    refused(VectorFilter.And(Vector.empty))
    assert(
      VectorFilters
        .problems(VectorIndexes.TopicInstitutionSummary, VectorFilter.Eq(CommunityId, communityId))
        .nonEmpty
    )
  }

  test(
    "duplicate vector keys: same record is AlreadyPresent, other text under a key is a Conflict"
  ) {
    val s = new RecordingVectorStore(store())
    val a = record(researcherA, "a", Vector(1f, 0f, 0f, 0f))
    assertEquals(
      s.putVectors(spec.name, Vector(a)).map(_.map(_.outcome)),
      Right(Vector(Right(PutOutcome.Written)))
    )
    assertEquals(
      s.putVectors(spec.name, Vector(a)).map(_.map(_.outcome)),
      Right(Vector(Right(PutOutcome.AlreadyPresent)))
    )
    val otherText =
      a.copy(metadata = withMeta(a.metadata, "textSha256", MetaValue.Str(hex("different"))))
    s.putVectors(spec.name, Vector(otherText)).toOption.get.head.outcome match {
      case Left(StoreError.Conflict(_)) => ()
      case other => fail(s"expected Conflict, got $other")
    }
    val otherData = a.copy(data = Vector(0f, 1f, 0f, 0f))
    assert(s.putVectors(spec.name, Vector(otherData)).toOption.get.head.outcome.isLeft)
    assertEquals(
      s.getVectors(spec.name, Vector(a.key)),
      Right(Vector(a)),
      "the stored vector is unchanged"
    )
    assert(s.putVectors(spec.name, Vector(a, a)).isLeft, "duplicate keys inside one batch")
    assertEquals(
      (s.counts.written, s.counts.alreadyPresent, s.counts.failed, s.batches),
      (1, 1, 2, 5)
    )
  }

  test("dimension mismatch, non-finite data, unknown index and oversize batches are refused") {
    val s = store()
    val short = record(researcherA, "a", Vector(1f, 0f))
    assert(s.putVectors(spec.name, Vector(short)).toOption.get.head.outcome.isLeft)
    val nan = record(researcherA, "a", Vector(Float.NaN, 0f, 0f, 0f))
    assert(s.putVectors(spec.name, Vector(nan)).toOption.get.head.outcome.isLeft)
    assert(
      s.putVectors("no-such-index", Vector(record(researcherA, "a", Vector(1f, 0f, 0f, 0f)))).isLeft
    )
    val many = (0 to limits.maxBatchSize).toVector.map(i =>
      record(researcherA, s"t$i", Vector(1f, 0f, 0f, 0f))
    )
    assert(s.putVectors(spec.name, many).isLeft)
    assert(s.putVectors(spec.name, Vector.empty).isLeft)
    assert(s.query(spec.name, Vector(1f, 0f), 1, None).isLeft, "query dimension")
    assert(
      s.query(spec.name, Vector(1f, 0f, 0f, 0f), limits.maxTopK + 1, None).isLeft,
      "topK bound"
    )
    assertEquals(s.count(spec.name), 0)
    assertEquals(s.describe(spec.name), Right(Some(info)))
    assertEquals(s.describe("no-such-index"), Right(None))
  }

  test("query returns the closest vectors first") {
    val s = store()
    val a = record(researcherA, "a", Vector(1f, 0f, 0f, 0f))
    val b = record(researcherB, "b", Vector(0f, 1f, 0f, 0f))
    s.putVectors(spec.name, Vector(a, b))
    assertEquals(
      s.query(spec.name, Vector(0.1f, 0.9f, 0f, 0f), 2, None).map(_.map(_.key)),
      Right(Vector(b.key, a.key))
    )
  }

  test("injected batch failures surface unchanged and write nothing") {
    val s = store()
    s.failNextWrites(StoreError.Transient("ThrottlingException"))
    val a = record(researcherA, "a", Vector(1f, 0f, 0f, 0f))
    assertEquals(
      s.putVectors(spec.name, Vector(a)),
      Left(StoreError.Transient("ThrottlingException"))
    )
    assertEquals(s.count(spec.name), 0)
  }

  test("the vector ports and the fake have no delete") {
    Vector(classOf[VectorReader], classOf[VectorWriter], classOf[InMemoryVectorStore]).foreach {
      c =>
        val names = c.getMethods.map(_.getName.toLowerCase).toSet
        assert(!names.exists(_.contains("delete")), s"${c.getSimpleName}")
    }
  }
}
