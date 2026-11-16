package scholarchain.core

import java.security.MessageDigest

import upickle.default.ReadWriter
import upickle.implicits.allowUnknownKeys

import Validated.{all, at, when}

/**
 * One file of a published run directory, as recorded in its [[PartManifest]].
 *
 * @param path
 *   path relative to the run directory: `/`-separated segments of `[A-Za-z0-9._=+-]`, none empty,
 *   `.` or `..`; never absolute, never with a backslash, never one of the two marker files
 * @param recordSchemaVersion
 *   the ScholarChain record schema of every row of the file (a value of [[SchemaVersions.all]])
 * @param rowCount
 *   number of rows: rows are LF-terminated (byte 0x0A), so this is the number of LF bytes
 * @param byteCount
 *   exact file size in bytes
 * @param sha256
 *   SHA-256 of the file's bytes, 64 lowercase hex
 */
@allowUnknownKeys(false)
final case class PartFile(
    path: String,
    recordSchemaVersion: String,
    rowCount: Long,
    byteCount: Long,
    sha256: String
) derives ReadWriter

/**
 * Integrity manifest of one published run directory (schema `part-manifest.v1`, contract C5),
 * written as [[PartManifest.FileName]] in the run directory. It lists every file of the directory
 * except itself and [[PartManifest.SuccessMarker]], including [[PartManifest.RunManifestFile]].
 * Publication order: data files, then the run manifest, then this manifest, then the success marker
 * last. Readers verify a copy with [[PartManifestVerifier.verify]] before using it.
 *
 * Like [[RunManifest]], it is a control record about files, not a data record, and carries no
 * Provenance: `runId` binds it to the run whose manifest it lists. Core does no file I/O; the
 * producing and consuming modules read the files and pass observations ([[ObservedFile]]).
 *
 * Validation cost, for F listed files: the per-file, ordering and case-folding checks take linear
 * passes over the path text plus O(F log F) path comparisons. The file-versus-directory check
 * indexes only valid paths (every path with a [[PartManifest.pathProblem]] is refused and skipped,
 * so each indexed path has at most L = [[PartManifest.MaxPathLength]] characters and d <= L / 2
 * separators); it copies one prefix per separator, O(F * L * d) time and memory, bounded by F * L *
 * L / 2 characters. It is not linear in the total path length.
 *
 * @param runId
 *   the run the directory holds ([[RunId.of]] of its run manifest)
 * @param files
 *   every listed file, sorted by the UTF-8 bytes of `path` without duplicates, non-empty
 */
@allowUnknownKeys(false)
final case class PartManifest(schemaVersion: String, runId: String, files: Vector[PartFile])
    derives ReadWriter

object PartManifest {

  /** File name of the part manifest inside a run directory. */
  val FileName = "_parts.json"

  /** File name of the success marker, written last. */
  val SuccessMarker = "_SUCCESS"

  /** File name of the run manifest; always listed, as one row of `run-manifest` schema. */
  val RunManifestFile = "_run-manifest.json"

  /** The two marker files that a part manifest never lists. */
  val Markers: Set[String] = Set(FileName, SuccessMarker)

  /** SHA-256 of zero bytes. */
  val EmptySha256: String = Canonical.sha256Hex(Array.emptyByteArray)

  private val Segment = "^[A-Za-z0-9._=+-]+$".r
  val MaxPathLength = 1024

  /** Why `path` is not a valid relative file path of a run directory, or None. */
  def pathProblem(path: String): Option[String] =
    if (path == null || path.isEmpty) Some("missing")
    else if (path.length > MaxPathLength) Some(s"longer than $MaxPathLength characters")
    else if (path.contains("\\")) Some("contains a backslash")
    else if (path.startsWith("/")) Some("is absolute")
    else {
      val segments = path.split("/", -1).toVector
      if (segments.exists(_.isEmpty)) Some("has an empty segment")
      else if (segments.exists(s => s == "." || s == "..")) Some("has a '.' or '..' segment")
      else if (!segments.forall(Segment.matches)) Some("has a segment outside [A-Za-z0-9._=+-]")
      else None
    }

