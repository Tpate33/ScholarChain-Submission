package scholarchain.storage.model

import upickle.default.{read, write, ReadWriter}

import scholarchain.core.{Canonical, EvidenceBundle, IdKind}

/**
 * Item codec of one record type and table (SE-P7-01). Every item holds:
 *
 *   - the table key (`pk`, `sk`) and the record's GSI key attributes (sparse: only the GSIs the
 *     record belongs to);
 *   - `schemaVersion`, `body` (the record's compact JSON, written by uPickle) and `recordSha256`
 *     (SHA-256 of `body`): conditional writes compare `recordSha256` to tell an identical rewrite
 *     (`AlreadyPresent`) from a different record under the same key (`Conflict`);
 *   - `version` (N) for versioned records, and `expiresAt` (N, epoch seconds) exactly on TTL
 *     tables.
 *
 * Decoding recomputes the whole item from the decoded body and refuses (`Corrupt`) any item that
 * differs: a tampered body, hash, key, index attribute, version or TTL never decodes.
 */
abstract class ItemCodec[A](val table: TableSpec, val schemaVersion: String)(using
    rw: ReadWriter[A]
) {
  protected def schemaVersionOf(a: A): String
  protected def validate(a: A): Either[StoreError, Unit] = Right(())
  def key(a: A): Either[StoreError, PrimaryKey]
  protected def indexKeys(a: A): Either[StoreError, Map[String, String]] = Right(Map.empty)
  protected def version(a: A): Option[Long] = None
  protected def expiresAt(a: A): Option[Long] = None

  private def refused(why: String) = StoreError.Refused(s"${table.role.tag}: $why")

  final def encode(a: A): Either[StoreError, Item] =
    if (a == null) Left(refused("record missing"))
    else if (schemaVersionOf(a) != schemaVersion)
      Left(refused(s"schemaVersion '${schemaVersionOf(a)}' is not $schemaVersion"))
    else
      for {
        _ <- validate(a)
        k <- key(a)
        ix <- indexKeys(a)
        allowed = table.indexes.flatMap(g => Vector(g.partitionKey, g.sortKey)).toSet
        _ <-
          if (ix.keySet.subsetOf(allowed)) Right(())
          else Left(refused(s"unknown index attributes ${(ix.keySet -- allowed).toVector.sorted}"))
        _ <- table.indexes.find(g => ix.contains(g.partitionKey) != ix.contains(g.sortKey)) match {
          case Some(g) => Left(refused(s"index ${g.name} needs both key attributes"))
          case None => Right(())
        }
        ttl <- (table.ttlAttribute, expiresAt(a)) match {
          case (Some(attr), Some(t)) if t > 0 => Right(Map(attr -> AttrValue.n(t)))
          case (Some(attr), _) =>
            Left(refused(s"TTL attribute $attr needs a positive epoch second"))
          case (None, Some(_)) => Left(refused("table has no TTL"))
          case (None, None) => Right(Map.empty[String, AttrValue])
        }
      } yield {
        val body = write(a)
        Map[String, AttrValue](
          table.partitionKey -> AttrValue.S(k.pk),
          table.sortKey -> AttrValue.S(k.sk),
          Attrs.SchemaVersion -> AttrValue.S(schemaVersion),
          Attrs.Body -> AttrValue.S(body),
          Attrs.RecordSha256 -> AttrValue.S(Canonical.sha256HexUtf8(body))
        ) ++ ix.map { case (n, v) => n -> AttrValue.S(v) } ++
          version(a).map(v => Attrs.Version -> AttrValue.n(v)) ++ ttl
      }

  final def decode(item: Item): Either[StoreError, A] = {
    def corrupt(why: String) = StoreError.Corrupt(s"${table.role.tag}: $why")
    for {
      _ <- Option(item).toRight(corrupt("item missing"))
      body <- AttrValue.string(item, Attrs.Body)
      sha <- AttrValue.string(item, Attrs.RecordSha256)
      _ <-
        if (Canonical.sha256HexUtf8(body) == sha) Right(())
        else Left(corrupt("recordSha256 does not match body"))
      sv <- AttrValue.string(item, Attrs.SchemaVersion)
      _ <- if (sv == schemaVersion) Right(()) else Left(corrupt(s"schemaVersion '$sv'"))
      a <- parse(body).left.map(why => corrupt(s"body undecodable ($why)"))
      expected <- encode(a).left.map(e => corrupt(s"body is not a valid record: ${e.message}"))
      _ <-
        if (expected == item) Right(()) else Left(corrupt("item attributes differ from its body"))
    } yield a
  }

  private def parse(body: String): Either[String, A] =
    try Right(read[A](body))
    catch { case e: Exception => Left(e.getClass.getSimpleName) }
}

