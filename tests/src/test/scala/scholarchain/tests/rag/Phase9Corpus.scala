package scholarchain.tests.rag

import scala.collection.mutable

import scholarchain.core.{
  Canonical,
  CoauthorEdgeV2,
  CommunityMembership,
  DocumentType,
  EntitySummaryInput,
  IdKind,
  IdentityConfidence,
  Json,
  LoadItem,
  ResearcherMetricsV2,
  SemanticSummaryInput,
  StableId,
  StoreLoadManifest,
  TimeWindow
}
import scholarchain.core.config.RuntimeConfig
import scholarchain.rag.agents.{
  AgentClock,
  AgentLimits,
  AnswerModel,
  AuditConfig,
  CollaborationWorkflow,
  EvidenceLimits,
  EvidenceSchemas,
  NonProductionTemplateSynthesizer,
  PathTraversalEvidence,
  SemanticConfig,
  SemanticIndex,
  ServingContext,
  SynthesisConfig,
  WorkflowConfig,
  WorkflowResult
}
import scholarchain.rag.semantic.{NonProductionHashedTokenEmbedder, SemanticContextBudget}
import scholarchain.storage.{Fixtures, OpeningFixture, RunSettingsFixture, UpstreamFixture}
import scholarchain.storage.fake.{
  FractionJitter,
  InMemoryGraphStore,
  InMemoryKeyValueStore,
  InMemoryObjectStore,
  InMemoryVectorStore,
  ManualClock,
  RecordingSleeper
}
import scholarchain.storage.load.{
  ContextText,
  OpeningLoad,
  OpeningLoadResult,
  OpeningLoadTargets,
  ParquetRecords,
  UpstreamRuns
}
import scholarchain.storage.model.{
  AuditBounds,
  BoundQuery,
  Bytes,
  EmbeddingPolicy,
  EntityLinkRecord,
  EntityLinkSurfaceForm,
  ItemCodecs,
  PrimaryKey,
  QueryAuditRecord,
  TableKeys,
  TableRole,
  VectorIndexSpec,
  VectorIndexes
}
import scholarchain.storage.port.{
  GraphReader,
  KeyQuery,
  KeyValueReader,
  ObjectReader,
  RuntimeStores,
  SortKeyCondition,
  VectorReader
}

/**
 * TE-P9-01: the SOURCE data of the local corpus (the Phase 6 run-1 fixture and its bound
 * entity-resolution upstream), decoded by storage's own readers only, independently of any rag
 * stage. Every benchmark and gate expectation is derived from these records (stored metrics, edges,
 * community rows, link rows, summary inputs), never from workflow output.
 */
object Phase9Corpus {
  val RunId: String = Fixtures.RunId
  val Snapshot = "phase06-run-1-local"
  val Bucket = "test-data-bucket" // the SemanticContext zone bucket of TestData.layout

  /** Test configuration values (not production values). */
  val Dim = 256
  val MaxHops = 8
  val DirectLimit = 20
  val RankK = 5
  val Epoch = 1_800_000_000L // before the fixture links' expiry (1.9e9), after the load clock

  def utf8Lt(a: String, b: String): Boolean = Canonical.compareUtf8(a, b) < 0

  def windowText(w: TimeWindow): String = f"${w.startYear}%04d-${w.endYear}%04d"

  // ------------------------------------------------------------------ decoded run parts

  private def manifestItems(name: String): Vector[LoadItem] =
    Json
      .decode[StoreLoadManifest](
        Fixtures.text(Fixtures.loadDir(Fixtures.run1Root).resolve(name)).stripSuffix("\n")
      )
      .fold(e => throw new AssertionError(s"$name: $e"), _.items)

  private def decoded(name: String): Vector[(LoadItem, Product)] =
    manifestItems(name).flatMap { it =>
      val b = Bytes.of(Fixtures.bytes(Fixtures.runDir(Fixtures.run1Root).resolve(it.path)))
      ParquetRecords
        .decodePart(it.path, it.recordSchemaVersion, b, RunSettingsFixture.parquetBounds)
        .fold(e => throw new AssertionError(s"${it.path}: $e"), _.records)
        .map(it -> _)
    }

