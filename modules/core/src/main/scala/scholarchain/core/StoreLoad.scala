package scholarchain.core

import upickle.default.ReadWriter
import upickle.implicits.allowUnknownKeys

import Validated.{all, at, when}

/**
 * Downstream store a load manifest targets (contract C7). Frozen tags. Data contract only: no
 * endpoint, bucket, table, index or account appears in a load manifest; the loader (module
 * `storage`) resolves the physical target from configuration.
 *
 *   - `neptune-bulk-load`: the Neptune bulk loader, which reads CSV files ([[PartFormat.Csv]]
 *     only).
 *   - `dynamodb`, `s3`, `s3-vectors`: any part format.
 */
enum TargetStore(val tag: String, val formats: Set[PartFormat]) {
  case NeptuneBulkLoad extends TargetStore("neptune-bulk-load", Set(PartFormat.Csv))
  case DynamoDb extends TargetStore("dynamodb", PartFormat.values.toSet)
  case S3 extends TargetStore("s3", PartFormat.values.toSet)
  case S3Vectors extends TargetStore("s3-vectors", PartFormat.values.toSet)
}
object TargetStore {
  def fromTag(tag: String): Option[TargetStore] = values.find(_.tag == tag)
  given ReadWriter[TargetStore] = EnumCodec.tagged("TargetStore", values.toSeq, _.tag)
}

/**
 * One part to load: a copy of its entry in the source run's part manifest (path relative to the run
 * directory, record schema, format, declared rows, bytes, SHA-256) plus the logical role it fills
 * in the target store (a token such as `researcher-vertices`; the loader maps roles to physical
 * names from configuration).
 */
@allowUnknownKeys(false)
final case class LoadItem(
    path: String,
    recordSchemaVersion: String,
    format: PartFormat,
    rowCount: Long,
    byteCount: Long,
    sha256: String,
    targetRole: String
) derives ReadWriter

/**
 * Manifest of one load into a downstream store (schema `store-load-manifest.v1`, contract C7). A
 * control record about files, like [[PartManifest]]: it carries no Provenance, and `sourceRunId`
 * binds it to the published run whose parts it loads. Integrity reuses the part-manifest scheme:
 * `partManifestSha256` is the SHA-256 of the exact `_parts.json` bytes of the source run, and every
 * item equals its entry in that manifest ([[StoreLoadManifest.checkAgainst]]). The loader verifies
 * the source run with [[PartManifests.verify]] before it loads anything.
 *
 * @param loadId
 *   [[StoreLoadId.of]](targetStore, sourceRunId, partManifestSha256, (path, targetRole) of every
 *   item): a different role map over the same parts is a different load
 * @param items
 *   sorted by the UTF-8 bytes of `path`, without duplicates, non-empty
 */
@allowUnknownKeys(false)
final case class StoreLoadManifest(
    schemaVersion: String,
    loadId: String,
    targetStore: TargetStore,
    sourceRunId: String,
    partManifestSha256: String,
    items: Vector[LoadItem]
) derives ReadWriter

object StoreLoadManifest {

  /** Builds a load manifest from the listed parts of a part manifest (either version). */
  def build(
      targetStore: TargetStore,
      partManifest: Either[PartManifest, PartManifestV2],
      partManifestSha256: String,
      roles: Map[String, String]
  ): Either[Vector[ValidationError], StoreLoadManifest] =
    if (partManifest == null) Left(Vector(ValidationError.MissingField("partManifest")))
    else {
      val v2 = PartManifests.asV2(partManifest)
      val selected = Option(roles).getOrElse(Map.empty)
      val missing = selected.keys.toVector.sorted.filterNot(p =>
        Option(v2.files).getOrElse(Vector.empty).exists(f => f != null && f.path == p)
      )
      if (missing.nonEmpty)
        Left(
          missing
            .map(p => ValidationError.InvalidValue(s"items[$p]", "not listed in the part manifest"))
        )
      else {
        val items = v2.files
          .filter(f => f != null && selected.contains(f.path))
          .map(f =>
            LoadItem(
              f.path,
              f.recordSchemaVersion,
              f.format,
              f.rowCount,
              f.byteCount,
              f.sha256,
              selected(f.path)
            )
          )
        StoreLoadId.of(
          targetStore,
          v2.runId,
          partManifestSha256,
          items.map(i => (i.path, i.targetRole))
        ) match {
          case Left(e) => Left(Vector(e))
          case Right(id) =>
            val m = StoreLoadManifest(
              SchemaVersions.StoreLoadManifest,
              id,
              targetStore,
              v2.runId,
              partManifestSha256,
              items
            )
            val errors = contract.validate(m) ++ checkAgainst(m, partManifest, partManifestSha256)
            if (errors.isEmpty) Right(m) else Left(errors)
        }
      }
    }

