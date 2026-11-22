package scholarchain.services

import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicBoolean

import scholarchain.storage.port.RuntimeStores

/**
 * The runtime composition of ONE [[ServiceKind]] (services -> core, rag, storage). It wires only
 * the application services, pools and breakers that service needs behind its router:
 *
 *   - QueryJobs: the Query and Jobs APIs over the rag workflow port; pools `workflow` (synchronous
 *     calls), `jobs` (async job runners), `job-workflow` (their workflow calls) and `key-value`
 *     (recording query ownership, SE-P10-04 m2); breakers `workflow` and `key-value`.
 *   - EvidenceExplain: the Evidence and Explain APIs over the key-value read port; pool and breaker
 *     `key-value`.
 *   - Graph: the graph exploration API over the graph read port; pool and breaker `graph`.
 *   - All: every service in one process (tests and a one-process local run).
 *
 * Every pool is bounded, uses daemon threads and is stopped by [[close]]; a call waits at most
 * `queueWaitMillis` for a thread of its own pool. Query ownership ([[QueryOwnership]]) is a port
 * shared by QueryJobs (records) and EvidenceExplain (checks).
 */
final class ServiceRuntime private (
    val kind: ServiceKind,
    val router: Router,
    val health: Health,
    val metrics: Metrics,
    val breakers: Vector[CircuitBreaker],
    accepting: AtomicBoolean,
    shutdown: () => Unit
) {

  /** Stops accepting /v1 requests (readiness fails); in-flight work continues. */
  def drain(): Unit = accepting.set(false)

  /** Stops accepting /v1 requests (readiness fails) and stops the executors. */
  def close(): Unit = {
    accepting.set(false)
    shutdown()
  }
}

object ServiceRuntime {

