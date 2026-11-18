package scholarchain.spark.input

import scholarchain.spark.job.CollaborationJob
import scholarchain.spark.store.RunStore

/**
 * Test-only access to the `private[input]` synthetic-fixture seams of [[ArchiveRuns]] and
 * [[ArchiveInput]] (CR-P6-21 n1). Synthetic curated runs have synthetic run IDs, so the fixtures
 * pin their own run ID here; main code has no such access and always uses
 * [[HadoopCuratedRun.PinnedRunId]] through [[ArchiveRuns.of]] and [[ArchiveInput.of]].
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

  def archiveInput(
      store: RunStore,
      archiveRoot: String,
      curatedRun: String,
      decisionRun: String,
      groupingRun: String,
      pinnedCuratedRunId: String
  ): Either[Vector[String], ArchiveInput] =
    ArchiveInput.forSyntheticFixture(
      store,
      archiveRoot,
      curatedRun,
      decisionRun,
      groupingRun,
      pinnedCuratedRunId
    )

  /** An archive opener for the job's synthetic-fixture suite, pinned to the fixture's run ID. */
  def opener(pinnedCuratedRunId: String): CollaborationJob.ArchiveOpener =
    (store, root, curated, decision, grouping) =>
      archiveInput(store, root, curated, decision, grouping, pinnedCuratedRunId)
}
