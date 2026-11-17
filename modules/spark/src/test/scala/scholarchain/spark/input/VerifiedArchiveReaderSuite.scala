package scholarchain.spark.input

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions

import scholarchain.core.*
import scholarchain.spark.testkit.{LocalSpark, RunFixtures}
import scholarchain.spark.testkit.RunFixtures.{Spec, Written}

/**
 * Feature tests of the verified archive reader. Each refusal test runs on a synthetic chain that
 * differs from the valid one only by the refused property (run IDs propagate through the chain, see
 * [[RunFixtures.Spec]]), and asserts that exactly that check refuses, so the test fails if the
 * check is deleted. Exceptions, where another check also catches the input, are named on the test.
 * Order tests show that a later check never runs while an earlier one refuses. Test names start
 * with the [[CheckStep]] order of the check they exercise. Synthetic fixtures only; no archive
 * access.
 *
 * The curated run is written in the Hadoop producer layout (HD-P6-01,
 * [[RunFixtures.writeHadoopRun]]) and checked against the curated-run contract of
 * [[HadoopCuratedRun]] (step 1A repair 02).
 */
class VerifiedArchiveReaderSuite extends munit.FunSuite {

  private val limits =
    ReaderLimits(
      maxTreeEntries = 1000,
      maxTreeDepth = 6,
      maxDriverValidationRows = 10000,
      maxDriverValidationBytes = 1L << 20,
      maxMetadataBytes = 1L << 16
    )

  private val tmp = FunFixture[Path](
    _ => Files.createTempDirectory("scholarchain-reader-"),
    p => LocalSpark.deleteTree(p)
  )

  private def read(w: Written, l: ReaderLimits = limits) = VerifiedArchiveReader.read(w.runs, l)

  private def steps(w: Written, l: ReaderLimits = limits): Set[CheckStep] =
    read(w, l) match {
      case Right(_) => Set.empty
      case Left(rs) => rs.map(_.step).toSet
    }

  private def refusals(w: Written, l: ReaderLimits = limits): Vector[Refusal] =
    read(w, l).swap.getOrElse(fail("expected a refusal, the chain was accepted"))

  private def refusedOnly(w: Written, step: CheckStep, l: ReaderLimits = limits): Unit =
    read(w, l) match {
      case Right(_) => fail(s"expected a ${step.tag} refusal, the chain was accepted")
      case Left(rs) => assertEquals(rs.map(_.step).toSet, Set(step), rs.mkString("\n"))
    }

  /** Exactly one refusal, of `step`, about `run`, whose message contains `fragment`. */
  private def refusedExactly(
      w: Written,
      step: CheckStep,
      run: String,
      fragment: String,
      l: ReaderLimits = limits
  ): Unit = {
    val rs = refusals(w, l)
    assertEquals(rs.map(r => (r.step, r.run)), Vector((step, run)), rs.mkString("\n"))
    assert(rs.head.message.contains(fragment), rs.mkString("\n"))
  }

  private def size(p: Path): Long = Files.size(p)
  private def utf8(s: String): Array[Byte] = s.getBytes(StandardCharsets.UTF_8)

  private def accepted(w: Written, l: ReaderLimits = limits): VerifiedInput =
    read(w, l).fold(rs => fail(rs.mkString("\n")), identity)

  tmp.test("the valid synthetic chain is accepted with every record decoded") { root =>
    val w = RunFixtures.write(root)
    val v = accepted(w)
    assertEquals(v.grouping.runId, w.groupingRunId)
    assertEquals(v.decision.runId, RunFixtures.decisionRunId)
    assertEquals(v.curated.runId, RunFixtures.curatedRunId)
    assertEquals(v.curated.partManifest, None)
    assertEquals(v.graphInput.teams.size, 5)
    assertEquals(v.graphInput.works.size, 6)
    assertEquals(v.graphInput.researchers.size, 11)
    assertEquals(v.decisions.size, 1)
    assertEquals(v.teamParts.map(_.getFileName.toString), Vector("part-00000.jsonl"))
    assertEquals(v.limits, limits)
  }

  // ---------------------------------------------------------------- Hadoop curated layout: valid

  tmp.test(
    "curated contract: the valid Hadoop layout (5 stages, round-0 and round-1 with entity parts " +
      "at depth 5, crcs, join+pairs+final) is accepted end to end through the run chain"
  ) { root =>
    val w = RunFixtures.write(root)
    val c = w.runs.curatedRun
    // Fixture shape the test relies on: no run-level _SUCCESS or _parts.json, depth-5 parts.
    assert(!Files.exists(c.resolve("_SUCCESS")))
    assert(!Files.exists(c.resolve("_parts.json")))
    assert(Files.isRegularFile(c.resolve("blocking/round-1/members/work/part-r-00000.gz")))
    assert(Files.isRegularFile(c.resolve("blocking/round-1/members/work/.part-r-00000.gz.crc")))
    assert(Files.isRegularFile(c.resolve("candidates/final/work/part-r-00000.gz")))
    assertEquals(c.getParent.getFileName.toString, s"snapshot=${RunFixtures.snapshot}")
    val v = accepted(w)
    assertEquals(v.curated.runId, w.runs.pinnedCuratedRunId)
    assertEquals(v.curated.manifest.stage, "hadoop.pipeline")
    assertEquals(v.decision.manifest.parameters.get("inputRunId"), Some(v.curated.runId))
    assertEquals(v.grouping.manifest.parameters.get("inputRunId"), Some(v.curated.runId))
  }

