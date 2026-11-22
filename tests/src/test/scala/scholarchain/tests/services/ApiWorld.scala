package scholarchain.tests.services

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}

import scala.collection.immutable.SortedMap

import scholarchain.core.*
import scholarchain.core.config.RuntimeConfig
import scholarchain.rag.agents.*
import scholarchain.rag.semantic.{
  ChunkingPolicy,
  NonProductionHashedTokenEmbedder,
  SemanticContextBudget
}
import scholarchain.services.{
  AccessEvent,
  AccessLog,
  ApiClient,
  ApiKeyDigestSource,
  GraphService,
  HttpRequest,
  HttpResponse,
  IdSource,
  QueryOwnership,
  QueryWorkflow,
  Router,
  Scope,
  ServiceClock,
  ServiceConfig,
  ServiceKind,
  ServiceRuntime,
  StoreAccess
}
import scholarchain.storage.fake.{
  InMemoryGraphStore,
  InMemoryKeyValueStore,
  InMemoryObjectStore,
  InMemoryVectorStore
}
import scholarchain.storage.model.*
import scholarchain.storage.port.{
  Embedder,
  GraphReader,
  KeyValueReader,
  KeyValueWriter,
  RuntimeStores,
  VectorReader
}

/**
 * TE-P11-02: the services fixture of the tests tree (owner test-engineer). services' own test
 * helpers (`ServiceWorld`, `TestHttp`, `FakeKeys`) are not on the tests classpath (build.sbt:
 * `services % Test` is Compile scope only), so this is an independent copy of the same hand-built
 * world, composed only through public compile-scope API: `ServiceRuntime.build`,
 * `QueryWorkflow.of`, `CollaborationWorkflow`, `Router.handle`. Extra seams for adversarial tests:
 * reader wrappers for graph, key-value and vectors, a writer wrapper for the runtime tables, an
 * injectable answer model and query embedder, and an empty world.
 */
final class SvcClock(start: Long = 1800000000000L) extends ServiceClock {
  private val now = new AtomicLong(start)
  def nowMillis(): Long = now.get()
  def monotonicNanos(): Long = now.get() * 1000000L
  def advance(millis: Long): Unit = { now.addAndGet(millis); () }
}

/** Deterministic tokens `000...0001`, `000...0002`, ... (32 digits). */
final class CountingIds extends IdSource {
  private val n = new AtomicInteger(0)
  def token(): String = f"${n.incrementAndGet()}%032d"
}

/** Always the same token: request, trace, query and job IDs are predictable. */
final class FixedIds(val fixed: String) extends IdSource {
  def token(): String = fixed
}

/** An access log that keeps every event. */
final class RecordingLog extends AccessLog {
  val events = new java.util.concurrent.ConcurrentLinkedQueue[AccessEvent]()
  def record(e: AccessEvent): Unit = { events.add(e); () }
  def all: Vector[AccessEvent] = {
    import scala.jdk.CollectionConverters.*
    events.asScala.toVector
  }
}

/** Fake credential source: fixed test keys (not secrets; used only by these tests). */
final class Keys extends ApiKeyDigestSource {
  import Keys.*
  def clients(): Either[String, Vector[ApiClient]] =
    Right(
      Vector(
        ApiClient.of("client-full", sha(FullKey), Set(Scope.Query, Scope.Read, Scope.Ops)),
        ApiClient.of("client-reader", sha(ReaderKey), Set(Scope.Read)),
        ApiClient.of("client-other", sha(OtherKey), Set(Scope.Query, Scope.Read))
      ).map(_.fold(e => throw new IllegalStateException(e), identity))
    )
}

object Keys {
  val FullKey = "te-p11-key-full-0123456789abcdef"
  val ReaderKey = "te-p11-key-reader-0123456789abcdef"
  val OtherKey = "te-p11-key-other-0123456789abcdef"

