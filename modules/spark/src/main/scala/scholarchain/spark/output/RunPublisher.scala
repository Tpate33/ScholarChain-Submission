package scholarchain.spark.output

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path, StandardCopyOption, StandardOpenOption}
import java.util.Comparator

import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.control.NonFatal

import org.apache.hadoop.fs.{Path => HPath}
import org.apache.parquet.hadoop.ParquetFileReader
import org.apache.parquet.hadoop.util.HadoopInputFile
import org.apache.spark.sql.{DataFrame, SaveMode, SparkSession}

import scholarchain.core.*
import scholarchain.spark.input.{RunTree, TreeEntry}

/**
 * One Parquet dataset of a run: written under `<run>/<name>/part-NNNNN.parquet`, every row a record
 * of `recordSchemaVersion`, `expectedRows` records in total.
 */
final case class ParquetDataset(
    name: String,
    recordSchemaVersion: String,
    frame: DataFrame,
    expectedRows: Long
)

/** A published, re-verified run directory. */
final case class PublishedRun(
    dir: Path,
    runId: String,
    manifest: RunManifest,
    partManifest: PartManifestV2
) {
  def partsOf(recordSchemaVersion: String): Vector[Path] =
    partManifest.files
      .filter(_.recordSchemaVersion == recordSchemaVersion)
      .map(f => dir.resolve(f.path))
}

/**
 * Test seams of [[RunPublisher.publish]]: each runs at one fixed point of the publication with the
 * path named, so tests can change the file system there deterministically. Production uses
 * [[PublishHooks.Noop]].
 */
private[output] final case class PublishHooks(
    beforeClaim: Path => Unit = _ => (),
    beforeMove: Path => Unit = _ => (),
    afterMove: Path => Unit = _ => ()
)
private[output] object PublishHooks {
  val Noop: PublishHooks = PublishHooks()
}

/**
 * Staged, verified, renamed publication of a Spark run (SP-P6-02 step 1; CR-P6-19 F4). Only paths
 * under the configured `outputRoot` are created; nothing outside it is written or deleted.
 *
 * Order:
 *   1. refuse when `run=<hex>`, `.staging-<hex>` or `.spark-<hex>` exists;
 *   2. create `.staging-<hex>` (create-once: `Files.createDirectory`, which fails when anything
 *      exists there, so two publishers of one run ID never share a staging directory);
 *   3. per dataset check the frame's StructType against its record schema label
 *      ([[RecordSchemas]]), write it with Spark into the scratch directory `.spark-<hex>` (deleted
 *      afterwards), move each `part-*.parquet` into the staging run as `<name>/part-NNNNN.parquet`
 *      (sorted by Spark's part name), and re-read each part's StructType and footer row count
 *      (CR-P6-15 N1); then `_run-manifest.json` (one LF-terminated row), `_parts.json`
 *      (part-manifest v2: parquet parts by byte count and SHA-256, rows from the footer) and
 *      `_SUCCESS`; verify the staged directory with core;
 *   4. claim the destination: `Files.createDirectory(run=<hex>)` (mkdir, atomic; it fails when any
 *      entry, including an empty directory, already exists, and never replaces it);
 *   5. one `ATOMIC_MOVE` rename of the staging directory onto the claimed, still empty destination;
 *   6. [[verifyPublished]] re-verifies the renamed directory from its bytes.
 *
 * Defined outcomes:
 *   - any refusal before step 4: the staging and scratch directories this call created are removed;
 *     nothing else is touched;
 *   - the claim fails (the destination exists): refused, the staging directory is removed, the
 *     existing destination is left exactly as it was (never replaced, never written);
 *   - the rename fails: refused, the staging directory is removed, and the claim is removed only if
 *     it is still empty (`Files.delete` of a directory removes only an empty one), so content that
 *     another process put into it is never deleted;
 *   - post-rename verification fails: refused, and the run is marked unverified so it can never be
 *     taken as complete: `_UNVERIFIED` is created first, then `_SUCCESS` is deleted; the directory
 *     is kept for diagnosis. Readers ([[scholarchain.spark.input.VerifiedArchiveReader]],
 *     [[verifyPublished]]) refuse a run with `_UNVERIFIED` or without `_SUCCESS`. If marking itself
 *     fails, the refusal says so.
 *
 * Concurrency limitation (stated, not removed): the JDK file API has no no-replace rename (Linux
 * `renameat2(RENAME_NOREPLACE)`, macOS `renamex_np(RENAME_EXCL)`), and `ATOMIC_MOVE` maps to
 * rename(2), which atomically replaces an existing empty target directory. The no-replace guarantee
 * therefore rests on the mkdir claim of step 4: the rename can only replace the empty directory
 * this call itself created. If a non-empty entry appears in the claim before the rename, rename(2)
 * fails and nothing is replaced. Not covered: another process that deletes this call's claim and
 * creates its own empty `run=<hex>` between steps 4 and 5 would have that empty directory replaced;
 * this requires deleting another writer's directory and is excluded by the
 * single-writer-per-output-root assumption of this local runner. Between steps 4 and 5 readers see
 * an empty `run=<hex>` without `_SUCCESS` and refuse it; a crash in that window leaves that empty
 * claim, which blocks re-publication of the run ID (fail closed; a human removes it).
 *
 * Driver use: file listing, hashing and footer reads happen on the driver's file system (a local or
 * mounted output root); the rows themselves are written by the executors. `_parts.json` and
 * `_run-manifest.json` are read back with at most `maxMetadataBytes` bytes each.
 */
