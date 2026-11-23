package scholarchain.storage.aws.s3

import java.io.{ByteArrayInputStream, IOException, InputStream}
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

import scala.collection.mutable
import scala.jdk.CollectionConverters.*

import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.http.AbortableInputStream
import software.amazon.awssdk.services.s3.model.{
  ChecksumMode,
  ChecksumType,
  GetObjectRequest,
  GetObjectResponse,
  HeadObjectRequest,
  HeadObjectResponse,
  ListObjectsV2Request,
  ListObjectsV2Response,
  S3Exception,
  S3Object
}

import scholarchain.storage.model.{ObjectRef, StoreError}

/** SE-INT-04 (A3, A7): the S3 ObjectReader over a stub seam (no credentials, no network). */
class S3ObjectReaderSuite extends munit.FunSuite {
  import S3ObjectReaderSuite.*

  private val settings = S3ReaderSettings.of(Set(Bucket), 64, 5).toOption.get

  private def reader(stub: Stub) = S3ObjectReader.of(stub, settings)

  test("head: a stored object yields its hex sha256, size and content type") {
    val stub = Stub()
    stub.put("a/b.txt", "hello")
    val got = reader(stub).head(ObjectRef(Bucket, "a/b.txt"))
    assertEquals(got.map(_.map(_.sha256)), Right(Some(hex("hello"))))
    assertEquals(got.map(_.map(_.byteCount)), Right(Some(5L)))
    assertEquals(got.map(_.map(_.contentType)), Right(Some("text/plain")))
    assertEquals(stub.heads.head.checksumMode(), ChecksumMode.ENABLED)
  }

  test("head and get: a 404 is missing; a 403 is Permanent, never missing (Q5)") {
    val stub = Stub()
    assertEquals(reader(stub).head(ObjectRef(Bucket, "nope")), Right(None))
    assertEquals(reader(stub).get(ObjectRef(Bucket, "nope")), Right(None))
    stub.failWith = Some(status(403))
    assert(
      reader(stub).head(ObjectRef(Bucket, "x")).left.exists(_.isInstanceOf[StoreError.Permanent])
    )
    assert(
      reader(stub).get(ObjectRef(Bucket, "x")).left.exists(_.isInstanceOf[StoreError.Permanent])
    )
  }

  test("service and transport errors are classified; the message never leaks") {
    val stub = Stub()
    stub.failWith = Some(status(503))
    val e = reader(stub).head(ObjectRef(Bucket, "x"))
    assert(e.left.exists(_.isInstanceOf[StoreError.Transient]), e.toString)
    assert(!e.toString.contains(Marker), e.toString)
    stub.failWith = Some(new SocketException("Connection reset " + Marker))
    assert(
      reader(stub).get(ObjectRef(Bucket, "x")).left.exists(_.isInstanceOf[StoreError.Transient])
    )
    stub.failWith = Some(status(400))
    assert(
      reader(stub).get(ObjectRef(Bucket, "x")).left.exists(_.isInstanceOf[StoreError.Permanent])
    )
  }

  test("get: returns the bytes, reads to EOF and closes the stream") {
    val stub = Stub()
    stub.put("k", "body")
    val got = reader(stub).get(ObjectRef(Bucket, "k"))
    assertEquals(got.map(_.map(_._2.utf8)), Right(Some("body")))
    assertEquals(stub.lastStream.get.closed, true)
    assertEquals(stub.lastStream.get.aborted, false)
    assertEquals(stub.lastStream.get.sawEof, true)
    assertEquals(stub.gets.head.checksumMode(), ChecksumMode.ENABLED)
  }

  test("get: bytes that differ from the stored checksum are Corrupt") {
    val stub = Stub()
    stub.put("k", "body", bodyOverride = Some("bodx"))
    val got = reader(stub).get(ObjectRef(Bucket, "k"))
    assert(got.left.exists(_.isInstanceOf[StoreError.Corrupt]), got.toString)
  }

  test("get: an object over maxObjectBytes is refused and its stream aborted, never truncated") {
    val stub = Stub()
    stub.put("big", "x" * 65)
    val got = reader(stub).get(ObjectRef(Bucket, "big"))
    assert(got.left.exists(_.isInstanceOf[StoreError.Permanent]), got.toString)
    assertEquals(stub.lastStream.get.aborted, true)
    assertEquals(stub.lastStream.get.readCount, 0)
  }

  test("get: a body longer than its Content-Length is Corrupt and aborted") {
    val stub = Stub()
    stub.put("k", "abc", bodyOverride = Some("abcdef"))
    val got = reader(stub).get(ObjectRef(Bucket, "k"))
    assert(got.left.exists(_.isInstanceOf[StoreError.Corrupt]), got.toString)
    assertEquals(stub.lastStream.get.aborted, true)
  }

