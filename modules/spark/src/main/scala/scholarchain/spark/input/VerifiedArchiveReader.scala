package scholarchain.spark.input

import java.nio.file.{Path, Paths}

import scholarchain.core.*

/**
 * The three run directories a collaboration analysis reads, from configuration (never built in):
 * the Hadoop curated input run, the entity-resolution decision run and the grouping run. Every path
 * is absolute, normalized and strictly inside `archiveRoot`, and the three are distinct.
 *
 * `pinnedCuratedRunId` is the one curated run (`run:<64 hex>`) to which the reader applies the
 * Hadoop curated-run contract ([[HadoopCuratedRun]], HD-P6-01 rules 1-11, human decision Option A).
 * The caller supplies it explicitly from a committed constant; it is never inferred from the input,
 * and a curated run with any other run ID is refused (CheckStep.CuratedRun).
 */
final case class ArchiveRuns(
    archiveRoot: Path,
    curatedRun: Path,
    decisionRun: Path,
    groupingRun: Path,
    pinnedCuratedRunId: String
)

object ArchiveRuns {

  /** Builds and checks the locations and the pinned run ID's form; Left with every problem. */
  def of(
      archiveRoot: String,
      curatedRun: String,
      decisionRun: String,
      groupingRun: String,
      pinnedCuratedRunId: String
  ): Either[Vector[String], ArchiveRuns] = {
    val pinForm =
      if (
        pinnedCuratedRunId != null && HadoopCuratedRun.PinnedRunIdText.matches(pinnedCuratedRunId)
      )
        Vector.empty
      else Vector(s"pinnedCuratedRunId '$pinnedCuratedRunId' is not run:<64 lowercase hex>")
    def path(label: String, s: String): Either[String, Path] =
      if (s == null || s.isEmpty) Left(s"$label is not set")
      else {
        val p = Paths.get(s)
        if (!p.isAbsolute) Left(s"$label '$s' is not absolute")
        else if (p.normalize != p) Left(s"$label '$s' is not normalized")
        else Right(p)
      }
    val parsed = Vector(
      "archiveRoot" -> archiveRoot,
      "curatedRun" -> curatedRun,
      "decisionRun" -> decisionRun,
      "groupingRun" -> groupingRun
    ).map { case (l, s) => path(l, s) }
    val errors = parsed.collect { case Left(e) => e } ++ pinForm
    if (errors.nonEmpty) Left(errors)
    else {
      val ps = parsed.collect { case Right(p) => p }
      val (root, c, d, g) = (ps(0), ps(1), ps(2), ps(3))
      val inside = Vector("curatedRun" -> c, "decisionRun" -> d, "groupingRun" -> g).collect {
        case (l, p) if !(p.startsWith(root) && p != root) => s"$l '$p' is not inside '$root'"
      }
      val distinct =
        if (Set(c, d, g).size != 3) Vector("the three runs must be different directories")
        else Vector.empty
      if (inside.nonEmpty || distinct.nonEmpty) Left(inside ++ distinct)
      else Right(ArchiveRuns(root, c, d, g, pinnedCuratedRunId))
    }
  }
}

/** One verified run: its directory, run ID, run manifest and (when published) part manifest. */
final case class VerifiedRun(
    label: String,
    dir: Path,
    runId: String,
    manifest: RunManifest,
    partManifest: Option[PartManifestV2]
) {

  /** Absolute paths of the listed parts of `recordSchemaVersion`, sorted by listed path. */
  def partsOf(recordSchemaVersion: String): Vector[Path] =
    partManifest.toVector
      .flatMap(_.files)
      .filter(_.recordSchemaVersion == recordSchemaVersion)
      .map(f => dir.resolve(f.path))

  /** The listed parts of `recordSchemaVersion` with their absolute paths, sorted by listed path. */
  def listedOf(recordSchemaVersion: String): Vector[(Path, PartFileV2)] =
    partManifest.toVector
      .flatMap(_.files)
      .filter(_.recordSchemaVersion == recordSchemaVersion)
      .map(f => (dir.resolve(f.path), f))

  /** Declared rows of the listed parts of `recordSchemaVersion`. */
  def rowsOf(recordSchemaVersion: String): Long =
    listedOf(recordSchemaVersion).map(_._2.rowCount).sum

  /** Declared bytes (verified against the observed bytes) of the parts of `recordSchemaVersion`. */
  def bytesOf(recordSchemaVersion: String): Long =
    listedOf(recordSchemaVersion).map(_._2.byteCount).sum
}

