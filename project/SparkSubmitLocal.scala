import java.io.{BufferedReader, ByteArrayInputStream, ByteArrayOutputStream, File, IOException}
import java.io.{InputStream, InputStreamReader}
import java.nio.charset.StandardCharsets
import java.nio.file.{FileAlreadyExistsException, Files, LinkOption, Path, StandardOpenOption}
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.util.{Failure, Success, Try}

import sbt._
import sbt.internal.util.MessageOnlyException
import sbt.librarymanagement.{DependencyResolution, UnresolvedWarningConfiguration}
import sbt.librarymanagement.UpdateConfiguration

/**
 * EMR Option A packaging and the local Spark runtime compatibility gate (work order BE-P6-09).
 * Owner: build-engineer.
 *
 * Packaging (`sbt packageJobs`, then [[AssemblyCheckTask]]): the sparkAnalytics assembly has the
 * main class [[JobMainClass]], no Spark, Hadoop, AWS SDK or logging-implementation class, and no
 * scala-library 2.13 class; its `scala/` classes are exactly those of scala3-library_3. Scala 3.3.6
 * needs scala-library 2.13.16 (its POM), while the pinned Spark distribution ships an older 2.13
 * library (ledger `spark.distribution.scala-library`, checked from the extracted `jars/` names).
 * Decision (one approach): the job jar carries no scala-library; the newer library (ledger
 * `spark.runtime.scala-library`) is put FIRST on the driver and executor system classpath
 * (`--driver-class-path`, `spark.executor.extraClassPath`), so Spark and the job share one
 * scala-library on one class loader. Not chosen: `userClassPathFirst`, because a child-first loader
 * holding a second copy of `scala.*` breaks every object passed between Spark and job code (for
 * example `scala.Function1` closures). The distribution itself is never modified. 2.13 is backward
 * binary compatible (code built for 2.13.8 runs on 2.13.16), not forward, so the newer library on
 * top is the direction that can work; [[Task]] is the first place that exercises it.
 *
 * `sbt sparkSubmitLocal` (not part of `check`; writes no test marker): one fixed-shape root task.
 *   1. Before compiling: the sbt JVM's (or javaHome's) `release` file says Java 17; the ledger pins
 *      are consistent ([[pinsFrom]]); the code scope [[CodeScope]] is committed and byte-identical
 *      to HEAD ([[LocalRuns.scopeEqualsHead]], as erDecisionGroupingDev); the sparkIT archive
 *      preflight ([[SparkItRuns.preflight]]: SCHOLARCHAIN_CFG_* refusal,
 *      project/spark-it.properties, the verified dev archive, no held-out, gold/ or target/streams
 *      path); the committed configuration and pair list exist.
 *   2. sbt builds the assembly and the sparkAnalytics Test classpath.
 *   3. The checks of step 1 again (HEAD must not move); the comparison entry point
 *      [[CompareMainClass]] (spark-engineer, Test tree) must exist; the assembly content check; the
 *      runtime jars resolved through the build's resolver (intransitive; hadoop-aws sha1 checked).
 *   4. The distribution: cached under [[CacheRel]] (root target, removed by `sbt clean`; nothing is
 *      written outside the repository). A cached file whose sha512 matches is used and never
 *      re-downloaded; a mismatch refuses and keeps the file. Otherwise the pinned HTTPS URL is
 *      fetched once (no redirect, bounded bytes and time) into a fresh partial file, which is
 *      hard-linked to the final name only when its sha512 matches; on any failure only that partial
 *      file is deleted. When this invocation downloaded, it stops there (fails, NOT a pass) so the
 *      runs start in the next invocation within the tool-call bound.
 *   5. A fresh create-once `target/spark-submit-local/attempt-<n>/`: the distribution extracted by
 *      the build's own tar reader (no link, no absolute or `..` name, one top directory, bounded),
 *      its `jars/` checked against the ledger, the three runtime jars and the job jar copied.
 *   6. The distribution's own `bin/spark-submit` (client mode), at `local[2]` and then
 *      `local-cluster[2,1,1024]`, over the dev archive with the job's 17 flags, the real HEAD as
 *      `--code-version`, the event log into the run directory, the two required confs, the Java 17
 *      opens, loopback only, its own empty SPARK_CONF_DIR; inherited [[StrippedPrefixes]] and
 *      [[StrippedNames]] removed and the AWS isolation variables added; bounded.
 *   7. PASS only when both runs exit 0, each publishes exactly one `run=<hex>` with `_SUCCESS` and
 *      one event log, both run names and `_run-manifest.json` bytes are equal, and the comparison
 *      entry point's nonce-bound result file shows all [[ExpectedDatasets]] datasets logically
 *      equal and the sparkIT baseline counts ([[verdict]]). Writes `result.txt`.
 */
object SparkSubmitLocal {

  val Task = "sparkSubmitLocal"
  val SelfTest = "verifySparkSubmitLocalGuards"
  val AssemblyCheckTask = "sparkJobAssemblyCheck"

  val JobMainClass = "scholarchain.spark.job.CollaborationJob"

  /** Required from spark-engineer (sparkAnalytics Test tree); see [[parseComparison]]. */
  val CompareMainClass = "scholarchain.spark.testkit.PublishedRunComparisonMain"
  val ComparisonFormat = "scholarchain.spark.published-run-comparison.v1"

  val CacheRel = "target/spark-dist"
  val AttemptsRel = "target/spark-submit-local"

  /** The code the runs execute: the build definition, core and the Spark module. */
  val CodeScope: Seq[String] = Seq("build.sbt", "project", "modules/core", "modules/spark")
  val GeneratedDirs: Set[String] = Set(
    "project/target/",
    "project/project/target/",
    "modules/core/target/",
    "modules/spark/target/"
  )

  /** The committed dev configuration and pair list (the ones sparkIT uses, LocalSpark). */
  val DevResources = "modules/spark/src/test/resources/scholarchain/spark/dev-run-01"
  val ConfigRel = s"$DevResources/analysis-dev.conf"
  val PairsRel = s"$DevResources/benchmark-pairs-dev.v1.json"

  // Build-envelope bounds, not data policy. sparkIT run 08 took 32 s in total for two in-run
  // builds at local[1]; each spark-submit adds JVM and launcher start-up (local-cluster also starts
  // a master, two workers and two executor JVMs). 150 + 150 + 120 s plus sbt start-up and the
  // extraction stays inside the 600 s maximum of one agent tool call.
  val RunTimeoutSeconds: Long = 150L
  val CompareTimeoutSeconds: Long = 120L
  val DownloadTimeoutSeconds: Long = 420L
  val StopGraceSeconds: Long = 10L
  val DrainMillis: Long = 5000L

  /** The verified listing gives 391M; the bound leaves room without accepting an unbounded body. */
  val MaxDistributionBytes: Long = 512L * 1024L * 1024L
  val Tar: TarLimits = TarLimits(
    maxEntries = 20000,
    maxTotalBytes = 2L * 1024L * 1024L * 1024L,
    maxFileBytes = 1024L * 1024L * 1024L
  )

  val Masters: Seq[String] = Seq("local[2]", "local-cluster[2,1,1024]")
  val Partitions = 2
  val CheckpointInterval = 2
  val Attempt = 1

  /** ReaderLimits of the sparkIT suite (DevArchiveGraphFoundationChecks), as job flags. */
  val Limits: Seq[(String, String)] = Seq(
    "--max-tree-entries" -> "20000",
    "--max-tree-depth" -> "4",
    "--max-driver-validation-rows" -> "2000",
    "--max-driver-validation-bytes" -> (64L * 1024L * 1024L).toString,
    "--max-metadata-bytes" -> (1024L * 1024L).toString
  )

  /** sparkIT run 08 baseline (docs/agent-runs/phase-06/sparkit-run-08.md). */
  val ExpectedDatasets = 28
  val ExpectedEdges = 259L
  val ExpectedMetrics = 176L
  val ExpectedLoads: Map[String, (Long, Long)] = Map(
    "neptune" -> (12L, 1258L),
    "dynamodb" -> (12L, 1302L),
    "s3" -> (16L, 2809L),
    "s3-vectors" -> (2L, 249L)
  )

  /** Inherited variables removed from every child (by name prefix and by exact name). */
  val StrippedPrefixes: Seq[String] =
    Seq("AWS_", "SCHOLARCHAIN_", "GIT_", "MUNIT_", "SPARK_", "HADOOP_", "YARN_", "PYSPARK_")
  val StrippedNames: Set[String] = Set(
    "JAVA_TOOL_OPTIONS",
    "_JAVA_OPTIONS",
    "JDK_JAVA_OPTIONS",
    "JAVA_OPTS",
    "SBT_OPTS",
    "CLASSPATH"
  )
  def strippedFromChild(name: String): Boolean =
    StrippedPrefixes.exists(name.startsWith) || StrippedNames.contains(name)

  /** Packages that EMR and the distribution supply; never inside the job jar. */
  val ForbiddenPrefixes: Seq[String] = Seq(
    "org/apache/spark/",
    "org/apache/hadoop/",
    "org/apache/parquet/",
    "com/amazonaws/",
    "software/amazon/awssdk/",
    "org/apache/logging/log4j/",
    "org/apache/log4j/",
    "org/slf4j/"
  )

  private val Hex40 = "^[0-9a-f]{40}$".r
  private val Hex64 = "^[0-9a-f]{64}$".r
  private val Hex128 = "^[0-9a-f]{128}$".r
  private val RunDir = "^run=[0-9a-f]{64}$".r
  private val AttemptName = "^attempt-([1-9][0-9]*)$".r
  private val SafePath = "^/[A-Za-z0-9._/+=@:-]+$".r

  private def refuse(message: String): Nothing =
    throw new MessageOnlyException(s"$Task: refused: $message")
  private def fail(message: String): Nothing = throw new MessageOnlyException(s"$Task: $message")
  private def orRefuse[A](e: Either[String, A]): A = e.fold(refuse, identity)

  // --- Ledger pins ----------------------------------------------------------------------

  final case class Pins(
      spark: String,
      url: String,
      file: String,
      sha512: String,
      distScalaLibrary: String,
      distHadoopClient: String,
      runtimeScalaLibrary: String,
      hadoopAws: String,
      hadoopAwsSha1: String,
      sdkBundle: String
  )

  val UrlPrefix = "https://archive.apache.org/dist/spark/"

  /** The pins, or every inconsistency between them. */
  def pinsFrom(get: String => Option[String]): Either[String, Pins] = {
    val keys = Seq(
      "spark",
      "spark.distribution.url",
      "spark.distribution.file",
      "spark.distribution.sha512",
      "spark.distribution.scala-library",
      "spark.distribution.hadoop-client",
      "spark.runtime.scala-library",
      "hadoop-aws.s3a",
      "hadoop-aws.s3a.jar-sha1",
      "aws-sdk-bundle.s3a"
    )
    val missing = keys.filter(k => get(k).isEmpty)
    if (missing.nonEmpty) Left(s"ledger keys missing: ${missing.mkString(", ")}")
    else {
      val v = keys.map(k => get(k).get)
      val p = Pins(v(0), v(1), v(2), v(3), v(4), v(5), v(6), v(7), v(8), v(9))
      val bad = ListBuffer.empty[String]
      if (!p.url.startsWith(UrlPrefix)) bad += s"spark.distribution.url is not under $UrlPrefix"
      if (p.url.split('/').last != p.file)
        bad += "spark.distribution.file is not the last segment of spark.distribution.url"
      if (p.file != s"spark-${p.spark}-bin-hadoop3-scala2.13.tgz")
        bad += s"spark.distribution.file is not spark-${p.spark}-bin-hadoop3-scala2.13.tgz (ledger spark)"
      if (!p.url.contains(s"/spark-${p.spark}/"))
        bad += s"spark.distribution.url is not in the spark-${p.spark}/ directory"
      if (!Hex128.pattern.matcher(p.sha512).matches)
        bad += "spark.distribution.sha512 is not 128 lower-case hex"
      if (!Hex40.pattern.matcher(p.hadoopAwsSha1).matches)
        bad += "hadoop-aws.s3a.jar-sha1 is not 40 lower-case hex"
      if (p.hadoopAws != p.distHadoopClient)
        bad += "hadoop-aws.s3a must equal spark.distribution.hadoop-client (S3A matches the client)"
      if (!p.runtimeScalaLibrary.startsWith("2.13.") || !p.distScalaLibrary.startsWith("2.13."))
        bad += "the scala-library pins must be 2.13.x"
      if (bad.nonEmpty) Left(bad.mkString("; ")) else Right(p)
    }
  }

  def topDir(p: Pins): String = p.file.stripSuffix(".tgz")

  // --- Small helpers ----------------------------------------------------------------------

  def hex(b: Array[Byte]): String = b.map(x => f"${x & 0xff}%02x").mkString

