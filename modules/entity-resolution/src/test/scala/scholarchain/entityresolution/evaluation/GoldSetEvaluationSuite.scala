package scholarchain.entityresolution.evaluation

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import upickle.default.write

import scholarchain.core.{Canonical, MentionId}
import scholarchain.entityresolution.policy.PolicyFixtures.*
import scholarchain.entityresolution.review.{
  EndpointRef,
  GoldMember,
  GoldMembership,
  LabelGuide,
  MemberEndpoint,
  ReviewQueue,
  ReviewQueueRow
}

/**
 * SYNTHETIC TEST FIXTURES ONLY. The labels below are invented inside this test to check the metric
 * arithmetic and the ingest rules. They are not gold data, they are never written to any label
 * file, and no result of this suite is a gold-set result.
 */
object SyntheticEval {
  private val refs: Map[String, EndpointRef] = Vector(mentionA, mentionB).map { id =>
    id -> EndpointRef(
      id,
      "mention",
      "dblp",
      "journals/example/Syn",
      "dblp-2026-10-03",
      "dblp-raw/1.0.0",
      candidateRunId,
      Some("work:dblp:journals/example/Syn"),
      Some(0),
      Vector("SYNTHETIC")
    )
  }.toMap

  private val base: ReviewQueueRow =
    ReviewQueue
      .row(policy, decide(syntheticResearcher(nameOnly)), refs)
      .fold(e => throw new AssertionError(e), identity)

  /** A queue row with the given system decision (SYNTHETIC). */
  def row(
      tag: String,
      kind: String,
      outcome: String,
      path: String,
      score: Double
  ): ReviewQueueRow = {
    val cid = "mcand:" + Canonical.hashFields("synthetic-eval", tag)
    val Vector(a, b) = Vector(
      ok(MentionId.of(s"work:dblp:journals/example/A$tag", 0)),
      ok(MentionId.of(s"work:dblp:journals/example/B$tag", 0))
    ).sortWith(Canonical.compareUtf8(_, _) < 0)
    val member =
      GoldMembership.memberId(kind, a, b).fold(e => throw new AssertionError(e), identity)
    base.copy(
      candidateId = cid,
      memberId = member,
      rowId = ReviewQueue.rowId(member, base.policyVersion),
      entityKind = kind,
      sourceIdA = a,
      sourceIdB = b,
      endpointA = base.endpointA.copy(endpointId = a),
      endpointB = base.endpointB.copy(endpointId = b),
      system = base.system.copy(outcome = outcome, path = path, score = score)
    )
  }

  /** The SYNTHETIC manifest member of a row. */
  def member(r: ReviewQueueRow, population: Int): GoldMember =
    GoldMember(
      GoldMembership.SchemaVersion,
      r.memberId,
      r.entityKind,
      MemberEndpoint.of(r.endpointA),
      MemberEndpoint.of(r.endpointB),
      Vector("syn-unit"),
      "syn-criterion",
      "heldout",
      candidateRunId,
      r.blockingVersion,
      GoldMembership.CensusRule,
      None,
      None,
      population
    )

  /** A SYNTHETIC label for a row. */
  def label(r: ReviewQueueRow, l: String): GoldLabel =
    GoldLabel(
      GoldLabels.SchemaVersion,
      r.memberId,
      r.entityKind,
      LabelGuide.target(r.entityKind),
      Some(LabelGuide.Version),
      r.sourceIdA,
      r.sourceIdB,
      Some(l),
      Some("synthetic-fixture"),
      Some("2026-11-04T00:00:00Z"),
      None
    )

  val P = "probabilistic"
  val rows: Vector[(ReviewQueueRow, String)] = Vector(
    row("tp1", "researcher", "ACCEPTED", P, 0.90) -> "match",
    row("tp2", "researcher", "ACCEPTED", P, 0.88) -> "match",
    row("tp3", "researcher", "ACCEPTED", P, 0.86) -> "match",
    row("fp1", "researcher", "ACCEPTED", P, 0.87) -> "non-match",
    row("tn1", "researcher", "REJECTED", P, 0.10) -> "non-match",
    row("tn2", "researcher", "REJECTED", P, 0.15) -> "non-match",
    row("fn1", "researcher", "REJECTED", P, 0.18) -> "match",
    row("un1", "researcher", "UNRESOLVED", P, 0.50) -> "match",
    row("cf1", "researcher", "CONFLICT", "exact-id", 0.95) -> "non-match",
    row("us1", "researcher", "ACCEPTED", P, 0.95) -> "unsure",
    row("w1", "work", "ACCEPTED", "exact-id", 0.99) -> "match"
  )

