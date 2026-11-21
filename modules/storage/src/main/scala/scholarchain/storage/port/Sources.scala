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
 * Embedding model port (SE-P8-01 item 5). Everything model-specific comes from the implementation
 * and its configuration; storage names no real model. The production model is NOT decided (human
 * decision pending); the only implementation in storage is the synthetic test embedder, which the
 * production policy (`EmbeddingPolicy.RealModelsOnly`) refuses.
 *
 *   - `modelVersion`: the canonical storage label of the model and its settings
 *     ([[scholarchain.storage.model.EmbeddingModelVersion]], `[a-z0-9][a-z0-9._-]{0,127}`); it is a
 *     component of every vector key and recorded in every vector's metadata, so any change of
 *     model, model revision or embedding settings must change it (new keys, never an overwrite);
 *   - `dimension`: the vector length; must equal every index's configured dimension (the five index
 *     names are fixed, so a model of another dimension needs other indexes: Terraform);
 *   - `embed`: one batch (at most the loader's `batchSize` texts), one vector per text in order; a
 *     `Transient` failure is retried by the loader with bounded backoff. CR-P8-01 M1: a `Permanent`
 *     failure means "some text of this batch is refused": the loader splits the batch in halves
 *     down to single texts (bounded) and records each text still refused alone as one
 *     `embedding-failure` rejection (counted against `maxRejected`), so one bad text cannot stop
 *     every rerun. Any other `Left` (`Transient` after every attempt, `Refused`, `Corrupt`) stops
 *     the call; its rejections so far and the error are recorded in the RunLedger and a rerun
 *     resumes, but a provider that returns such an error deterministically for one text stops every
 *     rerun at that batch (an implementation must report a per-text refusal as `Permanent`).
 *     Determinism is not required.
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