  test("get: a body shorter than its Content-Length is Corrupt") {
    val stub = Stub()
    stub.put("k", "abcdef", bodyOverride = Some("abc"))
    assert(reader(stub).get(ObjectRef(Bucket, "k")).left.exists(_.isInstanceOf[StoreError.Corrupt]))
  }

  test("get: an IOException while reading the body is Transient and aborts the stream") {
    val stub = Stub()
    stub.put("k", "abc")
    stub.failBodyRead = true
    val got = reader(stub).get(ObjectRef(Bucket, "k"))
    assert(got.left.exists(_.isInstanceOf[StoreError.Transient]), got.toString)
    assertEquals(stub.lastStream.get.aborted, true)
  }

  test("checksums: missing, COMPOSITE and suffixed values are Corrupt (Q6, D2)") {
    for (
      (sum, tpe) <- Vector(
        (None, Some(ChecksumType.FULL_OBJECT)),
        (Some(b64("x")), Some(ChecksumType.COMPOSITE)),
        (Some(b64("x").dropRight(1) + "-3"), None),
        (Some(b64("x") + "-3"), Some(ChecksumType.FULL_OBJECT)),
        (Some("AAAA"), None)
      )
    ) {
      val stub = Stub()
      stub.put("k", "x", checksum = Some(sum), checksumType = tpe)
      val h = reader(stub).head(ObjectRef(Bucket, "k"))
      assert(h.left.exists(_.isInstanceOf[StoreError.Corrupt]), s"$sum $tpe: $h")
      val g = reader(stub).get(ObjectRef(Bucket, "k"))
      assert(g.left.exists(_.isInstanceOf[StoreError.Corrupt]), s"$sum $tpe: $g")
      assertEquals(stub.lastStream.get.aborted, true)
    }
  }

  test("checksums: a plain value with no checksum type is accepted") {
    val stub = Stub()
    stub.put("k", "x", checksumType = None)
    assertEquals(
      reader(stub).head(ObjectRef(Bucket, "k")).map(_.map(_.sha256)),
      Right(Some(hex("x")))
    )
  }

  test("a missing content type or length is Corrupt") {
    val stub = Stub()
    stub.put("k", "x", contentType = None)
    assert(
      reader(stub).head(ObjectRef(Bucket, "k")).left.exists(_.isInstanceOf[StoreError.Corrupt])
    )
  }

  test("refused before any call: other buckets, missing or long keys, bad maxKeys") {
    val stub = Stub()
    val r = reader(stub)
    def refused(e: Either[StoreError, ?]) =
      assert(e.left.exists(_.isInstanceOf[StoreError.Refused]), e.toString)
    refused(r.head(ObjectRef("other-bucket", "k")))
    refused(r.get(ObjectRef("other-bucket", "k")))
    refused(r.list("other-bucket", "", 1))
    refused(r.head(ObjectRef(Bucket, "")))
    refused(r.head(ObjectRef(Bucket, "k" * 1025)))
    refused(r.head(null))
    refused(r.list(Bucket, "", 0))
    refused(r.list(Bucket, "", 6))
    refused(r.list(Bucket, null, 1))
    assertEquals(stub.calls, 0)
  }

  test("list: one page with MaxKeys, then one HeadObject per listed key (D1)") {
    val stub = Stub()
    Vector("p/a", "p/b", "p/c").foreach(k => stub.put(k, k))
    val got = reader(stub).list(Bucket, "p/", 2)
    assertEquals(got.map(_.map(_.ref.key)), Right(Vector("p/a", "p/b")))
    assertEquals(got.map(_.map(_.sha256)), Right(Vector(hex("p/a"), hex("p/b"))))
    assertEquals(stub.lists.head.maxKeys(), Integer.valueOf(2))
    assertEquals(stub.lists.head.prefix(), "p/")
    assertEquals(stub.heads.size, 2)
  }

  test("list: an empty listing is empty") {
    assertEquals(reader(Stub()).list(Bucket, "none/", 5), Right(Vector.empty))
  }

  test("list: more keys than maxKeys, keys outside the prefix or out of order are Corrupt") {
    for (bad <- Vector(Vector("p/a", "p/b", "p/c"), Vector("p/a", "q/b"), Vector("p/b", "p/a"))) {
      val stub = Stub()
      bad.foreach(k => stub.put(k, k))
      stub.listOverride = Some(bad)
      val got = reader(stub).list(Bucket, "p/", 2.max(bad.size - 1))
      assert(got.left.exists(_.isInstanceOf[StoreError.Corrupt]), s"$bad: $got")
    }
  }

  test("list: a key listed and then not found is Transient") {
    val stub = Stub()
    stub.listOverride = Some(Vector("p/gone"))
    assert(reader(stub).list(Bucket, "p/", 5).left.exists(_.isInstanceOf[StoreError.Transient]))
  }

