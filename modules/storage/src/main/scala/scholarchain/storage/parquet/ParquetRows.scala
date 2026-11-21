package scholarchain.storage.parquet

import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}

import scala.util.Using
import scala.util.control.NonFatal

import org.apache.parquet.ParquetReadOptions
import org.apache.parquet.bytes.BytesInput
import org.apache.parquet.compression.CompressionCodecFactory
import org.apache.parquet.example.data.Group
import org.apache.parquet.example.data.simple.convert.GroupRecordConverter
import org.apache.parquet.hadoop.ParquetFileReader
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.apache.parquet.io.ColumnIOFactory
import org.apache.parquet.schema.{GroupType, LogicalTypeAnnotation, PrimitiveType, Type}

import scholarchain.storage.model.{Bytes, StoreError}

/**
 * Bounds of one Parquet part read (SE-P7-06 item 1). Configuration: passed in, no default in main
 * code.
 *
 * @param maxPartBytes
 *   the largest part (bytes) decoded at all
 * @param maxRowsPerPart
 *   the largest footer row count decoded (checked before any page is read)
 * @param maxPageBytes
 *   the largest uncompressed page size accepted from a page header (checked before a page buffer is
 *   allocated)
 */
final case class ParquetReadBounds private (
    footer: ParquetFooterBounds,
    maxPartBytes: Long,
    maxRowsPerPart: Long,
    maxPageBytes: Int
)

object ParquetReadBounds {
  def of(
      maxFooterBytes: Int,
      maxPartBytes: Long,
      maxRowsPerPart: Long,
      maxPageBytes: Int
  ): Either[StoreError, ParquetReadBounds] =
    for {
      f <- ParquetFooterBounds.of(maxFooterBytes)
      _ <- if (maxPartBytes >= 1) Right(()) else Left(StoreError.Refused("maxPartBytes below 1"))
      _ <-
        if (maxRowsPerPart >= 0) Right(()) else Left(StoreError.Refused("maxRowsPerPart below 0"))
      _ <- if (maxPageBytes >= 1) Right(()) else Left(StoreError.Refused("maxPageBytes below 1"))
    } yield new ParquetReadBounds(f, maxPartBytes, maxRowsPerPart, maxPageBytes)
}

/**
 * A decoded Parquet value, typed by the physical column type (SE-P7-06 item 1). Strings are the
 * `BINARY (STRING)` columns decoded as strict UTF-8; groups are structs; groups annotated `LIST`
 * (the three-level list layout Spark writes) are lists; an absent optional value is
 * [[PValue.Null]].
 */
enum PValue {
  case Null
  case Str(value: String)
  case I32(value: Int)
  case I64(value: Long)
  case F64(value: Double)
  case Bool(value: Boolean)
  case Struct(fields: Vector[(String, PValue)])
  case ListOf(items: Vector[PValue])
}

/** Why a Parquet part could not be decoded (typed; SE-P7-06 item 1). */
enum ParquetReadError {

  /** A column chunk uses a codec other than SNAPPY; nothing was decompressed. */
  case UnsupportedCodec(path: String, codec: String)

  /** The part or its declared row count exceeds the configured bounds; nothing was decoded. */
  case OverBound(path: String, detail: String)

  /** The bytes are not a Parquet file this reader decodes. */
  case Corrupt(path: String, detail: String)

  def toStoreError: StoreError = this match {
    case UnsupportedCodec(p, c) =>
      StoreError.Refused(s"$p: compression codec $c is not accepted (SNAPPY only)")
    case OverBound(p, d) => StoreError.Refused(s"$p: $d")
    case Corrupt(p, d) => StoreError.Corrupt(s"$p: $d")
  }
}

/** One decoded part: its footer and every row, in file order. */
final case class ParquetPart(
    footer: ParquetFooter,
    columns: Vector[String],
    rows: Vector[PValue.Struct]
)

