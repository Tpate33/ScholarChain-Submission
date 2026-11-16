package scholarchain.entityresolution.grouping

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.immutable.SortedMap
import scala.jdk.CollectionConverters.*

import scholarchain.core.*
import scholarchain.core.config.{ConfigError, GroupingRunSettings, ScholarChainConfig}
import scholarchain.entityresolution.{
  BlockingKeys,
  EntityResolution,
  MatchFeatures,
  ResearcherEndpoint,
  Samples
}
import scholarchain.entityresolution.Samples.{em, ep}
import scholarchain.entityresolution.decisions.{DecisionExport, PublishResult}
import scholarchain.entityresolution.policy.PolicyFixtures
import scholarchain.entityresolution.review.{CuratedRunReader, GroupingRunMain, ReviewQueueMain}

/**
 * ER-P6-05: publication and verified reading of grouping runs, the CR-P6-06 m1 read-back regression
 * for the grouping publisher, the entry point, and the committed-sample probe. Every fixture is
 * SYNTHETIC except the probe, which reads the existing Hadoop committed-sample test output (skipped
 * without it). Everything is written under modules/entity-resolution/target/test-work. No gold
 * file, label or held-out output is read.
 */
class GroupingExportSuite extends munit.FunSuite {
  import GroupingFixtures.{group, sampleInputs}

  private def entries(root: Path): Vector[String] =
    if (!Files.exists(root)) Vector.empty
    else {
      val s = Files.walk(root)
      try s.iterator.asScala.toVector.filter(_ != root).map(root.relativize(_).toString).sorted
      finally s.close()
    }

  private def tree(dir: Path): Map[String, Vector[Byte]] = {
    val s = Files.walk(dir)
    try
      s.iterator.asScala.toVector
        .filter(Files.isRegularFile(_))
        .map(p => dir.relativize(p).toString -> Files.readAllBytes(p).toVector)
        .toMap
    finally s.close()
  }

  private def copyRun(src: Path, dstRoot: Path, name: Option[String] = None): Path = {
    val dst = dstRoot.resolve(name.getOrElse(src.getFileName.toString))
    val s = Files.walk(src)
    try
      s.iterator.asScala.toVector.foreach { p =>
        val rel = src.relativize(p).toString
        if (Files.isDirectory(p)) Files.createDirectories(dst.resolve(rel))
        else {
          Files.createDirectories(dst.resolve(rel).getParent)
          Files.copy(p, dst.resolve(rel))
        }
      }
    finally s.close()
    dst
  }

  private def rewrite(p: Path, bytes: Array[Byte]): Unit = {
    Files.delete(p)
    Files.write(p, bytes)
  }

  private lazy val in = sampleInputs()
  private lazy val batch = group(in)

  private val files = Vector(
    GroupingExport.ResearcherPart,
    GroupingExport.WorkPart,
    GroupingExport.AuthorshipPart,
    GroupingExport.TeamPart
  )

  // ---------------------------------------------------------------- 11. publication

  test("11. publication: G2 order, _parts.json of the bytes on disk, byte-identical rerun") {
    val base = PolicyFixtures.workDir("grouping-publish")
    var order = Vector.empty[String]
    val recording: DecisionExport.FileWriter = (p, text) => {
      val names = p.iterator.asScala.map(_.toString).toVector
      val at = names.indexWhere(_.startsWith(DecisionExport.StagingPrefix))
      order :+= names.drop(at + 1).mkString("/")
      DecisionExport.DefaultFileWriter(p, text)
    }
    val dir =
      GroupingExport.publish(base.resolve("out-1"), batch, recording).fold(fail(_), identity)
    assertEquals(order, files ++ Vector("_run-manifest.json", "_parts.json", "_SUCCESS"))
    assertEquals(dir.getFileName.toString, DecisionExport.runDirName(batch.runId))
    val pm = Json
      .decode[PartManifest](
        Files.readString(dir.resolve("_parts.json"), StandardCharsets.UTF_8).stripSuffix("\n")
      )
      .fold(e => fail(e.toString), identity)
    assertEquals(pm.runId, batch.runId)
    assertEquals(pm.files.map(_.path).toSet, (files :+ "_run-manifest.json").toSet)
    pm.files.foreach { f =>
      val o = ObservedFile.of(f.path, Files.readAllBytes(dir.resolve(f.path)))
      assertEquals((f.rowCount, f.byteCount, f.sha256), (o.rowCount, o.byteCount, o.sha256))
    }
    // The same batch under another root gives the same bytes.
    val again = GroupingExport.publish(base.resolve("out-2"), batch).fold(fail(_), identity)
    assertEquals(tree(again), tree(dir))
    assertEquals(GroupingExport.publishedRuns(base.resolve("out-1")), Vector(dir))
    val run = GroupingExport.readValidated(dir).fold(fail(_), identity)
    assertEquals(run.researchers, batch.researchers)
    assertEquals(run.works, batch.works)
    assertEquals(run.authorships, batch.authorships)
    assertEquals(run.teams, batch.teams)
    assertEquals(run.manifest, batch.manifest)
  }

