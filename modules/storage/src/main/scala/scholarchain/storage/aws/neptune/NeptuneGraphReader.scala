package scholarchain.storage.aws.neptune

import java.net.URI

import scala.collection.immutable.SortedMap
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import software.amazon.awssdk.core.document.Document
import software.amazon.awssdk.services.neptunedata.NeptunedataClient
import software.amazon.awssdk.services.neptunedata.model.{
  ExecuteOpenCypherQueryRequest,
  ExecuteOpenCypherQueryResponse
}

import scholarchain.storage.aws.{AwsClientSettings, AwsErrors}
import scholarchain.storage.model.{
  BoundQuery,
  GraphParamValue,
  GraphQuery,
  GraphRow,
  GraphValue,
  StoreError
}
import scholarchain.storage.port.GraphReader

/**
 * SE-INT-04 (A4): the narrow seam over the SDK `NeptunedataClient`: ExecuteOpenCypherQuery only. No
 * Gremlin, SPARQL, loader, reset or management operation is reachable through it. (IAM should grant
 * `neptune-db:ReadDataViaQuery` only, Q11: routing item for infra-engineer.)
 */
trait NeptuneCalls {
  def executeOpenCypherQuery(request: ExecuteOpenCypherQueryRequest): ExecuteOpenCypherQueryResponse
}

object NeptuneCalls {
  def of(client: NeptunedataClient): NeptuneCalls = {
    require(client != null, "client is required")
    new NeptuneCalls {
      def executeOpenCypherQuery(request: ExecuteOpenCypherQueryRequest) =
        client.executeOpenCypherQuery(request)
    }
  }
}

/**
 * SE-INT-04: settings of the neptunedata client. The endpoint is required and structurally checked
 * only (D3): `https`, a host, an explicit port (Q11: `https://<cluster endpoint>:<port>`, default
 * port 8182), no user info, query, fragment or path. Whether it is the reader (`cluster-ro-`)
 * endpoint is NOT checked here (routed to core G2 and infra/IAM; whether the data API works against
 * the reader endpoint is NOT STATED, UNVERIFIED (P)).
 */
final case class NeptuneClientSettings private (client: AwsClientSettings) {
  def endpoint: URI = client.endpointOverride.get
}

object NeptuneClientSettings {
  def of(
      region: String,
      endpoint: String,
      apiCallTimeoutMillis: Long,
      apiCallAttemptTimeoutMillis: Long,
      retryMaxAttempts: Int
  ): Either[StoreError, NeptuneClientSettings] =
    for {
      u <- checkEndpoint(endpoint)
      c <- AwsClientSettings.of(
        region,
        None,
        apiCallTimeoutMillis,
        apiCallAttemptTimeoutMillis,
        retryMaxAttempts
      )
    } yield new NeptuneClientSettings(AwsClientSettings.withEndpoint(c, u))

  private def checkEndpoint(s: String): Either[StoreError, URI] = {
    val bad = Left(
      StoreError.Refused(
        "neptune endpoint: must be https://<host>:<port> with no user info, path, query or fragment"
      )
    )
    if (s == null || s.exists(c => c.isWhitespace || c.isControl)) bad
    else
      (try Some(new URI(s))
      catch { case NonFatal(_) => None }) match {
        case Some(u)
            if u.getScheme == "https" && Option(u.getHost).exists(_.nonEmpty) &&
              u.getPort >= 1 && u.getPort <= 65535 && u.getRawUserInfo == null &&
              u.getRawQuery == null && u.getRawFragment == null &&
              Option(u.getRawPath).forall(p => p.isEmpty || p == "/") =>
          Right(u)
        case _ => bad
      }
  }
}

/**
 * SE-INT-04: bounds of the graph reader. `maxRows` is the most rows one query may return; more is
 * `Permanent` (refused, never truncated). It must be at least the services graph limits it serves
 * (a G4 wiring check).
 */
final case class NeptuneReaderSettings private (maxRows: Int)

