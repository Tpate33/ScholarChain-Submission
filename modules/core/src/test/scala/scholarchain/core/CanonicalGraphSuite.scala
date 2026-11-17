package scholarchain.core

import scala.collection.immutable.SortedMap

import AnalysisFixtures.{
  analysisManifest,
  benchmarkPairs,
  groupingManifest,
  lineage,
  prov,
  settings
}
import CanonicalGraphValidation.*
import Fixtures.{graphPolicy, mention0, mention1, ok, researcherA, workId, workId2}
import StructureFixtures.*

/**
 * Course item 1 (DE-P6-03): the canonical Researcher, AuthorMention, Work, Venue, Institution and
 * Topic tables. Synthetic fixtures only ([[StructureFixtures]]); no file is read.
 */
class CanonicalGraphSuite extends munit.FunSuite {
  import AssignmentFixtures.{mentionU, singletonId, workW1, workW2}

  private def refused(errs: Vector[ValidationError], fragment: String)(using munit.Location): Unit =
    assert(
      errs.exists(_.message.contains(fragment)),
      s"expected a refusal containing '$fragment', got ${errs.map(_.message)}"
    )

  private def sortedNodes(xs: Vector[ExpectedNode]) =
    xs.sortWith((a, b) => Canonical.compareUtf8(a.nodeId, b.nodeId) < 0)
  private def sortedEdges(xs: Vector[ExpectedEdge]) = xs.sortWith(ExpectedEdge.less)

  private val i1 = Fixtures.institution.institutionId
  private val t1 = Fixtures.topic.topicId

  // ---------------------------------------------------------------- the executable definition

  test("expected tables equal the hand derivation: nodes, edges and unmatched references") {
    val g = ok(CanonicalGraph.expected(graphInput, Vector(coauthorAC)))
    assertEquals(g.nodes, sortedNodes(handNodes))
    assertEquals(g.edges, sortedEdges(handEdges))
    assertEquals(g.unmatched, handUnmatched)
  }

  test(
    "unresolved mentions are preserved as AuthorMention nodes with AUTHORED, never RESOLVES_TO"
  ) {
    val g = ok(CanonicalGraph.expected(graphInput, Vector(coauthorAC)))
    assert(g.nodes.exists(n => n.nodeId == mentionU && n.label == NodeLabel.AuthorMention))
    assertEquals(
      g.edges.filter(_.fromId == mentionU).map(e => (e.edgeType, e.toId)).toSet,
      Set(EdgeLabel.Authored -> workId2, EdgeLabel.AffiliatedWith -> i1)
    )
    assert(!g.edges.exists(e => e.edgeType == EdgeLabel.ResolvesTo && e.fromId == mentionU))
  }

  test("the synthetic tables pass the structural and the exact checks") {
    assertEquals(checkStructure(nodes, graphEdges), Vector.empty)
    assertEquals(
      checkTables(nodes, graphEdges, graphInput, Vector(coauthorAC), settings),
      Vector.empty
    )
  }

  test(
    "input refusals: authorship on a copy, representative without a work record, name conflict"
  ) {
    val onCopy = AssignmentFixtures.resolvedAuthorship.copy(workId = workW1)
    refused(
      CanonicalGraph
        .expected(graphInput.copy(authorships = Vector(onCopy)), Vector(coauthorAC))
        .left
        .getOrElse(Vector.empty),
      "is not a representative work"
    )
    refused(
      CanonicalGraph
        .expected(
          graphInput.copy(sourceWorks = graphInput.sourceWorks.filterNot(_.workId == workW2)),
          Vector.empty
        )
        .left
        .getOrElse(Vector.empty),
      s"representative $workW2 has no curated work record"
    )
    refused(
      CanonicalGraph
        .expected(
          graphInput.copy(venues =
            graphInput.venues :+ Fixtures.venue.copy(name = "Other Journal")
          ),
          Vector.empty
        )
        .left
        .getOrElse(Vector.empty),
      "disagree on name"
    )
    assert(CanonicalGraph.expected(null, Vector.empty).isLeft)
  }

  // ---------------------------------------------------------------- structural rules

  test("dangling endpoints are refused") {
    val noVenue = nodes.filterNot(_.label == NodeLabel.Venue)
    refused(checkStructure(noVenue, graphEdges), "dangling to endpoint venue:dblp:journals/example")
    val noWork = nodes.filterNot(_.nodeId == workId2)
    refused(checkStructure(noWork, graphEdges), s"dangling to endpoint $workId2")
    val noResearcher = nodes.filterNot(_.nodeId == researcherA)
    refused(checkStructure(noResearcher, graphEdges), s"dangling from endpoint $researcherA")
  }

