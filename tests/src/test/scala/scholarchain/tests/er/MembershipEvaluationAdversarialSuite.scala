package scholarchain.tests.er

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.immutable.SortedMap
import scala.util.Random

import scholarchain.core.*
import scholarchain.entityresolution.evaluation.*
import scholarchain.entityresolution.policy.*
import scholarchain.entityresolution.review.*

import ErFixtures.*

/**
 * TE-P5-01 area 6: fixed gold membership, label ingestion, and gold-set evaluation. Every member,
 * queue row, and label line here is SYNTHETIC and machine-generated for testing only: none is a
 * human label, and no metric computed here is a gold metric. Files are written only to a fresh
 * temporary directory outside the repository.
 */
class MembershipEvaluationAdversarialSuite extends munit.FunSuite {

  private val runId = candidateRunId
  private val blocking = "er-blocking.v2"

  private def workId(i: Int): String = f"work:dblp:journals/synthetic/TeW$i%04d"

  private def workRef(id: String): EndpointRef =
    EndpointRef(
      id,
      "work",
      "dblp",
      id.stripPrefix("work:dblp:"),
      "synthetic-snapshot",
      "dblp-raw/1.0.0",
      runId,
      Some(id),
      None,
      Vector(s"title: synthetic $id")
    )

  /** `n` SYNTHETIC work pairs; pair i is unit `te-u<i>` with criterion a (i % 4 != 0) or b. */
  private def population(n: Int, split: String = "heldout") = {
    val pairs = (0 until n).toVector.map(i =>
      CandidatePair("work", workId(2 * i), workId(2 * i + 1), blocking, runId)
    )
    val refs = pairs.flatMap(p => Vector(p.sourceIdA, p.sourceIdB)).map(e => e -> workRef(e)).toMap
    val units = SelectionUnits(
      (0 until n).map { i =>
        val u = s"te-u$i"
        u -> SelectionUnit(u, if (i % 4 == 0) "crit-b" else "crit-a", split)
      }.toMap,
      (0 until n)
        .flatMap(i =>
          Vector(workId(2 * i) -> Vector(s"te-u$i"), workId(2 * i + 1) -> Vector(s"te-u$i"))
        )
        .toMap
    )
    (pairs, refs, units)
  }

  private def plan(n: Int, design: MembershipDesign) = {
    val (pairs, refs, units) = population(n, design.split)
    GoldMembership.plan(pairs, refs, units, design).fold(e => fail(e.mkString("; ")), identity)
  }

  // ------------------------------------------------------------------ membership

  test(
    "memberId is stable across policy, blocking, feature versions and runs; the candidate ID is not"
  ) {
    val v2 = researcher(nameOnly)
    val other = researcher(
      strongResearcher,
      blockingVersion = "er-blocking.v9-te",
      featureVersion = "er-features.v9-te",
      runId = "run:" + "c" * 64
    )
    assertNotEquals(v2.candidate.candidateId, other.candidate.candidateId)
    val m1 = GoldMembership.memberId("researcher", v2.candidate.sourceIdA, v2.candidate.sourceIdB)
    val m2 =
      GoldMembership.memberId("researcher", other.candidate.sourceIdB, other.candidate.sourceIdA)
    assertEquals(m1, m2)
    assert(m1.exists(_.startsWith("gm:")))
    assertNotEquals(
      GoldMembership.memberId("work", mentionA, mentionB),
      m1,
      "entity kind is an input"
    )
    val id = m1.toOption.get
    assertNotEquals(ReviewQueue.rowId(id, "er-policy.v3"), ReviewQueue.rowId(id, "er-policy.v4"))
    assert(GoldMembership.memberId("venue", mentionA, mentionB).isLeft)
    assert(GoldMembership.memberId("researcher", mentionA, mentionA).isLeft, "self pair")
  }

  test(
    "membership reads no outcome: candidates with permuted features and versions give the same manifest"
  ) {
    assertEquals(
      classOf[CandidatePair].getDeclaredFields.map(_.getName).toVector.sorted,
      Vector("blockingVersion", "entityKind", "runId", "sourceIdA", "sourceIdB")
    )
    val a = researcher(nameOnly).candidate
    val b = researcher(sameWork, featureVersion = "er-features.v9-te").candidate
    assertEquals(CandidatePair.of(a), CandidatePair.of(b))
    val (pairs, refs, units) = population(40)
    val d = MembershipDesign.labeling("heldout", "te-seed")
    val base = GoldMembership.plan(pairs, refs, units, d.copy(capacity = Some(12)))
    val shuffled =
      GoldMembership.plan(new Random(7).shuffle(pairs), refs, units, d.copy(capacity = Some(12)))
    val doubled =
      GoldMembership.plan(pairs ++ pairs.reverse, refs, units, d.copy(capacity = Some(12)))
    assert(base.isRight, s"$base")
    assertEquals(shuffled, base)
    assertEquals(doubled.map(_.members), base.map(_.members), "duplicate pairs are one member")
  }

