package scholarchain.storage

import org.apache.parquet.hadoop.metadata.CompressionCodecName

import scholarchain.core.{PartFormat, PartManifests}
import scholarchain.storage.model.{Bytes, StoreError}
import scholarchain.storage.parquet.{ParquetFooterBounds, ParquetFooters}
import Fixtures.*

/**
 * Parquet footers of the real fixture (SE-P7-04 item 5): footer metadata only, from the fixture
 * bytes, never a page. Pins the exact codec set, so a writer change is detected.
 */
class ParquetFooterSuite extends munit.FunSuite {

  private lazy val parquetParts =
    PartManifests
      .decode(text(runDir(run1Root).resolve("_parts.json")))
      .map(PartManifests.asV2)
      .fold(e => fail(e.toString), identity)
      .files
      .filter(_.format == PartFormat.Parquet)

  private lazy val footers =
    parquetParts.map { f =>
      f -> ParquetFooters
        .read(f.path, Bytes.of(bytes(runDir(run1Root).resolve(f.path))), footerBounds)
        .fold(e => fail(s"${f.path}: $e"), identity)
    }

  test("every fixture Parquet footer reads, with the declared row count") {
    assertEquals(parquetParts.size, 29)
    footers.foreach { case (f, ft) =>
      assertEquals(ft.rowCount, f.rowCount, f.path)
      assert(ft.rowGroups >= 1, f.path)
      assert(ft.codecs.nonEmpty, f.path)
    }
  }

  test("the exact set of column-chunk compression codecs in the fixture") {
    val perFile = footers.map { case (f, ft) => f.path -> ft.codecSet }
    assertEquals(perFile.map(_._2).toSet, Set(Set("SNAPPY")), perFile.mkString("\n"))
    assertEquals(footers.flatMap(_._2.codecSet).toSet, Set("SNAPPY"))
  }

  test("the fixture writer string") {
    assertEquals(
      footers.map(_._2.createdBy).toSet,
      Set("parquet-mr version 1.13.1 (build db4183109d5b734ec5930d870cdae161e408ddba)")
    )
  }

  test("codec to decoder class: what parquet-hadoop would load for the observed codecs") {
    val observed = footers.flatMap(_._2.codecSet).toSet
    val classes =
      observed.map(c => c -> CompressionCodecName.valueOf(c).getHadoopCompressionCodecClassName)
    assertEquals(classes, Set("SNAPPY" -> "org.apache.parquet.hadoop.codec.SnappyCodec"))
    // The codec NAME mapping of parquet-mr 1.13.1 (strings only; no codec class is loaded).
    assertEquals(
      Vector("ZSTD", "LZ4_RAW").map(c =>
        c -> CompressionCodecName.valueOf(c).getHadoopCompressionCodecClassName
      ),
      Vector(
        "ZSTD" -> "org.apache.parquet.hadoop.codec.ZstandardCodec",
        "LZ4_RAW" -> "org.apache.parquet.hadoop.codec.Lz4RawCodec"
      )
    )
    // SE-P7-11 item 2 (DU-P7-02 plan): only what the Test scope keeps after the codec exclusion
    // is asserted present: parquet-hadoop's own codec wrapper and snappy-java (kept at Test). The
    // presence or absence of zstd-jni and aircompressor is NOT asserted here (build-engineer's
    // exclusion removes them; ParquetCodecIsolationSuite shows production reads need neither).
    // Loaded without initialisation (no native code).
    val loader = getClass.getClassLoader
    def present(n: String) =
      try { Class.forName(n, false, loader); true }
      catch { case _: ClassNotFoundException => false }
    assertEquals(
      Vector("org.apache.parquet.hadoop.codec.SnappyCodec", "org.xerial.snappy.Snappy")
        .map(n => n -> present(n)),
      Vector(
        "org.apache.parquet.hadoop.codec.SnappyCodec" -> true,
        "org.xerial.snappy.Snappy" -> true
      )
    )
  }