  /**
   * A SYNTHETIC set of researcher members: `decided` labeled match/non-match (alternating, all
   * ACCEPTED), `unsure` labeled unsure (UNRESOLVED), and `unlabeled` without a label line. Every
   * member has a queue row. Returns (queue, manifest, labels).
   */
  def bigSet(
      decided: Int,
      unsure: Int,
      unlabeled: Int
  ): (Vector[ReviewQueueRow], Vector[GoldMember], Vector[GoldLabel]) = {
    val d = (0 until decided).toVector.map(i =>
      row(s"big$i", "researcher", "ACCEPTED", P, 0.9) -> (if (i % 2 == 0) "match" else "non-match")
    )
    val u = (0 until unsure).toVector.map(i =>
      row(s"bigu$i", "researcher", "UNRESOLVED", P, 0.5) -> "unsure"
    )
    val n = (0 until unlabeled).toVector.map(i => row(s"bign$i", "researcher", "REJECTED", P, 0.1))
    val q = (d ++ u).map(_._1) ++ n
    (q, q.map(member(_, q.size)).sortBy(_.memberId), (d ++ u).map { case (r, l) => label(r, l) })
  }

  /**
   * Records the freeze of a SYNTHETIC manifest as the human does (`shasum -a 256 <name> >
   * <name>.sha256` beside it) and returns the evaluation's freeze arguments for `split`.
   */
  def freezeArgs(manifest: java.nio.file.Path, split: String = "heldout"): Vector[String] = {
    val rec = manifest.resolveSibling(manifest.getFileName.toString + ".sha256")
    val sha = GoldMembership.fileSha256(manifest).fold(e => throw new AssertionError(e), identity)
    Files.writeString(rec, s"$sha  ${manifest.getFileName}\n", StandardCharsets.UTF_8)
    Vector(s"--manifest-sha256-file=$rec", s"--expect-split=$split")
  }

  val queue: Vector[ReviewQueueRow] = rows.map(_._1)
  val manifest: Vector[GoldMember] = queue.map(member(_, queue.size)).sortBy(_.memberId)
  val labels: Vector[GoldLabel] = rows.map { case (r, l) => label(r, l) }
}

class GoldSetEvaluationSuite extends munit.FunSuite {
  import SyntheticEval.*

  private val report =
    GoldSetEvaluation.evaluate(manifest, queue, labels).fold(e => fail(e.mkString), identity)

  private def seg(r: EvaluationReport, kind: String, path: String) =
    r.segments.find(s => s.entityKind == kind && s.path == path).get
  private def seg(kind: String, path: String): SegmentReport = seg(report, kind, path)

  test("researcher and work metrics are reported separately, never as one aggregate") {
    assertEquals(report.segments.map(_.entityKind).distinct, Vector("researcher", "work"))
    assert(!report.segments.exists(_.entityKind == "all"))
    assertEquals(seg("work", "all").counts.tp, 1)
    assertEquals(seg("researcher", "all").counts.reviewed, 10)
    assertEquals(seg("researcher", "all").counts.definitive, 9)
  }

  test("precision, recall, end-to-end recall, F1, coverage, and unresolved rate") {
    val s = seg("researcher", "all")
    val c = s.counts
    assertEquals((c.tp, c.fp, c.tn, c.fn, c.unsure), (3, 1, 2, 1, 1))
    assertEquals((c.unresolvedMatch, c.conflictNonMatch), (1, 1))
    assertEqualsDouble(s.precision.get, 0.75, 1e-12)
    assertEqualsDouble(s.recall.get, 0.75, 1e-12)
    assertEqualsDouble(s.endToEndRecall.get, 0.6, 1e-12)
    assertEqualsDouble(s.f1.get, 0.75, 1e-12)
    assertEqualsDouble(s.coverage.get, 7.0 / 9.0, 1e-12)
    assertEqualsDouble(s.unresolvedRate.get, 1.0 / 9.0, 1e-12)
    assertEquals(seg("work", "probabilistic").precision, None, "no prediction: undefined, not 0")
  }

  test("metrics by match path, and a full confusion matrix") {
    assertEquals(seg("researcher", "exact-id").counts.conflictNonMatch, 1)
    assertEquals(seg("researcher", "exact-id").counts.tp, 0)
    assertEquals(seg("researcher", "probabilistic").counts.tp, 3)
    assertEquals(seg("researcher", "llm-adjudicated").counts.reviewed, 0)
    assertEquals(
      report.segments.map(_.path).distinct,
      Vector(
        "all",
        "exact-id",
        "deterministic-bibliographic",
        "probabilistic",
        "llm-adjudicated",
        "not-generated"
      )
    )
    val m = seg("researcher", "all").confusion
    assertEquals(m.size, 15)
    assertEquals(m("ACCEPTED|match"), 3)
    assertEquals(m("ACCEPTED|non-match"), 1)
    assertEquals(m("ACCEPTED|unsure"), 1)
    assertEquals(m("CONFLICT|non-match"), 1)
    assertEquals(m("REJECTED|match"), 1)
    assertEquals(m("NOT-GENERATED|match"), 0)
  }