  lazy val dynamo: Vector[Product] = decoded("dynamodb.json").map(_._2)
  lazy val metrics: Vector[ResearcherMetricsV2] = dynamo.collect { case x: ResearcherMetricsV2 =>
    x
  }
  lazy val edges: Vector[CoauthorEdgeV2] = dynamo.collect { case x: CoauthorEdgeV2 => x }
  lazy val communities: Vector[CommunityMembership] = dynamo.collect {
    case x: CommunityMembership => x
  }

  type Facts = Vector[(String, String, Vector[(String, String)])]

  /** One decoded summary input (storage's Parquet decoder), as in the Phase 8 corpus suite. */
  final case class Src(
      item: LoadItem,
      inputId: String,
      docType: DocumentType,
      subject: String,
      window: Option[TimeWindow],
      identity: Option[IdentityConfidence],
      facts: Facts
  ) {
    def index: VectorIndexSpec = VectorIndexes.forDocumentType(docType)
    def render(fs: Facts): String =
      ContextText.render(RunId, item, docType, subject, window, inputId, identity, fs)
    lazy val text: String = render(facts)
  }

  lazy val summaries: Vector[Src] = decoded("s3-vectors.json").map {
    case (it, x: SemanticSummaryInput) =>
      Src(
        it,
        x.inputId,
        x.documentType,
        x.subjectId,
        Some(x.window),
        Option(x.identity).flatten,
        x.facts.map(f => (f.name, f.value, f.evidence.map(e => (e.kind.tag, e.id))))
      )
    case (it, x: EntitySummaryInput) =>
      Src(
        it,
        x.inputId,
        x.documentType,
        x.subjectId,
        None,
        None,
        x.facts.map(f => (f.name, f.value, f.evidence.map(e => (e.kind.tag, e.id))))
      )
    case (it, other) => throw new AssertionError(s"${it.path}: ${other.productPrefix}")
  }

  // ------------------------------------------------------------------ entity-resolution upstream

  lazy val grouping =
    UpstreamRuns
      .grouping(
        Fixtures.source(UpstreamFixture.groupingDir, UpstreamFixture.groupingBounds),
        UpstreamFixture.upstreamBounds
      )
      .fold(e => throw new AssertionError(e.mkString("; ")), identity)

  lazy val curated =
    UpstreamRuns
      .curated(
        Fixtures.source(UpstreamFixture.curatedDir, UpstreamFixture.curatedBounds),
        UpstreamFixture.upstreamBounds
      )
      .fold(e => throw new AssertionError(e.mkString("; ")), identity)

  /** (raw name, researcher) of every identified author mention (resolved or singleton). */
  lazy val namedMentions: Vector[(String, String)] = {
    val byId = curated.mentions.map(m => m.mentionId -> m).toMap
    grouping.graphInput.researchers.flatMap { a =>
      if (!StableId.prefixKind(a.memberId).contains(IdKind.Mention)) Vector.empty
      else
        for {
          r <- a.researcherId.toVector
          m <- byId.get(a.memberId).toVector
        } yield (m.rawName, r)
    }
  }

  /** One surface form: its hash, the researchers whose identified mentions carry it, spellings. */
  final case class Surface(sha: String, researchers: Set[String], spellings: Vector[String])

  lazy val surfaces: Vector[Surface] =
    namedMentions
      .filter(x => EntityLinkSurfaceForm.normalize(x._1).nonEmpty)
      .groupBy(x => EntityLinkSurfaceForm.sha256(x._1))
      .toVector
      .map { case (h, xs) =>
        Surface(h, xs.map(_._2).toSet, xs.map(_._1).distinct.sortWith(utf8Lt))
      }
      .sortWith((a, b) => utf8Lt(a.sha, b.sha))

  lazy val ambiguous: Vector[Surface] = surfaces.filter(_.researchers.size > 1)
  lazy val unique: Vector[Surface] = surfaces.filter(_.researchers.size == 1)

  private val YearLike = "\\b(1\\d{3}|2\\d{3})\\b".r

  /**
   * Whether a stored spelling can be put into a question as a quoted name at all (planner input
   * rules: no quote characters, no control or format character, no year-like number, at most 200
   * characters inside the quotes). This is input selection, counted and asserted by the suites.
   */
  def askable(n: String): Boolean =
    n != null && n.trim.nonEmpty && n.length <= 200 &&
      !n.exists(c =>
        c == '"' || c == '“' || c == '”' || Character.isISOControl(c) ||
          Character.getType(c) == Character.FORMAT
      ) && YearLike.findFirstIn(n).isEmpty

