package scholarchain.entityresolution.review

import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.zip.GZIPOutputStream

import scholarchain.core.{Json, MatchCandidate, SourceAuthorProfile, WorkRecord}
import scholarchain.entityresolution.Samples
import scholarchain.entityresolution.Samples.{em, ep}
import scholarchain.entityresolution.policy.PolicyFixtures.*

/** A SYNTHETIC miniature curated run in the Hadoop output layout (sample-mirroring records). */
class CuratedRunReaderSuite extends munit.FunSuite {

  private def gz(p: Path, lines: Vector[String]): Unit = {
    Files.createDirectories(p.getParent)
    val out = new GZIPOutputStream(new FileOutputStream(p.toFile))
    try out.write(lines.mkString("", "\n", "\n").getBytes(StandardCharsets.UTF_8))
    finally out.close()
  }

  private def plain(p: Path, lines: Vector[String]): Unit = {
    Files.createDirectories(p.getParent)
    Files.writeString(p, lines.mkString("", "\n", "\n"), StandardCharsets.UTF_8)
  }

  private val candidates = Vector(
    researcherCandidate(em(Samples.cacmDblp, 0), ep(Samples.popperOpenAlex)),
    researcherCandidate(em(Samples.spannerDblp, 4), em(Samples.spannerCrossref, 4))
  )
  private val work = workCandidate(Samples.spannerDblp, Samples.spannerCrossref)

  private def miniRun(name: String): Path = {
    val run = workDir(name).resolve("curated/hadoop/snapshot=dblp-2026-10-03/run=" + "1" * 64)
    gz(
      run.resolve("normalize/works/part-r-00000.gz"),
      Vector(Samples.spannerDblp, Samples.spannerCrossref, Samples.cacmDblp).map(
        Json.write[WorkRecord]
      )
    )
    plain(
      run.resolve("normalize/profiles/part-r-00000"),
      Vector(Json.write[SourceAuthorProfile](Samples.popperOpenAlex))
    )
    gz(
      run.resolve("candidates/final/researcher/part-r-00000.gz"),
      candidates.map(Json.write[MatchCandidate])
    )
    gz(
      run.resolve("candidates/final/work/part-r-00000.gz"),
      Vector(Json.write[MatchCandidate](work))
    )
    plain(run.resolve("normalize/._part-r-00000.crc"), Vector("ignored"))
    plain(run.resolve("normalize/_SUCCESS"), Vector.empty)
    plain(run.resolve("candidates/_SUCCESS"), Vector.empty)
    run
  }

  test("reads works, profiles, and candidates of a completed run; gzip and plain parts") {
    val run = miniRun("reader-ok")
    val root = run.getParent.getParent.getParent
    assertEquals(CuratedRunReader.runDirs(root), Vector(run))
    val r = CuratedRunReader.read(run).fold(e => fail(e), identity)
    assertEquals(r.works.size, 3)
    assertEquals(r.profiles.size, 1)
    assertEquals(r.candidates.toSet, (candidates :+ work).toSet)
  }

  test("refuses incomplete runs and invalid lines; tolerates a missing root") {
    val run = miniRun("reader-bad")
    Files.delete(run.resolve("candidates/_SUCCESS"))
    assert(CuratedRunReader.read(run).left.exists(_.contains("not a completed run")))
    plain(run.resolve("candidates/_SUCCESS"), Vector.empty)
    plain(run.resolve("normalize/profiles/part-r-00001"), Vector("{\"schemaVersion\":\"x\"}"))
    assert(CuratedRunReader.read(run).isLeft)
    assertEquals(CuratedRunReader.runDirs(run.resolve("does-not-exist")), Vector.empty)
  }

  /** SYNTHETIC selection files for the mini run's records, all in one unit of the given split. */
  private def unitFiles(base: Path, split: String): (Path, Path) = {
    val anchors = Vector(
      Samples.spannerDblp.workId,
      Samples.spannerCrossref.workId,
      Samples.cacmDblp.workId,
      Samples.popperOpenAlex.profileId
    )
    val (sa, pv) = SyntheticUnits.texts(Vector((s"syn-$split", "syn-criterion", split, anchors)))
    val saPath = base.resolve(s"units-$split/split-assignment.tsv")
    val pvPath = base.resolve(s"units-$split/PROVENANCE.tsv")
    plain(saPath, sa.split('\n').toVector)
    plain(pvPath, pv.split('\n').toVector)
    (saPath, pvPath)
  }

  private def lines(p: Path): Vector[String] =
    Files.readString(p, StandardCharsets.UTF_8).split('\n').toVector.filter(_.nonEmpty)

