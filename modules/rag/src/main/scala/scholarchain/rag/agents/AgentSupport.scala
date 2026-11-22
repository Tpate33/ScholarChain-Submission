package scholarchain.rag.agents

import scholarchain.core.{IdKind, RecordContract, StableId, TimeWindow}
import scholarchain.core.config.RuntimeConfig
import scholarchain.storage.model.{
  AnalysisResultRecord,
  BoundQuery,
  GraphParamValue,
  GraphQuery,
  GraphRow,
  GraphValue,
  ItemCodecs,
  PrimaryKey,
  StoreError,
  StoreIds,
  TableKeys,
  TableRole
}
import scholarchain.storage.port.{GraphReader, KeyQuery, KeyValueReader, SortKeyCondition}

/**
 * Every failure of a runtime stage (RE-P9-01). A stage never guesses: a missing, malformed or
 * inconsistent stored value is an error or a typed gap, never an estimate.
 *
 *   - `Store`: the storage port failed (its own [[StoreError]], kept as is).
 *   - `Corrupt`: a stored value fails its own contract or disagrees with another stored value.
 *   - `Refused`: the request breaks a stage rule (bad ID, bound above its configured limit).
 *   - `BudgetExceeded`: the configured call budget of one stage invocation is spent.
 *   - `TimedOut`: the configured time budget of one stage invocation is spent.
 */
enum AgentError {
  case Store(error: StoreError)
  case Corrupt(detail: String)
  case Refused(detail: String)
  case BudgetExceeded(detail: String)
  case TimedOut(detail: String)

  def message: String = this match {
    case Store(e) => s"store: ${e.message}"
    case Corrupt(d) => s"corrupt: $d"
    case Refused(d) => s"refused: $d"
    case BudgetExceeded(d) => s"budget: $d"
    case TimedOut(d) => s"timeout: $d"
  }
}

/** Time source of the stages: monotonic for budgets, epoch seconds for cache expiry. */
trait AgentClock {
  def monotonicNanos(): Long
  def epochSeconds(): Long
}

object AgentClock {
  val system: AgentClock = new AgentClock {
    def monotonicNanos(): Long = System.nanoTime()
    def epochSeconds(): Long = java.time.Instant.now().getEpochSecond
  }
}

/**
 * The published analysis run this runtime serves and the snapshot token of its runtime records.
 * Configuration (never hardcoded); every stored row a stage reads must belong to `analysisRunId`
 * (one analysis run owns a store instance, storage decision 2026-11-20 option b).
 */
final case class ServingContext private (analysisRunId: String, snapshot: String)

object ServingContext {
  def of(analysisRunId: String, snapshot: String): Either[String, ServingContext] =
    if (StableId.validate(IdKind.Run, analysisRunId, "analysisRunId").isLeft)
      Left("analysisRunId: not a run ID")
    else if (!StableId.isToken(snapshot)) Left("snapshot: not a token")
    else Right(new ServingContext(analysisRunId, snapshot))
}

/**
 * Configured bounds of the runtime stages (role law 7). No value has a default: production
 * composition passes them from configuration. `maxPathDepth` and the default window come from core
 * [[RuntimeConfig]] (`runtime.maxPathHops`, `runtime.defaultWindow`); the other bounds have no core
 * configuration key yet (recorded as a domain-engineer follow-up in the RE-P9-01 report) and are
 * passed explicitly.
 *
 * @param maxResults
 *   largest result count of one graph template call or one key-value query (also the `maxLimit` of
 *   [[BoundQuery.bind]])
 * @param maxPathDepth
 *   longest coauthorship path searched, in hops
 * @param timeoutMillis
 *   time budget of one stage invocation, checked before every store call (cooperative: a single
 *   blocking adapter call must carry its own request timeout)
 * @param maxGraphCalls
 *   graph template calls allowed in one stage invocation
 * @param maxKeyValueCalls
 *   key-value reads allowed in one stage invocation
 * @param maxVisited
 *   researchers a path search may visit
 * @param maxCandidates
 *   researcher candidates of one name; more is an abstention (never a silent cut)
 * @param maxContextRows
 *   affiliations and coauthors shown per candidate as disambiguation context
 * @param maxQuestionBytes
 *   longest question (UTF-8 bytes) the planner reads
 * @param maxNames
 *   researcher names one question may name
 */
final case class AgentLimits private (
    maxResults: Int,
    maxPathDepth: Int,
    timeoutMillis: Long,
    maxGraphCalls: Int,
    maxKeyValueCalls: Int,
    maxVisited: Int,
    maxCandidates: Int,
    maxContextRows: Int,
    maxQuestionBytes: Int,
    maxNames: Int,
    defaultWindow: TimeWindow
)