  def sha(s: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(s.getBytes(StandardCharsets.US_ASCII))
      .map(b => f"${b & 0xff}%02x")
      .mkString
}

/** In-process HTTP helpers (no socket). */
object Http {
  def auth(key: String): Vector[(String, String)] = Vector("Authorization" -> s"Bearer $key")

  def get(
      router: Router,
      path: String,
      key: Option[String] = Some(Keys.FullKey),
      headers: Vector[(String, String)] = Vector.empty
  ): HttpResponse = {
    val (p, q) = path.split("\\?", 2).toVector match {
      case Vector(a, b) => (a, Some(b))
      case v => (v.head, None)
    }
    router.handle(
      HttpRequest(
        "GET",
        p,
        q,
        HttpRequest.headersOf(key.toVector.flatMap(auth) ++ headers),
        Array.emptyByteArray
      )
    )
  }

  def post(
      router: Router,
      path: String,
      body: String,
      key: Option[String] = Some(Keys.FullKey),
      idempotencyKey: Option[String] = None,
      headers: Vector[(String, String)] = Vector.empty,
      rawQuery: Option[String] = None
  ): HttpResponse =
    router.handle(
      HttpRequest(
        "POST",
        path,
        rawQuery,
        HttpRequest.headersOf(
          key.toVector.flatMap(auth) ++ Vector("Content-Type" -> "application/json") ++
            idempotencyKey.toVector.map("Idempotency-Key" -> _) ++ headers
        ),
        body.getBytes(StandardCharsets.UTF_8)
      )
    )

  /** POST /v1/query with raw header pairs (no default Authorization). */
  def postRaw(router: Router, body: String, headers: Vector[(String, String)]): HttpResponse =
    router.handle(
      HttpRequest(
        "POST",
        "/v1/query",
        None,
        HttpRequest.headersOf(Vector("Content-Type" -> "application/json") ++ headers),
        body.getBytes(StandardCharsets.UTF_8)
      )
    )

  def json(r: HttpResponse): ujson.Value = ujson.read(r.bodyText)

  def query(q: String, mode: String = "sync"): String =
    ujson.Obj("question" -> q, "mode" -> mode).render()

  /** Waits (bounded, short polls) until `ok` holds; false on the deadline. */
  def eventually(timeoutMillis: Long)(ok: => Boolean): Boolean = {
    val deadline = System.nanoTime() + timeoutMillis * 1000000L
    var done = ok
    while (!done && System.nanoTime() < deadline) {
      Thread.sleep(5)
      done = ok
    }
    done
  }
}

/** An embedder with another model label over the same vectors (a model-version change). */
final class RelabelledEmbedder(inner: Embedder, val modelVersion: String) extends Embedder {
  def dimension: Int = inner.dimension
  def embed(texts: Vector[String]) = inner.embed(texts)
}

object ApiWorld {
  val run: String = "run:" + "a" * 64
  val load: String = "load:" + "9" * 64
  val graphPolicy = "graph-policy.v1"
  val window: TimeWindow = TimeWindow(2018, 2022)
  val windowKeyText = "2018-2022"
  val serving: ServingContext = ServingContext.of(run, "analysis-2026").toOption.get
  val Dim = 64
  val bucket = "test-context-bucket"

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

  def prov(subject: String): Provenance =
    Provenance(
      SourceType.Derived,
      subject,
      "analysis-2026",
      run,
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

  val c1: String = "community:" + hex("c1")
  val c2: String = "community:" + hex("c2")
  val c3: String = "community:" + hex("c3")
  val k1: String = "component:" + hex("k1")
  val k2: String = "component:" + hex("k2")

  def work(n: Int): String = s"work:dblp:conf/test/W$n"

  val stanford = "institution:openalex:I97018004"
  val toronto = "institution:openalex:I185261750"

  val limits: AgentLimits =
    AgentLimits
      .of(RuntimeConfig(4, 10, TimeWindow(2018, 2022)), 50, 100000L, 200, 200, 1000, 5, 10, 2048, 4)
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

  def metrics(id: String, p: Long, c: Long, t: Long, m: Long, identity: IdentityConfidence) =
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
      prov(id)
    )