/**
 * The verified analysis input. `graphInput` and `decisions` are the decoded grouping and decision
 * records, held on the driver only because the core validators are driver-side; their size is
 * bounded by [[ReaderLimits.maxDriverValidationRows]] and
 * [[ReaderLimits.maxDriverValidationBytes]]. `limits` are the bounds the input was read with (the
 * publisher reuses `maxMetadataBytes` when it reads back the run it writes).
 */
final case class VerifiedInput(
    curated: VerifiedRun,
    decision: VerifiedRun,
    grouping: VerifiedRun,
    graphInput: GraphInput,
    decisions: Vector[MatchDecision],
    limits: ReaderLimits
) {
  def teamParts: Vector[Path] = grouping.partsOf(SchemaVersions.PublicationTeam)
  def workParts: Vector[Path] = grouping.partsOf(SchemaVersions.WorkAssignment)
}

/**
 * Verified reader of an archived dev (or sample) run chain (SP-P6-02 step 1). Read-only: it never
 * writes, renames or deletes anything. Actual order of checks (CR-P6-14 nit 3, corrected in
 * CR-P6-19 F6); the reader stops at the first stage that refuses:
 *
 *   1. layout of all three runs (curated, decision, grouping), no file content read; every layout
 *      refusal of every run is reported together: [[CheckStep.RunName]] `run=<64 lowercase hex>`
 *      (if not, nothing else is checked for that run); curated run only, [[CheckStep.CuratedRun]]:
 *      its run ID is [[ArchiveRuns.pinnedCuratedRunId]] and it lies in `hadoop/snapshot=<token>/`
 *      (if not, nothing else is checked for it); then the run must be a real directory (reported as
 *      [[CheckStep.Tree]]); [[CheckStep.UnverifiedMarker]] no `_UNVERIFIED`;
 *      [[CheckStep.StagingEntry]] no `.staging-*` / `.unverified-*` entry beside the run or in its
 *      tree, and the parent directory must be listable (fail closed); decision and grouping runs
 *      only, [[CheckStep.SuccessMarker]] a regular `_SUCCESS`; [[CheckStep.Tree]] a bounded
 *      NOFOLLOW walk that finds only regular files and directories (decision and grouping:
 *      `maxTreeDepth`; curated: files at most 5 and directories at most 4 levels, HD-P6-01 rule 9);
 *      curated run only, [[CheckStep.HadoopLayout]]: the structural HD-P6-01 rules 2-8, 10 and 11
 *      ([[HadoopCuratedRun]]; stage `_SUCCESS` and `_counters.json`, crc sidecars, no run-level
 *      `_SUCCESS`, no `.DS_Store` or other hidden entry);
 *   2. per run, in the order curated, decision, grouping, and stopping at the first run that
 *      refuses:
 *      - curated: [[CheckStep.RunManifest]] (`_run-manifest.json` at most `maxMetadataBytes`,
 *        decoded through the core contract, stage `hadoop.pipeline`, `RunId.of` equals the
 *        directory's run ID, exactly one DBLP snapshot, equal to the `snapshot=` token; HD-P6-01
 *        rule 2), then [[CheckStep.StageCounters]] (each stage's `_counters.json` at most
 *        `maxMetadataBytes`, a JSON object naming its stage, with the ten required counters as
 *        non-negative integers; rule 4). The curated run publishes no part manifest (one is refused
 *        by rule 3); its data parts are not consumed by this step, so they are neither opened,
 *        decompressed nor compared with their crc sidecars (rules 7 and 8 content: not enforced);
 *      - decision, then grouping: [[CheckStep.PartManifest]] (`_parts.json` at most
 *        `maxMetadataBytes`, decoded, and every file verified against it by core
 *        (`PartManifests.verify`; the grouping run through
 *        `AnalysisValidation.checkGroupingRunParts`; the decision run must list a match-decision
 *        part), then [[CheckStep.RunManifest]] (`_run-manifest.json` at most `maxMetadataBytes`,
 *        decodes, `RunId.of` equals the directory's run ID and the part manifest's run ID);
 *   3. [[CheckStep.RunChain]]: the grouping manifest's `decisionRunId` is the decision run and its
 *      `inputRunId` the curated run; the decision manifest's `inputRunId` is the curated run; the
 *      input snapshot sets are equal (grouping and decision, decision and curated);
 *   4. [[CheckStep.DriverBound]]: the declared rows and the declared (verified) bytes of the
 *      consumed parts fit `maxDriverValidationRows` and `maxDriverValidationBytes`; checked before
 *      any data row is read;
 *   5. [[CheckStep.Decode]] of teams, work and researcher assignments and decisions through
 *      `Json.decode`, each part read with at most its declared bytes;
 *   6. [[CheckStep.GraphInput]] `AnalysisValidation.checkGraphInput`, then [[CheckStep.Identity]]
 *      `AnalysisValidation.checkIdentity` (no metrics yet: it checks the run binding of every
 *      researcher assignment and decision).
 *
 * Limitations: the archive is read-only by convention only. A file changed between verification and
 * a later read is detected only where a later bound or count applies: a data part read for decoding
 * that is larger than its declared bytes is refused, and the Spark job re-counts the rows it
 * decodes; a same-size change after verification is not detected here.
 */