  tmp.test("curated contract: the valid Hadoop layout with candidates join only is accepted") {
    root =>
      val w = RunFixtures.write(root, Spec(curatedCandidates = Vector("joined")))
      assert(!Files.exists(w.runs.curatedRun.resolve("candidates/pairs")))
      accepted(w)
  }

  tmp.test(
    "curated contract: one round and an empty join job output directory (rule 11) are accepted"
  ) { root =>
    val w = RunFixtures.write(root, Spec(curatedRounds = 1, curatedCandidates = Vector.empty))
    assert(Files.isDirectory(w.runs.curatedRun.resolve("candidates/join")))
    accepted(w)
  }

  tmp.test("curated contract: four rounds (round-0 to round-3) are accepted") { root =>
    accepted(RunFixtures.write(root, Spec(curatedRounds = 4)))
  }

  test("curated contract: the restated producer names equal the hand-copied fixture lists") {
    assertEquals(HadoopCuratedRun.RequiredCounters, RunFixtures.requiredCounters)
    assertEquals(HadoopCuratedRun.RequiredCounters.size, 10)
    assertEquals(HadoopCuratedRun.Stages, RunFixtures.hadoopStages)
    assertEquals((HadoopCuratedRun.MaxFileDepth, HadoopCuratedRun.MaxDirDepth), (5, 4))
  }

  // ---------------------------------------------------------------- Hadoop curated layout: refused

  /**
   * One curated refusal: the valid chain (or `spec`), changed by `change` on the curated run, is
   * refused only by `step`, only for the curated run, and one refusal names `fragment`. Deleting
   * the check removes that message, so the test fails.
   */
  private def curatedRefused(
      name: String,
      step: CheckStep,
      fragment: String,
      spec: Spec = Spec(),
      l: ReaderLimits = limits
  )(change: Path => Unit): Unit =
    tmp.test(name) { root =>
      val w = RunFixtures.write(root, spec)
      change(w.runs.curatedRun)
      val rs = refusals(w, l)
      assertEquals(rs.map(r => (r.step, r.run)).toSet, Set((step, "curated")), rs.mkString("\n"))
      assert(rs.exists(_.message.contains(fragment)), rs.mkString("\n"))
    }

  private val foreign = "run:" + Canonical.sha256HexUtf8("a foreign run")

  private def deleteWithCrc(p: Path): Unit = {
    Files.delete(p)
    Files.delete(RunFixtures.crcOf(p))
  }

  // Rule 1.
  curatedRefused(
    "2 curated-run: a curated run whose ID is not the pinned run ID is refused (rule 1)",
    CheckStep.CuratedRun,
    "is not the pinned curated run " + foreign,
    Spec(pinnedCuratedRunId = Some(foreign))
  )(_ => ())

  curatedRefused(
    "2 curated-run: a curated run outside a snapshot=<token> directory is refused (rule 1)",
    CheckStep.CuratedRun,
    "is not a snapshot=<token> directory",
    Spec(curatedParentDir = Some(RunFixtures.snapshot))
  )(_ => ())

  // Rule 2.
  curatedRefused(
    "9 run-manifest: a curated manifest whose stage is not hadoop.pipeline is refused (rule 2)",
    CheckStep.RunManifest,
    "the run manifest stage is 'hadoop.other', expected 'hadoop.pipeline'",
    Spec(curatedStage = "hadoop.other")
  )(_ => ())

  curatedRefused(
    "9 run-manifest: a DBLP snapshot that differs from the snapshot= token is refused (rule 2)",
    CheckStep.RunManifest,
    s"DBLP snapshot '${RunFixtures.snapshot}' is not the directory's snapshot= token " +
      "'dblp-2026-09-01'",
    Spec(curatedParentDir = Some("snapshot=dblp-2026-09-01"))
  )(_ => ())

  curatedRefused(
    "9 run-manifest: a curated manifest that does not decode is refused (rule 2)",
    CheckStep.RunManifest,
    "_run-manifest.json:"
  )(c => Files.write(c.resolve("_run-manifest.json"), utf8("{\"stage\":\"hadoop.pipeline\"}")))

  curatedRefused(
    "7 hadoop-layout: a curated run without _run-manifest.json is refused (rules 2, 3)",
    CheckStep.HadoopLayout,
    "lacks _run-manifest.json"
  )(c => deleteWithCrc(c.resolve("_run-manifest.json")))

  // Rule 3.
  curatedRefused(
    "7 hadoop-layout: a run-level _SUCCESS in the curated run is refused (rules 3, 10)",
    CheckStep.HadoopLayout,
    "the Hadoop producer never writes a run-level _SUCCESS"
  )(c => RunFixtures.withCrc(c.resolve("_SUCCESS"), Array.emptyByteArray))

