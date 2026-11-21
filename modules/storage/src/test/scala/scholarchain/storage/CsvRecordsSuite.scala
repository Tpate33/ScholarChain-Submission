package scholarchain.storage

import java.nio.charset.StandardCharsets

import scholarchain.core.{Json, StoreLoadManifest}
import scholarchain.storage.load.{CsvRecords, NeptuneHeaders}
import scholarchain.storage.model.Bytes
import Fixtures.*

/** The strict RFC 4180 record scanner and the Neptune header table (SE-P7-03 item 4). */
class CsvRecordsSuite extends munit.FunSuite {

  private def scan(s: String) = CsvRecords.scan(s.getBytes(StandardCharsets.UTF_8))
  private def rows(s: String) = scan(s).map(_.dataRows)

  test("records are terminated by LF or CRLF; the last terminator is optional") {
    assertEquals(rows("a,b\n1,2\n3,4\n"), Right(2L))
    assertEquals(rows("a,b\r\n1,2\r\n"), Right(1L))
    assertEquals(rows("a,b\n1,2"), Right(1L))
    assertEquals(rows("a,b\n"), Right(0L), "header only")
    assertEquals(rows(""), Right(0L), "empty file")
    assertEquals(scan("").map(_.header), Right(Bytes.of(Array.emptyByteArray)))
  }

  test("quoted fields may hold commas, quotes (doubled), CR and LF without ending a record") {
    assertEquals(
      rows("a,b\n\"x,y\",\"he said \"\"hi\"\"\"\n\"line1\nline2\",\"cr\r\nlf\"\n"),
      Right(2L)
    )
    assertEquals(rows("a,b\n,\n"), Right(1L), "two empty fields")
    assertEquals(rows("a,b\n\"\",\"\"\n"), Right(1L), "two empty quoted fields")
  }

  test("the header is the exact bytes of the first record with its terminator") {
    assertEquals(scan("~id,~label\nx,y\n").map(_.header.utf8), Right("~id,~label\n"))
    assertEquals(scan("~id,~label\r\nx,y\r\n").map(_.header.utf8), Right("~id,~label\r\n"))
    assertEquals(scan("a,b,c\n1,2,3\n").map(_.fieldsPerRecord), Right(3))
  }

  test("malformed CSV is refused with a reason") {
    def refused(s: String, fragment: String) = scan(s) match {
      case Left(why) => assert(why.contains(fragment), why)
      case Right(r) => fail(s"accepted: $r")
    }
    refused("a,b\n1,\"2\n", "unterminated quoted field")
    refused("a,b\n1,x\"y\n", "a quote inside an unquoted field")
    refused("a,b\n1,\"2\"x\n", "byte after a closing quote")
    refused("a,b\n1,2\r3,4\n", "bare CR")
    refused("a,b\n\n1,2\n", "empty record")
    refused("a,b\n1,2,3\n", "record 2 has 3 fields; the header has 2")
    refused("a,b\n1\n", "record 2 has 1 fields")
  }

  test("a trailing blank line is an empty record and is refused (n5)") {
    def refused(s: String) = scan(s) match {
      case Left(why) => assert(why.contains("empty record"), why)
      case Right(r) => fail(s"accepted: $r")
    }
    refused("a,b\n1,2\n\n")
    refused("a,b\r\n1,2\r\n\r\n")
    refused("a,b\n1,2\n\n\n")
  }

  test("a UTF-8 BOM is field content: never stripped, so a Neptune header with one differs (n5)") {
    val bom = "﻿"
    val header = NeptuneHeaders.byRole("work-vertices").utf8
    val s = scan(bom + header + "x,Work,eligible,1,2000,r\n").fold(fail(_), identity)
    assertEquals(s.dataRows, 1L)
    assertEquals(s.header.toArray.take(3).toVector, Vector(0xef, 0xbb, 0xbf).map(_.toByte))
    assertNotEquals(Some(s.header), NeptuneHeaders.byRole.get("work-vertices"))
    scan(bom + "\"a\",b\n") match {
      case Left(why) => assert(why.contains("a quote inside an unquoted field"), why)
      case Right(r) => fail(s"accepted: $r")
    }
  }

  test("a very long field (quoted and unquoted, 4 MiB) is counted as one field (n5)") {
    val n = 4 * 1024 * 1024
    val long = "y" * n
    val unquoted = scan(s"a,b\nx,$long\n").fold(fail(_), identity)
    assertEquals((unquoted.dataRows, unquoted.fieldsPerRecord), (1L, 2))
    val quoted = scan(s"a,b\n\"${long.replace("y", "\"\"").take(n)}\",z\n1,2\n")
      .fold(fail(_), identity)
    assertEquals((quoted.dataRows, quoted.fieldsPerRecord), (2L, 2))
    val withNewlines = scan("a,b\n\"" + ("line\n" * 100000) + "\",z\n").fold(fail(_), identity)
    assertEquals(withNewlines.dataRows, 1L)
  }

  test("every fixture Neptune CSV scans, with the declared row count and the core header") {
    val m = Json
      .decode[StoreLoadManifest](text(loadDir(run1Root).resolve("neptune-bulk-load.json")).trim)
      .fold(e => fail(e.toString), identity)
    assertEquals(m.items.size, 24)
    m.items.foreach { it =>
      val s = CsvRecords
        .scan(bytes(runDir(run1Root).resolve(it.path)))
        .fold(why => fail(s"${it.path}: $why"), identity)
      assertEquals(s.dataRows, it.rowCount, it.path)
      assertEquals(Some(s.header), NeptuneHeaders.byRole.get(it.targetRole), it.path)
    }
    assertEquals(m.items.map(_.rowCount).sum, 1258L)
  }

  test("the header table covers the 12 Neptune roles, six vertex and six edge") {
    assertEquals(NeptuneHeaders.byRole.size, 12)
    assertEquals(NeptuneHeaders.byRole.keys.count(_.endsWith("-vertices")), 6)
    assertEquals(
      NeptuneHeaders.byRole("coauthored-with-edges").utf8,
      "~id,~from,~to,~label,firstCollaborationYear:Int,jointPublicationCount:Int," +
        "lastCollaborationYear:Int,windowEndYear:Int,windowStartYear:Int,sourceEvidence:String,runId:String\n"
    )
    assertEquals(
      NeptuneHeaders.byRole("work-vertices").utf8,
      "~id,~label,graphEligibility:String,groupSize:Int,year:Int,runId:String\n"
    )
  }
}
