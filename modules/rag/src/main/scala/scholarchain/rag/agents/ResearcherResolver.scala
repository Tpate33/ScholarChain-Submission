package scholarchain.rag.agents

import java.text.Normalizer
import java.util.Locale

import scholarchain.core.{
  Canonical,
  IdentityConfidence,
  IdKind,
  ResearcherMetricsV2,
  SchemaVersions,
  TimeWindow
}
import scholarchain.storage.model.{EntityLinkRecord, GraphQuery, ItemCodecs, TableKeys, TableRole}
import scholarchain.storage.port.{GraphReader, KeyQuery, KeyValueReader, SortKeyCondition}

/**
 * The surface-form key of a researcher name in the entity-link cache (EntityLinkCache EL-1,
 * `surface#<sha256>`): storage never normalizes names, so the normalization is rag's and versioned
 * here. Rules (v1): Unicode NFKC, lower case (ROOT locale), `.`, `,`, `;` and `:` become spaces,
 * whitespace runs collapse to one space, trimmed. Diacritics are KEPT (folding them would merge
 * distinct names). The name itself is never stored or sent anywhere: only its SHA-256.
 */
object SurfaceForms {
  val Version = "scholarchain.rag.surface-form.v1"

  def normalize(name: String): String =
    Normalizer
      .normalize(Option(name).getOrElse(""), Normalizer.Form.NFKC)
      .toLowerCase(Locale.ROOT)
      .replaceAll("[.,;:]", " ")
      .replaceAll("\\s+", " ")
      .trim

  def sha256(name: String): String = Canonical.sha256HexUtf8(normalize(name))
}

/** One affiliation of a candidate (graph `AFFILIATED_WITH`), as stored. */
final case class Affiliation(institutionId: String, name: String)

/** One direct coauthor of a candidate in the window (graph `COAUTHORED_WITH`), as stored. */
final case class CoauthorContext(collaboratorId: String, jointPublicationCount: Long)

/**
 * One researcher candidate of a name, with everything used to tell candidates apart. Every value is
 * copied from a store; none is estimated.
 *
 * @param matchedBy
 *   the lookup that produced the cached link (EntityLinkRecord `matchedBy`)
 * @param candidateRank
 *   the cache's candidate order (display only: the resolver NEVER picks by rank)
 * @param sourceIdentifiers
 *   source identifiers carried by the researcher ID itself (`orcid:<iD>` for an ORCID-anchored ID)
 *   and the anchor namespace; the per-source member IDs are not on the runtime path (see report)
 * @param identity
 *   the grouping run's identity confidence (C5) from the researcher's metrics row in the window,
 *   with its stored-row pointer; None when the researcher has no metrics row in the window
 * @param link
 *   RE-P9-02: the stored entity-link row exactly as decoded (identity evidence of the resolution)
 */
final case class ResearcherCandidate(
    researcherId: String,
    matchedBy: String,
    candidateRank: Int,
    sourceRunId: String,
    sourceIdentifiers: Vector[String],
    anchorNamespace: String,
    identityStatus: String,
    memberCount: Long,
    identity: Option[(IdentityConfidence, StoredRecordRef)],
    affiliations: Vector[Affiliation],
    affiliationsTruncated: Boolean,
    coauthors: Vector[CoauthorContext],
    coauthorsTruncated: Boolean,
    link: EntityLinkRecord
)

/** A cached link that was not used, and why (audit of the resolution). */
final case class ExcludedLink(researcherId: String, reason: String)

/** Why a resolution abstains. */
enum AbstainReason(val tag: String) {
  case NoCandidate extends AbstainReason("no-candidate")
  case MultipleCandidates extends AbstainReason("multiple-candidates")
  case TooManyCandidates extends AbstainReason("too-many-candidates")
  case InstitutionMatchesNone extends AbstainReason("institution-matches-no-candidate")
  case InstitutionMatchesSeveral extends AbstainReason("institution-matches-several-candidates")
  case AffiliationsTruncated extends AbstainReason("affiliations-truncated")
}

/** How a single candidate was reached. */
enum ResolutionBasis(val tag: String) {
  case SingleCandidate extends ResolutionBasis("single-candidate")
  case InstitutionHint extends ResolutionBasis("institution-hint")
}

/**
 * The outcome of resolving one name. Only `Resolved` names exactly one researcher; every other case
 * abstains and keeps all remaining candidates for the caller (README: never a silent choice).
 */
enum Resolution {
  case Resolved(
      name: String,
      candidate: ResearcherCandidate,
      basis: ResolutionBasis,
      others: Vector[ResearcherCandidate],
      excluded: Vector[ExcludedLink]
  )
  case Abstained(
      name: String,
      reason: AbstainReason,
      candidates: Vector[ResearcherCandidate],
      excluded: Vector[ExcludedLink]
  )

  def abstains: Boolean = this match {
    case _: Resolved => false
    case _: Abstained => true
  }

