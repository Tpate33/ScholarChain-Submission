package scholarchain.rag.agents

import scholarchain.core.{IdKind, StableId, TimeWindow}
import scholarchain.rag.semantic.{SemanticContext, SemanticContextBudget, SemanticRetrieval}
import scholarchain.storage.model.{MetadataField, VectorFilter, VectorIndexSpec, VectorIndexes}
import scholarchain.storage.port.{Embedder, RuntimeStores}

/** One configured vector index and its dimension (configuration; never hardcoded). */
final case class SemanticIndex(spec: VectorIndexSpec, dimension: Int)

/**
 * Configuration of stage 6 (role law 7: every bound is configuration, no default).
 *
 * @param indexes
 *   the configured indexes; an index the plan would use but that is not configured is a reported
 *   gap, never a silent skip
 * @param textBucket
 *   the ordinary-S3 bucket of the context texts
 * @param maxRetrievals
 *   index queries one question may issue (at most five: one per index)
 */
final case class SemanticConfig private (
    embedder: Embedder,
    indexes: Vector[SemanticIndex],
    textBucket: String,
    budget: SemanticContextBudget,
    maxRetrievals: Int
)

object SemanticConfig {
  def of(
      embedder: Embedder,
      indexes: Vector[SemanticIndex],
      textBucket: String,
      budget: SemanticContextBudget,
      maxRetrievals: Int
  ): Either[String, SemanticConfig] =
    if (embedder == null || budget == null) Left("embedder and budget are required")
    else if (indexes == null || indexes.exists(i => i == null || i.spec == null))
      Left("indexes: missing")
    else if (indexes.exists(i => VectorIndexes.byName(i.spec.name).isEmpty))
      Left("indexes: only the five storage indexes may be queried")
    else if (indexes.map(_.spec.name).distinct.size != indexes.size)
      Left("indexes: an index is configured twice")
    else if (indexes.exists(_.dimension < 1)) Left("indexes: dimension must be >= 1")
    else if (textBucket == null || textBucket.isEmpty) Left("textBucket: missing")
    else if (maxRetrievals < 1 || maxRetrievals > VectorIndexes.all.size)
      Left(s"maxRetrievals: must be 1..${VectorIndexes.all.size}")
    else Right(new SemanticConfig(embedder, indexes, textBucket, budget, maxRetrievals))
}

/**
 * What the plan asks of semantic context: only IDs and flags, all from deterministic stages (never
 * from retrieved text). `query` is the user's question, used ONLY as the embedding input.
 */
final case class SemanticRequest(
    query: String,
    window: TimeWindow,
    researcherIds: Vector[String],
    communityIds: Vector[String],
    paths: Boolean,
    topics: Boolean
)

/** One index retrieval of stage 6. */
final case class SemanticRetrievalResult(
    index: String,
    filter: Option[VectorFilter],
    context: SemanticContext
)

/**
 * The semantic context of one question: per index, the Phase 8 [[SemanticContext]] (scores and
 * ordinary-S3 pointers, `notice` fixed), plus gaps (an index the plan wanted but not configured).
 * DESCRIPTIVE ONLY: nothing here is evidence of a coauthorship edge, a count, a path, a community
 * or a bridge score.
 */
final case class SemanticResult(retrievals: Vector[SemanticRetrievalResult], gaps: Vector[String])

/**
 * RE-P9-02 stage 6, SemanticContextAgent. Queries the five Phase 8 indexes through the model-scoped
 * query path only ([[SemanticRetrieval.retrieve]] uses `RuntimeStores.vectorsFor(modelVersion)`),
 * with metadata filters built from typed IDs and the plan window:
 *
 *   - `researcher-profile-v1`: researchers resolved by stage 2 (`researcherIds` in, window equal);
 *   - `collaboration-community-v1`: communities read by stage 5 (`communityId` in, window equal);
 *   - `collaboration-path-v1`: when the plan asks for a path (window equal);
 *   - `paper-abstract-v1`: when the plan asks for an explanation or a topic (`researcherIds` in
 *     when researchers are resolved);
 *   - `topic-institution-summary-v1`: when the plan names a topic or institution filter.
 *
 * Every retrieval is the Phase 8 fail-closed retrieval (text pointer and hash checked, templated
 * documents validated, fact values withheld except the allowlisted descriptive ones).
 */
