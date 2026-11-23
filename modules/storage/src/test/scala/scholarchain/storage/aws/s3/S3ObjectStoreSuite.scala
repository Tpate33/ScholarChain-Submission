package scholarchain.storage.aws.s3

import java.util.Base64
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.model.*
import scholarchain.storage.model.*

class S3ObjectStoreSuite extends munit.FunSuite {
  private val ref = ObjectRef("test-data-bucket", "context/x.txt")
  private val body = Bytes.utf8("evidence text")
  private val settings = S3ReaderSettings.of(Set(ref.bucket), 100, 10).toOption.get
  private class Stub extends S3Calls with S3PutCalls {
    var request: PutObjectRequest = null
    var sent = Array.emptyByteArray
    var status = 0
    var heads = 0
    var stored = body
    def putObject(r: PutObjectRequest, b: RequestBody): PutObjectResponse = {
      request = r
      sent = b.contentStreamProvider().newStream().readAllBytes()
      if (status != 0)
        throw S3Exception.builder().statusCode(status).message("private-value").build()
      PutObjectResponse.builder().build()
    }
    def headObject(r: HeadObjectRequest): HeadObjectResponse = {
      heads += 1
      HeadObjectResponse
        .builder()
        .contentLength(stored.size.toLong)
        .contentType("text/plain")
        .checksumSHA256(
          Base64.getEncoder.encodeToString(
            java.security.MessageDigest.getInstance("SHA-256").digest(stored.toArray)
          )
        )
        .checksumType(ChecksumType.FULL_OBJECT)
        .build()
    }
    def getObject(r: GetObjectRequest): ResponseInputStream[GetObjectResponse] =
      throw new AssertionError("no get")
    def listObjectsV2(r: ListObjectsV2Request): ListObjectsV2Response = throw new AssertionError(
      "no list"
    )
  }
  test("create-once request has exact bytes, checksum and conditional header") {
    val s = new Stub
    val store = S3ObjectStore.of(s, s, settings)
    assertEquals(store.putCreateOnce(ref, body, "text/plain"), Right(PutOutcome.Written))
    assertEquals(s.request.ifNoneMatch(), "*")
    assertEquals(s.request.bucket(), ref.bucket)
    assertEquals(s.request.key(), ref.key)
    assertEquals(s.request.contentType(), "text/plain")
    assertEquals(s.request.contentLength().longValue(), body.size.toLong)
    assertEquals(Bytes.of(s.sent), body)
    assertEquals(S3ObjectReader.sha256Hex(s.request.checksumSHA256(), null), Right(body.sha256))
    assertEquals(s.heads, 0)
  }
  test("412 checks checksum, size and content type; different content never overwrites") {
    val s = new Stub
    s.status = 412
    val store = S3ObjectStore.of(s, s, settings)
    assertEquals(store.putCreateOnce(ref, body, "text/plain"), Right(PutOutcome.AlreadyPresent))
    s.stored = Bytes.utf8("other")
    assert(
      store.putCreateOnce(ref, body, "text/plain").left.exists(_.isInstanceOf[StoreError.Conflict])
    )
    assertEquals(s.heads, 2)
  }
  test("409 is retryable and service error messages are sanitized") {
    val s = new Stub
    s.status = 409
    val store = S3ObjectStore.of(s, s, settings)
    assert(
      store.putCreateOnce(ref, body, "text/plain").left.exists(_.isInstanceOf[StoreError.Transient])
    )
    assertEquals(s.heads, 0)
    s.status = 403
    val out = store.putCreateOnce(ref, body, "text/plain")
    assert(out.isLeft)
    assert(!out.toString.contains("private-value"))
  }
  test("out-of-scope bucket and oversize body are refused before any write") {
    val s = new Stub
    val store = S3ObjectStore.of(s, s, settings)
    assert(store.putCreateOnce(ObjectRef("other-bucket", "x"), body, "text/plain").isLeft)
    assert(store.putCreateOnce(ref, Bytes.utf8("x" * 101), "text/plain").isLeft)
    assertEquals(s.request, null)
  }
}
