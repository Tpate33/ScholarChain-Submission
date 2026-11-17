package scholarchain.spark.graph

import java.nio.file.{Files, Path}

import scala.collection.immutable.SortedMap

import org.apache.spark.sql.SparkSession

import scholarchain.core.*
import scholarchain.spark.analysis.{AnalysisJobConfig, AnalysisRun}
import scholarchain.spark.input.{ReaderLimits, VerifiedArchiveReader, VerifiedInput}
import scholarchain.spark.output.{CoauthorEdgeRows, RunPublisher}
import scholarchain.spark.testkit.{LocalSpark, RunFixtures}
import scholarchain.spark.testkit.RunFixtures.*

/**
 * Coauthor edges v2 on the hand-checkable fixture graph (see [[RunFixtures]]): the published edges
 * equal the hand-computed edges exactly, equal `AnalysisValidation.checkEdges`'s recomputation
 * (empty error vector), and pass `checkRunChain`; counter-tests show the comparison detects a
 * changed edge. The driver collects at most four fixture edges (test only).
 */
class CoauthorEdgeJobSuite extends munit.FunSuite {

  private var tmp: Path = null
  private var spark: SparkSession = null
  private var input: VerifiedInput = null
  private var written: Written = null

  private val limits = ReaderLimits(1000, 6, 10000, 1L << 20, 1L << 16)

  override def beforeAll(): Unit = {
    tmp = Files.createTempDirectory("scholarchain-edges-")
    spark = LocalSpark.start("scholarchain-edge-test", tmp.resolve("spark"))
    written = RunFixtures.write(tmp.resolve("fixture"))
    input =
      VerifiedArchiveReader.read(written.runs, limits).fold(r => fail(r.mkString("\n")), identity)
  }

  override def afterAll(): Unit =
    try if (spark != null) spark.stop()
    finally LocalSpark.deleteTree(tmp)

  private val code = "89abcdef0123456789abcdef0123456789abcdef"
  private def cfg = AnalysisJobConfig(settings, code, 1, SortedMap.empty, 1)

  private def run(out: String) = {
    val root = Files.createDirectories(tmp.resolve(out))
    AnalysisRun
      .runCollaboration(spark, cfg, input, benchmarkPairs(written.groupingRunId), root)
      .fold(e => fail(e.mkString("\n")), identity)
  }

  private def readEdges(paths: Vector[Path]): Vector[CoauthorEdgeV2] =
    spark.read
      .schema(CoauthorEdgeRows.schema)
      .parquet(paths.map(_.toUri.toString)*)
      .collect()
      .toVector
      .map(r => CoauthorEdgeRows.fromRow(r).fold(fail(_), identity))

  private lazy val result = run("out-a")
  private lazy val edges = readEdges(result.published.partsOf(SchemaVersions.CoauthorEdgeV2))

  test("the published edges are exactly the hand-computed edges of the fixture") {
    def key(e: CoauthorEdgeV2) = (e.window, e.researcherA, e.researcherB)
    val got = edges.map(e => key(e) -> (e.publicationsByYear, e.supportingWorkIds)).toMap
    def pair(x: String, y: String) = if (Canonical.compareUtf8(x, y) < 0) (x, y) else (y, x)
    def k(w: TimeWindow, x: String, y: String) = { val (a, b) = pair(x, y); (w, a, b) }
    val expected = Map(
      k(window1, rA, rB) -> (Vector(YearCount(2018, 1), YearCount(2019, 1)), sorted(w1, w2)),
      k(window1, rA, rC) -> (Vector(YearCount(2018, 1)), Vector(w1)),
      k(window1, rB, rC) -> (Vector(YearCount(2018, 1)), Vector(w1)),
      k(window2, rC, rD) -> (Vector(YearCount(2021, 1)), Vector(w3))
    )
    assertEquals(got, expected)
    assertEquals(result.edgeCount, 4L)
    val ab = edges.find(e => key(e) == k(window1, rA, rB)).get
    assertEquals(ab.jointPublicationCount, 2L)
    assertEquals((ab.firstCollaborationYear, ab.lastCollaborationYear), (2018, 2019))
  }

