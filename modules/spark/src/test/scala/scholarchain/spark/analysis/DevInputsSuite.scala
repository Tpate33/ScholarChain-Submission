package scholarchain.spark.analysis

import scholarchain.core.*
import scholarchain.spark.testkit.{LocalSpark, RunFixtures}

/**
 * The committed dev inputs under src/test/resources/scholarchain/spark/dev-run-01 (no archive
 * access): the benchmark pair list (`scholarchain.benchmark-pairs.v1`, bound to the dev grouping
 * run by human decision) and the dev analysis configuration; plus the loader's refusals.
 */
class DevInputsSuite extends munit.FunSuite {

  private val devGroupingRun =
    "run:84dd896fda43977b934008f10955191e78c2b93b80ec57c00f55dbb137dbd7d2"

  private def text = LocalSpark.resourceText(LocalSpark.DevBenchmarkPairsResource)

  test("the committed benchmark pair list is canonical, valid and bound to the dev grouping run") {
    val l = BenchmarkPairs.parse(text).fold(e => fail(e.mkString("\n")), identity)
    assertEquals(l.schemaVersion, SchemaVersions.BenchmarkPairList)
    assertEquals(l.listVersion, "benchmark-pairs-dev.v1")
    assertEquals(l.provenance.runId, devGroupingRun)
    assertEquals(l.pairs.size, 5)
    val ids = l.pairs.flatMap(p => Vector(p.researcherA, p.researcherB)).toSet
    assertEquals(BenchmarkPairs.bindingProblems(l, devGroupingRun, ids), Vector.empty)
    println(s"SP-P6-02 benchmarkPairsDigest=${BenchmarkPairList.digest(l)}")
  }

  test("the loader refuses non-canonical text, another grouping run and unidentified researchers") {
    assert(BenchmarkPairs.parse(text.replace(",\"pairs\"", ", \"pairs\"")).isLeft)
    assert(BenchmarkPairs.parse(text.stripSuffix("\n")).isLeft)
    val l = BenchmarkPairs.parse(text).toOption.get
    val ids = l.pairs.flatMap(p => Vector(p.researcherA, p.researcherB)).toSet
    assert(
      BenchmarkPairs
        .load(text, "run:" + "0" * 64, ids)
        .left
        .exists(_.exists(_.contains("provenance.runId")))
    )
    assert(
      BenchmarkPairs
        .load(text, devGroupingRun, ids - l.pairs.head.researcherA)
        .left
        .exists(_.exists(_.contains("not an identified researcher")))
    )
    // A list whose pairs are out of order fails its record contract.
    val swapped = Json.write(l.copy(pairs = l.pairs.reverse)) + "\n"
    assert(BenchmarkPairs.parse(swapped).isLeft)
  }

  test("the dev analysis configuration resolves with explicit windows and Parquet export") {
    val s = LocalSpark.devSettings()
    assertEquals(s.windows, Vector(TimeWindow(1970, 2026), TimeWindow(2020, 2024)))
    assertEquals(s.graphPolicyVersion, "sc-graph.v1")
    assertEquals(s.identityVersion, "v1")
    assertEquals(s.exportFormat, PartFormat.Parquet)
    assertEquals(s.pathBounds, PathBounds(4, 3, 3))
    // SP-P6-03 step 2C: the path-length estimate is enabled in the dev configuration.
    assertEquals(
      s.pathLengthSampling,
      Some(scholarchain.core.config.PathLengthSampling(8, 20261117L, 3))
    )
    println(s"SP-P6-02 dev analysis configDigest=${s.configDigest}")
  }

  test("the fixture grouping run's identified researchers come from its assignments") {
    val g = "run:" + "9" * 64
    val ids = BenchmarkPairs.identifiedOf(RunFixtures.researchers(g))
    assertEquals(
      ids,
      Set(RunFixtures.rA, RunFixtures.rB, RunFixtures.rC, RunFixtures.rD, RunFixtures.rE)
    )
  }
}
