package scholarchain.entityresolution.evaluation

import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import java.nio.file.{Files, Path}
import java.security.MessageDigest

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import upickle.default.write

import scholarchain.core.config.{ConfigError, ScholarChainConfig}
import scholarchain.entityresolution.{ConfigEvidence, LoadedConfig}
import scholarchain.entityresolution.policy.MatchPolicy
import scholarchain.entityresolution.review.{GoldMember, GoldMembership, SelectionUnits}

/**
 * Gold-set evaluation entry point.
 *
 * Arguments: `<membership manifest .jsonl> <review queue .jsonl> <human gold labels .jsonl>
 * [--manifest-sha256-file=<membership file>.sha256 --expect-split=<heldout|dev>] [--max-errors=<n> |
 * <n>]`. Every flag may be given at most once.
 *
 *   - Labels are keyed by member ID and validated against the manifest; any unlabeled, duplicate,
 *     malformed, or untraceable label line is refused (every problem listed).
 *   - `--manifest-sha256-file` (ER-P5-15/16, decisions-15 item 2): the ONLY freeze mechanism. It
 *     names the human-recorded freeze record [[FreezeRecord]]: the file
 *     `<membership file name>.sha256` in the membership file's directory, holding exactly the
 *     `shasum -a 256` output line. It is parsed strictly; the manifest file's bytes (one read: the
 *     bytes that are parsed) must hash to the recorded value, or the evaluation is refused (exit
 *     2). The earlier `--manifest-sha256=<hex>` flag was removed (ER-P5-16) and is an unknown
 *     argument.
 *   - `--expect-split` (ER-P5-15, decisions-15 item 1): the split the caller means to evaluate. A
 *     membership manifest of another split is refused (exit 2) before anything is evaluated.
 *   - Rule (ER-P5-16): final held-out metrics require `--manifest-sha256-file`, and the file
 *     requires `--expect-split`; so every run that can print final held-out metrics carries both. A
 *     run without them can only print coverage (held-out), refusals, or DEV diagnostics, which are
 *     never final; it is allowed so that coverage and refusal checks need no freeze.
 *   - Configuration evidence (ER-P5-15): every run prints `config file=<absolute path>
 *     sha256=<hex>` for each configuration file the loader read and `config overrides=<names|none>`
 *     for the `SCHOLARCHAIN_CFG_*` variables set (names only; each overrides the files for its key,
 *     core precedence).
 *   - Labels evidence (ER-P5-17, D5 L1): every run that read the labels file prints `labels
 *     file=<absolute path> sha256=<lowercase hex>` once, before any metric (with the split marking
 *     of the report). The hash is of the raw bytes of the one read whose lines are ingested. A
 *     labels file that was not read (missing, unreadable, or not reached) gets no line.
 *   - The coverage report (members sampled; labeled match / non-match / unsure; unlabeled and
 *     excluded members with reasons; not-generated; human-reviewed) is always printed. Every line
 *     that shows the human-reviewed count also shows the definitive gold-label count (match plus
 *     non-match); the reviewed count is never presented as the definitive count (human decision
 *     2026-11-06).
 *   - Final gold metrics are printed only when at least [[GoldSetEvaluation.MinimumReviewedPairs]]
 *     members carry a human label (unsure counts as reviewed, is reported separately, and is
 *     excluded from binary precision and recall). Below it, the metrics are withheld. There is no
 *     provisional output: `--provisional` was removed (human decision 2026-11-06) and is refused.
 *   - The split comes from the manifest (human decision 2026-11-06, decisions-06). Every output
 *     line ends with `split=<heldout|dev>` and both JSON reports carry `split`. Final held-out
 *     metrics require the recorded freeze: without it a held-out evaluation that meets the minimum
 *     prints its coverage and refuses the metrics (exit 2). A dev manifest is evaluated as a
 *     development diagnostic: every line is marked `DEV`, the status is
 *     [[GoldSetEvaluation.DevStatus]], and its metrics are never final gold metrics.
 *   - Configuration (RV-P5-04 m6): the external configuration (`SCHOLARCHAIN_CONFIG_FILES`, core
 *     `ConfigLoader`) is applied with [[MatchPolicy.withConfig]]; its policy and prompt versions
 *     must be the policy's, and every queue row must have been decided under the configured
 *     thresholds and band (recorded on the row) and, when adjudicated, under the configured
 *     `models.llmModelId`. Any mismatch, or a missing configuration, is refused (exit 2). Every run
 *     prints a `settings` line with the effective thresholds, band, versions, prompt version and
 *     model (`none (no adjudicator ran)` when no row carries an adjudication); the report JSON
 *     carries the same settings.
 *   - Error examples (RV-P5-04 N3): at least [[GoldSetEvaluation.MinErrorExamples]] false positives
 *     and false negatives when that many exist (`--max-errors` below it is refused); false
 *     negatives are every gold match not ACCEPTED (REJECTED, UNRESOLVED, CONFLICT, not-generated),
 *     drawn from each category in turn; fewer are said to be fewer.
 *   - Threshold sensitivity respects the policy's acceptance gates
 *     ([[GoldSetEvaluation.SensitivityMethod]], RV-P5-04 m1).
 *
 * Writes nothing. Exit codes: 0 metrics reported (final for held-out; dev diagnostics for dev); 2
 * refused or usage error (including final held-out metrics without the freeze, a freeze mismatch,
 * and a manifest of another split); 3 evaluated but the metrics refused (fewer than the minimum of
 * human-reviewed members).
 */
