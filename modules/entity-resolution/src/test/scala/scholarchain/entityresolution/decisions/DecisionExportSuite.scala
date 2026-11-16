package scholarchain.entityresolution.decisions

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.immutable.SortedMap
import scala.jdk.CollectionConverters.*

import scholarchain.core.*
import scholarchain.core.config.{ConfigError, ScholarChainConfig}
import scholarchain.entityresolution.{
  BlockingKeys,
  EntityResolution,
  MatchFeatures,
  ResearcherEndpoint,
  Samples
}
import scholarchain.entityresolution.Samples.{em, ep}
import scholarchain.entityresolution.grouping.GroupingFixtures
import scholarchain.entityresolution.policy.{
  DecisionContext,
  EndpointIdentifiers,
  PolicyFixtures,
  Resolver
}
import scholarchain.entityresolution.review.{
  CuratedRunReader,
  DecisionRunMain,
  EndpointRef,
  ReviewQueue,
  ReviewQueueMain,
  RunExit,
  RunQueue,
  SamplingDesign,
  SyntheticUnits
}

/**
 * ER-P6-02 (human decision H9): the decision exporter. Every fixture is SYNTHETIC: a miniature
 * curated run in the Hadoop output layout built from the sample-mirroring records of [[Samples]]
 * with the real er-features.v2 functions, and an invented input run manifest. No gold file, label
 * or held-out output is read; no LLM is called.
 */
class DecisionExportSuite extends munit.FunSuite {
  import PolicyFixtures.ok

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
  private val inputRunId: String = ok(RunId.of(inputManifest))

  private def candidateProvenance(runId: String): Provenance = Provenance(
    SourceType.Derived,
    "fixture",
    "dblp-2026-10-03",
    runId,
    SchemaVersions.CandidateBlock,
    Some("hadoop-transform.v2"),
    None,
    None
  )

  private def rc(
      a: ResearcherEndpoint,
      b: ResearcherEndpoint,
      featureVersion: String = MatchFeatures.Version,
      runId: String = inputRunId
  ): MatchCandidate =
    ok(
      MatchCandidate.build(
        MatchEntityKind.Researcher,
        a.id,
        a.source,
        b.id,
        b.source,
        Vector(BlockKey(0, "fixture|x")),
        BlockingKeys.Version,
        featureVersion,
        MatchFeatures.researcher(a, b),
        EntityResolution.researcherExactIdAgreements(a, b),
        candidateProvenance(runId)
      )
    )

