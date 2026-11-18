package scholarchain.spark.analysis

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.immutable.SortedMap

import org.apache.spark.sql.{Row, SparkSession}
import org.apache.spark.sql.types.StructType

import scholarchain.core.*
import scholarchain.spark.graph.{Checkpointing, PathJob}
import scholarchain.spark.input.{ReaderLimits, VerifiedArchiveReader, VerifiedInput}
import scholarchain.spark.output.*
import scholarchain.spark.testkit.{LocalSpark, PathFixtures, RunFixtures}
import scholarchain.spark.testkit.PathFixtures.*

/**
 * SP-P6-03 step 2C on the hand-checkable [[PathFixtures]] chain (its object comment holds the hand
 * derivation): the published evidence paths, path-length estimates, analysis windows, window
 * comparisons, temporal changes, semantic-summary and entity-summary inputs and load manifests of
 * one run equal the hand-derived values and pass `AnalysisValidation.checkPaths`, `checkWindows`,
 * `checkSummaryInputs`, `checkRunChain` (every dataset), `StructureValidation.
 * checkPathLengthEstimates`, `checkTemporal`, `checkEntitySummaries` and `checkLoadManifests`;
 * counter-tests show that a changed record of each dataset is detected. DEV fixture, unevaluated.
 * The driver collects at most a few dozen records per dataset (test only).
 *
 * Path-length configuration: sample size 4, seed 7, minimum component size 3 (set in the fixture
 * configuration; the run records them in its config digest and the outputs parameter).
 */
class PathSummaryRunSuite extends munit.FunSuite {

  private var tmp: Path = null
  private var spark: SparkSession = null
  private var written: RunFixtures.Written = null
  private var input: VerifiedInput = null

  private val limits = ReaderLimits(1000, 6, 10000, 1L << 20, 1L << 16)
  private val settings = PathFixtures.settings
  private val code = "1234567890abcdef1234567890abcdef12345678"
  private val w1 = RunFixtures.window1
  private val w2 = RunFixtures.window2
  private val policy = settings.graphPolicyVersion

  override def beforeAll(): Unit = {
    tmp = Files.createTempDirectory("scholarchain-path-run-")
    spark = LocalSpark.start("scholarchain-path-run-test", tmp.resolve("spark"))
    written = RunFixtures.write(tmp.resolve("fixture"), PathFixtures.spec)
    input =
      VerifiedArchiveReader.read(written.runs, limits).fold(r => fail(r.mkString("\n")), identity)
  }

  override def afterAll(): Unit =
    try if (spark != null) spark.stop()
    finally LocalSpark.deleteTree(tmp)

  private def pairList = PathFixtures.pairs(written.groupingRunId)

  private def cfg = AnalysisJobConfig(
    settings,
    code,
    1,
    SortedMap.empty,
    1,
    Checkpointing(tmp.resolve("checkpoints").toString, 2)
  )

  private lazy val out = Files.createDirectories(tmp.resolve("out"))

  private lazy val result: CollaborationRunResult =
    AnalysisRun
      .runCollaboration(spark, cfg, input, pairList, out)
      .fold(e => fail(e.mkString("\n")), identity)

  private def read[T](label: String, schema: StructType, from: Row => Either[String, T]) =
    spark.read
      .schema(schema)
      .parquet(result.published.parquetPartsOf(label).map(_.toUri.toString)*)
      .collect()
      .toVector
      .map(r => from(r).fold(fail(_), identity))

