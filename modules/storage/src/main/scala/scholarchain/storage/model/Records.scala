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
 * One cached surface-form to researcher link (EntityLinkCache, TTL `expiresAt`). The surface form
 * is stored only as its SHA-256; `matchedBy` names the lookup that produced the link.
 */
@allowUnknownKeys(false)
final case class EntityLinkRecord(
    schemaVersion: String,
    surfaceSha256: String,
    researcherId: String,
    matchedBy: String,
    candidateRank: Int,
    sourceRunId: String,
    expiresAtEpochSeconds: Long
) derives ReadWriter

object EntityLinkRecord {
  val SchemaVersion = "scholarchain.storage.entity-link.v1"
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
