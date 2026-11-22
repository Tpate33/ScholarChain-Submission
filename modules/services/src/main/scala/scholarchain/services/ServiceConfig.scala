package scholarchain.services

import scholarchain.core.TimeWindow

/**
 * Every bound of the service (role law 7: configuration, no built-in default). Production values
 * come from the deployment configuration; core has no `services.*` configuration keys yet (deferred
 * to domain-engineer), so the composition receives this value explicitly.
 *
 * @param maxBodyBytes
 *   largest request body read
 * @param maxQuestionBytes
 *   longest question (UTF-8 bytes) accepted by POST /v1/query
 * @param syncTimeoutMillis
 *   time budget of one synchronous Graph-RAG workflow call
 * @param asyncTimeoutMillis
 *   time budget of one asynchronous job's workflow call
 * @param readTimeoutMillis
 *   time budget of one downstream read attempt (evidence, graph, explain)
 * @param readMaxAttempts
 *   attempts of one idempotent downstream read (retries only transient store errors and timeouts)
 * @param breakerFailureThreshold
 *   consecutive downstream failures that open a circuit
 * @param breakerOpenMillis
 *   how long an open circuit refuses calls before one trial call
 * @param readRequestTimeoutMillis
 *   overall deadline of one read request (every attempt and every read of a multi-read request)
 * @param queueWaitMillis
 *   longest wait of a downstream call for a thread of its pool; a call not started by then is
 *   withdrawn (`503 overloaded`, never a breaker failure, never retried)
 * @param workflowWorkers
 *   threads of the synchronous workflow pool (POST /v1/query mode sync)
 * @param workflowQueueCapacity
 *   synchronous workflow calls waiting for a thread; more is `503 overloaded`
 * @param keyValueWorkers
 *   threads of the key-value read pool (evidence, explain)
 * @param keyValueQueueCapacity
 *   key-value reads waiting for a thread
 * @param graphWorkers
 *   threads of the graph read pool (graph exploration)
 * @param graphQueueCapacity
 *   graph reads waiting for a thread
 * @param jobWorkers
 *   threads that run asynchronous jobs; each job's workflow call runs on its own pool of the same
 *   size, so jobs never take a thread of the synchronous workflow or read pools
 * @param jobQueueCapacity
 *   jobs waiting for a worker; more is `429`
 * @param maxJobs
 *   jobs retained (finished jobs are evicted oldest first); more unfinished jobs is `429`
 * @param idempotencyCapacity
 *   idempotency records retained per process
 * @param idempotencyTtlMillis
 *   how long a COMPLETED idempotency record is honoured (an in-flight record never expires); must
 *   exceed both workflow timeouts
 * @param queryOwnershipCapacity
 *   queries whose submitting client is remembered (explain is served only to it); older ones are
 *   forgotten first and their explanation is then 404
 * @param graphMaxDepth
 *   largest neighbourhood depth served (the closed template set has one-hop templates only)
 * @param graphMaxPageSize
 *   largest neighbourhood page
 * @param graphDefaultPageSize
 *   page size when the request names none
 * @param graphMaxResults
 *   largest LIMIT of one graph template call (offset plus page plus one)
 * @param graphEdgeTypes
 *   edge types served by the neighbourhood route (a subset of [[GraphService.SupportedEdgeTypes]])
 * @param graphDefaultWindow
 *   coauthorship window when the request names none
 * @param evidenceMaxContentBytes
 *   largest supporting content returned with one evidence item
 * @param evidenceMaxBundles
 *   occurrences (the caller's own bundles) listed per evidence item
 * @param evidenceScanLimit
 *   stored rows of one evidence ID read to find the caller's own occurrences (> evidenceMaxBundles)
 * @param explainMaxSteps
 *   plan steps read for one explanation
 * @param metricsEnabled
 *   whether GET /metrics is served (the deployment model decides)
 * @param retryAfterSeconds
 *   the `Retry-After` value of 429 and 503 responses
 */
final case class ServiceConfig(
    maxBodyBytes: Int,
    maxQuestionBytes: Int,
    syncTimeoutMillis: Long,
    asyncTimeoutMillis: Long,
    readTimeoutMillis: Long,
    readMaxAttempts: Int,
    breakerFailureThreshold: Int,
    breakerOpenMillis: Long,
    readRequestTimeoutMillis: Long,
    queueWaitMillis: Long,
    workflowWorkers: Int,
    workflowQueueCapacity: Int,
    keyValueWorkers: Int,
    keyValueQueueCapacity: Int,
    graphWorkers: Int,
    graphQueueCapacity: Int,
    jobWorkers: Int,
    jobQueueCapacity: Int,
    maxJobs: Int,
    idempotencyCapacity: Int,
    idempotencyTtlMillis: Long,
    queryOwnershipCapacity: Int,
    graphMaxDepth: Int,
    graphMaxPageSize: Int,
    graphDefaultPageSize: Int,
    graphMaxResults: Int,
    graphEdgeTypes: Vector[String],
    graphDefaultWindow: TimeWindow,
    evidenceMaxContentBytes: Int,
    evidenceMaxBundles: Int,
    evidenceScanLimit: Int,
    explainMaxSteps: Int,
    metricsEnabled: Boolean,
    retryAfterSeconds: Int
)

object ServiceConfig {

