import java.io.{File, IOException}
import java.nio.charset.StandardCharsets
import java.nio.file.{
  FileAlreadyExistsException,
  FileVisitOption,
  FileVisitResult,
  Files,
  LinkOption,
  Path,
  Paths,
  SimpleFileVisitor,
  StandardOpenOption
}
import java.nio.file.attribute.BasicFileAttributes

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.Try

import sbt._
import sbt.internal.util.MessageOnlyException

/**
 * Opt-in local Spark integration tests (work order BE-P6-03). Owner: build-engineer.
 *
 * `sbt sparkIT` is one fixed-shape root task without arguments. It is not part of `check`, writes
 * no test marker, and depends only on the sparkAnalytics project (core and Spark; never hadoop or
 * entityResolution). In order:
 *   1. refuses while any SCHOLARCHAIN_CFG_* variable is inherited (names only);
 *   2. reads the committed configuration [[ConfigRel]] (regular, symlink-free, bounded) and
 *      resolves the archive root as `<user.home>/<archive.root.home-relative>` (`user.home` of the
 *      sbt JVM; no user name is committed);
 *   3. refuses unless the root is an existing real directory reachable without a symbolic link,
 *      outside the repository, not containing it, and outside every sbt target directory, and no
 *      path segment names the held-out split, gold/, target/streams/ or target/local-runs/heldout;
 *   4. refuses unless the archive holds exactly the three pinned run directories along their paths
 *      (no missing or extra entry, no symbolic link; `.DS_Store` files in those path directories
 *      excepted), none with _UNVERIFIED. The decision and grouping runs need a regular _SUCCESS and
 *      _parts.json and are walked without following links (CR-P6-16 F5; names and file types only,
 *      at most [[MaxRunDepth]] levels and [[MaxRunEntries]] entries), refusing any symbolic link
 *      and any entry that is neither a regular file nor a directory. The curated run must be
 *      exactly [[PinnedCuratedRunName]] (any other curated run is refused) and is checked against
 *      the Hadoop producer contract HD-P6-01 rules 1-11 by [[curatedRunProblems]] (BE-P6-04, human
 *      decision Option A: stage `_SUCCESS` and `_counters.json`, checksum sidecars, no run-level
 *      `_SUCCESS`, no `.DS_Store` or other hidden entry, files at most 5 and directories at most 4
 *      levels deep, NOFOLLOW, bounded). No file content is read and no part checksum is verified
 *      (spark-engineer's reader does that via core);
 *   5. compiles the SparkIT configuration (modules/spark/src/it/scala) and refuses when it defines
 *      no test (zero tests is never a pass);
 *   6. repeats steps 1-4, creates a fresh `modules/spark/target/spark-it/attempt-<n>/`
 *      (create-once; sparkIT never reuses or deletes an attempt, but `sbt clean` removes
 *      modules/spark/target/ and with it every attempt folder, so integration evidence must be
 *      copied into the phase evidence after a real run), writes `run-config.properties` there,
 *      copies the runner class [[RunnerMain]] (project/SparkItJUnitRunner.java) into
 *      `attempt-<n>/runner/` with a one-time nonce file, and forks one JVM in the attempt
 *      directory: the runner on `runner/` plus the SparkIT classpath, Java 17 opens as in Test,
 *      java.io.tmpdir under the attempt, configuration through fixed `-Dscholarchain.sparkit.*`
 *      system properties only, inherited AWS_*, SCHOLARCHAIN_*, GIT_* and MUNIT_* removed (MUNIT_*
 *      for MUNIT_FLAKY_OK; the runner also refuses any MUNIT_* it still sees) and the AWS isolation
 *      variables added, stdin closed, output through the sbt task log, and a wall-clock bound of
 *      [[TimeoutSeconds]] (destroy, then destroyForcibly);
 *   7. decides only from the runner's result file, the nonce and the exit code ([[verdict]]): PASS
 *      requires exit 0 and a complete result file written by this run's runner (its first and last
 *      lines carry the nonce) in which the classes that ran equal the expected SparkIT test classes
 *      (definedTestNames, n > 0) and every class ran at least one test with zero failures, zero
 *      ignored tests and zero assumption failures. Printed output is never parsed; writes
 *      `result.txt`.
 *
 * The archive is opened read-only by convention only: the build cannot stop test code from writing
 * there. `verifySparkItGuards` (part of `check`) tests the refusals on temporary directories and
 * synthetic results, forks the runner twice without JUnit, and never reads the real archive.
 */
object SparkItRuns {

  val Task = "sparkIT"
  val SelfTest = "verifySparkItGuards"

  /** The committed configuration (owner build-engineer). */
  val ConfigRel = "project/spark-it.properties"
  val KeyRoot = "archive.root.home-relative"
  val KeyCurated = "run.curated"
  val KeyDecisions = "run.decisions"
  val KeyAssignments = "run.assignments"
  val ConfigKeys: Seq[String] = Seq(KeyRoot, KeyCurated, KeyDecisions, KeyAssignments)
  val MaxConfigBytes: Long = 64L * 1024L

  /**
   * Build-envelope bound for the forked test JVM, not data policy: dev scale (374 candidates, 90
   * works, 286 mentions) in a local SparkSession (start-up about 5-10 s per JVM). 120 s leaves room
   * for several suites; with sbt start-up and the SparkIT compile the whole call stays well inside
   * the 600 s maximum of one agent tool call.
   */
  val TimeoutSeconds: Long = 120L

  /** The runner main (project/SparkItJUnitRunner.java), copied into `attempt-<n>/runner/`. */
  val RunnerMain = "SparkItJUnitRunner"
  val RunnerDir = "runner"
  val NonceFile = "nonce"
  val JUnitResultFile = "junit-result.txt"
  val MaxResultBytes: Long = 1024L * 1024L

  /** Inherited variables removed from the forked JVM (by name prefix). */
  val StrippedPrefixes: Seq[String] = Seq("AWS_", "SCHOLARCHAIN_", "GIT_", "MUNIT_")
  val ConfigOverridePrefix = "SCHOLARCHAIN_CFG_"

  /** True when an inherited variable of this name is removed from the forked JVM. */
  def strippedFromChild(name: String): Boolean = StrippedPrefixes.exists(name.startsWith)

  /** Bounds of the NOFOLLOW walk of each pinned run directory (names and file types only). */
  val MaxRunDepth: Int = 4
  val MaxRunEntries: Int = 20000

  /**
   * BE-P6-04, human decision Option A (docs/agent-runs/phase-06/prompt-curated-compat-option-a.md):
   * the one Hadoop curated dev run to which the producer contract HD-P6-01 rules 1-11
   * (docs/agent-runs/phase-06/hd-p6-01-report.md) is applied instead of the top-level _SUCCESS and
   * depth-4 rules. Any other curated run ID is refused outright: the exception is not generalized.
   */
  val PinnedCuratedRunHex: String =
    "2881724b5c86b561a235836d51946fbd884beed36a14f929b27c278cb5de8baa"
  val PinnedCuratedRunName: String = s"run=$PinnedCuratedRunHex"

  /** HD-P6-01 rule 9: below the curated run directory, files at most 5, directories at most 4. */
  val CuratedMaxFileDepth: Int = 5
  val CuratedMaxDirDepth: Int = 4
  val CuratedMaxEntries: Int = MaxRunEntries

  val ManifestFile = "_run-manifest.json"
  val CountersFile = "_counters.json"

  /** HD-P6-01 rule 3: the five stage directories (Pipeline.scala Stage, in order). */
  val CuratedStages: Seq[String] = Seq("normalize", "exact-ids", "teams", "blocking", "candidates")

  /** HD-P6-01 rule 5: round-0 .. round-(k-1) with 1 <= k <= 4 (BlockingKeys.Levels = 4). */
  val MaxBlockingRounds: Int = 4

  val SparkTargetRel = "modules/spark/target"
  val OutputRootRel = s"$SparkTargetRel/spark-it"
  val RunConfigFile = "run-config.properties"
  val ResultFile = "result.txt"

  /**
   * Every SparkIT key that would compile and run the suites outside `sparkIT` (CR-P6-16 F3). Each
   * is overridden in build.sbt to [[refuseSbtRoute]]; `verifySparkItGuards` checks the wiring in
   * the loaded build. `testFull` is not a key in sbt 1.11.7 (the compiler reports "not found: value
   * testFull"); [[RunCapableKeys]] still lists it, so the self-test fails if a later sbt defines it
   * in SparkIT without a refusal.
   */
  val RefusedRoutes: Seq[String] = Seq(
    "test",
    "testOnly",
    "testQuick",
    "executeTests",
    "run",
    "runMain",
    "bgRun",
    "bgRunMain",
    "fgRun",
    "fgRunMain",
    "console",
    "consoleQuick",
    "consoleProject"
  )
  val RunCapableKeys: Seq[String] = RefusedRoutes :+ "testFull"

  /** The format check step of `check` for modules/spark/src/it/scala (CR-P6-16 F2). */
  val FormatCheckStep = "sparkAnalytics/SparkIT/scalafmtCheck"

  val SuccessMarker = "_SUCCESS"
  val UnverifiedMarker = "_UNVERIFIED"
  val PartsFile = "_parts.json"
  val DsStore = ".DS_Store"

  /** The fixed system properties of the forked JVM: the only configuration it receives. */
  object Prop {
    val Prefix = "scholarchain.sparkit."
    val ArchiveRoot: String = Prefix + "archiveRoot"
    val CuratedRun: String = Prefix + "curatedRun"
    val DecisionRun: String = Prefix + "decisionRun"
    val GroupingRun: String = Prefix + "groupingRun"
    val AttemptDir: String = Prefix + "attemptDir"
    val OutputDir: String = Prefix + "outputDir"
    val RunConfig: String = Prefix + "runConfig"
    val All: Seq[String] =
      Seq(ArchiveRoot, CuratedRun, DecisionRun, GroupingRun, AttemptDir, OutputDir, RunConfig)
  }

  /** One pinned run directory: its key, label, required parent, marker rule and property. */
  final case class RunSpec(
      key: String,
      label: String,
      parentText: String,
      parent: scala.util.matching.Regex,
      needsParts: Boolean,
      prop: String
  )

  val CuratedSpec = RunSpec(
    KeyCurated,
    "Hadoop curated dev input",
    "modules/hadoop/target/local-pipeline/er-candidates/dev/attempt-<n>/curated/hadoop/snapshot=<s>",
    ("^modules/hadoop/target/local-pipeline/er-candidates/dev/attempt-[1-9][0-9]*/curated/hadoop/" +
      "snapshot=[A-Za-z0-9._-]+$").r,
    needsParts = false,
    Prop.CuratedRun
  )
  val DecisionSpec = RunSpec(
    KeyDecisions,
    "ER decision run",
    "target/local-runs/dev/er-decisions",
    "^target/local-runs/dev/er-decisions$".r,
    needsParts = true,
    Prop.DecisionRun
  )
  val GroupingSpec = RunSpec(
    KeyAssignments,
    "ER grouping run",
    "target/local-runs/dev/er-assignments",
    "^target/local-runs/dev/er-assignments$".r,
    needsParts = true,
    Prop.GroupingRun
  )
  val RunSpecs: Seq[RunSpec] = Seq(CuratedSpec, DecisionSpec, GroupingSpec)

  /** The parsed configuration: the home-relative root and the three archive-relative runs. */
  final case class ItConfig(rootRel: String, runs: Seq[(RunSpec, String)])

  private val RunName = "^run=[0-9a-f]{64}$".r
  private val SegmentChars = "^[A-Za-z0-9._=+@:-]+$".r
  private val AttemptName = "^attempt-([1-9][0-9]*)$".r
  private val ClassLine =
    "^class\t([^\t]+)\trun=([0-9]+)\tfailures=([0-9]+)\tignored=([0-9]+)\tassumptionFailures=([0-9]+)$".r
  private val ErrorLine = "^error\t([^\t]+)\t([^\t]+)$".r
  private val ClassesLine = "^classes=([0-9]+)$".r
  private val NonceText = "^[0-9a-f]{64}$".r
  private val PartName = "^part-([mr])-[0-9]{5,}\\.gz$".r
  private val CrcName = "^\\.(.+)\\.crc$".r
  private val RoundName = "^round-(0|[1-9][0-9]*)$".r
  private val IncompleteMarkers: Set[String] = Set("_temporary", "_IN_PROGRESS", "_FAILED")
  private val StagingPrefix = ".staging"

  private def refuse(message: String): Nothing =
    throw new MessageOnlyException(s"$Task: refused: $message")

  private def fail(message: String): Nothing =
    throw new MessageOnlyException(s"$Task: $message")

  private def selfFail(message: String): Nothing =
    throw new MessageOnlyException(s"$SelfTest: $message")

  private def lower(s: String): String = s.toLowerCase(java.util.Locale.ROOT)

  // --- Pure checks ------------------------------------------------------------------------

  /** SCHOLARCHAIN_CFG_* refusal (names only). */
  def configOverrideProblem(inherited: Map[String, String]): Option[String] = {
    val names = inherited.keys.filter(_.startsWith(ConfigOverridePrefix)).toVector.sorted
    if (names.isEmpty) None
    else
      Some(
        s"${names.mkString(", ")} set in sbt's environment (names only are checked); sparkIT " +
          s"takes its configuration only from $ConfigRel; unset them and start sbt again"
      )
  }

  /**
   * Segment rules for every configured path, compared case-insensitively: no segment names the
   * held-out split (any spelling, LocalRuns.namesHeldout); no `gold` segment; no `target`,
   * `streams` pair; no `target`, `local-runs`, held-out triple.
   */
  def segmentProblems(what: String, segs: Seq[String]): Seq[String] = {
    val p = ListBuffer.empty[String]
    val low = segs.map(lower)
    if (segs.exists(LocalRuns.namesHeldout)) p += s"$what names the held-out split"
    if (low.contains("gold")) p += s"$what has a gold/ segment"
    if (low.sliding(2).exists(_ == Seq("target", "streams")))
      p += s"$what has a target/streams/ segment pair"
    if (
      low
        .sliding(3)
        .exists(w =>
          w.size == 3 && w(0) == "target" && w(1) == "local-runs" && LocalRuns.namesHeldout(w(2))
        )
    )
      p += s"$what has a target/local-runs/heldout segment triple"
    p.toList
  }

