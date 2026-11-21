package scholarchain.storage.model

import scholarchain.core.{CanonicalPair, IdKind}

/**
 * Key values of every table (forms as in [[TableSpecs]]). Every builder validates its IDs with
 * `core`, so a key never embeds a malformed or foreign-kind ID.
 */
object TableKeys {
  private def id(kind: IdKind, v: String, f: String) = StoreIds.require(kind, v, f)

  // ------------------------------------------------------------------ RunLedger
  object RunLedger {
    def partition(runId: String): Either[StoreError, String] =
      id(IdKind.Run, runId, "runId").map("run#" + _)

    def publication(runId: String): Either[StoreError, PrimaryKey] =
      partition(runId).map(PrimaryKey(_, "publication"))

    def load(runId: String, loadId: String): Either[StoreError, PrimaryKey] =
      for {
        p <- partition(runId)
        l <- id(IdKind.StoreLoad, loadId, "loadId")
      } yield PrimaryKey(p, s"load#$l")

    val LoadPrefix = "load#"

    def rejectPrefix(loadId: String): Either[StoreError, String] =
      id(IdKind.StoreLoad, loadId, "loadId").map(l => s"reject#$l#")

    /** CR-P7-11 m1: the rejected-record rows of one write generation of a load. */
    def rejectGenerationPrefix(loadId: String, generation: Long): Either[StoreError, String] =
      if (generation < 1 || generation > RejectedRecord.MaxGeneration)
        Left(
          StoreError.Refused(
            s"generation: $generation is outside 1..${RejectedRecord.MaxGeneration}"
          )
        )
      else rejectPrefix(loadId).map(p => f"$p$generation%010d#")

    def reject(
        runId: String,
        loadId: String,
        generation: Long,
        recordKey: String
    ): Either[StoreError, PrimaryKey] =
      for {
        p <- partition(runId)
        r <- rejectGenerationPrefix(loadId, generation)
        k <- RejectedRecord.recordKeyProblem(recordKey) match {
          case None => Right(recordKey)
          case Some(why) => Left(StoreError.Refused(s"recordKey: $why"))
        }
      } yield PrimaryKey(p, r + k)

    /**
     * SE-P7-11 (CR-P7-12 F2): the rejection-set summary rows of a load, `rejectset#<loadId>#`
     * (never a prefix of `reject#`, so RL-4 never returns them).
     */
    def rejectionSetPrefix(loadId: String): Either[StoreError, String] =
      id(IdKind.StoreLoad, loadId, "loadId").map(l => s"rejectset#$l#")

    /** The summary row of one write generation's complete rejection set. */
    def rejectionSet(
        runId: String,
        loadId: String,
        generation: Long
    ): Either[StoreError, PrimaryKey] =
      if (generation < 1 || generation > RejectedRecord.MaxGeneration)
        Left(
          StoreError.Refused(
            s"generation: $generation is outside 1..${RejectedRecord.MaxGeneration}"
          )
        )
      else
        for {
          p <- partition(runId)
          r <- rejectionSetPrefix(loadId)
        } yield PrimaryKey(p, f"$r$generation%010d")

    def byLoad(loadId: String): Either[StoreError, String] =
      id(IdKind.StoreLoad, loadId, "loadId").map("load#" + _)

    /** CR-P7-10 m1: the owner row of one target store instance (one run per store). */
    def storeOwner(target: scholarchain.core.TargetStore): Either[StoreError, PrimaryKey] =
      Option(target)
        .toRight(StoreError.Refused("targetStore: missing"))
        .map(t => PrimaryKey(s"store#${t.tag}", "owner"))
  }

  // ------------------------------------------------------------------ ArtifactRegistry
  object ArtifactRegistry {
    def partition(runId: String): Either[StoreError, String] =
      id(IdKind.Run, runId, "runId").map("run#" + _)

    def zonePrefix(zone: Zone): String = s"object#${zone.tag}#"

    def artifact(runId: String, zone: Zone, key: String): Either[StoreError, PrimaryKey] =
      for {
        p <- partition(runId)
        k <-
          if (key != null && key.nonEmpty && !key.exists(_.isControl)) Right(key)
          else Left(StoreError.Refused("key: missing or has control characters"))
      } yield PrimaryKey(p, zonePrefix(zone) + k)

    def bySha256(sha256: String): Either[StoreError, String] =
      StoreIds.hex64(sha256, "sha256").map("sha256#" + _)
  }

  // ------------------------------------------------------------------ EvidenceBundle
  object EvidenceBundle {
    def partition(bundleId: String): Either[StoreError, String] =
      id(IdKind.Bundle, bundleId, "bundleId").map("bundle#" + _)

    def bundle(bundleId: String): Either[StoreError, PrimaryKey] =
      partition(bundleId).map(PrimaryKey(_, "bundle"))

    def item(bundleId: String, evidenceId: String): Either[StoreError, PrimaryKey] =
      for {
        p <- partition(bundleId)
        e <- id(IdKind.Evidence, evidenceId, "evidenceId")
      } yield PrimaryKey(p, s"item#$e")

    def byQuery(queryId: String): Either[StoreError, String] =
      id(IdKind.Query, queryId, "queryId").map("query#" + _)

    def byEvidence(evidenceId: String): Either[StoreError, String] =
      id(IdKind.Evidence, evidenceId, "evidenceId").map("ev#" + _)
  }

  // ------------------------------------------------------------------ QuerySession
  object QuerySession {
    def partition(queryId: String): Either[StoreError, String] =
      id(IdKind.Query, queryId, "queryId").map("query#" + _)

