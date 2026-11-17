package scholarchain.spark.output

import scala.util.control.NonFatal

import org.apache.spark.sql.Row
import org.apache.spark.sql.types.*

import scholarchain.core.*

import StructureRows.f

/**
 * Parquet row layouts of the SP-P6-03 step 2C datasets, each in its record's field order with the
 * record's own field names (nested records as structs, vectors as arrays, enums as their frozen
 * tags, `Option` as a nullable column), as [[CoauthorEdgeRows]]. Every `fromRow` returns Left
 * instead of throwing.
 */
private[output] object AnalysisRowParts {

  def tag[A](what: String, s: String, of: String => Option[A]): A =
    of(s).getOrElse(throw new IllegalArgumentException(s"$what '$s'"))

  def seq[A](r: Row, i: Int): Vector[A] = r.getSeq[A](i).toVector

  def optLong(r: Row, i: Int): Option[Long] = if (r.isNullAt(i)) None else Some(r.getLong(i))
}

import AnalysisRowParts.{optLong, seq, tag}

/** Parquet row layout of `evidence-path.v1` ([[EvidencePath]]), record field order. */
object EvidencePathRows {
  val HopType: StructType = StructType(
    Seq(f("edgeId", StringType), f("evidenceWorkIds", ArrayType(StringType, containsNull = false)))
  )
  val BoundsType: StructType = StructType(
    Seq(
      f("maxHops", IntegerType),
      f("maxPathsPerPair", IntegerType),
      f("maxEvidenceWorksPerHop", IntegerType)
    )
  )

  val schema: StructType = StructType(
    Seq(
      f("schemaVersion", StringType),
      f("pathId", StringType),
      f("window", CoauthorEdgeRows.WindowType),
      f("sourceResearcherId", StringType),
      f("targetResearcherId", StringType),
      f("ordinal", IntegerType),
      f("hopCount", IntegerType),
      f("nodeIds", ArrayType(StringType, containsNull = false)),
      f("hops", ArrayType(HopType, containsNull = false)),
      f("nodeIdentities", ArrayType(ResearcherMetricsRows.IdentityType, containsNull = false)),
      f("bounds", BoundsType),
      f("lineage", CoauthorEdgeRows.LineageType),
      f("provenance", CoauthorEdgeRows.ProvenanceType)
    )
  )

  def toRow(p: EvidencePath): Row = Row(
    p.schemaVersion,
    p.pathId,
    CoauthorEdgeRows.windowRow(p.window),
    p.sourceResearcherId,
    p.targetResearcherId,
    p.ordinal,
    p.hopCount,
    p.nodeIds,
    p.hops.map(h => Row(h.edgeId, h.evidenceWorkIds)),
    p.nodeIdentities.map(ResearcherMetricsRows.identityRow),
    Row(p.bounds.maxHops, p.bounds.maxPathsPerPair, p.bounds.maxEvidenceWorksPerHop),
    CoauthorEdgeRows.lineageRow(p.lineage),
    CoauthorEdgeRows.provenanceRow(p.provenance)
  )

  def fromRow(r: Row): Either[String, EvidencePath] =
    try {
      val b = r.getStruct(10)
      Right(
        EvidencePath(
          r.getString(0),
          r.getString(1),
          CoauthorEdgeRows.windowOf(r.getStruct(2)),
          r.getString(3),
          r.getString(4),
          r.getInt(5),
          r.getInt(6),
          seq[String](r, 7),
          seq[Row](r, 8).map(h => PathHop(h.getString(0), seq[String](h, 1))),
          seq[Row](r, 9).map(ResearcherMetricsRows.identityOf),
          PathBounds(b.getInt(0), b.getInt(1), b.getInt(2)),
          CoauthorEdgeRows.lineageOf(r.getStruct(11)),
          CoauthorEdgeRows.provenanceOf(r.getStruct(12))
        )
      )
    } catch { case NonFatal(e) => Left(s"row is not an evidence-path.v1 record: $e") }
}

