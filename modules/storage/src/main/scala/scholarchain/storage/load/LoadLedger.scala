package scholarchain.storage.load

import scholarchain.core.TargetStore
import scholarchain.storage.model.{
  EmbeddingTotals,
  ItemCodecs,
  LoadCounts,
  LoadEntry,
  LoadFailure,
  LoadState,
  PutOutcome,
  RunLedgerStates,
  RunPublication,
  StoreError,
  StoreOwnership,
  TableKeys
}
import scholarchain.storage.port.{Clock, KeyValueStore}

/** What one ledger-guarded load call did (counts of THIS call). */
final case class LedgerRun(
    written: Long,
    alreadyPresent: Long,
    finalState: LoadState,
    skipped: Boolean,
    rejected: Long = 0L
)

/**
 * The elements one load call actually created, found present and rejected so far (CR-P7-10 m2). A
 * loader records into it as it writes, so a caller can report true partial counts after a failure
 * (the call itself returns only the error). Only package `load` records; everyone may read.
 */
final class LoadProgress {
  private var w = 0L
  private var p = 0L
  private var r = 0L
  private val side = scala.collection.mutable.LinkedHashMap.empty[String, Long]
  private var sets = 0
  private var setSize = 0L
  def written: Long = w
  def alreadyPresent: Long = p

  /** Rejected-record rows newly written so far (any generation recorded through this progress). */
  def rejected: Long = r

  /**
   * SE-P7-11 item 4: how many complete rejection sets (rows and the rejects object of one write
   * generation) were recorded through this progress, and the size of the last one. The ledger's
   * `rejected` count is the size of the CURRENT set (the last generation that recorded one), not a
   * sum over generations. SE-P8-03: only COMPLETE sets are counted here; the incomplete set of a
   * failed call is not.
   */
  def rejectionSetsRecorded: Int = sets
  def lastRejectionSetSize: Long = setSize
  private[load] def rejectionSet(n: Long): Unit = { sets += 1; setSize = n }

  /**
   * CR-P7-11 m3: writes of this call to the stores a loader writes besides its target (text
   * objects, registry rows, rejected-record evidence), by name, in first-recorded order (the order
   * of the loader's writes, so deterministic). Empty for a loader that writes only its target.
   */
  def sideCounts: Vector[(String, Long)] = side.toVector
  private[load] def wrote(): Unit = w += 1
  private[load] def present(): Unit = p += 1
  private[load] def rejectedOne(): Unit = r += 1
  private[load] def sideWrite(name: String): Unit = side.update(name, side.getOrElse(name, 0L) + 1)

  // SE-P8-01 items 3-4: embedding counts recorded by the vector loader (None until its work
  // manifest is known); the ledger adds this call's part of them to the row's EmbeddingTotals.
  private var emb: Option[EmbeddingTotals] = None
  def embedding: Option[EmbeddingTotals] = emb
  private[load] def workManifest(sha256: String, items: Long, remaining: Long): Unit =
    emb = Some(
      emb.fold(EmbeddingTotals(sha256, items, 0L, 0L, 0L, remaining, 0L))(
        _.copy(workManifestSha256 = sha256, workItems = items, remaining = remaining)
      )
    )
  private[load] def embedded(n: Long): Unit = emb = emb.map(e => e.copy(embedded = e.embedded + n))
  private[load] def putRetried(n: Long): Unit =
    emb = emb.map(e => e.copy(putRetries = e.putRetries + n))
  private[load] def embedRetried(n: Long): Unit =
    emb = emb.map(e => e.copy(embedRetries = e.embedRetries + n))

  override def toString: String =
    s"LoadProgress(written=$w, alreadyPresent=$p, rejected=$r, side=${sideCounts.mkString(",")}" +
      emb.fold("")(e => s", embedding=$e") + ")"
}

/**
 * SE-P8-01 item 3: how a write pass ended. `Complete`: every element was handled; the row moves to
 * `loaded`. `Staged`: the pass stopped at its configured bound with work remaining; the row moves
 * to `documents-staged` and the next call resumes.
 */
