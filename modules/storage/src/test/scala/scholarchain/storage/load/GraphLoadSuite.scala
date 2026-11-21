package scholarchain.storage.load

import java.nio.file.Path

import scala.collection.immutable.SortedMap

import scholarchain.core.{EdgeLabel, NodeLabel, SchemaVersions, TargetStore}
import scholarchain.storage.Fixtures.*
import scholarchain.storage.fake.*
import scholarchain.storage.model.*
import scholarchain.storage.port.KeyValueReader

/**
 * SE-P7-05 B4, B7 (checklist N5, L2, L3, G-ID; risk T2): the Neptune CSV of the real fixture loaded
 * into the in-memory graph through the gate, the permit and the write templates only.
 *
 * Stated plainly: this suite is an IN-PACKAGE GATE-MECHANICS suite. It verifies WITHOUT a
 * verification context (steps 1-11), so graph-id-parity and evidence-trace are open, and marks
 * exactly those two checks passed by hand to exercise the writer. It is not evidence of a complete
 * load. SE-P7-06: the production proof (every Neptune check performed by the verifier with a
 * context, no hand-marked check) is `scholarchain.storage.RecordChecksSuite`, item 8.
 */
class GraphLoadSuite extends munit.FunSuite {

  private val Neptune = TargetStore.NeptuneBulkLoad

  /**
   * The Neptune checks open without a verification context (marked by this test only; SE-P7-07 adds
   * analysis-recompute and canonical-tables, which need the upstream runs).
   */
  private val NotYetPerformed: Set[DeferredCheck] =
    Set(
      DeferredCheck.GraphIdParity,
      DeferredCheck.EvidenceTrace,
      DeferredCheck.AnalysisRecompute,
      DeferredCheck.CanonicalTables
    )

  private def verifiedReport(run: Path, load: Path): VerificationReport = {
    val r = LoadVerifier.verify(
      source(run),
      source(load, loadBounds),
      KeyValueReader.readOnly(new InMemoryKeyValueStore(100))
    )
    assertEquals(r.rejections, Vector.empty)
    assert(!r.acceptedFor(Neptune), "production: the gate refuses neptune-bulk-load")
    assertEquals(r.outstandingFor(Neptune).toSet, NotYetPerformed)
    r
  }

  /** The production report plus the two not-yet-performed checks, for Neptune only. */
  private def passing(run: Path, load: Path): VerificationReport = {
    val r = verifiedReport(run, load)
    val v = r.verified.get
    val marked = new VerifiedRun(
      v.runId,
      v.partManifestSha256,
      v.partManifest,
      v.runManifest,
      v.loads,
      v.passedChecks + (Neptune -> (v.passedFor(Neptune) ++ NotYetPerformed)),
      source(run),
      v.partManifestSchemaVersion
    )
    val p = r.copy(verified = Some(marked))
    assert(p.acceptedFor(Neptune))
    TargetStore.values.filterNot(_ == Neptune).foreach(t => assert(!p.acceptedFor(t), t.tag))
    p
  }

  private final class World {
    val graph = new InMemoryGraphStore
    val kv = new RecordingKeyValueStore(new InMemoryKeyValueStore(100))
    val clock = new ManualClock(1_700_000_000_000L)
  }

  private def load(
      report: VerificationReport,
      w: World,
      writer: scholarchain.storage.port.GraphWriter
  ): Either[StoreError, GraphLoadReport] =
    LoadGate.proceed(report, Neptune)(p => GraphLoad.load(p, writer, w.kv, w.clock)) match {
      case Left(rs) => fail(s"gate refused: $rs")
      case Right(out) => out
    }

  private lazy val fixtureReport = passing(runDir(run1Root), loadDir(run1Root))
  private lazy val fixtureGraph: NeptuneGraph = {
    val v = fixtureReport.verified.get
    NeptuneRows
      .parse(v.load(Neptune).get, v.runId, v.readVerified)
      .fold(ps => fail(ps.mkString("\n")), identity)
  }
  private def neptuneLoadId = fixtureReport.verified.get.load(Neptune).get.loadId

