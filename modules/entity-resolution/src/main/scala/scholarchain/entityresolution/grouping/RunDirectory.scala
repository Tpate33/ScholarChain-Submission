package scholarchain.entityresolution.grouping

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}
import java.nio.file.{
  DirectoryNotEmptyException,
  FileAlreadyExistsException,
  Files,
  LinkOption,
  Path,
  StandardCopyOption,
  StandardOpenOption
}
import java.nio.file.attribute.BasicFileAttributes

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import scholarchain.core.{
  Canonical,
  Json,
  ObservedFile,
  PartFile,
  PartManifest,
  PartManifestVerifier,
  RunId,
  RunManifest
}
import scholarchain.entityresolution.decisions.{DecisionExport, PublishResult}

/**
 * The fixed layout of one kind of published run directory.
 *
 * @param stage
 *   the stage token its run manifest must carry
 * @param parts
 *   run-relative path and record schema of every data part file, in write order
 */
final case class RunLayout(stage: String, parts: Vector[(String, String)]) {

  /** The directories the run holds (run-relative); any other directory is refused. */
  def directories: Set[String] =
    parts.flatMap { case (p, _) =>
      val segments = p.split('/').toVector.init
      segments.indices.map(i => segments.take(i + 1).mkString("/"))
    }.toSet

  /** The files `_parts.json` lists: the run manifest, then every part, with their schemas. */
  def listed: Vector[(String, String)] =
    Vector(PartManifest.RunManifestFile -> scholarchain.core.SchemaVersions.RunManifest) ++ parts
}

/** A run directory verified against its part manifest, with the verified part bytes. */
final case class VerifiedRunDirectory(
    manifest: RunManifest,
    runId: String,
    parts: Map[String, Array[Byte]]
)

/**
 * All-or-nothing publication and verified reading of a run directory on a local file system, with
 * the same rules as the decision export ([[DecisionExport]], ER-P6-04, decision package G2), for
 * any [[RunLayout]]:
 *
 * '''Publication.''' The files are written into a staging directory in the output root named
 * `.staging-run=<hex>-<random>`: every part file in layout order, then `_run-manifest.json` (one
 * LF-terminated row), then `_parts.json` (a core [[PartManifest]] built from the bytes READ BACK
 * from staging after they were written, never from the in-memory text), then `_SUCCESS` last; the
 * staging directory is then renamed to `run=<hex>` in one atomic rename. A published directory is
 * never overwritten (refused when `run=<hex>` exists, and a rename onto a non-empty directory
 * fails); on a write or I/O failure the staging directory is deleted, so nothing consumable is
 * left.
 *
 * '''Staged verification (ER-P6-07).''' Between `_SUCCESS` and the rename, the caller's check reads
 * the staged directory back under its final name and compares it with the batch. If it fails,
 * `run=<hex>` is not created and nothing is deleted: the staging directory is withdrawn to
 * `.unverified-run=<hex>-<random>` with the marker `_UNVERIFIED` ([[DecisionExport.withdraw]]). A
 * directory holding the marker is refused by every reader here, whatever its name.
 *
 * '''Verified reads.''' A run is returned only after the layout (no symbolic link followed at any
 * level, the exact set of regular files and directories), the core [[PartManifestVerifier]] (exact
 * file set, row counts, byte counts, SHA-256, final LF, the run ID named by the directory) and the
 * run manifest (stage, run ID equal to the directory name and to `_parts.json`) all hold. Every
 * file is read once; the bytes returned are the bytes verified.
 *
 * '''Operations.''' A directory opened by a file browser or copied through a file system that adds
 * side files (for example `.DS_Store` or `._*` files) is refused as holding an extra file: refusing
 * is correct, and such files must be removed before the run is read (CR-P6-06 n2). The marker files
 * `_parts.json` and `_run-manifest.json` are read into memory without a size bound, and every
 * listing hashes every file of every candidate run (CR-P6-06 n3, n4; documented limitations shared
 * with the decision export).
 *
 * '''Known limitations''' (as the decision export, CR-P6-01 n2 and n3): neither the staging
 * directory nor the output root is forced to disk after the rename (atomicity holds, durability
 * after a power loss is not claimed), and the manifest's `codeVersion` is asserted by the caller.
 */
object RunDirectory {

  private val RunDirName = "^run=([0-9a-f]{64})$".r

  private def byUtf8(a: String, b: String): Boolean = Canonical.compareUtf8(a, b) < 0