object ItemCodecs {
  private val K = TableKeys

  private def check(ok: Boolean, why: => String): Either[StoreError, Unit] =
    if (ok) Right(()) else Left(StoreError.Refused(why))

  given RunPublicationCodec: ItemCodec[RunPublication] =
    new ItemCodec[RunPublication](TableSpecs.RunLedger, RunPublication.SchemaVersion) {
      protected def schemaVersionOf(a: RunPublication) = a.schemaVersion
      override protected def validate(a: RunPublication) =
        StoreIds.firstError(
          StoreIds.hex64(a.partManifestSha256, "partManifestSha256"),
          StoreIds.require(IdKind.StoreLoad, a.firstLoadId, "firstLoadId")
        )
      def key(a: RunPublication) = K.RunLedger.publication(a.runId)
    }

  given LoadEntryCodec: ItemCodec[LoadEntry] =
    new ItemCodec[LoadEntry](TableSpecs.RunLedger, LoadEntry.SchemaVersion) {
      protected def schemaVersionOf(a: LoadEntry) = a.schemaVersion
      override protected def validate(a: LoadEntry) =
        StoreIds.firstError(
          StoreIds.hex64(a.partManifestSha256, "partManifestSha256"),
          check(a.targetStore != null && a.state != null, "targetStore and state are required"),
          check(a.version >= 1, s"version ${a.version} is below 1"),
          check(a.itemCount >= 1, "itemCount is below 1"),
          check(a.written >= 0 && a.alreadyPresent >= 0 && a.rejected >= 0, "negative count"),
          check(
            a.embeddingModel != null && a.embedding != null,
            "embeddingModel and embedding must be absent or present"
          ),
          check(
            Option(a.embedding).flatten.forall(_ => Option(a.embeddingModel).flatten.isDefined),
            "embedding totals belong to a model-scoped vector load row"
          ),
          check(
            Option(a.embedding).flatten.forall(EmbeddingTotals.problems(_).isEmpty),
            Option(a.embedding).flatten.toVector.flatMap(EmbeddingTotals.problems).mkString("; ")
          ),
          // CR-P8-01 M1: the failure of the last failed call, only on a failed or writing row.
          check(a.failure != null, "failure must be absent or present"),
          check(
            Option(a.failure).flatten.forall(_ =>
              a.state == LoadState.Failed || a.state == LoadState.Writing
            ),
            "a failure belongs to a failed or writing row"
          ),
          check(
            Option(a.failure).flatten.forall(LoadFailure.problems(_).isEmpty),
            Option(a.failure).flatten.toVector.flatMap(LoadFailure.problems).mkString("; ")
          )
        )
      def key(a: LoadEntry) = K.RunLedger.load(a.runId, a.loadId, a.embeddingModel)
      override protected def indexKeys(a: LoadEntry) =
        for {
          p <- K.RunLedger.byLoad(a.loadId)
          s <- K.RunLedger.partition(a.runId)
        } yield Map("byLoadPk" -> p, "byLoadSk" -> s)
      override protected def version(a: LoadEntry) = Some(a.version)
    }

  given StoreOwnershipCodec: ItemCodec[StoreOwnership] =
    new ItemCodec[StoreOwnership](TableSpecs.RunLedger, StoreOwnership.SchemaVersion) {
      protected def schemaVersionOf(a: StoreOwnership) = a.schemaVersion
      override protected def validate(a: StoreOwnership) =
        StoreIds.firstError(
          StoreIds.require(IdKind.Run, a.runId, "runId"),
          StoreIds.hex64(a.partManifestSha256, "partManifestSha256")
        )
      def key(a: StoreOwnership) = K.RunLedger.storeOwner(a.targetStore)
    }