  /**
   * The load manifest names exactly listed parts of the source run's part manifest: the same run,
   * the same part manifest bytes (by SHA-256, observed by the caller from the `_parts.json` file),
   * and every item equal to its part entry. Never lists the run manifest or a marker file.
   */
  def checkAgainst(
      m: StoreLoadManifest,
      partManifest: Either[PartManifest, PartManifestV2],
      observedPartManifestSha256: String
  ): Vector[ValidationError] =
    if (m == null || partManifest == null)
      Vector(ValidationError.MissingField("loadManifest,partManifest"))
    else {
      val v2 = PartManifests.asV2(partManifest)
      val listed =
        Option(v2.files).getOrElse(Vector.empty).filter(_ != null).map(f => f.path -> f).toMap
      all(
        when(m.sourceRunId != v2.runId)(
          ValidationError.InvalidValue("sourceRunId", s"the part manifest is for run ${v2.runId}")
        ),
        when(m.partManifestSha256 != observedPartManifestSha256)(
          ValidationError.InvalidValue(
            "partManifestSha256",
            s"the observed _parts.json has sha256 $observedPartManifestSha256"
          )
        ),
        Option(m.items).getOrElse(Vector.empty).filter(_ != null).flatMap { it =>
          val f = s"items[${it.path}]"
          listed.get(it.path) match {
            case None => Vector(ValidationError.InvalidValue(f, "not listed in the part manifest"))
            case Some(pf) =>
              when(
                pf.recordSchemaVersion != it.recordSchemaVersion || pf.format != it.format ||
                  pf.rowCount != it.rowCount || pf.byteCount != it.byteCount || pf.sha256 != it.sha256
              )(ValidationError.InvalidValue(f, "differs from its part manifest entry"))
          }
        }
      )
    }

  given contract: RecordContract[StoreLoadManifest] =
    new RecordContract[StoreLoadManifest](
      "StoreLoadManifest",
      SchemaVersions.StoreLoadManifest,
      false
    ) {
      def schemaVersionOf(t: StoreLoadManifest): String = t.schemaVersion
      def provenanceOf(t: StoreLoadManifest): Provenance = null
      protected def fields(t: StoreLoadManifest, p: String): Vector[ValidationError] = {
        val items = Option(t.items).getOrElse(Vector.empty)
        val paths = items.map(i => if (i == null) null else i.path)
        all(
          when(t.targetStore == null)(ValidationError.MissingField(at(p, "targetStore"))),
          StableId.check(IdKind.Run, t.sourceRunId, at(p, "sourceRunId")),
          when(t.partManifestSha256 == null || !Canonical.isHex64(t.partManifestSha256))(
            ValidationError
              .InvalidValue(at(p, "partManifestSha256"), "expected 64 lowercase hex characters")
          ),
          when(t.items == null || items.isEmpty)(ValidationError.MissingField(at(p, "items"))),
          items.zipWithIndex.flatMap { case (it, i) =>
            val f = at(p, s"items[$i]")
            if (it == null) Vector(ValidationError.MissingField(f))
            else
              all(
                PartManifest
                  .pathProblem(it.path)
                  .toVector
                  .map(why => ValidationError.InvalidValue(at(f, "path"), why)),
                when(
                  it.path != null &&
                    (PartManifest.Markers
                      .contains(it.path) || it.path == PartManifest.RunManifestFile)
                )(
                  ValidationError.InvalidValue(
                    at(f, "path"),
                    "markers and the run manifest are never loaded"
                  )
                ),
                when(
                  it.recordSchemaVersion == null ||
                    !SchemaVersions.all.values.exists(_ == it.recordSchemaVersion)
                )(
                  ValidationError.InvalidValue(
                    at(f, "recordSchemaVersion"),
                    s"'${it.recordSchemaVersion}' is not a current ScholarChain record schema"
                  )
                ),
                when(it.format == null)(ValidationError.MissingField(at(f, "format"))),
                when(
                  it.format != null && t.targetStore != null && !t.targetStore.formats
                    .contains(it.format)
                )(
                  ValidationError.InvalidValue(
                    at(f, "format"),
                    s"${t.targetStore.tag} does not load ${it.format.tag} files"
                  )
                ),
                Validated.nonNegative(at(f, "rowCount"), it.rowCount),
                Validated.nonNegative(at(f, "byteCount"), it.byteCount),
                when(it.sha256 == null || !Canonical.isHex64(it.sha256))(
                  ValidationError
                    .InvalidValue(at(f, "sha256"), "expected 64 lowercase hex characters")
                ),
                AssignmentRules.token(at(f, "targetRole"), it.targetRole)
              )
          },
          Validated.strictlyAscending(at(p, "items.path"), paths),
          StableId.check(IdKind.StoreLoad, t.loadId, at(p, "loadId")),
          if (paths.exists(_ == null) || paths.isEmpty || t.targetStore == null) Vector.empty
          else
            StoreLoadId.of(
              t.targetStore,
              t.sourceRunId,
              t.partManifestSha256,
              items.map(i => (i.path, i.targetRole))
            ) match {
              case Right(expected) =>
                when(expected != t.loadId)(
                  ValidationError.InvalidValue(at(p, "loadId"), s"expected $expected")
                )
              case Left(_) => Vector.empty
            }
        )
      }
    }
}
