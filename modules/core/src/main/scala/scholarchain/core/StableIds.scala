package scholarchain.core

/**
 * Stable ID kinds. Every ID is `<prefix>:<body>`, printable ASCII without whitespace.
 *
 * Namespaced IDs carry an authoritative source ID: `work`, `researcher` (ORCID form), `venue`,
 * `institution`, `topic`, `snapshot`, `query`, `source-author`. Hash IDs have the body `<64
 * lowercase hex>`, the SHA-256 of the netstring encoding of a domain tag plus their inputs (see
 * [[Canonical]]).
 */
enum IdKind(val prefix: String) {
  case Work extends IdKind("work")
  case Researcher extends IdKind("researcher")
  case Mention extends IdKind("mention")
  case Venue extends IdKind("venue")
  case Institution extends IdKind("institution")
  case Topic extends IdKind("topic")
  case Snapshot extends IdKind("snapshot")
  case Chunk extends IdKind("chunk")
  case CoauthorEdge extends IdKind("coauthor")
  case GraphEdge extends IdKind("gedge")
  case Evidence extends IdKind("ev")
  case MatchDecision extends IdKind("match")
  case Bundle extends IdKind("bundle")
  case Claim extends IdKind("claim")
  case Answer extends IdKind("answer")
  case Query extends IdKind("query")
  case Run extends IdKind("run")
  case SourceAuthor extends IdKind("source-author")
  case MatchCandidate extends IdKind("mcand")
  case CandidateBlock extends IdKind("block")
  case IdentityLink extends IdKind("idlink")

  /** Phase 6 (DE-P6-02, C3): a connected component of one window's coauthorship graph. */
  case Component extends IdKind("component")

  /** Phase 6 (DE-P6-02, C3): a community of one window's coauthorship graph. */
  case Community extends IdKind("community")

  /** Phase 6 (DE-P6-02, C3/C6): a bounded shortest-path evidence record. */
  case EvidencePath extends IdKind("path")

  /** Phase 6 (DE-P6-02, C6): one semantic-summary input record. */
  case SummaryInput extends IdKind("suminput")

  /** Phase 6 (DE-P6-02, C7): one downstream store load manifest. */
  case StoreLoad extends IdKind("load")
}

/** Parsing and validation of stable IDs of every kind. */
object StableId {
  type Result = Either[ValidationError, String]

  /** Frozen hash domain tags (version 1). Changing one changes every ID of that kind. */
  object Tags {
    val Researcher = "scholarchain.researcher.v1"
    val Mention = "scholarchain.mention.v1"
    val CoauthorEdge = "scholarchain.coauthor-edge.v1"
    val GraphEdge = "scholarchain.graph-edge.v1"
    val Chunk = "scholarchain.chunk.v1"
    val Evidence = "scholarchain.evidence.v1"
    val MatchDecision = "scholarchain.match-decision.v1"
    val Bundle = "scholarchain.evidence-bundle.v1"
    val Claim = "scholarchain.claim.v1"
    val Answer = "scholarchain.answer.v1"
    val Run = "scholarchain.run.v1"
    val MatchCandidate = "scholarchain.match-candidate.v1"
    val CandidateBlock = "scholarchain.candidate-block.v1"
    val IdentityLink = "scholarchain.identity-link.v1"
    val Component = "scholarchain.component.v1"
    val Community = "scholarchain.community.v1"
    val EvidencePath = "scholarchain.evidence-path.v1"
    val SummaryInput = "scholarchain.summary-input.v1"
    val StoreLoad = "scholarchain.store-load.v2"

    /** DE-P6-03: the deterministic BFS source sample of a path-length estimate (not an ID). */
    val PathSample = "scholarchain.path-sample.v1"

    /** DE-P6-03: work and topic summary inputs (prefix `suminput`, separate tag). */
    val EntitySummaryInput = "scholarchain.entity-summary-input.v1"
  }

  private val Token = "^[A-Za-z0-9._-]+$".r
  private val QueryToken = "^[A-Za-z0-9._-]{1,128}$".r
  private val IdentityVersion = "^v[0-9]+$".r
  private val OrcidWork = "^(.+)/work/([0-9]+)$".r

  /** Validates `id` as an ID of `kind`; `field` names the location for the error. */
  def validate(kind: IdKind, id: String, field: String = "id"): Result =
    reason(kind, id) match {
      case None => Right(id)
      case Some(why) =>
        Left(ValidationError.MalformedId(field, kind.prefix, String.valueOf(id), why))
    }

  /** Validation as an error vector (empty when valid). */
  def check(kind: IdKind, id: String, field: String): Vector[ValidationError] =
    validate(kind, id, field).left.toOption.toVector

  /** Kind named by the prefix, without validating the body. */
  def prefixKind(id: String): Option[IdKind] =
    if (id == null) None
    else {
      val i = id.indexOf(':')
      if (i <= 0) None else IdKind.values.find(_.prefix == id.substring(0, i))
    }

  /** Validates an ID of any kind and returns its kind. */
  def validateAny(id: String, field: String = "id"): Either[ValidationError, IdKind] =
    prefixKind(id) match {
      case None =>
        Left(ValidationError.MalformedId(field, "any", String.valueOf(id), "unknown ID prefix"))
      case Some(k) => validate(k, id, field).map(_ => k)
    }

