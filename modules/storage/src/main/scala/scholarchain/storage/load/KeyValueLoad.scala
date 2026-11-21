package scholarchain.storage.load

import scholarchain.core.*
import scholarchain.storage.model.{
  AnalysisResultRecord,
  Attrs,
  AttrValue,
  PrimaryKey,
  Item,
  ItemCodecs,
  LoadState,
  StoreError,
  TableRole
}
import scholarchain.storage.parquet.ParquetReadBounds
import scholarchain.storage.port.{Clock, KeyValueStore}

/**
 * Limits of the DynamoDB loader (SE-P7-07). Configuration: passed in, no default.
 *
 * @param maxItemBytes
 *   the largest item accepted, by [[KeyValueLoad.itemBytes]] (the DynamoDB item limit is 400 KB per
 *   AWS documentation as remembered; UNVERIFIED, aws-docs MCP unavailable on 2026-11-20)
 */
final case class KeyValueLoadLimits private (maxItemBytes: Int)

object KeyValueLoadLimits {
  def of(maxItemBytes: Int): Either[StoreError, KeyValueLoadLimits] =
    if (maxItemBytes < 1) Left(StoreError.Refused(s"maxItemBytes: $maxItemBytes is below 1"))
    else Right(new KeyValueLoadLimits(maxItemBytes))
}

/** Result of one DynamoDB load call. */
final case class KeyValueLoadReport(
    runId: String,
    loadId: String,
    items: Int,
    written: Long,
    alreadyPresent: Long,
    finalState: LoadState,
    skipped: Boolean,
    largestItemBytes: Int,
    rowsByRole: Map[String, Int]
)

/**
 * Loads the `dynamodb` store of a verified run into the analysis-results table (SE-P7-07 items 4,
 * 5; checklist D1-D4, N4, L2, L3). It runs only inside [[LoadGate.proceed]] with a [[LoadPermit]]
 * for `dynamodb`, and reads nothing but the permit's items (re-read and re-checked against the
 * verified manifest now), decoded with [[ParquetRecords.decodePart]].
 *
 *   - Rows: one [[AnalysisResultRecord]] per decoded record, keyed as the MC access patterns of
 *     [[scholarchain.storage.model.TableSpecs.ResearcherMetricCache]] state; the core record's JSON
 *     and its SHA-256 are the body, with run, load, source part and record schema (L3). The item's
 *     role must be the one its record schema maps to (fail closed).
 *   - Ranks (GSI rank): researcher-metrics by distinctCoauthorCount then publicationCount;
 *     bridge-measures by crossCommunityDegree then communitiesReached; coreness by coreness;
 *     path-length-estimates by componentSize; each descending, ties by subject ID (UTF-8), per
 *     window: deterministic from Spark's values, never computed by similarity.
 *   - Every row is built and size-checked before any write; any refusal stops the load with zero
 *     writes.
 *   - Writes: `putIfAbsent` only (D3): equal body (same `recordSha256`) is `AlreadyPresent`, a
 *     different body under the key is a `Conflict` and fails the load (never an overwrite).
 *   - Ledger: [[LoadLedger]] (publication claim first; resume without duplicates; a loaded load is
 *     skipped with zero writes). No TTL: analysis results are not disposable (D4).
 */
object KeyValueLoad {

  /** CR-P7-11 M1: the physical stores this loader writes (the key-value store only). */
  val WrittenStores: Vector[TargetStore] = Vector(TargetStore.DynamoDb)

  private val codec = ItemCodecs.AnalysisResultCodec
  private val R = AnalysisResultRecord

  /** The dynamodb load role of each record schema (core LoadRoles, as the fixture manifest). */
  val RoleOf: Map[String, String] = Map(
    SchemaVersions.ResearcherMetricsV2 -> "researcher-metrics",
    SchemaVersions.CoauthorEdgeV2 -> "coauthor-edges",
    SchemaVersions.EvidencePath -> "evidence-paths",
    SchemaVersions.ComponentMembership -> "component-memberships",
    SchemaVersions.CommunityMembership -> "community-memberships",
    SchemaVersions.BridgeMeasure -> "bridge-measures",
    SchemaVersions.Coreness -> "coreness",
    SchemaVersions.PathLengthEstimate -> "path-length-estimates",
    SchemaVersions.AnalysisWindow -> "analysis-windows",
    SchemaVersions.WindowStructure -> "window-structures",
    SchemaVersions.WindowComparison -> "window-comparisons",
    SchemaVersions.ResearcherTemporalChange -> "temporal-changes"
  )

