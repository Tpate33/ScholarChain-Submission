package scholarchain.core.config

import upickle.default.ReadWriter

import scholarchain.core.{Canonical, StableId, TimeWindow}

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
    runtime: RuntimeConfig
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

final case class CommunityConfig(algorithm: String, maxIterations: Int) derives ReadWriter

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
   * The config digest of every stage except grouping, recorded in
   * [[scholarchain.core.RunManifest.configDigest]]: [[digest]] of [[withoutGrouping]](c). Setting,
   * changing or removing a grouping-scope key never changes it, and for a configuration that sets
   * no grouping-scope key it equals [[digest]], so every existing run manifest keeps its
   * configDigest.
   */
  def digestWithoutGrouping(c: ScholarChainConfig): String = digest(withoutGrouping(c))
}