  /** A plain relative path of safe segments, plus [[segmentProblems]]. */
  def relPathProblems(what: String, value: String): Seq[String] = {
    val segs = value.split("/", -1).toSeq
    val shape =
      if (
        value.isEmpty || value.startsWith("/") || value.startsWith("~") ||
        segs.exists(s => s.isEmpty || s == "." || s == "..") ||
        !segs.forall(s => SegmentChars.pattern.matcher(s).matches)
      )
        Seq(
          s"$what '$value' is not a plain relative path (segments of A-Z a-z 0-9 . _ = + @ : -; " +
            "no empty, . or .. segment; no ~, backslash or space)"
        )
      else Nil
    shape ++ segmentProblems(s"$what '$value'", segs)
  }

  /**
   * BE-P6-04: the curated run must be exactly [[PinnedCuratedRunName]] (HD-P6-01 rule 1, pinned).
   * Checked when the configuration is parsed and again in [[layoutProblems]].
   */
  def curatedPinProblem(curatedRel: String): Option[String] = {
    val name = curatedRel.split('/').last
    if (name == PinnedCuratedRunName) None
    else
      Some(
        s"$KeyCurated '$curatedRel' is not the pinned Hadoop curated dev run $PinnedCuratedRunName; " +
          "the HD-P6-01 curated-run contract (human decision Option A, BE-P6-04) applies to that " +
          "one run only and no other curated run is accepted"
      )
  }

  /** Parses the configuration text: every key exactly once, no other key, every value checked. */
  def parseConfig(text: String): Either[String, ItConfig] = {
    val problems = ListBuffer.empty[String]
    val seen = mutable.LinkedHashMap.empty[String, String]
    text.split("\n", -1).zipWithIndex.foreach { case (raw, i) =>
      val line = raw.trim
      if (line.nonEmpty && !line.startsWith("#")) {
        val eq = line.indexOf('=')
        if (eq <= 0) problems += s"line ${i + 1} is not key=value"
        else {
          val k = line.take(eq).trim
          val v = line.drop(eq + 1).trim
          if (!ConfigKeys.contains(k)) problems += s"line ${i + 1}: unknown key '$k'"
          else if (seen.contains(k)) problems += s"line ${i + 1}: duplicate key '$k'"
          else seen(k) = v
        }
      }
    }
    ConfigKeys.filterNot(seen.contains).foreach(k => problems += s"missing key '$k'")
    if (problems.nonEmpty) Left(problems.mkString("; "))
    else {
      val root = seen(KeyRoot)
      problems ++= relPathProblems(KeyRoot, root)
      val runs = RunSpecs.map { s =>
        val v = seen(s.key)
        problems ++= relPathProblems(s.key, v)
        val j = v.lastIndexOf('/')
        val (parent, name) = if (j < 0) ("", v) else (v.take(j), v.drop(j + 1))
        if (!RunName.pattern.matcher(name).matches)
          problems += s"${s.key} '$v' does not end in run=<64 lower-case hex>"
        else if (s.key == KeyCurated) problems ++= curatedPinProblem(v)
        if (!s.parent.pattern.matcher(parent).matches)
          problems += s"${s.key} '$v' is not under ${s.parentText}/"
        (s, v)
      }
      if (problems.nonEmpty) Left(problems.mkString("; ")) else Right(ItConfig(root, runs))
    }
  }

