package scholarchain.core

import Fixtures.*

class SchemaVersionSuite extends munit.FunSuite {

  private def unsupported(errs: Vector[ValidationError]): Boolean =
    errs.exists(_.isInstanceOf[ValidationError.UnsupportedSchemaVersion])

  test("schema version constants are frozen and distinct") {
    assertEquals(SchemaVersions.all.size, 24)
    assertEquals(SchemaVersions.all.values.toSet.size, 24)
    assertEquals(SchemaVersions.WorkRecord, "scholarchain.work-record.v3")
    assertEquals(SchemaVersions.CoauthorPairCandidate, "scholarchain.coauthor-pair-candidate.v2")
    assertEquals(SchemaVersions.MatchCandidate, "scholarchain.match-candidate.v1")
    assertEquals(SchemaVersions.CandidateBlock, "scholarchain.candidate-block.v1")
    assertEquals(SchemaVersions.IdentityLink, "scholarchain.identity-link.v1")
    assertEquals(SchemaVersions.SourceAuthorProfile, "scholarchain.source-author-profile.v1")
    assertEquals(SchemaVersions.CoauthorEdge, "scholarchain.coauthor-edge.v1")
    assertEquals(SchemaVersions.ResearcherMetrics, "scholarchain.researcher-metrics.v1")
    assertEquals(SchemaVersions.MatchDecision, "scholarchain.match-decision.v1")
    assertEquals(SchemaVersions.EvidenceBundle, "scholarchain.evidence-bundle.v1")
    assertEquals(SchemaVersions.RunManifest, "scholarchain.run-manifest.v1")
  }

  test("each contract accepts exactly its current version") {
    val contracts: Vector[RecordContract[?]] = Vector(
      WorkRecord.contract,
      AuthorMention.contract,
      CanonicalResearcher.contract,
      AuthorshipRecord.contract,
      PublicationTeam.contract,
      CoauthorPairCandidate.contract,
      CoauthorEdge.contract,
      ResearcherMetrics.contract,
      Venue.contract,
      Institution.contract,
      Topic.contract,
      MatchDecision.contract,
      GraphNode.contract,
      GraphEdge.contract,
      EvidenceItem.contract,
      EvidenceBundle.contract,
      QueryPlan.contract,
      AnswerClaim.contract,
      AnswerResponse.contract,
      RunManifest.contract,
      MatchCandidate.contract,
      CandidateBlock.contract,
      IdentityLink.contract,
      SourceAuthorProfile.contract
    )
    assertEquals(contracts.map(_.recordType).toSet, SchemaVersions.all.keySet)
    contracts.foreach { c =>
      assertEquals(c.currentVersion, SchemaVersions.all(c.recordType), c.recordType)
      assertEquals(c.supportedVersions, Vector(c.currentVersion), c.recordType)
      assert(c.checkVersion(c.currentVersion).isEmpty, c.recordType)
      val next = c.currentVersion.replaceAll("\\.v[0-9]+$", ".v999")
      assert(unsupported(c.checkVersion(next)), c.recordType)
      SchemaVersions.retired.getOrElse(c.recordType, Vector.empty).foreach { old =>
        assert(unsupported(c.checkVersion(old)), s"${c.recordType} $old")
      }
    }
    assertEquals(SchemaVersions.retired.keySet, Set("WorkRecord", "CoauthorPairCandidate"))
  }

  test("work-record.v1 JSON is rejected, and v1-shaped JSON labelled v3 lacks required keys") {
    val v3 = Json.write(work)
    val v1Shape = v3.substring(0, v3.indexOf(",\"recordType\":")) + "}"
    val v1 = v1Shape.replace(SchemaVersions.WorkRecord, "scholarchain.work-record.v1")
    assert(v1.startsWith("{\"schemaVersion\":\"scholarchain.work-record.v1\""))
    assert(v1.endsWith("\"promptVersion\":null}}"), "exactly the v1 key set")
    assertEquals(
      Json.decode[WorkRecord](v1Shape),
      Left(
        Vector(
          ValidationError.Undecodable(
            "WorkRecord",
            "missing key(s): " + WorkRecord.DefaultedFields.mkString(", ")
          )
        )
      )
    )
    assertEquals(
      Json.decode[WorkRecord](v1),
      Left(
        Vector(
          ValidationError.UnsupportedSchemaVersion(
            "WorkRecord",
            "scholarchain.work-record.v1",
            Vector("scholarchain.work-record.v3")
          )
        )
      )
    )
  }

