package scholarchain.core

import Fixtures.*

/**
 * Frozen JSON wire examples. Each golden string is the exact compact encoding of the fixture.
 * Changing any golden string requires bumping that record's schema version in SchemaVersions.
 */
class WireContractSuite extends munit.FunSuite {

  // Literal fragments shared by several goldens (still literal, frozen text).
  private val Run = "run:8dc188c7670e600b1e1f8bf679a11809bd4ebadbd4a9ed27d27e318a59a88614"
  private val DblpProv =
    s"""{"sourceDataset":"dblp","sourceRecordId":"journals/example/Synthetic21","snapshot":"dblp-2026-10-03","runId":"$Run","sourceSchemaVersion":"dblp-raw/1.0.0","policyVersion":null,"modelVersion":null,"promptVersion":null}"""
  private def derived(policy: String) =
    s"""{"sourceDataset":"scholarchain-derived","sourceRecordId":"work:dblp:journals/example/Synthetic21","snapshot":"dblp-2026-10-03","runId":"$Run","sourceSchemaVersion":"scholarchain.work-record.v1","policyVersion":"$policy","modelVersion":null,"promptVersion":null}"""
  private val RuntimeProv =
    s"""{"sourceDataset":"scholarchain-runtime","sourceRecordId":"query:q-0001","snapshot":"dblp-2026-10-03","runId":"$Run","sourceSchemaVersion":"scholarchain.query-plan.v1","policyVersion":null,"modelVersion":"example-llm-model","promptVersion":"answer-prompt-1"}"""

  private val Mention0 =
    s"""{"schemaVersion":"scholarchain.author-mention.v1","mentionId":"mention:4d99b473c7f1d066094cb66ae71ded0bb0305eea0b75006f46ff2fd12c4c0bc4","workId":"work:dblp:journals/example/Synthetic21","position":0,"rawName":"Ada Example","normalizedName":"ada example","sourceAuthorId":{"scheme":"dblp-pid","value":"00/0001"},"orcid":"0000-0000-0000-0001","affiliationIds":[],"rawAffiliations":[],"provenance":$DblpProv}"""
  private val Mention1 =
    s"""{"schemaVersion":"scholarchain.author-mention.v1","mentionId":"mention:191fd11b93e4fceca68d7e03e0d62de4a2fb826cfbdec968e4951677afd6fcbf","workId":"work:dblp:journals/example/Synthetic21","position":1,"rawName":"Ben Example","normalizedName":"ben example","sourceAuthorId":{"scheme":"dblp-pid","value":"00/0002"},"orcid":null,"affiliationIds":[],"rawAffiliations":[],"provenance":$DblpProv}"""

  val WorkRecordGolden: String =
    s"""{"schemaVersion":"scholarchain.work-record.v1","workId":"work:dblp:journals/example/Synthetic21","source":"dblp","sourceRecordId":"journals/example/Synthetic21","title":"A Synthetic Study of Collaboration","normalizedTitle":"a synthetic study of collaboration","year":2021,"venueId":"venue:dblp:journals/example","doi":"10.1234/example.2021.001","arxivId":null,"authors":[$Mention0,$Mention1],"abstractTextRef":null,"provenance":$DblpProv}"""

  val CoauthorEdgeGolden: String =
    s"""{"schemaVersion":"scholarchain.coauthor-edge.v1","edgeId":"coauthor:cd7c4e8bd9050455fb19cf46ce86ecd73659cbe3c0a9bce3e4206e1596f23071","researcherA":"researcher:orcid:0000-0000-0000-0001","researcherB":"researcher:orcid:0000-0000-0000-001X","window":{"startYear":2015,"endYear":2024},"jointPublicationCount":3,"firstCollaborationYear":2017,"lastCollaborationYear":2021,"representativeWorkIds":["work:dblp:journals/example/Synthetic21","work:dblp:conf/example/Synthetic22"],"provenance":${derived(
        "graph-policy-1.0.0"
      )}}"""

  val ResearcherMetricsGolden: String =
    s"""{"schemaVersion":"scholarchain.researcher-metrics.v1","researcherId":"researcher:orcid:0000-0000-0000-0001","window":{"startYear":2015,"endYear":2024},"publicationCount":10,"distinctCoauthorCount":9,"totalCoauthorOccurrences":25,"averageCoauthorsPerPaper":2.5,"maximumCoauthorsOnOnePaper":6,"maximumTeamWorkIds":["work:dblp:journals/example/Synthetic21"],"weightedDegree":25,"componentId":"cc-0001","componentSize":1523,"communityId":"community-17","bridgeScore":0.125,"bridgeMeasure":"betweenness-sampled","coreness":4,"provenance":${derived(
        "graph-policy-1.0.0"
      )}}"""