  def digestFile(f: Path, algorithm: String): String = {
    val md = MessageDigest.getInstance(algorithm)
    val in = Files.newInputStream(f, LinkOption.NOFOLLOW_LINKS)
    try {
      val buf = new Array[Byte](1 << 16)
      var r = in.read(buf)
      while (r != -1) { md.update(buf, 0, r); r = in.read(buf) }
    } finally in.close()
    hex(md.digest())
  }

  def newNonce(): String = SparkItRuns.newNonce()

  /** Creates `<repo>/<rel>` after refusing a symbolic link or non-directory on any component. */
  def ensureDir(repo: Path, rel: String): Either[String, Path] = {
    val segs = rel.split('/').toSeq
    val bad = (1 to segs.length).map(n => segs.take(n).mkString("/")).find { r =>
      val q = repo.resolve(r)
      Files.isSymbolicLink(q) ||
      (Files
        .exists(q, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(q, LinkOption.NOFOLLOW_LINKS))
    }
    bad match {
      case Some(r) => Left(s"$r is a symbolic link or not a directory; $Task writes nothing")
      case None =>
        val d = repo.resolve(rel)
        Files.createDirectories(d)
        if (d.toRealPath() != d) Left(s"$rel resolves through a symbolic link") else Right(d)
    }
  }

  /** The next create-once `attempt-<n>`; refuses symbolic links in the attempts root. */
  def nextAttempt(root: Path): Either[String, Path] = {
    val names = {
      val s = Files.list(root)
      try s.iterator.asScala.map(_.getFileName.toString).toList
      finally s.close()
    }
    val links = names.filter(n => Files.isSymbolicLink(root.resolve(n)))
    if (links.nonEmpty) Left(s"$AttemptsRel/ holds symbolic link(s) ${links.sorted.mkString(", ")}")
    else {
      val used = names.flatMap {
        case AttemptName(k) => Try(k.toInt).toOption.toList
        case _ => Nil
      }
      val n = if (used.isEmpty) 1 else used.max + 1
      try Right(Files.createDirectory(root.resolve(s"attempt-$n")))
      catch {
        case _: FileAlreadyExistsException =>
          Left(s"$AttemptsRel/attempt-$n already exists; attempts are create-once")
      }
    }
  }

  /** The Java feature version from `<home>/release` (JAVA_VERSION="17...."). */
  def javaFeatureOf(releaseText: String): Option[Int] =
    releaseText
      .split("\n")
      .map(_.trim)
      .collectFirst { case l if l.startsWith("JAVA_VERSION=") => l.drop(13).stripPrefix("\"") }
      .flatMap(v => Try(v.takeWhile(_.isDigit).toInt).toOption)

  def javaHomeProblem(home: File): Option[String] = {
    val rel = new File(home, "release")
    if (!rel.isFile) Some(s"$rel is missing; the Java version cannot be confirmed")
    else
      javaFeatureOf(IO.read(rel, StandardCharsets.UTF_8)) match {
        case Some(17) => None
        case other =>
          Some(
            s"the JDK at $home is Java ${other.fold("unknown")(_.toString)}, not 17 (EMR 7.14 " +
              "runs Corretto 17; the compatibility gate uses the same feature version)"
          )
      }
  }

  // --- Tar reader (no links, one top directory, bounded) ------------------------------------

  final case class TarLimits(maxEntries: Int, maxTotalBytes: Long, maxFileBytes: Long)
  final case class Extracted(files: Int, dirs: Int, bytes: Long, executables: Int)

  private final class TarError(m: String) extends Exception(m)
  private def tarFail(m: String): Nothing = throw new TarError(m)

  private def readFully(in: InputStream, buf: Array[Byte], n: Int): Int = {
    var off = 0
    var r = 0
    while (off < n && r != -1) {
      r = in.read(buf, off, n - off)
      if (r > 0) off += r
    }
    off
  }

  private def field(h: Array[Byte], off: Int, len: Int): String = {
    val end = (off until off + len).find(i => h(i) == 0).getOrElse(off + len)
    new String(h, off, end - off, StandardCharsets.UTF_8)
  }

  private def octal(h: Array[Byte], off: Int, len: Int, what: String): Long = {
    if ((h(off) & 0x80) != 0) tarFail(s"$what uses a base-256 numeric field (not supported)")
    val s = field(h, off, len).trim
    if (s.isEmpty) 0L
    else if (!s.forall(c => c >= '0' && c <= '7')) tarFail(s"$what is not an octal number")
    else java.lang.Long.parseLong(s, 8)
  }

  /** Why an entry name may not be extracted, or its segments (top directory first). */
  def entryPath(raw: String, top: String): Either[String, Seq[String]] = {
    val s = (if (raw.startsWith("./")) raw.drop(2) else raw).stripSuffix("/")
    if (s.isEmpty) Left("an entry has an empty name")
    else if (s.length > 4096) Left("an entry name is longer than 4096 characters")
    else if (s.startsWith("/")) Left(s"absolute entry name '${raw.take(200)}'")
    else if (s.exists(c => c < 0x20 || c == 0x7f || c == '\\'))
      Left(s"entry name with a control character or backslash: '${raw.take(200)}'")
    else {
      val segs = s.split("/", -1).toSeq
      if (segs.exists(x => x.isEmpty || x == "." || x == ".."))
        Left(s"entry name '${raw.take(200)}' has an empty, . or .. segment")
      else if (segs.head != top)
        Left(s"entry '${raw.take(200)}' is outside the expected top directory $top/")
      else Right(segs)
    }
  }

  private def paxRecords(data: Array[Byte]): Map[String, String] = {
    val out = scala.collection.mutable.Map.empty[String, String]
    var i = 0
    while (i < data.length) {
      val sp = data.indexWhere(_ == ' '.toByte, i)
      if (sp < 0) tarFail("malformed pax header")
      val len = Try(new String(data, i, sp - i, StandardCharsets.US_ASCII).toInt).getOrElse(-1)
      if (len <= 0 || i + len > data.length) tarFail("malformed pax record length")
      val rec = new String(data, sp + 1, i + len - sp - 2, StandardCharsets.UTF_8)
      val eq = rec.indexOf('=')
      if (eq <= 0) tarFail("malformed pax record")
      out(rec.take(eq)) = rec.drop(eq + 1)
      i += len
    }
    out.toMap
  }

  private val Exec = PosixFilePermissions.fromString("rwxr-xr-x")
  private val Plain = PosixFilePermissions.fromString("rw-r--r--")

  /**
   * Extracts a ustar/GNU/pax tar stream into the existing empty directory `dest`. Regular files and
   * directories only (any link or special entry refuses), every name under `top`, no absolute, `.`,
   * `..`, empty, control-character or backslash segment, no duplicate file, checksums verified, two
   * zero end blocks required, bounded entries and bytes. Modes: executable when the owner execute
   * bit is set, else rw-r--r--. On Left the partly extracted tree stays (it is inside the
   * create-once attempt and never used).
   */
  def untar(
      in: InputStream,
      dest: Path,
      top: String,
      limits: TarLimits
  ): Either[String, Extracted] =
    try {
      val h = new Array[Byte](512)
      val scratch = new Array[Byte](1 << 16)
      var files = 0
      var dirs = 0
      var execs = 0
      var entries = 0
      var total = 0L
      var longName: Option[String] = None
      var paxPath: Option[String] = None
      var paxSize: Option[Long] = None
      var done = false
      def skip(n: Long): Unit = {
        var left = n
        while (left > 0) {
          val k = readFully(in, scratch, math.min(left, scratch.length.toLong).toInt)
          if (k <= 0) tarFail("truncated entry data")
          left -= k
        }
      }
      def pad(size: Long): Long = (512 - size % 512) % 512
      def small(size: Long): Array[Byte] = {
        if (size > 65536) tarFail("a tar metadata entry is larger than 64 KiB")
        val b = new Array[Byte](size.toInt)
        if (readFully(in, b, size.toInt) != size) tarFail("truncated tar metadata entry")
        skip(pad(size))
        b
      }
      while (!done) {
        val n = readFully(in, h, 512)
        if (n == 0)
          tarFail("the archive ends without its two zero end-of-archive blocks (truncated)")
        if (n < 512) tarFail("truncated tar header block")
        if (h.forall(_ == 0)) {
          if (readFully(in, h, 512) != 512 || !h.forall(_ == 0))
            tarFail("a zero block is not followed by a second zero block")
          done = true
        } else {
          val stored = octal(h, 148, 8, "the header checksum")
          var sum = 0L
          var i = 0
          while (i < 512) {
            sum += (if (i >= 148 && i < 156) 32 else h(i) & 0xff)
            i += 1
          }
          if (sum != stored) tarFail("a tar header checksum does not match (corrupt archive)")
          val kind = h(156).toChar
          val size = paxSize.getOrElse(octal(h, 124, 12, "an entry size"))
          kind match {
            case 'L' =>
              longName = Some(new String(small(size), StandardCharsets.UTF_8).takeWhile(_ != 0))
            case 'x' =>
              val r = paxRecords(small(size))
              r.get("path").foreach(p => paxPath = Some(p))
              r.get("size").foreach { s =>
                paxSize = Some(Try(s.toLong).getOrElse(tarFail("malformed pax size")))
              }
            case 'g' | 'K' => skip(size + pad(size))
            case '0' | '\u0000' | '7' | '5' =>
              val raw = paxPath.orElse(longName).getOrElse {
                val name = field(h, 0, 100)
                val prefix = if (field(h, 257, 6).startsWith("ustar")) field(h, 345, 155) else ""
                if (prefix.isEmpty) name else s"$prefix/$name"
              }
              longName = None
              paxPath = None
              paxSize = None
              val segs = entryPath(raw, top).fold(tarFail, identity)
              entries += 1
              if (entries > limits.maxEntries)
                tarFail(s"more than ${limits.maxEntries} entries (bound)")
              val target = segs.foldLeft(dest)(_ resolve _)
              if (kind == '5') {
                if (size != 0) tarFail(s"directory entry ${segs.mkString("/")} has data")
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                  if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS))
                    tarFail(s"${segs.mkString("/")} is both a file and a directory")
                } else {
                  Files.createDirectories(target)
                  dirs += 1
                }
                Files.setPosixFilePermissions(target, Exec)
              } else {
                if (size > limits.maxFileBytes)
                  tarFail(s"${segs.mkString("/")} is larger than ${limits.maxFileBytes} bytes")
                total += size
                if (total > limits.maxTotalBytes)
                  tarFail(s"more than ${limits.maxTotalBytes} bytes in total (bound)")
                Files.createDirectories(target.getParent)
                val out =
                  try
                    Files.newOutputStream(
                      target,
                      StandardOpenOption.CREATE_NEW,
                      StandardOpenOption.WRITE
                    )
                  catch {
                    case _: FileAlreadyExistsException =>
                      tarFail(s"${segs.mkString("/")} appears twice")
                  }
                try {
                  var left = size
                  while (left > 0) {
                    val k = readFully(in, scratch, math.min(left, scratch.length.toLong).toInt)
                    if (k <= 0) tarFail(s"truncated data of ${segs.mkString("/")}")
                    out.write(scratch, 0, k)
                    left -= k
                  }
                } finally out.close()
                skip(pad(size))
                val exec = (octal(h, 100, 8, "an entry mode") & 64L) != 0
                Files.setPosixFilePermissions(target, if (exec) Exec else Plain)
                if (exec) execs += 1
                files += 1
              }
            case '1' | '2' =>
              tarFail(
                s"link entry '${paxPath.orElse(longName).getOrElse(field(h, 0, 100)).take(200)}' " +
                  "(links are refused)"
              )
            case other =>
              tarFail(s"entry type '${other.toInt}' is neither a regular file nor a directory")
          }
        }
      }
      Right(Extracted(files, dirs, total, execs))
    } catch {
      case e: TarError => Left(e.getMessage)
      case e: IOException => Left(s"I/O error while extracting (${e.getClass.getSimpleName})")
    }

  def extractTarGz(
      archive: Path,
      dest: Path,
      top: String,
      limits: TarLimits
  ): Either[String, Extracted] =
    Try(
      new java.io.BufferedInputStream(
        new java.util.zip.GZIPInputStream(
          Files.newInputStream(archive, LinkOption.NOFOLLOW_LINKS),
          1 << 16
        ),
        1 << 16
      )
    ) match {
      case Failure(e) =>
        Left(s"cannot open ${archive.getFileName} as gzip (${e.getClass.getSimpleName})")
      case Success(in) =>
        try untar(in, dest, top, limits)
        finally in.close()
    }

  // --- Distribution cache and download ------------------------------------------------------

  /** Writes the body into the given (absent) partial file; Left on any failure. */
  type Fetch = Path => Either[String, Unit]

  /** One HTTPS GET: no redirect, status 200, bounded bytes and wall-clock time, create-new file. */
  def httpsFetch(url: String, boundSeconds: Long, maxBytes: Long): Fetch = partial =>
    Try {
      val client = java.net.http.HttpClient
        .newBuilder()
        .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
        .connectTimeout(java.time.Duration.ofSeconds(30))
        .build()
      val req = java.net.http.HttpRequest
        .newBuilder(java.net.URI.create(url))
        .timeout(java.time.Duration.ofSeconds(60))
        .GET()
        .build()
      val resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofInputStream())
      val body = resp.body()
      try {
        val length = resp.headers().firstValueAsLong("content-length")
        if (resp.statusCode() != 200)
          Left(s"HTTP status ${resp.statusCode()} (expected 200; no redirect followed)")
        else if (length.isPresent && length.getAsLong > maxBytes)
          Left(s"content-length ${length.getAsLong} exceeds the $maxBytes-byte bound")
        else {
          val timedOut = new AtomicBoolean(false)
          val timer = new java.util.Timer(s"$Task-download-bound", true)
          timer.schedule(
            new java.util.TimerTask {
              def run(): Unit = { timedOut.set(true); Try(body.close()); () }
            },
            boundSeconds * 1000L
          )
          val out =
            Files.newOutputStream(partial, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
          try {
            val buf = new Array[Byte](1 << 16)
            var n = 0L
            var r = body.read(buf)
            while (r != -1 && n <= maxBytes) {
              out.write(buf, 0, r)
              n += r
              r = body.read(buf)
            }
            if (n > maxBytes) Left(s"the body exceeds the $maxBytes-byte bound")
            else Right(())
          } finally { out.close(); timer.cancel() }
        }
      } finally Try(body.close())
    } match {
      case Success(r) => r
      case Failure(e) => Left(s"download failed (${e.getClass.getSimpleName}: ${e.getMessage})")
    }

  final case class Dist(file: Path, downloadedNow: Boolean, note: String)

  /**
   * The cached distribution (`<cache>/<file>`): used when its sha512 matches (never re-fetched);
   * refused when it differs (kept for the human). Otherwise `fetch` writes a fresh partial file,
   * hard-linked to the final name only on a sha512 match; on failure or mismatch only that partial
   * file is deleted. Other partial files are reported and left alone. If the final name appears
   * while this invocation downloads (CR-P6-27 n3): a regular file with the ledger's sha512 is used
   * and only the own partial file is deleted; anything else refuses and keeps both. The final name
   * is never deleted or overwritten.
   */
  def ensureDistribution(
      cache: Path,
      pins: Pins,
      fetch: Fetch,
      nonce: String
  ): Either[String, Dist] = {
    val dest = cache.resolve(pins.file)
    val others = {
      val s = Files.list(cache)
      try
        s.iterator.asScala
          .map(_.getFileName.toString)
          .filter(_.startsWith(pins.file + ".partial-"))
          .toList
          .sorted
      finally s.close()
    }
    val othersNote =
      if (others.isEmpty) ""
      else s"; earlier partial file(s) left alone (delete them by hand): ${others.mkString(", ")}"
    if (Files.isSymbolicLink(dest)) Left(s"$CacheRel/${pins.file} is a symbolic link")
    else if (Files.exists(dest, LinkOption.NOFOLLOW_LINKS)) {
      if (!Files.isRegularFile(dest, LinkOption.NOFOLLOW_LINKS))
        Left(s"$CacheRel/${pins.file} is not a regular file")
      else {
        val got = digestFile(dest, "SHA-512")
        if (got == pins.sha512)
          Right(
            Dist(
              dest,
              downloadedNow = false,
              s"cached $CacheRel/${pins.file}: sha512 matches the ledger; not downloaded$othersNote"
            )
          )
        else
          Left(
            s"cached $CacheRel/${pins.file} has sha512 $got, not the ledger's ${pins.sha512}; it is " +
              "kept (never overwritten or deleted by the build); the human inspects and removes it"
          )
      }
    } else {
      val partial = cache.resolve(s"${pins.file}.partial-${nonce.take(16)}")
      def drop(): Unit = { Files.deleteIfExists(partial); () }
      fetch(partial) match {
        case Left(m) =>
          drop()
          Left(s"$m; the partial download ${partial.getFileName} was deleted$othersNote")
        case Right(()) =>
          if (!Files.isRegularFile(partial, LinkOption.NOFOLLOW_LINKS)) {
            drop()
            Left("the download wrote no regular file")
          } else {
            val got = digestFile(partial, "SHA-512")
            if (got != pins.sha512) {
              drop()
              Left(
                s"the downloaded file has sha512 $got, not the ledger's ${pins.sha512}; the partial " +
                  s"download was deleted$othersNote"
              )
            } else
              try {
                Files.createLink(dest, partial)
                Files.delete(partial)
                Right(
                  Dist(
                    dest,
                    downloadedNow = true,
                    s"downloaded ${pins.url}: sha512 matches the ledger$othersNote"
                  )
                )
              } catch {
                // CR-P6-27 n3: another process created `dest` meanwhile. `dest` is never deleted.
                case _: FileAlreadyExistsException =>
                  val kept =
                    s"; the verified partial download ${partial.getFileName} is kept$othersNote"
                  if (Files.isSymbolicLink(dest))
                    Left(
                      s"$CacheRel/${pins.file} appeared during the download as a symbolic link$kept"
                    )
                  else if (!Files.isRegularFile(dest, LinkOption.NOFOLLOW_LINKS))
                    Left(
                      s"$CacheRel/${pins.file} appeared during the download, not a regular file$kept"
                    )
                  else {
                    val other = digestFile(dest, "SHA-512")
                    if (other != pins.sha512)
                      Left(
                        s"$CacheRel/${pins.file} appeared during the download with sha512 $other, not " +
                          s"the ledger's ${pins.sha512}; it is kept (never overwritten or deleted by " +
                          s"the build)$kept; the human inspects and removes them"
                      )
                    else {
                      drop()
                      Right(
                        Dist(
                          dest,
                          downloadedNow = true,
                          s"downloaded ${pins.url}, but $CacheRel/${pins.file} appeared meanwhile " +
                            "with the ledger's sha512; it is used and this invocation's partial " +
                            s"download was deleted$othersNote"
                        )
                      )
                    }
                  }
              }
          }
      }
    }
  }

  // --- Distribution layout ------------------------------------------------------------------

  /** Launcher scripts the runs execute: each an executable regular file (no symbolic link). */
  val ExecutableScripts: Seq[String] = Seq("bin/spark-submit", "bin/spark-class")

  /**
   * Sourced, never executed (`bin/spark-class` runs `. "${SPARK_HOME}"/bin/load-spark-env.sh`): a
   * readable regular file (no symbolic link); no execute bit (the pinned tarball ships it 0644).
   */
  val SourcedScripts: Seq[String] = Seq("bin/load-spark-env.sh")

  /**
   * The extracted `<top>/`: spark-submit and spark-class executable, load-spark-env.sh readable;
   * jars/ matching the ledger.
   */
  def distributionProblems(home: Path, pins: Pins): Seq[String] = {
    val p = ListBuffer.empty[String]
    def script(r: String, executable: Boolean): Unit = {
      val f = home.resolve(r)
      if (Files.isSymbolicLink(f)) p += s"$r is a symbolic link"
      else if (!Files.exists(f, LinkOption.NOFOLLOW_LINKS)) p += s"$r is missing"
      else if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) p += s"$r is not a regular file"
      else if (executable) {
        if (!Files.isExecutable(f)) p += s"$r is not executable"
      } else if (!Files.isReadable(f))
        p += s"$r is not readable (it is sourced by bin/spark-class, never executed)"
    }
    ExecutableScripts.foreach(script(_, executable = true))
    SourcedScripts.foreach(script(_, executable = false))
    val jars = home.resolve("jars")
    val names =
      if (!Files.isDirectory(jars, LinkOption.NOFOLLOW_LINKS)) {
        p += "jars/ is missing"
        Nil
      } else {
        val s = Files.list(jars)
        try s.iterator.asScala.map(_.getFileName.toString).toList.sorted
        finally s.close()
      }
    def one(prefix: String, want: String): Unit = {
      val found = names.filter(n => n.startsWith(prefix) && n.endsWith(".jar"))
      val wantName = s"$prefix$want.jar"
      if (found != Seq(wantName))
        p += s"jars/ holds ${if (found.isEmpty) "no" else found.mkString(", ")} for $prefix*, expected exactly $wantName (ledger)"
    }
    if (names.nonEmpty) {
      one("scala-library-", pins.distScalaLibrary)
      one("hadoop-client-api-", pins.distHadoopClient)
      one("hadoop-client-runtime-", pins.distHadoopClient)
      one("spark-core_2.13-", pins.spark)
      one("spark-sql_2.13-", pins.spark)
      val cloud = names.filter(n => n.startsWith("hadoop-aws") || n.startsWith("aws-java-sdk"))
      if (cloud.nonEmpty) p += s"jars/ already holds ${cloud.mkString(", ")}"
    }
    p.toList
  }

  // --- Assembly content check -----------------------------------------------------------------

  final case class AssemblyReport(
      sha256: String,
      bytes: Long,
      entries: Int,
      classes: Int,
      scala3Classes: Int,
      timestamps: Int,
      mainClass: String,
      topLevel: Seq[(String, Int)]
  )

  def jarEntries(jar: File): Seq[java.util.zip.ZipEntry] = {
    val z = new java.util.zip.ZipFile(jar)
    try z.entries().asScala.toList
    finally z.close()
  }

  /**
   * The job jar: Main-Class equals `main`; no entry under [[ForbiddenPrefixes]]; no class of the
   * scala-library jar; every `scala/` class is a class of the scala3-library jar. Returns the
   * problems and the report (sha256, counts, distinct entry timestamps).
   */
  def assemblyProblems(
      jar: File,
      scalaLibrary: Set[String],
      scala3Library: Set[String],
      main: String
  ): (Seq[String], AssemblyReport) = {
    val p = ListBuffer.empty[String]
    val entries = jarEntries(jar)
    val names = entries.map(_.getName)
    val manifestMain = Try {
      val j = new java.util.jar.JarFile(jar)
      try
        Option(j.getManifest)
          .flatMap(m => Option(m.getMainAttributes.getValue("Main-Class")))
          .getOrElse("")
      finally j.close()
    }.getOrElse("")
    if (manifestMain != main) p += s"Main-Class is '$manifestMain', expected $main"
    ForbiddenPrefixes.foreach { f =>
      val hits = names.filter(_.startsWith(f))
      if (hits.nonEmpty)
        p += s"${hits.size} entr(y/ies) under $f (supplied by EMR or the distribution), first ${hits.head}"
    }
    val classes = names.filter(_.endsWith(".class"))
    val fromLibrary = classes.filter(scalaLibrary.contains)
    if (fromLibrary.nonEmpty)
      p += s"${fromLibrary.size} scala-library 2.13 class(es), first ${fromLibrary.head} (the runtime supplies scala-library)"
    val scalaClasses = classes.filter(_.startsWith("scala/"))
    val foreign = scalaClasses.filterNot(scala3Library.contains)
    if (foreign.nonEmpty)
      p += s"${foreign.size} scala/ class(es) not from scala3-library_3, first ${foreign.head}"
    val top = classes
      .map(n => n.split('/').take(2).mkString("/"))
      .groupBy(identity)
      .map { case (k, v) => k -> v.size }
      .toSeq
      .sortBy(x => (-x._2, x._1))
    val report = AssemblyReport(
      digestFile(jar.toPath, "SHA-256"),
      jar.length,
      entries.size,
      classes.size,
      scalaClasses.size,
      entries.map(_.getTime).distinct.size,
      manifestMain,
      top
    )
    (p.toList, report)
  }

  private def moduleFile(
      cp: Seq[Attributed[File]],
      org: String,
      name: String
  ): Option[(File, String)] =
    cp.flatMap(a =>
      a.get(Keys.moduleID.key)
        .filter(m => m.organization == org && m.name == name)
        .map(m => (a.data, m.revision))
    ).headOption

  private def classNames(f: File): Set[String] =
    jarEntries(f).map(_.getName).filter(_.endsWith(".class")).toSet

  /** The check of `sbt packageJobs` (and of sparkSubmitLocal): refuses, or logs and returns. */
  def checkAssembly(
      task: String,
      jar: File,
      compileClasspath: Seq[Attributed[File]],
      scalaVersion: String,
      runtimeScalaLibrary: String,
      log: Logger
  ): AssemblyReport = {
    def no(m: String): Nothing = throw new MessageOnlyException(s"$task: refused: $m")
    val (lib, libRev) = moduleFile(compileClasspath, "org.scala-lang", "scala-library")
      .getOrElse(
        no("no org.scala-lang:scala-library on sparkAnalytics Compile/dependencyClasspath")
      )
    val (lib3, lib3Rev) = moduleFile(compileClasspath, "org.scala-lang", "scala3-library_3")
      .getOrElse(
        no("no org.scala-lang:scala3-library_3 on sparkAnalytics Compile/dependencyClasspath")
      )
    if (lib3Rev != scalaVersion)
      no(s"scala3-library_3 is $lib3Rev, not the ledger's scala=$scalaVersion")
    if (libRev != runtimeScalaLibrary)
      no(
        s"Scala $scalaVersion resolves scala-library $libRev, but the ledger's " +
          s"spark.runtime.scala-library is $runtimeScalaLibrary; the runtime must supply the library " +
          "the code was compiled against (update the ledger value with the Scala version)"
      )
    val (problems, r) = assemblyProblems(jar, classNames(lib), classNames(lib3), JobMainClass)
    if (problems.nonEmpty) no(s"assembly ${jar.getName}: ${problems.mkString("; ")}")
    log.info(
      s"$task: PASS: ${jar.getName}: Main-Class ${r.mainClass}; no class under " +
        s"${ForbiddenPrefixes.mkString(", ")}; no scala-library $libRev class; ${r.scala3Classes} scala/ " +
        s"class(es), all from scala3-library_3 $lib3Rev; ${r.entries} entries, ${r.classes} classes, " +
        s"${r.timestamps} distinct entry timestamp(s); top-level packages " +
        r.topLevel.take(8).map { case (k, n) => s"$k:$n" }.mkString(", ")
    )
    log.info(s"$task: ${jar.getAbsolutePath} bytes=${r.bytes} sha256=${r.sha256}")
    r
  }

  // --- Runtime jars (resolved through the build's resolver) -----------------------------------

  def resolveJar(dr: DependencyResolution, m: ModuleID, log: Logger): Either[String, File] = {
    val module = dr.wrapDependencyInModule(m.intransitive())
    dr.update(module, UpdateConfiguration(), UnresolvedWarningConfiguration(), log) match {
      case Left(w) => Left(s"cannot resolve $m (${w.resolveException.getMessage})")
      case Right(report) =>
        val files = (for {
          c <- report.configurations
          mr <- c.modules
          if mr.module.organization == m.organization && mr.module.name == m.name &&
            mr.module.revision == m.revision
          (a, f) <- mr.artifacts
          if a.`type` == "jar" && a.classifier.isEmpty
        } yield f).distinct
        files match {
          case Seq(f) if f.isFile => Right(f)
          case other => Left(s"$m resolved to ${other.size} jar file(s): ${other.mkString(", ")}")
        }
    }
  }

  def runtimeModules(p: Pins): Seq[ModuleID] = Seq(
    "org.scala-lang" % "scala-library" % p.runtimeScalaLibrary,
    "org.apache.hadoop" % "hadoop-aws" % p.hadoopAws,
    "com.amazonaws" % "aws-java-sdk-bundle" % p.sdkBundle
  )

  // --- spark-submit command and child environment ---------------------------------------------

  /** The job's 17 flags for one run. */
  def jobArgs(
      archiveRoot: Path,
      cfg: SparkItRuns.ItConfig,
      runDir: Path,
      configFile: Path,
      pairsFile: Path,
      codeVersion: String
  ): Seq[String] = {
    def run(key: String): String = cfg.runs.collectFirst { case (s, r) if s.key == key => r }.get
    Seq(
      "--input-root" -> archiveRoot.toString,
      "--curated-run" -> run(SparkItRuns.KeyCurated),
      "--decision-run" -> run(SparkItRuns.KeyDecisions),
      "--grouping-run" -> run(SparkItRuns.KeyAssignments),
      "--output-root" -> runDir.resolve("out").toString,
      "--checkpoint-dir" -> runDir.resolve("checkpoint").toString,
      "--config" -> configFile.toString,
      "--benchmark-pairs" -> pairsFile.toString,
      "--code-version" -> codeVersion,
      "--attempt" -> Attempt.toString,
      "--partitions" -> Partitions.toString,
      "--checkpoint-interval" -> CheckpointInterval.toString
    ).flatMap { case (k, v) => Seq(k, v) } ++ Limits.flatMap { case (k, v) => Seq(k, v) }
  }

  /** The exact spark-submit argv (client mode) for one local run. */
  def submitCommand(
      sparkHome: Path,
      master: String,
      jobJar: Path,
      runtimeJars: Seq[Path],
      runDir: Path,
      java17: Seq[String],
      args: Seq[String]
  ): Seq[String] = {
    val cp = runtimeJars.map(_.toString).mkString(File.pathSeparator)
    val driverOpts = (java17 ++ Seq(
      s"-Djava.io.tmpdir=${runDir.resolve("tmp")}",
      s"-XX:ErrorFile=${runDir.resolve("hs_err_driver_pid%p.log")}"
    )).mkString(" ")
    val execOpts = (java17 ++ Seq(
      s"-Djava.io.tmpdir=${runDir.resolve("executor-tmp")}",
      s"-XX:ErrorFile=${runDir.resolve("hs_err_executor_pid%p.log")}"
    )).mkString(" ")
    Seq(
      sparkHome.resolve("bin").resolve("spark-submit").toString,
      "--master",
      master,
      "--deploy-mode",
      "client",
      "--name",
      s"scholarchain-$Task",
      "--class",
      JobMainClass,
      "--driver-memory",
      "1g",
      "--driver-class-path",
      cp,
      "--driver-java-options",
      driverOpts,
      "--conf",
      s"spark.executor.extraClassPath=$cp",
      "--conf",
      s"spark.executor.extraJavaOptions=$execOpts",
      "--conf",
      "spark.eventLog.enabled=true",
      "--conf",
      s"spark.eventLog.dir=${runDir.resolve("events").toUri}",
      "--conf",
      "spark.yarn.maxAppAttempts=1",
      "--conf",
      "spark.hadoop.fs.s3a.directory.marker.retention=keep",
      "--conf",
      "spark.ui.enabled=false",
      "--conf",
      s"spark.local.dir=${runDir.resolve("local")}",
      "--conf",
      "spark.driver.host=127.0.0.1",
      "--conf",
      "spark.driver.bindAddress=127.0.0.1",
      jobJar.toString
    ) ++ args
  }

  /** The child's environment: inherited minus [[strippedFromChild]], plus `add`. */
  def childEnv(inherited: Map[String, String], add: Map[String, String]): Map[String, String] =
    inherited.filterNot { case (k, _) => strippedFromChild(k) } ++ add

  val Log4j2Properties: String =
    "# Generated by sbt sparkSubmitLocal (BE-P6-09) for this attempt only.\n" +
      "rootLogger.level = warn\n" +
      "rootLogger.appenderRef.stderr.ref = console\n" +
      "appender.console.type = Console\n" +
      "appender.console.name = console\n" +
      "appender.console.target = SYSTEM_ERR\n" +
      "appender.console.layout.type = PatternLayout\n" +
      "appender.console.layout.pattern = %d{yy/MM/dd HH:mm:ss} %p %c{1}: %m%n\n"

  /**
   * Runs `command` in `dir` with exactly `env` (the process environment is cleared first), stdin
   * closed, output to the task log and to `outputFile`; bounded (descendants then the process are
   * stopped). Some(exit) or None on timeout.
   */
  def runProcess(
      command: Seq[String],
      dir: Path,
      env: Map[String, String],
      boundSeconds: Long,
      outputFile: Path,
      log: Logger
  ): Option[Int] = {
    val b = new java.lang.ProcessBuilder(command.asJava)
    b.directory(dir.toFile)
    b.redirectErrorStream(true)
    val e = b.environment()
    e.clear()
    env.foreach { case (k, v) => e.put(k, v) }
    val child =
      try b.start()
      catch { case x: IOException => fail(s"could not start ${command.head}: ${x.getMessage}") }
    child.getOutputStream.close()
    val sink =
      Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)
    val pump = new Thread(
      new Runnable {
        def run(): Unit = {
          val r =
            new BufferedReader(new InputStreamReader(child.getInputStream, StandardCharsets.UTF_8))
          try {
            var line = r.readLine()
            while (line != null) {
              log.info(line)
              sink.write(line)
              sink.newLine()
              line = r.readLine()
            }
          } catch { case _: IOException => () }
          finally { Try(r.close()); Try(sink.close()); () }
        }
      },
      s"$Task-output"
    )
    pump.setDaemon(true)
    pump.start()
    def stopAll(): Unit = {
      child.toHandle.descendants().iterator().asScala.foreach(h => Try(h.destroy()))
      child.destroy()
      if (!child.waitFor(StopGraceSeconds, TimeUnit.SECONDS)) {
        child.toHandle.descendants().iterator().asScala.foreach(h => Try(h.destroyForcibly()))
        child.destroyForcibly()
        child.waitFor(StopGraceSeconds, TimeUnit.SECONDS)
      }
      ()
    }
    val killer = new Thread(new Runnable { def run(): Unit = stopAll() })
    java.lang.Runtime.getRuntime.addShutdownHook(killer)
    try {
      if (child.waitFor(boundSeconds, TimeUnit.SECONDS)) {
        pump.join(DrainMillis)
        Some(child.exitValue())
      } else {
        log.error(
          s"$Task: the ${boundSeconds}s bound was reached; stopping the child and its descendants"
        )
        stopAll()
        pump.join(DrainMillis)
        None
      }
    } finally {
      Try(java.lang.Runtime.getRuntime.removeShutdownHook(killer))
      Try(sink.close())
      ()
    }
  }

  // --- Per-run output check -----------------------------------------------------------------

  final case class Published(runName: String, manifest: Array[Byte], eventLog: String)

  private def list(dir: Path): Seq[String] =
    if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) Nil
    else {
      val s = Files.list(dir)
      try s.iterator.asScala.map(_.getFileName.toString).toList.sorted
      finally s.close()
    }

  /** One run's outputs: exactly one published `run=<hex>`, no scratch, exactly one event log. */
  def publishedProblems(runDir: Path): Either[String, Published] = {
    val p = ListBuffer.empty[String]
    val out = runDir.resolve("out")
    val names = list(out)
    val runs = names.filter(n => RunDir.pattern.matcher(n).matches)
    val scratch = names.filter(_.startsWith(".spark-"))
    if (runs.size != 1)
      p += s"out/ holds ${runs.size} run=<hex> directories (expected 1): ${names.mkString(", ")}"
    if (scratch.nonEmpty) p += s"out/ still holds scratch ${scratch.mkString(", ")}"
    names
      .filter(n => Files.isSymbolicLink(out.resolve(n)))
      .foreach(n => p += s"out/$n is a symbolic link")
    val run = runs.headOption.map(out.resolve)
    run.foreach { r =>
      if (Files.isSymbolicLink(r) || !Files.isDirectory(r, LinkOption.NOFOLLOW_LINKS))
        p += s"${r.getFileName} is not a real directory"
      Seq("_SUCCESS", "_parts.json", "_run-manifest.json").foreach { f =>
        if (!Files.isRegularFile(r.resolve(f), LinkOption.NOFOLLOW_LINKS))
          p += s"${r.getFileName}/$f is not a regular file"
      }
      if (Files.exists(r.resolve("_UNVERIFIED"), LinkOption.NOFOLLOW_LINKS))
        p += s"${r.getFileName} holds _UNVERIFIED"
    }
    val events = list(runDir.resolve("events"))
    val good = events.filter { n =>
      val f = runDir.resolve("events").resolve(n)
      Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS) && Files.size(f) > 0 && !n.endsWith(
        ".inprogress"
      )
    }
    if (events.size != 1 || good.size != 1)
      p += s"events/ holds ${events.mkString(", ")}; expected exactly one complete, non-empty event log"
    if (p.nonEmpty) Left(p.mkString("; "))
    else {
      val r = run.get
      if (Files.size(r.resolve("_run-manifest.json")) > 1024L * 1024L)
        Left("_run-manifest.json is larger than 1 MiB")
      else
        Right(
          Published(
            r.getFileName.toString,
            Files.readAllBytes(r.resolve("_run-manifest.json")),
            good.head
          )
        )
    }
  }

  // --- Comparison result (interface required from spark-engineer) -----------------------------

  final case class Side(run: String, counts: Map[String, Long], loads: Map[String, (Long, Long)])
  final case class Comparison(
      left: Side,
      right: Side,
      datasets: Seq[(String, Long, Long, Boolean)]
  )

  private def nat(s: String): Option[Long] = Try(s.toLong).toOption.filter(_ >= 0)

  /**
   * Strict parse of the comparison result written by [[CompareMainClass]] (tab-separated, LF line
   * ends, final LF, nothing else):
   * {{{
   * format=scholarchain.spark.published-run-comparison.v1
   * nonce=<64 hex>
   * run  left   <run=hex>
   * run  right  <run=hex>
   * count  left|right  edges|metrics  <n>              (each side, both names)
   * load   left|right  <store>  <items>  <rows>       (each side, one line per load manifest)
   * dataset  <name>  <left rows>  <right rows>  equal|different   (one line per dataset)
   * datasets=<number of dataset lines>
   * complete=<64 hex>
   * }}}
   */
  def parseComparison(text: String, nonce: String): Either[String, Comparison] = {
    val lines = text.split("\n", -1).toVector
    if (!text.endsWith("\n") || lines.size < 6)
      Left("the comparison result is incomplete (no final line)")
    else if (lines(0) != s"format=$ComparisonFormat")
      Left(s"the comparison result does not start with format=$ComparisonFormat")
    else if (lines(1) != s"nonce=$nonce" || lines(lines.size - 2) != s"complete=$nonce")
      Left("the comparison result does not carry this run's nonce on its first and last lines")
    else {
      val body = lines.slice(2, lines.size - 3)
      val bad = ListBuffer.empty[Int]
      val runs = scala.collection.mutable.Map.empty[String, String]
      val counts = scala.collection.mutable.Map.empty[(String, String), Long]
      val loads = scala.collection.mutable.Map.empty[(String, String), (Long, Long)]
      val ds = ListBuffer.empty[(String, Long, Long, Boolean)]
      def side(s: String): Boolean = s == "left" || s == "right"
      body.zipWithIndex.foreach { case (l, i) =>
        val f = l.split("\t", -1).toSeq
        val ok = f match {
          case Seq("run", s, r)
              if side(s) && RunDir.pattern.matcher(r).matches && !runs.contains(s) =>
            runs(s) = r; true
          case Seq("count", s, k, n)
              if side(s) && (k == "edges" || k == "metrics") && nat(n).isDefined && !counts
                .contains((s, k)) =>
            counts((s, k)) = nat(n).get; true
          case Seq("load", s, store, a, b)
              if side(s) && store.nonEmpty && nat(a).isDefined && nat(b).isDefined && !loads
                .contains((s, store)) =>
            loads((s, store)) = (nat(a).get, nat(b).get); true
          case Seq("dataset", n, a, b, e)
              if n.nonEmpty && nat(a).isDefined && nat(
                b
              ).isDefined && (e == "equal" || e == "different") && !ds.exists(_._1 == n) =>
            ds += ((n, nat(a).get, nat(b).get, e == "equal")); true
          case _ => false
        }
        if (!ok) bad += i + 3
      }
      val declared = lines(lines.size - 3).stripPrefix("datasets=")
      if (bad.nonEmpty)
        Left(s"the comparison result has malformed or repeated line(s) ${bad.mkString(", ")}")
      else if (
        !lines(lines.size - 3).startsWith("datasets=") || nat(declared) != Some(ds.size.toLong)
      )
        Left("the datasets= line does not match the dataset lines")
      else if (!runs.contains("left") || !runs.contains("right")) Left("a run line is missing")
      else {
        def sideOf(s: String) = Side(
          runs(s),
          counts.collect { case ((x, k), n) if x == s => k -> n }.toMap,
          loads.collect { case ((x, k), v) if x == s => k -> v }.toMap
        )
        Right(Comparison(sideOf("left"), sideOf("right"), ds.toList))
      }
    }
  }

  /** The PASS rule over the comparison (see the object comment, step 7). */
  def verdict(
      exit: Option[Int],
      result: Either[String, String],
      nonce: String,
      runName: String
  ): Either[String, Comparison] =
    exit match {
      case None => Left(s"the comparison exceeded its ${CompareTimeoutSeconds}s bound; NOT a pass")
      case Some(n) =>
        val parsed = result.flatMap(parseComparison(_, nonce))
        val p = ListBuffer.empty[String]
        if (n != 0) p += s"the comparison JVM ended with exit $n"
        parsed match {
          case Left(m) => p += m
          case Right(c) =>
            Seq(c.left, c.right).zip(Seq("left (local[2])", "right (local-cluster)")).foreach {
              case (s, label) =>
                if (s.run != runName) p += s"$label compared ${s.run}, not the published $runName"
                if (s.counts.get("edges") != Some(ExpectedEdges))
                  p += s"$label edges ${s.counts.getOrElse("edges", "missing")}, baseline $ExpectedEdges"
                if (s.counts.get("metrics") != Some(ExpectedMetrics))
                  p += s"$label metrics ${s.counts.getOrElse("metrics", "missing")}, baseline $ExpectedMetrics"
                if (s.loads != ExpectedLoads)
                  p += s"$label load manifests ${s.loads.toSeq.sorted.map { case (k, (a, b)) => s"$k:$a/$b" }.mkString(",")}, " +
                    s"baseline ${ExpectedLoads.toSeq.sorted.map { case (k, (a, b)) => s"$k:$a/$b" }.mkString(",")}"
            }
            if (c.datasets.size != ExpectedDatasets)
              p += s"${c.datasets.size} datasets compared, baseline $ExpectedDatasets"
            c.datasets.filter(d => !d._4 || d._2 != d._3).foreach { d =>
              p += s"dataset ${d._1} differs (left ${d._2} rows, right ${d._3} rows)"
            }
        }
        if (p.isEmpty) parsed else Left(s"FAILED: ${p.mkString("; ")}; NOT a pass")
    }

  // --- The task -------------------------------------------------------------------------------

  final case class Pre(repo: Path, head: String, pins: Pins, it: SparkItRuns.Preflight)

  /** Step 1 (and 3): every check that needs no compiled code. */
  def preChecks(
      buildBase: File,
      home: Option[String],
      inherited: Map[String, String],
      projectTargets: Seq[File],
      sparkTarget: File,
      ledger: Ledger.Versions,
      javaHome: Option[File],
      log: Logger
  ): Pre = {
    val jdk = javaHome.getOrElse(new File(System.getProperty("java.home")))
    javaHomeProblem(jdk).foreach(refuse)
    val pins = orRefuse(pinsFrom(ledger.entries.get).left.map(m => s"version ledger: $m"))
    val repo = buildBase.toPath.toRealPath()
    if (!SafePath.pattern.matcher(repo.toString).matches)
      refuse(
        s"the repository path $repo holds a character spark-submit options cannot carry (space, quote, comma)"
      )
    val run = LocalRuns.gitRunnerAt(repo.toFile)
    val st = LocalRuns.scopeEqualsHead(
      Task,
      repo.toFile,
      run,
      CodeScope,
      GeneratedDirs,
      "the job jar is built from this scope",
      "the code-scope check (BE-P6-09)"
    )
    val head = run(Seq("rev-parse", "--verify", "HEAD"))
      .collect {
        case (0, out) if Hex40.pattern.matcher(out.trim).matches => out.trim
      }
      .getOrElse(refuse("git rev-parse --verify HEAD did not print a 40-hex commit"))
    log.info(
      s"$Task: code scope ${CodeScope.mkString(" ")}: ${st.tracked} tracked files, raw bytes equal " +
        s"to HEAD $head, no uncommitted path; ${st.ignoredAllowed.size} ignored sbt target or " +
        ".DS_Store path(s); --code-version will be HEAD"
    )
    Seq(ConfigRel, PairsRel).foreach { r =>
      if (!Files.isRegularFile(repo.resolve(r), LinkOption.NOFOLLOW_LINKS))
        refuse(s"$r is not a regular file")
    }
    val it =
      try SparkItRuns.preflight(buildBase, home, inherited, projectTargets, sparkTarget, log)
      catch {
        case e: MessageOnlyException =>
          refuse(s"(archive preflight shared with sparkIT) ${e.getMessage}")
      }
    Pre(repo, head, pins, it)
  }

  /** Step 1; returns the HEAD it observed, which step 3 requires unchanged (CR-P6-27 m2). */
  def beforeCompile(
      buildBase: File,
      home: Option[String],
      inherited: Map[String, String],
      projectTargets: Seq[File],
      sparkTarget: File,
      ledger: Ledger.Versions,
      javaHome: Option[File],
      log: Logger
  ): String = {
    log.info(s"$Task: Java, ledger, code-scope and archive checks before compiling")
    preChecks(buildBase, home, inherited, projectTargets, sparkTarget, ledger, javaHome, log).head
  }

  /**
   * "HEAD must not move" (CR-P6-27 m2): the refusal when the HEAD seen after compiling differs from
   * the HEAD seen before compiling, or None. The pre-compile HEAD is the `--code-version`.
   */
  def headMoved(beforeCompile: String, afterCompile: String): Option[String] =
    if (Hex40.pattern.matcher(beforeCompile).matches && beforeCompile == afterCompile) None
    else
      Some(
        s"HEAD moved while sbt compiled: $beforeCompile before compiling, $afterCompile after; the " +
          "assembly may not be the code of either commit; run the task again on a stable checkout"
      )

  def sparkSubmitLocal(
      buildBase: File,
      home: Option[String],
      inherited: Map[String, String],
      projectTargets: Seq[File],
      sparkTarget: File,
      ledger: Ledger.Versions,
      javaHome: Option[File],
      headBeforeCompile: String,
      assemblyJar: File,
      compileClasspath: Seq[Attributed[File]],
      testClasspath: Seq[File],
      testMainClasses: Seq[String],
      dr: DependencyResolution,
      java17: Seq[String],
      isolation: Map[String, String],
      log: Logger
  ): Unit = {
    val pre =
      preChecks(buildBase, home, inherited, projectTargets, sparkTarget, ledger, javaHome, log)
    headMoved(headBeforeCompile, pre.head).foreach(refuse)
    val pins = pre.pins
    val repo = pre.repo
    if (!testMainClasses.contains(CompareMainClass))
      refuse(
        s"the comparison entry point $CompareMainClass is not in the sparkAnalytics Test tree " +
          "(owner spark-engineer; interface in project/SparkSubmitLocal.scala parseComparison and the " +
          "BE-P6-09 report); without it no PASS is possible, so nothing is downloaded or run"
      )
    val report = checkAssembly(
      Task,
      assemblyJar,
      compileClasspath,
      ledger("scala"),
      pins.runtimeScalaLibrary,
      log
    )
    val jars = runtimeModules(pins).map(m => m -> orRefuse(resolveJar(dr, m, log)))
    val awsJar = jars(1)._2
    val awsSha1 = digestFile(awsJar.toPath, "SHA-1")
    if (awsSha1 != pins.hadoopAwsSha1)
      refuse(
        s"${awsJar.getName} has sha1 $awsSha1, not the ledger's hadoop-aws.s3a.jar-sha1 ${pins.hadoopAwsSha1}"
      )

    val cache = orRefuse(ensureDir(repo, CacheRel))
    val dist = orRefuse(
      ensureDistribution(
        cache,
        pins,
        httpsFetch(pins.url, DownloadTimeoutSeconds, MaxDistributionBytes),
        newNonce()
      )
    )
    log.info(s"$Task: ${dist.note}")
    if (dist.downloadedNow)
      fail(
        s"downloaded and verified ${pins.file} into $CacheRel/ (sha512 ${pins.sha512}); no Spark run " +
          s"was started in this invocation (tool-call bound); run `sbt $Task` again; NOT a pass"
      )

    val attempt = orRefuse(nextAttempt(orRefuse(ensureDir(repo, AttemptsRel))))
    val attemptRel = repo.relativize(attempt).toString
    log.info(s"$Task: attempt $attemptRel (create-once; removed only by sbt clean)")
    val distDir = Files.createDirectory(attempt.resolve("dist"))
    val ex = orFail(
      extractTarGz(dist.file, distDir, topDir(pins), Tar).left.map(m =>
        s"extraction refused: $m; attempt $attemptRel kept"
      )
    )
    val sparkHome = distDir.resolve(topDir(pins))
    val layout = distributionProblems(sparkHome, pins)
    if (layout.nonEmpty)
      fail(
        s"the extracted distribution differs from the ledger: ${layout.mkString("; ")}; attempt $attemptRel kept"
      )
    log.info(
      s"$Task: extracted ${ex.files} files (${ex.executables} executable), ${ex.dirs} directories, " +
        s"${ex.bytes} bytes; jars/ holds scala-library-${pins.distScalaLibrary}.jar, " +
        s"hadoop-client-api/runtime-${pins.distHadoopClient}, spark-core_2.13-${pins.spark}"
    )
    val rtDir = Files.createDirectory(attempt.resolve("runtime-jars"))
    val runtimeJars = jars.map { case (_, f) =>
      val t = rtDir.resolve(f.getName)
      Files.copy(f.toPath, t)
      t
    }
    val jobDir = Files.createDirectory(attempt.resolve("job"))
    val jobJar = jobDir.resolve(assemblyJar.getName)
    Files.copy(assemblyJar.toPath, jobJar)
    if (digestFile(jobJar, "SHA-256") != report.sha256)
      fail("the copied job jar differs from the checked assembly")
    val confDir = Files.createDirectory(attempt.resolve("conf"))
    Files.write(
      confDir.resolve("log4j2.properties"),
      Log4j2Properties.getBytes(StandardCharsets.UTF_8),
      StandardOpenOption.CREATE_NEW
    )
    val inventory =
      (runtimeJars.map(j =>
        s"runtime-jar\t${j.getFileName}\tbytes=${Files.size(j)}\tsha256=${digestFile(j, "SHA-256")}"
      ) ++
        Seq(
          s"job-jar\t${jobJar.getFileName}\tbytes=${report.bytes}\tsha256=${report.sha256}",
          s"distribution\t${pins.file}\tbytes=${Files.size(dist.file)}\tsha512=${pins.sha512}",
          s"hadoop-aws-sha1\t$awsSha1",
          s"code-version\t${pre.head}"
        )).mkString("", "\n", "\n")
    Files.write(
      attempt.resolve("inventory.txt"),
      inventory.getBytes(StandardCharsets.UTF_8),
      StandardOpenOption.CREATE_NEW
    )
    inventory.split("\n").foreach(l => log.info(s"$Task: $l"))

    val cmds = ListBuffer.empty[String]
    val published = Masters.zipWithIndex.map { case (master, i) =>
      val label = if (master.startsWith("local-cluster")) "local-cluster" else "local"
      val runDir = Files.createDirectory(attempt.resolve(s"run-${i + 1}-$label"))
      Seq("out", "checkpoint", "events", "tmp", "executor-tmp", "local", "work").foreach(d =>
        Files.createDirectory(runDir.resolve(d))
      )
      val args = jobArgs(
        pre.it.root,
        pre.it.cfg,
        runDir,
        repo.resolve(ConfigRel),
        repo.resolve(PairsRel),
        pre.head
      )
      val cmd = submitCommand(sparkHome, master, jobJar, runtimeJars, runDir, java17, args)
      cmds += cmd.mkString(" ")
      val env = childEnv(
        inherited,
        isolation ++ Map(
          "JAVA_HOME" -> javaHome
            .getOrElse(new File(System.getProperty("java.home")))
            .getAbsolutePath,
          "SPARK_HOME" -> sparkHome.toString,
          "SPARK_CONF_DIR" -> confDir.toString,
          "SPARK_LOCAL_IP" -> "127.0.0.1",
          "SPARK_WORKER_DIR" -> runDir.resolve("work").toString
        )
      )
      log.info(
        s"$Task: run ${i + 1}: ${cmd.mkString(" ")} (bound ${RunTimeoutSeconds}s; no stdin; output also in ${repo.relativize(runDir)}/output.txt)"
      )
      val exit = runProcess(cmd, runDir, env, RunTimeoutSeconds, runDir.resolve("output.txt"), log)
      exit match {
        case Some(0) => ()
        case Some(n) =>
          fail(s"run ${i + 1} ($master) exited $n; attempt $attemptRel kept; NOT a pass")
        case None =>
          fail(
            s"run ${i + 1} ($master) exceeded ${RunTimeoutSeconds}s and was stopped; attempt $attemptRel kept; NOT a pass"
          )
      }
      val pub =
        orFail(publishedProblems(runDir).left.map(m => s"run ${i + 1} ($master): $m; NOT a pass"))
      log.info(
        s"$Task: run ${i + 1} ($master): exit 0; published ${pub.runName} with _SUCCESS; event log events/${pub.eventLog}"
      )
      (runDir, pub)
    }
    Files.write(
      attempt.resolve("submit-commands.txt"),
      cmds.mkString("", "\n", "\n").getBytes(StandardCharsets.UTF_8),
      StandardOpenOption.CREATE_NEW
    )
    val (a, b) = (published(0)._2, published(1)._2)
    if (a.runName != b.runName)
      fail(s"the two runs published different run IDs (${a.runName}, ${b.runName}); NOT a pass")
    if (!java.util.Arrays.equals(a.manifest, b.manifest))
      fail("the two runs' _run-manifest.json differ in bytes; NOT a pass")

    val cmpDir = Files.createDirectory(attempt.resolve("compare"))
    Files.createDirectory(cmpDir.resolve("tmp"))
    val nonce = newNonce()
    val resultFile = cmpDir.resolve("comparison.txt")
    val exit = LocalRuns.runForked(
      Task,
      cmpDir.toFile,
      javaHome,
      testClasspath,
      SparkItRuns.baseJvmOptions(java17, cmpDir),
      isolation,
      CompareMainClass,
      Seq(
        "--left-output-root",
        published(0)._1.resolve("out").toString,
        "--right-output-root",
        published(1)._1.resolve("out").toString,
        "--nonce",
        nonce,
        "--result",
        resultFile.toString
      ),
      CompareTimeoutSeconds,
      LocalRuns.ToLog(log),
      log,
      // CR-P6-27 m3: the same prefixes AND exact names as the spark-submit children.
      StrippedPrefixes,
      StrippedNames
    )
    val read =
      if (!Files.isRegularFile(resultFile, LinkOption.NOFOLLOW_LINKS))
        Left("the comparison wrote no regular result file")
      else if (Files.size(resultFile) > 1024L * 1024L)
        Left("the comparison result is larger than 1 MiB")
      else Right(new String(Files.readAllBytes(resultFile), StandardCharsets.UTF_8))
    val v = verdict(exit, read, nonce, a.runName)
    val summary = v.fold(
      m => s"status=fail\nreason=$m\n",
      c =>
        s"status=pass\nrun=${a.runName}\ndatasets=${c.datasets.size}\nedges=$ExpectedEdges\nmetrics=$ExpectedMetrics\n"
    ) + s"codeVersion=${pre.head}\njobJarSha256=${report.sha256}\nmasters=${Masters.mkString(",")}\n"
    Files.write(
      attempt.resolve("result.txt"),
      summary.getBytes(StandardCharsets.UTF_8),
      StandardOpenOption.CREATE_NEW
    )
    v match {
      case Right(c) =>
        log.info(
          s"$Task: PASS: spark-submit of ${jobJar.getFileName} (sha256 ${report.sha256}) on ${pins.file} " +
            s"exited 0 at ${Masters.mkString(" and ")}; both published ${a.runName} with _SUCCESS and " +
            s"byte-equal _run-manifest.json; ${c.datasets.size} datasets logically equal; edges " +
            s"$ExpectedEdges, metrics $ExpectedMetrics and the four load manifests equal the sparkIT " +
            s"run 08 baseline; evidence $attemptRel/ (result.txt, inventory.txt, submit-commands.txt, " +
            "run-*/output.txt, run-*/events/, compare/comparison.txt); copy it into the phase evidence"
        )
      case Left(m) => fail(s"$m; attempt $attemptRel kept")
    }
  }

  private def orFail[A](e: Either[String, A]): A = e.fold(fail, identity)

  // --- Self-test (part of `sbt check`; temporary directories only, no network) ---------------

  /** One synthetic tar entry for the self-test. */
  final case class TestEntry(name: String, kind: Char, data: Array[Byte], mode: Int)

  private def header(
      name: String,
      kind: Char,
      size: Long,
      mode: Int,
      prefix: String = ""
  ): Array[Byte] = {
    val h = new Array[Byte](512)
    def put(off: Int, s: String): Unit = {
      val b = s.getBytes(StandardCharsets.UTF_8)
      System.arraycopy(b, 0, h, off, b.length)
    }
    put(0, name)
    put(100, f"$mode%07o")
    put(108, "0000000")
    put(116, "0000000")
    put(124, f"$size%011o")
    put(136, "00000000000")
    h(156) = kind.toByte
    put(257, "ustar")
    put(263, "00")
    if (prefix.nonEmpty) put(345, prefix)
    (148 until 156).foreach(i => h(i) = ' '.toByte)
    val sum = h.map(_ & 0xff).sum
    put(148, f"$sum%06o")
    h(154) = 0
    h(155) = ' '.toByte
    h
  }

  def tarBytes(
      entries: Seq[TestEntry],
      endBlocks: Int = 2,
      corruptFirst: Boolean = false
  ): Array[Byte] = {
    val out = new ByteArrayOutputStream
    entries.zipWithIndex.foreach { case (e, i) =>
      val h = header(e.name, e.kind, e.data.length.toLong, e.mode)
      if (corruptFirst && i == 0) h(0) = (h(0) ^ 1).toByte
      out.write(h)
      out.write(e.data)
      out.write(new Array[Byte](((512 - e.data.length % 512) % 512)))
    }
    out.write(new Array[Byte](512 * endBlocks))
    out.toByteArray
  }

  private def deleteTree(p: Path): Unit =
    if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
      Files.walk(p).iterator.asScala.toList.reverse.foreach(q => Files.deleteIfExists(q))
    }

  private def writeJar(f: File, main: Option[String], entries: Seq[String]): Unit = {
    val m = new java.util.jar.Manifest()
    m.getMainAttributes.put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0")
    main.foreach(c => m.getMainAttributes.put(java.util.jar.Attributes.Name.MAIN_CLASS, c))
    val out = new java.util.jar.JarOutputStream(Files.newOutputStream(f.toPath), m)
    try
      entries.foreach { e =>
        out.putNextEntry(new java.util.zip.ZipEntry(e))
        out.write(Array[Byte](1, 2, 3))
        out.closeEntry()
      }
    finally out.close()
  }

  def guardSelfTest(
      ledger: Ledger.Versions,
      checkSteps: Seq[String],
      java17: Seq[String],
      log: Logger
  ): Unit = {
    val failures = ListBuffer.empty[String]
    var cases = 0
    def expect(name: String, ok: Boolean, got: => String): Unit = {
      cases += 1
      if (!ok) failures += s"case '$name': got $got"
    }
    def refusedWith(name: String, got: Either[String, Any], sub: String): Unit =
      expect(s"$name (refused)", got.left.exists(_.contains(sub)), got.fold(m => m, _ => "ALLOW"))

    // Fixed shape.
    expect(
      "task names no held-out split",
      !LocalRuns.namesHeldout(Task) && !LocalRuns.namesHeldout(SelfTest),
      Task
    )
    expect(
      "`check` never runs the task",
      !checkSteps.exists(_.trim.split("\\s+").head.endsWith(Task)),
      checkSteps.mkString(" ; ")
    )
    expect(
      "`check` runs the self-test before markTestRun",
      checkSteps.indexOf(SelfTest) >= 0 && checkSteps.indexOf(SelfTest) < checkSteps.indexOf(
        "markTestRun"
      ),
      checkSteps.mkString(" ; ")
    )
    expect(
      "code scope holds the build, core and spark",
      CodeScope == Seq("build.sbt", "project", "modules/core", "modules/spark"),
      CodeScope.mkString(" ")
    )
    expect(
      "outputs are under the root target",
      Seq(CacheRel, AttemptsRel).forall(r =>
        r.startsWith("target/") && !r.contains("streams") && !r.contains("local-runs") && !LocalRuns
          .namesHeldout(r)
      ),
      s"$CacheRel $AttemptsRel"
    )

    // Real ledger pins.
    val pins = pinsFrom(ledger.entries.get)
    expect("the ledger pins are consistent", pins.isRight, pins.fold(identity, _.toString))
    pins.foreach { p =>
      refusedWith(
        "http URL",
        pinsFrom(k =>
          if (k == "spark.distribution.url") Some(p.url.replace("https:", "http:"))
          else ledger.entries.get(k)
        ),
        "not under"
      )
      refusedWith(
        "file name not the URL tail",
        pinsFrom(k =>
          if (k == "spark.distribution.file") Some("spark-3.5.5-bin-hadoop3.tgz")
          else ledger.entries.get(k)
        ),
        "last segment"
      )
      refusedWith(
        "short sha512",
        pinsFrom(k =>
          if (k == "spark.distribution.sha512") Some("a" * 127) else ledger.entries.get(k)
        ),
        "128"
      )
      refusedWith(
        "hadoop-aws differs from the client",
        pinsFrom(k => if (k == "hadoop-aws.s3a") Some("3.4.1") else ledger.entries.get(k)),
        "must equal"
      )
      refusedWith(
        "missing key",
        pinsFrom(k => if (k == "aws-sdk-bundle.s3a") None else ledger.entries.get(k)),
        "missing"
      )
    }

    // Java release file.
    expect(
      "release Java 17",
      javaFeatureOf("IMPLEMENTOR=\"x\"\nJAVA_VERSION=\"17.0.17\"\n").contains(17),
      ""
    )
    expect("release Java 21", javaFeatureOf("JAVA_VERSION=\"21.0.2\"").contains(21), "")
    expect("release without version", javaFeatureOf("X=1").isEmpty, "")

    // Environment.
    val env = childEnv(
      Map(
        "PATH" -> "/bin",
        "AWS_PROFILE" -> "p",
        "SPARK_CONF_DIR" -> "/x",
        "HADOOP_CONF_DIR" -> "/y",
        "SCHOLARCHAIN_CFG_A" -> "1",
        "GIT_DIR" -> "/g",
        "MUNIT_FLAKY_OK" -> "1",
        "JAVA_TOOL_OPTIONS" -> "-x",
        "CLASSPATH" -> "/c",
        "HOME" -> "/h"
      ),
      Map("SPARK_HOME" -> "/s")
    )
    expect(
      "child env keeps PATH and HOME and adds SPARK_HOME, strips the rest",
      env == Map("PATH" -> "/bin", "HOME" -> "/h", "SPARK_HOME" -> "/s"),
      env.toString
    )
    // CR-P6-27 m3: the comparison JVM (LocalRuns.runForked with StrippedPrefixes and
    // StrippedNames) gets exactly the spark-submit children's stripping.
    val inheritedEnv = Map("PATH" -> "/bin", "HOME" -> "/h", "SPARK_CONF_DIR" -> "/x") ++
      (StrippedNames.toSeq ++ Seq("AWS_PROFILE", "GIT_DIR", "MUNIT_FLAKY_OK", "HADOOP_HOME"))
        .map(_ -> "v")
    val iso = Map("AWS_EC2_METADATA_DISABLED" -> "true")
    val cmpEnv = LocalRuns.forkedEnvironment(inheritedEnv, StrippedPrefixes, StrippedNames, iso)
    expect(
      "comparison JVM environment equals the spark-submit children's (same names stripped)",
      cmpEnv == childEnv(inheritedEnv, iso) &&
        cmpEnv == Map("PATH" -> "/bin", "HOME" -> "/h") ++ iso,
      cmpEnv.toString
    )
    StrippedNames.toSeq.sorted.foreach { n =>
      expect(
        s"comparison JVM strips $n",
        LocalRuns.strippedFromFork(n, StrippedPrefixes, StrippedNames) && !cmpEnv.contains(n),
        cmpEnv.toString
      )
    }
    val defaultEnv =
      LocalRuns.forkedEnvironment(inheritedEnv, LocalRuns.DefaultStrippedPrefixes, Set.empty, iso)
    expect(
      "default runForked stripping (local runs, sparkIT) is unchanged: prefixes only",
      StrippedNames.forall(defaultEnv.contains) && !defaultEnv.contains("AWS_PROFILE") &&
        defaultEnv.contains("GIT_DIR"),
      defaultEnv.toString
    )

    // CR-P6-27 m2: HEAD must not move between the pre- and post-compile checks.
    val h1 = "a" * 40
    expect("unchanged HEAD is accepted", headMoved(h1, h1).isEmpty, headMoved(h1, h1).toString)
    expect(
      "HEAD moved during compilation refuses",
      headMoved(h1, "b" * 40).exists(_.contains("HEAD moved")),
      headMoved(h1, "b" * 40).toString
    )
    expect("a malformed pre-compile HEAD refuses", headMoved("x", "x").nonEmpty, "ALLOW")

    // Command shape.
    val runDir = new File("/r/run-1-local").toPath
    val cfg = SparkItRuns.parseConfig(
      "archive.root.home-relative=A/b\n" +
        s"run.curated=modules/hadoop/target/local-pipeline/er-candidates/dev/attempt-1/curated/hadoop/snapshot=s/${SparkItRuns.PinnedCuratedRunName}\n" +
        s"run.decisions=target/local-runs/dev/er-decisions/run=${"b" * 64}\n" +
        s"run.assignments=target/local-runs/dev/er-assignments/run=${"c" * 64}\n"
    )
    expect("synthetic sparkIT configuration parses", cfg.isRight, cfg.fold(identity, _.toString))
    cfg.foreach { c =>
      val args = jobArgs(
        new File("/a").toPath,
        c,
        runDir,
        new File("/c.conf").toPath,
        new File("/p.json").toPath,
        "d" * 40
      )
      val flags = args.grouped(2).map(_.head).toSeq
      expect(
        "17 job flags, each once",
        flags.size == 17 && flags.distinct.size == 17,
        flags.mkString(" ")
      )
      expect(
        "code version is the given commit",
        args.sliding(2).exists(_ == Seq("--code-version", "d" * 40)),
        args.mkString(" ")
      )
      val cmd = submitCommand(
        new File("/s").toPath,
        "local-cluster[2,1,1024]",
        new File("/j.jar").toPath,
        Seq(new File("/l.jar").toPath),
        runDir,
        java17,
        args
      )
      Seq(
        "spark.yarn.maxAppAttempts=1",
        "spark.hadoop.fs.s3a.directory.marker.retention=keep",
        "spark.eventLog.enabled=true",
        "spark.executor.extraClassPath=/l.jar"
      ).foreach(s =>
        expect(s"command sets $s", cmd.sliding(2).exists(_ == Seq("--conf", s)), cmd.mkString(" "))
      )
      expect(
        "command sets the event log dir in the run directory",
        cmd.contains("spark.eventLog.dir=file:///r/run-1-local/events"),
        cmd.mkString(" ")
      )
      expect(
        "command puts the runtime jars first on the driver classpath",
        cmd.sliding(2).exists(_ == Seq("--driver-class-path", "/l.jar")),
        cmd.mkString(" ")
      )
      expect(
        "command carries the Java 17 opens to driver and executors",
        cmd.count(a => java17.forall(a.contains)) == 2,
        cmd.mkString(" ")
      )
      expect(
        "command runs the job class in client mode",
        cmd.sliding(2).exists(_ == Seq("--class", JobMainClass)) && cmd
          .sliding(2)
          .exists(_ == Seq("--deploy-mode", "client")),
        cmd.mkString(" ")
      )
      expect(
        "job jar precedes the job flags",
        cmd.indexOf("/j.jar") == cmd.size - args.size - 1,
        cmd.mkString(" ")
      )
    }

    // Tar reader.
    val tmp = Files.createTempDirectory("scholarchain-sparksubmit-selftest-")
    try {
      val top = "spark-x"
      var k = 0
      def extract(
          bytes: Array[Byte],
          limits: TarLimits = Tar
      ): (Path, Either[String, Extracted]) = {
        k += 1
        val d = Files.createDirectory(tmp.resolve(s"x$k"))
        (d, untar(new ByteArrayInputStream(bytes), d, top, limits))
      }
      val f1 = "abc".getBytes(StandardCharsets.UTF_8)
      val longName = s"$top/" + ("n" * 150) + "/file.txt"
      val good = Seq(
        TestEntry(s"$top/", '5', Array.emptyByteArray, 493),
        TestEntry(s"$top/bin/spark-submit", '0', f1, 493),
        TestEntry(s"./$top/README", '0', Array.fill(700)(7.toByte), 420),
        TestEntry(
          "././@LongLink",
          'L',
          (longName + "\u0000").getBytes(StandardCharsets.UTF_8),
          420
        ),
        TestEntry("ignored", '0', f1, 420),
        TestEntry(
          "pax",
          'x', {
            // A pax record's length counts its own decimal digits (here two).
            val r = s" path=$top/pax/name.txt\n"
            s"${r.length + 2}$r".getBytes(StandardCharsets.UTF_8)
          },
          420
        ),
        TestEntry("ignored2", '0', f1, 420)
      )
      val (d0, r0) = extract(tarBytes(good))
      expect("valid archive extracts", r0 == Right(Extracted(4, 1, 709, 1)), r0.toString)
      expect("executable bit kept", Files.isExecutable(d0.resolve(s"$top/bin/spark-submit")), "")
      expect("plain file not executable", !Files.isExecutable(d0.resolve(s"$top/README")), "")
      expect("GNU long name used", Files.isRegularFile(d0.resolve(longName)), "")
      expect("pax path used", Files.isRegularFile(d0.resolve(s"$top/pax/name.txt")), "")
      val gz = new ByteArrayOutputStream
      val z = new java.util.zip.GZIPOutputStream(gz)
      z.write(tarBytes(good)); z.close()
      val tgz = tmp.resolve("a.tgz")
      Files.write(tgz, gz.toByteArray)
      val d1 = Files.createDirectory(tmp.resolve("gz"))
      expect("gzip archive extracts", extractTarGz(tgz, d1, top, Tar).isRight, "")
      def one(name: String, e: TestEntry, sub: String): Unit =
        refusedWith(name, extract(tarBytes(Seq(e)))._2, sub)
      one("dot-dot name", TestEntry(s"$top/../x", '0', f1, 420), ".. segment")
      one("absolute name", TestEntry(s"/$top/x", '0', f1, 420), "absolute")
      one("other top directory", TestEntry("other/x", '0', f1, 420), "outside the expected top")
      one(
        "symbolic link",
        TestEntry(s"$top/l", '2', Array.emptyByteArray, 420),
        "links are refused"
      )
      one("hard link", TestEntry(s"$top/h", '1', Array.emptyByteArray, 420), "links are refused")
      one("fifo", TestEntry(s"$top/f", '6', Array.emptyByteArray, 420), "neither a regular file")
      one("backslash", TestEntry(s"$top/a\\b", '0', f1, 420), "backslash")
      refusedWith(
        "bad checksum",
        extract(tarBytes(Seq(TestEntry(s"$top/x", '0', f1, 420)), corruptFirst = true))._2,
        "checksum"
      )
      refusedWith(
        "no end blocks",
        extract(tarBytes(Seq(TestEntry(s"$top/x", '0', f1, 420)), endBlocks = 0))._2,
        "truncated"
      )
      refusedWith(
        "one end block then data",
        extract(tarBytes(Seq(TestEntry(s"$top/x", '0', f1, 420)), endBlocks = 1))._2,
        ""
      )
      refusedWith(
        "duplicate file",
        extract(
          tarBytes(Seq(TestEntry(s"$top/x", '0', f1, 420), TestEntry(s"$top/x", '0', f1, 420)))
        )._2,
        "appears twice"
      )
      val truncated =
        tarBytes(Seq(TestEntry(s"$top/x", '0', Array.fill(2000)(1.toByte), 420))).take(1024)
      refusedWith("truncated data", extract(truncated)._2, "truncated")
      refusedWith(
        "entry bound",
        extract(tarBytes(good), Tar.copy(maxEntries = 2))._2,
        "entries (bound)"
      )
      refusedWith(
        "byte bound",
        extract(tarBytes(good), Tar.copy(maxTotalBytes = 100))._2,
        "bytes in total"
      )
      refusedWith(
        "file bound",
        extract(tarBytes(good), Tar.copy(maxFileBytes = 10))._2,
        "larger than"
      )

      // Distribution layout (BE-P6-09 repair 02): trees extracted by the build's own tar reader,
      // with the pinned tarball's modes (load-spark-env.sh 0644, spark-submit and spark-class 0755).
      // Each refusal must be the only problem, so a failed extraction cannot pass a refusal case.
      pins.foreach { p =>
        val jarNames = Seq(
          s"scala-library-${p.distScalaLibrary}.jar",
          s"hadoop-client-api-${p.distHadoopClient}.jar",
          s"hadoop-client-runtime-${p.distHadoopClient}.jar",
          s"spark-core_2.13-${p.spark}.jar",
          s"spark-sql_2.13-${p.spark}.jar"
        )
        val pinnedModes =
          Map("spark-submit" -> 493, "spark-class" -> 493, "load-spark-env.sh" -> 420)
        def dist(bin: Map[String, Int], extra: Seq[TestEntry] = Nil): Path = {
          val es = Seq(TestEntry(s"$top/", '5', Array.emptyByteArray, 493)) ++
            bin.toSeq.sorted.map { case (n, m) => TestEntry(s"$top/bin/$n", '0', f1, m) } ++
            jarNames.map(j => TestEntry(s"$top/jars/$j", '0', f1, 420)) ++ extra
          extract(tarBytes(es))._1.resolve(top)
        }
        def layout(name: String, home: Path, refusal: Option[String]): Unit = {
          val ps = distributionProblems(home, p)
          refusal match {
            case None => expect(name, ps.isEmpty, ps.mkString("; "))
            case Some(sub) =>
              expect(
                s"$name (refused)",
                ps.size == 1 && ps.head.contains(sub),
                if (ps.isEmpty) "ALLOW" else ps.mkString("; ")
              )
          }
        }
        val env = "load-spark-env.sh"
        layout(
          "pinned tarball modes: non-executable regular load-spark-env.sh accepted",
          dist(pinnedModes),
          None
        )
        layout(
          "executable load-spark-env.sh accepted",
          dist(pinnedModes.updated(env, 493)),
          None
        )
        layout(
          "missing load-spark-env.sh",
          dist(pinnedModes - env),
          Some("bin/load-spark-env.sh is missing")
        )
        val linked = dist(pinnedModes - env)
        Files.createSymbolicLink(
          linked.resolve("bin").resolve(env),
          new File("spark-class").toPath
        )
        layout(
          "load-spark-env.sh as a symbolic link to a regular file",
          linked,
          Some("bin/load-spark-env.sh is a symbolic link")
        )
        layout(
          "load-spark-env.sh as a directory",
          dist(
            pinnedModes - env,
            Seq(TestEntry(s"$top/bin/$env/", '5', Array.emptyByteArray, 493))
          ),
          Some("bin/load-spark-env.sh is not a regular file")
        )
        val unreadable = dist(pinnedModes)
        Files.setPosixFilePermissions(
          unreadable.resolve("bin").resolve(env),
          PosixFilePermissions.fromString("-w-------")
        )
        layout(
          "unreadable load-spark-env.sh",
          unreadable,
          Some("bin/load-spark-env.sh is not readable")
        )
        layout(
          "non-executable spark-submit",
          dist(pinnedModes.updated("spark-submit", 420)),
          Some("bin/spark-submit is not executable")
        )
        layout(
          "non-executable spark-class",
          dist(pinnedModes.updated("spark-class", 420)),
          Some("bin/spark-class is not executable")
        )
        layout(
          "missing spark-submit",
          dist(pinnedModes - "spark-submit"),
          Some("bin/spark-submit is missing")
        )
        val linkedSubmit = dist(pinnedModes - "spark-submit")
        Files.createSymbolicLink(
          linkedSubmit.resolve("bin").resolve("spark-submit"),
          new File("spark-class").toPath
        )
        layout(
          "spark-submit as a symbolic link to an executable",
          linkedSubmit,
          Some("bin/spark-submit is a symbolic link")
        )
      }

      // Distribution cache.
      pins.foreach { p0 =>
        val body = "distribution".getBytes(StandardCharsets.UTF_8)
        val sha = hex(MessageDigest.getInstance("SHA-512").digest(body))
        val p = p0.copy(sha512 = sha)
        var calls = 0
        def writer(b: Array[Byte], result: Either[String, Unit] = Right(())): Fetch = q => {
          calls += 1
          Files.write(q, b, StandardOpenOption.CREATE_NEW)
          result
        }
        def cacheDir(n: String): Path = Files.createDirectory(tmp.resolve(n))
        val c1 = cacheDir("c1")
        Files.write(c1.resolve(p.file), body)
        val r1 = ensureDistribution(c1, p, writer(body), "e" * 64)
        expect(
          "cached match is used without fetching",
          r1.exists(!_.downloadedNow) && calls == 0,
          r1.toString
        )
        val c2 = cacheDir("c2")
        Files.write(c2.resolve(p.file), "other".getBytes(StandardCharsets.UTF_8))
        refusedWith("cached mismatch", ensureDistribution(c2, p, writer(body), "e" * 64), "is kept")
        expect("cached mismatch is kept", Files.isRegularFile(c2.resolve(p.file)), "")
        val c3 = cacheDir("c3")
        Files.write(c3.resolve(s"${p.file}.partial-old"), body)
        val r3 = ensureDistribution(c3, p, writer(body), "f" * 64)
        expect(
          "fresh download is verified and linked",
          r3.exists(_.downloadedNow) && Files.isRegularFile(c3.resolve(p.file)) && !Files.exists(
            c3.resolve(s"${p.file}.partial-${"f" * 16}")
          ),
          r3.toString
        )
        expect(
          "a foreign partial file is left alone",
          Files.isRegularFile(c3.resolve(s"${p.file}.partial-old")) && r3.exists(
            _.note.contains("left alone")
          ),
          r3.toString
        )
        val c4 = cacheDir("c4")
        refusedWith(
          "downloaded mismatch",
          ensureDistribution(c4, p, writer("bad".getBytes(StandardCharsets.UTF_8)), "f" * 64),
          "partial download was deleted"
        )
        expect("downloaded mismatch leaves no file", list(c4).isEmpty, list(c4).mkString(","))
        val c5 = cacheDir("c5")
        refusedWith(
          "failed download",
          ensureDistribution(c5, p, writer(body, Left("boom")), "f" * 64),
          "boom"
        )
        expect("failed download leaves no file", list(c5).isEmpty, list(c5).mkString(","))

        // CR-P6-27 n3: the final name appears while this invocation downloads.
        val own = s"${p.file}.partial-${"f" * 16}"
        def racing(make: Path => Unit): Fetch = q => {
          Files.write(q, body, StandardOpenOption.CREATE_NEW)
          make(q.getParent.resolve(p.file))
          Right(())
        }
        val c6 = cacheDir("c6")
        val r6 = ensureDistribution(
          c6,
          p,
          racing(d => { Files.write(d, body, StandardOpenOption.CREATE_NEW); () }),
          "f" * 64
        )
        expect(
          "concurrent final file with the pinned sha512 is used; only the own partial is deleted",
          r6.exists(d => d.downloadedNow && d.note.contains("appeared meanwhile")) &&
            list(c6) == Seq(p.file) &&
            java.util.Arrays.equals(Files.readAllBytes(c6.resolve(p.file)), body),
          s"$r6 ${list(c6).mkString(",")}"
        )
        val c7 = cacheDir("c7")
        val otherBytes = "other".getBytes(StandardCharsets.UTF_8)
        refusedWith(
          "concurrent final file with another sha512",
          ensureDistribution(
            c7,
            p,
            racing(d => { Files.write(d, otherBytes, StandardOpenOption.CREATE_NEW); () }),
            "f" * 64
          ),
          "appeared during the download with sha512"
        )
        expect(
          "concurrent mismatching final file and the own partial are both kept",
          list(c7) == Seq(p.file, own).sorted &&
            java.util.Arrays.equals(Files.readAllBytes(c7.resolve(p.file)), otherBytes),
          list(c7).mkString(",")
        )
        val c8 = cacheDir("c8")
        refusedWith(
          "concurrent final name as a symbolic link",
          ensureDistribution(
            c8,
            p,
            racing(d => { Files.createSymbolicLink(d, d.resolveSibling("elsewhere")); () }),
            "f" * 64
          ),
          "as a symbolic link"
        )
        expect(
          "concurrent symbolic link and the own partial are both kept",
          Files.isSymbolicLink(c8.resolve(p.file)) && Files.isRegularFile(c8.resolve(own)),
          list(c8).mkString(",")
        )
        val c9 = cacheDir("c9")
        refusedWith(
          "concurrent final name as a directory",
          ensureDistribution(c9, p, racing(d => { Files.createDirectory(d); () }), "f" * 64),
          "not a regular file"
        )
        expect(
          "concurrent directory and the own partial are both kept",
          Files.isDirectory(c9.resolve(p.file), LinkOption.NOFOLLOW_LINKS) &&
            Files.isRegularFile(c9.resolve(own)),
          list(c9).mkString(",")
        )
      }

      // Assembly content.
      val lib = tmp.resolve("scala-library.jar").toFile
      val lib3 = tmp.resolve("scala3-library.jar").toFile
      writeJar(lib, None, Seq("scala/collection/immutable/List.class", "scala/Predef.class"))
      writeJar(lib3, None, Seq("scala/runtime/LazyVals.class", "scala/deriving/Mirror.class"))
      val libSet = classNames(lib)
      val lib3Set = classNames(lib3)
      def jar(name: String, main: Option[String], entries: Seq[String]): File = {
        val f = tmp.resolve(name).toFile
        writeJar(f, main, entries)
        f
      }
      val ok = jar(
        "ok.jar",
        Some(JobMainClass),
        Seq(
          "scholarchain/spark/job/CollaborationJob.class",
          "scala/runtime/LazyVals.class",
          "upickle/default.class"
        )
      )
      val (p0, r) = assemblyProblems(ok, libSet, lib3Set, JobMainClass)
      expect(
        "clean assembly passes",
        p0.isEmpty && r.scala3Classes == 1 && r.sha256.length == 64,
        p0.mkString("; ")
      )
      def bad(name: String, main: Option[String], entries: Seq[String], sub: String): Unit = {
        val (ps, _) = assemblyProblems(jar(name, main, entries), libSet, lib3Set, JobMainClass)
        expect(s"$name (refused)", ps.exists(_.contains(sub)), ps.mkString("; "))
      }
      bad(
        "spark.jar",
        Some(JobMainClass),
        Seq("org/apache/spark/SparkContext.class"),
        "org/apache/spark/"
      )
      bad(
        "hadoop.jar",
        Some(JobMainClass),
        Seq("org/apache/hadoop/fs/FileSystem.class"),
        "org/apache/hadoop/"
      )
      bad(
        "sdk.jar",
        Some(JobMainClass),
        Seq("com/amazonaws/AmazonClientException.class"),
        "com/amazonaws/"
      )
      bad(
        "scalalib.jar",
        Some(JobMainClass),
        Seq("scala/collection/immutable/List.class"),
        "scala-library 2.13"
      )
      bad(
        "foreignscala.jar",
        Some(JobMainClass),
        Seq("scala/Other.class"),
        "not from scala3-library_3"
      )
      bad("nomain.jar", None, Seq("scholarchain/A.class"), "Main-Class")
      bad("wrongmain.jar", Some("x.Y"), Seq("scholarchain/A.class"), "Main-Class")

      // Published outputs.
      val run1 = Files.createDirectory(tmp.resolve("run-1"))
      Seq("out", "events").foreach(d => Files.createDirectory(run1.resolve(d)))
      val pub = Files.createDirectory(run1.resolve("out").resolve(s"run=${"a" * 64}"))
      Seq("_SUCCESS", "_parts.json", "_run-manifest.json").foreach(f =>
        Files.write(pub.resolve(f), Array[Byte](1))
      )
      Files.write(run1.resolve("events").resolve("local-1"), Array[Byte](1))
      expect(
        "published run accepted",
        publishedProblems(run1).isRight,
        publishedProblems(run1).toString
      )
      Files.createDirectory(run1.resolve("out").resolve(s".spark-${"a" * 64}"))
      refusedWith("scratch left", publishedProblems(run1), "scratch")
      val run2 = Files.createDirectory(tmp.resolve("run-2"))
      Seq("out", "events").foreach(d => Files.createDirectory(run2.resolve(d)))
      refusedWith("nothing published", publishedProblems(run2), "0 run=<hex>")
      Files.write(run2.resolve("events").resolve("app-1.inprogress"), Array[Byte](1))
      refusedWith("in-progress event log", publishedProblems(run2), "event log")
    } finally deleteTree(tmp)

    // Comparison verdict.
    val nonce = "9" * 64
    val runName = s"run=${"a" * 64}"
    def resultText(
        nonceTop: String = nonce,
        datasets: Int = ExpectedDatasets,
        edges: Long = ExpectedEdges,
        rightRun: String = runName,
        differ: Boolean = false,
        declared: Option[Int] = None
    ): String = {
      val sides = Seq("left", "right")
      (Seq(
        s"format=$ComparisonFormat",
        s"nonce=$nonceTop",
        s"run\tleft\t$runName",
        s"run\tright\t$rightRun"
      ) ++
        sides.flatMap(s =>
          Seq(s"count\t$s\tedges\t$edges", s"count\t$s\tmetrics\t$ExpectedMetrics")
        ) ++
        sides.flatMap(s =>
          ExpectedLoads.toSeq.sorted.map { case (k, (a, b)) => s"load\t$s\t$k\t$a\t$b" }
        ) ++
        (1 to datasets).map(i =>
          s"dataset\td$i\t5\t5\t${if (differ && i == 3) "different" else "equal"}"
        ) ++
        Seq(s"datasets=${declared.getOrElse(datasets)}", s"complete=$nonce"))
        .mkString("", "\n", "\n")
    }
    val pass = verdict(Some(0), Right(resultText()), nonce, runName)
    expect("complete comparison passes", pass.isRight, pass.fold(identity, _.toString))
    refusedWith("exit 1", verdict(Some(1), Right(resultText()), nonce, runName), "exit 1")
    refusedWith("timeout", verdict(None, Right(resultText()), nonce, runName), "bound")
    refusedWith(
      "no result",
      verdict(Some(0), Left("the comparison wrote no regular result file"), nonce, runName),
      "no regular result"
    )
    refusedWith(
      "wrong nonce",
      verdict(Some(0), Right(resultText(nonceTop = "8" * 64)), nonce, runName),
      "nonce"
    )
    refusedWith(
      "27 datasets",
      verdict(Some(0), Right(resultText(datasets = 27)), nonce, runName),
      "27 datasets"
    )
    refusedWith(
      "a different dataset",
      verdict(Some(0), Right(resultText(differ = true)), nonce, runName),
      "dataset d3 differs"
    )
    refusedWith(
      "edge count off baseline",
      verdict(Some(0), Right(resultText(edges = 258)), nonce, runName),
      "edges 258"
    )
    refusedWith(
      "other run compared",
      verdict(Some(0), Right(resultText(rightRun = s"run=${"b" * 64}")), nonce, runName),
      "not the published"
    )
    refusedWith(
      "datasets= mismatch",
      verdict(Some(0), Right(resultText(declared = Some(3))), nonce, runName),
      "datasets= line"
    )
    refusedWith(
      "truncated result",
      verdict(Some(0), Right(resultText().dropRight(1)), nonce, runName),
      "incomplete"
    )
    refusedWith(
      "unknown line",
      verdict(
        Some(0),
        Right(resultText().replace(s"run\tleft", "extra\tline\nrun\tleft")),
        nonce,
        runName
      ),
      "malformed"
    )

    if (failures.nonEmpty)
      throw new MessageOnlyException(
        s"$SelfTest: ${failures.size} of $cases case(s) failed: ${failures.mkString("; ")}"
      )
    log.info(
      s"$SelfTest: PASS: $cases cases gave the expected verdict (temporary directories only; no network, no fork)"
    )
  }
}
