package scholarchain.services

import java.nio.charset.StandardCharsets
import java.util.Base64

import scholarchain.core.{Canonical, IdKind, Json, StableId, TimeWindow}
import scholarchain.rag.agents.AuditRecord
import scholarchain.storage.model.{
  BoundQuery,
  GraphParamValue,
  GraphQuery,
  GraphRow,
  GraphValue,
  ItemCodecs,
  StoreError,
  TableKeys,
  TableRole
}
import scholarchain.storage.port.{GraphReader, KeyQuery, KeyValueReader, SortKeyCondition}

/** Shared failure mapping of the read services. */
private[services] object Reads {
  def badId(field: String, kind: IdKind): ApiError =
    ApiError(ErrorCode.InvalidParameter, s"$field: not a valid '${kind.prefix}:' ID")

  /** A row that fails its codec or contract is a downstream integrity failure (502). */
  def corrupt(what: String): ApiError =
    ApiError(ErrorCode.DownstreamFailure, s"a stored $what failed its integrity check")

  def all[A, B](xs: Vector[A])(f: A => Either[ApiError, B]): Either[ApiError, Vector[B]] =
    xs.foldLeft[Either[ApiError, Vector[B]]](Right(Vector.empty))((acc, x) =>
      acc.flatMap(bs => f(x).map(bs :+ _))
    )

  /** A key builder's refusal of a validated ID is an internal error. */
  def key[A](e: Either[StoreError, A]): Either[ApiError, A] =
    e.left.map(_ => ApiError(ErrorCode.Internal, "could not build a store key"))
}

/**
 * The Evidence API: resolves one evidence ID to its stored evidence items (EvidenceBundle table,
 * access pattern EB-4 `byEvidence`, one item row per bundle that cites it) and returns its
 * provenance, source record pointer, source URI, graph path or text pointer and, PER BUNDLE, the
 * query-dependent fields: score and compact supporting content (cut to the configured bound).
 *
 * SE-P10-02 M1: an evidence ID (core `EvidenceId`) is derived from type, source URI, work, chunk,
 * graph path and run only; score and text are not inputs, and a semantic item's score (distance)
 * and text (the retrieved excerpt with its rank) depend on the question that retrieved it. Rows of
 * one ID are therefore checked for equal IDENTITY fields only (type, source URI, work, chunk, graph
 * path, provenance, run); a difference there is a store integrity failure (502). Scores are never
 * merged into one value. The content is stored data, never instructions.
 *
 * SE-P10-04 m1 (human decision 2026-11-22, supersedes the SE-P10-03 note): CALLER-ONLY occurrences.
 * Any client with the `read` scope may resolve an evidence ID that has stored rows and receives its
 * identity and provenance (type, source URI, work, chunk, graph path, run). The query-dependent
 * part, the `occurrences` (per bundle: bundle ID, score and excerpt), is listed ONLY for bundles of
 * queries the calling client submitted ([[QueryOwnership]]); other clients' occurrences are not
 * listed (no score, excerpt or bundle ID), and `occurrencesTruncated` is computed over the caller's
 * own listed occurrences. The stored row's query ID is used internally and never returned. This
 * removes the direct disclosure of CR-P10-04 m1; it does NOT remove every side channel. Two remain,
 * accepted by the human as recorded residuals (2026-11-22, CR-P10-05):
 *
 *   - F1 (scan window before the ownership filter): at most `evidenceScanLimit` rows of the ID are
 *     read, in sort-key (bundle ID) order, and only then filtered by ownership. Foreign bundles
 *     that sort before the caller's own can push the caller's occurrence out of the window, so a
 *     caller who polls an evidence ID it cited can observe its own occurrence disappear and so
 *     learn a rough count of foreign co-citations and when they happened. A caller's own occurrence
 *     beyond the window is also simply not listed (completeness).
 *   - F2 (existence is shared): evidence rows are written only by per-query audits (one item row
 *     per bundle), and an evidence ID is computable from public inputs (type, source URI, work,
 *     chunk, path, run). So a 404 that turns into 200 tells any `read` client that SOME query
 *     (possibly another client's) cited that evidence for the first time, and roughly when. 404
 *     means "no query has cited this ID yet", not a property of the published run alone.
 *
 * Deferred fix (both): an owner-keyed evidence index (the caller's rows read directly, no shared
 * scan window) and/or a published-run evidence table written by the analysis run rather than by
 * queries, so existence no longer depends on query activity: storage-engineer (table, adapter),
 * possibly rag-engineer (what the audit writes) and domain-engineer (keys and contracts).
 *
 * U-c (recorded, deployment configuration): `evidenceScanLimit` is not validated against the key-
 * value adapter's maximum query limit; a value above it makes the adapter refuse the query, which
 * is a 500 here. Owners: domain-engineer (configuration contract), infra-engineer (deployed value).
 *
 * An ownership outage fails closed (the read policy's 503/504/502; never a list built without the
 * ownership check).
 */
