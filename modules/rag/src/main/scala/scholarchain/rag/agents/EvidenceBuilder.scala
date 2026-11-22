package scholarchain.rag.agents

import java.net.{URLDecoder, URLEncoder}
import java.nio.charset.StandardCharsets

import upickle.default.ReadWriter
import upickle.implicits.allowUnknownKeys

import scholarchain.core.{
  Canonical,
  CoauthorEdgeV2,
  DocumentType,
  EvidenceBundle,
  EvidenceId,
  EvidenceItem,
  EvidenceType,
  Json,
  Provenance,
  SchemaVersions,
  SourceType,
  TimeWindow
}
import scholarchain.rag.semantic.{RenderTemplates, SemanticContextItem}
import scholarchain.storage.model.{
  EntityLinkRecord,
  ItemCodecs,
  ObjectRef,
  PrimaryKey,
  TableKeys,
  TableRole,
  VectorKeys
}
import scholarchain.storage.port.{KeyValueReader, ObjectReader}

/**
 * The record schemas of the evidence TEXT that rag itself writes (RE-P9-02). Stored analysis rows
 * keep their own core record schema (`scholarchain.researcher-metrics.v2`, ...); these name the
 * runtime records. Each is the `provenance.sourceSchemaVersion` of its evidence items, so
 * [[BundleFacts]] knows how to read an item's text.
 */
object EvidenceSchemas {
  val EntityLink = "scholarchain.rag-entity-link.v1"

  /** v2 (RE-P9-04, M1): adds `searchTruncated` and the `found-not-proven-shortest` outcome. */
  val PathTraversal = "scholarchain.rag-path-traversal.v2"
  val StoredRanking = "scholarchain.rag-stored-ranking.v1"
  val PublicationSupport = "scholarchain.rag-publication-support.v1"
  val SemanticItem = "scholarchain.rag-semantic-item.v1"

  /** The stored analysis record schemas an evidence item may carry. */
  val StoredRecords: Set[String] = Set(
    SchemaVersions.ResearcherMetricsV2,
    SchemaVersions.CoauthorEdgeV2,
    SchemaVersions.ComponentMembership,
    SchemaVersions.CommunityMembership,
    SchemaVersions.BridgeMeasure,
    SchemaVersions.Coreness,
    SchemaVersions.PathLengthEstimate
  )
}

/**
 * The outcome of one bounded traversal (stage 4), as evidence text. `found`: `nodeIds` in path
 * order, `hopEvidenceIds` the coauthor-edge items of each hop (in the same bundle), `hopCount` the
 * traversal's hop count, a PROVEN shortest path. `found-not-proven-shortest`: a connecting path of
 * `hopCount` hops, found after a neighbour list was cut (`searchTruncated`, `reason`), so only an
 * upper bound. `not-connected-within`: no path of at most `maxDepth` hops exists and every
 * neighbour list was complete. `inconclusive`: the search was cut (`reason`). `searchTruncated` is
 * true exactly when a neighbour list read by the search reached the result limit (or the visit
 * bound stopped it); no shortest-path fact is ever read from a truncated traversal.
 */
@allowUnknownKeys(false)
final case class PathTraversalEvidence(
    schemaVersion: String,
    template: String,
    sourceId: String,
    targetId: String,
    window: String,
    maxDepth: Int,
    outcome: String,
    nodeIds: Vector[String],
    hopEvidenceIds: Vector[String],
    hopCount: Option[Int],
    reason: Option[String],
    executedQueries: Vector[String],
    searchTruncated: Boolean
) derives ReadWriter

object PathTraversalEvidence {
  val Found = "found"
  val FoundNotProvenShortest = "found-not-proven-shortest"
  val NotConnected = "not-connected-within"
  val Inconclusive = "inconclusive"
}

/** One entry of a stored ranking (MC-5) as evidence: the stored rank and its record items. */
@allowUnknownKeys(false)
final case class RankingEntryEvidence(rank: Int, subjectId: String, evidenceIds: Vector[String])
    derives ReadWriter

/** A stored top-k ranking as evidence text (the order is the loader's stored order). */
@allowUnknownKeys(false)
final case class RankingEvidence(
    schemaVersion: String,
    metric: String,
    role: String,
    window: String,
    orderingRule: String,
    entries: Vector[RankingEntryEvidence],
    truncated: Boolean
) derives ReadWriter

