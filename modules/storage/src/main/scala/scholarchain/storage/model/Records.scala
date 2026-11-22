package scholarchain.storage.model

import upickle.default.ReadWriter
import upickle.implicits.allowUnknownKeys

import scholarchain.core.EvidenceItem

/**
 * One stored object (ArtifactRegistry row; README L1370): where it is, its content hash and size,
 * and the run, load and source path it came from.
 *
 * @param loadId
 *   the load that wrote it (absent for run outputs registered without a load)
 * @param sourcePath
 *   the part path inside the source run directory, when the object copies one
 */
@allowUnknownKeys(false)
final case class ArtifactRecord(
    schemaVersion: String,
    runId: String,
    loadId: Option[String],
    zone: String,
    bucket: String,
    key: String,
    sha256: String,
    byteCount: Long,
    contentType: String,
    sourcePath: Option[String],
    recordSchemaVersion: Option[String]
) derives ReadWriter

object ArtifactRecord {
  val SchemaVersion = "scholarchain.storage.artifact.v1"
}

/** One evidence item row of an EvidenceBundle (sort key `item#<evidenceId>`). */
@allowUnknownKeys(false)
final case class EvidenceItemRow(
    schemaVersion: String,
    bundleId: String,
    queryId: String,
    item: EvidenceItem
) derives ReadWriter

object EvidenceItemRow {
  val SchemaVersion = "scholarchain.storage.evidence-item-row.v1"
}

/**
 * A runtime query session (QuerySession, TTL `expiresAt`).
 *
 * @param status
 *   a token chosen by the runtime (for example `planned`, `answered`)
 * @param expiresAtEpochSeconds
 *   TTL instant, epoch seconds
 */
@allowUnknownKeys(false)
final case class QuerySessionRecord(
    schemaVersion: String,
    queryId: String,
    windowStartYear: Int,
    windowEndYear: Int,
    status: String,
    planSha256: Option[String],
    bundleId: Option[String],
    createdAtMillis: Long,
    expiresAtEpochSeconds: Long
) derives ReadWriter

object QuerySessionRecord {
  val SchemaVersion = "scholarchain.storage.query-session.v1"
}

/** One executed plan step of a session (QuerySession, sort key `step#NNNN`). */
@allowUnknownKeys(false)
final case class QueryStepRecord(
    schemaVersion: String,
    queryId: String,
    ordinal: Int,
    operation: String,
    status: String,
    evidenceIds: Vector[String],
    expiresAtEpochSeconds: Long
) derives ReadWriter

object QueryStepRecord {
  val SchemaVersion = "scholarchain.storage.query-step.v1"
}

/**
 * SE-P9-02: the audit record of one query (QuerySession, sort key `audit`, access pattern QS-3),
 * written create-once by the runtime audit stage. The audit body is opaque to storage: it is stored
 * exactly as given, bound to its SHA-256 and to its query, and never interpreted beyond being one
 * JSON object (storage must not depend on the module that defines it).
 *
 * @param auditSchemaVersion
 *   the schema of `auditJson` (a token, for example `scholarchain.rag.audit-record.v1`)
 * @param auditJson
 *   the audit record's compact JSON, at most [[AuditBounds.CeilingBytes]] UTF-8 bytes (and at most
 *   the configured [[AuditBounds]] when written through the runtime writer)
 * @param auditSha256
 *   SHA-256 of the UTF-8 bytes of `auditJson`
 * @param expiresAtEpochSeconds
 *   TTL instant, epoch seconds: the same value as the query's session and step rows
 */
@allowUnknownKeys(false)
final case class QueryAuditRecord(
    schemaVersion: String,
    queryId: String,
    auditSchemaVersion: String,
    auditJson: String,
    auditSha256: String,
    expiresAtEpochSeconds: Long
) derives ReadWriter

object QueryAuditRecord {
  val SchemaVersion = "scholarchain.storage.query-audit.v1"

  /** Builds the row for `auditJson`, computing its hash (the caller still passes the TTL). */
  def of(
      queryId: String,
      auditSchemaVersion: String,
      auditJson: String,
      expiresAtEpochSeconds: Long
  ): QueryAuditRecord =
    QueryAuditRecord(
      SchemaVersion,
      queryId,
      auditSchemaVersion,
      auditJson,
      scholarchain.core.Canonical.sha256HexUtf8(Option(auditJson).getOrElse("")),
      expiresAtEpochSeconds
    )

