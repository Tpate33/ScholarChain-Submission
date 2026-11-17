package scholarchain.spark.output

import scala.collection.immutable.SortedMap
import scala.util.control.NonFatal

import org.apache.spark.sql.Row
import org.apache.spark.sql.types.*

import scholarchain.core.*

/**
 * Shared parts of the structure row layouts. Algorithm parameters (a `SortedMap[String, String]` in
 * the record) are stored as `array<struct<key, value>>` in ascending key order, not as a Parquet
 * map: Spark cannot group by a map column, and the logical-reproducibility comparison groups by
 * every column. [[parametersOf]] refuses duplicate or unordered keys, so the round trip is exact.
 */
object StructureRows {

  private[output] def f(name: String, t: DataType, nullable: Boolean = false) =
    StructField(name, t, nullable)

  val ParameterType: StructType = StructType(Seq(f("key", StringType), f("value", StringType)))

  val ParametersType: ArrayType = ArrayType(ParameterType, containsNull = false)

  def parametersRow(m: SortedMap[String, String]): Vector[Row] =
    m.toVector.map { case (k, v) => Row(k, v) }

  def parametersOf(r: Row, i: Int): SortedMap[String, String] = {
    val kv = r.getSeq[Row](i).toVector.map(x => (x.getString(0), x.getString(1)))
    if (kv.sliding(2).exists(w => w.size == 2 && w(0)._1 >= w(1)._1))
      throw new IllegalArgumentException(s"parameters keys not strictly ascending: ${kv.map(_._1)}")
    SortedMap.from(kv)
  }
}

import StructureRows.{f, parametersOf, parametersRow, ParametersType}

/**
 * Parquet row layout of `component-membership.v1` ([[ComponentMembership]]), record field order.
 */
object ComponentMembershipRows {
  val schema: StructType = StructType(
    Seq(
      f("schemaVersion", StringType),
      f("researcherId", StringType),
      f("window", CoauthorEdgeRows.WindowType),
      f("componentId", StringType),
      f("componentSize", LongType),
      f("lineage", CoauthorEdgeRows.LineageType),
      f("provenance", CoauthorEdgeRows.ProvenanceType)
    )
  )

  def toRow(c: ComponentMembership): Row = Row(
    c.schemaVersion,
    c.researcherId,
    CoauthorEdgeRows.windowRow(c.window),
    c.componentId,
    c.componentSize,
    CoauthorEdgeRows.lineageRow(c.lineage),
    CoauthorEdgeRows.provenanceRow(c.provenance)
  )

  def fromRow(r: Row): Either[String, ComponentMembership] =
    try
      Right(
        ComponentMembership(
          r.getString(0),
          r.getString(1),
          CoauthorEdgeRows.windowOf(r.getStruct(2)),
          r.getString(3),
          r.getLong(4),
          CoauthorEdgeRows.lineageOf(r.getStruct(5)),
          CoauthorEdgeRows.provenanceOf(r.getStruct(6))
        )
      )
    catch { case NonFatal(e) => Left(s"row is not a component-membership.v1 record: $e") }
}

/**
 * Parquet row layout of `community-membership.v1` ([[CommunityMembership]]), record field order.
 */
object CommunityMembershipRows {
  val schema: StructType = StructType(
    Seq(
      f("schemaVersion", StringType),
      f("researcherId", StringType),
      f("window", CoauthorEdgeRows.WindowType),
      f("communityId", StringType),
      f("communitySize", LongType),
      f("componentId", StringType),
      f("algorithm", StringType),
      f("parameters", ParametersType),
      f("lineage", CoauthorEdgeRows.LineageType),
      f("provenance", CoauthorEdgeRows.ProvenanceType)
    )
  )

  def toRow(c: CommunityMembership): Row = Row(
    c.schemaVersion,
    c.researcherId,
    CoauthorEdgeRows.windowRow(c.window),
    c.communityId,
    c.communitySize,
    c.componentId,
    c.algorithm,
    parametersRow(c.parameters),
    CoauthorEdgeRows.lineageRow(c.lineage),
    CoauthorEdgeRows.provenanceRow(c.provenance)
  )