  test("11. no overwrite, and nothing is left after a failure at any write") {
    val base = PolicyFixtures.workDir("grouping-failure")
    val out = base.resolve("out")
    val dir = GroupingExport.publish(out, batch).fold(fail(_), identity)
    val before = tree(dir)
    assert(GroupingExport.publish(out, batch).left.exists(_.contains("refusing to overwrite")))
    assertEquals(tree(dir), before)
    // An I/O failure injected at each write in turn (CR-P6-06 n1 for this publisher).
    (files ++ Vector("_run-manifest.json", "_parts.json", "_SUCCESS")).zipWithIndex.foreach {
      case (target, i) =>
        val root = base.resolve(s"fail-$i")
        val failing: DecisionExport.FileWriter = (p, text) =>
          if (p.toString.endsWith("/" + target)) throw new java.io.IOException("injected")
          else DecisionExport.DefaultFileWriter(p, text)
        val failed = GroupingExport.publish(root, batch, failing)
        assert(failed.left.exists(_.contains("nothing published")), s"$target: $failed")
        assertEquals(entries(root), Vector.empty, s"$target: the staging directory was removed")
        assertEquals(GroupingExport.publishedRuns(root), Vector.empty, target)
    }
  }

  test("11. a cut-short, extended, renamed or partial copy is refused; an exact copy reads") {
    val base = PolicyFixtures.workDir("grouping-integrity")
    val dir = GroupingExport.publish(base.resolve("out"), batch).fold(fail(_), identity)
    val exact = copyRun(dir, base.resolve("exact"))
    assertEquals(GroupingExport.readValidated(exact).map(_.researchers), Right(batch.researchers))
    val part = GroupingExport.ResearcherPart
    val cases: Vector[(String, Path => Path, String)] = Vector(
      (
        "cut at a line boundary",
        d => {
          val b = Files.readAllBytes(d.resolve(part))
          rewrite(d.resolve(part), b.take(b.indexOf('\n'.toByte) + 1))
          d
        },
        s"rowCount: listed ${batch.researchers.size}, observed 1"
      ),
      (
        "a missing team part",
        d => { Files.delete(d.resolve(GroupingExport.TeamPart)); d },
        "teams"
      ),
      (
        "an extra file",
        d => { Files.write(d.resolve("assignments/notes.txt"), "x\n".getBytes); d },
        "extra file not listed"
      ),
      (
        "an extra directory",
        d => { Files.createDirectories(d.resolve("assignments/venue")); d },
        "unexpected directory assignments/venue"
      ),
      (
        "no _parts.json",
        d => { Files.delete(d.resolve("_parts.json")); d },
        "no regular _parts.json"
      )
    )
    cases.zipWithIndex.foreach { case ((clue, breakIt, expected), i) =>
      val copy = breakIt(copyRun(dir, base.resolve(s"case-$i")))
      val read = GroupingExport.readPublished(copy)
      assert(read.left.exists(_.contains(expected)), s"$clue: $read")
      assertEquals(GroupingExport.publishedRuns(copy.getParent), Vector.empty, clue)
    }
    // A complete run copied under another run's directory name (CR-P6-02 nit 1 for this reader).
    val renamed = copyRun(dir, base.resolve("renamed"), Some("run=" + "7" * 64))
    val read = GroupingExport.readPublished(renamed)
    assert(read.left.exists(_.contains("the directory holds run run:" + "7" * 64)), read)
    // The decision reader refuses a grouping run, and the grouping reader a decision run.
    assert(DecisionExport.readPublished(dir).isLeft)
  }

