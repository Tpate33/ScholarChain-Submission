package scholarchain.rag.benchmark

import upickle.default.ReadWriter
import upickle.implicits.allowUnknownKeys

import scholarchain.core.EvidenceType
import scholarchain.rag.agents.{
  BundleFacts,
  FactFields,
  ModelAssertion,
  Verdict,
  WorkflowOutcome,
  WorkflowResult
}

/** One fact a VERIFIED claim must assert (after alias substitution). */
@allowUnknownKeys(false)
final case class ExpectedFact(subject: String, field: String, value: String) derives ReadWriter

/**
 * The expected outcome of one benchmark question. Every string may name aliases as `{alias}`, which
 * the runner replaces with the IDs of the store world it runs against.
 *
 * @param outcome
 *   `answered`, `abstained` or `refused`
 * @param reason
 *   the abstention reason tag or plan refusal tag (abstained and refused only)
 * @param operations
 *   the exact plan operation sequence (empty: not checked; refused questions have no plan)
 * @param resolved
 *   the researcher IDs resolved (set equality)
 * @param candidates
 *   the candidate IDs of the abstaining names (set equality; empty: not checked)
 * @param verifiedFacts
 *   assertions some VERIFIED claim must make (exact subject, field and value)
 * @param allowedVerdicts
 *   README outcome tags every claim result must have (empty: not checked)
 * @param minVerified
 *   at least this many VERIFIED claims
 * @param forbiddenText
 *   strings the answer text must not contain (injection and fabrication checks)
 * @param semanticIndex
 *   an index whose retrieval must rank `semanticTopSubject` first
 */
@allowUnknownKeys(false)
final case class BenchmarkExpectation(
    outcome: String,
    reason: Option[String],
    operations: Vector[String],
    resolved: Vector[String],
    candidates: Vector[String],
    verifiedFacts: Vector[ExpectedFact],
    allowedVerdicts: Vector[String],
    minVerified: Int,
    forbiddenText: Vector[String],
    semanticIndex: Option[String],
    semanticTopSubject: Option[String]
) derives ReadWriter

/** One benchmark question. `category`: `graph` or `semantic` (the README gate's two families). */
@allowUnknownKeys(false)
final case class BenchmarkCase(
    id: String,
    category: String,
    question: String,
    expect: BenchmarkExpectation
) derives ReadWriter

/** A committed, versioned benchmark set (schema `prompts/schemas/benchmark-set.v1`). */
@allowUnknownKeys(false)
final case class BenchmarkSet(
    schemaVersion: String,
    setVersion: String,
    description: String,
    cases: Vector[BenchmarkCase]
) derives ReadWriter

/**
 * One check of one case; `family` is `graph` (deterministic retrieval) or `semantic`. `applicable`
 * (RE-P9-04, m6) is false when the case states no expectation for the check (and the check has
 * nothing to inspect): such a check is reported as not applicable, never counted as passed. A
 * not-applicable check always has `passed = true` so it cannot fail a case.
 */
final case class CheckResult(
    name: String,
    family: String,
    passed: Boolean,
    detail: String,
    applicable: Boolean
)

final case class CaseResult(
    id: String,
    category: String,
    outcome: String,
    checks: Vector[CheckResult]
) {
  def passed: Boolean = checks.forall(_.passed)
}

/**
 * Totals of one check family over the set. `checks` and `checksPassed` count APPLICABLE checks only
 * (m6); `checksNotApplicable` counts the checks without an expectation, separately.
 */
final case class FamilySummary(
    family: String,
    cases: Int,
    casesPassed: Int,
    checks: Int,
    checksPassed: Int,
    checksNotApplicable: Int
)

/**
 * The benchmark result. Deterministic graph retrieval and semantic retrieval are reported
 * SEPARATELY (README Phase 9 gate): `graph` counts the graph-family checks of every case and the
 * cases of category `graph`; `semantic` the semantic-family checks and the cases of category
 * `semantic`.
 */
final case class BenchmarkReport(
    setVersion: String,
    results: Vector[CaseResult],
    graph: FamilySummary,
    semantic: FamilySummary
) {
  def passed: Boolean = results.forall(_.passed)

  def lines: Vector[String] =
    Vector(graph, semantic).map(s =>
      s"${s.family}: cases ${s.casesPassed}/${s.cases} passed, checks ${s.checksPassed}/${s.checks} passed, ${s.checksNotApplicable} not applicable"
    ) ++ results.flatMap(r =>
      r.checks.filterNot(_.passed).map(c => s"FAILED ${r.id} ${c.family}/${c.name}: ${c.detail}")
    )
}