  /** Validation of an ID that must be one of `kinds`. */
  def checkOneOf(kinds: Set[IdKind], id: String, field: String): Vector[ValidationError] =
    validateAny(id, field) match {
      case Left(e) => Vector(e)
      case Right(k) if kinds.contains(k) => Vector.empty
      case Right(k) =>
        Vector(
          ValidationError.MalformedId(
            field,
            kinds.map(_.prefix).toVector.sorted.mkString("|"),
            id,
            s"ID kind '${k.prefix}' is not allowed here"
          )
        )
    }

  /** A version or policy label: printable token `[A-Za-z0-9._-]+`. */
  def isToken(s: String): Boolean = s != null && Token.matches(s)

  private[core] def tokenOrError(field: String, s: String): Either[ValidationError, String] =
    if (isToken(s)) Right(s)
    else Left(ValidationError.InvalidValue(field, s"'$s' is not a token [A-Za-z0-9._-]+"))

  private def reason(kind: IdKind, id: String): Option[String] =
    if (!Identifiers.isPrintableAscii(id)) Some("must be non-empty printable ASCII without spaces")
    else if (!id.startsWith(kind.prefix + ":")) Some(s"must start with '${kind.prefix}:'")
    else {
      val rest = id.substring(kind.prefix.length + 1)
      kind match {
        case IdKind.Work =>
          namespaced(
            rest,
            Map(
              "dblp" -> Identifiers.isValidDblpKey,
              "doi" -> Identifiers.isCanonicalDoi,
              "openalex" -> (s => Identifiers.isValidOpenAlex('W', s)),
              "orcid-work" -> isOrcidWork
            )
          )
        case IdKind.Researcher =>
          namespaced(
            rest,
            Map(
              "orcid" -> Identifiers.isValidOrcid,
              "sc" -> isScholarChainIdentity
            )
          )
        case IdKind.Venue =>
          namespaced(
            rest,
            Map(
              "dblp" -> Identifiers.isValidDblpKey,
              "openalex" -> (s => Identifiers.isValidOpenAlex('S', s)),
              "issn" -> Identifiers.isValidIssn
            )
          )
        case IdKind.Institution =>
          namespaced(
            rest,
            Map(
              "ror" -> Identifiers.isValidRor,
              "openalex" -> (s => Identifiers.isValidOpenAlex('I', s))
            )
          )
        case IdKind.Topic =>
          namespaced(rest, Map("openalex" -> (s => Identifiers.isValidOpenAlex('T', s))))
        case IdKind.SourceAuthor =>
          namespaced(
            rest,
            SourceAuthorId.Namespaces.map { case (ns, (_, ok)) => ns -> ok }
          )
        case IdKind.Snapshot =>
          namespaced(
            rest,
            SourceType.values.filter(_.isDataset).map(s => s.tag -> isToken).toMap
          )
        case IdKind.Query =>
          if (QueryToken.matches(rest)) None
          else Some("query token must match [A-Za-z0-9._-]{1,128}")
        case _ =>
          if (Canonical.isHex64(rest)) None
          else Some("expected 64 lowercase hex characters after the prefix")
      }
    }

  private def namespaced(rest: String, rules: Map[String, String => Boolean]): Option[String] = {
    val i = rest.indexOf(':')
    if (i <= 0) Some("missing namespace")
    else {
      val ns = rest.substring(0, i)
      rules.get(ns) match {
        case None =>
          Some(s"unknown namespace '$ns' (allowed: ${rules.keys.toVector.sorted.mkString(", ")})")
        case Some(ok) => if (ok(rest.substring(i + 1))) None else Some(s"invalid $ns source ID")
      }
    }
  }

  private def isOrcidWork(s: String): Boolean = s match {
    case OrcidWork(orcid, _) => Identifiers.isValidOrcid(orcid)
    case _ => false
  }

  private def isScholarChainIdentity(s: String): Boolean = {
    val i = s.indexOf(':')
    i > 0 && IdentityVersion.matches(s.substring(0, i)) && Canonical.isHex64(s.substring(i + 1))
  }

  private[core] def hashId(kind: IdKind, fields: String*): String =
    kind.prefix + ":" + Canonical.hashFields(fields*)
}

import StableId.Result

/**
 * Work ID = `work:<namespace>:<authoritative source ID>`. Namespaces: `dblp` (record key), `doi`
 * (canonical lowercase bare DOI), `openalex` (W-ID), `orcid-work` (`<ORCID iD>/work/<put-code>`).
 * There is no hash fallback: every selected source provides an authoritative ID.
 */
