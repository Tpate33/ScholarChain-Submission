package scholarchain.entityresolution.review

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import java.time.Instant

import scala.jdk.CollectionConverters.*

import scholarchain.core.*
import scholarchain.core.config.{ConfigLoader, GroupingRunSettings, ScholarChainConfig}
import scholarchain.entityresolution.{
  BlockingKeys,
  ConfigEvidence,
  EntityResolution,
  LoadedConfig,
  MatchFeatures,
  ResearcherEndpoint,
  Samples
}
import scholarchain.entityresolution.Samples.{em, ep}
import scholarchain.entityresolution.decisions.DecisionExport
import scholarchain.entityresolution.grouping.{
  Grouping,
  GroupingExport,
  GroupingFixtures,
  GroupingPolicy
}
import scholarchain.entityresolution.policy.{MatchPolicy, PolicyFixtures}

/**
 * ER-P6-06: the three committed inputs of `sbt erDecisionGroupingDev` (BE-P6-01) agree with each
 * other, with the committed Hadoop dev run manifest and with both entry points, and both entry
 * points read their published run back.
 *
 * Every expected value is computed here with the core and entity-resolution functions the entry
 * points use (config loader, `digestWithoutGrouping`, `GroupingRunSettings.configDigest`,
 * `RunId.of`, `DecisionExport.manifestProblems`, the entry points' own manifest readers), never
 * copied by hand. The run-root overlays are SYNTHETIC files under
 * modules/entity-resolution/target/test-work, written in the form the build generates. The curated
 * run of the end-to-end tests is SYNTHETIC (the sample-mirroring records of [[Samples]]); only its
 * `_run-manifest.json` is a byte copy of the committed Hadoop dev run manifest, so its run ID is
 * the dev run ID. No gold file, label, review queue, held-out file or real curated output is read.
 */
class DevRunInputsSuite extends munit.FunSuite {

  private val repo: Path = PolicyFixtures.repoRoot
  private val localRuns: Path = repo.resolve("modules/entity-resolution/local-runs")
  private val devConf: Path = localRuns.resolve("er-dev.conf")
  private val decisionFile: Path = localRuns.resolve("er-decisions.dev.run-manifest.json")
  private val groupingFile: Path = localRuns.resolve("er-grouping.dev.run-manifest.json")
  private val hadoopDev: Path =
    repo.resolve("modules/hadoop/local-runs/er-candidates.dev.run-manifest.json")

  /** The dev curated run ID as reported by BE-P6-01 and ER-P6-05 (verified below by RunId.of). */
  private val DevCuratedRunId =
    "run:2881724b5c86b561a235836d51946fbd884beed36a14f929b27c278cb5de8baa"

  private def ok[A](e: Either[?, A]): A = e.fold(err => fail(s"unexpected: $err"), identity)

  private def text(p: Path): String = Files.readString(p, StandardCharsets.UTF_8)

  /** A run-root overlay in the form the build writes (schema version and the two roots only). */
  private def overlay(dir: Path, decisions: Path, assignments: Path): Path = {
    Files.createDirectories(dir)
    val f = dir.resolve("er-run-roots.conf")
    Files.writeString(
      f,
      "# SYNTHETIC test overlay\n" +
        "configSchemaVersion = \"scholarchain.config.v2\"\n" +
        s"entityResolution.decisionRunRoot = \"$decisions\"\n" +
        s"entityResolution.assignmentRunRoot = \"$assignments\"\n",
      StandardCharsets.UTF_8
    )
    f
  }

  /** Loads the given files through core exactly as the entry points do (no other environment). */
  private def load(files: Path*): LoadedConfig =
    ConfigEvidence.load(
      Map(ConfigLoader.FilesEnvVar -> files.map(_.toAbsolutePath.toString).mkString(",")),
      ConfigLoader.readLocalFile
    )

  private def config(files: Path*): ScholarChainConfig =
    load(files*).result.fold(es => fail(es.map(_.message).mkString("; ")), identity)

