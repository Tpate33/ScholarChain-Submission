package scholarchain.spark.job

import scholarchain.core.PartManifest
import scholarchain.spark.graph.{Checkpointing, GraphStructureJob}
import scholarchain.spark.input.{ReaderLimits, RunTree}
import scholarchain.spark.store.StorePath

/**
 * The arguments of [[CollaborationJob]] (SP-P6-04), every one explicit and required once; nothing
 * has a built-in default (no bucket, endpoint, Region, threshold or window is hardcoded).
 *
 * @param inputRoot
 *   the verified archive root (read only)
 * @param curatedRun
 *   the curated run, relative to `inputRoot` (for the dev archive
 *   `curated/hadoop/snapshot=<token>/run=<64 hex>`)
 * @param decisionRun
 *   the decision run, relative to `inputRoot`
 * @param groupingRun
 *   the grouping run, relative to `inputRoot`
 * @param outputRoot
 *   where `run=<hex>/` and `load=<hex>/` are published (create-once); outside the input root
 * @param checkpointDir
 *   checkpoint root of the iterative algorithms; outside the input and the output root
 * @param configFile
 *   the analysis configuration file (HOCON, `ConfigLoader`), read with at most `maxMetadataBytes`
 * @param benchmarkPairsFile
 *   the benchmark pair list (canonical JSON plus LF), read with at most `maxMetadataBytes`
 * @param codeVersion
 *   40 lowercase hex: the commit the job jar was built from (recorded in the run manifest)
 */
final case class JobArguments(
    inputRoot: StorePath,
    curatedRun: String,
    decisionRun: String,
    groupingRun: String,
    outputRoot: StorePath,
    checkpointDir: String,
    configFile: StorePath,
    benchmarkPairsFile: StorePath,
    codeVersion: String,
    attempt: Int,
    partitions: Int,
    checkpointInterval: Int,
    limits: ReaderLimits
) {
  def checkpointing: Checkpointing = Checkpointing(checkpointDir, checkpointInterval)
}

object JobArguments {

  val InputRoot = "--input-root"
  val CuratedRun = "--curated-run"
  val DecisionRun = "--decision-run"
  val GroupingRun = "--grouping-run"
  val OutputRoot = "--output-root"
  val CheckpointDir = "--checkpoint-dir"
  val Config = "--config"
  val BenchmarkPairs = "--benchmark-pairs"
  val CodeVersion = "--code-version"
  val Attempt = "--attempt"
  val Partitions = "--partitions"
  val CheckpointInterval = "--checkpoint-interval"
  val MaxTreeEntries = "--max-tree-entries"
  val MaxTreeDepth = "--max-tree-depth"
  val MaxDriverValidationRows = "--max-driver-validation-rows"
  val MaxDriverValidationBytes = "--max-driver-validation-bytes"
  val MaxMetadataBytes = "--max-metadata-bytes"

  /** Every flag, each required exactly once, in usage order. */
  val Flags: Vector[String] = Vector(
    InputRoot,
    CuratedRun,
    DecisionRun,
    GroupingRun,
    OutputRoot,
    CheckpointDir,
    Config,
    BenchmarkPairs,
    CodeVersion,
    Attempt,
    Partitions,
    CheckpointInterval,
    MaxTreeEntries,
    MaxTreeDepth,
    MaxDriverValidationRows,
    MaxDriverValidationBytes,
    MaxMetadataBytes
  )

  /**
   * Location schemes the job accepts: the local file system (absolute paths or `file:` URIs), HDFS,
   * and S3 through S3A. `s3:` and `s3n:` are refused: EMRFS (`s3:` on EMR) is not part of the job's
   * runtime, and the job never guesses which client a scheme means.
   */
  val Schemes: Set[String] = Set(StorePath.FileScheme, "hdfs", "s3a")

  /**
   * The code-version placeholder of the local sparkIT runs (the all-zero version with
   * `codeVersionStatus = unpinned-sparkit-dev`); refused here, so a cloud run always names the
   * commit of its jar.
   */
  val UnpinnedMarker = "unpinned-sparkit-dev"
  val UnpinnedCodeVersion: String = "0" * 40

