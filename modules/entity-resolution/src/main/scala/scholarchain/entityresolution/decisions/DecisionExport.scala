package scholarchain.entityresolution.decisions

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}
import java.nio.file.{
  DirectoryNotEmptyException,
  FileAlreadyExistsException,
  Files,
  LinkOption,
  Path,
  StandardCopyOption,
  StandardOpenOption
}
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.format.DateTimeParseException

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import scholarchain.core.{
  Canonical,
  Json,
  MatchCandidate,
  MatchDecision,
  MatchEntityKind,
  ObservedFile,
  PartFile as ListedFile,
  PartManifest,
  PartManifestVerifier,
  Provenance,
  RunId,
  RunManifest,
  SchemaVersions,
  SourceAuthorProfile,
  WorkRecord
}
import scholarchain.entityresolution.policy.{
  DecisionContext,
  EndpointIdentifiers,
  MatchPolicy,
  Resolver
}

/**
 * The decisions of one decision run, complete and validated, ready to publish.
 *
 * @param manifest
 *   the decision run manifest (stage [[DecisionExport.Stage]])
 * @param runId
 *   [[RunId.of]](manifest): the `provenance.runId` of every decision and an input of every decision
 *   ID
 * @param researcher
 *   one decision per researcher candidate, sorted by candidate ID (UTF-8 bytes)
 * @param work
 *   one decision per work candidate, sorted by candidate ID (UTF-8 bytes)
 */
final case class DecisionBatch(
    manifest: RunManifest,
    runId: String,
    researcher: Vector[MatchDecision],
    work: Vector[MatchDecision]
) {
  def all: Vector[MatchDecision] = researcher ++ work
}

/**
 * Where the artifacts of a run that failed verification were left (ER-P6-07). Nothing is ever
 * deleted.
 *
 * @param path
 *   the directory that holds them: normally `.unverified-run=<hex>-<attempt>`; if that rename
 *   failed, the directory where they were (`.staging-run=<hex>-<attempt>` before publication,
 *   `run=<hex>` after it)
 * @param marked
 *   the [[DecisionExport.UnverifiedMarker]] file exists in it
 * @param moved
 *   it was renamed to [[DecisionExport.unverifiedName]]
 */
final case class Preserved(path: Path, marked: Boolean, moved: Boolean) {

  /**
   * True when no verified reader accepts the directory: it was moved to the `.unverified-` name, it
   * holds the marker, or its name is not `run=<hex>` (a staging directory). False only when a
   * published `run=<hex>` could be neither moved nor marked.
   */
  def rejectedByReaders: Boolean =
    moved || marked || !Option(path.getFileName).exists(_.toString.startsWith("run="))
}

/** What an all-or-nothing publication did (ER-P6-07). */
enum PublishResult {

  /**
   * The staged run was verified against the batch and renamed to `run=<hex>` (`dir`). `attempt` is
   * the attempt tag `run=<hex>-<random>` of its staging name, reused by
   * [[DecisionExport.withdraw]].
   */
  case Published(dir: Path, attempt: String)

  /** Nothing was published, and the staging directory (if any) was deleted. */
  case Refused(reason: String)

  /** Staged verification failed: `run=<hex>` was not created; the artifacts are preserved. */
  case Unverified(preserved: Preserved, reason: String)
}

