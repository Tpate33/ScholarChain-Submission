package scholarchain.rag.agents

import scholarchain.core.{
  AnswerId,
  AnswerResponse,
  Canonical,
  EvidenceType,
  Provenance,
  QueryOperation,
  QueryPlan,
  QueryStep,
  SchemaVersions,
  SourceType,
  TimeWindow
}
import scholarchain.storage.port.RuntimeStores

/**
 * Configuration of the composed workflow (role law 7; every bound from configuration).
 *
 * @param rankK
 *   entries of a stored ranking read for a ranking question (1..maxResults)
 * @param directLimit
 *   direct collaborators listed for one researcher (1..maxResults)
 */
final case class WorkflowConfig private (
    limits: AgentLimits,
    evidence: EvidenceLimits,
    synthesis: SynthesisConfig,
    semantic: SemanticConfig,
    audit: AuditConfig,
    rankK: Int,
    directLimit: Int
)

object WorkflowConfig {
  def of(
      limits: AgentLimits,
      evidence: EvidenceLimits,
      synthesis: SynthesisConfig,
      semantic: SemanticConfig,
      audit: AuditConfig,
      rankK: Int,
      directLimit: Int
  ): Either[String, WorkflowConfig] =
    if (Vector(limits, evidence, synthesis, semantic, audit).contains(null))
      Left("every part is required")
    else if (rankK < 1 || rankK > limits.maxResults) Left(s"rankK: must be 1..${limits.maxResults}")
    else if (directLimit < 1 || directLimit > limits.maxResults)
      Left(s"directLimit: must be 1..${limits.maxResults}")
    else if (semantic.budget.maxQueryBytes < limits.maxQuestionBytes)
      Left("semantic maxQueryBytes must be >= maxQuestionBytes (the question is the query)")
    else Right(new WorkflowConfig(limits, evidence, synthesis, semantic, audit, rankK, directLimit))
}

/** Why the workflow abstains (never a guess, never a partial answer). */
enum AbstentionReason(val tag: String) {
  case IdentityUnresolved extends AbstentionReason("identity-unresolved")
  case InsufficientEvidence extends AbstentionReason("insufficient-evidence")
  case EvidenceBudget extends AbstentionReason("evidence-budget")
  case EvidenceUnresolved extends AbstentionReason("evidence-unresolved")
  case StageFailure extends AbstentionReason("stage-failure")
  case SynthesisFailed extends AbstentionReason("synthesis-failed")
  case NothingVerified extends AbstentionReason("no-verified-claim")

  /** RE-P9-03: an answer whose audit could not be persisted is withheld (fail closed). */
  case AuditNotPersisted extends AbstentionReason("audit-not-persisted")
}

/** A name that did not resolve, with every candidate kept (README: never a silent choice). */
final case class NameAbstention(
    name: String,
    reason: AbstainReason,
    candidates: Vector[ResearcherCandidate]
) {

  /** RE-P9-04 (M4): the candidates are the first `maxCandidates` of more links. */
  def truncated: Boolean = reason == AbstainReason.TooManyCandidates
}

enum WorkflowOutcome {
  case Answered(
      response: AnswerResponse,
      verification: VerificationReport,
      nonProductionModel: Boolean
  )
  case Abstained(
      reason: AbstentionReason,
      detail: String,
      names: Vector[NameAbstention],
      gaps: Vector[String]
  )
  case Refused(reason: PlanRefusal, detail: String)

  def tag: String = this match {
    case _: Answered => "answered"
    case _: Abstained => "abstained"
    case _: Refused => "refused"
  }
}

/**
 * Everything one question produced: outcome, plan, evidence, audit and its persistence.
 *
 * RE-P9-03 audit failure policy (fail closed): when `persistence` is a Left, an answer is NOT
 * returned: `outcome` becomes `Abstained(AuditNotPersisted, <store error>)`. A refusal or
 * abstention keeps its outcome (nothing is answered) and the Left is reported in `persistence`.
 * RE-P9-04 (m4): a Left means no audit row was stored, so no stored audit ever records `answered`
 * for a withheld answer; a stored audit's outcome is always the returned outcome. RE-P9-05 (F2):
 * `audit` and `auditWrite.audit` always record `outcome` as returned, so after a withheld answer
 * they record the abstention (`audit-not-persisted`, no answer hash), never `answered`;
 * `auditWrite` holds the exact rows and the one [[AuditStamp]], so `AuditStage.persist(auditWrite)`
 * (or `persistAgain`) retries without conflicts and stores the outcome the caller received.
 */
