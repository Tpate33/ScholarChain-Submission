package scholarchain.storage

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scholarchain.core.TargetStore
import scholarchain.storage.load.*
import OpeningFixture.*

/**
 * CR-P7-09 m1: negative tests of the upstream readers and of the upstream-dependent checks. Every
 * change is made to a temporary copy; the fixtures are only read.
 */
class UpstreamRefusalsSuite extends munit.FunSuite {

  private val Works = "normalize/works/part-r-00000.gz"
  private val Counters = "normalize/_counters.json"

  private def curatedCopy[A](f: Path => A): A =
    Fixtures.withTemp(tmp => f(copyOf(UpstreamFixture.curatedDir, tmp, "curated")))

  private def groupingCopy[A](f: Path => A): A =
    Fixtures.withTemp(tmp => f(copyOf(UpstreamFixture.groupingDir, tmp, "grouping")))

  private def curated(dir: Path) =
    UpstreamRuns.curated(
      Fixtures.source(dir, UpstreamFixture.curatedBounds),
      UpstreamFixture.upstreamBounds
    )

  private def grouping(dir: Path) =
    UpstreamRuns.grouping(
      Fixtures.source(dir, UpstreamFixture.groupingBounds),
      UpstreamFixture.upstreamBounds
    )

  private def refused(r: Either[Vector[String], Any], expected: String): Unit =
    r match {
      case Left(ps) => assert(ps.exists(_.contains(expected)), ps.mkString("\n"))
      case Right(_) => fail(s"expected a refusal containing '$expected'")
    }

  private def text(p: Path): String = utf8(Files.readAllBytes(p))

  // ------------------------------------------------------------------ crcProblem

  test("crcProblem: valid, bytes-per-checksum 0 or negative, truncated, count mismatch, magic") {
    val data = Array.tabulate[Byte](1500)(i => (i % 251).toByte)
    val ok = crcSidecar(data, 512)
    assertEquals(HadoopFiles.crcProblem(data, ok), None)
    def withPer(n: Int) = {
      val c = ok.clone(); java.nio.ByteBuffer.wrap(c).putInt(4, n); c
    }
    assert(HadoopFiles.crcProblem(data, withPer(0)).exists(_.contains("bytes per checksum 0")))
    assert(HadoopFiles.crcProblem(data, withPer(-5)).exists(_.contains("bytes per checksum -5")))
    assert(HadoopFiles.crcProblem(data, ok.dropRight(4)).exists(_.contains("checksums for")))
    assert(HadoopFiles.crcProblem(data, ok.take(6)).isDefined, "shorter than the header")
    assert(HadoopFiles.crcProblem(data, crcSidecar(data, 256)).isEmpty)
    assert(
      HadoopFiles.crcProblem(data :+ 1.toByte, ok).isDefined,
      "one more byte: another chunk count or CRC"
    )
    val badMagic = ok.clone(); badMagic(0) = 'x'.toByte
    assert(HadoopFiles.crcProblem(data, badMagic).contains("not a Hadoop checksum file"))
    val badCrc = ok.clone(); badCrc(9) = (badCrc(9) ^ 1).toByte
    assert(HadoopFiles.crcProblem(data, badCrc).exists(_.contains("differs from its CRC32")))
  }

  // ------------------------------------------------------------------ curated layout

  test("curated: a missing sidecar and an orphan sidecar are refused") {
    curatedCopy { d =>
      Files.delete(d.resolve(HadoopFiles.sidecarOf(Works)))
      refused(curated(d), "no checksum sidecar")
    }
    curatedCopy { d =>
      Files.write(d.resolve("normalize/.extra.crc"), crcSidecar(Array[Byte](1)))
      refused(curated(d), "not the checksum sidecar")
    }
  }

  test("curated: a changed byte with the old sidecar is refused (CRC32)") {
    curatedCopy { d =>
      flipByte(d.resolve(Works), 20)
      refused(curated(d), "CRC32 sidecar")
    }
  }