object GoldSetEvalMain {
  def main(args: Array[String]): Unit = sys.exit(run(args.toVector, println))

  val BelowMinimumExit: Int = 3

  private def readLines(p: String): Either[String, Vector[String]] =
    try Right(Files.readAllLines(Path.of(p), StandardCharsets.UTF_8).asScala.toVector)
    catch { case NonFatal(e) => Left(s"cannot read $p: ${e.getMessage}") }

  /**
   * The labels evidence line (ER-P5-17; D5 L1): held-out labels stay gitignored until accepted, so
   * a git hash cannot name them. Every run that read the labels file names it (absolute, normalized
   * path) and the lowercase hex SHA-256 of the raw bytes that were parsed.
   */
  def labelsLine(path: Path, sha256: String): String =
    s"labels file=${path.toAbsolutePath.normalize} sha256=$sha256"

  /**
   * Reads the labels file ONCE (ER-P5-17) and returns its path, the SHA-256 (lowercase hex) of its
   * raw bytes, and the lines decoded from exactly those bytes, so the hash and the parse cannot
   * diverge. The lines are those `Files.readAllLines(path, UTF_8)` returns for the same bytes
   * (strict UTF-8, malformed input refused; split on `\n`, `\r\n` or `\r`; no element after a final
   * terminator), so label parsing is unchanged. A missing or unreadable file is refused with the
   * same message as before, and no hash is produced for a file that was not read.
   */
  def readLabelsWithSha256(p: String): Either[String, (Path, String, Vector[String])] =
    (try {
      val path = Path.of(p)
      Right((path, Files.readAllBytes(path)))
    } catch { case NonFatal(e) => Left(s"cannot read $p: ${e.getMessage}") })
      .flatMap { case (path, bytes) =>
        val sha = MessageDigest
          .getInstance("SHA-256")
          .digest(bytes)
          .map(b => f"${b & 0xff}%02x")
          .mkString
        (try
          Right(
            StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString
          )
        catch { case NonFatal(e) => Left(s"cannot read $p: ${e.getMessage}") })
          .map { text =>
            val parts = text.split("\r\n|\r|\n", -1).toVector
            val lines = if (parts.lastOption.contains("")) parts.init else parts
            (path, sha, lines)
          }
      }

  private def fmt(d: Option[Double]): String = d.fold("n/a")(x => f"$x%.4f")

  /** The split suffix every output line ends with. */
  def splitTag(split: String): String = s" split=$split"

