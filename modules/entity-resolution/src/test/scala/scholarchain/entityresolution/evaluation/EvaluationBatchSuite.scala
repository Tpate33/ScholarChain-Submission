package scholarchain.entityresolution.evaluation

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import upickle.default.write

import scholarchain.core.{ExactId, ExactIdScheme, MatchCandidate, MatchOutcome}
import scholarchain.entityresolution.policy.*
import scholarchain.entityresolution.policy.PolicyFixtures.*
import scholarchain.entityresolution.review.{
  EndpointRef,
  GoldMember,
  GoldMembership,
  ReviewQueue,
  ReviewQueueRow
}

/**
 * ER-P5-11 evaluation batch (decisions-09 item 2): RV-P5-04 m1 (threshold sensitivity respects the
 * acceptance gates), m6 (effective thresholds, prompt and model versions reported; external
 * configuration applied), N3 (false-negative examples of every non-accept outcome), RV-P5-05 #4
 * (programmatic duplicate and non-canonical labels refused) and #5 (the freeze hash is of the bytes
 * parsed). SYNTHETIC TEST FIXTURES ONLY: every label here is invented inside the test to check
 * arithmetic and refusals; none is gold data and no result here is a gold-set result.
 */
class EvaluationBatchSuite extends munit.FunSuite {
  import SyntheticEval.{label, member, row, P}

  // ------------------------------------------------------------------ fixtures (SYNTHETIC)

  private val strong: Seq[(String, Double)] = Seq(
    "title.exact" -> 1.0,
    "title.sim" -> 1.0,
    "title.token_jaccard" -> 1.0,
    "authors.both" -> 1.0,
    "authors.family_jaccard" -> 1.0,
    "authors.first_family_agree" -> 1.0,
    "authors.sequence_lcs_ratio" -> 0.8
  )
  private val sameVenue: Seq[(String, Double)] =
    Seq("venue.id_both" -> 1.0, "venue.id_agree" -> 1.0)
  private val dk1 = ExactId(ExactIdScheme.DblpKey, "journals/example/SynA")
  private val dk2 = ExactId(ExactIdScheme.DblpKey, "conf/example/SynB")

  private val workRefs: Map[String, EndpointRef] = Map(
    workA -> EndpointRef(
      workA,
      "work",
      "dblp",
      "journals/example/SynA",
      "dblp-2026-10-03",
      "dblp-raw/1.0.0",
      candidateRunId,
      Some(workA),
      None,
      Vector("SYNTHETIC")
    ),
    workB -> EndpointRef(
      workB,
      "work",
      "crossref",
      "10.9999/example.1",
      "crossref-synthetic",
      "crossref-raw/1.0.0",
      candidateRunId,
      Some(workB),
      None,
      Vector("SYNTHETIC")
    )
  )

  /**
   * (tag, candidate, SYNTHETIC label) of work pairs whose system decision comes from the Resolver.
   */
  private val gated: Vector[(String, MatchCandidate, String)] = Vector(
    // Stage uncertain, score above the accept threshold: the Resolver caps it to UNRESOLVED.
    ("stage-blocked", syntheticWork(strong), "match"),
    // A dblp-key disagreement on an established stage (same venue): capped by the disagreement.
    ("id-blocked", syntheticWork(strong ++ sameVenue, Vector(dk1), Vector(dk2)), "non-match"),
    // Established stage, no disagreement: score acceptance is reachable.
    ("open", syntheticWork(strong ++ sameVenue), "match"),
    (
      "open-mid",
      syntheticWork(Seq("title.sim" -> 1.0, "title.token_jaccard" -> 1.0) ++ sameVenue),
      "non-match"
    ),
    // Stage uncertain, low score: REJECTED stands, the stage cap never touches rejection.
    ("low-blocked", syntheticWork(Seq("title.sim" -> 0.5)), "non-match")
  )