  given RejectedRecordCodec: ItemCodec[RejectedRecord] =
    new ItemCodec[RejectedRecord](TableSpecs.RunLedger, RejectedRecord.SchemaVersion) {
      protected def schemaVersionOf(a: RejectedRecord) = a.schemaVersion
      override protected def validate(a: RejectedRecord) = {
        val p = RejectedRecord.problems(a)
        check(p.isEmpty, p.mkString("; "))
      }
      def key(a: RejectedRecord) =
        K.RunLedger.reject(a.runId, a.loadId, a.generation, a.recordKey, a.embeddingModel)
    }

  /** SE-P7-11 (CR-P7-12 F2): the per-generation rejection-set summary (RL-8). */
  given RejectionSetCodec: ItemCodec[RejectionSet] =
    new ItemCodec[RejectionSet](TableSpecs.RunLedger, RejectionSet.SchemaVersion) {
      protected def schemaVersionOf(a: RejectionSet) = a.schemaVersion
      override protected def validate(a: RejectionSet) = {
        val p = RejectionSet.problems(a)
        check(p.isEmpty, p.mkString("; "))
      }
      def key(a: RejectionSet) =
        K.RunLedger.rejectionSet(a.runId, a.loadId, a.generation, a.embeddingModel)
    }

  given ArtifactCodec: ItemCodec[ArtifactRecord] =
    new ItemCodec[ArtifactRecord](TableSpecs.ArtifactRegistry, ArtifactRecord.SchemaVersion) {
      protected def schemaVersionOf(a: ArtifactRecord) = a.schemaVersion
      override protected def validate(a: ArtifactRecord) =
        StoreIds.firstError(
          StoreIds.hex64(a.sha256, "sha256"),
          check(a.byteCount >= 0, "byteCount is negative"),
          check(a.bucket != null && a.bucket.nonEmpty, "bucket is required"),
          check(a.contentType != null && a.contentType.nonEmpty, "contentType is required"),
          a.loadId match {
            case Some(l) => StoreIds.require(IdKind.StoreLoad, l, "loadId")
            case None => Right(())
          },
          a.sourcePath match {
            case Some(p) => S3Layout.checkRelative(p)
            case None => Right(())
          }
        )
      def key(a: ArtifactRecord) =
        Zone.fromTag(a.zone) match {
          case None => Left(StoreError.Refused(s"zone: '${a.zone}' is not a zone"))
          case Some(z) => K.ArtifactRegistry.artifact(a.runId, z, a.key)
        }
      override protected def indexKeys(a: ArtifactRecord) =
        for {
          p <- K.ArtifactRegistry.bySha256(a.sha256)
          k <- key(a)
        } yield Map("bySha256Pk" -> p, "bySha256Sk" -> k.sk)
    }

  given EvidenceBundleCodec: ItemCodec[EvidenceBundle] =
    new ItemCodec[EvidenceBundle](
      TableSpecs.EvidenceBundle,
      scholarchain.core.SchemaVersions.EvidenceBundle
    ) {
      protected def schemaVersionOf(a: EvidenceBundle) = a.schemaVersion
      override protected def validate(a: EvidenceBundle) = {
        val errors = EvidenceBundle.contract.validate(a)
        check(errors.isEmpty, errors.map(_.message).mkString("; "))
      }
      def key(a: EvidenceBundle) = K.EvidenceBundle.bundle(a.bundleId)
      override protected def indexKeys(a: EvidenceBundle) =
        for {
          p <- K.EvidenceBundle.byQuery(a.queryId)
          b <- K.EvidenceBundle.partition(a.bundleId)
        } yield Map("byQueryPk" -> p, "byQuerySk" -> b)
    }

