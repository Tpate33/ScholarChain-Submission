package scholarchain.spark.input

import java.nio.file.{Path, Paths}

import scholarchain.core.*
import scholarchain.spark.store.{NioRunStore, RunStore, StorePath}

/**
 * The three run directories a collaboration analysis reads, from configuration (never built in):
 * the Hadoop curated input run, the entity-resolution decision run and the grouping run. Every path
 * is absolute, normalized and strictly inside `archiveRoot`, and the three are distinct.
 *
 * `pinnedCuratedRunId` is the one curated run (`run:<64 hex>`) to which the reader applies the
 * Hadoop curated-run contract ([[HadoopCuratedRun]], HD-P6-01 rules 1-11, human decision Option A).
 * CR-P6-21 n1: [[ArchiveRuns.of]], the only public constructor, always sets it to the committed
 * main-code constant [[HadoopCuratedRun.PinnedRunId]], so no caller can pass a directory's own run
 * ID and generalise the exception; a curated run with any other run ID is refused
 * (CheckStep.CuratedRun). The case class constructor (and so `apply` and `copy`) is private. The
 * only other way in is the `private[input]` synthetic-fixture seam
 * [[ArchiveRuns.forSyntheticFixture]], which no main code calls (test sources reach it through
 * their own `scholarchain.spark.input` helper).
 */
final case class ArchiveRuns private (
    archiveRoot: Path,
    curatedRun: Path,
    decisionRun: Path,
    groupingRun: Path,
    pinnedCuratedRunId: String
)

object ArchiveRuns {

  /**
   * Builds and checks the locations; the curated pin is [[HadoopCuratedRun.PinnedRunId]]. Left with
   * every problem.
   */
  def of(
      archiveRoot: String,
      curatedRun: String,
      decisionRun: String,
      groupingRun: String
  ): Either[Vector[String], ArchiveRuns] =
    build(archiveRoot, curatedRun, decisionRun, groupingRun, HadoopCuratedRun.PinnedRunId)

  /**
   * Synthetic-fixture seam only (synthetic curated runs have synthetic run IDs): as [[of]] with an
   * explicit pin. `private[input]`; never called by main code.
   */
  private[input] def forSyntheticFixture(
      archiveRoot: String,
      curatedRun: String,
      decisionRun: String,
      groupingRun: String,
      pinnedCuratedRunId: String
  ): Either[Vector[String], ArchiveRuns] =
    build(archiveRoot, curatedRun, decisionRun, groupingRun, pinnedCuratedRunId)