  test("ReviewQueueMain membership: manifest and blind view once, never overwritten") {
    val run = miniRun("reader-membership")
    val base = run.getParent.getParent.getParent.getParent
    val (sa, pv) = unitFiles(base, "heldout")
    val manifest = base.resolve("gold/er-gold-membership.jsonl")
    val blindOut = base.resolve("gold/er-blind-label-view.jsonl")
    val args = Vector("membership", sa.toString, pv.toString, run.toString, "heldout") ++
      Vector(manifest.toString, blindOut.toString, "syn-seed")
    val out = Vector.newBuilder[String]
    assertEquals(ReviewQueueMain.run(args, out += _), 0, out.result().toString)
    val text = out.result()
    assert(
      text.head.startsWith(
        "membership split=heldout seed=syn-seed capacity=240 population=3 sampled=3 " +
          "rule=er-gold-census.v1 shortfall=237 (census"
      ),
      text.head
    )
    assert(
      text.exists(
        _.startsWith("blind view rows=3 schema=er-blind-label-view.v5 labelGuide=er-label-guide.v1")
      ),
      text.toString
    )
    val members = GoldMembership.readFile(manifest).fold(e => fail(e.mkString), identity)
    assertEquals(members.size, 3)
    assert(members.forall(m => m.seed.contains("syn-seed") && m.capacity.contains(240)))
    val sha = GoldMembership.fileSha256(manifest).fold(e => fail(e), identity)
    assert(text.contains(s"manifest sha256=$sha members=3"), text.toString)
    assert(text.exists(_.startsWith("population researcher=")), text.toString)
    assert(text.exists(_.startsWith("stratum ")), text.toString)
    val blindRows = lines(blindOut).map(l => upickle.default.read[BlindRow](l))
    assertEquals(blindRows.map(_.memberId).toSet, members.map(_.memberId).toSet)
    val blindText = Files.readString(blindOut, StandardCharsets.UTF_8)
    Vector("\"score\"", "\"system\"", "\"criterion\"", "syn-heldout", "syn-criterion", "\"split\"")
      .foreach(h => assert(!blindText.contains(h), h))
    assertEquals(ReviewQueueMain.run(args, _ => ()), 2, "refuses to overwrite")
    // Dev endpoints are refused for a held-out manifest; nothing is written.
    val (dsa, dpv) = unitFiles(base, "dev")
    val m2 = base.resolve("gold/other.jsonl")
    val b2 = base.resolve("gold/other-blind.jsonl")
    val devArgs = Vector("membership", dsa.toString, dpv.toString, run.toString, "heldout") ++
      Vector(m2.toString, b2.toString, "syn-seed")
    val devOut = Vector.newBuilder[String]
    assertEquals(ReviewQueueMain.run(devArgs, devOut += _), 2)
    assert(devOut.result().exists(_.contains("dev split")), devOut.result().toString)
    assert(!Files.exists(m2) && !Files.exists(b2))
    assertEquals(ReviewQueueMain.run(Vector("membership", "x"), _ => ()), 2)
  }

  test("ReviewQueueMain queue: rows of every member under the policy; diagnostics is dev-only") {
    val run = miniRun("reader-queue")
    val base = run.getParent.getParent.getParent.getParent
    val (sa, pv) = unitFiles(base, "heldout")
    val manifest = base.resolve("gold/er-gold-membership.jsonl")
    val blindOut = base.resolve("gold/er-blind-label-view.jsonl")
    assertEquals(
      ReviewQueueMain.run(
        Vector("membership", sa.toString, pv.toString, run.toString, "heldout") ++
          Vector(manifest.toString, blindOut.toString, "syn-seed"),
        _ => ()
      ),
      0
    )
    val internal = base.resolve("queue/er-review-queue.er-policy.v7.jsonl")
    val out = Vector.newBuilder[String]
    val qArgs = Vector("queue", manifest.toString, run.toString, internal.toString)
    assertEquals(ReviewQueueMain.run(qArgs, out += _, configLoader()), 0, out.result().toString)
    assert(
      out.result().head.startsWith("policy=er-policy.v8 members=3 rows=3 not-generated=0"),
      out.result().head
    )
    val rows = QueueFile.rows(internal)
    assertEquals(
      rows.map(_.memberId).sorted,
      GoldMembership.readFile(manifest).toOption.get.map(_.memberId)
    )
    assert(rows.forall(r => r.label.isEmpty && r.labeler.isEmpty))
    assertEquals(ReviewQueueMain.run(qArgs, _ => (), configLoader()), 2, "refuses to overwrite")
    // The outcome-stratified diagnostics refuse held-out endpoints and accept dev ones.
    val di = base.resolve("diag/internal.jsonl")
    val db = base.resolve("diag/blind.jsonl")
    val held =
      Vector("diagnostics", sa.toString, pv.toString, run.toString, di.toString, db.toString)
    val heldOut = Vector.newBuilder[String]
    assertEquals(ReviewQueueMain.run(held, heldOut += _, configLoader()), 2)
    assert(heldOut.result().exists(_.contains("dev diagnostics only")), heldOut.result().toString)
    assert(!Files.exists(di))
    val (dsa, dpv) = unitFiles(base, "dev")
    val devArgs =
      Vector("diagnostics", dsa.toString, dpv.toString, run.toString, di.toString, db.toString)
    val devOut = Vector.newBuilder[String]
    assertEquals(
      ReviewQueueMain.run(devArgs, devOut += _, configLoader()),
      0,
      devOut.result().toString
    )
    assert(devOut.result().head.startsWith("candidates resolved=3 refused=0 untraceable=0"))
    assert(devOut.result().exists(_.startsWith("cell work/ACCEPTED available=1")))
    val same = base.resolve("diag/same.jsonl")
    assertEquals(
      ReviewQueueMain.run(
        Vector(
          "diagnostics",
          dsa.toString,
          dpv.toString,
          run.toString,
          same.toString,
          same.toString
        ),
        _ => (),
        configLoader()
      ),
      2
    )
    assertEquals(ReviewQueueMain.run(Vector("only-one-arg"), _ => ()), 2)
  }

