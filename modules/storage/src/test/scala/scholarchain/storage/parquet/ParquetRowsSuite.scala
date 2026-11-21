package scholarchain.storage.parquet

import java.io.ByteArrayOutputStream

import org.apache.parquet.example.data.simple.SimpleGroupFactory
import org.apache.parquet.hadoop.example.ExampleParquetWriter
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.apache.parquet.io.{OutputFile, PositionOutputStream}
import org.apache.parquet.schema.MessageTypeParser

import scholarchain.core.{PartFormat, PartManifests}
import scholarchain.storage.Fixtures.*
import scholarchain.storage.model.{Bytes, StoreError}

/**
 * SE-P7-06 item 1: the Parquet row reader on the real fixture parts (SNAPPY, written by Spark), the
 * codec refusal and the configured bounds. Non-SNAPPY parts are written here, in memory, by
 * parquet-mr's example writer (test code only; nothing is written under the fixtures).
 */
class ParquetRowsSuite extends munit.FunSuite {

  /** Test bounds (configuration values chosen for the fixture). */
  private val bounds =
    ParquetReadBounds.of(1024 * 1024, 8L * 1024 * 1024, 100000L, 8 * 1024 * 1024).toOption.get

  private lazy val parquetParts =
    PartManifests
      .decode(text(runDir(run1Root).resolve("_parts.json")))
      .map(PartManifests.asV2)
      .fold(e => fail(e.toString), identity)
      .files
      .filter(_.format == PartFormat.Parquet)

  private def fixture(path: String): Bytes = Bytes.of(bytes(runDir(run1Root).resolve(path)))

  test("every fixture Parquet part decodes: footer rows = decoded rows = declared rows") {
    assertEquals(parquetParts.size, 29)
    parquetParts.foreach { f =>
      val p = ParquetRows.read(f.path, fixture(f.path), bounds).fold(e => fail(s"$e"), identity)
      assertEquals(p.footer.rowCount, f.rowCount, f.path)
      assertEquals(p.rows.size.toLong, f.rowCount, f.path)
      assertEquals(p.footer.codecSet, Set("SNAPPY"), f.path)
      assertEquals(p.columns.head, "schemaVersion", f.path)
      p.rows.foreach { r =>
        assertEquals(r.fields.head, "schemaVersion" -> PValue.Str(f.recordSchemaVersion), f.path)
      }
    }
  }

  // ------------------------------------------------------------------ codecs

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

  private val schema = MessageTypeParser.parseMessageType(
    "message m { required binary schemaVersion (STRING); required int64 n; }"
  )

  private def written(codec: CompressionCodecName, rows: Int = 3): Bytes = {
    val out = new MemoryOut
    val w = ExampleParquetWriter.builder(out).withType(schema).withCompressionCodec(codec).build()
    val groups = new SimpleGroupFactory(schema)
    (0 until rows).foreach(i =>
      w.write(groups.newGroup().append("schemaVersion", "x").append("n", i.toLong))
    )
    w.close()
    Bytes.of(out.bytes.toByteArray)
  }

  test("a non-SNAPPY part is refused with a typed error before any page is decompressed") {
    Vector(CompressionCodecName.UNCOMPRESSED, CompressionCodecName.GZIP).foreach { c =>
      val b = written(c)
      assertEquals(
        ParquetRows.read("x/part.parquet", b, bounds),
        Left(ParquetReadError.UnsupportedCodec("x/part.parquet", c.name)),
        c.name
      )
      assertEquals(
        ParquetRows.read("x/part.parquet", b, bounds).left.map(_.toStoreError),
        Left(
          StoreError.Refused(
            s"x/part.parquet: compression codec ${c.name} is not accepted (SNAPPY only)"
          )
        )
      )
    }
  }

  test("the accepted codec is SNAPPY only (the fixture parts above decode through SnappyRaw)") {
    assertEquals(ParquetRows.AcceptedCodec, "SNAPPY")
  }

  // ------------------------------------------------------------------ bounds

  private val path = "bridges/part-00000.parquet"

  test("a part larger than maxPartBytes is refused before its footer is read") {
    val b = fixture(path)
    val tight =
      ParquetReadBounds.of(1024 * 1024, (b.size - 1).toLong, 100000L, 1 << 20).toOption.get
    assertEquals(
      ParquetRows.read(path, b, tight),
      Left(ParquetReadError.OverBound(path, s"${b.size} bytes exceed maxPartBytes ${b.size - 1}"))
    )
  }

  test("a footer row count above maxRowsPerPart is refused before any page is read") {
    val b = fixture(path)
    val tight = ParquetReadBounds.of(1024 * 1024, 1L << 24, 97L, 1 << 20).toOption.get
    assertEquals(
      ParquetRows.read(path, b, tight),
      Left(ParquetReadError.OverBound(path, "footer row count 98 exceeds maxRowsPerPart 97"))
    )
  }

  test("a page whose declared uncompressed size exceeds maxPageBytes is refused") {
    val tight = ParquetReadBounds.of(1024 * 1024, 1L << 24, 100000L, 16).toOption.get
    ParquetRows.read(path, fixture(path), tight) match {
      // CR-P7-08 n1: a bound, so OverBound (it was Corrupt before).
      case Left(ParquetReadError.OverBound(p, d)) =>
        assertEquals(p, path)
        assert(d.contains("outside 0..maxPageBytes 16"), d)
      case other => fail(s"expected OverBound, got $other")
    }
  }

  test("bounds are validated") {
    assert(ParquetReadBounds.of(0, 1L, 1L, 1).isLeft)
    assert(ParquetReadBounds.of(1, 0L, 1L, 1).isLeft)
    assert(ParquetReadBounds.of(1, 1L, -1L, 1).isLeft)
    assert(ParquetReadBounds.of(1, 1L, 1L, 0).isLeft)
  }

  test("a truncated part is Corrupt, never thrown") {
    val b = fixture(path).toArray
    assert(ParquetRows.read(path, Bytes.of(b.take(b.length / 2)), bounds).isLeft)
  }
}