  curatedRefused(
    "7 hadoop-layout: a _parts.json in the curated run is refused (rule 3)",
    CheckStep.HadoopLayout,
    "'_parts.json' is an unexpected entry"
  )(c => RunFixtures.withCrc(c.resolve("_parts.json"), utf8("{}")))

  curatedRefused(
    "7 hadoop-layout: a missing stage directory is refused (rule 3)",
    CheckStep.HadoopLayout,
    "lacks the stage directory teams/"
  )(c => LocalSpark.deleteTree(c.resolve("teams")))

  // Rule 4, structural.
  curatedRefused(
    "7 hadoop-layout: a stage without _SUCCESS (incomplete input) is refused (rule 4)",
    CheckStep.HadoopLayout,
    "'teams/_SUCCESS' is missing"
  )(c => deleteWithCrc(c.resolve("teams/_SUCCESS")))

  curatedRefused(
    "7 hadoop-layout: a stage _SUCCESS that is not empty is refused (rule 4)",
    CheckStep.HadoopLayout,
    "'normalize/_SUCCESS' is not empty (1 bytes"
  )(c => RunFixtures.withCrc(c.resolve("normalize/_SUCCESS"), utf8("x")))

  curatedRefused(
    "7 hadoop-layout: a stage without _counters.json is refused (rule 4)",
    CheckStep.HadoopLayout,
    "'blocking/_counters.json' is missing"
  )(c => deleteWithCrc(c.resolve("blocking/_counters.json")))

  curatedRefused(
    "7 hadoop-layout: an empty _counters.json is refused (rule 4)",
    CheckStep.HadoopLayout,
    "'exact-ids/_counters.json' is empty"
  )(c => RunFixtures.withCrc(c.resolve("exact-ids/_counters.json"), Array.emptyByteArray))

  curatedRefused(
    "7 hadoop-layout: an unknown stage subdirectory is refused (rule 4)",
    CheckStep.HadoopLayout,
    "'normalize/extra' is an unexpected entry"
  )(c => RunFixtures.hadoopPart(c.resolve("normalize/extra")))

  // Rule 4, content.
  curatedRefused(
    "10 stage-counters: a _counters.json that is not valid JSON is refused (rule 4)",
    CheckStep.StageCounters,
    "'teams/_counters.json' is not valid JSON"
  )(c => Files.write(c.resolve("teams/_counters.json"), utf8("{not json")))

  curatedRefused(
    "10 stage-counters: a _counters.json that names another stage is refused (rule 4)",
    CheckStep.StageCounters,
    "'blocking/_counters.json' names stage 'teams', expected 'blocking'"
  )(c => Files.write(c.resolve("blocking/_counters.json"), utf8(RunFixtures.countersJson("teams"))))

  curatedRefused(
    "10 stage-counters: a _counters.json without a required counter is refused (rule 4)",
    CheckStep.StageCounters,
    "'candidates/_counters.json' lacks the required counter SCHEMA_FAILURE"
  )(c =>
    Files.write(
      c.resolve("candidates/_counters.json"),
      utf8(
        RunFixtures
          .countersJson("candidates", RunFixtures.requiredCounters.filterNot(_ == "SCHEMA_FAILURE"))
      )
    )
  )

  curatedRefused(
    "10 stage-counters: a negative counter is refused (rule 4)",
    CheckStep.StageCounters,
    "'normalize/_counters.json' counter INPUT is not a non-negative integer"
  )(c =>
    Files.write(
      c.resolve("normalize/_counters.json"),
      utf8(
        ujson.write(
          ujson.Obj(
            "stage" -> "normalize",
            "counters" -> ujson.Obj.from(
              RunFixtures.requiredCounters.map(k => k -> ujson.Num(if (k == "INPUT") -1 else 0))
            ),
            "stats" -> ujson.Obj()
          )
        )
      )
    )
  )

  tmp.test("10 stage-counters: a _counters.json larger than maxMetadataBytes is refused unread") {
    root =>
      val w = RunFixtures.write(root)
      val c = w.runs.curatedRun
      val bound = (Vector(size(c.resolve("_run-manifest.json"))) ++
        RunFixtures.hadoopStages.map(s => size(c.resolve(s).resolve("_counters.json")))).max
      val big = ujson.write(
        ujson.Obj(
          "stage" -> "teams",
          "counters" -> ujson.Obj.from(RunFixtures.requiredCounters.map(k => k -> ujson.Num(0))),
          "stats" -> ujson.Obj("pad" -> ("x" * bound.toInt))
        )
      )
      assert(big.length > bound, s"fixture: ${big.length} <= $bound")
      Files.write(c.resolve("teams/_counters.json"), utf8(big))
      refusedExactly(
        w,
        CheckStep.StageCounters,
        "curated",
        "more than the read bound",
        limits.copy(maxMetadataBytes = bound)
      )
  }

  // Rule 5.
  curatedRefused(
    "7 hadoop-layout: a gap in the blocking rounds (round-0, round-2) is refused (rule 5)",
    CheckStep.HadoopLayout,
    "round directories are not contiguous from round-0 (found round-0, round-2"
  )(c => Files.move(c.resolve("blocking/round-1"), c.resolve("blocking/round-2")))