  test("CR-P6-06 m1 (grouping): _parts.json records the bytes read back, not the in-memory text") {
    val base = PolicyFixtures.workDir("grouping-read-back")
    // A writer whose bytes on disk differ from its argument: one extra valid row (a copy of the
    // first work assignment) is appended to the work part.
    val extra: DecisionExport.FileWriter = (p, text) =>
      if (p.toString.endsWith("/" + GroupingExport.WorkPart))
        DecisionExport.DefaultFileWriter(p, text + text.linesIterator.next() + "\n")
      else DecisionExport.DefaultFileWriter(p, text)
    // ER-P6-07: the staged verification refuses these bytes and preserves them; _parts.json in
    // the preserved directory is examined.
    val out = base.resolve("out")
    val (dir, why) = GroupingExport.publishRun(out, batch, extra) match {
      case PublishResult.Unverified(p, why) => (p.path, why)
      case other => fail(s"expected an unverified result: $other")
    }
    assert(dir.getFileName.toString.startsWith(".unverified-run="), dir.toString)
    val inMemory =
      GroupingExport
        .toJsonl(batch.works)
        .fold(e => fail(e.mkString), identity)
        .getBytes(StandardCharsets.UTF_8)
    val onDisk = Files.readAllBytes(dir.resolve(GroupingExport.WorkPart))
    assertNotEquals(onDisk.toVector, inMemory.toVector)
    val pm = Json
      .decode[PartManifest](
        Files.readString(dir.resolve("_parts.json"), StandardCharsets.UTF_8).stripSuffix("\n")
      )
      .fold(e => fail(e.toString), identity)
    val listed = pm.files.find(_.path == GroupingExport.WorkPart).get
    val disk = ObservedFile.of(GroupingExport.WorkPart, onDisk)
    val memory = ObservedFile.of(GroupingExport.WorkPart, inMemory)
    assertEquals(
      (listed.rowCount, listed.byteCount, listed.sha256),
      (disk.rowCount, disk.byteCount, disk.sha256)
    )
    assertEquals(listed.rowCount, batch.works.size + 1L)
    assertNotEquals(listed.rowCount, memory.rowCount)
    assertNotEquals(listed.byteCount, memory.byteCount)
    assertNotEquals(listed.sha256, memory.sha256)
    // The integrity manifest matched the bytes on disk: the staged reader got past _parts.json, and
    // the dataset validators refused the duplicated work assignment.
    assert(why.contains("appears in 2 records"), why)
    assertEquals(GroupingExport.publishedRuns(out), Vector.empty)
    assert(GroupingExport.readValidated(dir).isLeft, "a preserved directory is never read")
  }

  // ---------------------------------------------------------------- entry point

  private val snapshots = Vector(
    SnapshotRef(SourceType.Dblp, "dblp-2026-10-03"),
    SnapshotRef(SourceType.OpenAlex, "openalex-2026-10-14"),
    SnapshotRef(SourceType.Crossref, "crossref-2026-10-01")
  )

  /** SYNTHETIC manifest of the curated (Hadoop) input run. */
  private val inputManifest = RunManifest(
    SchemaVersions.RunManifest,
    "hadoop.pipeline",
    "a" * 40,
    "b" * 64,
    snapshots,
    SortedMap.empty[String, String],
    1
  )
  private val inputRunId: String = GroupingFixtures.ok(RunId.of(inputManifest))

  private val candidateProvenance = Provenance(
    SourceType.Derived,
    "fixture",
    "dblp-2026-10-03",
    inputRunId,
    SchemaVersions.CandidateBlock,
    Some("hadoop-transform.v2"),
    None,
    None
  )

  private def rc(a: ResearcherEndpoint, b: ResearcherEndpoint): MatchCandidate =
    GroupingFixtures.ok(
      MatchCandidate.build(
        MatchEntityKind.Researcher,
        a.id,
        a.source,
        b.id,
        b.source,
        Vector(BlockKey(0, "fixture|x")),
        BlockingKeys.Version,
        MatchFeatures.Version,
        MatchFeatures.researcher(a, b),
        EntityResolution.researcherExactIdAgreements(a, b),
        candidateProvenance
      )
    )

