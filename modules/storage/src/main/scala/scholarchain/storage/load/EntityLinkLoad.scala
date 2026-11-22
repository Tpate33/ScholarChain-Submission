package scholarchain.storage.load

import scholarchain.core.{
  AuthorMention,
  Canonical,
  IdKind,
  ResearcherAssignment,
  StableId,
  TargetStore
}
import scholarchain.storage.model.{
  Attrs,
  AttrValue,
  EntityLinkEvidence,
  EntityLinkRecord,
  EntityLinkSurfaceForm,
  Item,
  ItemCodecs,
  LoadState,
  PrimaryKey,
  StoreError,
  TableRole
}
import scholarchain.storage.port.{Clock, KeyQuery, KeyValueStore, SortKeyCondition}

/**
 * Configuration of the entity-link load (SE-P9-01). No default: every value is the caller's
 * configuration.
 *
 * @param expiresAtEpochSeconds
 *   the TTL instant written on every link (epoch seconds, absolute). Absolute, not "now + ttl", so
 *   a rerun writes byte-identical rows (idempotent); a load whose instant is not after the injected
 *   clock's now is refused (it would write links the runtime already treats as expired)
 * @param maxLinks
 *   the most link rows one load may write (fail closed above it)
 * @param maxMentionIdsPerLink
 *   the most supporting mention IDs listed in one link's evidence (the count and the hash of the
 *   full list are always recorded)
 */
final case class EntityLinkLoadConfig private (
    expiresAtEpochSeconds: Long,
    maxLinks: Int,
    maxMentionIdsPerLink: Int
)

object EntityLinkLoadConfig {
  def of(
      expiresAtEpochSeconds: Long,
      maxLinks: Int,
      maxMentionIdsPerLink: Int
  ): Either[StoreError, EntityLinkLoadConfig] =
    if (expiresAtEpochSeconds < 1)
      Left(StoreError.Refused(s"expiresAtEpochSeconds: $expiresAtEpochSeconds is below 1"))
    else if (maxLinks < 1) Left(StoreError.Refused(s"maxLinks: $maxLinks is below 1"))
    else if (maxMentionIdsPerLink < 1)
      Left(StoreError.Refused(s"maxMentionIdsPerLink: $maxMentionIdsPerLink is below 1"))
    else Right(new EntityLinkLoadConfig(expiresAtEpochSeconds, maxLinks, maxMentionIdsPerLink))
}

/**
 * The links of one load, before any write.
 *
 * @param surfaceForms
 *   distinct surface forms (partitions) written
 * @param ambiguousSurfaceForms
 *   surface forms that name two or more researchers (every one of them is written, ranked)
 * @param skippedEmptyNames
 *   identified mentions whose name normalizes to the empty string (no surface form, no link)
 */
final case class EntityLinkPlan(
    loadId: String,
    links: Vector[EntityLinkRecord],
    surfaceForms: Int,
    ambiguousSurfaceForms: Int,
    skippedEmptyNames: Int
)

/** Result of one entity-link load call. */
final case class EntityLinkLoadReport(
    runId: String,
    loadId: String,
    links: Int,
    surfaceForms: Int,
    ambiguousSurfaceForms: Int,
    skippedEmptyNames: Int,
    written: Long,
    alreadyPresent: Long,
    finalState: LoadState,
    skipped: Boolean,
    largestItemBytes: Int
)

