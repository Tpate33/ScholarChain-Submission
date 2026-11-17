package scholarchain.spark.input

import java.io.{ByteArrayInputStream, IOException}
import java.nio.ByteBuffer
import java.nio.file.Path
import java.util.zip.{CRC32, GZIPInputStream}

import scala.util.Using

import scholarchain.core.*

/**
 * The checksum sidecar layout of Hadoop's `ChecksumFileSystem` (the local file system the producer
 * wrote the curated run with; HD-P6-01 finding 3): `.<name>.crc` holds the 4-byte magic `crc\0`,
 * the big-endian `bytesPerChecksum` (an int), then one big-endian CRC32 (the `java.util.zip.CRC32`
 * polynomial, Hadoop `DataChecksum.Type.CRC32`) per `bytesPerChecksum`-byte chunk of the file, the
 * last chunk possibly shorter. An empty file has the header only. Pure JDK: sparkAnalytics never
 * imports the hadoop module, and the test suite checks this verifier against sidecars written by
 * Hadoop's own `LocalFileSystem`.
 */
object HadoopChecksum {

  val Magic: Vector[Byte] = Vector('c'.toByte, 'r'.toByte, 'c'.toByte, 0.toByte)
  val HeaderBytes: Int = 8

  /**
   * The sidecar length for `dataBytes` bytes at `bytesPerChecksum` (header plus one int a chunk).
   */
  def expectedLength(dataBytes: Long, bytesPerChecksum: Int): Long =
    HeaderBytes.toLong + 4L * ((dataBytes + bytesPerChecksum - 1L) / bytesPerChecksum)

  /** Why `crc` is not the Hadoop checksum sidecar of `data`, or None when it verifies. */
  def problem(data: Array[Byte], crc: Array[Byte]): Option[String] =
    if (crc.length < HeaderBytes || crc.take(4).toVector != Magic)
      Some("is not a Hadoop checksum sidecar (no crc\\0 header)")
    else {
      val bpc = ByteBuffer.wrap(crc, 4, 4).getInt
      if (bpc < 1) Some(s"declares bytesPerChecksum $bpc (must be >= 1)")
      else {
        val expected = expectedLength(data.length.toLong, bpc)
        if (crc.length.toLong != expected)
          Some(
            s"has ${crc.length} bytes, but ${data.length} data bytes at $bpc bytes per checksum " +
              s"need $expected"
          )
        else {
          val chunks = ((data.length.toLong + bpc - 1L) / bpc).toInt
          (0 until chunks).iterator
            .flatMap { i =>
              val from = i.toLong * bpc
              val len = math.min(bpc.toLong, data.length.toLong - from).toInt
              val c = new CRC32
              c.update(data, from.toInt, len)
              val stored = ByteBuffer.wrap(crc, HeaderBytes + 4 * i, 4).getInt
              if (c.getValue.toInt == stored) None
              else Some(s"chunk $i (bytes $from..${from + len - 1}) does not match its CRC32")
            }
            .nextOption()
        }
      }
    }

  /** The sidecar path of `p`: `.<name>.crc` beside it. */
  def sidecarOf(p: Path): Path = p.resolveSibling(s".${p.getFileName}.crc")
}

/**
 * The part files of one consumed normalize leaf and the rows `normalize/_counters.json` declares
 * for it (`EMITTED.<leaf>`; the curated run has no part manifest, so the producer's counters are
 * the declared row counts).
 */
final case class CuratedLeaf(leaf: String, parts: Vector[Path], declaredRows: Long) {

  /** Compressed bytes of the parts (NOFOLLOW stat; an unreadable size counts as unbounded). */
  def compressedBytes: Long =
    parts.map(p => RunTree.sizeOf(p).getOrElse(Long.MaxValue / 8)).sum
}

/** The decoded curated records the canonical tables are built from (`CanonicalGraphInput`). */
final case class CuratedSources(
    works: Vector[WorkRecord],
    mentions: Vector[AuthorMention],
    venues: Vector[Venue],
    institutions: Vector[Institution],
    topics: Vector[Topic]
)

/**
 * Bounded driver read of the consumed curated parts (SP-P6-03 step 2B; HD-P6-01 rules 7 and 8
 * content for [[HadoopCuratedRun.ConsumedLeaves]] only). Per part, in leaf order and part-name
 * order: the part is read with at most the remaining byte budget, its crc sidecar (at most
 * `maxMetadataBytes`) must verify ([[HadoopChecksum]]), it must decompress as gzip within the
 * remaining budget (the budget is shared by all decompressed parts), be strict UTF-8, end in LF
 * when not empty, and every row must decode through its core contract (`Json.decode`). Each leaf's
 * decoded rows must equal its declared rows. Nothing is written.
 *
 * Driver memory: the decoded records are held on the driver only because the core validators
 * (`CanonicalGraph.expected`, `CanonicalGraphValidation.checkTables`) are driver-side; the budget
 * is the part of `maxDriverValidationBytes` the grouping and decision parts leave, and the rows
 * count towards `maxDriverValidationRows` before any part is read. The Spark job reads the same
 * parts again, distributed.
 */
object CuratedParts {