  test("bytes that are not Parquet are refused as Corrupt") {
    val f = parquetParts.head
    val b = bytes(runDir(run1Root).resolve(f.path))
    Vector(
      Bytes.utf8("not parquet"),
      Bytes.of(b.take(b.length - 8)),
      Bytes.of(Array.emptyByteArray)
    )
      .foreach { bad =>
        ParquetFooters.read(f.path, bad, footerBounds) match {
          case Left(StoreError.Corrupt(d)) => assert(d.contains("not a readable Parquet footer"), d)
          case other => fail(s"expected Corrupt, got $other")
        }
      }
  }

  // ------------------------------------------------------------------ A5: frame checked before parsing

  /** The fixture part with its little-endian footer length replaced by `declared`. */
  private def withDeclared(declared: Long): Array[Byte] = {
    val b = bytes(runDir(run1Root).resolve(parquetParts.head.path))
    val at = b.length - 8
    (0 until 4).foreach(i => b(at + i) = ((declared >>> (8 * i)) & 0xff).toByte)
    b
  }

  private def corrupt(b: Array[Byte], bounds: ParquetFooterBounds, fragment: String): Unit =
    ParquetFooters.read("x.parquet", Bytes.of(b), bounds) match {
      case Left(StoreError.Corrupt(d)) =>
        assert(d.contains(fragment), d)
        assert(!d.contains("Exception"), s"refused before parquet-mr ran: $d")
      case other => fail(s"expected Corrupt containing '$fragment', got $other")
    }

  test("A5: a valid trailing PAR1 with a zero or out-of-range footer length is refused unparsed") {
    val size = bytes(runDir(run1Root).resolve(parquetParts.head.path)).length.toLong
    assert(withDeclared(0).takeRight(4).sameElements("PAR1".getBytes("US-ASCII")), "magic kept")
    corrupt(withDeclared(0), footerBounds, "declared footer length 0")
    corrupt(withDeclared(size - 11), footerBounds, s"declared footer length ${size - 11} exceeds")
    corrupt(withDeclared(size), footerBounds, s"declared footer length $size exceeds")
    corrupt(withDeclared(0xffffffffL), footerBounds, "declared footer length 4294967295 exceeds")
    // At the frame limit (size - 12) the frame check passes and parquet-mr itself refuses.
    ParquetFooters.read("x.parquet", Bytes.of(withDeclared(size - 12)), footerBounds) match {
      case Left(StoreError.Corrupt(d)) => assert(d.contains("not a readable Parquet footer"), d)
      case other => fail(s"expected Corrupt, got $other")
    }
  }

  test("A5: the configured maximum footer length refuses a real footer that exceeds it") {
    val b = bytes(runDir(run1Root).resolve(parquetParts.head.path))
    val at = b.length - 8
    val declared = (0 until 4).map(i => (b(at + i) & 0xffL) << (8 * i)).sum
    assert(declared > 1, s"the fixture footer is $declared bytes")
    val tight = ParquetFooterBounds.of((declared - 1).toInt).toOption.get
    corrupt(b, tight, s"exceeds maxFooterBytes ${declared - 1}")
    val exact = ParquetFooterBounds.of(declared.toInt).toOption.get
    assert(ParquetFooters.read("x.parquet", Bytes.of(b), exact).isRight, "the exact bound reads")
    assert(ParquetFooterBounds.of(0).isLeft, "the bound is at least 1")
  }

  test("A5: frames without both magics are refused before parsing") {
    val b = bytes(runDir(run1Root).resolve(parquetParts.head.path))
    val noLead = b.clone(); noLead(0) = 'X'.toByte
    corrupt(noLead, footerBounds, "does not start with PAR1")
    val noTrail = b.clone(); noTrail(b.length - 1) = 'X'.toByte
    corrupt(noTrail, footerBounds, "does not end with PAR1")
    corrupt("PAR1PAR1".getBytes("US-ASCII"), footerBounds, "shorter than the 12-byte Parquet frame")
  }
}
