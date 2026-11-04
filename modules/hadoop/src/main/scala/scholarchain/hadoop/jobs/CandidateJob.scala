package scholarchain.hadoop.jobs

import scala.collection.immutable.SortedMap
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*

import org.apache.hadoop.io.{NullWritable, Text}
import org.apache.hadoop.mapreduce.lib.output.MultipleOutputs
import org.apache.hadoop.mapreduce.{Mapper, Reducer}
import upickle.default.ReadWriter

import scholarchain.core.{
  BlockKey,
  Canonical,
  CanonicalPair,
  ExactId,
  Json,
  MatchCandidate,
  Provenance,
  SchemaVersions,
  SourceAuthorProfile,
  SourceType,
  ValidationError,
  WorkRecord
}
import scholarchain.entityresolution.{
  MatchFeatures,
  PreparedResearcher,
  PreparedWork,
  ResearcherEndpoint
}
import scholarchain.hadoop.SideOutputs
import scholarchain.hadoop.{
  BlockingEntity,
  CandidateSettings,
  Counters,
  Disposition,
  HadoopPipeline,
  JobParams,
  NamedOutputs,
  RejectReason,
  RejectSink,
  RunContext,
  StatLine
}

/**
 * One block member joined to its curated record (hadoop-internal, format
 * `scholarchain.hadoop.joined-member.v1`). `recordKind` is `work` (for a mention: the work that
 * holds it; for a work member: the work itself) or `profile`; `record` is the record's JSON as
 * written by the normalize stage, decoded again with the strict core decoder before use.
 */
final case class JoinedMember(
    schemaVersion: String,
    entity: String,
    memberId: String,
    source: String,
    blockId: String,
    blockingVersion: String,
    level: Int,
    blockKey: String,
    recordKind: String,
    record: String
) derives ReadWriter

object JoinedMember {
  val SchemaVersion = "scholarchain.hadoop.joined-member.v1"
  val Work = "work"
  val Profile = "profile"
}

/**
 * Deterministic candidate construction for one block (pure). Calls only the entity-resolution
 * feature functions (`MatchFeatures.prepare` once per member, then `researcher` or `work` per pair,
 * and the exact-identifier agreement function); no score, threshold, or decision. Same-work pairs
 * are kept (`context.same_work` records them).
 */
object CandidateFeatures {

  /** A prepared endpoint of either entity kind. */
  enum Prepared {
    case Researcher(p: PreparedResearcher)
    case Work(p: PreparedWork)

    def id: String = this match {
      case Researcher(p) => p.id
      case Work(p) => p.id
    }
    def source: SourceType = this match {
      case Researcher(p) => p.source
      case Work(p) => p.source
    }
  }

  private def errors(what: String, es: Vector[ValidationError]): String =
    s"$what: ${es.map(_.message).mkString("; ")}"

  /** Decodes the member's record (strict core decoder) and prepares its feature inputs. */
  def prepare(m: JoinedMember): Either[String, Prepared] =
    (BlockingEntity.fromTag(m.entity), m.recordKind) match {
      case (BlockingEntity.Work, JoinedMember.Work) =>
        Json
          .decode[WorkRecord](m.record)
          .left
          .map(errors(m.memberId, _))
          .map(w => Prepared.Work(MatchFeatures.prepare(w)))
      case (BlockingEntity.Researcher, JoinedMember.Work) =>
        Json.decode[WorkRecord](m.record).left.map(errors(m.memberId, _)).flatMap { w =>
          w.authors
            .find(_.mentionId == m.memberId)
            .toRight(s"${m.memberId} is not an author mention of ${w.workId}")
            .map(mention =>
              Prepared.Researcher(MatchFeatures.prepare(ResearcherEndpoint.OfMention(w, mention)))
            )
        }
      case (BlockingEntity.Researcher, JoinedMember.Profile) =>
        Json
          .decode[SourceAuthorProfile](m.record)
          .left
          .map(errors(m.memberId, _))
          .map(p => Prepared.Researcher(MatchFeatures.prepare(ResearcherEndpoint.OfProfile(p))))
      case (e, k) => Left(s"${m.memberId}: a ${e.tag} member cannot be joined to a $k record")
    }