  private val Hex40 = "^[0-9a-f]{40}$".r

  /**
   * Refusals of local (`file:`) data locations under a cluster master (CR-P6-25 f2): with any
   * master other than `local`, `local[n]` or `local-cluster[...]` (one machine, one disk) the
   * executors do not share the driver's local file system, so the input root, the output root and
   * the checkpoint root must be shared locations (`hdfs:`, `s3a:`). The configuration and the pair
   * list are read by the driver only and may be local.
   */
  def clusterProblems(master: String, a: JobArguments): Vector[String] =
    if (master == null || master.startsWith("local")) Vector.empty
    else {
      // An absolute local path or a URI, both already accepted by parse.
      val checkpoint = StorePath.parse(a.checkpointDir).toOption
      Vector(
        Some(InputRoot -> a.inputRoot),
        Some(OutputRoot -> a.outputRoot),
        checkpoint.map(CheckpointDir -> _)
      ).flatten.collect {
        case (flag, p) if p.isLocal =>
          s"$flag $p is on the local file system, which the executors of master '$master' do not share: use hdfs:// or s3a://"
      }
    }

  def usage: String =
    "arguments (each required once, as '--flag value'): " + Flags.mkString(" ")

  private def location(
      schemes: Set[String],
      flag: String,
      v: String
  ): Either[String, StorePath] =
    StorePath.parse(v).left.map(w => s"$flag: $w").flatMap { p =>
      if (schemes.contains(p.scheme)) Right(p)
      else
        Left(
          s"$flag: scheme '${p.scheme}' is not accepted (${schemes.toVector.sorted.mkString(", ")}" +
            "; use s3a:// for S3)"
        )
    }

  private def relative(flag: String, v: String): Either[String, String] =
    PartManifest.pathProblem(v) match {
      case Some(why) => Left(s"$flag: '$v' is not a relative path below the input root ($why)")
      case None => Right(v)
    }

  private def positiveInt(flag: String, v: String): Either[String, Int] =
    v.toIntOption.filter(_ >= 1).toRight(s"$flag: '$v' is not an integer >= 1")

  private def positiveLong(flag: String, v: String, max: Long): Either[String, Long] =
    v.toLongOption
      .filter(n => n >= 1L && n <= max)
      .toRight(s"$flag: '$v' is not an integer in 1..$max")

  private def codeVersion(v: String): Either[String, String] =
    if (v == UnpinnedMarker || v == UnpinnedCodeVersion)
      Left(
        s"$CodeVersion: '$v' is the unpinned sparkIT placeholder; a job run names the 40-hex commit of its jar"
      )
    else if (!Hex40.matches(v)) Left(s"$CodeVersion: '$v' is not 40 lowercase hex")
    else Right(v)

  /** The flag-value pairs, or every problem with their shape. */
  private def pairs(args: Seq[String]): Either[Vector[String], Map[String, String]] =
    if (args == null) Left(Vector("no arguments"))
    else {
      val v = args.toVector
      val shape =
        if (v.size % 2 != 0) Vector(s"${v.size} arguments: expected '--flag value' pairs")
        else Vector.empty
      val grouped = v.grouped(2).toVector.filter(_.size == 2).map(p => (p(0), p(1)))
      val unknown = grouped.map(_._1).filterNot(Flags.contains).map(f => s"unknown flag '$f'")
      val valueLooksLikeFlag = grouped.collect {
        case (f, x) if x.startsWith("--") => s"$f has no value (next token '$x')"
      }
      val dup = grouped
        .groupBy(_._1)
        .collect { case (f, xs) if xs.size > 1 => s"$f is given ${xs.size} times" }
        .toVector
        .sorted
      val present = grouped.map(_._1).toSet
      val missing = Flags.filterNot(present).map(f => s"$f is required")
      val problems = shape ++ unknown ++ valueLooksLikeFlag ++ dup ++ missing
      if (problems.nonEmpty) Left(problems) else Right(grouped.toMap)
    }

  /** Parses and checks the arguments; Left with every problem found. */
  def parse(args: Seq[String]): Either[Vector[String], JobArguments] = parseWith(args, Schemes)

