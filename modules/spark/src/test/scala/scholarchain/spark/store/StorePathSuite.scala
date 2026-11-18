package scholarchain.spark.store

import java.nio.file.Paths

/**
 * Locations (SP-P6-04): parsing of user-supplied locations (absolute local paths and URIs), the
 * local form that keeps the java.nio behaviour (same printed path, same `file:` URI), and the pure
 * syntax operations the reader and the publishers use. Counter-tests: every refused form.
 */
class StorePathSuite extends munit.FunSuite {

  private def ok(s: String): StorePath = StorePath.parse(s).fold(e => fail(e), identity)

  private def refused(s: String, fragment: String): Unit =
    StorePath.parse(s) match {
      case Right(p) => fail(s"'$s' was accepted as $p")
      case Left(w) => assert(w.contains(fragment), s"'$s': $w")
    }

  test("local paths and file URIs are one local location, printed and converted as before") {
    val p = Paths.get("/tmp/scholarchain/run=ab")
    val a = StorePath.local(p)
    assertEquals(ok("/tmp/scholarchain/run=ab"), a)
    assertEquals(ok("file:///tmp/scholarchain/run=ab"), a)
    assertEquals(ok("file:/tmp/scholarchain/run=ab"), a)
    assertEquals(ok("file:///tmp/scholarchain/run=ab/"), a, "one trailing slash of a URI")
    assert(a.isLocal)
    assertEquals(a.toString, "/tmp/scholarchain/run=ab")
    assertEquals(a.localPath, p)
    assertEquals(a.toUri, p.toUri)
    assertEquals(a.hadoopUri.toString, "file:/tmp/scholarchain/run=ab")
  }

  test("S3A and HDFS locations keep their authority and print as URIs") {
    val s = ok("s3a://bucket-a/prefix/run=ab")
    assertEquals(
      (s.scheme, s.authority, s.segments),
      ("s3a", Some("bucket-a"), Vector("prefix", "run=ab"))
    )
    assertEquals(s.toString, "s3a://bucket-a/prefix/run=ab")
    assertEquals(s.toUri.toString, "s3a://bucket-a/prefix/run=ab")
    assertEquals(s.hadoopPath.toString, "s3a://bucket-a/prefix/run=ab")
    assert(!s.isLocal)
    intercept[IllegalStateException](s.localPath)
    val h = ok("hdfs://namenode:8020/user/x")
    assertEquals(h.authority, Some("namenode:8020"))
    assertEquals(ok("s3a://bucket-a").path, "/")
    assertEquals(ok("s3a://bucket-a/").segments, Vector.empty)
  }

  test("resolve, parent, name, startsWith and relativeTo are pure segment operations") {
    val root = ok("s3a://b/archive")
    val run = root.resolve("er-assignments/run=cd")
    assertEquals(run.toString, "s3a://b/archive/er-assignments/run=cd")
    assertEquals(run.name, "run=cd")
    assertEquals(run.getFileName, "run=cd")
    assertEquals(run.getParent.getParent, root)
    assertEquals(run.resolveSibling("x").toString, "s3a://b/archive/er-assignments/x")
    assert(run.startsWith(root))
    assert(!root.startsWith(run))
    assert(!ok("s3a://other/archive/x").startsWith(root), "another bucket")
    assert(!ok("hdfs://b/archive/x").startsWith(root), "another scheme")
    assert(!ok("s3a://b/archive2").startsWith(root), "a segment, not a string prefix")
    assertEquals(run.relativeTo(root), Some("er-assignments/run=cd"))
    assertEquals(root.relativeTo(run), None)
    assertEquals(ok("s3a://b").getParent, null)
    assertEquals(ok("s3a://b").name, "")
  }

  test("counter-tests: relative, unnormalized, escaped, query, user info and authority forms") {
    refused("", "empty")
    refused("relative/path", "neither an absolute local path")
    refused("/tmp/a/../b", "not an absolute, normalized local path")
    refused("/tmp/a/", "not an absolute, normalized local path")
    refused("s3a://b/a/../c", "'.' or '..'")
    refused("s3a://b/a//c", "empty segment")
    refused("s3a://b/a%2Fc", "percent escape")
    refused("s3a://b/a?x=1", "query or a fragment")
    refused("s3a://b/a#f", "query or a fragment")
    refused("s3a://user@b/a", "user info")
    refused("s3a:///a", "no authority")
    refused("file://host/a", "no authority")
    refused("s3a://b/a b", "whitespace")
    refused("s3a://b/a\\b", "backslash")
    refused("S3A://b/a", "lowercase scheme")
    refused("mailto:x@y", "not a hierarchical URI")
  }