  /** The line that names the kind of evaluation (always the first report line). */
  def splitLine(split: String): String =
    if (split == GoldSetEvaluation.HeldoutSplit)
      "evaluation split=heldout: final held-out gold metrics only with the recorded freeze " +
        "(--manifest-sha256-file) and at least the minimum of human-reviewed members"
    else
      s"DEV evaluation split=$split: development diagnostics on the dev split; NOT held-out " +
        "gold metrics and never final"

  /** Marks every line of a dev report; held-out lines are unchanged. */
  private def mark(split: String)(line: String): String =
    (if (split == GoldSetEvaluation.HeldoutSplit) line else s"DEV $line") + splitTag(split)

  /** Coverage lines (always printed). */
  def coverageSummary(c: Coverage): Vector[String] = coverageLines(c).map(mark(c.split))

  private def coverageLines(c: Coverage): Vector[String] =
    Vector(
      s"coverage members-sampled=${c.membersSampled} population=${c.populationSize} " +
        s"capacity=${c.capacity.fold("-")(_.toString)} seed=${c.seed.getOrElse("-")} " +
        s"rule=${c.selectionRule}",
      "coverage members " + c.membersByKind.map { case (k, n) => s"$k=$n" }.mkString(" "),
      s"coverage human-reviewed=${c.humanReviewed} (includes unsure=${c.labeledUnsure}; " +
        s"definitive gold labels=${c.definitiveLabels}) " +
        c.reviewedByKind.map { case (k, n) => s"$k=$n" }.mkString(" ") +
        s" minimum=${c.minimumReviewed} meetsMinimum=${c.meetsMinimum}",
      s"coverage definitive-gold-labels=${c.definitiveLabels} (match=${c.labeledMatch} " +
        s"non-match=${c.labeledNonMatch}; unsure excluded) " +
        c.definitiveByKind.map { case (k, n) => s"$k=$n" }.mkString(" "),
      s"coverage labeled match=${c.labeledMatch} non-match=${c.labeledNonMatch} " +
        s"unsure=${c.labeledUnsure} unlabeled=${c.unlabeled} not-generated=${c.notGenerated} " +
        s"(reviewed=${c.notGeneratedReviewed})"
    ) ++ c.exclusionsByReason.toVector.map { case (r, n) => s"coverage excluded $n: $r" }

  /** Metric lines. */
  def summary(r: EvaluationReport): Vector[String] = summaryLines(r).map(mark(r.split))

  /**
   * The effective settings line (RV-P5-04 m6): versions, thresholds, LLM band, prompt version, and
   * the adjudication model. The model is the configured `models.llmModelId` only when a valid
   * adjudication is recorded on the rows; otherwise it is [[GoldSetEvaluation.NoAdjudicator]].
   * Without settings (no configuration supplied) nothing is invented: the line says so.
   */
  def settingsLine(r: EvaluationReport): String =
    r.settings match {
      case Some(s) =>
        s"settings policy=${s.policyVersion} blocking=${s.blockingVersion} " +
          s"features=${s.featureVersion} accept=${s.acceptThreshold} reject=${s.rejectThreshold} " +
          s"llmBand=[${s.llmBandLower},${s.llmBandUpper}] prompt=${s.promptVersion} " +
          s"model=${r.adjudicationModel} config=applied"
      case None =>
        s"settings policy=${r.policyVersion} blocking=${r.blockingVersion} " +
          s"features=${r.featureVersion} thresholds=not-supplied prompt=not-supplied " +
          s"model=${r.adjudicationModel} config=not-applied"
    }

