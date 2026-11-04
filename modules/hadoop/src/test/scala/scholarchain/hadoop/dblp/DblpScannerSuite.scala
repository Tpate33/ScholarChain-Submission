package scholarchain.hadoop.dblp

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

import scala.jdk.CollectionConverters.*

import org.apache.hadoop.fs.Path
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat
import org.apache.hadoop.mapreduce.task.{JobContextImpl, TaskAttemptContextImpl}
import org.apache.hadoop.mapreduce.{JobID, TaskAttemptID}

import scholarchain.hadoop.{JobParams, TestSupport}

class DblpScannerSuite extends munit.FunSuite {

  /**
   * Fixture: entities, raw multi-byte UTF-8, multiline title with inline markup, `>` inside a
   * quoted attribute, whitespace before `>` in an end tag, a self-closing record, non-publication
   * records, and a 3-byte CJK sequence.
   */
  val fixture: String =
    """<?xml version="1.0" encoding="UTF-8"?>
      |<!DOCTYPE dblp SYSTEM "dblp.dtd">
      |<dblp>
      |<article key="journals/x/A1" mdate="2020-01-01">
      |<author orcid="0000-0002-2814-962X">Christina P&ouml;pper</author>
      |<author>João Varajão</author>
      |<title>A <i>multiline</i>
      |   title  with spaces.</title>
      |<year>2021</year>
      |<journal>J. X</journal>
      |<ee>https://doi.org/10.1234/ABC</ee>
      |</article>
      |<www key="homepages/x/Y"><author>Some One</author><title>Home Page</title></www>
      |<inproceedings key="conf/x/B2" publtype="a>b"><author>Ann B</author><author>Bob C</author><author>Cy D</author><title>Three.</title><year>2019</year><booktitle>X</booktitle><crossref>conf/x/2019</crossref></inproceedings >
      |<article key="journals/x/C3"><author>中文 Name</author><title>CJK 中.</title><year>2020</year></article>
      |<proceedings key="conf/x/2019"><editor>E Ditor</editor><title>Proc.</title><year>2019</year></proceedings>
      |<person key="persons/z"/>
      |</dblp>
      |""".stripMargin

  val bytes: Array[Byte] = fixture.getBytes(StandardCharsets.UTF_8)
  val max = 1 << 20

  def scan(data: Array[Byte], start: Int, end: Long, maxBytes: Int = max): Vector[DblpRawRecord] = {
    val from = DblpScanner.streamStart(start.toLong, maxBytes).toInt
    val s = new DblpScanner(
      new ByteArrayInputStream(data, from, data.length - from),
      from.toLong,
      start.toLong,
      end,
      maxBytes
    )
    Iterator.continually(s.next()).takeWhile(_.isDefined).map(_.get).toVector
  }

  def sig(rs: Vector[DblpRawRecord]): Vector[(Long, String, Boolean, String)] =
    rs.map(r => (r.offset, r.recordType, r.complete, new String(r.bytes, StandardCharsets.UTF_8)))

  lazy val whole: Vector[DblpRawRecord] = scan(bytes, 0, bytes.length.toLong)

  test("whole-file scan finds every record exactly, in order, complete") {
    assertEquals(
      whole.map(_.recordType),
      Vector("article", "www", "inproceedings", "article", "proceedings", "person")
    )
    assert(whole.forall(_.complete))
    whole.foreach(r => assertEquals(bytes(r.offset.toInt).toChar, '<'))
    assert(new String(whole(2).bytes, StandardCharsets.UTF_8).endsWith("</inproceedings >"))
    assertEquals(
      new String(whole(5).bytes, StandardCharsets.UTF_8),
      """<person key="persons/z"/>"""
    )
    assert(new String(whole(0).bytes, StandardCharsets.UTF_8).endsWith("</article>"))
  }

  test("two-way split at EVERY byte offset yields each record exactly once") {
    val expected = sig(whole)
    (0 to bytes.length).foreach { s =>
      val got = sig(scan(bytes, 0, s.toLong) ++ scan(bytes, s, bytes.length.toLong))
      assertEquals(got, expected, s"split at byte $s")
    }
  }

  test("three-way splits around every record boundary yield each record exactly once") {
    val expected = sig(whole)
    val points = whole
      .flatMap(r =>
        Vector(
          r.offset - 1,
          r.offset,
          r.offset + 1,
          r.offset + r.bytes.length - 1,
          r.offset + r.bytes.length
        )
      )
      .map(_.toInt)
      .filter(p => p >= 0 && p <= bytes.length)
      .distinct
      .sorted
    for {
      a <- points
      b <- points if b >= a
    } {
      val got = sig(
        scan(bytes, 0, a.toLong) ++ scan(bytes, a, b.toLong) ++ scan(bytes, b, bytes.length.toLong)
      )
      assertEquals(got, expected, s"splits at $a and $b")
    }
  }

