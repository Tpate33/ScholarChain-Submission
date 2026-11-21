package scholarchain.storage.model

/**
 * Outcome of an idempotent write (SE-P7-01). `AlreadyPresent` means the store already held exactly
 * the bytes or record being written (equal content hash), so nothing changed.
 */
enum PutOutcome {
  case Written
  case AlreadyPresent
}

/**
 * Every storage failure (SE-P7-01). A `detail` never carries record text, credentials or other
 * secret values: it names keys, IDs, hashes and reasons only.
 *
 *   - `Transient`: throttling, timeouts, 5xx; the only retryable class.
 *   - `Permanent`: the store refused the request for a reason a retry does not change.
 *   - `Conflict`: the key already holds a different content hash (create-once or putIfAbsent).
 *   - `StaleVersion`: an optimistic `putIfVersion` lost (the stored version differs).
 *   - `Corrupt`: stored bytes or an item fail their own integrity check (hash, schema, keys).
 *   - `Refused`: the caller's request breaks a storage rule (unknown table or index, bad key, a
 *     write the role may not make); nothing was sent.
 */
enum StoreError {
  case Transient(detail: String)
  case Permanent(detail: String)
  case Conflict(detail: String)
  case StaleVersion(detail: String)
  case Corrupt(detail: String)
  case Refused(detail: String)

  /** True only for [[Transient]]. */
  def retryable: Boolean = this match {
    case Transient(_) => true
    case _ => false
  }

  /** The detail text of any case. */
  def message: String = this match {
    case Transient(d) => d
    case Permanent(d) => d
    case Conflict(d) => d
    case StaleVersion(d) => d
    case Corrupt(d) => d
    case Refused(d) => d
  }
}
