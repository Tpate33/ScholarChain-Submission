package scholarchain.rag.agents

import scholarchain.core.{
  BridgeMeasure,
  CoauthorEdgeV2,
  CommunityMembership,
  ComponentMembership,
  Coreness as CorenessRecord,
  EvidenceBundle,
  EvidenceItem,
  EvidenceType,
  Json,
  ResearcherMetricsV2,
  SchemaVersions,
  TimeWindow
}
import scholarchain.rag.semantic.SemanticContextItem
import scholarchain.storage.model.EntityLinkRecord

/**
 * One checkable fact read from one evidence item: `subject` `field` = `value`. `deterministic` is
 * false exactly for semantic context (`text-chunk`) items: such a fact is descriptive (a document
 * exists for a subject, a work title) and is NEVER accepted as support for a count, path, hop,
 * component, community or bridge value.
 */
final case class Fact(
    evidenceId: String,
    subject: String,
    field: String,
    value: String,
    deterministic: Boolean
)

/**
 * The closed field vocabulary of facts and claim assertions (the `field` of the output schema
 * `prompts/schemas/answer-synthesis-output.v3.schema.json`, unchanged since v2).
 */
object FactFields {
  val ResolvedFromName = "resolved-from-name"
  val IdentityStatus = "identity-status"
  val PublicationCount = "publication-count"
  val DistinctCoauthorCount = "distinct-coauthor-count"
  val TotalCoauthorOccurrences = "total-coauthor-occurrences"
  val AverageCoauthorsPerPaper = "average-coauthors-per-paper"
  val MaximumCoauthorsOnOnePaper = "maximum-coauthors-on-one-paper"
  val WeightedDegree = "weighted-degree"
  val ComponentId = "component-id"
  val ComponentSize = "component-size"
  val CommunityId = "community-id"
  val CommunitySize = "community-size"
  val BridgeScore = "bridge-score"
  val CrossCommunityDegree = "cross-community-degree"
  val ParticipationCoefficient = "participation-coefficient"
  val BridgeMeasureName = "bridge-measure"
  val Coreness = "coreness"
  val JointPublicationCount = "joint-publication-count"
  val FirstCollaborationYear = "first-collaboration-year"
  val LastCollaborationYear = "last-collaboration-year"
  val Coauthor = "coauthor"
  val ShortestPathHops = "shortest-path-hops"

  /**
   * RE-P9-04 (M1): the hop count of a connecting path found by a TRUNCATED search: an upper bound,
   * never a shortest-path value (a claim stating `shortest-path-hops` for it finds no fact).
   */
  val PathFoundHops = "path-found-hops"

  /** RE-P9-04 (M1): `true` for a path found after a neighbour list was cut. */
  val PathSearchTruncated = "path-search-truncated"
  val PathNodePrefix = "path-node-"
  val NotConnectedWithin = "not-connected-within"
  val PathInconclusive = "path-inconclusive"
  val RankPrefix = "rank-"
  val SupportsCoauthorEdge = "supports-coauthor-edge"
  val HasContextDocument = "has-context-document"
  val Title = "title"

  /** Internal: the surface-form hash of a resolved name (never asserted directly). */
  val ResolvedSurfaceSha256 = "resolved-surface-sha256"

  val fixed: Set[String] = Set(
    ResolvedFromName,
    IdentityStatus,
    PublicationCount,
    DistinctCoauthorCount,
    TotalCoauthorOccurrences,
    AverageCoauthorsPerPaper,
    MaximumCoauthorsOnOnePaper,
    WeightedDegree,
    ComponentId,
    ComponentSize,
    CommunityId,
    CommunitySize,
    BridgeScore,
    CrossCommunityDegree,
    ParticipationCoefficient,
    BridgeMeasureName,
    Coreness,
    JointPublicationCount,
    FirstCollaborationYear,
    LastCollaborationYear,
    Coauthor,
    ShortestPathHops,
    PathFoundHops,
    PathSearchTruncated,
    NotConnectedWithin,
    PathInconclusive,
    SupportsCoauthorEdge,
    HasContextDocument,
    Title
  )

  private val PathNode = "^path-node-(0|[1-9][0-9]{0,2})$".r
  private val Rank = "^rank-([a-z0-9-]+)$".r

