package scholarchain.hadoop.jobs

import java.nio.charset.StandardCharsets

import scala.collection.mutable
import scala.jdk.CollectionConverters.*

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.io.{BytesWritable, NullWritable, Text}
import org.apache.hadoop.mapreduce.lib.output.MultipleOutputs
import org.apache.hadoop.mapreduce.{Mapper, Reducer}

import scholarchain.core.{Json, SourceType, WorkRecord}
import scholarchain.hadoop.SideOutputs
import scholarchain.hadoop.dblp.{DblpParser, DblpRawOutcome, DblpRawRecord}
import scholarchain.hadoop.io.WholeFileInputFormat
import scholarchain.hadoop.normalize.{
  CrossrefNormalizer,
  OpenAlexNormalizer,
  OrcidNormalizer,
  Outcome
}
import scholarchain.hadoop.{
  Counters,
  DblpRecordTypes,
  DblpSettings,
  Disposition,
  Envelope,
  JobParams,
  NamedOutputs,
  Problem,
  Rank,
  RecordKind,
  RejectReason,
  RejectSink,
  RunContext
}

/** Stage names (also output directory names). */
object Stages {
  val Normalize = "normalize"
  val ExactIds = "exact-ids"
  val Teams = "teams"
  val Blocking = "blocking"
  val Candidates = "candidates"
}

/**
 * Base of every normalize mapper. Input key: locator `<path>@<offset>` (or the file path). Map
 * output: key `<kind>\t<id>` (Text), value [[Envelope]] (Text). Located rejects and flags go to the
 * `rejects` named output from the map side.
 */
abstract class NormalizeMapper[V] extends Mapper[Text, V, Text, Text] {
  protected def source: SourceType

  /** Detail qualifier of the INPUT and ACCEPTED counters. */
  protected def inputName: String

  /** Normalizes one record; the excerpt is computed only for rejects. */
  protected def normalize(value: V): Outcome
  protected def excerpt(value: V): String

  protected def init(conf: Configuration): Unit = ()

  private var mos: MultipleOutputs[NullWritable, Text] = null
  private var sink: RejectSink = null
  protected var run: RunContext = null

  override def setup(ctx: Mapper[Text, V, Text, Text]#Context): Unit = {
    run = JobParams.orThrow(RunContext.from(ctx.getConfiguration, source))
    mos = SideOutputs.open(ctx)
    sink = new RejectSink(mos, ctx, Stages.Normalize)
    init(ctx.getConfiguration)
  }

  override def cleanup(ctx: Mapper[Text, V, Text, Text]#Context): Unit =
    if (mos != null) mos.close()

  private def emit(
      ctx: Mapper[Text, V, Text, Text]#Context,
      kind: RecordKind,
      id: String,
      rank: Int,
      json: String
  ): Unit = ctx.write(RecordKind.key(kind, id), Envelope.of(rank, json).encode)

  override def map(key: Text, value: V, ctx: Mapper[Text, V, Text, Text]#Context): Unit = {
    Counters.inc2(ctx, Counters.Input, inputName)
    val locator = key.toString
    normalize(value) match {
      case Outcome.Accepted(id, n) =>
        Counters.inc2(ctx, Counters.Accepted, inputName)
        n.works.foreach(w => emit(ctx, RecordKind.Work, w.workId, Rank.Primary, Json.write(w)))
        n.venues.foreach(v => emit(ctx, RecordKind.Venue, v.venueId, Rank.Reference, Json.write(v)))
        n.institutions.foreach { case (i, r) =>
          emit(ctx, RecordKind.Institution, i.institutionId, r, Json.write(i))
        }
        n.topics.foreach(t => emit(ctx, RecordKind.Topic, t.topicId, Rank.Reference, Json.write(t)))
        n.profiles.foreach { p =>
          emit(ctx, RecordKind.Profile, p.profileId, Rank.Primary, Json.write(p))
        }
        n.flags.foreach { p =>
          sink.write(p.reason, Disposition.Flagged, run, Some(id), locator, p.detail, None)
        }
      case Outcome.Rejected(id, problems) =>
        val reason = problems.headOption.map(_.reason).getOrElse(RejectReason.SchemaFailure)
        val detail = problems.flatMap(p => p.detail.map(d => s"${p.reason.tag}: $d"))
        sink.write(reason, Disposition.Rejected, run, id, locator, detail, Some(excerpt(value)))
      case Outcome.Filtered(_, why) => Counters.inc2(ctx, Counters.Filtered, why)
      case Outcome.Skipped(why) => Counters.inc2(ctx, Counters.SkippedNonPublication, why)
    }
  }
}

/** DBLP records (from [[scholarchain.hadoop.dblp.DblpInputFormat]]). */
final class DblpMapper extends NormalizeMapper[DblpRawRecord] {
  protected def source: SourceType = SourceType.Dblp
  protected def inputName: String = "dblp"
  private var settings: DblpSettings = null
  private var parser: DblpParser = null

  override protected def init(conf: Configuration): Unit = {
    settings = JobParams.orThrow(DblpSettings.from(conf))
    parser = new DblpParser(settings.encoding)
  }

  protected def excerpt(value: DblpRawRecord): String = parser.decode(value.bytes)