  /** The workflow's clock: fixed epoch seconds (entity links expire at 1900000000). */
  val agentClock: AgentClock = new AgentClock {
    def monotonicNanos(): Long = 0L
    def epochSeconds(): Long = 1800000000L
  }

  /** The service configuration of these tests (every bound explicit). */
  def config(
      syncTimeoutMillis: Long = 10000L,
      asyncTimeoutMillis: Long = 10000L,
      readMaxAttempts: Int = 3,
      jobWorkers: Int = 2,
      idempotencyTtlMillis: Long = 3600000L
  ): ServiceConfig = {
    val requestBound = math.min(8000L, math.min(syncTimeoutMillis, asyncTimeoutMillis) * 4 / 5)
    val attemptBound = math.min(5000L, requestBound)
    ServiceConfig(
      maxBodyBytes = 16384,
      maxQuestionBytes = 2048,
      syncTimeoutMillis = syncTimeoutMillis,
      asyncTimeoutMillis = asyncTimeoutMillis,
      readTimeoutMillis = attemptBound,
      readMaxAttempts = readMaxAttempts,
      breakerFailureThreshold = 3,
      breakerOpenMillis = 60000L,
      readRequestTimeoutMillis = requestBound,
      queueWaitMillis = 5000L,
      workflowWorkers = 4,
      workflowQueueCapacity = 8,
      keyValueWorkers = 4,
      keyValueQueueCapacity = 16,
      graphWorkers = 4,
      graphQueueCapacity = 16,
      jobWorkers = jobWorkers,
      jobQueueCapacity = 8,
      maxJobs = 16,
      idempotencyCapacity = 16,
      idempotencyTtlMillis = idempotencyTtlMillis,
      queryOwnershipCapacity = 1000,
      graphMaxDepth = 1,
      graphMaxPageSize = 20,
      graphDefaultPageSize = 10,
      graphMaxResults = 50,
      graphEdgeTypes = GraphService.SupportedEdgeTypes,
      graphDefaultWindow = window,
      evidenceMaxContentBytes = 4096,
      evidenceMaxBundles = 10,
      evidenceScanLimit = 50,
      explainMaxSteps = 32,
      metricsEnabled = true,
      retryAfterSeconds = 2
    )
  }
}

/**
 * One fresh world: stores, the rag workflow and the composed service. `empty = true` stores nothing
 * (no vertex, row, link or document).
 */
final class ApiWorld(
    graphWrap: GraphReader => GraphReader = identity,
    kvWrap: KeyValueReader => KeyValueReader = identity,
    vectorWrap: VectorReader => VectorReader = identity,
    writerWrap: KeyValueWriter => KeyValueWriter = identity,
    empty: Boolean = false
) {
  import ApiWorld.*

  val graphStore = new InMemoryGraphStore
  val kv = new InMemoryKeyValueStore(1000)
  val objects = new InMemoryObjectStore(1000)
  val embedder = new NonProductionHashedTokenEmbedder(Dim)
  val vectors = new InMemoryVectorStore(
    VectorIndexes.all.map(i =>
      i -> IndexInfo(
        i.name,
        Dim,
        DistanceMetric.Cosine,
        MetadataField.nonFilterableFields.map(_.key)
      )
    ),
    VectorLimits(100, 2048, 4096, 40960, 50, 20, 100, 30)
  )
  val runtime: RuntimeStores = RuntimeStores(
    objects,
    kvWrap(kv),
    writerWrap(kv),
    graphWrap(graphStore),
    vectorWrap(vectors),
    vectors,
    EmbeddingPolicy.SyntheticTestOnly
  )

  private val gprov = GraphProvenance(run, load, "graph/part-00000.csv", SchemaVersions.GraphNodeV2)

  def vertex(id: String, label: NodeLabel, props: (String, GraphValue)*): Unit = {
    val w = BoundGraphWrite.vertex(StoredVertex(id, label, SortedMap(props*), gprov))
    assert(
      graphStore.write(w.fold(e => throw new IllegalStateException(e.message), identity)).isRight
    )
  }

  def edge(label: EdgeLabel, from: String, to: String, props: (String, GraphValue)*): Unit = {
    val id = "gedge:" + hex(s"$label $from $to")
    val w = BoundGraphWrite.edge(StoredEdge(id, label, from, to, SortedMap(props*), gprov))
    assert(
      graphStore.write(w.fold(e => throw new IllegalStateException(e.message), identity)).isRight
    )
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
      run,
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
      rank: Option[Int] = None
  )(using c: RecordContract[A]): Unit = {
    assert(c.validate(a).isEmpty, s"fixture $role $subject invalid")
    row(role, subject, Json.write(a), schema, group = group, rank = rank)
  }

  def link(name: String, researcherId: String, rank: Int): Unit = {
    val rec = EntityLinkRecord(
      EntityLinkRecord.SchemaVersion,
      SurfaceForms.sha256(name),
      researcherId,
      "exact-name",
      rank,
      run,
      1900000000L
    )
    val item = ItemCodecs.EntityLinkCodec
      .encode(rec)
      .fold(e => throw new IllegalStateException(e.message), identity)
    assert(kv.putIfAbsent(TableRole.EntityLinkCache, item).isRight)
  }

  def coauthor(a: String, b: String, years: Vector[YearCount], works: Vector[String]): Unit = {
    val e = CoauthorEdgeV2
      .build(a, b, window, years, works, lineage, graphPolicy, id => prov(id))
      .fold(x => throw new IllegalStateException(x.map(_.message).mkString("; ")), identity)
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
      "runId" -> GraphValue.Str(run)
    )
    row(
      AnalysisRoles.CoauthorEdges,
      e.edgeId,
      Json.write(e),
      SchemaVersions.CoauthorEdgeV2,
      pair = Some(Vector(e.researcherA, e.researcherB))
    )
  }

