package scholarchain.core

import java.nio.charset.StandardCharsets

import AssignmentFixtures.{groupingManifest, groupingRunId, observedRun, partManifest, runFiles}

/**
 * part-manifest.v2 (per-format integrity), the version-dispatching reader, v1 compatibility, and
 * the C7 store load manifest. No file I/O: every file is a synthetic byte array.
 */
class PartManifestV2Suite extends munit.FunSuite {

  private def utf8(s: String): Array[Byte] = s.getBytes(StandardCharsets.UTF_8)

  private def refused(errs: Vector[ValidationError], fragment: String): Unit =
    assert(
      errs.exists(_.message.contains(fragment)),
      s"expected a refusal containing '$fragment', got ${errs.map(_.message)}"
    )

  /** A synthetic Parquet-like binary: magic, binary bytes with no final LF, magic. */
  val parquetBytes: Array[Byte] =
    utf8("PAR1") ++ Array[Byte](0, 1, 2, '\n'.toByte, 3, -1, 10, 0, 0, 0) ++ utf8("PAR1")
  val ParquetPath = "metrics/part-00000.parquet"
  val JsonlPath = "edges/part-00000.jsonl"
  val jsonlBytes: Array[Byte] = utf8("{\"a\":1}\n{\"a\":2}\n")
  val runManifestBytes: Array[Byte] = utf8(Json.write(groupingManifest) + "\n")

  private def obs(path: String, bytes: Array[Byte]) = ObservedFile.of(path, bytes)

  val parquetFile: PartFileV2 = {
    val o = obs(ParquetPath, parquetBytes)
    PartFileV2(
      ParquetPath,
      SchemaVersions.ResearcherMetricsV2,
      PartFormat.Parquet,
      6L, // from the Parquet footer: not the LF count of the bytes (2, both inside binary data)
      RowCountSource.ParquetFooter,
      o.byteCount,
      o.sha256
    )
  }
  val jsonlFile: PartFileV2 = {
    val o = obs(JsonlPath, jsonlBytes)
    PartFileV2(
      JsonlPath,
      SchemaVersions.CoauthorEdgeV2,
      PartFormat.Jsonl,
      o.rowCount,
      RowCountSource.LfCount,
      o.byteCount,
      o.sha256
    )
  }
  val runManifestFile: PartFileV2 = {
    val o = obs(PartManifest.RunManifestFile, runManifestBytes)
    PartFileV2(
      PartManifest.RunManifestFile,
      SchemaVersions.RunManifest,
      PartFormat.Jsonl,
      1,
      RowCountSource.LfCount,
      o.byteCount,
      o.sha256
    )
  }

  val manifest: PartManifestV2 =
    Fixtures.ok(
      PartManifestV2.build(groupingRunId, Vector(parquetFile, runManifestFile, jsonlFile))
    )

  val observed: Vector[ObservedFile] = Vector(
    obs(ParquetPath, parquetBytes),
    obs(JsonlPath, jsonlBytes),
    obs(PartManifest.RunManifestFile, runManifestBytes),
    obs(PartManifest.FileName, utf8(Json.write(manifest) + "\n")),
    obs(PartManifest.SuccessMarker, Array.emptyByteArray)
  )

  private def verify(o: Vector[ObservedFile]) =
    PartManifestVerifier.verifyV2(manifest, groupingRunId, o)
  private def replaced(path: String, bytes: Array[Byte]) =
    observed.map(o => if (o.path == path) obs(path, bytes) else o)

  test("a binary part that is not newline-terminated passes when bytes and sha256 match") {
    val o = obs(ParquetPath, parquetBytes)
    assert(!o.endsWithLf, "the fixture binary does not end in LF")
    assertNotEquals(o.rowCount, parquetFile.rowCount, "LF bytes are not Parquet rows")
    assertEquals(verify(observed), Vector.empty)
    assertEquals(PartManifestV2.checkRunManifest(manifest, groupingManifest), Vector.empty)
  }

