package scholarchain.core.config

import scala.collection.immutable.SortedMap

import upickle.default.ReadWriter

import scholarchain.core.{Canonical, PartFormat, PathBounds, StableId, TimeWindow}

/**
 * Resolved ScholarChain configuration. Every value comes from versioned config files and
 * environment variables ([[ConfigLoader]]); nothing here has a built-in default. Secrets are never
 * configuration values: secrets are referenced through AWS Secrets Manager by the owning module.
 */
final case class ScholarChainConfig(
    configSchemaVersion: String,
    environment: String,
    aws: AwsConfig,
    storage: StorageConfig,
    models: ModelConfig,
    graph: GraphConfig,
    bridge: BridgeConfig,
    community: CommunityConfig,
    entityResolution: EntityResolutionConfig,
    runtime: RuntimeConfig,
    analysis: AnalysisConfig = AnalysisConfig()
) derives ReadWriter

final case class AwsConfig(region: String, accountId: String) derives ReadWriter

final case class StorageConfig(
    rawBucket: String,
    curatedBucket: String,
    contextBucket: String,
    vectorBucket: String,
    vectorIndex: String,
    dynamoTablePrefix: String,
    neptuneEndpoint: String,
    openSearchEndpoint: Option[String]
) derives ReadWriter

final case class ModelConfig(llmModelId: String, embeddingModelId: String, embeddingDimension: Int)
    derives ReadWriter

/**
 * @param windows
 *   analysis windows for the temporal graph (explicit, never defaulted)
 * @param policyVersion
 *   graph construction policy version (input of coauthor and graph edge IDs)
 * @param identityVersion
 *   version of ScholarChain identity IDs (`v[0-9]+`)
 */
final case class GraphConfig(
    windows: Vector[TimeWindow],
    policyVersion: String,
    identityVersion: String
) derives ReadWriter

final case class BridgeConfig(
    algorithm: String,
    sampleSize: Int,
    randomSeed: Long,
    maxIterations: Int
) derives ReadWriter

/**
 * @param randomSeed
 *   optional (Phase 6, DE-P6-02; analysis scope, see [[ScholarChainConfig.AnalysisScopeKeys]]):
 *   seed of a randomized community algorithm; when set it is recorded as the `randomSeed` parameter
 *   of every community membership
 */
final case class CommunityConfig(
    algorithm: String,
    maxIterations: Int,
    randomSeed: Option[Long] = None
) derives ReadWriter

/**
 * Analysis-stage settings (Phase 6, DE-P6-02; additive, optional keys within
 * `scholarchain.config.v2`, the same precedent as the grouping keys of DE-P6-01). They are optional
 * at load time, so every existing configuration loads unchanged and keeps its exact digest (an
 * unset key is omitted from the digest input). They are validated when set, and the analysis
 * feature refuses a configuration without them ([[AnalysisRunSettings.from]]).
 *
 * @param maxPathHops
 *   `analysis.maxPathHops`: longest stored shortest path (hops), >= 1
 * @param maxPathsPerPair
 *   `analysis.maxPathsPerPair`: most shortest paths stored per researcher pair and window, >= 1
 * @param maxEvidenceWorksPerHop
 *   `analysis.maxEvidenceWorksPerHop`: most evidence works shown per path hop, >= 1
 * @param exportFormat
 *   `analysis.exportFormat`: part format of the analysis exports, `parquet` or `jsonl`
 * @param pathLengthSampleSize
 *   `analysis.pathLengthSampleSize` (DE-P6-03, optional): number of BFS source researchers sampled
 *   per component for the component-level path-length estimate
 *   ([[scholarchain.core.PathLengthEstimate]]), >= 1
 * @param pathLengthSeed
 *   `analysis.pathLengthSeed` (DE-P6-03, optional): seed of the deterministic source sample
 *   ([[scholarchain.core.PathSampleKey]]), any 64-bit integer
 * @param pathLengthMinComponentSize
 *   `analysis.pathLengthMinComponentSize` (DE-P6-03, optional): smallest component (researchers)
 *   that gets an estimate, >= 2
 *
 * The three path-length keys are set together or not at all; when none is set the run stores no
 * path-length estimate, and its config digest is unchanged (unset keys are omitted from every
 * digest input).
 */