  private def wc(a: WorkRecord, b: WorkRecord): MatchCandidate =
    GroupingFixtures.ok(
      MatchCandidate.build(
        MatchEntityKind.Work,
        a.workId,
        a.source,
        b.workId,
        b.source,
        Vector(BlockKey(0, "fixture_title")),
        BlockingKeys.Version,
        MatchFeatures.Version,
        MatchFeatures.work(a, b),
        EntityResolution.workExactIdAgreements(a, b),
        candidateProvenance
      )
    )

  import Samples.{
    bigtableDblp,
    cacmCrossref,
    cacmDblp,
    popperOpenAlex,
    spannerCrossref,
    spannerDblp
  }

  private val works = Vector(spannerDblp, spannerCrossref, cacmDblp, cacmCrossref, bigtableDblp)
  private val profiles = Vector(popperOpenAlex)
  private val candidates = Vector(
    rc(em(cacmDblp, 0), ep(popperOpenAlex)),
    rc(em(spannerDblp, 4), em(spannerCrossref, 4)),
    rc(em(spannerDblp, 1), em(bigtableDblp, 1)),
    rc(em(cacmDblp, 0), em(cacmDblp, 1)),
    rc(em(cacmDblp, 0), em(cacmCrossref, 0)),
    wc(spannerDblp, spannerCrossref),
    wc(cacmDblp, cacmCrossref),
    wc(bigtableDblp, spannerDblp)
  )

  private def plain(p: Path, lines: Vector[String]): Unit = {
    Files.createDirectories(p.getParent)
    Files.writeString(p, lines.map(_ + "\n").mkString, StandardCharsets.UTF_8)
  }

  /** A completed SYNTHETIC curated run in the Hadoop layout, with its `_run-manifest.json`. */
  private def writeCurated(
      base: Path,
      worksRun: String = inputRunId,
      profilesRun: String = inputRunId
  ): Path = {
    val run = base.resolve(
      "curated/hadoop/snapshot=dblp-2026-10-03/" + DecisionExport.runDirName(inputRunId)
    )
    plain(run.resolve(DecisionExport.ManifestFile), Vector(Json.write(inputManifest)))
    plain(
      run.resolve("normalize/works/part-r-00000"),
      works.map(w => Json.write[WorkRecord](GroupingFixtures.ofRun(w, worksRun)))
    )
    plain(
      run.resolve("normalize/profiles/part-r-00000"),
      profiles.map(p =>
        Json.write[SourceAuthorProfile](GroupingFixtures.profileOfRun(p, profilesRun))
      )
    )
    plain(
      run.resolve("candidates/final/researcher/part-r-00000"),
      candidates.filter(_.entityKind == MatchEntityKind.Researcher).map(Json.write[MatchCandidate])
    )
    plain(
      run.resolve("candidates/final/work/part-r-00000"),
      candidates.filter(_.entityKind == MatchEntityKind.Work).map(Json.write[MatchCandidate])
    )
    plain(run.resolve("normalize/_SUCCESS"), Vector.empty)
    plain(run.resolve("candidates/_SUCCESS"), Vector.empty)
    run
  }

  private type Loader = () => Either[Vector[ConfigError], ScholarChainConfig]

  /** The SYNTHETIC test configuration plus the three grouping keys. */
  private def grouped(decisions: String, assignments: String, version: String = "er-grouping.v1") =
    PolicyFixtures.configLoader(
      PolicyFixtures.configText() +
        s"""entityResolution.groupingPolicyVersion = "$version"
           |entityResolution.decisionRunRoot = "$decisions"
           |entityResolution.assignmentRunRoot = "$assignments"
           |""".stripMargin
    )

  private final case class Prepared(
      base: Path,
      curated: Path,
      decisionDir: Path,
      decisionRoot: Path,
      assignmentRoot: Path,
      loader: Loader,
      grouping: RunManifest
  )