  def community(id: String, communityId: String, size: Long, componentId: String) =
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

  def component(id: String, componentId: String, size: Long) =
    ComponentMembership(
      SchemaVersions.ComponentMembership,
      id,
      window,
      componentId,
      size,
      lineage,
      prov(id)
    )

  def bridge(id: String, communityId: String, degree: Long, cross: Long, reached: Long, sq: Long) =
    BridgeMeasure(
      SchemaVersions.BridgeMeasure,
      id,
      window,
      communityId,
      "sampled-betweenness",
      SortedMap("sampleSize" -> "10"),
      if (cross > 0) 0.5 else 0.0,
      degree,
      cross,
      reached,
      BridgeMeasure.participation(degree, sq),
      lineage,
      prov(id)
    )

  // --- the standard world (as services ServiceWorld / rag AgentWorld.standard()) -------------
  if (!empty) {
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
    edge(
      EdgeLabel.AffiliatedWith,
      bob,
      stanford,
      "sourceEvidence" -> GraphValue.Str("mention:" + hex("m1")),
      "runId" -> GraphValue.Str(run)
    )
    edge(
      EdgeLabel.AffiliatedWith,
      cyd,
      toronto,
      "sourceEvidence" -> GraphValue.Str("mention:" + hex("m2")),
      "runId" -> GraphValue.Str(run)
    )
    coauthor(ada, bob, Vector(YearCount(2019, 1), YearCount(2021, 1)), Vector(work(1), work(2)))
    coauthor(bob, cyd, Vector(YearCount(2020, 1)), Vector(work(3)))
    coauthor(cyd, dan, Vector(YearCount(2022, 1)), Vector(work(4)))

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
    ).foreach { case (id, (cm, size, comp)) =>
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
    val bs = Vector(
      bridge(bob, c1, 2, 1, 1, 2),
      bridge(cyd, c2, 2, 1, 1, 2),
      bridge(ada, c1, 1, 0, 0, 1),
      bridge(dan, c2, 1, 0, 0, 1)
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
        put(
          Coreness(S.Coreness, id, window, k, d, lineage, prov(id)),
          R.Coreness,
          id,
          S.Coreness,
          rank = Some(i + 1)
        )
      }
    link("Ada Lovelace", ada, 1)
    link("John Smith", bob, 1)
    link("John Smith", cyd, 2)
    // TE-P11-02: the singleton researcher (no coauthor edge, own component) is nameable.
    link("Eve Example", eve, 1)
    link("Dan Example", dan, 1)
  }

