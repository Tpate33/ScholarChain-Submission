package scholarchain.spark.job

import org.apache.hadoop.conf.Configuration
import org.apache.spark.SparkConf

import scholarchain.spark.store.StorePath

/**
 * Start-up refusals of [[CollaborationJob]] that depend on the effective runtime configuration, not
 * on the arguments alone (CR-P6-26 u1 and the single-writer risk). Pure: each check reads only the
 * configuration objects it is given and never touches a file system, so it runs after the Spark
 * session exists and before any read or write of job data. Refusals exit with
 * [[CollaborationJob.ExitRefused]] (1), not [[CollaborationJob.ExitArguments]] (2): exit 2 means
 * "decided by the arguments alone, before any Spark session", and these need the session's
 * configuration (`spark-submit --conf`, cluster defaults).
 *
 *   - S3A directory markers: for every `s3a:` location of the arguments (input root, output root,
 *     checkpoint root, configuration file, pair list), the effective
 *     `fs.s3a.directory.marker.retention` of its bucket must be exactly `keep` (trimmed; S3A's
 *     per-bucket key `fs.s3a.bucket.<bucket>.directory.marker.retention` overrides the base key, as
 *     S3A applies it). Unset is refused: the job does not rely on a Hadoop version's built-in
 *     default (it changed between Hadoop lines). Reason: with marker deletion S3A deletes parent
 *     directory marker objects after each write, including under `run=<hex>` and `load=<hex>`,
 *     where the publication protocol never deletes anything (and where the planned IAM policy
 *     denies deletes).
 *   - YARN attempts: under a YARN master (`yarn`, any deploy mode) `spark.yarn.maxAppAttempts` must
 *     be exactly `1` (trimmed). Unset is refused (YARN's own maximum, normally 2, would apply). The
 *     publication's create-once is check-then-create on S3A, so a second application attempt
 *     running beside or after a failed first one would break the single-writer assumption of
 *     [[scholarchain.spark.output.RunPublisher]] protocol B. Other masters are not checked.
 */
object StartupChecks {

  val S3aScheme = "s3a"
  val MarkerRetentionKey = "fs.s3a.directory.marker.retention"
  val MarkerRetentionRequired = "keep"
  val MaxAppAttemptsKey = "spark.yarn.maxAppAttempts"
  val MaxAppAttemptsRequired = "1"

  /** S3A's per-bucket form of [[MarkerRetentionKey]]. */
  def bucketMarkerRetentionKey(bucket: String): String =
    s"fs.s3a.bucket.$bucket.directory.marker.retention"

  /** Every location of the arguments, with its flag (the checkpoint root parsed as accepted). */
  def locations(a: JobArguments): Vector[(String, StorePath)] =
    Vector(
      JobArguments.InputRoot -> a.inputRoot,
      JobArguments.OutputRoot -> a.outputRoot,
      JobArguments.Config -> a.configFile,
      JobArguments.BenchmarkPairs -> a.benchmarkPairsFile
    ) ++ StorePath.parse(a.checkpointDir).toOption.map(JobArguments.CheckpointDir -> _).toVector

  /** The refusals of the S3A directory-marker policy (empty when no `s3a:` location is used). */
  def markerRetentionProblems(conf: Configuration, a: JobArguments): Vector[String] =
    locations(a)
      .collect { case (flag, p) if p.scheme == S3aScheme => (flag, p) }
      .flatMap { case (flag, p) =>
        val bucket = p.authority.map(StorePath.hostOf).getOrElse("")
        val perBucket = bucketMarkerRetentionKey(bucket)
        val (key, value) = Option(conf.getTrimmed(perBucket)) match {
          case Some(v) => (perBucket, Some(v))
          case None => (MarkerRetentionKey, Option(conf.getTrimmed(MarkerRetentionKey)))
        }
        Option.when(!value.contains(MarkerRetentionRequired))(
          s"$flag $p: the effective $key is ${value.fold("unset")(v => s"'$v'")}; " +
            s"the job requires '$MarkerRetentionRequired' (for example spark-submit --conf " +
            s"spark.hadoop.$MarkerRetentionKey=$MarkerRetentionRequired): with marker deletion S3A " +
            "deletes directory markers under run= and load=, where the publication never deletes"
        )
      }

  /** The refusal of more than one YARN application attempt (empty for any other master). */
  def attemptProblems(master: String, conf: SparkConf): Vector[String] =
    if (master == null || !master.startsWith("yarn")) Vector.empty
    else {
      val value = conf.getOption(MaxAppAttemptsKey).map(_.trim)
      Option
        .when(!value.contains(MaxAppAttemptsRequired))(
          s"master '$master': $MaxAppAttemptsKey is ${value.fold("unset")(v => s"'$v'")}; the job " +
            s"requires '$MaxAppAttemptsRequired' (spark-submit --conf $MaxAppAttemptsKey=" +
            s"$MaxAppAttemptsRequired): publication is single-writer (check-then-create on S3A), so " +
            "a second application attempt must never run"
        )
        .toVector
    }

  /** Every start-up refusal, in a fixed order (attempts, then markers). */
  def problems(
      master: String,
      sparkConf: SparkConf,
      hadoopConf: Configuration,
      a: JobArguments
  ): Vector[String] =
    attemptProblems(master, sparkConf) ++ markerRetentionProblems(hadoopConf, a)
}
