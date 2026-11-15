package scholarchain.hadoop

import java.nio.charset.Charset

import org.apache.hadoop.conf.Configuration

import scholarchain.core.{IdKind, MatchEntityKind, SourceType, StableId}

/** A job parameter that is missing or invalid. */
final case class ParamError(key: String, reason: String) {
  def message: String = s"job parameter '$key' $reason"
}

/** What the blocking stage does with a block still oversize after its last fallback level. */
enum BlockOversizePolicy(val tag: String) {

  /** Do not emit the block; flag it and count its members as dropped. */
  case Drop extends BlockOversizePolicy("drop")

  /** Fail the job. */
  case Fail extends BlockOversizePolicy("fail")
}

/**
 * Entity that the blocking and candidate stages group. Every run blocks both entities, each in its
 * own output directories (`<output>/<tag>/`).
 */
enum BlockingEntity(val tag: String, val kind: MatchEntityKind) {
  case Researcher extends BlockingEntity("researcher", MatchEntityKind.Researcher)
  case Work extends BlockingEntity("work", MatchEntityKind.Work)
}

object BlockingEntity {
  def fromTag(tag: String): BlockingEntity =
    values
      .find(_.tag == tag)
      .getOrElse(throw new IllegalStateException(s"unknown blocking entity '$tag'"))
}

/**
 * Hadoop job parameters. Every value comes from the run manifest's `parameters` (copied into the
 * Hadoop `Configuration` by the driver), so a parameter change changes the run ID. Nothing has a
 * built-in default: a missing key is an error. Where "no restriction" is meaningful it must be
 * written explicitly as `*`.
 */
object JobParams {

  // ---- set by the driver from the run manifest (not manifest parameters) ----
  val RunIdKey = "scholarchain.run.id"
  def snapshotKey(s: SourceType): String = s"scholarchain.snapshot.${s.tag}"

  /** Blocking round being executed (set by the driver per round). */
  val BlockingRound = "scholarchain.blocking.round"

  // ---- manifest parameters ----
  val DblpRecordTypes = "scholarchain.dblp.recordTypes"
  val DblpYearRange = "scholarchain.dblp.yearRange"
  val DblpVenues = "scholarchain.dblp.venues"
  val DblpEncoding = "scholarchain.dblp.encoding"
  val DblpMaxRecordBytes = "scholarchain.dblp.maxRecordBytes"
  val OrcidMaxRecordBytes = "scholarchain.orcid.maxRecordBytes"
  val RejectMaxExcerptChars = "scholarchain.reject.maxExcerptChars"
  val TeamSources = "scholarchain.team.sources"
  val TeamMaxSize = "scholarchain.team.maxSize"
  val BlockingKeySourceClass = "scholarchain.blocking.keySource"
  val BlockingKeySourceVersion = "scholarchain.blocking.keySourceVersion"
  val BlockingMaxBlockSize = "scholarchain.blocking.maxBlockSize"
  val BlockingOversizePolicyKey = "scholarchain.blocking.oversizePolicy"
  val CandidatesFeatureVersion = "scholarchain.candidates.featureVersion"
  val ReducersNormalize = "scholarchain.reducers.normalize"
  val ReducersExactIds = "scholarchain.reducers.exactIds"
  val ReducersTeams = "scholarchain.reducers.teams"
  val ReducersBlocking = "scholarchain.reducers.blocking"
  val ReducersCandidates = "scholarchain.reducers.candidates"
  val CodecIntermediate = "scholarchain.codec.intermediate"
  val CodecOutput = "scholarchain.codec.output"

  /** Parameters every run needs. */
  val Common: Vector[String] = Vector(RejectMaxExcerptChars, CodecIntermediate, CodecOutput)

  /** Parameters each stage needs in addition to [[Common]]. */
  val ByStage: Map[String, Vector[String]] = Map(
    "normalize" -> Vector(
      DblpRecordTypes,
      DblpYearRange,
      DblpVenues,
      DblpEncoding,
      DblpMaxRecordBytes,
      OrcidMaxRecordBytes,
      ReducersNormalize
    ),
    "exact-ids" -> Vector(ReducersExactIds),
    "teams" -> Vector(TeamSources, TeamMaxSize, ReducersTeams),
    "blocking" -> Vector(
      BlockingKeySourceClass,
      BlockingKeySourceVersion,
      BlockingMaxBlockSize,
      BlockingOversizePolicyKey,
      ReducersBlocking
    ),
    "candidates" -> Vector(CandidatesFeatureVersion, BlockingMaxBlockSize, ReducersCandidates)
  )