/**
 * SE-P9-01: fills the EntityLinkCache (EL-1) with the surface forms of the entity-resolution
 * outputs a verified analysis run was built from, so the runtime ResearcherResolver can resolve a
 * name.
 *
 * Inputs: only the bound, verified upstream of the run ([[SummaryUpstream]]: the grouping run's
 * researcher assignments and the curated run's author mentions, both read and verified by
 * [[UpstreamRuns]] and bound to the analysis run by the verifier). It runs only with a `dynamodb`
 * [[LoadPermit]] (the gate opened for the DynamoDB store, which holds the EntityLinkCache table).
 *
 *   - One link per (surface form, researcher): the surface form is
 *     [[EntityLinkSurfaceForm.normalize]] of the raw name of every author mention whose researcher
 *     assignment is identified (resolved or singleton). Unresolved mentions give no link.
 *   - Ambiguity is preserved, never collapsed: a surface form shared by several researchers gets
 *     one row per researcher, ranked by supporting mentions (descending), then researcher ID
 *     (UTF-8); the rank is display order only.
 *   - Every row carries the analysis run ID (`sourceRunId`), the configured absolute expiry and its
 *     [[EntityLinkEvidence]] (grouping and curated runs, policy versions, source datasets,
 *     snapshots, supporting mention IDs and their hash).
 *   - Idempotent: deterministic rows, `putIfAbsent` only. Probe before any write (whole EL-1
 *     partition of each surface form): a different body under one of this load's keys, or ANY row
 *     of another researcher or run in one of its partitions, is a `Conflict` and nothing is
 *     written, so one surface form and run never silently point at a different researcher set.
 *   - Ledger: its own RunLedger row (load ID [[loadIdOf]], target `dynamodb`) under the
 *     [[LoadLedger]] protocol (owner and publication claims, resume, skip when loaded).
 */
object EntityLinkLoad {

  /** The `matchedBy` token of every loader-written link. */
  val MatchedBy = "er-mention-raw-name"

  /** Domain tag of the derived load ID. */
  val LoadIdTag = "scholarchain.storage.entity-link-load.v1"

  /** The physical stores this loader writes (the key-value store only). */
  val WrittenStores: Vector[TargetStore] = Vector(TargetStore.DynamoDb)

  private val codec = ItemCodecs.EntityLinkCodec

  private def netstring(parts: Vector[String]): String =
    parts.map(p => s"${p.getBytes("UTF-8").length}:$p,").mkString

  /**
   * The deterministic load ID of the entity-link load: a SHA-256 over the analysis run, its
   * `_parts.json` SHA-256, the run's dynamodb load ID, the upstream run IDs, the normalization
   * version, `matchedBy` and the configuration (a changed configuration is another load, whose
   * probe then refuses the stored rows instead of skipping them).
   */
  def loadIdOf(
      runId: String,
      partManifestSha256: String,
      dynamoLoadId: String,
      groupingRunId: String,
      curatedRunId: String,
      config: EntityLinkLoadConfig
  ): String =
    "load:" + Canonical.sha256HexUtf8(
      netstring(
        Vector(
          LoadIdTag,
          runId,
          partManifestSha256,
          dynamoLoadId,
          groupingRunId,
          curatedRunId,
          EntityLinkSurfaceForm.Version,
          MatchedBy,
          config.expiresAtEpochSeconds.toString,
          config.maxLinks.toString,
          config.maxMentionIdsPerLink.toString
        )
      )
    )

  private def distinctSorted(xs: Iterable[String]): Vector[String] =
    xs.toVector.distinct.sortWith((a, b) => Canonical.compareUtf8(a, b) < 0)

