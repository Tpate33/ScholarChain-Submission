package scholarchain.storage

import java.io.RandomAccessFile
import java.nio.file.{Files, Path}
import java.util.concurrent.{Executors, TimeUnit, TimeoutException}

import scholarchain.storage.fake.InMemoryKeyValueStore
import scholarchain.storage.load.{LoadVerifier, VerificationCheck}
import scholarchain.storage.local.Traversal
import scholarchain.storage.model.{Bytes, SourceBounds, StoreError}
import scholarchain.storage.port.KeyValueReader
import Fixtures.*

/**
 * SE-P7-05 A3 (risk T1, with the CR-P7-05 details): reads refuse non-regular files before any open,
 * never follow a link in any path segment, and are bounded by the caller's byte count + 1.
 */
class SourceHardeningSuite extends munit.FunSuite {

  private def bounds(bytes: Long = 64L * 1024 * 1024) =
    SourceBounds.of(100, bytes, 3).toOption.get

  private def refused(r: Either[StoreError, ?], fragment: String): Unit =
    r match {
      case Left(StoreError.Refused(d)) => assert(d.contains(fragment), d)
      case other => fail(s"expected Refused containing '$fragment', got $other")
    }

  test("the traversal this platform uses is observed and reported") {
    withTemp { root =>
      val s = scholarchain.storage.local.LocalRunSource.open(root, bounds()).toOption.get
      val t = s.traversal
      assert(Traversal.values.contains(t))
      // Observable in the sbt test output for the report (no assertion on which one).
      println(s"SE-P7-05 A3: LocalRunSource traversal on this JVM = ${t.tag}")
    }
  }

  test("readAtMost: at most maxBytes is accepted, maxBytes + 1 refused") {
    withTemp { root =>
      write(root.resolve("a.txt"), "x" * 100)
      val s = source(root, bounds())
      assertEquals(s.readAtMost("a.txt", 100), Right(Bytes.utf8("x" * 100)))
      refused(s.readAtMost("a.txt", 99), "a.txt: more than 99 bytes")
      assert(s.readAtMost("a.txt", -1).isLeft, "a negative bound is refused")
      // The source bound still applies when the caller's is larger.
      refused(
        source(root, bounds(bytes = 50)).readAtMost("a.txt", 1000),
        "more than maxTotalBytes 50"
      )
    }
  }

  test("an oversized part is refused from its size at open, without reading it") {
    withTemp { root =>
      // A sparse 1 GiB file: reading it would take visible time and memory; refusing it does not.
      val big = root.resolve("big.bin")
      Using(new RandomAccessFile(big.toFile, "rw"))(_.setLength(1L << 30))
      val s = source(root, bounds(bytes = 2L << 30))
      refused(s.readAtMost("big.bin", 1024), "the file holds 1073741824 bytes at open")
    }
  }

  test("the verifier reads every listed part with its manifest byte count as the bound") {
    val rec = recording(runDir(run1Root))
    val r = LoadVerifier.verify(
      rec,
      source(loadDir(run1Root), loadBounds),
      KeyValueReader.readOnly(new InMemoryKeyValueStore(100))
    )
    assert(r.structurallyValid)
    val pm = r.verified.get.partManifest
    // Files not in _parts.json (itself and the markers) have no manifest size: the source bound.
    val listed = pm.files.map(f => f.path -> f.byteCount).toMap
    val expected = rec.listFiles().toOption.get.map(p => p -> listed.get(p)).toMap
    assertEquals(expected.collect { case (p, None) => p }.toSet, Set("_SUCCESS", "_parts.json"))
    val got = rec.bounds.toMap
    assertEquals(
      (
        (got.toSet -- expected.toSet).toVector.sortBy(_._1),
        (expected.toSet -- got.toSet).toVector.sortBy(_._1)
      ),
      (Vector.empty, Vector.empty),
      "(read but not expected, expected but not read)"
    )
    assertEquals(rec.bounds.size, expected.size, "each file read once")
  }

  test(
    "the verifier refuses a part that grew after _parts.json was written, reading only the bound"
  ) {
    withCopy { (run, load) =>
      val p = "authored-edges/part-00000.csv"
      val before = Files.size(run.resolve(p))
      write(run.resolve(p), text(run.resolve(p)) + ("z" * 4096))
      val r = LoadVerifier.verify(
        source(run),
        source(load, loadBounds),
        KeyValueReader.readOnly(new InMemoryKeyValueStore(100))
      )
      assert(
        r.rejections.exists(j =>
          j.check == VerificationCheck.PartIntegrity && j.subject == p &&
            j.error.message.contains(s"more than $before bytes")
        ),
        r.rejections.mkString("\n")
      )
      assertEquals(r.verified, None)
    }
  }

  test("a symbolic link in an intermediate directory is refused, never followed") {
    withTemp { tmp =>
      val root = tmp.resolve("root")
      val elsewhere = tmp.resolve("elsewhere")
      Files.createDirectories(root.resolve("a"))
      Files.createDirectories(elsewhere.resolve("b"))
      write(elsewhere.resolve("b/secret.txt"), "outside the root\n")
      write(root.resolve("a/x.txt"), "x\n")
      // a/b -> elsewhere/b : the final file is regular, but the path crosses a link.
      Files.createSymbolicLink(root.resolve("a/b"), elsewhere.resolve("b"))
      val s = source(root, bounds())
      refused(s.read("a/b/secret.txt"), "a/b: not a directory (links are never followed)")
      refused(s.readAtMost("a/b/secret.txt", 1000), "a/b: not a directory")
      // A link as the first segment too.
      Files.createSymbolicLink(root.resolve("c"), root.resolve("a"))
      refused(s.read("c/x.txt"), "c: not a directory")
      assertEquals(s.read("a/x.txt"), Right(Bytes.utf8("x\n")), "control: the real path reads")
    }
  }

  /** Creates a FIFO with the platform's mkfifo, if there is one. */
  private def mkfifo(p: Path): Boolean = {
    val tool = Vector("/usr/bin/mkfifo", "/bin/mkfifo").find(t => Files.isExecutable(Path.of(t)))
    tool.exists { t =>
      val proc = new ProcessBuilder(t, p.toString).redirectErrorStream(true).start()
      proc.waitFor(10, TimeUnit.SECONDS) && proc.exitValue() == 0 && Files.exists(p)
    }
  }

  test("a FIFO is refused before it is opened, so the read cannot block") {
    withTemp { root =>
      val fifo = root.resolve("part.csv")
      val made = mkfifo(fifo)
      assert(made, "this test needs mkfifo (present on macOS and Linux); see the report if absent")
      val s = source(root, bounds())
      val pool = Executors.newSingleThreadExecutor()
      try {
        val f = pool.submit(() => (s.read("part.csv"), s.readAtMost("part.csv", 10)))
        val (a, b) =
          try f.get(10, TimeUnit.SECONDS)
          catch {
            case _: TimeoutException =>
              // Unblock the reader before failing (a writer end lets the open return).
              Using(new java.io.FileOutputStream(fifo.toFile))(_.close())
              fail("the read blocked on the FIFO")
          }
        refused(a, "part.csv: not a regular file")
        refused(b, "part.csv: not a regular file")
        refused(s.listFiles(), "part.csv: not a regular file")
      } finally pool.shutdownNow()
    }
  }

  private object Using {
    def apply[A <: AutoCloseable, B](a: => A)(f: A => B): B = scala.util.Using.resource(a)(f)
  }
}