  /** Features and exact-identifier agreements of one pair of the same entity kind. */
  def compare(
      x: Prepared,
      y: Prepared
  ): Either[String, (SortedMap[String, Double], Vector[ExactId])] =
    (x, y) match {
      case (Prepared.Researcher(a), Prepared.Researcher(b)) =>
        Right(
          (MatchFeatures.researcher(a, b), MatchFeatures.exactIdAgreements(a.exactIds, b.exactIds))
        )
      case (Prepared.Work(a), Prepared.Work(b)) =>
        Right((MatchFeatures.work(a, b), MatchFeatures.exactIdAgreements(a.exactIds, b.exactIds)))
      case _ => Left(s"${x.id} and ${y.id} are of different entity kinds")
    }

  /** Derived provenance of a candidate: the canonical pair is its source record ID. */
  def provenance(run: RunContext, pair: CanonicalPair): Provenance =
    Provenance(
      sourceDataset = SourceType.Derived,
      sourceRecordId = s"${pair.first}|${pair.second}",
      snapshot = run.snapshot,
      runId = run.runId,
      sourceSchemaVersion = SchemaVersions.CandidateBlock,
      policyVersion = Some(HadoopPipeline.TransformVersion),
      modelVersion = None,
      promptVersion = None
    )

  /**
   * Merges the copies of one candidate (same candidate ID: same canonical pair, versions, run) that
   * came from different blocks: the union of their block keys, sorted. Returns the merged candidate
   * and whether the copies disagreed in anything but their block keys (a conflict: features depend
   * only on the two records, so copies must be equal). On a conflict the copy with the smallest
   * JSON (without block keys) is kept, which is deterministic.
   */
  def merge(
      copies: Vector[MatchCandidate]
  ): Either[Vector[ValidationError], (MatchCandidate, Boolean)] = {
    val keyless = copies.map(c => c.copy(blockKeys = Vector.empty))
    val base = keyless
      .zip(copies)
      .minBy { case (k, _) => Json.write(k) }(using
        Ordering.fromLessThan[String]((a, b) => Canonical.compareUtf8(a, b) < 0)
      )
      ._2
    val conflict = keyless.distinct.size > 1
    MatchCandidate
      .build(
        base.entityKind,
        base.sourceIdA,
        base.sourceDatasetA,
        base.sourceIdB,
        base.sourceDatasetB,
        BlockKey.canonical(copies.flatMap(_.blockKeys)),
        base.blockingVersion,
        base.featureVersion,
        base.features,
        base.exactIdAgreements,
        base.provenance
      )
      .map(_ -> conflict)
  }
}

// ------------------------------------------------------------------ job 5a: join

/**
 * Join mapper over block memberships: key `recordKey`, value `M\t<membership JSON>`. INPUT counts
 * memberships (the candidate stage's input unit).
 */
final class MembershipJoinMapper extends Mapper[Text, Text, Text, Text] {
  override def map(key: Text, line: Text, ctx: Mapper[Text, Text, Text, Text]#Context): Unit = {
    val m = upickle.default.read[BlockMembership](line.toString)
    if (m.schemaVersion != BlockMembership.SchemaVersion)
      throw new IllegalStateException(s"unsupported block membership format ${m.schemaVersion}")
    Counters.inc2(ctx, Counters.Input, m.entity)
    ctx.write(new Text(m.recordKey), new Text(s"M\t${line.toString}"))
  }
}

/** Join mapper base over curated records: key record ID, value `<tag>\t<record JSON>`. */
abstract class RecordJoinMapper[R] extends Mapper[Text, Text, Text, Text] {
  protected def inputName: String
  protected def tag: String
  protected def decode(line: String): Either[Vector[ValidationError], R]
  protected def idOf(r: R): String

  private var mos: MultipleOutputs[NullWritable, Text] = null
  private var sink: RejectSink = null
  private var run: RunContext = null

  override def setup(ctx: Mapper[Text, Text, Text, Text]#Context): Unit = {
    run = DerivedRun.from(ctx.getConfiguration)
    mos = SideOutputs.open(ctx)
    sink = new RejectSink(mos, ctx, Stages.Candidates)
  }