  test("error analysis lists false positives and false negatives with source traceability") {
    val fp1 = row("fp1", "researcher", "ACCEPTED", P, 0.87)
    assertEquals(report.falsePositives.map(_.memberId), Vector(fp1.memberId))
    assertEquals(report.falsePositives.map(_.candidateId), Vector(Some(fp1.candidateId)))
    // RV-P5-04 N3: the missed matches are fn1 (REJECTED) and un1 (UNRESOLVED), not only REJECTED.
    assertEquals(
      report.falseNegatives.map(e => (e.memberId, e.outcome)).toSet,
      Set(
        row("fn1", "researcher", "REJECTED", P, 0.18).memberId -> "REJECTED",
        row("un1", "researcher", "UNRESOLVED", P, 0.50).memberId -> "UNRESOLVED"
      )
    )
    val fp = report.falsePositives.head
    assert(fp.sourceA.startsWith("dblp:journals/example/Syn@dblp-2026-10-03"))
    assertEquals(fp.goldLabel, "non-match")
  }

  test("threshold sensitivity re-decides score-based rows only") {
    def at(a: Double, r: Double) =
      report.sensitivity
        .find(p => p.entityKind == "researcher" && p.accept == a && p.reject == r)
        .get
    val cur = at(0.85, 0.2)
    assertEquals((cur.tp, cur.fp, cur.tn, cur.fn, cur.undecided), (3, 1, 2, 1, 1))
    val strict = at(0.9, 0.05)
    assertEquals((strict.tp, strict.fp, strict.tn, strict.fn, strict.undecided), (1, 0, 0, 0, 7))
    assertEquals(strict.precision, Some(1.0))
    assert(report.sensitivity.forall(p => p.reject < p.accept))
    assertEquals(report.sensitivity.filter(_.entityKind == "work").map(_.tp).distinct, Vector(0))
  }

  test("the 200-pair minimum is reported, not assumed") {
    assertEquals(report.minimumRequired, 200)
    assert(!report.meetsMinimum)
    assertEquals(report.metricsStatus, "refused-below-minimum")
    assertEquals(report.coverage.humanReviewed, 11)
  }

  test("unsure counts as reviewed: 190 decided + 10 unsure meets the 200 minimum") {
    val (q, m, l) = bigSet(190, 10, 0)
    val r = GoldSetEvaluation.evaluate(m, q, l).fold(e => fail(e.mkString), identity)
    assert(r.meetsMinimum)
    assertEquals(r.metricsStatus, "final")
    assertEquals(
      (r.coverage.humanReviewed, r.coverage.labeledUnsure, r.coverage.labeledMatch),
      (200, 10, 95)
    )
    // Unsure is excluded from precision and recall.
    val c = seg(r, "researcher", "all").counts
    assertEquals((c.unsure, c.tp + c.fp), (10, 190))
    val (q2, m2, l2) = bigSet(189, 10, 0)
    val r2 = GoldSetEvaluation.evaluate(m2, q2, l2).fold(e => fail(e.mkString), identity)
    assert(!r2.meetsMinimum)
    assertEquals(r2.metricsStatus, "refused-below-minimum")
  }

  test("coverage: members sampled, labels by value, unlabeled and not-generated with reasons") {
    val unlabeled = labels.take(2).map(_.memberId).toSet
    val dropped = queue.last.memberId
    val r = GoldSetEvaluation
      .evaluate(
        manifest,
        queue.filterNot(_.memberId == dropped),
        labels.filterNot(g => unlabeled(g.memberId))
      )
      .fold(e => fail(e.mkString), identity)
    val c = r.coverage
    assertEquals(c.membersSampled, 11)
    assertEquals(c.membersByKind.toMap, Map("researcher" -> 10, "work" -> 1))
    assertEquals(
      (c.humanReviewed, c.unlabeled, c.notGenerated, c.notGeneratedReviewed),
      (9, 2, 1, 1)
    )
    assertEquals((c.labeledMatch, c.labeledNonMatch, c.labeledUnsure), (4, 4, 1))
    assertEquals(c.exclusionsByReason(GoldSetEvaluation.UnlabeledReason), 2)
    assertEquals(c.exclusionsByReason(GoldSetEvaluation.UnsureReason), 1)
    assertEquals(c.exclusionsByReason(GoldSetEvaluation.NotGeneratedReason), 1)
    assertEquals(
      c.exclusions
        .filter(_.reasons.contains(GoldSetEvaluation.UnlabeledReason))
        .map(_.memberId)
        .toSet,
      unlabeled
    )
    assertEquals(c.exclusions.map(_.memberId), c.exclusions.map(_.memberId).sorted)
  }

