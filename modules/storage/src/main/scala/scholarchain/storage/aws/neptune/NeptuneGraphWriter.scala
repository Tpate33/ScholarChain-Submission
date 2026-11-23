package scholarchain.storage.aws.neptune

import scala.collection.immutable.SortedMap
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import software.amazon.awssdk.core.document.Document
import software.amazon.awssdk.services.neptunedata.model.ExecuteOpenCypherQueryRequest
import scholarchain.core.{CanonicalGraph, EdgeLabel, NodeLabel, StableId}
import scholarchain.storage.aws.AwsErrors
import scholarchain.storage.model.*
import scholarchain.storage.port.GraphWriter

/**
 * Loader-only adapter; never exposed by RuntimeStores. Uses the existing fixed MERGE/ON CREATE
 * templates and parameterized, bounded ID probes. All writes are verified with a second probe.
 * Custom IDs and MERGE documented for Neptune Database >=1.2.0.2 (checked 2026-11-23):
 * https://docs.aws.amazon.com/neptune/latest/userguide/access-graph-opencypher-extensions.html
 * Exact map SET/response shapes still require the approved live run; no live claim is made. Use one
 * externally serialized loader: an absent-before/present-after race cannot distinguish this writer
 * from a concurrent identical create, so Written is not a concurrent audit count.
 */
final class NeptuneGraphWriter private (calls: NeptuneCalls) extends GraphWriter {
  import NeptuneGraphWriter.*

  private def execute(text: String, params: ujson.Obj): Either[StoreError, Document] =
    try {
      val response = calls.executeOpenCypherQuery(
        ExecuteOpenCypherQueryRequest
          .builder()
          .openCypherQuery(text)
          .parameters(params.render())
          .build()
      )
      Option(response)
        .flatMap(r => Option(r.results()))
        .toRight(StoreError.Corrupt("Neptune loader: missing results"))
    } catch {
      case NonFatal(e) =>
        Left(AwsErrors.classify("Neptune", "loader openCypher", e, NeptuneGraphReader.retryable))
    }

  private def probe(id: String, edge: Boolean): Either[StoreError, Option[Map[String, Document]]] =
    if (id == null || StableId.validateAny(id, "id").isLeft)
      Left(StoreError.Refused("Neptune loader: invalid ID"))
    else
      execute(if (edge) EdgeProbe else VertexProbe, ujson.Obj("id" -> id)).flatMap { d =>
        if (!d.isList) Left(StoreError.Corrupt("Neptune loader: probe is not a list"))
        else {
          val rows = d.asList().asScala.toVector
          if (rows.size > 1 || rows.exists(r => r == null || !r.isMap))
            Left(StoreError.Corrupt("Neptune loader: invalid probe rows"))
          else Right(rows.headOption.map(_.asMap().asScala.toMap))
        }
      }

  private def string(row: Map[String, Document], key: String): Either[StoreError, String] =
    row
      .get(key)
      .filter(d => d != null && d.isString)
      .map(_.asString())
      .toRight(StoreError.Corrupt("Neptune loader: missing string column " + key))

  private def properties(
      row: Map[String, Document]
  ): Either[StoreError, (SortedMap[String, GraphValue], GraphProvenance)] =
    for {
      raw <- row
        .get("properties")
        .filter(d => d != null && d.isMap)
        .toRight(StoreError.Corrupt("Neptune loader: missing properties map"))
      props <- raw
        .asMap()
        .asScala
        .toVector
        .foldLeft[Either[StoreError, SortedMap[String, GraphValue]]](Right(SortedMap.empty)) {
          case (acc, (k, v)) =>
            for {
              a <- acc
              value <- NeptuneGraphReader
                .value(v)
                .left
                .map(_ => StoreError.Corrupt("Neptune loader: invalid property value"))
            } yield a.updated(k, value)
        }
      p <- {
        def s(k: String) = props.get(k).collect { case GraphValue.Str(v) => v }
        (s("runId"), s("loadId"), s("sourcePath"), s("recordSchemaVersion")) match {
          case (Some(r), Some(l), Some(path), Some(schema)) =>
            Right(GraphProvenance(r, l, path, schema))
          case _ => Left(StoreError.Corrupt("Neptune loader: missing provenance"))
        }
      }
    } yield (props -- Vector("loadId", "sourcePath", "recordSchemaVersion"), p)

  def storedVertex(id: String): Either[StoreError, Option[StoredVertex]] =
    probe(id, false).flatMap {
      case None => Right(None)
      case Some(row) =>
        for {
          rid <- string(row, "id")
          label <- row
            .get("labels")
            .filter(d => d != null && d.isList)
            .map(_.asList().asScala.toVector) match {
            case Some(Vector(d)) if d != null && d.isString =>
              NodeLabel.values
                .find(_.tag == d.asString())
                .toRight(StoreError.Corrupt("Neptune loader: unknown vertex label"))
            case _ =>
              Left(StoreError.Corrupt("Neptune loader: expected one canonical vertex label"))
          }
          pp <- properties(row)
          v = StoredVertex(rid, label, pp._1, pp._2)
          _ <- BoundGraphWrite.vertex(v)
          _ <-
            if (rid == id) Right(())
            else Left(StoreError.Corrupt("Neptune loader: probe returned another ID"))
        } yield Some(v)
    }