  test("work-record.v2 JSON is rejected, and v2-shaped JSON labelled v3 lacks sourceLocalWorkIds") {
    val v3 = Json.write(work)
    val suffix = ",\"sourceLocalWorkIds\":[]}"
    assert(v3.endsWith(suffix))
    val v2Shape = v3.dropRight(suffix.length) + "}"
    val v2 = v2Shape.replace(SchemaVersions.WorkRecord, "scholarchain.work-record.v2")
    assertEquals(
      Json.decode[WorkRecord](v2),
      Left(
        Vector(
          ValidationError.UnsupportedSchemaVersion(
            "WorkRecord",
            "scholarchain.work-record.v2",
            Vector("scholarchain.work-record.v3")
          )
        )
      )
    )
    assertEquals(
      Json.decode[WorkRecord](v2Shape),
      Left(Vector(ValidationError.Undecodable("WorkRecord", "missing key(s): sourceLocalWorkIds")))
    )
  }

  test("coauthor-pair-candidate.v1 JSON (no positions) is rejected") {
    val v1 =
      s"""{"schemaVersion":"scholarchain.coauthor-pair-candidate.v1","endpointA":"${pairCandidate.endpointA}","endpointB":"${pairCandidate.endpointB}","workId":"$workId","year":2021,"teamSize":2,"provenance":${Json
          .write(pairCandidate)
          .split("\"provenance\":")(1)
          .dropRight(1)}}"""
    assert(Json.decode[CoauthorPairCandidate](v1).left.exists(unsupported))
    val v2NoPositions = v1.replace(".v1", ".v2")
    assert(
      Json
        .decode[CoauthorPairCandidate](v2NoPositions)
        .left
        .exists(_.exists(_.isInstanceOf[ValidationError.Undecodable])),
      "positions are required in v2"
    )
  }

  test("decode rejects an unsupported schema version before decoding fields") {
    val json =
      Json.write(coauthorEdge).replace(SchemaVersions.CoauthorEdge, "scholarchain.coauthor-edge.v2")
    assertEquals(
      Json.decode[CoauthorEdge](json),
      Left(
        Vector(
          ValidationError.UnsupportedSchemaVersion(
            "CoauthorEdge",
            "scholarchain.coauthor-edge.v2",
            Vector(SchemaVersions.CoauthorEdge)
          )
        )
      )
    )
  }

  test("decode rejects a missing or non-string schema version") {
    val missing = Json.write(topic).replace(s"\"schemaVersion\":\"${SchemaVersions.Topic}\",", "")
    assertEquals(
      Json.decode[Topic](missing),
      Left(Vector(ValidationError.MissingSchemaVersion("Topic")))
    )
    val numeric = Json.write(topic).replace(s"\"${SchemaVersions.Topic}\"", "1")
    assertEquals(
      Json.decode[Topic](numeric),
      Left(Vector(ValidationError.MissingSchemaVersion("Topic")))
    )
  }

  test("a record of another type is rejected by its schema version") {
    assert(Json.decode[CoauthorEdge](Json.write(metrics)).left.exists(unsupported))
  }

  test("in-memory validation rejects unsupported versions, including nested records") {
    assert(unsupported(WorkRecord.contract.validate(work.copy(schemaVersion = "x"))))
    val nested = work.copy(authors =
      Vector(mention0.copy(schemaVersion = "scholarchain.author-mention.v0"), mention1)
    )
    assert(unsupported(WorkRecord.contract.validate(nested)))
    val nestedItem = bundle.copy(items = bundle.items.map(_.copy(schemaVersion = "old")))
    assert(unsupported(EvidenceBundle.contract.validate(nestedItem)))
  }
}
