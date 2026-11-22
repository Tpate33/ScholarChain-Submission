package scholarchain.rag.agents

import java.nio.charset.StandardCharsets
import java.util.Locale

import scala.collection.immutable.SortedMap
import scala.util.matching.Regex

import scholarchain.core.{
  CollaborationMetric,
  IdKind,
  Provenance,
  QueryOperation,
  QueryPlan,
  QueryStep,
  SchemaVersions,
  SourceType,
  StableId,
  TimeWindow
}

/**
 * The question intents the planner recognises (README Phase 9 stage 1). Each maps to allowlisted
 * [[QueryOperation]] steps only; filters (topic, venue, institution) are typed step arguments, not
 * operations (core has no filter operation).
 */
enum Intent(val tag: String) {
  case ResearcherRanking extends Intent("researcher-ranking")
  case StrongestRepeatedCollaboration extends Intent("strongest-repeated-collaboration")
  case MaximumTeamSize extends Intent("maximum-team-size")
  case DirectCoauthors extends Intent("direct-coauthors")
  case ShortestPath extends Intent("shortest-path")
  case ComponentLookup extends Intent("component-lookup")
  case CommunityComparison extends Intent("community-comparison")
  case BridgeRanking extends Intent("bridge-ranking")
  case TemporalComparison extends Intent("temporal-comparison")
  case MetricLookup extends Intent("metric-lookup")
  case CorenessLookup extends Intent("coreness-lookup")
  case SemanticExplanation extends Intent("semantic-explanation")
}

/** Why the planner abstains. A refusal is never a guess and never a partial plan. */
enum PlanRefusal(val tag: String) {
  case EmptyQuestion extends PlanRefusal("empty-question")
  case QuestionTooLong extends PlanRefusal("question-too-long")
  case ControlCharacters extends PlanRefusal("control-characters")
  case InvalidQueryId extends PlanRefusal("invalid-query-id")
  case NoAllowlistedOperation extends PlanRefusal("no-allowlisted-operation")
  case MissingResearcher extends PlanRefusal("missing-researcher")
  case WrongResearcherCount extends PlanRefusal("wrong-researcher-count")
  case TooManyNames extends PlanRefusal("too-many-names")
  case UnparseableWindow extends PlanRefusal("unparseable-window")
  case TemporalNeedsTwoWindows extends PlanRefusal("temporal-needs-two-windows")
  case InvalidPlan extends PlanRefusal("invalid-plan")
}

/** Where the plan's window came from. */
enum WindowSource(val tag: String) {
  case Question extends WindowSource("question")
  case ConfiguredDefault extends WindowSource("configured-default")
}

/**
 * The typed reading of a question. Every string here is untrusted user DATA: it is only ever an
 * argument value of a typed step, hashed for the entity-link lookup or compared for equality, never
 * query text.
 */
final case class ParsedQuestion(
    intents: Vector[Intent],
    names: Vector[String],
    window: TimeWindow,
    windowSource: WindowSource,
    toWindow: Option[TimeWindow],
    topic: Option[String],
    venue: Option[String],
    institution: Option[String]
)

enum PlanOutcome {
  case Planned(plan: QueryPlan, parsed: ParsedQuestion)
  case Refused(reason: PlanRefusal, detail: String)
}

/**
 * RE-P9-01 stage 1, QueryPlannerAgent: a DETERMINISTIC parser (no model call) of question text into
 * a validated core [[QueryPlan]] (rules version [[QueryPlanner.Version]], argument vocabulary
 * `prompts/schemas/query-plan-arguments.v1.schema.json`).
 *
 * Laws: only the operations of [[QueryPlanner.AllowedOperations]] are ever emitted; user text
 * becomes argument VALUES only (keys are fixed tokens); no Cypher, key, table, index or AWS call is
 * ever built from it (graph access is through storage's closed
 * [[scholarchain.storage.model.GraphQuery]] set with ID-typed parameters). A question with no
 * recognised intent, a missing or extra name, or an unparseable year expression is refused, never
 * guessed.
 */
