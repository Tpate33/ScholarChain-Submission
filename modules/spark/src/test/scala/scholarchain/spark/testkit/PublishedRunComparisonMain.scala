package scholarchain.spark.testkit

import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.{
  FileAlreadyExistsException,
  Files,
  InvalidPathException,
  LinkOption,
  Path,
  Paths,
  StandardOpenOption
}

import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}
import scala.util.control.NonFatal

import org.apache.spark.sql.SparkSession

import scholarchain.core.{PartFormat, SchemaVersions, TargetStore}
import scholarchain.spark.input.{ReaderLimits, VerifiedArchiveReader}
import scholarchain.spark.output.{LoadManifests, PublishedLoads, PublishedRun, RunPublisher}
import scholarchain.spark.store.StorePath

/**
 * Comparison of two published collaboration-analysis runs (SP-P6-06; the entry point required by
 * build-engineer's `sbt sparkSubmitLocal`, BE-P6-09, `SparkSubmitLocal.CompareMainClass`). Test
 * tree only: forked by the build on `sparkAnalytics/Test/fullClasspath`; never part of the job jar.
 *
 * Arguments (each exactly once, `--flag value`, nothing else): [[LeftRoot]] and [[RightRoot]], the
 * two job output roots (absolute, normalized, different, neither inside the other); [[NonceFlag]],
 * 64 lower-case hex chosen by the caller; [[ResultFlag]], an absolute file that must not exist, in
 * an existing real directory outside both roots. Any refusal of the arguments exits
 * [[ExitArguments]] before a Spark session exists.
 *
 * Per root, in order (a refusal exits [[ExitImpossible]] and writes no result file):
 *   1. before Spark: the root is a real directory (no symbolic link on its path) holding exactly
 *      one `run=<64 hex>` directory and its `load=<same hex>` directory, and nothing else;
 *   2. the existing verified readers: [[VerifiedArchiveReader.layout]] of the run (run name, real
 *      directory, no `_UNVERIFIED`, no staging entry beside or inside it, a regular `_SUCCESS`, a
 *      bounded NOFOLLOW walk), [[RunPublisher.verifyPublished]] (every file against `_parts.json`
 *      from its bytes, Parquet schemas and footer rows, CSV headers, the run manifest of the run
 *      ID), and [[LoadManifests.verify]] (exactly the four store manifests, each decoding and
 *      passing `StructureValidation.checkLoadManifests` against the run's observed `_parts.json`
 *      hash).
 *
 * Then, in Spark ([[LogicalReproducibility]], reused unchanged): the row multisets of the 16
 * Parquet datasets ([[LogicalReproducibility.ParquetDatasets]]: StructType and every row with its
 * multiplicity) and of the 12 Neptune CSV datasets ([[LogicalReproducibility.CsvDatasets]]: header
 * columns and every row with its multiplicity). A dataset with no listed part on a side has 0 rows
 * there (its declared rows otherwise) and is `equal` only when neither side lists a part; no Spark
 * comparison runs for it. Only counts and at most 20 differing rows per side reach the driver; the
 * differing rows are never printed or written (only their number).
 *
 * Result (UTF-8, LF line ends, final LF, tab-separated, created once; exit [[ExitCompared]]
 * whenever it is written, whether or not the datasets are equal: facts only, the caller applies its
 * baseline):
 * {{{
 * format=scholarchain.spark.published-run-comparison.v1
 * nonce=<hex>
 * run      left|right  run=<hex>
 * count    left|right  edges|metrics  <declared rows of coauthor-edge.v2 | researcher-metrics.v2>
 * load     left|right  <store>  <items>  <sum of item rows>
 * dataset  <name>  <left rows>  <right rows>  equal|different
 * datasets=<number of dataset lines>
 * complete=<hex>
 * }}}
 * Store tags ([[storeTag]]): `neptune` (core `neptune-bulk-load`), `dynamodb`, `s3`, `s3-vectors`.
 * Dataset names: the record schema label of a Parquet dataset, the directory of a CSV dataset.
 *
 * Spark: [[main]] starts `local[2]` ([[LocalSpark]]: UI off, loopback driver, scratch under a fresh
 * directory in `java.io.tmpdir`, deleted afterwards); no network, no AWS, no `s3a:` location (both
 * roots are local paths read through java.nio and `file:` URIs). Driver memory: the part and load
 * manifests (bounded by [[Limits]] `maxMetadataBytes`), 28 per-dataset counts and at most 40 rows
 * per dataset; shuffles: one grouping per side and two `except` per dataset
 * (spark.sql.shuffle.partitions 1). Envelope bounds [[Limits]] are those of the sparkIT suite and
 * the job flags of `sparkSubmitLocal` (20000 tree entries, depth 4, 1 MiB metadata), not data
 * policy.
 */
object PublishedRunComparisonMain {

