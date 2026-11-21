package scholarchain.storage.load

import scala.collection.immutable.SortedMap

import scholarchain.core.*
import scholarchain.storage.model.{Bytes, StoreError}
import scholarchain.storage.parquet.{PValue, ParquetFooter, ParquetReadBounds, ParquetRows}

/**
 * One decoded Parquet part (SE-P7-06 items 1, 2): the footer row count, the rows the reader
 * decoded, and the records they decode to (each of the part's record schema).
 */
final case class DecodedPart(
    path: String,
    recordSchemaVersion: String,
    footer: ParquetFooter,
    decodedRows: Long,
    records: Vector[Product]
)

/**
 * The Parquet row layouts of the analysis record schemas, as the Spark writer defines them (the
 * `...Rows` objects of package `scholarchain.spark.output`): one column per record field, in the
 * record's field order and with its names; nested records as structs, vectors as lists, enums as
 * their frozen tags, `Option` as a nullable column, string maps as an ascending
 * `list<struct<key,value>>`. Storage only reads them: every column set is checked exactly (names
 * and order, at every level), a null in a non-optional field is refused, and an unknown tag is
 * refused. The decoded record is not yet validated here; [[ParquetChecks]] runs its core record
 * contract.
 */
object ParquetRecords {

  private final class Bad(why: String) extends RuntimeException(why, null, false, false)

  private def bad(why: String): Nothing = throw new Bad(why)

  /** Field access to one decoded struct, with exact column sets. */
  private final class F(at: String, s: PValue.Struct) {
    private val byName = s.fields.toMap

    def expect(columns: Vector[String]): F =
      if (s.fields.map(_._1) != columns)
        bad(s"$at: columns ${s.fields.map(_._1).mkString(",")} are not ${columns.mkString(",")}")
      else this

    private def get(n: String): PValue = byName.getOrElse(n, bad(s"$at$n: missing"))

    private def value[A](n: String)(pf: PartialFunction[PValue, A]): A =
      get(n) match {
        case PValue.Null => bad(s"$at$n: null")
        case v if pf.isDefinedAt(v) => pf(v)
        case v => bad(s"$at$n: unexpected ${v.productPrefix}")
      }

    private def opt[A](n: String)(pf: PartialFunction[PValue, A]): Option[A] =
      get(n) match {
        case PValue.Null => None
        case v if pf.isDefinedAt(v) => Some(pf(v))
        case v => bad(s"$at$n: unexpected ${v.productPrefix}")
      }

    def str(n: String): String = value(n) { case PValue.Str(v) => v }
    def int(n: String): Int = value(n) { case PValue.I32(v) => v }
    def long(n: String): Long = value(n) { case PValue.I64(v) => v }
    def double(n: String): Double = value(n) { case PValue.F64(v) => v }
    def bool(n: String): Boolean = value(n) { case PValue.Bool(v) => v }
    def optLong(n: String): Option[Long] = opt(n) { case PValue.I64(v) => v }
    def optDouble(n: String): Option[Double] = opt(n) { case PValue.F64(v) => v }
    def optString(n: String): Option[String] = opt(n) { case PValue.Str(v) => v }
    def obj(n: String, columns: Vector[String]): F =
      value(n) { case x: PValue.Struct => new F(s"$at$n.", x).expect(columns) }
    def optObj(n: String, columns: Vector[String]): Option[F] =
      opt(n) { case x: PValue.Struct => new F(s"$at$n.", x).expect(columns) }
    private def items(n: String): Vector[PValue] = value(n) { case PValue.ListOf(xs) => xs }
    def strs(n: String): Vector[String] =
      items(n).zipWithIndex.map {
        case (PValue.Str(v), _) => v
        case (other, i) => bad(s"$at$n[$i]: unexpected ${other.productPrefix}")
      }
    def objs(n: String, columns: Vector[String]): Vector[F] =
      items(n).zipWithIndex.map {
        case (x: PValue.Struct, i) => new F(s"$at$n[$i].", x).expect(columns)
        case (other, i) => bad(s"$at$n[$i]: unexpected ${other.productPrefix}")
      }
  }

