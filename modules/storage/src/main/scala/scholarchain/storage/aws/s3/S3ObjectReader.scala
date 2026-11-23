package scholarchain.storage.aws.s3

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{
  ChecksumMode,
  ChecksumType,
  GetObjectRequest,
  GetObjectResponse,
  HeadObjectRequest,
  HeadObjectResponse,
  ListObjectsV2Request,
  ListObjectsV2Response
}

import scholarchain.storage.aws.AwsErrors
import scholarchain.storage.model.{Bytes, ObjectRef, StoreError}
import scholarchain.storage.port.{ObjectMeta, ObjectReader}

/**
 * SE-INT-04 (A3): the narrow seam over the SDK `S3Client`. Exactly three read operations exist:
 * HeadObject, GetObject and ListObjectsV2. No write, copy or delete is reachable through it, and
 * [[S3Calls.of]] captures the client privately. (The deployed IAM policy should grant s3:GetObject
 * and s3:ListBucket on the configured buckets only; s3:ListBucket is also what makes a missing key
 * a 404 instead of a 403, Q5: routing item for infra-engineer.)
 */
trait S3Calls {
  def headObject(request: HeadObjectRequest): HeadObjectResponse
  def getObject(request: GetObjectRequest): ResponseInputStream[GetObjectResponse]
  def listObjectsV2(request: ListObjectsV2Request): ListObjectsV2Response
}

object S3Calls {
  def of(client: S3Client): S3Calls = {
    require(client != null, "client is required")
    new S3Calls {
      def headObject(request: HeadObjectRequest) = client.headObject(request)
      def getObject(request: GetObjectRequest) = client.getObject(request)
      def listObjectsV2(request: ListObjectsV2Request) = client.listObjectsV2(request)
    }
  }
}

/**
 * SE-INT-04: bounds of the S3 reader (caller-supplied; no defaults).
 *
 * @param buckets
 *   the only buckets a read may name (D4: the raw, curated and context buckets of configuration);
 *   any other bucket is `Refused` before a call
 * @param maxObjectBytes
 *   the largest object `get` reads; a larger object is refused (the stream is aborted), never
 *   truncated
 * @param maxListKeys
 *   the largest `maxKeys` a `list` accepts, 1..1000 (ListObjectsV2 MaxKeys cap, Q8)
 */
final case class S3ReaderSettings private (
    buckets: Set[String],
    maxObjectBytes: Int,
    maxListKeys: Int
)

object S3ReaderSettings {

  /** ListObjectsV2 returns at most 1,000 keys per page (Q8, checked 2026-11-22). */
  val MaxListKeysLimit = 1000

  /** Object keys are at most 1,024 UTF-8 bytes (Q9, checked 2026-11-22). */
  val MaxKeyBytes = 1024

  /** The largest in-memory body (same JDK array bound as core `MaxServerBodyBytes`). */
  val MaxObjectBytesLimit: Int = Int.MaxValue - 9

  private val BucketName = "^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$".r

  def of(
      buckets: Set[String],
      maxObjectBytes: Int,
      maxListKeys: Int
  ): Either[StoreError, S3ReaderSettings] =
    if (buckets == null || buckets.isEmpty)
      Left(StoreError.Refused("s3: at least one bucket is required"))
    else if (buckets.exists(b => b == null || !BucketName.matches(b)))
      Left(StoreError.Refused("s3: every bucket must be an S3 bucket name"))
    else if (maxObjectBytes < 1 || maxObjectBytes > MaxObjectBytesLimit)
      Left(StoreError.Refused(s"s3: maxObjectBytes must be in 1..$MaxObjectBytesLimit"))
    else if (maxListKeys < 1 || maxListKeys > MaxListKeysLimit)
      Left(StoreError.Refused(s"s3: maxListKeys must be in 1..$MaxListKeysLimit"))
    else Right(new S3ReaderSettings(buckets, maxObjectBytes, maxListKeys))
}

