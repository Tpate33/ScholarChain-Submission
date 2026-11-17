package scholarchain.spark.input

import java.nio.file.Path

import scala.collection.mutable.ArrayBuffer
import scala.util.control.NonFatal

import scholarchain.core.*

/**
 * The Hadoop curated-run contract the verified reader applies to the one pinned curated dev run
 * (work order SP-P6-02 step 1A repair 02; human decision Option A,
 * docs/agent-runs/phase-06/prompt-curated-compat-option-a.md). The rules are the producer contract
 * HD-P6-01 rules 1-11 (docs/agent-runs/phase-06/hd-p6-01-report.md) and match, rule for rule, the
 * structural checks of build-engineer's `sbt sparkIT` preflight (`project/SparkItRuns.scala`
 * `curatedRunProblems`, BE-P6-04). The reader also enforces the content parts the preflight leaves
 * to it (rules 2 and 4). sparkAnalytics never imports the hadoop module: the producer's names are
 * restated here, each with its source.
 *
 * Rules (S = structural, no content read; C = content, bounded by `maxMetadataBytes`):
 *   1. S: the run directory is `run=<64 hex>` (CheckStep.RunName), its run ID equals the committed
 *      pin [[PinnedRunId]] (CR-P6-21 n1: the pin is a main-code constant, never supplied by a
 *      production caller and never inferred from the input; [[ArchiveRuns.of]] always uses it), and
 *      it lies in `snapshot=<token>/` under `hadoop/` (CheckStep.CuratedRun); not a symbolic link
 *      (CheckStep.Tree).
 *   2. S: `_run-manifest.json` is a non-empty regular file with its crc (CheckStep.HadoopLayout).
 *      C: it decodes strictly through the core RunManifest contract (`Json.decode`: schema version,
 *      structural decode, field validation), `stage == "hadoop.pipeline"`, `RunId.of(manifest)`
 *      equals the directory's run ID, and the manifest has exactly one DBLP snapshot, equal to the
 *      `snapshot=` token (CheckStep.RunManifest).
 *   3. S: the run directory holds exactly `_run-manifest.json`, its crc and the five stage
 *      directories; a run-level `_SUCCESS` is refused (the producer never writes one).
 *   4. S: each stage holds a regular `_SUCCESS` of size 0 and a non-empty regular `_counters.json`,
 *      their crcs, and only its listed subdirectories. C: `_counters.json` is a JSON object whose
 *      `stage` is the stage's name and whose `counters` object holds each of the ten required
 *      counters as a non-negative integer below 2^53 (CheckStep.StageCounters). For the consumed
 *      normalize leaves ([[ConsumedLeaves]]) the normalize counters must also declare the rows:
 *      `EMITTED.<leaf>` present (same integer rule) exactly when the leaf holds a part file, and
 *      absent or 0 when the leaf directory is absent ([[emittedRows]]).
 *   5. S: blocking holds round-0 .. round-(k-1), contiguous, 1 <= k <= [[MaxBlockingRounds]]; for r
 *      + 1 < [[MaxBlockingRounds]], round-(r+1) is present if and only if round-r/spill holds a
 *      part file (CR-P6-21 m1; the same rule as the preflight, project/SparkItRuns.scala:869-880).
 *   6. S: candidates/join is present; candidates/pairs is present if and only if join/joined holds
 *      a part file; candidates/final is present if and only if pairs/pairs holds a part file
 *      (CR-P6-21 m1; preflight project/SparkItRuns.scala:889-903).
 *   7. S: leaves hold only non-empty regular `part-[mr]-[0-9]{5,}.gz` files, `part-m` only in a
 *      `rejects` leaf. C (SP-P6-03 step 2B): enforced for the consumed leaves only
 *      ([[ConsumedLeaves]], [[CuratedParts]]): each part decompresses (gzip) within the driver byte
 *      budget, is strict UTF-8 JSON lines, every row decodes through its core contract, and the
 *      decoded rows equal `EMITTED.<leaf>`. Parts of other leaves are never opened.
 *   8. S: the only hidden entry is `.<X>.crc` (a regular file) for a regular, non-hidden sibling X,
 *      and every regular non-hidden file has one; `.DS_Store`, `._*` and every other hidden entry
 *      are refused. C (step 2B): the crc sidecar of every consumed part is verified against the
 *      part's bytes ([[HadoopChecksum]], the Hadoop ChecksumFileSystem CRC32 layout); other crc
 *      sidecars are not compared.
 *   9. S: files at most [[MaxFileDepth]] and directories at most [[MaxDirDepth]] levels below the
 *      run directory, at most `maxTreeEntries` entries (the bounded NOFOLLOW walk, CheckStep.Tree).
 *   10. S: `_temporary`, `_IN_PROGRESS`, `_FAILED` and `.staging*` are refused at any level, and
 *       `_SUCCESS` outside a stage directory; symbolic links and special files are refused
 *       (CheckStep.Tree).
 *   11. S: an empty leaf (no part file) or an empty blocks/ or members/ directory is refused; an
 *       empty job output directory (round-<r>, join, pairs, final) is accepted.
 *   12. Not checked: the archive holds the run directory only, not its attempt directory; the
 *       attempt-level success is recorded separately (prompt-curated-compat-option-a.md).
 *
 * Every structural rule not given another step above is reported as CheckStep.HadoopLayout. Entries
 * the reader already refuses by another layout step (`.staging-*` and `.unverified-*` by
 * CheckStep.StagingEntry, the run-level `_UNVERIFIED` by CheckStep.UnverifiedMarker, symbolic links
 * and special files by CheckStep.Tree) are not reported a second time here.
 */