  /** Whether `f` is in the assertion vocabulary. */
  def known(f: String): Boolean =
    f != null && (fixed.contains(f) || PathNode.matches(f) || (f match {
      case Rank(m) => scholarchain.core.CollaborationMetric.fromTag(m).isDefined
      case _ => false
    }))

  /** Fields only descriptive (semantic) evidence may support. */
  val descriptive: Set[String] = Set(HasContextDocument, Title)
}

object BundleFacts {
  import FactFields.*

  def windowText(w: TimeWindow): String = f"${w.startYear}%04d-${w.endYear}%04d"

  /** `<id>@<window>`: a windowed subject. */
  def at(id: String, window: String): String = s"$id@$window"

  /** The canonical-pair subject `<a>|<b>@<window>` (a < b by UTF-8 bytes). */
  def pair(a: String, b: String, window: String): String =
    if (scholarchain.core.Canonical.compareUtf8(a, b) <= 0) s"$a|$b@$window" else s"$b|$a@$window"

  /** The path subject `<source>|<target>@<window>` (traversal order). */
  def path(source: String, target: String, window: String): String = s"$source|$target@$window"

  private def n(v: Long): String = v.toString
  private def d(v: Double): String = java.lang.Double.toString(v)

  private def decode[A](
      it: EvidenceItem
  )(using c: scholarchain.core.RecordContract[A]): Either[String, A] =
    Json
      .decode[A](it.text.getOrElse(""))
      .left
      .map(es => s"${it.evidenceId}: text fails ${c.recordType} (${es.size} error(s))")

  private def read[A: upickle.default.Reader](it: EvidenceItem, what: String): Either[String, A] =
    scala.util
      .Try(upickle.default.read[A](it.text.getOrElse("")))
      .toOption
      .toRight(s"${it.evidenceId}: text is not a $what")

  /** The evidence IDs a runtime record cites (its hops or ranked entries). */
  def citedIds(it: EvidenceItem): Either[String, Vector[String]] =
    it.provenance.sourceSchemaVersion match {
      case EvidenceSchemas.PathTraversal =>
        read[PathTraversalEvidence](it, "path traversal").map(_.hopEvidenceIds)
      case EvidenceSchemas.StoredRanking =>
        read[RankingEvidence](it, "stored ranking").map(_.entries.flatMap(_.evidenceIds))
      case _ => Right(Vector.empty)
    }