  test("curated: missing stage _SUCCESS or _counters.json is refused") {
    curatedCopy { d =>
      Files.delete(d.resolve("normalize/_SUCCESS"))
      Files.delete(d.resolve(HadoopFiles.sidecarOf("normalize/_SUCCESS")))
      refused(curated(d), "normalize: no _SUCCESS")
    }
    curatedCopy { d =>
      Files.delete(d.resolve(Counters))
      Files.delete(d.resolve(HadoopFiles.sidecarOf(Counters)))
      refused(curated(d), "normalize: no _counters.json")
    }
  }

  test("curated: an EMITTED mismatch and a missing EMITTED counter are refused") {
    curatedCopy { d =>
      val t = text(d.resolve(Counters)).replace("\"EMITTED.works\": 90", "\"EMITTED.works\": 91")
      writeCurated(d, Counters, t.getBytes(StandardCharsets.UTF_8))
      refused(curated(d), "EMITTED.works is 91, decoded 90")
    }
    curatedCopy { d =>
      val t = text(d.resolve(Counters)).replace("    \"EMITTED.works\": 90,\n", "")
      writeCurated(d, Counters, t.getBytes(StandardCharsets.UTF_8))
      refused(curated(d), "no EMITTED.works counter")
    }
  }

  test("curated: counters that are not strict UTF-8 are refused (CR-P7-09 m3)") {
    curatedCopy { d =>
      val b = Files.readAllBytes(d.resolve(Counters))
      b(3) = 0xff.toByte
      writeCurated(d, Counters, b)
      refused(curated(d), "not valid UTF-8")
    }
  }

  test("curated: a run manifest that is not one LF-terminated row is refused (CR-P7-09 m3)") {
    curatedCopy { d =>
      val rm = "_run-manifest.json"
      val t = text(d.resolve(rm))
      writeCurated(d, rm, (t + "\n" + t).getBytes(StandardCharsets.UTF_8))
      refused(curated(d), "_run-manifest.json: is not exactly one row")
    }
    curatedCopy { d =>
      val rm = "_run-manifest.json"
      val b = Files.readAllBytes(d.resolve(rm))
      b(5) = 0xff.toByte
      writeCurated(d, rm, b)
      refused(curated(d), "not valid UTF-8")
    }
  }

  test("curated: a part with a bad file name is refused") {
    curatedCopy { d =>
      val bad = "normalize/works/part-x-00000.gz"
      Files.move(d.resolve(Works), d.resolve(bad))
      Files.move(d.resolve(HadoopFiles.sidecarOf(Works)), d.resolve(HadoopFiles.sidecarOf(bad)))
      refused(curated(d), "not a gzip part file name")
    }
  }

  test("curated: an empty works output is refused (never an empty curated input)") {
    curatedCopy { d =>
      writeCurated(d, Works, gzip(Array.emptyByteArray))
      val t = text(d.resolve(Counters)).replace("\"EMITTED.works\": 90", "\"EMITTED.works\": 0")
      writeCurated(d, Counters, t.getBytes(StandardCharsets.UTF_8))
      refused(curated(d), "no work record")
    }
  }

  // ------------------------------------------------------------------ grouping layout

  test("grouping: _parts.json naming another run than the run manifest is refused") {
    groupingCopy { d =>
      val p = d.resolve("_parts.json")
      val t = text(p).replace(
        "run:84dd896fda43977b934008f10955191e78c2b93b80ec57c00f55dbb137dbd7d2",
        "run:" + "0" * 64
      )
      Files.write(p, t.getBytes(StandardCharsets.UTF_8))
      refused(grouping(d), "_parts.json names run:" + "0" * 64)
    }
  }

  test("grouping: an empty teams part is refused") {
    groupingCopy { d =>
      val rel = "teams/part-00000.jsonl"
      resealGrouping(d, rel, text(d.resolve(rel)), "")
      val p = d.resolve("_parts.json")
      Files.write(
        p,
        text(p).replace("\"rowCount\":41,", "\"rowCount\":0,").getBytes(StandardCharsets.UTF_8)
      )
      // CR-P7-10 m3: the specific guard (SummaryEvidenceCheck / UpstreamRuns empty-input refusal).
      refused(grouping(d), "an empty graph input is never accepted")
    }
  }

  // ------------------------------------------------------------------ tampered upstream content