  protected def normalize(raw: DblpRawRecord): Outcome =
    DblpRawOutcome.of(raw, settings, parser, run)
}

abstract class JsonLineMapper extends NormalizeMapper[Text] {
  protected def excerpt(value: Text): String = value.toString
}

final class OpenAlexWorksMapper extends JsonLineMapper {
  protected def source: SourceType = SourceType.OpenAlex
  protected def inputName: String = "openalex-works"
  protected def normalize(v: Text): Outcome = OpenAlexNormalizer.workLine(v.toString, run)
}

final class OpenAlexAuthorsMapper extends JsonLineMapper {
  protected def source: SourceType = SourceType.OpenAlex
  protected def inputName: String = "openalex-authors"
  protected def normalize(v: Text): Outcome = OpenAlexNormalizer.authorLine(v.toString, run)
}

final class OpenAlexInstitutionsMapper extends JsonLineMapper {
  protected def source: SourceType = SourceType.OpenAlex
  protected def inputName: String = "openalex-institutions"
  protected def normalize(v: Text): Outcome = OpenAlexNormalizer.institutionLine(v.toString, run)
}

final class CrossrefWorksMapper extends JsonLineMapper {
  protected def source: SourceType = SourceType.Crossref
  protected def inputName: String = "crossref-works"
  protected def normalize(v: Text): Outcome = CrossrefNormalizer.workLine(v.toString, run)
}

/** ORCID summaries: one whole XML file per record. */
final class OrcidMapper extends NormalizeMapper[BytesWritable] {
  protected def source: SourceType = SourceType.Orcid
  protected def inputName: String = "orcid-records"
  private val normalizer = new OrcidNormalizer

  protected def excerpt(v: BytesWritable): String =
    new String(v.getBytes, 0, v.getLength, StandardCharsets.UTF_8)

  protected def normalize(v: BytesWritable): Outcome = {
    val bytes = v.copyBytes()
    if (java.util.Arrays.equals(bytes, WholeFileInputFormat.OversizeMarker))
      Outcome.Rejected(
        None,
        Vector(
          Problem.of(RejectReason.MalformedField, s"file exceeds ${JobParams.OrcidMaxRecordBytes}")
        )
      )
    else normalizer.record(bytes, run)
  }
}

/** Combiner: merges envelopes of one key (associative, commutative). */
final class EnvelopeCombiner extends Reducer[Text, Text, Text, Text] {
  override def reduce(
      key: Text,
      values: java.lang.Iterable[Text],
      ctx: Reducer[Text, Text, Text, Text]#Context
  ): Unit = {
    val merged = values.asScala.iterator.map(Envelope.decode).reduce(Envelope.merge)
    ctx.write(key, merged.encode)
  }
}

/**
 * Normalize reducer: one canonical record per `<kind>\t<id>`. Primary copies beyond the first count
 * as DUPLICATE; reference copies as MERGED_REFERENCE. Copies whose content (record without
 * provenance) differs are flagged CONFLICT; the kept record is the smallest (content hash, JSON),
 * which is deterministic. Works also emit their author mentions to `mentions` (the kept work is
 * read back through the strict core decoder, `Json.decode`).
 */
final class CanonicalRecordReducer extends Reducer[Text, Text, NullWritable, Text] {
  private var mos: MultipleOutputs[NullWritable, Text] = null
  private var sink: RejectSink = null
  private val runs = mutable.Map.empty[SourceType, RunContext]
  private var conf: Configuration = null

  override def setup(ctx: Reducer[Text, Text, NullWritable, Text]#Context): Unit = {
    conf = ctx.getConfiguration
    mos = SideOutputs.open(ctx)
    sink = new RejectSink(mos, ctx, Stages.Normalize)
  }

  override def cleanup(ctx: Reducer[Text, Text, NullWritable, Text]#Context): Unit =
    if (mos != null) mos.close()

  private def runFor(s: SourceType): RunContext =
    runs.getOrElseUpdate(s, JobParams.orThrow(RunContext.from(conf, s)))

  override def reduce(
      key: Text,
      values: java.lang.Iterable[Text],
      ctx: Reducer[Text, Text, NullWritable, Text]#Context
  ): Unit = {
    val merged = values.asScala.iterator.map(Envelope.decode).reduce(Envelope.merge)
    val (kind, id) = RecordKind.parse(key)
    if (merged.copies > 1) {
      val base = if (merged.rank == Rank.Primary) Counters.Duplicate else Counters.MergedReference
      Counters.inc2(ctx, base, kind.tag, merged.copies - 1)
    }
    if (merged.conflicting) {
      val prov = ujson.read(merged.json)("provenance")
      val source = SourceType.fromTag(prov("sourceDataset").str).getOrElse(SourceType.Dblp)
      sink.write(
        RejectReason.Conflict,
        Disposition.Flagged,
        runFor(source),
        Some(id),
        s"${kind.tag}:$id",
        Vector(
          s"${merged.copies} copies of ${kind.tag} $id (rank ${merged.rank}) differ in content; " +
            s"kept the copy with the smallest content hash ${merged.contentHash}"
        ),
        None
      )
    }
    def write(): Unit = {
      mos.write(kind.output, NullWritable.get, new Text(merged.json), s"${kind.output}/part")
      Counters.inc2(ctx, Counters.Emitted, kind.output)
    }
    if (kind == RecordKind.Work)
      Json.decode[WorkRecord](merged.json) match {
        case Left(errors) =>
          val source = WorkRecord.NamespaceOf
            .collectFirst { case (s, ns) if id.startsWith(s"work:$ns:") => s }
            .getOrElse(SourceType.Dblp)
          sink.write(
            RejectReason.SchemaFailure,
            Disposition.Rejected,
            runFor(source),
            Some(id),
            s"${kind.tag}:$id",
            errors.map(_.message),
            Some(merged.json)
          )
        case Right(work) =>
          write()
          work.authors.foreach { m =>
            mos.write(
              NamedOutputs.Mentions,
              NullWritable.get,
              new Text(Json.write(m)),
              "mentions/part"
            )
          }
          Counters.inc2(ctx, Counters.Emitted, NamedOutputs.Mentions, work.authors.size.toLong)
      }
    else write()
  }
}
