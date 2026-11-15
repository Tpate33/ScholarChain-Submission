package scholarchain.entityresolution.review

import java.nio.file.{Files, Path}

import scala.util.control.NonFatal

import scholarchain.core.MatchCandidate
import scholarchain.core.config.{ConfigError, ScholarChainConfig}
import scholarchain.entityresolution.{ConfigEvidence, LoadedConfig}
import scholarchain.entityresolution.policy.{
  EndpointIdentifiers,
  MatchPolicy,
  ResolutionOutcome,
  Resolver
}

/** Resolution of a whole curated run, for the dev diagnostic queue. */
final case class RunResolution(
    outcomes: Vector[ResolutionOutcome],
    refused: Vector[(String, Vector[String])],
    build: ReviewQueueBuild
)

/**
 * The per-matching-version rows of a fixed membership manifest.
 *
 * @param rows
 *   one row per resolved member, sorted by member ID
 * @param notGenerated
 *   members for which the run produced no candidate (a gold match among them is a false negative of
 *   blocking); reported, never dropped
 * @param refused
 *   members whose candidate the policy refused, with the reasons
 * @param untraceable
 *   members whose candidate could not be traced to its source records
 * @param outsideManifest
 *   candidates of the run that are not members (ignored; counted for the report)
 */
final case class MemberQueue(
    policyVersion: String,
    rows: Vector[ReviewQueueRow],
    notGenerated: Vector[GoldMember],
    refused: Vector[(String, Vector[String])],
    untraceable: Vector[(String, String)],
    outsideManifest: Int
)

object RunQueue {

  private def decide(p: MatchPolicy, run: CuratedRun, cs: Vector[MatchCandidate]) = {
    val idIndex = EndpointIdentifiers.index(run.works, run.profiles)
    cs.map { c =>
      c -> EndpointIdentifiers
        .of(c, idIndex)
        .left
        .map(Vector(_))
        .flatMap(ids => Resolver.decide(p, c, ids))
    }
  }

  /**
   * Resolves every candidate of a run with the policy and the identifiers of its endpoint records
   * (no LLM call: band cases stay UNRESOLVED and are flagged `llmBandEligible`), then builds the
   * outcome-stratified DEV DIAGNOSTIC queue ([[ReviewQueue.buildDevDiagnostics]]), which refuses a
   * run with held-out endpoints.
   */
  def resolveDiagnostics(
      p: MatchPolicy,
      run: CuratedRun,
      design: SamplingDesign,
      units: SelectionUnits
  ): Either[Vector[String], RunResolution] = {
    val results = decide(p, run, run.candidates)
    val outcomes = results.collect { case (_, Right(o)) => o }
    val refused = results.collect { case (c, Left(e)) => c.candidateId -> e }
    val refs = EndpointRef.index(run.works, run.profiles)
    ReviewQueue
      .buildDevDiagnostics(p, outcomes, refs, design, units)
      .map(b => RunResolution(outcomes, refused, b))
  }

  /**
   * The rows of a fixed manifest under one policy: every member is looked up in the run by its
   * member ID (entity kind and endpoint pair), never by candidate ID, so a run of another blocking
   * or feature version still finds the same members. Membership is never changed here.
   */
  def resolveMembers(p: MatchPolicy, manifest: Vector[GoldMember], run: CuratedRun): MemberQueue = {
    val byMember = run.candidates
      .flatMap(c =>
        GoldMembership.memberId(c.entityKind.tag, c.sourceIdA, c.sourceIdB).toOption.map(_ -> c)
      )
      .groupBy(_._1)
      .map { case (id, cs) => id -> cs.map(_._2).minBy(_.candidateId) }
    val members = manifest.distinctBy(_.memberId).sortBy(_.memberId)
    val memberIds = members.map(_.memberId).toSet
    val present = members.flatMap(m => byMember.get(m.memberId).map(m -> _))
    val decided = decide(p, run, present.map(_._2)).map(_._2)
    val refs = EndpointRef.index(run.works, run.profiles)
    val traced = present.zip(decided).map { case ((m, _), d) =>
      m -> d.map(o => ReviewQueue.row(p, o, refs))
    }
    MemberQueue(
      p.policyVersion,
      traced.collect { case (_, Right(Right(r))) => r },
      members.filterNot(m => byMember.contains(m.memberId)),
      traced.collect { case (m, Left(e)) => m.memberId -> e },
      traced.collect { case (m, Right(Left(why))) => m.memberId -> why },
      byMember.keySet.count(id => !memberIds.contains(id))
    )
  }

