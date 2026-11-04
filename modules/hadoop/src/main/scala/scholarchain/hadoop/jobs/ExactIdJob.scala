package scholarchain.hadoop.jobs

import scala.collection.mutable
import scala.jdk.CollectionConverters.*

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.io.{NullWritable, Text}
import org.apache.hadoop.mapreduce.lib.output.MultipleOutputs
import org.apache.hadoop.mapreduce.{Mapper, Reducer}

import scholarchain.core.{
  AssertionSource,
  Canonical,
  ExactId,
  ExactIdScheme,
  ExternalId,
  ExternalIdScheme,
  IdKind,
  IdentityLink,
  Json,
  MatchEntityKind,
  Provenance,
  SourceAuthorId,
  SourceAuthorProfile,
  SourceType,
  StableId,
  WorkRecord
}
import scholarchain.hadoop.SideOutputs
import scholarchain.hadoop.{
  Counters,
  Disposition,
  JobParams,
  NamedOutputs,
  Problem,
  RejectReason,
  RejectSink,
  RunContext,
  StatLine
}

/**
 * Exact-identifier assertions of normalized records (pure), each as an [[IdentityLink]] ("identity-
 * link inputs"). Identifiers are already canonical (normalization happened in the normalize stage)
 * and are rebuilt through `ExactId.of`, which validates them.
 *
 *   - Work links (entity = the work, no workId): the work's own source ID (`dblp-key`,
 *     `openalex-work`; Crossref's own ID is its DOI), `doi`, `arxiv`, and every `additionalIds`
 *     entry (`mag`, `scopus-eid`, further DOIs). ORCID works carry their `assertionSource`.
 *   - Mention links (entity = the author mention, workId = its work): the mention's `orcid` and its
 *     `sourceAuthorId` (`openalex-author` for an OpenAlex A-ID, `dblp-pid`).
 *   - ORCID claim links (entity = the holder's profile `source-author:orcid:<iD>`, workId = the
 *     claimed ORCID work): one per ORCID work, with the work summary's `assertionSource`.
 *   - Profile links (entity = the profile, no workId): the profile's own source ID, its `orcid`,
 *     and its author `externalIds`.
 *
 * Shuffle key `<scheme tag>\t<value>`; value [[Entry]].
 */
object ExactIdKeys {

  /** One assertion: `source`, asserting entity, its work (or ""), and the link JSON. */
  final case class Entry(source: String, entityId: String, workId: String, link: String) {
    def id: String = s"$source\t$entityId\t$workId"
    def encode: String = s"$id\t$link"
  }

  object Entry {
    def decode(s: String): Entry = s.split("\t", 4) match {
      case Array(a, b, c, d) => Entry(a, b, c, d)
      case _ => throw new IllegalStateException(s"malformed exact-id entry '$s'")
    }
  }

  def key(id: ExactId): String = s"${id.scheme.tag}\t${id.value}"

  private def link(
      id: ExactId,
      kind: MatchEntityKind,
      entityId: String,
      workId: Option[String],
      assertion: Option[AssertionSource],
      prov: Provenance
  ): Either[Problem, (String, Entry)] =
    IdentityLink.build(id, kind, entityId, workId, assertion, prov) match {
      case Left(errors) =>
        Left(
          Problem(
            RejectReason.SchemaFailure,
            errors.map(e =>
              s"identity link ${id.scheme.tag} ${id.value} of $entityId: ${e.message}"
            )
          )
        )
      case Right(l) =>
        Right(
          key(id) -> Entry(prov.sourceDataset.tag, entityId, workId.getOrElse(""), Json.write(l))
        )
    }

  private def exact(scheme: ExactIdScheme, value: String, where: String): Either[Problem, ExactId] =
    ExactId
      .of(scheme, value)
      .left
      .map(e => Problem.of(RejectReason.MalformedIdentifier, s"$where: ${e.message}"))

  private def researcherId(e: ExternalId, where: String): Option[Either[Problem, ExactId]] =
    e.scheme match {
      case ExternalIdScheme.Orcid => Some(exact(ExactIdScheme.Orcid, e.value, where))
      case ExternalIdScheme.OpenAlex => Some(exact(ExactIdScheme.OpenAlexAuthor, e.value, where))
      case ExternalIdScheme.DblpPid => Some(exact(ExactIdScheme.DblpPid, e.value, where))
      case _ => None
    }

