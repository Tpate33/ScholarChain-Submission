package scholarchain.services

import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}
import java.nio.ByteBuffer
import java.util.concurrent.{ExecutorService, RejectedExecutionException}

import scala.util.control.NonFatal

import scholarchain.core.{Canonical, Json, QueryId}
import scholarchain.rag.agents.{CollaborationWorkflow, WorkflowOutcome, WorkflowResult}

/**
 * The Graph-RAG workflow port of the query service. The production implementation is rag's
 * [[CollaborationWorkflow]] (stages 1-10 including audit persistence); tests pass fakes that hang
 * or fail.
 */
trait QueryWorkflow {
  def answer(queryId: String, question: String): WorkflowResult
}

object QueryWorkflow {

  /**
   * rag's workflow, every call inside `access` (the runtime stores it reads and writes).
   *
   * SE-P10-03 N5 (residual, recorded): with [[StoreAccess.serialized]] (the local in-memory fakes)
   * the WHOLE workflow call holds the store lock, so a slow workflow blocks evidence, explain and
   * graph reads, which then time out, can open `circuit-key-value`/`circuit-graph` and fail
   * `/readyz`, although the pools are separate. The M2 test does not cover this (its hanging
   * workflow runs outside `access`). The fix is thread-safe store adapters
   * ([[StoreAccess.concurrent]]): owner storage-engineer.
   *
   * SE-P10-05 F4: ownership recording and lookups are NOT inside `access` (the ownership port must
   * be safe for concurrent use), so a query's ownership call does not itself wait for this lock.
   * CR-P10-06 n-A: it does share the `key-value` pool and breaker with evidence and explain store
   * reads, which DO wait for the lock; under this residual those reads can fill the pool or open
   * the breaker, and an ownership call then gets `503 overloaded` or `503 circuit-open`.
   */
  def of(workflow: CollaborationWorkflow, access: StoreAccess): QueryWorkflow =
    new QueryWorkflow {
      def answer(queryId: String, question: String): WorkflowResult =
        access(workflow.answer(queryId, question))
    }
}

/** Synchronous (answer in the response) or asynchronous (job reference) execution. */
enum QueryMode(val tag: String) {
  case Sync extends QueryMode("sync")
  case Async extends QueryMode("async")
}

/**
 * A validated POST /v1/query body: `{"question": <string>, "mode": "sync"|"async", "constraints":
 * {}}` (`mode` and `constraints` optional). No constraint key is supported yet: the rag workflow's
 * input is the question alone (window and path depth come from the question and configuration), so
 * every constraint is `422 unsupported-constraint` (deferred to rag-engineer).
 */
final case class QueryRequest(question: String, mode: QueryMode) {

  /**
   * SHA-256 of the canonical request (idempotency binding). U1 (CR-P11-01): every non-ASCII UTF-16
   * code unit is rendered as a `\uXXXX` escape before hashing, so the hashed text is pure ASCII and
   * `getBytes(UTF_8)` can never map an unpaired surrogate to `?`: distinct questions always have
   * distinct canonical texts. (parse already refuses unpaired surrogates; this keeps the digest
   * injective for any directly constructed request.)
   */
  def digest: String =
    Canonical.sha256HexUtf8(
      ujson.Obj("question" -> question, "mode" -> mode.tag).render(escapeUnicode = true)
    )
}

object QueryRequest {
  val Fields: Set[String] = Set("question", "mode", "constraints")

  /** The supported constraint keys (none in Phase 10). */
  val SupportedConstraints: Set[String] = Set.empty

  private def invalid(m: String) = Left(ApiError(ErrorCode.InvalidRequest, m))