object HadoopCuratedRun {

  /** The producer's run manifest stage (modules/hadoop Pipeline.scala `ManifestStage`). */
  val ManifestStage = "hadoop.pipeline"

  /** modules/hadoop Pipeline.scala `CountersFile`. */
  val CountersFile = "_counters.json"

  /** The five stage directories, in order (modules/hadoop Pipeline.scala `Stage`). */
  val Stages: Vector[String] = Vector("normalize", "exact-ids", "teams", "blocking", "candidates")

  /**
   * The ten required base counters, in the producer's order (modules/hadoop Counters.scala:26-37
   * `Counters.Required`; restated because sparkAnalytics must not import hadoop).
   */
  val RequiredCounters: Vector[String] = Vector(
    "INPUT",
    "ACCEPTED",
    "REJECTED",
    "DUPLICATE",
    "CONFLICT",
    "OVERSIZE_BLOCK",
    "OVERSIZE_TEAM",
    "EMITTED_PAIR",
    "MISSING_KEY",
    "SCHEMA_FAILURE"
  )

  /** HD-P6-01 rule 9 (producer layout, not a tunable threshold). */
  val MaxFileDepth: Int = 5
  val MaxDirDepth: Int = 4

  /** HD-P6-01 rule 5: round-0 .. round-3 (entity-resolution BlockingKeys.Levels = 4). */
  val MaxBlockingRounds: Int = 4

  /**
   * The one pinned Hadoop curated dev run (human decision Option A,
   * docs/agent-runs/phase-06/prompt-curated-compat-option-a.md; the same run as build-engineer's
   * `SparkItRuns.PinnedCuratedRunHex`, BE-P6-04). Committed main-code constant (CR-P6-21 n1): the
   * HD-P6-01 contract applies to this run only, and [[ArchiveRuns.of]] gives no caller a way to
   * pass another run ID (the synthetic-fixture seam is `private[input]`). It equals `RunId.of` of
   * the committed dev manifest `modules/hadoop/local-runs/er-candidates.dev.run-manifest.json`
   * (checked by a test that restates that manifest by hand).
   */
  val PinnedRunId: String =
    "run:2881724b5c86b561a235836d51946fbd884beed36a14f929b27c278cb5de8baa"

  /**
   * The normalize leaves an analysis consumes (SP-P6-03 step 2B, the curated records of
   * `CanonicalGraphInput`), in this order: works (`work-record.v3`), mentions
   * (`author-mention.v1`), venues, institutions, topics. Restated from modules/hadoop
   * NormalizeJob.scala (`kind.output` of the canonical record reducer and `NamedOutputs.Mentions`).
   */
  val ConsumedLeaves: Vector[String] =
    Vector("works", "mentions", "venues", "institutions", "topics")