  test("seeded sampling is deterministic, proportional by (kind, criterion), and seed-dependent") {
    val d = MembershipDesign("heldout", Some(20), Some("te-seed-1"))
    val p1 = plan(40, d)
    assertEquals(plan(40, d), p1)
    assertEquals(p1.members.size, 20)
    assertEquals(p1.population.size, 40)
    assertEquals(
      GoldMembership.strataCounts(p1.members),
      SortedMap("work/crit-a" -> 15, "work/crit-b" -> 5)
    )
    assert(p1.members.forall(_.selectionRule == GoldMembership.SampleRule))
    assert(p1.members.forall(m => m.seed.contains("te-seed-1") && m.capacity.contains(20)))
    val p2 = plan(40, d.copy(seed = Some("te-seed-2")))
    assertEquals(GoldMembership.strataCounts(p2.members), GoldMembership.strataCounts(p1.members))
    assertNotEquals(
      p2.members.map(_.memberId),
      p1.members.map(_.memberId),
      "the seed changes the sample"
    )
    assert(GoldMembership.plan(Vector.empty, Map.empty, SelectionUnits.Empty, d).isLeft)
    assert(
      GoldMembership
        .plan(population(5)._1, population(5)._2, population(5)._3, d.copy(seed = None))
        .isLeft
    )
    assert(
      GoldMembership
        .plan(population(5)._1, population(5)._2, population(5)._3, d.copy(seed = Some("bad seed")))
        .isLeft
    )
  }

  test("below capacity the census is taken and never padded") {
    val p = plan(12, MembershipDesign.labeling("heldout", "te-seed"))
    assertEquals(p.members, p.population)
    assertEquals(p.members.size, 12)
    assertEquals(p.shortfall, 240 - 12)
    assert(p.members.forall(_.selectionRule == GoldMembership.CensusRule))
    assert(p.members.forall(_.populationSize == 12))
    val summary = GoldMembership.summary(p)
    assert(summary.head.contains("shortfall=228"), summary.head)
    assert(summary.head.contains("nothing padded"), summary.head)
  }

  test("a held-out manifest refuses a dev endpoint, an unmapped endpoint, and mixed runs") {
    val (pairs, refs, units) = population(6)
    val devUnits =
      units.copy(units = units.units.updated("te-u1", SelectionUnit("te-u1", "crit-a", "dev")))
    assert(GoldMembership.plan(pairs, refs, devUnits, MembershipDesign.census("heldout")).isLeft)
    val unmapped = units.copy(byAnchor = units.byAnchor - workId(0))
    assert(GoldMembership.plan(pairs, refs, unmapped, MembershipDesign.census("heldout")).isLeft)
    val mixed = pairs.updated(0, pairs(0).copy(runId = "run:" + "d" * 64))
    assert(GoldMembership.plan(mixed, refs, units, MembershipDesign.census("heldout")).isLeft)
  }

  // ------------------------------------------------------------------ files

  private lazy val tmp: Path = Files.createTempDirectory("te-p5-01-er-")

  private def fresh(name: String): Path = {
    val d = Files.createDirectories(tmp.resolve(name))
    d
  }

  test("the manifest is create-once: a second write is refused and the bytes are unchanged") {
    val ms = plan(8, MembershipDesign.census("heldout")).members
    val f = fresh("create-once").resolve("manifest.jsonl")
    assertEquals(GoldMembership.writeNew(f, ms), Right(()))
    val before = Files.readAllBytes(f)
    assert(GoldMembership.writeNew(f, ms.take(1)).isLeft)
    assert(java.util.Arrays.equals(Files.readAllBytes(f), before))
    assertEquals(GoldMembership.fileSha256(f), Right(GoldMembership.manifestSha256(ms)))
    assertEquals(GoldMembership.readFile(f), Right(ms))
  }

  // ------------------------------------------------------------------ labels and queue rows

  private def label(
      m: GoldMember,
      value: String,
      schema: String = GoldLabels.SchemaVersion,
      target: String = "publication-stage",
      guide: String = LabelGuide.Version,
      a: String = "",
      b: String = "",
      kind: String = "",
      labeler: String = "te-synthetic",
      at: String = "2026-11-06T12:00:00Z",
      extra: String = ""
  ): String = {
    val sa = if (a.isEmpty) m.endpointA.endpointId else a
    val sb = if (b.isEmpty) m.endpointB.endpointId else b
    val k = if (kind.isEmpty) m.entityKind else kind
    s"""{"schemaVersion":"$schema","memberId":"${m.memberId}","entityKind":"$k",""" +
      s""""labelingTarget":"$target","labelGuideVersion":"$guide","sourceIdA":"$sa",""" +
      s""""sourceIdB":"$sb","label":"$value","labeler":"$labeler","labeledAt":"$at","notes":null$extra}"""
  }

  private def row(
      m: GoldMember,
      outcome: String,
      path: String = "probabilistic",
      policyVersion: String = "er-policy.v3",
      score: Double = 0.9,
      thresholds: Option[DecisionThresholds] = None
  ): ReviewQueueRow =
    ReviewQueueRow(
      ReviewQueue.RowSchemaVersion,
      ReviewQueue.rowId(m.memberId, policyVersion),
      m.memberId,
      s"${m.entityKind}/$path/$outcome/synthetic",
      m.entityKind,
      "mcand:" + Canonical.sha256HexUtf8(m.memberId),
      runId,
      "synthetic-snapshot",
      blocking,
      "er-features.v2",
      policyVersion,
      m.endpointA.endpointId,
      m.endpointB.endpointId,
      workRef(m.endpointA.endpointId),
      workRef(m.endpointB.endpointId),
      Vector.empty,
      SortedMap.empty,
      SystemDecision(
        outcome,
        path,
        score,
        0.5,
        Vector(s"$path.synthetic"),
        Vector("synthetic"),
        Vector.empty,
        None,
        false,
        None,
        None,
        thresholds
      ),
      None,
      None,
      None,
      None
    )

