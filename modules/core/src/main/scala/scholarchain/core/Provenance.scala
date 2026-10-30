package scholarchain.core

import scala.collection.immutable.SortedMap

import upickle.default.ReadWriter
import upickle.implicits.allowUnknownKeys

import Validated.{all, at, nonEmpty, when}

/**
 * Where a record came from. Present on every data record.
 *
 * @param sourceDataset
 *   dataset of the source record, or `scholarchain-derived` / `scholarchain-runtime` for records
 *   ScholarChain computed
 * @param sourceRecordId
 *   ID of the source record in that dataset (for derived records, the ID of the primary input, for
 *   example a work ID or a query ID)
 * @param snapshot
 *   dataset snapshot token, for example `dblp-2026-10-03`
 * @param runId
 *   run that produced the record (`run:<hex64>`)
 * @param sourceSchemaVersion
 *   schema version of the input record. For a dataset source it is that dataset's `schema_version`
 *   in `datasets/dataset-manifest.yaml`, of the form `<source tag>-raw/<semver>` (for example
 *   `dblp-raw/1.0.0`), and its tag must equal `sourceDataset`. For `scholarchain-derived` and
 *   `scholarchain-runtime` it is the ScholarChain record schema of the primary input, of the form
 *   `scholarchain.<record>.v<N>` (see [[SchemaVersions]]). Checked by
 *   [[Provenance.sourceSchemaVersionProblem]].
 * @param policyVersion
 *   transformation or match policy version, when one applies
 * @param modelVersion
 *   model ID used, when a model was involved
 * @param promptVersion
 *   prompt version used, when an LLM was involved
 */
@allowUnknownKeys(false)
final case class Provenance(
    sourceDataset: SourceType,
    sourceRecordId: String,
    snapshot: String,
    runId: String,
    sourceSchemaVersion: String,
    policyVersion: Option[String],
    modelVersion: Option[String],
    promptVersion: Option[String]
) derives ReadWriter

object Provenance {
  def check(recordType: String, field: String, p: Provenance): Vector[ValidationError] =
    if (p == null) Vector(ValidationError.MissingProvenance(recordType, field))
    else
      all(
        when(p.sourceDataset == null)(ValidationError.MissingField(at(field, "sourceDataset"))),
        nonEmpty(at(field, "sourceRecordId"), p.sourceRecordId),
        when(!StableId.isToken(p.snapshot))(
          ValidationError.InvalidValue(at(field, "snapshot"), "missing or not a token")
        ),
        StableId.check(IdKind.Run, p.runId, at(field, "runId")),
        nonEmpty(at(field, "sourceSchemaVersion"), p.sourceSchemaVersion),
        (if (p.sourceDataset == null || p.sourceSchemaVersion == null) None
         else sourceSchemaVersionProblem(p.sourceDataset, p.sourceSchemaVersion)).toVector.map {
          why => ValidationError.InvalidValue(at(field, "sourceSchemaVersion"), why)
        },
        optionalNonEmpty(at(field, "policyVersion"), p.policyVersion),
        optionalNonEmpty(at(field, "modelVersion"), p.modelVersion),
        optionalNonEmpty(at(field, "promptVersion"), p.promptVersion)
      )

  /** Raw dataset schema: `<source tag>-raw/<MAJOR>.<MINOR>.<PATCH>` (no leading zeros). */
  private val RawSchema = "^([a-z0-9-]+)-raw/(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$".r

  /** ScholarChain record schema: `scholarchain.<kebab-case record>.v<N>`, N >= 1. */
  private val RecordSchema = "^scholarchain\\.[a-z0-9]+(-[a-z0-9]+)*\\.v[1-9][0-9]*$".r

  /**
   * Why `version` is not an acceptable source schema version for `source`, or None when it is.
   * Dataset sources need `<source tag>-raw/<semver>` with their own tag; non-dataset sources need a
   * `scholarchain.<record>.v<N>` record schema. Blank values are reported separately as missing.
   */
  def sourceSchemaVersionProblem(source: SourceType, version: String): Option[String] =
    if (version == null || version.trim.isEmpty) None
    else if (source.isDataset)
      version match {
        case RawSchema(tag, _, _, _) if tag == source.tag => None
        case RawSchema(tag, _, _, _) =>
          Some(s"'$version' names dataset '$tag', but sourceDataset is '${source.tag}'")
        case _ => Some(s"'$version' is not of the form ${source.tag}-raw/<MAJOR.MINOR.PATCH>")
      }
    else if (RecordSchema.matches(version)) None
    else Some(s"'$version' is not a ScholarChain record schema scholarchain.<record>.v<N>")

  private def optionalNonEmpty(field: String, v: Option[String]): Vector[ValidationError] =
    if (v == null) Vector(ValidationError.MissingField(field))
    else v.toVector.flatMap(nonEmpty(field, _))
}