  curatedRefused(
    "7 hadoop-layout: round-4 is beyond the last blocking level (rule 5)",
    CheckStep.HadoopLayout,
    "'blocking/round-4' is beyond the last blocking level",
    Spec(curatedRounds = 5)
  )(_ => ())

  // Rule 6.
  curatedRefused(
    "7 hadoop-layout: candidates/pairs without join/joined parts is refused (rule 6)",
    CheckStep.HadoopLayout,
    "'candidates/pairs' exists although join/joined holds no part file"
  )(c => LocalSpark.deleteTree(c.resolve("candidates/join/joined")))

  // Rule 7.
  curatedRefused(
    "7 hadoop-layout: a part-m file outside a rejects directory is refused (rule 7)",
    CheckStep.HadoopLayout,
    "'normalize/works/part-m-00000.gz' is a map-side part-m file outside a rejects"
  )(c => RunFixtures.hadoopPart(c.resolve("normalize/works"), "part-m-00000.gz"))

  curatedRefused(
    "7 hadoop-layout: an empty part file is refused (rule 7)",
    CheckStep.HadoopLayout,
    "'teams/teams/part-r-00000.gz' is an empty part file"
  )(c => RunFixtures.withCrc(c.resolve("teams/teams/part-r-00000.gz"), Array.emptyByteArray))

  // Rule 8.
  curatedRefused(
    "7 hadoop-layout: a .DS_Store file in the curated run is refused (rule 8)",
    CheckStep.HadoopLayout,
    "'normalize/.DS_Store' is a .DS_Store file"
  )(c => Files.write(c.resolve("normalize/.DS_Store"), Array.emptyByteArray))

  curatedRefused(
    "7 hadoop-layout: an AppleDouble ._* file in the curated run is refused (rule 8)",
    CheckStep.HadoopLayout,
    "'normalize/works/._part-r-00000.gz' is a hidden entry other than"
  )(c => Files.write(c.resolve("normalize/works/._part-r-00000.gz"), Array.emptyByteArray))

  curatedRefused(
    "7 hadoop-layout: another dotfile in the curated run is refused (rule 8)",
    CheckStep.HadoopLayout,
    "'normalize/works/.hidden' is a hidden entry other than"
  )(c => Files.write(c.resolve("normalize/works/.hidden"), Array.emptyByteArray))

  curatedRefused(
    "7 hadoop-layout: a file without its .crc sidecar is refused (rule 8)",
    CheckStep.HadoopLayout,
    "'exact-ids/links/part-r-00000.gz' has no checksum sidecar .part-r-00000.gz.crc"
  )(c => Files.delete(RunFixtures.crcOf(c.resolve("exact-ids/links/part-r-00000.gz"))))

  curatedRefused(
    "7 hadoop-layout: an orphan .crc sidecar is refused (rule 8)",
    CheckStep.HadoopLayout,
    "'teams/pairs/.part-r-00001.gz.crc' is a checksum sidecar without a regular sibling"
  )(c => Files.write(c.resolve("teams/pairs/.part-r-00001.gz.crc"), utf8("crc")))

  // Rule 9.
  curatedRefused(
    "6 tree: a file 6 levels below the curated run (a directory at depth 5) is refused (rule 9)",
    CheckStep.Tree,
    "deeper than 5 level(s) at 'blocking/round-0/blocks/work/deep'"
  )(c => RunFixtures.hadoopPart(c.resolve("blocking/round-0/blocks/work/deep")))

  curatedRefused(
    "6 tree: the curated walk is bounded by maxTreeEntries (rule 9)",
    CheckStep.Tree,
    "more than 50 entries",
    l = limits.copy(maxTreeEntries = 50)
  )(_ => ())

  // Rule 10.
  curatedRefused(
    "7 hadoop-layout: _temporary in a job output directory is refused (rule 10)",
    CheckStep.HadoopLayout,
    "'blocking/round-0/_temporary' marks incomplete or failed output"
  )(c => Files.createDirectories(c.resolve("blocking/round-0/_temporary/0")))

  curatedRefused(
    "7 hadoop-layout: _IN_PROGRESS in the curated run is refused (rule 10)",
    CheckStep.HadoopLayout,
    "'_IN_PROGRESS' marks incomplete or failed output"
  )(c => RunFixtures.withCrc(c.resolve("_IN_PROGRESS"), Array.emptyByteArray))

  curatedRefused(
    "7 hadoop-layout: _FAILED in a stage directory is refused (rule 10)",
    CheckStep.HadoopLayout,
    "'candidates/_FAILED' marks incomplete or failed output"
  )(c => RunFixtures.withCrc(c.resolve("candidates/_FAILED"), Array.emptyByteArray))

  curatedRefused(
    "7 hadoop-layout: a _SUCCESS outside a stage directory is refused (rule 10)",
    CheckStep.HadoopLayout,
    "'candidates/join/_SUCCESS' is a _SUCCESS outside a stage directory"
  )(c => RunFixtures.withCrc(c.resolve("candidates/join/_SUCCESS"), Array.emptyByteArray))