/**
 * Decodes the rows of one Parquet part from bytes already in memory (verified bytes), through
 * parquet-mr's `ParquetFileReader` over an in-memory `InputFile` and the example `Group`
 * materializer: no file system, no Hadoop `FileSystem`, no network. SE-P7-06 item 1.
 *
 * Order, each step before the next one runs:
 *   1. the part is at most `maxPartBytes`;
 *   2. the footer frame and footer ([[ParquetFooters.read]], its own bounds); it also refuses a
 *      negative or overflowing row-group row count and any column chunk whose starting position and
 *      total compressed size do not lie inside the part (CR-P7-08 M1, m1);
 *   3. every column chunk's codec is SNAPPY ([[ParquetReadError.UnsupportedCodec]] otherwise);
 *   4. the footer row count is at most `maxRowsPerPart`;
 *   5. pages are read with a codec factory that hands out only the pure [[SnappyRaw]] decoder (any
 *      other codec request is refused) and refuses a page whose declared uncompressed size exceeds
 *      `maxPageBytes`;
 *   6. every value is converted by its physical type: INT32, INT64, DOUBLE, BOOLEAN without a
 *      logical annotation, BINARY only as STRING (strict UTF-8), groups as structs or three-level
 *      LISTs; any other type, annotation or list shape is `Corrupt`;
 *   7. the decoded row count equals the footer row count; the running count is checked before each
 *      row is appended, so no more than `min(maxRowsPerPart, footer rows)` rows are ever held
 *      (`OverBound` otherwise, CR-P7-08 M1). A page whose declared uncompressed size exceeds
 *      `maxPageBytes` is `OverBound` (n1). Uncompressed DataPageV2 pages (`is_compressed` false)
 *      never reach the decompressor; their size is bounded by the column-chunk check of step 2.
 *
 * Hadoop `Configuration`: the reader options carry this codec factory, so
 * `ParquetReadOptions.builder().build()` does not create its default Hadoop-backed factory, and the
 * `InputFile` constructor of `ParquetFileReader` takes no `Configuration`. Whether parquet-mr
 * 1.13.1 builds a `Configuration` anywhere on this path (and reads a classpath `core-site.xml`) is
 * not observed (see the report).
 *
 * Memory: the part bytes (at most `maxPartBytes`), one row group's column chunks (at most the
 * part), one decompressed page at a time (at most `maxPageBytes`), and the decoded rows (at most
 * `maxRowsPerPart`).
 */
object ParquetRows {

  /** The only codec accepted (the codec observed in every fixture part, SE-P7-04). */
  val AcceptedCodec: String = CompressionCodecName.SNAPPY.name

  private final class CodecRequest(val codec: String)
      extends RuntimeException(codec, null, false, false)

  private final class Shape(why: String) extends RuntimeException(why, null, false, false)

  private def shape(why: String): Nothing = throw new Shape(why)

  /** The running row count reached the bound (CR-P7-08 M1). */
  private final class RowBound(val row: Long)
      extends RuntimeException(row.toString, null, false, false)

  /** A page declares more uncompressed bytes than `maxPageBytes` (CR-P7-08 n1: OverBound). */
  private final class PageBound(why: String) extends RuntimeException(why, null, false, false)

  def read(
      path: String,
      bytes: Bytes,
      bounds: ParquetReadBounds
  ): Either[ParquetReadError, ParquetPart] =
    if (bytes.size.toLong > bounds.maxPartBytes)
      Left(
        ParquetReadError
          .OverBound(path, s"${bytes.size} bytes exceed maxPartBytes ${bounds.maxPartBytes}")
      )
    else
      ParquetFooters.read(path, bytes, bounds.footer) match {
        case Left(e) => Left(ParquetReadError.Corrupt(path, e.message))
        case Right(footer) =>
          footer.codecs.find(_.codec != AcceptedCodec) match {
            case Some(c) => Left(ParquetReadError.UnsupportedCodec(path, c.codec))
            case None if footer.rowCount > bounds.maxRowsPerPart =>
              Left(
                ParquetReadError.OverBound(
                  path,
                  s"footer row count ${footer.rowCount} exceeds maxRowsPerPart ${bounds.maxRowsPerPart}"
                )
              )
            case None => decode(path, bytes.toArray, footer, bounds)
          }
      }

