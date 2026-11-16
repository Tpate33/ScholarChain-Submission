package scholarchain.core

import scala.collection.immutable.SortedMap

import AnalysisFixtures.*

/**
 * Frozen JSON wire examples and stable-ID formulas of the Phase 6 analysis contracts (DE-P6-02).
 * Each golden string is the exact compact encoding of its fixture. Changing any golden string
 * requires bumping that record's schema version in SchemaVersions.
 */
class AnalysisWireSuite extends munit.FunSuite {

  // Pinned literal IDs (each checked against its formula below).
  private val IRun = "run:b6908bc5179c8eb1fcd062370d4fa77ac39a537fe465344cf83f43b1708f80ba"
  private val DRun = "run:8dc188c7670e600b1e1f8bf679a11809bd4ebadbd4a9ed27d27e318a59a88614"
  private val GRun = "run:4eb49e707c88dacafc9e25e51440169044d7c9f6033504cacf0ef78e9e2e0d41"
  private val ARun = "run:62f7df70f585ca6be513c5d14ef00409967412e6acd5ab8c2f369f6f34f64bfa"
  private val RA = "researcher:orcid:0000-0000-0000-0001"
  private val RB = "researcher:orcid:0000-0000-0000-001X"
  private val RC =
    "researcher:sc:v1:6408a5d4a47f0c82908175bcf67d848349b35e47d3df2aef8acfbeee05209ff0"
  private val RD =
    "researcher:sc:v1:e68814616f5e61d5f30123146d61e2a056de3b789864da35dd9b99dccab89251"
  private val RE =
    "researcher:sc:v1:a4d447543ba840e12e59210f0aa9716251db635bcd46a108d54f7d2533f7ea4e"
  private val CDA = RC
  private val CDB = RD
  private val EAB = "coauthor:8e650792e4622d04f01eed184faa7ca60d57fba650ce48be831dc4df5f3a7e8f"
  private val EBC = "coauthor:4bf10d2f8315394d7b6665428b6846ebab003c173bea0a4f8b2cafdcc150fbbf"
  private val ECD = "coauthor:3d2e5be172daf1a03613a468cbb2ba9daa96a382c0d03cd39ae3903c0b69f2a5"
  private val CW1 = "component:e2fa7dfe0e6c8ed3adf6c36d6e3bd6f4cef0cf3a1d2e91cca31705a78a499126"
  private val CMAB = "community:03b5973ed51595449127952875028ed3f1416b318e5d9828bc87211732ba8a8f"
  private val PAC = "path:9b36adadd1ad1b88ee905147ebc798d292567aee3cd0999501fa5c2c75fc44df"
  private val SB = "suminput:5b92522dc1a5ab1fdb6d1ead41aba6c4d40a5f1f5fe3bf57bf10a5dc819df398"
  private val LOAD = "load:fa5344b2abfbaf061e5a5dc5afeb05332cf01d311a45e2812ff5a62fcfdaeb9a"
  private val G1 = "work:dblp:journals/example/Synthetic31"
  private val G2 = "work:dblp:journals/example/Synthetic32"
  private val G3 = "work:dblp:journals/example/Synthetic33"
  private val G4 = "work:dblp:journals/example/Synthetic34"
  private val G6 = "work:dblp:journals/example/Synthetic36"

  /** The analysis config digest of AnalysisFixtures.config (repair 01: derived, then pinned). */
  private val ADigest = "ac573722caf6f122485d5fdbf4832c557d0b0ef7ff940144c52fe33d4852c348"
  private val BPDigest = "965c3ce886c0216527894d2e47abc1dc82045ac33283a74d2b0f348af3025108"