  /**
   * As [[parse]] with another set of accepted file schemes for the data locations (test seam only:
   * the S3-semantics test file system; the checkpoint root keeps [[Schemes]]).
   */
  private[job] def parseWith(
      args: Seq[String],
      schemes: Set[String]
  ): Either[Vector[String], JobArguments] =
    pairs(args).flatMap { m =>
      val inputRoot = location(schemes, InputRoot, m(InputRoot))
      val outputRoot = location(schemes, OutputRoot, m(OutputRoot))
      val config = location(schemes, Config, m(Config))
      val benchmark = location(schemes, BenchmarkPairs, m(BenchmarkPairs))
      val curated = relative(CuratedRun, m(CuratedRun))
      val decision = relative(DecisionRun, m(DecisionRun))
      val grouping = relative(GroupingRun, m(GroupingRun))
      val code = codeVersion(m(CodeVersion))
      val attempt = positiveInt(Attempt, m(Attempt))
      val partitions = positiveInt(Partitions, m(Partitions))
      val interval = positiveInt(CheckpointInterval, m(CheckpointInterval))
      val entries = positiveInt(MaxTreeEntries, m(MaxTreeEntries))
      val depth = positiveInt(MaxTreeDepth, m(MaxTreeDepth))
      val rows = positiveLong(MaxDriverValidationRows, m(MaxDriverValidationRows), Long.MaxValue)
      val bytes =
        positiveLong(MaxDriverValidationBytes, m(MaxDriverValidationBytes), Long.MaxValue)
      val meta = positiveLong(MaxMetadataBytes, m(MaxMetadataBytes), RunTree.MaxArrayBytes)
      val checkpoint = {
        val c = m(CheckpointDir)
        GraphStructureJob.checkpointProblem(Checkpointing(c, 1)) match {
          case Some(w) => Left(s"$CheckpointDir: $w")
          case None =>
            (if (c.startsWith("/")) StorePath.parse(c)
             else location(Schemes, CheckpointDir, c)) match {
              case Left(w) => Left(if (w.startsWith(CheckpointDir)) w else s"$CheckpointDir: $w")
              case Right(p) if !Schemes.contains(p.scheme) =>
                Left(s"$CheckpointDir: scheme '${p.scheme}' is not accepted")
              case Right(p) => Right((c, p))
            }
        }
      }
      val single = Vector(
        inputRoot,
        outputRoot,
        config,
        benchmark,
        curated,
        decision,
        grouping,
        code,
        attempt,
        partitions,
        interval,
        entries,
        depth,
        rows,
        bytes,
        meta,
        checkpoint
      ).collect { case Left(w) => w }
      if (single.nonEmpty) Left(single)
      else {
        val in = inputRoot.toOption.get
        val out = outputRoot.toOption.get
        val ck = checkpoint.toOption.get._2
        def overlap(a: StorePath, b: StorePath) = a.startsWith(b) || b.startsWith(a)
        val overlaps = Vector(
          Option.when(overlap(in, out))(
            s"$OutputRoot $out and $InputRoot $in overlap: the job never writes into the archive"
          ),
          Option.when(overlap(ck, in))(
            s"$CheckpointDir $ck and $InputRoot $in overlap: the job never writes into the archive"
          ),
          Option.when(overlap(ck, out))(s"$CheckpointDir $ck and $OutputRoot $out overlap")
        ).flatten
        val runs = Vector(curated, decision, grouping).map(_.toOption.get)
        val distinct =
          if (runs.distinct.size == 3) Vector.empty
          else Vector(s"$CuratedRun, $DecisionRun and $GroupingRun must be three different runs")
        if (overlaps.nonEmpty || distinct.nonEmpty) Left(overlaps ++ distinct)
        else
          Right(
            JobArguments(
              in,
              runs(0),
              runs(1),
              runs(2),
              out,
              checkpoint.toOption.get._1,
              config.toOption.get,
              benchmark.toOption.get,
              code.toOption.get,
              attempt.toOption.get,
              partitions.toOption.get,
              interval.toOption.get,
              ReaderLimits(
                entries.toOption.get,
                depth.toOption.get,
                rows.toOption.get,
                bytes.toOption.get,
                meta.toOption.get
              )
            )
          )
      }
    }
}