  def storedEdge(id: String): Either[StoreError, Option[StoredEdge]] = probe(id, true).flatMap {
    case None => Right(None)
    case Some(row) =>
      for {
        rid <- string(row, "id")
        tag <- string(row, "label")
        label <- EdgeLabel.values
          .find(_.tag == tag)
          .toRight(StoreError.Corrupt("Neptune loader: unknown edge label"))
        from <- string(row, "fromId")
        to <- string(row, "toId")
        pp <- properties(row)
        e = StoredEdge(rid, label, from, to, pp._1, pp._2)
        _ <- BoundGraphWrite.edge(e)
        _ <-
          if (rid == id) Right(())
          else Left(StoreError.Corrupt("Neptune loader: probe returned another ID"))
      } yield Some(e)
  }

  private def end(id: String, allowed: Set[NodeLabel]): Either[StoreError, Unit] =
    storedVertex(id).flatMap {
      case Some(v) if allowed(v.label) => Right(())
      case _ => Left(StoreError.Refused("Neptune loader: edge endpoint absent or wrong label"))
    }

  def write(w: BoundGraphWrite): Either[StoreError, PutOutcome] = synchronized {
    if (w == null) Left(StoreError.Refused("Neptune loader: write missing"))
    else {
      val (id, props, p) = w.element.fold(
        v => (v.id, v.properties, v.provenance),
        e => (e.id, e.properties, e.provenance)
      )
      if (
        props.exists { case (k, v) =>
          Reserved(k) || v == null || v == GraphValue.Null || (v match {
            case GraphValue.IntValue(n) => n < -MaxSafeInteger || n > MaxSafeInteger
            case GraphValue.Str(s) => s == null
            case _ => false
          })
        } || props.get("runId") != Some(GraphValue.Str(p.runId))
      )
        Left(
          StoreError.Refused("Neptune loader: invalid properties or inconsistent run provenance")
        )
      else {
        def current: Either[StoreError, Option[Either[StoredVertex, StoredEdge]]] =
          w.element match {
            case Left(_) => storedVertex(id).map(_.map(Left(_)))
            case Right(_) => storedEdge(id).map(_.map(Right(_)))
          }
        current.flatMap {
          case Some(existing) if existing == w.element => Right(PutOutcome.AlreadyPresent)
          case Some(_) => Left(StoreError.Conflict("Neptune loader: ID has different element"))
          case None =>
            for {
              _ <- w.element match {
                case Left(_) => Right(())
                case Right(e) =>
                  val (a, b) = CanonicalGraph.Endpoints(e.label)
                  end(e.fromId, a).flatMap(_ => end(e.toId, b))
              }
              params = ujson.Obj(
                "id" -> id,
                "properties" -> ujson.Obj.from(props.map {
                  case (k, GraphValue.Str(s)) => k -> ujson.Str(s)
                  case (k, GraphValue.IntValue(n)) => k -> ujson.Num(n.toDouble)
                  case (k, GraphValue.Null) => k -> ujson.Null
                }),
                "provenance" -> ujson.Obj(
                  "runId" -> p.runId,
                  "loadId" -> p.loadId,
                  "sourcePath" -> p.sourcePath,
                  "recordSchemaVersion" -> p.recordSchemaVersion
                )
              )
              _ = w.element.foreach(e => {
                params("fromId") = ujson.Str(e.fromId); params("toId") = ujson.Str(e.toId)
              })
              _ <- execute(w.template.text, params)
              after <- current
              _ <-
                if (after.contains(w.element)) Right(())
                else
                  Left(
                    StoreError.Conflict("Neptune loader: create verification differs or is absent")
                  )
            } yield PutOutcome.Written
        }
      }
    }
  }
}
object NeptuneGraphWriter {
  private val Reserved = Set("~id", "~label", "loadId", "sourcePath", "recordSchemaVersion")
  private val MaxSafeInteger = (1L << 53) - 1
  private val VertexProbe =
    "MATCH (v) WHERE id(v) = $id RETURN id(v) AS id, labels(v) AS labels, properties(v) AS properties LIMIT 2"
  private val EdgeProbe =
    "MATCH (a)-[e]->(b) WHERE id(e) = $id RETURN id(e) AS id, type(e) AS label, id(a) AS fromId, id(b) AS toId, properties(e) AS properties LIMIT 2"
  def of(calls: NeptuneCalls): NeptuneGraphWriter = {
    require(calls != null, "calls required")
    new NeptuneGraphWriter(calls)
  }
}