  /**
   * Input-set labels (optional). No job reads them; they are recorded in the run manifest, so they
   * change the run ID. `InputSplit` names the selection split of the inputs (for example `dev` or
   * `heldout` of the ER candidate samples) and `InputDigest` binds the exact input files (64 hex).
   * The local split entry point (test tree, `scholarchain.hadoop.local`) requires and verifies
   * both.
   */
  val InputSplit = "scholarchain.input.split"
  val InputDigest = "scholarchain.input.digest"
  val Optional: Vector[String] = Vector(InputSplit, InputDigest)

  /** Every parameter a run manifest may carry. Unknown keys are rejected by the driver. */
  val ManifestKeys: Set[String] = (Common ++ ByStage.values.flatten ++ Optional).toSet

  /**
   * Encodings whose first 128 code points are ASCII bytes (the parser prepends an ASCII header).
   */
  val AsciiCompatibleEncodings: Set[String] = Set("UTF-8", "ISO-8859-1", "US-ASCII")

  // ---- typed readers ----

  def raw(conf: Configuration, key: String): Either[ParamError, String] =
    Option(conf.get(key)).map(_.trim).filter(_.nonEmpty).toRight(ParamError(key, "is not set"))

  def int(conf: Configuration, key: String, min: Int): Either[ParamError, Int] =
    raw(conf, key).flatMap { s =>
      s.toIntOption.filter(_ >= min).toRight(ParamError(key, s"'$s' is not an integer >= $min"))
    }

  /** Comma-separated, trimmed, non-empty, duplicate-free list (order kept). */
  def list(conf: Configuration, key: String, sep: Char = ','): Either[ParamError, Vector[String]] =
    raw(conf, key).flatMap { s =>
      val parts = s.split(sep).toVector.map(_.trim)
      if (parts.exists(_.isEmpty)) Left(ParamError(key, s"'$s' has an empty list element"))
      else if (parts.distinct.size != parts.size) Left(ParamError(key, s"'$s' repeats a value"))
      else Right(parts)
    }

  /** `*` means no restriction (None); otherwise a list. */
  def selection(
      conf: Configuration,
      key: String,
      sep: Char = ','
  ): Either[ParamError, Option[Vector[String]]] =
    raw(conf, key).flatMap(s => if (s == "*") Right(None) else list(conf, key, sep).map(Some(_)))

  def codecClass(conf: Configuration, key: String): Either[ParamError, String] =
    raw(conf, key).flatMap { name =>
      try {
        val c = Class.forName(name, false, getClass.getClassLoader)
        if (classOf[org.apache.hadoop.io.compress.CompressionCodec].isAssignableFrom(c)) Right(name)
        else Left(ParamError(key, s"'$name' is not a Hadoop CompressionCodec"))
      } catch {
        case _: ClassNotFoundException =>
          Left(ParamError(key, s"class '$name' is not on the classpath"))
      }
    }

  /** Throws on error: for task setup, after the driver has already validated the parameters. */
  def orThrow[A](e: Either[ParamError, A]): A =
    e.fold(err => throw new IllegalStateException(err.message), identity)
}

/** Run identity available to every task. */
final case class RunContext(
    runId: String,
    snapshot: String,
    source: SourceType,
    maxExcerptChars: Int
)

object RunContext {
  def from(conf: Configuration, source: SourceType): Either[ParamError, RunContext] =
    for {
      run <- JobParams.raw(conf, JobParams.RunIdKey)
      _ <- StableId
        .validate(IdKind.Run, run, JobParams.RunIdKey)
        .left
        .map(e => ParamError(JobParams.RunIdKey, e.message))
      snap <- JobParams.raw(conf, JobParams.snapshotKey(source))
      _ <-
        if (StableId.isToken(snap)) Right(snap)
        else Left(ParamError(JobParams.snapshotKey(source), s"'$snap' is not a token"))
      excerpt <- JobParams.int(conf, JobParams.RejectMaxExcerptChars, 0)
    } yield RunContext(run, snap, source, excerpt)
}

/** DBLP extraction settings. */
final case class DblpSettings(
    recordTypes: Set[String],
    years: Option[(Int, Int)],
    venues: Option[Set[String]],
    encoding: Charset,
    maxRecordBytes: Int
)

object DblpSettings {
  private val YearRange = "^([0-9]{4})-([0-9]{4})$".r