final class SemanticContextAgent(runtime: RuntimeStores, config: SemanticConfig) {
  import MetadataField.*

  private def windowFilters(w: TimeWindow): Vector[VectorFilter] =
    Vector(
      VectorFilter.Range(WindowStart, w.startYear.toLong, w.startYear.toLong),
      VectorFilter.Range(WindowEnd, w.endYear.toLong, w.endYear.toLong)
    )

  private def and(fs: Vector[VectorFilter]): Option[VectorFilter] =
    fs match {
      case Vector() => None
      case Vector(one) => Some(one)
      case many => Some(VectorFilter.And(many))
    }

  /** (index spec, filter) wanted by the request, in the storage index order. */
  def wanted(r: SemanticRequest): Vector[(VectorIndexSpec, Option[VectorFilter])] = {
    val ids = r.researcherIds.distinct
    val communities = r.communityIds.distinct
    val V = VectorIndexes
    Vector(
      Option.when(ids.nonEmpty)(
        V.ResearcherProfile -> and(VectorFilter.In(ResearcherIds, ids) +: windowFilters(r.window))
      ),
      Option.when(communities.nonEmpty)(
        V.CollaborationCommunity ->
          and(VectorFilter.In(CommunityId, communities) +: windowFilters(r.window))
      ),
      Option.when(r.topics || (ids.isEmpty && !r.paths && communities.isEmpty))(
        V.PaperAbstract -> and(
          Option.when(ids.nonEmpty)(VectorFilter.In(ResearcherIds, ids)).toVector
        )
      ),
      Option.when(r.paths)(V.CollaborationPath -> and(windowFilters(r.window))),
      Option.when(r.topics)(V.TopicInstitutionSummary -> None)
    ).flatten
  }

  def retrieve(r: SemanticRequest): Either[AgentError, SemanticResult] =
    if (r == null || r.window == null || r.researcherIds == null || r.communityIds == null)
      Left(AgentError.Refused("semantic request: missing"))
    else if (r.query == null || r.query.trim.isEmpty)
      Left(AgentError.Refused("semantic request: empty query"))
    else
      (r.researcherIds.map(StableId.validate(IdKind.Researcher, _, "researcherIds")) ++
        r.communityIds.map(StableId.validate(IdKind.Community, _, "communityIds")))
        .collectFirst { case Left(e) => e } match {
        case Some(e) => Left(AgentError.Refused(e.message))
        case None =>
          val plan = wanted(r)
          val (configured, missing) =
            plan.partition { case (spec, _) => config.indexes.exists(_.spec.name == spec.name) }
          val gaps0 = missing.map { case (spec, _) => s"index ${spec.name} is not configured" }
          val run = configured.take(config.maxRetrievals)
          val gaps = gaps0 ++ configured.drop(config.maxRetrievals).map { case (spec, _) =>
            s"index ${spec.name} skipped: maxRetrievals ${config.maxRetrievals} reached"
          }
          run
            .foldLeft[Either[AgentError, Vector[SemanticRetrievalResult]]](Right(Vector.empty)) {
              case (acc, (spec, filter)) =>
                acc.flatMap { xs =>
                  val ix = config.indexes.find(_.spec.name == spec.name).get
                  SemanticRetrieval
                    .retrieve(
                      runtime,
                      config.embedder,
                      ix.spec,
                      ix.dimension,
                      config.textBucket,
                      r.query,
                      filter,
                      config.budget
                    )
                    .left
                    .map(AgentError.Store(_))
                    .map(c => xs :+ SemanticRetrievalResult(spec.name, filter, c))
                }
            }
            .map(SemanticResult(_, gaps))
      }
}