/**
 * SE-INT-04 (A3): the ordinary-S3 [[ObjectReader]].
 *
 *   - `sha256`: hex of the object's stored full-object SHA-256 checksum, read with
 *     `ChecksumMode.ENABLED` (Q6). An object without one, with checksum type COMPOSITE (or any type
 *     other than FULL_OBJECT), or whose value is not a plain 44-character base64 SHA-256 (a
 *     suffixed value such as `...-3`) is `Corrupt` (D2; whether S3 ever suffixes composite values
 *     is NOT STATED, UNVERIFIED (P)). An absent checksum type with a plain value is accepted (P: a
 *     single-part PutObject is documented as full-object).
 *   - missing: an HTTP 404 on HeadObject or GetObject is `Right(None)` (Q5); 403 is `Permanent`
 *     (without s3:ListBucket S3 answers 403 for a missing key, which is never reported as missing).
 *   - `get`: refuses (aborts) an object whose Content-Length is over `maxObjectBytes`, reads the
 *     body to EOF within that bound (so the SDK's own checksum validation, reported on the read
 *     that returns -1, fires: Q7), aborts when the body exceeds the bound or its length, then
 *     recomputes the SHA-256 itself: a mismatch with the stored checksum is `Corrupt`. A checksum
 *     failure the SDK raises while reading is classified by [[AwsErrors]] (its exception type is
 *     NOT STATED, P).
 *   - `list`: one ListObjectsV2 page (`MaxKeys = maxKeys`), checked to be within the prefix,
 *     strictly ascending by UTF-8 bytes (Q8) and at most `maxKeys`; then ONE HeadObject per listed
 *     key (D1: ListObjectsV2 returns no content type and no checksum value, Q8), so a list costs up
 *     to `maxKeys + 1` calls (D6). A key listed and then not found is `Transient`.
 *   - `contentType` missing or empty is `Corrupt` (the loader always writes one).
 */