  def askName(s: Surface): Option[String] = s.spellings.find(askable)

  // ------------------------------------------------------------------ the served window and graph

  lazy val windows: Vector[TimeWindow] =
    metrics.map(_.window).distinct.sortBy(w => (w.startYear, w.endYear))

  /** The window with the most stored coauthor edges (ties: the earlier window). */
  lazy val window: TimeWindow =
    windows.sortBy(w => (-edges.count(_.window == w), w.startYear, w.endYear)).head

  lazy val W: String = windowText(window)

  lazy val edgesW: Vector[CoauthorEdgeV2] = edges.filter(_.window == window)

  lazy val adj: Map[String, Vector[(String, CoauthorEdgeV2)]] =
    (edgesW.map(e => e.researcherA -> (e.researcherB, e)) ++
      edgesW.map(e => e.researcherB -> (e.researcherA, e))).groupMap(_._1)(_._2)

  def degree(r: String): Int = adj.getOrElse(r, Vector.empty).size

  lazy val metricsW: Map[String, ResearcherMetricsV2] =
    metrics.filter(_.window == window).map(m => m.researcherId -> m).toMap

  /** Hop distances from `src` over the stored coauthor edges of the window. */
  def bfs(src: String): Map[String, Int] = {
    val dist = mutable.Map(src -> 0)
    var frontier = Vector(src)
    var level = 0
    while (frontier.nonEmpty) {
      level += 1
      val next = frontier
        .flatMap(n => adj.getOrElse(n, Vector.empty).map(_._1))
        .distinct
        .filterNot(dist.contains)
      next.foreach(x => dist(x) = level)
      frontier = next
    }
    dist.toMap
  }

  /** Researchers with exactly one askable unique surface form name (first by UTF-8). */
  lazy val uniqueNames: Map[String, String] =
    unique
      .flatMap(s => askName(s).map(n => s.researchers.head -> n))
      .groupBy(_._1)
      .map { case (r, ns) => r -> ns.map(_._2).sortWith(utf8Lt).head }

  private def byIdOrder(xs: Iterable[String]): Vector[String] = xs.toVector.sortWith(utf8Lt)

  /** The unique-name researcher with the most coauthors below the direct limit (ties: ID). */
  lazy val u: String = byIdOrder(uniqueNames.keys)
    .filter(r => degree(r) >= 1 && degree(r) < DirectLimit && metricsW.contains(r))
    .sortBy(r => -degree(r))
    .headOption
    .getOrElse(throw new AssertionError("no unique-name researcher with coauthors in the window"))

  /** The farthest connected pair of unique-name researchers (2..MaxHops hops; ties: ID order). */
  lazy val pathPair: (String, String, Int) = {
    val rs = byIdOrder(uniqueNames.keys).filter(degree(_) >= 1)
    val all = for {
      a <- rs
      d = bfs(a)
      b <- rs if a != b
      h <- d.get(b).toVector if h >= 2 && h <= MaxHops
    } yield (a, b, h)
    all.sortBy(x => -x._3).headOption.getOrElse(throw new AssertionError("no path pair"))
  }

  /** The first pair (ID order) of unique-name researchers with edges but no path between them. */
  lazy val disconnected: (String, String) = {
    val rs = byIdOrder(uniqueNames.keys).filter(degree(_) >= 1)
    (for {
      a <- rs.iterator
      d = bfs(a)
      b <- rs.iterator if a != b && !d.contains(b)
    } yield (a, b)).nextOption().getOrElse(throw new AssertionError("no disconnected pair"))
  }

  /** The stored order of the C_r ranking (loader rule: C desc, P desc, researcher ID asc). */
  lazy val rankingC: Vector[ResearcherMetricsV2] =
    metricsW.values.toVector.sortWith { (a, b) =>
      if (a.distinctCoauthorCount != b.distinctCoauthorCount)
        a.distinctCoauthorCount > b.distinctCoauthorCount
      else if (a.publicationCount != b.publicationCount) a.publicationCount > b.publicationCount
      else utf8Lt(a.researcherId, b.researcherId)
    }

  /** A unique-name researcher with a researcher-profile summary input in the window. */
  lazy val s: String = {
    val withProfile = summaries
      .filter(x => x.docType == DocumentType.ResearcherProfile && x.window.contains(window))
      .map(_.subject)
      .toSet
    if (withProfile(u)) u
    else
      byIdOrder(uniqueNames.keys)
        .find(withProfile)
        .getOrElse(throw new AssertionError("no unique-name researcher with a profile"))
  }

