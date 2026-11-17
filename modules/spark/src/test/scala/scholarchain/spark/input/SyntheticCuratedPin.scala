package scholarchain.spark.input

/**
 * Test-only access to the `private[input]` synthetic-fixture seam of [[ArchiveRuns]] (CR-P6-21 n1).
 * Synthetic curated runs have synthetic run IDs, so the fixtures pin their own run ID here; main
 * code has no such access and always uses [[HadoopCuratedRun.PinnedRunId]] through
 * [[ArchiveRuns.of]].
 */
object SyntheticCuratedPin {
  def archiveRuns(
      archiveRoot: String,
      curatedRun: String,
      decisionRun: String,
      groupingRun: String,
      pinnedCuratedRunId: String
  ): Either[Vector[String], ArchiveRuns] =
    ArchiveRuns.forSyntheticFixture(
      archiveRoot,
      curatedRun,
      decisionRun,
      groupingRun,
      pinnedCuratedRunId
    )
}