  /** The row of a Resolver outcome, moved to distinct SYNTHETIC endpoints (one member per tag). */
  private def gatedRow(tag: String, c: MatchCandidate): ReviewQueueRow = {
    val sys = ReviewQueue.row(policy, decide(c), workRefs).fold(e => fail(e), identity).system
    row(s"gated-$tag", "work", sys.outcome, sys.path, sys.score).copy(system = sys)
  }

  private lazy val gatedRows: Vector[(ReviewQueueRow, String, MatchCandidate)] =
    gated.map { case (tag, c, l) => (gatedRow(tag, c), l, c) }

  private def evaluateRows(
      rows: Vector[(ReviewQueueRow, String)],
      settings: Option[EffectiveSettings] = None
  ): EvaluationReport = {
    val q = rows.map(_._1)
    val m = q.map(member(_, q.size)).sortBy(_.memberId)
    GoldSetEvaluation
      .evaluate(m, q, rows.map { case (r, l) => label(r, l) }, settings = settings)
      .fold(e => fail(e.mkString("; ")), identity)
  }

  // ------------------------------------------------------------------ RV-P5-04 m1

  test("m1: the Resolver's score gate records which rows a threshold change could accept") {
    val byTag = gatedRows.map { case (r, _, _) => r.memberId -> r }.toMap
    def gate(tag: String) =
      gatedRows.find(_._1.candidateId == row(s"gated-$tag", "work", "", "", 0).candidateId).get._1
    val stage = gate("stage-blocked").system
    assertEquals((stage.outcome, stage.path), ("UNRESOLVED", "probabilistic"))
    assert(stage.score >= policy.thresholds.accept, stage.score.toString)
    assertEquals(
      stage.scoreGate,
      Some(ScoreGate(false, true, Vector(Rules.acceptBlockedByStage(MatchPath.Probabilistic))))
    )
    val id = gate("id-blocked").system
    assertEquals((id.outcome, id.path), ("UNRESOLVED", "probabilistic"))
    assertEquals(
      id.scoreGate,
      Some(ScoreGate(false, true, Vector(Rules.acceptBlocked(MatchPath.Probabilistic))))
    )
    assertEquals(gate("open").system.scoreGate, Some(ScoreGate(true, true, Vector.empty)))
    assertEquals(gate("low-blocked").system.outcome, "REJECTED")
    assertEquals(gate("low-blocked").system.scoreGate.map(_.rejectReachable), Some(true))
    assertEquals(gate("low-blocked").system.scoreGate.map(_.acceptReachable), Some(false))
    // Exact-id and deterministic outcomes never reach the score path.
    val exact = decide(syntheticWork(strong, Vector(dk1), Vector(dk1)))
    assertEquals(exact.scoreGate, Some(ScoreGate(false, false, Vector.empty)))
    assert(byTag.size == gated.size)
  }