  private def ledgerEntry(w: World, loadId: String): LoadEntry =
    (for {
      k <- TableKeys.RunLedger.load(RunId, loadId)
      i <- w.kv.get(TableRole.RunLedger, k)
      e <- ItemCodecs.LoadEntryCodec.decode(i.get)
    } yield e).fold(e => fail(e.toString), identity)

  // ------------------------------------------------------------------ CR-P7-09 M1

  test("CR-P7-09 M1: another run's vertex under a LATE ID refuses the load with zero writes") {
    val w = new World
    val (vs, _) = LoadGate
      .proceed(fixtureReport, Neptune)(GraphLoad.elements)
      .toOption
      .get
      .fold(e => fail(e.toString), identity)
    val last = vs.last
    val foreign = last.copy(provenance =
      last.provenance.copy(
        runId = scholarchain.storage.TestData.otherRunId,
        loadId = scholarchain.storage.TestData.otherLoadId
      )
    )
    assertEquals(
      BoundGraphWrite.vertex(foreign).flatMap(w.graph.write),
      Right(PutOutcome.Written)
    )
    val g = new RecordingGraphWriter(w.graph)
    load(fixtureReport, w, g) match {
      case Left(StoreError.Conflict(d)) => assert(d.contains("pre-write probe"), d)
      case x => fail(s"expected Conflict, got $x")
    }
    assertEquals(g.counts.calls, 0, "no graph write was attempted")
    assertEquals(w.graph.vertexIds, Set(last.id))
    assertEquals(w.graph.vertex(last.id), Some(foreign), "the other run's vertex is intact")
    assertEquals(ledgerEntry(w, neptuneLoadId).state, LoadState.Failed)
  }

  // ------------------------------------------------------------------ the fixture load

  test("B7: the fixture loads; vertex and edge ID sets equal the CSV ID sets (459, 799)") {
    val w = new World
    val g = new RecordingGraphWriter(w.graph)
    val out = load(fixtureReport, w, g).fold(e => fail(e.toString), identity)
    assertEquals((out.vertices, out.edges), (459, 799))
    assertEquals(
      (out.written, out.alreadyPresent, out.finalState, out.skipped),
      (1258L, 0L, LoadState.Loaded, false)
    )
    assertEquals(w.graph.vertexIds, fixtureGraph.vertices.map(_.id).toSet)
    assertEquals(w.graph.edgeIds, fixtureGraph.edges.map(_.id).toSet)
    assertEquals((w.graph.vertexIds.size, w.graph.edgeIds.size), (459, 799))
    assertEquals(w.graph.edges.map(_.label).toSet.size, 6)
    assertEquals(g.counts.written, 1258)
    // Endpoints copied verbatim.
    val byId = fixtureGraph.edges.map(e => e.id -> e).toMap
    w.graph.edges.foreach { e =>
      assertEquals(
        (e.fromId, e.toId, e.label),
        (byId(e.id).fromId, byId(e.id).toId, byId(e.id).label)
      )
    }
    assertEquals(ledgerEntry(w, neptuneLoadId).state, LoadState.Loaded)
  }

  test("L3: every stored vertex and edge keeps run, load, source path and schema version") {
    val w = new World
    load(fixtureReport, w, w.graph).fold(e => fail(e.toString), identity)
    val items = fixtureReport.verified.get.load(Neptune).get.items.map(i => i.path -> i).toMap
    def check(p: GraphProvenance, label: String): Unit = {
      assertEquals(p.runId, RunId)
      assertEquals(p.loadId, neptuneLoadId)
      assert(items.contains(p.sourcePath), p.sourcePath)
      assertEquals(p.recordSchemaVersion, items(p.sourcePath).recordSchemaVersion)
      assert(p.sourcePath.startsWith(label), s"${p.sourcePath} is a $label part")
    }
    w.graph.vertices.foreach(v =>
      check(v.provenance, scholarchain.core.LoadRoles.vertexRole(v.label))
    )
    w.graph.edges.foreach(e => check(e.provenance, scholarchain.core.LoadRoles.edgeRole(e.label)))
    assertEquals(
      (w.graph.vertices.map(_.provenance.recordSchemaVersion) ++ w.graph.edges
        .map(_.provenance.recordSchemaVersion)).toSet,
      Set(SchemaVersions.GraphNodeV2, SchemaVersions.GraphEdgeV2)
    )
  }