  /**
   * The normalize counter declaring a leaf's rows (modules/hadoop `Counters.inc2(Emitted, out)`).
   */
  def emittedCounter(leaf: String): String = s"EMITTED.$leaf"

  /** The parent of the snapshot directory (modules/hadoop Pipeline.scala `RunLayout.of`). */
  val HadoopDir = "hadoop"
  val SnapshotDirName = "^snapshot=([A-Za-z0-9._-]+)$".r
  val PinnedRunIdText = "^run:[0-9a-f]{64}$".r

  private val SuccessMarker = PartManifest.SuccessMarker
  private val ManifestFile = PartManifest.RunManifestFile
  private val Rejects = "rejects"
  private val DsStore = ".DS_Store"
  private[input] val PartName = "^part-([mr])-[0-9]{5,}\\.gz$".r
  private val CrcName = "^\\.(.+)\\.crc$".r
  private val RoundName = "^round-(0|[1-9][0-9]*)$".r
  private val IncompleteMarkers: Set[String] = Set("_temporary", "_IN_PROGRESS", "_FAILED")
  private val StagingPrefix = ".staging"

  // ---------------------------------------------------------------- rule 1

  /** The `snapshot=` token of the curated run's parent directory, when it has one. */
  def snapshotToken(dir: Path): Option[String] =
    Option(dir.getParent).flatMap(p => Option(p.getFileName)).map(_.toString).flatMap {
      case SnapshotDirName(t) => Some(t)
      case _ => None
    }

  /**
   * Rule 1 beyond the run-name check: the directory's run ID is `pinnedRunId` (supplied by the
   * caller from a committed constant) and the run lies in `hadoop/snapshot=<token>/`.
   */
  def locationProblems(dir: Path, pinnedRunId: String): Vector[String] = {
    val name = Option(dir.getFileName).map(_.toString).getOrElse("")
    val runId = "run:" + name.stripPrefix("run=")
    val pin =
      if (!PinnedRunIdText.matches(pinnedRunId))
        Vector(s"the pinned curated run ID '$pinnedRunId' is not run:<64 lowercase hex>")
      else if (runId != pinnedRunId)
        Vector(
          s"$runId is not the pinned curated run $pinnedRunId; the HD-P6-01 curated-run contract " +
            "(human decision Option A) applies to that one run only and no other curated run is " +
            "accepted"
        )
      else Vector.empty
    val snapshot =
      if (snapshotToken(dir).isDefined) Vector.empty
      else Vector(s"the parent of $dir is not a snapshot=<token> directory (HD-P6-01 rule 1)")
    val hadoop = Option(dir.getParent).flatMap(p => Option(p.getParent)) match {
      case Some(g) if Option(g.getFileName).map(_.toString).contains(HadoopDir) => Vector.empty
      case _ => Vector(s"the snapshot directory of $dir is not under $HadoopDir/ (HD-P6-01 rule 1)")
    }
    pin ++ snapshot ++ hadoop
  }

  // ---------------------------------------------------------------- rules 2-8, 10, 11

  private enum Node {
    case File(size: Long)
    case Dir

    /** A symbolic link or special file (refused by CheckStep.Tree), or a file without a size. */
    case Other
  }

