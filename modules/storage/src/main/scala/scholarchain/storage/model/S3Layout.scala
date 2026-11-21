package scholarchain.storage.model

import java.nio.charset.StandardCharsets

import scholarchain.core.{IdKind, SourceType}

/** The six ordinary-S3 zones (README Phase 7, L1368). Frozen tags. */
enum Zone(val tag: String) {
  case Raw extends Zone("raw")
  case Curated extends Zone("curated")
  case Graph extends Zone("graph")
  case SemanticContext extends Zone("semantic-context")
  case Reports extends Zone("reports")
  case Logs extends Zone("logs")
}
object Zone {
  def fromTag(tag: String): Option[Zone] = values.find(_.tag == tag)
}

/** One S3 object address. */
final case class ObjectRef(bucket: String, key: String) {
  def uri: String = s"s3://$bucket/$key"
}

/** Where one zone lives: a bucket and a non-empty key prefix (no leading or trailing `/`). */
final case class ZoneLocation(bucket: String, prefix: String)

/**
 * Storage-local layout settings (SE-P7-02). Every value is passed in; nothing has a default and no
 * bucket name is a literal. Binding these values to `core` configuration keys is pending
 * (BLOCKED-ON domain-engineer: `core` StorageConfig has rawBucket, curatedBucket and contextBucket
 * only, no per-zone prefixes and no reports or logs location).
 *
 * @param zones
 *   exactly the six zones
 * @param maxKeyBytes
 *   the longest object key accepted, in UTF-8 bytes (an S3 service limit: UNVERIFIED in SE-P7-01
 *   because mcp__aws-docs was unavailable; supplied by configuration, never assumed)
 */
final case class S3LayoutSettings(zones: Map[Zone, ZoneLocation], maxKeyBytes: Int)

/**
 * Versioned object keys (SE-P7-01; README L1368-1370). Every key embeds the 64-hex body of a `core`
 * run or load ID, so one key never names two different runs or loads:
 *
 *   - raw snapshots: `<raw>/snapshots/<source tag>/<snapshot>/<relative path>`
 *   - run outputs: `<zone>/runs/<runHex>/<relative path>` (curated, graph, semantic-context)
 *   - load artifacts: `<zone>/loads/<loadHex>/<relative path>` (graph, semantic-context, reports,
 *     logs), including
 *     - context text: `<semantic-context>/loads/<loadHex>/text/<chunkHex>.txt`
 *     - rejects: `<reports>/loads/<loadHex>/rejects/part-<NNNNN>.jsonl`
 *   - run files (SE-P7-13 item 2): `<graph>/runs/<runHex>/_parts.json` and `_run-manifest.json`
 *
 * SE-P7-13 item 3: there is no `load-report.json` key. The durable record of a load is its
 * RunLedger row (state, counts, `_parts.json` SHA-256, conditional writes) plus one
 * ArtifactRegistry row per stored object; an S3 copy of the in-memory opening-load report would be
 * an undocumented second record of the same facts, and it could not be create-once, because its
 * per-call fields (status `loaded` vs `skipped-already-loaded`, `written`) differ between a load
 * and its rerun.
 *
 * Relative paths are `/`-separated segments of `[A-Za-z0-9._=-]`, never `.` or `..`.
 */
final class S3Layout private (settings: S3LayoutSettings) {
  import S3Layout.*

  def location(zone: Zone): ZoneLocation = settings.zones(zone)

  /** The prefix of a zone, ending in `/`. */
  def zonePrefix(zone: Zone): ObjectRef = {
    val l = location(zone)
    ObjectRef(l.bucket, l.prefix + "/")
  }

  def rawSnapshot(
      source: SourceType,
      snapshot: String,
      relativePath: String
  ): Either[StoreError, ObjectRef] =
    for {
      s <-
        if (source != null && source.isDataset) Right(source)
        else Left(StoreError.Refused("source: raw snapshots belong to a dataset source"))
      n <- StoreIds.token(snapshot, "snapshot")
      p <- checkRelative(relativePath)
      r <- build(Zone.Raw, s"snapshots/${s.tag}/$n/$p")
    } yield r

  def runObject(zone: Zone, runId: String, relativePath: String): Either[StoreError, ObjectRef] =
    for {
      z <- allowed(zone, RunZones, "run outputs")
      h <- StoreIds.hexOf(IdKind.Run, runId, "runId")
      p <- checkRelative(relativePath)
      r <- build(z, s"runs/$h/$p")
    } yield r

  def loadObject(zone: Zone, loadId: String, relativePath: String): Either[StoreError, ObjectRef] =
    for {
      z <- allowed(zone, LoadZones, "load artifacts")
      h <- StoreIds.hexOf(IdKind.StoreLoad, loadId, "loadId")
      p <- checkRelative(relativePath)
      r <- build(z, s"loads/$h/$p")
    } yield r

