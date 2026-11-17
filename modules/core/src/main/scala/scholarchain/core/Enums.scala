package scholarchain.core

import upickle.default.{readwriter, ReadWriter}

/**
 * JSON codec for enums: every case is written as its explicit, frozen string tag. Ordinals and
 * Scala case names never appear on the wire. Unknown tags are rejected when decoding.
 */
object EnumCodec {
  def tagged[E](enumName: String, all: Seq[E], tagOf: E => String): ReadWriter[E] = {
    val byTag: Map[String, E] = all.map(e => tagOf(e) -> e).toMap
    require(byTag.size == all.size, s"duplicate tags in $enumName")
    readwriter[String].bimap[E](
      tagOf,
      s => byTag.getOrElse(s, throw new IllegalArgumentException(s"unknown $enumName tag '$s'"))
    )
  }
}

/**
 * Source of a record. The four dataset values are exactly the datasets selected in
 * `datasets/dataset-manifest.yaml` (Phase 3). The two non-dataset values mark records produced by
 * ScholarChain itself: pipeline outputs (`scholarchain-derived`) and runtime query artifacts
 * (`scholarchain-runtime`). Adding a dataset requires a manifest entry and a Provenance schema
 * version bump.
 */
enum SourceType(val tag: String, val isDataset: Boolean) {
  case Dblp extends SourceType("dblp", true)
  case OpenAlex extends SourceType("openalex", true)
  case Orcid extends SourceType("orcid", true)
  case Crossref extends SourceType("crossref", true)
  case Derived extends SourceType("scholarchain-derived", false)
  case QueryRuntime extends SourceType("scholarchain-runtime", false)
}
object SourceType {
  def fromTag(tag: String): Option[SourceType] = values.find(_.tag == tag)
  given ReadWriter[SourceType] = EnumCodec.tagged("SourceType", values.toSeq, _.tag)
}

/** Entity-link decision outcomes. Tags are the specification's literal names. */
enum MatchOutcome(val tag: String) {
  case Accepted extends MatchOutcome("ACCEPTED")
  case Rejected extends MatchOutcome("REJECTED")
  case Unresolved extends MatchOutcome("UNRESOLVED")
  case Conflict extends MatchOutcome("CONFLICT")
}
object MatchOutcome {
  def fromTag(tag: String): Option[MatchOutcome] = values.find(_.tag == tag)
  given ReadWriter[MatchOutcome] = EnumCodec.tagged("MatchOutcome", values.toSeq, _.tag)
}

/**
 * The level of the entity-resolution evidence hierarchy that produced a [[MatchDecision]] (README
 * Phase 5: metrics are reported by match path). Closed: adding a path requires a MatchDecision
 * schema version bump. Named `MatchDecisionPath` (not `MatchPath`) so that it never shadows or
 * collides with a module-local type of that name.
 */
enum MatchDecisionPath(val tag: String) {

  /** An exact identifier agreement or disagreement decided the pair. */
  case ExactId extends MatchDecisionPath("exact-id")

  /** Exact normalized bibliographic rules decided the pair (no score needed). */
  case DeterministicBibliographic extends MatchDecisionPath("deterministic-bibliographic")

  /** The blocked probabilistic score decided the pair against the configured thresholds. */
  case Probabilistic extends MatchDecisionPath("probabilistic")

  /** A validated LLM adjudication of a pair in the bounded unresolved score band. */
  case LlmAdjudicated extends MatchDecisionPath("llm-adjudicated")
}
object MatchDecisionPath {
  def fromTag(tag: String): Option[MatchDecisionPath] = values.find(_.tag == tag)
  given ReadWriter[MatchDecisionPath] =
    EnumCodec.tagged("MatchDecisionPath", values.toSeq, _.tag)
}

/**
 * The decision of a validated LLM adjudication response, and the [[MatchOutcome]] it implies. The
 * LLM may abstain; an abstention leaves the pair UNRESOLVED.
 */
enum AdjudicationVerdict(val tag: String, val outcome: MatchOutcome) {
  case Accept extends AdjudicationVerdict("accept", MatchOutcome.Accepted)
  case Reject extends AdjudicationVerdict("reject", MatchOutcome.Rejected)
  case Abstain extends AdjudicationVerdict("abstain", MatchOutcome.Unresolved)
}
object AdjudicationVerdict {
  def fromTag(tag: String): Option[AdjudicationVerdict] = values.find(_.tag == tag)
  given ReadWriter[AdjudicationVerdict] =
    EnumCodec.tagged("AdjudicationVerdict", values.toSeq, _.tag)
}