object QueryPlanner {
  val Version = "scholarchain.rag.query-planner.v1"

  /** The plan operations this planner may emit (k-core is reached through coreness lookups). */
  val AllowedOperations: Vector[QueryOperation] = Vector(
    QueryOperation.ResolveResearcher,
    QueryOperation.RankResearchers,
    QueryOperation.GetMetrics,
    QueryOperation.DirectCollaborators,
    QueryOperation.ShortestPath,
    QueryOperation.Component,
    QueryOperation.Community,
    QueryOperation.BridgeResearchers,
    QueryOperation.KCore,
    QueryOperation.TeamSize,
    QueryOperation.TemporalTrend,
    QueryOperation.SemanticContext,
    QueryOperation.VerifyClaims
  )

  /** Argument keys (fixed tokens; values are data). Mirrors the versioned argument schema. */
  object Args {
    val Name = "name"
    val Researchers = "researchers"
    val Intent = "intent"
    val WindowSource = "window.source"
    val ToWindowStart = "compare.toWindowStart"
    val ToWindowEnd = "compare.toWindowEnd"
    val Topic = "filter.topic"
    val Venue = "filter.venue"
    val Institution = "filter.institution"
    val RankingRule = "ranking.rule"

    val all: Vector[String] =
      Vector(
        Name,
        Researchers,
        Intent,
        WindowSource,
        ToWindowStart,
        ToWindowEnd,
        Topic,
        Venue,
        Institution,
        RankingRule
      )
  }

  /**
   * The documented ranking rule of each ranked intent (README: no universal formula). Distinct
   * coauthors rank by C_r; strongest repeated collaboration by the joint publication count of the
   * coauthor edge (the weight W_r sums); bridges by the stored bridge ranking (cross-community
   * degree, the selected bridge metric of the stored rank, see [[CollaborationMetricsAgent]]).
   */
  object RankingRules {
    val DistinctCoauthors = "distinct-coauthor-count"
    val JointPublications = "joint-publication-count"
    val Bridge = "cross-community-degree"
    val Coreness = "coreness"
    val MaximumTeam = "maximum-coauthors-on-one-paper"
  }

  private def rx(s: String): Regex = ("(?iu)" + s).r

  private val intentRules: Vector[(Intent, Vector[Regex])] = Vector(
    Intent.ResearcherRanking -> Vector(
      rx(
        "\\b(most|highest number of|largest number of|greatest number of)\\s+(distinct\\s+|different\\s+|unique\\s+)?(co-?authors|collaborators)\\b"
      ),
      rx("\\bcollaboration breadth\\b")
    ),
    Intent.StrongestRepeatedCollaboration -> Vector(
      rx("\\bstrongest\\s+(repeated\\s+)?collaborat"),
      rx("\\bmost\\s+(repeated|frequent)\\s+collaborat"),
      rx("\\bmost\\s+joint\\s+(papers|publications)\\b")
    ),
    Intent.MaximumTeamSize -> Vector(
      rx("\\b(largest|biggest|maximum|max)\\s+team\\b"),
      rx("\\bteam\\s+sizes?\\b"),
      rx("\\bmost\\s+co-?authors\\s+on\\s+(a|one|a single)\\s+(paper|publication)\\b")
    ),
    Intent.DirectCoauthors -> Vector(
      rx("\\b(co-?authors|collaborators)\\s+of\\b"),
      rx("\\b(collaborated|co-?authored|worked|published)\\s+with\\b"),
      rx("\\bdirect\\s+(co-?authors|collaborators)\\b")
    ),
    Intent.ShortestPath -> Vector(
      rx("\\bshortest\\s+(co-?authorship\\s+|collaboration\\s+)?path\\b"),
      rx("\\bpath\\s+between\\b"),
      rx("\\bhow\\s+(is|are)\\b.+\\bconnected\\b"),
      rx("\\bdegrees?\\s+of\\s+separation\\b")
    ),
    Intent.ComponentLookup -> Vector(rx("\\bcomponents?\\b")),
    Intent.CommunityComparison -> Vector(rx("\\bcommunit(y|ies)\\b")),
    Intent.BridgeRanking -> Vector(
      rx("\\bbridg(e|es|ing)\\b"),
      rx("\\bbrokers?\\b"),
      rx("\\bspan(s|ning)?\\s+communities\\b")
    ),
    Intent.TemporalComparison -> Vector(
      rx("\\bover\\s+time\\b"),
      rx("\\btrends?\\b"),
      rx("\\bchang(e|ed|es)\\b"),
      rx("\\bcompared?\\s+(with|to)\\b"),
      rx("\\bversus\\b")
    ),
    Intent.MetricLookup -> Vector(
      rx("\\b(how many|number of|count of)\\s+(publications|papers|co-?authors|collaborators)\\b"),
      rx("\\bpublication\\s+count\\b"),
      rx("\\bweighted\\s+degree\\b"),
      rx("\\baverage\\s+(team|number of co-?authors)\\b")
    ),
    Intent.CorenessLookup -> Vector(rx("\\bcoreness\\b"), rx("\\bk-?core\\b")),
    Intent.SemanticExplanation -> Vector(
      rx("\\bexplain\\b"),
      rx("\\bwhy\\b"),
      rx("\\bdescribe\\b"),
      rx("\\bsummari[sz]e\\b"),
      rx("\\bresearch\\s+(areas?|topics?|interests?)\\b")
    )
  )