  def build(
      runtime: RuntimeStores,
      workflow: QueryWorkflow,
      keys: ApiKeyDigestSource,
      config: ServiceConfig,
      access: StoreAccess,
      accessLog: AccessLog,
      clock: ServiceClock,
      ids: IdSource,
      kind: ServiceKind = ServiceKind.All,
      ownership: QueryOwnership = null
  ): Either[String, ServiceRuntime] = {
    val problems = ServiceConfig.problems(config)
    val needsWorkflow = kind.serves(ServiceKind.QueryJobs)
    if (problems.nonEmpty) Left(problems.mkString("; "))
    else if (Vector(keys, access, accessLog, clock, ids, kind).contains(null))
      Left("every port is required")
    else if (needsWorkflow && workflow == null) Left("workflow: required by query-jobs")
    else if (
      (kind.serves(ServiceKind.EvidenceExplain) || kind.serves(ServiceKind.Graph)) &&
      runtime == null
    ) Left("runtime stores: required by evidence-explain and graph")
    // SE-P10-04 n5: split services must share one ownership record (QueryJobs records,
    // EvidenceExplain checks); a per-process default would silently make every explain 404.
    else if (ownership == null && kind != ServiceKind.All && kind != ServiceKind.Graph)
      Left(s"ownership: a shared QueryOwnership is required by ${kind.tag}")
    else {
      // Only the single-process composition may default to an in-memory record.
      val owners =
        Option(ownership).getOrElse(new InMemoryQueryOwnership(config.queryOwnershipCapacity))
      val pools = Vector.newBuilder[ThreadPoolExecutor]
      def pool(name: String, threads: Int, queue: Int) = {
        val p = Pools.bounded(name, threads, queue)
        pools += p
        p
      }
      def calls(p: ThreadPoolExecutor) = new Calls(p, config.queueWaitMillis)
      def breaker(name: String) =
        new CircuitBreaker(name, config.breakerFailureThreshold, config.breakerOpenMillis, clock)
      val breakers = Vector.newBuilder[CircuitBreaker]
      def policy(b: CircuitBreaker, c: Calls, through: StoreAccess = access) =
        new ReadPolicy(
          b.name,
          c,
          b,
          through,
          clock,
          config.readTimeoutMillis,
          config.readMaxAttempts,
          config.retryAfterSeconds
        )
      def deadline(): Deadline =
        Deadline(clock.monotonicNanos() + config.readRequestTimeoutMillis * 1000000L)

      val jobs = new JobStore(config.maxJobs, clock, config.retryAfterSeconds)
      // SE-P10-04 m2: key-value class calls (evidence, explain AND ownership) share one pool and
      // breaker; QueryJobs has it too, for recording ownership.
      //
      // SE-P10-05 F4: ownership calls use the same pool, timeouts, retries and breaker, but NOT
      // `access` (the store lock of the in-memory runtime stores): a QueryOwnership is required to
      // be safe for concurrent use, so an ownership call does not itself wait for another query's
      // workflow holding the serialized lock. CR-P10-06 n-A: it still shares this pool and breaker
      // with evidence and explain store reads, which do wait for that lock (residual N5); those
      // reads can fill the pool or open the breaker, and ownership calls then get 503 overloaded
      // or 503 circuit-open.
      //
      // Residual (CR-P10-06 U-3, recorded): the key-value pool has no `*-workers` readiness check
      // (only the workflow pools do); a key-value pool pinned by overdue calls shows only through
      // `circuit-key-value`.
      val kvBoth = Option.when(needsWorkflow || kind.serves(ServiceKind.EvidenceExplain)) {
        val b = breaker("key-value")
        breakers += b
        val c = calls(pool("key-value", config.keyValueWorkers, config.keyValueQueueCapacity))
        (policy(b, c), policy(b, c, StoreAccess.concurrent))
      }
      val kv = kvBoth.map(_._1)
      val ownershipKv = kvBoth.map(_._2)
      val query = Option.when(needsWorkflow) {
        val b = breaker("workflow")
        breakers += b
        new QueryService(
          workflow,
          calls(pool("workflow", config.workflowWorkers, config.workflowQueueCapacity)),
          calls(pool("job-workflow", config.jobWorkers, config.jobWorkers)),
          b,
          jobs,
          pool("jobs", config.jobWorkers, config.jobQueueCapacity),
          new IdempotencyStore(config.idempotencyCapacity, config.idempotencyTtlMillis, clock),
          owners,
          ownershipKv.get,
          () => deadline(),
          config,
          ids
        )
      }
      val evidence = Option.when(kind.serves(ServiceKind.EvidenceExplain))(
        new EvidenceService(
          runtime.keyValues,
          kv.get,
          config,
          () => deadline(),
          owners,
          ownershipKv.get
        )
      )
      val explain = Option.when(kind.serves(ServiceKind.EvidenceExplain))(
        new ExplainService(
          runtime.keyValues,
          kv.get,
          config,
          () => deadline(),
          owners,
          ownershipKv.get
        )
      )
      val graph = Option.when(kind.serves(ServiceKind.Graph)) {
        val b = breaker("graph")
        breakers += b
        new GraphService(
          runtime.graph,
          policy(b, calls(pool("graph", config.graphWorkers, config.graphQueueCapacity))),
          config,
          () => deadline()
        )
      }
      val allPools = pools.result()
      val allBreakers = breakers.result()
      val accepting = new AtomicBoolean(true)
      val health = new Health(
        Vector(
          ReadinessCheck("accepting-requests", () => accepting.get()),
          ReadinessCheck("credential-source", () => keys.clients().exists(_.nonEmpty)),
          ReadinessCheck("executors", () => allPools.forall(!_.isShutdown))
        ) ++
          // SE-P10-04 m3: fails when every workflow thread is pinned by an overdue call (an
          // interrupt-ignoring workflow keeps its thread; bounded by the pool's workers).
          query.toVector.flatMap(q =>
            Vector(
              ReadinessCheck("workflow-workers", () => q.syncWorkersLive),
              ReadinessCheck("job-workers", () => q.jobWorkersLive)
            )
          ) ++
          // m3: a half-open circuit whose trial never started is not reported healthy, for one
          // open period (SE-P10-05 F3: the mark expires without traffic).
          allBreakers.map(b => ReadinessCheck(s"circuit-${b.name}", () => b.ready))
      )
      val metrics = new Metrics
      val auth = new ApiKeyAuthenticator(keys, config.retryAfterSeconds)
      val jobCounts = () => if (query.isDefined) jobs.counts else Map.empty[String, Int]
      val router = new Router(
        kind,
        RoutedServices(query, evidence, graph, explain),
        health,
        metrics,
        () => metrics.render(jobCounts(), allBreakers.map(b => b.name -> b.state)),
        auth,
        accepting,
        accessLog,
        config,
        ids,
        clock
      )
      Right(
        new ServiceRuntime(
          kind,
          router,
          health,
          metrics,
          allBreakers,
          accepting,
          () => { allPools.foreach(_.shutdownNow()); () }
        )
      )
    }
  }
}
