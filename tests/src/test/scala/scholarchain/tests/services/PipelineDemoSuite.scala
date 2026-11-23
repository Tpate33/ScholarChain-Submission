package scholarchain.tests.services

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Paths}
import java.time.Duration

import scholarchain.services.localdemo.{LocalDemo, PipelineDemo}

/**
 * Real archived pipeline data -> existing verified loaders -> loopback query/evidence API. No AWS
 * clients, real model calls, test-world seed or generated collaboration facts.
 */
class PipelineDemoSuite extends munit.FunSuite {
  private def dataRoot = Vector(
    Paths.get("modules/storage/src/test/resources/fixtures"),
    Paths.get("../modules/storage/src/test/resources/fixtures")
  ).find(Files.isDirectory(_)).getOrElse(fail("Phase 6 source fixtures are absent")).toAbsolutePath

  test("missing real-data artifacts refuse startup rather than substituting the synthetic seed") {
    val missing = dataRoot.resolve("does-not-exist")
    assert(PipelineDemo.start(PipelineDemo.Args(missing, 0)).isLeft)
    assert(PipelineDemo.parseArgs(Seq("--port", "0")).isLeft)
    assert(PipelineDemo.parseArgs(Seq("--data-root", missing.toString, "--port", "65536")).isLeft)
  }

  test("a different part manifest cannot be presented as the accepted real-data run") {
    val root = Files.createTempDirectory("pipeline-demo-wrong-parts-")
    val runRoot = root.resolve("phase06-run-1-local")
    val run = Files.createDirectories(runRoot.resolve("run=changed"))
    val loads = Files.createDirectories(runRoot.resolve("load=changed"))
    val parts = Files.writeString(run.resolve("_parts.json"), "{}")
    try {
      val result = PipelineDemo.load(root)
      assert(
        result.left.exists(_.contains("not the accepted Phase 6 dev artifact")),
        result.toString
      )
    } finally {
      Files.delete(parts)
      Files.delete(run)
      Files.delete(loads)
      Files.delete(runRoot)
      Files.delete(root)
    }
  }

  test("accepted Phase 6 real-data run serves a verified query, citation and explain over HTTP") {
    val started = PipelineDemo.start(PipelineDemo.Args(dataRoot, 0)).fold(fail(_), identity)
    try {
      assert(started.loaded.report.complete)
      assertEquals(started.loaded.report.runId, Some(PipelineDemo.AcceptedRunId))
      assertEquals(started.loaded.report.partManifestSha256, Some(PipelineDemo.AcceptedPartsSha256))
      val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
      def request(path: String, body: Option[String] = None): ujson.Value = {
        val builder = HttpRequest
          .newBuilder(URI.create(s"http://127.0.0.1:${started.port}$path"))
          .timeout(Duration.ofSeconds(30))
          .header("Authorization", s"Bearer ${LocalDemo.DemoApiKey}")
        body.foreach(b =>
          builder
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(b))
        )
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(response.statusCode(), 200, response.body())
        assertEquals(
          response.headers().firstValue(PipelineDemo.ProfileHeader._1).orElse(""),
          PipelineDemo.ProfileHeader._2
        )
        ujson.read(response.body())
      }
      val answer = request(
        "/v1/query",
        Some(
          ujson
            .Obj("question" -> "Who are the coauthors of Ailing Zeng?", "mode" -> "sync")
            .render()
        )
      )
      assertEquals(answer("verification")("overall").str, "VERIFIED", answer.render())
      assertEquals(answer("nonProductionModel").bool, true)
      val claims = answer("answer")("claims").arr
      assert(claims.nonEmpty && claims.forall(_("evidenceIds").arr.nonEmpty), answer.render())
      assert(
        answer.render().contains(PipelineDemo.AcceptedRunId),
        "answer must retain actual analysis lineage"
      )
      val edges = answer("answer")("evidenceBundle")("items").arr
        .filter(_("evidenceType").str == "coauthor-edge")
        .map(i => ujson.read(i("text").str))
      assertEquals(edges.size, 3)
      edges.foreach { edge =>
        assertEquals(edge("jointPublicationCount").num, 1.0)
        assertEquals(
          edge("supportingWorkIds").arr.map(_.str).toVector,
          Vector("work:dblp:conf/aaai/ZengCZ023")
        )
      }
      val id = claims.head("evidenceIds").arr.head.str
      val evidence = request("/v1/evidence/" + id)
      assertEquals(evidence("evidenceId").str, id)
      request("/v1/explain/" + answer("queryId").str)
    } finally started.stop()
  }
}