  private def tag[A](what: String, s: String, of: String => Option[A]): A =
    of(s).getOrElse(bad(s"$what: '$s' is not a frozen tag"))

  // ------------------------------------------------------------------ shared nested layouts

  private val WindowColumns = Vector("startYear", "endYear")
  private val LineageColumns = Vector(
    "groupingRunId",
    "decisionRunId",
    "inputRunId",
    "codeVersion",
    "configDigest",
    "groupingPolicyVersion",
    "matchPolicyVersion",
    "identityVersion",
    "groupingEvaluation"
  )
  private val ProvenanceColumns = Vector(
    "sourceDataset",
    "sourceRecordId",
    "snapshot",
    "runId",
    "sourceSchemaVersion",
    "policyVersion",
    "modelVersion",
    "promptVersion"
  )
  private val ConfidenceColumns = Vector("kind", "value", "unknownReason")
  private val IdentityColumns =
    Vector("status", "anchorNamespace", "memberCount", "collisionCodes", "lowestDecisionConfidence")
  private val KeyValueColumns = Vector("key", "value")
  private val FactColumns = Vector("name", "value", "evidence")
  private val EvidenceRefColumns = Vector("kind", "id")

  private def window(f: F): TimeWindow = TimeWindow(f.int("startYear"), f.int("endYear"))

  private def lineage(f: F): AnalysisLineage =
    AnalysisLineage(
      f.str("groupingRunId"),
      f.str("decisionRunId"),
      f.str("inputRunId"),
      f.str("codeVersion"),
      f.str("configDigest"),
      f.str("groupingPolicyVersion"),
      f.str("matchPolicyVersion"),
      f.str("identityVersion"),
      tag("groupingEvaluation", f.str("groupingEvaluation"), GroupingEvaluation.fromTag)
    )

  private def provenance(f: F): Provenance =
    Provenance(
      tag("sourceDataset", f.str("sourceDataset"), SourceType.fromTag),
      f.str("sourceRecordId"),
      f.str("snapshot"),
      f.str("runId"),
      f.str("sourceSchemaVersion"),
      f.optString("policyVersion"),
      f.optString("modelVersion"),
      f.optString("promptVersion")
    )

  private def win(f: F, n: String): TimeWindow = window(f.obj(n, WindowColumns))
  private def lin(f: F): AnalysisLineage = lineage(f.obj("lineage", LineageColumns))
  private def prov(f: F): Provenance = provenance(f.obj("provenance", ProvenanceColumns))

  private def identity(f: F): IdentityConfidence = {
    val d = f.obj("lowestDecisionConfidence", ConfidenceColumns)
    IdentityConfidence(
      tag("status", f.str("status"), IdentityStatus.fromTag),
      f.str("anchorNamespace"),
      f.int("memberCount"),
      f.strs("collisionCodes"),
      DecisionConfidence(
        tag("kind", d.str("kind"), ConfidenceKind.fromTag),
        d.optDouble("value"),
        d.optString("unknownReason").map(tag("unknownReason", _, ConfidenceUnknownReason.fromTag))
      )
    )
  }

  /**
   * `list<struct<key,value>>` with strictly ascending keys (as the Spark writer refuses otherwise).
   */
  private def parameters(f: F, n: String): SortedMap[String, String] = {
    val kv = f.objs(n, KeyValueColumns).map(x => (x.str("key"), x.str("value")))
    // Unsigned UTF-8 byte order, as SortedMap keys are compared by the core (CR-P7-08 n3).
    if (kv.sliding(2).exists(w => w.size == 2 && Canonical.compareUtf8(w(0)._1, w(1)._1) >= 0))
      bad(s"$n: keys not strictly ascending")
    SortedMap.from(kv)
  }

  // ------------------------------------------------------------------ record decoders

  /** One record schema: its top-level columns and its decoder. */
  private final case class Layout(schemaVersion: String, columns: Vector[String])(
      val decode: F => Product
  )

  /** The record schemas this reader decodes. */
  def schemas: Set[String] = layouts.keySet