  private val pins: Vector[(String, String, String)] = Vector(
    ("analysisConfigDigest", ADigest, analysisConfigDigest),
    ("benchmarkPairsDigest", BPDigest, BenchmarkPairList.digest(benchmarkPairs)),
    ("inputRunId", IRun, inputRunId),
    ("decisionRunId", DRun, decisionRunId),
    ("groupingRunId", GRun, groupingRunId),
    ("analysisRunId", ARun, analysisRunId),
    ("rA", RA, rA),
    ("rB", RB, rB),
    ("rC", RC, rC),
    ("rD", RD, rD),
    ("rE", RE, rE),
    ("edgeCD.researcherA", CDA, edgeCD.researcherA),
    ("edgeCD.researcherB", CDB, edgeCD.researcherB),
    ("edgeAB", EAB, edgeAB.edgeId),
    ("edgeBC", EBC, edgeBC.edgeId),
    ("edgeCD", ECD, edgeCD.edgeId),
    ("component W1", CW1, componentIdOf(rA, w1)),
    ("community AB W1", CMAB, communityIdOf(rB, w1)),
    ("pathAC", PAC, pathAC.pathId),
    ("profileB1", SB, profileB1.inputId),
    ("load", LOAD, literalLoad.loadId),
    ("g1", G1, g1),
    ("g2", G2, g2),
    ("g3", G3, g3),
    ("g4", G4, g4),
    ("g6", G6, g6)
  )

  pins.foreach { case (name, literal, actual) =>
    test(s"pinned literal ID $name equals its formula") {
      assertEquals(actual, literal)
    }
  }

  // ---------------------------------------------------------------- ID formulas

  private def hash(prefix: String, fields: String*): String =
    prefix + ":" + Canonical.sha256Hex(Canonical.encode(fields.toVector))

  test("component ID is SHA-256 of the documented netstring encoding (anchor = smallest member)") {
    assertEquals(
      componentIdOf(rA, w1),
      hash("component", "scholarchain.component.v1", "2015", "2019", "graph-policy-1.0.0", rA)
    )
    assertEquals(
      ComponentId.of(w1, Fixtures.graphPolicy, Vector(rC, rB, rA)),
      ComponentId.of(w1, Fixtures.graphPolicy, Vector(rA, rB, rC)),
      "member order does not matter"
    )
    assertNotEquals(
      ComponentId.of(w2, Fixtures.graphPolicy, Vector(rA)),
      ComponentId.of(w1, Fixtures.graphPolicy, Vector(rA))
    )
    assert(ComponentId.of(w1, Fixtures.graphPolicy, Vector.empty).isLeft)
    assert(ComponentId.of(w1, Fixtures.graphPolicy, Vector(Fixtures.workId)).isLeft)
  }

  test(
    "community ID is SHA-256 of the documented netstring encoding; algorithm and parameters are inputs"
  ) {
    assertEquals(
      communityIdOf(rB, w1),
      hash(
        "community",
        "scholarchain.community.v1",
        "2015",
        "2019",
        "graph-policy-1.0.0",
        "label-propagation",
        "1",
        "maxIterations",
        "20",
        rA
      )
    )
    def id(alg: String, params: SortedMap[String, String]) =
      CommunityId.of(w1, Fixtures.graphPolicy, alg, params, Vector(rB, rA))
    val base = id("label-propagation", SortedMap("maxIterations" -> "20"))
    assertEquals(base, Right(communityIdOf(rB, w1)))
    assertNotEquals(id("louvain", SortedMap("maxIterations" -> "20")), base)
    assertNotEquals(id("label-propagation", SortedMap("maxIterations" -> "21")), base)
    assertNotEquals(
      id("label-propagation", SortedMap("maxIterations" -> "20", "randomSeed" -> "1")),
      base
    )
    assert(id("label propagation", SortedMap.empty).isLeft)
  }

  test("evidence path ID is SHA-256 of the documented netstring encoding; node order is an input") {
    assertEquals(
      pathAC.pathId,
      hash(
        "path",
        "scholarchain.evidence-path.v1",
        "2015",
        "2019",
        "graph-policy-1.0.0",
        "3",
        rA,
        rB,
        rC
      )
    )
    assertNotEquals(
      EvidencePathId.of(w1, Fixtures.graphPolicy, Vector(rC, rB, rA)),
      Right(pathAC.pathId)
    )
    assert(EvidencePathId.of(w1, Fixtures.graphPolicy, Vector(rA)).isLeft)
  }