  /** Two different SYNTHETIC roots overlays (BE-P6-01: the roots enter no run ID). */
  private lazy val overlays: Vector[Path] = {
    val base = PolicyFixtures.workDir("dev-run-inputs-overlays")
    Vector(
      overlay(base.resolve("a"), base.resolve("a/er-decisions"), base.resolve("a/er-assignments")),
      overlay(
        base.resolve("b"),
        base.resolve("b/elsewhere/decisions"),
        base.resolve("b/other/assignments")
      )
    )
  }

  private lazy val hadoopManifest: RunManifest =
    ok(Json.decode[RunManifest](text(hadoopDev)))
  private lazy val decisionManifest: RunManifest = ok(DecisionRunMain.readManifest(decisionFile))
  private lazy val groupingManifest: RunManifest = ok(GroupingRunMain.readManifest(groupingFile))

  // ---------------------------------------------------------------- er-dev.conf

  test("er-dev.conf: sets no run-root key, loads, and carries the accepted Phase 5 settings") {
    val raw = text(devConf)
    Vector("RunRoot", "RUN_ROOT", "runroot", "${", "include").foreach { s =>
      assert(!raw.contains(s), s"er-dev.conf contains '$s'")
    }
    val alone = config(devConf)
    val er = alone.entityResolution
    assertEquals(alone.configSchemaVersion, "scholarchain.config.v2")
    assertEquals(er.decisionRunRoot, None)
    assertEquals(er.assignmentRunRoot, None)
    assertEquals(er.policyVersion, "er-policy.v8")
    assertEquals(
      (er.acceptThreshold, er.rejectThreshold, er.llmBandLower, er.llmBandUpper),
      (0.85, 0.2, 0.4, 0.7)
    )
    assertEquals(er.promptVersion, "er-adjudication-prompt.v2")
    assertEquals(er.groupingPolicyVersion, Some("er-grouping.v1"))
    assertEquals(alone.graph.identityVersion, "v1")
    // The same values as the accepted policy file, and the effective policy resolves.
    val file = ok(MatchPolicy.loadDefault())
    assertEquals(
      (er.policyVersion, er.acceptThreshold, er.rejectThreshold, er.llmBandLower),
      (file.policyVersion, file.thresholds.accept, file.thresholds.reject, file.llmBand.lower)
    )
    assertEquals(
      (er.llmBandUpper, er.promptVersion),
      (file.llmBand.upper, file.adjudication.promptVersion)
    )
    val effective = ok(ReviewQueueMain.effectivePolicy(() => Right(alone)))
    assertEquals(effective.policyVersion, "er-policy.v8")
    assertEquals(
      er.groupingPolicyVersion,
      Some(ok(GroupingPolicy.loadDefault()).groupingPolicyVersion)
    )
    // Without the overlay the grouping settings are refused (both roots missing).
    val missing = GroupingRunSettings.from(alone)
    assert(missing.left.exists(_.exists(_.message.contains("decisionRunRoot"))), missing)
    assert(missing.left.exists(_.exists(_.message.contains("assignmentRunRoot"))), missing)
  }

  test("er-dev.conf with either roots overlay: only the two roots differ; both digests unchanged") {
    val alone = config(devConf)
    val loaded = overlays.map(o => o -> config(devConf, o))
    loaded.foreach { case (o, cfg) =>
      val roots = text(o).linesIterator.collect {
        case l if l.startsWith("entityResolution.") => l.split("\"")(1)
      }.toVector
      assertEquals(
        Vector(
          cfg.entityResolution.decisionRunRoot.get,
          cfg.entityResolution.assignmentRunRoot.get
        ),
        roots
      )
      assertEquals(
        cfg.copy(entityResolution =
          cfg.entityResolution.copy(decisionRunRoot = None, assignmentRunRoot = None)
        ),
        alone,
        "the overlay changes nothing but the two roots"
      )
      assertEquals(
        ScholarChainConfig.digestWithoutGrouping(cfg),
        ScholarChainConfig.digestWithoutGrouping(alone)
      )
    }
    val (a, b) = (loaded(0)._2, loaded(1)._2)
    assertNotEquals(a.entityResolution.decisionRunRoot, b.entityResolution.decisionRunRoot)
    assertNotEquals(a.entityResolution.assignmentRunRoot, b.entityResolution.assignmentRunRoot)
    assertEquals(ok(GroupingRunSettings.configDigest(a)), ok(GroupingRunSettings.configDigest(b)))
  }