  private def exists(p: Path): Boolean = Files.exists(p, LinkOption.NOFOLLOW_LINKS)
  private def isDir(p: Path): Boolean = Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)
  private def isFile(p: Path): Boolean = Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)

  private def deleteTree(p: Path): Unit =
    try
      if (exists(p)) {
        val s = Files.walk(p)
        try
          s.iterator.asScala.toVector.reverse.foreach(x =>
            try Files.deleteIfExists(x)
            catch { case NonFatal(_) => () }
          )
        finally s.close()
      }
    catch { case NonFatal(_) => () }

  private def resolve(dir: Path, rel: String): Path =
    rel.split('/').foldLeft(dir)((d, s) => d.resolve(s))

  /**
   * Reads one regular file in full without following a symbolic link at its last component, feeding
   * the core [[ObservedFile.Accumulator]] (and `keep`, when given) with every byte.
   */
  def readNoFollow(p: Path, rel: String, keep: Option[ByteArrayOutputStream]): ObservedFile = {
    val acc = new ObservedFile.Accumulator
    val ch = Files.newByteChannel(p, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
    try {
      val buf = ByteBuffer.allocate(64 * 1024)
      var n = ch.read(buf)
      while (n >= 0) {
        if (n > 0) {
          acc.update(buf.array, 0, n)
          keep.foreach(_.write(buf.array, 0, n))
        }
        buf.clear()
        n = ch.read(buf)
      }
    } finally ch.close()
    acc.result(rel)
  }

  /**
   * Publishes one run under `outputRoot` all-or-nothing (see the object documentation). `texts`
   * holds the complete text of every part of `layout`, by path. After `_SUCCESS` is written, the
   * staged directory is checked with `check(stagingDir, finalName)` (ER-P6-07: the caller reads it
   * back under its final name and compares it with its batch); only if that passes is it renamed to
   * `run=<hex>`. If it fails, nothing is deleted and the staging directory is withdrawn
   * ([[DecisionExport.withdraw]]).
   */
  def publish(
      outputRoot: Path,
      layout: RunLayout,
      runId: String,
      manifest: RunManifest,
      texts: Map[String, String],
      writer: DecisionExport.FileWriter,
      check: (Path, String) => Either[String, Unit]
  ): PublishResult = {
    val root = outputRoot.toAbsolutePath.normalize
    val name = DecisionExport.runDirName(runId)
    val target = root.resolve(name)
    val refusal = s"$target exists; refusing to overwrite a published ${layout.stage} run"
    val missing = layout.parts.map(_._1).filterNot(texts.contains)
    val manifestText = Json.encode(manifest).left.map(_.map(_.message).mkString("; "))
    if (missing.nonEmpty)
      PublishResult.Refused(s"nothing published: no text for ${missing.mkString(", ")}")
    else if (RunId.of(manifest) != Right(runId))
      PublishResult.Refused(s"nothing published: the run manifest does not give run $runId")
    else
      manifestText match {
        case Left(e) => PublishResult.Refused(s"nothing published: run manifest: $e")
        case Right(_) if exists(target) => PublishResult.Refused(refusal)
        case Right(m) =>
          var staging: Option[Path] = None
          try {
            Files.createDirectories(root)
            val dir = Files.createTempDirectory(root, DecisionExport.StagingPrefix + name + "-")
            staging = Some(dir)
            layout.directories.toVector.sorted
              .foreach(d => Files.createDirectories(resolve(dir, d)))
            layout.parts.foreach { case (rel, _) => writer(resolve(dir, rel), texts(rel)) }
            writer(dir.resolve(PartManifest.RunManifestFile), m + "\n")
            val listed = layout.listed.map { case (rel, schema) =>
              val o = readNoFollow(resolve(dir, rel), rel, None)
              PartFile(rel, schema, o.rowCount, o.byteCount, o.sha256)
            }
            PartManifest.build(runId, listed).flatMap(Json.encode(_)) match {
              case Left(es) =>
                PublishResult.Refused(
                  "nothing published: part manifest: " + es.map(_.message).mkString("; ")
                )
              case Right(parts) =>
                writer(dir.resolve(PartManifest.FileName), parts + "\n")
                writer(dir.resolve(PartManifest.SuccessMarker), "")
                val attempt = dir.getFileName.toString.stripPrefix(DecisionExport.StagingPrefix)
                val staged =
                  try check(dir, name)
                  catch { case NonFatal(e) => Left(s"${e.getClass.getName}: ${e.getMessage}") }
                staged match {
                  case Left(why) =>
                    staging = None // preserved for diagnosis, never deleted
                    val p = DecisionExport.withdraw(dir, attempt, why)
                    PublishResult.Unverified(
                      p,
                      s"staged verification failed, so $target was not published; " +
                        s"${DecisionExport.unverifiedText(p)}: $why"
                    )
                  case Right(()) if exists(target) => PublishResult.Refused(refusal)
                  case Right(()) =>
                    Files.move(dir, target, StandardCopyOption.ATOMIC_MOVE)
                    staging = None
                    PublishResult.Published(target, attempt)
                }
            }
          } catch {
            case _: FileAlreadyExistsException | _: DirectoryNotEmptyException =>
              PublishResult.Refused(refusal)
            case NonFatal(e) =>
              PublishResult.Refused(
                s"nothing published under $root: ${e.getClass.getName}: ${e.getMessage}"
              )
          } finally staging.foreach(deleteTree)
      }
  }

  /** Strict UTF-8 decoding: malformed or unmappable input is refused, never replaced. */
  def utf8(bytes: Array[Byte], what: String): Either[String, String] =
    try
      Right(
        StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString
      )
    catch { case _: CharacterCodingException => Left(s"$what is not valid UTF-8") }

  private def relative(dir: Path, p: Path): String =
    dir.relativize(p).iterator.asScala.map(_.toString).mkString("/")

  /**
   * Why `dir`, checked as if named `name`, is not a structurally complete run directory of
   * `layout`, or None. A directory holding [[DecisionExport.UnverifiedMarker]] is refused
   * (ER-P6-07).
   */
  private def layoutProblem(dir: Path, layout: RunLayout, name: String): Option[String] =
    if (!RunDirName.matches(name)) Some(s"$dir is not a run directory (run=<hex>)")
    else if (!isDir(dir)) Some(s"$dir is not a directory (symbolic links are not followed)")
    else if (exists(dir.resolve(DecisionExport.UnverifiedMarker)))
      Some(
        s"$dir holds ${DecisionExport.UnverifiedMarker}: it failed verification and is never " +
          "accepted"
      )
    else if (!isFile(dir.resolve(PartManifest.SuccessMarker)))
      Some(s"$dir is not a published run (no regular ${PartManifest.SuccessMarker})")
    else if (!isFile(dir.resolve(PartManifest.RunManifestFile)))
      Some(s"$dir is incomplete: no regular ${PartManifest.RunManifestFile}")
    else if (!isFile(dir.resolve(PartManifest.FileName)))
      Some(s"$dir is incomplete or unverifiable: no regular ${PartManifest.FileName}")
    else
      layout.directories.toVector.sorted.map(resolve(dir, _)).find(d => !isDir(d)) match {
        case Some(d) => Some(s"$dir is incomplete: $d is not a directory")
        case None =>
          layout.parts.map(p => resolve(dir, p._1)).find(f => !isFile(f)).map { f =>
            s"$dir is incomplete: $f is missing or not a regular file"
          }
      }

  /**
   * Every regular file below `dir`, observed from its bytes without following a symbolic link, with
   * the bytes of the files `keep` names. Refused when an entry is neither a regular file nor a
   * directory, or when a directory is not one of the layout's.
   */
  private def observe(
      dir: Path,
      layout: RunLayout,
      keep: Set[String]
  ): Either[String, (Vector[ObservedFile], Map[String, Array[Byte]])] = {
    val s = Files.walk(dir)
    val entries =
      try s.iterator.asScala.toVector.filter(_ != dir)
      finally s.close()
    val sorted = entries.map(p => relative(dir, p) -> p).sortWith((a, b) => byUtf8(a._1, b._1))
    val dirs = layout.directories
    sorted.foldLeft[Either[String, (Vector[ObservedFile], Map[String, Array[Byte]])]](
      Right((Vector.empty, Map.empty))
    ) { case (acc, (rel, p)) =>
      acc.flatMap { case (obs, bytes) =>
        val attrs =
          Files.readAttributes(p, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
        if (attrs.isDirectory)
          if (dirs.contains(rel)) Right((obs, bytes))
          else Left(s"$dir holds an unexpected directory $rel")
        else if (attrs.isRegularFile) {
          val out = Option.when(keep.contains(rel))(new ByteArrayOutputStream)
          val o = readNoFollow(p, rel, out)
          Right((obs :+ o, bytes ++ out.map(b => rel -> b.toByteArray)))
        } else Left(s"$dir holds $rel, which is neither a regular file nor a directory")
      }
    }
  }

  /** The file name of `dir` ("" for a root). */
  def nameOf(dir: Path): String = Option(dir.getFileName).map(_.toString).getOrElse("")

  /**
   * Verifies a run directory of `layout` against the bytes on disk, as if it were named `name` (its
   * own name for every reader; the final name for the staged verification, ER-P6-07).
   */
  def verified(
      dir: Path,
      layout: RunLayout,
      keepParts: Boolean,
      name: String
  ): Either[String, VerifiedRunDirectory] =
    try
      layoutProblem(dir, layout, name) match {
        case Some(why) => Left(why)
        case None =>
          val expectedRunId = "run:" + name.stripPrefix("run=")
          val partPaths = layout.parts.map(_._1)
          val keep = Set(PartManifest.RunManifestFile, PartManifest.FileName) ++
            (if (keepParts) partPaths else Vector.empty)
          for {
            observed <- observe(dir, layout, keep)
            (files, bytes) = observed
            partsText <- utf8(bytes(PartManifest.FileName), s"$dir/${PartManifest.FileName}")
            pm <- Json
              .decode[PartManifest](partsText)
              .left
              .map(es => s"$dir/${PartManifest.FileName}: ${es.map(_.message).mkString("; ")}")
            listed = pm.files.map(f => f.path -> f.recordSchemaVersion)
            _ <- Either.cond(
              listed.toSet == layout.listed.toSet && listed.size == layout.listed.size,
              (),
              s"$dir/${PartManifest.FileName}: lists ${listed.map(_._1).mkString(", ")}; a " +
                s"${layout.stage} run lists " +
                layout.listed.map { case (p, v) => s"$p ($v)" }.mkString(", ")
            )
            _ <- {
              val errors = PartManifestVerifier.verify(pm, expectedRunId, files)
              Either.cond(
                errors.isEmpty,
                (),
                s"$dir does not match its ${PartManifest.FileName} (refused): " +
                  errors.map(_.message).mkString("; ")
              )
            }
            manifestText <- utf8(
              bytes(PartManifest.RunManifestFile),
              s"$dir/${PartManifest.RunManifestFile}"
            )
            m <- Json
              .decode[RunManifest](manifestText.stripSuffix("\n"))
              .left
              .map(es =>
                s"$dir/${PartManifest.RunManifestFile}: ${es.map(_.message).mkString("; ")}"
              )
            _ <- Either.cond(
              m.stage == layout.stage,
              (),
              s"$dir/${PartManifest.RunManifestFile}: stage is '${m.stage}', expected " +
                s"'${layout.stage}'"
            )
            runId <- RunId
              .of(m)
              .left
              .map(e => s"$dir/${PartManifest.RunManifestFile}: ${e.message}")
            _ <- Either.cond(
              DecisionExport.runDirName(runId) == name,
              (),
              s"$dir does not hold run $runId"
            )
            _ <- {
              val errors = PartManifest.checkRunManifest(pm, m)
              Either.cond(
                errors.isEmpty,
                (),
                s"$dir/${PartManifest.FileName}: ${errors.map(_.message).mkString("; ")}"
              )
            }
          } yield VerifiedRunDirectory(
            m,
            runId,
            bytes.filter { case (k, _) => partPaths.contains(k) }
          )
      }
    catch {
      case NonFatal(e) => Left(s"cannot read $dir: ${e.getClass.getName}: ${e.getMessage}")
    }

  /** The verified published runs of `layout` below `root`, sorted by directory name. */
  def publishedRuns(root: Path, layout: RunLayout): Vector[Path] =
    try
      if (!Files.isDirectory(root)) Vector.empty
      else {
        val s = Files.list(root)
        try
          s.iterator.asScala.toVector
            .filter(p => verified(p, layout, keepParts = false, nameOf(p)).isRight)
            .sortBy(_.getFileName.toString)
        finally s.close()
      }
    catch { case NonFatal(_) => Vector.empty }

  /**
   * The rows of verified part bytes: empty for a zero-byte file, otherwise every LF-terminated
   * line. An empty row is refused, never dropped.
   */
  def rows(bytes: Array[Byte], file: String): Either[String, Vector[String]] =
    utf8(bytes, file).flatMap { text =>
      if (text.isEmpty) Right(Vector.empty)
      else if (!text.endsWith("\n")) Left(s"$file: the last row has no final LF")
      else {
        val rs = text.stripSuffix("\n").split("\n", -1).toVector
        rs.indexWhere(_.isEmpty) match {
          case -1 => Right(rs)
          case i => Left(s"$file: row ${i + 1} is empty")
        }
      }
    }
}
