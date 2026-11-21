package scholarchain.storage

import scholarchain.core.*
import scholarchain.storage.load.{HadoopFiles, UpstreamRuns}
import Fixtures.*
import UpstreamFixture.*

/**
 * SE-P7-07: integrity of the fixtures the human copied on 2026-11-20, within their evidence limits
 * (human-fixture-copy-2026-11-20.md):
 *   - grouping run: every part against the run's own `_parts.json` (and so against the committed
 *     Phase 6 hashes the human compared), its run ID equal to the analysis manifest's
 *     `groupingRunId`;
 *   - curated run: NO historical SHA-256 exists; checked are its run manifest (run ID = the
 *     analysis manifest's `inputRunId`), each stage's `_SUCCESS` and the absence of a run-level
 *     `_SUCCESS`, `_counters.json` counts against the decoded rows, and every Hadoop CRC32 sidecar.
 *     CRC32 detects accidental corruption only; it is not a cryptographic integrity check;
 *   - settings: the pinned SHA-256 of the human record, and the parsed config digest equal to the
 *     analysis run manifest's `configDigest`.
 */
class UpstreamFixtureIntegritySuite extends munit.FunSuite {

  private lazy val analysisManifest: RunManifest =
    Json
      .decode[RunManifest](text(runDir(run1Root).resolve("_run-manifest.json")).stripSuffix("\n"))
      .fold(e => fail(e.toString), identity)

  private def param(k: String) = analysisManifest.parameters(k)
  private val K = AnalysisValidation.AnalysisManifestKeys

  test("settings: pinned SHA-256, and its config digest is the analysis run's") {
    assertEquals(
      Canonical.sha256Hex(bytes(RunSettingsFixture.settingsFile)),
      RunSettingsFixture.SettingsSha256
    )
    assertEquals(RunSettingsFixture.settings.configDigest, analysisManifest.configDigest)
    assertEquals(RunSettingsFixture.settings.configDigest, RunSettingsFixture.RunConfigDigest)
  }

  test("grouping run: every part equals its _parts.json entry; the run is the groupingRunId") {
    val pm = PartManifests
      .decode(text(groupingDir.resolve("_parts.json")))
      .map(PartManifests.asV2)
      .fold(e => fail(e.toString), identity)
    assertEquals(pm.files.size, 5)
    pm.files.foreach { f =>
      val b = bytes(groupingDir.resolve(f.path))
      assertEquals((b.length.toLong, Canonical.sha256Hex(b)), (f.byteCount, f.sha256), f.path)
    }
    assertEquals(grouping.runId, s"run:$GroupingHex")
    assertEquals(grouping.runId, param(K.GroupingRunId))
    // The production reader decoded every row of the four schemas.
    assertEquals(
      (
        grouping.graphInput.researchers.size,
        grouping.graphInput.works.size,
        grouping.authorships.size,
        grouping.graphInput.teams.size
      ),
      (292, 90, 143, 41)
    )
  }

  test("curated run: run manifest bound to inputRunId, stage markers, no run-level _SUCCESS") {
    assertEquals(curated.runId, s"run:$CuratedHex")
    assertEquals(curated.runId, param(K.InputRunId))
    val files = source(curatedDir, curatedBounds).listFiles().fold(e => fail(e.toString), identity)
    assert(!files.contains(PartManifest.SuccessMarker), "no run-level _SUCCESS")
    assert(!files.contains(PartManifest.FileName), "the Hadoop layout has no _parts.json")
    Vector("normalize", "exact-ids", "teams", "blocking", "candidates").foreach { s =>
      assert(files.contains(s"$s/_SUCCESS"), s)
      assert(files.contains(s"$s/_counters.json"), s)
    }
  }

  test("curated run: every file's CRC32 sidecar verifies (CRC32 is not cryptographic)") {
    val src = source(curatedDir, curatedBounds)
    val files = src.listFiles().fold(e => fail(e.toString), identity)
    val data = files.filterNot(_.split('/').last.startsWith("."))
    assertEquals(UpstreamRuns.checkSidecars(src, files), Right(data.size))
    assertEquals(curated.checkedFiles, data.size)
    // A flipped byte is caught by its sidecar.
    val p = "normalize/works/part-r-00000.gz"
    val b = bytes(curatedDir.resolve(p))
    b(b.length / 2) = (b(b.length / 2) ^ 1).toByte
    assert(
      HadoopFiles.crcProblem(b, bytes(curatedDir.resolve(HadoopFiles.sidecarOf(p)))).isDefined
    )
  }

  test("curated run: every _counters.json count equals the decoded rows of its output") {
    val src = source(curatedDir, curatedBounds)
    val files = src.listFiles().fold(e => fail(e.toString), identity)
    // (stage, output directory under the stage, counter)
    val expected = Vector(
      ("normalize", "works", "EMITTED.works"),
      ("normalize", "mentions", "EMITTED.mentions"),
      ("normalize", "profiles", "EMITTED.profiles"),
      ("normalize", "venues", "EMITTED.venues"),
      ("normalize", "institutions", "EMITTED.institutions"),
      ("normalize", "topics", "EMITTED.topics"),
      ("exact-ids", "links", "EMITTED.links"),
      ("teams", "teams", "EMITTED.teams"),
      ("teams", "pairs", "EMITTED_PAIR"),
      ("candidates", "final/researcher", "EMITTED.researcher-candidates"),
      ("candidates", "final/work", "EMITTED.work-candidates")
    )
    expected.foreach { case (stage, out, counter) =>
      val counters =
        UpstreamRuns.stageCounters(src, files, stage).fold(e => fail(e.toString), identity)
      val lines = UpstreamRuns
        .outputLines(src, files, stage, out, upstreamBounds)
        .fold(e => fail(e.toString), identity)
      assertEquals(lines.size.toLong, counters(counter), s"$stage/$out vs $counter")
    }
    assertEquals(
      (
        curated.works.size,
        curated.mentions.size,
        curated.venues.size,
        curated.institutions.size,
        curated.topics.size
      ),
      (90, 286, 64, 60, 30)
    )
    // Every source work carries the curated run as its provenance run.
    assert(curated.works.forall(_.provenance.runId == curated.runId))
  }

  test("bounded inflation: a part inflating beyond the bound is refused, never truncated") {
    val p = "normalize/mentions/part-r-00000.gz"
    val gz = bytes(curatedDir.resolve(p))
    val full = HadoopFiles.gunzip(p, gz, 16L << 20).fold(e => fail(e.toString), identity)
    assert(full.length > 1000)
    HadoopFiles.gunzip(p, gz, (full.length - 1).toLong) match {
      case Left(e) => assert(e.message.contains("inflates to more than"), e.message)
      case Right(_) => fail("a part beyond the bound was accepted")
    }
    assertEquals(HadoopFiles.gunzip(p, gz, full.length.toLong).map(_.length), Right(full.length))
    assert(HadoopFiles.gunzip(p, Array[Byte](1, 2, 3), 100L).isLeft, "not gzip")
  }
}