  test("the same binary described by v1 is refused by the v1 newline and row rules") {
    // Why v2 exists: v1 can only describe LF-terminated rows.
    val v1 = PartManifest(
      SchemaVersions.PartManifest,
      groupingRunId,
      Vector(
        PartFile(
          PartManifest.RunManifestFile,
          SchemaVersions.RunManifest,
          1,
          runManifestFile.byteCount,
          runManifestFile.sha256
        ),
        PartFile(
          ParquetPath,
          SchemaVersions.ResearcherMetricsV2,
          6,
          parquetFile.byteCount,
          parquetFile.sha256
        )
      )
    )
    val errs =
      PartManifestVerifier.verify(v1, groupingRunId, observed.filterNot(_.path == JsonlPath))
    refused(errs, "a non-empty file must end in LF")
    refused(errs, "rowCount: listed 6, observed 2")
  }

  test("a JSONL part still fails on a missing final newline (v2)") {
    val cut = jsonlBytes.dropRight(1)
    val errs = verify(replaced(JsonlPath, cut))
    refused(errs, "a non-empty file must end in LF")
    refused(errs, "byteCount: listed")
    refused(errs, "sha256: listed")
  }

  test("a JSONL part is still compared by LF row count (v2)") {
    val m = manifest.copy(files =
      manifest.files.map(f => if (f.path == JsonlPath) f.copy(rowCount = 3) else f)
    )
    val errs = PartManifestVerifier.verifyV2(m, groupingRunId, observed)
    refused(errs, "rowCount: listed 3, observed 2")
  }

  test("a binary part is refused when its bytes or sha256 differ") {
    val flipped = parquetBytes.clone()
    flipped(6) = (flipped(6) ^ 0x01).toByte
    val same = verify(replaced(ParquetPath, flipped))
    assertEquals(same.size, 1, same)
    refused(same, "sha256: listed")
    val truncated = verify(replaced(ParquetPath, parquetBytes.dropRight(4)))
    refused(truncated, "byteCount: listed")
    refused(truncated, "sha256: listed")
    assert(!truncated.exists(_.message.contains("rowCount")), "binary rows are never line-counted")
    assert(!truncated.exists(_.message.contains("end in LF")), "no newline rule for binary files")
  }

  test("file set rules are those of v1: missing, extra, duplicate and marker files") {
    refused(verify(observed.filterNot(_.path == ParquetPath)), "listed file missing")
    refused(verify(observed :+ obs("metrics/part-00001.parquet", parquetBytes)), "extra file")
    refused(verify(observed.filterNot(_.path == PartManifest.SuccessMarker)), "marker file missing")
    refused(verify(observed :+ observed.head), "observed more than once")
    refused(
      PartManifestVerifier.verifyV2(manifest, Fixtures.runId, observed),
      "the directory holds run"
    )
    assertEquals(
      PartManifestVerifier.verifyV2(null, groupingRunId, observed),
      Vector(ValidationError.MissingField("partManifest"))
    )
  }

  private def invalid(m: PartManifestV2, fragment: String): Unit =
    refused(PartManifestV2.contract.validate(m), fragment)
  private def withFile(f: PartFileV2): PartManifestV2 =
    manifest.copy(files =
      (manifest.files.filterNot(_.path == f.path) :+ f)
        .sortWith((a, b) => Canonical.compareUtf8(a.path, b.path) < 0)
    )

  test(
    "row-count sources: lf-count for jsonl; parquet-footer or writer for parquet; writer for csv"
  ) {
    invalid(
      withFile(parquetFile.copy(rowCountSource = RowCountSource.LfCount)),
      "not a row-count source of parquet"
    )
    invalid(
      withFile(jsonlFile.copy(rowCountSource = RowCountSource.Writer)),
      "not a row-count source of jsonl"
    )
    val csv = PartFileV2(
      "neptune/vertices.csv",
      SchemaVersions.GraphNode,
      PartFormat.Csv,
      2,
      RowCountSource.Writer,
      30,
      "a" * 64
    )
    assertEquals(PartManifestV2.contract.validate(withFile(csv)), Vector.empty)
    invalid(
      withFile(csv.copy(rowCountSource = RowCountSource.ParquetFooter)),
      "not a row-count source of csv"
    )
    assertEquals(
      PartManifestV2.contract.validate(
        withFile(parquetFile.copy(rowCountSource = RowCountSource.Writer))
      ),
      Vector.empty
    )
  }