  private def build(
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

/**
 * The three run locations of an analysis in one [[RunStore]] (SP-P6-04): the same rules as
 * [[ArchiveRuns]] for locations given as URIs or absolute local paths ([[StorePath.parse]]: every
 * location absolute and normalized), each run strictly inside `archiveRoot` (same scheme and
 * authority), the three distinct. The curated pin is the committed constant
 * [[HadoopCuratedRun.PinnedRunId]] for every public constructor (CR-P6-21 n1); the case class
 * constructor (and so `apply` and `copy`) is private, and the synthetic-fixture seam
 * [[ArchiveInput.forSyntheticFixture]] is `private[input]`, as on [[ArchiveRuns]].
 */
final case class ArchiveInput private (
    store: RunStore,
    archiveRoot: StorePath,
    curatedRun: StorePath,
    decisionRun: StorePath,
    groupingRun: StorePath,
    pinnedCuratedRunId: String
)

object ArchiveInput {

  /** Builds and checks the locations in `store`; the curated pin is the committed constant. */
  def of(
      store: RunStore,
      archiveRoot: String,
      curatedRun: String,
      decisionRun: String,
      groupingRun: String
  ): Either[Vector[String], ArchiveInput] =
    build(store, archiveRoot, curatedRun, decisionRun, groupingRun, HadoopCuratedRun.PinnedRunId)

  /** Synthetic-fixture seam only, as [[ArchiveRuns.forSyntheticFixture]]; never main code. */
  private[input] def forSyntheticFixture(
      store: RunStore,
      archiveRoot: String,
      curatedRun: String,
      decisionRun: String,
      groupingRun: String,
      pinnedCuratedRunId: String
  ): Either[Vector[String], ArchiveInput] =
    build(store, archiveRoot, curatedRun, decisionRun, groupingRun, pinnedCuratedRunId)

  /** The local java.nio form of [[ArchiveRuns]] (already checked; its pin is kept). */
  private[input] def local(runs: ArchiveRuns): ArchiveInput =
    ArchiveInput(
      NioRunStore,
      StorePath.local(runs.archiveRoot),
      StorePath.local(runs.curatedRun),
      StorePath.local(runs.decisionRun),
      StorePath.local(runs.groupingRun),
      runs.pinnedCuratedRunId
    )

  private def build(
      store: RunStore,
      archiveRoot: String,
      curatedRun: String,
      decisionRun: String,
      groupingRun: String,
      pinnedCuratedRunId: String
  ): Either[Vector[String], ArchiveInput] = {
    val pinForm =
      if (
        pinnedCuratedRunId != null && HadoopCuratedRun.PinnedRunIdText.matches(pinnedCuratedRunId)
      )
        Vector.empty
      else Vector(s"pinnedCuratedRunId '$pinnedCuratedRunId' is not run:<64 lowercase hex>")
    val parsed = Vector(
      "archiveRoot" -> archiveRoot,
      "curatedRun" -> curatedRun,
      "decisionRun" -> decisionRun,
      "groupingRun" -> groupingRun
    ).map { case (l, s) =>
      if (s == null || s.isEmpty) Left(s"$l is not set")
      else StorePath.parse(s).left.map(w => s"$l: $w")
    }
    val storeProblem =
      if (store == null) Vector("no run store is given") else Vector.empty
    val errors = parsed.collect { case Left(e) => e } ++ pinForm ++ storeProblem
    if (errors.nonEmpty) Left(errors)
    else {
      val ps = parsed.collect { case Right(p) => p }
      val (root, c, d, g) = (ps(0), ps(1), ps(2), ps(3))
      val localOnly =
        if (!store.supportsAtomicDirectoryRename || ps.forall(_.isLocal)) Vector.empty
        else Vector(s"the store '${store.describe}' holds local locations only")
      val inside = Vector("curatedRun" -> c, "decisionRun" -> d, "groupingRun" -> g).collect {
        case (l, p) if !(p.startsWith(root) && p != root) => s"$l '$p' is not inside '$root'"
      }
      val distinct =
        if (Set(c, d, g).size != 3) Vector("the three runs must be different directories")
        else Vector.empty
      if (localOnly.nonEmpty || inside.nonEmpty || distinct.nonEmpty)
        Left(localOnly ++ inside ++ distinct)
      else Right(ArchiveInput(store, root, c, d, g, pinnedCuratedRunId))
    }
  }
}

/** One verified run: its location, run ID, run manifest and (when published) part manifest. */
final case class VerifiedRun(
    label: String,
    dir: StorePath,
    runId: String,
    manifest: RunManifest,
    partManifest: Option[PartManifestV2]
) {

  /** Locations of the listed parts of `recordSchemaVersion`, sorted by listed path. */
  def partsOf(recordSchemaVersion: String): Vector[StorePath] =
    partManifest.toVector
      .flatMap(_.files)
      .filter(_.recordSchemaVersion == recordSchemaVersion)
      .map(f => dir.resolve(f.path))

  /** The listed parts of `recordSchemaVersion` with their locations, sorted by listed path. */
  def listedOf(recordSchemaVersion: String): Vector[(StorePath, PartFileV2)] =
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
    limits: ReaderLimits,
    authorships: Vector[AuthorshipRecordV2],
    curatedLeaves: Map[String, CuratedLeaf],
    curatedSources: CuratedSources
) {
  def teamParts: Vector[StorePath] = grouping.partsOf(SchemaVersions.PublicationTeam)
  def workParts: Vector[StorePath] = grouping.partsOf(SchemaVersions.WorkAssignment)

  /** The verified part files of one consumed curated leaf (none when the leaf is absent). */
  def curatedPartsOf(leaf: String): Vector[StorePath] =
    curatedLeaves.get(leaf).map(_.parts).getOrElse(Vector.empty)

  /** The declared (and decoded) rows of one consumed curated leaf. */
  def curatedRowsOf(leaf: String): Long = curatedLeaves.get(leaf).map(_.declaredRows).getOrElse(0L)

  /** The input of `CanonicalGraph.expected` and `CanonicalGraphValidation.checkTables`. */
  def canonicalInput: CanonicalGraphInput = CanonicalGraphInput(
    works = graphInput.works,
    researchers = graphInput.researchers,
    authorships = authorships,
    sourceWorks = curatedSources.works,
    mentions = curatedSources.mentions,
    venues = curatedSources.venues,
    institutions = curatedSources.institutions,
    topics = curatedSources.topics
  )
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
 *        non-negative integers below 2^53, and the `EMITTED.<leaf>` rows of the consumed normalize
 *        leaves; rule 4). The curated run publishes no part manifest (one is refused by rule 3);
 *        only the consumed leaves' parts are opened, at step 7;
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
 *      any data row is read. Step 2B adds the authorship parts and the consumed curated leaves (the
 *      rows their `EMITTED.<leaf>` counters declare, checked at [[CheckStep.StageCounters]], and
 *      their compressed bytes);
 *   5. [[CheckStep.Decode]] of teams, work and researcher assignments, authorships (step 2B) and
 *      decisions through `Json.decode`, each part read with at most its declared bytes;
 *   6. [[CheckStep.GraphInput]] `AnalysisValidation.checkGraphInput`, then [[CheckStep.Identity]]
 *      `AnalysisValidation.checkIdentity` (no metrics yet: it checks the run binding of every
 *      researcher assignment and decision);
 *   7. [[CheckStep.CuratedParts]] (step 2B): the consumed curated parts ([[CuratedParts]]: crc
 *      sidecar verified, gzip within the remaining byte budget, rows decoded, rows equal the
 *      counters);
 *   8. [[CheckStep.CanonicalInput]] (step 2B): `CanonicalGraph.expected` accepts the curated and
 *      grouping records (no duplicate key, every representative has a curated work record, every
 *      authorship on a representative and agreeing with its assignment, every curated mention
 *      assigned, one name per venue, institution and topic ID).
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

  /** Reads the local (java.nio) archive runs: the behaviour before SP-P6-04. */
  def read(runs: ArchiveRuns, limits: ReaderLimits): Either[Vector[Refusal], VerifiedInput] =
    read(ArchiveInput.local(runs), limits)

  /**
   * Reads the archive runs of `runs` through its store (local java.nio, HDFS, S3A, `file:` URIs
   * through Hadoop): the same checks in the same order.
   */
  def read(runs: ArchiveInput, limits: ReaderLimits): Either[Vector[Refusal], VerifiedInput] = {
    val store = runs.store
    val layoutRefusals =
      curatedLayout(store, runs.curatedRun, runs.pinnedCuratedRunId, limits) ++
        layout(store, "decision", runs.decisionRun, limits) ++
        layout(store, "grouping", runs.groupingRun, limits)
    if (layoutRefusals.nonEmpty) Left(layoutRefusals)
    else
      for {
        cl <- curatedIntegrity(store, runs.curatedRun, limits)
        d <- integrity(store, "decision", runs.decisionRun, limits)
        g <- integrity(store, "grouping", runs.groupingRun, limits)
        _ <- chain(cl._1, d, g)
        budget <- bound(store, d, g, cl._2, limits)
        decoded <- decode(store, d, g)
        _ <- validate(decoded._1, decoded._2, g)
        sources <- CuratedParts
          .read(cl._2, budget, limits.maxMetadataBytes, store)
          .left
          .map(_.map(w => Refusal(CheckStep.CuratedParts, "curated", w)))
        v = VerifiedInput(
          cl._1,
          d,
          g,
          decoded._1,
          decoded._2,
          limits,
          decoded._3,
          cl._2,
          sources
        )
        _ <- CanonicalGraph
          .expected(v.canonicalInput, Vector.empty)
          .left
          .map(
            _.take(20).map(e => Refusal(CheckStep.CanonicalInput, "curated,grouping", e.toString))
          )
      } yield v
  }

  // ---------------------------------------------------------------- 1. layout

  /**
   * Layout refusals of one published (decision, grouping or analysis) run, in check order; no file
   * content is read. Unchanged by step 1A repair 02.
   */
  def layout(label: String, dir: Path, limits: ReaderLimits): Vector[Refusal] =
    layout(NioRunStore, label, StorePath.local(dir), limits)

  /** As the `Path` overload, for a run location in `store`. */
  def layout(
      store: RunStore,
      label: String,
      dir: StorePath,
      limits: ReaderLimits
  ): Vector[Refusal] =
    layoutOf(
      store,
      label,
      dir,
      limits.maxTreeEntries,
      limits.maxTreeDepth,
      pinnedCuratedRunId = None
    )

  /**
   * Layout refusals of the Hadoop curated run, in check order; no file content is read. HD-P6-01
   * rules 1 and 3-11 ([[HadoopCuratedRun]]): the run ID must be `pinnedCuratedRunId`, no top-level
   * `_SUCCESS` is required (one is refused), and the NOFOLLOW walk is bounded by `maxTreeEntries`
   * and the contract depths (a directory at depth [[HadoopCuratedRun.MaxDirDepth]] + 1 stops the
   * walk, so files lie at most [[HadoopCuratedRun.MaxFileDepth]] levels below the run).
   */
  def curatedLayout(dir: Path, pinnedCuratedRunId: String, limits: ReaderLimits): Vector[Refusal] =
    curatedLayout(NioRunStore, StorePath.local(dir), pinnedCuratedRunId, limits)

  /** As the `Path` overload, for a curated run location in `store`. */
  def curatedLayout(
      store: RunStore,
      dir: StorePath,
      pinnedCuratedRunId: String,
      limits: ReaderLimits
  ): Vector[Refusal] =
    layoutOf(
      store,
      "curated",
      dir,
      limits.maxTreeEntries,
      HadoopCuratedRun.MaxDirDepth + 1,
      Some(pinnedCuratedRunId)
    )

  private def layoutOf(
      store: RunStore,
      label: String,
      dir: StorePath,
      maxEntries: Int,
      maxDepth: Int,
      pinnedCuratedRunId: Option[String]
  ): Vector[Refusal] = {
    val name = dir.name
    lazy val location =
      pinnedCuratedRunId.toVector.flatMap(pin => HadoopCuratedRun.locationProblems(dir, pin))
    if (!RunTree.RunDirName.matches(name))
      Vector(Refusal(CheckStep.RunName, label, s"'$name' is not run=<64 lowercase hex>"))
    else if (location.nonEmpty) location.map(w => Refusal(CheckStep.CuratedRun, label, w))
    else if (!RunTree.isRealDirectory(store, dir))
      Vector(Refusal(CheckStep.Tree, label, s"$dir is not a real directory (missing or a link)"))
    else {
      val walked = RunTree.walk(store, dir, maxEntries, maxDepth)
      val entries = walked.getOrElse(Vector.empty)
      val unverified =
        if (RunTree.exists(store, dir.resolve(RunTree.UnverifiedMarker)))
          Vector(Refusal(CheckStep.UnverifiedMarker, label, "_UNVERIFIED is present"))
        else Vector.empty
      // The parent must be listable: an unlistable parent is refused, never read as empty (F5).
      val siblings: Vector[Either[String, String]] = dir.parent match {
        case None => Vector(Left(s"$dir has no parent directory"))
        case Some(parent) =>
          RunTree.names(store, parent) match {
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
        else if (RunTree.isRegularFile(store, dir.resolve(PartManifest.SuccessMarker)))
          Vector.empty
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
            .structureProblems(store, dir, es)
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

  private def runIdOf(dir: StorePath): String =
    "run:" + dir.name.stripPrefix("run=")

  /**
   * Content checks of the Hadoop curated run (HD-P6-01 rules 2 and 4): [[CheckStep.RunManifest]],
   * then [[CheckStep.StageCounters]]. Every metadata file is read with at most `maxMetadataBytes`.
   * No data part is read (rules 7 and 8 content are not enforced: not consumed, not decoded).
   */
  private def curatedIntegrity(
      store: RunStore,
      dir: StorePath,
      limits: ReaderLimits
  ): Either[Vector[Refusal], (VerifiedRun, Map[String, CuratedLeaf])] = {
    val label = "curated"
    def refuse(step: CheckStep, why: Vector[String]) =
      Left(why.map(w => Refusal(step, label, w)))
    val expected = runIdOf(dir)
    val manifest: Either[Vector[Refusal], (String, RunManifest)] =
      RunTree
        .readUtf8(store, dir.resolve(PartManifest.RunManifestFile), limits.maxMetadataBytes)
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
      val texts = HadoopCuratedRun.Stages.map { stage =>
        stage -> RunTree.readUtf8(
          store,
          dir.resolve(stage).resolve(HadoopCuratedRun.CountersFile),
          limits.maxMetadataBytes
        )
      }
      val counters = texts.flatMap {
        case (_, Left(w)) => Vector(w)
        case (stage, Right(text)) => HadoopCuratedRun.countersProblems(stage, text)
      }
      if (counters.nonEmpty) refuse(CheckStep.StageCounters, counters)
      else {
        // SP-P6-03 step 2B: the rows the normalize counters declare for the consumed leaves.
        val normalize = texts.collectFirst { case ("normalize", Right(t)) => t }.getOrElse("")
        val leaves = for {
          parts <- CuratedParts.listing(store, dir)
          rows <- HadoopCuratedRun.emittedRows(normalize, leaf => parts(leaf).nonEmpty)
        } yield HadoopCuratedRun.ConsumedLeaves.map { leaf =>
          leaf -> CuratedLeaf(leaf, parts(leaf), rows(leaf))
        }.toMap
        leaves match {
          case Left(w) => refuse(CheckStep.StageCounters, w)
          case Right(ls) => Right((VerifiedRun(label, dir, id, m, None), ls))
        }
      }
    }
  }

  /** Part-manifest and run-manifest checks of the decision or grouping run (unchanged). */
  private def integrity(
      store: RunStore,
      label: String,
      dir: StorePath,
      limits: ReaderLimits
  ): Either[Vector[Refusal], VerifiedRun] = {
    def refuse(step: CheckStep, why: String) = Left(Vector(Refusal(step, label, why)))
    val expected = runIdOf(dir)
    val partsFile = dir.resolve(PartManifest.FileName)
    val parts: Either[Vector[Refusal], Option[PartManifestV2]] =
      if (!RunTree.exists(store, partsFile)) refuse(CheckStep.PartManifest, "no _parts.json")
      else
        RunTree
          .readUtf8(store, partsFile, limits.maxMetadataBytes)
          .left
          .map(w => Vector(Refusal(CheckStep.PartManifest, label, w)))
          .flatMap { text =>
            PartManifests.decode(text) match {
              case Left(e) => refuse(CheckStep.PartManifest, s"_parts.json: ${e.mkString("; ")}")
              case Right(pm) =>
                val walked =
                  RunTree.walk(store, dir, limits.maxTreeEntries, limits.maxTreeDepth)
                val observed = walked match {
                  case Left(why) => Vector(Left(why))
                  case Right(es) =>
                    es.filter(_.kind == TreeEntry.Kind.Regular)
                      .map(f => RunTree.observe(store, dir, f.relativePath))
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
        .readUtf8(store, dir.resolve(PartManifest.RunManifestFile), limits.maxMetadataBytes)
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
    SchemaVersions.ResearcherAssignment,
    SchemaVersions.AuthorshipRecordV2
  )

  /**
   * The driver bound over every consumed dataset, before any data row is read: the declared rows of
   * the grouping and decision parts plus the rows the curated counters declare for the consumed
   * leaves, and their declared bytes plus the compressed curated bytes. Returns the decompressed
   * byte budget left for the curated parts (`maxDriverValidationBytes` minus the declared bytes of
   * the grouping and decision parts).
   */
  private def bound(
      store: RunStore,
      d: VerifiedRun,
      g: VerifiedRun,
      leaves: Map[String, CuratedLeaf],
      limits: ReaderLimits
  ): Either[Vector[Refusal], Long] = {
    val declaredBytes = Consumed.map(g.bytesOf).sum + d.bytesOf(SchemaVersions.MatchDecision)
    val rows = Consumed.map(g.rowsOf).sum + d.rowsOf(SchemaVersions.MatchDecision) +
      leaves.values.map(_.declaredRows).sum
    val bytes = declaredBytes + leaves.values.map(_.compressedBytes(store)).sum
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
    if (problems.isEmpty) Right(limits.maxDriverValidationBytes - declaredBytes)
    else Left(problems)
  }

  /**
   * Decodes every row of `parts`; each part is read with at most its declared byte count (verified
   * at the part-manifest check), so a part that grew afterwards is refused, never read unbounded.
   */
  private def decodeAll[T](
      store: RunStore,
      label: String,
      parts: Vector[(StorePath, PartFileV2)]
  )(using
      c: RecordContract[T]
  ): Either[Vector[Refusal], Vector[T]] = {
    val results = parts.flatMap { case (p, listed) =>
      RunTree.readUtf8(store, p, listed.byteCount) match {
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
      store: RunStore,
      d: VerifiedRun,
      g: VerifiedRun
  ): Either[Vector[Refusal], (GraphInput, Vector[MatchDecision], Vector[AuthorshipRecordV2])] =
    for {
      teams <- decodeAll[PublicationTeam](
        store,
        "grouping",
        g.listedOf(SchemaVersions.PublicationTeam)
      )
      works <- decodeAll[WorkAssignment](
        store,
        "grouping",
        g.listedOf(SchemaVersions.WorkAssignment)
      )
      researchers <- decodeAll[ResearcherAssignment](
        store,
        "grouping",
        g.listedOf(SchemaVersions.ResearcherAssignment)
      )
      authorships <- decodeAll[AuthorshipRecordV2](
        store,
        "grouping",
        g.listedOf(SchemaVersions.AuthorshipRecordV2)
      )
      decisions <- decodeAll[MatchDecision](
        store,
        "decision",
        d.listedOf(SchemaVersions.MatchDecision)
      )
    } yield (GraphInput(teams, works, researchers), decisions, authorships)

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
