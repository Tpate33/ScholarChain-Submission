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
 */
final case class EntityResolutionConfig(
    policyVersion: String,
    acceptThreshold: Double,
    rejectThreshold: Double,
    llmBandLower: Double,
    llmBandUpper: Double,
    promptVersion: String
) derives ReadWriter

object EntityResolutionConfig {

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
      versions ++ numbers ++ ordering
    }
}

final case class RuntimeConfig(maxPathHops: Int, retrievalTopK: Int, defaultWindow: TimeWindow)
    derives ReadWriter

object ScholarChainConfig {

  /**
   * Digest of a resolved configuration: SHA-256 hex of its compact JSON (frozen field order).
   * Recorded in [[scholarchain.core.RunManifest.configDigest]], so a config change changes the run
   * ID.
   */
  def digest(c: ScholarChainConfig): String = Canonical.sha256HexUtf8(upickle.default.write(c))
}
