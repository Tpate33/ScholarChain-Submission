package scholarchain.services.localdemo

import java.nio.charset.StandardCharsets

import scala.collection.immutable.SortedMap
import scala.util.control.NonFatal

import upickle.default.ReadWriter
import upickle.implicits.allowUnknownKeys

import scholarchain.core.*
import scholarchain.rag.agents.{AnalysisRoles, SurfaceForms}
import scholarchain.storage.fake.{
  InMemoryGraphStore,
  InMemoryKeyValueStore,
  InMemoryObjectStore,
  InMemoryVectorStore
}
import scholarchain.storage.model.*
import scholarchain.storage.port.RuntimeStores

// ---------------------------------------------------------------------------------------------
// The committed seed (classpath resource scholarchain/local-demo/world.v1.json), strictly decoded.
// ---------------------------------------------------------------------------------------------

@allowUnknownKeys(false)
final case class SeedWindow(startYear: Int, endYear: Int) derives ReadWriter

@allowUnknownKeys(false)
final case class SeedResearcher(
    alias: String,
    label: String,
    dblpPid: String,
    identityStatus: String,
    vertexMemberCount: Long,
    identityMemberCount: Int,
    publications: Long,
    distinctCoauthors: Long,
    coauthorOccurrences: Long,
    maxCoauthorsOnOnePaper: Long,
    community: String,
    communitySize: Long,
    component: String,
    componentSize: Long
) derives ReadWriter

@allowUnknownKeys(false)
final case class SeedInstitution(id: String, name: String) derives ReadWriter

@allowUnknownKeys(false)
final case class SeedAffiliation(
    researcher: String,
    institution: String,
    mentionSeed: String
) derives ReadWriter

@allowUnknownKeys(false)
final case class SeedYear(year: Int, count: Long) derives ReadWriter

@allowUnknownKeys(false)
final case class SeedCoauthorship(
    a: String,
    b: String,
    years: Vector[SeedYear],
    works: Vector[Int]
) derives ReadWriter

@allowUnknownKeys(false)
final case class SeedBridge(
    researcher: String,
    community: String,
    degree: Long,
    crossCommunityDegree: Long,
    communitiesReached: Long,
    sumOfSquares: Long
) derives ReadWriter

@allowUnknownKeys(false)
final case class SeedCoreness(researcher: String, k: Long, degree: Long) derives ReadWriter

@allowUnknownKeys(false)
final case class SeedLink(name: String, researcher: String, rank: Int) derives ReadWriter

@allowUnknownKeys(false)
final case class SeedQuestion(question: String, expectedStatus: String) derives ReadWriter

@allowUnknownKeys(false)
/** The NON-PRODUCTION local demo seed (schema [[DemoSeed.SchemaVersion]]). */
final case class DemoSeed(
    schemaVersion: String,
    nonProduction: Boolean,
    description: String,
    window: SeedWindow,
    researchers: Vector[SeedResearcher],
    institutions: Vector[SeedInstitution],
    affiliations: Vector[SeedAffiliation],
    coauthorships: Vector[SeedCoauthorship],
    bridges: Vector[SeedBridge],
    coreness: Vector[SeedCoreness],
    entityLinks: Vector[SeedLink],
    exampleQuestions: Vector[SeedQuestion]
) derives ReadWriter

object DemoSeed {
  val SchemaVersion = "scholarchain.services.local-demo-world.v1"
  val Resource = "/scholarchain/local-demo/world.v1.json"

  /** Reads one committed classpath resource as UTF-8 (None when absent). */
  def resourceText(name: String): Option[String] =
    Option(getClass.getResourceAsStream(name)).map { in =>
      try new String(in.readAllBytes(), StandardCharsets.UTF_8)
      finally in.close()
    }

  /** Strict decoding: unknown keys, a wrong schema version or a missing flag fail closed. */
  def parse(json: String): Either[String, DemoSeed] =
    (try Right(upickle.default.read[DemoSeed](json))
    catch { case NonFatal(e) => Left(s"local demo seed: malformed (${e.getClass.getSimpleName})") })
      .flatMap { s =>
        val aliases = s.researchers.map(_.alias)
        val known = aliases.toSet
        val named = s.affiliations.map(_.researcher) ++ s.coauthorships.flatMap(c =>
          Vector(c.a, c.b)
        ) ++ s.bridges.map(_.researcher) ++ s.coreness.map(_.researcher) ++
          s.entityLinks.map(_.researcher)
        if (s.schemaVersion != SchemaVersion)
          Left(s"local demo seed: schemaVersion is not $SchemaVersion")
        else if (!s.nonProduction) Left("local demo seed: nonProduction must be true")
        else if (aliases.isEmpty || aliases.distinct.size != aliases.size)
          Left("local demo seed: researcher aliases must be non-empty and distinct")
        else if (!named.forall(known)) Left("local demo seed: a row names an unknown researcher")
        else Right(s)
      }