  /**
   * Decides `curatedRun` with the existing decision exporter into `<base>/decisions` and writes a
   * grouping manifest that fits it; the configuration's run roots are the test-work directories.
   */
  private def prepare(
      base: Path,
      curatedRun: Path,
      curatedManifest: RunManifest,
      curated: scholarchain.entityresolution.review.CuratedRun
  ): Prepared = {
    val decisionRoot = base.resolve("decisions")
    val assignmentRoot = base.resolve("assignments")
    val loader = grouped(decisionRoot.toString, assignmentRoot.toString)
    val cfg = loader().fold(e => fail(e.toString), identity)
    val effective = ReviewQueueMain.effectivePolicy(loader).fold(fail(_), identity)
    val decisionDigest = ScholarChainConfig.digestWithoutGrouping(cfg)
    val dm = RunManifest(
      SchemaVersions.RunManifest,
      DecisionExport.Stage,
      "c" * 40,
      decisionDigest,
      curatedManifest.inputSnapshots,
      SortedMap(
        DecisionExport.Params.BlockingVersion -> effective.blockingVersion,
        DecisionExport.Params.DecidedAt -> "2026-11-15T00:00:00Z",
        DecisionExport.Params.FeatureVersion -> effective.featureVersion,
        DecisionExport.Params.InputRunId -> GroupingFixtures.ok(RunId.of(curatedManifest)),
        DecisionExport.Params.PolicyVersion -> effective.policyVersion
      ),
      1
    )
    val decided = DecisionExport
      .decideAll(
        effective,
        curated.works,
        curated.profiles,
        curated.candidates,
        curatedManifest,
        dm,
        decisionDigest
      )
      .fold(e => fail(e.take(5).mkString("; ")), identity)
    val decisionDir = DecisionExport.publish(decisionRoot, decided).fold(fail(_), identity)
    val settings = GroupingRunSettings.from(cfg).fold(e => fail(e.toString), identity)
    val gm = RunManifest(
      SchemaVersions.RunManifest,
      Grouping.Stage,
      "f" * 40,
      GroupingRunSettings.configDigest(cfg).fold(e => fail(e.toString), identity),
      curatedManifest.inputSnapshots,
      SortedMap(
        Grouping.Params.AssignedAt -> "2026-11-15T12:00:00Z",
        Grouping.Params.DecisionRunId -> decided.runId,
        Grouping.Params.GroupingPolicyVersion -> settings.groupingPolicyVersion,
        Grouping.Params.IdentityVersion -> settings.identityVersion,
        Grouping.Params.InputRunId -> GroupingFixtures.ok(RunId.of(curatedManifest)),
        Grouping.Params.MatchPolicyVersion -> settings.matchPolicyVersion
      ),
      1
    )
    Prepared(base, curatedRun, decisionDir, decisionRoot, assignmentRoot, loader, gm)
  }

  private var manifestCount = 0

  private def runMain(
      p: Prepared,
      m: RunManifest = null,
      decisionDir: Path = null,
      outputRoot: Path = null,
      loader: Loader = null
  ): (Int, Vector[String]) = {
    manifestCount += 1
    val mf = p.base.resolve(s"manifests/grouping-run-$manifestCount.json")
    plain(mf, Vector(Json.write(Option(m).getOrElse(p.grouping))))
    val lines = Vector.newBuilder[String]
    val code = GroupingRunMain.run(
      Vector(
        mf.toString,
        Option(decisionDir).getOrElse(p.decisionDir).toString,
        p.curated.toString,
        Option(outputRoot).getOrElse(p.assignmentRoot).toString
      ),
      lines += _,
      Option(loader).getOrElse(p.loader)
    )
    (code, lines.result())
  }

  private def synthetic(
      name: String,
      worksRun: String = inputRunId,
      profilesRun: String = inputRunId
  ): Prepared = {
    val base = PolicyFixtures.workDir(name)
    val curatedRun = writeCurated(base, worksRun, profilesRun)
    val curated = CuratedRunReader.read(curatedRun).fold(fail(_), identity)
    prepare(base, curatedRun, inputManifest, curated)
  }

