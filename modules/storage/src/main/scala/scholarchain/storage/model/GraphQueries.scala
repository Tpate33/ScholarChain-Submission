package scholarchain.storage.model

import scala.collection.immutable.SortedMap

import scholarchain.core.{IdKind, StableId}

/** Type of one openCypher template parameter. */
enum GraphParamType {

  /** A `core` stable ID of this kind (validated, so free text never reaches the graph). */
  case Id(kind: IdKind)

  /** A calendar year of the canonical graph profiles. */
  case Year

  /** A result bound, 1..maxLimit. */
  case Limit
}

final case class GraphParam(name: String, tpe: GraphParamType)

/** A parameter value supplied by the caller. */
enum GraphParamValue {
  case Str(value: String)
  case IntValue(value: Int)
}

/**
 * The closed set of runtime openCypher queries (SE-P7-01 law 3; README L1377). Texts are fixed,
 * read-only and carry no literal: every caller value is a typed `$parameter`. Property names follow
 * the canonical graph profiles (`core` CanonicalGraph); vertex and edge IDs are the Spark IDs, read
 * with `id(...)`. Neptune openCypher support of every construct used here (`id()`, `DISTINCT`,
 * `ORDER BY`, a parameterized `LIMIT`) is UNVERIFIED until the bounded integration run.
 */
enum GraphQuery(val text: String, val params: Vector[GraphParam]) {
  case ResearcherById
      extends GraphQuery(
        "MATCH (r:Researcher) WHERE id(r) = $researcherId " +
          "RETURN id(r) AS researcherId, r.anchorNamespace AS anchorNamespace, " +
          "r.identityStatus AS identityStatus, r.memberCount AS memberCount",
        Vector(GraphParam("researcherId", GraphParamType.Id(IdKind.Researcher)))
      )

  case DirectCollaborators
      extends GraphQuery(
        "MATCH (r:Researcher)-[e:COAUTHORED_WITH]-(c:Researcher) WHERE id(r) = $researcherId " +
          "AND e.windowStartYear = $windowStartYear AND e.windowEndYear = $windowEndYear " +
          "RETURN id(c) AS collaboratorId, e.jointPublicationCount AS jointPublicationCount, " +
          "e.firstCollaborationYear AS firstCollaborationYear, " +
          "e.lastCollaborationYear AS lastCollaborationYear, e.sourceEvidence AS sourceEvidence " +
          "ORDER BY jointPublicationCount DESC, collaboratorId ASC LIMIT $limit",
        Vector(
          GraphParam("researcherId", GraphParamType.Id(IdKind.Researcher)),
          GraphParam("windowStartYear", GraphParamType.Year),
          GraphParam("windowEndYear", GraphParamType.Year),
          GraphParam("limit", GraphParamType.Limit)
        )
      )

  case CoauthorEdgeBetween
      extends GraphQuery(
        "MATCH (a:Researcher)-[e:COAUTHORED_WITH]-(b:Researcher) WHERE id(a) = $researcherA " +
          "AND id(b) = $researcherB AND e.windowStartYear = $windowStartYear " +
          "AND e.windowEndYear = $windowEndYear " +
          "RETURN id(e) AS edgeId, e.jointPublicationCount AS jointPublicationCount, " +
          "e.firstCollaborationYear AS firstCollaborationYear, " +
          "e.lastCollaborationYear AS lastCollaborationYear, e.sourceEvidence AS sourceEvidence",
        Vector(
          GraphParam("researcherA", GraphParamType.Id(IdKind.Researcher)),
          GraphParam("researcherB", GraphParamType.Id(IdKind.Researcher)),
          GraphParam("windowStartYear", GraphParamType.Year),
          GraphParam("windowEndYear", GraphParamType.Year)
        )
      )

  case SharedWorks
      extends GraphQuery(
        "MATCH (a:Researcher)-[:AUTHORED]->(w:Work)<-[:AUTHORED]-(b:Researcher) " +
          "WHERE id(a) = $researcherA AND id(b) = $researcherB " +
          "RETURN DISTINCT id(w) AS workId, w.year AS year ORDER BY workId ASC LIMIT $limit",
        Vector(
          GraphParam("researcherA", GraphParamType.Id(IdKind.Researcher)),
          GraphParam("researcherB", GraphParamType.Id(IdKind.Researcher)),
          GraphParam("limit", GraphParamType.Limit)
        )
      )