  /** Links asserted by one work (work, mention, and ORCID claim links). */
  def ofWork(w: WorkRecord): Vector[Either[Problem, (String, Entry)]] = {
    val workLinks = ExactId
      .canonical(WorkRecord.dedicatedIds(w).toVector ++ w.additionalIds)
      .map(id => link(id, MatchEntityKind.Work, w.workId, None, w.assertionSource, w.provenance))
    val mentionLinks = w.authors.flatMap { m =>
      val ids = m.orcid.toVector.map(exact(ExactIdScheme.Orcid, _, s"${m.mentionId} orcid")) ++
        m.sourceAuthorId.toVector.flatMap(researcherId(_, s"${m.mentionId} sourceAuthorId"))
      ids.map(
        _.flatMap(id =>
          link(id, MatchEntityKind.Researcher, m.mentionId, Some(w.workId), None, m.provenance)
        )
      )
    }
    val claimLinks =
      if (w.source != SourceType.Orcid) Vector.empty
      else {
        val holder = w.workId.stripPrefix("work:orcid-work:").takeWhile(_ != '/')
        Vector(for {
          id <- exact(ExactIdScheme.Orcid, holder, s"${w.workId} holder")
          profile <- SourceAuthorId
            .orcid(holder)
            .left
            .map(e => Problem.of(RejectReason.MalformedIdentifier, e.message))
          l <- link(
            id,
            MatchEntityKind.Researcher,
            profile,
            Some(w.workId),
            w.assertionSource,
            w.provenance
          )
        } yield l)
      }
    workLinks ++ mentionLinks ++ claimLinks
  }

  /** Links asserted by one source author profile. */
  def ofProfile(p: SourceAuthorProfile): Vector[Either[Problem, (String, Entry)]] = {
    val own: Vector[Either[Problem, ExactId]] =
      SourceAuthorId.parts(p.profileId).toVector.flatMap {
        case ("openalex", v) => Vector(exact(ExactIdScheme.OpenAlexAuthor, v, p.profileId))
        case ("orcid", v) => Vector(exact(ExactIdScheme.Orcid, v, p.profileId))
        case ("dblp-pid", v) => Vector(exact(ExactIdScheme.DblpPid, v, p.profileId))
        case _ => Vector.empty
      }
    val asserted = p.orcid.toVector.map(exact(ExactIdScheme.Orcid, _, s"${p.profileId} orcid")) ++
      p.externalIds.flatMap(researcherId(_, s"${p.profileId} externalIds"))
    val ids = own ++ asserted
    val good = ExactId.canonical(ids.collect { case Right(id) => id })
    ids.collect { case Left(problem) => Left(problem) } ++
      good.map(id => link(id, MatchEntityKind.Researcher, p.profileId, None, None, p.provenance))
  }

  /**
   * Sources whose work IDs identify a publication. Two works of one such source with the same work
   * identifier are a conflict. ORCID work IDs are per-person claims, so many ORCID works sharing a
   * DOI or Scopus EID (coauthors' claims) is expected and is not a conflict.
   */
  val PublicationScopedSources: Set[String] =
    Set(SourceType.Dblp, SourceType.OpenAlex, SourceType.Crossref).map(_.tag)

  private def isProfile(id: String): Boolean =
    StableId.prefixKind(id).contains(IdKind.SourceAuthor)

  /** Conflict descriptions of one key group (empty when consistent). */
  def conflicts(scheme: String, value: String, entries: Vector[Entry]): Vector[(String, String)] =
    ExactIdScheme.fromTag(scheme).map(_.entityKind) match {
      case Some(MatchEntityKind.Work) =>
        entries
          .groupBy(_.source)
          .toVector
          .sortBy(_._1)
          .collect {
            case (src, es)
                if PublicationScopedSources(src) && es.map(_.entityId).distinct.size > 1 =>
              src -> s"$scheme $value is asserted by ${es.map(_.entityId).distinct.size} distinct $src works: ${es.map(_.entityId).distinct.sorted.mkString(", ")}"
          }
      case _ =>
        val (profiles, mentions) = entries.partition(e => isProfile(e.entityId))
        val mentionConflicts = mentions
          .groupBy(e => (e.source, e.workId))
          .toVector
          .sortBy(_._1)
          .collect {
            case ((src, work), es) if es.map(_.entityId).distinct.size > 1 =>
              src -> s"$scheme $value is asserted by ${es.map(_.entityId).distinct.size} author mentions of one work $work"
          }
        val profileConflicts = profiles
          .groupBy(_.source)
          .toVector
          .sortBy(_._1)
          .collect {
            case (src, es) if es.map(_.entityId).distinct.size > 1 =>
              src -> s"$scheme $value is asserted by ${es.map(_.entityId).distinct.size} distinct $src author profiles: ${es.map(_.entityId).distinct.sorted.mkString(", ")}"
          }
        mentionConflicts ++ profileConflicts
    }
}

/** Shared mapper logic: emits the links of one record keyed by identifier. */
abstract class ExactIdMapperBase[R] extends Mapper[Text, Text, Text, Text] {
  protected def inputName: String
  protected def decode(line: String): Either[Vector[scholarchain.core.ValidationError], R]
  protected def sourceOf(r: R): SourceType
  protected def links(r: R): Vector[Either[Problem, (String, ExactIdKeys.Entry)]]

  private var mos: MultipleOutputs[NullWritable, Text] = null
  private var sink: RejectSink = null
  private var run: RunContext = null
  private var conf: Configuration = null