object WorkId {
  def dblp(key: String): Result = StableId.validate(IdKind.Work, s"work:dblp:$key", "workId")
  def doi(raw: String): Result =
    Identifiers
      .normalizeDoi(raw)
      .left
      .map(why => ValidationError.MalformedId("workId", "work", String.valueOf(raw), why))
      .flatMap(d => StableId.validate(IdKind.Work, s"work:doi:$d", "workId"))
  def openAlex(raw: String): Result =
    Identifiers
      .normalizeOpenAlex('W', raw)
      .left
      .map(why => ValidationError.MalformedId("workId", "work", String.valueOf(raw), why))
      .flatMap(w => StableId.validate(IdKind.Work, s"work:openalex:$w", "workId"))
  def orcidWork(orcid: String, putCode: Long): Result =
    Identifiers
      .normalizeOrcid(orcid)
      .left
      .map(why => ValidationError.MalformedId("workId", "work", String.valueOf(orcid), why))
      .flatMap { o =>
        if (putCode < 0)
          Left(ValidationError.InvalidValue("putCode", s"$putCode is negative"))
        else StableId.validate(IdKind.Work, s"work:orcid-work:$o/work/$putCode", "workId")
      }
}

/**
 * Researcher ID.
 *   - Validated canonical external ID: `researcher:orcid:<bare ORCID iD>` (MOD 11-2 checked).
 *   - Otherwise a versioned ScholarChain identity ID: `researcher:sc:<identityVersion>:<hex64>`
 *     with hex64 = SHA-256(netstrings("scholarchain.researcher.v1", identityVersion,
 *     anchorNamespace, anchorValue)). The anchor (for example `dblp-pid` / `11/3019`) is chosen by
 *     the entity-resolution policy; `identityVersion` matches `v[0-9]+`.
 */
object ResearcherId {
  def orcid(raw: String): Result =
    Identifiers
      .normalizeOrcid(raw)
      .left
      .map(why =>
        ValidationError.MalformedId("researcherId", "researcher", String.valueOf(raw), why)
      )
      .map(o => s"researcher:orcid:$o")

  def scholarChain(identityVersion: String, anchorNamespace: String, anchorValue: String): Result =
    if (identityVersion == null || !identityVersion.matches("v[0-9]+"))
      Left(
        ValidationError.InvalidValue("identityVersion", s"'$identityVersion' must match v[0-9]+")
      )
    else if (!StableId.isToken(anchorNamespace))
      Left(ValidationError.InvalidValue("anchorNamespace", s"'$anchorNamespace' is not a token"))
    else if (!Identifiers.isPrintableAscii(anchorValue))
      Left(ValidationError.InvalidValue("anchorValue", "must be non-empty printable ASCII"))
    else
      Right(
        s"researcher:sc:$identityVersion:" +
          Canonical
            .hashFields(StableId.Tags.Researcher, identityVersion, anchorNamespace, anchorValue)
      )
}

/**
 * Author mention ID = `mention:` + SHA-256(netstrings("scholarchain.mention.v1", workId,
 * position)).
 */
object MentionId {
  def of(workId: String, position: Int): Result =
    for {
      w <- StableId.validate(IdKind.Work, workId, "workId")
      _ <-
        if (position >= 0) Right(position)
        else Left(ValidationError.InvalidValue("position", s"$position is negative"))
    } yield StableId.hashId(IdKind.Mention, StableId.Tags.Mention, w, position.toString)
}

/** Venue ID = `venue:<dblp|openalex|issn>:<source ID>`. */
object VenueId {
  def of(namespace: String, sourceId: String): Result =
    StableId.validate(IdKind.Venue, s"venue:$namespace:$sourceId", "venueId")
}

/** Institution ID = `institution:<ror|openalex>:<source ID>`. */
object InstitutionId {
  def of(namespace: String, sourceId: String): Result =
    StableId.validate(IdKind.Institution, s"institution:$namespace:$sourceId", "institutionId")
}

/** Topic ID = `topic:openalex:<T-ID>`. */
object TopicId {
  def of(namespace: String, sourceId: String): Result =
    StableId.validate(IdKind.Topic, s"topic:$namespace:$sourceId", "topicId")
}

/** Dataset snapshot ID = `snapshot:<dataset source tag>:<snapshot token>`. */
object SnapshotId {
  def of(source: SourceType, snapshot: String): Result =
    StableId.validate(IdKind.Snapshot, s"snapshot:${source.tag}:$snapshot", "snapshotId")
}

/** Runtime query ID = `query:<token>`, token `[A-Za-z0-9._-]{1,128}` assigned by the caller. */
object QueryId {
  def of(token: String): Result = StableId.validate(IdKind.Query, s"query:$token", "queryId")
}

/**
 * An undirected pair in canonical order: `first < second` under unsigned byte-wise comparison of
 * UTF-8 encodings ([[Canonical.compareUtf8]]). Equal endpoints are rejected. The constructor is
 * private, so every instance is canonical by construction.
 */
final class CanonicalPair private (val first: String, val second: String) {
  override def equals(other: Any): Boolean = other match {
    case p: CanonicalPair => p.first == first && p.second == second
    case _ => false
  }
  override def hashCode: Int = (first, second).##
  override def toString: String = s"CanonicalPair($first, $second)"
}

object CanonicalPair {

  /** Orders two distinct, non-empty endpoints canonically. */
  def of(x: String, y: String, field: String = "pair"): Either[ValidationError, CanonicalPair] =
    if (x == null || y == null || x.isEmpty || y.isEmpty) Left(ValidationError.MissingField(field))
    else {
      val c = Canonical.compareUtf8(x, y)
      if (c == 0) Left(ValidationError.SelfPair(field, x))
      else if (c < 0) Right(new CanonicalPair(x, y))
      else Right(new CanonicalPair(y, x))
    }