  test("N5: only allowlisted templates ran, one per element; no text holds data") {
    val w = new World
    load(fixtureReport, w, w.graph).fold(e => fail(e.toString), identity)
    val allowed = GraphWriteTemplates.all.map(_.text).toSet
    assertEquals(w.graph.executedTemplates.size, 1258)
    assert(w.graph.executedTemplates.forall(allowed.contains))
    assertEquals(GraphWriteTemplates.all.size, 12)
    w.graph.executedTemplates.distinct.foreach { t =>
      assert(!t.contains("researcher:") && !t.contains("work:") && !t.contains("run:"), t)
    }
  }

  // ------------------------------------------------------------------ idempotency (L2)

  test("L2: a rerun with the same ledger is skipped with zero writes; the graph is unchanged") {
    val w = new World
    load(fixtureReport, w, w.graph).fold(e => fail(e.toString), identity)
    val (vs, es) = (w.graph.vertices, w.graph.edges)
    val kvWrittenBefore = w.kv.counts.written
    val g = new RecordingGraphWriter(w.graph)
    val again = load(fixtureReport, w, g).fold(e => fail(e.toString), identity)
    assertEquals((again.skipped, again.written, again.alreadyPresent), (true, 0L, 0L))
    assertEquals(g.counts.calls, 0, "no graph write was attempted")
    assertEquals(w.kv.counts.written, kvWrittenBefore, "no new ledger row")
    assertEquals((w.graph.vertices, w.graph.edges), (vs, es))
  }

  test("L2: a rerun against a fresh ledger writes nothing new: every element is already present") {
    val w = new World
    load(fixtureReport, w, w.graph).fold(e => fail(e.toString), identity)
    val (vs, es) = (w.graph.vertices, w.graph.edges)
    val w2 = new World
    val g = new RecordingGraphWriter(w.graph)
    val again =
      LoadGate
        .proceed(fixtureReport, Neptune)(p => GraphLoad.load(p, g, w2.kv, w2.clock))
        .toOption
        .get
        .fold(e => fail(e.toString), identity)
    assertEquals((again.written, again.alreadyPresent), (0L, 1258L))
    assertEquals((g.counts.written, g.counts.alreadyPresent), (0, 1258))
    assertEquals((w.graph.vertices, w.graph.edges), (vs, es))
  }

  test("a different element under an existing ID is a Conflict, never an overwrite") {
    val w = new World
    val target = fixtureGraph.vertices.find(_.label == NodeLabel.Venue).get
    val existing = StoredVertex(
      target.id,
      NodeLabel.Venue,
      SortedMap("name" -> GraphValue.Str("Another name"), "runId" -> GraphValue.Str(RunId)),
      GraphProvenance(
        RunId,
        neptuneLoadId,
        "venue-vertices/part-00000.csv",
        SchemaVersions.GraphNodeV2
      )
    )
    assertEquals(BoundGraphWrite.vertex(existing).flatMap(w.graph.write), Right(PutOutcome.Written))
    load(fixtureReport, w, w.graph) match {
      case Left(StoreError.Conflict(d)) => assert(d.contains(target.id), d)
      case other => fail(s"expected Conflict, got $other")
    }
    assertEquals(w.graph.vertex(target.id), Some(existing), "unchanged")
    assertEquals(ledgerEntry(w, neptuneLoadId).state, LoadState.Failed)
  }