  /**
   * Members whose endpoint records are not records of `run`, as (member ID, endpoint ID), sorted
   * (ER-P5-15, ER-P5-14 G8). A membership is drawn from its generating run's candidates and every
   * endpoint is a record of that run, and every run of the same split's inputs holds the same
   * records whatever its blocking, feature or policy version. A missing endpoint therefore means a
   * run of another split or of other inputs: the queue refuses it instead of reporting every member
   * as not-generated.
   */
  def missingEndpoints(manifest: Vector[GoldMember], run: CuratedRun): Vector[(String, String)] = {
    val refs = EndpointRef.index(run.works, run.profiles)
    manifest
      .flatMap(m =>
        Vector(m.endpointA, m.endpointB)
          .filterNot(e => refs.contains(e.endpointId))
          .map(e => m.memberId -> e.endpointId)
      )
      .distinct
      .sorted
  }

  /** The blind labeling view of a manifest (policy-free: built from the members only). */
  def blindOfMembers(
      manifest: Vector[GoldMember],
      run: CuratedRun,
      seed: String
  ): Either[Vector[String], Vector[BlindRow]] =
    BlindView.build(
      manifest.map(BlindItem.of),
      BlindView.index(run.works, run.profiles),
      seed
    )

  /** The blind labeling view of the selected rows of a dev diagnostic queue. */
  def blind(
      r: RunResolution,
      run: CuratedRun,
      seed: String
  ): Either[Vector[String], Vector[BlindRow]] =
    BlindView.build(
      r.build.rows.map(BlindItem.of),
      BlindView.index(run.works, run.profiles),
      seed
    )

  /** Plain-text summary of strata, coverage cells, and shortfalls. */
  def summary(r: RunResolution): Vector[String] = {
    val b = r.build
    Vector(
      s"candidates resolved=${r.outcomes.size} refused=${r.refused.size} " +
        s"untraceable=${b.untraceable.size} traceable=${b.availableTotal} " +
        s"selected=${b.selectedTotal} target=${b.design.targetTotal} " +
        s"shortfall=${b.totalShortfall}"
    ) ++ b.strata.map(s =>
      s"stratum ${s.stratum} available=${s.available} selected=${s.selected} " +
        s"shortfall_vs_min=${s.shortfall}"
    ) ++ b.cells.map(c =>
      s"cell ${c.entityKind}/${c.cell} available=${c.available} selected=${c.selected}"
    ) ++ b.untraceable.map { case (id, why) => s"untraceable $id: $why" } ++
      r.refused.map { case (id, why) => s"refused $id: ${why.mkString("; ")}" }
  }

  /** Plain-text summary of a member queue: every member is accounted for. */
  def summary(q: MemberQueue, manifestSize: Int): Vector[String] =
    Vector(
      s"policy=${q.policyVersion} members=$manifestSize rows=${q.rows.size} " +
        s"not-generated=${q.notGenerated.size} refused=${q.refused.size} " +
        s"untraceable=${q.untraceable.size} candidates-outside-manifest=${q.outsideManifest}"
    ) ++ q.notGenerated.map(m => s"not-generated ${m.memberId}") ++
      q.refused.map { case (id, why) => s"refused $id: ${why.mkString("; ")}" } ++
      q.untraceable.map { case (id, why) => s"untraceable $id: $why" }
}

