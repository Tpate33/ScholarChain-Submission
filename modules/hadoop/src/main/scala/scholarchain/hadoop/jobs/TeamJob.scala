package scholarchain.hadoop.jobs

import scala.collection.mutable
import scala.jdk.CollectionConverters.*

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.io.{NullWritable, Text}
import org.apache.hadoop.mapreduce.lib.output.MultipleOutputs
import org.apache.hadoop.mapreduce.{Mapper, Reducer}

import scholarchain.core.{
  Canonical,
  CoauthorPairCandidate,
  Json,
  PublicationTeam,
  SchemaVersions,
  SourceType,
  ValidationError,
  WorkRecord
}
import scholarchain.hadoop.{
  Counters,
  Disposition,
  JobParams,
  NamedOutputs,
  RejectReason,
  RejectSink,
  RunContext,
  SideOutputs,
  StatLine,
  TeamSettings
}

/**
 * Coauthor-pair expansion of one work (pure). A work with n author mentions yields exactly n(n-1)/2
 * unordered pairs, one per position pair i < j, in (i, j) order, always: there is no cap and no
 * suppression for large teams. Endpoints are author mention IDs (before identity resolution) stored
 * in canonical order; every pair carries both author positions (each following its endpoint), the
 * work ID, year, team size, and the work's source provenance. Pairs are built with the positional
 * core builder (`CoauthorPairCandidate.of(x, posX, y, posY, ...)`), whose cost is O(1) hashes per
 * pair, and are produced lazily, so a large team never materializes its pairs in memory.
 */
object PairExpansion {
  def pairCount(n: Int): Long = n.toLong * (n.toLong - 1L) / 2L

  def iterator(w: WorkRecord): Iterator[Either[Vector[ValidationError], CoauthorPairCandidate]] = {
    val ms = w.authors
    val n = ms.size
    for {
      i <- Iterator.range(0, n)
      j <- Iterator.range(i + 1, n)
    } yield CoauthorPairCandidate.of(
      ms(i).mentionId,
      i,
      ms(j).mentionId,
      j,
      w.workId,
      w.year,
      n,
      w.provenance
    )
  }

  /** The publication team of a work before identity resolution (all mentions unresolved). */
  def team(w: WorkRecord): PublicationTeam =
    PublicationTeam(
      schemaVersion = SchemaVersions.PublicationTeam,
      workId = w.workId,
      year = w.year,
      researcherIds = Vector.empty,
      teamSize = w.authors.size,
      unresolvedMentionIds = w.authors.map(_.mentionId),
      provenance = w.provenance
    )
}

/** Run context of records derived from curated outputs (rejects of later stages). */
object DerivedRun {
  def from(conf: Configuration): RunContext = {
    val dblp = JobParams.orThrow(RunContext.from(conf, SourceType.Dblp))
    dblp.copy(source = SourceType.Derived)
  }
}

/**
 * Teams mapper. Input: curated works (JSON lines). Output: key workId, value work JSON, for works
 * of a configured team source with at least one author.
 */
final class TeamMapper extends Mapper[Text, Text, Text, Text] {
  private var settings: TeamSettings = null
  private var mos: MultipleOutputs[NullWritable, Text] = null
  private var sink: RejectSink = null
  private var run: RunContext = null

  override def setup(ctx: Mapper[Text, Text, Text, Text]#Context): Unit = {
    settings = JobParams.orThrow(TeamSettings.from(ctx.getConfiguration))
    run = DerivedRun.from(ctx.getConfiguration)
    mos = SideOutputs.open(ctx)
    sink = new RejectSink(mos, ctx, Stages.Teams)
  }

  override def cleanup(ctx: Mapper[Text, Text, Text, Text]#Context): Unit =
    if (mos != null) mos.close()

  override def map(key: Text, line: Text, ctx: Mapper[Text, Text, Text, Text]#Context): Unit = {
    Counters.inc2(ctx, Counters.Input, "works")
    Json.decode[WorkRecord](line.toString) match {
      case Left(errors) =>
        sink.write(
          RejectReason.SchemaFailure,
          Disposition.Rejected,
          run,
          None,
          key.toString,
          errors.map(_.message),
          Some(line.toString)
        )
      case Right(w) =>
        if (!settings.sources.contains(w.source))
          Counters.inc2(ctx, Counters.TeamSourceExcluded, w.source.tag)
        else if (w.authors.isEmpty) Counters.inc2(ctx, Counters.TeamNoAuthors, w.source.tag)
        else {
          Counters.inc2(ctx, Counters.Accepted, w.source.tag)
          ctx.write(new Text(w.workId), line)
        }
    }
  }
}

/**
 * Teams reducer: one [[PublicationTeam]] per work to `teams`, and ALL its n(n-1)/2 pair candidates
 * to `pairs` (EMITTED_PAIR). A team above `scholarchain.team.maxSize` increments OVERSIZE_TEAM and
 * is written to the reject output with disposition `flagged` (team size and pair count in the
 * detail); its pairs are emitted like any other team's. Per-task maxima (`maxTeamSize`,
 * `maxPairExpansion` = n(n-1)/2 of the largest team) go to `stats`. Reports progress while
 * expanding, so a giant team does not hit the task timeout.
 */
final class TeamReducer extends Reducer[Text, Text, NullWritable, Text] {
  private var settings: TeamSettings = null
  private var mos: MultipleOutputs[NullWritable, Text] = null
  private var sink: RejectSink = null
  private var conf: Configuration = null
  private val runs = mutable.Map.empty[SourceType, RunContext]
  private var maxTeam = StatLine("maxTeamSize", 0L, "")
  private var maxPairs = StatLine("maxPairExpansion", 0L, "")

