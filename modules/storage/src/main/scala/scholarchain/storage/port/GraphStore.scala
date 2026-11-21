package scholarchain.storage.port

import scholarchain.storage.model.{
  BoundGraphWrite,
  BoundQuery,
  GraphRow,
  PutOutcome,
  StoreError,
  StoredEdge,
  StoredVertex
}

/**
 * Runtime graph access (README L1377-1378): runs one allowlisted, parameterized openCypher template
 * against the read-only (`cluster-ro-`) endpoint. It accepts only a [[BoundQuery]], so query text
 * is never built from user input.
 */
trait GraphReader {
  def run(query: BoundQuery): Either[StoreError, Vector[GraphRow]]
}

/**
 * Loader-only graph writes (role law 4; SE-P7-05 B4, B5): one allowlisted write template bound to
 * one vertex or edge ([[BoundGraphWrite]]), create-once. `Written` when the ID was absent,
 * `AlreadyPresent` when the stored element is equal (label, endpoints, properties, provenance),
 * `Conflict` when it differs (never an overwrite), `Refused` for an edge whose endpoint is absent.
 * Never part of runtime composition ([[RuntimeStores]] holds only a [[GraphReader]] facade).
 */
trait GraphWriter {
  def write(w: BoundGraphWrite): Either[StoreError, PutOutcome]

  /**
   * Loader-side pre-write probe (CR-P7-09 M1): the stored vertex with this `~id`, if any. The
   * Neptune adapter answers it with a fixed parameterized lookup by ID (never built from text).
   */
  def storedVertex(id: String): Either[StoreError, Option[StoredVertex]]

  /** Loader-side pre-write probe (CR-P7-09 M1): the stored edge with this `~id`, if any. */
  def storedEdge(id: String): Either[StoreError, Option[StoredEdge]]
}

/**
 * One Neptune bulk-loader request (built, never sent, in Phase 7; SE-P7-01, SE-P7-05 B6). Every
 * field is data from the load manifest and configuration: the source is an S3 prefix of the graph
 * zone, the format is `csv`. Field names and values of [[body]] follow the Neptune loader request
 * as recalled; UNVERIFIED against current AWS documentation (`mcp__aws-docs` unavailable on
 * 2026-11-20).
 *
 * @param step
 *   the dataset directory (role) the request loads, for example `researcher-vertices`
 * @param dependsOnSteps
 *   steps that must finish first (every edge step depends on every vertex step); the submitter
 *   turns them into Neptune `dependencies` load IDs, which exist only after submission
 */
final case class BulkLoadRequest(
    step: String,
    sourceUri: String,
    format: String,
    iamRoleArn: String,
    region: String,
    failOnError: Boolean,
    parallelism: String,
    queueRequest: Boolean,
    dependsOnSteps: Vector[String]
) {

  /** The loader request body (compact JSON, fixed key order); `dependencies` added at submit. */
  def body: String = {
    def flag(b: Boolean) = if (b) "TRUE" else "FALSE"
    ujson
      .Obj(
        "source" -> sourceUri,
        "format" -> format,
        "iamRoleArn" -> iamRoleArn,
        "region" -> region,
        "failOnError" -> flag(failOnError),
        "parallelism" -> parallelism,
        "queueRequest" -> flag(queueRequest)
      )
      .render()
  }
}

/** Every request of one Neptune store load, in submission order. */
final case class BulkLoadPlan(runId: String, loadId: String, requests: Vector[BulkLoadRequest])

/** The handle Neptune returns for a submitted bulk load. */
final case class BulkLoadHandle(neptuneLoadId: String)

/** Status of a submitted bulk load. */
enum BulkLoadStatus {
  case InProgress
  case Completed
  case Failed(detail: String)
}

/** Batch graph bulk loads: loaders only, never part of runtime composition (role law 4). */
trait GraphLoader {
  def submit(
      request: BulkLoadRequest,
      dependencies: Vector[BulkLoadHandle]
  ): Either[StoreError, BulkLoadHandle]
  def status(handle: BulkLoadHandle): Either[StoreError, BulkLoadStatus]
}
