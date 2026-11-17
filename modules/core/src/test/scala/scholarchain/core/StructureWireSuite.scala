package scholarchain.core

import StructureFixtures.*

/**
 * Frozen JSON wire examples and ID formulas of the DE-P6-03 contracts. Each golden string is the
 * exact compact encoding of its fixture. Changing any golden string requires bumping that record's
 * schema version in SchemaVersions.
 */
class StructureWireSuite extends munit.FunSuite {

  // Pinned literal IDs (each checked against its formula below).
  private val ARun = "run:62f7df70f585ca6be513c5d14ef00409967412e6acd5ab8c2f369f6f34f64bfa"
  private val GRun = "run:4eb49e707c88dacafc9e25e51440169044d7c9f6033504cacf0ef78e9e2e0d41"
  private val DRun = "run:8dc188c7670e600b1e1f8bf679a11809bd4ebadbd4a9ed27d27e318a59a88614"
  private val IRun = "run:b6908bc5179c8eb1fcd062370d4fa77ac39a537fe465344cf83f43b1708f80ba"
  private val ADigest = "ac573722caf6f122485d5fdbf4832c557d0b0ef7ff940144c52fe33d4852c348"
  private val RA = "researcher:orcid:0000-0000-0000-0001"
  private val RC =
    "researcher:sc:v1:6408a5d4a47f0c82908175bcf67d848349b35e47d3df2aef8acfbeee05209ff0"
  private val RD =
    "researcher:sc:v1:e68814616f5e61d5f30123146d61e2a056de3b789864da35dd9b99dccab89251"
  private val M0 = "mention:4d99b473c7f1d066094cb66ae71ded0bb0305eea0b75006f46ff2fd12c4c0bc4"
  private val W = "work:dblp:journals/example/Synthetic21"
  private val T1 = "topic:openalex:T1"
  private val AuthoredA = "gedge:22664df48936f78740561dc94e2d4d9b8c56dd737ad1ebd6a3cec9aa77a3c67a"
  private val HasTopicT1 = "gedge:eb5691fd09c3913240c708273fe93c041133d9bf03550509ca9e87c405152518"
  private val CW2 = "component:762eafafc9d4544c5a6d134775169bd7b57bef497bc77b978ce8d38265e24bb0"
  private val TopicInput =
    "suminput:0e4351ff8aaba69f048d49822266ac0fa3b51a98cf58694581f7a265ffa70fca"

  private val pins: Vector[(String, String, String)] = Vector(
    ("analysisRunId", ARun, AnalysisFixtures.analysisRunId),
    ("rA", RA, AnalysisFixtures.rA),
    ("rC", RC, AnalysisFixtures.rC),
    ("rD", RD, AnalysisFixtures.rD),
    ("mention0", M0, Fixtures.mention0.mentionId),
    ("AUTHORED A", AuthoredA, edgeOf(EdgeLabel.Authored, RA, W).edgeId),
    ("HAS_TOPIC T1", HasTopicT1, edgeOf(EdgeLabel.HasTopic, W, T1).edgeId),
    ("component W2", CW2, AnalysisFixtures.componentIdOf(RC, AnalysisFixtures.w2)),
    ("topic summary", TopicInput, topicSummary.inputId)
  )

  pins.foreach { case (name, literal, actual) =>
    test(s"pinned literal ID $name equals its formula") {
      assertEquals(actual, literal)
    }
  }

  private def hash(prefix: String, fields: String*): String =
    prefix + ":" + Canonical.sha256Hex(Canonical.encode(fields.toVector))

  test("graph edge v2 IDs keep the v1 formula: type, endpoints, graph policy, evidence") {
    assertEquals(
      AuthoredA,
      hash("gedge", "scholarchain.graph-edge.v1", "AUTHORED", RA, W, "graph-policy-1.0.0", M0)
    )
  }

  test("entity summary input ID is SHA-256 of the documented netstring encoding") {
    assertEquals(
      TopicInput,
      hash(
        "suminput",
        "scholarchain.entity-summary-input.v1",
        "topic-summary",
        T1,
        "graph-policy-1.0.0"
      )
    )
    assertNotEquals(
      EntitySummaryInputId.of(DocumentType.WorkSummary, T1, Fixtures.graphPolicy),
      Right(TopicInput)
    )
  }

