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
 *   - `documents-staged`: vector loads (SE-P8-01): the call stopped at its configured bound
 *     (`maxItemsPerCall`) with the work manifest, every text object and part of the vectors stored;
 *     the remaining embedding work is pending and the next call resumes it.
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
    updatedAtMillis: Long,
    embeddingModel: Option[String] = None,
    embedding: Option[EmbeddingTotals] = None,
    failure: Option[LoadFailure] = None
) derives ReadWriter

/**
 * CR-P8-01 M1: why the last call of a load stopped with an error, recorded on the transition to
 * `failed` (and kept while the next call is `writing`; cleared by `loaded` and `documents-staged`).
 * Absent on every other row (omitted from the JSON, so rows without a failure are unchanged).
 *
 * @param errorClass
 *   the [[StoreError]] case of the error that stopped the call ([[LoadFailure.classOf]])
 * @param detail
 *   its bounded diagnostic (no record text; control characters replaced, at most
 *   [[LoadFailure.MaxDetailBytes]] UTF-8 bytes)
 * @param generation
 *   the write generation of the failed call (its rejection set, if any, has this generation)
 */
@allowUnknownKeys(false)
final case class LoadFailure(errorClass: String, detail: String, generation: Long)
    derives ReadWriter

object LoadFailure {
  val MaxDetailBytes = 512

  /** Frozen tags of the error classes. */
  val Classes: Vector[String] =
    Vector("transient", "permanent", "conflict", "stale-version", "corrupt", "refused")

  def classOf(e: StoreError): String = e match {
    case StoreError.Transient(_) => "transient"
    case StoreError.Permanent(_) => "permanent"
    case StoreError.Conflict(_) => "conflict"
    case StoreError.StaleVersion(_) => "stale-version"
    case StoreError.Corrupt(_) => "corrupt"
    case StoreError.Refused(_) => "refused"
  }

  private def bytes(s: String): Int = s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length

  /** The failure record of `e` in write generation `generation` (detail sanitized and bounded). */
  def of(e: StoreError, generation: Long): LoadFailure = {
    val clean = Option(e.message).getOrElse("").map(c => if (c.isControl) ' ' else c)
    val out = new StringBuilder
    var n = 0
    clean.foreach { c =>
      val b = bytes(c.toString)
      if (n + b <= MaxDetailBytes) { out += c; n += b }
    }
    LoadFailure(classOf(e), if (out.isEmpty) "failed" else out.toString, generation)
  }

  def problems(f: LoadFailure): Vector[String] =
    if (f == null) Vector("failure: missing")
    else
      Vector(
        Option.when(!Classes.contains(f.errorClass))(s"failure.errorClass: '${f.errorClass}'"),
        Option.when(
          f.detail == null || f.detail.isEmpty || f.detail.exists(_.isControl) ||
            bytes(f.detail) > MaxDetailBytes
        )(s"failure.detail: missing, has control characters, or longer than $MaxDetailBytes bytes"),
        Option.when(f.generation < 1)("failure.generation: below 1")
      ).flatten
}

/**
 * SE-P8-01 (items 2-4): the `s3-vectors` load row is scoped by the embedding model version
 * (`embeddingModel`, key `load#<loadId>#model#<mv>`), so each model has its own state machine,
 * write generations and totals, and a new model version is new work instead of a `loaded` skip.
 * Other loads leave both fields absent (they are omitted from the JSON, so their rows are
 * byte-identical to Phase 7 rows). For a vector row, `written` counts vectors stored,
 * `alreadyPresent` counts items no call of this scope stored (skipped as unchanged by the
 * content-hash-and-model rule, or found equal on put) and `rejected` is the current rejection set;
 * `embedding` holds the rest.
 */
object LoadEntry {
  val SchemaVersion = "scholarchain.storage.load-entry.v1"
}

/**
 * SE-P8-01 items 3-4: the embedding totals of one model-scoped vector load row (RunLedger), summed
 * over the calls (write generations) of that scope.
 *
 * @param workManifestSha256
 *   SHA-256 of the stored work manifest bytes the LAST call worked through (also its object key)
 * @param workItems
 *   items in the work manifest
 * @param embedded
 *   texts sent to the embedder (each text counted once per call that embedded it)
 * @param putRetries
 *   record re-sends after a transient put failure (batch-level or per record)
 * @param embedRetries
 *   text re-sends after a transient embedding failure, or (CR-P8-01 M1) in the halves of a batch
 *   split after a `Permanent` embedding failure
 * @param remaining
 *   manifest items the last call left for later calls by its per-call bound `maxItemsPerCall` (0
 *   once loaded; > 0 in `documents-staged`); a failed call may also leave items of its own slice
 * @param passes
 *   calls that recorded totals
 */