enum PassEnd {
  case Complete
  case Staged
}

/**
 * The write attempt of one ledger-guarded call (CR-P7-11 m1): `generation` is the RunLedger row's
 * version once THIS call moved it to `writing`, so every call that writes has its own generation
 * (strictly increasing over the calls of one load); evidence keyed by it (rejected-record rows, the
 * rejects object) is never re-written by a later call. (CR-P7-12 F2: the former `priorRejected`
 * field is removed; whether an empty set must be recorded is decided from the stored RL-8
 * summaries, never from the ledger count.)
 */
final case class WriteAttempt(generation: Long, embeddingModel: Option[String] = None)

/**
 * SE-P9-01: what one RunLedger load row describes: the run, its `_parts.json` SHA-256, the load ID,
 * the target store and the row's item count. A [[LoadPermit]] gives the subject of a manifest load
 * ([[LedgerSubject.of]]); a load derived from a verified run (the entity-link load,
 * [[EntityLinkLoad]]) names its own deterministic load ID under the permit's run and store, so it
 * gets its own row, state machine and generations under the same protocol.
 */
private[load] final case class LedgerSubject(
    runId: String,
    partManifestSha256: String,
    loadId: String,
    target: TargetStore,
    itemCount: Int
)

private[load] object LedgerSubject {
  def of(p: LoadPermit): LedgerSubject =
    LedgerSubject(p.runId, p.partManifestSha256, p.load.loadId, p.target, p.items.size)
}

/**
 * The RunLedger protocol every loader follows (SE-P7-05 B4, generalized in SE-P7-06 item 7; risk
 * T2, checklist L2), for any target store:
 *
 * 0. (CR-P7-10 m1, CR-P7-11 M1) the store-owner rows ([[StoreOwnership]]) of EVERY physical store
 * the loader writes (its target and, for the s3 and s3-vectors loaders, the object store and the
 * key-value store their registry rows go to): one analysis run per store instance (human decision
 * 2026-11-20). First every row is read; a row naming another run or `_parts.json` SHA-256 refuses
 * the load with zero writes (not even a ledger write). Then every absent row is claimed with
 * `putIfAbsent` (a lost race to another run is the same refusal), before any data write;
 *   1. claim the run's publication row with `putIfAbsent` BEFORE any data write. A `Conflict` there
 *      is decided by `partManifestSha256` alone: the same SHA-256 is the same publication; another
 *      SHA-256 is a `Conflict` and nothing is written;
 *   2. create (`putIfAbsent`) or adopt the load's RunLedger row (same run, load, store, SHA-256); a
 *      row already `loaded` ends the call with zero writes (`skipped`);
 *   3. move the row to `writing` with `putIfVersion` (a lost race re-reads and decides again); a
 *      row found already `writing` at the start of the call (a call that stopped without marking it
 *      failed) is first moved to `failed`, so this call writes under a new generation
 *      ([[WriteAttempt]]);
 *   4. probe every element, then write every element create-once: `Written`, `AlreadyPresent` (a
 *      resume or a concurrent loader), or an error (a `Conflict` is never an overwrite): the row
 *      moves to `failed` and the call fails, so a rerun resumes;
 *   5. move the row to `loaded` with the counts; a lost race to a finished loader is success.
 *
 * Ledger counts (CR-P7-10 m2): `written` is the total created by every call of the load;
 * `alreadyPresent` counts only elements no call of this load created (a resumed call finds its own
 * earlier writes present, and those are not added again). `rejected` (SE-P7-11 item 4) is the size
 * of the CURRENT rejection set: the set of the last call (write generation) that recorded one, so a
 * rerun that rejects another set replaces it and a clean rerun sets it to 0; a call that stops
 * before recording its set, or (SE-P8-03) stops with an error and records an INCOMPLETE set, leaves
 * it unchanged. The authoritative current set is the highest COMPLETE RL-8
 * [[scholarchain.storage.model.RejectionSet]] summary (`RejectionSet.current`; its generation's
 * rows by RL-7); the ledger count is a convenience copy of that summary's count.
 */