  case WorksOfResearcher
      extends GraphQuery(
        "MATCH (r:Researcher)-[:AUTHORED]->(w:Work) WHERE id(r) = $researcherId " +
          "RETURN id(w) AS workId, w.year AS year ORDER BY workId ASC LIMIT $limit",
        Vector(
          GraphParam("researcherId", GraphParamType.Id(IdKind.Researcher)),
          GraphParam("limit", GraphParamType.Limit)
        )
      )

  case WorkVenue
      extends GraphQuery(
        "MATCH (w:Work)-[:PUBLISHED_IN]->(v:Venue) WHERE id(w) = $workId " +
          "RETURN id(v) AS venueId, v.name AS name",
        Vector(GraphParam("workId", GraphParamType.Id(IdKind.Work)))
      )

  case WorkTopics
      extends GraphQuery(
        "MATCH (w:Work)-[:HAS_TOPIC]->(t:Topic) WHERE id(w) = $workId " +
          "RETURN id(t) AS topicId, t.name AS name ORDER BY topicId ASC LIMIT $limit",
        Vector(
          GraphParam("workId", GraphParamType.Id(IdKind.Work)),
          GraphParam("limit", GraphParamType.Limit)
        )
      )

  case ResearcherInstitutions
      extends GraphQuery(
        "MATCH (r:Researcher)-[:AFFILIATED_WITH]->(i:Institution) WHERE id(r) = $researcherId " +
          "RETURN DISTINCT id(i) AS institutionId, i.name AS name " +
          "ORDER BY institutionId ASC LIMIT $limit",
        Vector(
          GraphParam("researcherId", GraphParamType.Id(IdKind.Researcher)),
          GraphParam("limit", GraphParamType.Limit)
        )
      )

  case MentionResolution
      extends GraphQuery(
        "MATCH (m:AuthorMention)-[e:RESOLVES_TO]->(r:Researcher) WHERE id(m) = $mentionId " +
          "RETURN id(r) AS researcherId, e.assignmentStatus AS assignmentStatus",
        Vector(GraphParam("mentionId", GraphParamType.Id(IdKind.Mention)))
      )
}

/**
 * A template with checked parameter values. The constructor is private: the only way to obtain one
 * is [[BoundQuery.bind]], and the graph reader port accepts nothing else, so no query text is ever
 * built from user input.
 */
final case class BoundQuery private (
    query: GraphQuery,
    parameters: SortedMap[String, GraphParamValue]
)

object BoundQuery {

  /**
   * Binds exactly the template's parameters: a missing, extra or wrongly typed parameter, an ID
   * that is not a valid `core` ID of the declared kind, a year outside the profile range or a limit
   * outside 1..maxLimit is `Refused`.
   */
  def bind(
      query: GraphQuery,
      args: Map[String, GraphParamValue],
      maxLimit: Int
  ): Either[StoreError, BoundQuery] =
    if (query == null || args == null) Left(StoreError.Refused("query and arguments are required"))
    else {
      val declared = query.params.map(_.name).toSet
      val missing = declared -- args.keySet
      val extra = args.keySet -- declared
      if (missing.nonEmpty || extra.nonEmpty)
        Left(
          StoreError.Refused(
            s"${query.toString}: missing ${missing.toVector.sorted.mkString(",")}; " +
              s"unexpected ${extra.toVector.sorted.mkString(",")}"
          )
        )
      else
        query.params
          .map(p => check(p, args(p.name), maxLimit))
          .collectFirst { case Left(e) => e } match {
          case Some(e) => Left(e)
          case None => Right(new BoundQuery(query, SortedMap.from(args)))
        }
    }

  private def check(p: GraphParam, v: GraphParamValue, maxLimit: Int): Either[StoreError, Unit] =
    (p.tpe, v) match {
      case (GraphParamType.Id(kind), GraphParamValue.Str(s)) =>
        StableId.validate(kind, s, p.name).left.map(e => StoreError.Refused(e.message)).map(_ => ())
      case (GraphParamType.Year, GraphParamValue.IntValue(y)) =>
        StoreIds.year(y, p.name).map(_ => ())
      case (GraphParamType.Limit, GraphParamValue.IntValue(n)) =>
        if (n >= 1 && n <= maxLimit) Right(())
        else Left(StoreError.Refused(s"${p.name}: $n is outside 1..$maxLimit"))
      case _ => Left(StoreError.Refused(s"${p.name}: wrong parameter type"))
    }
}

/** A value of one graph result column. */
enum GraphValue {
  case Str(value: String)
  case IntValue(value: Long)
  case Null
}

/** One graph result row: column name to value. */
final case class GraphRow(columns: SortedMap[String, GraphValue])
