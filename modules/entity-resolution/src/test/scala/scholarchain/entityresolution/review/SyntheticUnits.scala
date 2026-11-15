package scholarchain.entityresolution.review

/**
 * SYNTHETIC selection units for tests: `split-assignment.tsv` and `PROVENANCE.tsv` text in the
 * DS-P5-01 column layout, generated for the given record anchors. Not dataset files, not labels.
 *
 * @param units
 *   (unit ID, criterion, split, canonical record IDs of the unit's records)
 */
object SyntheticUnits {

  /** (source, kind, record_id) of a canonical record ID. */
  def recordOf(anchor: String): (String, String, String) =
    if (anchor.startsWith("work:dblp:")) ("dblp", "rec", anchor.stripPrefix("work:dblp:"))
    else if (anchor.startsWith("work:doi:"))
      ("crossref", "work-by-doi", anchor.stripPrefix("work:doi:"))
    else if (anchor.startsWith("work:openalex:"))
      ("openalex", "work-by-doi", anchor.stripPrefix("work:openalex:"))
    else if (anchor.startsWith("source-author:openalex:"))
      ("openalex", "author", anchor.stripPrefix("source-author:openalex:"))
    else throw new IllegalArgumentException(s"no synthetic record form for $anchor")

  def texts(units: Vector[(String, String, String, Vector[String])]): (String, String) = {
    val sa = ("# SYNTHETIC split assignment" +: "unit_id\tcriterion\tsplit\trank_in_criterion" +:
      units.map { case (u, c, s, _) => s"$u\t$c\t$s\t1" }).mkString("\n") + "\n"
    val pv = ("# SYNTHETIC provenance" +:
      "file\tunit_id\tsplit\tcriterion\tsource\tkind\trecord_id" +:
      units.flatMap { case (u, c, s, anchors) =>
        anchors.map { a =>
          val (src, kind, id) = recordOf(a)
          s"synthetic/$u\t$u\t$s\t$c\t$src\t$kind\t$id"
        }
      }).mkString("\n") + "\n"
    (sa, pv)
  }

  def of(units: Vector[(String, String, String, Vector[String])]): SelectionUnits = {
    val (sa, pv) = texts(units)
    SelectionUnits.fromText(sa, pv).fold(e => throw new AssertionError(e.mkString("; ")), identity)
  }
}