  val Prefix = "published-run-comparison"
  val Format = "scholarchain.spark.published-run-comparison.v1"

  val ExitCompared = 0
  val ExitImpossible = 1
  val ExitArguments = 2

  val LeftRoot = "--left-output-root"
  val RightRoot = "--right-output-root"
  val NonceFlag = "--nonce"
  val ResultFlag = "--result"
  val Flags: Vector[String] = Vector(LeftRoot, RightRoot, NonceFlag, ResultFlag)

  val Master = "local[2]"

  val Limits: ReaderLimits =
    ReaderLimits(
      maxTreeEntries = 20000,
      maxTreeDepth = 4,
      maxDriverValidationRows = 2000,
      maxDriverValidationBytes = 64L * 1024 * 1024,
      maxMetadataBytes = 1024L * 1024
    )

  /** At most this many differing rows per side reach the driver (counted, never printed). */
  val DifferenceLimit = 20

  private val Hex64 = "^[0-9a-f]{64}$".r
  private val RunName = "^run=([0-9a-f]{64})$".r

  final case class Arguments(left: Path, right: Path, nonce: String, result: Path)

  /** One side's facts for the result file. */
  final case class Side(
      runName: String,
      edges: Long,
      metrics: Long,
      loads: Vector[(String, Long, Long)]
  )

  /** One dataset line: name, left rows, right rows, logically equal. */
  final case class DatasetLine(name: String, left: Long, right: Long, equal: Boolean)

  def usage: String =
    s"arguments (each exactly once): $LeftRoot <absolute dir> $RightRoot <absolute dir> " +
      s"$NonceFlag <64 lower-case hex> $ResultFlag <absolute file that must not exist>"

  def storeTag(t: TargetStore): String = t match {
    case TargetStore.NeptuneBulkLoad => "neptune"
    case other => other.tag
  }

  // ---------------------------------------------------------------- arguments

  private def absolute(flag: String, s: String): Either[String, Path] =
    (try Right(Paths.get(s))
    catch { case _: InvalidPathException => Left(s"$flag '$s' is not a path") }).flatMap { p =>
      if (!p.isAbsolute) Left(s"$flag '$s' is not absolute")
      else if (p.normalize != p || p.toString != s)
        Left(s"$flag '$s' is not normalized (no '.', '..', repeated or trailing '/')")
      else if (p.getParent == null) Left(s"$flag '$s' is the file-system root")
      else Right(p)
    }

  /** The accepted arguments, or every refusal. Reads only the result file's parent and name. */
  def parse(args: Seq[String]): Either[Vector[String], Arguments] = {
    val v = args.toVector
    if (v.size != 2 * Flags.size)
      Left(
        Vector(
          s"expected exactly ${2 * Flags.size} arguments (four '--flag value' pairs), got ${v.size}"
        )
      )
    else {
      val pairs = v.grouped(2).toVector.map(p => (p(0), p(1)))
      val unknown = pairs.map(_._1).filterNot(Flags.contains).map(f => s"unknown flag '$f'")
      val repeated = Flags.collect {
        case f if pairs.count(_._1 == f) > 1 => s"$f is given ${pairs.count(_._1 == f)} times"
      }
      val missing = Flags.filterNot(f => pairs.exists(_._1 == f)).map(f => s"$f is required")
      if (unknown.nonEmpty || repeated.nonEmpty || missing.nonEmpty)
        Left(unknown ++ repeated ++ missing)
      else {
        val m = pairs.toMap
        val left = absolute(LeftRoot, m(LeftRoot))
        val right = absolute(RightRoot, m(RightRoot))
        val result = absolute(ResultFlag, m(ResultFlag))
        val nonce =
          if (Hex64.matches(m(NonceFlag))) Right(m(NonceFlag))
          else Left(s"$NonceFlag is not 64 lower-case hex")
        val form = Vector(left, right, result, nonce).collect { case Left(w) => w }
        if (form.nonEmpty) Left(form)
        else {
          val (l, r, f) = (left.toOption.get, right.toOption.get, result.toOption.get)
          val rel = Vector(
            Option.when(l == r)(s"$LeftRoot and $RightRoot are the same directory"),
            Option.when(l != r && (l.startsWith(r) || r.startsWith(l)))(
              s"$LeftRoot and $RightRoot are nested"
            ),
            Option.when(f.startsWith(l) || f.startsWith(r))(
              s"$ResultFlag lies inside an output root"
            )
          ).flatten
          val parent = f.getParent
          val file = Vector(
            Option.when(
              Files.exists(f, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(f)
            )(s"$ResultFlag $f already exists (the result file is created once)"),
            Option.when(
              !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) ||
                Try(parent.toRealPath() != parent).getOrElse(true)
            )(s"$ResultFlag: the directory $parent is missing or reached through a symbolic link")
          ).flatten
          if (rel.nonEmpty || file.nonEmpty) Left(rel ++ file)
          else Right(Arguments(l, r, nonce.toOption.get, f))
        }
      }
    }
  }