  test("summary input ID is SHA-256 of the documented netstring encoding") {
    assertEquals(
      profileB1.inputId,
      hash(
        "suminput",
        "scholarchain.summary-input.v1",
        "researcher-profile",
        rB,
        "2015",
        "2019",
        "graph-policy-1.0.0"
      )
    )
    assertNotEquals(
      SummaryInputId.of(DocumentType.CollaborationSummary, rB, w1, Fixtures.graphPolicy),
      Right(profileB1.inputId)
    )
  }

  test("edge IDs keep the v1 formula: canonical pair, window and graph policy") {
    assertEquals(
      edgeAB.edgeId,
      hash(
        "coauthor",
        "scholarchain.coauthor-edge.v1",
        rA,
        rB,
        "2015",
        "2019",
        "graph-policy-1.0.0"
      )
    )
  }

  test("the new ID kinds validate and classify") {
    for (
      (kind, id) <- Vector(
        IdKind.Component -> componentIdOf(rA, w1),
        IdKind.Community -> communityIdOf(rB, w1),
        IdKind.EvidencePath -> pathAC.pathId,
        IdKind.SummaryInput -> profileB1.inputId,
        IdKind.StoreLoad -> literalLoad.loadId
      )
    ) {
      assertEquals(StableId.validate(kind, id), Right(id))
      assertEquals(StableId.validateAny(id), Right(kind))
      assert(StableId.validate(kind, id.dropRight(1)).isLeft)
    }
  }

  test(
    "repair 01: store load ID is SHA-256 of the v2 netstring encoding with sorted (path, role) pairs"
  ) {
    assertEquals(
      literalLoad.loadId,
      hash(
        "load",
        "scholarchain.store-load.v2",
        "s3",
        analysisRunId,
        "d" * 64,
        "1",
        "edges/part-00000.parquet",
        "coauthor-edges"
      )
    )
    def load(roles: Map[String, String]) =
      StoreLoadManifest.build(TargetStore.S3, Right(literalParts), "d" * 64, roles).map(_.loadId)
    val p = "edges/part-00000.parquet"
    val q = "metrics/part-00000.jsonl"
    // CR-P6-13 finding 3: the same parts with different roles are different loads.
    assertNotEquals(load(Map(p -> "researcher-vertices")), load(Map(p -> "edges")))
    assertNotEquals(
      load(Map(p -> "coauthor-edges", q -> "metrics")),
      load(Map(p -> "metrics", q -> "coauthor-edges")),
      "swapping roles between parts changes the ID"
    )
    // The same inputs give the same ID, whatever the map's iteration order.
    assertEquals(load(Map(p -> "edges")), load(Map(p -> "edges")))
    assertEquals(
      load(Map(p -> "coauthor-edges", q -> "metrics")),
      load(Map(q -> "metrics", p -> "coauthor-edges"))
    )
    assert(load(Map(p -> "edges")).isRight)
    // The contract recomputes the ID with the roles: a relabelled role is refused.
    val relabelled =
      literalLoad.copy(items = literalLoad.items.map(_.copy(targetRole = "researcher-vertices")))
    assert(
      StoreLoadManifest.contract.validate(relabelled).exists(_.message.contains("loadId: expected"))
    )
    assert(
      StoreLoadId.of(TargetStore.S3, analysisRunId, "d" * 64, Vector(p -> "a", p -> "b")).isLeft,
      "duplicate paths are refused"
    )
  }

