package scholarchain.spark.output

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path, StandardCopyOption, StandardOpenOption}
import java.util.Comparator

import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.control.NonFatal

import org.apache.parquet.hadoop.ParquetFileReader
import org.apache.parquet.hadoop.util.HadoopInputFile
import org.apache.spark.sql.{DataFrame, SaveMode, SparkSession}

import scholarchain.core.*
import scholarchain.spark.input.{RunTree, TreeEntry}
import scholarchain.spark.store.{NioRunStore, RunStore, StorePath, StoreStat}

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

/**
 * One CSV dataset of a run (SP-P6-03 step 2B, the Neptune bulk-load parts): one element per
 * partition, each a complete CSV file (`header` line included, every line LF-terminated) with the
 * number of records it holds. Written under `<run>/<name>/part-NNNNN.csv` (part i is partition i),
 * listed as `csv` with the writer-reported row count; `expectedRows` records in total. The caller
 * persists `files` (it is evaluated twice: the row counts, then the text).
 */
final case class CsvDataset(
    name: String,
    recordSchemaVersion: String,
    header: String,
    files: org.apache.spark.rdd.RDD[(Long, String)],
    expectedRows: Long
)

/** A published, re-verified run directory, and the store it was published to and read from. */
final case class PublishedRun(
    dir: StorePath,
    runId: String,
    manifest: RunManifest,
    partManifest: PartManifestV2,
    store: RunStore = NioRunStore
) {

  /** Every listed part of `recordSchemaVersion`, whatever its format. */
  def partsOf(recordSchemaVersion: String): Vector[StorePath] =
    partManifest.files
      .filter(_.recordSchemaVersion == recordSchemaVersion)
      .map(f => dir.resolve(f.path))

  /** The Parquet parts of `recordSchemaVersion` (the canonical tables also have CSV parts). */
  def parquetPartsOf(recordSchemaVersion: String): Vector[StorePath] =
    partManifest.files
      .filter(f => f.recordSchemaVersion == recordSchemaVersion && f.format == PartFormat.Parquet)
      .map(f => dir.resolve(f.path))

  /** The parts of the dataset directory `name` (for example a Neptune CSV dataset). */
  def datasetParts(name: String): Vector[StorePath] =
    partManifest.files.filter(_.path.startsWith(name + "/")).map(f => dir.resolve(f.path))
}

/**
 * Test seams of the local staged-rename protocol ([[RunPublisher.publish]]): each runs at one fixed
 * point of the publication with the path named, so tests can change the file system there
 * deterministically. Production uses [[PublishHooks.Noop]].
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
 * Test seams of the commit-marker protocol ([[RunPublisher.publishTo]] on a store without atomic
 * rename), each with the final run location: after every data part was copied, before
 * `_parts.json`, before `_SUCCESS` (after the pre-commit verification), and after `_SUCCESS`.
 * Production uses [[CommitHooks.Noop]].
 */
private[output] final case class CommitHooks(
    afterDataParts: StorePath => Unit = _ => (),
    beforePartManifest: StorePath => Unit = _ => (),
    beforeSuccess: StorePath => Unit = _ => (),
    afterSuccess: StorePath => Unit = _ => ()
)
private[output] object CommitHooks {
  val Noop: CommitHooks = CommitHooks()
}