  private def summaryLines(r: EvaluationReport): Vector[String] =
    Vector(
      s"policy=${r.policyVersion} blocking=${r.blockingVersion} features=${r.featureVersion}",
      s"reviewed pairs=${r.reviewedPairs} definitive gold labels=${r.definitiveGoldLabels} " +
        s"unsure=${r.unsurePairs} minimum=${r.minimumRequired} meetsMinimum=${r.meetsMinimum}" +
        s" metrics=${r.metricsStatus}"
    ) ++ r.segments.map { s =>
      val c = s.counts
      s"${s.entityKind}/${s.path}: reviewed=${c.reviewed} definitive=${c.definitive} " +
        s"unsure=${c.unsure} TP=${c.tp} FP=${c.fp} " +
        s"TN=${c.tn} FN=${c.fn} unresolved(match/non)=${c.unresolvedMatch}/${c.unresolvedNonMatch} " +
        s"conflict(match/non)=${c.conflictMatch}/${c.conflictNonMatch} precision=${fmt(s.precision)} " +
        s"recall=${fmt(s.recall)} e2eRecall=${fmt(s.endToEndRecall)} F1=${fmt(s.f1)} " +
        s"coverage=${fmt(s.coverage)} unresolvedRate=${fmt(s.unresolvedRate)} " +
        s"notGenerated(match/non)=${c.notGeneratedMatch}/${c.notGeneratedNonMatch}"
    } ++ Vector(
      s"membership run=${r.membershipRunId} blocking=${r.membershipBlockingVersion}",
      s"not-generated gold matches=${r.notGeneratedMatches.size}",
      s"false positives listed=${r.falsePositives.size} of available=${r.falsePositivesAvailable}" +
        fewer(r.falsePositivesAvailable, r.errorExampleMinimum),
      s"false negatives listed=${r.falseNegatives.size} of available=" +
        s"${r.falseNegativesAvailable.values.sum} (gold matches not ACCEPTED: " +
        GoldSetEvaluation.MissedMatchOutcomes
          .map(o => s"$o=${r.falseNegativesAvailable.getOrElse(o, 0)}")
          .mkString(" ") + ")" +
        fewer(r.falseNegativesAvailable.values.sum, r.errorExampleMinimum)
    ) ++ r.sensitivity.map { p =>
      s"sensitivity ${p.entityKind} accept=${p.accept} reject=${p.reject} TP=${p.tp} FP=${p.fp} " +
        s"TN=${p.tn} FN=${p.fn} undecided=${p.undecided} blockedAccept=${p.blockedAccept} " +
        s"blockedReject=${p.blockedReject} gateUnknown=${p.gateUnknown} " +
        s"precision=${fmt(p.precision)} recall=${fmt(p.recall)} coverage=${fmt(p.coverage)}"
    } :+ s"sensitivity method: ${r.sensitivityMethod}"

  private def fewer(available: Int, minimum: Int): String =
    if (available < minimum) s" (fewer than $minimum exist; all are listed)" else ""

  private final case class Options(
      shaFile: Option[String],
      expectSplit: Option[String],
      maxErrors: Int,
      seen: Set[String]
  ) {
    def frozen: Boolean = shaFile.isDefined
  }

  /** Why `--provisional` is refused (removed by human decision 2026-11-06). */
  val ProvisionalRemoved: String =
    "--provisional was removed (human decision 2026-11-06): held-out metrics are withheld below " +
      s"the minimum of ${GoldSetEvaluation.MinimumReviewedPairs} human-reviewed members and are " +
      "never printed provisionally"

  /** The flags that read the freeze record and the expected split. */
  val ShaFileFlag: String = "--manifest-sha256-file"
  val ExpectSplitFlag: String = "--expect-split"

  private val MinErrors = GoldSetEvaluation.MinErrorExamples

  private def once(o: Options, flag: String): Either[String, Options] =
    if (o.seen(flag)) Left(s"$flag is given more than once")
    else Right(o.copy(seen = o.seen + flag))

