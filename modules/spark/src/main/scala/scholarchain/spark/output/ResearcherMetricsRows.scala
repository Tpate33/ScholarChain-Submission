package scholarchain.spark.output

import scala.util.control.NonFatal

import org.apache.spark.sql.Row
import org.apache.spark.sql.types.*

import scholarchain.core.*

/**
 * Parquet row layout of `researcher-metrics.v2` ([[ResearcherMetricsV2]]): one column per record
 * field, in the record's field order, with the record's own field names (nested records as structs,
 * vectors as arrays, enums as their frozen tags, `Option` as a nullable column). The identity
 * confidence keeps its explicit unknown: `lowestDecisionConfidence.kind` is always set, `value` is
 * null exactly for `unknown` and `unknownReason` null exactly for `measured`. The six specification
 * quantities stay separate columns. [[fromRow]] returns Left instead of throwing.
 */
object ResearcherMetricsRows {

  private def f(name: String, t: DataType, nullable: Boolean = false) =
    StructField(name, t, nullable)

  val DecisionConfidenceType: StructType = StructType(
    Seq(
      f("kind", StringType),
      f("value", DoubleType, nullable = true),
      f("unknownReason", StringType, nullable = true)
    )
  )

  val IdentityType: StructType = StructType(
    Seq(
      f("status", StringType),
      f("anchorNamespace", StringType),
      f("memberCount", IntegerType),
      f("collisionCodes", ArrayType(StringType, containsNull = false)),
      f("lowestDecisionConfidence", DecisionConfidenceType)
    )
  )

  val schema: StructType = StructType(
    Seq(
      f("schemaVersion", StringType),
      f("researcherId", StringType),
      f("window", CoauthorEdgeRows.WindowType),
      f("publicationCount", LongType),
      f("distinctCoauthorCount", LongType),
      f("totalCoauthorOccurrences", LongType),
      f("unresolvedCoauthorOccurrences", LongType),
      f("worksWithUnresolvedCoauthors", LongType),
      f("averageCoauthorsPerPaper", DoubleType),
      f("maximumCoauthorsOnOnePaper", LongType),
      f("maximumTeamWorkIds", ArrayType(StringType, containsNull = false)),
      f("weightedDegree", LongType),
      f("identity", IdentityType),
      f("lineage", CoauthorEdgeRows.LineageType),
      f("provenance", CoauthorEdgeRows.ProvenanceType)
    )
  )

  def identityRow(c: IdentityConfidence): Row = {
    val d = c.lowestDecisionConfidence
    Row(
      c.status.tag,
      c.anchorNamespace,
      c.memberCount,
      c.collisionCodes,
      Row(
        d.kind.tag,
        d.value match {
          case Some(v) => v
          case None => null
        },
        d.unknownReason.map(_.tag).orNull
      )
    )
  }

  def toRow(m: ResearcherMetricsV2): Row = Row(
    m.schemaVersion,
    m.researcherId,
    CoauthorEdgeRows.windowRow(m.window),
    m.publicationCount,
    m.distinctCoauthorCount,
    m.totalCoauthorOccurrences,
    m.unresolvedCoauthorOccurrences,
    m.worksWithUnresolvedCoauthors,
    m.averageCoauthorsPerPaper,
    m.maximumCoauthorsOnOnePaper,
    m.maximumTeamWorkIds,
    m.weightedDegree,
    identityRow(m.identity),
    CoauthorEdgeRows.lineageRow(m.lineage),
    CoauthorEdgeRows.provenanceRow(m.provenance)
  )

  private def tag[A](what: String, s: String, of: String => Option[A]): A =
    of(s).getOrElse(throw new IllegalArgumentException(s"$what '$s'"))

  def identityOf(r: Row): IdentityConfidence = {
    val d = r.getStruct(4)
    IdentityConfidence(
      tag("status", r.getString(0), IdentityStatus.fromTag),
      r.getString(1),
      r.getInt(2),
      r.getSeq[String](3).toVector,
      DecisionConfidence(
        tag("kind", d.getString(0), ConfidenceKind.fromTag),
        if (d.isNullAt(1)) None else Some(d.getDouble(1)),
        Option(d.getString(2)).map(tag("unknownReason", _, ConfidenceUnknownReason.fromTag))
      )
    )
  }

  /** The record of a row read with [[schema]]; Left when a value cannot be converted. */
  def fromRow(r: Row): Either[String, ResearcherMetricsV2] =
    try
      Right(
        ResearcherMetricsV2(
          r.getString(0),
          r.getString(1),
          CoauthorEdgeRows.windowOf(r.getStruct(2)),
          r.getLong(3),
          r.getLong(4),
          r.getLong(5),
          r.getLong(6),
          r.getLong(7),
          r.getDouble(8),
          r.getLong(9),
          r.getSeq[String](10).toVector,
          r.getLong(11),
          identityOf(r.getStruct(12)),
          CoauthorEdgeRows.lineageOf(r.getStruct(13)),
          CoauthorEdgeRows.provenanceOf(r.getStruct(14))
        )
      )
    catch { case NonFatal(e) => Left(s"row is not a researcher-metrics.v2 record: $e") }
}
