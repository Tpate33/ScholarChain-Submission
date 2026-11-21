package scholarchain.storage

import java.nio.file.Path

import scholarchain.core.{CanonicalGraph, EdgeLabel, NodeLabel, StoreLoadManifest, TargetStore}
import scholarchain.storage.fake.InMemoryKeyValueStore
import scholarchain.storage.load.*
import scholarchain.storage.model.{Bytes, GraphValue, StoreError}
import scholarchain.storage.port.KeyValueReader
import Fixtures.*

/**
 * SE-P7-05 B1, B2, B7 (checklist N2, N3, G-ID): the Neptune CSV row contracts and graph IDs on the
 * real fixture, and one refusal per corruption class. Each corrupted copy is resealed
 * ([[Fixtures.reseal]]), so it passes every structural check and only the row is wrong.
 */
class NeptuneRowsSuite extends munit.FunSuite {

  private def ledger = KeyValueReader.readOnly(new InMemoryKeyValueStore(100))

  private def verify(run: Path, load: Path): VerificationReport =
    LoadVerifier.verify(source(run), source(load, loadBounds), ledger)

  private def neptune(load: Path): StoreLoadManifest =
    scholarchain.core.Json
      .decode[StoreLoadManifest](text(load.resolve("neptune-bulk-load.json")).stripSuffix("\n"))
      .fold(e => fail(e.toString), identity)

  private def graph(run: Path, load: Path): NeptuneGraph =
    NeptuneRows
      .parse(neptune(load), RunId, p => Right(Bytes.of(bytes(run.resolve(p)))))
      .fold(ps => fail(ps.mkString("\n")), identity)

  /** The first occurrence of `a` in `s` replaced by `b` (exactly one must exist). */
  private def first(s: String, a: String, b: String): String = {
    val i = s.indexOf(a)
    assert(i >= 0, s"'$a' not found")
    s.substring(0, i) + b + s.substring(i + a.length)
  }

  /** The verifier refuses with `check` and a message containing `fragment`; nothing is verified. */
  private def refusedBy(run: Path, load: Path, check: VerificationCheck, fragment: String): Unit = {
    val r = verify(run, load)
    assertEquals(r.verified, None)
    assert(
      r.rejections.exists(j => j.check == check && j.error.message.contains(fragment)),
      s"expected ${check.tag} containing '$fragment'; got:\n" +
        r.rejections.map(j => s"  ${j.check.tag} ${j.subject}: ${j.error}").mkString("\n")
    )
    TargetStore.values.foreach(t => assert(!r.acceptedFor(t), t.tag))
  }

  // ------------------------------------------------------------------ the real fixture

  test("B7: 459 vertices and 799 edges; ID sets equal the CSV ~id sets, independently read") {
    val g = graph(runDir(run1Root), loadDir(run1Root))
    assertEquals((g.vertices.size, g.edges.size), (459, 799))
    // Independent read: first field of every data line (IDs are never quoted: printable ASCII,
    // no comma or quote), straight from the files.
    def csvIds(roles: Set[String]): Set[String] =
      neptune(loadDir(run1Root)).items
        .filter(i => roles.contains(i.targetRole))
        .flatMap(i => text(runDir(run1Root).resolve(i.path)).split("\n").toVector.tail)
        .filter(_.nonEmpty)
        .map(_.takeWhile(_ != ','))
        .toSet
    assertEquals(g.vertices.map(_.id).toSet, csvIds(NeptuneRows.vertexRoles.keySet))
    assertEquals(g.edges.map(_.id).toSet, csvIds(NeptuneRows.edgeRoles.keySet))
    assertEquals(g.vertices.map(_.id).distinct.size, 459, "no duplicate vertex ~id")
    assertEquals(g.edges.map(_.id).distinct.size, 799, "no duplicate edge ~id")
    assertEquals(NeptuneRows.graphIds(g), Vector.empty)
  }

