package scholarchain.storage.aws.neptune

import scala.collection.immutable.SortedMap
import scala.jdk.CollectionConverters.*
import software.amazon.awssdk.core.document.Document
import software.amazon.awssdk.services.neptunedata.model.{
  ExecuteOpenCypherQueryRequest,
  ExecuteOpenCypherQueryResponse
}
import scholarchain.core.{EdgeLabel, NodeLabel}
import scholarchain.storage.TestData
import scholarchain.storage.model.*

class NeptuneGraphWriterSuite extends munit.FunSuite {
  private val provenance =
    GraphProvenance(TestData.runId, TestData.loadId, "graph/researchers.csv", "test.v1")
  private val vertex = StoredVertex(
    TestData.researcherA,
    NodeLabel.Researcher,
    SortedMap("runId" -> GraphValue.Str(TestData.runId), "name" -> GraphValue.Str("O'Neil $name")),
    provenance
  )
  private def row(v: StoredVertex): Document = {
    val props = v.properties.map {
      case (k, GraphValue.Str(s)) => k -> Document.fromString(s)
      case (k, GraphValue.IntValue(n)) => k -> Document.fromNumber(n)
      case (k, _) => k -> Document.fromNull()
    } ++ Map(
      "loadId" -> Document.fromString(v.provenance.loadId),
      "sourcePath" -> Document.fromString(v.provenance.sourcePath),
      "recordSchemaVersion" -> Document.fromString(v.provenance.recordSchemaVersion)
    )
    Document.fromMap(
      Map(
        "id" -> Document.fromString(v.id),
        "labels" -> Document.fromList(java.util.List.of(Document.fromString(v.label.tag))),
        "properties" -> Document.fromMap(props.asJava)
      ).asJava
    )
  }
  private class Stub extends NeptuneCalls {
    var calls = Vector.empty[ExecuteOpenCypherQueryRequest]
    var stored = Option.empty[StoredVertex]
    var ignoreWrite = false
    def executeOpenCypherQuery(r: ExecuteOpenCypherQueryRequest) = {
      calls :+= r
      val rows = if (r.openCypherQuery().startsWith("MERGE")) {
        if (!ignoreWrite) stored = Some(vertex)
        Vector.empty
      } else stored.toVector.map(row)
      ExecuteOpenCypherQueryResponse.builder().results(Document.fromList(rows.asJava)).build()
    }
  }
  test("write sends frozen template and parameter data; rereads full stored provenance") {
    val s = new Stub
    val w = BoundGraphWrite.vertex(vertex).toOption.get
    val writer = NeptuneGraphWriter.of(s)
    assertEquals(writer.write(w), Right(PutOutcome.Written))
    assertEquals(s.calls.size, 3)
    val mutation = s.calls(1)
    assertEquals(mutation.openCypherQuery(), w.template.text)
    assert(!mutation.openCypherQuery().contains("O'Neil"))
    val params = ujson.read(mutation.parameters())
    assertEquals(params("id").str, vertex.id)
    assertEquals(params("properties")("name").str, "O'Neil $name")
    assertEquals(params("provenance")("sourcePath").str, provenance.sourcePath)
    assertEquals(writer.storedVertex(vertex.id), Right(Some(vertex)))
  }
  test("equal ID is skipped and different ID content never mutated") {
    val s = new Stub
    s.stored = Some(vertex)
    val writer = NeptuneGraphWriter.of(s)
    assertEquals(
      writer.write(BoundGraphWrite.vertex(vertex).toOption.get),
      Right(PutOutcome.AlreadyPresent)
    )
    val different =
      vertex.copy(properties = vertex.properties.updated("name", GraphValue.Str("other")))
    assert(
      writer
        .write(BoundGraphWrite.vertex(different).toOption.get)
        .left
        .exists(_.isInstanceOf[StoreError.Conflict])
    )
    assert(!s.calls.exists(_.openCypherQuery().startsWith("MERGE")))
  }
  test("write does not report success if verification is absent") {
    val s = new Stub
    s.ignoreWrite = true
    assert(NeptuneGraphWriter.of(s).write(BoundGraphWrite.vertex(vertex).toOption.get).isLeft)
  }
  test("missing endpoint and unsupported/null properties are refused before mutation") {
    val s = new Stub
    val writer = NeptuneGraphWriter.of(s)
    val bad = vertex.copy(properties = vertex.properties.updated("x", GraphValue.Null))
    assert(writer.write(BoundGraphWrite.vertex(bad).toOption.get).isLeft)
    assertEquals(s.calls.size, 0)
    // Probe for an absent edge, followed by its absent endpoint. No edge template is sent.
    val edge = StoredEdge(
      "gedge:" + TestData.hex("edge"),
      EdgeLabel.CoauthoredWith,
      vertex.id,
      TestData.researcherB,
      vertex.properties,
      provenance
    )
    val bound = BoundGraphWrite.edge(edge)
    assert(bound.isRight, bound.toString)
    assert(writer.write(bound.toOption.get).isLeft)
    assert(!s.calls.exists(_.openCypherQuery().contains("MERGE")))
  }
}