  // ---------------------------------------------------------------- per root, before Spark

  final case class RootLayout(root: Path, run: Path, load: Path, hex: String)

  private def names(dir: Path): Vector[String] =
    Using.resource(Files.list(dir))(
      _.iterator().asScala.map(_.getFileName.toString).toVector.sorted
    )

  /** Step 1: exactly `run=<hex>` and `load=<hex>` in a real directory. */
  def rootLayout(label: String, root: Path): Either[Vector[String], RootLayout] =
    if (
      !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) ||
      Try(root.toRealPath() != root).getOrElse(true)
    )
      Left(
        Vector(
          s"$label output root $root is not a real directory (missing, or a symbolic link on its path)"
        )
      )
    else {
      val ns = names(root)
      val runs = ns.filter(n => RunName.matches(n))
      runs match {
        case Vector(run) =>
          val hex = run.stripPrefix("run=")
          val load = LoadManifests.DirPrefix + hex
          val others = ns.filterNot(n => n == run || n == load)
          val problems = Vector(
            Option.when(!ns.contains(load))(s"$label output root $root holds no $load"),
            Option.when(others.nonEmpty)(
              s"$label output root $root holds entries other than $run and $load: ${others.mkString(", ")}"
            )
          ).flatten ++ Vector(run, load).filter(ns.contains).collect {
            case n if !Files.isDirectory(root.resolve(n), LinkOption.NOFOLLOW_LINKS) =>
              s"$label $root/$n is not a real directory"
          }
          if (problems.nonEmpty) Left(problems)
          else Right(RootLayout(root, root.resolve(run), root.resolve(load), hex))
        case other =>
          Left(
            Vector(
              s"$label output root $root holds ${other.size} run=<64 hex> directories, expected exactly 1" +
                (if (ns.isEmpty) " (it is empty)" else s": ${ns.mkString(", ")}")
            )
          )
      }
    }

  // ---------------------------------------------------------------- per root, verified readers

  /** Step 2: the verified readers over one root. */
  def verifySide(
      spark: SparkSession,
      label: String,
      r: RootLayout
  ): Either[Vector[String], (PublishedRun, PublishedLoads)] = {
    val layout = VerifiedArchiveReader.layout("analysis", r.run, Limits)
    if (layout.nonEmpty) Left(layout.map(x => s"$label run ${r.run}: $x"))
    else
      for {
        published <- RunPublisher
          .verifyPublished(spark, r.run, "run:" + r.hex, Limits.maxMetadataBytes)
          .left
          .map(_.map(w => s"$label run ${r.run}: $w"))
        loads <- LoadManifests
          .verify(r.load, published, Limits.maxMetadataBytes)
          .left
          .map(_.map(w => s"$label load manifests ${r.load}: $w"))
      } yield (published, loads)
  }

  // ---------------------------------------------------------------- facts

  private def declaredRows(run: PublishedRun, parts: Vector[StorePath]): Long = {
    val listed = parts.toSet
    run.partManifest.files.filter(f => listed.contains(run.dir.resolve(f.path))).map(_.rowCount).sum
  }

  def side(run: PublishedRun, loads: PublishedLoads): Side = {
    def parquetRows(schema: String) =
      run.partManifest.files
        .filter(f => f.recordSchemaVersion == schema && f.format == PartFormat.Parquet)
        .map(_.rowCount)
        .sum
    val byStore = TargetStore.values.toVector.flatMap(t =>
      loads.of(t).map(m => (storeTag(t), m.items.size.toLong, m.items.map(_.rowCount).sum))
    )
    Side(
      run.dir.name,
      parquetRows(SchemaVersions.CoauthorEdgeV2),
      parquetRows(SchemaVersions.ResearcherMetricsV2),
      byStore
    )
  }

  /**
   * The 28 dataset lines; `out` receives one diagnostic line per differing dataset (counts only).
   */
  def compareDatasets(
      spark: SparkSession,
      a: PublishedRun,
      b: PublishedRun,
      out: PrintStream
  ): Vector[DatasetLine] = {
    def one(
        name: String,
        pa: Vector[StorePath],
        pb: Vector[StorePath],
        compare: (
            SparkSession,
            Vector[StorePath],
            Vector[StorePath],
            Int
        ) => LogicalReproducibility.Report
    ): DatasetLine =
      if (pa.isEmpty || pb.isEmpty)
        DatasetLine(name, declaredRows(a, pa), declaredRows(b, pb), pa.isEmpty && pb.isEmpty)
      else {
        val r = compare(spark, pa, pb, DifferenceLimit)
        if (!r.equal)
          out.println(
            s"$Prefix dataset $name differs: rows ${r.rowsA}/${r.rowsB} sameSchema=${r.sameSchema} " +
              s"differing rows shown to the driver (at most $DifferenceLimit per side): " +
              s"${r.onlyInA.size}/${r.onlyInB.size} (rows not printed)"
          )
        DatasetLine(name, r.rowsA, r.rowsB, r.equal)
      }
    LogicalReproducibility.ParquetDatasets.map(s =>
      one(s, a.parquetPartsOf(s), b.parquetPartsOf(s), LogicalReproducibility.compare)
    ) ++ LogicalReproducibility.CsvDatasets.map(n =>
      one(n, a.datasetParts(n), b.datasetParts(n), LogicalReproducibility.compareCsv)
    )
  }

  /** The exact result text (v1). */
  def render(nonce: String, left: Side, right: Side, datasets: Vector[DatasetLine]): String = {
    def sideLines(label: String, s: Side) =
      Vector(s"count\t$label\tedges\t${s.edges}", s"count\t$label\tmetrics\t${s.metrics}")
    def loadLines(label: String, s: Side) =
      s.loads.map { case (t, items, rows) => s"load\t$label\t$t\t$items\t$rows" }
    val lines =
      Vector(
        s"format=$Format",
        s"nonce=$nonce",
        s"run\tleft\t${left.runName}",
        s"run\tright\t${right.runName}"
      ) ++
        sideLines("left", left) ++ sideLines("right", right) ++
        loadLines("left", left) ++ loadLines("right", right) ++
        datasets.map(d =>
          s"dataset\t${d.name}\t${d.left}\t${d.right}\t${if (d.equal) "equal" else "different"}"
        ) ++ Vector(s"datasets=${datasets.size}", s"complete=$nonce")
    lines.mkString("", "\n", "\n")
  }

  // ---------------------------------------------------------------- the entry point

  /**
   * The comparison body: `session` is called only after the arguments and both root layouts are
   * accepted. Exit status [[ExitCompared]], [[ExitImpossible]] or [[ExitArguments]].
   */
  def run(args: Seq[String], session: () => SparkSession, out: PrintStream): Int =
    parse(args) match {
      case Left(problems) =>
        problems.foreach(w => out.println(s"$Prefix refused: $w"))
        out.println(s"$Prefix $usage")
        ExitArguments
      case Right(a) =>
        val layouts = for {
          l <- rootLayout("left", a.left)
          r <- rootLayout("right", a.right)
        } yield (l, r)
        val outcome: Either[Vector[String], String] = layouts.flatMap { case (l, r) =>
          try {
            val spark = session()
            val vl = verifySide(spark, "left", l)
            val vr = verifySide(spark, "right", r)
            (vl, vr) match {
              case (Right((pl, ll)), Right((pr, lr))) =>
                val datasets = compareDatasets(spark, pl, pr, out)
                Right(render(a.nonce, side(pl, ll), side(pr, lr), datasets))
              case _ =>
                Left(vl.left.toOption.toVector.flatten ++ vr.left.toOption.toVector.flatten)
            }
          } catch { case NonFatal(e) => Left(Vector(s"failure: $e")) }
        }
        outcome.flatMap { text =>
          try {
            Files.write(
              a.result,
              text.getBytes(StandardCharsets.UTF_8),
              StandardOpenOption.CREATE_NEW,
              StandardOpenOption.WRITE,
              LinkOption.NOFOLLOW_LINKS
            )
            Right(text)
          } catch {
            case _: FileAlreadyExistsException =>
              Left(Vector(s"${a.result} appeared before the result was written; nothing written"))
            case NonFatal(e) => Left(Vector(s"cannot write ${a.result}: $e"))
          }
        } match {
          case Left(problems) =>
            problems.foreach(w => out.println(s"$Prefix could not compare: $w"))
            out.println(s"$Prefix no result file was written")
            ExitImpossible
          case Right(text) =>
            val n = text.split("\n").count(_.startsWith("dataset\t"))
            val differ =
              text.split("\n").count(l => l.startsWith("dataset\t") && l.endsWith("\tdifferent"))
            out.println(s"$Prefix compared: $n datasets, $differ different; result ${a.result}")
            ExitCompared
        }
    }

  def main(args: Array[String]): Unit = {
    var tmp: Path = null
    var started: Option[SparkSession] = None
    val code =
      try
        run(
          args.toVector,
          () => {
            tmp = Files.createTempDirectory("scholarchain-compare-")
            val s = LocalSpark
              .start("scholarchain-published-run-comparison", tmp.resolve("spark"), Master)
            started = Some(s)
            s
          },
          System.out
        )
      finally {
        started.foreach(s => Try(s.stop()))
        Try(LocalSpark.deleteTree(tmp))
      }
    System.out.flush()
    sys.exit(code)
  }
}