  /** Canonical pair of two valid researcher IDs. */
  def researchers(x: String, y: String): Either[ValidationError, CanonicalPair] =
    for {
      a <- StableId.validate(IdKind.Researcher, x, "researcherA")
      b <- StableId.validate(IdKind.Researcher, y, "researcherB")
      p <- of(a, b, "researcherA,researcherB")
    } yield p

  /** Errors when a stored pair `(a, b)` is not already canonical. */
  def checkStored(field: String, a: String, b: String): Vector[ValidationError] =
    if (a == null || b == null) Vector(ValidationError.MissingField(field))
    else {
      val c = Canonical.compareUtf8(a, b)
      if (c == 0) Vector(ValidationError.SelfPair(field, a))
      else if (c > 0) Vector(ValidationError.NonCanonicalPair(field, a, b))
      else Vector.empty
    }
}

/**
 * Coauthor edge ID = `coauthor:` + SHA-256(netstrings("scholarchain.coauthor-edge.v1", first,
 * second, startYear, endYear, policyVersion)); years in decimal.
 */
object CoauthorEdgeId {
  def of(pair: CanonicalPair, window: TimeWindow, policyVersion: String): Result =
    for {
      w <- TimeWindow.of(window.startYear, window.endYear)
      p <- StableId.tokenOrError("policyVersion", policyVersion)
    } yield StableId.hashId(
      IdKind.CoauthorEdge,
      StableId.Tags.CoauthorEdge,
      pair.first,
      pair.second,
      w.startYear.toString,
      w.endYear.toString,
      p
    )
}

/**
 * Graph edge ID = `gedge:` + SHA-256(netstrings("scholarchain.graph-edge.v1", edgeType tag, from,
 * to, policyVersion, sourceEvidence)). For undirected edge types the endpoints are put in canonical
 * order first, so both directions give the same ID.
 */
object GraphEdgeId {
  def of(
      edgeType: EdgeLabel,
      fromId: String,
      toId: String,
      policyVersion: String,
      sourceEvidence: String
  ): Result =
    for {
      _ <- StableId.validateAny(fromId, "fromId")
      _ <- StableId.validateAny(toId, "toId")
      p <- StableId.tokenOrError("policyVersion", policyVersion)
      e <-
        if (Identifiers.isPrintableAscii(sourceEvidence)) Right(sourceEvidence)
        else Left(ValidationError.MissingField("sourceEvidence"))
      ends <-
        if (edgeType.undirected)
          CanonicalPair.of(fromId, toId, "fromId,toId").map(c => (c.first, c.second))
        else Right((fromId, toId))
    } yield StableId.hashId(
      IdKind.GraphEdge,
      StableId.Tags.GraphEdge,
      edgeType.tag,
      ends._1,
      ends._2,
      p,
      e
    )
}

/**
 * Chunk or profile ID = `chunk:` + SHA-256(netstrings("scholarchain.chunk.v1", entityId,
 * sourceVersion, documentType tag, ordinal or "" when absent, SHA-256 hex of the UTF-8 text)).
 */
object ChunkId {
  def of(
      entityId: String,
      sourceVersion: String,
      documentType: DocumentType,
      ordinal: Option[Int],
      text: String
  ): Result =
    if (text == null) Left(ValidationError.MissingField("text"))
    else ofTextHash(entityId, sourceVersion, documentType, ordinal, Canonical.sha256HexUtf8(text))

  def ofTextHash(
      entityId: String,
      sourceVersion: String,
      documentType: DocumentType,
      ordinal: Option[Int],
      textSha256: String
  ): Result =
    for {
      e <- StableId.validateAny(entityId, "entityId").map(_ => entityId)
      v <- StableId.tokenOrError("sourceVersion", sourceVersion)
      o <- ordinal match {
        case Some(n) if n < 0 => Left(ValidationError.InvalidValue("ordinal", s"$n is negative"))
        case Some(n) => Right(n.toString)
        case None => Right("")
      }
      h <-
        if (Canonical.isHex64(textSha256)) Right(textSha256)
        else
          Left(ValidationError.InvalidValue("textSha256", "expected 64 lowercase hex characters"))
    } yield StableId.hashId(IdKind.Chunk, StableId.Tags.Chunk, e, v, documentType.tag, o, h)
}

/**
 * Evidence ID = `ev:` + SHA-256(netstrings("scholarchain.evidence.v1", evidenceType tag, sourceUri,
 * workId or "", chunkId or "", path length, path node IDs..., runId)).
 */