  test("an interrupted load resumes without duplicates") {
    val w = new World
    val failAt = 500
    val g = new RecordingGraphWriter(
      w.graph,
      n => if (n == failAt) w.graph.failNextWrites(StoreError.Transient("throttled"))
    )
    assertEquals(load(fixtureReport, w, g), Left(StoreError.Transient("throttled")))
    assertEquals(w.graph.vertexIds.size + w.graph.edgeIds.size, failAt)
    val failed = ledgerEntry(w, neptuneLoadId)
    assertEquals((failed.state, failed.written), (LoadState.Failed, failAt.toLong))

    val resumed = load(fixtureReport, w, w.graph).fold(e => fail(e.toString), identity)
    assertEquals((resumed.written, resumed.alreadyPresent), (1258L - failAt, failAt.toLong))
    assertEquals(w.graph.vertexIds, fixtureGraph.vertices.map(_.id).toSet)
    assertEquals(w.graph.edgeIds, fixtureGraph.edges.map(_.id).toSet)
    val done = ledgerEntry(w, neptuneLoadId)
    assertEquals((done.state, done.written), (LoadState.Loaded, 1258L))
  }

  test("T2: two interleaved loaders produce one graph, no duplicates, and one loaded row") {
    val w = new World
    var second: Option[Either[StoreError, GraphLoadReport]] = None
    val b = new RecordingGraphWriter(w.graph)
    val a = new RecordingGraphWriter(
      w.graph,
      n => if (n == 300 && second.isEmpty) second = Some(load(fixtureReport, w, b))
    )
    val first = load(fixtureReport, w, a).fold(e => fail(e.toString), identity)
    val other = second.get.fold(e => fail(e.toString), identity)
    assertEquals(first.finalState, LoadState.Loaded)
    assertEquals(other.finalState, LoadState.Loaded)
    assertEquals(first.written + other.written, 1258L, "each element written exactly once")
    assertEquals(a.counts.failed + b.counts.failed, 0)
    assertEquals(w.graph.vertexIds.size + w.graph.edgeIds.size, 1258)
    assertEquals(ledgerEntry(w, neptuneLoadId).state, LoadState.Loaded)
    assertEquals(w.kv.byTable(TableRole.RunLedger) > 0, true)
  }

  test("T2: the publication row is claimed before any graph write; decided by partManifestSha256") {
    val v = fixtureReport.verified.get
    val s3LoadId = v.load(TargetStore.S3).get.loadId
    def publish(w: World, sha: String, firstLoadId: String) =
      ItemCodecs.RunPublicationCodec
        .encode(RunPublication(RunPublication.SchemaVersion, RunId, sha, firstLoadId))
        .flatMap(w.kv.putIfAbsent(TableRole.RunLedger, _))

    // Another store recorded the same publication first: not a false Conflict.
    val same = new World
    assertEquals(publish(same, v.partManifestSha256, s3LoadId), Right(PutOutcome.Written))
    assert(load(fixtureReport, same, same.graph).isRight)
    assertEquals(same.graph.vertexIds.size, 459)

    // A different _parts.json was recorded for this run ID: Conflict, zero graph writes.
    val other = new World
    assertEquals(publish(other, Run2PartsSha256, s3LoadId), Right(PutOutcome.Written))
    val g = new RecordingGraphWriter(other.graph)
    load(fixtureReport, other, g) match {
      case Left(StoreError.Conflict(d)) => assert(d.contains(Run2PartsSha256), d)
      case x => fail(s"expected Conflict, got $x")
    }
    assertEquals(g.counts.calls, 0)

    // Fresh: this load records the publication itself, first, with its own load ID.
    val fresh = new World
    var publicationBeforeFirstWrite = false
    val probe = new RecordingGraphWriter(
      fresh.graph,
      n =>
        if (n == 0)
          publicationBeforeFirstWrite = TableKeys.RunLedger
            .publication(RunId)
            .flatMap(fresh.kv.get(TableRole.RunLedger, _))
            .exists(_.isDefined)
    )
    assert(load(fixtureReport, fresh, probe).isRight)
    assert(publicationBeforeFirstWrite, "the publication row existed before the first graph write")
  }

