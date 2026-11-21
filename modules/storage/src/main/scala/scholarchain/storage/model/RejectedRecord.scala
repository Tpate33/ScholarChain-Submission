package scholarchain.storage.model

import java.nio.charset.StandardCharsets

import upickle.default.ReadWriter
import upickle.implicits.allowUnknownKeys

import scholarchain.core.{EnumCodec, IdKind, Identifiers, StableId, TargetStore}

/** Why a loader rejected one record (README L1396). Frozen tags. */
enum RejectReason(val tag: String) {
  case MalformedRecord extends RejectReason("malformed-record")
  case MissingMetadata extends RejectReason("missing-metadata")
  case MalformedMetadata extends RejectReason("malformed-metadata")
  case OversizeMetadata extends RejectReason("oversize-metadata")
  case DuplicateKeyConflict extends RejectReason("duplicate-key-conflict")
  case DimensionMismatch extends RejectReason("dimension-mismatch")
  case PermanentWriteFailure extends RejectReason("permanent-write-failure")
  case RetriesExhausted extends RejectReason("retries-exhausted")

  /**
   * CR-P8-01 M1: the embedder refused this one text permanently (a `Permanent` error for the item
   * embedded alone, after its batch was split).
   */
  case EmbeddingFailure extends RejectReason("embedding-failure")
}
object RejectReason {
  def fromTag(tag: String): Option[RejectReason] = values.find(_.tag == tag)
  given ReadWriter[RejectReason] = EnumCodec.tagged("RejectReason", values.toSeq, _.tag)
}

/**
 * One rejected record (schema `scholarchain.storage.rejected-record.v2`). Written to RunLedger
 * (sort key `reject#<loadId>#<generation, 10 digits>#<recordKey>`) and as one JSON line of
 * `reports/loads/<loadHex>/rejects/part-<generation, 5 digits>.jsonl`. It never carries the
 * record's text: `detail` is a bounded diagnostic.
 *
 * CR-P7-11 m1 (v2): evidence is per write generation ([[scholarchain.storage.load.WriteAttempt]]),
 * so a rerun that rejects another set (a configuration change), or the same record with another
 * error detail, writes new keys and never collides with an earlier call's evidence. The CURRENT
 * evidence of a load is the highest generation that has a COMPLETE [[RejectionSet]] summary
 * (SE-P7-11, CR-P7-12 F2; SE-P8-03: `complete = true`); earlier generations are history (for
 * example a record rejected by a failed call and stored by the rerun that loaded), rows of a
 * generation without a summary are incomplete, and rows of a generation whose summary has
 * `complete = false` (a failed call) are the partial evidence of that call, never current.
 *
 * @param recordKey
 *   the record's stable ID, or `row:<sourcePath>#<row index>` when it has none. SE-P8-01 item 4
 *   (SE-P8-03 n-B wording): for a vector load this row is also the per-item failure evidence of one
 *   work item of the call's work list (the stored [[EmbeddingWorkManifest]] is the evidence of that
 *   list, not a queue the loader reads back): `attempts` is the number of attempts made for the
 *   item (its retry count is `attempts - 1`), `detail` holds the final error and `reason` its class
 *   (`retries-exhausted` after the last transient failure, `permanent-write-failure`,
 *   `embedding-failure`, `dimension-mismatch`, ...).
 *
 * @param attempts
 *   write attempts made (0 when refused before any write)
 * @param generation
 *   the write generation of the call that rejected it (>= 1)
 * @param embeddingModel
 *   SE-P8-01: the embedding model version of a vector load's ledger scope (its generations are that
 *   scope's, key `reject#<loadId>#model#<mv>#<generation>#<recordKey>`); absent for other loads
 *   (omitted from the JSON, so their rows are unchanged)
 */
@allowUnknownKeys(false)
final case class RejectedRecord(
    schemaVersion: String,
    runId: String,
    loadId: String,
    targetStore: TargetStore,
    targetRole: String,
    sourcePath: String,
    recordKey: String,
    reason: RejectReason,
    detail: String,
    attempts: Int,
    generation: Long,
    embeddingModel: Option[String] = None
) derives ReadWriter

object RejectedRecord {
  val SchemaVersion = "scholarchain.storage.rejected-record.v2"

  /** The largest generation a key holds (the rejects object part number has five digits). */
  val MaxGeneration = 99999L

  /** Bounds (bytes, UTF-8) of the free-text and key fields. */
  val MaxDetailBytes = 512
  val MaxRecordKeyBytes = 512

  private def bytes(s: String): Int = s.getBytes(StandardCharsets.UTF_8).length

  def recordKeyProblem(k: String): Option[String] =
    if (!Identifiers.isPrintableAscii(k)) Some("must be non-empty printable ASCII without spaces")
    else if (bytes(k) > MaxRecordKeyBytes) Some(s"longer than $MaxRecordKeyBytes bytes")
    else None