  private def options(rest: Vector[String]): Either[String, Options] =
    rest
      .foldLeft[Either[String, Options]](Right(Options(None, None, MinErrors, Set.empty))) {
        case (Left(e), _) => Left(e)
        case (Right(o), a) if a.startsWith(s"$ShaFileFlag=") =>
          val v = a.stripPrefix(s"$ShaFileFlag=")
          if (v.isEmpty) Left(s"$ShaFileFlag needs the path of the freeze record")
          else once(o, ShaFileFlag).map(_.copy(shaFile = Some(v)))
        case (Right(o), a) if a.startsWith(s"$ExpectSplitFlag=") =>
          val v = a.stripPrefix(s"$ExpectSplitFlag=")
          if (SelectionUnits.Splits.contains(v))
            once(o, ExpectSplitFlag).map(_.copy(expectSplit = Some(v)))
          else Left(s"$ExpectSplitFlag must be heldout or dev")
        case (Right(o), a) if a.startsWith("--max-errors=") =>
          a.stripPrefix("--max-errors=").toIntOption.filter(_ >= MinErrors) match {
            case Some(n) => Right(o.copy(maxErrors = n))
            case None => Left(s"--max-errors must be an integer of at least $MinErrors")
          }
        case (Right(_), a) if a == "--provisional" || a.startsWith("--provisional=") =>
          Left(ProvisionalRemoved)
        case (Right(o), a) if a.toIntOption.exists(_ >= MinErrors) =>
          Right(o.copy(maxErrors = a.toInt))
        case (_, a) => Left(s"unknown argument '$a'")
      }
      .flatMap { o =>
        if (o.shaFile.isDefined && o.expectSplit.isEmpty)
          Left(s"$ShaFileFlag requires $ExpectSplitFlag=<heldout|dev>")
        else Right(o)
      }

  private val usage =
    "usage: GoldSetEvalMain <membership manifest .jsonl> <review queue .jsonl> " +
      s"<gold labels .jsonl> [$ShaFileFlag=<membership .jsonl>.sha256 " +
      s"$ExpectSplitFlag=<heldout|dev>] [--max-errors=<n>]"

  /**
   * The effective settings: the default policy with the external configuration applied
   * ([[MatchPolicy.withConfig]]) and the configured model ID. Refused (no fallback) when the
   * configuration is missing or invalid or names another policy or prompt version.
   */
  def effectiveSettings(
      loadConfig: () => Either[Vector[ConfigError], ScholarChainConfig]
  ): Either[Vector[String], EffectiveSettings] = settingsOf(loadConfig())

  private def settingsOf(
      loaded: Either[Vector[ConfigError], ScholarChainConfig]
  ): Either[Vector[String], EffectiveSettings] =
    for {
      cfg <- loaded.left.map(_.map(e => s"configuration refused: ${e.message}"))
      base <- MatchPolicy.loadDefault()
      p <- MatchPolicy
        .withConfig(base, cfg.entityResolution)
        .left
        .map(_.map(e => s"configuration refused: $e"))
      model <- MatchPolicy.modelId(cfg.models).left.map(e => Vector(s"configuration refused: $e"))
    } yield EffectiveSettings.of(p, model)

  /** Refuses a manifest whose split is not the expected one. */
  private def checkSplit(
      manifest: Vector[GoldMember],
      expected: Option[String]
  ): Either[Vector[String], Unit] =
    expected match {
      case None => Right(())
      case Some(want) =>
        val splits = manifest.map(_.split).distinct.sorted
        if (splits == Vector(want)) Right(())
        else
          Left(
            Vector(
              s"the membership manifest is split=${splits.mkString("+")}, not " +
                s"$ExpectSplitFlag=$want; a manifest of another split is refused"
            )
          )
    }

  /**
   * The freeze check of the manifest bytes `sha` against the recorded freeze record, or none.
   * Returns the `freeze=` text of the report.
   */
  private def checkFreeze(
      o: Options,
      sha: String,
      manifestPath: String
  ): Either[Vector[String], String] =
    o.shaFile match {
      case Some(f) =>
        val record = Path.of(f)
        FreezeRecord.read(record, Path.of(manifestPath)).left.map(Vector(_)).flatMap { want =>
          if (want != sha)
            Left(
              Vector(
                s"manifest sha256 $sha differs from the recorded freeze $want in $record; the " +
                  "membership is not the frozen one"
              )
            )
          else Right(s"matches file=${record.toAbsolutePath.normalize}")
        }
      case None => Right(s"not-checked (no $ShaFileFlag given)")
    }

  def run(args: Vector[String], out: String => Unit): Int =
    runWithConfig(args, out, () => ConfigEvidence.system())