  private def decode(
      path: String,
      data: Array[Byte],
      footer: ParquetFooter,
      bounds: ParquetReadBounds
  ): Either[ParquetReadError, ParquetPart] =
    try {
      val options =
        ParquetReadOptions.builder().withCodecFactory(new SnappyOnly(bounds.maxPageBytes)).build()
      Using.resource(ParquetFileReader.open(new ParquetFooters.ArrayInputFile(data), options)) {
        r =>
          val schema = r.getFooter.getFileMetaData.getSchema
          val io = new ColumnIOFactory().getColumnIO(schema)
          val rows = Vector.newBuilder[PValue.Struct]
          var count = 0L
          // CR-P7-08 M1: the running row count is bounded inside the loop, before a row is
          // appended, by both the bound and the footer's (overflow-checked) total.
          val limit = math.min(bounds.maxRowsPerPart, footer.rowCount)
          var pages = r.readNextRowGroup()
          while (pages != null) {
            if (pages.getRowCount < 0) shape(s"a row group declares ${pages.getRowCount} rows")
            val rr = io.getRecordReader(pages, new GroupRecordConverter(schema))
            var i = 0L
            while (i < pages.getRowCount) {
              if (count >= limit) throw new RowBound(count + 1)
              val g = rr.read()
              if (g == null) shape("a row decoded to nothing")
              rows += struct(g, schema)
              count += 1
              i += 1
            }
            pages = r.readNextRowGroup()
          }
          if (count != footer.rowCount)
            Left(
              ParquetReadError
                .Corrupt(path, s"decoded $count rows; the footer declares ${footer.rowCount}")
            )
          else {
            val columns = (0 until schema.getFieldCount).map(schema.getFieldName).toVector
            Right(ParquetPart(footer, columns, rows.result()))
          }
      }
    } catch {
      case c: CodecRequest => Left(ParquetReadError.UnsupportedCodec(path, c.codec))
      case b: RowBound =>
        Left(
          ParquetReadError.OverBound(
            path,
            s"row ${b.row} exceeds maxRowsPerPart ${bounds.maxRowsPerPart} or the footer total ${footer.rowCount}"
          )
        )
      case p: PageBound => Left(ParquetReadError.OverBound(path, p.getMessage))
      case s: Shape => Left(ParquetReadError.Corrupt(path, s.getMessage))
      case NonFatal(e) =>
        // parquet-mr may wrap an error raised inside the page decompressor (CR-P7-08 n1, n2).
        causes(e).collectFirst {
          case c: CodecRequest => ParquetReadError.UnsupportedCodec(path, c.codec)
          case p: PageBound => ParquetReadError.OverBound(path, p.getMessage)
          case s: Shape => ParquetReadError.Corrupt(path, s.getMessage)
        } match {
          case Some(err) => Left(err)
          case None =>
            Left(ParquetReadError.Corrupt(path, s"undecodable (${e.getClass.getSimpleName})"))
        }
      case _: StackOverflowError => Left(ParquetReadError.Corrupt(path, "nesting too deep"))
    }

  /** `e` and its causes, at most 16 deep (a cause cycle ends the walk). */
  private def causes(e: Throwable): Vector[Throwable] =
    Iterator.iterate(e)(_.getCause).takeWhile(_ != null).take(16).toVector

  // ------------------------------------------------------------------ value conversion

  private def struct(g: Group, t: GroupType): PValue.Struct =
    PValue.Struct(
      (0 until t.getFieldCount).toVector.map(i => t.getFieldName(i) -> field(g, i, t.getType(i)))
    )

  private def field(g: Group, i: Int, t: Type): PValue = {
    val n = g.getFieldRepetitionCount(i)
    if (t.isRepetition(Type.Repetition.REPEATED))
      shape(s"${t.getName}: a repeated field outside a LIST group")
    else if (n == 0) {
      if (t.isRepetition(Type.Repetition.REQUIRED)) shape(s"${t.getName}: required value absent")
      PValue.Null
    } else if (n > 1) shape(s"${t.getName}: $n values in a non-repeated field")
    else single(g, i, 0, t)
  }

  private def single(g: Group, i: Int, j: Int, t: Type): PValue =
    if (t.isPrimitive) primitive(g, i, j, t.asPrimitiveType)
    else {
      val gt = t.asGroupType
      gt.getLogicalTypeAnnotation match {
        case null => struct(g.getGroup(i, j), gt)
        case _: LogicalTypeAnnotation.ListLogicalTypeAnnotation => list(g.getGroup(i, j), gt)
        case other => shape(s"${t.getName}: group annotation $other is not decoded")
      }
    }