object VerifiedArchiveReader {

  /** Parameter of the decision run manifest naming its curated input (ER-P6-02 decision run). */
  val DecisionInputRunKey = "inputRunId"

  private val Keys = AnalysisValidation.GroupingManifestKeys

  def read(runs: ArchiveRuns, limits: ReaderLimits): Either[Vector[Refusal], VerifiedInput] = {
    val layoutRefusals =
      curatedLayout(runs.curatedRun, runs.pinnedCuratedRunId, limits) ++
        layout("decision", runs.decisionRun, limits) ++
        layout("grouping", runs.groupingRun, limits)
    if (layoutRefusals.nonEmpty) Left(layoutRefusals)
    else
      for {
        c <- curatedIntegrity(runs.curatedRun, limits)
        d <- integrity("decision", runs.decisionRun, limits)
        g <- integrity("grouping", runs.groupingRun, limits)
        _ <- chain(c, d, g)
        _ <- bound(d, g, limits)
        decoded <- decode(d, g)
        _ <- validate(decoded._1, decoded._2, g)
      } yield VerifiedInput(c, d, g, decoded._1, decoded._2, limits)
  }

  // ---------------------------------------------------------------- 1. layout

  /**
   * Layout refusals of one published (decision, grouping or analysis) run, in check order; no file
   * content is read. Unchanged by step 1A repair 02.
   */
  def layout(label: String, dir: Path, limits: ReaderLimits): Vector[Refusal] =
    layoutOf(label, dir, limits.maxTreeEntries, limits.maxTreeDepth, pinnedCuratedRunId = None)

  /**
   * Layout refusals of the Hadoop curated run, in check order; no file content is read. HD-P6-01
   * rules 1 and 3-11 ([[HadoopCuratedRun]]): the run ID must be `pinnedCuratedRunId`, no top-level
   * `_SUCCESS` is required (one is refused), and the NOFOLLOW walk is bounded by `maxTreeEntries`
   * and the contract depths (a directory at depth [[HadoopCuratedRun.MaxDirDepth]] + 1 stops the
   * walk, so files lie at most [[HadoopCuratedRun.MaxFileDepth]] levels below the run).
   */
  def curatedLayout(dir: Path, pinnedCuratedRunId: String, limits: ReaderLimits): Vector[Refusal] =
    layoutOf(
      "curated",
      dir,
      limits.maxTreeEntries,
      HadoopCuratedRun.MaxDirDepth + 1,
      Some(pinnedCuratedRunId)
    )

