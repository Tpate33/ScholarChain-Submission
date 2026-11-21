package scholarchain.storage.parquet

import java.io.ByteArrayOutputStream

import org.apache.parquet.column.statistics.Statistics
import org.apache.parquet.column.Encoding
import org.apache.parquet.example.data.simple.SimpleGroupFactory
import org.apache.parquet.hadoop.example.ExampleParquetWriter
import org.apache.parquet.hadoop.metadata.{
  BlockMetaData,
  ColumnChunkMetaData,
  ColumnPath,
  CompressionCodecName
}
import org.apache.parquet.io.{OutputFile, PositionOutputStream}
import org.apache.parquet.schema.{MessageTypeParser, PrimitiveType, Types}

import scholarchain.storage.model.Bytes

/**
 * CR-P7-08 M1, m1, m3: hostile footers (row-count overflow, negative counts, column chunks outside
 * the part) and hostile raw-Snappy streams, plus a differential check of [[SnappyRaw]] against
 * parquet-mr's SNAPPY writer (snappy-java). CR-P7-09 n1: snappy-java is probably also on the
 * storage Compile/Runtime classpath, transitively through parquet-hadoop (unverified); the storage
 * read path does not call it (it decodes with [[SnappyRaw]]). Only this test uses it.
 */
class ParquetHostileSuite extends munit.FunSuite {

  // ------------------------------------------------------------------ footer (M1, m1)

  private val int32: PrimitiveType =
    Types.required(PrimitiveType.PrimitiveTypeName.INT32).named("a")

  private def chunk(start: Long, size: Long): ColumnChunkMetaData =
    ColumnChunkMetaData.get(
      ColumnPath.get("a"),
      int32,
      CompressionCodecName.SNAPPY,
      null,
      java.util.Set.of(Encoding.PLAIN),
      Statistics.createStats(int32),
      start,
      0L,
      1L,
      size,
      size
    )

  private def block(rows: Long, chunks: ColumnChunkMetaData*): BlockMetaData = {
    val b = new BlockMetaData
    b.setRowCount(rows)
    chunks.foreach(b.addColumn)
    b
  }

  test("M1: two row groups whose row counts overflow a Long are refused") {
    val p = ParquetFooters.footerProblem(Vector(block(1L << 62), block(1L << 62)), 1024L)
    assertEquals(p, Some("the row-group row counts overflow"))
    val q = ParquetFooters.footerProblem(
      Vector(block(Long.MaxValue), block(1L)),
      1024L
    )
    assertEquals(q, Some("the row-group row counts overflow"))
  }

  test("M1: a negative row-group row count is refused") {
    val p = ParquetFooters.footerProblem(Vector(block(5L), block(-3L)), 1024L)
    assertEquals(p, Some("row group 1 declares -3 rows"))
  }

  test("m1: a 1 KiB part declaring a 64 GiB column chunk is refused before any page is read") {
    val p = ParquetFooters.footerProblem(Vector(block(1L, chunk(4L, 64L << 30))), 1024L)
    assert(p.exists(_.contains("outside the 1024-byte part")), p.toString)
    // start + size overflowing a Long, a start inside the leading magic, a negative size.
    assert(
      ParquetFooters.footerProblem(Vector(block(1L, chunk(8L, Long.MaxValue))), 1024L).nonEmpty
    )
    assert(ParquetFooters.footerProblem(Vector(block(1L, chunk(0L, 8L))), 1024L).nonEmpty)
    assert(ParquetFooters.footerProblem(Vector(block(1L, chunk(4L, -1L))), 1024L).nonEmpty)
    // Control: a chunk inside the part passes.
    assertEquals(ParquetFooters.footerProblem(Vector(block(1L, chunk(4L, 100L))), 1024L), None)
  }

  // ------------------------------------------------------------------ SnappyRaw (m3)

  private def b(xs: Int*): Array[Byte] = xs.map(_.toByte).toArray
  private def run(in: Array[Byte], expected: Int, max: Int = 1 << 20) =
    SnappyRaw.decompress(in, 0, in.length, expected, max)
  private def refused(in: Array[Byte], expected: Int, fragment: String): Unit =
    run(in, expected) match {
      case Left(why) => assert(why.contains(fragment), why)
      case Right(a) => fail(s"decoded ${a.length} bytes")
    }

  test("m3: trailing bytes after the output is full are refused") {
    // "ab" (declared 2), then a further literal tag of one byte.
    refused(b(2, 1 << 2, 'a', 'b', 0, 'c'), 2, "literal runs past the declared length")
    // A trailing copy element after the output is full.
    refused(b(2, 1 << 2, 'a', 'b', 0x01, 0x01), 2, "copy runs past the declared length")
  }