/**
 * Entry point. Three modes; every output file is created new and never overwritten, and each mode
 * refuses equal output paths. Exit codes: 0 written, 2 usage or input error.
 *
 *   - `membership <split-assignment.tsv> <PROVENANCE.tsv> <curated run dir> <heldout|dev> <manifest
 *     .jsonl> <blind view .jsonl> <seed> [capacity]`: creates the fixed membership manifest
 *     (`er-gold-membership.v1`) of one per-split run ONCE, and its masked blind labeling view
 *     (schema [[BlindView.RowSchemaVersion]], label guide [[LabelGuide.Version]]). The seed is
 *     required and recorded; the capacity defaults to [[GoldMembership.DefaultCapacity]] (240). A
 *     smaller population is a census and the shortfall is printed. Prints researcher/work and
 *     per-stratum counts of the population (`population=` is the number of distinct candidate pairs
 *     of the run) and the sample, and the manifest SHA-256 that freezes the membership. Refuses
 *     endpoints of the other split. No policy is loaded and no configuration is read.
 *   - `queue <manifest .jsonl> <curated run dir> <internal queue .jsonl> [--expect-split=<heldout|
 *     dev>]`: the rows ([[ReviewQueue.RowSchemaVersion]]) of every member under the effective
 *     policy; not-generated, refused and untraceable members are listed. Refused (ER-P5-15, G8): a
 *     manifest whose split is not `--expect-split`, and a run that does not hold every member's
 *     endpoint records ([[RunQueue.missingEndpoints]]: a run of another split or other inputs).
 *   - `diagnostics <split-assignment.tsv> <PROVENANCE.tsv> <curated run dir> <internal .jsonl>
 *     <blind .jsonl> [targetTotal] [minPerStratum] [seed]`: the outcome-stratified queue, DEV ONLY
 *     (refuses held-out endpoints).
 *
 * `queue` and `diagnostics` apply the external configuration (`SCHOLARCHAIN_CONFIG_FILES`, core
 * `ConfigLoader`) with [[MatchPolicy.withConfig]] (RV-P5-04 m6): the configured policy and prompt
 * versions must equal the policy's, and the configured thresholds and LLM band are the ones used
 * and recorded on every row. Each prints a `settings` line with the effective values, then the
 * configuration evidence ([[ConfigEvidence.lines]]: every file read with its SHA-256, and the names
 * of any `SCHOLARCHAIN_CFG_` overrides).
 */
object ReviewQueueMain {
  def main(args: Array[String]): Unit = sys.exit(run(args.toVector, println))

  private def distinctNew(paths: Vector[Path]): Either[String, Unit] =
    if (paths.map(_.toAbsolutePath.normalize).distinct.size != paths.size)
      Left("output files must be different files")
    else
      paths.find(Files.exists(_)) match {
        case Some(t) => Left(s"$t exists; refusing to overwrite")
        case None => Right(())
      }

  /**
   * Creates every output once and completely ([[CreateOnceFile]], RV-P5-04 N4). When one output
   * cannot be written, the outputs this call already created are removed again, so a failed run
   * leaves no partial set of files behind.
   */
  private def writeAll(outputs: Vector[(Path, String)]): Either[String, Unit] = {
    val created = Vector.newBuilder[Path]
    val result = outputs.foldLeft[Either[String, Unit]](Right(())) {
      case (Left(e), _) => Left(e)
      case (Right(_), (t, text)) =>
        CreateOnceFile.write(t, text).map(_ => created += t).map(_ => ())
    }
    if (result.isLeft)
      created
        .result()
        .foreach(t =>
          try Files.deleteIfExists(t)
          catch { case NonFatal(_) => false }
        )
    result
  }

  /**
   * The effective policy of a run: the default policy with the external configuration applied
   * ([[MatchPolicy.withConfig]]: policy and prompt versions must match; thresholds and the LLM band
   * come from the configuration). A missing or invalid configuration, or a mismatch, refuses the
   * run; there is no fallback to the thresholds in the policy file.
   */
  def effectivePolicy(
      loadConfig: () => Either[Vector[ConfigError], ScholarChainConfig]
  ): Either[String, MatchPolicy] = policyOf(loadConfig())

  private def policyOf(
      loaded: Either[Vector[ConfigError], ScholarChainConfig]
  ): Either[String, MatchPolicy] =
    for {
      cfg <- loaded.left.map(es => "configuration refused: " + es.map(_.message).mkString("; "))
      base <- MatchPolicy.loadDefault().left.map(_.mkString("; "))
      p <- MatchPolicy
        .withConfig(base, cfg.entityResolution)
        .left
        .map(es => "configuration refused: " + es.mkString("; "))
    } yield p

