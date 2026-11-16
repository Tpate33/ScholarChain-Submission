package scholarchain.core

import java.nio.charset.StandardCharsets

import AssignmentFixtures.*

/** Contract C5: the part manifest and its pure verifier (no file I/O anywhere in this suite). */
class PartManifestSuite extends munit.FunSuite {

  private def refused(errs: Vector[ValidationError], fragment: String): Unit =
    assert(
      errs.exists(_.message.contains(fragment)),
      s"expected a refusal containing '$fragment', got ${errs.map(_.message)}"
    )

  private def verify(observed: Vector[ObservedFile]): Vector[ValidationError] =
    PartManifestVerifier.verify(partManifest, groupingRunId, observed)

  private val ResearchersPath = "researchers/part-00000.jsonl"
  private def bytesOf(path: String): Array[Byte] = runFiles.find(_._1 == path).get._3

  /** The complete observation with the file at `path` replaced by `bytes`. */
  private def withContent(path: String, bytes: Array[Byte]): Vector[ObservedFile] =
    observedRun.map(o => if (o.path == path) ObservedFile.of(path, bytes) else o)

  test("an exact copy is accepted") {
    assertEquals(verify(observedRun), Vector.empty)
    assertEquals(PartManifest.checkRunManifest(partManifest, groupingManifest), Vector.empty)
  }

  test("observations count LF-terminated rows, bytes and SHA-256 exactly") {
    val o = ObservedFile.of("x", "a\nbb\n".getBytes(StandardCharsets.UTF_8))
    assertEquals(
      o,
      ObservedFile(
        "x",
        5L,
        2L,
        Canonical.sha256Hex("a\nbb\n".getBytes(StandardCharsets.UTF_8)),
        true
      )
    )
    assertEquals(
      ObservedFile.of("e", Array.emptyByteArray),
      ObservedFile("e", 0L, 0L, PartManifest.EmptySha256, false)
    )
    assertEquals(ObservedFile.of("n", "a\nb".getBytes(StandardCharsets.UTF_8)).endsWithLf, false)
    // Streaming in chunks gives the same observation.
    val bytes = bytesOf(ResearchersPath)
    val acc = new ObservedFile.Accumulator
    bytes.grouped(7).foreach(c => acc.update(c, 0, c.length))
    assertEquals(acc.result(ResearchersPath), ObservedFile.of(ResearchersPath, bytes))
    assertEquals(
      partManifest.files.find(_.path == ResearchersPath).map(_.rowCount),
      Some(researchers.size.toLong)
    )
  }

  test("a file cut short at a line boundary is refused: fewer rows, fewer bytes, another sha256") {
    val bytes = bytesOf(ResearchersPath)
    val text = new String(bytes, StandardCharsets.UTF_8)
    val cut = text.substring(0, text.indexOf('\n') + 1) // first row only, still LF-terminated
    val obs = withContent(ResearchersPath, cut.getBytes(StandardCharsets.UTF_8))
    val o = obs.find(_.path == ResearchersPath).get
    assert(o.endsWithLf, "the cut copy still ends in LF, so only the counts and hash reveal it")
    val errs = verify(obs)
    refused(errs, s"rowCount: listed ${researchers.size}, observed 1")
    refused(errs, "byteCount: listed")
    refused(errs, "sha256: listed")
  }

  test("a file cut mid-line is refused, including the missing final LF") {
    val bytes = bytesOf(ResearchersPath)
    val obs = withContent(ResearchersPath, bytes.take(bytes.length - 5))
    val errs = verify(obs)
    refused(errs, "a non-empty file must end in LF")
    refused(errs, "byteCount: listed")
    refused(errs, "sha256: listed")
    refused(errs, "rowCount: listed")
  }

  test("a same-length corruption is refused by the sha256 alone") {
    val bytes = bytesOf(ResearchersPath).clone()
    bytes(3) = (bytes(3) ^ 0x01).toByte
    val errs = verify(withContent(ResearchersPath, bytes))
    assertEquals(errs.size, 1, errs)
    refused(errs, "sha256: listed")
  }

  test("a missing file and an extra file are refused") {
    refused(
      verify(observedRun.filterNot(_.path == ResearchersPath)),
      "listed file missing from the run directory"
    )
    refused(
      verify(observedRun.filterNot(_.path == PartManifest.RunManifestFile)),
      "listed file missing"
    )
    val extra = observedRun :+ ObservedFile.of(
      "researchers/part-00001.jsonl",
      "{}\n".getBytes(StandardCharsets.UTF_8)
    )
    refused(verify(extra), "extra file not listed in the part manifest")
    refused(verify(observedRun :+ observedRun.head), "observed more than once")
  }

  test("both marker files must be present; they are not compared") {
    refused(
      verify(observedRun.filterNot(_.path == PartManifest.SuccessMarker)),
      "marker file missing"
    )
    refused(verify(observedRun.filterNot(_.path == PartManifest.FileName)), "marker file missing")
    val otherSuccess = observedRun.map(o =>
      if (o.path == PartManifest.SuccessMarker)
        ObservedFile.of(o.path, "x\n".getBytes(StandardCharsets.UTF_8))
      else o
    )
    assertEquals(verify(otherSuccess), Vector.empty)
  }

