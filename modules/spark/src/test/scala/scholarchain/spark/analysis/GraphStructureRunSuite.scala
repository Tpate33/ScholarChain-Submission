package scholarchain.spark.analysis

import java.nio.file.{Files, Path}

import scala.collection.immutable.SortedMap

import org.apache.spark.sql.{Row, SparkSession}
import org.apache.spark.sql.types.StructType

import scholarchain.core.*
import scholarchain.core.config.{AnalysisRunSettings, BridgeConfig, CommunityConfig}
import scholarchain.spark.graph.{Checkpointing, StructureWindowSummary}
import scholarchain.spark.input.{ReaderLimits, VerifiedArchiveReader, VerifiedInput}
import scholarchain.spark.output.*
import scholarchain.spark.testkit.{LocalSpark, MetricsFixtures, RunFixtures}
import scholarchain.spark.testkit.MetricsFixtures.*

/**
 * The published structure datasets of one collaboration run (SP-P6-03 step 2A) on the
 * hand-checkable [[MetricsFixtures]] chain: component memberships, community memberships, bridge
 * measures and coreness pass `AnalysisValidation.checkComponents`, `checkCommunities`,
 * `checkBridges`, `checkCoreness` and `checkRunChain` (all six datasets), equal the hand-derived
 * values below, and counter-tests show that a changed coreness, cross-community degree, component
 * size or community component is detected; unsupported settings are refused before anything is
 * published. DEV fixture, unevaluated. The driver collects at most nine records per dataset (test
 * only).
 *
 * Hand derivation (edges from the fixture comment): window 2015-2019 has researchers A, B, C, E, F
 * and edges A-B, A-C (a path B-A-C); window 2020-2024 has A, B, C, D, all six pairs (a K4).
 *   - components: 2015-2019 {A,B,C} (size 3), {E}, {F}: 3 components, largest 3 of 5, 2 singletons;
 *     2020-2024 one component of 4;
 *   - communities: 2020-2024 every vertex sees all four labels once and takes the smallest in the
 *     first iteration, the second changes nothing (converged at 2), one community of 4. 2015-2019:
 *     the path always ends in one community {A,B,C} whatever the researcher-ID order: if A has the
 *     smallest ID all three take it in iteration 1 (converged at 2); if B (or C) has it, A and B
 *     take it in iteration 1 and C (or B) takes it in iteration 2 by the tie rule (converged at 3).
 *     E and F stay alone. So every cross-community degree is 0, every participation coefficient
 *     0.0;
 *   - coreness: 2015-2019 A, B, C 1 (degrees 2, 1, 1), E, F 0; 2020-2024 all 3 (degree 3).
 */
class GraphStructureRunSuite extends munit.FunSuite {

  private var tmp: Path = null
  private var spark: SparkSession = null
  private var written: RunFixtures.Written = null
  private var input: VerifiedInput = null

  private val limits = ReaderLimits(1000, 6, 10000, 1L << 20, 1L << 16)
  private val settings = RunFixtures.settings
  private val code = "76543210fedcba9876543210fedcba9876543210"
  private val w1 = RunFixtures.window1
  private val w2 = RunFixtures.window2

  private def cfg(s: AnalysisRunSettings = settings, checkpoints: String = "checkpoints") =
    AnalysisJobConfig(
      s,
      code,
      1,
      SortedMap.empty,
      1,
      Checkpointing(tmp.resolve(checkpoints).toString, 2)
    )

  override def beforeAll(): Unit = {
    tmp = Files.createTempDirectory("scholarchain-structure-run-")
    spark = LocalSpark.start("scholarchain-structure-run-test", tmp.resolve("spark"))
    written = RunFixtures.write(tmp.resolve("fixture"), MetricsFixtures.spec())
    input =
      VerifiedArchiveReader.read(written.runs, limits).fold(r => fail(r.mkString("\n")), identity)
  }

  override def afterAll(): Unit =
    try if (spark != null) spark.stop()
    finally LocalSpark.deleteTree(tmp)