  test(
    "path sample key is SHA-256 of the documented netstring encoding; seed and window are inputs"
  ) {
    assertEquals(
      PathSampleKey.of(7L, AnalysisFixtures.w1, RA),
      Right(Canonical.hashFields("scholarchain.path-sample.v1", "7", "2015", "2019", RA))
    )
    assertNotEquals(
      PathSampleKey.of(8L, AnalysisFixtures.w1, RA),
      PathSampleKey.of(7L, AnalysisFixtures.w1, RA)
    )
    assertNotEquals(
      PathSampleKey.of(7L, AnalysisFixtures.w2, RA),
      PathSampleKey.of(7L, AnalysisFixtures.w1, RA)
    )
    assert(PathSampleKey.of(7L, AnalysisFixtures.w1, W).isLeft)
    // The sample is the k smallest keys, returned sorted by UTF-8 bytes.
    val members = Vector(AnalysisFixtures.rA, AnalysisFixtures.rB, AnalysisFixtures.rC)
    val bySmallestKey = members
      .map(m => Fixtures.ok(PathSampleKey.of(7L, AnalysisFixtures.w1, m)) -> m)
      .sortBy(_._1)
      .take(2)
      .map(_._2)
    assertEquals(sampleW1.toSet, bySmallestKey.toSet)
  }

  // ---------------------------------------------------------------- golden fragments

  private val W1 = """{"startYear":2015,"endYear":2019}"""
  private val W2 = """{"startYear":2020,"endYear":2024}"""
  private val L =
    s"""{"groupingRunId":"$GRun","decisionRunId":"$DRun","inputRunId":"$IRun","codeVersion":"89abcdef0123456789abcdef0123456789abcdef","configDigest":"$ADigest","groupingPolicyVersion":"er-grouping.v1","matchPolicyVersion":"er-policy-1.0.0","identityVersion":"v1","groupingEvaluation":"unevaluated"}"""
  private def prov(subject: String) =
    s"""{"sourceDataset":"scholarchain-derived","sourceRecordId":"$subject","snapshot":"dblp-2026-10-03","runId":"$ARun","sourceSchemaVersion":"scholarchain.publication-team.v1","policyVersion":"graph-policy-1.0.0","modelVersion":null,"promptVersion":null}"""

  val ResearcherNodeGolden: String =
    s"""{"schemaVersion":"scholarchain.graph-node.v2","nodeId":"$RA","label":"Researcher","properties":{"anchorNamespace":"orcid","identityStatus":"resolved","memberCount":"2"},"lineage":$L,"provenance":${prov(
        RA
      )}}"""

  val MentionNodeGolden: String =
    s"""{"schemaVersion":"scholarchain.graph-node.v2","nodeId":"$M0","label":"AuthorMention","properties":{"assignmentStatus":"resolved","position":"0","workId":"$W"},"lineage":$L,"provenance":${prov(
        M0
      )}}"""

  val AuthoredGolden: String =
    s"""{"schemaVersion":"scholarchain.graph-edge.v2","edgeId":"$AuthoredA","fromId":"$RA","edgeType":"AUTHORED","toId":"$W","sourceEvidence":"$M0","properties":{"position":"0"},"lineage":$L,"provenance":${prov(
        AuthoredA
      )}}"""

  val StructureGolden: String =
    s"""{"schemaVersion":"scholarchain.window-structure.v1","window":$W2,"ordinal":1,"researcherCount":3,"edgeCount":1,"componentCount":2,"largestComponentSize":2,"largestComponentFraction":0.6666666666666666,"singletonCount":1,"communityCount":2,"largestCommunitySize":2,"crossCommunityEdgeCount":0,"communityAlgorithm":"label-propagation","communityIterations":2,"communityConverged":true,"maxCoreness":1,"lineage":$L,"provenance":${prov(
        "window:2020-2024"
      )}}"""

  val EstimateGolden: String =
    s"""{"schemaVersion":"scholarchain.path-length-estimate.v1","window":$W2,"componentId":"$CW2","componentSize":2,"method":"sampled-bfs","sampleSeed":7,"sampleSize":2,"sourceIds":["$RC","$RD"],"exact":true,"distanceCounts":[{"distance":1,"pairs":2}],"pairCount":2,"averageDistance":1,"maxObservedDistance":1,"effectiveDiameter90":1,"lineage":$L,"provenance":${prov(
        CW2
      )}}"""