/** Parquet row layout of `analysis-window.v1` ([[AnalysisWindow]]), record field order. */
object AnalysisWindowRows {
  val schema: StructType = StructType(
    Seq(
      f("schemaVersion", StringType),
      f("window", CoauthorEdgeRows.WindowType),
      f("ordinal", IntegerType),
      f("countedWorkCount", LongType),
      f("countedWorksWithoutYear", LongType),
      f("researcherCount", LongType),
      f("edgeCount", LongType),
      f("componentCount", LongType),
      f("communityCount", LongType),
      f("unresolvedCoauthorOccurrences", LongType),
      f("lineage", CoauthorEdgeRows.LineageType),
      f("provenance", CoauthorEdgeRows.ProvenanceType)
    )
  )

  def toRow(w: AnalysisWindow): Row = Row(
    w.schemaVersion,
    CoauthorEdgeRows.windowRow(w.window),
    w.ordinal,
    w.countedWorkCount,
    w.countedWorksWithoutYear,
    w.researcherCount,
    w.edgeCount,
    w.componentCount,
    w.communityCount,
    w.unresolvedCoauthorOccurrences,
    CoauthorEdgeRows.lineageRow(w.lineage),
    CoauthorEdgeRows.provenanceRow(w.provenance)
  )

  def fromRow(r: Row): Either[String, AnalysisWindow] =
    try
      Right(
        AnalysisWindow(
          r.getString(0),
          CoauthorEdgeRows.windowOf(r.getStruct(1)),
          r.getInt(2),
          r.getLong(3),
          r.getLong(4),
          r.getLong(5),
          r.getLong(6),
          r.getLong(7),
          r.getLong(8),
          r.getLong(9),
          CoauthorEdgeRows.lineageOf(r.getStruct(10)),
          CoauthorEdgeRows.provenanceOf(r.getStruct(11))
        )
      )
    catch { case NonFatal(e) => Left(s"row is not an analysis-window.v1 record: $e") }
}

/** Parquet row layout of `path-length-estimate.v1` ([[PathLengthEstimate]]), record field order. */
object PathLengthEstimateRows {
  val DistanceCountType: StructType =
    StructType(Seq(f("distance", IntegerType), f("pairs", LongType)))

  val schema: StructType = StructType(
    Seq(
      f("schemaVersion", StringType),
      f("window", CoauthorEdgeRows.WindowType),
      f("componentId", StringType),
      f("componentSize", LongType),
      f("method", StringType),
      f("sampleSeed", LongType),
      f("sampleSize", IntegerType),
      f("sourceIds", ArrayType(StringType, containsNull = false)),
      f("exact", BooleanType),
      f("distanceCounts", ArrayType(DistanceCountType, containsNull = false)),
      f("pairCount", LongType),
      f("averageDistance", DoubleType),
      f("maxObservedDistance", IntegerType),
      f("effectiveDiameter90", IntegerType),
      f("lineage", CoauthorEdgeRows.LineageType),
      f("provenance", CoauthorEdgeRows.ProvenanceType)
    )
  )

  def toRow(e: PathLengthEstimate): Row = Row(
    e.schemaVersion,
    CoauthorEdgeRows.windowRow(e.window),
    e.componentId,
    e.componentSize,
    e.method,
    e.sampleSeed,
    e.sampleSize,
    e.sourceIds,
    e.exact,
    e.distanceCounts.map(c => Row(c.distance, c.pairs)),
    e.pairCount,
    e.averageDistance,
    e.maxObservedDistance,
    e.effectiveDiameter90,
    CoauthorEdgeRows.lineageRow(e.lineage),
    CoauthorEdgeRows.provenanceRow(e.provenance)
  )

