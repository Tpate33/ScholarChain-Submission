package scholarchain.spark

import scholarchain.spark.testkit.DevArchiveGraphFoundationChecks

/**
 * Archive-backed integration suite (SP-P6-02 steps 1A and 1B, SP-P6-03 steps 2A, 2B and 2C), run
 * only by `sbt sparkIT`. Its body is [[scholarchain.spark.testkit.DevArchiveGraphFoundationChecks]]
 * (abstract, in the Test tree, so routine `sbt check` compiles it without running it). Fourteen
 * tests: verified reader and pinned run IDs; curated sources and authorships (step 2B); graph
 * input; per-window counts; AnalysisValidation (checkEdges, checkMetrics, checkIdentity,
 * checkComponents, checkCommunities, checkBridges, checkCoreness, checkRunChain); written run under
 * outputDir re-verified; benchmark pair binding; hand-derived metric totals per window;
 * hand-derived metrics of four researchers; hand-derived structure facts; canonical tables (step
 * 2B); window structures (step 2B); paths, path-length estimates, temporal comparison, summaries
 * and load manifests (step 2C); logical reproducibility of two in-run builds (every Parquet and
 * Neptune CSV dataset). DEV inputs, unevaluated.
 */
class DevArchiveGraphFoundationSuite extends DevArchiveGraphFoundationChecks
