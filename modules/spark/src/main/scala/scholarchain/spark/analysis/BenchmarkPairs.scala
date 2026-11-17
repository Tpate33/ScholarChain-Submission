package scholarchain.spark.analysis

import scholarchain.core.*

/**
 * Loader of the versioned benchmark pair list (`scholarchain.benchmark-pairs.v1`,
 * [[BenchmarkPairList]]): the pairs for which a later step stores shortest paths. SP-P6-02 step 1
 * loads and validates the list only; no path is computed yet.
 *
 * The committed text must be exactly the canonical JSON of the decoded list (`Json.write`) plus one
 * LF, so the file bytes and [[BenchmarkPairList.digest]] describe the same list. Besides the record
 * contract, the list must be bound to the analysis run's grouping run (`provenance.runId`) and name
 * only researcher IDs that are identified in that grouping run.
 */
object BenchmarkPairs {

  def parse(text: String): Either[Vector[String], BenchmarkPairList] =
    if (text == null || !text.endsWith("\n") || text.count(_ == '\n') != 1)
      Left(Vector("the list must be one line of canonical JSON ending in LF"))
    else
      Json.decode[BenchmarkPairList](text.stripSuffix("\n")) match {
        case Left(e) => Left(e.map(_.toString))
        case Right(l) if Json.write(l) != text.stripSuffix("\n") =>
          Left(Vector("the list is not in canonical JSON form (Json.write of the decoded record)"))
        case Right(l) => Right(l)
      }

  /** Problems binding `list` to the grouping run `groupingRunId` and its identified researchers. */
  def bindingProblems(
      list: BenchmarkPairList,
      groupingRunId: String,
      identified: Set[String]
  ): Vector[String] = {
    val run =
      if (list.provenance.runId != groupingRunId)
        Vector(s"provenance.runId is ${list.provenance.runId}, the grouping run is $groupingRunId")
      else Vector.empty
    val ids = list.pairs.zipWithIndex.flatMap { case (p, i) =>
      Vector(p.researcherA, p.researcherB)
        .filterNot(identified.contains)
        .map(r => s"pairs[$i]: $r is not an identified researcher of the grouping run")
    }
    run ++ ids
  }

  /** The identified researcher IDs of a grouping run's researcher assignments. */
  def identifiedOf(researchers: Vector[ResearcherAssignment]): Set[String] =
    researchers.filter(_.status.identified).flatMap(_.researcherId).toSet

  def load(
      text: String,
      groupingRunId: String,
      identified: Set[String]
  ): Either[Vector[String], BenchmarkPairList] =
    parse(text).flatMap { l =>
      val b = bindingProblems(l, groupingRunId, identified)
      if (b.isEmpty) Right(l) else Left(b)
    }
}