  test(
    "the directory must hold the expected run, and the run manifest must give the manifest's run ID"
  ) {
    refused(
      PartManifestVerifier.verify(partManifest, decisionRunId, observedRun),
      "the directory holds run"
    )
    refused(
      PartManifest.checkRunManifest(partManifest, Fixtures.manifest),
      "the run manifest gives"
    )
  }

  test("an invalid manifest is refused before any comparison") {
    val bad = partManifest.copy(runId = "run:nope")
    val errs = PartManifestVerifier.verify(bad, groupingRunId, observedRun)
    assert(errs.exists(_.isInstanceOf[ValidationError.MalformedId]), errs)
    assertEquals(
      PartManifestVerifier.verify(null, groupingRunId, observedRun),
      Vector(ValidationError.MissingField("partManifest"))
    )
    refused(PartManifestVerifier.verify(partManifest, groupingRunId, null), "observed")
  }

  // ------------------------------------------------------------------ manifest rules

  private def withFile(f: PartFile): PartManifest =
    partManifest.copy(files =
      (partManifest.files :+ f).sortWith((a, b) => Canonical.compareUtf8(a.path, b.path) < 0)
    )

  private val okFile =
    PartFile("works/part-00001.jsonl", SchemaVersions.WorkAssignment, 1, 3, "a" * 64)

  private def invalid(m: PartManifest, fragment: String): Unit =
    refused(PartManifest.contract.validate(m), fragment)

  test("paths: relative, '/'-separated, no '..', '.', empty segment, backslash or absolute path") {
    assertEquals(PartManifest.contract.validate(withFile(okFile)), Vector.empty)
    for (
      (p, why) <- Vector(
        "../x.jsonl" -> "'.' or '..'",
        "a/./b" -> "'.' or '..'",
        "/abs.jsonl" -> "absolute",
        "a\\b.jsonl" -> "backslash",
        "a//b" -> "empty segment",
        "a/" -> "empty segment",
        "a b" -> "outside",
        "" -> "missing"
      )
    ) {
      invalid(withFile(okFile.copy(path = p)), why)
      assert(PartManifest.pathProblem(p).isDefined, p)
    }
    assertEquals(PartManifest.pathProblem("run=abc/part-00000.jsonl"), None)
  }

  test(
    "paths: sorted by bytes without duplicates, no case-only twins, no file that is also a directory"
  ) {
    invalid(partManifest.copy(files = partManifest.files.reverse), "sorted by UTF-8 bytes")
    invalid(
      partManifest.copy(files = partManifest.files :+ partManifest.files.last),
      "sorted by UTF-8 bytes"
    )
    invalid(withFile(okFile.copy(path = "Works/part-00000.jsonl")), "differ only in letter case")
    invalid(withFile(okFile.copy(path = "works")), "is a directory of")
  }

  test("F5: the file-versus-directory check names the first listed path below the file") {
    val m = withFile(okFile.copy(path = "works"))
    val errs = PartManifest.contract.validate(m).map(_.message).filter(_.contains("directory"))
    assertEquals(
      errs,
      Vector(
        "files.path: 'works' is listed as a file but is a directory of 'works/part-00000.jsonl'"
      )
    )
    // Nested: a file at each level is reported once, naming the first deeper path.
    val nested = Vector(
      okFile.copy(path = "a"),
      okFile.copy(path = "a/b"),
      okFile.copy(path = "a/b/c.jsonl"),
      okFile.copy(path = "a/bc.jsonl")
    ).foldLeft(partManifest)((pm, f) =>
      pm.copy(files = (pm.files :+ f).sortWith((x, y) => Canonical.compareUtf8(x.path, y.path) < 0))
    )
    assertEquals(
      PartManifest.contract.validate(nested).map(_.message).filter(_.contains("directory")),
      Vector(
        "files.path: 'a' is listed as a file but is a directory of 'a/b'",
        "files.path: 'a/b' is listed as a file but is a directory of 'a/b/c.jsonl'"
      )
    )
    // A shared name prefix is not a directory.
    assertEquals(
      PartManifest.contract.validate(withFile(okFile.copy(path = "work"))),
      Vector.empty
    )
  }

