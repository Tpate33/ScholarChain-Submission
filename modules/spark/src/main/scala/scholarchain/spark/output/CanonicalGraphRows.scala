package scholarchain.spark.output

import scala.util.control.NonFatal

import org.apache.spark.sql.Row
import org.apache.spark.sql.types.*

import scholarchain.core.*

import StructureRows.{f, parametersOf, parametersRow, ParametersType}

/**
 * Parquet row layout of `graph-node.v2` ([[GraphNodeV2]]), record field order. The label is its
 * frozen tag; the string properties are an ascending `array<struct<key,value>>` (as the structure
 * parameters, [[StructureRows]]), so the round trip is exact and the logical-reproducibility
 * comparison can group by every column.
 */
object GraphNodeRows {
  val schema: StructType = StructType(
    Seq(
      f("schemaVersion", StringType),
      f("nodeId", StringType),
      f("label", StringType),
      f("properties", ParametersType),
      f("lineage", CoauthorEdgeRows.LineageType),
      f("provenance", CoauthorEdgeRows.ProvenanceType)
    )
  )

  def toRow(n: GraphNodeV2): Row = Row(
    n.schemaVersion,
    n.nodeId,
    n.label.tag,
    parametersRow(n.properties),
    CoauthorEdgeRows.lineageRow(n.lineage),
    CoauthorEdgeRows.provenanceRow(n.provenance)
  )

  def fromRow(r: Row): Either[String, GraphNodeV2] =
    try
      Right(
        GraphNodeV2(
          r.getString(0),
          r.getString(1),
          NodeLabel
            .fromTag(r.getString(2))
            .getOrElse(throw new IllegalArgumentException(s"label '${r.getString(2)}'")),
          parametersOf(r, 3),
          CoauthorEdgeRows.lineageOf(r.getStruct(4)),
          CoauthorEdgeRows.provenanceOf(r.getStruct(5))
        )
      )
    catch { case NonFatal(e) => Left(s"row is not a graph-node.v2 record: $e") }
}

/** Parquet row layout of `graph-edge.v2` ([[GraphEdgeV2]]), record field order. */
object GraphEdgeRows {
  val schema: StructType = StructType(
    Seq(
      f("schemaVersion", StringType),
      f("edgeId", StringType),
      f("fromId", StringType),
      f("edgeType", StringType),
      f("toId", StringType),
      f("sourceEvidence", StringType),
      f("properties", ParametersType),
      f("lineage", CoauthorEdgeRows.LineageType),
      f("provenance", CoauthorEdgeRows.ProvenanceType)
    )
  )

  def toRow(e: GraphEdgeV2): Row = Row(
    e.schemaVersion,
    e.edgeId,
    e.fromId,
    e.edgeType.tag,
    e.toId,
    e.sourceEvidence,
    parametersRow(e.properties),
    CoauthorEdgeRows.lineageRow(e.lineage),
    CoauthorEdgeRows.provenanceRow(e.provenance)
  )

  def fromRow(r: Row): Either[String, GraphEdgeV2] =
    try
      Right(
        GraphEdgeV2(
          r.getString(0),
          r.getString(1),
          r.getString(2),
          EdgeLabel
            .fromTag(r.getString(3))
            .getOrElse(throw new IllegalArgumentException(s"edgeType '${r.getString(3)}'")),
          r.getString(4),
          r.getString(5),
          parametersOf(r, 6),
          CoauthorEdgeRows.lineageOf(r.getStruct(7)),
          CoauthorEdgeRows.provenanceOf(r.getStruct(8))
        )
      )
    catch { case NonFatal(e) => Left(s"row is not a graph-edge.v2 record: $e") }
}

/** Parquet row layout of `window-structure.v1` ([[WindowStructure]]), record field order. */
object WindowStructureRows {
  val schema: StructType = StructType(
    Seq(
      f("schemaVersion", StringType),
      f("window", CoauthorEdgeRows.WindowType),
      f("ordinal", IntegerType),
      f("researcherCount", LongType),
      f("edgeCount", LongType),
      f("componentCount", LongType),
      f("largestComponentSize", LongType),
      f("largestComponentFraction", DoubleType),
      f("singletonCount", LongType),
      f("communityCount", LongType),
      f("largestCommunitySize", LongType),
      f("crossCommunityEdgeCount", LongType),
      f("communityAlgorithm", StringType),
      f("communityIterations", IntegerType),
      f("communityConverged", BooleanType),
      f("maxCoreness", LongType),
      f("lineage", CoauthorEdgeRows.LineageType),
      f("provenance", CoauthorEdgeRows.ProvenanceType)
    )
  )

  def toRow(s: WindowStructure): Row = Row(
    s.schemaVersion,
    CoauthorEdgeRows.windowRow(s.window),
    s.ordinal,
    s.researcherCount,
    s.edgeCount,
    s.componentCount,
    s.largestComponentSize,
    s.largestComponentFraction,
    s.singletonCount,
    s.communityCount,
    s.largestCommunitySize,
    s.crossCommunityEdgeCount,
    s.communityAlgorithm,
    s.communityIterations,
    s.communityConverged,
    s.maxCoreness,
    CoauthorEdgeRows.lineageRow(s.lineage),
    CoauthorEdgeRows.provenanceRow(s.provenance)
  )

  def fromRow(r: Row): Either[String, WindowStructure] =
    try
      Right(
        WindowStructure(
          r.getString(0),
          CoauthorEdgeRows.windowOf(r.getStruct(1)),
          r.getInt(2),
          r.getLong(3),
          r.getLong(4),
          r.getLong(5),
          r.getLong(6),
          r.getDouble(7),
          r.getLong(8),
          r.getLong(9),
          r.getLong(10),
          r.getLong(11),
          r.getString(12),
          r.getInt(13),
          r.getBoolean(14),
          r.getLong(15),
          CoauthorEdgeRows.lineageOf(r.getStruct(16)),
          CoauthorEdgeRows.provenanceOf(r.getStruct(17))
        )
      )
    catch { case NonFatal(e) => Left(s"row is not a window-structure.v1 record: $e") }
}

/**
 * The Neptune bulk-load CSV parts of a run (core `NeptuneCsv` layout, `LoadRoles` names): one
 * dataset directory per canonical node label and edge class, named by its load role
 * (`researcher-vertices`, ..., `has-topic-edges`), each part a complete CSV file with its header.
 */
object NeptuneCsvParts {

  /** (dataset name, record schema, header line) of every node label, in `CanonicalGraph` order. */
  val vertexDatasets: Vector[(NodeLabel, String, String, String)] =
    CanonicalGraph.NodeLabels.map(l =>
      (
        l,
        LoadRoles.vertexRole(l),
        SchemaVersions.GraphNodeV2,
        NeptuneCsv.vertexColumns(l).map(NeptuneCsv.field).mkString(",")
      )
    )

  /** (dataset name, record schema, header line) of every edge class, in `CanonicalGraph` order. */
  val edgeDatasets: Vector[(EdgeLabel, String, String, String)] =
    CanonicalGraph.EdgeLabels.map(l =>
      (
        l,
        LoadRoles.edgeRole(l),
        SchemaVersions.GraphEdgeV2,
        NeptuneCsv.edgeColumns(l).map(NeptuneCsv.field).mkString(",")
      )
    )

  /** Header line of the CSV dataset `name` (a Neptune load role), when it is one. */
  def headerOf(name: String): Option[String] =
    vertexDatasets
      .collectFirst { case (_, n, _, h) if n == name => h }
      .orElse(edgeDatasets.collectFirst { case (_, n, _, h) if n == name => h })
}