  private def ingest(lines: Vector[String], ms: Vector[GoldMember]) =
    GoldLabels.ingest(lines, ms)

  test(
    "label files: unknown keys, wrong target or guide, duplicates, non-members, retired schemas are refused"
  ) {
    val ms = plan(6, MembershipDesign.census("heldout")).members
    val m = ms.head
    val other = plan(10, MembershipDesign.census("heldout")).members.last
    assert(ingest(Vector(label(m, "match")), ms).isRight, "positive control")
    val bad = Vector(
      "unknown key" -> label(m, "match", extra = ""","confidence":0.9"""),
      "wrong labelingTarget" -> label(m, "match", target = "person"),
      "lifecycle target" -> label(m, "match", target = "publication-lifecycle"),
      "old guide" -> label(m, "match", guide = "er-label-guide.v0"),
      "retired v1" -> label(m, "match", schema = "er-gold-label.v1"),
      "retired v2" -> label(m, "match", schema = "er-gold-label.v2"),
      "future v4" -> label(m, "match", schema = "er-gold-label.v4"),
      "not a manifest member" -> label(other, "match"),
      "endpoints of another member" -> label(m, "match", a = ms(1).endpointA.endpointId),
      "entity kind changed" -> label(m, "match", kind = "researcher", target = "person"),
      "upper-case label" -> label(m, "Match"),
      "unknown label" -> label(m, "probably"),
      "blank label" -> label(m, " "),
      "labeler with a space" -> label(m, "match", labeler = "te synthetic"),
      "non-UTC time" -> label(m, "match", at = "2026-11-06T12:00:00+01:00"),
      "not a time" -> label(m, "match", at = "yesterday"),
      "not JSON" -> "match"
    )
    bad.foreach { case (clue, line) => assert(ingest(Vector(line), ms).isLeft, clue) }
    val dup = ingest(Vector(label(m, "match"), label(m, "non-match")), ms)
    assert(dup.left.exists(_.exists(_.problem.contains("duplicate"))), s"$dup")
    // All-or-nothing: one bad line refuses the whole file.
    assert(ingest(Vector(label(ms(1), "match"), label(m, "probably")), ms).isLeft)
    // Swapped endpoint order states the same pair.
    assert(
      ingest(
        Vector(label(m, "match", a = m.endpointB.endpointId, b = m.endpointA.endpointId)),
        ms
      ).isRight
    )
  }

  test(
    "masked label references are recomputed per member and cannot be replayed on another member"
  ) {
    val profileA = "source-author:dblp-pid:00/9101"
    val profileB = "source-author:dblp-pid:00/9102"
    def member(x: String, y: String): GoldMember = {
      val id = ok(GoldMembership.memberId("researcher", x, y))
      val (a, b) = if (Canonical.compareUtf8(x, y) < 0) (x, y) else (y, x)
      GoldMember(
        GoldMembership.SchemaVersion,
        id,
        "researcher",
        MemberEndpoint(a, "profile", "dblp", a, "synthetic-snapshot"),
        MemberEndpoint(b, "mention", "dblp", "journals/synthetic/X21", "synthetic-snapshot"),
        Vector("te-u0"),
        "crit-a",
        "heldout",
        runId,
        blocking,
        GoldMembership.CensusRule,
        None,
        None,
        2
      )
    }
    val m1 = member(profileA, mentionA)
    val m2 = member(profileB, mentionA)
    val ms = Vector(m1, m2)
    def refs(m: GoldMember) =
      (
        BlindView.labelRef(m.memberId, m.endpointA.endpointId),
        BlindView.labelRef(m.memberId, m.endpointB.endpointId)
      )
    val (r1a, r1b) = refs(m1)
    assert(
      Vector(r1a, r1b).count(_.startsWith(BlindView.MaskedRefPrefix)) == 1,
      s"exactly the DBLP profile endpoint is masked: $r1a $r1b"
    )
    val ok1 = ingest(Vector(label(m1, "match", target = "person", a = r1a, b = r1b)), ms)
    assert(ok1.isRight, s"$ok1")
    val replay = ingest(Vector(label(m2, "match", target = "person", a = r1a, b = r1b)), ms)
    assert(replay.isLeft, "a reference of member 1 accepted for member 2")
  }

  // ------------------------------------------------------------------ evaluation

  test("labels join by memberId: the same labels evaluate the rows of a different policy version") {
    val ms = plan(6, MembershipDesign.census("heldout")).members
    val labels = ingest(ms.map(m => label(m, "match")), ms).fold(e => fail(s"$e"), identity)
    val v3 = ms.map(row(_, "ACCEPTED"))
    val v4 = ms.map(row(_, "UNRESOLVED", policyVersion = "er-policy.v4-te"))
    val r3 = GoldSetEvaluation.evaluate(ms, v3, labels).fold(e => fail(s"$e"), identity)
    val r4 = GoldSetEvaluation.evaluate(ms, v4, labels).fold(e => fail(s"$e"), identity)
    assertNotEquals(v3.map(_.rowId), v4.map(_.rowId))
    assertEquals((r3.policyVersion, r4.policyVersion), ("er-policy.v3", "er-policy.v4-te"))
    assertEquals((r3.reviewedPairs, r4.reviewedPairs), (6, 6))
    def all(r: EvaluationReport) =
      r.segments.find(s => s.entityKind == "work" && s.path == "all").get.counts
    assertEquals((all(r3).tp, all(r4).unresolvedMatch), (6, 6))
    assert(
      GoldSetEvaluation.evaluate(ms, v3 ++ v4.take(1), labels).isLeft,
      "two rows for one member"
    )
    assert(GoldSetEvaluation.evaluate(ms, v3.take(5) :+ v4(5), labels).isLeft, "mixed versions")
  }