final case class AnalysisConfig(
    maxPathHops: Option[Int] = None,
    maxPathsPerPair: Option[Int] = None,
    maxEvidenceWorksPerHop: Option[Int] = None,
    exportFormat: Option[String] = None,
    pathLengthSampleSize: Option[Int] = None,
    pathLengthSeed: Option[Long] = None,
    pathLengthMinComponentSize: Option[Int] = None
) derives ReadWriter

object AnalysisConfig {
  val MaxPathHopsKey = "analysis.maxPathHops"
  val MaxPathsPerPairKey = "analysis.maxPathsPerPair"
  val MaxEvidenceWorksPerHopKey = "analysis.maxEvidenceWorksPerHop"
  val ExportFormatKey = "analysis.exportFormat"
  val CommunityRandomSeedKey = "community.randomSeed"
  val PathLengthSampleSizeKey = "analysis.pathLengthSampleSize"
  val PathLengthSeedKey = "analysis.pathLengthSeed"
  val PathLengthMinComponentSizeKey = "analysis.pathLengthMinComponentSize"

  /** The three path-length keys, set together or not at all. */
  val PathLengthKeys: Vector[String] =
    Vector(PathLengthSampleSizeKey, PathLengthSeedKey, PathLengthMinComponentSizeKey)

  /** Every problem of the set analysis keys as (configuration key, reason), empty when valid. */
  def problems(c: AnalysisConfig): Vector[(String, String)] =
    if (c == null) Vector("analysis" -> "missing")
    else {
      def positive(key: String, v: Option[Int]): Vector[(String, String)] =
        Option(v).flatten.toVector.flatMap(n =>
          if (n >= 1) Vector.empty else Vector(key -> s"$n is below 1")
        )
      val pathLengthSet = Vector(
        Option(c.pathLengthSampleSize).flatten.isDefined,
        Option(c.pathLengthSeed).flatten.isDefined,
        Option(c.pathLengthMinComponentSize).flatten.isDefined
      )
      positive(MaxPathHopsKey, c.maxPathHops) ++
        positive(MaxPathsPerPairKey, c.maxPathsPerPair) ++
        positive(MaxEvidenceWorksPerHopKey, c.maxEvidenceWorksPerHop) ++
        positive(PathLengthSampleSizeKey, c.pathLengthSampleSize) ++
        Option(c.pathLengthMinComponentSize).flatten.toVector.flatMap(n =>
          if (n >= 2) Vector.empty
          else
            Vector(PathLengthMinComponentSizeKey -> s"$n is below 2 (a path needs two researchers)")
        ) ++
        (if (pathLengthSet.distinct.size > 1)
           Vector(
             PathLengthSampleSizeKey -> s"${PathLengthKeys.mkString(", ")} are set together or not at all"
           )
         else Vector.empty) ++
        Option(c.exportFormat).flatten.toVector.flatMap { f =>
          if (scholarchain.core.PartFormat.AnalysisExportFormats.exists(_.tag == f)) Vector.empty
          else
            Vector(
              ExportFormatKey -> s"'$f' is not an analysis export format (${scholarchain.core.PartFormat.AnalysisExportFormats.map(_.tag).mkString(", ")})"
            )
        }
    }
}