/** One supporting publication of a coauthor edge (copied from the stored edge record). */
@allowUnknownKeys(false)
final case class PublicationSupportEvidence(
    schemaVersion: String,
    workId: String,
    coauthorEdgeId: String,
    researcherA: String,
    researcherB: String,
    window: String
) derives ReadWriter

/**
 * Evidence source URIs. Stored rows: `scholarchain-kv://<table tag>/<pk>/<sk>` (pk and sk
 * URL-encoded), resolvable through the key-value READ port. Runtime records:
 * `scholarchain-runtime://<kind>/<sha256 of the text>`. Semantic texts: `s3://<bucket>/<key>`.
 */
object RowPointer {
  val KvScheme = "scholarchain-kv"
  val RuntimeScheme = "scholarchain-runtime"

  private def enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8)
  private def dec(s: String) = URLDecoder.decode(s, StandardCharsets.UTF_8)

  def kv(table: String, pk: String, sk: String): String =
    s"$KvScheme://$table/${enc(pk)}/${enc(sk)}"

  def runtime(kind: String, text: String): String =
    s"$RuntimeScheme://$kind/${Canonical.sha256HexUtf8(text)}"

  def s3(bucket: String, key: String): String = s"s3://$bucket/$key"

  /** (table, key) of a stored-row URI. */
  def parseKv(uri: String): Option[(TableRole, PrimaryKey)] =
    Option(uri).filter(_.startsWith(s"$KvScheme://")).flatMap { u =>
      u.stripPrefix(s"$KvScheme://").split("/", -1).toVector match {
        case Vector(t, pk, sk) =>
          TableRole.values.find(_.tag == t).map(_ -> PrimaryKey(dec(pk), dec(sk)))
        case _ => None
      }
    }

  /** (kind, text hash) of a runtime URI. */
  def parseRuntime(uri: String): Option[(String, String)] =
    Option(uri).filter(_.startsWith(s"$RuntimeScheme://")).flatMap { u =>
      u.stripPrefix(s"$RuntimeScheme://").split("/", -1).toVector match {
        case Vector(k, h) if Canonical.isHex64(h) => Some(k -> h)
        case _ => None
      }
    }

  /** (bucket, key) of an S3 URI. */
  def parseS3(uri: String): Option[(String, String)] =
    Option(uri).filter(_.startsWith("s3://")).flatMap { u =>
      val rest = u.stripPrefix("s3://")
      val i = rest.indexOf('/')
      Option.when(i > 0 && i < rest.length - 1)(rest.substring(0, i) -> rest.substring(i + 1))
    }
}

/** The output of one plan step (stages 2-6), by plan step ordinal. */
enum StageOutput(val stepOrdinal: Int) {
  case Resolved(step: Int, name: String, candidate: ResearcherCandidate) extends StageOutput(step)
  case Metrics(step: Int, result: ResearcherMetricsResult) extends StageOutput(step)
  case Ranked(step: Int, ranking: Ranking) extends StageOutput(step)
  case Direct(step: Int, result: DirectCollaboratorsResult) extends StageOutput(step)
  case Edge(step: Int, result: DirectEdgeResult) extends StageOutput(step)
  case Path(step: Int, result: PathResult) extends StageOutput(step)
  case Community(step: Int, view: CommunityView) extends StageOutput(step)
  case Bridges(step: Int, ranking: BridgeRanking) extends StageOutput(step)
  case Semantic(step: Int, result: SemanticResult) extends StageOutput(step)
}

/**
 * Bounds of one evidence bundle (role law 7; configuration, no default).
 *
 * @param maxItems
 *   items of one bundle
 * @param maxBundleBytes
 *   UTF-8 bytes of the items' JSON
 * @param maxPublicationItems
 *   supporting-publication items per coauthor edge (the edge record lists them all)
 */
final case class EvidenceLimits private (
    maxItems: Int,
    maxBundleBytes: Int,
    maxPublicationItems: Int
)

