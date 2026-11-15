package scholarchain.hadoop.local

import java.io.{File, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path as JPath}
import java.time.Instant

import scala.collection.immutable.SortedMap
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path

import scholarchain.core.{
  BlockKey,
  Canonical,
  Json,
  RunId,
  RunManifest,
  SourceAuthorProfile,
  SourceType,
  WorkRecord
}
import scholarchain.hadoop.jobs.{BlockKeys, BlockingKeySource}
import scholarchain.hadoop.pipeline.{Pipeline, PipelineInputs, RunLayout, Stage, StageReport}
import scholarchain.hadoop.{JobParams, NamedOutputs, RejectRecord}

/**
 * Fixed layout of the per-split local pipeline over the ER candidate samples (work order HD-P5-02).
 * Every path is relative to the repository root. Nothing here is configurable from the command
 * line: the only argument is the split.
 */
object ErSplit {
  val Splits: Vector[String] = Vector("dev", "heldout")
  val SampleRoot = "datasets/samples/er-candidates"
  val LedgerFile = s"$SampleRoot/PROVENANCE.tsv"
  val RangesFile = s"$SampleRoot/DBLP-RELEASE-RANGES.tsv"

  /** Input sub-directories of a split root, each read by its own reader. */
  val InputDirs: Vector[String] =
    Vector("dblp", "openalex-works", "openalex-authors", "crossref-works")

  def inputRoot(split: String): String = s"$SampleRoot/$split"
  def manifestPath(split: String): String =
    s"modules/hadoop/local-runs/er-candidates.$split.run-manifest.json"
  def outputRoot(split: String): String =
    s"modules/hadoop/target/local-pipeline/er-candidates/$split"

  /** Hash domain tags (version 1) of the two digests the entry point verifies. */
  val InputDigestTag = "scholarchain.hadoop.er-split-inputs.v1"
  val ConfigDigestTag = "scholarchain.hadoop.local-config.v1"

  /** Every summary line starts with this token (machine-greppable). */
  val Prefix = "[er-split]"

  /** Attempt directory markers. */
  val InProgress = "_IN_PROGRESS"
  val Failed = "_FAILED"
  val Success = "_SUCCESS"
  val SummaryFile = "summary.txt"

  /** Block caps simulated for the block-size report (None = no cap). */
  val SimulatedCaps: Vector[Option[Int]] = Vector(2, 3, 4, 5, 6, 8).map(Some(_)) :+ None

  /**
   * Repository root: the nearest ancestor of the working directory with build.sbt and the samples.
   */
  def findRepoRoot(start: JPath): Option[JPath] = {
    var dir = start.toAbsolutePath.normalize
    while (
      dir != null && !(Files.isRegularFile(dir.resolve("build.sbt")) &&
        Files.isDirectory(dir.resolve(SampleRoot)))
    ) dir = dir.getParent
    Option(dir)
  }

  /**
   * `configDigest` of a split manifest: the Hadoop pipeline reads no ScholarChain configuration
   * file, and its whole configuration is the manifest's `parameters`, so the digest is the
   * canonical hash of the sorted parameters under [[ConfigDigestTag]].
   */
  def configDigest(parameters: SortedMap[String, String]): String =
    Canonical.hashFields(
      (ConfigDigestTag +: parameters.toVector.flatMap { case (k, v) => Vector(k, v) })*
    )

  /**
   * Input digest: the ledger rows of the split (file and SHA-256), sorted by file (UTF-8 bytes).
   */
  def inputDigest(rows: Vector[LedgerRow]): String =
    Canonical.hashFields(
      (InputDigestTag +: rows
        .sortWith((a, b) => Canonical.compareUtf8(a.file, b.file) < 0)
        .flatMap(r => Vector(r.file, r.sha256)))*
    )

  /**
   * Ledger cross-check of an API snapshot token (HD-P5-04). The ratified token is recorded by
   * dataset-engineer as `er_candidates.api_snapshots.<source>.snapshot_id` in
   * `datasets/dataset-manifest.yaml` (decision D13 option D) and read through [[DatasetManifest]];
   * this function only recomputes the documented derivation from the split's ledger rows (the
   * source tag, `-api-`, and their UTC retrieval date, or `<first>-to-<last>` when the rows span
   * several dates) so that a ledger that drifted from the recorded token is refused. None when the
   * split has no row of that source.
   */
  def ledgerApiSnapshot(rows: Vector[LedgerRow], source: String): Option[String] = {
    val dates = rows.filter(_.source == source).map(_.retrievedAt.take(10)).distinct.sorted
    dates.headOption.map { first =>
      if (dates.size == 1) s"$source-api-$first" else s"$source-api-$first-to-${dates.last}"
    }
  }
}

/** One row of the ER candidate provenance ledger (columns used here only). */
final case class LedgerRow(
    file: String,
    split: String,
    source: String,
    kind: String,
    retrievedAt: String,
    sha256: String
)

object Ledger {

  /** Rows of a tab-separated ledger with a header row; `#` lines are comments. */
  def parseTsv(text: String): Either[String, (Vector[String], Vector[Vector[String]])] = {
    val lines = text.split("\n", -1).toVector.map(_.stripSuffix("\r")).filter(l => l.nonEmpty)
    val data = lines.filterNot(_.startsWith("#"))
    data.headOption match {
      case None => Left("no header row")
      case Some(h) =>
        val header = h.split("\t", -1).toVector
        val rows = data.tail.map(_.split("\t", -1).toVector)
        rows.zipWithIndex.find(_._1.size != header.size) match {
          case Some((r, i)) =>
            Left(s"row ${i + 1} has ${r.size} columns, header has ${header.size}")
          case None => Right((header, rows))
        }
    }
  }