  test("an invalid edge record stops the structural check at its contract") {
    // Endpoint ID kinds are tied to labels by the contracts, so an edge with an endpoint of a kind
    // its class does not allow is refused before any table rule runs.
    val bad = edgeOf(EdgeLabel.ResolvesTo, mention0.mentionId, researcherA).copy(toId = workId)
    refused(checkStructure(nodes, graphEdges :+ bad), "graphEdges[10].toId")
  }

  test("an unresolved mention with RESOLVES_TO (a forced merge) is refused") {
    val merged = ok(
      GraphEdgeV2.build(
        EdgeLabel.ResolvesTo,
        mentionU,
        researcherA,
        mentionU,
        SortedMap("assignmentStatus" -> "resolved"),
        lineage,
        graphPolicy,
        prov
      )
    )
    refused(checkStructure(nodes, graphEdges :+ merged), "never has a RESOLVES_TO edge")
    refused(
      checkTables(nodes, graphEdges :+ merged, graphInput, Vector(coauthorAC), settings),
      "never has a RESOLVES_TO edge"
    )
  }

  test("an unresolved mention without its AUTHORED edge, or with it to another work, is refused") {
    val own = edgeOf(EdgeLabel.Authored, mentionU, workId2)
    refused(
      checkStructure(nodes, graphEdges.filterNot(_ == own)),
      "exactly one AUTHORED edge (found 0)"
    )
    val elsewhere = ok(
      GraphEdgeV2.build(
        EdgeLabel.Authored,
        mentionU,
        workId,
        mentionU,
        SortedMap("position" -> "0"),
        lineage,
        graphPolicy,
        prov
      )
    )
    refused(
      checkStructure(nodes, graphEdges.filterNot(_ == own) :+ elsewhere),
      "must point to its own work"
    )
  }

  test(
    "an identified mention has one RESOLVES_TO, no own AUTHORED, and a backing researcher AUTHORED"
  ) {
    val resolves = edgeOf(EdgeLabel.ResolvesTo, mention0.mentionId, researcherA)
    refused(
      checkStructure(nodes, graphEdges.filterNot(_ == resolves)),
      "exactly one RESOLVES_TO edge (found 0)"
    )
    val authored = edgeOf(EdgeLabel.Authored, researcherA, workId)
    refused(
      checkStructure(nodes, graphEdges.filterNot(_ == authored)),
      "its researcher has exactly one AUTHORED"
    )
    val own = ok(
      GraphEdgeV2.build(
        EdgeLabel.Authored,
        mention1.mentionId,
        workId,
        mention1.mentionId,
        SortedMap("position" -> "1"),
        lineage,
        graphPolicy,
        prov
      )
    )
    refused(
      checkStructure(nodes, graphEdges :+ own),
      "authorship is its researcher's AUTHORED edge"
    )
  }

  test("a researcher AUTHORED edge must cite a mention node as its evidence") {
    val ghost = ok(MentionId.of(workId, 7))
    val e = ok(
      GraphEdgeV2.build(
        EdgeLabel.Authored,
        researcherA,
        workId,
        ghost,
        SortedMap("position" -> "7"),
        lineage,
        graphPolicy,
        prov
      )
    )
    refused(checkStructure(nodes, graphEdges :+ e), "is not an AuthorMention node")
  }

  test("duplicate node or edge IDs are refused") {
    refused(checkStructure(nodes :+ nodes.head, graphEdges), "nodes.nodeId")
    refused(checkStructure(nodes, graphEdges :+ graphEdges.head), "graphEdges.edgeId")
  }

  // ---------------------------------------------------------------- exact comparison

  test("counter-tests: changed property, missing edge, extra node are detected") {
    val a = nodeOf(researcherA)
    val changed = nodes.map(n =>
      if (n == a) n.copy(properties = n.properties.updated("memberCount", "3")) else n
    )
    refused(
      checkTables(changed, graphEdges, graphInput, Vector(coauthorAC), settings),
      s"nodes[$researcherA].properties: expected"
    )
    val topicEdge = edgeOf(EdgeLabel.HasTopic, workId, t1)
    refused(
      checkTables(
        nodes,
        graphEdges.filterNot(_ == topicEdge),
        graphInput,
        Vector(coauthorAC),
        settings
      ),
      s"missing HAS_TOPIC edge $workId -> $t1"
    )
    val t2 = ok(
      GraphNodeV2.build(
        "topic:openalex:T2",
        NodeLabel.Topic,
        SortedMap("name" -> "Invented Topic"),
        lineage,
        prov
      )
    )
    refused(
      checkTables(nodes :+ t2, graphEdges, graphInput, Vector(coauthorAC), settings),
      "not a node of the canonical tables of the input"
    )
    refused(
      checkTables(nodes, graphEdges, graphInput, Vector.empty, settings),
      "not an edge of the canonical tables of the input"
    )
  }