  /**
   * Rewrites the works and profiles of a curated run with the given run IDs on works, on mentions
   * and on profiles (the candidates and `_run-manifest.json` stay those of the curated run).
   */
  private def rewriteCurated(
      run: Path,
      worksRun: String,
      mentionsRun: String,
      profilesRun: String
  ): Unit = {
    val ws = works.map { w =>
      val x = GroupingFixtures.ofRun(w, worksRun)
      x.copy(authors =
        x.authors.map(a => a.copy(provenance = a.provenance.copy(runId = mentionsRun)))
      )
    }
    plain(run.resolve("normalize/works/part-r-00000"), ws.map(Json.write[WorkRecord]))
    plain(
      run.resolve("normalize/profiles/part-r-00000"),
      profiles.map(p =>
        Json.write[SourceAuthorProfile](GroupingFixtures.profileOfRun(p, profilesRun))
      )
    )
  }

  test(
    "CR-P6-07 finding 1 (entry point): a curated run with another run's data publishes nothing"
  ) {
    val other = "run:" + "5" * 64
    val expected = s"provenance.runId is not the curated run $inputRunId"
    val i = inputRunId
    Vector(
      ("works", (other, i, i), "work "),
      ("mentions", (i, other, i), "mention "),
      ("profiles", (i, i, other), "profile ")
    ).foreach { case (clue, (worksRun, mentionsRun, profilesRun), prefix) =>
      // ER-P6-07 (CR-P6-08 minor 1): the decision exporter now refuses such a curated run itself.
      val mixedBase = PolicyFixtures.workDir(s"grouping-mixed-decisions-$clue")
      val mixedRun = writeCurated(mixedBase)
      rewriteCurated(mixedRun, worksRun, mentionsRun, profilesRun)
      val mixed = CuratedRunReader.read(mixedRun).fold(fail(_), identity)
      val loader = grouped(
        mixedBase.resolve("decisions").toString,
        mixedBase.resolve("assignments").toString
      )
      val cfg = loader().fold(e => fail(e.toString), identity)
      val effective = ReviewQueueMain.effectivePolicy(loader).fold(fail(_), identity)
      val digest = ScholarChainConfig.digestWithoutGrouping(cfg)
      val dm = RunManifest(
        SchemaVersions.RunManifest,
        DecisionExport.Stage,
        "c" * 40,
        digest,
        inputManifest.inputSnapshots,
        SortedMap(
          DecisionExport.Params.BlockingVersion -> effective.blockingVersion,
          DecisionExport.Params.DecidedAt -> "2026-11-15T00:00:00Z",
          DecisionExport.Params.FeatureVersion -> effective.featureVersion,
          DecisionExport.Params.InputRunId -> inputRunId,
          DecisionExport.Params.PolicyVersion -> effective.policyVersion
        ),
        1
      )
      val refused = DecisionExport.decideAll(
        effective,
        mixed.works,
        mixed.profiles,
        mixed.candidates,
        inputManifest,
        dm,
        digest
      )
      assert(
        refused.left.exists(es =>
          es.nonEmpty && es.forall(e => e.startsWith(prefix) && e.contains(expected))
        ),
        s"$clue: $refused"
      )
      // The grouping check still holds on its own: the decisions are made from the curated run,
      // whose works or profiles are then replaced by another run's.
      val p = synthetic(s"grouping-mixed-$clue")
      rewriteCurated(p.curated, worksRun, mentionsRun, profilesRun)
      assertEquals(DecisionExport.publishedRuns(p.decisionRoot), Vector(p.decisionDir), clue)
      val (code, lines) = runMain(p)
      assertEquals(code, 2, s"$clue: ${lines.mkString("\n")}")
      assert(
        lines.exists(l =>
          l.contains("grouping run refused, nothing published") && l.contains(prefix) &&
            l.contains(expected)
        ),
        s"$clue: ${lines.mkString("\n")}"
      )
      assertEquals(entries(p.assignmentRoot), Vector.empty, s"$clue: nothing was published")
    }
  }