  /** As [[run]], with the configuration source given explicitly (no file evidence). */
  def run(
      args: Vector[String],
      out: String => Unit,
      loadConfig: () => Either[Vector[ConfigError], ScholarChainConfig]
  ): Int =
    runWithConfig(args, out, () => ConfigEvidence.injected(loadConfig))

  /** As [[run]], with the configuration and its file evidence from `source`. */
  def runWithConfig(args: Vector[String], out: String => Unit, source: () => LoadedConfig): Int =
    args match {
      case manifestPath +: queuePath +: labelPath +: rest =>
        options(rest) match {
          case Left(e) =>
            out(s"$e; $usage")
            2
          case Right(o) =>
            val loaded = source()
            val evidence = ConfigEvidence.lines(loaded)
            // ER-P5-17: set only once the labels file was actually read (never for a file that
            // was not read); printed exactly once, before any metric.
            var labelsEvidence: Option[String] = None
            val result = for {
              settings <- settingsOf(loaded.result)
              // RV-P5-05 #5: one read; the hash is of exactly the bytes that are parsed.
              read <- GoldMembership.readFileWithSha256(Path.of(manifestPath))
              (sha, manifest) = read
              _ <- checkSplit(manifest, o.expectSplit)
              freeze <- checkFreeze(o, sha, manifestPath)
              ql <- readLines(queuePath).left.map(Vector(_))
              // ER-P5-17: one read; the hash is of exactly the bytes whose lines are ingested.
              readLabels <- readLabelsWithSha256(labelPath).left.map(Vector(_))
              (labelFile, labelSha, ll) = readLabels
              _ = (labelsEvidence = Some(labelsLine(labelFile, labelSha)))
              queue <- GoldLabels.parseQueue(ql)
              labels <- GoldLabels
                .ingest(ll, manifest)
                .left
                .map(_.map(p => s"label line ${p.line} ${p.memberId}: ${p.problem}"))
              report <- GoldSetEvaluation.evaluate(
                manifest,
                queue,
                labels,
                o.maxErrors,
                settings = Some(settings)
              )
            } yield (sha, freeze, report)
            result match {
              case Left(problems) =>
                out(s"refused: ${problems.size} problem(s)")
                problems.foreach(p => out(s"  $p"))
                evidence.foreach(out)
                labelsEvidence.foreach(out)
                2
              case Right((sha, freeze, report)) =>
                val sp = report.split
                val line = mark(sp)
                val heldout = sp == GoldSetEvaluation.HeldoutSplit
                out(splitLine(sp))
                evidence.map(line).foreach(out)
                out(line(s"manifest sha256=$sha freeze=$freeze"))
                labelsEvidence.foreach(l => out(line(l)))
                o.expectSplit.foreach(w => out(line(s"expect-split=$w matches the manifest")))
                out(line(settingsLine(report)))
                coverageSummary(report.coverage).foreach(out)
                if (report.meetsMinimum && heldout && !o.frozen) {
                  // RV-P5-04 m4: final held-out metrics only against the recorded freeze.
                  out(
                    line(
                      s"final held-out gold metrics refused: $ShaFileFlag=<path> (the freeze " +
                        "record the human wrote when the membership was frozen) is required for " +
                        "final held-out metrics; nothing but coverage is printed without it"
                    )
                  )
                  out("coverage-json: " + write(report.coverage))
                  2
                } else if (report.meetsMinimum) {
                  summary(report).foreach(out)
                  out("report-json: " + write(report))
                  0
                } else {
                  out(
                    line(
                      s"final gold metrics refused: ${report.coverage.humanReviewed} human-reviewed " +
                        s"members < ${GoldSetEvaluation.MinimumReviewedPairs} (definitive gold " +
                        s"labels=${report.coverage.definitiveLabels}, unsure=" +
                        s"${report.coverage.labeledUnsure}; unsure counts as reviewed; unsure " +
                        "members are never replaced; no provisional metrics are printed)"
                    )
                  )
                  out("coverage-json: " + write(report.coverage))
                  BelowMinimumExit
                }
            }
        }
      case _ =>
        out(usage)
        2
    }
}
