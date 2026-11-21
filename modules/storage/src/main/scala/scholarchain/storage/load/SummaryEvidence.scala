package scholarchain.storage.load

import scholarchain.core.{
  AnalysisOutputs,
  AnalysisValidation,
  EntitySummaryInput,
  GraphEdgeV2,
  GraphInput,
  GraphNodeV2,
  RunId,
  RunManifest,
  SemanticSummaryInput,
  StructureValidation,
  ValidationError,
  WorkAssignment,
  WorkRecord
}
import scholarchain.core.config.AnalysisRunSettings

/**
 * The decoded datasets the summary-evidence check needs (SE-P7-05 A1, CR-P7-05 F1). The analysis
 * run directory holds `summary-inputs`, `entity-summary-inputs`, the analysis outputs and the
 * `graph-nodes` / `graph-edges` tables; `graphInput`, `works` and `sourceWorks` come from the
 * grouping run and the curated input run the analysis run names (run-manifest parameters
 * `groupingRunId`, `inputRunId`), which are not in the analysis run directory.
 */
final case class SummaryEvidenceInputs(
    summaryInputs: Vector[SemanticSummaryInput],
    outputs: AnalysisOutputs,
    graphInput: GraphInput,
    entitySummaryInputs: Vector[EntitySummaryInput],
    nodes: Vector[GraphNodeV2],
    edges: Vector[GraphEdgeV2],
    works: Vector[WorkAssignment],
    sourceWorks: Vector[WorkRecord],
    settings: AnalysisRunSettings
)

/**
 * Deferred check `summary-evidence` (SE-P7-05 A1): every semantic-summary input and every entity
 * summary input cites only records that exist in the run and states exactly their values, by the
 * existing core validators, unchanged: [[AnalysisValidation.checkSummaryInputs]] (subjects and
 * cited IDs resolve to stored records of the same window, facts equal the computed values) and
 * [[StructureValidation.checkEntitySummaries]] (subjects are Work or Topic nodes, cited graph
 * nodes, graph edges and source works resolve, facts follow the frozen vocabulary).
 *
 * Performed by [[ParquetChecks]] (verifier step 12) over the decoded Parquet records of the run and
 * a bound [[SummaryUpstream]] (SE-P7-06 item 6), read and verified from the grouping and curated
 * run directories by [[UpstreamRuns]] (SE-P7-07). Without an upstream, summary-evidence is NOT
 * performed and the gate refuses every store it applies to ([[DeferredCheck.appliesTo]]).
 */
object SummaryEvidenceCheck {

  /**
   * Why `up` is not the upstream of the analysis run of `analysisManifest` (SE-P7-06 item 6), or
   * empty: the grouping manifest's run ID is the analysis manifest's `groupingRunId`, the graph
   * input passes [[AnalysisValidation.checkGraphInput]] against that manifest and is not empty, the
   * curated run's ID and the grouping manifest's `inputRunId` and `decisionRunId` are the analysis
   * manifest's, and every source work carries the analysis manifest's `inputRunId`.
   */
  def bound(up: SummaryUpstream, analysisManifest: RunManifest): Vector[String] = {
    val K = AnalysisValidation.AnalysisManifestKeys
    val params = Option(analysisManifest.parameters).getOrElse(Map.empty[String, String])
    val G = AnalysisValidation.GroupingManifestKeys
    if (up == null || up.graphInput == null || up.sourceWorks == null)
      Vector("upstream: missing")
    else
      (RunId.of(up.groupingManifest) match {
        case Left(e) => Vector(s"groupingManifest: ${e.message}")
        case Right(g) =>
          if (params.get(K.GroupingRunId).contains(g)) Vector.empty
          else Vector(s"groupingManifest: run $g is not the analysis run's groupingRunId")
      }) ++
        // SE-P7-07: the curated run is the analysis run's (and the grouping run's) inputRunId.
        (if (params.get(K.InputRunId).contains(up.curated.runId)) Vector.empty
         else Vector(s"curated run ${up.curated.runId} is not the analysis run's inputRunId")) ++
        (if (
           Option(up.groupingManifest.parameters).flatMap(_.get(G.InputRunId)) ==
             params.get(K.InputRunId)
         ) Vector.empty
         else Vector("groupingManifest: its inputRunId is not the analysis run's inputRunId")) ++
        (if (
           Option(up.groupingManifest.parameters).flatMap(_.get(G.DecisionRunId)) ==
             params.get(K.DecisionRunId)
         ) Vector.empty
         else
           Vector("groupingManifest: its decisionRunId is not the analysis run's decisionRunId")) ++
        // CR-P7-08 m2: an empty graph input never passes (the core check accepts one).
        (if (
           up.graphInput.teams.nonEmpty && up.graphInput.works.nonEmpty &&
           up.graphInput.researchers.nonEmpty && up.sourceWorks.nonEmpty
         ) Vector.empty
         else Vector("upstream: an empty graph input or curated work set is never accepted")) ++
        AnalysisValidation.checkGraphInput(up.graphInput, up.groupingManifest).map(_.message) ++
        up.sourceWorks.zipWithIndex.flatMap { case (w, i) =>
          if (
            w != null && Option(w.provenance)
              .exists(p => params.get(K.InputRunId).contains(p.runId))
          )
            Vector.empty
          else Vector(s"sourceWorks[$i]: provenance.runId is not the analysis run's inputRunId")
        }
  }

  /** Every summary-evidence problem; empty when every summary input is grounded. */
  def check(in: SummaryEvidenceInputs): Vector[ValidationError] =
    AnalysisValidation.checkSummaryInputs(
      in.summaryInputs,
      in.outputs,
      in.graphInput,
      in.settings
    ) ++ StructureValidation.checkEntitySummaries(
      in.entitySummaryInputs,
      in.nodes,
      in.edges,
      in.works,
      in.sourceWorks,
      in.settings
    )
}