  /** Sorts the files by path and validates the result. */
  def build(runId: String, files: Vector[PartFile]): Either[Vector[ValidationError], PartManifest] =
    if (files == null || files.exists(_ == null))
      Left(Vector(ValidationError.MissingField("files")))
    else {
      val m = PartManifest(
        SchemaVersions.PartManifest,
        runId,
        files.sortWith((a, b) => Canonical.compareUtf8(a.path, b.path) < 0)
      )
      val errors = contract.validate(m)
      if (errors.isEmpty) Right(m) else Left(errors)
    }

  /** True when the run manifest `rm` is the one this part manifest is bound to. */
  def checkRunManifest(m: PartManifest, rm: RunManifest): Vector[ValidationError] =
    RunId.of(rm) match {
      case Left(e) => Vector(e)
      case Right(id) =>
        when(m == null || id != m.runId)(
          ValidationError.InvalidValue("runId", s"the run manifest gives $id")
        )
    }

  /**
   * The path-set rules shared by every part manifest version (v1 and v2): paths sorted by UTF-8
   * bytes without duplicates, no two paths that differ only in letter case, and no listed file that
   * is also a directory of another listed path. `rawPaths` is the `path` of every listed file in
   * listed order (null for a null file or path).
   */
  private[core] def pathSetChecks(p: String, rawPaths: Vector[String]): Vector[ValidationError] = {
    val paths = rawPaths.filter(_ != null)
    // Only valid paths (pathProblem empty, so at most MaxPathLength characters) enter the
    // directory index; an invalid path is already refused by the per-file checks (CR-P6-04 m1).
    // Every directory prefix of a valid path is itself valid, so no overlap among valid paths is
    // lost.
    val validPaths = paths.filter(pathProblem(_).isEmpty)
    val directoryOwner: Map[String, String] =
      validPaths.foldLeft(Map.empty[String, String]) { (m, b) =>
        b.indices.filter(b.charAt(_) == '/').foldLeft(m) { (acc, i) =>
          val dir = b.substring(0, i)
          if (acc.contains(dir)) acc else acc.updated(dir, b)
        }
      }
    all(
      Validated.strictlyAscending(at(p, "files.path"), rawPaths),
      paths.groupBy(_.toLowerCase(java.util.Locale.ROOT)).toVector.sortBy(_._1).flatMap {
        case (_, same) =>
          when(same.distinct.size > 1)(
            ValidationError.InvalidValue(
              at(p, "files.path"),
              s"paths differ only in letter case: ${same.distinct.sorted.mkString(", ")}"
            )
          )
      },
      // A valid file path that is also a directory prefix of another valid listed path (first
      // listed path kept per prefix, the one `paths.find` named before). Each valid path adds one
      // prefix copy per '/', so the index costs O(L * d) characters per path, d <= L / 2.
      validPaths.flatMap { a =>
        directoryOwner.get(a).toVector.map { b =>
          ValidationError.InvalidValue(
            at(p, "files.path"),
            s"'$a' is listed as a file but is a directory of '$b'"
          )
        }
      }
    )
  }