  // Windows: explicit ranges or a single year; anything else that looks like a year refuses.
  private val RangeWords = rx(
    "\\b(?:between|from)\\s+(\\d{4})\\s+(?:and|to|until|through)\\s+(\\d{4})\\b"
  )
  private val RangeDash = rx("\\b(\\d{4})\\s*(?:-|–|to)\\s*(\\d{4})\\b")
  private val SingleYear = rx("\\bin\\s+(\\d{4})\\b")
  private val AnyYear = rx("\\b(1\\d{3}|2\\d{3})\\b")

  private val Quoted = "[\"“”]([^\"“”]{1,200})[\"“”]".r
  // Case-insensitive keywords only: the captured words must start with a capital (or digit),
  // so the case-insensitive flag is scoped to the keyword group.
  private val InstitutionRx =
    ("\\b(?iu:at|affiliated\\s+with)\\s+(?:(?iu:the)\\s+)?((?:\\p{Lu}[\\p{L}\\p{M}&.'-]*)" +
      "(?:\\s+(?:of\\s+|for\\s+|and\\s+)?\\p{Lu}[\\p{L}\\p{M}&.'-]*)*)").r
  private val VenueRx =
    ("\\b(?iu:venue|conference|journal)\\s+((?:[\\p{Lu}\\p{N}][\\p{L}\\p{N}&.-]*)" +
      "(?:\\s+[\\p{Lu}\\p{N}][\\p{L}\\p{N}&.-]*)*)").r
  private val TopicRxs = Vector(
    rx("\\btopic\\s+(?:of\\s+)?([^\",?.;]{2,60})"),
    rx("\\b(?:research|papers|publications|work)\\s+(?:on|about)\\s+([^\",?.;]{2,60})")
  )
  // A name word has an upper-case initial and at least one lower-case letter (McDonald, O'Neil),
  // or is an initial ("J."). All-capital words (MATCH, DELETE, MIT) are never name words.
  private val NameToken =
    "\\p{Lu}(?:[\\p{L}\\p{M}'’-]*\\p{Ll}[\\p{L}\\p{M}'’-]*|\\.)"
  private val TopicStop =
    rx("\\s+(?:between|from|since|during|in|before|after|for|by|with|and)\\b.*$")
  private val Particle = "(?:van|von|de|der|den|da|di|del|della|la|le|du|bin|al|el|ten|ter)"
  private val CapitalSeq =
    s"$NameToken(?:\\s+(?:$Particle\\s+)*$NameToken)+".r

