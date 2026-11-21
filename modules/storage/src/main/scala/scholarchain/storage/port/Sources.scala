package scholarchain.storage.port

import scholarchain.storage.model.{Bytes, StoreError}

/**
 * Read access to one published directory: a run directory or its load-manifest directory (local
 * archive now, S3 curated zone in a later increment). Loaders observe `_SUCCESS`, `_parts.json`,
 * the run manifest, the parts and the load manifests through it before any write (SE-P7-01,
 * SE-P7-03).
 *
 * Contract of every implementation: a symbolic link (file, directory or the root itself) is never
 * followed and is refused; anything other than regular files and directories is refused; paths are
 * `/`-separated and relative to the directory. Bounds
 * ([[scholarchain.storage.model.SourceBounds]]), exactly:
 *   - [[listFiles]] refuses the whole listing when the directory holds more than `maxFiles` files,
 *     more than `maxTotalBytes` bytes in total, or a file deeper than `maxDepth`;
 *   - [[read]] refuses a path outside the part-manifest path rules, deeper than `maxDepth`, or a
 *     file larger than `maxTotalBytes`. It does not require a prior listing and does not check that
 *     the path was listed (`maxFiles` bounds listings only); callers read only listed or
 *     part-manifest paths.
 *   - [[readAtMost]] (SE-P7-05, risk T1) is [[read]] with a caller bound: it refuses a file of more
 *     than `maxBytes` bytes and never reads more than `maxBytes + 1` bytes of it, so an oversized
 *     or endless file costs at most that much. Verified reads pass the byte count of the part's
 *     manifest entry.
 */
trait RunSource {

  /** Every regular file path of the directory, recursively, relative to it, ascending by UTF-8. */
  def listFiles(): Either[StoreError, Vector[String]]

  /** The complete bytes of one file, read now (no caching), bounded by the source's bounds. */
  def read(relativePath: String): Either[StoreError, Bytes]

  /**
   * The complete bytes of one file, read now, only if it holds at most `maxBytes` bytes (and the
   * source's own bounds hold); at most `maxBytes + 1` bytes are read. `maxBytes` below 0 is
   * refused.
   */
  def readAtMost(relativePath: String, maxBytes: Long): Either[StoreError, Bytes]
}

/** Decodes the rows of one part file (JSON lines, CSV or Parquet adapters). */
trait PartReader[A] {
  def decode(relativePath: String, bytes: Bytes): Either[StoreError, Vector[A]]
}

/**
 * Embedding model port (Phase 8 provides the real model; Phase 7 tests use a deterministic fake).
 */
trait Embedder {
  def modelVersion: String
  def dimension: Int
  def embed(texts: Vector[String]): Either[StoreError, Vector[Vector[Float]]]
}

/** Wall clock, injectable for tests. */
trait Clock {
  def nowMillis(): Long
}

/** Backoff sleeping, injectable for tests. */
trait Sleeper {
  def sleep(millis: Long): Unit
}

/** Full-jitter source: a value in `0..upperInclusive`. */
trait Jitter {
  def uniform(upperInclusive: Long): Long
}