  def fromRow(r: Row): Either[String, CommunityMembership] =
    try
      Right(
        CommunityMembership(
          r.getString(0),
          r.getString(1),
          CoauthorEdgeRows.windowOf(r.getStruct(2)),
          r.getString(3),
          r.getLong(4),
          r.getString(5),
          r.getString(6),
          parametersOf(r, 7),
          CoauthorEdgeRows.lineageOf(r.getStruct(8)),
          CoauthorEdgeRows.provenanceOf(r.getStruct(9))
        )
      )
    catch { case NonFatal(e) => Left(s"row is not a community-membership.v1 record: $e") }
}

/** Parquet row layout of `bridge-measure.v1` ([[BridgeMeasure]]), record field order. */
object BridgeMeasureRows {
  val schema: StructType = StructType(
    Seq(
      f("schemaVersion", StringType),
      f("researcherId", StringType),
      f("window", CoauthorEdgeRows.WindowType),
      f("communityId", StringType),
      f("measure", StringType),
      f("parameters", ParametersType),
      f("score", DoubleType),
      f("degree", LongType),
      f("crossCommunityDegree", LongType),
      f("communitiesReached", LongType),
      f("participationCoefficient", DoubleType),
      f("lineage", CoauthorEdgeRows.LineageType),
      f("provenance", CoauthorEdgeRows.ProvenanceType)
    )
  )

  def toRow(b: BridgeMeasure): Row = Row(
    b.schemaVersion,
    b.researcherId,
    CoauthorEdgeRows.windowRow(b.window),
    b.communityId,
    b.measure,
    parametersRow(b.parameters),
    b.score,
    b.degree,
    b.crossCommunityDegree,
    b.communitiesReached,
    b.participationCoefficient,
    CoauthorEdgeRows.lineageRow(b.lineage),
    CoauthorEdgeRows.provenanceRow(b.provenance)
  )

  def fromRow(r: Row): Either[String, BridgeMeasure] =
    try
      Right(
        BridgeMeasure(
          r.getString(0),
          r.getString(1),
          CoauthorEdgeRows.windowOf(r.getStruct(2)),
          r.getString(3),
          r.getString(4),
          parametersOf(r, 5),
          r.getDouble(6),
          r.getLong(7),
          r.getLong(8),
          r.getLong(9),
          r.getDouble(10),
          CoauthorEdgeRows.lineageOf(r.getStruct(11)),
          CoauthorEdgeRows.provenanceOf(r.getStruct(12))
        )
      )
    catch { case NonFatal(e) => Left(s"row is not a bridge-measure.v1 record: $e") }
}

/** Parquet row layout of `coreness.v1` ([[Coreness]]), record field order. */
object CorenessRows {
  val schema: StructType = StructType(
    Seq(
      f("schemaVersion", StringType),
      f("researcherId", StringType),
      f("window", CoauthorEdgeRows.WindowType),
      f("coreness", LongType),
      f("degree", LongType),
      f("lineage", CoauthorEdgeRows.LineageType),
      f("provenance", CoauthorEdgeRows.ProvenanceType)
    )
  )

  def toRow(c: Coreness): Row = Row(
    c.schemaVersion,
    c.researcherId,
    CoauthorEdgeRows.windowRow(c.window),
    c.coreness,
    c.degree,
    CoauthorEdgeRows.lineageRow(c.lineage),
    CoauthorEdgeRows.provenanceRow(c.provenance)
  )

  def fromRow(r: Row): Either[String, Coreness] =
    try
      Right(
        Coreness(
          r.getString(0),
          r.getString(1),
          CoauthorEdgeRows.windowOf(r.getStruct(2)),
          r.getLong(3),
          r.getLong(4),
          CoauthorEdgeRows.lineageOf(r.getStruct(5)),
          CoauthorEdgeRows.provenanceOf(r.getStruct(6))
        )
      )
    catch { case NonFatal(e) => Left(s"row is not a coreness.v1 record: $e") }
}