/**
 * RE-P9-02: the benchmark-query runner. Store-world independent: the caller passes the workflow
 * entry point and the alias map, so the rag fixture world (rag tests) and the local corpus (the
 * cross-module `tests` tree, test-engineer) run the same runner over their own committed sets.
 */
object BenchmarkRunner {
  val SchemaVersion = "scholarchain.rag.benchmark-set.v1"
  val SchemaFile = "prompts/schemas/benchmark-set.v1.schema.json"
  val Outcomes: Set[String] = Set("answered", "abstained", "refused")
  val Categories: Set[String] = Set("graph", "semantic")

  def parse(json: String): Either[String, BenchmarkSet] =
    scala.util
      .Try(upickle.default.read[BenchmarkSet](json))
      .toEither
      .left
      .map(e => s"benchmark set: ${e.getMessage}")
      .flatMap { s =>
        val bad = s.cases.find(c => !Outcomes(c.expect.outcome) || !Categories(c.category))
        if (s.schemaVersion != SchemaVersion)
          Left(s"benchmark set: schemaVersion is not $SchemaVersion")
        else if (s.cases.map(_.id).distinct.size != s.cases.size)
          Left("benchmark set: repeated case id")
        else
          bad.fold[Either[String, BenchmarkSet]](Right(s))(c =>
            Left(s"case ${c.id}: unknown outcome or category")
          )
      }

  def substitute(s: String, aliases: Map[String, String]): String =
    aliases.foldLeft(s) { case (acc, (k, v)) => acc.replace(s"{$k}", v) }

  /** Fields whose subject is a canonical researcher pair (UTF-8 order of the two IDs). */
  private val PairFields: Set[String] = Set(
    FactFields.JointPublicationCount,
    FactFields.FirstCollaborationYear,
    FactFields.LastCollaborationYear
  )

  /**
   * The expected subject as the facts write it: pair fields use the canonical pair order (a set
   * written with aliases cannot know the byte order of the IDs); path subjects keep their order.
   */
  def expectedSubject(f: ExpectedFact, aliases: Map[String, String]): String = {
    val s = substitute(f.subject, aliases)
    if (!PairFields.contains(f.field)) s
    else
      s.split("@", -1).toVector match {
        case Vector(p, w) =>
          p.split("\\|", -1).toVector match {
            case Vector(a, b) => BundleFacts.pair(a, b, w)
            case _ => s
          }
        case _ => s
      }
  }

  private def matches(f: ExpectedFact, a: ModelAssertion, aliases: Map[String, String]): Boolean =
    a.subject == expectedSubject(f, aliases) && a.field == f.field &&
      a.value == substitute(f.value, aliases)

  private def check(name: String, family: String, applicable: Boolean = true)(
      ok: => Boolean,
      detail: => String
  ): CheckResult =
    if (!applicable) CheckResult(name, family, true, "not applicable: no expectation", false)
    else {
      val passed = ok
      CheckResult(name, family, passed, if (passed) "" else detail, true)
    }