/**
 * Settings of a collaboration-analysis run (Phase 6, DE-P6-02, contracts C3, C4 and the config
 * contract), resolved from configuration. Every value is required here, so the analysis feature
 * refuses a configuration that lacks any analysis key, while other features load without them.
 *
 * Windows (C4): `graph.windows`, in configured order; non-empty, each with startYear <= endYear
 * (the loader), and distinct (here). Windows may overlap. Nothing hardcodes a window.
 *
 * @param bridgeParameters
 *   `maxIterations`, `randomSeed` and `sampleSize` from `bridge.*`, as decimal text
 * @param communityParameters
 *   `maxIterations` from `community.maxIterations` and, when set, `randomSeed` from
 *   `community.randomSeed`, as decimal text
 * @param configDigest
 *   [[AnalysisRunSettings.configDigest]] of the configuration these settings were resolved from
 *   (DE-P6-02 repair 01, CR-P6-13 finding 1). The analysis run manifest and every record lineage
 *   must record exactly this value ([[scholarchain.core.AnalysisValidation.checkRunChain]]).
 *
 * @param pathLengthSampling
 *   (DE-P6-03) the component-level path-length estimate settings, present exactly when the three
 *   `analysis.pathLength*` keys are set; absent: the run stores no path-length estimate
 *
 * The constructor is private: settings exist only through [[AnalysisRunSettings.from]], so the
 * digest always describes the analysis settings it travels with (a changed setting is a changed
 * configuration, hence a changed digest and a changed analysis run ID).
 */
final case class AnalysisRunSettings private (
    windows: Vector[TimeWindow],
    graphPolicyVersion: String,
    identityVersion: String,
    bridgeAlgorithm: String,
    bridgeParameters: SortedMap[String, String],
    communityAlgorithm: String,
    communityParameters: SortedMap[String, String],
    pathBounds: PathBounds,
    exportFormat: PartFormat,
    configDigest: String,
    pathLengthSampling: Option[PathLengthSampling] = None
)

/**
 * Settings of the component-level path-length estimate (DE-P6-03, course item 5 "where feasible";
 * [[scholarchain.core.PathLengthEstimate]]).
 *
 * @param sampleSize
 *   `analysis.pathLengthSampleSize`: BFS sources per component (all members when the component is
 *   not larger, which makes the estimate exact)
 * @param seed
 *   `analysis.pathLengthSeed`: seed of [[scholarchain.core.PathSampleKey]]
 * @param minComponentSize
 *   `analysis.pathLengthMinComponentSize`: smallest component that gets an estimate (>= 2)
 */
final case class PathLengthSampling(sampleSize: Int, seed: Long, minComponentSize: Int)

object AnalysisRunSettings {

  /** Schema tag of the analysis config digest input; part of the digested JSON. */
  val DigestSchema = "scholarchain.analysis-config-digest.v1"

  /**
   * The analysis digest input. Frozen field order. The three path-length fields (DE-P6-03) have the
   * default None and are omitted from the JSON when unset, so every configuration without them
   * keeps its exact digest.
   */
  private final case class DigestInput(
      schema: String,
      baseConfigDigest: String,
      communityRandomSeed: Option[Long],
      maxPathHops: Int,
      maxPathsPerPair: Int,
      maxEvidenceWorksPerHop: Int,
      exportFormat: String,
      pathLengthSampleSize: Option[Int] = None,
      pathLengthSeed: Option[Long] = None,
      pathLengthMinComponentSize: Option[Int] = None
  ) derives ReadWriter

  /**
   * The config digest of an analysis run (its own scope, recorded in the analysis run manifest and
   * in every record's lineage): SHA-256 hex of the compact JSON
   * `{"schema":DigestSchema,"baseConfigDigest":`[[ScholarChainConfig.digestWithoutGrouping]]`,"communityRandomSeed":...,"maxPathHops":...,"maxPathsPerPair":...,"maxEvidenceWorksPerHop":...,"exportFormat":...}`.
   *
   * Digest scope: the base digest covers every value outside the grouping and analysis scopes,
   * including the values the analysis reads from them (`graph.windows`, `graph.policyVersion`,
   * `graph.identityVersion`, `bridge.*`, `community.algorithm`, `community.maxIterations`); the
   * analysis-scope keys are added explicitly. The grouping-scope keys are not inputs: the analysis
   * reads the grouping policy from the grouping run, recorded in the lineage. Refuses a
   * configuration that [[from]] refuses.
   */
  def configDigest(c: ScholarChainConfig): Either[Vector[ConfigError], String] =
    from(c).map(_.configDigest)