  val ComparisonGolden: String =
    s"""{"schemaVersion":"scholarchain.window-comparison.v1","fromWindow":$W1,"toWindow":$W2,"ordinal":0,"inclusionRuleCompatible":true,"overlapYears":0,"limitations":["grouping-unevaluated","works-without-year-excluded"],"researchersInBoth":1,"researchersOnlyFrom":2,"researchersOnlyTo":2,"edgesInBoth":0,"edgesOnlyFrom":2,"edgesOnlyTo":1,"lineage":$L,"provenance":${prov(
        "window-pair:2015-2019:2020-2024"
      )}}"""

  val ChangeGolden: String =
    s"""{"schemaVersion":"scholarchain.researcher-temporal-change.v1","researcherId":"$RC","fromWindow":$W1,"toWindow":$W2,"presence":"in-both","from":{"publicationCount":1,"distinctCoauthorCount":1,"averageCoauthorsPerPaper":1,"maximumCoauthorsOnOnePaper":1,"componentSize":3,"inLargestComponent":true,"communitySize":1,"crossCommunityDegree":1,"communitiesReached":1,"participationCoefficient":0,"coreness":1},"to":{"publicationCount":2,"distinctCoauthorCount":1,"averageCoauthorsPerPaper":1,"maximumCoauthorsOnOnePaper":1,"componentSize":2,"inLargestComponent":true,"communitySize":2,"crossCommunityDegree":0,"communitiesReached":0,"participationCoefficient":0,"coreness":1},"retainedCoauthors":0,"gainedCoauthors":1,"lostCoauthors":1,"communityOverlap":1,"lineage":$L,"provenance":${prov(
        RC
      )}}"""

  val TopicSummaryGolden: String =
    s"""{"schemaVersion":"scholarchain.entity-summary-input.v1","inputId":"$TopicInput","documentType":"topic-summary","subjectId":"$T1","facts":[{"name":"name","value":"Example Topic","evidence":[{"kind":"graph-node","id":"$T1"}]},{"name":"work","value":"$W","evidence":[{"kind":"graph-edge","id":"$HasTopicT1"}]}],"lineage":$L,"provenance":${prov(
        TopicInput
      )}}"""

  private def frozen[T](name: String, value: => T, golden: String)(using
      c: RecordContract[T]
  ): Unit = {
    test(s"$name: encoding equals the frozen wire example") {
      assertEquals(Json.write(value), golden)
      assertEquals(Json.encode(value), Right(golden))
    }
    test(s"$name: the frozen wire example decodes to the fixture") {
      assertEquals(Json.decode[T](golden), Right(value))
    }
  }

  frozen("GraphNodeV2 (Researcher)", nodeOf(RA), ResearcherNodeGolden)
  frozen("GraphNodeV2 (AuthorMention)", nodeOf(M0), MentionNodeGolden)
  frozen("GraphEdgeV2 (AUTHORED)", edgeOf(EdgeLabel.Authored, RA, W), AuthoredGolden)
  frozen("WindowStructure", structure2, StructureGolden)
  frozen("PathLengthEstimate (exact)", estimateW2, EstimateGolden)
  frozen("WindowComparison", comparison, ComparisonGolden)
  frozen("ResearcherTemporalChange (in both windows)", changeC, ChangeGolden)
  frozen("EntitySummaryInput (topic summary)", topicSummary, TopicSummaryGolden)

  test("strict decoding: unknown keys and graph-node.v1 JSON are refused") {
    assert(Json.decode[GraphNodeV2](ResearcherNodeGolden.replaceFirst("\\{", "{\"x\":1,")).isLeft)
    val v1 = ResearcherNodeGolden.replace("graph-node.v2", "graph-node.v1")
    assert(
      Json
        .decode[GraphNodeV2](v1)
        .left
        .exists(_.exists(_.isInstanceOf[ValidationError.UnsupportedSchemaVersion]))
    )
    assert(Json.decode[GraphNode](ResearcherNodeGolden).isLeft, "v1 never reads a v2 node")
  }
}