/**
 * Publication of a Spark run. Two protocols, one result: a directory `run=<hex>` under the output
 * root holding the datasets, `_run-manifest.json`, `_parts.json` (part-manifest v2) and `_SUCCESS`,
 * re-verified from its bytes ([[verifyPublished]]). Only paths under the configured output root are
 * created; nothing outside it is written or deleted. In both, Spark's executors write the rows (one
 * distributed write per dataset into the scratch directory `.spark-<hex>`) and the driver lists,
 * hashes and checks the parts through the store of the output root (java.nio for the local store,
 * the output root's Hadoop FileSystem otherwise; CR-P6-25 f2: on a multi-node cluster the driver
 * therefore sees what the executors wrote).
 *
 * '''A. Local staged rename''' ([[publish]] with a `java.nio.file.Path`, or [[publishTo]] with the
 * [[NioRunStore]]; SP-P6-02 step 1, CR-P6-19 F4; unchanged by SP-P6-04). Order:
 *   1. refuse when `run=<hex>`, `.staging-<hex>` or `.spark-<hex>` exists;
 *   2. create `.staging-<hex>` (create-once: `Files.createDirectory`, which fails when anything
 *      exists there, so two publishers of one run ID never share a staging directory);
 *   3. per dataset check the frame's StructType against its record schema label
 *      ([[RecordSchemas]]), write it with Spark into the scratch directory `.spark-<hex>` (deleted
 *      afterwards), move each `part-*.parquet` into the staging run as `<name>/part-NNNNN.parquet`
 *      (sorted by Spark's part name), and re-read each part's StructType and footer row count
 *      (CR-P6-15 N1); per CSV dataset ([[CsvDataset]], SP-P6-03 step 2B) collect the per-partition
 *      row counts (one Long per partition to the driver), write the files with `saveAsTextFile`
 *      into the scratch directory, move part i as `<name>/part-0000i.csv`, and check that it starts
 *      with the dataset's header; then `_run-manifest.json` (one LF-terminated row), `_parts.json`
 *      (part-manifest v2: parquet parts by byte count and SHA-256, rows from the footer) and
 *      `_SUCCESS`; verify the staged directory with core;
 *   4. claim the destination: `Files.createDirectory(run=<hex>)` (mkdir, atomic; it fails when any
 *      entry, including an empty directory, already exists, and never replaces it);
 *   5. one `ATOMIC_MOVE` rename of the staging directory onto the claimed, still empty destination;
 *   6. [[verifyPublished]] re-verifies the renamed directory from its bytes.
 *
 * Defined outcomes of A:
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
 * Concurrency limitation of A (stated, not removed): the JDK file API has no no-replace rename
 * (Linux `renameat2(RENAME_NOREPLACE)`, macOS `renamex_np(RENAME_EXCL)`), and `ATOMIC_MOVE` maps to
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
 * '''B. Commit marker''' ([[publishTo]] with a store without atomic rename: any Hadoop FileSystem,
 * HDFS, S3A, or `file:` URIs through Hadoop; SP-P6-04). Nothing is ever renamed by this code and no
 * conditional put is relied on; completion is the last-written `_SUCCESS`. Order:
 *   1. refuse when the output root is anything but an absent location or a directory, when anything
 *      exists at `run=<hex>` (for S3A: any object under the prefix) or `.spark-<hex>`, or when
 *      either cannot be checked (fail closed);
 *   2. per dataset, the StructType check, the distributed Spark write into `.spark-<hex>/<name>`
 *      (Spark's own commit protocol, inside the scratch prefix only), then on the scratch parts
 *      (listed through the output root's FileSystem, sorted by Spark's part name): Parquet schema
 *      and footer row counts, CSV part count per partition; the rows must sum to `expectedRows`. A
 *      refusal here leaves nothing under `run=<hex>`;
 *   3. each part is copied create-once (`create(overwrite = false)`) to its final name
 *      `run=<hex>/<name>/part-NNNNN.<ext>`, hashing the source bytes while copying; the copy is
 *      then re-read from the store and must have the same byte count and SHA-256; Parquet parts are
 *      re-checked (schema, footer rows), CSV parts must start with the header;
 *   4. `_run-manifest.json` (create-once, re-read), then `_parts.json` (create-once);
 *   5. pre-commit verification: the run location holds exactly the listed files, each verifying
 *      against `_parts.json` from its bytes (core `PartManifests.verify`, with `_SUCCESS` as the
 *      one marker still to be written), plus the Parquet, CSV and run-manifest checks of
 *      [[verifyPublished]];
 *   6. `_SUCCESS` (empty, create-once), written last: the commit point;
 *   7. [[verifyPublished]] re-verifies the committed run from its bytes;
 *   8. the scratch prefix `.spark-<hex>` is deleted (best effort) whatever the outcome.
 *
 * What is and is not atomic in B: each object or file is created once; on S3A an object appears
 * complete or not at all (one PUT or a completed multipart upload), on HDFS a file is visible with
 * a growing length while it is written; the run as a whole is not atomic on any store: readers can
 * see a partial `run=<hex>` without `_SUCCESS` while it is written and after a crash, and refuse it
 * ([[scholarchain.spark.input.VerifiedArchiveReader]] and [[verifyPublished]] require a regular
 * `_SUCCESS`, refuse `_UNVERIFIED`, and re-verify every byte against `_parts.json` rather than
 * trusting the marker). Create-once is check-then-create on S3A and RawLocalFileSystem (atomic on
 * HDFS only), so B assumes a single writer per output root (one job, one attempt at a time; no
 * speculative duplicate of the driver).
 *
 * Defined outcomes of B (no outcome deletes anything under `run=<hex>`):
 *   - refused before step 3: nothing exists under `run=<hex>`; the same run ID may be published
 *     again;
 *   - refused or failed in steps 3-5 (an exception included): `run=<hex>` holds a partial run
 *     without `_SUCCESS`; `_UNVERIFIED` is written into it (best effort) and the refusal says so;
 *     every reader refuses it, and a publication of the same run ID is refused (the location
 *     exists): publish again as a new attempt (run manifest `attempt` + 1, so a new run ID), or a
 *     human removes the partial run;
 *   - the process dies before step 6 (crash): the partial run has neither `_SUCCESS` nor
 *     `_UNVERIFIED`; readers refuse it (no `_SUCCESS`); the same remedy;
 *   - step 7 fails: `_UNVERIFIED` is written beside `_SUCCESS` (which is not deleted); readers
 *     refuse the run (`_UNVERIFIED`); the same remedy.
 *
 * Driver use: file listing, hashing, copying (B) and footer reads happen on the driver through the
 * store (streamed in 64 KiB buffers, so driver memory does not grow with the data); the rows are
 * written by the executors. The B copy is driver-side and doubles the bytes written: acceptable for
 * the dev and bounded runs, a throughput limit at full scale (stated). `_parts.json` and
 * `_run-manifest.json` are read back with at most `maxMetadataBytes` bytes each.
 */