  /** The digest of a configuration whose analysis keys are all set (called by [[from]] only). */
  private def digestOf(
      c: ScholarChainConfig,
      bounds: PathBounds,
      format: PartFormat
  ): String =
    Canonical.sha256HexUtf8(
      upickle.default.write(
        DigestInput(
          DigestSchema,
          ScholarChainConfig.digestWithoutGrouping(c),
          c.community.randomSeed,
          bounds.maxHops,
          bounds.maxPathsPerPair,
          bounds.maxEvidenceWorksPerHop,
          format.tag,
          c.analysis.pathLengthSampleSize,
          c.analysis.pathLengthSeed,
          c.analysis.pathLengthMinComponentSize
        )
      )
    )

  /**
   * Missing analysis keys as [[ConfigError.MissingKey]], then every rule of the section. On success
   * the settings carry their own [[configDigest]].
   */
  def from(c: ScholarChainConfig): Either[Vector[ConfigError], AnalysisRunSettings] =
    if (
      c == null || c.graph == null || c.bridge == null || c.community == null || c.analysis == null
    )
      Left(Vector(ConfigError.MissingKey("analysis", ConfigKeys.envNameOf("analysis"))))
    else {
      import AnalysisConfig.*
      val a = c.analysis
      def required[A](key: String, v: Option[A]): Either[ConfigError, A] =
        Option(v).flatten.toRight(ConfigError.MissingKey(key, ConfigKeys.envNameOf(key)))
      val hops = required(MaxPathHopsKey, a.maxPathHops)
      val perPair = required(MaxPathsPerPairKey, a.maxPathsPerPair)
      val perHop = required(MaxEvidenceWorksPerHopKey, a.maxEvidenceWorksPerHop)
      val format = required(ExportFormatKey, a.exportFormat)
      val missing = Vector(hops, perPair, perHop, format).collect { case Left(e) => e }
      def invalid(key: String, why: String) =
        ConfigError.InvalidValue(key, "resolved configuration", why)
      val windows = Option(c.graph.windows).getOrElse(Vector.empty)
      val rules =
        problems(a).map { case (k, why) => invalid(k, why) } ++
          (if (windows.isEmpty) Vector(invalid("graph.windows", "at least one window is required"))
           else Vector.empty) ++
          windows.zipWithIndex.flatMap { case (w, i) =>
            TimeWindow.check(s"graph.windows[$i]", w).map(e => invalid("graph.windows", e.message))
          } ++
          (if (windows.distinct.size != windows.size)
             Vector(invalid("graph.windows", "windows must be distinct"))
           else Vector.empty) ++
          (if (StableId.isToken(c.graph.policyVersion)) Vector.empty
           else Vector(invalid("graph.policyVersion", "missing or not a token"))) ++
          (if (c.graph.identityVersion != null && c.graph.identityVersion.matches("v[0-9]+"))
             Vector.empty
           else Vector(invalid("graph.identityVersion", "must match v[0-9]+"))) ++
          (if (StableId.isToken(c.bridge.algorithm)) Vector.empty
           else Vector(invalid("bridge.algorithm", "missing or not a token"))) ++
          (if (StableId.isToken(c.community.algorithm)) Vector.empty
           else Vector(invalid("community.algorithm", "missing or not a token")))
      val errors = missing ++ rules
      if (errors.nonEmpty) Left(errors)
      else {
        val bounds = PathBounds(hops.toOption.get, perPair.toOption.get, perHop.toOption.get)
        val exportFormat = PartFormat.fromTag(format.toOption.get).get
        Right(
          new AnalysisRunSettings(
            windows = windows,
            graphPolicyVersion = c.graph.policyVersion,
            identityVersion = c.graph.identityVersion,
            bridgeAlgorithm = c.bridge.algorithm,
            bridgeParameters = SortedMap(
              "maxIterations" -> c.bridge.maxIterations.toString,
              "randomSeed" -> c.bridge.randomSeed.toString,
              "sampleSize" -> c.bridge.sampleSize.toString
            ),
            communityAlgorithm = c.community.algorithm,
            communityParameters = SortedMap(
              "maxIterations" -> c.community.maxIterations.toString
            ) ++ c.community.randomSeed.map(s => "randomSeed" -> s.toString),
            pathBounds = bounds,
            exportFormat = exportFormat,
            configDigest = digestOf(c, bounds, exportFormat),
            pathLengthSampling = for {
              k <- a.pathLengthSampleSize
              s <- a.pathLengthSeed
              m <- a.pathLengthMinComponentSize
            } yield PathLengthSampling(k, s, m)
          )
        )
      }
    }
}