/**
 * Persists the pairwise decisions of a completed curated run as core [[MatchDecision]]
 * (`scholarchain.match-decision.v2`) data (ER-P6-02, human decision H9). Decision logic is not
 * re-implemented: every candidate is decided by [[Resolver.decide]] with the identifiers of its
 * endpoint records ([[EndpointIdentifiers]]) and mapped by [[Resolver.toMatchDecision]], exactly as
 * the review-queue tooling decides it. No LLM is called; band cases stay UNRESOLVED.
 *
 * '''Decision run manifest.''' A core [[RunManifest]] with stage [[Stage]], the curated run's input
 * snapshots (the same set), the digest of the resolved configuration, and exactly the parameters
 * [[Params.All]]:
 *   - `decidedAt`: the ISO-8601 UTC instant recorded on every decision (H9: no clock is read, so
 *     the same manifest and inputs always give the same bytes);
 *   - `inputRunId`: the run ID of the curated run (its `_run-manifest.json`); every candidate must
 *     carry it;
 *   - `policyVersion`, `blockingVersion`, `featureVersion`: must equal the effective policy's.
 *
 * '''Fail closed.''' Any manifest or configuration mismatch, any candidate that cannot be decided
 * or mapped, a duplicate candidate ID or decision ID: the whole run is refused, with every reason,
 * and nothing is written.
 *
 * '''Published layout''' (local file system), under the output root:
 * {{{
 * <outputRoot>/run=<decision run ID hex>/
 *   decisions/researcher/part-00000.jsonl   one MatchDecision v2 per LF-terminated line (may be empty)
 *   decisions/work/part-00000.jsonl
 *   _run-manifest.json                      the decision run manifest, one LF-terminated row
 *   _parts.json                             core PartManifest (part-manifest.v1, contract C5)
 *   _SUCCESS                                empty; written last
 * }}}
 * '''Publication order (decision package G2, ER-P6-04).''' Inside the staging directory: both part
 * files, then `_run-manifest.json`, then `_parts.json`, then `_SUCCESS` last. `_parts.json` lists
 * exactly the run manifest (`run-manifest.v1`) and both part files (`match-decision.v2`), each with
 * the row count, byte count and SHA-256 that the core [[ObservedFile]] computes from the bytes read
 * back from the staging directory after they were written; it never lists itself or `_SUCCESS`.
 *
 * '''All-or-nothing publication.''' The files are written into a staging directory in the output
 * root named `.staging-run=<hex>-<random>`, in the order above, and the staging directory is then
 * renamed to `run=<hex>` in one atomic rename (the only publication step). A published directory is
 * never overwritten: the publish is refused when `run=<hex>` exists, and a rename onto a non-empty
 * directory fails. On a write or I/O failure the staging directory is deleted.
 *
 * '''Staged verification (ER-P6-07).''' Before the rename, the staged directory is read back with
 * the verified reader, under the name it will be published as (layout, `_parts.json`, row and byte
 * counts, SHA-256, run manifest, every decision decoded), and the manifest and decisions read back
 * must equal the batch. If they do not, `run=<hex>` is not created and nothing is deleted: the
 * staging directory receives the marker [[UnverifiedMarker]] (one line, the reason) and is renamed
 * to `.unverified-run=<hex>-<random>` (same attempt suffix) by [[withdraw]]; the result is
 * [[PublishResult.Unverified]]. A run whose read-back fails after publication is withdrawn the same
 * way by the caller. No reader accepts a withdrawn run: the name is not `run=<hex>`, and a
 * directory holding the marker is refused whatever its name.
 *
 * '''Verified reads.''' [[publishedRuns]] and [[readPublished]] return a run only after verifying
 * it against the bytes on disk with the core [[PartManifestVerifier]]: the exact file set (no
 * missing and no extra file), every row count, byte count and SHA-256, a final LF on every
 * non-empty file, and the run ID named by the directory. Every file is read once, without following
 * a symbolic link, and decision lines are decoded only from the verified bytes. A copied run that
 * is cut short (at a line boundary or mid-line), corrupted, missing a file, holding an extra file,
 * or holding `_SUCCESS` before `_parts.json` and the parts (a `_SUCCESS`-first copy) is refused. A
 * run published before ER-P6-04, which has no `_parts.json`, is refused; it is regenerated, not
 * migrated.
 *
 * '''What a failed attempt leaves on disk.''' After a refusal or a write failure, nothing,
 * normally. Only when the process dies, or the deletion itself fails, can a `.staging-run=...`
 * directory remain (possibly partial, possibly with `_SUCCESS` if the process died between that
 * write and the rename). After a verification failure, `.unverified-run=<hex>-<random>` holding the
 * complete staged file set plus [[UnverifiedMarker]] (see [[withdraw]] for the fallbacks when the
 * rename or the marker write fails). Readers ignore both: a published dataset is only a real
 * directory (never a symbolic link) named exactly `run=<64 hex>` that holds `_SUCCESS`,
 * `_parts.json`, `_run-manifest.json` and both part files as regular files, holds no
 * [[UnverifiedMarker]], and passes verification ([[publishedRuns]], [[readPublished]]; a part file
 * with zero rows is complete), and both names start with `.`, which Hadoop and Spark file listings
 * treat as hidden. A leftover staging directory may be deleted by hand; an unverified one is kept
 * for diagnosis.
 *
 * '''Known limitations''' (CR-P6-01 n2 and n3; documented, not fixed, pending human acceptance):
 *   - Durability (n2): every file is forced to disk before the rename, but neither the staging
 *     directory nor the output root is forced after the rename. Atomicity holds; after a power loss
 *     the rename itself may be lost, so the run is then simply absent (never partial). Durability
 *     after a power loss is neither guaranteed nor claimed.
 *   - `codeVersion` (n3): the manifest's `codeVersion` is asserted by the caller and never verified
 *     against the running build, yet it is an input of the run ID and so of every decision ID. Two
 *     different builds given the same manifest claim the same run ID; the no-overwrite rule
 *     protects only within one output root.
 */
object DecisionExport {

  /** Stage token of a decision run manifest. */
  val Stage: String = "er.decisions"

  /** Parameter keys of a decision run manifest. */
  object Params {
    val DecidedAt = "decidedAt"
    val InputRunId = "inputRunId"
    val PolicyVersion = "policyVersion"
    val BlockingVersion = "blockingVersion"
    val FeatureVersion = "featureVersion"
    val All: Vector[String] =
      Vector(BlockingVersion, DecidedAt, FeatureVersion, InputRunId, PolicyVersion)
  }

  /** The run manifest file, in a curated input run and in a published decision run. */
  val ManifestFile: String = PartManifest.RunManifestFile
  val SuccessFile: String = PartManifest.SuccessMarker

  /** The integrity manifest of a published run (core contract C5). */
  val PartsFile: String = PartManifest.FileName
  val DecisionsDir: String = "decisions"
  val PartFile: String = "part-00000.jsonl"
  val StagingPrefix: String = ".staging-"

  /** Name prefix of a run that failed verification (ER-P6-07); hidden from Hadoop and Spark. */
  val UnverifiedPrefix: String = ".unverified-"