  def contextText(loadId: String, chunkId: String): Either[StoreError, ObjectRef] =
    StoreIds
      .hexOf(IdKind.Chunk, chunkId, "chunkId")
      .flatMap(c => loadObject(Zone.SemanticContext, loadId, s"text/$c.txt"))

  def rejects(loadId: String, part: Int): Either[StoreError, ObjectRef] =
    if (part < 0 || part > 99999)
      Left(StoreError.Refused(s"part: $part is outside 0..99999"))
    else loadObject(Zone.Reports, loadId, f"rejects/part-$part%05d.jsonl")

  private def allowed(zone: Zone, zones: Set[Zone], what: String): Either[StoreError, Zone] =
    if (zone != null && zones.contains(zone)) Right(zone)
    else
      Left(
        StoreError.Refused(
          s"zone: ${Option(zone).map(_.tag).orNull} does not hold $what " +
            s"(allowed: ${zones.toVector.map(_.tag).sorted.mkString(", ")})"
        )
      )

  private def build(zone: Zone, rest: String): Either[StoreError, ObjectRef] = {
    val l = location(zone)
    val key = s"${l.prefix}/$rest"
    val bytes = key.getBytes(StandardCharsets.UTF_8).length
    if (bytes > settings.maxKeyBytes)
      Left(StoreError.Refused(s"key: $bytes bytes exceeds maxKeyBytes ${settings.maxKeyBytes}"))
    else Right(ObjectRef(l.bucket, key))
  }
}

object S3Layout {

  /** Zones that hold run outputs keyed by run ID. */
  val RunZones: Set[Zone] = Set(Zone.Curated, Zone.Graph, Zone.SemanticContext)

  /** Zones that hold load artifacts keyed by load ID. */
  val LoadZones: Set[Zone] = Set(Zone.Graph, Zone.SemanticContext, Zone.Reports, Zone.Logs)

  private val BucketName = "^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$".r
  private val Segment = "^[A-Za-z0-9._=-]+$".r

  /** Why `path` is not a relative key path, or None. */
  def relativeProblem(path: String): Option[String] =
    if (path == null || path.isEmpty) Some("missing")
    else if (path.startsWith("/") || path.endsWith("/")) Some("has a leading or trailing '/'")
    else {
      val segments = path.split("/", -1).toVector
      if (segments.exists(_.isEmpty)) Some("has an empty segment")
      else if (segments.exists(s => s == "." || s == "..")) Some("has a '.' or '..' segment")
      else if (segments.exists(s => !Segment.matches(s)))
        Some("segments must match [A-Za-z0-9._=-]+")
      else None
    }

  def checkRelative(path: String): Either[StoreError, String] =
    relativeProblem(path) match {
      case None => Right(path)
      case Some(why) => Left(StoreError.Refused(s"relativePath: $why"))
    }

  /** Every problem of the settings; empty when the layout can be built. */
  def problems(s: S3LayoutSettings): Vector[String] =
    if (s == null || s.zones == null) Vector("settings: missing")
    else {
      val missing =
        Zone.values.toVector.filterNot(s.zones.contains).map(z => s"zones.${z.tag}: missing")
      val each = Zone.values.toVector.flatMap { z =>
        s.zones.get(z).toVector.flatMap { l =>
          val f = s"zones.${z.tag}"
          if (l == null) Vector(s"$f: missing")
          else
            (if (l.bucket != null && BucketName.matches(l.bucket) && !l.bucket.contains(".."))
               Vector.empty
             else Vector(s"$f.bucket: not an S3 bucket name")) ++
              relativeProblem(l.prefix).toVector.map(why => s"$f.prefix: $why")
        }
      }
      val located = s.zones.toVector.collect {
        case (z, l) if l != null && l.bucket != null && l.prefix != null => (z, l)
      }
      val overlapping = for {
        (a, la) <- located
        (b, lb) <- located
        if a.ordinal < b.ordinal && la.bucket == lb.bucket &&
          (la.prefix == lb.prefix || lb.prefix.startsWith(la.prefix + "/") ||
            la.prefix.startsWith(lb.prefix + "/"))
      } yield s"zones.${a.tag}, zones.${b.tag}: prefixes overlap in bucket ${la.bucket}"
      val limit =
        if (s.maxKeyBytes >= 1) Vector.empty else Vector("maxKeyBytes: must be >= 1")
      missing ++ each ++ overlapping ++ limit
    }

  def from(s: S3LayoutSettings): Either[Vector[String], S3Layout] = {
    val p = problems(s)
    if (p.isEmpty) Right(new S3Layout(s)) else Left(p)
  }
}
