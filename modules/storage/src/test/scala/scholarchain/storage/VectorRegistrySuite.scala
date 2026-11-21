package scholarchain.storage

import scholarchain.core.{ChunkId, DocumentType}
import scholarchain.storage.model.*
import TestData.*

/** Suite 13, registry part (SE-P7-01): the five vector indexes, routing and key derivation. */
class VectorRegistrySuite extends munit.FunSuite {

  test("exactly the five required indexes (README L1403-1408)") {
    assertEquals(
      VectorIndexes.all.map(_.name).toSet,
      Set(
        "researcher-profile-v1",
        "collaboration-community-v1",
        "paper-abstract-v1",
        "collaboration-path-v1",
        "topic-institution-summary-v1"
      )
    )
    assertEquals(VectorIndexes.all.size, 5)
    VectorIndexes.all.foreach(i => assertEquals(VectorIndexes.byName(i.name), Some(i)))
    assertEquals(VectorIndexes.byName("researcher-profile-v2"), None)
  }

  test("every document type routes to exactly one index, as designed") {
    DocumentType.values.foreach { d =>
      assertEquals(VectorIndexes.all.count(_.documentTypes.contains(d)), 1, d.tag)
    }
    val routed =
      DocumentType.values.toVector.map(d => d.tag -> VectorIndexes.forDocumentType(d).name).toMap
    assertEquals(
      routed,
      Map(
        "researcher-profile" -> "researcher-profile-v1",
        "community-summary" -> "collaboration-community-v1",
        "path-summary" -> "collaboration-path-v1",
        "collaboration-summary" -> "collaboration-path-v1",
        "work-summary" -> "paper-abstract-v1",
        "work-abstract" -> "paper-abstract-v1",
        "topic-summary" -> "topic-institution-summary-v1"
      )
    )
    assert(VectorIndexes.CollaborationPath.note.contains("provisional"))
  }

  test("every index requires the compact common fields and the S3 pointer, hash, model and run") {
    VectorIndexes.all.foreach { i =>
      val required = i.required.map(_.key).toSet
      Set(
        "textS3Key",
        "textSha256",
        "modelVersion",
        "runId",
        "loadId",
        "indexVersion",
        "documentType"
      )
        .foreach(k => assert(required.contains(k), s"${i.name} requires $k"))
      assert(i.required.toSet.intersect(i.optional.toSet).isEmpty, i.name)
    }
  }

  test("non-filterable fields are the pointer, hash and lineage fields; no text field exists") {
    assertEquals(
      MetadataField.nonFilterableFields.map(_.key).toSet,
      Set(
        "textS3Key",
        "textSha256",
        "modelVersion",
        "dataSha256", // SE-P7-11: digest of the stored floats
        "loadId",
        "inputId",
        "schemaVersion",
        "sourcePath"
      )
    )
    assertEquals(
      MetadataField.filterableFields.map(_.key).toSet,
      Set(
        "documentType",
        "subjectId",
        "runId",
        "indexVersion",
        "sourceDataset",
        "inputSources",
        "researcherIds",
        "windowStart",
        "windowEnd",
        "communityId",
        "topicIds",
        "institutionIds",
        // CR-P7-11 M2: the researcher subject's ER identity status and anchor-collision flag.
        "identityStatus",
        "anchorCollision"
      )
    )
    assertEquals(
      VectorIndexes.all.filter(i =>
        i.allowed(MetadataField.IdentityStatusField) || i.allowed(MetadataField.AnchorCollision)
      ),
      Vector(VectorIndexes.ResearcherProfile),
      "identity fields belong to researcher profiles only (core: identity only for a researcher)"
    )
    val keys = MetadataField.values.map(_.key.toLowerCase).toSet
    Set("text", "body", "summary", "profile", "abstract").foreach(k => assert(!keys.contains(k), k))
  }

  test("the README filter dimensions are filterable fields") {
    import MetadataField.*
    Vector(
      ResearcherIds,
      WindowStart,
      WindowEnd,
      SourceDataset,
      InputSources,
      TopicIds,
      InstitutionIds,
      CommunityId,
      DocumentTypeField
    ).foreach(f => assert(f.filterable, f.key))
  }

  test("text chunk ID = core chunk ID of (subject, index, type, text hash): other text, other ID") {
    val sha = hex("text one")
    val k = VectorKeys.chunkId(researcherA, DocumentType.ResearcherProfile, sha)
    val expected = ChunkId
      .ofTextHash(researcherA, "researcher-profile-v1", DocumentType.ResearcherProfile, None, sha)
      .toOption
    assertEquals(k.toOption, expected)
    assert(expected.isDefined)
    assertNotEquals(
      k,
      VectorKeys.chunkId(researcherA, DocumentType.ResearcherProfile, hex("text two"))
    )
    assertNotEquals(k, VectorKeys.chunkId(researcherB, DocumentType.ResearcherProfile, sha))
    assert(VectorKeys.chunkId(researcherA, DocumentType.ResearcherProfile, "not-a-hash").isLeft)
    assert(VectorKeys.chunkId("not an id", DocumentType.ResearcherProfile, sha).isLeft)
  }

  test("SE-P7-09 V2: vector key = index version + core summary-input ID, independent of text") {
    val ix = VectorIndexes.ResearcherProfile
    assertEquals(VectorKeys.of(ix, inputId), Right(s"researcher-profile-v1/$inputId"))
    assertNotEquals(VectorKeys.of(ix, inputId), VectorKeys.of(VectorIndexes.PaperAbstract, inputId))
    assert(VectorKeys.of(ix, researcherA).isLeft, "only a suminput: ID is a vector source")
    assert(VectorKeys.of(ix, "suminput:not-hex").isLeft)
    assert(VectorKeys.of(null, inputId).isLeft)
  }

  test("SE-P7-09: path summaries need no researcher IDs (none are cited); profiles do") {
    assert(!VectorIndexes.CollaborationPath.required.contains(MetadataField.ResearcherIds))
    assert(VectorIndexes.CollaborationPath.optional.contains(MetadataField.ResearcherIds))
    assert(VectorIndexes.ResearcherProfile.required.contains(MetadataField.ResearcherIds))
    VectorIndexes.all.foreach(i => assert(i.required.contains(MetadataField.SourcePath), i.name))
  }
}