  test(
    "not-generated members, unsure labels, and reviewed vs definitive counts are reported separately"
  ) {
    val ms = plan(6, MembershipDesign.census("heldout")).members
    // m0..m2 ACCEPTED + match; m3 ACCEPTED + unsure; m4 has no queue row + match; m5 unlabeled.
    val lines = Vector(
      label(ms(0), "match"),
      label(ms(1), "match"),
      label(ms(2), "match"),
      label(ms(3), "unsure"),
      label(ms(4), "match")
    )
    val labels = ingest(lines, ms).fold(e => fail(s"$e"), identity)
    val queue = Vector(ms(0), ms(1), ms(2), ms(3), ms(5)).map(row(_, "ACCEPTED"))
    val r = GoldSetEvaluation.evaluate(ms, queue, labels).fold(e => fail(s"$e"), identity)
    assertEquals((r.reviewedPairs, r.definitiveGoldLabels, r.unsurePairs), (5, 4, 1))
    val c = r.coverage
    assertEquals((c.humanReviewed, c.definitiveLabels, c.labeledUnsure, c.unlabeled), (5, 4, 1, 1))
    assertEquals((c.notGenerated, c.notGeneratedReviewed), (1, 1))
    assertEquals(r.notGeneratedMatches, Vector(ms(4).memberId))
    val all = r.segments.find(s => s.entityKind == "work" && s.path == "all").get
    assertEquals(
      (all.counts.tp, all.counts.fp, all.counts.unsure),
      (3, 0, 1),
      "unsure is never a FP"
    )
    assertEquals(all.precision, Some(1.0))
    assertEquals(all.counts.notGeneratedMatch, 1)
    assertEquals(all.endToEndRecall, Some(3.0 / 4.0))
    assertEquals(c.meetsMinimum, false)
    assertEquals(r.metricsStatus, GoldSetEvaluation.RefusedStatus)
    val reasons = c.exclusions.map(e => e.memberId -> e.reasons).toMap
    assert(reasons(ms(3).memberId).contains(GoldSetEvaluation.UnsureReason))
    assert(reasons(ms(4).memberId).contains(GoldSetEvaluation.NotGeneratedReason))
    assert(reasons(ms(5).memberId).contains(GoldSetEvaluation.UnlabeledReason))
  }

  test(
    "a whitespace-padded label is either refused at ingest or counted as that label (RV-P5-04 M1)"
  ) {
    // SYNTHETIC: a hand-edited label file can carry " match" or "match " (a label value inside
    // JSON quotes). Ingest validates the trimmed value; evaluation must then count the same value.
    val ms = plan(2, MembershipDesign.census("heldout")).members
    val queue = ms.map(row(_, "ACCEPTED"))
    for (padded <- Vector(" match", "match ", "non-match\t")) {
      ingest(Vector(label(ms(0), padded), label(ms(1), "match")), ms) match {
        case Left(_) => () // refused: acceptable
        case Right(labels) =>
          val r = GoldSetEvaluation.evaluate(ms, queue, labels).fold(e => fail(s"$e"), identity)
          val all = r.segments.find(s => s.entityKind == "work" && s.path == "all").get.counts
          val expectedMatches = if (padded.trim == "match") 2 else 1
          assertEquals(
            (r.definitiveGoldLabels, r.coverage.definitiveLabels, all.tp + all.fp),
            (2, 2, 2),
            s"label '$padded' was accepted but not counted as a definitive label"
          )
          assertEquals(all.tp, expectedMatches, s"label '$padded'")
      }
    }
  }

  // ------------------------------------------------------------------ entry point

  /** The membership file name of the gold layout (decisions-15; ER-P5-15 [[FreezeRecord]]). */
  private val ManifestName = "er-gold-membership.jsonl"

  /**
   * SYNTHETIC evaluation inputs in one temporary directory. `sha` is computed here from the file's
   * raw bytes with the JDK digest (an oracle independent of the production hashing).
   */
  private final case class EvalFiles(
      dir: Path,
      manifest: Path,
      queue: Path,
      labels: Path,
      sha: String
  ) {

    /** The freeze record's place: `<membership file>.sha256` beside the membership file. */
    def freeze: Path = manifest.resolveSibling(manifest.getFileName.toString + ".sha256")

    def args: Vector[String] = Vector(manifest.toString, queue.toString, labels.toString)
  }

  private def sha256Hex(p: Path): String =
    java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(Files.readAllBytes(p))
      .map(b => f"${b & 0xff}%02x")
      .mkString

  /**
   * Exactly the output of `shasum -a 256 <name>` run in the file's directory: 64 lowercase hex, two
   * spaces, the file name, one LF.
   */
  private def shasumLine(hex: String, name: String): String = s"$hex  $name\n"