object EvidenceId {
  def of(
      evidenceType: EvidenceType,
      sourceUri: String,
      workId: Option[String],
      chunkId: Option[String],
      graphPath: Vector[String],
      runId: String
  ): Result =
    if (sourceUri == null || sourceUri.isEmpty) Left(ValidationError.MissingField("sourceUri"))
    else
      for {
        _ <- workId.fold[Result](Right(""))(StableId.validate(IdKind.Work, _, "workId"))
        _ <- chunkId.fold[Result](Right(""))(StableId.validate(IdKind.Chunk, _, "chunkId"))
        _ <- graphPath.zipWithIndex
          .map { case (n, i) => StableId.validateAny(n, s"graphPath[$i]") }
          .collectFirst { case Left(e) => e }
          .toLeft(())
        r <- StableId.validate(IdKind.Run, runId, "runId")
      } yield {
        val fields = Vector(
          StableId.Tags.Evidence,
          evidenceType.tag,
          sourceUri,
          workId.getOrElse(""),
          chunkId.getOrElse(""),
          graphPath.size.toString
        ) ++ graphPath :+ r
        StableId.hashId(IdKind.Evidence, fields*)
      }
}

/**
 * Match decision ID = `match:` + SHA-256(netstrings("scholarchain.match-decision.v1", entityKind
 * tag, sourceId, targetId, policyVersion, runId)).
 */
object MatchDecisionId {
  def of(
      entityKind: MatchEntityKind,
      sourceId: String,
      targetId: String,
      policyVersion: String,
      runId: String
  ): Result =
    for {
      _ <- StableId.validateAny(sourceId, "sourceId")
      _ <- StableId.validateAny(targetId, "targetId")
      p <- StableId.tokenOrError("policyVersion", policyVersion)
      r <- StableId.validate(IdKind.Run, runId, "runId")
    } yield StableId.hashId(
      IdKind.MatchDecision,
      StableId.Tags.MatchDecision,
      entityKind.tag,
      sourceId,
      targetId,
      p,
      r
    )
}

/**
 * Evidence bundle ID = `bundle:` + SHA-256(netstrings("scholarchain.evidence-bundle.v1", queryId,
 * count, evidence IDs sorted canonically...)). Item order does not change the ID.
 */
object BundleId {
  def of(queryId: String, evidenceIds: Vector[String]): Result =
    for {
      q <- StableId.validate(IdKind.Query, queryId, "queryId")
      _ <- evidenceIds.zipWithIndex
        .map { case (e, i) => StableId.validate(IdKind.Evidence, e, s"evidenceIds[$i]") }
        .collectFirst { case Left(e) => e }
        .toLeft(())
    } yield {
      val sorted = evidenceIds.distinct.sortWith((a, b) => Canonical.compareUtf8(a, b) < 0)
      StableId.hashId(
        IdKind.Bundle,
        (Vector(StableId.Tags.Bundle, q, sorted.size.toString) ++ sorted)*
      )
    }
}

/**
 * Claim ID = `claim:` + SHA-256(netstrings("scholarchain.claim.v1", queryId, ordinal, SHA-256 hex
 * of text)).
 */
object ClaimId {
  def of(queryId: String, ordinal: Int, text: String): Result =
    for {
      q <- StableId.validate(IdKind.Query, queryId, "queryId")
      _ <-
        if (ordinal >= 0) Right(ordinal)
        else Left(ValidationError.InvalidValue("ordinal", s"$ordinal is negative"))
      t <- Option(text).filter(_.nonEmpty).toRight(ValidationError.MissingField("text"))
    } yield StableId.hashId(
      IdKind.Claim,
      StableId.Tags.Claim,
      q,
      ordinal.toString,
      Canonical.sha256HexUtf8(t)
    )
}

/**
 * Answer ID = `answer:` + SHA-256(netstrings("scholarchain.answer.v1", queryId, bundleId, SHA-256
 * hex of answer text)).
 */
object AnswerId {
  def of(queryId: String, bundleId: String, answerText: String): Result =
    for {
      q <- StableId.validate(IdKind.Query, queryId, "queryId")
      b <- StableId.validate(IdKind.Bundle, bundleId, "bundleId")
      t <- Option(answerText).filter(_.nonEmpty).toRight(ValidationError.MissingField("answerText"))
    } yield StableId.hashId(IdKind.Answer, StableId.Tags.Answer, q, b, Canonical.sha256HexUtf8(t))
}

/**
 * Run ID = `run:` + SHA-256(netstrings("scholarchain.run.v1", manifest schemaVersion, stage,
 * codeVersion, configDigest, input count, (source tag, snapshot) pairs sorted canonically...,
 * parameter count, (key, value) pairs sorted by key..., attempt)). No timestamp is an input: the
 * same immutable manifest always yields the same run ID, and a deliberate re-run increments
 * `attempt`.
 */
object RunId {
  def of(manifest: RunManifest): Result = {
    val errors = RunManifest.validateFields(manifest)
    if (errors.nonEmpty) Left(errors.head)
    else {
      val inputs = manifest.inputSnapshots
        .map(s => (s.source.tag, s.snapshot))
        .sortWith { (a, b) =>
          val c = Canonical.compareUtf8(a._1, b._1)
          if (c != 0) c < 0 else Canonical.compareUtf8(a._2, b._2) < 0
        }
      val params = manifest.parameters.toVector // SortedMap: already ordered by key
      val fields =
        Vector(
          StableId.Tags.Run,
          manifest.schemaVersion,
          manifest.stage,
          manifest.codeVersion,
          manifest.configDigest,
          inputs.size.toString
        ) ++ inputs.flatMap { case (s, n) => Vector(s, n) } ++
          Vector(params.size.toString) ++ params.flatMap { case (k, v) => Vector(k, v) } :+
          manifest.attempt.toString
      Right(StableId.hashId(IdKind.Run, fields*))
    }
  }
}