  test("m1: threshold sensitivity equals re-resolving every row with the alternative thresholds") {
    val report = evaluateRows(gatedRows.map { case (r, l, _) => (r, l) })
    val points = report.sensitivity.filter(_.entityKind == "work")
    assertEquals(points.size, GoldSetEvaluation.DefaultGrid.size)
    points.foreach { pt =>
      // The independent oracle: the Resolver itself under the alternative thresholds.
      val alt = policy.copy(thresholds = Thresholds(pt.accept, pt.reject))
      val decided = gatedRows.map { case (_, l, c) => (decide(c, alt), l) }
      def n(o: MatchOutcome, l: String) = decided.count { case (x, g) =>
        x.path == MatchPath.Probabilistic && x.outcome == o && g == l
      }
      val clue = s"accept=${pt.accept} reject=${pt.reject}"
      assertEquals(
        (pt.tp, pt.fp, pt.tn, pt.fn),
        (
          n(MatchOutcome.Accepted, "match"),
          n(MatchOutcome.Accepted, "non-match"),
          n(MatchOutcome.Rejected, "non-match"),
          n(MatchOutcome.Rejected, "match")
        ),
        clue
      )
      val wouldAccept = gatedRows.count { case (r, _, _) => r.system.score >= pt.accept }
      val blockedByResolver = decided.count { case (x, _) =>
        x.path == MatchPath.Probabilistic && x.outcome == MatchOutcome.Unresolved &&
        x.score.probability >= pt.accept
      }
      assertEquals(pt.blockedAccept, blockedByResolver, clue)
      assertEquals(pt.tp + pt.fp + pt.blockedAccept, wouldAccept, clue)
      assertEquals(pt.blockedReject, 0, clue)
      assertEquals(pt.gateUnknown, 0, clue)
      assertEquals(
        pt.tp + pt.fp + pt.tn + pt.fn + pt.undecided + pt.blockedAccept,
        gated.size,
        clue
      )
    }
    // At the configured thresholds both capped rows are reported as blocked, each under its rule,
    // and neither is counted as a TP or FP (the pre-fix table counted both).
    val cur = points.find(p => p.accept == 0.85 && p.reject == 0.2).get
    assertEquals(cur.blockedAccept, 2)
    assertEquals(
      cur.blockedAcceptByRule.toMap,
      Map(
        Rules.acceptBlockedByStage(MatchPath.Probabilistic) -> 1,
        Rules.acceptBlocked(MatchPath.Probabilistic) -> 1
      )
    )
    assertEquals((cur.tp, cur.fp), (1, 0))
    assertEquals(report.sensitivityMethod, GoldSetEvaluation.SensitivityMethod)
  }

  test("m1: a row without a recorded gate is never assumed decidable") {
    val (r, l, _) = gatedRows.find(_._2 == "match").get
    val open = gatedRows.find(_._1.system.scoreGate.exists(_.acceptReachable)).get
    val unknown = open._1.copy(system = open._1.system.copy(scoreGate = None))
    val report = evaluateRows(Vector(unknown -> "match", r -> l))
    val pt = report.sensitivity
      .find(p => p.entityKind == "work" && p.accept == 0.6 && p.reject == 0.05)
      .get
    assertEquals(pt.gateUnknown, 1)
    assertEquals(pt.tp + pt.fp + pt.tn + pt.fn + pt.undecided + pt.blockedAccept, 1)
  }

  // ------------------------------------------------------------------ RV-P5-04 m6

  private def files(
      name: String,
      q: Vector[ReviewQueueRow],
      m: Vector[GoldMember],
      l: Vector[GoldLabel]
  ): (Vector[String], String) = {
    val dir = workDir(name)
    val mf = dir.resolve("manifest-synthetic.jsonl")
    val qf = dir.resolve("queue.jsonl")
    val lf = dir.resolve("labels-synthetic.jsonl")
    Files.writeString(mf, GoldMembership.toJsonl(m), StandardCharsets.UTF_8)
    Files.writeString(qf, ReviewQueue.toJsonl(q), StandardCharsets.UTF_8)
    Files.writeString(lf, l.map(x => write(x)).mkString("\n"), StandardCharsets.UTF_8)
    (
      Vector(mf.toString, qf.toString, lf.toString) ++ SyntheticEval.freezeArgs(mf),
      GoldMembership.manifestSha256(m)
    )
  }

  private def runMain(
      args: Vector[String],
      loader: () => Either[
        Vector[scholarchain.core.config.ConfigError],
        scholarchain.core.config.ScholarChainConfig
      ] = configLoader()
  ): (Int, Vector[String]) = {
    val out = Vector.newBuilder[String]
    val code = GoldSetEvalMain.run(args, out += _, loader)
    (code, out.result())
  }

