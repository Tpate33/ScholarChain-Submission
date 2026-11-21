package scholarchain.storage

import scholarchain.core.*
import scholarchain.core.config.AnalysisRunSettings
import scholarchain.storage.fake.InMemoryKeyValueStore
import scholarchain.storage.load.*
import scholarchain.storage.model.{Bytes, StoreError}
import scholarchain.storage.port.KeyValueReader
import Fixtures.*

/**
 * SE-P7-06 items 4, 5 (gate G-ID, G-EV): [[GraphParity]] and [[EvidenceTrace]] on the decoded real
 * fixture, unchanged (nothing found) and with one fact changed (each change found). The verifier
 * runs these same functions in step 12 ([[RecordChecksSuite]]).
 */
class EvidenceTraceSuite extends munit.FunSuite {

  private lazy val verified: VerifiedRun = LoadVerifier
    .verify(
      source(runDir(run1Root)),
      source(loadDir(run1Root), loadBounds),
      KeyValueReader.readOnly(new InMemoryKeyValueStore(100))
    )
    .verified
    .getOrElse(fail("verified"))

  private lazy val graph: NeptuneGraph =
    NeptuneRows
      .parse(
        verified.load(TargetStore.NeptuneBulkLoad).get,
        Fixtures.RunId,
        p => Right(Bytes.of(bytes(runDir(run1Root).resolve(p))))
      )
      .fold(ps => fail(ps.toString), identity)

  private lazy val outputs: AnalysisOutputs = {
    val out = ParquetChecks.run(
      Fixtures.RunId,
      verified.runManifest,
      verified.loads,
      Some(graph),
      (it: LoadItem) =>
        Right(Bytes.of(bytes(runDir(run1Root).resolve(it.path)))): Either[StoreError, Bytes],
      RunSettingsFixture.context
    )
    assertEquals(out.rejections, Vector.empty)
    ParquetChecks.outputsOf(out.parts)
  }

  private def found(ps: Vector[(String, String)], fragment: String): Unit =
    assert(
      ps.exists(p => (p._1 + " " + p._2).contains(fragment)),
      s"'$fragment' in:\n${ps.take(10).mkString("\n")}"
    )

  // ------------------------------------------------------------------ evidence-trace

  test("the fixture's evidence traces from both sides: CSV and graph-edges records") {
    assertEquals(outputs.edges.size, 259)
    assertEquals(
      EvidenceTrace.check(EvidenceTrace.fromCsv(graph), outputs.edges, Vector.empty),
      Vector.empty
    )
    assertEquals(
      EvidenceTrace.check(
        EvidenceTrace.fromRecords(outputs.nodes, outputs.graphEdges),
        outputs.edges,
        outputs.paths
      ),
      Vector.empty
    )
    val facts = EvidenceTrace.fromCsv(graph)
    assertEquals(facts.coauthored.size, outputs.edges.size, "one COAUTHORED_WITH per coauthor edge")
  }

  test("a broken evidence pointer is refused") {
    val facts = EvidenceTrace.fromCsv(graph)
    val c = facts.coauthored.head
    val broken = c.copy(sourceEvidence = Some("coauthor:" + "0" * 64))
    val ps = EvidenceTrace.check(
      facts.copy(coauthored = broken +: facts.coauthored.tail),
      outputs.edges,
      Vector.empty
    )
    found(ps, s"${c.subject} broken evidence pointer: coauthor:${"0" * 64}")
    found(ps, s"edges[${c.sourceEvidence.get}] no COAUTHORED_WITH edge cites this coauthor edge")
  }

  test("a missing pointer, another pair, another count or another window is refused") {
    val facts = EvidenceTrace.fromCsv(graph)
    val c = facts.coauthored.head
    def with1(x: EvidenceTrace.Coauthored) =
      EvidenceTrace.check(
        facts.copy(coauthored = x +: facts.coauthored.tail),
        outputs.edges,
        Vector.empty
      )
    found(with1(c.copy(sourceEvidence = None)), "no sourceEvidence pointer")
    found(with1(c.copy(fromId = c.toId, toId = c.fromId)), "coauthor edge")
    val n = c.properties("jointPublicationCount").toInt
    found(
      with1(c.copy(properties = c.properties.updated("jointPublicationCount", (n + 1).toString))),
      "jointPublicationCount"
    )
    found(
      with1(c.copy(properties = c.properties.updated("windowStartYear", "1971"))),
      "windowStartYear"
    )
  }

