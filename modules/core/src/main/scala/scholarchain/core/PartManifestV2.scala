package scholarchain.core

import scala.util.control.NonFatal

import upickle.default.ReadWriter
import upickle.implicits.allowUnknownKeys

import Validated.{all, at, when}

/**
 * File format of one part (part-manifest.v2). Frozen tags.
 *
 *   - `jsonl`: UTF-8 JSON lines, each LF-terminated. The row count is the number of LF bytes and is
 *     verified from the bytes; a non-empty file must end in LF.
 *   - `parquet`: Apache Parquet, a binary format. Integrity is verified by byte count and SHA-256
 *     only. No newline rule applies and the bytes are never line-counted: the row count is taken
 *     from the Parquet footer or reported by the writer, recorded in the manifest, and is not
 *     re-derived by core (core has no Parquet dependency).
 *   - `csv`: delimited text whose quoted fields may contain LF (for example a Neptune bulk-load
 *     file), so LF bytes are not rows. Integrity by byte count and SHA-256; the writer reports the
 *     row count.
 */
enum PartFormat(val tag: String, val lineDelimited: Boolean) {
  case Jsonl extends PartFormat("jsonl", true)
  case Parquet extends PartFormat("parquet", false)
  case Csv extends PartFormat("csv", false)
}
object PartFormat {
  def fromTag(tag: String): Option[PartFormat] = values.find(_.tag == tag)
  given ReadWriter[PartFormat] = EnumCodec.tagged("PartFormat", values.toSeq, _.tag)

  /** Formats an analysis stage may export ([[config.AnalysisRunSettings.exportFormat]]). */
  val AnalysisExportFormats: Vector[PartFormat] = Vector(Parquet, Jsonl)
}

/**
 * Where a part's declared row count comes from. Frozen tags.
 *
 *   - `lf-count`: counted from the bytes (number of LF bytes); the only source for `jsonl`, and
 *     verified by [[PartManifestVerifier.verifyV2]].
 *   - `parquet-footer`: the sum of the row-group row counts in the Parquet footer, read by the
 *     producer after writing; `parquet` only.
 *   - `writer`: reported by the writer that produced the file; `parquet` or `csv`.
 */
enum RowCountSource(val tag: String) {
  case LfCount extends RowCountSource("lf-count")
  case ParquetFooter extends RowCountSource("parquet-footer")
  case Writer extends RowCountSource("writer")
}
object RowCountSource {
  def fromTag(tag: String): Option[RowCountSource] = values.find(_.tag == tag)
  given ReadWriter[RowCountSource] = EnumCodec.tagged("RowCountSource", values.toSeq, _.tag)

  /** The row-count sources each format accepts. */
  val allowed: Map[PartFormat, Set[RowCountSource]] = Map(
    PartFormat.Jsonl -> Set(LfCount),
    PartFormat.Parquet -> Set(ParquetFooter, Writer),
    PartFormat.Csv -> Set(Writer)
  )
}

/**
 * One file of a published run directory in a [[PartManifestV2]]. As [[PartFile]], plus the file's
 * `format` and the source of its declared `rowCount`.
 *
 * @param rowCount
 *   number of records: LF bytes for `jsonl`; from the Parquet footer or the writer for `parquet`;
 *   from the writer for `csv`
 */
@allowUnknownKeys(false)
final case class PartFileV2(
    path: String,
    recordSchemaVersion: String,
    format: PartFormat,
    rowCount: Long,
    rowCountSource: RowCountSource,
    byteCount: Long,
    sha256: String
) derives ReadWriter

/**
 * Integrity manifest of one published run directory, schema `part-manifest.v2`. Same layout and
 * publication rules as [[PartManifest]] (file name, markers, sorted paths, the run manifest always
 * listed as one `jsonl` row), with a declared format per file so that the verifier applies the
 * checks of that format ([[PartManifestVerifier.verifyV2]]).
 *
 * Compatibility: `part-manifest.v1` is unchanged and still decodes and verifies exactly as before.
 * A v1 manifest is equivalent to a v2 manifest whose every file is `jsonl` with `lf-count`
 * ([[PartManifestV2.fromV1]]); [[PartManifests]] reads either version. Producers of binary exports
 * (Parquet) must write v2; v1 has no way to declare a binary file.
 */
@allowUnknownKeys(false)
final case class PartManifestV2(schemaVersion: String, runId: String, files: Vector[PartFileV2])
    derives ReadWriter

object PartManifestV2 {