  private def pairs = RunFixtures
    .benchmarkPairs(written.groupingRunId)
    .copy(pairs = Vector(RunFixtures.sorted(rA, rD) match {
      case Vector(a, b) => BenchmarkPair(a, b)
      case other => fail(s"pair $other")
    }))

  private def run(out: String, c: AnalysisJobConfig) =
    AnalysisRun.runCollaboration(spark, c, input, pairs, Files.createDirectories(tmp.resolve(out)))

  private lazy val result: CollaborationRunResult =
    run("out", cfg()).fold(e => fail(e.mkString("\n")), identity)

  private def read[T](label: String, schema: StructType, from: Row => Either[String, T]) =
    spark.read
      .schema(schema)
      .parquet(result.published.partsOf(label).map(_.toUri.toString)*)
      .collect()
      .toVector
      .map(r => from(r).fold(fail(_), identity))

  private lazy val components = read(
    SchemaVersions.ComponentMembership,
    ComponentMembershipRows.schema,
    ComponentMembershipRows.fromRow
  )
  private lazy val communities = read(
    SchemaVersions.CommunityMembership,
    CommunityMembershipRows.schema,
    CommunityMembershipRows.fromRow
  )
  private lazy val bridges =
    read(SchemaVersions.BridgeMeasure, BridgeMeasureRows.schema, BridgeMeasureRows.fromRow)
  private lazy val coreness =
    read(SchemaVersions.Coreness, CorenessRows.schema, CorenessRows.fromRow)
  private lazy val metrics = read(
    SchemaVersions.ResearcherMetricsV2,
    ResearcherMetricsRows.schema,
    ResearcherMetricsRows.fromRow
  )
  private lazy val edges =
    read(SchemaVersions.CoauthorEdgeV2, CoauthorEdgeRows.schema, CoauthorEdgeRows.fromRow)

  private def outputs = AnalysisOutputs(
    metrics,
    edges,
    components,
    communities,
    bridges,
    coreness,
    Vector.empty,
    Vector.empty,
    Vector.empty
  )

  private def at[T](xs: Vector[T])(w: TimeWindow, r: String)(
      window: T => TimeWindow,
      id: T => String
  ): T = xs.find(x => window(x) == w && id(x) == r).getOrElse(fail(s"no record of $r in $w"))

  private val abc = RunFixtures.sorted(rA, rB, rC)

  test("every structure row layout has the record's JSON keys, in record order") {
    def keys(v: ujson.Value) = v match {
      case o: ujson.Obj => o.value.keys.toVector
      case _ => Vector.empty
    }
    assertEquals(
      ComponentMembershipRows.schema.fieldNames.toVector,
      keys(ujson.read(Json.write(components.head)))
    )
    assertEquals(
      CommunityMembershipRows.schema.fieldNames.toVector,
      keys(ujson.read(Json.write(communities.head)))
    )
    assertEquals(
      BridgeMeasureRows.schema.fieldNames.toVector,
      keys(ujson.read(Json.write(bridges.head)))
    )
    assertEquals(
      CorenessRows.schema.fieldNames.toVector,
      keys(ujson.read(Json.write(coreness.head)))
    )
    // The parameters survive the array<struct<key,value>> layout exactly.
    assertEquals(communities.head.parameters, settings.communityParameters)
    assertEquals(bridges.head.parameters, settings.bridgeParameters)
  }

  test("components: hand-derived memberships, sizes and IDs") {
    assertEquals(components.size, 9)
    val p = settings.graphPolicyVersion
    def id(w: TimeWindow, members: String*) = RunFixtures.ok(ComponentId.of(w, p, members.toVector))
    val expected = Map(
      (w1, rA) -> (id(w1, rA, rB, rC), 3L),
      (w1, rB) -> (id(w1, rA, rB, rC), 3L),
      (w1, rC) -> (id(w1, rA, rB, rC), 3L),
      (w1, rE) -> (id(w1, rE), 1L),
      (w1, rF) -> (id(w1, rF), 1L),
      (w2, rA) -> (id(w2, rA, rB, rC, rD), 4L),
      (w2, rB) -> (id(w2, rA, rB, rC, rD), 4L),
      (w2, rC) -> (id(w2, rA, rB, rC, rD), 4L),
      (w2, rD) -> (id(w2, rA, rB, rC, rD), 4L)
    )
    assertEquals(
      components.map(c => (c.window, c.researcherId) -> (c.componentId, c.componentSize)).toMap,
      expected
    )
  }