object AgentLimits {
  def of(
      runtime: RuntimeConfig,
      maxResults: Int,
      timeoutMillis: Long,
      maxGraphCalls: Int,
      maxKeyValueCalls: Int,
      maxVisited: Int,
      maxCandidates: Int,
      maxContextRows: Int,
      maxQuestionBytes: Int,
      maxNames: Int
  ): Either[String, AgentLimits] =
    if (runtime == null) Left("runtime: missing")
    else if (TimeWindow.check("runtime.defaultWindow", runtime.defaultWindow).nonEmpty)
      Left("runtime.defaultWindow: not a valid window")
    else if (runtime.maxPathHops < 1) Left("runtime.maxPathHops: must be >= 1")
    else {
      val positives = Vector(
        "maxResults" -> maxResults.toLong,
        "timeoutMillis" -> timeoutMillis,
        "maxGraphCalls" -> maxGraphCalls.toLong,
        "maxKeyValueCalls" -> maxKeyValueCalls.toLong,
        "maxVisited" -> maxVisited.toLong,
        "maxCandidates" -> maxCandidates.toLong,
        "maxContextRows" -> maxContextRows.toLong,
        "maxQuestionBytes" -> maxQuestionBytes.toLong,
        "maxNames" -> maxNames.toLong
      )
      positives.collectFirst { case (k, v) if v < 1 => k } match {
        case Some(k) => Left(s"$k: must be >= 1")
        case None if maxContextRows > maxResults => Left("maxContextRows: must be <= maxResults")
        case None if maxCandidates >= maxResults =>
          Left("maxCandidates: must be < maxResults (one extra row detects overflow)")
        case None =>
          Right(
            new AgentLimits(
              maxResults,
              runtime.maxPathHops,
              timeoutMillis,
              maxGraphCalls,
              maxKeyValueCalls,
              maxVisited,
              maxCandidates,
              maxContextRows,
              maxQuestionBytes,
              maxNames,
              runtime.defaultWindow
            )
          )
      }
    }
}

/**
 * The call and time budget of ONE stage invocation. Every store call goes through it; the deadline
 * is checked before each call, so a stage stops at the first call after the budget is spent.
 */
final class CallBudget(limits: AgentLimits, clock: AgentClock) {
  private val start = clock.monotonicNanos()
  private var graphCalls = 0
  private var kvCalls = 0
  private val executed = scala.collection.mutable.ArrayBuffer.empty[String]

  def graphCallCount: Int = graphCalls
  def keyValueCallCount: Int = kvCalls

  /** Names of the graph templates executed, in order (audit). */
  def executedTemplates: Vector[String] = executed.toVector

  private def deadline(what: String): Either[AgentError, Unit] = {
    val elapsedMillis = (clock.monotonicNanos() - start) / 1000000L
    if (elapsedMillis >= limits.timeoutMillis)
      Left(AgentError.TimedOut(s"$what: ${limits.timeoutMillis} ms budget spent"))
    else Right(())
  }

  def graph(reader: GraphReader, query: BoundQuery): Either[AgentError, Vector[GraphRow]] =
    for {
      _ <- deadline(query.query.toString)
      _ <-
        if (graphCalls < limits.maxGraphCalls) Right(())
        else Left(AgentError.BudgetExceeded(s"${limits.maxGraphCalls} graph calls spent"))
      _ = { graphCalls += 1; executed += query.query.toString }
      rows <- reader.run(query).left.map(AgentError.Store(_))
      _ <-
        if (rows == null || rows.contains(null))
          Left(AgentError.Corrupt(s"${query.query}: missing result row"))
        else Right(())
    } yield rows

  def keyValue[A](what: String)(call: => Either[StoreError, A]): Either[AgentError, A] =
    for {
      _ <- deadline(what)
      _ <-
        if (kvCalls < limits.maxKeyValueCalls) Right(())
        else Left(AgentError.BudgetExceeded(s"${limits.maxKeyValueCalls} key-value calls spent"))
      _ = kvCalls += 1
      a <- call.left.map(AgentError.Store(_))
    } yield a
}

/**
 * A pointer to one stored analysis row, for evidence assembly (RE-P9-02): the table key, the
 * record's schema and graph-policy version, its content hash and the run, load and part it came
 * from. Every value is copied from the stored row; none is computed here.
 *
 * @param recordJson
 *   RE-P9-02: the stored record's compact JSON exactly as the row holds it (its SHA-256 is
 *   `recordSha256`); it becomes the text of the record's evidence item
 */