  test("B7: the label sets are the six relationship labels and the six canonical vertex labels") {
    val g = graph(runDir(run1Root), loadDir(run1Root))
    assertEquals(
      g.edges.map(_.label).toSet,
      Set(
        EdgeLabel.Authored,
        EdgeLabel.CoauthoredWith,
        EdgeLabel.PublishedIn,
        EdgeLabel.AffiliatedWith,
        EdgeLabel.HasTopic,
        EdgeLabel.ResolvesTo
      )
    )
    assertEquals(g.edges.map(_.label.tag).toSet.size, 6)
    assert(
      !g.edges.exists(e => e.label == EdgeLabel.DerivedFrom || e.label == EdgeLabel.SupportedBy)
    )
    assertEquals(g.vertices.map(_.label).toSet, CanonicalGraph.NodeLabels.toSet)
    assert(
      !g.vertices.exists(v =>
        v.label == NodeLabel.TextChunk || v.label == NodeLabel.DatasetSnapshot
      )
    )
  }

  test(
    "N3: every COAUTHORED_WITH row carries count, years, window and its evidence pointer, typed"
  ) {
    val g = graph(runDir(run1Root), loadDir(run1Root))
    val co = g.edges.filter(_.label == EdgeLabel.CoauthoredWith)
    assert(co.nonEmpty)
    co.foreach { e =>
      Vector(
        "jointPublicationCount",
        "firstCollaborationYear",
        "lastCollaborationYear",
        "windowStartYear",
        "windowEndYear"
      )
        .foreach(k =>
          assert(e.properties.get(k).exists(_.isInstanceOf[GraphValue.IntValue]), s"${e.id} $k")
        )
      assert(
        e.properties.get("sourceEvidence").exists {
          case GraphValue.Str(s) => s.startsWith("coauthor:")
          case _ => false
        },
        e.id
      )
      assertEquals(e.properties.get("runId"), Some(GraphValue.Str(RunId)))
    }
  }

  test("the verifier marks row-contracts and graph-ids passed for neptune-bulk-load only") {
    val r = verify(runDir(run1Root), loadDir(run1Root))
    assertEquals(r.rejections, Vector.empty)
    val v = r.verified.get
    assertEquals(
      v.passedFor(TargetStore.NeptuneBulkLoad),
      Set[DeferredCheck](DeferredCheck.RowContracts, DeferredCheck.GraphIds)
    )
    assertEquals(
      r.outstandingFor(TargetStore.NeptuneBulkLoad),
      Vector(
        DeferredCheck.GraphIdParity,
        DeferredCheck.EvidenceTrace,
        DeferredCheck.AnalysisRecompute,
        DeferredCheck.CanonicalTables
      ),
      "without a context, parity, evidence-trace and the upstream recomputation stay open"
    )
    assert(!r.acceptedFor(TargetStore.NeptuneBulkLoad))
  }

  // ------------------------------------------------------------------ refusals (B7)

  test("a dangling endpoint is refused (graph-ids), and nothing is verified") {
    withCopy { (run, load) =>
      resealCsv(run, load, "authored-edges/part-00000.csv")(
        first(
          _,
          ",work:dblp:conf/csai/ZhangHCCX18,AUTHORED,",
          ",work:dblp:conf/csai/Missing18,AUTHORED,"
        )
      )
      refusedBy(
        run,
        load,
        VerificationCheck.NeptuneGraphIds,
        "dangling ~to endpoint work:dblp:conf/csai/Missing18"
      )
    }
  }

  test("a duplicate vertex ~id is refused (graph-ids)") {
    withCopy { (run, load) =>
      resealCsv(run, load, "venue-vertices/part-00001.csv") { s =>
        s + s.split("\n")(1) + "\n"
      }
      refusedBy(
        run,
        load,
        VerificationCheck.NeptuneGraphIds,
        "~id venue:dblp:conf/aaai/2023 appears 2 times"
      )
    }
  }

