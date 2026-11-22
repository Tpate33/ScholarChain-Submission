package scholarchain.rag.agents

import scala.collection.immutable.SortedMap
import scala.collection.mutable

import scholarchain.core.*
import scholarchain.core.config.RuntimeConfig
import scholarchain.storage.fake.{InMemoryGraphStore, InMemoryKeyValueStore}
import scholarchain.storage.model.*
import scholarchain.storage.port.GraphReader

/** A clock that advances `stepMillis` on every monotonic read (deterministic budgets). */
final class StepClock(stepMillis: Long, epoch: Long = 1800000000L) extends AgentClock {
  private var now = 0L
  def monotonicNanos(): Long = { val t = now; now += stepMillis * 1000000L; t }
  def epochSeconds(): Long = epoch
}

/** A graph reader that records every bound query it receives (template and typed parameters). */
final class RecordingGraph(inner: GraphReader) extends GraphReader {
  val seen: mutable.ArrayBuffer[BoundQuery] = mutable.ArrayBuffer.empty
  def run(query: BoundQuery) = { seen += query; inner.run(query) }
}

/**
 * Hand-built test world (unit inputs, not the local corpus): five researchers, three coauthor edges
 * in the window 2018-2022 (Ada-Bob 2 joint works, Bob-Cyd 1, Cyd-Dan 1), Eve isolated; communities
 * {Ada, Bob}, {Cyd, Dan}, {Eve}; components {Ada, Bob, Cyd, Dan}, {Eve}. Names: Ada Lovelace ->
 * Ada; John Smith -> Bob (Stanford University) and Cyd (University of Toronto).
 */
object AgentWorld {
  val run: String = "run:" + "a" * 64
  val otherRun: String = "run:" + "0" * 64
  val load: String = "load:" + "9" * 64
  val graphPolicy = "graph-policy.v1"
  val window: TimeWindow = TimeWindow(2018, 2022)
  val windowKeyText = "2018-2022"
  val serving: ServingContext = ServingContext.of(run, "analysis-2026").toOption.get

  val lineage: AnalysisLineage = AnalysisLineage(
    "run:" + "b" * 64,
    "run:" + "c" * 64,
    "run:" + "d" * 64,
    "e" * 40,
    "f" * 64,
    "er-grouping.v1",
    "er-policy.v8",
    "v1",
    GroupingEvaluation.Unevaluated
  )

  def prov(subject: String, runId: String = run): Provenance =
    Provenance(
      SourceType.Derived,
      subject,
      "analysis-2026",
      runId,
      SchemaVersions.ResearcherAssignment,
      Some(graphPolicy),
      None,
      None
    )

  def hex(seed: String): String = Canonical.sha256HexUtf8(seed)

  private def researcher(anchor: String) =
    ResearcherId.scholarChain("v1", "dblp-pid", anchor).toOption.get

  val ada: String = researcher("11/1")
  val bob: String = researcher("22/2")
  val cyd: String = researcher("33/3")
  val dan: String = researcher("44/4")
  val eve: String = researcher("55/5")
  val ghost: String = researcher("66/6") // linked by name, absent from the graph

  val c1: String = "community:" + hex("c1")
  val c2: String = "community:" + hex("c2")
  val c3: String = "community:" + hex("c3")
  val k1: String = "component:" + hex("k1")
  val k2: String = "component:" + hex("k2")

  def work(n: Int): String = s"work:dblp:conf/test/W$n"

  val stanford = "institution:openalex:I97018004"
  val toronto = "institution:openalex:I185261750"

  def defaultLimits(
      maxResults: Int = 50,
      maxPathHops: Int = 4,
      timeoutMillis: Long = 100000L,
      maxGraphCalls: Int = 200,
      maxKeyValueCalls: Int = 200,
      maxVisited: Int = 1000,
      maxCandidates: Int = 5,
      maxContextRows: Int = 10,
      maxQuestionBytes: Int = 2048,
      maxNames: Int = 4
  ): AgentLimits =
    AgentLimits
      .of(
        RuntimeConfig(maxPathHops, 10, TimeWindow(2018, 2022)),
        maxResults,
        timeoutMillis,
        maxGraphCalls,
        maxKeyValueCalls,
        maxVisited,
        maxCandidates,
        maxContextRows,
        maxQuestionBytes,
        maxNames
      )
      .fold(e => throw new IllegalArgumentException(e), identity)