  /**
   * Structural refusals (rules 2-8, 10 and 11) of the walked entries of the curated run `dir`. The
   * caller has already refused symbolic links and special files (CheckStep.Tree), and staging and
   * `_UNVERIFIED` entries; they take no further part here. Sizes come from a NOFOLLOW stat; no file
   * content is read.
   */
  def structureProblems(dir: Path, entries: Vector[TreeEntry]): Vector[String] = {
    val p = ArrayBuffer.empty[String]
    val nodes: Map[Vector[String], Node] = entries.flatMap { e =>
      val segs = e.relativePath.split('/').toVector
      val last = segs.last
      if (RunTree.isStagingName(last)) None
      else if (segs.size == 1 && last == RunTree.UnverifiedMarker) None
      else
        Some(segs -> (e.kind match {
          case TreeEntry.Kind.Directory => Node.Dir
          case TreeEntry.Kind.Regular =>
            RunTree.sizeOf(dir.resolve(e.relativePath)) match {
              case Some(n) => Node.File(n)
              case None =>
                p += s"'${e.relativePath}' cannot be stat'ed (NOFOLLOW)"
                Node.Other
            }
          case _ => Node.Other
        }))
    }.toMap
    p ++= layoutProblems(nodes)
    p.toVector
  }

  private def layoutProblems(entries: Map[Vector[String], Node]): Vector[String] = {
    val p = ArrayBuffer.empty[String]
    val kids: Map[Vector[String], Map[String, Node]] =
      entries.toVector.groupBy(_._1.init).map { case (k, v) =>
        k -> v.map { case (s, n) => s.last -> n }.toMap
      }
    def at(d: Vector[String]): Map[String, Node] = kids.getOrElse(d, Map.empty)
    def show(s: Vector[String]): String =
      if (s.isEmpty) "the run directory" else s"'${s.mkString("/")}'"
    def isPart(n: String, e: Node): Boolean = e match {
      case Node.File(sz) => sz > 0 && PartName.matches(n)
      case _ => false
    }
    def hasPart(d: Vector[String]): Boolean = at(d).exists { case (n, e) => isPart(n, e) }

    // Rules 8 and 10 on every judged directory; returns its ordinary entries (not hidden, not a
    // rule-10 marker, not a misplaced _SUCCESS).
    def screen(d: Vector[String], stage: Boolean): Map[String, Node] = {
      val ch = at(d)
      val regular: Set[String] =
        ch.collect { case (n, Node.File(_)) if !n.startsWith(".") => n }.toSet
      ch.toVector.sortBy(_._1).foreach { case (n, e) =>
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
              if (e == Node.Dir) p += s"$q is a checksum sidecar that is not a regular file"
            case CrcName(x) =>
              p += s"$q is a checksum sidecar without a regular sibling '$x' (HD-P6-01 rule 8)"
            case _ =>
              p += s"$q is a hidden entry other than a .<name>.crc checksum sidecar " +
                "(HD-P6-01 rule 8)"
          }
      }
      regular.toVector.sorted.foreach { x =>
        ch.get(s".$x.crc") match {
          case Some(Node.File(_)) | Some(Node.Other) => ()
          case _ => p += s"${show(d :+ x)} has no checksum sidecar .$x.crc (HD-P6-01 rule 8)"
        }
      }
      ch.filter { case (n, _) =>
        !n.startsWith(".") && !IncompleteMarkers(n) && !(n == SuccessMarker && !stage)
      }
    }

    type Handler = Vector[String] => Unit

    def dirsOnly(d: Vector[String], o: Map[String, Node], allowed: Map[String, Handler]): Unit =
      o.toVector.sortBy(_._1).foreach { case (n, e) =>
        val q = show(d :+ n)
        allowed.get(n) match {
          case None =>
            if (e != Node.Other) p += s"$q is an unexpected entry (HD-P6-01 rules 3, 4)"
          case Some(f) =>
            e match {
              case Node.Dir => f(d :+ n)
              case Node.Other => ()
              case _ => p += s"$q is not a directory (HD-P6-01 rule 4)"
            }
        }
      }

    def leaf(allowM: Boolean)(d: Vector[String]): Unit = {
      val o = screen(d, stage = false)
      if (!hasPart(d))
        p += s"${show(d)} holds no part file (an empty leaf output directory, HD-P6-01 rule 11)"
      o.toVector.sortBy(_._1).foreach { case (n, e) =>
        val q = show(d :+ n)
        (n, e) match {
          case (_, Node.Other) => ()
          case (_, Node.Dir) =>
            p += s"$q is a directory inside a leaf output directory (HD-P6-01 rules 4, 7)"
          case (PartName(kind), Node.File(sz)) =>
            if (kind == "m" && !allowM)
              p += s"$q is a map-side part-m file outside a rejects directory (HD-P6-01 rule 7)"
            if (sz <= 0) p += s"$q is an empty part file (HD-P6-01 rule 7)"
          case _ => p += s"$q is not a part-[mr]-NNNNN.gz file (HD-P6-01 rule 7)"
        }
      }
    }
    def leaves(names: String*): Map[String, Handler] =
      names.map(n => n -> ((d: Vector[String]) => leaf(n == Rejects)(d))).toMap