  /** DynamoDB-style item size estimate: attribute name bytes plus value bytes (UTF-8). */
  def itemBytes(item: Item): Int = {
    def value(v: AttrValue): Int = v match {
      case AttrValue.S(s) => s.getBytes("UTF-8").length
      case AttrValue.N(n) => n.length
      case AttrValue.Bool(_) | AttrValue.Null => 1
      case AttrValue.L(xs) => 3 + xs.map(x => 1 + value(x)).sum
      case AttrValue.M(m) =>
        3 + m.map { case (k, x) => 1 + k.getBytes("UTF-8").length + value(x) }.sum
    }
    item.map { case (k, v) => k.getBytes("UTF-8").length + value(v) }.sum
  }

  private def wkey(w: TimeWindow): String = f"${w.startYear}%04d-${w.endYear}%04d"

  private def canonical(a: String, b: String): Vector[String] =
    if (Canonical.compareUtf8(a, b) < 0) Vector(a, b) else Vector(b, a)

  /** One row before ranking: the record, and its rank values when its role is ranked. */
  private final case class Draft(row: AnalysisResultRecord, rankBy: Option[Vector[Long]])

  private def draft(
      permit: LoadPermit,
      it: LoadItem,
      p: Product
  ): Either[StoreError, Draft] = {
    def mk(
        scope: String,
        scopeId: String,
        window: TimeWindow,
        to: Option[TimeWindow],
        json: String,
        group: Option[String] = None,
        pair: Option[Vector[String]] = None,
        rankBy: Option[Vector[Long]] = None
    ) =
      Draft(
        AnalysisResultRecord(
          R.SchemaVersion,
          it.targetRole,
          scope,
          scopeId,
          wkey(window),
          to.map(wkey),
          json,
          Canonical.sha256HexUtf8(json),
          it.recordSchemaVersion,
          group,
          pair,
          None,
          permit.runId,
          permit.load.loadId,
          it.path
        ),
        rankBy
      )
    val S = R.SubjectScope
    val W = R.WindowScope
    val d: Either[StoreError, Draft] = p match {
      case x: ResearcherMetricsV2 =>
        Right(
          mk(
            S,
            x.researcherId,
            x.window,
            None,
            Json.write(x),
            rankBy = Some(Vector(x.distinctCoauthorCount, x.publicationCount))
          )
        )
      case x: CoauthorEdgeV2 =>
        Right(
          mk(
            S,
            x.edgeId,
            x.window,
            None,
            Json.write(x),
            pair = Some(canonical(x.researcherA, x.researcherB))
          )
        )
      case x: EvidencePath =>
        Right(
          mk(
            S,
            x.pathId,
            x.window,
            None,
            Json.write(x),
            pair = Some(canonical(x.sourceResearcherId, x.targetResearcherId))
          )
        )
      case x: ComponentMembership =>
        Right(mk(S, x.researcherId, x.window, None, Json.write(x), group = Some(x.componentId)))
      case x: CommunityMembership =>
        Right(mk(S, x.researcherId, x.window, None, Json.write(x), group = Some(x.communityId)))
      case x: BridgeMeasure =>
        Right(
          mk(
            S,
            x.researcherId,
            x.window,
            None,
            Json.write(x),
            rankBy = Some(Vector(x.crossCommunityDegree, x.communitiesReached))
          )
        )
      case x: Coreness =>
        Right(
          mk(S, x.researcherId, x.window, None, Json.write(x), rankBy = Some(Vector(x.coreness)))
        )
      case x: PathLengthEstimate =>
        Right(
          mk(
            S,
            x.componentId,
            x.window,
            None,
            Json.write(x),
            rankBy = Some(Vector(x.componentSize))
          )
        )
      case x: AnalysisWindow => Right(mk(W, wkey(x.window), x.window, None, Json.write(x)))
      case x: WindowStructure => Right(mk(W, wkey(x.window), x.window, None, Json.write(x)))
      case x: WindowComparison =>
        Right(mk(W, wkey(x.fromWindow), x.fromWindow, Some(x.toWindow), Json.write(x)))
      case x: ResearcherTemporalChange =>
        Right(mk(S, x.researcherId, x.fromWindow, Some(x.toWindow), Json.write(x)))
      case other =>
        Left(StoreError.Refused(s"${it.path}: ${other.productPrefix} is not a dynamodb record"))
    }
    d.flatMap { dr =>
      RoleOf.get(it.recordSchemaVersion) match {
        case Some(role) if role == it.targetRole => Right(dr)
        case _ =>
          Left(
            StoreError.Refused(
              s"${it.path}: role ${it.targetRole} is not the dynamodb role of ${it.recordSchemaVersion}"
            )
          )
      }
    }
  }

