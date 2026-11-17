package scholarchain.spark.analysis

import java.nio.file.{Files, Path}

import scala.collection.immutable.SortedMap

import org.apache.spark.sql.{Row, SparkSession}
import org.apache.spark.sql.types.StructType

import scholarchain.core.*
import scholarchain.spark.graph.Checkpointing
import scholarchain.spark.input.{ReaderLimits, VerifiedArchiveReader, VerifiedInput}
import scholarchain.spark.output.*
import scholarchain.spark.testkit.{LocalSpark, MetricsFixtures, RunFixtures}
import scholarchain.spark.testkit.MetricsFixtures.*

/**
 * Temporal comparison (course item 9, SP-P6-03 step 2C) on the hand-checkable [[MetricsFixtures]]
 * chain: windows 2015-2019 and 2020-2024 (one consecutive pair), and the researcher profiles of the
 * semantic-summary inputs. DEV fixture, unevaluated.
 *
 * Hand derivation (graphs from the fixture comment: 2015-2019 A-B, A-C plus E and F alone, one
 * community {A,B,C}; 2020-2024 the K4 A, B, C, D, one community):
 * {{{
 *   window comparison 2015-2019 -> 2020-2024
 *     researchers in both A B C (3), only before E F (2), only after D (1)
 *     edges in both A-B A-C (2), only before none (0), only after A-D B-C B-D C-D (4)
 *     overlap 0 years, equal lengths (4 = 4), both windows converged; m8 is a counted work
 *     without a year: limitations [grouping-unevaluated, works-without-year-excluded]
 *   temporal changes (P, C, A_r, M, componentSize, inLargest, communitySize, cross, reached, pc,
 *   coreness; retained, gained, lost; community overlap)
 *     A in-both  from (2,2,2.5,3,3,true,3,0,0,0.0,1)  to (2,3,2.0,3,4,true,4,0,0,0.0,3)  2 1 0  3
 *     B in-both  from (1,1,2.0,2,3,true,3,0,0,0.0,1)  to (3,3,7/3,3,4,true,4,0,0,0.0,3)  1 2 0  3
 *     C in-both  from (1,1,3.0,3,3,true,3,0,0,0.0,1)  to (2,3,3.0,3,4,true,4,0,0,0.0,3)  1 2 0  3
 *     D only-to                                       to (3,3,7/3,3,4,true,4,0,0,0.0,3)  0 3 0  -
 *     E only-from from (1,0,2.0,2,1,false,1,0,0,0.0,0)                                   0 0 0  -
 *     F only-from from (1,0,0.0,0,1,false,1,0,0,0.0,0)                                   0 0 0  -
 * }}}
 * (A's coauthors B, C before and B, C, D after: B and C retained, D gained; the community overlap
 * of {A,B,C} and {A,B,C,D} is 3.) Analysis windows: 2015-2019 4 counted works (m1-m4), 1 without a
 * year (m8), 5 researchers, 2 edges, 3 components, 3 communities, 5 unresolved occurrences;
 * 2020-2024 4 (m5, m6, m7, m9), 1, 4, 6, 1, 1, 2.
 */
class TemporalRunSuite extends munit.FunSuite {

  private var tmp: Path = null
  private var spark: SparkSession = null
  private var written: RunFixtures.Written = null
  private var input: VerifiedInput = null

  private val limits = ReaderLimits(1000, 6, 10000, 1L << 20, 1L << 16)
  private val settings = RunFixtures.settings
  private val w1 = RunFixtures.window1
  private val w2 = RunFixtures.window2

  override def beforeAll(): Unit = {
    tmp = Files.createTempDirectory("scholarchain-temporal-run-")
    spark = LocalSpark.start("scholarchain-temporal-run-test", tmp.resolve("spark"))
    written = RunFixtures.write(tmp.resolve("fixture"), MetricsFixtures.spec())
    input =
      VerifiedArchiveReader.read(written.runs, limits).fold(r => fail(r.mkString("\n")), identity)
  }

  override def afterAll(): Unit =
    try if (spark != null) spark.stop()
    finally LocalSpark.deleteTree(tmp)

  private def pairs = RunFixtures
    .benchmarkPairs(written.groupingRunId)
    .copy(pairs = Vector(RunFixtures.sorted(rA, rD) match {
      case Vector(a, b) => BenchmarkPair(a, b)
      case other => fail(s"pair $other")
    }))