  /** Every problem of a rejected record; empty when valid. */
  def problems(r: RejectedRecord): Vector[String] =
    if (r == null) Vector("rejected record: missing")
    else
      Vector(
        Option.when(r.schemaVersion != SchemaVersion)(s"schemaVersion: expected $SchemaVersion"),
        StableId.validate(IdKind.Run, r.runId, "runId").left.toOption.map(_.message),
        StableId.validate(IdKind.StoreLoad, r.loadId, "loadId").left.toOption.map(_.message),
        Option.when(r.targetStore == null)("targetStore: missing"),
        Option.when(!StableId.isToken(r.targetRole))("targetRole: missing or not a token"),
        S3Layout.relativeProblem(r.sourcePath).map("sourcePath: " + _),
        recordKeyProblem(r.recordKey).map("recordKey: " + _),
        Option.when(r.reason == null)("reason: missing"),
        Option.when(
          r.detail == null || r.detail.isEmpty || r.detail.exists(_.isControl) ||
            bytes(r.detail) > MaxDetailBytes
        )(s"detail: missing, has control characters, or longer than $MaxDetailBytes bytes"),
        Option.when(r.attempts < 0)("attempts: negative"),
        Option.when(r.generation < 1 || r.generation > MaxGeneration)(
          s"generation: outside 1..$MaxGeneration"
        ),
        Option(r.embeddingModel).getOrElse(Some(null)).flatMap(EmbeddingModelVersion.problem)
      ).flatten

  /** The compact JSON line of the rejects object (no trailing newline). */
  def jsonLine(r: RejectedRecord): String = upickle.default.write(r)

  def parseLine(line: String): Either[StoreError, RejectedRecord] =
    try {
      val r = upickle.default.read[RejectedRecord](line)
      val p = problems(r)
      if (p.isEmpty) Right(r) else Left(StoreError.Corrupt(p.mkString("; ")))
    } catch {
      case e: Exception =>
        Left(StoreError.Corrupt(s"rejected record: undecodable (${e.getClass.getSimpleName})"))
    }
}

/**
 * SE-P7-11 (CR-P7-12 F2): the summary and commit marker of ONE write generation's rejection set
 * (RunLedger key `rejectset#<loadId>#<generation, 10 digits>`, access pattern RL-8). It is written
 * create-once AFTER that generation's rejected-record rows and its rejects object (possibly empty),
 * so its presence means the rows and the object of that generation are all written.
 *
 * SE-P8-03 (CR-P8-02 m-A): `complete` says whether the generation's call FINISHED its pass (it
 * ended `loaded` or `documents-staged`), so the set holds every rejection of the items that call
 * handled. A call that stopped with an error (CR-P8-01 M1: a put `Conflict`, a fatal put or
 * embedding error, more than `maxRejected` rejections, a failed text or manifest write) records the
 * rejections it reached with `complete = false`: evidence of that failed call only. The CURRENT
 * rejection evidence of a load is the summary with the highest generation among the COMPLETE ones
 * ([[RejectionSet.current]]); an incomplete summary, and rows of a generation without a summary,
 * are never current, and the ledger row's `rejected` count is set only from a complete set.
 *
 * @param count
 *   the number of rejected records of that generation (rows and object lines)
 * @param rejectsObjectSha256
 *   SHA-256 of that generation's rejects object bytes
 * @param complete
 *   SE-P8-03: true when the generation's call finished its pass; false for the partial set of a
 *   failed call. Default true, omitted from the JSON when true, so every existing (complete) row
 *   keeps its bytes
 */
@allowUnknownKeys(false)
final case class RejectionSet(
    schemaVersion: String,
    runId: String,
    loadId: String,
    targetStore: TargetStore,
    generation: Long,
    count: Long,
    rejectsObjectKey: String,
    rejectsObjectSha256: String,
    embeddingModel: Option[String] = None,
    complete: Boolean = true
) derives ReadWriter

object RejectionSet {
  val SchemaVersion = "scholarchain.storage.rejection-set.v1"

  /**
   * SE-P8-03: the CURRENT rejection set among the summaries of one load scope: the complete one
   * with the highest generation (None when no complete set exists). Incomplete sets (failed calls)
   * are never current.
   */
  def current(sets: Vector[RejectionSet]): Option[RejectionSet] =
    Option(sets)
      .getOrElse(Vector.empty)
      .filter(s => s != null && s.complete)
      .maxByOption(_.generation)

  def problems(s: RejectionSet): Vector[String] =
    if (s == null) Vector("rejection set: missing")
    else
      Vector(
        Option.when(s.schemaVersion != SchemaVersion)(s"schemaVersion: expected $SchemaVersion"),
        StableId.validate(IdKind.Run, s.runId, "runId").left.toOption.map(_.message),
        StableId.validate(IdKind.StoreLoad, s.loadId, "loadId").left.toOption.map(_.message),
        Option.when(s.targetStore == null)("targetStore: missing"),
        Option.when(s.generation < 1 || s.generation > RejectedRecord.MaxGeneration)(
          s"generation: outside 1..${RejectedRecord.MaxGeneration}"
        ),
        Option.when(s.count < 0)("count: negative"),
        Option.when(
          s.rejectsObjectKey == null || s.rejectsObjectKey.isEmpty ||
            s.rejectsObjectKey.exists(c => c.isWhitespace || c.isControl)
        )("rejectsObjectKey: missing or has whitespace"),
        Option.when(!scholarchain.core.Canonical.isHex64(s.rejectsObjectSha256))(
          "rejectsObjectSha256: expected 64 lowercase hex characters"
        ),
        Option(s.embeddingModel).getOrElse(Some(null)).flatMap(EmbeddingModelVersion.problem)
      ).flatten
}
