package scholarchain.core.config

import upickle.default.ReadWriter

import scholarchain.core.{Canonical, TimeWindow}

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

/** Thresholds are supplied by configuration; their values are entity-resolution policy. */
final case class EntityResolutionConfig(
    policyVersion: String,
    acceptThreshold: Double,
    rejectThreshold: Double
) derives ReadWriter

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