  def from(conf: Configuration): Either[ParamError, DblpSettings] =
    for {
      types <- JobParams.list(conf, JobParams.DblpRecordTypes)
      _ <- types.find(t => !DblpRecordTypes.Publication.contains(t)) match {
        case Some(t) =>
          Left(
            ParamError(
              JobParams.DblpRecordTypes,
              s"names '$t', which is not a DBLP publication record type " +
                s"(${DblpRecordTypes.Publication.toVector.sorted.mkString(", ")})"
            )
          )
        case None => Right(())
      }
      yearText <- JobParams.raw(conf, JobParams.DblpYearRange)
      years <- yearText match {
        case "*" => Right(None)
        case YearRange(a, b) if a.toInt <= b.toInt => Right(Some((a.toInt, b.toInt)))
        case other =>
          Left(
            ParamError(JobParams.DblpYearRange, s"'$other' is not '*' or YYYY-YYYY (start <= end)")
          )
      }
      venues <- JobParams.selection(conf, JobParams.DblpVenues, '|')
      encName <- JobParams.raw(conf, JobParams.DblpEncoding)
      enc <-
        if (JobParams.AsciiCompatibleEncodings.contains(encName)) Right(Charset.forName(encName))
        else
          Left(
            ParamError(
              JobParams.DblpEncoding,
              s"'$encName' is not one of ${JobParams.AsciiCompatibleEncodings.toVector.sorted.mkString(", ")}"
            )
          )
      max <- JobParams.int(conf, JobParams.DblpMaxRecordBytes, 1024)
    } yield DblpSettings(types.toSet, years, venues.map(_.toSet), enc, max)
}

/**
 * Teams stage settings. `maxSize` is the oversize-team reporting threshold: a larger team is
 * counted (OVERSIZE_TEAM) and flagged, and still emits all n(n-1)/2 pairs. There is no pair
 * suppression.
 */
final case class TeamSettings(sources: Set[SourceType], maxSize: Int)

object TeamSettings {
  def from(conf: Configuration): Either[ParamError, TeamSettings] =
    for {
      tags <- JobParams.list(conf, JobParams.TeamSources)
      sources <- tags.foldLeft[Either[ParamError, Vector[SourceType]]](Right(Vector.empty)) {
        (acc, t) =>
          acc.flatMap { v =>
            SourceType.fromTag(t).filter(_.isDataset) match {
              case Some(s) => Right(v :+ s)
              case None =>
                Left(
                  ParamError(JobParams.TeamSources, s"names '$t', which is not a dataset source")
                )
            }
          }
      }
      max <- JobParams.int(conf, JobParams.TeamMaxSize, 2)
    } yield TeamSettings(sources.toSet, max)
}

/**
 * Blocking stage settings. `keySourceVersion` must equal the loaded key source's `version` (checked
 * by the driver), so that the blocking-key formula version is recorded in the run manifest.
 */
final case class BlockingSettings(
    keySourceClass: String,
    keySourceVersion: String,
    maxBlockSize: Int,
    oversizePolicy: BlockOversizePolicy
)

object BlockingSettings {
  def from(conf: Configuration): Either[ParamError, BlockingSettings] =
    for {
      cls <- JobParams.raw(conf, JobParams.BlockingKeySourceClass)
      version <- JobParams.raw(conf, JobParams.BlockingKeySourceVersion)
      _ <-
        if (StableId.isToken(version)) Right(version)
        else Left(ParamError(JobParams.BlockingKeySourceVersion, s"'$version' is not a token"))
      max <- JobParams.int(conf, JobParams.BlockingMaxBlockSize, 2)
      policyTag <- JobParams.raw(conf, JobParams.BlockingOversizePolicyKey)
      policy <- BlockOversizePolicy.values
        .find(_.tag == policyTag)
        .toRight(
          ParamError(
            JobParams.BlockingOversizePolicyKey,
            s"'$policyTag' is not one of ${BlockOversizePolicy.values.map(_.tag).mkString(", ")}"
          )
        )
    } yield BlockingSettings(cls, version, max, policy)
}

/**
 * Candidate-feature stage settings. `featureVersion` must equal the feature functions' version
 * (checked by the driver). `maxBlockSize` bounds the members of one block (and so the pairs of one
 * reduce call: at most maxBlockSize * (maxBlockSize - 1) / 2).
 */
final case class CandidateSettings(featureVersion: String, maxBlockSize: Int)

object CandidateSettings {
  def from(conf: Configuration): Either[ParamError, CandidateSettings] =
    for {
      version <- JobParams.raw(conf, JobParams.CandidatesFeatureVersion)
      _ <-
        if (StableId.isToken(version)) Right(version)
        else Left(ParamError(JobParams.CandidatesFeatureVersion, s"'$version' is not a token"))
      max <- JobParams.int(conf, JobParams.BlockingMaxBlockSize, 2)
    } yield CandidateSettings(version, max)
}

/** Record element names of the DBLP DTD root declaration (schema note `dblp-raw/1.0.0`). */
object DblpRecordTypes {
  val All: Vector[String] = Vector(
    "article",
    "inproceedings",
    "proceedings",
    "book",
    "incollection",
    "phdthesis",
    "mastersthesis",
    "www",
    "person",
    "data"
  )

  /** Publication record types (the only ones that may become works). */
  val Publication: Set[String] =
    Set(
      "article",
      "inproceedings",
      "proceedings",
      "book",
      "incollection",
      "phdthesis",
      "mastersthesis"
    )
}