  /** An askable ambiguous surface form naming exactly two researchers. */
  lazy val amb2: Surface =
    ambiguous
      .find(x => x.researchers.size == 2 && askName(x).isDefined)
      .getOrElse(throw new AssertionError("no askable two-researcher ambiguous name"))

  val UnknownName = "Zebedee Quillfeather Nonexistent"

  /** Aliases of the committed corpus benchmark set, every value from the source records. */
  lazy val aliases: Map[String, String] = {
    val coU = adj(u).sortBy(x => (-x._2.jointPublicationCount, x._1)).head
    val (pa, pb, ph) = pathPair
    val (na, nb) = disconnected
    val ambIds = byIdOrder(amb2.researchers)
    Map(
      "W" -> W,
      "u" -> u,
      "uName" -> uniqueNames(u),
      "uP" -> metricsW(u).publicationCount.toString,
      "uC" -> metricsW(u).distinctCoauthorCount.toString,
      "c0" -> coU._1,
      "nonCo" -> byIdOrder(metricsW.keys)
        .find(r => r != u && !adj(u).exists(_._1 == r))
        .getOrElse(throw new AssertionError("every researcher is a coauthor of u")),
      "j0" -> coU._2.jointPublicationCount.toString,
      "pA" -> pa,
      "pB" -> pb,
      "pAName" -> uniqueNames(pa),
      "pBName" -> uniqueNames(pb),
      "pHops" -> ph.toString,
      "nA" -> na,
      "nB" -> nb,
      "nAName" -> uniqueNames(na),
      "nBName" -> uniqueNames(nb),
      "maxHops" -> MaxHops.toString,
      "r1" -> rankingC(0).researcherId,
      "r2" -> rankingC(1).researcherId,
      "r1C" -> rankingC(0).distinctCoauthorCount.toString,
      "ambName" -> askName(amb2).get,
      "amb0" -> ambIds(0),
      "amb1" -> ambIds(1),
      "s" -> s,
      "sName" -> uniqueNames(s),
      "unknownName" -> UnknownName
    )
  }

  // ------------------------------------------------------------------ source-value oracle

  /**
   * TE-P9-03: whether an assertion's value equals the SOURCE records (decoded fixture Parquet), for
   * the fields the source can decide directly; None for fields it cannot (titles, path nodes, IDs
   * of runtime records). Independent of every rag stage.
   */
  def sourceAgrees(subject: String, field: String, value: String): Option[Boolean] = {
    import scholarchain.rag.agents.FactFields as F
    val (ids, w) = subject.split("@", -1).toVector match {
      case Vector(i, win) => (i.split("\\|", -1).toVector, win)
      case _ => (Vector(subject), "")
    }
    def metric(rid: String) = metrics.find(m => m.researcherId == rid && windowText(m.window) == w)
    def edge(a: String, b: String) = edges.find(e =>
      windowText(e.window) == w &&
        ((e.researcherA == a && e.researcherB == b) || (e.researcherA == b && e.researcherB == a))
    )
    val d = (x: Double) => java.lang.Double.toString(x)
    (ids, field) match {
      case (Vector(r), F.PublicationCount) =>
        Some(metric(r).exists(_.publicationCount.toString == value))
      case (Vector(r), F.DistinctCoauthorCount) =>
        Some(metric(r).exists(_.distinctCoauthorCount.toString == value))
      case (Vector(r), F.TotalCoauthorOccurrences) =>
        Some(metric(r).exists(_.totalCoauthorOccurrences.toString == value))
      case (Vector(r), F.AverageCoauthorsPerPaper) =>
        Some(metric(r).exists(m => d(m.averageCoauthorsPerPaper) == value))
      case (Vector(r), F.MaximumCoauthorsOnOnePaper) =>
        Some(metric(r).exists(_.maximumCoauthorsOnOnePaper.toString == value))
      case (Vector(r), F.WeightedDegree) =>
        Some(metric(r).exists(_.weightedDegree.toString == value))
      case (Vector(r), F.IdentityStatus) => Some(metric(r).exists(_.identity.status.tag == value))
      case (Vector(r), F.Coauthor) => Some(edge(r, value).isDefined)
      case (Vector(r), F.CommunityId) =>
        Some(
          communities.exists(c =>
            c.researcherId == r && windowText(c.window) == w && c.communityId == value
          )
        )
      case (Vector(r), F.CommunitySize) =>
        Some(
          communities.exists(c =>
            c.researcherId == r && windowText(c.window) == w && c.communitySize.toString == value
          )
        )
      case (Vector(a, b), F.JointPublicationCount) =>
        Some(edge(a, b).exists(_.jointPublicationCount.toString == value))
      case (Vector(a, b), F.FirstCollaborationYear) =>
        Some(edge(a, b).exists(_.firstCollaborationYear.toString == value))
      case (Vector(a, b), F.LastCollaborationYear) =>
        Some(edge(a, b).exists(_.lastCollaborationYear.toString == value))
      case (Vector(a, b), F.ShortestPathHops) if w == W =>
        Some(bfs(a).get(b).map(_.toString).contains(value))
      case (Vector(a, b), F.NotConnectedWithin) if w == W =>
        Some(value.toIntOption.exists(n => bfs(a).get(b).forall(_ > n)))
      case (Vector(r), f) if f == s"${F.RankPrefix}distinct-coauthor-count" && w == W =>
        Some(rankingC.indexWhere(_.researcherId == r) + 1 == value.toIntOption.getOrElse(-1))
      case _ => None
    }
  }