  /** Writes (or overwrites) the bytes of a freeze record at `at`. */
  private def writeRecord(at: Path, line: String): Path =
    Files.write(at, line.getBytes(StandardCharsets.UTF_8))

  private def freezeArgs(record: Path, split: String): Vector[String] =
    Vector(s"--manifest-sha256-file=$record", s"--expect-split=$split")

  /**
   * Records the correct freeze of `f` beside its manifest, as the human does (`shasum -a 256
   * er-gold-membership.jsonl > er-gold-membership.jsonl.sha256`), and returns the freeze arguments
   * for `split`.
   */
  private def frozen(f: EvalFiles, split: String = "heldout"): Vector[String] =
    freezeArgs(writeRecord(f.freeze, shasumLine(f.sha, ManifestName)), split)

  private def files(
      name: String,
      ms: Vector[GoldMember],
      queue: Vector[ReviewQueueRow],
      lines: Vector[String]
  ) = {
    val d = fresh(name)
    val mf = d.resolve(ManifestName)
    assertEquals(GoldMembership.writeNew(mf, ms), Right(()))
    val qf = Files.writeString(
      d.resolve("queue.jsonl"),
      ReviewQueue.toJsonl(queue),
      StandardCharsets.UTF_8
    )
    val lf = Files.writeString(
      d.resolve("labels.jsonl"),
      lines.mkString("\n") + "\n",
      StandardCharsets.UTF_8
    )
    EvalFiles(d, mf, qf, lf, sha256Hex(mf))
  }

  /**
   * The entry point loads the external configuration (RV-P5-04 m6, ER-P5-11) and refuses rows that
   * were not decided under it. These tests use the SYNTHETIC configuration of [[ErFixtures]]: the
   * active policy er-policy.v8 (decisions-11) with the policy file's thresholds and band.
   */
  private val activePolicy = "er-policy.v8"
  private val configuredThresholds = DecisionThresholds(0.85, 0.2, 0.4, 0.7)

  /** A queue row of the active policy that records the configured thresholds (row schema v4). */
  private def activeRow(m: GoldMember, outcome: String): ReviewQueueRow =
    row(m, outcome, policyVersion = activePolicy, thresholds = Some(configuredThresholds))

  private def runWith(
      loadConfig: () => Either[
        Vector[scholarchain.core.config.ConfigError],
        scholarchain.core.config.ScholarChainConfig
      ]
  )(args: String*): (Int, Vector[String]) = {
    val out = Vector.newBuilder[String]
    val code = GoldSetEvalMain.run(args.toVector, s => out += s, loadConfig)
    (code, out.result())
  }

  private def runMain(args: String*): (Int, Vector[String]) = runWith(syntheticErConfig())(args*)

  test("entry-point premises: the synthetic configuration names the active policy and thresholds") {
    assertEquals(policy.policyVersion, activePolicy)
    assertEquals(ReviewQueue.RowSchemaVersion, "er-review-queue-row.v4")
    assertEquals(DecisionThresholds.of(policy), configuredThresholds)
    val cfg = syntheticErConfig()().fold(e => fail(e.map(_.message).mkString("; ")), identity)
    assertEquals(cfg.entityResolution.policyVersion, activePolicy)
    assertEquals(
      MatchPolicy.withConfig(policy, cfg.entityResolution).map(DecisionThresholds.of),
      Right(configuredThresholds)
    )
    assert(missingErConfig().isLeft, "the no-configuration source must not resolve")
  }