final case class StoredRecordRef(
    table: String,
    pk: String,
    sk: String,
    role: String,
    subjectId: String,
    window: String,
    recordSchemaVersion: String,
    policyVersion: Option[String],
    recordSha256: String,
    runId: String,
    loadId: String,
    sourcePath: String,
    recordJson: String
)

/** Closed vocabulary of the analysis-result roles the stages read (storage SE-P7-07 roles). */
object AnalysisRoles {
  val ResearcherMetrics = "researcher-metrics"
  val CoauthorEdges = "coauthor-edges"
  val ComponentMemberships = "component-memberships"
  val CommunityMemberships = "community-memberships"
  val BridgeMeasures = "bridge-measures"
  val Coreness = "coreness"
  val PathLengthEstimates = "path-length-estimates"

  val all: Vector[String] = Vector(
    ResearcherMetrics,
    CoauthorEdges,
    ComponentMemberships,
    CommunityMemberships,
    BridgeMeasures,
    Coreness,
    PathLengthEstimates
  )
}

/** Window key `SSSS-EEEE` of the analysis-results table. */
private[agents] def windowKey(w: TimeWindow): Either[AgentError, String] =
  if (w == null) Left(AgentError.Refused("window: missing"))
  else StoreIds.window(w.startYear, w.endYear).left.map(e => AgentError.Refused(e.message))

private[agents] def requireId(kind: IdKind, id: String, field: String): Either[AgentError, String] =
  StableId.validate(kind, id, field).left.map(e => AgentError.Refused(e.message))