  /** Capitalised words that start sentences or name the domain, never part of a name. */
  private val StopWords: Set[String] = Set(
    "who",
    "what",
    "which",
    "how",
    "show",
    "list",
    "find",
    "compare",
    "explain",
    "describe",
    "summarize",
    "summarise",
    "is",
    "are",
    "did",
    "does",
    "do",
    "has",
    "have",
    "the",
    "between",
    "in",
    "from",
    "since",
    "give",
    "tell",
    "me",
    "name",
    "rank",
    "top",
    "and",
    "of",
    "with",
    "for",
    "please",
    "ignore",
    "previous",
    "instructions",
    "why",
    "where",
    "when",
    "whose",
    "a",
    "an"
  ).map(_.toLowerCase(Locale.ROOT))

  private def collapse(s: String): String = s.trim.replaceAll("\\s+", " ")

  private def blank(text: String, spans: Iterable[(Int, Int)]): String = {
    val b = new StringBuilder(text)
    spans.foreach { case (s, e) => (s until e).foreach(i => b.setCharAt(i, ' ')) }
    b.toString
  }

  private def year(s: String): Int = s.toInt

  /** Windows in question order, or a refusal for a year expression outside the patterns. */
  private def windows(q: String): Either[(PlanRefusal, String), Vector[TimeWindow]] = {
    val found = scala.collection.mutable.ArrayBuffer.empty[(Int, Int, Int, Int)]
    def overlaps(s: Int, e: Int) = found.exists { case (a, b, _, _) => s < b && a < e }
    Vector(RangeWords, RangeDash).foreach { r =>
      r.findAllMatchIn(q).foreach { m =>
        if (!overlaps(m.start, m.end))
          found += ((m.start, m.end, year(m.group(1)), year(m.group(2))))
      }
    }
    SingleYear.findAllMatchIn(q).foreach { m =>
      if (!overlaps(m.start, m.end)) found += ((m.start, m.end, year(m.group(1)), year(m.group(1))))
    }
    val stray = AnyYear
      .findAllMatchIn(q)
      .exists(m =>
        !found.exists { case (a, b, _, _) =>
          m.start >= a && m.end <= b
        }
      )
    if (stray)
      Left(
        PlanRefusal.UnparseableWindow ->
          "a year appears outside 'between Y1 and Y2', 'from Y1 to Y2', 'Y1-Y2' or 'in Y'"
      )
    else
      found
        .sortBy(_._1)
        .toVector
        .foldLeft[Either[(PlanRefusal, String), Vector[TimeWindow]]](
          Right(Vector.empty)
        ) { case (acc, (_, _, s, e)) =>
          acc.flatMap(ws =>
            TimeWindow.of(s, e) match {
              case Right(w) => Right(ws :+ w)
              case Left(_) => Left(PlanRefusal.UnparseableWindow -> s"$s..$e is not a valid window")
            }
          )
        }
  }

  private def names(text: String): Vector[String] =
    CapitalSeq
      .findAllIn(text)
      .toVector
      .map { seq =>
        val tokens = seq.split("\\s+").toVector
        tokens.dropWhile(t => StopWords.contains(t.toLowerCase(Locale.ROOT))).mkString(" ")
      }
      .filter(_.split("\\s+").count(t => !t.matches(Particle)) >= 2)
      .map(collapse)
      .distinct