  private def wc(a: WorkRecord, b: WorkRecord): MatchCandidate =
    ok(
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
        candidateProvenance(inputRunId)
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

  // ER-P6-07 (CR-P6-08 minor 1): every work, mention and profile is a record of the curated run.
  private val works =
    Vector(spannerDblp, spannerCrossref, cacmDblp, cacmCrossref, bigtableDblp)
      .map(GroupingFixtures.ofRun(_, inputRunId))
  private val profiles = Vector(GroupingFixtures.profileOfRun(popperOpenAlex, inputRunId))
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
  private def writeRun(base: Path, cs: Vector[MatchCandidate] = candidates): Path = {
    val run = base.resolve(
      "curated/hadoop/snapshot=dblp-2026-10-03/" + DecisionExport.runDirName(inputRunId)
    )
    plain(run.resolve(DecisionExport.ManifestFile), Vector(Json.write(inputManifest)))
    plain(run.resolve("normalize/works/part-r-00000"), works.map(Json.write[WorkRecord]))
    plain(
      run.resolve("normalize/profiles/part-r-00000"),
      profiles.map(Json.write[SourceAuthorProfile])
    )
    plain(
      run.resolve("candidates/final/researcher/part-r-00000"),
      cs.filter(_.entityKind == MatchEntityKind.Researcher).map(Json.write[MatchCandidate])
    )
    plain(
      run.resolve("candidates/final/work/part-r-00000"),
      cs.filter(_.entityKind == MatchEntityKind.Work).map(Json.write[MatchCandidate])
    )
    plain(run.resolve("normalize/_SUCCESS"), Vector.empty)
    plain(run.resolve("candidates/_SUCCESS"), Vector.empty)
    run
  }

  private type Loader = () => Either[Vector[ConfigError], ScholarChainConfig]

  private val loader: Loader = PolicyFixtures.configLoader()

  /** The decision stage's config digest (F4: the grouping scope is left out). */
  private val digest: String =
    ScholarChainConfig.digestWithoutGrouping(
      loader().fold(e => throw new AssertionError(e.toString), identity)
    )
  private val effective =
    ReviewQueueMain.effectivePolicy(loader).fold(e => throw new AssertionError(e), identity)

  private val decidedAt = "2026-11-15T00:00:00Z"

  private def baseParams: Map[String, String] = Map(
    DecisionExport.Params.DecidedAt -> decidedAt,
    DecisionExport.Params.InputRunId -> inputRunId,
    DecisionExport.Params.PolicyVersion -> effective.policyVersion,
    DecisionExport.Params.BlockingVersion -> effective.blockingVersion,
    DecisionExport.Params.FeatureVersion -> effective.featureVersion
  )

  private def decisionManifest(
      attempt: Int = 1,
      configDigest: String = digest,
      set: Map[String, String] = Map.empty,
      drop: Set[String] = Set.empty,
      stage: String = DecisionExport.Stage,
      inputs: Vector[SnapshotRef] = snapshots
  ): RunManifest =
    RunManifest(
      SchemaVersions.RunManifest,
      stage,
      "c" * 40,
      configDigest,
      inputs,
      SortedMap.from(baseParams ++ set -- drop),
      attempt
    )

  private var manifestCount = 0

  private def runMain(
      base: Path,
      m: RunManifest,
      runDir: Path,
      out: Path,
      config: Loader = loader
  ): (Int, Vector[String]) = {
    manifestCount += 1
    val mf = base.resolve(s"manifests/decision-run-$manifestCount.json")
    plain(mf, Vector(Json.write(m)))
    val lines = Vector.newBuilder[String]
    val code =
      DecisionRunMain.run(Vector(mf.toString, runDir.toString, out.toString), lines += _, config)
    (code, lines.result())
  }

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

  private def fileLines(p: Path): Vector[String] =
    Files.readString(p, StandardCharsets.UTF_8).split('\n').toVector.filter(_.nonEmpty)

  test(
    "parity: one core MatchDecision per candidate, equal to toMatchDecision(decide(...)) and to the tooling"
  ) {
    val base = PolicyFixtures.workDir("decisions-parity")
    val run = writeRun(base)
    val out = base.resolve("out")
    val m = decisionManifest()
    val (code, lines) = runMain(base, m, run, out)
    assertEquals(code, 0, lines.mkString("\n"))
    val runId = ok(RunId.of(m))
    val published = DecisionExport.publishedRuns(out)
    assertEquals(published.map(_.getFileName.toString), Vector(DecisionExport.runDirName(runId)))
    val (pm, ds) = DecisionExport.readPublished(published.head).fold(e => fail(e), identity)
    assertEquals(pm, m, "the published manifest is the decision run manifest")
    // Exactly one decision per candidate.
    assertEquals(ds.size, candidates.size)
    assertEquals(ds.map(_.candidateId).sorted, candidates.map(_.candidateId).sorted)
    // Every line decodes with the core codec; files are sorted by candidate ID.
    Vector("researcher", "work").foreach { k =>
      val ls = fileLines(published.head.resolve(s"decisions/$k/part-00000.jsonl"))
      val decoded = ls.map(l => Json.decode[MatchDecision](l).fold(e => fail(e.toString), identity))
      assert(decoded.forall(_.entityKind.tag == k))
      assertEquals(
        decoded.map(_.candidateId),
        decoded.map(_.candidateId).sortWith(Canonical.compareUtf8(_, _) < 0)
      )
    }
    // Each decision equals Resolver.toMatchDecision(Resolver.decide(...)) under the effective policy.
    val index = EndpointIdentifiers.index(works, profiles)
    val ctx = DecisionContext(runId, decidedAt)
    val byCandidate = ds.map(d => d.candidateId -> d).toMap
    candidates.foreach { c =>
      val expected = Resolver
        .decide(effective, c, ok(EndpointIdentifiers.of(c, index)))
        .flatMap(Resolver.toMatchDecision(_, ctx))
        .fold(e => fail(e.mkString("; ")), identity)
      assertEquals(byCandidate(c.candidateId), expected, c.candidateId)
    }
    // And agrees with the existing tooling's SystemDecision for the same candidate.
    val curated = CuratedRunReader.read(run).fold(e => fail(e), identity)
    val units = SyntheticUnits.of(
      Vector(("syn-dev", "syn-criterion", "dev", works.map(_.workId) ++ profiles.map(_.profileId)))
    )
    val resolution = RunQueue
      .resolveDiagnostics(effective, curated, SamplingDesign.Default, units)
      .fold(e => fail(e.mkString("; ")), identity)
    assertEquals(resolution.refused, Vector.empty)
    assertEquals(
      resolution.outcomes.map(_.candidateId).sorted,
      candidates.map(_.candidateId).sorted
    )
    val refs = EndpointRef.index(curated.works, curated.profiles)
    resolution.outcomes.foreach { o =>
      val sys = ReviewQueue.row(effective, o, refs).fold(e => fail(e), identity).system
      val d = byCandidate(o.candidateId)
      assertEquals(d.decision.tag, sys.outcome, o.candidateId)
      assertEquals(d.matchPath.tag, sys.path, o.candidateId)
      assertEquals(d.score, Some(sys.score), o.candidateId)
      assertEquals(d.confidence, sys.confidence, o.candidateId)
      assertEquals(d.rulesFired, sys.rulesFired, o.candidateId)
      assertEquals(d.reasonCodes, sys.reasonCodes, o.candidateId)
      assertEquals(d.conflicts, IdentifierConflict.canonical(sys.conflicts), o.candidateId)
      assertEquals(d.provenance.modelVersion, sys.modelVersion)
      assertEquals(d.provenance.promptVersion, sys.promptVersion)
    }
    // Run provenance; no LLM.
    assert(ds.forall(_.decidedAt == decidedAt))
    assert(ds.forall(_.provenance.runId == runId))
    assert(ds.forall(_.provenance.policyVersion.contains(effective.policyVersion)))
    assert(ds.forall(d => d.blockingVersion == effective.blockingVersion))
    assert(ds.forall(d => d.adjudication.isEmpty && d.provenance.modelVersion.isEmpty))
    assert(
      ds.map(_.decision).distinct.size >= 2,
      s"fixture decides one outcome only: ${ds.map(_.decision)}"
    )
    assert(lines.contains(s"decisions researcher=5 work=3"), lines.mkString("\n"))
    assert(
      lines.exists(
        _.startsWith("settings policy=er-policy.v8 blocking=er-blocking.v2 features=er-features.v2")
      )
    )
  }

  test(
    "reproducibility: the same manifest and inputs give byte-identical output; a new attempt is a new run"
  ) {
    val base = PolicyFixtures.workDir("decisions-repro")
    val run = writeRun(base)
    val m = decisionManifest()
    val (c1, l1) = runMain(base, m, run, base.resolve("out-1"))
    val (c2, l2) = runMain(base, m, run, base.resolve("out-2"))
    assertEquals((c1, c2), (0, 0), (l1 ++ l2).mkString("\n"))
    val d1 = DecisionExport.publishedRuns(base.resolve("out-1"))
    val d2 = DecisionExport.publishedRuns(base.resolve("out-2"))
    assertEquals(d1.map(_.getFileName), d2.map(_.getFileName))
    val t1 = tree(d1.head)
    assertEquals(
      t1.keySet,
      Set(
        "_run-manifest.json",
        "_parts.json",
        "_SUCCESS",
        "decisions/researcher/part-00000.jsonl",
        "decisions/work/part-00000.jsonl"
      )
    )
    assertEquals(t1, tree(d2.head))
    // The batch itself is a pure function of its inputs.
    val again = () =>
      DecisionExport.decideAll(
        effective,
        works,
        profiles,
        candidates.reverse,
        inputManifest,
        m,
        digest
      )
    assertEquals(again(), again())
    assertEquals(
      again().map(_.all),
      DecisionExport
        .decideAll(effective, works, profiles, candidates, inputManifest, m, digest)
        .map(_.all)
    )
    // A deliberate re-run (attempt 2) is a different run with different decision IDs.
    val (c3, l3) = runMain(base, decisionManifest(attempt = 2), run, base.resolve("out-1"))
    assertEquals(c3, 0, l3.mkString("\n"))
    val both = DecisionExport.publishedRuns(base.resolve("out-1"))
    assertEquals(both.size, 2)
    assertEquals(tree(both.find(_ == d1.head).get), t1, "the first run is untouched")
  }

  test(
    "failure without partial publication: a refused candidate or an I/O failure publishes nothing"
  ) {
    val base = PolicyFixtures.workDir("decisions-failure")
    // A candidate whose endpoint record is not in the run cannot be decided: the run is refused.
    val orphan = rc(em(Samples.varajaoDblp, 0), em(cacmDblp, 0))
    val bad = writeRun(base.resolve("bad"), candidates :+ orphan)
    val out = base.resolve("out")
    val (code, lines) = runMain(base, decisionManifest(), bad, out)
    assertEquals(code, 2)
    assert(
      lines.exists(l => l.contains("nothing published") && l.contains(orphan.candidateId)),
      lines.toString
    )
    assertEquals(entries(out), Vector.empty, "nothing at all is written for a refused run")
    // An I/O failure in the middle of the staging writes: nothing is published, no staging is left.
    val m = decisionManifest()
    val batch = DecisionExport
      .decideAll(effective, works, profiles, candidates, inputManifest, m, digest)
      .fold(e => fail(e.mkString("; ")), identity)
    var written = Vector.empty[String]
    val failing: DecisionExport.FileWriter = (p, text) =>
      if (p.toString.endsWith("work/part-00000.jsonl")) throw new java.io.IOException("injected")
      else { DecisionExport.DefaultFileWriter(p, text); written :+= p.getFileName.toString }
    val failed = DecisionExport.publish(out, batch, failing)
    assert(failed.left.exists(_.contains("nothing published")), failed.toString)
    // G2 order: the researcher part is written first, so it alone existed when the work part failed.
    assertEquals(written, Vector("part-00000.jsonl"), "partial staging existed")
    assertEquals(DecisionExport.publishedRuns(out), Vector.empty)
    assertEquals(entries(out), Vector.empty, "the staging directory was removed")
    // What a crashed attempt can leave (a staging directory, even with _SUCCESS) and an incomplete
    // run directory are never read as published data.
    val hex = DecisionExport.runDirName(batch.runId)
    plain(out.resolve(s".staging-$hex-crash/_SUCCESS"), Vector.empty)
    plain(out.resolve(s".staging-$hex-crash/decisions/researcher/part-00000.jsonl"), Vector("{}"))
    Files.createDirectories(out.resolve(hex))
    assertEquals(DecisionExport.publishedRuns(out), Vector.empty)
    assert(DecisionExport.readPublished(out.resolve(s".staging-$hex-crash")).isLeft)
    assert(DecisionExport.readPublished(out.resolve(hex)).isLeft)
    // An existing (here: empty, incomplete) run directory is never replaced.
    assert(DecisionExport.publish(out, batch).left.exists(_.contains("refusing to overwrite")))
    assertEquals(entries(out.resolve(hex)), Vector.empty)
  }

  test("an existing published decision run is never overwritten") {
    val base = PolicyFixtures.workDir("decisions-no-overwrite")
    val run = writeRun(base)
    val out = base.resolve("out")
    val m = decisionManifest()
    assertEquals(runMain(base, m, run, out)._1, 0)
    val dir = DecisionExport.publishedRuns(out).head
    val before = tree(dir)
    val (code, lines) = runMain(base, m, run, out)
    assertEquals(code, 2)
    assert(lines.exists(_.contains("refusing to overwrite")), lines.toString)
    val batch = DecisionExport
      .decideAll(effective, works, profiles, candidates, inputManifest, m, digest)
      .fold(e => fail(e.mkString("; ")), identity)
    assert(DecisionExport.publish(out, batch).left.exists(_.contains("refusing to overwrite")))
    assertEquals(tree(dir), before)
    assertEquals(
      entries(out).filterNot(_.startsWith(dir.getFileName.toString + "/")),
      Vector(dir.getFileName.toString)
    )
  }

  test("committed-sample probe: the Hadoop local-pipeline output exports (skipped without it)") {
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
    val input = Json
      .decode[RunManifest](
        Files.readString(runs.head.resolve(DecisionExport.ManifestFile), StandardCharsets.UTF_8)
      )
      .fold(e => fail(e.toString), identity)
    val m = decisionManifest(
      set = Map(DecisionExport.Params.InputRunId -> ok(RunId.of(input))),
      inputs = input.inputSnapshots
    )
    val batch = DecisionExport
      .decideAll(effective, curated.works, curated.profiles, curated.candidates, input, m, digest)
      .fold(e => fail(e.take(5).mkString("; ")), identity)
    val out = PolicyFixtures.workDir("decisions-sample-probe")
    val dir = DecisionExport.publish(out, batch).fold(e => fail(e), identity)
    val (_, ds) = DecisionExport.readPublished(dir).fold(e => fail(e), identity)
    assertEquals(ds.size, curated.candidates.size)
    assertEquals(ds.map(_.candidateId).distinct.size, curated.candidates.size)
    println(s"[er-decisions-probe] input ${runs.head.getFileName} published ${dir.getFileName}")
    println(
      s"[er-decisions-probe] decisions researcher=${batch.researcher.size} work=${batch.work.size}"
    )
    ds.groupBy(d => (d.entityKind.tag, d.decision.tag, d.matchPath.tag))
      .toVector
      .sortBy(_._1)
      .foreach { case ((k, o, p), xs) =>
        println(s"[er-decisions-probe] count $k/$o/$p=${xs.size}")
      }
  }

  test("policy, configuration and manifest mismatches are refused and publish nothing") {
    val base = PolicyFixtures.workDir("decisions-mismatch")
    val run = writeRun(base)
    val P = DecisionExport.Params
    val cases: Vector[(String, RunManifest, Path, Loader, String)] = Vector(
      ("config digest", decisionManifest(configDigest = "d" * 64), run, loader, "configDigest"),
      (
        "other thresholds than the recorded digest",
        decisionManifest(),
        run,
        PolicyFixtures.configLoader(
          PolicyFixtures.configText(accept = 0.9, reject = 0.1, lower = 0.3, upper = 0.6)
        ),
        "configDigest"
      ),
      (
        "no configuration",
        decisionManifest(),
        run,
        PolicyFixtures.noConfig,
        "configuration refused"
      ),
      (
        "configured retired policy",
        decisionManifest(),
        run,
        PolicyFixtures.configLoader(PolicyFixtures.configText(policyVersion = "er-policy.v6")),
        "configuration refused"
      ),
      (
        "policy version",
        decisionManifest(set = Map(P.PolicyVersion -> "er-policy.v7")),
        run,
        loader,
        "parameter policyVersion"
      ),
      (
        "blocking version",
        decisionManifest(set = Map(P.BlockingVersion -> "er-blocking.v1")),
        run,
        loader,
        "parameter blockingVersion"
      ),
      (
        "feature version",
        decisionManifest(set = Map(P.FeatureVersion -> "er-features.v1")),
        run,
        loader,
        "parameter featureVersion"
      ),
      (
        "input run",
        decisionManifest(set = Map(P.InputRunId -> ("run:" + "3" * 64))),
        run,
        loader,
        "parameter inputRunId"
      ),
      ("stage", decisionManifest(stage = "er.other"), run, loader, "manifest stage"),
      (
        "unknown parameter",
        decisionManifest(set = Map("extra" -> "x")),
        run,
        loader,
        "unknown parameter 'extra'"
      ),
      (
        "missing decidedAt",
        decisionManifest(drop = Set(P.DecidedAt)),
        run,
        loader,
        "parameter 'decidedAt' is required"
      ),
      (
        "decidedAt offset",
        decisionManifest(set = Map(P.DecidedAt -> "2026-11-15T00:00:00+01:00")),
        run,
        loader,
        "decidedAt"
      ),
      (
        "decidedAt text",
        decisionManifest(set = Map(P.DecidedAt -> "yesterday")),
        run,
        loader,
        "not an ISO-8601 instant"
      ),
      (
        "input snapshots",
        decisionManifest(inputs = snapshots.take(2)),
        run,
        loader,
        "inputSnapshots"
      ),
      (
        "candidate of another feature version",
        decisionManifest(),
        writeRun(
          base.resolve("fv"),
          candidates :+ rc(em(cacmDblp, 1), em(cacmCrossref, 1), featureVersion = "er-features.v1")
        ),
        loader,
        "featureVersion er-features.v1 differs"
      ),
      (
        "candidate of another run",
        decisionManifest(),
        writeRun(
          base.resolve("foreign"),
          candidates :+ rc(em(cacmDblp, 2), em(cacmCrossref, 2), runId = "run:" + "4" * 64)
        ),
        loader,
        "provenance.runId is not the curated run"
      )
    )
    cases.zipWithIndex.foreach { case ((clue, m, runDir, config, expected), i) =>
      val out = base.resolve(s"out-$i")
      val (code, lines) = runMain(base, m, runDir, out, config)
      assertEquals(code, 2, s"$clue: ${lines.mkString("\n")}")
      assert(lines.exists(_.contains(expected)), s"$clue: ${lines.mkString("\n")}")
      assertEquals(entries(out), Vector.empty, clue)
    }
    assertEquals(DecisionRunMain.run(Vector("one-arg"), _ => (), loader), 2)
  }

  // ---- ER-P6-02 repair 01 (CR-P6-01 m1, m2, n1, n4) ----

  /** Publishes the SYNTHETIC batch of `cs` under a fresh `out` and returns the run directory. */
  private def publishedRun(base: Path, cs: Vector[MatchCandidate] = candidates): Path = {
    val batch = DecisionExport
      .decideAll(effective, works, profiles, cs, inputManifest, decisionManifest(), digest)
      .fold(e => fail(e.mkString("; ")), identity)
    DecisionExport.publish(base.resolve("out"), batch).fold(e => fail(e), identity)
  }

  private def part(dir: Path, kind: String): Path =
    dir.resolve(s"decisions/$kind/${DecisionExport.PartFile}")

  test("m1: an incomplete published run is refused by readPublished and never listed") {
    val cases: Vector[(String, Path => Unit, String)] = Vector(
      ("missing _SUCCESS", d => Files.delete(d.resolve(DecisionExport.SuccessFile)), "_SUCCESS"),
      (
        "missing manifest",
        d => Files.delete(d.resolve(DecisionExport.ManifestFile)),
        "no regular _run-manifest.json"
      ),
      (
        "undecodable manifest",
        d => {
          Files.delete(d.resolve(DecisionExport.ManifestFile))
          plain(d.resolve(DecisionExport.ManifestFile), Vector("{not json"))
        },
        "_run-manifest.json"
      ),
      (
        "missing researcher part",
        d => Files.delete(part(d, "researcher")),
        "researcher/part-00000.jsonl is missing or not a regular file"
      ),
      (
        "missing work part",
        d => Files.delete(part(d, "work")),
        "work/part-00000.jsonl is missing or not a regular file"
      ),
      (
        "work part is a directory",
        d => { Files.delete(part(d, "work")); Files.createDirectories(part(d, "work")) },
        "work/part-00000.jsonl is missing or not a regular file"
      ),
      (
        "work part is a symbolic link to a regular file",
        d => {
          val real = d.getParent.resolve("elsewhere.jsonl")
          Files.move(part(d, "work"), real)
          Files.createSymbolicLink(part(d, "work"), real)
        },
        "work/part-00000.jsonl is missing or not a regular file"
      )
    )
    cases.zipWithIndex.foreach { case ((clue, breakIt, expected), i) =>
      val dir = publishedRun(PolicyFixtures.workDir(s"decisions-incomplete-$i"))
      assert(DecisionExport.readPublished(dir).isRight, s"$clue: the intact run reads")
      breakIt(dir)
      val read = DecisionExport.readPublished(dir)
      assert(read.left.exists(_.contains(expected)), s"$clue: $read")
      assertEquals(DecisionExport.publishedRuns(dir.getParent), Vector.empty, clue)
    }
  }

  test("m1: a part file with zero rows is a complete run with no decisions of that kind") {
    val researcherOnly = candidates.filter(_.entityKind == MatchEntityKind.Researcher)
    val dir = publishedRun(PolicyFixtures.workDir("decisions-zero-rows"), researcherOnly)
    assertEquals(Files.size(part(dir, "work")), 0L)
    assertEquals(DecisionExport.publishedRuns(dir.getParent), Vector(dir))
    val (_, ds) = DecisionExport.readPublished(dir).fold(e => fail(e), identity)
    assertEquals(ds.map(_.candidateId).sorted, researcherOnly.map(_.candidateId).sorted)
    assert(ds.forall(_.entityKind == MatchEntityKind.Researcher))
  }

  test("m1: a complete run whose manifest stage is not er.decisions is refused and not listed") {
    val base = PolicyFixtures.workDir("decisions-wrong-stage")
    val m = decisionManifest(stage = "er.other")
    val runId = ok(RunId.of(m))
    // A complete layout under the directory its own run ID names: only the stage is wrong.
    val dir = base.resolve("out").resolve(DecisionExport.runDirName(runId))
    plain(dir.resolve(DecisionExport.ManifestFile), Vector(Json.write(m)))
    plain(part(dir, "researcher"), Vector.empty)
    plain(part(dir, "work"), Vector.empty)
    plain(dir.resolve(DecisionExport.SuccessFile), Vector.empty)
    // ER-P6-04: a consistent part manifest, so that only the stage is wrong.
    remanifest(dir)
    val read = DecisionExport.readPublished(dir)
    assert(read.left.exists(_.contains("stage is 'er.other', expected 'er.decisions'")), read)
    assertEquals(DecisionExport.publishedRuns(dir.getParent), Vector.empty)
  }

  test("n4: a symbolic link named run=<hex> is not a published run") {
    val base = PolicyFixtures.workDir("decisions-symlink")
    val dir = publishedRun(base)
    val other = Files.createDirectories(base.resolve("other-root"))
    val link = Files.createSymbolicLink(other.resolve(dir.getFileName.toString), dir)
    assert(Files.isDirectory(link), "the link resolves to a complete run")
    assertEquals(DecisionExport.publishedRuns(other), Vector.empty)
    val read = DecisionExport.readPublished(link)
    assert(read.left.exists(_.contains("symbolic links are not followed")), read)
    assertEquals(DecisionExport.publishedRuns(dir.getParent), Vector(dir), "the real run is listed")
  }

  test("n1: decisions of a kind other than researcher or work refuse the batch") {
    val batch = DecisionExport
      .decideAll(effective, works, profiles, candidates, inputManifest, decisionManifest(), digest)
      .fold(e => fail(e.mkString("; ")), identity)
    val all = batch.all
    assertEquals(
      DecisionExport.partition(batch.manifest, batch.runId, all).map(_.all.size),
      Right(all.size)
    )
    val venue = all.head.copy(entityKind = MatchEntityKind.Venue)
    val refused = DecisionExport.partition(batch.manifest, batch.runId, all :+ venue)
    assert(
      refused.left.exists(_.exists(_.contains(s"${venue.candidateId}:venue"))),
      refused.toString
    )
  }

  test("m2: the configured (non-default) thresholds decide, not the policy file's defaults") {
    val base = PolicyFixtures.workDir("decisions-thresholds")
    val run = writeRun(base)
    // Only the accept threshold differs from the defaults (0.85): the fixture's probabilistic
    // ACCEPTED researcher pair (p about 0.94 under er-policy.v8) falls below 0.95.
    val configured: Loader =
      PolicyFixtures.configLoader(PolicyFixtures.configText(accept = 0.95))
    val cfg = configured().fold(e => fail(e.toString), identity)
    val configuredDigest = ScholarChainConfig.digestWithoutGrouping(cfg)
    assertNotEquals(configuredDigest, digest)
    val cp = ReviewQueueMain.effectivePolicy(configured).fold(e => fail(e), identity)
    assertEquals(cp.thresholds.accept, 0.95)
    assertNotEquals(cp.thresholds.accept, effective.thresholds.accept)
    // The fixture must hold a candidate whose outcome depends on the thresholds.
    val index = EndpointIdentifiers.index(works, profiles)
    def outcomes(p: scholarchain.entityresolution.policy.MatchPolicy) =
      candidates
        .map(c => c.candidateId -> ok(Resolver.decide(p, c, ok(EndpointIdentifiers.of(c, index)))))
        .toMap
    val under = outcomes(cp)
    val default = outcomes(effective)
    val flipped =
      candidates.map(_.candidateId).filter(id => under(id).outcome != default(id).outcome)
    assert(
      flipped.nonEmpty,
      "no fixture candidate changes outcome: " +
        candidates
          .map { c =>
            val o = default(c.candidateId)
            s"${o.path}/${o.outcome}/p=${o.score.probability}"
          }
          .mkString(" | ")
    )
    assert(
      flipped.forall(id =>
        default(id).outcome == MatchOutcome.Accepted && under(id).outcome == MatchOutcome.Unresolved
      ),
      flipped.map(id => s"$id ${default(id).outcome} -> ${under(id).outcome}").mkString(", ")
    )
    val m = decisionManifest(configDigest = configuredDigest)
    val (code, lines) = runMain(base, m, run, base.resolve("out"), configured)
    assertEquals(code, 0, lines.mkString("\n"))
    assert(
      lines.exists(_.contains("accept=0.95 reject=0.2 llmBand=[0.4,0.7]")),
      lines.mkString("\n")
    )
    val dir = DecisionExport.publishedRuns(base.resolve("out")).head
    val (_, ds) = DecisionExport.readPublished(dir).fold(e => fail(e), identity)
    val byId = ds.map(d => d.candidateId -> d).toMap
    val ctx = DecisionContext(ok(RunId.of(m)), decidedAt)
    candidates.foreach { c =>
      val expected = ok(Resolver.toMatchDecision(under(c.candidateId), ctx))
      assertEquals(byId(c.candidateId), expected, c.candidateId)
    }
    flipped.foreach { id =>
      assertEquals(byId(id).decision, under(id).outcome, id)
      assertNotEquals(byId(id).decision, default(id).outcome, id)
    }
  }

  // ---- ER-P6-04: integrity manifest (decision package G1-G2) and F4 digest scope ----

  private val listedFiles: Vector[(String, String)] = Vector(
    "_run-manifest.json" -> SchemaVersions.RunManifest,
    "decisions/researcher/part-00000.jsonl" -> SchemaVersions.MatchDecision,
    "decisions/work/part-00000.jsonl" -> SchemaVersions.MatchDecision
  )

  /**
   * Rewrites `_parts.json` so that it is consistent with the files now on disk (built here from the
   * core contract, independently of the exporter): used to show that content rules hold even when
   * the integrity manifest matches.
   */
  private def remanifest(dir: Path, runId: Option[String] = None): Unit = {
    val id = runId.getOrElse("run:" + dir.getFileName.toString.stripPrefix("run="))
    val files = listedFiles.map { case (rel, schema) =>
      val o = ObservedFile.of(rel, Files.readAllBytes(dir.resolve(rel)))
      PartFile(rel, schema, o.rowCount, o.byteCount, o.sha256)
    }
    val pm = PartManifest.build(id, files).fold(e => fail(e.toString), identity)
    Files.deleteIfExists(dir.resolve(PartManifest.FileName))
    Files.writeString(dir.resolve(PartManifest.FileName), Json.write(pm) + "\n")
  }

  /** Copies the run directory `src` into `dstRoot`, keeping only the files `include` accepts. */
  private def copyRun(src: Path, dstRoot: Path, include: String => Boolean = _ => true): Path = {
    val dst = dstRoot.resolve(src.getFileName.toString)
    val s = Files.walk(src)
    try
      s.iterator.asScala.toVector.foreach { p =>
        val rel = src.relativize(p).toString
        if (Files.isDirectory(p)) Files.createDirectories(dst.resolve(rel))
        else if (include(rel)) {
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

  test("G2: publication writes parts, then the run manifest, then _parts.json, then _SUCCESS") {
    val base = PolicyFixtures.workDir("decisions-write-order")
    val batch = DecisionExport
      .decideAll(effective, works, profiles, candidates, inputManifest, decisionManifest(), digest)
      .fold(e => fail(e.mkString("; ")), identity)
    var order = Vector.empty[String]
    val recording: DecisionExport.FileWriter = (p, text) => {
      val names = p.iterator.asScala.map(_.toString).toVector
      val at = names.indexWhere(_.startsWith(DecisionExport.StagingPrefix))
      order :+= names.drop(at + 1).mkString("/")
      DecisionExport.DefaultFileWriter(p, text)
    }
    val dir = DecisionExport.publish(base.resolve("out"), batch, recording).fold(fail(_), identity)
    assertEquals(
      order,
      Vector(
        "decisions/researcher/part-00000.jsonl",
        "decisions/work/part-00000.jsonl",
        "_run-manifest.json",
        "_parts.json",
        "_SUCCESS"
      )
    )
    // _parts.json is one LF-terminated row: a core PartManifest of this run listing every file
    // except itself and _SUCCESS, with the counts and SHA-256 of the bytes on disk.
    val text = Files.readString(dir.resolve("_parts.json"), StandardCharsets.UTF_8)
    assert(text.endsWith("\n") && text.count(_ == '\n') == 1, text)
    val pm = Json.decode[PartManifest](text.stripSuffix("\n")).fold(e => fail(e.toString), identity)
    assertEquals(pm.runId, batch.runId)
    assertEquals(pm.files.map(f => f.path -> f.recordSchemaVersion), listedFiles)
    pm.files.foreach { f =>
      val o = ObservedFile.of(f.path, Files.readAllBytes(dir.resolve(f.path)))
      assertEquals((f.rowCount, f.byteCount, f.sha256), (o.rowCount, o.byteCount, o.sha256), f.path)
    }
    assertEquals(pm.files.find(_.path == "_run-manifest.json").map(_.rowCount), Some(1L))
    assertEquals(
      pm.files.find(_.path.contains("researcher")).map(_.rowCount),
      Some(batch.researcher.size.toLong)
    )
  }

  test("G2: an exact copy, including a zero-row part, is accepted") {
    val base = PolicyFixtures.workDir("decisions-exact-copy")
    val researcherOnly = candidates.filter(_.entityKind == MatchEntityKind.Researcher)
    val dir = publishedRun(base, researcherOnly)
    assertEquals(Files.size(part(dir, "work")), 0L)
    val copy = copyRun(dir, base.resolve("copy"))
    assertEquals(DecisionExport.publishedRuns(base.resolve("copy")), Vector(copy))
    assertEquals(DecisionExport.readPublished(copy), DecisionExport.readPublished(dir))
    assert(DecisionExport.readPublished(copy).isRight)
    val full = publishedRun(PolicyFixtures.workDir("decisions-exact-copy-full"))
    val fullCopy = copyRun(full, base.resolve("copy-full"))
    val (_, ds) = DecisionExport.readPublished(fullCopy).fold(fail(_), identity)
    assertEquals(ds.size, candidates.size)
  }

  test("G2: a cut-short, corrupted, partial or foreign copy is refused and never listed") {
    val researcher = "decisions/researcher/part-00000.jsonl"
    val cases: Vector[(String, Path => Unit, String)] = Vector(
      (
        "cut at a line boundary",
        d => {
          val b = Files.readAllBytes(d.resolve(researcher))
          rewrite(d.resolve(researcher), b.take(b.indexOf('\n'.toByte) + 1))
        },
        "rowCount: listed 5, observed 1"
      ),
      (
        "cut mid-line",
        d => {
          val b = Files.readAllBytes(d.resolve(researcher))
          rewrite(d.resolve(researcher), b.dropRight(10))
        },
        "a non-empty file must end in LF"
      ),
      (
        "same-length corruption",
        d => {
          val b = Files.readAllBytes(d.resolve(researcher))
          val i = b.indexWhere(c => c >= 'a' && c <= 'z', 20)
          b(i) = (if (b(i) == 'a') 'b' else 'a').toByte
          rewrite(d.resolve(researcher), b)
        },
        "sha256: listed"
      ),
      (
        "a missing part",
        d => Files.delete(part(d, "work")),
        "work/part-00000.jsonl is missing or not a regular file"
      ),
      (
        "an extra file in a part directory",
        d => Files.write(d.resolve("decisions/work/part-00001.jsonl"), Array.emptyByteArray),
        "extra file not listed in the part manifest"
      ),
      (
        "an extra file at the top",
        d => Files.write(d.resolve("notes.txt"), "x\n".getBytes(StandardCharsets.UTF_8)),
        "extra file not listed in the part manifest"
      ),
      (
        "an extra directory",
        d => Files.createDirectories(d.resolve("decisions/venue")),
        "unexpected directory decisions/venue"
      ),
      (
        "an extra symbolic link",
        d => Files.createSymbolicLink(d.resolve("link.jsonl"), d.resolve(researcher)),
        "neither a regular file nor a directory"
      ),
      (
        "_parts.json missing",
        d => Files.delete(d.resolve("_parts.json")),
        "no regular _parts.json"
      ),
      (
        "_parts.json undecodable",
        d => rewrite(d.resolve("_parts.json"), "{not json\n".getBytes(StandardCharsets.UTF_8)),
        "_parts.json: "
      ),
      (
        "wrong runId in _parts.json",
        d => remanifest(d, Some("run:" + "5" * 64)),
        "the directory holds run"
      ),
      (
        "consistent _parts.json, but a part without its final LF",
        d => {
          val b = Files.readAllBytes(d.resolve(researcher))
          rewrite(d.resolve(researcher), b.dropRight(1))
          remanifest(d)
        },
        "a non-empty file must end in LF"
      ),
      (
        "consistent _parts.json, but an empty row in a part",
        d => {
          val b = Files.readAllBytes(d.resolve(researcher))
          rewrite(d.resolve(researcher), b ++ "\n".getBytes(StandardCharsets.UTF_8))
          remanifest(d)
        },
        "row 6 is empty"
      ),
      (
        "consistent _parts.json listing a fourth file",
        d => {
          Files.write(d.resolve("notes.txt"), "x\n".getBytes(StandardCharsets.UTF_8))
          val files = (listedFiles :+ ("notes.txt" -> SchemaVersions.MatchDecision)).map {
            case (rel, schema) =>
              val o = ObservedFile.of(rel, Files.readAllBytes(d.resolve(rel)))
              PartFile(rel, schema, o.rowCount, o.byteCount, o.sha256)
          }
          val id = "run:" + d.getFileName.toString.stripPrefix("run=")
          val pm = PartManifest.build(id, files).fold(e => fail(e.toString), identity)
          rewrite(
            d.resolve("_parts.json"),
            (Json.write(pm) + "\n").getBytes(StandardCharsets.UTF_8)
          )
        },
        "a decision run lists"
      )
    )
    cases.zipWithIndex.foreach { case ((clue, breakIt, expected), i) =>
      val base = PolicyFixtures.workDir(s"decisions-integrity-$i")
      val dir = publishedRun(base)
      val copy = copyRun(dir, base.resolve("copy"))
      assert(DecisionExport.readPublished(copy).isRight, s"$clue: the exact copy reads")
      breakIt(copy)
      val read = DecisionExport.readPublished(copy)
      assert(read.left.exists(_.contains(expected)), s"$clue: $read")
      // publishedRuns verifies integrity but never decodes rows (readPublished does): a copy whose
      // integrity manifest matches but whose row is empty is listed, and refused when read.
      val intact = expected == "row 6 is empty"
      assertEquals(
        DecisionExport.publishedRuns(base.resolve("copy")),
        if (intact) Vector(copy) else Vector.empty,
        clue
      )
      assertEquals(DecisionExport.publishedRuns(base.resolve("out")), Vector(dir), clue)
    }
  }

  test("G2: a _SUCCESS-first copy (marker present, _parts.json or a part absent) is refused") {
    val base = PolicyFixtures.workDir("decisions-success-first")
    val dir = publishedRun(base)
    val stages: Vector[(String, Set[String], String)] = Vector(
      ("only _SUCCESS", Set("_SUCCESS"), "no regular _run-manifest.json"),
      (
        "_SUCCESS, manifest and one part",
        Set("_SUCCESS", "_run-manifest.json", "decisions/researcher/part-00000.jsonl"),
        "no regular _parts.json"
      ),
      (
        "_SUCCESS, manifest and both parts, no _parts.json",
        Set(
          "_SUCCESS",
          "_run-manifest.json",
          "decisions/researcher/part-00000.jsonl",
          "decisions/work/part-00000.jsonl"
        ),
        "no regular _parts.json"
      ),
      (
        "_SUCCESS, _parts.json and the manifest, no parts",
        Set("_SUCCESS", "_parts.json", "_run-manifest.json"),
        "researcher/part-00000.jsonl is missing or not a regular file"
      )
    )
    stages.zipWithIndex.foreach { case ((clue, files, expected), i) =>
      val root = base.resolve(s"partial-$i")
      val copy = copyRun(dir, root, files.contains)
      val read = DecisionExport.readPublished(copy)
      assert(read.left.exists(_.contains(expected)), s"$clue: $read")
      assertEquals(DecisionExport.publishedRuns(root), Vector.empty, clue)
    }
  }

  test("G2: a run published without _parts.json (before ER-P6-04) is refused") {
    val base = PolicyFixtures.workDir("decisions-legacy")
    val dir = publishedRun(base)
    // The pre-ER-P6-04 layout: the same files, without the integrity manifest.
    val legacy = copyRun(dir, base.resolve("legacy"), _ != "_parts.json")
    val read = DecisionExport.readPublished(legacy)
    assert(read.left.exists(_.contains("no regular _parts.json")), read)
    assertEquals(DecisionExport.publishedRuns(base.resolve("legacy")), Vector.empty)
  }

  test("F4: grouping-scope keys never change the decision run's configDigest or run ID") {
    val base = PolicyFixtures.workDir("decisions-digest-scope")
    val run = writeRun(base)
    def grouping(version: String, decisions: String, assignments: String): String =
      s"""entityResolution.groupingPolicyVersion = "$version"
         |entityResolution.decisionRunRoot = "$decisions"
         |entityResolution.assignmentRunRoot = "$assignments"
         |""".stripMargin
    val grouped: Loader = PolicyFixtures.configLoader(
      PolicyFixtures.configText() + grouping(
        "er-grouping.v1",
        "/var/tmp/scholarchain-synthetic/er/decisions",
        "/var/tmp/scholarchain-synthetic/er/assignments"
      )
    )
    val changed: Loader = PolicyFixtures.configLoader(
      PolicyFixtures.configText() + grouping(
        "er-grouping.v2",
        "s3://synthetic-curated-bucket/er/decisions",
        "/var/tmp/scholarchain-synthetic/elsewhere"
      )
    )
    val plainCfg = loader().fold(e => fail(e.toString), identity)
    val groupedCfg = grouped().fold(e => fail(e.toString), identity)
    val changedCfg = changed().fold(e => fail(e.toString), identity)
    assertEquals(groupedCfg.entityResolution.groupingPolicyVersion, Some("er-grouping.v1"))
    // Without grouping keys the decision digest is the full digest, as before F4.
    assertEquals(ScholarChainConfig.digest(plainCfg), digest)
    // The keys do change the full digest, but not the decision stage's.
    assertNotEquals(ScholarChainConfig.digest(groupedCfg), digest)
    assertEquals(ScholarChainConfig.digestWithoutGrouping(groupedCfg), digest)
    assertEquals(ScholarChainConfig.digestWithoutGrouping(changedCfg), digest)
    // DecisionRunMain accepts the unchanged manifest under each configuration and publishes the
    // same run, byte for byte.
    val m = decisionManifest()
    val trees = Vector("plain" -> loader, "grouped" -> grouped, "changed" -> changed).map {
      case (name, config) =>
        val out = base.resolve(s"out-$name")
        val (code, lines) = runMain(base, m, run, out, config)
        assertEquals(code, 0, s"$name: ${lines.mkString("\n")}")
        val dirs = DecisionExport.publishedRuns(out)
        assertEquals(
          dirs.map(_.getFileName.toString),
          Vector(DecisionExport.runDirName(ok(RunId.of(m))))
        )
        tree(dirs.head)
    }
    assertEquals(trees(1), trees(0))
    assertEquals(trees(2), trees(0))
    // A manifest that recorded the full digest of a grouped configuration is refused.
    val full = decisionManifest(configDigest = ScholarChainConfig.digest(groupedCfg))
    val (code, lines) = runMain(base, full, run, base.resolve("out-full"), grouped)
    assertEquals(code, 2, lines.mkString("\n"))
    assert(lines.exists(_.contains("configDigest")), lines.mkString("\n"))
  }

  // ---- ER-P6-05: CR-P6-06 m1 (the read-back of _parts.json is tested) ----

  test("CR-P6-06 m1: _parts.json records the bytes read back from disk, not the in-memory text") {
    val base = PolicyFixtures.workDir("decisions-read-back")
    val batch = DecisionExport
      .decideAll(effective, works, profiles, candidates, inputManifest, decisionManifest(), digest)
      .fold(e => fail(e.mkString("; ")), identity)
    val researcherPart = "decisions/researcher/part-00000.jsonl"
    // A writer whose bytes on disk differ from its argument: it appends one extra valid row (a
    // copy of the first decision) to the researcher part.
    val extra: DecisionExport.FileWriter = (p, text) =>
      if (p.toString.endsWith("/" + researcherPart))
        DecisionExport.DefaultFileWriter(p, text + text.linesIterator.next() + "\n")
      else DecisionExport.DefaultFileWriter(p, text)
    // ER-P6-07: the staged verification refuses to publish these bytes (they are not the batch),
    // and preserves them; _parts.json in the preserved directory is examined.
    val out = base.resolve("out")
    val dir = DecisionExport.publishRun(out, batch, extra) match {
      case PublishResult.Unverified(p, why) =>
        assert(why.contains("differs from the batch"), why)
        p.path
      case other => fail(s"expected an unverified result: $other")
    }
    assertEquals(dir.getFileName.toString.startsWith(".unverified-run="), true, dir.toString)
    val inMemory = DecisionExport
      .toJsonl(batch.researcher)
      .fold(e => fail(e.mkString("; ")), identity)
      .getBytes(StandardCharsets.UTF_8)
    val onDisk = Files.readAllBytes(dir.resolve(researcherPart))
    assertNotEquals(onDisk.toVector, inMemory.toVector, "the injected writer changed the bytes")
    val pm = Json
      .decode[PartManifest](
        Files.readString(dir.resolve("_parts.json"), StandardCharsets.UTF_8).stripSuffix("\n")
      )
      .fold(e => fail(e.toString), identity)
    val listed = pm.files.find(_.path == researcherPart).get
    val disk = ObservedFile.of(researcherPart, onDisk)
    val memory = ObservedFile.of(researcherPart, inMemory)
    assertEquals(
      (listed.rowCount, listed.byteCount, listed.sha256),
      (disk.rowCount, disk.byteCount, disk.sha256)
    )
    assertEquals(listed.rowCount, batch.researcher.size + 1L)
    // Hashing the in-memory text instead would have recorded these values, which differ.
    assertNotEquals(listed.rowCount, memory.rowCount)
    assertNotEquals(listed.byteCount, memory.byteCount)
    assertNotEquals(listed.sha256, memory.sha256)
    // The integrity manifest matched the bytes on disk (the staged reader got past _parts.json and
    // decoded every row; only the comparison with the batch failed), and nothing is published.
    assertEquals(DecisionExport.publishedRuns(out), Vector.empty)
    assert(!Files.exists(out.resolve(DecisionExport.runDirName(batch.runId))))
  }

  // ---- ER-P6-07: CR-P6-08 minor 1 (provenance of works, mentions and profiles) ----

  private val otherRun = "run:" + "6" * 64

  /** Each curated record kind of another run, alone; the rest is of the curated run. */
  private val mixed: Vector[(String, Vector[WorkRecord], Vector[SourceAuthorProfile], String)] =
    Vector(
      (
        "works",
        works.map(w => w.copy(provenance = w.provenance.copy(runId = otherRun))),
        profiles,
        s"work ${works.head.workId}: provenance.runId is not the curated run $inputRunId"
      ),
      (
        "mentions",
        works.map(w =>
          w.copy(authors =
            w.authors.map(a => a.copy(provenance = a.provenance.copy(runId = otherRun)))
          )
        ),
        profiles,
        s"mention ${works.head.authors.head.mentionId}: provenance.runId is not the curated run " +
          inputRunId
      ),
      (
        "profiles",
        works,
        profiles.map(p => p.copy(provenance = p.provenance.copy(runId = otherRun))),
        s"profile ${profiles.head.profileId}: provenance.runId is not the curated run $inputRunId"
      )
    )

  test("CR-P6-08 minor 1: decideAll refuses works, mentions or profiles of another run") {
    // The fixture of the curated run decides.
    assert(
      DecisionExport
        .decideAll(
          effective,
          works,
          profiles,
          candidates,
          inputManifest,
          decisionManifest(),
          digest
        )
        .isRight
    )
    mixed.foreach { case (clue, ws, ps, expected) =>
      val refused = DecisionExport
        .decideAll(effective, ws, ps, candidates, inputManifest, decisionManifest(), digest)
      assert(refused.left.exists(_.contains(expected)), s"$clue: $refused")
      // Only the foreign kind is reported.
      val prefix = clue match {
        case "works" => "work "
        case "mentions" => "mention "
        case _ => "profile "
      }
      assert(refused.left.exists(_.forall(_.startsWith(prefix))), s"$clue: $refused")
    }
  }

  test("CR-P6-08 minor 1 (entry point): a curated run with another run's data exits 2") {
    mixed.zipWithIndex.foreach { case ((clue, ws, ps, expected), i) =>
      val base = PolicyFixtures.workDir(s"decisions-mixed-$i")
      val run = writeRun(base)
      plain(run.resolve("normalize/works/part-r-00000"), ws.map(Json.write[WorkRecord]))
      plain(run.resolve("normalize/profiles/part-r-00000"), ps.map(Json.write[SourceAuthorProfile]))
      val out = base.resolve("out")
      val (code, lines) = runMain(base, decisionManifest(), run, out)
      assertEquals(code, RunExit.Refused, s"$clue: ${lines.mkString("\n")}")
      assert(!lines.exists(_.startsWith(RunExit.UnverifiedPrefix)), clue)
      assert(
        lines.exists(l =>
          l.startsWith("error: decision run refused, nothing published") && l.contains(expected)
        ),
        s"$clue: ${lines.mkString("\n")}"
      )
      assertEquals(entries(out), Vector.empty, s"$clue: nothing at all is written")
    }
  }

  // ---- ER-P6-07: staged verification, withdrawal, readers ----

  private def batchOf(cs: Vector[MatchCandidate] = candidates): DecisionBatch =
    DecisionExport
      .decideAll(effective, works, profiles, cs, inputManifest, decisionManifest(), digest)
      .fold(e => fail(e.mkString("; ")), identity)

  /** Writes `rel` with its first row repeated (the bytes on disk are not the batch). */
  private def repeatFirstRow(rel: String): DecisionExport.FileWriter = (p, t) =>
    if (p.toString.endsWith("/" + rel) && t.nonEmpty)
      DecisionExport.DefaultFileWriter(p, t + t.linesIterator.next() + "\n")
    else DecisionExport.DefaultFileWriter(p, t)

  /** Writes normally; on `_SUCCESS` also appends a row to `rel` (after `_parts.json`). */
  private def appendAfterParts(rel: String): DecisionExport.FileWriter = (p, t) => {
    DecisionExport.DefaultFileWriter(p, t)
    if (p.getFileName.toString == DecisionExport.SuccessFile)
      Files.write(
        p.getParent.resolve(rel),
        "{}\n".getBytes(StandardCharsets.UTF_8),
        java.nio.file.StandardOpenOption.APPEND
      )
  }

  test("ER-P6-07: staged verification failure creates no run=<hex> and preserves every file") {
    val researcherPart = DecisionExport.partPath(MatchEntityKind.Researcher)
    Vector(
      ("rows not the batch", repeatFirstRow(researcherPart), "differs from the batch"),
      ("bytes changed after _parts.json", appendAfterParts(researcherPart), "does not match")
    ).zipWithIndex.foreach { case ((clue, writer, why), i) =>
      val base = PolicyFixtures.workDir(s"decisions-staged-$i")
      val out = base.resolve("out")
      val batch = batchOf()
      val result = DecisionExport.publishRun(out, batch, writer)
      val p = result match {
        case PublishResult.Unverified(p, reason) =>
          assert(reason.contains(why), s"$clue: $reason")
          assert(reason.contains(s"artifacts preserved under ${p.path}"), s"$clue: $reason")
          p
        case other => fail(s"$clue: $other")
      }
      assertEquals((p.marked, p.moved, p.rejectedByReaders), (true, true, true), clue)
      val name = DecisionExport.runDirName(batch.runId)
      // Exactly one entry: .unverified-run=<hex>-<attempt>; no run=<hex>, no .staging- left.
      val top = entries(out).filterNot(_.contains("/"))
      assertEquals(top.size, 1, s"$clue: $top")
      assert(top.head.startsWith(DecisionExport.UnverifiedPrefix + name + "-"), s"$clue: $top")
      assertEquals(p.path.getFileName.toString, top.head, clue)
      // Every staged file is preserved, plus the marker (one line with the reason).
      assertEquals(
        tree(p.path).keySet,
        Set(
          "_run-manifest.json",
          "_parts.json",
          "_SUCCESS",
          DecisionExport.UnverifiedMarker,
          "decisions/researcher/part-00000.jsonl",
          "decisions/work/part-00000.jsonl"
        ),
        clue
      )
      val marker = Files.readString(p.path.resolve(DecisionExport.UnverifiedMarker))
      assert(marker.endsWith("\n") && marker.count(_ == '\n') == 1 && marker.contains(why), marker)
      // No reader accepts it.
      assertEquals(DecisionExport.publishedRuns(out), Vector.empty, clue)
      assert(DecisionExport.readPublished(p.path).isLeft, clue)
      assert(!Files.exists(out.resolve(name)), clue)
      // The Either form reports the same failure.
      val again = DecisionExport.publish(base.resolve("out-again"), batch, writer)
      assert(again.left.exists(_.contains("staged verification failed")), s"$clue: $again")
    }
  }

  test("ER-P6-07: readers refuse a run holding _UNVERIFIED and the .unverified- name") {
    val base = PolicyFixtures.workDir("decisions-marked")
    val dir = publishedRun(base)
    assert(DecisionExport.readPublished(dir).isRight)
    // An exact copy under the withdrawn name is refused (name), though its bytes verify.
    val renamed = copyRun(dir, base.resolve("renamed"))
    val hidden =
      renamed.resolveSibling(DecisionExport.unverifiedName(dir.getFileName.toString + "-x"))
    Files.move(renamed, hidden)
    assert(DecisionExport.readPublished(hidden).left.exists(_.contains("run=<hex>")))
    assertEquals(DecisionExport.publishedRuns(base.resolve("renamed")), Vector.empty)
    // The marker refuses a run=<hex> directory whatever else it holds.
    Files.writeString(dir.resolve(DecisionExport.UnverifiedMarker), "verification failed\n")
    val read = DecisionExport.readPublished(dir)
    assert(read.left.exists(_.contains(DecisionExport.UnverifiedMarker)), read)
    assertEquals(DecisionExport.publishedRuns(dir.getParent), Vector.empty)
  }

  test("ER-P6-07: withdraw deletes nothing; a failed rename leaves the run marked and refused") {
    val base = PolicyFixtures.workDir("decisions-withdraw")
    val dir = publishedRun(base)
    val before = tree(dir)
    val attempt = dir.getFileName.toString + "-synthetic"
    val failingMove: DecisionExport.Mover = (_, _) => throw new java.io.IOException("injected")
    val p = DecisionExport.withdraw(dir, attempt, "synthetic reason\nsecond line", failingMove)
    assertEquals(p, Preserved(dir, marked = true, moved = false))
    assert(p.rejectedByReaders)
    assertEquals(tree(dir) - DecisionExport.UnverifiedMarker, before, "nothing deleted or changed")
    assertEquals(
      Files.readString(dir.resolve(DecisionExport.UnverifiedMarker)),
      "verification failed; never accepted by readers: synthetic reason second line\n"
    )
    assert(DecisionExport.readPublished(dir).isLeft)
    assertEquals(DecisionExport.publishedRuns(dir.getParent), Vector.empty)
    // The normal path: marker, then rename to .unverified-<attempt>.
    val dir2 = publishedRun(PolicyFixtures.workDir("decisions-withdraw-2"))
    val before2 = tree(dir2)
    val p2 = DecisionExport.withdraw(dir2, attempt, "synthetic reason")
    assertEquals(p2.moved, true)
    assertEquals(p2.path, dir2.resolveSibling(DecisionExport.UnverifiedPrefix + attempt))
    assert(!Files.exists(dir2))
    assertEquals(tree(p2.path) - DecisionExport.UnverifiedMarker, before2)
    assertEquals(DecisionExport.publishedRuns(dir2.getParent), Vector.empty)
    // Neither marked nor moved: a run=<hex> that a reader may accept is reported as such.
    val unmarked = Preserved(dir2, marked = false, moved = false)
    assert(!unmarked.rejectedByReaders)
    assert(DecisionExport.unverifiedText(unmarked).contains("a reader may accept it"))
  }
}
