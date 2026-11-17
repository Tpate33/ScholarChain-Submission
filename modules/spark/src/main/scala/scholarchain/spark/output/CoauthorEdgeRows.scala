package scholarchain.spark.output

import scala.util.control.NonFatal

import org.apache.spark.sql.Row
import org.apache.spark.sql.types.*

import scholarchain.core.*

/**
 * Parquet row layout of `coauthor-edge.v2` ([[CoauthorEdgeV2]]): one column per record field, in
 * the record's field order, with the record's own field names (nested records as structs, vectors
 * as arrays, enums as their frozen tags, `Option` as a nullable column). [[fromRow]] returns Left
 * instead of throwing.
 */
object CoauthorEdgeRows {

  private def f(name: String, t: DataType, nullable: Boolean = false) =
    StructField(name, t, nullable)

  val WindowType: StructType =
    StructType(Seq(f("startYear", IntegerType), f("endYear", IntegerType)))

  val YearCountType: StructType = StructType(Seq(f("year", IntegerType), f("count", LongType)))

  val LineageType: StructType = StructType(
    Seq(
      "groupingRunId",
      "decisionRunId",
      "inputRunId",
      "codeVersion",
      "configDigest",
      "groupingPolicyVersion",
      "matchPolicyVersion",
      "identityVersion",
      "groupingEvaluation"
    ).map(n => f(n, StringType))
  )

  val ProvenanceType: StructType = StructType(
    Seq(
      f("sourceDataset", StringType),
      f("sourceRecordId", StringType),
      f("snapshot", StringType),
      f("runId", StringType),
      f("sourceSchemaVersion", StringType),
      f("policyVersion", StringType, nullable = true),
      f("modelVersion", StringType, nullable = true),
      f("promptVersion", StringType, nullable = true)
    )
  )

  val schema: StructType = StructType(
    Seq(
      f("schemaVersion", StringType),
      f("edgeId", StringType),
      f("researcherA", StringType),
      f("researcherB", StringType),
      f("window", WindowType),
      f("jointPublicationCount", LongType),
      f("firstCollaborationYear", IntegerType),
      f("lastCollaborationYear", IntegerType),
      f("publicationsByYear", ArrayType(YearCountType, containsNull = false)),
      f("supportingWorkIds", ArrayType(StringType, containsNull = false)),
      f("lineage", LineageType),
      f("provenance", ProvenanceType)
    )
  )

  def windowRow(w: TimeWindow): Row = Row(w.startYear, w.endYear)

  def lineageRow(l: AnalysisLineage): Row = Row(
    l.groupingRunId,
    l.decisionRunId,
    l.inputRunId,
    l.codeVersion,
    l.configDigest,
    l.groupingPolicyVersion,
    l.matchPolicyVersion,
    l.identityVersion,
    l.groupingEvaluation.tag
  )

  def provenanceRow(p: Provenance): Row = Row(
    p.sourceDataset.tag,
    p.sourceRecordId,
    p.snapshot,
    p.runId,
    p.sourceSchemaVersion,
    p.policyVersion.orNull,
    p.modelVersion.orNull,
    p.promptVersion.orNull
  )

  def toRow(e: CoauthorEdgeV2): Row = Row(
    e.schemaVersion,
    e.edgeId,
    e.researcherA,
    e.researcherB,
    windowRow(e.window),
    e.jointPublicationCount,
    e.firstCollaborationYear,
    e.lastCollaborationYear,
    e.publicationsByYear.map(y => Row(y.year, y.count)),
    e.supportingWorkIds,
    lineageRow(e.lineage),
    provenanceRow(e.provenance)
  )

  private def seq[A](r: Row, i: Int): Vector[A] = r.getSeq[A](i).toVector

  def windowOf(r: Row): TimeWindow = TimeWindow(r.getInt(0), r.getInt(1))

  def lineageOf(r: Row): AnalysisLineage = AnalysisLineage(
    r.getString(0),
    r.getString(1),
    r.getString(2),
    r.getString(3),
    r.getString(4),
    r.getString(5),
    r.getString(6),
    r.getString(7),
    GroupingEvaluation
      .fromTag(r.getString(8))
      .getOrElse(throw new IllegalArgumentException(s"groupingEvaluation '${r.getString(8)}'"))
  )