  /** UTF-8 byte length of the audit body. */
  def jsonBytes(a: QueryAuditRecord): Long =
    Option(a.auditJson)
      .map(_.getBytes(java.nio.charset.StandardCharsets.UTF_8).length.toLong)
      .getOrElse(0L)

  /** Why `a` is not a valid audit row (independent of any configured bound); empty when valid. */
  def problems(a: QueryAuditRecord): Vector[String] =
    if (a == null) Vector("audit: missing")
    else {
      val bytes = jsonBytes(a)
      val json =
        if (a.auditJson == null || a.auditJson.isEmpty) None
        else
          try Some(ujson.read(a.auditJson))
          catch { case _: Exception => None }
      Vector(
        Option.when(!scholarchain.core.StableId.isToken(a.auditSchemaVersion))(
          s"auditSchemaVersion: '${a.auditSchemaVersion}' is not a token [A-Za-z0-9._-]+"
        ),
        Option.when(a.auditJson == null || a.auditJson.isEmpty)("auditJson: missing or empty"),
        Option.when(bytes > AuditBounds.CeilingBytes)(
          s"auditJson: $bytes bytes exceed the ceiling of ${AuditBounds.CeilingBytes}"
        ),
        Option.when(
          a.auditJson != null && a.auditJson.nonEmpty && !json.exists(_.objOpt.isDefined)
        )(
          "auditJson: not one JSON object"
        ),
        json.flatMap(_.objOpt).flatMap(_.get("queryId")).flatMap { q =>
          Option.when(q.strOpt != Some(a.queryId))(
            "auditJson: its queryId is not the row's queryId"
          )
        },
        Option.when(!scholarchain.core.Canonical.isHex64(a.auditSha256))(
          "auditSha256: expected 64 lowercase hex characters"
        ),
        Option.when(
          a.auditJson != null && scholarchain.core.Canonical.isHex64(a.auditSha256) &&
            scholarchain.core.Canonical.sha256HexUtf8(a.auditJson) != a.auditSha256
        )("auditSha256: not the SHA-256 of auditJson")
      ).flatten
    }
}

/**
 * SE-P9-02: the configured size bound of one audit body (configuration; no default).
 *
 * @param maxAuditJsonBytes
 *   the largest `auditJson` the runtime writer accepts, in UTF-8 bytes, 1..[[CeilingBytes]]
 */
final case class AuditBounds private (maxAuditJsonBytes: Long)

object AuditBounds {

  /**
   * Storage's own hard cap on an audit body (256 KiB), enforced by the codec on every encode and
   * decode whatever the configuration says. It is a storage design limit, not an AWS figure: the
   * DynamoDB item size limit it must stay under was NOT re-verified in SE-P9-02 (the aws-docs MCP
   * server failed to connect on 2026-11-21); see the SE-P9-02 report.
   */
  val CeilingBytes: Long = 262144L

  def of(maxAuditJsonBytes: Long): Either[StoreError, AuditBounds] =
    if (maxAuditJsonBytes < 1)
      Left(StoreError.Refused(s"maxAuditJsonBytes: $maxAuditJsonBytes is below 1"))
    else if (maxAuditJsonBytes > CeilingBytes)
      Left(
        StoreError.Refused(
          s"maxAuditJsonBytes: $maxAuditJsonBytes exceeds the storage ceiling $CeilingBytes"
        )
      )
    else Right(new AuditBounds(maxAuditJsonBytes))
}

/**
 * One cached surface-form to researcher link (EntityLinkCache, TTL `expiresAt`). The surface form
 * is stored only as its SHA-256; `matchedBy` names the lookup that produced the link.
 *
 * @param evidence
 *   SE-P9-01: where a loader-written link comes from ([[EntityLinkEvidence]]); absent on links
 *   written by the runtime cache (the field is omitted from the JSON when absent, so such bodies
 *   are byte-identical to the pre-SE-P9-01 form)
 */
@allowUnknownKeys(false)
final case class EntityLinkRecord(
    schemaVersion: String,
    surfaceSha256: String,
    researcherId: String,
    matchedBy: String,
    candidateRank: Int,
    sourceRunId: String,
    expiresAtEpochSeconds: Long,
    evidence: Option[EntityLinkEvidence] = None
) derives ReadWriter