  test("entry point: decides, groups and publishes; the verified decision reader is the input") {
    val p = synthetic("grouping-main")
    val (code, lines) = runMain(p)
    assertEquals(code, 0, lines.mkString("\n"))
    val published = GroupingExport.publishedRuns(p.assignmentRoot)
    assertEquals(published.size, 1)
    val run = GroupingExport.readValidated(published.head).fold(fail(_), identity)
    assertEquals(run.manifest, p.grouping)
    // Both verified input run IDs are recorded in the grouping run manifest.
    assertEquals(
      run.manifest.parameters(Grouping.Params.DecisionRunId),
      "run:" + p.decisionDir.getFileName.toString.stripPrefix("run=")
    )
    assertEquals(run.manifest.parameters(Grouping.Params.InputRunId), inputRunId)
    // Every curated researcher member and work has exactly one assignment.
    assertEquals(run.works.size, works.size)
    assertEquals(run.researchers.size, works.map(_.authors.size).sum + profiles.size)
    assert(
      run.researchers.forall(
        _.decisionRunId == run.manifest.parameters(Grouping.Params.DecisionRunId)
      )
    )
    assert(
      lines.exists(_.startsWith("settings groupingPolicy=er-grouping.v1 matchPolicy=er-policy.v8")),
      lines.mkString("\n")
    )
    assert(lines.exists(_.startsWith("unevaluated ST=0 ")), lines.mkString("\n"))
    assert(lines.filter(_.startsWith("unevaluated")).size >= 10)
  }

  test("entry point refusals publish nothing") {
    val p = synthetic("grouping-main-refusals")
    val other = p.base.resolve("elsewhere")
    val (c1, l1) = runMain(p, outputRoot = other)
    assertEquals(c1, 2)
    assert(l1.exists(_.contains("is not the configured entityResolution.assignmentRunRoot")), l1)
    assertEquals(entries(other), Vector.empty)
    val s3 = grouped(p.decisionRoot.toString, "s3://synthetic-curated-bucket/er/assignments")
    val (c2, l2) = runMain(p, loader = s3)
    assertEquals(c2, 2)
    assert(l2.exists(_.contains("is not an absolute local path")), l2)
    val (c3, l3) = runMain(p, loader = PolicyFixtures.configLoader())
    assertEquals(c3, 2)
    assert(l3.exists(_.contains("grouping configuration refused")), l3)
    val wrongRun = p.grouping.copy(parameters =
      p.grouping.parameters.updated(Grouping.Params.DecisionRunId, "run:" + "1" * 64)
    )
    val (c4, l4) = runMain(p, m = wrongRun)
    assertEquals(c4, 2)
    assert(l4.exists(_.contains("the verified decision run's ID")), l4)
    val v2 = grouped(p.decisionRoot.toString, p.assignmentRoot.toString, version = "er-grouping.v2")
    val (c5, l5) = runMain(p, loader = v2)
    assertEquals(c5, 2)
    assert(l5.exists(_.contains("differs from the grouping policy resource")), l5)
    // A decision run whose part no longer matches its _parts.json: refused by the verified reader.
    val part = p.decisionDir.resolve("decisions/researcher/part-00000.jsonl")
    val bytes = Files.readAllBytes(part)
    rewrite(part, bytes.take(bytes.indexOf('\n'.toByte) + 1))
    val (c6, l6) = runMain(p)
    assertEquals(c6, 2)
    assert(l6.exists(_.contains("decision run refused (verified reader)")), l6)
    assertEquals(entries(p.assignmentRoot), Vector.empty, "nothing was published")
    assertEquals(GroupingRunMain.run(Vector("one-arg"), _ => (), p.loader), 2)
  }