  /** The typed reading of a question (no plan yet). */
  def parse(question: String, limits: AgentLimits): Either[(PlanRefusal, String), ParsedQuestion] =
    if (question == null || question.trim.isEmpty) Left(PlanRefusal.EmptyQuestion -> "empty")
    else if (question.getBytes(StandardCharsets.UTF_8).length > limits.maxQuestionBytes)
      Left(PlanRefusal.QuestionTooLong -> s"more than ${limits.maxQuestionBytes} bytes")
    else if (
      question.exists(c =>
        (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t') ||
          Character.getType(c) == Character.FORMAT
      )
    )
      Left(PlanRefusal.ControlCharacters -> "control or format characters are refused")
    else {
      val q = collapse(question)
      val intents = intentRules.collect {
        case (i, rs) if rs.exists(_.findFirstIn(q).isDefined) => i
      }
      // A temporal word alone ("changed") is not a comparison unless two windows are named; it is
      // classified below once the windows are known.
      for {
        ws <- windows(q)
        quoted = Quoted.findAllMatchIn(q).toVector
        quotedNames = quoted.map(m => collapse(m.group(1))).filter(_.nonEmpty).distinct
        afterQuotes = blank(q, quoted.map(m => (m.start, m.end)))
        inst = InstitutionRx.findFirstMatchIn(afterQuotes)
        venue = VenueRx.findFirstMatchIn(afterQuotes)
        topicSpan = TopicRxs.flatMap(_.findFirstMatchIn(afterQuotes)).headOption.map { m =>
          val trimmed = TopicStop.replaceFirstIn(m.group(1), "").trim
          (trimmed, m.start(1), m.start(1) + m.group(1).indexOf(trimmed) + trimmed.length)
        }
        topic = topicSpan.map(_._1)
        rest = blank(
          afterQuotes,
          (inst.toVector ++ venue.toVector).map(m => (m.start, m.end)) ++
            topicSpan.toVector.map(t => (t._2, t._3))
        )
        ns = if (quotedNames.nonEmpty) quotedNames else names(rest)
        _ <-
          if (ns.size > limits.maxNames)
            Left(PlanRefusal.TooManyNames -> s"${ns.size} names; at most ${limits.maxNames}")
          else Right(())
        primary = intents.filterNot(i =>
          i == Intent.SemanticExplanation || i == Intent.TemporalComparison
        )
        temporal = intents.contains(Intent.TemporalComparison)
        _ <-
          if (temporal && ws.size != 2 && primary.isEmpty)
            Left(
              PlanRefusal.TemporalNeedsTwoWindows ->
                "a temporal comparison names exactly two windows (for example 2010-2014 and 2015-2019)"
            )
          else Right(())
        _ <-
          if (ws.size > 2) Left(PlanRefusal.UnparseableWindow -> "at most two windows")
          else if (ws.size == 2 && !temporal)
            Left(PlanRefusal.UnparseableWindow -> "two windows without a comparison")
          else Right(())
        // "bridge ... communities" is a bridge question: the bridge step already carries the
        // community view, so the community word alone adds no community step.
        finalIntents = intents
          .filter(i => i != Intent.TemporalComparison || ws.size == 2)
          .filter(i => i != Intent.CommunityComparison || !intents.contains(Intent.BridgeRanking))
        _ <-
          if (finalIntents.isEmpty)
            Left(PlanRefusal.NoAllowlistedOperation -> "no recognised collaboration question")
          else Right(())
      } yield ParsedQuestion(
        finalIntents,
        ns,
        ws.headOption.getOrElse(limits.defaultWindow),
        if (ws.isEmpty) WindowSource.ConfiguredDefault else WindowSource.Question,
        ws.lift(1),
        topic.map(collapse).filter(_.nonEmpty),
        venue.map(m => collapse(m.group(1))).filter(_.nonEmpty),
        inst.map(m => collapse(m.group(1))).filter(_.nonEmpty)
      )
    }

  private final case class Op(
      operation: QueryOperation,
      metrics: Vector[CollaborationMetric],
      args: Vector[(String, String)]
  )

  /** Operation steps of one intent (after the resolve steps), or a refusal. */
  private def opsOf(
      i: Intent,
      names: Int,
      refs: String
  ): Either[(PlanRefusal, String), Vector[Op]] = {
    import CollaborationMetric.*
    def need(min: Int, max: Int): Either[(PlanRefusal, String), Unit] =
      if (names == 0 && min > 0)
        Left(PlanRefusal.MissingResearcher -> s"${i.tag} needs a researcher name")
      else if (names < min || names > max)
        Left(
          PlanRefusal.WrongResearcherCount -> s"${i.tag} takes $min..$max researcher name(s), got $names"
        )
      else Right(())
    val who = Vector(Args.Researchers -> refs)
    val tag = Args.Intent -> i.tag
    i match {
      case Intent.ResearcherRanking =>
        if (names == 0)
          Right(
            Vector(
              Op(
                QueryOperation.RankResearchers,
                Vector(DistinctCoauthorCount),
                Vector(tag, Args.RankingRule -> RankingRules.DistinctCoauthors)
              )
            )
          )
        else
          need(1, 2).map(_ =>
            Vector(Op(QueryOperation.GetMetrics, Vector(DistinctCoauthorCount), who :+ tag))
          )
      case Intent.StrongestRepeatedCollaboration =>
        if (names == 0)
          Right(
            Vector(
              Op(
                QueryOperation.RankResearchers,
                Vector(JointPublicationCount),
                Vector(tag, Args.RankingRule -> RankingRules.JointPublications)
              )
            )
          )
        else
          need(1, 1).map(_ =>
            Vector(
              Op(
                QueryOperation.DirectCollaborators,
                Vector(JointPublicationCount),
                who ++ Vector(tag, Args.RankingRule -> RankingRules.JointPublications)
              ),
              Op(QueryOperation.GetMetrics, Vector(WeightedDegree), who :+ tag)
            )
          )
      case Intent.MaximumTeamSize =>
        if (names == 0)
          Right(
            Vector(
              Op(
                QueryOperation.RankResearchers,
                Vector(MaximumCoauthorsOnOnePaper),
                Vector(tag, Args.RankingRule -> RankingRules.MaximumTeam)
              )
            )
          )
        else
          need(1, 2).map(_ =>
            Vector(
              Op(
                QueryOperation.TeamSize,
                Vector(MaximumCoauthorsOnOnePaper, AverageCoauthorsPerPaper),
                who :+ tag
              )
            )
          )
      case Intent.DirectCoauthors =>
        // One name: the researcher's direct coauthors; two names: the direct edge between them.
        need(1, 2).map(_ =>
          Vector(
            Op(QueryOperation.DirectCollaborators, Vector(JointPublicationCount), who :+ tag)
          )
        )
      case Intent.ShortestPath =>
        need(2, 2).map(_ =>
          Vector(Op(QueryOperation.ShortestPath, Vector(ShortestPathLength), who :+ tag))
        )
      case Intent.ComponentLookup =>
        need(1, 2).map(_ =>
          Vector(Op(QueryOperation.Component, Vector(ComponentId, ComponentSize), who :+ tag))
        )
      case Intent.CommunityComparison =>
        need(1, 2).map(_ =>
          Vector(
            Op(
              QueryOperation.Community,
              Vector(CommunityId, CrossCommunityDegree, DistinctCoauthorCount),
              who :+ tag
            )
          )
        )
      case Intent.BridgeRanking =>
        val ms = Vector(CrossCommunityDegree, BridgeScore, ParticipationCoefficient)
        if (names == 0)
          Right(
            Vector(
              Op(
                QueryOperation.BridgeResearchers,
                ms,
                Vector(tag, Args.RankingRule -> RankingRules.Bridge)
              )
            )
          )
        else
          need(1, 2).map(_ =>
            Vector(Op(QueryOperation.BridgeResearchers, ms :+ DistinctCoauthorCount, who :+ tag))
          )
      case Intent.TemporalComparison =>
        need(1, 2).map(_ =>
          Vector(
            Op(
              QueryOperation.TemporalTrend,
              Vector(PublicationCount, DistinctCoauthorCount, WeightedDegree),
              who :+ tag
            )
          )
        )
      case Intent.MetricLookup =>
        need(1, 2).map(_ =>
          Vector(
            Op(
              QueryOperation.GetMetrics,
              Vector(
                PublicationCount,
                DistinctCoauthorCount,
                TotalCoauthorOccurrences,
                AverageCoauthorsPerPaper,
                WeightedDegree
              ),
              who :+ tag
            )
          )
        )
      case Intent.CorenessLookup =>
        if (names == 0)
          Right(
            Vector(
              Op(
                QueryOperation.RankResearchers,
                Vector(Coreness),
                Vector(tag, Args.RankingRule -> RankingRules.Coreness)
              )
            )
          )
        else need(1, 2).map(_ => Vector(Op(QueryOperation.KCore, Vector(Coreness), who :+ tag)))
      case Intent.SemanticExplanation =>
        Right(
          Vector(
            Op(
              QueryOperation.SemanticContext,
              Vector.empty,
              (if (names > 0) who else Vector.empty) :+ tag
            )
          )
        )
    }
  }

  /**
   * Plans a question. `queryId` is allocated by the caller (`query:<token>`); the plan's provenance
   * is a runtime record of the served analysis run, policy = this planner's rules version, no model
   * and no prompt (deterministic code).
   */
  def plan(
      queryId: String,
      question: String,
      limits: AgentLimits,
      serving: ServingContext
  ): PlanOutcome =
    if (StableId.validate(IdKind.Query, queryId, "queryId").isLeft)
      PlanOutcome.Refused(PlanRefusal.InvalidQueryId, "queryId: not query:<token>")
    else
      parse(question, limits) match {
        case Left((r, d)) => PlanOutcome.Refused(r, d)
        case Right(p) =>
          val resolveSteps = p.names.map { n =>
            (
              QueryOperation.ResolveResearcher,
              Vector.empty[CollaborationMetric],
              Vector(Args.Name -> n) ++ p.institution.map(Args.Institution -> _).toVector
            )
          }
          val refs = p.names.indices.mkString(",")
          val filters =
            Vector(Args.WindowSource -> p.windowSource.tag) ++
              p.toWindow.toVector.flatMap(w =>
                Vector(
                  Args.ToWindowStart -> w.startYear.toString,
                  Args.ToWindowEnd -> w.endYear.toString
                )
              ) ++ p.topic.map(Args.Topic -> _) ++ p.venue.map(Args.Venue -> _) ++
              p.institution.map(Args.Institution -> _)
          p.intents
            .foldLeft[Either[(PlanRefusal, String), Vector[Op]]](Right(Vector.empty)) { (acc, i) =>
              acc.flatMap(xs => opsOf(i, p.names.size, refs).map(xs ++ _))
            } match {
            case Left((r, d)) => PlanOutcome.Refused(r, d)
            case Right(ops) =>
              val all =
                resolveSteps ++ ops.map(o => (o.operation, o.metrics, o.args ++ filters)) :+
                  (QueryOperation.VerifyClaims, Vector.empty[CollaborationMetric], Vector.empty)
              val steps = all.zipWithIndex.map { case ((op, ms, args), i) =>
                QueryStep(i, op, ms, SortedMap.from(args))
              }
              val plan = QueryPlan(
                SchemaVersions.QueryPlan,
                queryId,
                question,
                p.window,
                steps,
                Provenance(
                  SourceType.QueryRuntime,
                  queryId,
                  serving.snapshot,
                  serving.analysisRunId,
                  SchemaVersions.QueryPlan,
                  Some(Version),
                  None,
                  None
                )
              )
              val errors = QueryPlan.contract.validate(plan)
              if (errors.nonEmpty)
                PlanOutcome.Refused(PlanRefusal.InvalidPlan, errors.map(_.message).mkString("; "))
              else if (!steps.forall(s => AllowedOperations.contains(s.operation)))
                PlanOutcome.Refused(PlanRefusal.InvalidPlan, "operation outside the allowlist")
              else if (!steps.forall(_.arguments.keys.forall(Args.all.contains)))
                PlanOutcome.Refused(PlanRefusal.InvalidPlan, "argument key outside the vocabulary")
              else PlanOutcome.Planned(plan, p)
          }
      }
}
