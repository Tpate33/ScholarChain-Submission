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

/** Document type of a text chunk or generated profile (an input of the chunk ID). */
enum DocumentType(val tag: String) {
  case ResearcherProfile extends DocumentType("researcher-profile")
  case WorkAbstract extends DocumentType("work-abstract")
  case CommunitySummary extends DocumentType("community-summary")
  case PathSummary extends DocumentType("path-summary")
  case CollaborationSummary extends DocumentType("collaboration-summary")
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