  def load(): Either[String, DemoSeed] =
    resourceText(Resource)
      .toRight(s"local demo seed: $Resource is not on the classpath")
      .flatMap(parse)
}

/**
 * NON-PRODUCTION (SV-INT-03a). The in-memory stores of the local demo, seeded from the committed
 * seed resource `scholarchain/local-demo/world.v1.json`: the hand-built Phase 9 rag fixture world
 * (rag `AgentWorld`/`WorkflowWorld`, which the services test fixture `ServiceWorld` mirrors),
 * written through storage's own codecs and bound graph writes into storage's in-memory fakes
 * (`scholarchain.storage.fake.*`). No network, no AWS SDK client. Pipeline lineage and provenance
 * are fixed synthetic values (as in the test fixture); the collaboration facts come from the seed.
 *
 * The vector store is created with the five indexes but holds no document: the demo answers
 * deterministic graph and metric questions; semantic context is not seeded (residual, recorded).
 */
final class LocalDemoWorld private[localdemo] (val seed: DemoSeed) {
  import LocalDemoWorld.*

  val window: TimeWindow = TimeWindow(seed.window.startYear, seed.window.endYear)
  private val windowKeyText = f"${window.startYear}%04d-${window.endYear}%04d"

  val serving: scholarchain.rag.agents.ServingContext =
    scholarchain.rag.agents.ServingContext
      .of(RunId, Snapshot)
      .fold(e => throw new IllegalStateException(e), identity)

  /** Alias -> canonical researcher ID (core `ResearcherId.scholarChain`, dblp-pid anchor). */
  val researcherIds: Map[String, String] =
    seed.researchers.map { r =>
      r.alias -> ResearcherId
        .scholarChain("v1", "dblp-pid", r.dblpPid)
        .fold(e => throw new IllegalStateException(s"researcher ${r.alias}: $e"), identity)
    }.toMap

  val graphStore = new InMemoryGraphStore
  val kv = new InMemoryKeyValueStore(KeyValueCapacity)
  val objects = new InMemoryObjectStore(ObjectCapacity)
  val vectors = new InMemoryVectorStore(
    VectorIndexes.all.map(i =>
      i -> IndexInfo(
        i.name,
        Dimension,
        DistanceMetric.Cosine,
        MetadataField.nonFilterableFields.map(_.key)
      )
    ),
    VectorLimits(100, 2048, 4096, 40960, 50, 20, 100, 30)
  )

  /** The runtime stores (writes of the runtime, e.g. audits and evidence, go to `kv`). */
  val runtime: RuntimeStores = RuntimeStores(
    objects,
    kv,
    kv,
    graphStore,
    vectors,
    vectors,
    EmbeddingPolicy.SyntheticTestOnly
  )