  test("unsure members are reported, never replaced or resampled; the manifest is unchanged") {
    // 240 members, 200 reviewed of which 30 unsure, 40 never labeled.
    val (q, m, l) = bigSet(170, 30, 40)
    val dir = workDir("gold-set-eval-no-replacement")
    val mf = dir.resolve("manifest-synthetic.jsonl")
    Files.writeString(mf, GoldMembership.toJsonl(m), StandardCharsets.UTF_8)
    val before = GoldMembership.fileSha256(mf).toOption.get
    val r = GoldSetEvaluation.evaluate(m, q, l).fold(e => fail(e.mkString), identity)
    val c = r.coverage
    assertEquals(
      (c.membersSampled, c.humanReviewed, c.labeledUnsure, c.unlabeled),
      (240, 200, 30, 40)
    )
    val unsureIds = l.filter(_.label.contains("unsure")).map(_.memberId).toSet
    assertEquals(
      c.exclusions.filter(_.reasons.contains(GoldSetEvaluation.UnsureReason)).map(_.memberId).toSet,
      unsureIds
    )
    // Nothing outside the frozen manifest appears anywhere in the report.
    val ids = m.map(_.memberId).toSet
    assert(c.exclusions.forall(e => ids(e.memberId)))
    assert((r.falsePositives ++ r.falseNegatives).forall(e => ids(e.memberId)))
    assertEquals(GoldMembership.fileSha256(mf), Right(before))
    assertEquals(GoldMembership.readFile(mf), Right(m))
    assert(r.meetsMinimum, "200 reviewed including 30 unsure")
  }

  test("GoldSetEvalMain: final metrics at 200 reviewed; manifest freeze checked by sha256") {
    val (q, m, l) = bigSet(195, 5, 3)
    val dir = workDir("gold-set-eval-final")
    val mf = dir.resolve("manifest-synthetic.jsonl")
    val qf = dir.resolve("queue.jsonl")
    val lf = dir.resolve("labels-synthetic.jsonl")
    Files.writeString(mf, GoldMembership.toJsonl(m), StandardCharsets.UTF_8)
    Files.writeString(qf, ReviewQueue.toJsonl(q), StandardCharsets.UTF_8)
    Files.writeString(lf, l.map(x => write(x)).mkString("\n"), StandardCharsets.UTF_8)
    val sha = GoldMembership.manifestSha256(m)
    val out = Vector.newBuilder[String]
    val args = Vector(mf.toString, qf.toString, lf.toString)
    val frozen = freezeArgs(mf)
    assertEquals(GoldSetEvalMain.run(args ++ frozen, out += _, configLoader()), 0)
    val text = out.result()
    assert(
      text.exists(l =>
        l.startsWith(s"manifest sha256=$sha freeze=matches file=") && l.endsWith(" split=heldout")
      ),
      text.mkString("\n")
    )
    assert(text.exists(_.startsWith("researcher/all:")))
    assert(text.exists(_.startsWith("report-json: ")))
    assert(text.exists(_.startsWith("coverage human-reviewed=200")), text.mkString("\n"))
    assert(text.exists(_.contains("unlabeled=3")), text.mkString("\n"))
    // The reviewed count (200, unsure included) and the definitive count (195) are both printed,
    // on every line that shows the reviewed count, and never confused.
    val reviewedLine = text.find(_.startsWith("coverage human-reviewed=")).get
    assert(reviewedLine.contains("definitive gold labels=195"), reviewedLine)
    assert(reviewedLine.contains("unsure=5"), reviewedLine)
    assert(
      text.exists(_.startsWith("coverage definitive-gold-labels=195 (match=98 non-match=97")),
      text.mkString("\n")
    )
    val summaryLine = text.find(_.startsWith("reviewed pairs=")).get
    assert(
      summaryLine.contains("reviewed pairs=200 definitive gold labels=195 unsure=5"),
      summaryLine
    )
    val researcherAll = text.find(_.startsWith("researcher/all:")).get
    assert(researcherAll.contains("reviewed=200 definitive=195 unsure=5"), researcherAll)
    assert(!text.exists(l => l.startsWith("labeled pairs=") || l.contains(" labeled=")))
    val bad = Vector.newBuilder[String]
    val record = mf.resolveSibling(mf.getFileName.toString + ".sha256")
    Files.writeString(record, s"${"0" * 64}  ${mf.getFileName}\n", StandardCharsets.UTF_8)
    assertEquals(GoldSetEvalMain.run(args ++ frozen, bad += _, configLoader()), 2)
    assert(bad.result().exists(_.contains("differs from the recorded freeze")))
    // ER-P5-16: the earlier hex flag is gone; it is an unknown argument (exit 2).
    val hex = Vector.newBuilder[String]
    assertEquals(
      GoldSetEvalMain.run(args :+ s"--manifest-sha256=$sha", hex += _, configLoader()),
      2
    )
    assert(hex.result().exists(_.contains("unknown argument '--manifest-sha256=")), hex.result())
    // Without the provisional flag, below the minimum the metrics are withheld.
    val few = Vector.newBuilder[String]
    Files.writeString(lf, l.take(199).map(x => write(x)).mkString("\n"), StandardCharsets.UTF_8)
    assertEquals(GoldSetEvalMain.run(args, few += _, configLoader()), 3)
    val t = few.result()
    assert(
      t.exists(_.startsWith("final gold metrics refused: 199 human-reviewed")),
      t.mkString("\n")
    )
    assert(
      t.exists(l =>
        l.startsWith("final gold metrics refused") &&
          l.contains("definitive gold labels=195, unsure=4")
      ),
      t.mkString("\n")
    )
    assert(t.exists(_.startsWith("coverage-json: ")))
    assert(!t.exists(l => l.contains("/all:") || l.startsWith("report-json")))
    assert(!t.exists(_.contains("PROVISIONAL")))
  }

