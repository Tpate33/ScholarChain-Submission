package scholarchain.spark.analysis

import java.nio.file.{Files, Path}

import scala.collection.immutable.SortedMap

import org.apache.spark.sql.{Row, SparkSession}
import org.apache.spark.sql.types.StructType

import scholarchain.core.*
import scholarchain.spark.graph.{CanonicalGraphJob, Checkpointing}
import scholarchain.spark.input.{ReaderLimits, VerifiedArchiveReader, VerifiedInput}
import scholarchain.spark.output.*
import scholarchain.spark.testkit.{CanonicalFixtures, LocalSpark, RunFixtures}
import scholarchain.spark.testkit.CanonicalFixtures.*
import scholarchain.spark.testkit.RunFixtures.{rA, u1, w1, w2}

/**
 * The canonical tables of one collaboration run (SP-P6-03 step 2B, course item 1) on the
 * hand-checkable [[CanonicalFixtures]] chain: the published `graph-node.v2` and `graph-edge.v2`
 * records equal the hand tables (an unresolved mention kept with AUTHORED and no RESOLVES_TO, the
 * identified mentions with RESOLVES_TO and their researchers' AUTHORED, the copy's venue and topic
 * joined into its representative, unmatched references counted, never edges); they pass
 * `CanonicalGraphValidation.checkStructure` and `checkTables`, equal `CanonicalGraph.expected`, and
 * pass `AnalysisValidation.checkRunChain` with the window structures; the twelve Neptune CSV
 * datasets hold exactly `NeptuneCsv.vertexRow` / `edgeRow` of their records; counter-tests show
 * that a changed property, a dropped unresolved AUTHORED, a forced merge and a dangling endpoint
 * are detected. DEV fixture, unevaluated; the driver collects at most 31 records (test only).
 */
class CanonicalGraphRunSuite extends munit.FunSuite {

  private var tmp: Path = null
  private var spark: SparkSession = null
  private var written: RunFixtures.Written = null
  private var input: VerifiedInput = null

  private val limits = ReaderLimits(1000, 6, 10000, 1L << 20, 1L << 16)
  private val settings = RunFixtures.settings

  override def beforeAll(): Unit = {
    tmp = Files.createTempDirectory("scholarchain-canonical-run-")
    spark = LocalSpark.start("scholarchain-canonical-run-test", tmp.resolve("spark"))
    written = RunFixtures.write(tmp.resolve("fixture"), CanonicalFixtures.spec)
    input =
      VerifiedArchiveReader.read(written.runs, limits).fold(r => fail(r.mkString("\n")), identity)
  }

  override def afterAll(): Unit =
    try if (spark != null) spark.stop()
    finally LocalSpark.deleteTree(tmp)

  private def pairs = RunFixtures.benchmarkPairs(written.groupingRunId)

  private lazy val result: CollaborationRunResult = AnalysisRun
    .runCollaboration(
      spark,
      AnalysisJobConfig(
        settings,
        "0123456789abcdef0123456789abcdef76543210",
        1,
        SortedMap.empty,
        1,
        Checkpointing(tmp.resolve("checkpoints").toString, 2)
      ),
      input,
      pairs,
      Files.createDirectories(tmp.resolve("out"))
    )
    .fold(e => fail(e.mkString("\n")), identity)

  private def read[T](label: String, schema: StructType, from: Row => Either[String, T]) =
    spark.read
      .schema(schema)
      .parquet(
        result.published.partManifest.files
          .filter(f => f.recordSchemaVersion == label && f.format == PartFormat.Parquet)
          .map(f => result.published.dir.resolve(f.path).toUri.toString)*
      )
      .collect()
      .toVector
      .map(r => from(r).fold(fail(_), identity))

  private lazy val nodes =
    read(SchemaVersions.GraphNodeV2, GraphNodeRows.schema, GraphNodeRows.fromRow)
  private lazy val graphEdges =
    read(SchemaVersions.GraphEdgeV2, GraphEdgeRows.schema, GraphEdgeRows.fromRow)
  private def records[T](label: String, schema: StructType, from: Row => Either[String, T]) =
    read(label, schema, from)

  private def nodeTable(xs: Vector[GraphNodeV2]) =
    xs.map(n => n.nodeId -> (n.label, n.properties)).toMap
  private def edgeTable(xs: Vector[GraphEdgeV2]) =
    xs.map(e => (e.edgeType, e.fromId, e.toId, e.sourceEvidence) -> e.properties).toMap

  test("the reader decodes the fixture's curated sources and authorships (hand counts)") {
    // 6 default works + wc; 11 default mentions + wc/0; V1, V2; I1; T1; 10 authorships.
    val s = input.curatedSources
    assertEquals(
      (s.works.size, s.mentions.size, s.venues.size, s.institutions.size, s.topics.size),
      (7, 12, 2, 1, 1)
    )
    assertEquals(input.authorships.size, 10)
  }