object EvidenceLimits {
  def of(
      maxItems: Int,
      maxBundleBytes: Int,
      maxPublicationItems: Int
  ): Either[String, EvidenceLimits] =
    if (maxItems < 1) Left("maxItems: must be >= 1")
    else if (maxBundleBytes < 1) Left("maxBundleBytes: must be >= 1")
    else if (maxPublicationItems < 0) Left("maxPublicationItems: must be >= 0")
    else Right(new EvidenceLimits(maxItems, maxBundleBytes, maxPublicationItems))
}

/**
 * The assembled evidence: the core bundle, the step each item came from, and the split between
 * DETERMINISTIC graph and metric evidence and DESCRIPTIVE semantic context (never mixed: a fact is
 * read only from deterministic items, see [[BundleFacts]]).
 */
final case class BuiltEvidence(
    bundle: EvidenceBundle,
    stepEvidence: Map[Int, Vector[String]],
    deterministicIds: Vector[String],
    semanticIds: Vector[String],
    semanticOmittedByBudget: Int
)

/**
 * RE-P9-02 stage 7, EvidenceBuilderAgent. Builds a bounded core [[EvidenceBundle]] from the stage
 * outputs. Every item's text is the stored record or runtime record it stands for, so the bundle is
 * self-contained and every ID resolves ([[EvidenceResolver]]):
 *
 *   - `match-decision`: the entity-link row of a resolved name (EntityLinkCache pointer);
 *   - `metric`: an analysis-results row (researcher metrics, memberships, bridge, coreness), with
 *     the stored record JSON; a stored ranking and a path traversal outcome (runtime records);
 *   - `coauthor-edge`: a hop's stored coauthor-edge row, path = its two researchers;
 *   - `source-record`: a supporting publication of an edge (bounded per edge);
 *   - `graph-path`: a found shortest path (node IDs in path order);
 *   - `text-chunk`: one semantic context item (descriptive only).
 *
 * Deterministic items come first and must all fit the limits (else `BudgetExceeded`: no answer is
 * built on a cut deterministic bundle); semantic items are added while they fit, the rest counted.
 */