  test("the run chain covers the canonical tables") {
    val out = StructureFixtures.fullOutputs
    assertEquals(
      AnalysisValidation
        .checkRunChain(analysisManifest, groupingManifest, out, settings, benchmarkPairs),
      Vector.empty
    )
    val foreign =
      out.copy(nodes = nodes.map(_.copy(lineage = lineage.copy(configDigest = "8" * 64))))
    refused(
      AnalysisValidation
        .checkRunChain(analysisManifest, groupingManifest, foreign, settings, benchmarkPairs),
      "nodes[0].lineage: must equal the run chain"
    )
  }

  // ---------------------------------------------------------------- record contracts

  private def nodeErrors(n: GraphNodeV2) = GraphNodeV2.contract.validate(n)
  private def edgeErrors(e: GraphEdgeV2) = GraphEdgeV2.contract.validate(e)

  test("graph-node.v2 contract: label profiles, runtime labels, formulas and identity rules") {
    nodes.foreach(n => assertEquals(nodeErrors(n), Vector.empty, n.nodeId))
    val a = nodeOf(researcherA)
    refused(
      nodeErrors(a.copy(properties = a.properties - "memberCount")),
      "properties.memberCount: missing"
    )
    refused(
      nodeErrors(a.copy(properties = a.properties.updated("name", "Ada"))),
      "not a property of this label"
    )
    refused(
      nodeErrors(a.copy(properties = a.properties.updated("memberCount", "1"))),
      "resolved requires a group of >= 2"
    )
    refused(
      nodeErrors(a.copy(properties = a.properties.updated("memberCount", "02"))),
      "decimal integer"
    )
    refused(
      nodeErrors(a.copy(properties = a.properties.updated("identityStatus", "merged"))),
      "must be one of"
    )
    refused(nodeErrors(a.copy(label = NodeLabel.TextChunk)), "runtime nodes")
    refused(nodeErrors(a.copy(label = NodeLabel.Work)), "nodeId")
    val c = nodeOf(singletonId)
    refused(
      nodeErrors(c.copy(properties = c.properties.updated("anchorNamespace", "openalex-author"))),
      "t1-anchored singleton is anchored on orcid or dblp-pid"
    )
    val m = nodeOf(mention1.mentionId)
    refused(
      nodeErrors(m.copy(properties = m.properties.updated("position", "0"))),
      "is not the mention at position 0"
    )
    val w = nodeOf(workId)
    refused(
      nodeErrors(
        w.copy(properties = w.properties.updated("graphEligibility", "member-of-counted"))
      ),
      "must be one of counted, enrichment-only"
    )
    refused(
      nodeErrors(w.copy(provenance = w.provenance.copy(sourceRecordId = workId2))),
      "must equal"
    )
    refused(nodeErrors(w.copy(lineage = null)), "lineage")
    refused(
      nodeErrors(w.copy(schemaVersion = SchemaVersions.GraphNode)),
      "unsupported schemaVersion"
    )
  }

  test("graph-edge.v2 contract: classes, endpoints, evidence, properties and ID formulas") {
    graphEdges.foreach(e => assertEquals(edgeErrors(e), Vector.empty, e.edgeId))
    val authored = edgeOf(EdgeLabel.Authored, mentionU, workId2)
    refused(
      edgeErrors(authored.copy(sourceEvidence = mention0.mentionId)),
      "the evidence is the mention itself"
    )
    refused(edgeErrors(authored.copy(edgeType = EdgeLabel.DerivedFrom)), "runtime edges")
    refused(edgeErrors(authored.copy(toId = i1)), "toId")
    refused(edgeErrors(authored.copy(properties = SortedMap.empty)), "properties.position: missing")
    refused(edgeErrors(authored.copy(edgeId = "gedge:" + "0" * 64)), "edgeId: expected")
    val co = edgeOf(EdgeLabel.CoauthoredWith, researcherA, singletonId)
    refused(
      edgeErrors(co.copy(properties = co.properties.updated("windowStartYear", "2015"))),
      "sourceEvidence: expected"
    )
    refused(
      edgeErrors(co.copy(properties = co.properties.updated("firstCollaborationYear", "2019"))),
      "inside the window"
    )
    refused(edgeErrors(co.copy(fromId = co.toId, toId = co.fromId)), "canonical order")
    refused(
      edgeErrors(co.copy(sourceEvidence = Fixtures.workId)),
      "sourceEvidence"
    )
    val published = edgeOf(EdgeLabel.PublishedIn, workId, Fixtures.venue.venueId)
    refused(edgeErrors(published.copy(sourceEvidence = mentionU)), "sourceEvidence")
    refused(edgeErrors(published.copy(properties = SortedMap("year" -> "2021"))), "not a property")
    // Build orders an undirected pair canonically: both input orders give one edge.
    val reversed = ok(
      GraphEdgeV2.build(
        EdgeLabel.CoauthoredWith,
        singletonId,
        researcherA,
        coauthorAC.edgeId,
        co.properties,
        lineage,
        graphPolicy,
        prov
      )
    )
    assertEquals(reversed, co)
  }