  private lazy val paths =
    read(SchemaVersions.EvidencePath, EvidencePathRows.schema, EvidencePathRows.fromRow)
  private lazy val estimates = read(
    SchemaVersions.PathLengthEstimate,
    PathLengthEstimateRows.schema,
    PathLengthEstimateRows.fromRow
  )
  private lazy val windows =
    read(SchemaVersions.AnalysisWindow, AnalysisWindowRows.schema, AnalysisWindowRows.fromRow)
      .sortBy(_.ordinal)
  private lazy val comparisons = read(
    SchemaVersions.WindowComparison,
    WindowComparisonRows.schema,
    WindowComparisonRows.fromRow
  )
  private lazy val changes = read(
    SchemaVersions.ResearcherTemporalChange,
    TemporalChangeRows.schema,
    TemporalChangeRows.fromRow
  )
  private lazy val summaries =
    read(SchemaVersions.SemanticSummaryInput, SummaryInputRows.schema, SummaryInputRows.fromRow)
  private lazy val entities = read(
    SchemaVersions.EntitySummaryInput,
    EntitySummaryInputRows.schema,
    EntitySummaryInputRows.fromRow
  )
  private lazy val metrics = read(
    SchemaVersions.ResearcherMetricsV2,
    ResearcherMetricsRows.schema,
    ResearcherMetricsRows.fromRow
  )
  private lazy val edges =
    read(SchemaVersions.CoauthorEdgeV2, CoauthorEdgeRows.schema, CoauthorEdgeRows.fromRow)
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
  private lazy val structures =
    read(SchemaVersions.WindowStructure, WindowStructureRows.schema, WindowStructureRows.fromRow)
      .sortBy(_.ordinal)
  private lazy val nodes =
    read(SchemaVersions.GraphNodeV2, GraphNodeRows.schema, GraphNodeRows.fromRow)
  private lazy val graphEdges =
    read(SchemaVersions.GraphEdgeV2, GraphEdgeRows.schema, GraphEdgeRows.fromRow)

  private def outputs = AnalysisOutputs(
    metrics,
    edges,
    components,
    communities,
    bridges,
    coreness,
    paths,
    windows,
    summaries,
    nodes = nodes,
    graphEdges = graphEdges,
    windowStructures = structures,
    pathLengthEstimates = estimates,
    windowComparisons = comparisons,
    temporalChanges = changes,
    entitySummaryInputs = entities
  )

  private def oriented(nodes: Vector[String]): Vector[String] =
    if (Canonical.compareUtf8(nodes.head, nodes.last) < 0) nodes else nodes.reverse

  private def firstK(k: Int, candidates: Vector[String]*): Vector[Vector[String]] =
    candidates.toVector.map(oriented).sortWith(PathJob.pathLess).take(k)

  private def stored(w: TimeWindow, a: String, b: String): Vector[Vector[String]] = {
    val p = pair(a, b)
    paths
      .filter(x =>
        x.window == w && x.sourceResearcherId == p.researcherA && x.targetResearcherId == p.researcherB
      )
      .sortBy(_.ordinal)
      .map(_.nodeIds)
  }

  private def edgeId(w: TimeWindow, a: String, b: String) =
    RunFixtures.ok(CanonicalPair.of(a, b).flatMap(p => CoauthorEdgeId.of(p, w, policy)))

  test("every step-2C row layout has the record's JSON keys, in record order") {
    def keys(v: ujson.Value) = v match {
      case o: ujson.Obj => o.value.keys.toVector
      case _ => Vector.empty
    }
    def same[T](schema: StructType, x: T)(using RecordContract[T]) =
      assertEquals(schema.fieldNames.toVector, keys(ujson.read(Json.write(x))), schema.simpleString)
    same(EvidencePathRows.schema, paths.head)
    same(PathLengthEstimateRows.schema, estimates.head)
    same(AnalysisWindowRows.schema, windows.head)
    same(WindowComparisonRows.schema, comparisons.head)
    same(TemporalChangeRows.schema, changes.head)
    same(SummaryInputRows.schema, summaries.head)
    same(EntitySummaryInputRows.schema, entities.head)
    // Optional fields survive the nullable columns exactly (absent and present).
    assert(changes.exists(_.from.isEmpty) && changes.exists(_.to.isEmpty))
    assert(summaries.exists(_.identity.isEmpty) && summaries.exists(_.identity.isDefined))
  }