  test("the loader refuses a permit for another store") {
    val w = new World
    val all = TargetStore.values.toVector.map(_ -> DeferredCheck.values.toSet).toMap
    val v = fixtureReport.verified.get
    val open = fixtureReport.copy(verified =
      Some(
        new VerifiedRun(
          v.runId,
          v.partManifestSha256,
          v.partManifest,
          v.runManifest,
          v.loads,
          all,
          source(runDir(run1Root)),
          v.partManifestSchemaVersion
        )
      )
    )
    val out = LoadGate.proceed(open, TargetStore.S3)(p => GraphLoad.load(p, w.graph, w.kv, w.clock))
    assertEquals(out.map(_.left.map(_.message.contains("the permit is for s3"))), Right(Left(true)))
    assertEquals(w.graph.vertexIds.size, 0)
  }

  // ------------------------------------------------------------------ injection and reads (N5)

  test("N5: an injection string in the CSV is stored as one property value, never as query text") {
    withCopy { (run, load0) =>
      val payload = "AAAI\"}) MATCH (n) DETACH DELETE n //"
      val quoted = "\"" + payload.replace("\"", "\"\"") + "\""
      resealCsv(run, load0, "venue-vertices/part-00001.csv")(s =>
        s.replace(",Venue,AAAI,", s",Venue,$quoted,")
      )
      val report = passing(run, load0)
      val w = new World
      load(report, w, w.graph).fold(e => fail(e.toString), identity)
      assertEquals(
        w.graph.vertex("venue:dblp:conf/aaai/2023").map(_.properties("name")),
        Some(GraphValue.Str(payload))
      )
      assertEquals((w.graph.vertexIds.size, w.graph.edgeIds.size), (459, 799))
      assert(!w.graph.executedTemplates.exists(_.contains("DETACH")), "no template text holds data")
      // The runtime read returns it as data too.
      val work = fixtureGraph.edges.find(e =>
        e.label == EdgeLabel.PublishedIn && e.toId == "venue:dblp:conf/aaai/2023"
      )
      work.foreach { e =>
        val q = BoundQuery
          .bind(GraphQuery.WorkVenue, Map("workId" -> GraphParamValue.Str(e.fromId)), 10)
          .fold(x => fail(x.toString), identity)
        val names =
          w.graph.run(q).map(_.map(_.columns("name"))).fold(x => fail(x.toString), identity)
        assert(names.contains(GraphValue.Str(payload)), names.toString) // a work may have 2 venues
      }
    }
  }