  /**
   * RE-P9-04 (M4): true when the name has more cached links than `maxCandidates`, so the candidate
   * list holds only the first `maxCandidates` of them (bounded) and more exist.
   */
  def candidatesTruncated: Boolean = this match {
    case Abstained(_, AbstainReason.TooManyCandidates, _, _) => true
    case _ => false
  }
}

/**
 * RE-P9-01 stage 2, ResearcherResolverAgent. Resolves a name to canonical researcher IDs from the
 * entity-resolution outputs on the runtime path only:
 *
 *   1. the name's surface-form hash ([[SurfaceForms]]) is looked up in EntityLinkCache (EL-1, a
 *      Query bounded at `maxCandidates + 1`; more candidates abstain with `too-many-candidates`,
 *      returning the first `maxCandidates` candidates with context, marked truncated);
 *   2. a link is excluded (and reported) when it has expired, belongs to another analysis run than
 *      the served one, or names a researcher absent from the graph;
 *   3. each remaining candidate gets its graph identity (ResearcherById), affiliations
 *      (ResearcherInstitutions) and coauthors in the window (DirectCollaborators), each bounded by
 *      `maxContextRows`, and its identity confidence from its metrics row;
 *   4. exactly one candidate resolves; an institution hint (from the question) may narrow several
 *      candidates to exactly one whose stored affiliation name equals or contains it (normalized);
 *      anything else abstains with every remaining candidate.
 */