  curatedRefused(
    "6 tree: a symbolic link in the curated run (a part file at depth 5) is refused (rule 10)",
    CheckStep.Tree,
    "'blocking/round-0/members/researcher/part-r-00001.gz' is a symbolic link"
  ) { c =>
    Files.createSymbolicLink(
      c.resolve("blocking/round-0/members/researcher/part-r-00001.gz"),
      c.resolve("blocking/round-0/members/researcher/part-r-00000.gz")
    )
    ()
  }

  // Rule 11.
  curatedRefused(
    "7 hadoop-layout: an empty leaf output directory is refused (rule 11)",
    CheckStep.HadoopLayout,
    "'normalize/venues' holds no part file (an empty leaf output directory"
  )(c => Files.createDirectories(c.resolve("normalize/venues")))

  curatedRefused(
    "7 hadoop-layout: an empty blocks/ directory is refused (rule 11)",
    CheckStep.HadoopLayout,
    "'blocking/round-1/blocks' is an empty output directory without researcher/ or work/"
  ) { c =>
    LocalSpark.deleteTree(c.resolve("blocking/round-1/blocks/researcher"))
    LocalSpark.deleteTree(c.resolve("blocking/round-1/blocks/work"))
  }

  // ---------------------------------------------------------------- 1-7. layout (all runs)

  tmp.test("1 run-name: a grouping directory not named run=<64 lowercase hex> is refused") { root =>
    val w = RunFixtures.write(root, Spec(groupingDirName = Some("run=" + "A" * 64)))
    refusedOnly(w, CheckStep.RunName)
  }

  tmp.test("3 unverified-marker: _UNVERIFIED in the decision run is refused") { root =>
    val w = RunFixtures.write(root)
    Files.write(w.runs.decisionRun.resolve("_UNVERIFIED"), Array.emptyByteArray)
    // The marker is also an unlisted file, but no content check runs while a layout check refuses.
    refusedOnly(w, CheckStep.UnverifiedMarker)
  }

  tmp.test("3 unverified-marker: _UNVERIFIED in the curated run is refused") { root =>
    val w = RunFixtures.write(root)
    Files.write(w.runs.curatedRun.resolve("_UNVERIFIED"), Array.emptyByteArray)
    refusedExactly(w, CheckStep.UnverifiedMarker, "curated", "_UNVERIFIED is present")
  }

  tmp.test("4 staging-entry: a .staging-* sibling of the grouping run is refused") { root =>
    val w = RunFixtures.write(root)
    Files.createDirectory(w.runs.groupingRun.getParent.resolve(".staging-abc"))
    refusedOnly(w, CheckStep.StagingEntry)
  }

  tmp.test("4 staging-entry: an .unverified-* entry inside the curated run is refused") { root =>
    val w = RunFixtures.write(root)
    Files.createDirectory(w.runs.curatedRun.resolve(".unverified-1"))
    refusedOnly(w, CheckStep.StagingEntry)
  }

  tmp.test("4 staging-entry: a .staging-* directory inside the curated run is refused (rule 10)") {
    root =>
      val w = RunFixtures.write(root)
      Files.createDirectories(w.runs.curatedRun.resolve("blocking/.staging-1"))
      refusedExactly(w, CheckStep.StagingEntry, "curated", "'blocking/.staging-1' inside the run")
  }