final case class WorkflowResult(
    outcome: WorkflowOutcome,
    plan: Option[QueryPlan],
    evidence: Option[BuiltEvidence],
    semantic: Vector[SemanticResult],
    audit: AuditRecord,
    auditWrite: AuditWrite,
    persistence: Either[AgentError, PersistReport]
)

/**
 * RE-P9-02: the runtime collaboration-analysis workflow, stages 1-10 in order:
 *
 *   1. plan (deterministic planner; refusal ends the question);
 *   2. resolve every name; ANY abstaining name abstains the whole question with all candidates;
 *      3-5. run each allowlisted plan step through its stage (metrics, path, community);
 *   6. semantic context when the plan asks for an explanation (descriptive only);
 *   7. build the bounded evidence bundle, then resolve every item to stored data (fail closed);
 *   8. synthesize claims through the model port (validated, bounded retries);
 *   9. verify every claim; unsupported claims are removed, partial and conflicting ones labelled;
 *   10. audit and persist (QuerySession and EvidenceBundle tables).
 *
 * No stage builds a query from user text: names are hashed, IDs come from stores, and graph access
 * is through storage's closed template set.
 */
final class CollaborationWorkflow(
    runtime: RuntimeStores,
    serving: ServingContext,
    config: WorkflowConfig,
    clock: AgentClock
) {
  private val limits = config.limits
  private val resolver =
    new ResearcherResolverAgent(runtime.graph, runtime.keyValues, serving, limits, clock)
  private val metricsAgent =
    new CollaborationMetricsAgent(runtime.keyValues, serving, limits, clock)
  private val pathFinder =
    new PathFinderAgent(runtime.graph, runtime.keyValues, serving, limits, clock)
  private val communities =
    new CommunityAnalystAgent(runtime.graph, runtime.keyValues, serving, limits, clock)
  private val semanticAgent = new SemanticContextAgent(runtime, config.semantic)
  private val builder =
    new EvidenceBuilderAgent(serving, config.evidence, config.semantic.textBucket)
  private val resolverOfEvidence =
    new EvidenceResolver(runtime.keyValues, runtime.objects, limits, clock)
  private val synthesis = new AnswerSynthesisAgent(config.synthesis)
  private val verifier = new ClaimVerificationAgent(serving)
  private val auditStage =
    new AuditStage(runtime.querySessions, runtime.evidenceBundles, config.audit, clock)

  private def windowOf(args: Map[String, String]): Option[TimeWindow] =
    for {
      a <- args.get(QueryPlanner.Args.ToWindowStart).flatMap(_.toIntOption)
      b <- args.get(QueryPlanner.Args.ToWindowEnd).flatMap(_.toIntOption)
      w <- TimeWindow.of(a, b).toOption
    } yield w

  private def all[A, B](
      xs: Vector[A]
  )(f: A => Either[AgentError, B]): Either[AgentError, Vector[B]] =
    xs.foldLeft[Either[AgentError, Vector[B]]](Right(Vector.empty))((acc, x) =>
      acc.flatMap(bs => f(x).map(bs :+ _))
    )

  /** Outputs and gaps of one non-resolve step. */
  private def run(
      s: QueryStep,
      plan: QueryPlan,
      ids: Vector[String],
      done: Vector[StageOutput]
  ): Either[AgentError, (Vector[StageOutput], Vector[String])] = {
    val args = s.arguments.toMap
    val rs = args
      .get(QueryPlanner.Args.Researchers)
      .toVector
      .flatMap(_.split(",").toVector)
      .flatMap(_.toIntOption)
      .flatMap(ids.lift)
    val w = plan.window
    val n = s.ordinal
    def metrics(ws: Vector[TimeWindow]) =
      all(rs.flatMap(r => ws.map(r -> _))) { case (r, win) =>
        metricsAgent.metrics(r, win, s.metrics)
      }.map { res =>
        (
          res.map(StageOutput.Metrics(n, _)),
          res.flatMap(x => x.gaps.map(g => s"${g.metric.tag} of ${x.researcherId}: ${g.reason}"))
        )
      }
    s.operation match {
      case QueryOperation.GetMetrics | QueryOperation.TeamSize | QueryOperation.KCore |
          QueryOperation.Component =>
        metrics(Vector(w))
      case QueryOperation.TemporalTrend =>
        windowOf(args) match {
          case None =>
            Right((Vector.empty, Vector("a temporal comparison needs its second window")))
          case Some(to) => metrics(Vector(w, to))
        }
      case QueryOperation.RankResearchers =>
        s.metrics.headOption match {
          case None => Right((Vector.empty, Vector("a ranking step names no metric")))
          case Some(m) =>
            metricsAgent.rank(m, w, config.rankK).map {
              case RankingOutcome.Ranked(r) => (Vector(StageOutput.Ranked(n, r)), Vector.empty)
              case RankingOutcome.NoStoredRanking(_, why) => (Vector.empty, Vector(why))
            }
        }
      case QueryOperation.DirectCollaborators =>
        rs match {
          case Vector(a, b) =>
            pathFinder.directEdge(a, b, w).map { e =>
              (
                Vector(StageOutput.Edge(n, e)),
                e.hop.fold(Vector(s"no direct coauthor edge between $a and $b in the window"))(_ =>
                  Vector.empty
                )
              )
            }
          case _ =>
            all(rs)(pathFinder.directCollaborators(_, w, config.directLimit)).map { res =>
              (
                res.map(StageOutput.Direct(n, _)),
                res
                  .filter(_.truncated)
                  .map(r =>
                    s"the direct collaborators of ${r.researcherId} are cut at the configured limit"
                  )
              )
            }
        }
      case QueryOperation.ShortestPath =>
        rs match {
          case Vector(a, b) =>
            pathFinder
              .shortestPath(a, b, w, limits.maxPathDepth)
              .map(p => (Vector(StageOutput.Path(n, p)), Vector.empty))
          case _ => Right((Vector.empty, Vector("a shortest path needs two resolved researchers")))
        }
      case QueryOperation.Community =>
        all(rs)(communities.view(_, w)).map(vs =>
          (vs.map(StageOutput.Community(n, _)), vs.flatMap(_.gaps))
        )
      case QueryOperation.BridgeResearchers if rs.isEmpty =>
        communities.bridgeRanking(w, config.rankK).map {
          case Some(b) => (Vector(StageOutput.Bridges(n, b)), Vector.empty)
          case None => (Vector.empty, Vector("no stored bridge ranking in the window"))
        }
      case QueryOperation.BridgeResearchers =>
        all(rs)(communities.view(_, w)).map(vs =>
          (vs.map(StageOutput.Community(n, _)), vs.flatMap(_.gaps))
        )
      case QueryOperation.SemanticContext =>
        val comms = done.collect { case StageOutput.Community(_, v) =>
          v.community.map(_._1.communityId)
        }.flatten
        semanticAgent
          .retrieve(
            SemanticRequest(
              plan.question,
              w,
              rs,
              comms,
              plan.steps.exists(_.operation == QueryOperation.ShortestPath),
              args.contains(QueryPlanner.Args.Topic)
            )
          )
          .map(r => (Vector(StageOutput.Semantic(n, r)), r.gaps))
      case QueryOperation.ResolveResearcher | QueryOperation.VerifyClaims =>
        Right((Vector.empty, Vector.empty))
    }
  }

  def answer(queryId: String, question: String): WorkflowResult = {
    val t0 = clock.monotonicNanos()
    QueryPlanner.plan(queryId, question, limits, serving) match {
      case PlanOutcome.Refused(r, d) =>
        finish(
          queryId,
          t0,
          WorkflowOutcome.Refused(r, d),
          None,
          Vector.empty,
          None,
          Vector.empty,
          None,
          None,
          Map.empty,
          Set.empty
        )
      case PlanOutcome.Planned(plan, _) => planned(queryId, t0, plan)
    }
  }

  /**
   * RE-P9-05 (CR-P9-02 F3): the name indices whose resolution applied the institution hint: the
   * resolver resolved the name by the hint, or abstained because the hint matched no candidate,
   * several, or could not exclude a candidate with a cut affiliation list.
   */
  private def institutionApplied(rs: Vector[Resolution]): Set[Int] =
    rs.zipWithIndex.collect {
      case (Resolution.Resolved(_, _, ResolutionBasis.InstitutionHint, _, _), i) => i
      case (Resolution.Abstained(_, r, _, _), i) if CollaborationWorkflow.HintReasons(r) => i
    }.toSet

  /** Every name resolved, in name order (the audit's researcher arguments). */
  private def allResolved(ids: Vector[String]): Vector[AuditResearcherArg] =
    ids.zipWithIndex.map { case (id, i) =>
      AuditResearcherArg(i, Some(id), AuditResearcherArg.Resolved)
    }

  private def abstain(
      queryId: String,
      t0: Long,
      plan: QueryPlan,
      reason: AbstentionReason,
      detail: String,
      names: Vector[NameAbstention],
      gaps: Vector[String],
      resolved: Vector[AuditResearcherArg],
      hinted: Set[Int],
      evidence: Option[BuiltEvidence] = None,
      accounting: Option[SynthesisAccounting] = None
  ) = finish(
    queryId,
    t0,
    WorkflowOutcome.Abstained(reason, detail, names, gaps),
    Some(plan),
    resolved,
    evidence,
    Vector.empty,
    None,
    accounting,
    Map.empty,
    hinted
  )

  private def planned(queryId: String, t0: Long, plan: QueryPlan): WorkflowResult = {
    val resolveSteps = plan.steps.filter(_.operation == QueryOperation.ResolveResearcher)
    val resolutions = all(resolveSteps) { s =>
      val name = s.arguments.getOrElse(QueryPlanner.Args.Name, "")
      resolver
        .resolve(name, plan.window, s.arguments.get(QueryPlanner.Args.Institution))
        .map(s -> _)
    }
    resolutions match {
      case Left(e) =>
        abstain(
          queryId,
          t0,
          plan,
          AbstentionReason.StageFailure,
          e.message,
          Vector.empty,
          Vector.empty,
          resolveSteps.indices.toVector.map(i =>
            AuditResearcherArg(i, None, AuditResearcherArg.NotRun)
          ),
          Set.empty
        )
      case Right(rs) =>
        val hinted = institutionApplied(rs.map(_._2))
        val abstained = rs.collect { case (_, Resolution.Abstained(n, why, cs, _)) =>
          NameAbstention(n, why, cs)
        }
        val resolved = rs.collect { case (s, Resolution.Resolved(n, c, _, _, _)) => (s, n, c) }
        val nameArgs = rs.zipWithIndex.map {
          case ((_, Resolution.Resolved(_, c, _, _, _)), i) =>
            AuditResearcherArg(i, Some(c.researcherId), AuditResearcherArg.Resolved)
          case ((_, _), i) => AuditResearcherArg(i, None, AuditResearcherArg.Unresolved)
        }
        if (abstained.nonEmpty)
          abstain(
            queryId,
            t0,
            plan,
            AbstentionReason.IdentityUnresolved,
            s"${abstained.size} name(s) did not resolve to exactly one researcher",
            abstained,
            Vector.empty,
            nameArgs,
            hinted
          )
        else gathered(queryId, t0, plan, resolved, hinted)
    }
  }

  private def gathered(
      queryId: String,
      t0: Long,
      plan: QueryPlan,
      resolved: Vector[(QueryStep, String, ResearcherCandidate)],
      hinted: Set[Int]
  ): WorkflowResult = {
    val ids = resolved.map(_._3.researcherId)
    val identity: Vector[StageOutput] = resolved.map { case (s, n, c) =>
      StageOutput.Resolved(s.ordinal, n, c)
    }
    val filters = plan.steps.exists(s =>
      s.operation != QueryOperation.ResolveResearcher &&
        Vector(QueryPlanner.Args.Topic, QueryPlanner.Args.Venue, QueryPlanner.Args.Institution)
          .exists(s.arguments.contains)
    )
    val filterGap =
      if (filters)
        Vector(
          "a topic, venue or institution filter in the question is not applied to the stored graph and metric records"
        )
      else Vector.empty
    val ran = plan.steps
      .filterNot(s =>
        s.operation == QueryOperation.ResolveResearcher || s.operation == QueryOperation.VerifyClaims
      )
      .foldLeft[Either[AgentError, (Vector[StageOutput], Vector[String])]](
        Right((identity, filterGap))
      ) { (acc, s) =>
        acc.flatMap { case (outs, gaps) =>
          run(s, plan, ids, outs).map { case (o, g) => (outs ++ o, gaps ++ g) }
        }
      }
    ran match {
      case Left(e) =>
        abstain(
          queryId,
          t0,
          plan,
          AbstentionReason.StageFailure,
          e.message,
          Vector.empty,
          Vector.empty,
          allResolved(ids),
          hinted
        )
      case Right((outs, gaps)) =>
        builder.build(queryId, outs) match {
          case Left(e: AgentError.BudgetExceeded) =>
            abstain(
              queryId,
              t0,
              plan,
              AbstentionReason.EvidenceBudget,
              e.message,
              Vector.empty,
              gaps,
              allResolved(ids),
              hinted
            )
          case Left(e) =>
            abstain(
              queryId,
              t0,
              plan,
              AbstentionReason.StageFailure,
              e.message,
              Vector.empty,
              gaps,
              allResolved(ids),
              hinted
            )
          case Right(ev) => evidenced(queryId, t0, plan, resolved, outs, gaps, ev, hinted)
        }
    }
  }

  private def evidenced(
      queryId: String,
      t0: Long,
      plan: QueryPlan,
      resolved: Vector[(QueryStep, String, ResearcherCandidate)],
      outs: Vector[StageOutput],
      gaps: Vector[String],
      ev: BuiltEvidence,
      hinted: Set[Int]
  ): WorkflowResult = {
    val ids = resolved.map(_._3.researcherId)
    val semantic = outs.collect { case StageOutput.Semantic(_, r) => r }
    val dataSteps = plan.steps.filterNot(s =>
      s.operation == QueryOperation.ResolveResearcher || s.operation == QueryOperation.VerifyClaims
    )
    val dataIds = dataSteps.flatMap(s => ev.stepEvidence.getOrElse(s.ordinal, Vector.empty))
    def stop(
        reason: AbstentionReason,
        detail: String,
        accounting: Option[SynthesisAccounting] = None
    ) =
      abstain(
        queryId,
        t0,
        plan,
        reason,
        detail,
        Vector.empty,
        gaps,
        allResolved(ids),
        hinted,
        Some(ev),
        accounting
      )
    if (dataIds.isEmpty)
      stop(AbstentionReason.InsufficientEvidence, "no stored evidence for the question")
    else
      resolverOfEvidence.resolve(ev.bundle) match {
        case Left(e) => stop(AbstentionReason.StageFailure, e.message)
        case Right(bad) if bad.nonEmpty =>
          stop(
            AbstentionReason.EvidenceUnresolved,
            bad.map(b => s"${b.evidenceId}: ${b.reason}").mkString("; ")
          )
        case Right(_) =>
          BundleFacts.of(ev.bundle) match {
            case Left(why) => stop(AbstentionReason.StageFailure, why)
            case Right(facts) =>
              val input = SynthesisInput(
                plan,
                resolved.map { case (_, n, c) => n -> c.researcherId },
                facts,
                gaps
              )
              synthesis.synthesizeAccounted(input) match {
                case (Left(f), acc) =>
                  stop(AbstentionReason.SynthesisFailed, f.message, Some(acc))
                case (Right(syn), acc) =>
                  verified(queryId, t0, plan, ids, gaps, ev, semantic, facts, syn, acc, hinted)
              }
          }
      }
  }

  private def verified(
      queryId: String,
      t0: Long,
      plan: QueryPlan,
      ids: Vector[String],
      gaps: Vector[String],
      ev: BuiltEvidence,
      semantic: Vector[SemanticResult],
      facts: Vector[Fact],
      syn: Synthesized,
      acc: SynthesisAccounting,
      hinted: Set[Int]
  ): WorkflowResult = {
    val windows = Set(BundleFacts.windowText(plan.window)) ++
      plan.steps.flatMap(s => windowOf(s.arguments.toMap)).map(BundleFacts.windowText)
    val report = verifier.verify(
      queryId,
      ev.bundle,
      facts,
      syn.output,
      windows,
      syn.modelVersion,
      Prompts.AnswerSynthesisVersion,
      gaps
    )
    if (report.claims.isEmpty)
      finish(
        queryId,
        t0,
        WorkflowOutcome.Abstained(
          AbstentionReason.NothingVerified,
          "no claim survived verification",
          Vector.empty,
          gaps
        ),
        Some(plan),
        allResolved(ids),
        Some(ev),
        semantic,
        Some(syn -> report),
        Some(acc),
        Map.empty,
        hinted
      )
    else {
      val text = CollaborationWorkflow.answerText(
        report,
        syn.output.explanations,
        syn.nonProduction,
        ev.semanticIds.nonEmpty
      )
      val response = AnswerId.of(queryId, ev.bundle.bundleId, text).toOption.map { rid =>
        AnswerResponse(
          SchemaVersions.AnswerResponse,
          rid,
          queryId,
          plan.window,
          text,
          report.claims,
          ev.bundle,
          Provenance(
            SourceType.QueryRuntime,
            queryId,
            serving.snapshot,
            serving.analysisRunId,
            SchemaVersions.QueryPlan,
            Some(ClaimVerificationAgent.Version),
            Some(syn.modelVersion),
            Some(Prompts.AnswerSynthesisVersion)
          )
        )
      }
      response.filter(r => AnswerResponse.contract.validate(r).isEmpty) match {
        case None =>
          abstain(
            queryId,
            t0,
            plan,
            AbstentionReason.StageFailure,
            "the answer fails its core contract",
            Vector.empty,
            gaps,
            allResolved(ids),
            hinted,
            Some(ev),
            Some(acc)
          )
        case Some(r) =>
          finish(
            queryId,
            t0,
            WorkflowOutcome.Answered(r, report, syn.nonProduction),
            Some(plan),
            allResolved(ids),
            Some(ev),
            semantic,
            Some(syn -> report),
            Some(acc),
            ev.stepEvidence,
            hinted
          )
      }
    }
  }

  private def finish(
      queryId: String,
      t0: Long,
      outcome: WorkflowOutcome,
      plan: Option[QueryPlan],
      names: Vector[AuditResearcherArg],
      evidence: Option[BuiltEvidence],
      semantic: Vector[SemanticResult],
      verified: Option[(Synthesized, VerificationReport)],
      accounting: Option[SynthesisAccounting],
      stepEvidence: Map[Int, Vector[String]],
      hinted: Set[Int]
  ): WorkflowResult = {
    val resolvedIds = names.flatMap(_.researcherId)
    val items = evidence.toVector.flatMap(_.bundle.items)
    // m3: the bundle is written exactly when an answer is returned, and only a written bundle is
    // named by the audit and the session row.
    val writtenBundle =
      evidence.filter(_ => outcome.isInstanceOf[WorkflowOutcome.Answered]).map(_.bundle)
    val metricVersions = items
      .filter(i => EvidenceSchemas.StoredRecords.contains(i.provenance.sourceSchemaVersion))
      .map(i => s"${i.provenance.sourceSchemaVersion}|${i.provenance.policyVersion.getOrElse("")}")
      .distinct
      .sorted
    val templates = items
      .filter(_.provenance.sourceSchemaVersion == EvidenceSchemas.PathTraversal)
      .flatMap(i =>
        scala.util.Try(upickle.default.read[PathTraversalEvidence](i.text.getOrElse(""))).toOption
      )
      .flatMap(p => p.template +: p.executedQueries)
      .distinct
    val answerText = outcome match {
      case WorkflowOutcome.Answered(r, _, _) => Some(r.answerText)
      case _ => None
    }
    val (reason, abstainedNames) = outcome match {
      case WorkflowOutcome.Abstained(r, _, ns, _) => (Some(r.tag), ns.size)
      case WorkflowOutcome.Refused(r, _) => (Some(r.tag), 0)
      case _: WorkflowOutcome.Answered => (None, 0)
    }
    val syn = verified.map(_._1)
    val report = verified.map(_._2)
    val audit = AuditRecord(
      AuditRecord.SchemaVersion,
      queryId,
      outcome.tag,
      reason,
      plan.map(AuditStage.planSha256),
      plan.map(_ => QueryPlanner.Version),
      plan.toVector.flatMap(_.steps.map(_.operation.tag)),
      plan.map(AuditPlan.of(_, names, hinted)),
      resolvedIds,
      abstainedNames,
      items.map(_.evidenceId),
      writtenBundle.map(_.bundleId),
      metricVersions,
      serving.analysisRunId,
      templates,
      // m1: the accounting of the synthesis, accepted or failed.
      accounting.map(_.promptVersion),
      accounting.map(_.outputSchemaVersion),
      accounting.map(_.modelVersion),
      accounting.exists(_.nonProduction),
      accounting.map(_.attempts).getOrElse(0),
      syn.map(_.outputSha256),
      accounting.flatMap(_.inputTokens),
      accounting.flatMap(_.outputTokens),
      (clock.monotonicNanos() - t0) / 1000000L,
      report.map(_.counts).getOrElse(Map.empty),
      report.map(_.overall.tag),
      report.map(_.droppedMissingEvidence).getOrElse(0),
      answerText.map(Canonical.sha256HexUtf8),
      // RG-P11-02: the claim-verification version, whenever claims were verified.
      report.map(_ => ClaimVerificationAgent.Version)
    )
    val stepStatus: Map[Int, String] = plan.toVector
      .flatMap(_.steps)
      .map { s =>
        val status =
          if (s.operation == QueryOperation.VerifyClaims)
            report.map(_.overall.tag).getOrElse("not-run")
          else if (outcome.isInstanceOf[WorkflowOutcome.Answered])
            if (stepEvidence.getOrElse(s.ordinal, Vector.empty).nonEmpty) "evidenced"
            else "no-evidence"
          else outcome.tag
        s.ordinal -> status
      }
      .toMap
    val write = AuditWrite(
      audit,
      plan,
      writtenBundle,
      stepEvidence,
      stepStatus,
      auditStage.stamp() // once per query; reused by every row and by a retry
    )
    val persisted = auditStage.persist(write)
    // m4: a Left means the audit row was NOT stored (the audit row is the commit point), so no
    // stored audit says "answered" for a withheld answer; a Right means it is stored, and the
    // outcome it records is returned (a later session-row failure is in notPersisted).
    (outcome, persisted) match {
      case (_: WorkflowOutcome.Answered, Left(e)) =>
        val returned = WorkflowOutcome.Abstained(
          AbstentionReason.AuditNotPersisted,
          s"the audit could not be persisted, so the answer is withheld: ${e.message}",
          Vector.empty,
          Vector.empty
        )
        // RE-P9-05 (CR-P9-02 F2): the caller never receives the answer, so the record a retry
        // stores is the WITHHELD outcome: abstained, reason audit-not-persisted, no answer hash.
        // The bundle, step rows and stamp are those this call attempted, so a retry repeats them
        // exactly (create-once rows already written are AlreadyPresent, never a conflict).
        val withheld = audit.copy(
          outcome = returned.tag,
          reason = Some(AbstentionReason.AuditNotPersisted.tag),
          answerSha256 = None
        )
        WorkflowResult(
          returned,
          plan,
          evidence,
          semantic,
          withheld,
          write.copy(audit = withheld),
          persisted
        )
      case _ => WorkflowResult(outcome, plan, evidence, semantic, audit, write, persisted)
    }
  }

  /**
   * Retries the persistence of `r` with its own rows and stamp (create-once, idempotent). The rows
   * are `r.auditWrite`, whose audit outcome is always `r.outcome` (F2: a withheld answer is retried
   * as the withheld abstention, never as `answered`).
   */
  def persistAgain(r: WorkflowResult): Either[AgentError, PersistReport] =
    auditStage.persist(r.auditWrite)
}