  def provenanceOf(r: Row): Provenance = Provenance(
    SourceType
      .fromTag(r.getString(0))
      .getOrElse(throw new IllegalArgumentException(s"sourceDataset '${r.getString(0)}'")),
    r.getString(1),
    r.getString(2),
    r.getString(3),
    r.getString(4),
    Option(r.getString(5)),
    Option(r.getString(6)),
    Option(r.getString(7))
  )

  /** The record of a row read with [[schema]]; Left when a value cannot be converted. */
  def fromRow(r: Row): Either[String, CoauthorEdgeV2] =
    try
      Right(
        CoauthorEdgeV2(
          r.getString(0),
          r.getString(1),
          r.getString(2),
          r.getString(3),
          windowOf(r.getStruct(4)),
          r.getLong(5),
          r.getInt(6),
          r.getInt(7),
          seq[Row](r, 8).map(y => YearCount(y.getInt(0), y.getLong(1))),
          seq[String](r, 9),
          lineageOf(r.getStruct(10)),
          provenanceOf(r.getStruct(11))
        )
      )
    catch { case NonFatal(e) => Left(s"row is not a coauthor-edge.v2 record: $e") }
}

/**
 * The Parquet layout of every record schema this module exports, keyed by the schema label a part
 * manifest records (CR-P6-15 N1: every Parquet part's StructType must match its label).
 */
object RecordSchemas {

  val parquet: Map[String, StructType] =
    Map(
      SchemaVersions.CoauthorEdgeV2 -> CoauthorEdgeRows.schema,
      SchemaVersions.ResearcherMetricsV2 -> ResearcherMetricsRows.schema,
      SchemaVersions.ComponentMembership -> ComponentMembershipRows.schema,
      SchemaVersions.CommunityMembership -> CommunityMembershipRows.schema,
      SchemaVersions.BridgeMeasure -> BridgeMeasureRows.schema,
      SchemaVersions.Coreness -> CorenessRows.schema,
      SchemaVersions.GraphNodeV2 -> GraphNodeRows.schema,
      SchemaVersions.GraphEdgeV2 -> GraphEdgeRows.schema,
      SchemaVersions.WindowStructure -> WindowStructureRows.schema,
      // SP-P6-03 step 2C
      SchemaVersions.EvidencePath -> EvidencePathRows.schema,
      SchemaVersions.AnalysisWindow -> AnalysisWindowRows.schema,
      SchemaVersions.PathLengthEstimate -> PathLengthEstimateRows.schema,
      SchemaVersions.WindowComparison -> WindowComparisonRows.schema,
      SchemaVersions.ResearcherTemporalChange -> TemporalChangeRows.schema,
      SchemaVersions.SemanticSummaryInput -> SummaryInputRows.schema,
      SchemaVersions.EntitySummaryInput -> EntitySummaryInputRows.schema
    )

  /**
   * Same field names, order and types at every level, ignoring nullability (Spark writes file
   * sources with every column nullable, so nullability does not survive a Parquet round trip).
   */
  def sameShape(a: DataType, b: DataType): Boolean = (a, b) match {
    case (x: StructType, y: StructType) =>
      x.fields.length == y.fields.length &&
      x.fields.zip(y.fields).forall { case (p, q) =>
        p.name == q.name && sameShape(p.dataType, q.dataType)
      }
    case (x: ArrayType, y: ArrayType) => sameShape(x.elementType, y.elementType)
    case (x: MapType, y: MapType) =>
      sameShape(x.keyType, y.keyType) && sameShape(x.valueType, y.valueType)
    case (x, y) => x == y
  }

  /** Why `actual` is not the layout of `label`, or None. */
  def problem(label: String, actual: StructType): Option[String] =
    parquet.get(label) match {
      case None => Some(s"no Parquet layout is defined for record schema '$label'")
      case Some(expected) if !sameShape(expected, actual) =>
        Some(
          s"StructType ${actual.simpleString} is not the layout of $label (${expected.simpleString})"
        )
      case Some(_) => None
    }
}