  // ---------------------------------------------------------------- Neptune CSV

  test("Neptune CSV layout: columns per label, quoting, absent optional properties, runId") {
    assertEquals(
      NeptuneCsv.vertexColumns(NodeLabel.Work),
      Vector(
        "~id",
        "~label",
        "graphEligibility:String",
        "groupSize:Int",
        "year:Int",
        "runId:String"
      )
    )
    assertEquals(
      NeptuneCsv.edgeColumns(EdgeLabel.Authored),
      Vector(
        "~id",
        "~from",
        "~to",
        "~label",
        "position:Int",
        "sourceEvidence:String",
        "runId:String"
      )
    )
    val run = AnalysisFixtures.analysisRunId
    assertEquals(
      NeptuneCsv.vertexRow(nodeOf(workId)),
      s"$workId,Work,counted,2,2021,$run\n"
    )
    val noYear = nodeOf(workId).copy(properties = nodeOf(workId).properties - "year")
    assertEquals(NeptuneCsv.vertexRow(noYear), s"$workId,Work,counted,2,,$run\n")
    assertEquals(NeptuneCsv.field("Example, \"Inc\""), "\"Example, \"\"Inc\"\"\"")
    assertEquals(NeptuneCsv.field(" padded"), "\" padded\"")
    assertEquals(NeptuneCsv.field("plain"), "plain")
    val venues = nodes.filter(_.label == NodeLabel.Venue)
    assertEquals(
      NeptuneCsv.vertexFile(NodeLabel.Venue, venues),
      Right(
        s"~id,~label,name:String,runId:String\nvenue:dblp:journals/example,Venue,Example Journal,$run\n"
      )
    )
    assert(
      NeptuneCsv.vertexFile(NodeLabel.Venue, nodes).isLeft,
      "nodes of another label are refused"
    )
    assert(NeptuneCsv.vertexFile(NodeLabel.TextChunk, Vector.empty).isLeft)
    val e = edgeOf(EdgeLabel.Authored, researcherA, workId)
    assertEquals(
      NeptuneCsv.edgeRow(e),
      s"${e.edgeId},$researcherA,$workId,AUTHORED,0,${mention0.mentionId},$run\n"
    )
    assert(NeptuneCsv.edgeFile(EdgeLabel.Authored, graphEdges).isLeft)
    assert(
      NeptuneCsv
        .edgeFile(EdgeLabel.Authored, graphEdges.filter(_.edgeType == EdgeLabel.Authored))
        .isRight
    )
  }

  test("load roles: one Neptune role per canonical label, CSV only; vocabulary names") {
    assertEquals(LoadRoles.vertexRole(NodeLabel.AuthorMention), "author-mention-vertices")
    assertEquals(LoadRoles.edgeRole(EdgeLabel.ResolvesTo), "resolves-to-edges")
    val neptune = LoadRoles.all.filter(_.target == TargetStore.NeptuneBulkLoad)
    assertEquals(neptune.size, CanonicalGraph.NodeLabels.size + CanonicalGraph.EdgeLabels.size)
    assert(neptune.forall(_.formats == Set(PartFormat.Csv)))
    assertEquals(LoadRoles.all.map(r => (r.target, r.role)).distinct.size, LoadRoles.all.size)
    assert(LoadRoles.all.forall(r => SchemaVersions.all.values.exists(_ == r.recordSchemaVersion)))
    assert(LoadRoles.all.forall(r => StableId.isToken(r.role)))
  }
}