  /**
   * Marker file of a run that failed verification (ER-P6-07): one LF-terminated line with the
   * reason. Every verified reader refuses a directory that holds it.
   */
  val UnverifiedMarker: String = "_UNVERIFIED"

  /** The name a withdrawn attempt `run=<hex>-<random>` is preserved under. */
  def unverifiedName(attempt: String): String = UnverifiedPrefix + attempt

  /** Run-relative path (`/`-separated) of the part file of one entity kind. */
  def partPath(kind: MatchEntityKind): String = s"$DecisionsDir/${kind.tag}/$PartFile"

  private val RunDirName = "^run=([0-9a-f]{64})$".r

  private def byUtf8(a: String, b: String): Boolean = Canonical.compareUtf8(a, b) < 0

  private def instantProblem(s: String): Option[String] =
    if (s == null || s.isEmpty) Some(s"parameter ${Params.DecidedAt} is empty")
    else
      try {
        Instant.parse(s)
        Option.when(!s.endsWith("Z"))(s"parameter ${Params.DecidedAt} must be a UTC instant (Z)")
      } catch {
        case _: DateTimeParseException =>
          Some(s"parameter ${Params.DecidedAt} '$s' is not an ISO-8601 instant")
      }

  /**
   * Every reason the decision run manifest `m` does not fit the effective policy, the resolved
   * configuration (`configDigest`), and the curated run's manifest `input` (empty when it fits).
   */
  def manifestProblems(
      m: RunManifest,
      p: MatchPolicy,
      configDigest: String,
      input: RunManifest
  ): Vector[String] = {
    val own = RunId.of(m).left.toOption.map(e => s"decision run manifest: ${e.message}").toVector
    val in = RunId.of(input)
    val inErr = in.left.toOption.map(e => s"curated run manifest: ${e.message}").toVector
    if (own.nonEmpty || inErr.nonEmpty) own ++ inErr
    else {
      val params = m.parameters
      def param(k: String, expected: String, what: String): Vector[String] =
        params.get(k) match {
          case Some(v) if v == expected => Vector.empty
          case Some(v) => Vector(s"parameter $k is $v, but $what is $expected")
          case None => Vector.empty // reported as missing below
        }
      Vector(
        Option.when(m.stage != Stage)(s"manifest stage is '${m.stage}', expected '$Stage'"),
        Option.when(m.configDigest != configDigest)(
          s"manifest configDigest ${m.configDigest} differs from the resolved configuration's " +
            s"$configDigest"
        ),
        Option.when(m.inputSnapshots.toSet != input.inputSnapshots.toSet)(
          "manifest inputSnapshots " +
            m.inputSnapshots.map(s => s"${s.source.tag}:${s.snapshot}").sorted.mkString(",") +
            " differ from the curated run's " +
            input.inputSnapshots.map(s => s"${s.source.tag}:${s.snapshot}").sorted.mkString(",")
        )
      ).flatten ++
        params.keys.filterNot(Params.All.contains).map(k => s"unknown parameter '$k'") ++
        Params.All.filterNot(params.contains).map(k => s"parameter '$k' is required") ++
        param(Params.InputRunId, in.getOrElse(""), "the curated run's run ID") ++
        param(Params.PolicyVersion, p.policyVersion, "the effective policy's version") ++
        param(Params.BlockingVersion, p.blockingVersion, "the policy's blocking version") ++
        param(Params.FeatureVersion, p.featureVersion, "the policy's feature version") ++
        params.get(Params.DecidedAt).flatMap(instantProblem).toVector
    }
  }

  /** The label of a curated candidate in a [[foreignProvenance]] reason. */
  def candidateLabel(candidateId: String): String = s"candidate $candidateId"

  /**
   * The provenance check of curated input records, shared by [[decideAll]] (candidates) and the
   * grouping layer (candidates, works, mentions and profiles; CR-P6-07): one reason per record
   * whose `provenance.runId` is not `inputRunId`, the run ID of the curated run's
   * `_run-manifest.json` (empty when every record carries it). Each record is given as its label
   * (for example [[candidateLabel]]) and its provenance; a null provenance is foreign. Input order
   * is kept.
   */
  def foreignProvenance(
      inputRunId: String,
      records: Iterable[(String, Provenance)]
  ): Vector[String] =
    records.iterator.collect {
      case (label, p) if Option(p).forall(_.runId != inputRunId) =>
        s"$label: provenance.runId is not the curated run $inputRunId"
    }.toVector

  /**
   * The curated records other than candidates, each with its [[foreignProvenance]] reason prefix,
   * in input order: every work (`work <workId>`) followed by each of its mentions (`mention
   * <mentionId>`), then every profile (`profile <profileId>`). Shared by [[decideAll]] and the
   * grouping layer (ER-P6-07, CR-P6-08 minor 1).
   */
  def curatedRecords(
      works: Vector[WorkRecord],
      profiles: Vector[SourceAuthorProfile]
  ): Vector[(String, Provenance)] =
    works.flatMap(w =>
      (s"work ${w.workId}" -> w.provenance) +:
        w.authors.map(m => s"mention ${m.mentionId}" -> m.provenance)
    ) ++ profiles.map(p => s"profile ${p.profileId}" -> p.provenance)

