package scholarchain.rag.agents

import upickle.default.ReadWriter
import upickle.implicits.{allowUnknownKeys, serializeDefaults}

import scholarchain.core.{Canonical, EvidenceBundle, Json, QueryPlan}
import scholarchain.storage.model.{
  AuditBounds,
  PutOutcome,
  QueryAuditRecord,
  QuerySessionRecord,
  QueryStepRecord,
  StoreError
}
import scholarchain.storage.port.{EvidenceBundleWriter, QuerySessionWriter}

/**
 * One researcher argument of a plan step (RE-P9-04, m5): the name's index in the plan (names are
 * never audited), the researcher ID it resolved to, and `status`: `resolved`, `unresolved` (the
 * name abstained: no candidate, several, too many) or `not-run` (resolution did not complete).
 */
@allowUnknownKeys(false)
final case class AuditResearcherArg(nameIndex: Int, researcherId: Option[String], status: String)
    derives ReadWriter

object AuditResearcherArg {
  val Resolved = "resolved"
  val Unresolved = "unresolved"
  val NotRun = "not-run"
}

/**
 * A topic, venue or institution filter of a step: its argument key and the SHA-256 of its value
 * (the value is question text and is never audited). `applied` is true exactly when the step's
 * stage used the value: RE-P9-05 (CR-P9-02 F3), the institution hint of a resolve step is applied
 * (to name resolution) when the resolver evaluated it against stored affiliations and it decided
 * the name's outcome (resolved by [[ResolutionBasis.InstitutionHint]], or abstained because the
 * hint matched no candidate, several, or a cut affiliation list). A filter of a data step is never
 * applied to the stored graph and metric records (the workflow reports that as a gap).
 */
@allowUnknownKeys(false)
final case class AuditFilter(key: String, valueSha256: String, applied: Boolean) derives ReadWriter

/**
 * One plan step as audited (m5): operation, metrics, intent, researchers (resolved IDs or explicit
 * unresolved entries), the compared window, the window source, the ranking rule, the filters
 * (hashed) and `unresolvedArguments`, the argument names whose researcher is not resolved (`name`
 * of a resolve step, `researchers[<i>]` of a data step).
 */
@allowUnknownKeys(false)
final case class AuditStep(
    ordinal: Int,
    operation: String,
    metrics: Vector[String],
    intent: Option[String],
    researchers: Vector[AuditResearcherArg],
    compareWindow: Option[String],
    windowSource: Option[String],
    rankingRule: Option[String],
    filters: Vector[AuditFilter],
    unresolvedArguments: Vector[String]
) derives ReadWriter

/** The structured QueryPlan as audited (m5): planner version, window and every step. */
@allowUnknownKeys(false)
final case class AuditPlan(planVersion: String, window: String, steps: Vector[AuditStep])
    derives ReadWriter

object AuditPlan {

  /**
   * The audited plan of `p`, given the audit status of each name (by name index) and
   * `institutionApplied`, the name indices whose resolution applied the institution hint (F3).
   */
  def of(
      p: QueryPlan,
      names: Vector[AuditResearcherArg],
      institutionApplied: Set[Int] = Set.empty
  ): AuditPlan = {
    val A = QueryPlanner.Args
    val resolveOrdinals =
      p.steps
        .filter(_.operation == scholarchain.core.QueryOperation.ResolveResearcher)
        .map(_.ordinal)
    def arg(i: Int): AuditResearcherArg =
      names.lift(i).getOrElse(AuditResearcherArg(i, None, AuditResearcherArg.NotRun))
    val steps = p.steps.map { s =>
      val args = s.arguments.toMap
      val isResolve = s.operation == scholarchain.core.QueryOperation.ResolveResearcher
      val hintApplied =
        isResolve && institutionApplied.contains(resolveOrdinals.indexOf(s.ordinal))
      val researchers =
        if (s.operation == scholarchain.core.QueryOperation.ResolveResearcher)
          Vector(arg(resolveOrdinals.indexOf(s.ordinal)))
        else
          args
            .get(A.Researchers)
            .toVector
            .flatMap(_.split(",").toVector)
            .flatMap(_.toIntOption)
            .map(arg)
      val unresolved =
        if (s.operation == scholarchain.core.QueryOperation.ResolveResearcher)
          researchers.filter(_.researcherId.isEmpty).map(_ => A.Name)
        else
          researchers.filter(_.researcherId.isEmpty).map(r => s"${A.Researchers}[${r.nameIndex}]")
      val compare = for {
        a <- args.get(A.ToWindowStart).flatMap(_.toIntOption)
        b <- args.get(A.ToWindowEnd).flatMap(_.toIntOption)
      } yield f"$a%04d-$b%04d"
      AuditStep(
        s.ordinal,
        s.operation.tag,
        s.metrics.map(_.tag),
        args.get(A.Intent),
        researchers,
        compare,
        args.get(A.WindowSource),
        args.get(A.RankingRule),
        Vector(A.Topic, A.Venue, A.Institution).flatMap(k =>
          args
            .get(k)
            .map(v =>
              AuditFilter(
                k,
                Canonical.sha256HexUtf8(v),
                applied = hintApplied && k == A.Institution
              )
            )
        ),
        unresolved
      )
    }
    AuditPlan(QueryPlanner.Version, BundleFacts.windowText(p.window), steps)
  }
}

