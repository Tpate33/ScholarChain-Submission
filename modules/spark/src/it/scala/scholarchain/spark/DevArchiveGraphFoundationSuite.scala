package scholarchain.spark

import scholarchain.spark.testkit.DevArchiveGraphFoundationChecks

/**
 * First archive-backed integration suite (SP-P6-02 step 1), run only by `sbt sparkIT`. Its body is
 * [[scholarchain.spark.testkit.DevArchiveGraphFoundationChecks]] (abstract, in the Test tree, so
 * routine `sbt check` compiles it without running it). Six tests: verified reader and pinned run
 * IDs; graph input; per-window counts; AnalysisValidation (checkEdges, checkRunChain); written run
 * under outputDir re-verified; benchmark pair binding. DEV inputs, unevaluated.
 */
class DevArchiveGraphFoundationSuite extends DevArchiveGraphFoundationChecks