  // ------------------------------------------------------------------ configuration

  lazy val limits: AgentLimits = limitsWith(10)

  def limitsWith(maxCandidates: Int, maxResults: Int = 100, maxContextRows: Int = 10): AgentLimits =
    AgentLimits
      .of(
        RuntimeConfig(MaxHops, 10, window),
        maxResults,
        1_000_000_000L, // timeoutMillis (the test clock advances 1 ms per read)
        5000, // maxGraphCalls
        20000, // maxKeyValueCalls
        1000, // maxVisited
        maxCandidates,
        maxContextRows,
        4096, // maxQuestionBytes
        4 // maxNames
      )
      .fold(e => throw new IllegalStateException(e), identity)

  lazy val serving: ServingContext =
    ServingContext.of(RunId, Snapshot).fold(e => throw new IllegalStateException(e), identity)

  def semanticConfig(embedder: NonProductionHashedTokenEmbedder): SemanticConfig =
    (for {
      b <- SemanticContextBudget
        .of(math.min(10, OpeningFixture.openingLimits.maxTopK), 10, 4096, 1 << 20, 4096)
      c <- SemanticConfig.of(embedder, VectorIndexes.all.map(SemanticIndex(_, Dim)), Bucket, b, 5)
    } yield c).fold(e => throw new IllegalStateException(e), identity)

  def config(
      model: AnswerModel = NonProductionTemplateSynthesizer,
      maxAttempts: Int = 3,
      maxOutputBytes: Int = 8_000_000,
      maxAuditJsonBytes: Long = AuditBounds.CeilingBytes,
      agentLimits: AgentLimits = limits,
      rankK: Int = RankK,
      directLimit: Int = DirectLimit
  ): WorkflowConfig =
    (for {
      ev <- EvidenceLimits.of(3000, 16_000_000, 5)
      syn <- SynthesisConfig.of(model, maxAttempts, 8_000_000, maxOutputBytes, 512, 100_000_000L)
      au <- AuditConfig.of(3600L, maxAuditJsonBytes)
      wc <- WorkflowConfig.of(
        agentLimits,
        ev,
        syn,
        semanticConfig(new NonProductionHashedTokenEmbedder(Dim)),
        au,
        rankK,
        directLimit
      )
    } yield wc).fold(e => throw new IllegalStateException(e), identity)

  /**
   * The path-traversal evidence items of a result's bundle, decoded (schema rag-path-traversal.v2).
   */
  def pathTraversals(r: WorkflowResult): Vector[PathTraversalEvidence] =
    r.evidence.toVector
      .flatMap(_.bundle.items)
      .filter(_.provenance.sourceSchemaVersion == EvidenceSchemas.PathTraversal)
      .map(i => upickle.default.read[PathTraversalEvidence](i.text.getOrElse("")))

  // ------------------------------------------------------------------ stored-row reads

  def linkRows(kv: KeyValueReader, sha: String): Vector[EntityLinkRecord] =
    TableKeys.EntityLinkCache
      .partition(sha)
      .flatMap(p =>
        kv.query(TableRole.EntityLinkCache, KeyQuery(None, p, SortKeyCondition.All, 100))
      )
      .fold(e => throw new AssertionError(e.message), identity)
      .map(i =>
        ItemCodecs.EntityLinkCodec
          .decode(i)
          .fold(e => throw new AssertionError(e.message), identity)
      )