/**
 * The audit record of one question (README stage 10; schema `prompts/schemas/audit-record.v3`).
 * Only IDs, versions, counts, hashes and tags: no question text and no researcher name (the plan is
 * audited as its structure with resolved IDs and hashed filter values, see [[AuditPlan]]), no
 * retrieved text, no model output, and never hidden reasoning.
 *
 * @param plan
 *   v2 (m5): the structured plan; None for a refused question
 * @param bundleId
 *   the evidence bundle, only when it is written with the query's rows (m3)
 * @param droppedMissingEvidence
 *   v2 (M3, m2): model missing-evidence lines dropped because they are not a workflow gap
 *
 * @param outcome
 *   `answered`, `abstained` or `refused`
 * @param metricVersions
 *   distinct `<record schema>|<graph policy>` of the stored rows behind the evidence
 * @param graphRunId
 *   the analysis run the graph and metric stores serve
 * @param verification
 *   claims per README outcome tag (`VERIFIED`, ...), empty when no answer was verified
 * @param latencyMillis
 *   wall time of the workflow on the injected clock
 * @param answerSha256
 *   SHA-256 of the returned answer text
 * @param verifierVersion
 *   v3 (RG-P11-02): the claim-verification version ([[ClaimVerificationAgent.Version]]) that
 *   verified the answer's claims; None when no claim was verified (refused, or abstained before
 *   verification). Always serialized (`null` when None).
 */
@allowUnknownKeys(false)
@serializeDefaults(true)
final case class AuditRecord(
    schemaVersion: String,
    queryId: String,
    outcome: String,
    reason: Option[String],
    planSha256: Option[String],
    planVersion: Option[String],
    operations: Vector[String],
    plan: Option[AuditPlan],
    resolvedResearcherIds: Vector[String],
    abstainedNames: Int,
    evidenceIds: Vector[String],
    bundleId: Option[String],
    metricVersions: Vector[String],
    graphRunId: String,
    executedTemplates: Vector[String],
    promptVersion: Option[String],
    outputSchemaVersion: Option[String],
    modelVersion: Option[String],
    nonProductionModel: Boolean,
    synthesisAttempts: Int,
    synthesisOutputSha256: Option[String],
    inputTokens: Option[Long],
    outputTokens: Option[Long],
    latencyMillis: Long,
    verification: Map[String, Int],
    overallVerification: Option[String],
    droppedMissingEvidence: Int,
    answerSha256: Option[String],
    verifierVersion: Option[String] = None
) derives ReadWriter

object AuditRecord {

  /**
   * v3 (RG-P11-02): adds `verifierVersion` (the claim-verification version, v4). v2 (RE-P9-04):
   * adds `plan` (m5) and `droppedMissingEvidence` (M3, m2).
   */
  val SchemaVersion = "scholarchain.rag.audit-record.v3"
  val SchemaFile = "prompts/schemas/audit-record.v3.schema.json"