  val MatchDecisionGolden: String =
    s"""{"schemaVersion":"scholarchain.match-decision.v1","decisionId":"match:9d9d899ab153f9836f40ae28d20a71913ca8bc1f2a0313b6a15d124467f65670","entityKind":"researcher","sourceId":"mention:4d99b473c7f1d066094cb66ae71ded0bb0305eea0b75006f46ff2fd12c4c0bc4","targetId":"researcher:orcid:0000-0000-0000-0001","features":{"coauthorOverlap":0.75,"orcidExact":1},"rulesFired":["orcid-exact"],"decision":"ACCEPTED","confidence":0.97,"reasonCodes":["ORCID_EXACT"],"conflictingTargetIds":[],"decidedAt":"2026-10-29T12:00:00Z","provenance":${derived(
        "er-policy-1.0.0"
      )}}"""

  private val EvidenceWorkJson =
    s"""{"schemaVersion":"scholarchain.evidence-item.v1","evidenceId":"ev:a23c2deeb69843d6d7c61d20b74106c611b2ca9811701c05eb9edd7ac9dcf425","evidenceType":"source-record","workId":"work:dblp:journals/example/Synthetic21","sourceUri":"https://dblp.org/rec/journals/example/Synthetic21","chunkId":null,"graphPath":null,"score":1,"text":null,"provenance":$RuntimeProv}"""
  private val EvidenceEdgeJson =
    s"""{"schemaVersion":"scholarchain.evidence-item.v1","evidenceId":"ev:2ce09c14cf58ddcf790c6be853fd4b6a34ab6e7e15c7e5882db91127280c1413","evidenceType":"coauthor-edge","workId":null,"sourceUri":"urn:scholarchain:coauthor:cd7c4e8bd9050455fb19cf46ce86ecd73659cbe3c0a9bce3e4206e1596f23071","chunkId":null,"graphPath":["researcher:orcid:0000-0000-0000-0001","researcher:orcid:0000-0000-0000-001X"],"score":1,"text":null,"provenance":$RuntimeProv}"""

  val EvidenceBundleGolden: String =
    s"""{"schemaVersion":"scholarchain.evidence-bundle.v1","bundleId":"bundle:29c5fb7e497fc51e84c0e09a623484d12e2a54d6dd89850c18a6f7bcde870af9","queryId":"query:q-0001","items":[$EvidenceWorkJson,$EvidenceEdgeJson],"provenance":$RuntimeProv}"""

  val GraphEdgeGolden: String =
    s"""{"schemaVersion":"scholarchain.graph-edge.v1","edgeId":"gedge:d1b7e4740a1812ec2080af060fa4486fffa9ccf3c2a49417dbcd3fadf0dd11c0","fromId":"researcher:orcid:0000-0000-0000-0001","edgeType":"COAUTHORED_WITH","toId":"researcher:orcid:0000-0000-0000-001X","sourceEvidence":"coauthor:cd7c4e8bd9050455fb19cf46ce86ecd73659cbe3c0a9bce3e4206e1596f23071","properties":{"firstCollaborationYear":"2017","jointPublicationCount":"3","lastCollaborationYear":"2021"},"provenance":${derived(
        "graph-policy-1.0.0"
      )}}"""

  val AnswerClaimGolden: String =
    s"""{"schemaVersion":"scholarchain.answer-claim.v1","claimId":"claim:89b2d877ad2813d8bb282c38da17e97201ff36b55ac4a71ac95146e8a7fa99f1","text":"Ada Example and Ben Example coauthored 3 works between 2015 and 2024.","evidenceIds":["ev:2ce09c14cf58ddcf790c6be853fd4b6a34ab6e7e15c7e5882db91127280c1413"],"verificationStatus":"supported","confidence":0.9,"uncertainty":"Ben Example is identified by one source-asserted ORCID iD.","provenance":$RuntimeProv}"""

  val CanonicalResearcherGolden: String =
    s"""{"schemaVersion":"scholarchain.canonical-researcher.v1","researcherId":"researcher:orcid:0000-0000-0000-0001","displayName":"Ada Example","nameVariants":["A. Example"],"externalIds":[{"scheme":"orcid","value":"0000-0000-0000-0001"},{"scheme":"dblp-pid","value":"00/0001"}],"institutionIds":["institution:openalex:I1"],"identityConfidence":0.95,"mentionCount":12,"provenance":${derived(
        "er-policy-1.0.0"
      )}}"""

  val QueryPlanGolden: String =
    s"""{"schemaVersion":"scholarchain.query-plan.v1","queryId":"query:q-0001","question":"How often did Ada Example and Ben Example collaborate?","window":{"startYear":2015,"endYear":2024},"steps":[{"ordinal":0,"operation":"resolve-researcher","metrics":[],"arguments":{"name":"Ada Example"}},{"ordinal":1,"operation":"get-metrics","metrics":["joint-publication-count"],"arguments":{}}],"provenance":$RuntimeProv}"""