  test("repair 01: benchmark pair list contract (canonical, ascending, versioned, provenance)") {
    val c = BenchmarkPairList.contract
    assertEquals(c.validate(benchmarkPairs), Vector.empty)
    def bad(l: BenchmarkPairList, fragment: String) =
      assert(
        c.validate(l).exists(_.message.contains(fragment)),
        s"expected '$fragment', got ${c.validate(l).map(_.message)}"
      )
    val ac = benchmarkPairs.pairs.head
    bad(
      benchmarkPairs.copy(pairs = Vector(BenchmarkPair(ac.researcherB, ac.researcherA))),
      "pairs[0]"
    )
    bad(benchmarkPairs.copy(pairs = Vector(BenchmarkPair(rA, rA))), "pairs[0]")
    bad(benchmarkPairs.copy(pairs = benchmarkPairs.pairs.reverse), "strictly ascending")
    bad(benchmarkPairs.copy(pairs = Vector(ac, ac)), "strictly ascending")
    bad(benchmarkPairs.copy(pairs = Vector.empty), "pairs")
    bad(benchmarkPairs.copy(pairs = Vector(BenchmarkPair(rA, Fixtures.workId))), "researcherB")
    bad(benchmarkPairs.copy(listVersion = "has space"), "listVersion")
    bad(benchmarkPairs.copy(listVersion = "benchmark-pairs-synthetic.v2"), "must equal listVersion")
    bad(
      benchmarkPairs.copy(provenance = benchmarkPairs.provenance.copy(modelVersion = Some("m"))),
      "not model output"
    )
    bad(benchmarkPairs.copy(schemaVersion = "scholarchain.benchmark-pairs.v2"), "benchmark-pairs")
    assertEquals(
      BenchmarkPairList.digest(benchmarkPairs),
      Canonical.sha256HexUtf8(BenchmarkPairsGolden),
      "the digest is the SHA-256 of the frozen compact encoding"
    )
  }

  // ---------------------------------------------------------------- golden fragments

  private val W1 = """{"startYear":2015,"endYear":2019}"""
  private val W2 = """{"startYear":2020,"endYear":2024}"""
  private val L =
    s"""{"groupingRunId":"$GRun","decisionRunId":"$DRun","inputRunId":"$IRun","codeVersion":"89abcdef0123456789abcdef0123456789abcdef","configDigest":"$ADigest","groupingPolicyVersion":"er-grouping.v1","matchPolicyVersion":"er-policy-1.0.0","identityVersion":"v1","groupingEvaluation":"unevaluated"}"""
  private def prov(subject: String) =
    s"""{"sourceDataset":"scholarchain-derived","sourceRecordId":"$subject","snapshot":"dblp-2026-10-03","runId":"$ARun","sourceSchemaVersion":"scholarchain.publication-team.v1","policyVersion":"graph-policy-1.0.0","modelVersion":null,"promptVersion":null}"""
  private val Unknown =
    """{"kind":"unknown","value":null,"unknownReason":"no-supporting-decision"}"""
  private val IdA =
    """{"status":"resolved","anchorNamespace":"orcid","memberCount":2,"collisionCodes":["grouping.anchor-collision.orcid"],"lowestDecisionConfidence":{"kind":"measured","value":0.97,"unknownReason":null}}"""
  private val IdB =
    s"""{"status":"singleton","anchorNamespace":"orcid","memberCount":1,"collisionCodes":[],"lowestDecisionConfidence":$Unknown}"""
  private val IdC =
    s"""{"status":"singleton-t1-anchored","anchorNamespace":"dblp-pid","memberCount":1,"collisionCodes":[],"lowestDecisionConfidence":$Unknown}"""
  private val IdE =
    s"""{"status":"singleton","anchorNamespace":"dblp-pid","memberCount":1,"collisionCodes":[],"lowestDecisionConfidence":$Unknown}"""

  val MetricsA1Golden: String =
    s"""{"schemaVersion":"scholarchain.researcher-metrics.v2","researcherId":"$RA","window":$W1,"publicationCount":1,"distinctCoauthorCount":1,"totalCoauthorOccurrences":2,"unresolvedCoauthorOccurrences":1,"worksWithUnresolvedCoauthors":1,"averageCoauthorsPerPaper":2,"maximumCoauthorsOnOnePaper":2,"maximumTeamWorkIds":["$G1"],"weightedDegree":1,"identity":$IdA,"lineage":$L,"provenance":${prov(
        RA
      )}}"""

  val MetricsE2Golden: String =
    s"""{"schemaVersion":"scholarchain.researcher-metrics.v2","researcherId":"$RE","window":$W2,"publicationCount":1,"distinctCoauthorCount":0,"totalCoauthorOccurrences":0,"unresolvedCoauthorOccurrences":0,"worksWithUnresolvedCoauthors":0,"averageCoauthorsPerPaper":0,"maximumCoauthorsOnOnePaper":0,"maximumTeamWorkIds":["$G4"],"weightedDegree":0,"identity":$IdE,"lineage":$L,"provenance":${prov(
        RE
      )}}"""