  /**
   * Smallest possible Parquet file: the 4-byte magic `PAR1`, a 4-byte footer length and the closing
   * magic (a property of the format, not a tunable threshold).
   */
  val ParquetMinimumBytes: Long = 12L

  /** Sorts the files by path and validates the result. */
  def build(
      runId: String,
      files: Vector[PartFileV2]
  ): Either[Vector[ValidationError], PartManifestV2] =
    if (files == null || files.exists(_ == null))
      Left(Vector(ValidationError.MissingField("files")))
    else {
      val m = PartManifestV2(
        SchemaVersions.PartManifestV2,
        runId,
        files.sortWith((a, b) => Canonical.compareUtf8(a.path, b.path) < 0)
      )
      val errors = contract.validate(m)
      if (errors.isEmpty) Right(m) else Left(errors)
    }

  /** The v2 view of a v1 manifest: every file `jsonl`, rows counted from LF bytes. */
  def fromV1(m: PartManifest): PartManifestV2 =
    PartManifestV2(
      SchemaVersions.PartManifestV2,
      m.runId,
      Option(m.files).getOrElse(Vector.empty).map { f =>
        if (f == null) null
        else
          PartFileV2(
            f.path,
            f.recordSchemaVersion,
            PartFormat.Jsonl,
            f.rowCount,
            RowCountSource.LfCount,
            f.byteCount,
            f.sha256
          )
      }
    )

  /** The run manifest this part manifest is bound to (as [[PartManifest.checkRunManifest]]). */
  def checkRunManifest(m: PartManifestV2, rm: RunManifest): Vector[ValidationError] =
    RunId.of(rm) match {
      case Left(e) => Vector(e)
      case Right(id) =>
        when(m == null || id != m.runId)(
          ValidationError.InvalidValue("runId", s"the run manifest gives $id")
        )
    }

  private def fileChecks(f: PartFileV2, p: String): Vector[ValidationError] =
    if (f == null) Vector(ValidationError.MissingField(p))
    else {
      val common = all(
        PartManifest
          .pathProblem(f.path)
          .toVector
          .map(why => ValidationError.InvalidValue(at(p, "path"), why)),
        when(f.path != null && PartManifest.Markers.contains(f.path))(
          ValidationError.InvalidValue(at(p, "path"), "a marker file is never listed")
        ),
        when(
          f.recordSchemaVersion == null || !SchemaVersions.all.values
            .exists(_ == f.recordSchemaVersion)
        )(
          ValidationError.InvalidValue(
            at(p, "recordSchemaVersion"),
            s"'${f.recordSchemaVersion}' is not a current ScholarChain record schema"
          )
        ),
        when(f.format == null)(ValidationError.MissingField(at(p, "format"))),
        when(f.rowCountSource == null)(ValidationError.MissingField(at(p, "rowCountSource"))),
        Validated.nonNegative(at(p, "rowCount"), f.rowCount),
        Validated.nonNegative(at(p, "byteCount"), f.byteCount),
        when(f.sha256 == null || !Canonical.isHex64(f.sha256))(
          ValidationError.InvalidValue(at(p, "sha256"), "expected 64 lowercase hex characters")
        ),
        when(f.byteCount == 0 && f.sha256 != null && f.sha256 != PartManifest.EmptySha256)(
          ValidationError
            .InvalidValue(at(p, "sha256"), "an empty file has the SHA-256 of zero bytes")
        )
      )
      val source =
        if (f.format == null || f.rowCountSource == null) Vector.empty
        else
          when(!RowCountSource.allowed(f.format).contains(f.rowCountSource))(
            ValidationError.InvalidValue(
              at(p, "rowCountSource"),
              s"'${f.rowCountSource.tag}' is not a row-count source of ${f.format.tag} (allowed: ${RowCountSource
                  .allowed(f.format)
                  .map(_.tag)
                  .toVector
                  .sorted
                  .mkString(", ")})"
            )
          )
      val perFormat = Option(f.format).toVector.flatMap {
        case PartFormat.Jsonl =>
          all(
            when(f.rowCount > f.byteCount && f.rowCount >= 0 && f.byteCount >= 0)(
              ValidationError
                .InvalidValue(at(p, "rowCount"), "more rows than bytes (each row ends in LF)")
            ),
            when(f.byteCount > 0 && f.rowCount == 0)(
              ValidationError.InvalidValue(
                at(p, "rowCount"),
                "a non-empty file ends in LF, so it has at least one row"
              )
            )
          )
        case PartFormat.Parquet =>
          when(f.byteCount >= 0 && f.byteCount < ParquetMinimumBytes)(
            ValidationError.InvalidValue(
              at(p, "byteCount"),
              s"a Parquet file has at least $ParquetMinimumBytes bytes"
            )
          )
        case PartFormat.Csv =>
          when(f.byteCount == 0 && f.rowCount > 0)(
            ValidationError.InvalidValue(at(p, "rowCount"), "an empty file has no rows")
          )
      }
      common ++ source ++ perFormat
    }

  given contract: RecordContract[PartManifestV2] =
    new RecordContract[PartManifestV2]("PartManifestV2", SchemaVersions.PartManifestV2, false) {
      def schemaVersionOf(t: PartManifestV2): String = t.schemaVersion
      def provenanceOf(t: PartManifestV2): Provenance = null
      protected def fields(t: PartManifestV2, p: String): Vector[ValidationError] = {
        val files = Option(t.files).getOrElse(Vector.empty)
        val runManifest = files.find(f => f != null && f.path == PartManifest.RunManifestFile)
        val rm = PartManifest.RunManifestFile
        all(
          StableId.check(IdKind.Run, t.runId, at(p, "runId")),
          when(t.files == null || files.isEmpty)(ValidationError.MissingField(at(p, "files"))),
          files.zipWithIndex.flatMap { case (f, i) => fileChecks(f, at(p, s"files[$i]")) },
          PartManifest.pathSetChecks(p, files.map(f => if (f == null) null else f.path)),
          runManifest match {
            case None =>
              Vector(ValidationError.InvalidValue(at(p, "files"), s"must list $rm"))
            case Some(f) =>
              all(
                when(f.recordSchemaVersion != SchemaVersions.RunManifest)(
                  ValidationError.InvalidValue(
                    at(p, s"files[$rm].recordSchemaVersion"),
                    s"must be ${SchemaVersions.RunManifest}"
                  )
                ),
                when(f.format != PartFormat.Jsonl)(
                  ValidationError.InvalidValue(at(p, s"files[$rm].format"), "must be jsonl")
                ),
                when(f.rowCount != 1)(
                  ValidationError.InvalidValue(
                    at(p, s"files[$rm].rowCount"),
                    "the run manifest is exactly one LF-terminated row"
                  )
                )
              )
          }
        )
      }
    }
}