  test(
    "entry point: freeze mismatch and --provisional are refused; below 200 reviewed withholds metrics"
  ) {
    val ms = plan(10, MembershipDesign.census("heldout")).members
    val f = files(
      "below-minimum",
      ms,
      ms.map(activeRow(_, "ACCEPTED")),
      ms.take(8).map(label(_, "match")) ++ ms.slice(8, 10).map(label(_, "unsure"))
    )
    val base = f.args
    // A well-formed freeze record (beside the manifest, shasum format) with another digest.
    val wrongSha = (if (f.sha.head == '0') '1' else '0') +: f.sha.tail
    assertNotEquals(wrongSha, f.sha)
    val (c1, o1) =
      runMain(
        base ++ freezeArgs(writeRecord(f.freeze, shasumLine(wrongSha, ManifestName)), "heldout")*
      )
    assertEquals(c1, 2, o1.mkString("\n"))
    assert(o1.exists(_.contains("differs from the recorded freeze")), o1.mkString("\n"))
    assert(!o1.exists(_.contains("human-reviewed=")), o1.mkString("\n"))
    // The right digest in upper case is refused, never repaired.
    val (cUp, oUp) = runMain(
      base ++ freezeArgs(
        writeRecord(f.freeze, shasumLine(f.sha.toUpperCase, ManifestName)),
        "heldout"
      )*
    )
    assertEquals(cUp, 2, oUp.mkString("\n"))
    assert(!oUp.exists(_.contains("freeze=matches")), oUp.mkString("\n"))
    for (p <- Vector("--provisional", "--provisional=true")) {
      val (c, o) = runMain(base :+ p*)
      assertEquals(c, 2, p)
      assert(o.exists(_.contains("--provisional was removed")), o.mkString("\n"))
      assert(!o.exists(_.contains("PROVISIONAL")), o.mkString("\n"))
      assert(!o.exists(_.contains("precision=")), o.mkString("\n"))
    }
    val withFreeze = base ++ frozen(f)
    val (c3, o3) = runMain(withFreeze*)
    assertEquals(c3, GoldSetEvalMain.BelowMinimumExit, o3.mkString("\n"))
    assert(o3.exists(_.contains("freeze=matches")), o3.mkString("\n"))
    assert(!o3.exists(_.contains("precision=")), "metrics printed below the minimum")
    assert(!o3.exists(_.startsWith("report-json")), "report printed below the minimum")
    val reviewedLines = o3.filter(_.contains("human-reviewed="))
    assert(reviewedLines.nonEmpty, o3.mkString("\n"))
    reviewedLines.foreach { l =>
      assert(l.contains("human-reviewed=10"), l)
      assert(
        l.contains("definitive gold labels=8"),
        s"reviewed shown without the definitive count: $l"
      )
    }
    assert(
      o3.exists(l => l.contains("refused") && l.contains("10 human-reviewed")),
      o3.mkString("\n")
    )
    // The run reports the effective settings it was checked against (m6).
    assert(
      o3.exists(l =>
        l.startsWith(s"settings policy=$activePolicy ") && l.contains("accept=0.85") &&
          l.contains("reject=0.2") && l.contains("llmBand=[0.4,0.7]") &&
          l.contains("config=applied")
      ),
      o3.mkString("\n")
    )
    // No fallback: without a configuration the same valid run is refused before any report.
    val (cNo, oNo) = runWith(missingErConfig)(withFreeze*)
    assertEquals(cNo, 2, oNo.mkString("\n"))
    assert(oNo.exists(_.contains("configuration refused")), oNo.mkString("\n"))
    assert(!oNo.exists(_.contains("freeze=matches")), oNo.mkString("\n"))
    assert(!oNo.exists(_.contains("human-reviewed=")), oNo.mkString("\n"))
    // A configuration naming a retired policy is refused (exit 2), never silently applied.
    for (old <- Vector("er-policy.v5", "er-policy.v6", "er-policy.v7")) {
      val (cOld, oOld) =
        runWith(syntheticErConfig(syntheticErConfigText(policyVersion = old)))(withFreeze*)
      assertEquals(cOld, 2, s"$old: ${oOld.mkString("\n")}")
      assert(oOld.exists(_.contains("configuration refused")), oOld.mkString("\n"))
    }
    // Rows not decided under the configured settings are refused, not reported under them.
    val labels = ms.take(8).map(label(_, "match")) ++ ms.slice(8, 10).map(label(_, "unsure"))
    val mismatched = Vector(
      "stale v3 rows without thresholds" -> (
        ms.map(row(_, "ACCEPTED")),
        s"differs from the configured $activePolicy"
      ),
      "active rows without recorded thresholds" -> (
        ms.map(row(_, "ACCEPTED", policyVersion = activePolicy)),
        "records no thresholds"
      ),
      "active rows decided under other thresholds" -> (
        ms.map(
          row(
            _,
            "ACCEPTED",
            policyVersion = activePolicy,
            thresholds = Some(configuredThresholds.copy(accept = 0.9))
          )
        ),
        "not the configured"
      )
    )
    mismatched.zipWithIndex.foreach { case ((clue, (queue, why)), i) =>
      val g = files(s"below-minimum-mismatch-$i", ms, queue, labels)
      val (c, o) = runMain(g.manifest.toString, g.queue.toString, g.labels.toString)
      assertEquals(c, 2, s"$clue: ${o.mkString("\n")}")
      assert(o.exists(_.contains(why)), s"$clue: ${o.mkString("\n")}")
      assert(!o.exists(_.contains("human-reviewed=")), s"$clue: coverage reported")
    }
  }

  test(
    "entry point: 199 reviewed withholds the metrics; 200 reviewed (unsure included) reports them"
  ) {
    val ms = plan(200, MembershipDesign.census("heldout")).members
    val queue = ms.map(activeRow(_, "ACCEPTED"))
    val labels199 = ms.take(199).zipWithIndex.map { case (m, i) =>
      label(m, if (i % 5 == 0) "unsure" else "match")
    }
    val f199 = files("n199", ms, queue, labels199)
    val (c199, o199) = runMain(f199.manifest.toString, f199.queue.toString, f199.labels.toString)
    assertEquals(c199, GoldSetEvalMain.BelowMinimumExit, o199.takeRight(3).mkString("\n"))
    assert(!o199.exists(_.contains("precision=")))
    val labels200 = labels199 :+ label(ms(199), "unsure")
    val f200 = files("n200", ms, queue, labels200)
    // decisions-15 / RV-P5-04 m4: no freeze argument at all -> final held-out metrics refused.
    val (cBare, oBare) = runMain(f200.args*)
    assertRefused("200 reviewed, no freeze argument at all", (cBare, oBare), "final held-out")
    val freeze200 = frozen(f200)
    val (c200, o200) = runMain(f200.args ++ freeze200*)
    assertEquals(c200, 0, o200.takeRight(3).mkString("\n"))
    assert(
      o200.exists(_.contains(s"manifest sha256=${f200.sha} freeze=matches")),
      o200.mkString("\n")
    )
    val summary = o200.find(_.startsWith("reviewed pairs=")).getOrElse(fail(o200.mkString("\n")))
    assert(summary.contains("reviewed pairs=200"), summary)
    assert(summary.contains("definitive gold labels=159"), summary)
    assert(summary.contains("unsure=41"), summary)
    assert(
      o200.exists(_.startsWith("work/all: reviewed=200 definitive=159 unsure=41 TP=159 FP=0")),
      o200.mkString("\n")
    )
    assert(o200.exists(_.startsWith("report-json")))
    assert(
      o200.exists(l =>
        l.startsWith(s"settings policy=$activePolicy ") && l.contains("accept=0.85")
      ),
      o200.mkString("\n")
    )
    // No fallback: the same 200-reviewed run without a configuration reports nothing.
    val (cNo, oNo) = runWith(missingErConfig)(f200.args ++ freeze200*)
    assertEquals(cNo, 2, oNo.mkString("\n"))
    assert(!oNo.exists(_.startsWith("report-json")), oNo.mkString("\n"))
    assert(!oNo.exists(_.contains("precision=")), oNo.mkString("\n"))
  }