  // ---------------------------------------------------------------- the Hadoop dev input

  test("inputRunId: RunId.of the committed Hadoop dev run manifest") {
    assertEquals(ok(RunId.of(hadoopManifest)), DevCuratedRunId)
    assertEquals(hadoopManifest.parameters.get("scholarchain.input.split"), Some("dev"))
  }

  // ---------------------------------------------------------------- decision manifest

  test("decision manifest: canonical bytes, and exactly what DecisionRunMain requires") {
    val m = decisionManifest
    val cfgs = overlays.map(o => config(devConf, o))
    val digests = cfgs.map(ScholarChainConfig.digestWithoutGrouping).distinct
    assertEquals(digests.size, 1, "the roots overlay does not change the decision digest")
    val inputRunId = ok(RunId.of(hadoopManifest))
    val expected = m.copy(
      configDigest = digests.head,
      inputSnapshots = hadoopManifest.inputSnapshots,
      parameters = m.parameters.updated(DecisionExport.Params.InputRunId, inputRunId)
    )
    // The whole file, compact and with one final LF (the failure message prints the right text).
    assertEquals(text(decisionFile), Json.write(expected) + "\n")
    assertEquals(m.stage, DecisionExport.Stage)
    assertEquals(m.attempt, 1)
    assertEquals(m.parameters.keySet, DecisionExport.Params.All.toSet)
    assertEquals(m.parameters(DecisionExport.Params.InputRunId), DevCuratedRunId)
    assertEquals(m.parameters(DecisionExport.Params.DecidedAt), "2026-11-15T00:00:00Z")
    // The entry point's own manifest check, under each overlay's effective policy.
    cfgs.foreach { cfg =>
      val effective = ok(ReviewQueueMain.effectivePolicy(() => Right(cfg)))
      assertEquals(
        DecisionExport.manifestProblems(
          m,
          effective,
          ScholarChainConfig.digestWithoutGrouping(cfg),
          hadoopManifest
        ),
        Vector.empty
      )
    }
  }

  // ---------------------------------------------------------------- grouping manifest

  test("grouping manifest: canonical bytes, the decision run ID chain, the grouping digest") {
    val g = groupingManifest
    val cfgs = overlays.map(o => config(devConf, o))
    val digests = cfgs.map(c => ok(GroupingRunSettings.configDigest(c))).distinct
    assertEquals(digests.size, 1, "the roots overlay does not change the grouping digest")
    val settings = ok(GroupingRunSettings.from(cfgs.head))
    val decisionRunId = ok(RunId.of(decisionManifest))
    val expected = g.copy(
      configDigest = digests.head,
      inputSnapshots = decisionManifest.inputSnapshots,
      parameters = g.parameters ++ Map(
        Grouping.Params.DecisionRunId -> decisionRunId,
        Grouping.Params.InputRunId -> ok(RunId.of(hadoopManifest)),
        Grouping.Params.GroupingPolicyVersion -> settings.groupingPolicyVersion,
        Grouping.Params.MatchPolicyVersion -> settings.matchPolicyVersion,
        Grouping.Params.IdentityVersion -> settings.identityVersion
      )
    )
    assertEquals(text(groupingFile), Json.write(expected) + "\n")
    assertEquals(g.stage, Grouping.Stage)
    assertEquals(g.attempt, 1)
    assertEquals(g.parameters.keySet, Grouping.Params.All.toSet)
    assertEquals(g.parameters(Grouping.Params.DecisionRunId), decisionRunId)
    assertEquals(g.parameters(Grouping.Params.InputRunId), DevCuratedRunId)
    assertEquals(
      g.parameters(Grouping.Params.GroupingPolicyVersion),
      ok(GroupingPolicy.loadDefault()).groupingPolicyVersion
    )
    assertEquals(Grouping.instantProblem(g.parameters(Grouping.Params.AssignedAt)), None)
    assert(
      !Instant
        .parse(g.parameters(Grouping.Params.AssignedAt))
        .isBefore(Instant.parse(decisionManifest.parameters(DecisionExport.Params.DecidedAt))),
      "assignedAt is not before decidedAt"
    )
    assertEquals(g.codeVersion, decisionManifest.codeVersion, "one asserted code version")
    assert(Canonical.isHex40(g.codeVersion))
  }