  def resolvedIdentity(n: Int): IdentityConfidence =
    IdentityConfidence(
      IdentityStatus.Resolved,
      "dblp-pid",
      n,
      Vector.empty,
      DecisionConfidence.measured(0.93)
    )

  val singletonIdentity: IdentityConfidence = IdentityConfidence(
    IdentityStatus.Singleton,
    "dblp-pid",
    1,
    Vector.empty,
    DecisionConfidence.unknown(ConfidenceUnknownReason.NoSupportingDecision)
  )

  /** P_r, C_r, T_r (no unresolved), M_r; A_r and W_r follow the contract. */
  def metrics(
      id: String,
      p: Long,
      c: Long,
      t: Long,
      m: Long,
      identity: IdentityConfidence,
      runId: String = run
  ) =
    ResearcherMetricsV2(
      SchemaVersions.ResearcherMetricsV2,
      id,
      window,
      p,
      c,
      t,
      0L,
      0L,
      ResearcherMetricsV2.average(t, p),
      m,
      Vector(work(1)),
      t,
      identity,
      lineage,
      prov(id, runId)
    )
}

/** One fresh world: graph, key-value store and the helpers that write them. */
final class AgentWorld(val limits: AgentLimits = AgentWorld.defaultLimits()) {
  import AgentWorld.*

  val graphStore = new InMemoryGraphStore
  val graph = new RecordingGraph(graphStore)
  val kv = new InMemoryKeyValueStore(1000)
  val clock = new StepClock(0L)

  private val gprov = GraphProvenance(run, load, "graph/part-00000.csv", SchemaVersions.GraphNodeV2)

  def vertex(id: String, label: NodeLabel, props: (String, GraphValue)*): Unit = {
    val w = BoundGraphWrite.vertex(StoredVertex(id, label, SortedMap(props*), gprov))
    assert(
      graphStore.write(w.fold(e => throw new IllegalStateException(e.message), identity)).isRight,
      id
    )
  }

  def edge(label: EdgeLabel, from: String, to: String, props: (String, GraphValue)*): Unit = {
    val id = "gedge:" + hex(s"$label $from $to")
    val w = BoundGraphWrite.edge(StoredEdge(id, label, from, to, SortedMap(props*), gprov))
    val out = graphStore.write(w.fold(e => throw new IllegalStateException(e.message), identity))
    assert(out.isRight, s"$id: $out")
  }

  def researcherVertex(id: String, status: String = "resolved", members: Long = 2L): Unit =
    vertex(
      id,
      NodeLabel.Researcher,
      "anchorNamespace" -> GraphValue.Str("dblp-pid"),
      "identityStatus" -> GraphValue.Str(status),
      "memberCount" -> GraphValue.IntValue(members),
      "runId" -> GraphValue.Str(run)
    )

  def row(
      role: String,
      subject: String,
      json: String,
      schema: String,
      group: Option[String] = None,
      pair: Option[Vector[String]] = None,
      rank: Option[Int] = None,
      runId: String = run,
      keyedSubject: Option[String] = None,
      windowText: String = windowKeyText
  ): Unit = {
    val r = AnalysisResultRecord(
      AnalysisResultRecord.SchemaVersion,
      role,
      AnalysisResultRecord.SubjectScope,
      keyedSubject.getOrElse(subject),
      windowText,
      None,
      json,
      Canonical.sha256HexUtf8(json),
      schema,
      group,
      pair,
      rank,
      runId,
      load,
      s"dynamodb/$role/part-00000.parquet"
    )
    val item = ItemCodecs.AnalysisResultCodec
      .encode(r)
      .fold(e => throw new IllegalStateException(e.message), identity)
    assert(kv.putIfAbsent(TableRole.ResearcherMetricCache, item).isRight, s"$role $subject")
  }

  def put[A](
      a: A,
      role: String,
      subject: String,
      schema: String,
      group: Option[String] = None,
      rank: Option[Int] = None,
      windowText: String = windowKeyText
  )(using c: RecordContract[A]): Unit = {
    val errors = c.validate(a)
    assert(errors.isEmpty, s"fixture $role $subject invalid: ${errors.map(_.message)}")
    row(role, subject, Json.write(a), schema, group = group, rank = rank, windowText = windowText)
  }