  test("GoldSetEvalMain: --provisional is removed; held-out metrics are withheld below 200") {
    val dir = workDir("gold-set-eval-no-provisional")
    val mf = dir.resolve("manifest-synthetic.jsonl")
    val qf = dir.resolve("queue.jsonl")
    val lf = dir.resolve("labels-synthetic.jsonl")
    Files.writeString(mf, GoldMembership.toJsonl(manifest), StandardCharsets.UTF_8)
    Files.writeString(qf, ReviewQueue.toJsonl(queue), StandardCharsets.UTF_8)
    Files.writeString(lf, labels.map(x => write(x)).mkString("\n"), StandardCharsets.UTF_8)
    assert(manifest.forall(_.split == "heldout"), "the fixture manifest is held out")
    val base = Vector(mf.toString, qf.toString, lf.toString)
    Vector("--provisional", "--provisional=true").foreach { flag =>
      val out = Vector.newBuilder[String]
      assertEquals(GoldSetEvalMain.run(base :+ flag, out += _, configLoader()), 2, flag)
      val text = out.result()
      assert(text.exists(_.contains("--provisional was removed")), text.mkString("\n"))
      assert(!text.exists(l => l.contains("/all:") || l.contains("report-json")), flag)
      assert(!text.exists(_.contains("PROVISIONAL")), flag)
    }
    // Without the flag: coverage only, metrics withheld (11 reviewed < 200).
    val out = Vector.newBuilder[String]
    assertEquals(
      GoldSetEvalMain.run(base, out += _, configLoader()),
      GoldSetEvalMain.BelowMinimumExit
    )
    val text = out.result()
    assert(!text.exists(l => l.contains("/all:") || l.startsWith("report-json")))
    assert(text.exists(_.contains("definitive gold labels=10")), text.mkString("\n"))
  }

  test("reviewed and definitive counts are distinct fields of the report and of every segment") {
    val (q, m, l) = bigSet(170, 30, 40)
    val r = GoldSetEvaluation.evaluate(m, q, l).fold(e => fail(e.mkString), identity)
    assertEquals((r.reviewedPairs, r.definitiveGoldLabels, r.unsurePairs), (200, 170, 30))
    assertEquals((r.coverage.humanReviewed, r.coverage.definitiveLabels), (200, 170))
    assertEquals(r.coverage.definitiveByKind.toMap, Map("researcher" -> 170, "work" -> 0))
    val c = seg(r, "researcher", "all").counts
    assertEquals((c.reviewed, c.definitive, c.unsure), (200, 170, 30))
    // Binary metrics use only definitive labels: coverage = decided / definitive.
    assertEqualsDouble(seg(r, "researcher", "all").coverage.get, 170.0 / 170.0, 1e-12)
    val json = write(r)
    assert(json.contains("\"definitiveGoldLabels\":170") && json.contains("\"reviewedPairs\":200"))
  }

  /** Writes a SYNTHETIC manifest, queue and label file; returns (args, manifest sha256). */
  private def evalFiles(
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
    (Vector(mf.toString, qf.toString, lf.toString), GoldMembership.manifestSha256(m))
  }

  private def runMain(
      args: Vector[String],
      config: String = configText()
  ): (Int, Vector[String]) = {
    val out = Vector.newBuilder[String]
    val code = GoldSetEvalMain.run(args, out += _, configLoader(config))
    (code, out.result())
  }

  test(
    "m4: final held-out metrics require --manifest-sha256; without it only coverage is printed"
  ) {
    val (q, m, l) = bigSet(195, 5, 3)
    val (args, sha) = evalFiles("gold-set-eval-freeze-required", q, m, l)
    val (code, text) = runMain(args)
    assertEquals(code, 2, text.mkString("\n"))
    assert(
      text.exists(_.startsWith("final held-out gold metrics refused: --manifest-sha256")),
      text.mkString("\n")
    )
    assert(text.exists(_.contains("freeze=not-checked")), text.mkString("\n"))
    assert(text.exists(_.startsWith("coverage human-reviewed=200")), text.mkString("\n"))
    assert(!text.exists(l => l.contains("/all:") || l.startsWith("report-json")), "metrics shown")
    assert(!text.exists(_.contains("precision=")))
    // With the recorded freeze record the same files report final metrics.
    assertEquals(sha, GoldMembership.fileSha256(java.nio.file.Path.of(args.head)).toOption.get)
    val (ok, okText) = runMain(args ++ freezeArgs(java.nio.file.Path.of(args.head)))
    assertEquals(ok, 0, okText.mkString("\n"))
    assert(okText.exists(_.startsWith("researcher/all:")))
  }