  given EvidenceItemRowCodec: ItemCodec[EvidenceItemRow] =
    new ItemCodec[EvidenceItemRow](TableSpecs.EvidenceBundle, EvidenceItemRow.SchemaVersion) {
      protected def schemaVersionOf(a: EvidenceItemRow) = a.schemaVersion
      override protected def validate(a: EvidenceItemRow) =
        for {
          _ <- check(a.item != null, "item is required")
          _ <- StoreIds.require(IdKind.Query, a.queryId, "queryId")
          errors = scholarchain.core.EvidenceItem.contract.validate(a.item)
          _ <- check(errors.isEmpty, errors.map(_.message).mkString("; "))
        } yield ()
      def key(a: EvidenceItemRow) =
        if (a.item == null) Left(StoreError.Refused("item is required"))
        else K.EvidenceBundle.item(a.bundleId, a.item.evidenceId)
      override protected def indexKeys(a: EvidenceItemRow) =
        for {
          p <- K.EvidenceBundle.byEvidence(a.item.evidenceId)
          b <- K.EvidenceBundle.partition(a.bundleId)
        } yield Map("byEvidencePk" -> p, "byEvidenceSk" -> b)
    }

  given QuerySessionCodec: ItemCodec[QuerySessionRecord] =
    new ItemCodec[QuerySessionRecord](TableSpecs.QuerySession, QuerySessionRecord.SchemaVersion) {
      protected def schemaVersionOf(a: QuerySessionRecord) = a.schemaVersion
      override protected def validate(a: QuerySessionRecord) =
        StoreIds.firstError(
          StoreIds.window(a.windowStartYear, a.windowEndYear),
          StoreIds.token(a.status, "status"),
          a.planSha256 match {
            case Some(h) => StoreIds.hex64(h, "planSha256")
            case None => Right(())
          },
          a.bundleId match {
            case Some(b) => StoreIds.require(IdKind.Bundle, b, "bundleId")
            case None => Right(())
          }
        )
      def key(a: QuerySessionRecord) = K.QuerySession.session(a.queryId)
      override protected def expiresAt(a: QuerySessionRecord) = Some(a.expiresAtEpochSeconds)
    }

  given QueryStepCodec: ItemCodec[QueryStepRecord] =
    new ItemCodec[QueryStepRecord](TableSpecs.QuerySession, QueryStepRecord.SchemaVersion) {
      protected def schemaVersionOf(a: QueryStepRecord) = a.schemaVersion
      override protected def validate(a: QueryStepRecord) =
        StoreIds.firstError(
          (StoreIds.token(a.operation, "operation") +: StoreIds.token(a.status, "status") +:
            Option(a.evidenceIds)
              .getOrElse(Vector.empty)
              .map(StoreIds.require(IdKind.Evidence, _, "evidenceIds")))*
        )
      def key(a: QueryStepRecord) = K.QuerySession.step(a.queryId, a.ordinal)
      override protected def expiresAt(a: QueryStepRecord) = Some(a.expiresAtEpochSeconds)
    }

  given EntityLinkCodec: ItemCodec[EntityLinkRecord] =
    new ItemCodec[EntityLinkRecord](TableSpecs.EntityLinkCache, EntityLinkRecord.SchemaVersion) {
      protected def schemaVersionOf(a: EntityLinkRecord) = a.schemaVersion
      override protected def validate(a: EntityLinkRecord) =
        StoreIds.firstError(
          StoreIds.token(a.matchedBy, "matchedBy"),
          check(a.candidateRank >= 1, "candidateRank is below 1"),
          StoreIds.require(IdKind.Run, a.sourceRunId, "sourceRunId")
        )
      def key(a: EntityLinkRecord) = K.EntityLinkCache.link(a.surfaceSha256, a.researcherId)
      override protected def indexKeys(a: EntityLinkRecord) =
        for {
          p <- K.EntityLinkCache.byResearcher(a.researcherId)
          s <- K.EntityLinkCache.partition(a.surfaceSha256)
        } yield Map("byResearcherPk" -> p, "byResearcherSk" -> s)
      override protected def expiresAt(a: EntityLinkRecord) = Some(a.expiresAtEpochSeconds)
    }