/** Kind of an [[IdentifierConflict]] between the two sides of a match decision. */
enum IdentifierConflictKind(val tag: String) {

  /** Both sides assert the scheme and their canonical value sets are disjoint. */
  case Disagreement extends IdentifierConflictKind("identifier-disagreement")

  /** Both sides share a value of the scheme, but a named policy rule contradicts that agreement. */
  case ContradictedAgreement extends IdentifierConflictKind("agreement-contradicted")
}
object IdentifierConflictKind {
  def fromTag(tag: String): Option[IdentifierConflictKind] = values.find(_.tag == tag)
  given ReadWriter[IdentifierConflictKind] =
    EnumCodec.tagged("IdentifierConflictKind", values.toSeq, _.tag)
}

/** Kind of entity a MatchDecision links. */
enum MatchEntityKind(val tag: String) {
  case Work extends MatchEntityKind("work")
  case Researcher extends MatchEntityKind("researcher")
  case Venue extends MatchEntityKind("venue")
  case Institution extends MatchEntityKind("institution")
  case Topic extends MatchEntityKind("topic")
}
object MatchEntityKind {
  def fromTag(tag: String): Option[MatchEntityKind] = values.find(_.tag == tag)
  given ReadWriter[MatchEntityKind] = EnumCodec.tagged("MatchEntityKind", values.toSeq, _.tag)
}

/** Kind of an evidence item in an evidence bundle. */
enum EvidenceType(val tag: String) {
  case SourceRecord extends EvidenceType("source-record")
  case CoauthorEdge extends EvidenceType("coauthor-edge")
  case GraphPath extends EvidenceType("graph-path")
  case Metric extends EvidenceType("metric")
  case TextChunk extends EvidenceType("text-chunk")
  case MatchDecision extends EvidenceType("match-decision")
}
object EvidenceType {
  def fromTag(tag: String): Option[EvidenceType] = values.find(_.tag == tag)
  given ReadWriter[EvidenceType] = EnumCodec.tagged("EvidenceType", values.toSeq, _.tag)
}

/**
 * Collaboration metrics that a query may request. The first six are the specification's
 * per-researcher quantities P_r, C_r, T_r, A_r, M_r and W_r, kept separate by design.
 */
enum CollaborationMetric(val tag: String) {
  case PublicationCount extends CollaborationMetric("publication-count")
  case DistinctCoauthorCount extends CollaborationMetric("distinct-coauthor-count")
  case TotalCoauthorOccurrences extends CollaborationMetric("total-coauthor-occurrences")
  case AverageCoauthorsPerPaper extends CollaborationMetric("average-coauthors-per-paper")
  case MaximumCoauthorsOnOnePaper extends CollaborationMetric("maximum-coauthors-on-one-paper")
  case WeightedDegree extends CollaborationMetric("weighted-degree")
  case JointPublicationCount extends CollaborationMetric("joint-publication-count")
  case ShortestPathLength extends CollaborationMetric("shortest-path-length")
  case ComponentId extends CollaborationMetric("component-id")
  case ComponentSize extends CollaborationMetric("component-size")
  case CommunityId extends CollaborationMetric("community-id")
  case BridgeScore extends CollaborationMetric("bridge-score")
  case CrossCommunityDegree extends CollaborationMetric("cross-community-degree")
  case ParticipationCoefficient extends CollaborationMetric("participation-coefficient")
  case Coreness extends CollaborationMetric("coreness")
}
object CollaborationMetric {
  def fromTag(tag: String): Option[CollaborationMetric] = values.find(_.tag == tag)
  given ReadWriter[CollaborationMetric] =
    EnumCodec.tagged("CollaborationMetric", values.toSeq, _.tag)
}

/** Operations of a runtime query plan. */
enum QueryOperation(val tag: String) {
  case ResolveResearcher extends QueryOperation("resolve-researcher")
  case RankResearchers extends QueryOperation("rank-researchers")
  case GetMetrics extends QueryOperation("get-metrics")
  case DirectCollaborators extends QueryOperation("direct-collaborators")
  case ShortestPath extends QueryOperation("shortest-path")
  case Component extends QueryOperation("component")
  case Community extends QueryOperation("community")
  case BridgeResearchers extends QueryOperation("bridge-researchers")
  case KCore extends QueryOperation("k-core")
  case TeamSize extends QueryOperation("team-size")
  case TemporalTrend extends QueryOperation("temporal-trend")
  case SemanticContext extends QueryOperation("semantic-context")
  case VerifyClaims extends QueryOperation("verify-claims")
}
object QueryOperation {
  def fromTag(tag: String): Option[QueryOperation] = values.find(_.tag == tag)
  given ReadWriter[QueryOperation] = EnumCodec.tagged("QueryOperation", values.toSeq, _.tag)
}