  def read(repoRoot: JPath): Either[String, Vector[LedgerRow]] = {
    val p = repoRoot.resolve(ErSplit.LedgerFile)
    if (!Files.isRegularFile(p)) Left(s"ledger ${ErSplit.LedgerFile} not found")
    else
      parseTsv(new String(Files.readAllBytes(p), StandardCharsets.UTF_8)).left
        .map(e => s"${ErSplit.LedgerFile}: $e")
        .flatMap { case (header, rows) =>
          val need = Vector("file", "split", "source", "kind", "retrieved_at_utc", "sha256")
          need.filterNot(header.contains) match {
            case missing if missing.nonEmpty =>
              Left(s"${ErSplit.LedgerFile}: missing column(s) ${missing.mkString(", ")}")
            case _ =>
              val ix = need.map(header.indexOf(_))
              val parsed =
                rows.map(r => LedgerRow(r(ix(0)), r(ix(1)), r(ix(2)), r(ix(3)), r(ix(4)), r(ix(5))))
              val dup = parsed.groupBy(_.file).collect { case (f, rs) if rs.size > 1 => f }
              if (dup.nonEmpty)
                Left(s"${ErSplit.LedgerFile}: file(s) listed twice: ${dup.mkString(", ")}")
              else Right(parsed)
          }
        }
  }

  /** dblp `release_id` values of the split's rows in DBLP-RELEASE-RANGES.tsv. */
  def dblpReleases(repoRoot: JPath, split: String): Either[String, Vector[String]] = {
    val p = repoRoot.resolve(ErSplit.RangesFile)
    if (!Files.isRegularFile(p)) Left(s"${ErSplit.RangesFile} not found")
    else
      parseTsv(new String(Files.readAllBytes(p), StandardCharsets.UTF_8)).left
        .map(e => s"${ErSplit.RangesFile}: $e")
        .flatMap { case (header, rows) =>
          val (fi, ri) = (header.indexOf("file"), header.indexOf("release_id"))
          if (fi < 0 || ri < 0) Left(s"${ErSplit.RangesFile}: no file or release_id column")
          else
            Right(
              rows
                .filter(_(fi).startsWith(ErSplit.inputRoot(split) + "/"))
                .map(_(ri))
                .distinct
                .sorted
            )
        }
  }
}

/** Status of an existing attempt directory. */
enum AttemptStatus(val tag: String) {
  case Complete extends AttemptStatus("complete")
  case Failed extends AttemptStatus("failed")
  case Partial extends AttemptStatus("partial")
}

object Attempts {
  private val Name = "^attempt-([1-9][0-9]*)$".r

  def statusOf(dir: JPath): AttemptStatus =
    if (Files.exists(dir.resolve(ErSplit.Success))) AttemptStatus.Complete
    else if (Files.exists(dir.resolve(ErSplit.Failed))) AttemptStatus.Failed
    else AttemptStatus.Partial

  /** Existing `attempt-<n>` directories of a split output root, by n. */
  def existing(outRoot: JPath): Vector[(Int, JPath)] =
    if (!Files.isDirectory(outRoot)) Vector.empty
    else {
      val s = Files.list(outRoot)
      try
        s.iterator.asScala.toVector
          .flatMap { p =>
            p.getFileName.toString match {
              case Name(n) if Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) =>
                Vector(n.toInt -> p)
              case _ => Vector.empty
            }
          }
          .sortBy(_._1)
      finally s.close()
    }

  /**
   * The retry rule. A complete attempt (with `_SUCCESS`) refuses every further run of the split
   * (the remedy names the directory to move away). Otherwise a NEW directory `attempt-<max+1>` is
   * created (never reused); earlier failed or partial attempts (no `_SUCCESS`; partial = still has
   * `_IN_PROGRESS` or nothing, as after a kill or timeout) are reported and left untouched.
   */
  def next(outRoot: JPath): Either[String, (JPath, Vector[String])] = {
    val found = existing(outRoot).map { case (n, p) => (n, p, statusOf(p)) }
    found.find(_._3 == AttemptStatus.Complete) match {
      case Some((_, p, _)) =>
        Left(
          s"$p is a complete run of this split (it has ${ErSplit.Success}); refusing a second run. " +
            s"To run again, first move it away, for example: mv $p $p.old"
        )
      case None =>
        val notes = found.map { case (n, p, st) =>
          val why = st match {
            case AttemptStatus.Failed => s"failed (see $p/${ErSplit.Failed})"
            case _ =>
              s"partial: no ${ErSplit.Success} (stopped, killed or timed out; " +
                s"${
                    if (Files.exists(p.resolve(ErSplit.InProgress))) ErSplit.InProgress + " still present"
                    else "no marker"
                  })"
          }
          s"previous attempt=attempt-$n status=${st.tag} dir=$p: $why; left untouched, not reused"
        }
        val n = found.lastOption.map(_._1 + 1).getOrElse(1)
        val dir = outRoot.resolve(s"attempt-$n")
        Files.createDirectories(outRoot)
        Files.createDirectory(dir) // fails if it exists: an attempt directory is never reused
        Right((dir, notes))
    }
  }
}