  private def utf8(body: Array[Byte]): Option[String] =
    try
      Some(
        StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(body))
          .toString
      )
    catch { case _: CharacterCodingException => None }

  /**
   * SE-P10-02 U3: the largest nesting of arrays and objects accepted. The valid body nests to depth
   * 2; the bound is checked by a linear scan BEFORE the parser runs, so no input can drive a
   * recursive parse deep.
   */
  val MaxNesting = 16

  /** Nesting depth of `t` outside string literals (escapes honoured). */
  private[services] def nesting(t: String): Int = {
    var depth = 0
    var max = 0
    var inString = false
    var escaped = false
    var i = 0
    while (i < t.length && max <= MaxNesting) {
      val c = t.charAt(i)
      if (inString) {
        if (escaped) escaped = false
        else if (c == '\\') escaped = true
        else if (c == '"') inString = false
      } else if (c == '"') inString = true
      else if (c == '[' || c == '{') { depth += 1; if (depth > max) max = depth }
      else if (c == ']' || c == '}') depth -= 1
      i += 1
    }
    max
  }

  /** CR-P11-01-4: the fixed message of a repeated member name (the name is never echoed). */
  val DuplicateName = "the request body repeats a member name within one object"

  /** CR-P11-01-5: the fixed message of an ill-formed UTF-16 string (the text is never echoed). */
  val UnpairedSurrogate = "the request body holds a string with an unpaired UTF-16 surrogate"

  /** One open array (`names` null) or object of [[strictProblem]]. */
  private final class Frame(val names: scala.collection.mutable.HashSet[String]) {
    var expectName: Boolean = names != null
  }

  /** True when every surrogate code unit of `s` is part of a high-low pair. */
  private[services] def wellFormedUtf16(s: String): Boolean = {
    var i = 0
    var ok = true
    while (ok && i < s.length) {
      val c = s.charAt(i)
      if (Character.isHighSurrogate(c)) {
        if (i + 1 < s.length && Character.isLowSurrogate(s.charAt(i + 1))) i += 2
        else ok = false
      } else if (Character.isLowSurrogate(c)) ok = false
      else i += 1
    }
    ok
  }

  /** The decoded string literal whose body starts at `from` and the index after its quote. */
  private def literal(t: String, from: Int): (String, Int) = {
    val b = new java.lang.StringBuilder
    var j = from
    var open = true
    while (open && j < t.length) {
      val c = t.charAt(j)
      if (c == '"') { open = false; j += 1 }
      else if (c == '\\' && j + 1 < t.length) {
        t.charAt(j + 1) match {
          case 'u' if j + 5 < t.length =>
            b.append(Integer.parseInt(t.substring(j + 2, j + 6), 16).toChar); j += 6
          case 'b' => b.append('\b'); j += 2
          case 'f' => b.append('\f'); j += 2
          case 'n' => b.append('\n'); j += 2
          case 'r' => b.append('\r'); j += 2
          case 't' => b.append('\t'); j += 2
          case other => b.append(other); j += 2
        }
      } else { b.append(c); j += 1 }
    }
    (b.toString, j)
  }

  /**
   * CR-P11-01-4 and CR-P11-01-5: one linear scan of a body that ujson has ALREADY parsed (so `t` is
   * well-formed JSON), at every nesting level. Its first problem, if any:
   *
   *   - a member name repeated within one object, compared after escape decoding (`"question"` and
   *     `"question"` are the same name). ujson keeps the last value of a repeated name, so a
   *     validating intermediary and this service could read different requests (RFC 8259 §4 leaves
   *     the behaviour undefined; rejecting is the safe reading);
   *   - a string, member name or value, holding an unpaired UTF-16 surrogate. The UTF-8 decoder
   *     already refuses raw ill-formed bytes, so this can only arrive as a `\uXXXX` escape. Such
   *     text would otherwise enter the workflow and the audit, and `String.getBytes(UTF_8)` maps it
   *     to `?`, so two distinct questions could share one digest and byte length (U1).
   *
   * Both are `400 invalid-request` with a fixed message: neither the name nor the text is echoed.
   */
  private[services] def strictProblem(t: String): Option[String] = {
    val stack = new java.util.ArrayDeque[Frame]()
    var problem: Option[String] = None
    var i = 0
    while (problem.isEmpty && i < t.length) {
      t.charAt(i) match {
        case '"' =>
          val (s, next) = literal(t, i + 1)
          if (!wellFormedUtf16(s)) problem = Some(UnpairedSurrogate)
          else {
            val top = stack.peek()
            if (top != null && top.expectName) {
              top.expectName = false
              if (!top.names.add(s)) problem = Some(DuplicateName)
            }
          }
          i = next
        case '{' => stack.push(new Frame(scala.collection.mutable.HashSet.empty)); i += 1
        case '[' => stack.push(new Frame(null)); i += 1
        case '}' | ']' =>
          if (!stack.isEmpty) stack.pop()
          i += 1
        case ',' =>
          val top = stack.peek()
          if (top != null && top.names != null) top.expectName = true
          i += 1
        case _ => i += 1
      }
    }
    problem
  }

  def parse(body: Array[Byte], maxQuestionBytes: Int): Either[ApiError, QueryRequest] = {
    // Right(value) or Left(strict problem) of a well-formed body; None: not UTF-8 JSON.
    val json: Option[Either[String, ujson.Value]] =
      utf8(body)
        .filter(nesting(_) <= MaxNesting)
        .flatMap(t =>
          try Some(ujson.read(t)).map(v => strictProblem(t).toLeft(v))
          catch { case NonFatal(_) => None }
        )
    json match {
      case None =>
        Left(ApiError(ErrorCode.InvalidJson, "the request body is not well-formed UTF-8 JSON"))
      case Some(Left(problem)) => invalid(problem)
      case Some(Right(o: ujson.Obj)) =>
        val unknown = o.value.keySet.diff(Fields).toVector.sorted
        if (unknown.nonEmpty) invalid(s"unknown field(s): ${unknown.mkString(", ")}")
        else
          for {
            q <- o.value.get("question") match {
              case Some(ujson.Str(s)) => Right(s)
              case Some(_) => invalid("question: must be a string")
              case None => invalid("question: required")
            }
            _ <-
              if (q.trim.isEmpty) invalid("question: must not be blank")
              else if (Js.utf8Bytes(q) > maxQuestionBytes)
                invalid(s"question: longer than $maxQuestionBytes UTF-8 bytes")
              else if (q.exists(c => Character.isISOControl(c) && c != '\n' && c != '\t'))
                invalid("question: contains control characters")
              else Right(())
            mode <- o.value.get("mode") match {
              case None => Right(QueryMode.Sync)
              case Some(ujson.Str(m)) =>
                QueryMode.values
                  .find(_.tag == m)
                  .toRight(
                    ApiError(
                      ErrorCode.UnsupportedConstraint,
                      "mode: supported values are sync and async",
                      Vector("constraint" -> "mode")
                    )
                  )
              case Some(_) => invalid("mode: must be a string")
            }
            _ <- o.value.get("constraints") match {
              case None => Right(())
              case Some(c: ujson.Obj) =>
                val unsupported = c.value.keySet.diff(SupportedConstraints).toVector.sorted
                if (unsupported.isEmpty) Right(())
                else
                  Left(
                    ApiError(
                      ErrorCode.UnsupportedConstraint,
                      s"unsupported query constraint(s): ${unsupported.mkString(", ")}",
                      Vector("constraints" -> Js.strs(unsupported))
                    )
                  )
              case Some(_) => invalid("constraints: must be an object")
            }
          } yield QueryRequest(q, mode)
      case Some(_) => invalid("the request body must be a JSON object")
    }
  }
}

