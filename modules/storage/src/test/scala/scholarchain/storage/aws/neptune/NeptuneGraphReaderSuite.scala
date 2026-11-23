package scholarchain.storage.aws.neptune

import java.io.IOException

import scala.collection.mutable
import scala.jdk.CollectionConverters.*

import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.core.document.Document
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.neptunedata.model.{
  ExecuteOpenCypherQueryRequest,
  ExecuteOpenCypherQueryResponse,
  NeptunedataException
}

import scholarchain.storage.model.{
  BoundQuery,
  GraphParamValue,
  GraphQuery,
  GraphRow,
  GraphValue,
  StoreError
}

/** SE-INT-04 (A4, A7): the Neptune GraphReader over a stub seam (no credentials, no network). */
class NeptuneGraphReaderSuite extends munit.FunSuite {
  import NeptuneGraphReaderSuite.*

  private val researcher = "researcher:orcid:0000-0002-1825-0097"
  private val other = "researcher:orcid:0000-0001-5109-3700"

  private def bound(q: GraphQuery, args: (String, GraphParamValue)*): BoundQuery =
    BoundQuery.bind(q, args.toMap, 50).fold(e => fail(e.message), identity)

  private def byId =
    bound(GraphQuery.ResearcherById, "researcherId" -> GraphParamValue.Str(researcher))

  private def collaborators(limit: Int) =
    bound(
      GraphQuery.DirectCollaborators,
      "researcherId" -> GraphParamValue.Str(researcher),
      "windowStartYear" -> GraphParamValue.IntValue(2000),
      "windowEndYear" -> GraphParamValue.IntValue(2010),
      "limit" -> GraphParamValue.IntValue(limit)
    )

  private def reader(stub: Stub, maxRows: Int = 10) =
    NeptuneGraphReader.of(stub, NeptuneReaderSettings.of(maxRows).toOption.get)

  private def row(cols: (String, Document)*): Document = Document.fromMap(cols.toMap.asJava)
  private def rows(rs: Document*): Document = Document.fromList(rs.asJava)

  private val byIdRow = row(
    "researcherId" -> Document.fromString(researcher),
    "anchorNamespace" -> Document.fromString("dblp"),
    "identityStatus" -> Document.fromString("resolved"),
    "memberCount" -> Document.fromNumber(3L)
  )

  test("the template text is sent unchanged and the parameters as one JSON object") {
    val stub = Stub(rows(byIdRow))
    val got = reader(stub).run(byId)
    assertEquals(stub.requests.head.openCypherQuery(), GraphQuery.ResearcherById.text)
    assertEquals(
      ujson.read(stub.requests.head.parameters()),
      ujson.Obj("researcherId" -> ujson.Str(researcher))
    )
    assertEquals(
      got,
      Right(
        Vector(
          GraphRow(
            scala.collection.immutable.SortedMap(
              "anchorNamespace" -> GraphValue.Str("dblp"),
              "identityStatus" -> GraphValue.Str("resolved"),
              "memberCount" -> GraphValue.IntValue(3L),
              "researcherId" -> GraphValue.Str(researcher)
            )
          )
        )
      )
    )
  }

  test("integer parameters are JSON numbers, never strings") {
    val stub = Stub(rows())
    reader(stub).run(collaborators(5))
    val p = ujson.read(stub.requests.head.parameters())
    assertEquals(p("limit"), ujson.Num(5))
    assertEquals(p("windowStartYear"), ujson.Num(2000))
    assertEquals(p("researcherId"), ujson.Str(researcher))
  }

  test("no rows is an empty result; a document null is Null (P)") {
    assertEquals(reader(Stub(rows())).run(byId), Right(Vector.empty))
    val r = row(
      "researcherId" -> Document.fromString(researcher),
      "anchorNamespace" -> Document.fromNull(),
      "identityStatus" -> Document.fromString("resolved"),
      "memberCount" -> Document.fromNumber(1L)
    )
    assertEquals(
      reader(Stub(rows(r))).run(byId).map(_.head.columns("anchorNamespace")),
      Right(GraphValue.Null)
    )
  }

  test("an absent column, an extra column or a non-scalar value is Corrupt") {
    val missing = row("researcherId" -> Document.fromString(researcher))
    val extra = Document.fromMap(
      (byIdRow.asMap().asScala.toMap + ("secret" -> Document.fromString("x"))).asJava
    )
    def withValue(d: Document) =
      Document.fromMap((byIdRow.asMap().asScala.toMap + ("memberCount" -> d)).asJava)
    for (
      bad <- Vector(
        rows(missing),
        rows(extra),
        rows(withValue(Document.fromBoolean(true))),
        rows(withValue(Document.fromNumber(1.5))),
        rows(withValue(Document.fromList(java.util.List.of()))),
        rows(Document.fromString("not a row")),
        Document.fromString("not a list")
      )
    ) {
      val got = reader(Stub(bad)).run(byId)
      assert(got.left.exists(_.isInstanceOf[StoreError.Corrupt]), s"$bad: $got")
    }
    assert(reader(Stub(null)).run(byId).left.exists(_.isInstanceOf[StoreError.Corrupt]))
  }