  test("stored paths: the hand-derived tie order, truncation, bound, components and absences") {
    assertEquals(paths.size, 6)
    assertEquals(result.step2C.pathCount, 6L)
    assertEquals(
      stored(w1, rA, rE),
      firstK(2, Vector(rA, rB, rD, rE), Vector(rA, rC, rD, rE))
    )
    assertEquals(stored(w2, rA, rE), Vector(oriented(Vector(rA, rE))))
    assertEquals(stored(w1, rA, rF), Vector.empty, "different components")
    assertEquals(stored(w2, rA, rF), Vector.empty, "F is not in the window")
    assertEquals(
      stored(w1, rB, rC),
      firstK(2, Vector(rB, rA, rC), Vector(rB, rD, rC), Vector(rB, rI, rC)),
      "three ties, the first two in node order"
    )
    assertEquals(stored(w2, rB, rC), Vector.empty, "neither is in the window")
    assertEquals(stored(w1, rA, rH), Vector.empty, "H is not in the window")
    assertEquals(stored(w2, rA, rH), Vector(oriented(Vector(rA, rE, rH))))
    paths.foreach { p =>
      assertEquals(p.hopCount, p.nodeIds.size - 1)
      assertEquals(p.bounds, PathBounds(3, 2, 1))
      assertEquals(
        p.hops.map(_.edgeId),
        p.nodeIds.sliding(2).toVector.map(x => edgeId(p.window, x(0), x(1)))
      )
      assertEquals(p.pathId, RunFixtures.ok(EvidencePathId.of(p.window, policy, p.nodeIds)))
    }
    // Hop evidence: the A-B edge (q01, q09) shows its smallest supporting work only.
    val ab = edgeId(w1, rA, rB)
    val abHops = paths.flatMap(_.hops).filter(_.edgeId == ab)
    assert(abHops.nonEmpty)
    abHops.foreach(h => assertEquals(h.evidenceWorkIds, Vector(q(1))))
    val st = result.step2C.pathStats
    // 4 pairs x 2 windows; present: (A,E) and (A,F), (B,C) in 2015-2019, (A,E), (A,H) in 2020-2024.
    assertEquals((st.searches, st.present, st.connected), (8, 5, 4))
  }

  test("stored paths pass checkPaths; a dropped, reordered or foreign path is detected") {
    val g = input.graphInput
    def check(ps: Vector[EvidencePath]) =
      AnalysisValidation.checkPaths(ps, metrics, g, settings, pairList)
    assertEquals(check(paths), Vector.empty)
    val bc = paths.filter(p => p.window == w1 && p.hopCount == 2)
    assertEquals(bc.size, 2)
    assert(check(paths.filterNot(_ == bc.head)).exists(_.toString.contains("first")))
    val swapped = paths.map(p =>
      if (p == bc.head) p.copy(ordinal = 1) else if (p == bc(1)) p.copy(ordinal = 0) else p
    )
    assert(check(swapped).nonEmpty)
    val other = pairList.copy(pairs = pairList.pairs.filterNot(_ == pair(rA, rH)))
    assert(
      AnalysisValidation
        .checkPaths(paths, metrics, g, settings, other)
        .exists(_.toString.contains("benchmark pair list"))
    )
  }

  test("path-length estimates: the exact 2020-2024 case and the sampled 2015-2019 case") {
    assertEquals(estimates.size, 2)
    val big = Vector(rA, rB, rC, rD, rE, rI)
    val sources =
      PathSampleKey
        .sample(sampling.seed, w1, big, sampling.sampleSize)
        .fold(e => fail(e.toString), identity)
    val e1 = estimates.find(_.window == w1).getOrElse(fail("no 2015-2019 estimate"))
    assertEquals(e1.componentId, RunFixtures.ok(ComponentId.of(w1, policy, big)))
    assertEquals((e1.componentSize, e1.exact, e1.sourceIds), (6L, false, sources))
    assertEquals(e1.distanceCounts, handCounts(sources))
    assertEquals(e1.pairCount, 20L)
    val e2 = estimates.find(_.window == w2).getOrElse(fail("no 2020-2024 estimate"))
    assertEquals((e2.componentSize, e2.exact, e2.sourceIds), (3L, true, sorted3(rA, rE, rH)))
    assertEquals(e2.distanceCounts, Vector(DistanceCount(1, 4L), DistanceCount(2, 2L)))
    assertEquals((e2.pairCount, e2.averageDistance), (6L, 8.0 / 6.0))
    assertEquals((e2.maxObservedDistance, e2.effectiveDiameter90), (2, 2))
    assertEquals((e1.sampleSeed, e1.sampleSize, e1.method), (7L, 4, "sampled-bfs"))
    assertEquals(result.step2C.pathLength.map(_._1), Some(2L))
    assertEquals(
      StructureValidation.checkPathLengthEstimates(estimates, input.graphInput, settings),
      Vector.empty
    )
    // A record that passes its own contract (every derived field rebuilt by the core builder) but
    // whose counts are not the breadth-first counts: only the dataset check can detect it.
    val wrong = PathLengthEstimate
      .build(
        e2.window,
        e2.componentId,
        e2.componentSize,
        e2.sampleSeed,
        e2.sampleSize,
        e2.sourceIds,
        Vector(DistanceCount(1, 5L), DistanceCount(2, 1L)),
        e2.lineage,
        _ => e2.provenance
      )
      .fold(e => fail(e.mkString("; ")), identity)
    val bad = estimates.map(e => if (e == e2) wrong else e)
    assert(
      StructureValidation
        .checkPathLengthEstimates(bad, input.graphInput, settings)
        .exists(_.toString.contains("distanceCounts"))
    )
    assert(
      StructureValidation
        .checkPathLengthEstimates(estimates.tail, input.graphInput, settings)
        .exists(_.toString.contains("no estimate"))
    )
    assertEquals(
      result.published.manifest.parameters.get(AnalysisRun.OutputsParameter),
      Some(AnalysisRun.OutputsWithPathLength)
    )
  }