final class EvidenceService(
    kv: KeyValueReader,
    read: ReadPolicy,
    config: ServiceConfig,
    deadline: () => Deadline,
    ownership: QueryOwnership,
    ownershipRead: ReadPolicy
) {

  /** The identity of an item: everything except the query-dependent score and text. */
  private[services] def identity(i: scholarchain.core.EvidenceItem) =
    i.copy(score = 0.0, text = None)

  def get(client: ApiClient, evidenceId: String): Either[ApiError, Reply] = {
    val until = deadline()
    for {
      _ <- StableId
        .validate(IdKind.Evidence, evidenceId, "evidenceId")
        .left
        .map(_ => Reads.badId("evidenceId", IdKind.Evidence))
      partition <- Reads.key(TableKeys.EvidenceBundle.byEvidence(evidenceId))
      items <- read.read(until)(
        kv.query(
          TableRole.EvidenceBundle,
          KeyQuery(Some("byEvidence"), partition, SortKeyCondition.All, config.evidenceScanLimit)
        )
      )
      all <- Reads.all(items)(i =>
        ItemCodecs.EvidenceItemRowCodec.decode(i).left.map(_ => Reads.corrupt("evidence item"))
      )
      first <- all.headOption.toRight(
        ApiError(ErrorCode.NotFound, "no stored evidence has this ID")
      )
      _ <-
        if (
          all.forall(r =>
            r.item.evidenceId == evidenceId && identity(r.item) == identity(first.item)
          )
        ) Right(())
        else Left(Reads.corrupt("evidence item"))
      owned <- ownershipRead.read(until)(
        ownership.ownedBy(client.clientId, all.map(_.queryId).distinct)
      )
    } yield {
      val mine = all.filter(r => owned(r.queryId))
      val rows = mine.take(config.evidenceMaxBundles)
      val item = first.item
      val core = ujson.read(Json.write(item)).obj
      def content(text: Option[String]): ujson.Obj = {
        val t = text.getOrElse("")
        val shown = Js.cutUtf8(t, config.evidenceMaxContentBytes)
        ujson.Obj(
          "text" -> Js.opt(text.map(_ => ujson.Str(shown))),
          "bytes" -> Js.utf8Bytes(t),
          "truncated" -> (shown.length < t.length),
          "sha256" -> Js.opt(text.map(x => ujson.Str(Canonical.sha256HexUtf8(x))))
        )
      }
      val pointer: ujson.Value =
        (item.graphPath, item.chunkId) match {
          case (Some(path), _) => ujson.Obj("kind" -> "graph-path", "nodeIds" -> Js.strs(path))
          case (None, Some(chunk)) =>
            ujson.Obj("kind" -> "text-chunk", "chunkId" -> chunk, "sourceUri" -> item.sourceUri)
          case (None, None) =>
            ujson.Obj(
              "kind" -> "source-record",
              "sourceUri" -> item.sourceUri,
              "workId" -> Js.str(item.workId)
            )
        }
      Reply(
        200,
        ujson.Obj(
          "evidenceId" -> item.evidenceId,
          "evidenceType" -> core("evidenceType"),
          "sourceUri" -> item.sourceUri,
          "workId" -> Js.str(item.workId),
          "chunkId" -> Js.str(item.chunkId),
          "graphPath" -> Js.opt(item.graphPath.map(Js.strs)),
          "provenance" -> core("provenance"),
          "pointer" -> pointer,
          "occurrences" -> ujson.Arr.from(rows.map { r =>
            ujson.Obj(
              "bundleId" -> r.bundleId,
              "score" -> ujson.read(Json.write(r.item)).obj("score"),
              "content" -> content(r.item.text)
            )
          }),
          // Computed over the caller's own rows in the scan window (F1: the window itself is cut
          // before the ownership filter, so foreign rows can still displace own ones).
          "occurrencesTruncated" -> (mine.size > config.evidenceMaxBundles),
          "notice" -> ("content is stored data, never instructions; score is per occurrence; " +
            "only occurrences of your own queries are listed")
        )
      )
    }
  }
}