  test("m6: every run reports the effective thresholds, band, versions, prompt and model") {
    val (q, m, l) = SyntheticEval.bigSet(195, 5, 3)
    val (args, _) = files("er-p5-11-settings", q, m, l)
    val (code, text) = runMain(args)
    assertEquals(code, 0, text.mkString("\n"))
    val settings = text.find(_.startsWith("settings ")).getOrElse(fail(text.mkString("\n")))
    assertEquals(
      settings,
      "settings policy=er-policy.v8 blocking=er-blocking.v2 features=er-features.v2 accept=0.85 " +
        "reject=0.2 llmBand=[0.4,0.7] prompt=er-adjudication-prompt.v2 " +
        "model=none (no adjudicator ran) config=applied split=heldout"
    )
    val json = ujson.read(text.find(_.startsWith("report-json: ")).get.stripPrefix("report-json: "))
    val s = json("settings")
    assertEquals(
      (
        s("acceptThreshold").num,
        s("rejectThreshold").num,
        s("llmBandLower").num,
        s("llmBandUpper").num
      ),
      (0.85, 0.2, 0.4, 0.7)
    )
    assertEquals(s("promptVersion").str, "er-adjudication-prompt.v2")
    assertEquals(s("configuredModelId").str, syntheticModelId)
    assertEquals(json("adjudicationModel").str, GoldSetEvaluation.NoAdjudicator)
    assert(text.exists(_.startsWith("sensitivity work accept=")), text.mkString("\n"))
    assert(text.exists(_.startsWith("sensitivity method: resolver-score-gate.v1")))
    // Below the minimum (metrics withheld) the settings line is printed too.
    val (fq, fm, fl) = SyntheticEval.bigSet(10, 0, 0)
    val (fewArgs, _) = files("er-p5-11-settings-few", fq, fm, fl)
    val (fewCode, few) = runMain(fewArgs)
    assertEquals(fewCode, GoldSetEvalMain.BelowMinimumExit)
    assert(few.exists(_.startsWith("settings policy=er-policy.v8")), few.mkString("\n"))
  }

  test(
    "m6: configured thresholds are the ones checked; a mismatch or a missing configuration is refused"
  ) {
    val (q, m, l) = SyntheticEval.bigSet(195, 5, 3)
    val (args, _) = files("er-p5-11-config", q, m, l)
    val cases = Vector(
      "other thresholds" -> (configLoader(configText(accept = 0.9)), "not the configured"),
      "other band" -> (configLoader(configText(lower = 0.3, upper = 0.6)), "not the configured"),
      "retired policy v6" -> (
        configLoader(configText(policyVersion = "er-policy.v6")),
        "configuration refused"
      ),
      "other prompt" -> (
        configLoader(configText(promptVersion = "er-adjudication-prompt.v1")),
        "configuration refused"
      ),
      "no configuration" -> (noConfig, "SCHOLARCHAIN_CONFIG_FILES")
    )
    cases.foreach { case (clue, (loader, why)) =>
      val (code, text) = runMain(args, loader)
      assertEquals(code, 2, s"$clue: ${text.mkString("\n")}")
      assert(text.exists(_.contains(why)), s"$clue: ${text.mkString("\n")}")
      assert(!text.exists(_.startsWith("report-json")), clue)
      assert(!text.exists(_.contains("precision=")), clue)
    }
    // Rows that record no thresholds cannot be checked against the configuration: refused.
    val bare = q.map(r => r.copy(system = r.system.copy(thresholds = None)))
    val (bareArgs, _) = files("er-p5-11-config-bare", bare, m, l)
    val (bareCode, bareText) = runMain(bareArgs)
    assertEquals(bareCode, 2)
    assert(bareText.exists(_.contains("records no thresholds")), bareText.mkString("\n"))
  }