  test("m5: every output line and both JSON reports name the split, taken from the manifest") {
    val (q, m, l) = bigSet(195, 5, 3)
    val (args, _) = evalFiles("gold-set-eval-split-heldout", q, m, l)
    val (code, text) = runMain(args ++ freezeArgs(java.nio.file.Path.of(args.head)))
    assertEquals(code, 0, text.mkString("\n"))
    assert(text.head.startsWith("evaluation split=heldout"), text.head)
    text
      .filterNot(l => l.startsWith("report-json: ") || l.startsWith("evaluation split="))
      .foreach(l => assert(l.endsWith(" split=heldout"), l))
    val json = text.find(_.startsWith("report-json: ")).get.stripPrefix("report-json: ")
    val parsed = ujson.read(json)
    assertEquals(parsed("split").str, "heldout")
    assertEquals(parsed("coverage")("split").str, "heldout")
    assertEquals(parsed("metricsStatus").str, GoldSetEvaluation.FinalStatus)
    // Below the minimum the coverage JSON names the split too.
    val (fq, fm, fl) = bigSet(10, 0, 0)
    val (fewArgs, _) = evalFiles("gold-set-eval-split-few", fq, fm, fl)
    val (fewCode, few) = runMain(fewArgs)
    assertEquals(fewCode, GoldSetEvalMain.BelowMinimumExit)
    val cov = few.find(_.startsWith("coverage-json: ")).get.stripPrefix("coverage-json: ")
    assertEquals(ujson.read(cov)("split").str, "heldout")
  }

  test("m5: a dev manifest is evaluated as DEV diagnostics on every line, never final") {
    val (q, heldoutManifest, l) = bigSet(195, 5, 3)
    val m = heldoutManifest.map(_.copy(split = "dev"))
    val r = GoldSetEvaluation.evaluate(m, q, l).fold(e => fail(e.mkString), identity)
    assertEquals(r.split, "dev")
    assertEquals(r.coverage.split, "dev")
    assertEquals(r.metricsStatus, GoldSetEvaluation.DevStatus)
    val (args, _) = evalFiles("gold-set-eval-split-dev", q, m, l)
    val (code, text) = runMain(args)
    assertEquals(code, 0, text.mkString("\n"))
    assert(text.head.startsWith("DEV evaluation split=dev"), text.head)
    assert(text.head.contains("NOT held-out gold metrics"), text.head)
    text
      .filterNot(l => l.startsWith("report-json: ") || l.startsWith("DEV evaluation"))
      .foreach { l =>
        assert(l.startsWith("DEV "), l)
        assert(l.endsWith(" split=dev"), l)
      }
    assert(
      !text.exists(_.contains(s"metrics=${GoldSetEvaluation.FinalStatus} ")),
      "dev shown final"
    )
    // A manifest that mixes splits, or names another split, is refused.
    val mixed = m.updated(0, m(0).copy(split = "heldout"))
    assert(
      GoldSetEvaluation.evaluate(mixed, q, l).left.exists(_.exists(_.contains("mixes splits")))
    )
    val other = m.map(_.copy(split = "test"))
    assert(GoldSetEvaluation.evaluate(other, q, l).isLeft)
    assertEquals(GoldSetEvaluation.splitOf(Vector.empty).isLeft, true)
  }

  test("D1: a non-canonical label is refused at ingest; counts stay consistent with every cell") {
    val l0 = labels.head
    // U+00AD SOFT HYPHEN and U+200B ZERO WIDTH SPACE built from code points (invisible in source).
    val shy = 0x00ad.toChar.toString
    val zwsp = 0x200b.toChar.toString
    val padded = Vector(" match", "match ", "Match", "non-match\t", s"un${shy}sure", s"match$zwsp")
    padded.foreach { v =>
      val ps = problems(labels.updated(0, l0.copy(label = Some(v))))
      assert(ps.exists(_.contains("is not one of")), s"'$v' accepted: $ps")
      assert(
        GoldLabels.ingest(lines(labels.updated(0, l0.copy(label = Some(v)))), manifest).isLeft,
        s"all-or-nothing for '$v'"
      )
    }
    // A label built in code that is not canonical is refused by evaluate, never dropped.
    val forged = labels.updated(0, l0.copy(label = Some("match ")))
    val refused = GoldSetEvaluation.evaluate(manifest, queue, forged)
    assert(refused.left.exists(_.exists(_.contains("is not exactly one of"))), refused.toString)
    // With canonical labels every reviewed label lands in exactly one cell or the unsure count.
    val r = report
    Vector("researcher", "work").foreach { k =>
      val c = seg(r, k, "all").counts
      val cells = c.tp + c.fp + c.tn + c.fn + c.unresolvedMatch + c.unresolvedNonMatch +
        c.conflictMatch + c.conflictNonMatch + c.notGeneratedMatch + c.notGeneratedNonMatch
      assertEquals(cells + c.unsure, c.reviewed, k)
      assertEquals(c.definitive, c.reviewed - c.unsure, k)
    }
    assertEquals(r.definitiveGoldLabels, r.coverage.definitiveLabels)
    assertEquals(r.reviewedPairs, r.coverage.humanReviewed)
  }