/**
 * Source author profile ID = `source-author:<namespace>:<source author ID>`, the authoritative
 * author ID of one source: `openalex` (A-ID, source OpenAlex), `orcid` (bare ORCID iD of the record
 * holder, source ORCID), `dblp-pid` (DBLP person ID, source DBLP). Not a researcher ID: a profile
 * is a source record, resolved to a researcher only by entity resolution.
 */
object SourceAuthorId {

  /** Namespace -> (source dataset, value rule). */
  val Namespaces: Map[String, (SourceType, String => Boolean)] = Map(
    "openalex" -> (SourceType.OpenAlex, s => Identifiers.isValidOpenAlex('A', s)),
    "orcid" -> (SourceType.Orcid, Identifiers.isValidOrcid),
    "dblp-pid" -> (SourceType.Dblp, Identifiers.isValidDblpPid)
  )

  def of(namespace: String, sourceId: String): Result =
    StableId.validate(IdKind.SourceAuthor, s"source-author:$namespace:$sourceId", "profileId")

  def openAlex(raw: String): Result =
    Identifiers
      .normalizeOpenAlex('A', raw)
      .left
      .map(why =>
        ValidationError.MalformedId("profileId", "source-author", String.valueOf(raw), why)
      )
      .flatMap(of("openalex", _))

  def orcid(raw: String): Result =
    Identifiers
      .normalizeOrcid(raw)
      .left
      .map(why =>
        ValidationError.MalformedId("profileId", "source-author", String.valueOf(raw), why)
      )
      .flatMap(of("orcid", _))

  /** (namespace, source ID) of a valid profile ID. */
  def parts(id: String): Option[(String, String)] =
    if (StableId.validate(IdKind.SourceAuthor, id).isLeft) None
    else {
      val rest = id.stripPrefix("source-author:")
      val i = rest.indexOf(':')
      Some((rest.substring(0, i), rest.substring(i + 1)))
    }

  /** Dataset source of a valid profile ID (from its namespace). */
  def sourceOf(id: String): Option[SourceType] =
    parts(id).flatMap(p => Namespaces.get(p._1).map(_._1))
}

/**
 * Candidate block ID = `block:` + SHA-256(netstrings("scholarchain.candidate-block.v1", entityKind
 * tag, blockingVersion, level in decimal, blockKey, runId)). A block is one (entity kind, key
 * formula version, fallback level, key) group of one run.
 */
object CandidateBlockId {
  def of(
      entityKind: MatchEntityKind,
      blockingVersion: String,
      level: Int,
      blockKey: String,
      runId: String
  ): Result =
    for {
      v <- StableId.tokenOrError("blockingVersion", blockingVersion)
      _ <-
        if (level >= 0) Right(level)
        else Left(ValidationError.InvalidValue("level", s"$level is negative"))
      k <- BlockKey.checkKey("blockKey", blockKey).headOption.toLeft(blockKey)
      r <- StableId.validate(IdKind.Run, runId, "runId")
    } yield StableId.hashId(
      IdKind.CandidateBlock,
      StableId.Tags.CandidateBlock,
      entityKind.tag,
      v,
      level.toString,
      k,
      r
    )
}

/**
 * Match candidate ID = `mcand:` + SHA-256(netstrings("scholarchain.match-candidate.v1", entityKind
 * tag, first, second, blockingVersion, featureVersion, runId)), where (first, second) is the
 * canonical pair ([[CanonicalPair]]) of the two source IDs, so both input orders give one ID. Block
 * keys and feature values are not inputs: one candidate per pair, version set, and run.
 */
object MatchCandidateId {
  def of(
      entityKind: MatchEntityKind,
      pair: CanonicalPair,
      blockingVersion: String,
      featureVersion: String,
      runId: String
  ): Result =
    for {
      b <- StableId.tokenOrError("blockingVersion", blockingVersion)
      f <- StableId.tokenOrError("featureVersion", featureVersion)
      r <- StableId.validate(IdKind.Run, runId, "runId")
    } yield StableId.hashId(
      IdKind.MatchCandidate,
      StableId.Tags.MatchCandidate,
      entityKind.tag,
      pair.first,
      pair.second,
      b,
      f,
      r
    )
}

/**
 * Identity link ID = `idlink:` + SHA-256(netstrings("scholarchain.identity-link.v1", scheme tag,
 * identifier value, entityKind tag, entityId, workId or "", assertedBy source tag, runId)).
 */
object IdentityLinkId {
  def of(
      identifier: ExactId,
      entityKind: MatchEntityKind,
      entityId: String,
      workId: Option[String],
      assertedBy: SourceType,
      runId: String
  ): Result =
    for {
      _ <- ExactId.check("identifier", identifier).headOption.toLeft(())
      _ <- StableId.validateAny(entityId, "entityId")
      w <- workId.fold[Result](Right(""))(StableId.validate(IdKind.Work, _, "workId"))
      r <- StableId.validate(IdKind.Run, runId, "runId")
    } yield StableId.hashId(
      IdKind.IdentityLink,
      StableId.Tags.IdentityLink,
      identifier.scheme.tag,
      identifier.value,
      entityKind.tag,
      entityId,
      w,
      assertedBy.tag,
      r
    )
}