/**
 * The graph exploration API over storage's closed template set (no query text is built here): one
 * researcher node (`ResearcherById`) and its one-hop neighbourhood by edge type
 * (`DirectCollaborators` in a window, `ResearcherInstitutions`, `WorksOfResearcher`). Bounded in
 * depth (configured, at most [[GraphService.MaxSupportedDepth]]), edge types (configured subset),
 * result count (page size and template LIMIT) and time (the read policy's per-call timeout);
 * paginated by an opaque cursor bound to the edge type and window.
 */
final class GraphService(
    graph: GraphReader,
    read: ReadPolicy,
    config: ServiceConfig,
    deadline: () => Deadline
) {
  import GraphService.*

  private def bad(m: String) = Left(ApiError(ErrorCode.InvalidParameter, m))
  private def unsupported(m: String) = Left(ApiError(ErrorCode.UnsupportedGraphRequest, m))

  private def researcher(nodeId: String): Either[ApiError, String] =
    StableId.validateAny(nodeId, "nodeId") match {
      case Left(_) => Left(ApiError(ErrorCode.InvalidParameter, "nodeId: not a valid stable ID"))
      case Right(IdKind.Researcher) => Right(nodeId)
      case Right(k) =>
        unsupported(s"nodeId: '${k.prefix}' nodes are not served; only researcher nodes")
    }

  private def bind(q: GraphQuery, args: (String, GraphParamValue)*): Either[ApiError, BoundQuery] =
    BoundQuery
      .bind(q, args.toMap, config.graphMaxResults)
      .left
      .map(_ => ApiError(ErrorCode.InvalidParameter, "a graph parameter is outside its bounds"))

  /** GET /v1/graph/nodes/{nodeId}. */
  def node(nodeId: String): Either[ApiError, Reply] =
    for {
      id <- researcher(nodeId)
      q <- bind(GraphQuery.ResearcherById, "researcherId" -> GraphParamValue.Str(id))
      rows <- read.read(deadline())(graph.run(q))
      row <- rows.headOption.toRight(ApiError(ErrorCode.NotFound, "no researcher node has this ID"))
    } yield Reply(
      200,
      ujson.Obj(
        "nodeId" -> id,
        "label" -> "Researcher",
        "properties" -> props(row, Vector("anchorNamespace", "identityStatus", "memberCount"))
      )
    )

  private def int(params: Map[String, String], name: String, default: Int): Either[ApiError, Int] =
    params.get(name) match {
      case None => Right(default)
      case Some(v) if v.matches("^[0-9]{1,9}$") => Right(v.toInt)
      case Some(_) => bad(s"$name: must be a non-negative integer")
    }

  private def window(
      params: Map[String, String],
      edgeType: String
  ): Either[ApiError, Option[TimeWindow]] =
    (edgeType, params.get("window")) match {
      case (CoauthoredWith, None) => Right(Some(config.graphDefaultWindow))
      case (CoauthoredWith, Some(w)) =>
        w match {
          case WindowText(a, b) =>
            TimeWindow
              .of(a.toInt, b.toInt)
              .left
              .map(_ => ApiError(ErrorCode.InvalidParameter, "window: not a valid window"))
              .map(Some(_))
          case _ => bad("window: expected SSSS-EEEE")
        }
      case (_, None) => Right(None)
      case (_, Some(_)) => unsupported(s"window: not applicable to edge type $edgeType")
    }

  /** GET /v1/graph/nodes/{nodeId}/neighbors?edgeType=&depth=&window=&limit=&cursor= */
  def neighbors(nodeId: String, params: Map[String, String]): Either[ApiError, Reply] =
    for {
      id <- researcher(nodeId)
      edgeType = params.getOrElse("edgeType", CoauthoredWith)
      _ <-
        if (!SupportedEdgeTypes.contains(edgeType))
          unsupported(s"edgeType: supported types are ${config.graphEdgeTypes.mkString(", ")}")
        else if (!config.graphEdgeTypes.contains(edgeType))
          unsupported(s"edgeType: $edgeType is not enabled")
        else Right(())
      depth <- int(params, "depth", 1)
      _ <-
        if (depth < 1) bad("depth: must be >= 1")
        else if (depth > config.graphMaxDepth)
          unsupported(s"depth: at most ${config.graphMaxDepth}")
        else Right(())
      limit <- int(params, "limit", config.graphDefaultPageSize)
      _ <-
        if (limit < 1 || limit > config.graphMaxPageSize)
          bad(s"limit: must be 1..${config.graphMaxPageSize}")
        else Right(())
      win <- window(params, edgeType)
      scope = s"$edgeType|${win.map(windowText).getOrElse("")}"
      offset <- params.get("cursor") match {
        case None => Right(0)
        case Some(c) =>
          Cursor
            .decode(c, scope)
            .toRight(ApiError(ErrorCode.InvalidParameter, "cursor: not valid for this request"))
      }
      fetch = offset.toLong + limit + 1
      _ <-
        if (fetch > config.graphMaxResults)
          unsupported(s"the page lies beyond the ${config.graphMaxResults}-result bound")
        else Right(())
      q <- edgeType match {
        case CoauthoredWith =>
          val w = win.getOrElse(config.graphDefaultWindow)
          bind(
            GraphQuery.DirectCollaborators,
            "researcherId" -> GraphParamValue.Str(id),
            "windowStartYear" -> GraphParamValue.IntValue(w.startYear),
            "windowEndYear" -> GraphParamValue.IntValue(w.endYear),
            "limit" -> GraphParamValue.IntValue(fetch.toInt)
          )
        case AffiliatedWith =>
          bind(
            GraphQuery.ResearcherInstitutions,
            "researcherId" -> GraphParamValue.Str(id),
            "limit" -> GraphParamValue.IntValue(fetch.toInt)
          )
        case _ =>
          bind(
            GraphQuery.WorksOfResearcher,
            "researcherId" -> GraphParamValue.Str(id),
            "limit" -> GraphParamValue.IntValue(fetch.toInt)
          )
      }
      rows <- read.read(deadline())(graph.run(q))
    } yield {
      val page = rows.slice(offset, offset + limit)
      val next = Option.when(rows.size > offset + limit)(Cursor.encode(offset + limit, scope))
      val (idColumn, columns) = Columns(edgeType)
      Reply(
        200,
        ujson.Obj(
          "nodeId" -> id,
          "edgeType" -> edgeType,
          "depth" -> depth,
          "window" -> Js.str(win.map(windowText)),
          "items" -> ujson.Arr.from(page.map { r =>
            ujson.Obj(
              "nodeId" -> value(r.columns.getOrElse(idColumn, GraphValue.Null)),
              "edgeType" -> edgeType,
              "hop" -> 1,
              "properties" -> props(r, columns)
            )
          }),
          "page" -> ujson.Obj(
            "limit" -> limit,
            "cursor" -> Js.str(params.get("cursor")),
            "nextCursor" -> Js.str(next)
          )
        )
      )
    }
}