  private lazy val result: CollaborationRunResult =
    AnalysisRun
      .runCollaboration(
        spark,
        AnalysisJobConfig(
          settings,
          "abcdef0123456789abcdef0123456789abcdef01",
          1,
          SortedMap.empty,
          1,
          Checkpointing(tmp.resolve("checkpoints").toString, 2)
        ),
        input,
        pairs,
        Files.createDirectories(tmp.resolve("out"))
      )
      .fold(e => fail(e.mkString("\n")), identity)

  private def read[T](label: String, schema: StructType, from: Row => Either[String, T]) =
    spark.read
      .schema(schema)
      .parquet(result.published.parquetPartsOf(label).map(_.toUri.toString)*)
      .collect()
      .toVector
      .map(r => from(r).fold(fail(_), identity))

  private lazy val comparisons = read(
    SchemaVersions.WindowComparison,
    WindowComparisonRows.schema,
    WindowComparisonRows.fromRow
  )
  private lazy val changes = read(
    SchemaVersions.ResearcherTemporalChange,
    TemporalChangeRows.schema,
    TemporalChangeRows.fromRow
  )
  private lazy val windows =
    read(SchemaVersions.AnalysisWindow, AnalysisWindowRows.schema, AnalysisWindowRows.fromRow)
      .sortBy(_.ordinal)
  private lazy val summaries =
    read(SchemaVersions.SemanticSummaryInput, SummaryInputRows.schema, SummaryInputRows.fromRow)

  private def outputs = AnalysisOutputs(
    read(
      SchemaVersions.ResearcherMetricsV2,
      ResearcherMetricsRows.schema,
      ResearcherMetricsRows.fromRow
    ),
    read(SchemaVersions.CoauthorEdgeV2, CoauthorEdgeRows.schema, CoauthorEdgeRows.fromRow),
    read(
      SchemaVersions.ComponentMembership,
      ComponentMembershipRows.schema,
      ComponentMembershipRows.fromRow
    ),
    read(
      SchemaVersions.CommunityMembership,
      CommunityMembershipRows.schema,
      CommunityMembershipRows.fromRow
    ),
    read(SchemaVersions.BridgeMeasure, BridgeMeasureRows.schema, BridgeMeasureRows.fromRow),
    read(SchemaVersions.Coreness, CorenessRows.schema, CorenessRows.fromRow),
    read(SchemaVersions.EvidencePath, EvidencePathRows.schema, EvidencePathRows.fromRow),
    windows,
    summaries,
    windowStructures = read(
      SchemaVersions.WindowStructure,
      WindowStructureRows.schema,
      WindowStructureRows.fromRow
    ),
    windowComparisons = comparisons,
    temporalChanges = changes
  )

  private def st(
      p: Long,
      c: Long,
      a: Double,
      m: Long,
      comp: Long,
      largest: Boolean,
      comm: Long,
      core: Long
  ) = ResearcherWindowState(p, c, a, m, comp, largest, comm, 0L, 0L, 0.0, core)

  test("the window comparison: hand counts and the stated limitations") {
    assertEquals(comparisons.size, 1)
    val c = comparisons.head
    assertEquals((c.fromWindow, c.toWindow, c.ordinal), (w1, w2, 0))
    assertEquals(
      (
        c.researchersInBoth,
        c.researchersOnlyFrom,
        c.researchersOnlyTo,
        c.edgesInBoth,
        c.edgesOnlyFrom,
        c.edgesOnlyTo
      ),
      (3L, 2L, 1L, 2L, 0L, 4L)
    )
    assertEquals(
      c.limitations,
      Vector(
        ComparisonLimitation.GroupingUnevaluated,
        ComparisonLimitation.WorksWithoutYearExcluded
      )
    )
    assertEquals((c.overlapYears, c.inclusionRuleCompatible), (0, true))
    assertEquals(c.provenance.sourceRecordId, "window-pair:2015-2019:2020-2024")
    assertEquals(result.step2C.windowComparisons, comparisons)
    assertEquals(result.step2C.worksWithoutYear, 1L)
  }

