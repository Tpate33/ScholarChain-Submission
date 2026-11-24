package scholarchain.storage

import java.nio.file.Files
import scholarchain.storage.local.*
import scholarchain.storage.model.*
import scholarchain.storage.port.*

class S3MaterializeSuite extends munit.FunSuite {
  private val body = Bytes.utf8("real source bytes\n")
  private val entry = MaterializeEntry(
    "source-bucket",
    "exact/key",
    body.sha256,
    body.size.toLong,
    "inputs/source.txt"
  )
  private val manifest = MaterializeManifest(
    S3Materialize.SchemaVersion,
    "bounded-run-01",
    "us-east-1",
    1000,
    500,
    1,
    2,
    1024,
    1024,
    Vector(entry)
  )
  private class Reader(bytes: Bytes = body) extends ObjectReader {
    var gets = 0
    def head(ref: ObjectRef) = throw new AssertionError("no HeadObject required")
    def list(bucket: String, prefix: String, maxKeys: Int) = throw new AssertionError(
      "no listing allowed"
    )
    def get(ref: ObjectRef) = {
      gets += 1
      Right(Some(ObjectMeta(ref, bytes.sha256, bytes.size.toLong, "text/plain") -> bytes))
    }
  }

  test("default CLI validates literal or file manifest without writes or client construction") {
    Fixtures.withTemp { temp =>
      val dir = temp.toRealPath()
      val json = upickle.default.write(manifest)
      assert(
        S3MaterializeMain
          .run(Array("--manifest-json", json, "--root", dir.toString))
          .exists(_.startsWith("validated-only"))
      )
      assert(!Files.exists(dir.resolve("inputs")))
      val file = dir.resolve("manifest.json")
      Files.writeString(file, json)
      assert(
        S3MaterializeMain
          .run(Array("--manifest-file", file.toString, "--root", dir.toString))
          .isRight
      )
      assert(
        S3MaterializeMain
          .run(Array("--manifest-json", json, "--root", dir.toString, "--execute", "wrong-run"))
          .isLeft
      )
    }
  }

  test("injected reader materializes exact bytes atomically and identical rerun skips reads") {
    Fixtures.withTemp { temp =>
      val dir = temp.toRealPath()
      val reader = new Reader
      assertEquals(S3Materialize.materialize(manifest, dir, reader), Right(1))
      assertEquals(Files.readString(dir.resolve(entry.localPath)), body.utf8)
      assertEquals(S3Materialize.materialize(manifest, dir, reader), Right(0))
      assertEquals(reader.gets, 1)
      Files.writeString(dir.resolve(entry.localPath), "different")
      assert(S3Materialize.materialize(manifest, dir, reader).isLeft)
      assertEquals(reader.gets, 1)
      assertEquals(Files.readString(dir.resolve(entry.localPath)), "different")
    }
  }

  test("digest mismatch or missing objects publish no file") {
    Fixtures.withTemp { temp =>
      val dir = temp.toRealPath()
      assert(S3Materialize.materialize(manifest, dir, new Reader(Bytes.utf8("wrong"))).isLeft)
      assert(!Files.exists(dir.resolve(entry.localPath)))
      val missing = new Reader {
        override def get(ref: ObjectRef) = Right(None)
      }
      assert(S3Materialize.materialize(manifest, dir, missing).isLeft)
    }
  }

  test("traversal symlinks duplicates and all byte/file bounds refuse before reads") {
    Fixtures.withTemp { temp =>
      val dir = temp.toRealPath()
      val reader = new Reader
      val invalid = Vector(
        manifest.copy(entries = Vector(entry.copy(localPath = "../escape"))),
        manifest.copy(entries = Vector(entry.copy(localPath = "/absolute"))),
        manifest.copy(entries = Vector(entry, entry)),
        manifest.copy(maxFiles = 0),
        manifest.copy(maxTotalBytes = 1),
        manifest.copy(maxObjectBytes = 1),
        manifest.copy(retryMaxAttempts = 0),
        manifest.copy(entries = Vector(entry.copy(sha256 = "bad")))
      )
      invalid.foreach(m => assert(S3Materialize.materialize(m, dir, reader).isLeft))
      Files.createSymbolicLink(dir.resolve("inputs"), dir)
      assert(S3Materialize.materialize(manifest, dir, reader).isLeft)
      assertEquals(reader.gets, 0)
    }
  }
}