  def runCase(
      c: BenchmarkCase,
      aliases: Map[String, String],
      result: WorkflowResult
  ): CaseResult = {
    val e = c.expect
    def sub(s: String) = substitute(s, aliases)
    val G = "graph"
    val S = "semantic"
    val (reason, candidates, answer, report) = result.outcome match {
      case WorkflowOutcome.Abstained(r, _, ns, _) =>
        (Some(r.tag), ns.flatMap(_.candidates.map(_.researcherId)).toSet, "", None)
      case WorkflowOutcome.Refused(r, _) => (Some(r.tag), Set.empty[String], "", None)
      case WorkflowOutcome.Answered(r, v, _) =>
        (None, Set.empty[String], r.answerText, Some((r, v)))
    }
    val results = report.toVector.flatMap(_._2.results)
    val verifiedAssertions = results.filter(_.verdict == Verdict.Verified).flatMap(_.assertions)
    val ops = result.plan.toVector.flatMap(_.steps.map(_.operation.tag))
    val bundle = report.map(_._1.evidenceBundle)
    val semanticIds = bundle.toVector
      .flatMap(_.items)
      .filter(_.evidenceType == EvidenceType.TextChunk)
      .map(_.evidenceId)
      .toSet
    val graphChecks = Vector(
      check("outcome", G)(
        result.outcome.tag == e.outcome,
        s"outcome ${result.outcome.tag}, expected ${e.outcome}"
      ),
      check("reason", G, e.reason.isDefined)(
        e.reason.forall(r => reason.contains(r)),
        s"reason $reason, expected ${e.reason}"
      ),
      check("operations", G, e.operations.nonEmpty)(
        ops == e.operations,
        s"operations $ops, expected ${e.operations}"
      ),
      check("resolved", G)(
        result.audit.resolvedResearcherIds.toSet == e.resolved.map(sub).toSet,
        s"resolved ${result.audit.resolvedResearcherIds}, expected ${e.resolved.map(sub)}"
      ),
      check("candidates", G, e.candidates.nonEmpty)(
        candidates == e.candidates.map(sub).toSet,
        s"candidates $candidates"
      ),
      check("verified-facts", G, e.verifiedFacts.nonEmpty)(
        e.verifiedFacts.forall(f => verifiedAssertions.exists(matches(f, _, aliases))),
        s"missing verified facts ${e.verifiedFacts
            .filterNot(f => verifiedAssertions.exists(matches(f, _, aliases)))}"
      ),
      check("verdicts", G, e.allowedVerdicts.nonEmpty)(
        results.forall(r => e.allowedVerdicts.contains(r.verdict.tag)),
        s"verdicts ${results.map(_.verdict.tag)}"
      ),
      check("min-verified", G, e.minVerified > 0)(
        results.count(_.verdict == Verdict.Verified) >= e.minVerified,
        s"${results.count(_.verdict == Verdict.Verified)} verified"
      ),
      check("forbidden-text", G, e.forbiddenText.nonEmpty)(
        e.forbiddenText.forall(t => !answer.contains(sub(t))),
        "the answer contains a forbidden string"
      ),
      // An invariant of every answer: applicable whenever an answer exists to inspect.
      check("claims-cite-evidence", G, report.isDefined)(
        report.forall { case (r, _) =>
          r.claims.forall(cl =>
            cl.evidenceIds.nonEmpty && cl.evidenceIds.forall(i =>
              r.evidenceBundle.items.exists(_.evidenceId == i)
            )
          )
        },
        "a kept claim cites no evidence or evidence outside the bundle"
      )
    )
    val semanticChecks = Vector(
      check("semantic-top-subject", S, e.semanticIndex.isDefined && e.semanticTopSubject.isDefined)(
        (e.semanticIndex, e.semanticTopSubject) match {
          case (Some(ix), Some(top)) =>
            result.semantic
              .flatMap(_.retrievals)
              .find(_.index == ix)
              .flatMap(_.context.items.headOption)
              .exists(_.subjectId == sub(top))
          case _ => false
        },
        s"top subject of ${e.semanticIndex}: ${result.semantic.flatMap(_.retrievals).map(r => r.index -> r.context.items.headOption.map(_.subjectId))}"
      ),
      // An invariant: applicable whenever the answer's bundle holds semantic context.
      check("semantic-not-proof", S, semanticIds.nonEmpty)(
        results.forall(r =>
          r.verdict != Verdict.Verified || r.kept.forall(k => !k.evidenceIds.forall(semanticIds)) ||
            r.assertions.forall(a => FactFields.descriptive.contains(a.field))
        ),
        "a VERIFIED claim rests only on semantic context for a deterministic field"
      )
    )
    CaseResult(c.id, c.category, result.outcome.tag, graphChecks ++ semanticChecks)
  }

  /** Runs every case through `answer(queryId, question)`; query IDs are `query:<prefix>-<n>`. */
  def run(set: BenchmarkSet, aliases: Map[String, String], queryPrefix: String)(
      answer: (String, String) => WorkflowResult
  ): BenchmarkReport = {
    val results = set.cases.zipWithIndex.map { case (c, i) =>
      val q = substitute(c.question, aliases)
      runCase(c, aliases, answer(s"query:$queryPrefix-$i", q))
    }
    def summary(family: String) = {
      val all = results.flatMap(_.checks.filter(_.family == family))
      val (checks, notApplicable) = all.partition(_.applicable)
      val cases = results.filter(_.category == family)
      FamilySummary(
        family,
        cases.size,
        cases.count(_.passed),
        checks.size,
        checks.count(_.passed),
        notApplicable.size
      )
    }
    BenchmarkReport(set.setVersion, results, summary("graph"), summary("semantic"))
  }
}
