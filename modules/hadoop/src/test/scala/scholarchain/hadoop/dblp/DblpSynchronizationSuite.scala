package scholarchain.hadoop.dblp

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import scala.jdk.CollectionConverters.*

import org.apache.hadoop.fs.Path
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat
import org.apache.hadoop.mapreduce.task.{JobContextImpl, TaskAttemptContextImpl}
import org.apache.hadoop.mapreduce.{JobID, TaskAttemptID}

import scholarchain.core.SourceType
import scholarchain.hadoop.normalize.Outcome
import scholarchain.hadoop.{DblpSettings, JobParams, RejectReason, TestSupport}

/**
 * Phase 4 repair D1/D2/D3 (test report, work order 4): the DBLP reader yields the same records and
 * rejects for every split size, never treats markup content (comments, CDATA, processing
 * instructions, the DOCTYPE internal subset) as a record even when a split starts inside it, and
 * resynchronizes at the next top-level record start after an unclosed record.
 */
class DblpSynchronizationSuite extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")

  def filler(tag: String, i: Int): String =
    s"""<article key="journals/pad/$tag$i"><author>Pad $tag$i</author><title>Padding $i.</title>""" +
      "<year>2000</year></article>\n"

  def fillers(tag: String, n: Int): String = (0 until n).map(filler(tag, _)).mkString

  /** Every "ghost/" key sits inside markup and must never be a record. */
  val fixture: String =
    """<?xml version="1.0" encoding="UTF-8"?>
      |<!DOCTYPE dblp SYSTEM "dblp.dtd" [
      |<!ENTITY ghostent "<article key='ghost/Entity'>x</article>">
      |<!-- a ' quote, a ]> and <inproceedings key="ghost/SubsetComment"> in a subset comment -->
      |<?subset-pi <article key="ghost/SubsetPi"> ?>
      |<!ELEMENT dblp (article|inproceedings)*>
      |]>
      |<dblp>
      |""".stripMargin +
      fillers("A", 8) +
      """<article key="journals/s/Before"><author>A One</author><title>Before.</title><year>2020</year></article>
        |<!-- This comment is longer than many small splits. Padding padding padding padding padding.
        |<article key="ghost/Comment"><author>Ghost Writer</author><title>Ghost.</title><year>1999</year></article>
        |<inproceedings key="ghost/Comment2"><author>Phantom</author>
        |end of the commented-out region -->
        |<?ghost-pi <article key="ghost/Pi"><title>pi</title></article> ?>
        |<![CDATA[ <article key="ghost/Cdata"><title>cdata</title></article> ]]>
        |<article key="journals/s/WithCdata"><author>B Two</author><title>T <![CDATA[ </article> <article key="ghost/InnerCdata"> ]]> end.</title><year>2021</year></article>
        |<article key="journals/s/WithComment"><author>C Three</author><!-- </article> <article key="ghost/InnerComment"> --><title>Commented.</title><year>2021</year></article>
        |<article key="journals/s/Unclosed"><author>D Four</author><title>Never closed.</title><year>2020</year>
        |<inproceedings key="conf/s/After1"><author>E Five</author><author>F Five</author><title>After one.</title><year>2022</year><booktitle>S</booktitle></inproceedings>
        |<article key="journals/s/Unclosed2"><author>F Six</author><title>Also never
        |<article key="journals/s/After2"><author>G Seven</author><title>After two.</title><year>2023</year></article>
        |""".stripMargin +
      fillers("B", 3) +
      """<!-- This comment is never closed; the records after it are recovered.
        |<article key="journals/s/AfterUnclosedComment"><author>H Eight</author><title>Recovered.</title><year>2024</year></article>
        |""".stripMargin +
      fillers("C", 12) +
      "</dblp>\n"

  val bytes: Array[Byte] = fixture.getBytes(StandardCharsets.UTF_8)

  /** Small bound: records and closed constructs fit, and lookback starts mid-file. */
  val SmallW = 512
  val BigW: Int = 1 << 20

  def scan(start: Int, end: Long, w: Int): Vector[DblpRawRecord] = {
    val from = DblpScanner.streamStart(start.toLong, w).toInt
    val s = new DblpScanner(
      new ByteArrayInputStream(bytes, from, bytes.length - from),
      from.toLong,
      start.toLong,
      end,
      w
    )
    Iterator.continually(s.next()).takeWhile(_.isDefined).map(_.get).toVector
  }

  type Sig = (Long, String, Int, Long, String)

  def sig(rs: Vector[DblpRawRecord]): Vector[Sig] =
    rs.map(r =>
      (r.offset, r.recordType, r.status, r.nextOffset, new String(r.bytes, StandardCharsets.UTF_8))
    ).sortBy(_._1)

  private val KeyRe = """key="([^"]*)"""".r

  def keyOf(r: DblpRawRecord): String =
    KeyRe
      .findFirstMatchIn(new String(r.bytes, StandardCharsets.UTF_8))
      .map(_.group(1))
      .getOrElse("")

  def chained(split: Int, w: Int): Vector[DblpRawRecord] =
    (0 until bytes.length by split).toVector.flatMap { s =>
      scan(s, math.min(bytes.length.toLong, s.toLong + split), w)
    }

  lazy val wholeSmall: Vector[DblpRawRecord] = scan(0, bytes.length.toLong, SmallW)
  lazy val wholeBig: Vector[DblpRawRecord] = scan(0, bytes.length.toLong, BigW)

  def offsetOf(needle: String): Long = fixture.indexOf(needle).toLong.ensuring(_ >= 0)

  test("precondition: tricky region lies beyond the small lookback window; fixture is small") {
    assert(offsetOf("ghost/Comment") > DblpScanner.lookback(SmallW) * 2)
    assert(bytes.length < 8192)
    assert(fixture.indexOf("-->", fixture.indexOf("never closed;")) < 0, "no closer after it")
  }

  test("whole-file scan: expected records and statuses, no ghost, unclosed markup reported") {
    Vector(wholeSmall, wholeBig).foreach { whole =>
      val named = whole.filter(_.status != DblpRawRecord.UnclosedMarkup)
      assert(named.forall(r => !keyOf(r).startsWith("ghost/")), named.map(keyOf))
      val tricky = named.map(r => keyOf(r) -> r.status).filterNot(_._1.startsWith("journals/pad/"))
      assertEquals(
        tricky,
        Vector(
          "journals/s/Before" -> DblpRawRecord.Complete,
          "journals/s/WithCdata" -> DblpRawRecord.Complete,
          "journals/s/WithComment" -> DblpRawRecord.Complete,
          "journals/s/Unclosed" -> DblpRawRecord.Interrupted,
          "conf/s/After1" -> DblpRawRecord.Complete,
          "journals/s/Unclosed2" -> DblpRawRecord.Interrupted,
          "journals/s/After2" -> DblpRawRecord.Complete,
          "journals/s/AfterUnclosedComment" -> DblpRawRecord.Complete
        )
      )
      assertEquals(named.count(r => keyOf(r).startsWith("journals/pad/")), 8 + 3 + 12)
      assert(named.filter(keyOf(_).startsWith("journals/pad/")).forall(_.complete))
      // The unclosed records stop right before the next record start, which is resynchronized.
      val unclosed = named.find(keyOf(_) == "journals/s/Unclosed").get
      assertEquals(unclosed.nextOffset, offsetOf("<inproceedings key=\"conf/s/After1\""))
      assertEquals(unclosed.offset + unclosed.bytes.length, unclosed.nextOffset)
      assert(!new String(unclosed.bytes, StandardCharsets.UTF_8).contains("After1"))
      // The record with CDATA/comment content keeps it and ends at its real end tag.
      val withCdata = named.find(keyOf(_) == "journals/s/WithCdata").get
      assert(new String(withCdata.bytes, StandardCharsets.UTF_8).endsWith("</year></article>"))
      assert(new String(withCdata.bytes, StandardCharsets.UTF_8).contains("ghost/InnerCdata"))
      // Exactly one report: the comment that is never closed, at its opener.
      val reports = whole.filter(_.status == DblpRawRecord.UnclosedMarkup)
      assertEquals(
        reports.map(r => (r.recordType, r.offset)),
        Vector(DblpRawRecord.MarkupComment -> offsetOf("<!-- This comment is never closed"))
      )
      assert(new String(reports.head.bytes, StandardCharsets.UTF_8).startsWith("<!-- This"))
      whole.foreach(r => assertEquals(bytes(r.offset.toInt).toChar, '<'))
    }
    assertEquals(sig(wholeSmall), sig(wholeBig), "the bound does not change a conforming file")
  }

  test("two-way split at EVERY byte offset equals the whole-file scan (both bounds)") {
    Vector(SmallW, BigW).foreach { w =>
      val expected = sig(scan(0, bytes.length.toLong, w))
      (0 to bytes.length).foreach { s =>
        val got = sig(scan(0, s.toLong, w) ++ scan(s, bytes.length.toLong, w))
        assertEquals(got, expected, s"w=$w split at byte $s")
      }
    }
  }

  test("every split size 1..64 and larger sizes yield the whole-file records and reports") {
    val sizes = (1 to 64).toVector ++ Vector(97, 128, 200, 333, 511, 512, 513, 1000, 2048)
    Vector(SmallW, BigW).foreach { w =>
      val expected = sig(scan(0, bytes.length.toLong, w))
      sizes.foreach(k => assertEquals(sig(chained(k, w)), expected, s"w=$w split size $k"))
    }
  }

  test("splits starting inside the comment, PI, CDATA, and the DOCTYPE subset emit no ghost") {
    val inside = Vector(
      "ghost/Comment\"",
      "ghost/Comment2",
      "ghost/Pi",
      "ghost/Cdata",
      "ghost/InnerCdata",
      "ghost/InnerComment",
      "ghost/Entity",
      "ghost/SubsetComment",
      "ghost/SubsetPi"
    ).map(n => fixture.indexOf(n).ensuring(_ >= 0))
    val starts = inside.flatMap(i => (i - 40 to i).filter(_ >= 0))
    starts.foreach { s =>
      val rs = scan(s, bytes.length.toLong, SmallW)
      assert(rs.forall(r => !keyOf(r).startsWith("ghost/")), s"split at $s: ${rs.map(keyOf)}")
    }
  }

  // --------------------------------------------------------------- classification of raw records

  val parser = new DblpParser(StandardCharsets.UTF_8)
  val run = TestSupport.run(SourceType.Dblp)
  val settings =
    DblpSettings(Set("article", "inproceedings"), None, None, StandardCharsets.UTF_8, SmallW)

  def outcomes(rs: Vector[DblpRawRecord]): Vector[(Long, String)] =
    rs.map { r =>
      r.offset -> (DblpRawOutcome.of(r, settings, parser, run) match {
        case Outcome.Accepted(id, _) => s"accepted:$id"
        case Outcome.Rejected(_, ps) => s"rejected:${ps.head.reason.tag}"
        case Outcome.Filtered(_, why) => s"filtered:$why"
        case Outcome.Skipped(why) => s"skipped:$why"
      })
    }.sortBy(_._1)

  test("unclosed record and unclosed markup are rejected malformed-xml; neighbours accepted") {
    val o = outcomes(wholeSmall).toMap
    def at(needle: String) = o(offsetOf(needle))
    assertEquals(at("<article key=\"journals/s/Unclosed\""), "rejected:malformed-xml")
    assertEquals(at("<article key=\"journals/s/Unclosed2\""), "rejected:malformed-xml")
    assertEquals(at("<!-- This comment is never closed"), "rejected:malformed-xml")
    assertEquals(at("<inproceedings key=\"conf/s/After1\""), "accepted:conf/s/After1")
    assertEquals(at("<article key=\"journals/s/After2\""), "accepted:journals/s/After2")
    assertEquals(at("<article key=\"journals/s/Before\""), "accepted:journals/s/Before")
    assertEquals(
      at("<article key=\"journals/s/AfterUnclosedComment\""),
      "accepted:journals/s/AfterUnclosedComment"
    )
    assertEquals(at("<article key=\"journals/s/WithComment\""), "accepted:journals/s/WithComment")
    assert(o.values.forall(v => !v.contains("ghost/")))
    // A cut-off record is still unterminated-record (not malformed-xml).
    val cut = "<dblp><article key=\"k/a\"><title>x".getBytes(StandardCharsets.UTF_8)
    val rs = new DblpScanner(new ByteArrayInputStream(cut), 0L, 0L, cut.length.toLong, SmallW)
    val r = rs.next().get
    assertEquals(r.status, DblpRawRecord.Unterminated)
    DblpRawOutcome.of(r, settings, parser, run) match {
      case Outcome.Rejected(_, ps) => assertEquals(ps.head.reason, RejectReason.UnterminatedRecord)
      case other => fail(s"expected rejected, got $other")
    }
  }

  test("raw records survive the Writable round trip with status and next offset") {
    val r = wholeSmall.find(_.status == DblpRawRecord.Interrupted).get
    val out = new java.io.ByteArrayOutputStream()
    r.write(new java.io.DataOutputStream(out))
    val back = new DblpRawRecord()
    back.readFields(new java.io.DataInputStream(new java.io.ByteArrayInputStream(out.toByteArray)))
    assertEquals(sig(Vector(back)), sig(Vector(r)))
  }

  // --------------------------------------------------------------- real Hadoop record reader

  test("DblpInputFormat: classified outcomes are identical for every split size (D3)") {
    val dir = TestSupport.workDir("dblp-synchronization")
    val file = dir.resolve("dblp.xml")
    Files.write(file, bytes)
    val w = 1024 // the smallest allowed scholarchain.dblp.maxRecordBytes
    val wSettings = settings.copy(maxRecordBytes = w)

    def read(splitMax: Long): (Int, Vector[(Long, String)]) = {
      val conf = TestSupport.conf(Map(JobParams.DblpMaxRecordBytes -> w.toString))
      conf.set("fs.defaultFS", "file:///")
      conf.setLong(FileInputFormat.SPLIT_MAXSIZE, splitMax)
      conf.set(FileInputFormat.INPUT_DIR, new Path(file.toUri).toString)
      val fmt = new DblpInputFormat
      val splits = fmt.getSplits(new JobContextImpl(conf, new JobID("t", 1))).asScala.toVector
      val recs = splits.flatMap { split =>
        val ctx = new TaskAttemptContextImpl(conf, new TaskAttemptID())
        val rr = fmt.createRecordReader(split, ctx)
        rr.initialize(split, ctx)
        val acc = Vector.newBuilder[DblpRawRecord]
        while (rr.nextKeyValue()) {
          assertEquals(
            rr.getCurrentKey.toString.substring(rr.getCurrentKey.toString.lastIndexOf('@') + 1),
            rr.getCurrentValue.offset.toString
          )
          acc += rr.getCurrentValue
        }
        rr.close()
        acc.result()
      }
      val classified = recs.map { r =>
        r.offset -> (DblpRawOutcome.of(r, wSettings, parser, run) match {
          case Outcome.Accepted(id, _) => s"accepted:$id"
          case Outcome.Rejected(_, ps) => s"rejected:${ps.head.reason.tag}"
          case other => other.toString
        })
      }
      (splits.size, classified.sortBy(_._1))
    }

    val (wholeSplits, expected) = read(Long.MaxValue)
    assertEquals(wholeSplits, 1)
    assertEquals(expected.count(_._2.startsWith("accepted:")), 8 + 3 + 12 + 6)
    assertEquals(expected.count(_._2 == "rejected:malformed-xml"), 3)
    assert(expected.forall(!_._2.contains("ghost/")))
    Vector(16L, 64L, 100L, 257L, 1000L, 1500L).foreach { size =>
      val (n, got) = read(size)
      assert(n > 1, s"split size $size gave $n splits")
      assertEquals(got, expected, s"split size $size")
    }
  }
}