/** Rendering of workflow results and jobs (pure). */
object QueryViews {
  def explainLink(queryId: String): String = s"/v1/explain/$queryId"
  def evidenceLink(evidenceId: String): String = s"/v1/evidence/$evidenceId"
  def jobLink(jobId: String): String = s"/v1/jobs/$jobId"

  /**
   * The response of one finished workflow call. Answered and abstained questions are `200` (an
   * abstention is an outcome, never a guess); a refused question (no allowlisted operation, a
   * missing name, an unparseable window, ...) is `422 unsupported-query` with the planner's reason
   * tag. Question text and researcher names are never echoed.
   */
  def outcome(r: WorkflowResult): Either[ApiError, Reply] = {
    val q = r.audit.queryId
    val persisted = r.persistence.isRight
    val explain: ujson.Value = if (persisted) ujson.Str(explainLink(q)) else ujson.Null
    r.outcome match {
      case WorkflowOutcome.Answered(response, verification, nonProduction) =>
        Right(
          Reply(
            200,
            ujson.Obj(
              "queryId" -> q,
              "status" -> "answered",
              "answer" -> ujson.read(Json.write(response)),
              "verification" -> ujson.Obj(
                "overall" -> verification.overall.tag,
                "counts" -> Js.counts(verification.counts)
              ),
              "nonProductionModel" -> nonProduction,
              "auditPersisted" -> persisted,
              "links" -> ujson.Obj(
                "explain" -> explain,
                "evidence" -> Js.strs(
                  response.evidenceBundle.items.map(i => evidenceLink(i.evidenceId))
                )
              )
            )
          )
        )
      // SE-P10-02 m1: the workflow's free-text detail (it can carry AgentError and StoreError
      // text: keys, roles, store messages) is never returned; the reason tag is the stable
      // contract, as for refused outcomes. Gaps are the workflow's own user-facing gap lines.
      case WorkflowOutcome.Abstained(reason, _, names, gaps) =>
        Right(
          Reply(
            200,
            ujson.Obj(
              "queryId" -> q,
              "status" -> "abstained",
              "abstention" -> ujson.Obj(
                "reason" -> reason.tag,
                "gaps" -> Js.strs(gaps),
                "names" -> ujson.Arr.from(names.zipWithIndex.map { case (n, i) =>
                  ujson.Obj(
                    "ordinal" -> i,
                    "reason" -> n.reason.tag,
                    "candidateIds" -> Js.strs(n.candidates.map(_.researcherId)),
                    "truncated" -> n.truncated
                  )
                })
              ),
              "auditPersisted" -> persisted,
              "links" -> ujson.Obj("explain" -> explain)
            )
          )
        )
      case WorkflowOutcome.Refused(reason, _) =>
        Left(
          ApiError(
            ErrorCode.UnsupportedQuery,
            "the question asks for no supported collaboration analysis",
            Vector("reason" -> reason.tag, "queryId" -> q)
          )
        )
    }
  }