  def workflowConfig(
      model: AnswerModel = NonProductionTemplateSynthesizer,
      queryEmbedder: Embedder = embedder,
      maxAttempts: Int = 3
  ): WorkflowConfig =
    (for {
      ev <- EvidenceLimits.of(300, 2000000, 5)
      syn <- SynthesisConfig.of(model, maxAttempts, 400000, 400000, 64, 1000000L)
      au <- AuditConfig.of(3600L, 65536L)
      sem <- SemanticContextBudget
        .of(3, 3, 2048, 65536, 4096)
        .flatMap(b =>
          SemanticConfig.of(
            queryEmbedder,
            VectorIndexes.all.map(SemanticIndex(_, Dim)),
            bucket,
            b,
            5
          )
        )
      wc <- WorkflowConfig.of(limits, ev, syn, sem, au, 5, 10)
    } yield wc).fold(e => throw new IllegalStateException(e), identity)

  val access: StoreAccess = StoreAccess.serialized()

  /** The rag workflow itself (stages 1-10), for tests that inspect the WorkflowResult. */
  def collaboration(
      model: AnswerModel = NonProductionTemplateSynthesizer,
      queryEmbedder: Embedder = embedder,
      maxAttempts: Int = 3
  ): CollaborationWorkflow =
    new CollaborationWorkflow(
      runtime,
      serving,
      workflowConfig(model, queryEmbedder, maxAttempts),
      agentClock
    )

  def ragWorkflow(
      model: AnswerModel = NonProductionTemplateSynthesizer,
      maxAttempts: Int = 3
  ): QueryWorkflow =
    QueryWorkflow.of(collaboration(model, embedder, maxAttempts), access)

  /** The composed service over this world. */
  def service(
      config: ServiceConfig = ApiWorld.config(),
      workflow: QueryWorkflow = null,
      keys: ApiKeyDigestSource = new Keys,
      clock: ServiceClock = new SvcClock,
      log: RecordingLog = new RecordingLog,
      ids: IdSource = new CountingIds,
      kind: ServiceKind = ServiceKind.All,
      ownership: QueryOwnership = null
  ): ServiceRuntime =
    ServiceRuntime
      .build(
        runtime,
        Option(workflow).getOrElse(ragWorkflow()),
        keys,
        config,
        access,
        log,
        clock,
        ids,
        kind,
        ownership
      )
      .fold(e => throw new IllegalStateException(e), identity)

  // --- semantic context ---------------------------------------------------------------------
  private val summaryItem = LoadItem(
    "summary-inputs/part-00000.bin",
    SchemaVersions.SemanticSummaryInput,
    PartFormat.values.head,
    1L,
    1L,
    "0" * 64,
    "summary-inputs"
  )

  private def putVector(
      ix: VectorIndexSpec,
      inputId: String,
      text: String,
      fields: Vector[(String, MetaValue)]
  ): String = {
    val sha = Canonical.sha256HexUtf8(text)
    val key = s"ctx/text/$sha.txt"
    assert(objects.putCreateOnce(ObjectRef(bucket, key), Bytes.utf8(text), "text/plain").isRight)
    val v = embedder.embed(Vector(text)).toOption.flatMap(_.headOption).get
    val md = VectorMetadata(
      SortedMap.from(
        Vector(
          MetadataField.TextS3Key.key -> MetaValue.Str(key),
          MetadataField.TextSha256.key -> MetaValue.Str(sha),
          MetadataField.ModelVersion.key -> MetaValue.Str(embedder.modelVersion),
          MetadataField.InputId.key -> MetaValue.Str(inputId),
          MetadataField.DataSha256.key -> MetaValue.Str(VectorDigest.of(v).toOption.get)
        ) ++ fields
      )
    )
    val vk = VectorKeys.of(ix, embedder.modelVersion, inputId).toOption.get
    val out = vectors.putVectors(ix.name, Vector(VectorRecord(vk, v, md)))
    assert(out.exists(_.forall(_.outcome == Right(PutOutcome.Written))), s"$inputId: $out")
    key
  }