/** One blocking member as the block-size simulation sees it. */
final case class SimMember(entity: String, kind: String, keys: Vector[String])

/** Blocking outcome of one entity at one cap (simulated; same rules as BlockReducer). */
final case class SimResult(
    blocks: Long,
    pairs: Long,
    oversizeGroups: Long,
    dropped: Long,
    droppedByKind: SortedMap[String, Long],
    largestBlock: Long
)

object BlockSimulation {
  def pairsOf(n: Long): Long = n * (n - 1) / 2

  /**
   * Replays the blocking rounds of BlockReducer for one entity: round r groups the members by their
   * level-r key; a group of at most `cap` members is a block; a larger group is oversize, and its
   * members with a level-(r+1) key go to round r + 1 while the others are dropped. `cap` None = no
   * cap (every level-0 group is a block).
   */
  def run(members: Vector[SimMember], cap: Option[Int], levels: Int): SimResult = {
    var current = members
    var blocks = 0L
    var pairs = 0L
    var oversize = 0L
    var largest = 0L
    val dropped = scala.collection.mutable.Map.empty[String, Long]
    var r = 0
    while (r < levels && current.nonEmpty) {
      val next = Vector.newBuilder[SimMember]
      current.groupBy(_.keys(r)).foreach { case (_, g) =>
        if (cap.forall(g.size <= _)) {
          blocks += 1; pairs += pairsOf(g.size.toLong); largest = math.max(largest, g.size.toLong)
        } else {
          oversize += 1
          g.foreach { m =>
            if (m.keys.size > r + 1) next += m
            else dropped.update(m.kind, dropped.getOrElse(m.kind, 0L) + 1)
          }
        }
      }
      current = next.result()
      r += 1
    }
    SimResult(blocks, pairs, oversize, dropped.values.sum, SortedMap.from(dropped), largest)
  }

  /** Level-0 group sizes (size -> number of groups). */
  def level0Histogram(members: Vector[SimMember]): SortedMap[Int, Int] =
    SortedMap.from(members.groupBy(_.keys(0)).values.groupBy(_.size).view.mapValues(_.size))
}

/** Where the entry point reads and writes (tests point the output root elsewhere). */
final case class ErSplitEnv(
    repoRoot: JPath,
    outputRootOf: String => JPath,
    manifestOf: String => JPath
)

object ErSplitEnv {
  def fixed(repoRoot: JPath): ErSplitEnv =
    ErSplitEnv(
      repoRoot,
      s => repoRoot.resolve(ErSplit.outputRoot(s)),
      s => repoRoot.resolve(ErSplit.manifestPath(s))
    )
}

/**
 * The per-split local pipeline: verifies the split, the committed manifest and every input file,
 * runs the five MapReduce stages (Hadoop local job runner) one after another into a fresh attempt
 * directory, and prints a machine-greppable summary. Exit codes: 0 complete and consistent, 1 a
 * stage failed or a consistency check failed, 2 refused before any job ran (usage, split guard,
 * manifest, inputs, or a complete earlier run).
 */
object ErSplitPipeline {

  val Usage: String =
    "usage: hadoop/Test/runMain scholarchain.hadoop.local.ErSplitPipelineMain <dev|heldout>"

  private final class Refusal(msg: String) extends Exception(msg)
  private def refuse(msg: String): Nothing = throw new Refusal(msg)

  def run(args: Vector[String], env: ErSplitEnv, out: PrintStream): Int = {
    val t0 = System.nanoTime()
    args match {
      case Vector(split) if ErSplit.Splits.contains(split) =>
        try prepareAndRun(split, env, out, t0)
        catch {
          case r: Refusal =>
            out.println(
              s"${ErSplit.Prefix} split=$split status=refused reason=${oneLine(r.getMessage)}"
            )
            2
        }
      case _ =>
        out.println(
          s"${ErSplit.Prefix} status=refused reason=expected exactly one argument dev or heldout"
        )
        out.println(Usage)
        2
    }
  }

  private def oneLine(s: String): String = String.valueOf(s).replaceAll("\\s+", "_")

  /**
   * Decodes and checks the committed manifest of `split` against the ledger and the snapshot tokens
   * of `datasets/dataset-manifest.yaml` (no input is read). Snapshot rule (HD-P5-04, decision D13
   * option D): the OpenAlex and Crossref snapshots must equal `tokens.api` verbatim (never the bulk
   * IDs `tokens.bulk`), must agree with the split's ledger retrieval date, and the dblp snapshot
   * must equal `tokens.dblp` and the split's release in DBLP-RELEASE-RANGES.tsv. A manifest that
   * lists any source more than once in `inputSnapshots` is refused first, whatever the snapshot
   * values (HD-P5-05, finding HD-1).
   */
  def checkManifest(
      split: String,
      manifestFile: JPath,
      ledger: Vector[LedgerRow],
      dblpReleases: Vector[String],
      tokens: ErSnapshotTokens
  ): Either[String, (RunManifest, String)] =
    if (!Files.isRegularFile(manifestFile)) Left(s"manifest $manifestFile not found")
    else
      Json.decode[RunManifest](
        new String(Files.readAllBytes(manifestFile), StandardCharsets.UTF_8)
      ) match {
        case Left(errors) =>
          Left(s"manifest $manifestFile: ${errors.map(_.message).mkString("; ")}")
        case Right(m) =>
          // HD-P5-05 (finding HD-1): every source at most once, whatever its snapshot values. Core
          // validation only requires distinct (source, snapshot) pairs; a repeated source would let
          // a `.toMap` (last entry) and a `.find` (first entry, RunLayout.of) see different
          // snapshots. Refused before any other check, so every later lookup is source-unique.
          val repeated = m.inputSnapshots
            .groupBy(_.source)
            .collect { case (s, refs) if refs.size > 1 => s }
            .toVector
            .map(_.tag)
            .sorted
          if (repeated.nonEmpty)
            Left(
              s"manifest $manifestFile lists source(s) ${repeated.mkString(",")} more than once in " +
                "inputSnapshots (each source must appear exactly once: " +
                m.inputSnapshots
                  .filter(r => repeated.contains(r.source.tag))
                  .map(r => s"${r.source.tag}=${r.snapshot}")
                  .mkString(",") + ")"
            )
          else checkUniqueManifest(split, m, ledger, dblpReleases, tokens)
      }

