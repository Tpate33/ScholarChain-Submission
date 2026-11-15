package scholarchain.entityresolution.review

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scholarchain.core.config.ConfigLoader
import scholarchain.core.{Json, MatchCandidate, SourceAuthorProfile, WorkRecord}
import scholarchain.entityresolution.{ConfigEvidence, Samples}
import scholarchain.entityresolution.Samples.{em, ep}
import scholarchain.entityresolution.policy.PolicyFixtures.*

/**
 * ER-P5-15 (ER-P5-14 G8; decisions-15 item 1 "enforce the expected split at runtime"): the review
 * queue refuses a manifest of the other split (`--expect-split`) and a curated run that does not
 * hold the members' endpoint records (a run of the other split), instead of writing an empty queue
 * with every member not-generated. It also prints the configuration evidence. SYNTHETIC miniature
 * runs of sample-mirroring records; no labels anywhere.
 */
class QueueSplitGuardSuite extends munit.FunSuite {

  private def plain(p: Path, lines: Vector[String]): Unit = {
    Files.createDirectories(p.getParent)
    Files.writeString(p, lines.mkString("", "\n", if (lines.isEmpty) "" else "\n"))
  }

  /** A completed miniature run in the Hadoop layout with the given records and candidates. */
  private def miniRun(
      base: Path,
      name: String,
      works: Vector[WorkRecord],
      profiles: Vector[SourceAuthorProfile],
      researcher: Vector[MatchCandidate],
      work: Vector[MatchCandidate]
  ): Path = {
    val run = base.resolve(s"$name/hadoop/snapshot=dblp-2026-10-03/run=" + "1" * 64)
    plain(run.resolve("normalize/works/part-r-00000"), works.map(Json.write[WorkRecord]))
    plain(
      run.resolve("normalize/profiles/part-r-00000"),
      profiles.map(Json.write[SourceAuthorProfile])
    )
    plain(
      run.resolve("candidates/final/researcher/part-r-00000"),
      researcher.map(Json.write[MatchCandidate])
    )
    plain(run.resolve("candidates/final/work/part-r-00000"), work.map(Json.write[MatchCandidate]))
    plain(run.resolve("normalize/_SUCCESS"), Vector.empty)
    plain(run.resolve("candidates/_SUCCESS"), Vector.empty)
    run
  }

  private val heldoutWorks = Vector(Samples.spannerDblp, Samples.spannerCrossref, Samples.cacmDblp)

  private def heldoutRun(base: Path): Path =
    miniRun(
      base,
      "heldout-run",
      heldoutWorks,
      Vector(Samples.popperOpenAlex),
      Vector(
        researcherCandidate(em(Samples.cacmDblp, 0), ep(Samples.popperOpenAlex)),
        researcherCandidate(em(Samples.spannerDblp, 4), em(Samples.spannerCrossref, 4))
      ),
      Vector(workCandidate(Samples.spannerDblp, Samples.spannerCrossref))
    )

  /** A run of other records (standing in for the other split's run). */
  private def otherRun(base: Path): Path =
    miniRun(
      base,
      "other-run",
      Vector(Samples.varajaoDblp, Samples.bigtableDblp),
      Vector.empty,
      Vector.empty,
      Vector(workCandidate(Samples.varajaoDblp, Samples.bigtableDblp))
    )

  private def unitFiles(base: Path, split: String): (Path, Path) = {
    val anchors = heldoutWorks.map(_.workId) :+ Samples.popperOpenAlex.profileId
    val (sa, pv) = SyntheticUnits.texts(Vector((s"syn-$split", "syn-criterion", split, anchors)))
    val saPath = base.resolve(s"units-$split/split-assignment.tsv")
    val pvPath = base.resolve(s"units-$split/PROVENANCE.tsv")
    plain(saPath, sa.split('\n').toVector)
    plain(pvPath, pv.split('\n').toVector)
    (saPath, pvPath)
  }

  /** The membership of the mini run for `split`, created through the CLI; returns its path. */
  private def membership(base: Path, run: Path, split: String): Path = {
    val (sa, pv) = unitFiles(base, split)
    val manifest = base.resolve(s"gold/$split-v1/er-gold-membership.jsonl")
    val blind = base.resolve(s"gold/$split-v1/er-blind-label-view.jsonl")
    val out = Vector.newBuilder[String]
    val args = Vector("membership", sa.toString, pv.toString, run.toString, split) ++
      Vector(manifest.toString, blind.toString, "syn-seed", "240")
    assertEquals(ReviewQueueMain.run(args, out += _, noConfig), 0, out.result().mkString("\n"))
    manifest
  }

  private def queue(args: Vector[String]): (Int, Vector[String]) = {
    val out = Vector.newBuilder[String]
    val code = ReviewQueueMain.run(args, out += _, configLoader())
    (code, out.result())
  }

  test("queue refuses a run that does not hold the members' records; nothing is written") {
    val base = workDir("er-p5-15-queue-other-run")
    val run = heldoutRun(base)
    val manifest = membership(base, run, "heldout")
    val target = base.resolve("gold/heldout-v1/er-review-queue.jsonl")
    val wrong = otherRun(base)
    val (code, text) = queue(Vector("queue", manifest.toString, wrong.toString, target.toString))
    assertEquals(code, 2, text.mkString("\n"))
    assert(
      text.exists(_.contains("3 of 3 members have endpoint records that are not in the run")),
      text.mkString("\n")
    )
    assert(text.exists(_.contains("a run of another split")), text.mkString("\n"))
    assert(!Files.exists(target), "a queue was written for the wrong run")
    // The generating run is accepted: rows for every member, none not-generated.
    val (ok, okText) = queue(Vector("queue", manifest.toString, run.toString, target.toString))
    assertEquals(ok, 0, okText.mkString("\n"))
    assert(okText.head.startsWith("policy=er-policy.v8 members=3 rows=3 not-generated=0"))
  }