  test("a duplicate edge ~id across two files is refused (graph-ids)") {
    withCopy { (run, load) =>
      val line = text(run.resolve("authored-edges/part-00000.csv")).split("\n")(1)
      resealCsv(run, load, "authored-edges/part-00001.csv")(_ + line + "\n")
      refusedBy(run, load, VerificationCheck.NeptuneGraphIds, "appears 2 times")
    }
  }

  test("a bad Int value is refused (row contract)") {
    withCopy { (run, load) =>
      resealCsv(run, load, "coauthored-with-edges/part-00000.csv")(
        first(_, ",COAUTHORED_WITH,2022,1,2022,", ",COAUTHORED_WITH,2022,x1,2022,")
      )
      refusedBy(
        run,
        load,
        VerificationCheck.NeptuneRowContract,
        "jointPublicationCount: 'x1' is not a 32-bit decimal Int"
      )
    }
  }

  test("an Int outside 32 bits and an Int outside its profile are refused (row contract)") {
    withCopy { (run, load) =>
      resealCsv(run, load, "coauthored-with-edges/part-00000.csv")(
        first(_, ",COAUTHORED_WITH,2022,1,2022,", ",COAUTHORED_WITH,2022,4294967296,2022,")
      )
      refusedBy(run, load, VerificationCheck.NeptuneRowContract, "is not a 32-bit decimal Int")
    }
    withCopy { (run, load) =>
      resealCsv(run, load, "coauthored-with-edges/part-00000.csv")(
        first(_, ",COAUTHORED_WITH,2022,1,2022,", ",COAUTHORED_WITH,2022,0,2022,")
      )
      refusedBy(run, load, VerificationCheck.NeptuneRowContract, "jointPublicationCount")
    }
  }

  test("a missing required COAUTHORED_WITH property is refused (row contract, N3)") {
    withCopy { (run, load) =>
      resealCsv(run, load, "coauthored-with-edges/part-00000.csv")(
        first(_, ",COAUTHORED_WITH,2022,1,2022,", ",COAUTHORED_WITH,2022,,2022,")
      )
      refusedBy(run, load, VerificationCheck.NeptuneRowContract, "jointPublicationCount")
    }
  }

  test("a missing header column is refused before the rows are read") {
    withCopy { (run, load) =>
      val p = "coauthored-with-edges/part-00000.csv"
      val lines = text(run.resolve(p)).split("\n", -1)
      val changed =
        (first(lines.head, "jointPublicationCount:Int,", "") +: lines.tail).mkString("\n")
      // The rows keep 11 fields under a 10-column header: not countable, so the declared rows stay.
      val declared = lines.count(_.nonEmpty) - 1L
      reseal(run, load, p, changed.getBytes("UTF-8"), declared)
      val r = verify(run, load)
      assertEquals(r.verified, None)
      assert(
        r.rejections.exists(j =>
          j.check == VerificationCheck.CsvRecords || j.check == VerificationCheck.NeptuneHeader
        ),
        r.rejections.mkString("\n")
      )
    }
  }

  test("collaboration years outside the window are refused (row contract)") {
    withCopy { (run, load) =>
      resealCsv(run, load, "coauthored-with-edges/part-00000.csv")(
        first(
          _,
          ",COAUTHORED_WITH,2022,1,2022,2024,2020,",
          ",COAUTHORED_WITH,2019,1,2022,2024,2020,"
        )
      )
      refusedBy(run, load, VerificationCheck.NeptuneRowContract, "inside the window")
    }
  }