  val EdgeCDGolden: String =
    s"""{"schemaVersion":"scholarchain.coauthor-edge.v2","edgeId":"$ECD","researcherA":"$CDA","researcherB":"$CDB","window":$W2,"jointPublicationCount":2,"firstCollaborationYear":2021,"lastCollaborationYear":2023,"publicationsByYear":[{"year":2021,"count":1},{"year":2023,"count":1}],"supportingWorkIds":["$G3","$G6"],"lineage":$L,"provenance":${prov(
        ECD
      )}}"""

  val ComponentGolden: String =
    s"""{"schemaVersion":"scholarchain.component-membership.v1","researcherId":"$RA","window":$W1,"componentId":"$CW1","componentSize":3,"lineage":$L,"provenance":${prov(
        RA
      )}}"""

  val CommunityGolden: String =
    s"""{"schemaVersion":"scholarchain.community-membership.v1","researcherId":"$RB","window":$W1,"communityId":"$CMAB","communitySize":2,"componentId":"$CW1","algorithm":"label-propagation","parameters":{"maxIterations":"20"},"lineage":$L,"provenance":${prov(
        RB
      )}}"""

  val BridgeGolden: String =
    s"""{"schemaVersion":"scholarchain.bridge-measure.v1","researcherId":"$RB","window":$W1,"communityId":"$CMAB","measure":"cross-community-degree","parameters":{"maxIterations":"10","randomSeed":"7","sampleSize":"64"},"score":1,"degree":2,"crossCommunityDegree":1,"communitiesReached":1,"participationCoefficient":0.5,"lineage":$L,"provenance":${prov(
        RB
      )}}"""

  val CorenessGolden: String =
    s"""{"schemaVersion":"scholarchain.coreness.v1","researcherId":"$RB","window":$W1,"coreness":1,"degree":2,"lineage":$L,"provenance":${prov(
        RB
      )}}"""

  val PathGolden: String =
    s"""{"schemaVersion":"scholarchain.evidence-path.v1","pathId":"$PAC","window":$W1,"sourceResearcherId":"$RA","targetResearcherId":"$RC","ordinal":0,"hopCount":2,"nodeIds":["$RA","$RB","$RC"],"hops":[{"edgeId":"$EAB","evidenceWorkIds":["$G1"]},{"edgeId":"$EBC","evidenceWorkIds":["$G2"]}],"nodeIdentities":[$IdA,$IdB,$IdC],"bounds":{"maxHops":3,"maxPathsPerPair":2,"maxEvidenceWorksPerHop":2},"lineage":$L,"provenance":${prov(
        PAC
      )}}"""

  val WindowGolden: String =
    s"""{"schemaVersion":"scholarchain.analysis-window.v1","window":$W1,"ordinal":0,"countedWorkCount":2,"countedWorksWithoutYear":1,"researcherCount":3,"edgeCount":2,"componentCount":1,"communityCount":2,"unresolvedCoauthorOccurrences":1,"lineage":$L,"provenance":${prov(
        "window:2015-2019"
      )}}"""

  val SummaryGolden: String =
    s"""{"schemaVersion":"scholarchain.semantic-summary-input.v1","inputId":"$SB","documentType":"researcher-profile","subjectId":"$RB","window":$W1,"facts":[{"name":"publicationCount","value":"2","evidence":[{"kind":"researcher-metrics","id":"$RB"}]},{"name":"community","value":"$CMAB","evidence":[{"kind":"community","id":"$CMAB"}]}],"identity":$IdB,"lineage":$L,"provenance":${prov(
        SB
      )}}"""