  test("communities: one community per component here, configured algorithm and parameters") {
    assertEquals(communities.size, 9)
    def id(w: TimeWindow, members: Vector[String]) = RunFixtures.ok(
      CommunityId.of(
        w,
        settings.graphPolicyVersion,
        "label-propagation",
        settings.communityParameters,
        members
      )
    )
    val got = communities.map(c => (c.window, c.researcherId) -> (c.communityId, c.communitySize))
    assertEquals(
      got.toMap,
      Map(
        (w1, rA) -> (id(w1, abc), 3L),
        (w1, rB) -> (id(w1, abc), 3L),
        (w1, rC) -> (id(w1, abc), 3L),
        (w1, rE) -> (id(w1, Vector(rE)), 1L),
        (w1, rF) -> (id(w1, Vector(rF)), 1L),
        (w2, rA) -> (id(w2, Vector(rA, rB, rC, rD)), 4L),
        (w2, rB) -> (id(w2, Vector(rA, rB, rC, rD)), 4L),
        (w2, rC) -> (id(w2, Vector(rA, rB, rC, rD)), 4L),
        (w2, rD) -> (id(w2, Vector(rA, rB, rC, rD)), 4L)
      )
    )
    communities.foreach { c =>
      assertEquals(c.algorithm, "label-propagation")
      assertEquals(c.parameters, SortedMap("maxIterations" -> "20"))
      assertEquals(
        c.componentId,
        at(components)(c.window, c.researcherId)(_.window, _.researcherId).componentId
      )
    }
  }

  test("label-propagation outcome per window, recorded in the run manifest") {
    // 2015-2019: converged at 2 when A has the smallest ID of A, B, C, else at 3.
    val w1Iterations = if (abc.head == rA) 2 else 3
    assertEquals(
      result.structureSummaries.map(x =>
        (x.labelPropagation.window, x.labelPropagation.iterations, x.labelPropagation.converged)
      ),
      Vector((w1, w1Iterations, true), (w2, 2, true))
    )
    val params = result.published.manifest.parameters
    assertEquals(params.get("community.converged.2015-2019"), Some("true"))
    assertEquals(params.get("community.iterations.2015-2019"), Some(w1Iterations.toString))
    assertEquals(params.get("community.converged.2020-2024"), Some("true"))
    assertEquals(params.get("community.iterations.2020-2024"), Some("2"))
    assertEquals(params.get(AnalysisRun.OutputsParameter), Some(AnalysisRun.Outputs))
  }

  test("bridge measures: cross-community degree 0 everywhere, degree = C_r, configured measure") {
    assertEquals(bridges.size, 9)
    bridges.foreach { b =>
      val m = at(metrics)(b.window, b.researcherId)(_.window, _.researcherId)
      assertEquals(b.degree, m.distinctCoauthorCount, b.researcherId)
      assertEquals(
        (b.crossCommunityDegree, b.communitiesReached, b.score, b.participationCoefficient),
        (0L, 0L, 0.0, 0.0)
      )
      assertEquals(b.measure, "cross-community-degree")
      assertEquals(b.parameters, settings.bridgeParameters)
      assertEquals(
        b.communityId,
        at(communities)(b.window, b.researcherId)(_.window, _.researcherId).communityId
      )
    }
  }

  test("coreness: hand-derived values and degrees") {
    assertEquals(
      coreness.map(c => (c.window, c.researcherId) -> (c.coreness, c.degree)).toMap,
      Map(
        (w1, rA) -> (1L, 2L),
        (w1, rB) -> (1L, 1L),
        (w1, rC) -> (1L, 1L),
        (w1, rE) -> (0L, 0L),
        (w1, rF) -> (0L, 0L),
        (w2, rA) -> (3L, 3L),
        (w2, rB) -> (3L, 3L),
        (w2, rC) -> (3L, 3L),
        (w2, rD) -> (3L, 3L)
      )
    )
  }