final class S3ObjectReader private (calls: S3Calls, settings: S3ReaderSettings)
    extends ObjectReader {

  private def utf8(s: String): Array[Byte] = s.getBytes(StandardCharsets.UTF_8)

  private def checkBucket(bucket: String): Either[StoreError, String] =
    if (bucket != null && settings.buckets.contains(bucket)) Right(bucket)
    else Left(StoreError.Refused("s3: the bucket is not one of the configured buckets"))

  private def checkRef(ref: ObjectRef): Either[StoreError, ObjectRef] =
    if (ref == null) Left(StoreError.Refused("s3: object reference missing"))
    else
      checkBucket(ref.bucket).flatMap { _ =>
        if (ref.key == null || ref.key.isEmpty)
          Left(StoreError.Refused("s3: object key missing"))
        else if (utf8(ref.key).length > S3ReaderSettings.MaxKeyBytes)
          Left(StoreError.Refused(s"s3: object key over ${S3ReaderSettings.MaxKeyBytes} bytes"))
        else Right(ref)
      }

  private def is404(e: Throwable): Boolean = e match {
    case s: AwsServiceException => s.statusCode() == 404
    case _ => false
  }

  private def fail(op: String, e: Throwable): StoreError = AwsErrors.classify("S3", op, e)

  private def meta(
      ref: ObjectRef,
      contentLength: java.lang.Long,
      contentType: String,
      checksum: String,
      checksumType: ChecksumType
  ): Either[StoreError, ObjectMeta] =
    for {
      sha <- S3ObjectReader
        .sha256Hex(checksum, checksumType)
        .left
        .map(why => StoreError.Corrupt(s"${ref.uri}: $why"))
      n <-
        if (contentLength == null || contentLength.longValue < 0)
          Left(StoreError.Corrupt(s"${ref.uri}: no valid Content-Length"))
        else Right(contentLength.longValue)
      ct <-
        if (contentType == null || contentType.isEmpty)
          Left(StoreError.Corrupt(s"${ref.uri}: no content type"))
        else Right(contentType)
    } yield ObjectMeta(ref, sha, n, ct)

  private def headRequest(ref: ObjectRef) =
    HeadObjectRequest
      .builder()
      .bucket(ref.bucket)
      .key(ref.key)
      .checksumMode(ChecksumMode.ENABLED)
      .build()

  def head(ref: ObjectRef): Either[StoreError, Option[ObjectMeta]] =
    checkRef(ref).flatMap { r =>
      (try Right(Option(calls.headObject(headRequest(r))))
      catch {
        case e: Throwable if NonFatal(e) && is404(e) => Right(None)
        case NonFatal(e) => Left(fail("HeadObject", e))
      }).flatMap {
        case None => Right(None)
        case Some(h) =>
          meta(r, h.contentLength(), h.contentType(), h.checksumSHA256(), h.checksumType())
            .map(Some(_))
      }
    }

  def get(ref: ObjectRef): Either[StoreError, Option[(ObjectMeta, Bytes)]] =
    checkRef(ref).flatMap { r =>
      val request = GetObjectRequest
        .builder()
        .bucket(r.bucket)
        .key(r.key)
        .checksumMode(ChecksumMode.ENABLED)
        .build()
      (try Right(Option(calls.getObject(request)))
      catch {
        case e: Throwable if NonFatal(e) && is404(e) => Right(None)
        case NonFatal(e) => Left(fail("GetObject", e))
      }).flatMap {
        case None => Right(None)
        case Some(in) => read(r, in).map(Some(_))
      }
    }

  private def abortQuietly(in: ResponseInputStream[GetObjectResponse]): Unit =
    try in.abort()
    catch { case NonFatal(_) => () }

  private def read(
      ref: ObjectRef,
      in: ResponseInputStream[GetObjectResponse]
  ): Either[StoreError, (ObjectMeta, Bytes)] = {
    val resp = in.response()
    val checked =
      if (resp == null) Left(StoreError.Corrupt(s"${ref.uri}: no response"))
      else
        meta(
          ref,
          resp.contentLength(),
          resp.contentType(),
          resp.checksumSHA256(),
          resp.checksumType()
        )
          .flatMap(m =>
            if (m.byteCount > settings.maxObjectBytes)
              Left(
                StoreError.Permanent(
                  s"${ref.uri}: ${m.byteCount} bytes, over maxObjectBytes ${settings.maxObjectBytes}"
                )
              )
            else Right(m)
          )
    checked match {
      case Left(e) =>
        abortQuietly(in)
        Left(e)
      case Right(m) =>
        val limit = m.byteCount.toInt // <= maxObjectBytes
        val out = new ByteArrayOutputStream(math.min(limit, 1 << 20))
        val buf = new Array[Byte](8192)
        try {
          var total = 0L
          var n = in.read(buf)
          var over = false
          while (n != -1 && !over) {
            total += n
            if (total > limit) over = true
            else {
              out.write(buf, 0, n)
              n = in.read(buf)
            }
          }
          if (over) {
            abortQuietly(in)
            Left(
              StoreError.Corrupt(s"${ref.uri}: body longer than its Content-Length ${m.byteCount}")
            )
          } else {
            in.close()
            val body = Bytes.of(out.toByteArray)
            if (body.size.toLong != m.byteCount)
              Left(
                StoreError.Corrupt(
                  s"${ref.uri}: body of ${body.size} bytes, Content-Length ${m.byteCount}"
                )
              )
            else if (body.sha256 != m.sha256)
              Left(
                StoreError.Corrupt(
                  s"${ref.uri}: body sha256 ${body.sha256} differs from the stored checksum ${m.sha256}"
                )
              )
            else Right((m, body))
          }
        } catch {
          case NonFatal(e) =>
            abortQuietly(in)
            Left(fail("GetObject body", e))
        }
    }
  }

  private def ascending(a: String, b: String): Boolean =
    java.util.Arrays.compareUnsigned(utf8(a), utf8(b)) < 0

  def list(bucket: String, prefix: String, maxKeys: Int): Either[StoreError, Vector[ObjectMeta]] =
    for {
      b <- checkBucket(bucket)
      p <-
        if (prefix == null) Left(StoreError.Refused("s3: prefix missing"))
        else if (utf8(prefix).length > S3ReaderSettings.MaxKeyBytes)
          Left(StoreError.Refused(s"s3: prefix over ${S3ReaderSettings.MaxKeyBytes} bytes"))
        else Right(prefix)
      k <-
        if (maxKeys >= 1 && maxKeys <= settings.maxListKeys) Right(maxKeys)
        else Left(StoreError.Refused(s"maxKeys: $maxKeys is outside 1..${settings.maxListKeys}"))
      resp <-
        try
          Right(
            calls.listObjectsV2(
              ListObjectsV2Request.builder().bucket(b).prefix(p).maxKeys(Integer.valueOf(k)).build()
            )
          )
        catch { case NonFatal(e) => Left(fail("ListObjectsV2", e)) }
      keys <- {
        val contents =
          Option(resp).flatMap(r => Option(r.contents())).map(_.asScala.toVector)
        contents match {
          case None => Left(StoreError.Corrupt(s"s3://$b: ListObjectsV2 returned no contents"))
          case Some(cs) =>
            val ks = cs.map(o => Option(o).flatMap(x => Option(x.key())).orNull)
            if (ks.contains(null)) Left(StoreError.Corrupt(s"s3://$b: a listed key is missing"))
            else if (ks.size > k)
              Left(StoreError.Corrupt(s"s3://$b: ${ks.size} keys listed, above maxKeys $k"))
            else if (ks.exists(!_.startsWith(p)))
              Left(StoreError.Corrupt(s"s3://$b: a listed key is outside the prefix"))
            else if (ks.zip(ks.drop(1)).exists { case (x, y) => !ascending(x, y) })
              Left(StoreError.Corrupt(s"s3://$b: listed keys are not strictly ascending"))
            else Right(ks)
        }
      }
      metas <- keys.foldLeft[Either[StoreError, Vector[ObjectMeta]]](Right(Vector.empty)) {
        (acc, key) =>
          acc.flatMap(ms =>
            head(ObjectRef(b, key)).flatMap {
              case Some(m) => Right(ms :+ m)
              case None =>
                Left(StoreError.Transient(s"s3://$b/$key: listed but not found by HeadObject"))
            }
          )
      }
    } yield metas
}