  private def fileChecks(f: PartFile, p: String): Vector[ValidationError] =
    if (f == null) Vector(ValidationError.MissingField(p))
    else
      all(
        pathProblem(f.path).toVector.map(why => ValidationError.InvalidValue(at(p, "path"), why)),
        when(f.path != null && Markers.contains(f.path))(
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
        Validated.nonNegative(at(p, "rowCount"), f.rowCount),
        Validated.nonNegative(at(p, "byteCount"), f.byteCount),
        when(f.rowCount > f.byteCount && f.rowCount >= 0 && f.byteCount >= 0)(
          ValidationError
            .InvalidValue(at(p, "rowCount"), "more rows than bytes (each row ends in LF)")
        ),
        when(f.byteCount > 0 && f.rowCount == 0)(
          ValidationError.InvalidValue(
            at(p, "rowCount"),
            "a non-empty file ends in LF, so it has at least one row"
          )
        ),
        when(f.sha256 == null || !Canonical.isHex64(f.sha256))(
          ValidationError.InvalidValue(at(p, "sha256"), "expected 64 lowercase hex characters")
        ),
        when(f.byteCount == 0 && f.sha256 != null && f.sha256 != EmptySha256)(
          ValidationError
            .InvalidValue(at(p, "sha256"), "an empty file has the SHA-256 of zero bytes")
        )
      )

  given contract: RecordContract[PartManifest] =
    new RecordContract[PartManifest]("PartManifest", SchemaVersions.PartManifest, false) {
      def schemaVersionOf(t: PartManifest): String = t.schemaVersion
      def provenanceOf(t: PartManifest): Provenance = null
      protected def fields(t: PartManifest, p: String): Vector[ValidationError] = {
        val files = Option(t.files).getOrElse(Vector.empty)
        val runManifest = files.find(f => f != null && f.path == RunManifestFile)
        all(
          StableId.check(IdKind.Run, t.runId, at(p, "runId")),
          when(t.files == null || files.isEmpty)(ValidationError.MissingField(at(p, "files"))),
          files.zipWithIndex.flatMap { case (f, i) => fileChecks(f, at(p, s"files[$i]")) },
          pathSetChecks(p, files.map(f => if (f == null) null else f.path)),
          runManifest match {
            case None =>
              Vector(
                ValidationError.InvalidValue(at(p, "files"), s"must list $RunManifestFile")
              )
            case Some(f) =>
              all(
                when(f.recordSchemaVersion != SchemaVersions.RunManifest)(
                  ValidationError.InvalidValue(
                    at(p, s"files[$RunManifestFile].recordSchemaVersion"),
                    s"must be ${SchemaVersions.RunManifest}"
                  )
                ),
                when(f.rowCount != 1)(
                  ValidationError.InvalidValue(
                    at(p, s"files[$RunManifestFile].rowCount"),
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
 * What a reader observed for one regular file of a run directory. Built by the reader from the
 * file's bytes ([[ObservedFile.of]] or [[ObservedFile.Accumulator]]); core never opens a file. The
 * reader lists every regular file of the directory, recursively, including the marker files, and
 * refuses directories that hold anything other than regular files and directories (symbolic links
 * included) before it calls the verifier.
 *
 * @param rowCount
 *   number of LF bytes
 * @param endsWithLf
 *   true when the file is non-empty and its last byte is LF
 */
final case class ObservedFile(
    path: String,
    byteCount: Long,
    rowCount: Long,
    sha256: String,
    endsWithLf: Boolean
)

object ObservedFile {

  /** Observation of a file whose complete content is `bytes`. */
  def of(path: String, bytes: Array[Byte]): ObservedFile = {
    val acc = new Accumulator
    acc.update(bytes, 0, bytes.length)
    acc.result(path)
  }

  /**
   * Streaming observation for large files: feed every byte of the file in order, then call `result`
   * once. In-memory only (no I/O).
   */
  final class Accumulator {
    private val digest = MessageDigest.getInstance("SHA-256")
    private var bytes = 0L
    private var lfs = 0L
    private var last: Int = -1
    private var done = false

    def update(chunk: Array[Byte], offset: Int, length: Int): Unit = {
      require(!done, "result was already taken")
      digest.update(chunk, offset, length)
      var i = offset
      while (i < offset + length) {
        if (chunk(i) == '\n'.toByte) lfs += 1
        i += 1
      }
      if (length > 0) last = chunk(offset + length - 1) & 0xff
      bytes += length
    }

    def result(path: String): ObservedFile = {
      require(!done, "result was already taken")
      done = true
      val hex = digest.digest().map(b => f"${b & 0xff}%02x").mkString
      ObservedFile(path, bytes, lfs, hex, bytes > 0 && last == '\n'.toInt)
    }
  }
}

/**
 * Pure verification of an observed run directory against its [[PartManifest]] (no I/O). Detects a
 * truncated or partial copy: the exact file set (no missing and no extra file), and for each file
 * the byte count, the row count, the SHA-256 and the final LF of a non-empty file. Both marker
 * files must be present; they are not compared.
 */
object PartManifestVerifier {

  /**
   * Every problem, empty when the observed directory is exactly the published run `expectedRunId`.
   */
  def verify(
      manifest: PartManifest,
      expectedRunId: String,
      observed: Vector[ObservedFile]
  ): Vector[ValidationError] = {
    val manifestErrors =
      if (manifest == null) Vector(ValidationError.MissingField("partManifest"))
      else PartManifest.contract.validate(manifest)
    if (manifestErrors.nonEmpty) manifestErrors
    else
      compare(
        manifest.runId,
        expectedRunId,
        manifest.files.map(f => Listed(f.path, f.byteCount, f.rowCount, f.sha256, true)),
        observed
      )
  }

  /**
   * Every problem, empty when the observed directory is exactly the published run `expectedRunId`
   * described by a v2 manifest ([[PartManifestV2]], DE-P6-02). File set and markers as in
   * [[verify]]. Per file: byte count and SHA-256 always; for `jsonl` also the LF row count and the
   * final LF. For `parquet` and `csv` the row count is the declared value (Parquet footer or
   * writer) and is not compared with the bytes, and no newline rule applies: a binary file that
   * does not end in LF is accepted when its byte count and SHA-256 match.
   */
  def verifyV2(
      manifest: PartManifestV2,
      expectedRunId: String,
      observed: Vector[ObservedFile]
  ): Vector[ValidationError] = {
    val manifestErrors =
      if (manifest == null) Vector(ValidationError.MissingField("partManifest"))
      else PartManifestV2.contract.validate(manifest)
    if (manifestErrors.nonEmpty) manifestErrors
    else
      compare(
        manifest.runId,
        expectedRunId,
        manifest.files
          .map(f => Listed(f.path, f.byteCount, f.rowCount, f.sha256, f.format.lineDelimited)),
        observed
      )
  }

  /**
   * One listed file as the comparison sees it. `lineDelimited` files (every v1 file, and v2 `jsonl`
   * files) are also compared by LF row count and must end in LF when non-empty; other files (v2
   * `parquet` and `csv`) are compared by byte count and SHA-256 only.
   */
  private[core] final case class Listed(
      path: String,
      byteCount: Long,
      rowCount: Long,
      sha256: String,
      lineDelimited: Boolean
  )

  /** The comparison shared by every part manifest version; the manifest is already valid. */
  private[core] def compare(
      manifestRunId: String,
      expectedRunId: String,
      files: Vector[Listed],
      observed: Vector[ObservedFile]
  ): Vector[ValidationError] = {
    locally {
      val obs = Option(observed).getOrElse(Vector.empty)
      val listed = files.map(f => f.path -> f).toMap
      val byPath = obs.filter(o => o != null && o.path != null).groupBy(_.path)
      def field(path: String) = s"files[$path]"
      all(
        when(manifestRunId != expectedRunId)(
          ValidationError.InvalidValue("runId", s"the directory holds run $expectedRunId")
        ),
        when(observed == null || obs.exists(o => o == null || o.path == null))(
          ValidationError.MissingField("observed")
        ),
        byPath.toVector.sortBy(_._1).flatMap { case (path, same) =>
          when(same.size > 1)(ValidationError.InvalidValue(field(path), "observed more than once"))
        },
        PartManifest.Markers.toVector.sorted.flatMap { m =>
          when(!byPath.contains(m))(
            ValidationError.InvalidValue(field(m), "marker file missing from the run directory")
          )
        },
        files.flatMap { f =>
          when(!byPath.contains(f.path))(
            ValidationError
              .InvalidValue(field(f.path), "listed file missing from the run directory")
          )
        },
        byPath.keys.toVector.sorted
          .filterNot(PartManifest.Markers.contains)
          .filterNot(listed.contains)
          .map(path =>
            ValidationError.InvalidValue(field(path), "extra file not listed in the part manifest")
          ),
        files.flatMap { f =>
          byPath.get(f.path).flatMap(_.headOption).toVector.flatMap { o =>
            all(
              when(o.byteCount != f.byteCount)(
                ValidationError.InvalidValue(
                  field(f.path),
                  s"byteCount: listed ${f.byteCount}, observed ${o.byteCount}"
                )
              ),
              when(f.lineDelimited && o.rowCount != f.rowCount)(
                ValidationError.InvalidValue(
                  field(f.path),
                  s"rowCount: listed ${f.rowCount}, observed ${o.rowCount}"
                )
              ),
              when(o.sha256 != f.sha256)(
                ValidationError.InvalidValue(
                  field(f.path),
                  s"sha256: listed ${f.sha256}, observed ${o.sha256}"
                )
              ),
              when(f.lineDelimited && o.byteCount > 0 && !o.endsWithLf)(
                ValidationError.InvalidValue(field(f.path), "a non-empty file must end in LF")
              )
            )
          }
        }
      )
    }
  }
}