  /** Every fact of one item; an item whose text does not decode is an error (fail closed). */
  def ofItem(it: EvidenceItem): Either[String, Vector[Fact]] = {
    val id = it.evidenceId
    def f(s: String, field: String, v: String) =
      Fact(id, s, field, v, it.evidenceType != EvidenceType.TextChunk)
    val S = SchemaVersions
    it.provenance.sourceSchemaVersion match {
      case S.ResearcherMetricsV2 =>
        decode[ResearcherMetricsV2](it).map { r =>
          val s = at(r.researcherId, windowText(r.window))
          Vector(
            f(s, PublicationCount, n(r.publicationCount)),
            f(s, DistinctCoauthorCount, n(r.distinctCoauthorCount)),
            f(s, TotalCoauthorOccurrences, n(r.totalCoauthorOccurrences)),
            f(s, AverageCoauthorsPerPaper, d(r.averageCoauthorsPerPaper)),
            f(s, MaximumCoauthorsOnOnePaper, n(r.maximumCoauthorsOnOnePaper)),
            f(s, WeightedDegree, n(r.weightedDegree)),
            f(s, IdentityStatus, r.identity.status.tag)
          )
        }
      case S.ComponentMembership =>
        decode[ComponentMembership](it).map { r =>
          val s = at(r.researcherId, windowText(r.window))
          Vector(f(s, ComponentId, r.componentId), f(s, ComponentSize, n(r.componentSize)))
        }
      case S.CommunityMembership =>
        decode[CommunityMembership](it).map { r =>
          val s = at(r.researcherId, windowText(r.window))
          Vector(f(s, CommunityId, r.communityId), f(s, CommunitySize, n(r.communitySize)))
        }
      case S.BridgeMeasure =>
        decode[BridgeMeasure](it).map { r =>
          val s = at(r.researcherId, windowText(r.window))
          Vector(
            f(s, BridgeScore, d(r.score)),
            f(s, CrossCommunityDegree, n(r.crossCommunityDegree)),
            f(s, ParticipationCoefficient, d(r.participationCoefficient)),
            f(s, BridgeMeasureName, r.measure)
          )
        }
      case S.Coreness =>
        decode[CorenessRecord](it).map(r =>
          Vector(f(at(r.researcherId, windowText(r.window)), Coreness, n(r.coreness)))
        )
      case S.CoauthorEdgeV2 =>
        decode[CoauthorEdgeV2](it).map { r =>
          val w = windowText(r.window)
          val p = pair(r.researcherA, r.researcherB, w)
          Vector(
            f(p, JointPublicationCount, n(r.jointPublicationCount)),
            f(p, FirstCollaborationYear, n(r.firstCollaborationYear.toLong)),
            f(p, LastCollaborationYear, n(r.lastCollaborationYear.toLong)),
            f(at(r.researcherA, w), Coauthor, r.researcherB),
            f(at(r.researcherB, w), Coauthor, r.researcherA)
          )
        }
      case S.PathLengthEstimate => Right(Vector.empty)
      case EvidenceSchemas.EntityLink =>
        read[EntityLinkRecord](it, "entity link").map(l =>
          Vector(f(l.researcherId, ResolvedSurfaceSha256, l.surfaceSha256))
        )
      case EvidenceSchemas.PathTraversal =>
        read[PathTraversalEvidence](it, "path traversal").map { p =>
          val s = path(p.sourceId, p.targetId, p.window)
          p.outcome match {
            // A shortest-path fact only from a complete (untruncated) search (M1).
            case PathTraversalEvidence.Found if !p.searchTruncated =>
              p.hopCount.toVector.map(h => f(s, ShortestPathHops, n(h.toLong))) ++
                p.nodeIds.zipWithIndex.map { case (node, i) => f(s, s"$PathNodePrefix$i", node) }
            case PathTraversalEvidence.Found | PathTraversalEvidence.FoundNotProvenShortest =>
              p.hopCount.toVector.map(h => f(s, PathFoundHops, n(h.toLong))) ++
                p.nodeIds.zipWithIndex.map { case (node, i) =>
                  f(s, s"$PathNodePrefix$i", node)
                } :+ f(s, PathSearchTruncated, "true")
            case PathTraversalEvidence.NotConnected =>
              Vector(f(s, NotConnectedWithin, n(p.maxDepth.toLong)))
            case _ => Vector(f(s, PathInconclusive, "true"))
          }
        }
      case EvidenceSchemas.StoredRanking =>
        read[RankingEvidence](it, "stored ranking").map(r =>
          r.entries.map(e =>
            f(at(e.subjectId, r.window), s"$RankPrefix${r.metric}", n(e.rank.toLong))
          )
        )
      case EvidenceSchemas.PublicationSupport =>
        read[PublicationSupportEvidence](it, "publication support").map(p =>
          Vector(f(p.workId, SupportsCoauthorEdge, p.coauthorEdgeId))
        )
      case EvidenceSchemas.SemanticItem =>
        read[SemanticContextItem](it, "semantic item").map { si =>
          f(si.subjectId, HasContextDocument, si.documentType) +: titles(si.excerpt).map(t =>
            f(si.subjectId, Title, t)
          )
        }
      case other => Left(s"$id: unknown evidence text schema $other")
    }
  }

  /** `- "title": "<value>"` lines of an excerpt (JSON string literals; nothing else is read). */
  private def titles(excerpt: String): Vector[String] =
    excerpt.split("\n").toVector.filter(_.startsWith("- \"title\": \"")).flatMap { l =>
      scala.util
        .Try(ujson.read(l.stripPrefix("- \"title\": ")).str)
        .toOption
        .filter(_ != scholarchain.rag.semantic.SemanticContext.Withheld)
    }

  def of(bundle: EvidenceBundle): Either[String, Vector[Fact]] =
    bundle.items.foldLeft[Either[String, Vector[Fact]]](Right(Vector.empty))((acc, it) =>
      acc.flatMap(fs => ofItem(it).map(fs ++ _))
    )
}