/** Shared input checks of the Phase 6 analysis IDs (DE-P6-02). */
private[core] object AnalysisIdInputs {
  def window(w: TimeWindow): Either[ValidationError, TimeWindow] =
    if (w == null) Left(ValidationError.MissingField("window"))
    else TimeWindow.of(w.startYear, w.endYear)

  def researchers(field: String, ids: Vector[String]): Either[ValidationError, Vector[String]] =
    if (ids == null || ids.isEmpty) Left(ValidationError.MissingField(field))
    else
      ids.zipWithIndex
        .map { case (r, i) => StableId.validate(IdKind.Researcher, r, s"$field[$i]") }
        .collectFirst { case Left(e) => e }
        .toLeft(ids)

  /** The smallest researcher ID by UTF-8 bytes: the anchor of a component or community. */
  def anchor(field: String, members: Vector[String]): Either[ValidationError, String] =
    researchers(field, members).map(
      _.reduce((a, b) => if (Canonical.compareUtf8(a, b) <= 0) a else b)
    )
}

/**
 * Component ID = `component:` + SHA-256(netstrings("scholarchain.component.v1", startYear, endYear,
 * graph policyVersion, anchor)), where anchor is the smallest member researcher ID by UTF-8 bytes.
 * The ID depends only on the window, the policy and the member set (through its anchor), never on
 * an algorithm's internal labels, so any correct connected-components implementation gives it.
 */
object ComponentId {
  def of(window: TimeWindow, policyVersion: String, members: Vector[String]): Result =
    for {
      w <- AnalysisIdInputs.window(window)
      p <- StableId.tokenOrError("policyVersion", policyVersion)
      a <- AnalysisIdInputs.anchor("members", members)
    } yield StableId.hashId(
      IdKind.Component,
      StableId.Tags.Component,
      w.startYear.toString,
      w.endYear.toString,
      p,
      a
    )
}

/**
 * Community ID = `community:` + SHA-256(netstrings("scholarchain.community.v1", startYear, endYear,
 * graph policyVersion, algorithm, parameter count, (key, value) pairs sorted by key..., anchor)),
 * anchor as for [[ComponentId]]. The algorithm and its parameters are inputs, so two algorithms or
 * two parameter settings never share a community ID.
 */
object CommunityId {
  def of(
      window: TimeWindow,
      policyVersion: String,
      algorithm: String,
      parameters: scala.collection.immutable.SortedMap[String, String],
      members: Vector[String]
  ): Result =
    for {
      w <- AnalysisIdInputs.window(window)
      p <- StableId.tokenOrError("policyVersion", policyVersion)
      alg <- StableId.tokenOrError("algorithm", algorithm)
      params <- Option(parameters).toRight(ValidationError.MissingField("parameters"))
      _ <- params.keys
        .find(k => !StableId.isToken(k))
        .map(k => ValidationError.InvalidValue(s"parameters.$k", "key not a token"))
        .toLeft(())
      a <- AnalysisIdInputs.anchor("members", members)
    } yield {
      val fields = Vector(
        StableId.Tags.Community,
        w.startYear.toString,
        w.endYear.toString,
        p,
        alg,
        params.size.toString
      ) ++ params.toVector.flatMap { case (k, v) => Vector(k, v) } :+ a
      StableId.hashId(IdKind.Community, fields*)
    }
}

/**
 * Evidence path ID = `path:` + SHA-256(netstrings("scholarchain.evidence-path.v1", startYear,
 * endYear, graph policyVersion, node count, node researcher IDs in path order...)). The node order
 * is the stored order (from the canonical source to the canonical target), so one path has one ID.
 */
object EvidencePathId {
  def of(window: TimeWindow, policyVersion: String, nodeIds: Vector[String]): Result =
    for {
      w <- AnalysisIdInputs.window(window)
      p <- StableId.tokenOrError("policyVersion", policyVersion)
      nodes <- AnalysisIdInputs.researchers("nodeIds", nodeIds)
      _ <-
        if (nodes.size >= 2) Right(())
        else Left(ValidationError.InvalidValue("nodeIds", "a path has at least two nodes"))
    } yield StableId.hashId(
      IdKind.EvidencePath,
      (Vector(
        StableId.Tags.EvidencePath,
        w.startYear.toString,
        w.endYear.toString,
        p,
        nodes.size.toString
      ) ++ nodes)*
    )
}

/**
 * Semantic-summary input ID = `suminput:` + SHA-256(netstrings("scholarchain.summary-input.v1",
 * documentType tag, subjectId, startYear, endYear, graph policyVersion)).
 */