  test("nodes: exactly the 26 hand-derived nodes, labels and properties") {
    assertEquals(nodes.size, 26)
    assertEquals(nodeTable(nodes), handNodes.map(n => n.nodeId -> (n.label, n.properties)).toMap)
    assertEquals(
      result.canonical.nodesByLabel,
      Vector(
        "Researcher" -> 5L,
        "AuthorMention" -> 11L,
        "Work" -> 6L,
        "Venue" -> 2L,
        "Institution" -> 1L,
        "Topic" -> 1L
      )
    )
  }

  test("edges: exactly the 31 hand-derived edges, endpoints, evidence and properties") {
    assertEquals(graphEdges.size, 31)
    assertEquals(
      edgeTable(graphEdges),
      handEdges.map(e => (e.edgeType, e.fromId, e.toId, e.sourceEvidence) -> e.properties).toMap
    )
    assertEquals(
      result.canonical.edgesByClass,
      Vector(
        "AUTHORED" -> 11L,
        "RESOLVES_TO" -> 10L,
        "COAUTHORED_WITH" -> 4L,
        "PUBLISHED_IN" -> 2L,
        "AFFILIATED_WITH" -> 2L,
        "HAS_TOPIC" -> 2L
      )
    )
    assertEquals(result.canonical.invariantFailures.map(_._2), Vector(0L, 0L, 0L))
  }

  test("the unresolved mention is preserved unmerged; the copy's unresolved mention is no node") {
    val own = graphEdges.filter(_.fromId == u1)
    assertEquals(
      own.map(e => (e.edgeType, e.toId)).toSet,
      Set(EdgeLabel.Authored -> w1, EdgeLabel.AffiliatedWith -> i1)
    )
    assert(!graphEdges.exists(e => e.edgeType == EdgeLabel.ResolvesTo && e.fromId == u1))
    assert(
      nodes.exists(n =>
        n.nodeId == u1 && n.properties.get("assignmentStatus").contains("unresolved")
      )
    )
    assert(!nodes.exists(_.nodeId == wcMention))
    assert(!nodes.exists(_.nodeId == wc))
    assert(!graphEdges.exists(e => e.fromId == wcMention || e.sourceEvidence == wcMention))
  }

  test("the copy's venue and topic join its representative; unmatched references are counted") {
    assert(
      graphEdges.exists(e =>
        e.edgeType == EdgeLabel.PublishedIn && e.fromId == w2 && e.toId == v2 && e.sourceEvidence == wc
      )
    )
    assert(
      graphEdges.exists(e =>
        e.edgeType == EdgeLabel.HasTopic && e.fromId == w2 && e.toId == t1 && e.sourceEvidence == wc
      )
    )
    assertEquals(result.canonical.unmatched, handUnmatched)
    assert(!graphEdges.exists(e => Set(vMissing, t9, i8).contains(e.toId)))
  }

  test("checkStructure, checkTables, CanonicalGraph.expected and checkRunChain accept the tables") {
    val coauthor =
      records(SchemaVersions.CoauthorEdgeV2, CoauthorEdgeRows.schema, CoauthorEdgeRows.fromRow)
    val structures = records(
      SchemaVersions.WindowStructure,
      WindowStructureRows.schema,
      WindowStructureRows.fromRow
    )
    assertEquals(CanonicalGraphValidation.checkStructure(nodes, graphEdges), Vector.empty)
    assertEquals(
      CanonicalGraphValidation
        .checkTables(nodes, graphEdges, input.canonicalInput, coauthor, settings),
      Vector.empty
    )
    val g = RunFixtures.ok(CanonicalGraph.expected(input.canonicalInput, coauthor))
    assertEquals(g.unmatched, result.canonical.unmatched)
    assertEquals(g.nodes.size, nodes.size)
    val outputs = AnalysisOutputs(
      records(
        SchemaVersions.ResearcherMetricsV2,
        ResearcherMetricsRows.schema,
        ResearcherMetricsRows.fromRow
      ),
      coauthor,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      nodes = nodes,
      graphEdges = graphEdges,
      windowStructures = structures
    )
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
    nodes.foreach(n => assertEquals(n.provenance.runId, result.runId))
  }