  val RunManifestGolden: String =
    """{"schemaVersion":"scholarchain.run-manifest.v1","stage":"hadoop.dblp-extract","codeVersion":"0123456789abcdef0123456789abcdef01234567","configDigest":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","inputSnapshots":[{"source":"dblp","snapshot":"dblp-2026-10-03"}],"parameters":{"maxRecords":"1000"},"attempt":1}"""

  private val OpenAlexProvT1 =
    s"""{"sourceDataset":"openalex","sourceRecordId":"T1","snapshot":"openalex-2026-10-14","runId":"$Run","sourceSchemaVersion":"openalex-raw/1.0.0","policyVersion":null,"modelVersion":null,"promptVersion":null}"""
  private val OpenAlexProvI1 =
    s"""{"sourceDataset":"openalex","sourceRecordId":"I1","snapshot":"openalex-2026-10-14","runId":"$Run","sourceSchemaVersion":"openalex-raw/1.0.0","policyVersion":null,"modelVersion":null,"promptVersion":null}"""

  val CoauthorPairCandidateGolden: String =
    s"""{"schemaVersion":"scholarchain.coauthor-pair-candidate.v1","endpointA":"mention:191fd11b93e4fceca68d7e03e0d62de4a2fb826cfbdec968e4951677afd6fcbf","endpointB":"mention:4d99b473c7f1d066094cb66ae71ded0bb0305eea0b75006f46ff2fd12c4c0bc4","workId":"work:dblp:journals/example/Synthetic21","year":2021,"teamSize":2,"provenance":$DblpProv}"""

  val PublicationTeamGolden: String =
    s"""{"schemaVersion":"scholarchain.publication-team.v1","workId":"work:dblp:journals/example/Synthetic21","year":2021,"researcherIds":["researcher:orcid:0000-0000-0000-0001","researcher:orcid:0000-0000-0000-001X"],"teamSize":2,"unresolvedMentionIds":[],"provenance":${derived(
        "er-policy-1.0.0"
      )}}"""

  val AuthorshipRecordGolden: String =
    s"""{"schemaVersion":"scholarchain.authorship-record.v1","workId":"work:dblp:journals/example/Synthetic21","mentionId":"mention:4d99b473c7f1d066094cb66ae71ded0bb0305eea0b75006f46ff2fd12c4c0bc4","researcherId":"researcher:orcid:0000-0000-0000-0001","position":0,"year":2021,"matchDecisionId":"match:9d9d899ab153f9836f40ae28d20a71913ca8bc1f2a0313b6a15d124467f65670","matchConfidence":0.97,"provenance":${derived(
        "er-policy-1.0.0"
      )}}"""

  val AnswerResponseGolden: String =
    s"""{"schemaVersion":"scholarchain.answer-response.v1","responseId":"answer:14cd1802bed530ed707b5b162772446cf925a11557853705770a9a1bfe9423f3","queryId":"query:q-0001","window":{"startYear":2015,"endYear":2024},"answerText":"They coauthored 3 works in 2015-2024.","claims":[$AnswerClaimGolden],"evidenceBundle":$EvidenceBundleGolden,"provenance":$RuntimeProv}"""

  val VenueGolden: String =
    s"""{"schemaVersion":"scholarchain.venue.v1","venueId":"venue:dblp:journals/example","name":"Example Journal","venueType":"journal","issns":["0000-0019"],"externalIds":[],"provenance":$DblpProv}"""

  val InstitutionGolden: String =
    s"""{"schemaVersion":"scholarchain.institution.v1","institutionId":"institution:openalex:I1","name":"Example University","rorId":null,"countryCode":"US","externalIds":[],"provenance":$OpenAlexProvI1}"""

  val TopicGolden: String =
    s"""{"schemaVersion":"scholarchain.topic.v1","topicId":"topic:openalex:T1","name":"Example Topic","parentTopicId":null,"provenance":$OpenAlexProvT1}"""

  val GraphNodeGolden: String =
    s"""{"schemaVersion":"scholarchain.graph-node.v1","nodeId":"work:dblp:journals/example/Synthetic21","label":"Work","properties":{"year":"2021"},"provenance":${derived(
        "graph-policy-1.0.0"
      )}}"""

  val EvidenceItemGolden: String = EvidenceWorkJson

  val AuthorMentionGolden: String = Mention1

  private def frozen[T](
      name: String,
      value: T,
      golden: String
  )(using c: RecordContract[T]): Unit = {
    test(s"$name: encoding equals the frozen wire example") {
      assertEquals(Json.write(value), golden)
      assertEquals(Json.encode(value), Right(golden))
    }
    test(s"$name: the frozen wire example decodes to the fixture") {
      assertEquals(Json.decode[T](golden), Right(value))
    }
  }