  /**
   * Decides every candidate of a curated run (its `works`, `profiles` and final `candidates`, as
   * read by the curated-run reader) under policy `p` (the effective policy) and maps each to a core
   * [[MatchDecision]] with the decision run's ID and `decidedAt`. Refused, with every reason, when
   * the manifest does not fit ([[manifestProblems]]), a candidate, work, mention or profile is not
   * of the curated run (ER-P6-07, CR-P6-08 minor 1: the endpoint identifiers come from the works
   * and profiles, so they must be of the run whose candidates are decided), two candidates share an
   * ID, any candidate cannot be decided or mapped, or two decisions share an ID.
   */
  def decideAll(
      p: MatchPolicy,
      works: Vector[WorkRecord],
      profiles: Vector[SourceAuthorProfile],
      runCandidates: Vector[MatchCandidate],
      input: RunManifest,
      m: RunManifest,
      configDigest: String
  ): Either[Vector[String], DecisionBatch] = {
    val problems = manifestProblems(m, p, configDigest, input)
    if (problems.nonEmpty) Left(problems)
    else {
      val runId = RunId.of(m).getOrElse("")
      val inputRunId = RunId.of(input).getOrElse("")
      val ctx = DecisionContext(runId, m.parameters(Params.DecidedAt))
      val candidates = runCandidates.sortWith((a, b) => byUtf8(a.candidateId, b.candidateId))
      val foreign =
        foreignProvenance(
          inputRunId,
          candidates.map(c => candidateLabel(c.candidateId) -> c.provenance) ++
            curatedRecords(works, profiles)
        )
      val duplicates = candidates.groupBy(_.candidateId).collect {
        case (id, cs) if cs.size > 1 => s"candidate $id appears ${cs.size} times"
      }
      if (foreign.nonEmpty || duplicates.nonEmpty) Left(foreign ++ duplicates.toVector.sorted)
      else {
        val index = EndpointIdentifiers.index(works, profiles)
        val results: Vector[(MatchCandidate, Either[Vector[String], MatchDecision])] =
          candidates.map { c =>
            c -> EndpointIdentifiers
              .of(c, index)
              .left
              .map(Vector(_))
              .flatMap(ids => Resolver.decide(p, c, ids))
              .flatMap(o => Resolver.toMatchDecision(o, ctx))
          }
        val refused = results.collect { case (c, Left(es)) =>
          s"candidate ${c.candidateId}: ${es.mkString("; ")}"
        }
        if (refused.nonEmpty) Left(refused)
        else {
          val decisions = results.collect { case (_, Right(d)) => d }
          val dupIds = decisions.groupBy(_.decisionId).collect {
            case (id, ds) if ds.size > 1 =>
              s"decision ID $id is shared by candidates ${ds.map(_.candidateId).mkString(", ")}"
          }
          if (dupIds.nonEmpty) Left(dupIds.toVector.sorted)
          else
            partition(m, runId, decisions)
        }
      }
    }
  }

  /**
   * Splits decisions into the researcher and work parts of a batch. Fails closed (CR-P6-01 n1)
   * unless the two parts together hold every decision: a decision of any other entity kind refuses
   * the run instead of being dropped.
   */
  private[decisions] def partition(
      m: RunManifest,
      runId: String,
      decisions: Vector[MatchDecision]
  ): Either[Vector[String], DecisionBatch] = {
    val researcher = decisions.filter(_.entityKind == MatchEntityKind.Researcher)
    val work = decisions.filter(_.entityKind == MatchEntityKind.Work)
    if (researcher.size + work.size == decisions.size)
      Right(DecisionBatch(m, runId, researcher, work))
    else
      Left(
        Vector(
          s"researcher decisions (${researcher.size}) + work decisions (${work.size}) != " +
            s"total decisions (${decisions.size}); other kinds: " +
            decisions
              .filterNot(d => Kinds.contains(d.entityKind))
              .map(d => s"${d.candidateId}:${d.entityKind.tag}")
              .mkString(", ")
        )
      )
  }

  /** JSON Lines of decisions: each validated and encoded by the core codec, one per line. */
  def toJsonl(ds: Vector[MatchDecision]): Either[Vector[String], String] = {
    val encoded = ds.map(d => d.candidateId -> Json.encode(d))
    val bad = encoded.collect { case (id, Left(es)) =>
      s"decision of $id: ${es.map(_.message).mkString("; ")}"
    }
    if (bad.nonEmpty) Left(bad)
    else Right(encoded.collect { case (_, Right(l)) => l + "\n" }.mkString)
  }

  /** Writes one new file (replaceable in tests to inject an I/O failure). */
  type FileWriter = (Path, String) => Unit