@allowUnknownKeys(false)
final case class EmbeddingTotals(
    workManifestSha256: String,
    workItems: Long,
    embedded: Long,
    putRetries: Long,
    embedRetries: Long,
    remaining: Long,
    passes: Long
) derives ReadWriter

object EmbeddingTotals {
  def problems(t: EmbeddingTotals): Vector[String] =
    if (t == null) Vector("embedding: missing")
    else
      Vector(
        Option.when(!scholarchain.core.Canonical.isHex64(t.workManifestSha256))(
          "embedding.workManifestSha256: not 64 lowercase hex"
        ),
        Option.when(
          Vector(t.workItems, t.embedded, t.putRetries, t.embedRetries, t.remaining, t.passes)
            .exists(_ < 0)
        )("embedding: negative count"),
        Option.when(t.remaining > t.workItems)("embedding.remaining: above workItems")
      ).flatten

  /**
   * The totals after one more call: this call's counts are added; the work manifest, its item count
   * and `remaining` are this call's (a configuration change may change the work list: each list is
   * its own content-addressed manifest object, and which items are done is decided by the
   * vector-store probe, never by the manifest).
   */
  def add(
      before: Option[EmbeddingTotals],
      call: EmbeddingTotals
  ): Either[StoreError, EmbeddingTotals] =
    if (call == null || problems(call).nonEmpty)
      Left(
        StoreError.Refused(s"embedding: ${Option(call).toVector.flatMap(problems).mkString("; ")}")
      )
    else
      Right(before match {
        case None => call.copy(passes = 1L)
        case Some(b) =>
          EmbeddingTotals(
            call.workManifestSha256,
            call.workItems,
            b.embedded + call.embedded,
            b.putRetries + call.putRetries,
            b.embedRetries + call.embedRetries,
            call.remaining,
            b.passes + 1L
          )
      })
}

/**
 * Progress counts carried into a transition. SE-P8-01: `embedding` (vector loads) is the CALL's
 * embedding counts, added to the row's totals by [[RunLedgerStates.advance]]; None keeps them.
 */
final case class LoadCounts(
    written: Long,
    alreadyPresent: Long,
    rejected: Long,
    embedding: Option[EmbeddingTotals] = None,
    failure: Option[LoadFailure] = None
)

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
      nowMillis: Long,
      embeddingModel: Option[String] = None
  ): Either[StoreError, LoadEntry] =
    for {
      r <- StoreIds.require(IdKind.Run, runId, "runId")
      l <- StoreIds.require(IdKind.StoreLoad, loadId, "loadId")
      t <- Option(targetStore).toRight(StoreError.Refused("targetStore: missing"))
      h <- StoreIds.hex64(partManifestSha256, "partManifestSha256")
      n <-
        if (itemCount >= 1) Right(itemCount)
        else Left(StoreError.Refused(s"itemCount: $itemCount is below 1"))
      m <- Option(embeddingModel)
        .toRight(StoreError.Refused("embeddingModel: missing"))
        .flatMap {
          case None => Right(None)
          case Some(v) => EmbeddingModelVersion.check(v).map(Some(_))
        }
    } yield LoadEntry(
      LoadEntry.SchemaVersion,
      r,
      l,
      t,
      h,
      Planned,
      1L,
      n,
      0L,
      0L,
      0L,
      nowMillis,
      m
    )

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
    else if (counts.embedding.isDefined && current.embeddingModel.isEmpty)
      Left(StoreError.Refused("transition: embedding totals need a model-scoped vector load row"))
    else if (counts.failure.isDefined && to != Failed)
      Left(StoreError.Refused("transition: a failure is recorded only on the move to failed"))
    else if (counts.failure.exists(f => LoadFailure.problems(f).nonEmpty))
      Left(StoreError.Refused(counts.failure.toVector.flatMap(LoadFailure.problems).mkString("; ")))
    else
      counts.embedding
        .map(c => EmbeddingTotals.add(current.embedding, c).map(Some(_)))
        .getOrElse(Right(current.embedding))
        .map(embedding =>
          current.copy(
            state = to,
            version = current.version + 1,
            written = counts.written,
            alreadyPresent = counts.alreadyPresent,
            rejected = counts.rejected,
            updatedAtMillis = nowMillis,
            embedding = embedding,
            // CR-P8-01 M1: set by the move to failed, kept while writing, cleared on success.
            failure = to match {
              case Failed => counts.failure
              case Writing => current.failure
              case _ => None
            }
          )
        )
}