  tmp.test("4 staging-entry (F5): an unlistable parent of a run is refused, never read as empty") {
    root =>
      val w = RunFixtures.write(root)
      val parent = w.runs.groupingRun.getParent
      val before = Files.getPosixFilePermissions(parent)
      // Search but no read permission: the run itself stays reachable, its parent unlistable.
      Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("-wx--x--x"))
      try {
        assert(RunTree.names(parent).isLeft, "fixture: the parent must be unlistable")
        refusedExactly(w, CheckStep.StagingEntry, "grouping", "cannot be checked")
      } finally {
        Files.setPosixFilePermissions(parent, before)
        ()
      }
  }

  tmp.test("5 success-marker: a decision run without _SUCCESS is refused") { root =>
    val w = RunFixtures.write(root)
    Files.delete(w.runs.decisionRun.resolve("_SUCCESS"))
    refusedOnly(w, CheckStep.SuccessMarker)
  }

  tmp.test("6 tree: a symbolic link inside the grouping run is refused") { root =>
    val w = RunFixtures.write(root)
    Files.createSymbolicLink(
      w.runs.groupingRun.resolve("teams/link.jsonl"),
      w.runs.groupingRun.resolve("teams/part-00000.jsonl")
    )
    refusedOnly(w, CheckStep.Tree)
  }

  tmp.test("6 tree: a decision or grouping tree deeper than maxTreeDepth is refused") { root =>
    val w = RunFixtures.write(root)
    // The curated run is walked to the fixed HD-P6-01 depths, not to maxTreeDepth.
    val rs = refusals(w, limits.copy(maxTreeDepth = 1))
    assertEquals(rs.map(_.step).toSet, Set(CheckStep.Tree), rs.mkString("\n"))
    assertEquals(rs.map(_.run).toSet, Set("decision", "grouping"), rs.mkString("\n"))
  }

  tmp.test("6 tree (F2): a tree with more entries than maxTreeEntries is refused") { root =>
    val w = RunFixtures.write(root)
    // Every run of the fixture has more than 3 entries.
    val rs = refusals(w, limits.copy(maxTreeEntries = 3))
    assertEquals(rs.map(_.step).toSet, Set(CheckStep.Tree), rs.mkString("\n"))
    assertEquals(rs.map(_.run).toSet, Set("curated", "decision", "grouping"), rs.mkString("\n"))
    assert(rs.forall(_.message.contains("more than 3 entries")), rs.mkString("\n"))
  }

  test("6 tree (F2): a special file (kind Other: FIFO, socket, device) is refused") {
    // Unit test of the refusal rule on walked entries: the JDK cannot create a FIFO, and a Unix
    // socket path under the fixture's run directory exceeds the socket path limit.
    val entries = Vector(
      TreeEntry("teams", TreeEntry.Kind.Directory, 1),
      TreeEntry("teams/fifo", TreeEntry.Kind.Other, 2),
      TreeEntry("teams/part-00000.jsonl", TreeEntry.Kind.Regular, 2)
    )
    assertEquals(
      VerifiedArchiveReader.treeRefusals("grouping", entries),
      Vector(
        Refusal(CheckStep.Tree, "grouping", "'teams/fifo' is not a regular file or directory")
      )
    )
  }

  // ---------------------------------------------------------------- 8-10. integrity

  private def tamper(p: Path): Unit = {
    val b = Files.readAllBytes(p)
    b(10) = (if (b(10) == 'x'.toByte) 'y' else 'x').toByte
    Files.write(p, b)
    ()
  }

  tmp.test("8 part-manifest: one changed byte in the grouping teams part is refused") { root =>
    val w = RunFixtures.write(root)
    tamper(w.runs.groupingRun.resolve("teams/part-00000.jsonl"))
    val rs = refusals(w)
    assertEquals(rs.map(_.step).toSet, Set(CheckStep.PartManifest))
    assert(rs.exists(_.message.contains("sha256")), rs.mkString("\n"))
  }

  tmp.test("8 part-manifest: an extra unlisted file in the decision run is refused") { root =>
    val w = RunFixtures.write(root)
    Files.write(
      w.runs.decisionRun.resolve("decisions/extra.jsonl"),
      "x\n".getBytes(StandardCharsets.UTF_8)
    )
    refusedOnly(w, CheckStep.PartManifest)
  }

  tmp.test("8 part-manifest: a grouping run without a teams part is not a complete grouping run") {
    root =>
      val w = RunFixtures.write(root, Spec(withTeamsPart = false))
      val rs = refusals(w)
      assertEquals(rs.map(_.step).toSet, Set(CheckStep.PartManifest))
      assert(rs.exists(_.message.contains(SchemaVersions.PublicationTeam)), rs.mkString("\n"))
  }

  tmp.test("8 part-manifest (F2): a decision run that lists no match-decision part is refused") {
    root =>
      val w = RunFixtures.write(root, Spec(decisionPartSchema = SchemaVersions.WorkAssignment))
      refusedExactly(
        w,
        CheckStep.PartManifest,
        "decision",
        "no part of schema " + SchemaVersions.MatchDecision
      )
  }

  tmp.test("8 part-manifest (F3): a _parts.json larger than maxMetadataBytes is refused unread") {
    root =>
      val w = RunFixtures.write(root)
      val c = w.runs.curatedRun
      // Every metadata file read before the decision part manifest fits the bound.
      val before = Vector(c, w.runs.decisionRun).map(d =>
        size(d.resolve(PartManifest.RunManifestFile))
      ) ++ RunFixtures.hadoopStages.map(s => size(c.resolve(s).resolve("_counters.json")))
      val bound = before.max
      val parts = size(w.runs.decisionRun.resolve(PartManifest.FileName))
      assert(parts > bound, s"fixture: decision _parts.json $parts, metadata read before $before")
      refusedExactly(
        w,
        CheckStep.PartManifest,
        "decision",
        "more than the read bound",
        limits.copy(maxMetadataBytes = bound)
      )
  }

  tmp.test("9 run-manifest: a run manifest whose run ID is not the directory's is refused") {
    root =>
      // Grouping run: also caught by PartManifestV2.checkRunManifest (the parts name the directory).
      val other = "run:" + Canonical.sha256HexUtf8("another grouping run")
      val w = RunFixtures.write(root, Spec(groupingPartsRunId = Some(other)))
      refusedOnly(w, CheckStep.RunManifest)
  }

  tmp.test("9 run-manifest (F1): a curated run manifest whose run ID is not its directory's") {
    root =>
      // The curated run has no part manifest, so this check is its only binding. The pinned run ID
      // is the directory's (Spec default), so the pin check passes.
      val w = RunFixtures.write(
        root,
        Spec(curatedDirHex = Some(Canonical.sha256HexUtf8("another curated run")))
      )
      refusedExactly(
        w,
        CheckStep.RunManifest,
        "curated",
        s"the run manifest gives ${w.curatedRunId}"
      )
  }

  tmp.test("9 run-manifest (F3): a _run-manifest.json larger than maxMetadataBytes is refused") {
    root =>
      val w = RunFixtures.write(root)
      val bound = size(w.runs.curatedRun.resolve(PartManifest.RunManifestFile)) - 1
      refusedExactly(
        w,
        CheckStep.RunManifest,
        "curated",
        "more than the read bound",
        limits.copy(maxMetadataBytes = bound)
      )
  }

  // ---------------------------------------------------------------- 11. chain

  tmp.test("11 run-chain: a grouping run naming another decision run is refused") { root =>
    val w = RunFixtures.write(root, Spec(groupingEdit = _.updated("decisionRunId", foreign)))
    refusedOnly(w, CheckStep.RunChain)
  }

  tmp.test("11 run-chain: a grouping run naming another curated run is refused") { root =>
    val w = RunFixtures.write(root, Spec(groupingEdit = _.updated("inputRunId", foreign)))
    refusedExactly(w, CheckStep.RunChain, "grouping", "parameter inputRunId")
  }

  tmp.test("11 run-chain (F1): a decision run naming another curated run is refused") { root =>
    val w = RunFixtures.write(root, Spec(decisionInputRunId = Some(foreign)))
    refusedExactly(
      w,
      CheckStep.RunChain,
      "decision",
      s"parameter ${VerifiedArchiveReader.DecisionInputRunKey} is $foreign"
    )
  }

  tmp.test("11 run-chain (F1): grouping input snapshots that differ from the decision run's") {
    root =>
      val w = RunFixtures.write(root, Spec(groupingSnapshots = RunFixtures.otherSnapshots))
      refusedExactly(
        w,
        CheckStep.RunChain,
        "grouping",
        "input snapshots differ from the decision run's"
      )
  }

  tmp.test("11 run-chain (F1): decision input snapshots that differ from the curated run's") {
    root =>
      // The curated run lies under snapshot=<its own DBLP snapshot>, so rule 2 holds for it.
      val w = RunFixtures.write(root, Spec(curatedSnapshots = RunFixtures.otherSnapshots))
      refusedExactly(
        w,
        CheckStep.RunChain,
        "decision",
        "input snapshots differ from the curated run's"
      )
  }

  // ---------------------------------------------------------------- 12-13. bound and decode

  tmp.test("12 driver-bound: more declared rows than the driver validation bound is refused") {
    root =>
      val w = RunFixtures.write(root)
      refusedExactly(
        w,
        CheckStep.DriverBound,
        "grouping,decision",
        "declared rows",
        limits.copy(maxDriverValidationRows = 5)
      )
  }

  tmp.test(
    "12 driver-bound (F3): more declared bytes than the driver validation bound is refused"
  ) { root =>
    val w = RunFixtures.write(root)
    refusedExactly(
      w,
      CheckStep.DriverBound,
      "grouping,decision",
      "declared bytes",
      limits.copy(maxDriverValidationBytes = 1000)
    )
  }

  private val badTeamLine = Json
    .write(
      RunFixtures.team(RunFixtures.w4, Some(2022), Vector(RunFixtures.rE), Vector.empty, "run:x")
    )
    .replace("\"teamSize\":1", "\"teamSize\":0")

  tmp.test("13 decode: a teams row that breaks its record contract is refused") { root =>
    val w = RunFixtures.write(root, Spec(extraTeamLines = Vector(badTeamLine)))
    refusedOnly(w, CheckStep.Decode)
  }

  test("RunTree.readUtf8 (F3): refuses a file larger than the bound and an invalid bound") {
    val dir = Files.createTempDirectory("scholarchain-read-")
    try {
      val f = Files.write(dir.resolve("ten.txt"), "0123456789".getBytes(StandardCharsets.UTF_8))
      assert(RunTree.readUtf8(f, 9).left.exists(_.contains("more than the read bound 9")))
      assertEquals(RunTree.readUtf8(f, 10), Right("0123456789"))
      assert(RunTree.readUtf8(f, -1).isLeft)
      assert(RunTree.readUtf8(f, RunTree.MaxArrayBytes + 1).isLeft)
    } finally LocalSpark.deleteTree(dir)
  }

  // ---------------------------------------------------------------- 14-15. core validators

  tmp.test("14 graph-input: a team of an enrichment-only work is refused (checkGraphInput)") {
    root =>
      val w = RunFixtures.write(
        root,
        Spec(teams =
          Some(g =>
            RunFixtures.teams(g) :+ RunFixtures.team(
              RunFixtures.x1,
              Some(2020),
              Vector(RunFixtures.rE),
              Vector.empty,
              g
            )
          )
        )
      )
      val rs = refusals(w)
      assertEquals(rs.map(_.step).toSet, Set(CheckStep.GraphInput))
      assert(rs.exists(_.message.contains("only counted works enter the graph")), rs.mkString("\n"))
  }

  tmp.test("15 identity: a decision of another run is refused (checkIdentity)") { root =>
    val w = RunFixtures.write(root, Spec(decisionRunIdOfDecisions = Some(foreign)))
    val rs = refusals(w)
    assertEquals(rs.map(_.step).toSet, Set(CheckStep.Identity))
    assert(rs.exists(_.message.contains("must be the decision run")), rs.mkString("\n"))
  }

  // ---------------------------------------------------------------- order

  tmp.test("order: a layout refusal stops before the part manifest is verified") { root =>
    val w = RunFixtures.write(root)
    tamper(w.runs.groupingRun.resolve("teams/part-00000.jsonl"))
    Files.write(w.runs.groupingRun.resolve("_UNVERIFIED"), Array.emptyByteArray)
    assertEquals(steps(w), Set(CheckStep.UnverifiedMarker))
  }

  tmp.test("order: a curated pin refusal stops every other check of the curated run") { root =>
    val w = RunFixtures.write(root, Spec(pinnedCuratedRunId = Some(foreign)))
    Files.write(w.runs.curatedRun.resolve("_UNVERIFIED"), Array.emptyByteArray)
    Files.write(w.runs.curatedRun.resolve("normalize/.DS_Store"), Array.emptyByteArray)
    assertEquals(steps(w), Set(CheckStep.CuratedRun))
  }

  tmp.test("order: a curated layout refusal stops before any curated metadata is read") { root =>
    val w = RunFixtures.write(root, Spec(curatedStage = "hadoop.other"))
    Files.write(w.runs.curatedRun.resolve("teams/_counters.json"), utf8("{not json"))
    Files.write(w.runs.curatedRun.resolve("normalize/.DS_Store"), Array.emptyByteArray)
    assertEquals(steps(w), Set(CheckStep.HadoopLayout))
  }

  tmp.test("order: a curated run-manifest refusal stops before the stage counters are read") {
    root =>
      val w = RunFixtures.write(root, Spec(curatedStage = "hadoop.other"))
      Files.write(w.runs.curatedRun.resolve("teams/_counters.json"), utf8("{not json"))
      assertEquals(steps(w), Set(CheckStep.RunManifest))
  }

  tmp.test("order: a part-manifest refusal stops before the run chain is checked") { root =>
    val w = RunFixtures.write(root, Spec(groupingEdit = _.updated("inputRunId", foreign)))
    tamper(w.runs.groupingRun.resolve("teams/part-00000.jsonl"))
    assertEquals(steps(w), Set(CheckStep.PartManifest))
  }

  tmp.test("order: a run-chain refusal stops before graph input and identity are validated") {
    root =>
      val w = RunFixtures.write(
        root,
        Spec(
          groupingEdit = _.updated("decisionRunId", foreign),
          decisionRunIdOfDecisions = Some(foreign)
        )
      )
      assertEquals(steps(w), Set(CheckStep.RunChain))
  }

  tmp.test("order (F2): undecodable rows and an exceeded row bound yield only driver-bound") {
    root =>
      // The bound is checked before any data row is read: decode never runs.
      val w = RunFixtures.write(root, Spec(extraTeamLines = Vector(badTeamLine)))
      val rs = refusals(w, limits.copy(maxDriverValidationRows = 5))
      assertEquals(rs.map(_.step), Vector(CheckStep.DriverBound), rs.mkString("\n"))
      assert(rs.head.message.contains("declared rows"), rs.mkString("\n"))
  }

  tmp.test("order (F3): undecodable rows and an exceeded byte bound yield only driver-bound") {
    root =>
      val w = RunFixtures.write(root, Spec(extraTeamLines = Vector(badTeamLine)))
      val rs = refusals(w, limits.copy(maxDriverValidationBytes = 1000))
      assertEquals(rs.map(_.step), Vector(CheckStep.DriverBound), rs.mkString("\n"))
      assert(rs.head.message.contains("declared bytes"), rs.mkString("\n"))
  }

  test("ArchiveRuns refuses relative, unnormalized and outside paths and a malformed pin") {
    val pin = "run:" + "a" * 64
    assert(ArchiveRuns.of("/a", "b", "/a/c", "/a/d", pin).isLeft)
    assert(ArchiveRuns.of("/a", "/a/../a/b", "/a/c", "/a/d", pin).isLeft)
    assert(ArchiveRuns.of("/a", "/x/b", "/a/c", "/a/d", pin).isLeft)
    assert(ArchiveRuns.of("/a", "/a/b", "/a/b", "/a/d", pin).isLeft)
    assert(ArchiveRuns.of("/a", "/a/b", "/a/c", "/a/d", "a" * 64).isLeft)
    assert(ArchiveRuns.of("/a", "/a/b", "/a/c", "/a/d", "run:" + "A" * 64).isLeft)
    assert(ArchiveRuns.of("/a", "/a/b", "/a/c", "/a/d", null).isLeft)
    assertEquals(
      ArchiveRuns.of("/a", "/a/b", "/a/c", "/a/d", pin).map(_.pinnedCuratedRunId),
      Right(pin)
    )
  }
}