object S3ObjectReader {

  def of(calls: S3Calls, settings: S3ReaderSettings): S3ObjectReader = {
    require(calls != null && settings != null, "calls and settings are required")
    new S3ObjectReader(calls, settings)
  }

  private val PlainSha256Base64 = "^[A-Za-z0-9+/]{43}=$".r

  /**
   * The hex SHA-256 of a stored S3 checksum, or why it is not a full-object SHA-256 (Q6, D2):
   * required, a plain 44-character base64 value decoding to 32 bytes, and a checksum type that is
   * FULL_OBJECT or absent.
   */
  def sha256Hex(checksum: String, checksumType: ChecksumType): Either[String, String] =
    if (checksum == null || checksum.isEmpty)
      Left("no SHA-256 checksum (uploaded without one, or checksum mode not honoured)")
    else if (checksumType != null && checksumType != ChecksumType.FULL_OBJECT)
      Left(s"checksum type ${checksumType.toString} is not FULL_OBJECT")
    else if (!PlainSha256Base64.matches(checksum))
      Left("the SHA-256 checksum is not a plain base64 full-object value")
    else {
      val raw =
        try Some(Base64.getDecoder.decode(checksum))
        catch { case _: IllegalArgumentException => None }
      raw match {
        case Some(b) if b.length == 32 => Right(b.map(x => f"${x & 0xff}%02x").mkString)
        case _ => Left("the SHA-256 checksum does not decode to 32 bytes")
      }
    }
}