  def fromRow(r: Row): Either[String, PathLengthEstimate] =
    try
      Right(
        PathLengthEstimate(
          r.getString(0),
          CoauthorEdgeRows.windowOf(r.getStruct(1)),
          r.getString(2),
          r.getLong(3),
          r.getString(4),
          r.getLong(5),
          r.getInt(6),
          seq[String](r, 7),
          r.getBoolean(8),
          seq[Row](r, 9).map(c => DistanceCount(c.getInt(0), c.getLong(1))),
          r.getLong(10),
          r.getDouble(11),
          r.getInt(12),
          r.getInt(13),
          CoauthorEdgeRows.lineageOf(r.getStruct(14)),
          CoauthorEdgeRows.provenanceOf(r.getStruct(15))
        )
      )
    catch { case NonFatal(e) => Left(s"row is not a path-length-estimate.v1 record: $e") }
}

/** Parquet row layout of `window-comparison.v1` ([[WindowComparison]]), record field order. */
object WindowComparisonRows {
  val schema: StructType = StructType(
    Seq(
      f("schemaVersion", StringType),
      f("fromWindow", CoauthorEdgeRows.WindowType),
      f("toWindow", CoauthorEdgeRows.WindowType),
      f("ordinal", IntegerType),
      f("inclusionRuleCompatible", BooleanType),
      f("overlapYears", IntegerType),
      f("limitations", ArrayType(StringType, containsNull = false)),
      f("researchersInBoth", LongType),
      f("researchersOnlyFrom", LongType),
      f("researchersOnlyTo", LongType),
      f("edgesInBoth", LongType),
      f("edgesOnlyFrom", LongType),
      f("edgesOnlyTo", LongType),
      f("lineage", CoauthorEdgeRows.LineageType),
      f("provenance", CoauthorEdgeRows.ProvenanceType)
    )
  )

  def toRow(c: WindowComparison): Row = Row(
    c.schemaVersion,
    CoauthorEdgeRows.windowRow(c.fromWindow),
    CoauthorEdgeRows.windowRow(c.toWindow),
    c.ordinal,
    c.inclusionRuleCompatible,
    c.overlapYears,
    c.limitations.map(_.tag),
    c.researchersInBoth,
    c.researchersOnlyFrom,
    c.researchersOnlyTo,
    c.edgesInBoth,
    c.edgesOnlyFrom,
    c.edgesOnlyTo,
    CoauthorEdgeRows.lineageRow(c.lineage),
    CoauthorEdgeRows.provenanceRow(c.provenance)
  )

  def fromRow(r: Row): Either[String, WindowComparison] =
    try
      Right(
        WindowComparison(
          r.getString(0),
          CoauthorEdgeRows.windowOf(r.getStruct(1)),
          CoauthorEdgeRows.windowOf(r.getStruct(2)),
          r.getInt(3),
          r.getBoolean(4),
          r.getInt(5),
          seq[String](r, 6).map(tag("limitation", _, ComparisonLimitation.fromTag)),
          r.getLong(7),
          r.getLong(8),
          r.getLong(9),
          r.getLong(10),
          r.getLong(11),
          r.getLong(12),
          CoauthorEdgeRows.lineageOf(r.getStruct(13)),
          CoauthorEdgeRows.provenanceOf(r.getStruct(14))
        )
      )
    catch { case NonFatal(e) => Left(s"row is not a window-comparison.v1 record: $e") }
}

/**
 * Parquet row layout of `researcher-temporal-change.v1` ([[ResearcherTemporalChange]]), record
 * field order; `from`, `to` and `communityOverlap` are nullable (absent exactly as the contract
 * says).
 */
object TemporalChangeRows {
  val StateType: StructType = StructType(
    Seq(
      f("publicationCount", LongType),
      f("distinctCoauthorCount", LongType),
      f("averageCoauthorsPerPaper", DoubleType),
      f("maximumCoauthorsOnOnePaper", LongType),
      f("componentSize", LongType),
      f("inLargestComponent", BooleanType),
      f("communitySize", LongType),
      f("crossCommunityDegree", LongType),
      f("communitiesReached", LongType),
      f("participationCoefficient", DoubleType),
      f("coreness", LongType)
    )
  )