  /** The configuration file of `repo` (real path): regular, symlink-free, bounded, then parsed. */
  def loadConfig(repo: Path): Either[String, ItConfig] = {
    val dir = repo.resolve("project")
    val f = repo.resolve(ConfigRel)
    if (Files.isSymbolicLink(dir) || Files.isSymbolicLink(f))
      Left(s"$ConfigRel resolves through a symbolic link")
    else if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS))
      Left(s"$ConfigRel is missing or not a regular file")
    else if (Files.size(f) > MaxConfigBytes)
      Left(s"$ConfigRel is larger than $MaxConfigBytes bytes")
    else
      parseConfig(new String(Files.readAllBytes(f), StandardCharsets.UTF_8)).left
        .map(m => s"$ConfigRel: $m")
  }

  /** `<home>/<rootRel>`, normalized; refuses a relative home and any forbidden segment. */
  def resolveRoot(home: File, cfg: ItConfig): Either[String, Path] = {
    val h = home.toPath
    if (!h.isAbsolute) Left(s"user.home '$home' is not an absolute path")
    else {
      val root = h.resolve(cfg.rootRel).normalize
      val p = segmentProblems(s"archive root $root", root.iterator.asScala.map(_.toString).toSeq)
      if (p.nonEmpty) Left(p.mkString("; ")) else Right(root)
    }
  }

  private def canonical(p: Path): Path =
    Try(p.toRealPath()).getOrElse(p.toAbsolutePath.normalize)

  /**
   * The root must exist, be reachable without a symbolic link (its real path equals itself), be a
   * directory, lie outside the repository (`repo`, a real path) without containing it, and lie
   * outside every given sbt target directory.
   */
  def locationProblem(root: Path, repo: Path, targets: Seq[Path]): Option[String] =
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS))
      Some(s"archive root $root does not exist")
    else {
      val real = Try(root.toRealPath()).toOption
      if (Files.isSymbolicLink(root) || !real.contains(root))
        Some(
          s"archive root $root is reachable only through a symbolic link or a differently cased " +
            s"path (real path ${real.fold("unreadable")(_.toString)})"
        )
      else if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
        Some(s"archive root $root is not a directory")
      else if (root.startsWith(repo))
        Some(s"archive root $root is inside the repository $repo")
      else if (repo.startsWith(root))
        Some(s"archive root $root contains the repository $repo")
      else
        targets
          .map(canonical)
          .find(t => root.startsWith(t))
          .map(t => s"archive root $root is inside the sbt target directory $t")
    }

  private def listNames(dir: Path): Either[String, Seq[String]] =
    Try {
      val s = Files.list(dir)
      try s.iterator.asScala.map(_.getFileName.toString).toList.sorted
      finally s.close()
    }.toEither.left.map(e => s"cannot list $dir: ${e.getMessage}")

  /**
   * The exact-layout check: along the three configured paths every directory holds exactly the
   * expected children (a regular `.DS_Store` file is ignored in these path directories, never
   * inside the curated run), each a real directory (no symbolic link). The decision and grouping
   * runs have a regular _SUCCESS, a regular _parts.json and no _UNVERIFIED, and are walked by
   * [[runTreeProblems]] (depth [[MaxRunDepth]]; unchanged by BE-P6-04). The curated run must be the
   * pinned run ([[curatedPinProblem]]), holds no _UNVERIFIED, and is checked by
   * [[curatedRunProblems]] (HD-P6-01 rules 1-11; no top-level _SUCCESS is required, and one is
   * refused). Reads no file content.
   */
  def layoutProblems(root: Path, cfg: ItConfig): Seq[String] = {
    val children: Map[String, Set[String]] = cfg.runs
      .flatMap { case (_, r) =>
        val segs = r.split('/').toSeq
        segs.indices.map(i => segs.take(i).mkString("/") -> segs(i))
      }
      .groupBy(_._1)
      .map { case (k, v) => k -> v.map(_._2).toSet }
    def depth(k: String): Int = if (k.isEmpty) 0 else k.count(_ == '/') + 1
    val good = mutable.Set("")
    val p = ListBuffer.empty[String]
    children.keys.toSeq.sortBy(k => (depth(k), k)).foreach { parent =>
      if (good(parent)) {
        val dir = if (parent.isEmpty) root else root.resolve(parent)
        val shown = if (parent.isEmpty) "<archive root>" else parent
        listNames(dir) match {
          case Left(m) => p += m
          case Right(names) =>
            val actual: Set[String] = names
              .filterNot(n =>
                n == DsStore && Files.isRegularFile(dir.resolve(n), LinkOption.NOFOLLOW_LINKS)
              )
              .toSet[String]
            val expected: Set[String] = children(parent)
            (expected -- actual).toSeq.sorted.foreach(c => p += s"$shown/ lacks $c")
            (actual -- expected).toSeq.sorted
              .foreach(c => p += s"$shown/ holds unexpected entry $c")
            (expected & actual).toSeq.sorted.foreach { c =>
              val q = dir.resolve(c)
              val rel = if (parent.isEmpty) c else s"$parent/$c"
              if (Files.isSymbolicLink(q)) p += s"$rel is a symbolic link"
              else if (!Files.isDirectory(q, LinkOption.NOFOLLOW_LINKS))
                p += s"$rel is not a directory"
              else good += rel
              ()
            }
        }
      }
    }
    cfg.runs.foreach { case (s, r) =>
      if (good(r)) {
        val d = root.resolve(r)
        if (s.key == KeyCurated) {
          curatedPinProblem(r) match {
            case Some(m) => p += m
            case None =>
              if (Files.exists(d.resolve(UnverifiedMarker), LinkOption.NOFOLLOW_LINKS))
                p += s"$r (${s.label}) holds $UnverifiedMarker"
              p ++= curatedRunProblems(d, r, CuratedMaxEntries)
          }
        } else {
          if (!Files.isRegularFile(d.resolve(SuccessMarker), LinkOption.NOFOLLOW_LINKS))
            p += s"$r (${s.label}) has no regular $SuccessMarker"
          if (Files.exists(d.resolve(UnverifiedMarker), LinkOption.NOFOLLOW_LINKS))
            p += s"$r (${s.label}) holds $UnverifiedMarker"
          if (s.needsParts && !Files.isRegularFile(d.resolve(PartsFile), LinkOption.NOFOLLOW_LINKS))
            p += s"$r (${s.label}) has no regular $PartsFile"
          p ++= runTreeProblems(d, r, MaxRunDepth, MaxRunEntries)
        }
      }
    }
    p.toList
  }

  /**
   * CR-P6-16 F5: walks one run directory without following any symbolic link (names and file types
   * only; no content is read) and refuses every symbolic link (the link is reported by its own path
   * in the archive; its target is never resolved or shown), every entry that is neither a regular
   * file nor a directory (FIFO, socket, device), every entry more than `maxDepth` levels below the
   * run directory, and more than `maxEntries` entries (the walk stops there). Reports at most 20.
   */
  def runTreeProblems(runDir: Path, shown: String, maxDepth: Int, maxEntries: Int): Seq[String] = {
    val p = ListBuffer.empty[String]
    var entries = 0
    def rel(q: Path): String = s"$shown/${runDir.relativize(q)}"
    def withinBound(): Boolean = {
      entries += 1
      if (entries <= maxEntries) true
      else {
        p += s"$shown holds more than $maxEntries entries (bound); the walk stopped there"
        false
      }
    }
    try {
      Files.walkFileTree(
        runDir,
        java.util.EnumSet.noneOf(classOf[FileVisitOption]),
        maxDepth + 1,
        new SimpleFileVisitor[Path] {
          override def preVisitDirectory(d: Path, a: BasicFileAttributes): FileVisitResult =
            if (d == runDir) FileVisitResult.CONTINUE
            else if (withinBound()) FileVisitResult.CONTINUE
            else FileVisitResult.TERMINATE
          override def visitFile(f: Path, a: BasicFileAttributes): FileVisitResult =
            if (!withinBound()) FileVisitResult.TERMINATE
            else {
              if (runDir.relativize(f).getNameCount > maxDepth)
                p += s"${rel(f)} is more than $maxDepth levels below the run directory (bound)"
              else if (a.isSymbolicLink) p += s"${rel(f)} is a symbolic link (not followed)"
              else if (!a.isRegularFile && !a.isDirectory)
                p += s"${rel(f)} is a special file (neither a regular file nor a directory)"
              FileVisitResult.CONTINUE
            }
          override def visitFileFailed(f: Path, e: IOException): FileVisitResult = {
            p += s"${rel(f)} cannot be examined (${e.getClass.getSimpleName})"
            FileVisitResult.CONTINUE
          }
          override def postVisitDirectory(d: Path, e: IOException): FileVisitResult = {
            if (e != null) p += s"${rel(d)} cannot be listed (${e.getClass.getSimpleName})"
            FileVisitResult.CONTINUE
          }
        }
      )
      ()
    } catch {
      case e: IOException => p += s"$shown cannot be walked (${e.getClass.getSimpleName})"
    }
    if (p.size <= 20) p.toList else p.take(20).toList :+ s"... and ${p.size - 20} more"
  }

  /** One entry of the curated walk: names, file types and sizes only (no content). */
  private sealed trait CEntry
  private final case class CFile(size: Long) extends CEntry
  private case object CDir extends CEntry

  /** A symbolic link, special file or too-deep directory: already refused by the walk. */
  private case object COther extends CEntry

  /**
   * BE-P6-04: the Hadoop curated dev run check, applied only to [[PinnedCuratedRunName]] (the pin
   * itself is [[curatedPinProblem]]). It replaces, for this run only, the top-level _SUCCESS rule
   * and the depth-4 walk, with the producer contract HD-P6-01 rules 1-11
   * (docs/agent-runs/phase-06/hd-p6-01-report.md; producer modules/hadoop Pipeline.scala RunLayout
   * and runStage, RejectRecord.scala NamedOutputs). Names, file types and sizes only: NO file
   * content is read, so every content rule is left to spark-engineer's reader.
   *
   * The walk does not follow symbolic links and refuses every symbolic link and special file, every
   * file more than [[CuratedMaxFileDepth]] and every directory more than [[CuratedMaxDirDepth]]
   * levels below the run directory, and more than `maxEntries` entries (the walk stops there and no
   * layout is judged). Rule-to-check mapping (S = enforced here, structurally; R = left to the
   * reader):
   *   1. S: run directory `run=<64 hex>` under `snapshot=<token>/` under `hadoop/`, not a symbolic
   *      link ([[parseConfig]], [[layoutProblems]]), and equal to the pinned run.
   *   2. S: `_run-manifest.json` is a non-empty regular file with its crc. R: strict decoding,
   *      `stage == "hadoop.pipeline"`, `RunId.of(manifest)` equals the directory, and the
   *      manifest's dblp snapshot equals the `snapshot=` token.
   *   3. S: the run directory holds exactly `_run-manifest.json`, its crc and the five stage
   *      directories of [[CuratedStages]]; `_SUCCESS` there is refused.
   *   4. S: each stage holds a regular `_SUCCESS` of size 0 and a non-empty regular
   *      `_counters.json`, their crcs, and only its listed subdirectories (normalize: works,
   *      mentions, profiles, venues, institutions, topics, rejects; exact-ids: links, rejects,
   *      stats; teams: teams, pairs, rejects, stats; blocking: round-<r>; candidates: join, pairs,
   *      final; round-<r>: blocks/{researcher,work}, members/{researcher,work}, spill, rejects,
   *      stats; join: joined, rejects; pairs: pairs, rejects, stats; final: researcher, work,
   *      rejects, stats). R: that `_counters.json` is valid JSON naming the stage and the ten
   *      required counters.
   *   5. S: blocking holds round-0 .. round-(k-1), contiguous, 1 <= k <= [[MaxBlockingRounds]].
   *   6. S: candidates/join is present; candidates/pairs only if join/joined holds a part file;
   *      candidates/final only if pairs/pairs holds a part file.
   *   7. S: leaves hold only non-empty regular `part-[mr]-[0-9]{5,}.gz` files, `part-m` only in a
   *      `rejects` leaf. R: that each part decompresses and decodes, and any checksum verification.
   *   8. S: the only hidden entry is `.<X>.crc` (a regular file) for a regular, non-hidden sibling
   *      X, and every regular non-hidden file has one; `.DS_Store`, `._*` and every other hidden
   *      entry are refused. R: that a crc matches its file.
   *   9. S: files at most 5 and directories at most 4 levels below the run directory, at most
   *      `maxEntries` entries.
   *   10. S: `_temporary`, `_IN_PROGRESS`, `_FAILED` and `.staging*` are refused at any level, and
   *       `_SUCCESS` outside a stage directory; symbolic links and special files are refused.
   *   11. S: an empty leaf (no part file) or an empty blocks/ or members/ directory is refused; an
   *       empty job output directory (round-<r>, join, pairs, final) is accepted.
   *   12. Not checked: the archive holds the run directory only, not its attempt directory (the
   *       attempt-level success is recorded separately in prompt-curated-compat-option-a.md).
   * Reports at most 20 problems.
   */
  def curatedRunProblems(runDir: Path, shown: String, maxEntries: Int): Seq[String] = {
    val p = ListBuffer.empty[String]
    val entries = mutable.LinkedHashMap.empty[Vector[String], CEntry]
    var count = 0
    var truncated = false
    def segs(q: Path): Vector[String] =
      runDir.relativize(q).iterator.asScala.map(_.toString).toVector
    def rel(q: Path): String = s"$shown/${runDir.relativize(q)}"
    def withinBound(): Boolean = {
      count += 1
      if (count <= maxEntries) true
      else {
        truncated = true
        p += s"$shown holds more than $maxEntries entries (bound); the walk stopped there"
        false
      }
    }
    def dirTooDeep(q: Path): String =
      s"${rel(q)} is a directory more than $CuratedMaxDirDepth levels below the run directory " +
        "(curated bound, HD-P6-01 rule 9)"
    try {
      Files.walkFileTree(
        runDir,
        java.util.EnumSet.noneOf(classOf[FileVisitOption]),
        CuratedMaxFileDepth + 1,
        new SimpleFileVisitor[Path] {
          override def preVisitDirectory(d: Path, a: BasicFileAttributes): FileVisitResult =
            if (d == runDir) FileVisitResult.CONTINUE
            else if (!withinBound()) FileVisitResult.TERMINATE
            else {
              val s = segs(d)
              if (s.size > CuratedMaxDirDepth) {
                p += dirTooDeep(d)
                entries(s) = COther
              } else entries(s) = CDir
              FileVisitResult.CONTINUE
            }
          override def visitFile(f: Path, a: BasicFileAttributes): FileVisitResult =
            if (!withinBound()) FileVisitResult.TERMINATE
            else {
              val s = segs(f)
              if (a.isSymbolicLink) {
                p += s"${rel(f)} is a symbolic link (not followed)"
                entries(s) = COther
              } else if (a.isDirectory) {
                p += dirTooDeep(f)
                entries(s) = COther
              } else if (!a.isRegularFile) {
                p += s"${rel(f)} is a special file (neither a regular file nor a directory)"
                entries(s) = COther
              } else {
                if (s.size > CuratedMaxFileDepth)
                  p += s"${rel(f)} is more than $CuratedMaxFileDepth levels below the run " +
                    "directory (curated bound, HD-P6-01 rule 9)"
                entries(s) = CFile(a.size)
              }
              FileVisitResult.CONTINUE
            }
          override def visitFileFailed(f: Path, e: IOException): FileVisitResult = {
            p += s"${rel(f)} cannot be examined (${e.getClass.getSimpleName})"
            entries(segs(f)) = COther
            FileVisitResult.CONTINUE
          }
          override def postVisitDirectory(d: Path, e: IOException): FileVisitResult = {
            if (e != null) p += s"${rel(d)} cannot be listed (${e.getClass.getSimpleName})"
            FileVisitResult.CONTINUE
          }
        }
      )
      ()
    } catch {
      case e: IOException =>
        truncated = true
        p += s"$shown cannot be walked (${e.getClass.getSimpleName})"
    }
    if (!truncated) p ++= curatedLayoutProblems(shown, entries.toMap)
    if (p.size <= 20) p.toList else p.take(20).toList :+ s"... and ${p.size - 20} more"
  }

  /** HD-P6-01 rules 2-8, 10 and 11 on the walked entries (see [[curatedRunProblems]]). */
  private def curatedLayoutProblems(
      shown: String,
      entries: Map[Vector[String], CEntry]
  ): Seq[String] = {
    val p = ListBuffer.empty[String]
    val kids: Map[Vector[String], Map[String, CEntry]] =
      entries.toSeq.groupBy(_._1.init).map { case (k, v) =>
        k -> v.map { case (s, e) => s.last -> e }.toMap
      }
    def at(d: Vector[String]): Map[String, CEntry] = kids.getOrElse(d, Map.empty)
    def show(s: Vector[String]): String = if (s.isEmpty) shown else s"$shown/${s.mkString("/")}"
    def isPart(n: String, e: CEntry): Boolean = e match {
      case CFile(sz) => sz > 0 && PartName.pattern.matcher(n).matches
      case _ => false
    }
    def hasPart(d: Vector[String]): Boolean = at(d).exists { case (n, e) => isPart(n, e) }

    // Rules 8 and 10 on every judged directory; returns the ordinary entries (not hidden, not a
    // rule-10 marker, not a misplaced _SUCCESS).
    def screen(d: Vector[String], stage: Boolean): Map[String, CEntry] = {
      val ch = at(d)
      val regular: Set[String] =
        ch.collect { case (n, CFile(_)) if !n.startsWith(".") => n }.toSet
      ch.toSeq.sortBy(_._1).foreach { case (n, e) =>
        val q = show(d :+ n)
        if (IncompleteMarkers(n) || n.startsWith(StagingPrefix))
          p += s"$q marks incomplete or failed output (HD-P6-01 rule 10)"
        else if (n == SuccessMarker && !stage) {
          if (d.isEmpty)
            p += s"$q: the Hadoop producer never writes a run-level $SuccessMarker; a run " +
              "directory holding one is refused (HD-P6-01 rules 3, 10)"
          else p += s"$q is a $SuccessMarker outside a stage directory (HD-P6-01 rule 10)"
        } else if (n == DsStore)
          p += s"$q is a $DsStore file (refused in the curated run, HD-P6-01 rule 8)"
        else if (n.startsWith("."))
          n match {
            case CrcName(x) if !x.startsWith(".") && regular(x) =>
              e match {
                case CDir => p += s"$q is a checksum sidecar that is not a regular file"
                case _ => ()
              }
            case CrcName(x) =>
              p += s"$q is a checksum sidecar without a regular sibling $x (HD-P6-01 rule 8)"
            case _ =>
              p += s"$q is a hidden entry other than a .<name>.crc checksum sidecar " +
                "(HD-P6-01 rule 8)"
          }
      }
      regular.toSeq.sorted.foreach { x =>
        ch.get(s".$x.crc") match {
          case Some(CFile(_)) | Some(COther) => ()
          case _ => p += s"${show(d :+ x)} has no checksum sidecar .$x.crc (HD-P6-01 rule 8)"
        }
      }
      ch.filter { case (n, _) =>
        !n.startsWith(".") && !IncompleteMarkers(n) && !(n == SuccessMarker && !stage)
      }
    }

    def dirsOnly(
        d: Vector[String],
        o: Map[String, CEntry],
        allowed: Map[String, Vector[String] => Unit]
    ): Unit =
      o.toSeq.sortBy(_._1).foreach { case (n, e) =>
        val q = show(d :+ n)
        allowed.get(n) match {
          case None =>
            if (e != COther) p += s"$q is an unexpected entry (HD-P6-01 rules 3, 4)"
          case Some(f) =>
            e match {
              case CDir => f(d :+ n)
              case COther => ()
              case _ => p += s"$q is not a directory (HD-P6-01 rule 4)"
            }
        }
      }

    def leaf(allowM: Boolean)(d: Vector[String]): Unit = {
      val o = screen(d, stage = false)
      if (!hasPart(d))
        p += s"${show(d)} holds no part file (an empty leaf output directory, HD-P6-01 rule 11)"
      o.toSeq.sortBy(_._1).foreach { case (n, e) =>
        val q = show(d :+ n)
        (n, e) match {
          case (_, COther) => ()
          case (_, CDir) =>
            p += s"$q is a directory inside a leaf output directory (HD-P6-01 rules 4, 7)"
          case (PartName(kind), CFile(sz)) =>
            if (kind == "m" && !allowM)
              p += s"$q is a map-side part-m file outside a rejects directory (HD-P6-01 rule 7)"
            if (sz <= 0) p += s"$q is an empty part file (HD-P6-01 rule 7)"
          case _ => p += s"$q is not a part-[mr]-NNNNN.gz file (HD-P6-01 rule 7)"
        }
      }
    }
    def leaves(names: String*): Map[String, Vector[String] => Unit] =
      names.map(n => n -> (leaf(n == NamedRejects) _)).toMap

    def entityParent(d: Vector[String]): Unit = {
      val o = screen(d, stage = false)
      if (o.isEmpty)
        p += s"${show(d)} is an empty output directory without researcher/ or work/ " +
          "(HD-P6-01 rule 11)"
      dirsOnly(d, o, leaves("researcher", "work"))
    }

    def jobOut(allowed: Map[String, Vector[String] => Unit])(d: Vector[String]): Unit =
      dirsOnly(d, screen(d, stage = false), allowed)

    val roundDir = jobOut(
      Map[String, Vector[String] => Unit](
        "blocks" -> (entityParent _),
        "members" -> (entityParent _)
      ) ++ leaves("spill", NamedRejects, "stats")
    ) _

    def stageDir(allowed: Map[String, Vector[String] => Unit])(d: Vector[String]): Unit = {
      val o = screen(d, stage = true)
      o.get(SuccessMarker) match {
        case None =>
          p += s"${show(d)}/$SuccessMarker is missing (the stage is not committed; HD-P6-01 " +
            "rules 4, 10)"
        case Some(CFile(0L)) | Some(COther) => ()
        case Some(CFile(sz)) =>
          p += s"${show(d)}/$SuccessMarker is not empty ($sz bytes; HD-P6-01 rule 4)"
        case Some(CDir) => p += s"${show(d)}/$SuccessMarker is not a regular file (HD-P6-01 rule 4)"
      }
      o.get(CountersFile) match {
        case None => p += s"${show(d)}/$CountersFile is missing (HD-P6-01 rule 4)"
        case Some(CFile(0L)) => p += s"${show(d)}/$CountersFile is empty (HD-P6-01 rule 4)"
        case Some(CFile(_)) | Some(COther) => ()
        case Some(CDir) => p += s"${show(d)}/$CountersFile is not a regular file (HD-P6-01 rule 4)"
      }
      dirsOnly(d, o - SuccessMarker - CountersFile, allowed)
    }

    // Rule 5: contiguous blocking rounds.
    def blocking(d: Vector[String]): Unit = {
      val rounds = at(d).keys.toSeq.collect { case n @ RoundName(i) => (n, i.toInt) }
      val idx = rounds.map(_._2).sorted
      if (idx.isEmpty) p += s"${show(d)} holds no round-0 directory (HD-P6-01 rule 5)"
      else if (idx != idx.indices)
        p += s"${show(d)} round directories are not contiguous from round-0 (found " +
          s"${idx.map(i => s"round-$i").mkString(", ")}; HD-P6-01 rule 5)"
      idx
        .filter(_ >= MaxBlockingRounds)
        .foreach(i =>
          p += s"${show(d :+ s"round-$i")} is beyond the last blocking level (round-0 to " +
            s"round-${MaxBlockingRounds - 1}; HD-P6-01 rule 5)"
        )
      stageDir(rounds.map { case (n, _) => n -> roundDir }.toMap)(d)
    }

    // Rule 6: the candidate jobs.
    def candidates(d: Vector[String]): Unit = {
      val ch = at(d)
      if (!ch.contains("join"))
        p += s"${show(d)} lacks join/ (the join job always runs; HD-P6-01 rule 6)"
      if (ch.contains("pairs") && !hasPart(d ++ Vector("join", "joined")))
        p += s"${show(d)}/pairs exists although join/joined holds no part file (HD-P6-01 rule 6)"
      if (ch.contains("final") && !hasPart(d ++ Vector("pairs", "pairs")))
        p += s"${show(d)}/final exists although pairs/pairs holds no part file (HD-P6-01 rule 6)"
      stageDir(
        Map(
          "join" -> jobOut(leaves("joined", NamedRejects)) _,
          "pairs" -> jobOut(leaves("pairs", NamedRejects, "stats")) _,
          "final" -> jobOut(leaves("researcher", "work", NamedRejects, "stats")) _
        )
      )(d)
    }

    val stages: Map[String, Vector[String] => Unit] = Map(
      "normalize" -> stageDir(
        leaves("works", "mentions", "profiles", "venues", "institutions", "topics", NamedRejects)
      ) _,
      "exact-ids" -> stageDir(leaves("links", NamedRejects, "stats")) _,
      "teams" -> stageDir(leaves("teams", "pairs", NamedRejects, "stats")) _,
      "blocking" -> (blocking _),
      "candidates" -> (candidates _)
    )

    // Rules 2 and 3: the run directory itself.
    val top = screen(Vector.empty, stage = false)
    top.get(ManifestFile) match {
      case None => p += s"$shown lacks $ManifestFile (HD-P6-01 rules 2, 3)"
      case Some(CFile(0L)) => p += s"$shown/$ManifestFile is empty (HD-P6-01 rule 2)"
      case Some(CFile(_)) | Some(COther) => ()
      case Some(CDir) => p += s"$shown/$ManifestFile is not a regular file (HD-P6-01 rule 2)"
    }
    CuratedStages
      .filterNot(top.contains)
      .foreach(s => p += s"$shown lacks the stage directory $s/ (HD-P6-01 rule 3)")
    dirsOnly(Vector.empty, top - ManifestFile, stages)
    p.toList
  }

  /** The `rejects` named output (RejectRecord.scala NamedOutputs.Rejects): part-m is allowed. */
  private val NamedRejects = "rejects"

  /** The Spark build target must be exactly `<build base>/modules/spark/target`. */
  def outputTargetProblem(buildBase: File, sparkTarget: File): Option[String] = {
    val want = buildBase.getAbsoluteFile.toPath.normalize.resolve(SparkTargetRel)
    val got = sparkTarget.getAbsoluteFile.toPath.normalize
    if (got == want) None
    else
      Some(
        s"the Spark build target is $got, not $want; sparkIT writes only under $OutputRootRel/"
      )
  }

  /** Creates `<repo>/modules/spark/target/spark-it/` after refusing a link on any component. */
  def outputRoot(repo: Path): Either[String, Path] = {
    val segs = OutputRootRel.split('/').toSeq
    val bad = (1 to segs.length).map(n => segs.take(n).mkString("/")).find { r =>
      val q = repo.resolve(r)
      Files.isSymbolicLink(q) ||
      (Files
        .exists(q, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(q, LinkOption.NOFOLLOW_LINKS))
    }
    bad match {
      case Some(r) => Left(s"$r is a symbolic link or not a directory; sparkIT writes nothing")
      case None =>
        val out = repo.resolve(OutputRootRel)
        Files.createDirectories(out)
        if (out.toRealPath() != out) Left(s"$OutputRootRel resolves through a symbolic link")
        else Right(out)
    }
  }

  /**
   * Creates `attempt-<n>` once; refuses an existing one. sparkIT never reuses or deletes an
   * attempt; `sbt clean` removes modules/spark/target/ and with it every attempt folder (CR-P6-16
   * F6), so integration evidence is copied into the phase evidence after a real run.
   */
  def createAttempt(out: Path, n: Int): Either[String, Path] =
    try Right(Files.createDirectory(out.resolve(s"attempt-$n")))
    catch {
      case _: FileAlreadyExistsException =>
        Left(
          s"$OutputRootRel/attempt-$n already exists; attempts are create-once and sparkIT never " +
            "reuses or deletes one (sbt clean can remove attempt folders)"
        )
    }

  /** The next attempt: one more than every `attempt-<n>` name present; refuses symbolic links. */
  def nextAttempt(out: Path): Either[String, Path] =
    listNames(out).flatMap { names =>
      val links = names.filter(n => Files.isSymbolicLink(out.resolve(n)))
      if (links.nonEmpty)
        Left(
          s"$OutputRootRel/ holds symbolic link(s) ${links.mkString(", ")}; sparkIT writes nothing"
        )
      else {
        val used = names.flatMap {
          case AttemptName(k) => Try(k.toInt).toOption.toList
          case _ => Nil
        }
        createAttempt(out, if (used.isEmpty) 1 else used.max + 1)
      }
    }

  /** Zero tests is never a pass: refuses when the SparkIT configuration defines no test class. */
  def testsProblem(testNames: Seq[String]): Option[String] =
    if (testNames.nonEmpty) None
    else
      Some(
        "no integration test is defined in modules/spark/src/it/scala (SparkIT configuration); " +
          "nothing ran, no attempt directory was created, and this is NOT a pass"
      )

  /** The forked JVM's options: Java 17 opens, scratch under the attempt, the fixed properties. */
  def jvmOptions(java17: Seq[String], attempt: Path, root: Path, cfg: ItConfig): Seq[String] =
    java17 ++ Seq(
      s"-Djava.io.tmpdir=${attempt.resolve("tmp")}",
      s"-XX:ErrorFile=${attempt.resolve("hs_err_pid%p.log")}",
      s"-D${Prop.ArchiveRoot}=$root"
    ) ++ cfg.runs.map { case (s, r) => s"-D${s.prop}=${root.resolve(r)}" } ++ Seq(
      s"-D${Prop.AttemptDir}=$attempt",
      s"-D${Prop.OutputDir}=${attempt.resolve("out")}",
      s"-D${Prop.RunConfig}=${attempt.resolve(RunConfigFile)}"
    )

  // --- Runner, nonce, result file and verdict (CR-P6-16 pass-rule hardening) -------------------

  /** A fresh one-time nonce: 32 bytes of SecureRandom, 64 lower-case hex. */
  def newNonce(): String = {
    val b = new Array[Byte](32)
    new java.security.SecureRandom().nextBytes(b)
    b.map(x => f"${x & 0xff}%02x").mkString
  }

  /**
   * The runner's one class file, read from the meta-build classes. Refused when the class has a
   * nested or anonymous class (only this one file is copied) or is not a class file.
   */
  def runnerClassBytes(): Either[String, Array[Byte]] = {
    val c = classOf[SparkItJUnitRunner]
    val extra = (c.getDeclaredClasses.map(_.getName) ++
      Seq(s"$RunnerMain$$1").filter(n => c.getResource(s"/$n.class") != null)).toSeq
    if (c.getName != RunnerMain) Left(s"the runner class is ${c.getName}, not $RunnerMain")
    else if (extra.nonEmpty)
      Left(
        s"$RunnerMain has nested class(es) ${extra.mkString(", ")}; only one class file is copied"
      )
    else {
      val in = c.getResourceAsStream(s"/$RunnerMain.class")
      if (in == null) Left(s"the meta-build class file $RunnerMain.class cannot be found")
      else {
        val bytes =
          try in.readAllBytes()
          finally in.close()
        val magic = bytes.length > 4 && (bytes(0) & 0xff) == 0xca && (bytes(1) & 0xff) == 0xfe &&
          (bytes(2) & 0xff) == 0xba && (bytes(3) & 0xff) == 0xbe
        if (magic) Right(bytes) else Left(s"$RunnerMain.class is not a class file")
      }
    }
  }

  /**
   * Creates `<attempt>/runner/` with the runner class file and the nonce file (both create-new).
   */
  def installRunner(attempt: Path, nonce: String): Either[String, Path] =
    runnerClassBytes().map { bytes =>
      val dir = Files.createDirectory(attempt.resolve(RunnerDir))
      Files.write(dir.resolve(s"$RunnerMain.class"), bytes, StandardOpenOption.CREATE_NEW)
      Files.write(
        dir.resolve(NonceFile),
        nonce.getBytes(StandardCharsets.US_ASCII),
        StandardOpenOption.CREATE_NEW
      )
      dir
    }

  /** The runner's arguments: nonce file, result file, then the expected classes. */
  def runnerArgs(attempt: Path, tests: Seq[String]): Seq[String] =
    Seq(
      attempt.resolve(RunnerDir).resolve(NonceFile).toString,
      attempt.resolve(JUnitResultFile).toString
    ) ++ tests

  /** One class's counts as the runner recorded them. */
  final case class ClassResult(
      name: String,
      run: Int,
      failures: Int,
      ignored: Int,
      assumptionFailures: Int
  )

  /** The parsed result file: per class either counts or an error (class name, exception name). */
  final case class RunnerResult(classes: Seq[ClassResult], errors: Seq[(String, String)]) {
    def names: Seq[String] = classes.map(_.name) ++ errors.map(_._1)
  }

  /** The result file, when it is a regular, symlink-free file within [[MaxResultBytes]]. */
  def readResult(attempt: Path): Either[String, String] = {
    val f = attempt.resolve(JUnitResultFile)
    if (!Files.exists(f, LinkOption.NOFOLLOW_LINKS))
      Left(
        s"the runner wrote no $JUnitResultFile (a test may have called System.exit, the JVM may " +
          "have been stopped, or the runner refused before running)"
      )
    else if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS))
      Left(s"$JUnitResultFile is not a regular file")
    else if (Files.size(f) > MaxResultBytes)
      Left(s"$JUnitResultFile is larger than $MaxResultBytes bytes")
    else Right(new String(Files.readAllBytes(f), StandardCharsets.UTF_8))
  }

  private def nat(s: String): Option[Int] = Try(s.toInt).toOption.filter(_ >= 0)

  /**
   * Strict parse: `format=<FORMAT>`, `nonce=<nonce>`, one `class` or `error` line per class,
   * `classes=<count of those lines>`, `complete=<nonce>`, and a final LF; nothing else.
   */
  def parseResult(text: String, nonce: String): Either[String, RunnerResult] = {
    val lines = text.split("\n", -1).toVector
    val format = s"format=${SparkItJUnitRunner.FORMAT}"
    if (!text.endsWith("\n") || lines.size < 5)
      Left(s"$JUnitResultFile is incomplete (no final line); NOT a pass")
    else if (lines(0) != format) Left(s"$JUnitResultFile does not start with $format")
    else if (lines(1) != s"nonce=$nonce" || lines(lines.size - 2) != s"complete=$nonce")
      Left(
        s"$JUnitResultFile does not carry this run's nonce on its first and last lines: it was not " +
          "written completely by this run's runner; NOT a pass"
      )
    else {
      val body = lines.slice(2, lines.size - 3)
      val classes = ListBuffer.empty[ClassResult]
      val errors = ListBuffer.empty[(String, String)]
      val bad = ListBuffer.empty[Int]
      body.zipWithIndex.foreach { case (l, i) =>
        l match {
          case ClassLine(n, r, f, ig, a) =>
            (nat(r), nat(f), nat(ig), nat(a)) match {
              case (Some(r1), Some(f1), Some(i1), Some(a1)) =>
                classes += ClassResult(n, r1, f1, i1, a1)
              case _ => bad += i + 3
            }
          case ErrorLine(n, e) => errors += (n -> e)
          case _ => bad += i + 3
        }
      }
      val declared = lines(lines.size - 3) match {
        case ClassesLine(k) => nat(k)
        case _ => None
      }
      if (bad.nonEmpty) Left(s"$JUnitResultFile has malformed line(s) ${bad.mkString(", ")}")
      else if (!declared.contains(body.size))
        Left(s"$JUnitResultFile: the classes= line does not match the ${body.size} class line(s)")
      else Right(RunnerResult(classes.toList, errors.toList))
    }
  }

  /**
   * The PASS rule. PASS (the total test count) only when: the JVM exited 0 within the bound; the
   * runner consumed the nonce file; the result file is complete and carries this run's nonce; the
   * classes that ran equal the expected SparkIT classes (n > 0; none missing, extra or repeated);
   * no class has an error line; and every class ran at least one test with zero failures, zero
   * ignored tests and zero assumption failures. Everything else is a failure that names every
   * problem found.
   */
  def verdict(
      exit: Option[Int],
      nonceConsumed: Boolean,
      result: Either[String, String],
      expected: Seq[String],
      nonce: String
  ): Either[String, Int] = {
    val want = expected.distinct.sorted
    val exitText = exit.fold("no exit code")(n => s"exit $n")
    if (exit.isEmpty)
      Left(s"exceeded the ${TimeoutSeconds}s bound; the forked JVM was stopped; NOT a pass")
    else if (want.isEmpty) Left("no expected test class; zero tests is NOT a pass")
    else {
      val p = ListBuffer.empty[String]
      if (!exit.contains(SparkItJUnitRunner.EXIT_PASS)) p += s"the runner JVM ended with $exitText"
      if (!nonceConsumed)
        p += "the runner did not consume its nonce file (it never started its run)"
      result.flatMap(parseResult(_, nonce)) match {
        case Left(m) => p += m
        case Right(r) =>
          val names = r.names
          val missing = want.filterNot(names.contains)
          val extra = names.distinct.filterNot(want.contains)
          val repeated = names.diff(names.distinct).distinct
          if (missing.nonEmpty)
            p += s"expected class(es) that did not run: ${missing.mkString(", ")}"
          if (extra.nonEmpty) p += s"unexpected class(es) in the result: ${extra.mkString(", ")}"
          if (repeated.nonEmpty) p += s"class(es) recorded twice: ${repeated.mkString(", ")}"
          r.errors.foreach { case (n, e) => p += s"$n could not be run ($e)" }
          r.classes.foreach { c =>
            if (c.run <= 0) p += s"${c.name} ran no test"
            if (c.failures > 0) p += s"${c.name} had ${c.failures} failure(s)"
            if (c.ignored > 0) p += s"${c.name} had ${c.ignored} ignored test(s)"
            if (c.assumptionFailures > 0)
              p += s"${c.name} had ${c.assumptionFailures} assumption failure(s) (skipped)"
          }
          if (p.isEmpty) return Right(r.classes.map(_.run).sum)
      }
      Left(s"FAILED ($exitText): ${p.mkString("; ")}; NOT a pass")
    }
  }

  // --- Preflight and run ------------------------------------------------------------------

  final case class Preflight(repo: Path, root: Path, cfg: ItConfig)

  private def targetsFor(repo: Path, projectTargets: Seq[File]): Seq[Path] =
    projectTargets.map(_.toPath) ++
      Seq(repo.resolve("project/target"), repo.resolve("project/project/target"))

  /** Steps 1-4 and the Spark-target check; refuses with the first failing check. */
  def preflight(
      buildBase: File,
      home: Option[String],
      inherited: Map[String, String],
      projectTargets: Seq[File],
      sparkTarget: File,
      log: Logger
  ): Preflight = {
    configOverrideProblem(inherited).foreach(refuse)
    val repo = buildBase.toPath.toRealPath()
    val cfg = orRefuse(loadConfig(repo))
    val homeDir =
      home.filter(_.nonEmpty).getOrElse(refuse("the user.home system property is unset"))
    val root = orRefuse(resolveRoot(new File(homeDir), cfg))
    locationProblem(root, repo, targetsFor(repo, projectTargets)).foreach(refuse)
    val layout = layoutProblems(root, cfg)
    if (layout.nonEmpty)
      refuse(
        s"archive $root does not hold exactly the three pinned run directories of $ConfigRel: " +
          layout.mkString("; ")
      )
    outputTargetProblem(buildBase, sparkTarget).foreach(refuse)
    log.info(
      s"$Task: archive root $root (<user.home>/${cfg.rootRel}, from $ConfigRel): a real directory " +
        "outside the repository and every sbt target directory, holding exactly the pinned runs " +
        cfg.runs.map { case (s, r) => s"${s.label} ${r.split('/').last}" }.mkString(", ") +
        s" (decision and grouping runs with $SuccessMarker and $PartsFile; the curated run per " +
        "the HD-P6-01 contract, BE-P6-04; none with " + s"$UnverifiedMarker) " +
        "(layout only; the archive is opened read-only by convention, which the build cannot enforce)"
    )
    Preflight(repo, root, cfg)
  }

  private def orRefuse[A](e: Either[String, A]): A = e match {
    case Right(a) => a
    case Left(m) => refuse(m)
  }

  /** The part before compiling: build.sbt runs it before the SparkIT classpath is built. */
  def beforeCompile(
      buildBase: File,
      home: Option[String],
      inherited: Map[String, String],
      projectTargets: Seq[File],
      sparkTarget: File,
      log: Logger
  ): Unit = {
    log.info(s"$Task: configuration and archive checks before compiling")
    preflight(buildBase, home, inherited, projectTargets, sparkTarget, log)
    ()
  }

  /** After compiling: zero-test refusal, the checks again, a fresh attempt, the bounded fork. */
  def sparkIT(
      buildBase: File,
      home: Option[String],
      inherited: Map[String, String],
      projectTargets: Seq[File],
      sparkTarget: File,
      javaHome: Option[File],
      classpath: Seq[File],
      testNames: Seq[String],
      java17: Seq[String],
      isolation: Map[String, String],
      log: Logger
  ): Unit = {
    val tests = testNames.distinct.sorted
    testsProblem(tests).foreach(fail)
    val p = preflight(buildBase, home, inherited, projectTargets, sparkTarget, log)
    val out = orRefuse(outputRoot(p.repo))
    val attempt = orRefuse(nextAttempt(out))
    val attemptRel = p.repo.relativize(attempt).toString
    Files.createDirectory(attempt.resolve("tmp"))
    Files.createDirectory(attempt.resolve("out"))
    val jvm = jvmOptions(java17, attempt, p.root, p.cfg)
    val record =
      "# Generated by sbt sparkIT (build-engineer, BE-P6-03) for this attempt only; never committed.\n" +
        "# The archive is opened read-only by convention; the build cannot enforce read-only access.\n" +
        jvm.filter(_.startsWith("-D" + Prop.Prefix)).map(_.drop(2)).mkString("\n") + "\n" +
        s"timeoutSeconds=$TimeoutSeconds\ntestClasses=${tests.mkString(",")}\n"
    Files.write(
      attempt.resolve(RunConfigFile),
      record.getBytes(StandardCharsets.UTF_8),
      StandardOpenOption.CREATE_NEW
    )
    if (inherited.keys.exists(strippedFromChild))
      log.info(s"$Task: inherited ${StrippedPrefixes.mkString("*, ")}* variables are not passed on")
    val nonce = newNonce()
    val runnerDir = orFail(installRunner(attempt, nonce))
    val nonceFile = runnerDir.resolve(NonceFile)
    val exit = LocalRuns.runForked(
      Task,
      attempt.toFile,
      javaHome,
      runnerDir.toFile +: classpath,
      jvm,
      isolation,
      RunnerMain,
      runnerArgs(attempt, tests),
      TimeoutSeconds,
      LocalRuns.ToLog(log),
      log,
      StrippedPrefixes
    )
    val consumed = !Files.exists(nonceFile, LinkOption.NOFOLLOW_LINKS)
    val read = readResult(attempt)
    val v = verdict(exit, consumed, read, tests, nonce)
    val evidence = read.toOption.flatMap(parseResult(_, nonce).toOption)
    val perClass = evidence.toList.flatMap { r =>
      r.classes.map(c =>
        s"class=${c.name} run=${c.run} failures=${c.failures} ignored=${c.ignored} " +
          s"assumptionFailures=${c.assumptionFailures}"
      ) ++ r.errors.map { case (n, e) => s"class=${n} error=$e" }
    }
    val result = v.fold(m => s"status=fail\nreason=$m\n", n => s"status=pass\ntests=$n\n") +
      s"exit=${exit.fold("none")(_.toString)}\nexpectedClasses=${tests.size}\n" +
      s"evidence=$JUnitResultFile\n" + perClass.map(_ + "\n").mkString
    Files.write(
      attempt.resolve(ResultFile),
      result.getBytes(StandardCharsets.UTF_8),
      StandardOpenOption.CREATE_NEW
    )
    v match {
      case Right(n) =>
        log.info(
          s"$Task: PASS: $n tests in ${tests.size} class(es), every expected class ran, no failure, " +
            s"ignored test or assumption failure (exit 0); evidence $attemptRel/$JUnitResultFile " +
            "and result.txt; copy them into the phase evidence (sbt clean can remove attempt folders)"
        )
      case Left(m) => fail(s"$m; attempt $attemptRel/ kept; sparkIT deleted nothing")
    }
  }

  private def orFail[A](e: Either[String, A]): A = e match {
    case Right(a) => a
    case Left(m) => fail(m)
  }

  /** Every SparkIT route of [[RefusedRoutes]] (build.sbt): refused, naming `sbt sparkIT`. */
  def refuseSbtRoute(name: String): Nothing =
    throw new MessageOnlyException(
      s"sparkAnalytics/SparkIT/$name is disabled: integration tests run only through the bounded, " +
        s"fixed-shape `sbt $Task` (work order BE-P6-03)"
    )

  /** An input-task route of [[RefusedRoutes]]: accepts any arguments, then refuses. */
  def refusedInput[T](name: String): Def.Initialize[InputTask[T]] = Def.inputTask[T] {
    Def.spaceDelimited("<args>").parsed
    refuseSbtRoute(name)
  }

  // --- Wiring checks on the loaded build (CR-P6-16 F2, F3, F4) ------------------------------

  private def isSparkItScope(s: Scope, ref: ProjectRef): Boolean =
    s.project == Select(ref) && s.config == Select(ConfigKey(SparkItConfigName)) &&
      s.task == Zero && s.extra == Zero

  /** The configuration name of SparkIT (`config("sparkit")` in build.sbt). */
  val SparkItConfigName = "sparkit"

  private def positionOf(pos: sbt.internal.util.SourcePosition): Option[(String, Int, Int)] =
    pos match {
      case sbt.internal.util.LinePosition(path, line) => Some((path, line, line))
      case sbt.internal.util.RangePosition(path, range) => Some((path, range.start, range.end))
      case _ => None
    }

  /**
   * For each refused route: the effective (last) setting of `sparkAnalytics / SparkIT / <route>` in
   * the loaded build must come from build.sbt, on lines that call `refuseSbtRoute("<route>")` or
   * `refusedInput[...]("<route>")`. Also every key of [[RunCapableKeys]] defined in SparkIT must be
   * refused. Nothing is run.
   */
  def routeWiringProblems(
      settings: Seq[Def.Setting[_]],
      definedInSparkIt: Set[String],
      ref: ProjectRef,
      buildSbtLines: IndexedSeq[String],
      routes: Seq[String]
  ): Seq[String] = {
    val p = ListBuffer.empty[String]
    RunCapableKeys
      .filter(definedInSparkIt)
      .filterNot(routes.contains)
      .foreach(k => p += s"SparkIT defines $k, which is not refused")
    routes.foreach { r =>
      val last = settings.filter(s => s.key.key.label == r && isSparkItScope(s.key.scope, ref))
      last.lastOption match {
        case None => p += s"no setting defines sparkAnalytics/SparkIT/$r"
        case Some(s) =>
          positionOf(s.pos) match {
            case Some((path, a, b)) if path.endsWith("build.sbt") =>
              val text = (a to b).flatMap(i => buildSbtLines.lift(i - 1)).mkString("\n")
              val calls = text.contains(s"""refuseSbtRoute("$r")""") ||
                (text.contains("refusedInput[") && text.contains(s"""]("$r")"""))
              if (!calls)
                p += s"sparkAnalytics/SparkIT/$r is defined at build.sbt:$a-$b, not by a refusal"
            case other =>
              p += s"sparkAnalytics/SparkIT/$r is defined at ${other.fold(s.pos.toString)(_._1)}, " +
                "not by a refusal in build.sbt"
          }
      }
    }
    p.toList
  }

  /**
   * F2: `sparkAnalytics/SparkIT/scalafmtCheck` is defined in SparkIT's own scope (the plugin's
   * per-configuration settings, sbt-scalafmt 2.5.5 `scalafmtConfigSettings(configuration)`), reads
   * SparkIT's `unmanagedSources`, whose directories hold modules/spark/src/it/scala; and `check`
   * runs it before `Test/compile`.
   */
  def formatGateProblems(
      settings: Seq[Def.Setting[_]],
      ref: ProjectRef,
      sparkItSourceDirs: Option[Seq[File]],
      itScala: File,
      checkSteps: Seq[String]
  ): Seq[String] = {
    val p = ListBuffer.empty[String]
    val defs =
      settings.filter(s => s.key.key.label == "scalafmtCheck" && isSparkItScope(s.key.scope, ref))
    defs.lastOption match {
      case None => p += "sparkAnalytics/SparkIT/scalafmtCheck is not defined in SparkIT's scope"
      case Some(s) =>
        val reads = s.dependencies.filter(d =>
          d.key.label == "unmanagedSources" && d.scope.config == Select(
            ConfigKey(SparkItConfigName)
          )
        )
        if (reads.isEmpty)
          p += "sparkAnalytics/SparkIT/scalafmtCheck does not read SparkIT unmanagedSources " +
            s"(it reads ${s.dependencies.map(_.key.label).distinct.sorted.mkString(", ")})"
    }
    val want = itScala.getAbsoluteFile.toPath.normalize
    if (!sparkItSourceDirs.exists(_.exists(_.getAbsoluteFile.toPath.normalize == want)))
      p += s"SparkIT unmanagedSourceDirectories ${sparkItSourceDirs.fold("undefined")(_.mkString(", "))} " +
        s"do not hold $want"
    val i = checkSteps.indexOf(FormatCheckStep)
    val c = checkSteps.indexOf("Test/compile")
    if (i < 0) p += s"`check` does not run $FormatCheckStep"
    else if (c >= 0 && i > c) p += s"`check` runs $FormatCheckStep after Test/compile"
    p.toList
  }

  // --- Self-test (part of `sbt check`) -------------------------------------------------------

  private def deleteTree(p: Path): Unit =
    if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
      // walkFileTree does not follow symbolic links: a link is deleted, never its target.
      Files.walkFileTree(
        p,
        new SimpleFileVisitor[Path] {
          override def visitFile(f: Path, a: BasicFileAttributes): FileVisitResult = {
            Files.delete(f); FileVisitResult.CONTINUE
          }
          override def postVisitDirectory(d: Path, e: IOException): FileVisitResult = {
            Files.delete(d); FileVisitResult.CONTINUE
          }
        }
      )
      ()
    }

  /**
   * `verifySparkItGuards`: the refusals of sparkIT on constants, synthetic text and results, and
   * temporary directories in the system temp directory (never the real repository's outputs and
   * never the real archive); the SparkIT route and format-gate wiring of the loaded build (read
   * from its settings, nothing is run); and two forks of the runner class on a temporary directory
   * without JUnit. Last, a read-only report (not enforced) of the committed configuration and of
   * whether the configured archive root exists as a directory (one stat; no listing, no content).
   */
  def guardSelfTest(
      buildBase: File,
      home: Option[String],
      sparkDeps: Seq[String],
      checkSteps: Seq[String],
      java17: Seq[String],
      structure: sbt.internal.BuildStructure,
      sparkRef: ProjectRef,
      javaHome: Option[File],
      log: Logger
  ): Unit = {
    val failures = ListBuffer.empty[String]
    var cases = 0
    def expect(name: String, ok: Boolean, got: => String): Unit = {
      cases += 1
      if (ok) log.info(s"$SelfTest: case '$name': as expected")
      else failures += s"case '$name': got $got"
    }
    def refused(name: String, got: Option[String], subs: String*): Unit =
      expect(s"$name (refused)", got.exists(g => subs.forall(g.contains)), got.getOrElse("ALLOW"))
    def allowed(name: String, got: Option[String]): Unit =
      expect(s"$name (allowed)", got.isEmpty, got.getOrElse(""))
    def problems(ps: Seq[String]): Option[String] =
      if (ps.isEmpty) None else Some(ps.mkString("; "))

    // Fixed shape.
    expect("sparkAnalytics depends on core only", sparkDeps == Seq("core"), sparkDeps.mkString(","))
    expect(
      s"`check` runs $SelfTest and never $Task",
      checkSteps.contains(SelfTest) && !checkSteps.contains(Task),
      checkSteps.mkString(" ; ")
    )
    expect("task name names no held-out split", !LocalRuns.namesHeldout(Task), Task)
    expect(
      "AWS_*, SCHOLARCHAIN_* and GIT_* are stripped from the forked JVM",
      Seq("AWS_", "SCHOLARCHAIN_", "GIT_").forall(StrippedPrefixes.contains),
      StrippedPrefixes.mkString(",")
    )
    val munitNames = Seq("MUNIT_FLAKY_OK", "MUNIT_ANY_OTHER")
    expect(
      "MUNIT_FLAKY_OK and every other MUNIT_* are stripped from the forked JVM",
      munitNames.forall(strippedFromChild) && !strippedFromChild("PATH") &&
        !strippedFromChild("JAVA_HOME"),
      munitNames.map(n => s"$n=${strippedFromChild(n)}").mkString(",")
    )
    expect(
      s"`check` runs $FormatCheckStep before Test/compile",
      checkSteps.contains(FormatCheckStep) &&
        checkSteps.indexOf(FormatCheckStep) < checkSteps.indexOf("Test/compile"),
      checkSteps.mkString(" ; ")
    )

    // Loaded-build wiring (F2, F3, F4): read from the settings of this build; nothing is run.
    val itScope = Scope(Select(sparkRef), Select(ConfigKey(SparkItConfigName)), Zero, Zero)
    val definedInIt = structure.data.keys(itScope).map(_.label)
    val buildSbt = buildBase.toPath.resolve("build.sbt")
    val buildSbtLines =
      Try(Files.readAllLines(buildSbt, StandardCharsets.UTF_8).asScala.toVector)
        .getOrElse(Vector.empty)
    allowed(
      s"every SparkIT route (${RefusedRoutes.mkString(", ")}) is wired to a refusal in build.sbt",
      problems(
        routeWiringProblems(structure.settings, definedInIt, sparkRef, buildSbtLines, RefusedRoutes)
      )
    )
    val withoutTest = buildSbtLines.map(l =>
      if (l.contains("""refuseSbtRoute("test")""")) l.replace("refuseSbtRoute", "noRefusal") else l
    )
    refused(
      "route wiring counter-test: build.sbt text without the SparkIT/test refusal",
      problems(
        routeWiringProblems(structure.settings, definedInIt, sparkRef, withoutTest, RefusedRoutes)
      ),
      "sparkAnalytics/SparkIT/test is defined at build.sbt:",
      "not by a refusal"
    )
    refused(
      "route wiring counter-test: a run-capable key defined in SparkIT but not refused",
      problems(
        routeWiringProblems(
          structure.settings,
          definedInIt,
          sparkRef,
          buildSbtLines,
          RefusedRoutes.filterNot(_ == "executeTests")
        )
      ),
      "SparkIT defines executeTests, which is not refused"
    )
    val itScala = buildBase.toPath.resolve("modules/spark/src/it/scala").toFile
    val itDirs =
      structure.data.get(itScope, Keys.unmanagedSourceDirectories.key)
    allowed(
      s"$FormatCheckStep is defined in SparkIT, reads SparkIT unmanagedSources over " +
        "modules/spark/src/it/scala, and runs in `check` before Test/compile",
      problems(formatGateProblems(structure.settings, sparkRef, itDirs, itScala, checkSteps))
    )
    refused(
      "format gate counter-test: checkSteps without the SparkIT format step",
      problems(
        formatGateProblems(
          structure.settings,
          sparkRef,
          itDirs,
          itScala,
          checkSteps.filterNot(_ == FormatCheckStep)
        )
      ),
      s"`check` does not run $FormatCheckStep"
    )
    expect(
      "the time bound is positive and below one agent tool call (600 s)",
      TimeoutSeconds > 0 && TimeoutSeconds < 600,
      TimeoutSeconds.toString
    )
    expect(
      "the output root is under modules/spark/target/ and passes the dev path rules",
      OutputRootRel.startsWith(SparkTargetRel + "/") &&
        LocalRuns.devPathProblems(OutputRootRel).isEmpty,
      OutputRootRel
    )

    // Environment.
    refused(
      "SCHOLARCHAIN_CFG_* inherited",
      configOverrideProblem(Map("SCHOLARCHAIN_CFG_SPARK_X" -> "1", "PATH" -> "/bin")),
      "SCHOLARCHAIN_CFG_SPARK_X set in sbt's environment"
    )
    allowed(
      "SCHOLARCHAIN_CONFIG_FILES and AWS_PROFILE only (stripped, not refused)",
      configOverrideProblem(Map("SCHOLARCHAIN_CONFIG_FILES" -> "x", "AWS_PROFILE" -> "y"))
    )

    // Configuration text.
    // The fixture curated run carries the pinned run ID (BE-P6-04); hexOther is any other ID.
    val hexA = PinnedCuratedRunHex
    val hexOther = "f" * 64
    val hexB = "b" * 64
    val hexC = "c" * 64
    val curated =
      s"modules/hadoop/target/local-pipeline/er-candidates/dev/attempt-1/curated/hadoop/snapshot=dblp-x/run=$hexA"
    val decisions = s"target/local-runs/dev/er-decisions/run=$hexB"
    val assignments = s"target/local-runs/dev/er-assignments/run=$hexC"
    def cfgText(
        root: String = "arch/phase/dev",
        cur: String = curated,
        dec: String = decisions,
        asg: String = assignments,
        extra: String = ""
    ): String =
      s"# fixture\n\n$KeyRoot=$root\n$KeyCurated=$cur\n$KeyDecisions=$dec\n$KeyAssignments=$asg\n$extra"
    def parsed(text: String): Option[String] = parseConfig(text).left.toOption
    allowed("fixture configuration parses", parsed(cfgText()))
    Seq(
      ("ScholarChain-archive/heldout-v1", "names the held-out split"),
      ("arch/Held_Out/x", "names the held-out split"),
      ("arch/gold/x", "has a gold/ segment"),
      ("arch/GOLD", "has a gold/ segment"),
      ("arch/target/streams/x", "target/streams/ segment pair"),
      ("arch/TARGET/local-runs/heldout", "target/local-runs/heldout segment triple"),
      ("/Users/someone/arch", "not a plain relative path"),
      ("../arch", "not a plain relative path"),
      ("arch/./dev", "not a plain relative path"),
      ("arch//dev", "not a plain relative path"),
      ("~/arch", "not a plain relative path"),
      ("arch\\dev", "not a plain relative path"),
      ("arch dev", "not a plain relative path"),
      ("", "not a plain relative path")
    ).foreach { case (v, sub) =>
      refused(s"archive root value '$v'", parsed(cfgText(root = v)), sub)
    }
    Seq(
      (KeyDecisions, "target/local-runs/dev/er-decisions/run=XYZ", "does not end in run=<64"),
      (KeyDecisions, s"target/local-runs/heldout/er-decisions/run=$hexB", "names the held-out"),
      (KeyDecisions, s"gold/er-decisions/run=$hexB", "has a gold/ segment"),
      (KeyDecisions, s"target/streams/er-decisions/run=$hexB", "target/streams/ segment pair"),
      (
        KeyCurated,
        s"modules/hadoop/target/local-pipeline/er-candidates/heldout/attempt-1/curated/hadoop/snapshot=s/run=$hexA",
        "names the held-out split"
      ),
      (
        KeyCurated,
        s"modules/hadoop/target/local-pipeline/er-candidates/dev/attempt-1/curated/run=$hexA",
        "is not under modules/hadoop/target/local-pipeline"
      ),
      (
        KeyAssignments,
        s"target/local-runs/dev/er-decisions/run=$hexC",
        "is not under target/local-runs/dev/er-assignments/"
      )
    ).foreach { case (k, v, sub) =>
      val text = k match {
        case KeyCurated => cfgText(cur = v)
        case KeyDecisions => cfgText(dec = v)
        case _ => cfgText(asg = v)
      }
      refused(s"$k value '$v'", parsed(text), sub)
    }
    refused(
      "missing key",
      parsed(cfgText().split('\n').filterNot(_.startsWith(KeyDecisions)).mkString("\n")),
      s"missing key '$KeyDecisions'"
    )
    refused(
      "unknown key",
      parsed(cfgText(extra = "archive.root=/x\n")),
      "unknown key 'archive.root'"
    )
    refused(
      "duplicate key",
      parsed(cfgText(extra = s"$KeyRoot=arch/other\n")),
      s"duplicate key '$KeyRoot'"
    )
    refused("line without =", parsed(cfgText(extra = "just words\n")), "is not key=value")
    val curatedOther = curated.replace(s"run=$hexA", s"run=$hexOther")
    refused(
      "configuration naming a curated run other than the pinned run",
      parsed(cfgText(cur = curatedOther)),
      s"is not the pinned Hadoop curated dev run $PinnedCuratedRunName"
    )
    val fixtureCfg = parseConfig(cfgText()) match {
      case Right(c) => c
      case Left(m) => selfFail(s"FAIL: fixture configuration: $m")
    }
    refused(
      "user.home is relative",
      resolveRoot(new File("relative-home"), fixtureCfg).left.toOption,
      "is not an absolute path"
    )
    refused(
      "user.home names the held-out split",
      resolveRoot(new File("/fixture/held-out/home"), fixtureCfg).left.toOption,
      "names the held-out split"
    )

    // Verdicts (the pass rule) on synthetic result files, and zero tests.
    val nonce = "1" * 64
    val suiteA = "scholarchain.spark.it.ASuite"
    val suiteB = "scholarchain.spark.it.BSuite"
    val suiteC = "scholarchain.spark.it.CSuite"
    val expectedSuites = Seq(suiteB, suiteA)
    def cl(n: String, run: Int = 2, f: Int = 0, ig: Int = 0, a: Int = 0): String =
      s"class\t$n\trun=$run\tfailures=$f\tignored=$ig\tassumptionFailures=$a"
    def res(
        body: Seq[String],
        n: String = nonce,
        complete: Boolean = true,
        count: Option[Int] = None
    ): String =
      (Seq(s"format=${SparkItJUnitRunner.FORMAT}", s"nonce=$n") ++ body ++
        Seq(s"classes=${count.getOrElse(body.size)}") ++
        (if (complete) Seq(s"complete=$n") else Nil)).mkString("\n") + "\n"
    def vd(
        exit: Option[Int],
        text: Either[String, String],
        consumed: Boolean = true,
        exp: Seq[String] = expectedSuites
    ): Either[String, Int] = verdict(exit, consumed, text, exp, nonce)
    val clean = res(Seq(cl(suiteA), cl(suiteB, run = 3)))
    expect(
      "verdict: exit 0, complete result, both expected classes clean (PASS with 5 tests)",
      vd(Some(0), Right(clean)) == Right(5),
      vd(Some(0), Right(clean)).toString
    )
    def vRefused(name: String, got: Either[String, Int], subs: String*): Unit =
      refused(s"verdict: $name", got.left.toOption, subs :+ "NOT a pass": _*)
    vRefused(
      "an expected class did not run",
      vd(Some(0), Right(res(Seq(cl(suiteA))))),
      s"expected class(es) that did not run: $suiteB"
    )
    vRefused(
      "an unexpected class ran",
      vd(Some(0), Right(res(Seq(cl(suiteA), cl(suiteB), cl(suiteC))))),
      s"unexpected class(es) in the result: $suiteC"
    )
    vRefused(
      "a class recorded twice",
      vd(Some(0), Right(res(Seq(cl(suiteA), cl(suiteA), cl(suiteB))))),
      s"class(es) recorded twice: $suiteA"
    )
    vRefused(
      "a class ran no test",
      vd(Some(0), Right(res(Seq(cl(suiteA, run = 0), cl(suiteB))))),
      s"$suiteA ran no test"
    )
    vRefused(
      "a failure",
      vd(Some(0), Right(res(Seq(cl(suiteA, f = 1), cl(suiteB))))),
      s"$suiteA had 1 failure(s)"
    )
    vRefused(
      "an ignored test",
      vd(Some(0), Right(res(Seq(cl(suiteA), cl(suiteB, ig = 1))))),
      s"$suiteB had 1 ignored test(s)"
    )
    vRefused(
      "an assumption failure (skipped test, e.g. assume or a flaky test under MUNIT_FLAKY_OK)",
      vd(Some(0), Right(res(Seq(cl(suiteA, a = 2), cl(suiteB))))),
      s"$suiteA had 2 assumption failure(s)"
    )
    vRefused(
      "a class that could not be run",
      vd(
        Some(1),
        Right(res(Seq(s"error\t$suiteA\tjava.lang.ClassNotFoundException", cl(suiteB))))
      ),
      s"$suiteA could not be run (java.lang.ClassNotFoundException)"
    )
    vRefused(
      "a test printed a JUnit summary and called System.exit(0): no result file",
      vd(Some(0), Left(s"the runner wrote no $JUnitResultFile")),
      s"the runner wrote no $JUnitResultFile"
    )
    vRefused(
      "exit 1 without a result file",
      vd(Some(1), Left(s"the runner wrote no $JUnitResultFile")),
      "FAILED (exit 1)",
      "the runner JVM ended with exit 1",
      s"the runner wrote no $JUnitResultFile"
    )
    vRefused(
      "exit 1 with a clean result file",
      vd(Some(1), Right(clean)),
      "the runner JVM ended with exit 1"
    )
    vRefused(
      "result without its complete= line",
      vd(Some(0), Right(res(Seq(cl(suiteA), cl(suiteB)), complete = false))),
      "not written completely by this run's runner"
    )
    vRefused(
      "result cut before its final line feed",
      vd(Some(0), Right(clean.dropRight(1))),
      "is incomplete"
    )
    vRefused(
      "result with another nonce (forged by test code)",
      vd(Some(0), Right(res(Seq(cl(suiteA), cl(suiteB)), n = "2" * 64))),
      "does not carry this run's nonce"
    )
    vRefused(
      "result with another format line",
      vd(Some(0), Right(clean.replace(SparkItJUnitRunner.FORMAT, "other.v0"))),
      "does not start with format="
    )
    vRefused(
      "result with a printed summary line",
      vd(Some(0), Right(res(Seq(cl(suiteA), "OK (5 tests)", cl(suiteB))))),
      "malformed line(s) 4"
    )
    vRefused(
      "result whose classes= line does not match",
      vd(Some(0), Right(res(Seq(cl(suiteA), cl(suiteB)), count = Some(5)))),
      "the classes= line does not match"
    )
    vRefused(
      "exit 0 but the nonce file was not consumed",
      vd(Some(0), Right(clean), consumed = false),
      "did not consume its nonce file"
    )
    vRefused(
      "runner refused MUNIT_* (exit 4)",
      vd(Some(SparkItJUnitRunner.EXIT_MUNIT_ENV), Left("no file")),
      "ended with exit 4"
    )
    refused(
      "verdict: bound reached",
      vd(None, Right(clean)).left.toOption,
      "exceeded the 120s bound",
      "NOT a pass"
    )
    refused(
      "verdict: no expected class",
      vd(Some(0), Right(res(Nil)), exp = Nil).left.toOption,
      "zero tests is NOT a pass"
    )
    refused("zero integration tests", testsProblem(Nil), "no integration test", "NOT a pass")
    allowed("one integration test class", testsProblem(Seq("scholarchain.spark.it.XSuite")))
    expect(
      "a fresh nonce is 64 lower-case hex and differs between calls",
      NonceText.pattern.matcher(newNonce()).matches && newNonce() != newNonce(),
      "nonce shape"
    )
    expect(
      "runner arguments: nonce file, result file, then the expected classes",
      runnerArgs(Paths.get("/a"), Seq(suiteA)) ==
        Seq(s"/a/$RunnerDir/$NonceFile", s"/a/$JUnitResultFile", suiteA),
      runnerArgs(Paths.get("/a"), Seq(suiteA)).mkString(" ")
    )
    allowed("the runner class file is one plain class file", runnerClassBytes().left.toOption)

    // Temporary directories.
    val tmp = Files.createTempDirectory("scholarchain-sparkit-guard-")
    try {
      val t = tmp.toRealPath()
      val homeDir = t.resolve("home")
      val repo = homeDir.resolve("repo")
      Files.createDirectories(repo.resolve("project"))
      Files.createDirectories(repo.resolve(SparkTargetRel))
      Files.write(repo.resolve(ConfigRel), cfgText().getBytes(StandardCharsets.UTF_8))
      val root = homeDir.resolve(fixtureCfg.rootRel)
      val elsewhere = t.resolve("elsewhere")
      Files.createDirectories(elsewhere)
      val targets = Seq(repo.resolve("target"), repo.resolve(SparkTargetRel))
      def touch(p: Path): Unit = {
        Files.createDirectories(p.getParent)
        Files.write(p, Array.emptyByteArray)
      }
      // BE-P6-04: a synthetic Hadoop curated layout per HD-P6-01 (names, types and sizes only;
      // file bytes are placeholders, since the preflight reads no content).
      def put(p: Path, text: String): Unit = {
        Files.createDirectories(p.getParent)
        Files.write(p, text.getBytes(StandardCharsets.UTF_8))
      }
      def crcOf(p: Path): Path = p.resolveSibling(s".${p.getFileName}.crc")
      def withCrc(p: Path, text: String): Unit = {
        put(p, text)
        put(crcOf(p), "crc")
      }
      def part(d: Path, name: String = "part-r-00000.gz"): Unit = withCrc(d.resolve(name), "gz")
      val FullCandidates = Seq("joined", "pairs", "final")
      def hadoopRun(r: Path, rounds: Int = 2, cands: Seq[String] = FullCandidates): Unit = {
        Files.createDirectories(r)
        withCrc(r.resolve(ManifestFile), "{}")
        CuratedStages.foreach { s =>
          withCrc(r.resolve(s).resolve(SuccessMarker), "")
          withCrc(r.resolve(s).resolve(CountersFile), "{}")
        }
        part(r.resolve("normalize/works"))
        part(r.resolve("normalize/mentions"))
        part(r.resolve("normalize/rejects"), "part-m-00000.gz")
        part(r.resolve("normalize/rejects"))
        part(r.resolve("exact-ids/links"))
        part(r.resolve("teams/teams"))
        part(r.resolve("teams/pairs"))
        part(r.resolve("teams/stats"))
        (0 until rounds).foreach { i =>
          val rd = r.resolve(s"blocking/round-$i")
          Seq("blocks", "members").foreach(k =>
            Seq("researcher", "work").foreach(e => part(rd.resolve(s"$k/$e")))
          )
          if (i + 1 < rounds) part(rd.resolve("spill"))
          part(rd.resolve("stats"))
        }
        val c = r.resolve("candidates")
        Files.createDirectories(c.resolve("join"))
        if (cands.contains("joined")) part(c.resolve("join/joined"))
        if (cands.contains("pairs")) {
          part(c.resolve("pairs/pairs"))
          part(c.resolve("pairs/stats"))
        }
        if (cands.contains("final"))
          Seq("researcher", "work", "stats").foreach(x => part(c.resolve(s"final/$x")))
      }
      def buildFor(cfg: ItConfig): Unit = {
        deleteTree(root)
        deleteTree(elsewhere)
        Files.createDirectories(elsewhere)
        cfg.runs.foreach { case (s, r) =>
          val d = root.resolve(r)
          Files.createDirectories(d)
          if (s.key == KeyCurated) hadoopRun(d)
          else {
            touch(d.resolve(SuccessMarker))
            if (s.needsParts)
              Files.write(d.resolve(PartsFile), "[]".getBytes(StandardCharsets.UTF_8))
          }
        }
      }
      def build(): Unit = buildFor(fixtureCfg)
      def layout(): Option[String] = problems(layoutProblems(root, fixtureCfg))
      def moveAndLink(rel: String): Unit = {
        val p = root.resolve(rel)
        val moved = elsewhere.resolve(p.getFileName.toString)
        Files.move(p, moved)
        Files.createSymbolicLink(p, moved)
      }

      build()
      allowed("archive layout control (curated run without _parts.json)", layout())
      touch(root.resolve(DsStore))
      touch(root.resolve("target/local-runs/dev").resolve(DsStore))
      allowed(".DS_Store files along the paths are ignored", layout())
      build()
      deleteTree(root.resolve(curated))
      refused("missing curated run", layout(), s"lacks run=$hexA")
      build()
      deleteTree(root.resolve(decisions))
      refused("missing decision run", layout(), s"lacks run=$hexB")
      build()
      Files.createDirectories(root.resolve(s"target/local-runs/dev/er-decisions/run=${"d" * 64}"))
      refused("extra run directory", layout(), s"holds unexpected entry run=${"d" * 64}")
      build()
      touch(root.resolve("notes.txt"))
      refused("extra top-level entry", layout(), "holds unexpected entry notes.txt")
      build()
      Files.createDirectories(root.resolve(curated).getParent.resolveSibling("snapshot=dblp-y"))
      refused("extra snapshot directory", layout(), "holds unexpected entry snapshot=dblp-y")
      build()
      Files.move(
        root.resolve(assignments),
        root.resolve(s"target/local-runs/dev/er-assignments/run=${"e" * 64}")
      )
      refused(
        "renamed grouping run",
        layout(),
        s"lacks run=$hexC",
        s"unexpected entry run=${"e" * 64}"
      )
      build()
      moveAndLink("target/local-runs")
      refused(
        "symbolically linked intermediate directory",
        layout(),
        "target/local-runs is a symbolic link"
      )
      build()
      moveAndLink(curated)
      refused("symbolically linked run directory", layout(), s"run=$hexA is a symbolic link")
      build()
      deleteTree(root.resolve(decisions))
      touch(root.resolve(decisions))
      refused("run directory is a regular file", layout(), s"run=$hexB is not a directory")
      build()
      touch(root.resolve(decisions).resolve(UnverifiedMarker))
      refused(
        "decision run holds _UNVERIFIED",
        layout(),
        s"$decisions (ER decision run) holds _UNVERIFIED"
      )
      build()
      touch(root.resolve(curated).resolve(UnverifiedMarker))
      refused(
        "curated run holds _UNVERIFIED",
        layout(),
        s"(Hadoop curated dev input) holds _UNVERIFIED"
      )
      build()
      withCrc(root.resolve(curated).resolve(SuccessMarker), "")
      refused(
        "curated run holds a run-level _SUCCESS (the producer never writes one)",
        layout(),
        s"$curated/_SUCCESS: the Hadoop producer never writes a run-level _SUCCESS"
      )
      build()
      Files.delete(root.resolve(assignments).resolve(SuccessMarker))
      Files.createDirectory(root.resolve(assignments).resolve(SuccessMarker))
      refused(
        "grouping run _SUCCESS is a directory",
        layout(),
        "(ER grouping run) has no regular _SUCCESS"
      )
      build()
      Files.delete(root.resolve(assignments).resolve(PartsFile))
      refused(
        "grouping run without _parts.json",
        layout(),
        "(ER grouping run) has no regular _parts.json"
      )
      build()
      Files.delete(root.resolve(decisions).resolve(PartsFile))
      refused(
        "decision run without _parts.json",
        layout(),
        "(ER decision run) has no regular _parts.json"
      )
      build()

      // F5: the NOFOLLOW walk of each run directory (synthetic fixtures only).
      val decDir = root.resolve(decisions)
      Files.createDirectories(decDir.resolve("parts/a/b"))
      touch(decDir.resolve("part-00000.jsonl"))
      touch(decDir.resolve("parts/a/b/part-00001"))
      allowed(
        "run tree control: regular part files, nested directories to the depth bound",
        layout()
      )
      touch(elsewhere.resolve("outside-part"))
      Files.createSymbolicLink(
        decDir.resolve("part-00002.jsonl"),
        elsewhere.resolve("outside-part")
      )
      refused(
        "symbolically linked part file",
        layout(),
        s"$decisions/part-00002.jsonl is a symbolic link (not followed)"
      )
      build()
      Files.createDirectories(elsewhere.resolve("outside-dir"))
      Files.createSymbolicLink(
        root.resolve(curated).resolve("works"),
        elsewhere.resolve("outside-dir")
      )
      refused(
        "symbolically linked subdirectory",
        layout(),
        s"$curated/works is a symbolic link (not followed)"
      )
      build()
      // A dangling link whose target string names a held-out path inside this fixture only; the
      // target is never created, followed or read, and the refusal does not show it.
      val heldoutNamed = t.resolve(
        "fixture-only/modules/hadoop/target/local-pipeline/er-candidates/heldout/part-00000"
      )
      Files.createSymbolicLink(root.resolve(assignments).resolve("part-00000"), heldoutNamed)
      val linkGot = layout()
      expect(
        "symbolic link to a held-out-named path (refused by its own path; target never shown)",
        linkGot.exists(g =>
          g.contains(s"$assignments/part-00000 is a symbolic link (not followed)") &&
            !g.contains("heldout")
        ) && !Files.exists(heldoutNamed, LinkOption.NOFOLLOW_LINKS),
        linkGot.getOrElse("ALLOW")
      )
      build()
      val sockDir = Files.createTempDirectory("scs")
      val special = Try {
        val sp = sockDir.resolve("s")
        val ch = java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX)
        try ch.bind(java.net.UnixDomainSocketAddress.of(sp))
        finally ch.close()
        Files.move(sp, decDir.resolve("part-socket"))
      }
      special match {
        case scala.util.Success(_) =>
          refused(
            "special file (a Unix domain socket) in a run directory",
            layout(),
            s"$decisions/part-socket is a special file"
          )
        case scala.util.Failure(e) =>
          log.info(
            s"$SelfTest: special-file case not run: no socket file can be created here " +
              s"(${e.getClass.getSimpleName})"
          )
      }
      deleteTree(sockDir)
      build()
      Files.createDirectories(decDir.resolve("a/b/c/d"))
      touch(decDir.resolve("a/b/c/d/e"))
      refused(
        "entry deeper than the depth bound",
        layout(),
        s"$decisions/a/b/c/d/e is more than $MaxRunDepth levels below the run directory"
      )
      build()
      (1 to 5).foreach(i => touch(decDir.resolve(s"part-$i")))
      refused(
        "entry-count bound (3 entries)",
        problems(runTreeProblems(decDir, decisions, MaxRunDepth, 3)),
        s"$decisions holds more than 3 entries (bound)"
      )
      allowed(
        "entry-count bound control (100 entries)",
        problems(runTreeProblems(decDir, decisions, MaxRunDepth, 100))
      )
      build()
      touch(decDir.resolve(DsStore))
      allowed(".DS_Store inside the decision run stays tolerated (unchanged)", layout())
      build()

      // BE-P6-04: the Hadoop curated contract (HD-P6-01 rules 1-11) on synthetic fixtures only.
      val cur = root.resolve(curated)
      def curCase(name: String, sub: String)(change: => Unit): Unit = {
        build()
        change
        refused(s"curated: $name", layout(), sub)
      }
      def curAllowed(name: String)(change: => Unit): Unit = {
        build()
        change
        allowed(s"curated: $name", layout())
      }
      curAllowed(
        "valid Hadoop layout (5 stages, round-0 and round-1, entity parts at depth 5, crc " +
          "sidecars, candidates join+pairs+final)"
      )(())
      curAllowed("valid Hadoop layout with candidates join only") {
        deleteTree(cur)
        hadoopRun(cur, cands = Seq("joined"))
      }
      curAllowed("valid Hadoop layout with an empty join job output directory, one round") {
        deleteTree(cur)
        hadoopRun(cur, rounds = 1, cands = Nil)
      }
      curAllowed("valid Hadoop layout with four rounds (round-0 to round-3)") {
        deleteTree(cur)
        hadoopRun(cur, rounds = 4)
      }
      curCase("missing stage _SUCCESS", s"$curated/teams/_SUCCESS is missing") {
        Files.delete(cur.resolve("teams/_SUCCESS"))
        Files.delete(crcOf(cur.resolve("teams/_SUCCESS")))
      }
      curCase("stage _SUCCESS not empty", s"$curated/normalize/_SUCCESS is not empty (1 bytes") {
        withCrc(cur.resolve("normalize/_SUCCESS"), "x")
      }
      curCase("missing _counters.json", s"$curated/blocking/_counters.json is missing") {
        Files.delete(cur.resolve("blocking/_counters.json"))
        Files.delete(crcOf(cur.resolve("blocking/_counters.json")))
      }
      curCase("missing _run-manifest.json", s"$curated lacks _run-manifest.json") {
        Files.delete(cur.resolve(ManifestFile))
        Files.delete(crcOf(cur.resolve(ManifestFile)))
      }
      curCase("empty _run-manifest.json", s"$curated/_run-manifest.json is empty") {
        withCrc(cur.resolve(ManifestFile), "")
      }
      curCase("missing stage directory", s"$curated lacks the stage directory teams/") {
        deleteTree(cur.resolve("teams"))
      }
      curCase(
        "_temporary in a job output directory",
        s"$curated/blocking/round-0/_temporary marks incomplete or failed output"
      )(Files.createDirectories(cur.resolve("blocking/round-0/_temporary/0")))
      curCase("_IN_PROGRESS in the run directory", s"$curated/_IN_PROGRESS marks incomplete")(
        withCrc(cur.resolve("_IN_PROGRESS"), "")
      )
      curCase("_FAILED in a stage directory", s"$curated/candidates/_FAILED marks incomplete")(
        withCrc(cur.resolve("candidates/_FAILED"), "")
      )
      curCase(".staging directory", s"$curated/.staging-1 marks incomplete")(
        Files.createDirectories(cur.resolve(".staging-1"))
      )
      curCase(
        "_SUCCESS outside a stage directory",
        s"$curated/candidates/join/_SUCCESS is a _SUCCESS outside a stage directory"
      )(withCrc(cur.resolve("candidates/join/_SUCCESS"), ""))
      curCase(".DS_Store in the curated run", s"$curated/normalize/.DS_Store is a .DS_Store file")(
        touch(cur.resolve("normalize/.DS_Store"))
      )
      curCase(
        "stray dotfile",
        s"$curated/normalize/works/.hidden is a hidden entry other than a .<name>.crc"
      )(touch(cur.resolve("normalize/works/.hidden")))
      curCase(
        "AppleDouble file",
        s"$curated/normalize/works/._part-r-00000.gz is a hidden entry other than"
      )(touch(cur.resolve("normalize/works/._part-r-00000.gz")))
      curCase(
        "missing .crc sibling",
        s"$curated/exact-ids/links/part-r-00000.gz has no checksum sidecar .part-r-00000.gz.crc"
      )(Files.delete(crcOf(cur.resolve("exact-ids/links/part-r-00000.gz"))))
      curCase(
        "orphan .crc",
        s"$curated/teams/pairs/.part-r-00001.gz.crc is a checksum sidecar without a regular " +
          "sibling part-r-00001.gz"
      )(put(cur.resolve("teams/pairs/.part-r-00001.gz.crc"), "crc"))
      val deep = cur.resolve("blocking/round-0/blocks/work/deep")
      build()
      part(deep)
      refused(
        "curated: file at depth 6",
        layout(),
        s"$curated/blocking/round-0/blocks/work/deep/part-r-00000.gz is more than 5 levels below"
      )
      refused(
        "curated: directory at depth 5",
        layout(),
        s"$curated/blocking/round-0/blocks/work/deep is a directory more than 4 levels below"
      )
      curCase(
        "round gap (round-0 and round-2)",
        s"$curated/blocking round directories are not contiguous from round-0 (found round-0, " +
          "round-2"
      )(Files.move(cur.resolve("blocking/round-1"), cur.resolve("blocking/round-2")))
      curCase("round-4", s"$curated/blocking/round-4 is beyond the last blocking level") {
        deleteTree(cur)
        hadoopRun(cur, rounds = 5)
      }
      curCase("no blocking round", s"$curated/blocking holds no round-0 directory") {
        deleteTree(cur.resolve("blocking/round-0"))
        deleteTree(cur.resolve("blocking/round-1"))
      }
      curCase(
        "part-m outside rejects",
        s"$curated/normalize/works/part-m-00000.gz is a map-side part-m file outside a rejects"
      )(part(cur.resolve("normalize/works"), "part-m-00000.gz"))
      curCase("empty part file", s"$curated/teams/teams/part-r-00000.gz is an empty part file")(
        withCrc(cur.resolve("teams/teams/part-r-00000.gz"), "")
      )
      curCase(
        "non-part file in a leaf",
        s"$curated/normalize/works/data.json is not a part-[mr]-NNNNN.gz file"
      )(withCrc(cur.resolve("normalize/works/data.json"), "x"))
      curCase(
        "empty leaf output directory",
        s"$curated/normalize/venues holds no part file (an empty leaf output directory"
      )(Files.createDirectories(cur.resolve("normalize/venues")))
      curCase(
        "empty blocks/ directory",
        s"$curated/blocking/round-1/blocks is an empty output directory without researcher/"
      ) {
        deleteTree(cur.resolve("blocking/round-1/blocks/researcher"))
        deleteTree(cur.resolve("blocking/round-1/blocks/work"))
      }
      curCase("unknown top-level entry", s"$curated/notes.txt is an unexpected entry")(
        withCrc(cur.resolve("notes.txt"), "x")
      )
      curCase("unknown stage subdirectory", s"$curated/normalize/extra is an unexpected entry")(
        part(cur.resolve("normalize/extra"))
      )
      curCase("candidates without join", s"$curated/candidates lacks join/")(
        deleteTree(cur.resolve("candidates/join"))
      )
      curCase(
        "candidates/pairs without join/joined parts",
        s"$curated/candidates/pairs exists although join/joined holds no part file"
      )(deleteTree(cur.resolve("candidates/join/joined")))
      curCase(
        "candidates/final without pairs/pairs parts",
        s"$curated/candidates/final exists although pairs/pairs holds no part file"
      )(deleteTree(cur.resolve("candidates/pairs/pairs")))
      curCase(
        "symbolic link in the curated run (a part file at depth 5)",
        s"$curated/blocking/round-0/members/researcher/part-r-00001.gz is a symbolic link " +
          "(not followed)"
      ) {
        touch(elsewhere.resolve("outside-part"))
        Files.createSymbolicLink(
          cur.resolve("blocking/round-0/members/researcher/part-r-00001.gz"),
          elsewhere.resolve("outside-part")
        )
      }
      build()
      refused(
        "curated: entry-count bound (10 entries)",
        problems(curatedRunProblems(cur, curated, 10)),
        s"$curated holds more than 10 entries (bound)"
      )
      allowed(
        s"curated: entry-count bound control ($CuratedMaxEntries entries)",
        problems(curatedRunProblems(cur, curated, CuratedMaxEntries))
      )
      val otherCfg = fixtureCfg.copy(runs = fixtureCfg.runs.map { case (s, r) =>
        if (s.key == KeyCurated) (s, curatedOther) else (s, r)
      })
      buildFor(otherCfg)
      refused(
        "curated: a valid Hadoop layout under a different curated run ID",
        problems(layoutProblems(root, otherCfg)),
        s"is not the pinned Hadoop curated dev run $PinnedCuratedRunName"
      )
      build()

      // Location.
      allowed("archive root location control", locationProblem(root, repo, targets))
      refused(
        "missing root",
        locationProblem(homeDir.resolve("absent"), repo, targets),
        "does not exist"
      )
      touch(homeDir.resolve("file.txt"))
      refused(
        "root is a regular file",
        locationProblem(homeDir.resolve("file.txt"), repo, targets),
        "is not a directory"
      )
      Files.createSymbolicLink(homeDir.resolve("link"), root)
      refused(
        "root is a symbolic link",
        locationProblem(homeDir.resolve("link"), repo, targets),
        "symbolic link"
      )
      Files.createSymbolicLink(homeDir.resolve("alink"), homeDir.resolve("arch"))
      refused(
        "root below a symbolically linked ancestor",
        locationProblem(homeDir.resolve("alink/phase/dev"), repo, targets),
        "symbolic link"
      )
      Files.createDirectories(repo.resolve("arch-copy"))
      refused(
        "root inside the repository",
        locationProblem(repo.resolve("arch-copy"), repo, targets),
        "inside the repository"
      )
      refused(
        "root contains the repository",
        locationProblem(homeDir, repo, targets),
        "contains the repository"
      )
      val ext = t.resolve("ext-target")
      Files.createDirectories(ext.resolve("arch"))
      refused(
        "root inside an sbt target directory outside the repository",
        locationProblem(ext.resolve("arch"), repo, targets :+ ext),
        "inside the sbt target directory"
      )

      // Output.
      allowed(
        "Spark build target control",
        outputTargetProblem(repo.toFile, repo.resolve(SparkTargetRel).toFile)
      )
      refused(
        "output under the hadoop target",
        outputTargetProblem(repo.toFile, repo.resolve("modules/hadoop/target").toFile),
        "sparkIT writes only under"
      )
      refused(
        "output outside the repository",
        outputTargetProblem(repo.toFile, elsewhere.toFile),
        "sparkIT writes only under"
      )
      refused(
        "output path that normalizes outside modules/spark/target",
        outputTargetProblem(
          repo.toFile,
          repo.resolve(s"$SparkTargetRel/../../hadoop/target").toFile
        ),
        "sparkIT writes only under"
      )
      val out = outputRoot(repo)
      allowed("output root is created", out.left.toOption)
      out.foreach { o =>
        Files.createDirectory(o.resolve("attempt-1"))
        Files.createDirectory(o.resolve("attempt-3"))
        touch(o.resolve("notes"))
        val next = nextAttempt(o)
        expect(
          "next attempt is attempt-4 and earlier attempts stay",
          next.toOption.contains(o.resolve("attempt-4")) &&
            Files.isDirectory(o.resolve("attempt-1")) && Files.isDirectory(o.resolve("attempt-3")),
          next.toString
        )
        refused(
          "create-once: an existing attempt",
          createAttempt(o, 3).left.toOption,
          "create-once"
        )
        Files.createSymbolicLink(o.resolve("attempt-9"), elsewhere)
        refused("symbolic link in the output root", nextAttempt(o).left.toOption, "symbolic link")
        Files.delete(o.resolve("attempt-9"))
        deleteTree(o)
        Files.createSymbolicLink(o, elsewhere)
        refused("output root is a symbolic link", outputRoot(repo).left.toOption, "symbolic link")
        Files.delete(o)
        val st = repo.resolve(SparkTargetRel)
        Files.move(st, t.resolve("moved-target"))
        Files.createSymbolicLink(st, t.resolve("moved-target"))
        refused(
          "Spark target is a symbolic link",
          outputRoot(repo).left.toOption,
          s"$SparkTargetRel is a symbolic link"
        )
        Files.delete(st)
        Files.move(t.resolve("moved-target"), st)
        touch(repo.resolve(OutputRootRel))
        refused(
          "output root is a regular file",
          Try(outputRoot(repo)).fold(
            e => Some(s"EXCEPTION ${e.getClass.getSimpleName}"),
            _.left.toOption
          ),
          s"$OutputRootRel is a symbolic link or not a directory; sparkIT writes nothing"
        )
        Files.delete(repo.resolve(OutputRootRel))
      }
      val att = repo.resolve(s"$OutputRootRel/attempt-7")
      val opts = jvmOptions(java17, att, root, fixtureCfg)
      expect(
        "JVM options: Java 17 opens, scratch and every property under the attempt or the archive",
        java17.forall(opts.contains) &&
          opts.contains(s"-Djava.io.tmpdir=${att.resolve("tmp")}") &&
          Prop.All.forall(k => opts.exists(_.startsWith(s"-D$k="))) &&
          opts.filter(_.startsWith("-D" + Prop.Prefix)).forall { o =>
            val v = o.drop(o.indexOf('=') + 1)
            v == att.toString || v.startsWith(att.toString + "/") || v == root.toString ||
            v.startsWith(root.toString + "/")
          },
        opts.mkString(" ")
      )

      // Preflight end to end on the fixture.
      def preflightResult(
          h: Option[String],
          inherited: Map[String, String] = Map.empty,
          sparkTarget: Path = repo.resolve(SparkTargetRel)
      ): Option[String] =
        Try(
          preflight(
            repo.toFile,
            h,
            inherited,
            targets.map(_.toFile),
            sparkTarget.toFile,
            sbt.util.Logger.Null
          )
        ).failed.toOption
          .map(_.getMessage)
      build()
      val h = Some(homeDir.toString)
      allowed("preflight control", preflightResult(h))
      refused(
        "preflight: SCHOLARCHAIN_CFG_* inherited",
        preflightResult(h, Map("SCHOLARCHAIN_CFG_X" -> "1")),
        "SCHOLARCHAIN_CFG_X"
      )
      refused("preflight: user.home unset", preflightResult(None), "user.home")
      refused(
        "preflight: Spark target elsewhere",
        preflightResult(h, sparkTarget = elsewhere),
        "sparkIT writes only under"
      )
      touch(root.resolve("extra"))
      refused("preflight: extra archive entry", preflightResult(h), "holds unexpected entry extra")
      build()
      val cfgFile = repo.resolve(ConfigRel)
      Files.move(cfgFile, elsewhere.resolve("spark-it.properties"))
      Files.createSymbolicLink(cfgFile, elsewhere.resolve("spark-it.properties"))
      refused(
        "preflight: configuration file is a symbolic link",
        preflightResult(h),
        "resolves through a symbolic link"
      )
      Files.delete(cfgFile)
      refused(
        "preflight: configuration file missing",
        preflightResult(h),
        "is missing or not a regular file"
      )
      Files.write(cfgFile, cfgText().getBytes(StandardCharsets.UTF_8))
      val oversized = cfgText() + ("# padding to exceed the size bound\n" * 2000)
      Files.write(cfgFile, oversized.getBytes(StandardCharsets.UTF_8))
      refused(
        s"configuration file larger than $MaxConfigBytes bytes (${oversized.length} bytes)",
        loadConfig(repo).left.toOption,
        s"is larger than $MaxConfigBytes bytes"
      )
      Files.write(cfgFile, cfgText().getBytes(StandardCharsets.UTF_8))
      val projectDir = repo.resolve("project")
      val movedProject = t.resolve("moved-project")
      Files.move(projectDir, movedProject)
      Files.createSymbolicLink(projectDir, movedProject)
      refused(
        "project/ is a symbolic link (the configuration file itself is regular)",
        loadConfig(repo).left.toOption,
        s"$ConfigRel resolves through a symbolic link"
      )
      Files.delete(projectDir)
      Files.move(movedProject, projectDir)
      allowed("preflight final control after every undo", preflightResult(h))

      // The runner class, forked twice on a fixture attempt without JUnit on its classpath.
      val rAttempt = t.resolve("runner-attempt")
      Files.createDirectories(rAttempt)
      val rNonce = newNonce()
      installRunner(rAttempt, rNonce) match {
        case Left(m) => expect("the runner installs into a fixture attempt", ok = false, m)
        case Right(rDir) =>
          val listed = listNames(rDir).getOrElse(Nil)
          expect(
            "the runner directory holds exactly the runner class file and the nonce file",
            listed == Seq(NonceFile, s"$RunnerMain.class").sorted,
            listed.mkString(",")
          )
          def fork(env: Map[String, String]): Option[Int] =
            LocalRuns.runForked(
              SelfTest,
              rAttempt.toFile,
              javaHome,
              Seq(rDir.toFile),
              Seq(s"-Djava.io.tmpdir=$rAttempt", "-Xshare:auto"),
              env,
              RunnerMain,
              runnerArgs(rAttempt, Seq(suiteA)),
              30L,
              LocalRuns.ToLog(log),
              log,
              StrippedPrefixes
            )
          val munitExit = fork(Map("MUNIT_FLAKY_OK" -> "true"))
          expect(
            "runner fork with MUNIT_FLAKY_OK set: exit 4 before reading the nonce, no result file",
            munitExit.contains(SparkItJUnitRunner.EXIT_MUNIT_ENV) &&
              Files.exists(rDir.resolve(NonceFile), LinkOption.NOFOLLOW_LINKS) &&
              !Files.exists(rAttempt.resolve(JUnitResultFile), LinkOption.NOFOLLOW_LINKS),
            s"exit $munitExit"
          )
          val noJunitExit = fork(Map.empty)
          val consumed = !Files.exists(rDir.resolve(NonceFile), LinkOption.NOFOLLOW_LINKS)
          val v = verdict(noJunitExit, consumed, readResult(rAttempt), Seq(suiteA), rNonce)
          expect(
            "runner fork without JUnit: nonce consumed, exit 2, no result file, verdict NOT a pass",
            noJunitExit.contains(SparkItJUnitRunner.EXIT_SETUP) && consumed &&
              v.left.exists(_.contains(s"the runner wrote no $JUnitResultFile")),
            s"exit $noJunitExit; nonce consumed=$consumed; verdict $v"
          )
      }
    } finally deleteTree(tmp)

    // The committed configuration, and a read-only report of the real archive root (not enforced).
    val repoReal = buildBase.toPath.toRealPath()
    val committed = loadConfig(repoReal)
    expect(
      "the committed configuration parses",
      committed.isRight,
      committed.left.toOption.getOrElse("")
    )
    committed.foreach { c =>
      val where = home.filter(_.nonEmpty).map(hd => resolveRoot(new File(hd), c))
      val text = where match {
        case None => "user.home is unset"
        case Some(Left(m)) => s"REFUSE: $m"
        case Some(Right(r)) =>
          val state =
            if (Files.isDirectory(r, LinkOption.NOFOLLOW_LINKS)) "present (a directory)"
            else if (Files.exists(r, LinkOption.NOFOLLOW_LINKS)) "present but not a real directory"
            else "absent"
          s"<user.home>/${c.rootRel} is $state; contents not read, layout not checked"
      }
      log.info(
        s"$SelfTest: configured archive root, read-only (reported, not enforced by check): $text"
      )
    }
    if (failures.nonEmpty)
      selfFail(s"FAIL: ${failures.size} case(s) wrong: ${failures.mkString(" | ")}")
    log.info(s"$SelfTest: PASS: $cases cases gave the expected verdict")
  }
}