  test(
    "RV-P5-04 m6: queue and diagnostics apply the external configuration; a missing or mismatched one is refused"
  ) {
    val run = miniRun("reader-config")
    val base = run.getParent.getParent.getParent.getParent
    val (sa, pv) = unitFiles(base, "heldout")
    val manifest = base.resolve("gold/er-gold-membership.jsonl")
    val blindOut = base.resolve("gold/er-blind-label-view.jsonl")
    assertEquals(
      ReviewQueueMain.run(
        Vector("membership", sa.toString, pv.toString, run.toString, "heldout") ++
          Vector(manifest.toString, blindOut.toString, "syn-seed"),
        _ => (),
        noConfig
      ),
      0,
      "membership loads no policy and reads no configuration"
    )
    // Configured thresholds and band (SYNTHETIC values, valid ordering) differ from the file's.
    val configured = configText(accept = 0.9, reject = 0.1, lower = 0.3, upper = 0.6)
    val internal = base.resolve("queue/configured.jsonl")
    val qArgs = Vector("queue", manifest.toString, run.toString, internal.toString)
    val out = Vector.newBuilder[String]
    assertEquals(ReviewQueueMain.run(qArgs, out += _, configLoader(configured)), 0)
    val rows = QueueFile.rows(internal)
    assertEquals(rows.size, 3)
    assert(
      rows.forall(_.system.thresholds.contains(DecisionThresholds(0.9, 0.1, 0.3, 0.6))),
      rows.map(_.system.thresholds).toString
    )
    assert(rows.forall(_.system.scoreGate.isDefined))
    assert(rows.forall(_.schemaVersion == "er-review-queue-row.v4"))
    val settings =
      out.result().find(_.startsWith("settings ")).getOrElse(fail(out.result().toString))
    assert(
      settings.contains(
        "settings policy=er-policy.v8 blocking=er-blocking.v2 features=er-features.v2 " +
          "accept=0.9 reject=0.1 llmBand=[0.3,0.6] prompt=er-adjudication-prompt.v2 model=none"
      ),
      settings
    )
    // Refusals: no configuration, a configuration for another policy or prompt version. Nothing is
    // written in any of these runs.
    val refusedCases = Vector(
      "no configuration" -> noConfig,
      "retired policy v6" -> configLoader(configText(policyVersion = "er-policy.v6")),
      "other prompt" -> configLoader(configText(promptVersion = "er-adjudication-prompt.v1"))
    )
    refusedCases.zipWithIndex.foreach { case ((clue, loader), i) =>
      val target = base.resolve(s"queue/refused-$i.jsonl")
      val o = Vector.newBuilder[String]
      val code =
        ReviewQueueMain.run(
          Vector("queue", manifest.toString, run.toString, target.toString),
          o += _,
          loader
        )
      assertEquals(code, 2, clue)
      assert(o.result().exists(_.contains("configuration refused")), s"$clue: ${o.result()}")
      assert(!Files.exists(target), clue)
    }
    val (dsa, dpv) = unitFiles(base, "dev")
    val di = base.resolve("diag/refused-internal.jsonl")
    val db = base.resolve("diag/refused-blind.jsonl")
    assertEquals(
      ReviewQueueMain.run(
        Vector("diagnostics", dsa.toString, dpv.toString, run.toString, di.toString, db.toString),
        _ => (),
        noConfig
      ),
      2
    )
    assert(!Files.exists(di) && !Files.exists(db))
  }
}

/** Reads a written queue back (test helper; the evaluation package owns the strict parser). */
object QueueFile {
  def rows(p: Path): Vector[ReviewQueueRow] =
    Files
      .readString(p, StandardCharsets.UTF_8)
      .split('\n')
      .toVector
      .filter(_.nonEmpty)
      .map(l => upickle.default.read[ReviewQueueRow](l))
}