  /** The top-level columns of `schemaVersion`'s layout. */
  def columnsOf(schemaVersion: String): Option[Vector[String]] =
    layouts.get(schemaVersion).map(_.columns)

  private val Lineage = "lineage"
  private val Prov = "provenance"

  private lazy val layouts: Map[String, Layout] = Vector(
    Layout(
      SchemaVersions.CoauthorEdgeV2,
      Vector(
        "schemaVersion",
        "edgeId",
        "researcherA",
        "researcherB",
        "window",
        "jointPublicationCount",
        "firstCollaborationYear",
        "lastCollaborationYear",
        "publicationsByYear",
        "supportingWorkIds",
        Lineage,
        Prov
      )
    )(r =>
      CoauthorEdgeV2(
        r.str("schemaVersion"),
        r.str("edgeId"),
        r.str("researcherA"),
        r.str("researcherB"),
        win(r, "window"),
        r.long("jointPublicationCount"),
        r.int("firstCollaborationYear"),
        r.int("lastCollaborationYear"),
        r.objs("publicationsByYear", Vector("year", "count"))
          .map(y => YearCount(y.int("year"), y.long("count"))),
        r.strs("supportingWorkIds"),
        lin(r),
        prov(r)
      )
    ),
    Layout(
      SchemaVersions.ResearcherMetricsV2,
      Vector(
        "schemaVersion",
        "researcherId",
        "window",
        "publicationCount",
        "distinctCoauthorCount",
        "totalCoauthorOccurrences",
        "unresolvedCoauthorOccurrences",
        "worksWithUnresolvedCoauthors",
        "averageCoauthorsPerPaper",
        "maximumCoauthorsOnOnePaper",
        "maximumTeamWorkIds",
        "weightedDegree",
        "identity",
        Lineage,
        Prov
      )
    )(r =>
      ResearcherMetricsV2(
        r.str("schemaVersion"),
        r.str("researcherId"),
        win(r, "window"),
        r.long("publicationCount"),
        r.long("distinctCoauthorCount"),
        r.long("totalCoauthorOccurrences"),
        r.long("unresolvedCoauthorOccurrences"),
        r.long("worksWithUnresolvedCoauthors"),
        r.double("averageCoauthorsPerPaper"),
        r.long("maximumCoauthorsOnOnePaper"),
        r.strs("maximumTeamWorkIds"),
        r.long("weightedDegree"),
        identity(r.obj("identity", IdentityColumns)),
        lin(r),
        prov(r)
      )
    ),
    Layout(
      SchemaVersions.ComponentMembership,
      Vector(
        "schemaVersion",
        "researcherId",
        "window",
        "componentId",
        "componentSize",
        Lineage,
        Prov
      )
    )(r =>
      ComponentMembership(
        r.str("schemaVersion"),
        r.str("researcherId"),
        win(r, "window"),
        r.str("componentId"),
        r.long("componentSize"),
        lin(r),
        prov(r)
      )
    ),
    Layout(
      SchemaVersions.CommunityMembership,
      Vector(
        "schemaVersion",
        "researcherId",
        "window",
        "communityId",
        "communitySize",
        "componentId",
        "algorithm",
        "parameters",
        Lineage,
        Prov
      )
    )(r =>
      CommunityMembership(
        r.str("schemaVersion"),
        r.str("researcherId"),
        win(r, "window"),
        r.str("communityId"),
        r.long("communitySize"),
        r.str("componentId"),
        r.str("algorithm"),
        parameters(r, "parameters"),
        lin(r),
        prov(r)
      )
    ),
    Layout(
      SchemaVersions.BridgeMeasure,
      Vector(
        "schemaVersion",
        "researcherId",
        "window",
        "communityId",
        "measure",
        "parameters",
        "score",
        "degree",
        "crossCommunityDegree",
        "communitiesReached",
        "participationCoefficient",
        Lineage,
        Prov
      )
    )(r =>
      BridgeMeasure(
        r.str("schemaVersion"),
        r.str("researcherId"),
        win(r, "window"),
        r.str("communityId"),
        r.str("measure"),
        parameters(r, "parameters"),
        r.double("score"),
        r.long("degree"),
        r.long("crossCommunityDegree"),
        r.long("communitiesReached"),
        r.double("participationCoefficient"),
        lin(r),
        prov(r)
      )
    ),
    Layout(
      SchemaVersions.Coreness,
      Vector("schemaVersion", "researcherId", "window", "coreness", "degree", Lineage, Prov)
    )(r =>
      Coreness(
        r.str("schemaVersion"),
        r.str("researcherId"),
        win(r, "window"),
        r.long("coreness"),
        r.long("degree"),
        lin(r),
        prov(r)
      )
    ),
    Layout(
      SchemaVersions.GraphNodeV2,
      Vector("schemaVersion", "nodeId", "label", "properties", Lineage, Prov)
    )(r =>
      GraphNodeV2(
        r.str("schemaVersion"),
        r.str("nodeId"),
        tag("label", r.str("label"), NodeLabel.fromTag),
        parameters(r, "properties"),
        lin(r),
        prov(r)
      )
    ),
    Layout(
      SchemaVersions.GraphEdgeV2,
      Vector(
        "schemaVersion",
        "edgeId",
        "fromId",
        "edgeType",
        "toId",
        "sourceEvidence",
        "properties",
        Lineage,
        Prov
      )
    )(r =>
      GraphEdgeV2(
        r.str("schemaVersion"),
        r.str("edgeId"),
        r.str("fromId"),
        tag("edgeType", r.str("edgeType"), EdgeLabel.fromTag),
        r.str("toId"),
        r.str("sourceEvidence"),
        parameters(r, "properties"),
        lin(r),
        prov(r)
      )
    ),
    Layout(
      SchemaVersions.WindowStructure,
      Vector(
        "schemaVersion",
        "window",
        "ordinal",
        "researcherCount",
        "edgeCount",
        "componentCount",
        "largestComponentSize",
        "largestComponentFraction",
        "singletonCount",
        "communityCount",
        "largestCommunitySize",
        "crossCommunityEdgeCount",
        "communityAlgorithm",
        "communityIterations",
        "communityConverged",
        "maxCoreness",
        Lineage,
        Prov
      )
    )(r =>
      WindowStructure(
        r.str("schemaVersion"),
        win(r, "window"),
        r.int("ordinal"),
        r.long("researcherCount"),
        r.long("edgeCount"),
        r.long("componentCount"),
        r.long("largestComponentSize"),
        r.double("largestComponentFraction"),
        r.long("singletonCount"),
        r.long("communityCount"),
        r.long("largestCommunitySize"),
        r.long("crossCommunityEdgeCount"),
        r.str("communityAlgorithm"),
        r.int("communityIterations"),
        r.bool("communityConverged"),
        r.long("maxCoreness"),
        lin(r),
        prov(r)
      )
    ),
    Layout(
      SchemaVersions.EvidencePath,
      Vector(
        "schemaVersion",
        "pathId",
        "window",
        "sourceResearcherId",
        "targetResearcherId",
        "ordinal",
        "hopCount",
        "nodeIds",
        "hops",
        "nodeIdentities",
        "bounds",
        Lineage,
        Prov
      )
    ) { r =>
      val b = r.obj("bounds", Vector("maxHops", "maxPathsPerPair", "maxEvidenceWorksPerHop"))
      EvidencePath(
        r.str("schemaVersion"),
        r.str("pathId"),
        win(r, "window"),
        r.str("sourceResearcherId"),
        r.str("targetResearcherId"),
        r.int("ordinal"),
        r.int("hopCount"),
        r.strs("nodeIds"),
        r.objs("hops", Vector("edgeId", "evidenceWorkIds"))
          .map(h => PathHop(h.str("edgeId"), h.strs("evidenceWorkIds"))),
        r.objs("nodeIdentities", IdentityColumns).map(identity),
        PathBounds(b.int("maxHops"), b.int("maxPathsPerPair"), b.int("maxEvidenceWorksPerHop")),
        lin(r),
        prov(r)
      )
    },
    Layout(
      SchemaVersions.AnalysisWindow,
      Vector(
        "schemaVersion",
        "window",
        "ordinal",
        "countedWorkCount",
        "countedWorksWithoutYear",
        "researcherCount",
        "edgeCount",
        "componentCount",
        "communityCount",
        "unresolvedCoauthorOccurrences",
        Lineage,
        Prov
      )
    )(r =>
      AnalysisWindow(
        r.str("schemaVersion"),
        win(r, "window"),
        r.int("ordinal"),
        r.long("countedWorkCount"),
        r.long("countedWorksWithoutYear"),
        r.long("researcherCount"),
        r.long("edgeCount"),
        r.long("componentCount"),
        r.long("communityCount"),
        r.long("unresolvedCoauthorOccurrences"),
        lin(r),
        prov(r)
      )
    ),
    Layout(
      SchemaVersions.PathLengthEstimate,
      Vector(
        "schemaVersion",
        "window",
        "componentId",
        "componentSize",
        "method",
        "sampleSeed",
        "sampleSize",
        "sourceIds",
        "exact",
        "distanceCounts",
        "pairCount",
        "averageDistance",
        "maxObservedDistance",
        "effectiveDiameter90",
        Lineage,
        Prov
      )
    )(r =>
      PathLengthEstimate(
        r.str("schemaVersion"),
        win(r, "window"),
        r.str("componentId"),
        r.long("componentSize"),
        r.str("method"),
        r.long("sampleSeed"),
        r.int("sampleSize"),
        r.strs("sourceIds"),
        r.bool("exact"),
        r.objs("distanceCounts", Vector("distance", "pairs"))
          .map(c => DistanceCount(c.int("distance"), c.long("pairs"))),
        r.long("pairCount"),
        r.double("averageDistance"),
        r.int("maxObservedDistance"),
        r.int("effectiveDiameter90"),
        lin(r),
        prov(r)
      )
    ),
    Layout(
      SchemaVersions.WindowComparison,
      Vector(
        "schemaVersion",
        "fromWindow",
        "toWindow",
        "ordinal",
        "inclusionRuleCompatible",
        "overlapYears",
        "limitations",
        "researchersInBoth",
        "researchersOnlyFrom",
        "researchersOnlyTo",
        "edgesInBoth",
        "edgesOnlyFrom",
        "edgesOnlyTo",
        Lineage,
        Prov
      )
    )(r =>
      WindowComparison(
        r.str("schemaVersion"),
        win(r, "fromWindow"),
        win(r, "toWindow"),
        r.int("ordinal"),
        r.bool("inclusionRuleCompatible"),
        r.int("overlapYears"),
        r.strs("limitations").map(tag("limitation", _, ComparisonLimitation.fromTag)),
        r.long("researchersInBoth"),
        r.long("researchersOnlyFrom"),
        r.long("researchersOnlyTo"),
        r.long("edgesInBoth"),
        r.long("edgesOnlyFrom"),
        r.long("edgesOnlyTo"),
        lin(r),
        prov(r)
      )
    ),
    Layout(
      SchemaVersions.ResearcherTemporalChange,
      Vector(
        "schemaVersion",
        "researcherId",
        "fromWindow",
        "toWindow",
        "presence",
        "from",
        "to",
        "retainedCoauthors",
        "gainedCoauthors",
        "lostCoauthors",
        "communityOverlap",
        Lineage,
        Prov
      )
    )(r =>
      ResearcherTemporalChange(
        r.str("schemaVersion"),
        r.str("researcherId"),
        win(r, "fromWindow"),
        win(r, "toWindow"),
        tag("presence", r.str("presence"), TemporalPresence.fromTag),
        r.optObj("from", StateColumns).map(state),
        r.optObj("to", StateColumns).map(state),
        r.long("retainedCoauthors"),
        r.long("gainedCoauthors"),
        r.long("lostCoauthors"),
        r.optLong("communityOverlap"),
        lin(r),
        prov(r)
      )
    ),
    Layout(
      SchemaVersions.SemanticSummaryInput,
      Vector(
        "schemaVersion",
        "inputId",
        "documentType",
        "subjectId",
        "window",
        "facts",
        "identity",
        Lineage,
        Prov
      )
    )(r =>
      SemanticSummaryInput(
        r.str("schemaVersion"),
        r.str("inputId"),
        tag("documentType", r.str("documentType"), DocumentType.fromTag),
        r.str("subjectId"),
        win(r, "window"),
        r.objs("facts", FactColumns)
          .map(x =>
            SummaryFact(
              x.str("name"),
              x.str("value"),
              x.objs("evidence", EvidenceRefColumns)
                .map(e =>
                  SummaryEvidenceRef(
                    tag("kind", e.str("kind"), SummaryEvidenceKind.fromTag),
                    e.str("id")
                  )
                )
            )
          ),
        r.optObj("identity", IdentityColumns).map(identity),
        lin(r),
        prov(r)
      )
    ),
    Layout(
      SchemaVersions.EntitySummaryInput,
      Vector("schemaVersion", "inputId", "documentType", "subjectId", "facts", Lineage, Prov)
    )(r =>
      EntitySummaryInput(
        r.str("schemaVersion"),
        r.str("inputId"),
        tag("documentType", r.str("documentType"), DocumentType.fromTag),
        r.str("subjectId"),
        r.objs("facts", FactColumns)
          .map(x =>
            EntityFact(
              x.str("name"),
              x.str("value"),
              x.objs("evidence", EvidenceRefColumns)
                .map(e =>
                  EntityEvidenceRef(
                    tag("kind", e.str("kind"), EntityEvidenceKind.fromTag),
                    e.str("id")
                  )
                )
            )
          ),
        lin(r),
        prov(r)
      )
    )
  ).map(l => l.schemaVersion -> l).toMap