  /** The part files of each consumed leaf (a missing leaf directory has none), sorted by name. */
  def listing(runDir: Path): Either[Vector[String], Map[String, Vector[Path]]] = {
    val results = HadoopCuratedRun.ConsumedLeaves.map { leaf =>
      val dir = runDir.resolve("normalize").resolve(leaf)
      if (!RunTree.exists(dir)) Right(leaf -> Vector.empty[Path])
      else
        RunTree.names(dir).map { ns =>
          leaf -> ns
            .filter(n => !n.startsWith(".") && HadoopCuratedRun.PartName.matches(n))
            .map(dir.resolve)
        }
    }
    val bad = results.collect { case Left(w) => w }
    if (bad.nonEmpty) Left(bad) else Right(results.collect { case Right(kv) => kv }.toMap)
  }

  /** Gzip-decompressed bytes of `data`, at most `maxBytes` (Left when larger or not gzip). */
  def gunzip(data: Array[Byte], maxBytes: Long): Either[String, Array[Byte]] =
    if (maxBytes < 0L || maxBytes > RunTree.MaxArrayBytes)
      Left(s"decompression bound $maxBytes is outside 0..${RunTree.MaxArrayBytes}")
    else
      try {
        val out =
          Using.resource(new GZIPInputStream(new ByteArrayInputStream(data)))(
            _.readNBytes(maxBytes.toInt + 1)
          )
        if (out.length.toLong > maxBytes)
          Left(s"decompresses to more than the remaining driver byte budget $maxBytes")
        else Right(out)
      } catch { case e: IOException => Left(s"is not a readable gzip stream: $e") }

  private def decodeRows[T](leaf: String, p: Path, text: String)(using
      c: RecordContract[T]
  ): Vector[Either[String, T]] =
    RunTree.jsonlRows(text).zipWithIndex.map { case (row, i) =>
      Json
        .decode[T](row)
        .left
        .map(e => s"normalize/$leaf/${p.getFileName} row ${i + 1}: ${e.mkString("; ")}")
    }

  /** Reads one part: verified, decompressed, decoded; with the decompressed byte count. */
  private def part[T](leaf: String, p: Path, budget: Long, maxMetadataBytes: Long)(using
      c: RecordContract[T]
  ): Either[Vector[String], (Vector[T], Long)] = {
    val name = s"normalize/$leaf/${p.getFileName}"
    val text: Either[String, (String, Long)] = for {
      data <- RunTree.readBytes(p, budget).left.map(w => s"$name: $w")
      crc <- RunTree
        .readBytes(HadoopChecksum.sidecarOf(p), maxMetadataBytes)
        .left
        .map(w => s"$name crc sidecar: $w")
      _ <- HadoopChecksum.problem(data, crc).map(w => s"$name crc sidecar $w").toLeft(())
      raw <- gunzip(data, budget).left.map(w => s"$name $w")
      t <- RunTree.strictUtf8(raw).left.map(w => s"$name is $w")
      _ <-
        if (t.nonEmpty && !t.endsWith("\n")) Left(s"$name does not end in LF (JSON lines)")
        else Right(())
    } yield (t, raw.length.toLong)
    text match {
      case Left(w) => Left(Vector(w))
      case Right((t, n)) =>
        val rows = decodeRows[T](leaf, p, t)
        val bad = rows.collect { case Left(w) => w }
        if (bad.nonEmpty) Left(bad.take(20)) else Right((rows.collect { case Right(x) => x }, n))
    }
  }

  private def leafRecords[T](l: CuratedLeaf, budget: Long, maxMetadataBytes: Long)(using
      c: RecordContract[T]
  ): Either[Vector[String], (Vector[T], Long)] = {
    var left = budget
    var out = Vector.empty[T]
    var problems = Vector.empty[String]
    l.parts.foreach { p =>
      if (problems.isEmpty)
        part[T](l.leaf, p, left, maxMetadataBytes) match {
          case Left(w) => problems = w
          case Right((xs, n)) =>
            out = out ++ xs
            left -= n
        }
    }
    if (problems.nonEmpty) Left(problems)
    else if (out.size.toLong != l.declaredRows)
      Left(
        Vector(
          s"normalize/${l.leaf}: decoded ${out.size} rows, normalize/_counters.json declares " +
            s"${HadoopCuratedRun.emittedCounter(l.leaf)} = ${l.declaredRows}"
        )
      )
    else Right((out, budget - left))
  }

  /**
   * Reads the consumed leaves (`leaves` keyed by leaf name; a leaf missing from the map has no part
   * and declares 0 rows) with a shared decompressed-byte `budget`.
   */
  def read(
      leaves: Map[String, CuratedLeaf],
      budget: Long,
      maxMetadataBytes: Long
  ): Either[Vector[String], CuratedSources] = {
    def leaf(n: String) = leaves.getOrElse(n, CuratedLeaf(n, Vector.empty, 0L))
    for {
      works <- leafRecords[WorkRecord](leaf("works"), budget, maxMetadataBytes)
      mentions <- leafRecords[AuthorMention](leaf("mentions"), budget - works._2, maxMetadataBytes)
      venues <- leafRecords[Venue](
        leaf("venues"),
        budget - works._2 - mentions._2,
        maxMetadataBytes
      )
      institutions <- leafRecords[Institution](
        leaf("institutions"),
        budget - works._2 - mentions._2 - venues._2,
        maxMetadataBytes
      )
      topics <- leafRecords[Topic](
        leaf("topics"),
        budget - works._2 - mentions._2 - venues._2 - institutions._2,
        maxMetadataBytes
      )
    } yield CuratedSources(works._1, mentions._1, venues._1, institutions._1, topics._1)
  }
}
