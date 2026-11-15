package scholarchain.hadoop.local

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path as JPath}

import scholarchain.core.{Json, RunId, RunManifest, SnapshotRef, SourceType}
import scholarchain.entityresolution.EntityResolution
import scholarchain.hadoop.{JobParams, TestSupport}

/**
 * The per-split entry point (work order HD-P5-02). Held-out SAMPLES are never read here: the
 * held-out manifest is checked only against the committed ledgers (PROVENANCE.tsv and
 * DBLP-RELEASE-RANGES.tsv), and the only pipeline run is on dev.
 */
class ErSplitPipelineSuite extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(15, "min")

  val repo: JPath = TestSupport.repoRoot

  /** Printed lines are also appended to target/test-work/er-split-report.txt (git-ignored). */
  val reportFile: JPath = repo.resolve("modules/hadoop/target/test-work/er-split-report.txt")
  override def beforeAll(): Unit = Files.deleteIfExists(reportFile): Unit

  def note(s: String): Unit = {
    println(s)
    Files.createDirectories(reportFile.getParent)
    Files.write(
      reportFile,
      (s + "\n").getBytes(StandardCharsets.UTF_8),
      java.nio.file.StandardOpenOption.CREATE,
      java.nio.file.StandardOpenOption.APPEND
    )
  }

  def capture(f: PrintStream => Int): (Int, Vector[String]) = {
    val bytes = new ByteArrayOutputStream()
    val ps = new PrintStream(bytes, true, StandardCharsets.UTF_8)
    val code = f(ps)
    ps.flush()
    (code, new String(bytes.toByteArray, StandardCharsets.UTF_8).split("\n").toVector)
  }

  def envAt(out: JPath, manifest: String => JPath = s => repo.resolve(ErSplit.manifestPath(s))) =
    ErSplitEnv(repo, s => out.resolve(s), manifest)

  def manifest(split: String): RunManifest =
    Json
      .decode[RunManifest](
        new String(
          Files.readAllBytes(repo.resolve(ErSplit.manifestPath(split))),
          StandardCharsets.UTF_8
        )
      )
      .fold(e => fail(e.map(_.message).mkString("; ")), identity)

  lazy val ledger: Vector[LedgerRow] = Ledger.read(repo).fold(e => fail(e), identity)

  lazy val tokens: ErSnapshotTokens =
    DatasetManifest.erSnapshots(repo).fold(e => fail(e), identity)

  def writeManifest(p: JPath, m: RunManifest): JPath = {
    Files.write(p, Json.write(m).getBytes(StandardCharsets.UTF_8))
    p
  }

  def withSnapshot(m: RunManifest, s: SourceType, token: Option[String]): RunManifest =
    m.copy(inputSnapshots =
      m.inputSnapshots.filterNot(_.source == s) ++ token.map(SnapshotRef(s, _)).toVector
    )

  test("dataset manifest reader: nested paths, quoted strings, list items, fail-closed errors") {
    val yaml = Vector(
      "# comment",
      "top: \"a \\\"q\\\" b\"",
      "datasets:",
      "  x:",
      "    snapshot:",
      "      id: \"x-1\"",
      "      status: bare-value # trailing comment",
      "    list:",
      "      - name: shadow",
      "        snapshot:",
      "          id: \"never-read\"",
      "      - \"item\"",
      "  y:",
      "    snapshot:",
      "      id: y-2",
      ""
    ).mkString("\n")
    val m = DatasetManifest.parse(yaml).fold(e => fail(e), identity)
    assertEquals(m.get("top"), Some("a \"q\" b"))
    assertEquals(m.get("datasets.x.snapshot.id"), Some("x-1"))
    assertEquals(m.get("datasets.x.snapshot.status"), Some("bare-value"))
    assertEquals(m.get("datasets.y.snapshot.id"), Some("y-2"))
    assert(!m.keys.exists(_.contains("never-read")) && !m.values.exists(_ == "never-read"))
    assert(!m.keys.exists(_.startsWith("datasets.x.list.")), m.keys.toString)
    assert(DatasetManifest.parse("a:\n  b: \"1\"\n  b: \"2\"\n").left.exists(_.contains("twice")))
    assert(DatasetManifest.parse("a: \"open\n").left.exists(_.contains("unterminated")))
    assert(DatasetManifest.parse("a:\n  just text\n").left.exists(_.contains("not a")))
    assert(DatasetManifest.parse("a: \"x\" y\n").left.exists(_.contains("text after")))
  }

  test("ER snapshot tokens: API tokens required, never equal to the bulk snapshot IDs") {
    val good = Map(
      "datasets.dblp.snapshot.id" -> "dblp-1",
      "datasets.openalex.snapshot.id" -> "openalex-bulk",
      "datasets.crossref.snapshot.id" -> "crossref-bulk",
      "er_candidates.api_snapshots.openalex.source" -> "openalex",
      "er_candidates.api_snapshots.openalex.snapshot_id" -> "openalex-api-1",
      "er_candidates.api_snapshots.crossref.source" -> "crossref",
      "er_candidates.api_snapshots.crossref.snapshot_id" -> "crossref-api-1"
    )
    val t = DatasetManifest.erSnapshots(good).fold(e => fail(e), identity)
    assertEquals(
      t.api,
      Map(SourceType.OpenAlex -> "openalex-api-1", SourceType.Crossref -> "crossref-api-1")
    )
    assertEquals(t.dblp, "dblp-1")
    def bad(m: Map[String, String], why: String) = {
      val r = DatasetManifest.erSnapshots(m)
      assert(r.left.exists(_.contains(why)), s"$why: $r")
    }
    bad(good - "er_candidates.api_snapshots.crossref.snapshot_id", "has no er_candidates")
    bad(good - "datasets.dblp.snapshot.id", "has no datasets.dblp.snapshot.id")
    bad(
      good + ("er_candidates.api_snapshots.openalex.snapshot_id" -> "openalex-bulk"),
      "equals the bulk snapshot ID"
    )
    bad(
      good + ("er_candidates.api_snapshots.crossref.source" -> "openalex"),
      "expected crossref"
    )
    bad(good + ("er_candidates.api_snapshots.openalex.snapshot_id" -> "bad token"), "not a")
  }

  test("committed dataset manifest: the D13 option D tokens (hand-checkable)") {
    note(
      s"[er-split-tokens] dblp=${tokens.dblp} " +
        DatasetManifest.ApiSources
          .map(s => s"${s.tag}.api=${tokens.api(s)} ${s.tag}.bulk=${tokens.bulk.getOrElse(s, "-")}")
          .mkString(" ")
    )
    assertEquals(tokens.dblp, "dblp-2026-10-03")
    assertEquals(tokens.api(SourceType.OpenAlex), "openalex-api-2026-11-05")
    assertEquals(tokens.api(SourceType.Crossref), "crossref-api-2026-11-05")
    assertEquals(tokens.bulk(SourceType.OpenAlex), "openalex-2026-10-14")
    assertEquals(tokens.bulk(SourceType.Crossref), "crossref-public-data-file-2026-03")
  }

  test("snapshot guard: a bulk, wrong or missing OpenAlex/Crossref snapshot is refused (exit 2)") {
    val work = TestSupport.workDir("er-split-snapshot-guard")
    val dev = manifest("dev")
    DatasetManifest.ApiSources.foreach { s =>
      val cases = Vector(
        "bulk" -> Some(tokens.bulk(s)),
        "wrong" -> Some(s"${s.tag}-api-2026-11-06"),
        "absent" -> None
      )
      cases.foreach { case (label, token) =>
        val f = writeManifest(work.resolve(s"${s.tag}-$label.json"), withSnapshot(dev, s, token))
        val out = work.resolve(s"out-${s.tag}-$label")
        val (code, lines) = capture(ErSplitPipeline.run(Vector("dev"), envAt(out, _ => f), _))
        assertEquals(code, 2, s"${s.tag} $label: ${lines.mkString("\n")}")
        val refusal = lines.find(_.contains("status=refused")).getOrElse(fail(lines.mkString))
        assert(
          refusal.contains(s"${s.tag}_snapshot_is_${token.getOrElse("absent")},_but"),
          refusal
        )
        if (label == "bulk") assert(refusal.contains("is_the_bulk_snapshot_ID"), refusal)
        assert(!Files.exists(out), "refused before any output directory is created")
      }
    }
    // the DBLP snapshot must be datasets.dblp.snapshot.id
    val f = writeManifest(
      work.resolve("dblp-wrong.json"),
      withSnapshot(dev, SourceType.Dblp, Some("dblp-2026-09-01"))
    )
    val (code, lines) =
      capture(ErSplitPipeline.run(Vector("dev"), envAt(work.resolve("out-dblp"), _ => f), _))
    assertEquals(code, 2)
    assert(lines.exists(_.contains("datasets.dblp.snapshot.id_is_dblp-2026-10-03")), lines.toString)
  }

  test("duplicate-source guard (HD-1): a source listed twice is refused (exit 2), any snapshots") {
    val work = TestSupport.workDir("er-split-duplicate-source")
    val dev = manifest("dev")
    val devReleases = Ledger.dblpReleases(repo, "dev").fold(e => fail(e), identity)
    val oa = SourceType.OpenAlex
    def twice(s: SourceType, first: String, second: String): RunManifest =
      dev.copy(inputSnapshots =
        dev.inputSnapshots.filterNot(_.source == s) ++ Vector(
          SnapshotRef(s, first),
          SnapshotRef(s, second)
        )
      )
    val cases = Vector(
      ("openalex-bulk-then-api", twice(oa, tokens.bulk(oa), tokens.api(oa)), true),
      ("openalex-api-then-bulk", twice(oa, tokens.api(oa), tokens.bulk(oa)), true),
      ("dblp-two-snapshots", twice(SourceType.Dblp, tokens.dblp, "dblp-2026-09-01"), true),
      ("dblp-two-snapshots-reversed", twice(SourceType.Dblp, "dblp-2026-09-01", tokens.dblp), true),
      ("openalex-exact-duplicate", twice(oa, tokens.api(oa), tokens.api(oa)), false)
    )
    cases.foreach { case (label, m, distinctPairs) =>
      val f = writeManifest(work.resolve(s"$label.json"), m)
      val coreDecode = Json.decode[RunManifest](
        new String(Files.readAllBytes(f), StandardCharsets.UTF_8)
      )
      note(
        s"[er-split-duplicate-source] case=$label coreDecode=${coreDecode.fold(e => "rejected:" + e.map(_.message).mkString("|"), _ => "accepted")}"
      )
      // Pairs that differ in snapshot pass core validation; only the Hadoop guard refuses them.
      if (distinctPairs) assert(coreDecode.isRight, s"$label: $coreDecode")
      // Checked directly first, so a regression fails here and never starts a pipeline run.
      val checked = ErSplitPipeline.checkManifest("dev", f, ledger, devReleases, tokens)
      assert(checked.isLeft, s"$label: accepted ${checked.map(_._2)}")
      if (coreDecode.isRight)
        assert(
          checked.left.exists(_.contains("more than once in inputSnapshots")),
          checked.toString
        )
      val out = work.resolve(s"out-$label")
      val (code, lines) = capture(ErSplitPipeline.run(Vector("dev"), envAt(out, _ => f), _))
      assertEquals(code, 2, s"$label: ${lines.mkString("\n")}")
      val refusal = lines.find(_.contains("status=refused")).getOrElse(fail(lines.mkString))
      if (coreDecode.isRight) assert(refusal.contains("more_than_once_in_inputSnapshots"), refusal)
      assert(!Files.exists(out), "refused before any output directory is created")
    }
    // control: the committed manifests (one entry per source) still pass
    ErSplit.Splits.foreach { s =>
      val m = manifest(s)
      assertEquals(m.inputSnapshots.map(_.source).distinct.size, m.inputSnapshots.size, s)
      val releases = Ledger.dblpReleases(repo, s).fold(e => fail(e), identity)
      val r = ErSplitPipeline.checkManifest(
        s,
        repo.resolve(ErSplit.manifestPath(s)),
        ledger,
        releases,
        tokens
      )
      assertEquals(r.map(_._2), RunId.of(m).left.map(_.message), s)
    }
  }

  test("usage: exactly one argument dev or heldout; anything else exits 2 and touches nothing") {
    val out = TestSupport.workDir("er-split-usage")
    Vector(
      Vector(),
      Vector("x"),
      Vector("DEV"),
      Vector("dev", "heldout"),
      Vector("dev", "dev"),
      Vector("")
    )
      .foreach { args =>
        val (code, lines) = capture(ErSplitPipeline.run(args, envAt(out), _))
        assertEquals(code, 2, args.toString)
        assert(lines.exists(_.contains("status=refused")), lines.mkString("\n"))
        assert(lines.exists(_.startsWith("usage:")), lines.mkString("\n"))
      }
    assertEquals(Files.list(out).count(), 0L, "nothing written")
  }

  test("snapshot-token derivation from PROVENANCE.tsv (printed for the report)") {
    for {
      split <- ErSplit.Splits
      source <- Vector("dblp", "openalex", "crossref")
    } {
      val rows = ledger.filter(r => r.split == split && r.source == source)
      val dates = rows.map(_.retrievedAt).sorted
      val kinds = rows.groupBy(_.kind).view.mapValues(_.size).toVector.sorted
      note(
        s"[er-split-derivation] split=$split source=$source rows=${rows.size} kinds=${kinds.mkString(",")} " +
          s"retrieved_at_utc.min=${dates.headOption.getOrElse("-")} retrieved_at_utc.max=${dates.lastOption.getOrElse("-")} " +
          s"utcDates=${rows.map(_.retrievedAt.take(10)).distinct.sorted.mkString(",")} " +
          s"ledgerToken=${
              if (source == "dblp") "-(release snapshot, not an API source)"
              else ErSplit.ledgerApiSnapshot(ledger.filter(_.split == split), source).getOrElse("-")
            } " +
          s"recordedToken=${SourceType.fromTag(source).flatMap(tokens.api.get).getOrElse("-")}"
      )
    }
    ErSplit.Splits.foreach { split =>
      note(
        s"[er-split-derivation] split=$split dblpReleaseIds=${Ledger.dblpReleases(repo, split).fold(identity, _.mkString(","))} " +
          s"inputDigest=${ErSplit.inputDigest(ledger.filter(_.split == split))} " +
          s"ledgerRows=${ledger.count(_.split == split)}"
      )
    }
    val other = ledger.filterNot(r => ErSplit.Splits.contains(r.split))
    assertEquals(other, Vector.empty, "every ledger row belongs to dev or heldout")
  }

  test(
    "committed manifests: valid, split-guarded, distinct run IDs, identical except split inputs"
  ) {
    val ms = ErSplit.Splits.map(s => s -> manifest(s)).toMap
    // Print every split's expected digests first, so one run shows all of them.
    ErSplit.Splits.foreach { s =>
      val m = ms(s)
      val expected = m.parameters ++ Map(
        JobParams.InputDigest -> ErSplit.inputDigest(ledger.filter(_.split == s))
      )
      note(
        s"[er-split-manifest] split=$s runId=${RunId.of(m).fold(_.message, identity)} " +
          s"expected.inputDigest=${expected(JobParams.InputDigest)} expected.configDigest=${ErSplit.configDigest(expected)}"
      )
    }
    ErSplit.Splits.foreach { s =>
      val m = ms(s)
      val releases = Ledger.dblpReleases(repo, s).fold(e => fail(e), identity)
      ErSplitPipeline.checkManifest(
        s,
        repo.resolve(ErSplit.manifestPath(s)),
        ledger,
        releases,
        tokens
      ) match {
        case Left(e) => fail(s"$s: $e")
        case Right((_, id)) => assertEquals(Some(id), RunId.of(m).toOption)
      }
      assertEquals(m.parameters(JobParams.InputSplit), s)
      // D13 option D: the API tokens recorded in the dataset manifest, verbatim; never the bulk IDs
      DatasetManifest.ApiSources.foreach { st =>
        val got = m.inputSnapshots.find(_.source == st).map(_.snapshot)
        assertEquals(got, tokens.api.get(st), s"$s ${st.tag}")
        assertNotEquals(got, tokens.bulk.get(st), s"$s ${st.tag} bulk")
        assertEquals(
          ErSplit.ledgerApiSnapshot(ledger.filter(_.split == s), st.tag),
          tokens.api.get(st),
          s"$s ${st.tag} ledger"
        )
      }
      assertEquals(
        m.inputSnapshots.find(_.source == SourceType.Dblp).map(_.snapshot),
        Some(tokens.dblp)
      )
      assertEquals(
        m.parameters(JobParams.BlockingMaxBlockSize),
        "8",
        "block cap is one manifest parameter (8: ER-P5-15, decisions-15/dev-run-01)"
      )
      assertEquals(m.parameters(JobParams.BlockingOversizePolicyKey), "drop")
      assertEquals(
        m.parameters(JobParams.BlockingKeySourceVersion),
        EntityResolution.blockingKeysVersion
      )
      assertEquals(
        m.parameters(JobParams.CandidatesFeatureVersion),
        EntityResolution.featuresVersion
      )
      assert(
        m.parameters.keys.forall(JobParams.ManifestKeys.contains),
        "the generic driver accepts it"
      )
    }
    val (dev, held) = (ms("dev"), ms("heldout"))
    assertNotEquals(RunId.of(dev), RunId.of(held), "distinct run IDs")
    val differing = (dev.parameters.keySet ++ held.parameters.keySet).filter(k =>
      dev.parameters.get(k) != held.parameters.get(k)
    )
    assertEquals(differing, Set(JobParams.InputSplit, JobParams.InputDigest))
    assertEquals(
      dev.copy(configDigest = "", inputSnapshots = Vector.empty, parameters = held.parameters),
      held.copy(configDigest = "", inputSnapshots = Vector.empty)
    )
    assertEquals(dev.inputSnapshots.map(_.source), held.inputSnapshots.map(_.source))
  }

  test("split guard: a manifest whose split parameter differs from the argument is refused") {
    val work = TestSupport.workDir("er-split-guard")
    val dev = manifest("dev")
    val tampered = work.resolve("tampered.json")
    Files.write(
      tampered,
      Json
        .write(dev.copy(parameters = dev.parameters + (JobParams.InputSplit -> "heldout")))
        .getBytes(StandardCharsets.UTF_8)
    )
    val out = work.resolve("out")
    val (code, lines) = capture(ErSplitPipeline.run(Vector("dev"), envAt(out, _ => tampered), _))
    assertEquals(code, 2)
    assert(
      lines.exists(l => l.contains("status=refused") && l.contains("split_guard")),
      lines.mkString("\n")
    )
    assert(!Files.exists(out), "refused before any output directory is created")
  }

  test(
    "input guard: symbolic links, paths outside the root, unexpected entries, ledger mismatches"
  ) {
    val fake = TestSupport.workDir("er-split-fake")
    val root = fake.resolve(ErSplit.inputRoot("dev"))
    ErSplit.InputDirs.foreach(d => Files.createDirectories(root.resolve(d)))
    val a = root.resolve("dblp/a.xml")
    Files.write(a, "<dblp/>".getBytes(StandardCharsets.UTF_8))
    val sha = scholarchain.core.Canonical.sha256Hex(Files.readAllBytes(a))
    val row = LedgerRow(
      s"${ErSplit.inputRoot("dev")}/dblp/a.xml",
      "dev",
      "dblp",
      "rec",
      "2026-11-13T00:00:00Z",
      sha
    )
    assert(ErSplitPipeline.collectInputs(fake, "dev", Vector(row)).isRight)
    assert(
      ErSplitPipeline
        .collectInputs(fake, "dev", Vector(row.copy(sha256 = "0" * 64)))
        .left
        .exists(_.contains("SHA-256 differs"))
    )
    assert(
      ErSplitPipeline
        .collectInputs(fake, "dev", Vector.empty)
        .left
        .exists(_.contains("without a ledger row"))
    )
    assert(
      ErSplitPipeline
        .collectInputs(
          fake,
          "dev",
          Vector(row, row.copy(file = s"${ErSplit.inputRoot("dev")}/dblp/b.xml"))
        )
        .left
        .exists(_.contains("without a file"))
    )
    assert(
      ErSplitPipeline
        .collectInputs(fake, "dev", Vector(row.copy(split = "heldout")))
        .left
        .exists(_.contains("without a ledger row")),
      "a row of the other split never admits a file"
    )
    val outside = fake.resolve("outside.xml")
    Files.write(outside, "<dblp/>".getBytes(StandardCharsets.UTF_8))
    val link = root.resolve("crossref-works/link.json")
    Files.createSymbolicLink(link, outside)
    assert(
      ErSplitPipeline
        .collectInputs(fake, "dev", Vector(row))
        .left
        .exists(_.contains("symbolic link"))
    )
    Files.delete(link)
    Files.createDirectories(root.resolve("orcid"))
    assert(
      ErSplitPipeline
        .collectInputs(fake, "dev", Vector(row))
        .left
        .exists(_.contains("unexpected entries: orcid"))
    )
    Files.delete(root.resolve("orcid"))
    Files.delete(root.resolve("openalex-authors"))
    assert(
      ErSplitPipeline
        .collectInputs(fake, "dev", Vector(row))
        .left
        .exists(_.contains("openalex-authors is missing"))
    )
  }

  test(
    "retry rule: a fresh attempt directory per run; partial and failed attempts reported; complete refuses"
  ) {
    val out = TestSupport.workDir("er-split-attempts")
    val (a1, n1) = Attempts.next(out).fold(fail(_), identity)
    assertEquals(a1.getFileName.toString, "attempt-1")
    assertEquals(n1, Vector.empty)
    Files.write(
      a1.resolve(ErSplit.InProgress),
      "x".getBytes(StandardCharsets.UTF_8)
    ) // killed / timed out
    val (a2, n2) = Attempts.next(out).fold(fail(_), identity)
    assertEquals(a2.getFileName.toString, "attempt-2")
    assert(
      n2.exists(n =>
        n.contains("attempt-1 status=partial") && n.contains("_IN_PROGRESS still present")
      ),
      n2.toString
    )
    Files.write(a2.resolve(ErSplit.Failed), "x".getBytes(StandardCharsets.UTF_8))
    val (a3, n3) = Attempts.next(out).fold(fail(_), identity)
    assertEquals(a3.getFileName.toString, "attempt-3")
    assert(n3.exists(_.contains("attempt-2 status=failed")), n3.toString)
    assert(Files.exists(a1.resolve(ErSplit.InProgress)), "earlier attempts are left untouched")
    Files.write(a3.resolve(ErSplit.Success), "x".getBytes(StandardCharsets.UTF_8))
    val refused = Attempts.next(out)
    assert(refused.left.exists(_.contains("refusing a second run")), refused.toString)
    assert(!Files.exists(out.resolve("attempt-4")))
  }

  test("block simulation: hand-checkable caps, refinement and drops (same rules as BlockReducer)") {
    // level-0 key "a": 5 members; 3 have a level-1 key (x, x, y); level-0 key "b": 2 members.
    val ms = Vector(
      SimMember("researcher", "mention.dblp", Vector("a", "x")),
      SimMember("researcher", "mention.dblp", Vector("a", "x")),
      SimMember("researcher", "mention.openalex", Vector("a", "y")),
      SimMember("researcher", "mention.dblp", Vector("a")),
      SimMember("researcher", "profile.openalex", Vector("a")),
      SimMember("researcher", "mention.dblp", Vector("b")),
      SimMember("researcher", "mention.dblp", Vector("b"))
    )
    val none = BlockSimulation.run(ms, None, 4)
    assertEquals((none.blocks, none.pairs, none.dropped), (2L, 10L + 1L, 0L))
    val cap4 = BlockSimulation.run(ms, Some(4), 4)
    // "a" (5) is oversize: x(2) and y(1) become blocks at level 1, 2 members dropped; "b" a block.
    assertEquals(
      (cap4.blocks, cap4.pairs, cap4.oversizeGroups, cap4.dropped),
      (3L, 1L + 0L + 1L, 1L, 2L)
    )
    assertEquals(cap4.droppedByKind.toMap, Map("mention.dblp" -> 1L, "profile.openalex" -> 1L))
    val cap1 = BlockSimulation.run(ms, Some(1), 4)
    assertEquals(cap1.dropped, 2L + 2L + 2L, "a's 2 unrefinable, b's 2, and x's 2 at level 1")
    assertEquals(BlockSimulation.level0Histogram(ms).toMap, Map(2 -> 1, 5 -> 1))
  }

  test(
    "DEV run end to end: Crossref read, distinct pairs, oversize drops, cap simulation, retry refusal"
  ) {
    val out = TestSupport.workDir("er-split-dev-run")
    val (code, lines) = capture(ErSplitPipeline.run(Vector("dev"), envAt(out), _))
    val summary = lines.filter(_.startsWith(ErSplit.Prefix))
    summary.foreach(l => note(l))
    assertEquals(code, 0, summary.mkString("\n"))
    val runId = RunId.of(manifest("dev")).toOption.get
    assert(summary.exists(l => l.contains(s"runId=$runId") && l.contains("status=complete")))
    assert(
      summary.exists(_.contains("normalize input=crossref-works read=6 accepted=6")),
      "Crossref envelopes read"
    )
    assert(
      summary.exists(
        _.contains("check=distinct.total==candidatePairs-candidateMerged,readBack==distinct:ok")
      )
    )
    assertEquals(summary.count(_.contains("simulationMatchesRun=true")), 2)
    assertEquals(summary.count(_.contains(" capsim ")), 2 * ErSplit.SimulatedCaps.size)
    assert(summary.exists(_.contains("elapsed total ms=")))
    // the dev conflict and duplicate records are named (HD-P5-03 item 3)
    val counts = summary.find(_.contains(" normalize conflictRecords=")).getOrElse(fail("no line"))
    assertEquals(
      summary.count(_.contains(" normalize conflict source=")).toString,
      counts.split("conflictRecords=")(1).takeWhile(_.isDigit)
    )
    assert(counts.contains("allDuplicatesNamed=true"), counts)
    // HD-P5-04: OpenAlex and Crossref records carry the API tokens, never the bulk IDs
    assert(
      summary.exists(_.contains("provenance check=every-record-snapshot==manifest-snapshot:ok")),
      summary.mkString("\n")
    )
    DatasetManifest.ApiSources.foreach { s =>
      val line = summary
        .find(_.contains(s" provenance source=${s.tag} "))
        .getOrElse(fail(s"no provenance line for ${s.tag}"))
      assert(line.contains(s"snapshots=${tokens.api(s)} "), line)
    }
    assert(
      summary.exists(_.contains("provenance source=dblp ")) &&
        summary.forall(l => !tokens.bulk.values.exists(b => l.contains(s"snapshots=$b"))),
      summary.mkString("\n")
    )
    val dev = manifest("dev")
    val curated = out.resolve("dev/attempt-1/curated")
    val layout = scholarchain.hadoop.pipeline.RunLayout
      .of(new org.apache.hadoop.fs.Path(curated.toUri), dev, runId)
      .fold(e => fail(e), identity)
    val conf = ErSplitPipeline.localConf(out.resolve("dev/attempt-1"))
    val fs = layout.root.getFileSystem(conf)
    val snaps = Vector(
      scholarchain.hadoop.NamedOutputs.Works,
      scholarchain.hadoop.NamedOutputs.Mentions,
      scholarchain.hadoop.NamedOutputs.Profiles
    ).flatMap(o => scholarchain.hadoop.pipeline.Pipeline.readLines(conf, fs, layout.normalized(o)))
      .flatMap(l => ErSplitPipeline.provenanceSnapshots(ujson.read(l)))
    DatasetManifest.ApiSources.foreach { s =>
      val got = snaps.filter(_._1 == s.tag).map(_._2).distinct
      assertEquals(got, Vector(tokens.api(s)), s"${s.tag} record snapshots")
    }
    assert(!snaps.exists(p => tokens.bulk.values.toSet.contains(p._2)), "no bulk snapshot ID")
    val attempt = out.resolve("dev/attempt-1")
    assert(Files.exists(attempt.resolve(ErSplit.Success)))
    assert(!Files.exists(attempt.resolve(ErSplit.InProgress)))
    assert(Files.exists(attempt.resolve(ErSplit.SummaryFile)))
    val (again, lines2) = capture(ErSplitPipeline.run(Vector("dev"), envAt(out), _))
    assertEquals(again, 2)
    assert(lines2.exists(_.contains("refusing_a_second_run")), lines2.mkString("\n"))
  }
}