  private lazy val StateColumns = Vector(
    "publicationCount",
    "distinctCoauthorCount",
    "averageCoauthorsPerPaper",
    "maximumCoauthorsOnOnePaper",
    "componentSize",
    "inLargestComponent",
    "communitySize",
    "crossCommunityDegree",
    "communitiesReached",
    "participationCoefficient",
    "coreness"
  )

  private def state(s: F): ResearcherWindowState =
    ResearcherWindowState(
      s.long("publicationCount"),
      s.long("distinctCoauthorCount"),
      s.double("averageCoauthorsPerPaper"),
      s.long("maximumCoauthorsOnOnePaper"),
      s.long("componentSize"),
      s.bool("inLargestComponent"),
      s.long("communitySize"),
      s.long("crossCommunityDegree"),
      s.long("communitiesReached"),
      s.double("participationCoefficient"),
      s.long("coreness")
    )

  /** The records of one decoded part's rows under `schemaVersion`, or the first problem. */
  def decodeRows(
      path: String,
      schemaVersion: String,
      columns: Vector[String],
      rows: Vector[PValue.Struct]
  ): Either[String, Vector[Product]] =
    layouts.get(schemaVersion) match {
      case None => Left(s"$path: no Parquet layout for record schema $schemaVersion")
      case Some(l) if columns != l.columns =>
        Left(
          s"$path: columns ${columns.mkString(",")} are not the $schemaVersion layout ${l.columns.mkString(",")}"
        )
      case Some(l) =>
        try
          Right(rows.zipWithIndex.map { case (r, i) => l.decode(new F(s"$path#${i + 1} ", r)) })
        catch { case b: Bad => Left(b.getMessage) }
    }

  /** Reads one verified part and decodes its records (bounded by `bounds`). */
  def decodePart(
      path: String,
      schemaVersion: String,
      bytes: Bytes,
      bounds: ParquetReadBounds
  ): Either[StoreError, DecodedPart] =
    ParquetRows.read(path, bytes, bounds) match {
      case Left(e) => Left(e.toStoreError)
      case Right(p) =>
        decodeRows(path, schemaVersion, p.columns, p.rows) match {
          case Left(why) => Left(StoreError.Corrupt(why))
          case Right(rs) =>
            Right(DecodedPart(path, schemaVersion, p.footer, p.rows.size.toLong, rs))
        }
    }
}