  override def setup(ctx: Mapper[Text, Text, Text, Text]#Context): Unit = {
    conf = ctx.getConfiguration
    run = DerivedRun.from(conf)
    mos = SideOutputs.open(ctx)
    sink = new RejectSink(mos, ctx, Stages.ExactIds)
  }

  override def cleanup(ctx: Mapper[Text, Text, Text, Text]#Context): Unit =
    if (mos != null) mos.close()

  override def map(key: Text, line: Text, ctx: Mapper[Text, Text, Text, Text]#Context): Unit = {
    Counters.inc2(ctx, Counters.Input, inputName)
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
      case Right(r) =>
        Counters.inc2(ctx, Counters.Accepted, sourceOf(r).tag)
        links(r).foreach {
          case Right((k, e)) => ctx.write(new Text(k), new Text(e.encode))
          case Left(p) =>
            sink.write(
              p.reason,
              Disposition.Flagged,
              run,
              None,
              key.toString,
              p.detail,
              None
            )
        }
    }
  }
}

/** Exact-id mapper over curated works. */
final class ExactIdMapper extends ExactIdMapperBase[WorkRecord] {
  protected def inputName: String = "works"
  protected def decode(line: String) = Json.decode[WorkRecord](line)
  protected def sourceOf(w: WorkRecord): SourceType = w.source
  protected def links(w: WorkRecord) = ExactIdKeys.ofWork(w)
}

/** Exact-id mapper over curated source author profiles. */
final class ExactIdProfileMapper extends ExactIdMapperBase[SourceAuthorProfile] {
  protected def inputName: String = "profiles"
  protected def decode(line: String) = Json.decode[SourceAuthorProfile](line)
  protected def sourceOf(p: SourceAuthorProfile): SourceType = p.source
  protected def links(p: SourceAuthorProfile) = ExactIdKeys.ofProfile(p)
}

/**
 * Exact-id reducer: writes the identity links of one identifier to `links` (distinct, sorted by
 * (source, entity, work) and link JSON), counts keys (EXACT_ID_KEY) and keys asserted by several
 * sources (EXACT_ID_CROSS_SOURCE), flags within-source conflicts (CONFLICT), and records the
 * largest group per scheme in `stats`. No match is decided here.
 */
final class ExactIdReducer extends Reducer[Text, Text, NullWritable, Text] {
  private var mos: MultipleOutputs[NullWritable, Text] = null
  private var sink: RejectSink = null
  private var conf: Configuration = null
  private val runs = mutable.Map.empty[SourceType, RunContext]
  private val largest = mutable.Map.empty[String, StatLine]

  override def setup(ctx: Reducer[Text, Text, NullWritable, Text]#Context): Unit = {
    conf = ctx.getConfiguration
    mos = SideOutputs.open(ctx)
    sink = new RejectSink(mos, ctx, Stages.ExactIds)
  }

  private def runFor(s: SourceType): RunContext =
    runs.getOrElseUpdate(s, JobParams.orThrow(RunContext.from(conf, s)))

  override def reduce(
      key: Text,
      values: java.lang.Iterable[Text],
      ctx: Reducer[Text, Text, NullWritable, Text]#Context
  ): Unit = {
    val k = key.toString
    val i = k.indexOf('\t')
    val (scheme, value) = (k.substring(0, i), k.substring(i + 1))
    val entries = values.asScala.iterator
      .map(t => ExactIdKeys.Entry.decode(t.toString))
      .toVector
      .distinct
      .sortWith((a, b) => Canonical.compareUtf8(a.encode, b.encode) < 0)
    entries.foreach { e =>
      mos.write(NamedOutputs.Links, NullWritable.get, new Text(e.link), "links/part")
    }
    Counters.inc2(ctx, Counters.Emitted, NamedOutputs.Links, entries.size.toLong)
    Counters.inc2(ctx, Counters.ExactIdKey, scheme)
    if (entries.map(_.source).distinct.size > 1)
      Counters.inc2(ctx, Counters.ExactIdCrossSource, scheme)
    ExactIdKeys.conflicts(scheme, value, entries).foreach { case (src, why) =>
      sink.write(
        RejectReason.Conflict,
        Disposition.Flagged,
        SourceType.fromTag(src).filter(_.isDataset).map(runFor).getOrElse(DerivedRun.from(conf)),
        None,
        k.replace('\t', ':'),
        Vector(why),
        None
      )
    }
    val metric = s"largestGroup.$scheme"
    val candidate = StatLine(metric, entries.size.toLong, k.replace('\t', ':'))
    largest.update(metric, largest.get(metric).fold(candidate)(StatLine.better(_, candidate)))
  }

  override def cleanup(ctx: Reducer[Text, Text, NullWritable, Text]#Context): Unit = {
    largest.toVector.sortBy(_._1).foreach { case (_, s) =>
      mos.write(NamedOutputs.Stats, NullWritable.get, new Text(StatLine.toJson(s)), "stats/part")
    }
    if (mos != null) mos.close()
  }
}