/**
 * Verification status of an answer claim. A claim without resolvable evidence must be weakened or
 * labelled unsupported (CLAUDE.md, provenance invariant).
 */
enum VerificationStatus(val tag: String, val requiresEvidence: Boolean) {
  case Supported extends VerificationStatus("supported", true)
  case Weakened extends VerificationStatus("weakened", true)
  case Unsupported extends VerificationStatus("unsupported", false)
  case Contradicted extends VerificationStatus("contradicted", true)
}
object VerificationStatus {
  def fromTag(tag: String): Option[VerificationStatus] = values.find(_.tag == tag)
  given ReadWriter[VerificationStatus] =
    EnumCodec.tagged("VerificationStatus", values.toSeq, _.tag)
}

/** Graph node classes (specification "Research graph model"); tags are the literal names. */
enum NodeLabel(val tag: String) {
  case Researcher extends NodeLabel("Researcher")
  case AuthorMention extends NodeLabel("AuthorMention")
  case Work extends NodeLabel("Work")
  case Venue extends NodeLabel("Venue")
  case Institution extends NodeLabel("Institution")
  case Topic extends NodeLabel("Topic")
  case TextChunk extends NodeLabel("TextChunk")
  case DatasetSnapshot extends NodeLabel("DatasetSnapshot")
}
object NodeLabel {
  def fromTag(tag: String): Option[NodeLabel] = values.find(_.tag == tag)
  given ReadWriter[NodeLabel] = EnumCodec.tagged("NodeLabel", values.toSeq, _.tag)
}

/**
 * Graph edge classes (specification "Research graph model"). `COAUTHORED_WITH` is the only
 * undirected class; its stored endpoints are always in canonical order.
 */
enum EdgeLabel(val tag: String, val undirected: Boolean) {
  case Authored extends EdgeLabel("AUTHORED", false)
  case ResolvesTo extends EdgeLabel("RESOLVES_TO", false)
  case CoauthoredWith extends EdgeLabel("COAUTHORED_WITH", true)
  case PublishedIn extends EdgeLabel("PUBLISHED_IN", false)
  case AffiliatedWith extends EdgeLabel("AFFILIATED_WITH", false)
  case HasTopic extends EdgeLabel("HAS_TOPIC", false)
  case DerivedFrom extends EdgeLabel("DERIVED_FROM", false)
  case SupportedBy extends EdgeLabel("SUPPORTED_BY", false)
}
object EdgeLabel {
  def fromTag(tag: String): Option[EdgeLabel] = values.find(_.tag == tag)
  given ReadWriter[EdgeLabel] = EnumCodec.tagged("EdgeLabel", values.toSeq, _.tag)
}

/**
 * Document type of a text chunk or generated profile (an input of the chunk ID).
 *
 * DE-P6-03 appends `work-summary` and `topic-summary` (the selected paper and topic summaries,
 * [[EntitySummaryInput]]). Appending changes no existing tag and no frozen example; the
 * `semantic-summary-input.v1` contract still refuses both (they are not analysis-window subjects).
 */
enum DocumentType(val tag: String) {
  case ResearcherProfile extends DocumentType("researcher-profile")
  case WorkAbstract extends DocumentType("work-abstract")
  case CommunitySummary extends DocumentType("community-summary")
  case PathSummary extends DocumentType("path-summary")
  case CollaborationSummary extends DocumentType("collaboration-summary")
  case WorkSummary extends DocumentType("work-summary")
  case TopicSummary extends DocumentType("topic-summary")
}
object DocumentType {
  def fromTag(tag: String): Option[DocumentType] = values.find(_.tag == tag)
  given ReadWriter[DocumentType] = EnumCodec.tagged("DocumentType", values.toSeq, _.tag)
}

/** Scheme of an external identifier attached to a researcher, venue, or institution. */
enum ExternalIdScheme(val tag: String) {
  case Orcid extends ExternalIdScheme("orcid")
  case DblpPid extends ExternalIdScheme("dblp-pid")
  case OpenAlex extends ExternalIdScheme("openalex")
  case Doi extends ExternalIdScheme("doi")
  case Ror extends ExternalIdScheme("ror")
  case Issn extends ExternalIdScheme("issn")
  case CrossrefMember extends ExternalIdScheme("crossref-member")
}
object ExternalIdScheme {
  def fromTag(tag: String): Option[ExternalIdScheme] = values.find(_.tag == tag)
  given ReadWriter[ExternalIdScheme] = EnumCodec.tagged("ExternalIdScheme", values.toSeq, _.tag)
}