  override def cleanup(ctx: Mapper[Text, Text, Text, Text]#Context): Unit =
    if (mos != null) mos.close()

  override def map(key: Text, line: Text, ctx: Mapper[Text, Text, Text, Text]#Context): Unit = {
    Counters.inc2(ctx, Counters.RecordsRead, inputName)
    decode(line.toString) match {
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
      case Right(r) => ctx.write(new Text(idOf(r)), new Text(s"$tag\t${line.toString}"))
    }
  }
}

final class WorkJoinMapper extends RecordJoinMapper[WorkRecord] {
  protected def inputName: String = "works"
  protected def tag: String = "W"
  protected def decode(line: String) = Json.decode[WorkRecord](line)
  protected def idOf(w: WorkRecord): String = w.workId
}

final class ProfileJoinMapper extends RecordJoinMapper[SourceAuthorProfile] {
  protected def inputName: String = "profiles"
  protected def tag: String = "P"
  protected def decode(line: String) = Json.decode[SourceAuthorProfile](line)
  protected def idOf(p: SourceAuthorProfile): String = p.profileId
}

/**
 * Join reducer (key: record ID). Receives at most one record and the memberships of its members
 * (for a work: at most one per author mention plus one for the work itself, so the buffer is
 * bounded by team size + 1). Emits one [[JoinedMember]] per membership to `joined` (sorted by block
 * ID, then member ID; ACCEPTED). A membership whose record is missing, or whose mention is not on
 * the work, is rejected `unresolved-member` (MISSING_KEY). Records without memberships emit
 * nothing.
 */
final class JoinReducer extends Reducer[Text, Text, NullWritable, Text] {
  private var mos: MultipleOutputs[NullWritable, Text] = null
  private var sink: RejectSink = null
  private var run: RunContext = null

  override def setup(ctx: Reducer[Text, Text, NullWritable, Text]#Context): Unit = {
    run = DerivedRun.from(ctx.getConfiguration)
    mos = SideOutputs.open(ctx)
    sink = new RejectSink(mos, ctx, Stages.Candidates)
  }

  override def reduce(
      key: Text,
      values: java.lang.Iterable[Text],
      ctx: Reducer[Text, Text, NullWritable, Text]#Context
  ): Unit = {
    val records = ArrayBuffer.empty[(String, String)]
    val memberships = ArrayBuffer.empty[BlockMembership]
    values.asScala.foreach { v =>
      val s = v.toString
      val tag = s.substring(0, s.indexOf('\t'))
      val body = s.substring(s.indexOf('\t') + 1)
      if (tag == "M") memberships += upickle.default.read[BlockMembership](body)
      else records += (tag -> body)
    }
    if (memberships.nonEmpty) {
      if (records.size > 1) Counters.inc2(ctx, Counters.Duplicate, "record", records.size - 1L)
      val record =
        records.toVector.sortWith((a, b) => Canonical.compareUtf8(a._2, b._2) < 0).headOption
      val mentionIds: Set[String] = record match {
        case Some(("W", json)) =>
          Json
            .decode[WorkRecord](json)
            .toOption
            .map(_.authors.map(_.mentionId).toSet)
            .getOrElse(Set.empty)
        case _ => Set.empty
      }
      memberships.toVector
        .sortWith { (a, b) =>
          val c = Canonical.compareUtf8(a.blockId, b.blockId)
          if (c != 0) c < 0 else Canonical.compareUtf8(a.memberId, b.memberId) < 0
        }
        .foreach { m =>
          val kind = record.map(_._1) match {
            case Some("W") => Some(JoinedMember.Work)
            case Some("P") => Some(JoinedMember.Profile)
            case _ => None
          }
          val resolved = (kind, BlockingEntity.fromTag(m.entity)) match {
            case (Some(JoinedMember.Work), BlockingEntity.Researcher) => mentionIds(m.memberId)
            case (Some(JoinedMember.Work), BlockingEntity.Work) => m.memberId == m.recordKey
            case (Some(JoinedMember.Profile), BlockingEntity.Researcher) =>
              m.memberId == m.recordKey
            case _ => false
          }
          if (!resolved)
            sink.write(
              RejectReason.UnresolvedMember,
              Disposition.Rejected,
              run,
              Some(m.memberId),
              s"${m.blockId}:${m.memberId}",
              Vector(
                s"${m.entity} member ${m.memberId} of block ${m.blockId} has no curated record " +
                  s"under ${m.recordKey}" + kind.fold("")(k => s" (found a $k record)")
              ),
              None
            )
          else {
            Counters.inc2(ctx, Counters.Accepted, m.entity)
            val joined = JoinedMember(
              JoinedMember.SchemaVersion,
              m.entity,
              m.memberId,
              m.source,
              m.blockId,
              m.blockingVersion,
              m.level,
              m.blockKey,
              kind.get,
              record.get._2
            )
            mos.write(
              NamedOutputs.Joined,
              NullWritable.get,
              new Text(upickle.default.write(joined)),
              "joined/part"
            )
          }
        }
    }
  }