  /** Renders, stores and indexes one context document (storage's loader renderer). */
  def addDoc(
      subject: String,
      docType: DocumentType,
      facts: Vector[(String, String)],
      extra: Vector[(MetadataField, MetaValue)]
  ): String = {
    val ix = VectorIndexes.forDocumentType(docType)
    val windowed = scholarchain.rag.semantic.RenderTemplates.WindowedTypes.contains(docType)
    val input =
      (if (windowed) SummaryInputId.of(docType, subject, window, graphPolicy)
       else EntitySummaryInputId.of(docType, subject, graphPolicy)).toOption.get
    val text = scholarchain.storage.load.ContextText.render(
      run,
      summaryItem,
      docType,
      subject,
      Option.when(windowed)(window),
      input,
      None,
      facts.map((n, v) => (n, v, Vector.empty))
    )
    val windowFields =
      if (windowed)
        Vector(
          MetadataField.WindowStart.key -> MetaValue.Num(window.startYear.toLong),
          MetadataField.WindowEnd.key -> MetaValue.Num(window.endYear.toLong)
        )
      else Vector.empty
    putVector(
      ix,
      input,
      text,
      Vector(
        MetadataField.DocumentTypeField.key -> MetaValue.Str(docType.tag),
        MetadataField.SubjectId.key -> MetaValue.Str(subject)
      ) ++ windowFields ++ extra.map((f, x) => f.key -> x)
    )
  }

  def ids(xs: String*): (MetadataField, MetaValue) =
    MetadataField.ResearcherIds -> MetaValue.StrList(xs.toVector)

  /** Ada's researcher profile and two work summaries naming Ada (no injection text). */
  def addSemanticDocs(): Unit = {
    addDoc(
      ada,
      DocumentType.ResearcherProfile,
      Vector("displayName" -> "Ada Example"),
      Vector(ids(ada))
    )
    addDoc(
      work(1),
      DocumentType.WorkSummary,
      Vector("title" -> "Graph Methods for Collaboration"),
      Vector(ids(ada, bob))
    )
    addDoc(
      work(2),
      DocumentType.WorkSummary,
      Vector("title" -> "Community Structure in Coauthorship Networks"),
      Vector(ids(ada, bob))
    )
  }

  /**
   * A free-text `work-abstract` of `workId`, chunked with rag's [[ChunkingPolicy]] and stored one
   * vector per chunk in `paper-abstract-v1` (the shape the policy prescribes; no loader writes it
   * locally). Returns the text object keys of the chunks.
   */
  def addAbstract(workId: String, text: String, researcherIds: Vector[String]): Vector[String] = {
    val policy =
      ChunkingPolicy.of(512, 64, 8).fold(e => throw new IllegalStateException(e), identity)
    val chunks = policy
      .chunk(workId, VectorIndexes.PaperAbstract.name, DocumentType.WorkAbstract, text)
      .fold(e => throw new IllegalStateException(e), identity)
    chunks.map { c =>
      putVector(
        VectorIndexes.PaperAbstract,
        "suminput:" + hex(s"te-p11-abstract ${c.chunkId}"),
        c.text,
        Vector(
          MetadataField.DocumentTypeField.key -> MetaValue.Str(DocumentType.WorkAbstract.tag),
          MetadataField.SubjectId.key -> MetaValue.Str(workId),
          MetadataField.ResearcherIds.key -> MetaValue.StrList(researcherIds)
        )
      )
    }
  }
}