final class EvidenceBuilderAgent(
    serving: ServingContext,
    limits: EvidenceLimits,
    textBucket: String
) {

  private def windowText(w: TimeWindow): String = f"${w.startYear}%04d-${w.endYear}%04d"

  private def make(
      t: EvidenceType,
      uri: String,
      workId: Option[String],
      chunkId: Option[String],
      path: Option[Vector[String]],
      score: Double,
      text: Option[String],
      prov: Provenance
  ): Either[AgentError, EvidenceItem] =
    EvidenceId
      .of(t, uri, workId, chunkId, path.getOrElse(Vector.empty), prov.runId)
      .left
      .map(e => AgentError.Corrupt(s"evidence ID: ${e.message}"))
      .flatMap { id =>
        val it = EvidenceItem(
          SchemaVersions.EvidenceItem,
          id,
          t,
          workId,
          uri,
          chunkId,
          path,
          score,
          text,
          prov
        )
        val errors = EvidenceItem.contract.validate(it)
        if (errors.isEmpty) Right(it)
        else Left(AgentError.Corrupt(s"evidence item: ${errors.map(_.message).mkString("; ")}"))
      }

  private def stored(ref: StoredRecordRef, t: EvidenceType, path: Option[Vector[String]]) =
    make(
      t,
      RowPointer.kv(ref.table, ref.pk, ref.sk),
      None,
      None,
      path,
      0.0,
      Some(ref.recordJson),
      Provenance(
        SourceType.Derived,
        ref.subjectId,
        serving.snapshot,
        ref.runId,
        ref.recordSchemaVersion,
        ref.policyVersion,
        None,
        None
      )
    )

  private def runtime(
      kind: String,
      t: EvidenceType,
      schema: String,
      policy: String,
      text: String,
      path: Option[Vector[String]],
      subject: String
  ) =
    make(
      t,
      RowPointer.runtime(kind, text),
      None,
      None,
      path,
      0.0,
      Some(text),
      Provenance(
        SourceType.QueryRuntime,
        subject,
        serving.snapshot,
        serving.analysisRunId,
        schema,
        Some(policy),
        None,
        None
      )
    )

  private def link(c: ResearcherCandidate): Either[AgentError, EvidenceItem] =
    for {
      key <- TableKeys.EntityLinkCache
        .link(c.link.surfaceSha256, c.link.researcherId)
        .left
        .map(e => AgentError.Corrupt(e.message))
      it <- make(
        EvidenceType.MatchDecision,
        RowPointer.kv(TableRole.EntityLinkCache.tag, key.pk, key.sk),
        None,
        None,
        None,
        0.0,
        Some(upickle.default.write(c.link)),
        Provenance(
          SourceType.Derived,
          c.researcherId,
          serving.snapshot,
          c.link.sourceRunId,
          EvidenceSchemas.EntityLink,
          Some(SurfaceForms.Version),
          None,
          None
        )
      )
    } yield it

  private def pairPath(a: String, b: String): Vector[String] =
    if (Canonical.compareUtf8(a, b) <= 0) Vector(a, b) else Vector(b, a)

  /** The edge item and its bounded publication items of one hop. */
  private def hop(h: Hop): Either[AgentError, (EvidenceItem, Vector[EvidenceItem])] = {
    val pair = pairPath(h.fromId, h.toId)
    for {
      edge <- stored(h.evidence.source, EvidenceType.CoauthorEdge, Some(pair))
      pubs <- all(h.evidence.supportingWorkIds.take(limits.maxPublicationItems)) { w =>
        val text = upickle.default.write(
          PublicationSupportEvidence(
            EvidenceSchemas.PublicationSupport,
            w,
            h.evidence.coauthorEdgeId,
            pair(0),
            pair(1),
            h.evidence.source.window
          )
        )
        make(
          EvidenceType.SourceRecord,
          RowPointer.kv(h.evidence.source.table, h.evidence.source.pk, h.evidence.source.sk),
          Some(w),
          None,
          None,
          0.0,
          Some(text),
          Provenance(
            SourceType.Derived,
            w,
            serving.snapshot,
            h.evidence.source.runId,
            EvidenceSchemas.PublicationSupport,
            h.evidence.source.policyVersion,
            None,
            None
          )
        )
      }
    } yield (edge, pubs)
  }

  private def all[A, B](
      xs: Vector[A]
  )(f: A => Either[AgentError, B]): Either[AgentError, Vector[B]] =
    xs.foldLeft[Either[AgentError, Vector[B]]](Right(Vector.empty))((acc, x) =>
      acc.flatMap(bs => f(x).map(bs :+ _))
    )

  private def hops(hs: Vector[Hop]): Either[AgentError, Vector[EvidenceItem]] =
    all(hs)(hop).map(_.flatMap { case (e, ps) => e +: ps })

  private def readings(rs: Vector[MetricReading]): Either[AgentError, Vector[EvidenceItem]] =
    all(rs.map(_.source).distinct)(stored(_, EvidenceType.Metric, None))

  private def refs(rs: Vector[StoredRecordRef]): Either[AgentError, Vector[EvidenceItem]] =
    all(rs.distinct)(stored(_, EvidenceType.Metric, None))

  private def pathItems(p: PathResult): Either[AgentError, Vector[EvidenceItem]] = {
    val w = windowText(p.window)
    def traversal(
        outcome: String,
        nodes: Vector[String],
        hopIds: Vector[String],
        count: Option[Int],
        reason: Option[String],
        t: EvidenceType,
        truncated: Boolean
    ) = {
      val text = upickle.default.write(
        PathTraversalEvidence(
          EvidenceSchemas.PathTraversal,
          p.template.tag,
          p.sourceId,
          p.targetId,
          w,
          p.maxDepth,
          outcome,
          nodes,
          hopIds,
          count,
          reason,
          p.executedQueries,
          truncated
        )
      )
      runtime(
        "path-traversal",
        t,
        EvidenceSchemas.PathTraversal,
        p.template.tag,
        text,
        Option.when(t == EvidenceType.GraphPath)(nodes),
        p.sourceId
      )
    }
    p.outcome match {
      case PathOutcome.Found(nodes, hs) =>
        for {
          hopItems <- all(hs)(hop)
          edgeIds = hopItems.map(_._1.evidenceId)
          path <- traversal(
            PathTraversalEvidence.Found,
            nodes,
            edgeIds,
            Some(hs.size),
            None,
            EvidenceType.GraphPath,
            false
          )
        } yield path +: hopItems.flatMap { case (e, ps) => e +: ps }
      case PathOutcome.FoundNotProvenShortest(nodes, hs, why) =>
        for {
          hopItems <- all(hs)(hop)
          edgeIds = hopItems.map(_._1.evidenceId)
          path <- traversal(
            PathTraversalEvidence.FoundNotProvenShortest,
            nodes,
            edgeIds,
            Some(hs.size),
            Some(why),
            EvidenceType.GraphPath,
            true
          )
        } yield path +: hopItems.flatMap { case (e, ps) => e +: ps }
      case PathOutcome.NotConnectedWithin(d) =>
        traversal(
          PathTraversalEvidence.NotConnected,
          Vector(p.sourceId, p.targetId),
          Vector.empty,
          None,
          Some(s"no path of at most $d hops"),
          EvidenceType.Metric,
          false
        ).map(Vector(_))
      case PathOutcome.Inconclusive(why) =>
        traversal(
          PathTraversalEvidence.Inconclusive,
          Vector(p.sourceId, p.targetId),
          Vector.empty,
          None,
          Some(why),
          EvidenceType.Metric,
          true
        ).map(Vector(_))
    }
  }

  private def ranking(
      metric: String,
      role: String,
      w: TimeWindow,
      rule: String,
      truncated: Boolean,
      entries: Vector[(Int, String, Vector[EvidenceItem])]
  ): Either[AgentError, Vector[EvidenceItem]] = {
    val text = upickle.default.write(
      RankingEvidence(
        EvidenceSchemas.StoredRanking,
        metric,
        role,
        windowText(w),
        rule,
        entries.map { case (r, s, its) => RankingEntryEvidence(r, s, its.map(_.evidenceId)) },
        truncated
      )
    )
    runtime(
      "stored-ranking",
      EvidenceType.Metric,
      EvidenceSchemas.StoredRanking,
      rule,
      text,
      None,
      role
    )
      .map(_ +: entries.flatMap(_._3))
  }

  private def semantic(r: SemanticResult): Either[AgentError, Vector[EvidenceItem]] =
    all(r.retrievals.flatMap(x => x.context.items.map(x.context -> _))) { case (ctx, it) =>
      for {
        docType <- DocumentType
          .fromTag(it.documentType)
          .toRight(AgentError.Corrupt(s"semantic item: document type ${it.documentType}"))
        chunk <- VectorKeys
          .chunkId(it.subjectId, docType, it.textSha256)
          .left
          .map(e => AgentError.Corrupt(e.message))
        out <- make(
          EvidenceType.TextChunk,
          RowPointer.s3(textBucket, it.textS3Key),
          None,
          Some(chunk),
          None,
          it.distance,
          Some(upickle.default.write(it)),
          Provenance(
            SourceType.Derived,
            it.subjectId,
            serving.snapshot,
            serving.analysisRunId,
            EvidenceSchemas.SemanticItem,
            Some(RenderTemplates.Version),
            Some(ctx.modelVersion),
            None
          )
        )
      } yield out
    }

  /** The deterministic items of one stage output (semantic outputs yield none). */
  private def deterministic(o: StageOutput): Either[AgentError, Vector[EvidenceItem]] =
    o match {
      case StageOutput.Resolved(_, _, c) =>
        for {
          l <- link(c)
          id <- all(c.identity.toVector.map(_._2))(stored(_, EvidenceType.Metric, None))
        } yield l +: id
      case StageOutput.Metrics(_, r) => readings(r.readings)
      case StageOutput.Ranked(_, r) =>
        all(r.entries)(e => readings(e.readings).map(its => (e.rank, e.subjectId, its)))
          .flatMap(es => ranking(r.metric.tag, r.role, r.window, r.orderingRule, r.truncated, es))
      case StageOutput.Direct(_, r) => hops(r.hops)
      case StageOutput.Edge(_, r) => hops(r.hop.toVector)
      case StageOutput.Path(_, p) => pathItems(p)
      case StageOutput.Community(_, v) =>
        refs(
          v.community.map(_._2).toVector ++ v.component.map(_._2) ++
            v.structuralBridge.map(_.source) ++ v.directDegree.map(_.reading.source) ++
            v.crossCommunityEdges.map(_.communitySource)
        )
      case StageOutput.Bridges(_, b) =>
        all(b.entries)(e =>
          refs(e.structuralBridge.source +: e.directDegree.map(_.reading.source).toVector)
            .map(its => (e.rank, e.researcherId, its))
        ).flatMap(es =>
          ranking(
            scholarchain.core.CollaborationMetric.CrossCommunityDegree.tag,
            AnalysisRoles.BridgeMeasures,
            b.window,
            b.orderingRule,
            b.truncated,
            es
          )
        )
      case StageOutput.Semantic(_, _) => Right(Vector.empty)
    }

  private def bytes(it: EvidenceItem): Int =
    upickle.default.write(it).getBytes(StandardCharsets.UTF_8).length

  def build(queryId: String, outputs: Vector[StageOutput]): Either[AgentError, BuiltEvidence] =
    for {
      det <- all(outputs)(o => deterministic(o).map(o.stepOrdinal -> _))
      sem <- all(outputs.collect { case s: StageOutput.Semantic => s })(s =>
        semantic(s.result).map(s.stepOrdinal -> _)
      )
      detItems = distinctById(det.flatMap(_._2))
      _ <-
        if (detItems.size > limits.maxItems)
          Left(
            AgentError.BudgetExceeded(
              s"${detItems.size} deterministic evidence items exceed maxItems ${limits.maxItems}"
            )
          )
        else Right(())
      detBytes = detItems.map(bytes).sum
      _ <-
        if (detBytes > limits.maxBundleBytes)
          Left(
            AgentError.BudgetExceeded(
              s"deterministic evidence of $detBytes bytes exceeds maxBundleBytes ${limits.maxBundleBytes}"
            )
          )
        else Right(())
      semItems = distinctById(sem.flatMap(_._2)).filterNot(i =>
        detItems.exists(_.evidenceId == i.evidenceId)
      )
      kept = fit(semItems, limits.maxItems - detItems.size, limits.maxBundleBytes - detBytes)
      items = detItems ++ kept
      prov = Provenance(
        SourceType.QueryRuntime,
        queryId,
        serving.snapshot,
        serving.analysisRunId,
        SchemaVersions.QueryPlan,
        Some(EvidenceBuilderAgent.Version),
        None,
        None
      )
      bundle <- EvidenceBundle
        .build(queryId, items, prov)
        .left
        .map(es => AgentError.Corrupt(s"bundle: ${es.map(_.message).mkString("; ")}"))
    } yield {
      val keptIds = items.map(_.evidenceId).toSet
      val steps = (det ++ sem)
        .groupBy(_._1)
        .view
        .mapValues(_.flatMap(_._2.map(_.evidenceId)).distinct.filter(keptIds))
        .toMap
      BuiltEvidence(
        bundle,
        steps,
        detItems.map(_.evidenceId),
        kept.map(_.evidenceId),
        semItems.size - kept.size
      )
    }

  private def distinctById(xs: Vector[EvidenceItem]): Vector[EvidenceItem] =
    xs.foldLeft(Vector.empty[EvidenceItem])((acc, x) =>
      if (acc.exists(_.evidenceId == x.evidenceId)) acc else acc :+ x
    )

  private def fit(xs: Vector[EvidenceItem], items: Int, room: Int): Vector[EvidenceItem] =
    xs.foldLeft((Vector.empty[EvidenceItem], room)) { case ((kept, left), x) =>
      val b = bytes(x)
      if (kept.size < items && b <= left) (kept :+ x, left - b) else (kept, left)
    }._1
}