  /**
   * SE-P7-07: the analysis-results rows (see [[AnalysisResultRecord]] and the MC access patterns of
   * [[TableSpecs.ResearcherMetricCache]]). Subject rows `subject#<id>` / `metric#<role>#<window>
   * [#<to>]`; window rows `window#<window>` / `result#<role>[#<to>]`.
   */
  given AnalysisResultCodec: ItemCodec[AnalysisResultRecord] =
    new ItemCodec[AnalysisResultRecord](
      TableSpecs.ResearcherMetricCache,
      AnalysisResultRecord.SchemaVersion
    ) {
      private val R = AnalysisResultRecord
      protected def schemaVersionOf(a: AnalysisResultRecord) = a.schemaVersion
      override protected def validate(a: AnalysisResultRecord) =
        StoreIds.firstError(
          StoreIds.token(a.role, "role"),
          check(a.scope == R.SubjectScope || a.scope == R.WindowScope, s"scope: '${a.scope}'"),
          StoreIds.windowKey(a.window, "window"),
          a.toWindow.map(StoreIds.windowKey(_, "toWindow")).getOrElse(Right(())),
          if (a.scope == R.WindowScope)
            check(a.scopeId == a.window, "a window row is keyed by its window")
          else StoreIds.requireAny(a.scopeId, "scopeId"),
          check(a.record != null && a.record.nonEmpty, "record is required"),
          check(
            a.record != null && Canonical.sha256HexUtf8(a.record) == a.recordSha256,
            "recordSha256 is not the SHA-256 of record"
          ),
          check(
            a.recordSchemaVersion != null && a.recordSchemaVersion.nonEmpty,
            "recordSchemaVersion is required"
          ),
          StoreIds.require(IdKind.Run, a.runId, "runId"),
          StoreIds.require(IdKind.StoreLoad, a.loadId, "loadId"),
          S3Layout.checkRelative(a.sourcePath),
          check(a.pair.forall(_.size == 2), "pair must have exactly two researcher IDs"),
          check(
            a.scope == R.SubjectScope || (a.groupId.isEmpty && a.pair.isEmpty && a.rank.isEmpty),
            "a window row has no group, pair or rank"
          )
        )
      def key(a: AnalysisResultRecord) =
        if (a.scope == R.WindowScope)
          K.ResearcherMetricCache.windowResult(a.window, a.role, a.toWindow)
        else K.ResearcherMetricCache.subjectResult(a.scopeId, a.role, a.window, a.toWindow)
      override protected def indexKeys(a: AnalysisResultRecord) = {
        val M = K.ResearcherMetricCache
        for {
          group <- a.groupId match {
            case None => Right(Map.empty[String, String])
            case Some(g) =>
              for {
                p <- M.byGroupOf(g, a.window)
                m <- M.groupMember(a.scopeId)
              } yield Map("byGroupPk" -> p, "byGroupSk" -> m)
          }
          pair <- a.pair match {
            case None => Right(Map.empty[String, String])
            case Some(Vector(f, t)) =>
              for {
                p <- M.byPair(f, t)
                s <- M.pairSort(a.window, a.role, a.scopeId)
              } yield Map("byPairPk" -> p, "byPairSk" -> s)
            case Some(_) => Left(StoreError.Refused("pair must have exactly two researcher IDs"))
          }
          rank <- a.rank match {
            case None => Right(Map.empty[String, String])
            case Some(r) =>
              for {
                p <- M.rankOf(a.role, a.window)
                k <- M.rankSort(r, a.scopeId)
              } yield Map("rankPk" -> p, "rankSk" -> k)
          }
        } yield group ++ pair ++ rank
      }
    }

  // SE-P7-08 (closes the SE-P7-07 key-space risk): the pre-SE-P7-07 `ResearcherMetricCodec` is
  // removed. It wrote the same table under the same `subject#<id>` / `metric#<role>#<window>` keys
  // as AnalysisResultCodec, so one of its rows could collide with an analysis-result row (a
  // Conflict). No loader or runtime path used it; AnalysisResultCodec is the only codec of the
  // analysis-results table (pinned by TableSpecsSuite).
}