object GraphService {
  val CoauthoredWith = "coauthored-with"
  val AffiliatedWith = "affiliated-with"
  val Authored = "authored"

  /** Edge types the closed template set can serve as a one-hop neighbourhood. */
  val SupportedEdgeTypes: Vector[String] = Vector(CoauthoredWith, AffiliatedWith, Authored)

  /** Every neighbourhood template is one hop. */
  val MaxSupportedDepth = 1

  /** Allowed query parameters of the neighbourhood route. */
  val NeighborParams: Set[String] = Set("edgeType", "depth", "window", "limit", "cursor")

  private[services] val WindowText = "^([0-9]{4})-([0-9]{4})$".r

  private[services] def windowText(w: TimeWindow): String = f"${w.startYear}%04d-${w.endYear}%04d"

  /** (neighbour ID column, property columns) of each edge type's template. */
  private[services] val Columns: Map[String, (String, Vector[String])] = Map(
    CoauthoredWith -> (
      "collaboratorId",
      Vector(
        "jointPublicationCount",
        "firstCollaborationYear",
        "lastCollaborationYear",
        "sourceEvidence"
      )
    ),
    AffiliatedWith -> ("institutionId", Vector("name")),
    Authored -> ("workId", Vector("year"))
  )

  private[services] def value(v: GraphValue): ujson.Value = v match {
    case GraphValue.Str(s) => ujson.Str(s)
    case GraphValue.IntValue(n) => ujson.Num(n.toDouble)
    case GraphValue.Null => ujson.Null
  }