object SummaryInputId {
  def of(
      documentType: DocumentType,
      subjectId: String,
      window: TimeWindow,
      policyVersion: String
  ): Result =
    for {
      d <- Option(documentType).toRight(ValidationError.MissingField("documentType"))
      s <- StableId.validateAny(subjectId, "subjectId").map(_ => subjectId)
      w <- AnalysisIdInputs.window(window)
      p <- StableId.tokenOrError("policyVersion", policyVersion)
    } yield StableId.hashId(
      IdKind.SummaryInput,
      StableId.Tags.SummaryInput,
      d.tag,
      s,
      w.startYear.toString,
      w.endYear.toString,
      p
    )
}

/**
 * Entity summary input ID (DE-P6-03) = `suminput:` + SHA-256(netstrings(
 * "scholarchain.entity-summary-input.v1", documentType tag, subjectId, graph policyVersion)). Work
 * and topic summaries are not windowed; the separate tag keeps them apart from [[SummaryInputId]].
 */
object EntitySummaryInputId {
  def of(documentType: DocumentType, subjectId: String, policyVersion: String): Result =
    for {
      d <- Option(documentType).toRight(ValidationError.MissingField("documentType"))
      s <- StableId.validateAny(subjectId, "subjectId").map(_ => subjectId)
      p <- StableId.tokenOrError("policyVersion", policyVersion)
    } yield StableId.hashId(
      IdKind.SummaryInput,
      StableId.Tags.EntitySummaryInput,
      d.tag,
      s,
      p
    )
}

/**
 * Sample key of one researcher for the path-length estimate of its component (DE-P6-03): 64
 * lowercase hex = SHA-256(netstrings("scholarchain.path-sample.v1", seed in decimal, startYear,
 * endYear, researcherId)). The BFS sources of a component are its `min(sampleSize, size)` members
 * with the smallest keys (ascending as strings, which equals ascending bytes for lowercase hex).
 * The key depends only on the seed, the window and the researcher, never on partitioning or
 * iteration order, so every implementation selects the same sources.
 */
object PathSampleKey {
  def of(seed: Long, window: TimeWindow, researcherId: String): Result =
    for {
      w <- AnalysisIdInputs.window(window)
      r <- StableId.validate(IdKind.Researcher, researcherId, "researcherId")
    } yield Canonical.hashFields(
      StableId.Tags.PathSample,
      seed.toString,
      w.startYear.toString,
      w.endYear.toString,
      r
    )

  /** The sample of a component: the `k` members with the smallest keys, sorted by UTF-8 bytes. */
  def sample(
      seed: Long,
      window: TimeWindow,
      members: Vector[String],
      k: Int
  ): Either[ValidationError, Vector[String]] = {
    val keyed = Option(members).getOrElse(Vector.empty).map(m => of(seed, window, m).map(_ -> m))
    keyed.collectFirst { case Left(e) => e } match {
      case Some(e) => Left(e)
      case None =>
        Right(
          keyed
            .collect { case Right(x) => x }
            .distinct
            .sortWith((a, b) =>
              if (a._1 != b._1) a._1 < b._1 else Canonical.compareUtf8(a._2, b._2) < 0
            )
            .take(math.max(k, 0))
            .map(_._2)
            .sortWith((a, b) => Canonical.compareUtf8(a, b) < 0)
        )
    }
  }
}

/**
 * Store load ID = `load:` + SHA-256(netstrings("scholarchain.store-load.v2", targetStore tag,
 * sourceRunId, partManifestSha256, item count, then for each item in ascending UTF-8 byte order of
 * its path: path, targetRole)).
 *
 * Version 2 (DE-P6-02 repair 01, CR-P6-13 finding 3): the role of every item is an input, so two
 * loads of the same parts with different role maps never share a load ID (the loader's idempotency
 * key). The v1 tag (paths only) was never committed or published and no longer exists.
 *
 * @param items
 *   (path, targetRole) of every load item; paths distinct
 */
object StoreLoadId {
  def of(
      targetStore: TargetStore,
      sourceRunId: String,
      partManifestSha256: String,
      items: Vector[(String, String)]
  ): Result =
    for {
      t <- Option(targetStore).toRight(ValidationError.MissingField("targetStore"))
      r <- StableId.validate(IdKind.Run, sourceRunId, "sourceRunId")
      h <-
        if (partManifestSha256 != null && Canonical.isHex64(partManifestSha256))
          Right(partManifestSha256)
        else
          Left(
            ValidationError.InvalidValue(
              "partManifestSha256",
              "expected 64 lowercase hex characters"
            )
          )
      ps <-
        if (items == null || items.isEmpty || items.exists(i => i == null || i._1 == null))
          Left(ValidationError.MissingField("items"))
        else if (items.exists(i => !StableId.isToken(i._2)))
          Left(ValidationError.InvalidValue("items.targetRole", "missing or not a token"))
        else if (items.map(_._1).distinct.size != items.size)
          Left(ValidationError.InvalidValue("items.path", "contains duplicate values"))
        else Right(items.sortWith((a, b) => Canonical.compareUtf8(a._1, b._1) < 0))
    } yield StableId.hashId(
      IdKind.StoreLoad,
      (Vector(StableId.Tags.StoreLoad, t.tag, r, h, ps.size.toString) ++
        ps.flatMap { case (path, role) => Vector(path, role) })*
    )
}