  /** The checks of [[checkManifest]] on a manifest whose `inputSnapshots` are source-unique. */
  private def checkUniqueManifest(
      split: String,
      m: RunManifest,
      ledger: Vector[LedgerRow],
      dblpReleases: Vector[String],
      tokens: ErSnapshotTokens
  ): Either[String, (RunManifest, String)] = {
    val p = m.parameters
    val rows = ledger.filter(_.split == split)
    val expectedDigest = ErSplit.inputDigest(rows)
    val snap = m.inputSnapshots.map(s => s.source -> s.snapshot).toMap
    val problems = Vector(
      Option.when(p.get(JobParams.InputSplit) != Some(split))(
        s"parameter ${JobParams.InputSplit} is ${p.get(JobParams.InputSplit).getOrElse("absent")}, " +
          s"but the split argument is $split (split guard)"
      ),
      Option.when(rows.isEmpty)(s"the ledger has no row for split $split"),
      Option.when(p.get(JobParams.InputDigest) != Some(expectedDigest))(
        s"parameter ${JobParams.InputDigest} is ${p.get(JobParams.InputDigest).getOrElse("absent")}, " +
          s"but the ledger rows of $split give $expectedDigest"
      ),
      Option.when(m.configDigest != ErSplit.configDigest(p))(
        s"configDigest is ${m.configDigest}, but the parameters give ${ErSplit.configDigest(p)}"
      ),
      Option.when(
        dblpReleases.size != 1 || !snap.get(SourceType.Dblp).contains(dblpReleases.head)
      )(
        s"dblp snapshot is ${snap.get(SourceType.Dblp).getOrElse("absent")}, but " +
          s"${ErSplit.RangesFile} names ${dblpReleases.mkString(",")} for $split"
      ),
      Option.when(!snap.get(SourceType.Dblp).contains(tokens.dblp))(
        s"dblp snapshot is ${snap.get(SourceType.Dblp).getOrElse("absent")}, but " +
          s"${DatasetManifest.File} ${DatasetManifest.DblpSnapshotKey} is ${tokens.dblp}"
      ),
      Option.when(snap.contains(SourceType.Orcid))("the ER samples hold no ORCID input")
    ).flatten ++ DatasetManifest.ApiSources.flatMap { s =>
      val got = snap.get(s)
      val want = tokens.api.get(s)
      val derived = ErSplit.ledgerApiSnapshot(rows, s.tag)
      Vector(
        Option.when(got.isDefined && got == tokens.bulk.get(s))(
          s"${s.tag} snapshot ${got.get} is the bulk snapshot ID " +
            s"${DatasetManifest.bulkSnapshotKey(s)}; the ER samples are API records and carry " +
            s"${DatasetManifest.apiSnapshotKey(s)} (decision D13 option D)"
        ),
        Option.when(got != want)(
          s"${s.tag} snapshot is ${got.getOrElse("absent")}, but ${DatasetManifest.File} " +
            s"${DatasetManifest.apiSnapshotKey(s)} is ${want.getOrElse("absent")}"
        ),
        Option.when(derived.isDefined && derived != want)(
          s"${s.tag}: the $split ledger rows give ${derived.get}, but " +
            s"${DatasetManifest.apiSnapshotKey(s)} is ${want.getOrElse("absent")}"
        )
      ).flatten
    }
    if (problems.nonEmpty) Left(problems.mkString("; "))
    else RunId.of(m).left.map(e => s"manifest: ${e.message}").map(id => (m, id))
  }