  test("ER-P6-07: grouping readers refuse _UNVERIFIED and .unverified- runs; staged failure") {
    val base = PolicyFixtures.workDir("grouping-unverified")
    val out = base.resolve("out")
    val dir = GroupingExport.publish(out, batch).fold(fail(_), identity)
    assert(GroupingExport.readValidated(dir).isRight)
    // An exact copy under the withdrawn name: refused by name, though its bytes verify.
    val copy = copyRun(dir, base.resolve("copy"))
    val hidden = copy.resolveSibling(DecisionExport.unverifiedName(dir.getFileName.toString + "-x"))
    Files.move(copy, hidden)
    assert(GroupingExport.readValidated(hidden).left.exists(_.contains("run=<hex>")))
    assertEquals(GroupingExport.publishedRuns(base.resolve("copy")), Vector.empty)
    // The marker refuses a run=<hex> directory.
    Files.writeString(dir.resolve(DecisionExport.UnverifiedMarker), "verification failed\n")
    Vector(GroupingExport.readPublished(dir), GroupingExport.readValidated(dir)).foreach(r =>
      assert(r.left.exists(_.contains(DecisionExport.UnverifiedMarker)), r)
    )
    assertEquals(GroupingExport.publishedRuns(out), Vector.empty)
    // A staged verification failure (bytes changed after _parts.json): no run=<hex>, preserved.
    val tamper: DecisionExport.FileWriter = (p, text) => {
      DecisionExport.DefaultFileWriter(p, text)
      if (p.getFileName.toString == DecisionExport.SuccessFile)
        Files.write(
          p.getParent.resolve(GroupingExport.TeamPart),
          "{}\n".getBytes(StandardCharsets.UTF_8),
          java.nio.file.StandardOpenOption.APPEND
        )
    }
    val root2 = base.resolve("out-2")
    GroupingExport.publishRun(root2, batch, tamper) match {
      case PublishResult.Unverified(p, why) =>
        assert(why.contains("does not match its _parts.json"), why)
        assertEquals((p.marked, p.moved), (true, true))
        assertEquals(
          entries(root2).filterNot(_.contains("/")),
          Vector(p.path.getFileName.toString)
        )
        assert(p.path.getFileName.toString.startsWith(".unverified-" + dir.getFileName + "-"))
        assertEquals(
          tree(p.path).keySet,
          (files ++ Vector(
            "_run-manifest.json",
            "_parts.json",
            "_SUCCESS",
            DecisionExport.UnverifiedMarker
          )).toSet
        )
      case other => fail(s"expected an unverified result: $other")
    }
    assertEquals(GroupingExport.publishedRuns(root2), Vector.empty)
  }

  // ---------------------------------------------------------------- 13. committed-sample probe

  test("13. committed-sample probe: decisions then grouping on the Hadoop local-pipeline output") {
    val root = PolicyFixtures.repoRoot
      .resolve("modules/hadoop/target/test-work/local-pipeline/curated-a")
    val runs = CuratedRunReader.runDirs(root)
    assume(
      runs.size == 1,
      s"expected one completed local-pipeline run under $root, found ${runs.size}"
    )
    val read = CuratedRunReader.read(runs.head)
    assume(read.isRight, read.left.getOrElse(""))
    val curated = read.toOption.get
    val manifestText = scala.util.Try(
      Files.readString(runs.head.resolve(DecisionExport.ManifestFile), StandardCharsets.UTF_8)
    )
    assume(manifestText.isSuccess, s"no readable run manifest in ${runs.head}")
    val input = Json
      .decode[RunManifest](manifestText.get.stripSuffix("\n"))
      .fold(e => fail(e.toString), identity)
    val base = PolicyFixtures.workDir("grouping-sample-probe")
    val p = prepare(base, runs.head, input, curated)
    val (code, lines) = runMain(p)
    assertEquals(code, 0, lines.mkString("\n"))
    val published = GroupingExport.publishedRuns(p.assignmentRoot)
    assertEquals(published.size, 1)
    val run = GroupingExport.readValidated(published.head).fold(fail(_), identity)
    assertEquals(run.works.size, curated.works.size)
    assertEquals(
      run.researchers.size,
      curated.works.map(_.authors.size).sum + curated.profiles.size
    )
    val P = "[er-grouping-probe]"
    val report =
      Vector(
        s"$P input ${runs.head.getFileName} decisions ${p.decisionDir.getFileName} " +
          s"published ${published.head.getFileName}",
        s"$P output ${published.head}"
      ) ++ lines
        .filter(l => l.startsWith("records ") || l.startsWith("links "))
        .map(l => s"$P unevaluated $l") ++
        lines.filter(_.startsWith("unevaluated")).map(l => s"$P $l")
    report.foreach(println)
    // Also kept next to the probe's own output (test-work, regenerated by every test run).
    Files.writeString(
      base.resolve("diagnostics.txt"),
      report.mkString("", "\n", "\n"),
      StandardCharsets.UTF_8
    )
    assert(lines.exists(_.startsWith("unevaluated ST=0 ")), lines.mkString("\n"))
  }
}