  /**
   * The links of `assignments` and `mentions` (pure, deterministic). Refused when an identified
   * mention assignment has no author mention in the curated run, the assignments carry more than
   * one grouping or match policy, or the plan holds more than `config.maxLinks` rows.
   */
  def plan(
      analysisRunId: String,
      loadId: String,
      groupingRunId: String,
      curatedRunId: String,
      assignments: Vector[ResearcherAssignment],
      mentions: Vector[AuthorMention],
      config: EntityLinkLoadConfig
  ): Either[StoreError, EntityLinkPlan] = {
    val byMention: Map[String, AuthorMention] = mentions.map(m => m.mentionId -> m).toMap
    val identified = assignments.filter(a =>
      a.researcherId.isDefined &&
        StableId.prefixKind(a.memberId).contains(IdKind.Mention)
    )
    val missing = identified.filterNot(a => byMention.contains(a.memberId))
    val groupingPolicies = distinctSorted(identified.map(_.groupingPolicyVersion))
    val matchPolicies = distinctSorted(identified.map(_.matchPolicyVersion))
    if (missing.nonEmpty)
      Left(
        StoreError.Refused(
          s"entity links: ${missing.size} identified mention assignments have no author mention " +
            s"in curated run $curatedRunId (first ${missing.head.memberId})"
        )
      )
    else if (groupingPolicies.size > 1 || matchPolicies.size > 1)
      Left(
        StoreError.Refused(
          "entity links: the assignments carry more than one grouping or match policy " +
            s"(${groupingPolicies.mkString(",")}; ${matchPolicies.mkString(",")})"
        )
      )
    else if (identified.isEmpty)
      Left(StoreError.Refused("entity links: no identified mention assignment"))
    else {
      // (surface hash, researcher) -> supporting mentions
      val named = identified.map { a =>
        val m = byMention(a.memberId)
        (EntityLinkSurfaceForm.normalize(m.rawName), a.researcherId.get, m)
      }
      val (empty, usable) = named.partition(_._1.isEmpty)
      val bySurface: Map[String, Map[String, Vector[AuthorMention]]] =
        usable
          .groupBy(x => Canonical.sha256HexUtf8(x._1))
          .map { case (h, xs) => h -> xs.groupBy(_._2).map { case (r, ys) => r -> ys.map(_._3) } }
      val links = bySurface.toVector
        .sortWith((a, b) => Canonical.compareUtf8(a._1, b._1) < 0)
        .flatMap { case (surface, researchers) =>
          researchers.toVector
            .sortWith { (a, b) =>
              if (a._2.size != b._2.size) a._2.size > b._2.size
              else Canonical.compareUtf8(a._1, b._1) < 0
            }
            .zipWithIndex
            .map { case ((researcher, ms), i) =>
              val ids = distinctSorted(ms.map(_.mentionId))
              EntityLinkRecord(
                EntityLinkRecord.SchemaVersion,
                surface,
                researcher,
                MatchedBy,
                i + 1,
                analysisRunId,
                config.expiresAtEpochSeconds,
                Some(
                  EntityLinkEvidence(
                    EntityLinkEvidence.SchemaVersion,
                    EntityLinkSurfaceForm.Version,
                    loadId,
                    groupingRunId,
                    curatedRunId,
                    groupingPolicies.head,
                    matchPolicies.head,
                    distinctSorted(ms.map(_.provenance.sourceDataset.tag)),
                    distinctSorted(ms.map(_.provenance.snapshot)),
                    ids.size,
                    ids.take(config.maxMentionIdsPerLink),
                    EntityLinkEvidence.mentionIdsSha256(ids)
                  )
                )
              )
            }
        }
      if (links.size > config.maxLinks)
        Left(
          StoreError.Refused(
            s"entity links: ${links.size} links exceed maxLinks ${config.maxLinks}"
          )
        )
      else
        Right(
          EntityLinkPlan(
            loadId,
            links,
            bySurface.size,
            bySurface.count(_._2.size > 1),
            empty.size
          )
        )
    }
  }

  private def keyOf(i: Item): Either[StoreError, PrimaryKey] =
    (i.get(Attrs.Pk), i.get(Attrs.Sk)) match {
      case (Some(AttrValue.S(pk)), Some(AttrValue.S(sk))) => Right(PrimaryKey(pk, sk))
      case _ => Left(StoreError.Refused("entity links: an item has no table key"))
    }

  /**
   * Before any write: every EL-1 partition this load writes holds only this load's rows (by key and
   * `recordSha256`). Another body under one of its keys, or a row under another key (another
   * researcher, another run, or a runtime-written link), is a `Conflict`.
   */
  private[load] def probe(table: KeyValueStore)(items: Vector[Item]): Either[StoreError, Unit] = {
    val byPartition = items.groupBy(i => keyOf(i).map(_.pk))
    byPartition.toVector
      .sortBy(_._1.toOption.getOrElse(""))
      .foldLeft[Either[StoreError, Unit]](Right(())) { case (acc, (pk, mine)) =>
        acc.flatMap { _ =>
          for {
            p <- pk
            expected <- mine.foldLeft[Either[StoreError, Map[PrimaryKey, Option[AttrValue]]]](
              Right(Map.empty)
            )((m, i) =>
              m.flatMap(mm => keyOf(i).map(k => mm.updated(k, i.get(Attrs.RecordSha256))))
            )
            stored <- table.query(
              TableRole.EntityLinkCache,
              KeyQuery(None, p, SortKeyCondition.All, mine.size + 1)
            )
            _ <- stored.foldLeft[Either[StoreError, Unit]](Right(())) { (a, s) =>
              a.flatMap(_ =>
                keyOf(s).flatMap { k =>
                  expected.get(k) match {
                    case None =>
                      Left(
                        StoreError.Conflict(
                          s"${TableRole.EntityLinkCache.tag} ${k.pk}: holds ${k.sk}, which this " +
                            "load does not write (pre-write probe): a surface form and run may not " +
                            "point at another researcher set"
                        )
                      )
                    case Some(h) if h != s.get(Attrs.RecordSha256) =>
                      Left(
                        StoreError.Conflict(
                          s"${TableRole.EntityLinkCache.tag} ${k.pk} ${k.sk}: holds a different " +
                            "record (pre-write probe)"
                        )
                      )
                    case Some(_) => Right(())
                  }
                }
              )
            }
          } yield ()
        }
      }
  }