object RunPublisher {

  private def sha256Hex(p: Path): Either[String, (Long, String)] =
    RunTree
      .observe(p.getParent, p.getFileName.toString)
      .map(o => (o.byteCount, o.sha256))

  private def footerRows(spark: SparkSession, p: Path): Either[String, Long] =
    try {
      val in = HadoopInputFile.fromPath(new HPath(p.toUri), spark.sparkContext.hadoopConfiguration)
      Using.resource(ParquetFileReader.open(in))(r => Right(r.getRecordCount))
    } catch { case NonFatal(e) => Left(s"cannot read the Parquet footer of $p: $e") }

  private def schemaProblem(spark: SparkSession, label: String, p: Path): Option[String] =
    try
      RecordSchemas.problem(label, spark.read.parquet(p.toUri.toString).schema).map(w => s"$p: $w")
    catch { case NonFatal(e) => Some(s"cannot read the Parquet schema of $p: $e") }

  private def deleteTree(root: Path): Unit =
    if (Files.exists(root, LinkOption.NOFOLLOW_LINKS))
      Using.resource(Files.walk(root)) { s =>
        s.sorted(Comparator.reverseOrder[Path]()).iterator().asScala.foreach { p =>
          Files.deleteIfExists(p)
          ()
        }
      }

  private def writeNew(p: Path, bytes: Array[Byte]): Unit = {
    Files.write(p, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    ()
  }

  def publish(
      spark: SparkSession,
      outputRoot: Path,
      manifest: RunManifest,
      datasets: Vector[ParquetDataset],
      maxMetadataBytes: Long
  ): Either[Vector[String], PublishedRun] =
    publishWith(spark, outputRoot, manifest, datasets, maxMetadataBytes, PublishHooks.Noop)

  /** Marks a published directory as not complete: `_UNVERIFIED` first, then `_SUCCESS` removed. */
  private def markUnverified(dir: Path): Vector[String] =
    try {
      writeNew(dir.resolve(RunTree.UnverifiedMarker), Array.emptyByteArray)
      Files.deleteIfExists(dir.resolve(PartManifest.SuccessMarker))
      Vector(s"$dir is marked ${RunTree.UnverifiedMarker} and has no _SUCCESS: not a published run")
    } catch {
      case NonFatal(e) =>
        Vector(s"marking $dir unverified failed ($e): do not use it; it may still hold _SUCCESS")
    }

  private[output] def publishWith(
      spark: SparkSession,
      outputRoot: Path,
      manifest: RunManifest,
      datasets: Vector[ParquetDataset],
      maxMetadataBytes: Long,
      hooks: PublishHooks
  ): Either[Vector[String], PublishedRun] =
    RunId.of(manifest) match {
      case Left(e) => Left(Vector(s"run manifest: $e"))
      case Right(runId) =>
        val hex = runId.stripPrefix("run:")
        val finalDir = outputRoot.resolve(s"run=$hex")
        val staging = outputRoot.resolve(s".staging-$hex")
        val scratch = outputRoot.resolve(s".spark-$hex")
        val names = datasets.map(_.name)
        if (!outputRoot.isAbsolute || outputRoot.normalize != outputRoot)
          Left(Vector(s"output root $outputRoot is not an absolute, normalized path"))
        else if (!RunTree.isRealDirectory(outputRoot))
          Left(Vector(s"output root $outputRoot is not a real directory"))
        else if (RunTree.exists(finalDir))
          Left(Vector(s"$finalDir already exists: never overwritten"))
        else if (RunTree.exists(staging) || RunTree.exists(scratch))
          Left(Vector(s"$staging or $scratch already exists: refused"))
        else if (
          names.distinct.size != names.size || names
            .exists(n => PartManifest.pathProblem(n).nonEmpty || n.contains("/"))
        )
          Left(Vector(s"dataset names must be distinct single path segments: $names"))
        else {
          val created =
            try { Files.createDirectory(staging); None }
            catch { case e: IOException => Some(s"cannot create $staging (create-once): $e") }
          created match {
            case Some(w) => Left(Vector(w)) // not created by this call: left untouched
            case None =>
              val result =
                try stage(spark, staging, scratch, runId, manifest, datasets)
                catch {
                  case e: IOException => Left(Vector(s"I/O failure while staging: $e"))
                  case NonFatal(e) => Left(Vector(s"failure while staging: $e"))
                } finally deleteTree(scratch)
              result match {
                case Left(e) =>
                  deleteTree(staging)
                  Left(e)
                case Right(_) =>
                  claimAndMove(spark, staging, finalDir, runId, maxMetadataBytes, hooks)
              }
          }
        }
    }

  /** Steps 4-6 of [[publish]]: claim, rename, re-verify (outcomes on the object's Scaladoc). */
  private def claimAndMove(
      spark: SparkSession,
      staging: Path,
      finalDir: Path,
      runId: String,
      maxMetadataBytes: Long,
      hooks: PublishHooks
  ): Either[Vector[String], PublishedRun] = {
    hooks.beforeClaim(finalDir)
    val claimed =
      try { Files.createDirectory(finalDir); None }
      catch { case e: IOException => Some(e) }
    claimed match {
      case Some(e) =>
        deleteTree(staging)
        Left(Vector(s"$finalDir exists or cannot be created ($e): never overwritten"))
      case None =>
        hooks.beforeMove(finalDir)
        val moved =
          try { Files.move(staging, finalDir, StandardCopyOption.ATOMIC_MOVE); None }
          catch { case e: IOException => Some(e) }
        moved match {
          case Some(e) =>
            deleteTree(staging)
            val claim =
              try { Files.deleteIfExists(finalDir); "the empty claim was removed" }
              catch {
                case NonFatal(x) =>
                  s"the claim could not be removed (not empty, or $x) and was kept untouched"
              }
            Left(Vector(s"the rename of $staging to $finalDir failed ($e): $claim"))
          case None =>
            hooks.afterMove(finalDir)
            verifyPublished(spark, finalDir, runId, maxMetadataBytes) match {
              case Right(p) => Right(p)
              case Left(problems) =>
                Left(
                  problems.map(w => s"post-publication verification failed: $w") ++
                    markUnverified(finalDir)
                )
            }
        }
    }
  }

  private def stage(
      spark: SparkSession,
      staging: Path,
      scratch: Path,
      runId: String,
      manifest: RunManifest,
      datasets: Vector[ParquetDataset]
  ): Either[Vector[String], Unit] = {
    Files.createDirectory(scratch)
    val entries = datasets.map { d =>
      RecordSchemas.problem(d.recordSchemaVersion, d.frame.schema) match {
        case Some(w) => Left(Vector(s"dataset ${d.name} before writing: $w"))
        case None =>
          val out = scratch.resolve(d.name)
          d.frame.write.mode(SaveMode.ErrorIfExists).parquet(out.toUri.toString)
          val sparkParts = Using.resource(Files.list(out)) { s =>
            s.iterator()
              .asScala
              .map(_.getFileName.toString)
              .filter(n => n.startsWith("part-") && n.endsWith(".parquet"))
              .toVector
              .sorted
          }
          val dir = Files.createDirectory(staging.resolve(d.name))
          val files = sparkParts.zipWithIndex.map { case (n, i) =>
            val target = dir.resolve(f"part-$i%05d.parquet")
            Files.move(out.resolve(n), target)
            val rel = s"${d.name}/${target.getFileName}"
            for {
              _ <- schemaProblem(spark, d.recordSchemaVersion, target).toLeft(())
              rows <- footerRows(spark, target)
              hash <- sha256Hex(target)
            } yield PartFileV2(
              rel,
              d.recordSchemaVersion,
              PartFormat.Parquet,
              rows,
              RowCountSource.ParquetFooter,
              hash._1,
              hash._2
            )
          }
          val bad = files.collect { case Left(w) => w }
          val good = files.collect { case Right(f) => f }
          if (bad.nonEmpty) Left(bad)
          else if (good.isEmpty) Left(Vector(s"dataset ${d.name}: Spark wrote no Parquet part"))
          else if (good.map(_.rowCount).sum != d.expectedRows)
            Left(
              Vector(
                s"dataset ${d.name}: the footers hold ${good.map(_.rowCount).sum} rows, expected ${d.expectedRows}"
              )
            )
          else Right(good)
      }
    }
    val bad = entries.collect { case Left(w) => w }.flatten
    if (bad.nonEmpty) Left(bad)
    else
      Json.encode(manifest) match {
        case Left(e) => Left(Vector(s"run manifest: ${e.mkString("; ")}"))
        case Right(json) =>
          val rmBytes = (json + "\n").getBytes(StandardCharsets.UTF_8)
          writeNew(staging.resolve(PartManifest.RunManifestFile), rmBytes)
          val rm = RunTree.observe(staging, PartManifest.RunManifestFile).toOption.get
          val rmEntry = PartFileV2(
            PartManifest.RunManifestFile,
            SchemaVersions.RunManifest,
            PartFormat.Jsonl,
            rm.rowCount,
            RowCountSource.LfCount,
            rm.byteCount,
            rm.sha256
          )
          PartManifestV2.build(
            runId,
            entries.collect { case Right(fs) => fs }.flatten :+ rmEntry
          ) match {
            case Left(e) => Left(Vector(s"part manifest: ${e.mkString("; ")}"))
            case Right(pm) =>
              writeNew(
                staging.resolve(PartManifest.FileName),
                (Json.write(pm) + "\n").getBytes(StandardCharsets.UTF_8)
              )
              writeNew(staging.resolve(PartManifest.SuccessMarker), Array.emptyByteArray)
              val problems = observeAll(staging).flatMap(obs => {
                val v = PartManifests.verify(Right(pm), runId, obs)
                if (v.isEmpty) Right(()) else Left(v.map(e => s"staged run: $e"))
              })
              problems
          }
      }
  }

  private def observeAll(dir: Path): Either[Vector[String], Vector[ObservedFile]] =
    RunTree.walk(dir, Int.MaxValue, Int.MaxValue) match {
      case Left(w) => Left(Vector(w))
      case Right(es) =>
        val links =
          es.filter(e => e.kind != TreeEntry.Kind.Regular && e.kind != TreeEntry.Kind.Directory)
        if (links.nonEmpty)
          Left(links.map(e => s"'${e.relativePath}' is not a regular file or directory"))
        else {
          val obs = es
            .filter(_.kind == TreeEntry.Kind.Regular)
            .map(e => RunTree.observe(dir, e.relativePath))
          val bad = obs.collect { case Left(w) => w }
          if (bad.nonEmpty) Left(bad) else Right(obs.collect { case Right(o) => o })
        }
    }

  /**
   * Re-verifies a published run directory from its bytes: no `_UNVERIFIED` and a regular
   * `_SUCCESS`, `_parts.json` (at most `maxMetadataBytes`) a v2 manifest of run `expectedRunId`
   * that every file verifies against, every Parquet part with the StructType of its label and the
   * footer row count it declares, and `_run-manifest.json` (at most `maxMetadataBytes`) the run
   * manifest of `expectedRunId`.
   */
  def verifyPublished(
      spark: SparkSession,
      dir: Path,
      expectedRunId: String,
      maxMetadataBytes: Long
  ): Either[Vector[String], PublishedRun] =
    for {
      _ <-
        if (RunTree.exists(dir.resolve(RunTree.UnverifiedMarker)))
          Left(Vector(s"$dir holds ${RunTree.UnverifiedMarker}: not a published run"))
        else if (!RunTree.isRegularFile(dir.resolve(PartManifest.SuccessMarker)))
          Left(Vector(s"$dir has no regular _SUCCESS: not a published run"))
        else Right(())
      text <- RunTree
        .readUtf8(dir.resolve(PartManifest.FileName), maxMetadataBytes)
        .left
        .map(Vector(_))
      pm <- PartManifests.decode(text) match {
        case Right(Right(v2)) => Right(v2)
        case Right(Left(_)) => Left(Vector("_parts.json is part-manifest.v1; Parquet runs are v2"))
        case Left(e) => Left(e.map(_.toString))
      }
      obs <- observeAll(dir)
      _ <- {
        val v = PartManifests.verify(Right(pm), expectedRunId, obs)
        if (v.isEmpty) Right(()) else Left(v.map(_.toString))
      }
      _ <- {
        val bad = pm.files.filter(_.format == PartFormat.Parquet).flatMap { f =>
          val p = dir.resolve(f.path)
          schemaProblem(spark, f.recordSchemaVersion, p).toVector ++
            (footerRows(spark, p) match {
              case Left(w) => Vector(w)
              case Right(n) if n != f.rowCount =>
                Vector(s"${f.path}: footer $n rows, listed ${f.rowCount}")
              case Right(_) => Vector.empty
            })
        }
        if (bad.isEmpty) Right(()) else Left(bad)
      }
      rmText <- RunTree
        .readUtf8(dir.resolve(PartManifest.RunManifestFile), maxMetadataBytes)
        .left
        .map(Vector(_))
      rm <- Json.decode[RunManifest](rmText.stripSuffix("\n")).left.map(_.map(_.toString))
      _ <- {
        val b = PartManifestV2.checkRunManifest(pm, rm)
        if (b.isEmpty) Right(()) else Left(b.map(_.toString))
      }
    } yield PublishedRun(dir, expectedRunId, rm, pm)
}