  override def cleanup(ctx: Reducer[Text, Text, NullWritable, Text]#Context): Unit =
    if (mos != null) mos.close()
}

// ------------------------------------------------------------------ job 5b: pair features

/** Pair mapper over joined members: key block ID, value the joined member line. */
final class BlockPairMapper extends Mapper[Text, Text, Text, Text] {
  override def map(key: Text, line: Text, ctx: Mapper[Text, Text, Text, Text]#Context): Unit = {
    val m = upickle.default.read[JoinedMember](line.toString)
    if (m.schemaVersion != JoinedMember.SchemaVersion)
      throw new IllegalStateException(s"unsupported joined member format ${m.schemaVersion}")
    ctx.write(new Text(m.blockId), line)
  }
}

/**
 * Pair-feature reducer (key: block ID). A block has at most `maxBlockSize` members (enforced: a
 * larger group fails the task, because blocking guarantees the bound), so one call computes at most
 * maxBlockSize * (maxBlockSize - 1) / 2 pairs. Prepares each member once, then emits one
 * [[MatchCandidate]] per unordered member pair i < j (by member ID) with this block's
 * BlockKey(level, key) to `pairs` (CANDIDATE_PAIRS). A member whose record cannot be prepared is
 * rejected (SCHEMA_FAILURE) and takes part in no pair.
 */
final class PairFeatureReducer extends Reducer[Text, Text, NullWritable, Text] {
  private var settings: CandidateSettings = null
  private var mos: MultipleOutputs[NullWritable, Text] = null
  private var sink: RejectSink = null
  private var run: RunContext = null
  private var largest = StatLine("largestBlockPairs", 0L, "")

  override def setup(ctx: Reducer[Text, Text, NullWritable, Text]#Context): Unit = {
    settings = JobParams.orThrow(CandidateSettings.from(ctx.getConfiguration))
    run = DerivedRun.from(ctx.getConfiguration)
    mos = SideOutputs.open(ctx)
    sink = new RejectSink(mos, ctx, Stages.Candidates)
  }

  override def reduce(
      key: Text,
      values: java.lang.Iterable[Text],
      ctx: Reducer[Text, Text, NullWritable, Text]#Context
  ): Unit = {
    val buffer = ArrayBuffer.empty[JoinedMember]
    values.asScala.foreach { v =>
      buffer += upickle.default.read[JoinedMember](v.toString)
      if (buffer.size > settings.maxBlockSize)
        throw new IllegalStateException(
          s"block ${key.toString} has more than maxBlockSize=${settings.maxBlockSize} members"
        )
    }
    val members = buffer.toVector
      .groupBy(_.memberId)
      .values
      .map(_.head)
      .toVector
      .sortWith((a, b) => Canonical.compareUtf8(a.memberId, b.memberId) < 0)
    val prepared = members.flatMap { m =>
      CandidateFeatures.prepare(m) match {
        case Right(p) => Some(m -> p)
        case Left(why) =>
          sink.write(
            RejectReason.SchemaFailure,
            Disposition.Rejected,
            run,
            Some(m.memberId),
            s"${m.blockId}:${m.memberId}",
            Vector(why),
            None
          )
          None
      }
    }
    var emitted = 0L
    for {
      i <- prepared.indices
      j <- (i + 1) until prepared.size
    } {
      val (mx, px) = prepared(i)
      val (my, py) = prepared(j)
      val entity = BlockingEntity.fromTag(mx.entity)
      val built = for {
        cmp <- CandidateFeatures.compare(px, py).left.map(Vector(_))
        pair <- CanonicalPair.of(px.id, py.id).left.map(e => Vector(e.message))
        c <- MatchCandidate
          .build(
            entity.kind,
            px.id,
            px.source,
            py.id,
            py.source,
            Vector(BlockKey(mx.level, mx.blockKey)),
            mx.blockingVersion,
            settings.featureVersion,
            cmp._1,
            cmp._2,
            CandidateFeatures.provenance(run, pair)
          )
          .left
          .map(_.map(_.message))
      } yield c
      built match {
        case Left(why) =>
          throw new IllegalStateException(
            s"candidate ${px.id} / ${py.id} of block ${key.toString}: ${why.mkString("; ")}"
          )
        case Right(c) =>
          mos.write(NamedOutputs.Pairs, NullWritable.get, new Text(Json.write(c)), "pairs/part")
          Counters.inc2(ctx, Counters.CandidatePairs, entity.tag)
          emitted += 1
          if (emitted % 1000L == 0L) ctx.progress()
      }
    }
    largest = StatLine.better(largest, StatLine("largestBlockPairs", emitted, key.toString))
  }

