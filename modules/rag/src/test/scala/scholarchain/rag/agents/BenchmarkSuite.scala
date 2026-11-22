package scholarchain.rag.agents

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scholarchain.rag.benchmark.{BenchmarkReport, BenchmarkRunner, BenchmarkSet}

/**
 * RE-P9-02 benchmark-query suite over the rag fixture world (run inside `sbt check` as a test
 * suite; the `sbt benchmarkQueries` alias row is inactive, B10). The committed set is
 * `modules/rag/src/test/resources/benchmarks/rag-fixture-world.v1.json`. Graph and semantic results
 * are reported separately (README Phase 9 gate).
 */
class BenchmarkSuite extends munit.FunSuite {

  private def load(): BenchmarkSet = {
    val in = getClass.getResourceAsStream("/benchmarks/rag-fixture-world.v1.json")
    assert(in != null, "benchmark set resource missing")
    val json =
      try new String(in.readAllBytes(), StandardCharsets.UTF_8)
      finally in.close()
    BenchmarkRunner.parse(json).fold(e => fail(e), identity)
  }

  private def runAll(): BenchmarkReport = {
    val w = new WorkflowWorld
    val wf = w.workflow()
    BenchmarkRunner.run(load(), w.aliases, "bench")((q, text) => wf.answer(q, text))
  }

  private def root: Path = {
    var dir = Paths.get("").toAbsolutePath
    while (dir != null && !Files.isDirectory(dir.resolve("prompts/schemas"))) dir = dir.getParent
    assert(dir != null, "prompts/schemas not found above the working directory")
    dir
  }

  test("the committed set covers the required question kinds and parses under schema v1") {
    val s = load()
    assertEquals(s.schemaVersion, BenchmarkRunner.SchemaVersion)
    val ids = s.cases.map(_.id).toSet
    Vector(
      "ambiguous-name",
      "unknown-name",
      "direct-coauthors",
      "shortest-path",
      "not-connected",
      "bridge-ranking",
      "degree-ranking",
      "temporal-comparison",
      "injection-question",
      "out-of-allowlist"
    ).foreach(i => assert(ids.contains(i), i))
    assert(s.cases.exists(_.category == "semantic"))
    assert(BenchmarkRunner.parse(upickle.default.write(s.copy(schemaVersion = "x"))).isLeft)
  }

  test("benchmark schema file pins the set and expectation keys") {
    val schema = ujson.read(Files.readString(root.resolve(BenchmarkRunner.SchemaFile)))
    assertEquals(schema("$id").str, BenchmarkRunner.SchemaVersion)
    val s = load()
    val json = ujson.read(upickle.default.write(s))
    assertEquals(json.obj.keys.toSet, schema("properties").obj.keys.toSet)
    val caseSchema = schema("properties")("cases")("items")
    assertEquals(json("cases").arr.head.obj.keys.toSet, caseSchema("properties").obj.keys.toSet)
    assertEquals(
      json("cases").arr.head("expect").obj.keys.toSet,
      caseSchema("properties")("expect")("properties").obj.keys.toSet
    )
  }

  test("every benchmark case passes; graph and semantic families reported separately") {
    val report = runAll()
    println(s"benchmark ${report.setVersion}:\n" + report.lines.mkString("\n"))
    assert(report.passed, report.lines.mkString("\n"))
    assertEquals(report.graph.cases, 11)
    assertEquals(report.semantic.cases, 2)
    assert(report.graph.checks > 0 && report.semantic.checks > 0)
  }

  test(
    "negative control: a wrong expected value, verdict, outcome or top subject fails its family"
  ) {
    val s = load()
    def broken(id: String)(f: BenchmarkSet => BenchmarkSet) = {
      val w = new WorkflowWorld
      val wf = w.workflow()
      val one = f(s.copy(cases = s.cases.filter(_.id == id)))
      BenchmarkRunner.run(one, w.aliases, "neg")((q, t) => wf.answer(q, t))
    }
    def edit(
        f: scholarchain.rag.benchmark.BenchmarkExpectation => scholarchain.rag.benchmark.BenchmarkExpectation
    )(b: BenchmarkSet) =
      b.copy(cases = b.cases.map(c => c.copy(expect = f(c.expect))))
    val wrongValue = broken("shortest-path")(
      edit(e => e.copy(verifiedFacts = e.verifiedFacts.map(_.copy(value = "4"))))
    )
    assert(!wrongValue.passed && wrongValue.graph.checksPassed < wrongValue.graph.checks)
    val wrongOutcome = broken("ambiguous-name")(edit(_.copy(outcome = "answered")))
    assert(!wrongOutcome.passed)
    val wrongTop = broken("semantic-profile")(edit(_.copy(semanticTopSubject = Some("{bob}"))))
    assert(!wrongTop.passed && wrongTop.semantic.checksPassed < wrongTop.semantic.checks)
    assertEquals(wrongTop.graph.checksPassed, wrongTop.graph.checks)
  }

  test("the runner is deterministic: two runs over fresh worlds give the same report") {
    assertEquals(runAll(), runAll())
  }
}