  test("CR-P6-26 n1: one trailing slash is ignored, two or more are refused") {
    assertEquals(ok("s3a://b/a/"), ok("s3a://b/a"))
    assertEquals(ok("hdfs://nn:8020/x/"), ok("hdfs://nn:8020/x"))
    refused("s3a://b/a//", "more than one slash")
    refused("s3a://b/a///", "more than one slash")
    refused("s3a://b//", "more than one slash")
    refused("file:///tmp/a//", "more than one slash")
    refused("hdfs://nn:8020/x//", "more than one slash")
  }

  test("CR-P6-26 u2: a port-only (empty host) authority is refused") {
    refused("s3a://:80/x", "port-only authority")
    refused("hdfs://:8020/x", "port-only authority")
    refused("s3a://:/x", "port-only authority")
    assertEquals(ok("hdfs://nn:8020/x").authority, Some("nn:8020"))
    assertEquals(StorePath.hostOf("nn:8020"), "nn")
    assertEquals(StorePath.hostOf("nn:"), "nn")
    assertEquals(StorePath.hostOf("bucket-a"), "bucket-a")
    assertEquals(StorePath.hostOf("[::1]:8020"), "[::1]")
    assertEquals(StorePath.hostOf("[::1]"), "[::1]")
    assertEquals(StorePath.hostOf(":80"), "")
  }

  test("CR-P6-26 n2: overlap ignores authority spelling (HDFS authority, port, host case)") {
    def overlap(a: String, b: String) = StorePath.mayOverlap(ok(a), ok(b))
    // The reviewer's failure input: one name node spelled with and without its port.
    assert(overlap("hdfs://nn:8020/x", "hdfs://nn/x/ck"))
    assert(overlap("hdfs://nn/x/ck", "hdfs://nn:8020/x"), "symmetric")
    assert(!ok("hdfs://nn/x/ck").startsWith(ok("hdfs://nn:8020/x")), "startsWith stays exact")
    // HDFS: any authority spelling (FQDN, address, nameservice) is one file system.
    assert(overlap("hdfs://nn.example.internal:8020/x", "hdfs://10.0.0.1/x/y"))
    // Other schemes: host without the port, case-insensitive.
    assert(overlap("s3a://bucket-a:443/x", "s3a://bucket-a/x/y"))
    assert(overlap("s3a://Bucket-A/x/y", "s3a://bucket-a/x"))
    assert(overlap("s3a://bucket-a/x", "s3a://bucket-a/x"), "equal")
    assert(overlap("/data/x", "file:///data/x/y"))
    // Not overlapping: another bucket, another scheme, a sibling, a string (not segment) prefix.
    assert(!overlap("s3a://bucket-a/x", "s3a://bucket-b/x/y"))
    assert(!overlap("s3a://nn/x", "hdfs://nn/x/y"))
    assert(!overlap("hdfs://nn/x/a", "hdfs://nn:8020/x/b"))
    assert(!overlap("s3a://b/archive", "s3a://b/archive2"))
  }

  test("CR-P6-26 n3: a comma or a Hadoop glob character in the path is reported") {
    Vector(
      "s3a://b/dev,archive",
      "s3a://b/dev*",
      "/data/a,b",
      "/data/a*b",
      "/data/a?b",
      "/data/a[1]",
      "/data/{a,b}"
    ).foreach { s =>
      val p = ok(s)
      assert(StorePath.globProblem(p).exists(_.contains("comma or a Hadoop glob character")), s)
    }
    Vector("s3a://b/run=ab/part-00000.jsonl", "/data/x_y-z.+=", "hdfs://nn:8020/x").foreach { s =>
      assertEquals(StorePath.globProblem(ok(s)), None, s)
    }
    // Backslashes never parse, and the authority is not a path (IPv6 brackets are not refused).
    refused("/data/a\\b", "backslash")
    assertEquals(StorePath.globProblem(ok("hdfs://[::1]:8020/x")), None)
  }
}