    def entityParent(d: Vector[String]): Unit = {
      val o = screen(d, stage = false)
      if (o.isEmpty)
        p += s"${show(d)} is an empty output directory without researcher/ or work/ " +
          "(HD-P6-01 rule 11)"
      dirsOnly(d, o, leaves("researcher", "work"))
    }

    def jobOut(allowed: Map[String, Handler])(d: Vector[String]): Unit =
      dirsOnly(d, screen(d, stage = false), allowed)

    val roundDir: Handler = jobOut(
      Map[String, Handler]("blocks" -> entityParent, "members" -> entityParent) ++
        leaves("spill", Rejects, "stats")
    )

    def stageDir(allowed: Map[String, Handler])(d: Vector[String]): Unit = {
      val o = screen(d, stage = true)
      o.get(SuccessMarker) match {
        case None =>
          p += s"${show(d :+ SuccessMarker)} is missing (the stage is not committed; HD-P6-01 " +
            "rules 4, 10)"
        case Some(Node.File(0L)) | Some(Node.Other) => ()
        case Some(Node.File(sz)) =>
          p += s"${show(d :+ SuccessMarker)} is not empty ($sz bytes; HD-P6-01 rule 4)"
        case Some(Node.Dir) =>
          p += s"${show(d :+ SuccessMarker)} is not a regular file (HD-P6-01 rule 4)"
      }
      o.get(CountersFile) match {
        case None => p += s"${show(d :+ CountersFile)} is missing (HD-P6-01 rule 4)"
        case Some(Node.File(0L)) => p += s"${show(d :+ CountersFile)} is empty (HD-P6-01 rule 4)"
        case Some(Node.File(_)) | Some(Node.Other) => ()
        case Some(Node.Dir) =>
          p += s"${show(d :+ CountersFile)} is not a regular file (HD-P6-01 rule 4)"
      }
      dirsOnly(d, o - SuccessMarker - CountersFile, allowed)
    }

    // Rule 5: contiguous blocking rounds, round r + 1 present if and only if round r spills.
    def blocking(d: Vector[String]): Unit = {
      val rounds = at(d).keys.toVector.collect { case n @ RoundName(i) =>
        (n, i.toIntOption.getOrElse(Int.MaxValue))
      }
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
      // CR-P6-21 m1, as build-engineer's preflight (project/SparkItRuns.scala:869-880): the
      // producer runs round r + 1 if and only if round r's spill holds a non-empty part file and
      // r + 1 is below the last level (modules/hadoop Pipeline.scala runBlocking :516-521,
      // nonEmptyDirs :417-423).
      val present = idx.toSet
      def spillHasPart(i: Int): Boolean = hasPart(d ++ Vector(s"round-$i", "spill"))
      idx.foreach { i =>
        if (i > 0 && present(i - 1) && !spillHasPart(i - 1))
          p += s"${show(d :+ s"round-$i")} exists although round-${i - 1}/spill holds no part " +
            "file (the producer never runs that round; HD-P6-01 rule 5)"
        if (i + 1 < MaxBlockingRounds && spillHasPart(i) && !present(i + 1))
          p += s"${show(d ++ Vector(s"round-$i", "spill"))} holds a part file but round-${i + 1} " +
            "is missing (the producer always runs the next round; truncated layout, HD-P6-01 " +
            "rule 5)"
      }
      stageDir(rounds.map { case (n, _) => n -> roundDir }.toMap)(d)
    }