  // ------------------------------------------------------------------ freeze record (TE-P5-06)
  //
  // decisions-15: a human-recorded manifest SHA-256 in one hash-file format
  // (`--manifest-sha256-file`, the `shasum -a 256` line beside the manifest), and the expected
  // split enforced at runtime (`--expect-split`). Every input is SYNTHETIC. None of these tests
  // uses the removed `--manifest-sha256=<hex>` flag except the TARGET test at the end, which
  // asserts that it no longer yields final metrics.

  /** Refused (exit 2) with `why` in the output and neither metrics nor a report printed. */
  private def assertRefused(clue: String, r: (Int, Vector[String]), why: String): Unit = {
    val (code, out) = r
    val text = out.mkString("\n")
    assertEquals(code, 2, s"$clue: $text")
    assert(out.exists(_.contains(why)), s"$clue: expected '$why' in: $text")
    assert(!out.exists(_.startsWith("report-json")), s"$clue: report printed: $text")
    assert(!out.exists(_.contains("precision=")), s"$clue: metrics printed: $text")
  }

  /** `n` SYNTHETIC members of `split`, all ACCEPTED and labeled match: meets the minimum. */
  private def minimumSet(name: String, split: String, n: Int = 200): EvalFiles = {
    val ms = plan(n, MembershipDesign.census(split)).members
    files(name, ms, ms.map(activeRow(_, "ACCEPTED")), ms.map(label(_, "match")))
  }

  private val NotInDir = "not in the membership file's directory"
  private val Differs = "differs from the recorded freeze"

  test("TE-P5-06: --expect-split refuses the other split for held-out and dev manifests") {
    val h = minimumSet("te06-split-heldout", "heldout")
    val d = minimumSet("te06-split-dev", "dev")
    // Positive controls: each split with its own correct freeze record is evaluated.
    val (ch, oh) = runMain(h.args ++ frozen(h, "heldout")*)
    assertEquals(ch, 0, oh.mkString("\n"))
    assert(oh.exists(_.startsWith("report-json")), oh.mkString("\n"))
    val (cd, od) = runMain(d.args ++ frozen(d, "dev")*)
    assertEquals(cd, 0, od.mkString("\n"))
    assert(od.head.startsWith("DEV evaluation split=dev"), od.head)
    // The freeze matches in every case below; only the expected split is wrong.
    assertRefused(
      "held-out manifest, --expect-split=dev",
      runMain(h.args ++ frozen(h, "dev")*),
      "split=heldout, not --expect-split=dev"
    )
    assertRefused(
      "dev manifest, --expect-split=heldout",
      runMain(d.args ++ frozen(d, "heldout")*),
      "split=dev, not --expect-split=heldout"
    )
    // Without a freeze record the split is enforced too (dev manifest meeting the minimum, which
    // would otherwise exit 0 as dev diagnostics).
    val (cdx, odx) = runMain(d.args :+ "--expect-split=heldout"*)
    assertRefused("dev manifest, no freeze, expect heldout", (cdx, odx), "split=dev")
    assert(!odx.exists(_.contains("human-reviewed=")), odx.mkString("\n"))
    // A refused split names no matching freeze and prints no coverage.
    val (cw, ow) = runMain(h.args ++ frozen(h, "dev")*)
    assert(
      !ow.exists(l => l.contains("freeze=matches") || l.contains("human-reviewed=")),
      ow.mkString("\n")
    )
    assertEquals(cw, 2)
  }

  test("TE-P5-06: a freeze record written for a different manifest is refused") {
    val a = minimumSet("te06-other-a", "heldout")
    val b = minimumSet("te06-other-b", "heldout", n = 201)
    assertNotEquals(a.sha, b.sha, "premise: two different manifests")
    val bRecord = writeRecord(b.freeze, shasumLine(b.sha, ManifestName))
    // B's own, valid record (same file name, B's directory) given for A.
    assertRefused(
      "record of B in B's directory",
      runMain(a.args ++ freezeArgs(bRecord, "heldout")*),
      NotInDir
    )
    // B's record bytes copied beside A: the right place and name, the wrong digest.
    Files.copy(bRecord, a.freeze)
    assertRefused(
      "B's record copied beside A",
      runMain(a.args ++ freezeArgs(a.freeze, "heldout")*),
      Differs
    )
    // A record beside A naming a different membership file, even with A's own digest.
    val sibling = a.dir.resolve("er-gold-membership-dev.jsonl")
    Files.copy(b.manifest, sibling)
    for ((clue, hex) <- Vector("sibling digest" -> sha256Hex(sibling), "A's digest" -> a.sha)) {
      writeRecord(a.freeze, shasumLine(hex, sibling.getFileName.toString))
      assertRefused(
        s"record naming the sibling manifest ($clue)",
        runMain(a.args ++ freezeArgs(a.freeze, "heldout")*),
        "names 'er-gold-membership-dev.jsonl'"
      )
    }
    // Positive control: A with its own record is evaluated (A was never changed above).
    assertEquals(sha256Hex(a.manifest), a.sha)
    val (c, o) = runMain(a.args ++ frozen(a)*)
    assertEquals(c, 0, o.mkString("\n"))
  }