  test("m6: the model is the configured models.llmModelId only when an adjudication is recorded") {
    val (q, m, l) = SyntheticEval.bigSet(195, 5, 3)
    val adjudicated = q.updated(
      0,
      q(0).copy(system =
        q(0).system.copy(
          modelVersion = Some(syntheticModelId),
          promptVersion = Some(policy.adjudication.promptVersion)
        )
      )
    )
    val (args, _) = files("er-p5-11-model", adjudicated, m, l)
    val (code, text) = runMain(args)
    assertEquals(code, 0, text.mkString("\n"))
    assert(
      text.exists(l =>
        l.startsWith("settings ") && l.contains(s"model=$syntheticModelId config=applied")
      ),
      text.mkString("\n")
    )
    val other =
      q.updated(0, q(0).copy(system = q(0).system.copy(modelVersion = Some("fake-model.other"))))
    val (otherArgs, _) = files("er-p5-11-model-other", other, m, l)
    val (otherCode, otherText) = runMain(otherArgs)
    assertEquals(otherCode, 2)
    assert(
      otherText.exists(_.contains("was adjudicated by model fake-model.other")),
      otherText.mkString("\n")
    )
    val prompt = q.updated(
      0,
      q(0).copy(system =
        q(0).system.copy(
          modelVersion = Some(syntheticModelId),
          promptVersion = Some("er-adjudication-prompt.v1")
        )
      )
    )
    val (promptArgs, _) = files("er-p5-11-model-prompt", prompt, m, l)
    assertEquals(runMain(promptArgs)._1, 2)
    // Library call without configuration: nothing is invented.
    val r = GoldSetEvaluation.evaluate(m, q, l).fold(e => fail(e.mkString), identity)
    assertEquals(r.settings, None)
    assertEquals(r.adjudicationModel, GoldSetEvaluation.NoAdjudicator)
    assert(
      GoldSetEvalMain.settingsLine(r).contains("thresholds=not-supplied"),
      GoldSetEvalMain.settingsLine(r)
    )
  }

  // ------------------------------------------------------------------ RV-P5-04 N3

  test(
    "N3: false-negative examples cover REJECTED, UNRESOLVED, CONFLICT and not-generated matches"
  ) {
    val rejected =
      (0 until 12).map(i => row(s"n3-rej$i", "researcher", "REJECTED", P, 0.1) -> "match")
    val unresolved =
      (0 until 3).map(i => row(s"n3-un$i", "researcher", "UNRESOLVED", P, 0.5) -> "match")
    val conflict =
      (0 until 2).map(i => row(s"n3-cf$i", "researcher", "CONFLICT", "exact-id", 0.9) -> "match")
    val notGen = (0 until 2).map(i => row(s"n3-ng$i", "researcher", "ACCEPTED", P, 0.9) -> "match")
    val fps =
      (0 until 11).map(i => row(s"n3-fp$i", "researcher", "ACCEPTED", P, 0.9) -> "non-match")
    val all = (rejected ++ unresolved ++ conflict ++ notGen ++ fps).toVector
    val q = all.map(_._1)
    val m = q.map(member(_, q.size)).sortBy(_.memberId)
    val ng = notGen.map(_._1.memberId).toSet
    val r = GoldSetEvaluation
      .evaluate(m, q.filterNot(x => ng(x.memberId)), all.map { case (x, l) => label(x, l) })
      .fold(e => fail(e.mkString), identity)
    assertEquals(
      r.falseNegativesAvailable.toMap,
      Map("REJECTED" -> 12, "UNRESOLVED" -> 3, "CONFLICT" -> 2, "NOT-GENERATED" -> 2)
    )
    assertEquals(r.falseNegatives.size, 10, "the minimum of 10 is listed when available")
    assertEquals(
      r.falseNegatives.map(_.outcome).toSet,
      Set("REJECTED", "UNRESOLVED", "CONFLICT", "NOT-GENERATED")
    )
    assert(r.falseNegatives.forall(_.goldLabel == "match"))
    val notGenerated = r.falseNegatives.filter(_.outcome == GoldSetEvaluation.NotGenerated)
    assert(notGenerated.nonEmpty)
    notGenerated.foreach { e =>
      assert(ng(e.memberId))
      assertEquals(
        (e.rowId, e.candidateId, e.score, e.rulesFired),
        (None, None, None, Vector.empty)
      )
      assertEquals(e.path, GoldSetEvaluation.NotGeneratedPath)
      assert(e.sourceA.startsWith("dblp:journals/example/Syn@dblp-2026-10-03"), e.sourceA)
    }
    assertEquals((r.falsePositives.size, r.falsePositivesAvailable), (10, 11))
    assertEquals(r.errorExampleMinimum, 10)
    // A smaller example limit is refused (the minimum is kept), in the library and at the CLI.
    assert(
      GoldSetEvaluation.evaluate(m, q, all.map { case (x, l) => label(x, l) }, maxErrors = 5).isLeft
    )
    val (bq, bm, bl) = SyntheticEval.bigSet(195, 5, 3)
    val (args, _) = files("er-p5-11-n3", bq, bm, bl)
    assertEquals(runMain(args :+ "--max-errors=5")._1, 2)
    val (code, text) = runMain(args)
    assertEquals(code, 0, text.mkString("\n"))
    assert(
      text.exists(
        _.startsWith(
          "false negatives listed=0 of available=0 (gold matches not ACCEPTED: REJECTED=0 " +
            "UNRESOLVED=0 CONFLICT=0 NOT-GENERATED=0) (fewer than 10 exist; all are listed)"
        )
      ),
      text.mkString("\n")
    )
    assert(
      text.exists(_.startsWith("false positives listed=10 of available=97")),
      text.mkString("\n")
    )
  }