  private def lines(ls: Vector[GoldLabel]): Vector[String] = ls.map(l => write(l))
  private def problems(ls: Vector[GoldLabel]): Vector[String] =
    GoldLabels.ingest(lines(ls), manifest).left.toOption.getOrElse(Vector.empty).map(_.problem)

  test("a queue that mixes policy versions is refused") {
    val r0 = queue(0)
    val mixed =
      queue.updated(
        0,
        r0.copy(
          policyVersion = "er-policy.v0",
          rowId = ReviewQueue.rowId(r0.memberId, "er-policy.v0")
        )
      )
    assert(GoldSetEvaluation.evaluate(manifest, mixed, labels).isLeft)
  }

  test("labels keyed by memberId still join after a policy-version change") {
    val next = queue.map(r =>
      r.copy(
        policyVersion = "er-policy.synthetic-next",
        rowId = ReviewQueue.rowId(r.memberId, "er-policy.synthetic-next")
      )
    )
    assert(next.zip(queue).forall { case (a, b) => a.rowId != b.rowId && a.memberId == b.memberId })
    val again =
      GoldSetEvaluation.evaluate(manifest, next, labels).fold(e => fail(e.mkString), identity)
    assertEquals(again.policyVersion, "er-policy.synthetic-next")
    assertEquals(again.segments.map(_.counts), report.segments.map(_.counts))
    assertEquals(again.reviewedPairs, labels.size)
    // The same labels ingest against the manifest whatever the queue's policy version.
    assertEquals(
      GoldLabels.ingest(labels.map(l => write(l)), manifest).map(_.size),
      Right(labels.size)
    )
  }

  test("a labeled member without a queue row counts as not-generated, never dropped") {
    val tp1 = queue(0)
    val r = GoldSetEvaluation
      .evaluate(manifest, queue.filterNot(_.memberId == tp1.memberId), labels)
      .fold(e => fail(e.mkString), identity)
    val all = seg(r, "researcher", "all").counts
    assertEquals((all.reviewed, all.tp, all.notGeneratedMatch), (10, 2, 1))
    assertEqualsDouble(seg(r, "researcher", "all").endToEndRecall.get, 2.0 / 5.0, 1e-12)
    assertEquals(seg(r, "researcher", "not-generated").counts.notGeneratedMatch, 1)
    assertEquals(seg(r, "researcher", "probabilistic").counts.notGeneratedMatch, 0)
    assertEquals(r.notGeneratedMatches, Vector(tp1.memberId))
    assertEquals(seg(r, "researcher", "all").confusion("NOT-GENERATED|match"), 1)
  }

  test("queue rows outside the manifest, or differing from their member, are refused") {
    assert(
      GoldSetEvaluation
        .evaluate(
          manifest.drop(1),
          queue,
          labels.filter(l => manifest.drop(1).exists(_.memberId == l.memberId))
        )
        .isLeft
    )
    val moved = queue.updated(0, queue(0).copy(entityKind = "work"))
    assert(GoldSetEvaluation.evaluate(manifest, moved, labels).isLeft)
    assert(GoldSetEvaluation.evaluate(manifest, queue :+ queue(0), labels).isLeft)
  }
}

class GoldLabelsSuite extends munit.FunSuite {
  import SyntheticEval.*

  private def lines(ls: Vector[GoldLabel]): Vector[String] = ls.map(l => write(l))
  private def problems(ls: Vector[String]): Vector[String] =
    GoldLabels.ingest(ls, manifest).left.toOption.getOrElse(Vector.empty).map(_.problem)

  test("valid labels are accepted") {
    assertEquals(GoldLabels.ingest(lines(labels), manifest).map(_.size), Right(labels.size))
    assertEquals(GoldLabels.SchemaVersion, "er-gold-label.v3")
  }

  test("labelingTarget and labelGuideVersion round-trip and must match the guide and the kind") {
    val l = labels.head
    assertEquals(l.labelingTarget, Some("person"))
    val w = labels.find(_.entityKind == "work").get
    assertEquals(w.labelingTarget, Some("publication-stage"))
    assertEquals(w.labelGuideVersion, Some("er-label-guide.v1"))
    val back = upickle.default.read[GoldLabel](write(w))
    assertEquals(back, w)
    assert(write(w).contains("\"labelingTarget\":\"publication-stage\""))
    assert(
      problems(lines(labels.updated(0, l.copy(labelingTarget = Some("publication-stage")))))
        .exists(_.startsWith("labelingTarget must be 'person'"))
    )
    assert(
      problems(lines(labels.updated(0, l.copy(labelingTarget = None))))
        .exists(_.startsWith("labelingTarget"))
    )
    assert(
      problems(lines(labels.updated(0, l.copy(labelGuideVersion = Some("er-label-guide.v0")))))
        .exists(_.startsWith("labelGuideVersion"))
    )
    assert(
      problems(lines(labels.updated(0, l.copy(schemaVersion = "er-gold-label.v2"))))
        .exists(_.contains("retired"))
    )
  }