  test("per-window structure totals equal the hand-derived totals") {
    def totals(s: StructureWindowSummary) = (
      s.researchers,
      s.edges,
      s.components,
      s.largestComponent,
      s.singletonComponents,
      s.communities,
      s.largestCommunity,
      s.crossCommunityEdges,
      s.maxCoreness,
      s.corenessHistogram
    )
    assertEquals(
      result.structureSummaries.map(totals),
      Vector(
        (5L, 2L, 3L, 3L, 2L, 3L, 3L, 0L, 1L, Vector((0L, 2L), (1L, 3L))),
        (4L, 6L, 1L, 4L, 0L, 1L, 4L, 0L, 3L, Vector((3L, 4L)))
      )
    )
    assertEquals(result.structureSummaries.map(_.largestComponentFraction), Vector(0.6, 1.0))
    assertEquals(
      (result.componentCount, result.communityCount, result.bridgeCount, result.corenessCount),
      (9L, 9L, 9L, 9L)
    )
    assert(result.checkpoints > 0)
  }

  test("the structure records pass AnalysisValidation and the run chain over all six datasets") {
    val g = input.graphInput
    assertEquals(AnalysisValidation.checkComponents(components, g, settings), Vector.empty)
    assertEquals(
      AnalysisValidation.checkCommunities(communities, components, settings),
      Vector.empty
    )
    assertEquals(AnalysisValidation.checkBridges(bridges, communities, g, settings), Vector.empty)
    assertEquals(AnalysisValidation.checkCoreness(coreness, g, settings), Vector.empty)
    assertEquals(
      AnalysisValidation.checkRunChain(
        result.published.manifest,
        input.grouping.manifest,
        outputs,
        settings,
        pairs
      ),
      Vector.empty
    )
    (components.map(_.provenance) ++ coreness.map(_.provenance)).foreach { pr =>
      assertEquals(pr.runId, result.runId)
      assertEquals(pr.policyVersion, Some(settings.graphPolicyVersion))
    }
  }

  test("counter-tests: changed coreness, cross degree, component size or component are detected") {
    val g = input.graphInput
    val a2 = at(coreness)(w2, rA)(_.window, _.researcherId)
    val k = AnalysisValidation.checkCoreness(
      coreness.map(c => if (c == a2) c.copy(coreness = 2L) else c),
      g,
      settings
    )
    assert(k.exists(_.toString.contains("coreness")), k)
    val b = at(bridges)(w2, rA)(_.window, _.researcherId)
    val cross = AnalysisValidation.checkBridges(
      bridges.map(x =>
        if (x == b) x.copy(crossCommunityDegree = 1L, communitiesReached = 1L, score = 1.0) else x
      ),
      communities,
      g,
      settings
    )
    assert(cross.exists(_.toString.contains("crossCommunityDegree")), cross)
    val c = at(components)(w1, rE)(_.window, _.researcherId)
    val size = AnalysisValidation.checkComponents(
      components.map(x => if (x == c) x.copy(componentSize = 2L) else x),
      g,
      settings
    )
    assert(size.exists(_.toString.contains("componentSize")), size)
    val m = at(communities)(w1, rE)(_.window, _.researcherId)
    val other = at(components)(w1, rF)(_.window, _.researcherId).componentId
    val moved = AnalysisValidation.checkCommunities(
      communities.map(x => if (x == m) x.copy(componentId = other) else x),
      components,
      settings
    )
    assert(moved.exists(_.toString.contains("componentId")), moved)
  }

  private lazy val structures = read(
    SchemaVersions.WindowStructure,
    WindowStructureRows.schema,
    WindowStructureRows.fromRow
  ).sortBy(_.ordinal)