  // ---------------------------------------------------------------- end to end (SYNTHETIC data)

  private val devRunId: String = DevCuratedRunId

  private val candidateProvenance = Provenance(
    SourceType.Derived,
    "fixture",
    "dblp-2026-10-03",
    devRunId,
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
  private lazy val candidates = Vector(
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

  /**
   * A completed SYNTHETIC curated run in the Hadoop layout whose `_run-manifest.json` is a byte
   * copy of the committed Hadoop dev run manifest (so the run ID is the dev run ID).
   */
  private def writeCurated(base: Path): Path = {
    val run =
      base.resolve("curated/hadoop/snapshot=dblp-2026-10-03/" + DecisionExport.runDirName(devRunId))
    Files.createDirectories(run)
    Files.copy(hadoopDev, run.resolve(DecisionExport.ManifestFile))
    plain(
      run.resolve("normalize/works/part-r-00000"),
      works.map(w => Json.write[WorkRecord](GroupingFixtures.ofRun(w, devRunId)))
    )
    plain(
      run.resolve("normalize/profiles/part-r-00000"),
      profiles.map(p => Json.write[SourceAuthorProfile](GroupingFixtures.profileOfRun(p, devRunId)))
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

  private final case class Env(
      curated: Path,
      decisionRoot: Path,
      assignmentRoot: Path,
      source: () => LoadedConfig
  )

  /** The committed config plus a SYNTHETIC overlay naming this test's own roots. */
  private def env(name: String): Env = {
    val base = PolicyFixtures.workDir(name)
    val decisionRoot = base.resolve("er-decisions")
    val assignmentRoot = base.resolve("er-assignments")
    val o = overlay(base, decisionRoot, assignmentRoot)
    Env(writeCurated(base), decisionRoot, assignmentRoot, () => load(devConf, o))
  }

  private def decide(
      e: Env,
      writer: DecisionExport.FileWriter = DecisionExport.DefaultFileWriter
  ): (Int, Vector[String]) = {
    val lines = Vector.newBuilder[String]
    val code = DecisionRunMain.runWithConfig(
      Vector(decisionFile.toString, e.curated.toString, e.decisionRoot.toString),
      lines += _,
      e.source,
      writer
    )
    (code, lines.result())
  }

  private def group(
      e: Env,
      decisionDir: Path,
      writer: DecisionExport.FileWriter = DecisionExport.DefaultFileWriter
  ): (Int, Vector[String]) = {
    val lines = Vector.newBuilder[String]
    val code = GroupingRunMain.runWithConfig(
      Vector(
        groupingFile.toString,
        decisionDir.toString,
        e.curated.toString,
        e.assignmentRoot.toString
      ),
      lines += _,
      e.source,
      writer
    )
    (code, lines.result())
  }

  private def decided(e: Env): Path = {
    val (code, lines) = decide(e)
    assertEquals(code, 0, lines.mkString("\n"))
    e.decisionRoot.resolve(DecisionExport.runDirName(ok(RunId.of(decisionManifest))))
  }

  private def children(root: Path): Vector[String] =
    if (!Files.isDirectory(root)) Vector.empty
    else {
      val s = Files.list(root)
      try s.iterator.asScala.map(_.getFileName.toString).toVector.sorted
      finally s.close()
    }

  test("end to end: the committed files drive both entry points; both runs read back verified") {
    val e = env("dev-run-inputs-e2e")
    val decisionRunId = ok(RunId.of(decisionManifest))
    val (c1, l1) = decide(e)
    assertEquals(c1, 0, l1.mkString("\n"))
    val decisionDir = e.decisionRoot.resolve(DecisionExport.runDirName(decisionRunId))
    assertEquals(children(e.decisionRoot), Vector(decisionDir.getFileName.toString))
    assert(
      l1.exists(l =>
        l.startsWith(s"verified decision run=$decisionRunId ") &&
          l.contains("DecisionExport.readPublished")
      ),
      l1.mkString("\n")
    )
    val conf = devConf.toAbsolutePath.normalize
    val confHash = Canonical.sha256HexUtf8(text(devConf))
    assert(l1.contains(s"config file=$conf sha256=$confHash"), l1.mkString("\n"))
    val (dm, _) = ok(DecisionExport.readPublished(decisionDir))
    assertEquals(dm, decisionManifest)

    val groupingRunId = ok(RunId.of(groupingManifest))
    val (c2, l2) = group(e, decisionDir)
    assertEquals(c2, 0, l2.mkString("\n"))
    val groupingDir = e.assignmentRoot.resolve(DecisionExport.runDirName(groupingRunId))
    assertEquals(children(e.assignmentRoot), Vector(groupingDir.getFileName.toString))
    assert(
      l2.exists(l =>
        l.startsWith(s"verified grouping run=$groupingRunId ") &&
          l.contains("GroupingExport.readValidated")
      ),
      l2.mkString("\n")
    )
    val run = ok(GroupingExport.readValidated(groupingDir))
    assertEquals(run.manifest, groupingManifest)
    assertEquals(run.works.size, works.size)
    assert(run.researchers.forall(_.decisionRunId == decisionRunId))
    assert(l2.contains(s"config file=$conf sha256=$confHash"), l2.mkString("\n"))
  }

  // ---------------------------------------------------------------- integrity read-back

  /** Writes normally; on the last write (`_SUCCESS`) also appends a row to `rel` in staging. */
  private def tamperAfterParts(rel: String): DecisionExport.FileWriter = (p, t) => {
    DecisionExport.DefaultFileWriter(p, t)
    if (p.getFileName.toString == DecisionExport.SuccessFile)
      Files.write(
        p.getParent.resolve(rel),
        "{}\n".getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.APPEND
      )
  }

  /** Writes `rel` with its first row repeated (the bytes on disk differ from the batch). */
  private def repeatFirstRow(rel: String): DecisionExport.FileWriter = (p, t) =>
    if (p.toString.endsWith("/" + rel) && t.nonEmpty)
      DecisionExport.DefaultFileWriter(p, t + t.linesIterator.next() + "\n")
    else DecisionExport.DefaultFileWriter(p, t)

  // ---------------------------------------------------------------- ER-P6-07 exit-code contract

  /** Runs an entry point with separate stdout and stderr sinks and a post-rename hook. */
  private final case class Ran(code: Int, out: Vector[String], err: Vector[String])

  private def decide3(
      e: Env,
      writer: DecisionExport.FileWriter = DecisionExport.DefaultFileWriter,
      afterRename: Path => Unit = _ => ()
  ): Ran = {
    val out = Vector.newBuilder[String]
    val err = Vector.newBuilder[String]
    val code = DecisionRunMain.runWithConfig(
      Vector(decisionFile.toString, e.curated.toString, e.decisionRoot.toString),
      out += _,
      e.source,
      writer,
      err += _,
      afterRename
    )
    Ran(code, out.result(), err.result())
  }

  private def group3(
      e: Env,
      decisionDir: Path,
      writer: DecisionExport.FileWriter = DecisionExport.DefaultFileWriter,
      afterRename: Path => Unit = _ => ()
  ): Ran = {
    val out = Vector.newBuilder[String]
    val err = Vector.newBuilder[String]
    val code = GroupingRunMain.runWithConfig(
      Vector(
        groupingFile.toString,
        decisionDir.toString,
        e.curated.toString,
        e.assignmentRoot.toString
      ),
      out += _,
      e.source,
      writer,
      err += _,
      afterRename
    )
    Ran(code, out.result(), err.result())
  }

  /**
   * The exit-3 invariants: one stderr line naming the preserved directory, which is the only entry
   * under the root, named `.unverified-run=<hex>-<attempt>`, holds the marker, and is refused by
   * `read`; no `run=<hex>` exists.
   */
  private def assertWithdrawn(
      clue: String,
      r: Ran,
      root: Path,
      runId: String,
      why: String,
      read: Path => Either[String, ?],
      listed: Path => Vector[Path]
  ): Path = {
    val all = (r.out ++ r.err).mkString("\n")
    assertEquals(r.code, RunExit.Unverified, s"$clue: $all")
    assertEquals(r.err.size, 1, s"$clue: $all")
    val name = DecisionExport.runDirName(runId)
    val kids = children(root)
    assertEquals(kids.size, 1, s"$clue: $kids")
    assert(kids.head.startsWith(DecisionExport.UnverifiedPrefix + name + "-"), s"$clue: $kids")
    val preserved = root.resolve(kids.head)
    assertEquals(
      r.err.head,
      s"${RunExit.UnverifiedPrefix}verification failed; artifacts preserved under $preserved; " +
        "not accepted by readers",
      clue
    )
    assert(
      r.out.exists(l =>
        l.startsWith("error: ") && l.contains(why) && l.contains(preserved.toString)
      ),
      s"$clue: $all"
    )
    assert(!r.out.exists(_.startsWith("verified")), clue)
    assert(Files.isRegularFile(preserved.resolve(DecisionExport.UnverifiedMarker)), clue)
    assert(!Files.exists(root.resolve(name)), clue)
    assert(read(preserved).isLeft, clue)
    assertEquals(listed(root), Vector.empty, clue)
    preserved
  }

  test("ER-P6-07 decision: staged verification failure exits 3, no run=<hex>, preserved") {
    val part = DecisionExport.partPath(MatchEntityKind.Researcher)
    val runId = ok(RunId.of(decisionManifest))
    Vector(
      ("changed after _parts.json", tamperAfterParts(part), "does not match its _parts.json"),
      ("a repeated row", repeatFirstRow(part), "differs from the batch")
    ).zipWithIndex.foreach { case ((clue, writer, why), i) =>
      val e = env(s"dev-run-inputs-decision-staged-$i")
      val r = decide3(e, writer)
      val preserved = assertWithdrawn(
        clue,
        r,
        e.decisionRoot,
        runId,
        why,
        DecisionExport.readPublished,
        DecisionExport.publishedRuns
      )
      assert(r.out.exists(_.contains("staged verification failed")), clue)
      // Every staged file is preserved.
      Vector("_run-manifest.json", "_parts.json", "_SUCCESS", part).foreach(f =>
        assert(Files.isRegularFile(preserved.resolve(f)), s"$clue: $f")
      )
      // The grouping entry point cannot use it as its decision input.
      val g = group3(e, preserved)
      assertEquals(g.code, RunExit.Refused, (g.out ++ g.err).mkString("\n"))
      assert(g.out.exists(_.contains("decision run refused (verified reader)")), g.out)
      assertEquals(children(e.assignmentRoot), Vector.empty)
    }
  }

  test("ER-P6-07 decision: post-publication read-back failure exits 3; the run is withdrawn") {
    val e = env("dev-run-inputs-decision-post")
    val runId = ok(RunId.of(decisionManifest))
    val part = DecisionExport.partPath(MatchEntityKind.Researcher)
    var renamedTo: Option[Path] = None
    val r = decide3(
      e,
      afterRename = dir => {
        renamedTo = Some(dir)
        // The published run verifies at this point ...
        assert(DecisionExport.readPublished(dir).isRight)
        // ... and is then changed on disk before the read-back.
        Files.write(
          dir.resolve(part),
          "{}\n".getBytes(StandardCharsets.UTF_8),
          StandardOpenOption.APPEND
        )
      }
    )
    assertEquals(renamedTo, Some(e.decisionRoot.resolve(DecisionExport.runDirName(runId))))
    assertWithdrawn(
      "post-publication",
      r,
      e.decisionRoot,
      runId,
      "does not match its _parts.json",
      DecisionExport.readPublished,
      DecisionExport.publishedRuns
    )
    assert(r.out.exists(_.contains("integrity read-back failed")), r.out)
  }

  test("ER-P6-07 grouping: staged and post-publication verification failures exit 3") {
    val runId = ok(RunId.of(groupingManifest))
    Vector(
      (
        "staged: changed after _parts.json",
        tamperAfterParts(GroupingExport.WorkPart),
        (_: Path) => (),
        "does not match its _parts.json"
      ),
      (
        "staged: a repeated work assignment (core dataset validators)",
        repeatFirstRow(GroupingExport.WorkPart),
        (_: Path) => (),
        "is not a valid grouping run"
      ),
      (
        "post-publication: changed after the rename",
        DecisionExport.DefaultFileWriter,
        (dir: Path) => {
          Files.write(
            dir.resolve(GroupingExport.WorkPart),
            "{}\n".getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.APPEND
          )
          ()
        },
        "does not match its _parts.json"
      )
    ).zipWithIndex.foreach { case ((clue, writer, after, why), i) =>
      val e = env(s"dev-run-inputs-grouping-unverified-$i")
      val decisionDir = decided(e)
      val r = group3(e, decisionDir, writer, after)
      assertWithdrawn(
        clue,
        r,
        e.assignmentRoot,
        runId,
        why,
        GroupingExport.readValidated,
        GroupingExport.publishedRuns
      )
      // The decision run it read is untouched and still verified.
      assertEquals(DecisionExport.publishedRuns(e.decisionRoot), Vector(decisionDir), clue)
    }
  }

  test("ER-P6-07: a refusal still exits 2, prints nothing on stderr and publishes nothing") {
    val e = env("dev-run-inputs-refusal")
    // A refusal before anything is staged: the curated run directory does not exist.
    val missing = e.copy(curated = e.curated.resolveSibling("absent"))
    val r = decide3(missing)
    assertEquals(r.code, RunExit.Refused, r.out.mkString("\n"))
    assertEquals(r.err, Vector.empty)
    assert(r.out.exists(_.startsWith("error: ")), r.out)
    assertEquals(children(e.decisionRoot), Vector.empty)
    // An existing run is never overwritten: exit 2, the run untouched, no other entry.
    val decisionDir = decided(e)
    val again = decide3(e)
    assertEquals(again.code, RunExit.Refused, again.out.mkString("\n"))
    assertEquals(again.err, Vector.empty)
    assert(again.out.exists(_.contains("refusing to overwrite")), again.out)
    assertEquals(children(e.decisionRoot), Vector(decisionDir.getFileName.toString))
    // The grouping entry point given a decision run marked _UNVERIFIED: exit 2, nothing written.
    Files.writeString(decisionDir.resolve(DecisionExport.UnverifiedMarker), "verification failed\n")
    val g = group3(e, decisionDir)
    assertEquals(g.code, RunExit.Refused, g.out.mkString("\n"))
    assertEquals(g.err, Vector.empty)
    assert(
      g.out.exists(l =>
        l.contains("decision run refused (verified reader)") &&
          l.contains(DecisionExport.UnverifiedMarker)
      ),
      g.out
    )
    assertEquals(children(e.assignmentRoot), Vector.empty)
  }
}
