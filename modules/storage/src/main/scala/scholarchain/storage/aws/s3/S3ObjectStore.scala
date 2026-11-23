package scholarchain.storage.aws.s3

import java.util.Base64
import scala.util.control.NonFatal
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{PutObjectRequest, PutObjectResponse}
import scholarchain.storage.aws.AwsErrors
import scholarchain.storage.model.{Bytes, ObjectRef, PutOutcome, StoreError}
import scholarchain.storage.port.ObjectStore

/** Loader-only seam. Runtime S3Calls still exposes no writes. */
trait S3PutCalls {
  def putObject(request: PutObjectRequest, body: RequestBody): PutObjectResponse
}
object S3PutCalls {
  def of(client: S3Client): S3PutCalls = new S3PutCalls {
    def putObject(r: PutObjectRequest, b: RequestBody) = client.putObject(r, b)
  }
}

/**
 * Single-part, checksum-bearing conditional S3 writes. AWS PutObject documentation checked
 * 2026-11-23: If-None-Match * refuses an existing key with 412; 409 is retryable, not success.
 * https://docs.aws.amazon.com/AmazonS3/latest/API/API_PutObject.html The ordinary reader verifies
 * the winner's full-object checksum after a 412.
 */
final class S3ObjectStore private (
    reads: S3Calls,
    writes: S3PutCalls,
    settings: S3ReaderSettings
) extends ObjectStore {
  private val reader = S3ObjectReader.of(reads, settings)
  def head(ref: ObjectRef) = reader.head(ref)
  def get(ref: ObjectRef) = reader.get(ref)
  def list(bucket: String, prefix: String, maxKeys: Int) = reader.list(bucket, prefix, maxKeys)

  def putCreateOnce(
      ref: ObjectRef,
      body: Bytes,
      contentType: String
  ): Either[StoreError, PutOutcome] =
    if (
      ref == null || !settings.buckets.contains(ref.bucket) || ref.key == null ||
      ref.key.isEmpty || ref.key.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1024
    )
      Left(StoreError.Refused("s3: invalid or unconfigured object reference"))
    else if (
      body == null || body.size > settings.maxObjectBytes || contentType == null ||
      contentType.isEmpty || contentType.exists(_.isControl)
    )
      Left(StoreError.Refused("s3: invalid content type or body above configured bound"))
    else {
      val checksum = Base64.getEncoder.encodeToString(
        java.security.MessageDigest.getInstance("SHA-256").digest(body.toArray)
      )
      val request = PutObjectRequest
        .builder()
        .bucket(ref.bucket)
        .key(ref.key)
        .contentType(contentType)
        .contentLength(body.size.toLong)
        .checksumSHA256(checksum)
        .ifNoneMatch("*")
        .build()
      try {
        val response = writes.putObject(request, RequestBody.fromBytes(body.toArray))
        if (response == null) Left(StoreError.Corrupt("s3: PutObject returned no response"))
        else Right(PutOutcome.Written)
      } catch {
        case e: AwsServiceException if e.statusCode() == 412 =>
          reader.head(ref).flatMap {
            case Some(m)
                if m.sha256 == body.sha256 && m.byteCount == body.size.toLong && m.contentType == contentType =>
              Right(PutOutcome.AlreadyPresent)
            case Some(_) =>
              Left(StoreError.Conflict("s3: conditional write found different content"))
            case None =>
              Left(StoreError.Transient("s3: conditional write winner is no longer readable"))
          }
        case e: AwsServiceException if e.statusCode() == 409 =>
          Left(
            StoreError.Transient("s3: conditional write conflict; retry the same create-once write")
          )
        case NonFatal(e) => Left(AwsErrors.classify("S3", "PutObject", e))
      }
    }
}
object S3ObjectStore {
  def of(reads: S3Calls, writes: S3PutCalls, settings: S3ReaderSettings): S3ObjectStore = {
    require(reads != null && writes != null && settings != null, "calls and settings required")
    new S3ObjectStore(reads, writes, settings)
  }
}