  /**
   * SP-P6-03 step 2B: the published `window-structure.v1` records, from the hand totals above
   * (fraction 3 / 5 = 0.6 and 4 / 4 = 1.0; cross-community edges 0; label propagation converged at
   * the iteration derived in the outcome test).
   */
  test("window structures: the hand-derived window-structure.v1 records, one per window") {
    val w1Iterations = if (abc.head == rA) 2 else 3
    assertEquals(
      structures.map(s =>
        (
          s.window,
          s.ordinal,
          s.researcherCount,
          s.edgeCount,
          s.componentCount,
          s.largestComponentSize,
          s.largestComponentFraction,
          s.singletonCount,
          s.communityCount,
          s.largestCommunitySize,
          s.crossCommunityEdgeCount,
          s.communityAlgorithm,
          s.communityIterations,
          s.communityConverged,
          s.maxCoreness
        )
      ),
      Vector(
        (w1, 0, 5L, 2L, 3L, 3L, 0.6, 2L, 3L, 3L, 0L, "label-propagation", w1Iterations, true, 1L),
        (w2, 1, 4L, 6L, 1L, 4L, 1.0, 0L, 1L, 4L, 0L, "label-propagation", 2, true, 3L)
      )
    )
    assertEquals(result.windowStructures, structures)
    structures.foreach { s =>
      assertEquals(s.provenance.sourceRecordId, AnalysisWindow.subjectOf(s.window))
      assertEquals(s.provenance.runId, result.runId)
    }
  }

  test("window structures pass checkWindowStructures and the run chain; counter-tests detected") {
    val outs = outputs.copy(windowStructures = structures)
    def check(xs: Vector[WindowStructure], m: RunManifest = result.published.manifest) =
      StructureValidation.checkWindowStructures(xs, outs, settings, m)
    assertEquals(check(structures), Vector.empty)
    assertEquals(
      AnalysisValidation.checkRunChain(
        result.published.manifest,
        input.grouping.manifest,
        outs,
        settings,
        pairs
      ),
      Vector.empty
    )
    val first = structures.head
    def with_(f: WindowStructure => WindowStructure) =
      structures.map(s => if (s == first) f(s) else s)
    assert(check(with_(_.copy(edgeCount = 3L))).exists(_.toString.contains("edgeCount")))
    assert(
      check(with_(_.copy(singletonCount = 1L))).exists(_.toString.contains("singletonCount"))
    )
    // An outcome that differs from the run manifest's computed parameters is detected.
    val k = AnalysisValidation.AnalysisManifestKeys
    val m = result.published.manifest
    val flipped = m.copy(parameters = m.parameters.updated(k.communityConverged(w1), "false"))
    assert(check(structures, flipped).exists(_.toString.contains(k.communityConverged(w1))))
    assertEquals(AnalysisRun.convergedKey(w1), "community.converged.2015-2019")
    assertEquals(AnalysisRun.iterationsKey(w2), k.communityIterations(w2))
  }

  test("unsupported structure settings are refused before anything is published") {
    def refused(out: String, c: AnalysisJobConfig, what: String) = {
      val r = run(out, c)
      assert(r.left.exists(_.exists(_.contains(what))), r)
      assertEquals(Files.list(tmp.resolve(out)).count(), 0L, s"$out: nothing is published")
    }
    def with_(
        f: scholarchain.core.config.ScholarChainConfig => scholarchain.core.config.ScholarChainConfig
    ) =
      RunFixtures.ok(AnalysisRunSettings.from(f(RunFixtures.config)))
    refused(
      "out-louvain",
      cfg(with_(c => c.copy(community = CommunityConfig("louvain", 20, None)))),
      "only label-propagation is implemented"
    )
    refused(
      "out-betweenness",
      cfg(with_(c => c.copy(bridge = BridgeConfig("betweenness", 10, 1L, 1)))),
      "only cross-community-degree is implemented"
    )
    refused(
      "out-seed",
      cfg(with_(c => c.copy(community = CommunityConfig("label-propagation", 20, Some(7L))))),
      "community.randomSeed is set"
    )
    refused(
      "out-zero",
      cfg(with_(c => c.copy(community = CommunityConfig("label-propagation", 0, None)))),
      "community.maxIterations 0 must be an integer >= 1"
    )
    refused("out-overlap", cfg(checkpoints = "out-overlap/checkpoints"), "overlaps the output root")
    refused(
      "out-reserved",
      cfg().copy(extraParameters = SortedMap("community.note" -> "x")),
      "reserved prefix community."
    )
  }
}
