package scholarchain.storage.parquet

import java.io.EOFException
import java.nio.ByteBuffer

import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.control.NonFatal

import org.apache.parquet.ParquetReadOptions
import org.apache.parquet.compression.CompressionCodecFactory
import org.apache.parquet.hadoop.ParquetFileReader
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.apache.parquet.io.{InputFile, SeekableInputStream}

import scholarchain.storage.model.{Bytes, StoreError}

/** The compression codec of one column chunk, as its footer metadata names it. */
final case class ColumnChunkCodec(rowGroup: Int, column: String, codec: String)

/**
 * The footer metadata of one Parquet file (SE-P7-04 item 5): no page is read or decompressed.
 *
 * @param rowCount
 *   the sum of the row-group row counts
 * @param createdBy
 *   the writer string of the file metadata (for example the parquet-mr version)
 * @param codecs
 *   every column chunk's codec, in row-group then column order
 */
final case class ParquetFooter(
    rowCount: Long,
    rowGroups: Int,
    createdBy: String,
    codecs: Vector[ColumnChunkCodec]
) {

  /** The distinct codec names (`CompressionCodecName` constants) of the file's column chunks. */
  def codecSet: Set[String] = codecs.map(_.codec).toSet
}

/**
 * Bound of the footer a reader accepts (SE-P7-05 item A5). Configuration: passed in, no default.
 *
 * @param maxFooterBytes
 *   the largest declared footer length (the 4-byte little-endian length before the trailing `PAR1`)
 *   handed to parquet-mr's Thrift decoder
 */
final case class ParquetFooterBounds private (maxFooterBytes: Int)

object ParquetFooterBounds {
  def of(maxFooterBytes: Int): Either[StoreError, ParquetFooterBounds] =
    if (maxFooterBytes < 1)
      Left(StoreError.Refused(s"maxFooterBytes: $maxFooterBytes is below 1"))
    else Right(new ParquetFooterBounds(maxFooterBytes))
}

/**
 * Reads Parquet footers from bytes already in memory (verified bytes read through a load permit),
 * through parquet-hadoop's `ParquetFileReader` over an in-memory `InputFile`: no file system, no
 * Hadoop `FileSystem`, no network. Opening the reader parses only the footer; codecs are recorded
 * as names, and no decompressor is requested. Start of the Parquet-reader increment: row decoding
 * is not here.
 *
 * Before anything reaches parquet-mr (SE-P7-05 item A5, the Thrift concern of CR-P7-05): the bytes
 * must start and end with the magic `PAR1`, and the declared footer length must be at least 1, at
 * most `size - 12` (the footer lies between the leading magic and the trailing length and magic, so
 * this is stricter than `< size - 8`) and at most [[ParquetFooterBounds.maxFooterBytes]]. A
 * declared length outside these bounds is `Corrupt` without parsing. A `StackOverflowError` from a
 * deeply nested Thrift structure is also `Corrupt`; an `OutOfMemoryError` is not caught (the length
 * bound limits what a crafted footer can declare in bytes, not what Thrift may allocate for a
 * declared container size: residual, see the report).
 *
 * The reader options carry a codec factory that refuses every codec: footer reading never needs
 * one, and supplying it keeps `ParquetReadOptions.builder().build()` from creating its default
 * Hadoop-backed factory (which, by reading parquet-mr 1.13.1 from memory, constructs a Hadoop
 * `Configuration`; UNVERIFIED, see the report).
 */
object ParquetFooters {

  /** The Parquet magic bytes `PAR1`. */
  private val Magic: Array[Byte] = "PAR1".getBytes(java.nio.charset.StandardCharsets.US_ASCII)

  /** Bytes of the leading magic plus the trailing footer length and magic (format constants). */
  private val FrameBytes: Long = 12L

  /** Why the frame of `data` is refused before parsing, or None. */
  def frameProblem(data: Array[Byte], bounds: ParquetFooterBounds): Option[String] = {
    val n = data.length.toLong
    def magicAt(at: Int) = java.util.Arrays.equals(data, at, at + 4, Magic, 0, 4)
    if (n < FrameBytes) Some(s"$n bytes: shorter than the $FrameBytes-byte Parquet frame")
    else if (!magicAt(0)) Some("does not start with PAR1")
    else if (!magicAt(data.length - 4)) Some("does not end with PAR1")
    else {
      val at = data.length - 8
      val declared = (data(at) & 0xffL) | ((data(at + 1) & 0xffL) << 8) |
        ((data(at + 2) & 0xffL) << 16) | ((data(at + 3) & 0xffL) << 24)
      if (declared < 1) Some("declared footer length 0")
      else if (declared > n - FrameBytes)
        Some(s"declared footer length $declared exceeds the ${n - FrameBytes} bytes available")
      else if (declared > bounds.maxFooterBytes)
        Some(s"declared footer length $declared exceeds maxFooterBytes ${bounds.maxFooterBytes}")
      else None
    }
  }

  def read(
      path: String,
      bytes: Bytes,
      bounds: ParquetFooterBounds
  ): Either[StoreError, ParquetFooter] = {
    val data = bytes.toArray
    frameProblem(data, bounds) match {
      case Some(why) => Left(StoreError.Corrupt(s"$path: not a readable Parquet footer ($why)"))
      case None => parse(path, data)
    }
  }

  private def options: ParquetReadOptions =
    ParquetReadOptions.builder().withCodecFactory(NoCodecs).build()