  def json(a: AuditRecord): String = upickle.default.write(a)
  def sha256(a: AuditRecord): String = Canonical.sha256HexUtf8(json(a))
}

/**
 * What the persistence stored. The QuerySession table stores the session (outcome token, window,
 * plan hash, bundle ID, TTL), one row per plan step (operation, status, evidence IDs) and, since
 * RE-P9-03 (storage SE-P9-02, access pattern QS-3), the FULL [[AuditRecord]] as its audit row; the
 * EvidenceBundle table stores the bundle and its items. `notPersisted` names what has no row: the
 * session row of a refused question (no plan, so no window), or (RE-P9-04, m4) a session row whose
 * write failed after the audit row was stored ([[AuditStage.SessionNotPersisted]]).
 *
 * @param auditSha256
 *   the stored row's hash of the audit JSON (equals [[AuditRecord.sha256]])
 * @param expiresAtEpochSeconds
 *   the one TTL instant shared by the session, step and audit rows of the query
 */
final case class PersistReport(
    sessionWritten: Option[PutOutcome],
    stepsWritten: Int,
    bundleWritten: Option[PutOutcome],
    auditWritten: PutOutcome,
    auditSha256: String,
    expiresAtEpochSeconds: Long,
    notPersisted: Vector[String]
)

/**
 * Configuration of stage 10, both values from configuration with no default.
 *
 * @param ttlSeconds
 *   QuerySession TTL
 * @param bounds
 *   the largest audit JSON (UTF-8 bytes) the audit row may carry: storage [[AuditBounds]],
 *   1..[[AuditBounds.CeilingBytes]]
 */
final case class AuditConfig private (ttlSeconds: Long, bounds: AuditBounds)

object AuditConfig {
  def of(ttlSeconds: Long, maxAuditJsonBytes: Long): Either[String, AuditConfig] =
    if (ttlSeconds < 1) Left("ttlSeconds: must be >= 1")
    else
      AuditBounds
        .of(maxAuditJsonBytes)
        .left
        .map(_.message)
        .map(new AuditConfig(ttlSeconds, _))
}

/**
 * The clock values of one query, taken ONCE (RE-P9-03): the session's creation instant and the TTL
 * shared by its session, step and audit rows. A retry reuses it; a recomputed TTL would make the
 * create-once rows conflict.
 */
final case class AuditStamp(createdAtEpochSeconds: Long, expiresAtEpochSeconds: Long)

/**
 * Everything stage 10 writes for one query, as a value, so a retry after a failed write repeats
 * exactly the same rows (create-once: identical rows are `AlreadyPresent`).
 *
 * @param stepStatus
 *   status of each plan step, by ordinal
 */
final case class AuditWrite(
    audit: AuditRecord,
    plan: Option[QueryPlan],
    bundle: Option[EvidenceBundle],
    stepEvidence: Map[Int, Vector[String]],
    stepStatus: Map[Int, String],
    stamp: AuditStamp
)

/**
 * RE-P9-02 stage 10, the audit stage. Persists through the runtime writers only
 * ([[QuerySessionWriter]], [[EvidenceBundleWriter]]; create-once). RE-P9-03 order: the audit row is
 * checked first (its storage problems and the configured bound) so an unstorable audit writes
 * nothing; then the bundle (item rows, then the bundle row), the steps, the audit row, and the
 * session row last, so a present session row means its steps, bundle and audit are present. Every
 * question is audited, refused and abstained ones included. RE-P9-04: the audit row is the commit
 * point (m4: a session failure after it is reported in `notPersisted`, not a Left), and the audit
 * may name only the bundle the write stores (m3).
 */