object EntityLinkRecord {
  val SchemaVersion = "scholarchain.storage.entity-link.v1"
}

/**
 * SE-P9-01: the provenance of one loader-written entity link: the entity-resolution outputs it was
 * derived from. Only IDs, versions and hashes; never a name.
 *
 * @param surfaceFormVersion
 *   the normalization the surface hash was computed with ([[EntityLinkSurfaceForm.Version]])
 * @param loadId
 *   the entity-link load that wrote it (its own RunLedger row)
 * @param groupingRunId
 *   the grouping (entity-resolution) run whose researcher assignments name the researcher
 * @param curatedRunId
 *   the curated Hadoop run whose author mentions carry the name
 * @param groupingPolicyVersion
 *   the grouping policy of those assignments
 * @param matchPolicyVersion
 *   the match policy of the decisions they rest on
 * @param sourceDatasets
 *   the source datasets of the supporting mentions (tags, sorted, distinct)
 * @param snapshots
 *   their dataset snapshots (sorted, distinct)
 * @param mentionCount
 *   the number of supporting mentions (mentions of the researcher whose name has this surface form)
 * @param mentionIds
 *   the supporting mention IDs (the source record IDs of the curated run, graph `AuthorMention`
 *   nodes), sorted by UTF-8 bytes, at most the configured number: the first ones of the full list
 * @param mentionIdsSha256
 *   SHA-256 of every supporting mention ID, sorted, each followed by LF (the full list's hash even
 *   when `mentionIds` is cut)
 */
@allowUnknownKeys(false)
final case class EntityLinkEvidence(
    schemaVersion: String,
    surfaceFormVersion: String,
    loadId: String,
    groupingRunId: String,
    curatedRunId: String,
    groupingPolicyVersion: String,
    matchPolicyVersion: String,
    sourceDatasets: Vector[String],
    snapshots: Vector[String],
    mentionCount: Int,
    mentionIds: Vector[String],
    mentionIdsSha256: String
) derives ReadWriter

object EntityLinkEvidence {
  val SchemaVersion = "scholarchain.storage.entity-link-evidence.v1"

  /** The hash of a full, sorted mention-ID list (each ID followed by LF). */
  def mentionIdsSha256(sortedIds: Vector[String]): String =
    scholarchain.core.Canonical.sha256HexUtf8(sortedIds.map(_ + "\n").mkString)

  private def ascending(xs: Vector[String]): Boolean =
    xs.zip(xs.drop(1)).forall { case (a, b) => scholarchain.core.Canonical.compareUtf8(a, b) < 0 }

  /** Why `e` is not a valid evidence record, in field order; empty when valid. */
  def problems(e: EntityLinkEvidence): Vector[String] =
    if (e == null) Vector("evidence: missing")
    else {
      def tokens(name: String, xs: Vector[String]): Vector[String] =
        if (xs == null || xs.isEmpty) Vector(s"evidence.$name: missing or empty")
        else if (xs.exists(x => !scholarchain.core.StableId.isToken(x)))
          Vector(s"evidence.$name: not tokens")
        else if (!ascending(xs)) Vector(s"evidence.$name: not sorted and distinct")
        else Vector.empty
      def id(kind: scholarchain.core.IdKind, v: String, f: String): Vector[String] =
        StoreIds.require(kind, v, s"evidence.$f").left.toOption.toVector.map(_.message)
      val ids = Option(e.mentionIds).getOrElse(Vector.empty)
      Vector(
        Option.when(e.schemaVersion != SchemaVersion)(
          s"evidence.schemaVersion: '${e.schemaVersion}'"
        ),
        Option.when(e.surfaceFormVersion != EntityLinkSurfaceForm.Version)(
          s"evidence.surfaceFormVersion: '${e.surfaceFormVersion}'"
        ),
        Option.when(!scholarchain.core.StableId.isToken(e.groupingPolicyVersion))(
          "evidence.groupingPolicyVersion: not a token"
        ),
        Option.when(!scholarchain.core.StableId.isToken(e.matchPolicyVersion))(
          "evidence.matchPolicyVersion: not a token"
        ),
        Option.when(e.mentionIds == null || ids.isEmpty)("evidence.mentionIds: missing or empty"),
        Option.when(!ascending(ids))("evidence.mentionIds: not sorted and distinct"),
        Option.when(e.mentionCount < ids.size)("evidence.mentionCount: below the listed mentions"),
        Option.when(!scholarchain.core.Canonical.isHex64(e.mentionIdsSha256))(
          "evidence.mentionIdsSha256: not 64 lowercase hex"
        ),
        Option.when(
          e.mentionCount == ids.size && ids.nonEmpty && mentionIdsSha256(ids) != e.mentionIdsSha256
        )("evidence.mentionIdsSha256: not the hash of the complete mention list")
      ).flatten ++
        id(scholarchain.core.IdKind.StoreLoad, e.loadId, "loadId") ++
        id(scholarchain.core.IdKind.Run, e.groupingRunId, "groupingRunId") ++
        id(scholarchain.core.IdKind.Run, e.curatedRunId, "curatedRunId") ++
        tokens("sourceDatasets", e.sourceDatasets) ++
        tokens("snapshots", e.snapshots) ++
        ids.zipWithIndex.flatMap { case (m, i) =>
          id(scholarchain.core.IdKind.Mention, m, s"mentionIds[$i]")
        }
    }
}

