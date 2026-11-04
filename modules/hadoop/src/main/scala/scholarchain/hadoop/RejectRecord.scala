package scholarchain.hadoop

import org.apache.hadoop.io.{NullWritable, Text}
import org.apache.hadoop.mapreduce.TaskInputOutputContext
import org.apache.hadoop.mapreduce.lib.output.MultipleOutputs
import upickle.default.ReadWriter

import scholarchain.core.Canonical

/** Why a record was rejected or flagged; `counter` is the required counter it increments. */
enum RejectReason(val tag: String, val counter: String) {
  case MalformedXml extends RejectReason("malformed-xml", Counters.SchemaFailure)
  case MalformedJson extends RejectReason("malformed-json", Counters.SchemaFailure)
  case UnterminatedRecord extends RejectReason("unterminated-record", Counters.SchemaFailure)
  case MissingKey extends RejectReason("missing-key", Counters.MissingKey)
  case MissingField extends RejectReason("missing-field", Counters.SchemaFailure)
  case MalformedField extends RejectReason("malformed-field", Counters.SchemaFailure)
  case MalformedIdentifier extends RejectReason("malformed-identifier", Counters.SchemaFailure)
  case SchemaFailure extends RejectReason("schema-failure", Counters.SchemaFailure)
  case Conflict extends RejectReason("conflict", Counters.Conflict)
  case OversizeTeam extends RejectReason("oversize-team", Counters.OversizeTeam)
  case OversizeBlock extends RejectReason("oversize-block", Counters.OversizeBlock)

  /** A blocking key that is not valid block-key text (blank or with control characters). */
  case InvalidBlockingKey extends RejectReason("invalid-blocking-key", Counters.SchemaFailure)

  /** A block member whose record (work or profile) is absent from the run's curated outputs. */
  case UnresolvedMember extends RejectReason("unresolved-member", Counters.MissingKey)
}

/** `rejected`: the record is not in any curated output. `flagged`: it is kept, and reported. */
enum Disposition(val tag: String) {
  case Rejected extends Disposition("rejected")
  case Flagged extends Disposition("flagged")
}

/** A problem found by a pure mapping function, before it is located in the input. */
final case class Problem(reason: RejectReason, detail: Vector[String])

object Problem {
  def of(reason: RejectReason, detail: String*): Problem = Problem(reason, detail.toVector)
}

/**
 * Versioned reject/flag record (format `scholarchain.hadoop.reject.v1`). Owned by hadoop-engineer
 * (rejected-record formats are hadoop territory). Written as one JSON object per line under
 * `<stage>/rejects/`. Never only logged.
 *
 * @param rejectId
 *   `reject:` + SHA-256 of the netstrings of (format version, stage, reason, disposition, source,
 *   locator, source record ID or "", detail...): deterministic for deterministic input
 * @param locator
 *   input file and byte offset (`<path>@<offset>`), the input file alone, or the record key for
 *   reducer-side findings
 * @param excerpt
 *   at most `scholarchain.reject.maxExcerptChars` characters of the raw record
 */
final case class RejectRecord(
    schemaVersion: String,
    rejectId: String,
    stage: String,
    reason: String,
    disposition: String,
    sourceDataset: String,
    sourceRecordId: Option[String],
    snapshot: String,
    runId: String,
    locator: String,
    detail: Vector[String],
    excerpt: Option[String]
) derives ReadWriter

object RejectRecord {
  val SchemaVersion = "scholarchain.hadoop.reject.v1"

  def build(
      stage: String,
      reason: RejectReason,
      disposition: Disposition,
      run: RunContext,
      sourceRecordId: Option[String],
      locator: String,
      detail: Vector[String],
      rawExcerpt: Option[String]
  ): RejectRecord = {
    val id = "reject:" + Canonical.hashFields(
      (Vector(
        SchemaVersion,
        stage,
        reason.tag,
        disposition.tag,
        run.source.tag,
        locator,
        sourceRecordId.getOrElse("")
      ) ++ detail)*
    )
    RejectRecord(
      SchemaVersion,
      id,
      stage,
      reason.tag,
      disposition.tag,
      run.source.tag,
      sourceRecordId,
      run.snapshot,
      run.runId,
      locator,
      detail,
      rawExcerpt.map(truncate(_, run.maxExcerptChars)).filter(_.nonEmpty)
    )
  }

  /** At most `max` code points (never splits a surrogate pair). */
  def truncate(s: String, max: Int): String =
    if (s.codePointCount(0, s.length) <= max) s
    else s.substring(0, s.offsetByCodePoints(0, max))

  def toJson(r: RejectRecord): String = upickle.default.write(r)
  def fromJson(s: String): RejectRecord = upickle.default.read[RejectRecord](s)
}

/**
 * Writes reject records to the `rejects` named output and increments counters. One instance per
 * task (task-local, never shared).
 */
final class RejectSink(
    mos: MultipleOutputs[NullWritable, Text],
    ctx: TaskInputOutputContext[?, ?, ?, ?],
    stage: String
) {

  def write(
      reason: RejectReason,
      disposition: Disposition,
      run: RunContext,
      sourceRecordId: Option[String],
      locator: String,
      detail: Vector[String],
      rawExcerpt: Option[String]
  ): Unit = {
    val r = RejectRecord.build(
      stage,
      reason,
      disposition,
      run,
      sourceRecordId,
      locator,
      detail,
      rawExcerpt
    )
    mos.write(
      NamedOutputs.Rejects,
      NullWritable.get,
      new Text(RejectRecord.toJson(r)),
      "rejects/part"
    )
    disposition match {
      case Disposition.Rejected => Counters.inc2(ctx, Counters.Rejected, reason.tag)
      case Disposition.Flagged => Counters.inc2(ctx, Counters.Flagged, reason.tag)
    }
    Counters.inc(ctx, reason.counter)
  }
}

/**
 * Opens the side outputs of a task. Named outputs are declared as `NullWritable`/`Text` and are
 * written with the generic `write(name, key, value, base)`, which does not use the task's own
 * output types; the cast only widens the map-side context (`Text`/`Text` map output) to that
 * declaration.
 */
object SideOutputs {
  def open(ctx: TaskInputOutputContext[?, ?, ?, ?]): MultipleOutputs[NullWritable, Text] =
    new MultipleOutputs[NullWritable, Text](
      ctx.asInstanceOf[TaskInputOutputContext[?, ?, NullWritable, Text]]
    )
}

/** Named outputs (MultipleOutputs requires alphanumeric names). */
object NamedOutputs {
  val Works = "works"
  val Mentions = "mentions"
  val Venues = "venues"
  val Institutions = "institutions"
  val Topics = "topics"
  val Profiles = "profiles"
  val Links = "links"
  val Teams = "teams"
  val Pairs = "pairs"
  val Blocks = "blocks"
  val Members = "members"
  val Spill = "spill"
  val Joined = "joined"
  val Candidates = "candidates"
  val Rejects = "rejects"
  val Stats = "stats"
}