  private def layoutOf(
      label: String,
      dir: Path,
      maxEntries: Int,
      maxDepth: Int,
      pinnedCuratedRunId: Option[String]
  ): Vector[Refusal] = {
    val name = Option(dir.getFileName).map(_.toString).getOrElse("")
    lazy val location =
      pinnedCuratedRunId.toVector.flatMap(pin => HadoopCuratedRun.locationProblems(dir, pin))
    if (!RunTree.RunDirName.matches(name))
      Vector(Refusal(CheckStep.RunName, label, s"'$name' is not run=<64 lowercase hex>"))
    else if (location.nonEmpty) location.map(w => Refusal(CheckStep.CuratedRun, label, w))
    else if (!RunTree.isRealDirectory(dir))
      Vector(Refusal(CheckStep.Tree, label, s"$dir is not a real directory (missing or a link)"))
    else {
      val walked = RunTree.walk(dir, maxEntries, maxDepth)
      val entries = walked.getOrElse(Vector.empty)
      val unverified =
        if (RunTree.exists(dir.resolve(RunTree.UnverifiedMarker)))
          Vector(Refusal(CheckStep.UnverifiedMarker, label, "_UNVERIFIED is present"))
        else Vector.empty
      // The parent must be listable: an unlistable parent is refused, never read as empty (F5).
      val siblings: Vector[Either[String, String]] = Option(dir.getParent) match {
        case None => Vector(Left(s"$dir has no parent directory"))
        case Some(parent) =>
          RunTree.names(parent) match {
            case Left(why) => Vector(Left(why))
            case Right(ns) => ns.filter(RunTree.isStagingName).map(n => Right(n))
          }
      }
      val inside = entries.filter(e => RunTree.isStagingName(e.relativePath.split('/').last))
      val staging = siblings.map {
        case Left(why) =>
          Refusal(
            CheckStep.StagingEntry,
            label,
            s"the staging-entry rule cannot be checked: $why"
          )
        case Right(n) =>
          Refusal(CheckStep.StagingEntry, label, s"staging or unverified entry '$n' beside the run")
      } ++ inside.map(e =>
        Refusal(
          CheckStep.StagingEntry,
          label,
          s"staging or unverified entry '${e.relativePath}' inside the run"
        )
      )
      // The Hadoop curated run has no run-level _SUCCESS (HD-P6-01 rules 3, 10: one is refused by
      // the curated structure check); every other run needs one.
      val success =
        if (pinnedCuratedRunId.isDefined) Vector.empty
        else if (RunTree.isRegularFile(dir.resolve(PartManifest.SuccessMarker))) Vector.empty
        else Vector(Refusal(CheckStep.SuccessMarker, label, "no regular _SUCCESS"))
      val tree = walked match {
        case Left(why) =>
          val bound =
            if (pinnedCuratedRunId.isDefined)
              s" (curated bound, HD-P6-01 rule 9: files at most ${HadoopCuratedRun.MaxFileDepth}" +
                s" and directories at most ${HadoopCuratedRun.MaxDirDepth} levels below the run," +
                s" at most $maxEntries entries)"
            else ""
          Vector(Refusal(CheckStep.Tree, label, why + bound))
        case Right(es) => treeRefusals(label, es)
      }
      val hadoop = (pinnedCuratedRunId, walked) match {
        case (Some(_), Right(es)) =>
          HadoopCuratedRun
            .structureProblems(dir, es)
            .map(w => Refusal(CheckStep.HadoopLayout, label, w))
        case _ => Vector.empty
      }
      unverified ++ staging ++ success ++ tree ++ hadoop
    }
  }