  /**
   * One load call. Writes nothing (not even a ledger row) when the plan, an item, the size limit or
   * the expiry refuses; otherwise follows the [[LoadLedger]] protocol.
   */
  def load(
      permit: LoadPermit,
      upstream: SummaryUpstream,
      config: EntityLinkLoadConfig,
      limits: KeyValueLoadLimits,
      table: KeyValueStore,
      ledger: KeyValueStore,
      clock: Clock,
      progress: LoadProgress = new LoadProgress
  ): Either[StoreError, EntityLinkLoadReport] =
    if (Vector(permit, upstream, config, limits, table, ledger, clock, progress).contains(null))
      Left(StoreError.Refused("entity-link load: every argument is required"))
    else if (permit.target != TargetStore.DynamoDb)
      Left(StoreError.Refused(s"entity-link load: the permit is for ${permit.target.tag}"))
    else if (config.expiresAtEpochSeconds <= clock.nowMillis() / 1000L)
      Left(
        StoreError.Refused(
          s"entity-link load: expiresAtEpochSeconds ${config.expiresAtEpochSeconds} is not after " +
            "the clock's now: every link would already be expired"
        )
      )
    else {
      val loadId = loadIdOf(
        permit.runId,
        permit.partManifestSha256,
        permit.load.loadId,
        upstream.grouping.runId,
        upstream.curated.runId,
        config
      )
      for {
        p <- plan(
          permit.runId,
          loadId,
          upstream.grouping.runId,
          upstream.curated.runId,
          upstream.graphInput.researchers,
          upstream.curated.mentions,
          config
        )
        items <- p.links.foldLeft[Either[StoreError, Vector[Item]]](Right(Vector.empty)) {
          (acc, r) => acc.flatMap(xs => codec.encode(r).map(xs :+ _))
        }
        largest = items.map(KeyValueLoad.itemBytes).maxOption.getOrElse(0)
        _ <-
          if (largest <= limits.maxItemBytes) Right(())
          else
            Left(
              StoreError.Refused(
                s"entity-link load: an item of $largest bytes exceeds maxItemBytes ${limits.maxItemBytes}"
              )
            )
        subject = LedgerSubject(
          permit.runId,
          permit.partManifestSha256,
          loadId,
          TargetStore.DynamoDb,
          items.size
        )
        out <- LoadLedger.runSubject(
          subject,
          ledger,
          clock,
          items,
          WrittenStores,
          progress,
          None
        )(probe(table)) { (_, es) =>
          val it = es.iterator
          var failure: Option[StoreError] = None
          while (failure.isEmpty && it.hasNext)
            table.putIfAbsent(TableRole.EntityLinkCache, it.next()) match {
              case Right(scholarchain.storage.model.PutOutcome.Written) => progress.wrote()
              case Right(scholarchain.storage.model.PutOutcome.AlreadyPresent) =>
                progress.present()
              case Left(e) => failure = Some(e)
            }
          failure.toLeft(PassEnd.Complete)
        }
      } yield EntityLinkLoadReport(
        permit.runId,
        loadId,
        items.size,
        p.surfaceForms,
        p.ambiguousSurfaceForms,
        p.skippedEmptyNames,
        out.written,
        out.alreadyPresent,
        out.finalState,
        out.skipped,
        largest
      )
    }
}