  test("counter-tests: a changed property, a dropped or merged mention edge, a dangling edge") {
    val coauthor =
      records(SchemaVersions.CoauthorEdgeV2, CoauthorEdgeRows.schema, CoauthorEdgeRows.fromRow)
    def tables(ns: Vector[GraphNodeV2], es: Vector[GraphEdgeV2]) =
      CanonicalGraphValidation.checkTables(ns, es, input.canonicalInput, coauthor, settings)
    val changed = nodes.map(n =>
      if (n.nodeId == w2) n.copy(properties = n.properties.updated("groupSize", "1")) else n
    )
    assert(tables(changed, graphEdges).exists(_.toString.contains(s"nodes[$w2].properties")))
    val ownAuthored =
      graphEdges.filter(e => e.edgeType == EdgeLabel.Authored && e.fromId == u1)
    assertEquals(ownAuthored.size, 1)
    val dropped = graphEdges.filterNot(ownAuthored.contains)
    assert(
      CanonicalGraphValidation
        .checkStructure(nodes, dropped)
        .exists(_.toString.contains("exactly one AUTHORED edge (found 0)"))
    )
    val merged = RunFixtures.ok(
      GraphEdgeV2.build(
        EdgeLabel.ResolvesTo,
        u1,
        rA,
        u1,
        SortedMap("assignmentStatus" -> "resolved"),
        ownAuthored.head.lineage,
        settings.graphPolicyVersion,
        id => ownAuthored.head.provenance.copy(sourceRecordId = id)
      )
    )
    assert(
      tables(nodes, graphEdges :+ merged).exists(_.toString.contains("never has a RESOLVES_TO"))
    )
    // The job's own distributed invariant counts, on the same mutations.
    val sc = spark.sparkContext
    def counts(ns: Vector[GraphNodeV2], es: Vector[GraphEdgeV2]) =
      CanonicalGraphJob.invariants(sc.parallelize(ns, 2), sc.parallelize(es, 2), 2).map(_._2)
    assertEquals(counts(nodes, graphEdges), Vector(0L, 0L, 0L))
    assertEquals(counts(nodes.filterNot(_.nodeId == v1), graphEdges), Vector(1L, 0L, 0L))
    assertEquals(counts(nodes, dropped), Vector(0L, 0L, 1L))
    assertEquals(counts(nodes, graphEdges :+ merged), Vector(0L, 0L, 1L))
  }

  test("Neptune CSV: one dataset per label and class, each part its header plus the record rows") {
    val pm = result.published.partManifest
    def text(name: String): Vector[String] =
      pm.files
        .filter(_.path.startsWith(name + "/"))
        .map(f => Files.readString(result.published.dir.resolve(f.path).localPath))
    def rows(name: String, header: String): Vector[String] = text(name).flatMap { t =>
      val lines = t.split("\n", -1).toVector
      assertEquals(lines.head, header, name)
      assertEquals(lines.last, "", s"$name: every line ends in LF")
      lines.tail.init
    }
    NeptuneCsvParts.vertexDatasets.foreach { case (label, name, _, header) =>
      val want = nodes.filter(_.label == label).map(n => NeptuneCsv.vertexRow(n).stripSuffix("\n"))
      assertEquals(rows(name, header).sorted, want.sorted, name)
      assertEquals(
        pm.files.filter(_.path.startsWith(name + "/")).map(_.rowCount).sum,
        want.size.toLong,
        name
      )
    }
    NeptuneCsvParts.edgeDatasets.foreach { case (label, name, _, header) =>
      val want =
        graphEdges.filter(_.edgeType == label).map(e => NeptuneCsv.edgeRow(e).stripSuffix("\n"))
      assertEquals(rows(name, header).sorted, want.sorted, name)
    }
    // Hand check of one row: the unresolved mention's vertex (no quoting needed).
    assert(
      rows("author-mention-vertices", NeptuneCsvParts.vertexDatasets(1)._4)
        .contains(s"$u1,AuthorMention,unresolved,3,$w1,${result.runId}")
    )
    assertEquals(result.canonical.csvParts.size, 12)
  }

  test("every new row layout has the record's JSON keys, in record order") {
    def keys(v: ujson.Value) = v match {
      case o: ujson.Obj => o.value.keys.toVector
      case _ => Vector.empty
    }
    assertEquals(GraphNodeRows.schema.fieldNames.toVector, keys(ujson.read(Json.write(nodes.head))))
    assertEquals(
      GraphEdgeRows.schema.fieldNames.toVector,
      keys(ujson.read(Json.write(graphEdges.head)))
    )
    assertEquals(
      WindowStructureRows.schema.fieldNames.toVector,
      keys(ujson.read(Json.write(result.windowStructures.head)))
    )
    // The properties survive the array<struct<key,value>> layout exactly.
    assertEquals(
      nodes.find(_.nodeId == w2).map(_.properties),
      Some(SortedMap("graphEligibility" -> "counted", "groupSize" -> "2", "year" -> "2019"))
    )
  }
}