  /**
   * Tree refusals of walked entries: every symbolic link and every entry that is neither a regular
   * file nor a directory (a special file: FIFO, socket, device; kind `Other`).
   */
  def treeRefusals(label: String, entries: Vector[TreeEntry]): Vector[Refusal] =
    entries.collect {
      case e if e.kind == TreeEntry.Kind.SymbolicLink =>
        Refusal(CheckStep.Tree, label, s"'${e.relativePath}' is a symbolic link")
      case e if e.kind == TreeEntry.Kind.Other =>
        Refusal(CheckStep.Tree, label, s"'${e.relativePath}' is not a regular file or directory")
    }

  // ---------------------------------------------------------------- 2-3. integrity, run manifest

  private def runIdOf(dir: Path): String =
    "run:" + dir.getFileName.toString.stripPrefix("run=")

  /**
   * Content checks of the Hadoop curated run (HD-P6-01 rules 2 and 4): [[CheckStep.RunManifest]],
   * then [[CheckStep.StageCounters]]. Every metadata file is read with at most `maxMetadataBytes`.
   * No data part is read (rules 7 and 8 content are not enforced: not consumed, not decoded).
   */
  private def curatedIntegrity(
      dir: Path,
      limits: ReaderLimits
  ): Either[Vector[Refusal], VerifiedRun] = {
    val label = "curated"
    def refuse(step: CheckStep, why: Vector[String]) =
      Left(why.map(w => Refusal(step, label, w)))
    val expected = runIdOf(dir)
    val manifest: Either[Vector[Refusal], (String, RunManifest)] =
      RunTree
        .readUtf8(dir.resolve(PartManifest.RunManifestFile), limits.maxMetadataBytes)
        .left
        .map(w => Vector(Refusal(CheckStep.RunManifest, label, w)))
        .flatMap { text =>
          Json.decode[RunManifest](text.stripSuffix("\n")) match {
            case Left(e) =>
              refuse(CheckStep.RunManifest, Vector(s"_run-manifest.json: ${e.mkString("; ")}"))
            case Right(m) =>
              val id = RunId.of(m).left.map(e => s"run ID: $e")
              val binding = id match {
                case Left(w) => Vector(w)
                case Right(i) if i != expected =>
                  Vector(s"the run manifest gives $i, the directory is $expected")
                case Right(_) => Vector.empty
              }
              val problems = binding ++ HadoopCuratedRun.manifestProblems(dir, m)
              if (problems.nonEmpty) refuse(CheckStep.RunManifest, problems)
              else Right((expected, m))
          }
        }
    manifest.flatMap { case (id, m) =>
      val counters = HadoopCuratedRun.Stages.flatMap { stage =>
        RunTree.readUtf8(
          dir.resolve(stage).resolve(HadoopCuratedRun.CountersFile),
          limits.maxMetadataBytes
        ) match {
          case Left(w) => Vector(w)
          case Right(text) => HadoopCuratedRun.countersProblems(stage, text)
        }
      }
      if (counters.nonEmpty) refuse(CheckStep.StageCounters, counters)
      else Right(VerifiedRun(label, dir, id, m, None))
    }
  }