  /** Every row of the permit's load, ranked, in a deterministic order (item path, then record). */
  def rows(
      permit: LoadPermit,
      parquet: ParquetReadBounds
  ): Either[StoreError, Vector[AnalysisResultRecord]] = {
    val drafts = permit.items.foldLeft[Either[StoreError, Vector[Draft]]](Right(Vector.empty)) {
      (acc, it) =>
        for {
          xs <- acc
          b <- permit.readItem(it.path)
          part <- ParquetRecords.decodePart(it.path, it.recordSchemaVersion, b, parquet)
          ds <- part.records.foldLeft[Either[StoreError, Vector[Draft]]](Right(Vector.empty)) {
            (a, r) => a.flatMap(v => draft(permit, it, r).map(v :+ _))
          }
        } yield xs ++ ds
    }
    drafts.map { ds =>
      val ranks: Map[(String, String, String), Int] =
        ds.filter(_.rankBy.isDefined)
          .groupBy(d => (d.row.role, d.row.window))
          .toVector
          .flatMap { case ((role, w), group) =>
            group
              .sortWith { (a, b) =>
                val (x, y) = (a.rankBy.get, b.rankBy.get)
                val c = x.zip(y).map { case (i, j) => java.lang.Long.compare(j, i) }.find(_ != 0)
                c match {
                  case Some(v) => v < 0
                  case None => Canonical.compareUtf8(a.row.scopeId, b.row.scopeId) < 0
                }
              }
              .zipWithIndex
              .map { case (d, i) => (role, w, d.row.scopeId) -> (i + 1) }
          }
          .toMap
      ds.map(d =>
        d.row.copy(rank = d.rankBy.map(_ => ranks((d.row.role, d.row.window, d.row.scopeId))))
      )
    }
  }

  /**
   * CR-P7-09 M1: GetItem of the item's key; absent or the same `recordSha256` is fine, another
   * record under the key (another run's row, or another body) is a `Conflict` before any write.
   */
  private[load] def probe(table: KeyValueStore)(i: Item): Either[StoreError, Unit] = {
    val spec = codec.table
    (i.get(spec.partitionKey), i.get(spec.sortKey)) match {
      case (Some(AttrValue.S(pk)), Some(AttrValue.S(sk))) =>
        table.get(TableRole.ResearcherMetricCache, PrimaryKey(pk, sk)).flatMap {
          case Some(existing) if existing.get(Attrs.RecordSha256) != i.get(Attrs.RecordSha256) =>
            Left(
              StoreError.Conflict(
                s"${spec.role.tag} $pk $sk: holds a different record (pre-write probe)"
              )
            )
          case _ => Right(())
        }
      case _ => Left(StoreError.Refused("key-value load: an item has no table key"))
    }
  }

  def load(
      permit: LoadPermit,
      parquet: ParquetReadBounds,
      limits: KeyValueLoadLimits,
      table: KeyValueStore,
      ledger: KeyValueStore,
      clock: Clock,
      progress: LoadProgress = new LoadProgress
  ): Either[StoreError, KeyValueLoadReport] =
    if (Vector(permit, parquet, limits, table, ledger, clock, progress).contains(null))
      Left(StoreError.Refused("key-value load: every argument is required"))
    else if (permit.target != TargetStore.DynamoDb)
      Left(StoreError.Refused(s"key-value load: the permit is for ${permit.target.tag}"))
    else
      for {
        rs <- rows(permit, parquet)
        items <- rs.foldLeft[Either[StoreError, Vector[Item]]](Right(Vector.empty)) { (acc, r) =>
          acc.flatMap(xs => codec.encode(r).map(xs :+ _))
        }
        dup = items.groupBy(i => (i("pk"), i("sk"))).filter(_._2.size > 1)
        _ <-
          if (dup.isEmpty) Right(())
          else
            Left(
              StoreError
                .Refused(s"key-value load: ${dup.size} keys are produced by more than one record")
            )
        largest = items.map(itemBytes).maxOption.getOrElse(0)
        _ <-
          if (largest <= limits.maxItemBytes) Right(())
          else
            Left(
              StoreError.Refused(
                s"key-value load: an item of $largest bytes exceeds maxItemBytes ${limits.maxItemBytes}"
              )
            )
        out <- LoadLedger.run(permit, ledger, clock, items, WrittenStores, progress)(probe(table))(
          i => table.putIfAbsent(TableRole.ResearcherMetricCache, i)
        )
      } yield KeyValueLoadReport(
        permit.runId,
        permit.load.loadId,
        items.size,
        out.written,
        out.alreadyPresent,
        out.finalState,
        out.skipped,
        largest,
        rs.groupBy(_.role).map { case (k, v) => k -> v.size }
      )
}