object EvidenceBuilderAgent {
  val Version = "scholarchain.rag.evidence-builder.v1"
}

/** Why an evidence item does not resolve to stored data. */
final case class UnresolvedEvidence(evidenceId: String, reason: String)

/**
 * RE-P9-02: resolves every evidence item of a bundle to the data it claims to stand for, through
 * the READ ports only (one call per stored item, bounded by the stage call budget):
 *
 *   - `scholarchain-kv` analysis rows: the row decodes (hash-checked by storage's codec), belongs
 *     to the item's run and holds exactly the item's text; a publication item's work is listed by
 *     the stored edge record;
 *   - `scholarchain-kv` entity links: the row decodes and equals the item's text;
 *   - `scholarchain-runtime` records: the URI hash is the text hash, and every evidence ID the
 *     record cites is in the bundle;
 *   - `s3` semantic items: the text object exists, its SHA-256 is the item's `textSha256`, and the
 *     chunk ID commits to that hash.
 */
final class EvidenceResolver(
    kv: KeyValueReader,
    objects: ObjectReader,
    limits: AgentLimits,
    clock: AgentClock
) {

  def resolve(bundle: EvidenceBundle): Either[AgentError, Vector[UnresolvedEvidence]] = {
    val budget = new CallBudget(limits, clock)
    val ids = bundle.items.map(_.evidenceId).toSet
    bundle.items.foldLeft[Either[AgentError, Vector[UnresolvedEvidence]]](Right(Vector.empty)) {
      (acc, it) =>
        acc.flatMap(bad =>
          one(budget, ids, it).map(r => bad ++ r.map(UnresolvedEvidence(it.evidenceId, _)))
        )
    }
  }

  private def one(
      budget: CallBudget,
      ids: Set[String],
      it: EvidenceItem
  ): Either[AgentError, Option[String]] = {
    val text = it.text.getOrElse("")
    (
      RowPointer.parseKv(it.sourceUri),
      RowPointer.parseRuntime(it.sourceUri),
      RowPointer.parseS3(it.sourceUri)
    ) match {
      case (Some((TableRole.ResearcherMetricCache, key)), _, _) =>
        budget.keyValue("resolve analysis row")(kv.get(TableRole.ResearcherMetricCache, key)).map {
          case None => Some("stored row absent")
          case Some(item) =>
            ItemCodecs.AnalysisResultCodec.decode(item) match {
              case Left(e) => Some(s"stored row undecodable: ${e.message}")
              case Right(row) if row.runId != it.provenance.runId =>
                Some("stored row of another run")
              case Right(row) if it.evidenceType == EvidenceType.SourceRecord =>
                Json.decode[CoauthorEdgeV2](row.record) match {
                  case Right(e) if it.workId.exists(e.supportingWorkIds.contains) => None
                  case _ => Some("the stored edge record does not list the work")
                }
              case Right(row) if row.record != text =>
                Some("stored record differs from the evidence text")
              case Right(_) => None
            }
        }
      case (Some((TableRole.EntityLinkCache, key)), _, _) =>
        budget.keyValue("resolve entity link")(kv.get(TableRole.EntityLinkCache, key)).map {
          case None => Some("entity link absent")
          case Some(item) =>
            ItemCodecs.EntityLinkCodec.decode(item) match {
              case Right(l) if upickle.default.write[EntityLinkRecord](l) == text => None
              case Right(_) => Some("entity link differs from the evidence text")
              case Left(e) => Some(s"entity link undecodable: ${e.message}")
            }
        }
      case (Some((other, _)), _, _) => Right(Some(s"table ${other.tag} is not an evidence table"))
      case (_, Some((_, hash)), _) =>
        Right(
          if (Canonical.sha256HexUtf8(text) != hash)
            Some("runtime record differs from its URI hash")
          else
            BundleFacts.citedIds(it) match {
              case Left(why) => Some(why)
              case Right(cited) =>
                cited.find(c => !ids.contains(c)).map(c => s"cites $c, which is not in the bundle")
            }
        )
      case (_, _, Some((bucket, key))) =>
        scala.util.Try(upickle.default.read[SemanticContextItem](text)).toOption match {
          case None => Right(Some("semantic evidence text is not a semantic item"))
          case Some(si) =>
            budget.keyValue("resolve context text")(objects.get(ObjectRef(bucket, key))).map {
              case None => Some("context text absent")
              case Some((_, body)) if body.sha256 != si.textSha256 =>
                Some("context text differs from its hash")
              case Some(_) =>
                DocumentType
                  .fromTag(si.documentType)
                  .flatMap(d => VectorKeys.chunkId(si.subjectId, d, si.textSha256).toOption) match {
                  case Some(c) if it.chunkId.contains(c) => None
                  case _ => Some("chunk ID does not commit to the context text")
                }
            }
        }
      case _ => Right(Some(s"unknown source URI ${it.sourceUri}"))
    }
  }
}
