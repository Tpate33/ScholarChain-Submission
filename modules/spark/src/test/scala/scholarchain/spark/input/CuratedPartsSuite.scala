package scholarchain.spark.input

import java.nio.file.{Files, Path}

import scholarchain.spark.testkit.{LocalSpark, RunFixtures}

/**
 * The curated-part verifier (SP-P6-03 step 2B, HD-P6-01 rules 7 and 8 content) against sidecars
 * written by Hadoop's own `LocalFileSystem` (the producer's library; [[RunFixtures.withCrc]]), so
 * the CRC32 layout is checked against Hadoop, not against itself. Counter-tests: one changed data
 * byte, a truncated sidecar, a sidecar of another file, a corrupt header; gzip and its byte bound.
 */
class CuratedPartsSuite extends munit.FunSuite {

  private val tmp = FunFixture[Path](
    _ => Files.createTempDirectory("scholarchain-curated-parts-"),
    p => LocalSpark.deleteTree(p)
  )

  private def bytes(n: Int): Array[Byte] = Array.tabulate(n)(i => ((i * 31 + 7) % 251).toByte)

  private def written(dir: Path, name: String, data: Array[Byte]): (Array[Byte], Array[Byte]) = {
    val p = dir.resolve(name)
    RunFixtures.withCrc(p, data)
    (Files.readAllBytes(p), Files.readAllBytes(HadoopChecksum.sidecarOf(p)))
  }

  tmp.test("Hadoop-written sidecars verify: empty, one byte, exactly one chunk, three chunks") {
    dir =>
      Vector(0, 1, 512, 1500).foreach { n =>
        val (data, crc) = written(dir, s"f$n", bytes(n))
        assertEquals(data.length, n)
        // Hadoop's default 512 bytes per checksum: 8 header bytes plus 4 per chunk.
        assertEquals(crc.length.toLong, HadoopChecksum.expectedLength(n.toLong, 512))
        assertEquals(HadoopChecksum.problem(data, crc), None, s"$n bytes")
      }
  }

  tmp.test("counter-tests: a changed byte, a truncated or foreign sidecar, a corrupt header") {
    dir =>
      val (data, crc) = written(dir, "f", bytes(1500))
      val changed = data.clone()
      changed(1000) = (changed(1000) ^ 1).toByte
      assert(
        HadoopChecksum.problem(changed, crc).exists(_.contains("chunk 1 (bytes 512..1023)")),
        HadoopChecksum.problem(changed, crc)
      )
      assert(HadoopChecksum.problem(data, crc.dropRight(4)).exists(_.contains("need 20")))
      val (_, other) = written(dir, "g", bytes(1499).map(b => (b ^ 3).toByte))
      assert(HadoopChecksum.problem(data, other).isDefined)
      val bad = crc.clone()
      bad(0) = 'x'.toByte
      assert(HadoopChecksum.problem(data, bad).exists(_.contains("no crc\\0 header")))
      assertEquals(
        HadoopChecksum.problem(Array.emptyByteArray, Array.emptyByteArray).isDefined,
        true
      )
  }

  test("gunzip: round trip, byte bound, and a non-gzip input") {
    val text = "{\"a\":1}\n" * 50
    val gz = RunFixtures.gzip(text)
    assertEquals(
      CuratedParts.gunzip(gz, text.length.toLong).map(new String(_, "UTF-8")),
      Right(text)
    )
    assert(
      CuratedParts
        .gunzip(gz, text.length.toLong - 1)
        .left
        .exists(_.contains("more than the remaining driver byte budget"))
    )
    assert(CuratedParts.gunzip(text.getBytes("UTF-8"), 1000).left.exists(_.contains("gzip")))
  }
}