final class ResearcherResolverAgent(
    graph: GraphReader,
    kv: KeyValueReader,
    serving: ServingContext,
    limits: AgentLimits,
    clock: AgentClock
) {

  def resolve(
      name: String,
      window: TimeWindow,
      institutionHint: Option[String]
  ): Either[AgentError, Resolution] = {
    val budget = new CallBudget(limits, clock)
    val rows = new AnalysisRows(kv, serving, budget)
    if (name == null || SurfaceForms.normalize(name).isEmpty)
      Left(AgentError.Refused("name: empty"))
    else
      for {
        _ <- windowKey(window)
        partition <- TableKeys.EntityLinkCache
          .partition(SurfaceForms.sha256(name))
          .left
          .map(e => AgentError.Refused(e.message))
        items <- budget.keyValue("entity links")(
          kv.query(
            TableRole.EntityLinkCache,
            KeyQuery(None, partition, SortKeyCondition.All, limits.maxCandidates + 1)
          )
        )
        links <- items
          .foldLeft[Either[AgentError, Vector[scholarchain.storage.model.EntityLinkRecord]]](
            Right(Vector.empty)
          ) { (acc, i) =>
            acc.flatMap(xs =>
              ItemCodecs.EntityLinkCodec.decode(i).left.map(AgentError.Store(_)).map(xs :+ _)
            )
          }
        out <-
          if (links.size > limits.maxCandidates)
            // RE-P9-04 (M4): more links than the bound. Still abstain (an unseen candidate could
            // be the one meant, so neither a single candidate nor a hint may resolve), but return
            // the first `maxCandidates` links (sort-key order) as candidates with their context,
            // marked truncated by the reason (Resolution.candidatesTruncated).
            candidates(name, window, None, links.take(limits.maxCandidates), budget, rows).map {
              r =>
                val (cs, ex) = r match {
                  case Resolution.Resolved(_, c, _, others, ex) => (c +: others, ex)
                  case Resolution.Abstained(_, _, cs, ex) => (cs, ex)
                }
                Resolution.Abstained(name, AbstainReason.TooManyCandidates, cs, ex)
            }
          else candidates(name, window, institutionHint, links, budget, rows)
      } yield out
  }

  private def candidates(
      name: String,
      window: TimeWindow,
      hint: Option[String],
      links: Vector[scholarchain.storage.model.EntityLinkRecord],
      budget: CallBudget,
      rows: AnalysisRows
  ): Either[AgentError, Resolution] = {
    val now = clock.epochSeconds()
    val (expired, live) = links.partition(_.expiresAtEpochSeconds <= now)
    val (otherRun, served) = live.partition(_.sourceRunId != serving.analysisRunId)
    val excluded0 =
      expired.map(l => ExcludedLink(l.researcherId, "expired")) ++
        otherRun.map(l => ExcludedLink(l.researcherId, s"link of run ${l.sourceRunId}"))
    val ordered = served
      .groupBy(_.researcherId)
      .values
      .map(_.minBy(_.candidateRank))
      .toVector
      .sortWith((a, b) =>
        if (a.candidateRank != b.candidateRank) a.candidateRank < b.candidateRank
        else Canonical.compareUtf8(a.researcherId, b.researcherId) < 0
      )
    ordered
      .foldLeft[Either[AgentError, (Vector[ResearcherCandidate], Vector[ExcludedLink])]](
        Right((Vector.empty, excluded0))
      ) { case (acc, l) =>
        acc.flatMap { case (cs, ex) =>
          candidate(l, window, budget, rows).map {
            case Some(c) => (cs :+ c, ex)
            case None => (cs, ex :+ ExcludedLink(l.researcherId, "not a researcher in the graph"))
          }
        }
      }
      .map { case (cs, ex) => decide(name, hint, cs, ex) }
  }

  private def decide(
      name: String,
      hint: Option[String],
      cs: Vector[ResearcherCandidate],
      ex: Vector[ExcludedLink]
  ): Resolution =
    (cs, hint.map(SurfaceForms.normalize).filter(_.nonEmpty)) match {
      case (Vector(), _) => Resolution.Abstained(name, AbstainReason.NoCandidate, cs, ex)
      case (Vector(one), None) =>
        Resolution.Resolved(name, one, ResolutionBasis.SingleCandidate, Vector.empty, ex)
      case (many, None) => Resolution.Abstained(name, AbstainReason.MultipleCandidates, many, ex)
      case (many, Some(h)) =>
        // An institution named in the question must match a stored affiliation, even for a single
        // candidate; a non-matching candidate whose affiliation list was cut by the bound might
        // still match, so the hint then cannot exclude it (abstain).
        val (matching, rest) = many.partition(
          _.affiliations.exists(a => SurfaceForms.normalize(a.name).contains(h))
        )
        matching match {
          case Vector(_) if rest.exists(_.affiliationsTruncated) =>
            Resolution.Abstained(name, AbstainReason.AffiliationsTruncated, many, ex)
          case Vector(one) =>
            Resolution.Resolved(name, one, ResolutionBasis.InstitutionHint, rest, ex)
          case Vector() =>
            Resolution.Abstained(name, AbstainReason.InstitutionMatchesNone, many, ex)
          case _ =>
            Resolution.Abstained(name, AbstainReason.InstitutionMatchesSeveral, many, ex)
        }
    }

  private def candidate(
      l: scholarchain.storage.model.EntityLinkRecord,
      window: TimeWindow,
      budget: CallBudget,
      rows: AnalysisRows
  ): Either[AgentError, Option[ResearcherCandidate]] = {
    val rid = l.researcherId
    val ctx = limits.maxContextRows
    for {
      _ <- requireId(IdKind.Researcher, rid, "researcherId")
      q <- Bind(GraphQuery.ResearcherById, limits, "researcherId" -> Bind.id(rid))
      found <- budget.graph(graph, q)
      out <- found match {
        case Vector() => Right(None)
        case Vector(r) =>
          val Q = GraphQuery.ResearcherById
          for {
            id <- Cols.str(r, "researcherId", Q)
            _ <- if (id == rid) Right(()) else Left(AgentError.Corrupt(s"$Q returned $id for $rid"))
            anchor <- Cols.str(r, "anchorNamespace", Q)
            status <- Cols.str(r, "identityStatus", Q)
            members <- Cols.long(r, "memberCount", Q)
            iq <- Bind(
              GraphQuery.ResearcherInstitutions,
              limits,
              "researcherId" -> Bind.id(rid),
              "limit" -> Bind.int(ctx)
            )
            irows <- budget.graph(graph, iq)
            affs <- Cols.all(irows)(x =>
              for {
                i <- Cols.str(x, "institutionId", GraphQuery.ResearcherInstitutions)
                n <- Cols.str(x, "name", GraphQuery.ResearcherInstitutions)
              } yield Affiliation(i, n)
            )
            cq <- Bind(
              GraphQuery.DirectCollaborators,
              limits,
              "researcherId" -> Bind.id(rid),
              "windowStartYear" -> Bind.int(window.startYear),
              "windowEndYear" -> Bind.int(window.endYear),
              "limit" -> Bind.int(ctx)
            )
            crows <- budget.graph(graph, cq)
            co <- Cols.all(crows)(x =>
              for {
                c <- Cols.str(x, "collaboratorId", GraphQuery.DirectCollaborators)
                n <- Cols.long(x, "jointPublicationCount", GraphQuery.DirectCollaborators)
              } yield CoauthorContext(c, n)
            )
            m <- rows.subject[ResearcherMetricsV2](
              rid,
              AnalysisRoles.ResearcherMetrics,
              window,
              SchemaVersions.ResearcherMetricsV2,
              _.researcherId,
              _.window
            )
          } yield Some(
            ResearcherCandidate(
              rid,
              l.matchedBy,
              l.candidateRank,
              l.sourceRunId,
              sourceIdentifiers(rid, anchor),
              anchor,
              status,
              members,
              m.map { case (rec, ref) => (rec.identity, ref) },
              affs,
              irows.size >= ctx,
              co,
              crows.size >= ctx,
              l
            )
          )
        case _ => Left(AgentError.Corrupt(s"ResearcherById returned several rows for $rid"))
      }
    } yield out
  }

  private def sourceIdentifiers(rid: String, anchor: String): Vector[String] = {
    val orcid = "researcher:orcid:"
    (if (rid.startsWith(orcid)) Vector("orcid:" + rid.stripPrefix(orcid)) else Vector.empty) :+
      s"anchor-namespace:$anchor"
  }
}