object CollaborationWorkflow {
  val Version = "scholarchain.rag.workflow.v1"

  /** F3: the abstention reasons decided by evaluating the institution hint. */
  val HintReasons: Set[AbstainReason] = Set(
    AbstainReason.InstitutionMatchesNone,
    AbstainReason.InstitutionMatchesSeveral,
    AbstainReason.AffiliationsTruncated
  )

  val NonProductionNotice =
    "[NON-PRODUCTION template synthesizer: deterministic test stand-in, not a language model]"

  val SemanticNotice =
    "Semantic context is descriptive only: vector similarity is never evidence of a coauthorship edge, a count, a path, a community or a bridge score."

  /**
   * RE-P9-04 (M3, m2): the fixed notice for model missing-evidence lines that were dropped because
   * they are not a workflow-detected gap (the lines themselves are never shown).
   */
  def droppedNotice(n: Int): String =
    s"$n missing-evidence line(s) written by the model were withheld: not a workflow-detected gap."

  /**
   * The answer text: kept claims only (verified, then labelled), missing evidence (workflow gaps
   * only), the dropped-line notice, fixed texts.
   */
  def answerText(
      r: VerificationReport,
      explanations: Vector[String],
      nonProduction: Boolean,
      semantic: Boolean
  ): String = {
    val fixed = Prompts.Explanations.toMap
    (Option.when(nonProduction)(NonProductionNotice).toVector ++
      r.claims.map(_.text) ++
      r.missingEvidence.map("Missing evidence: " + _) ++
      Option.when(r.droppedMissingEvidence > 0)(droppedNotice(r.droppedMissingEvidence)) ++
      explanations.flatMap(fixed.get) ++
      Option.when(semantic)(SemanticNotice)).mkString("\n")
  }
}