private[load] object LoadLedger {

  private val publications = ItemCodecs.RunPublicationCodec
  private val entries = ItemCodecs.LoadEntryCodec
  private val owners = ItemCodecs.StoreOwnershipCodec

  /**
   * Bound on ledger re-reads after lost races in one call (each is another loader's transition).
   */
  private val MaxLedgerRounds: Int = 2 * LoadState.values.length

  /**
   * One element at a time.
   *
   * @param probe
   *   CR-P7-09 M1: reads the stored element under `e`'s key or ID; Right when it is absent or
   *   equal, `Conflict` when a different element is stored. Every element is probed after the
   *   ledger row moved to `writing` and BEFORE the first data write; any Left moves the row to
   *   `failed` and ends the call with zero data writes. The probe and the writes are NOT atomic
   *   (CR-P7-10 m1): a concurrent writer that stores a different element after the probe is still
   *   caught by the create-once write itself, but only as a `Conflict` after the writes before it
   *   (k-1 of them) were made; those stay, and the load is `failed`. One run per store instance is
   *   enforced by the store-owner row (step 0), not by the probe.
   */
  def run[E](
      permit: LoadPermit,
      ledger: KeyValueStore,
      clock: Clock,
      elements: Vector[E],
      stores: Vector[TargetStore],
      progress: LoadProgress
  )(
      probe: E => Either[StoreError, Unit]
  )(
      write: E => Either[StoreError, PutOutcome]
  ): Either[StoreError, LedgerRun] =
    runBulk(permit, ledger, clock, elements, stores, progress)(es =>
      es.iterator.map(probe).collectFirst { case Left(e) => e }.toLeft(())
    ) { (_, es) =>
      val it = es.iterator
      var failure: Option[StoreError] = None
      while (failure.isEmpty && it.hasNext)
        write(it.next()) match {
          case Right(PutOutcome.Written) => progress.wrote()
          case Right(PutOutcome.AlreadyPresent) => progress.present()
          case Left(e) => failure = Some(e)
        }
      failure.toLeft(())
    }

  /**
   * The same protocol with a writer of the whole element vector (the vector loader batches). The
   * writer receives this call's [[WriteAttempt]], records into `progress` (written, present, newly
   * rejected, side writes) as it goes and returns the first error that stops the load.
   *
   * @param stores
   *   every physical store the loader writes (CR-P7-11 M1); must contain `permit.target`
   */
  def runBulk[E](
      permit: LoadPermit,
      ledger: KeyValueStore,
      clock: Clock,
      elements: Vector[E],
      stores: Vector[TargetStore],
      progress: LoadProgress
  )(
      probeAll: Vector[E] => Either[StoreError, Unit]
  )(
      writeAll: (WriteAttempt, Vector[E]) => Either[StoreError, Unit]
  ): Either[StoreError, LedgerRun] =
    runScoped(permit, ledger, clock, elements, stores, progress, None)(probeAll)((a, es) =>
      writeAll(a, es).map(_ => PassEnd.Complete)
    )

  /**
   * SE-P8-01: the protocol for one ledger SCOPE of a load (`embeddingModel`: the vector load under
   * one embedding model version has its own row `load#<loadId>#model#<mv>`, its own generations and
   * totals), with a writer that may end a pass [[PassEnd.Staged]] (bounded work per call): the row
   * then moves to `documents-staged` instead of `loaded`, and the next call resumes it.
   */
  def runScoped[E](
      permit: LoadPermit,
      ledger: KeyValueStore,
      clock: Clock,
      elements: Vector[E],
      stores: Vector[TargetStore],
      progress: LoadProgress,
      embeddingModel: Option[String]
  )(
      probeAll: Vector[E] => Either[StoreError, Unit]
  )(
      writeAll: (WriteAttempt, Vector[E]) => Either[StoreError, PassEnd]
  ): Either[StoreError, LedgerRun] =
    runSubject(LedgerSubject.of(permit), ledger, clock, elements, stores, progress, embeddingModel)(
      probeAll
    )(writeAll)

  /** [[runScoped]] for any [[LedgerSubject]] (SE-P9-01: a derived load's own row). */
  def runSubject[E](
      permit: LedgerSubject,
      ledger: KeyValueStore,
      clock: Clock,
      elements: Vector[E],
      stores: Vector[TargetStore],
      progress: LoadProgress,
      embeddingModel: Option[String]
  )(
      probeAll: Vector[E] => Either[StoreError, Unit]
  )(
      writeAll: (WriteAttempt, Vector[E]) => Either[StoreError, PassEnd]
  ): Either[StoreError, LedgerRun] =
    for {
      _ <- refuseUnscopedRow(permit, ledger, embeddingModel)
      _ <- claimOwners(permit, ledger, stores)
      _ <- claimPublication(permit, ledger)
      entry <- ensureEntry(permit, ledger, clock, embeddingModel)
      out <-
        if (entry.state == LoadState.Loaded)
          Right(LedgerRun(0L, 0L, LoadState.Loaded, skipped = true))
        else probeAndWrite(permit, ledger, clock, entry, elements, progress, probeAll, writeAll)
    } yield out

  /**
   * CR-P8-01 m1 (choice: refuse, not only document): a model-scoped load (SE-P8-01) refuses, with
   * zero writes (read only, before the owner claims), a RunLedger that holds the Phase 7 UNSCOPED
   * row `load#<loadId>` of the same load. Such a ledger was written by the Phase 7 loader, whose
   * vectors used the 2-part keys `<index>/<inputId>` that the model-scoped loader neither probes
   * nor counts; resuming beside them would silently leave them in the indexes. Use fresh stores and
   * a fresh RunLedger. Documented limitation: Phase 7 2-part vector keys in a store paired with a
   * FRESH ledger are not detected (S3 Vectors has no key listing in the port); one analysis run per
   * store instance (human decision 2026-11-20) makes that a misconfiguration.
   */
  private def refuseUnscopedRow(
      permit: LedgerSubject,
      ledger: KeyValueStore,
      embeddingModel: Option[String]
  ): Either[StoreError, Unit] =
    if (embeddingModel == null || embeddingModel.isEmpty) Right(())
    else
      for {
        key <- TableKeys.RunLedger.load(permit.runId, permit.loadId, None)
        row <- ledger.get(entries.table.role, key)
        _ <- row match {
          case None => Right(())
          case Some(_) =>
            Left(
              StoreError.Refused(
                s"${permit.loadId}: the RunLedger holds an unscoped (Phase 7) " +
                  s"${permit.target.tag} row ${key.sk}; its vectors use pre-SE-P8-01 keys the " +
                  "model-scoped loader does not manage: load into fresh stores with a fresh RunLedger"
              )
            )
        }
      } yield ()

  /** The one message of a store owned by another run (one run per store instance). */
  private[load] def ownedByOther(
      store: TargetStore,
      other: StoreOwnership,
      runId: String,
      partManifestSha256: String
  ): StoreError.Conflict =
    StoreError.Conflict(
      s"${store.tag}: this store instance is owned by run ${other.runId} " +
        s"(_parts.json sha256 ${other.partManifestSha256}); run $runId " +
        s"(sha256 $partManifestSha256) is refused before any data write: one " +
        "analysis run per local store instance (human decision 2026-11-20); load " +
        "another run into fresh stores with a fresh RunLedger"
    )

  /**
   * The owner row of `store` (read only): None when absent. A row that does not decode is `Corrupt`
   * (fail closed).
   */
  private[load] def ownerOf(
      ledger: KeyValueStore,
      store: TargetStore
  ): Either[StoreError, Option[StoreOwnership]] =
    for {
      key <- TableKeys.RunLedger.storeOwner(store)
      row <- ledger.get(owners.table.role, key)
      decoded <- row match {
        case None => Right(None)
        case Some(i) => owners.decode(i).map(Some(_))
      }
    } yield decoded

  /**
   * CR-P7-11 M1: the first of `stores` owned by another run (another run ID or `_parts.json`
   * SHA-256) as its refusal, or Right when every row is absent or this run's. Reads only.
   */
  private[load] def checkOwners(
      ledger: KeyValueStore,
      runId: String,
      partManifestSha256: String,
      stores: Vector[TargetStore]
  ): Either[StoreError, Unit] =
    stores.foldLeft[Either[StoreError, Unit]](Right(())) { (acc, s) =>
      acc.flatMap(_ =>
        ownerOf(ledger, s).flatMap {
          case Some(o) if o.runId != runId || o.partManifestSha256 != partManifestSha256 =>
            Left(ownedByOther(s, o, runId, partManifestSha256))
          case _ => Right(())
        }
      )
    }

  /**
   * Step 0 (CR-P7-10 m1, CR-P7-11 M1): every store the loader writes belongs to this run. All rows
   * are checked (reads) before any is claimed, so a refusal writes nothing at all.
   */
  private def claimOwners(
      permit: LedgerSubject,
      ledger: KeyValueStore,
      stores: Vector[TargetStore]
  ): Either[StoreError, Unit] =
    if (stores == null || stores.contains(null) || !stores.contains(permit.target))
      Left(StoreError.Refused(s"${permit.loadId}: the written stores must include the target"))
    else {
      val ordered = TargetStore.values.toVector.filter(stores.contains)
      for {
        _ <- checkOwners(ledger, permit.runId, permit.partManifestSha256, ordered)
        _ <- ordered.foldLeft[Either[StoreError, Unit]](Right(())) { (acc, s) =>
          acc.flatMap(_ => claimOwner(permit, ledger, s))
        }
      } yield ()
    }

  private def claimOwner(
      permit: LedgerSubject,
      ledger: KeyValueStore,
      store: TargetStore
  ): Either[StoreError, Unit] = {
    val mine = StoreOwnership(
      StoreOwnership.SchemaVersion,
      store,
      permit.runId,
      permit.partManifestSha256
    )
    for {
      item <- owners.encode(mine)
      _ <- ledger.putIfAbsent(owners.table.role, item) match {
        case Right(_) => Right(())
        case Left(StoreError.Conflict(_)) =>
          // Lost a race after the check: the row is another run's now.
          ownerOf(ledger, store).flatMap {
            case Some(other) =>
              Left(ownedByOther(store, other, permit.runId, permit.partManifestSha256))
            case None => Left(StoreError.Corrupt("store-owner row vanished"))
          }
        case Left(e) => Left(e)
      }
    } yield ()
  }

  private def claimPublication(
      permit: LedgerSubject,
      ledger: KeyValueStore
  ): Either[StoreError, Unit] =
    for {
      item <- publications.encode(
        RunPublication(
          RunPublication.SchemaVersion,
          permit.runId,
          permit.partManifestSha256,
          permit.loadId
        )
      )
      _ <- ledger.putIfAbsent(publications.table.role, item) match {
        case Right(_) => Right(())
        case Left(StoreError.Conflict(_)) =>
          // The row exists with other content (another store's first load): same run iff same sha.
          Publications.check(ledger, permit.runId, permit.partManifestSha256).map(_ => ())
        case Left(e) => Left(e)
      }
    } yield ()

  private def readEntry(
      permit: LedgerSubject,
      ledger: KeyValueStore,
      scope: Option[String]
  ): Either[StoreError, LoadEntry] =
    for {
      key <- TableKeys.RunLedger.load(permit.runId, permit.loadId, scope)
      item <- ledger.get(entries.table.role, key)
      e <- item.toRight(StoreError.Corrupt(s"${permit.loadId}: ledger row vanished"))
      entry <- entries.decode(e)
      _ <-
        if (
          entry.runId == permit.runId && entry.loadId == permit.loadId &&
          entry.targetStore == permit.target &&
          entry.partManifestSha256 == permit.partManifestSha256 && entry.embeddingModel == scope
        ) Right(())
        else
          Left(
            StoreError
              .Conflict(s"${permit.loadId}: the ledger row describes another run or load")
          )
    } yield entry

  private def ensureEntry(
      permit: LedgerSubject,
      ledger: KeyValueStore,
      clock: Clock,
      scope: Option[String]
  ): Either[StoreError, LoadEntry] =
    for {
      start <- RunLedgerStates.start(
        permit.runId,
        permit.loadId,
        permit.target,
        permit.partManifestSha256,
        permit.itemCount,
        clock.nowMillis(),
        scope
      )
      item <- entries.encode(start)
      entry <- ledger.putIfAbsent(entries.table.role, item) match {
        case Right(PutOutcome.Written) => Right(start)
        case Right(PutOutcome.AlreadyPresent) => readEntry(permit, ledger, scope)
        case Left(StoreError.Conflict(_)) => readEntry(permit, ledger, scope) // later state/time
        case Left(e) => Left(e)
      }
    } yield entry

  private def advance(
      permit: LedgerSubject,
      ledger: KeyValueStore,
      clock: Clock,
      current: LoadEntry,
      to: LoadState,
      counts: LoadCounts
  ): Either[StoreError, LoadEntry] =
    for {
      next <- RunLedgerStates.advance(current, to, counts, clock.nowMillis())
      item <- entries.encode(next)
      stored <- ledger.putIfVersion(entries.table.role, item, current.version) match {
        case Right(()) => Right(next)
        case Left(StoreError.StaleVersion(_)) => readEntry(permit, ledger, current.embeddingModel)
        case Left(e) => Left(e)
      }
    } yield stored

  private def toWriting(
      permit: LedgerSubject,
      ledger: KeyValueStore,
      clock: Clock,
      start: LoadEntry,
      fresh: Boolean
  ): Either[StoreError, LoadEntry] = {
    def counts(e: LoadEntry) = LoadCounts(e.written, e.alreadyPresent, e.rejected)
    def go(e: LoadEntry, rounds: Int): Either[StoreError, LoadEntry] =
      if (rounds > MaxLedgerRounds)
        Left(StoreError.Transient(s"${e.loadId}: ledger contended for $MaxLedgerRounds rounds"))
      else
        e.state match {
          case LoadState.Writing if fresh && rounds == 0 =>
            // CR-P7-11 m1: a row left `writing` by a call that stopped: a new generation.
            advance(permit, ledger, clock, e, LoadState.Failed, counts(e)).flatMap(
              go(_, rounds + 1)
            )
          case LoadState.Writing | LoadState.Loaded => Right(e)
          case LoadState.Planned =>
            advance(permit, ledger, clock, e, LoadState.Verified, counts(e)).flatMap(
              go(_, rounds + 1)
            )
          case LoadState.Verified | LoadState.Failed | LoadState.DocumentsStaged =>
            advance(permit, ledger, clock, e, LoadState.Writing, counts(e)).flatMap(
              go(_, rounds + 1)
            )
          case LoadState.Refused =>
            Left(StoreError.Refused(s"${e.loadId}: the ledger records this load as refused"))
        }
    go(start, 0)
  }

  /**
   * The ledger totals after this call (CR-P7-10 m2): written accumulates; alreadyPresent counts the
   * elements processed by any call that no call of this load created, never decreasing. SE-P7-11
   * item 4: `rejectionSet` is the size of the rejection set this call recorded (None when it
   * recorded none), which replaces the ledger's count; otherwise the count is kept.
   */
  private[load] def totals(
      e: LoadEntry,
      written: Long,
      present: Long,
      rejectionSet: Option[Long]
  ): LoadCounts = {
    val writtenTotal = e.written + written
    val processed = math.max(e.written + e.alreadyPresent, written + present)
    LoadCounts(
      writtenTotal,
      math.max(e.alreadyPresent, processed - writtenTotal),
      rejectionSet.getOrElse(e.rejected)
    )
  }

  private def probeAndWrite[E](
      permit: LedgerSubject,
      ledger: KeyValueStore,
      clock: Clock,
      start: LoadEntry,
      elements: Vector[E],
      progress: LoadProgress,
      probeAll: Vector[E] => Either[StoreError, Unit],
      writer: (WriteAttempt, Vector[E]) => Either[StoreError, PassEnd]
  ): Either[StoreError, LedgerRun] =
    toWriting(permit, ledger, clock, start, fresh = true).flatMap { writing =>
      if (writing.state == LoadState.Loaded)
        Right(LedgerRun(0L, 0L, LoadState.Loaded, skipped = true))
      else {
        val scope = writing.embeddingModel
        val (w0, p0, r0) = (progress.written, progress.alreadyPresent, progress.rejected)
        val s0 = progress.rejectionSetsRecorded
        val e0 = progress.embedding
        def call = (progress.written - w0, progress.alreadyPresent - p0, progress.rejected - r0)
        def currentSet: Option[Long] =
          Option.when(progress.rejectionSetsRecorded > s0)(progress.lastRejectionSetSize)
        // SE-P8-01: this call's embedding counts (vector loads only), added once, by the one
        // transition that ends the call (loaded, documents-staged or failed).
        def embeddingCall: Option[EmbeddingTotals] =
          progress.embedding.filter(_ => scope.isDefined).map { now =>
            e0.fold(now)(b =>
              now.copy(
                embedded = now.embedded - b.embedded,
                putRetries = now.putRetries - b.putRetries,
                embedRetries = now.embedRetries - b.embedRetries
              )
            )
          }
        def total(e: LoadEntry) = {
          val (w, p, _) = call
          totals(e, w, p, currentSet).copy(embedding = embeddingCall)
        }
        // CR-P8-01 M1: the failed row records the error class and detail of the call (and, through
        // `total`, the rejection set the writer recorded before it returned the error).
        def markFailed(err: StoreError): Unit = {
          val _ = readEntry(permit, ledger, scope).flatMap { cur =>
            if (cur.state == LoadState.Writing)
              advance(
                permit,
                ledger,
                clock,
                cur,
                LoadState.Failed,
                total(cur).copy(failure = Some(LoadFailure.of(err, writing.version)))
              )
            else Right(cur)
          }
        }
        probeAll(elements) match {
          case Left(e) =>
            markFailed(e)
            Left(e)
          case Right(()) =>
            writer(WriteAttempt(writing.version, scope), elements) match {
              case Left(e) =>
                // Best effort: record the failure so a rerun resumes; the original error is returned.
                markFailed(e)
                Left(e)
              case Right(end) =>
                val target =
                  if (end == PassEnd.Staged) LoadState.DocumentsStaged else LoadState.Loaded
                def finish(e: LoadEntry, rounds: Int): Either[StoreError, LoadEntry] =
                  if (e.state == LoadState.Loaded || e.state == target) Right(e)
                  else if (rounds > MaxLedgerRounds)
                    Left(
                      StoreError
                        .Transient(s"${e.loadId}: ledger contended for $MaxLedgerRounds rounds")
                    )
                  else if (e.state == LoadState.Writing)
                    advance(permit, ledger, clock, e, target, total(e)).flatMap(
                      finish(_, rounds + 1)
                    )
                  else if (target == LoadState.DocumentsStaged)
                    Right(e) // moved by a concurrent loader: the next call decides again
                  else // failed by a concurrent loader after our writes: resume it to loaded
                    toWriting(permit, ledger, clock, e, fresh = false).flatMap(
                      finish(_, rounds + 1)
                    )
                finish(writing, 0).map { done =>
                  val (w, p, r) = call
                  LedgerRun(w, p, done.state, skipped = false, r)
                }
            }
        }
      }
    }
}