object NeptuneReaderSettings {
  def of(maxRows: Int): Either[StoreError, NeptuneReaderSettings] =
    if (maxRows >= 1) Right(new NeptuneReaderSettings(maxRows))
    else Left(StoreError.Refused("neptune: maxRows must be >= 1"))
}

/**
 * SE-INT-04 (A4): the Neptune [[GraphReader]].
 *
 *   - Request: the template text of the [[BoundQuery]] is sent UNCHANGED as `openCypherQuery` (the
 *     SDK field name, verified at compile time against 2.57.8; the HTTP API names it `query`, Q10),
 *     and its parameters as one JSON object string (`Str` as a JSON string, `IntValue` as a JSON
 *     number). Nothing is concatenated into the text and no query hint is added (Q11: the server
 *     bound is the cluster `neptune_query_timeout`, infra-engineer; the client bound is the SDK
 *     timeouts).
 *   - Response: `results` must be a list of maps whose key set equals exactly the template's `AS`
 *     aliases ([[NeptuneGraphReader.columns]]). A JSON string is `GraphValue.Str`, an integral JSON
 *     number within Long is `IntValue` (Q10), a document null is `Null` (the null encoding is NOT
 *     STATED, UNVERIFIED (P); an absent column, a boolean, a fractional number, a list or a map is
 *     `Corrupt`). More than `maxRows` rows is `Permanent`; more rows than the template's `$limit`
 *     is `Corrupt`.
 *   - Errors: Neptune's documented retry table (Q10, [[NeptuneGraphReader.retryable]]), then the
 *     common rules of [[AwsErrors]].
 */
final class NeptuneGraphReader private (calls: NeptuneCalls, settings: NeptuneReaderSettings)
    extends GraphReader {
  import NeptuneGraphReader.*

  def run(query: BoundQuery): Either[StoreError, Vector[GraphRow]] =
    if (query == null) Left(StoreError.Refused("neptune: query missing"))
    else {
      val name = query.query.toString
      val request = ExecuteOpenCypherQueryRequest
        .builder()
        .openCypherQuery(query.query.text)
        .parameters(parametersJson(query))
        .build()
      (try Right(calls.executeOpenCypherQuery(request))
      catch {
        case NonFatal(e) =>
          Left(AwsErrors.classify("Neptune", s"openCypher $name", e, retryable))
      }).flatMap(resp => decode(name, query, Option(resp).flatMap(r => Option(r.results()))))
    }

  private def decode(
      name: String,
      query: BoundQuery,
      results: Option[Document]
  ): Either[StoreError, Vector[GraphRow]] = {
    def corrupt(why: String) = Left(StoreError.Corrupt(s"Neptune openCypher $name: $why"))
    results match {
      case None => corrupt("no results")
      case Some(d) if !d.isList => corrupt("results is not a list")
      case Some(d) =>
        val rows = d.asList().asScala.toVector
        val limit = query.parameters.get("limit").collect { case GraphParamValue.IntValue(n) =>
          n
        }
        if (rows.size > settings.maxRows)
          Left(
            StoreError.Permanent(
              s"Neptune openCypher $name: ${rows.size} rows, above maxRows ${settings.maxRows}"
            )
          )
        else if (limit.exists(rows.size > _)) corrupt(s"${rows.size} rows, above the query limit")
        else {
          val expected = columns(query.query)
          rows.foldLeft[Either[StoreError, Vector[GraphRow]]](Right(Vector.empty)) { (acc, r) =>
            acc.flatMap(out =>
              if (r == null || !r.isMap) corrupt("a row is not a map")
              else {
                val m = r.asMap().asScala.toMap
                if (m.keySet != expected)
                  corrupt(
                    s"row columns ${m.keySet.toVector.sorted.mkString(",")} differ from " +
                      expected.toVector.sorted.mkString(",")
                  )
                else
                  m.toVector
                    .foldLeft[Either[StoreError, SortedMap[String, GraphValue]]](
                      Right(SortedMap.empty)
                    ) { case (cols, (k, v)) =>
                      cols.flatMap(c =>
                        value(v)
                          .map(c.updated(k, _))
                          .left
                          .map(why =>
                            StoreError.Corrupt(s"Neptune openCypher $name column $k: $why")
                          )
                      )
                    }
                    .map(c => out :+ GraphRow(c))
              }
            )
          }
        }
    }
  }
}