/**
 * SE-P9-01: the surface form of a researcher name, the partition key of the entity-link cache
 * (EntityLinkCache EL-1, `surface#<sha256>`). It must be byte-identical to the runtime's lookup
 * normalization (rag `SurfaceForms`, version `scholarchain.rag.surface-form.v1`), so storage
 * implements the same rules under the same version tag (storage may not depend on rag): Unicode
 * NFKC, lower case (ROOT locale), `.`, `,`, `;` and `:` become spaces, ASCII whitespace runs (Java
 * `\s`) collapse to one space, trimmed (`String.trim`). Diacritics are kept. Only the hash is
 * stored, never the name. A change here is a new version, made in both modules together.
 */
object EntityLinkSurfaceForm {
  val Version = "scholarchain.rag.surface-form.v1"

  def normalize(name: String): String =
    java.text.Normalizer
      .normalize(Option(name).getOrElse(""), java.text.Normalizer.Form.NFKC)
      .toLowerCase(java.util.Locale.ROOT)
      .replaceAll("[.,;:]", " ")
      .replaceAll("\\s+", " ")
      .trim

  def sha256(name: String): String = scholarchain.core.Canonical.sha256HexUtf8(normalize(name))
}

/**
 * One analysis result of a published run in the analysis-results table (SE-P7-07; table role
 * ResearcherMetricCache, the README's optional metric table, used as the one analysis-results table
 * with typed keys). Every DynamoDB load role of the run maps to one such row per record.
 *
 * @param scope
 *   `subject` (keyed by a stable ID: researcher, coauthor edge, evidence path or component) or
 *   `window` (keyed by the window itself: analysis windows, window structures, window comparisons)
 * @param scopeId
 *   the subject's stable ID, or for `window` scope the window key itself
 * @param window
 *   `SSSS-EEEE`; for window comparisons and temporal changes the `from` window
 * @param toWindow
 *   the `to` window of a comparison or temporal change
 * @param record
 *   the core record's compact JSON (core `Json.write`), exactly as decoded from the verified part
 * @param recordSha256
 *   SHA-256 of `record` (the evidence content hash a citation can check)
 * @param groupId
 *   component or community of a membership row (GSI byGroup)
 * @param pair
 *   canonical researcher pair of a coauthor-edge or evidence-path row (GSI byPair)
 * @param rank
 *   1-based position of a ranked role's row in its window (GSI rank)
 */
@allowUnknownKeys(false)
final case class AnalysisResultRecord(
    schemaVersion: String,
    role: String,
    scope: String,
    scopeId: String,
    window: String,
    toWindow: Option[String],
    record: String,
    recordSha256: String,
    recordSchemaVersion: String,
    groupId: Option[String],
    pair: Option[Vector[String]],
    rank: Option[Int],
    runId: String,
    loadId: String,
    sourcePath: String
) derives ReadWriter

object AnalysisResultRecord {
  val SchemaVersion = "scholarchain.storage.analysis-result.v1"
  val SubjectScope = "subject"
  val WindowScope = "window"
}