/**
 * Entity-resolution settings (config schema `scholarchain.config.v2`). Every value is supplied by
 * configuration; the values themselves are entity-resolution policy. None has a default.
 *
 * @param policyVersion
 *   match policy version (token)
 * @param acceptThreshold
 *   scores at or above it are ACCEPTED on the probabilistic path
 * @param rejectThreshold
 *   scores at or below it are REJECTED on the probabilistic path
 * @param llmBandLower
 *   lower bound of the unresolved score band that may be sent to LLM adjudication
 * @param llmBandUpper
 *   upper bound of that band
 * @param promptVersion
 *   adjudication prompt version (token)
 *
 * Valid exactly when every number is finite and in [0,1], both versions are tokens, and
 * `rejectThreshold < llmBandLower < llmBandUpper < acceptThreshold`
 * ([[EntityResolutionConfig.problems]]).
 *
 * Optional keys (Phase 6, DE-P6-01, contract C4; additive within `scholarchain.config.v2`). They
 * are optional at load time, so every existing configuration loads unchanged, and a configuration
 * that sets none of them keeps its exact digest (an unset key is omitted from the digest input).
 * They form the grouping scope ([[ScholarChainConfig.GroupingScopeKeys]]): setting them never
 * changes [[ScholarChainConfig.digestWithoutGrouping]], the digest of every non-grouping stage.
 * They are validated when set, and a feature that needs one refuses a missing value through
 * [[EntityResolutionConfig.requireKey]] or [[GroupingRunSettings.from]].
 *
 * @param groupingPolicyVersion
 *   grouping policy version (token, for example `er-grouping.v1`); must differ from
 *   `policyVersion`, the match policy version (the grouping layer is versioned separately)
 * @param decisionRunRoot
 *   location under which decision run directories are created and read ([[RunLocation]])
 * @param assignmentRunRoot
 *   location under which grouping (assignment) run directories are created and read; neither root
 *   may equal or contain the other
 */
final case class EntityResolutionConfig(
    policyVersion: String,
    acceptThreshold: Double,
    rejectThreshold: Double,
    llmBandLower: Double,
    llmBandUpper: Double,
    promptVersion: String,
    groupingPolicyVersion: Option[String] = None,
    decisionRunRoot: Option[String] = None,
    assignmentRunRoot: Option[String] = None
) derives ReadWriter

/**
 * A run location: `s3://<bucket>` or `s3://<bucket>/<key prefix>` (bucket as for the storage
 * buckets), or an absolute local path. Segments after the bucket or root are non-empty and never
 * `.` or `..`; no trailing slash, no whitespace, no control characters, no backslash.
 */
object RunLocation {
  private val Bucket = "^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$".r
  private val S3 = "s3://"

  private def segmentsProblem(segments: Vector[String]): Option[String] =
    if (segments.exists(_.isEmpty)) Some("has an empty path segment (or a trailing slash)")
    else if (segments.exists(s => s == "." || s == "..")) Some("has a '.' or '..' segment")
    else None