  test("bounds: more than maxRows is Permanent; more than the query limit is Corrupt") {
    val r = row(
      "collaboratorId" -> Document.fromString(other),
      "jointPublicationCount" -> Document.fromNumber(2L),
      "firstCollaborationYear" -> Document.fromNumber(2001L),
      "lastCollaborationYear" -> Document.fromNumber(2002L),
      "sourceEvidence" -> Document.fromString("s3://x/y")
    )
    assertEquals(reader(Stub(rows(r, r)), maxRows = 2).run(collaborators(2)).map(_.size), Right(2))
    assert(
      reader(Stub(rows(r, r, r)), maxRows = 2)
        .run(collaborators(5))
        .left
        .exists(_.isInstanceOf[StoreError.Permanent])
    )
    assert(
      reader(Stub(rows(r, r)), maxRows = 10)
        .run(collaborators(1))
        .left
        .exists(_.isInstanceOf[StoreError.Corrupt])
    )
  }

  test("errors follow Neptune's retry table; the message never leaks") {
    val cases = Vector(
      ("ConcurrentModificationException", 500, "Transient"),
      ("ThrottlingException", 500, "Transient"),
      ("TooManyRequestsException", 429, "Transient"),
      ("TimeLimitExceededException", 500, "Transient"),
      ("QueryLimitExceededException", 500, "Transient"),
      ("InternalFailureException", 500, "Permanent"),
      ("MalformedQueryException", 400, "Permanent"),
      ("AccessDeniedException", 403, "Permanent"),
      ("ReadOnlyViolationException", 400, "Permanent"),
      ("ClientTimeoutException", 408, "Permanent"),
      ("SomethingNewException", 503, "Transient"),
      ("SomethingNewException", 400, "Permanent")
    )
    for ((code, st, expected) <- cases) {
      val stub = Stub(rows())
      stub.failWith = Some(service(st, code))
      val got = reader(stub).run(byId)
      assertEquals(got.left.map(_.getClass.getSimpleName), Left(expected), code)
      assert(!got.toString.contains(Marker), got.toString)
    }
    val stub = Stub(rows())
    stub.failWith = Some(SdkClientException.create("x", new IOException("Connection reset")))
    assert(reader(stub).run(byId).left.exists(_.isInstanceOf[StoreError.Transient]))
  }

  test("retry table: codes with and without the Exception suffix") {
    assertEquals(NeptuneGraphReader.retryable("FailureByQuery"), Some(true))
    assertEquals(NeptuneGraphReader.retryable("BadRequestException"), Some(false))
    assertEquals(NeptuneGraphReader.retryable(""), None)
  }

  test("every template's columns are its AS aliases") {
    GraphQuery.values.foreach(q => assert(NeptuneGraphReader.columns(q).nonEmpty, q.toString))
    assertEquals(
      NeptuneGraphReader.columns(GraphQuery.SharedWorks),
      Set("workId", "year")
    )
  }

  test("the parameterized-LIMIT templates (live-verification risk, Q12) are exactly these five") {
    assertEquals(
      NeptuneGraphReader.parameterizedLimitTemplates.map(_.toString).toSet,
      Set(
        "DirectCollaborators",
        "SharedWorks",
        "WorksOfResearcher",
        "WorkTopics",
        "ResearcherInstitutions"
      )
    )
    assert(GraphQuery.values.forall(q => !q.text.contains("SKIP")))
  }

  test("settings: endpoint structure and maxRows") {
    def ok(e: String) = NeptuneClientSettings.of("us-east-1", e, 2000, 1000, 3)
    assert(ok("https://db.cluster-ro-abc.us-east-1.neptune.amazonaws.com:8182").isRight)
    assert(ok("https://db.cluster-ro-abc.us-east-1.neptune.amazonaws.com:8182/").isRight)
    for (
      bad <- Vector(
        "http://h:8182",
        "https://h",
        "https://h:8182/path",
        "https://u@h:8182",
        "https://h:8182?q=1",
        "https://h:8182#f",
        "h:8182",
        "",
        null
      )
    ) assert(ok(bad).isLeft, String.valueOf(bad))
    assert(NeptuneClientSettings.of("us-east-1", "https://h:8182", 1000, 1000, 3).isLeft)
    assert(NeptuneClientSettings.of("us-east-1", "https://h:8182", 2000, 1000, 0).isLeft)
    assert(NeptuneReaderSettings.of(0).isLeft)
    assertEquals(
      ok("https://h:8182").map(_.endpoint.getPort),
      Right(8182)
    )
  }

  test("a null query is refused before any call") {
    val stub = Stub(rows())
    assert(reader(stub).run(null).left.exists(_.isInstanceOf[StoreError.Refused]))
    assertEquals(stub.requests.size, 0)
  }
}

object NeptuneGraphReaderSuite {
  val Marker = "SECRET-request-value-7f3a"

  def service(status: Int, code: String): Throwable =
    NeptunedataException
      .builder()
      .statusCode(status)
      .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).errorMessage(Marker).build())
      .message(Marker)
      .build()

  final class Stub(results: Document) extends NeptuneCalls {
    val requests = mutable.ArrayBuffer.empty[ExecuteOpenCypherQueryRequest]
    var failWith: Option[Throwable] = None
    def executeOpenCypherQuery(r: ExecuteOpenCypherQueryRequest) = {
      requests += r
      failWith.foreach(e => throw e)
      ExecuteOpenCypherQueryResponse.builder().results(results).build()
    }
  }
}