    // Rule 6: the candidate jobs, each present if and only if its input holds a part file.
    def candidates(d: Vector[String]): Unit = {
      val ch = at(d)
      if (!ch.contains("join"))
        p += s"${show(d)} lacks join/ (the join job always runs; HD-P6-01 rule 6)"
      // CR-P6-21 m1, as the preflight (project/SparkItRuns.scala:889-903): the producer runs the
      // pairs job if and only if join/joined holds a non-empty part file, and the final job if and
      // only if pairs/pairs does (modules/hadoop Pipeline.scala runCandidates :646-666).
      val joinedParts = hasPart(d ++ Vector("join", "joined"))
      val pairsParts = hasPart(d ++ Vector("pairs", "pairs"))
      if (ch.contains("pairs") && !joinedParts)
        p += s"${show(d :+ "pairs")} exists although join/joined holds no part file " +
          "(HD-P6-01 rule 6)"
      if (!ch.contains("pairs") && joinedParts)
        p += s"${show(d ++ Vector("join", "joined"))} holds a part file but pairs/ is missing " +
          "(the producer always runs the pairs job then; truncated layout, HD-P6-01 rule 6)"
      if (ch.contains("final") && !pairsParts)
        p += s"${show(d :+ "final")} exists although pairs/pairs holds no part file " +
          "(HD-P6-01 rule 6)"
      if (!ch.contains("final") && pairsParts)
        p += s"${show(d ++ Vector("pairs", "pairs"))} holds a part file but final/ is missing " +
          "(the producer always runs the final job then; truncated layout, HD-P6-01 rule 6)"
      stageDir(
        Map[String, Handler](
          "join" -> jobOut(leaves("joined", Rejects)),
          "pairs" -> jobOut(leaves("pairs", Rejects, "stats")),
          "final" -> jobOut(leaves("researcher", "work", Rejects, "stats"))
        )
      )(d)
    }

    val stages: Map[String, Handler] = Map(
      "normalize" -> stageDir(
        leaves("works", "mentions", "profiles", "venues", "institutions", "topics", Rejects)
      ),
      "exact-ids" -> stageDir(leaves("links", Rejects, "stats")),
      "teams" -> stageDir(leaves("teams", "pairs", Rejects, "stats")),
      "blocking" -> blocking,
      "candidates" -> candidates
    )

