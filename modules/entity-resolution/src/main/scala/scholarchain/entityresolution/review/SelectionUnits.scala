package scholarchain.entityresolution.review

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.util.control.NonFatal

import scholarchain.core.{SourceAuthorId, WorkId}

/**
 * One DS-P5-01 selection unit (a name group or a work cluster): its criterion and its split. Read
 * from `datasets/er-candidates/split-assignment.tsv` (owner: dataset-engineer) as data.
 */
final case class SelectionUnit(unitId: String, criterion: String, split: String)

/**
 * Maps candidate endpoints to the selection units whose stored records they come from. Built only
 * from the dataset-engineer files `split-assignment.tsv` (unit -> criterion, split) and
 * `PROVENANCE.tsv` (stored record -> unit), read as data. It never reads a candidate, a decision,
 * or a label.
 *
 * An endpoint's anchor is the record it was normalized from: the work of a mention, the work
 * itself, or the source author profile. A record that two units share (for example one OpenAlex
 * work returned for two DOIs) maps to both units.
 *
 * @param units
 *   every unit of `split-assignment.tsv`, by unit ID
 * @param byAnchor
 *   canonical record ID (`work:<ns>:<id>` or `source-author:openalex:<A-ID>`) to its unit IDs,
 *   sorted and distinct
 */
final case class SelectionUnits(
    units: Map[String, SelectionUnit],
    byAnchor: Map[String, Vector[String]]
) {

  /** The unit IDs of an endpoint's record (empty when the record is not in the selection). */
  def unitIdsOf(e: EndpointRef): Vector[String] =
    byAnchor.getOrElse(SelectionUnits.anchorOf(e), Vector.empty)

  /** The units of an endpoint's record. */
  def unitsOf(e: EndpointRef): Vector[SelectionUnit] = unitIdsOf(e).flatMap(units.get)
}

object SelectionUnits {
  val Heldout: String = "heldout"
  val Dev: String = "dev"
  val Splits: Set[String] = Set(Heldout, Dev)

  /** No selection units: every endpoint is outside the DS-P5-01 selection. */
  val Empty: SelectionUnits = SelectionUnits(Map.empty, Map.empty)

  /** The canonical record ID an endpoint was normalized from. */
  def anchorOf(e: EndpointRef): String = e.endpointKind match {
    case "mention" => e.workId.getOrElse(e.endpointId)
    case _ => e.endpointId
  }

  /**
   * The canonical record ID of one `PROVENANCE.tsv` row, from its `source`, `kind` and `record_id`
   * columns (`datasets/schemas/er-candidates.md`): dblp `rec` (dblp key), openalex `work-by-doi` (W
   * ID), openalex `author` (A ID), crossref `work-by-doi` (DOI). Any other combination is refused.
   */
  def anchor(source: String, kind: String, recordId: String): Either[String, String] =
    ((source, kind) match {
      case ("dblp", "rec") => Right(WorkId.dblp(recordId))
      case ("openalex", "work-by-doi") => Right(WorkId.openAlex(recordId))
      case ("openalex", "author") => Right(SourceAuthorId.openAlex(recordId))
      case ("crossref", "work-by-doi") => Right(WorkId.doi(recordId))
      case _ => Left(s"unknown record source/kind '$source/$kind'")
    }).flatMap(_.left.map(_.message))

  /**
   * Rows of a tab-separated file: `#` comment lines and blank lines are skipped, the first other
   * line is the header, and every row must have exactly the header's column count.
   */
  def parseTsv(
      text: String,
      name: String,
      required: Vector[String]
  ): Either[Vector[String], Vector[Map[String, String]]] = {
    val lines = text.split("\n", -1).toVector.map(_.stripSuffix("\r")).zipWithIndex.filter {
      case (l, _) => l.trim.nonEmpty && !l.startsWith("#")
    }
    lines.headOption match {
      case None => Left(Vector(s"$name: no header line"))
      case Some((h, _)) =>
        val header = h.split("\t", -1).toVector
        val missing = required.filterNot(header.contains)
        if (missing.nonEmpty) Left(Vector(s"$name: header lacks ${missing.mkString(", ")}"))
        else {
          val parsed = lines.tail.map { case (l, i) =>
            val cells = l.split("\t", -1).toVector
            if (cells.size != header.size)
              Left(s"$name line ${i + 1}: ${cells.size} columns, header has ${header.size}")
            else Right(header.zip(cells).toMap)
          }
          val errors = parsed.collect { case Left(e) => e }
          if (errors.nonEmpty) Left(errors) else Right(parsed.collect { case Right(r) => r })
        }
    }
  }

  /**
   * Builds the mapping from the text of `split-assignment.tsv` and `PROVENANCE.tsv`. Refused when a
   * unit is duplicated, a split is not `heldout` or `dev`, a provenance row names an unknown unit
   * or disagrees with the unit's criterion or split, or a record ID is not canonicalizable.
   */
  def fromText(
      splitAssignment: String,
      provenance: String
  ): Either[Vector[String], SelectionUnits] =
    for {
      sa <- parseTsv(splitAssignment, "split-assignment", Vector("unit_id", "criterion", "split"))
      pv <- parseTsv(
        provenance,
        "PROVENANCE",
        Vector("unit_id", "split", "criterion", "source", "kind", "record_id")
      )
      units <- {
        val us = sa.map(r => SelectionUnit(r("unit_id"), r("criterion"), r("split")))
        val dup = us.groupBy(_.unitId).collect { case (id, xs) if xs.size > 1 => id }
        val errors =
          dup.toVector.sorted.map(id => s"split-assignment: unit $id is listed twice") ++
            us.filterNot(u => Splits.contains(u.split))
              .map(u => s"split-assignment: unit ${u.unitId} has split '${u.split}'") ++
            us.filter(u => u.unitId.isEmpty || u.criterion.isEmpty)
              .map(u => s"split-assignment: empty unit ID or criterion (${u.unitId})")
        if (errors.nonEmpty) Left(errors) else Right(us.map(u => u.unitId -> u).toMap)
      }
      byAnchor <- {
        val rows = pv.map { r =>
          val unit = r("unit_id")
          (units.get(unit) match {
            case None => Left(s"PROVENANCE: unit $unit is not in split-assignment")
            case Some(u) if u.split != r("split") || u.criterion != r("criterion") =>
              Left(
                s"PROVENANCE: unit $unit is ${u.criterion}/${u.split} in split-assignment but " +
                  s"${r("criterion")}/${r("split")} in PROVENANCE"
              )
            case Some(_) =>
              anchor(r("source"), r("kind"), r("record_id")).left
                .map(e => s"PROVENANCE: record ${r("record_id")} of unit $unit: $e")
          }).map(_ -> unit)
        }
        val errors = rows.collect { case Left(e) => e }
        if (errors.nonEmpty) Left(errors)
        else
          Right(
            rows
              .collect { case Right(x) => x }
              .groupBy(_._1)
              .map { case (a, xs) => a -> xs.map(_._2).distinct.sorted }
          )
      }
    } yield SelectionUnits(units, byAnchor)

  /** [[fromText]] over the two files. */
  def load(splitAssignment: Path, provenance: Path): Either[Vector[String], SelectionUnits] =
    try
      fromText(
        Files.readString(splitAssignment, StandardCharsets.UTF_8),
        Files.readString(provenance, StandardCharsets.UTF_8)
      )
    catch {
      case NonFatal(e) => Left(Vector(s"cannot read selection files: ${e.getMessage}"))
    }
}
