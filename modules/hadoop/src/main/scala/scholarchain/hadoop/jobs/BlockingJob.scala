package scholarchain.hadoop.jobs

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*

import org.apache.hadoop.io.{NullWritable, Text}
import org.apache.hadoop.mapreduce.lib.output.MultipleOutputs
import org.apache.hadoop.mapreduce.{Mapper, Reducer}
import upickle.default.ReadWriter

import scholarchain.core.{
  AuthorMention,
  BlockKey,
  Canonical,
  CandidateBlock,
  IdKind,
  Json,
  Provenance,
  SchemaVersions,
  SourceAuthorProfile,
  SourceType,
  StableId,
  ValidationError,
  WorkRecord
}
import scholarchain.entityresolution.EntityResolution
import scholarchain.hadoop.SideOutputs
import scholarchain.hadoop.{
  BlockOversizePolicy,
  BlockingEntity,
  BlockingSettings,
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
 * Blocking-key formulas. Their content is entity-resolution policy (owner:
 * entity-resolution-engineer); this module only runs them. Production runs use
 * [[EntityResolutionKeySource]]; tests may use fixtures for plumbing. Implementations must be pure,
 * deterministic, thread-confined, and have a public no-argument constructor.
 *
 * Keys are hierarchical: level 0 is the primary key; level `l + 1` is the fallback that refines an
 * oversize level-`l` block. A member may have fewer than [[levels]] keys (no further fallback).
 */
trait BlockingKeySource {

  /** Version of the key formulas (recorded on every block and candidate). Must be a token. */
  def version: String

  /** Maximum number of key levels. */
  def levels: Int

  /** Keys of one researcher mention (with its work as context), level 0 first. */
  def researcherKeys(work: WorkRecord, mention: AuthorMention): Vector[String]

  /** Keys of one source author profile (a researcher member), level 0 first. */
  def profileKeys(profile: SourceAuthorProfile): Vector[String]

  /** Keys of one work, level 0 first. */
  def workKeys(work: WorkRecord): Vector[String]
}

object BlockingKeySource {
  def load(className: String): BlockingKeySource =
    Class
      .forName(className, true, getClass.getClassLoader)
      .getDeclaredConstructor()
      .newInstance()
      .asInstanceOf[BlockingKeySource]
}

/**
 * The production key source: delegates every formula, the version, and the level count to
 * `modules/entity-resolution` ([[EntityResolution]]). No key policy lives in this module.
 */
final class EntityResolutionKeySource extends BlockingKeySource {
  def version: String = EntityResolution.blockingKeysVersion
  def levels: Int = EntityResolution.blockingLevels
  def researcherKeys(work: WorkRecord, mention: AuthorMention): Vector[String] =
    EntityResolution.researcherBlockingKeys(work, mention)
  def profileKeys(profile: SourceAuthorProfile): Vector[String] =
    EntityResolution.profileBlockingKeys(profile)
  def workKeys(work: WorkRecord): Vector[String] = EntityResolution.workBlockingKeys(work)
}

/**
 * A blocking member in the shuffle and in the spill output (hadoop-internal). `recordKey` is the ID
 * of the curated record that holds the member: the work ID for a mention or a work, the profile ID
 * for a profile.
 */
final case class BlockMember(
    entity: String,
    memberId: String,
    source: String,
    recordKey: String,
    keys: Vector[String]
) derives ReadWriter

/**
 * One member of one emitted block (hadoop-internal join index, format
 * `scholarchain.hadoop.block-membership.v1`), written next to the blocks so that the candidate
 * stage can join each member back to its record by `recordKey`.
 */
final case class BlockMembership(
    schemaVersion: String,
    entity: String,
    memberId: String,
    source: String,
    recordKey: String,
    blockId: String,
    blockingVersion: String,
    level: Int,
    blockKey: String
) derives ReadWriter

object BlockMembership {
  val SchemaVersion = "scholarchain.hadoop.block-membership.v1"
}

object BlockKeys {
  def shuffleKey(entity: BlockingEntity, level: Int, key: String): Text =
    new Text(s"${entity.tag}\t$level\t$key")

  def parse(k: Text): (BlockingEntity, Int, String) = {
    val s = k.toString
    val i = s.indexOf('\t')
    val j = s.indexOf('\t', i + 1)
    (BlockingEntity.fromTag(s.substring(0, i)), s.substring(i + 1, j).toInt, s.substring(j + 1))
  }

  /** The researcher members (every mention) and the work member of one work. */
  def workMembers(src: BlockingKeySource, w: WorkRecord): Vector[BlockMember] =
    w.authors.map(m =>
      BlockMember(
        BlockingEntity.Researcher.tag,
        m.mentionId,
        w.source.tag,
        w.workId,
        src.researcherKeys(w, m)
      )
    ) :+ BlockMember(BlockingEntity.Work.tag, w.workId, w.source.tag, w.workId, src.workKeys(w))

  /** The researcher member of one source author profile. */
  def profileMembers(src: BlockingKeySource, p: SourceAuthorProfile): Vector[BlockMember] =
    Vector(
      BlockMember(
        BlockingEntity.Researcher.tag,
        p.profileId,
        p.source.tag,
        p.profileId,
        src.profileKeys(p)
      )
    )

  /**
   * Derived provenance of a block: `scholarchain-derived`, the run's ID and DBLP snapshot, the
   * block label as source record ID, and the schema of the blocked records (work-record for work
   * blocks; author-mention for researcher blocks, or source-author-profile when every member is a
   * profile).
   */
  def provenance(
      run: RunContext,
      entity: BlockingEntity,
      label: String,
      memberIds: Vector[String]
  ): Provenance =
    Provenance(
      sourceDataset = SourceType.Derived,
      sourceRecordId = label,
      snapshot = run.snapshot,
      runId = run.runId,
      sourceSchemaVersion = entity match {
        case BlockingEntity.Work => SchemaVersions.WorkRecord
        case BlockingEntity.Researcher =>
          if (memberIds.forall(id => StableId.prefixKind(id).contains(IdKind.SourceAuthor)))
            SchemaVersions.SourceAuthorProfile
          else SchemaVersions.AuthorMention
      },
      policyVersion = Some(HadoopPipeline.TransformVersion),
      modelVersion = None,
      promptVersion = None
    )
}

/**
 * Round-0 mapper base. INPUT counts blocking members (one per mention, profile, or work), plus one
 * per input line that cannot be decoded (rejected): INPUT = ACCEPTED + flagged MISSING_KEY and
 * invalid-key members + REJECTED. Each accepted member is emitted under its level-0 key; records
 * read are counted in RECORDS_READ.
 */
abstract class BlockingSeedMapper[R] extends Mapper[Text, Text, Text, Text] {
  protected def inputName: String
  protected def decode(line: String): Either[Vector[ValidationError], R]
  protected def members(src: BlockingKeySource, r: R): Vector[BlockMember]

  private var source: BlockingKeySource = null
  private var mos: MultipleOutputs[NullWritable, Text] = null
  private var sink: RejectSink = null
  private var run: RunContext = null

  override def setup(ctx: Mapper[Text, Text, Text, Text]#Context): Unit = {
    val settings = JobParams.orThrow(BlockingSettings.from(ctx.getConfiguration))
    source = BlockingKeySource.load(settings.keySourceClass)
    run = DerivedRun.from(ctx.getConfiguration)
    mos = SideOutputs.open(ctx)
    sink = new RejectSink(mos, ctx, Stages.Blocking)
  }

  override def cleanup(ctx: Mapper[Text, Text, Text, Text]#Context): Unit =
    if (mos != null) mos.close()

  override def map(key: Text, line: Text, ctx: Mapper[Text, Text, Text, Text]#Context): Unit =
    decode(line.toString) match {
      case Left(errors) =>
        Counters.inc2(ctx, Counters.Input, s"undecodable-$inputName")
        sink.write(
          RejectReason.SchemaFailure,
          Disposition.Rejected,
          run,
          None,
          key.toString,
          errors.map(_.message),
          Some(line.toString)
        )
      case Right(r) =>
        Counters.inc2(ctx, Counters.RecordsRead, inputName)
        members(source, r).foreach { m =>
          Counters.inc2(ctx, Counters.Input, m.entity)
          if (m.keys.size > source.levels)
            throw new IllegalStateException(
              s"key source ${source.version} returned ${m.keys.size} keys for ${m.memberId}; " +
                s"it declares ${source.levels} levels"
            )
          val invalid = m.keys.zipWithIndex.flatMap { case (k, i) =>
            BlockKey.checkKey(s"keys[$i]", k)
          }
          if (m.keys.isEmpty)
            sink.write(
              RejectReason.MissingKey,
              Disposition.Flagged,
              run,
              Some(m.memberId),
              key.toString,
              Vector(s"${m.entity} ${m.memberId} has no blocking key; not blocked"),
              None
            )
          else if (invalid.nonEmpty)
            sink.write(
              RejectReason.InvalidBlockingKey,
              Disposition.Flagged,
              run,
              Some(m.memberId),
              key.toString,
              invalid.map(e => s"${m.entity} ${m.memberId}: ${e.message}; not blocked"),
              None
            )
          else {
            Counters.inc2(ctx, Counters.Accepted, m.entity)
            ctx.write(
              BlockKeys.shuffleKey(BlockingEntity.fromTag(m.entity), 0, m.keys(0)),
              new Text(upickle.default.write(m))
            )
          }
        }
    }
}

/** Round 0 over curated works: every mention (researcher) and the work itself (work). */
final class BlockingWorkSeedMapper extends BlockingSeedMapper[WorkRecord] {
  protected def inputName: String = "works"
  protected def decode(line: String) = Json.decode[WorkRecord](line)
  protected def members(src: BlockingKeySource, w: WorkRecord) = BlockKeys.workMembers(src, w)
}

/** Round 0 over curated source author profiles (researcher members). */
final class BlockingProfileSeedMapper extends BlockingSeedMapper[SourceAuthorProfile] {
  protected def inputName: String = "profiles"
  protected def decode(line: String) = Json.decode[SourceAuthorProfile](line)
  protected def members(src: BlockingKeySource, p: SourceAuthorProfile) =
    BlockKeys.profileMembers(src, p)
}

/**
 * Round-r mapper (r >= 1) over the previous round's spill: emits members under their level-r key.
 * Re-read members are counted in REFINE_INPUT (never in INPUT).
 */
final class BlockingSpillMapper extends Mapper[Text, Text, Text, Text] {
  private var round = 0

  override def setup(ctx: Mapper[Text, Text, Text, Text]#Context): Unit =
    round = JobParams.orThrow(JobParams.int(ctx.getConfiguration, JobParams.BlockingRound, 1))

  override def map(key: Text, line: Text, ctx: Mapper[Text, Text, Text, Text]#Context): Unit = {
    val m = upickle.default.read[BlockMember](line.toString)
    Counters.inc2(ctx, Counters.RefineInput, s"${m.entity}.round-$round")
    ctx.write(BlockKeys.shuffleKey(BlockingEntity.fromTag(m.entity), round, m.keys(round)), line)
  }
}

/**
 * Block reducer (both entities; key `<entity>\t<level>\t<key>`). Holds at most `maxBlockSize + 1`
 * members in memory. A group of at most `maxBlockSize` members becomes one core [[CandidateBlock]]
 * (`blocks/<entity>/`, BLOCK_EMITTED) plus one [[BlockMembership]] per member (`members/<entity>/`,
 * sorted by member ID). A larger group increments OVERSIZE_BLOCK and is flagged; its members that
 * have a next-level key go to `spill` for the next round (BLOCK_MEMBERS_REFINED), the others are
 * dropped (BLOCK_MEMBERS_DROPPED) under the `drop` policy or fail the job under `fail`. A larger
 * reducer is never the strategy. Spill lines are in arrival order (not sorted): spill is a
 * between-round intermediate, not a curated output.
 */
final class BlockReducer extends Reducer[Text, Text, NullWritable, Text] {
  private var settings: BlockingSettings = null
  private var source: BlockingKeySource = null
  private var mos: MultipleOutputs[NullWritable, Text] = null
  private var sink: RejectSink = null
  private var run: RunContext = null
  private val stats = mutable.Map.empty[String, StatLine]

  override def setup(ctx: Reducer[Text, Text, NullWritable, Text]#Context): Unit = {
    settings = JobParams.orThrow(BlockingSettings.from(ctx.getConfiguration))
    source = BlockingKeySource.load(settings.keySourceClass)
    run = DerivedRun.from(ctx.getConfiguration)
    mos = SideOutputs.open(ctx)
    sink = new RejectSink(mos, ctx, Stages.Blocking)
  }

  private def stat(metric: String, value: Long, id: String): Unit = {
    val s = StatLine(metric, value, id)
    stats.update(metric, stats.get(metric).fold(s)(StatLine.better(_, s)))
  }

  override def reduce(
      key: Text,
      values: java.lang.Iterable[Text],
      ctx: Reducer[Text, Text, NullWritable, Text]#Context
  ): Unit = {
    val (entity, level, blockKey) = BlockKeys.parse(key)
    val label = s"${entity.tag}:$level:$blockKey"
    val buffer = ArrayBuffer.empty[String]
    var size = 0L
    var refined = 0L
    var dropped = 0L
    var spilling = false

    def spill(json: String): Unit = {
      val m = upickle.default.read[BlockMember](json)
      if (m.keys.size > level + 1) {
        mos.write(NamedOutputs.Spill, NullWritable.get, new Text(json), "spill/part")
        refined += 1
      } else dropped += 1
    }

    values.asScala.foreach { v =>
      size += 1
      if (spilling) spill(v.toString)
      else {
        buffer += v.toString
        if (buffer.size > settings.maxBlockSize) {
          spilling = true
          buffer.foreach(spill)
          buffer.clear()
        }
      }
    }
    stat(s"largestGroup.${entity.tag}", size, label)

    if (!spilling) emitBlock(entity, level, blockKey, label, ctx, buffer.toVector)
    else {
      Counters.inc2(ctx, Counters.BlockMembersRefined, entity.tag, refined)
      Counters.inc2(ctx, Counters.BlockMembersDropped, entity.tag, dropped)
      sink.write(
        RejectReason.OversizeBlock,
        Disposition.Flagged,
        run,
        None,
        label,
        Vector(
          s"entity=${entity.tag}",
          s"level=$level",
          s"size=$size",
          s"maxBlockSize=${settings.maxBlockSize}",
          s"refinedToNextLevel=$refined",
          s"dropped=$dropped",
          s"policy=${settings.oversizePolicy.tag}"
        ),
        None
      )
      if (dropped > 0 && settings.oversizePolicy == BlockOversizePolicy.Fail)
        throw new IllegalStateException(
          s"block $label has $size members (> ${settings.maxBlockSize}) and $dropped of them " +
            "have no fallback key; oversize policy is 'fail'"
        )
    }
  }

  private def emitBlock(
      entity: BlockingEntity,
      level: Int,
      blockKey: String,
      label: String,
      ctx: Reducer[Text, Text, NullWritable, Text]#Context,
      jsons: Vector[String]
  ): Unit = {
    val members = jsons
      .map(upickle.default.read[BlockMember](_))
      .groupBy(_.memberId)
      .values
      .map(_.head)
      .toVector
      .sortWith((a, b) => Canonical.compareUtf8(a.memberId, b.memberId) < 0)
    val ids = members.map(_.memberId)
    val sources = members.map(m =>
      SourceType
        .fromTag(m.source)
        .getOrElse(throw new IllegalStateException(s"unknown member source '${m.source}'"))
    )
    CandidateBlock.build(
      entity.kind,
      source.version,
      level,
      blockKey,
      settings.maxBlockSize,
      ids,
      sources,
      BlockKeys.provenance(run, entity, label, ids)
    ) match {
      case Left(errors) =>
        throw new IllegalStateException(
          s"block $label failed its contract: ${errors.map(_.message).mkString("; ")}"
        )
      case Right(block) =>
        mos.write(
          NamedOutputs.Blocks,
          NullWritable.get,
          new Text(Json.write(block)),
          s"blocks/${entity.tag}/part"
        )
        members.foreach { m =>
          val membership = BlockMembership(
            BlockMembership.SchemaVersion,
            entity.tag,
            m.memberId,
            m.source,
            m.recordKey,
            block.blockId,
            block.blockingVersion,
            level,
            blockKey
          )
          mos.write(
            NamedOutputs.Members,
            NullWritable.get,
            new Text(upickle.default.write(membership)),
            s"members/${entity.tag}/part"
          )
        }
        Counters.inc2(ctx, Counters.BlockEmitted, s"${entity.tag}.level-$level")
        stat(s"largestBlock.${entity.tag}", ids.size.toLong, label)
    }
  }

  override def cleanup(ctx: Reducer[Text, Text, NullWritable, Text]#Context): Unit = {
    stats.toVector.sortBy(_._1).foreach { case (_, s) =>
      mos.write(NamedOutputs.Stats, NullWritable.get, new Text(StatLine.toJson(s)), "stats/part")
    }
    if (mos != null) mos.close()
  }
}