  private[services] def props(r: GraphRow, columns: Vector[String]): ujson.Obj =
    ujson.Obj.from(columns.map(c => c -> value(r.columns.getOrElse(c, GraphValue.Null))))

  /**
   * Opaque page cursor: base64url of `v1|<offset>|<scope>`; a cursor of another scope is refused.
   */
  private[services] object Cursor {
    def encode(offset: Int, scope: String): String =
      Base64.getUrlEncoder.withoutPadding
        .encodeToString(s"v1|$offset|$scope".getBytes(StandardCharsets.UTF_8))

    def decode(c: String, scope: String): Option[Int] =
      if (c.length > 256) None
      else
        scala.util
          .Try(new String(Base64.getUrlDecoder.decode(c), StandardCharsets.UTF_8))
          .toOption
          .flatMap { s =>
            s.split("\\|", 3).toVector match {
              case Vector("v1", o, sc) if sc == scope && o.matches("^[0-9]{1,9}$") => Some(o.toInt)
              case _ => None
            }
          }
  }
}

/**
 * The explain and audit API: the stored audit record of a query (QuerySession QS-3), its executed
 * plan steps (QS-2) and the score components of its evidence bundle (EB-1). It returns the
 * structured QueryPlan as audited (operations, resolved IDs, hashed filters; never question text or
 * names), stages executed, prompt and model versions, evidence IDs, score components, counters and
 * verification status. No hidden reasoning is stored, so none can be returned.
 */
