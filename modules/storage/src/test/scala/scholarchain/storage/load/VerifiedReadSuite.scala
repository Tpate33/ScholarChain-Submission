package scholarchain.storage.load

import java.nio.file.{Files, StandardOpenOption}

import scala.compiletime.testing.typeCheckErrors

import scholarchain.storage.Fixtures.*
import scholarchain.storage.fake.{InMemoryKeyValueStore, InMemoryObjectStore, RecordingObjectStore}
import scholarchain.storage.model.{ObjectRef, PutOutcome, StoreError}
import scholarchain.storage.port.{KeyValueReader, RunSource}

/**
 * SE-P7-04 m2 (re-checking handle), moved into package `load` by SE-P7-05 A2 because
 * `VerifiedRun.readVerified` is package-private now; plus the per-read bound of risk T1.
 */
class VerifiedReadSuite extends munit.FunSuite {

  private def ledger: KeyValueReader =
    KeyValueReader.readOnly(new InMemoryKeyValueStore(100))

  private def verified(src: RunSource, loads: RunSource): VerifiedRun = {
    val r = LoadVerifier.verify(src, loads, ledger)
    assertEquals(r.rejections, Vector.empty)
    r.verified.getOrElse(fail("structurally valid"))
  }

  test("readVerified returns the verified bytes of a listed part, and refuses unlisted paths") {
    val v = verified(source(runDir(run1Root)), source(loadDir(run1Root), loadBounds))
    v.partManifest.files.foreach { f =>
      val b = v.readVerified(f.path).fold(e => fail(s"${f.path}: $e"), identity)
      assertEquals((b.size.toLong, b.sha256), (f.byteCount, f.sha256), f.path)
    }
    Vector("_SUCCESS", "_parts.json", "nope/part-00000.csv", "../_parts.json", "").foreach { p =>
      v.readVerified(p) match {
        case Left(StoreError.Refused(d)) => assert(d.contains("not a file of the verified run"), d)
        case other => fail(s"$p: $other")
      }
    }
  }

  test("a part replaced after verification is refused at read time, with zero writes") {
    withCopy { (run, load) =>
      val v = verified(source(run), source(load, loadBounds))
      val objects = new RecordingObjectStore(new InMemoryObjectStore(100))
      def upload(path: String) =
        v.readVerified(path)
          .flatMap(objects.putCreateOnce(ObjectRef("bucket", path), _, "application/octet-stream"))
      def changed(path: String): Unit =
        upload(path) match {
          case Left(StoreError.Corrupt(d)) =>
            assert(d.contains(s"$path: changed since verification"), d)
          case Left(StoreError.Refused(d)) => // larger than verified: refused by the read bound
            assert(d.contains(s"$path: more than"), d)
          case other => fail(s"$path: expected Corrupt or the bound's Refused, got $other")
        }

      // Same length, one byte different.
      val csv = "researcher-vertices/part-00000.csv"
      val b = bytes(run.resolve(csv))
      b(b.length - 2) = (b(b.length - 2) ^ 0x01).toByte
      Files.write(run.resolve(csv), b, StandardOpenOption.TRUNCATE_EXISTING)
      changed(csv)

      // Replaced by another verified part of the same run (a valid Parquet file, other bytes).
      val pq = "metrics/part-00000.parquet"
      val other = "metrics/part-00001.parquet"
      Files.copy(
        run.resolve(other),
        run.resolve(pq),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING
      )
      changed(pq)

      // Truncated.
      val t = "edges/part-00001.parquet"
      val tb = bytes(run.resolve(t))
      Files.write(run.resolve(t), tb.take(tb.length - 1), StandardOpenOption.TRUNCATE_EXISTING)
      changed(t)

      // Grown by one byte (T1): refused by the per-read bound, before any hashing.
      val g = "coreness/part-00001.parquet"
      Files.write(
        run.resolve(g),
        bytes(run.resolve(g)) :+ 0.toByte,
        StandardOpenOption.TRUNCATE_EXISTING
      )
      upload(g) match {
        case Left(StoreError.Refused(d)) => assert(d.contains(s"$g: more than"), d)
        case other => fail(s"$g: expected Refused by the bound, got $other")
      }

      // Removed.
      Files.delete(run.resolve("coreness/part-00000.parquet"))
      assert(upload("coreness/part-00000.parquet").isLeft)

      assertEquals(objects.counts.calls, 0, "nothing was written from a changed part")

      // An unchanged part still reads, and only it is written.
      assertEquals(upload("bridges/part-00000.parquet"), Right(PutOutcome.Written))
      assertEquals(objects.counts.calls, 1)
    }
  }

  test("readVerified reads at most the manifest byte count + 1 (T1)") {
    val rec = recording(runDir(run1Root))
    val v = verified(rec, source(loadDir(run1Root), loadBounds))
    rec.bounds.clear()
    v.partManifest.files.foreach(f => assert(v.readVerified(f.path).isRight, f.path))
    assertEquals(
      rec.bounds.toVector,
      v.partManifest.files.map(f => f.path -> Some(f.byteCount)),
      "every verified read passes its manifest byte count as the bound"
    )
  }

  test("a VerifiedRun reads only through the source it verified") {
    withCopy { (runA, loadA) =>
      withCopy { (runB, _) =>
        val a = recording(runA)
        val b = recording(runB)
        val v = verified(a, source(loadA, loadBounds))
        a.reads.clear()
        val path = "venue-vertices/part-00001.csv"
        assert(v.readVerified(path).isRight)
        assertEquals(a.reads.toVector, Vector(path))
        assertEquals((b.listings, b.reads.toVector), (0, Vector.empty))
        // The other copy differs in this part; the VerifiedRun still reads A only, and refuses A
        // once A's bytes become B's.
        write(runB.resolve(path), text(runB.resolve(path)) + "x,y\n")
        assert(v.readVerified(path).isRight, "B's change is invisible: A is the bound source")
        Files.copy(
          runB.resolve(path),
          runA.resolve(path),
          java.nio.file.StandardCopyOption.REPLACE_EXISTING
        )
        assert(v.readVerified(path).isLeft, "A now holds other bytes: refused")
        // No API takes a source.
        assert(typeCheckErrors("v.readVerified(b, path)").nonEmpty)
        assert(typeCheckErrors("v.readVerified(path)").isEmpty, "control: the bound read compiles")
      }
    }
  }
}