  test("temporal changes: retained, gained and lost coauthors; one-window researchers") {
    val byR = changes.map(c => c.researcherId -> c).toMap
    assertEquals(byR.keySet, Set(rA, rB, rC, rD, rE, rF))
    def row(r: String) = {
      val c = byR(r)
      (
        c.presence,
        c.from,
        c.to,
        c.retainedCoauthors,
        c.gainedCoauthors,
        c.lostCoauthors,
        c.communityOverlap
      )
    }
    import TemporalPresence.*
    assertEquals(
      row(rA),
      (
        InBoth,
        Some(st(2, 2, 2.5, 3, 3, true, 3, 1)),
        Some(st(2, 3, 2.0, 3, 4, true, 4, 3)),
        2L,
        1L,
        0L,
        Some(3L)
      )
    )
    assertEquals(
      row(rB),
      (
        InBoth,
        Some(st(1, 1, 2.0, 2, 3, true, 3, 1)),
        Some(st(3, 3, 7.0 / 3.0, 3, 4, true, 4, 3)),
        1L,
        2L,
        0L,
        Some(3L)
      )
    )
    assertEquals(
      row(rC),
      (
        InBoth,
        Some(st(1, 1, 3.0, 3, 3, true, 3, 1)),
        Some(st(2, 3, 3.0, 3, 4, true, 4, 3)),
        1L,
        2L,
        0L,
        Some(3L)
      )
    )
    assertEquals(
      row(rD),
      (OnlyTo, None, Some(st(3, 3, 7.0 / 3.0, 3, 4, true, 4, 3)), 0L, 3L, 0L, None)
    )
    assertEquals(
      row(rE),
      (OnlyFrom, Some(st(1, 0, 2.0, 2, 1, false, 1, 0)), None, 0L, 0L, 0L, None)
    )
    assertEquals(
      row(rF),
      (OnlyFrom, Some(st(1, 0, 0.0, 0, 1, false, 1, 0)), None, 0L, 0L, 0L, None)
    )
    assertEquals(result.step2C.temporalChangeCount, 6L)
  }

  test("checkTemporal accepts the run's records; changed records are detected") {
    val g = input.graphInput
    def check(cs: Vector[WindowComparison], ch: Vector[ResearcherTemporalChange]) =
      StructureValidation.checkTemporal(cs, ch, outputs, g, settings)
    assertEquals(check(comparisons, changes), Vector.empty)
    val a = changes.find(_.researcherId == rA).get
    val more =
      changes.map(c => if (c == a) c.copy(retainedCoauthors = 3L, gainedCoauthors = 0L) else c)
    assert(check(comparisons, more).exists(_.toString.contains("retainedCoauthors")))
    val c = comparisons.head
    val unstated = Vector(c.copy(limitations = Vector(ComparisonLimitation.GroupingUnevaluated)))
    assert(check(unstated, changes).exists(_.toString.contains("limitations")))
    assert(
      check(comparisons, changes.filterNot(_.researcherId == rE))
        .exists(_.toString.contains("no change record"))
    )
  }

  test("analysis windows: hand counts; checkWindows accepts them") {
    assertEquals(
      windows.map(w =>
        (
          w.window,
          w.countedWorkCount,
          w.countedWorksWithoutYear,
          w.researcherCount,
          w.edgeCount,
          w.componentCount,
          w.communityCount,
          w.unresolvedCoauthorOccurrences
        )
      ),
      Vector((w1, 4L, 1L, 5L, 2L, 3L, 3L, 5L), (w2, 4L, 1L, 4L, 6L, 1L, 1L, 2L))
    )
    assertEquals(
      AnalysisValidation.checkWindows(windows, outputs, input.graphInput, settings),
      Vector.empty
    )
  }

  test("researcher profiles: A's hand facts, M_r cites its works, validated") {
    val a = summaries
      .find(s =>
        s.documentType == DocumentType.ResearcherProfile && s.subjectId == rA && s.window == w1
      )
      .getOrElse(fail("no profile of A in 2015-2019"))
    val values = a.facts.map(f => f.name -> f.value).toMap
    assertEquals(
      Vector(
        "publicationCount",
        "distinctCoauthorCount",
        "totalCoauthorOccurrences",
        "unresolvedCoauthorOccurrences",
        "averageCoauthorsPerPaper",
        "maximumCoauthorsOnOnePaper",
        "weightedDegree",
        "componentSize",
        "communitySize",
        "coreness"
      ).map(values),
      Vector("2", "2", "5", "3", "2.5", "3", "2", "3", "3", "1")
    )
    val m = a.facts.find(_.name == "maximumCoauthorsOnOnePaper").get
    assertEquals(
      m.evidence,
      Vector(
        SummaryEvidenceRef(SummaryEvidenceKind.ResearcherMetrics, rA),
        SummaryEvidenceRef(SummaryEvidenceKind.CountedWork, m2)
      )
    )
    assertEquals(a.identity, Some(MetricsFixtures.expectedIdentity(rA)))
    assertEquals(
      AnalysisValidation.checkSummaryInputs(summaries, outputs, input.graphInput, settings),
      Vector.empty
    )
    // 9 profiles, 4 communities (3 + 1), 1 stored path ([A, D] in 2020-2024).
    assertEquals(summaries.size, 9 + 4 + 1)
  }
}