  test("m3: a 4-byte literal length near 2^32 is refused, never allocated or wrapped") {
    // Tag 63 << 2: four little-endian length bytes follow; length - 1 = 0xFFFFFFFE.
    refused(b(4, 63 << 2, 0xfe, 0xff, 0xff, 0xff, 'a'), 4, "literal runs past the input")
  }

  test("m3: a 5-byte varint above Int.MaxValue is refused") {
    // 0xFFFFFFFF as a varint: ff ff ff ff 0f.
    refused(
      b(0xff, 0xff, 0xff, 0xff, 0x0f),
      Int.MaxValue,
      "differs from the page's uncompressed size"
    )
  }

  test("m3: a kind-3 copy with an offset >= 2^31 is refused") {
    refused(b(4, 0 << 2, 'a', 0x0f, 0x00, 0x00, 0x00, 0x80), 4, "copy offset 2147483648 outside")
  }

  test("m3: an empty stream with declared length 0 decodes to no bytes") {
    assertEquals(run(b(0), 0).map(_.length), Right(0))
    // ...and declared 0 with a following element is refused.
    refused(b(0, 0, 'a'), 0, "literal runs past the declared length")
  }

  // ------------------------------------------------------------------ differential (m3)

  private final class MemoryOut extends OutputFile {
    val bytes = new ByteArrayOutputStream()
    def create(blockSizeHint: Long): PositionOutputStream = new PositionOutputStream {
      def getPos(): Long = bytes.size.toLong
      def write(b: Int): Unit = bytes.write(b)
      override def write(b: Array[Byte], off: Int, len: Int): Unit = bytes.write(b, off, len)
    }
    def createOrOverwrite(blockSizeHint: Long): PositionOutputStream = create(blockSizeHint)
    def supportsBlockSize(): Boolean = false
    def defaultBlockSize(): Long = 0L
  }

  test("differential: parts compressed by the reference SNAPPY writer decode exactly") {
    val schema = MessageTypeParser.parseMessageType(
      "message m { required binary s (STRING); required int64 n; }"
    )
    val rnd = new scala.util.Random(20261120L)
    val values = (0 until 2000).toVector.map { i =>
      // Repetitive and random text: exercises literals and all copy lengths and offsets.
      val s =
        if (i % 3 == 0) "abc" * (i % 50)
        else if (i % 3 == 1) rnd.alphanumeric.take(i % 97).mkString
        else s"row-$i-" + ("é" * (i % 7))
      (s, rnd.nextLong())
    }
    val out = new MemoryOut
    val w = ExampleParquetWriter
      .builder(out)
      .withType(schema)
      .withCompressionCodec(CompressionCodecName.SNAPPY)
      .withPageSize(4096)
      .build()
    val groups = new SimpleGroupFactory(schema)
    values.foreach { case (s, n) => w.write(groups.newGroup().append("s", s).append("n", n)) }
    w.close()
    val bounds = ParquetReadBounds.of(1 << 20, 1L << 24, 10000L, 1 << 20).toOption.get
    val part = ParquetRows
      .read("p.parquet", Bytes.of(out.bytes.toByteArray), bounds)
      .fold(e => fail(e.toString), identity)
    assertEquals(
      part.rows.map(r => (r.fields(0)._2, r.fields(1)._2)),
      values.map { case (s, n) => (PValue.Str(s), PValue.I64(n)) }
    )
  }

  test("n1: a page larger than maxPageBytes is OverBound, not Corrupt") {
    val schema = MessageTypeParser.parseMessageType("message m { required binary s (STRING); }")
    val out = new MemoryOut
    val w = ExampleParquetWriter
      .builder(out)
      .withType(schema)
      .withCompressionCodec(CompressionCodecName.SNAPPY)
      .build()
    val groups = new SimpleGroupFactory(schema)
    (0 until 200).foreach(i => w.write(groups.newGroup().append("s", "x" * 50 + i)))
    w.close()
    val tight = ParquetReadBounds.of(1 << 20, 1L << 24, 10000L, 64).toOption.get
    ParquetRows.read("p.parquet", Bytes.of(out.bytes.toByteArray), tight) match {
      case Left(ParquetReadError.OverBound(_, why)) => assert(why.contains("maxPageBytes 64"), why)
      case other => fail(s"expected OverBound, got $other")
    }
  }
}