  test("split inside a multi-byte UTF-8 sequence does not corrupt the record") {
    val cjk = fixture.indexOf("中文")
    val byteOffset = fixture.substring(0, cjk).getBytes(StandardCharsets.UTF_8).length
    (byteOffset to byteOffset + 6).foreach { s =>
      assertEquals(sig(scan(bytes, 0, s.toLong) ++ scan(bytes, s, bytes.length.toLong)), sig(whole))
    }
  }

  test("a record cut off by end of input is returned incomplete; scanning stops") {
    val cut =
      """<dblp><article key="k/a"><title>x</title><author>A""".getBytes(StandardCharsets.UTF_8)
    val rs = scan(cut, 0, cut.length.toLong)
    assertEquals(rs.map(r => (r.recordType, r.complete)), Vector(("article", false)))
  }

  test("a record above the size limit is returned incomplete and the next record is still found") {
    val big = ("<dblp><article key=\"k/a\"><title>" + ("x" * 3000) + "</title></article>" +
      "<article key=\"k/b\"><title>y</title></article></dblp>").getBytes(StandardCharsets.UTF_8)
    val rs = scan(big, 0, big.length.toLong, maxBytes = 1024)
    assertEquals(rs.map(r => (r.recordType, r.complete)).last, ("article", true))
    assertEquals(rs.head.complete, false)
    assert(new String(rs.last.bytes, StandardCharsets.UTF_8).contains("k/b"))
  }

  test("comments between records are skipped, even if they contain a record start tag") {
    val c =
      """<dblp><!-- <article key="fake/x"> --><article key="real/x"><title>t</title></article></dblp>"""
        .getBytes(StandardCharsets.UTF_8)
    val rs = scan(c, 0, c.length.toLong)
    assertEquals(rs.size, 1)
    assert(new String(rs.head.bytes, StandardCharsets.UTF_8).contains("real/x"))
  }

  test("every fixture record parses with the entity table; non-publication records parse too") {
    val parser = new DblpParser(StandardCharsets.UTF_8)
    val parsed = whole.map(r => parser.parse(r.bytes))
    assert(parsed.forall(_.isRight), parsed.collect { case Left(e) => e }.mkString("; "))
  }

  // ---------------------------------------------------------------- real Hadoop record reader

  test(
    "DblpInputFormat: many byte-range splits of an uncompressed file and one split of a gzip file"
  ) {
    val dir = TestSupport.workDir("dblp-input-format")
    val plain = dir.resolve("dblp.xml")
    Files.write(plain, bytes)
    val gz = dir.resolve("dblp.xml.gz")
    val out = new GZIPOutputStream(Files.newOutputStream(gz))
    try out.write(bytes)
    finally out.close()

    def records(p: java.nio.file.Path, splitMax: Long): (Int, Vector[(Long, String)]) = {
      val conf = TestSupport.conf(Map(JobParams.DblpMaxRecordBytes -> "1048576"))
      conf.set("fs.defaultFS", "file:///")
      conf.setLong(FileInputFormat.SPLIT_MAXSIZE, splitMax)
      conf.set(FileInputFormat.INPUT_DIR, new Path(p.toUri).toString)
      val fmt = new DblpInputFormat
      val splits = fmt.getSplits(new JobContextImpl(conf, new JobID("t", 1))).asScala.toVector
      val recs = splits.flatMap { split =>
        val ctx = new TaskAttemptContextImpl(conf, new TaskAttemptID())
        val rr = fmt.createRecordReader(split, ctx)
        rr.initialize(split, ctx)
        val acc = Vector.newBuilder[(Long, String)]
        while (rr.nextKeyValue())
          acc += (rr.getCurrentValue.offset -> rr.getCurrentValue.recordType)
        rr.close()
        acc.result()
      }
      (splits.size, recs)
    }

    val expected = whole.map(r => r.offset -> r.recordType)
    val (plainSplits, plainRecs) = records(plain, 64L)
    assert(plainSplits > 10, s"expected many splits, got $plainSplits")
    assertEquals(plainRecs, expected)

    val (gzSplits, gzRecs) = records(gz, 64L)
    assertEquals(gzSplits, 1)
    assertEquals(gzRecs, expected)
  }
}