    def session(queryId: String): Either[StoreError, PrimaryKey] =
      partition(queryId).map(PrimaryKey(_, "session"))

    /** Step ordinals are zero-padded to four digits, so sort-key order is step order. */
    def step(queryId: String, ordinal: Int): Either[StoreError, PrimaryKey] =
      if (ordinal < 0 || ordinal > 9999)
        Left(StoreError.Refused(s"ordinal: $ordinal is outside 0..9999"))
      else partition(queryId).map(PrimaryKey(_, f"step#$ordinal%04d"))
  }

  // ------------------------------------------------------------------ EntityLinkCache
  object EntityLinkCache {

    /** The surface form arrives hashed: storage never normalizes or stores names here. */
    def partition(surfaceSha256: String): Either[StoreError, String] =
      StoreIds.hex64(surfaceSha256, "surfaceSha256").map("surface#" + _)

    def link(surfaceSha256: String, researcherId: String): Either[StoreError, PrimaryKey] =
      for {
        p <- partition(surfaceSha256)
        r <- id(IdKind.Researcher, researcherId, "researcherId")
      } yield PrimaryKey(p, s"researcher#$r")

    def byResearcher(researcherId: String): Either[StoreError, String] =
      id(IdKind.Researcher, researcherId, "researcherId").map("researcher#" + _)
  }

  // ------------------------------------------------------------------ ResearcherMetricCache
  object ResearcherMetricCache {
    def partition(subjectId: String): Either[StoreError, String] =
      StoreIds.requireAny(subjectId, "subjectId").map("subject#" + _)

    def byGroup(groupId: String, startYear: Int, endYear: Int): Either[StoreError, String] =
      for {
        g <- StoreIds.requireAny(groupId, "groupId")
        _ <-
          if (
            g.startsWith(IdKind.Component.prefix + ":") || g.startsWith(
              IdKind.Community.prefix + ":"
            )
          )
            Right(())
          else Left(StoreError.Refused("groupId: must be a component or community ID"))
        w <- StoreIds.window(startYear, endYear)
      } yield s"group#$g#$w"

    def groupMember(researcherId: String): Either[StoreError, String] =
      id(IdKind.Researcher, researcherId, "researcherId").map("member#" + _)

    /** The pair must already be canonical (first < second by UTF-8 bytes). */
    def byPair(first: String, second: String): Either[StoreError, String] =
      CanonicalPair.researchers(first, second) match {
        case Left(e) => Left(StoreError.Refused(e.message))
        case Right(p) if p.first != first =>
          Left(StoreError.Refused("pair: not in canonical order (first < second)"))
        case Right(p) => Right(s"pair#${p.first}#${p.second}")
      }

    def rank(role: String, startYear: Int, endYear: Int): Either[StoreError, String] =
      for {
        r <- StoreIds.token(role, "role")
        w <- StoreIds.window(startYear, endYear)
      } yield s"rank#$r#$w"

    /** Rank 1.. zero-padded to ten digits, so ascending sort-key order is ascending rank. */
    def rankSort(rank: Int, subjectId: String): Either[StoreError, String] =
      if (rank < 1) Left(StoreError.Refused(s"rank: $rank is below 1"))
      else StoreIds.requireAny(subjectId, "subjectId").map(s => f"$rank%010d#$s")

    // ---------------------------------------------------------------- SE-P7-07 analysis results

    /** `<window>` or `<from>#<to>` (both validated window keys). */
    private def windows(window: String, toWindow: Option[String]): Either[StoreError, String] =
      for {
        w <- StoreIds.windowKey(window, "window")
        t <- toWindow match {
          case None => Right("")
          case Some(x) => StoreIds.windowKey(x, "toWindow").map("#" + _)
        }
      } yield w + t

    /** A subject row: `subject#<id>` / `metric#<role>#<window>[#<toWindow>]` (MC-1, MC-2, MC-7). */
    def subjectResult(
        subjectId: String,
        role: String,
        window: String,
        toWindow: Option[String]
    ): Either[StoreError, PrimaryKey] =
      for {
        p <- partition(subjectId)
        r <- StoreIds.token(role, "role")
        w <- windows(window, toWindow)
      } yield PrimaryKey(p, s"metric#$r#$w")

    /** A window row: `window#<window>` / `result#<role>[#<toWindow>]` (MC-6). */
    def windowResult(
        window: String,
        role: String,
        toWindow: Option[String]
    ): Either[StoreError, PrimaryKey] =
      for {
        w <- StoreIds.windowKey(window, "window")
        r <- StoreIds.token(role, "role")
        t <- toWindow match {
          case None => Right("")
          case Some(x) => StoreIds.windowKey(x, "toWindow").map("#" + _)
        }
      } yield PrimaryKey(s"window#$w", s"result#$r$t")

    /** byGroup partition from a window key. */
    def byGroupOf(groupId: String, window: String): Either[StoreError, String] =
      StoreIds.windowYears(window, "window").flatMap { case (s, e) => byGroup(groupId, s, e) }

    /** byPair sort key `window#<window>#<role>#<subjectId>` (MC-4: BeginsWith `window#<w>#`). */
    def pairSort(window: String, role: String, subjectId: String): Either[StoreError, String] =
      for {
        w <- StoreIds.windowKey(window, "window")
        r <- StoreIds.token(role, "role")
        s <- StoreIds.requireAny(subjectId, "subjectId")
      } yield s"window#$w#$r#$s"

    /** rank partition from a window key. */
    def rankOf(role: String, window: String): Either[StoreError, String] =
      StoreIds.windowYears(window, "window").flatMap { case (s, e) => rank(role, s, e) }
  }
}
