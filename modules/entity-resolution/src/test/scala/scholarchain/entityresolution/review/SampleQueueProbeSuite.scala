package scholarchain.entityresolution.review

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import scholarchain.entityresolution.policy.PolicyFixtures

/**
 * Report probe over the committed samples. It reads the curated outputs that the Hadoop local
 * bounded pipeline suite (`LocalPipelineSuite`, over every file in `datasets/samples`) leaves in
 * `modules/hadoop/target/test-work/local-pipeline/curated-a` as DATA (no Hadoop code is imported),
 * resolves every candidate with the default policy (no LLM call), and prints the per-stratum
 * (dev-diagnostic, outcome-stratified; never held-out gold membership) availability of review-queue
 * pairs. It writes the unlabeled queue to `modules/entity-resolution/target/review-queue/` (git
 * ignored). When the Hadoop outputs are absent or incomplete (for example on a clean tree, or while
 * the Hadoop suite is still running) it is skipped; it asserts invariants only, never counts.
 */
class SampleQueueProbeSuite extends munit.FunSuite {

  test("sample review-queue strata (probe; skipped without Hadoop local-pipeline outputs)") {
    val root =
      PolicyFixtures.repoRoot.resolve("modules/hadoop/target/test-work/local-pipeline/curated-a")
    val runs = CuratedRunReader.runDirs(root)
    assume(
      runs.size == 1,
      s"expected one completed local-pipeline run under $root, found ${runs.size}"
    )
    val run = CuratedRunReader.read(runs.head)
    assume(run.isRight, run.left.getOrElse(""))
    val curated = run.toOption.get
    // The Phase 4 samples are outside the DS-P5-01 selection, so the dev-only guard admits them.
    val units = SelectionUnits
      .load(
        PolicyFixtures.repoRoot.resolve("datasets/er-candidates/split-assignment.tsv"),
        PolicyFixtures.repoRoot.resolve("datasets/samples/er-candidates/PROVENANCE.tsv")
      )
      .fold(e => fail(e.mkString("; ")), identity)
    val r = RunQueue
      .resolveDiagnostics(PolicyFixtures.policy, curated, SamplingDesign.Default, units)
      .fold(e => fail(e.mkString("; ")), identity)
    println(s"[er-sample-queue] run ${runs.head.getFileName}")
    RunQueue.summary(r).foreach(l => println(s"[er-sample-queue] $l"))
    assertEquals(r.outcomes.size + r.refused.size, curated.candidates.size)
    assert(r.build.rows.forall(_.label.isEmpty))
    assert(
      r.build.rows.forall(x =>
        x.endpointA.sourceRecordId.nonEmpty && x.endpointB.sourceRecordId.nonEmpty
      )
    )
    assert(
      r.build.rows.forall(x => !(x.system.outcome == "ACCEPTED" && x.system.conflicts.nonEmpty))
    )
    val blind = RunQueue.blind(r, curated, SamplingDesign.Default.seed)
    assert(blind.isRight, blind.left.getOrElse(Vector.empty).mkString("; "))
    val out = PolicyFixtures.repoRoot.resolve("modules/entity-resolution/target/review-queue")
    Files.createDirectories(out)
    val file = out.resolve("er-review-queue.samples.internal.jsonl")
    Files.writeString(file, ReviewQueue.toJsonl(r.build.rows), StandardCharsets.UTF_8)
    val blindFile = out.resolve("er-blind-label-view.samples.jsonl")
    Files.writeString(blindFile, BlindView.toJsonl(blind.toOption.get), StandardCharsets.UTF_8)
    println(s"[er-sample-queue] wrote ${r.build.rows.size} internal rows to $file")
    println(s"[er-sample-queue] wrote ${blind.toOption.get.size} blind rows to $blindFile")
  }
}