object RunPublisher {

  private val BufferBytes = 64 * 1024

  private def footerRows(spark: SparkSession, p: StorePath): Either[String, Long] =
    try {
      val in = HadoopInputFile.fromPath(p.hadoopPath, spark.sparkContext.hadoopConfiguration)
      Using.resource(ParquetFileReader.open(in))(r => Right(r.getRecordCount))
    } catch { case NonFatal(e) => Left(s"cannot read the Parquet footer of $p: $e") }

  private def schemaProblem(spark: SparkSession, label: String, p: StorePath): Option[String] =
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

  /** As [[publish]], with CSV datasets beside the Parquet ones (one staged, verified run). */
  def publish(
      spark: SparkSession,
      outputRoot: Path,
      manifest: RunManifest,
      datasets: Vector[ParquetDataset],
      csv: Vector[CsvDataset],
      maxMetadataBytes: Long
  ): Either[Vector[String], PublishedRun] =
    publishWith(spark, outputRoot, manifest, datasets, maxMetadataBytes, PublishHooks.Noop, csv)

  /**
   * Publishes under `outputRoot` in `store`: protocol A for the local java.nio store (a local
   * output root), protocol B (commit marker, no rename) for every other store.
   */
  def publishTo(
      spark: SparkSession,
      store: RunStore,
      outputRoot: StorePath,
      manifest: RunManifest,
      datasets: Vector[ParquetDataset],
      csv: Vector[CsvDataset],
      maxMetadataBytes: Long
  ): Either[Vector[String], PublishedRun] =
    if (store.supportsAtomicDirectoryRename)
      if (outputRoot.isLocal)
        publishWith(
          spark,
          outputRoot.localPath,
          manifest,
          datasets,
          maxMetadataBytes,
          PublishHooks.Noop,
          csv
        )
      else Left(Vector(s"output root $outputRoot is not local, but the store is ${store.describe}"))
    else
      publishCommitted(
        spark,
        store,
        outputRoot,
        manifest,
        datasets,
        csv,
        maxMetadataBytes,
        CommitHooks.Noop
      )