  def job(j: Job): ujson.Obj =
    ujson.Obj(
      "jobId" -> j.jobId,
      "queryId" -> j.queryId,
      "state" -> j.state.tag,
      "stage" -> j.stage,
      "createdAt" -> Js.iso(j.createdAtMillis),
      "startedAt" -> Js.str(j.startedAtMillis.map(Js.iso)),
      "finishedAt" -> Js.str(j.finishedAtMillis.map(Js.iso)),
      "error" -> Js.opt(j.error.map(e => ujson.Obj("code" -> e.code.tag, "message" -> e.message))),
      "links" -> ujson.Obj(
        "self" -> jobLink(j.jobId),
        "result" -> s"${jobLink(j.jobId)}/result"
      )
    )
}

/**
 * The Query API and Jobs API application service: validates the request, creates the query ID and
 * the idempotency record, runs the workflow synchronously (under the sync timeout) or as a job
 * (bounded executor, async timeout), and returns the answer or the job reference. The workflow call
 * is guarded by the `workflow` circuit breaker and is never retried (it persists create-once audit
 * rows under its query ID).
 */
final class QueryService(
    workflow: QueryWorkflow,
    syncCalls: Calls,
    jobCalls: Calls,
    breaker: CircuitBreaker,
    jobs: JobStore,
    jobRunner: ExecutorService,
    idempotency: IdempotencyStore,
    ownership: QueryOwnership,
    ownershipReads: ReadPolicy,
    deadline: () => Deadline,
    config: ServiceConfig,
    ids: IdSource
) {

  /** SE-P10-04 m3: readiness of the workflow pools (not every thread pinned by an overdue call). */
  def syncWorkersLive: Boolean = syncCalls.hasLiveThread
  def jobWorkersLive: Boolean = jobCalls.hasLiveThread
  private type Settled = java.util.concurrent.CompletableFuture[Unit]
  private val KeyPattern = "^[A-Za-z0-9._-]{1,128}$".r
  private val JobPattern = "^job-[A-Za-z0-9]{1,64}$".r

  private def retryAfter = ApiError.retryAfter(config.retryAfterSeconds)

  def submit(
      client: ApiClient,
      body: Array[Byte],
      idempotencyKey: Vector[String]
  ): Either[ApiError, Reply] =
    for {
      key <- idempotencyKey match {
        case Vector() => Right(None)
        case Vector(k) if KeyPattern.matches(k) => Right(Some(k))
        case _ =>
          Left(
            ApiError(
              ErrorCode.InvalidParameter,
              "Idempotency-Key: one value of 1-128 characters [A-Za-z0-9._-]"
            )
          )
      }
      request <- QueryRequest.parse(body, config.maxQuestionBytes)
      reply <- key match {
        case None => execute(client, request)._1.map(_._1)
        case Some(k) => idempotent(client, k, request)
      }
    } yield reply

  private def idempotent(client: ApiClient, key: String, request: QueryRequest) =
    idempotency.begin(client.clientId, key, request.digest) match {
      case IdempotencyBegin.Fresh =>
        // SE-P10-02 n2: the in-flight key is always finalised, also when execute throws.
        // SE-P10-03 N4: a retryable outcome (504 after a timeout, 503, 429) releases the key
        // only when the workflow call has actually ended, so a retry under the key can never run
        // a second workflow beside a first one that ignored its cancellation.
        // SE-P10-06 n-B: an accepted async job completes the key as JobRef at 202; any later
        // failure of that job (an ownership-record failure included) is its final error and is
        // replayed under the key until the key's TTL. Only sync failures release the key.
        var finalised = false
        try {
          val (out, settled) = execute(client, request)
          out match {
            case Left(e) if e.code.retryable =>
              settled.thenRun(() => idempotency.abandon(client.clientId, key))
              ()
            case Left(e) =>
              idempotency.complete(client.clientId, key, IdempotentOutcome.Final(Left(e)))
            case Right((_, Some(jobId))) =>
              idempotency.complete(client.clientId, key, IdempotentOutcome.JobRef(jobId))
            case Right((reply, None)) =>
              idempotency.complete(client.clientId, key, IdempotentOutcome.Final(Right(reply)))
          }
          finalised = true
          out.map(_._1)
        } finally if (!finalised) idempotency.abandon(client.clientId, key)
      case IdempotencyBegin.Replay(outcome) =>
        val replayed = Vector("Idempotent-Replayed" -> "true")
        (outcome match {
          case IdempotentOutcome.Final(r) => r
          case IdempotentOutcome.JobRef(jobId) =>
            jobs
              .get(client.clientId, jobId)
              .map(j => accepted(j))
              .toRight(
                ApiError(
                  ErrorCode.NotFound,
                  "the job of this Idempotency-Key is no longer retained"
                )
              )
        }) match {
          case Right(r) => Right(r.copy(headers = r.headers ++ replayed))
          case Left(e) => Left(e.copy(headers = e.headers ++ replayed))
        }
      case IdempotencyBegin.InProgress =>
        Left(
          ApiError(
            ErrorCode.IdempotencyInProgress,
            "a request with this Idempotency-Key is still running",
            headers = retryAfter
          )
        )
      case IdempotencyBegin.Mismatch =>
        Left(
          ApiError(
            ErrorCode.IdempotencyKeyReused,
            "this Idempotency-Key was used with a different request body"
          )
        )
      case IdempotencyBegin.Full =>
        Left(
          ApiError(
            ErrorCode.TooManyRequests,
            "the idempotency record capacity is exhausted",
            headers = retryAfter
          )
        )
    }

  private def accepted(j: Job): Reply =
    Reply(202, QueryViews.job(j), Vector("Location" -> QueryViews.jobLink(j.jobId)))

  private def newQueryId(): Either[ApiError, String] =
    QueryId
      .of(ids.token())
      .left
      .map(_ => ApiError(ErrorCode.Internal, "could not create a query ID"))

  /**
   * The workflow call on `calls` (the sync pool or the job pool) under `timeoutMillis` and the
   * breaker; never retried. A call that never started is `503 overloaded` and no breaker failure.
   */
  private def runWorkflow(
      calls: Calls,
      client: ApiClient,
      queryId: String,
      question: String,
      timeoutMillis: Long
  ): (Either[ApiError, Reply], Settled) =
    if (!breaker.allow())
      (
        Left(ApiError(ErrorCode.CircuitOpen, "the workflow circuit is open", headers = retryAfter)),
        QueryService.Done
      )
    else {
      val reached = new java.util.concurrent.atomic.AtomicBoolean(false)
      val (outcome, settled) =
        calls.runTracked(timeoutMillis)(admitted(client, queryId, question, reached))
      (render(outcome, queryId, timeoutMillis, reached.get), settled)
    }

  /**
   * The body of an ADMITTED workflow call (the circuit allowed it, a pool thread claimed it).
   *
   * N1: the submitting client owns the query (its explanation and its evidence occurrences are
   * served only to it). SE-P10-04 m2 policy: ownership is recorded BEFORE the workflow runs,
   * through the key-value policy; if it cannot be recorded the workflow does not run (503/504/502
   * from the policy), so no answer ever exists whose owner is unknown.
   *
   * SE-P10-05 U-b: recording happens only here, after admission. A submission refused by the open
   * circuit, a full or slow pool (`Rejected`, `NotStarted`) or a full job queue records nothing, so
   * a flood of refused submissions cannot evict older owners from a bounded ownership record. (A
   * flood of ADMITTED queries still can: the in-memory record is bounded by
   * `queryOwnershipCapacity`; a forgotten query fails closed, 404 and no occurrences.)
   *
   * SE-P10-06 m-A: the record runs under the workflow timer, so it is bounded in real time by
   * `readRequestTimeoutMillis`, which configuration requires to be below both workflow timeouts. A
   * hanging ownership store is therefore reported by the key-value policy (its 504/503/502, the
   * `key-value` breaker; message prefixed "recording query ownership"), never as a workflow
   * timeout. `reached` is set only when `workflow.answer` is about to start: a workflow timer that
   * still fires before it (scheduling jitter) is neutral for the `workflow` breaker.
   *
   * SE-P10-06 n-B: for an ASYNCHRONOUS query this runs inside the job, after the 202 was returned
   * and an Idempotency-Key was completed as that job. A record failure becomes the job's final
   * error, and the same key replays that job (and its error) until the key's TTL, as for every
   * other failure of an accepted job. Only a SYNCHRONOUS record failure releases the key.
   */
  private def admitted(
      client: ApiClient,
      queryId: String,
      question: String,
      reached: java.util.concurrent.atomic.AtomicBoolean
  ): Either[ApiError, WorkflowResult] =
    ownershipReads.read(deadline(), config.readRequestTimeoutMillis)(
      ownership.record(queryId, client.clientId)
    ) match {
      case Left(e) => Left(e.copy(message = s"recording query ownership: ${e.message}"))
      case Right(()) =>
        reached.set(true)
        Right(workflow.answer(queryId, question))
    }

  private def render(
      outcome: Either[CallFailure, Either[ApiError, WorkflowResult]],
      queryId: String,
      timeoutMillis: Long,
      reached: Boolean
  ): Either[ApiError, Reply] =
    outcome match {
      case Right(Left(e)) =>
        // Ownership could not be recorded: the workflow was never reached.
        breaker.neutral()
        Left(e)
      case Left(CallFailure.TimedOut) if !reached =>
        // m-A: the timer fired before workflow.answer started (still recording ownership).
        breaker.neutral()
        Left(
          ApiError(
            ErrorCode.Timeout,
            "recording query ownership did not finish within the workflow bound; " +
              "the workflow did not start",
            Vector("queryId" -> queryId)
          )
        )
      case Right(Right(result)) =>
        breaker.success()
        try QueryViews.outcome(result)
        catch {
          case NonFatal(_) =>
            Left(ApiError(ErrorCode.Internal, "the workflow result could not be rendered"))
        }
      case Left(CallFailure.TimedOut) =>
        breaker.failure()
        Left(
          ApiError(
            ErrorCode.Timeout,
            s"the Graph-RAG workflow did not finish within $timeoutMillis ms",
            Vector("queryId" -> queryId)
          )
        )
      case Left(CallFailure.Threw(_)) =>
        breaker.failure()
        Left(
          ApiError(
            ErrorCode.DownstreamFailure,
            "the Graph-RAG workflow failed",
            Vector("queryId" -> queryId)
          )
        )
      case Left(CallFailure.Rejected) | Left(CallFailure.NotStarted) =>
        breaker.notStarted()
        Left(
          ApiError(
            ErrorCode.Overloaded,
            "no capacity for the workflow call",
            headers = retryAfter
          )
        )
      case Left(CallFailure.Interrupted) =>
        breaker.neutral()
        Left(ApiError(ErrorCode.Internal, "the request was interrupted"))
    }

  /**
   * The outcome of one execution and `settled`: completed when its workflow call has actually ended
   * (immediately for an async submission or a call that never ran).
   */
  private def execute(
      client: ApiClient,
      request: QueryRequest
  ): (Either[ApiError, (Reply, Option[String])], Settled) =
    newQueryId() match {
      case Left(e) => (Left(e), QueryService.Done)
      // Ownership is recorded inside the admitted workflow call ([[admitted]], U-b).
      case Right(queryId) => run(client, request, queryId)
    }

  private def run(
      client: ApiClient,
      request: QueryRequest,
      queryId: String
  ): (Either[ApiError, (Reply, Option[String])], Settled) =
    request.mode match {
      case QueryMode.Sync =>
        val (out, settled) =
          runWorkflow(syncCalls, client, queryId, request.question, config.syncTimeoutMillis)
        (out.map(_ -> None), settled)
      case QueryMode.Async =>
        val jobId = s"job-${ids.token()}"
        val out = jobs.create(client.clientId, jobId, queryId).flatMap { job =>
          try {
            jobRunner.execute(() => runJob(jobId, client, queryId, request.question))
            Right(accepted(job) -> Some(jobId))
          } catch {
            case _: RejectedExecutionException =>
              jobs.remove(jobId)
              Left(
                ApiError(
                  ErrorCode.TooManyRequests,
                  "the asynchronous job queue is full",
                  headers = retryAfter
                )
              )
          }
        }
        (out, QueryService.Done)
    }

  /** SE-P10-02 n2: a job always reaches a final state, also when its run throws. */
  private def runJob(jobId: String, client: ApiClient, queryId: String, question: String): Unit = {
    var finalised = false
    try {
      jobs.start(jobId)
      runWorkflow(jobCalls, client, queryId, question, config.asyncTimeoutMillis)._1 match {
        case Right(reply) => jobs.succeed(jobId, reply)
        case Left(e) => jobs.fail(jobId, e)
      }
      finalised = true
    } finally
      if (!finalised)
        jobs.fail(jobId, ApiError(ErrorCode.Internal, "the job failed unexpectedly"))
  }

  private def jobOf(client: ApiClient, jobId: String): Either[ApiError, Job] =
    if (!JobPattern.matches(jobId))
      Left(ApiError(ErrorCode.InvalidParameter, "jobId: not a job ID"))
    else
      jobs
        .get(client.clientId, jobId)
        .toRight(ApiError(ErrorCode.NotFound, "no such job for this client"))

  /** GET /v1/jobs/{jobId}. */
  def job(client: ApiClient, jobId: String): Either[ApiError, Reply] =
    jobOf(client, jobId).map(j => Reply(200, QueryViews.job(j)))

  /** GET /v1/jobs/{jobId}/result: the query response, the job's error, or 409 while running. */
  def jobResult(client: ApiClient, jobId: String): Either[ApiError, Reply] =
    jobOf(client, jobId).flatMap { j =>
      (j.state, j.result, j.error) match {
        case (JobState.Succeeded, Some(r), _) => Right(r)
        case (JobState.Failed, _, Some(e)) => Left(e)
        case _ =>
          Left(
            ApiError(
              ErrorCode.JobNotFinished,
              s"the job is ${j.state.tag}",
              Vector("state" -> j.state.tag),
              retryAfter
            )
          )
      }
    }
}

object QueryService {

  /** An already-settled execution (nothing is still running). */
  val Done: java.util.concurrent.CompletableFuture[Unit] =
    java.util.concurrent.CompletableFuture.completedFuture(())
}