  /** Why `s` is not a run location, or None. */
  def problem(s: String): Option[String] =
    if (s == null || s.isEmpty) Some("missing")
    else if (s.exists(c => c.isWhitespace || c.isControl || c == '\\'))
      Some("contains whitespace, a control character or a backslash")
    else if (s.startsWith(S3)) {
      val parts = s.stripPrefix(S3).split("/", -1).toVector
      if (!Bucket.matches(parts.head)) Some(s"'${parts.head}' is not an S3 bucket name")
      else segmentsProblem(parts.tail)
    } else if (s.startsWith("/")) {
      if (s == "/") Some("the file system root is not a run location")
      else segmentsProblem(s.stripPrefix("/").split("/", -1).toVector)
    } else Some("must be s3://<bucket>[/<prefix>] or an absolute local path")

  /** True when `a` equals `b` or contains it (segment-wise prefix). */
  def contains(a: String, b: String): Boolean = a == b || b.startsWith(a + "/")
}

object EntityResolutionConfig {

  /** Configuration key paths of the optional Phase 6 keys. */
  val GroupingPolicyVersionKey = "entityResolution.groupingPolicyVersion"
  val DecisionRunRootKey = "entityResolution.decisionRunRoot"
  val AssignmentRunRootKey = "entityResolution.assignmentRunRoot"

  /**
   * The value of an optional key that a feature requires: `Left(MissingKey)` when it is unset. Only
   * the three optional keys are accepted.
   */
  def requireKey(c: EntityResolutionConfig, key: String): Either[ConfigError, String] = {
    val value = key match {
      case GroupingPolicyVersionKey => Some(c.groupingPolicyVersion)
      case DecisionRunRootKey => Some(c.decisionRunRoot)
      case AssignmentRunRootKey => Some(c.assignmentRunRoot)
      case _ => None
    }
    value match {
      case None => Left(ConfigError.UnknownKey("EntityResolutionConfig.require", key))
      case Some(v) =>
        Option(v).flatten.toRight(ConfigError.MissingKey(key, ConfigKeys.envNameOf(key)))
    }
  }

  /**
   * Every problem of a resolved entity-resolution configuration as (configuration key, reason),
   * empty when valid. The loader reports these; code that builds a configuration in memory can call
   * it to apply the same rules.
   */
  def problems(c: EntityResolutionConfig): Vector[(String, String)] =
    if (c == null) Vector("entityResolution" -> "missing")
    else {
      def prob(key: String, v: Double): Vector[(String, String)] =
        if (v.isNaN || v.isInfinite || v < 0.0 || v > 1.0) Vector(key -> s"$v is outside [0,1]")
        else Vector.empty
      def token(key: String, v: String): Vector[(String, String)] =
        if (StableId.isToken(v)) Vector.empty else Vector(key -> "missing or not a token")
      val k = "entityResolution."
      val numbers = prob(k + "acceptThreshold", c.acceptThreshold) ++
        prob(k + "rejectThreshold", c.rejectThreshold) ++
        prob(k + "llmBandLower", c.llmBandLower) ++
        prob(k + "llmBandUpper", c.llmBandUpper)
      val versions =
        token(k + "policyVersion", c.policyVersion) ++ token(k + "promptVersion", c.promptVersion)
      def order(ok: Boolean, key: String, reason: String) =
        if (ok) Vector.empty else Vector(key -> reason)
      // The ordering is checked only when every number is valid, so that one bad value is
      // reported once.
      val ordering =
        if (numbers.nonEmpty) Vector.empty
        else
          order(
            c.rejectThreshold < c.acceptThreshold,
            k + "rejectThreshold",
            "must be below entityResolution.acceptThreshold"
          ) ++ order(
            c.rejectThreshold < c.llmBandLower,
            k + "llmBandLower",
            "must be above entityResolution.rejectThreshold"
          ) ++ order(
            c.llmBandLower < c.llmBandUpper,
            k + "llmBandUpper",
            "must be above entityResolution.llmBandLower"
          ) ++ order(
            c.llmBandUpper < c.acceptThreshold,
            k + "llmBandUpper",
            "must be below entityResolution.acceptThreshold"
          )
      val grouping = Option(c.groupingPolicyVersion).flatten
      val decisionRoot = Option(c.decisionRunRoot).flatten
      val assignmentRoot = Option(c.assignmentRunRoot).flatten
      val optional =
        grouping.toVector.flatMap(token(GroupingPolicyVersionKey, _)) ++
          grouping.toVector.flatMap { g =>
            if (g == c.policyVersion)
              Vector(
                GroupingPolicyVersionKey -> "must differ from entityResolution.policyVersion"
              )
            else Vector.empty
          } ++
          decisionRoot.toVector.flatMap(r => RunLocation.problem(r).map(DecisionRunRootKey -> _)) ++
          assignmentRoot.toVector.flatMap(r =>
            RunLocation.problem(r).map(AssignmentRunRootKey -> _)
          )
      val roots = (decisionRoot, assignmentRoot) match {
        case (Some(d), Some(a))
            if RunLocation.problem(d).isEmpty && RunLocation.problem(a).isEmpty &&
              (RunLocation.contains(d, a) || RunLocation.contains(a, d)) =>
          Vector(
            AssignmentRunRootKey -> "must not equal, contain or lie inside entityResolution.decisionRunRoot"
          )
        case _ => Vector.empty
      }
      versions ++ numbers ++ ordering ++ optional ++ roots
    }
}

