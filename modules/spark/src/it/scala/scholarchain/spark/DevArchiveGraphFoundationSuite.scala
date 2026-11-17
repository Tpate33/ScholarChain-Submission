package scholarchain.spark

import scholarchain.spark.testkit.DevArchiveGraphFoundationChecks

/**
 * Archive-backed integration suite (SP-P6-02 steps 1A and 1B), run only by `sbt sparkIT`. Its body
 * is [[scholarchain.spark.testkit.DevArchiveGraphFoundationChecks]] (abstract, in the Test tree, so
 * routine `sbt check` compiles it without running it). Nine tests: verified reader and pinned run
 * IDs; graph input; per-window counts; AnalysisValidation (checkEdges, checkMetrics, checkIdentity,
 * checkRunChain); written run under outputDir re-verified; benchmark pair binding; hand-derived
 * metric totals per window; hand-derived metrics of four researchers; logical reproducibility of
 * two in-run builds. DEV inputs, unevaluated.
 */
class DevArchiveGraphFoundationSuite extends DevArchiveGraphFoundationChecks