  // ------------------------------------------------------------------ RV-P5-05 #4

  test("#4: evaluate refuses a member labeled twice; coverage refuses a non-canonical label") {
    import SyntheticEval.{labels, manifest, queue}
    val twice = GoldSetEvaluation.evaluate(manifest, queue, labels :+ labels.head)
    assert(twice.left.exists(_.exists(_.contains("has 2 labels"))), twice.toString)
    val padded = labels.updated(0, labels.head.copy(label = Some("match ")))
    assert(GoldSetEvaluation.coverage(manifest, queue, padded).isLeft)
    assert(GoldSetEvaluation.coverage(manifest, queue, labels :+ labels.head).isLeft)
    val ok =
      GoldSetEvaluation.coverage(manifest, queue, labels).fold(e => fail(e.mkString), identity)
    val report =
      GoldSetEvaluation.evaluate(manifest, queue, labels).fold(e => fail(e.mkString), identity)
    assertEquals(ok, report.coverage)
    assertEquals(report.reviewedPairs, report.coverage.humanReviewed)
  }

  // ------------------------------------------------------------------ RV-P5-05 #5

  test("#5: the freeze hash is of exactly the bytes that are parsed (one read)") {
    import SyntheticEval.manifest
    val dir = workDir("er-p5-11-freeze")
    val lf = dir.resolve("manifest-synthetic.jsonl")
    Files.writeString(lf, GoldMembership.toJsonl(manifest), StandardCharsets.UTF_8)
    assertEquals(
      GoldMembership.readFileWithSha256(lf),
      Right((GoldMembership.manifestSha256(manifest), manifest))
    )
    assertEquals(
      GoldMembership.fileSha256(lf).toOption.map(s => (s, manifest)),
      GoldMembership.readFileWithSha256(lf).toOption
    )
    // CRLF line ends parse like readFile; the hash is of the CRLF bytes, not of the members.
    val crlf = dir.resolve("manifest-crlf.jsonl")
    Files.writeString(
      crlf,
      GoldMembership.toJsonl(manifest).replace("\n", "\r\n"),
      StandardCharsets.UTF_8
    )
    val (crlfSha, crlfMembers) =
      GoldMembership.readFileWithSha256(crlf).fold(e => fail(e.mkString), identity)
    assertEquals(crlfMembers, GoldMembership.readFile(crlf).fold(e => fail(e.mkString), identity))
    assertEquals(Right(crlfSha), GoldMembership.fileSha256(crlf))
    assertNotEquals(crlfSha, GoldMembership.manifestSha256(manifest))
    // Bytes that are not UTF-8 are refused, never decoded leniently.
    val bad: Path = dir.resolve("manifest-latin1.jsonl")
    Files.write(bad, Array[Byte](0x7b, 0xff.toByte, 0x7d, 0x0a))
    assert(GoldMembership.readFileWithSha256(bad).isLeft)
  }
}