  test(
    "per-format shape rules: Parquet minimum size, empty Parquet rows, JSONL rows, run manifest"
  ) {
    invalid(
      withFile(parquetFile.copy(byteCount = PartManifestV2.ParquetMinimumBytes - 1)),
      s"at least ${PartManifestV2.ParquetMinimumBytes} bytes"
    )
    assertEquals(
      PartManifestV2.contract.validate(withFile(parquetFile.copy(rowCount = 0))),
      Vector.empty,
      "a Parquet file with a schema and no rows is valid"
    )
    invalid(withFile(jsonlFile.copy(rowCount = 0)), "at least one row")
    invalid(withFile(jsonlFile.copy(rowCount = jsonlFile.byteCount + 1)), "more rows than bytes")
    invalid(
      withFile(
        runManifestFile.copy(format = PartFormat.Parquet, rowCountSource = RowCountSource.Writer)
      ),
      "must be jsonl"
    )
    invalid(manifest.copy(files = manifest.files.reverse), "sorted by UTF-8 bytes")
    invalid(withFile(parquetFile.copy(path = "a/../b.parquet")), "'.' or '..'")
    invalid(withFile(parquetFile.copy(recordSchemaVersion = "parquet")), "not a current")
  }

  test("v2 JSON round-trips; unknown keys, format tags and versions are rejected") {
    val json = Json.write(manifest)
    assertEquals(Json.decode[PartManifestV2](json), Right(manifest))
    assert(Json.decode[PartManifestV2](json.replace("\"parquet\"", "\"orc\"")).isLeft)
    assert(Json.decode[PartManifestV2](json.replaceFirst("\\{", "{\"x\":1,")).isLeft)
    assert(
      Json
        .decode[PartManifest](json)
        .left
        .exists(_.exists(_.isInstanceOf[ValidationError.UnsupportedSchemaVersion])),
      "the v1 contract still accepts exactly v1"
    )
  }

  test("PartManifests reads either version and verifies each with its own rules") {
    val v1Json = Json.write(partManifest)
    val v1 = PartManifests.decode(v1Json)
    assertEquals(v1, Right(Left(partManifest)))
    assertEquals(PartManifests.verify(v1.toOption.get, groupingRunId, observedRun), Vector.empty)
    val v2 = PartManifests.decode(Json.write(manifest))
    assertEquals(v2, Right(Right(manifest)))
    assertEquals(PartManifests.verify(v2.toOption.get, groupingRunId, observed), Vector.empty)
    assert(
      PartManifests
        .decode(v1Json.replace("part-manifest.v1", "part-manifest.v9"))
        .left
        .exists(_.exists(_.isInstanceOf[ValidationError.UnsupportedSchemaVersion]))
    )
    assert(PartManifests.decode("[]").isLeft)
    assertEquals(
      PartManifests.readable,
      Vector(SchemaVersions.PartManifest, SchemaVersions.PartManifestV2)
    )
  }

  test("v1 compatibility: the v2 view of a v1 manifest verifies exactly as v1") {
    val lifted = PartManifestV2.fromV1(partManifest)
    assertEquals(PartManifestV2.contract.validate(lifted), Vector.empty)
    assert(
      lifted.files.forall(f =>
        f.format == PartFormat.Jsonl && f.rowCountSource == RowCountSource.LfCount
      )
    )
    val path = "researchers/part-00000.jsonl"
    val bytes = runFiles.find(_._1 == path).get._3
    for (
      o <- Vector(
        observedRun,
        observedRun.map(x => if (x.path == path) ObservedFile.of(path, bytes.dropRight(3)) else x),
        observedRun.filterNot(_.path == path)
      )
    )
      assertEquals(
        PartManifestVerifier.verifyV2(lifted, groupingRunId, o),
        PartManifestVerifier.verify(partManifest, groupingRunId, o)
      )
  }