  /** Part-manifest and run-manifest checks of the decision or grouping run (unchanged). */
  private def integrity(
      label: String,
      dir: Path,
      limits: ReaderLimits
  ): Either[Vector[Refusal], VerifiedRun] = {
    def refuse(step: CheckStep, why: String) = Left(Vector(Refusal(step, label, why)))
    val expected = runIdOf(dir)
    val partsFile = dir.resolve(PartManifest.FileName)
    val parts: Either[Vector[Refusal], Option[PartManifestV2]] =
      if (!RunTree.exists(partsFile)) refuse(CheckStep.PartManifest, "no _parts.json")
      else
        RunTree
          .readUtf8(partsFile, limits.maxMetadataBytes)
          .left
          .map(w => Vector(Refusal(CheckStep.PartManifest, label, w)))
          .flatMap { text =>
            PartManifests.decode(text) match {
              case Left(e) => refuse(CheckStep.PartManifest, s"_parts.json: ${e.mkString("; ")}")
              case Right(pm) =>
                val walked = RunTree.walk(dir, limits.maxTreeEntries, limits.maxTreeDepth)
                val observed = walked match {
                  case Left(why) => Vector(Left(why))
                  case Right(es) =>
                    es.filter(_.kind == TreeEntry.Kind.Regular)
                      .map(f => RunTree.observe(dir, f.relativePath))
                }
                observed.collectFirst { case Left(w) => w } match {
                  case Some(w) => refuse(CheckStep.PartManifest, w)
                  case None =>
                    val obs = observed.collect { case Right(o) => o }
                    val problems =
                      if (label == "grouping")
                        AnalysisValidation.checkGroupingRunParts(pm, expected, obs)
                      else PartManifests.verify(pm, expected, obs)
                    val v2 = PartManifests.asV2(pm)
                    val decisionParts =
                      if (
                        label == "decision" && problems.isEmpty &&
                        !v2.files.exists(_.recordSchemaVersion == SchemaVersions.MatchDecision)
                      )
                        Vector("no part of schema " + SchemaVersions.MatchDecision)
                      else Vector.empty
                    if (problems.nonEmpty || decisionParts.nonEmpty)
                      Left(
                        (problems.map(_.toString) ++ decisionParts)
                          .map(w => Refusal(CheckStep.PartManifest, label, w))
                      )
                    else Right(Some(v2))
                }
            }
          }
    parts.flatMap { pm =>
      RunTree
        .readUtf8(dir.resolve(PartManifest.RunManifestFile), limits.maxMetadataBytes)
        .left
        .map(w => Vector(Refusal(CheckStep.RunManifest, label, w)))
        .flatMap { text =>
          Json.decode[RunManifest](text.stripSuffix("\n")) match {
            case Left(e) =>
              refuse(CheckStep.RunManifest, s"_run-manifest.json: ${e.mkString("; ")}")
            case Right(m) =>
              RunId.of(m) match {
                case Left(e) => refuse(CheckStep.RunManifest, s"run ID: $e")
                case Right(id) if id != expected =>
                  refuse(
                    CheckStep.RunManifest,
                    s"the run manifest gives $id, the directory is $expected"
                  )
                case Right(id) =>
                  val bound = pm.toVector.flatMap(PartManifestV2.checkRunManifest(_, m))
                  if (bound.nonEmpty) refuse(CheckStep.RunManifest, bound.mkString("; "))
                  else Right(VerifiedRun(label, dir, id, m, pm))
              }
          }
        }
    }
  }

  // ---------------------------------------------------------------- 4. run chain

  private def chain(
      c: VerifiedRun,
      d: VerifiedRun,
      g: VerifiedRun
  ): Either[Vector[Refusal], Unit] = {
    def param(r: VerifiedRun, k: String) = r.manifest.parameters.get(k)
    def need(r: VerifiedRun, k: String, expected: String, what: String): Vector[Refusal] =
      param(r, k) match {
        case Some(v) if v == expected => Vector.empty
        case Some(v) =>
          Vector(
            Refusal(CheckStep.RunChain, r.label, s"parameter $k is $v, but the $what is $expected")
          )
        case None => Vector(Refusal(CheckStep.RunChain, r.label, s"parameter $k is missing"))
      }
    def snaps(r: VerifiedRun) = r.manifest.inputSnapshots.toSet
    val problems =
      need(g, Keys.DecisionRunId, d.runId, "decision run") ++
        need(g, Keys.InputRunId, c.runId, "curated run") ++
        need(d, DecisionInputRunKey, c.runId, "curated run") ++
        (if (snaps(g) != snaps(d))
           Vector(
             Refusal(
               CheckStep.RunChain,
               "grouping",
               "input snapshots differ from the decision run's"
             )
           )
         else Vector.empty) ++
        (if (snaps(d) != snaps(c))
           Vector(
             Refusal(
               CheckStep.RunChain,
               "decision",
               "input snapshots differ from the curated run's"
             )
           )
         else Vector.empty)
    if (problems.nonEmpty) Left(problems) else Right(())
  }