  test("N5: runtime templates read the loaded fixture; an injection parameter is refused at bind") {
    val w = new World
    load(fixtureReport, w, w.graph).fold(e => fail(e.toString), identity)
    val co = fixtureGraph.edges.find(_.label == EdgeLabel.CoauthoredWith).get
    def i(k: String) = co.properties(k) match {
      case GraphValue.IntValue(n) => n.toInt
      case x => fail(s"$k: $x")
    }
    val window = Map(
      "windowStartYear" -> GraphParamValue.IntValue(i("windowStartYear")),
      "windowEndYear" -> GraphParamValue.IntValue(i("windowEndYear"))
    )
    val between = BoundQuery
      .bind(
        GraphQuery.CoauthorEdgeBetween,
        window ++ Map(
          "researcherA" -> GraphParamValue.Str(co.toId),
          "researcherB" -> GraphParamValue.Str(co.fromId)
        ),
        10
      )
      .fold(x => fail(x.toString), identity)
    val rows = w.graph.run(between).fold(e => fail(e.toString), identity)
    assertEquals(rows.map(_.columns("edgeId")), Vector(GraphValue.Str(co.id)), "either direction")
    assertEquals(rows.head.columns("sourceEvidence"), co.properties("sourceEvidence"))

    val direct = BoundQuery
      .bind(
        GraphQuery.DirectCollaborators,
        window ++ Map(
          "researcherId" -> GraphParamValue.Str(co.fromId),
          "limit" -> GraphParamValue.IntValue(100)
        ),
        100
      )
      .fold(x => fail(x.toString), identity)
    assert(
      w.graph.run(direct).exists(_.exists(_.columns("collaboratorId") == GraphValue.Str(co.toId)))
    )

    val rid = BoundQuery
      .bind(GraphQuery.ResearcherById, Map("researcherId" -> GraphParamValue.Str(co.fromId)), 10)
      .fold(x => fail(x.toString), identity)
    assertEquals(w.graph.run(rid).map(_.size), Right(1))

    val res = fixtureGraph.edges.find(_.label == EdgeLabel.ResolvesTo).get
    val mention = BoundQuery
      .bind(GraphQuery.MentionResolution, Map("mentionId" -> GraphParamValue.Str(res.fromId)), 10)
      .fold(x => fail(x.toString), identity)
    assertEquals(
      w.graph.run(mention).map(_.map(_.columns("researcherId"))),
      Right(Vector(GraphValue.Str(res.toId)))
    )

    Vector(
      "researcher:orcid:0000-0003-3922-8419' OR 1=1 //",
      "researcher:orcid:0000-0003-3922-8419\"}) MATCH (n) DETACH DELETE n //",
      "x) RETURN n"
    ).foreach { evil =>
      assert(
        BoundQuery
          .bind(GraphQuery.ResearcherById, Map("researcherId" -> GraphParamValue.Str(evil)), 10)
          .isLeft,
        evil
      )
    }
  }

  test("the writer refuses a dangling edge") {
    val g = new InMemoryGraphStore
    val e = fixtureGraph.edges.find(_.label == EdgeLabel.Authored).get
    val edge = StoredEdge(
      e.id,
      e.label,
      e.fromId,
      e.toId,
      e.properties,
      GraphProvenance(RunId, neptuneLoadId, e.path, SchemaVersions.GraphEdgeV2)
    )
    BoundGraphWrite.edge(edge).flatMap(g.write) match {
      case Left(StoreError.Refused(d)) => assert(d.contains("is not a vertex"), d)
      case other => fail(s"expected Refused, got $other")
    }
    assertEquals(g.edgeIds, Set.empty[String])
  }

  test("CR-P7-07 m1: the writer refuses an edge whose endpoints exist with the wrong labels") {
    val g = new InMemoryGraphStore
    val e = fixtureGraph.edges.find(_.label == EdgeLabel.PublishedIn).get
    val byId = fixtureGraph.vertices.map(v => v.id -> v).toMap
    def stored(id: String) = {
      val v = byId(id)
      StoredVertex(
        v.id,
        v.label,
        v.properties,
        GraphProvenance(RunId, neptuneLoadId, v.path, SchemaVersions.GraphNodeV2)
      )
    }
    Vector(e.fromId, e.toId).foreach { id =>
      assertEquals(BoundGraphWrite.vertex(stored(id)).flatMap(g.write), Right(PutOutcome.Written))
    }
    // Both endpoints are vertices, but swapped: PUBLISHED_IN runs Work -> Venue.
    val swapped = StoredEdge(
      e.id,
      e.label,
      e.toId,
      e.fromId,
      e.properties,
      GraphProvenance(RunId, neptuneLoadId, e.path, SchemaVersions.GraphEdgeV2)
    )
    BoundGraphWrite.edge(swapped).flatMap(g.write) match {
      case Left(StoreError.Refused(d)) =>
        assert(d.contains(s"from endpoint ${e.toId} is a ${NodeLabel.Venue.tag} vertex"), d)
      case other => fail(s"expected Refused, got $other")
    }
    assertEquals(g.edgeIds, Set.empty[String])
  }
}