/**
 * Settings of a grouping run, resolved from configuration (contract C4). Every value is required
 * here, so the grouping feature refuses a configuration that lacks any of them, while
 * configurations used by other features load without them.
 *
 * @param groupingPolicyVersion
 *   `entityResolution.groupingPolicyVersion`
 * @param matchPolicyVersion
 *   `entityResolution.policyVersion`, the match policy of the decisions being grouped
 * @param identityVersion
 *   `graph.identityVersion`
 * @param decisionRunRoot
 *   `entityResolution.decisionRunRoot`
 * @param assignmentRunRoot
 *   `entityResolution.assignmentRunRoot`
 */
final case class GroupingRunSettings(
    groupingPolicyVersion: String,
    matchPolicyVersion: String,
    identityVersion: String,
    decisionRunRoot: String,
    assignmentRunRoot: String
)

object GroupingRunSettings {

  /** Schema tag of the grouping config digest input; part of the digested JSON. */
  val DigestSchema = "scholarchain.grouping-config-digest.v1"

  /** The grouping digest input. Frozen field order. */
  private final case class DigestInput(
      schema: String,
      baseConfigDigest: String,
      groupingPolicyVersion: String
  ) derives ReadWriter

  /**
   * The config digest of a grouping run (its own scope): SHA-256 hex of the compact JSON
   * `{"schema":DigestSchema,"baseConfigDigest":`[[ScholarChainConfig.digestWithoutGrouping]]`,"groupingPolicyVersion":...}`.
   * It changes with every upstream value and with the grouping policy version. The two run roots
   * are locations, not run identity (as the path arguments of the decision run), so moving a root
   * keeps the digest. Refuses a configuration that [[from]] refuses.
   */
  def configDigest(c: ScholarChainConfig): Either[Vector[ConfigError], String] =
    from(c).map { s =>
      Canonical.sha256HexUtf8(
        upickle.default.write(
          DigestInput(
            DigestSchema,
            ScholarChainConfig.digestWithoutGrouping(c),
            s.groupingPolicyVersion
          )
        )
      )
    }