  test("RunQueue.missingEndpoints names each member endpoint absent from the run") {
    val base = workDir("er-p5-15-missing-endpoints")
    val run = heldoutRun(base)
    val members =
      GoldMembership
        .readFile(membership(base, run, "heldout"))
        .fold(e => fail(e.mkString), identity)
    val own = CuratedRunReader.read(run).fold(e => fail(e), identity)
    assertEquals(RunQueue.missingEndpoints(members, own), Vector.empty)
    val other = CuratedRunReader.read(otherRun(base)).fold(e => fail(e), identity)
    val missing = RunQueue.missingEndpoints(members, other)
    assertEquals(missing.map(_._1).distinct.sorted, members.map(_.memberId).sorted)
    assertEquals(missing, missing.sorted)
    // A run that lacks one record misses exactly the members with an endpoint on it.
    val partial = own.copy(profiles = Vector.empty)
    val popper = Samples.popperOpenAlex.profileId
    assertEquals(
      RunQueue.missingEndpoints(members, partial).map(_._2).distinct,
      Vector(popper)
    )
  }

  test("queue --expect-split refuses a manifest of the other split and accepts its own") {
    val base = workDir("er-p5-15-queue-expect-split")
    val run = heldoutRun(base)
    val manifest = membership(base, run, "heldout")
    val target = base.resolve("gold/heldout-v1/er-review-queue.jsonl")
    val qArgs = Vector("queue", manifest.toString, run.toString, target.toString)
    val (c1, o1) = queue(qArgs :+ "--expect-split=dev")
    assertEquals(c1, 2, o1.mkString("\n"))
    assert(
      o1.exists(_.contains("the membership manifest is split=heldout, not --expect-split=dev")),
      o1.mkString("\n")
    )
    assert(!Files.exists(target))
    assertEquals(queue(qArgs :+ "--expect-split=test")._1, 2)
    assertEquals(queue(qArgs :+ "--other")._1, 2)
    val (c2, o2) = queue(qArgs :+ "--expect-split=heldout")
    assertEquals(c2, 0, o2.mkString("\n"))
    assert(o2.contains("expect-split=heldout matches the manifest"), o2.mkString("\n"))
    // A dev manifest of the same records is refused as held-out.
    val devManifest = membership(base, run, "dev")
    val devTarget = base.resolve("gold/dev-v1/er-review-queue.jsonl")
    val (c3, o3) = queue(
      Vector(
        "queue",
        devManifest.toString,
        run.toString,
        devTarget.toString,
        "--expect-split=heldout"
      )
    )
    assertEquals(c3, 2, o3.mkString("\n"))
    assert(o3.exists(_.contains("split=dev, not --expect-split=heldout")), o3.mkString("\n"))
    assert(!Files.exists(devTarget))
  }

  test("queue prints the configuration file and its sha256; membership reads no configuration") {
    val base = workDir("er-p5-15-queue-config")
    val run = heldoutRun(base)
    val manifest = membership(base, run, "heldout")
    val conf = base.resolve("gold/heldout-v1/er-config.conf")
    Files.writeString(conf, configText(), StandardCharsets.UTF_8)
    val confSha = GoldMembership.fileSha256(conf).fold(e => fail(e), identity)
    val target = base.resolve("gold/heldout-v1/er-review-queue.jsonl")
    val out = Vector.newBuilder[String]
    val code = ReviewQueueMain.runWithConfig(
      Vector("queue", manifest.toString, run.toString, target.toString, "--expect-split=heldout"),
      out += _,
      () =>
        ConfigEvidence.load(
          Map(ConfigLoader.FilesEnvVar -> conf.toString),
          ConfigLoader.readLocalFile
        )
    )
    val text = out.result()
    assertEquals(code, 0, text.mkString("\n"))
    assert(
      text.contains(s"config file=${conf.toAbsolutePath.normalize} sha256=$confSha"),
      text.mkString("\n")
    )
    assert(text.contains("config overrides=none"), text.mkString("\n"))
    // membership never evaluates the configuration source.
    val (sa, pv) = unitFiles(base, "heldout")
    var read = false
    val m2 = base.resolve("gold/again/er-gold-membership.jsonl")
    val b2 = base.resolve("gold/again/er-blind-label-view.jsonl")
    val mOut = Vector.newBuilder[String]
    assertEquals(
      ReviewQueueMain.runWithConfig(
        Vector("membership", sa.toString, pv.toString, run.toString, "heldout") ++
          Vector(m2.toString, b2.toString, "syn-seed", "240"),
        mOut += _,
        () => { read = true; ConfigEvidence.injected(configLoader()) }
      ),
      0
    )
    assert(!read, "membership read the configuration")
    assert(
      mOut
        .result()
        .exists(l =>
          l.startsWith("freeze file: in ") &&
            l.contains(
              "shasum -a 256 er-gold-membership.jsonl > er-gold-membership.jsonl.sha256"
            ) &&
            l.contains("--manifest-sha256-file=<that file> --expect-split=heldout")
        ),
      mOut.result().mkString("\n")
    )
  }
}