  // ---------------------------------------------------------------- 5. bound and decode

  private val Consumed: Vector[String] = Vector(
    SchemaVersions.PublicationTeam,
    SchemaVersions.WorkAssignment,
    SchemaVersions.ResearcherAssignment
  )

  private def bound(
      d: VerifiedRun,
      g: VerifiedRun,
      limits: ReaderLimits
  ): Either[Vector[Refusal], Unit] = {
    val rows = Consumed.map(g.rowsOf).sum + d.rowsOf(SchemaVersions.MatchDecision)
    val bytes = Consumed.map(g.bytesOf).sum + d.bytesOf(SchemaVersions.MatchDecision)
    def over(what: String, n: Long, max: Long) =
      if (n <= max) Vector.empty
      else
        Vector(
          Refusal(
            CheckStep.DriverBound,
            "grouping,decision",
            s"$n declared $what exceed the driver validation bound $max: not validated, so not computed"
          )
        )
    val problems = over("rows", rows, limits.maxDriverValidationRows) ++
      over("bytes", bytes, limits.maxDriverValidationBytes)
    if (problems.isEmpty) Right(()) else Left(problems)
  }

  /**
   * Decodes every row of `parts`; each part is read with at most its declared byte count (verified
   * at the part-manifest check), so a part that grew afterwards is refused, never read unbounded.
   */
  private def decodeAll[T](label: String, parts: Vector[(Path, PartFileV2)])(using
      c: RecordContract[T]
  ): Either[Vector[Refusal], Vector[T]] = {
    val results = parts.flatMap { case (p, listed) =>
      RunTree.readUtf8(p, listed.byteCount) match {
        case Left(w) => Vector(Left(Refusal(CheckStep.Decode, label, w)))
        case Right(text) =>
          RunTree.jsonlRows(text).zipWithIndex.map { case (row, i) =>
            Json
              .decode[T](row)
              .left
              .map(e =>
                Refusal(
                  CheckStep.Decode,
                  label,
                  s"${p.getFileName} row ${i + 1}: ${e.mkString("; ")}"
                )
              )
          }
      }
    }
    val bad = results.collect { case Left(r) => r }
    if (bad.nonEmpty) Left(bad.take(20)) else Right(results.collect { case Right(t) => t })
  }

  private def decode(
      d: VerifiedRun,
      g: VerifiedRun
  ): Either[Vector[Refusal], (GraphInput, Vector[MatchDecision])] =
    for {
      teams <- decodeAll[PublicationTeam]("grouping", g.listedOf(SchemaVersions.PublicationTeam))
      works <- decodeAll[WorkAssignment]("grouping", g.listedOf(SchemaVersions.WorkAssignment))
      researchers <- decodeAll[ResearcherAssignment](
        "grouping",
        g.listedOf(SchemaVersions.ResearcherAssignment)
      )
      decisions <- decodeAll[MatchDecision]("decision", d.listedOf(SchemaVersions.MatchDecision))
    } yield (GraphInput(teams, works, researchers), decisions)

  // ---------------------------------------------------------------- 6. core validators

  private def validate(
      input: GraphInput,
      decisions: Vector[MatchDecision],
      g: VerifiedRun
  ): Either[Vector[Refusal], Unit] = {
    val graph = AnalysisValidation.checkGraphInput(input, g.manifest)
    if (graph.nonEmpty) Left(graph.map(e => Refusal(CheckStep.GraphInput, "grouping", e.toString)))
    else {
      val identity =
        AnalysisValidation.checkIdentity(Vector.empty, input.researchers, decisions, g.manifest)
      if (identity.nonEmpty)
        Left(identity.map(e => Refusal(CheckStep.Identity, "grouping,decision", e.toString)))
      else Right(())
    }
  }
}