  /** Missing optional keys as [[ConfigError.MissingKey]], then every rule of the section. */
  def from(c: ScholarChainConfig): Either[Vector[ConfigError], GroupingRunSettings] =
    if (c == null || c.entityResolution == null || c.graph == null)
      Left(
        Vector(ConfigError.MissingKey("entityResolution", ConfigKeys.envNameOf("entityResolution")))
      )
    else {
      val er = c.entityResolution
      import EntityResolutionConfig.*
      val g = requireKey(er, GroupingPolicyVersionKey)
      val d = requireKey(er, DecisionRunRootKey)
      val a = requireKey(er, AssignmentRunRootKey)
      val missing = Vector(g, d, a).collect { case Left(e) => e }
      val invalid = problems(er).map { case (k, why) =>
        ConfigError.InvalidValue(k, "resolved configuration", why)
      }
      val identity =
        if (StableId.isToken(c.graph.identityVersion) && c.graph.identityVersion.matches("v[0-9]+"))
          Vector.empty
        else
          Vector(
            ConfigError
              .InvalidValue("graph.identityVersion", "resolved configuration", "must match v[0-9]+")
          )
      val errors = missing ++ invalid ++ identity
      if (errors.nonEmpty) Left(errors)
      else
        Right(
          GroupingRunSettings(
            g.toOption.get,
            er.policyVersion,
            c.graph.identityVersion,
            d.toOption.get,
            a.toOption.get
          )
        )
    }
}

final case class RuntimeConfig(maxPathHops: Int, retrievalTopK: Int, defaultWindow: TimeWindow)
    derives ReadWriter

object ScholarChainConfig {

  /**
   * Digest of a resolved configuration: SHA-256 hex of its compact JSON (frozen field order).
   * Covers every value, including the grouping scope ([[GroupingScopeKeys]]). A run manifest of any
   * stage other than grouping records [[digestWithoutGrouping]] instead, and a grouping run records
   * [[GroupingRunSettings.configDigest]].
   */
  def digest(c: ScholarChainConfig): String = Canonical.sha256HexUtf8(upickle.default.write(c))

  /**
   * The grouping scope: settings read only by the grouping stage. They are never an input of the
   * run identity of an upstream stage (decision runs and earlier).
   */
  val GroupingScopeKeys: Vector[String] = Vector(
    EntityResolutionConfig.GroupingPolicyVersionKey,
    EntityResolutionConfig.DecisionRunRootKey,
    EntityResolutionConfig.AssignmentRunRootKey
  )

  /** `c` with every grouping-scope key unset. */
  def withoutGrouping(c: ScholarChainConfig): ScholarChainConfig =
    if (c == null || c.entityResolution == null) c
    else
      c.copy(entityResolution =
        c.entityResolution
          .copy(groupingPolicyVersion = None, decisionRunRoot = None, assignmentRunRoot = None)
      )

  /**
   * The analysis scope (Phase 6, DE-P6-02): settings read only by the collaboration-analysis stage.
   * Like the grouping scope, they are never an input of the run identity of an upstream stage
   * (curated input, decision and grouping runs).
   */
  val AnalysisScopeKeys: Vector[String] = Vector(
    AnalysisConfig.CommunityRandomSeedKey,
    AnalysisConfig.MaxPathHopsKey,
    AnalysisConfig.MaxPathsPerPairKey,
    AnalysisConfig.MaxEvidenceWorksPerHopKey,
    AnalysisConfig.ExportFormatKey,
    AnalysisConfig.PathLengthSampleSizeKey,
    AnalysisConfig.PathLengthSeedKey,
    AnalysisConfig.PathLengthMinComponentSizeKey
  )

  /** `c` with every analysis-scope key unset. */
  def withoutAnalysis(c: ScholarChainConfig): ScholarChainConfig =
    if (c == null) c
    else
      c.copy(
        community = Option(c.community).map(_.copy(randomSeed = None)).orNull,
        analysis = AnalysisConfig()
      )

  /**
   * The config digest of every stage except grouping and analysis, recorded in
   * [[scholarchain.core.RunManifest.configDigest]]: [[digest]] of [[withoutGrouping]] and
   * [[withoutAnalysis]] of `c`. Setting, changing or removing a grouping-scope or analysis-scope
   * key never changes it, and for a configuration that sets neither it equals [[digest]], so every
   * existing run manifest (decision and grouping runs included) keeps its configDigest. The
   * analysis-scope part was added in DE-P6-02; it changes no existing value, because no earlier
   * configuration could set an analysis-scope key.
   */
  def digestWithoutGrouping(c: ScholarChainConfig): String =
    digest(withoutAnalysis(withoutGrouping(c)))
}