  test("a wrong ~label, a foreign runId and a malformed ~id are refused (row contract)") {
    withCopy { (run, load) =>
      resealCsv(run, load, "venue-vertices/part-00001.csv")(
        first(_, ",Venue,AAAI,", ",Topic,AAAI,")
      )
      refusedBy(run, load, VerificationCheck.NeptuneRowContract, "~label 'Topic' is not Venue")
    }
    withCopy { (run, load) =>
      val other = "run:" + "0" * 64
      resealCsv(run, load, "venue-vertices/part-00001.csv")(
        first(_, s",AAAI,$RunId", s",AAAI,$other")
      )
      refusedBy(
        run,
        load,
        VerificationCheck.NeptuneRowContract,
        s"runId: $other is not the verified run"
      )
    }
    withCopy { (run, load) =>
      resealCsv(run, load, "venue-vertices/part-00001.csv")(
        first(_, "venue:dblp:conf/aaai/2023,", "venue:nope:x,")
      )
      refusedBy(run, load, VerificationCheck.NeptuneRowContract, "unknown namespace 'nope'")
    }
  }

  test("an injection string in a property stays a plain value (verifier passes it)") {
    withCopy { (run, load) =>
      val payload = "AAAI\"}) MATCH (n) DETACH DELETE n //"
      val quoted = "\"" + payload.replace("\"", "\"\"") + "\""
      resealCsv(run, load, "venue-vertices/part-00001.csv")(
        first(_, ",Venue,AAAI,", s",Venue,$quoted,")
      )
      val r = verify(run, load)
      assertEquals(r.rejections, Vector.empty, "valid Text: no control characters")
      val g = graph(run, load)
      assertEquals(
        g.vertices.find(_.id == "venue:dblp:conf/aaai/2023").flatMap(_.properties.get("name")),
        Some(GraphValue.Str(payload)),
        "decoded verbatim, one field"
      )
      assertEquals(g.vertices.size, 459)
    }
  }

  test("parse refuses invalid UTF-8 and non-Neptune roles") {
    // CR-P7-07 n2: the real venue header, so the refusal can only be the UTF-8 one.
    val header =
      text(runDir(run1Root).resolve("venue-vertices/part-00001.csv")).takeWhile(_ != '\n')
    // A data row with the header's field count whose first field is invalid UTF-8.
    val commas = ("," * (header.split(",", -1).length - 1)).getBytes("UTF-8")
    val bad = Bytes.of(
      (header + "\n").getBytes("UTF-8") ++ Array(0xc3.toByte, 0x28.toByte) ++ commas ++
        Array('\n'.toByte)
    )
    NeptuneRows.vertices("venue-vertices", "venue-vertices/x.csv", bad, RunId) match {
      case Left(ps) => assert(ps.forall(_.reason.contains("UTF-8")) && ps.nonEmpty, ps.toString)
      case Right(rows) => fail(s"accepted $rows")
    }
    assert(NeptuneRows.vertices("metrics", "m.csv", Bytes.utf8(""), RunId).isLeft)
    assert(NeptuneRows.edges("researcher-vertices", "r.csv", Bytes.utf8(""), RunId).isLeft)
  }

  test("CsvRecords.parse agrees with the scanner on every fixture CSV part") {
    neptune(loadDir(run1Root)).items.foreach { i =>
      val b = Bytes.of(bytes(runDir(run1Root).resolve(i.path)))
      val parsed = CsvRecords.parse(b).fold(e => fail(s"${i.path}: $e"), identity)
      val scanned = CsvRecords.scan(b).fold(e => fail(s"${i.path}: $e"), identity)
      assertEquals(parsed.size.toLong, scanned.records, i.path)
      assertEquals(parsed.size.toLong - 1, i.rowCount, i.path)
      parsed.foreach(r => assertEquals(r.size, scanned.fieldsPerRecord, i.path))
    }
    assertEquals(
      CsvRecords.parse(Bytes.utf8("a,\"b,\"\"c\"\"\"\r\n,\n")),
      Right(Vector(Vector("a", "b,\"c\""), Vector("", "")))
    )
    assertEquals(CsvRecords.parse(Bytes.utf8("a,b,")), Right(Vector(Vector("a", "b", ""))))
    assertEquals(
      CsvRecords.parse(Bytes.of(Array(0xff.toByte, '\n'.toByte))).left.map(_.contains("UTF-8")),
      Left(true)
    )
    assertEquals(StoreError.Refused("x").message, "x")
  }
}