/**
 * Scheme of an exact identifier: the join keys of identity-link records, exact-identifier
 * agreements of match candidates, and additional source-specific work IDs. Each scheme identifies
 * exactly one entity kind (`entityKind`). Value formats are checked by
 * [[Identifiers.validateExact]]. Separate from [[ExternalIdScheme]] so that the records already
 * frozen with that enum keep their exact wire contract.
 *
 * A scheme's format rule is never proof that the identifier exists or that it was assigned to the
 * entity that carries it: a source's assertion is evidence with provenance. What each scheme
 * identifies:
 *   - `doi`, `arxiv`, `dblp-key`, `openalex-work` (OpenAlex `W` ID), `mag`, `scopus-eid`: one work.
 *   - `orcid`, `openalex-author` (OpenAlex `A` ID, an OpenAlex author cluster, not a person
 *     asserted by the person), `dblp-pid`: one researcher.
 *   - `s2-corpus` and `s2-paper` (Phase 5): one Semantic Scholar paper record, in its two encodings
 *     (numeric corpus ID, 40-hex paper ID). They identify the same kind of thing but are distinct
 *     schemes: values of one are never compared with values of the other.
 *   - `s2-author` (Phase 5): one Semantic Scholar author record (a numeric ID, kept distinct from
 *     `s2-corpus` although both are digit strings).
 *   - `repository-url` (Phase 5): one source-code repository. Its entity kind is `work` because a
 *     repository is cited by works, but one repository can accompany several distinct works (a
 *     paper and its extended version, or many papers that use one tool), so an agreement is
 *     evidence of a shared artifact, not proof that two works are the same work.
 *
 * No selected dataset (DBLP, OpenAlex, ORCID, Crossref) is recorded as carrying the three Semantic
 * Scholar schemes or repository URLs (`datasets/schemas/identifiers.md`); their formats follow the
 * public identifier conventions documented in [[Identifiers]], not observed samples. Adding a case
 * does not change any frozen wire example of the records that carry exact identifiers.
 */
enum ExactIdScheme(val tag: String, val entityKind: MatchEntityKind) {
  case Doi extends ExactIdScheme("doi", MatchEntityKind.Work)
  case Arxiv extends ExactIdScheme("arxiv", MatchEntityKind.Work)
  case DblpKey extends ExactIdScheme("dblp-key", MatchEntityKind.Work)
  case OpenAlexWork extends ExactIdScheme("openalex-work", MatchEntityKind.Work)
  case Mag extends ExactIdScheme("mag", MatchEntityKind.Work)
  case ScopusEid extends ExactIdScheme("scopus-eid", MatchEntityKind.Work)
  case Orcid extends ExactIdScheme("orcid", MatchEntityKind.Researcher)
  case OpenAlexAuthor extends ExactIdScheme("openalex-author", MatchEntityKind.Researcher)
  case DblpPid extends ExactIdScheme("dblp-pid", MatchEntityKind.Researcher)
  case SemanticScholarCorpus extends ExactIdScheme("s2-corpus", MatchEntityKind.Work)
  case SemanticScholarPaper extends ExactIdScheme("s2-paper", MatchEntityKind.Work)
  case SemanticScholarAuthor extends ExactIdScheme("s2-author", MatchEntityKind.Researcher)
  case RepositoryUrl extends ExactIdScheme("repository-url", MatchEntityKind.Work)
}
object ExactIdScheme {
  def fromTag(tag: String): Option[ExactIdScheme] = values.find(_.tag == tag)
  given ReadWriter[ExactIdScheme] = EnumCodec.tagged("ExactIdScheme", values.toSeq, _.tag)
}

/**
 * Kind of the party that asserted an ORCID item (ORCID `common:source` and
 * `common:assertion-origin-*`): a member API client (`source-client-id`) or an ORCID iD holder
 * (`source-orcid`).
 */
enum AssertionSourceKind(val tag: String) {
  case Client extends AssertionSourceKind("client")
  case Orcid extends AssertionSourceKind("orcid")
}
object AssertionSourceKind {
  def fromTag(tag: String): Option[AssertionSourceKind] = values.find(_.tag == tag)
  given ReadWriter[AssertionSourceKind] =
    EnumCodec.tagged("AssertionSourceKind", values.toSeq, _.tag)
}