  /** The effective settings line of a queue run (no adjudicator runs here). */
  def settingsLine(p: MatchPolicy): String =
    s"settings policy=${p.policyVersion} blocking=${p.blockingVersion} " +
      s"features=${p.featureVersion} accept=${p.thresholds.accept} " +
      s"reject=${p.thresholds.reject} llmBand=[${p.llmBand.lower},${p.llmBand.upper}] " +
      s"prompt=${p.adjudication.promptVersion} model=none (no adjudicator ran; this queue makes " +
      "no LLM call) config=applied"

  private def units(sa: String, pv: String): Either[String, SelectionUnits] =
    SelectionUnits.load(Path.of(sa), Path.of(pv)).left.map(_.mkString("; "))

  /** The optional flag of `queue` that names the split the manifest must be of. */
  val ExpectSplitFlag: String = "--expect-split"

  private def usage: String =
    "usage: ReviewQueueMain membership <split-assignment.tsv> <PROVENANCE.tsv> <run dir> " +
      "<heldout|dev> <manifest .jsonl> <blind view .jsonl> <seed> [capacity, default " +
      s"${GoldMembership.DefaultCapacity}] | " +
      "queue <manifest .jsonl> <run dir> <internal queue .jsonl> " +
      s"[$ExpectSplitFlag=<heldout|dev>] | " +
      "diagnostics <split-assignment.tsv> <PROVENANCE.tsv> <run dir> <internal .jsonl> " +
      "<blind .jsonl> [targetTotal] [minPerStratum] [seed]"

  private def queueOptions(rest: Vector[String]): Either[String, Option[String]] =
    rest match {
      case Vector() => Right(None)
      case Vector(a) if a.startsWith(s"$ExpectSplitFlag=") =>
        val v = a.stripPrefix(s"$ExpectSplitFlag=")
        if (SelectionUnits.Splits.contains(v)) Right(Some(v))
        else Left(s"$ExpectSplitFlag must be heldout or dev")
      case _ => Left(usage)
    }

  /** Refuses a manifest whose split is not the expected one. */
  private def checkSplit(members: Vector[GoldMember], want: Option[String]): Either[String, Unit] =
    want match {
      case None => Right(())
      case Some(w) =>
        val splits = members.map(_.split).distinct.sorted
        if (splits == Vector(w)) Right(())
        else
          Left(
            s"the membership manifest is split=${splits.mkString("+")}, not $ExpectSplitFlag=$w; " +
              "a manifest of another split is refused"
          )
    }

  /** Refuses a run that does not hold every member's endpoint records. */
  private def checkRunRecords(
      members: Vector[GoldMember],
      run: CuratedRun
  ): Either[String, Unit] = {
    val missing = RunQueue.missingEndpoints(members, run)
    if (missing.isEmpty) Right(())
    else {
      val ms = missing.map(_._1).distinct
      val runs = members.map(_.generatingRunId).distinct.mkString(",")
      Left(
        s"${ms.size} of ${members.size} members have endpoint records that are not in the run " +
          s"${run.runDir} (a run of another split or of other inputs; the membership was drawn " +
          s"from run $runs); first: " +
          missing.take(5).map { case (m, e) => s"$m endpoint $e" }.mkString(", ")
      )
    }
  }

  def run(args: Vector[String], out: String => Unit): Int =
    runWithConfig(args, out, () => ConfigEvidence.system())

  /**
   * As [[run]], with the configuration source given explicitly (no file evidence). The `queue` and
   * `diagnostics` modes decide pairs and therefore require the configuration; `membership` loads no
   * policy and reads no configuration.
   */
  def run(
      args: Vector[String],
      out: String => Unit,
      loadConfig: () => Either[Vector[ConfigError], ScholarChainConfig]
  ): Int = runWithConfig(args, out, () => ConfigEvidence.injected(loadConfig))