    // Rules 2 and 3: the run directory itself.
    val top = screen(Vector.empty, stage = false)
    top.get(ManifestFile) match {
      case None => p += s"the run directory lacks $ManifestFile (HD-P6-01 rules 2, 3)"
      case Some(Node.File(0L)) => p += s"'$ManifestFile' is empty (HD-P6-01 rule 2)"
      case Some(Node.File(_)) | Some(Node.Other) => ()
      case Some(Node.Dir) => p += s"'$ManifestFile' is not a regular file (HD-P6-01 rule 2)"
    }
    Stages
      .filterNot(top.contains)
      .foreach(s => p += s"the run directory lacks the stage directory $s/ (HD-P6-01 rule 3)")
    dirsOnly(Vector.empty, top - ManifestFile, stages)
    p.toVector
  }

  // ---------------------------------------------------------------- rule 2 content

  /**
   * Rule 2 content of the decoded manifest `m` of the curated run in `dir`, beyond the run-ID
   * binding (checked by the reader): the producer's stage and the DBLP snapshot token.
   */
  def manifestProblems(dir: Path, m: RunManifest): Vector[String] = {
    val stage =
      if (m.stage == ManifestStage) Vector.empty
      else Vector(s"the run manifest stage is '${m.stage}', expected '$ManifestStage'")
    val dblp = m.inputSnapshots.filter(_.source == SourceType.Dblp).map(_.snapshot)
    val token = snapshotToken(dir)
    val snapshot = dblp match {
      case Vector(s) if token.contains(s) => Vector.empty
      case Vector(s) =>
        Vector(
          s"the run manifest's DBLP snapshot '$s' is not the directory's snapshot= token " +
            s"'${token.getOrElse("")}' (HD-P6-01 rule 2)"
        )
      case other =>
        Vector(s"the run manifest has ${other.size} DBLP snapshots; exactly one is required")
    }
    stage ++ snapshot
  }

  // ---------------------------------------------------------------- rule 4 content

  /** Rule 4 content of `<stage>/_counters.json` (its text, already read with a byte bound). */
  def countersProblems(stage: String, text: String): Vector[String] = {
    val at = s"'$stage/$CountersFile'"
    val parsed: Either[String, ujson.Value] =
      try Right(ujson.read(text))
      catch { case NonFatal(e) => Left(String.valueOf(e.getMessage)) }
    parsed match {
      case Left(why) => Vector(s"$at is not valid JSON: $why")
      case Right(o: ujson.Obj) =>
        val named = o.value.get("stage") match {
          case Some(ujson.Str(s)) if s == stage => Vector.empty
          case Some(ujson.Str(s)) => Vector(s"$at names stage '$s', expected '$stage'")
          case _ => Vector(s"$at has no string field 'stage'")
        }
        val counters = o.value.get("counters") match {
          case Some(c: ujson.Obj) =>
            RequiredCounters.flatMap { k =>
              c.value.get(k) match {
                case Some(v) =>
                  counterValue(v).left.toOption.map(w => s"$at counter $k $w").toVector
                case None => Vector(s"$at lacks the required counter $k")
              }
            }
          case _ => Vector(s"$at has no object field 'counters'")
        }
        named ++ counters
      case Right(_) => Vector(s"$at is not a JSON object")
    }
  }

  /**
   * Exclusive bound of a counter value: 2^53. Every integer below it is an exact IEEE double, and
   * any JSON integer text of at least 2^53 parses to a double of at least 2^53 (rounding is
   * monotonic), so refusing values >= 2^53 refuses every counter that a double cannot hold exactly
   * (for example 2^53 + 1, which parses to 2^53). Limitation: a whole-valued fraction text such as
   * `5.0` parses to the integer 5 and is accepted (the parsed value is exact).
   */
  val CounterBound: Double = 9007199254740992.0

  /** A counter as a non-negative integer below [[CounterBound]], or why it is not one. */
  def counterValue(v: ujson.Value): Either[String, Long] = v match {
    case ujson.Num(n) if n >= 0 && n.isWhole && n < CounterBound => Right(n.toLong)
    case other => Left(s"is not a non-negative integer below 2^53: $other")
  }

  /**
   * The rows the normalize counters declare for each consumed leaf ([[ConsumedLeaves]]), given
   * whether the leaf holds a part file (`hasParts`). Refuses (rule 4 content) a leaf with parts but
   * without `EMITTED.<leaf>`, a declared count that is not a counter value, and a positive count
   * for a leaf without parts; a leaf without parts and without a counter declares 0 rows. `text` is
   * `normalize/_counters.json`, already accepted by [[countersProblems]].
   */
  def emittedRows(
      text: String,
      hasParts: String => Boolean
  ): Either[Vector[String], Map[String, Long]] = {
    val at = s"'normalize/$CountersFile'"
    val counters: Map[String, ujson.Value] =
      try
        ujson.read(text) match {
          case o: ujson.Obj =>
            o.value.get("counters") match {
              case Some(c: ujson.Obj) => c.value.toMap
              case _ => Map.empty
            }
          case _ => Map.empty
        }
      catch { case NonFatal(_) => Map.empty }
    val results = ConsumedLeaves.map { leaf =>
      val k = emittedCounter(leaf)
      (counters.get(k), hasParts(leaf)) match {
        case (None, false) => Right(leaf -> 0L)
        case (None, true) =>
          Left(s"$at lacks $k although normalize/$leaf holds part files (rows undeclared)")
        case (Some(v), parts) =>
          counterValue(v) match {
            case Left(w) => Left(s"$at counter $k $w")
            case Right(n) if n > 0 && !parts =>
              Left(s"$at declares $k = $n but normalize/$leaf holds no part file")
            case Right(n) => Right(leaf -> n)
          }
      }
    }
    val bad = results.collect { case Left(w) => w }
    if (bad.nonEmpty) Left(bad) else Right(results.collect { case Right(kv) => kv }.toMap)
  }
}
