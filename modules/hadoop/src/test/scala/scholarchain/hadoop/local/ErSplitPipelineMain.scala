package scholarchain.hadoop.local

import java.nio.file.Paths

/**
 * Per-split local pipeline over the ER candidate samples (work order HD-P5-02). Test scope, so that
 * the Provided Hadoop jars and the forked test JVM options apply. Exactly one argument, `dev` or
 * `heldout`; everything else is fixed (see [[ErSplit]]):
 *
 *   - inputs:
 *     `datasets/samples/er-candidates/<split>/{dblp,openalex-works,openalex-authors,crossref-works}`
 *   - manifest: `modules/hadoop/local-runs/er-candidates.<split>.run-manifest.json` (committed);
 *     its snapshots are verified against `datasets/dataset-manifest.yaml` (HD-P5-04, D13 option D)
 *   - outputs: `modules/hadoop/target/local-pipeline/er-candidates/<split>/attempt-<n>/`
 *
 * The repository root is found by walking up from the working directory, so the entry point works
 * whether sbt forks it in the repository root or in `modules/hadoop`. Exit codes: see
 * [[ErSplitPipeline]].
 */
object ErSplitPipelineMain {
  def main(args: Array[String]): Unit = {
    val code = ErSplit.findRepoRoot(Paths.get("")) match {
      case None =>
        println(
          s"${ErSplit.Prefix} status=refused reason=repository_root_not_found_from_${Paths.get("").toAbsolutePath}"
        )
        2
      case Some(root) =>
        println(s"${ErSplit.Prefix} repoRoot=$root")
        ErSplitPipeline.run(args.toVector, ErSplitEnv.fixed(root), System.out)
    }
    System.out.flush()
    System.exit(code)
  }
}