  private def sorted3(xs: String*) = RunFixtures.sorted(xs*)

  test("analysis windows and the window comparison: hand counts, validators, limitation") {
    assertEquals(
      windows.map(w =>
        (
          w.window,
          w.ordinal,
          w.countedWorkCount,
          w.countedWorksWithoutYear,
          w.researcherCount,
          w.edgeCount,
          w.componentCount,
          w.unresolvedCoauthorOccurrences
        )
      ),
      Vector((w1, 0, 9L, 0L, 8L, 8L, 2L, 0L), (w2, 1, 2L, 0L, 3L, 2L, 1L, 0L))
    )
    assertEquals(
      AnalysisValidation.checkWindows(windows, outputs, input.graphInput, settings),
      Vector.empty
    )
    assertEquals(comparisons.size, 1)
    val c = comparisons.head
    assertEquals(
      (
        c.researchersInBoth,
        c.researchersOnlyFrom,
        c.researchersOnlyTo,
        c.edgesInBoth,
        c.edgesOnlyFrom,
        c.edgesOnlyTo
      ),
      (2L, 6L, 1L, 0L, 8L, 2L)
    )
    assert(structures.forall(_.communityConverged))
    assertEquals(c.limitations, Vector(ComparisonLimitation.GroupingUnevaluated))
    assertEquals((c.overlapYears, c.inclusionRuleCompatible), (0, true))
    assertEquals(
      StructureValidation.checkTemporal(comparisons, changes, outputs, input.graphInput, settings),
      Vector.empty
    )
    assertEquals(changes.size, 9, "A..I in either window: 2 in both, 6 only before, 1 after")
  }

  test("semantic-summary inputs: one per metrics record, community and path; validated") {
    val byType = summaries.groupBy(_.documentType).view.mapValues(_.size).toMap
    assertEquals(byType.get(DocumentType.ResearcherProfile), Some(11))
    assertEquals(byType.get(DocumentType.PathSummary), Some(6))
    assertEquals(
      byType.get(DocumentType.CommunitySummary),
      Some(communities.map(x => (x.window, x.communityId)).distinct.size)
    )
    assertEquals(result.step2C.summaryInputCount, summaries.size.toLong)
    assertEquals(
      AnalysisValidation.checkSummaryInputs(summaries, outputs, input.graphInput, settings),
      Vector.empty
    )
    // The (A, H) 2020-2024 path summary: hop count 2 citing the path and both hop edges.
    val ah = paths.find(p => p.window == w2 && p.hopCount == 2).getOrElse(fail("no A-H path"))
    val s = summaries.find(_.subjectId == ah.pathId).getOrElse(fail("no path summary"))
    val hop = s.facts.find(_.name == "hopCount").getOrElse(fail("no hopCount fact"))
    assertEquals(hop.value, "2")
    assertEquals(
      hop.evidence,
      SummaryEvidenceRef(SummaryEvidenceKind.EvidencePath, ah.pathId) +:
        ah.hops.map(h => SummaryEvidenceRef(SummaryEvidenceKind.CoauthorEdge, h.edgeId))
    )
    // Counter-tests: a changed value and an invented metric are refused.
    val a = summaries.find(x => x.subjectId == rA && x.window == w1).getOrElse(fail("no A"))
    def withFacts(f: Vector[SummaryFact] => Vector[SummaryFact]) =
      summaries.map(x => if (x == a) x.copy(facts = f(x.facts)) else x)
    val changed =
      withFacts(_.map(x => if (x.name == "publicationCount") x.copy(value = "9") else x))
    assert(
      AnalysisValidation
        .checkSummaryInputs(changed, outputs, input.graphInput, settings)
        .exists(_.toString.contains("publicationCount"))
    )
    val invented = withFacts(fs =>
      fs :+ SummaryFact(
        "collaborationScore",
        "0.9",
        Vector(SummaryEvidenceRef(SummaryEvidenceKind.ResearcherMetrics, rA))
      )
    )
    assert(
      AnalysisValidation
        .checkSummaryInputs(invented, outputs, input.graphInput, settings)
        .exists(_.toString.contains("frozen vocabulary"))
    )
  }