  test("a supporting work without an AUTHORED edge from an endpoint is refused") {
    val facts = EvidenceTrace.fromCsv(graph)
    val e = outputs.edges.head
    val w = e.supportingWorkIds.head
    val ps = EvidenceTrace.check(
      facts.copy(authored = facts.authored - ((e.researcherA, w))),
      outputs.edges,
      Vector.empty
    )
    found(ps, s"supporting work $w has no AUTHORED edge from ${e.researcherA}")
    val ps2 = EvidenceTrace.check(facts.copy(works = facts.works - w), outputs.edges, Vector.empty)
    found(ps2, s"supporting work $w is not a Work vertex of the run")
  }

  test("an evidence-path hop that cites a missing edge or a non-supporting work is refused") {
    val facts = EvidenceTrace.fromRecords(outputs.nodes, outputs.graphEdges)
    val p = outputs.paths.find(_.hops.nonEmpty).getOrElse(fail("a path with hops"))
    val h = p.hops.head
    val missing = p.copy(hops = h.copy(edgeId = "coauthor:" + "0" * 64) +: p.hops.tail)
    found(
      EvidenceTrace.check(facts, outputs.edges, Vector(missing)),
      "resolves to no coauthor-edge record"
    )
    val notSupporting =
      p.copy(hops = h.copy(evidenceWorkIds = h.evidenceWorkIds :+ "work:x") +: p.hops.tail)
    found(
      EvidenceTrace.check(facts, outputs.edges, Vector(notSupporting)),
      "work:x is not a supporting work"
    )
  }

  test("a coauthor edge cited twice is refused") {
    val facts = EvidenceTrace.fromCsv(graph)
    val ps = EvidenceTrace.check(
      facts.copy(coauthored = facts.coauthored.head +: facts.coauthored),
      outputs.edges,
      Vector.empty
    )
    found(ps, "cited by 2 COAUTHORED_WITH edges")
  }

  // ------------------------------------------------------------------ graph-id-parity

  private val settings = RunSettingsFixture.settings

  test("the fixture's Neptune CSV equals its graph-nodes and graph-edges records") {
    assertEquals((outputs.nodes.size, outputs.graphEdges.size), (459, 799))
    assertEquals(
      GraphParity.check(graph, outputs.nodes, outputs.graphEdges, settings),
      Vector.empty
    )
  }

  test("a record missing on one side, or another property, is refused") {
    val n = outputs.nodes.head
    found(
      GraphParity.check(graph, outputs.nodes.tail, outputs.graphEdges, settings),
      s"vertex ${n.nodeId} has no graph-nodes record"
    )
    val e = outputs.graphEdges.head
    found(
      GraphParity.check(graph, outputs.nodes, outputs.graphEdges.tail, settings),
      s"edge ${e.edgeId} has no graph-edges record"
    )
    val changed = n.copy(properties = n.properties.updated(n.properties.keys.head, "changed"))
    found(
      GraphParity.check(graph, changed +: outputs.nodes.tail, outputs.graphEdges, settings),
      s"properties differ from the graph-nodes record ${n.nodeId}"
    )
    val g2 = graph.copy(edges = graph.edges.tail)
    found(
      GraphParity.check(g2, outputs.nodes, outputs.graphEdges, settings),
      s"graph-edges[${graph.edges.head.id}] no Neptune edge row"
    )
  }

  test("records of another graph policy are refused") {
    val other: AnalysisRunSettings = AnalysisRunSettings
      .from(
        RunSettingsFixture.config
          .copy(graph = RunSettingsFixture.config.graph.copy(policyVersion = "sc-graph.v9"))
      )
      .fold(e => fail(e.toString), identity)
    found(
      GraphParity.check(graph, outputs.nodes, outputs.graphEdges, other),
      "policyVersion is not the configured sc-graph.v9"
    )
  }
}