  private val lineage: AnalysisLineage = AnalysisLineage(
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

  private def prov(subject: String): Provenance =
    Provenance(
      SourceType.Derived,
      subject,
      Snapshot,
      RunId,
      SchemaVersions.ResearcherAssignment,
      Some(GraphPolicy),
      None,
      None
    )

  private def hex(s: String): String = Canonical.sha256HexUtf8(s)
  private def work(n: Int): String = s"work:dblp:conf/demo/W$n"
  private def id(alias: String): String = researcherIds(alias)
  private def communityId(seedId: String) = "community:" + hex(seedId)
  private def componentId(seedId: String) = "component:" + hex(seedId)

  private def fail(what: String): Nothing =
    throw new IllegalStateException(s"local demo seed: $what was refused by its contract")

  private val gprov =
    GraphProvenance(RunId, LoadId, "graph/part-00000.csv", SchemaVersions.GraphNodeV2)

  private def vertex(vid: String, label: NodeLabel, props: (String, GraphValue)*): Unit = {
    val w = BoundGraphWrite
      .vertex(StoredVertex(vid, label, SortedMap(props*), gprov))
      .fold(_ => fail(s"vertex $vid"), identity)
    if (graphStore.write(w).isLeft) fail(s"vertex $vid")
  }

  private def edge(label: EdgeLabel, from: String, to: String, props: (String, GraphValue)*) = {
    val eid = "gedge:" + hex(s"$label $from $to")
    val w = BoundGraphWrite
      .edge(StoredEdge(eid, label, from, to, SortedMap(props*), gprov))
      .fold(_ => fail(s"edge $eid"), identity)
    if (graphStore.write(w).isLeft) fail(s"edge $eid")
  }

  private def row(
      role: String,
      subject: String,
      json: String,
      schema: String,
      group: Option[String] = None,
      pair: Option[Vector[String]] = None,
      rank: Option[Int] = None
  ): Unit = {
    val r = AnalysisResultRecord(
      AnalysisResultRecord.SchemaVersion,
      role,
      AnalysisResultRecord.SubjectScope,
      subject,
      windowKeyText,
      None,
      json,
      Canonical.sha256HexUtf8(json),
      schema,
      group,
      pair,
      rank,
      RunId,
      LoadId,
      s"dynamodb/$role/part-00000.parquet"
    )
    val item = ItemCodecs.AnalysisResultCodec.encode(r).fold(_ => fail(s"$role $subject"), identity)
    if (kv.putIfAbsent(TableRole.ResearcherMetricCache, item).isLeft) fail(s"$role $subject")
  }

  private def put[A](
      a: A,
      role: String,
      subject: String,
      schema: String,
      group: Option[String] = None,
      rank: Option[Int] = None
  )(using c: RecordContract[A]): Unit = {
    if (c.validate(a).nonEmpty) fail(s"$role $subject")
    row(role, subject, Json.write(a), schema, group = group, rank = rank)
  }

  private def identityOf(r: SeedResearcher): IdentityConfidence =
    r.identityStatus match {
      case "resolved" =>
        IdentityConfidence(
          IdentityStatus.Resolved,
          "dblp-pid",
          r.identityMemberCount,
          Vector.empty,
          DecisionConfidence.measured(0.93)
        )
      case "singleton" =>
        IdentityConfidence(
          IdentityStatus.Singleton,
          "dblp-pid",
          r.identityMemberCount,
          Vector.empty,
          DecisionConfidence.unknown(ConfidenceUnknownReason.NoSupportingDecision)
        )
      case _ => fail(s"researcher ${r.alias} identityStatus")
    }

  private def metricsOf(r: SeedResearcher) =
    ResearcherMetricsV2(
      SchemaVersions.ResearcherMetricsV2,
      id(r.alias),
      window,
      r.publications,
      r.distinctCoauthors,
      r.coauthorOccurrences,
      0L,
      0L,
      ResearcherMetricsV2.average(r.coauthorOccurrences, r.publications),
      r.maxCoauthorsOnOnePaper,
      Vector(work(1)),
      r.coauthorOccurrences,
      identityOf(r),
      lineage,
      prov(id(r.alias))
    )

  // ------------------------------------------------------------------ seeding
  {
    val R = AnalysisRoles
    val S = SchemaVersions
    seed.researchers.foreach { r =>
      vertex(
        id(r.alias),
        NodeLabel.Researcher,
        "anchorNamespace" -> GraphValue.Str("dblp-pid"),
        "identityStatus" -> GraphValue.Str(r.identityStatus),
        "memberCount" -> GraphValue.IntValue(r.vertexMemberCount),
        "runId" -> GraphValue.Str(RunId)
      )
    }
    seed.institutions.foreach { i =>
      vertex(
        i.id,
        NodeLabel.Institution,
        "name" -> GraphValue.Str(i.name),
        "runId" -> GraphValue.Str(RunId)
      )
    }
    seed.affiliations.foreach { a =>
      edge(
        EdgeLabel.AffiliatedWith,
        id(a.researcher),
        a.institution,
        "sourceEvidence" -> GraphValue.Str("mention:" + hex(a.mentionSeed)),
        "runId" -> GraphValue.Str(RunId)
      )
    }
    seed.coauthorships.foreach { c =>
      val e = CoauthorEdgeV2
        .build(
          id(c.a),
          id(c.b),
          window,
          c.years.map(y => YearCount(y.year, y.count)),
          c.works.map(work),
          lineage,
          GraphPolicy,
          x => prov(x)
        )
        .fold(_ => fail(s"coauthorship ${c.a}-${c.b}"), identity)
      edge(
        EdgeLabel.CoauthoredWith,
        e.researcherA,
        e.researcherB,
        "firstCollaborationYear" -> GraphValue.IntValue(e.firstCollaborationYear.toLong),
        "jointPublicationCount" -> GraphValue.IntValue(e.jointPublicationCount),
        "lastCollaborationYear" -> GraphValue.IntValue(e.lastCollaborationYear.toLong),
        "windowEndYear" -> GraphValue.IntValue(window.endYear.toLong),
        "windowStartYear" -> GraphValue.IntValue(window.startYear.toLong),
        "sourceEvidence" -> GraphValue.Str(e.edgeId),
        "runId" -> GraphValue.Str(RunId)
      )
      row(
        R.CoauthorEdges,
        e.edgeId,
        Json.write(e),
        S.CoauthorEdgeV2,
        pair = Some(Vector(e.researcherA, e.researcherB))
      )
    }

    // Rankings as the loader stores them (C desc, P desc, researcher ID asc).
    val ms = seed.researchers.map(metricsOf)
    val ranked = ms.sortWith { (a, b) =>
      if (a.distinctCoauthorCount != b.distinctCoauthorCount)
        a.distinctCoauthorCount > b.distinctCoauthorCount
      else if (a.publicationCount != b.publicationCount) a.publicationCount > b.publicationCount
      else Canonical.compareUtf8(a.researcherId, b.researcherId) < 0
    }
    ranked.zipWithIndex.foreach { case (m, i) =>
      put(m, R.ResearcherMetrics, m.researcherId, S.ResearcherMetricsV2, rank = Some(i + 1))
    }

    seed.researchers.foreach { r =>
      val rid = id(r.alias)
      val cm = communityId(r.community)
      val comp = componentId(r.component)
      put(
        CommunityMembership(
          S.CommunityMembership,
          rid,
          window,
          cm,
          r.communitySize,
          comp,
          "label-propagation",
          SortedMap("maxIterations" -> "10"),
          lineage,
          prov(rid)
        ),
        R.CommunityMemberships,
        rid,
        S.CommunityMembership,
        group = Some(cm)
      )
      put(
        ComponentMembership(
          S.ComponentMembership,
          rid,
          window,
          comp,
          r.componentSize,
          lineage,
          prov(rid)
        ),
        R.ComponentMemberships,
        rid,
        S.ComponentMembership,
        group = Some(comp)
      )
    }

    val bs = seed.bridges.map { b =>
      val rid = id(b.researcher)
      BridgeMeasure(
        S.BridgeMeasure,
        rid,
        window,
        communityId(b.community),
        "sampled-betweenness",
        SortedMap("sampleSize" -> "10"),
        if (b.crossCommunityDegree > 0) 0.5 else 0.0,
        b.degree,
        b.crossCommunityDegree,
        b.communitiesReached,
        BridgeMeasure.participation(b.degree, b.sumOfSquares),
        lineage,
        prov(rid)
      )
    }
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

    seed.coreness.zipWithIndex.foreach { case (c, i) =>
      val rid = id(c.researcher)
      put(
        Coreness(S.Coreness, rid, window, c.k, c.degree, lineage, prov(rid)),
        R.Coreness,
        rid,
        S.Coreness,
        rank = Some(i + 1)
      )
    }

    seed.entityLinks.foreach { l =>
      val rec = EntityLinkRecord(
        EntityLinkRecord.SchemaVersion,
        SurfaceForms.sha256(l.name),
        id(l.researcher),
        "exact-name",
        l.rank,
        RunId,
        LinkExpiresEpochSeconds
      )
      val item = ItemCodecs.EntityLinkCodec.encode(rec).fold(_ => fail(s"link ${l.name}"), identity)
      if (kv.putIfAbsent(TableRole.EntityLinkCache, item).isLeft) fail(s"link ${l.name}")
    }
  }
}

object LocalDemoWorld {

  /** Synthetic run, load and snapshot tokens of the demo world (not a real pipeline run). */
  val RunId: String = "run:" + "a" * 64
  val LoadId: String = "load:" + "9" * 64
  val Snapshot = "local-demo-nonproduction"
  val GraphPolicy = "graph-policy.v1"

  /** The stand-in embedder's dimension (NON-PRODUCTION hashed-token embedder). */
  val Dimension = 64

  /**
   * Absolute expiry of the seeded entity links (2030-03-17); the demo clock is the system clock.
   */
  val LinkExpiresEpochSeconds = 1900000000L

  /** Demo capacities of the in-memory stores (not production values). */
  val KeyValueCapacity = 10000
  val ObjectCapacity = 1000

  /** Builds the world from the committed seed resource; Left when the seed is refused. */
  def fromClasspath(): Either[String, LocalDemoWorld] = DemoSeed.load().flatMap(of)

  private[localdemo] def of(seed: DemoSeed): Either[String, LocalDemoWorld] =
    try Right(new LocalDemoWorld(seed))
    catch { case e: IllegalStateException => Left(e.getMessage) }
}