object NeptuneGraphReader {

  def of(calls: NeptuneCalls, settings: NeptuneReaderSettings): NeptuneGraphReader = {
    require(calls != null && settings != null, "calls and settings are required")
    new NeptuneGraphReader(calls, settings)
  }

  private val Alias = """\bAS ([A-Za-z][A-Za-z0-9]*)""".r

  /** The result columns of a template: its `AS` aliases. */
  def columns(q: GraphQuery): Set[String] = Alias.findAllMatchIn(q.text).map(_.group(1)).toSet

  /**
   * The templates with a parameterized `LIMIT $limit` (none uses SKIP). Neptune does not support
   * RETURN or WITH "with non-static values for SKIP or LIMIT", and whether a parameter is static is
   * NOT STATED (Q12): each is a live-verification risk, UNVERIFIED (P). Templates are unchanged.
   */
  def parameterizedLimitTemplates: Vector[GraphQuery] =
    GraphQuery.values.toVector.filter(_.text.contains("LIMIT $limit"))

  /** The parameters of `q` as one compact JSON object (sorted keys). */
  def parametersJson(q: BoundQuery): String =
    ujson.Obj
      .from(q.parameters.toVector.map {
        case (k, GraphParamValue.Str(s)) => k -> ujson.Str(s)
        case (k, GraphParamValue.IntValue(n)) => k -> ujson.Num(n.toDouble)
      })
      .render()

  /** One result value (Q10: integers are JSON numbers, strings and IDs JSON strings). */
  def value(d: Document): Either[String, GraphValue] =
    if (d == null || d.isNull) Right(GraphValue.Null) // null encoding NOT STATED (P)
    else if (d.isString) Right(GraphValue.Str(d.asString()))
    else if (d.isNumber) {
      val b = d.asNumber().bigDecimalValue()
      val integral = b.signum == 0 || b.stripTrailingZeros.scale <= 0
      if (!integral) Left("a fractional number")
      else
        try Right(GraphValue.IntValue(b.longValueExact()))
        catch { case _: ArithmeticException => Left("an integer outside Long") }
    } else if (d.isBoolean) Left("a boolean")
    else if (d.isList) Left("a list")
    else Left("a map")

  private val RetryYes = Set(
    "CancelledByUser",
    "ConcurrentModification",
    "ConstraintViolation",
    "FailureByQuery",
    "MemoryLimitExceeded",
    "QueryLimitExceeded",
    "Throttling",
    "TooManyRequests",
    // "Retry Maybe" in the table; treated as retryable by orchestrator decision (Q10).
    "TimeLimitExceeded"
  )

  private val RetryNo = Set(
    "AccessDenied",
    "BadRequest",
    "InternalFailure",
    "InvalidNumericData",
    "InvalidParameter",
    "MalformedQuery",
    "MethodNotAllowed",
    "MissingParameter",
    "QueryLimit",
    "QueryTooLarge",
    "ReadOnlyViolation",
    "UnsupportedOperation",
    // Listed without a retry flag (NOT STATED): never retried (fail closed, P).
    "ClientTimeout",
    "Parsing",
    "PreconditionsFailed"
  )

  /**
   * Neptune's engine error-code retry table (errors-engine-codes.html, checked 2026-11-22). Codes
   * are compared without an `Exception` suffix. None for an unlisted code (the common status rule
   * of [[AwsErrors]] then applies).
   */
  def retryable(code: String): Option[Boolean] = {
    val c = Option(code).getOrElse("").stripSuffix("Exception")
    if (RetryYes.contains(c)) Some(true)
    else if (RetryNo.contains(c)) Some(false)
    else None
  }
}