  test("unlabeled and invalid labels are refused, and refusal is all-or-nothing") {
    val l = labels.head
    assert(
      problems(lines(labels.updated(0, l.copy(label = None)))).exists(_.startsWith("unlabeled"))
    )
    assert(
      problems(lines(labels.updated(0, l.copy(label = Some(" ")))))
        .exists(_.startsWith("unlabeled"))
    )
    assert(
      problems(lines(labels.updated(0, l.copy(label = Some("maybe")))))
        .exists(_.contains("not one of"))
    )
    assert(problems(lines(labels.updated(0, l.copy(labeler = None)))).exists(_.contains("labeler")))
    assert(
      problems(lines(labels.updated(0, l.copy(labeledAt = Some("2026-11-04 10:00")))))
        .exists(_.contains("labeledAt"))
    )
    assert(
      problems(lines(labels.updated(0, l.copy(schemaVersion = "v0"))))
        .exists(_.contains("schemaVersion"))
    )
    assert(
      problems(lines(labels.updated(0, l.copy(schemaVersion = "er-gold-label.v1"))))
        .exists(_.contains("retired"))
    )
    assert(GoldLabels.ingest(lines(labels.updated(0, l.copy(label = None))), manifest).isLeft)
  }

  test("duplicate members are refused") {
    assert(problems(lines(labels :+ labels.head)).exists(_.startsWith("duplicate memberId")))
  }

  test("untraceable labels are refused: unknown member, or endpoints that differ from it") {
    val l = labels.head
    assert(
      problems(lines(labels.updated(0, l.copy(memberId = "gm:" + "0" * 64))))
        .exists(_.startsWith("untraceable"))
    )
    assert(
      problems(lines(labels.updated(0, l.copy(sourceIdB = mentionA))))
        .exists(_.startsWith("untraceable"))
    )
    assert(
      problems(lines(labels.updated(0, l.copy(entityKind = "work"))))
        .exists(_.startsWith("untraceable"))
    )
  }

  test("malformed lines and unknown keys are refused (no second, lifecycle label exists)") {
    assert(problems(Vector("{not json")).exists(_.startsWith("malformed")))
    val extra = write(labels.head).replaceFirst("\\{", "{\"confidence\":0.9,")
    assert(problems(extra +: lines(labels.tail)).exists(_.startsWith("malformed")))
    val second = write(labels.head).replaceFirst("\\{", "{\"workLabel\":\"match\",")
    assert(problems(second +: lines(labels.tail)).exists(_.startsWith("malformed")))
    val rowKeyed = write(labels.head).replaceFirst("\\{", "{\"rowId\":\"rq:x\",")
    assert(problems(rowKeyed +: lines(labels.tail)).exists(_.startsWith("malformed")))
  }

  test("a queue row that already carries a label, or a bad row or member ID, is refused") {
    val tampered = queue.updated(0, queue(0).copy(label = Some("match")))
    assert(GoldLabels.parseQueue(tampered.map(r => write(r))).isLeft)
    assert(GoldLabels.parseQueue(queue.map(r => write(r))).isRight)
    val badId = queue.updated(0, queue(0).copy(rowId = "rq:" + "1" * 64))
    assert(GoldLabels.parseQueue(badId.map(r => write(r))).isLeft)
    val badMember = queue.updated(0, queue(0).copy(memberId = "gm:" + "1" * 64))
    assert(GoldLabels.parseQueue(badMember.map(r => write(r))).isLeft)
  }

  test("GoldSetEvalMain: evaluates traceable labels and refuses bad label files") {
    val dir = workDir("gold-set-eval-main")
    val m = dir.resolve("manifest-synthetic.jsonl")
    val q = dir.resolve("queue.jsonl")
    val good = dir.resolve("labels-synthetic.jsonl")
    val bad = dir.resolve("labels-synthetic-bad.jsonl")
    Files.writeString(m, GoldMembership.toJsonl(manifest), StandardCharsets.UTF_8)
    Files.writeString(q, ReviewQueue.toJsonl(queue), StandardCharsets.UTF_8)
    Files.writeString(good, lines(labels).mkString("\n"), StandardCharsets.UTF_8)
    Files.writeString(bad, lines(labels :+ labels.head).mkString("\n"), StandardCharsets.UTF_8)
    // 11 reviewed members < 200: final metrics are withheld; nothing is printed provisionally.
    val out = Vector.newBuilder[String]
    assertEquals(
      GoldSetEvalMain.run(Vector(m.toString, q.toString, good.toString), out += _, configLoader()),
      3
    )
    val text = out.result()
    assert(text.exists(_.startsWith("coverage human-reviewed=11")), text.mkString("\n"))
    assert(!text.exists(l => l.startsWith("researcher/all:") || l.startsWith("report-json:")))
    val out2 = Vector.newBuilder[String]
    assertEquals(
      GoldSetEvalMain.run(Vector(m.toString, q.toString, bad.toString), out2 += _, configLoader()),
      2
    )
    assert(out2.result().exists(_.contains("duplicate memberId")))
    assertEquals(GoldSetEvalMain.run(Vector.empty, _ => (), configLoader()), 2)
  }
}