  def link(
      name: String,
      researcherId: String,
      rank: Int,
      runId: String = run,
      expires: Long = 1900000000L
  ): Unit = {
    val rec = EntityLinkRecord(
      EntityLinkRecord.SchemaVersion,
      SurfaceForms.sha256(name),
      researcherId,
      "exact-name",
      rank,
      runId,
      expires
    )
    val item = ItemCodecs.EntityLinkCodec
      .encode(rec)
      .fold(e => throw new IllegalStateException(e.message), identity)
    assert(kv.putIfAbsent(TableRole.EntityLinkCache, item).isRight)
  }

  /** The coauthor edge record of a pair, its graph edge, and its analysis row. */
  def coauthor(
      a: String,
      b: String,
      years: Vector[YearCount],
      works: Vector[String],
      graphWeight: Option[Long] = None
  ): CoauthorEdgeV2 = {
    val e = CoauthorEdgeV2
      .build(a, b, window, years, works, lineage, graphPolicy, id => prov(id))
      .fold(x => throw new IllegalStateException(x.map(_.message).mkString("; ")), identity)
    edge(
      EdgeLabel.CoauthoredWith,
      e.researcherA,
      e.researcherB,
      "firstCollaborationYear" -> GraphValue.IntValue(e.firstCollaborationYear.toLong),
      "jointPublicationCount" -> GraphValue.IntValue(
        graphWeight.getOrElse(e.jointPublicationCount)
      ),
      "lastCollaborationYear" -> GraphValue.IntValue(e.lastCollaborationYear.toLong),
      "windowEndYear" -> GraphValue.IntValue(window.endYear.toLong),
      "windowStartYear" -> GraphValue.IntValue(window.startYear.toLong),
      "sourceEvidence" -> GraphValue.Str(e.edgeId),
      "runId" -> GraphValue.Str(run)
    )
    row(
      AnalysisRoles.CoauthorEdges,
      e.edgeId,
      Json.write(e),
      SchemaVersions.CoauthorEdgeV2,
      pair = Some(Vector(e.researcherA, e.researcherB))
    )
    e
  }

  def community(
      id: String,
      communityId: String,
      size: Long,
      componentId: String
  ): CommunityMembership =
    CommunityMembership(
      SchemaVersions.CommunityMembership,
      id,
      window,
      communityId,
      size,
      componentId,
      "label-propagation",
      SortedMap("maxIterations" -> "10"),
      lineage,
      prov(id)
    )

  def component(id: String, componentId: String, size: Long): ComponentMembership =
    ComponentMembership(
      SchemaVersions.ComponentMembership,
      id,
      window,
      componentId,
      size,
      lineage,
      prov(id)
    )

  def bridge(
      id: String,
      communityId: String,
      degree: Long,
      cross: Long,
      reached: Long,
      sumSquares: Long,
      score: Double
  ): BridgeMeasure =
    BridgeMeasure(
      SchemaVersions.BridgeMeasure,
      id,
      window,
      communityId,
      "sampled-betweenness",
      SortedMap("sampleSize" -> "10"),
      score,
      degree,
      cross,
      reached,
      BridgeMeasure.participation(degree, sumSquares),
      lineage,
      prov(id)
    )

  def coreness(id: String, k: Long, degree: Long): Coreness =
    Coreness(SchemaVersions.Coreness, id, window, k, degree, lineage, prov(id))