  /** Creates the file (it must not exist), writes the UTF-8 text in full, and forces it. */
  val DefaultFileWriter: FileWriter = (path, text) => {
    val ch = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    try {
      val buf = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8))
      while (buf.hasRemaining) ch.write(buf)
      ch.force(true)
    } finally ch.close()
  }

  /** The published directory name of a decision run. */
  def runDirName(runId: String): String = "run=" + runId.stripPrefix("run:")

  private def exists(p: Path): Boolean = Files.exists(p, LinkOption.NOFOLLOW_LINKS)

  private def deleteTree(p: Path): Unit =
    try
      if (exists(p)) {
        val s = Files.walk(p)
        try
          s.iterator.asScala.toVector.reverse.foreach(x =>
            try Files.deleteIfExists(x)
            catch { case NonFatal(_) => () }
          )
        finally s.close()
      }
    catch { case NonFatal(_) => () }

  /**
   * The files a decision run's `_parts.json` lists, with their record schema: exactly the run
   * manifest and the two part files.
   */
  private def listedFiles: Vector[(String, String)] =
    Vector(ManifestFile -> SchemaVersions.RunManifest) ++
      Kinds.map(k => partPath(k) -> SchemaVersions.MatchDecision)

  /** The directories a decision run holds (run-relative); any other directory is refused. */
  private def runDirectories: Set[String] =
    Set(DecisionsDir) ++ Kinds.map(k => s"$DecisionsDir/${k.tag}")

  /**
   * Reads one regular file in full without following a symbolic link at its last component, feeding
   * the core [[ObservedFile.Accumulator]] (and `keep`, when given) with every byte in order.
   */
  private def readNoFollow(
      p: Path,
      rel: String,
      keep: Option[ByteArrayOutputStream]
  ): ObservedFile = {
    val acc = new ObservedFile.Accumulator
    val ch = Files.newByteChannel(p, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
    try {
      val buf = ByteBuffer.allocate(64 * 1024)
      var n = ch.read(buf)
      while (n >= 0) {
        if (n > 0) {
          acc.update(buf.array, 0, n)
          keep.foreach(_.write(buf.array, 0, n))
        }
        buf.clear()
        n = ch.read(buf)
      }
    } finally ch.close()
    acc.result(rel)
  }

  /** Renames a directory (replaceable in tests to inject a rename failure). */
  type Mover = (Path, Path) => Unit

  /** One atomic rename; fails when the target exists. */
  val AtomicMove: Mover = (from, to) => Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)

  /** The reason as one line (line breaks replaced by spaces). */
  private def oneLine(s: String): String = s.replace('\r', ' ').replace('\n', ' ')

  /**
   * Withdraws the run directory `dir` (a staging directory, or a published `run=<hex>`) of attempt
   * `attempt` (`run=<hex>-<random>`) after a failed verification (ER-P6-07). Deletes nothing:
   *   1. writes [[UnverifiedMarker]] into `dir` (one line: the reason), so that every verified
   *      reader refuses it from that moment on, whatever its name;
   *   2. renames `dir` to the sibling [[unverifiedName]]`(attempt)` with `move`, unless that name
   *      exists.
   *
   * Each step is attempted even if the other fails. The result names where the artifacts are and
   * whether they are rejected by every reader ([[Preserved.rejectedByReaders]]).
   */
  def withdraw(dir: Path, attempt: String, reason: String, move: Mover = AtomicMove): Preserved = {
    val marker = dir.resolve(UnverifiedMarker)
    val marked =
      try {
        DefaultFileWriter(
          marker,
          s"verification failed; never accepted by readers: ${oneLine(reason)}\n"
        )
        true
      } catch { case NonFatal(_) => exists(marker) }
    val target = dir.resolveSibling(unverifiedName(attempt))
    val moved =
      !exists(target) &&
        (try { move(dir, target); true }
        catch { case NonFatal(_) => false })
    Preserved(if (moved) target else dir, marked, moved)
  }

  /** The text of a verification failure, naming where the artifacts are. */
  def unverifiedText(p: Preserved): String =
    if (p.rejectedByReaders)
      s"artifacts preserved under ${p.path}; not accepted by readers" +
        (if (p.moved) "" else s" (the rename to ${UnverifiedPrefix}... failed)") +
        (if (p.marked) "" else s" (no $UnverifiedMarker marker could be written)")
    else
      s"artifacts left under ${p.path}, which could be neither renamed nor marked " +
        s"$UnverifiedMarker: a reader may accept it; move it aside by hand before any reader runs"

  /**
   * Why the manifest and decisions read back from a run differ from the batch `b`, or Right (the
   * comparison of the staged and the post-publication read-back, ER-P6-06 and ER-P6-07).
   */
  def compareWithBatch(
      b: DecisionBatch,
      read: (RunManifest, Vector[MatchDecision])
  ): Either[String, Unit] = {
    val (m, ds) = read
    val manifestNote = if (m == b.manifest) "" else ", another run manifest"
    Either.cond(
      m == b.manifest && ds == b.all,
      (),
      s"the run read back (${ds.size} decisions$manifestNote) differs from the batch " +
        s"(${b.all.size} decisions)"
    )
  }

  /**
   * Publishes a batch under `outputRoot` all-or-nothing (see the object documentation), writing in
   * the G2 order: part files, `_run-manifest.json`, `_parts.json` (from the bytes read back from
   * staging), `_SUCCESS`; then verifies the staged directory against the batch (read back under its
   * final name, [[compareWithBatch]]) and only then renames it to `run=<hex>`. An existing
   * `run=<hex>` is never touched.
   *
   * @return
   *   [[PublishResult.Published]] (staged run verified and renamed), [[PublishResult.Refused]]
   *   (nothing published; the staging directory, if created, was deleted), or
   *   [[PublishResult.Unverified]] (staged verification failed; `run=<hex>` was not created; the
   *   staging directory was withdrawn by [[withdraw]], nothing deleted)
   */
  def publishRun(
      outputRoot: Path,
      b: DecisionBatch,
      writer: FileWriter = DefaultFileWriter
  ): PublishResult = {
    val root = outputRoot.toAbsolutePath.normalize
    val name = runDirName(b.runId)
    val target = root.resolve(name)
    val refusal = s"$target exists; refusing to overwrite a published decision run"
    val texts = for {
      manifest <- Json.encode(b.manifest).left.map(_.map(_.message))
      researcher <- toJsonl(b.researcher)
      work <- toJsonl(b.work)
    } yield (manifest + "\n", researcher, work)
    texts match {
      case Left(es) => PublishResult.Refused("nothing published: " + es.mkString("; "))
      case Right(_) if exists(target) => PublishResult.Refused(refusal)
      case Right((manifest, researcher, work)) =>
        var staging: Option[Path] = None
        try {
          Files.createDirectories(root)
          val dir = Files.createTempDirectory(root, StagingPrefix + name + "-")
          staging = Some(dir)
          Kinds.foreach(k => Files.createDirectories(dir.resolve(DecisionsDir).resolve(k.tag)))
          writer(partOf(dir, MatchEntityKind.Researcher), researcher)
          writer(partOf(dir, MatchEntityKind.Work), work)
          writer(dir.resolve(ManifestFile), manifest)
          val listed = listedFiles.map { case (rel, schema) =>
            val o = readNoFollow(dir.resolve(rel), rel, None)
            ListedFile(rel, schema, o.rowCount, o.byteCount, o.sha256)
          }
          PartManifest.build(b.runId, listed).flatMap(Json.encode(_)) match {
            case Left(es) =>
              PublishResult.Refused(
                "nothing published: part manifest: " + es.map(_.message).mkString("; ")
              )
            case Right(parts) =>
              writer(dir.resolve(PartsFile), parts + "\n")
              writer(dir.resolve(SuccessFile), "")
              val attempt = dir.getFileName.toString.stripPrefix(StagingPrefix)
              val staged =
                try readAs(dir, name).flatMap(compareWithBatch(b, _))
                catch { case NonFatal(e) => Left(s"${e.getClass.getName}: ${e.getMessage}") }
              staged match {
                case Left(why) =>
                  staging = None // preserved for diagnosis, never deleted
                  val p = withdraw(dir, attempt, why)
                  PublishResult.Unverified(
                    p,
                    s"staged verification failed, so $target was not published; " +
                      s"${unverifiedText(p)}: $why"
                  )
                case Right(()) if exists(target) => PublishResult.Refused(refusal)
                case Right(()) =>
                  Files.move(dir, target, StandardCopyOption.ATOMIC_MOVE)
                  staging = None
                  PublishResult.Published(target, attempt)
              }
          }
        } catch {
          case _: FileAlreadyExistsException | _: DirectoryNotEmptyException =>
            PublishResult.Refused(refusal)
          case NonFatal(e) =>
            PublishResult.Refused(
              s"nothing published under $root: ${e.getClass.getName}: ${e.getMessage}"
            )
        } finally staging.foreach(deleteTree)
    }
  }

  /**
   * [[publishRun]] as an Either: Right with the published (staged-verified) directory; Left with
   * the reason otherwise (a refusal, or a staged verification failure whose text names the
   * preserved directory).
   */
  def publish(
      outputRoot: Path,
      b: DecisionBatch,
      writer: FileWriter = DefaultFileWriter
  ): Either[String, Path] =
    publishRun(outputRoot, b, writer) match {
      case PublishResult.Published(dir, _) => Right(dir)
      case PublishResult.Refused(why) => Left(why)
      case PublishResult.Unverified(_, why) => Left(why)
    }

  private val Kinds: Vector[MatchEntityKind] =
    Vector(MatchEntityKind.Researcher, MatchEntityKind.Work)

  private def isDir(p: Path): Boolean = Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)
  private def isFile(p: Path): Boolean = Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)

  /** The part file of one entity kind in a run directory. */
  private def partOf(dir: Path, kind: MatchEntityKind): Path =
    dir.resolve(DecisionsDir).resolve(kind.tag).resolve(PartFile)

  /**
   * Why `dir` is not a complete published run directory, or None (ER-P6-02 repair 01, CR-P6-01 m1
   * and n4; ER-P6-04). Structural only, and no symbolic link is followed at any level: `dir` is a
   * directory named exactly `run=<64 hex>`; `_SUCCESS`, `_run-manifest.json` and `_parts.json` are
   * regular files; the directories `decisions/`, `decisions/researcher/`, `decisions/work/` are
   * directories; and both part files are regular files. A part file with zero rows is complete. Any
   * entry named [[UnverifiedMarker]] refuses the directory (ER-P6-07). Contents are checked by
   * [[verifiedRun]]. `name` is the directory name checked (its own, except for the staged
   * verification, which checks the name it will be published as).
   */
  private def layoutProblem(dir: Path, name: String): Option[String] =
    if (!RunDirName.matches(name)) Some(s"$dir is not a decision run directory (run=<hex>)")
    else if (!isDir(dir)) Some(s"$dir is not a directory (symbolic links are not followed)")
    else if (exists(dir.resolve(UnverifiedMarker)))
      Some(s"$dir holds $UnverifiedMarker: it failed verification and is never accepted")
    else if (!isFile(dir.resolve(SuccessFile)))
      Some(s"$dir is not a published decision run (no regular $SuccessFile)")
    else if (!isFile(dir.resolve(ManifestFile)))
      Some(s"$dir is incomplete: no regular $ManifestFile")
    else if (!isFile(dir.resolve(PartsFile)))
      Some(s"$dir is incomplete or unverifiable: no regular $PartsFile")
    else {
      val dirs =
        dir.resolve(DecisionsDir) +: Kinds.map(k => dir.resolve(DecisionsDir).resolve(k.tag))
      dirs.find(d => !isDir(d)) match {
        case Some(d) => Some(s"$dir is incomplete: $d is not a directory")
        case None =>
          Kinds.map(partOf(dir, _)).find(f => !isFile(f)).map { f =>
            s"$dir is incomplete: $f is missing or not a regular file"
          }
      }
    }

  /** A run directory verified against its part manifest, with the verified part bytes. */
  private final case class VerifiedRun(
      manifest: RunManifest,
      runId: String,
      parts: Map[String, Array[Byte]]
  )

  /** Strict UTF-8 decoding: malformed or unmappable input is refused, never replaced. */
  private def utf8(bytes: Array[Byte], what: String): Either[String, String] =
    try
      Right(
        StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString
      )
    catch { case _: CharacterCodingException => Left(s"$what is not valid UTF-8") }

  /** `p` relative to `dir`, `/`-separated whatever the platform separator. */
  private def relative(dir: Path, p: Path): String =
    dir.relativize(p).iterator.asScala.map(_.toString).mkString("/")

  /**
   * Every regular file below `dir`, observed from its bytes without following a symbolic link, with
   * the bytes of the files `keep` names. Refused when an entry is neither a regular file nor a
   * directory (a symbolic link included), or when a directory is not one of [[runDirectories]].
   */
  private def observe(
      dir: Path,
      keep: Set[String]
  ): Either[String, (Vector[ObservedFile], Map[String, Array[Byte]])] = {
    val s = Files.walk(dir) // does not follow symbolic links
    val entries =
      try s.iterator.asScala.toVector.filter(_ != dir)
      finally s.close()
    val sorted = entries.map(p => relative(dir, p) -> p).sortWith((a, b) => byUtf8(a._1, b._1))
    sorted.foldLeft[Either[String, (Vector[ObservedFile], Map[String, Array[Byte]])]](
      Right((Vector.empty, Map.empty))
    ) { case (acc, (rel, p)) =>
      acc.flatMap { case (obs, bytes) =>
        val attrs =
          Files.readAttributes(p, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
        if (attrs.isDirectory)
          if (runDirectories.contains(rel)) Right((obs, bytes))
          else Left(s"$dir holds an unexpected directory $rel")
        else if (attrs.isRegularFile) {
          val out = Option.when(keep.contains(rel))(new ByteArrayOutputStream)
          val o = readNoFollow(p, rel, out)
          Right((obs :+ o, bytes ++ out.map(b => rel -> b.toByteArray)))
        } else
          Left(s"$dir holds $rel, which is neither a regular file nor a directory")
      }
    }
  }

  /**
   * Verifies a published run directory against the bytes on disk (ER-P6-04, decision package G2):
   * the layout ([[layoutProblem]]); `_parts.json` decodes as a core [[PartManifest]] and lists
   * exactly the run manifest (`run-manifest.v1`) and both part files (`match-decision.v2`);
   * [[PartManifestVerifier.verify]] against the run ID the directory names (exact file set, row
   * counts, byte counts, SHA-256, final LF); then the verified run manifest decodes with the core
   * codec, has stage [[Stage]] and gives that run ID, which is also the part manifest's. Each file
   * is read once; the bytes returned are the bytes verified. Part bytes are kept only when
   * `keepParts`. `name` is the directory name verified (see [[layoutProblem]]).
   */
  private def verifiedRun(
      dir: Path,
      keepParts: Boolean,
      name: String
  ): Either[String, VerifiedRun] =
    try
      layoutProblem(dir, name) match {
        case Some(why) => Left(why)
        case None =>
          val expectedRunId = "run:" + name.stripPrefix("run=")
          val partPaths = Kinds.map(partPath)
          val keep = Set(ManifestFile, PartsFile) ++ (if (keepParts) partPaths else Vector.empty)
          for {
            observed <- observe(dir, keep)
            (files, bytes) = observed
            partsText <- utf8(bytes(PartsFile), s"$dir/$PartsFile")
            pm <- Json
              .decode[PartManifest](partsText)
              .left
              .map(es => s"$dir/$PartsFile: ${es.map(_.message).mkString("; ")}")
            listed = pm.files.map(f => f.path -> f.recordSchemaVersion)
            _ <- Either.cond(
              listed.toSet == listedFiles.toSet && listed.size == listedFiles.size,
              (),
              s"$dir/$PartsFile: lists ${listed.map(_._1).mkString(", ")}; a decision run lists " +
                listedFiles.map { case (p, v) => s"$p ($v)" }.mkString(", ")
            )
            _ <- {
              val errors = PartManifestVerifier.verify(pm, expectedRunId, files)
              Either.cond(
                errors.isEmpty,
                (),
                s"$dir does not match its $PartsFile (refused): " +
                  errors.map(_.message).mkString("; ")
              )
            }
            manifestText <- utf8(bytes(ManifestFile), s"$dir/$ManifestFile")
            m <- Json
              .decode[RunManifest](manifestText.stripSuffix("\n"))
              .left
              .map(es => s"$dir/$ManifestFile: ${es.map(_.message).mkString("; ")}")
            _ <- Either.cond(
              m.stage == Stage,
              (),
              s"$dir/$ManifestFile: stage is '${m.stage}', expected '$Stage'"
            )
            runId <- RunId.of(m).left.map(e => s"$dir/$ManifestFile: ${e.message}")
            _ <- Either.cond(runDirName(runId) == name, (), s"$dir does not hold run $runId")
            _ <- {
              val errors = PartManifest.checkRunManifest(pm, m)
              Either.cond(
                errors.isEmpty,
                (),
                s"$dir/$PartsFile: ${errors.map(_.message).mkString("; ")}"
              )
            }
          } yield VerifiedRun(m, runId, bytes.filter { case (k, _) => partPaths.contains(k) })
      }
    catch {
      case NonFatal(e) => Left(s"cannot read $dir: ${e.getClass.getName}: ${e.getMessage}")
    }

  /**
   * The published decision runs below `root`: entries named exactly `run=<64 hex>` that pass
   * [[verifiedRun]] (real directories, never symbolic links, the exact file set of `_parts.json`
   * with matching row counts, byte counts and SHA-256, a run manifest of stage [[Stage]] that names
   * the directory), sorted. Staging, incomplete, unverifiable and foreign directories are never
   * listed. Decision lines are decoded only by [[readPublished]], which every consumer must use.
   */
  def publishedRuns(root: Path): Vector[Path] =
    try
      if (!Files.isDirectory(root)) Vector.empty
      else {
        val s = Files.list(root)
        try
          s.iterator.asScala.toVector
            .filter(p => verifiedRun(p, keepParts = false, nameOf(p)).isRight)
            .sortBy(_.getFileName.toString)
        finally s.close()
      }
    catch { case NonFatal(_) => Vector.empty }

  /**
   * The rows of verified part bytes: empty for a zero-byte file, otherwise every LF-terminated line
   * (the verifier has established the final LF). An empty row is refused, never dropped.
   */
  private def rows(bytes: Array[Byte], file: String): Either[String, Vector[String]] =
    utf8(bytes, file).flatMap { text =>
      if (text.isEmpty) Right(Vector.empty)
      else if (!text.endsWith("\n")) Left(s"$file: the last row has no final LF")
      else {
        val rs = text.stripSuffix("\n").split("\n", -1).toVector
        rs.indexWhere(_.isEmpty) match {
          case -1 => Right(rs)
          case i => Left(s"$file: row ${i + 1} is empty")
        }
      }
    }

  /**
   * Reads a published decision run: refused unless it passes [[verifiedRun]] (layout, no symbolic
   * link followed, the exact file set, row counts, byte counts, SHA-256 and final LF of
   * `_parts.json`, the run ID, a run manifest of stage [[Stage]] naming the directory), then every
   * row of the verified bytes decodes with the core codec (an empty row is refused), and every
   * decision carries the run ID and the entity kind of its directory. A part file with zero rows is
   * valid (no decisions of that kind). A directory holding [[UnverifiedMarker]], or not named
   * `run=<hex>` (a `.staging-` or `.unverified-` directory), is refused.
   */
  def readPublished(dir: Path): Either[String, (RunManifest, Vector[MatchDecision])] =
    readAs(dir, nameOf(dir))

  private def nameOf(dir: Path): String = Option(dir.getFileName).map(_.toString).getOrElse("")

  /** [[readPublished]] of `dir` as if it were named `name` (the staged verification, ER-P6-07). */
  private def readAs(
      dir: Path,
      name: String
  ): Either[String, (RunManifest, Vector[MatchDecision])] =
    try {
      verifiedRun(dir, keepParts = true, name).flatMap { v =>
        Kinds
          .foldLeft[Either[String, Vector[MatchDecision]]](Right(Vector.empty)) { (acc, kind) =>
            acc.flatMap { done =>
              val file = s"$dir/${partPath(kind)}"
              rows(v.parts(partPath(kind)), file).flatMap { ls =>
                val decoded = ls.map(Json.decode[MatchDecision](_))
                decoded.collectFirst { case Left(es) => es } match {
                  case Some(es) => Left(s"$file: ${es.map(_.message).mkString("; ")}")
                  case None =>
                    val got = decoded.collect { case Right(d) => d }
                    got.find(d => d.entityKind != kind || d.provenance.runId != v.runId) match {
                      case Some(d) =>
                        Left(s"$file: decision ${d.decisionId} is not a $kind of ${v.runId}")
                      case None => Right(done ++ got)
                    }
                }
              }
            }
          }
          .map(ds => (v.manifest, ds))
      }
    } catch {
      case NonFatal(e) => Left(s"cannot read $dir: ${e.getClass.getName}: ${e.getMessage}")
    }
}