  frozen("WorkRecord", work, WorkRecordGolden)
  frozen("CoauthorEdge", coauthorEdge, CoauthorEdgeGolden)
  frozen("ResearcherMetrics", metrics, ResearcherMetricsGolden)
  frozen("MatchDecision", matchDecision, MatchDecisionGolden)
  frozen("EvidenceBundle", bundle, EvidenceBundleGolden)
  frozen("GraphEdge", graphEdge, GraphEdgeGolden)
  frozen("AnswerClaim", claim, AnswerClaimGolden)
  frozen("CanonicalResearcher", researcher, CanonicalResearcherGolden)
  frozen("QueryPlan", queryPlan, QueryPlanGolden)
  frozen("RunManifest", manifest, RunManifestGolden)

  frozen("CoauthorPairCandidate", pairCandidate, CoauthorPairCandidateGolden)
  frozen("PublicationTeam", team, PublicationTeamGolden)
  frozen("AuthorshipRecord", authorship, AuthorshipRecordGolden)
  frozen("AnswerResponse", answer, AnswerResponseGolden)
  frozen("Venue", venue, VenueGolden)
  frozen("Institution", institution, InstitutionGolden)
  frozen("Topic", topic, TopicGolden)
  frozen("GraphNode", graphNode, GraphNodeGolden)
  frozen("EvidenceItem", evidenceWork, EvidenceItemGolden)
  frozen("AuthorMention", mention1, AuthorMentionGolden)

  test("every record contract has a frozen wire example") {
    val frozenTypes = Set(
      "WorkRecord",
      "CoauthorEdge",
      "ResearcherMetrics",
      "MatchDecision",
      "EvidenceBundle",
      "GraphEdge",
      "AnswerClaim",
      "CanonicalResearcher",
      "QueryPlan",
      "RunManifest",
      "CoauthorPairCandidate",
      "PublicationTeam",
      "AuthorshipRecord",
      "AnswerResponse",
      "Venue",
      "Institution",
      "Topic",
      "GraphNode",
      "EvidenceItem",
      "AuthorMention"
    )
    assertEquals(frozenTypes, SchemaVersions.all.keySet)
  }

  private def roundTrip[T](name: String, value: T)(using c: RecordContract[T]): Unit =
    test(s"$name: validates and round-trips through JSON") {
      assertEquals(c.validate(value), Vector.empty)
      assertEquals(Json.decode[T](Json.write(value)), Right(value))
    }

  roundTrip("WorkRecord", work)
  roundTrip("AuthorMention", mention0)
  roundTrip("CanonicalResearcher", researcher)
  roundTrip("AuthorshipRecord", authorship)
  roundTrip("PublicationTeam", team)
  roundTrip("CoauthorPairCandidate", pairCandidate)
  roundTrip("CoauthorEdge", coauthorEdge)
  roundTrip("ResearcherMetrics", metrics)
  roundTrip("Venue", venue)
  roundTrip("Institution", institution)
  roundTrip("Topic", topic)
  roundTrip("MatchDecision", matchDecision)
  roundTrip("GraphNode", graphNode)
  roundTrip("GraphEdge", graphEdge)
  roundTrip("EvidenceItem", evidenceEdge)
  roundTrip("EvidenceBundle", bundle)
  roundTrip("QueryPlan", queryPlan)
  roundTrip("AnswerClaim", claim)
  roundTrip("AnswerResponse", answer)
  roundTrip("RunManifest", manifest)

  test("optional fields round-trip both as null and as values") {
    val withOptionals =
      metrics.copy(communityId = None, bridgeScore = None, bridgeMeasure = None, coreness = None)
    assertEquals(Json.decode[ResearcherMetrics](Json.write(withOptionals)), Right(withOptionals))
    assert(Json.write(withOptionals).contains("\"communityId\":null"))
  }

  test("unknown JSON fields are rejected (strict contracts)") {
    val extra = CoauthorEdgeGolden.replaceFirst("\\{", "{\"unexpected\":1,")
    assert(
      Json
        .decode[CoauthorEdge](extra)
        .left
        .exists(_.exists(_.isInstanceOf[ValidationError.Undecodable]))
    )
    val nestedExtra = CoauthorEdgeGolden.replace("\"endYear\":2024}", "\"endYear\":2024,\"x\":0}")
    assert(Json.decode[CoauthorEdge](nestedExtra).isLeft)
  }

  test("unknown enum tags are rejected on decode") {
    val bad = MatchDecisionGolden.replace("\"ACCEPTED\"", "\"MAYBE\"")
    assert(
      Json
        .decode[MatchDecision](bad)
        .left
        .exists(_.exists(_.isInstanceOf[ValidationError.Undecodable]))
    )
  }
}