final class ExplainService(
    kv: KeyValueReader,
    read: ReadPolicy,
    config: ServiceConfig,
    deadline: () => Deadline,
    ownership: QueryOwnership,
    ownershipRead: ReadPolicy
) {

  private def unknown = ApiError(ErrorCode.NotFound, "no audit record for this query")

  /**
   * SE-P10-03 N1: served only to the client that submitted the query ([[QueryOwnership]]); an
   * unknown query, a query whose ownership record was forgotten and another client's query are the
   * same 404, decided before any audit read.
   *
   * SE-P10-02 m4: ONE deadline for the whole request; the audit, step and bundle reads share it.
   * U4: `auditSha256` is verified against `auditJson` by the storage codec on decode
   * (`QueryAuditRecord.problems`) and checked again here before it is returned.
   */
  def explain(client: ApiClient, queryId: String): Either[ApiError, Reply] = {
    val until = deadline()
    for {
      _ <- StableId
        .validate(IdKind.Query, queryId, "queryId")
        .left
        .map(_ => Reads.badId("queryId", IdKind.Query))
      // SE-P10-04 m2: the ownership lookup runs through the read policy; an ownership outage
      // is that policy's 503/504/502, never a 404. SE-P10-05 F4: outside the store lock.
      owner <- ownershipRead.read(until)(ownership.owner(queryId))
      _ <- if (owner.contains(client.clientId)) Right(()) else Left(unknown)
      key <- Reads.key(TableKeys.QuerySession.audit(queryId))
      got <- read.read(until)(kv.get(TableRole.QuerySession, key))
      item <- got.toRight(unknown)
      row <- ItemCodecs.QueryAuditCodec.decode(item).left.map(_ => Reads.corrupt("audit record"))
      audit <-
        if (
          row.auditSchemaVersion != AuditRecord.SchemaVersion || row.queryId != queryId ||
          Canonical.sha256HexUtf8(row.auditJson) != row.auditSha256
        )
          Left(Reads.corrupt("audit record"))
        else
          scala.util
            .Try(upickle.default.read[AuditRecord](row.auditJson))
            .toOption
            .filter(_.queryId == queryId)
            .toRight(Reads.corrupt("audit record"))
      partition <- Reads.key(TableKeys.QuerySession.partition(queryId))
      stepItems <- read.read(until)(
        kv.query(
          TableRole.QuerySession,
          KeyQuery(None, partition, SortKeyCondition.BeginsWith("step#"), config.explainMaxSteps)
        )
      )
      steps <- Reads.all(stepItems)(i =>
        ItemCodecs.QueryStepCodec.decode(i).left.map(_ => Reads.corrupt("plan step"))
      )
      bundle <- audit.bundleId match {
        case None => Right(None)
        case Some(b) =>
          for {
            k <- Reads.key(TableKeys.EvidenceBundle.bundle(b))
            got <- read.read(until)(kv.get(TableRole.EvidenceBundle, k))
            decoded <- got match {
              case None => Left(Reads.corrupt("evidence bundle"))
              case Some(i) =>
                ItemCodecs.EvidenceBundleCodec
                  .decode(i)
                  .left
                  .map(_ => Reads.corrupt("evidence bundle"))
                  .map(Some(_))
            }
          } yield decoded
      }
    } yield {
      val a = audit
      Reply(
        200,
        ujson.Obj(
          "queryId" -> a.queryId,
          "outcome" -> a.outcome,
          "reason" -> Js.str(a.reason),
          "plan" -> Js.opt(a.plan.map(p => upickle.default.writeJs(p))),
          "planVersion" -> Js.str(a.planVersion),
          "planSha256" -> Js.str(a.planSha256),
          "stagesExecuted" -> ujson.Arr.from(steps.sortBy(_.ordinal).map { s =>
            ujson.Obj(
              "ordinal" -> s.ordinal,
              "operation" -> s.operation,
              "status" -> s.status,
              "evidenceIds" -> Js.strs(s.evidenceIds)
            )
          }),
          "stepsTruncated" -> (steps.size >= config.explainMaxSteps),
          "promptVersions" -> ujson.Obj(
            "answerSynthesis" -> Js.str(a.promptVersion),
            "outputSchema" -> Js.str(a.outputSchemaVersion)
          ),
          "modelVersions" -> ujson.Obj(
            "answerModel" -> Js.str(a.modelVersion),
            "nonProductionModel" -> a.nonProductionModel
          ),
          "evidenceIds" -> Js.strs(a.evidenceIds),
          "bundleId" -> Js.str(a.bundleId),
          "metricVersions" -> Js.strs(a.metricVersions),
          "graphRunId" -> a.graphRunId,
          "executedTemplates" -> Js.strs(a.executedTemplates),
          "scoreComponents" -> ujson.Arr.from(bundle.toVector.flatMap(_.items).map { i =>
            ujson.Obj(
              "evidenceId" -> i.evidenceId,
              "evidenceType" -> ujson.read(Json.write(i)).obj("evidenceType"),
              "score" -> i.score
            )
          }),
          "counters" -> ujson.Obj(
            "latencyMillis" -> a.latencyMillis.toDouble,
            "synthesisAttempts" -> a.synthesisAttempts,
            "inputTokens" -> Js.num(a.inputTokens),
            "outputTokens" -> Js.num(a.outputTokens),
            "abstainedNames" -> a.abstainedNames,
            "droppedMissingEvidence" -> a.droppedMissingEvidence,
            "evidenceItems" -> a.evidenceIds.size
          ),
          "verification" -> ujson.Obj(
            "overall" -> Js.str(a.overallVerification),
            "counts" -> Js.counts(a.verification)
          ),
          "auditSha256" -> row.auditSha256,
          "notice" -> "the audit stores no hidden reasoning, question text or researcher names"
        )
      )
    }
  }
}
