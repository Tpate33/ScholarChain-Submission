package scholarchain.storage

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import scholarchain.core.{BenchmarkPairList, Canonical}
import scholarchain.storage.load.BenchmarkPairsReader
import OpeningFixture.*

/** SE-P7-08 item 1: the benchmark pair fixture and its production reader. */
class BenchmarkPairsSuite extends munit.FunSuite {

  private def readDir(dir: java.nio.file.Path, max: Long = benchmarkMaxBytes) =
    BenchmarkPairsReader.read(Fixtures.source(dir, benchmarkBounds), BenchmarkFile, max)

  private def withText[A](text: String)(f: java.nio.file.Path => A): A =
    Fixtures.withTemp { tmp =>
      val dir = tmp.resolve("bench")
      Files.createDirectories(dir)
      Files.write(dir.resolve(BenchmarkFile), text.getBytes(StandardCharsets.UTF_8))
      f(dir)
    }

  private lazy val original: String = utf8(Files.readAllBytes(benchmarkDir.resolve(BenchmarkFile)))

  test("fixture integrity: the benchmark fixture's sha256 is the pinned copy-time value") {
    assertEquals(
      Canonical.sha256Hex(Files.readAllBytes(benchmarkDir.resolve(BenchmarkFile))),
      BenchmarkSha256
    )
    assertEquals(
      Fixtures.source(benchmarkDir, benchmarkBounds).listFiles(),
      Right(Vector(BenchmarkFile))
    )
  }

  test("the reader accepts the fixture: 5 pairs, core digest of the decoded list") {
    val bp = readDir(benchmarkDir).fold(e => fail(e.mkString("; ")), identity)
    assertEquals(bp.list.pairs.size, 5)
    assertEquals(bp.fileSha256, BenchmarkSha256)
    assertEquals(bp.digest, BenchmarkPairList.digest(bp.list))
    assertEquals(bp.list.listVersion, "benchmark-pairs-dev.v1")
  }

  test("refused: not canonical JSON, two lines, no final LF, invalid UTF-8, oversized, absent") {
    withText(original.replace("\":", "\": ")) { d =>
      assert(readDir(d).left.exists(_.exists(_.contains("canonical"))), "non-canonical")
    }
    withText(original + original)(d => assert(readDir(d).isLeft, "two lines"))
    withText(original.stripSuffix("\n"))(d => assert(readDir(d).isLeft, "no LF"))
    Fixtures.withTemp { tmp =>
      val dir = tmp.resolve("bench")
      Files.createDirectories(dir)
      val b = original.getBytes(StandardCharsets.UTF_8)
      b(10) = 0xff.toByte
      Files.write(dir.resolve(BenchmarkFile), b)
      assert(readDir(dir).left.exists(_.exists(_.contains("UTF-8"))), "invalid UTF-8")
    }
    assert(readDir(benchmarkDir, 100).isLeft, "larger than maxBytes")
    assert(readDir(benchmarkDir, 0).isLeft, "maxBytes below 1")
    withText(original) { d =>
      assert(
        BenchmarkPairsReader.read(Fixtures.source(d, benchmarkBounds), "other.json", 1000).isLeft
      )
    }
  }

  test("refused: a list that fails the core contract (pairs out of order)") {
    val bp = readDir(benchmarkDir).toOption.get
    val swapped = bp.list.copy(pairs = bp.list.pairs.reverse)
    withText(scholarchain.core.Json.write(swapped) + "\n")(d => assert(readDir(d).isLeft))
  }
}
