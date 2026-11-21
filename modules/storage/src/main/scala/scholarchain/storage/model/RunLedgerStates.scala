package scholarchain.storage.model

import upickle.default.ReadWriter
import upickle.implicits.allowUnknownKeys

import scholarchain.core.{EnumCodec, IdKind, TargetStore}

/**
 * State of one load in RunLedger (SE-P7-01). Frozen tags.
 *
 *   - `planned`: the ledger row exists; nothing verified yet.
 *   - `verified`: the load manifest, part manifest and every part passed verification.
 *   - `writing`: writes are in progress (create-once and conditional, so a resume is safe).
 *   - `documents-staged`: vector loads in Phase 7: text and metadata staged, embeddings pending
 *     (Phase 8 resumes from here).
 *   - `loaded`: complete (terminal); a rerun writes nothing.
 *   - `failed`: stopped after an error; resumable.
 *   - `refused`: verification refused the input (terminal); nothing was written.
 */
enum LoadState(val tag: String, val terminal: Boolean) {
  case Planned extends LoadState("planned", false)
  case Verified extends LoadState("verified", false)
  case Writing extends LoadState("writing", false)
  case DocumentsStaged extends LoadState("documents-staged", false)
  case Loaded extends LoadState("loaded", true)
  case Failed extends LoadState("failed", false)
  case Refused extends LoadState("refused", true)
}
object LoadState {
  def fromTag(tag: String): Option[LoadState] = values.find(_.tag == tag)
  given ReadWriter[LoadState] = EnumCodec.tagged("LoadState", values.toSeq, _.tag)
}

/**
 * The publication of a run: the one `_parts.json` sha256 accepted for this run ID (SE-P7-01 R1). A
 * second publication of the same run ID with other bytes is a `Conflict`.
 */
@allowUnknownKeys(false)
final case class RunPublication(
    schemaVersion: String,
    runId: String,
    partManifestSha256: String,
    firstLoadId: String
) derives ReadWriter

object RunPublication {
  val SchemaVersion = "scholarchain.storage.run-publication.v1"
}

/**
 * CR-P7-10 m1 (human decision 2026-11-20, option b): ONE analysis run per local store instance. The
 * first load of a target store claims this row (`putIfAbsent`, RunLedger key `store#<tag>` /
 * `owner`) BEFORE any data write; a later load of the same store by any other run (another run ID
 * or another `_parts.json` SHA-256) is refused before its first data write, with zero data writes,
 * whatever keys its data would use (another window set shares no key with the first run, so the
 * per-key probe alone could not see it). The same run (resume, rerun) passes. Another run needs
 * fresh stores AND a fresh RunLedger; multi-run coexistence and active-run switching are deferred.
 *
 * The row lives in the RunLedger paired with the stores: a fresh ledger in front of populated
 * stores has no owner row, and then only the per-key pre-write probe protects them (documented
 * limitation).
 */
@allowUnknownKeys(false)
final case class StoreOwnership(
    schemaVersion: String,
    targetStore: TargetStore,
    runId: String,
    partManifestSha256: String
) derives ReadWriter

object StoreOwnership {
  val SchemaVersion = "scholarchain.storage.store-ownership.v1"
}

/**
 * One load's ledger row. `version` starts at 1 and increases by exactly 1 per transition; every
 * update is a `putIfVersion` on the previous version, so two concurrent loaders never both win.
 * `written` and `alreadyPresent` are totals over the load's calls; `rejected` is the size of the
 * current rejection set (SE-P7-11 item 4: the last write generation that recorded one), never a sum
 * over generations.
 */
@allowUnknownKeys(false)
final case class LoadEntry(
    schemaVersion: String,
    runId: String,
    loadId: String,
    targetStore: TargetStore,
    partManifestSha256: String,
    state: LoadState,
    version: Long,
    itemCount: Int,
    written: Long,
    alreadyPresent: Long,
    rejected: Long,
    updatedAtMillis: Long
) derives ReadWriter

object LoadEntry {
  val SchemaVersion = "scholarchain.storage.load-entry.v1"
}

/** Progress counts carried into a transition. */
final case class LoadCounts(written: Long, alreadyPresent: Long, rejected: Long)

/** The RunLedger load state machine (SE-P7-01). */
object RunLedgerStates {
  import LoadState.*

  /** Allowed transitions; a state never transitions to itself. */
  val transitions: Map[LoadState, Set[LoadState]] = Map(
    Planned -> Set(Verified, Refused),
    Verified -> Set(Writing),
    Writing -> Set(Loaded, DocumentsStaged, Failed),
    DocumentsStaged -> Set(Writing),
    Failed -> Set(Writing),
    Loaded -> Set.empty,
    Refused -> Set.empty
  )

  def allowed(from: LoadState, to: LoadState): Boolean =
    transitions.getOrElse(from, Set.empty).contains(to)

  /** The first ledger row of a load: state `planned`, version 1, zero counts. */
  def start(
      runId: String,
      loadId: String,
      targetStore: TargetStore,
      partManifestSha256: String,
      itemCount: Int,
      nowMillis: Long
  ): Either[StoreError, LoadEntry] =
    for {
      r <- StoreIds.require(IdKind.Run, runId, "runId")
      l <- StoreIds.require(IdKind.StoreLoad, loadId, "loadId")
      t <- Option(targetStore).toRight(StoreError.Refused("targetStore: missing"))
      h <- StoreIds.hex64(partManifestSha256, "partManifestSha256")
      n <-
        if (itemCount >= 1) Right(itemCount)
        else Left(StoreError.Refused(s"itemCount: $itemCount is below 1"))
    } yield LoadEntry(LoadEntry.SchemaVersion, r, l, t, h, Planned, 1L, n, 0L, 0L, 0L, nowMillis)

  /**
   * The next ledger row: `to` must be an allowed transition, `written` and `alreadyPresent` never
   * decrease, the version increases by exactly 1. `rejected` is the size of the CURRENT rejection
   * set (SE-P7-11 item 4: the last write generation that recorded one), so it may decrease (a rerun
   * that rejects fewer records, or none) but is never negative. The caller writes it with
   * `putIfVersion(previous.version)`.
   */
  def advance(
      current: LoadEntry,
      to: LoadState,
      counts: LoadCounts,
      nowMillis: Long
  ): Either[StoreError, LoadEntry] =
    if (current == null || to == null) Left(StoreError.Refused("transition: missing state"))
    else if (!allowed(current.state, to))
      Left(
        StoreError.Refused(
          s"transition: ${current.state.tag} -> ${to.tag} is not allowed for ${current.loadId}"
        )
      )
    else if (counts.written < current.written || counts.alreadyPresent < current.alreadyPresent)
      Left(StoreError.Refused("transition: counts never decrease"))
    else if (counts.rejected < 0)
      Left(StoreError.Refused("transition: the rejected count is negative"))
    else
      Right(
        current.copy(
          state = to,
          version = current.version + 1,
          written = counts.written,
          alreadyPresent = counts.alreadyPresent,
          rejected = counts.rejected,
          updatedAtMillis = nowMillis
        )
      )
}