  /** The three-level LIST layout: `<list> (LIST) { repeated group list { <element> } }`. */
  private def list(g: Group, t: GroupType): PValue =
    if (t.getFieldCount != 1) shape(s"${t.getName}: a LIST group has one repeated field")
    else {
      val r = t.getType(0)
      if (!r.isRepetition(Type.Repetition.REPEATED) || r.isPrimitive)
        shape(s"${t.getName}: not the three-level LIST layout")
      else {
        val rg = r.asGroupType
        if (rg.getFieldCount != 1) shape(s"${t.getName}: a LIST entry holds one element")
        else {
          val n = g.getFieldRepetitionCount(0)
          PValue.ListOf((0 until n).toVector.map { j =>
            field(g.getGroup(0, j), 0, rg.getType(0))
          })
        }
      }
    }

  private def primitive(g: Group, i: Int, j: Int, t: PrimitiveType): PValue = {
    val annotation = t.getLogicalTypeAnnotation
    def plain[A](v: => A): A =
      if (annotation == null) v else shape(s"${t.getName}: annotation $annotation is not decoded")
    t.getPrimitiveTypeName match {
      case PrimitiveType.PrimitiveTypeName.INT32 => plain(PValue.I32(g.getInteger(i, j)))
      case PrimitiveType.PrimitiveTypeName.INT64 => plain(PValue.I64(g.getLong(i, j)))
      case PrimitiveType.PrimitiveTypeName.DOUBLE => plain(PValue.F64(g.getDouble(i, j)))
      case PrimitiveType.PrimitiveTypeName.BOOLEAN => plain(PValue.Bool(g.getBoolean(i, j)))
      case PrimitiveType.PrimitiveTypeName.BINARY =>
        annotation match {
          case _: LogicalTypeAnnotation.StringLogicalTypeAnnotation =>
            PValue.Str(utf8(t.getName, g.getBinary(i, j).toByteBuffer))
          case _ => shape(s"${t.getName}: BINARY is decoded only as STRING")
        }
      case other => shape(s"${t.getName}: physical type $other is not decoded")
    }
  }

  private def utf8(name: String, b: ByteBuffer): String =
    try
      StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(b)
        .toString
    catch { case _: CharacterCodingException => shape(s"$name: not valid UTF-8") }

  // ------------------------------------------------------------------ codecs

  /** Hands out the pure SNAPPY decoder only; every other request (and every compressor) refuses. */
  private final class SnappyOnly(maxPageBytes: Int) extends CompressionCodecFactory {
    def getCompressor(c: CompressionCodecName): CompressionCodecFactory.BytesInputCompressor =
      throw new CodecRequest(s"compressor ${c.name}")
    def getDecompressor(c: CompressionCodecName): CompressionCodecFactory.BytesInputDecompressor =
      if (c == CompressionCodecName.SNAPPY) new SnappyPages(maxPageBytes)
      else throw new CodecRequest(String.valueOf(c))
    def release(): Unit = ()
  }

  private final class SnappyPages(maxPageBytes: Int)
      extends CompressionCodecFactory.BytesInputDecompressor {
    private def run(in: Array[Byte], expected: Int): Array[Byte] =
      if (expected < 0 || expected > maxPageBytes)
        throw new PageBound(
          s"SNAPPY page: declared uncompressed size $expected is outside 0..maxPageBytes $maxPageBytes"
        )
      else
        SnappyRaw.decompress(in, 0, in.length, expected, maxPageBytes) match {
          case Right(out) => out
          case Left(why) => shape(s"SNAPPY page: $why")
        }

    def decompress(bytes: BytesInput, uncompressedSize: Int): BytesInput =
      BytesInput.from(run(bytes.toByteArray, uncompressedSize), 0, uncompressedSize)

    def decompress(
        input: ByteBuffer,
        compressedSize: Int,
        output: ByteBuffer,
        uncompressedSize: Int
    ): Unit = {
      val in = new Array[Byte](compressedSize)
      input.get(in)
      output.put(run(in, uncompressedSize))
      ()
    }

    def release(): Unit = ()
  }
}