  /** The standard world. */
  def standard(): AgentWorld = {
    val R = AnalysisRoles
    val S = SchemaVersions
    Vector(ada, bob, cyd, dan).foreach(researcherVertex(_))
    researcherVertex(eve, "singleton", 1L)
    vertex(
      stanford,
      NodeLabel.Institution,
      "name" -> GraphValue.Str("Stanford University"),
      "runId" -> GraphValue.Str(run)
    )
    vertex(
      toronto,
      NodeLabel.Institution,
      "name" -> GraphValue.Str("University of Toronto"),
      "runId" -> GraphValue.Str(run)
    )
    val m1 = "mention:" + hex("m1")
    val m2 = "mention:" + hex("m2")
    edge(
      EdgeLabel.AffiliatedWith,
      bob,
      stanford,
      "sourceEvidence" -> GraphValue.Str(m1),
      "runId" -> GraphValue.Str(run)
    )
    edge(
      EdgeLabel.AffiliatedWith,
      cyd,
      toronto,
      "sourceEvidence" -> GraphValue.Str(m2),
      "runId" -> GraphValue.Str(run)
    )

    coauthor(ada, bob, Vector(YearCount(2019, 1), YearCount(2021, 1)), Vector(work(1), work(2)))
    coauthor(bob, cyd, Vector(YearCount(2020, 1)), Vector(work(3)))
    coauthor(cyd, dan, Vector(YearCount(2022, 1)), Vector(work(4)))

    // researcher metrics, ranked by C_r desc, P_r desc, ID asc (as the loader ranks them)
    val ms = Vector(
      metrics(ada, 2, 1, 2, 1, resolvedIdentity(2)),
      metrics(bob, 3, 2, 3, 1, resolvedIdentity(3)),
      metrics(cyd, 2, 2, 2, 1, resolvedIdentity(2)),
      metrics(dan, 1, 1, 1, 1, resolvedIdentity(2)),
      metrics(eve, 1, 0, 0, 0, singletonIdentity)
    )
    val ranked = ms.sortWith { (a, b) =>
      if (a.distinctCoauthorCount != b.distinctCoauthorCount)
        a.distinctCoauthorCount > b.distinctCoauthorCount
      else if (a.publicationCount != b.publicationCount) a.publicationCount > b.publicationCount
      else Canonical.compareUtf8(a.researcherId, b.researcherId) < 0
    }
    ranked.zipWithIndex.foreach { case (m, i) =>
      put(m, R.ResearcherMetrics, m.researcherId, S.ResearcherMetricsV2, rank = Some(i + 1))
    }

    Vector(
      ada -> (c1, 2L, k1),
      bob -> (c1, 2L, k1),
      cyd -> (c2, 2L, k1),
      dan -> (c2, 2L, k1),
      eve -> (c3, 1L, k2)
    )
      .foreach { case (id, (cm, size, comp)) =>
        put(
          community(id, cm, size, comp),
          R.CommunityMemberships,
          id,
          S.CommunityMembership,
          group = Some(cm)
        )
      }
    Vector(ada -> (k1, 4L), bob -> (k1, 4L), cyd -> (k1, 4L), dan -> (k1, 4L), eve -> (k2, 1L))
      .foreach { case (id, (comp, size)) =>
        put(
          component(id, comp, size),
          R.ComponentMemberships,
          id,
          S.ComponentMembership,
          group = Some(comp)
        )
      }
    // bridges, ranked by cross-community degree desc, communities reached desc, ID asc
    val bs = Vector(
      bridge(bob, c1, 2, 1, 1, 2, 0.5),
      bridge(cyd, c2, 2, 1, 1, 2, 0.5),
      bridge(ada, c1, 1, 0, 0, 1, 0.0),
      bridge(dan, c2, 1, 0, 0, 1, 0.0)
    )
    val rankedB = bs.sortWith { (a, b) =>
      if (a.crossCommunityDegree != b.crossCommunityDegree)
        a.crossCommunityDegree > b.crossCommunityDegree
      else if (a.communitiesReached != b.communitiesReached)
        a.communitiesReached > b.communitiesReached
      else Canonical.compareUtf8(a.researcherId, b.researcherId) < 0
    }
    rankedB.zipWithIndex.foreach { case (b, i) =>
      put(b, R.BridgeMeasures, b.researcherId, S.BridgeMeasure, rank = Some(i + 1))
    }
    Vector(bob -> (1L, 2L), cyd -> (1L, 2L), ada -> (1L, 1L), dan -> (1L, 1L)).zipWithIndex
      .foreach { case ((id, (k, d)), i) =>
        put(coreness(id, k, d), R.Coreness, id, S.Coreness, rank = Some(i + 1))
      }

    link("Ada Lovelace", ada, 1)
    link("John Smith", bob, 1)
    link("John Smith", cyd, 2)
    this
  }

  def resolver = new ResearcherResolverAgent(graph, kv, serving, limits, clock)
  def metricsAgent = new CollaborationMetricsAgent(kv, serving, limits, clock)
  def pathFinder(c: AgentClock = clock) = new PathFinderAgent(graph, kv, serving, limits, c)
  def communities = new CommunityAnalystAgent(graph, kv, serving, limits, clock)
}