  /** The rejection tags of an opening load from tampered upstream copies; asserts zero writes. */
  private def tags(grouping: Path, curated: Path): Set[String] =
    report(grouping, curated).rejections.map(_.check.tag).toSet

  private def report(grouping: Path, curated: Path): OpeningLoadReport = {
    val w = new World
    val r = run(w, sources(grouping = grouping, curated = curated)).report
    println(
      "CR-P7-09 m1 tampered upstream: " +
        r.rejections.map(_.check.tag).distinct.mkString(",") +
        r.upstreamProblems.take(2).mkString(" | ")
    )
    TargetStore.values.foreach(t => assertEquals(r.store(t).status, StoreLoadStatus.Refused, t.tag))
    assertEquals(w.writeCalls, 0)
    r
  }

  test("tampered team (one year) is refused by analysis-recompute, zero writes") {
    groupingCopy { g =>
      resealGrouping(g, "teams/part-00000.jsonl", "\"year\":2023", "\"year\":2022")
      assert(tags(g, UpstreamFixture.curatedDir).contains("analysis-recompute"))
    }
  }

  test(
    "tampered researcher assignment (one position) is refused before any check (contract), zero writes"
  ) {
    groupingCopy { g =>
      resealGrouping(
        g,
        "assignments/researcher/part-00000.jsonl",
        "\"position\":1,",
        "\"position\":9,"
      )
      // Observed (SE-P7-08): the grouping reader refuses it (upstream-read) before the verifier.
      assertEquals(tags(g, UpstreamFixture.curatedDir), Set("upstream-read"))
    }
  }

  test("tampered work assignment (one counted work made enrichment-only) is refused, zero writes") {
    groupingCopy { g =>
      resealGrouping(
        g,
        "assignments/work/part-00000.jsonl",
        "\"graphEligibility\":\"counted\"",
        "\"graphEligibility\":\"enrichment-only\""
      )
      // CR-P7-10 m3: refused by the grouping reader's work-assignment contract, by name.
      val r = report(g, UpstreamFixture.curatedDir)
      assertEquals(r.rejections.map(_.check.tag).toSet, Set("upstream-read"))
      assert(
        r.upstreamProblems.exists(p =>
          p.contains("assignments/work/part-00000.jsonl#1: graphEligibility") &&
            p.contains("is never enrichment-only")
        ),
        r.upstreamProblems.mkString("\n")
      )
    }
  }

  /** In a curated copy: the first `from` after `anchor` in the works part becomes `to`. */
  private def tamperWork(c: Path, anchor: String, from: String, to: String): Unit = {
    val works = utf8(gunzip(Files.readAllBytes(c.resolve(Works))))
    val id = works.indexOf(anchor)
    assert(id >= 0, s"$anchor is a curated record")
    val at = works.indexOf(from, id)
    assert(at >= 0 && works.lastIndexOf('\n', at) < id, s"$from in the record of $anchor")
    val changed = works.substring(0, at) + to + works.substring(at + from.length)
    writeCurated(c, Works, gzip(changed.getBytes(StandardCharsets.UTF_8)))
  }

  // A work that is a Work vertex of the graph (counted DBLP representative).
  private val CountedWork = "\"workId\":\"work:dblp:conf/aaai/YuanBZLD23\""

  test("tampered curated work (one year) is refused by canonical-tables, zero writes") {
    curatedCopy { c =>
      tamperWork(c, CountedWork, "\"year\":2023", "\"year\":2022")
      assert(tags(UpstreamFixture.groupingDir, c).contains("canonical-tables"))
    }
  }

  test("tampered curated venue (one name) is refused by canonical-tables, zero writes") {
    curatedCopy { c =>
      val rel = "normalize/venues/part-r-00000.gz"
      val v = utf8(gunzip(Files.readAllBytes(c.resolve(rel))))
      val changed = v.replaceFirst("\"name\":\"AAAI\"", "\"name\":\"AAAX\"")
      assertNotEquals(changed, v)
      writeCurated(c, rel, gzip(changed.getBytes(StandardCharsets.UTF_8)))
      assert(tags(UpstreamFixture.groupingDir, c).contains("canonical-tables"))
    }
  }
}