  test("entity summaries: the selected works and topics, hand facts of q01 and T21; validated") {
    val selected = paths.flatMap(_.hops.flatMap(_.evidenceWorkIds)).distinct
    // Always on a stored path: q01 (A-B), q02 (A-C), q03 (B-D), q04 (C-D), q05 (D-E) of the two
    // (A, E) paths of 2015-2019, q10 (A-E) and q11 (E-H) of 2020-2024; q07 and q08 only when B-I-C
    // is among the two stored (B, C) paths.
    Vector(1, 2, 3, 4, 5, 10, 11).foreach(i => assert(selected.contains(q(i)), q(i)))
    val viaI = stored(w1, rB, rC).exists(_.contains(rI))
    assertEquals(selected.size, if (viaI) 9 else 7)
    val works = entities.filter(_.documentType == DocumentType.WorkSummary)
    val topics = entities.filter(_.documentType == DocumentType.TopicSummary)
    assertEquals(works.map(_.subjectId).toSet, selected.toSet)
    assertEquals(topics.map(_.subjectId).toSet, Set(t1, t2))
    def facts(s: EntitySummaryInput) = s.facts.map(f => (f.name, f.value)).toSet
    val w = works.find(_.subjectId == q(1)).getOrElse(fail("no q01 summary"))
    assertEquals(
      facts(w),
      Set(
        "title" -> "Synthetic Work journals/example/Path01",
        "year" -> "2015",
        "graphEligibility" -> "counted",
        "groupSize" -> "1",
        "author" -> rA,
        "author" -> rB,
        "venue" -> v1,
        "topic" -> t1,
        "name" -> "Path Venue One",
        "name" -> "Path Topic One"
      )
    )
    val t = topics.find(_.subjectId == t1).getOrElse(fail("no T21 summary"))
    assertEquals(facts(t), Set("name" -> "Path Topic One", "work" -> q(1), "work" -> q(5)))
    val canonical = input.canonicalInput
    def check(xs: Vector[EntitySummaryInput]) =
      StructureValidation.checkEntitySummaries(
        xs,
        nodes,
        graphEdges,
        canonical.works,
        canonical.sourceWorks,
        settings
      )
    assertEquals(check(entities), Vector.empty)
    val retitled = entities.map(x =>
      if (x == w)
        x.copy(facts = x.facts.map(f => if (f.name == "title") f.copy(value = "x") else f))
      else x
    )
    assert(check(retitled).exists(_.toString.contains("title")))
  }