  def auditRow(kv: KeyValueReader, queryId: String): Option[QueryAuditRecord] =
    TableKeys.QuerySession
      .audit(queryId)
      .flatMap(k => kv.get(TableRole.QuerySession, k))
      .fold(e => throw new AssertionError(e.message), identity)
      .map(i =>
        ItemCodecs.QueryAuditCodec
          .decode(i)
          .fold(e => throw new AssertionError(e.message), identity)
      )

  def present(kv: KeyValueReader, table: TableRole, key: Either[?, PrimaryKey]): Boolean =
    key.toOption.exists(k => kv.get(table, k).toOption.flatten.isDefined)
}

/** A deterministic clock: 1 ms per monotonic read; a fixed epoch second. */
final class TestClock extends AgentClock {
  private var n = 0L
  def monotonicNanos(): Long = { n += 1000000L; n }
  def epochSeconds(): Long = Phase9Corpus.Epoch
}

/** Records every bound graph query (template and typed parameters). */
final class GraphRecorder(inner: GraphReader) extends GraphReader {
  val seen: mutable.ArrayBuffer[BoundQuery] = mutable.ArrayBuffer.empty
  def run(q: BoundQuery) = { seen += q; inner.run(q) }
}

/** Records every key-value read (table and key, or table and query). */
final class KeyValueRecorder(inner: KeyValueReader) extends KeyValueReader {
  val gets: mutable.ArrayBuffer[(TableRole, PrimaryKey)] = mutable.ArrayBuffer.empty
  val queries: mutable.ArrayBuffer[(TableRole, KeyQuery)] = mutable.ArrayBuffer.empty
  def get(t: TableRole, k: PrimaryKey) = { gets += (t -> k); inner.get(t, k) }
  def query(t: TableRole, q: KeyQuery) = { queries += (t -> q); inner.query(t, q) }
  def clear(): Unit = { gets.clear(); queries.clear() }
}

/**
 * The local corpus loaded by storage's production entry point ([[OpeningLoad.run]]) with
 * `OpeningFixture.configWithEntityLinks` (EntityLinkCache filled from the entity-resolution
 * upstream) and rag's NON-PRODUCTION stand-in embedder, into in-memory stores.
 */
final class CorpusWorld(dim: Int) {
  val graph = new OpeningFixture.RecordedGraph(new InMemoryGraphStore)
  val kvStore = new InMemoryKeyValueStore(1000)
  val ledgerStore = new InMemoryKeyValueStore(1000)
  val objectStore = new InMemoryObjectStore(1000)
  val vectorStore =
    new InMemoryVectorStore(OpeningFixture.indexInfos(dim), OpeningFixture.openingLimits)
  private val loadClock = new ManualClock(1_700_000_000_000L)
  val result: OpeningLoadResult = OpeningLoad.run(
    OpeningFixture.sources(),
    OpeningFixture.configWithEntityLinks
      .copy(vectors = OpeningFixture.vectorConfig(dimension = dim)),
    OpeningLoadTargets(
      graph,
      kvStore,
      ledgerStore,
      objectStore,
      vectorStore,
      new NonProductionHashedTokenEmbedder(dim),
      loadClock,
      new RecordingSleeper(loadClock),
      new FractionJitter(0.5)
    )
  )
  require(
    result.report.complete,
    s"corpus load incomplete: ${result.report.stores.map(s => (s.target, s.status, s.error))}"
  )

  /** A runtime over the loaded stores (runtime writes go to the same key-value store). */
  def runtime(
      graphReader: GraphReader = graph,
      kv: KeyValueReader = kvStore,
      objects: ObjectReader = objectStore,
      vectors: VectorReader = vectorStore
  ): RuntimeStores =
    RuntimeStores(
      objects,
      kv,
      kvStore,
      graphReader,
      vectors,
      vectorStore,
      EmbeddingPolicy.SyntheticTestOnly
    )

  def workflow(
      c: WorkflowConfig = Phase9Corpus.config(),
      rt: RuntimeStores = runtime()
  ): CollaborationWorkflow =
    new CollaborationWorkflow(rt, Phase9Corpus.serving, c, new TestClock)
}