/** Reads and checks analysis-result rows through the key-value READ port only. */
private[agents] final class AnalysisRows(
    kv: KeyValueReader,
    serving: ServingContext,
    budget: CallBudget
) {
  private val codec = ItemCodecs.AnalysisResultCodec
  private val table = TableRole.ResearcherMetricCache

  private def refOf(row: AnalysisResultRecord, key: PrimaryKey, policy: Option[String]) =
    StoredRecordRef(
      table.tag,
      key.pk,
      key.sk,
      row.role,
      row.scopeId,
      row.window,
      row.recordSchemaVersion,
      policy,
      row.recordSha256,
      row.runId,
      row.loadId,
      row.sourcePath,
      row.record
    )

  /**
   * Decodes one row and its record, refusing (Corrupt) a row of another role, subject, window,
   * schema or run, and a record whose own run or subject differs from the row.
   */
  private def check[A](
      row: AnalysisResultRecord,
      key: PrimaryKey,
      role: String,
      expectedSchema: String,
      subjectOf: A => String,
      windowOf: A => TimeWindow
  )(using c: RecordContract[A]): Either[AgentError, (A, StoredRecordRef)] = {
    val where = s"$role ${key.pk} ${key.sk}"
    def bad(why: String) = Left(AgentError.Corrupt(s"$where: $why"))
    if (row.role != role) bad(s"row role ${row.role}")
    else if (row.recordSchemaVersion != expectedSchema)
      bad(s"record schema ${row.recordSchemaVersion}, expected $expectedSchema")
    else if (row.runId != serving.analysisRunId)
      bad(s"row of run ${row.runId}, not the served run ${serving.analysisRunId}")
    else
      scholarchain.core.Json.decode[A](row.record) match {
        case Left(errors) => bad(s"record fails its contract (${errors.size} error(s))")
        case Right(a) =>
          val pr = c.provenanceOf(a)
          windowKey(windowOf(a)) match {
            case Left(e) => Left(e)
            case Right(w) if w != row.window =>
              bad(s"record window $w differs from row ${row.window}")
            case Right(_) if subjectOf(a) != row.scopeId =>
              bad(s"record subject ${subjectOf(a)} differs from row ${row.scopeId}")
            case Right(_) if pr == null || pr.runId != row.runId =>
              bad("record provenance run differs from the row's run")
            case Right(_) => Right((a, refOf(row, key, Option(pr.policyVersion).flatten)))
          }
      }
  }

  /** MC-2: one role row of a subject in a window (None when absent: never an estimate). */
  def subject[A](
      subjectId: String,
      role: String,
      window: TimeWindow,
      expectedSchema: String,
      subjectOf: A => String,
      windowOf: A => TimeWindow
  )(using c: RecordContract[A]): Either[AgentError, Option[(A, StoredRecordRef)]] =
    for {
      w <- windowKey(window)
      key <- TableKeys.ResearcherMetricCache
        .subjectResult(subjectId, role, w, None)
        .left
        .map(e => AgentError.Refused(e.message))
      got <- budget.keyValue(s"get $role")(kv.get(table, key))
      out <- got match {
        case None => Right(None)
        case Some(item) =>
          codec.decode(item).left.map(AgentError.Store(_)).flatMap { row =>
            if (row.scopeId != subjectId || row.window != w)
              Left(AgentError.Corrupt(s"$role ${key.pk} ${key.sk}: row keyed for another subject"))
            else check(row, key, role, expectedSchema, subjectOf, windowOf).map(Some(_))
          }
      }
    } yield out

  /** A GSI query; returns each row with its own table key, and whether `limit` cut it. */
  def query(
      q: KeyQuery,
      what: String
  ): Either[AgentError, (Vector[AnalysisResultRecord], Boolean)] =
    budget.keyValue(what)(kv.query(table, q)).flatMap { items =>
      items
        .foldLeft[Either[AgentError, Vector[AnalysisResultRecord]]](Right(Vector.empty)) {
          (acc, i) =>
            acc.flatMap(xs => codec.decode(i).left.map(AgentError.Store(_)).map(xs :+ _))
        }
        .map(rows => (rows, items.size >= q.limit))
    }

  /** The table key of a decoded subject row (recomputed with storage's own key builder). */
  def keyOf(row: AnalysisResultRecord): Either[AgentError, PrimaryKey] =
    TableKeys.ResearcherMetricCache
      .subjectResult(row.scopeId, row.role, row.window, row.toWindow)
      .left
      .map(e => AgentError.Corrupt(e.message))

  def checked[A](
      row: AnalysisResultRecord,
      role: String,
      expectedSchema: String,
      subjectOf: A => String,
      windowOf: A => TimeWindow
  )(using c: RecordContract[A]): Either[AgentError, (A, StoredRecordRef)] =
    keyOf(row).flatMap(k => check(row, k, role, expectedSchema, subjectOf, windowOf))

  /** MC-3 / MC-5 helpers. */
  def groupQuery(groupId: String, window: TimeWindow, limit: Int): Either[AgentError, KeyQuery] =
    for {
      w <- windowKey(window)
      p <- TableKeys.ResearcherMetricCache
        .byGroupOf(groupId, w)
        .left
        .map(e => AgentError.Refused(e.message))
    } yield KeyQuery(Some("byGroup"), p, SortKeyCondition.All, limit)

  def rankQuery(role: String, window: TimeWindow, limit: Int): Either[AgentError, KeyQuery] =
    for {
      w <- windowKey(window)
      p <- TableKeys.ResearcherMetricCache
        .rankOf(role, w)
        .left
        .map(e => AgentError.Refused(e.message))
    } yield KeyQuery(Some("rank"), p, SortKeyCondition.All, limit)
}

/** Typed reads of graph result columns; a wrong type is Corrupt (never coerced). */
private[agents] object Cols {
  def str(r: GraphRow, c: String, q: GraphQuery): Either[AgentError, String] =
    r.columns.get(c) match {
      case Some(GraphValue.Str(s)) if s != null => Right(s)
      case other => Left(AgentError.Corrupt(s"$q: column $c is ${other.getOrElse("absent")}"))
    }

  def long(r: GraphRow, c: String, q: GraphQuery): Either[AgentError, Long] =
    r.columns.get(c) match {
      case Some(GraphValue.IntValue(n)) => Right(n)
      case other => Left(AgentError.Corrupt(s"$q: column $c is ${other.getOrElse("absent")}"))
    }

  def all[A](
      rows: Vector[GraphRow]
  )(f: GraphRow => Either[AgentError, A]): Either[AgentError, Vector[A]] =
    rows.foldLeft[Either[AgentError, Vector[A]]](Right(Vector.empty)) { (acc, r) =>
      acc.flatMap(xs => f(r).map(xs :+ _))
    }
}

/** Binds one closed-set graph template (storage [[GraphQuery]]) with typed, validated values. */
private[agents] object Bind {
  def apply(
      q: GraphQuery,
      limits: AgentLimits,
      args: (String, GraphParamValue)*
  ): Either[AgentError, BoundQuery] =
    BoundQuery.bind(q, args.toMap, limits.maxResults).left.map(e => AgentError.Refused(e.message))

  def id(s: String): GraphParamValue = GraphParamValue.Str(s)
  def int(n: Int): GraphParamValue = GraphParamValue.IntValue(n)
}
