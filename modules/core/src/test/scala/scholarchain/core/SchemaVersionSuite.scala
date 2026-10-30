package scholarchain.core

import Fixtures.*

class SchemaVersionSuite extends munit.FunSuite {

  private def unsupported(errs: Vector[ValidationError]): Boolean =
    errs.exists(_.isInstanceOf[ValidationError.UnsupportedSchemaVersion])

  test("schema version constants are frozen and distinct") {
    assertEquals(SchemaVersions.all.size, 20)
    assertEquals(SchemaVersions.all.values.toSet.size, 20)
    assertEquals(SchemaVersions.WorkRecord, "scholarchain.work-record.v1")
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
      RunManifest.contract
    )
    assertEquals(contracts.map(_.recordType).toSet, SchemaVersions.all.keySet)
    contracts.foreach { c =>
      assertEquals(c.currentVersion, SchemaVersions.all(c.recordType), c.recordType)
      assertEquals(c.supportedVersions, Vector(c.currentVersion), c.recordType)
      assert(c.checkVersion(c.currentVersion).isEmpty, c.recordType)
      assert(unsupported(c.checkVersion(c.currentVersion.replace(".v1", ".v2"))), c.recordType)
    }
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