  test(
    "no self-pair, no unresolved mention, no isolated researcher and no yearless work in an edge"
  ) {
    edges.foreach(e => assert(e.researcherA != e.researcherB))
    val endpoints = edges.flatMap(e => Vector(e.researcherA, e.researcherB)).toSet
    assert(!endpoints.contains(u1))
    assert(!endpoints.contains(rE), "E has no coauthor")
    assert(!edges.exists(_.supportingWorkIds.contains(w5)), "w5 has no year: in no window")
    assert(
      !edges.exists(e => e.window == window2 && e.researcherA == rA),
      "A-D only on yearless w5"
    )
    edges.foreach(e => assert(Canonical.compareUtf8(e.researcherA, e.researcherB) < 0))
  }

  test("edges equal AnalysisValidation.checkEdges's recomputation and pass checkRunChain") {
    assertEquals(AnalysisValidation.checkEdges(edges, input.graphInput, settings), Vector.empty)
    val outputs = AnalysisOutputs(
      Vector.empty,
      edges,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty
    )
    assertEquals(
      AnalysisValidation.checkRunChain(
        result.published.manifest,
        input.grouping.manifest,
        outputs,
        settings,
        benchmarkPairs(written.groupingRunId)
      ),
      Vector.empty
    )
    edges.foreach(e => assertEquals(e.lineage, result.lineage))
    edges.foreach(e => assertEquals(e.provenance.runId, result.runId))
  }

  test("counter-tests: a dropped edge or a changed supporting work is detected by checkEdges") {
    val dropped = AnalysisValidation.checkEdges(edges.tail, input.graphInput, settings)
    assert(dropped.exists(_.toString.contains("missing edge")), dropped)
    val ab = edges.find(_.jointPublicationCount == 2L).get
    val changed = ab.copy(supportingWorkIds =
      Vector(w1, w3).sortWith((a, b) => Canonical.compareUtf8(a, b) < 0)
    )
    val errs = AnalysisValidation.checkEdges(
      edges.map(e => if (e == ab) changed else e),
      input.graphInput,
      settings
    )
    assert(errs.exists(_.toString.contains("supportingWorkIds differ")), errs)
  }

  test("per-window summaries count counted works, identified researchers and unresolved mentions") {
    assertEquals(
      result.summaries,
      Vector(
        WindowInputSummary(window1, 2, 3, 1, 3, 4),
        WindowInputSummary(window2, 2, 3, 0, 1, 1)
      )
    )
  }

  test("the published run re-verifies from its bytes, and a changed part byte is refused") {
    val again =
      RunPublisher.verifyPublished(
        spark,
        result.published.dir,
        result.runId,
        limits.maxMetadataBytes
      )
    assert(again.isRight, again)
    assertEquals(
      result.published.dir.getFileName.toString,
      "run=" + result.runId.stripPrefix("run:")
    )
    val part = result.published.partsOf(SchemaVersions.CoauthorEdgeV2).head
    val copy =
      Files.createDirectories(tmp.resolve("tampered")).resolve(result.published.dir.getFileName)
    Files.createDirectories(copy.resolve("edges"))
    Vector("_SUCCESS", "_parts.json", "_run-manifest.json").foreach(n =>
      Files.copy(result.published.dir.resolve(n), copy.resolve(n))
    )
    val bytes = Files.readAllBytes(part)
    bytes(bytes.length / 2) = (bytes(bytes.length / 2) ^ 1).toByte
    Files.write(copy.resolve("edges").resolve(part.getFileName), bytes)
    val bad = RunPublisher.verifyPublished(spark, copy, result.runId, limits.maxMetadataBytes)
    assert(bad.left.exists(_.exists(_.contains("sha256"))), bad)
  }

  test("a second run of the same manifest into the same output root is refused") {
    val again = AnalysisRun.runCollaboration(
      spark,
      cfg,
      input,
      benchmarkPairs(written.groupingRunId),
      result.published.dir.getParent
    )
    assert(again.left.exists(_.exists(_.contains("never overwritten"))), again)
  }
}