  // ---------------------------------------------------------------- C7 store load manifest

  private val manifestSha = Canonical.sha256Hex(utf8(Json.write(manifest) + "\n"))

  private val load: StoreLoadManifest = Fixtures.ok(
    StoreLoadManifest.build(
      TargetStore.S3,
      Right(manifest),
      manifestSha,
      Map(ParquetPath -> "researcher-metrics", JsonlPath -> "coauthor-edges")
    )
  )

  test("C7: a load manifest names parts of the part manifest, with its ID formula") {
    assertEquals(StoreLoadManifest.contract.validate(load), Vector.empty)
    assertEquals(load.items.map(_.path), Vector(JsonlPath, ParquetPath))
    assertEquals(load.items.find(_.path == ParquetPath).map(_.rowCount), Some(6L))
    assertEquals(StoreLoadManifest.checkAgainst(load, Right(manifest), manifestSha), Vector.empty)
    // Repair 01 (CR-P6-13 finding 3): v2 formula, (path, role) pairs sorted by path.
    val encoding = Canonical.encode(
      Vector(
        "scholarchain.store-load.v2",
        "s3",
        groupingRunId,
        manifestSha,
        "2",
        JsonlPath,
        "coauthor-edges",
        ParquetPath,
        "researcher-metrics"
      )
    )
    assertEquals(load.loadId, "load:" + Canonical.sha256Hex(encoding))
    val json = Json.write(load)
    assertEquals(Json.decode[StoreLoadManifest](json), Right(load))
    assert(!json.contains("s3://"), "no bucket or endpoint in a load manifest")
  }

  test("C7: a load is refused against changed part-manifest bytes or a changed part entry") {
    refused(
      StoreLoadManifest.checkAgainst(load, Right(manifest), "0" * 64),
      "the observed _parts.json has sha256"
    )
    val changed = manifest.copy(files =
      manifest.files.map(f => if (f.path == ParquetPath) f.copy(rowCount = 7) else f)
    )
    refused(
      StoreLoadManifest.checkAgainst(load, Right(changed), manifestSha),
      "differs from its part manifest entry"
    )
    refused(
      StoreLoadManifest
        .checkAgainst(load, Right(manifest.copy(runId = Fixtures.runId)), manifestSha),
      "the part manifest is for run"
    )
    refused(
      StoreLoadManifest.checkAgainst(load, Left(partManifest), manifestSha),
      "not listed in the part manifest"
    )
    refused(
      StoreLoadManifest.checkAgainst(
        load,
        Right(manifest.copy(files = manifest.files.filterNot(_.path == JsonlPath))),
        manifestSha
      ),
      "not listed in the part manifest"
    )
    assert(
      StoreLoadManifest
        .build(TargetStore.S3, Right(manifest), manifestSha, Map("nope.jsonl" -> "x"))
        .isLeft
    )
  }

  test("C7: Neptune bulk load takes CSV only; markers and the run manifest are never loaded") {
    val nept = StoreLoadManifest.build(
      TargetStore.NeptuneBulkLoad,
      Right(manifest),
      manifestSha,
      Map(ParquetPath -> "researcher-vertices")
    )
    assert(
      nept.left.exists(_.exists(_.message.contains("neptune-bulk-load does not load parquet"))),
      nept
    )
    refused(
      StoreLoadManifest.contract.validate(
        load.copy(items = load.items :+ load.items.head.copy(path = PartManifest.RunManifestFile))
      ),
      "never loaded"
    )
    refused(
      StoreLoadManifest.contract.validate(load.copy(items = load.items.reverse)),
      "sorted by UTF-8 bytes"
    )
    refused(
      StoreLoadManifest.contract.validate(load.copy(loadId = "load:" + "0" * 64)),
      "expected load:"
    )
    refused(StoreLoadManifest.contract.validate(load.copy(items = Vector.empty)), "items: missing")
  }
}