  test("m1: an over-long path is refused by length, and never enters the directory index") {
    // Without the fix, the index copied one prefix per '/' of these paths: about 2.5e9 and 5e9
    // characters, which exhausts the heap instead of returning the length refusal.
    val manySlashes = "/" * 100000
    val deep = "a/" * 50000 + "x.jsonl"
    for (long <- Vector(manySlashes, deep)) {
      assertEquals(
        PartManifest.pathProblem(long),
        Some(s"longer than ${PartManifest.MaxPathLength} characters")
      )
      val errs = PartManifest.contract.validate(withFile(okFile.copy(path = long)))
      refused(errs, s"longer than ${PartManifest.MaxPathLength} characters")
      assert(!errs.exists(_.message.contains("is a directory of")), errs.map(_.message))
    }
    // Both at once, next to a valid file below a valid directory file: only the valid overlap is
    // reported.
    val both = Vector(okFile.copy(path = manySlashes), okFile.copy(path = deep))
      .foldLeft(withFile(okFile.copy(path = "works")))((pm, f) =>
        pm.copy(files =
          (pm.files :+ f).sortWith((x, y) => Canonical.compareUtf8(x.path, y.path) < 0)
        )
      )
    val errs = PartManifest.contract.validate(both)
    assertEquals(errs.count(_.message.contains("longer than")), 2, errs.map(_.message))
    assertEquals(
      errs.map(_.message).filter(_.contains("directory")),
      Vector(
        "files.path: 'works' is listed as a file but is a directory of 'works/part-00000.jsonl'"
      )
    )
  }

  test("m1: a valid path of exactly MaxPathLength characters still takes part in the check") {
    val dir = "d/" * ((PartManifest.MaxPathLength - 8) / 2)
    val longest = dir + "x" * (PartManifest.MaxPathLength - dir.length)
    assertEquals(longest.length, PartManifest.MaxPathLength)
    assertEquals(PartManifest.pathProblem(longest), None)
    val fileAtDir = dir.dropRight(1)
    val m = Vector(okFile.copy(path = longest), okFile.copy(path = fileAtDir))
      .foldLeft(partManifest)((pm, f) =>
        pm.copy(files =
          (pm.files :+ f).sortWith((x, y) => Canonical.compareUtf8(x.path, y.path) < 0)
        )
      )
    val msgs = PartManifest.contract.validate(m).map(_.message).filter(_.contains("directory"))
    assertEquals(
      msgs,
      Vector(s"files.path: '$fileAtDir' is listed as a file but is a directory of '$longest'")
    )
  }

  test("the marker files are never listed, and the run manifest is always listed as one row") {
    invalid(
      withFile(okFile.copy(path = PartManifest.SuccessMarker)),
      "a marker file is never listed"
    )
    invalid(withFile(okFile.copy(path = PartManifest.FileName)), "a marker file is never listed")
    invalid(
      partManifest.copy(files =
        partManifest.files.filterNot(_.path == PartManifest.RunManifestFile)
      ),
      "must list _run-manifest.json"
    )
    val rm = partManifest.files.find(_.path == PartManifest.RunManifestFile).get
    invalid(
      partManifest.copy(files =
        partManifest.files.map(f => if (f eq rm) f.copy(rowCount = 2) else f)
      ),
      "exactly one"
    )
    invalid(
      partManifest.copy(files =
        partManifest.files.map(f =>
          if (f eq rm) f.copy(recordSchemaVersion = SchemaVersions.WorkAssignment) else f
        )
      ),
      "must be scholarchain.run-manifest.v1"
    )
    invalid(partManifest.copy(files = Vector.empty), "files: missing")
  }

  test(
    "counts: non-negative, rows <= bytes, a non-empty file has a row, an empty file the empty hash"
  ) {
    invalid(withFile(okFile.copy(rowCount = -1)), "is negative")
    invalid(withFile(okFile.copy(byteCount = -1, rowCount = 0)), "is negative")
    invalid(withFile(okFile.copy(rowCount = 4)), "more rows than bytes")
    invalid(withFile(okFile.copy(rowCount = 0)), "at least one row")
    invalid(withFile(okFile.copy(rowCount = 0, byteCount = 0)), "SHA-256 of zero bytes")
    assertEquals(
      PartManifest.contract.validate(
        withFile(okFile.copy(rowCount = 0, byteCount = 0, sha256 = PartManifest.EmptySha256))
      ),
      Vector.empty
    )
  }

  test("sha256 is 64 lowercase hex; the record schema is a current ScholarChain schema") {
    invalid(withFile(okFile.copy(sha256 = "A" * 64)), "64 lowercase hex")
    invalid(withFile(okFile.copy(sha256 = "a" * 63)), "64 lowercase hex")
    invalid(
      withFile(okFile.copy(recordSchemaVersion = "scholarchain.match-decision.v1")),
      "not a current"
    )
    invalid(withFile(okFile.copy(recordSchemaVersion = "csv")), "not a current")
  }

  test("build sorts files, and JSON round-trips with unknown keys rejected") {
    val built = PartManifest.build(groupingRunId, partManifest.files.reverse)
    assertEquals(built, Right(partManifest))
    val json = Json.write(partManifest)
    assertEquals(Json.decode[PartManifest](json), Right(partManifest))
    assert(Json.decode[PartManifest](json.replaceFirst("\\{", "{\"x\":1,")).isLeft)
    assert(
      Json.decode[PartManifest](json.replace("\"sha256\":", "\"x\":0,\"sha256\":")).isLeft,
      "nested unknown key"
    )
    assert(PartManifest.build(groupingRunId, null).isLeft)
  }
}