  val schema: StructType = StructType(
    Seq(
      f("schemaVersion", StringType),
      f("researcherId", StringType),
      f("fromWindow", CoauthorEdgeRows.WindowType),
      f("toWindow", CoauthorEdgeRows.WindowType),
      f("presence", StringType),
      f("from", StateType, nullable = true),
      f("to", StateType, nullable = true),
      f("retainedCoauthors", LongType),
      f("gainedCoauthors", LongType),
      f("lostCoauthors", LongType),
      f("communityOverlap", LongType, nullable = true),
      f("lineage", CoauthorEdgeRows.LineageType),
      f("provenance", CoauthorEdgeRows.ProvenanceType)
    )
  )

  def stateRow(s: ResearcherWindowState): Row = Row(
    s.publicationCount,
    s.distinctCoauthorCount,
    s.averageCoauthorsPerPaper,
    s.maximumCoauthorsOnOnePaper,
    s.componentSize,
    s.inLargestComponent,
    s.communitySize,
    s.crossCommunityDegree,
    s.communitiesReached,
    s.participationCoefficient,
    s.coreness
  )

  def stateOf(r: Row): ResearcherWindowState = ResearcherWindowState(
    r.getLong(0),
    r.getLong(1),
    r.getDouble(2),
    r.getLong(3),
    r.getLong(4),
    r.getBoolean(5),
    r.getLong(6),
    r.getLong(7),
    r.getLong(8),
    r.getDouble(9),
    r.getLong(10)
  )

  def toRow(c: ResearcherTemporalChange): Row = Row(
    c.schemaVersion,
    c.researcherId,
    CoauthorEdgeRows.windowRow(c.fromWindow),
    CoauthorEdgeRows.windowRow(c.toWindow),
    c.presence.tag,
    c.from.map(stateRow).orNull,
    c.to.map(stateRow).orNull,
    c.retainedCoauthors,
    c.gainedCoauthors,
    c.lostCoauthors,
    c.communityOverlap match {
      case Some(v) => v
      case None => null
    },
    CoauthorEdgeRows.lineageRow(c.lineage),
    CoauthorEdgeRows.provenanceRow(c.provenance)
  )

  def fromRow(r: Row): Either[String, ResearcherTemporalChange] =
    try
      Right(
        ResearcherTemporalChange(
          r.getString(0),
          r.getString(1),
          CoauthorEdgeRows.windowOf(r.getStruct(2)),
          CoauthorEdgeRows.windowOf(r.getStruct(3)),
          tag("presence", r.getString(4), TemporalPresence.fromTag),
          if (r.isNullAt(5)) None else Some(stateOf(r.getStruct(5))),
          if (r.isNullAt(6)) None else Some(stateOf(r.getStruct(6))),
          r.getLong(7),
          r.getLong(8),
          r.getLong(9),
          optLong(r, 10),
          CoauthorEdgeRows.lineageOf(r.getStruct(11)),
          CoauthorEdgeRows.provenanceOf(r.getStruct(12))
        )
      )
    catch {
      case NonFatal(e) => Left(s"row is not a researcher-temporal-change.v1 record: $e")
    }
}

/** Parquet row layout of `semantic-summary-input.v1` ([[SemanticSummaryInput]]), field order. */
object SummaryInputRows {
  val EvidenceType: StructType = StructType(Seq(f("kind", StringType), f("id", StringType)))
  val FactType: StructType = StructType(
    Seq(
      f("name", StringType),
      f("value", StringType),
      f("evidence", ArrayType(EvidenceType, containsNull = false))
    )
  )