  /**
   * Lists, guards and hashes the input files of `split`. Refuses: a missing input directory, any
   * other entry in the split root, a symbolic link or non-regular file, a path that resolves
   * outside the split root, a file without a ledger row of this split, a ledger row without a file,
   * and a SHA-256 that differs from the ledger.
   */
  def collectInputs(
      repoRoot: JPath,
      split: String,
      ledger: Vector[LedgerRow]
  ): Either[String, PipelineInputs] = {
    val rootRel = ErSplit.inputRoot(split)
    val root = repoRoot.resolve(rootRel)
    def list(d: JPath): Vector[JPath] = {
      val s = Files.list(d)
      try s.iterator.asScala.toVector.sortBy(_.getFileName.toString)
      finally s.close()
    }
    def guardFile(realRoot: JPath, rel: String, f: JPath): Option[String] =
      if (Files.isSymbolicLink(f)) Some(s"$rel is a symbolic link (refused)")
      else if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS))
        Some(s"$rel is not a regular file (refused)")
      else if (!f.toRealPath().startsWith(realRoot))
        Some(s"$rel resolves outside the split root $rootRel (refused)")
      else None
    val listed: Either[String, Map[String, Vector[JPath]]] =
      if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
        Left(s"split root $rootRel is not a directory")
      else {
        val realRoot = root.toRealPath()
        val others = list(root).map(_.getFileName.toString).filterNot(ErSplit.InputDirs.contains)
        if (others.nonEmpty) Left(s"$rootRel holds unexpected entries: ${others.mkString(", ")}")
        else
          ErSplit.InputDirs.foldLeft[Either[String, Map[String, Vector[JPath]]]](Right(Map.empty)) {
            (acc, d) =>
              acc.flatMap { m =>
                val dir = root.resolve(d)
                if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS))
                  Left(s"input directory $rootRel/$d is missing or not a directory")
                else {
                  val files = list(dir)
                  files.iterator
                    .flatMap(f => guardFile(realRoot, s"$rootRel/$d/${f.getFileName}", f))
                    .nextOption()
                    .toLeft(m + (d -> files))
                }
              }
          }
      }
    listed.flatMap(perDir => checkLedger(repoRoot, split, ledger, perDir))
  }

  private def checkLedger(
      repoRoot: JPath,
      split: String,
      ledger: Vector[LedgerRow],
      perDir: Map[String, Vector[JPath]]
  ): Either[String, PipelineInputs] = {
    val rows = ledger.filter(_.split == split).map(r => r.file -> r).toMap
    val found = perDir.values.flatten.toVector.map { f =>
      repoRoot.relativize(f).iterator.asScala.map(_.toString).mkString("/") -> f
    }
    val notInLedger = found.map(_._1).filterNot(rows.contains)
    val notOnDisk = rows.keys.toVector.filterNot(found.map(_._1).toSet).sorted
    val mismatched = found.collect {
      case (rel, f)
          if rows.contains(rel) && Canonical.sha256Hex(Files.readAllBytes(f)) != rows(rel).sha256 =>
        rel
    }
    val problems = Vector(
      Option.when(notInLedger.nonEmpty)(
        s"file(s) without a ledger row of $split: ${notInLedger.mkString(", ")}"
      ),
      Option.when(notOnDisk.nonEmpty)(
        s"ledger row(s) of $split without a file: ${notOnDisk.mkString(", ")}"
      ),
      Option.when(mismatched.nonEmpty)(
        s"SHA-256 differs from the ledger: ${mismatched.mkString(", ")}"
      )
    ).flatten
    if (problems.nonEmpty) Left(problems.mkString("; "))
    else {
      def paths(d: String) = perDir(d).map(f => new Path(f.toUri))
      Right(
        PipelineInputs(
          dblp = paths("dblp"),
          openAlexWorks = paths("openalex-works"),
          openAlexAuthors = paths("openalex-authors"),
          openAlexInstitutions = Vector.empty,
          crossrefWorks = paths("crossref-works"),
          orcidRecords = Vector.empty
        )
      )
    }
  }

  def localConf(attempt: JPath): Configuration = {
    val c = new Configuration()
    c.set("fs.defaultFS", "file:///")
    c.set("mapreduce.framework.name", "local")
    c.set("hadoop.tmp.dir", attempt.resolve("hadoop-tmp").toString)
    c
  }

  private def write(p: JPath, lines: Vector[String]): Unit =
    Files.write(p, lines.mkString("", "\n", "\n").getBytes(StandardCharsets.UTF_8))

  private def prepareAndRun(split: String, env: ErSplitEnv, out: PrintStream, t0: Long): Int = {
    val P = s"${ErSplit.Prefix} split=$split"
    val ledger = Ledger.read(env.repoRoot).fold(refuse, identity)
    val releases = Ledger.dblpReleases(env.repoRoot, split).fold(refuse, identity)
    val tokens = DatasetManifest.erSnapshots(env.repoRoot).fold(refuse, identity)
    val (manifest, runId) =
      checkManifest(split, env.manifestOf(split), ledger, releases, tokens).fold(refuse, identity)
    val inputs = collectInputs(env.repoRoot, split, ledger).fold(refuse, identity)
    val (attempt, notes) = Attempts.next(env.outputRootOf(split)).fold(refuse, identity)
    notes.foreach(n => out.println(s"$P $n"))
    val curated = attempt.resolve("curated")
    write(
      attempt.resolve(ErSplit.InProgress),
      Vector(
        s"split=$split",
        s"runId=$runId",
        s"startedAt=${Instant.now()}",
        s"manifest=${env.manifestOf(split)}"
      )
    )
    out.println(s"$P runId=$runId attempt=${attempt.getFileName} status=started output=$attempt")
    out.println(
      s"$P inputs dblp=${inputs.dblp.size} openalex-works=${inputs.openAlexWorks.size} " +
        s"openalex-authors=${inputs.openAlexAuthors.size} crossref-works=${inputs.crossrefWorks.size}"
    )
    out.println(
      s"$P snapshots " + manifest.inputSnapshots
        .map(s => s"${s.source.tag}=${s.snapshot}")
        .mkString(" ") + s" source=${DatasetManifest.File}"
    )
    val conf = localConf(attempt)
    val timings = Vector.newBuilder[(String, Long, String)]
    val reports = Vector.newBuilder[StageReport]
    def fail(stage: String, errors: Vector[String]): Int = {
      val elapsed = timings.result()
      val lines =
        elapsed.map { case (s, ms, st) => s"$P elapsed stage=$s ms=$ms status=$st" } ++ Vector(
          s"$P elapsed total ms=${(System.nanoTime() - t0) / 1000000L}",
          s"$P runId=$runId attempt=${attempt.getFileName} status=failed stage=$stage output=$attempt"
        ) ++ errors.map(e => s"$P error=${oneLine(e)}")
      write(attempt.resolve(ErSplit.Failed), lines)
      Files.deleteIfExists(attempt.resolve(ErSplit.InProgress))
      lines.foreach(out.println)
      1
    }
    val failedAt = Stage.values.toVector.iterator
      .map { stage =>
        val s0 = System.nanoTime()
        val res =
          try Pipeline.run(conf, manifest, new Path(curated.toUri), inputs, Vector(stage))
          catch { case NonFatal(e) => Left(Vector(e.toString)) }
        val ms = (System.nanoTime() - s0) / 1000000L
        res match {
          case Left(errors) =>
            timings += ((stage.name, ms, "failed")); Some(stage.name -> errors)
          case Right(rs) =>
            rs.foreach(reports += _); timings += ((stage.name, ms, rs.map(_.status).mkString(",")));
            None
        }
      }
      .collectFirst { case Some(f) => f }
    failedAt match {
      case Some((stage, errors)) => fail(stage, errors)
      case None =>
        val summary =
          try
            Right(
              summarize(split, runId, attempt, conf, manifest, curated, reports.result(), inputs)
            )
          catch { case NonFatal(e) => Left(e.toString) }
        summary match {
          case Left(e) => fail("summary", Vector(e))
          case Right((lines, consistent)) =>
            val all =
              timings.result().map { case (s, ms, st) =>
                s"$P elapsed stage=$s ms=$ms status=$st"
              } ++
                lines ++ Vector(
                  s"$P elapsed total ms=${(System.nanoTime() - t0) / 1000000L}",
                  s"$P runId=$runId attempt=${attempt.getFileName} " +
                    s"status=${if (consistent) "complete" else "inconsistent"} output=$attempt"
                )
            write(attempt.resolve(ErSplit.SummaryFile), all)
            if (consistent) write(attempt.resolve(ErSplit.Success), Vector(s"runId=$runId"))
            else write(attempt.resolve(ErSplit.Failed), all)
            Files.deleteIfExists(attempt.resolve(ErSplit.InProgress))
            all.foreach(out.println)
            if (consistent) 0 else 1
        }
    }
  }

  /**
   * (sourceDataset, snapshot) of every object in `v` that has both keys (records nest Provenance).
   */
  def provenanceSnapshots(v: ujson.Value): Vector[(String, String)] = v match {
    case o: ujson.Obj =>
      val here = (o.value.get("sourceDataset"), o.value.get("snapshot")) match {
        case (Some(ujson.Str(d)), Some(ujson.Str(s))) => Vector(d -> s)
        case _ => Vector.empty
      }
      here ++ o.value.values.toVector.flatMap(provenanceSnapshots)
    case a: ujson.Arr => a.value.toVector.flatMap(provenanceSnapshots)
    case _ => Vector.empty
  }

  private def detailMap(r: RejectRecord): Map[String, String] =
    r.detail.flatMap { d =>
      d.indexOf('=') match {
        case -1 => None
        case i => Some(d.substring(0, i) -> d.substring(i + 1))
      }
    }.toMap

  /** Summary lines and whether every consistency check passed. */
  private def summarize(
      split: String,
      runId: String,
      attempt: JPath,
      conf: Configuration,
      manifest: RunManifest,
      curated: JPath,
      reports: Vector[StageReport],
      inputs: PipelineInputs
  ): (Vector[String], Boolean) = {
    val P = s"${ErSplit.Prefix} split=$split"
    val layout = RunLayout.of(new Path(curated.toUri), manifest, runId).fold(refuse, identity)
    val fs = layout.root.getFileSystem(conf)
    def lines(p: Path) = Pipeline.readLines(conf, fs, p)
    val byStage = reports.map(r => r.stage -> r.counters).toMap
    def c(stage: String, k: String): Long =
      byStage.get(stage).flatMap(_.get(k)).getOrElse(0L)
    val entities = Vector("researcher", "work")
    val out = Vector.newBuilder[String]
    var ok = true

    // ---- normalize: per input
    val n = "normalize"
    Vector("dblp", "openalex-works", "openalex-authors", "crossref-works").foreach { in =>
      out += s"$P normalize input=$in read=${c(n, s"INPUT.$in")} accepted=${c(n, s"ACCEPTED.$in")}"
    }
    out += s"$P normalize rejected=${c(n, "REJECTED")} flagged=${c(n, "FLAGGED")} " +
      s"works=${c(n, "EMITTED.works")} mentions=${c(n, "EMITTED.mentions")} profiles=${c(n, "EMITTED.profiles")} " +
      s"duplicate=${c(n, "DUPLICATE")} conflict=${c(n, "CONFLICT")}"

    // ---- snapshot provenance (HD-P5-04): every Provenance object in the normalized works,
    // mentions and profiles carries its source's manifest snapshot (API tokens for OpenAlex and
    // Crossref, decision D13 option D)
    val expectedSnap = manifest.inputSnapshots.map(s => s.source.tag -> s.snapshot).toMap
    val provSnaps = Vector(NamedOutputs.Works, NamedOutputs.Mentions, NamedOutputs.Profiles)
      .flatMap(o => lines(layout.normalized(o)))
      .flatMap(l => ErSplitPipeline.provenanceSnapshots(ujson.read(l)))
    val bySource = provSnaps.groupBy(_._1).toVector.sortBy(_._1)
    val provCheck = provSnaps.nonEmpty && bySource.forall { case (src, ps) =>
      !SourceType.fromTag(src).forall(_.isDataset) ||
      ps.forall(p => expectedSnap.get(src).contains(p._2))
    }
    ok &&= provCheck
    bySource.foreach { case (src, ps) =>
      out += s"$P provenance source=$src records=${ps.size} " +
        s"snapshots=${ps.map(_._2).distinct.sorted.mkString(",")} expected=${expectedSnap.getOrElse(src, "absent")}"
    }
    out += s"$P provenance check=every-record-snapshot==manifest-snapshot:${
        if (provCheck) "ok" else "FAILED"
      }"

    // ---- every `conflict` record of normalize (mapper flags, e.g. several DOIs in one DBLP
    // record, and reducer flags for copies that differ in content), with its own detail
    val conflicts = lines(layout.normalized(NamedOutputs.Rejects))
      .map(RejectRecord.fromJson)
      .filter(_.reason == "conflict")
      .sortBy(r => (r.sourceDataset, r.sourceRecordId.getOrElse("")))
    conflicts.foreach { r =>
      out += s"$P normalize conflict source=${r.sourceDataset} id=${r.sourceRecordId.getOrElse("-")} " +
        s"disposition=${r.disposition} file=${r.locator.split('/').last} rejectId=${r.rejectId} " +
        s"detail=${oneLine(r.detail.mkString(" | "))}"
    }
    // ---- DUPLICATE copies carry no record when they are identical apart from provenance; the
    // driver names them from the JSON inputs (source ID -> files), a diagnostic outside MapReduce.
    def jsonIds(paths: Vector[Path], source: String, id: ujson.Value => Option[String]) =
      paths.flatMap { p =>
        val f = java.nio.file.Paths.get(p.toUri)
        new String(Files.readAllBytes(f), StandardCharsets.UTF_8)
          .split("\n")
          .toVector
          .filter(_.trim.nonEmpty)
          .flatMap(l => scala.util.Try(ujson.read(l)).toOption.flatMap(id))
          .map(i => (source, i, f.getFileName.toString))
      }
    val openAlexId = (v: ujson.Value) =>
      scholarchain.hadoop.normalize.Js.str(v, "id").map(_.split('/').last)
    val crossrefDoi = (v: ujson.Value) =>
      scholarchain.hadoop.normalize.CrossrefNormalizer
        .unwrap(v)
        .toOption
        .flatMap(scholarchain.hadoop.normalize.Js.str(_, "DOI"))
        .map(_.toLowerCase(java.util.Locale.ROOT))
    val dupGroups = (jsonIds(inputs.openAlexWorks, "openalex-work", openAlexId) ++
      jsonIds(inputs.openAlexAuthors, "openalex-author", openAlexId) ++
      jsonIds(inputs.crossrefWorks, "crossref-work", crossrefDoi))
      .groupBy(t => (t._1, t._2))
      .filter(_._2.size > 1)
      .toVector
      .sortBy(_._1)
    dupGroups.foreach { case ((src, id), fs) =>
      out += s"$P normalize duplicate source=$src id=$id copies=${fs.size} files=${fs.map(_._3).sorted.mkString("|")}"
    }
    val explained = dupGroups.map(_._2.size - 1L).sum
    out += s"$P normalize conflictRecords=${conflicts.size} duplicateCounter=${c(n, "DUPLICATE")} " +
      s"duplicateCopiesNamedFromJsonInputs=$explained " +
      s"allDuplicatesNamed=${explained == c(n, "DUPLICATE")}"

    // ---- distinct candidate pairs
    val k = "candidates"
    val finalDir = new Path(layout.stage(Stage.Candidates), Pipeline.FinalJob)
    val readBack = entities.map { e =>
      e -> lines(new Path(finalDir, e))
        .map(l => ujson.read(l)("candidateId").str)
        .distinct
        .size
        .toLong
    }.toMap
    val distinct = entities.map(e => e -> c(k, s"EMITTED.$e-candidates")).toMap
    val pairs = c(k, "CANDIDATE_PAIRS")
    val merged = c(k, "CANDIDATE_MERGED")
    val pairCheck =
      distinct.values.sum == pairs - merged && entities.forall(e => readBack(e) == distinct(e))
    ok &&= pairCheck
    out += s"$P distinct.researcher=${distinct("researcher")} distinct.work=${distinct("work")} " +
      s"distinct.total=${distinct.values.sum} candidatePairs=$pairs " +
      s"candidatePairs.researcher=${c(k, "CANDIDATE_PAIRS.researcher")} candidatePairs.work=${c(k, "CANDIDATE_PAIRS.work")} " +
      s"candidateMerged=$merged readBack.researcher=${readBack("researcher")} readBack.work=${readBack("work")} " +
      s"check=distinct.total==candidatePairs-candidateMerged,readBack==distinct:${
          if (pairCheck) "ok" else "FAILED"
        }"

    // ---- oversize blocks (from the run's flagged oversize-block records)
    val b = "blocking"
    val blockingDir = layout.stage(Stage.Blocking)
    val rounds = fs
      .listStatus(blockingDir)
      .toVector
      .filter(s => s.isDirectory && s.getPath.getName.startsWith("round-"))
      .map(_.getPath)
      .sortBy(_.getName)
    val oversize = rounds
      .flatMap(r => lines(new Path(r, NamedOutputs.Rejects)))
      .map(RejectRecord.fromJson)
      .filter(_.reason == "oversize-block")
      .map(detailMap)

    // ---- block-size simulation over the run's curated works and profiles
    val src = BlockingKeySource.load(manifest.parameters(JobParams.BlockingKeySourceClass))
    def valid(keys: Vector[String]) =
      keys.nonEmpty && keys.zipWithIndex.forall { case (key, i) =>
        BlockKey.checkKey(s"keys[$i]", key).isEmpty
      }
    val works = lines(layout.works).map(l =>
      Json.decode[WorkRecord](l).fold(e => refuse(e.toString), identity)
    )
    val profiles = lines(layout.profiles).map(l =>
      Json.decode[SourceAuthorProfile](l).fold(e => refuse(e.toString), identity)
    )
    val members =
      (works.flatMap { w =>
        BlockKeys.workMembers(src, w).map { m =>
          val kind = if (m.entity == "work") s"work.${m.source}" else s"mention.${m.source}"
          SimMember(m.entity, kind, m.keys)
        }
      } ++ profiles.flatMap(p =>
        BlockKeys
          .profileMembers(src, p)
          .map(m => SimMember(m.entity, s"profile.${m.source}", m.keys))
      )).filter(m => valid(m.keys))
    val cap = manifest.parameters(JobParams.BlockingMaxBlockSize).toInt
    val policy = manifest.parameters(JobParams.BlockingOversizePolicyKey)

    entities.foreach { e =>
      val ov = oversize.filter(_.get("entity").contains(e))
      def num(d: Map[String, String], key: String) =
        d.get(key).flatMap(_.toLongOption).getOrElse(0L)
      val withDrops = ov.filter(num(_, "dropped") > 0)
      val sim = BlockSimulation.run(members.filter(_.entity == e), Some(cap), src.levels)
      val simCheck =
        sim.dropped == c(b, s"BLOCK_MEMBERS_DROPPED.$e") &&
          sim.oversizeGroups == ov.size.toLong &&
          sim.pairs == c(k, s"CANDIDATE_PAIRS.$e")
      ok &&= simCheck
      out += s"$P oversize entity=$e cap=$cap policy=$policy groups=${ov.size} groupsWithDrops=${withDrops.size} " +
        s"membersDropped=${c(b, s"BLOCK_MEMBERS_DROPPED.$e")} membersRefined=${c(b, s"BLOCK_MEMBERS_REFINED.$e")} " +
        s"uncappedPairsOfOversizeGroups=${ov.map(d => BlockSimulation.pairsOf(num(d, "size"))).sum} " +
        s"uncappedPairsOfDroppingGroups=${withDrops.map(d => BlockSimulation.pairsOf(num(d, "size"))).sum} " +
        s"pairsInvolvingDroppedMembers=${ov.map(d => BlockSimulation.pairsOf(num(d, "size")) - BlockSimulation.pairsOf(num(d, "refinedToNextLevel"))).sum} " +
        s"largestOversizeGroup=${(0L +: ov.map(num(_, "size"))).max} " +
        s"droppedByKind=${
            if (sim.droppedByKind.isEmpty) "none"
            else sim.droppedByKind.map { case (kk, v) => s"$kk:$v" }.mkString(",")
          } " +
        s"simulationMatchesRun=$simCheck"
      val hist = BlockSimulation.level0Histogram(members.filter(_.entity == e))
      out += s"$P histogram entity=$e level=0 members=${members.count(_.entity == e)} groups=${hist.values.sum} " +
        s"sizes=${hist.map { case (s, cnt) => s"$s:$cnt" }.mkString(",")}"
      ErSplit.SimulatedCaps.foreach { cp =>
        val r = BlockSimulation.run(members.filter(_.entity == e), cp, src.levels)
        out += s"$P capsim entity=$e cap=${cp.map(_.toString).getOrElse("none")} blocks=${r.blocks} " +
          s"pairs=${r.pairs} oversizeGroups=${r.oversizeGroups} membersDropped=${r.dropped} " +
          s"largestBlock=${r.largestBlock} " +
          s"droppedByKind=${
              if (r.droppedByKind.isEmpty) "none"
              else r.droppedByKind.map { case (kk, v) => s"$kk:$v" }.mkString(",")
            }"
      }
    }
    val t = "teams"
    def stat(name: String) =
      reports
        .find(_.stage == t)
        .flatMap(_.stats.get(name))
        .map(s => s"${s.value}(${s.id})")
        .getOrElse("none")
    out += s"$P teams teams=${c(t, "EMITTED.teams")} coauthorPairs=${c(t, "EMITTED_PAIR")} " +
      s"oversizeTeams=${c(t, "OVERSIZE_TEAM")} maxTeamSize=${stat("maxTeamSize")} " +
      s"maxPairExpansion=${stat("maxPairExpansion")}"
    (out.result(), ok)
  }
}