  override def setup(ctx: Reducer[Text, Text, NullWritable, Text]#Context): Unit = {
    conf = ctx.getConfiguration
    settings = JobParams.orThrow(TeamSettings.from(conf))
    mos = SideOutputs.open(ctx)
    sink = new RejectSink(mos, ctx, Stages.Teams)
  }

  private def runFor(s: SourceType): RunContext =
    runs.getOrElseUpdate(s, JobParams.orThrow(RunContext.from(conf, s)))

  override def reduce(
      key: Text,
      values: java.lang.Iterable[Text],
      ctx: Reducer[Text, Text, NullWritable, Text]#Context
  ): Unit = {
    val copies = values.asScala.iterator.map(_.toString).toVector
    if (copies.size > 1) Counters.inc2(ctx, Counters.Duplicate, "work", copies.size - 1L)
    val json = copies.reduce((a, b) => if (Canonical.compareUtf8(a, b) <= 0) a else b)
    Json.decode[WorkRecord](json) match {
      case Left(errors) =>
        sink.write(
          RejectReason.SchemaFailure,
          Disposition.Rejected,
          DerivedRun.from(conf),
          Some(key.toString),
          key.toString,
          errors.map(_.message),
          Some(json)
        )
      case Right(w) => expand(w, ctx)
    }
  }

  private def expand(w: WorkRecord, ctx: Reducer[Text, Text, NullWritable, Text]#Context): Unit = {
    val n = w.authors.size
    val pairs = PairExpansion.pairCount(n)
    val run = runFor(w.source)
    Json.encode(PairExpansion.team(w)) match {
      case Left(errors) =>
        sink.write(
          RejectReason.SchemaFailure,
          Disposition.Rejected,
          run,
          Some(w.sourceRecordId),
          w.workId,
          errors.map(_.message),
          None
        )
      case Right(teamJson) =>
        mos.write(NamedOutputs.Teams, NullWritable.get, new Text(teamJson), "teams/part")
        Counters.inc2(ctx, Counters.Emitted, NamedOutputs.Teams)
        maxTeam = StatLine.better(maxTeam, StatLine("maxTeamSize", n.toLong, w.workId))
        maxPairs = StatLine.better(maxPairs, StatLine("maxPairExpansion", pairs, w.workId))
        if (n > settings.maxSize)
          sink.write(
            RejectReason.OversizeTeam,
            Disposition.Flagged,
            run,
            Some(w.sourceRecordId),
            w.workId,
            Vector(
              s"teamSize=$n",
              s"pairs=$pairs",
              s"maxSize=${settings.maxSize}",
              "all pairs emitted"
            ),
            None
          )
        var emitted = 0L
        PairExpansion.iterator(w).foreach {
          case Right(p) =>
            mos.write(NamedOutputs.Pairs, NullWritable.get, new Text(Json.write(p)), "pairs/part")
            emitted += 1
            if (emitted % 10000L == 0L) ctx.progress()
          case Left(errors) =>
            throw new IllegalStateException(
              s"pair of ${w.workId} failed its contract: ${errors.map(_.message).mkString("; ")}"
            )
        }
        if (emitted != pairs)
          throw new IllegalStateException(
            s"${w.workId}: emitted $emitted pairs, expected n(n-1)/2 = $pairs"
          )
        Counters.inc(ctx, Counters.EmittedPair, emitted)
    }
  }

  override def cleanup(ctx: Reducer[Text, Text, NullWritable, Text]#Context): Unit = {
    Vector(maxTeam, maxPairs).filter(_.value > 0).foreach { s =>
      mos.write(NamedOutputs.Stats, NullWritable.get, new Text(StatLine.toJson(s)), "stats/part")
    }
    if (mos != null) mos.close()
  }
}
