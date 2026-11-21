package scholarchain.storage.fake

import scala.collection.mutable

import scholarchain.storage.model.{Bytes, ObjectRef, PutOutcome, StoreError}
import scholarchain.storage.port.{ObjectMeta, ObjectStore}

/**
 * In-memory ordinary S3 with the adapter's create-once semantics (SE-P7-01): an absent key is
 * `Written`; a present key with equal sha256 is `AlreadyPresent`; different bytes are `Conflict`
 * and the stored bytes are unchanged. No overwrite and no delete exist. Not thread-safe: tests only
 * (it lives in main so rag and services tests can use it).
 *
 * @param maxListKeys
 *   the largest `maxKeys` a list accepts (a service limit of the real store, passed in)
 */
final class InMemoryObjectStore(maxListKeys: Int) extends ObjectStore {
  private val objects = mutable.TreeMap.empty[(String, String), (ObjectMeta, Bytes)]
  private val faults = Faults()

  /** The next writes fail with these errors, in order, before touching the store. */
  def failNextWrites(errors: StoreError*): Unit = faults.enqueue(errors*)

  def size: Int = objects.size

  def head(ref: ObjectRef): Either[StoreError, Option[ObjectMeta]] =
    Right(objects.get((ref.bucket, ref.key)).map(_._1))

  def get(ref: ObjectRef): Either[StoreError, Option[(ObjectMeta, Bytes)]] =
    Right(objects.get((ref.bucket, ref.key)))

  def list(bucket: String, prefix: String, maxKeys: Int): Either[StoreError, Vector[ObjectMeta]] =
    if (maxKeys < 1 || maxKeys > maxListKeys)
      Left(StoreError.Refused(s"maxKeys: $maxKeys is outside 1..$maxListKeys"))
    else
      Right(
        objects.iterator
          .collect { case ((b, k), (m, _)) if b == bucket && k.startsWith(prefix) => m }
          .take(maxKeys)
          .toVector
      )

  def putCreateOnce(
      ref: ObjectRef,
      body: Bytes,
      contentType: String
  ): Either[StoreError, PutOutcome] =
    faults.next() match {
      case Some(e) => Left(e)
      case None =>
        if (ref == null || body == null || contentType == null || contentType.isEmpty)
          Left(StoreError.Refused("putCreateOnce: ref, body and contentType are required"))
        else
          objects.get((ref.bucket, ref.key)) match {
            case None =>
              objects((ref.bucket, ref.key)) =
                (ObjectMeta(ref, body.sha256, body.size.toLong, contentType), body)
              Right(PutOutcome.Written)
            case Some((m, _)) if m.sha256 == body.sha256 => Right(PutOutcome.AlreadyPresent)
            case Some((m, _)) =>
              Left(
                StoreError.Conflict(
                  s"${ref.uri}: holds sha256 ${m.sha256}, refused sha256 ${body.sha256}"
                )
              )
          }
    }
}

/** A queue of injected failures shared by the fakes. */
private[fake] final class Faults {
  private val queue = mutable.Queue.empty[StoreError]
  def enqueue(errors: StoreError*): Unit = queue.enqueueAll(errors)
  def next(): Option[StoreError] = if (queue.isEmpty) None else Some(queue.dequeue())
}
private[fake] object Faults {
  def apply(): Faults = new Faults
}