  /**
   * As [[run]], with the configuration and its file evidence from `source`. The source is read only
   * by `queue` and `diagnostics`.
   */
  def runWithConfig(args: Vector[String], out: String => Unit, source: () => LoadedConfig): Int = {
    lazy val loaded = source()
    val result: Either[String, Vector[String]] = args match {
      case "membership" +: sa +: pv +: dir +: split +: manifest +: blindOut +: seed +: rest
          if rest.size <= 1 =>
        val capacity = rest.headOption.fold(Option(GoldMembership.DefaultCapacity))(_.toIntOption)
        if (capacity.isEmpty) Left("capacity must be an integer")
        else {
          val design = MembershipDesign(split, capacity, Some(seed))
          val mPath = Path.of(manifest)
          val bPath = Path.of(blindOut)
          for {
            _ <- distinctNew(Vector(mPath, bPath))
            u <- units(sa, pv)
            run <- CuratedRunReader.read(Path.of(dir))
            plan <- GoldMembership.planOfRun(run, u, design).left.map(_.mkString("; "))
            blind <- RunQueue.blindOfMembers(plan.members, run, seed).left.map(_.mkString("; "))
            _ <- writeAll(
              Vector(
                mPath -> GoldMembership.toJsonl(plan.members),
                bPath -> BlindView.toJsonl(blind)
              )
            )
          } yield GoldMembership.summary(plan) ++ Vector(
            s"blind view rows=${blind.size} schema=${BlindView.RowSchemaVersion} " +
              s"labelGuide=${LabelGuide.Version} (${LabelGuide.Resource})",
            "freeze: record the manifest sha256 above in the human gate before labeling; " +
              "GoldSetEvalMain --manifest-sha256-file refuses any other manifest",
            s"freeze file: in ${Option(mPath.toAbsolutePath.normalize.getParent).fold(".")(_.toString)} " +
              s"run: shasum -a 256 ${mPath.getFileName} > ${mPath.getFileName}.sha256 (one line " +
              s"'<64 lowercase hex>  ${mPath.getFileName}' and one LF); GoldSetEvalMain " +
              s"--manifest-sha256-file=<that file> $ExpectSplitFlag=$split verifies it"
          )
        }
      case "queue" +: manifest +: dir +: internal +: rest if rest.size <= 1 =>
        val iPath = Path.of(internal)
        for {
          want <- queueOptions(rest)
          _ <- distinctNew(Vector(iPath))
          members <- GoldMembership.readFile(Path.of(manifest)).left.map(_.mkString("; "))
          _ <- checkSplit(members, want)
          p <- policyOf(loaded.result)
          run <- CuratedRunReader.read(Path.of(dir))
          _ <- checkRunRecords(members, run)
          q = RunQueue.resolveMembers(p, members, run)
          _ <- writeAll(Vector(iPath -> ReviewQueue.toJsonl(q.rows)))
        } yield (RunQueue.summary(q, members.size) ++
          want.map(w => s"expect-split=$w matches the manifest").toVector :+
          settingsLine(p)) ++ ConfigEvidence.lines(loaded)
      case "diagnostics" +: sa +: pv +: dir +: internal +: blindOut +: rest if rest.size <= 3 =>
        val design = SamplingDesign(
          rest.lift(0).flatMap(_.toIntOption).getOrElse(SamplingDesign.Default.targetTotal),
          rest.lift(1).flatMap(_.toIntOption).getOrElse(SamplingDesign.Default.minPerStratum),
          rest.lift(2).getOrElse(SamplingDesign.Default.seed)
        )
        val iPath = Path.of(internal)
        val bPath = Path.of(blindOut)
        for {
          _ <- distinctNew(Vector(iPath, bPath))
          u <- units(sa, pv)
          p <- policyOf(loaded.result)
          run <- CuratedRunReader.read(Path.of(dir))
          r <- RunQueue.resolveDiagnostics(p, run, design, u).left.map(_.mkString("; "))
          blind <- RunQueue.blind(r, run, design.seed).left.map(_.mkString("; "))
          _ <- writeAll(
            Vector(iPath -> ReviewQueue.toJsonl(r.build.rows), bPath -> BlindView.toJsonl(blind))
          )
        } yield RunQueue.summary(r) ++ Vector(s"blind view rows=${blind.size}", settingsLine(p)) ++
          ConfigEvidence.lines(loaded)
      case _ => Left(usage)
    }
    result match {
      case Left(e) => out(s"error: $e"); 2
      case Right(lines) => lines.foreach(out); 0
    }
  }
}