  private def parse(path: String, data: Array[Byte]): Either[StoreError, ParquetFooter] =
    try
      Using.resource(ParquetFileReader.open(new ArrayInputFile(data), options)) { r =>
        val meta = r.getFooter
        val blocks = meta.getBlocks.asScala.toVector
        footerProblem(blocks, data.length.toLong) match {
          case Some(why) =>
            Left(StoreError.Corrupt(s"$path: not a readable Parquet footer ($why)"))
          case None =>
            Right(
              ParquetFooter(
                blocks.foldLeft(0L)((acc, b) => Math.addExact(acc, b.getRowCount)),
                blocks.size,
                Option(meta.getFileMetaData.getCreatedBy).getOrElse(""),
                blocks.zipWithIndex.flatMap { case (b, i) =>
                  b.getColumns.asScala.toVector
                    .map(c => ColumnChunkCodec(i, c.getPath.toDotString, c.getCodec.name))
                }
              )
            )
        }
      }
    catch {
      case NonFatal(e) =>
        Left(
          StoreError.Corrupt(s"$path: not a readable Parquet footer (${e.getClass.getSimpleName})")
        )
      case _: StackOverflowError =>
        Left(StoreError.Corrupt(s"$path: not a readable Parquet footer (nesting too deep)"))
    }

  /**
   * Why the declared row groups are refused (CR-P7-08 M1, m1), or None: every row-group row count
   * is at least 0 and their sum does not overflow a Long; every column chunk lies inside the part
   * (starting position at least 4, after the leading magic; total compressed size at least 0; start
   * plus size, computed without overflow, at most the part's byte count). Checked before any page
   * is read, so a crafted footer cannot make the reader allocate a chunk larger than the part.
   */
  private[parquet] def footerProblem(
      blocks: Vector[org.apache.parquet.hadoop.metadata.BlockMetaData],
      partBytes: Long
  ): Option[String] = {
    val negative = blocks.zipWithIndex.find(_._1.getRowCount < 0)
    val sum =
      try Right(blocks.foldLeft(0L)((acc, b) => Math.addExact(acc, b.getRowCount)))
      catch { case _: ArithmeticException => Left(()) }
    negative match {
      case Some((b, i)) => Some(s"row group $i declares ${b.getRowCount} rows")
      case None if sum.isLeft => Some("the row-group row counts overflow")
      case None =>
        blocks.zipWithIndex.iterator
          .flatMap { case (b, i) =>
            b.getColumns.asScala.iterator.map(c => (i, c))
          }
          .collectFirst {
            case (i, c) if chunkOutside(c.getStartingPos, c.getTotalSize, partBytes) =>
              s"row group $i column ${c.getPath.toDotString} declares ${c.getTotalSize} bytes at " +
                s"${c.getStartingPos}, outside the $partBytes-byte part"
          }
    }
  }

  private def chunkOutside(start: Long, size: Long, partBytes: Long): Boolean =
    start < 4 || size < 0 || {
      try Math.addExact(start, size) > partBytes
      catch { case _: ArithmeticException => true }
    }

  /** Footer reading needs no codec: every request is refused. */
  private object NoCodecs extends CompressionCodecFactory {
    def getCompressor(c: CompressionCodecName): CompressionCodecFactory.BytesInputCompressor =
      throw new UnsupportedOperationException(s"footer reading requests no codec ($c)")
    def getDecompressor(c: CompressionCodecName): CompressionCodecFactory.BytesInputDecompressor =
      throw new UnsupportedOperationException(s"footer reading requests no codec ($c)")
    def release(): Unit = ()
  }

  /** A Parquet `InputFile` over an array (never written); shared with [[ParquetRows]]. */
  private[parquet] final class ArrayInputFile(data: Array[Byte]) extends InputFile {
    def getLength(): Long = data.length.toLong
    def newStream(): SeekableInputStream = new ArrayStream(data)
  }

  private final class ArrayStream(data: Array[Byte]) extends SeekableInputStream {
    private var pos = 0

    private def remaining: Int = data.length - pos

    override def getPos(): Long = pos.toLong

    override def seek(newPos: Long): Unit =
      if (newPos < 0 || newPos > data.length) throw new EOFException(s"seek to $newPos")
      else pos = newPos.toInt

    override def read(): Int =
      if (remaining <= 0) -1
      else { val b = data(pos) & 0xff; pos += 1; b }

    override def read(b: Array[Byte], off: Int, len: Int): Int =
      if (len == 0) 0
      else if (remaining <= 0) -1
      else {
        val n = math.min(len, remaining)
        System.arraycopy(data, pos, b, off, n)
        pos += n
        n
      }

    override def readFully(bytes: Array[Byte]): Unit = readFully(bytes, 0, bytes.length)

    override def readFully(bytes: Array[Byte], start: Int, len: Int): Unit =
      if (len > remaining) throw new EOFException(s"$len bytes requested, $remaining left")
      else { System.arraycopy(data, pos, bytes, start, len); pos += len }

    override def read(buf: ByteBuffer): Int =
      if (!buf.hasRemaining) 0
      else if (remaining <= 0) -1
      else {
        val n = math.min(buf.remaining, remaining)
        buf.put(data, pos, n)
        pos += n
        n
      }

    override def readFully(buf: ByteBuffer): Unit = {
      val n = buf.remaining
      if (n > remaining) throw new EOFException(s"$n bytes requested, $remaining left")
      else { buf.put(data, pos, n); pos += n }
    }
  }
}