  test("TE-P5-06: a correct freeze record outside the manifest's directory is refused") {
    val a = minimumSet("te06-dir-a", "heldout")
    val line = shasumLine(a.sha, ManifestName) // right digest, right name
    val elsewhere = writeRecord(fresh("te06-dir-elsewhere").resolve(ManifestName + ".sha256"), line)
    assertRefused(
      "record in an unrelated directory",
      runMain(a.args ++ freezeArgs(elsewhere, "heldout")*),
      NotInDir
    )
    val sub = Files.createDirectories(a.dir.resolve("sub"))
    val inSub = writeRecord(sub.resolve(ManifestName + ".sha256"), line)
    assertRefused(
      "record in a subdirectory",
      runMain(a.args ++ freezeArgs(inSub, "heldout")*),
      NotInDir
    )
    // A symbolic-link directory inside the manifest's directory does not make an outside record
    // local: the record's real directory is compared.
    val link = Files.createSymbolicLink(a.dir.resolve("linked"), elsewhere.getParent)
    val viaLink = link.resolve(ManifestName + ".sha256")
    assertRefused(
      "record reached through a linked directory",
      runMain(a.args ++ freezeArgs(viaLink, "heldout")*),
      NotInDir
    )
    // Positive control: the same bytes beside the manifest are accepted.
    val (c, o) = runMain(a.args ++ freezeArgs(writeRecord(a.freeze, line), "heldout")*)
    assertEquals(c, 0, o.mkString("\n"))
  }

  test("TE-P5-06: a manifest changed after its freeze is refused, even when it parses the same") {
    val a = minimumSet("te06-tamper", "heldout")
    val args = a.args ++ frozen(a)
    val (c0, o0) = runMain(args*)
    assertEquals(c0, 0, "positive control: " + o0.mkString("\n"))
    val original = Files.readAllBytes(a.manifest)
    val text = new String(original, StandardCharsets.UTF_8)
    val lines = text.split("\n").toVector
    val tampered = Vector(
      // Same members after parsing: only the bytes differ.
      "CRLF line ends" -> text.replace("\n", "\r\n"),
      "trailing blank line" -> (text + "\n"),
      // Fewer members: still a valid manifest.
      "one member removed" -> lines.dropRight(1).mkString("", "\n", "\n"),
      "members reordered" -> lines.reverse.mkString("", "\n", "\n")
    )
    tampered.foreach { case (clue, t) =>
      assertNotEquals(t, text, clue)
      Files.write(a.manifest, t.getBytes(StandardCharsets.UTF_8))
      assertRefused(s"tampered: $clue", runMain(args*), Differs)
    }
    // Restoring the frozen bytes restores the evaluation (the record itself never changed).
    Files.write(a.manifest, original)
    assertEquals(runMain(args*)._1, 0)
  }

  test("TE-P5-07: a correct record beside the manifest under another file name is refused") {
    // ER-P5-16: the record must be named exactly `<membership file name>.sha256` (case-sensitive).
    val a = minimumSet("te07-record-name", "heldout")
    val line = shasumLine(a.sha, ManifestName) // right digest, right content name
    for (
      wrong <- Vector(
        ManifestName + ".SHA256",
        ManifestName + ".sha256.bak",
        "er-gold-membership.sha256",
        "freeze.txt"
      )
    ) {
      val at = writeRecord(a.dir.resolve(wrong), line)
      assertRefused(
        s"record named $wrong",
        runMain(a.args ++ freezeArgs(at, "heldout")*),
        s"must be named exactly '$ManifestName.sha256'"
      )
      Files.delete(at)
    }
    // Positive control: the same bytes under the exact name are accepted.
    val (c, o) = runMain(a.args ++ frozen(a)*)
    assertEquals(c, 0, o.mkString("\n"))
  }

  /**
   * ER-P5-16 removed the legacy `--manifest-sha256=<hex>` flag (now an unknown argument): held-out
   * final metrics given only the hex digest must not exit 0 (decisions-15 item 2, RV-P5-04 m4).
   */
  test("TE-P5-06 TARGET: held-out final metrics with only the legacy hex flag do not exit 0") {
    val a = minimumSet("te06-legacy-hex", "heldout")
    for (
      extra <- Vector(
        Vector(s"--manifest-sha256=${a.sha}"),
        Vector(s"--manifest-sha256=${a.sha}", "--expect-split=heldout")
      )
    ) {
      val (c, o) = runMain(a.args ++ extra*)
      assertNotEquals(c, 0, s"$extra: ${o.mkString("\n")}")
      assert(!o.exists(_.startsWith("report-json")), s"$extra: ${o.mkString("\n")}")
      assert(!o.exists(_.contains("precision=")), s"$extra: ${o.mkString("\n")}")
    }
  }
}