  test("settings: bounds") {
    assert(S3ReaderSettings.of(Set.empty, 1, 1).isLeft)
    assert(S3ReaderSettings.of(Set("Bad_Bucket"), 1, 1).isLeft)
    assert(S3ReaderSettings.of(Set(Bucket), 0, 1).isLeft)
    assert(S3ReaderSettings.of(Set(Bucket), 1, 0).isLeft)
    assert(S3ReaderSettings.of(Set(Bucket), 1, 1001).isLeft)
    assert(S3ReaderSettings.of(Set(Bucket), 1, 1000).isRight)
    assert(S3ReaderSettings.of(Set(Bucket), Int.MaxValue, 1).isLeft)
  }
}

object S3ObjectReaderSuite {
  val Bucket = "scholarchain-test-context"
  val Marker = "SECRET-request-value-7f3a"

  def sha(s: String): Array[Byte] =
    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))
  def hex(s: String): String = sha(s).map(b => f"${b & 0xff}%02x").mkString
  def b64(s: String): String = Base64.getEncoder.encodeToString(sha(s))

  def status(code: Int): Throwable =
    S3Exception.builder().statusCode(code).message(Marker).build()

  final class Body(bytes: Array[Byte], failRead: Boolean) extends InputStream {
    private val in = new ByteArrayInputStream(bytes)
    var closed = false
    var aborted = false
    var sawEof = false
    var readCount = 0
    def read(): Int = { val b = Array.ofDim[Byte](1); if (read(b, 0, 1) == -1) -1 else b(0) & 0xff }
    override def read(b: Array[Byte], off: Int, len: Int): Int = {
      readCount += 1
      if (failRead) throw new IOException("read failed " + Marker)
      val n = in.read(b, off, len)
      if (n == -1) sawEof = true
      n
    }
    override def close(): Unit = closed = true
  }

  final case class Obj(
      body: String,
      bodyOverride: Option[String],
      contentType: Option[String],
      checksum: Option[String],
      checksumType: Option[ChecksumType]
  )

  final class Stub extends S3Calls {
    val objects = mutable.TreeMap.empty[String, Obj]
    var failWith: Option[Throwable] = None
    var failBodyRead = false
    var listOverride: Option[Vector[String]] = None
    var lastStream: Option[Body] = None
    val heads = mutable.ArrayBuffer.empty[HeadObjectRequest]
    val gets = mutable.ArrayBuffer.empty[GetObjectRequest]
    val lists = mutable.ArrayBuffer.empty[ListObjectsV2Request]
    def calls: Int = heads.size + gets.size + lists.size

    def put(
        key: String,
        body: String,
        bodyOverride: Option[String] = None,
        contentType: Option[String] = Some("text/plain"),
        checksum: Option[Option[String]] = None,
        checksumType: Option[ChecksumType] = Some(ChecksumType.FULL_OBJECT)
    ): Unit =
      objects(key) =
        Obj(body, bodyOverride, contentType, checksum.getOrElse(Some(b64(body))), checksumType)

    private def lookup(key: String): Obj =
      failWith match {
        case Some(e) => throw e
        case None => objects.getOrElse(key, throw status(404))
      }

    def headObject(r: HeadObjectRequest): HeadObjectResponse = {
      heads += r
      val o = lookup(r.key())
      HeadObjectResponse
        .builder()
        .contentLength(
          java.lang.Long.valueOf(o.body.getBytes(StandardCharsets.UTF_8).length.toLong)
        )
        .contentType(o.contentType.orNull)
        .checksumSHA256(o.checksum.orNull)
        .checksumType(o.checksumType.orNull)
        .build()
    }

    def getObject(r: GetObjectRequest): ResponseInputStream[GetObjectResponse] = {
      gets += r
      val o = lookup(r.key())
      val resp = GetObjectResponse
        .builder()
        .contentLength(
          java.lang.Long.valueOf(o.body.getBytes(StandardCharsets.UTF_8).length.toLong)
        )
        .contentType(o.contentType.orNull)
        .checksumSHA256(o.checksum.orNull)
        .checksumType(o.checksumType.orNull)
        .build()
      val body =
        new Body(o.bodyOverride.getOrElse(o.body).getBytes(StandardCharsets.UTF_8), failBodyRead)
      lastStream = Some(body)
      new ResponseInputStream(resp, AbortableInputStream.create(body, () => body.aborted = true))
    }

    def listObjectsV2(r: ListObjectsV2Request): ListObjectsV2Response = {
      lists += r
      failWith.foreach(e => throw e)
      val keys = listOverride.getOrElse(
        objects.keys.filter(_.startsWith(r.prefix())).take(r.maxKeys().intValue).toVector
      )
      ListObjectsV2Response
        .builder()
        .contents(keys.map(k => S3Object.builder().key(k).build()).asJava)
        .isTruncated(java.lang.Boolean.FALSE)
        .build()
    }
  }
}
