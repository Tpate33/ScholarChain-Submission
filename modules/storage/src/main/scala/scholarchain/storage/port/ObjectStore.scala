package scholarchain.storage.port

import scholarchain.storage.model.{Bytes, ObjectRef, PutOutcome, StoreError}

/** What a head or list returns about one object. */
final case class ObjectMeta(ref: ObjectRef, sha256: String, byteCount: Long, contentType: String)

/** Read side of ordinary S3 (runtime and loaders). */
trait ObjectReader {
  def head(ref: ObjectRef): Either[StoreError, Option[ObjectMeta]]
  def get(ref: ObjectRef): Either[StoreError, Option[(ObjectMeta, Bytes)]]

  /** At most `maxKeys` objects under `prefix`, ascending by key (bounded: never a full listing). */
  def list(bucket: String, prefix: String, maxKeys: Int): Either[StoreError, Vector[ObjectMeta]]
}

/**
 * Write side of ordinary S3: create-once only (SE-P7-01). The SDK adapter sends `If-None-Match: *`
 * with `ChecksumSHA256`; on a precondition failure it heads the object and returns `AlreadyPresent`
 * for equal sha256 and `Conflict` otherwise. There is no overwrite and no delete: storage code
 * never deletes (cleanup is the human's `scripts/cleanup.sh`).
 */
trait ObjectWriter {
  def putCreateOnce(
      ref: ObjectRef,
      body: Bytes,
      contentType: String
  ): Either[StoreError, PutOutcome]
}

/** Both sides, for loaders. Runtime composition receives only [[ObjectReader]]. */
trait ObjectStore extends ObjectReader with ObjectWriter

object ObjectReader {

  /** A facade exposing only the read methods of `r` (it cannot be cast back to a writer). */
  def readOnly(r: ObjectReader): ObjectReader = new ObjectReader {
    def head(ref: ObjectRef) = r.head(ref)
    def get(ref: ObjectRef) = r.get(ref)
    def list(bucket: String, prefix: String, maxKeys: Int) = r.list(bucket, prefix, maxKeys)
  }
}