  /** Every problem of `c`, empty when valid. */
  def problems(c: ServiceConfig): Vector[String] =
    if (c == null) Vector("config: missing")
    else {
      val positives = Vector(
        "maxBodyBytes" -> c.maxBodyBytes.toLong,
        "maxQuestionBytes" -> c.maxQuestionBytes.toLong,
        "syncTimeoutMillis" -> c.syncTimeoutMillis,
        "asyncTimeoutMillis" -> c.asyncTimeoutMillis,
        "readTimeoutMillis" -> c.readTimeoutMillis,
        "readMaxAttempts" -> c.readMaxAttempts.toLong,
        "breakerFailureThreshold" -> c.breakerFailureThreshold.toLong,
        "breakerOpenMillis" -> c.breakerOpenMillis,
        "readRequestTimeoutMillis" -> c.readRequestTimeoutMillis,
        "queueWaitMillis" -> c.queueWaitMillis,
        "workflowWorkers" -> c.workflowWorkers.toLong,
        "workflowQueueCapacity" -> c.workflowQueueCapacity.toLong,
        "keyValueWorkers" -> c.keyValueWorkers.toLong,
        "keyValueQueueCapacity" -> c.keyValueQueueCapacity.toLong,
        "graphWorkers" -> c.graphWorkers.toLong,
        "graphQueueCapacity" -> c.graphQueueCapacity.toLong,
        "jobWorkers" -> c.jobWorkers.toLong,
        "jobQueueCapacity" -> c.jobQueueCapacity.toLong,
        "maxJobs" -> c.maxJobs.toLong,
        "idempotencyCapacity" -> c.idempotencyCapacity.toLong,
        "idempotencyTtlMillis" -> c.idempotencyTtlMillis,
        "queryOwnershipCapacity" -> c.queryOwnershipCapacity.toLong,
        "graphMaxDepth" -> c.graphMaxDepth.toLong,
        "graphMaxPageSize" -> c.graphMaxPageSize.toLong,
        "graphDefaultPageSize" -> c.graphDefaultPageSize.toLong,
        "graphMaxResults" -> c.graphMaxResults.toLong,
        "evidenceMaxContentBytes" -> c.evidenceMaxContentBytes.toLong,
        "evidenceMaxBundles" -> c.evidenceMaxBundles.toLong,
        "evidenceScanLimit" -> c.evidenceScanLimit.toLong,
        "explainMaxSteps" -> c.explainMaxSteps.toLong,
        "retryAfterSeconds" -> c.retryAfterSeconds.toLong
      ).collect { case (k, v) if v < 1 => s"$k: must be >= 1" }
      val graph = Vector(
        Option.when(c.graphMaxDepth > GraphService.MaxSupportedDepth)(
          s"graphMaxDepth: at most ${GraphService.MaxSupportedDepth} (one-hop templates only)"
        ),
        Option.when(c.graphDefaultPageSize > c.graphMaxPageSize)(
          "graphDefaultPageSize: must be <= graphMaxPageSize"
        ),
        Option.when(c.graphMaxPageSize >= c.graphMaxResults)(
          "graphMaxPageSize: must be < graphMaxResults (one extra row detects a next page)"
        ),
        Option.when(c.graphEdgeTypes == null || c.graphEdgeTypes.isEmpty)(
          "graphEdgeTypes: at least one"
        ),
        Option.when(
          c.graphEdgeTypes != null &&
            c.graphEdgeTypes.exists(t => !GraphService.SupportedEdgeTypes.contains(t))
        )(
          s"graphEdgeTypes: only ${GraphService.SupportedEdgeTypes.mkString(", ")}"
        ),
        Option.when(TimeWindow.check("graphDefaultWindow", c.graphDefaultWindow).nonEmpty)(
          "graphDefaultWindow: not a valid window"
        ),
        // SE-P10-04 m1: the scan must be able to tell whether the caller's own list was cut.
        Option.when(c.evidenceScanLimit <= c.evidenceMaxBundles)(
          "evidenceScanLimit: must be > evidenceMaxBundles"
        ),
        // SE-P10-03 X1: one full attempt must fit the request deadline.
        Option.when(c.readRequestTimeoutMillis < c.readTimeoutMillis)(
          "readRequestTimeoutMillis: must be >= readTimeoutMillis"
        ),
        // SE-P10-06 m-A: the query-ownership record runs at the start of the workflow call, under
        // the workflow timer, and is bounded (in real time) by readRequestTimeoutMillis. Its bound
        // must end before the workflow timer, so a hanging ownership store is reported against
        // the key-value dependency (its own 504/503, key-value breaker), never as a workflow
        // timeout. The difference is the headroom; keep it well above scheduling jitter.
        Option.when(
          c.readRequestTimeoutMillis >= math.min(c.syncTimeoutMillis, c.asyncTimeoutMillis)
        )(
          "readRequestTimeoutMillis: must be < syncTimeoutMillis and < asyncTimeoutMillis"
        ),
        // SE-P10-02 m3: a completed record must outlive any execution of its request.
        Option.when(
          c.idempotencyTtlMillis <= math.max(c.syncTimeoutMillis, c.asyncTimeoutMillis)
        )(
          "idempotencyTtlMillis: must exceed syncTimeoutMillis and asyncTimeoutMillis"
        )
      ).flatten
      positives ++ graph
    }
}