  test("load manifests: one per store, every accepted part listed, re-verified; counter-tests") {
    val loads = result.step2C.loads
    assertEquals(loads.dir.localPath, out.resolve("load=" + result.runId.stripPrefix("run:")))
    val pm = result.published.partManifest
    assertEquals(
      StructureValidation.checkLoadManifests(loads.manifests, Right(pm), loads.partManifestSha256),
      Vector.empty
    )
    def roles(t: TargetStore) =
      loads.of(t).getOrElse(fail(s"no ${t.tag} manifest")).items.map(_.targetRole).toSet
    assertEquals(
      roles(TargetStore.NeptuneBulkLoad),
      (NeptuneCsvParts.vertexDatasets.map(_._2) ++ NeptuneCsvParts.edgeDatasets.map(_._2)).toSet
    )
    assertEquals(roles(TargetStore.S3Vectors), Set("summary-inputs", "entity-summary-inputs"))
    assertEquals(
      roles(TargetStore.DynamoDb),
      Set(
        "researcher-metrics",
        "coauthor-edges",
        "component-memberships",
        "community-memberships",
        "bridge-measures",
        "coreness",
        "evidence-paths",
        "analysis-windows",
        "window-structures",
        "path-length-estimates",
        "window-comparisons",
        "temporal-changes"
      )
    )
    assertEquals(
      roles(TargetStore.S3),
      roles(TargetStore.DynamoDb) ++ roles(TargetStore.S3Vectors) ++ Set(
        "graph-nodes",
        "graph-edges"
      )
    )
    // Every Parquet part is in the S3 manifest and every CSV part in the Neptune one.
    assertEquals(
      loads.of(TargetStore.S3).get.items.map(_.path).toSet,
      pm.files.filter(_.format == PartFormat.Parquet).map(_.path).toSet
    )
    assertEquals(
      loads.of(TargetStore.NeptuneBulkLoad).get.items.map(_.path).toSet,
      pm.files.filter(_.format == PartFormat.Csv).map(_.path).toSet
    )
    // Counter-tests: a dropped item, another part-manifest hash, a tampered file, a second write.
    val dynamo = loads.of(TargetStore.DynamoDb).get
    // A valid manifest (core builder, own load ID) that silently leaves one accepted part out.
    val short = StoreLoadManifest
      .build(
        TargetStore.DynamoDb,
        Right(pm),
        loads.partManifestSha256,
        dynamo.items.tail.map(i => i.path -> i.targetRole).toMap
      )
      .fold(e => fail(e.mkString("; ")), identity)
    val dropped = loads.manifests.map(m => if (m == dynamo) short else m)
    assert(
      StructureValidation
        .checkLoadManifests(dropped, Right(pm), loads.partManifestSha256)
        .exists(_.toString.contains("is not loaded"))
    )
    assert(
      StructureValidation
        .checkLoadManifests(loads.manifests, Right(pm), "0" * 64)
        .exists(_.toString.contains("partManifestSha256"))
    )
    val copy = Files.createDirectories(tmp.resolve("tampered-loads"))
    TargetStore.values.foreach { t =>
      Files.copy(
        loads.dir.resolve(LoadManifests.fileName(t)).localPath,
        copy.resolve(LoadManifests.fileName(t))
      )
    }
    val f = copy.resolve(LoadManifests.fileName(TargetStore.S3))
    val text = new String(Files.readAllBytes(f), StandardCharsets.UTF_8)
    Files.write(
      f,
      text
        .replace("\"summary-inputs\"", "\"entity-summary-inputs\"")
        .getBytes(StandardCharsets.UTF_8)
    )
    assert(LoadManifests.verify(copy, result.published, limits.maxMetadataBytes).isLeft)
    assert(
      LoadManifests.verify(loads.dir.localPath, result.published, limits.maxMetadataBytes).isRight
    )
    assert(
      LoadManifests
        .publish(out, result.published, limits.maxMetadataBytes)
        .left
        .exists(_.exists(_.contains("never overwritten")))
    )
  }

  test("the run chain covers every dataset of the run, the step-2C records included") {
    assertEquals(
      AnalysisValidation.checkRunChain(
        result.published.manifest,
        input.grouping.manifest,
        outputs,
        settings,
        pairList
      ),
      Vector.empty
    )
    assertEquals(outputs.lineageAndProvenance.map(_._1).distinct.size, 16)
    val bad = outputs.copy(entitySummaryInputs =
      entities.map(e => e.copy(lineage = e.lineage.copy(codeVersion = "f" * 40)))
    )
    assert(
      AnalysisValidation
        .checkRunChain(result.published.manifest, input.grouping.manifest, bad, settings, pairList)
        .exists(_.toString.contains("entitySummaryInputs"))
    )
  }
}