final class AuditStage(
    sessions: QuerySessionWriter,
    bundles: EvidenceBundleWriter,
    config: AuditConfig,
    clock: AgentClock
) {

  /** Takes the query's clock values (call once per query). */
  def stamp(): AuditStamp = {
    val now = clock.epochSeconds()
    AuditStamp(now, now + config.ttlSeconds)
  }

  /** The audit row of `w` (hash computed by storage over [[AuditRecord.json]]). */
  def auditRow(w: AuditWrite): QueryAuditRecord =
    QueryAuditRecord.of(
      w.audit.queryId,
      AuditRecord.SchemaVersion,
      AuditRecord.json(w.audit),
      w.stamp.expiresAtEpochSeconds
    )

  private def precheck(row: QueryAuditRecord): Either[AgentError, Unit] = {
    val bytes = QueryAuditRecord.jsonBytes(row)
    val problems = QueryAuditRecord.problems(row)
    if (bytes > config.bounds.maxAuditJsonBytes)
      Left(
        AgentError.Store(
          StoreError.Refused(
            s"audit: $bytes bytes, over the configured bound of ${config.bounds.maxAuditJsonBytes}"
          )
        )
      )
    else if (problems.nonEmpty)
      Left(AgentError.Store(StoreError.Refused(problems.mkString("; "))))
    else Right(())
  }

  /** RE-P9-04 (m3): the audit and session may name only the bundle this write stores. */
  private def bundleCheck(w: AuditWrite): Either[AgentError, Unit] =
    if (w.audit.bundleId == w.bundle.map(_.bundleId)) Right(())
    else
      Left(
        AgentError.Store(
          StoreError.Refused("audit: bundleId names a bundle this write does not store")
        )
      )

  def persist(w: AuditWrite): Either[AgentError, PersistReport] = {
    val audit = w.audit
    val plan = w.plan
    val expires = w.stamp.expiresAtEpochSeconds
    val window = plan.map(_.window)
    val row = auditRow(w)
    for {
      _ <- precheck(row)
      _ <- bundleCheck(w)
      b <- w.bundle match {
        case None => Right(None)
        case Some(x) => bundles.put(x).left.map(AgentError.Store(_)).map(Some(_))
      }
      steps <- plan.toVector.flatMap(_.steps).foldLeft[Either[AgentError, Int]](Right(0)) {
        (acc, s) =>
          acc.flatMap { n =>
            sessions
              .putStep(
                QueryStepRecord(
                  QueryStepRecord.SchemaVersion,
                  audit.queryId,
                  s.ordinal,
                  s.operation.tag,
                  w.stepStatus.getOrElse(s.ordinal, "not-run"),
                  w.stepEvidence.getOrElse(s.ordinal, Vector.empty),
                  expires
                )
              )
              .left
              .map(AgentError.Store(_))
              .map(_ => n + 1)
          }
      }
      a <- sessions.putAudit(row, config.bounds).left.map(AgentError.Store(_))
    } yield {
      // RE-P9-04 (m4): the audit row is the commit point. Once it is stored the outcome it
      // records is the outcome the caller returns, so a failed session row after it is
      // REPORTED (notPersisted; retry with the same rows) and is not a Left: a Left always
      // means no audit row was stored by this call.
      // A refused question has no plan and so no window: the QuerySession row needs one, so
      // no session row is written for it (reported in notPersisted); its audit row is written.
      val session: Either[AgentError, Option[PutOutcome]] = window match {
        case None => Right(None)
        case Some(win) =>
          sessions
            .putSession(
              QuerySessionRecord(
                QuerySessionRecord.SchemaVersion,
                audit.queryId,
                win.startYear,
                win.endYear,
                audit.outcome,
                audit.planSha256,
                audit.bundleId,
                w.stamp.createdAtEpochSeconds * 1000L,
                expires
              )
            )
            .left
            .map(AgentError.Store(_))
            .map(Some(_))
      }
      PersistReport(
        session.toOption.flatten,
        steps,
        b,
        a,
        row.auditSha256,
        expires,
        (if (window.isEmpty) Vector(AuditStage.RefusedSessionNotPersisted) else Vector.empty) ++
          session.left.toOption.map(e => s"${AuditStage.SessionNotPersisted}: ${e.message}")
      )
    }
  }
}

object AuditStage {

  /** The one `notPersisted` entry: a refused question's session row (it has no window). */
  val RefusedSessionNotPersisted = "session (refused question: no plan window)"

  /** RE-P9-04 (m4): prefix of the `notPersisted` entry of a session row that failed. */
  val SessionNotPersisted = "session (write failed after the audit row was stored)"

  /** SHA-256 of the plan's core JSON. */
  def planSha256(p: QueryPlan): String = Canonical.sha256HexUtf8(Json.write(p))
}