  override def cleanup(ctx: Reducer[Text, Text, NullWritable, Text]#Context): Unit = {
    if (largest.value > 0)
      mos.write(
        NamedOutputs.Stats,
        NullWritable.get,
        new Text(StatLine.toJson(largest)),
        "stats/part"
      )
    if (mos != null) mos.close()
  }
}

// ------------------------------------------------------------------ job 5c: consolidation

/** Consolidation mapper over per-block candidates: key candidate ID, value candidate JSON. */
final class CandidateConsolidateMapper extends Mapper[Text, Text, Text, Text] {
  private var mos: MultipleOutputs[NullWritable, Text] = null
  private var sink: RejectSink = null
  private var run: RunContext = null

  override def setup(ctx: Mapper[Text, Text, Text, Text]#Context): Unit = {
    run = DerivedRun.from(ctx.getConfiguration)
    mos = SideOutputs.open(ctx)
    sink = new RejectSink(mos, ctx, Stages.Candidates)
  }

  override def cleanup(ctx: Mapper[Text, Text, Text, Text]#Context): Unit =
    if (mos != null) mos.close()

  override def map(key: Text, line: Text, ctx: Mapper[Text, Text, Text, Text]#Context): Unit =
    Json.decode[MatchCandidate](line.toString) match {
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
      case Right(c) => ctx.write(new Text(c.candidateId), line)
    }
}

/**
 * Consolidation reducer (key: candidate ID). Emits exactly ONE [[MatchCandidate]] per candidate ID
 * (= canonical pair, blocking and feature versions, run) to `<entity>/` with the union of every
 * BlockKey(level, key) the pair co-occurred in, sorted. Extra copies count CANDIDATE_MERGED; a
 * candidate with more than one block key counts CANDIDATE_MULTI_BLOCK; copies that disagree in
 * anything but their block keys are flagged CONFLICT. Stats: `maxBlockKeysPerCandidate`.
 */
final class CandidateConsolidateReducer extends Reducer[Text, Text, NullWritable, Text] {
  private var mos: MultipleOutputs[NullWritable, Text] = null
  private var sink: RejectSink = null
  private var run: RunContext = null
  private val stats = mutable.Map.empty[String, StatLine]

  override def setup(ctx: Reducer[Text, Text, NullWritable, Text]#Context): Unit = {
    run = DerivedRun.from(ctx.getConfiguration)
    mos = SideOutputs.open(ctx)
    sink = new RejectSink(mos, ctx, Stages.Candidates)
  }

  override def reduce(
      key: Text,
      values: java.lang.Iterable[Text],
      ctx: Reducer[Text, Text, NullWritable, Text]#Context
  ): Unit = {
    val copies = values.asScala.iterator
      .map(v => Json.decode[MatchCandidate](v.toString))
      .collect { case Right(c) => c }
      .toVector
    if (copies.nonEmpty) {
      Counters.inc(ctx, Counters.CandidateMerged, copies.size - 1L)
      CandidateFeatures.merge(copies) match {
        case Left(errors) =>
          throw new IllegalStateException(
            s"candidate ${key.toString}: ${errors.map(_.message).mkString("; ")}"
          )
        case Right((c, conflict)) =>
          if (conflict)
            sink.write(
              RejectReason.Conflict,
              Disposition.Flagged,
              run,
              Some(c.candidateId),
              c.candidateId,
              Vector(
                s"${copies.size} copies of ${c.candidateId} differ in features, agreements, or " +
                  "endpoint datasets; kept the copy with the smallest JSON"
              ),
              None
            )
          val entity = c.entityKind.tag
          mos.write(
            NamedOutputs.Candidates,
            NullWritable.get,
            new Text(Json.write(c)),
            s"$entity/part"
          )
          Counters.inc2(ctx, Counters.Emitted, s"$entity-candidates")
          if (c.blockKeys.size > 1) Counters.inc2(ctx, Counters.CandidateMultiBlock, entity)
          val metric = s"maxBlockKeysPerCandidate.$entity"
          val s = StatLine(metric, c.blockKeys.size.toLong, c.candidateId)
          stats.update(metric, stats.get(metric).fold(s)(StatLine.better(_, s)))
      }
    }
  }

  override def cleanup(ctx: Reducer[Text, Text, NullWritable, Text]#Context): Unit = {
    stats.toVector.sortBy(_._1).foreach { case (_, s) =>
      mos.write(NamedOutputs.Stats, NullWritable.get, new Text(StatLine.toJson(s)), "stats/part")
    }
    if (mos != null) mos.close()
  }
}