  /**
   * Why the first line of the CSV part `p` is not `header`, or None. Reads at most the header's
   * UTF-8 length plus one byte.
   */
  private def csvHeaderProblem(store: RunStore, p: StorePath, header: String): Option[String] = {
    val want = (header + "\n").getBytes(StandardCharsets.UTF_8)
    try {
      val got = Using.resource(store.open(p))(_.readNBytes(want.length))
      if (java.util.Arrays.equals(got, want)) None
      else Some(s"$p: the first line is not the CSV header $header")
    } catch { case e: IOException => Some(s"cannot read $p: $e") }
  }

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
      hooks: PublishHooks,
      csv: Vector[CsvDataset] = Vector.empty
  ): Either[Vector[String], PublishedRun] =
    RunId.of(manifest) match {
      case Left(e) => Left(Vector(s"run manifest: $e"))
      case Right(runId) =>
        val hex = runId.stripPrefix("run:")
        val finalDir = outputRoot.resolve(s"run=$hex")
        val staging = outputRoot.resolve(s".staging-$hex")
        val scratch = outputRoot.resolve(s".spark-$hex")
        val names = datasets.map(_.name) ++ csv.map(_.name)
        if (!outputRoot.isAbsolute || outputRoot.normalize != outputRoot)
          Left(Vector(s"output root $outputRoot is not an absolute, normalized path"))
        else if (!RunTree.isRealDirectory(outputRoot))
          Left(Vector(s"output root $outputRoot is not a real directory"))
        else if (RunTree.exists(finalDir))
          Left(Vector(s"$finalDir already exists: never overwritten"))
        else if (RunTree.exists(staging) || RunTree.exists(scratch))
          Left(Vector(s"$staging or $scratch already exists: refused"))
        else if (namesProblem(names))
          Left(Vector(s"dataset names must be distinct single path segments: $names"))
        else {
          val created =
            try { Files.createDirectory(staging); None }
            catch { case e: IOException => Some(s"cannot create $staging (create-once): $e") }
          created match {
            case Some(w) => Left(Vector(w)) // not created by this call: left untouched
            case None =>
              val result =
                try stage(spark, staging, scratch, runId, manifest, datasets, csv)
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

  private def namesProblem(names: Vector[String]): Boolean =
    names.distinct.size != names.size ||
      names.exists(n => PartManifest.pathProblem(n).nonEmpty || n.contains("/"))

  /** Steps 4-6 of protocol A: claim, rename, re-verify (outcomes on the object's Scaladoc). */
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
      datasets: Vector[ParquetDataset],
      csv: Vector[CsvDataset]
  ): Either[Vector[String], Unit] = {
    Files.createDirectory(scratch)
    val parquetEntries = datasets.map { d =>
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
            val t = StorePath.local(target)
            for {
              _ <- schemaProblem(spark, d.recordSchemaVersion, t).toLeft(())
              rows <- footerRows(spark, t)
              hash <- RunTree.observe(staging, rel).map(o => (o.byteCount, o.sha256))
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
    val csvEntries = csv.map { d =>
      val out = scratch.resolve(d.name)
      val counts = d.files.map(_._1).collect().toVector
      // Every element already ends in LF; saveAsTextFile appends one LF per element.
      d.files.map(_._2.stripSuffix("\n")).saveAsTextFile(out.toUri.toString)
      val sparkParts = Using.resource(Files.list(out)) { s =>
        s.iterator()
          .asScala
          .map(_.getFileName.toString)
          .filter(n => n.startsWith("part-"))
          .toVector
          .sorted
      }
      if (sparkParts.size != counts.size)
        Left(
          Vector(
            s"dataset ${d.name}: Spark wrote ${sparkParts.size} CSV parts for ${counts.size} partitions"
          )
        )
      else {
        val dir = Files.createDirectory(staging.resolve(d.name))
        val files = sparkParts.zip(counts).zipWithIndex.map { case ((n, rows), i) =>
          val target = dir.resolve(f"part-$i%05d.csv")
          Files.move(out.resolve(n), target)
          val rel = s"${d.name}/${target.getFileName}"
          for {
            _ <- csvHeaderProblem(NioRunStore, StorePath.local(target), d.header).toLeft(())
            hash <- RunTree.observe(staging, rel).map(o => (o.byteCount, o.sha256))
          } yield PartFileV2(
            rel,
            d.recordSchemaVersion,
            PartFormat.Csv,
            rows,
            RowCountSource.Writer,
            hash._1,
            hash._2
          )
        }
        val bad = files.collect { case Left(w) => w }
        val good = files.collect { case Right(f) => f }
        if (bad.nonEmpty) Left(bad)
        else if (good.isEmpty) Left(Vector(s"dataset ${d.name}: Spark wrote no CSV part"))
        else if (good.map(_.rowCount).sum != d.expectedRows)
          Left(
            Vector(
              s"dataset ${d.name}: the CSV parts hold ${good.map(_.rowCount).sum} rows, expected ${d.expectedRows}"
            )
          )
        else Right(good)
      }
    }
    val entries = parquetEntries ++ csvEntries
    val bad = entries.collect { case Left(w) => w }.flatten
    if (bad.nonEmpty) Left(bad)
    else
      Json.encode(manifest) match {
        case Left(e) => Left(Vector(s"run manifest: ${e.mkString("; ")}"))
        case Right(json) =>
          val rmBytes = (json + "\n").getBytes(StandardCharsets.UTF_8)
          writeNew(staging.resolve(PartManifest.RunManifestFile), rmBytes)
          val rm = RunTree.observe(staging, PartManifest.RunManifestFile).toOption.get
          val rmEntry = runManifestEntry(rm)
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
              val problems = observeAll(NioRunStore, StorePath.local(staging)).flatMap(obs => {
                val v = PartManifests.verify(Right(pm), runId, obs)
                if (v.isEmpty) Right(()) else Left(v.map(e => s"staged run: $e"))
              })
              problems
          }
      }
  }

  private def runManifestEntry(rm: ObservedFile): PartFileV2 = PartFileV2(
    PartManifest.RunManifestFile,
    SchemaVersions.RunManifest,
    PartFormat.Jsonl,
    rm.rowCount,
    RowCountSource.LfCount,
    rm.byteCount,
    rm.sha256
  )

  private def observeAll(
      store: RunStore,
      dir: StorePath
  ): Either[Vector[String], Vector[ObservedFile]] =
    RunTree.walk(store, dir, Int.MaxValue, Int.MaxValue) match {
      case Left(w) => Left(Vector(w))
      case Right(es) =>
        val links =
          es.filter(e => e.kind != TreeEntry.Kind.Regular && e.kind != TreeEntry.Kind.Directory)
        if (links.nonEmpty)
          Left(links.map(e => s"'${e.relativePath}' is not a regular file or directory"))
        else {
          val obs = es
            .filter(_.kind == TreeEntry.Kind.Regular)
            .map(e => RunTree.observe(store, dir, e.relativePath))
          val bad = obs.collect { case Left(w) => w }
          if (bad.nonEmpty) Left(bad) else Right(obs.collect { case Right(o) => o })
        }
    }

  // ---------------------------------------------------------------- protocol B: commit marker

  /** What protocol B has written under the run location (for the failure handling). */
  private final class CommitState {
    var wroteFinal = false
    var committed = false
  }

  /** Runs `f` over `xs` in order and stops at the first refusal. */
  private def inOrder[A, B](xs: Vector[A])(
      f: A => Either[Vector[String], B]
  ): Either[Vector[String], Vector[B]] = {
    val out = Vector.newBuilder[B]
    var failed: Option[Vector[String]] = None
    val it = xs.iterator
    while (failed.isEmpty && it.hasNext)
      f(it.next()) match {
        case Left(e) => failed = Some(e)
        case Right(b) => out += b
      }
    failed.toLeft(out.result())
  }

  /** Creates `p` once with `bytes` (Left when anything exists there or the write fails). */
  private def writeOnce(store: RunStore, p: StorePath, bytes: Array[Byte]): Either[String, Unit] =
    try { Using.resource(store.createNew(p))(_.write(bytes)); Right(()) }
    catch { case NonFatal(e) => Left(s"cannot create $p (create-once): $e") }

  /**
   * Copies `src` to the new file `dst` (create-once), streamed; the byte count and SHA-256 of the
   * source bytes as they were copied.
   */
  private def copyOnce(
      store: RunStore,
      src: StorePath,
      dst: StorePath
  ): Either[String, (Long, String)] =
    try {
      val acc = new ObservedFile.Accumulator
      Using.resource(store.open(src)) { in =>
        Using.resource(store.createNew(dst)) { out =>
          val buf = new Array[Byte](BufferBytes)
          var n = in.read(buf)
          while (n >= 0) {
            if (n > 0) { acc.update(buf, 0, n); out.write(buf, 0, n) }
            n = in.read(buf)
          }
        }
      }
      val o = acc.result(dst.name)
      Right((o.byteCount, o.sha256))
    } catch { case NonFatal(e) => Left(s"cannot copy $src to $dst (create-once): $e") }

  /** The regular files of the Spark output directory `dir` whose name `keep` selects, sorted. */
  private def sparkParts(
      store: RunStore,
      dir: StorePath,
      keep: String => Boolean
  ): Either[Vector[String], Vector[StorePath]] =
    store.list(dir) match {
      case Left(e) => Left(Vector(s"cannot list the Spark output $dir: $e"))
      case Right(es) =>
        Right(
          es.filter(e => e.kind == TreeEntry.Kind.Regular && keep(e.name))
            .map(_.name)
            .sorted
            .map(dir.resolve)
        )
    }

  private[output] def publishCommitted(
      spark: SparkSession,
      store: RunStore,
      outputRoot: StorePath,
      manifest: RunManifest,
      datasets: Vector[ParquetDataset],
      csv: Vector[CsvDataset],
      maxMetadataBytes: Long,
      hooks: CommitHooks
  ): Either[Vector[String], PublishedRun] =
    RunId.of(manifest) match {
      case Left(e) => Left(Vector(s"run manifest: $e"))
      case Right(runId) =>
        val hex = runId.stripPrefix("run:")
        val finalDir = outputRoot.resolve(s"run=$hex")
        val scratch = outputRoot.resolve(s".spark-$hex")
        val names = datasets.map(_.name) ++ csv.map(_.name)
        def absent(p: StorePath): Either[Vector[String], Unit] = store.stat(p) match {
          case Right(None) => Right(())
          case Right(Some(_)) => Left(Vector(s"$p already exists: never overwritten"))
          case Left(e) => Left(Vector(s"$p cannot be checked (fail closed): $e"))
        }
        val pre = for {
          _ <- store.stat(outputRoot) match {
            case Right(None) | Right(Some(StoreStat(TreeEntry.Kind.Directory, _))) => Right(())
            case Right(Some(st)) =>
              Left(Vector(s"output root $outputRoot is not a directory (${st.kind})"))
            case Left(e) =>
              Left(Vector(s"output root $outputRoot cannot be checked (fail closed): $e"))
          }
          _ <- absent(finalDir)
          _ <- absent(scratch)
          _ <-
            if (namesProblem(names))
              Left(Vector(s"dataset names must be distinct single path segments: $names"))
            else Right(())
        } yield ()
        pre.flatMap { _ =>
          val state = new CommitState
          val result =
            try
              commit(
                spark,
                store,
                finalDir,
                scratch,
                runId,
                manifest,
                datasets,
                csv,
                maxMetadataBytes,
                hooks,
                state
              )
            catch { case NonFatal(e) => Left(Vector(s"failure while publishing $finalDir: $e")) }
            finally
              try store.deleteRecursively(scratch)
              catch { case NonFatal(_) => () }
          result.left.map(e =>
            if (state.wroteFinal) e ++ markIncomplete(store, finalDir, state) else e
          )
        }
    }

  /** Writes `_UNVERIFIED` into a run location this call wrote to and that is not published. */
  private def markIncomplete(
      store: RunStore,
      dir: StorePath,
      state: CommitState
  ): Vector[String] = {
    val what =
      if (state.committed)
        "_SUCCESS was written but the run did not verify; _SUCCESS is not deleted (protocol B never deletes under the run)"
      else "the partial run has no _SUCCESS"
    writeOnce(store, dir.resolve(RunTree.UnverifiedMarker), Array.emptyByteArray) match {
      case Right(()) =>
        Vector(
          s"$dir: $what, and it is marked ${RunTree.UnverifiedMarker}: every reader refuses it; " +
            "publish again as a new attempt (a new run ID)"
        )
      case Left(w) =>
        Vector(s"$dir: $what; marking it ${RunTree.UnverifiedMarker} failed ($w): do not use it")
    }
  }

  private def commit(
      spark: SparkSession,
      store: RunStore,
      finalDir: StorePath,
      scratch: StorePath,
      runId: String,
      manifest: RunManifest,
      datasets: Vector[ParquetDataset],
      csv: Vector[CsvDataset],
      maxMetadataBytes: Long,
      hooks: CommitHooks,
      state: CommitState
  ): Either[Vector[String], PublishedRun] = {
    // Step 2: distributed writes into the scratch prefix, checked there.
    val parquetStaged = datasets.map { d =>
      RecordSchemas.problem(d.recordSchemaVersion, d.frame.schema) match {
        case Some(w) => Left(Vector(s"dataset ${d.name} before writing: $w"))
        case None =>
          val out = scratch.resolve(d.name)
          d.frame.write.mode(SaveMode.ErrorIfExists).parquet(out.toUri.toString)
          sparkParts(store, out, n => n.startsWith("part-") && n.endsWith(".parquet")).flatMap {
            parts =>
              val checked = parts.map(p =>
                for {
                  _ <- schemaProblem(spark, d.recordSchemaVersion, p).toLeft(())
                  rows <- footerRows(spark, p)
                } yield (p, rows)
              )
              val bad = checked.collect { case Left(w) => w }
              val good = checked.collect { case Right(x) => x }
              if (bad.nonEmpty) Left(bad)
              else if (good.isEmpty) Left(Vector(s"dataset ${d.name}: Spark wrote no Parquet part"))
              else if (good.map(_._2).sum != d.expectedRows)
                Left(
                  Vector(
                    s"dataset ${d.name}: the footers hold ${good.map(_._2).sum} rows, expected ${d.expectedRows}"
                  )
                )
              else Right((d, good))
          }
      }
    }
    val csvStaged = csv.map { d =>
      val out = scratch.resolve(d.name)
      val counts = d.files.map(_._1).collect().toVector
      // Every element already ends in LF; saveAsTextFile appends one LF per element.
      d.files.map(_._2.stripSuffix("\n")).saveAsTextFile(out.toUri.toString)
      sparkParts(store, out, _.startsWith("part-")).flatMap { parts =>
        if (parts.size != counts.size)
          Left(
            Vector(
              s"dataset ${d.name}: Spark wrote ${parts.size} CSV parts for ${counts.size} partitions"
            )
          )
        else if (parts.isEmpty) Left(Vector(s"dataset ${d.name}: Spark wrote no CSV part"))
        else if (counts.sum != d.expectedRows)
          Left(
            Vector(
              s"dataset ${d.name}: the CSV parts hold ${counts.sum} rows, expected ${d.expectedRows}"
            )
          )
        else Right((d, parts.zip(counts)))
      }
    }
    val stagedBad = (parquetStaged ++ csvStaged).collect { case Left(w) => w }.flatten
    if (stagedBad.nonEmpty) Left(stagedBad)
    else {
      val parquet = parquetStaged.collect { case Right(x) => x }
      val csvParts = csvStaged.collect { case Right(x) => x }
      // Step 3: create-once copies to the final names, re-read and re-checked.
      def place(src: StorePath, rel: String): Either[Vector[String], ObservedFile] = {
        val dst = finalDir.resolve(rel)
        state.wroteFinal = true
        (for {
          copied <- copyOnce(store, src, dst)
          o <- RunTree.observe(store, finalDir, rel)
          _ <-
            if (o.byteCount == copied._1 && o.sha256 == copied._2) Right(())
            else
              Left(
                s"$dst re-read as ${o.byteCount} bytes, sha256 ${o.sha256}; copied ${copied._1} bytes, sha256 ${copied._2}"
              )
        } yield o).left.map(Vector(_))
      }
      val parquetFiles = inOrder(parquet.flatMap { case (d, parts) =>
        parts.zipWithIndex.map { case ((src, rows), i) => (d, src, rows, i) }
      }) { case (d, src, rows, i) =>
        val rel = s"${d.name}/" + f"part-$i%05d.parquet"
        val dst = finalDir.resolve(rel)
        for {
          o <- place(src, rel)
          _ <- schemaProblem(spark, d.recordSchemaVersion, dst).toLeft(()).left.map(Vector(_))
          n <- footerRows(spark, dst).left.map(Vector(_))
          _ <-
            if (n == rows) Right(())
            else Left(Vector(s"$dst: footer $n rows, the scratch part had $rows"))
        } yield PartFileV2(
          rel,
          d.recordSchemaVersion,
          PartFormat.Parquet,
          rows,
          RowCountSource.ParquetFooter,
          o.byteCount,
          o.sha256
        )
      }
      val csvFiles = parquetFiles.flatMap(_ =>
        inOrder(csvParts.flatMap { case (d, parts) =>
          parts.zipWithIndex.map { case ((src, rows), i) => (d, src, rows, i) }
        }) { case (d, src, rows, i) =>
          val rel = s"${d.name}/" + f"part-$i%05d.csv"
          for {
            o <- place(src, rel)
            _ <- csvHeaderProblem(store, finalDir.resolve(rel), d.header)
              .toLeft(())
              .left
              .map(Vector(_))
          } yield PartFileV2(
            rel,
            d.recordSchemaVersion,
            PartFormat.Csv,
            rows,
            RowCountSource.Writer,
            o.byteCount,
            o.sha256
          )
        }
      )
      for {
        pf <- parquetFiles
        cf <- csvFiles
        _ = hooks.afterDataParts(finalDir)
        // Step 4: the run manifest, then the part manifest.
        json <- Json.encode(manifest).left.map(e => Vector(s"run manifest: ${e.mkString("; ")}"))
        _ <- writeOnce(
          store,
          finalDir.resolve(PartManifest.RunManifestFile),
          (json + "\n").getBytes(StandardCharsets.UTF_8)
        ).left.map(Vector(_))
        rm <- RunTree.observe(store, finalDir, PartManifest.RunManifestFile).left.map(Vector(_))
        pm <- PartManifestV2
          .build(runId, pf ++ cf :+ runManifestEntry(rm))
          .left
          .map(e => Vector(s"part manifest: ${e.mkString("; ")}"))
        _ = hooks.beforePartManifest(finalDir)
        _ <- writeOnce(
          store,
          finalDir.resolve(PartManifest.FileName),
          (Json.write(pm) + "\n").getBytes(StandardCharsets.UTF_8)
        ).left.map(Vector(_))
        // Step 5: pre-commit verification of every byte.
        _ <- verifyContents(spark, store, finalDir, runId, maxMetadataBytes, committed = false).left
          .map(_.map(w => s"pre-commit verification failed: $w"))
        _ = hooks.beforeSuccess(finalDir)
        // Step 6: the commit point.
        _ <- writeOnce(
          store,
          finalDir.resolve(PartManifest.SuccessMarker),
          Array.emptyByteArray
        ).left
          .map(Vector(_))
        _ = { state.committed = true }
        _ = hooks.afterSuccess(finalDir)
        // Step 7: re-verification of the committed run.
        published <- verifyPublished(spark, store, finalDir, runId, maxMetadataBytes).left
          .map(_.map(w => s"post-publication verification failed: $w"))
      } yield published
    }
  }

  // ---------------------------------------------------------------- verification

  /**
   * Re-verifies a published local run directory from its bytes: no `_UNVERIFIED` and a regular
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
    verifyPublished(spark, NioRunStore, StorePath.local(dir), expectedRunId, maxMetadataBytes)

  /** As the `Path` overload, for a run location in `store` (either protocol). */
  def verifyPublished(
      spark: SparkSession,
      store: RunStore,
      dir: StorePath,
      expectedRunId: String,
      maxMetadataBytes: Long
  ): Either[Vector[String], PublishedRun] =
    verifyContents(spark, store, dir, expectedRunId, maxMetadataBytes, committed = true)

  /**
   * The checks of [[verifyPublished]]. `committed = false` is protocol B's pre-commit check: the
   * same checks, except that `_SUCCESS` must still be absent (it is the one marker not yet written,
   * observed as the empty file it will be).
   */
  private def verifyContents(
      spark: SparkSession,
      store: RunStore,
      dir: StorePath,
      expectedRunId: String,
      maxMetadataBytes: Long,
      committed: Boolean
  ): Either[Vector[String], PublishedRun] = {
    val success = dir.resolve(PartManifest.SuccessMarker)
    for {
      _ <-
        if (RunTree.exists(store, dir.resolve(RunTree.UnverifiedMarker)))
          Left(Vector(s"$dir holds ${RunTree.UnverifiedMarker}: not a published run"))
        else if (committed && !RunTree.isRegularFile(store, success))
          Left(Vector(s"$dir has no regular _SUCCESS: not a published run"))
        else if (!committed && store.stat(success) != Right(None))
          Left(Vector(s"$dir already holds _SUCCESS (or it cannot be checked) before the commit"))
        else Right(())
      text <- RunTree
        .readUtf8(store, dir.resolve(PartManifest.FileName), maxMetadataBytes)
        .left
        .map(Vector(_))
      pm <- PartManifests.decode(text) match {
        case Right(Right(v2)) => Right(v2)
        case Right(Left(_)) => Left(Vector("_parts.json is part-manifest.v1; Parquet runs are v2"))
        case Left(e) => Left(e.map(_.toString))
      }
      observed <- observeAll(store, dir)
      obs =
        if (committed) observed
        else
          observed :+ ObservedFile(
            PartManifest.SuccessMarker,
            0L,
            0L,
            PartManifest.EmptySha256,
            false
          )
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
      _ <- {
        // SP-P6-03 step 2B: every CSV part starts with the header of its dataset's layout.
        val bad = pm.files.filter(_.format == PartFormat.Csv).flatMap { f =>
          val name = f.path.takeWhile(_ != '/')
          NeptuneCsvParts.headerOf(name) match {
            case None => Vector(s"${f.path}: no CSV layout is defined for dataset $name")
            case Some(h) => csvHeaderProblem(store, dir.resolve(f.path), h).toVector
          }
        }
        if (bad.isEmpty) Right(()) else Left(bad)
      }
      rmText <- RunTree
        .readUtf8(store, dir.resolve(PartManifest.RunManifestFile), maxMetadataBytes)
        .left
        .map(Vector(_))
      rm <- Json.decode[RunManifest](rmText.stripSuffix("\n")).left.map(_.map(_.toString))
      _ <- {
        val b = PartManifestV2.checkRunManifest(pm, rm)
        if (b.isEmpty) Right(()) else Left(b.map(_.toString))
      }
    } yield PublishedRun(dir, expectedRunId, rm, pm, store)
  }
}