  val schema: StructType = StructType(
    Seq(
      f("schemaVersion", StringType),
      f("inputId", StringType),
      f("documentType", StringType),
      f("subjectId", StringType),
      f("window", CoauthorEdgeRows.WindowType),
      f("facts", ArrayType(FactType, containsNull = false)),
      f("identity", ResearcherMetricsRows.IdentityType, nullable = true),
      f("lineage", CoauthorEdgeRows.LineageType),
      f("provenance", CoauthorEdgeRows.ProvenanceType)
    )
  )

  def toRow(s: SemanticSummaryInput): Row = Row(
    s.schemaVersion,
    s.inputId,
    s.documentType.tag,
    s.subjectId,
    CoauthorEdgeRows.windowRow(s.window),
    s.facts.map(x => Row(x.name, x.value, x.evidence.map(e => Row(e.kind.tag, e.id)))),
    s.identity.map(ResearcherMetricsRows.identityRow).orNull,
    CoauthorEdgeRows.lineageRow(s.lineage),
    CoauthorEdgeRows.provenanceRow(s.provenance)
  )

  def fromRow(r: Row): Either[String, SemanticSummaryInput] =
    try
      Right(
        SemanticSummaryInput(
          r.getString(0),
          r.getString(1),
          tag("documentType", r.getString(2), DocumentType.fromTag),
          r.getString(3),
          CoauthorEdgeRows.windowOf(r.getStruct(4)),
          seq[Row](r, 5).map(x =>
            SummaryFact(
              x.getString(0),
              x.getString(1),
              seq[Row](x, 2).map(e =>
                SummaryEvidenceRef(
                  tag("kind", e.getString(0), SummaryEvidenceKind.fromTag),
                  e.getString(1)
                )
              )
            )
          ),
          if (r.isNullAt(6)) None else Some(ResearcherMetricsRows.identityOf(r.getStruct(6))),
          CoauthorEdgeRows.lineageOf(r.getStruct(7)),
          CoauthorEdgeRows.provenanceOf(r.getStruct(8))
        )
      )
    catch { case NonFatal(e) => Left(s"row is not a semantic-summary-input.v1 record: $e") }
}

/** Parquet row layout of `entity-summary-input.v1` ([[EntitySummaryInput]]), field order. */
object EntitySummaryInputRows {
  val schema: StructType = StructType(
    Seq(
      f("schemaVersion", StringType),
      f("inputId", StringType),
      f("documentType", StringType),
      f("subjectId", StringType),
      f("facts", ArrayType(SummaryInputRows.FactType, containsNull = false)),
      f("lineage", CoauthorEdgeRows.LineageType),
      f("provenance", CoauthorEdgeRows.ProvenanceType)
    )
  )

  def toRow(s: EntitySummaryInput): Row = Row(
    s.schemaVersion,
    s.inputId,
    s.documentType.tag,
    s.subjectId,
    s.facts.map(x => Row(x.name, x.value, x.evidence.map(e => Row(e.kind.tag, e.id)))),
    CoauthorEdgeRows.lineageRow(s.lineage),
    CoauthorEdgeRows.provenanceRow(s.provenance)
  )

  def fromRow(r: Row): Either[String, EntitySummaryInput] =
    try
      Right(
        EntitySummaryInput(
          r.getString(0),
          r.getString(1),
          tag("documentType", r.getString(2), DocumentType.fromTag),
          r.getString(3),
          seq[Row](r, 4).map(x =>
            EntityFact(
              x.getString(0),
              x.getString(1),
              seq[Row](x, 2).map(e =>
                EntityEvidenceRef(
                  tag("kind", e.getString(0), EntityEvidenceKind.fromTag),
                  e.getString(1)
                )
              )
            )
          ),
          CoauthorEdgeRows.lineageOf(r.getStruct(5)),
          CoauthorEdgeRows.provenanceOf(r.getStruct(6))
        )
      )
    catch { case NonFatal(e) => Left(s"row is not an entity-summary-input.v1 record: $e") }
}