  /** A literal v2 part manifest of an analysis export (counts and hashes are synthetic). */
  lazy val literalParts: PartManifestV2 = PartManifestV2(
    SchemaVersions.PartManifestV2,
    analysisRunId,
    Vector(
      PartFileV2(
        PartManifest.RunManifestFile,
        SchemaVersions.RunManifest,
        PartFormat.Jsonl,
        1,
        RowCountSource.LfCount,
        500,
        "a" * 64
      ),
      PartFileV2(
        "edges/part-00000.parquet",
        SchemaVersions.CoauthorEdgeV2,
        PartFormat.Parquet,
        3,
        RowCountSource.ParquetFooter,
        4096,
        "b" * 64
      ),
      PartFileV2(
        "metrics/part-00000.jsonl",
        SchemaVersions.ResearcherMetricsV2,
        PartFormat.Jsonl,
        6,
        RowCountSource.LfCount,
        9000,
        "c" * 64
      )
    )
  )

  val PartsGolden: String =
    s"""{"schemaVersion":"scholarchain.part-manifest.v2","runId":"$ARun","files":[{"path":"_run-manifest.json","recordSchemaVersion":"scholarchain.run-manifest.v1","format":"jsonl","rowCount":1,"rowCountSource":"lf-count","byteCount":500,"sha256":"${"a" * 64}"},{"path":"edges/part-00000.parquet","recordSchemaVersion":"scholarchain.coauthor-edge.v2","format":"parquet","rowCount":3,"rowCountSource":"parquet-footer","byteCount":4096,"sha256":"${"b" * 64}"},{"path":"metrics/part-00000.jsonl","recordSchemaVersion":"scholarchain.researcher-metrics.v2","format":"jsonl","rowCount":6,"rowCountSource":"lf-count","byteCount":9000,"sha256":"${"c" * 64}"}]}"""

  lazy val literalLoad: StoreLoadManifest = Fixtures.ok(
    StoreLoadManifest.build(
      TargetStore.S3,
      Right(literalParts),
      "d" * 64,
      Map("edges/part-00000.parquet" -> "coauthor-edges")
    )
  )

  val LoadGolden: String =
    s"""{"schemaVersion":"scholarchain.store-load-manifest.v1","loadId":"$LOAD","targetStore":"s3","sourceRunId":"$ARun","partManifestSha256":"${"d" * 64}","items":[{"path":"edges/part-00000.parquet","recordSchemaVersion":"scholarchain.coauthor-edge.v2","format":"parquet","rowCount":3,"byteCount":4096,"sha256":"${"b" * 64}","targetRole":"coauthor-edges"}]}"""

  lazy val BenchmarkPairsGolden: String =
    s"""{"schemaVersion":"scholarchain.benchmark-pairs.v1","listVersion":"benchmark-pairs-synthetic.v1","pairs":[{"researcherA":"$RA","researcherB":"$RC"},{"researcherA":"$RC","researcherB":"$RD"}],"provenance":{"sourceDataset":"scholarchain-derived","sourceRecordId":"benchmark-pairs-synthetic.v1","snapshot":"dblp-2026-10-03","runId":"$GRun","sourceSchemaVersion":"scholarchain.researcher-assignment.v1","policyVersion":null,"modelVersion":null,"promptVersion":null}}"""

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

  frozen("ResearcherMetricsV2 (resolved, with unresolved coauthor)", metricA1, MetricsA1Golden)
  frozen("ResearcherMetricsV2 (singleton, no coauthor)", metricE2, MetricsE2Golden)
  frozen("CoauthorEdgeV2", edgeCD, EdgeCDGolden)
  frozen("ComponentMembership", components.head, ComponentGolden)
  frozen(
    "CommunityMembership",
    communities.find(c => c.researcherId == rB && c.window == w1).get,
    CommunityGolden
  )
  frozen("BridgeMeasure", bridgeB1, BridgeGolden)
  frozen("Coreness", corenessB1, CorenessGolden)
  frozen("EvidencePath", pathAC, PathGolden)
  frozen("AnalysisWindow", window1, WindowGolden)
  frozen("SemanticSummaryInput", profileB1, SummaryGolden)
  frozen("PartManifestV2", literalParts, PartsGolden)
  frozen("StoreLoadManifest", literalLoad, LoadGolden)
  frozen("BenchmarkPairList", benchmarkPairs, BenchmarkPairsGolden)
}