/**
 * Reads part manifests of either version. Each contract still accepts exactly its own version; this
 * reader dispatches on `schemaVersion`, and [[verify]] applies the verifier of that version.
 * Existing v1 readers ([[PartManifest.contract]], [[PartManifestVerifier.verify]]) are unchanged.
 */
object PartManifests {

  /** Every part manifest schema version this build reads, oldest first. */
  val readable: Vector[String] = Vector(SchemaVersions.PartManifest, SchemaVersions.PartManifestV2)

  /** Decodes a v1 manifest as `Left` and a v2 manifest as `Right`, with full validation. */
  def decode(
      json: String
  ): Either[Vector[ValidationError], Either[PartManifest, PartManifestV2]] = {
    val version: Either[Vector[ValidationError], String] =
      try
        ujson.read(json) match {
          case o: ujson.Obj =>
            o.value.get("schemaVersion") match {
              case Some(ujson.Str(s)) => Right(s)
              case _ => Left(Vector(ValidationError.MissingSchemaVersion("PartManifest")))
            }
          case _ =>
            Left(Vector(ValidationError.MalformedJson("top-level value is not a JSON object")))
        }
      catch {
        case NonFatal(e) =>
          Left(Vector(ValidationError.MalformedJson(String.valueOf(e.getMessage))))
      }
    version.flatMap {
      case SchemaVersions.PartManifest => Json.decode[PartManifest](json).map(Left(_))
      case SchemaVersions.PartManifestV2 => Json.decode[PartManifestV2](json).map(Right(_))
      case other =>
        Left(Vector(ValidationError.UnsupportedSchemaVersion("PartManifest", other, readable)))
    }
  }

  /** The verifier of the manifest's own version. */
  def verify(
      manifest: Either[PartManifest, PartManifestV2],
      expectedRunId: String,
      observed: Vector[ObservedFile]
  ): Vector[ValidationError] =
    manifest match {
      case null => Vector(ValidationError.MissingField("partManifest"))
      case Left(v1) => PartManifestVerifier.verify(v1, expectedRunId, observed)
      case Right(v2) => PartManifestVerifier.verifyV2(v2, expectedRunId, observed)
    }

  /** The v2 view of either version ([[PartManifestV2.fromV1]] for v1). */
  def asV2(manifest: Either[PartManifest, PartManifestV2]): PartManifestV2 =
    manifest.fold(PartManifestV2.fromV1, identity)
}
