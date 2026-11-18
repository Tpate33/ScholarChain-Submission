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
}