/** Inclusive year window `[startYear, endYear]`. A single year has `startYear == endYear`. */
@allowUnknownKeys(false)
final case class TimeWindow(startYear: Int, endYear: Int) derives ReadWriter {
  def contains(year: Int): Boolean = year >= startYear && year <= endYear
}

object TimeWindow {

  /** Smart constructor: rejects `startYear > endYear` and years outside [0, 9999]. */
  def of(startYear: Int, endYear: Int): Either[ValidationError, TimeWindow] = {
    val errors = check("window", TimeWindow(startYear, endYear))
    if (errors.isEmpty) Right(TimeWindow(startYear, endYear)) else Left(errors.head)
  }

  def check(field: String, w: TimeWindow): Vector[ValidationError] =
    if (w == null) Vector(ValidationError.MissingField(field))
    else
      all(
        Validated.year(at(field, "startYear"), w.startYear),
        Validated.year(at(field, "endYear"), w.endYear),
        when(w.startYear > w.endYear)(ValidationError.InvalidWindow(field, w.startYear, w.endYear))
      )
}

/** An external identifier in its canonical form for the scheme. */
@allowUnknownKeys(false)
final case class ExternalId(scheme: ExternalIdScheme, value: String) derives ReadWriter

object ExternalId {
  def check(field: String, e: ExternalId): Vector[ValidationError] =
    if (e == null || e.scheme == null) Vector(ValidationError.MissingField(field))
    else
      Identifiers.validateExternal(e.scheme, e.value) match {
        case Right(_) => Vector.empty
        case Left(why) =>
          Vector(ValidationError.MalformedId(field, e.scheme.tag, String.valueOf(e.value), why))
      }
}

/** One input dataset snapshot of a run. */
@allowUnknownKeys(false)
final case class SnapshotRef(source: SourceType, snapshot: String) derives ReadWriter

/**
 * Immutable run manifest. The run ID is derived from it ([[RunId.of]]) and contains no timestamp.
 * Stored next to the run's outputs. It has no Provenance: it is the root of provenance.
 *
 * @param stage
 *   pipeline stage token, for example `hadoop.dblp-extract`
 * @param codeVersion
 *   full 40-hex git commit SHA of the code that runs
 * @param configDigest
 *   [[scholarchain.core.config.ScholarChainConfig.digest]] of the resolved config
 * @param parameters
 *   stage parameters; keys are tokens
 * @param attempt
 *   1 for the first run of this manifest; incremented for a deliberate re-run
 */
@allowUnknownKeys(false)
final case class RunManifest(
    schemaVersion: String,
    stage: String,
    codeVersion: String,
    configDigest: String,
    inputSnapshots: Vector[SnapshotRef],
    parameters: SortedMap[String, String],
    attempt: Int
) derives ReadWriter

object RunManifest {

  /** Full validation, including the schema version. */
  def validateFields(m: RunManifest): Vector[ValidationError] = contract.validate(m)

  private def checks(m: RunManifest): Vector[ValidationError] =
    all(
      when(!StableId.isToken(m.stage))(ValidationError.InvalidValue("stage", "not a token")),
      when(!Canonical.isHex40(m.codeVersion))(
        ValidationError.InvalidValue("codeVersion", "expected a 40-hex lowercase git SHA")
      ),
      when(!Canonical.isHex64(m.configDigest))(
        ValidationError.InvalidValue("configDigest", "expected 64 lowercase hex characters")
      ),
      when(m.inputSnapshots == null)(ValidationError.MissingField("inputSnapshots")),
      Option(m.inputSnapshots).toVector.flatten.zipWithIndex.flatMap { case (s, i) =>
        if (s == null || s.source == null || !s.source.isDataset || !StableId.isToken(s.snapshot))
          Vector(ValidationError.InvalidValue(s"inputSnapshots[$i]", "not a dataset snapshot"))
        else Vector.empty
      },
      Validated.distinct(
        "inputSnapshots",
        Option(m.inputSnapshots).toVector.flatten.filter(_ != null).map(_.toString)
      ),
      when(m.parameters == null)(ValidationError.MissingField("parameters")),
      Option(m.parameters).toVector.flatMap(_.keys).flatMap { k =>
        when(!StableId.isToken(k))(
          ValidationError.InvalidValue(s"parameters.$k", "key not a token")
        )
      },
      when(m.attempt < 1)(ValidationError.InvalidValue("attempt", s"${m.attempt} is below 1"))
    )

  given contract: RecordContract[RunManifest] =
    new RecordContract[RunManifest]("RunManifest", SchemaVersions.RunManifest, false) {
      def schemaVersionOf(t: RunManifest): String = t.schemaVersion
      def provenanceOf(t: RunManifest): Provenance = null
      protected def fields(t: RunManifest, at: String): Vector[ValidationError] = checks(t)
    }
}
