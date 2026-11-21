package scholarchain.tests.storage

import scala.collection.immutable.SortedMap
import scala.collection.mutable

import scholarchain.core.{EdgeLabel, NodeLabel}
import scholarchain.storage.OpeningFixture
import scholarchain.storage.OpeningFixture.World
import scholarchain.storage.fake.InMemoryGraphStore
import scholarchain.storage.load.OpeningLoadResult
import scholarchain.storage.model.*
import scholarchain.storage.port.{GraphReader, RuntimeStores}

import StorageAdversarial.*

/**
 * TE-P7-01 case 4 (checklist N5): an openCypher-meaningful string stays data through the RUNTIME
 * graph reader ([[RuntimeStores.graph]]). The stored graph is the full fixture load (public
 * [[scholarchain.storage.load.OpeningLoad.run]]), copied into a fresh in-memory store through the
 * allowlisted write templates with one venue name replaced by the payload (the public load path
 * itself refuses such a CSV-only change: see GraphIdConsistencyAdversarialSuite, graph-id-parity).
 * A spy behind the runtime facade records exactly what reaches the store.
 */
class GraphInjectionAdversarialSuite extends munit.FunSuite {

  private val Payloads = Vector(
    "AAAI\"}) MATCH (n) DETACH DELETE n //",
    "x' OR 1=1 WITH n MATCH (m) SET m.name = 'pwned' RETURN m //",
    "}) RETURN 1 UNION MATCH (n) RETURN n //",
    "$researcherId",
    "`~id`: $id}) ON CREATE SET v += $properties"
  )

  private lazy val first: (World, OpeningLoadResult) = {
    val w = new World
    val res = OpeningFixture.run(w)
    assert(res.report.complete, show(res.report))
    (w, res)
  }

  /** The loaded graph copied into a fresh store, the venue `venueId` renamed to `name`. */
  private def copyWithVenueName(w: World, venueId: String, name: String): InMemoryGraphStore = {
    val g = new InMemoryGraphStore
    w.graph.store.vertices.foreach { v =>
      val v2 =
        if (v.id == venueId) v.copy(properties = v.properties.updated("name", GraphValue.Str(name)))
        else v
      assertEquals(BoundGraphWrite.vertex(v2).flatMap(g.write), Right(PutOutcome.Written), v.id)
    }
    w.graph.store.edges.foreach { e =>
      assertEquals(BoundGraphWrite.edge(e).flatMap(g.write), Right(PutOutcome.Written), e.id)
    }
    g
  }

  private final class Spy(g: GraphReader) extends GraphReader {
    val seen: mutable.ArrayBuffer[BoundQuery] = mutable.ArrayBuffer.empty
    def run(q: BoundQuery) = { seen += q; g.run(q) }
  }

  private def bind(q: GraphQuery, args: (String, GraphParamValue)*): BoundQuery =
    BoundQuery.bind(q, args.toMap, 100).fold(e => fail(e.toString), identity)

  test("an openCypher payload in a stored venue name is returned as data; the template is fixed") {
    val (w, _) = first
    val pub = w.graph.store.edges.find(_.label == EdgeLabel.PublishedIn).get
    val venue = w.graph.store.vertex(pub.toId).get
    assertEquals(venue.label, NodeLabel.Venue)
    Payloads.foreach { payload =>
      val g = copyWithVenueName(w, venue.id, payload)
      val spy = new Spy(g)
      val rt = RuntimeStores(
        w.objects,
        w.kv,
        w.kv,
        spy,
        w.vectors,
        w.vectors,
        EmbeddingPolicy.SyntheticTestOnly
      )
      val q = bind(GraphQuery.WorkVenue, "workId" -> GraphParamValue.Str(pub.fromId))
      val rows = rt.graph.run(q).fold(e => fail(e.toString), identity)
      val byVenue = rows.map(r => r.columns("venueId") -> r.columns("name")).toMap
      assertEquals(byVenue.get(GraphValue.Str(venue.id)), Some(GraphValue.Str(payload)), payload)
      // Exactly the fixed template text and exactly the one typed parameter reached the store.
      assertEquals(spy.seen.toVector.map(_.query.text), Vector(GraphQuery.WorkVenue.text))
      assertEquals(
        spy.seen.head.parameters,
        SortedMap[String, GraphParamValue]("workId" -> GraphParamValue.Str(pub.fromId))
      )
      assert(!spy.seen.head.query.text.contains(payload), payload)
      // The read changed nothing: same element sets, only the 1258 template writes of the copy.
      assertEquals((g.vertexIds.size, g.edgeIds.size), (459, 799), payload)
      assertEquals(g.executedTemplates.size, 1258, payload)
      assert(g.executedTemplates.toSet.subsetOf(GraphWriteTemplates.all.map(_.text).toSet))
      assertEquals(
        g.vertices.filter(_.id != venue.id),
        w.graph.store.vertices.filter(_.id != venue.id),
        s"$payload: no other vertex changed"
      )
      assertEquals(g.edges, w.graph.store.edges, s"$payload: no edge changed")
    }
  }

  test("the same payloads as query parameters are refused at bind; nothing reaches the store") {
    val (w, res) = first
    val spy = new Spy(w.graph)
    val rt = RuntimeStores(
      w.objects,
      w.kv,
      w.kv,
      spy,
      w.vectors,
      w.vectors,
      EmbeddingPolicy.SyntheticTestOnly
    )
    val idParams = GraphQuery.values.toVector.flatMap(q =>
      q.params.collect { case GraphParam(n, GraphParamType.Id(_)) => q -> n }
    )
    assert(idParams.size >= 9, idParams.toString)
    val researcher = w.graph.store.vertices.find(_.label == NodeLabel.Researcher).get.id
    (Payloads ++ Payloads.map(researcher + _)).foreach { payload =>
      idParams.foreach { case (q, name) =>
        val args: Map[String, GraphParamValue] = q.params.map {
          case GraphParam(n, _) if n == name => n -> GraphParamValue.Str(payload)
          case GraphParam(n, GraphParamType.Id(_)) => n -> GraphParamValue.Str(researcher)
          case GraphParam(n, GraphParamType.Year) => n -> GraphParamValue.IntValue(2020)
          case GraphParam(n, GraphParamType.Limit) => n -> GraphParamValue.IntValue(10)
        }.toMap
        BoundQuery.bind(q, args, 100) match {
          case Left(StoreError.Refused(d)) => assert(d.contains(name), s"$q $name: $d")
          case other => fail(s"$q $name accepted '$payload': $other")
        }
      }
    }
    assertEquals(spy.seen.size, 0, "no query reached the graph")
    assert(res.runtime.isDefined)
    // A string where the template declares an Int is refused too (no type coercion).
    assert(
      BoundQuery
        .bind(
          GraphQuery.WorksOfResearcher,
          Map(
            "researcherId" -> GraphParamValue.Str(researcher),
            "limit" -> GraphParamValue.Str("10 UNION MATCH (n) RETURN n")
          ),
          100
        )
        .left
        .exists(_.message.contains("wrong parameter type"))
    )
  }
}
